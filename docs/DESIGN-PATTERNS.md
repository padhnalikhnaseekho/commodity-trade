# Design patterns used, by name

Interview prep index. Each pattern names where it lives in the code, why it was chosen, what it costs, and the test that backs it.
Only patterns present in the code are listed; patterns that are TARGET-only are marked as such.

## Where this is already covered

| Need | Read |
|---|---|
| The claims and how to say them | `docs/INTERVIEW-PITCH.md` sections 2A to 2M6 (improvements with `Say:` and `Proof:` lines) |
| "Where is X in the code?" | `docs/LEARNING-GUIDE.md` (question to file/test table) |
| Terms | `docs/GLOSSARY.md` |
| Per-phase decisions | `docs/phase-notes/P0.1.md` to `P0.5.md` |
| Saga and rollback (designed, not built) | `docs/saga-actualization-orchestrator.md` |

The pitch is organised by improvement, and none of those docs indexes patterns by name. This file is that index, so an interviewer's
"which pattern is that?" has a one-line answer and a file to point at.

## Summary

| # | Pattern | Category | Main file | Proof |
|---|---|---|---|---|
| 1 | Transactional outbox + polling publisher | Messaging | `platform/outbox/OutboxWriter`, `OutboxPublisher` | `OutboxPublisherTest` (`rolledBackTransactionPublishesNothing`, `failedDeliveryStaysUnsentAndIsRetriedInOrder`) |
| 2 | Idempotent consumer | Messaging | `platform/eventing/DedupStore`, `pricing/service/QagRevisionHandler` | `QagRevisionConsumerTest.aRedeliveredEventIsAppliedOnce`, `PricingApiTest.aDuplicateEventIsAppliedOnceAndAFailedAttemptCanBeRetried` |
| 3 | Retry then dead-letter queue | Messaging | `platform/eventing/DlqErrorHandler` | `QagRevisionConsumerTest.aPoisonMessageGoesToTheDlqWithoutStallingThePartition`, `GatewayFlowTest.aMalformedReplyGoesToTheDlqAndTheGatewayKeepsWorking` |
| 4 | Event-carried state transfer | Messaging | `logistics/domain/QagDiff`, `QagRevisionMember`, `pricing/service/PricingPublisher` | `AssignmentApiTest` (`addingAnAssignmentCuts...`, `modifyingOneOfTwenty...`), `PricingApiTest.everyPricingChangeAndApprovalPublishesAQuotaSnapshot` |
| 5 | Partition key = aggregate id | Messaging | `platform/outbox/OutboxMessage` | `OutboxPublisherTest` proves the key is carried and per-key order kept; choosing the aggregate id is by convention, not tested |
| 6 | Ports and adapters | Structure | `contracts/lookup/*`, `platform/lookup/Http*`, `services/stubs` | `HttpQuotaDirectoryTest`, `GatewayFlowTest.theEngineRequestIsSelfContained` |
| 7 | Modular monolith, profile-selected modules | Structure | `gateway/GatewayModule`, `stubs/StubsModule`, root `build.gradle.kts` | `ProfileIsolationTest`; the boundary rule is checked by the root build, not a test |
| 8 | Functional core, imperative shell | Structure | `domain/` packages vs `service/` packages | `QuotaQuantityPolicyTest`, `FixationAndEventTest`, `RowPatchTest`, `LaneLimiterTest` |
| 9 | Immutable, insert-only revisions | Data | `pricing/domain/QuotaRevision`, DB trigger | `PricingRepositoryTest.databaseRejectsUpdateAndDeleteOnRevisionTables` |
| 10 | Structural sharing (copy-on-write, content-addressed) | Data | `pricing/service/StructuralSharingRevisionWriter`, `domain/ContentHasher` | `EquivalencePropertyTest`, `ContentHasherTest`, `RevisionWritersTest.changingOneOfTwentyAssignmentsWritesFewerRowsUnderSharing` |
| 11 | Strategy behind a config switch | Data | `pricing/service/RevisionWriter` | `EquivalencePropertyTest`, `RevisionWritersTest.resolutionIsIdenticalUnderBothStrategies` |
| 12 | Derived values never stored | Data | `pricing/domain/AssignmentContent` | `ContentHasherTest.pricedUnpricedAndOverFixedAreDerived`, `PricingApiTest.approvalIsAnInsertOnlyRecordAndDoesNotCutARevision` |
| 13 | Per-aggregate advisory lock | Concurrency | `PricingService`, `AssignmentService` | `AssignmentApiTest.concurrentWritersToOneQuotaProduceALinearChainAndDistinctRefs`, `PricingApiTest.concurrentFixationsCannotTogetherOverFixTheAssignment` |
| 14 | Compare-and-set state machine | Concurrency | `gateway/repository/RequestStore`, `service/ReplyHandler` | `GatewayRestartTest`, `GatewayFlowTest.aDuplicateReplyChangesNothing` |
| 15 | Idempotency key from immutable inputs | Resilience | `gateway/domain/RequestKey` | `RequestKeyTest`, `GatewayFlowTest.submittingTheSameRequestTwiceCallsTheEngineOnce` |
| 16 | Bulkhead (per-lane in-flight budget) | Resilience | `gateway/domain/LaneLimiter`, `service/Dispatcher` | `LaneLimiterTest`, `GatewayLanesTest.saturatingBulkLeavesInteractiveInsideItsTimeout` |
| 17 | Watchdog with bounded retry | Resilience | `gateway/service/Watchdog` | `GatewayFlowTest` (`aLostReplyIsRetriedByTheWatchdogAndThenCompletes`, `aRequestThatNeverGetsAReplyEndsFailedAfterThreeAttempts`) |
| 18 | Fail closed on lookups; timeouts on every call | Resilience | `platform/lookup/HttpFixationDirectory`, `HttpClients` | `HttpQuotaDirectoryTest` (`fixationAdapterMapsAnswersAndFailsClosedOnOutage`, `aServerErrorPropagatesInsteadOfLookingLikeAMissingQuota`); timeouts themselves are untested |
| 19 | Materialised read model (projection) | Read side | `blotter/service/BlotterProjector`, `domain/RowView` | `BlotterProjectorTest`, `BlotterStreamTest.aPricingChangeReachesAnOpenBlotterWithinASecond` |
| 20 | Delta push: JSON Patch, coalescing, resumable SSE | Read side | `blotter/domain/RowPatch`, `service/RowBroadcaster` | `RowPatchTest.mergedPatchesAreEquivalentToTheSequence`, `RowBroadcasterTest` |
| 21 | Read-through cache | Read side | `blotter/service/CachedQuotaLookup` | no dedicated test; exercised inside `BlotterProjectorTest` |
| 22 | Reference data drives routing | Domain | `contracts/lookup/FunctionalLine*`, `stubs/srd` | `InMemoryFunctionalLineDirectoryTest`, `GatewayFlowTest.theFunctionalLineDecidesTheEngine` |
| 23 | Hierarchical composite keys | Domain | `contracts/refs/TradeRef`, `QuotaRef`, `AssignmentRef` | `RefsTest` |
| 24 | Problem Details error translation | API | `platform/error/DomainException`, `ProblemAdvice` | `AssignmentApiTest.exceedingTheQuotaIs409WithNumbersAndLeavesNoTrace`, `PricingApiTest.overFixationIs409WithTheOffendingQuantities` |
| 25 | Sequence, not timestamp, for ordering | Data | pricing V4 and logistics V4 migrations | `RevisionWritersTest.aLaterRevisionWithAnEarlierTimestampStillWins` |

Every test named here was found by reading the test sources. I did not run them, so "proof" means the test exists and its name states the claim. Gaps are called out in the row.

## Messaging

### 1. Transactional outbox with a polling publisher
- **What:** a service writes its business change and an event row in the SAME database transaction; a separate publisher ships unsent rows to Kafka.
- **Why:** direct send after commit can be lost, and send before commit can announce a rollback. The outbox makes "state changed" and "event will be published" one atomic fact.
- **Details:** `FOR UPDATE SKIP LOCKED` lets several publishers poll without double-sending. Rows are marked sent only after the broker acknowledges. On the first failure the batch stops, preserving order.
- **Tradeoff:** up to one poll interval of latency; per-key order holds only with one publisher. Target: CDC on the outbox table plus consumer-side version checks.
- **Say:** "At-least-once delivery, atomic with state. Consumers dedup."

### 2. Idempotent consumer
- **What:** `DedupStore.firstTime(eventId)` is an atomic insert-if-absent inside the SAME transaction that applies the event.
- **Why:** the outbox is at-least-once, so duplicates are certain. A crash rolls back the marker with the change, so the redelivery is processed for real, never "marked but not applied".
- **Tradeoff:** a TTL sweep bounds the table; an event redelivered after the TTL would apply twice. Target: a distributed cache with a TTL.

### 3. Retry then dead-letter queue
- **What:** fixed retries, then the record goes to `<topic>.dlq` and the partition moves on.
- **Why:** a poison message must never stall everything behind it. Alerting on DLQ depth above zero makes silent failure visible.
- **Tradeoff:** retries briefly block the partition. Target: non-blocking retries on delay topics (5s, 30s, 5m).

### 4. Event-carried state transfer
- **What:** the QAG revision event carries the diff AND the full member list; pricing's snapshot event carries the full quota with derived quantities.
- **Why:** consumers never read back from the producer, so they stay decoupled and available. The producer classifies material vs operational and publishes what changed, so no two consumers can disagree.
- **Tradeoff:** a few extra rows and bytes per event in exchange for consumers that never call the producer.

### 5. Partition key is the aggregate id
- **What:** the Kafka key is `quotaRef` for the revision chain and `assignmentRef` for assignment events. Never a deal id.
- **Why:** ordering is per key only; a desk-default deal would put a whole desk on one partition.

## Structure

### 6. Ports and adapters (hexagonal)
- **What:** `contracts` holds interfaces (`QuotaDirectory`, `FixationDirectory`, `BusinessDayClock`, `FunctionalLineDirectory`, `ValuationInputsProvider`, `ValuationSubmitter`). HTTP adapters live in `platform/lookup`; in-memory stubs live in `services/stubs`.
- **Why:** a service may not import another service (enforced in the root build). Swapping a stub for the real service changes no caller.
- **Tradeoff:** the stubs' ports are in-process Java interfaces, not HTTP yet, so those stubs must run in the same process (stated in `StubsModule`).

### 7. Modular monolith with profile-selected modules
- **What:** one deployable; each service sits behind its own Spring profile; the app scans nothing else. Each service owns its schema (`SchemaMigration`).
- **Why:** splitting a service out becomes a change of profile and base URLs, not a rewrite.
- **Say honestly:** the single JVM hides real network failure, and all services share one database server.

### 8. Functional core, imperative shell
- **What:** rules live in `domain/` as plain Java (`QuotaQuantityPolicy`, `FixationPolicy`, `BrdGuard`, `QuantitySplitter`, `ContentHasher`, `EventApplier`, `RowPatch`, `LaneLimiter`); `service/` classes own transactions and orchestration.
- **Why:** rules are testable without Spring or a database, and the code an interviewer reads is the code that matters.

## Data

### 9. Immutable, insert-only revisions
- **What:** revision tables allow INSERT only. Hibernate `@Immutable` plus a database trigger that rejects UPDATE and DELETE.
- **Why:** structural sharing and replay are only safe if rows never change.

### 10. Structural sharing (copy-on-write, content-addressed)
- **What:** each assignment gets a bottom-up SHA-256 content hash. If a row with that (ref, hash) exists it is reused; the new quota revision links to new and reused rows through `quota_revision_member`.
- **Why:** 35 rows instead of 301 for one change out of twenty. Same idea as Git trees.
- **Worked example:** 1.0x when everything changes; 8.6x when one of twenty changes.
- **Tradeoff:** cheaper writes, costlier reads (a join), and the link table still grows one row per assignment per revision.
- **Proof:** the equivalence property is the most important test in the repo.

### 11. Strategy behind a config switch
- **What:** `RevisionWriter` has two implementations, `StructuralSharingRevisionWriter` and `CopyAllRevisionWriter`, chosen by `commodity.pricing.revision-strategy`.
- **Why:** turn sharing off without a release; the legacy writer is also the control in the benchmark and the equivalence property.

### 12. Derived values never stored
- **What:** priced, unpriced and over-fixed quantities are computed on read and sent on events. Approval is its own insert-only record.
- **Why:** storing a derived value means rewriting it on every change, the amplification the design removes.

### 25. Sequence, not timestamp, for ordering
- **What:** "revision in force" is decided by insertion sequence, not `created_at`.
- **Why:** wall clocks step backwards (NTP, hypervisor sync). Safe because all writers of a quota hold its lock, so id order is logical order.

## Concurrency

### 13. Per-aggregate advisory lock
- **What:** a per-quota lock around read-decide-write for the revision chain, reference sequence, quantity cap and over-fixation.
- **Why:** two simultaneous fixations must not both pass the check against the same head. Other quotas run in parallel.
- **Tradeoff:** chosen over optimistic retry for simplicity at this write rate.

### 14. Compare-and-set state machine
- **What:** every gateway state change is `UPDATE ... WHERE status = ...`; whoever wins the transition acts, others see zero rows.
- **Why:** duplicate replies, a watchdog racing a late reply, and several instances are all safe with no in-memory coordination. Replies correlate through the database, so a killed gateway resumes from durable state.

## Resilience

### 15. Idempotency key from immutable inputs
- **What:** `RequestKey` is a hash of every immutable id and date the answer depends on.
- **Why:** a valuation is a pure function of its inputs, so retries are free, duplicates collapse, and replay is a cache hit (`cached: true`).
- **Volunteer:** the key includes the quota revision id, so repricing one assignment changes every key in that quota even though sharing left the other rows unchanged.

### 16. Bulkhead
- **What:** an independent in-flight budget per lane (interactive, bulk). A concurrency limit, not a rate limit.
- **Why:** a bulk sweep can fill only its own budget. Requests beyond the budget stay PENDING in the database.
- **Detail:** built on atomic counters, not `Semaphore`, so it can start from the durable SENT count after a restart and refuse to release more permits than were taken.
- **Tradeoff:** the budget is in memory (one gateway instance). Target: derive from the durable table or a distributed semaphore, with adaptive limits.

### 17. Watchdog with bounded retry
- **What:** requests SENT too long are retried, then FAILED when the attempt budget is spent.
- **Why:** the stored request is self-contained, so a retry needs nothing from any other service.

### 18. Fail closed, and timeouts everywhere
- **What:** a pricing outage blocks a quantity edit instead of allowing one that might break the fixation rule. A missing resource (404) is distinguished from an outage.
- **Why:** an outage must never be mistaken for "no such thing". Timeouts stop one slow service collapsing its callers.
- **Target only:** Resilience4j retries with jitter and a circuit breaker. Not built.

## Read side

### 19. Materialised read model
- **What:** the blotter is a flat, denormalised, disposable projection assembled from pricing snapshots and valuation results.
- **Why:** the UI never joins across services; the view can be rebuilt from event streams. Changes are offered to subscribers only AFTER COMMIT.
- **Note:** `provisional` is derived at read time from current approval state, never stored as current.

### 20. Delta push with a resumable stream
- **What:** JSON Patch (RFC 6902) per row, merged every 250 ms so a row changed nine times is pushed once; SSE with `Last-Event-ID` and a bounded replay buffer; a client away too long is told to reset.
- **Proof:** `RowPatch.merge` equals applying all changes in order.

### 21. Read-through cache
- **What:** `CachedQuotaLookup` caches only FOUND quotas, so a quota asked about too early is retried, not remembered as missing.
- **Target:** a distributed cache with event-driven invalidation.

## Domain and API

### 22. Reference data drives routing
- **What:** which valuation engine handles a trade is a `FunctionalLine` from reference data (cutover date, optional override).
- **Why:** retiring the legacy engine is a data change and a migration, not a release.

### 23. Hierarchical composite keys
- **What:** trade `1`, quota `1.1`, assignment `1.1.3`, generated by plain Java and stored as text.
- **Why:** a ref tells its ancestry with no lookup, so logs and support read naturally.

### 24. Problem Details error translation
- **What:** domain code throws `DomainException`; one advice renders RFC 7807 `application/problem+json` with the failing numbers.
- **Why not blanket IllegalArgumentException:** it would hide programming and configuration errors as "400". Only explicit `DomainException` is a client error.

## Patterns I did not use (say so if asked)
- **Event sourcing:** revisions are immutable and replayable, but state is not rebuilt by folding an event log. Do not claim it.
- **Saga:** designed only, in `docs/saga-actualization-orchestrator.md`. Not built.
- **CDC, circuit breaker, distributed cache, adaptive limits:** named as TARGET in the code comments above.
- **Classic GoF:** only Strategy (11) and Adapter (6) are used deliberately. Do not stretch others to fit.
