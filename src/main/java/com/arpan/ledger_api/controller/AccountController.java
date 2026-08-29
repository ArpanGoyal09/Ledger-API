package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.dto.*;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService accountService;
    
    public AccountController(AccountService accountService){
        this.accountService = accountService;
    }

    @PostMapping
    public AccountResponse createAccount(@RequestBody CreateAccountRequest request){
        Account account = accountService.createAccount(request.getUserId(), request.getAccountNumber(), request.getCurrency());

        return AccountResponse.from(account);
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable Long id){
        Account account = accountService.getAccount(id);
        return AccountResponse.from(account);
    }

    @GetMapping("/{id}/entries")
    public List<LedgerEntryResponse> getEntries(@PathVariable Long id){
        return accountService.getEntries(id);
    }
}
