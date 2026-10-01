package com.aismartstore.backend.auth;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** r2-business-parity-03 Task 1 (AC-1): GET /me/permissions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class MeControllerIT {

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
    private KeycloakAdminService kc;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void lanhDaoGetsRoleAndStory01Permissions() throws Exception {
        mockMvc.perform(get("/me/permissions")
                        .with(jwt().authorities(RolePermissionFixtures.authoritiesFor("LANH_DAO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("lanh-dao"))
                .andExpect(jsonPath("$.permissions", hasItems(
                        "document.upload", "digitization_review.manage", "trash.manage",
                        "tag.manage", "note.write")));
    }

    @Test
    void quanTriDoesNotHoldStory01Permissions() throws Exception {
        mockMvc.perform(get("/me/permissions")
                        .with(jwt().authorities(RolePermissionFixtures.authoritiesFor("QUAN_TRI"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("quan-tri"))
                .andExpect(jsonPath("$.permissions", not(hasItem("trash.manage"))))
                .andExpect(jsonPath("$.permissions", not(hasItem("document.upload"))));
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(get("/me/permissions")).andExpect(status().isUnauthorized());
    }
}
