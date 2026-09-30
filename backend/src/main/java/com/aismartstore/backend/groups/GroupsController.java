package com.aismartstore.backend.groups;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.groups.dto.AddGroupMemberDto;
import com.aismartstore.backend.groups.dto.ChangeGroupMemberRoleDto;
import com.aismartstore.backend.groups.dto.CreateGroupDto;
import com.aismartstore.backend.groups.dto.UpdateGroupDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * rbac-group-scope.md §3.2: minimal group/membership API - enough for
 * scoping (Story 3/4) to work, full membership UI is out of scope.
 * {@code QUAN_TRI} is deliberately never granted (NFR-04 - see the class
 * javadoc on {@link GroupsService}).
 */
@RestController
@RequestMapping("/groups")
public class GroupsController {

    private static final int DEFAULT_DOCUMENTS_PAGE_SIZE = 50;
    private static final int MAX_DOCUMENTS_PAGE_SIZE = 50;

    private final GroupsService groupsService;

    public GroupsController(GroupsService groupsService) {
        this.groupsService = groupsService;
    }

    /** {@code GET /groups/{id}/documents} response shape (group-management-sharing-02-document-sharing.md §3.2) - see {@code AuditController.AuditPage} for the same "small explicit DTO, not Page directly" rationale. */
    public record GroupDocumentsPage(
            List<GroupsService.GroupDocumentView> content, long totalElements, int page, int size, int totalPages) {
        static GroupDocumentsPage from(Page<GroupsService.GroupDocumentView> page) {
            return new GroupDocumentsPage(
                    page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize(),
                    page.getTotalPages());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public Group create(@CurrentUser KeycloakUser user, @Valid @RequestBody CreateGroupDto dto) {
        return groupsService.create(user, dto);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public List<Group> list(@CurrentUser KeycloakUser user) {
        return groupsService.listForCurrentUser(user);
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public List<GroupsService.GroupMemberView> listMembers(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        return groupsService.listMembers(user, id);
    }

    /** Story 2 (group-management-sharing-02-document-sharing.md §3.2/§4.2). */
    @GetMapping("/{id}/documents")
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public GroupDocumentsPage listDocuments(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_DOCUMENTS_PAGE_SIZE) int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_DOCUMENTS_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize);
        return GroupDocumentsPage.from(groupsService.listGroupDocuments(user, id, pageable));
    }

    /** Story 1. */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public Group update(@CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody UpdateGroupDto dto) {
        return groupsService.update(user, id, dto);
    }

    /** Story 4: soft delete. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public void delete(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        groupsService.deleteGroup(user, id);
    }

    /** Story 3. */
    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public void leave(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        groupsService.leaveGroup(user, id);
    }

    /** Story 2. */
    @PatchMapping("/{id}/members/{userSub}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public void changeMemberRole(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @PathVariable String userSub,
            @Valid @RequestBody ChangeGroupMemberRoleDto dto) {
        groupsService.changeMemberRole(user, id, userSub, dto);
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public void addMember(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody AddGroupMemberDto dto) {
        groupsService.addMember(user, id, dto);
    }

    @DeleteMapping("/{id}/members/{userSub}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_group.manage')")
    public void removeMember(@CurrentUser KeycloakUser user, @PathVariable UUID id, @PathVariable String userSub) {
        groupsService.removeMember(user, id, userSub);
    }
}
