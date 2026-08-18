package com.arpan.ledger_api.service;

import com.arpan.ledger_api.repository.TransferRepository;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.UserRepository;
import com.arpan.ledger_api.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import com.arpan.ledger_api.model.*;
import org.springframework.transaction.annotation.Transactional;;


@Service
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository, LedgerEntryRepository ledgerEntryRepository, UserRepository userRepository){
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
    }


    @Transactional
    public Transfer transfer(Long fromAccountId, Long toAccountId, long amountMinor, String description, Long initiatedByUserId){
        if(amountMinor <= 0){
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        if(fromAccountId.equals(toAccountId)){
            throw new IllegalArgumentException("Cannot transfer to same account");
        }

        Account from = accountRepository.findById(fromAccountId).orElseThrow(() -> new IllegalArgumentException("Source account not found: "+ fromAccountId));

        Account to = accountRepository.findById(toAccountId).orElseThrow(() -> new IllegalArgumentException("Destination account not found: " + toAccountId));

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
}
