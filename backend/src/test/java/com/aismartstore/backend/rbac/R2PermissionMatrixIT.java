package com.aismartstore.backend.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.aismartstore.backend.auth.RoleCodes;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.storage.ObjectStorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SPEC-r2-business-parity-01 §5.3 (AC-1..AC-6, AC-10): ma trận 32 endpoint ×
 * 3 vai trò (R1/{@code CAN_BO}, R2/{@code LANH_DAO}, R3/{@code QUAN_TRI}) -
 * kỳ vọng R1/R2 không bao giờ 403, R3 luôn 403. Khác với hầu hết IT khác,
 * authorities ở đây dựng <b>từ DB sau khi Flyway chạy</b> ({@code
 * role_permission}), không dùng {@code RolePermissionFixtures} (hardcode) -
 * nên test này (cùng {@code RolePermissionSeedTest}) là bằng chứng V35 thực
 * sự có hiệu lực, không chỉ là {@code @PreAuthorize} đúng annotation.
 *
 * <p>Mỗi case dùng path UUID hợp lệ (nhưng không tồn tại) + body JSON/
 * multipart hợp lệ tối thiểu theo từng DTO's bean validation - vì argument
 * resolution ({@code @RequestBody}/{@code @Valid}, path UUID conversion)
 * chạy <b>trước</b> proxy {@code @PreAuthorize} (xem spec §5.3 lưu ý kỹ
 * thuật): nếu body/path không hợp lệ, R3 sẽ nhận 400 thay vì 403 và test sẽ
 * sai ý. "Pass" (R1/R2) = status khác 403 - entity không tồn tại nên 2xx/400/
 * 404/409 đều hợp lệ, logic nghiệp vụ phía sau {@code @PreAuthorize} không
 * phải thứ test này xác minh.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class R2PermissionMatrixIT {

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
    private ClamdService clamdService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private static final List<String> ROLE_CODES = List.of("CAN_BO", "LANH_DAO", "QUAN_TRI");

    /** §5.2: đúng 5 permission được cấp thêm cho LANH_DAO (R2) qua V35. */
    private static final List<String> MATRIX_PERMISSIONS =
            List.of("document.upload", "tag.manage", "note.write", "digitization_review.manage", "trash.manage");

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String json(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof List<?> list) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(list.get(i)).append('"');
                }
                sb.append(']');
            } else {
                sb.append('"').append(v).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private record MatrixCase(String label, Supplier<MockHttpServletRequestBuilder> request) {
    }

    /** §5.2 - thứ tự y hệt bảng, số # trong label khớp cột # của spec. */
    private static final List<MatrixCase> ENDPOINTS = List.of(
            new MatrixCase("1 document.upload GET /ingest/limits", () -> get("/ingest/limits")),
            new MatrixCase("2 document.upload GET /ingest/allowed-formats", () -> get("/ingest/allowed-formats")),
            new MatrixCase("3 document.upload POST /ingest/batches", () -> post("/ingest/batches")
                    .contentType("application/json")
                    .content(json(Map.of("targetFolderId", uuid(), "declaredFileCount", 1, "declaredTotalBytes", 10)))),
            new MatrixCase("4 document.upload POST /ingest/batches/{batchId}/files", () -> multipart(
                            "/ingest/batches/" + uuid() + "/files")
                    .file(new MockMultipartFile("file", "a.png", "image/png",
                            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}))
                    .param("displayName", "a.png")),
            new MatrixCase("5 document.upload POST /ingest/name-check", () -> post("/ingest/name-check")
                    .contentType("application/json")
                    .content(json(Map.of("folderId", uuid(), "files", List.of())))),
            new MatrixCase(
                    "6 document.upload POST /ingest/batches/{batchId}/upload-sessions",
                    () -> post("/ingest/batches/" + uuid() + "/upload-sessions")
                            .contentType("application/json")
                            .content(json(Map.of("originalName", "a.png", "declaredSizeBytes", 1)))),
            new MatrixCase("7 document.upload POST /ingest/batches/{batchId}/close",
                    () -> post("/ingest/batches/" + uuid() + "/close")),
            new MatrixCase("8 document.upload GET /ingest/batches/{batchId}",
                    () -> get("/ingest/batches/" + uuid())),
            new MatrixCase("9 document.upload POST /ingest/from-personal", () -> post("/ingest/from-personal")
                    .contentType("application/json")
                    .content(json(Map.of("targetFolderId", uuid(), "fileIds", List.of(uuid()))))),
            new MatrixCase(
                    "10 document.upload POST /ingest/from-personal/name-check",
                    () -> post("/ingest/from-personal/name-check")
                            .contentType("application/json")
                            .content(json(Map.of("targetFolderId", uuid(), "fileIds", List.of(uuid()))))),
            new MatrixCase(
                    "11 document.upload PUT /ingest/from-personal/{batchId}/items/{itemId}/display-name",
                    () -> put("/ingest/from-personal/" + uuid() + "/items/" + uuid() + "/display-name")
                            .contentType("application/json")
                            .content(json(Map.of("displayName", "a.png")))),
            new MatrixCase("12 document.upload GET /ingest/from-personal/{batchId}",
                    () -> get("/ingest/from-personal/" + uuid())),
            new MatrixCase("13 tag.manage POST /tags", () -> post("/tags")
                    .contentType("application/json")
                    .content(json(Map.of("kind", "topic", "label", "matrix-tag-" + uuid())))),
            new MatrixCase("14 tag.manage PATCH /tags/{id}", () -> patch("/tags/" + uuid())
                    .contentType("application/json")
                    .content(json(Map.of("label", "matrix-renamed")))),
            new MatrixCase("15 tag.manage POST /tags/{id}/deactivate", () -> post("/tags/" + uuid() + "/deactivate")),
            new MatrixCase("16 tag.manage POST /documents/{documentId}/tags",
                    () -> post("/documents/" + uuid() + "/tags")
                            .contentType("application/json")
                            .content(json(Map.of("tagId", uuid())))),
            new MatrixCase("17 tag.manage DELETE /documents/{documentId}/tags/{tagId}",
                    () -> delete("/documents/" + uuid() + "/tags/" + uuid())),
            new MatrixCase("18 note.write POST /documents/{documentId}/notes",
                    () -> post("/documents/" + uuid() + "/notes")
                            .contentType("application/json")
                            .content(json(Map.of("body", "ghi chú test")))),
            new MatrixCase("19 note.write PATCH /documents/{documentId}/notes/{noteId}",
                    () -> patch("/documents/" + uuid() + "/notes/" + uuid())
                            .contentType("application/json")
                            .content(json(Map.of("body", "ghi chú test 2")))),
            new MatrixCase("20 digitization_review.manage GET /digitization-review/queue",
                    () -> get("/digitization-review/queue")),
            new MatrixCase("21 digitization_review.manage GET /digitization-review/runs/{runId}",
                    () -> get("/digitization-review/runs/" + uuid())),
            new MatrixCase(
                    "22 digitization_review.manage PATCH /digitization-review/segments/{segmentId}",
                    () -> patch("/digitization-review/segments/" + uuid())
                            .contentType("application/json")
                            .content(json(Map.of("expectedRevisionNo", 1, "text", "x")))),
            new MatrixCase(
                    "23 digitization_review.manage DELETE /digitization-review/segments/{segmentId}",
                    () -> delete("/digitization-review/segments/" + uuid())
                            .param("expectedRevisionNo", "1")),
            new MatrixCase(
                    "24 digitization_review.manage POST /digitization-review/runs/{runId}/segments",
                    () -> post("/digitization-review/runs/" + uuid() + "/segments")
                            .contentType("application/json")
                            .content(json(Map.of("text", "x")))),
            new MatrixCase(
                    "25 digitization_review.manage POST /digitization-review/runs/{runId}/confirm",
                    () -> post("/digitization-review/runs/" + uuid() + "/confirm")),
            new MatrixCase(
                    "26 digitization_review.manage GET /digitization-review/runs/{runId}/pages/{pageNo}/image",
                    () -> get("/digitization-review/runs/" + uuid() + "/pages/1/image")),
            new MatrixCase("27 trash.manage POST /documents/trash", () -> post("/documents/trash")
                    .contentType("application/json")
                    .content(json(Map.of("documentIds", List.of(uuid()))))),
            new MatrixCase("28 trash.manage POST /documents/trash-preview", () -> post("/documents/trash-preview")
                    .contentType("application/json")
                    .content(json(Map.of("documentIds", List.of(uuid()))))),
            new MatrixCase("29 trash.manage GET /trash", () -> get("/trash")),
            new MatrixCase("30 trash.manage GET /trash/{id}", () -> get("/trash/" + uuid())),
            new MatrixCase("31 trash.manage POST /trash/{id}/restore", () -> post("/trash/" + uuid() + "/restore")),
            new MatrixCase("32 trash.manage POST /trash/restore-batch", () -> post("/trash/restore-batch")
                    .contentType("application/json")
                    .content(json(Map.of("documentIds", List.of(uuid()))))));

    static {
        if (ENDPOINTS.size() != 32) {
            throw new IllegalStateException("§5.2 liệt kê đúng 32 endpoint, ENDPOINTS có " + ENDPOINTS.size());
        }
    }

    /** 32 endpoint × 3 role = 96 case. */
    static Stream<Arguments> matrixArgs() {
        List<Arguments> args = new ArrayList<>();
        for (MatrixCase c : ENDPOINTS) {
            for (String role : ROLE_CODES) {
                args.add(Arguments.of(c.label(), role, "QUAN_TRI".equals(role), c.request()));
            }
        }
        return args.stream();
    }

    /** ROLE_&lt;code&gt; + PERM_&lt;code&gt; từ {@code role_permission} thật trong DB (sau Flyway), không fixture. */
    private JwtRequestPostProcessor jwtFromDb(String roleCode) {
        List<String> permissions = jdbcTemplate.queryForList(
                "SELECT permission_code FROM role_permission WHERE role_code = ?", String.class, roleCode);
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
        }
        String username = "matrix-" + roleCode.toLowerCase(java.util.Locale.ROOT);
        String realmRole = RoleCodes.toKeycloakRoleName(roleCode);
        JwtRequestPostProcessor processor = jwt().jwt((Jwt.Builder builder) -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(authorities);
        return processor;
    }

    @ParameterizedTest(name = "[{index}] {0} | role={1} | expectForbidden={2}")
    @MethodSource("matrixArgs")
    void endpointPermissionMatrix(
            String endpointLabel, String roleCode, boolean expectForbidden,
            Supplier<MockHttpServletRequestBuilder> requestSupplier) throws Exception {
        int status = mockMvc.perform(requestSupplier.get().with(jwtFromDb(roleCode)))
                .andReturn()
                .getResponse()
                .getStatus();

        if (expectForbidden) {
            assertThat(status).as(endpointLabel + " role=" + roleCode).isEqualTo(403);
        } else {
            assertThat(status).as(endpointLabel + " role=" + roleCode).isNotEqualTo(403);
        }
    }

    /**
     * §5.3: đếm {@code @PreAuthorize} chứa 1 trong 5 quyền bằng reflection
     * trên toàn bộ handler method đã map, so khớp với 32 dòng của ma trận -
     * để endpoint mới gate bởi 1 trong 5 quyền này mà quên thêm vào §5.2/
     * ENDPOINTS ở trên không lọt qua được.
     */
    @Test
    void preAuthorizeCountForTheFivePermissionsMatchesTheMatrixOf32() {
        List<Pattern> patterns = MATRIX_PERMISSIONS.stream()
                .map(p -> Pattern.compile("PERM_" + Pattern.quote(p) + "\\b"))
                .toList();

        long count = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            PreAuthorize ann = entry.getValue().getMethodAnnotation(PreAuthorize.class);
            if (ann == null) {
                continue;
            }
            String expr = ann.value();
            if (patterns.stream().anyMatch(p -> p.matcher(expr).find())) {
                count++;
            }
        }

        assertThat(count)
                .as("Số @PreAuthorize gate bởi 1 trong 5 quyền r2-business-parity phải khớp §5.2 (32 dòng); "
                        + "nếu lệch, cập nhật ENDPOINTS ở trên + bảng §5.2 trong spec")
                .isEqualTo(32);
    }
}
