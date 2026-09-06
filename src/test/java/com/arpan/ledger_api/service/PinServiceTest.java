package com.arpan.ledger_api.service;

import com.arpan.ledger_api.exception.PinException;
import com.arpan.ledger_api.model.Account;
import com.arpan.ledger_api.model.User;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.IdempotencyKeyRepository;
import com.arpan.ledger_api.repository.LedgerEntryRepository;
import com.arpan.ledger_api.repository.TransferRepository;
import com.arpan.ledger_api.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PinServiceTest {

    private static final String PASSWORD = "testpassword123";
    private static final String PIN = "1234";

    @Autowired private PinService pinService;
    @Autowired private TransferService transferService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;

    private Long userId;
    private Long fromId;
    private Long toId;

    private String key() {
        return UUID.randomUUID().toString();
    }

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        String shortSuffix = suffix.substring(suffix.length() - 8);

        User user = userRepository.saveAndFlush(new User("p" + suffix, "p" + suffix + "@example.com", passwordEncoder.encode(PASSWORD)));
        userId = user.getId();

        Account from = accountRepository.save(new Account(user, "PF" + shortSuffix, "INR"));
        Account to = accountRepository.save(new Account(user, "PT" + shortSuffix, "INR"));
        from.credit(100000);
        accountRepository.saveAndFlush(from);

        fromId = from.getId();
        toId = to.getId();
    }

    @AfterEach
    void tearDown() {
        idempotencyKeyRepository.deleteByUserId(userId);
        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByAccountIdWithTransfer(fromId));
        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByAccountIdWithTransfer(toId));
        transferRepository.deleteAll(transferRepository.findByInitiatedByIdOrderByCreatedAtDesc(userId));
        accountRepository.deleteById(fromId);
        accountRepository.deleteById(toId);
        userRepository.deleteById(userId);
    }

    @Test
    void pinIsStoredHashed() {
        pinService.setPin(userId, PASSWORD, PIN);

        User user = userRepository.findById(userId).orElseThrow();
        assertNotNull(user.getPinHash());
        assertNotEquals(PIN, user.getPinHash(), "PIN must never be stored in plaintext");
        assertTrue(passwordEncoder.matches(PIN, user.getPinHash()));
    }

    @Test
    void settingPinRequiresTheCurrentPassword() {
        assertThrows(BadCredentialsException.class, () -> pinService.setPin(userId, "wrongpassword", PIN));

        User user = userRepository.findById(userId).orElseThrow();
        assertNull(user.getPinHash(), "PIN must not be set when the password is wrong");
    }

    @Test
    void pinMustBeFourToSixDigits() {
        assertThrows(IllegalArgumentException.class, () ->
                pinService.setPin(userId, PASSWORD, "12"));
        assertThrows(IllegalArgumentException.class, () ->
                pinService.setPin(userId, PASSWORD, "abcd"));
        assertThrows(IllegalArgumentException.class, () ->
                pinService.setPin(userId, PASSWORD, "12345678"));
    }

    @Test
    void failedAttemptSurvivesTheRollbackOfTheTransfer() {
        pinService.setPin(userId, PASSWORD, PIN);

        assertThrows(PinException.class, () -> transferService.transfer(fromId, toId, 10000, "wrong pin", userId, "9999", key()));

        User user = userRepository.findById(userId).orElseThrow();
        assertEquals(1, user.getFailedPinAttempts(), "the increment must survive the transfer's rollback");

        Account from = accountRepository.findById(fromId).orElseThrow();
        assertEquals(100000, from.getBalanceMinor(), "no money may move on a failed PIN");
    }

    @Test
    void fiveFailedAttemptsLockTheAccount() {
        pinService.setPin(userId, PASSWORD, PIN);

        for (int i = 0; i < 5; i++) {
            assertThrows(PinException.class, () -> transferService.transfer(fromId, toId, 10000, "wrong pin", userId, "9999", key()));
        }

        User user = userRepository.findById(userId).orElseThrow();
        assertTrue(user.isLocked(), "account must be locked after five failures");
        assertEquals(0, user.getFailedPinAttempts(), "counter resets on lockout so the next window starts fresh");
    }

    @Test
    void lockedAccountIsRejectedEvenWithTheCorrectPin() {
        pinService.setPin(userId, PASSWORD, PIN);

        for (int i = 0; i < 5; i++) {
            assertThrows(PinException.class, () -> transferService.transfer(fromId, toId, 10000, "wrong pin", userId, "9999", key()));
        }

        PinException ex = assertThrows(PinException.class, () -> transferService.transfer(fromId, toId, 10000, "correct pin", userId, PIN, key()));

        assertTrue(ex.isLocked(), "a locked account must be refused regardless of PIN");
    }

    @Test
    void successfulTransferClearsTheFailureCount() {
        pinService.setPin(userId, PASSWORD, PIN);

        assertThrows(PinException.class, () -> transferService.transfer(fromId, toId, 10000, "wrong pin", userId, "9999", key()));

        transferService.transfer(fromId, toId, 10000, "correct pin", userId, PIN, key());

        User user = userRepository.findById(userId).orElseThrow();
        assertEquals(0, user.getFailedPinAttempts());
        assertNull(user.getLockedUntil());
    }
}