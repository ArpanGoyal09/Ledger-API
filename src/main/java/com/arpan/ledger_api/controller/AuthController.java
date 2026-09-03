package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.dto.*;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.service.*;
import org.springframework.web.bind.annotation.*;
import com.arpan.ledger_api.config.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final PinService pinService;

    public AuthController(AuthService authService, PinService pinService){
        this.authService = authService;
        this.pinService = pinService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request){
        User user = authService.register(request.getUsername(), request.getEmail(), request.getPassword());

        return Map.of("id", user.getId(), "username", user.getUsername(), "email", user.getEmail());
    }
    
    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest request){
        String token = authService.login(request.getUsername(), request.getPassword());
        return Map.of("token", token, "type", "Bearer");
    }

        @PostMapping("/pin")
    public Map<String, String> setPin(@RequestBody SetPinRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        pinService.setPin(user.userId(), request.getPassword(), request.getPin());
        return Map.of("status", "PIN set");
    }
}
