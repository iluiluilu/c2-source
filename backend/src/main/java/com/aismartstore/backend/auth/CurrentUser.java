package com.aismartstore.backend.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the decoded Keycloak access token as a {@link KeycloakUser} into a
 * controller method parameter. Mirrors the NestJS backend's
 * {@code @CurrentUser()} decorator (auth/current-user.decorator.ts).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUser {
}
