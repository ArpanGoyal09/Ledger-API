package com.arpan.ledger_api.exception;

public class IdempotencyRaceException extends RuntimeException {
    public IdempotencyRaceException(String key) {
        super("A concurrent request with idempotency key '" + key
                + "' is being processed. Retry to retrieve the result.");
    }
}