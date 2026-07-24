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

## Planned surfaces (not yet implemented)

- **Surface 2 — pre-deploy CLI.** A `REPORT_ONLY` lifecycle mode that computes the report against the
  live database, prints it, writes zero DDL, and exits with a code (`0` safe / `2` needs-attention /
  `3` destructive), wired to a `-ImpactOnly` flag on `Build-NpdevApp.ps1`. (`-PlanOnly` remains the
  model-vs-previous-model preview that needs no database; `-ImpactOnly` would be model-vs-live-database.)
- **Surface 3 — ControlPanel.** A SUPERUSER endpoint + page that renders the report on demand for a
  running app.

Both are specified in [`SCHEMA_ENGINE_REBUILD_PLAN.md`](SCHEMA_ENGINE_REBUILD_PLAN.md) Phase 6 (P6.4,
P6.5) and are safe, additive follow-ups on top of the engine (`ImpactReport` / `ImpactReportJson` /
`ImpactReportText`) that already exists.
