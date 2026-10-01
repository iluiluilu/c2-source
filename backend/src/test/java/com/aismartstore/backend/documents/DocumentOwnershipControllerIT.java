package com.aismartstore.backend.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.KcRole;
import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.digitization.results.NewSegment;
import com.aismartstore.backend.digitization.results.NewSoundEvent;
import com.aismartstore.backend.digitization.results.SegmentRevisionRepository;
import com.aismartstore.backend.digitization.results.SoundEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * digitization-review-03-ownership-transfer.md §5 "Integration test": real
 * Postgres (Flyway through V28) + MockMvc + mocked {@link
 * KeycloakAdminService} (same rationale as {@code AccountsControllerIT} -
 * there is no Keycloak Testcontainers module here yet).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class DocumentOwnershipControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/unused-jwks");
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @MockBean
    private KeycloakAdminService kc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private DigitizationRunRepository digitizationRunRepository;

    @Autowired
    private ContentSegmentRepository contentSegmentRepository;

    @Autowired
    private SegmentRevisionRepository segmentRevisionRepository;

    @Autowired
    private SoundEventRepository soundEventRepository;

    @Autowired
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;

    @Autowired
    private DigitizationRunLifecycleService lifecycleService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private UUID folderId;

    @BeforeEach
    void resetState() {
        segmentRevisionRepository.deleteAll();
        soundEventRepository.deleteAll();
        contentSegmentRepository.deleteAll();
        documentDigitizationStatusRepository.deleteAll();
        digitizationRunRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        documentRepository.deleteAll();

        List<WarehouseFolder> all = new ArrayList<>(warehouseFolderRepository.findAll());
        all.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(all);

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        folderId = warehouseFolderRepository.save(folder).getId();
    }

    private static JwtRequestPostProcessor asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(RolePermissionFixtures.authoritiesFor(springRole));
    }

    /** R3 - QUAN_TRI: {@code document.transfer_ownership} (V28), the only role holding it. */
    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private static JwtRequestPostProcessor officer(String username) {
        return asRole(username, "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor leader() {
        return asRole("leader-1", "lanh-dao", "LANH_DAO");
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("scan-" + UUID.randomUUID() + ".png");
        document.setMimeType("image/png");
        document.setSize(10);
        document.setSha256((UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    private UUID saveSource(UUID documentId) {
        DigitizationSource source = new DigitizationSource();
        source.setId(UUID.randomUUID());
        source.setDocumentId(documentId);
        source.setComponentType(DigitizationSource.COMPONENT_ORIGINAL);
        source.setFilePath("bucket/" + documentId);
        source.setMimeType("image/png");
        source.setDispatchable(true);
        source.setDispatchStatus(DigitizationSource.DISPATCH_PENDING);
        OffsetDateTime now = OffsetDateTime.now();
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        return digitizationSourceRepository.save(source).getId();
    }

    /** An OCR run, {@code succeeded}, {@code review_state=pending}, with one segment. */
    private DigitizationRun seedPendingOcrRunWithOneSegment(UUID documentId, UUID sourceId) {
        DigitizationRun run = lifecycleService.createRun(documentId, sourceId, DigitizationRun.TASK_OCR, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
        lifecycleService.writeSegments(run.getId(), List.of(new NewSegment(
                0, ContentSegment.SOURCE_OCR, null, 1, null, 0, null, null, null, null, null, null,
                "original text", new BigDecimal("0.900"))));
        return digitizationRunRepository.findById(run.getId()).orElseThrow();
    }

    /**
     * A SED run, {@code succeeded}, {@code review_state=pending}, one
     * promoted detection - the explicit {@code transitionReviewState} call
     * (a no-op on the run's own {@code review_state}, already {@code
     * pending} since {@code createRun}) is what makes {@code
     * document_digitization_status.sed_status} become {@code
     * pending_review} (real pipeline: {@code completeRunSuccess} does this
     * as one step; {@code writeSoundEvents} alone does not, unlike {@code
     * writeSegments} for ocr/stt/video_summary).
     */
    private DigitizationRun seedPendingSedRun(UUID documentId, UUID sourceId) {
        DigitizationRun run = lifecycleService.createRun(documentId, sourceId, DigitizationRun.TASK_SED, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
        lifecycleService.writeSoundEvents(run.getId(), List.of(
                new NewSoundEvent("Dog", "Chó sủa", new BigDecimal("0.900"), 0, 1000)));
        lifecycleService.transitionReviewState(run.getId(), DigitizationRun.REVIEW_PENDING, null, null);
        return digitizationRunRepository.findById(run.getId()).orElseThrow();
    }

    private static void mockBValid(KeycloakAdminService kc, String bSub) {
        when(kc.getUser(bSub)).thenReturn(Map.of("id", bSub, "username", "user-" + bSub, "enabled", true));
        when(kc.listUserRealmRoles(bSub)).thenReturn(List.of(new KcRole("r1", "can-bo")));
    }

    // ---- AC-1/AC-8: transfer + audit ---------------------------------------------

    @Test
    void transfer_validB_updatesUploaderAndReturns200() throws Exception {
        Document document = saveDocument("officer-a");
        mockBValid(kc, "officer-b");

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-b", "reason", "nghỉ việc"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.fromUploaderSub").value("officer-a"))
                .andExpect(jsonPath("$.toUploaderSub").value("officer-b"));

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getUploaderSub())
                .isEqualTo("officer-b");

        List<AuditLog> rows = auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(
                "document", document.getId().toString());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo("document.ownership_transferred");
        assertThat(rows.get(0).getMetadata())
                .containsEntry("fromUploaderSub", "officer-a")
                .containsEntry("toUploaderSub", "officer-b");
    }

    @Test
    void transfer_chainedTwice_eachAuditRowHasItsOwnFromUploaderSub() throws Exception {
        Document document = saveDocument("officer-a");
        mockBValid(kc, "officer-b");

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-b"))))
                .andExpect(status().isOk());

        mockBValid(kc, "officer-c");
        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-c"))))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(
                "document", document.getId().toString());
        assertThat(rows).hasSize(2);
        // findByResourceTypeAndResourceIdOrderByIdDesc: index 0 = most recent (B -> C).
        assertThat(rows.get(0).getMetadata()).containsEntry("fromUploaderSub", "officer-b")
                .containsEntry("toUploaderSub", "officer-c");
        assertThat(rows.get(1).getMetadata()).containsEntry("fromUploaderSub", "officer-a")
                .containsEntry("toUploaderSub", "officer-b");
    }

    // ---- AC-1: B validation --------------------------------------------------

    @Test
    void transfer_bSameAsCurrentUploader_returns400() throws Exception {
        Document document = saveDocument("officer-a");

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-a"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transfer_bAdminOnlyRole_returns400() throws Exception {
        Document document = saveDocument("officer-a");
        when(kc.getUser("admin-x")).thenReturn(Map.of("id", "admin-x", "username", "adminX", "enabled", true));
        when(kc.listUserRealmRoles("admin-x")).thenReturn(List.of(new KcRole("r1", "quan-tri")));

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "admin-x"))))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Lãnh đạo (R2)")));

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getUploaderSub())
                .isEqualTo("officer-a");
    }

    @Test
    void transfer_bIsLeaderRole_returns200AndUpdatesUploader() throws Exception {
        Document document = saveDocument("officer-a");
        when(kc.getUser("leader-x")).thenReturn(Map.of("id", "leader-x", "username", "leaderX", "enabled", true));
        when(kc.listUserRealmRoles("leader-x")).thenReturn(List.of(new KcRole("r1", "lanh-dao")));

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "leader-x"))))
                .andExpect(status().isOk());

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getUploaderSub())
                .isEqualTo("leader-x");
    }

    @Test
    void transfer_bHasQuanTriPlusLanhDao_returns200() throws Exception {
        Document document = saveDocument("officer-a");
        when(kc.getUser("leader-y")).thenReturn(Map.of("id", "leader-y", "username", "leaderY", "enabled", true));
        when(kc.listUserRealmRoles("leader-y"))
                .thenReturn(List.of(new KcRole("r1", "quan-tri"), new KcRole("r2", "lanh-dao")));

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "leader-y"))))
                .andExpect(status().isOk());
    }

    @Test
    void transfer_documentNotFound_returns404() throws Exception {
        mockBValid(kc, "officer-b");
        mockMvc.perform(post("/admin/document-ownership/" + UUID.randomUUID() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-b"))))
                .andExpect(status().isNotFound());
    }

    // ---- AC-2/AC-3: hiệu lực tức thì -------------------------------------------

    @Test
    void afterTransfer_bSeesDocInQueue_aLosesAccessToRunDetail() throws Exception {
        Document document = saveDocument("officer-a");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        mockBValid(kc, "officer-b");

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-b"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/digitization-review/queue").with(officer("officer-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].documentId").value(document.getId().toString()));

        mockMvc.perform(get("/digitization-review/runs/" + run.getId()).with(officer("officer-a")))
                .andExpect(status().isForbidden());
    }

    // ---- AC-7: không có run đang chờ vẫn chuyển được --------------------------

    @Test
    void transfer_documentWithOnlyConfirmedRun_succeeds() throws Exception {
        Document document = saveDocument("officer-a");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        lifecycleService.confirmRun(run.getId(), "officer-a");
        mockBValid(kc, "officer-b");

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-b"))))
                .andExpect(status().isOk());
    }

    // ---- AC-4: deadline snapshot không đổi ------------------------------------

    @Test
    void transfer_doesNotChangePendingRunReviewDeadline() throws Exception {
        Document document = saveDocument("officer-a");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        OffsetDateTime deadlineBefore = digitizationRunRepository.findById(run.getId()).orElseThrow().getReviewDeadlineAt();
        mockBValid(kc, "officer-b");

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toUserSub", "officer-b"))))
                .andExpect(status().isOk());

        OffsetDateTime deadlineAfter = digitizationRunRepository.findById(run.getId()).orElseThrow().getReviewDeadlineAt();
        assertThat(deadlineAfter).isEqualTo(deadlineBefore);
    }

    // ---- quyền: chỉ QUAN_TRI ---------------------------------------------------

    @Test
    void transfer_nonAdminRoles_return403() throws Exception {
        Document document = saveDocument("officer-a");
        mockBValid(kc, "officer-b");
        String body = objectMapper.writeValueAsString(Map.of("toUserSub", "officer-b"));

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(officer("officer-c"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/document-ownership/" + document.getId() + "/transfer")
                        .with(leader())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ---- AC-5/§3.1: metadata-only, đủ 4 trạng thái ------------------------------

    @Test
    void list_returnsMetadataOnlyNoDigitizedContent() throws Exception {
        Document document = saveDocument("officer-a");
        seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/admin/document-ownership")
                        .with(admin())
                        .param("uploaderSub", "officer-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].documentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.content[0].originalName").value(document.getOriginalName()))
                .andExpect(jsonPath("$.content[0].uploaderSub").value("officer-a"))
                .andExpect(jsonPath("$.content[0].text").doesNotExist())
                .andExpect(jsonPath("$.content[0].segments").doesNotExist())
                .andExpect(jsonPath("$.content[0].currentText").doesNotExist());
    }

    @Test
    void list_digitizationStatusHasAllFourTaskFields() throws Exception {
        Document document = saveDocument("officer-a");
        seedPendingSedRun(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/admin/document-ownership")
                        .with(admin())
                        .param("uploaderSub", "officer-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].digitizationStatus.ocrStatus").value("not_applicable"))
                .andExpect(jsonPath("$.content[0].digitizationStatus.sttStatus").value("not_applicable"))
                .andExpect(jsonPath("$.content[0].digitizationStatus.sedStatus").value("pending_review"))
                .andExpect(jsonPath("$.content[0].digitizationStatus.videoSummaryStatus").value("not_applicable"));
    }
}
