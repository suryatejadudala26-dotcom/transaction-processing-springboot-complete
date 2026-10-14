package com.example.transactions.entity;


import com.example.transactions.dto.TransactionRequest;
import com.example.transactions.enums.TransactionStatus;
import com.example.transactions.enums.TransactionType;
import com.example.transactions.util.TransactionRequestUtils;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
@Entity @Table(name="transaction_records", uniqueConstraints={
 @UniqueConstraint(name="uk_business_transaction",columnNames="transaction_id"),
 @UniqueConstraint(name="uk_request_id",columnNames="request_id"),
 @UniqueConstraint(name="uk_account_currency_sequence",columnNames={"account_id","currency","sequence_number"})
})
public class TransactionRecord {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="transaction_id",nullable=false,unique=true,length=100) private String transactionId;
 @Column(name="request_id",nullable=false,unique=true,length=100) private String requestId;
 @Column(nullable=false,length=100) private String accountId;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private TransactionType transactionType;
 @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
 @Column(nullable=false,length=3) private String currency;
 @Column(name="sequence_number",nullable=false) private Long sequenceNumber;
 @Column(name="original_transaction_id",length=100) private String originalTransactionId;
 @Column(nullable=false) private boolean simulateTransientFailure;
 @Column(nullable=false) private boolean simulatePersistentFailure;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private TransactionStatus status;
 @Column(nullable=false) private int retryCount;
 @Column(length=500) private String failureReason;
 @Column(nullable=false) private Instant createdAt;
 @Column(nullable=false) private Instant updatedAt;
 protected TransactionRecord(){}
 public TransactionRecord(TransactionRequest r){
  transactionId=r.transactionId();requestId=r.requestId();accountId=r.accountId();transactionType=r.transactionType();
  amount=r.amount();currency=r.currency();sequenceNumber=r.sequenceNumber();
  originalTransactionId=TransactionRequestUtils.normalizeOptionalId(r.originalTransactionId());simulateTransientFailure=r.simulateTransientFailure();
  simulatePersistentFailure=r.simulatePersistentFailure();status=TransactionStatus.RECEIVED;retryCount=0;createdAt=updatedAt=Instant.now();
 }
 public void setStatus(TransactionStatus s){status=s;updatedAt=Instant.now();}
 public void incrementRetry(){retryCount++;updatedAt=Instant.now();}
 public void setFailureReason(String s){failureReason=s;updatedAt=Instant.now();}
 public void clearFailure(){failureReason=null;updatedAt=Instant.now();}
 public Long getId(){return id;} public String getTransactionId(){return transactionId;} public String getRequestId(){return requestId;}
 public String getAccountId(){return accountId;} public TransactionType getTransactionType(){return transactionType;}
 public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;} public Long getSequenceNumber(){return sequenceNumber;}
 public String getOriginalTransactionId(){return originalTransactionId;} public boolean isSimulateTransientFailure(){return simulateTransientFailure;}
 public boolean isSimulatePersistentFailure(){return simulatePersistentFailure;} public TransactionStatus getStatus(){return status;}
 public int getRetryCount(){return retryCount;} public String getFailureReason(){return failureReason;}
 public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
