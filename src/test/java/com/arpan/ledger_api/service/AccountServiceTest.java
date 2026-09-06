package com.arpan.ledger_api.service;

import com.arpan.ledger_api.dto.LedgerEntryResponse;
import com.arpan.ledger_api.dto.ReconciliationResponse;
import com.arpan.ledger_api.model.Account;
import com.arpan.ledger_api.model.User;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.arpan.ledger_api.exception.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AccountServiceTest {

    @Autowired private AccountService accountService;
    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String PIN = "1234";
    private User user;
    private String suffix;

    private String key() {
        return UUID.randomUUID().toString();
    }

    @BeforeEach
    void setUp() {
        suffix = String.valueOf(System.nanoTime());
        user = userRepository.save(
                new User("u" + suffix, "u" + suffix + "@example.com", "hash"));

        user.setPinHash(passwordEncoder.encode(PIN));
        userRepository.saveAndFlush(user);
    }

    private String accountNumber(String prefix) {
        return prefix + suffix.substring(suffix.length() - 8);
    }

    @Test
    void newAccountStartsAtZero() {
        Account account = accountService.createAccount(
                user.getId(), accountNumber("N"), "INR");

        assertNotNull(account.getId());
        assertEquals(0, account.getBalanceMinor());
    }

    @Test
    void duplicateAccountNumberIsRejected() {
        String number = accountNumber("D");
        accountService.createAccount(user.getId(), number, "INR");

        assertThrows(IllegalStateException.class, () ->
                accountService.createAccount(user.getId(), number, "INR"));
    }

    @Test
    void unknownUserCannotOwnAnAccount() {
        assertThrows(IllegalArgumentException.class, () ->
                accountService.createAccount(999999L, accountNumber("U"), "INR"));
    }

    @Test
    void unknownAccountIsRejectedOnRead() {
        assertThrows(AccountNotFoundException.class, () ->
                accountService.getAccount(999999L, user.getId()));
    }

    @Test
    void anAccountWithNoActivityReconciles() {
        Account account = accountService.createAccount(
                user.getId(), accountNumber("R"), "INR");

        ReconciliationResponse result = accountService.reconcile(account.getId(), user.getId());

        assertTrue(result.isBalanced());
        assertEquals(0, result.getDriftMinor());
    }

    @Test
    void reconciliationDetectsABalanceWithNoLedgerBacking() {
        Account account = accountService.createAccount(
                user.getId(), accountNumber("X"), "INR");

        account.credit(50000);
        accountRepository.saveAndFlush(account);

        ReconciliationResponse result = accountService.reconcile(account.getId(), user.getId());

        assertFalse(result.isBalanced(), "money with no ledger entry must show as drift");
        assertEquals(50000, result.getStoredBalanceMinor());
        assertEquals(0, result.getDerivedBalanceMinor());
        assertEquals(50000, result.getDriftMinor());
    }

    @Test
    void transferHistoryIsReturnedNewestFirst() {
        Account from = accountService.createAccount(user.getId(), accountNumber("F"), "INR");
        Account to = accountService.createAccount(user.getId(), accountNumber("T"), "INR");

        from.credit(100000);
        accountRepository.saveAndFlush(from);

        transferService.transfer(from.getId(), to.getId(), 10000, "first", user.getId(), PIN, key());
        transferService.transfer(from.getId(), to.getId(), 20000, "second", user.getId(), PIN, key());

        List<LedgerEntryResponse> entries = accountService.getEntries(from.getId(), user.getId());

        assertEquals(2, entries.size());
        assertEquals("DEBIT", entries.get(0).getDirection());
        assertEquals(-20000, entries.get(0).getAmountMinor());
    }

    @Test
    void anotherUsersAccountIsNotVisible() {
        Account account = accountService.createAccount(user.getId(), accountNumber("P"), "INR");

        User intruder = userRepository.save(new User("intruder" + suffix, "intruder" + suffix + "@example.com", "hash"));

        assertThrows(AccountNotFoundException.class, () -> accountService.getAccount(account.getId(), intruder.getId()));
    }
}