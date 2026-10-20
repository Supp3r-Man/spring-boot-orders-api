#!/usr/bin/env python
"""Hand-rolled mutation check: apply one textual mutation at a time, run the test suite,
record whether at least one test failed, then restore the original file.

Usage:  python scripts/mutation_check.py             (all mutations)
        python scripts/mutation_check.py M05 M10     (selected ids)
        python scripts/mutation_check.py --list      (print the mutations, run nothing)
Set MVN to the Maven executable if `mvn` is not on PATH.

Each mutation replaces the ONLY occurrence of `old` in the named file with `new`. A mutation whose
`old` text is missing or appears more than once is reported as BROKEN (the script is out of date),
never as a survivor. Files are always restored from the original bytes, even on Ctrl-C.
Every run starts the whole Spring test suite, so expect roughly a minute per mutation.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "github", "sup3rmar10", "orders")
MVN = os.environ.get("MVN", "mvn")

# (id, description, relative path under SRC, old text, new text)
MUTATIONS = [
    # --- optimistic locking and stock arithmetic
    ("M01", "Item loses its @Version (stock updates can overwrite each other)", "domain/Item.java",
     "    @Version\n    private long version;", "    private long version;"),
    ("M02", "Order loses its @Version", "domain/Order.java",
     "    @Version\n    private long version;", "    private long version;"),
    ("M03", "reserve refuses to sell the last unit (> becomes >=)", "domain/Item.java",
     "if (quantity > stock) {", "if (quantity >= stock) {"),
    ("M04", "reserve does not decrement stock", "domain/Item.java",
     "stock -= quantity;", "stock -= 0;"),
    ("M05", "restock does nothing", "domain/Item.java",
     "stock += quantity;", "stock += 0;"),
    # --- idempotency
    ("M06", "same key with a different body is no longer rejected", "service/OrderService.java",
     "if (!existing.get().getRequestHash().equals(hash)) {", "if (false) {"),
    ("M07", "fingerprint ignores the customer", "service/RequestFingerprint.java",
     "StringBuilder canonical = new StringBuilder(\"customer=\").append(customerRef.length()).append(':')\n"
     "                .append(customerRef);",
     "StringBuilder canonical = new StringBuilder(\"customer=\");"),
    ("M08", "fingerprint ignores quantities", "service/RequestFingerprint.java",
     ".append(\"|qty=\").append(qty)", ".append(\"|qty=\")"),
    ("M09", "fingerprint drops the length prefix on SKUs", "service/RequestFingerprint.java",
     ".append(\"|sku=\").append(sku.length()).append(':').append(sku)", ".append(\"|sku=\").append(sku)"),
    ("M10", "duplicate SKU lines overwrite instead of summing", "service/OrderService.java",
     "wanted.merge(command.sku(), command.quantity(), Integer::sum);",
     "wanted.merge(command.sku(), command.quantity(), (a, b) -> b);"),
    ("M11", "replay is reported as a fresh placement (no replayed flag)", "service/OrderService.java",
     "return new Placement(existing.get(), true);", "return new Placement(existing.get(), false);"),
    ("M12", "replay response loses the Idempotent-Replayed header", "web/OrderController.java",
     ".header(REPLAYED, \"true\").body(body)", ".body(body)"),
    ("M13", "replay answers 201 instead of 200", "web/OrderController.java",
     "ResponseEntity.ok().header(REPLAYED", "ResponseEntity.status(HttpStatus.CREATED).header(REPLAYED"),
    ("M14", "Idempotency-Key length limit removed", "web/OrderController.java",
     "@NotBlank @Size(max = 128) String idempotencyKey", "@NotBlank @Size(max = 100000) String idempotencyKey"),
    # --- pricing and rounding
    ("M15", "discount rounds HALF_EVEN instead of HALF_UP", "service/PricingService.java",
     "RoundingMode.HALF_UP", "RoundingMode.HALF_EVEN"),
    ("M16", "tier threshold is exclusive (>= becomes >)", "service/PricingService.java",
     "subtotalMinor >= tier.thresholdMinor()", "subtotalMinor > tier.thresholdMinor()"),
    ("M17", "line total no longer detects overflow", "service/PricingService.java",
     "Math.multiplyExact(unitPriceMinor, (long) quantity)", "unitPriceMinor * (long) quantity"),
    ("M18", "total ignores the discount", "service/PricingService.java",
     "new Quote(subtotal, percent, discount, subtotal - discount)", "new Quote(subtotal, percent, discount, subtotal)"),
    # --- status machine
    ("M19", "PAID can no longer be cancelled", "domain/OrderStatus.java",
     "case PAID -> Set.of(SHIPPED, CANCELLED);", "case PAID -> Set.of(SHIPPED);"),
    ("M20", "NEW may jump straight to SHIPPED", "domain/OrderStatus.java",
     "case NEW -> Set.of(PAID, CANCELLED);", "case NEW -> Set.of(PAID, CANCELLED, SHIPPED);"),
    ("M21", "SHIPPED is no longer terminal (may be cancelled)", "domain/OrderStatus.java",
     "case SHIPPED, CANCELLED -> Set.of();", "case SHIPPED -> Set.of(CANCELLED);\n            case CANCELLED -> Set.of();"),
    ("M22", "cancelling does not restock", "service/OrderService.java",
     "if (target == OrderStatus.CANCELLED) {", "if (false) {"),
    # --- filters, paging
    ("M23", "createdAfter becomes inclusive", "repo/OrderSpecs.java",
     "cb.greaterThan(root.get(\"createdAt\"), createdAfter)", "cb.greaterThanOrEqualTo(root.get(\"createdAt\"), createdAfter)"),
    ("M24", "status filter ignored", "repo/OrderSpecs.java",
     "cb.equal(root.get(\"status\"), status)", "cb.conjunction()"),
    ("M25", "id tie-breaker removed from the sort", "service/OrderService.java",
     "Sort.by(Sort.Order.desc(\"createdAt\"), Sort.Order.desc(\"id\"))", "Sort.by(Sort.Order.desc(\"createdAt\"))"),
    ("M26", "list is oldest first", "service/OrderService.java",
     "Sort.Order.desc(\"createdAt\")", "Sort.Order.asc(\"createdAt\")"),
    ("M27", "page size cap raised from 100 to 1000", "web/OrderController.java",
     "@Min(1) @Max(100) int size", "@Min(1) @Max(1000) int size"),
    ("M28", "negative page numbers accepted", "web/OrderController.java",
     "@Min(0) int page", "@Min(-5) int page"),
    # --- validation and problem details
    ("M29", "line quantity 0 accepted by validation", "web/Dtos.java",
     "@Min(1) @Max(1000) int quantity", "@Min(0) @Max(1000) int quantity"),
    ("M30", "line quantity upper bound removed", "web/Dtos.java",
     "@Min(1) @Max(1000) int quantity", "@Min(1) @Max(100000) int quantity"),
    ("M31", "nested field paths (lines[0].quantity) not reported", "web/ApiExceptionHandler.java",
     "if (result instanceof ParameterErrors body) {", "if (result instanceof ParameterErrors body && false) {"),
    ("M32", "problem documents lose the machine-readable code", "web/ApiExceptionHandler.java",
     "        pd.setProperty(\"code\", code);\n", ""),
    ("M33", "optimistic-lock loser answers 500 instead of 409", "web/ApiExceptionHandler.java",
     "return problem(ex, HttpStatus.CONFLICT, \"concurrent-update\",",
     "return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, \"concurrent-update\","),
    ("M34", "unique-constraint race answers 500 instead of 409", "web/ApiExceptionHandler.java",
     "return problem(ex, HttpStatus.CONFLICT, \"conflict\",",
     "return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, \"conflict\","),
    ("M35", "duplicate SKU pre-check removed (relies on the unique index)", "service/CatalogService.java",
     "if (items.findBySku(sku).isPresent()) {", "if (false) {"),
    # --- added after the first run (tests were strengthened or a bug was fixed, see README)
    ("M36", "fingerprint drops the length prefix on the customer", "service/RequestFingerprint.java",
     "new StringBuilder(\"customer=\").append(customerRef.length()).append(':')", "new StringBuilder(\"customer=\")"),
    ("M37", "createdAt not truncated to microseconds (201 body differs from stored value)", "service/OrderService.java",
     "clock.instant().truncatedTo(ChronoUnit.MICROS)", "clock.instant()"),
]


def run_tests():
    try:
        p = subprocess.run([MVN, "-B", "-q", "test"], cwd=ROOT, capture_output=True, text=True, timeout=900)
    except subprocess.TimeoutExpired:
        return "timeout", []
    out = p.stdout + p.stderr
    if "COMPILATION ERROR" in out:
        return "compile-error", []
    failing = sorted(set(re.findall(r"\[ERROR\]\s{2,}(\w+Test\.\w+)", out)))
    return ("killed" if p.returncode != 0 else "survived"), failing


def main():
    args = sys.argv[1:]
    if "--list" in args:
        for mid, desc, fname, _old, _new in MUTATIONS:
            print(f"{mid} | {desc} | {fname}")
        return
    wanted = set(args)
    originals = {}
    results = []
    try:
        for mid, desc, fname, old, new in MUTATIONS:
            if wanted and mid not in wanted:
                continue
            path = os.path.join(SRC, *fname.split("/"))
            if fname not in originals:
                with open(path, "rb") as f:
                    originals[fname] = f.read()
            text = originals[fname].decode("utf-8").replace("\r\n", "\n")
            if text.count(old) != 1:
                label = "BROKEN (pattern not found)" if old not in text else "BROKEN (pattern not unique)"
                results.append((mid, desc, label, []))
                print(f"{mid} | {desc} | {label}", flush=True)
                continue
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text.replace(old, new, 1))
            try:
                status, failing = run_tests()
            finally:
                with open(path, "wb") as f:
                    f.write(originals[fname])
            label = {"killed": "KILLED", "survived": "SURVIVED", "compile-error": "COMPILE ERROR (invalid)",
                     "timeout": "KILLED (timeout)"}[status]
            results.append((mid, desc, label, failing))
            print(f"{mid} | {desc} | {label} | {len(failing)} failing: {', '.join(failing[:3])}", flush=True)
    finally:
        for fname, data in originals.items():
            with open(os.path.join(SRC, *fname.split("/")), "wb") as f:
                f.write(data)
    killed = sum(1 for r in results if r[2].startswith("KILLED"))
    print("\n| id | mutation | result |")
    print("|---|---|---|")
    for mid, desc, label, failing in results:
        print(f"| {mid} | {desc} | {label} |")
    print(f"\n{killed}/{len(results)} mutations killed")


if __name__ == "__main__":
    main()
