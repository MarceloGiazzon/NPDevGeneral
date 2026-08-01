# ADR-0008 Sanctioned Destruction via Conversion Hooks ("rule-6")

## Status

**APPROVED — 2026-07-25.** Drafted per `docs/archive/programme-history/SER_FINAL_CLOSURE_PLAN.md` G2. Conversion hooks
(schema-engine rebuild, Phase 7) were built and shipped in the prior session on a verbal go-ahead from
the project owner (an interactive "Approve rule-6, proceed to Phase 7" response); this ADR captures that
approval as a durable decision record and the owner re-confirmed it explicitly against this document's
decision block on 2026-07-25.

> **Note on filename:** the execution plan that requested this document (`archive/programme-history/SER_FINAL_CLOSURE_PLAN.md`
> §2.2) suggested `ADR-0005-sanctioned-destruction-conversion-hooks.md`. That number is already taken
> (`docs/adr/ADR-0005-auto-panel-patterns.md`, existing and unrelated) — this document uses the next
> free number, **ADR-0008**, instead.

## Context

Schema-engine rebuild Phase 6 (Impact Report) gives an operator a read-only preview of what an upgrade
would do to a live database, and the existing destructive-change gate requires an itemized
acknowledgment token (`DestructiveAckToken`, computed from the residual diff) before any drop/narrow
executes. That token answers "do you accept this data loss?" — it does not let an operator *avoid* the
loss by supplying a real conversion.

Phase 7 adds **conversion hooks**: an operator authors SQL (`hook.json` + `convert.sql`) that claims a
specific diff item and converts the data itself — e.g. splitting one column's meaning into another,
rather than the platform just dropping it. The question this ADR answers: once a hook has *actually
performed* (and the engine has *verified*) the conversion it claims, should the boot still demand an
acknowledgment token for that item?

## The rule, stated plainly

**A destructive schema item that an operator's conversion hook resolves requires NO acknowledgment
token — authoring the hook IS the acknowledgment.**

## What it authorizes

A hook's `convert.sql` may drop columns/tables and delete data as part of performing its claimed
conversion. Provided the post-hook re-diff (rule 5) confirms the claimed item is actually gone, the boot
proceeds with **no token required for that item** — the same way a `SAFE_ADDITIVE` change has never
needed one, because nothing is left unresolved for a human to authorize.

## What it does NOT authorize

Any item **no** hook claims is completely unaffected: it remains exactly as token-gated as it was before
Phase 7 existed. A hook narrows what still needs a token; it never widens what is allowed to happen
silently. The deprecated blanket `allowDestructiveRecreate` flag is a separate, pre-existing mechanism
this ADR does not touch.

## The safety net that remains

1. **Generation-time schema validation** — every `hook.json` is validated against
   `conversion-hook.schema.json` when the app is generated; a malformed hook fails the *build*, never
   the boot.
2. **Rule 4 (verify, now inside the hook's own transaction — `fce3eb1`)** — a hook's `convert.sql` and
   its optional `verifySql` run in ONE transaction; a verify mismatch (or a `verifySql` that itself
   errors) rolls the *entire hook* back. On Postgres this undoes both data and schema changes
   (transactional DDL); on H2 it undoes data changes but not DDL already executed (H2 has no
   transactional DDL — see the residual risk below and `ConversionHookRunner`'s javadoc).
3. **Rule 5 (re-diff, never trust a claim)** — after every selected hook runs, the engine re-computes the
   live diff and checks that each hook's claimed item is actually gone. A claim that doesn't hold true
   refuses the boot with an explicit message naming the hook and the item — a claim is a promise the
   engine verifies, never trusts.
4. **Rule 7 (full audit trail)** — every hook step (start, applied, verify result, failure, the final
   re-diff verdict) writes an `npdev_schema_history` row.
5. **Read-only preview before any of this runs** — `-ImpactOnly` (Surface 2) and the ControlPanel
   (Surface 3) show which items a hook *would* claim, and that no token would be needed, before an
   operator ever deploys the jar that would actually run it.

## The residual risks, stated honestly

- **The H2 DDL caveat (tracked as G6 in `archive/programme-history/SER_FINAL_CLOSURE_PLAN.md`).** H2 has no transactional DDL: a
  verify failure on a hook that mixes `ALTER`/`DROP TABLE` with data movement will roll back the data
  but not the already-executed DDL. Postgres does not have this gap. `archive/programme-history/SER_FINAL_CLOSURE_PLAN.md` §2.6
  adds an operator-facing warning when this combination is detected; it does not (cannot) make H2 DDL
  transactional.
- **A hook is only as safe as the SQL an operator wrote.** The engine verifies that a hook's *claim* was
  honored (the diff item is gone); it cannot verify that the conversion was *correct* in a business
  sense (e.g. that `price_dollars = price_cents / 100` used the right rounding). That judgment stays
  with whoever authored and reviewed the hook.
- **Hooks are individually atomic, not collectively atomic** (tracked as G5). If hook 2 in a boot fails,
  hook 1 stays committed. A later boot re-runs only what the diff still says is unresolved — hooks
  should be written idempotently.
- **v1 is SQL-only.** A Java `DataMigrationHook` (for conversions too complex for a script) is
  deliberately deferred (not part of this decision) -- tracked as `ledger/items/REG-84.yml`.

## Decision block

```
Decision: [x] APPROVED   [ ] APPROVED WITH CONDITIONS   [ ] REJECTED
Owner: Marcelo Giazzon
Date:  2026-07-25
Conditions (if any): none
```

Approved as drafted. The rest of `archive/programme-history/SER_FINAL_CLOSURE_PLAN.md`'s Group A (conversion-hook hardening: the
jar-loading proof G1, the backfill end-to-end proof G3, the SQL-splitter hardening G4, the operator-
facing docs/warnings G5/G6, engine-detection unification G7, and the live P7.7 packaged-jar proof) may
proceed on this basis.
