package com.example.transactions.dto;

import com.example.transactions.enums.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record TransactionRequest(
 @NotBlank @Size(max=100) String transactionId,
 @NotBlank @Size(max=100) String requestId,
 @NotBlank @Size(max=100) String accountId,
 @NotNull TransactionType transactionType,
 @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
 @NotBlank @Pattern(regexp="[A-Z]{3}",message="currency must be a 3-letter uppercase code") String currency,
 @NotNull @Positive Long sequenceNumber,
 String originalTransactionId,
 boolean simulateTransientFailure,
 boolean simulatePersistentFailure
) {}
