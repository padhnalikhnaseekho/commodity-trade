# Glossary
Quota, assignment, lot, QAG / QAGR, BRD, fixation, provisional price, priced/unpriced quantity.
Each entry will point at the class that models it. Filled in as phases land.

## Modelled so far (P0.1)
- **Trade / Quota / Assignment refs**: `contracts/.../refs/`. Nested text refs, e.g. 1, 1.1, 1.1.1.
- **Trade, Quota**: `services/trade/.../domain/`.
- **Assignment**: `services/logistics/.../domain/Assignment.java` (the commercial allocation inside a quota).
- **QAG / QAGR** (quota-assignment-graph and its revisions): `QagRevision`, `QagRevisionMember`. Cut on material changes only.
- **PQR / PAR** (pricing quota revision, pricing assignment revision): `QuotaRevision`, `AssignmentRevision`, linked by `QuotaRevisionMember` (the sharing table).
- **Price component / parameter**: `PriceComponent`, `ParameterRevision`. Unpriced quantity is derived, never stored.
- **BRD** (business reporting date): the per-desk as-of date every revision pins to; a plain `LocalDate` column.

## Added in P0.2
- **Change kind**: material (cuts a QAGR) vs operational (does not). `contracts/.../events/ChangeKind.java`.
- **Quota quantity cap**: only ACTIVE assignments count. `logistics/.../domain/QuotaQuantityPolicy.java`.
- **QAG diff**: added / removed / modified between revisions. `logistics/.../domain/QagDiff.java`.
- **Outbox**: event rows written with the business change, shipped later. `platform/.../outbox/`.
- **Envelope**: eventId, occurredAt, brd, source, schemaVersion, traceparent as Kafka headers. `KafkaMessageSink`.
