package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.dto.*;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService){
        this.authService = authService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request){
        User user = authService.register(request.getUsername(), request.getEmail(), request.getPassword());

        return Map.of("id", user.getId(), "username", user.getUserName(), "email", user.getEmail());
    }
    
}
