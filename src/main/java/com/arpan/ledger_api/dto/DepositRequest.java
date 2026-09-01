package com.arpan.ledger_api.dto;

public class DepositRequest {

    private Long toAccountId;
    private long amountMinor;
    private String description;

    public Long getToAccountId() { return toAccountId; }
    public void setToAccountId(Long toAccountId) { this.toAccountId = toAccountId; }

    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}