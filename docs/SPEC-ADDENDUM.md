# Spec addendum

Decisions that the build spec left open. Each was confirmed by the project owner or is an explicit assumption
awaiting confirmation. Nothing here changes the spec's own rules.

## Confirmed by the owner
| Item | Decision |
|---|---|
| `assignment.status` | `ACTIVE`, `SUPERSEDED` (set on the parent after a split), `CANCELLED`, `DELETED` |
| Assignment approval | `APPROVED` / `UNAPPROVED`. Owned by **pricing** (not logistics): an assignment must be approved to be eligible for valuation and P&L |
| Approval and QAG | Approval changes do NOT cut a QAG revision |
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
