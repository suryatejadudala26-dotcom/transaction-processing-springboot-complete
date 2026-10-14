package com.example.transactions.worker;

import com.example.transactions.entity.AccountBalance;
import com.example.transactions.entity.TransactionRecord;
import com.example.transactions.enums.TransactionStatus;
import com.example.transactions.enums.TransactionType;
import com.example.transactions.repository.AccountBalanceRepository;
import com.example.transactions.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class TransactionWorker {
 private static final Logger log=LoggerFactory.getLogger(TransactionWorker.class);
 private final TransactionRepository transactions; private final AccountBalanceRepository balances; private final int maxRetries;
 public TransactionWorker(TransactionRepository transactions,AccountBalanceRepository balances,@Value("${app.processor.max-retries:3}") int maxRetries){
  this.transactions=transactions;this.balances=balances;this.maxRetries=Math.max(1,maxRetries);
 }
 @Transactional
 public boolean processOne(Long id){
  TransactionRecord r=transactions.findByIdForUpdate(id).orElse(null);
  if(r==null||!(r.getStatus()==TransactionStatus.RECEIVED||r.getStatus()==TransactionStatus.RETRY_PENDING||r.getStatus()==TransactionStatus.PENDING))return false;
  if(r.getSequenceNumber()>1&&!transactions.existsByAccountIdAndCurrencyAndSequenceNumberAndStatus(r.getAccountId(),r.getCurrency(),r.getSequenceNumber()-1,TransactionStatus.PROCESSED)){
   r.setStatus(TransactionStatus.PENDING);return false;
  }
  r.setStatus(TransactionStatus.PROCESSING);
  if(r.isSimulatePersistentFailure()||(r.isSimulateTransientFailure()&&r.getRetryCount()==0)){
   r.incrementRetry();r.setFailureReason(r.isSimulatePersistentFailure()?"Simulated persistent failure":"Simulated transient failure");
   r.setStatus(r.getRetryCount()>=maxRetries?TransactionStatus.FAILED:TransactionStatus.RETRY_PENDING);
   log.warn("Processing attempt failed transactionId={} retry={} status={}",r.getTransactionId(),r.getRetryCount(),r.getStatus());return false;
  }
  BigDecimal delta;
  if(r.getTransactionType()==TransactionType.REVERSAL){
   TransactionRecord original=r.getOriginalTransactionId()==null?null:transactions.findByTransactionId(r.getOriginalTransactionId()).orElse(null);
   if(original==null||original.getStatus()!=TransactionStatus.PROCESSED||original.getTransactionType()==TransactionType.REVERSAL
    ||!original.getAccountId().equals(r.getAccountId())||!original.getCurrency().equals(r.getCurrency())
    ||original.getAmount().compareTo(r.getAmount())!=0){
    r.setFailureReason("Reversal requires a processed original transaction with matching account, currency, and amount");r.setStatus(TransactionStatus.FAILED);return false;
   }
   if(transactions.existsByOriginalTransactionIdAndStatus(r.getOriginalTransactionId(),TransactionStatus.PROCESSED)){
    r.setFailureReason("Original transaction has already been reversed");r.setStatus(TransactionStatus.FAILED);return false;
   }
   delta=original.getTransactionType()==TransactionType.CREDIT?r.getAmount().negate():r.getAmount();
  } else delta=r.getTransactionType()==TransactionType.DEBIT?r.getAmount().negate():r.getAmount();

  AccountBalance balance=balances.findWithLockByAccountIdAndCurrency(r.getAccountId(),r.getCurrency())
   .orElseGet(()->new AccountBalance(r.getAccountId(),r.getCurrency()));
  if(balance.getBalance().add(delta).signum()<0){r.setFailureReason("Insufficient funds");r.setStatus(TransactionStatus.FAILED);return false;}
  balance.apply(delta);balances.save(balance);r.clearFailure();r.setStatus(TransactionStatus.PROCESSED);
  log.info("Transaction processed transactionId={} type={}",r.getTransactionId(),r.getTransactionType());return true;
 }

 @Transactional
 public void recordUnexpectedFailure(Long id, String category) {
  TransactionRecord record = transactions.findByIdForUpdate(id).orElse(null);
  if (record == null || record.getStatus() == TransactionStatus.PROCESSED
      || record.getStatus() == TransactionStatus.FAILED) return;
  record.incrementRetry();
  record.setFailureReason("Unexpected processing error (" + category + ")");
  record.setStatus(record.getRetryCount() >= maxRetries
      ? TransactionStatus.FAILED : TransactionStatus.RETRY_PENDING);
 }

}
