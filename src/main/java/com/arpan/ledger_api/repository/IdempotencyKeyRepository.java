package com.arpan.ledger_api.repository;

import com.arpan.ledger_api.model.*;

import jakarta.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long>{
    Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String IdempotencyKey);

    @Modifying 
    @Transactional
    void deleteByUserId(Long userId);
}
