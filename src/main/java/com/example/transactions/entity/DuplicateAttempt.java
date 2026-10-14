package com.example.transactions.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "duplicate_attempts")
public class DuplicateAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, length = 100)
    private String transactionId;

    // A request may be replayed more than once, so the audit table intentionally permits repeated request IDs.
    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = 250)
    private String reason;

    protected DuplicateAttempt() { }

    public DuplicateAttempt(String transactionId, String requestId, String reason) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.reason = reason;
        this.receivedAt = Instant.now();
    }

    public String getTransactionId() { return transactionId; }
    public String getRequestId() { return requestId; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getReason() { return reason; }
}
