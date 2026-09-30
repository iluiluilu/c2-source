package com.aismartstore.backend.auth;

import java.util.Collections;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @CurrentUser} controller parameters from the current
 * request's validated JWT ({@link Jwt} principal set by Spring Security's
 * OAuth2 resource server support).
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(@NonNull MethodParameter parameter) {
        return parameter.getParameterAnnotation(CurrentUser.class) != null
                && parameter.getParameterType().equals(KeycloakUser.class);
    }

    @Override
    public Object resolveArgument(
            @NonNull MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            @NonNull NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT principal on the request");
        }
        return fromJwt(jwt);
    }

    @SuppressWarnings("unchecked")
    public static KeycloakUser fromJwt(Jwt jwt) {
        String sub = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        List<String> roles = Collections.emptyList();
        var realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof java.util.Map<?, ?> map && map.get("roles") instanceof List<?> list) {
            roles = (List<String>) list;
        }
        return new KeycloakUser(sub, username, roles);
    }
}
