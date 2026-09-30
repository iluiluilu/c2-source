package com.aismartstore.backend.notifications;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * group-management-sharing-02-document-sharing.md §3.4: personal
 * notification inbox - {@code hasAnyRole(...)} (not {@code hasAuthority}),
 * same rationale as {@link com.aismartstore.backend.userdirectory.UserDirectoryController}:
 * this is the caller's own data, no dedicated permission needed.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationsController {

    private final NotificationService notificationService;

    public NotificationsController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CAN_BO','LANH_DAO')")
    public List<NotificationService.NotificationView> list(
            @CurrentUser KeycloakUser user, @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notificationService.list(user, unreadOnly);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('CAN_BO','LANH_DAO')")
    public void markRead(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        notificationService.markRead(user, id);
    }
}
