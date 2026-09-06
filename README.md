# Ledger API

A double-entry transaction ledger REST API built with Spring Boot and PostgreSQL.

Money is stored as integer minor units, every transfer produces balanced debit and credit
entries, and the database enforces the invariants rather than trusting the application to
get them right. Transfers are atomic under concurrency, protected by pessimistic row
locking with ordered acquisition, and verified by a test that creates a real race between
two threads.

---

## Stack

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4.1.0 |
| Database | PostgreSQL 18 |
| Persistence | Spring Data JPA / Hibernate 7.4 |
| Security | Spring Security, JWT (JJWT 0.13), BCrypt |
| Build | Maven |
| Tests | JUnit 5, MockMvc — 37 tests |

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

The signing key must be at least 256 bits; the application fails at startup if either
variable is unset.

### 4. Run

```bash
mvn spring-boot:run
```

Starts on **port 8081**. A system account (`SYS-EXTERNAL`) is created automatically on
first startup and represents money entering or leaving the system.

---

## API

All endpoints except register and login require `Authorization: Bearer <token>`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register` | Create a user |
| POST | `/api/auth/login` | Exchange credentials for a JWT |
| POST | `/api/auth/pin` | Set or change the transaction PIN (requires password) |
| POST | `/api/accounts` | Create an account, owned by the caller |
| GET | `/api/accounts/{id}` | Balance and details |
| GET | `/api/accounts/{id}/entries` | Statement, newest first |
| GET | `/api/accounts/{id}/reconcile` | Stored balance vs ledger sum |
| POST | `/api/transfers` | Transfer between accounts (requires PIN) |
| POST | `/api/transfers/deposits` | External deposit into an account |

### Example

```bash
# Register and log in
curl -X POST localhost:8081/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"correcthorsebattery"}'

TOKEN=$(curl -s -X POST localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correcthorsebattery"}' | jq -r .token)

# Set a transaction PIN
curl -X POST localhost:8081/api/auth/pin \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"password":"correcthorsebattery","pin":"1234"}'

# Create an account and fund it
curl -X POST localhost:8081/api/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ACC001","currency":"INR"}'

curl -X POST localhost:8081/api/transfers/deposits \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"toAccountId":1,"amountMinor":100000,"description":"Opening deposit"}'
```

### Money format

Amounts are **integer minor units** (paise). `15075` is ₹150.75.

Responses return both forms, a signed integer for arithmetic and a string for display:

```json
{ "balanceMinor": 175000, "balance": "1750.00", "currency": "INR" }
```

Money is never returned as a JSON number. A client's JSON parser would convert it to a
float, reintroducing exactly the precision problem the integer representation avoids.

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
| `INVALID_REQUEST` | 400 | Validation failure |
| `MALFORMED_REQUEST` | 400 | Unparseable body |
| `INVALID_CREDENTIALS` | 401 | Login failed |
| `PIN_REQUIRED` | 403 | PIN missing, wrong, or not set |
| `ACCOUNT_NOT_FOUND` | 404 | Does not exist **or** is not yours |
| `INVALID_STATE` | 409 | Conflicts with current state |
| `ACCOUNT_LOCKED` | 423 | Too many failed PIN attempts |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many login attempts |
| `INTERNAL_ERROR` | 500 | Unexpected — details are logged, not returned |

`ACCOUNT_NOT_FOUND` is returned for accounts that exist but belong to another user. This is
deliberate: with sequential IDs, a 403 would confirm which accounts exist.

---

## Testing

```bash
mvn test
```

37 tests across five classes. The ones worth reading:

- **`TransferServiceConcurrencyTest`** — two threads released simultaneously by a
  `CountDownLatch`, both attempting ₹400 from an account holding ₹500. Asserts exactly one
  succeeds, the balance is exactly right, and the ledger shows exactly one debit. This test
  caught a real regression where an ownership check inadvertently caused a stale read and
  both transfers succeeded.
- **`PinServiceTest.failedAttemptSurvivesTheRollbackOfTheTransfer`** — verifies that the
  failed attempt counter persists even though the transfer that observed the bad PIN rolls
  back. Without a separate transaction the counter would never advance and the lockout
  would be unreachable.

Neither test class can use the usual `@Transactional` rollback pattern, both depend on
real commits, so they clean up explicitly instead.

`test-api.ps1` is a smoke-test script that exercises the running API end to end and checks
three invariants: every transfer's entries sum to zero, all balances sum to zero, and no
account has drifted from its ledger.

---

## Known limitations

- **No TLS.** Passwords and PINs travel in plaintext locally. Required before any real
  deployment.
- **JWTs cannot be revoked** before they expire (one hour). A refresh-token scheme or a
  short-lived denylist would address this at the cost of some statefulness.
- **Rate limiting is in-memory**, so it is lost on restart and not shared across instances.
  Redis would be the production answer. It also trusts `X-Forwarded-For`, which is safe
  only behind a reverse proxy that overwrites the header.
- **Login has a timing side channel** — a nonexistent user returns faster than a wrong
  password, since only the latter runs BCrypt.
- **PIN lockout enables denial of service** — anyone who knows a username can lock that
  account with five wrong PINs.
- **No idempotency** on transfer submission; a duplicated request creates a second transfer.
- **The transaction PIN protects against token theft, not client compromise.** The PIN
  travels in the same requests as the token, so anything that can read one can read both.

---

## Project layout

```
src/main/java/com/arpan/ledger_api/
├── config/       Security config, JWT filter, rate limiting, system account bootstrap
├── controller/   HTTP layer only — no business logic
├── dto/          Request and response shapes; entities are never serialised
├── exception/    Domain exceptions and the global handler
├── model/        Entities, with invariants enforced by the objects themselves
├── repository/   Spring Data JPA interfaces
└── service/      Business logic and transaction boundaries

src/main/resources/db/schema.sql    Source of truth for the schema
docs/design_decisions.md            Why everything is the way it is
```

Built as a portfolio project to demonstrate backend and systems engineering: relational
design, transaction management, concurrency control, and API security.
