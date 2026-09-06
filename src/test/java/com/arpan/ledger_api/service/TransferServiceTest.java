package com.arpan.ledger_api.service;

import com.arpan.ledger_api.exception.AccountNotFoundException;
import com.arpan.ledger_api.exception.IdempotencyConflictException;
import com.arpan.ledger_api.exception.InsufficientFundsException;
import com.arpan.ledger_api.exception.PinException;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class TransferServiceTest {

        private static final String PIN = "1234";

        @Autowired private TransferService transferService;
        @Autowired private AccountRepository accountRepository;
        @Autowired private UserRepository userRepository;
        @Autowired private LedgerEntryRepository ledgerEntryRepository;
        @Autowired private PasswordEncoder passwordEncoder;

        private User user;
        private Account accountA;
        private Account accountB;

        private String key() {
                return UUID.randomUUID().toString();
        }

        @BeforeEach
        void setUp() {
                String suffix = String.valueOf(System.nanoTime());
                String shortSuffix = suffix.substring(suffix.length() - 8);

                user = userRepository.save(
                        new User("u" + suffix, "u" + suffix + "@example.com", "hash"));

                user.setPinHash(passwordEncoder.encode(PIN));
                userRepository.saveAndFlush(user);

                accountA = accountRepository.save(new Account(user, "A" + shortSuffix, "INR"));
                accountB = accountRepository.save(new Account(user, "B" + shortSuffix, "INR"));

                accountA.credit(100000);
                accountRepository.saveAndFlush(accountA);
        }

        @Test
        void transferMovesMoneyBetweenAccounts() {
                transferService.transfer(accountA.getId(), accountB.getId(),
                        30000, "test transfer", user.getId(), PIN, key());

                assertEquals(70000, accountA.getBalanceMinor());
                assertEquals(30000, accountB.getBalanceMinor());
        }

        @Test
        void transferCreatesBalancedLedgerEntries() {
                Transfer transfer = transferService.transfer(accountA.getId(), accountB.getId(),
                        30000, "test transfer", user.getId(), PIN, key());

                List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(transfer.getId());

                assertEquals(2, entries.size(), "a transfer must produce exactly two entries");

                long sum = entries.stream().mapToLong(LedgerEntry::getAmountMinor).sum();
                assertEquals(0, sum, "ledger entries must sum to zero");
        }

        @Test
        void transferIsMarkedCompleted() {
                Transfer transfer = transferService.transfer(accountA.getId(), accountB.getId(),
                        30000, "test transfer", user.getId(), PIN, key());

                assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        }

        @Test
        void overdraftIsRejectedAndNothingChanges() {
                assertThrows(InsufficientFundsException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                150000, "too much", user.getId(), PIN, key()));

                assertEquals(100000, accountA.getBalanceMinor(), "balance must be unchanged");
                assertEquals(0, accountB.getBalanceMinor(), "balance must be unchanged");
        }

        @Test
        void insufficientFundsExceptionCarriesTheShortfall() {
                InsufficientFundsException ex = assertThrows(InsufficientFundsException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                150000, "too much", user.getId(), PIN, key()));

                assertEquals(100000, ex.getBalanceMinor());
                assertEquals(150000, ex.getRequestedMinor());
                assertEquals(50000, ex.getShortfallMinor());
        }

        @Test
        void selfTransferIsRejected() {
                assertThrows(IllegalArgumentException.class, () ->
                        transferService.transfer(accountA.getId(), accountA.getId(),
                                1000, "self", user.getId(), PIN, key()));
        }

        @Test
        void zeroAmountIsRejected() {
                assertThrows(IllegalArgumentException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                0, "zero", user.getId(), PIN, key()));
        }

        @Test
        void negativeAmountIsRejected() {
                assertThrows(IllegalArgumentException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                -5000, "negative", user.getId(), PIN, key()));
        }

        @Test
        void unknownAccountIsRejected() {
                assertThrows(AccountNotFoundException.class, () ->
                        transferService.transfer(accountA.getId(), 999999L,
                                1000, "missing", user.getId(), PIN, key()));
        }

        @Test
        void transferFromAnotherUsersAccountIsRejected() {
                assertThrows(AccountNotFoundException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                1000, "not my account", 999999L, PIN, key()));
        }

        @Test
        void missingPinIsRejected() {
                assertThrows(PinException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                30000, "no pin", user.getId(), null, key()));
        }

        @Test
        void userWithNoPinCannotTransfer() {
                String suffix = String.valueOf(System.nanoTime());
                User pinless = userRepository.save(
                        new User("np" + suffix, "np" + suffix + "@example.com", "hash"));
                Account account = accountRepository.save(
                        new Account(pinless, "NP" + suffix.substring(suffix.length() - 8), "INR"));
                account.credit(50000);
                accountRepository.saveAndFlush(account);

                assertThrows(PinException.class, () ->
                        transferService.transfer(account.getId(), accountB.getId(),
                                1000, "no pin set", pinless.getId(), "1234", key()));
        }

        @Test
        void missingIdempotencyKeyIsRejected() {
                assertThrows(IllegalArgumentException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                30000, "no key", user.getId(), PIN, null));
        }

        @Test
        void replayingAKeyReturnsTheOriginalTransferWithoutMovingMoney() {
                String sharedKey = key();

                Transfer first = transferService.transfer(accountA.getId(), accountB.getId(),
                        30000, "original", user.getId(), PIN, sharedKey);

                Transfer replay = transferService.transfer(accountA.getId(), accountB.getId(),
                        30000, "original", user.getId(), PIN, sharedKey);

                assertEquals(first.getId(), replay.getId(), "a replay must return the original transfer");
                assertEquals(70000, accountA.getBalanceMinor(), "money must move exactly once");
                assertEquals(30000, accountB.getBalanceMinor(), "money must move exactly once");
        }

        @Test
        void reusingAKeyWithDifferentParametersIsRejected() {
                String sharedKey = key();

                transferService.transfer(accountA.getId(), accountB.getId(),
                        30000, "original", user.getId(), PIN, sharedKey);

                assertThrows(IdempotencyConflictException.class, () ->
                        transferService.transfer(accountA.getId(), accountB.getId(),
                                50000, "different amount", user.getId(), PIN, sharedKey));

                assertEquals(70000, accountA.getBalanceMinor(), "the rejected request must not move money");
        }
}