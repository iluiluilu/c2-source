package com.aismartstore.backend.systemconfig;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface for the runtime business parameters (FR-01). Every endpoint is
 * R3-only (Story 1 AC-3). The request body is read as a raw map so the
 * polymorphic {@code value} field (string | number | boolean | object) is
 * preserved for per-key type conversion in the service.
 */
@RestController
@RequestMapping("/admin/system-config")
@PreAuthorize("hasAuthority('PERM_system_config.manage')")
public class SystemConfigController {

    private final SystemConfigService service;

    public SystemConfigController(SystemConfigService service) {
        this.service = service;
    }

    /** Story 1: full catalogue with resolved value, default and flags. */
    @GetMapping
    public List<SystemConfigService.ConfigView> list() {
        return service.listAll();
    }

    /** Story 5 AC-1: per-key change history, newest first. */
    @GetMapping("/{key}/history")
    public List<SystemConfigService.HistoryEntry> history(@PathVariable String key) {
        return service.history(key);
    }

    /**
     * Story 2/3: change a parameter. Non-sensitive keys apply immediately
     * (200); sensitive keys return 409 CONFIRMATION_REQUIRED (thrown by the
     * service) without writing.
     */
    @PatchMapping("/{key}")
    public SystemConfigService.UpdateResult update(
            @PathVariable String key,
            @RequestBody(required = false) Map<String, Object> body,
            @CurrentUser KeycloakUser user) {
        return service.update(key, requireValue(body), user);
    }

    /** Story 3 AC-3: apply a previously-issued sensitive change. */
    @PostMapping("/{key}/confirm")
    public SystemConfigService.UpdateResult confirm(
            @PathVariable String key,
            @RequestBody(required = false) Map<String, Object> body,
            @CurrentUser KeycloakUser user) {
        String token = asToken(valueOf(body, "confirmationToken"));
        return service.confirm(key, token, user);
    }

    /**
     * Story 5: roll back to a prior value. Sensitive keys still require the
     * two-step confirm - a missing/invalid token yields 409 (via the service).
     */
    @PostMapping("/{key}/rollback")
    public SystemConfigService.UpdateResult rollback(
            @PathVariable String key,
            @RequestBody(required = false) Map<String, Object> body,
            @CurrentUser KeycloakUser user) {
        Object toValue = valueOf(body, "toValue");
        if (toValue == null) {
            throw new BadRequestException("Thiếu giá trị 'toValue'");
        }
        String token = asToken(valueOf(body, "confirmationToken"));
        return service.rollback(key, toValue, token, user);
    }

    // ---- helpers --------------------------------------------------------

    private static Object requireValue(Map<String, Object> body) {
        if (body == null || !body.containsKey("value")) {
            throw new BadRequestException("Thiếu giá trị 'value'");
        }
        return body.get("value");
    }

    private static Object valueOf(Map<String, Object> body, String field) {
        return body == null ? null : body.get(field);
    }

    private static String asToken(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
