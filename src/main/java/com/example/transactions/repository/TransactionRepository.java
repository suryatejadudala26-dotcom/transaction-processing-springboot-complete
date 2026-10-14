package com.example.transactions.repository;

import com.example.transactions.entity.TransactionRecord;
import com.example.transactions.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface TransactionRepository extends JpaRepository<TransactionRecord,Long> {
 Optional<TransactionRecord> findByTransactionId(String transactionId);
 Optional<TransactionRecord> findByRequestId(String requestId);
 List<TransactionRecord> findByStatusInOrderByCreatedAtAsc(Collection<TransactionStatus> statuses);
 boolean existsByAccountIdAndCurrencyAndSequenceNumberAndStatus(String accountId,String currency,Long sequenceNumber,TransactionStatus status);
 boolean existsByOriginalTransactionIdAndStatus(String originalTransactionId,TransactionStatus status);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from TransactionRecord t where t.id=:id")
 Optional<TransactionRecord> findByIdForUpdate(@Param("id") Long id);
}
