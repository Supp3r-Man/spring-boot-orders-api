# STEPS

## Part A: Steps taken (in order)

1. **Scope.** Picked the parts of a Java service that matter once real traffic arrives, instead of plain CRUD:
   an `Idempotency-Key` on order placement, optimistic locking on stock, an explicit order state machine,
   money as integer minor units, RFC 9457 error documents, and a filtered, paged list. Left out on purpose:
   authentication, payments, item update/delete, multi-currency, a real database.
2. **Project skeleton.** `pom.xml` on `spring-boot-starter-parent` 3.4.5 (Java 17; web, data-jpa, validation,
   actuator, H2 at runtime, starter-test), `.gitattributes` (`* text=auto eol=lf`), `.gitignore`, MIT license, a CI
   workflow that runs `mvn -q -B verify` on Java 17 and 21. H2 in memory with `ddl-auto=create-drop`, so there is
   nothing to install.
3. **Pure pieces first, with unit tests.** `OrderStatus` (transition table in one `switch`), `PricingService`
   (tiers 5/10/15 percent at 200/500/1000 dollars, `BigDecimal` only for the percentage, `HALF_UP`,
   `Math.multiplyExact` and `addExact`), `RequestFingerprint` (SHA-256 of customer plus a sorted sku/quantity
   map, length-prefixed so fields cannot run into each other).
4. **Persistence.** `Item` and `Order` both carry `@Version`; `Order.idempotencyKey` has a unique index;
   `OrderLine` snapshots the unit price. `OrderSpecs` builds the optional status and exclusive `createdAfter`
   filters as a `Specification`. Repository tests check the unique constraint, the filters, and a deterministic stale
   write: a native `UPDATE` behind a detached entity, then a save that must fail.
5. **Service.** `OrderService.place` runs in one transaction: merge duplicate SKUs in a `TreeMap`, hash, look up the
   key (replay or 422), load items, `reserve` each line, price, save. A failure on line 2 rolls back line 1. Cancelling
   restocks inside the same transaction as the status change.
6. **Web layer.** Controllers, request records with Bean Validation, and one `ApiExceptionHandler` that extends
   `ResponseEntityExceptionHandler`, so Spring's own 400/404/405/415 also come out as `application/problem+json`.
7. **Bug found while writing the API tests: field names disappeared.** Because `@RequestHeader` carries constraints,
   Spring 6.2 switches the controller to method validation and body errors arrive as
   `HandlerMethodValidationException` instead of `MethodArgumentNotValidException`. Without a handler for it the
   error list lost paths like `lines[0].quantity`. The handler now reads `ParameterErrors` for the body and falls
   back to the parameter name for header and query errors.
8. **API tests (MockMvc, real H2).** 42 scenarios: pricing in the HTTP response, replay with reordered or split
   lines, key misuse with a different quantity or customer, rollback of a multi-line order, filters, tie-break paging
   on identical timestamps, every illegal transition, restock exactly once.
9. **Concurrency tests.** Real threads released together by a `CyclicBarrier`, no `@Transactional` on the test so
   transactions really overlap. They assert invariants (exactly one 201, losers are 409 or 422, stock never negative,
   order count equals accepted count), not a specific loser status, because two interleavings are both valid.
10. **`mvn verify` green (91 tests), then the mutation check.** Wrote `scripts/mutation_check.py` (35 textual
    mutations, each required to match exactly once, file restored in a `finally` block) and ran it in the background:
    32 killed, 3 survived (M02 `Order` without `@Version`, M09 SKU length prefix, M14 key length limit). All three were
    real gaps, so I added `staleStatusWriteIsRejectedByOptimisticLock`, rewrote `boundariesAreUnambiguous` (the old
    inputs never collided) and added `idempotencyKeyLengthLimitIs128`, then re-ran those mutations: all killed.
11. **Ran the real app** (`java -jar`) and pasted the curl examples in the README from actual responses. That showed a
    bug no test had caught: the 201 body had `createdAt` with nanoseconds but H2 stores microseconds, so a replay or
    GET returned a slightly different timestamp. `OrderService` now truncates to microseconds; a new test compares the
    201 body, the GET and the replay. Added mutations M36 (customer length prefix; my first test for it was too weak and
    the mutant survived, the second version killed it) and M37 (no truncation, killed). Final: 95 tests, 37 of 37 mutations
    killed, M33 and M34 (race-dependent) re-run twice with the same result, `mvn -q -B verify` green.
12. **Docs.** README, this file. CI status deliberately not claimed.

## Part B: How to run it yourself

Requirements: JDK 17 or newer, Maven 3.9.

```bash
git clone https://github.com/Supp3r-Man/spring-boot-orders-api.git && cd spring-boot-orders-api
mvn -q -B verify                       # compiles, runs all tests, builds target/spring-boot-orders-api-0.1.0.jar
```

Start it (port 8080; add `--server.port=8099` to the jar command to change it). Data lives in memory and is gone
when the process stops:

```bash
mvn spring-boot:run
# or
java -jar target/spring-boot-orders-api-0.1.0.jar
```

Then try the curl walk-through in the README: create two items, place an order, replay it with the same key, reuse the
key with a different body, push stock too low, walk the order through NEW, PAID, SHIPPED.

Run the mutation check (about 25 to 40 minutes, the whole Spring suite runs once per mutation):

```bash
python scripts/mutation_check.py            # all mutations
python scripts/mutation_check.py M06 M15    # selected ones
python scripts/mutation_check.py --list     # just print them
```

Set `MVN` to the Maven executable if `mvn` is not on PATH. The script reports a mutation whose match text is missing
or not unique as `BROKEN`, never as a survivor. Do not edit the sources while it runs; it restores each file from the
bytes it read before the first mutation of that file.
