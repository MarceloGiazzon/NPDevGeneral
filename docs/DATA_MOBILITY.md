# Data mobility — export, import, transfer

Moves an NPDev app's data DB↔file or DB↔DB, across any supported engine (H2, Postgres, MySQL,
SQL Server — including between two DIFFERENT engines). One screen in the
[NPDev Manager](MANAGER.md) ("Data Transfer"), and the `npdev db` verbs underneath it.

Everything the Manager screen can do, a terminal can do first — same standing rule as
[the Monitor](MONITOR.md): the Manager is a window onto `npdev`, never a second implementation.

## The four verbs

| Command | Direction | What it does |
|---|---|---|
| `npdev db export --app <dir>\|--url <jdbcUrl> --format csv\|sql --tables all\|business\|<list> --out <dir>` | DB → file | Streams every row of every table in scope to a file bundle (`manifest.json` + one `.csv`/`.sql` per table) |
| `npdev db import --bundle <dir> --format csv\|sql --app <dir>\|--url <jdbcUrl> [--include-ddl] [--confirm]` | file → DB | Reads a bundle back, after a DB Structure Check |
| `npdev db transfer --source-app\|--source-url ... --target-app\|--target-url ... --tables ... [--include-ddl] [--confirm]` | DB → DB | Streams rows directly between two live connections — no file touched |
| `npdev db structure-check --source-app\|--source-url ... --target-app\|--target-url ... --tables ...` | (read-only) | Runs the same check `import`/`transfer` run internally, on its own |

Every verb shells to a real compiled Java entry point, `com.finalexec.db.DataTransferMain`, on the
same `java -cp <app's classpath>` pattern `npdev db verify` already uses — the CLI is a thin
argument-and-classpath wrapper (see `npdev_cli.py`'s `run_db_export`/`run_db_import`/
`run_db_transfer`/`run_db_structure_check`), never a second implementation in Python.

## `--tables`

- `all` — every table.
- `business` — only concepts declared directly in the app's own model root (no pack origin).
  Excludes platform built-ins (identity, workspace) *and* any imported third-party pack alike.
  Resolved from the classpath `npdev/metadata/concepts.manifest.json` manifest's `isBusiness` flag.
- `<comma-list>` — an explicit, lower-cased table-name list.

## The DB Structure Check

Answers one question: **can the target's current schema receive the source's rows, as it stands
right now?** Three verdicts:

- **EQUAL** — every source table/column the scope touches exists on the target with a compatible
  type. Proceeds automatically.
- **COMPATIBLE** — the target has extra tables/columns the source doesn't need (harmless), or is
  missing something `--include-ddl` will create before writing. Needs `--confirm` to proceed.
- **INCOMPATIBLE** — the target is missing something the source needs and `--include-ddl` wasn't
  given, or a shared column's type can't safely hold the source's data. Always blocks, regardless
  of `--confirm`.

Implemented by reusing the same real machinery the boot-time schema-evolution engine uses
(`SchemaDiffEngine`/`TypeChangeMatrix`/`CurrentSchemaReader`, `com.finalexec.db.schemastate`) rather
than a parallel comparison engine — both schemas being compared are always NPDev-model-derived
(either two live app databases, or a live database against an export bundle's manifest, itself
captured from a live NPDev app), so the diff engine's existing cross-engine type handling already
answers "does the target's realized type have at least the capacity of the source's" correctly. See
`DataMobilityStructureCheck`'s own javadoc (`NPDevRuntimeHost/runtimehost-core/.../db/datamobility/`)
for the exact `SafetyClass` → verdict reduction and why the source/target roles in that reuse are
the opposite of what a first read of `SchemaDiffEngine.diff(desired, current)` suggests.

## Formats

- **CSV** — one `.csv` per table (RFC4180-shaped), plus `manifest.json`. Values carry no type
  information; import re-hydrates each value using the column's portable category from the
  manifest, never a bare string bind.
- **SQL Insert Statements** — one `.sql` per table, a single self-consistent
  `INSERT INTO t (...) VALUES (...);` grammar (see `SqlInsertRowFormat`'s own javadoc for its
  documented scope: it reads exactly what it writes, not an arbitrary hand-written dump). Values
  are re-hydrated into typed JDBC parameters at import time — never re-executed as raw SQL text
  against a possibly different target engine, which is what keeps booleans/dates portable across
  engines that spell them differently (SQL Server has no `TRUE`/`FALSE` literal, for one).

`--include-ddl` (export-independent — decided at import/transfer time, against the real target)
generates `CREATE TABLE`/`ALTER TABLE ... ADD COLUMN` for whatever the target is genuinely missing,
using the source's declared type rewritten through the target's own `SqlDialect`.
