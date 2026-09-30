package com.aismartstore.backend.accounts;

import com.aismartstore.backend.config.KeycloakProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Minimal Keycloak Admin REST client for the account endpoints (FR-04/05/06).
 * Hand-rolled on {@link HttpClient} deliberately (same reasoning as
 * {@code ClamdService}): the surface is a handful of JSON endpoints, and
 * every avoided dependency matters for the air-gapped deployment (NFR-11).
 * Java port of the NestJS backend's accounts/keycloak-admin.service.ts.
 *
 * Authenticates as the {@code backend} client's service account
 * (client_credentials grant), which holds the realm-management roles
 * view-users/manage-users.
 */
@Service
public class KeycloakAdminService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminService.class);
    // Response DTOs here (e.g. KcRole) are deliberately a subset of Keycloak's
    // full representations - reject-on-unknown would break on every field
    // Keycloak adds/returns that we don't model (e.g. a role's "description",
    // which every seeded realm role in realm-export.json sets).
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Refresh the cached service-account token this long before it expires. */
    private static final long TOKEN_EXPIRY_MARGIN_MS = 30_000;

    private final KeycloakProperties properties;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Realm-role representations never change id; memoize by name. */
    private final Map<String, KcRole> roleCache = new ConcurrentHashMap<>();

    private volatile CachedToken cachedToken;

    public KeycloakAdminService(KeycloakProperties properties) {
        this.properties = properties;
    }

    private record CachedToken(String value, long expiresAtEpochMs) {
    }

    public enum PasswordVerification {
        OK,
        INVALID,
        NOT_FULLY_SET_UP,
        DISABLED,
    }

    private String tokenUrl() {
        return properties.authServerUrl() + "/realms/" + properties.realm() + "/protocol/openid-connect/token";
    }

    private String adminBase() {
        return properties.authServerUrl() + "/admin/realms/" + properties.realm();
    }

    private synchronized String getToken(boolean force) {
        CachedToken token = this.cachedToken;
        if (!force && token != null && System.currentTimeMillis() < token.expiresAtEpochMs()) {
            return token.value();
        }
        return fetchServiceAccountToken();
    }

    private synchronized String fetchServiceAccountToken() {
        HttpResponse<String> res = postForm(
                tokenUrl(),
                Map.of(
                        "grant_type", "client_credentials",
                        "client_id", properties.clientId(),
                        "client_secret", properties.clientSecret()));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new KeycloakUnavailableError("service-account token request failed (" + res.statusCode() + ")");
        }
        Map<String, Object> body = readJson(res.body(), new TypeReference<>() {
        });
        String accessToken = (String) body.get("access_token");
        long expiresIn = ((Number) body.get("expires_in")).longValue();
        this.cachedToken =
                new CachedToken(accessToken, System.currentTimeMillis() + expiresIn * 1000 - TOKEN_EXPIRY_MARGIN_MS);
        return accessToken;
    }

    /** Best-effort extraction of Keycloak's error message from a response body. */
    private static String errorMessage(String body) {
        try {
            Map<String, Object> parsed = JSON.readValue(body, new TypeReference<Map<String, Object>>() {
            });
            Object message = parsed.getOrDefault(
                    "errorMessage", parsed.getOrDefault("error_description", parsed.get("error")));
            return message != null ? message.toString() : "error";
        } catch (Exception e) {
            return "error";
        }
    }

    /**
     * Admin API request with one forced-token retry on 401 (token revoked or
     * expired mid-flight). Non-2xx is classified into the Kc* error family.
     */
    private HttpResponse<String> request(String method, String path, Object body) {
        HttpResponse<String> res = doRequest(method, path, body, getToken(false));
        if (res.statusCode() == 401) {
            res = doRequest(method, path, body, getToken(true));
        }
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
            return res;
        }
        String message = errorMessage(res.body());
        switch (res.statusCode()) {
            case 404 -> throw new KcNotFoundError(message);
            case 409 -> throw new KcConflictError(message);
            case 400 -> throw new KcBadRequestError(message);
            default -> {
                // 401 (after retry), 403, 5xx: service-account/deployment problem.
                log.error("admin API {} {} failed: {} {}", method, path, res.statusCode(), message);
                throw new KeycloakUnavailableError("admin API returned " + res.statusCode() + ": " + message);
            }
        }
    }

    private HttpResponse<String> doRequest(String method, String path, Object body, String token) {
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder().uri(URI.create(adminBase() + path)).header("Authorization", "Bearer " + token);
            if (body != null) {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KeycloakUnavailableError("Keycloak admin API unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakUnavailableError("Keycloak admin API request interrupted: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listUsers(int max) {
        HttpResponse<String> res = request("GET", "/users?briefRepresentation=true&max=" + max, null);
        return readJson(res.body(), new TypeReference<>() {
        });
    }

    /**
     * group-management-sharing-01-group-lifecycle.md Story 5 (user
     * directory): Keycloak's {@code search} query param does an infix match
     * across username/firstName/lastName/email server-side - used instead of
     * a local SQL LIKE because identity fields (username, name) are not
     * mirrored into this backend's {@code account} table, only {@code
     * status}/{@code storageQuotaBytes} are (see {@code Account}).
     */
    public List<Map<String, Object>> searchUsers(String query, int max) {
        HttpResponse<String> res = request(
                "GET", "/users?briefRepresentation=true&search=" + encode(query) + "&max=" + max, null);
        return readJson(res.body(), new TypeReference<>() {
        });
    }

    public Map<String, Object> getUser(String id) {
        HttpResponse<String> res = request("GET", "/users/" + encode(id), null);
        return readJson(res.body(), new TypeReference<>() {
        });
    }

    /** Creates the user and returns the new id (from the Location header). */
    public String createUser(Map<String, Object> rep) {
        HttpResponse<String> res = request("POST", "/users", rep);
        String location = res.headers().firstValue("location").orElse("");
        String id = location.substring(location.lastIndexOf('/') + 1);
        if (id.isBlank()) {
            throw new KeycloakUnavailableError("user created but no Location header returned");
        }
        return id;
    }

    /** PUT /users/{id} is a full replace - pass a merged representation. */
    public void updateUser(String id, Map<String, Object> rep) {
        request("PUT", "/users/" + encode(id), rep);
    }

    /** Rollback-only (half-created accounts); FR-04 has no delete feature. */
    public void deleteUser(String id) {
        request("DELETE", "/users/" + encode(id), null);
    }

    public void resetPassword(String id, String value, boolean temporary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "password");
        body.put("value", value);
        body.put("temporary", temporary);
        request("PUT", "/users/" + encode(id) + "/reset-password", body);
    }

    /**
     * Revoke refresh tokens + SSO session (account-lifecycle.md Story 3/4,
     * lớp 1). Best-effort from the caller's point of view - it does not
     * revoke the access token already in a client's hands, which is why the
     * filter-based lớp 2 (mục 4.5) is the actual "chấm dứt phiên ngay"
     * mechanism.
     */
    public void logoutUser(String id) {
        request("POST", "/users/" + encode(id) + "/logout", null);
    }

    public KcRole getRealmRole(String name) {
        KcRole cached = roleCache.get(name);
        if (cached != null) {
            return cached;
        }
        HttpResponse<String> res = request("GET", "/roles/" + encode(name), null);
        KcRole role = readJson(res.body(), new TypeReference<>() {
        });
        roleCache.put(name, role);
        return role;
    }

    /** Users holding one realm role (brief) - lets listing avoid an N+1. */
    public List<Map<String, Object>> listRoleUsers(String name, int max) {
        HttpResponse<String> res =
                request("GET", "/roles/" + encode(name) + "/users?briefRepresentation=true&max=" + max, null);
        return readJson(res.body(), new TypeReference<>() {
        });
    }

    public List<KcRole> listUserRealmRoles(String id) {
        HttpResponse<String> res = request("GET", "/users/" + encode(id) + "/role-mappings/realm", null);
        return readJson(res.body(), new TypeReference<>() {
        });
    }

    public void addRealmRoles(String id, List<KcRole> roles) {
        request("POST", "/users/" + encode(id) + "/role-mappings/realm", roles);
    }

    public void removeRealmRoles(String id, List<KcRole> roles) {
        request("DELETE", "/users/" + encode(id) + "/role-mappings/realm", roles);
    }

    /**
     * FR-05: verify a user's current password via the password grant (the
     * {@code backend} client has directAccessGrantsEnabled for exactly this,
     * plus local testing - don't disable it). Returned tokens are discarded.
     *
     * Only a definitive Keycloak verdict may return {@code INVALID} -
     * transport failures throw, so an outage can never read as "wrong
     * password".
     */
    public PasswordVerification verifyPassword(String username, String password) {
        HttpResponse<String> res = postForm(
                tokenUrl(),
                Map.of(
                        "grant_type", "password",
                        "client_id", properties.clientId(),
                        "client_secret", properties.clientSecret(),
                        "username", username,
                        "password", password));
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
            // Discard the issued tokens; this was only a credentials check.
            return PasswordVerification.OK;
        }
        String message = errorMessage(res.body());
        if (message.contains("Invalid user credentials")) {
            return PasswordVerification.INVALID;
        }
        if (message.contains("Account is not fully set up")) {
            // e.g. a pending UPDATE_PASSWORD required action after an FR-06
            // temporary reset - the password may be correct; not a mismatch.
            return PasswordVerification.NOT_FULLY_SET_UP;
        }
        if (message.contains("Account disabled")) {
            return PasswordVerification.DISABLED;
        }
        // Unexpected grant failure (misconfigured client, etc.) - fail closed.
        log.error("password grant failed: {} {}", res.statusCode(), message);
        throw new KeycloakUnavailableError("password grant failed: " + message);
    }

    private HttpResponse<String> postForm(String url, Map<String, String> form) {
        String encoded = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encoded))
                    .build();
            return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KeycloakUnavailableError("Keycloak token endpoint unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakUnavailableError("Keycloak token endpoint request interrupted: " + e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Keycloak admin API request body", e);
        }
    }

    private static <T> T readJson(String body, TypeReference<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (Exception e) {
            throw new KeycloakUnavailableError("Failed to parse Keycloak admin API response: " + e.getMessage());
        }
    }
}
