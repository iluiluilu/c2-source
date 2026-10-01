package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.storage.ObjectStorageService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * rbac-group-scope.md §5 ("Seed role_permission khớp 100% với danh sách
 * @PreAuthorize hiện có"), Story 1 AC-4 - originally a "live invariant": the
 * {@code EXPECTED} map below was built by grepping every {@code
 * @PreAuthorize} in the codebase at the time {@code V12__init_groups.sql}
 * was written (rbac-group-scope.md Task 1), and this test asserted the DB
 * always matched the {@code @PreAuthorize} annotations at runtime.
 *
 * <p><b>rbac-permission-editing.md Task 8 (role change):</b> as of {@code
 * V15__role_permission_audit_columns.sql}, {@code role_permission} is
 * editable by R3 through {@code com.aismartstore.backend.rbac.RbacService}
 * (Story 2/3) and enforcement no longer reads {@code @PreAuthorize("hasRole
 * (...)")} directly - it reads {@code @PreAuthorize("hasAuthority('PERM_...')
 * ")}, resolved at request time from whatever {@code role_permission}
 * currently contains (see {@code SecurityConfig#realmRoleAuthorities}). So a
 * deliberate R3 edit (e.g. revoking {@code tag.manage} from {@code CAN_BO})
 * is expected to make the live DB diverge from {@code @PreAuthorize} source
 * text, and that is correct, not a bug.
 *
 * <p>This test therefore no longer means "the DB always matches the code";
 * it only means "the seed inserted by V12/V15 - i.e. the state on a fresh
 * install with zero R3 edits - matches the permission set each
 * {@code @PreAuthorize} annotation was written against". It is a point-in-
 * time data assertion about the migrations, not a runtime enforcement
 * invariant. If a future PR adds a brand-new {@code @PreAuthorize}, this
 * test has nothing to say about it unless the new migration's seed data is
 * also added to {@code EXPECTED} below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class RolePermissionSeedTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @org.springframework.test.context.DynamicPropertySource
    static void containerProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * One row per {@code @PreAuthorize} on a route with business meaning, as
     * reviewed in rbac-group-scope.md Task 1 (see V12__init_groups.sql's
     * comments for the source controllers/routes behind each permission).
     */
    private static final Map<String, Set<String>> EXPECTED = Map.ofEntries(
            Map.entry("document.read", Set.of("CAN_BO", "LANH_DAO")),
            // r2-business-parity.md ADR-1 / V35: LANH_DAO (R2) thêm vào 5 quyền
            // nghiệp vụ dưới đây, cùng với CAN_BO (R1).
            Map.entry("document.upload", Set.of("CAN_BO", "LANH_DAO")),
            Map.entry("note.write", Set.of("CAN_BO", "LANH_DAO")),
            Map.entry("tag.manage", Set.of("CAN_BO", "LANH_DAO")),
            Map.entry("folder.read", Set.of("CAN_BO", "LANH_DAO", "QUAN_TRI")),
            Map.entry("folder.manage", Set.of("QUAN_TRI")),
            Map.entry("scan_allowlist.manage", Set.of("QUAN_TRI")),
            Map.entry("system_config.manage", Set.of("QUAN_TRI")),
            Map.entry("account.read", Set.of("QUAN_TRI")),
            Map.entry("account.manage", Set.of("QUAN_TRI")),
            Map.entry("account.self_service", Set.of("CAN_BO", "LANH_DAO", "QUAN_TRI")),
            Map.entry("audit.read", Set.of("QUAN_TRI")),
            Map.entry("audit.read_scoped", Set.of("LANH_DAO")),
            Map.entry("personal.manage", Set.of("CAN_BO", "LANH_DAO", "QUAN_TRI")),
            Map.entry("group.manage", Set.of("CAN_BO", "LANH_DAO")),
            Map.entry("document.share", Set.of("CAN_BO", "LANH_DAO")),
            // group-management-sharing-01-group-lifecycle.md Task 1/V23, Story 5:
            // seed-only reference row - the actual UserDirectoryController check is
            // @PreAuthorize("hasAnyRole(...)"), not hasAuthority('PERM_...'), same
            // as every other still-static @PreAuthorize in this codebase.
            Map.entry("user.directory_search", Set.of("CAN_BO", "LANH_DAO")),
            // digitization-review-01-ocr.md V28 mục 2: hàng chờ/xem/sửa/xác nhận
            // OCR/STT (R1 + chính chủ - kiểm ownership riêng ở tầng service) và
            // chuyển quyền sở hữu tài liệu (dùng ở digitization-review-03, R3).
            // r2-business-parity.md ADR-1 / V35: + LANH_DAO (R2).
            Map.entry("digitization_review.manage", Set.of("CAN_BO", "LANH_DAO")),
            Map.entry("document.transfer_ownership", Set.of("QUAN_TRI")),
            // delete-restore-trash-01-xoa-mem-thung-rac.md V29 §2.1: xóa mềm/xem/
            // phục hồi thùng rác cá nhân (R1) và thống kê thùng rác không xem nội
            // dung (R3, Story 3). r2-business-parity.md ADR-1 / V35: + LANH_DAO (R2).
            Map.entry("trash.manage", Set.of("CAN_BO", "LANH_DAO")),
            Map.entry("trash.stats.read", Set.of("QUAN_TRI")));

    /**
     * rbac-permission-editing.md Task 8: a fresh migration (V12 seed + V15's
     * new columns, no R3 edits yet) still matches {@code EXPECTED} - this is
     * a static seed-data check, not a live runtime invariant (see class
     * javadoc). It intentionally does NOT re-verify this after
     * {@code RbacServiceTest}/{@code RbacControllerIT} exercise
     * {@code updateRolePermissions} in the same JVM, because those live in
     * different test classes with their own Testcontainers instances.
     */
    @Test
    void rolePermissionSeedMatchesThePreAuthorizeMatrixAtMigrationTime() {
        Map<String, Set<String>> actual = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT permission_code, role_code FROM role_permission");
        for (Map<String, Object> row : rows) {
            actual.computeIfAbsent((String) row.get("permission_code"), k -> new TreeSet<>())
                    .add((String) row.get("role_code"));
        }

        assertThat(actual).isEqualTo(EXPECTED);
    }

    @Test
    void everyRoleCodeInRolePermissionIsOneOfTheThreeSrsRoles() {
        List<String> roleCodes = jdbcTemplate.queryForList("SELECT code FROM role ORDER BY code", String.class);
        assertThat(roleCodes).containsExactly("CAN_BO", "LANH_DAO", "QUAN_TRI");
    }

    @Test
    void everyPermissionCodeUsedInRolePermissionExistsInPermission() {
        Integer orphanCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM role_permission rp "
                        + "LEFT JOIN permission p ON p.code = rp.permission_code WHERE p.code IS NULL",
                Integer.class);
        assertThat(orphanCount).isZero();
    }

    /**
     * rbac-permission-editing.md Task 1: V15 adds updated_at/updated_by_sub
     * to role_permission; every V12-seeded row gets updated_by_sub = NULL
     * (nobody - it's the original seed, not an R3 edit).
     */
    @Test
    void v12SeededRolePermissionRowsHaveNullUpdatedBySub() {
        Integer nonNullCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM role_permission WHERE updated_by_sub IS NOT NULL", Integer.class);
        assertThat(nonNullCount).isZero();
    }

    /** rbac-group-scope.md Task 3/V13, Story 5 AC-3: casefiles tables dropped, not just unmapped. */
    @Test
    void caseFileTablesNoLongerExistAfterV13() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN ('case_file', 'case_file_document')",
                Integer.class);
        assertThat(tableCount).isZero();
    }

    /**
     * r2-business-parity.md ADR-1 / V35 §5.5 case biên: nếu R3 đã tự chỉnh
     * {@code ('LANH_DAO','tag.manage')} (set {@code updated_by_sub}) trước
     * khi V35 chạy, {@code ON CONFLICT DO NOTHING} phải giữ nguyên dòng của
     * R3, không lỗi, không ghi đè. Flyway chỉ chạy versioned migration một
     * lần/DB nên không re-run được V35 thật; test này giả lập bằng cách thực
     * thi lại đúng câu SQL của V35 sau khi chỉnh tay dòng
     * {@code LANH_DAO/tag.manage} như một R3 edit. {@code postgres} là
     * {@code @Container static} (dùng chung giữa các test method trong lớp),
     * nên test tự khôi phục {@code updated_by_sub} về {@code NULL} ở cuối để
     * không ảnh hưởng thứ tự chạy của {@code v12SeededRolePermissionRowsHaveNullUpdatedBySub}.
     */
    @Test
    void v35ReRunIsNoopWhenR3AlreadyEditedThatPair() {
        jdbcTemplate.update(
                "UPDATE role_permission SET updated_by_sub = ? WHERE role_code = 'LANH_DAO' AND permission_code = 'tag.manage'",
                "r3-sub-1");
        try {
            jdbcTemplate.update(
                    "INSERT INTO role_permission (role_code, permission_code) VALUES "
                            + "('LANH_DAO', 'document.upload'), "
                            + "('LANH_DAO', 'tag.manage'), "
                            + "('LANH_DAO', 'note.write'), "
                            + "('LANH_DAO', 'digitization_review.manage'), "
                            + "('LANH_DAO', 'trash.manage') "
                            + "ON CONFLICT (role_code, permission_code) DO NOTHING");

            String updatedBySub = jdbcTemplate.queryForObject(
                    "SELECT updated_by_sub FROM role_permission WHERE role_code = 'LANH_DAO' AND permission_code = 'tag.manage'",
                    String.class);
            assertThat(updatedBySub).isEqualTo("r3-sub-1");

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM role_permission WHERE role_code = 'LANH_DAO' AND permission_code = 'tag.manage'",
                    Integer.class);
            assertThat(count).isEqualTo(1);
        } finally {
            jdbcTemplate.update(
                    "UPDATE role_permission SET updated_by_sub = NULL WHERE role_code = 'LANH_DAO' AND permission_code = 'tag.manage'");
        }
    }

    /** rbac-group-scope.md §5, Story 5 AC-2: the share row is a pure grant - no content/path columns. */
    @Test
    void documentGroupShareHasNoContentOrPathColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'document_group_share'",
                String.class);
        assertThat(columns)
                .containsExactlyInAnyOrder("document_id", "group_id", "shared_by_sub", "shared_at");
    }
}
