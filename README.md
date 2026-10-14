# Idempotent Background Transaction Processing Service

A Java / Spring Boot implementation of the supplied **Practical FDE Assessment — Idempotent Background Transaction Processing Service**. The project demonstrates idempotent ingestion, duplicate auditing, sequence-based ordering, retry-safe processing, transaction status tracking, basic operational endpoints, and automated tests.

## Prerequisites

- JDK 21 or newer (JDK 25 can compile this project using the Java 21 release target)
- Maven 3.9+
- IntelliJ IDEA (optional)

## Build and run

Run from the project root:

```bash
mvn clean verify
mvn spring-boot:run
```

The API starts at `http://localhost:8080`. H2 persists local demo data in `./data`. The scheduled worker checks for work every 2 seconds by default. To start with a clean demo database, stop the app and remove `./data`.

In IntelliJ IDEA, open this folder as a Maven project, set Project SDK and Maven Runner JRE to JDK 21 or newer, reload Maven, and run `mvn clean verify` in the terminal before submission.

## API endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/transactions` | Validate and accept a transaction; returns HTTP 202 |
| `GET` | `/api/transactions/{transactionId}` | Inspect transaction state and retry/failure details |
| `GET` | `/api/summary` | Counts by status, duplicate attempts, total submissions |
| `GET` | `/api/balances` | Account balances grouped by account and currency |
| `GET` | `/api/duplicates` | Duplicate request audit records |
| `GET` | `/api/processor/pending-gaps` | Missing predecessor sequence information |
| `POST` | `/api/processor/run` | Trigger a processing run immediately |
| `GET` | `/h2-console` | Local H2 console (JDBC URL `jdbc:h2:file:./data/transactions`, user `sa`, blank password) |

## Sample transaction

```json
{
  "transactionId": "TXN-1001",
  "requestId": "REQ-1001",
  "accountId": "ACC-201",
  "transactionType": "CREDIT",
  "amount": 100.00,
  "currency": "INR",
  "sequenceNumber": 1,
  "simulateTransientFailure": false,
  "simulatePersistentFailure": false
}
```

Save the request as `POST http://localhost:8080/api/transactions` with `Content-Type: application/json`. A successful submission returns HTTP 202 and a response similar to:

```json
{
  "transactionId": "TXN-1001",
  "status": "RECEIVED",
  "duplicate": false,
  "message": "Accepted for background processing"
}
```

Call `POST /api/processor/run`, then `GET /api/transactions/TXN-1001`, `GET /api/balances`, and `GET /api/summary` to inspect the result.

### Duplicate behavior

- Re-submit the same transaction with the same request ID and same payload to simulate a retry after a lost response. It returns a duplicate response and records the attempt.
- Re-submit the same business transaction ID with a new request ID and the same business fields. It is also recorded as a duplicate and is not applied twice.
- Reusing an identifier with different payload data returns HTTP 409.

### Retry simulation

Set `simulateTransientFailure` to `true`. The first processing attempt becomes `RETRY_PENDING`; a subsequent scheduled/manual processing run succeeds. Set `simulatePersistentFailure` to `true` to simulate repeated failures; the record reaches `FAILED` after `app.processor.max-retries` attempts (default 3). These flags are demonstration hooks, not production request fields.

### Ordering and reversal

Sequence numbers start at 1 and are scoped to `(accountId, currency)`. Sequence N waits in `PENDING` until N-1 is `PROCESSED`. Reversals use `transactionType: "REVERSAL"` and `originalTransactionId` referencing a successfully processed CREDIT or DEBIT; the reversal must match its original account, currency, and amount. An original transaction can be successfully reversed only once.

## Status lifecycle

- `RECEIVED`: accepted and awaiting a worker.
- `PROCESSING`: internal state while a worker transaction is executing.
- `PROCESSED`: business effect committed.
- `PENDING`: blocked by a missing predecessor sequence.
- `RETRY_PENDING`: retryable failure; another processing run may retry it.
- `FAILED`: permanent business failure or retry exhaustion.
- `DUPLICATE`: response status for a duplicate submission. The canonical transaction status is not overwritten; duplicate attempts are separately audited.

The worker commits the account balance update and transaction status in the same database transaction. Consequently, other clients normally observe the previously committed status or the final status, rather than an intermediate uncommitted `PROCESSING` state.

## Tests and CI

Run:

```bash
mvn clean verify
```

Tests cover same-request replay, duplicate business transactions, out-of-order processing, transient retry, retry exhaustion, malformed HTTP input, insufficient-funds debit, reversal behavior, request-ID reuse, payload mismatch, and duplicate sequence rejection. A GitHub Actions workflow runs `mvn clean verify` on pushes and pull requests.

## Design note

See [`DESIGN.md`](DESIGN.md) for architecture, idempotency contract, ordering policy, transaction boundaries, status definitions, observability, AI-assisted development disclosure, and production-hardening recommendations.

## Known scope limits and production hardening

This is a focused assessment implementation, not a certified banking ledger. Before production use, replace H2 with PostgreSQL and schema migrations; add authentication/authorization and secrets management; use a queue adapter and transactional outbox for downstream side effects; implement backoff with jitter, dead-letter/replay workflows, metrics/tracing/alerts, retention policies, and operational recovery for missing sequences; and run multi-worker concurrency/load tests against the target database. Review the accounting and audit semantics with the business domain before real financial use.
