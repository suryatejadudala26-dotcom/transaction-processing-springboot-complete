package com.example.transactions.controller;

import com.example.transactions.dto.SubmissionResult;
import com.example.transactions.dto.TransactionRequest;
import com.example.transactions.entity.DuplicateAttempt;
import com.example.transactions.entity.TransactionRecord;
import com.example.transactions.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api")
public class TransactionController {
 private final TransactionService service;
 public TransactionController(TransactionService service){this.service=service;}
 @PostMapping("/transactions") @ResponseStatus(HttpStatus.ACCEPTED)
 public SubmissionResult submit(@Valid @RequestBody TransactionRequest request){return service.submit(request);}
 @GetMapping("/transactions/{transactionId}") public TransactionRecord get(@PathVariable String transactionId){return service.get(transactionId);}
 @GetMapping("/summary") public Map<String,Long> summary(){return service.summary();}
 @GetMapping("/balances") public List<Map<String,Object>> balances(){return service.balances();}
 @GetMapping("/duplicates") public List<DuplicateAttempt> duplicates(){return service.duplicateAttempts();}
 @GetMapping("/processor/pending-gaps") public List<Map<String,Object>> pendingGaps(){return service.pendingGaps();}
 @PostMapping("/processor/run") public Map<String,Object> runNow(){return Map.of("processedCount",service.processPending(),"summary",service.summary(),"pendingGaps",service.pendingGaps());}
}
