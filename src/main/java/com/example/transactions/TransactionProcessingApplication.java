package com.example.transactions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransactionProcessingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionProcessingApplication.class, args);
    }
}
