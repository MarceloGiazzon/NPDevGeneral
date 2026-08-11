# Schema Impact Reports

The **impact report** is NPDev's answer to "what will this upgrade do to my data?" — the equivalent of
GeneXus's *Impact Analysis Report (IAR)*. Before (or as) a schema change is applied, NPDev computes a
read-only, per-item view of the blast radius from the ONE canonical desired-vs-current `SchemaDiff` (the
schema-engine rebuild, see [`DATABASES_AND_MIGRATIONS.md`](../../DATABASES_AND_MIGRATIONS.md) §16), probes the
live database for how many rows each change touches, and renders it two ways.

Everything here is **strictly read-only**: building a report issues zero DDL and zero writes, every probe
is a single `SELECT COUNT(*)` with a short timeout, and any failure degrades that one item to "unknown"
rather than affecting the boot.

## Verdict

Worst-item-wins, over every item in the diff:

| Verdict | Meaning |
|---|---|
| `NO_CHANGES` | The model matches the live schema; nothing to do. |
| `SAFE` | Only additive / relaxing / rename / safe-widening changes — no existing row is at risk. |
| `NEEDS_ATTENTION` | A new required field needs a backfill (or an operator hook) before it can be enforced. |
| `DESTRUCTIVE` | At least one column drop, table drop, or non-widening type change — data will be lost without an explicit acknowledgment. |

## What each row count means (the probes)

| Change | `rowsAffected` is… |
|---|---|
| Drop column | rows where the column **is not null** (how much data dies) |
| Drop table | the table's **total row count** |
| Narrow a `VARCHAR(n)` | rows where `LENGTH(col) > newSize` (would be truncated) |
| Narrow any other type (numeric precision, type-family change) | worst case: **every non-null value** — flagged `MANUAL_REVIEW` in the note, because convertibility needs a human |
| New required field / tighten to `NOT NULL` | rows that would violate the new `NOT NULL` (all rows for a brand-new column; the `NULL` rows for an existing column) |

`rowsAffected = -1` means the probe could not run (missing table, timeout, error); the `probeNote`
explains why. A safe item (additive, relax, widen, rename, create) affects no existing rows and is not
probed (`0`).

### A drift item that isn't from the schema diff: stale built-in-pack copy (REG-39)

One item can appear in the report that is **not** derived from `SchemaDiff` at all: `itemKey` prefix
`STALE_IDENTITY_PACK:identity_users:tokenVersion`, `safetyClass NEEDS_HOOK` (so it counts toward
`NEEDS_ATTENTION` the same as any other item). It fires when this app's compiled model declares an
`identity::User` concept (i.e. it uses the built-in identity pack, by whatever mechanism) but that
concept is missing the `tokenVersion` field the platform's identity pack has carried since LNCH-4 — the
same drift `StartupValidator` fails fast on at boot (see
[`CONFIGURATION.md`](../../CONFIGURATION.md#identity-pack-freshness-checked-at-boot)), surfaced here too so an
operator can see it in a pre-deploy `-ImpactOnly` run or the ControlPanel view without needing to boot
the app first. `rowsAffected` for this item is the `identity_users` row count — how many accounts are
affected. See `com.finalexec.db.IdentityPackDriftItem`.

## Surface 1 — at boot time (implemented)

On **every upgrade boot** (whenever the schema fingerprint changed), the executor writes the machine
report and prints the human table:

- **JSON** → `runtime-data/impact-reports/<yyyyMMdd-HHmmss-SSS>-<from>-<to>.json`, conforming to
  [`NPDevContract/schemas/impact-report.schema.json`](../../../NPDevContract/schemas/impact-report.schema.json).
  The last **10** reports are retained (older ones are pruned), mirroring the pre-drop snapshot writer.
- **Text** → the aligned table is printed to stdout, one line per item, `DESTRUCTIVE` items prefixed
  `!!`, with a `N safe / N attention / N destructive` footer.

When an upgrade is **destructive and unacknowledged**, the boot refuses, and the refusal message now
**leads with the impact table** so an operator sees the blast radius immediately — followed, as before,
by the itemized destructive report, the **expected acknowledgment token**, and the pointer to
[`SCHEMA_EVOLUTION.md#acknowledging-destructive-changes`](../../SCHEMA_EVOLUTION.md#acknowledging-destructive-changes).
The token is unchanged and byte-identical to what it always was (it is computed from the residual diff
at the decision point); only the message got a friendlier preamble.

> Note: the boot-time JSON file records the verdict and per-item impact but not the acknowledgment token
> (the token is derived later, post in-place-repair, and is shown in the refusal message and the
> ControlPanel). The report reflects the full pending change at the decision point — including changes
> the in-place passes then auto-resolve — which is deliberately the "everything this upgrade will do"
> view.

## The JSON shape

```json
{
  "generatedAt": "2026-07-24T00:00:00Z",
  "fingerprintFrom": "sha256:…",
  "fingerprintTo": "sha256:…",
  "verdict": "DESTRUCTIVE",
  "acknowledgmentToken": "… (only when verdict is DESTRUCTIVE and a token is supplied to the renderer)",
  "items": [
    {
      "itemKey": "DROP_COLUMN:widgets:legacy_flag:BOOLEAN",
      "table": "widgets", "column": "legacy_flag",
      "safetyClass": "DESTRUCTIVE_DROP_COLUMN",
      "before": "BOOLEAN", "after": null,
      "rowsAffected": 2, "probeNote": "",
      "resolution": "UNRESOLVED",
      "proposedConversionSql": null
    }
  ]
}
```

`proposedConversionSql` is reserved for a future phase (proposed conversion SQL) and is currently always
`null`.

## Surface 2 — pre-deploy CLI (implemented)

A `REPORT_ONLY` lifecycle mode computes the impact report against the app's **live, already-reachable**
database, prints it (`ImpactReportText`), and exits the JVM **before any DDL, claim, or history write** —
even before the stored fingerprint is read. It is enabled by a JVM system property, so no Spring wiring
is needed:

```
-Dnpdev.schema.lifecycle.mode=REPORT_ONLY
```

Exit codes mirror the verdict:

| Exit code | Verdict |
|---|---|
| `0` | `NO_CHANGES` or `SAFE` |
| `2` | `NEEDS_ATTENTION` |
| `3` | `DESTRUCTIVE` |

`Build-NpdevApp.ps1 -ImpactOnly` drives this end to end: it generates + builds the app, then runs the
freshly-built jar **once, in the foreground**, with `REPORT_ONLY` set, and propagates the app's exit code
as the script's own exit code. It also emits a standalone `_ops\Impact-Only.ps1` in the app's ops toolbox
(sibling of `Build-App.ps1` / `Start-App.ps1`) so the same check can be re-run later, independent of a
regeneration, against whatever jar is currently built — e.g. right before promoting it to the target
environment.

```powershell
# One-shot: generate, build, and check impact against the target database
& scripts\appgen\Build-NpdevApp.ps1 -AppFolder <appFolder> -ImpactOnly

# Or, re-run later against an already-built jar
& <OutRoot>\_ops\Impact-Only.ps1
```

`-PlanOnly` and `-ImpactOnly` answer different questions and are not interchangeable:

| Flag | Compares | Needs a database? |
|---|---|---|
| `-PlanOnly` | model vs. the **previous model** | No — offline estimate |
| `-ImpactOnly` | model vs. the **live database** | Yes — the target must already be reachable |

`-ImpactOnly` does **not** start the database environment (H2Server) or leave a long-running process
behind — the JVM exits as soon as the report is printed, before the web server would ever bind a port.

## Surface 3 — ControlPanel (implemented)

`SchemaImpactController`, SUPERUSER-gated exactly like `SchemaAcknowledgmentController`, exposes the same
report on a running app:

- `GET /api/admin/schema-migration/impact` → the JSON report (`ImpactReportJson`), header
  `X-Super-User-Key: <key>` required.
- `GET /api/admin/schema-migration/impact/view` → a minimal self-contained HTML page that prompts for the
  Super User key client-side and renders the table. Diagnostic surface, not a product page.

Both surfaces reuse the same read-only entry point, `com.finalexec.db.SchemaImpactFacade.forLiveDatabase`
(SER-P6.0), so the CLI and the ControlPanel can never disagree about what an upgrade would do.

Specified in [`archive/programme-history/SCHEMA_ENGINE_REBUILD_PLAN.md`](SCHEMA_ENGINE_REBUILD_PLAN.md) Phase 6 (P6.4, P6.5).

## Conversion hooks — sanctioned destruction (implemented, Phase 7)

A **conversion hook** is operator-authored SQL that converts data as part of a schema change, instead of
letting the change destroy it. Owner-approved policy (rule-6, 2026-07-24): **a destructive item a hook
resolves needs no acknowledgment token** — authoring the hook *is* the acknowledgment. An unclaimed
destructive item remains exactly as token-gated as it always was; hooks only ever narrow what still needs
a token, never widen what's allowed to happen silently.

### The operator loop

1. **See what an upgrade would do.** `-ImpactOnly` (Surface 2) or the ControlPanel (Surface 3) against
   the target database, *before* deploying the new jar. Every item that needs attention prints its
   `itemKey` — e.g. `DROP_COLUMN:invoices:legacy_total:NUMERIC(10,2)`.
2. **Write a hook folder** in the app definition (layer 2, e.g.
   `D:\WorkSpace\NPDev\AppGen\apps\<app>\definition\migrations\<ordinal>-<slug>\`):
   - `hook.json` — `id` (also the destination folder name and the natural-sort execution order — prefix
     it with the same ordinal as the folder, e.g. `"001-split-legacy-total"`), `claims` (the `itemKey`(s)
     this hook resolves), optional `description`/`verifySql`/`verifyExpect`.
   - `convert.sql` — the SQL that actually performs the conversion (it does the real work, including any
     destructive DDL — a hook is not a pre-step the platform later re-executes, it IS the execution).
   - Optional `convert.h2.sql` / `convert.postgres.sql` for engine-specific syntax; `convert.sql` is the
     fallback for whichever engine has no override.
3. **Regenerate.** `ConversionHookEmitter` validates every `hook.json` against
   [`conversion-hook.schema.json`](../../../NPDevContract/schemas/conversion-hook.schema.json) at generation
   time — a malformed hook fails the BUILD, never the boot — and copies valid hooks into the FinalApp at
   `src/main/resources/db/conversion-hooks/<id>/`.
4. **Re-run `-ImpactOnly`.** The claimed item now renders `HOOK: <id>` instead of `!!`, and — if that was
   the only destructive item — the verdict drops from `DESTRUCTIVE` to `SAFE`/`NEEDS_ATTENTION` with no
   acknowledgment token shown. Nothing has run yet; this is still a read-only preview.
5. **Deploy.** On the real upgrade boot, `ConversionHookRunner` runs every hook whose claims match the
   live unresolved diff (in ascending `id` order, each in its own transaction), verifies it if `verifySql`
   is set, and re-diffs afterward to confirm the claim was honored — a claim is a promise the engine
   verifies, never trusts. If the hook resolved everything it claimed, the boot proceeds with no token
   required for that item.

This loop — see the blast radius, write the conversion, watch it resolve, deploy — is the GeneXus
reorganization experience NPDev didn't have before Phase 7.

### What a refusal looks like

- **`verifySql` doesn't match `verifyExpect`** → the hook is rolled back and the boot refuses
  (`HOOK_VERIFY_FAILED` in `npdev_schema_history`). The `verifySql` runs *inside* the hook's transaction,
  so a mismatch undoes the hook's changes and nothing persists.
- **A hook's `convert.sql` throws** → its own transaction rolls back atomically, the boot refuses
  (`HOOK_FAILED`).
- **A hook claims an item but the re-diff still finds it** → the boot refuses (`hook '<id>' claimed
  '<itemKey>' but the change is still required`) — never trust a claim without checking.
- **No hook claims an item at all** → completely unaffected; the existing itemized acknowledgment-token
  path applies exactly as before Phase 7.

> **⚠ H2 DDL caveat (engine limitation, not a bug).** PostgreSQL has transactional DDL, so a rolled-back
> hook fully undoes both its data (DML) and its schema (DDL) changes — a failed `verifySql` leaves the
> schema exactly as it was. **H2 does not have transactional DDL:** an `ALTER TABLE`/`DROP` auto-commits
> (and implicitly commits everything before it in the same batch), so on H2 a verify failure rolls back
> the hook's DML but any DDL it already ran persists. If you need a verify failure to leave the schema
> untouched, keep destructive DDL and data movement in separate hooks/boots, or run the conversion on
> Postgres. (This is exactly why NPDev verifies the *residual diff* after hooks run — rule 5 — as the
> real backstop: a claim that didn't actually resolve refuses the boot regardless of engine.)
>
> The runtime also warns at the moment it matters: if a hook's `convert.sql` mixes `ALTER`/`DROP`/
> `CREATE TABLE` with a `verifySql`, on H2, the boot log prints a `WARNING` naming the hook before it runs.

> **⚠ Hooks are individually atomic, not collectively atomic.** Each hook runs in its own transaction
> (rule 3). If hook 2 in a boot fails, hook 1 stays committed and the boot refuses; the *next* boot
> re-runs only what the diff still says is unresolved — which may re-select an already-partially-applied
> hook. **Write every hook to be idempotent** (`ADD COLUMN` guarded so it tolerates already existing,
> `UPDATE ... WHERE <not-yet-converted>`), because a hook may run again after a later hook failed. When a
> boot selects more than one hook, the log prints a one-line reminder of this before running them.

### v1 scope

SQL-only. A Java `DataMigrationHook` interface (for conversions too complex for a SQL script) is
deliberately deferred to the ADR-0003 code-bearing-objects track — not part of this phase.

Specified in [`archive/programme-history/SCHEMA_ENGINE_REMAINING_EXECUTION_PLAN.md`](SCHEMA_ENGINE_REMAINING_EXECUTION_PLAN.md)
Phase 7 (P7.1–P7.5). See also [`DATABASES_AND_MIGRATIONS.md`](../../DATABASES_AND_MIGRATIONS.md) §12 for the
operator decision matrix this adds a row to.

## Proposed conversion SQL — platform drafts, operator decides (implemented, Phase 8)

For a convertible `DESTRUCTIVE_NARROW_TYPE` item, the Impact Report drafts the copy-convert SQL an
operator can paste straight into a conversion hook's `convert.sql` — `com.finalexec.db.ProposedConversionSql`,
a pure function (no DB, no clock). The pattern:

```sql
ALTER TABLE t ADD COLUMN col__new <newtype>;
UPDATE t SET col__new = SUBSTRING(col, 1, <n>);        -- a sized-char narrowing: truncate, don't error
  -- or: UPDATE t SET col__new = CAST(col AS <newtype>); -- everything else NARROWING (numeric precision/scale, etc.)
ALTER TABLE t DROP COLUMN col;
ALTER TABLE t RENAME COLUMN col__new TO col;
```

plus a suggested `verifySql` (run *before* the drop, so it can still see both columns):
`SELECT COUNT(*) FROM t WHERE col IS NOT NULL AND col__new IS NULL`, `verifyExpect: 0`.

- **JSON** — the `items[].proposedConversionSql` field (previously always `null`) now carries the draft
  script for a convertible item.
- **Text** — a `proposed conversions` section after the summary/token, one block per convertible item,
  formatted as a ready-to-paste hook body.
- **Where no safe automatic conversion exists** (an `INCOMPARABLE` type-family change, e.g.
  `VARCHAR -> INTEGER`, or an unparseable type) — the field stays `null` / the text shows *"no safe
  automatic conversion — write a custom hook."* A copy-convert draft is a mechanical `CAST`/`SUBSTRING`;
  it cannot invent a conversion between unrelated types.
- **An item already claimed by a real hook** (`HOOK_CLAIMED`) is never given a draft — there is nothing
  to propose for something already resolved.
- **Engine note:** H2 and Postgres share the exact syntax this class emits (`ADD`/`DROP`/`RENAME COLUMN`,
  `SUBSTRING(str, pos, len)`, `CAST(expr AS type)`) for every case it covers today, so nothing branches
  per-engine yet — `ProposedConversionSql`'s one internal seam (`convertExpression`) is where a future
  divergence would go, without touching either renderer.

### Non-goal

**NPDev never auto-runs a proposal.** Adoption is always the operator copying the draft into a hook and
reviewing it first — the same discipline every conversion hook already requires (Phase 7's "authoring
the hook IS the acknowledgment" is still true; a *draft* is not authorship). This is a deliberate
contrast with GeneXus, which auto-runs its reorganization conversions: NPDev keeps a human between the
draft and the execution.

Specified in [`archive/programme-history/SCHEMA_ENGINE_REMAINING_EXECUTION_PLAN.md`](SCHEMA_ENGINE_REMAINING_EXECUTION_PLAN.md)
Phase 8 (P8.1–P8.3).
