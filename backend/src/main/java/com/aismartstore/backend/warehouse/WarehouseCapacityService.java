package com.aismartstore.backend.warehouse;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Warehouse-wide storage capacity: "used" vs. the R3-configured maximum
 * (Story 5, spec §4.4). {@link #assertIngestAllowed()} and
 * {@link #recomputeAfterWrite()} are the two internal hooks
 * {@code data-ingestion.md}'s ingest pipeline calls at pre-check/post-check
 * (Story 5 AC-5) - see spec §3 "Noi bo" and Task 8.
 */
@Service
public class WarehouseCapacityService {

    private final WarehouseRepository warehouseRepository;
    private final DocumentRepository documentRepository;
    private final SystemConfigService systemConfigService;

    public WarehouseCapacityService(
            WarehouseRepository warehouseRepository,
            DocumentRepository documentRepository,
            SystemConfigService systemConfigService) {
        this.warehouseRepository = warehouseRepository;
        this.documentRepository = documentRepository;
        this.systemConfigService = systemConfigService;
    }

    public enum CapacityStatus {
        OK,
        WARNING,
        BLOCKED
    }

    /**
     * Shape mirrors the GET/PATCH /warehouse/capacity response (spec §3).
     * {@code trashBytes} (delete-restore-trash-01-xoa-mem-thung-rac.md §4.4,
     * Story 4) is purely a display split of {@code usedBytes} - it does not
     * feed into {@code usedPercent}/{@code status}, both computed exactly as
     * before.
     */
    public record Snapshot(
            long usedBytes,
            long maxCapacityBytes,
            double usedPercent,
            long warningThresholdPercent,
            long blockThresholdPercent,
            CapacityStatus status,
            long trashBytes) {
    }

    /**
     * Aggregate query every call (spec §4.4 tradeoff note): {@code document}
     * is insert-only, so this can't drift the way a maintained counter
     * could.
     */
    public Snapshot getSnapshot() {
        long usedBytes = documentRepository.sumSizeByStatus(DocumentStatus.CLEAN);
        long maxCapacityBytes = loadWarehouse().getMaxCapacityBytes();
        long warningThresholdPercent =
                systemConfigService.getLong(SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT);
        long blockThresholdPercent =
                systemConfigService.getLong(SystemConfigDefaults.WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT);
        double usedPercent = maxCapacityBytes > 0 ? usedBytes * 100.0 / maxCapacityBytes : 0.0;

        CapacityStatus status;
        if (usedPercent >= blockThresholdPercent) {
            status = CapacityStatus.BLOCKED;
        } else if (usedPercent >= warningThresholdPercent) {
            status = CapacityStatus.WARNING;
        } else {
            status = CapacityStatus.OK;
        }

        long trashBytes = documentRepository.sumSizeByStatusAndDeletedAtIsNotNull(DocumentStatus.CLEAN);

        return new Snapshot(
                usedBytes, maxCapacityBytes, usedPercent, warningThresholdPercent, blockThresholdPercent, status,
                trashBytes);
    }

    /** Pre-check (AC-5a): reject NEW ingest work once the warehouse is BLOCKED. */
    public void assertIngestAllowed() {
        if (getSnapshot().status() == CapacityStatus.BLOCKED) {
            throw capacityExceeded();
        }
    }

    /**
     * Post-check (AC-5b): re-evaluate status after a write. Never blocks the
     * write that just happened - only affects the NEXT call to
     * {@link #assertIngestAllowed()}/GET.
     */
    public Snapshot recomputeAfterWrite() {
        return getSnapshot();
    }

    /** AC-6: always saves, regardless of the current usedBytes - never validated against it. */
    public Snapshot updateCapacity(KeycloakUser user, long maxCapacityBytes) {
        if (maxCapacityBytes <= 0) {
            throw invalidValue();
        }

        Warehouse warehouse = loadWarehouse();
        warehouse.setMaxCapacityBytes(maxCapacityBytes);
        warehouse.setUpdatedAt(OffsetDateTime.now());
        warehouse.setUpdatedBySub(user.sub());
        warehouse.setUpdatedByUsername(user.preferredUsername());
        warehouseRepository.save(warehouse);

        return getSnapshot();
    }

    private Warehouse loadWarehouse() {
        return warehouseRepository
                .findById(WarehouseRepository.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "warehouse singleton row (id=1) is missing - V9 seed should have created it"));
    }

    private static ApiException capacityExceeded() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "WAREHOUSE_CAPACITY_EXCEEDED");
        body.put("message", "Kho đã đạt ngưỡng chặn, không thể nạp thêm dữ liệu");
        return new ApiException(HttpStatus.CONFLICT, body, "Warehouse capacity exceeded");
    }

    private static ApiException invalidValue() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_VALUE");
        body.put("message", "maxCapacityBytes phải lớn hơn 0");
        return new ApiException(HttpStatus.BAD_REQUEST, body, "Invalid maxCapacityBytes");
    }
}
