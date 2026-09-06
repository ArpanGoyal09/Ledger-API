package com.arpan.ledger_api.controller;

import com.arpan.ledger_api.model.Account;
import com.arpan.ledger_api.model.User;
import com.arpan.ledger_api.repository.AccountRepository;
import com.arpan.ledger_api.repository.IdempotencyKeyRepository;
import com.arpan.ledger_api.repository.LedgerEntryRepository;
import com.arpan.ledger_api.repository.TransferRepository;
import com.arpan.ledger_api.repository.UserRepository;
import com.arpan.ledger_api.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerTest {

        private static final String PIN = "1234";

        @Autowired private MockMvc mockMvc;
        @Autowired private JwtService jwtService;
        @Autowired private PasswordEncoder passwordEncoder;
        @Autowired private AccountRepository accountRepository;
        @Autowired private UserRepository userRepository;
        @Autowired private TransferRepository transferRepository;
        @Autowired private LedgerEntryRepository ledgerEntryRepository;
        @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;

        private Long userId;
        private Long fromId;
        private Long toId;
        private String authHeader;

        private String key() {
                return UUID.randomUUID().toString();
        }

        @BeforeEach
        void setUp() {
                String suffix = String.valueOf(System.nanoTime());
                String shortSuffix = suffix.substring(suffix.length() - 8);

                User user = userRepository.save(
                        new User("c" + suffix, "c" + suffix + "@example.com", "hash"));

                user.setPinHash(passwordEncoder.encode(PIN));
                userRepository.saveAndFlush(user);

                userId = user.getId();

                Account from = accountRepository.save(new Account(user, "CF" + shortSuffix, "INR"));
                Account to = accountRepository.save(new Account(user, "CT" + shortSuffix, "INR"));

                from.credit(100000);
                accountRepository.saveAndFlush(from);

                fromId = from.getId();
                toId = to.getId();

                authHeader = "Bearer " + jwtService.generateToken(userId, user.getUsername());
        }

        @AfterEach
        void tearDown() {
                idempotencyKeyRepository.deleteByUserId(userId);
                ledgerEntryRepository.deleteAll(
                        ledgerEntryRepository.findByAccountIdWithTransfer(fromId));
                ledgerEntryRepository.deleteAll(
                        ledgerEntryRepository.findByAccountIdWithTransfer(toId));
                transferRepository.deleteAll(
                        transferRepository.findByInitiatedByIdOrderByCreatedAtDesc(userId));
                accountRepository.deleteById(fromId);
                accountRepository.deleteById(toId);
                userRepository.deleteById(userId);
        }

        @Test
        void validTransferReturns200() throws Exception {
                String payload = """
                        {"fromAccountId":%d,"toAccountId":%d,"amountMinor":30000,
                        "description":"controller test","pin":"%s"}
                        """.formatted(fromId, toId, PIN);

                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isOk());
        }

        @Test
        void overdraftReturns400WithStructuredError() throws Exception {
                String payload = """
                        {"fromAccountId":%d,"toAccountId":%d,"amountMinor":500000,
                        "description":"too much","pin":"%s"}
                        """.formatted(fromId, toId, PIN);

                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                        .andExpect(jsonPath("$.details.shortfallMinor").value(400000));
        }

        @Test
        void transferWithoutPinIsRejected() throws Exception {
                String payload = """
                        {"fromAccountId":%d,"toAccountId":%d,"amountMinor":30000,
                        "description":"no pin"}
                        """.formatted(fromId, toId);

                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error").value("PIN_REQUIRED"));
        }

        @Test
        void transferWithoutIdempotencyKeyIsRejected() throws Exception {
                String payload = """
                        {"fromAccountId":%d,"toAccountId":%d,"amountMinor":30000,
                        "description":"no key","pin":"%s"}
                        """.formatted(fromId, toId, PIN);

                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }

        @Test
        void replayingAnIdempotencyKeyReturnsTheSameTransfer() throws Exception {
                String sharedKey = key();
                String payload = """
                        {"fromAccountId":%d,"toAccountId":%d,"amountMinor":30000,
                        "description":"replay test","pin":"%s"}
                        """.formatted(fromId, toId, PIN);

                String firstId = mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .header("Idempotency-Key", sharedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

                String replayId = mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .header("Idempotency-Key", sharedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

                org.junit.jupiter.api.Assertions.assertEquals(firstId, replayId,
                        "a replay must return the original transfer id");

                mockMvc.perform(get("/api/accounts/" + fromId)
                                .header("Authorization", authHeader))
                        .andExpect(jsonPath("$.balanceMinor").value(70000));
        }

        @Test
        void reusingAKeyWithDifferentParametersReturns422() throws Exception {
                String sharedKey = key();

                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .header("Idempotency-Key", sharedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"fromAccountId":%d,"toAccountId":%d,"amountMinor":30000,
                                        "description":"original","pin":"%s"}
                                        """.formatted(fromId, toId, PIN)))
                        .andExpect(status().isOk());

                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .header("Idempotency-Key", sharedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"fromAccountId":%d,"toAccountId":%d,"amountMinor":50000,
                                        "description":"different","pin":"%s"}
                                        """.formatted(fromId, toId, PIN)))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"));
        }

        @Test
        void missingBodyReturns400() throws Exception {
                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", authHeader)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }

        @Test
        void accountBalanceIsReturnedInBothForms() throws Exception {
                mockMvc.perform(get("/api/accounts/" + fromId)
                                .header("Authorization", authHeader))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.balanceMinor").value(100000))
                        .andExpect(jsonPath("$.balance").value("1000.00"))
                        .andExpect(jsonPath("$.accountNumber").exists());
        }

        @Test
        void reconcileReportsDriftForUnbackedBalance() throws Exception {
                mockMvc.perform(get("/api/accounts/" + fromId + "/reconcile")
                                .header("Authorization", authHeader))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.balanced").value(false))
                        .andExpect(jsonPath("$.driftMinor").value(100000));
        }

        @Test
        void requestWithoutTokenIsRejected() throws Exception {
                mockMvc.perform(get("/api/accounts/" + fromId))
                        .andExpect(status().isForbidden());
        }

        @Test
        void anotherUsersAccountReturns404() throws Exception {
                String suffix = String.valueOf(System.nanoTime());
                User intruder = userRepository.save(
                        new User("i" + suffix, "i" + suffix + "@example.com", "hash"));
                String intruderHeader =
                        "Bearer " + jwtService.generateToken(intruder.getId(), intruder.getUsername());

                mockMvc.perform(get("/api/accounts/" + fromId)
                                .header("Authorization", intruderHeader))
                        .andExpect(status().isNotFound());

                userRepository.deleteById(intruder.getId());
        }
}