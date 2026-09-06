package com.arpan.ledger_api.service;

import com.arpan.ledger_api.exception.AccountNotFoundException;
import com.arpan.ledger_api.exception.IdempotencyConflictException;
import com.arpan.ledger_api.exception.IdempotencyRaceException;
import com.arpan.ledger_api.exception.PinException;
import com.arpan.ledger_api.model.Account;
import com.arpan.ledger_api.model.IdempotencyKey;
import com.arpan.ledger_api.model.LedgerEntry;
import com.arpan.ledger_api.model.SystemAccounts;
import com.arpan.ledger_api.model.Transfer;
import com.arpan.ledger_api.model.User;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.IdempotencyKeyRepository;
import com.arpan.ledger_api.repository.LedgerEntryRepository;
import com.arpan.ledger_api.repository.TransferRepository;
import com.arpan.ledger_api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PinService pinService;
    private final PasswordEncoder passwordEncoder;

    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository,
                        LedgerEntryRepository ledgerEntryRepository, UserRepository userRepository,
                        IdempotencyKeyRepository idempotencyKeyRepository, PinService pinService, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.pinService = pinService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Transfer transfer(Long fromAccountId, Long toAccountId, long amountMinor, String description, 
        Long initiatedByUserId, String pin, String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        String requestHash = hashRequest(fromAccountId, toAccountId, amountMinor, description);

        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByUserIdAndIdempotencyKey(initiatedByUserId, idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyKey record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            log.info("Idempotent replay of key {} for user {}", idempotencyKey, initiatedByUserId);
            return record.getTransfer();
        }

        Account systemAccount = accountRepository.findByAccountNumber(SystemAccounts.EXTERNAL_ACCOUNT_NUMBER).orElse(null);

        if (systemAccount != null && systemAccount.getId().equals(fromAccountId)) {
            throw new IllegalArgumentException("Deposits must use the deposit endpoint, not a direct transfer");
        }

        Long ownerId = accountRepository.findOwnerIdByAccountId(fromAccountId).orElseThrow(() -> new AccountNotFoundException(fromAccountId));

        if (!ownerId.equals(initiatedByUserId)) {
            log.warn("User {} attempted to transfer from account {} owned by user {}", initiatedByUserId, fromAccountId, ownerId);
            throw new AccountNotFoundException(fromAccountId);
        }

        verifyPin(initiatedByUserId, pin);

        Transfer transfer = executeTransfer(fromAccountId, toAccountId, amountMinor, description, initiatedByUserId);

        User user = userRepository.findById(initiatedByUserId).orElseThrow();

        try {
            idempotencyKeyRepository.saveAndFlush(new IdempotencyKey(user, idempotencyKey, requestHash, transfer));

        } catch (DataIntegrityViolationException e) {

            log.warn("Idempotency race on key {} for user {}", idempotencyKey, initiatedByUserId);
            throw new IdempotencyRaceException(idempotencyKey);
        }

        return transfer;
    }

    @Transactional
    public Transfer deposit(Long toAccountId, long amountMinor, String description) {
        Account systemAccount = accountRepository.findByAccountNumber(SystemAccounts.EXTERNAL_ACCOUNT_NUMBER)
                .orElseThrow(() -> new IllegalStateException("System account " + SystemAccounts.EXTERNAL_ACCOUNT_NUMBER + " not found"));

        return executeTransfer(systemAccount.getId(), toAccountId, amountMinor, description, systemAccount.getUser().getId());
    }

    private Transfer executeTransfer(Long fromAccountId, Long toAccountId, long amountMinor, String description, Long initiatedByUserId) {

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer to same account");
        }

        Long firstId = Math.min(fromAccountId, toAccountId);
        Long secondId = Math.max(fromAccountId, toAccountId);

        Account firstLocked = loadForUpdate(firstId);
        Account secondLocked = loadForUpdate(secondId);

        boolean fromIsFirst = fromAccountId.equals(firstId);
        Account from = fromIsFirst ? firstLocked : secondLocked;
        Account to = fromIsFirst ? secondLocked : firstLocked;

        User initiatedBy = userRepository.findById(initiatedByUserId).orElseThrow(() -> new IllegalArgumentException("User not found: " + initiatedByUserId));

        Transfer transfer = new Transfer(initiatedBy, amountMinor, description);
        transferRepository.save(transfer);

        from.debit(amountMinor);
        to.credit(amountMinor);

        ledgerEntryRepository.save(LedgerEntry.debit(transfer, from, amountMinor));
        ledgerEntryRepository.save(LedgerEntry.credit(transfer, to, amountMinor));

        transfer.markCompleted();

        return transfer;
    }

    private void verifyPin(Long userId, String rawPin) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (user.isLocked()) {
            throw PinException.locked();
        }
        if (!user.hasPin()) {
            throw PinException.notSet();
        }
        if (rawPin == null || rawPin.isBlank()) {
            throw PinException.missing();
        }

        if (!passwordEncoder.matches(rawPin, user.getPinHash())) {
            int remaining = pinService.recordFailure(userId);
            if (remaining == 0) {
                throw PinException.locked();
            }
            throw PinException.incorrect(remaining);
        }

        pinService.recordSuccess(userId);
    }

    private Account loadForUpdate(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private String hashRequest(Long fromAccountId, Long toAccountId, long amountMinor, String description) {
        String canonical = fromAccountId + "|" + toAccountId + "|" + amountMinor
                + "|" + (description == null ? "" : description);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}