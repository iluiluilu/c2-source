package com.aismartstore.backend.systemconfig;

import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.NotFoundException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runtime store for admin-tunable business parameters (FR-01).
 *
 * <p>Reads: {@code get*} resolve Redis -> DB -> static default, never throwing
 * out to the caller (Story 6). Writes: DB (source of truth) then Redis then one
 * audit event (Story 2/4). Sensitive keys go through a two-step confirm token
 * held in Redis (Story 3). Rollback reuses the write path with a distinct audit
 * action (Story 5).
 */
@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);

    /** Redis key prefix for cached values. */
    private static final String CACHE_PREFIX = "sysconfig:";
    /** Redis key prefix for pending two-step confirmations. */
    private static final String CONFIRM_PREFIX = "sysconfig:confirm:";
    /** Backstop TTL for cached values; the real refresh is on every write. */
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    /** Two-step confirmation token lifetime (spec §3). */
    private static final Duration CONFIRM_TTL = Duration.ofMinutes(5);

    public static final String RESOURCE_TYPE = "system_config";
    public static final String ACTION_UPDATE = "system_config.update";
    public static final String ACTION_ROLLBACK = "system_config.rollback";

    private final SystemConfigRepository repository;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final AuditLogRepository auditLogRepository;
    private final StringRedisTemplate redis;

    /**
     * Self-reference used only to call {@link #persist} through the Spring
     * AOP proxy instead of {@code this.persist(...)}. A plain {@code this}
     * call is a same-class "self-invocation", which silently bypasses the
     * proxy and therefore {@link #persist}'s {@code @Transactional} - found
     * via {@code SystemConfigControllerIT} (real Postgres): without a real
     * transaction boundary around the {@code system_config} read+write,
     * Hibernate's session ends up flushing a stray {@code UPDATE} against the
     * very next unrelated entity ({@code audit_log}, written a few lines
     * later by {@link #apply}), which the append-only trigger rejects and the
     * whole write fails. Defaults to {@code this} so unit tests that
     * construct this class directly (no Spring context, no proxy) keep
     * working unchanged; Spring overwrites it with the real (lazily
     * resolved, to avoid a circular-bean-creation error) proxied bean.
     */
    private SystemConfigService self;

    public SystemConfigService(
            SystemConfigRepository repository,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            AuditLogRepository auditLogRepository,
            StringRedisTemplate redis) {
        this.repository = repository;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.auditLogRepository = auditLogRepository;
        this.redis = redis;
        this.self = this;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy SystemConfigService self) {
        this.self = self;
    }

    // ---- API projections ------------------------------------------------

    /** One catalogue row for GET /admin/system-config (Story 1). */
    public record ConfigView(
            String key,
            String description,
            ValueType valueType,
            String unit,
            Object value,
            Object defaultValue,
            boolean usingDefault,
            boolean sensitive) {
    }

    /** Result of a successful write. */
    public record UpdateResult(String key, Object value, OffsetDateTime updatedAt) {
    }

    /** One history entry for GET /{key}/history (Story 5 AC-1). */
    public record HistoryEntry(Object oldValue, Object newValue, String actor, OffsetDateTime createdAt) {
    }

    // ---- read (runtime consumers, Story 6) ------------------------------

    /**
     * Resolve a key's effective value: Redis -> DB -> static default. Any
     * failure of Redis or DB degrades to the next tier; a fully unresolved
     * known key falls back to its static default and logs a warning, never
     * throwing (Story 6 AC-1/AC-2).
     */
    public SystemConfigValue get(String key) {
        SystemConfigDef def = SystemConfigDefaults.get(key);
        if (def == null) {
            throw new IllegalArgumentException("Unknown system config key: " + key);
        }

        // 1) Redis
        try {
            String cached = redis.opsForValue().get(CACHE_PREFIX + key);
            if (cached != null) {
                return SystemConfigValue.fromStored(def.valueType(), cached);
            }
        } catch (RuntimeException e) {
            log.warn("Redis read failed for system config '{}', falling back to DB: {}", key, e.toString());
        }

        // 2) DB
        try {
            Optional<SystemConfig> row = repository.findById(key);
            if (row.isPresent()) {
                String value = row.get().getValue();
                // best-effort re-populate cache
                trySetCache(key, value);
                return SystemConfigValue.fromStored(def.valueType(), value);
            }
        } catch (RuntimeException e) {
            log.warn("DB read failed for system config '{}', falling back to default: {}", key, e.toString());
        }

        // 3) static default
        return SystemConfigValue.fromStored(def.valueType(), def.defaultValue());
    }

    public String getString(String key) {
        return get(key).asString();
    }

    public long getLong(String key) {
        return get(key).asLong();
    }

    public boolean getBoolean(String key) {
        return get(key).asBoolean();
    }

    public <T> T getJson(String key, Class<T> type) {
        return get(key).asJson(type);
    }

    // ---- read (admin catalogue, Story 1) --------------------------------

    /**
     * Full catalogue with resolved value, default and {@code usingDefault}
     * flag per key. Reads DB directly (admin view, not the cached runtime
     * path) so the flag reflects the true stored state.
     */
    public List<ConfigView> listAll() {
        Map<String, SystemConfig> rows = new LinkedHashMap<>();
        try {
            for (SystemConfig row : repository.findAll()) {
                rows.put(row.getKey(), row);
            }
        } catch (RuntimeException e) {
            log.warn("DB read failed for system config catalogue, showing defaults only: {}", e.toString());
        }

        List<ConfigView> views = new ArrayList<>();
        for (SystemConfigDef def : SystemConfigDefaults.all().values()) {
            SystemConfig row = rows.get(def.key());
            boolean usingDefault = row == null;
            String rawValue = usingDefault ? def.defaultValue() : row.getValue();
            views.add(new ConfigView(
                    def.key(),
                    def.description(),
                    def.valueType(),
                    def.unit(),
                    typed(def.valueType(), rawValue),
                    typed(def.valueType(), def.defaultValue()),
                    usingDefault,
                    def.sensitive()));
        }
        return views;
    }

    // ---- write (Story 2/3/4) --------------------------------------------

    /**
     * Apply a change to {@code key}. For a non-sensitive key the value is
     * validated and applied immediately. For a sensitive key nothing is
     * written; instead a confirmation token is issued and a
     * {@code 409 CONFIRMATION_REQUIRED} is thrown (Story 3, step 1). Callers
     * then re-submit via {@link #confirm}.
     */
    public UpdateResult update(String key, Object rawClientValue, KeycloakUser actor) {
        SystemConfigDef def = requireKnown(key);
        SystemConfigValue proposed = convertAndValidate(def, rawClientValue);

        if (def.sensitive()) {
            String token = issueConfirmToken(key, proposed);
            throw confirmationRequired(token);
        }

        return apply(def, proposed, actor, ACTION_UPDATE);
    }

    /**
     * Complete a sensitive change (Story 3 AC-3): looks up the pending value by
     * token, applies it, and consumes the token. A missing/expired/mismatched
     * token yields {@code 410}.
     */
    public UpdateResult confirm(String key, String confirmationToken, KeycloakUser actor) {
        SystemConfigDef def = requireKnown(key);
        String pending = consumeConfirmToken(key, confirmationToken);
        if (pending == null) {
            throw gone();
        }
        SystemConfigValue proposed = SystemConfigValue.fromStored(def.valueType(), pending);
        return apply(def, proposed, actor, ACTION_UPDATE);
    }

    /**
     * Rollback to a prior value (Story 5). Sensitive keys still require the
     * two-step confirm (Story 5 AC-2): without a valid token a
     * {@code 409 CONFIRMATION_REQUIRED} is issued for {@code toValue}; the FE
     * then calls {@link #confirm} to finish. The audit action is
     * {@code system_config.rollback} to distinguish from a normal edit.
     */
    public UpdateResult rollback(String key, Object toValue, String confirmationToken, KeycloakUser actor) {
        SystemConfigDef def = requireKnown(key);

        if (def.sensitive()) {
            if (confirmationToken == null || confirmationToken.isBlank()) {
                SystemConfigValue proposed = convertAndValidate(def, toValue);
                String token = issueConfirmToken(key, proposed);
                throw confirmationRequired(token);
            }
            String pending = consumeConfirmToken(key, confirmationToken);
            if (pending == null) {
                throw gone();
            }
            SystemConfigValue proposed = SystemConfigValue.fromStored(def.valueType(), pending);
            return apply(def, proposed, actor, ACTION_ROLLBACK);
        }

        SystemConfigValue proposed = convertAndValidate(def, toValue);
        return apply(def, proposed, actor, ACTION_ROLLBACK);
    }

    // ---- history (Story 5 AC-1) ------------------------------------------

    public List<HistoryEntry> history(String key) {
        requireKnown(key);
        List<AuditLog> rows = auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(RESOURCE_TYPE, key);
        List<HistoryEntry> entries = new ArrayList<>(rows.size());
        for (AuditLog row : rows) {
            Map<String, Object> meta = row.getMetadata();
            Object oldValue = meta != null ? meta.get("oldValue") : null;
            Object newValue = meta != null ? meta.get("newValue") : null;
            entries.add(new HistoryEntry(oldValue, newValue, row.getActor(), row.getCreatedAt()));
        }
        return entries;
    }

    // ---- internals ------------------------------------------------------

    private SystemConfigDef requireKnown(String key) {
        SystemConfigDef def = SystemConfigDefaults.get(key);
        if (def == null) {
            throw new NotFoundException("Unknown system config key: " + key);
        }
        return def;
    }

    private SystemConfigValue convertAndValidate(SystemConfigDef def, Object rawClientValue) {
        SystemConfigValue proposed;
        try {
            proposed = SystemConfigValue.of(def.valueType(), rawClientValue);
        } catch (SystemConfigValue.InvalidValueException e) {
            throw invalidValue(def.key(), e.getMessage());
        }
        try {
            SystemConfigValidator.validate(def.key(), proposed, this::currentValueForValidation);
        } catch (SystemConfigValidator.ConstraintViolationException e) {
            throw constraintViolation(def.key(), e.getMessage());
        }
        return proposed;
    }

    /** Current effective value of a related key for cross-parameter validation. */
    private SystemConfigValue currentValueForValidation(String key) {
        SystemConfigDef def = SystemConfigDefaults.get(key);
        try {
            Optional<SystemConfig> row = repository.findById(key);
            if (row.isPresent()) {
                return SystemConfigValue.fromStored(def.valueType(), row.get().getValue());
            }
        } catch (RuntimeException e) {
            log.warn("DB read failed resolving related key '{}' for validation: {}", key, e.toString());
        }
        return SystemConfigValue.fromStored(def.valueType(), def.defaultValue());
    }

    /**
     * Persist (DB), then cache (Redis), then audit. The DB write is the
     * transactional source of truth; a Redis failure is logged but does not
     * roll back or fail the request (spec §4.2 step 9).
     */
    private UpdateResult apply(SystemConfigDef def, SystemConfigValue proposed, KeycloakUser actor, String action) {
        Object oldValue = readCurrentTypedForAudit(def);

        // via `self`, not `this` - see the field javadoc: a same-class call
        // here would silently skip persist()'s @Transactional.
        OffsetDateTime updatedAt = self.persist(def, proposed, actor);

        trySetCache(def.key(), proposed.raw());

        Object newTyped = typed(def.valueType(), proposed.raw());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", def.key());
        metadata.put("oldValue", oldValue);
        metadata.put("newValue", newTyped);
        auditService.record(new AuditService.AuditEntry(
                actor != null ? actor.preferredUsername() : "system",
                actor != null ? actor.sub() : null,
                action,
                RESOURCE_TYPE,
                def.key(),
                "success",
                auditRequestContext.method(),
                auditRequestContext.path(),
                200,
                auditRequestContext.ip(),
                metadata));

        return new UpdateResult(def.key(), newTyped, updatedAt);
    }

    /**
     * Upsert the row. A single {@code save} is its own transaction and
     * commits before the subsequent Redis/audit steps, matching the spec's
     * "DB first, then cache, then audit" ordering. Concurrent writes to the
     * same key are last-write-wins (no optimistic lock) - sufficient for the
     * MVP's small, rarely-edited parameter set.
     */
    @Transactional
    public OffsetDateTime persist(SystemConfigDef def, SystemConfigValue proposed, KeycloakUser actor) {
        SystemConfig row = repository.findById(def.key()).orElseGet(SystemConfig::new);
        row.setKey(def.key());
        row.setValue(proposed.raw());
        row.setValueType(def.valueType());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        row.setUpdatedAt(now);
        row.setUpdatedBy(actor != null ? actor.preferredUsername() : null);
        repository.save(row);
        return now;
    }

    /** Current stored-or-default value, typed, for the audit oldValue field. */
    private Object readCurrentTypedForAudit(SystemConfigDef def) {
        try {
            Optional<SystemConfig> row = repository.findById(def.key());
            if (row.isPresent()) {
                return typed(def.valueType(), row.get().getValue());
            }
        } catch (RuntimeException e) {
            log.warn("DB read failed resolving old value for audit '{}': {}", def.key(), e.toString());
        }
        return typed(def.valueType(), def.defaultValue());
    }

    // ---- confirm token (Story 3) ----------------------------------------

    private String issueConfirmToken(String key, SystemConfigValue proposed) {
        String token = UUID.randomUUID().toString();
        try {
            redis.opsForValue().set(CONFIRM_PREFIX + key + ":" + token, proposed.raw(), CONFIRM_TTL);
        } catch (RuntimeException e) {
            log.error("Failed to store confirmation token for '{}': {}", key, e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể khởi tạo xác nhận 2 bước (cache không khả dụng)");
        }
        return token;
    }

    /** Returns the pending value and deletes the token, or null if absent. */
    private String consumeConfirmToken(String key, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String redisKey = CONFIRM_PREFIX + key + ":" + token;
        try {
            String pending = redis.opsForValue().get(redisKey);
            if (pending != null) {
                redis.delete(redisKey);
            }
            return pending;
        } catch (RuntimeException e) {
            log.error("Failed to read confirmation token for '{}': {}", key, e.toString());
            return null;
        }
    }

    private void trySetCache(String key, String value) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + key, value, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("Failed to cache system config '{}' in Redis: {}", key, e.toString());
        }
    }

    /** Convert a stored textual value to its typed JSON representation. */
    private static Object typed(ValueType type, String raw) {
        SystemConfigValue v = SystemConfigValue.fromStored(type, raw);
        return switch (type) {
            case STRING -> v.asString();
            case NUMBER -> v.asLong();
            case BOOLEAN -> v.asBoolean();
            case JSON -> v.asJsonNode();
        };
    }

    // ---- error bodies (match spec §3 API contract) ----------------------

    private static ApiException invalidValue(String key, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_VALUE");
        body.put("message", message);
        body.put("key", key);
        return new ApiException(HttpStatus.BAD_REQUEST, body, message);
    }

    private static ApiException constraintViolation(String key, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CONSTRAINT_VIOLATION");
        body.put("message", message);
        body.put("key", key);
        return new ApiException(HttpStatus.BAD_REQUEST, body, message);
    }

    private static ApiException confirmationRequired(String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CONFIRMATION_REQUIRED");
        body.put("confirmationToken", token);
        return new ApiException(HttpStatus.CONFLICT, body, "Confirmation required");
    }

    private static ApiException gone() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CONFIRMATION_EXPIRED");
        body.put("message", "Mã xác nhận không hợp lệ hoặc đã hết hạn");
        return new ApiException(HttpStatus.GONE, body, "Confirmation token invalid or expired");
    }
}
