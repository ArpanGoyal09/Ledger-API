package com.arpan.ledger_api.model;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "failed_pin_attempts", nullable = false)
    private int failedPinAttempts;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    protected User(){

    }

    public User(String username, String email, String passwordHash){
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getPinHash() { return pinHash; }
    public int getFailedPinAttempts() { return failedPinAttempts; }
    public OffsetDateTime getLockedUntil() { return lockedUntil; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setPasswordHash(String passwordHash){
        this.passwordHash = passwordHash;
    }

    public void setPinHash(String pinHash){
        this.pinHash = pinHash;
        this.failedPinAttempts = 0;
        this.lockedUntil = null;
    }

    public boolean hasPin(){
        return pinHash != null;
    }

    public boolean isLocked(){
        return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
    }

    private static final int MAX_PIN_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    public int recordFailedPinAttempt(){
        this.failedPinAttempts++;

        if(this.failedPinAttempts >= MAX_PIN_ATTEMPTS){
            this.lockedUntil = OffsetDateTime.now().plus(LOCKOUT_DURATION);
            this.failedPinAttempts = 0;
            return 0;
        }

        return MAX_PIN_ATTEMPTS - this.failedPinAttempts;
    }

    public void resetPinAttempts(){
        this.failedPinAttempts = 0;
        this.lockedUntil = null;
    }



    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode(){
        return getClass().hashCode();
    }
}
