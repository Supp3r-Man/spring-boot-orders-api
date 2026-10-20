# spring-boot-orders-api

A small orders REST API in Spring Boot 3.4 / Java 17 / Maven (H2, Spring Data JPA, Bean Validation, Actuator).
It exists to show the parts of a Java service that matter once real traffic arrives, not just CRUD:

- `POST /orders` needs an `Idempotency-Key`; a client retry cannot create a second order or reserve stock twice.
- Stock and orders carry a JPA `@Version`, so two buyers racing for the last unit get exactly one winner.
- Every error is an RFC 9457 `application/problem+json` document, with a field list for validation failures.
- Money is an integer count of minor units (USD cents); `BigDecimal` is used only for the discount percentage.
- The order lifecycle (NEW, PAID, SHIPPED, CANCELLED) is one transition table, and cancelling restocks.

All data is synthetic and lives in memory. This is a learning/showcase project, not a production service (see
[Known limits](#known-limits)).

## Endpoints

| Method and path | What it does | Success | Notable errors |
|---|---|---|---|
| `POST /items` | Create a catalogue item `{sku, name, priceMinor, stock}` | 201 + `Location` | 400 validation, 409 `sku-exists` |
| `GET /items` | All items, sorted by SKU (not paginated) | 200 | |
| `GET /items/{sku}` | One item | 200 | 404 `not-found` |
| `POST /orders` | Place an order. Header `Idempotency-Key` (max 128 chars) required | 201 + `Location`, or 200 + `Idempotent-Replayed: true` on replay | 400 validation or missing header, 422 `unknown-sku`, `insufficient-stock`, `idempotency-key-reuse`, 409 `concurrent-update` / `conflict` |
| `GET /orders/{id}` | One order with its lines | 200 | 404 |
| `GET /orders?status=&createdAfter=&page=&size=` | Newest first; `createdAfter` is an exclusive ISO-8601 instant; `size` 1..100 (default 20) | 200 `{content, page, size, totalElements, totalPages}` | 400 bad parameter |
| `POST /orders/{id}/status` | Move to `{status}` | 200 | 404, 409 `illegal-transition`, 400 |
| `GET /actuator/health` | Liveness/readiness | 200 | |

Order lifecycle: `NEW -> PAID -> SHIPPED`, and `CANCELLED` from `NEW` or `PAID`. `SHIPPED` and `CANCELLED` are
terminal. Discount tiers on the subtotal: 5% from 20,000 minor units, 10% from 50,000, 15% from 100,000, rounded
`HALF_UP` to a whole minor unit. Line prices are snapshotted on the order.

### Try it (output below is from a real run of the jar)

```bash
java -jar target/spring-boot-orders-api-0.1.0.jar        # or: mvn spring-boot:run   (port 8080)
```

```bash
# 1. two items
curl -s -X POST localhost:8080/items -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-1","name":"Widget","priceMinor":2500,"stock":10}'
# {"sku":"WIDGET-1","name":"Widget","priceMinor":2500,"currency":"USD","stock":10}
curl -s -X POST localhost:8080/items -H 'Content-Type: application/json' \
  -d '{"sku":"GADGET-1","name":"Gadget","priceMinor":19999,"stock":3}'

# 2. place an order: 4 x 2500 + 1 x 19999 = 29999, 5% tier
curl -s -i -X POST localhost:8080/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: order-001' \
  -d '{"customerRef":"cust-42","lines":[{"sku":"WIDGET-1","quantity":4},{"sku":"GADGET-1","quantity":1}]}'
# HTTP/1.1 201
# Location: /orders/1
# {"id":1,"status":"NEW","customerRef":"cust-42","createdAt":"2026-10-20T06:59:09.599612Z","currency":"USD",
#  "subtotalMinor":29999,"discountPercent":5,"discountMinor":1500,"totalMinor":28499,
#  "lines":[{"sku":"GADGET-1","quantity":1,"unitPriceMinor":19999,"lineTotalMinor":19999},
#           {"sku":"WIDGET-1","quantity":4,"unitPriceMinor":2500,"lineTotalMinor":10000}]}

# 3. retry with the same key (lines in a different order): same order back, nothing reserved twice
curl -s -i -X POST localhost:8080/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: order-001' \
  -d '{"customerRef":"cust-42","lines":[{"sku":"GADGET-1","quantity":1},{"sku":"WIDGET-1","quantity":4}]}'
# HTTP/1.1 200
# Idempotent-Replayed: true

# 4. same key, different body: refused
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: order-001' \
  -d '{"customerRef":"cust-42","lines":[{"sku":"WIDGET-1","quantity":5}]}'
# 422 {"type":"urn:problem:orders:idempotency-key-reuse","title":"Unprocessable Entity","status":422,
#       "detail":"Idempotency-Key was already used with a different request body","instance":"/orders",
#       "code":"idempotency-key-reuse"}

# 5. validation lists every bad field, including nested paths
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: k2' \
  -d '{"customerRef":"","lines":[{"sku":"WIDGET-1","quantity":0}]}'
# 400 {"type":"urn:problem:orders:validation","title":"Validation failed","status":400,
#      "detail":"Request validation failed","instance":"/orders","code":"validation",
#      "errors":[{"field":"customerRef","message":"must not be blank"},
#                {"field":"lines[0].quantity","message":"must be greater than or equal to 1"}]}

# 6. not enough stock (only 2 GADGET-1 left)
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: k3' \
  -d '{"customerRef":"c","lines":[{"sku":"GADGET-1","quantity":9}]}'
# 422 {... "detail":"SKU GADGET-1: requested 9, available 2", ... "code":"insufficient-stock"}

# 7. lifecycle: cannot ship before paying, then pay, then list
curl -s -X POST localhost:8080/orders/1/status -H 'Content-Type: application/json' -d '{"status":"SHIPPED"}'
# 409 {... "detail":"Cannot move order from NEW to SHIPPED", ... "code":"illegal-transition"}
curl -s -X POST localhost:8080/orders/1/status -H 'Content-Type: application/json' -d '{"status":"PAID"}'
curl -s "localhost:8080/orders?status=PAID&size=5"
# {"content":[{"id":1,"status":"PAID",...}],"page":0,"size":5,"totalElements":1,"totalPages":1}
```

(`createdAt` and ids will differ on your run. The `...` marks parts I abbreviated here; the real responses have
them in full.)

## Design

### Idempotency

`Idempotency-Key` is required on `POST /orders`. The service stores the key (unique index) plus a SHA-256 of the
normalised request: customer, and a sorted SKU-to-quantity map. Duplicate SKU lines are merged first and every field is
length-prefixed, so reordered or split lines hash the same and different requests cannot collide by text tricks.

| Situation | Result |
|---|---|
| New key | Order created, 201 |
| Known key, same hash | The stored order, 200 with `Idempotent-Replayed: true` |
| Known key, different hash | 422 `idempotency-key-reuse` (silently returning the old order would hide a client bug) |
| Same key twice at the same instant | Both can miss the lookup; the unique index makes one insert fail and that request gets 409 `conflict` ("retry"). The retry then replays. |

### Concurrency

`Item` and `Order` have `@Version`. `place` reads items, calls `reserve` on each (422 if short), prices, and saves, all in
one transaction, so a failure on line 2 rolls back line 1. If two transactions race for the same item, the later
flush fails the version check and the loser gets 409 `concurrent-update`; if it arrives after the winner committed it
sees stock 0 and gets 422. Both are valid, so the tests assert the invariant (exactly one 201, stock never negative,
orders accepted equals stock removed) rather than a particular loser status.

Honest trade-off: optimistic locking can reject a buyer even though stock remained when many people hit one SKU. The
pool-of-five test shows "between 1 and 5 sold", not always 5. For a genuinely hot SKU I would use an atomic
`UPDATE ... SET stock = stock - ? WHERE stock >= ?` or bounded retry.

Cancelling restocks in the same transaction as the status change; cancelling twice is a 409 and restocks once.

### Error format

One `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`, so Spring's own errors (bad JSON, wrong method,
unknown path, bad parameter type) are also problem+json. Business errors carry `type` (`urn:problem:orders:<code>`) and a
machine-readable `code`; validation errors add `errors: [{field, message}]` sorted by field.

| Status | `code` | When |
|---|---|---|
| 400 | `validation` | Body, query or header fails Bean Validation |
| 404 | `not-found` | Unknown order or SKU |
| 409 | `sku-exists`, `illegal-transition`, `concurrent-update`, `conflict` | State conflicts, optimistic-lock loser, unique-constraint race |
| 422 | `unknown-sku`, `insufficient-stock`, `idempotency-key-reuse` | Well-formed request that business rules refuse |
| 500 | `internal-error` | Anything unexpected; the body names no internals |

Errors raised by Spring itself before our code runs (for example a missing `Idempotency-Key` header, malformed JSON)
are problem+json too but have `type: about:blank` and no `code`.

Why 409 vs 422: 409 means the request conflicts with the current state of the resource; 422 means it is well-formed but
the business rules cannot process it. Defensible alternatives exist; the point is to be consistent and documented.

## Running and testing

Requirements: JDK 17+, Maven 3.9.

```bash
git clone https://github.com/Supp3r-Man/spring-boot-orders-api.git && cd spring-boot-orders-api
mvn -q -B verify                  # 95 tests, builds target/spring-boot-orders-api-0.1.0.jar
mvn spring-boot:run               # http://localhost:8080
```

95 tests: unit tests for pricing, the state machine and the fingerprint; `@DataJpaTest` repository tests (including
deterministic stale-write tests for both `@Version`s); 42 MockMvc API tests against H2; and 4 real-thread race tests
(25 rounds of two buyers for the last unit, 8 buyers for 1 unit, a pool of five with 12 buyers, the same key in flight
twice).

## Mutation check

Passing tests prove little if they would also pass on broken code. I applied 37 behaviour-defining mutations one at a
time (textual replacements run by `scripts/mutation_check.py`, which requires each match text to occur exactly once
and restores the file afterwards), ran the whole suite, and recorded whether any test failed. This is a hand-rolled
check, not PIT, and the mutations are the ones I thought of; it says nothing about bugs I did not think of.

**First run: 32 of 35 killed, 3 survived. Each survivor pointed at a real gap, so I fixed the tests, added 2 more
mutations, and re-ran: 37 of 37 killed against the current test suite.**

| # | Mutation | Result | First failing test(s) |
|---|---|---|---|
| M01 | `Item` loses `@Version` | killed | 3 `ConcurrencyTest` cases (5 failing) |
| M02 | `Order` loses `@Version` | **survived, then killed** | `OrderRepositoryTest.staleStatusWriteIsRejectedByOptimisticLock` (added) |
| M03 | last unit cannot be sold (`>` to `>=`) | killed | 2 `ConcurrencyTest`, `ItemRepositoryTest` (4 failing) |
| M04 | `reserve` does not decrement | killed | 15 tests |
| M05 | `restock` does nothing | killed | `ItemTest.restockAddsBack`, 2 more |
| M06 | key reuse with a different body not rejected | killed | `OrderApiTest.sameKeyDifferentQuantityIs422AndChangesNothing`, 1 more |
| M07 | fingerprint ignores customer | killed | `RequestFingerprintTest.customerChangesHash`, 1 more |
| M08 | fingerprint ignores quantity | killed | `RequestFingerprintTest.quantityChangesHash`, 1 more |
| M09 | fingerprint drops SKU length prefix | **survived, then killed** | `RequestFingerprintTest.boundariesAreUnambiguous` (rewritten) |
| M10 | duplicate SKU lines overwrite instead of sum | killed | `OrderApiTest.replayIsRecognisedEvenIfLinesAreReorderedOrSplit` |
| M11 | replay not flagged as replay | killed | 2 `OrderApiTest` |
| M12 | replay loses `Idempotent-Replayed` header | killed | `OrderApiTest.sameKeySameBodyReplaysTheOriginalOrder` |
| M13 | replay answers 201 | killed | 3 tests |
| M14 | `Idempotency-Key` length limit removed | **survived, then killed** | `OrderApiTest.idempotencyKeyLengthLimitIs128` (added) |
| M15 | discount rounds `HALF_EVEN` | killed | `PricingServiceTest.roundsHalfUpNotBankers` |
| M16 | tier threshold exclusive | killed | `PricingServiceTest.tierBoundaries`, 1 more |
| M17 | line total overflow not detected | killed | `PricingServiceTest.overflowFailsLoudlyInsteadOfWrappingAround` |
| M18 | total ignores discount | killed | 3 tests |
| M19 | PAID cannot be cancelled | killed | `OrderStatusTest` (2 failing) |
| M20 | NEW may jump to SHIPPED | killed | 3 tests |
| M21 | SHIPPED not terminal | killed | 3 tests |
| M22 | cancel does not restock | killed | 2 `OrderApiTest` |
| M23 | `createdAfter` inclusive | killed | `OrderApiTest.listCreatedAfterIsExclusive`, `OrderRepositoryTest.createdAfterIsExclusive` |
| M24 | status filter ignored | killed | 3 tests |
| M25 | id tie-breaker removed from sort | killed | `OrderApiTest.identicalTimestampsPageStablyByIdDescending` |
| M26 | list oldest first | killed | 2 `OrderApiTest` |
| M27 | page size cap 100 to 1000 | killed | `OrderApiTest.badListParametersAre400Problems` |
| M28 | negative page accepted | killed | `OrderApiTest.badListParametersAre400Problems` |
| M29 | quantity 0 passes validation | killed | `OrderApiTest.quantityOutOfRangeNamesTheNestedField` |
| M30 | quantity upper bound removed | killed | `OrderApiTest.quantityOutOfRangeNamesTheNestedField` |
| M31 | nested paths (`lines[0].quantity`) not reported | killed | 2 `OrderApiTest` |
| M32 | problem documents lose `code` | killed | 7 `OrderApiTest` |
| M33 | optimistic-lock loser answers 500 | killed (race-dependent, see below) | 3 `ConcurrencyTest` |
| M34 | unique-constraint race answers 500 | killed (race-dependent, see below) | `ConcurrencyTest.sameIdempotencyKeyInFlightTwiceCreatesOneOrder` |
| M35 | duplicate-SKU pre-check removed | killed | `OrderApiTest.duplicateSkuIs409Problem` |
| M36 | fingerprint drops customer length prefix | **survived my first new test, then killed** | `RequestFingerprintTest.customerBoundaryIsUnambiguous` |
| M37 | `createdAt` not truncated to microseconds | killed | `OrderApiTest.createdAtInThePlacementResponseMatchesWhatIsStored` |

Notes on the results:

- **M02 was a real gap.** Only `Item.version` had a deterministic stale-write test. Without a version on `Order`, a
  concurrent ship and cancel on the same order could both succeed, last write winning. The new repository test
  simulates the stale write with a native `UPDATE` behind a detached entity, the same way the `Item` test does.
- **M09 was a weak test, not an unprotected feature.** The old "boundaries" test used inputs that do not collide even
  without length prefixes. The new test uses `{"A|qty=1|sku=B": 2}` against `{"A": 1, "B": 2}`, which hash identically
  without the prefixes. Real requests cannot contain `|` in a SKU (the SKU pattern forbids it), so this is defence in
  depth, tested at the unit level only. My first attempt at the customer-boundary test (M36) was also too weak and
  survived; the version in the repo uses an empty SKU map.
- **M14:** no test sent an over-long key. Without the limit a 200-character key would only be stopped by the 128-character database
  column (a storage error, not a clean 400). The real jar now answers 400 with `errors: [{field: idempotencyKey, ...}]`.
- **M33 and M34 depend on the scheduler.** They are only killed if a race actually produces the collision. They were
  killed in both of two runs, and three tests exercise that path, but this is probabilistic, not deterministic.
- **M37 came from a bug the real run exposed.** The 201 response showed `createdAt` with nanoseconds
  (`...19.690239900Z`) but H2 stores microseconds, so a replay or `GET` returned `...19.690240Z`. A replayed
  response differing from the original is exactly what idempotency should avoid, so `OrderService` now truncates to
  microseconds before saving.
- The first run happened while other builds were running on the same machine; durations are not meaningful.

Reproduce: `python scripts/mutation_check.py` (set `MVN` if `mvn` is not on PATH; `--list` prints the mutations). The
whole Spring suite runs once per mutation, so expect roughly 25 to 40 minutes.

## Known limits

- H2 in memory, `ddl-auto=create-drop`. H2 is not Postgres: isolation, locking and error behaviour differ. Not run
  against Postgres or with Flyway migrations; I have not verified the optimistic-lock behaviour on another database.
- No authentication or authorization. Idempotency keys are global (two callers using the same key collide) and never
  expire. The original response is not stored, the order is re-read, so a replay after a status change shows the new status.
- Optimistic-lock failures are returned to the client as 409, not retried server-side.
- Catalogue list is not paginated; no item update or delete; no payment integration (`PAID` is just a state).
- Single instance only; no distributed concerns, no events when status changes.
- The concurrency tests are real-thread tests against H2 with MockMvc, not a load test.
- CI: the workflow (`.github/workflows/ci.yml`, `mvn -q -B verify` on Java 17 and 21) is included. CI ran green on the first run on GitHub Actions (Java 17 and 21, ubuntu-latest, 2026-10-20).

## Layout

```
src/main/java/com/github/sup3rmar10/orders/   domain (Item, Order, OrderStatus), service (OrderService, PricingService,
                                              RequestFingerprint, CatalogService), repo, web (controllers, Dtos,
                                              ApiExceptionHandler), error (ApiException)
src/test/java/...                             unit, DataJpa, MockMvc and real-thread concurrency tests
scripts/mutation_check.py                     the mutation check described above
STEPS.md                                      how this was built, and how to run it yourself
```

MIT licensed.
