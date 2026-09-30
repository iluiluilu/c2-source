package com.aismartstore.backend.digitization.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * digitization-pipeline-03-video-queue.md §5 "Integration test" bullets 2-4:
 * {@code trg_sync_video_status} (§2.3 - dropped in V32, see
 * digitization-pipeline-05-video-review.md), the {@code digitization_video_worker}
 * narrow-scope DB role (§2.4), and {@code GET /documents/{id}/video-status}
 * (§3) - all exercised against real Postgres (Testcontainers), same
 * MockMvc/JWT scaffolding as {@code DigitizationDispatchIT}. No RabbitMQ
 * container needed - nothing here publishes/consumes a queue message. Test
 * plan bullet 5 (lease reconciliation for task {@code video}) is covered at
 * the unit level instead, see {@code
 * DigitizationRunLeaseReconciliationJobTest#expiredVideoLease_isRequeuedToTheVideoQueue}
 * - that job has no task-specific branching, nothing more to prove against a
 * real DB there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class DigitizationVideoTaskIT {

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

    /**
     * Test-only operational step - production sets/rotates this via the
     * runbook (docs/00-overview/runbooks/video-worker-db-password.md), never
     * inside a Flyway migration (§2.4 comment: checksum drift on rotation).
     */
    private static final String VIDEO_WORKER_PASSWORD = "it-test-password";

    @MockBean
    private ObjectStorageService objectStorageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private DigitizationRunRepository digitizationRunRepository;

    @Autowired
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID documentId;
    private UUID sourceId;

    @BeforeEach
    void seed() {
        digitizationRunRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        documentDigitizationStatusRepository.deleteAll();
        documentRepository.deleteAll();
        List<WarehouseFolder> allFolders = new ArrayList<>(warehouseFolderRepository.findAll());
        allFolders.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(allFolders);

        // Idempotent - safe to re-run every test.
        jdbcTemplate.execute("ALTER ROLE digitization_video_worker WITH PASSWORD '" + VIDEO_WORKER_PASSWORD + "'");

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        UUID folderId = warehouseFolderRepository.save(folder).getId();

        Document document = new Document();
        document.setOriginalName("clip.mp4");
        document.setMimeType("video/mp4");
        document.setSize(10);
        document.setSha256("b".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub("officer-1");
        document.setUploaderUsername("officer-1");
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        documentId = documentRepository.save(document).getId();

        DigitizationSource source = new DigitizationSource();
        source.setId(UUID.randomUUID());
        source.setDocumentId(documentId);
        source.setComponentType(DigitizationSource.COMPONENT_VIDEO);
        source.setFilePath("bucket/derived/" + documentId + "/video.mp4");
        source.setMimeType("video/mp4");
        source.setDispatchable(true);
        source.setDispatchStatus(DigitizationSource.DISPATCH_PUBLISHED);
        OffsetDateTime now = OffsetDateTime.now();
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        sourceId = digitizationSourceRepository.save(source).getId();
    }

    private static JwtRequestPostProcessor officer() {
        return jwt().jwt(builder -> builder.subject("officer-1")
                        .claim("preferred_username", "officer-1")
                        .claim("realm_access", Map.of("roles", List.of("can-bo"))))
                .authorities(RolePermissionFixtures.authoritiesFor("CAN_BO"));
    }

    /** QUAN_TRI never carries {@code PERM_document.read} (NFR-07) - deliberately used for the 403 case below. */
    private static JwtRequestPostProcessor noDocumentReadPermission() {
        return jwt().jwt(builder -> builder.subject("admin-1")
                        .claim("preferred_username", "admin-1")
                        .claim("realm_access", Map.of("roles", List.of("quan-tri"))))
                .authorities(RolePermissionFixtures.authoritiesFor("QUAN_TRI"));
    }

    private DigitizationRun newVideoRun() {
        DigitizationRun run = new DigitizationRun();
        run.setDocumentId(documentId);
        run.setSourceId(sourceId);
        run.setTask(DigitizationRun.TASK_VIDEO);
        run.setProcessingStatus(DigitizationRun.PROCESSING_QUEUED);
        run.setReviewState(DigitizationRun.REVIEW_PENDING);
        OffsetDateTime now = OffsetDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    // ---- digitization-pipeline-05-video-review.md (V32): trigger V31 §2.3 đã bỏ ----

    @Test
    void videoRunSucceededBySql_noLongerTouchesDocumentDigitizationStatus() {
        DigitizationRun run = digitizationRunRepository.saveAndFlush(newVideoRun());

        // V32: video_status chỉ do DigitizationRunLifecycleServiceImpl ghi (qua
        // q.result) - UPDATE thẳng SQL không còn trigger đồng bộ nào.
        jdbcTemplate.update(
                "UPDATE digitization_run SET processing_status = 'succeeded', updated_at = now() WHERE id = ?",
                run.getId());

        assertThat(documentDigitizationStatusRepository.findById(documentId)).isEmpty();
    }

    // ---- §2.4 role: digitization_video_worker cannot write outside its GRANT list ----

    @Test
    void videoWorkerRole_cannotWriteDocumentDigitizationSourceOrContentSegment() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), "digitization_video_worker", VIDEO_WORKER_PASSWORD)) {
            assertPermissionDenied(connection, "UPDATE document SET original_name = 'x' WHERE id = gen_random_uuid()");
            assertPermissionDenied(
                    connection, "UPDATE digitization_source SET dispatch_status = 'pending' WHERE id = gen_random_uuid()");
            assertPermissionDenied(
                    connection, "UPDATE content_segment SET current_text = 'x' WHERE id = gen_random_uuid()");
        }
    }

    private static void assertPermissionDenied(Connection connection, String sql) {
        assertThatThrownBy(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    }

    // ---- §3 GET /documents/{id}/video-status -------------------------------

    @Test
    void videoStatusEndpoint_reflectsStoredVideoStatus_andEnforcesPermDocumentRead() throws Exception {
        mockMvc.perform(get("/documents/" + documentId + "/video-status").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("not_applicable"));

        jdbcTemplate.update(
                "INSERT INTO document_digitization_status (document_id, video_status, overall_status, updated_at) "
                        + "VALUES (?, 'pending_review', 'pending_review', now())",
                documentId);

        mockMvc.perform(get("/documents/" + documentId + "/video-status").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"));

        mockMvc.perform(get("/documents/" + documentId + "/video-status").with(noDocumentReadPermission()))
                .andExpect(status().isForbidden());
    }
}
