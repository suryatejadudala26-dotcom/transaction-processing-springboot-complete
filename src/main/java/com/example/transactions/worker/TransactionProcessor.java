package com.example.transactions.worker;


import com.example.transactions.service.TransactionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TransactionProcessor {
    private final TransactionService service;
    public TransactionProcessor(TransactionService service) { this.service=service; }

    @Scheduled(fixedDelayString="${app.processor.fixed-delay-ms:2000}")
    public void runBatch() { service.processPending(); }
}
