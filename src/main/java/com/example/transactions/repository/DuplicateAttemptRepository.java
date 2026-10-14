package com.example.transactions.repository;

import com.example.transactions.entity.DuplicateAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface DuplicateAttemptRepository extends JpaRepository<DuplicateAttempt,Long> {
 boolean existsByRequestId(String requestId);
 Optional<DuplicateAttempt> findFirstByRequestIdOrderByReceivedAtAsc(String requestId);
 List<DuplicateAttempt> findAllByOrderByReceivedAtAsc();
}
