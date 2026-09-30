package com.aismartstore.backend.config;

import com.aismartstore.backend.accounts.AccountStatusFilter;
import com.aismartstore.backend.auth.RoleCodes;
import com.aismartstore.backend.rbac.RolePermission;
import com.aismartstore.backend.rbac.RolePermissionRepository;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Resource-server security config. Equivalent to the NestJS backend's
 * {@code KeycloakConnectModule} + global {@code AuthGuard}/{@code RoleGuard}
 * (app.module.ts): validates the bearer JWT (offline, against Keycloak's
 * JWKS) and maps its {@code realm_access.roles} claim onto Spring authorities
 * so {@code @PreAuthorize("hasRole('CAN_BO')")} works on controller methods.
 *
 * Method-level {@code @PreAuthorize} is used instead of a global role guard
 * so the read/write (GET vs mutating verb) convention documented in
 * docs/technical/auth-and-rbac.md is expressed directly on each handler, the
 * same "per HTTP-verb method" pattern as the original backend.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Endpoints that skip authentication entirely (mirrors @Public() usages). */
    private static final String[] PUBLIC_PATHS = {"/", "/public"};

    private final RolePermissionRepository rolePermissionRepository;
    private final Cache<String, Set<String>> rolePermissionCache;

    public SecurityConfig(
            RolePermissionRepository rolePermissionRepository, Cache<String, Set<String>> rolePermissionCache) {
        this.rolePermissionRepository = rolePermissionRepository;
        this.rolePermissionCache = rolePermissionCache;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AccountStatusFilter accountStatusFilter)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // account-lifecycle.md §4.5 (lớp 2): runs after the JWT is verified
                // offline, so it can re-check account.status per request and reject
                // a still-valid access token belonging to a locked/disabled account.
                .addFilterAfter(accountStatusFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Converts realm roles (Keycloak's {@code realm_access.roles}, e.g.
     * {@code can-bo}) into Spring authorities {@code ROLE_CAN_BO}, matching
     * {@link com.aismartstore.backend.auth.Role} constants so
     * {@code hasRole('CAN_BO')} works. Bare Keycloak role names (kebab-case)
     * are upper-snake-cased; unknown roles pass through uppercased too.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::realmRoleAuthorities);
        return converter;
    }

    /**
     * rbac-permission-editing.md §4.3 (Quyết định 2, SA): besides the
     * {@code ROLE_<code>} authorities, also emits {@code PERM_<code>} for
     * every permission {@code role_permission} currently grants each of the
     * JWT's realm roles - so {@code @PreAuthorize("hasAuthority('PERM_...')")}
     * reflects R3's live edits (Task 6), not just the fixed role set. Runs
     * synchronously in the auth flow; a 60s-TTL cache
     * ({@link #rolePermissionCache}) keeps this to at most one small DB
     * query per role per 60 seconds, not per request (NFR-01, see mục 4.3
     * note 2).
     */
    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> realmRoleAuthorities(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof java.util.Map<?, ?> map)) {
            return List.of();
        }
        Object rolesClaim = map.get("roles");
        if (!(rolesClaim instanceof List<?> roles)) {
            return List.of();
        }

        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String role : (List<String>) roles) {
            String roleCode = RoleCodes.toRoleCode(role);
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
            for (String permissionCode : permissionsFor(roleCode)) {
                authorities.add(new SimpleGrantedAuthority("PERM_" + permissionCode));
            }
        }
        return authorities;
    }

    private Set<String> permissionsFor(String roleCode) {
        return rolePermissionCache.get(roleCode, this::loadPermissions);
    }

    private Set<String> loadPermissions(String roleCode) {
        return rolePermissionRepository.findByRoleCode(roleCode).stream()
                .map(RolePermission::getPermissionCode)
                .collect(Collectors.toSet());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
