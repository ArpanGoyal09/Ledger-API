package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.config.AuthenticatedUser;
import com.arpan.ledger_api.dto.DepositRequest;
import com.arpan.ledger_api.dto.TransferRequest;
import com.arpan.ledger_api.model.Transfer;
import com.arpan.ledger_api.service.TransferService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public Long createTransfer(@RequestBody TransferRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        Transfer transfer = transferService.transfer(
                request.getFromAccountId(),
                request.getToAccountId(),
                request.getAmountMinor(),
                request.getDescription(),
                user.userId(),
                request.getPin());
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