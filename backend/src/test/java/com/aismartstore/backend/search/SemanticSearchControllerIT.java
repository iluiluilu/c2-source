package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.aismartstore.backend.groups.GroupMember;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.GroupRepository;
import com.aismartstore.backend.groups.GroupRole;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.aismartstore.backend.testsupport.StubEmbeddingServer;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SPEC-search-semantic T22 (query §5): {@code POST /search/semantic} end to end against real PostgreSQL,
 * Redis and OpenSearch 2.17.1 (k-NN plugin, the real {@code segment-v2} mapping created by {@code
 * SearchIndexBootstrap}); the embedding endpoint is {@link StubEmbeddingServer} with controlled geometry.
 *
 * <p>Includes the go-live blocking regression AC-4.1 ("A cannot pull B's content with any question"),
 * both as a real k-NN run (the filter is inside {@code knn}, proven with decoys that would fill a
 * post-filtered top-k) and as a Postgres double-check case (the index ACL drifted).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class SemanticSearchControllerIT {

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
    static GenericContainer<?> opensearch = new GenericContainer<>(new ImageFromDockerfile(
                    "aismartstore-opensearch-icu-test", false)
            .withFileFromPath(".", Paths.get(System.getProperty("user.dir"), "..", "opensearch").normalize()))
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
        registry.add("app.embedding.url", EMBEDDING::url);
        registry.add("app.embedding.timeout-ms", () -> "2000");
        registry.add("app.semantic.enabled", () -> "true");
        registry.add("app.semantic.reconciliation-interval-ms", () -> "3600000");
        registry.add("spring.rabbitmq.port", () -> "1"); // no broker in this IT; the listener just keeps retrying
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:1/unused-jwks");
    }

    @AfterAll
    static void stopStub() {
        EMBEDDING.close();
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @SpyBean
    private GroupMemberRepository groupMemberRepository;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OpenSearchClient openSearch;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DigitizationSourceRepository sourceRepository;
    @Autowired private DigitizationRunRepository runRepository;
    @Autowired private ContentSegmentRepository segmentRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private DocumentGroupShareRepository shareRepository;
    @Autowired private WarehouseFolderRepository folderRepository;

    private UUID folderId;
    private int nextIndex;

    @BeforeEach
    void reset() throws Exception {
        org.mockito.Mockito.reset(groupMemberRepository);
        EMBEDDING.reset();
        jdbc.update("DELETE FROM document_digitization_status");
        var cachedVectors = redisTemplate.keys(EmbeddingClient.QUERY_CACHE_PREFIX + "*");
        if (cachedVectors != null && !cachedVectors.isEmpty()) {
            redisTemplate.delete(cachedVectors); // a cached question vector from a previous test would hide the stub mode
        }
        shareRepository.deleteAll();
        groupMemberRepository.deleteAll();
        groupRepository.deleteAll();
        segmentRepository.deleteAll();
        runRepository.deleteAll();
        sourceRepository.deleteAll();
        documentRepository.deleteAll();

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        folderId = folderRepository.save(folder).getId();
        nextIndex = 0;

        openSearch.deleteByQuery(d -> d.index(SearchIndexNames.SEGMENT_ALIAS).query(q -> q.matchAll(m -> m)).refresh(true));
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private record Passage(Document document, ContentSegment segment) {
        String segmentId() {
            return segment.getId().toString();
        }
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("tai-lieu-" + UUID.randomUUID() + ".pdf");
        document.setMimeType("application/pdf");
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

    /** A Postgres passage (document + run + segment) plus its OpenSearch segment document with the given vector. */
    private Passage seed(String ownerSub, String contentSource, String text, float[] vector, List<String> scopeGroupIds)
            throws Exception {
        Document document = saveDocument(ownerSub);
        DigitizationRun run = confirmedRun(document, "sed".equals(contentSource) ? "sed" : "ocr");
        ContentSegment segment = new ContentSegment();
        segment.setId(UUID.randomUUID());
        segment.setRunId(run.getId());
        segment.setDocumentId(document.getId());
        segment.setSegmentIndex(nextIndex++);
        segment.setContentSource(contentSource);
        segment.setAiText(text);
        segment.setCurrentText(text);
        segment.setPageNo(2);
        segment.setLineFrom(1);
        segment.setLineTo(3);
        segment.setCreatedAt(OffsetDateTime.now());
        segment.setUpdatedAt(OffsetDateTime.now());
        segment = segmentRepository.save(segment);

        indexSegment(segment.getId().toString(), document.getId(), contentSource, text, ownerSub, scopeGroupIds, vector);
        return new Passage(document, segment);
    }

    private void indexSegment(
            String segmentId, UUID documentId, String contentSource, String text, String ownerSub,
            List<String> scopeGroupIds, float[] vector) throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("segment_id", segmentId);
        source.put("document_id", documentId.toString());
        source.put("segment_index", 0);
        source.put("text", text);
        source.put("content_source", contentSource);
        source.put("confirm_source", "manual");
        source.put("run_id", UUID.randomUUID().toString());
        source.put("position_type", "page");
        source.put("doc_type", "Quyết định");
        source.put("folder_id", folderId.toString());
        source.put("folder_path_ids", List.of(folderId.toString()));
        source.put("owner_user_id", ownerSub);
        source.put("scope_group_ids", scopeGroupIds);
        source.put("acl_version", 1);
        source.put("index_version", 1);
        if (vector != null) {
            source.put("embedding", vector);
            source.put("model_version", "qwen3-embedding-0.6b-1024");
        }
        openSearch.index(i -> i.index(SearchIndexNames.SEGMENT_ALIAS).id(segmentId).document(source).refresh(Refresh.True));
    }

    private static float[] axis(String keyword) {
        return StubEmbeddingServer.vectorFor(keyword, StubEmbeddingServer.DIM);
    }

    private static JwtRequestPostProcessor canBo(String sub) {
        return jwt().jwt(b -> b.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of("can-bo"))))
                .authorities(RolePermissionFixtures.authoritiesFor("CAN_BO"));
    }

    private static JwtRequestPostProcessor quanTri(String sub) {
        return jwt().jwt(b -> b.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of("quan-tri"))))
                .authorities(RolePermissionFixtures.authoritiesFor("QUAN_TRI"));
    }

    private JsonNode ask(String query, JwtRequestPostProcessor user) throws Exception {
        return ask(Map.of("query", query), user, 200);
    }

    private JsonNode ask(Map<String, Object> body, JwtRequestPostProcessor user, int expectedStatus) throws Exception {
        String json = mockMvc.perform(post("/search/semantic")
                        .with(user)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.isEmpty() ? null : objectMapper.readTree(json);
    }

    private static List<String> segmentIds(JsonNode response) {
        List<String> ids = new ArrayList<>();
        response.get("results").forEach(r -> ids.add(r.get("segmentId").asText()));
        return ids;
    }

    // ---- AC-1/AC-2: hybrid, citation from Postgres ---------------------------------------------------------

    @Test
    void returnsTheCallersOwnPassageWithCitationAndNoModeLabel() throws Exception {
        Passage mine = seed("user-b", "ocr", "Thời hạn nộp báo cáo là 30 ngày", axis("alpha"), List.of());

        JsonNode response = ask("alpha thời hạn nộp báo cáo", canBo("user-b"));

        assertThat(segmentIds(response)).containsExactly(mine.segmentId());
        JsonNode hit = response.get("results").get(0);
        assertThat(hit.get("excerpt").asText()).isEqualTo("Thời hạn nộp báo cáo là 30 ngày");
        assertThat(hit.get("documentName").asText()).isEqualTo(mine.document().getOriginalName());
        assertThat(hit.get("contentSource").asText()).isEqualTo("ocr");
        assertThat(hit.get("position").get("type").asText()).isEqualTo("page");
        assertThat(hit.get("position").get("pageNo").asInt()).isEqualTo(2);
        assertThat(hit.get("cosineScore").asDouble()).isBetween(0.98, 1.001);
        assertThat(response.get("degradedMode").asText()).isEqualTo("none");
        assertThat(response.get("insufficientEvidence").asBoolean()).isFalse();
        assertThat(response.get("maxPages").asInt()).isEqualTo(2);
        // the question reaches the embedding endpoint wrapped with the Qwen3 instruction
        assertThat(EMBEDDING.lastInputs().get(0)).startsWith("Instruct: ").contains("\nQuery: alpha thời hạn nộp báo cáo");
    }

    @Test
    void anIdenticalQuestionIsServedFromTheVectorCacheButResultsAreNeverCached() throws Exception {
        Passage mine = seed("user-b", "ocr", "noi dung ban dau", axis("alpha"), List.of());
        ask("alpha cau hoi", canBo("user-b"));
        int callsAfterFirst = EMBEDDING.requestCount();

        // a result change must be visible immediately: results are not cached, only the question vector
        mine.segment().setCurrentText("noi dung da sua");
        segmentRepository.save(mine.segment());
        JsonNode second = ask("alpha cau hoi", canBo("user-b"));

        assertThat(EMBEDDING.requestCount()).isEqualTo(callsAfterFirst);
        assertThat(second.get("results").get(0).get("excerpt").asText()).isEqualTo("noi dung da sua");
    }

    // ---- AC-4.1 (go-live blocker): scope leak regression --------------------------------------------------------

    @Test
    void userBCannotPullUserAsUnsharedPassageWithAQuestionThatMatchesItPerfectly() throws Exception {
        Passage secret = seed("user-a", "ocr", "Nội dung mật chỉ của người dùng A", axis("alpha"), List.of());

        JsonNode response = ask("alpha mô tả ý khác hẳn từ khoá", canBo("user-b"));

        assertThat(segmentIds(response)).doesNotContain(secret.segmentId());
        assertThat(response.get("results")).isEmpty();
        assertThat(response.get("insufficientEvidence").asBoolean()).isTrue();
        assertThat(response.toString()).doesNotContain("mật");
    }

    @Test
    void theScopeFilterIsInsideTheKnnClauseSoManyBetterMatchingForeignPassagesDoNotHideTheCallersOwn() throws Exception {
        // 80 foreign passages are PERFECT matches (cos 1.0); the caller's own is a worse one (cos 0.8). With a post-filter
        // (k=50 chosen first, filtered afterwards) the caller would get nothing; efficient filtering finds it.
        for (int i = 0; i < 80; i++) {
            indexSegment(UUID.randomUUID().toString(), UUID.randomUUID(), "ocr", "decoy " + i, "user-a", List.of(), axis("alpha"));
        }
        Passage mine = seed("user-b", "ocr", "Đoạn của người dùng B", StubEmbeddingServer.mix(0, 0.8, 1, 0.6), List.of());

        JsonNode response = ask("alpha câu hỏi", canBo("user-b"));

        assertThat(segmentIds(response)).containsExactly(mine.segmentId());
        assertThat(response.get("results").get(0).get("cosineScore").asDouble()).isBetween(0.78, 0.82);
    }

    @Test
    void whenTheIndexAclHasDriftedPostgresStillDropsTheForeignPassage() throws Exception {
        // the index (wrongly) says user-b owns it; Postgres says user-a does
        Document foreign = saveDocument("user-a");
        DigitizationRun run = confirmedRun(foreign, "ocr");
        ContentSegment segment = new ContentSegment();
        segment.setId(UUID.randomUUID());
        segment.setRunId(run.getId());
        segment.setDocumentId(foreign.getId());
        segment.setSegmentIndex(0);
        segment.setContentSource("ocr");
        segment.setAiText("bi mat cua A");
        segment.setCurrentText("bi mat cua A");
        segment.setCreatedAt(OffsetDateTime.now());
        segment.setUpdatedAt(OffsetDateTime.now());
        segmentRepository.save(segment);
        indexSegment(segment.getId().toString(), foreign.getId(), "ocr", "bi mat cua A", "user-b", List.of(), axis("alpha"));

        JsonNode response = ask("alpha", canBo("user-b"));

        assertThat(response.get("results")).isEmpty();
    }

    @Test
    void aGroupShareMakesAPassageVisibleAndRevokingItInPostgresHidesItEvenIfTheIndexIsStale() throws Exception {
        Group group = new Group();
        group.setName("Nhom " + UUID.randomUUID());
        group.setOwnerSub("user-a");
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        UUID groupId = groupRepository.save(group).getId();
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserSub("user-b");
        member.setRoleInGroup(GroupRole.MEMBER);
        member.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(member);

        Passage shared = seed("user-a", "ocr", "Tài liệu chia sẻ cho nhóm", axis("alpha"), List.of(groupId.toString()));
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(shared.document().getId());
        share.setGroupId(groupId);
        share.setSharedBySub("user-a");
        share.setSharedAt(OffsetDateTime.now());
        shareRepository.save(share);

        assertThat(segmentIds(ask("alpha", canBo("user-b")))).containsExactly(shared.segmentId());

        shareRepository.deleteAll(); // Postgres revoked; the index still lists the group (stale)
        assertThat(ask("alpha", canBo("user-b")).get("results")).isEmpty();
    }

    // ---- AC-4.2: fail-closed ----------------------------------------------------------------------------------------

    @Test
    void whenTheGroupLookupFailsTheRequestIs403AndNoEngineOrEmbeddingEndpointIsCalled() throws Exception {
        seed("user-b", "ocr", "noi dung", axis("alpha"), List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(groupMemberRepository).findGroupIdsByUserSub(eq("user-b"));

        JsonNode body = ask(Map.of("query", "alpha"), canBo("user-b"), 403);

        assertThat(body.get("code").asText()).isEqualTo("SCOPE_UNRESOLVED");
        assertThat(body.get("message").asText()).startsWith("Không xác định được phạm vi dữ liệu của bạn");
        assertThat(EMBEDDING.requestCount()).isZero();
    }

    @Test
    void roleWithoutDocumentReadCannotCallTheEndpoint() throws Exception {
        ask(Map.of("query", "alpha"), quanTri("admin-1"), 403);
        assertThat(EMBEDDING.requestCount()).isZero();
    }

    // ---- AC-2.4 / AC-2.5 ----------------------------------------------------------------------------------------------

    @Test
    void afterAnEditTheNextSearchShowsTheNewTextNotTheOldOne() throws Exception {
        Passage mine = seed("user-b", "ocr", "Nội dung cũ đã lỗi thời", axis("alpha"), List.of());
        assertThat(ask("alpha", canBo("user-b")).get("results").get(0).get("excerpt").asText()).isEqualTo("Nội dung cũ đã lỗi thời");

        mine.segment().setCurrentText("Nội dung mới nhất sau kiểm duyệt");
        segmentRepository.save(mine.segment());

        JsonNode response = ask("alpha", canBo("user-b"));
        assertThat(response.get("results").get(0).get("excerpt").asText()).isEqualTo("Nội dung mới nhất sau kiểm duyệt");
        assertThat(response.toString()).doesNotContain("lỗi thời");
    }

    @Test
    void summaryIsReturnedWithItsSourceAndSedNeverAppearsEvenWithAMatchingVector() throws Exception {
        Passage summary = seed("user-b", "summary", "Tóm tắt nội dung video họp giao ban", axis("alpha"), List.of());
        Passage sed = seed("user-b", "sed", "tiếng vỗ tay", axis("alpha"), List.of());

        JsonNode response = ask("alpha", canBo("user-b"));

        assertThat(segmentIds(response)).containsExactly(summary.segmentId()).doesNotContain(sed.segmentId());
        assertThat(response.get("results").get(0).get("contentSource").asText()).isEqualTo("summary");
        assertThat(response.get("results").get(0).get("position").get("type").asText()).isEqualTo("none");
    }

    // ---- AC-3 / AC-3.1 -----------------------------------------------------------------------------------------------------

    @Test
    void noEvidenceIsAnOrdinary200WithAnEmptyListAndInsufficientEvidence() throws Exception {
        seed("user-b", "ocr", "noi dung ve beta", axis("beta"), List.of());

        JsonNode response = ask("gamma câu hỏi không liên quan", canBo("user-b")); // orthogonal => cosine 0 < 0.50

        assertThat(response.get("results")).isEmpty();
        assertThat(response.get("insufficientEvidence").asBoolean()).isTrue();
        assertThat(response.get("degradedMode").asText()).isEqualTo("none");
    }

    @Test
    void aVectorBelowTheThresholdIsNotReturnedEvenWhenItIsTheNearestOne() throws Exception {
        seed("user-b", "ocr", "tai lieu gan nhat nhung chua du can cu", StubEmbeddingServer.mix(0, 0.4, 1, 0.9165), List.of()); // cos ~0.4

        assertThat(ask("alpha", canBo("user-b")).get("results")).isEmpty();
    }

    // ---- AC-5 ------------------------------------------------------------------------------------------------------------------

    @Test
    void whenTheEmbeddingEndpointFailsTheAnswerIsLexicalOnlyAndStillScoped() throws Exception {
        EMBEDDING.setMode(StubEmbeddingServer.Mode.FAIL);
        Passage mine = seed("user-b", "ocr", "báo cáo tài chính hằng năm", axis("alpha"), List.of());
        seed("user-a", "ocr", "báo cáo tài chính của người khác", axis("alpha"), List.of());

        JsonNode response = ask("báo cáo tài chính", canBo("user-b") /* a unique question => no cached vector */);

        assertThat(response.get("degradedMode").asText()).isEqualTo("lexical_only");
        assertThat(segmentIds(response)).containsExactly(mine.segmentId());
        assertThat(response.get("results").get(0).get("matchedLexical").asBoolean()).isTrue();
    }

    @Test
    void anEmbeddingVectorOfTheWrongDimensionDegradesInsteadOfBeingUsed() throws Exception {
        EMBEDDING.setMode(StubEmbeddingServer.Mode.WRONG_DIM);
        Passage mine = seed("user-b", "ocr", "quy định mới về lưu trữ hồ sơ", axis("alpha"), List.of());

        JsonNode response = ask("quy định mới về lưu trữ", canBo("user-b"));

        assertThat(response.get("degradedMode").asText()).isEqualTo("lexical_only");
        assertThat(segmentIds(response)).containsExactly(mine.segmentId());
    }

    @Test
    void documentsWithoutAVectorStillWorkAndAnIndexWithNoVectorAtAllNeverBreaksTheAnswer() throws Exception {
        // spike T4 (OpenSearch 2.17.1): a shard that mixes vector and vectorless documents must stay searchable.
        Passage vectorless = seed("user-b", "ocr", "hồ sơ chưa được nhúng vector", null, List.of());
        JsonNode onlyVectorless = ask("hồ sơ chưa được nhúng", canBo("user-b"));
        assertThat(segmentIds(onlyVectorless)).containsExactly(vectorless.segmentId()); // found lexically, 200 either way

        Passage withVector = seed("user-b", "ocr", "đoạn đã có vector", axis("alpha"), List.of());
        JsonNode mixed = ask("alpha", canBo("user-b"));
        assertThat(mixed.get("degradedMode").asText()).isEqualTo("none"); // the vector branch itself must not fail
        assertThat(segmentIds(mixed)).containsExactly(withVector.segmentId());
    }

    // ---- validation / AC-6 -----------------------------------------------------------------------------------------------------

    @Test
    void invalidRequestsAre400() throws Exception {
        ask(Map.of("query", "alpha", "page", 2), canBo("user-b"), 400);
        ask(Map.of("query", "alpha", "page", -1), canBo("user-b"), 400);
        ask(Map.of("query", "   "), canBo("user-b"), 400);
        ask(Map.of("query", "x".repeat(257)), canBo("user-b"), 400);
        ask(Map.of("query", "alpha", "size", 51), canBo("user-b"), 400);
        assertThat(EMBEDDING.requestCount()).isZero();
    }

    @Test
    void aLongNaturalLanguageQuestionOver32WordsIsAccepted() throws Exception {
        seed("user-b", "ocr", "noi dung", axis("alpha"), List.of());
        String longQuestion = "alpha " + "tu ".repeat(60).trim(); // > 32 words, < 256 chars
        ask(Map.of("query", longQuestion), canBo("user-b"), 200);
    }

    @Test
    void clientSuppliedModeOrMinScoreFieldsAreIgnored() throws Exception {
        seed("user-b", "ocr", "noi dung beta", axis("beta"), List.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", "gamma khong lien quan");
        body.put("mode", "lexical");
        body.put("minScore", 0.0);
        body.put("skipScopeFilter", true);

        JsonNode response = ask(body, canBo("user-b"), 200);

        assertThat(response.get("results")).isEmpty(); // threshold unchanged despite minScore=0
    }

    @Test
    void pagesAreSlicesOfTheSameRankingAndAtMostTwoExist() throws Exception {
        for (int i = 0; i < 7; i++) {
            seed("user-b", "ocr", "doan so " + i, StubEmbeddingServer.mix(0, 1.0, 1, 0.01 * (i + 1)), List.of());
        }
        JsonNode page0 = ask(Map.of("query", "alpha", "page", 0, "size", 3), canBo("user-b"), 200);
        JsonNode page1 = ask(Map.of("query", "alpha", "page", 1, "size", 3), canBo("user-b"), 200);

        assertThat(page0.get("results")).hasSize(3);
        assertThat(page1.get("results")).hasSize(3);
        assertThat(segmentIds(page0)).doesNotContainAnyElementsOf(segmentIds(page1));
        assertThat(page0.get("hasMore").asBoolean()).isTrue(); // 7 > 2 pages x 3
        assertThat(page0.get("totalApprox").asLong()).isEqualTo(7);
    }

    // ---- AC-7 ----------------------------------------------------------------------------------------------------------------------

    @Test
    void everyQueryIsAuditedWithoutExcerptsAndA403IsAuditedToo() throws Exception {
        seed("user-b", "ocr", "Nội dung tuyệt mật không được vào nhật ký", axis("alpha"), List.of());
        ask("alpha " + "x".repeat(250), canBo("user-b"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT outcome, status_code, metadata::text AS metadata FROM audit_log "
                            + "WHERE action = 'search.executed' AND actor_sub = 'user-b' ORDER BY id");
            assertThat(rows).isNotEmpty();
            String metadata = (String) rows.get(rows.size() - 1).get("metadata");
            JsonNode node = objectMapper.readTree(metadata);
            assertThat(node.get("mode").asText()).isEqualTo("hybrid");
            assertThat(node.get("degradedMode").asText()).isEqualTo("none");
            assertThat(node.get("minScore").asDouble()).isEqualTo(0.5);
            assertThat(node.get("resultCount").asInt()).isEqualTo(1);
            assertThat(node.get("query").asText()).hasSize(200);
            assertThat(metadata).doesNotContain("tuyệt mật");
        });

        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(groupMemberRepository).findGroupIdsByUserSub(eq("user-c"));
        ask(Map.of("query", "alpha"), canBo("user-c"), 403);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'search.executed' AND actor_sub = 'user-c' AND status_code = 403",
                        Integer.class))
                .isEqualTo(1));
    }

    // ---- AC-8 ---------------------------------------------------------------------------------------------------------------------------

    @Test
    void theDocumentsDigitizationStatusIsReturnedSoTheUiCanShowThePendingReviewNote() throws Exception {
        Passage mine = seed("user-b", "ocr", "noi dung", axis("alpha"), List.of());
        jdbc.update(
                "INSERT INTO document_digitization_status (document_id, ocr_status, overall_status, updated_at) "
                        + "VALUES (?, 'pending_review', 'pending_review', now())",
                mine.document().getId());

        JsonNode hit = ask("alpha", canBo("user-b")).get("results").get(0);

        assertThat(hit.get("documentDigitizationStatus").asText()).isEqualTo("pending_review");
    }
}
