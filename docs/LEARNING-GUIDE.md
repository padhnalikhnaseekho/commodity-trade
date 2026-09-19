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
| (more added per phase) | |
