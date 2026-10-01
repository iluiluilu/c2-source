package com.aismartstore.backend.auth;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * r2-business-parity-03 Task 1: lets the web gate UI on the same effective
 * authorities the backend enforces via {@code @PreAuthorize}. Read-only,
 * no parameters - only ever reports the caller's own authorities.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    public record MePermissionsView(List<String> roles, List<String> permissions) {}

    @GetMapping("/permissions")
    @PreAuthorize("isAuthenticated()")
    public MePermissionsView permissions(Authentication authentication) {
        List<String> roles = withPrefix(authentication, "ROLE_")
                .map(RoleCodes::toKeycloakRoleName)
                .sorted()
                .toList();
        List<String> perms = withPrefix(authentication, "PERM_").sorted().toList();
        return new MePermissionsView(roles, perms);
    }

    private static Stream<String> withPrefix(Authentication authentication, String prefix) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith(prefix))
                .map(a -> a.substring(prefix.length()));
    }
}
