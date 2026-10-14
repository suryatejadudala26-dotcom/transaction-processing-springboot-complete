# Design note — Idempotent Background Transaction Processing

## Scope and assumptions

This is an assessment-scale single-service implementation using Spring Boot, Spring Data JPA and H2. It simulates a replaceable upstream source through a REST API and a scheduled worker. Amounts are decimal values with two fractional digits. Sequence numbers are contiguous, start at 1, and are scoped to `(accountId, currency)`. Negative balances are not allowed. Reversal records reference one successfully processed CREDIT or DEBIT and use the original transaction's account, currency and amount.

## Architecture

1. `controller/TransactionController` exposes ingestion and operational endpoints.
2. `TransactionRequest` validates external input before business processing.
3. `service/TransactionService` handles idempotency, duplicate audit, summaries and batch selection.
4. `TransactionWorker` owns one-record processing in a database transaction.
5. JPA repositories persist canonical requests, duplicate attempts and account balances.
6. `TransactionProcessor` periodically invokes the batch processor; the manual run endpoint supports deterministic demos.

## Idempotency contract

- `transactionId` is the business transaction key.
- A repeat with the same transaction ID and same semantic payload cannot apply the balance effect twice.
- A repeat using the same `requestId` and same transaction payload returns a duplicate response.
- Reusing either identifier with different payload data is rejected.
- A duplicate using a new request ID is written to the duplicate-attempt audit table.
- Database unique constraints are the final integrity guard for transaction ID, request ID and account/currency sequence.

## Ordering

A record with sequence N can run only after sequence N-1 for the same account and currency is `PROCESSED`. Otherwise it remains `PENDING` and is exposed by the pending-gap endpoint. The batch processor repeats passes only when a record succeeds, which allows a predecessor processed later in a pass to unblock a successor. A permanently failed predecessor blocks later records in that stream until operational recovery.

## Atomicity and retry

Each record is locked and processed inside a Spring-managed transaction. The balance mutation and transition to `PROCESSED` commit together or roll back together. Existing balance rows are pessimistically locked; `@Version` provides an additional optimistic concurrency check. A simulated transient failure fails its first attempt, then is eligible on the next scheduler/manual invocation. A persistent failure reaches `FAILED` after the configured retry limit. The processor avoids repeatedly retrying a failed record within the same batch invocation.

`PROCESSING` is an internal state within the atomic database transaction; other clients normally observe the prior committed state or the final committed state, not an intermediate uncommitted value. This avoids committing a processing claim separately from the business effect in this small implementation.

## Status model

- `RECEIVED`: accepted and waiting to be processed.
- `PROCESSING`: currently being handled inside a worker transaction.
- `PROCESSED`: business effect committed.
- `PENDING`: blocked by a missing predecessor sequence.
- `RETRY_PENDING`: processing attempt failed and another attempt is allowed.
- `FAILED`: validation/business rule failed or retry limit exhausted.
- `DUPLICATE`: used in the submission response for replay; the canonical transaction status is not overwritten. Duplicate attempts are separately audited.

Malformed requests are rejected at the API boundary with HTTP 400. Identifier/payload conflicts return HTTP 409. Unknown transaction IDs return HTTP 404.

## Observability

Logs include transaction IDs, status changes, retry counts and failure categories, without logging full customer request payloads. `/api/summary` counts canonical transactions by status and reports duplicate-attempt and total-submission counts. `/api/duplicates`, `/api/balances`, and `/api/processor/pending-gaps` support operational inspection.

## Production hardening

- Replace H2 schema auto-update with PostgreSQL and Flyway/Liquibase migrations.
- Add authentication/authorization, rate limiting, secret management and audit retention.
- Use a queue adapter plus a transactional outbox for downstream effects.
- Add exponential backoff with jitter, dead-letter/replay operations and alerting.
- Add concurrency/load tests against the intended production database, especially for hot accounts and simultaneous idempotency-key races.
- Add metrics and distributed tracing, and define recovery procedures for failed predecessors.
- Review the accounting semantics and legal/audit requirements before using this pattern for real financial transactions.

## AI-assisted development disclosure

AI assistance may accelerate scaffolding and test generation, but the candidate must personally review the code, run the tests, understand transaction boundaries and concurrency trade-offs, and be able to explain or modify the design. Do not claim that tests passed unless they have been run successfully in the submission environment.


## Package structure

- `controller`: REST API endpoints.
- `service`: application and transaction business workflows.
- `repository`: Spring Data persistence queries.
- `entity`: persisted JPA entities.
- `dto`: inbound requests and API submission results.
- `enums`: transaction type and lifecycle status.
- `worker`: background processing and scheduled batch trigger.
- `exception`: API exception-to-response handling.
- `util`: small shared normalization helpers.

The main application class stays in `com.example.transactions` so Spring Boot component scanning includes all subpackages.
