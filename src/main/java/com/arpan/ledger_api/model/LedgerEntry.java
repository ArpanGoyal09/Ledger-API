package com.arpan.ledger_api.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ledger_entries")

public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private Transfer transfer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    protected LedgerEntry(){

    }

    private LedgerEntry(Transfer transfer, Account account, long amountMinor){
        if(amountMinor == 0){
            throw new IllegalArgumentException("Ledger entry amount cannot be zero");
        }

        this.account = account;
        this.transfer = transfer;
        this.amountMinor = amountMinor;
    }

    public static LedgerEntry debit(Transfer transfer, Account account, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive");
        }
        return new LedgerEntry(transfer, account, -amountMinor);
    }

    public static LedgerEntry credit(Transfer transfer, Account account, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        return new LedgerEntry(transfer, account, amountMinor);
    }

    public Long getId() { return id; }
    public Transfer getTransfer() { return transfer; }
    public Account getAccount() { return account; }
    public Long getAmountMinor() { return amountMinor; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof LedgerEntry other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode(){
        return getClass().hashCode();
    }
}
