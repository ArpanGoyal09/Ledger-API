package com.arpan.ledger_api.service;

import com.arpan.ledger_api.exception.InsufficientFundsException;
import com.arpan.ledger_api.model.*;
import com.arpan.ledger_api.repository.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TransferServiceConcurrencyTest {
    
    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;

    private static final String PIN = "1234";
    private Long userId;
    private Long accountAId;
    private Long accountBId;

    private String key() {
        return UUID.randomUUID().toString();
    }

    @BeforeEach
    void setUp(){
        String suffix = String.valueOf(System.nanoTime());

        User user = userRepository.save(new User("test_" + suffix, "test_" + suffix + "@example.com", "hash"));
        userId = user.getId();

        user.setPinHash(passwordEncoder.encode(PIN));
        userRepository.saveAndFlush(user);

        Account a = accountRepository.save(new Account(user, "TA" + suffix.substring(suffix.length() - 8), "INR"));
        Account b = accountRepository.save(new Account(user, "TB" + suffix.substring(suffix.length() - 8), "INR"));

        a.credit(50000);
        accountRepository.saveAndFlush(a);

        accountAId = a.getId();
        accountBId = b.getId();
    }

    @Test
    void concurrentTransfersCannotOverdraw() throws Exception{
        int threads = 2;
        long amountEach = 40000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for(int i = 0; i < threads; i++){
            pool.submit(() -> {
                try{
                    startGate.await();
                    transferService.transfer(accountAId, accountBId, amountEach, "concurrent test", userId, PIN, key());
                    succeeded.incrementAndGet();
                } catch(InsufficientFundsException e){
                    rejected.incrementAndGet();
                } catch(Exception e){
                    e.printStackTrace();
                } finally{
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();

        assertEquals(1, succeeded.get(), "exactly one transfer should succeed");
        assertEquals(1, rejected.get(), "exactly one transfer should be rejected");

        Account a = accountRepository.findById(accountAId).orElseThrow();
        assertEquals(10000, a.getBalanceMinor(), "balance must reflect exactly one transfer");
        assertTrue(a.getBalanceMinor() >= 0, "balance must never go negative");

        long derived = ledgerEntryRepository.sumAmountByAccountId(accountAId);
        assertEquals(-40000, derived, "ledger must show exactly one debit");
    }

    @Autowired private TransferRepository transferRepository;

    @AfterEach
    void tearDown(){
        idempotencyKeyRepository.deleteByUserId(userId);
        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountAId));
        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountBId));

        transferRepository.deleteAll(transferRepository.findByInitiatedByIdOrderByCreatedAtDesc(userId));

        accountRepository.deleteById(accountAId);
        accountRepository.deleteById(accountBId);
        userRepository.deleteById(userId);
    }
}
