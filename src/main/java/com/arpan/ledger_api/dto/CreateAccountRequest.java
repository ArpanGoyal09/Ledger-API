package com.arpan.ledger_api.dto;

public class CreateAccountRequest {
    private Long userId;
    private String accountNumber;
    private String currency;

    public Long getUserId(){ return userId; }
    public void setUserId(Long userId){ this.userId = userId; }

    public String getAccountNumber(){ return accountNumber; }
    public void setAccountNumber(String accountNumber){ this.accountNumber = accountNumber; }

    public String getCurrency(){ return currency; }
    public void setCurrency(String currency){ this.currency = currency; }
}
