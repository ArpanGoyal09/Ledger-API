package com.arpan.ledger_api.dto;

import com.arpan.ledger_api.model.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;


public class LedgerEntryResponse {
    private final Long id;
    private final Long transferId;
    private final long amountMinor;
    private final String amount;
    private final String direction;
    private final String description;
    private final OffsetDateTime createdAt;

    public LedgerEntryResponse(Long id, Long transferId, long amountMinor, String amount, String direction, String description, OffsetDateTime createdAt){
        this.id = id;
        this.transferId = transferId;
        this.amountMinor = amountMinor;
        this.amount = amount;
        this.direction = direction;
        this.description = description;
        this.createdAt = createdAt;
    }

    public static LedgerEntryResponse from(LedgerEntry entry){
        long minor = entry.getAmountMinor();
        return new LedgerEntryResponse(entry.getId(),
                entry.getTransfer().getId(), minor,
                BigDecimal.valueOf(Math.abs(minor), 2).toPlainString(), 
                minor < 0 ? "DEBIT" : "CREDIT", entry.getTransfer().getDescription(), entry.getCreatedAt());
    }

    public Long getId() { return id; }
    public Long getTransferId() { return transferId; }
    public long getAmountMinor() { return amountMinor; }
    public String getAmount() { return amount; }
    public String getDirection() { return direction; }
    public String getDescription() { return description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    
}
