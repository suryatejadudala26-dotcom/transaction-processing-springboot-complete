package com.example.transactions;

import com.example.transactions.dto.TransactionRequest;
import com.example.transactions.enums.TransactionStatus;
import com.example.transactions.enums.TransactionType;
import com.example.transactions.repository.AccountBalanceRepository;
import com.example.transactions.repository.DuplicateAttemptRepository;
import com.example.transactions.repository.TransactionRepository;
import com.example.transactions.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@AutoConfigureMockMvc
@SpringBootTest(properties={
 "app.processor.fixed-delay-ms=60000",
 "spring.datasource.url=jdbc:h2:mem:transaction-tests;DB_CLOSE_DELAY=-1",
 "spring.jpa.hibernate.ddl-auto=create-drop",
 "app.processor.max-retries=3"
})
class TransactionServiceTest {
 @Autowired TransactionService service;
 @Autowired TransactionRepository repository;
 @Autowired AccountBalanceRepository balances;
 @Autowired DuplicateAttemptRepository duplicateAttempts;
 @Autowired MockMvc mockMvc;
 private String id(String p){return p+"-"+UUID.randomUUID();}
 private TransactionRequest req(String txn,String request,String account,long seq,TransactionType type,String amount,boolean transientFail,boolean persistentFail){
  return new TransactionRequest(txn,request,account,type,new BigDecimal(amount),"INR",seq,null,transientFail,persistentFail);
 }
 @BeforeEach void clean(){duplicateAttempts.deleteAll();repository.deleteAll();balances.deleteAll();}

 @Test void duplicateSubmissionIsAuditedAndOnlyAppliedOnce(){
  String txn=id("DUP"),account=id("ACC");
  var first=req(txn,id("REQ"),account,1,TransactionType.CREDIT,"100",false,false);
  assertThat(service.submit(first).duplicate()).isFalse();
  var replay=req(txn,id("REQ"),account,1,TransactionType.CREDIT,"100",false,false);
  assertThat(service.submit(replay).status()).isEqualTo(TransactionStatus.DUPLICATE);
  assertThat(service.processPending()).isEqualTo(1);
  assertThat(service.processPending()).isZero();
  assertThat(balances.findByAccountIdAndCurrency(account,"INR").orElseThrow().getBalance()).isEqualByComparingTo("100");
  assertThat(service.summary().get("DUPLICATE_ATTEMPTS")).isEqualTo(1L);
 }
 @Test void replayingAnAlreadyAuditedDuplicateRemainsIdempotent(){
  String txn=id("LATE-DUP"), account=id("ACC");
  var original=req(txn,id("ORIGINAL-REQ"),account,1,TransactionType.CREDIT,"15",false,false);
  service.submit(original);
  assertThat(service.processPending()).isEqualTo(1);

  var duplicate=req(txn,id("DUP-REQ"),account,1,TransactionType.CREDIT,"15",false,false);
  assertThat(service.submit(duplicate).duplicate()).isTrue();
  assertThat(service.submit(duplicate).duplicate()).isTrue();
  assertThat(service.get(txn).getStatus()).isEqualTo(TransactionStatus.PROCESSED);
  assertThat(balances.findByAccountIdAndCurrency(account,"INR").orElseThrow().getBalance()).isEqualByComparingTo("15");
  assertThat(service.summary().get("DUPLICATE_ATTEMPTS")).isEqualTo(2L);

  var changedPayload=req(txn,duplicate.requestId(),account,1,TransactionType.CREDIT,"99",false,false);
  assertThatThrownBy(()->service.submit(changedPayload)).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("requestId has already been used");
 }

 @Test void replayingSameRequestIdIsIdempotent(){
  String txn=id("SAME-REQ"), account=id("ACC"), requestId=id("REQ");
  var request=req(txn,requestId,account,1,TransactionType.CREDIT,"12",false,false);
  assertThat(service.submit(request).duplicate()).isFalse();
  var replay=service.submit(request);
  assertThat(replay.duplicate()).isTrue();
  assertThat(replay.status()).isEqualTo(TransactionStatus.DUPLICATE);
  assertThat(service.processPending()).isEqualTo(1);
  assertThat(balances.findByAccountIdAndCurrency(account,"INR").orElseThrow().getBalance()).isEqualByComparingTo("12");
 }

 @Test void transientFailureIsNotRetriedRepeatedlyInOneBatch(){
  String failedTxn=id("TRANSIENT"), goodTxn=id("GOOD");
  String failedAccount=id("ACC"), goodAccount=id("ACC");
  service.submit(req(failedTxn,id("R1"),failedAccount,1,TransactionType.CREDIT,"5",true,false));
  service.submit(req(goodTxn,id("R2"),goodAccount,1,TransactionType.CREDIT,"7",false,false));
  assertThat(service.processPending()).isEqualTo(1);
  assertThat(service.get(failedTxn).getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
  assertThat(service.get(failedTxn).getRetryCount()).isEqualTo(1);
  assertThat(service.processPending()).isEqualTo(1);
  assertThat(service.get(failedTxn).getStatus()).isEqualTo(TransactionStatus.PROCESSED);
  assertThat(balances.findByAccountIdAndCurrency(failedAccount,"INR").orElseThrow().getBalance()).isEqualByComparingTo("5");
 }

 @Test void outOfOrderSequenceWaitsThenBothProcessInSameRun(){
  String account=id("ACC"),suffix=id("ORDER"),t2="T2-"+suffix,t1="T1-"+suffix;
  service.submit(req(t2,id("R2"),account,2,TransactionType.CREDIT,"2",false,false));
  service.processPending();
  assertThat(service.get(t2).getStatus()).isEqualTo(TransactionStatus.PENDING);
  service.submit(req(t1,id("R1"),account,1,TransactionType.CREDIT,"1",false,false));
  assertThat(service.processPending()).isEqualTo(2);
  assertThat(service.get(t1).getStatus()).isEqualTo(TransactionStatus.PROCESSED);
  assertThat(service.get(t2).getStatus()).isEqualTo(TransactionStatus.PROCESSED);
  assertThat(balances.findByAccountIdAndCurrency(account,"INR").orElseThrow().getBalance()).isEqualByComparingTo("3");
 }
 @Test void transientFailureRetriesAndSucceedsWithoutDuplicateEffect(){
  String txn=id("RETRY"),account=id("ACC");
  service.submit(req(txn,id("R"),account,1,TransactionType.CREDIT,"25",true,false));
  assertThat(service.processPending()).isZero();
  assertThat(service.get(txn).getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
  assertThat(service.get(txn).getRetryCount()).isEqualTo(1);
  assertThat(service.processPending()).isEqualTo(1);
  assertThat(service.get(txn).getStatus()).isEqualTo(TransactionStatus.PROCESSED);
  assertThat(balances.findByAccountIdAndCurrency(account,"INR").orElseThrow().getBalance()).isEqualByComparingTo("25");
 }
 @Test void persistentFailureExhaustsRetryLimit(){
  String txn=id("FAIL"),account=id("ACC");
  service.submit(req(txn,id("R"),account,1,TransactionType.CREDIT,"25",false,true));
  for(int i=0;i<5;i++)service.processPending();
  assertThat(service.get(txn).getStatus()).isEqualTo(TransactionStatus.FAILED);
  assertThat(service.get(txn).getRetryCount()).isEqualTo(3);
  assertThat(balances.findByAccountIdAndCurrency(account,"INR")).isEmpty();
 }
 @Test void insufficientFundsDebitFailsWithoutBalanceMutation(){
  String txn=id("DEBIT"),account=id("ACC");
  service.submit(req(txn,id("R"),account,1,TransactionType.DEBIT,"10",false,false));
  service.processPending();
  assertThat(service.get(txn).getStatus()).isEqualTo(TransactionStatus.FAILED);
  assertThat(service.get(txn).getFailureReason()).contains("Insufficient funds");
  assertThat(balances.findByAccountIdAndCurrency(account,"INR")).isEmpty();
 }
 @Test void reversalOffsetsOriginalAndSecondReversalFails(){
  String account=id("ACC"),original=id("ORIG"),reversal=id("REV");
  service.submit(req(original,id("R1"),account,1,TransactionType.CREDIT,"30",false,false));service.processPending();
  service.submit(new TransactionRequest(reversal,id("R2"),account,TransactionType.REVERSAL,new BigDecimal("30"),"INR",2L,original,false,false));
  service.processPending();
  assertThat(service.get(reversal).getStatus()).isEqualTo(TransactionStatus.PROCESSED);
  assertThat(balances.findByAccountIdAndCurrency(account,"INR").orElseThrow().getBalance()).isEqualByComparingTo("0");
  String second=id("REV2");
  service.submit(new TransactionRequest(second,id("R3"),account,TransactionType.REVERSAL,new BigDecimal("30"),"INR",3L,original,false,false));
  service.processPending();
  assertThat(service.get(second).getStatus()).isEqualTo(TransactionStatus.FAILED);
 }
 @Test void requestIdCannotBeReused(){
  String account=id("ACC"),request=id("REQ");
  service.submit(req(id("A"),request,account,1,TransactionType.CREDIT,"10",false,false));
  assertThatThrownBy(()->service.submit(req(id("B"),request,account,2,TransactionType.CREDIT,"10",false,false)))
   .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
 }
 @Test void transactionReplayWithDifferentPayloadIsRejected(){
  String txn=id("TX"),account=id("ACC");
  service.submit(req(txn,id("R1"),account,1,TransactionType.CREDIT,"10",false,false));
  assertThatThrownBy(()->service.submit(req(txn,id("R2"),account,1,TransactionType.CREDIT,"99",false,false)))
   .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different transaction data");
 }

 @Test void malformedNegativeAmountIsRejectedAtHttpBoundary() throws Exception {
  String json = """
   {"transactionId":"BAD-1","requestId":"BAD-REQ-1","accountId":"BAD-ACC",
    "transactionType":"CREDIT","amount":-10,"currency":"INR","sequenceNumber":1}
   """;
  mockMvc.perform(post("/api/transactions").contentType("application/json").content(json))
   .andExpect(status().isBadRequest());

  // A rejected HTTP record is not persisted and does not prevent unrelated valid work.
  String validTxn = id("VALID-AFTER-INVALID"), account = id("ACC");
  service.submit(req(validTxn, id("REQ"), account, 1, TransactionType.CREDIT, "8", false, false));
  assertThat(service.processPending()).isEqualTo(1);
  assertThat(service.get(validTxn).getStatus()).isEqualTo(TransactionStatus.PROCESSED);
 }

 @Test void duplicateSequenceForDifferentTransactionIsRejected(){
  String account=id("ACC");
  service.submit(req(id("T1"),id("R1"),account,1,TransactionType.CREDIT,"10",false,false));
  assertThatThrownBy(()->service.submit(req(id("T2"),id("R2"),account,1,TransactionType.CREDIT,"20",false,false)))
   .isInstanceOf(IllegalArgumentException.class);
 }
}
