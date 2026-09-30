package com.aismartstore.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code app.keycloak.*} keys in application.yml. */
@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakProperties(String authServerUrl, String realm, String clientId, String clientSecret) {
}
