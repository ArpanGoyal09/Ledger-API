package com.arpan.ledger_api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "service", "ledger-api",
                "status", "up",
                "docs", "https://github.com/ArpanGoyal09/ledger-api");
    }
}