package com.example.transactions.dto;

import com.example.transactions.enums.TransactionStatus;
public record SubmissionResult(String transactionId, TransactionStatus status, boolean duplicate, String message) {}
