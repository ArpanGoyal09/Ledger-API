package com.arpan.ledger_api.service;

import com.arpan.ledger_api.model.Account;
import com.arpan.ledger_api.model.LedgerEntry;
import com.arpan.ledger_api.model.Transfer;
import com.arpan.ledger_api.model.User;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.LedgerEntryRepository;
import com.arpan.ledger_api.repository.TransferRepository;
import com.arpan.ledger_api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository, LedgerEntryRepository ledgerEntryRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Transfer transfer(Long fromAccountId, Long toAccountId, long amountMinor, String description, Long initiatedByUserId) {

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

        User initiatedBy = userRepository.findById(initiatedByUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found: " + initiatedByUserId));

        Transfer transfer = new Transfer(initiatedBy, amountMinor, description);
        transferRepository.save(transfer);

        from.debit(amountMinor);
        to.credit(amountMinor);

        ledgerEntryRepository.save(LedgerEntry.debit(transfer, from, amountMinor));
        ledgerEntryRepository.save(LedgerEntry.credit(transfer, to, amountMinor));

        transfer.markCompleted();

        return transfer;
    }

    private Account loadForUpdate(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + accountId));
    }
}