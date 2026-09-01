package com.arpan.ledger_api.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-ms}") long expirationMs){
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String username){
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder().subject(username).claim("userId", userId).issuedAt(now).expiration(expiry).signWith(signingKey).compact();
    }

    public Claims parseToken(String token){
        try{
            return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
        } catch(JwtException | IllegalArgumentException e){
            return null;
        }
    }

    public Long extractUserId(Claims claims){
        return claims.get("userId", Long.class);
    }

    public String extractUsername(Claims claims){
        return claims.getSubject();
    }
}
