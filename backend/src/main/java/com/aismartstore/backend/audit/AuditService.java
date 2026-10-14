package com.aismartstore.backend.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Append-only, hash-chained activity log service (FR-09 / NFR-09). Java port
 * of the NestJS backend's audit/audit.service.ts, reworked per
 * docs/03-dev/specs/audit-logging.md: {@link #record} no longer takes a
 * global lock or computes the chain synchronously (§4.1) - that is now
 * {@link AuditSealerJob}'s job, running against rows this class leaves with
 * {@code hash = NULL}.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * Request attribute set by {@link #markRecordedOnCurrentRequest()} so
     * {@code AuditInterceptor} (fallback-only, §4.3) and {@code
     * ApiExceptionHandler} (business-4xx audit, §4.7) both know a manual
     * audit entry already exists for the current request and must not write
     * a duplicate one.
     */
    public static final String AUDIT_RECORDED_ATTRIBUTE = "AUDIT_RECORDED";

    /**
     * JavaTimeModule: request metadata may carry {@code LocalDate}/{@code OffsetDateTime} values (e.g. the search
     * filters' {@code uploadedFrom/uploadedTo}) - without it every such entry failed to normalize and was never
     * written (FR-09 gap). Dates are written as ISO-8601 strings, which also keeps the sealing JSON readable.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * classification-notes-02-notes.md §4 step 6 / §8 câu hỏi #2 (đã CHỐT):
     * opt-in allowlist of {@code metadata} keys per {@code action}
     * (a.k.a. {@code eventType}). Only actions registered here are checked -
     * every other action keeps today's behaviour unchanged.
     */
    private static final Map<String, Set<String>> ALLOWED_METADATA_KEYS = Map.ofEntries(
            Map.entry("note.create", Set.of("documentId", "noteId")),
            Map.entry("note.update", Set.of("documentId", "noteId")),
            // search-fulltext-04-dong-bo-chi-muc.md Task 7: `query` is already truncated to
            // <=200 chars by the caller (SearchServiceImpl) before it ever reaches this
            // allowlist - it is never relied upon here to bound the value's length.
            Map.entry("search.executed", Set.of("query", "filters", "resultCount", "sort", "droppedGroupIds",
                    // search-semantic T3 (AC-7.1): FR-24 queries are audited under the same action.
                    "mode", "degradedMode", "minScore")),
            // digitization-review-03-ownership-transfer.md §2: written synchronously
            // (record(), not recordAsync) by DocumentOwnershipService.transfer().
            Map.entry("document.ownership_transferred", Set.of("fromUploaderSub", "toUploaderSub", "reason")),
            // digitization-review-01-ocr.md §4 bước 3 / BA AC-6 US2: edited/added/deleted segment
            // text must never reach audit_log, only a magnitude signal - see
            // DigitizationRunLifecycleServiceImpl.charsChanged().
            Map.entry("digitization_review.segment_revised",
                    Set.of("segmentId", "revisionNo", "charsChanged", "editedBy", "editedAt")),
            Map.entry("digitization_review.segment_deleted",
                    Set.of("segmentId", "revisionNo", "charsChanged", "editedBy", "editedAt")),
            Map.entry("digitization_review.segment_added",
                    Set.of("segmentId", "revisionNo", "charsChanged", "editedBy", "editedAt")),
            // digitization-review-01-ocr.md §4 bước 4: "ghi audit_log(digitization_review.confirmed)
            // (actor, documentId, runId, confirmSource)" - exact metadata list from the spec.
            Map.entry("digitization_review.confirmed", Set.of("documentId", "runId", "confirmSource")),
            // export-reporting SPEC T3 (AC-34/35): chỉ id/tham số đã chuẩn hoá + số đo - KHÔNG số liệu,
            // KHÔNG tên nhãn/nhóm/thư mục/tài liệu.
            Map.entry("report.generate",
                    Set.of("template_code", "params", "row_count", "duration_ms")),
            Map.entry("report.export",
                    Set.of("template_code", "format", "params", "row_count", "duration_ms", "export_id",
                            "result", "retry_of")),
            Map.entry("report.download", Set.of("template_code", "format", "export_id")),
            Map.entry("report.access_denied", Set.of("permission", "reason", "export_id", "template_code")));

    private final AuditLogRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public AuditService(AuditLogRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Fields a caller supplies; the service fills in createdAt. hash/prevHash/sealedAt are filled later by AuditSealerJob. */
    public record AuditEntry(
            String actor,
            String actorSub,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String method,
            String path,
            Integer statusCode,
            String ip,
            Map<String, Object> metadata) {
    }

    public record ChainVerification(boolean ok, long count, Long brokenAtId, String reason) {
    }

    /**
     * Append one entry (audit-logging.md §4.1, Story 1 AC-3/AC-4): plain
     * INSERT, no advisory lock, no read-tail-then-compute-hash - hash/
     * prevHash/sealedAt are left NULL for {@link AuditSealerJob} to fill in
     * a few seconds later. Still {@code @Transactional} and still runs
     * inside the caller's own business transaction, so a failure here still
     * rolls back the business operation that triggered it (AC-3); it is
     * simply no longer serialized against every other concurrent audit
     * write (AC-4).
     */
    @Transactional
    public void record(AuditEntry entry) {
        AuditLog row = new AuditLog();
        row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        row.setActor(entry.actor());
        row.setActorSub(entry.actorSub());
        row.setAction(entry.action());
        row.setResourceType(entry.resourceType());
        row.setResourceId(entry.resourceId());
        row.setOutcome(entry.outcome());
        row.setMethod(entry.method());
        row.setPath(entry.path());
        row.setStatusCode(entry.statusCode());
        row.setIp(entry.ip());
        row.setMetadata(normalizeMetadata(enforceMetadataAllowlist(entry.action(), entry.metadata())));
        row.setPrevHash(null);
        row.setHash(null);
        row.setSealedAt(null);

        repository.save(row);
        markRecordedOnCurrentRequest();
    }

    /**
     * Same write as {@link #record}, but in its own transaction that commits (or rolls back)
     * independently of whatever transaction the caller is in - for the one shape {@link #record}'s
     * own javadoc doesn't cover: a caller that writes a <b>denial/rejection</b> entry and then throws
     * from inside its own {@code @Transactional} method, in the same transaction, with no business
     * mutation to protect. There, {@link #record}'s ordinary REQUIRED propagation joins that
     * transaction, so the throw right after rolls the just-written audit row back too - the caller
     * ends up looking audited but leaves zero rows in {@code audit_log}. (Found via
     * delete-restore-trash-01-xoa-mem-thung-rac.md Story 1 AC-6: {@code TrashService.trash()}'s
     * forbidden-batch branch was exactly this - {@code document.trash.denied} written, then {@code
     * throw forbiddenDocuments(...)} discarded it.) Not a substitute for {@link #record} in general:
     * Story 1 AC-3 deliberately wants an audit-write failure to roll back a real business mutation, and
     * that coupling only works with REQUIRED - use this only for a denial/rejection entry that has no
     * mutation of its own to protect.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditEntry entry) {
        record(entry);
    }

    /**
     * audit-logging.md §4.3: called from inside {@link #record} (the
     * synchronous path only - {@link #recordAsync} still goes through
     * {@link #record} first, so this still fires for it) so that every
     * manual audit call for the current request marks it as already
     * covered, and {@code AuditInterceptor}'s fallback does not write a
     * duplicate. A no-op outside a servlet request (background jobs).
     */
    public void markRecordedOnCurrentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(AUDIT_RECORDED_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private static Map<String, Object> enforceMetadataAllowlist(String action, Map<String, Object> metadata) {
        Set<String> allowedKeys = ALLOWED_METADATA_KEYS.get(action);
        if (allowedKeys == null || metadata == null || metadata.isEmpty()) {
            return metadata;
        }

        Set<String> disallowedKeys = new LinkedHashSet<>(metadata.keySet());
        disallowedKeys.removeAll(allowedKeys);
        if (disallowedKeys.isEmpty()) {
            return metadata;
        }

        log.error(
                "Audit metadata for action={} contained disallowed key(s) {} - dropped before write "
                        + "(values never logged)",
                action,
                disallowedKeys);

        Map<String, Object> sanitized = new HashMap<>(metadata);
        disallowedKeys.forEach(sanitized::remove);
        return sanitized;
    }

    /**
     * Round-trip {@code metadata} through JSON before handing it to
     * Hibernate - see the extensive javadoc on the pre-refactor version of
     * this method (git history) for the Hibernate dirty-check bug this
     * avoids. Still needed: {@code record()} still builds a managed entity
     * via {@code repository.save}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            String json = JSON.writeValueAsString(metadata);
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to normalize audit log metadata", e);
        }
    }

    /**
     * Non-blocking variant for request-path callers (the audit interceptor):
     * a slow or failed write must never delay the HTTP response, but per
     * FR-09 a failure must never be silently discarded either - it's logged
     * loudly instead.
     */
    @Async
    public void recordAsync(AuditEntry entry) {
        try {
            record(entry);
        } catch (Exception e) {
            log.error("Failed to write audit log entry: action={}", entry.action(), e);
        }
    }

    /** GET /audit (audit-logging.md §3): combined filter + scope Specification, paginated, most recent first. */
    public Page<AuditLog> search(Specification<AuditLog> specification, Pageable pageable) {
        return repository.findAll(specification, pageable);
    }

    /**
     * Verify the hash chain over {@code [from, to]} using the most recent
     * checkpoint strictly before the range (audit-logging.md §4.5, Story 4
     * AC-1) instead of always scanning from the first row.
     *
     * <p>The chain is a strictly positional structure (each row's {@code
     * prevHash} must equal the immediately preceding row's {@code hash}), so
     * verification cannot "jump into" the middle of it - it must resume
     * exactly at {@code checkpoint.upToId + 1}, using {@code
     * checkpoint.rootHash} as the expected {@code prevHash} for that row.
     * The checkpoint used is therefore the most recent one whose {@code
     * upToId} is below the first row id in range (not literally "before the
     * {@code from} timestamp" - ids and timestamps are monotonically
     * correlated here, so resolving the boundary as an id first, then
     * picking a checkpoint by id, is what the spec's prose intends and
     * avoids comparing an id to a timestamp). Rows with {@code hash IS NULL}
     * (not yet sealed - AuditSealerJob runs every few seconds) are skipped
     * rather than treated as a break: they simply are not part of the
     * chain yet.
     */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain(OffsetDateTime from, OffsetDateTime to) {
        Long firstIdInRange = repository.findMinIdWithCreatedAtGreaterThanEqual(from);
        Long lastIdInRange = repository.findMaxIdWithCreatedAtLessThanEqual(to);
        if (firstIdInRange == null || lastIdInRange == null || firstIdInRange > lastIdInRange) {
            return new ChainVerification(true, 0, null, null);
        }

        Checkpoint checkpoint = findLatestCheckpointBefore(firstIdInRange);
        long startId;
        String expectedPrev;
        if (checkpoint != null) {
            startId = checkpoint.upToId() + 1;
            expectedPrev = checkpoint.rootHash();
        } else {
            Long genesisId = repository.findMinId();
            startId = genesisId != null ? genesisId : firstIdInRange;
            expectedPrev = null;
        }

        long count = 0;
        try (Stream<AuditLog> rows = repository.streamByIdRange(startId, lastIdInRange)) {
            var iterator = rows.iterator();
            while (iterator.hasNext()) {
                AuditLog row = iterator.next();
                if (row.getHash() == null) {
                    continue; // not sealed yet - not part of the chain until AuditSealerJob seals it
                }
                count++;
                if (!Objects.equals(row.getPrevHash(), expectedPrev)) {
                    return new ChainVerification(
                            false, count, row.getId(),
                            "prevHash does not match preceding row (row inserted, deleted, or reordered)");
                }
                if (!computeHash(row, row.getPrevHash()).equals(row.getHash())) {
                    return new ChainVerification(false, count, row.getId(), "hash mismatch (row contents tampered)");
                }
                expectedPrev = row.getHash();
            }
        }
        return new ChainVerification(true, count, null, null);
    }

    private record Checkpoint(long upToId, String rootHash) {
    }

    private Checkpoint findLatestCheckpointBefore(long id) {
        var rows = jdbcTemplate.query(
                "SELECT up_to_id, root_hash FROM audit_checkpoint WHERE up_to_id < ? ORDER BY up_to_id DESC LIMIT 1",
                (rs, rowNum) -> new Checkpoint(rs.getLong("up_to_id"), rs.getString("root_hash")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Canonical hash input for a row: its semantic fields plus the prior hash. Used by verifyChain only now - AuditSealerJob computes hashes from raw JDBC rows via {@link #computeHash(OffsetDateTime, String, String, String, String, String, String, String, String, Integer, String, Map, String)} to avoid touching a Hibernate-managed entity (see that job's javadoc). */
    static String computeHash(AuditLog row, String prevHash) {
        return computeHash(
                row.getCreatedAt(), row.getActor(), row.getActorSub(), row.getAction(), row.getResourceType(),
                row.getResourceId(), row.getOutcome(), row.getMethod(), row.getPath(), row.getStatusCode(),
                row.getIp(), row.getMetadata(), prevHash);
    }

    static String computeHash(
            OffsetDateTime createdAt,
            String actor,
            String actorSub,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String method,
            String path,
            Integer statusCode,
            String ip,
            Map<String, Object> metadata,
            String prevHash) {
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("createdAt", createdAt.toInstant().toString());
        canonical.put("actor", actor);
        canonical.put("actorSub", actorSub);
        canonical.put("action", action);
        canonical.put("resourceType", resourceType);
        canonical.put("resourceId", resourceId);
        canonical.put("outcome", outcome);
        canonical.put("method", method);
        canonical.put("path", path);
        canonical.put("statusCode", statusCode);
        canonical.put("ip", ip);
        canonical.put("metadata", metadata);
        canonical.put("prevHash", prevHash);
        return sha256Hex(canonicalJson(canonical));
    }

    /**
     * Deterministic JSON with recursively sorted object keys. Needed because
     * the hash must be reproducible from a row read back out of Postgres, and
     * jsonb does not preserve key order - so key order must not affect the
     * hash.
     */
    static String canonicalJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            var keys = map.keySet().stream().map(String::valueOf).sorted().toList();
            String entries = keys.stream()
                    .map(k -> writeJson(k) + ":" + canonicalJson(map.get(k)))
                    .collect(Collectors.joining(","));
            return "{" + entries + "}";
        }
        if (value instanceof java.util.List<?> list) {
            return "[" + list.stream().map(AuditService::canonicalJson).collect(Collectors.joining(",")) + "]";
        }
        return writeJson(value);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize audit log value", e);
        }
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
