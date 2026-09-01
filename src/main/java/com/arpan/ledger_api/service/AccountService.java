package com.arpan.ledger_api.service;

import com.arpan.ledger_api.dto.*;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.repository.*;
import com.arpan.ledger_api.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private Account loadOwnedAccount(Long accountId, Long requestingUserId){
        Account account = accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));

        if(!account.getUser().getId().equals(requestingUserId)){
            log.warn("User {} attempted to access account {} owned by user {}", requestingUserId, accountId, account.getUser().getId());
            throw new AccountNotFoundException(accountId);
        }

        return account;
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
    public Account getAccount(Long accountId, Long requestingUserId) {
        return loadOwnedAccount(accountId, requestingUserId);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> getEntries(Long accountId, Long requestingUserId) {
        loadOwnedAccount(accountId, requestingUserId);
        return ledgerEntryRepository.findByAccountIdWithTransfer(accountId).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(Long accountId, Long requestingUserId) {
        Account account = loadOwnedAccount(accountId, requestingUserId);
        long stored = account.getBalanceMinor();
        long derived = ledgerEntryRepository.sumAmountByAccountId(accountId);
        return ReconciliationResponse.of(accountId, account.getAccountNumber(), stored, derived);
    }

}
