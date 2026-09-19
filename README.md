# Commodity Trade Demo

Core write path of a commodity trading platform: trade capture with nested refs, quotas and
assignments, QAG revisions carrying the diff, copy-on-write pricing revisions (structural sharing),
an idempotent valuation gateway, and a thin blotter.

## Built vs designed
- P0.1 built: schemas, entities, refs, boundary-enforced modules.
- P0.2 built: quota derivation, assignment write path, QAG revisions with diff events, transactional outbox.
- P0.3 built: structural sharing (both strategies, benchmark, equivalence property), pricing API, QAG revision consumer with dedup and DLQ.
- P0.4-P0.5 pending. P1-P3 are designed only.

## Substitutions from the target design
| Target | Demo |
|---|---|
| Oracle Exadata | PostgreSQL 16 |
| Kafka on MSK | Redpanda (single node) |
| Distributed cache | Caffeine in-process |
| Valuation engines | Deterministic stub engine |
| OIDC + policy engine | Hard-coded principal |
| Avro + Schema Registry | JSON |

## Services share one JVM under Spring profiles - by choice
Module boundaries are real and enforced in the build; a single JVM just avoids five terminals.

## Docs
See `docs/LEARNING-GUIDE.md`, `docs/GLOSSARY.md`, `docs/phase-notes/`.
