package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
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
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §5 - the mandatory regression test
 * "A không thấy tài liệu của B" (rủi ro Cao SA), across every {@code
 * content_source} and both directions of an OpenSearch/Postgres ACL
 * mismatch: OpenSearch itself is only the <b>first</b> filter -
 * {@code SearchResultHydrator}'s Postgres double-check is the one that
 * actually decides.
 *
 * <p>Seeds directly into OpenSearch via the raw client rather than through an
 * indexing pipeline, since keeping the index in sync with Postgres writes is
 * spec {@code -04}'s job, not this one's - this file only exercises the read
 * path (Task 3's bootstrap creates the index/mapping; Task 5-7 do the rest).
 *
 * <p>NOTE: like every other Testcontainers-based test in this module, this
 * cannot currently be executed in this sandbox (V21's {@code setval} against
 * an empty {@code audit_log} - see that migration's comment / the task's
 * known-issue note). It is exercised by a normal CI/dev environment with a
 * populated or otherwise-compatible Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class SearchScopeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /**
     * Task 2's custom image (analysis-icu + Vietnamese stopwords) - built
     * from the same {@code main-workspace/opensearch/Dockerfile} docker-compose.yml
     * uses, not the bare {@code opensearchproject/opensearch} tag (which has
     * no analysis plugins at all, so the {@code segment}/{@code document}
     * mapping's analyzers would fail to create).
     */
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
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/unused-jwks");
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    private UUID rootFolderId;

    @BeforeEach
    void resetState() throws Exception {
        documentGroupShareRepository.deleteAll();
        groupMemberRepository.deleteAll();
        groupRepository.deleteAll();
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

        // The index survives across tests (created once by SearchIndexBootstrap at context
        // startup) - clear its documents so one test's fixtures never leak into another's.
        openSearchClient.deleteByQuery(d -> d
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .query(q -> q.matchAll(m -> m))
                .refresh(true));
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

    private UUID createGroupWithMember(String ownerSub, String memberSub) {
        Group group = new Group();
        group.setName("Nhom " + UUID.randomUUID());
        group.setOwnerSub(ownerSub);
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        UUID groupId = groupRepository.save(group).getId();

        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserSub(memberSub);
        member.setRoleInGroup(GroupRole.MEMBER);
        member.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(member);

        return groupId;
    }

    private void shareDocumentToGroup(UUID documentId, UUID groupId, String sharedBySub) {
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(documentId);
        share.setGroupId(groupId);
        share.setSharedBySub(sharedBySub);
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);
    }

    private void indexSegment(
            UUID documentId,
            String text,
            String contentSource,
            String ownerUserId,
            List<String> scopeGroupIds) throws Exception {
        indexSegment(documentId, text, contentSource, ownerUserId, scopeGroupIds, Map.of());
    }

    /**
     * search-fulltext-02-tim-kiem-nang-cao.md §5 - same seeding as the 5-arg
     * overload, plus whatever {@code extraFields} the -02 filter tests need
     * ({@code topic_tags}/{@code folder_id}/{@code folder_path_ids}/{@code
     * uploaded_at}/{@code media_type}/{@code confirm_source}) - {@code
     * extraFields} overrides the defaults below (e.g. {@code doc_type}) when
     * a key collides.
     */
    private void indexSegment(
            UUID documentId,
            String text,
            String contentSource,
            String ownerUserId,
            List<String> scopeGroupIds,
            Map<String, Object> extraFields)
            throws Exception {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("segment_id", UUID.randomUUID().toString());
        source.put("document_id", documentId.toString());
        source.put("segment_index", 0);
        source.put("text", text);
        source.put("content_source", contentSource);
        source.put("confirm_source", "auto");
        source.put("run_id", UUID.randomUUID().toString());
        source.put("position_type", "none");
        source.put("doc_type", "hop_dong");
        source.put("owner_user_id", ownerUserId);
        source.put("scope_group_ids", scopeGroupIds);
        source.put("acl_version", 1);
        source.put("index_version", 1);
        source.putAll(extraFields);

        openSearchClient.index(i -> i
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .id((String) source.get("segment_id"))
                .document(source)
                .refresh(Refresh.True));
    }

    /** search-fulltext-02-tim-kiem-nang-cao.md §5 - full response body (needed for {@code refineSuggestions}/{@code filters}). */
    private JsonNode searchWithBody(Map<String, Object> body, JwtRequestPostProcessor asUser) throws Exception {
        String responseBody = mockMvc.perform(post("/search")
                        .with(asUser)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(responseBody);
    }

    private static List<String> documentIdsOf(JsonNode response) {
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : response.get("results")) {
            ids.add(hit.get("documentId").asText());
        }
        return ids;
    }

    private static JwtRequestPostProcessor canBo(String sub) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of("can-bo"))))
                .authorities(RolePermissionFixtures.authoritiesFor("CAN_BO"));
    }

    /** rbac-group-scope.md's R3 (quan_tri) has no `PERM_document.read` - matches DocumentsController's own routes. */
    private static JwtRequestPostProcessor quanTri(String sub) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of("quan-tri"))))
                .authorities(RolePermissionFixtures.authoritiesFor("QUAN_TRI"));
    }

    private List<String> searchDocumentIds(String query, JwtRequestPostProcessor asUser) throws Exception {
        String body = mockMvc.perform(post("/search")
                        .with(asUser)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("query", query))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : root.get("results")) {
            ids.add(hit.get("documentId").asText());
        }
        return ids;
    }

    // ---- Rủi ro Cao SA: "A không thấy tài liệu của B", mọi content_source ----

    @ParameterizedTest
    @ValueSource(strings = {"ocr", "stt", "note", "title"})
    void userCannotSeeAnotherUsersUnsharedDocumentRegardlessOfContentSource(String contentSource) throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String userA = "stranger-" + UUID.randomUUID();
        String secretKeyword = "matkhaubimat" + UUID.randomUUID().toString().replace("-", "");

        Document documentD = saveDocument(userB);
        indexSegment(documentD.getId(), "noi dung " + secretKeyword + " rat quan trong", contentSource, userB, List.of());

        List<String> results = searchDocumentIds(secretKeyword, canBo(userA));

        assertThat(results).doesNotContain(documentD.getId().toString());
    }

    // ---- OpenSearch nói "được", Postgres nói "không" -> vẫn phải ẩn ----------

    @Test
    void deniesAccessWhenOpenSearchAclIsStaleEvenThoughItClaimsTheUserIsInScope() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String userA = "attacker-" + UUID.randomUUID();
        String secretKeyword = "lorongchiadu" + UUID.randomUUID().toString().replace("-", "");

        Document documentD = saveDocument(userB);
        // userA is a genuine member of this group (so ScopeContext.groupIds() legitimately
        // includes it, and the OpenSearch-side filter alone would let this segment through) -
        // but documentD was never actually shared to that group in Postgres
        // (document_group_share has no matching row). The index is seeded as if it had been
        // (an out-of-sync index), which the Postgres double-check must catch.
        UUID groupId = createGroupWithMember(userB, userA);

        indexSegment(
                documentD.getId(),
                "noi dung " + secretKeyword + " rat quan trong",
                "ocr",
                userB,
                List.of(groupId.toString()));

        List<String> results = searchDocumentIds(secretKeyword, canBo(userA));

        assertThat(results).doesNotContain(documentD.getId().toString());
    }

    // ---- Postgres nói "được", OpenSearch nói "không" -> mất tạm thời, KHÔNG phải rò rỉ ----

    @Test
    void hidesADocumentWhenOpenSearchAclFieldsAreMissingEvenThoughPostgresHasAValidShare() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String userA = "member-" + UUID.randomUUID();
        String keyword = "chiadu" + UUID.randomUUID().toString().replace("-", "");

        Document documentD = saveDocument(userB);
        UUID groupId = createGroupWithMember(userB, userA);
        shareDocumentToGroup(documentD.getId(), groupId, userB);

        // OpenSearch document has stale/missing ACL fields (owner=B, no groups) even though
        // Postgres now has a valid share to A's group.
        indexSegment(documentD.getId(), "noi dung " + keyword + " rat quan trong", "ocr", userB, List.of());

        List<String> results = searchDocumentIds(keyword, canBo(userA));

        // Accepted per QĐ 5 SA - OpenSearch is the first filter; missing there = temporarily
        // invisible, not a security leak in the other direction.
        assertThat(results).doesNotContain(documentD.getId().toString());
    }

    // ---- Sanity: a user can find their own document (the query pipeline actually works) ----

    @Test
    void userCanFindTheirOwnDocument() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String keyword = "tulieuquantrong" + UUID.randomUUID().toString().replace("-", "");

        Document documentD = saveDocument(userB);
        indexSegment(documentD.getId(), "noi dung " + keyword + " rat quan trong", "ocr", userB, List.of());

        List<String> results = searchDocumentIds(keyword, canBo(userB));

        assertThat(results).contains(documentD.getId().toString());
    }

    // ---- Sanity: a group member CAN see a document properly shared to their group ----

    @Test
    void userCanFindADocumentSharedToTheirGroup() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String userA = "member-" + UUID.randomUUID();
        String keyword = "hopdongchiadu" + UUID.randomUUID().toString().replace("-", "");

        Document documentD = saveDocument(userB);
        UUID groupId = createGroupWithMember(userB, userA);
        shareDocumentToGroup(documentD.getId(), groupId, userB);
        indexSegment(
                documentD.getId(), "noi dung " + keyword + " rat quan trong", "ocr", userB, List.of(groupId.toString()));

        List<String> results = searchDocumentIds(keyword, canBo(userA));

        assertThat(results).contains(documentD.getId().toString());
    }

    // ---- AC-7: empty query -> 400 (real HTTP round trip, not the unit-level SearchServiceTest) ----

    @Test
    void blankQueryReturns400() throws Exception {
        mockMvc.perform(post("/search")
                        .with(canBo("someone-" + UUID.randomUUID()))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("query", "   "))))
                .andExpect(status().isBadRequest());
    }

    // ---- Story 1 AC-2 (rbac-group-scope.md): R3 (quan_tri) has no PERM_document.read ----

    @Test
    void quanTriGetsForbiddenOnSearch() throws Exception {
        mockMvc.perform(post("/search")
                        .with(quanTri("admin-" + UUID.randomUUID()))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("query", "hop dong"))))
                .andExpect(status().isForbidden());
    }

    // ==== search-fulltext-02-tim-kiem-nang-cao.md §5 - advanced filters ==============================

    // ---- AC-1: doc_type - exactly the matching value, never the other one ----------------------------

    @Test
    void filtersByDocTypeAC1() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String keyword = "loaitailieu" + UUID.randomUUID().toString().replace("-", "");

        Document matching = saveDocument(userB);
        indexSegment(
                matching.getId(), "noi dung " + keyword, "ocr", userB, List.of(), Map.of("doc_type", "văn bản"));
        Document other = saveDocument(userB);
        indexSegment(other.getId(), "noi dung " + keyword, "ocr", userB, List.of(), Map.of("doc_type", "hình ảnh"));

        JsonNode response = searchWithBody(
                Map.of("query", keyword, "filters", Map.of("docType", "văn bản")), canBo(userB));

        assertThat(documentIdsOf(response)).containsExactly(matching.getId().toString());
    }

    // ---- AC-5: folderId filters the whole subtree, not just documents directly in it -----------------

    @Test
    void filtersByFolderSubtreeAC5() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String keyword = "thuocthumuc" + UUID.randomUUID().toString().replace("-", "");

        WarehouseFolder parent = new WarehouseFolder();
        parent.setName("Cha " + UUID.randomUUID());
        parent.setPathIds(new UUID[0]);
        parent.setDepth((short) 1);
        parent.setCreatedAt(OffsetDateTime.now());
        parent.setUpdatedAt(OffsetDateTime.now());
        UUID parentId = warehouseFolderRepository.save(parent).getId();

        WarehouseFolder child = new WarehouseFolder();
        child.setName("Con " + UUID.randomUUID());
        child.setParentId(parentId);
        child.setPathIds(new UUID[] {parentId});
        child.setDepth((short) 2);
        child.setCreatedAt(OffsetDateTime.now());
        child.setUpdatedAt(OffsetDateTime.now());
        UUID childId = warehouseFolderRepository.save(child).getId();

        Document documentInChild = saveDocument(userB);
        indexSegment(
                documentInChild.getId(),
                "noi dung " + keyword,
                "ocr",
                userB,
                List.of(),
                Map.of("folder_id", childId.toString(), "folder_path_ids", List.of(parentId.toString(), childId.toString())));

        JsonNode response =
                searchWithBody(Map.of("query", keyword, "filters", Map.of("folderId", parentId.toString())), canBo(userB));

        assertThat(documentIdsOf(response)).containsExactly(documentInChild.getId().toString());
    }

    // ---- AC-6: media_type + confirm_source combine with AND, independently of each other -------------

    @Test
    void combinesMediaTypeAndConfirmSourceWithAndAC6() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String keyword = "loaimedia" + UUID.randomUUID().toString().replace("-", "");

        Document matching = saveDocument(userB);
        indexSegment(
                matching.getId(),
                "noi dung " + keyword,
                "ocr",
                userB,
                List.of(),
                Map.of("media_type", "audio", "confirm_source", "manual"));
        Document wrongConfirmSource = saveDocument(userB);
        indexSegment(
                wrongConfirmSource.getId(),
                "noi dung " + keyword,
                "ocr",
                userB,
                List.of(),
                Map.of("media_type", "audio", "confirm_source", "auto"));

        JsonNode response = searchWithBody(
                Map.of(
                        "query",
                        keyword,
                        "filters",
                        Map.of("mediaType", List.of("audio"), "confirmSource", "manual")),
                canBo(userB));

        assertThat(documentIdsOf(response)).containsExactly(matching.getId().toString());
    }

    // ---- AC-12: query + filter -> 0 results, but dropping the filter alone has matches ---------------

    @Test
    void suggestsDroppingAFilterWhenTheCombinationReturnsZeroResultsAC12() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String keyword = "goikhong" + UUID.randomUUID().toString().replace("-", "");

        // Matches the keyword, but NOT `docType=video` - so query+filter together = 0, query alone > 0.
        Document document = saveDocument(userB);
        indexSegment(document.getId(), "noi dung " + keyword, "ocr", userB, List.of(), Map.of("doc_type", "văn bản"));

        JsonNode response =
                searchWithBody(Map.of("query", keyword, "filters", Map.of("docType", "video")), canBo(userB));

        assertThat(documentIdsOf(response)).isEmpty();
        assertThat(response.get("totalApprox").asLong()).isZero();
        JsonNode suggestions = response.get("refineSuggestions");
        assertThat(suggestions).isNotNull();
        boolean hasDocTypeSuggestion = false;
        for (JsonNode suggestion : suggestions) {
            if ("docType".equals(suggestion.get("removedFilter").asText())) {
                hasDocTypeSuggestion = true;
                assertThat(suggestion.get("estimatedCount").asLong()).isGreaterThan(0);
            }
        }
        assertThat(hasDocTypeSuggestion).isTrue();
    }

    // ==== search-fulltext-03-dinh-vi-ket-qua.md §5 - AC-5 excerpt ordering ============================

    // ---- AC-5: same score -> tie-broken by page_no ascending, not insertion order ----

    @Test
    void sortsMultipleExcerptsInTheSameDocumentByPageNoWhenScoresAreTiedAC5() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String keyword = "dinhvidoan" + UUID.randomUUID().toString().replace("-", "");
        Document document = saveDocument(userB);

        // Identical short text (so BM25 scores are effectively tied) - only `page_no` differs.
        // Indexed out of page order on purpose so a passing test can't be explained by
        // insertion/collapse order alone.
        Map<String, Integer> pageNoBySegmentId = new java.util.LinkedHashMap<>();
        for (int pageNo : List.of(5, 1, 3, 2, 4)) {
            String segmentId = UUID.randomUUID().toString();
            pageNoBySegmentId.put(segmentId, pageNo);
            indexSegment(
                    document.getId(),
                    "noi dung " + keyword,
                    "ocr",
                    userB,
                    List.of(),
                    Map.of("segment_id", segmentId, "page_no", pageNo));
        }

        JsonNode response = searchWithBody(Map.of("query", keyword), canBo(userB));
        JsonNode segments = response.get("results").get(0).get("segments");

        List<Integer> pagesInResponseOrder = new ArrayList<>();
        for (JsonNode segment : segments) {
            pagesInResponseOrder.add(pageNoBySegmentId.get(segment.get("segmentId").asText()));
        }
        // Only the top INNER_HITS_SIZE (3) come back - still must be the 3 lowest page numbers, ascending.
        assertThat(pagesInResponseOrder).containsExactly(1, 2, 3);
    }

    // ---- AC-5: a lower-scoring excerpt (title, no page_no at all) always sorts after a higher-scoring one ----

    @Test
    void higherScoringExcerptSortsBeforeALowerScoringOneRegardlessOfPageNoAC5() throws Exception {
        String userB = "owner-" + UUID.randomUUID();
        String keyword = "xephangdoan" + UUID.randomUUID().toString().replace("-", "");
        Document document = saveDocument(userB);

        String ocrSegmentId = UUID.randomUUID().toString();
        String titleSegmentId = UUID.randomUUID().toString();
        // Higher term frequency, short field -> higher BM25 score.
        indexSegment(
                document.getId(),
                keyword + " " + keyword + " " + keyword,
                "ocr",
                userB,
                List.of(),
                Map.of("segment_id", ocrSegmentId, "page_no", 9));
        // Single occurrence buried in a long field -> lower score from length normalization;
        // no `page_no` at all (a `title` pseudo-segment never has one).
        String padding = String.join(" ", java.util.Collections.nCopies(60, "khac"));
        indexSegment(
                document.getId(), keyword + " " + padding, "title", userB, List.of(), Map.of("segment_id", titleSegmentId));

        JsonNode response = searchWithBody(Map.of("query", keyword), canBo(userB));
        JsonNode segments = response.get("results").get(0).get("segments");

        List<String> segmentIdsInResponseOrder = new ArrayList<>();
        for (JsonNode segment : segments) {
            segmentIdsInResponseOrder.add(segment.get("segmentId").asText());
        }
        assertThat(segmentIdsInResponseOrder).containsExactly(ocrSegmentId, titleSegmentId);
    }
}
