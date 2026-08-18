package com.arpan.ledger_api.dto;

public class TransferRequest{
    private Long fromAccountId;
    private Long toAccountId;
    private long amountMinor;
    private String description;
    private Long initiatedByUserId;

    public Long getFromAccountId(){
        return fromAccountId;
    }
    public void setFromAccountId(Long fromAccountId){
        this.fromAccountId = fromAccountId;
    }

    public Long getToAccountId(){
        return toAccountId;
    }
    public void setToAccountId(Long toAccountId){
        this.toAccountId = toAccountId;
    }

    public long getAmountMinor(){
        return amountMinor;
    }
    public void setAmountMinor(long amountMinor){
        this.amountMinor = amountMinor;
    }

    public String getDescription(){
        return description;
    }
    public void setDescription(String description){
        this.description = description;
    }

    public Long getInitiatedByUserId(){
        return initiatedByUserId;
    }
    public void setInitiatedByUserId(Long initiatedByUserId){
        this.initiatedByUserId = initiatedByUserId;
    }
}