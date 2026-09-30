package com.aismartstore.backend.search;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.search.dto.SearchRequest;
import com.aismartstore.backend.search.dto.SearchResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §3/Task 7 (FR-22) - full-text search
 * across the caller's own scope (own documents + documents shared to a group
 * they belong to). Reuses {@code PERM_document.read} rather than a new
 * permission key: a search result is, functionally, a filtered view over the
 * same documents that permission already gates (rbac-group-scope.md's
 * {@code document.read} is granted to R1/R2, not R3 - matching {@code
 * DocumentsController}).
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §3: {@code SearchService} is now
     * the narrow {@code lexicalSearch()}-only contract for {@code
     * search-semantic} - the {@code /search} HTTP route depends on the
     * concrete {@link SearchServiceImpl} directly instead, since {@code
     * search()} is deliberately not part of that interface's surface.
     */
    private final SearchServiceImpl searchService;

    public SearchController(SearchServiceImpl searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public SearchResponse search(@Valid @RequestBody SearchRequest request, @CurrentUser KeycloakUser user) {
        return searchService.search(request, user);
    }
}
