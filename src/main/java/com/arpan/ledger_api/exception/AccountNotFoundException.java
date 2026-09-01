package com.arpan.ledger_api.exception;

public class AccountNotFoundException extends RuntimeException {
    private final Long accountId;

    public AccountNotFoundException(Long accountId){
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public Long getAccountId(){
        return accountId;
    }
}
