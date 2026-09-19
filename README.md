# Commodity Trade Demo

The core write path of a commodity trading platform, built end to end: trade capture with nested references, quotas and assignments, QAG revisions that carry their
own diff, copy-on-write pricing revisions (structural sharing), an idempotent valuation gateway, and a live blotter. It is a portfolio demo, not production.

## What is built, and what is only designed
**Built (P0):** trade, logistics, pricing, the valuation gateway, the blotter (backend and UI), the seed generator, and the stand-ins they read from.
**Designed, not built:** reference data (SRD) and Business Day Control as real services, the deal hierarchy, quality, the mirroring projector, invoicing, cost and incomes,
close of books, hedging, scheduling operations. They are specified in the design documents; this repo builds the spine and stubs what it depends on.

## Run it
```bash
docker compose up -d                    # Postgres 16 and Redpanda (Kafka API)
./gradlew :app:bootRun                  # every service in one JVM on :8080
./gradlew :demo-seed:run                # 50 trades with quotas, assignments, parameters, price components, approvals, valuations
cd ui && npm install && npm start       # the blotter at http://localhost:4200
```
Other commands:
```bash
./gradlew :benchmark:run                # writes the same pricing revision both ways and prints rows written (needs Docker)
./gradlew build                         # all tests, including the end-to-end test (needs Docker)
```

## One JVM, but real services
Everything runs in one process **by choice** so the demo starts with one command. The boundaries are real:
- The build **fails** if one service module depends on another; services share only `contracts` (events, DTOs, ports) and `platform` (outbox, error shape).
- Services call each other over **HTTP and Kafka even inside this one process**, and each owns its own Postgres schema and migrations (no cross-schema foreign keys).
- Each service sits behind its own **Spring profile**, so the same artifact runs as any subset:
  ```bash
  ./gradlew :app:bootRun --args='--spring.profiles.active=pricing,stubs --server.port=8082 --commodity.trade.base-url=http://otherhost:8080'
  ```
  A test starts each subset and asserts that a service not in the profile list loads no beans, no endpoints and no schema (`ProfileIsolationTest`).

What a single JVM hides (say these first): no real network failure between services, deployment independence is shown by structure and a test rather than by running separate
processes, the gateway's in-memory lane counter is only correct for one instance, and one Postgres server hosts every schema. The business-day clock and reference data are
in-process stubs behind ports, so any process running a service that needs them also runs the `stubs` profile.

## Substitutions from the target design
| Target | Demo | Why it is a fair substitute |
|---|---|---|
| Oracle Exadata | PostgreSQL 16 | Same partitioning model and SQL for everything the demo exercises; Exadata-specific features are absent and nothing depends on them |
| Kafka on MSK | Redpanda, single node | Kafka wire compatible, one container |
| Avro and a schema registry | JSON | Adds a container for no demonstrative gain |
| Distributed cache | Caffeine, in process | One node, so distribution buys nothing; the interface is the same |
| Valuation engines (legacy and modern) | A deterministic stub engine adapter | Valuation maths is not the point; it returns a computed number after a configurable delay |
| Reference data, business day control | In-memory stubs behind ports | Replaced by real services in P1 without touching callers |
| OIDC and a policy engine | No authentication | Security is designed, not demonstrated |
| CDC outbox | Polling outbox | CDC adds a container; the polling publisher is central to the design and trivial to run |
| Kubernetes, service mesh, observability stack, multi-AZ | Absent | Designed, and deliberately not built for a demo |

## Where to read next
- `docs/LEARNING-GUIDE.md`: reading order, the five-minute demo script, and an interview-question-to-code table.
- `docs/INTERVIEW-PITCH.md`: what the demo proves, each improvement with its evidence, and the limits to name first.
- `docs/phase-notes/`: one file per phase: what was built, the decisions and why, likely follow-up questions.
- `docs/SPEC-ADDENDUM.md`: every decision the spec left open, and every deviation from it, with the reason.
- `docs/GLOSSARY.md`: the domain vocabulary, each term pointing at the class that models it.

## Environment notes
- Java 21, Docker, Node 20 or newer (for the UI). Gradle is bootstrapped by the wrapper.
- Tests use Testcontainers, so they need a running Docker daemon. On a machine with a narrow ephemeral port range (WSL2 mirrored networking) container starts can collide on host
  ports; starts are retried and Testcontainers' cleanup helper is disabled (see `docs/SPEC-ADDENDUM.md`). If a run is killed hard, `docker ps` shows any leftover containers.
- The JVM runs in UTC. The JDBC driver sends the JVM timezone to Postgres, and a legacy zone alias can fail the connection.
