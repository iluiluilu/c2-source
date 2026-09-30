package com.aismartstore.backend.userdirectory;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * group-management-sharing-01-group-lifecycle.md §3.2 (Story 5): a minimal
 * user picker for the "add member" flow. {@code hasAnyRole(...)} - not
 * {@code hasAuthority('PERM_...')} - deliberately: R3 (quan-tri) must never
 * be grantable here even through an R3 role_permission edit (NFR-04), unlike
 * {@code group.manage}/{@code document.share} which R3 could otherwise grant
 * itself via {@code RbacService}.
 */
@RestController
@RequestMapping("/users")
public class UserDirectoryController {

    private final UserDirectoryService userDirectoryService;

    public UserDirectoryController(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    @GetMapping("/directory")
    @PreAuthorize("hasAnyRole('CAN_BO','LANH_DAO')")
    public List<UserDirectoryEntry> search(@CurrentUser KeycloakUser user, @RequestParam String q) {
        return userDirectoryService.search(user, q);
    }
}
