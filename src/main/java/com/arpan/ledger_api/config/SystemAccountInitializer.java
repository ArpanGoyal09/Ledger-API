package com.arpan.ledger_api.config;

import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
public class SystemAccountInitializer implements CommandLineRunner{
    private static final Logger log = LoggerFactory.getLogger(SystemAccountInitializer.class);
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    public SystemAccountInitializer(UserRepository userRepository, AccountRepository accountRepository){
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public void run(String... args){
        if(accountRepository.findByAccountNumber(SystemAccounts.EXTERNAL_ACCOUNT_NUMBER).isPresent()){
            return;
        }

        User systemUser = userRepository.findByUsername(SystemAccounts.SYSTEM_USERNAME).orElseGet(() -> userRepository.save(
            new User(SystemAccounts.SYSTEM_USERNAME, SystemAccounts.SYSTEM_EMAIL, "NOT_A_LOGIN_ACCOUNT")));

        Account external = Account.systemAccount(systemUser, SystemAccounts.EXTERNAL_ACCOUNT_NUMBER, "INR");
        accountRepository.save(external);

        log.info("Created system account {} for external money movement", SystemAccounts.EXTERNAL_ACCOUNT_NUMBER);
    }
}
