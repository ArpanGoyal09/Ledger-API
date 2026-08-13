package com.arpan.ledger_api.repository;

import com.arpan.ledger_api.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;



public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountNumber(String accountNumber);
}
