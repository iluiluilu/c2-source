package com.aismartstore.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentsService;
import com.aismartstore.backend.documents.IngestOutcome;
import com.aismartstore.backend.ingest.BatchKind;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.ingest.UploadBatchService;
import com.aismartstore.backend.ingest.dto.InitUploadSessionDto;
import com.aismartstore.backend.personal.PersonalFile;
import com.aismartstore.backend.personal.PersonalService;
import com.aismartstore.backend.personal.dto.OpenPersonalUploadSessionDto;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Unit coverage for chunked-file-upload-01-ingest-chunked-upload.md §4
 * (Task B7). The idempotent-replay behaviour of {@code complete()} (§4.4) is
 * the most important case here per the spec's §7 risk note.
 */
class UploadSessionServiceTest {

    private UploadSessionRepository uploadSessionRepository;
    private UploadBatchService uploadBatchService;
    private DocumentsService documentsService;
    private PersonalService personalService;
    private SystemConfigService systemConfigService;
    private UploadSessionService service;

    private final KeycloakUser officer = new KeycloakUser("officer-sub", "officer", List.of("can-bo"));
    private final UUID batchId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private Path createdTempFile;

    @BeforeEach
    void setUp() {
        uploadSessionRepository = mock(UploadSessionRepository.class);
        uploadBatchService = mock(UploadBatchService.class);
        documentsService = mock(DocumentsService.class);
        personalService = mock(PersonalService.class);
        systemConfigService = mock(SystemConfigService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new UploadSessionService(
                uploadSessionRepository, uploadBatchService, documentsService, personalService, systemConfigService,
                objectMapper, mock(AuditService.class), mock(AuditRequestContext.class));

        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES))
                .thenReturn(2_000_000_000L);
        when(systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_SIZE_BYTES)).thenReturn(8L * 1024 * 1024);
        // Mirrors real Hibernate @GeneratedValue behaviour (id populated on save) - needed now that
        // init()/initPersonal() read saved.getId() for their audit call right after saving. No
        // public setId() (Hibernate assigns @GeneratedValue ids via reflection, not a setter).
        when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
            UploadSession session = inv.getArgument(0);
            if (session.getId() == null) {
                ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
            }
            return session;
        });
    }

    @AfterEach
    void cleanupTempFile() throws IOException {
        if (createdTempFile != null) {
            Files.deleteIfExists(createdTempFile);
        }
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(Throwable ex) {
        return (String) ((Map<String, Object>) ((ApiException) ex).getBody()).get("code");
    }

    private static UploadBatch batch(BatchKind kind) {
        UploadBatch batch = new UploadBatch();
        batch.setKind(kind);
        return batch;
    }

    private static void setId(UploadSession session, UUID id) {
        ReflectionTestUtils.setField(session, "id", id);
    }

    private UploadSession openSession(long declaredSize, long chunkSize, long receivedBytes, byte[] existingContent)
            throws IOException {
        Path tempFile = Files.createTempFile("test-upload-session-", ".tmp");
        createdTempFile = tempFile;
        if (existingContent != null) {
            Files.write(tempFile, existingContent);
        }
        UploadSession session = new UploadSession();
        session.setBatchId(batchId);
        session.setOriginalName("a.txt");
        session.setRelativePath("");
        session.setDeclaredSizeBytes(declaredSize);
        session.setChunkSizeBytes(chunkSize);
        session.setReceivedBytes(receivedBytes);
        session.setTempFilePath(tempFile.toString());
        session.setStatus(UploadSessionStatus.OPEN);
        session.setCreatedBySub(officer.sub());
        session.setCreatedByUsername(officer.preferredUsername());
        OffsetDateTime now = OffsetDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(now.plusHours(24));
        return session;
    }

    private Document warehouseDocument() {
        Document document = new Document();
        document.setOriginalName("a.txt");
        document.setMimeType("text/plain");
        document.setSize(5);
        document.setSha256("abc");
        document.setStorageKey("documents/xyz");
        document.setRelativePath("");
        document.setFolderId(UUID.randomUUID());
        document.setUploaderSub(officer.sub());
        document.setUploaderUsername(officer.preferredUsername());
        document.setStatus("clean");
        document.setCreatedAt(OffsetDateTime.now());
        return document;
    }

    private static PersonalFile personalFile() {
        PersonalFile file = new PersonalFile();
        file.setOwnerSub("officer-sub");
        file.setOwnerUsername("officer");
        file.setOriginalName("a.txt");
        file.setMimeType("text/plain");
        file.setSize(5);
        file.setSha256("abc");
        file.setStorageKey("officer-sub/xyz");
        file.setCreatedAt(OffsetDateTime.now());
        return file;
    }

    // ---- init (§4.1) --------------------------------------------------------

    @Test
    void initRejectsPersonalBatchWithBatchNotFound() {
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.PERSONAL));

        InitUploadSessionDto dto = new InitUploadSessionDto("a.txt", "", 100L, "text/plain", null);
        assertThatThrownBy(() -> service.init(officer, batchId, dto))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("BATCH_NOT_FOUND"));
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void initRejectsDeclaredSizeAboveMaxFileSizeWithoutCreatingARow() {
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.WAREHOUSE));
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES)).thenReturn(100L);

        InitUploadSessionDto dto = new InitUploadSessionDto("a.txt", "", 200L, "text/plain", null);
        assertThatThrownBy(() -> service.init(officer, batchId, dto))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("FILE_TOO_LARGE"));
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void initPropagatesNonOpenBatchFailureFromLoadOpenBatch() {
        when(uploadBatchService.loadOpenBatch(batchId))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, Map.of("code", "BATCH_CLOSED"), "closed"));

        InitUploadSessionDto dto = new InitUploadSessionDto("a.txt", "", 100L, null, null);
        assertThatThrownBy(() -> service.init(officer, batchId, dto)).isInstanceOf(ApiException.class);
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void initCreatesAnOpenSessionWithTheConfiguredChunkSize() throws IOException {
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.WAREHOUSE));
        when(systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_SIZE_BYTES)).thenReturn(1024L);

        InitUploadSessionDto dto = new InitUploadSessionDto("a.txt", "sub", 500L, "text/plain", "a note");
        UploadSession session = service.init(officer, batchId, dto);
        createdTempFile = Path.of(session.getTempFilePath());

        assertThat(session.getChunkSizeBytes()).isEqualTo(1024L);
        assertThat(session.getReceivedBytes()).isZero();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.OPEN);
        assertThat(session.getDeclaredSizeBytes()).isEqualTo(500L);
        assertThat(session.getRelativePath()).isEqualTo("sub");
        assertThat(session.getNote()).isEqualTo("a note");
        assertThat(Files.exists(createdTempFile)).isTrue();
        verify(uploadSessionRepository).save(session);
    }

    // ---- initPersonal (chunked-file-upload-02-personal-chunked-upload.md §4.1) ----

    @Test
    void initPersonalRejectsAWarehouseBatchWithTheSame404LoadOwnedOpenBatchWouldGive() {
        // §4.1: PersonalService.loadOwnedOpenBatch is owner+kind scoped - a
        // WAREHOUSE batch id (even one owned by this same user) already comes
        // back "not found" from that query, mirrored here by mocking the
        // exact failure mode instead of re-deriving it inside UploadSessionService.
        when(personalService.loadOwnedOpenBatch(officer, batchId)).thenThrow(new NotFoundException("Batch not found"));

        OpenPersonalUploadSessionDto dto = new OpenPersonalUploadSessionDto("a.txt", 100L, null, "text/plain");
        assertThatThrownBy(() -> service.initPersonal(officer, batchId, dto)).isInstanceOf(ApiException.class);
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void initPersonalRejectsABatchOwnedByAnotherUserTheSameWay() {
        when(personalService.loadOwnedOpenBatch(officer, batchId)).thenThrow(new NotFoundException("Batch not found"));

        OpenPersonalUploadSessionDto dto = new OpenPersonalUploadSessionDto("a.txt", 100L, null, "text/plain");
        assertThatThrownBy(() -> service.initPersonal(officer, batchId, dto)).isInstanceOf(ApiException.class);
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void initPersonalRejectsAFolderNotOwnedByTheCallerBeforeCreatingARow() {
        when(personalService.loadOwnedOpenBatch(officer, batchId)).thenReturn(batch(BatchKind.PERSONAL));
        UUID foreignFolderId = UUID.randomUUID();
        doThrow(new NotFoundException("Folder not found"))
                .when(personalService)
                .assertOwnedFolder(officer, foreignFolderId);

        OpenPersonalUploadSessionDto dto = new OpenPersonalUploadSessionDto("a.txt", 100L, foreignFolderId, "text/plain");
        assertThatThrownBy(() -> service.initPersonal(officer, batchId, dto)).isInstanceOf(ApiException.class);
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void initPersonalRejectsDeclaredSizeAboveMaxFileSizeWithoutCreatingARowOrCheckingQuota() {
        when(personalService.loadOwnedOpenBatch(officer, batchId)).thenReturn(batch(BatchKind.PERSONAL));
        when(systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES)).thenReturn(100L);

        OpenPersonalUploadSessionDto dto = new OpenPersonalUploadSessionDto("a.txt", 200L, null, "text/plain");
        assertThatThrownBy(() -> service.initPersonal(officer, batchId, dto))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("FILE_TOO_LARGE"));
        verify(uploadSessionRepository, never()).save(any());
        // AC-7: no quota-related interaction with PersonalService beyond the
        // batch lookup above - the only method called is loadOwnedOpenBatch.
        verify(personalService, times(1)).loadOwnedOpenBatch(officer, batchId);
        verifyNoMoreInteractions(personalService);
    }

    @Test
    void initPersonalCreatesAnOpenSessionWithTheTargetFolderIdAndDoesNotCheckQuota() throws IOException {
        when(personalService.loadOwnedOpenBatch(officer, batchId)).thenReturn(batch(BatchKind.PERSONAL));
        when(systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_SIZE_BYTES)).thenReturn(1024L);
        UUID folderId = UUID.randomUUID();

        OpenPersonalUploadSessionDto dto = new OpenPersonalUploadSessionDto("a.txt", 500L, folderId, "text/plain");
        UploadSession session = service.initPersonal(officer, batchId, dto);
        createdTempFile = Path.of(session.getTempFilePath());

        assertThat(session.getChunkSizeBytes()).isEqualTo(1024L);
        assertThat(session.getReceivedBytes()).isZero();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.OPEN);
        assertThat(session.getDeclaredSizeBytes()).isEqualTo(500L);
        assertThat(session.getTargetPersonalFolderId()).isEqualTo(folderId);
        assertThat(session.getNote()).isNull();
        assertThat(Files.exists(createdTempFile)).isTrue();
        verify(uploadSessionRepository).save(session);
        // AC-7: no quota check at init - only the batch (and folder, if any) lookups.
        verify(personalService, times(1)).loadOwnedOpenBatch(officer, batchId);
        verify(personalService, times(1)).assertOwnedFolder(officer, folderId);
        verify(personalService, never()).uploadFileInBatch(any(), any(), any(), any());
        verify(personalService, never()).getQuota(any());
    }

    // ---- chunk (§4.2) --------------------------------------------------------

    @Test
    void appendChunkWritesBytesAndAdvancesReceivedBytes() throws IOException {
        UploadSession session = openSession(10, 5, 0, null);
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        byte[] chunk = "hello".getBytes(StandardCharsets.UTF_8);
        UploadSession result = service.appendChunk(officer, sessionId, 0, new ByteArrayInputStream(chunk));

        assertThat(result.getReceivedBytes()).isEqualTo(5);
        assertThat(Files.readAllBytes(Path.of(session.getTempFilePath()))).isEqualTo(chunk);
    }

    @Test
    void appendChunkRejectsOffsetMismatchAndDoesNotWrite() throws IOException {
        UploadSession session = openSession(10, 5, 0, null);
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        byte[] chunk = "hello".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.appendChunk(officer, sessionId, 3, new ByteArrayInputStream(chunk)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("CHUNK_OFFSET_MISMATCH"));
        assertThat(Files.size(Path.of(session.getTempFilePath()))).isZero();
    }

    @Test
    void appendChunkRejectsOverflowAndRollsBackTheFile() throws IOException {
        UploadSession session = openSession(4, 10, 0, null);
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        byte[] chunk = "hello".getBytes(StandardCharsets.UTF_8); // 5 bytes > declared 4
        assertThatThrownBy(() -> service.appendChunk(officer, sessionId, 0, new ByteArrayInputStream(chunk)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("CHUNK_OVERFLOW"));
        assertThat(Files.size(Path.of(session.getTempFilePath()))).isZero();
        assertThat(session.getReceivedBytes()).isZero();
    }

    @Test
    void appendChunkRejectsWhenSessionIsNotOpen() throws IOException {
        UploadSession session = openSession(10, 5, 0, null);
        session.setStatus(UploadSessionStatus.COMPLETED);
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() ->
                        service.appendChunk(officer, sessionId, 0, new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("UPLOAD_SESSION_NOT_OPEN"));
    }

    // ---- complete (§4.4) ------------------------------------------------------

    @Test
    void completeThrowsIncompleteWhenBytesAreMissing() throws IOException {
        UploadSession session = openSession(10, 5, 5, null);
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(officer, sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("UPLOAD_SESSION_INCOMPLETE"));
        verify(documentsService, never()).ingestBatchFile(any(), any(), any(), any(), any());
    }

    @Test
    void completeCallsIngestBatchFileOnceWithTheSessionsRelativePathNoteAndMimeType() throws IOException {
        UploadSession session = openSession(5, 5, 5, "hello".getBytes(StandardCharsets.UTF_8));
        session.setRelativePath("sub/dir");
        session.setNote("a note");
        session.setDeclaredMimeType("text/plain");
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.WAREHOUSE));
        long[] observedSize = new long[1];
        when(documentsService.ingestBatchFile(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    MultipartFile file = invocation.getArgument(2);
                    observedSize[0] = file.getSize(); // captured while the temp file still exists
                    return new IngestOutcome.Ingested(warehouseDocument(), List.of());
                });

        UploadSessionService.CompleteResult result = service.complete(officer, sessionId);

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.body().get("status")).isEqualTo("INGESTED");
        assertThat(observedSize[0]).isEqualTo(5);

        ArgumentCaptor<MultipartFile> fileCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(documentsService, times(1))
                .ingestBatchFile(eq(officer), eq(batchId), fileCaptor.capture(), eq("sub/dir"), eq("a note"));
        // NB: size is asserted via the mock answer below, not here - by the
        // time this line runs complete()'s `finally` has already deleted the
        // temp file backing `captured`, so PathMultipartFile#getSize() would
        // read 0 instead of the real size.
        MultipartFile captured = fileCaptor.getValue();
        assertThat(captured.getOriginalFilename()).isEqualTo("a.txt");
        assertThat(captured.getContentType()).isEqualTo("text/plain");

        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(Files.exists(Path.of(session.getTempFilePath()))).isFalse();
    }

    // ---- complete dispatch (chunked-file-upload-02-personal-chunked-upload.md §4.4, AC-5 - bắt buộc) ----

    @Test
    void completeOnAPersonalBatchCallsUploadFileInBatchOnceAndNeverIngestBatchFile() throws IOException {
        UUID folderId = UUID.randomUUID();
        UploadSession session = openSession(5, 5, 5, "hello".getBytes(StandardCharsets.UTF_8));
        session.setTargetPersonalFolderId(folderId);
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.PERSONAL));
        when(personalService.uploadFileInBatch(eq(officer), eq(batchId), eq(folderId), any()))
                .thenReturn(personalFile());

        UploadSessionService.CompleteResult result = service.complete(officer, sessionId);

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.body().get("status")).isEqualTo("STORED");
        verify(personalService, times(1)).uploadFileInBatch(eq(officer), eq(batchId), eq(folderId), any());
        // The crux of AC-5: dispatching to the personal branch must NEVER also
        // call the warehouse (ingest) branch's service.
        verify(documentsService, never()).ingestBatchFile(any(), any(), any(), any(), any());
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
    }

    @Test
    void completeOnAWarehouseBatchNeverCallsUploadFileInBatch() throws IOException {
        UploadSession session = openSession(5, 5, 5, "hello".getBytes(StandardCharsets.UTF_8));
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.WAREHOUSE));
        when(documentsService.ingestBatchFile(any(), any(), any(), any(), any()))
                .thenReturn(new IngestOutcome.Ingested(warehouseDocument(), List.of()));

        service.complete(officer, sessionId);

        // The other symmetric half of AC-5: dispatching to the warehouse branch
        // must NEVER also call the personal branch's service.
        verify(personalService, never()).uploadFileInBatch(any(), any(), any(), any());
    }

    // ---- complete quota (chunked-file-upload-02-personal-chunked-upload.md §4.4, AC-7 - bắt buộc) ----

    @Test
    void completeOnAPersonalBatchPropagatesQuotaExceededFromUploadFileInBatchAndStoresItAsARejection()
            throws IOException {
        UploadSession session = openSession(5, 5, 5, "hello".getBytes(StandardCharsets.UTF_8));
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.PERSONAL));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "QUOTA_EXCEEDED");
        body.put("message", "Vượt hạn mức lưu trữ cá nhân");
        ApiException quotaExceeded = new ApiException(HttpStatus.CONFLICT, body, "Quota exceeded");
        when(personalService.uploadFileInBatch(any(), any(), any(), any())).thenThrow(quotaExceeded);

        // AC-7: quota is only ever evaluated inside this single complete() call
        // (via uploadFileInBatch) - init()/appendChunk() never touch it (see
        // the initPersonal tests above, which assert no quota-related call at
        // that stage).
        assertThatThrownBy(() -> service.complete(officer, sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(codeOf(ex)).isEqualTo("QUOTA_EXCEEDED");
                });
        verify(personalService, times(1)).uploadFileInBatch(any(), any(), any(), any());
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(Files.exists(Path.of(session.getTempFilePath()))).isFalse();

        // Retrying complete() replays the stored rejection instead of calling
        // uploadFileInBatch (and re-evaluating quota) a second time.
        assertThatThrownBy(() -> service.complete(officer, sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("QUOTA_EXCEEDED"));
        verify(personalService, times(1)).uploadFileInBatch(any(), any(), any(), any());
    }

    @Test
    void completeOnAnAlreadyCompletedSessionReplaysWithoutCallingIngestBatchFileAgain() throws IOException {
        UploadSession session = openSession(5, 5, 5, "hello".getBytes(StandardCharsets.UTF_8));
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.WAREHOUSE));
        when(documentsService.ingestBatchFile(any(), any(), any(), any(), any()))
                .thenReturn(new IngestOutcome.Ingested(warehouseDocument(), List.of()));

        UploadSessionService.CompleteResult first = service.complete(officer, sessionId);
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);

        UploadSessionService.CompleteResult second = service.complete(officer, sessionId);

        verify(documentsService, times(1)).ingestBatchFile(any(), any(), any(), any(), any());
        assertThat(second.httpStatus()).isEqualTo(first.httpStatus());
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    void completeReplaysAStoredBusinessRejectionWithoutCallingIngestBatchFileAgain() throws IOException {
        UploadSession session = openSession(5, 5, 5, "hello".getBytes(StandardCharsets.UTF_8));
        when(uploadSessionRepository.findByIdAndCreatedBySub(sessionId, officer.sub())).thenReturn(Optional.of(session));
        when(uploadBatchService.loadOpenBatch(batchId)).thenReturn(batch(BatchKind.WAREHOUSE));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MALWARE_DETECTED");
        body.put("message", "Phát hiện mã độc (EICAR)");
        ApiException malware = new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, "Malware detected");
        when(documentsService.ingestBatchFile(any(), any(), any(), any(), any())).thenThrow(malware);

        assertThatThrownBy(() -> service.complete(officer, sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(codeOf(ex)).isEqualTo("MALWARE_DETECTED");
                });
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(Files.exists(Path.of(session.getTempFilePath()))).isFalse();

        assertThatThrownBy(() -> service.complete(officer, sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(codeOf(ex)).isEqualTo("MALWARE_DETECTED");
                });
        verify(documentsService, times(1)).ingestBatchFile(any(), any(), any(), any(), any());
    }

    // ---- ownership (AC-7) -------------------------------------------------------

    @Test
    void chunkStatusAndCompleteAllReturn403ForAWrongOwner() {
        when(uploadSessionRepository.findByIdAndCreatedBySub(eq(sessionId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        service.appendChunk(officer, sessionId, 0, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(codeOf(ex)).isEqualTo("UPLOAD_SESSION_FORBIDDEN");
                });

        assertThatThrownBy(() -> service.getStatus(officer, sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(codeOf(ex)).isEqualTo("UPLOAD_SESSION_FORBIDDEN");
                });

        assertThatThrownBy(() -> service.complete(officer, sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(codeOf(ex)).isEqualTo("UPLOAD_SESSION_FORBIDDEN");
                });
    }

    // ---- cleanup (chunked-file-upload-03-session-cleanup.md §4.2/§5) ----------------

    @Test
    void findExpiredSessionIdsDelegatesToTheStatusAndExpiresAtBeforeQueryAndMapsToIds() {
        UploadSession s1 = new UploadSession();
        setId(s1, sessionId);
        UUID otherId = UUID.randomUUID();
        UploadSession s2 = new UploadSession();
        setId(s2, otherId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        when(uploadSessionRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                        eq(UploadSessionStatus.OPEN), cutoffCaptor.capture(), pageableCaptor.capture()))
                .thenReturn(List.of(s1, s2));

        List<UUID> result = service.findExpiredSessionIds(500);

        assertThat(result).containsExactly(sessionId, otherId);
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 500));
        assertThat(cutoffCaptor.getValue()).isBeforeOrEqualTo(OffsetDateTime.now());
    }

    @Test
    void expireIfDueFlipsAnEligibleOpenSessionToExpiredAndReturnsItsTempFilePath() throws IOException {
        UploadSession session = openSession(10, 5, 3, null);
        session.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        UploadSessionService.ExpiryOutcome outcome = service.expireIfDue(sessionId);

        assertThat(outcome).isNotNull();
        assertThat(outcome.tempFilePath()).isEqualTo(session.getTempFilePath());
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
        verify(uploadSessionRepository, times(1)).save(session);
    }

    @Test
    void expireIfDueCalledTwiceOnTheSameAlreadyExpiredSessionIsANoOpTheSecondTime() throws IOException {
        UploadSession session = openSession(10, 5, 3, null);
        session.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        assertThat(service.expireIfDue(sessionId)).isNotNull();
        assertThat(service.expireIfDue(sessionId)).isNull();
        verify(uploadSessionRepository, times(1)).save(session);
    }

    @Test
    void expireIfDueReturnsNullWhenTheSessionIdDoesNotExist() {
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.empty());

        assertThat(service.expireIfDue(sessionId)).isNull();
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void expireIfDueLeavesACompletedSessionWithAPastExpiresAtAlone() throws IOException {
        UploadSession session = openSession(5, 5, 5, "hello".getBytes(StandardCharsets.UTF_8));
        session.setStatus(UploadSessionStatus.COMPLETED);
        session.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        assertThat(service.expireIfDue(sessionId)).isNull();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        verify(uploadSessionRepository, never()).save(any());
    }

    /**
     * The race case described in §4.2/§7: a chunk PUT renewing {@code
     * expires_at} to the future right before the cleanup job wins the row
     * lock on the same session must NOT be expired out from under it. This is
     * the one test the spec marks as mandatory.
     */
    @Test
    void expireIfDueDoesNotExpireASessionThatWasJustRenewedPastNow() throws IOException {
        UploadSession session = openSession(10, 5, 3, null);
        session.setExpiresAt(OffsetDateTime.now().minusMinutes(1)); // was due...
        when(uploadSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        // ...but a concurrent appendChunk-style renewal (§4.2 step 4 of spec 01)
        // pushes expires_at back into the future before expireIfDue's lock is
        // (conceptually) acquired.
        session.setExpiresAt(OffsetDateTime.now().plusHours(24));

        UploadSessionService.ExpiryOutcome outcome = service.expireIfDue(sessionId);

        assertThat(outcome).isNull();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.OPEN);
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void deleteTempFileReturnsDeletedWhenTheFileExists() throws IOException {
        Path tempFile = Files.createTempFile("cleanup-test-", ".tmp");
        createdTempFile = tempFile;

        UploadSessionService.ExpiryOutcome outcome = new UploadSessionService.ExpiryOutcome(tempFile.toString());
        assertThat(outcome.deleteTempFile()).isEqualTo(UploadSessionService.TempFileDeleteResult.DELETED);
        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    void deleteTempFileReturnsAlreadyMissingWhenTheFileIsGone() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "does-not-exist-" + UUID.randomUUID() + ".tmp");

        UploadSessionService.ExpiryOutcome outcome = new UploadSessionService.ExpiryOutcome(missing.toString());
        assertThat(outcome.deleteTempFile()).isEqualTo(UploadSessionService.TempFileDeleteResult.ALREADY_MISSING);
    }

    @Test
    void deleteTempFileReturnsDeleteFailedWhenTheFileCannotBeDeletedInsteadOfThrowing() throws IOException {
        // Files.deleteIfExists throws DirectoryNotEmptyException (an IOException)
        // for a non-empty directory - an easy, real way to force the failure
        // branch without mocking java.nio.file.Files.
        Path dir = Files.createTempDirectory("cleanup-test-dir-");
        Files.createFile(dir.resolve("child.tmp"));
        try {
            UploadSessionService.ExpiryOutcome outcome = new UploadSessionService.ExpiryOutcome(dir.toString());
            assertThat(outcome.deleteTempFile()).isEqualTo(UploadSessionService.TempFileDeleteResult.DELETE_FAILED);
        } finally {
            Files.deleteIfExists(dir.resolve("child.tmp"));
            Files.deleteIfExists(dir);
        }
    }
}
