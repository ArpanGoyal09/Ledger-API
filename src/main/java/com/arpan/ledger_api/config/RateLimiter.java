package com.arpan.ledger_api.config;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class RateLimiter {
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window{
        private int count;
        private Instant resetAt;

        Window(Instant resetAt){
            this.count = 1;
            this.resetAt = resetAt;
        }
    }

    public boolean tryAcquire(String key){
        Instant now = Instant.now();

        Window window = windows.compute(key, (k, existing) -> {
            if(existing == null || now.isAfter(existing.resetAt)){
                return new Window(now.plus(WINDOW));
            }

            existing.count++;
            return existing;
        });

        return window.count <= MAX_ATTEMPTS;
    }

    public long secondsUntilReset(String key){
        Window window = windows.get(key);
        if(window == null){
            return 0;
        }

        long seconds = Duration.between(Instant.now(), window.resetAt).getSeconds();
        return Math.max(seconds, 0);
    }
}
