package com.aismartstore.backend.userdirectory;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import java.util.List;
import java.util.Map;
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
 * group-management-sharing-01-group-lifecycle.md §5, Story 5: MockMvc
 * coverage of the {@code hasAnyRole('CAN_BO','LANH_DAO')} boundary and the
 * {@code q} length validation, end to end through the real Spring Security
 * filter chain. {@link KeycloakAdminService} is mocked, same as {@code
 * AccountsControllerIT} - no Keycloak Testcontainers module in this project.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class UserDirectoryControllerIT {

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

    private static JwtRequestPostProcessor asRole(String sub, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(RolePermissionFixtures.authoritiesFor(springRole));
    }

    @Test
    void queryShorterThanTwoCharactersIs400() throws Exception {
        mockMvc.perform(get("/users/directory").param("q", "a").with(asRole("u1", "can-bo", "CAN_BO")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void canBoAndLanhDaoCanSearch() throws Exception {
        when(kc.searchUsers(anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/users/directory").param("q", "ng").with(asRole("u1", "can-bo", "CAN_BO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/users/directory").param("q", "ng").with(asRole("u2", "lanh-dao", "LANH_DAO")))
                .andExpect(status().isOk());
    }

    @Test
    void quanTriIsForbidden() throws Exception {
        mockMvc.perform(get("/users/directory").param("q", "ng").with(asRole("admin", "quan-tri", "QUAN_TRI")))
                .andExpect(status().isForbidden());
    }
}
