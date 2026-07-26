# Post-REG-17 Closure Plan — LEDGER-1, REG-31, REG-32, REG-16-resid

> **STATUS: HISTORICAL** — last changed 2026-07-24; its completion state has **not** been re-verified. Treat nothing here as an open commitment: check `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (authoritative) or `docs/OPEN_ITEMS_SNAPSHOT.md` before acting on any item.


> **Written:** 2026-07-24 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
>
> **Honest scoping — read first.** These four are NOT all the same kind of work:
> - **LEDGER-1** (runtime diagnostic) and **REG-32** (bootstrap precondition) are **bounded code/config**
>   changes — specified concretely below, verifiable RED→GREEN, a careful agent can execute them.
> - **REG-31** (recalibrate a quality check) needs a **calibration judgment** — a short spot-check then a
>   decision; specified, but the decision is real.
> - **REG-16-resid** (adversarial review of ~21 surfaces) **cannot be mechanical.** It needs a capable
>   reviewer thinking like an attacker, is iterative (one surface per round), and its "output" is
>   findings, not a diff. This plan scopes Round 1 and the method; it does not — and cannot — hand a
>   less-capable tool a recipe that "does the review."
>
> Each task is independent. Do them in any order. Every code/config task is **verify-locally-then-commit**
> (the discipline that carried REG-17). Do not push unless the owner asks; nothing here is urgent.

---

## Global rules

1. Absolute paths. Shell: PowerShell (Bash where a step says `bash`).
2. Git: only the exact `git add <named files>` + `git commit` blocks each task gives. **Never** `git add .`,
   never push. Slimness hook runs on commit; if it fails, run
   `pwsh -File scripts\hygiene\clean-workspace-state.ps1` then retry once.
3. Never edit under `D:\WorkSpace\NPDev\Build` or any `npdev-generated` folder.
4. **RED-first for every code change:** reproduce the current broken/absent behavior before fixing, so the
   fix is proven, not asserted. This session's two wrong hypotheses (Flyway→DataSource, npm-prefix) were
   both caught this way.
5. If reality diverges from what's written here, STOP and report — do not improvise a different fix.

---

## Task 1 — LEDGER-1 runtime diagnostic (bounded code; ~½–1 session)

**Goal.** When a generated app's model uses `createConcept`/`updateConcept`/`saveConcept` (or a
`persistence.*` capabilityCall) but declares **no `persistence` binding**, surface a clear, actionable
diagnostic instead of a bare Spring 500. The **doc half is already done** (commit `9f180aa` fixed the
manual examples); this is the code half.

**Investigation already done (do not re-derive):**
- The runtime path: `createConcept` → the `persistence` capability → `RegistryCapabilityDispatcher`
  (`NPDevKernel/.../RegistryCapabilityDispatcher.java:~50`) returns a **structured**
  `CapabilityResult.failure("CAPABILITY_BINDING_MISSING", "…capability 'persistence' and adapter '<missing>'", NOT_FOUND, …)` — it does NOT throw. Downstream a flow step turns that into an uncaught
  exception → Spring default 500; the clear message reaches only `_ops\app.out.log`.
- **Validate-time cannot reliably catch it** and must not be forced to: `SemanticValidator`
  (`NPDevContract/dsl/.../validation/SemanticValidator.java:~2608`) already adds `persistence` to
  referenced capabilities for `createConcept` and checks bindings (`validateReferencedCapabilityBindings`,
  ~line 2284) — but only when `allowUnboundFlowCapabilities=false`, and the lenient default exists on
  purpose (a binding can legitimately come from a built-in pack). So the binding is only *definitively*
  absent at **runtime**. A runtime diagnostic is the right layer.

**Approach — Option A (boot-time fail-fast; preferred, lowest-risk):**
A generated app that will 500 on its first `createConcept` should refuse to boot with a clear message
instead. Add a startup check in the RuntimeHost template near where the capability registry is built:
`NPDevRuntimeHost/src/main/java/com/finalexec/config/NpdevCapabilityBindingConfig.java` builds the
`CapabilityRegistry` (`~line 217`) and the dispatcher (`~line 248`). Add a small
`ApplicationRunner`/`InitializingBean` bean (or extend the existing `StartupValidator` wiring) that:
1. reads the compiled model's flow steps (the same source `SemanticValidator` reads),
2. collects the persistence-semantic references (`createConcept`/`updateConcept`/`saveConcept` or a
   `persistence.*` capabilityCall),
3. if any exist AND the built `CapabilityRegistry` has no binding for `persistence`, throws a
   docs-linked `IllegalStateException` at boot: e.g. *"Model flow '<X>' persists via 'persistence' but
   no persistence capability binding is registered. Declare a binding (persistence-inproc for dev /
   persistence-postgres for prod). See docs/NPDEV_USER_MANUAL.md#capabilities-and-bindings."*
   Reuse the `configError(msg, anchor)` convention if extending `StartupValidator`.

*(Option B — an HTTP `@RestControllerAdvice` mapping a flow-step `CAPABILITY_BINDING_MISSING` failure to
an actionable 500/503 body — is the fallback if boot-time model access proves awkward. It's more
faithful to "runtime diagnostic" but needs tracing exactly where the structured failure becomes a
throw. Prefer A.)*

**Steps.**
1. **RED-first.** Generate an in-memory app whose model omits the persistence binding (or reuse the
   pattern from `simple-user-registry-inmemory` with the binding removed), build + boot it, hit the
   `createConcept` endpoint, and capture the **bare 500** + the stdout `CAPABILITY_BINDING_MISSING`. This
   is the "before".
2. Implement Option A in the RuntimeHost template.
3. **Rebuild via the `rebuild-app` skill** (RuntimeHost template change → generator-runtime + libs
   restage): `pwsh -File scripts/appgen/Rebuild-And-Restage.ps1 -AppFolder <the in-memory app>`.
4. **GREEN:** the regenerated app now **refuses to boot** with the docs-linked message (or, Option B,
   returns an actionable body). Confirm the message names `persistence` + the fix.
5. Run the RuntimeHost gate: `pwsh -File scripts\quality\run-runtimehost-gate.ps1` — must stay green.

**DoD.** A binding-less persistence app fails fast (boot) or returns an actionable HTTP body — naming
the missing binding and the fix — verified live on a regenerated app; RuntimeHost gate green.

**Commit.**
```
git -C D:\WorkSpace\NPDev\NPDev_General add NPDevRuntimeHost/src/main/java/com/finalexec/config/NpdevCapabilityBindingConfig.java <any StartupValidator file touched> docs/LAUNCH_READINESS_GAPS.md
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "feat(LEDGER-1): boot-time diagnostic naming a missing persistence binding instead of a bare 500 (runtime half; doc half was 9f180aa)"
```
(Update the LEDGER-1 finding in `docs/LAUNCH_READINESS_GAPS.md` from "runtime diagnostic half not yet
built" to done.)

**Caveat.** RuntimeHost template changes ship into **every** generated app — the RuntimeHost gate +
a live boot are the real verification, not a unit test alone.

---

## Task 2 — REG-31: recalibrate `run-script-automation-quality`'s report-contract check (calibration judgment; ~1–2h)

**Goal.** The check's `structured-report-contract` sub-check
(`scripts/quality/run-script-automation-quality.ps1:98-99`) greps script source for the literal helper
names `Invoke-NPDevReportedCommand`/`Write-NPDevJsonFile` and flags **59 of 68** quality scripts. That's
a *helper-name presence* test, not a *report-behavior* test. Made non-blocking in CI 2026-07-24; this
task decides the real fix.

**Already-confirmed evidence (2026-07-24 spot-check):** flagged scripts DO emit valid structured JSON by
other means — e.g. `run-frontend-gate.ps1` has **0** helper-name hits but **2** `ConvertTo-Json`/
`Set-Content` report writes; `run-observability-hardening.ps1` has 1 helper + 8 direct writes. So the
population is "differently-compliant," not broken. **The check is the suspect, not the 59 scripts.**

**Steps (judgment task — the decision is real, but scoped):**
1. **Spot-check 6 flagged scripts** (pick from the 59): for each, run it (or read it) and confirm it
   writes a report JSON to `scripts/reports/out/*.json`. Record which are truly compliant-by-other-means
   vs. genuinely emit nothing structured.
2. **Decision:**
   - If most write a valid report (expected) → **recalibrate the check**: instead of grepping for helper
     names, assert the *artifact* — run each scoped script (or check its declared report path) and
     validate the produced `*-report.json` exists and parses / matches its schema. That is a
     behavior test, which is what the check was always meant to be.
   - For any genuinely non-compliant remainder → list them as a small, separate migration backlog (NOT
     part of this task; do not mass-migrate 59 scripts).
3. Once the check tests behavior and passes on the compliant population, **re-block it** in
   `.github/workflows/npdev-ci-validation.yml` (remove the `continue-on-error: true` from the "Script
   automation quality" step) so it guards for real again.

**DoD.** `run-script-automation-quality.ps1`'s report-contract sub-check asserts report *behavior*, not
helper-name presence; passes on the compliant scripts; the CI step is blocking again; any real
non-compliant remainder is filed. Verify locally: `pwsh -File scripts\quality\run-script-automation-quality.ps1`
exits 0.

**Commit.**
```
git -C D:\WorkSpace\NPDev\NPDev_General add scripts/quality/run-script-automation-quality.ps1 .github/workflows/npdev-ci-validation.yml docs/NPDEV_OPEN_ITEMS_REGISTER.md
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "fix(REG-31): recalibrate structured-report-contract check to assert report behavior, not helper-name presence; re-block in CI"
```

**Caveat.** Do NOT "fix" the 59 scripts to add helper names — that's cargo-culting the miscalibration.
Fix the check.

---

## Task 3 — REG-32: make the maturity-report bootstrap precondition-aware + fix the 1 real schema-invalid report (bounded; ~½ session)

**Goal.** The Linux job's "Bootstrap post-Beta0 maturity reports" step (`npdev report bootstrap`)
**aggregates** ~21 maturity reports and hard-fails if any are missing — but this job doesn't run the ~21
producer gates that generate them, so ~19 are "missing" (a REG-3-class **precondition**, not a defect),
plus **1 genuinely schema-invalid** report. Made advisory in CI 2026-07-24; this task does the real fix.

**Investigation already done:** `scripts/quality/bootstrap-post-beta0-reports.ps1` computes
`overallStatus` (line ~99) as failed if ANY of {missing, failed, schema-invalid, producer-failure,
no-final-manifest}. It runs 2 producers (`validate-report-schemas.ps1`, `generate-final-evidence-bundle.ps1`)
that also fail when reports are missing. **REG-3 already established the precedent** for this exact
problem — `run-beta-release-gate.ps1` distinguishes *precondition-unmet (exit 2)* from *check-failed
(exit 1)* and added `run-beta-release-evidence-orchestration.ps1`. The 1 real defect:
`stateful-additive-migrations-report.json` fails several `const` fields
(`migrationModeAdditiveOnlySupported`, `versionedFlywayMigrationGenerated`, …) against
`schemas/ai/stateful-additive-migrations-report.schema.json`.

**Steps (do BOTH halves):**
1. **Precondition-awareness (REG-3 pattern).** In `bootstrap-post-beta0-reports.ps1` (and the two
   producers it invokes), classify **missing required reports** as *precondition-unmet* (producers not
   run → non-fatal, exit 2 / a distinct status) rather than *check-failed*. Only fail (exit 1) when a
   report that **exists** is schema-invalid or its own status is failed. RED-first: on a clean tree (all
   reports missing) the current script exits 1; after the fix it should exit non-fatally with a clear
   "PRECONDITION-UNMET: N producers not run" line.
2. **Fix the 1 real defect.** Reproduce the `stateful-additive-migrations-report.json` schema failure
   locally (run its producer, then `validate-report-schemas.ps1`). Determine whether the **report values**
   are wrong or the **schema `const`s are stale** (REG-3 found exactly this class — a stale fixture/schema).
   Fix the correct side so the report validates. Verify: the report passes schema validation.
3. Once bootstrap distinguishes precondition-unmet from check-failed AND the schema-invalid report is
   fixed, **remove the `continue-on-error: true`** from the CI Bootstrap step so it blocks on real
   defects again (but tolerates missing producers).

**DoD.** `npdev report bootstrap` on a clean tree exits non-fatally (precondition-unmet, not failure);
the `stateful-additive-migrations` report validates; the CI Bootstrap step is blocking again but
green. Verify locally: `python NPDevCli/npdev_cli.py report bootstrap` behaves as above.

**Commit.**
```
git -C D:\WorkSpace\NPDev\NPDev_General add scripts/quality/bootstrap-post-beta0-reports.ps1 scripts/quality/validate-report-schemas.ps1 scripts/quality/generate-final-evidence-bundle.ps1 <the stateful-additive report producer or schema> .github/workflows/npdev-ci-validation.yml docs/NPDEV_OPEN_ITEMS_REGISTER.md
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "fix(REG-32): bootstrap distinguishes precondition-unmet from check-failed (REG-3 pattern) + fix stateful-additive-migrations schema-invalid; re-block CI step"
```

**Alternative (heavier, if the owner wants full evidence):** instead of precondition-tolerance, wire the
~21 producer gates into the Linux job before bootstrap (some build/boot apps — expect more first-contact
findings and much longer CI). Precondition-tolerance is the lower-risk default.

---

## Task 4 — REG-16-resid: adversarial review of the un-reviewed launch surfaces  ⚠️ CAPABLE AGENT / HUMAN — NOT MECHANICAL

**Why it's different.** REG-16 was closed for **LNCH-2 (tenant isolation) + LNCH-4 (auth)** only. By its
own title, the **other ~21 launch surfaces** — generator codegen, kernel FlowEngine/`KernelRunner`,
LNCH-13 row-level authz, the export/PDF path — have had **zero attack-first review**. This is the single
largest *unknown* in the codebase (not a known bug). It **cannot** be done by a mechanical executor: it
needs a reviewer thinking like an attacker, and its output is *findings*, not a diff.

**This is iterative — one surface per round.** The LNCH-1 programme took five rounds; REG-16 Tier-A took
its own. Do not attempt "all 21 at once." Reuse the proven discipline and the existing template:
`docs/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md`.

**Round 1 target (highest value): the kernel execution path** — `KernelRunner` +
`RegistryCapabilityDispatcher` + the idempotency/circuit/bulkhead paths. It's the code **every**
generated app runs; a flaw there is a flaw everywhere.

**Method (per round):**
1. **Independent review, attack-first.** Read the surface adversarially: injection, tenant/actor
   confusion, replay/idempotency bypass, error-path info leaks, resource exhaustion, ordering/race in
   durable state, capability/authz bypass. Reproduce RED first — a claimed vuln with no failing test is
   a hypothesis, not a finding (this session had two wrong hypotheses; the same rigor applies).
2. **Findings document** in the REG-16 template shape (`docs/REG16_<surface>_ADVERSARIAL_REVIEW.md`):
   each finding with severity (CRITICAL/HIGH/MEDIUM/LOW/INFO), a concrete failure scenario, and a fix
   sketch. Triage: CRITICAL/HIGH are mandatory Tier-B; MEDIUM/LOW get dated `REG-NN` items (like
   REG-18…26 did).
3. **Phased plan → implement → re-review**, verifying live, not just by suite; keep a verification
   ledger; never let a summary claim more than its evidence file. File every finding — do NOT silently
   patch.
4. **Record** the round in `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (REG-16-resid): surface reviewed, findings
   count by severity, what was fixed vs. filed.

**DoD (per round, not for all 21 at once).** One surface has had an independent attack-first review with
a findings document + triaged remediation; findings filed as dated items; CRITICAL/HIGH fixed and
verified. **Full closure = all high-value surfaces reviewed** — a multi-round programme, not one task.

**Honest note.** If no capable reviewer is available, REG-16-resid stays OPEN. It is not blocking launch
(the ledger is 24/0/0), and the security *core* (LNCH-2/4) was reviewed clean. But it is the only
remaining item that could hide a real defect, so it's the highest-value non-mechanical work.

---

## Suggested order
1. **REG-32** and **REG-31** — small, unblock the two CI steps currently made advisory (restores real
   guarding). Both verifiable locally.
2. **LEDGER-1 runtime diagnostic** — bounded, improves the newcomer experience; needs a rebuild + gate.
3. **REG-16-resid Round 1** — the biggest value, but capable-agent/iterative; schedule deliberately.

## STOP rules
STOP and report if: a RED-first repro doesn't reproduce the described "before" · a fix's local
verification doesn't go GREEN · a commit fails the slimness hook twice · you're tempted to mass-migrate
the 59 scripts (REG-31), wire all 21 producers without owner sign-off (REG-32), or "do" REG-16-resid
mechanically. Do not push — the owner decides that.
