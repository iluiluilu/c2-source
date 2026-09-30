package com.aismartstore.backend.rbac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * rbac-permission-editing.md §4.3 (Quyết định 2, SA): a 60s-TTL in-process
 * cache of {@code role_permission}, keyed by role code (upper-snake). Read by
 * {@code SecurityConfig#realmRoleAuthorities} on every JWT authorities
 * conversion; evicted proactively by {@link RbacService#updateRolePermissions}
 * so an edit takes effect well before the TTL would otherwise expire it.
 */
@Configuration
public class RbacCacheConfig {

    @Bean
    public Cache<String, Set<String>> rolePermissionCache() {
        return Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).build();
    }
}
