package com.aismartstore.backend.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * classification-notes-02-notes.md §5 ("Nếu insert outbox_event ném lỗi
 * ... toàn bộ transaction rollback"): real Postgres, real {@code @Transactional}
 * {@link NotesService#createForDocument}, only {@link OutboxEventRepository}
 * mocked to fail - proves the {@code document_note} insert is rolled back
 * together with the failed {@code outbox_event} insert (same DB
 * transaction), which a pure Mockito unit test (see {@code
 * NotesServiceTest#outboxWriteFailurePropagatesInsteadOfBeingSwallowed})
 * cannot demonstrate on its own. Deliberately its own test class (not part
 * of {@code NotesControllerIT}) since this {@code @MockBean} would make
 * every other outbox-writing test in that class fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class NotesOutboxAtomicityIT {

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
    private OutboxEventRepository outboxEventRepository;

    /**
     * Not under test here - this class is about note/outbox atomicity. Real S3
     * credentials are not configured in this environment, so {@code
     * ObjectStorageService}'s constructor throws {@code NullPointerException:
     * Access key ID cannot be blank} and takes the whole ApplicationContext
     * down with it (via {@code auditRetentionJob}, an unrelated scheduled bean
     * in the same context). Sibling ITs - {@code OutboxRetentionJobIT},
     * {@code SearchIndexOutboxPollerParkingIT}, {@code IngestControllerIT} -
     * all declare this same mock for the same reason; this class was simply
     * missing it, which only surfaced when the ITs were first actually
     * executed (2026-09-18).
     */
    @MockBean
    private ObjectStorageService objectStorageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentNoteRepository documentNoteRepository;

    private UUID folderId;

    @BeforeEach
    void resetState() {
        documentNoteRepository.deleteAll();
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

        doThrow(new RuntimeException("simulated outbox_event constraint violation"))
                .when(outboxEventRepository)
                .save(any());
    }

    private static JwtRequestPostProcessor officer() {
        return jwt().jwt(builder -> builder.subject("officer-1")
                        .claim("preferred_username", "officer-1")
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
        document.setFolderId(folderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    @Test
    void aFailedOutboxInsertRollsBackTheDocumentNoteInsertInTheSameTransaction() throws Exception {
        Document document = saveDocument("officer-1");

        // The assertion that matters is the rollback below; the request itself
        // can only fail. It used to assert `status().isInternalServerError()`,
        // which can never hold here: nothing in ApiExceptionHandler handles a
        // bare RuntimeException, and MockMvc rethrows an unhandled exception
        // instead of rendering the servlet container's 500 page. Asserting the
        // throw keeps the test honest about what actually happens without
        // inventing a catch-all @ExceptionHandler purely to satisfy it.
        assertThatThrownBy(() -> mockMvc.perform(post("/documents/" + document.getId() + "/notes")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "ghi chú sẽ bị rollback")))))
                .hasRootCauseMessage("simulated outbox_event constraint violation");

        assertThat(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(document.getId())).isEmpty();
    }
}
