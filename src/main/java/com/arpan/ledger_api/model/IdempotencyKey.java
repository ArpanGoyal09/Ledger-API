package com.arpan.ledger_api.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity 
@Table(name = "idempotency_keys")

public class IdempotencyKey {
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private Transfer transfer;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKey(){

    }

    public IdempotencyKey(User user, String idempotencyKey, String requestHash, Transfer transfer){
        this.user = user;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.transfer = transfer;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Transfer getTransfer() { return transfer; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o){
        if(this == o){
            return true;
        }

        if(!(o instanceof IdempotencyKey other)){
            return false;
        }

        return id != null && id.equals(other.id);
    }

    @Override 
    public int hashCode(){
        return getClass().hashCode();
    }

}
