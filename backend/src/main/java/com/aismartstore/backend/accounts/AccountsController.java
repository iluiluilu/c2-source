package com.aismartstore.backend.accounts;

import com.aismartstore.backend.accounts.dto.ChangeOwnPasswordDto;
import com.aismartstore.backend.accounts.dto.CreateAccountDto;
import com.aismartstore.backend.accounts.dto.ResetPasswordDto;
import com.aismartstore.backend.accounts.dto.SetAccountQuotaDto;
import com.aismartstore.backend.accounts.dto.SetAccountStatusDto;
import com.aismartstore.backend.accounts.dto.UpdateAccountDto;
import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account management (FR-04/05/06). Accounts are an administration concern,
 * so every route is R3 (quan-tri) - except changing one's own password
 * (FR-05), which every role gets. Java port of the NestJS backend's
 * accounts/accounts.controller.ts.
 */
@RestController
@RequestMapping("/accounts")
public class AccountsController {

    private final AccountsService accountsService;

    public AccountsController(AccountsService accountsService) {
        this.accountsService = accountsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_account.read')")
    public List<AccountsService.AccountRow> list() {
        return accountsService.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_account.manage')")
    public AccountsService.AccountRow create(@CurrentUser KeycloakUser user, @Valid @RequestBody CreateAccountDto dto) {
        return accountsService.create(user, dto);
    }

    /**
     * FR-05 - declared before the {id} routes so 'me' reads cleanly as the
     * dedicated self-service route (Spring's literal-segment path matching
     * isn't order-sensitive the way Express's is, but the grouping mirrors
     * OLD's source layout for readability).
     */
    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_account.self_service')")
    public void changeOwnPassword(@CurrentUser KeycloakUser user, @Valid @RequestBody ChangeOwnPasswordDto dto) {
        accountsService.changeOwnPassword(user, dto);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_account.manage')")
    public AccountsService.AccountRow update(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody UpdateAccountDto dto) {
        return accountsService.update(user, id.toString(), dto);
    }

    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_account.manage')")
    public void setStatus(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody SetAccountStatusDto dto) {
        accountsService.setStatus(user, id.toString(), dto);
    }

    @PatchMapping("/{id}/quota")
    @PreAuthorize("hasAuthority('PERM_account.manage')")
    public AccountsService.QuotaResult setQuota(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody SetAccountQuotaDto dto) {
        return accountsService.setQuota(user, id.toString(), dto);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_account.manage')")
    public void resetPassword(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody ResetPasswordDto dto) {
        accountsService.resetPassword(user, id.toString(), dto);
    }
}
