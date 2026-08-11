# Optimistic locking / concurrent-edit protection (LNCH-16)

Every generated concept table carries a platform-managed `row_version` (bigint, default 0). A
write through `ConceptGateway` can ask for a compare-and-swap against it; a losing writer gets a
409-shaped `ConceptGatewayOptimisticLockException` carrying the row's current state instead of
silently clobbering someone else's change.

## The contract

`ConceptRecord` and `ConceptWriteRequest` both carry a `rowVersion`/`expectedRowVersion` component,
added as trailing fields behind a preserved 4-arg constructor — every pre-existing caller keeps
compiling and keeps today's behavior unchanged:

- **`expectedRowVersion == null`, `force == false`** (the default, and every call site that
  predates this feature) — an unconditional write. The store still tracks/increments
  `row_version` underneath so a *later* caller can start doing real CAS against it, but this write
  itself never fails on a version mismatch. This is create's normal shape, and it is also today's
  update behavior for every existing caller (nothing opted in yet).
- **`expectedRowVersion` set, `force == false`** — a real compare-and-swap. The write only lands if
  the row's stored `row_version` still equals what the caller read; otherwise
  `ConceptGateway#save` throws `ConceptGatewayOptimisticLockException` with `currentRecord()`
  populated (empty if the row was deleted out from under the caller).
- **`force == true`** — bypass the check regardless of what `expectedRowVersion` was set to, but
  still increment `row_version` (an explicit "last write wins" escape hatch — for a scheduled
  flow's `updateConcept` action, say — not a way to skip version tracking).

`ConceptStore#save` is where the actual compare-and-increment happens (`ConceptGateway` just
translates the write request into a `ConceptRecord` and translates the store-level
`ConceptStoreOptimisticLockException` into the gateway-level one). Both adapter families implement
the same contract:

- **`InMemoryConceptStore`** — every method is already `synchronized`, so the read-compare-write is
  atomic for free; a CAS request that doesn't match the map's current entry throws.
- **`JdbcBusinessConceptStore`** — a CAS request becomes an explicit
  `UPDATE ... SET ..., row_version = row_version + 1 WHERE id = ? AND tenant_id = ? AND
  row_version = ?`, checked against rows-affected (0 rows means someone else won the race, or the
  row is gone — either way, re-fetch and attach whatever's actually there). An unconditional write
  keeps using the existing upsert (`MERGE`/`ON CONFLICT`), just with `row_version` folded in as one
  more column.

## Two version columns, on purpose

Every generated table already has a `version` column backing the generated-entity
(`{Concept}ServiceBase`) JPA path's pre-existing `checkOptimisticVersion` compare — non-atomic
(read-then-compare-then-save, not a single conditional UPDATE) but functioning, and unrelated to
this feature's rollout. `row_version` is a **separate, new** column, deliberately not unified with
`version`:

- Zero coupling/regression risk to the existing mechanism — nothing about `checkOptimisticVersion`
  changes.
- `enforceWithConceptGateway` (the generated-service path's call into `ConceptGateway.save`) still
  constructs its `ConceptWriteRequest` via the old 4-arg shape (`expectedRowVersion == null`), so
  the JPA-generated CRUD path is entirely unaffected — it keeps its own existing (if non-atomic)
  version check, and now *also* gets `row_version` tracked underneath for free, ready for a future
  slice to wire real CAS through it.

## Scope of this slice

This lands the full kernel-level mechanism — schema column, `ConceptRecord`/`ConceptWriteRequest`
contract, both adapter CAS implementations, `ConceptGateway` wiring, the conflict exception with
the current record attached — proven with interleaved-update tests on both adapter families
(mirroring `RowLevelAuthorizationAttackTest`'s style: the loser gets rejected with the winner's
state). **Not** included in this slice: wiring a `rowVersion` field through the generic
`/api/concepts/{conceptName}` REST controller or generated business-UI forms (the "reloaded —
reapply your change" UX the doc's DoD describes). Nothing in the platform's HTTP surface asks for a
CAS yet, so nothing regresses; a caller that wants one today (a flow's `updateConcept` action, a
`PanelRuntime` write, `AggregateRuntime`, `SeedDataService`, ...) calls `ConceptGateway.save`
directly with a populated `ConceptWriteRequest` and gets the real behavior described above.

## Verification

- `DefaultConceptGatewayTest` — `interleavedUpdatesRejectLoserWithWinnersCurrentState` and
  `forceUpdateBypassesVersionCheckButStillIncrementsIt`, against `InMemoryConceptStore`.
- `JdbcBusinessConceptStoreOptimisticLockTest` — the same interleaved-conflict shape against a real
  H2 `UPDATE ... WHERE row_version = ?`, plus a deleted-row-conflict case and a
  no-`row_version`-column backward-compatibility case (pre-existing tables generated before this
  feature keep the old unconditional upsert, gated on `TableColumns#has("row_version")`).
- `SchemaRealizationEmitterReservedColumnTest` and the rest of the generator's schema-realization
  suite — `row_version` added to the reserved-column set (a model field named `row_version`/
  `version` fails fast with a rename suggestion, same as the pre-existing `version`/`tenant_id`
  check) and the additive-columns path (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS row_version ...
  DEFAULT 0`, so an in-place upgrade backfills existing rows to version 0 without a migration
  step).
- `run-runtimehost-gate.ps1` — regenerated `simple-contact-intake`, restaged local
  kernel/generator jars, ran the assembled app's full Gradle `test` task (includes the new JDBC
  test above) — green.
