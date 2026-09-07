# Design Decisions

Why this project is built the way it is. Each entry states the decision, the alternative
that was considered, and the reasoning.

---

## Money and the ledger

### Money is stored as BIGINT in minor units, not FLOAT

All amounts are integers counting paise. Rs 150.75 is `15075`. Column names carry a
`_minor` suffix so the unit is part of the name.

Floating point cannot represent 0.1 or 0.2 exactly. In Postgres:

```sql
SELECT 0.1::float8 + 0.2::float8;   -- 0.30000000000000004
```

Errors accumulate. In a ledger that means balances that drift, sums that fail to reconcile,
and an overdraft check that fails when a balance that should be exactly zero is
`-0.0000000000000004`.

`NUMERIC(19,4)` is exact and would also work. Integers were preferred because they are
exact *and* associative: `a + b + c` always equals `c + b + a`. That property is what makes
the reconciliation checks meaningful.

The tradeoff is division. Splitting Rs 100.00 three ways gives 3333 / 3333 / 3334 paise,
and the leftover paisa has to be assigned deliberately. `NUMERIC` does not solve that
either; integers just make it visible.

### Double-entry, not single-row transactions

A transfer writes two rows to `ledger_entries` that share a `transfer_id`, one debit and one
credit. The alternative was a single row with `from_account_id`, `to_account_id`, `amount`.

Three reasons:

1. **External money is expressible without special cases.** Under the single row scheme, a
   deposit from outside has a destination but no source, so `from_account_id` has to be
   nullable and every query has to handle the null. A fee has the same problem inverted.
2. **Balance becomes one query.** `SELECT SUM(amount_minor) WHERE account_id = ?`, one
   column, signs already encoded. Single row needs two aggregations and a sign convention
   remembered at every call site.
3. **It gives a self-checking invariant.** Entries for a transfer sum to exactly zero,
   which is assertable in a test and enforceable in the database.

`transfers` and `ledger_entries` are separate tables because a transfer is one business
event producing two lines. Merging them would duplicate the description, timestamp, and
status across both rows and leave no single row representing the transfer itself.

### Debits are negative, credits positive

Chosen so `SUM(amount_minor)` yields the balance directly with no sign juggling.

The convention is enforced at the call site by static factories, with the constructor
private:

```java
new LedgerEntry(transfer, from, -amount);   // reader must know the convention
LedgerEntry.debit(transfer, from, amount);  // reader does not need to
```

Both factories take a positive amount and apply the sign internally, so an accidental sign
flip becomes a compile time impossibility rather than a bug that silently creates money.

### The balance is stored, with a reconciliation endpoint to check it

`accounts.balance_minor` holds the current balance rather than deriving it on every read.
Deriving would be correct by construction, but an account with 400,000 entries makes every
balance read an expensive aggregation.

The honest objection to a stored balance is that it is derived data and can drift from its
source through bugs, manual edits, or a botched migration, silently, because the number
still looks fine. `GET /api/accounts/{id}/reconcile` is the answer to that objection: it
recomputes the balance from the ledger and reports any difference.

Full event sourcing with periodic snapshots is the right architecture at larger scale.

---

## Database as the source of truth

### `ddl-auto=validate`, not `update`

The SQL schema file is authoritative. Hibernate checks the entities against it at startup
and refuses to start on a mismatch, but never creates or alters anything.

`update` is the tutorial default and would have silently discarded the CHECK constraints,
the deferred trigger, and the indexes, because Hibernate generates none of those. The
schema is a shared contract, and letting an ORM mutate it is how production data gets lost.

This caught a real bug: the `currency` column was `CHAR(3)` in SQL while the entity mapped
to `VARCHAR(3)`. Startup refused with the table and column named, before a single row
existed. Under `update`, Hibernate would have altered the column or left the mismatch to
surface later as intermittent string comparison failures caused by `CHAR` blank padding.

### Constraints live in both the application and the database

The no overdraft rule exists twice: in `Account.debit()` and as
`CHECK (balance_minor >= 0)` in Postgres.

That is not redundancy, because they do different jobs. The application check produces a
useful error for the user, naming the balance, the requested amount, and the shortfall. The
database constraint is the actual guarantee: it holds against a bug in the service, a future
endpoint that forgets the check, and manual `UPDATE` statements.

**The application check is for the user; the database check is for the data.**

Constraints are explicitly named (`balance_non_negative`, `amount_positive`) so the name
appears in the exception and can be mapped to a specific API response.

### A deferred trigger enforces the zero-sum invariant

A `CHECK` constraint cannot span rows, so the "entries for a transfer must sum to zero"
rule is a `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`.

Deferred matters. Entries are inserted one at a time, so after the first insert the sum is
non zero. A normal `AFTER INSERT` trigger would reject a valid, merely unfinished transfer.
Deferring to `COMMIT` is exactly when "is this transfer complete and balanced?" becomes a
meaningful question.

The cost is that the exception surfaces at the transaction boundary rather than at the
statement that caused it, which makes stack traces less obvious. That is accepted for an
invariant enforced against *any* writer, not just application code.

**This constraint shaped the design.** It made external deposits impossible to record
honestly, which forced the system account decision below rather than allowing a quiet
special case.

---

## The system account

A deposit from outside the system has no internal counterparty, so it cannot satisfy the
zero-sum trigger. Seeded balances were initially inserted straight into
`accounts.balance_minor`, which is exactly why reconciliation reported drift on them.

The fix is an account representing the outside world. A deposit debits the system account
and credits the customer's, so the entries balance. The system account carries a large
negative balance, which is correct: it represents money issued into the system, the same
way a central bank's issuance is a liability on its own balance sheet.

Allowing one account to go negative required a choice:

```sql
account_type VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER',
CONSTRAINT balance_non_negative CHECK (account_type = 'SYSTEM' OR balance_minor >= 0),
CONSTRAINT account_type_valid CHECK (account_type IN ('CUSTOMER', 'SYSTEM'))
```

A conditional constraint on `account_type` was chosen over a constraint keyed to a specific
account ID, because it **makes the exception a property of the domain rather than of a
particular row**. It says "system accounts may go negative because that is what a system
account is," instead of "account 27 is special," which breaks the day a second system
account exists or a backup is restored with different IDs.

`account_type_valid` is not decoration. Without it a typo like `'SYSTEMM'` would create an
account that is neither valid type, and since it is not `'SYSTEM'`, the balance check would
apply and fail confusingly later rather than at insert.

The resulting invariant is the single best summary of the whole design:

```sql
SELECT SUM(balance_minor) FROM accounts;   -- always 0
```

Money cannot be created or destroyed inside the system.

### System accounts cannot be created through the API

`POST /api/accounts` always creates a `CUSTOMER` account and does not accept an
`accountType` field. A system account is exempt from the balance constraint, which means it
can create money, so exposing that through a public endpoint would let any caller mint
currency.

**Capabilities that cannot be safely exposed should not be exposed at all**, rather than
exposed and guarded. An endpoint that cannot create a system account has no authorization
bug to find, because there is no code path to attack.

`transfer()` separately refuses the system account as a source, so deposits must go through
the deposit operation. The system account itself is created by a startup
`CommandLineRunner` that checks whether it already exists, making it idempotent and
self-healing for anyone who clones the repository.

---

## Concurrency

### Pessimistic locking, not optimistic

Two transfers of Rs 400 from an account holding Rs 500, arriving simultaneously:

1. Thread A reads balance: 50000
2. Thread B reads balance: 50000
3. A checks 40000 <= 50000, passes, writes 10000
4. B checks 40000 <= 50000, passes, writes 10000

Rs 800 leaves an account holding Rs 500. Both application checks passed because both read
before either wrote. The read and the write are separate steps, and the gap between them is
the vulnerability.

Optimistic locking (a `version` column, retry on conflict) was the alternative.

| | Pessimistic | Optimistic |
|---|---|---|
| Cost with no conflict | Locking overhead on every read | None |
| Cost with conflict | Second transaction waits | Second fails, must retry |
| Risk | Deadlock, lock contention | Retry storms under contention |
| Fits | High contention | Low contention |

Pessimistic was chosen because **contention on a single account row is genuinely high in a
ledger**: payroll runs, merchant accounts, and batch settlement all hammer one row. Under
high contention most optimistic transactions lose and retry, which increases contention.
That feedback loop is a retry storm.

It also puts the retry burden in the right place. Optimistic locking needs application
retry code that is bounded, backed off, and tested. Pessimistic locking pushes the waiting
into the database, which is already very good at managing lock queues.

The costs are real and accepted: held locks serialise access to a hot account, deadlock is
possible, and a lock held across a slow external call would block everyone.

### Locks are acquired in ascending ID order

```java
Long firstId  = Math.min(fromAccountId, toAccountId);
Long secondId = Math.max(fromAccountId, toAccountId);
```

Two simultaneous opposing transfers, A to B and B to A, would deadlock if each locked its
own source first. If every transaction acquires locks in the same global order, a cycle is
impossible by construction and one simply waits for the other.

This is a general concurrency technique rather than a Postgres trick.

### The concurrency test, and the regression it caught

`TransferServiceConcurrencyTest` releases two threads simultaneously with a
`CountDownLatch` and asserts that exactly one transfer succeeds, the final balance is
exactly right, and the ledger shows exactly one debit.

**The `CountDownLatch` is what makes it a real race.** Without a start gate, thread A
finishes before thread B begins, and the test would pass whether or not locking worked.

It earned its place. Adding an ownership check to `transfer()` broke it immediately: both
transfers succeeded. The check loaded the account with `findById` to inspect its owner,
which put a managed entity in Hibernate's first-level cache. The later `SELECT ... FOR
UPDATE` acquired the lock correctly, but **Hibernate returned the cached entity rather than
the freshly read row**, so the second thread saw a stale balance of 50000 after waiting.

The lock worked. The read did not.

The fix was a scalar projection for the ownership check, so nothing managed enters the
persistence context:

```java
@Query("SELECT a.user.id FROM Account a WHERE a.id = :id")
Optional<Long> findOwnerIdByAccountId(@Param("id") Long id);
```

The general lesson: **acquiring a lock does not refresh an entity already in the
persistence context.** Nothing except a test with real threads and real commits would have
caught this.

---

## Domain modelling

### Objects expose operations, not setters

| Instead of | The entity exposes |
|---|---|
| `account.setBalanceMinor(x)` | `account.credit(n)` / `account.debit(n)` |
| `transfer.setStatus(x)` | `transfer.markCompleted()` / `markFailed()` |
| `new LedgerEntry(t, a, -500)` | `LedgerEntry.debit(t, a, 500)` |

A public setter lets any code put an object into any state: a balance changed with no
ledger entry, or an illegal transition like `COMPLETED` back to `PENDING`. The double-entry
design becomes decorative if a balance can simply be assigned.

State transitions guard themselves. `markCompleted()` throws unless the current status is
`PENDING`, making `PENDING` the only exitable state.

There is a boundary to this. `Account` can guarantee its own balance never goes negative
because it alone owns that balance. It cannot guarantee a matching ledger entry exists,
because an account does not know about transfers. `Transfer` cannot move money either, since
under double-entry it holds no account references.

**Entities enforce their own invariants; services coordinate between entities.**

### `EnumType.STRING`, never `ORDINAL`

`ORDINAL` is the default if `@Enumerated` is written bare, and it stores the constant's
position. Insert a new constant in the middle later:

```java
PENDING, REVERSED, COMPLETED, FAILED   // REVERSED inserted
```

Every row storing `1` meant `COMPLETED` and now means `REVERSED`. Every completed transfer
silently becomes a reversal, with no error and no migration failure. The data is simply
wrong, discovered from a customer report.

`STRING` costs marginally more storage. That is irrelevant next to silent data corruption.

### `PENDING` exists for a future that has not arrived yet

In the current single-transaction design, `PENDING` is never observable in a committed row,
and `FAILED` is never persisted at all. When a transfer fails, the rollback erases the
`INSERT INTO transfers` along with everything else, so there is no row left to mark as
failed. **The rollback that protects the money also destroys the audit trail.**

`PENDING` is kept because it becomes real the moment an external system is involved. A
payment network call taking 30 seconds cannot be held inside a database transaction, so the
shape becomes: commit `PENDING`, call the external system, then commit `COMPLETED` or
`FAILED`. `PENDING` is the state that survives a crash between the two, and
`findByStatus(PENDING)` is how a recovery job finds stranded transfers.

Introducing that external call becomes a change of transaction boundaries rather than a
schema migration.

---

## API design

### DTOs at the boundary, never entities

Serialising an entity would touch its lazy relationships and throw, expose database
concerns like `passwordHash`, and weld the API contract to the schema so a column rename
breaks every client.

Request and response DTOs have deliberately opposite shapes:

| | Request | Response |
|---|---|---|
| Fields | mutable | `final` |
| Accessors | getters and setters | getters only |
| Built by | Jackson | a static factory |

Jackson fills a request via setters, so final fields cause a runtime deserialisation
failure. A request DTO is a dumb container with no invariants to protect. The rule is about
invariants, not about setters being universally bad.

### Money crosses the wire as an integer and a string, never a number

```json
{ "balanceMinor": 175000, "balance": "1750.00", "currency": "INR" }
```

A JSON number would be parsed into a float by the client, reintroducing exactly the
precision problem the integer storage avoids. The string is for display, the integer for
arithmetic.

### Errors have a stable machine-readable code

```json
{
  "error": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds in account 4: ...",
  "details": { "balanceMinor": 75000, "requestedMinor": 100000, "shortfallMinor": 25000 }
}
```

`error` is what clients branch on and never changes, even if the message is reworded.
`message` is for logs and developers. `details` carries structured data the client can act
on, because a client cannot reliably parse an English sentence.

Domain exceptions carry data and know nothing about HTTP. `InsufficientFundsException`
holds the account, balance, and requested amount, but has no `@ResponseStatus`. Mapping to
status codes happens in one `@RestControllerAdvice`. If the same logic were later exposed
over a message queue or a CLI, the exception would still make sense.

Status codes are treated as an API contract. A client may retry a 500; retrying a 400 will
fail identically forever. Returning 500 for insufficient funds, where the server worked
correctly and refused, would make clients behave wrongly.

**The catch-all handler is deliberately asymmetric: log everything, return nothing.**
Exception messages leak table names, SQL fragments, and library versions that map to known
CVEs. The client gets "An unexpected error occurred."

That catch-all also introduced a regression, caught by a test. It intercepted
`HttpMessageNotReadableException` and turned Spring's correct 400 for a malformed body into
a 500. A catch-all is a safety net, not a substitute for handling, and it can swallow
correct behaviour the framework was already providing.

---

## Security

### JWT, with the tradeoff stated

Login returns a signed token; subsequent requests carry it in an `Authorization` header.
The server verifies the signature without storing anything, so any instance can serve any
request.

**A JWT cannot be revoked before it expires.** A stolen token is valid until `exp`.
Mitigations are short expiry (one hour here), refresh tokens, or a denylist, but a denylist
reintroduces the server state that made JWT stateless in the first place.

The payload is signed, not encrypted. Anyone holding a token can base64-decode and read it,
so nothing secret goes inside. `userId` is carried as a claim so the filter needs no
database lookup per request.

### Identity comes from the token, never the request body

`initiatedByUserId` was deleted from `TransferRequest`. Before that change, any caller could
transfer money from any account by naming a different user in the JSON.

**A body field is a claim; a signed token is proof.**

The field was deleted rather than ignored, because a field that remains invites a future
developer to wire it back up.

### Ownership checks return 404, not 403

Every account read and every transfer source is checked against the authenticated user. A
failure returns `ACCOUNT_NOT_FOUND`, identical to the response for an account that does not
exist.

403 would be literally accurate but would confirm the resource exists. With sequential IDs,
`/accounts/47` returning 403 tells an attacker that account 47 is real and just not theirs.
GitHub returns 404 for private repositories for the same reason.

The cost is that a legitimate user cannot distinguish a typo from a permission problem. The
mitigation is the same log-everything asymmetry used elsewhere: the server logs exactly what
happened, and repeated warnings from one user walking sequential IDs are a visible
enumeration attempt.

**Authentication alone was worth almost nothing.** Immediately after the JWT filter started
working, a request authenticated as one user successfully fetched another user's account
with the full balance. The system knew who the caller was and ignored it.

### Transaction PIN, and what it actually protects

A 4-6 digit PIN, BCrypt-hashed, required for transfers but not deposits. Five failures lock
the account for 15 minutes.

**What it protects against:** a stolen token alone cannot move money, because the PIN is not
stored anywhere the token is.

**What it does not protect against:** a compromised client or intercepted traffic leaks
both, since they travel in the same requests. A static PIN is weaker than an OTP delivered
out of band. Overstating this would be dishonest.

Ownership is checked *before* the PIN, so probing another user's account returns 404 without
touching their attempt counter. Otherwise an attacker could lock out any victim by guessing
PINs against an account they do not own.

The lockout has its own cost: anyone who knows a username can lock that account with five
wrong PINs. That converts a confidentiality risk into an availability one. It is a tradeoff,
not a solved problem.

### `REQUIRES_NEW` for the failed-attempt counter

On a wrong PIN the transfer throws and rolls back, which would also erase the counter
increment. The counter would sit permanently at zero and the lockout would be unreachable.
Structurally the same problem as `FAILED` transfers never persisting.

`PinService.recordFailure` is `@Transactional(propagation = REQUIRES_NEW)`, which suspends
the caller's transaction and commits independently.

**It lives in a separate bean deliberately.** `REQUIRES_NEW` is implemented by the Spring
proxy, so calling it from another method inside the same class would bypass the proxy and
run in the existing transaction, rolling back with everything else, silently.

A separate transaction also cannot see uncommitted data from the one it suspended, which
means any test exercising this path cannot use the usual `@Transactional` rollback pattern.

`PinServiceTest.failedAttemptSurvivesTheRollbackOfTheTransfer` is the test that justifies
all of this: it asserts both that the counter incremented and that no money moved.

### Rate limiting runs before the application

A filter keyed by client IP, 10 login attempts per 15 minutes, returning 429 with
`Retry-After`.

A filter rather than a service check, because it rejects before any application code runs:
no controller, no service, **no BCrypt**. That last point is the real reason. BCrypt is
deliberately slow, so an unlimited login endpoint is a denial-of-service vector on its own.

The limitations are deliberate and documented: in-memory so it is lost on restart and not
shared across instances, fixed window rather than sliding, and no eviction. Redis is the
production answer. `X-Forwarded-For` is trusted only because nginx overwrites it and the
application binds to `127.0.0.1`, so nothing can reach it except through the proxy.

### Login gives the same answer, and takes the same time, either way

Wrong password and nonexistent user return byte-identical responses, so the endpoint cannot
be used to discover which usernames exist.

That was not enough. A nonexistent user returned immediately while a wrong password ran
BCrypt for ~100ms, so **response timing distinguished them even though the responses did
not**. The fix runs a BCrypt comparison against a dummy hash on the not-found path.

This is mitigation rather than elimination. Full constant time behaviour is hard on a JVM
with a garbage collector and a JIT.

---

## Idempotency

`POST /api/transfers` requires an `Idempotency-Key` header. The problem it solves is real:
a request succeeds, the response is lost to a network drop, the client retries, and money
moves twice. Retrying is correct client behaviour, so the server has to make it safe.

The key is stored with the resulting transfer, scoped per user by a composite unique
constraint on `(user_id, idempotency_key)`. Global scoping would let a guessed key return
another user's transfer.

Alongside it, a SHA-256 hash of the request parameters:

- Same key, same parameters: the original transfer is returned, nothing executes.
- Same key, different parameters: 422, because retrying will fail identically forever.
- Concurrent requests with the same key: one wins, the other gets 409 and can retry to
  collect the result.

**The PIN is excluded from the hash.** It authenticates the caller rather than identifying
the operation, and a client retrying might legitimately reprompt for it.

The concurrent case cannot simply return the other transfer, because this request has
already executed and committing would move the money twice. It throws instead, forcing a
rollback, and the client retries into the replay path. That turns a silent double spend into
a visible retryable error.

Making concurrent duplicates fully transparent would require inserting the key before the
transfer completes, so the second request blocks on the unique constraint. That is what
Stripe does, and it is the next step up from what is built here.

Requiring the header rather than making it optional is a judgement call. It does not
guarantee correct use, since a client generating a fresh UUID per retry gets no protection.
It does force the developer to encounter the concept.

---

## Testing

43 tests across five classes, run on every push against a fresh PostgreSQL container.
Because CI starts from nothing, a green run also proves `schema.sql` is complete and that no
test depends on local state.

**Two test classes cannot use the standard `@Transactional` rollback pattern.** The
concurrency test needs real commits so the worker threads can see each other's data, and
`PinServiceTest` needs them because `REQUIRES_NEW` cannot see uncommitted data from a
suspended transaction. Both clean up explicitly in `@AfterEach`.

The suite has caught four real bugs that manual testing did not:

- The stale-read regression described above, where both concurrent transfers succeeded.
- Non-deterministic history ordering. Two entries created in the same millisecond shared a
  `created_at`, and `ORDER BY created_at DESC` alone has no way to break the tie. Any
  `ORDER BY` on a non-unique column needs a unique tiebreaker; `id DESC` was added.
- A malformed request body returning 500 instead of 400, because the catch-all handler
  intercepted Spring's own correct handling.
- A regex with a stray space (`\d{4, 6}`) that made every call to the set-PIN endpoint
  throw. Broken since it was written, invisible because no test called it.

---

## Things deliberately not built

- **JWT revocation.** A refresh-token scheme is real work: a second token type, rotation, a
  refresh endpoint, revocation on logout. Short expiry is a legitimate mitigation and a
  better answer than a half built implementation.
- **Redis-backed rate limiting.** Adds an infrastructure dependency for a problem that only
  appears at multiple instances.
- **Out-of-band second factor.** The right fix for the PIN's limitation, but it needs an SMS
  or email provider and asynchronous delivery.
- **Idempotency key expiry.** Stripe holds keys for 24 hours. Here the table grows
  indefinitely; a scheduled cleanup would fix it.
- **Monitoring.** No metrics, no alerting, no health checks beyond systemd's process
  supervision.

These are listed because knowing what is missing is part of understanding what was built.
