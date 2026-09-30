package com.aismartstore.backend.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.warehouse.WarehouseCapacityService;
import com.aismartstore.backend.warehouse.WarehouseService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit coverage for data-ingestion.md §4.1/§4.2/§4.4 (Task 4). */
class UploadBatchServiceTest {

    private UploadBatchRepository uploadBatchRepository;
    private WarehouseService warehouseService;
    private WarehouseCapacityService warehouseCapacityService;
    private SystemConfigService systemConfigService;
    private UploadBatchService service;

    private final KeycloakUser officer = new KeycloakUser("officer-sub", "officer", List.of("can-bo"));
    private final UUID folderId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        uploadBatchRepository = mock(UploadBatchRepository.class);
        warehouseService = mock(WarehouseService.class);
        warehouseCapacityService = mock(WarehouseCapacityService.class);
        systemConfigService = mock(SystemConfigService.class);
        service = new UploadBatchService(uploadBatchRepository, warehouseService, warehouseCapacityService, systemConfigService);

        when(warehouseService.folderExists(folderId)).thenReturn(true);
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH)).thenReturn(500L);
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_BATCH_SIZE_BYTES)).thenReturn(5_000_000_000L);
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES)).thenReturn(2_000_000_000L);
        when(uploadBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(Throwable ex) {
        return (String) ((Map<String, Object>) ((ApiException) ex).getBody()).get("code");
    }

    private UploadBatch openBatch(String status, OffsetDateTime expiresAt) {
        UploadBatch batch = new UploadBatch();
        batch.setTargetFolderId(folderId);
        batch.setOwnerSub(officer.sub());
        batch.setOwnerUsername(officer.preferredUsername());
        batch.setDeclaredFileCount(10);
        batch.setDeclaredTotalBytes(1_000);
        batch.setStatus(status);
        batch.setCreatedAt(OffsetDateTime.now());
        batch.setUpdatedAt(OffsetDateTime.now());
        batch.setExpiresAt(expiresAt);
        return batch;
    }

    // ---- open (§4.1) ------------------------------------------------------

    @Test
    void openRejectsUnknownFolderWith404() {
        when(warehouseService.folderExists(folderId)).thenReturn(false);

        assertThatThrownBy(() -> service.open(officer, folderId, 1, 100))
                .isInstanceOf(ApiException.class);
        verify(uploadBatchRepository, never()).save(any());
    }

    @Test
    void openRejectsDeclaredFileCountAboveLimit() {
        assertThatThrownBy(() -> service.open(officer, folderId, 501, 100))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("BATCH_LIMIT_EXCEEDED"));
        verify(uploadBatchRepository, never()).save(any());
    }

    @Test
    void openRejectsDeclaredTotalBytesAboveLimit() {
        assertThatThrownBy(() -> service.open(officer, folderId, 1, 6_000_000_000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("BATCH_LIMIT_EXCEEDED"));
        verify(uploadBatchRepository, never()).save(any());
    }

    @Test
    void openPropagatesWarehouseCapacityExceededAndNeverSaves() {
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "blocked"))
                .when(warehouseCapacityService)
                .assertIngestAllowed();

        assertThatThrownBy(() -> service.open(officer, folderId, 1, 100)).isInstanceOf(ApiException.class);
        verify(uploadBatchRepository, never()).save(any());
    }

    @Test
    void openCreatesBatchWithinLimits() {
        UploadBatch batch = service.open(officer, folderId, 5, 1_000);

        assertThat(batch.getStatus()).isEqualTo(UploadBatchStatus.OPEN);
        assertThat(batch.getTargetFolderId()).isEqualTo(folderId);
        assertThat(batch.getExpiresAt()).isAfter(OffsetDateTime.now().plusHours(23));
        verify(uploadBatchRepository).save(any());
    }

    // ---- loadOpenBatch (§4.2 step 1) ---------------------------------------

    @Test
    void loadOpenBatchThrows404WhenMissing() {
        when(uploadBatchRepository.findById(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadOpenBatch(batchId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("BATCH_NOT_FOUND"));
    }

    @Test
    void loadOpenBatchReturnsBatchWhenOpenAndNotExpired() {
        UploadBatch batch = openBatch(UploadBatchStatus.OPEN, OffsetDateTime.now().plusHours(1));
        when(uploadBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        assertThat(service.loadOpenBatch(batchId)).isSameAs(batch);
        verify(uploadBatchRepository, never()).save(any());
    }

    @Test
    void loadOpenBatchLazilyExpiresAndThrowsBatchExpired() {
        UploadBatch batch = openBatch(UploadBatchStatus.OPEN, OffsetDateTime.now().minusMinutes(1));
        when(uploadBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.loadOpenBatch(batchId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("BATCH_EXPIRED"));
        assertThat(batch.getStatus()).isEqualTo(UploadBatchStatus.EXPIRED);
        verify(uploadBatchRepository).save(batch);
    }

    @Test
    void loadOpenBatchThrowsBatchClosedForNonOpenStatus() {
        UploadBatch batch = openBatch(UploadBatchStatus.LIMIT_EXCEEDED, OffsetDateTime.now().plusHours(1));
        when(uploadBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.loadOpenBatch(batchId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("BATCH_CLOSED"));
    }

    // ---- reserve (§4.2 step 4) ---------------------------------------------

    @Test
    void reserveSucceedsWhenRepositoryUpdatesOneRow() {
        when(uploadBatchRepository.reserve(eq(batchId), anyLong(), anyLong(), anyLong())).thenReturn(1);

        service.reserve(batchId, 1_000);

        verify(uploadBatchRepository, never()).updateStatus(any(), any());
    }

    @Test
    void reserveMarksLimitExceededAndThrowsWhenNoRowUpdated() {
        when(uploadBatchRepository.reserve(eq(batchId), anyLong(), anyLong(), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(batchId, 1_000))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("BATCH_LIMIT_EXCEEDED"));
        verify(uploadBatchRepository, times(1)).updateStatus(batchId, UploadBatchStatus.LIMIT_EXCEEDED);
    }
}
