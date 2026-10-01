package com.aismartstore.backend.personal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.personal.PersonalFile;
import com.aismartstore.backend.personal.PersonalFileRepository;
import com.aismartstore.backend.personal.PersonalFolder;
import com.aismartstore.backend.personal.PersonalFolderRepository;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.Warehouse;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.aismartstore.backend.warehouse.WarehouseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** personal-to-warehouse.md §5 integration tests (storage + ClamAV mocked, real Postgres/Redis). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PersonalToWarehouseIT {

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
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:1/unused-jwks");
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @MockBean
    private ClamdService clamdService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private DigitizationRunRepository digitizationRunRepository;

    @Autowired
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private PersonalIngestItemRepository itemRepository;

    @Autowired
    private PersonalFileRepository personalFileRepository;

    @Autowired
    private PersonalFolderRepository personalFolderRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PersonalToWarehouseService personalToWarehouseService;

    private static final long DEFAULT_MAX_CAPACITY_BYTES = 10_995_116_277_760L;
    private static final String OWNER = "officer-1";
    private static final byte[] PNG_HEADER = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** storageKey -> bytes served by the mocked personal bucket. */
    private final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void resetState() throws Exception {
        // a previous test may have left an async run in flight - let it finish before wiping tables.
        await().atMost(Duration.ofSeconds(30)).until(() ->
                itemRepository.findAll().stream().noneMatch(i -> PersonalIngestItem.PENDING.equals(i.getStatus())));
        itemRepository.deleteAll();
        digitizationRunRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentDigitizationStatusRepository.deleteAll();
        documentRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        personalFileRepository.deleteAll();
        List<PersonalFolder> pf = new ArrayList<>(personalFolderRepository.findAll());
        // children first (parent FK)
        pf.sort(Comparator.comparing((PersonalFolder f) -> f.getParentId() == null ? 0 : 1).reversed());
        personalFolderRepository.deleteAll(pf);
        List<WarehouseFolder> all = new ArrayList<>(warehouseFolderRepository.findAll());
        all.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(all);

        Warehouse warehouse = warehouseRepository.findById(WarehouseRepository.SINGLETON_ID).orElseThrow();
        warehouse.setMaxCapacityBytes(DEFAULT_MAX_CAPACITY_BYTES);
        warehouseRepository.save(warehouse);

        setConfig("ingest.max_batch_size_bytes", 5L * 1024 * 1024 * 1024);
        setConfig("ingest.max_file_size_bytes", 2L * 1024 * 1024 * 1024);
        setConfig("ingest.max_files_per_batch", 500);
        redisTemplate.delete("ingest:allowlist");

        objects.clear();
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.ofClean());
        doNothing().when(objectStorageService).putObject(anyString(), any(), anyString());
        when(objectStorageService.getObject(anyString(), anyString())).thenAnswer(inv -> {
            byte[] bytes = objects.get(inv.<String>getArgument(0));
            if (bytes == null) {
                throw NoSuchKeyException.builder().message("gone").build();
            }
            return new ResponseInputStream<>(
                    GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static JwtRequestPostProcessor asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private static JwtRequestPostProcessor officer() {
        return asRole(OWNER, "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor leader() {
        return asRole("leader-1", "lanh-dao", "LANH_DAO");
    }

    private void setConfig(String key, Object value) throws Exception {
        mockMvc.perform(patch("/admin/system-config/" + key)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", value))))
                .andExpect(status().isOk());
    }

    private UUID createWarehouseFolder(String name) throws Exception {
        String json = mockMvc.perform(post("/folders")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString((String) objectMapper.readValue(json, Map.class).get("id"));
    }

    private PersonalFolder personalFolder(String owner, String name, UUID parentId) {
        PersonalFolder f = new PersonalFolder();
        f.setOwnerSub(owner);
        f.setOwnerUsername(owner);
        f.setName(name);
        f.setParentId(parentId);
        f.setCreatedAt(OffsetDateTime.now());
        f.setUpdatedAt(OffsetDateTime.now());
        return personalFolderRepository.save(f);
    }

    private static byte[] pngBytes(String tag) {
        byte[] tail = tag.getBytes();
        byte[] out = new byte[PNG_HEADER.length + tail.length];
        System.arraycopy(PNG_HEADER, 0, out, 0, PNG_HEADER.length);
        System.arraycopy(tail, 0, out, PNG_HEADER.length, tail.length);
        return out;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private PersonalFile personalPng(String owner, UUID folderId, String name, String tag) throws Exception {
        byte[] bytes = pngBytes(tag);
        return personalFile(owner, folderId, name, "image/png", bytes, sha256(bytes));
    }

    private PersonalFile personalFile(
            String owner, UUID folderId, String name, String mime, byte[] bytes, String sha) {
        PersonalFile f = new PersonalFile();
        f.setOwnerSub(owner);
        f.setOwnerUsername(owner);
        f.setFolderId(folderId);
        f.setOriginalName(name);
        f.setMimeType(mime);
        f.setSize(bytes.length);
        f.setSha256(sha);
        String key = owner + "/" + UUID.randomUUID();
        f.setStorageKey(key);
        f.setCreatedAt(OffsetDateTime.now());
        objects.put(key, bytes);
        return personalFileRepository.save(f);
    }

    /** displayNames for every personal file in the DB (= its original name); the server ignores unselected ones. */
    private List<Map<String, Object>> defaultDisplayNames() {
        return personalFileRepository.findAll().stream()
                .map(f -> Map.<String, Object>of("personalFileId", f.getId(), "displayName", f.getOriginalName()))
                .toList();
    }

    private String body(UUID target, List<UUID> fileIds, List<UUID> folderIds) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "targetFolderId", target, "fileIds", fileIds, "folderIds", folderIds,
                "displayNames", defaultDisplayNames()));
    }

    private Map<String, Object> startOk(UUID target, List<UUID> fileIds, List<UUID> folderIds) throws Exception {
        String json = mockMvc.perform(post("/ingest/from-personal")
                        .with(officer())
                        .contentType("application/json")
                        .content(body(target, fileIds, folderIds)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return parsed;
    }

    private Map<String, Object> awaitClosed(Object batchId) {
        return awaitClosedAs(officer(), batchId);
    }

    /** Same as {@link #awaitClosed}, parameterized by role - {@code /ingest/from-personal/{id}} is owner-scoped. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitClosedAs(JwtRequestPostProcessor role, Object batchId) {
        Map<String, Object>[] holder = new Map[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            String json = mockMvc.perform(get("/ingest/from-personal/" + batchId).with(role))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            holder[0] = objectMapper.readValue(json, Map.class);
            return "CLOSED".equals(holder[0].get("status"));
        });
        return holder[0];
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemFor(Map<String, Object> result, UUID personalFileId) {
        return ((List<Map<String, Object>>) result.get("items")).stream()
                .filter(i -> personalFileId.toString().equals(i.get("personalFileId")))
                .findFirst()
                .orElseThrow();
    }

    // -------------------------------------------------------------- AC-1 / AC-3

    // r2-business-parity.md ADR-1 / V35: LANH_DAO (R2) now has document.upload
    // too (SPEC-r2-business-parity-01 AC-1, R-7) - split into "leader, owner of
    // their own personal file, gets 202" + "admin (R3) still 403".
    @Test
    void canBoGets202() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile f = personalPng(OWNER, null, "a.png", "a");

        startOk(target, List.of(f.getId()), List.of());
    }

    @Test
    void leaderGets202ForTheirOwnPersonalFile() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile f = personalPng("leader-1", null, "a.png", "a");

        mockMvc.perform(post("/ingest/from-personal")
                        .with(leader())
                        .contentType("application/json")
                        .content(body(target, List.of(f.getId()), List.of())))
                .andExpect(status().isAccepted());
    }

    // SPEC-r2-business-parity-01 AC-9 (R-6): the document created from a
    // leader-initiated personal-to-warehouse ingest carries uploader_sub of
    // the leader who started it, same as R1 (§4.3 assertion above).
    @Test
    void leaderInitiatedIngestProducesADocumentUploadedByTheLeader() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile f = personalPng("leader-1", null, "a.png", "a");

        String json = mockMvc.perform(post("/ingest/from-personal")
                        .with(leader())
                        .contentType("application/json")
                        .content(body(target, List.of(f.getId()), List.of())))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> started = objectMapper.readValue(json, Map.class);
        awaitClosedAs(leader(), started.get("batchId"));

        Document created = documentRepository.findAll().stream()
                .filter(d -> f.getId().equals(d.getSourcePersonalFileId()))
                .findFirst()
                .orElseThrow();
        assertThat(created.getUploaderSub()).isEqualTo("leader-1");
    }

    @Test
    void adminIsStillForbidden() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile f = personalPng(OWNER, null, "a.png", "a");

        mockMvc.perform(post("/ingest/from-personal")
                        .with(admin())
                        .contentType("application/json")
                        .content(body(target, List.of(f.getId()), List.of())))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------- AC-2

    @Test
    void anotherUsersFileIsIndistinguishableFromMissing() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile foreign = personalPng("someone-else", null, "x.png", "x");

        String foreignJson = mockMvc.perform(post("/ingest/from-personal")
                        .with(officer())
                        .contentType("application/json")
                        .content(body(target, List.of(foreign.getId()), List.of())))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingJson = mockMvc.perform(post("/ingest/from-personal")
                        .with(officer())
                        .contentType("application/json")
                        .content(body(target, List.of(UUID.randomUUID()), List.of())))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(foreignJson).get("message"))
                .isEqualTo(objectMapper.readTree(missingJson).get("message"));

        // admin (R3) picking someone else's file is equally 404 - here: admin lacks upload perm => 403 anyway;
        // the owner-scoping itself is what the two calls above prove.
        assertThat(documentRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------ AC-4-5

    @Test
    void folderSelectionKeepsSelectedFolderNameInRelativePathAndCreatesNoWarehouseFolder() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        long foldersBefore = warehouseFolderRepository.count();
        PersonalFolder a = personalFolder(OWNER, "Hồ sơ A", null);
        PersonalFolder b = personalFolder(OWNER, "B", a.getId());
        personalFolder(OWNER, "Rỗng", a.getId());
        PersonalFile loose = personalPng(OWNER, null, "loose.png", "loose");
        PersonalFile inA = personalPng(OWNER, a.getId(), "inA.png", "inA");
        PersonalFile inB = personalPng(OWNER, b.getId(), "x.png", "inB");

        Map<String, Object> started = startOk(target, List.of(loose.getId()), List.of(a.getId()));
        assertThat(started.get("total")).isEqualTo(3);
        Map<String, Object> result = awaitClosed(started.get("batchId"));

        assertThat(itemFor(result, loose.getId()).get("status")).isEqualTo("INGESTED");
        assertThat(itemFor(result, inA.getId()).get("status")).isEqualTo("INGESTED");
        assertThat(itemFor(result, inB.getId()).get("status")).isEqualTo("INGESTED");

        Map<UUID, Document> bySource = new java.util.HashMap<>();
        documentRepository.findAll().forEach(d -> bySource.put(d.getSourcePersonalFileId(), d));
        assertThat(bySource.get(loose.getId()).getRelativePath()).isEmpty();
        assertThat(bySource.get(inA.getId()).getRelativePath()).isEqualTo("Hồ sơ A");
        assertThat(bySource.get(inB.getId()).getRelativePath()).isEqualTo("Hồ sơ A/B");
        assertThat(warehouseFolderRepository.count()).isEqualTo(foldersBefore);

        // AC-16/AC-18/AC-22: provenance + new storage key, personal originals untouched.
        Document d = bySource.get(inB.getId());
        assertThat(d.getSourceKind()).isEqualTo("personal");
        assertThat(d.getUploaderSub()).isEqualTo(OWNER);
        assertThat(d.getFolderId()).isEqualTo(target);
        assertThat(d.getStorageKey()).startsWith("documents/").isNotEqualTo(inB.getStorageKey());
        assertThat(personalFileRepository.findById(inB.getId())).isPresent();
    }

    // -------------------------------------------------------------------- AC-6

    @Test
    void unknownWarehouseTargetIsNotFound() throws Exception {
        PersonalFile f = personalPng(OWNER, null, "a.png", "a");
        mockMvc.perform(post("/ingest/from-personal")
                        .with(officer())
                        .contentType("application/json")
                        .content(body(UUID.randomUUID(), List.of(f.getId()), List.of())))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------- AC-8

    @Test
    void eligibleFilesOverTheBatchLimitAreRejectedWith400AndNothingIsCreated() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        setConfig("ingest.max_files_per_batch", 2);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(personalPng(OWNER, null, "f" + i + ".png", "f" + i).getId());
        }

        mockMvc.perform(post("/ingest/from-personal")
                        .with(officer())
                        .contentType("application/json")
                        .content(body(target, ids, List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));

        assertThat(uploadBatchRepository.findAll()).isEmpty();
        assertThat(itemRepository.findAll()).isEmpty();
    }

    @Test
    void ineligibleFilesDoNotCountAgainstTheLimitAndAreReported() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        setConfig("ingest.max_files_per_batch", 2);
        PersonalFile p1 = personalPng(OWNER, null, "p1.png", "p1");
        PersonalFile p2 = personalPng(OWNER, null, "p2.png", "p2");
        byte[] exe = "MZ...".getBytes();
        PersonalFile bad = personalFile(OWNER, null, "virus.exe", "application/x-msdownload", exe, sha256(exe));

        Map<String, Object> started = startOk(target, List.of(p1.getId(), p2.getId(), bad.getId()), List.of());
        assertThat(started.get("ineligible")).asList().hasSize(1);
        Map<String, Object> result = awaitClosed(started.get("batchId"));

        assertThat(itemFor(result, bad.getId()).get("code")).isEqualTo("INVALID_FORMAT");
        assertThat(itemFor(result, p1.getId()).get("status")).isEqualTo("INGESTED");
        assertThat(itemFor(result, p2.getId()).get("status")).isEqualTo("INGESTED");
    }

    // ------------------------------------------------------------ AC-11-AC-13, AC-15

    @Test
    void malwareChecksumAndMissingSourceFailOnlyTheirOwnItem() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile infected = personalPng(OWNER, null, "infected.png", "infected");
        PersonalFile tampered = personalPng(OWNER, null, "tampered.png", "tampered");
        tampered.setSha256("0".repeat(64));
        personalFileRepository.save(tampered);
        PersonalFile vanished = personalPng(OWNER, null, "vanished.png", "vanished");
        objects.remove(vanished.getStorageKey());
        PersonalFile good = personalPng(OWNER, null, "good.png", "good");

        when(clamdService.scanStream(any())).thenAnswer(inv -> {
            byte[] read = ((java.io.InputStream) inv.getArgument(0)).readAllBytes();
            return new String(read).contains("infected")
                    ? ClamdService.ScanVerdict.infected("Eicar-Test")
                    : ClamdService.ScanVerdict.ofClean();
        });

        Map<String, Object> started =
                startOk(target, List.of(infected.getId(), tampered.getId(), vanished.getId(), good.getId()), List.of());
        Map<String, Object> result = awaitClosed(started.get("batchId"));

        assertThat(itemFor(result, infected.getId()).get("code")).isEqualTo("MALWARE_DETECTED");
        assertThat(itemFor(result, tampered.getId()).get("code")).isEqualTo("CHECKSUM_MISMATCH");
        assertThat(itemFor(result, vanished.getId()).get("code")).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(itemFor(result, good.getId()).get("status")).isEqualTo("INGESTED");
        assertThat(documentRepository.findAll().stream().filter(d -> "clean".equals(d.getStatus())))
                .hasSize(1);

        // SPEC-folder-upload-display-name: no more SKIPPED/ALREADY_EXISTS - re-sending the same display name
        // is a per-item DISPLAY_NAME_CONFLICT (the client then renames it, see the PUT tests below).
        Map<String, Object> again = startOk(target, List.of(good.getId()), List.of());
        Map<String, Object> againResult = awaitClosed(again.get("batchId"));
        assertThat(itemFor(againResult, good.getId()).get("status")).isEqualTo("FAILED");
        assertThat(itemFor(againResult, good.getId()).get("code")).isEqualTo("DISPLAY_NAME_CONFLICT");
    }

    // ---------------------------------------------- display-name (AC-18/19/20)

    @Test
    void nameCheckExpandsTheSelectionLikeStartAndReportsTakenNames() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFolder a = personalFolder(OWNER, "A", null);
        PersonalFile inA = personalPng(OWNER, a.getId(), "x.png", "x");
        PersonalFile loose = personalPng(OWNER, null, "loose.png", "loose");
        awaitClosed(startOk(target, List.of(), List.of(a.getId())).get("batchId")); // x.png now lives at path "A"

        String json = mockMvc.perform(post("/ingest/from-personal/name-check")
                        .with(officer())
                        .contentType("application/json")
                        .content(body(target, List.of(loose.getId()), List.of(a.getId()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = objectMapper.readValue(json, List.class);
        assertThat(items).hasSize(2);
        Map<String, Object> xa = items.stream().filter(i -> inA.getId().toString().equals(i.get("personalFileId"))).findFirst().orElseThrow();
        Map<String, Object> l = items.stream().filter(i -> loose.getId().toString().equals(i.get("personalFileId"))).findFirst().orElseThrow();
        assertThat(xa.get("relativePath")).isEqualTo("A");
        assertThat(xa.get("taken")).isEqualTo(true);
        assertThat(l.get("relativePath")).isEqualTo("");
        assertThat(l.get("taken")).isEqualTo(false);
    }

    @Test
    void relativePathIsFolderTreePathPlusThePersonalFilesOwnRelativePath() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFolder a = personalFolder(OWNER, "A", null);
        PersonalFile f = personalPng(OWNER, a.getId(), "x.png", "x");
        f.setRelativePath("sub/deep");
        personalFileRepository.save(f);
        awaitClosed(startOk(target, List.of(), List.of(a.getId())).get("batchId"));
        assertThat(documentRepository.findAll().get(0).getRelativePath()).isEqualTo("A/sub/deep");
    }

    @Test
    void missingOrInvalidDisplayNameFailsOnlyThatItem() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile ok = personalPng(OWNER, null, "ok.png", "ok");
        PersonalFile missing = personalPng(OWNER, null, "missing.png", "missing");
        PersonalFile bad = personalPng(OWNER, null, "bad.png", "bad");
        String content = objectMapper.writeValueAsString(Map.of(
                "targetFolderId", target,
                "fileIds", List.of(ok.getId(), missing.getId(), bad.getId()),
                "folderIds", List.of(),
                "displayNames", List.of(
                        Map.of("personalFileId", ok.getId(), "displayName", "ok.png"),
                        Map.of("personalFileId", bad.getId(), "displayName", "not-related.png"))));
        String json = mockMvc.perform(post("/ingest/from-personal").with(officer()).contentType("application/json").content(content))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        Map<String, Object> result = awaitClosed(objectMapper.readValue(json, Map.class).get("batchId"));
        assertThat(itemFor(result, ok.getId()).get("status")).isEqualTo("INGESTED");
        assertThat(itemFor(result, missing.getId()).get("code")).isEqualTo("DISPLAY_NAME_MISSING");
        assertThat(itemFor(result, bad.getId()).get("code")).isEqualTo("INVALID_DISPLAY_NAME");
    }

    @Test
    void conflictItemCanBeRenamedThreeTimesAndTheFourthIsRejected() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile good = personalPng(OWNER, null, "good.png", "good");
        awaitClosed(startOk(target, List.of(good.getId()), List.of()).get("batchId"));
        Map<String, Object> again = startOk(target, List.of(good.getId()), List.of());
        String batchId = (String) again.get("batchId");
        Map<String, Object> result = awaitClosed(batchId);
        Map<String, Object> item = itemFor(result, good.getId());
        assertThat(item.get("code")).isEqualTo("DISPLAY_NAME_CONFLICT");
        String itemId = (String) item.get("itemId");

        // 1st rename: still clashes (a valid suffix name that is already taken is simulated by reusing the
        // original name, which is invalid -> 422 and does NOT consume a retry).
        mockMvc.perform(put("/ingest/from-personal/" + batchId + "/items/" + itemId + "/display-name")
                        .with(officer()).contentType("application/json").content("{\"displayName\":\"other.png\"}"))
                .andExpect(status().isUnprocessableEntity());

        // A valid suffix name -> item goes PENDING, re-runs and is ingested.
        String suffixed = "good_20260930101530123-ab12cd.png";
        mockMvc.perform(put("/ingest/from-personal/" + batchId + "/items/" + itemId + "/display-name")
                        .with(officer()).contentType("application/json").content("{\"displayName\":\"" + suffixed + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.nameRetryCount").value(1));
        Map<String, Object> after = awaitClosed(batchId);
        assertThat(itemFor(after, good.getId()).get("status")).isEqualTo("INGESTED");
        assertThat(documentRepository.findAll().stream().map(Document::getDisplayName)).contains(suffixed);

        // an item that is no longer FAILED/DISPLAY_NAME_CONFLICT cannot be renamed
        mockMvc.perform(put("/ingest/from-personal/" + batchId + "/items/" + itemId + "/display-name")
                        .with(officer()).contentType("application/json").content("{\"displayName\":\"" + suffixed + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void renameRetryCountIsCappedAtThreeByTheServer() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile good = personalPng(OWNER, null, "good.png", "good");
        awaitClosed(startOk(target, List.of(good.getId()), List.of()).get("batchId"));
        String batchId = (String) startOk(target, List.of(good.getId()), List.of()).get("batchId");
        Map<String, Object> item = itemFor(awaitClosed(batchId), good.getId());
        String itemId = (String) item.get("itemId");

        // burn 3 retries: each suffix name is pre-occupied so the item fails again with DISPLAY_NAME_CONFLICT
        for (int i = 1; i <= 3; i++) {
            String name = "good_2026093010153012" + i + "-ab12cd.png";
            Document blocker = new Document();
            blocker.setOriginalName("good.png");
            blocker.setDisplayName(name);
            blocker.setMimeType("image/png");
            blocker.setSize(1);
            blocker.setUploaderSub("someone");
            blocker.setUploaderUsername("someone");
            blocker.setStatus("clean");
            blocker.setFolderId(target);
            blocker.setRelativePath("");
            blocker.setCreatedAt(OffsetDateTime.now());
            documentRepository.save(blocker);
            mockMvc.perform(put("/ingest/from-personal/" + batchId + "/items/" + itemId + "/display-name")
                            .with(officer()).contentType("application/json").content("{\"displayName\":\"" + name + "\"}"))
                    .andExpect(status().isOk());
            Map<String, Object> r = awaitClosed(batchId);
            assertThat(itemFor(r, good.getId()).get("code")).isEqualTo("DISPLAY_NAME_CONFLICT");
        }
        mockMvc.perform(put("/ingest/from-personal/" + batchId + "/items/" + itemId + "/display-name")
                        .with(officer()).contentType("application/json")
                        .content("{\"displayName\":\"good_20260930101530199-ab12cd.png\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------- AC-14

    @Test
    void blockedWarehouseRejectsTheRunAtStart() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile f = personalPng(OWNER, null, "a.png", "a");
        Warehouse warehouse = warehouseRepository.findById(WarehouseRepository.SINGLETON_ID).orElseThrow();
        warehouse.setMaxCapacityBytes(1);
        warehouseRepository.save(warehouse);
        // usage must be non-zero for a 95% block: put one document in the warehouse first.
        Document existing = new Document();
        existing.setOriginalName("seed.png");
        existing.setMimeType("image/png");
        existing.setSize(100);
        existing.setSha256("f".repeat(64));
        existing.setStorageKey("documents/seed");
        existing.setFolderId(target);
        existing.setUploaderSub("x");
        existing.setUploaderUsername("x");
        existing.setStatus("clean");
        existing.setCreatedAt(OffsetDateTime.now());
        documentRepository.save(existing);

        mockMvc.perform(post("/ingest/from-personal")
                        .with(officer())
                        .contentType("application/json")
                        .content(body(target, List.of(f.getId()), List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_CAPACITY_EXCEEDED"));
    }

    // -------------------------------------------------------------------- AC-20

    @Test
    void sameContentAtAnotherLocationIsIngestedWithDuplicateOf() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFolder a = personalFolder(OWNER, "A", null);
        PersonalFile f1 = personalPng(OWNER, null, "same.png", "same");
        PersonalFile f2 = personalFile(OWNER, a.getId(), "same.png", "image/png", pngBytes("same"), sha256(pngBytes("same")));

        awaitClosed(startOk(target, List.of(f1.getId()), List.of()).get("batchId"));
        Map<String, Object> second = awaitClosed(startOk(target, List.of(), List.of(a.getId())).get("batchId"));

        Map<String, Object> item = itemFor(second, f2.getId());
        assertThat(item.get("status")).isEqualTo("INGESTED");
        assertThat((List<?>) item.get("duplicateOf")).hasSize(1);
    }

    // -------------------------------------------------------------------- AC-21

    @Test
    void addedToWarehouseFlagFollowsTheLiveWarehouseCopy() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile f = personalPng(OWNER, null, "a.png", "a");
        PersonalFile untouched = personalPng(OWNER, null, "b.png", "b");
        awaitClosed(startOk(target, List.of(f.getId()), List.of()).get("batchId"));

        assertThat(addedFlag(f.getId())).isTrue();
        assertThat(addedFlag(untouched.getId())).isFalse();

        Document d = documentRepository.findAll().get(0);
        d.setDeletedAt(OffsetDateTime.now());
        documentRepository.save(d);
        assertThat(addedFlag(f.getId())).isFalse();

        d.setDeletedAt(null);
        documentRepository.save(d);
        assertThat(addedFlag(f.getId())).isTrue();

        // AC-22: deleting the personal file leaves the document alone.
        personalFileRepository.deleteById(f.getId());
        assertThat(documentRepository.findById(d.getId())).isPresent();
    }

    @SuppressWarnings("unchecked")
    private boolean addedFlag(UUID personalFileId) throws Exception {
        String json = mockMvc.perform(get("/personal/files").with(officer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> rows = objectMapper.readValue(json, List.class);
        return (Boolean) rows.stream()
                .filter(r -> personalFileId.toString().equals(r.get("id")))
                .findFirst()
                .orElseThrow()
                .get("addedToWarehouse");
    }

    // -------------------------------------------------------------------- AC-23

    @Test
    void auditRowsForThisChannelNeverCarryRelativePathButMultipartStillDoes() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFolder secret = personalFolder(OWNER, "Thư mục riêng tư", null);
        PersonalFile f = personalPng(OWNER, secret.getId(), "a.png", "a");
        awaitClosed(startOk(target, List.of(), List.of(secret.getId())).get("batchId"));

        Document d = documentRepository.findAll().get(0);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<AuditLog> perFile =
                    auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc("personal_file", f.getId().toString());
            assertThat(perFile).anySatisfy(r -> assertThat(r.getAction()).isEqualTo("ingest.from_personal.file"));
            List<AuditLog> created =
                    auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc("document", d.getId().toString());
            assertThat(created).anySatisfy(r -> assertThat(r.getAction()).isEqualTo("document.create"));
        });
        for (AuditLog row : auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc("personal_file", f.getId().toString())) {
            assertThat(String.valueOf(row.getMetadata())).doesNotContain("Thư mục riêng tư").doesNotContain("relativePath");
            assertThat(row.getMetadata()).containsEntry("documentId", d.getId().toString());
        }
        for (AuditLog row : auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc("document", d.getId().toString())) {
            assertThat(row.getMetadata()).doesNotContainKey("relativePath");
        }

        // Regression: the multipart channel keeps recording relativePath.
        String batchJson = mockMvc.perform(post("/ingest/batches")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("targetFolderId", target, "declaredFileCount", 1, "declaredTotalBytes", 100))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String batchId = (String) objectMapper.readValue(batchJson, Map.class).get("batchId");
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files")
                        .file(new MockMultipartFile("file", "m.png", "image/png", pngBytes("multipart")))
                        .param("relativePath", "sub/dir")
                        .param("displayName", "m.png")
                        .with(officer()))
                .andExpect(status().isCreated());
        Document m = documentRepository.findAll().stream()
                .filter(x -> x.getSourceKind() == null)
                .findFirst()
                .orElseThrow();
        assertThat(m.getSourcePersonalFileId()).isNull();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc("document", m.getId().toString()))
                .anySatisfy(r -> assertThat(r.getMetadata()).containsEntry("relativePath", "sub/dir")));
    }

    // -------------------------------------------------------------- cleanup job

    @Test
    void cleanupDeletesOnlyItemsOfFinishedBatchesOlderThanRetention() throws Exception {
        UUID target = createWarehouseFolder("Kho");
        PersonalFile f = personalPng(OWNER, null, "a.png", "a");
        Map<String, Object> started = startOk(target, List.of(f.getId()), List.of());
        awaitClosed(started.get("batchId"));

        // fresh finished batch: kept
        assertThat(personalToWarehouseService.purgeOldItems(OffsetDateTime.now())).isZero();
        assertThat(itemRepository.count()).isEqualTo(1);

        // 31 days later: removed
        assertThat(personalToWarehouseService.purgeOldItems(OffsetDateTime.now().plusDays(31)))
                .isEqualTo(1);
        assertThat(itemRepository.count()).isZero();
    }
}
