package com.arpan.ledger_api.exception;

public class InsufficientFundsException  extends RuntimeException{
    private final Long accountId;
    private final Long balanceMinor;
    private final Long requestedMinor;

    public InsufficientFundsException(Long accountId, Long balanceMinor, Long requestedMinor){
        super(String.format("Insufficient funds in account %d: balance %d minor units, requested %d minor units",
            accountId, balanceMinor, requestedMinor
        ));

        this.accountId = accountId;
        this.balanceMinor = balanceMinor;
        this.requestedMinor = requestedMinor;
    }

    public Long getAccountId() { return accountId; }
    public Long getBalanceMinor() { return balanceMinor; }
    public Long getRequestedMinor() { return requestedMinor; }

    public Long getShortfallMinor(){
        return requestedMinor - balanceMinor;
    }

}
