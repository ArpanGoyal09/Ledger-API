package com.arpan.ledger_api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RateLimitFilter extends OncePerRequestFilter{
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String LOGIN_PATH = "/api/auth/login";

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter){
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException{
        if(!LOGIN_PATH.equals(request.getRequestURI()) || !"POST".equals(request.getMethod())){
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);

        if(!rateLimiter.tryAcquire(clientIp)){
            long retryAfter = rateLimiter.secondsUntilReset(clientIp);
            log.warn("Rate limit exceeded for {} on {}", clientIp, LOGIN_PATH);

            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("""
            {"error":"RATE_LIMIT_EXCEEDED",\
            "message":"Too many login attempts. Try again later.",\
            "details":{"retryAfterSeconds":%d}}"""
            .formatted(retryAfter));
            response.getWriter().flush();
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}
