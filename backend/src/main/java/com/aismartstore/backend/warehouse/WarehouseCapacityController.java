package com.aismartstore.backend.warehouse;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.warehouse.dto.UpdateWarehouseCapacityDto;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Story 5: warehouse-wide storage capacity display (any logged-in role) and R3-only configuration. */
@RestController
@RequestMapping("/warehouse")
public class WarehouseCapacityController {

    private final WarehouseCapacityService capacityService;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public WarehouseCapacityController(
            WarehouseCapacityService capacityService,
            AuditService auditService,
            AuditRequestContext auditRequestContext) {
        this.capacityService = capacityService;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    @GetMapping("/capacity")
    @PreAuthorize("hasAuthority('PERM_folder.read')")
    public WarehouseCapacityService.Snapshot getCapacity() {
        return capacityService.getSnapshot();
    }

    @PatchMapping("/capacity")
    @PreAuthorize("hasAuthority('PERM_folder.manage')")
    public WarehouseCapacityService.Snapshot updateCapacity(
            @CurrentUser KeycloakUser user, @Valid @RequestBody UpdateWarehouseCapacityDto dto) {
        WarehouseCapacityService.Snapshot snapshot = capacityService.updateCapacity(user, dto.maxCapacityBytes());
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "warehouse.capacity.update", "warehouse", "1",
                "success", auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("maxCapacityBytes", dto.maxCapacityBytes())));
        return snapshot;
    }
}
