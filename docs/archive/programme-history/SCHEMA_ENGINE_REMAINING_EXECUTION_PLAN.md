# Schema Engine — Remaining Work: Detailed Execution Plan

> **STATUS: EXECUTED (2026-07-25).** Every task landed. Superseded by `SER_FINAL_CLOSURE_PLAN.md` and then by `ONE_PLAN_CLOSE_EVERYTHING.md`; kept as a record.


**Audience:** an implementing AI/agent that will follow this literally. Be conservative, verify every
step, and do not improvise beyond what is written. When something here disagrees with the code, the code
wins — STOP and report rather than guessing.

**Context (already done, do not redo):** The REG-6 schema-engine rebuild is complete. There is ONE
canonical desired-vs-current model and every executor pass consumes it. The Impact Report *engine* and
its boot-time surface already exist:

- `com.finalexec.db.ImpactReport` (public) — verdict `NO_CHANGES|SAFE|NEEDS_ATTENTION|DESTRUCTIVE`;
  `record Item(SchemaDiffItem diffItem, long rowsAffected, String probeNote)`; `static ImpactReport
  generate(SchemaDiff diff, DataSource ds)`; `items()`, `verdict()`.
- `com.finalexec.db.ImpactReportText` (public) — `static String render(ImpactReport, String fromFp,
  String toFp, String ackToken)`.
- `com.finalexec.db.ImpactReportJson` (public) — `static String render(ImpactReport, String
  generatedAt, String fromFp, String toFp, String ackToken)`; conforms to
  `NPDevContract/schemas/impact-report.schema.json`.
- `com.finalexec.db.ImpactReportWriter` (package-private) — writes/prints on every upgrade boot.

This plan covers the remaining items **P6.4, P6.5, Phase 7, Phase 8, Phase 9**, plus a small shared
prerequisite **P6.0**. The master plan is `docs/SCHEMA_ENGINE_REBUILD_PLAN.md`; this document is the
step-by-step execution of its tail.

---

## 0. Ground rules (READ FIRST — applies to every step below)

### 0.1 Workflow discipline (non-negotiable)
- **One logical change per commit.** Both quality gates GREEN before you commit (see 0.3).
- **RED-first for new behavior:** write the test, watch it fail for the right reason, then implement.
- **Never `git add .`** — stage by explicit path only (`git add path/one path/two`).
- **Never regex-patch Java.** Use exact-string, anchored edits. If an edit target is not unique, include
  more surrounding lines until it is.
- **Do not push** to any remote. Do not open PRs. Commit locally only.
- **Evidence/scratch → `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`**. Build output →
  `D:\WorkSpace\NPDev\Build`. **Never write generated/build/evidence artifacts inside the repo.**
- **Commit message trailer** (last line of every commit body):
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

### 0.2 Package-access facts (critical — Java sub-packages get NO package access)
- `ImpactReport`, `ImpactReportText`, `ImpactReportJson`, `DesiredSchemaFactory`, `CurrentSchema`,
  `CurrentSchemaReader`, `DesiredSchema`, `SchemaDiff`, `SchemaDiffEngine`, `SchemaDiffItem`,
  `SafetyClass` are all reachable **from `com.finalexec.db`**.
- `ShadowParityProbe.scopeToOwnedBusinessTables(...)`, `SchemaDeltaReport`, and
  `SchemaLifecycleExecutor.readFingerprint(...)` are **package-private in `com.finalexec.db`**. Anything
  in another package (e.g. `com.finalexec.controlpanel`) **cannot** call them — it must go through a
  PUBLIC facade you add in `com.finalexec.db` (that is exactly what P6.0 provides).
- `SchemaLifecycleExecutor.loadManifest()` is **public static** → returns the `SchemaManifest`.
- `SchemaLifecycleExecutor.SchemaManifest` is public; useful accessors: `schemaFingerprint()`,
  `physicalDatabase()`, `externallyManaged()`, `destructiveAllowed()`, `businessTableColumns()`.

### 0.3 The quality gates (exact commands)
Run from the repo root `D:\WorkSpace\NPDev\NPDev_General` unless noted. Bash tool (Git Bash) is assumed;
adjust for PowerShell if needed.

- **Re-materialize the build file after ANY edit to `NPDevRuntimeHost/build.gradle.template`:**
  ```
  cp NPDevRuntimeHost/build.gradle.template NPDevRuntimeHost/build.gradle
  ```
- **GATE-H2** (fast, run constantly):
  ```
  cd NPDevRuntimeHost && ./gradlew test --tests "com.finalexec.db.SchemaLifecycleExecutor*" \
    -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs
  ```
  Add `--tests "com.finalexec.<YourNewTestClass>"` to include a new test while iterating.
- **GATE-PG** (cross-engine; requires Docker Desktop running; slow ~2 min):
  ```
  cd NPDevRuntimeHost && ./gradlew test --tests "com.finalexec.db.SchemaLifecycleExecutor*" \
    --tests "com.finalexec.db.*PostgresProofMatrixTest" -PincludePostgresMatrix \
    -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs
  ```
- **GATE-GEN** (only for Phase 7/9, which touch the generator):
  ```
  cd NPDevGenerator && ./gradlew :generator:test
  ```
- **Pre-commit hygiene** (the slimness hook blocks commits otherwise — tests write snapshots into
  `runtime-data/`):
  ```
  rm -rf NPDevRuntimeHost/runtime-data
  pwsh -File scripts/hygiene/clean-workspace-state.ps1
  ```
  Then `git add <paths>` and `git commit`.

### 0.4 Gotchas already learned (avoid these traps)
- `DesiredSchemaFactory` **lower-cases all table and column names.** Diff item `table()`/`column()` are
  lower-case; the manifest's lists (`businessTableRequiredColumns`, etc.) are model-case. Compare
  case-insensitively; resolve back to model-case before emitting DDL or looking up the manifest (there
  are already helpers `resolveModelTable`/`resolveModelColumn`/`containsIgnoreCase` in
  `SchemaLifecycleExecutor` — reuse the SAME approach if you need it again).
- A diff item's `itemKey()` for a destructive item IS its `SchemaDeltaItem.stableString()` verbatim —
  this is what keeps acknowledgment tokens byte-identical. Never reformat those keys.
- The runtimehost test task auto-excludes a couple of tests when the generated app mount is absent —
  that is normal, not a failure.

### 0.5 STOP-and-report conditions
Stop immediately and report to the owner if: a gate is red and you cannot make it green with a
behavior-preserving fix; a documented assumption here is false in the code; **or you reach Phase 7**
(it is gated on an explicit owner "rule-6" sign-off — see the Phase 7 header).

---

## P6.0 — Shared facade (do this FIRST; P6.4 and P6.5 both depend on it)

**Goal:** one PUBLIC entry point in `com.finalexec.db` that computes the full impact of the live database
against the current model, so both the CLI (P6.4) and the controller (P6.5) reuse identical logic and
neither needs package-private access.

**Step 1 — add a public wrapper for the stored fingerprint.** In
`NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java`, find the existing private
`readFingerprint(DataSource)` method (grep for `String readFingerprint(`). Add, right next to it:
```java
/** Public accessor for the stored schema fingerprint (SER-P6.0): lets the Impact Report facade and
 *  the ControlPanel surface read the "from" fingerprint without package-private access. Read-only. */
public static String readStoredFingerprintPublic(DataSource dataSource) {
    return readFingerprint(dataSource);
}
```
If `readFingerprint` is an instance method (not static), make the wrapper match (drop `static` and have
callers use an instance — but prefer static; check the actual signature and mirror it).

**Step 2 — create the facade.** New file
`NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaImpactFacade.java`:
```java
package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;

import javax.sql.DataSource;

/** Public facade (SER-P6.0): compute the live-database impact of the current model in one call, for the
 *  CLI (REPORT_ONLY) and ControlPanel surfaces. Read-only; never throws for a missing manifest (returns
 *  a NO_CHANGES result). */
public final class SchemaImpactFacade {

    /** The impact report plus the envelope a renderer needs. {@code ackToken} is non-null only when the
     *  verdict is DESTRUCTIVE (the token an operator must supply). */
    public record Result(ImpactReport report, String fromFingerprint, String toFingerprint, String ackToken) {
    }

    private SchemaImpactFacade() {
    }

    public static Result forLiveDatabase(DataSource dataSource) {
        SchemaLifecycleExecutor.SchemaManifest manifest = SchemaLifecycleExecutor.loadManifest();
        if (manifest == null || !manifest.physicalDatabase()) {
            return new Result(ImpactReport.generate(new SchemaDiff(java.util.List.of()), dataSource),
                    null, manifest == null ? null : manifest.schemaFingerprint(), null);
        }
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest),
                ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        ImpactReport report = ImpactReport.generate(diff, dataSource);
        String from = SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource);
        String to = manifest.schemaFingerprint();
        String ackToken = null;
        if (report.verdict() == ImpactReport.Verdict.DESTRUCTIVE) {
            // Same residual-token computation the boot refusal uses — SchemaDeltaReport is package-visible here.
            SchemaDeltaReport deltaReport = SchemaDeltaReport.generate(dataSource, manifest);
            ackToken = DestructiveAckToken.compute(to, deltaReport.stableStrings());
        }
        return new Result(report, from, to, ackToken);
    }
}
```
> Verify the `SchemaDeltaReport.generate` and `DestructiveAckToken.compute` signatures against the code
> before finalizing (grep them). If `SchemaDeltaReport` is not visible for some reason, STOP and report.

**Test (RED-first):** new `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaImpactFacadeH2Test.java`.
Copy the `UrlDataSource` inner class and the `@BeforeEach` H2 setup from the EXISTING
`ImpactReportH2Test.java` (same directory). Because `forLiveDatabase` calls `loadManifest()` (a fixed
classpath lookup), test the facade indirectly: assert that for a freshly-created DB matching the test
manifest it returns `NO_CHANGES`/`SAFE`. If `loadManifest()` returns null under test (no manifest on the
test classpath), assert `forLiveDatabase` returns a non-null `Result` with a `NO_CHANGES` report and does
NOT throw. Keep this test minimal — its job is "the facade never throws and returns a sane result."

**DoD:** GATE-H2 + GATE-PG green. Commit:
`feat(SER-P6.0): public SchemaImpactFacade.forLiveDatabase — shared impact entry for CLI + ControlPanel`.

---

## P6.4 — Surface 2: `REPORT_ONLY` mode + `-ImpactOnly` CLI + exit codes

**Goal:** a boot mode that computes + prints the impact report against the live DB, **writes zero DDL and
zero writes**, and exits the JVM with `0` (NO_CHANGES/SAFE), `2` (NEEDS_ATTENTION), `3` (DESTRUCTIVE);
plus a `-ImpactOnly` switch on the build script that runs it and propagates the code.

### P6.4.1 — the testable exit-code computation (Java)
In `SchemaLifecycleExecutor.java`, add a method that computes the code but does NOT call `System.exit`
(so it is unit-testable):
```java
/** SER-P6.4: compute the REPORT_ONLY exit code (read-only, zero writes) and print the impact table.
 *  0 = NO_CHANGES/SAFE, 2 = NEEDS_ATTENTION, 3 = DESTRUCTIVE. Package-private for direct unit testing;
 *  the JVM-exit shell is the only caller in production. */
int reportOnlyExitCode(DataSource dataSource) {
    SchemaImpactFacade.Result result = SchemaImpactFacade.forLiveDatabase(dataSource);
    System.out.println(ImpactReportText.render(result.report(), result.fromFingerprint(),
            result.toFingerprint(), result.ackToken()));
    return switch (result.report().verdict()) {
        case NO_CHANGES, SAFE -> 0;
        case NEEDS_ATTENTION -> 2;
        case DESTRUCTIVE -> 3;
    };
}
```

### P6.4.2 — the mode branch in `migrate(...)` (the JVM-exit shell)
In `SchemaLifecycleExecutor.migrate(Flyway flyway, SchemaManifest manifest)` (around line 226), insert the
REPORT_ONLY branch **after** the deprecated-`destructiveAllowed()` NOTICE block and **before** the line
`String storedAtBootStart = readFingerprint(dataSource);` (currently ~line 270). Exact anchor to edit —
find:
```java
        // LNCH-1 remediation R2 (F1): capture the stored fingerprint BEFORE beforeMigrate runs, so we
```
and insert immediately ABOVE that comment:
```java
        // SER-P6.4 (Surface 2): REPORT_ONLY mode computes + prints the impact report and exits with a
        // verdict code, WITHOUT any DDL/claim/history write. Read the mode as a JVM system property so no
        // Spring wiring is needed; the -ImpactOnly script passes -Dnpdev.schema.lifecycle.mode=REPORT_ONLY.
        if ("REPORT_ONLY".equalsIgnoreCase(System.getProperty("npdev.schema.lifecycle.mode", "APPLY"))) {
            int code = reportOnlyExitCode(dataSource);
            System.out.flush();
            System.exit(code);
        }
```
> IMPORTANT: this branch runs BEFORE `readFingerprint`, the claim (`MigrationClaimStore`), `beforeMigrate`,
> `flyway.migrate()`, and `afterMigrate` — so it is guaranteed to write nothing. Do NOT move it below any
> of those.
> Note on `System.exit` vs `Runtime.getRuntime().halt`: use `System.exit(code)`. If in a later live test
> the process hangs on shutdown hooks, switch to `Runtime.getRuntime().halt(code)` and note why.

### P6.4.3 — RED-first Java test
New `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorReportOnlyTest.java`. Reuse the
`UrlDataSource` + H2 setup pattern from `ImpactReportH2Test`/`SchemaLifecycleExecutorDestructiveItemizationTest`.
Because `reportOnlyExitCode` calls `SchemaImpactFacade.forLiveDatabase` → `loadManifest()` (classpath), and
tests supply hand-built manifests elsewhere by calling `beforeMigrate(...)` directly, the cleanest unit test
here asserts the **verdict→code mapping** directly via `ImpactReport.ofProbedItems(...)` is NOT possible
(that's package-private to `com.finalexec.db` — which this test IS in, so it works). Prefer: build a small
`ImpactReport` via `ImpactReport.ofProbedItems(List.of(...))` for each verdict and assert your `switch`
mapping by extracting it into a tiny helper `static int codeFor(ImpactReport.Verdict v)` you can test
directly. Minimum three assertions: SAFE→0, NEEDS_ATTENTION→2, DESTRUCTIVE→3. Do NOT call `System.exit` in
any test.
> If you extract `codeFor(...)`, have `reportOnlyExitCode` call it, so the tested logic is the real logic.

### P6.4.4 — the `-ImpactOnly` script switch
Edit `scripts/appgen/Build-NpdevApp.ps1`:
1. In the `param(...)` block (starts ~line 29), add next to `[switch]$PlanOnly` / `[switch]$Upgrade`:
   ```powershell
   # -ImpactOnly: build the jar, then run it ONCE against the app's configured live database with
   # npdev.schema.lifecycle.mode=REPORT_ONLY. Prints the impact table (what will change, how many rows)
   # and EXITS with the app's verdict code (0 safe/no-changes, 2 needs-attention, 3 destructive) WITHOUT
   # applying anything. Contrast with -PlanOnly = model-vs-previous-MODEL (offline, no DB needed);
   # -ImpactOnly = model-vs-LIVE-DATABASE (the GeneXus impact; the DB must be reachable).
   [switch]$ImpactOnly,
   ```
2. Find where a plain build runs/produces the boot jar (grep the script for `bootJar`, `java -jar`, or
   `Start-Process`). AFTER the jar is built and BEFORE any long-running app start, add an `-ImpactOnly`
   block that:
   - locates the built boot jar (mirror how the script already references it),
   - runs: `& java "-Dnpdev.schema.lifecycle.mode=REPORT_ONLY" -jar "<bootJarPath>"` with the SAME
     working directory / config the app normally boots with (so it reaches the app's real DB),
   - captures `$LASTEXITCODE`, prints a one-line summary, and `exit $LASTEXITCODE`.
   Keep it structurally parallel to the existing `if ($PlanOnly -or $Upgrade) { ... }` block (~line 200)
   so a reviewer can see they are siblings. Do NOT start the H2Server or the long-running app in this mode.
   > If you cannot confidently identify the jar-run seam, STOP and report — do not guess a path.

### P6.4.5 — docs
In `docs/IMPACT_REPORTS.md`, move the `-ImpactOnly` bullet from "Planned surfaces" up into a new
"Surface 2 — pre-deploy CLI (implemented)" section: document the exit codes (0/2/3), the
`-PlanOnly` vs `-ImpactOnly` distinction, and an example invocation. Remove it from the "Planned" list.

**DoD:** GATE-H2 + GATE-PG green; script parses (`pwsh -NoProfile -Command "& { . ./scripts/appgen/Build-NpdevApp.ps1 -? }"` or a `-WhatIf`-style dry syntax check — at minimum `Get-Command -Syntax`). Commit:
`feat(SER-P6.4): REPORT_ONLY mode + -ImpactOnly CLI (exit codes 0/2/3), zero writes`.

---

## P6.5 — Surface 3: `SchemaImpactController` + ControlPanel page (SUPERUSER)

**Goal:** `GET /api/admin/schema-migration/impact` returns the JSON impact report on demand (read-only,
safe on a running app); a minimal page renders it. SUPERUSER-gated, copying
`SchemaAcknowledgmentController` exactly.

### P6.5.1 — the controller
New file
`NPDevRuntimeHost/src/main/java/com/finalexec/controlpanel/SchemaImpactController.java`. Copy the class
skeleton, constructor, `requireSuperUser`, and `requireDataSource` **verbatim** from
`SchemaAcknowledgmentController.java` (same package, same imports: `ObjectProvider<DataSource>`,
`RuntimeContextService`, `HttpServletRequest`, `ResponseStatusException`, etc.). Use a DIFFERENT class
name and a distinct method path; keep the class `@RequestMapping("/api/admin/schema-migration")`.
Add:
```java
import com.finalexec.db.ImpactReportJson;
import com.finalexec.db.ImpactReportText;
import com.finalexec.db.SchemaImpactFacade;
import java.time.Instant;
// ... plus the same imports SchemaAcknowledgmentController uses for the helpers you copied ...

@GetMapping(value = "/impact", produces = "application/json")
public ResponseEntity<String> impact(HttpServletRequest httpRequest) {
    requireSuperUser(httpRequest);
    DataSource dataSource = requireDataSource();
    SchemaImpactFacade.Result r = SchemaImpactFacade.forLiveDatabase(dataSource);
    String json = ImpactReportJson.render(r.report(), Instant.now().toString(),
            r.fromFingerprint(), r.toFingerprint(), r.ackToken());
    return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
}
```

### P6.5.2 — the page (serve inline HTML from the controller — do NOT rely on a static asset path)
There is no existing `*.html` in the RuntimeHost template to copy, and the static-asset serving path is
generator-specific. The robust, self-contained choice: serve the page from a second controller method as
`text/html`. Add:
```java
@GetMapping(value = "/impact/view", produces = "text/html")
public ResponseEntity<String> impactView(HttpServletRequest httpRequest) {
    requireSuperUser(httpRequest);
    // Minimal page: fetches /impact via X-Super-User-Key from a prompt, renders the text table + token.
    String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Schema Impact</title>"
        + "<style>body{font:14px/1.5 system-ui,monospace;margin:2rem;max-width:70rem}"
        + "pre{background:#0b1020;color:#d6e2ff;padding:1rem;overflow:auto;border-radius:6px}"
        + ".dz{color:#ff6b6b;font-weight:700}</style></head><body>"
        + "<h1>Schema impact report</h1>"
        + "<p>Read-only. Requires the SUPERUSER key.</p>"
        + "<button id=go>Load impact</button> <span id=st></span><pre id=out>(not loaded)</pre>"
        + "<script>document.getElementById('go').onclick=async()=>{"
        + "const k=prompt('X-Super-User-Key');if(!k)return;"
        + "document.getElementById('st').textContent='loading…';"
        + "const res=await fetch('/api/admin/schema-migration/impact',{headers:{'X-Super-User-Key':k}});"
        + "if(!res.ok){document.getElementById('out').textContent='HTTP '+res.status;return;}"
        + "const j=await res.json();const lines=[];"
        + "lines.push('verdict: '+j.verdict);lines.push('from: '+j.fingerprintFrom+' -> '+j.fingerprintTo);"
        + "if(j.acknowledgmentToken)lines.push('token: '+j.acknowledgmentToken);"
        + "for(const it of (j.items||[]))lines.push((it.safetyClass.startsWith('DESTRUCTIVE')?'!! ':'   ')"
        + "+it.safetyClass+'  '+(it.table||'')+'.'+(it.column||'-')+'  rows='+it.rowsAffected);"
        + "document.getElementById('out').textContent=lines.join('\\n');"
        + "document.getElementById('st').textContent='';};</script></body></html>";
    return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8").body(html);
}
```
> Do NOT hardcode or echo the SUPERUSER key anywhere server-side or in logs. The page asks for it client-
> side and sends it as the `X-Super-User-Key` header, exactly as the other ControlPanel screens do.
> Keep the HTML minimal; it is a diagnostic surface, not a product page.

### P6.5.3 — RED-first controller test
New `NPDevRuntimeHost/src/test/java/com/finalexec/controlpanel/SchemaImpactControllerTest.java`. Look for an
existing controller test in `com/finalexec/controlpanel/` (grep for `SchemaAcknowledgmentControllerTest` or
`RuntimeContextService` in test sources) and copy its mocking setup (mock `RuntimeContextService` to return
a context whose `hasRole("SUPERUSER")` is true/false; mock the `ObjectProvider<DataSource>`). Assert:
1. no/!SUPERUSER role → the call throws `ResponseStatusException` with 403 (`requireSuperUser`);
2. SUPERUSER + a mocked/H2 `DataSource` → `impact(...)` returns 200 with a JSON body containing
   `"verdict"`. If wiring a full mock is hard, drive it with a real H2 `DataSource` (UrlDataSource pattern)
   and a SUPERUSER-true context — the facade tolerates a null manifest and returns a NO_CHANGES report.
> If no controller-test harness exists to copy, keep the test to the auth check (403 path) which needs no
> DataSource, and verify the 200 path manually via the live app in P6.6 — but say so in the commit.

**DoD:** GATE-H2 green (controller compiles + test passes); GATE-PG green. Commit:
`feat(SER-P6.5): SchemaImpactController — GET /impact (JSON) + /impact/view (SUPERUSER)`.
Then update `docs/IMPACT_REPORTS.md`: move Surface 3 from "Planned" to "implemented", document the two
endpoints + the SUPERUSER header.

---

## Phase 7 — Conversion hooks (the freedom pillar) — ⚠ OWNER GATE

> **DO NOT START PHASE 7 WITHOUT AN EXPLICIT OWNER SIGN-OFF ON "RULE-6" (SANCTIONED DESTRUCTION).**
> Rule-6 means: *a destructive schema item that an operator's conversion hook has resolved requires NO
> acknowledgment token — authoring the hook IS the acknowledgment.* This deliberately lets a hook cause
> data loss without the usual token. The owner must approve this policy before you build it. When you
> reach here, STOP and ask. Everything below is the spec to implement AFTER approval.

**v1 is SQL-only.** A Java `DataMigrationHook` interface is explicitly DEFERRED (it belongs to the
ADR-0003 code-bearing-objects track). Leave a one-paragraph design note in the code; do not build it.

### P7.1 — the artifact + schema
- Add `NPDevContract/schemas/conversion-hook.schema.json` (JSON Schema draft-07). Required fields:
  `id` (string), `claims` (non-empty array of strings, each a canonical Impact-Report item key),
  `description` (string, optional), `verifySql` (string, optional), `verifyExpect` (integer, optional,
  default 0). `additionalProperties: false`.
- Hook folders live in the **app definition (layer 2)**, e.g.
  `D:\WorkSpace\NPDev\AppGen\apps\<app>\definition\migrations\<ordinal>-<slug>/` containing:
  `hook.json`, `convert.sql` (common), optional `convert.h2.sql`, optional `convert.postgres.sql`.
  Remember: **layer-2 app definitions are NOT committed to this repo** — you author test fixtures under
  a scratch app, and evidence goes to OutsideRepo.

### P7.2 — generator plumbing
- Prefer a NEW single-purpose emitter `ConversionHookEmitter` (sibling of `SchemaRealizationEmitter` in
  `NPDevGenerator/generator`), rather than growing an existing emitter. It must:
  1. read every `definition/migrations/<ordinal>-<slug>/hook.json`,
  2. validate each against `conversion-hook.schema.json` **at generation time** (invalid hook = a
     generation ERROR with a precise message: which file, which rule) — never a boot-time surprise,
  3. copy each hook folder into the FinalApp at
     `src/main/resources/db/conversion-hooks/<id>/` (id sanitized to a safe folder name).
- **GATE-GEN test:** hooks land in the generated jar's resources; a malformed `hook.json` fails
  generation with a clear message (RED-first: write the failing case first).

### P7.3 — runtime execution contract (`ConversionHookRunner`)
New `com.finalexec.db.schemastate.ConversionHookRunner` (public), invoked by the executor at ONE fixed
point: **after** the safe convergent passes (renames/relax/tighten) and **before** the destructive
decision — i.e. in `beforeMigrateDecision`, immediately before `refuseIfRequiredBondColumnMissing(...)`
and the `SchemaDeltaReport.generate(...)` block (currently ~line 643–649). Implement EXACTLY these rules;
each numbered rule gets its own RED test in P7.5:

1. Compute the diff. If there are no unresolved items → skip hooks entirely (idempotent re-boot).
2. Load hooks from `classpath:db/conversion-hooks/*/hook.json`. Select hooks whose `claims` intersect the
   current unresolved item-key set. A hook that matches nothing → SKIP with an INFO log (a stale hook is
   NOT an error — the diff may already be converged).
3. Execute selected hooks in ascending `<ordinal>` order. For each, use the engine-variant SQL file if
   present (`convert.<engine>.sql`), else `convert.sql`. Run each hook in **its own transaction**.
4. After each hook, if `verifySql` is present: run it (read-only) and compare the single returned count to
   `verifyExpect`. Mismatch → **abort the boot**: write an `npdev_schema_history` row with outcome
   `HOOK_VERIFY_FAILED` and throw an itemized `IllegalStateException`. (Nothing destructive has run yet.)
5. After ALL hooks: **re-diff against the live DB.** For every claimed item: if it is now GONE → record
   `resolution=HOOK_CLAIMED` in history detail. If a claimed item is STILL present → **refuse the boot**:
   `hook '<id>' claimed '<itemKey>' but the change is still required`. (Claims are promises the engine
   verifies, never trusts.)
6. **Rule-6 (sanctioned destruction):** a DESTRUCTIVE item that a hook resolved requires NO acknowledgment
   token; history records `HOOK_APPLIED {id, claims, sqlHash}`. Destructive items NOT claimed by any hook
   STILL require the token exactly as today. Token = "yes, delete it"; hook = "here's how to migrate it";
   they compose.
7. Every hook step (start, success, failure, verify result, re-diff verdict) writes an
   `npdev_schema_history` row. A failed hook aborts BEFORE any destructive step, always. Roll back the
   failed hook's transaction.

> `sqlHash` = a stable hash (e.g. SHA-256 hex) of the exact SQL text executed. Reuse an existing hashing
> util if the codebase has one; else `java.security.MessageDigest`.

### P7.4 — Impact Report integration
Thread hook resolution into the report: an item claimed by a PRESENT hook renders as `HOOK: <id>` instead
of `!!`, in both the text and JSON (`resolution` field). The DESTRUCTIVE verdict and exit-code `3` then
apply only to *unclaimed* destructive items. (This requires the report to know which hooks claim which
items when generated in REPORT_ONLY / ControlPanel; pass the loaded hook claims into the facade.)

### P7.5 — tests (RED-first, one per rule, H2 + Postgres)
Cover: hook resolves a NEEDS_HOOK backfill → boot green, no token (rule 6); hook claims but does not
resolve → refused (rule 5); `verifySql` mismatch → refused before destruction (rule 4); unclaimed
destructive item still token-gated (rule 6); re-boot after success → hooks skipped (rule 1); hook SQL
error → transaction rolled back, boot refused, history row written (rule 7); two hooks ordered by ordinal
(rule 3).

### P7.6 — operator flow doc
Add to `docs/IMPACT_REPORTS.md`: run `-ImpactOnly` → copy item keys → write a hook folder → re-run
`-ImpactOnly` (items show `HOOK: <id>`) → deploy. This loop is the GeneXus reorganization experience.

### P7.7 — live proof
On a **scratch copy** of a real app DB under `D:\WorkSpace\NPDev\Build` (NEVER the live WmsOffice DB),
perform a real split-column conversion end-to-end via a hook. Evidence → OutsideRepo.

**Gate for Phase 7:** GATE-H2 + GATE-PG + GATE-GEN, all green.

---

## Phase 8 — Proposed conversion SQL (platform drafts, operator decides) — ~1 session

**Goal:** for convertible destructive items, the Impact Report's `proposedConversionSql` field carries a
platform-drafted, **NEVER auto-executed** script the operator can paste into a hook.

### P8.1 — draft generation
For `DESTRUCTIVE_NARROW_TYPE` items (and convertible `MANUAL_REVIEW` numeric cases), populate
`proposedConversionSql` using the copy-convert pattern, generated PER ENGINE via the existing
`com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix` knowledge:
```sql
ALTER TABLE t ADD COLUMN col__new <newtype>;
UPDATE t SET col__new = CAST(col AS <newtype>);          -- SUBSTRING(col,1,<n>) for a varchar shrink
-- verify: SELECT COUNT(*) FROM t WHERE col IS NOT NULL AND col__new IS NULL;  -- expect 0
ALTER TABLE t DROP COLUMN col;
ALTER TABLE t RENAME COLUMN col__new TO col;
```
Emit it in BOTH the JSON (`proposedConversionSql`) and the text report (as a ready-to-paste hook body with
a suggested `verifySql`). Where no safe automatic conversion exists (incompatible cast, e.g. VARCHAR→INT),
OMIT the proposal and set the note: `"no safe automatic conversion — write a custom hook"`.
- Implementation site: the drafting logic belongs next to `ImpactReport`/`ImpactReportJson` (a new
  `ProposedConversionSql` helper in `com.finalexec.db`). Wire it so `ImpactReport.Item` (or the JSON
  renderer) can surface the string; today `proposedConversionSql` is hardcoded `null` in
  `ImpactReportJson` — replace that with the drafted value.

### P8.2 — tests
Unit tests (deterministic — no timestamps inside the SQL text): one proposal per narrowing family
(varchar shrink; numeric precision; incompatible-cast → proposal omitted with the note). Assert exact SQL
strings per engine.

### P8.3 — docs + non-goal
Document the pattern in `docs/IMPACT_REPORTS.md`. Record the explicit non-goal: **NPDev never auto-runs a
proposal** — adoption is always the operator copying it into a hook. (GeneXus auto-runs conversions; NPDev
keeps a human between draft and execution — that is the discipline half of the brief.)

**Gate:** GATE-H2 + GATE-PG.

---

## Phase 9 — Retire the dead lineage + programme closure — ~0.5 session

### P9.1 — prove-then-delete the old migration lineage
Delete `com.finalexec.npdev.migration.*` — these 12 classes:
```
NPDevRuntimeHost/src/main/java/com/finalexec/npdev/migration/MigrationOperation.java
NPDevRuntimeHost/.../migration/MigrationRiskAssessment.java
NPDevRuntimeHost/.../migration/MigrationRiskAssessmentBuilder.java
NPDevRuntimeHost/.../migration/MigrationSharedSupport.java
NPDevRuntimeHost/.../migration/ModelDiffPreview.java
NPDevRuntimeHost/.../migration/ModelDiffPreviewBuilder.java
NPDevRuntimeHost/.../migration/RuntimeModelCompatibilityReport.java
NPDevRuntimeHost/.../migration/RuntimeModelCompatibilityReportBuilder.java
NPDevRuntimeHost/.../migration/StorageColumnSchema.java
NPDevRuntimeHost/.../migration/StorageSchemaFromCompiledModel.java
NPDevRuntimeHost/.../migration/StorageSchemaSnapshot.java
NPDevRuntimeHost/.../migration/StorageTableSchema.java
```
plus their generator-side tests
`NPDevGenerator/generator/src/test/java/com/npdev/generator/migration/*Test.java` and the
`db-history` snapshot / `model_delta.sql` emit path.

**Procedure (mandatory):**
1. For EACH class name, grep the ENTIRE repo: `grep -rn "ClassName" --include=*.java .`
2. If the ONLY references are (a) the class's own file, (b) other files in the same `migration` package
   also being deleted, or (c) the deleted tests → safe to delete.
3. **If ANY main-code (non-test, non-deleted) reference exists → STOP and report.** (This plan believes
   there are none as of 2026-07-24, but verify — do not delete on faith.)
4. Paste the grep output into the commit body as proof.
5. Remove the `FinalAppAssembler` preserved-path entry for `db/migration-plans/`: grep
   `migration-plans` in `NPDevGenerator/.../FinalAppAssembler.java` (~line 319) and delete that entry.

### P9.2 — keep + re-document `MigrationPlanEmitter`
Do NOT delete `MigrationPlanEmitter` (model-vs-model preview is still useful without a DB). Re-document it
honestly: in `Build-NpdevApp.ps1` help text and in the docs, `-PlanOnly` = "offline estimate
(model-vs-previous-model)", `-ImpactOnly` = "the truth (live database)".

### P9.3 — final docs sweep
- `docs/DATABASES_AND_MIGRATIONS.md`: §12 operator matrix gains a hook row ("Manually program the SQL —
  per-item, verified: conversion hooks"); §15 limitations prune everything this programme closed (REG-40,
  ExternallyManaged column-shaped-only, all-or-nothing); §20 rewritten to reflect the finished engine.
- `docs/NPDEV_OPEN_ITEMS_REGISTER.md`: confirm REG-6 CLOSED (already done); mark REG-40 CLOSED.
- Add a knowledge card `knowledge/cards/<slug>.json` for the conversion-hook workflow, following the
  existing card schema `schemas/ai/knowledge-card.schema.json` (copy an existing card's shape).

**Gate:** all gates one final time + one full live app rebuild via the `rebuild-app` skill flow
(`scripts/appgen/Rebuild-And-Restage.ps1`), to prove the deletions didn't break a real generated app.

---

## Appendix A — file & symbol quick-reference

| Need | Where |
|---|---|
| Impact report engine | `com.finalexec.db.ImpactReport` / `ImpactReportText` / `ImpactReportJson` (public) |
| Build the live diff | `new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(m), ShadowParityProbe.scopeToOwnedBusinessTables(new CurrentSchemaReader().read(ds), m))` — **only inside `com.finalexec.db`** |
| Shared public entry (P6.0) | `com.finalexec.db.SchemaImpactFacade.forLiveDatabase(DataSource)` |
| Manifest | `SchemaLifecycleExecutor.loadManifest()` (public static) |
| Executor migrate seam | `SchemaLifecycleExecutor.migrate(Flyway, SchemaManifest)` ~line 226; REPORT_ONLY branch goes ~line 263–269 |
| Hook-runner seam | `beforeMigrateDecision`, just before `refuseIfRequiredBondColumnMissing` / `SchemaDeltaReport.generate` (~line 643) |
| Controller pattern to copy | `NPDevRuntimeHost/src/main/java/com/finalexec/controlpanel/SchemaAcknowledgmentController.java` |
| Ack token compute | `com.npdev.dsl.v1.schemaevolution.DestructiveAckToken.compute(fingerprint, stableStrings)` |
| Build script | `scripts/appgen/Build-NpdevApp.ps1` (param block ~line 29; `-PlanOnly` run block ~line 200) |
| Old lineage to delete (P9) | `com.finalexec.npdev.migration.*` (12 classes, list above) |
| Docs | `docs/IMPACT_REPORTS.md`, `docs/DATABASES_AND_MIGRATIONS.md`, `docs/NPDEV_OPEN_ITEMS_REGISTER.md`, master plan `docs/SCHEMA_ENGINE_REBUILD_PLAN.md` |

## Appendix B — suggested order & commit sequence
1. `feat(SER-P6.0)` — SchemaImpactFacade + public fingerprint accessor.
2. `feat(SER-P6.4)` — REPORT_ONLY + `-ImpactOnly` + exit codes.
3. `feat(SER-P6.5)` — SchemaImpactController + page.
4. `docs(SER-P6)` — IMPACT_REPORTS.md: Surfaces 2 & 3 marked implemented.
5. **OWNER GATE** — rule-6 sign-off. Then Phase 7 in the P7.1→P7.7 order, one commit per sub-step.
6. `feat(SER-P8.*)` — proposed conversion SQL (+ tests + docs).
7. `refactor(SER-P9.*)` — prove-then-delete lineage; docs sweep; knowledge card; final rebuild proof.

Each commit: both gates green first; stage by explicit path; trailer
`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
