package com.arpan.ledger_api.dto;

import com.arpan.ledger_api.model.Account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class AccountResponse {
    private final Long id;
    private final Long userId;
    private final String accountNumber ;
    private final long balanceMinor;
    private final String balance;
    private final String currency;
    private final OffsetDateTime createdAt;

    public AccountResponse(Long id, Long userId, String accountNumber, long balanceMinor, String balance, String currency, OffsetDateTime createdAt){
        this.id = id;
        this.userId = userId;
        this.accountNumber = accountNumber;
        this.balanceMinor = balanceMinor;
        this.balance = balance;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static AccountResponse from(Account account){
        return new AccountResponse(
            account.getId(),
            account.getUser().getId(),
            account.getAccountNumber(), 
            account.getBalanceMinor(), 
            toDecimalString(account.getBalanceMinor()), 
            account.getCurrency().trim(), 
            account.getCreatedAt());
    }

    private static String toDecimalString(long minorUnits){
        return BigDecimal.valueOf(minorUnits, 2).toPlainString();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getAccountNumber() { return accountNumber; }
    public long getBalanceMinor() { return balanceMinor; }
    public String getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
