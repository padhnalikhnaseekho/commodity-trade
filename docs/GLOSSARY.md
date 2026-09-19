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

## Added in P0.3
- **Structural sharing / copy-on-write revisions**: unchanged assignments are pointed at, not copied. `StructuralSharingRevisionWriter`.
- **Content hash**: SHA-256 over an assignment's fields and its children's hashes; equal hash = equal content. `ContentHasher`.
- **Quota revision member (the sharing table)**: which assignment revisions a quota revision points at. `quota_revision_member`.
- **Fixation**: pricing part of an assignment's quantity at a price (a price component). Unpriced quantity = quantity - fixed, derived.
- **Over-fixed**: fixed quantity exceeds the assignment quantity; only reachable through the logistics/pricing race, reported not stored.
- **Approval**: pricing's insert-only decision that gates eligibility for valuation and P&L. `assignment_approval`.
- **Dedup marker / DLQ**: `processed_event` (consumer-side eventId dedup) and `<topic>.dlq` (parked poison messages).
