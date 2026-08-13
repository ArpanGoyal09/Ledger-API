package com.arpan.ledger_api.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "transfers")

public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false)
    private User initiatedBy;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Transfer(){

    }

    public Transfer(User initiatedBy, long amountMinor, String description){
        this.initiatedBy = initiatedBy;
        this.amountMinor = amountMinor;
        this.description = description;
        this.status = TransferStatus.PENDING;
    }

    public void markCompleted(){
        if(this.status != TransferStatus.PENDING){
            throw new IllegalStateException("Cannot complete transfer " + id + " from status " + this.status);
        }

        this.status = TransferStatus.COMPLETED;
    }

    public void markFailed(){
        if(this.status != TransferStatus.PENDING){
            throw new IllegalStateException("Cannot fail transfer " + id + " from status " + this.status);
        }

        this.status = TransferStatus.FAILED;
    }


    public Long getId() { return id; }
    public User getInitiatedBy() { return initiatedBy; }
    public Long getAmountMinor() { return amountMinor; }
    public String getDescription() { return description; }
    public TransferStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof Transfer other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode(){
        return getClass().hashCode();
    }
}
