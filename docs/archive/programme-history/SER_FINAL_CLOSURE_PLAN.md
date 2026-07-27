# Schema Engine — Final Closure Plan (ALL remaining open items)

> **STATUS: EXECUTED (2026-07-25).** Groups A/B/C all closed (G1-G12). Kept as a record.


> **Written:** 2026-07-25 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
> **HEAD when written:** `fce3eb1` (finding-#1 fix)
> **Audience:** an AI executor with limited autonomy. **Your job is execution, not investigation.**
> Every fact below was verified against the live tree on 2026-07-25. If reality does not match this
> document → **STOP and report** (§0.6). Do not improvise.
>
> **Supersedes:** `docs/REMAINING_GAPS_CLOSURE_PLAN.md` (untracked, dated 2026-07-22, targets REG-6
> "as re-scoped" + REG-17 — REG-6 is now CLOSED FULLY, so that document is stale). Task G12 deletes it.

---

## 0. Global rules — READ FIRST, they apply to every task

**0.1 Shell.** Git Bash for the commands as written. Repo root is
`D:\WorkSpace\NPDev\NPDev_General` (in Git Bash: `/d/WorkSpace/NPDev/NPDev_General`).

**0.2 The gates** (memorize; every task names which to run):

```bash
# Re-materialize the build file after ANY edit to build.gradle.template (ALWAYS do this first):
cd /d/WorkSpace/NPDev/NPDev_General && cp NPDevRuntimeHost/build.gradle.template NPDevRuntimeHost/build.gradle

# GATE-H2 (fast, ~30s)
cd /d/WorkSpace/NPDev/NPDev_General/NPDevRuntimeHost && ./gradlew test \
  --tests "com.finalexec.db.*" --tests "com.finalexec.controlpanel.*" \
  -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs

# GATE-PG (needs Docker Desktop RUNNING; ~2 min)
cd /d/WorkSpace/NPDev/NPDev_General/NPDevRuntimeHost && ./gradlew test \
  --tests "com.finalexec.db.SchemaLifecycleExecutor*" --tests "com.finalexec.db.ConversionHookRunnerPostgresTest" \
  --tests "com.finalexec.db.*PostgresProofMatrixTest" -PincludePostgresMatrix \
  -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs

# GATE-GEN (generator; ~1-3 min)
cd /d/WorkSpace/NPDev/NPDev_General/NPDevGenerator && ./gradlew :generator:test
```

**0.3 Pre-commit hygiene** (the slimness hook BLOCKS commits otherwise — tests write into `runtime-data/`):

```bash
cd /d/WorkSpace/NPDev/NPDev_General
rm -rf NPDevRuntimeHost/runtime-data
pwsh -File scripts/hygiene/clean-workspace-state.ps1
```

**0.4 Commit rules.** **NEVER `git add .`** — stage by explicit path. Never push, never merge, never
checkout another branch. One task = one commit. Last line of every commit body:
`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

**0.5 Editing rules.** **Never regex-patch Java.** Use exact-string anchored edits; if a find-target is
not unique, include more surrounding lines. Never edit anything under `D:\WorkSpace\NPDev\Build` or any
`npdev-generated` folder. Evidence/scratch → `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`.

**0.6 STOP-and-report conditions.** Stop immediately, do not "fix around it", if:
- a gate is red and the fix is not obviously a mistake you just made;
- a documented fact here is false in the code;
- **G2 (rule-6 sign-off) is not yet granted and you are about to touch conversion-hook behavior;**
- a task's "Expected" output does not match.

**0.7 Key package-access facts** (these bite):
- `ShadowParityProbe.scopeToOwnedBusinessTables`, `SchemaDeltaReport`, `ImpactReportWriter` are
  **package-private in `com.finalexec.db`** — a class in another package CANNOT call them.
- `DesiredSchemaFactory` **lower-cases** all table/column names; manifest lists are model-case →
  compare case-insensitively (`containsIgnoreCase` + `resolveModelTable`/`resolveModelColumn` already
  exist in `SchemaLifecycleExecutor`).
- A destructive diff item's `itemKey()` **is** its `SchemaDeltaItem.stableString()` → it feeds the
  acknowledgment token. **Never reformat item keys.**

---

## 1. THE GAP TABLE (all open items, 2026-07-25)

### Group A — Conversion hooks / Phase 7 (the risk concentration)

| ID | Gap | Sev | Effort | Task |
|---|---|---|---|---|
| **G1** | **P7.7 live proof missing** — hooks are never proven inside a real packaged boot jar. Runtime loads them via `classpath*:db/conversion-hooks/*/hook.json` + `Resource.createRelative("convert.sql")` (`ConversionHookRunner:267,300`); all tests run off a **directory** classpath, so jar-URL sibling resolution is **unverified**. | **HIGH** | M | §2.1 |
| **G2** | **rule-6 owner sign-off never granted** — Phase 7 ships the ability to destroy data with **no acknowledgment token**. The plan gated this on explicit owner approval; it was built anyway. | **HIGH (governance)** | S | §2.2 |
| **G3** | **Backfill-hook path proven only in isolation** — `rule6_hookResolvesTheNeedsHookItem` calls `ConversionHookRunner.run(...)` directly; the only *end-to-end* `beforeMigrate` proofs are destructive-drops. | MED | S | §2.3 |
| **G4** | **Naive SQL splitter** — `splitStatements` (`ConversionHookRunner:~357`) is single-quote aware only: no `--` / `/* */` comments, no `$$` dollar-quoting. A `;` inside a comment splits wrongly. | MED-LOW | S/M | §2.4 |
| **G5** | **Per-hook independent transactions** — hook 2 failing leaves hook 1 committed; a re-boot may re-run a non-idempotent hook 1. By design (rule 3) but unenforced and undocumented for operators. | LOW | S | §2.5 |
| **G6** | **H2 has no transactional DDL** — a failed `verifySql` rolls back DML but not DDL on H2 (Postgres is fine). Already fixed as far as possible + documented (`fce3eb1`); remaining work is a **hard guard**, not a fix. | LOW | S | §2.6 |
| **G7** | **Two engine-detection paths** — runner uses `getDatabaseProductName()`, the rest of the executor uses `manifest.engine()`. Harmless today, drift-prone. | LOW | S | §2.7 |

### Group B — Schema engine, known deferrals

| ID | Gap | Sev | Effort | Task |
|---|---|---|---|---|
| **G8** | **FK/index diffing deferred (P0.2/P5.2)** — the manifest carries **no explicit FK or index lists**, so `SchemaDiffEngine` models columns/types/nullability/defaults/uniques/renames only. A live FK/index change is invisible to the diff (and is a documented shadow-parity skip). | **MED** | L | §3.1 |

### Group C — Documentation & process integrity

| ID | Gap | Sev | Effort | Task |
|---|---|---|---|---|
| **G9** | **Register summary table is STALE** — the index at `NPDEV_OPEN_ITEMS_REGISTER.md` lines ~118–134 lists **REG-1, REG-2, REG-3, REG-4, REG-5, REG-9** as `GAP`, but each item's own §-section says **CLOSED**. Anyone reading the index gets a false picture of project state. | **MED** | S | §4.1 |
| **G10** | **REG-16 adversarial review — TIER A COMPLETE only**; the remaining tiers of the ~23 launch items are unreviewed. | HIGH | L | §4.2 |
| **G11** | **REG-17 third-party reproduction — PARTIAL** (2/4 gates run by an independent tester). | MED | M | §4.3 |
| **G12** | **Stale `docs/REMAINING_GAPS_CLOSURE_PLAN.md`** (untracked, superseded by this document). | LOW | XS | §4.4 |

> **Not gaps (verified CLOSED, do NOT re-open):** REG-1, REG-2, REG-3, REG-4, REG-5, REG-6, REG-9,
> REG-25, REG-38, REG-40. REG-7 and REG-8 are **BOUNDARY** items (deliberately converted to a
> feature / closed by refusal) — leave them alone.

---

## 2. GROUP A — Conversion hooks

### §2.1 · G1 — Live proof that hooks work inside a real packaged jar  ⟨HIGHEST VALUE⟩

**Why this matters.** `PathMatchingResourcePatternResolver.getResources("classpath*:db/conversion-hooks/*/hook.json")`
finds hooks, then `createRelative("convert.sql")` fetches the sibling. Inside a boot jar, resources are
`jar:file:...!/BOOT-INF/classes/...` URLs. If `createRelative` mis-resolves there, **every hook silently
has `sql == null`** → `ConversionHookRunner` throws "no convert SQL available for engine" and **refuses
every boot that has an unresolved diff**. That is a production-breaking failure mode that no current test
can see.

**Step 1 — cheap unit-level jar proof (do this FIRST; it is 80% of the value for 20% of the work).**

Create `NPDevRuntimeHost/src/test/java/com/finalexec/db/ConversionHookJarLoadingTest.java`. It must:
1. Build a temporary `.jar` **at test runtime** (use `java.util.jar.JarOutputStream` into a
   `@TempDir` file) containing exactly:
   - `db/conversion-hooks/jartest-hook/hook.json` (valid: `id`, non-empty `claims`, no `verifySql`)
   - `db/conversion-hooks/jartest-hook/convert.sql` (any single statement, e.g. `SELECT 1;`)
2. Open a `URLClassLoader` over that jar.
3. Call `ConversionHookRunner.loadClaimIndex()` **with that loader as the thread context class loader**
   (`Thread.currentThread().setContextClassLoader(jarLoader)` in a try/finally that restores the original).
4. Assert the claim from the jar's `hook.json` **is present in the returned index** — this proves
   `getResources` found it inside a jar.
5. **The critical assertion:** prove the SIBLING `convert.sql` was read. `loadClaimIndex()` does not
   expose SQL, so add a package-private test seam to `ConversionHookRunner`:
   ```java
   /** Test-only seam (G1): the number of loaded hooks whose common convert SQL resolved to non-null.
    *  Proves sibling resolution works on whatever classpath layout is in effect (dir vs jar). */
   static long loadedHooksWithConvertSqlCount() {
       return loadHooks().stream().filter(h -> h.commonSql() != null && !h.commonSql().isBlank()).count();
   }
   ```
   Assert it is `>= 1` under the jar loader.

**If the assertion FAILS** (sibling not resolvable in a jar) — that is the bug this task exists to find.
Fix it by replacing `createRelative` with a path-derived classpath lookup, e.g.:
```java
// Derive the sibling from the hook.json resource's own classpath path instead of URL-relative
// resolution, which is not reliable for jar: URLs.
String hookPath = "db/conversion-hooks/" + folderName + "/" + name;   // folderName from the hook.json URL
InputStream in = ConversionHookRunner.class.getClassLoader().getResourceAsStream(hookPath);
```
(Derive `folderName` by parsing the hook.json resource URL's parent segment.) Then re-run until green.

**Step 2 — the real end-to-end app proof (P7.7).**
Only after Step 1 is green. Use a **scratch copy** of an app under `D:\WorkSpace\NPDev\Build` —
**NEVER the live WmsOffice DB.**
1. Pick the smallest AppGen app that builds cleanly. Read `scripts/appgen/Build-NpdevApp.ps1 -?` for usage.
2. In its layer-2 definition (`D:\WorkSpace\NPDev\AppGen\apps\<app>\definition\`), create
   `migrations/1-split-demo/hook.json` + `convert.sql` performing a real split-column conversion
   (add two columns, copy data, drop the old column) with a `verifySql`.
   **Remember: layer-2 app definitions are NOT committed to this repo.**
3. Build the app, boot it against a scratch DB with existing rows, and confirm: the hook ran, the data
   is correct, no acknowledgment token was needed, and `npdev_schema_history` has `HOOK_APPLIED`.
4. Evidence (console log + a `SELECT` proving the data survived) →
   `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\schema-engine-rebuild\P7.7-live-proof\`.

**Gates:** GATE-H2 + GATE-PG. **DoD:** the jar test is green (and any resolution bug is fixed); the live
proof evidence file exists. **Commit:** `test(SER-P7.7): prove conversion hooks load + run from a packaged jar`
(plus a `fix(SER-P7)` commit first if Step 1 found a real bug).

---

### §2.2 · G2 — rule-6 owner sign-off  ⟨BLOCKING GOVERNANCE — DO THIS BEFORE ANY OTHER GROUP-A TASK⟩

**Do NOT write code for this task.** Produce a decision brief and STOP for the owner.

Create `docs/adr/ADR-0005-sanctioned-destruction-conversion-hooks.md` containing:
1. **The rule, stated plainly:** "A destructive schema item that an operator's conversion hook resolves
   requires NO acknowledgment token — authoring the hook IS the acknowledgment."
2. **What it authorizes:** a `convert.sql` may drop columns/tables and delete data; the boot proceeds
   with no token, provided the post-hook re-diff confirms the claimed item is gone (rule 5).
3. **What it does NOT authorize:** any item **no** hook claims — still token-gated exactly as before.
4. **The safety net that remains:** rule 4 (verify, now inside the hook transaction), rule 5 (re-diff —
   claims are verified, never trusted), rule 7 (every step written to `npdev_schema_history`), and
   generation-time schema validation of `hook.json`.
5. **The residual risks, honestly:** the H2 DDL caveat (G6); a hook is only as safe as the SQL an
   operator wrote; hooks are per-transaction, not globally atomic (G5).
6. **Decision block** — left blank for the owner:
   `Decision: [ ] APPROVED  [ ] APPROVED WITH CONDITIONS  [ ] REJECTED   Owner: ______  Date: ______`

**Then STOP and report to the owner.** If the owner has NOT approved, Phase 7 must be labelled
experimental: add to `docs/IMPACT_REPORTS.md` under the conversion-hooks heading:
`> **Status: EXPERIMENTAL** — pending owner sign-off on sanctioned destruction (ADR-0005). Not recommended for production data.`

**Commit:** `docs(ADR-0005): sanctioned-destruction decision brief for owner sign-off`.

---

### §2.3 · G3 — Prove a backfill hook end-to-end through `beforeMigrate`

Add ONE test to
`NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorConversionHookIntegrationTest.java`,
modelled **exactly** on the existing `hookResolvedDestructiveDropNeedsNoAcknowledgmentTokenAndBootSucceeds`
(copy its structure, manifest shape, and `SingleConnectionUrlDataSource`).

- New fixture: `NPDevRuntimeHost/src/test/resources/db/conversion-hooks/p73-backfill/` with
  `hook.json` claiming `ADD_REQUIRED_COLUMN:p73_backfill:status` and a `convert.sql` that
  `ALTER TABLE p73_backfill ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'legacy';`
- Test: create `p73_backfill (id BIGINT PRIMARY KEY)` + a row; seed fingerprint `sha256:old`; build a
  manifest where `status` is **required and NOT additive-eligible** (`businessTableAdditiveColumns` =
  empty for that table, `businessTableRequiredColumns` = `["status"]`) so it classifies as reaching the
  hook runner; call `executor.beforeMigrate(...)`; assert **no exception**, the column exists, and the
  pre-existing row survived with `status='legacy'`.

**Gates:** GATE-H2. **DoD:** the new test is green and genuinely exercises `beforeMigrate` (not `run()`).
**Commit:** `test(SER-P7.5): prove a backfill conversion hook resolves end-to-end through beforeMigrate`.

---

### §2.4 · G4 — Harden the SQL statement splitter

Edit `splitStatements` in `NPDevRuntimeHost/src/main/java/com/finalexec/db/ConversionHookRunner.java`.
Keep the existing single-quote handling and ADD, in the same character scan:
- `--` line comment → skip to end of line (do not emit, do not split on `;` inside it);
- `/* ... */` block comment → skip to the closing `*/`;
- `"` double-quoted identifier → treat like a quoted region (no splitting inside);
- `$$ ... $$` / `$tag$ ... $tag$` dollar-quoting (Postgres) → no splitting inside.

Keep it a single-pass state machine with an explicit state variable; do **not** pull in a SQL parser
dependency.

**RED-first test:** new `ConversionHookSqlSplitterTest` (same package, `com.finalexec.db`) — the splitter
is `private`, so add a package-private test seam:
`static List<String> splitStatementsForTest(String sql) { return splitStatements(sql); }`
Cases: `;` inside `--` comment; `;` inside `/* */`; `;` inside a single-quoted literal (must still work);
`;` inside `"quoted;ident"`; `;` inside `$$...;...$$`; two ordinary statements still split into two.

**Gates:** GATE-H2 + GATE-PG. **DoD:** all splitter cases green, existing hook tests unaffected.
**Commit:** `fix(SER-P7): comment/quote-aware conversion-hook SQL splitter`.

---

### §2.5 · G5 — Document + log the per-hook transaction boundary

No behavior change. Two edits:
1. **Operator docs** — `docs/IMPACT_REPORTS.md`, under the conversion-hooks refusal list, add:
   > **⚠ Hooks are individually atomic, not collectively atomic.** Each hook runs in its own
   > transaction. If hook 2 fails, hook 1 stays committed and the boot refuses; the next boot re-runs
   > only what the diff still says is unresolved. **Write every hook to be idempotent** (`ADD COLUMN
   > IF NOT EXISTS`, `UPDATE ... WHERE <not-yet-converted>`), because a hook may run again after a
   > later hook failed.
2. **Runtime log** — in `ConversionHookRunner.run(...)`, when `selected.size() > 1`, print once before
   the loop:
   `System.out.println("NPDev schema lifecycle: running " + selected.size() + " conversion hooks in separate transactions -- each hook must be idempotent (a later hook failing does not roll back an earlier one).");`

**Gates:** GATE-H2. **Commit:** `docs(SER-P7): make the per-hook transaction boundary explicit for operators`.

---

### §2.6 · G6 — Guard the H2 non-transactional-DDL caveat

The limitation is already fixed-as-far-as-possible and documented (`fce3eb1`). Add a **detection guard**
so an operator is warned at the moment it matters. In `ConversionHookRunner.run(...)`, before executing a
hook, if `engine` is `h2` **and** the hook has a non-blank `verifySql` **and** its SQL matches
`(?is).*\b(ALTER|DROP|CREATE)\s+TABLE\b.*`, print:
```
NPDev schema lifecycle: WARNING -- conversion hook '<id>' mixes DDL with a verifySql on H2. H2 has no
transactional DDL, so if the verify fails the DDL will NOT be rolled back (data changes will be).
Split DDL and data movement into separate hooks, or run this conversion on Postgres.
```
**Gates:** GATE-H2 (confirm existing hook tests still pass; a warning line must not break assertions —
if any test asserts on exact stdout, adjust that test's assertion to `contains`, never delete it).
**Commit:** `feat(SER-P7): warn when an H2 hook mixes DDL with verifySql (non-transactional DDL)`.

---

### §2.7 · G7 — Unify engine detection

`ConversionHookRunner.detectEngine` uses JDBC `getDatabaseProductName()`; the rest of the executor uses
`manifest.engine()` (one of `InMemory`/`H2Local`/`H2Server`/`Postgres`). Change `detectEngine` to take
the manifest and prefer it, falling back to the JDBC probe only when the manifest value is null/blank:
```java
private static String detectEngine(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
    String declared = manifest == null ? null : manifest.engine();
    if (declared != null && !declared.isBlank()) {
        return declared.toLowerCase(Locale.ROOT).contains("postgres") ? "postgres" : "h2";
    }
    // ... existing JDBC probe as fallback ...
}
```
**CAUTION:** `ConversionHookRunnerPostgresTest.manifestFor(...)` declares engine `"H2Local"` while
running on real Postgres. Changing this will make that test pick `h2` — which is FINE only because those
fixtures have no engine-specific variant file. **Verify GATE-PG is still green; if it goes red, revert
this task** — it is cosmetic and not worth breaking a proof.
**Gates:** GATE-H2 + GATE-PG. **Commit:** `refactor(SER-P7): prefer the manifest's engine for hook variant selection`.

---

## 3. GROUP B — Schema-engine deferral

### §3.1 · G8 — FK/index diffing (P5.2)  ⟨LARGE — treat as its own mini-programme⟩

**Do not start this in the same session as Group A.** This is the last real engine gap: the manifest has
no FK/index lists, so `SchemaDiffEngine` cannot diff them, and `CurrentSchemaReader` already reads
`CurrentForeignKey`/`CurrentIndex` that nothing consumes.

**Phase 1 — desired side (generator).** Extend the schema-realization manifest with explicit
`businessTableForeignKeys` and `businessTableIndexes` (derived from bonds + declared indexes, which the
generator already knows — it emits the DDL for them today). Add them as NEW components; do not reorder
existing ones. **Mirror `model.schema.json` to all four copies if the authoring contract changes**
(`NPDevContract/schemas/model.schema.json`, `.../authoring/model.schema.json`,
`NPDevContract/dsl/src/main/resources/schema/model.schema.json`,
`NPDevContract/dsl/resources/Schemas/model.schema.json`). GATE-GEN.

**Phase 2 — desired records.** Add `DesiredForeignKey`/`DesiredIndex` to `com.finalexec.db.schemastate`;
populate them in `DesiredSchemaFactory`. Pure, unwired. Unit test only.

**Phase 3 — SHADOW FIRST (mandatory — this is how Phase 3 of the original rebuild succeeded).** Extend
`SchemaDiffEngine` to emit FK/index items, but run them **log-only** behind a property
(`npdev.schema.fkindex.shadow`). Drive divergences to **zero** on H2 + PG before trusting them. Expect
noise: implicitly-created indexes (PKs, unique constraints), engine-generated FK names, differing name
casing. **Do not** let the diff propose dropping an index the engine created implicitly.

**Phase 4 — switch.** Only at zero divergences: remove the "empty shadow diff (unique/FK/internal)"
skip in `ShadowParityProbe.compareAndLog` and let FK/index items count. Both gates green.

**STOP condition:** if Phase 3 cannot reach zero divergences in one session, keep it log-only, commit the
shadow, and report. **A noisy FK diff that proposes dropping real constraints is far worse than no FK diff.**

---

## 4. GROUP C — Documentation & process

### §4.1 · G9 — Fix the stale register summary table  ⟨DO THIS FIRST — 20 minutes, high clarity value⟩

`docs/NPDEV_OPEN_ITEMS_REGISTER.md` lines ~118–134 contradict the document's own detail sections.
**Verified 2026-07-25:** REG-1, REG-2, REG-3, REG-4, REG-5, REG-9 all say **CLOSED** in their §-sections
but **GAP** in the index.

For each of those six rows: change the status cell to `CLOSED`, strike the ID (`~~**REG-1**~~`) to match
the existing convention used by REG-40's row, and put the closure date + section reference in the
description cell. **Verify each one individually** by reading its §-section status line before editing —
do not bulk-edit on trust. Also update the "genuinely open or partial" prose note (~line 111) so it lists
only what is genuinely open: **REG-16** (Tier A complete), **REG-17** (partial), and the Group A/B gaps.

**DoD:** every row's index status matches its §-section status. **Commit:**
`docs(register): sync the summary index with the detail sections (6 items were CLOSED but listed GAP)`.

### §4.2 · G10 — REG-16 adversarial review (remaining tiers)
Large, process-shaped, and **not** a schema-engine task. Read §3.1 of the register for the tier
definitions, then plan Tier B as its own session. **Do not attempt inside a closure session.**

### §4.3 · G11 — REG-17 third-party reproduction
Requires an independent tester/CI run; read §3.2 of the register. **Owner-scheduled, not code work.**

### §4.4 · G12 — Delete the superseded plan
```bash
cd /d/WorkSpace/NPDev/NPDev_General && rm docs/REMAINING_GAPS_CLOSURE_PLAN.md
```
It is untracked, so nothing to commit — just confirm `git status --short` no longer lists it. If it is
tracked by the time you run this, `git rm` it and commit
`docs: remove the superseded 2026-07-22 gaps plan (see SER_FINAL_CLOSURE_PLAN.md)`.

---

## 5. THE FINAL SESSION PLAN (execution order — follow exactly)

Do them in this order. The order is chosen so a blocked task never blocks a later one.

| # | Task | Why this position | Gates | Time |
|---|---|---|---|---|
| 1 | **§4.1 G9** register sync | Pure docs, no risk, immediately fixes a false picture of project state | none | 20 min |
| 2 | **§4.4 G12** delete stale plan | Trivial cleanup, avoids future confusion | none | 2 min |
| 3 | **§2.2 G2** rule-6 ADR → **STOP** | **Governance gate.** Owner must decide before more hook work is justified. Writing the brief costs little and unblocks everything | none | 45 min |
| 4 | **§2.1 G1 Step 1** jar-loading test | **Highest technical risk.** May expose a production-breaking bug; do it before polishing anything else | H2 | 1–2 h |
| 5 | **§2.3 G3** backfill end-to-end | Small, closes a real coverage hole | H2 | 45 min |
| 6 | **§2.4 G4** splitter hardening | Real correctness fix, self-contained | H2 + PG | 1–2 h |
| 7 | **§2.5 G5** + **§2.6 G6** docs/warning | Cheap operator-safety wins; batch them | H2 | 45 min |
| 8 | **§2.7 G7** engine unification | Cosmetic; revert if PG goes red | H2 + PG | 30 min |
| 9 | **§2.1 G1 Step 2** live P7.7 proof | Needs a working app build; do last in the session | H2 + PG | 2–3 h |
| — | **§3.1 G8** FK/index | **Separate session** — mini-programme with its own shadow phase | all | 1–2 sessions |
| — | **§4.2 G10**, **§4.3 G11** | **Owner-scheduled**, not code sessions | — | — |

**Session end checklist (run all three, all must be green):**
```bash
# 1
cd /d/WorkSpace/NPDev/NPDev_General && cp NPDevRuntimeHost/build.gradle.template NPDevRuntimeHost/build.gradle
# 2 GATE-H2, 3 GATE-PG, 4 GATE-GEN  (commands in §0.2)
```
Then update the Progress Ledger row for Phase 7 in `docs/SCHEMA_ENGINE_REBUILD_PLAN.md` (§I.6) with what
landed, and append a session digest to
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\schema-engine-rebuild\`.

---

## 6. What "done" looks like

- **Group A closed** → conversion hooks are proven in a real jar and a real app, backfill+destructive
  paths both proven end-to-end, the splitter is comment-safe, operators are warned about the two real
  sharp edges (per-hook atomicity, H2 DDL), and **the owner has signed off on rule-6** (or Phase 7 is
  clearly labelled EXPERIMENTAL).
- **Group B closed** → FK/index changes are visible to the diff, proven shadow-first at zero divergences.
- **Group C closed** → the register tells the truth at a glance; REG-16/REG-17 are owner-scheduled.

Only when Group A is closed should Phase 7 lose the "experimental" label. Until G1 Step 2 (live proof)
passes, **treat conversion hooks as unproven in production**, regardless of green unit tests.
