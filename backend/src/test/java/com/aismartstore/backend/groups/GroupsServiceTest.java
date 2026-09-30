package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.ForbiddenException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.UnprocessableEntityException;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.groups.dto.AddGroupMemberDto;
import com.aismartstore.backend.groups.dto.BulkShareDto;
import com.aismartstore.backend.groups.dto.BulkShareResultItem;
import com.aismartstore.backend.groups.dto.ChangeGroupMemberRoleDto;
import com.aismartstore.backend.groups.dto.CreateGroupDto;
import com.aismartstore.backend.notifications.NotificationService;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * Unit coverage for group-management-sharing-01-group-lifecycle.md §5
 * ("requireNotLastOwner", "Giới hạn số lượng"). See {@link
 * BusinessDataScopeIT}/{@code GroupLifecycleIT} for the persistence-backed
 * integration coverage (partial unique index, real cascades, etc).
 */
class GroupsServiceTest {

    private GroupRepository groupRepository;
    private GroupMemberRepository groupMemberRepository;
    private DocumentGroupShareRepository documentGroupShareRepository;
    private DocumentScopeGuard documentScopeGuard;
    private AuditService auditService;
    private AuditRequestContext auditRequestContext;
    private NotificationService notificationService;
    private OutboxEventRepository outboxEventRepository;
    private KeycloakAdminService keycloakAdminService;
    private GroupsService service;

    private final KeycloakUser owner = new KeycloakUser("owner-sub", "owner", List.of("can-bo"));
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        documentGroupShareRepository = mock(DocumentGroupShareRepository.class);
        documentScopeGuard = mock(DocumentScopeGuard.class);
        auditService = mock(AuditService.class);
        auditRequestContext = mock(AuditRequestContext.class);
        notificationService = mock(NotificationService.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        keycloakAdminService = mock(KeycloakAdminService.class);
        service = new GroupsService(
                groupRepository, groupMemberRepository, documentGroupShareRepository, documentScopeGuard,
                auditService, auditRequestContext, notificationService, outboxEventRepository, keycloakAdminService);

        when(groupRepository.findByIdAndDeletedAtIsNull(groupId)).thenReturn(Optional.of(activeGroup()));
        when(groupRepository.existsByIdAndDeletedAtIsNull(groupId)).thenReturn(true);
        Answer<Group> assignIdIfMissing = inv -> {
            Group saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, UUID.randomUUID());
            }
            return saved;
        };
        when(groupRepository.save(any(Group.class))).thenAnswer(assignIdIfMissing);
        // create() uses saveAndFlush (not save) so a uq_group_name_active violation surfaces
        // inside its own try/catch instead of being deferred past it - see GroupsService#create.
        when(groupRepository.saveAndFlush(any(Group.class))).thenAnswer(assignIdIfMissing);
        when(groupMemberRepository.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Group activeGroup() {
        Group group = new Group();
        setId(group, groupId);
        group.setName("Nhóm test");
        group.setOwnerSub(owner.sub());
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        return group;
    }

    /** {@code Group.id} has no public setter (DB-generated) - reflection keeps fixtures realistic. */
    private static void setId(Group group, UUID id) {
        try {
            var field = Group.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(group, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Same rationale as {@link #setId(Group, UUID)} - {@code Document.id} is also DB-generated only. */
    private static Document document(UUID id, String uploaderSub) {
        Document document = new Document();
        try {
            Field field = Document.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        document.setUploaderSub(uploaderSub);
        return document;
    }

    private GroupMember member(String userSub, String role) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserSub(userSub);
        m.setRoleInGroup(role);
        m.setJoinedAt(OffsetDateTime.now());
        return m;
    }

    private void ownerIs(String userSub) {
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, userSub))
                .thenReturn(Optional.of(member(userSub, GroupRole.OWNER)));
    }

    // ---- requireNotLastOwner (via changeMemberRole/leaveGroup/removeMember) ----

    @Test
    void demotingTheOnlyOwnerToMemberIsRejected() {
        ownerIs(owner.sub());
        when(groupMemberRepository.countByGroupIdAndRoleInGroup(groupId, GroupRole.OWNER)).thenReturn(1L);

        assertThatThrownBy(() -> service.changeMemberRole(
                        owner, groupId, owner.sub(), new ChangeGroupMemberRoleDto(GroupRole.MEMBER)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void demotingOneOfTwoOwnersToMemberSucceeds() {
        ownerIs(owner.sub());
        when(groupMemberRepository.countByGroupIdAndRoleInGroup(groupId, GroupRole.OWNER)).thenReturn(2L);
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, "other-owner"))
                .thenReturn(Optional.of(member("other-owner", GroupRole.OWNER)));

        service.changeMemberRole(owner, groupId, "other-owner", new ChangeGroupMemberRoleDto(GroupRole.MEMBER));

        verify(groupMemberRepository).save(argThatRole(GroupRole.MEMBER));
    }

    @Test
    void promotingAMemberToOwnerNeverConsultsTheLastOwnerGuard() {
        ownerIs(owner.sub());
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, "plain-member"))
                .thenReturn(Optional.of(member("plain-member", GroupRole.MEMBER)));

        service.changeMemberRole(owner, groupId, "plain-member", new ChangeGroupMemberRoleDto(GroupRole.OWNER));

        verify(groupMemberRepository, never()).countByGroupIdAndRoleInGroup(any(), anyString());
    }

    @Test
    void theOnlyOwnerCannotLeave() {
        ownerIs(owner.sub());
        when(groupMemberRepository.countByGroupIdAndRoleInGroup(groupId, GroupRole.OWNER)).thenReturn(1L);

        assertThatThrownBy(() -> service.leaveGroup(owner, groupId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aNonOwnerMemberCanAlwaysLeave() {
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, "plain-member"))
                .thenReturn(Optional.of(member("plain-member", GroupRole.MEMBER)));
        when(groupMemberRepository.deleteByGroupIdAndUserSub(groupId, "plain-member")).thenReturn(1);
        KeycloakUser plainMember = new KeycloakUser("plain-member", "plain", List.of("can-bo"));

        service.leaveGroup(plainMember, groupId);

        verify(groupMemberRepository).deleteByGroupIdAndUserSub(groupId, "plain-member");
        // Not an owner - the guard must never even count owners.
        verify(groupMemberRepository, never()).countByGroupIdAndRoleInGroup(any(), anyString());
    }

    @Test
    void removingTheLastOwnerThroughRemoveMemberIsNowRejected() {
        ownerIs(owner.sub()); // acting owner AND target, for simplicity
        when(groupMemberRepository.countByGroupIdAndRoleInGroup(groupId, GroupRole.OWNER)).thenReturn(1L);

        assertThatThrownBy(() -> service.removeMember(owner, groupId, owner.sub()))
                .isInstanceOf(ConflictException.class);
        verify(groupMemberRepository, never()).deleteByGroupIdAndUserSub(any(), anyString());
    }

    @Test
    void removingAnOrdinaryMemberStillWorks() {
        ownerIs(owner.sub());
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, "plain-member"))
                .thenReturn(Optional.of(member("plain-member", GroupRole.MEMBER)));
        when(groupMemberRepository.deleteByGroupIdAndUserSub(groupId, "plain-member")).thenReturn(1);

        service.removeMember(owner, groupId, "plain-member");

        verify(groupMemberRepository).deleteByGroupIdAndUserSub(groupId, "plain-member");
    }

    @Test
    void nonOwnerCannotChangeRoles() {
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, owner.sub()))
                .thenReturn(Optional.of(member(owner.sub(), GroupRole.MEMBER)));

        assertThatThrownBy(() -> service.changeMemberRole(
                        owner, groupId, "someone", new ChangeGroupMemberRoleDto(GroupRole.MEMBER)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- Story 6: giới hạn số lượng ----------------------------------------

    @Test
    void creatingAGroupAtTheSystemCapIsRejected() {
        when(groupRepository.countByDeletedAtIsNull()).thenReturn((long) GroupsService.MAX_GROUPS_SYSTEM);

        assertThatThrownBy(() -> service.create(owner, new CreateGroupDto("Nhóm mới", null)))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void creatingAGroupJustUnderTheSystemCapSucceeds() {
        when(groupRepository.countByDeletedAtIsNull()).thenReturn((long) GroupsService.MAX_GROUPS_SYSTEM - 1);
        when(groupMemberRepository.countByUserSub(owner.sub())).thenReturn(0L);

        Group created = service.create(owner, new CreateGroupDto("Nhóm mới", null));

        assertThat(created.getName()).isEqualTo("Nhóm mới");
    }

    @Test
    void creatingAGroupWhenTheCreatorIsAlreadyAtTheirPerUserCapIsRejected() {
        when(groupRepository.countByDeletedAtIsNull()).thenReturn(0L);
        when(groupMemberRepository.countByUserSub(owner.sub())).thenReturn((long) GroupsService.MAX_GROUPS_PER_USER);

        assertThatThrownBy(() -> service.create(owner, new CreateGroupDto("Nhóm mới", null)))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void addingAMemberAtTheGroupMemberCapIsRejected() {
        ownerIs(owner.sub());
        when(groupMemberRepository.countByGroupId(groupId)).thenReturn((long) GroupsService.MAX_MEMBERS_PER_GROUP);

        assertThatThrownBy(() -> service.addMember(owner, groupId, new AddGroupMemberDto("new-user", GroupRole.MEMBER)))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void addingAMemberJustUnderTheGroupMemberCapSucceeds() {
        ownerIs(owner.sub());
        when(groupMemberRepository.countByGroupId(groupId))
                .thenReturn((long) GroupsService.MAX_MEMBERS_PER_GROUP - 1);
        when(groupMemberRepository.countByUserSub("new-user")).thenReturn(0L);

        service.addMember(owner, groupId, new AddGroupMemberDto("new-user", GroupRole.MEMBER));

        verify(groupMemberRepository).save(any(GroupMember.class));
    }

    @Test
    void addingAMemberAlreadyAtTheirPerUserGroupCapIsRejected() {
        ownerIs(owner.sub());
        when(groupMemberRepository.countByGroupId(groupId)).thenReturn(0L);
        when(groupMemberRepository.countByUserSub("new-user")).thenReturn((long) GroupsService.MAX_GROUPS_PER_USER);

        assertThatThrownBy(() -> service.addMember(owner, groupId, new AddGroupMemberDto("new-user", GroupRole.MEMBER)))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    // ---- Case biên: nhóm đã xoá -------------------------------------------

    @Test
    void patchingADeletedGroupIs404() {
        when(groupRepository.findByIdAndDeletedAtIsNull(groupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(owner, groupId, new com.aismartstore.backend.groups.dto.UpdateGroupDto("x", null)))
                .isInstanceOf(NotFoundException.class);
    }

    private static GroupMember argThatRole(String role) {
        return org.mockito.ArgumentMatchers.argThat(m -> m != null && role.equals(m.getRoleInGroup()));
    }

    // ---- group-management-sharing-02-document-sharing.md §4.3 (Task 6): unshareDocument permission ----

    @Test
    void groupOwnerCanUnshareASharedCreatedBySomeoneElse() {
        UUID documentId = UUID.randomUUID();
        when(documentScopeGuard.requireVisible(owner, documentId)).thenReturn(document(documentId, "uploader-sub"));
        DocumentGroupShare share = share(documentId, groupId, "someone-else");
        when(documentGroupShareRepository.findByDocumentIdAndGroupId(documentId, groupId))
                .thenReturn(Optional.of(share));
        ownerIs(owner.sub());

        service.unshareDocument(owner, documentId, groupId);

        verify(documentGroupShareRepository).delete(share);
    }

    // ---- search-fulltext-04-dong-bo-chi-muc.md Task 4 - document.acl.changed outbox ----

    @Test
    void shareDocumentWritesAnAclChangedOutboxEvent() {
        UUID documentId = UUID.randomUUID();
        when(documentScopeGuard.requireVisible(owner, documentId)).thenReturn(document(documentId, owner.sub()));
        when(groupRepository.existsByIdAndDeletedAtIsNull(groupId)).thenReturn(true);

        service.shareDocument(owner, documentId, new com.aismartstore.backend.groups.dto.ShareDocumentDto(groupId));

        org.mockito.ArgumentCaptor<com.aismartstore.backend.outbox.OutboxEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.aismartstore.backend.outbox.OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("document.acl.changed");
        assertThat(captor.getValue().getPayload().get("documentId")).isEqualTo(documentId.toString());
        assertThat(captor.getValue().getPayload().get("groupId")).isEqualTo(groupId.toString());
        assertThat(captor.getValue().getPayload().get("action")).isEqualTo("shared");
    }

    @Test
    void unshareDocumentWritesAnAclChangedOutboxEvent() {
        UUID documentId = UUID.randomUUID();
        when(documentScopeGuard.requireVisible(owner, documentId)).thenReturn(document(documentId, "uploader-sub"));
        DocumentGroupShare share = share(documentId, groupId, "someone-else");
        when(documentGroupShareRepository.findByDocumentIdAndGroupId(documentId, groupId))
                .thenReturn(Optional.of(share));
        ownerIs(owner.sub());

        service.unshareDocument(owner, documentId, groupId);

        org.mockito.ArgumentCaptor<com.aismartstore.backend.outbox.OutboxEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.aismartstore.backend.outbox.OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("document.acl.changed");
        assertThat(captor.getValue().getPayload().get("action")).isEqualTo("unshared");
    }

    @Test
    void plainMemberCannotUnshareASharedCreatedBySomeoneElse() {
        UUID documentId = UUID.randomUUID();
        KeycloakUser plainMember = new KeycloakUser("plain-member", "plain", List.of("can-bo"));
        when(documentScopeGuard.requireVisible(plainMember, documentId))
                .thenReturn(document(documentId, "uploader-sub"));
        when(documentGroupShareRepository.findByDocumentIdAndGroupId(documentId, groupId))
                .thenReturn(Optional.of(share(documentId, groupId, "someone-else")));
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, "plain-member"))
                .thenReturn(Optional.of(member("plain-member", GroupRole.MEMBER)));

        assertThatThrownBy(() -> service.unshareDocument(plainMember, documentId, groupId))
                .isInstanceOf(ForbiddenException.class);
        verify(documentGroupShareRepository, never()).delete(any(DocumentGroupShare.class));
    }

    @Test
    void theUserWhoCreatedTheShareCanUnshareItEvenAsAPlainMember() {
        UUID documentId = UUID.randomUUID();
        KeycloakUser sharer = new KeycloakUser("sharer-sub", "sharer", List.of("can-bo"));
        when(documentScopeGuard.requireVisible(sharer, documentId)).thenReturn(document(documentId, "uploader-sub"));
        DocumentGroupShare share = share(documentId, groupId, "sharer-sub");
        when(documentGroupShareRepository.findByDocumentIdAndGroupId(documentId, groupId))
                .thenReturn(Optional.of(share));
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, "sharer-sub"))
                .thenReturn(Optional.of(member("sharer-sub", GroupRole.MEMBER)));

        service.unshareDocument(sharer, documentId, groupId);

        verify(documentGroupShareRepository).delete(share);
    }

    @Test
    void theDocumentsUploaderCanUnshareItEvenIfSomeoneElseSharedIt() {
        UUID documentId = UUID.randomUUID();
        KeycloakUser uploader = new KeycloakUser("uploader-sub", "uploader", List.of("can-bo"));
        when(documentScopeGuard.requireVisible(uploader, documentId)).thenReturn(document(documentId, "uploader-sub"));
        DocumentGroupShare share = share(documentId, groupId, "someone-else");
        when(documentGroupShareRepository.findByDocumentIdAndGroupId(documentId, groupId))
                .thenReturn(Optional.of(share));
        when(groupMemberRepository.findByGroupIdAndUserSub(groupId, "uploader-sub")).thenReturn(Optional.empty());

        service.unshareDocument(uploader, documentId, groupId);

        verify(documentGroupShareRepository).delete(share);
    }

    private static DocumentGroupShare share(UUID documentId, UUID groupId, String sharedBySub) {
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(documentId);
        share.setGroupId(groupId);
        share.setSharedBySub(sharedBySub);
        share.setSharedAt(OffsetDateTime.now());
        return share;
    }

    // ---- group-management-sharing-02-document-sharing.md §4.1 (Story 1): chia sẻ hàng loạt ----

    @Test
    void bulkShareRejectsAnItemOverTheGroupLimitButStillProcessesTheRest() {
        UUID overLimitDoc = UUID.randomUUID();
        UUID okDoc = UUID.randomUUID();
        when(groupRepository.existsByIdAndDeletedAtIsNull(groupId)).thenReturn(true);
        when(documentScopeGuard.requireVisible(owner, overLimitDoc)).thenReturn(document(overLimitDoc, "someone"));
        when(documentScopeGuard.requireVisible(owner, okDoc)).thenReturn(document(okDoc, "someone"));
        when(documentGroupShareRepository.countByDocumentId(overLimitDoc))
                .thenReturn((long) GroupsService.MAX_GROUPS_PER_DOCUMENT_BULK);
        when(documentGroupShareRepository.countByDocumentId(okDoc)).thenReturn(0L);
        when(documentGroupShareRepository.existsByDocumentIdAndGroupId(okDoc, groupId)).thenReturn(false);

        List<BulkShareResultItem> results =
                service.shareDocumentsBulk(owner, new BulkShareDto(groupId, List.of(overLimitDoc, okDoc)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId()).isEqualTo(overLimitDoc);
        assertThat(results.get(0).status()).isEqualTo("rejected");
        assertThat(results.get(0).reason()).isEqualTo("group_limit_exceeded");
        assertThat(results.get(1).documentId()).isEqualTo(okDoc);
        assertThat(results.get(1).status()).isEqualTo("shared");
        verify(documentGroupShareRepository).save(any(DocumentGroupShare.class));
    }

    @Test
    void bulkShareMarksAnOutOfScopeDocumentAsRejectedNotVisible() {
        UUID documentId = UUID.randomUUID();
        when(groupRepository.existsByIdAndDeletedAtIsNull(groupId)).thenReturn(true);
        when(documentScopeGuard.requireVisible(owner, documentId)).thenThrow(new NotFoundException("Document not found"));

        List<BulkShareResultItem> results = service.shareDocumentsBulk(owner, new BulkShareDto(groupId, List.of(documentId)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("rejected");
        assertThat(results.get(0).reason()).isEqualTo("not_visible");
        verify(documentGroupShareRepository, never()).save(any(DocumentGroupShare.class));
    }

    @Test
    void bulkShareMarksAnAlreadySharedDocumentWithoutError() {
        UUID documentId = UUID.randomUUID();
        when(groupRepository.existsByIdAndDeletedAtIsNull(groupId)).thenReturn(true);
        when(documentScopeGuard.requireVisible(owner, documentId)).thenReturn(document(documentId, "someone"));
        when(documentGroupShareRepository.countByDocumentId(documentId)).thenReturn(1L);
        when(documentGroupShareRepository.existsByDocumentIdAndGroupId(documentId, groupId)).thenReturn(true);

        List<BulkShareResultItem> results = service.shareDocumentsBulk(owner, new BulkShareDto(groupId, List.of(documentId)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("already_shared");
        verify(documentGroupShareRepository, never()).save(any(DocumentGroupShare.class));
    }

    @Test
    void bulkShareNotifiesOnlyForDocumentsThatEndedUpNewlyShared() {
        UUID sharedDoc = UUID.randomUUID();
        UUID alreadySharedDoc = UUID.randomUUID();
        when(groupRepository.existsByIdAndDeletedAtIsNull(groupId)).thenReturn(true);
        when(documentScopeGuard.requireVisible(owner, sharedDoc)).thenReturn(document(sharedDoc, "someone"));
        when(documentScopeGuard.requireVisible(owner, alreadySharedDoc)).thenReturn(document(alreadySharedDoc, "someone"));
        when(documentGroupShareRepository.existsByDocumentIdAndGroupId(alreadySharedDoc, groupId)).thenReturn(true);

        service.shareDocumentsBulk(owner, new BulkShareDto(groupId, List.of(sharedDoc, alreadySharedDoc)));

        verify(notificationService).notifyDocumentsSharedToGroup(groupId, List.of(sharedDoc), owner.sub());
    }

    @Test
    void bulkShareOnAMissingGroupThrowsBeforeProcessingAnyItem() {
        when(groupRepository.existsByIdAndDeletedAtIsNull(groupId)).thenReturn(false);

        assertThatThrownBy(() -> service.shareDocumentsBulk(owner, new BulkShareDto(groupId, List.of(UUID.randomUUID()))))
                .isInstanceOf(NotFoundException.class);
        verify(documentScopeGuard, never()).requireVisible(any(), any());
    }
}
