package com.arpan.ledger_api.exception;

public class PinException extends RuntimeException{
    private final Integer attemptsRemaining;
    private final boolean locked;

    private PinException(String message, Integer attemptsRemaining, boolean locked){
        super(message);
        this.attemptsRemaining = attemptsRemaining;
        this.locked = locked;
    }

    public static PinException notSet() {
        return new PinException(
                "No transaction PIN set. Set a PIN before making transfers.", null, false);
    }

    public static PinException missing() {
        return new PinException("Transaction PIN is required", null, false);
    }

    public static PinException incorrect(int attemptsRemaining) {
        return new PinException("Incorrect PIN", attemptsRemaining, false);
    }

    public static PinException locked() {
        return new PinException(
                "Too many incorrect PIN attempts. Try again later.", 0, true);
    }

    public Integer getAttemptsRemaining() { return attemptsRemaining; }
    public boolean isLocked() { return locked; }

}
