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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Accounts", description = "Account creation, balances, statements, reconciliation")
@SecurityRequirement(name = "bearerAuth")
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

    @Operation(summary = "Compare stored balance against the ledger",
            description = """
                    Recomputes the balance from ledger entries and reports any drift. \
                    A stored balance is derived data and can drift from its source; this \
                    endpoint detects it.""")

    @GetMapping("/{id}/reconcile")
    public ReconciliationResponse reconcile(@PathVariable Long id,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.reconcile(id, user.userId());
    }
}