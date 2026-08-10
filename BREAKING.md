# Breaking changes

NPDev is pre-1.0 and deliberately unstable — see the "Stability policy" section in `README.md` for
why. Every breaking change to the model DSL, generated code layout, or internal APIs gets a
one-line entry here, in the same commit that makes the change, alongside the `npdev migrate`
codemod that rewrites existing models automatically.

## 2026-08-10 — `SqlDialect.requiresOrderByForPagination()` is removed (STOR-13)

**What changes.** The method is gone from the `SqlDialect` interface and from all four
implementations. Nothing replaces it: `requireOrderedForPagination(String)` already demands an
`ORDER BY` of every engine, so the per-engine answer could never change an outcome.

**Who is affected.** Any implementation of `SqlDialect` outside this repo, which would no longer
compile against the interface — delete the override. In this repo, the four dialects and one
anonymous test stub, all updated in the same commit. No caller is affected, because there was none:
that is the defect this removes.

**Why.** Shipping an unconditional rule alongside a flag that reads like it gates the rule invites
the next reader to write a conditional that cannot exist. Conformance vector P3 pins the refusal to
every engine on purpose — injecting an order on the one engine that needs it hides the difference
from the model and still returns overlapping pages.

**No codemod.** A codemod rewrites models and call sites; this method had neither.

## 2026-08-10 — `_ops/resolved-db-plan.json` records app paths RELATIVE to the app (PORT-2)

**What changes.** `finalAppPath` and `opsRoot` were absolute paths on the generating machine; they
are now `"."` and `"_ops"`, resolved against the app directory at read time — the same treatment
`resolvedDataRoot` received in PORT-1. `Run-FinalApp.ps1`, `Build-FinalApp.ps1` and
`README_RUNBOOK.md` no longer name an absolute app location either. `runtimeHostLibsDir` stays
absolute (it is a machine-level cache, not part of the app) but is now overridable via
`NPDEV_RUNTIMEHOST_LIBS` and is dropped when the recorded cache is not present locally.

**Who is affected.** Anything reading `resolved-db-plan.json` and expecting `finalAppPath`/`opsRoot`
to be absolute. In this repo that is `NPDevCli/npdev_cli.py`, updated in the same commit.
`NPDevManager` mentions the file only in a doc comment and parses none of these fields.

**Why.** A copied or shared app's toolbox operated the ORIGINAL app: `_ops/Run-FinalApp.ps1` ran the
jar at the path the app was generated in. Not a failure — a silent success against the wrong
artefact. Someone who copied an app, edited it, and pressed run was running the copy they had not
edited.

**No codemod.** Nothing in a model references these paths, and a regenerated app is correct by
construction. An app generated before this change keeps working where it was generated; copy it and
it will not.

## 2026-08-10 — a generated app keeps its database BESIDE ITSELF, at `<FinalApp>/data` (PORT-1)

**What changes.** The data root, and therefore `spring.datasource.url`, is app-relative instead of an
absolute path on the machine that generated the app:

| | before | after |
|---|---|---|
| `spring.datasource.url` (H2Local) | `jdbc:h2:file:D:/…/Build/databases/<app>/<db>;…` | `jdbc:h2:file:./data/<db>;…` |
| `spring.datasource.url` (H2Server) | `jdbc:h2:tcp://host:port/D:/…/<db>;…` | `jdbc:h2:tcp://host:port/./data/<db>;…` |
| `npdev.database.data-root` / `_ops` plan `resolvedDataRoot` | absolute | `data`, or `data/<generated name>` |
| H2Server `-baseDir` | the data root | the FinalApp directory |

**Who is affected.** Everyone with an existing app on H2Local or H2Server: **your database does not
move itself.** The app will create a new, empty one at `<FinalApp>/data` on the next boot. To keep
your data, copy `<Build>/databases/<appId>/*` into `<FinalApp>/data/` before running it, or start the
app with `--spring.datasource.url=` pointing at the old file. Server engines (Postgres, MySQL, SQL
Server) are unaffected — their URLs are built from host/port and never contained a path.

Also: a `db.definition.json` that declares `database.h2FilePath`, or an H2Server
`database.jdbcUrl` containing an absolute path, is now REFUSED at generation time — NPDev derives an
app-relative path and cannot honour or verify an absolute one. Remove `h2FilePath` (nothing reads it),
or rewrite the URL's path as `./data/<databaseName>`. The refusal message says both.

**Why.** The absolute path was resolved at BOOT, so a generated app handed to anyone else tried to
open its database on a drive they may not have. It is the most serious of PORT-1's six leaks and the
only one that stops an app working. Proved fixed by copying an entire built FinalApp to a path
sharing no ancestry with the workspace and booting it there.

**Regeneration keeps your data.** `<FinalApp>/data` is now spared by both wipes (`Build-NpdevApp.ps1`
and the generator's `deleteBeforeMount`), so the schema-evolution paths that only run against an
existing database still have one.

**No codemod.** Nothing in a model references these paths — the value is derived, and the two
`db.definition.json` fields above are refused with a message naming the fix rather than rewritten,
because choosing where someone's existing database should end up is not a decision a codemod can make
for them. Same reasoning as the `wmsoffice` entry below.

## 2026-08-10 — the `wmsoffice` profile's JWT key paths are now relative and overridable (PORT-1)

**What changes.** `application-wmsoffice.yml`, which ships in every generated app via the shared
RuntimeHost template, had `public-key-path` and `private-key-path` set to absolute paths under the
AUTHOR's build directory. They are now
`${NPDEV_WMSOFFICE_KEYS_DIR:./wmsoffice-keys}/jwt-{public,private-pkcs8}.pem`.

**Who is affected.** Only an app that activates the `wmsoffice` Spring profile — for everyone else
the file was, and remains, inert. If you activate it, either put the keys in `./wmsoffice-keys`
beside the running app or set `NPDEV_WMSOFFICE_KEYS_DIR`.

**Why.** The file's own comment argued it was "inert for any app that doesn't activate the profile,
so it's safe to ship". Inert is not the same as harmless: every generated app, for every user,
carried one machine's filesystem layout and named its key material. Found by the first out-of-tree
generation this repo has run (`scripts/hygiene/check-out-of-tree-generation.ps1`), which reproduced
it independently as F7 from the third-person trial.

**No codemod.** Nothing in a model or in generated code references these paths; the change is a
default in a template resource.

## 2026-08-09 — an app's `_ops` toolbox and its database identity are now per-APP, not per-FOLDER (QUAL-3)

**What changes.** Two things move, and they are one defect:

1. `_ops` is emitted INSIDE the FinalApp (`<FinalApp>/_ops`), not beside it (`<FinalApp>/../_ops`).
2. `npdev init` now writes a `manifest.json` declaring the app's id. The generator already prefers
   that manifest over inferring an id from the directory layout, so `npdev init D:\Apps\my-app` now
   yields `my-app` where it previously yielded `Apps`, the parent folder. `containerName`, the
   database name and the data root all derive from it.

**Why.** Both encoded "the toolbox/identity belongs to the parent directory". Measured with two real
apps generated into one folder: both resolved to `appId=qual3`, `containerName=npdev-qual3` and data
root `Build/databases/qual3`, and both shared one `_ops/resolved-db-plan.json`. `npdev db status
--app <app-a>` answered about `app-b`. They were not two apps sharing a toolbox — they were one
database with two front doors, and `npdev db reset` for either destroyed the other's data while
reporting success. The acknowledgement token does not protect against this: the user types it
correctly, for the app they intend, and different data is deleted.

**Identity is DECLARED, not inferred better.** The obvious fix — "if the definition's directory is
not called `definition/`, that directory is the app" — was implemented, measured, and reverted: 25
corpus definitions live in a directory called `Input` with no manifest, and that rule collapsed all
25 onto `appId=Input`, a wider collision than the one being fixed and inside the corpus rather than
a user's folder. It also broke `UserDatabaseDefinitionDeclaredConnectionTest`. Path shape cannot
tell an app directory from a wrapper directory, so it is no longer asked to.

**Codemod: none, and none is possible.** No model content changes. Instead the READER carries the
compatibility: `_find_ops_root` prefers the app-local toolbox and only falls back to the legacy
shared location when no app-local one exists, printing `using the legacy SHARED toolbox at <path> --
it may describe a different app than the one you named`. Regenerating an app moves it to the new
layout. An existing app can also be fixed by hand by adding a `manifest.json` with an `id`.

**What breaks, and for whom.** An app generated before this change keeps working via that fallback,
with the warning. Once it gains a manifest its database identity changes (`npdev-Apps` →
`npdev-my-app`), so it connects to a NEW, empty database; the old one still exists under its old
name and can be dumped and restored if it held anything. Every corpus layout
(`<App>/definition/...`, `<App>/Input/...`) is unaffected — those already carry a manifest or
resolve correctly through the unchanged fallback, pinned by `AppIdentityIsolationTest`.

## 2026-08-09 — SQL identifiers are now QUOTED when the target engine reserves them (STOR-6)

**What changes.** A model field named `order`, or a concept whose table realizes to `rows`, now
emits `` `order` ``/`[order]`/`"order"` in the engine's own quoting syntax, and the runtime queries
it the same way. Nothing else moves: quoting is CONDITIONAL, so an identifier no engine reserves is
emitted exactly as before.

**Codemod: none, and none is possible.** A model that hits this could not generate a runnable schema
before, so there is no existing behaviour to migrate. Measured over the corpus, 4 models x 3
engines: 10 of 12 emit byte-identical DDL, and the 2 that move (`rank` on MySQL, `plan` on SQL
Server) move from broken to working.

**What breaks, and for whom.** `SqlDialect` gained abstract `isReservedIdentifier(String)`. Any
implementation outside this repo must add it. There is no default: a dialect that silently answered
"nothing is reserved" would restore this defect for its engine while every test stayed green, which
is the X0 rule this interface exists to enforce.

**Three seams, not two.** The generator emits the DDL, `JdbcBusinessConceptStore` reads and writes
rows, and `SchemaLifecycleExecutor.quotedIdentifier` serves the 40 schema-lifecycle sites that only
run when a column CHANGES on an existing database — the third was found by a live run, not by the
plan. They are pinned together by the twin-pair rule `sql-identifier-quoting-three-seams`, because
quoting one alone is worse than quoting none: the app builds, boots, and cannot find its own table.
`SchemaLifecycleExecutor.safeIdentifier` deliberately stays UNQUOTED for the places a name goes into
a string literal (`information_schema` guards, SQL Server's `sp_rename`).

## 2026-08-09 — `db.definition.json`: a `jdbcUrl` / `h2FilePath` that CONTRADICTS the real connection is now refused (STOR-8)

**What changes.** `database.jdbcUrl` and `database.h2FilePath` are still accepted. A value that
DISAGREES with the connection NPDev will actually make now fails at generation time, naming both the
declared value and the real one.

**Codemod: delete the key, or fix it.** There is nothing to migrate mechanically — a contradicting
value was already not being honoured, so removing it changes no behaviour. `npdev migrate` needs no
rule here, and the refusal message tells you which of the two you meant.

**Why this is a fix and not a restriction.** Both fields read as authoritative. `h2FilePath` is
consulted by nothing at all; `jdbcUrl` is consulted only for H2Server, where `resolveHost`/
`resolveHostPort` parse the host and port out of it and everything else is ignored. So a user who
pointed `jdbcUrl` at an existing production database got **no error, no warning, and a connection to
a different database** — and could then write to it. That is the X0 silent-answer rule broken in the
storage layer, where it is least visible and most expensive.

**The blanket refusal was measured and rejected.** The obvious change was to refuse both fields
outright. **Twelve app definitions set one of them — four of them official samples** (AuxScreen,
Pigmentampa, WmsOffice, WordLab) — and every one declares exactly what NPDev composes anyway.
Refusing the field would have broken all twelve to fix a hazard none of them has. So the guard is on
DISAGREEMENT, and options are ignored when comparing (`MODE=`, `DB_CLOSE_ON_EXIT=`) because failing
on those would be the noisy gate this project refuses everywhere else.

**Honouring an explicit URL remains unbuilt, deliberately.** It is a feature, and it raises a real
question — does an explicit URL bypass the identity check that stops two apps sharing a database? —
which deserves its own design rather than being smuggled into a cleanup.

## 2026-08-08 — `database.engine` gains `MySQL` and `SqlServer` (storage/PLAN.md S4b/S5)

**Not a breaking change — widened, not narrowed. No codemod needed, and that is a claim, not an
omission.** Every existing `db.definition.json` validates identically: `Postgres`, `InMemory`,
`H2Local` and `H2Server` keep their exact meaning, their exact conditional requirements, and their
exact generated output. The enum grew by two values; nothing was renamed, removed or retyped, so
there is no model text for `npdev migrate` to rewrite.

Threaded: `schemas/ai/user-db-definition.schema.json` (enum + a host/port/username/password
requirement for each new engine, matching Postgres's), `DatabaseEngine` (two new values on the
EXISTING `storageMode` axis — both `jdbc`, because that second string is the split a document engine
will use, not a dialect name), `UserDatabaseDefinitionLoader` (driver, JDBC URL, default port,
container naming), and `SqlDialects` (registry).

**`MySQL` and `SqlServer` are SUPPORTED** as of 2026-08-09, run `31296993259` -- and this entry
spent a long time saying the opposite, correctly, so the change is worth stating precisely.

The bar was never "the dialect passes unit tests". It was: **a generated app boots, serves and
persists on this engine, in CI.** That is now true for both, in the same run, in the same job as
Postgres:

| assertion | MySQL 8.4 | SQL Server 2022 |
|---|---|---|
| boots -- schema realized by NPDev's own engine | pass | pass |
| non-BMP unicode round-trips (`cafe (coffee) (rocket)`) | pass | pass |
| filtered + ordered + paginated query | pass | pass |
| rows survive a restart | pass | pass |
| Tier C: nullable column added, rows preserved (E1) | pass | pass |
| Tier C: `renamedFrom` MOVES data (E2) | pass | pass |
| Tier C: nullability and unique/non-unique enforced (I2/I3) | pass | pass |
| the five `_ops` operations, byte-identical to Postgres's | pass | pass |

**Eight defects stood between "the dialect is complete" and this**, each invisible until the one
before it was fixed, and every one of them found by building the artifact a user actually runs:

| # | what | id |
|---|---|---|
| 1 | the app template declared no MySQL/SQL Server **JDBC driver** | STOR-4 |
| 2 | NPDev's own internal tables key on `TEXT`, which neither engine can index | STOR-4 |
| 3 | the realization script is written in Postgres/H2 **guarded-DDL idioms** | STOR-5 |
| 4 | a text column has THREE roles and only two were ever asked about (`TEXT DEFAULT` is MySQL error 1101) | STOR-7 |
| 5 | a row lock is a suffix on three engines and a **table hint** on SQL Server | STOR-9 |
| 6 | five more two-engine assumptions between "it boots" and "it works" -- a Postgres-by-default dialect probe, UUID bound as a serialized Java object, timestamps read back unbindable, a schema differ comparing the catalog against a type the emitter never wrote, a two-way column rename | STOR-10 |
| 7 | a Postgres-only SQLSTATE, so an app booted once and **never again** | STOR-12 |
| 8 | on MySQL a create violating `unique: true` returned **200 and overwrote the row that held the value** | STOR-11 |

**#8 is the one to read.** It was known and documented -- `MySqlUpsertStrategy`'s javadoc described
the divergence exactly -- and then closed with "nothing in NPDev's generated schema puts a second
unique index on a table it also upserts by id today". That sentence was false when written: any
field declaring `unique: true` produces exactly that shape. A record correct about the ENGINE and
wrong about NPDEV is the more dangerous half, because it turns a live hazard into a closed question.

**What is still true and worth knowing** (these are differences, not defects, and each is declared
at the point of choice by `npdev engines` and by the generated `.env.example`):

- **MySQL commits implicitly on DDL.** A migration that fails partway CANNOT be rolled back --
  earlier steps are already permanent. NPDev reports this truthfully (`PartialApplicationTruth`)
  rather than claiming a rollback it did not perform.
- **SQL Server has no suffix row cap** (`TOP` is a prefix), so `SqlDialect.rowLimit()` throws there
  rather than returning a plausible wrong answer. Boundary **B29**; zero production call sites --
  every real site asks `rowLimited()`, which every engine answers.
- **Identifiers are not quoted** (`STOR-6`, open): a field named `value`, `order` or `rows` produces
  DDL the engine rejects. This is engine-INDEPENDENT -- it bites H2 and Postgres too -- and is not a
  MySQL/SQL Server limitation.


`npdev engines` marks MySQL and SQL Server EXPERIMENTAL and says why **at the point of choice**, in
the CLI, in the Manager's dropdown and in `docs/USING_MYSQL_AND_SQL_SERVER.md` — all from one
registry, so none of them can drift into claiming otherwise.

## 2026-08-08 — `build.javaVersion`'s upper enum removed (ROUND2_PLAN.md R1c)

**Not a breaking change — widened, not narrowed.** Every config that validated under the old
`enum: [17, 21]` still validates identically. What's new: any integer `>= 17` is now accepted at
the schema/validation layer, with no upper bound — the 3rd-party user who originally asked for "a
newer Java version" (below) wanted this future-proofed against every Java version to come, not
renegotiated every time a new JDK ships.

Threaded: all 3 `config.schema.json` mirrors (`build.javaVersion.enum` → `build.javaVersion.minimum:
17`), `GeneratorMain.resolveJavaVersion` (the `SUPPORTED_APP_JAVA_VERSIONS` allowlist replaced with a
floor-only check), `GeneratorMainJavaVersionResolutionTest` (new cases: accepts 25, accepts a
version that doesn't exist yet, rejects below the floor).

**What still gates a value above 21 in practice, and everything that had to move to make it real:**
every generated app's bundled Gradle wrapper (`NPDevRuntimeHost/gradle/wrapper`) moved 8.5 → 9.5.1,
`foojay-resolver-convention` 0.8.0 → 1.0.0, `org.springframework.boot` plugin 3.3.2 → 3.5.16 (its
`bootJar` task called a Copy API method Gradle 9 changed), ArchUnit 1.3.0 → 1.4.2 and Mockito
5.11.0 → 5.23.0 / Byte Buddy → 1.17.7 (both couldn't read/instrument Java 25's class file format).
Two more bugs surfaced only by actually booting a packaged jar as a real external process — the
`application-step0.yml` "zero-setup trial" profile never cleared an inherited
`spring.autoconfigure.exclude=DataSourceAutoConfiguration,...` (an empty-string YAML override was
silently ignored; fixed with proper `exclude: []` list syntax), and three `NpdevObservabilityConfig`
beans (`traceSummaryStore`/`executionSummaryStore`/`eventMetaStore`) registered a dual-interface
adapter instance under two type-assignable bean names, breaking any plain
`TraceStore`/`FlowInstanceStore`/`EventStore` injection once a real `DataSource` was available for
the first time. Full chain, live-verification, and how each was isolated as genuinely new (not
pre-existing): `REG-143`. Platform modules (dsl/kernel/generator/adapters/runtimehost source) are
unaffected — they stay on Gradle 8.5 / Java 17; only the template shipped inside every *generated
app* moved.

No `npdev migrate` codemod needed — nothing here requires rewriting an existing config to keep
working.

## 2026-08-07 — `config.json` gains an optional `build` block (deps-and-java/PLAN.md, per-app Java level + declared dependencies)

**Not a breaking change — added, not modified.** `config.json`'s new `build.javaVersion` (originally
17 or 21, default 17 — see the entry above for the same-day widening) and
`build.repositories[]`/`build.dependencies[]` are all optional; an app with no `build` block
generates and behaves exactly as before this change. No `npdev migrate` codemod needed — the
stability policy's codemod rule is for changes that require rewriting an EXISTING model/config to
keep working, and nothing here does.

## 2026-08-03 — `npdev migrate bounded-contexts` codemod; ADR-0011 D4 gap fixed (S3, docs/adr/ADR-0011-bounded-contexts.md addendum)

**Not a breaking change to any existing model — stated plainly, not overstated.** `contexts[]`
(S2, 2026-08-03) was already optional and backward-compatible; this entry is about the codemod that
now exists for authors who want to *adopt* it, plus a real bug fix underneath it.

**The codemod:** `npdev migrate bounded-contexts --input <definition-dir> [--write]`
(`NPDevCli/dsl_v2_migration_bounded_contexts.py`) wraps a model's whole authored content into one new
context. Dry-run by default. It physically relocates any `$ref`-referenced concept/plugin/fragment
files into a `contexts/<name>/` subtree that mirrors their original relative layout — every `$ref`
string stays byte-identical — rather than rewriting paths with `../`, which `model.schema.json`'s
`localModelRef` pattern forbids outright (a corrected premise from the drafting spec, not a design
choice with alternatives; see the ADR addendum for the full reasoning).

**The bug fix, found by running the codemod against real content:** ADR-0011's D4 ("no physical table
prefixing") was accepted but never implemented — a context-qualified concept's table was silently
prefixed exactly like a pack-qualified one (`SqlIdentifierSupport.toSnake` folds `::` into `_`
unconditionally). Fixed in `ModelCompiler.tableNameSource`, gated on the model's own declared
`contexts[]` names so pack-table-prefixing is completely unaffected. A second, smaller gap
(`flowStep.scope` never qualified alongside its concept) was fixed alongside it in
`ModelSourceResolver`. Both are live-proven on a WmsOffice scratch-copy trial
(`__OutsideRepo/s3/wmsoffice-migration-trial-evidence.txt`) — table names, DB schema, and generic-CRUD
REST routes are identical before/after a real migration; only concept identity and the generated Java
class name change, D1's intended qualified-identity consequence.

**No corpus-wide `npdev migrate` sweep** — `contexts[]` stays optional indefinitely (§0 of
`S3_SPEC.md`, confirmed). `AppGen/apps/pack-sample` was migrated for real (the only corpus model
combining a concept `$ref` and a pack `$ref`); every other corpus model, including live WmsOffice, is
untouched — the trial proved the codemod safe, it does not by itself make migrating WmsOffice useful,
and the owner's call was not to.

## 2026-08-02 — built-in `workspace` pack: `Preference` concept retired in favor of `PropertyValue` (RC-A2, Move 14 Phase B item B1)

`Preference(id, userId, category, prefKey, prefValue)` is replaced by
`PropertyValue(id, scopeType, scopeId, propKey, propValue)`, with a new unique index
`(tenant_id, scopeType, scopeId, propKey)` (`tenant_id` implicit, generator-injected on every
composite unique like all business tables). This is the storage layer for the scoped-property
cascade RC-A1 already declared in the DSL (`properties[]`/`propertyScopes[]`, Wave 6) but had
nothing to resolve against yet.

**Why the shape had to change, not just the name:** `Preference`'s `category`/`userId` pair could
not express the cascade's core rule — row presence is the is-set signal (a row with
`propValue = NULL` means explicitly set to null at that scope; no row at all means inherit from the
next-least-specific scope) — because nothing distinguished "this scope never declared an opinion"
from "this scope explicitly declared no value." `scopeType`/`scopeId` name an arbitrary declared
`propertyScopes[].name` and its resolved instance id directly, which is what RC-A3's resolver
(`PropertyResolver.resolve()`/`.explain()`, not yet built — next item) needs to walk the cascade
correctly.

**No `npdev migrate` codemod, deliberately** — same posture as the 2026-07-28 aggregate-boundary
entry below: there is nothing to mechanically rewrite because there are no witnesses. Measured, not
assumed, before writing this entry (Move 14 Phase B item B0, `__OutsideRepo/move13-helpers/
rc-a2-row-count-evidence-2026-08-02.txt`): zero corpus models (`AppGen/apps/**`, `NPDevSamples/**`)
declare `"Preference"` anywhere, and a live row count against every H2 database that actually
realizes the table (`wmsoffice`, plus a leftover `reg39-healthy-control` REG-39 fixture) returned 0
rows in both. `Preference` was realized as a table purely because the built-in `workspace` pack
declared it and `WmsOffice` includes that pack — nothing ever read or wrote it (no resolver existed
to). The next boot of any app including the `workspace` pack will see the old `workspace_preferences`
table as an orphaned/destructive schema diff through the existing schema-lifecycle acknowledgment
mechanism (LNCH-1 P6) — expected and correct, not a gap this entry needs to paper over.

**Swept:** the one private copy of the `workspace` pack (`AppGen/apps/_official/WmsOffice/
definition/packs/workspace/pack.json`, confirmed byte-identical to the built-in before this change —
Move 13's REG-39 drift hazard needs multiple copies and/or existing drift, neither present) was
updated identically in the same commit; `rc-a2-preflight.py`'s private-copy comparison confirms
`[IDENTICAL]` again after the sweep.

## 2026-08-01 — `queries[].where` grammar now accepts `:name` bind placeholders bound against a declared `parameters[]` (REG-101, Move 12 P1.4)

Widens, not breaks, the LC-P0 grammar directly below: a `:name` literal (previously always refused
as "neither a quoted string, a number, nor a boolean") now parses as a bind placeholder, resolved
against the query's declared `parameters[]` at compile time and against a caller-supplied value map
at runtime (`ConceptQueryPredicateCompiler.compile(where, parameters, boundParameters)`). An
unbound or undeclared placeholder is still refused by name (X0), never defaulted. No existing valid
`where` stops compiling — every accepted-before shape is still accepted — so no `npdev migrate`
codemod is needed; only new grammar became legal.

The grammar itself moved to `NPDevContract/dsl` (`com.npdev.dsl.v1.query.QueryPredicateGrammar`) so
`PackValidation.validateQueries` can refuse an uncompilable `where` at AUTHORING time, not just at
runtime — the durable fix REG-101's own detail asked for. `scripts/quality/check-query-predicate-compilable.py`
(the Python reimplementation of the same grammar, AI-knowledge gate step 22) and
`scripts/quality/query-predicate-allowlist.json` are both **deleted**: the corpus-wide check that
script existed for is now done by the real Java validator via `scripts/quality/validate-corpus.py`,
which already runs `SemanticValidator` over every corpus model.

`pack-sample`'s `SalesByStore` (`where: "storeId == :storeId"`, REG-101's own witness, filed
2026-07-31) is the proof: it now compiles clean and, once bound, returns exactly the matching
store's rows — proven live in
`ConceptQueryPredicateCompilerParameterSubstitutionTest`. REG-101 → DONE.

## 2026-07-31 — a declared `queries[].where` the engine cannot compile is now an ERROR, not silently unenforced (LC-P0)

`ConceptQueryFilterSupport` used to hand-parse a `where` with `indexOf("==")` and, per its own
javadoc, leave "a clause outside this shape … unenforced (rows pass through unfiltered)". It now
compiles the predicate with `ConceptQueryPredicateCompiler` and throws
`QUERY_PREDICATE_UNSUPPORTED` (a named `UnsupportedPredicateException`) for anything outside:

```
where   := clause ( "&&" clause )*
clause  := field op literal        op := == | != | >= | <= | > | <
literal := 'text' | number | true | false
```

**What now works that never did:** multi-clause `&&`, the ordered comparisons (`> >= < <=`), a
literal containing `&&`, and `>=` not being mis-read as `>`.

**What now fails loudly that used to return a wrong answer silently:** `||`, `in (...)`, functions
(`upper(x) == …`), nested paths (`a.b == …`), unquoted non-numeric literals, and unsubstituted
`$`/`:` references.

**There is no `npdev migrate` codemod, deliberately, and this is the one entry here without one.**
A codemod rewrites a declaration whose meaning is known; these declarations never had a working
meaning — the engine was ignoring them, over-filtering them to zero rows, or inverting them. There
is no correct automatic rewrite for "your filter never worked"; the author has to say what they
meant. What ships instead is a **detector**:
`scripts/quality/check-query-predicate-compilable.py` (AI-knowledge gate step 22) fails on any
corpus `where` that will now be refused, so this is found by a gate rather than by a running app.
(**Superseded 2026-08-01** — see the entry above this one: the detector and its allowlist are both
deleted, their job now done by the real Java validator at authoring time.)

Its first run found one: `pack-sample`'s `SalesByStore` declares `where: "storeId == :storeId"`
with a matching `parameters[]` entry that **nothing substitutes** — so that query has returned zero
rows for its whole life. Filed as **REG-101**, closed 2026-08-01.

Three prior behaviours are pinned as a before/after table in
`ConceptQueryFilterSupportRedTest`, including the one the finding itself got wrong: a 2-clause
`AND` returned **zero** rows, not "every row".

## Removal trigger (not yet a breaking change): the six retired `transaction.metadata` keys

`recompute`, `derived`, `computed`, `actions`, `visibleWhen`, and `bandPickers` under
`autoPanel.transaction.metadata` (retired below in favor of their typed replacements) now all emit
a deprecation WARNING when present (`PanelValidation`, Move 8 item G4) but still work as a
fallback — no removal date is set, since dates rot. **Trigger:** these six untyped keys are removed
entirely in the next breaking DSL change, whichever that turns out to be; when that change lands,
add the actual removal as its own dated entry here and extend `npdev migrate dsl-2` to reject
(not just rewrite) them. The corpus (`AppGen/apps` + `NPDevSamples`) is confirmed clean of all six
today.

## 2026-07-30 — Aggregate Workbench: `transaction.metadata.actions`/`.visibleWhen`/`.bandPickers` retired in favor of typed `transaction.actions`/`.visibleWhen`/`.bandPickers`

`autoPanel.transaction.metadata.actions` (a list of `{label?, procedure, inputFields?, applyTo?,
afterAction?, visibleWhen?}`), `.metadata.visibleWhen` (an object keyed by collection/band name, a
predicate string), and `.metadata.bandPickers` (an object keyed by band name, `{panel, label?,
columns?}`) are retired in favor of the typed, schema-validated `transaction.actions`/
`.visibleWhen`/`.bandPickers` — same shapes, now with `additionalProperties: false` so a typo'd key
(e.g. `actons`) fails at schema time instead of silently doing nothing. Both old keys still work
for this release (every read site in `AutoPanelExpander` accepts them as a fallback when the typed
slot is absent) — but new authoring should use the typed spelling; the fallback is expected to be
removed in a future release. When both a typed and untyped spelling are declared on the same
surface, the typed one wins entirely (it is not merged with the untyped list/map), matching the
precedent Move 6 set for `hooks`/`derivedFields`.

**Why:** docs/MOVE7_IMPLEMENTATION_SPEC.md W1 — the last three untyped `transaction.metadata` keys
left over after Move 6 typed `hooks`/`derivedFields`/`regions`. `transaction.actions[].procedure`
and `.afterAction` now also get real semantic validation (must name a declared procedure); a
`visibleWhen`/`bandPickers` key must name a real address/band derived from the aggregate's own
composition tree — the same class of check Move 6 already added for `transaction.regions`.

**Codemod:** `npdev migrate dsl-2 --input <path...> [--write]` (dry-run by default) now also
rewrites `transaction.metadata.actions` → `transaction.actions`, `.metadata.visibleWhen` →
`.visibleWhen`, and `.metadata.bandPickers` → `.bandPickers`, idempotently, dropping only the
malformed sub-fields (an unusable `applyTo`, a missing `procedure`/`panel`, a blank predicate) the
compiler always silently tolerated anyway, and reporting (not guessing) when both an old and new
spelling are present. See `NPDevCli/dsl_v2_migration.py`'s `_migrate_transaction_actions` /
`_migrate_transaction_visible_when` / `_migrate_transaction_band_pickers`.

**Migrated in this change:** no git-tracked corpus model declared `metadata.actions`,
`.visibleWhen`, or `.bandPickers` before this (all three were zero-witness in the tracked corpus;
`dsl-conformance-max` gains the first typed witness alongside this change).

## 2026-07-30 — Aggregate Workbench: `transaction.metadata.recompute`/`.derived` retired in favor of typed `transaction.hooks`/`.derivedFields`

`autoPanel.transaction.metadata.recompute` (a bare procedure name, or `{procedure}`) and
`.metadata.derived` (a list of `{name, expression, label?}`) are retired in favor of the typed,
closed-enum `transaction.hooks.onFieldChange` and the object-keyed `transaction.derivedFields`
(which also gains a `tier: "server"` option `.derived` never had). Both old keys still work for
this release — every read site accepts them as a fallback and `SemanticValidator` emits a
deprecation warning, not an error, when it sees either — but new authoring should use the typed
spelling; the fallback is expected to be removed in a future release.

Also new, additive (no retirement): `transaction.hooks.onLoad`/`.beforeAction` (no prior
untyped equivalent existed), `transaction.hooks.onValidate`/`.onCommit` (an alternate spelling of
the pre-existing `aggregate.onValidate`/`.onCommit` fields — a direct aggregate-level declaration
always wins if both are present), and a per-action `afterAction` (declared alongside, not instead
of, the pre-existing per-action `applyTo`, which it subsumes going forward but does not retire).

**Why:** docs/MOVE6_TYPED_SURFACE_PLAN.md §B — the same feature was typed when it attached to
`panelAction`/`procedure`/`flow`/`aggregate`, and untyped when it attached to
`autoPanel.transaction.metadata`, purely because of which object it happened to land on. A closed
`hooks` enum means an author's typo (e.g. `onRowLoad` for `onLoad`) fails at schema time instead of
silently doing nothing.

**Codemod:** `npdev migrate dsl-2 --input <path...> [--write]` (dry-run by default) now also
rewrites `transaction.metadata.recompute` → `transaction.hooks.onFieldChange` and
`transaction.metadata.derived` → `transaction.derivedFields`, idempotently, reporting (not
guessing) when both an old and new spelling are present with different values. `applyTo` →
`afterAction` is NOT migrated automatically — `afterAction` needs a real procedure written to
receive `{draft, result}`, which is an authoring decision, not a mechanical rewrite. See
`NPDevCli/dsl_v2_migration.py`'s `_migrate_autopanel`.

**Migrated in this change:** `NPDevSamples/dsl-conformance-max` (its only `transaction.metadata
.derived` witness); no other corpus model declared `recompute` or `derived` before this.

## 2026-07-28 — Aggregate transactional boundary enforced: a flow may not write two aggregates

A flow whose `createConcept`/`updateConcept`/`createEntity`/`updateEntity` steps write to concepts
owned by two DIFFERENT declared `aggregates[]` now fails semantic validation (previously silent —
`aggregates` carried `ownership` but nothing enforced it, so the construct was descriptive, not
load-bearing). DDD's core rule: one aggregate = one transaction = one consistency boundary. A
`referenced` (not `owned`) collection is unaffected — that is a normal cross-aggregate pointer, not
a boundary the rule cares about.

**Why:** docs/NEXT_EXECUTION_PLAN.md P6.1 (3.7). Cheap to enforce once written, and the exact class
of "the model says one thing, the runtime does another" gap this repo's own register keeps finding
(REG-52/53-shaped).

**No codemod, deliberately:** unlike a syntax/vocabulary rename, there is nothing safe to
mechanically rewrite here — splitting a boundary-crossing flow into two flows coordinated by a
domain event is a real design decision only the model's author can make correctly (same "refuse
rather than guess" posture as B1's no-automatic-rename-inference boundary in
`docs/ACCEPTED_BOUNDARIES.md`).

**Corpus impact, checked not assumed:** 0 — every git-tracked model in this repo (`NPDevSamples/**`,
`NPDevContract/dsl/resources/Models/**`, `NPDevGenerator/resources/Models/**`, test fixtures, full
`:dsl:test`/`:generator:test`/`:generator:behaviorTest` suites) and WmsOffice's real, currently
deployed model (`AppGen/apps/_official/WmsOffice`, validated directly via
`:NPDevContract:dsl:validateModel`) all pass with zero aggregate-boundary diagnostics. If your own
model trips this and needs a real fix: split the flow at the aggregate boundary, using an
`emitEvent` step in the first flow and an `orchestrationRules` trigger to start the second.

## 2026-07-27 — DSL 2.0: flowStep vocabulary narrowed to 12 canonical names

**"DSL 2.0" names this vocabulary-narrowing milestone, not a value you write anywhere.** It is
unrelated to the `dslVersion` field every model declares (`ModelAst.DEFAULT_DSL_VERSION`), which
stays `"1.0.0"` and means *model-format* version -- `dslVersion` has never changed and this change
does not bump it. Writing `"dslVersion": "2.0.0"` in a model is a mistake, not an upgrade; the
schema rejects it (`/dslVersion const: must be equal to constant`, or -- with `npdev validate
model`'s default semantic check -- the clearer `Unsupported dslVersion '2.0.0'. Supported value:
"1.0.0".`). If you're migrating a model to the new flowStep vocabulary, run `npdev migrate dsl-2`
below; don't touch `dslVersion`.

`model.schema.json`'s `flowStep.type` enum dropped from 23 accepted spellings to 12
(`invariantCheck`, `capabilityCall`, `generatedAction`, `emitEvent`, `scheduleEvent`, `return`,
`branch`, `awaitEvent`, `createConcept`, `updateConcept`, `map`, `forEach` — the camelCase of the
`FlowStepDefinition.Type` runtime enum, so a reader who sees a name in JSON needs no translation
table to find it in Java). Retired spellings: `validate`/`invariant`/`enforceInvariants`/
`evaluateInvariant`, `capability`/`callCapability`, `event`, `if`, `await`/`waitForEvent`/
`await_event`, `assign`, `loop`, `generated_action`, `createEntity`/`conceptCreate`,
`updateEntity`/`conceptUpdate`. Field aliases `cap`/`op`/`out`/`at`/`target` (on `flowHook`)/
`targetConcept`/`capabilityName`/`eventName`/`fieldMap` are also retired in favor of their longer,
unambiguous names; `orchestrationRule`'s scalar `action` is retired in favor of the always-a-list
`actions`.

**Why:** the alias vocabulary was 61% redundant relative to the 9 real runtime behaviors, and every
extra spelling was a way for an LLM authoring a model to produce an inconsistent one — the single
largest source of avoidable model variance in the AI-authoring path. Full rationale, corpus
measurements, and the naming decision: `docs/DSL2_AND_DECOMPOSITION_PLAN.md` §2.A.

**Codemod:** `npdev migrate dsl-2 --input <path...> [--write]` (dry-run by default). Structural,
idempotent, and refuses to touch anything it detects as a serialized compiled-model fixture rather
than an authored document. See `NPDevCli/dsl_v2_migration.py`'s module docstring for the full
design.

**Migrated in this change:** every git-tracked model in this repo (`NPDevSamples/**`,
`NPDevContract/dsl/resources/Models/**`, `NPDevGenerator/resources/Models/**`, test fixtures).
**Not yet migrated:** `AppGen/apps/**` — a non-git external directory, deliberately excluded from
this pass; run the same codemod there whenever that's reviewed directly.
