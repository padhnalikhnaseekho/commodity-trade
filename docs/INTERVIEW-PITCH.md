# Interview pitch: what this demo proves, and how to say it

Built so far: P0.1 to P0.3 (trade, logistics, pricing with structural sharing). Valuation gateway and blotter (P0.4, P0.5) are next.
Every number below is reproducible from the repo. Say "built" for what is built and "designed" for the rest.

## 1. The 30-second opener
"I built the core write path of a physical commodity trading platform end to end: trade capture with nested references, quota and
assignment management, revisioned logistics events that carry their own diff, and a pricing service that writes copy-on-write revisions.
The headline is write amplification. One logistics revision used to re-version a whole quota subtree, hundreds of rows for a change
that touched one assignment. With structural sharing it writes 35 rows instead of 301 for one changed assignment out of twenty, and a
property test proves readers cannot tell the difference."

## 2. The improvements, each with the claim and the proof

### A. Measure, don't quote
- **Improvement:** the build spec's benchmark figures (960 vs 48 rows) did not match its own schema. I counted real inserts instead and
  corrected the spec: 301 vs 35 (8.6x), 1.0x when everything changes.
- **Say:** "I caught that the illustrative numbers did not reconcile with the schema, so I measured. A wrong number said aloud is worse
  than a smaller true one. The 1.0x row is the one I point at first, because it is why the others are believable."
- **Proof:** `./gradlew :benchmark:run` (writer counts are cross-checked against the database's own row counts, and the run fails if they differ).

### B. The optimisation must be invisible to readers
- **Improvement:** a property-based equivalence test (random quota of 5-30 assignments, random history of 1-20 mutations) resolves under
  both strategies at every business date and compares with an in-memory model.
- **Say:** "If that test ever fails, the optimisation is wrong and nothing else about it matters. I also wrote a deliberately broken writer
  to prove the test can fail: it is caught within a few seeds."
- **Proof:** `EquivalencePropertyTest` (40 tries, real database), including `theEquivalenceCheckCatchesABrokenSharingWriter`.

### C. Immutability is a database guarantee, not a convention
- **Improvement:** revision tables reject UPDATE and DELETE with a trigger, on top of the ORM's immutable mapping.
- **Say:** "Structural sharing is only safe because rows never change. One stray UPDATE would silently corrupt every revision that
  points at the row, and nothing would fail loudly. So it is enforced where nobody can bypass it."
- **Proof:** `PricingRepositoryTest.databaseRejectsUpdateAndDeleteOnRevisionTables`.

### D. Correctness by content hash, not by trusting the event
- **Improvement:** sharing is decided by a bottom-up content hash, so a wrong diff in an event cannot produce a wrong revision.
- **Say:** "Each hashing detail prevents a silent bug: children sorted so order cannot look like change, fixed-scale decimals so 10.0 equals
  10.00, length-prefixed fields so 'ab'+'c' cannot collide with 'a'+'bc', null distinct from empty."
- **Proof:** `ContentHasherTest`.

### E. Events are atomic with state (transactional outbox)
- **Improvement:** the event is a row written in the same transaction as the change; a publisher ships it afterwards.
- **Say:** "A direct send after commit can be lost; a send before commit can announce something that rolls back. The outbox makes state
  change and event a single atomic fact. Delivery is at-least-once, so consumers dedup."
- **Proof:** `OutboxPublisherTest` (rollback leaves no event; a broker failure keeps rows and retries in order), on a real broker.

### F. Producer-classified events that carry the diff
- **Improvement:** logistics stamps material vs operational and publishes what changed (added, removed, modified, plus full members).
- **Say:** "The producer already knows what changed, so no consumer re-derives it, and no two consumers can disagree. Operational events
  use a separate high-volume topic, so revision consumers never pay for them."
- **Proof:** `AssignmentApiTest` (add cuts one revision with exactly that ref; modify one of twenty lists exactly one; an operational event cuts none).

### G. Concurrency handled and tested, not assumed
- **Improvement:** a per-quota advisory lock around read-decide-write in logistics and pricing.
- **Say:** "The revision chain, the reference sequence, the quantity cap and over-fixation are all read-decide-write over one quota. The lock
  serialises writers of one quota only; other quotas run in parallel. I chose it over optimistic retry because it is simpler at this write rate."
- **Proof:** ten simultaneous adds give a linear chain and distinct refs; two simultaneous 60-of-100 fixations give exactly one 201 and one 409.

### H. Delivery guarantees you can demonstrate
- **Improvement:** the dedup marker is written in the same transaction as the revision; failures retry three times then go to a DLQ.
- **Say:** "Marked-processed and applied can never disagree, so a crash cannot lose or double-apply an event. A poison message is parked
  and the partition keeps moving."
- **Proof:** `QagRevisionConsumerTest` (redelivery applied once; poison message on the DLQ while a valid event behind it is processed).

### I. Cross-service rules without coupling
- **Improvement:** "no fixation, no quantity edit" is enforced through a port (`FixationDirectory`) over HTTP; quotas and business dates
  the same way. The root build fails if one service module depends on another.
- **Say:** "Logistics owns quantity and pricing owns fixations; neither reads the other's tables. Lookups fail closed: a pricing outage blocks
  a quantity edit rather than silently allowing one that might break the rule."
- **Proof:** `AssignmentApiTest.quantityCannotBeEditedWhileAFixationExists`, `HttpQuotaDirectoryTest`, the boundary check in `build.gradle.kts`.

### J. Owning the race the design cannot fully close
- **Improvement:** the logistics-side fixation check is racy by nature, so pricing applies a conflicting quantity change and flags the assignment
  over-fixed (a derived value) instead of rejecting the event and falling behind.
- **Say:** "I would rather report an anomaly than stall a partition. Rejecting would leave pricing permanently behind the physical source of truth."

### K. Derived values are never stored
- **Improvement:** priced, unpriced and over-fixed are computed on read. Approval is its own insert-only record, so approving never cuts a revision.
- **Say:** "Storing a derived value means rewriting it on every change, which is exactly the amplification this design exists to remove."

### L. A switch, not a leap of faith
- **Improvement:** both writers stay in the codebase behind one interface and a config value.
- **Say:** "If sharing ever misbehaves, I turn it off without a release. The legacy writer is also the control in the benchmark and the property."

### M. Honest engineering habits (say these if asked how you work)
- Tests found real defects in my own work: an empty sum reporting `0` instead of `0.0000`, a wrong-way rename in the equivalence check, a missing
  compiler flag that a blanket "400 for any IllegalArgumentException" handler had hidden as a client error. I removed the blanket handler.
- Only what is specified is built; where the spec was silent I asked, and recorded every answer and assumption in `docs/SPEC-ADDENDUM.md`.
- Environment issues were fixed at the root (pinned Testcontainers for a newer Docker, UTC test JVM) and documented.

## 3. Known limits and what I would improve next (say these before being asked)
| Limit | Why it exists | Next step |
|---|---|---|
| Sharing saves 8.6x, not 20x | The member link table still has one row per assignment per revision, whatever changed | Bucketed tree or delta-plus-checkpoint links; costs a more complex read |
| Hashing loads and hashes unchanged assignments | Simplest provably-correct design | Use the event's diff to skip unchanged subtrees; trades read cost for trusting the diff |
| Polling outbox adds up to one poll interval of latency; per-key order only with one publisher | No CDC dependency in the demo | CDC where a licence exists; consumer-side version checks instead of arrival order |
| Retries block the partition briefly | Simple fixed back-off | Non-blocking retries on delay topics (5s, 30s, 5m) before the DLQ |
| Fixation check is racy | Two services, no shared transaction | Pricing's over-fixed flag is the safety net; a reservation protocol if the business needs a hard guarantee |
| Lookups have timeouts but no circuit breaker or cache | Demo scope | Resilience4j breaker plus a read-through cache with event invalidation |
| Dedup TTL bounds the marker table | Bounded storage | TTL must exceed broker retention plus the retry window |
| Approval and cancel/supersede interactions with fixation are not fully specified | Owner rule covers quantity edits only | Confirm with the business before enforcing more |

## 4. Likely questions, and the one-line answer
| Question | Answer | Where |
|---|---|---|
| Biggest scaling problem? | Write amplification: one logistics revision fanned out to hundreds of pricing rows. Structural sharing writes only what changed. | `StructuralSharingRevisionWriter` |
| How do you know it is still correct? | Property test over random histories, plus a broken writer that proves the test can fail. | `EquivalencePropertyTest` |
| How do you restate a number from a past date? | Every revision pins to a business date; resolution is "latest revision with brd <= D", deterministic and monotonic. | `RevisionStore.asOf` |
| What if an event is delivered twice? | Dedup on eventId in the same transaction as the write. | `QagRevisionHandler` |
| What if the broker is down? | Writes still commit; outbox rows wait and ship in order later. | `OutboxPublisherTest` |
| What does sharing cost you? | Reads become joins, purging needs whole-subtree archival, and a hash bug would corrupt history silently, hence the property test. | P0.3 notes |
| Why not just use the database's temporal features? | The revision model is what makes replay deterministic and valuation requests idempotent; swapping a working mechanism buys nothing and risks the one property the system depends on. | design notes |
| Where did you push back on the spec? | The benchmark numbers, and blanket exception handling. | `docs/SPEC-ADDENDUM.md` |

## 5. What not to say
- Do not claim the whole platform is built: three services are built, the rest are designed.
- Do not quote the spec's 960/48 or the captured "1,000 rows per revision" as measured. The measured figure is 301 vs 35 for the modelled tables,
  and the captured figure includes rows this demo does not model.
- Do not call the extrapolation a result. It is scaled from the measurement and labelled as such.
- Do not oversell tooling. Lead with the domain and the design; mention how it was built only if asked.

## 6. The five-minute demo order (rehearse this)
1. `./gradlew :benchmark:run`, the ratio table first; point at the 1.0x row unprompted.
2. Show `quota_revision_member` and `StructuralSharingRevisionWriter`; then the equivalence property and the broken-writer test.
3. Show as-of resolution (`RevisionWritersTest`: earlier date never sees a later change); the blotter picker follows in P0.5.
4. Show idempotency and replay (P0.4).
5. Close honestly: what is built, what is designed, and the limits table above.
