package com.aismartstore.backend.groups;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.ForbiddenException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.common.UnprocessableEntityException;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.groups.dto.AddGroupMemberDto;
import com.aismartstore.backend.groups.dto.BulkShareDto;
import com.aismartstore.backend.groups.dto.BulkShareResultItem;
import com.aismartstore.backend.groups.dto.ChangeGroupMemberRoleDto;
import com.aismartstore.backend.groups.dto.CreateGroupDto;
import com.aismartstore.backend.groups.dto.ShareDocumentDto;
import com.aismartstore.backend.groups.dto.UpdateGroupDto;
import com.aismartstore.backend.notifications.NotificationService;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * rbac-group-scope.md §3.2/§4.3 (Story 5 nền tảng): groups, membership and
 * document shares - the write side that {@link BusinessDataScope}'s read
 * side depends on. Every endpoint here is {@code CAN_BO}/{@code LANH_DAO}
 * only (see {@link GroupsController}/{@link DocumentSharesController}) - R3
 * is deliberately never granted, matching NFR-04 (admins manage identities,
 * never business content or its access grants).
 */
@Service
public class GroupsService {

    /** Story 6 AC-1-AC-3: hard-coded caps (chốt với khách hàng ở MVP - không đọc từ system-config). */
    static final int MAX_MEMBERS_PER_GROUP = 200;

    static final int MAX_GROUPS_PER_USER = 200;

    static final int MAX_GROUPS_SYSTEM = 1000;

    /** group-management-sharing-02-document-sharing.md §4.1 step 2 (Story 1): bulk-share-only cap. */
    static final int MAX_GROUPS_PER_DOCUMENT_BULK = 50;

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final DocumentGroupShareRepository documentGroupShareRepository;
    private final DocumentScopeGuard documentScopeGuard;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final NotificationService notificationService;
    private final OutboxEventRepository outboxEventRepository;
    private final KeycloakAdminService kc;

    /**
     * Self-reference used only to call {@link #shareDocumentBulkItem}
     * through the Spring AOP proxy instead of {@code this.shareDocumentBulkItem(...)}
     * - same rationale/pattern as {@code SystemConfigService#self}: a plain
     * {@code this} call is a same-class self-invocation, which silently
     * bypasses the proxy and therefore that method's own {@code
     * @Transactional} (group-management-sharing-02-document-sharing.md §7
     * rủi ro 2 - each bulk-share item must get its own transaction, not
     * share one with the rest of the request). Defaults to {@code this} so
     * {@code GroupsServiceTest} (constructs this class directly, no Spring
     * context/proxy) keeps working unchanged.
     */
    private GroupsService self;

    public GroupsService(
            GroupRepository groupRepository,
            GroupMemberRepository groupMemberRepository,
            DocumentGroupShareRepository documentGroupShareRepository,
            DocumentScopeGuard documentScopeGuard,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            NotificationService notificationService,
            OutboxEventRepository outboxEventRepository,
            KeycloakAdminService kc) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.documentGroupShareRepository = documentGroupShareRepository;
        this.documentScopeGuard = documentScopeGuard;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.notificationService = notificationService;
        this.outboxEventRepository = outboxEventRepository;
        this.kc = kc;
        this.self = this;
    }

    @Autowired
    void setSelf(@Lazy GroupsService self) {
        this.self = self;
    }

    /** Response shape for {@code GET /documents/{id}/shares} (rbac-group-scope.md §3.2). */
    public record ShareView(UUID groupId, String groupName, String sharedBySub, OffsetDateTime sharedAt) {
    }

    /**
     * Response shape for {@code GET /groups/{id}/documents}
     * (group-management-sharing-02-document-sharing.md §3.2). {@code
     * uploaderUsername}/{@code uploaderDisplayName} and {@code
     * sharedByUsername}/{@code sharedByDisplayName} are the same
     * best-effort Keycloak join as {@link GroupMemberView} (null if
     * unreachable or the account was deleted there) - the frontend falls
     * back to the raw sub in that case.
     */
    public record GroupDocumentView(
            UUID documentId,
            String title,
            String uploaderSub,
            String uploaderUsername,
            String uploaderDisplayName,
            String sharedBySub,
            String sharedByUsername,
            String sharedByDisplayName,
            OffsetDateTime sharedAt) {
    }

    /** Best-effort Keycloak identity lookup, shared by {@link #toMemberView} and {@link #listGroupDocuments}. */
    private record UserIdentity(String username, String displayName) {
        static final UserIdentity UNKNOWN = new UserIdentity(null, null);
    }

    /**
     * Response shape for {@code GET /groups/{id}/members} - {@link GroupMember}
     * plus the Keycloak-only {@code username}/{@code displayName} (same join
     * {@link com.aismartstore.backend.userdirectory.UserDirectoryService} does),
     * so the UI can show a name instead of a raw {@code sub} (UUID). {@code
     * username}/{@code displayName} are {@code null} only if Keycloak is
     * unreachable or the account was deleted there - the frontend falls back
     * to {@code userSub} in that case.
     */
    public record GroupMemberView(
            String userSub, String username, String displayName, String roleInGroup, OffsetDateTime joinedAt) {
    }

    /** Thrown only inside {@link #shareDocumentBulkItem} to let a unique-violation race unwind that item's own transaction cleanly (never seen outside {@link #shareDocumentsBulk}). */
    private static final class AlreadySharedRaceException extends RuntimeException {
    }

    @Transactional
    public Group create(KeycloakUser user, CreateGroupDto dto) {
        if (groupRepository.countByDeletedAtIsNull() >= MAX_GROUPS_SYSTEM) {
            throw groupLimitExceeded("Đã đạt giới hạn tối đa " + MAX_GROUPS_SYSTEM + " nhóm trong hệ thống");
        }
        if (groupMemberRepository.countByUserSub(user.sub()) >= MAX_GROUPS_PER_USER) {
            throw groupLimitExceeded("Bạn đã đạt giới hạn tối đa " + MAX_GROUPS_PER_USER + " nhóm");
        }

        OffsetDateTime now = OffsetDateTime.now();

        Group group = new Group();
        group.setName(dto.name());
        group.setDescription(dto.description());
        group.setOwnerSub(user.sub());
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        try {
            // saveAndFlush (not save): id is a Hibernate-in-memory-generated
            // UUID (@UuidGenerator), so Hibernate has no reason to flush
            // immediately and would otherwise defer the insert past this
            // try/catch, surfacing the uq_group_name_active violation
            // uncaught, later, at whatever point the persistence context
            // happens to flush (typically transaction commit, as an opaque
            // 500 instead of this 409) - same footgun as #update() below.
            groupRepository.saveAndFlush(group);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException(duplicateGroupNameMessage(dto.name()));
            }
            throw e;
        }

        GroupMember membership = new GroupMember();
        membership.setGroupId(group.getId());
        membership.setUserSub(user.sub());
        membership.setRoleInGroup(GroupRole.OWNER);
        membership.setJoinedAt(now);
        groupMemberRepository.save(membership);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "group.create", "group", group.getId().toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("name", group.getName())));
        return group;
    }

    /** Only groups {@code user} is a member of (rbac-group-scope.md §3.2). */
    public List<Group> listForCurrentUser(KeycloakUser user) {
        List<UUID> groupIds = groupMemberRepository.findGroupIdsByUserSub(user.sub());
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return groupRepository.findAllByIdInAndDeletedAtIsNull(groupIds);
    }

    public List<GroupMemberView> listMembers(KeycloakUser user, UUID groupId) {
        requireGroupExists(groupId);
        if (!groupMemberRepository.existsByGroupIdAndUserSub(groupId, user.sub())) {
            throw new ForbiddenException("Not a member of this group");
        }
        return groupMemberRepository.findByGroupIdOrderByJoinedAtAsc(groupId).stream()
                .map(this::toMemberView)
                .toList();
    }

    /** Best-effort Keycloak join, same resilience as {@code UserDirectoryService#roleOf}: a lookup failure (Keycloak down, account deleted) yields {@code null} name fields rather than failing the whole list. */
    private GroupMemberView toMemberView(GroupMember member) {
        UserIdentity identity = resolveIdentity(member.getUserSub());
        return new GroupMemberView(
                member.getUserSub(), identity.username(), identity.displayName(), member.getRoleInGroup(),
                member.getJoinedAt());
    }

    /** See {@link UserIdentity}: {@code UNKNOWN} (all-null) on any lookup failure, never thrown. */
    private UserIdentity resolveIdentity(String sub) {
        try {
            Map<String, Object> rep = kc.getUser(sub);
            String username = (String) rep.get("username");
            return new UserIdentity(username, displayName(rep, username));
        } catch (RuntimeException e) {
            return UserIdentity.UNKNOWN;
        }
    }

    private static String displayName(Map<String, Object> user, String fallback) {
        String firstName = (String) user.get("firstName");
        String lastName = (String) user.get("lastName");
        String combined = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        return combined.isEmpty() ? fallback : combined;
    }

    /**
     * Story 1: rename/re-describe a group - owner only, unique name (case
     * insensitive, among active groups) enforced by the partial unique index
     * added in {@code V23__group_lifecycle.sql}.
     */
    @Transactional
    public Group update(KeycloakUser user, UUID groupId, UpdateGroupDto dto) {
        Group group = requireActiveGroup(groupId);
        requireOwner(user, groupId);

        String oldName = group.getName();
        if (dto.name() != null) {
            group.setName(dto.name());
        }
        if (dto.description() != null) {
            group.setDescription(dto.description());
        }
        group.setUpdatedAt(OffsetDateTime.now());
        try {
            // saveAndFlush (not save): id is a client-assigned UUID, so
            // Hibernate has no reason to flush immediately and would
            // otherwise defer the insert past this try/catch, surfacing the
            // uq_group_name_active violation uncaught, later, at whatever
            // point the persistence context happens to flush.
            groupRepository.saveAndFlush(group);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException(duplicateGroupNameMessage(group.getName()));
            }
            throw e;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (dto.name() != null && !dto.name().equals(oldName)) {
            metadata.put("oldName", oldName);
            metadata.put("newName", dto.name());
        }
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "group.update", "group", groupId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(), metadata));
        return group;
    }

    /**
     * Story 2: owner changes a member's role. Demoting the last owner is
     * blocked ({@link #requireNotLastOwner}) - promoting a member to owner
     * never is, and never counts against the membership caps (Story 6 §4.6:
     * caps only apply when a member is newly added).
     */
    @Transactional
    public void changeMemberRole(KeycloakUser user, UUID groupId, String targetUserSub, ChangeGroupMemberRoleDto dto) {
        requireActiveGroup(groupId);
        requireOwner(user, groupId);

        if (GroupRole.MEMBER.equals(dto.roleInGroup())) {
            requireNotLastOwner(groupId, targetUserSub);
        }

        GroupMember membership = groupMemberRepository
                .findByGroupIdAndUserSub(groupId, targetUserSub)
                .orElseThrow(() -> new NotFoundException("User is not a member of this group"));
        membership.setRoleInGroup(dto.roleInGroup());
        groupMemberRepository.save(membership);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "group.change_member_role", "group", groupId.toString(),
                "success", auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("userSub", targetUserSub, "roleInGroup", dto.roleInGroup())));
    }

    /**
     * Story 3: leave a group - the last owner cannot ({@link
     * #requireNotLastOwner}). Does not touch {@code document_group_share}
     * (Quyết định 4 SA, AC-3): shares the leaving user created stay visible to
     * the remaining members.
     */
    @Transactional
    public void leaveGroup(KeycloakUser user, UUID groupId) {
        requireActiveGroup(groupId);
        requireNotLastOwner(groupId, user.sub());

        int affected = groupMemberRepository.deleteByGroupIdAndUserSub(groupId, user.sub());
        if (affected == 0) {
            throw new NotFoundException("User is not a member of this group");
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "group.leave", "group", groupId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(), null));

        // group-management-sharing-02-document-sharing.md §4.4 point 3/4: recipient is the leaving user themselves.
        notificationService.notifyAsync(
                List.of(user.sub()), "group.member_removed", Map.of("groupId", groupId.toString(), "userSub", user.sub()));
    }

    /**
     * Story 4: soft delete - any owner may delete (not "every owner must
     * agree"). Does not cascade-delete {@code group_member}/
     * {@code document_group_share} (Quyết định 5 SA); see {@link
     * GroupMemberRepository#findGroupIdsByUserSub} (Task 6) for the read-side
     * fix that keeps a deleted group's shares from still granting access.
     */
    @Transactional
    public void deleteGroup(KeycloakUser user, UUID groupId) {
        Group group = requireActiveGroup(groupId);
        requireOwner(user, groupId);

        group.setDeletedAt(OffsetDateTime.now());
        groupRepository.save(group);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "group.delete", "group", groupId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(), null));
    }

    @Transactional
    public void addMember(KeycloakUser user, UUID groupId, AddGroupMemberDto dto) {
        requireGroupExists(groupId);
        requireOwner(user, groupId);

        if (groupMemberRepository.countByGroupId(groupId) >= MAX_MEMBERS_PER_GROUP) {
            throw groupLimitExceeded("Nhóm đã đạt giới hạn tối đa " + MAX_MEMBERS_PER_GROUP + " thành viên");
        }
        if (groupMemberRepository.countByUserSub(dto.userSub()) >= MAX_GROUPS_PER_USER) {
            throw groupLimitExceeded("Người dùng đã đạt giới hạn tối đa " + MAX_GROUPS_PER_USER + " nhóm");
        }

        GroupMember membership = new GroupMember();
        membership.setGroupId(groupId);
        membership.setUserSub(dto.userSub());
        membership.setRoleInGroup(dto.roleInGroup());
        membership.setJoinedAt(OffsetDateTime.now());
        try {
            groupMemberRepository.save(membership);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("User is already a member of this group");
            }
            throw e;
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "group.add_member", "group", groupId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("userSub", dto.userSub(), "roleInGroup", dto.roleInGroup())));

        notificationService.notifyAsync(
                List.of(dto.userSub()), "group.member_added", Map.of("groupId", groupId.toString(), "userSub", dto.userSub()));
    }

    @Transactional
    public void removeMember(KeycloakUser user, UUID groupId, String userSub) {
        requireGroupExists(groupId);
        requireOwner(user, groupId);

        // group-management-sharing-01-group-lifecycle.md Task 4 (vá hành vi
        // thiếu): trước đây gỡ owner cuối cùng không bị chặn.
        requireNotLastOwner(groupId, userSub);

        int affected = groupMemberRepository.deleteByGroupIdAndUserSub(groupId, userSub);
        if (affected == 0) {
            throw new NotFoundException("User is not a member of this group");
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "group.remove_member", "group", groupId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("userSub", userSub)));

        notificationService.notifyAsync(
                List.of(userSub), "group.member_removed", Map.of("groupId", groupId.toString(), "userSub", userSub));
    }

    /**
     * A document may be shared to a group iff {@code user} can currently see
     * it (rbac-group-scope.md §4.3 step 3) - reusing {@link
     * DocumentScopeGuard} means the visibility rule can never drift from the
     * one enforced on the read side.
     */
    @Transactional
    public void shareDocument(KeycloakUser user, UUID documentId, ShareDocumentDto dto) {
        Document document = documentScopeGuard.requireVisible(user, documentId);
        requireGroupExists(dto.groupId());

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(document.getId());
        share.setGroupId(dto.groupId());
        share.setSharedBySub(user.sub());
        share.setSharedAt(OffsetDateTime.now());
        try {
            documentGroupShareRepository.save(share);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("Document is already shared to this group");
            }
            throw e;
        }

        AclChangeOutbox.write(outboxEventRepository, documentId, dto.groupId(), AclChangeOutbox.ACTION_SHARED, user.sub());

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "document.share", "document", documentId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("groupId", dto.groupId().toString())));
    }

    /**
     * group-management-sharing-02-document-sharing.md §4.3 (Task 6 - rà soát
     * bảo mật): before this fix, anyone who could see the document could
     * unshare it from any group. Now restricted to the group owner, the
     * user who created this specific share, or the document's uploader.
     */
    @Transactional
    public void unshareDocument(KeycloakUser user, UUID documentId, UUID groupId) {
        Document document = documentScopeGuard.requireVisible(user, documentId);
        DocumentGroupShare share = documentGroupShareRepository
                .findByDocumentIdAndGroupId(documentId, groupId)
                .orElseThrow(() -> new NotFoundException("Document is not shared to this group"));

        boolean isGroupOwner = groupMemberRepository
                .findByGroupIdAndUserSub(groupId, user.sub())
                .map(m -> GroupRole.OWNER.equals(m.getRoleInGroup()))
                .orElse(false);
        boolean isSharer = share.getSharedBySub().equals(user.sub());
        boolean isUploader = document.getUploaderSub().equals(user.sub());
        if (!isGroupOwner && !isSharer && !isUploader) {
            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(), user.sub(), "document.unshare", "document", documentId.toString(),
                    "failure", auditRequestContext.method(), auditRequestContext.path(), 403,
                    auditRequestContext.ip(), Map.of("groupId", groupId.toString(), "reason", "forbidden")));
            throw new ForbiddenException("Not allowed to unshare this document from this group");
        }

        documentGroupShareRepository.delete(share);

        AclChangeOutbox.write(outboxEventRepository, documentId, groupId, AclChangeOutbox.ACTION_UNSHARED, user.sub());

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "document.unshare", "document", documentId.toString(),
                "success", auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("groupId", groupId.toString())));
    }

    public List<ShareView> listShares(KeycloakUser user, UUID documentId) {
        documentScopeGuard.requireVisible(user, documentId);

        List<DocumentGroupShare> shares = documentGroupShareRepository.findByDocumentIdOrderBySharedAtAsc(documentId);
        if (shares.isEmpty()) {
            return List.of();
        }
        Map<UUID, Group> groupsById = groupRepository
                .findAllById(shares.stream().map(DocumentGroupShare::getGroupId).distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Group::getId, g -> g));

        return shares.stream()
                .map(s -> {
                    Group group = groupsById.get(s.getGroupId());
                    return new ShareView(
                            s.getGroupId(), group != null ? group.getName() : null, s.getSharedBySub(), s.getSharedAt());
                })
                .toList();
    }

    /**
     * group-management-sharing-02-document-sharing.md §4.1 (Story 1): {@code
     * POST /documents/shares} - one group, up to 100 documents, each
     * processed in its own transaction ({@link #shareDocumentBulkItem}, via
     * {@link #self} so it actually runs behind the Spring transaction
     * proxy) so one bad document never rolls back the others (§7 rủi ro 2).
     * The fan-out notification is sent exactly once per request, after every
     * item has been processed, for every document that ended up newly
     * shared.
     */
    public List<BulkShareResultItem> shareDocumentsBulk(KeycloakUser user, BulkShareDto dto) {
        requireGroupExists(dto.groupId());

        List<BulkShareResultItem> results = new ArrayList<>(dto.documentIds().size());
        List<UUID> sharedDocumentIds = new ArrayList<>();
        for (UUID documentId : dto.documentIds()) {
            BulkShareResultItem result;
            try {
                result = self.shareDocumentBulkItem(user, dto.groupId(), documentId);
            } catch (AlreadySharedRaceException e) {
                result = BulkShareResultItem.alreadyShared(documentId);
            }
            results.add(result);
            if ("shared".equals(result.status())) {
                sharedDocumentIds.add(documentId);
            }
        }

        if (!sharedDocumentIds.isEmpty()) {
            notificationService.notifyDocumentsSharedToGroup(dto.groupId(), sharedDocumentIds, user.sub());
        }
        return results;
    }

    /**
     * One item of {@link #shareDocumentsBulk} - its own transaction (called
     * only via {@link #self}, never directly). Not {@code private} so {@link
     * #self} can proxy it.
     */
    @Transactional
    BulkShareResultItem shareDocumentBulkItem(KeycloakUser user, UUID groupId, UUID documentId) {
        Document document;
        try {
            document = documentScopeGuard.requireVisible(user, documentId);
        } catch (NotFoundException e) {
            return BulkShareResultItem.rejected(documentId, "not_visible");
        }

        if (documentGroupShareRepository.countByDocumentId(documentId) >= MAX_GROUPS_PER_DOCUMENT_BULK) {
            return BulkShareResultItem.rejected(documentId, "group_limit_exceeded");
        }
        // Pre-check (in addition to the catch below): the common case never needs to provoke
        // and then catch a DataIntegrityViolationException, which - thrown from inside this same
        // @Transactional method by a nested repository call - would otherwise mark this item's
        // transaction rollback-only even though we want to keep processing it as a normal result.
        if (documentGroupShareRepository.existsByDocumentIdAndGroupId(documentId, groupId)) {
            return BulkShareResultItem.alreadyShared(documentId);
        }

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(document.getId());
        share.setGroupId(groupId);
        share.setSharedBySub(user.sub());
        share.setSharedAt(OffsetDateTime.now());
        try {
            documentGroupShareRepository.save(share);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                // A concurrent request won the race after the exists() check above - let this
                // propagate out so the transaction this method's own proxy started rolls back
                // for real, then shareDocumentsBulk translates it into an "already_shared" result.
                throw new AlreadySharedRaceException();
            }
            throw e;
        }

        AclChangeOutbox.write(outboxEventRepository, documentId, groupId, AclChangeOutbox.ACTION_SHARED, user.sub());

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "document.share", "document", documentId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("groupId", groupId.toString())));
        return BulkShareResultItem.shared(documentId);
    }

    /**
     * group-management-sharing-02-document-sharing.md §4.2 (Story 2): the
     * reverse of {@link #listShares} - every document currently shared to
     * one group, restricted to its own members (matches {@link
     * #listMembers}'s 403 rule).
     */
    public Page<GroupDocumentView> listGroupDocuments(KeycloakUser user, UUID groupId, Pageable pageable) {
        requireGroupExists(groupId);
        if (!groupMemberRepository.existsByGroupIdAndUserSub(groupId, user.sub())) {
            throw new ForbiddenException("Not a member of this group");
        }
        Page<Object[]> rows = documentGroupShareRepository.findGroupDocuments(groupId, pageable);

        // Resolve each distinct sub on this page once (not once per row/column):
        // findGroupDocuments has no join to a users table (uploaderSub/sharedBySub
        // are opaque Keycloak subs), same gap toMemberView fills for /members.
        Map<String, UserIdentity> identities = rows.getContent().stream()
                .flatMap(row -> java.util.stream.Stream.of((String) row[2], (String) row[3]))
                .distinct()
                .collect(java.util.stream.Collectors.toMap(sub -> sub, this::resolveIdentity));

        return rows.map(row -> {
            String uploaderSub = (String) row[2];
            String sharedBySub = (String) row[3];
            UserIdentity uploader = identities.getOrDefault(uploaderSub, UserIdentity.UNKNOWN);
            UserIdentity sharedBy = identities.getOrDefault(sharedBySub, UserIdentity.UNKNOWN);
            return new GroupDocumentView(
                    (UUID) row[0], (String) row[1],
                    uploaderSub, uploader.username(), uploader.displayName(),
                    sharedBySub, sharedBy.username(), sharedBy.displayName(),
                    (OffsetDateTime) row[4]);
        });
    }

    private void requireGroupExists(UUID groupId) {
        if (!groupRepository.existsByIdAndDeletedAtIsNull(groupId)) {
            throw new NotFoundException("Group not found");
        }
    }

    /** Like {@link #requireGroupExists} but returns the entity - callers that need to mutate it (update/delete). */
    private Group requireActiveGroup(UUID groupId) {
        return groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));
    }

    private void requireOwner(KeycloakUser user, UUID groupId) {
        boolean isOwner = groupMemberRepository
                .findByGroupIdAndUserSub(groupId, user.sub())
                .map(m -> GroupRole.OWNER.equals(m.getRoleInGroup()))
                .orElse(false);
        if (!isOwner) {
            throw new ForbiddenException("Only the group owner can manage its membership");
        }
    }

    /**
     * Bất biến "≥ 1 owner" dùng chung cho Story 2 (hạ vai trò), Story 3 (rời
     * nhóm) và {@link #removeMember} (Task 4): a no-op unless {@code userSub}
     * is currently the group's only owner.
     */
    private void requireNotLastOwner(UUID groupId, String userSub) {
        GroupMember member = groupMemberRepository
                .findByGroupIdAndUserSub(groupId, userSub)
                .orElseThrow(() -> new NotFoundException("User is not a member of this group"));
        if (!GroupRole.OWNER.equals(member.getRoleInGroup())) {
            return; // không phải owner, không cần kiểm tra
        }
        long ownerCount = groupMemberRepository.countByGroupIdAndRoleInGroup(groupId, GroupRole.OWNER);
        if (ownerCount <= 1) {
            throw new ConflictException("Cannot remove the last owner of the group");
        }
    }

    /**
     * {@code uq_group_name_active} violation (create or rename) - names the
     * conflicting value and tells the caller what to do about it, instead of
     * a generic "already exists", so the 409 is actionable on its own
     * without the caller having to already know the constraint is
     * case-insensitive among active groups.
     */
    private static String duplicateGroupNameMessage(String name) {
        return "Tên nhóm \"" + name + "\" đã được sử dụng, vui lòng đặt tên khác";
    }

    /** Story 6: {@code {code: "GROUP_LIMIT_EXCEEDED", message}} - reuses the existing 422-with-body shape (see ApiExceptionHandler). */
    private static UnprocessableEntityException groupLimitExceeded(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "GROUP_LIMIT_EXCEEDED");
        body.put("message", message);
        return new UnprocessableEntityException(body, message);
    }
}
