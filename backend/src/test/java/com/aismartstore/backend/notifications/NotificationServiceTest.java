package com.aismartstore.backend.notifications;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.groups.GroupMember;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.GroupRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * group-management-sharing-02-document-sharing.md §5 ("NotificationService.notifyAsync:
 * repository ném exception → không exception nào thoát ra khỏi hàm").
 */
class NotificationServiceTest {

    private NotificationRepository notificationRepository;
    private GroupMemberRepository groupMemberRepository;
    private NotificationService service;

    private final KeycloakUser user = new KeycloakUser("user-sub", "user", List.of("can-bo"));

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        service = new NotificationService(notificationRepository, groupMemberRepository);
    }

    @Test
    void notifyAsyncNeverLetsARepositoryFailurePropagate() {
        when(notificationRepository.saveAll(anyList())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.notifyAsync(List.of("user-sub"), "group.member_added", Map.of("groupId", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    void notifyDocumentsSharedToGroupNeverLetsARepositoryFailurePropagate() {
        UUID groupId = UUID.randomUUID();
        when(groupMemberRepository.findByGroupIdOrderByJoinedAtAsc(groupId))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.notifyDocumentsSharedToGroup(groupId, List.of(UUID.randomUUID()), "actor-sub"))
                .doesNotThrowAnyException();
    }

    @Test
    void notifyDocumentsSharedToGroupExcludesTheActorAndDedupesRecipients() {
        UUID groupId = UUID.randomUUID();
        when(groupMemberRepository.findByGroupIdOrderByJoinedAtAsc(groupId))
                .thenReturn(List.of(member("actor-sub"), member("member-a"), member("member-b")));

        service.notifyDocumentsSharedToGroup(groupId, List.of(UUID.randomUUID()), "actor-sub");

        verify(notificationRepository).saveAll(argThat((List<Notification> rows) -> rows.size() == 2));
    }

    @Test
    void notifyDocumentsSharedToGroupIsANoOpWhenThereAreNoDocuments() {
        UUID groupId = UUID.randomUUID();

        service.notifyDocumentsSharedToGroup(groupId, List.of(), "actor-sub");

        verify(groupMemberRepository, never()).findByGroupIdOrderByJoinedAtAsc(any());
    }

    @Test
    void markReadOnSomeoneElsesNotificationIs404() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.existsByIdAndRecipientSub(id, "user-sub")).thenReturn(false);

        assertThatThrownBy(() -> service.markRead(user, id)).isInstanceOf(NotFoundException.class);
        verify(notificationRepository, never()).markRead(any(), any(), any());
    }

    @Test
    void markReadOnOwnNotificationSucceeds() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.existsByIdAndRecipientSub(id, "user-sub")).thenReturn(true);

        service.markRead(user, id);

        verify(notificationRepository).markRead(eq(id), eq("user-sub"), any(OffsetDateTime.class));
    }

    private static GroupMember member(String userSub) {
        GroupMember m = new GroupMember();
        m.setUserSub(userSub);
        m.setRoleInGroup(GroupRole.MEMBER);
        m.setJoinedAt(OffsetDateTime.now());
        return m;
    }
}
