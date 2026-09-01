package com.arpan.ledger_api.service;

import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.repository.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder){
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String username, String email, String rawPassword){
        if(username == null || username.isBlank()){
            throw new IllegalArgumentException("Username is Required");
        }

        if(email == null || email.isBlank()){
            throw new IllegalArgumentException("Email is Required");
        }

        if(rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH){
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        if(userRepository.existsByUsername(username)){
            throw new IllegalStateException("Username already taken");
        }

        if(userRepository.existsByEmail(email)){
            throw new IllegalStateException("Email already registered");
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        return userRepository.save(new User(username, email, passwordHash));
    }
}
