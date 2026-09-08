package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.config.AuthenticatedUser;
import com.arpan.ledger_api.dto.DepositRequest;
import com.arpan.ledger_api.dto.TransferRequest;
import com.arpan.ledger_api.model.Transfer;
import com.arpan.ledger_api.service.TransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Transfers", description = "Money movement between accounts, and external deposits")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }
    
    @Operation(summary = "Transfer money between accounts",
            description = """
                    Requires ownership of the source account, a valid transaction PIN, and an \
                    Idempotency-Key header. A retry with the same key returns the original \
                    transfer without moving money again.""")
    

    @PostMapping
    public Long createTransfer(@RequestBody TransferRequest request, 
                            @RequestHeader(value = "Idempotency-Key", required = false)
                            @Parameter(required = true, description = "Unique Key per logical transfer." + "Retries must reuse the same.")
                            String idempotencyKey, 
                            @AuthenticationPrincipal AuthenticatedUser user) {
        Transfer transfer = transferService.transfer(
                request.getFromAccountId(),
                request.getToAccountId(),
                request.getAmountMinor(),
                request.getDescription(),
                user.userId(),
                request.getPin(),
                idempotencyKey);
        return transfer.getId();
    }

    @PostMapping("/deposits")
    public Long createDeposit(@RequestBody DepositRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        Transfer transfer = transferService.deposit(
                request.getToAccountId(),
                request.getAmountMinor(),
                request.getDescription());
        return transfer.getId();
    }
}