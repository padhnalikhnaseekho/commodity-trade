# Learning guide
Reading order per phase, the 5-minute demo script, and an "interview question -> file/test" table.
Filled in as each phase lands.

| Interview question | Where the answer lives |
|---|---|
| Why nested refs like 1.1.1, and what is the catch? | `contracts/.../refs/*`, `RefsTest.naturalOrderingIsNumericNotLexicographic` |
| How do you guarantee revisions are never mutated? | `pricing V1__pricing_schema.sql` (trigger), `PricingRepositoryTest.databaseRejectsUpdateAndDeleteOnRevisionTables` |
| How do you keep service boundaries honest in a monorepo? | root `build.gradle.kts` boundary check |
| Why an outbox instead of publishing to Kafka directly? | `platform/.../outbox/OutboxWriter.java`, `OutboxPublisherTest.rolledBackTransactionPublishesNothing` |
| What happens when the broker is down? | `OutboxPublisherTest.failedDeliveryStaysUnsentAndIsRetriedInOrder` |
| How do you stop an event storm from flooding revisions? | `ChangeKind`, `AssignmentApiTest.operationalChangeCutsNoRevision...` |
| Why publish the diff instead of a notification? | `QagRevisionEvent`, `QagDiff` |
| How do concurrent writers not fork the revision chain? | `AssignmentService.lockQuota`, `AssignmentApiTest.concurrentWriters...` |
| How do services read each other's data without coupling? | `contracts/.../lookup/*`, `HttpQuotaDirectory` |
| What was your biggest scaling problem and fix? | `StructuralSharingRevisionWriter`, `./gradlew :benchmark:run`, `docs/phase-notes/P0.3.md` |
| How do you know the optimisation did not change behaviour? | `EquivalencePropertyTest` (and the broken-writer test that proves it can fail) |
| How do you restate a number from a past date? | `RevisionStore.asOf`, `RevisionWritersTest` determinism and monotonicity tests |
| Why must revisions be insert-only? | `pricing V1__pricing_schema.sql` trigger, `PricingRepositoryTest`, structural sharing safety |
| What if the same event arrives twice, or a bad one? | `QagRevisionHandler`, `DedupStore`, `QagRevisionConsumerTest` |
| How is over-fixation prevented under concurrency? | `PricingService.mutate` lock, `PricingApiTest.concurrentFixations...` |
| Where does approval live and why? | `V2__approval_dedup_and_indexes.sql`, `PricingApiTest.approvalIsAnInsertOnlyRecord...` |
| Why can the valuation gateway be idempotent? | `RequestKey`, `RequestKeyTest`, `docs/phase-notes/P0.4.md` |
| What happens if the gateway dies mid-request? | `GatewayRestartTest`, `ReplyHandler`, `GatewayConfig.seedLaneLimiter` |
| How do you stop bulk work starving interactive work? | `LaneLimiter`, `Dispatcher`, `GatewayLanesTest` |
| How is a lost or failed engine call handled? | `Watchdog`, `RequestStore.failOrRetry`, `GatewayFlowTest` retry tests |
| Why is replay a cache hit, and is it read-only? | `ReplayService`, `PricingApiTest.replay...`, `ValuationService` (cache before BRD check) |
| How is the engine migration expressed? | `InMemoryFunctionalLineDirectory` (cutover date + override) |
| What does the engine adapter depend on? | Nothing but contracts and platform: `services/stubs/build.gradle.kts` |
| Why must you never order revisions by a timestamp? | `RevisionStore.asOf`, `V4__order_by_sequence.sql`, `RevisionWritersTest.aLaterRevisionWithAnEarlierTimestampStillWins` |
| How do you run one JVM and still claim independent services? | `PricingModule` (profile), `ProfileIsolationTest`, `CommodityApplication` |
| How does the blotter stay current without polling? | `BlotterProjector`, `RowBroadcaster`, `BlotterStreamTest`, `AppEndToEndTest.aPricingChangePushes...` |
| How does the as-of picker work? | `BlotterStore.rows` (latest row per assignment with brd <= date), `AppEndToEndTest.theAsOfControl...` |
| How is coalescing safe? | `RowPatch.merge`, `RowPatchTest.mergedPatchesAreEquivalentToTheSequence` |
| How do you prove the whole platform works? | `AppEndToEndTest` (real seed, real broker, real database) |
| (more added per phase) | |
