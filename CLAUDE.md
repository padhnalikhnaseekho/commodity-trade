# Commodity Trade Demo - working conventions

## What this is
A portfolio demo of a commodity trading platform's core write path
(trade -> quota -> assignment -> QAG revision -> pricing revision -> valuation).
Built phase by phase (P0.1 to P0.5). Not production. Used for interview preparation,
so the code must teach: see "Comment standard".

## Naming
Use neutral names only: package `io.commodity.<service>`, config prefix `commodity.*`,
error URLs `https://commodity.demo/errors/...`. Valuation engines are `LEGACY` / `MODERN`.
Do not use any employer or vendor product names in code, comments, config or docs.

## Non-negotiable rules
1. DO NOT INVENT DOMAIN RULES. If the spec does not state a business rule, stop and ask.
2. Revisions are immutable. Insert only on assignment_revision, parameter_revision,
   price_component, quota_revision. No UPDATE or DELETE. Ever.
3. Derived values are never stored (pricedQty, unpricedQty, expected lot).
4. Domain logic has no framework. Ref generation, hashing, invariants and revision
   resolution live in `domain/` as plain Java, testable without Spring or a database.
5. No service module imports another service module. Only `contracts`. Enforced in the root build.gradle.kts.

## Style
- Java 21. Records for DTOs/value objects. Sealed interfaces for closed hierarchies. No Lombok.
- BigDecimal for quantity and money, never double. Scale 4 for quantity, 6 for price.
- Constructor injection only. Package-private by default.

## Comment standard (every source file)
1. File header (3-6 lines): what it is, why it exists, where it sits in the flow,
   and the interview talking point it supports.
2. `WHY:` on non-obvious decisions, naming the constraint served.
3. `TRADEOFF:` where an alternative was rejected, with what it costs.
4. `LEGACY:` / `TARGET:` where the demo substitutes for the target design.
5. `INVARIANT:` on domain rules, naming the test that proves it.
6. Worked examples in Javadoc for tricky logic.
7. Tests carry a one-line `PROVES:` comment stating the claim they back.
8. Never narrate the obvious. Explain why and what to say in an interview.
9. Never attribute authorship to any AI/assistant in code, comments, commits or docs.

## Testing
- Domain: plain JUnit 5. Repositories/consumers: Testcontainers.
- Structural sharing: property-based with jqwik; the equivalence property is the most important test.
- Every exit criterion in the phase spec must exist as a real test.

## Commits
One commit per phase; message starts with the phase id (`P0.3: ...`). Never commit failing tests.
No AI attribution or co-author trailers.

## When stuck
Ask. Do not guess domain semantics and do not widen scope beyond the phase.
