package com.aismartstore.backend.search;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.search.dto.SemanticSearchRequest;
import com.aismartstore.backend.search.dto.SemanticSearchResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-search-semantic T16 (FR-24): {@code POST /search/semantic}. Same permission as {@code
 * SearchController} ({@code PERM_document.read}, Q-4). Answers 404 when {@code app.semantic.enabled}
 * is off (rollout flag, search-semantic.md §9).
 */
@RestController
@RequestMapping("/search/semantic")
public class SemanticSearchController {

    private final SemanticSearchService service;
    private final SemanticProperties properties;

    public SemanticSearchController(SemanticSearchService service, SemanticProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public SemanticSearchResponse search(@Valid @RequestBody SemanticSearchRequest request, @CurrentUser KeycloakUser user) {
        if (!properties.enabled()) {
            throw new NotFoundException("Không tìm thấy");
        }
        return service.search(request, user);
    }
}
