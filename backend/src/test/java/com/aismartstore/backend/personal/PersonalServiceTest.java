package com.aismartstore.backend.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.accounts.Account;
import com.aismartstore.backend.accounts.AccountsService;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.ingest.BatchKind;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.ingest.UploadBatchStatus;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.scanning.MimeAllowlist;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Unit coverage for personal-storage-quota.md §5 ("Unit test",
 * {@code PersonalServiceTest}): quota source, batch open/commit pipeline,
 * the Story 1 AC-2 bug fix (no format check), the shared file-size cap
 * (Quyết định 2 mục 0), and the OCR/STT/search/DocumentsService isolation
 * (Story 1 AC-4, Story 2 AC-2).
 *
 * <p>{@link PersonalService#commitFileInsert} uses a real
 * {@link org.springframework.transaction.support.TransactionTemplate} - here
 * backed by a mocked {@link PlatformTransactionManager} that just hands back
 * a {@link TransactionStatus} without a real DB transaction, so the
 * lambda's logic (advisory lock statement, SUM re-check, insert, batch
 * counter bump) runs for real against the mocked repositories.
 */
class PersonalServiceTest {

    private PersonalFolderRepository folderRepository;
    private PersonalFileRepository fileRepository;
    private UploadBatchRepository uploadBatchRepository;
    private ClamdService clamdService;
    private ObjectStorageService objectStorageService;
    private AccountsService accountsService;
    private AuditService auditService;
    private EntityManager entityManager;
    private PlatformTransactionManager transactionManager;
    private SystemConfigService systemConfigService;
    private PersonalService service;

    private final KeycloakUser owner = new KeycloakUser("owner-sub", "owner", List.of("can-bo"));
    private static final String PERSONAL_BUCKET = "personal";

    @BeforeEach
    void setUp() {
        folderRepository = mock(PersonalFolderRepository.class);
        fileRepository = mock(PersonalFileRepository.class);
        uploadBatchRepository = mock(UploadBatchRepository.class);
        clamdService = mock(ClamdService.class);
        objectStorageService = mock(ObjectStorageService.class);
        accountsService = mock(AccountsService.class);
        auditService = mock(AuditService.class);
        AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
        entityManager = mock(EntityManager.class);
        transactionManager = mock(PlatformTransactionManager.class);
        systemConfigService = mock(SystemConfigService.class);

        StorageProperties storageProperties = new StorageProperties(
                "http://localhost:8333", "us-east-1", "ak", "sk", "documents", PERSONAL_BUCKET, "audit-archive", "reports");

        service = new PersonalService(
                folderRepository, fileRepository, uploadBatchRepository, clamdService, objectStorageService,
                storageProperties, accountsService, auditService, auditRequestContext, entityManager,
                transactionManager, systemConfigService);

        // TransactionTemplate wiring: a real transaction boundary isn't needed for
        // unit coverage of the callback's logic - just hand back a status object.
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // entityManager.unwrap(Session.class).doWork(...) - execute the Work
        // callback against a mocked JDBC Connection/PreparedStatement so the
        // advisory-lock statement inside commitFileInsert runs without error.
        Session session = mock(Session.class);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        doAnswer(invocation -> {
            org.hibernate.jdbc.Work work = invocation.getArgument(0);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            work.execute(connection);
            return null;
        }).when(session).doWork(any());

        // Mimic Hibernate's @GeneratedValue: assign an id on first save, like a
        // real JpaRepository.save() would - PersonalService reads .getId() right
        // after saving (for the audit trail), which a bare mock echo leaves null.
        when(fileRepository.saveAndFlush(any())).thenAnswer(inv -> {
            PersonalFile f = inv.getArgument(0);
            if (f.getId() == null) {
                setId(f, UUID.randomUUID());
            }
            return f;
        });
        when(uploadBatchRepository.save(any())).thenAnswer(inv -> {
            UploadBatch b = inv.getArgument(0);
            if (b.getId() == null) {
                setId(b, UUID.randomUUID());
            }
            return b;
        });

        // Sensible defaults, overridden per test as needed.
        when(accountsService.getOrCreateAccountRow(eq(owner.sub()), any())).thenReturn(accountWithQuota(1_000_000));
        when(fileRepository.sumSizeByOwnerSub(owner.sub())).thenReturn(0L);
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES)).thenReturn(2_000_000_000L);
        when(clamdService.ping()).thenReturn(true);
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.ofClean());
    }

    private static Account accountWithQuota(long quota) {
        Account account = new Account();
        account.setKeycloakUserId("owner-sub");
        account.setStorageQuotaBytes(quota);
        return account;
    }

    private static UploadBatch batchWithId(UUID id, String ownerSub, String status, OffsetDateTime expiresAt) {
        UploadBatch batch = new UploadBatch();
        setId(batch, id);
        batch.setKind(BatchKind.PERSONAL);
        batch.setOwnerSub(ownerSub);
        batch.setOwnerUsername("owner");
        batch.setDeclaredFileCount(10);
        batch.setDeclaredTotalBytes(1_000_000);
        batch.setStatus(status);
        batch.setCreatedAt(OffsetDateTime.now());
        batch.setUpdatedAt(OffsetDateTime.now());
        batch.setExpiresAt(expiresAt);
        return batch;
    }

    private static void setId(UploadBatch batch, UUID id) {
        try {
            Field field = UploadBatch.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(batch, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setId(PersonalFile file, UUID id) {
        try {
            Field field = PersonalFile.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(file, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private UploadBatch stubOpenBatch(UUID batchId) {
        UploadBatch batch = batchWithId(batchId, owner.sub(), UploadBatchStatus.OPEN, OffsetDateTime.now().plusHours(1));
        when(uploadBatchRepository.findByIdAndOwnerSubAndKind(batchId, owner.sub(), BatchKind.PERSONAL))
                .thenReturn(Optional.of(batch));
        return batch;
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(Throwable ex) {
        return (String) ((Map<String, Object>) ((ApiException) ex).getBody()).get("code");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Throwable ex) {
        return (Map<String, Object>) ((ApiException) ex).getBody();
    }

    // ---- Story 1 AC-2: any format is accepted, no allowlist check ------------

    @Test
    void uploadAcceptsAnyFormatWithoutCallingMimeAllowlist() {
        UUID batchId = UUID.randomUUID();
        stubOpenBatch(batchId);
        MockMultipartFile file =
                new MockMultipartFile("file", "virus.exe", "application/x-msdownload", "not a real exe".getBytes());

        try (MockedStatic<MimeAllowlist> mocked = mockStatic(MimeAllowlist.class)) {
            PersonalFile saved = service.uploadFileInBatch(owner, batchId, null, file, "", file.getOriginalFilename());

            assertThat(saved).isNotNull();
            assertThat(saved.getOriginalName()).isEqualTo("virus.exe");
            // verify by mock, not just by outcome (spec §5).
            mocked.verifyNoInteractions();
        }
    }

    @Test
    void anyFormatSmallerThanTheSizeCapIsNeitherTooLargeNorFormatRejected() {
        UUID batchId = UUID.randomUUID();
        stubOpenBatch(batchId);
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES)).thenReturn(1_000L);
        MockMultipartFile file =
                new MockMultipartFile("file", "tool.exe", "application/x-msdownload", "MZ-header-bytes".getBytes());

        PersonalFile saved = service.uploadFileInBatch(owner, batchId, null, file, "", file.getOriginalFilename());

        assertThat(saved.getOriginalName()).isEqualTo("tool.exe");
    }

    // ---- malware ---------------------------------------------------------

    @Test
    void infectedFileIsRejectedWithoutInsertOrBatchCounterChange() {
        UUID batchId = UUID.randomUUID();
        stubOpenBatch(batchId);
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.infected("EICAR-TEST"));
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> service.uploadFileInBatch(owner, batchId, null, file, "", file.getOriginalFilename()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("MALWARE_DETECTED"));

        verify(fileRepository, never()).save(any());
        verify(uploadBatchRepository, never()).incrementActual(any(), anyLong());
        verify(objectStorageService, never()).putObject(any(), any(), any(), any());
    }

    // ---- quota: commit-time authoritative re-check -----------------------

    @Test
    void uploadSucceedsWhenQuotaHasRoomAndBumpsBatchCounters() {
        UUID batchId = UUID.randomUUID();
        stubOpenBatch(batchId);
        when(accountsService.getOrCreateAccountRow(eq(owner.sub()), any())).thenReturn(accountWithQuota(1_000));
        when(fileRepository.sumSizeByOwnerSub(owner.sub())).thenReturn(0L);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());

        PersonalFile saved = service.uploadFileInBatch(owner, batchId, null, file, "", file.getOriginalFilename());

        assertThat(saved.getSize()).isEqualTo(5);
        assertThat(saved.getBatchId()).isEqualTo(batchId);
        verify(fileRepository).saveAndFlush(any());
        verify(uploadBatchRepository).incrementActual(batchId, 5L);
        verify(objectStorageService).putObject(anyString(), any(), eq("text/plain"), eq(PERSONAL_BUCKET));
    }

    @Test
    void uploadFailsAtCommitWhenReCheckedSumExceedsQuotaAndCompensatesTheStoredObject() {
        UUID batchId = UUID.randomUUID();
        stubOpenBatch(batchId);
        when(accountsService.getOrCreateAccountRow(eq(owner.sub()), any())).thenReturn(accountWithQuota(10));
        when(fileRepository.sumSizeByOwnerSub(owner.sub())).thenReturn(8L);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()); // 5 bytes: 8+5=13>10

        assertThatThrownBy(() -> service.uploadFileInBatch(owner, batchId, null, file, "", file.getOriginalFilename()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(codeOf(ex)).isEqualTo("QUOTA_EXCEEDED");
                    Map<String, Object> body = bodyOf(ex);
                    assertThat(body.get("usedBytes")).isEqualTo(8L);
                    assertThat(body.get("quotaBytes")).isEqualTo(10L);
                    assertThat(body.get("remainingBytes")).isEqualTo(2L);
                });

        verify(fileRepository, never()).save(any());
        verify(uploadBatchRepository, never()).incrementActual(any(), anyLong());
        // object was already putObject'd before the locked re-check - must be compensated (deleted).
        verify(objectStorageService).putObject(anyString(), any(), anyString(), eq(PERSONAL_BUCKET));
        verify(objectStorageService).deleteObject(anyString(), eq(PERSONAL_BUCKET));
    }

    // ---- openBatch: early unlocked pre-check (§4.3) -----------------------

    @Test
    void openBatchRejectsWhenDeclaredTotalBytesExceedsRemainingQuota() {
        when(accountsService.getOrCreateAccountRow(eq(owner.sub()), any())).thenReturn(accountWithQuota(100));
        when(fileRepository.sumSizeByOwnerSub(owner.sub())).thenReturn(90L);

        assertThatThrownBy(() -> service.openBatch(owner, null, 1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("QUOTA_EXCEEDED"));

        verify(uploadBatchRepository, never()).save(any());
    }

    @Test
    void openBatchCreatesAPersonalKindBatchWithinQuota() {
        when(accountsService.getOrCreateAccountRow(eq(owner.sub()), any())).thenReturn(accountWithQuota(1_000));
        when(fileRepository.sumSizeByOwnerSub(owner.sub())).thenReturn(0L);

        UploadBatch batch = service.openBatch(owner, null, 1, 500);

        assertThat(batch.getKind()).isEqualTo(BatchKind.PERSONAL);
        assertThat(batch.getTargetFolderId()).isNull();
        assertThat(batch.getStatus()).isEqualTo(UploadBatchStatus.OPEN);
        verify(uploadBatchRepository).save(any());
    }

    // ---- Quyết định 2 mục 0: per-file size cap, checked before ClamAV -----

    @Test
    void fileAboveMaxFileSizeIsRejectedWithoutCallingClamAvOrTouchingBatchCounters() {
        UUID batchId = UUID.randomUUID();
        stubOpenBatch(batchId);
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES)).thenReturn(4L);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()); // 5 bytes > 4

        assertThatThrownBy(() -> service.uploadFileInBatch(owner, batchId, null, file, "", file.getOriginalFilename()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("FILE_TOO_LARGE"));

        verifyNoInteractions(clamdService);
        verify(uploadBatchRepository, never()).incrementActual(any(), anyLong());
        verify(objectStorageService, never()).putObject(any(), any(), any(), any());
    }

    // ---- Story 1 AC-4 / Story 2 AC-2: no OCR/STT/search/DocumentsService -----

    @Test
    void hasNoDependencyOnDocumentsOrOcrSttSearchModules() {
        for (Field field : PersonalService.class.getDeclaredFields()) {
            String typeName = field.getType().getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(typeName)
                    .as("field '%s' of type %s must not be a documents/OCR/STT/search dependency",
                            field.getName(), field.getType())
                    .doesNotContain(".documents.")
                    .doesNotContain(".ocr.")
                    .doesNotContain(".stt.")
                    .doesNotContain(".search.")
                    .doesNotContain("documentsservice");
        }
    }
}
