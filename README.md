# Ledger API

[![Tests](https://github.com/ArpanGoyal09/Ledger-API/actions/workflows/tests.yml/badge.svg)](https://github.com/ArpanGoyal09/Ledger-API/actions/workflows/tests.yml)

A double-entry transaction ledger REST API built with Spring Boot and PostgreSQL.

Money is stored as integer minor units, every transfer produces balanced debit and credit
entries, and the database enforces the invariants rather than trusting the application to
get them right. Transfers are atomic under concurrency, protected by pessimistic row
locking with ordered acquisition, and verified by a test that creates a real race between
two threads.

**Live:** https://arpan-ledger.duckdns.org

**Design rationale:** [`docs/design_decisions.md`](docs/design_decisions.md)

---

## Stack

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4.1.0 |
| Database | PostgreSQL (18 in development, 16 in production) |
| Persistence | Spring Data JPA / Hibernate 7.4 |
| Security | Spring Security, JWT (JJWT 0.13), BCrypt |
| Build | Maven |
| Tests | JUnit 5, MockMvc. 43 tests |
| Deployment | AWS EC2, nginx, systemd, Let's Encrypt |

---

## What it does

- **Double-entry ledger.** A transfer writes two rows that sum to exactly zero. A deferred
  database trigger rejects any transfer whose entries do not balance, at commit time.
- **Atomic transfers.** Debit, credit, both ledger entries and the status change happen in
  one transaction or not at all.
- **Concurrency safety.** Accounts are locked with `SELECT ... FOR UPDATE` in ascending ID
  order, preventing both lost updates and deadlock between opposing transfers.
- **Money conservation.** `SELECT SUM(balance_minor) FROM accounts` is always exactly zero.
  Money enters only through the system account, and that issuance is recorded as a
  liability.
- **Reconciliation.** An endpoint recomputes any account's balance from the ledger and
  reports drift between the stored value and the transaction log.
- **JWT authentication** with ownership authorization. You cannot read or transfer from an
  account you do not own, and the response does not reveal that it exists.
- **Transaction PIN** with attempt limiting and temporary lockout, plus IP based rate
  limiting on login.
- **Idempotent transfers.** A retry with the same `Idempotency-Key` returns the original
  result instead of moving money twice.

---

## Running it

**Requires:** Java 17+, Maven 3.9+, PostgreSQL 15+.

### 1. Create the database and application role

```sql
CREATE DATABASE ledger;
CREATE USER ledger_app WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE ledger TO ledger_app;
```

Then, connected to the `ledger` database:

```sql
GRANT ALL ON SCHEMA public TO ledger_app;
```

The second grant is required separately on PostgreSQL 15+. The `public` schema is not
writable by non-owners by default.

### 2. Create the schema

```bash
psql -U ledger_app -d ledger -f src/main/resources/db/schema.sql
```

The application does **not** generate its schema. `ddl-auto=validate` means Hibernate
checks the entities against this file at startup and refuses to start on a mismatch.

### 3. Set credentials

```bash
export DB_PASSWORD='your_password'
export JWT_SECRET="$(openssl rand -base64 48)"
```

On Windows PowerShell:

```powershell
[Environment]::SetEnvironmentVariable("DB_PASSWORD", "your_password", "User")
[Environment]::SetEnvironmentVariable("JWT_SECRET",
    [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes(48)), "User")
```

The signing key must be at least 256 bits. The application fails at startup if either
variable is unset.

### 4. Run

```bash
mvn spring-boot:run
```

Starts on **port 8081**. A system account (`SYS-EXTERNAL`) is created automatically on
first startup and represents money entering or leaving the system.

---

## API

All endpoints except the health check, register and login require `Authorization: Bearer <token>`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Service health check (public) |
| POST | `/api/auth/register` | Create a user |
| POST | `/api/auth/login` | Exchange credentials for a JWT |
| POST | `/api/auth/pin` | Set or change the transaction PIN (requires password) |
| POST | `/api/accounts` | Create an account, owned by the caller |
| GET | `/api/accounts/{id}` | Balance and details |
| GET | `/api/accounts/{id}/entries` | Statement, newest first |
| GET | `/api/accounts/{id}/reconcile` | Stored balance vs ledger sum |
| POST | `/api/transfers` | Transfer between accounts (requires PIN and `Idempotency-Key`) |
| POST | `/api/transfers/deposits` | External deposit into an account |

### Example

```bash
BASE=https://arpan-ledger.duckdns.org

# Register and log in
curl -X POST $BASE/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"correcthorsebattery"}'

TOKEN=$(curl -s -X POST $BASE/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correcthorsebattery"}' | jq -r .token)

# Set a transaction PIN
curl -X POST $BASE/api/auth/pin \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"password":"correcthorsebattery","pin":"1234"}'

# Create an account and fund it
curl -X POST $BASE/api/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ACC001","currency":"INR"}'

curl -X POST $BASE/api/transfers/deposits \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"toAccountId":1,"amountMinor":100000,"description":"Opening deposit"}'

# Transfer, with an idempotency key
curl -X POST $BASE/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId":1,"toAccountId":2,"amountMinor":25000,"description":"Rent","pin":"1234"}'
```

### Money format

Amounts are **integer minor units** (paise). `15075` is Rs 150.75.

Responses return both forms, a signed integer for arithmetic and a string for display:

```json
{ "balanceMinor": 175000, "balance": "1750.00", "currency": "INR" }
```

Money is never returned as a JSON number. A client's JSON parser would convert it to a
float, reintroducing exactly the precision problem the integer representation avoids.

### Idempotency

`POST /api/transfers` requires an `Idempotency-Key` header. The key is stored with the
resulting transfer, scoped per user, alongside a SHA-256 hash of the request parameters.

- Same key, same parameters: the original transfer is returned and no money moves.
- Same key, different parameters: `422 IDEMPOTENCY_KEY_REUSED`.
- Two concurrent requests with the same key: one succeeds, the other gets
  `409 IDEMPOTENCY_IN_PROGRESS` and can retry to collect the result.

The PIN is deliberately excluded from the hash. It authenticates the caller rather than
identifying the operation, and a client retrying might legitimately re-prompt for it.

### Errors

Every error has the same shape, with a stable machine readable code:

```json
{
  "error": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds in account 4: balance 75000 minor units, requested 100000 minor units",
  "details": { "accountId": 4, "balanceMinor": 75000, "requestedMinor": 100000, "shortfallMinor": 25000 }
}
```

| Code | Status | |
|---|---|---|
| `INSUFFICIENT_FUNDS` | 400 | Balance too low |
| `INVALID_REQUEST` | 400 | Validation failure, including a missing idempotency key |
| `MALFORMED_REQUEST` | 400 | Unparseable body |
| `INVALID_CREDENTIALS` | 401 | Login failed |
| `PIN_REQUIRED` | 403 | PIN missing, wrong, or not set |
| `ACCOUNT_NOT_FOUND` | 404 | Does not exist **or** is not yours |
| `INVALID_STATE` | 409 | Conflicts with current state |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | A concurrent request holds the same key |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Key reused with different parameters |
| `ACCOUNT_LOCKED` | 423 | Too many failed PIN attempts |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many login attempts |
| `INTERNAL_ERROR` | 500 | Unexpected. Details are logged, not returned |

`ACCOUNT_NOT_FOUND` is returned for accounts that exist but belong to another user. That is
deliberate: with sequential IDs, a 403 would confirm which accounts exist.

---

## Testing

```bash
mvn test
```

43 tests across five classes, run on every push by GitHub Actions against a fresh
PostgreSQL 16 container. Because CI starts from nothing, a green run also proves
`schema.sql` is complete and that the tests do not depend on local state.

The two worth reading:

- **`TransferServiceConcurrencyTest`** exercises two threads released simultaneously by a
  `CountDownLatch`, both attempting Rs 400 from an account holding Rs 500. It asserts that
  exactly one succeeds, the balance is exactly right, and the ledger shows exactly one
  debit. This test caught a real regression where an ownership check inadvertently caused a
  stale read and both transfers succeeded.
- **`PinServiceTest.failedAttemptSurvivesTheRollbackOfTheTransfer`** verifies that the
  failed attempt counter persists even though the transfer that observed the bad PIN rolls
  back. Without a separate transaction the counter would never advance and the lockout
  would be unreachable.

Neither class can use the usual `@Transactional` rollback pattern. Both depend on real
commits, so they clean up explicitly instead.

`test-api.ps1` is a smoke test script that exercises the running API end to end and checks
three invariants: every transfer's entries sum to zero, all balances sum to zero, and no
account has drifted from its ledger.

---

## Deployment

Running on AWS EC2 (t3.micro, Ubuntu 24.04):

```
Internet -> nginx (443, TLS) -> Spring Boot (8081, localhost) -> PostgreSQL (5432, localhost)
```

- **nginx** terminates TLS and reverse proxies to the application. The app binds to
  `127.0.0.1` and port 8081 is not open in the security group, so nginx is the only route
  in. That is what makes `X-Forwarded-For` trustworthy for rate limiting.
- **TLS** via Let's Encrypt, renewed automatically by certbot's systemd timer.
- **systemd** runs the service as an unprivileged user, restarts it on failure, and starts
  it on boot. Credentials live in a root owned `EnvironmentFile` with mode 600, readable by
  systemd before it drops privileges but not by the application user.
- **PostgreSQL 16** on the same instance. Development uses 18; nothing version specific is
  used, but the mismatch is worth noting.

---

## Known limitations

- **JWTs cannot be revoked** before they expire (one hour). A refresh token scheme or a
  short lived denylist would address this, at the cost of some statefulness.
- **Rate limiting is in memory**, so it is lost on restart and not shared across instances.
  Redis would be the production answer. There is also no eviction, so the IP map grows
  unbounded.
- **PIN lockout enables denial of service.** Anyone who knows a username can lock that
  account with five wrong PINs. Per IP limiting on the PIN path, or exponential backoff
  instead of hard lockout, would mitigate it.
- **The transaction PIN protects against token theft, not client compromise.** The PIN
  travels in the same requests as the token, so anything that can read one can read both.
  An out of band factor such as an OTP is the real fix.
- **Idempotency keys never expire.** The table grows indefinitely. Stripe holds keys for 24
  hours; a scheduled cleanup would do the same here.
- **Single instance, no monitoring.** No health checks beyond systemd's process
  supervision, no metrics, no alerting.

---

## Project layout

```
src/main/java/com/arpan/ledger_api/
├── config/       Security config, JWT filter, rate limiting, system account bootstrap
├── controller/   HTTP layer only, no business logic
├── dto/          Request and response shapes; entities are never serialised
├── exception/    Domain exceptions and the global handler
├── model/        Entities, with invariants enforced by the objects themselves
├── repository/   Spring Data JPA interfaces
└── service/      Business logic and transaction boundaries

src/main/resources/db/schema.sql    Source of truth for the schema
.github/workflows/tests.yml         CI: tests against a PostgreSQL 16 container
docs/design_decisions.md            Why everything is the way it is
```
Built as a portfolio project to demonstrate backend and systems engineering.