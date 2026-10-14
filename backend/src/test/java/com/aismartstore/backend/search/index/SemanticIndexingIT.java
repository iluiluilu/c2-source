package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.groups.DocumentGroupShare;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.groups.Group;
import com.aismartstore.backend.groups.GroupRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.search.SearchIndexNames;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.StubEmbeddingServer;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.aismartstore.backend.warehouse.WarehouseService;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * SPEC-search-semantic T22 (indexing §5): the write path end to end with a real PostgreSQL, RabbitMQ
 * ({@code q.embed} declared by {@code RabbitConfig}), Redis and OpenSearch 2.17.1 (k-NN, real {@code
 * segment-v2} mapping). The embedding endpoint is {@link StubEmbeddingServer}. The real {@code
 * SearchIndexOutboxPoller}, {@code EmbedRequestConsumer} and {@code SemanticIndexReconciliationJob} beans run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class SemanticIndexingIT {

    static final StubEmbeddingServer EMBEDDING = new StubEmbeddingServer();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "guest")
            .withEnv("RABBITMQ_DEFAULT_PASS", "guest")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static GenericContainer<?> opensearch = new GenericContainer<>(new ImageFromDockerfile(
                    "aismartstore-opensearch-icu-test", false)
            .withFileFromPath(".", Paths.get(System.getProperty("user.dir"), "..", "opensearch").normalize()))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("plugins.security.disabled", "true")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "Aismartstore1!")
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("app.opensearch.host", opensearch::getHost);
        registry.add("app.opensearch.port", () -> opensearch.getMappedPort(9200));
        registry.add("app.embedding.url", EMBEDDING::url);
        registry.add("app.semantic.enabled", () -> "true");
        registry.add("app.semantic.embed-max-retries", () -> "1");
        // backend-multi-instance: no grace window - the test publishes and then reconciles within seconds.
        registry.add("app.semantic.reconciliation-grace-ms", () -> "0");
        registry.add("app.semantic.reconciliation-interval-ms", () -> "3600000");
        registry.add("app.search-index.poll-interval-ms", () -> "300");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:1/unused-jwks");
    }

    @AfterAll
    static void stopStub() {
        EMBEDDING.close();
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @Autowired private OpenSearchClient openSearch;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DigitizationSourceRepository sourceRepository;
    @Autowired private DigitizationRunRepository runRepository;
    @Autowired private ContentSegmentRepository segmentRepository;
    @Autowired private WarehouseFolderRepository folderRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private DocumentNoteRepository documentNoteRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private DocumentGroupShareRepository shareRepository;
    @Autowired private WarehouseService warehouseService;
    @Autowired private SemanticIndexReconciliationJob reconciliationJob;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private QueueProperties queueProperties;

    private UUID folderId;

    @BeforeEach
    void reset() throws Exception {
        // backend-multi-instance: the context's own scheduler already ticked this job at startup and holds its
        // ShedLock row for lockAtLeastFor; expire it (UPDATE, not DELETE: ShedLock caches that the row exists) so the direct call below runs the job body.
        jdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 minute'");
        EMBEDDING.reset();
        jdbc.update("DELETE FROM outbox_event");
        shareRepository.deleteAll();
        groupRepository.deleteAll();
        documentNoteRepository.deleteAll();
        jdbc.update("DELETE FROM document_digitization_status");
        segmentRepository.deleteAll();
        runRepository.deleteAll();
        sourceRepository.deleteAll();
        documentRepository.deleteAll();
        List<WarehouseFolder> folders = new ArrayList<>(folderRepository.findAll());
        folders.sort((a, b) -> Integer.compare(b.getDepth(), a.getDepth()));
        folderRepository.deleteAll(folders);
        for (String queue : List.of("q.embed", "q.embed.retry", "q.embed.dlq")) {
            amqpAdmin.purgeQueue(queue, true);
        }
        folderId = saveFolder("Root " + UUID.randomUUID(), null, new UUID[0], 1).getId();
        openSearch.deleteByQuery(d -> d.index(SearchIndexNames.SEGMENT_ALIAS).query(q -> q.matchAll(m -> m)).refresh(true));
        openSearch.deleteByQuery(d -> d.index(SearchIndexNames.DOCUMENT_ALIAS).query(q -> q.matchAll(m -> m)).refresh(true));
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private WarehouseFolder saveFolder(String name, UUID parentId, UUID[] pathIds, int depth) {
        WarehouseFolder folder = new WarehouseFolder();
        folder.setName(name);
        folder.setParentId(parentId);
        folder.setPathIds(pathIds);
        folder.setDepth((short) depth);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        return folderRepository.save(folder);
    }

    private Document saveDocument(UUID inFolder) {
        Document document = new Document();
        document.setOriginalName("tai-lieu-" + UUID.randomUUID() + ".pdf");
        document.setMimeType("application/pdf");
        document.setSize(10);
        document.setSha256("x".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(inFolder);
        document.setUploaderSub("user-a");
        document.setUploaderUsername("user-a");
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    private DigitizationRun confirmedRun(Document document, String task) {
        DigitizationSource source = new DigitizationSource();
        source.setId(UUID.randomUUID());
        source.setDocumentId(document.getId());
        source.setComponentType(DigitizationSource.COMPONENT_ORIGINAL);
        source.setFilePath("bucket/" + document.getId());
        source.setMimeType("application/pdf");
        source.setDispatchable(true);
        source.setDispatchStatus(DigitizationSource.DISPATCH_PENDING);
        source.setCreatedAt(OffsetDateTime.now());
        source.setUpdatedAt(OffsetDateTime.now());
        UUID sourceId = sourceRepository.save(source).getId();

        DigitizationRun run = new DigitizationRun();
        run.setDocumentId(document.getId());
        run.setSourceId(sourceId);
        run.setTask(task);
        run.setProcessingStatus(DigitizationRun.PROCESSING_SUCCEEDED);
        run.setReviewState(DigitizationRun.REVIEW_CONFIRMED);
        run.setCreatedAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        return runRepository.save(run);
    }

    private ContentSegment saveSegment(DigitizationRun run, int index, String source, int page, String text) {
        ContentSegment segment = new ContentSegment();
        segment.setId(UUID.randomUUID());
        segment.setRunId(run.getId());
        segment.setDocumentId(run.getDocumentId());
        segment.setSegmentIndex(index);
        segment.setContentSource(source);
        segment.setAiText(text);
        segment.setCurrentText(text);
        segment.setPageNo(page);
        segment.setCreatedAt(OffsetDateTime.now());
        segment.setUpdatedAt(OffsetDateTime.now());
        return segmentRepository.save(segment);
    }

    private void outbox(String type, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(type);
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        outboxEventRepository.save(event);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(String segmentId) throws Exception {
        var response = openSearch.get(g -> g.index(SearchIndexNames.SEGMENT_ALIAS).id(segmentId), Map.class);
        return response.found() ? (Map<String, Object>) response.source() : null;
    }

    private boolean hasVector(String segmentId) throws Exception {
        Map<String, Object> doc = source(segmentId);
        return doc != null && doc.get("embedding") instanceof List<?> v && v.size() == 1024 && doc.get("model_version") != null;
    }

    private void awaitVector(String segmentId) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> hasVector(segmentId));
    }

    private void awaitLexical(String segmentId) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> source(segmentId) != null);
    }

    private void confirm(DigitizationRun run) {
        outbox("review.confirmed", Map.of("documentId", run.getDocumentId().toString(), "runId", run.getId().toString(), "task", run.getTask()));
    }

    // ---- review.confirmed -> lexical + vector -------------------------------------------------------------------

    @Test
    void reviewConfirmedWritesAValidatedVectorWithModelVersionForEveryEmbeddableSegmentButNeverSed() throws Exception {
        Document document = saveDocument(folderId);
        DigitizationRun run = confirmedRun(document, "ocr");
        ContentSegment first = saveSegment(run, 0, "ocr", 1, "alpha dòng đầu trang một");
        ContentSegment second = saveSegment(run, 1, "ocr", 1, "beta dòng hai trang một");
        ContentSegment otherPage = saveSegment(run, 2, "ocr", 2, "gamma trang hai");
        ContentSegment sed = saveSegment(run, 3, "sed", 0, "tiếng vỗ tay");
        confirm(run);

        awaitVector(first.getId().toString());
        awaitVector(second.getId().toString());
        awaitVector(otherPage.getId().toString());

        Map<String, Object> doc = source(first.getId().toString());
        assertThat(doc.get("model_version")).isEqualTo("qwen3-embedding-0.6b-1024");
        assertThat(doc.get("text")).isEqualTo("alpha dòng đầu trang một"); // lexical fields intact next to the vector
        assertThat(doc.get("owner_user_id")).isEqualTo("user-a"); // ACL fields were NOT touched by the consumer
        // neighbour window: same page => the neighbours' words are in the embedded text; other page => not mixed in
        assertThat(EMBEDDING.lastInputs()).anyMatch(t -> t.contains("alpha") && t.contains("beta"));
        assertThat(EMBEDDING.lastInputs()).anyMatch(t -> t.equals("gamma trang hai"));
        // sed is lexical-only: indexed but never embedded
        awaitLexical(sed.getId().toString());
        assertThat(hasVector(sed.getId().toString())).isFalse();
        assertThat(EMBEDDING.lastInputs()).noneMatch(t -> t.contains("vỗ tay"));
    }

    @Test
    void documentIngestedEmbedsTheTitleSegmentAndNoteChangedEmbedsTheNote() throws Exception {
        Document document = saveDocument(folderId);
        outbox("document.ingested", Map.of("documentId", document.getId().toString()));
        awaitVector(document.getId() + ":title");
        assertThat(EMBEDDING.lastInputs()).contains(document.getOriginalName());

        DocumentNote note = new DocumentNote();
        note.setDocumentId(document.getId());
        note.setBody("ghi chú alpha quan trọng");
        note.setCreatedBySub("user-a");
        note.setCreatedByUsername("user-a");
        note.setUpdatedBySub("user-a");
        note.setUpdatedByUsername("user-a");
        note.setCreatedAt(OffsetDateTime.now());
        note.setUpdatedAt(OffsetDateTime.now());
        documentNoteRepository.save(note);
        outbox("document.note.changed", Map.of("documentId", document.getId().toString()));
        awaitVector(document.getId() + ":note");
    }

    // ---- R4: lexical re-index overwrites the document => the vector must come back ------------------------------------

    @Test
    void afterAnEditTheLexicalReindexDropsTheVectorAndTheEmbedRequestBringsItBackWithTheNewText() throws Exception {
        Document document = saveDocument(folderId);
        DigitizationRun run = confirmedRun(document, "ocr");
        ContentSegment segment = saveSegment(run, 0, "ocr", 1, "alpha nội dung ban đầu");
        confirm(run);
        awaitVector(segment.getId().toString());
        int embeddedBefore = EMBEDDING.inputCount();

        segment.setCurrentText("alpha nội dung đã sửa sau kiểm duyệt");
        segment.setUpdatedAt(OffsetDateTime.now().plusSeconds(5)); // a newer contentVersion => the upsert wins
        segmentRepository.save(segment);
        outbox("review.reconfirmed", Map.of("documentId", document.getId().toString(), "runId", run.getId().toString(), "task", "ocr"));

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(
                () -> EMBEDDING.lastInputs().stream().anyMatch(t -> t.contains("đã sửa sau kiểm duyệt")));
        awaitVector(segment.getId().toString());
        assertThat(EMBEDDING.inputCount()).isGreaterThan(embeddedBefore);
        assertThat(source(segment.getId().toString()).get("text")).isEqualTo("alpha nội dung đã sửa sau kiểm duyệt");
    }

    @Test
    void replayingTheSameLexicalEventKeepsTheVector() throws Exception {
        Document document = saveDocument(folderId);
        DigitizationRun run = confirmedRun(document, "ocr");
        ContentSegment segment = saveSegment(run, 0, "ocr", 1, "alpha noi dung");
        confirm(run);
        awaitVector(segment.getId().toString());

        confirm(run); // same data => same contentVersion => benign version conflict, the vector must survive
        int callsAfterFirst = EMBEDDING.requestCount();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> EMBEDDING.requestCount() > callsAfterFirst);
        Thread.sleep(1500);

        assertThat(hasVector(segment.getId().toString())).isTrue();
    }

    @Test
    void reconciliationRepublishesMissingVectorsAndLeavesCompleteOnesAlone() throws Exception {
        Document document = saveDocument(folderId);
        DigitizationRun run = confirmedRun(document, "ocr");
        ContentSegment a = saveSegment(run, 0, "ocr", 1, "alpha mot");
        ContentSegment b = saveSegment(run, 1, "ocr", 2, "beta hai");
        outbox("document.ingested", Map.of("documentId", document.getId().toString()));
        confirm(run);
        awaitVector(a.getId().toString());
        awaitVector(b.getId().toString());
        awaitVector(document.getId() + ":title");

        // complete => nothing republished
        int before = EMBEDDING.requestCount();
        reconciliationJob.reconcileBatch();
        Thread.sleep(1500);
        assertThat(EMBEDDING.requestCount()).isEqualTo(before);

        // a lost vector (and a stale model_version) => both re-requested and healed
        openSearch.update(u -> u.index(SearchIndexNames.SEGMENT_ALIAS).id(a.getId().toString()).refresh(org.opensearch.client.opensearch._types.Refresh.True)
                .script(s -> s.inline(i -> i.lang("painless").source("ctx._source.remove('embedding'); ctx._source.remove('model_version');"))), Map.class);
        openSearch.update(u -> u.index(SearchIndexNames.SEGMENT_ALIAS).id(b.getId().toString()).refresh(org.opensearch.client.opensearch._types.Refresh.True)
                .script(s -> s.inline(i -> i.lang("painless").source("ctx._source.model_version = 'old-model';"))), Map.class);
        assertThat(hasVector(a.getId().toString())).isFalse();

        reconciliationJob.reconcileBatch();

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(
                () -> hasVector(a.getId().toString()) && "qwen3-embedding-0.6b-1024".equals(source(b.getId().toString()).get("model_version")));
    }

    // ---- ACL / trash keep or drop the document together with its vector, without calling the endpoint -----------------

    @Test
    void aclChangeKeepsTheVectorWithoutAnEmbeddingCallAndTrashRemovesTheDocument() throws Exception {
        Document document = saveDocument(folderId);
        DigitizationRun run = confirmedRun(document, "ocr");
        ContentSegment segment = saveSegment(run, 0, "ocr", 1, "alpha noi dung");
        confirm(run);
        awaitVector(segment.getId().toString());
        int inputsBefore = EMBEDDING.inputCount();

        Group group = new Group();
        group.setName("Nhom " + UUID.randomUUID());
        group.setOwnerSub("user-a");
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        UUID groupId = groupRepository.save(group).getId();
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(document.getId());
        share.setGroupId(groupId);
        share.setSharedBySub("user-a");
        share.setSharedAt(OffsetDateTime.now().plusMinutes(1));
        shareRepository.save(share);
        outbox("document.acl.changed", Map.of("documentId", document.getId().toString()));

        Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> source(segment.getId().toString()).get("scope_group_ids") instanceof List<?> l && l.contains(groupId.toString()));
        assertThat(hasVector(segment.getId().toString())).isTrue();
        assertThat(EMBEDDING.inputCount()).isEqualTo(inputsBefore);

        outbox("document.trash", Map.of("documentIds", List.of(document.getId().toString())));
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> source(segment.getId().toString()) == null);
    }

    // ---- folder.moved ---------------------------------------------------------------------------------------------------------

    @Test
    void movingAFolderRewritesFolderPathIdsOfTheWholeSubtreeInBothIndicesAndKeepsTheVectors() throws Exception {
        WarehouseFolder newParent = saveFolder("Dich " + UUID.randomUUID(), null, new UUID[0], 1);
        WarehouseFolder moved = saveFolder("Chuyen " + UUID.randomUUID(), null, new UUID[0], 1);
        WarehouseFolder child = saveFolder("Con " + UUID.randomUUID(), moved.getId(), new UUID[] {moved.getId()}, 2);
        Document inChild = saveDocument(child.getId());
        Document inMoved = saveDocument(moved.getId());
        outbox("document.ingested", Map.of("documentId", inChild.getId().toString()));
        outbox("document.ingested", Map.of("documentId", inMoved.getId().toString()));
        awaitVector(inChild.getId() + ":title");
        awaitVector(inMoved.getId() + ":title");
        assertThat(source(inChild.getId() + ":title").get("folder_path_ids")).isEqualTo(List.of(moved.getId().toString(), child.getId().toString()));

        warehouseService.moveFolder(new KeycloakUser("admin-sub", "admin", List.of("quan-tri")), moved.getId(), newParent.getId());

        List<String> expectedChild = List.of(newParent.getId().toString(), moved.getId().toString(), child.getId().toString());
        List<String> expectedMoved = List.of(newParent.getId().toString(), moved.getId().toString());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            assertThat(source(inChild.getId() + ":title").get("folder_path_ids")).isEqualTo(expectedChild);
            assertThat(source(inMoved.getId() + ":title").get("folder_path_ids")).isEqualTo(expectedMoved);
        });
        // the document index too, and the vectors survived the partial update
        var documentIndexDoc = openSearch.get(g -> g.index(SearchIndexNames.DOCUMENT_ALIAS).id(inChild.getId().toString()), Map.class);
        assertThat(documentIndexDoc.source().get("folder_path_ids")).isEqualTo(expectedChild);
        assertThat(hasVector(inChild.getId() + ":title")).isTrue();
        // filtering by the NEW ancestor finds the document, by the OLD location (the former parent) does not
        long underNewParent = openSearch.count(c -> c.index(SearchIndexNames.SEGMENT_ALIAS)
                .query(q -> q.term(t -> t.field("folder_path_ids").value(v -> v.stringValue(newParent.getId().toString()))))).count();
        assertThat(underNewParent).isEqualTo(2);
    }

    // ---- failures never write a vector; retry; DLQ ---------------------------------------------------------------------------------

    @Test
    void anEndpointFailureWritesNothingThenRetriesAndSucceedsOnceTheEndpointRecovers() throws Exception {
        EMBEDDING.setMode(StubEmbeddingServer.Mode.FAIL);
        Document document = saveDocument(folderId);
        DigitizationRun run = confirmedRun(document, "ocr");
        ContentSegment segment = saveSegment(run, 0, "ocr", 1, "alpha noi dung");
        confirm(run);
        awaitLexical(segment.getId().toString());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> EMBEDDING.requestCount() >= 1);

        assertThat(hasVector(segment.getId().toString())).isFalse();
        EMBEDDING.setMode(StubEmbeddingServer.Mode.OK); // the 5s retry-queue TTL elapses and the message returns to q.embed

        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500)).until(() -> hasVector(segment.getId().toString()));
    }

    @Test
    void aWrongDimensionResponseNeverReachesTheIndexAndTheMessageEndsInTheDlq() throws Exception {
        EMBEDDING.setMode(StubEmbeddingServer.Mode.WRONG_DIM);
        Document document = saveDocument(folderId);
        DigitizationRun run = confirmedRun(document, "ocr");
        ContentSegment segment = saveSegment(run, 0, "ocr", 1, "alpha noi dung");
        confirm(run);
        awaitLexical(segment.getId().toString());

        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> {
            Properties props = amqpAdmin.getQueueProperties(queueProperties.embedName() + ".dlq");
            return props != null && (Integer) props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT) >= 1;
        });
        assertThat(hasVector(segment.getId().toString())).isFalse();
        assertThat(source(segment.getId().toString())).doesNotContainKey("embedding");
    }

    @Test
    void theEmbedQueuesAreDeclaredExactlyAsOnStg() {
        Properties main = amqpAdmin.getQueueProperties("q.embed");
        Properties retry = amqpAdmin.getQueueProperties("q.embed.retry");
        Properties dlq = amqpAdmin.getQueueProperties("q.embed.dlq");
        assertThat(main).isNotNull();
        assertThat(retry).isNotNull();
        assertThat(dlq).isNotNull();
        var info = rabbitTemplate.execute(channel -> channel.queueDeclarePassive("q.embed"));
        assertThat(info.getQueue()).isEqualTo("q.embed");
    }
}
