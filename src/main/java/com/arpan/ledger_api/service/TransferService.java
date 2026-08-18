package com.arpan.ledger_api.service;

import com.arpan.ledger_api.repository.TransferRepository;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.UserRepository;
import com.arpan.ledger_api.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;

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
}
