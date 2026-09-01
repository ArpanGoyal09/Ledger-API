package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.model.Account;
import com.arpan.ledger_api.model.User;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.LedgerEntryRepository;
import com.arpan.ledger_api.repository.TransferRepository;
import com.arpan.ledger_api.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private Long userId;
    private Long fromId;
    private Long toId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        String shortSuffix = suffix.substring(suffix.length() - 8);

        User user = userRepository.save(
                new User("c" + suffix, "c" + suffix + "@example.com", "hash"));
        userId = user.getId();

        Account from = accountRepository.save(new Account(user, "CF" + shortSuffix, "INR"));
        Account to = accountRepository.save(new Account(user, "CT" + shortSuffix, "INR"));

        from.credit(100000);
        accountRepository.saveAndFlush(from);

        fromId = from.getId();
        toId = to.getId();
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    void validTransferReturns200() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "fromAccountId", fromId,
                                "toAccountId", toId,
                                "amountMinor", 30000,
                                "description", "controller test",
                                "initiatedByUserId", userId))))
                .andExpect(status().isOk());
    }

    @Test
    void overdraftReturns400WithStructuredError() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "fromAccountId", fromId,
                                "toAccountId", toId,
                                "amountMinor", 500000,
                                "description", "too much",
                                "initiatedByUserId", userId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.details.shortfallMinor").value(400000));
    }

    @Test
    void missingBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountBalanceIsReturnedInBothForms() throws Exception {
        mockMvc.perform(get("/api/accounts/" + fromId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(100000))
                .andExpect(jsonPath("$.balance").value("1000.00"))
                .andExpect(jsonPath("$.accountNumber").exists());
    }

    @Test
    void reconcileReportsDriftForUnbackedBalance() throws Exception {
        mockMvc.perform(get("/api/accounts/" + fromId + "/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.driftMinor").value(100000));
    }

    @Autowired private TransferRepository transferRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    @AfterEach
    void tearDown(){
        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(fromId));

        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(toId));

        transferRepository.deleteAll(transferRepository.findByInitiatedByIdOrderByCreatedAtDesc(userId));

        accountRepository.deleteById(fromId);
        accountRepository.deleteById(toId);
        userRepository.deleteById(userId);

    }

}