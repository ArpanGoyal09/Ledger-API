package com.arpan.ledger_api.model;

import com.arpan.ledger_api.exception.InsufficientFundsException;
import jakarta.persistence.*;
import java.time.OffsetDateTime;


@Entity
@Table(name = "accounts")

public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "balance_minor", nullable = false)
    private Long balanceMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10)
    private AccountType accountType;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    protected Account(){

    }

    public Account(User user, String accountNumber, String currency){
        this.user = user;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.accountType = AccountType.CUSTOMER;
        this.balanceMinor = 0L;
    }

    public void credit(long amountMinor){
        if(amountMinor <= 0){
            throw new IllegalArgumentException("Credit Amount must be positive");
        }
        this.balanceMinor += amountMinor;
    }

    public void debit(long amountMinor){
        if(amountMinor <= 0){
            throw new IllegalArgumentException("Debit Amount must be positive");
        }

        if(this.balanceMinor < amountMinor){
            throw new InsufficientFundsException(this.id, this.balanceMinor, amountMinor);
        }
        this.balanceMinor -= amountMinor;
    }

    public Long getId() { return id; }
    public User getUser() { return user;}
    public String getAccountNumber() { return accountNumber; }
    public String getCurrency() { return currency; }
    public Long getBalanceMinor() { return balanceMinor; }
    public AccountType getAccountType() { return accountType; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof Account other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode(){
        return getClass().hashCode();
    }

}
