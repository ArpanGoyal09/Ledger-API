package com.arpan.ledger_api.exception;

import com.arpan.ledger_api.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.*;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex){
        ErrorResponse body = new ErrorResponse("INSUFFICIENT_FUNDS", ex.getMessage(), Map.of(
            "accountId", ex.getAccountId(),
            "balanceMinor", ex.getBalanceMinor(),
            "requestedMinor", ex.getRequestedMinor(),
            "shortfallMinor", ex.getShortfallMinor()
        ));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex){
        ErrorResponse body = new ErrorResponse("INVALID_REQUEST", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex){
        ErrorResponse body = new ErrorResponse("INVALID_STATE", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        ErrorResponse body = new ErrorResponse("MALFORMED_REQUEST", "Request body is missing or could not be parsed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(BadCredentialsException.class)
        public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex){
            ErrorResponse body = new ErrorResponse("INVALID_CREDENTIALS", ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
    

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex){
        ErrorResponse body = new ErrorResponse("ACCOUNT_NOT_FOUND", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(PinException.class)
    public ResponseEntity<ErrorResponse> handlePin(PinException ex){
        HttpStatus status = ex.isLocked() ? HttpStatus.LOCKED : HttpStatus.FORBIDDEN;
        String code = ex.isLocked() ? "ACCOUNT_LOCKED" : "PIN_REQUIRED";
        Map<String, Object> details = ex.getAttemptsRemaining() == null ? null : Map.of("attemptsRemaining", ex.getAttemptsRemaining());
        return ResponseEntity.status(status).body(new ErrorResponse(code, ex.getMessage(), details));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("IDEMPOTENCY_KEY_REUSED", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyRaceException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyRace(IdempotencyRaceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_IN_PROGRESS", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnxepected(Exception ex){
        log.error("Unhandled exception processing request", ex);
        ErrorResponse body = new ErrorResponse("INTERNAL_ERROR", "An Unexpected Error Occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

}
