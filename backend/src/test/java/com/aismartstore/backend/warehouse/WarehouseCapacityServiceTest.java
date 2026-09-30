package com.aismartstore.backend.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit coverage for warehouse-structure.md §5 ("Story 5" capacity rules). */
class WarehouseCapacityServiceTest {

    private WarehouseRepository warehouseRepository;
    private DocumentRepository documentRepository;
    private SystemConfigService systemConfigService;
    private WarehouseCapacityService service;

    private final KeycloakUser admin = new KeycloakUser("admin-sub", "admin", List.of("quan-tri"));

    @BeforeEach
    void setUp() {
        warehouseRepository = mock(WarehouseRepository.class);
        documentRepository = mock(DocumentRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        service = new WarehouseCapacityService(warehouseRepository, documentRepository, systemConfigService);

        when(systemConfigService.getLong(SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT))
                .thenReturn(85L);
        when(systemConfigService.getLong(SystemConfigDefaults.WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT))
                .thenReturn(95L);
        when(warehouseRepository.findById(WarehouseRepository.SINGLETON_ID))
                .thenReturn(Optional.of(warehouseRow(1_000_000)));
    }

    private static Warehouse warehouseRow(long maxCapacityBytes) {
        Warehouse w = new Warehouse();
        w.setMaxCapacityBytes(maxCapacityBytes);
        w.setUpdatedAt(OffsetDateTime.now());
        return w;
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(Throwable ex) {
        return (String) ((Map<String, Object>) ((ApiException) ex).getBody()).get("code");
    }

    private WarehouseCapacityService.CapacityStatus statusFor(long usedBytes) {
        when(documentRepository.sumSizeByStatus(DocumentStatus.CLEAN)).thenReturn(usedBytes);
        return service.getSnapshot().status();
    }

    // ---- Story 5 AC-2/AC-3: threshold boundaries (85% warning, 95% block) ----

    @Test
    void statusBelowWarningThresholdIsOk() {
        assertThat(statusFor(849_900)).isEqualTo(WarehouseCapacityService.CapacityStatus.OK); // 84.99%
    }

    @Test
    void statusAtWarningThresholdIsWarning() {
        assertThat(statusFor(850_000)).isEqualTo(WarehouseCapacityService.CapacityStatus.WARNING); // 85.00%
    }

    @Test
    void statusJustBelowBlockThresholdIsStillWarning() {
        assertThat(statusFor(949_900)).isEqualTo(WarehouseCapacityService.CapacityStatus.WARNING); // 94.99%
    }

    @Test
    void statusAtBlockThresholdIsBlocked() {
        assertThat(statusFor(950_000)).isEqualTo(WarehouseCapacityService.CapacityStatus.BLOCKED); // 95.00%
    }

    // ---- Story 5 AC-5: pre-check / post-check ---------------------------------

    @Test
    void assertIngestAllowedThrowsCapacityExceededWhenBlocked() {
        when(documentRepository.sumSizeByStatus(DocumentStatus.CLEAN)).thenReturn(950_000L);

        assertThatThrownBy(() -> service.assertIngestAllowed())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("WAREHOUSE_CAPACITY_EXCEEDED"));
    }

    @Test
    void assertIngestAllowedPassesWhenOnlyWarning() {
        when(documentRepository.sumSizeByStatus(DocumentStatus.CLEAN)).thenReturn(900_000L);

        service.assertIngestAllowed(); // must not throw
    }

    @Test
    void recomputeAfterWriteReflectsBlockedStatusButNeverThrows() {
        when(documentRepository.sumSizeByStatus(DocumentStatus.CLEAN)).thenReturn(950_000L);

        WarehouseCapacityService.Snapshot snapshot = service.recomputeAfterWrite();

        assertThat(snapshot.status()).isEqualTo(WarehouseCapacityService.CapacityStatus.BLOCKED);
    }

    // ---- Story 5 AC-6: update never validates against usedBytes --------------

    @Test
    void updateCapacityRejectsNonPositiveValue() {
        assertThatThrownBy(() -> service.updateCapacity(admin, 0))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("INVALID_VALUE"));
        assertThatThrownBy(() -> service.updateCapacity(admin, -5))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("INVALID_VALUE"));
        verify(warehouseRepository, never()).save(any());
    }

    @Test
    void updateCapacitySavesEvenBelowCurrentUsedBytesAndReturnsFreshStatus() {
        Warehouse row = warehouseRow(1_000_000);
        when(warehouseRepository.findById(WarehouseRepository.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(documentRepository.sumSizeByStatus(DocumentStatus.CLEAN)).thenReturn(900_000L); // already 90% of the OLD max

        WarehouseCapacityService.Snapshot snapshot = service.updateCapacity(admin, 100_000L); // new max < used

        assertThat(row.getMaxCapacityBytes()).isEqualTo(100_000L);
        assertThat(row.getUpdatedBySub()).isEqualTo(admin.sub());
        assertThat(row.getUpdatedByUsername()).isEqualTo(admin.preferredUsername());
        verify(warehouseRepository).save(row);
        assertThat(snapshot.maxCapacityBytes()).isEqualTo(100_000L);
        assertThat(snapshot.status()).isEqualTo(WarehouseCapacityService.CapacityStatus.BLOCKED);
    }

    @Test
    void snapshotUsesRepositorySumDirectlyIncludingTrashedCleanDocuments() {
        // WarehouseCapacityService applies no extra filtering on top of the
        // repository query - DocumentRepository.sumSizeByStatus itself is
        // deliberately NOT deletedAt-filtered (spec §4.4), and that SQL-level
        // behaviour is proven against a real DB in WarehouseControllerIT.
        when(documentRepository.sumSizeByStatus(eq(DocumentStatus.CLEAN))).thenReturn(157_286_400L); // 150MB
        assertThat(service.getSnapshot().usedBytes()).isEqualTo(157_286_400L);
    }

    // ---- delete-restore-trash-01-xoa-mem-thung-rac.md §4.4 (Story 4) ---------

    @Test
    void snapshotExposesTrashBytesSeparatelyFromUsedBytesWithoutAffectingUsedPercent() {
        when(documentRepository.sumSizeByStatus(eq(DocumentStatus.CLEAN))).thenReturn(900_000L);
        when(documentRepository.sumSizeByStatusAndDeletedAtIsNotNull(eq(DocumentStatus.CLEAN)))
                .thenReturn(300_000L);

        WarehouseCapacityService.Snapshot snapshot = service.getSnapshot();

        assertThat(snapshot.trashBytes()).isEqualTo(300_000L);
        assertThat(snapshot.usedBytes()).isEqualTo(900_000L); // unchanged formula
        assertThat(snapshot.usedPercent()).isEqualTo(90.0); // 900_000 / 1_000_000
    }
}
