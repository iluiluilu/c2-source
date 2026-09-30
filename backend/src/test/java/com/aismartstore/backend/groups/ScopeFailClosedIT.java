package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.QueryTimeoutException;
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
 * rbac-group-scope.md §5 ("Giả lập lỗi tầng scoping ... toàn bộ entrypoint
 * đọc dữ liệu nghiệp vụ trả 403, không trả data"), Story 4 AC-1/AC-2: {@link
 * GroupMemberRepository} is mocked to simulate a DB failure while resolving
 * the current user's group membership - every read entrypoint must fail
 * closed with {@code 403}, never a bare {@code 500} and never a document
 * body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ScopeFailClosedIT {

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
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentRepository documentRepository;

    // digitization-pipeline-01-source-dispatch.md V18: FK-dependent tables must
    // be cleared before documentRepository.deleteAll() (see PersonalControllerIT).
    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    private UUID rootFolderId;

    /** {@code audit_log} is append-only (DB trigger rejects DELETE) - each test uses its own random sub instead. */
    @BeforeEach
    void resetStateAndSimulateADbFailure() {
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentRepository.deleteAll();

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        rootFolderId = warehouseFolderRepository.save(folder).getId();

        when(groupMemberRepository.findGroupIdsByUserSub(anyString()))
                .thenThrow(new QueryTimeoutException("simulated group_member lookup failure"));
    }

    private static JwtRequestPostProcessor canBo(String sub) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of("can-bo"))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor("CAN_BO"));
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("file-" + UUID.randomUUID() + ".txt");
        document.setMimeType("text/plain");
        document.setSize(10);
        document.setSha256("x".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(rootFolderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    @Test
    void listDocumentsFailsClosedWith403NotA500() throws Exception {
        String user = "user-" + UUID.randomUUID();
        mockMvc.perform(get("/documents").with(canBo(user))).andExpect(status().isForbidden());
    }

    @Test
    void getOneDocumentFailsClosedWith403NotA500() throws Exception {
        String user = "user-" + UUID.randomUUID();
        Document doc = saveDocument(user); // even the caller's own document must not leak through.
        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(user))).andExpect(status().isForbidden());
    }

    @Test
    void documentNotesFailClosedWith403NotA500() throws Exception {
        String user = "user-" + UUID.randomUUID();
        Document doc = saveDocument(user);
        mockMvc.perform(get("/documents/" + doc.getId() + "/notes").with(canBo(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aFailedScopeResolutionIsLoggedToTheAuditTrail() throws Exception {
        String user = "user-" + UUID.randomUUID();
        mockMvc.perform(get("/documents").with(canBo(user))).andExpect(status().isForbidden());

        List<com.aismartstore.backend.audit.AuditLog> deniedRows = awaitAuditLogs(
                row -> "access.denied".equals(row.getAction()) && user.equals(row.getActorSub()));
        assertThat(deniedRows).isNotEmpty();
    }

    /** Polls up to ~2s for {@code @Async} audit writes to land (see AuditService.recordAsync). */
    private List<com.aismartstore.backend.audit.AuditLog> awaitAuditLogs(
            java.util.function.Predicate<com.aismartstore.backend.audit.AuditLog> matcher) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<com.aismartstore.backend.audit.AuditLog> matches =
                    auditLogRepository.findAll().stream().filter(matcher).toList();
            if (!matches.isEmpty()) {
                return matches;
            }
            Thread.sleep(50);
        }
        return List.of();
    }
}
