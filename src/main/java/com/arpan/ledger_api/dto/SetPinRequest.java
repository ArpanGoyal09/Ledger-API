package com.arpan.ledger_api.dto;

public class SetPinRequest {
    private String password;
    private String pin;

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }
}
