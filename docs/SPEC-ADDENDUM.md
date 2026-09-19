# Spec addendum

Decisions that the build spec left open. Each was confirmed by the project owner or is an explicit assumption
awaiting confirmation. Nothing here changes the spec's own rules.

## Confirmed by the owner
| Item | Decision |
|---|---|
| `assignment.status` | `ACTIVE`, `SUPERSEDED` (set on the parent after a split), `CANCELLED`, `DELETED` |
| Assignment approval | `APPROVED` / `UNAPPROVED`. Owned by **pricing** (not logistics). Approval must be complete **before desk close** (a Close of Books check, a later phase). It does NOT gate valuation: valuing an unapproved assignment is normal and produces a **provisional** valuation (see P0.4) |
| Approval storage (pricing) | A separate insert-only approval record, not a field on the assignment revision, so approving never cuts a pricing revision |
| Benchmark numbers | The benchmark counts real inserts; the spec's 960/48 are replaced by the measured figures (see P0.3 notes) |
| Approval and QAG | Approval changes do NOT cut a QAG revision |
| Change kind on add/modify | Caller supplies a required material `changeKind` on POST and PATCH; a non-material or missing kind is rejected |
| QAG membership | A revision's `members` lists ACTIVE assignments only; moving one to SUPERSEDED, CANCELLED or DELETED appears as `assignmentsRemoved` |
| Fixation and quantity | While an assignment has any price fixation, its quantity cannot be edited (logistics refuses with 409). Cancelling/superseding is not covered by this rule and is not blocked |
| Quota quantity cap | Only `ACTIVE` assignments count toward `sum(assignment.qty) <= quota.qty` |
| Delivery periodicity | All four are required in scope: `MONTHLY`, `WEEKLY`, `DAILY`, `CUSTOM` |

## Assumptions (mine, made so work could proceed; confirm or correct)
Quota derivation from delivery terms `{ periodicity, from, to }`. Common rule for every periodicity except CUSTOM:
quantity is split evenly across periods, scale 4, with the remainder going to the last quota (the spec's monthly rule).

| Periodicity | Assumed period boundaries |
|---|---|
| MONTHLY | Calendar months from `from` to `to`; first and last may be partial-month only if `from`/`to` are not month edges (each still one quota) |
| WEEKLY | Consecutive 7-day blocks starting at `from`; the last block ends at `to` and may be shorter |
| DAILY | One quota per calendar day from `from` to `to` inclusive |
| CUSTOM | The caller supplies explicit `[{from, to, qty}]`; quantities must sum to the trade total, periods must not overlap |

Open, to settle in P0.3 (pricing): how approval is stored. Revisions are insert-only, so it is either an attribute
carried by the assignment revision (an approval then cuts a pricing revision) or a separate insert-only approval
record. Not modelled yet; ask before building it.

## P0.2 assumptions (mine; confirm or correct)
- Only ACTIVE assignments can be modified. SUPERSEDED, CANCELLED and DELETED are terminal (no reactivation).
- A PATCH that changes nothing cuts no revision and returns the latest `qagrId` unchanged.
- "Modified" in a diff means the quantity changed; quantity and status are the only assignment fields modelled so far.
- Assignment refs are never reused: a new assignment takes max(seq)+1 over ALL assignments in the quota, whatever their status.
- Trade numbers come from a database sequence (`trade.trade_number_seq`); gaps are possible and harmless.
- Monthly quotas follow calendar months clipped to `[from, to]`; e.g. from 15 Jan gives a first quota of 15-31 Jan.
- Logistics learns a quota's quantity and desk through a lookup port (`QuotaDirectory`), never by reading trade tables.

## P0.3 assumptions (mine; confirm or correct)
- Race fallback: if a revision event arrives that reduces the quantity of an assignment that has a fixation (a race the
  logistics check cannot fully close), pricing applies it and reports the assignment as over-fixed (derived, never stored)
  instead of rejecting the event.
- Component fields by kind: FIXED needs `fixedPrice`; AVERAGE needs `indexName`, `periodFrom`, `periodTo`; FORMULA needs `formula`.
  Formula syntax is not validated (pricing runs only initial checks; the engine owns semantics).
- Writing a revision for a BRD earlier than the desk's current BRD is refused with 409 (closed date).
- Latest-revision resolution orders by BRD, then creation time, then id (id only breaks exact timestamp ties).

## P0.3 measured figures (replace the spec's illustrative benchmark numbers)
Scenario: 1 quota, 20 assignments, each with 10 parameters and 3 price components. Rows counted by `./gradlew :benchmark:run`
and cross-checked against the database's own row counts.

| Assignments changed | Copy-all rows | Sharing rows | Ratio |
|---|---|---|---|
| 1 | 301 | 35 | 8.6x |
| 2 | 301 | 49 | 6.1x |
| 5 | 301 | 91 | 3.3x |
| 20 | 301 | 301 | 1.0x |

Arithmetic: copy-all = 1 quota revision + 20 assignment revisions + 200 parameters + 60 components + 20 member links = 301.
Sharing with k changed = 21 (quota revision + 20 links) + 14 per changed assignment (1 + 10 + 3). The 20 member links are why the
saving is bounded: they are written on every revision whatever changed.

The spec's exit criterion "<= 60 rows under sharing, >= 900 under copy-all" is replaced by "35 under sharing, 301 under copy-all"
(tests assert these exact numbers). The captured "~1,000 rows per revision" also counts rows this demo has no tables for
(a blotter row per assignment, valuation results).

## P0.4 confirmed by the owner
| Item | Decision |
|---|---|
| FunctionalLine (engine routing) | Default by a CUTOVER DATE (RM trades created before it use RM_LEGACY, later ones RM_MODERN; other business lines always their single modern line), with an optional explicit OVERRIDE chosen at trade creation (`functionalLine` on POST /api/trades, validated against the business line) |

## P0.4 assumptions (mine; confirm or correct)
- **DEVIATION FROM THE TARGET DESIGN (accepted simplification): who assembles the inputs.** The spec's `POST /api/valuations` takes only
  `{subjectRef, subjectLevel, brd, lane}`, so the gateway assembles the inputs through a port to pricing (`GET /api/valuation-inputs`). The target
  design has the CALLER assemble a complete request and the gateway perform NO business lookups, so it does not depend on pricing being up. The demo
  keeps the spec's API and accepts this deviation; it is deliberately left as is and is named as a simplification wherever the design is presented.
  What holds either way: the engine adapter receives complete inputs, looks nothing up, and its module depends on no other service.
- **Provisional valuation (owner correction, replaces an earlier wrong assumption):** approval does NOT gate valuation; it must be complete before desk close.
  A valuation is PROVISIONAL when any contributing assignment is not approved as of the BRD (for a quota-level request: any of the quota's assignments).
  Approval is not part of the request key (the valuation maths are identical), so approving later returns the SAME cached answer, now reported as final.
  A submission's response reports the CURRENT state (`provisional`); the stored row keeps the state at request time and the API names it
  `provisionalAtRequest`, so a read never claims a stale value is current. The Close of Books check "all assignments approved" is a later phase (P2).
  The comments in the committed pricing V2 and logistics V2 migrations still say approval "gates valuation"; they are superseded by this entry
  (applied migrations are not edited, because Flyway checksums include comments).
- **marketDataAsOf** in the request key is the BRD (no market data exists in P0).
- **Order of checks:** cache, then in-flight, then closed-BRD. A cache hit is served even for a closed BRD (that is how replay works);
  the restatement flag allows NEW work for a closed BRD.
- **Closed BRD** means a BRD earlier than the desk's current BRD (same rule as pricing writes).
- **Identical request in flight** collapses into the running one (same requestId returned) instead of starting a second.
- **Attempts:** at most 3 sends per request. A timeout (lane timeout, watchdog) or an engine error reply sends it back for another attempt, then FAILED.
  A late or duplicate reply for a request that is no longer SENT is ignored (a retry may then cost one extra engine call; the answer is identical by construction).
- **Lane defaults** are the spec's illustrative figures: INTERACTIVE 4 in flight / 5s, INVOICE 4 / 15s, BULK 8 / 60s, CLOSE 8 / 60s, all configurable.
- **Valuation level** is chosen by the caller and not enforced by the gateway. Replay follows the captured default: RM at quota level, every other business
  line at assignment level.
- **Replay** sends with the restatement flag on the BULK lane, reads at the revision's own BRD, is read-only, and refuses (409) to replay a revision that a
  newer revision has superseded on the same BRD, because the gateway resolves inputs by date and would otherwise value different content.
- **Engine reply** is a direct Kafka send from the stub adapter, not through an outbox: the adapter has no database, so there is nothing for an outbox row to be
  atomic with. A lost reply costs a retry by the watchdog, not a wrong answer.
- **Result shape** is `{"value": "<decimal string>", "engine": "LEGACY|MODERN"}`; the spec did not define it.

## P0.5 confirmed by the owner
| Item | Decision |
|---|---|
| Blotter columns | The spec columns, where `status` is the assignment lifecycle, plus the approval status and a **provisional** badge on valuations |
| RM valuation in the blotter | An RM quota is valued at QUOTA level; the quota number is shown on **every assignment row** of that quota, marked level `QUOTA` (no pro-rata split: none was specified) |
| Seed mix | 50 trades across all four business lines and **three desks**: RM (copper cathode, nickel), concentrates (copper, zinc), bulk (coal, iron ore), energy (crude oil); purchases and sales; monthly, weekly and custom delivery; 1-3 assignments per quota; parameters; partial fixations; mixed approval; some RM trades pinned to the legacy engine by the override |
| Blotter backend | Its own service module (`services/blotter`), not part of pricing |
| One JVM and profiles | The app runs every service in one JVM by default, **and** each service sits behind its own Spring profile so the same artifact runs as any subset (see phase notes P0.5) |

## P0.5 deviations from the spec, and assumptions (mine; confirm or correct)
- **DEVIATION (bug found by the end-to-end test): the revision in force is resolved by the insertion sequence (`id`), not by `created_at`.** The spec tie-breaks by
  `created_at DESC`. Wall-clock time is not monotonic (NTP and hypervisor time sync step it backwards, and servers disagree), so a revision written later can carry an
  earlier timestamp and the as-of read returned a STALE revision, which looked like a lost update. Ordering is now `brd DESC, id DESC` for pricing revisions, pricing
  approvals and QAG revisions (migrations pricing V4, logistics V4). `created_at` stays as an audit column. Regression tests insert a later revision stamped an hour earlier.
- **DEVIATION: valuation results are published by the gateway** (`valuation.published.v1`, key subjectRef), not by pricing (`pricing.valuation.published.v1`), because
  the gateway holds the results and pricing does not consume replies in this build.
- **NEW topic: `pricing.quota.published.v1`** (key quotaRef): pricing publishes a full snapshot of a quota (priced, unpriced, over-fixed, approval per assignment) after every
  pricing revision and every approval, through its outbox. The blotter is built from this and the valuation results.
- **Blotter `status`** is ACTIVE while pricing lists the assignment in its quota and REMOVED once a later snapshot drops it; the reason (cancelled, superseded, deleted) is not
  published (revision events carry membership only), so removed assignments simply leave the view. The as-of view still shows them on earlier dates.
- **Blotter valuation columns:** valuation, valuationAsOf, plus level, engine and `provisional`. Provisional is derived at read time from the CURRENT approval state (assignment level:
  this assignment unapproved; quota level: any assignment of the quota unapproved), never stored.
- **Blotter as-of:** `GET /api/blotter?deskId=&brd=`; without `brd` it is the live view. A row is resolved per assignment as the latest row with `brd <=` the date.
- **Live push:** Server-Sent Events, JSON Patch operations per row, coalesced per row every 250 ms, a bounded replay buffer of 1,000 events with `Last-Event-ID`, and a `reset`
  event when a client has been away longer than the buffer holds.
- **Run command:** `./gradlew :app:bootRun` (also `./gradlew bootRun` from the root). The stub engine adapter is switched by `commodity.engine.enabled`.
- **Profiles:** each service (trade, logistics, pricing, gateway, blotter) and the stand-ins (`stubs`) has a profile. The business-day clock and the functional-line reference data
  are still in-process stubs (Java ports), so any process running a service that needs them also runs the `stubs` profile.
- **The seed** goes through the public API (it depends on no service module), is deterministic for a given seed, and is not idempotent (running it twice creates two books).
- **Test environment:** container start-up is retried, and Testcontainers' cleanup helper (Ryuk) is disabled, because host-port collisions on this machine's narrow ephemeral port
  range made container starts fail intermittently. If a test JVM is killed hard, `docker ps` shows any leftovers.
