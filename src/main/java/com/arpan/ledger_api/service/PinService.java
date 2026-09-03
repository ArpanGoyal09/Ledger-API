package com.arpan.ledger_api.service;

import com.arpan.ledger_api.model.*;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.arpan.ledger_api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service 
public class PinService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public PinService(UserRepository userRepository, PasswordEncoder passwordEncoder){
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void setPin(Long userId, String rawPassword, String rawPin){
        if(rawPin == null || !rawPin.matches("\\d{4, 6}")){
            throw new IllegalArgumentException("PIN must be  4 to 6 digits");
        }

        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found " + userId));

        if(!passwordEncoder.matches(rawPassword, user.getPasswordHash())){
            throw new BadCredentialsException("Password is incorrect");
        }

        user.setPinHash(passwordEncoder.encode(rawPin));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long userId){
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found " + userId));

        int remaining = user.recordFailedPinAttempt();
        userRepository.saveAndFlush(user);
        return remaining;
    }

    @Transactional
    public void recordSuccess(Long userId){
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found " + userId));

        if(user.getFailedPinAttempts() > 0 || user.getLockedUntil() != null){
            user.resetPinAttempts();
            userRepository.saveAndFlush(user);
        }
    }

}
