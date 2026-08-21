# Using NPDev with MySQL or SQL Server

**Status: supported — a generated app boots, serves and persists on both engines, proven in CI.**
Everything below is measured — each claim names the CI run that produced it. Where something has
not been measured, it says so rather than sounding confident.

> **The one sentence to take away:** MySQL and SQL Server carry the full claim — an app generated for
> either engine realizes its schema through NPDev's own engine, boots Spring, serves, and persists,
> the same bar PostgreSQL crossed before them. Two operational differences remain real and change
> what you should *do*, not whether it works: MySQL commits implicitly on DDL (§4.2), MySQL wants
> `utf8mb4` (§4.1), and SQL Server's text columns are `NVARCHAR` (§5.1). Read §4 and §5 before
> relying on them in production.

**Companion docs:** [`DATABASES_AND_MIGRATIONS.md`](../DATABASES_AND_MIGRATIONS.md) (how NPDev turns a
model into a schema), [`SCHEMA_EVOLUTION.md`](../SCHEMA_EVOLUTION.md) (what happens when the model
changes), [`DEPLOYMENT.md`](../DEPLOYMENT.md).

---

## 1. Choosing an engine

```sh
npdev engines                     # what exists, what each needs, which carry caveats
npdev init my-app --engine mysql --db-host localhost --db-user npdev --db-password ...
```

`npdev engines` is the authoritative list — the CLI reads it from one registry, and so does the
Manager's dropdown, so the two cannot disagree. A supported engine that carries operational caveats
prints them **at the point of choice**, not in a changelog.

| engine | `--engine` | needs a server | status |
|---|---|---|---|
| H2 (file) | `h2local` | no | supported — the default |
| H2 (TCP) | `h2server` | yes | supported |
| PostgreSQL | `postgres` | yes | supported |
| MySQL 8.4+ | `mysql` | yes | supported — two operational caveats, see §4 |
| SQL Server 2022+ | `sqlserver` | yes | supported — two operational caveats, see §5 |
| in-memory | `inmemory` | no | supported — persists nothing |

`npdev init --engine` writes both files an app needs: `db.definition.json` (how your data persists)
and the `database` block of `config.json`. Hand-editing is no longer required, and the two files
cannot disagree about the engine.

---

## 2. What "supported" means here

An engine becomes **supported** — the status both MySQL and SQL Server hold today in `npdev engines`
— when a *generated application* boots, serves and persists on it in CI. That is a higher bar than a
dialect passing unit tests, and it is the entire point:

| Layer | MySQL / SQL Server | PostgreSQL |
|---|---|---|
| Dialect string generation (Tier A) | proven — 78 assertions, four engines | proven |
| Behaviour over raw JDBC (Tier B) | proven — **14/14 vectors per engine, 0 skips**, real containers (run `31271016482`) | proven |
| **A generated app booting and serving** | **proven** (run `31296993259`): boots, serves and persists on both. STOR-12 re-ran the app proof 4/4 on a fresh database and 4/4 on a non-fresh one — the deployment shape | **proven** (run `31279857141`): boots, non-BMP unicode round trip, paginated query, rows survive a restart |

**It was not always this way.** Getting here meant closing a chain of engine-specific defects that
each sat behind the one before it (the STOR-* work, 2026-08-08 → 2026-08-10). The first was STOR-5:
NPDev's own schema-realization script (`V1__npdev_schema_realization.sql`) was written in
PostgreSQL/H2 guarded-DDL idioms — `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`. MySQL
supports one of those and rejects the others; T-SQL rejects all of them, so an app generated for
either engine failed during its first Flyway migration. Two earlier causes in the same area were
already fixed when STOR-5 landed: the app template carried no JDBC driver for these engines at all
(`STOR-4`), and NPDev's own `execution_id` primary key was a `TEXT` column that MySQL will not
index. Each fix revealed the next construct and the next defect (STOR-6, STOR-7, STOR-9, STOR-10,
STOR-12) until the application-level probe closed on all three engines. That probe lives in
`.github/workflows/engine-support.yml`; today its matrix job is literally named "a generated app
boots, serves and persists" for `[mysql, sqlserver, postgres]`.

Tier B runs against **real** MySQL 8.4 and SQL Server 2022 containers, pinned by digest so a red
result cannot be an upstream image change. It covers upsert idempotence, pagination non-overlap,
JSON round-trip, reserved-word columns, DDL/DML transactionality, auto-increment monotonicity,
charset fidelity and enforced uniqueness.

The checks in §3 (`npdev doctor`) are the operational safety net on top of the proof: the moment a
database is configured wrong, they say which one of several failures it is — before a build.

---

## 3. Before you start: `npdev doctor`

```sh
cd my-app
npdev doctor
```

Six database checks run when doctor can find your app, each distinguishing a failure the previous
one cannot — a distinction that matters, because three of them would otherwise all read as "the
password is wrong":

| check | fails when | why it is separate |
|---|---|---|
| `database-reachable` | host/port refuses | the #1 first-run failure — otherwise a Spring stack trace after a full build |
| `database-credentials` | it answers, auth rejected | tells "wrong password" apart from "not running" |
| `database-exists` | credentials accepted, database absent | **NPDev creates TABLES at boot, never the database.** Create it once yourself |
| `database-privileges` | connects, cannot `CREATE TABLE` | NPDev realizes schema at boot; a read-only user fails late and confusingly |
| `database-charset` | MySQL is not `utf8mb4` | **the silent one** — see below |
| `database-engine-support` | the engine is experimental | honest status, not a footnote |

All six pass against a real PostgreSQL in CI (run `31279857141`); four are proven by fixtures built
to make them fail, because a check that has only ever passed is not a check.

Three of these need your engine's JDBC driver, which arrives in your Gradle cache the first time you
build an app for that engine. Before then doctor says so plainly and checks reachability anyway — it
never guesses.

### 3.1 Before the app exists: `npdev db test-connection`

`npdev doctor` needs an app to check. The moment you most want these answers is *earlier* — while
you are still deciding what to type into `--db-host` and `--db-port`:

```sh
npdev db test-connection --engine mysql --db-host localhost --db-port 3306 \
    --db-user root --db-password ...
```

Same five checks, same ids, same order — it shares one code path with `doctor`, so the two can never
disagree about one database. Omit `--db-name` and it asks whether the **server** is usable rather
than whether some particular database exists, which is the right question before `npdev init` has
chosen a name.

This is what the Manager's **Test connection** button runs, beside the connection fields on its
create-app form.

### 3.2 Running the database itself: `npdev db`

Once an app is generated, five operations drive its own generated `_ops` scripts:

```sh
npdev db start       # start this app's database
npdev db stop        # stop it, data intact
npdev db status      # is it running?
npdev db connection  # connection details, for DBeaver or psql
npdev db reset --confirm I_UNDERSTAND_DB_DATA_WILL_BE_DELETED
```

Each prints which app it is acting on (`[appId | engine | path]`) before anything else. That is
worth reading: the generated `_ops` toolbox is written next to the FinalApp's *parent*, so two apps
scaffolded into the same folder share one toolbox — see `QUAL-3`.

`reset` deletes data and removes the container, so it refuses without the acknowledgement token,
from a terminal and from the Manager's button alike.

---

## 4. MySQL: the two things that are genuinely different

### 4.1 `utf8mb4` is not optional

MySQL's legacy three-byte `utf8` **accepts** anything outside the Basic Multilingual Plane and
**stores it wrong**. No error, ever. An emoji, many CJK characters, and a great deal of real user
input are affected.

```sh
mysqld --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
# or, per database:
ALTER DATABASE my_app CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

`npdev doctor`'s `database-charset` check exists for exactly this, and it is the only problem in
this document that produces no error message at any layer.

### 4.2 MySQL commits implicitly on DDL — a failed migration cannot be undone

This is the one operational difference that changes what you should *do*, and it is measured, not
inferred (conformance vector T3, run `31271016482`, against real engines):

| engine | after a two-step migration whose second step fails |
|---|---|
| PostgreSQL | neither step survives — the whole thing rolled back |
| SQL Server | neither step survives |
| **MySQL** | **step one is already permanent** |
| H2 | step one is already permanent (the same trap, on the default dev engine) |

**On Postgres, "fix the model and re-run" is correct. On MySQL it is not** — a re-run starts from a
schema that already moved, which neither the old nor the new model describes.

NPDev reports this truthfully rather than claiming a rollback: a failed multi-step schema pass names
the items that already landed, the item that failed, and the `PARTIAL-CRASH` row in
`npdev_schema_history` to inspect. Take a backup before a multi-step migration on MySQL, and read
what the failure actually says before re-running.

> This was a real defect, not a hypothetical: three refusal messages used to say *"the hook's changes
> were rolled back; nothing persisted"* on engines where that is false (`STOR-2`). A false all-clear
> is what turns a recoverable half-migration into one nobody goes looking for.

---

## 5. SQL Server: the two things that are genuinely different

### 5.1 Text columns are `NVARCHAR`, and that matters

SQL Server's plain `VARCHAR` is **non-Unicode** — it loses characters silently, one per character.
NPDev's dialect maps text to `NVARCHAR` for this reason.

This is not theoretical either: the conformance suite's own J2 vector once hand-wrote
`VARCHAR(4000)` instead of asking the dialect, stored `café ☕`, and read back `café ?`. The dialect
had the right answer and was never asked. The vector was right to fail.

### 5.2 Row caps are a prefix, not a suffix

`SELECT TOP n` rather than `LIMIT n`. Handled inside the dialect; you never see it. Worth knowing
only because a paginated query **must declare an `ORDER BY`** — SQL Server rejects `OFFSET…FETCH`
without one, and NPDev refuses it on *every* engine rather than injecting an arbitrary order on the
one that needs it. An injected order still returns overlapping pages; that would trade a loud failure
for a silent wrong answer.

---

## 6. What is still not proven

Kept here deliberately, because a page that lists only strengths is not a status.

- **Only the pinned container versions have been exercised** — MySQL 8.4, SQL Server 2022,
  PostgreSQL 16. Other versions are unmeasured, not unsupported.
- **No production-scale data has been run through either engine.** The probes are small by design.
- **The application-level workflow is dispatch-triggered**, not per-push: it generates and compiles a
  Spring Boot app per engine, which is minutes rather than seconds. The raw-JDBC conformance suite
  *is* per-push.
- **Mongo, Firebase and other document engines are not supported and not in progress.** The
  capability model makes a document engine safe to add later — the generator refuses a model needing
  something the engine lacks — but shipping two SQL engines properly is worth more than a broader,
  shallower matrix.

---

## 7. If something goes wrong

| symptom | first thing to check |
|---|---|
| app fails to boot with a connection error | `npdev doctor` — `database-reachable` |
| boot fails while creating tables | `database-privileges`; NPDev realizes schema at boot |
| text comes back with `?` or missing characters | MySQL: `database-charset`. SQL Server: report it — the dialect should have used `NVARCHAR` |
| a migration failed and you are unsure what applied | **on MySQL, do not just re-run.** Read the failure message, then the `PARTIAL-CRASH` row in `npdev_schema_history` |
| pages of a list overlap or skip rows | the query is missing an `ORDER BY` with a tie-breaker |

`npdev capabilities` prints what each engine can do, generated from the dialects themselves, so it
cannot drift from what the generator actually refuses.
