package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.dto.*;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.service.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
    
    private final TransferService transferService;

    public TransferController(TransferService transferService){
        this.transferService = transferService;
    }

    @PostMapping
    public Long createTransfer(@RequestBody TransferRequest request){
        Transfer transfer = transferService.transfer(request.getFromAccountId(), request.getToAccountId(), request.getAmountMinor(), 
        request.getDescription(), request.getInitiatedByUserId());
        
        return transfer.getId();
    }

    @PostMapping("/deposits")
    public Long createDeposit(@RequestBody DepositRequest request) {
        Transfer transfer = transferService.deposit(request.getToAccountId(), request.getAmountMinor(), request.getDescription());
        return transfer.getId();
    }
}
