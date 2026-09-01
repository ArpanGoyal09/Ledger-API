package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.config.AuthenticatedUser;
import com.arpan.ledger_api.dto.AccountResponse;
import com.arpan.ledger_api.dto.CreateAccountRequest;
import com.arpan.ledger_api.dto.LedgerEntryResponse;
import com.arpan.ledger_api.dto.ReconciliationResponse;
import com.arpan.ledger_api.model.Account;
import com.arpan.ledger_api.service.AccountService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public AccountResponse createAccount(@RequestBody CreateAccountRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        Account account = accountService.createAccount(
                user.userId(),
                request.getAccountNumber(),
                request.getCurrency());
        return AccountResponse.from(account);
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return AccountResponse.from(accountService.getAccount(id, user.userId()));
    }

    @GetMapping("/{id}/entries")
    public List<LedgerEntryResponse> getEntries(@PathVariable Long id,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.getEntries(id, user.userId());
    }

    @GetMapping("/{id}/reconcile")
    public ReconciliationResponse reconcile(@PathVariable Long id,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.reconcile(id, user.userId());
    }
}