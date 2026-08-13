package com.arpan.ledger_api.repository;

import com.arpan.ledger_api.model.Transfer;
import com.arpan.ledger_api.model.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findByInitiatedByIdOrderByCreatedAtDesc(Long userId);

    List<Transfer> findByStatus(TransferStatus status);
}