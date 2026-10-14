package com.example.transactions.service;


import com.example.transactions.dto.SubmissionResult;
import com.example.transactions.dto.TransactionRequest;
import com.example.transactions.entity.DuplicateAttempt;
import com.example.transactions.entity.TransactionRecord;
import com.example.transactions.enums.TransactionStatus;
import com.example.transactions.repository.AccountBalanceRepository;
import com.example.transactions.repository.DuplicateAttemptRepository;
import com.example.transactions.repository.TransactionRepository;
import com.example.transactions.worker.TransactionWorker;
import com.example.transactions.util.TransactionRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TransactionService {
 private static final Logger log=LoggerFactory.getLogger(TransactionService.class);
 private final TransactionRepository transactions;private final AccountBalanceRepository balances;
 private final DuplicateAttemptRepository duplicateAttempts;private final TransactionWorker worker;
 public TransactionService(TransactionRepository transactions,AccountBalanceRepository balances,DuplicateAttemptRepository duplicateAttempts,
                           TransactionWorker worker){
  this.transactions=transactions;this.balances=balances;this.duplicateAttempts=duplicateAttempts;this.worker=worker;
 }
 @Transactional
 public SubmissionResult submit(TransactionRequest request){
  Optional<TransactionRecord> byRequestId = transactions.findByRequestId(request.requestId());
  if (byRequestId.isPresent()) {
   TransactionRecord priorRequest = byRequestId.get();
   if (!priorRequest.getTransactionId().equals(request.transactionId()) || !sameBusinessRequest(priorRequest, request)) {
    throw new IllegalArgumentException("requestId has already been used for a different request");
   }
   duplicateAttempts.saveAndFlush(new DuplicateAttempt(request.transactionId(), request.requestId(),
       "Exact request replay; canonical request was not re-applied"));
   log.info("Idempotent request replay transactionId={} requestId={}", request.transactionId(), request.requestId());
   return new SubmissionResult(priorRequest.getTransactionId(), TransactionStatus.DUPLICATE, true,
       "Request replay detected; the original request and business effect are authoritative");
  }

  // A request ID used by a previously audited duplicate must itself remain idempotent.
  Optional<DuplicateAttempt> priorDuplicateAttempt = duplicateAttempts.findFirstByRequestIdOrderByReceivedAtAsc(request.requestId());
  if (priorDuplicateAttempt.isPresent()) {
   DuplicateAttempt prior = priorDuplicateAttempt.get();
   Optional<TransactionRecord> canonical = transactions.findByTransactionId(request.transactionId());
   if (!prior.getTransactionId().equals(request.transactionId()) || canonical.isEmpty()
       || !sameBusinessRequest(canonical.get(), request)) {
    throw new IllegalArgumentException("requestId has already been used for a different request");
   }
   duplicateAttempts.saveAndFlush(new DuplicateAttempt(request.transactionId(), request.requestId(),
       "Replay of a previously audited duplicate; canonical transaction was not re-applied"));
   return new SubmissionResult(request.transactionId(), TransactionStatus.DUPLICATE, true,
       "Duplicate replay detected; original transaction remains authoritative");
  }

  Optional<TransactionRecord> byTransactionId = transactions.findByTransactionId(request.transactionId());
  if (byTransactionId.isPresent()) {
   TransactionRecord canonical = byTransactionId.get();
   if (!sameBusinessRequest(canonical, request)) {
    throw new IllegalArgumentException("transactionId already exists with different transaction data");
   }
   duplicateAttempts.saveAndFlush(new DuplicateAttempt(request.transactionId(), request.requestId(),
       "Repeated business transaction; canonical transaction was not re-applied"));
   log.info("Duplicate business transaction recorded transactionId={} requestId={}", request.transactionId(), request.requestId());
   return new SubmissionResult(request.transactionId(), TransactionStatus.DUPLICATE, true,
       "Duplicate detected; original transaction remains authoritative");
  }

  if (duplicateAttempts.existsByRequestId(request.requestId())) {
   throw new IllegalArgumentException("requestId has already been used");
  }
  try {
   TransactionRecord saved = transactions.saveAndFlush(new TransactionRecord(request));
   log.info("Transaction accepted transactionId={} status={}", saved.getTransactionId(), saved.getStatus());
   return new SubmissionResult(saved.getTransactionId(), saved.getStatus(), false, "Accepted for background processing");
  } catch (org.springframework.dao.DataIntegrityViolationException exception) {
   throw new IllegalArgumentException("Conflicting transaction, requestId, or account/currency sequence already exists");
  }
 }
 private boolean sameBusinessRequest(TransactionRecord r,TransactionRequest q){
  String original=TransactionRequestUtils.normalizeOptionalId(q.originalTransactionId());
  return r.getAccountId().equals(q.accountId())&&r.getTransactionType()==q.transactionType()
   &&r.getAmount().compareTo(q.amount())==0&&r.getCurrency().equals(q.currency())
   &&r.getSequenceNumber().equals(q.sequenceNumber())&&Objects.equals(r.getOriginalTransactionId(),original)
   &&r.isSimulateTransientFailure()==q.simulateTransientFailure()
   &&r.isSimulatePersistentFailure()==q.simulatePersistentFailure();
 }
 @Transactional(readOnly=true) public TransactionRecord get(String id){return transactions.findByTransactionId(id).orElseThrow(()->new NoSuchElementException("Transaction not found: "+id));}
 @Transactional(readOnly=true) public Map<String,Long> summary(){
  Map<String,Long> counts=transactions.findAll().stream().collect(Collectors.groupingBy(r->r.getStatus().name(),TreeMap::new,Collectors.counting()));
  for(TransactionStatus s:TransactionStatus.values())counts.putIfAbsent(s.name(),0L);
  counts.put("DUPLICATE_ATTEMPTS",duplicateAttempts.count());counts.put("TOTAL_SUBMISSIONS",transactions.count()+duplicateAttempts.count());return counts;
 }
 public int processPending(){
  int total = 0;
  int maxPasses = Math.max(1, (int) Math.min(10000, transactions.count() + 1));
  Set<Long> retryAttemptedThisRun = new HashSet<>();
  for (int pass = 0; pass < maxPasses; pass++) {
   List<TransactionRecord> candidates = transactions.findByStatusInOrderByCreatedAtAsc(
       List.of(TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING, TransactionStatus.PENDING));
   int succeeded = 0;
   for (TransactionRecord candidate : candidates) {
    if (retryAttemptedThisRun.contains(candidate.getId())) continue;
    int retriesBefore = candidate.getRetryCount();
    try {
     if (worker.processOne(candidate.getId())) {
      succeeded++;
      total++;
     } else {
      TransactionRecord after = transactions.findById(candidate.getId()).orElse(null);
      // A failed attempt is eligible on the next scheduled/manual run, not repeatedly in this batch.
      if (after != null && after.getRetryCount() > retriesBefore) retryAttemptedThisRun.add(candidate.getId());
     }
    } catch (Exception exception) {
     log.error("Unexpected processing failure transactionId={} category={}",
         candidate.getTransactionId(), exception.getClass().getSimpleName());
     try {
      worker.recordUnexpectedFailure(candidate.getId(), exception.getClass().getSimpleName());
     } catch (Exception persistenceException) {
      log.error("Could not persist failure state transactionId={} category={}",
          candidate.getTransactionId(), persistenceException.getClass().getSimpleName());
     }
     retryAttemptedThisRun.add(candidate.getId());
    }
   }
   // Repeat only when a successful predecessor may have unblocked a pending successor.
   if (succeeded == 0) break;
  }
  return total;
 }
 @Transactional(readOnly=true) public List<Map<String,Object>> balances(){
  return balances.findAll().stream().map(b->Map.<String,Object>of("accountId",b.getAccountId(),"currency",b.getCurrency(),"balance",b.getBalance())).toList();
 }
 @Transactional(readOnly=true) public List<Map<String,Object>> pendingGaps(){
  List<Map<String,Object>> gaps=new ArrayList<>();
  for(TransactionRecord r:transactions.findByStatusInOrderByCreatedAtAsc(List.of(TransactionStatus.PENDING))){
   boolean prior=transactions.existsByAccountIdAndCurrencyAndSequenceNumberAndStatus(r.getAccountId(),r.getCurrency(),r.getSequenceNumber()-1,TransactionStatus.PROCESSED);
   if(!prior)gaps.add(Map.of("transactionId",r.getTransactionId(),"accountId",r.getAccountId(),"currency",r.getCurrency(),"waitingForSequence",r.getSequenceNumber()-1));
  }return gaps;
 }
 @Transactional(readOnly=true) public List<DuplicateAttempt> duplicateAttempts(){return duplicateAttempts.findAllByOrderByReceivedAtAsc();}
}
