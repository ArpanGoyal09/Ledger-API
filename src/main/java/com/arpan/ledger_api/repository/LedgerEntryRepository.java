package com.arpan.ledger_api.repository;

import com.arpan.ledger_api.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<LedgerEntry> findByTransferId(Long transferId);

    @Query("SELECT COALESCE(SUM(e.amountMinor), 0) FROM LedgerEntry e WHERE e.account.id = :accountId")
    long sumAmountByAccountId(@Param("accountId") Long accountId);
}