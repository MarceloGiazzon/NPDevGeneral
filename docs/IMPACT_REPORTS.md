# Schema Impact Reports

The **impact report** is NPDev's answer to "what will this upgrade do to my data?" — the equivalent of
GeneXus's *Impact Analysis Report (IAR)*. Before (or as) a schema change is applied, NPDev computes a
read-only, per-item view of the blast radius from the ONE canonical desired-vs-current `SchemaDiff` (the
schema-engine rebuild, see [`DATABASES_AND_MIGRATIONS.md`](DATABASES_AND_MIGRATIONS.md) §16), probes the
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

## Surface 1 — at boot time (implemented)

On **every upgrade boot** (whenever the schema fingerprint changed), the executor writes the machine
report and prints the human table:

- **JSON** → `runtime-data/impact-reports/<yyyyMMdd-HHmmss-SSS>-<from>-<to>.json`, conforming to
  [`NPDevContract/schemas/impact-report.schema.json`](../NPDevContract/schemas/impact-report.schema.json).
  The last **10** reports are retained (older ones are pruned), mirroring the pre-drop snapshot writer.
- **Text** → the aligned table is printed to stdout, one line per item, `DESTRUCTIVE` items prefixed
  `!!`, with a `N safe / N attention / N destructive` footer.

When an upgrade is **destructive and unacknowledged**, the boot refuses, and the refusal message now
**leads with the impact table** so an operator sees the blast radius immediately — followed, as before,
by the itemized destructive report, the **expected acknowledgment token**, and the pointer to
[`SCHEMA_EVOLUTION.md#acknowledging-destructive-changes`](SCHEMA_EVOLUTION.md#acknowledging-destructive-changes).
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

Specified in [`SCHEMA_ENGINE_REBUILD_PLAN.md`](SCHEMA_ENGINE_REBUILD_PLAN.md) Phase 6 (P6.4, P6.5).
