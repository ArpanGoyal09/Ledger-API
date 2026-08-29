package com.arpan.ledger_api.service;

import com.arpan.ledger_api.dto.*;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository, LedgerEntryRepository ledgerEntryRepository){
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public Account createAccount(Long userId, String accountNumber, String currency){
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

        if(accountRepository.findByAccountNumber(accountNumber).isPresent()){
            throw new IllegalStateException("Account number already exists: " + accountNumber);
        }

        Account account = new Account(user, accountNumber, currency);
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Account getAccount(Long accountId){
        return accountRepository.findById(accountId).orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> getEntries(Long accountId){
        getAccount(accountId);
        return ledgerEntryRepository.findByAccountIdWithTransfer(accountId).stream().map(LedgerEntryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(Long accountId){
        Account account = getAccount(accountId);
        long stored = account.getBalanceMinor();
        long derived = ledgerEntryRepository.sumAmountByAccountId(accountId);
        return ReconciliationResponse.of(accountId, account.getAccountNumber(), stored, derived);
    }

}
