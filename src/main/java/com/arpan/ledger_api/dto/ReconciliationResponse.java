package com.arpan.ledger_api.dto;

import com.arpan.ledger_api.model.*;

import java.math.BigDecimal;

public class ReconciliationResponse{
    private final Long accountId;
    private final String accountNumber;
    private final long storedBalanceMinor;
    private final long derivedBalanceMinor;
    private final long driftMinor;
    private final boolean balanced;

    public ReconciliationResponse(Long accountId, String accountNumber, long storedBalanceMinor, long derivedBalanceMinor, long driftMinor, boolean balanced){
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.storedBalanceMinor = storedBalanceMinor;
        this.derivedBalanceMinor = derivedBalanceMinor;
        this.driftMinor = driftMinor;
        this.balanced = balanced;
    }

    public static ReconciliationResponse of(Long accountId, String accountNumber, long storedBalanceMinor, long derivedBalanceMinor){
        long drift = storedBalanceMinor - derivedBalanceMinor;
        return new ReconciliationResponse(accountId, accountNumber, storedBalanceMinor, derivedBalanceMinor, drift, drift == 0);
    }

    public Long getAccountId(){ return accountId; }
    public String getAccountNumber(){ return accountNumber; }
    public long getStoredBalanceMinor(){ return storedBalanceMinor; }
    public long getDerivedBalanceMinor(){ return derivedBalanceMinor; }
    public long getDriftMinor(){ return driftMinor; }
    public boolean isBalanced(){ return balanced; }

}