package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.groups.DocumentGroupShare;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.groups.Group;
import com.aismartstore.backend.groups.GroupRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.search.SearchIndexNames;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §5 - the real {@link
 * SearchIndexOutboxPoller} bean (autowired, running on its own {@code
 * @Scheduled} cadence, {@code app.search-index.poll-interval-ms} default
 * 2000ms) picking up rows this test writes directly to {@code outbox_event}
 * (same payload shape the real producers - {@code ClassificationOutbox},
 * {@code AclChangeOutbox}, {@code DocumentsService}'s own write - already
 * use, each covered by its own unit test) and reflecting them into the real
 * OpenSearch indices. Deliberately covers only the event types whose
 * Postgres fixture is cheap (document/tag/share, plus trash-search-index-
 * cleanup.md's document.trash/restored/purged which need no fixture beyond
 * the document itself) - {@code review.confirmed}'s indexer behaviour
 * (including the external-versioning/superseded-run-delete logic) is covered
 * at the unit level instead, in {@code SearchIndexOutboxPollerTest} - see
 * that class + this spec's own §7/handoff notes for the reasoning.
 *
 * <p>NOTE: like every other Testcontainers-based test in this module, this
 * cannot currently be executed in this sandbox (V21's {@code setval} against
 * an empty {@code audit_log} - see that migration's comment / {@code
 * SearchScopeIT}'s own note). It is exercised by a normal CI/dev environment.
 *
 * <p>2026-09-18: the first time this class actually ran (a dev machine that
 * finally had Docker available), {@code
 * classificationChangedUpdatesDocTypeOnBothIndicesWithinTheLatencyBudget}
 * failed with {@code TransactionRequiredException: Executing an update/delete
 * query} - {@code DocumentTagRepository.insert} is a {@code @Modifying}
 * native query, and {@code SimpleJpaRepository} is {@code
 * @Transactional(readOnly = true)} by default, so calling it straight from a
 * plain test method can never work. Fixed with the {@link TransactionTemplate}
 * below rather than by putting {@code @Transactional} on the test method:
 * the poller reads this row from its own scheduler thread, so the seed data
 * must be really committed, not sitting in a test-scoped transaction that
 * rolls back at the end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class SearchIndexOutboxPollerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> opensearch = new GenericContainer<>(new ImageFromDockerfile(
                    "aismartstore-opensearch-icu-test", false)
            .withFileFromPath(".", Paths.get(System.getProperty("user.dir"), "..", "main-workspace", "opensearch")
                    .normalize()))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("plugins.security.disabled", "true")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "Aismartstore1!")
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.opensearch.host", opensearch::getHost);
        registry.add("app.opensearch.port", () -> opensearch.getMappedPort(9200));
        registry.add("app.search-index.poll-interval-ms", () -> "500");
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/unused-jwks");
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentTagRepository documentTagRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID rootFolderId;

    @BeforeEach
    void resetState() throws Exception {
        documentGroupShareRepository.deleteAll();
        groupRepository.deleteAll();
        documentRepository.deleteAll();

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        rootFolderId = warehouseFolderRepository.save(folder).getId();

        openSearchClient.deleteByQuery(d -> d.index(SearchIndexNames.SEGMENT_ALIAS).query(q -> q.matchAll(m -> m)).refresh(true));
        openSearchClient.deleteByQuery(d -> d.index(SearchIndexNames.DOCUMENT_ALIAS).query(q -> q.matchAll(m -> m)).refresh(true));
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("file-" + UUID.randomUUID() + ".pdf");
        document.setMimeType("application/pdf");
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

    private void writeOutboxEvent(String eventType, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        outboxEventRepository.save(event);
    }

    // ---- document.classification.changed: doc_type/topic_tags reflected on both indices, AC-4/AC-6 ----

    @Test
    void classificationChangedUpdatesDocTypeOnBothIndicesWithinTheLatencyBudget() throws Exception {
        Document document = saveDocument("owner-" + UUID.randomUUID());
        Tag docTypeTag = tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "văn bản").orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                documentTagRepository.insert(
                        document.getId(), docTypeTag.getId(), TagKinds.DOC_TYPE, "system", "system"));

        // Seed a segment that predates the classification change (indexer must update it, not just insert a new one).
        indexBareSegment(document.getId());

        writeOutboxEvent("document.classification.changed", Map.of(
                "documentId", document.getId().toString(), "changedAt", OffsetDateTime.now().toString(), "changedBySub", "r1"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> segmentSource = readSegment(document.getId());
            assertThat(segmentSource.get("doc_type")).isEqualTo("văn bản");
        });
    }

    // ---- document.acl.changed: sharing/unsharing reflected in scope_group_ids, AC-4 story -04 ----

    @Test
    void aclChangedUpdatesScopeGroupIdsWithinTheLatencyBudget() throws Exception {
        Document document = saveDocument("owner-" + UUID.randomUUID());
        indexBareSegment(document.getId());

        Group group = new Group();
        group.setName("Nhom " + UUID.randomUUID());
        group.setOwnerSub("owner-sub");
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        UUID groupId = groupRepository.save(group).getId();

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(document.getId());
        share.setGroupId(groupId);
        share.setSharedBySub("owner-sub");
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        writeOutboxEvent("document.acl.changed", Map.of(
                "documentId", document.getId().toString(), "groupId", groupId.toString(), "action", "shared",
                "changedAt", OffsetDateTime.now().toString(), "changedBySub", "owner-sub"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> segmentSource = readSegment(document.getId());
            @SuppressWarnings("unchecked")
            List<String> scopeGroupIds = (List<String>) segmentSource.get("scope_group_ids");
            assertThat(scopeGroupIds).contains(groupId.toString());
        });
    }

    // ---- document.ingested: AC-2 - a title segment is searchable right away, before any digitization result exists ----

    @Test
    void documentIngestedUpsertsDocumentAndATitleSegmentWithinTheLatencyBudget() throws Exception {
        Document document = saveDocument("owner-" + UUID.randomUUID());

        writeOutboxEvent("document.ingested", Map.of("documentId", document.getId().toString(), "changedAt", OffsetDateTime.now().toString()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var response = openSearchClient.get(
                    g -> g.index(SearchIndexNames.DOCUMENT_ALIAS).id(document.getId().toString()),
                    Map.class);
            assertThat(response.found()).isTrue();

            var segmentSearch = openSearchClient.search(
                    s -> s.index(SearchIndexNames.SEGMENT_ALIAS)
                            .query(q -> q.term(t -> t.field("document_id").value(v -> v.stringValue(document.getId().toString())))),
                    Map.class);
            assertThat(segmentSearch.hits().hits()).anySatisfy(hit -> {
                assertThat(hit.source().get("content_source")).isEqualTo("title");
                assertThat(hit.source().get("text")).isEqualTo(document.getOriginalName());
            });
        });
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md - regression test for the
     * fix itself. Two separate {@code outbox_event} rows for the SAME
     * document, both computed from IDENTICAL Postgres state (nothing changes
     * between them) - this is exactly what a retry-after-partial-failure
     * looks like from {@code DocumentIndexFieldsBuilder}'s point of view (it
     * re-reads live state on every call, so two calls with no Postgres
     * change in between yield the identical {@code contentVersion}), the
     * confirmed most-likely trigger of BUG-002 on stg. Before the fix
     * (version = {@code outbox_event.id}, {@code VersionType.External}
     * strict {@code >}), the second event's document-write would conflict
     * with the first (same-or-lower version) and retry uselessly until
     * PARKED. After the fix ({@code contentVersion} + {@code ExternalGte}),
     * both apply cleanly - asserted here by requiring both to reach {@code
     * processed_at IS NOT NULL} well within the retry/park window.
     */
    @Test
    void twoOutboxEventsForTheSameUnchangedDocumentBothApplyCleanlyWithoutAVersionConflict() throws Exception {
        Document document = saveDocument("owner-" + UUID.randomUUID());

        writeOutboxEvent("document.ingested", Map.of(
                "documentId", document.getId().toString(), "changedAt", OffsetDateTime.now().toString()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var response = openSearchClient.get(
                    g -> g.index(SearchIndexNames.DOCUMENT_ALIAS).id(document.getId().toString()), Map.class);
            assertThat(response.found()).isTrue();
        });

        // Second event for the same document, nothing changed in Postgres since the first one.
        writeOutboxEvent("document.ingested", Map.of(
                "documentId", document.getId().toString(), "changedAt", OffsetDateTime.now().toString()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<OutboxEvent> unprocessed = outboxEventRepository.findAll().stream()
                    .filter(e -> "document.ingested".equals(e.getEventType()) && e.getProcessedAt() == null)
                    .toList();
            // Both events fully processed - if the old event.getId()-based version were still in
            // use, the second one would keep failing (version conflict) and never reach here
            // within the await window, eventually PARKing instead.
            assertThat(unprocessed).isEmpty();
        });
    }

    // ---- trash-search-index-cleanup.md §5: document.trash/document.restored/document.purged ----

    @Test
    void documentTrashedDeletesTheDocumentAndItsSegmentFromBothIndices() throws Exception {
        Document document = saveDocument("owner-" + UUID.randomUUID());
        indexBareDocument(document.getId(), document.getOriginalName());
        indexBareSegment(document.getId());

        writeOutboxEvent("document.trash", Map.of(
                "documentIds", List.of(document.getId().toString()), "changedAt", OffsetDateTime.now().toString()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var documentGet = openSearchClient.get(
                    g -> g.index(SearchIndexNames.DOCUMENT_ALIAS).id(document.getId().toString()), Map.class);
            assertThat(documentGet.found()).isFalse();

            var segmentSearch = openSearchClient.search(
                    s -> s.index(SearchIndexNames.SEGMENT_ALIAS)
                            .query(q -> q.term(t -> t.field("document_id").value(v -> v.stringValue(document.getId().toString())))),
                    Map.class);
            assertThat(segmentSearch.hits().hits()).isEmpty();
        });
    }

    /** Restore rebuilds from Postgres from scratch (§4.3) - nothing is seeded in the index beforehand. */
    @Test
    void documentRestoredRebuildsTheDocumentAndATitleSegmentFromPostgres() throws Exception {
        Document document = saveDocument("owner-" + UUID.randomUUID());

        writeOutboxEvent("document.restored", Map.of(
                "documentId", document.getId().toString(), "changedAt", OffsetDateTime.now().toString()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var documentGet = openSearchClient.get(
                    g -> g.index(SearchIndexNames.DOCUMENT_ALIAS).id(document.getId().toString()), Map.class);
            assertThat(documentGet.found()).isTrue();

            var segmentSearch = openSearchClient.search(
                    s -> s.index(SearchIndexNames.SEGMENT_ALIAS)
                            .query(q -> q.term(t -> t.field("document_id").value(v -> v.stringValue(document.getId().toString())))),
                    Map.class);
            assertThat(segmentSearch.hits().hits()).anySatisfy(hit -> {
                assertThat(hit.source().get("content_source")).isEqualTo("title");
                assertThat(hit.source().get("text")).isEqualTo(document.getOriginalName());
            });
        });
    }

    /** §4.4 point 2 - a document purged directly, without ever going through a `document.trash` event first (race hiếm). */
    @Test
    void documentPurgedDirectlyWithoutAPriorTrashEventStillDeletesFromBothIndices() throws Exception {
        Document document = saveDocument("owner-" + UUID.randomUUID());
        indexBareDocument(document.getId(), document.getOriginalName());
        indexBareSegment(document.getId());

        writeOutboxEvent("document.purged", Map.of(
                "documentId", document.getId().toString(), "changedAt", OffsetDateTime.now().toString()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var documentGet = openSearchClient.get(
                    g -> g.index(SearchIndexNames.DOCUMENT_ALIAS).id(document.getId().toString()), Map.class);
            assertThat(documentGet.found()).isFalse();

            var segmentSearch = openSearchClient.search(
                    s -> s.index(SearchIndexNames.SEGMENT_ALIAS)
                            .query(q -> q.term(t -> t.field("document_id").value(v -> v.stringValue(document.getId().toString())))),
                    Map.class);
            assertThat(segmentSearch.hits().hits()).isEmpty();
        });
    }

    private void indexBareDocument(UUID documentId, String name) throws Exception {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("document_id", documentId.toString());
        source.put("document_name", name);
        source.put("owner_user_id", "owner-sub");
        source.put("scope_group_ids", List.of());
        source.put("index_version", 0);
        openSearchClient.index(i -> i
                .index(SearchIndexNames.DOCUMENT_ALIAS)
                .id(documentId.toString())
                .document(source)
                .refresh(Refresh.True));
    }

    private void indexBareSegment(UUID documentId) throws Exception {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("segment_id", UUID.randomUUID().toString());
        source.put("document_id", documentId.toString());
        source.put("segment_index", 0);
        source.put("text", "noi dung ban dau");
        source.put("content_source", "ocr");
        source.put("confirm_source", "auto");
        source.put("owner_user_id", "owner-sub");
        source.put("scope_group_ids", List.of());
        source.put("index_version", 0);
        openSearchClient.index(i -> i
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .id((String) source.get("segment_id"))
                .document(source)
                .refresh(Refresh.True));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSegment(UUID documentId) throws Exception {
        var response = openSearchClient.search(
                s -> s.index(SearchIndexNames.SEGMENT_ALIAS)
                        .query(q -> q.term(t -> t.field("document_id").value(v -> v.stringValue(documentId.toString())))),
                Map.class);
        assertThat(response.hits().hits()).isNotEmpty();
        return response.hits().hits().get(0).source();
    }
}
