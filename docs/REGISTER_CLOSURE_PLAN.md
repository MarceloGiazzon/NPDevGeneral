# NPDev Open-Items Register — Closure Plan

> **STATUS: HISTORICAL** — last changed 2026-07-21; its completion state has **not** been re-verified. Treat nothing here as an open commitment: check `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (authoritative) or `docs/archive/programme-history/OPEN_ITEMS_SNAPSHOT.md` before acting on any item.


> **Status:** APPROVED PLAN — not started.
> **Written:** 2026-07-21, against `docs/NPDEV_OPEN_ITEMS_REGISTER.md` as corrected the same day (an
> independent 4-agent code-verification pass folded in) and commit `c7e3519` (branch
> `beta1-vision-spine`, `beta1-184-gc7e3519`). Working tree clean at write time except this file and
> its two companions (see §1).
> **Origin.** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` lists 17 open items (`REG-1`…`REG-17`) left after
> the five-round LNCH-1 schema-evolution programme (`docs/archive/programme-history/LNCH1_PROGRAMME_RETROSPECTIVE.md`). This
> plan sequences and details the work to close all 17: 2 are deliberate boundaries needing no action,
> 5 cannot close without an action only you can take (plus one item, REG-12, that's split — one slice
> actionable, one gated on your call), and 9 are independently, fully closable by an implementation
> session. **§0 has the exact per-item breakdown** — the original draft of this line undercounted the
> owner-gated group at "3"; it is really larger, and this plan says so plainly rather than rounding up
> its own success rate.
> **Audience.** An AI implementation session (or human) that has **not** read this project's history.
> Follow it phase by phase, in the order given — the order encodes real dependencies (e.g. REG-2 must
> be fixed before REG-16 reviews the surface those tests cover), not just priority. Where this
> document says **VERIFY**, check the real code before writing anything — line numbers and method
> names were captured 2026-07-21 and may have drifted by the time you act.
> **This plan does not re-litigate what LNCH-1 already closed.** It picks up exactly where
> `docs/NPDEV_OPEN_ITEMS_REGISTER.md` and `docs/archive/programme-history/LNCH1_PROGRAMME_RETROSPECTIVE.md` left off.
> **Reviewed and adjusted 2026-07-21 (same day)** against the question *"if this plan is completed,
> can we be certain these items are closed, or at least substantially advanced?"* The review found
> one section had already gone stale (§11.3's REG-1 sub-phase still cited pre-cleanup numbers after
> the sample-app removal in commits `6e5d7a9`/`e32f9bc`) and several phases were silently gated on an
> owner decision with no default if that decision never comes. Both classes of problem are fixed
> below. **New §0** answers the certainty question directly, per item, before any phase detail.

---

## 0. Closure certainty assessment — the question this review exists to answer

**The question:** if this plan is executed in full, which of the 17 register items are *certainly*
closed, which are *certainly substantially advanced but may need a follow-up round*, and which
**cannot** close no matter how much implementation work is done, because they need an action only
you can take? This section answers that honestly, per item, before any phase detail — read it first.

**No item was moved to the boundary list (§14) to make this table look better.** §14 still holds
exactly the same 2 items (`REG-7`, `REG-8`) it always did, for the same documented reasons. Every
other item below keeps its originally-scoped feature/fix work in full — the categories describe
*what limits closure*, not a shrinking of what will be attempted. Nothing was cut to make a checkbox
easier to tick.

| Item | Phase | Closure certainty | Why |
|---|---|---|---|
| REG-3 | P1 | **Certain full close** | Bounded, no owner dependency, DoD is mechanically checkable. |
| REG-2 | P2 | **Certain substantial advance; full close likely** | Root-cause diagnosis is guaranteed (reproducing an existing failure and reading a stack trace is bounded work). The *fix's* size depends on what's found — §7.5 (new) states an explicit fallback so "diagnosis complete, fix scoped" counts as real, recorded progress even if the fix itself turns out bigger than S/M. |
| REG-9 | P3 | **Certain full close** | The JWT-key half has no owner dependency. The super-user-key half now defaults to WONTFIX-with-rationale if Q1 goes unanswered (§8.4), so the item closes either way instead of stalling indefinitely. |
| REG-11 | P4 | **Code-complete certain; item-level close needs REG-10** | The `gradlew.bat` migration, the Docker-Desktop Postgres launcher, and the repo-wide `D:\` sweep are now all mandatory parts of this phase (not optional), so the *code* reaches full readiness. But REG-11's own register text names CI as "the enforcement mechanism" — true closure (proof it works cross-platform) needs REG-10 to actually run, which is owner-gated. Honest state after P4 alone: ready, not proven. |
| REG-10 | P5 | **Cannot close without you** | Needs `gh` CLI or GitHub web access (Q3) that no session has ever had. §10 now gives the exact one-click compare URL to shrink the ask to its floor, but the click itself cannot be performed by an implementation session. |
| REG-16 | P6 | **Certain substantial advance (Tier A); full close likely, may need more than one round** | Tier A (R0–R2: review run, findings documented, remediation planned) is bounded and guaranteed achievable. Tier B (R3–R4: implement + re-review) now explicitly guarantees every CRITICAL/HIGH finding gets fixed within this plan; MEDIUM/LOW findings are fixed if time allows or logged as new dated ledger entries otherwise — never silently dropped. LNCH-1 itself took five rounds on a comparable subsystem; promising one pass fully closes REG-16 would be this plan overpromising, not a real certainty. |
| REG-6 | P7 | **Certain full close** | Bounded refactor, no owner dependency, DoD is mechanically checkable (test count/assertions unchanged). |
| REG-1 | P8 | **Certain full close** (broadened from a 4-app batch) | §11.3 now targets all 7 remaining non-deliberate blanket-posture apps (the 4 `_official` apps plus `invoice-bonds-demo`, `restaurant-saas-multitenant`, `superuser-admin-console`), leaving only the 2 apps that are blanket *by design*. That is REG-1's actual "done" state, not a partial batch. |
| REG-5 | P8 | **Certain full close** | Now defaults to option (a) — rewrite against the exact-list model — if Q2 goes unanswered, with a note the owner can override any time. No longer indefinitely blocked on a response. |
| REG-4 | P8 | **Best-effort; cannot be guaranteed** | A flake by definition may not reproduce on demand. §11.3 now makes *forcing* reproduction (repeated parallel-load runs) the primary step, not a fallback, to maximize odds within this plan's window — but if it genuinely does not recur, the honest outcome is "instrumentation strengthened, still open," not closure. |
| REG-13 | P9 | **Cannot close without you** | Needs a real external human (Q4). No implementation-session substitute is legitimate — the friction *is* the measurement. |
| REG-14 | P9 | **Cannot close without you** | Same blocker as REG-13, same combined session. |
| REG-17 | P9 | **Cannot close without you, and without REG-10/REG-11 first** | Needs Q4 *and* a working cross-platform build to reproduce anything on. |
| REG-12 | P10 | **Slice 2 certain full close; item-level stays PARTIAL by design** | Slice 3 (server-side PDF) is `XL`-shaped and explicitly deferred to its own future plan regardless of this plan's execution — cramming it in here would make this plan's own estimates as dishonest as the original register's were before the 2026-07-21 correction. This is a scoping decision, not a missed closure. |
| REG-15 | P10 | **Cannot close without you** | Trademark clearance needs counsel; the release tag is a business decision. Neither is implementation work. |
| REG-7, REG-8 | §14 | **N/A — deliberate boundary, unchanged** | Not touched by this review; still 2 items, still documented rationale. |

**Bottom line.** Of the 15 actionable items: **5 reach certain full closure** through implementation
work alone after the adjustments in this review (REG-3, REG-9, REG-6, REG-1, REG-5); **2 reach a
certain, real, substantial advance with full closure likely but not guaranteed in one pass**
(REG-2, REG-16); **1 reaches full code-readiness but needs your action to prove closed**
(REG-11, via REG-10); **1 is best-effort and cannot be forced to reproduce on schedule** (REG-4); and
**6 cannot close without an action only you can take** (REG-10, REG-13, REG-14, REG-17, REG-15, and
REG-12's Slice 3 half). That last group was already true before this review — no amount of
implementation-session work changes it, and this plan will not pretend otherwise.

---

## 1. Read before touching anything, in this order

1. This document, end to end — it is long on purpose; skipping a phase's orientation table is how
   the LNCH-1 programme's own regressions happened (see §2 below).
2. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` — the source of truth for what is open, **as corrected
   2026-07-21**. Every phase below cites specific sections of it; read the cited section, not just
   this plan's summary of it.
3. `docs/archive/programme-history/LNCH1_PROGRAMME_RETROSPECTIVE.md` §6 ("Methodology that emerged") and §7 ("Corrections to
   my own work") — the working discipline this plan assumes throughout, and the specific mistake
   patterns (stale claims, un-re-verified attributions, fixture/production divergence) that produced
   several of the corrections in the register on 2026-07-21. Do not repeat them.
4. `docs/LAUNCH_READINESS_GAPS.md` §2 — the 24-item ledger that `REG-9` through `REG-17` trace back
   to (`LNCH-4`, `LNCH-19`, `LNCH-20`, etc.). Gives the original why/DoD behind each.
5. Per-phase reading is listed in that phase's own orientation table below — do not read everything
   up front, read it when the phase needs it.

---

## 2. Corrections already folded into the register — do not re-derive these

An independent, read-only, code-level verification pass ran 2026-07-21 (four parallel audits against
live source, before any fix work started) and found real drift between what the register claimed and
what is actually true. These are **already corrected in `docs/NPDEV_OPEN_ITEMS_REGISTER.md`** — this
plan is built on the corrected version. Do not re-open the original (wrong) framing:

- **`REG-3` is not a policy conflict.** The node_modules/slimness "mutual exclusivity" it originally
  described was already fixed by commit `437d19b` on **2026-05-14**, two months before the register
  was written. There is no decision to make here (see §6).
- **`REG-9`'s scope was overstated by roughly half.** DB credentials and runtime API keys already
  have working, Docker-Compose-wired env-var support. Only JWT keys and the super-user key are
  genuinely open (see §8).
- **`REG-2`'s "no DataSource bean" root cause does not hold up.** No `DataSourceAutoConfiguration`
  exclusion exists anywhere in the tree. The true cause is unestablished — re-diagnose, don't
  re-apply the old theory (see §7).
- **`REG-11`'s "`D:\`-rooted literals" claim is unsubstantiated** in the files it names
  (`scripts/appgen/*.ps1`, `scripts/quality/*.ps1`). The `gradlew.bat` count is real, and a
  cross-platform helper to fix it **already exists and is simply unused** (see §9).
- `REG-1`, `REG-6`, `REG-10`, `REG-16` were reconfirmed largely as described, with minor corrections
  (an app miscounted, a file-size estimate, sizing data added) — see each phase for the exact delta.

If anything you read elsewhere in the repo (an older doc, a stale comment, your own prior
assumptions) disagrees with the corrected register, **the register wins** — it was verified against
live code on the date at the top of this plan.

---

## 3. Guardrails — binding across every phase

Pulled from `CLAUDE.md`, the retrospective's §6/§7, and the specific mistakes that cost sessions in
the LNCH-1 programme. Violating any of these is how a "small fix" phase turns into a rediscovery
session.

1. **Build output never goes in this repo.** `D:\WorkSpace\NPDev\Build`. Evidence/scratch goes in
   `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`, never the repo.
2. **Source-of-truth layers.** This repo = platform code/scripts/schemas. `D:\WorkSpace\NPDev\AppGen\apps`
   = app *definitions* (not a git repo — edit freely, but propagate any code/script change back here).
   `Build` = ephemeral.
3. **No `git add -A` / `git add .`.** Stage named files. Small, bounded commits — one per phase or
   sub-slice, with a `Verified:` line naming what actually ran.
4. **Never `--no-verify`, `--no-gpg-sign`, or skip hooks** unless explicitly asked.
5. **Reproduce RED first.** For every bug fix (REG-2, REG-4, and any finding REG-16 produces), run
   the unfixed code and capture the failure before writing the fix. A green test that was never red
   proves nothing — this is the single most repeated lesson in the retrospective.
6. **A pre-existing bug found on the way gets its own commit and its own test**, before the feature
   commit that found it.
7. **Fixture shapes must mirror production.** If you add or touch a test fixture (REG-16 will), check
   it against the real emitter/manifest shape, not an idealized one — this hid three separate bugs in
   LNCH-1's history.
8. **Live > suite for anything touching auth, tenancy, or data integrity.** "The suite is green" is
   not closure for REG-2, REG-9, or any REG-16 finding that touches real request handling. Real app,
   real database, output recorded.
9. **Record measurement configuration.** Serial vs. parallel, which Gradle properties, cached or
   `--rerun-tasks` — every time a flake rate or timing number is quoted (matters directly for REG-4).
10. **VERIFY markers over assertions.** Every claim in this plan that names a file, line, method, or
    count is either independently re-verified 2026-07-21 (cited as such) or marked **VERIFY** — check
    it against real code before acting on it, because line numbers drift.
11. **Windows environment.** PowerShell primary; Git Bash coreutils on `PATH`. Regenerating an app can
    hit a transient VS Code Java/Gradle file lock — the workaround is bumping the build-root suffix.
12. **Name what is unfixed.** Every phase below ends with a Definition of Done — if you stop before
    it's met, say so explicitly in the commit/evidence note rather than letting it look finished.

---

## 4. Open questions for the owner — batched, do not guess mid-implementation

These are decisions only Marcelo can make. Batch them now rather than surfacing one per phase:

| # | Question | Blocks | Where it's discussed |
|---|---|---|---|
| Q1 | Do you want a **super-user-key seeding** feature (`NPDEV_SUPERUSER_KEY` env var to set a known key at boot), or should the key stay strictly issued-not-supplied? This is a security-posture change, not a bug fix. **Defaults to "no" (WONTFIX, reversible any time) if unanswered** — see §8.4. | REG-9, second half | §8.4 |
| Q2 | Who owns **`GATE-OBS-1a`**'s decision — restore the old package-convention checks, formally retire them, or rewrite against the exact-list model? **Defaults to "rewrite against the exact-list model" if unanswered** — see §11.3. | REG-5 | §11.3 |
| Q3 | Can you open the **`lnch19-ci-verify`** PR yourself (needs `gh` CLI or GitHub web access — no session so far has had either)? | REG-10, and transitively REG-17 | §10 |
| Q4 | Do you have (or can you recruit) **one person who is not you and has not seen this project**, for the combined REG-13/REG-14/REG-17 session? | REG-13, REG-14, REG-17 | §12 |
| Q5 | Is a real **trademark clearance search** (via counsel, not more web searching) something you want commissioned now, and is a release tag still deliberately deferred? | REG-15 | §13 |
| Q6 | For **REG-12 Slice 3** (server-side PDF/document objects) — is this in scope for the current push, or deferred past the current launch horizon? It is the one `L`-effort item in this plan and deserves its own phased plan if greenlit. | REG-12 | §13.1 |

Do not block phases that don't depend on these — most of this plan proceeds without them.

---

## 5. Phase map

| Phase | REG item(s) | Type | Priority/Sev | Effort | Depends on | § |
|---|---|---|---|---|---|---|
| P1 | REG-3 | GAP | LOW (was MED) | S | none | 6 |
| P2 | REG-2 | BUG | MED | S/M | Docker available | 7 |
| P3 | REG-9 | GAP | **P0** | S/M | Q1 (partial) | 8 |
| P4 | REG-11 (code-complete; item-close needs P5) | GAP | P2 | S/M | none | 9 |
| P5 | REG-10 | GAP | P1 | S/M | Q3, P4 (recommended) | 10 |
| P6 | REG-16 | PROCESS | **HIGH** | L | P2 (recommended) | 11.1 |
| P7 | REG-6 | GAP | MED | M | none (recommended before any new SchemaLifecycleExecutor pass) | 11.3 |
| P8 | REG-1 (7-app full batch), REG-5, REG-4 | GAP/PROCESS/BUG | MED/LOW/LOW | M/S/S-M | Q2 (REG-5, has a default) | 11.3 |
| P9 | REG-13, REG-14, REG-17 | GAP/PROCESS | P1/P2/MED | S+S+M | Q4, P5+P4 (REG-17 only) | 12 |
| P10 | REG-12, REG-15 | GAP/PROCESS | P1/P2 | L, S | Q6 (REG-12), Q5 (REG-15) | 13 |
| — | REG-7, REG-8 | BOUNDARY | — | — | no action | 14 |

**Why this order, not the register's raw priority order:** REG-3 is nearly free and removes a phantom
blocker from everyone's mental model before anything else is scoped (P1). REG-2 must be re-diagnosed
before REG-16 reviews the exact surface two of its ten dark tests cover (P2 before P6). REG-11's
`gradlew.bat` migration is mechanical and de-risks REG-10's eventual Linux run, so it's worth doing
just before it (P4 before P5). Everything else follows the register's own §4 reasoning, reconfirmed.
**See §0 for which of these phases the implementer can actually guarantee closes an item, versus
which need your action regardless of how this plan is sequenced.**

---

## 6. Phase P1 — REG-3: close the `GATE-REL-1` misdiagnosis, fix the real orchestration gap

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.3 (rewritten 2026-07-21 — read the corrected
version, not any memory of the original).

### 6.1 What is actually true (re-verified 2026-07-21, do not re-derive)

- `run-beta-release-gate.ps1` never touches `node_modules` directly; it only calls
  `scripts/quality/Invoke-JsonSchemaValidation.ps1`.
- That script's `node_modules` lives **outside the repo**, at
  `..\NPDev_General__OutsideRepo\node-tools\json-schema-validator`, provisioned on demand via `npm
  install` only when missing or the lockfile fingerprint changed. Confirmed already present and
  fingerprint-matched on disk.
- Landed by commit `437d19b` ("Keep schema validator dependencies outside workspace"), **2026-05-14**.
  `docs/WORKSPACE_CLEANUP_POLICY.md` (lines 15, 45 — **VERIFY**, may have shifted) documents this as
  the required pattern.
- `scripts/hygiene/Test-WorkspaceSlimness.ps1` (lines 165–172 — **VERIFY**) only scans the in-repo
  workspace root; the external location is invisible to it. **There is no conflict.**
- The gate's real problem: it exited 1 with **35 of 36** `requiredReports` (defined in
  `scripts/policy/beta-release-gate-policy.json`) missing from `scripts/reports/out/` — an
  orchestration/staleness gap, not a design conflict.

### 6.2 Orientation

| File | What matters here |
|---|---|
| `scripts/quality/run-beta-release-gate.ps1` | The gate entry point; calls `Invoke-JsonSchemaValidation.ps1` at line ~395 (self-check) and ~745 (own output). **VERIFY** exact call sites before editing. |
| `scripts/policy/beta-release-gate-policy.json` | The 36 `requiredReports` — this is your checklist for step 1 below. |
| `scripts/reports/out/` | Where reports must land. As of 2026-07-21 only `json-schema-validator-tests-report.json` is present. |
| `docs/WORKSPACE_CLEANUP_POLICY.md` | Confirms the external-`node_modules` pattern is policy-compliant, not an exception. |
| `docs/OPEN_GAPS_AND_ROADMAP.md` (`GATE-REL-1` entry) | **Describes the same stale premise as the pre-correction REG-3 and needs the identical correction — not yet applied.** Fold it in as part of this phase's commit (small, additive edit; see step 4). |

### 6.3 Steps

1. Read `scripts/policy/beta-release-gate-policy.json`'s `requiredReports` list in full. For each of
   the 36, identify which script/gate produces it (grep the quality-gate scripts for the report's
   filename as an output target).
2. Run each producer script in a sensible order (respect any inter-gate dependency — e.g. a
   sample-matrix report likely needs a built sample first), capturing output to
   `scripts/reports/out/`. Budget real time here: several of these gates build/boot apps.
3. Re-run `run-beta-release-gate.ps1`. Record how many of the original 35 "missing" entries are now
   present, and — separately — how many of the reports that *do* exist actually report a failure.
   These are two different findings; don't conflate them.
4. Decide (implementer's call, not blocking): wire report generation into the release gate itself as
   an orchestration phase (recommended — "run the release gate" becomes one command again), or leave
   it as a documented manual pre-step. Either way, document the choice in the gate's own header
   comment.
5. Make the gate distinguish *precondition unmet* (reports missing) from *check failed* (a report
   says something is broken) in its exit code or first output line. This ambiguity is exactly what
   let the original misdiagnosis stand for two months — closing REG-3 without closing this
   distinction just recreates the conditions for the same class of error.
6. Apply the identical correction to `GATE-REL-1` in `docs/OPEN_GAPS_AND_ROADMAP.md` (small, additive
   — mirror what was done in the register).

### 6.4 Definition of Done

- `run-beta-release-gate.ps1` run on a clean tree, either exits 0 or fails on **real** evaluated
  content (not missing preconditions).
- The gate's exit code (or first output line) distinguishes precondition-unmet from check-failed.
- `docs/OPEN_GAPS_AND_ROADMAP.md`'s `GATE-REL-1` entry matches the corrected `REG-3`.

**Effort:** S. **Risk:** low — this is orchestration, not logic change, on scripts outside the
generator/kernel/runtime hot path.

---

## 7. Phase P2 — REG-2: re-diagnose `IT-EXTPG-1` before fixing it

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.2 (rewritten 2026-07-21).

### 7.1 What is actually true (re-verified 2026-07-21, do not re-derive)

- Ten `integrationTest` classes fail with `ApplicationContext` load errors:
  `JwtAuthExternalBetaIT` (8 `@Test` methods, `@ActiveProfiles({"test","postgres","external-beta"})`),
  `TenantIsolationE2EIT` (1 test), `PublicationRollbackE2EIT` (1 test) — both via
  `AbstractScenarioIntegrationTest`'s `@ActiveProfiles({"test","postgres"})`. All confirmed to live in
  `NPDevRuntimeHost/src/test/java/com/finalexec/` (the **template** source, not a generated app).
- `application-postgres.yml` (`src/test/resources/`) declares `spring.datasource.url:
  jdbc:tc:postgresql:15:///npdev_test...` with `driver-class-name:
  org.testcontainers.jdbc.ContainerDatabaseDriver`.
- **Two prior theories, both now unconfirmed:**
  1. *(three rounds' worth)* "needs an externally-configured Postgres" — established wrong by the
     platform-column round.
  2. *(the platform-column round's replacement theory)* "`test,postgres` creates no `DataSource` bean
     at all" — **re-checked 2026-07-21 and not supported**: a full grep of the tree for
     `DataSourceAutoConfiguration` / `spring.autoconfigure.exclude` found **zero matches**. The
     declared `spring.datasource.*` properties are exactly the shape Spring Boot auto-configures a
     `DataSource` from with no extra bean required, and `org.testcontainers:jdbc` /
     `org.postgresql:postgresql` are both on the test classpath (`build.gradle.template:93-97` —
     **VERIFY**).
- **New leading candidate, not yet confirmed:** Hikari's default eager connection validation failing
  because the `jdbc:tc:` Testcontainers driver can't reach Docker in whatever environment last ran
  the suite — this presents identically to a context-load failure from the outside but is an
  environment/Docker-reachability issue, not a wiring defect.

### 7.2 Orientation

| File | What matters here |
|---|---|
| `NPDevRuntimeHost/src/test/java/com/finalexec/JwtAuthExternalBetaIT.java` | 8 tests, extra `external-beta` profile — check what that profile changes vs. the other two ITs. |
| `NPDevRuntimeHost/src/test/java/com/finalexec/TenantIsolationE2EIT.java`, `PublicationRollbackE2EIT.java` | Both extend `AbstractScenarioIntegrationTest` — read that base class's `@ActiveProfiles` and any `@DynamicPropertySource`/`@TestConfiguration` it declares. |
| `NPDevRuntimeHost/src/test/resources/application-postgres.yml` | The Testcontainers JDBC URL and credentials the failing profile activates. |
| `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorPostgresProofMatrixTest.java` | The **working** contrast case — it deliberately bypasses Spring Boot and starts its own bare-JDBC `PostgreSQLContainer`/`DataSource` manually. Its doc comment frames this as a lighter pattern choice, not evidence the Spring-based path is broken — but it's your reference for "Postgres via Testcontainers definitely works in this repo." |
| `build.gradle.template` (~lines 93–97) | Confirms `testcontainers:jdbc` / `postgresql` are on the classpath for the assembled app. |

### 7.3 Steps

1. **Reproduce RED first, with Docker running.** Run the assembled app's `integrationTest` task and
   capture the **exact** exception: bean name (if any), active profile set, property source, and
   full stack trace. Do this before reading further into this plan's own hypotheses — they may be
   wrong too.
2. Classify what you captured against these candidates, in order of likelihood given what's already
   ruled out:
   - (a) Hikari/Testcontainers-reachability timeout (new leading candidate — check whether Docker was
     reachable and whether the exception mentions connection timeout/pool exhaustion rather than a
     missing bean).
   - (b) A genuinely missing `DataSource` bean for a reason not caught by the
     `DataSourceAutoConfiguration`-exclusion grep (e.g. a conditional-on-property that isn't met, or
     a `@ConditionalOnClass` failing silently) — re-check with the actual stack trace in hand.
   - (c) `JwtAuthExternalBetaIT`'s extra `external-beta` profile activating something that conflicts
     with `postgres` — check this one only if the other two ITs (which don't use it) pass while this
     one fails, which would isolate the variable.
   - (d) The profile combination itself is simply unmaintained (least likely given properties are
     present and plausible, but keep it on the list).
3. Fix at the profile/config level — **not** by giving each IT its own Testcontainers `DataSource`
   (that duplicates the twin's setup ten times and would hide whatever the real defect turns out to
   be).
4. Once green, add the exact run recipe (Gradle command, Docker prerequisite, expected pass count) to
   `docs/OPEN_GAPS_AND_ROADMAP.md`'s `IT-EXTPG-1` entry so a future session can run these in five
   minutes instead of rediscovering the setup.
5. **Then, separately, treat the results as a new question.** Ten tests that haven't run in a long
   time — two of which are `TenantIsolationE2EIT` and part of the auth surface — may surface real
   findings once they execute. Budget time for this before considering REG-2 fully closed; don't stop
   at "the suite compiles and runs" if it then reports failures.

### 7.4 Definition of Done

- The exact root cause is identified with a captured stack trace (not re-asserted from a prior
  theory).
- All ten tests run to completion (pass or documented real failure) under a standard `--tag
  integration` invocation with Docker available.
- The run recipe is documented.
- Any *new* findings the now-running tests surface are logged (feed them into P6/REG-16 if they touch
  tenant isolation or auth — that's exactly the surface that review is about to examine).

**Effort:** S/M, but the estimate assumes the true cause is small (a config/timeout tweak). If step 2
lands on (b) with a real missing-bean cause not yet identified, re-scope before continuing.

### 7.5 Fallback — if the fix turns out bigger than S/M

This is the one phase in the plan whose fix size is genuinely unknown until step 1 runs. Do not let
that uncertainty become open-ended debugging:

- If, after capturing the exact exception and classifying it (steps 1–2), the fix is a config/profile
  change, a timeout adjustment, or anything else contained to `NPDevRuntimeHost/src/test/resources/`
  or a handful of test-support classes — proceed to full closure per the DoD below, same session.
- If the fix would require restructuring how the ITs get their `DataSource` (candidate (b), the least
  likely per the ruled-out-exclusion grep, but not impossible) — **stop at diagnosis.** Record the
  exact root cause, the reason it's bigger than expected, and a re-scoped effort estimate in
  `docs/OPEN_GAPS_AND_ROADMAP.md`'s `IT-EXTPG-1` entry. That is a real, certain, and valuable outcome
  on its own: REG-2 goes from "root cause unconfirmed" (its actual problem today) to "root cause
  confirmed, fix scoped" — a correct diagnosis with a deferred fix is not a failure of this phase, a
  wrong-but-confident diagnosis (REG-2's history twice over) is.
- Either way, do not skip step 5 (running the tests and logging what they find) just because the
  diagnosis took longer than expected — that step is independent of how big the *wiring* fix was.

---

## 8. Phase P3 — REG-9: LNCH-4 secrets, rescoped

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.1 (rewritten 2026-07-21 with a per-secret
table — read it before this section).

### 8.1 What is actually true (re-verified 2026-07-21, do not re-derive)

| Secret | Status |
|---|---|
| DB credentials | **Already works.** `spring.datasource.url/username/password` → Spring's built-in `SPRING_DATASOURCE_*` binding (no hyphen gotcha) → already emitted into generated Docker Compose with `${POSTGRES_PASSWORD:?...}` fail-fast syntax. **No work needed.** |
| Runtime API keys | **Already works.** `npdev.auth.api-keys` → `NPDEV_AUTH_APIKEYS` (the hyphen-stripping gotcha is real but already correctly handled in the compose emitter, which uses the right variable name and comments on the gotcha inline). **No work needed.** |
| JWT signing key (private) + verification key (public) | **Genuinely open.** No env-var path, no compose entry, `StartupValidator` doesn't even receive the path. See §8.3. |
| Super-user key | **Genuinely open, but a feature decision (Q1), not a pure bug fix.** See §8.4. |

### 8.2 Orientation

| File | What matters here |
|---|---|
| `NPDevRuntimeHost/.../auth/LoginController.java` | `@Value("${npdev.auth.jwt.private-key-path}")` (no default), `readKeyFile(...)` — **VERIFY** current line numbers (captured as ~line 58 / ~162–169 on 2026-07-21). |
| `NPDevRuntimeHost/.../auth/JwtBearerAuthFilter.java` | `loadPublicKey()` — **VERIFY** (captured as ~line 153 / ~173–203). |
| `NpdevObservabilityConfig.java` | Where `StartupValidator` is wired up — **VERIFY** (captured as ~lines 121–132). `jwtPrivateKeyPath` is not currently among the params passed in. |
| `NPDevKernel/adapters/runtime-validation/.../StartupValidator.java` | The anchor-ID convention: private `static final String` anchor constants (e.g. `AUTH_ANCHOR = "authentication"`) matching `docs/CONFIGURATION.md` heading slugs; `configError(msg, anchor)` throws `IllegalStateException(msg + " (See docs/CONFIGURATION.md#" + anchor + ")")`. Example call site: `validatePostgres()` → `configError("spring.datasource.password is required when mode=postgres", POSTGRES_ANCHOR)`. Reuse `AUTH_ANCHOR` for the new check. |
| `com.finalexec.controlpanel.SuperUserBootstrapper.java` | Generates the key at first boot (not read from anywhere), persists it hashed via `CredentialRegistryService`, writes the raw value once to `SUPER_USER_KEY.txt` (**VERIFY**, captured as ~lines 97–106). |
| `DockerDeploymentEmitter.java` | Where compose env vars and `.env.example` are emitted. Currently: DB creds and API keys present (**VERIFY** ~lines 137–145, 188–190, 288); zero `NPDEV_AUTH_JWT_*` entries; a **volume mount**, not an env var, for the super-user key file (**VERIFY** ~lines 170–176, 365–369, 408–412). |
| `docs/CONFIGURATION.md` | Documents the `NPDEV_AUTH_APIKEYS` relaxed-binding gotcha (confirmed verbatim accurate, ~lines 31–37, 76–77). `npdev.superuser.force-reissue` has the identical gotcha (→ `NPDEV_SUPERUSER_FORCEREISSUE`) and is currently **undocumented** — fix this as a drive-by in step 3 below. |

### 8.3 Steps — JWT keys (do this regardless of Q1's answer)

1. Add `NPDEV_AUTH_JWT_PRIVATE_KEY_PATH` / `NPDEV_AUTH_JWT_PUBLIC_KEY_PATH` (or a content-via-env /
   mounted-secret convention, if you decide that's preferable to a path — this is an implementer
   design call, not blocked on the owner) to the Compose template and `.env.example`.
2. Wire `jwtPrivateKeyPath` (and the public key path, if `JwtBearerAuthFilter` needs it explicitly
   rather than deriving it) into `StartupValidator`'s constructor/params in `NpdevObservabilityConfig.java`,
   reusing the existing `AUTH_ANCHOR`. A missing key must fail fast with a message linking to
   `docs/CONFIGURATION.md#authentication`, not a raw Spring bean-creation stack trace.
3. Document both relaxed-binding gotchas (`NPDEV_AUTH_JWT_PRIVATE_KEY_PATH` — check whether this one
   even has the hyphen-stripping issue, since it already contains no internal hyphens once
   snake-cased; `NPDEV_SUPERUSER_FORCEREISSUE`) in `docs/CONFIGURATION.md` alongside the existing
   `NPDEV_AUTH_APIKEYS` entry.
4. Update `docs/DEPLOYMENT.md`'s Compose file/instructions to pass the JWT key paths as environment
   variables (or the mounted-secret path chosen in step 1).
5. **Verify live:** deploy from a clean host with **no** key files present and a custom JWT key
   supplied only via the new env var / mount. Confirm boot succeeds and the app issues/validates
   tokens correctly. This satisfies the "live > suite" guardrail — do not close this on a unit test
   alone.

### 8.4 Steps — super-user key (Q1, with a default)

1. Check for Q1's answer before writing code here. **Do not block indefinitely on it:** if this phase
   is reached and Q1 is still unanswered, treat that as a **default "no"** and take the step-3 path —
   seeding a known super-user key is a security-posture *change* (operator-supplied vs. strictly
   issued), and the safe default for an unreviewed posture change is to not make it. Record explicitly
   that the default was applied, not a real "no," so the owner can revisit it any time without this
   looking like a considered rejection.
2. **If yes (seed a known key):** add `NPDEV_SUPERUSER_KEY` as an optional override in
   `SuperUserBootstrapper` — if present, use it instead of generating a random key; still persist it
   hashed via `CredentialRegistryService` the same way. Document the security-posture change
   explicitly (an operator-supplied key is a different trust model than an issued one).
3. **If no, or defaulted per step 1:** close this half of REG-9 as WONTFIX with a one-line rationale
   in `docs/OPEN_GAPS_AND_ROADMAP.md` (state whether it was an explicit answer or the default), and
   make sure `docs/DEPLOYMENT.md` is explicit that the super-user key is retrieved from the mounted
   volume after first boot, not supplied at deploy time.

### 8.5 Definition of Done

- JWT signing/verification keys can be supplied via environment variable (or documented
  mounted-secret convention) with no file baked into the image.
- A missing JWT key fails fast with a `docs/CONFIGURATION.md`-linked message.
- Super-user key handling matches whatever Q1 decided, and is documented either way.
- Verified by a clean-host deploy with no key files present (JWT half) — recorded, not just claimed.

**Effort:** S/M (down from the register's original M — two of four sub-items needed no work).

---

## 9. Phase P4 — REG-11 (partial): migrate `gradlew.bat` call sites to the existing helper

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.3 (rewritten 2026-07-21).

### 9.1 What is actually true (re-verified 2026-07-21, do not re-derive)

- The `D:\`-rooted path literal claim is **not substantiated** in `scripts/appgen/*.ps1` or
  `scripts/quality/*.ps1` — a targeted grep found zero matches. Don't spend time hunting for
  drive-letter literals in these specific files; if you want to close the broader "cross-platform"
  claim fully, do a repo-wide sweep separately (optional, not part of this phase's DoD).
  `gradlew.bat` hardcoding is real and counted: **13 files / 18 occurrences.**
- `scripts/npdev-common.ps1` **already contains** a working cross-platform helper:
  `Get-NPDevGradleWrapperExecutable($ProjectRoot)` (checks `$IsWindows`, falls back between
  `gradlew.bat`/`gradlew`), plus `Test-NPDevGradleExecutable` and
  `Invoke-NPDevCommandCapture`/`Streaming` wrappers. This phase is a call-site migration to existing
  infrastructure, not new design.

### 9.2 Orientation — the 13 files (re-verified counts 2026-07-21, **VERIFY** exact line numbers before editing)

| Location | Files | Occurrences |
|---|---|---|
| `scripts/appgen/*.ps1` | `Build-ClaudeApp.ps1` (×2), `Build-NpdevApp.ps1` (×1) | 3 |
| `scripts/quality/*.ps1` | 10 files including `run-incremental-migration-testing-check.ps1` (×2), `run-post-beta0-maturity-closure-check.ps1` (×2), `run-stateful-additive-migrations-check.ps1` (×2), `run-trusted-source-security-check.ps1` (×2) | 15 |

Re-run `Select-String -Pattern 'gradlew\.bat' -Path scripts\appgen\*.ps1,scripts\quality\*.ps1` (or
the Grep tool) at the start of this phase to get the current, exact file list — the one above is a
snapshot.

### 9.3 Steps

**Reviewer's note (2026-07-21):** the original version of this phase treated step 4 below as
optional. That let REG-11's item-level scope (the register/ledger names three surfaces — the
`gradlew.bat` sites, the AppGen builder scripts, and "the Docker-Desktop-specific Postgres proof
launcher") quietly shrink to just the first one. Step 4 is now **mandatory**, so this phase actually
covers everything this repo's code can control for REG-11 — see §0 for why item-level closure still
also needs REG-10.

1. Re-enumerate the current `gradlew.bat` call sites (command above) — treat the table in §9.2 as a
   starting point, not gospel.
2. For each file, replace the direct `gradlew.bat` invocation with a call to
   `Get-NPDevGradleWrapperExecutable` (or `Invoke-NPDevCommandCapture`/`Streaming` if the script
   already shells out via one of those wrappers elsewhere) from `scripts/npdev-common.ps1`. This is
   find-and-replace-grade work per file — resist the urge to refactor anything else in these scripts
   while you're in there.
3. After each file (or in small batches), run that script's own smoke path if one exists, or at
   minimum a syntax check (`pwsh -NoProfile -Command "& { . .\scripts\quality\<file>.ps1 -WhatIf }"`
   style dry run if the script supports one) — do not batch all 13 into one untested commit.
4. **Mandatory, not optional:** do a repo-wide `D:\`/`D:/` literal sweep outside `scripts/appgen` and
   `scripts/quality` (e.g. `scripts/*.ps1` directly, `scripts/hygiene/*.ps1`, `scripts/ai/*.ps1` if
   any exist) to find what the original REG-11 claim actually meant beyond the 13 files already
   found. Fix what's found using the same helper, same per-file discipline as steps 2–3.
5. **Mandatory:** locate "the Docker-Desktop-specific Postgres proof launcher" the register/ledger
   names as untouched (grep for "Docker Desktop", "postgres" + "proof" together, or check
   `scripts/quality/` and `scripts/appgen/` for anything invoking `docker compose` directly rather
   than through a cross-platform wrapper). If it has the same `gradlew.bat`/drive-letter pattern, fix
   it the same way. If it's genuinely Windows/Docker-Desktop-specific by necessity (not just by
   historical accident), document why in `docs/OPEN_GAPS_AND_ROADMAP.md`'s `LNCH-20` entry rather
   than silently leaving it — a named, justified exception is different from an unexamined gap.

### 9.4 Definition of Done

- All known `gradlew.bat` call sites (13 at last count, re-verify) call the shared helper.
- The repo-wide `D:\` sweep (step 4) is done and every finding either fixed or logged with a reason
  it wasn't.
- The Docker-Desktop Postgres launcher (step 5) is identified and either fixed or has a documented,
  reasoned exception — not silently skipped.
- Each migrated script still runs (smoke-tested, not just visually inspected).
- **This phase alone does not close REG-11** — it makes the code side fully ready. Say so explicitly
  in the closing commit/evidence note rather than implying the item is DONE; full closure needs P5.

**Effort:** S/M (up slightly from the original S, since steps 4–5 are no longer optional).

---

## 10. Phase P5 — REG-10: get a real GitHub Actions run to go green

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.2.

### 10.1 What is actually true (re-verified 2026-07-21, do not re-derive)

- 5 workflow files exist and are committed: `ai-beta-gate.yml`, `ai-knowledge-gate.yml`,
  `npdev-ci-validation.yml`, `npdev-pr-gate.yml`, `npdev-release-gate.yml`.
- `npdev-pr-gate.yml` (the critical path) uses `./gradlew` exclusively (4 invocations, POSIX form,
  with `chmod +x ./gradlew` before the integration-test step) — clean.
  `npdev-ci-validation.yml` mixes POSIX and one intentional Windows-specific job — that's by design,
  not a bug.
- Branch `lnch19-ci-verify` **exists and is already pushed to `origin`** (confirmed via
  `for-each-ref` against both local and remote-tracking refs).
- `gh` CLI is unavailable in every session that has touched this, including the one that produced
  this plan. **This is genuinely blocked on you (Q3).**

### 10.2 Steps

1. **This step is yours, Marcelo (Q3) — minimized to one link.** Re-verified 2026-07-21:
   `lnch19-ci-verify`'s actual branch point (`git merge-base lnch19-ci-verify beta1-vision-spine` =
   `ab14e20`, "LNCH-20 Phase 1: fix gradlew.bat hardcoding on the LNCH-19 CI critical path") is on
   `beta1-vision-spine`, not `main` — it was branched from the active development line, not the
   generic default. The one-click compare/PR URL is:
   `https://github.com/MarceloGiazzon/NPDevGeneral/compare/beta1-vision-spine...lnch19-ci-verify?expand=1`.
   Open it, click "Create pull request," and watch the `npdev-pr-gate.yml` run. (If you'd rather
   target `main` instead, the same URL works with `main` swapped in — that's a judgment call about
   where this should land, not a technical requirement.)
2. If it goes green: REG-10 is DONE. Update `docs/LAUNCH_READINESS_GAPS.md`'s `LNCH-19` row and the
   register.
3. If it fails: capture the failure (it will be the first genuine cross-platform signal this project
   has ever had). Budget specifically for:
   - the copied `gradlew`'s execute bit surviving the generator's file-copy on Linux (a named latent
     unknown in the register),
   - path assumptions the Windows dev environment has been hiding,
   - anything P4 didn't catch (scripts outside `scripts/appgen`/`scripts/quality` that CI happens to
     invoke).
4. Whichever outcome, don't let the run be silently forgotten — record the result (pass or specific
   failures) in `docs/OPEN_GAPS_AND_ROADMAP.md` and this plan's tracking, since REG-17 depends on CI
   being provably green before a third party can reproduce anything.

### 10.3 Definition of Done

- A real GitHub Actions run has executed against `lnch19-ci-verify` (or its successor once merged),
  and its outcome — pass or a specific, triaged failure list — is recorded.

**Effort:** S/M for the fix-up work if it fails; the PR-opening step itself is minutes, but it needs
your access.

---

## 11. Phases P6–P8 — the structural and security work

### 11.1 Phase P6 — REG-16: adversarial review of LNCH-2 (tenant isolation) + LNCH-4 (auth)

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §3.1 (sizing added 2026-07-21).

This is the single highest-severity item in the register (`PROCESS`, **HIGH**) and structurally
different from the others: it is not a known bug to fix, it's **a review that hasn't happened yet**.
The retrospective's own conclusion (§9, §10) is that this is worth more than any further LNCH-1
round. Run it using exactly the discipline that produced LNCH-1's declining-severity curve
(retrospective §6), scoped to this surface.

**Sizing (re-verified 2026-07-21):** `com.finalexec.auth` (10 files, 1,236 lines) +
`com.finalexec.tenant` (1 file, 101 lines) + 21 other tenant/auth-touching files across `api`,
`controlpanel`, `db`, `npdev/dto`, `npdev/service`, `seed`, `config` — roughly **23 distinct
production files, ~3,400+ LOC**. 12 existing test files were found (`TenantIsolationAttackTest`,
`TenantIsolationE2EIT`, `JdbcBusinessConceptStoreTenantIsolationTest`, `TenantRegistryServiceTest`,
`TenantExportRoundTripTest`, `TenantPartitioningGovernanceTest`,
`PublicationChainTenantReferenceValidationTest`, `RowLevelAuthorizationAttackTest`,
`JwtAuthExternalBetaIT`, `IdentityAwareContextResolverTest`, `LoginThrottleTest`,
`PasswordResetControllerTest`) — this surface has real test coverage, just never an adversarial
*review* of the coverage's own adequacy.

**Precondition:** run this after P2 (REG-2), so `TenantIsolationE2EIT` and `JwtAuthExternalBetaIT` are
actually executing — reviewing code whose own E2E safety net is dark is reviewing half-blind.

**Two-tier structure — read this before the round-by-round detail.** The original version of this
phase presented R0–R4 as one undifferentiated block, which understated a real fact: R0–R2 are bounded
and their completion is certain; R3–R4 are not bounded until R1's findings exist, and LNCH-1's own
history (five rounds on a comparable subsystem) is direct evidence that "implement + re-review" can
take more than one pass. Splitting them is not lowering the bar — it's stating plainly which part of
"substantially advance REG-16" is guaranteed and which part is a strong, disciplined best effort.

**Tier A — certain, bounded (R0–R2). Do not skip or compress any of it.**

1. **R0 — Orientation.** Read all 23 files (not summaries), the 12 existing tests, `LNCH-2`/`LNCH-4`'s
   original why/DoD in `docs/LAUNCH_READINESS_GAPS.md`, and `docs/SCHEMA_EVOLUTION.md`'s tenant/auth
   touchpoints if any (row-level authz, `tenant_id` platform-column handling connects to REG-6).
2. **R1 — Independent adversarial review.** Read attack-first, not feature-first. Concretely, probe:
   tenant-boundary enforcement at every data-access path (not just the obvious CRUD ones — check
   background jobs, exports, seed data, ControlPanel/SUPERUSER paths which bypass normal auth by
   design), JWT validation and revocation (token-version check, expiry, algorithm confusion),
   brute-force/lockout threshold correctness and bypass vectors, the documented CSRF posture against
   actual request paths, row-level authorization's interaction with tenant isolation (two mechanisms
   that must agree), password-reset token lifecycle (single-use? expiring? enumerable?), IDOR across
   tenants via any ID-taking endpoint, mass-assignment on any DTO bound directly from a request body,
   timing side-channels on login, and error-message information leakage (does a failed login or a
   cross-tenant fetch leak *which* part failed?). Produce a findings document in the same style as
   `archive/programme-history/LNCH1_HARDENING_PLAN.md`/`LNCH1_CLOSEOUT_PLAN.md`'s "Findings → phase map" tables — one row per
   finding, severity, why it matters, concrete failure scenario. **This document, existing and
   complete, is itself the certain outcome of Tier A** — REG-16's actual problem statement ("zero
   adversarial review") is resolved the moment R1 is done, independent of what R3/R4 achieve.
3. **R2 — Phased remediation plan, plus a severity triage.** Write the remediation plan for an
   implementer with no project history, following this document's own format: orientation table,
   VERIFY markers, guardrails, a stated minimum bar. In the same step, triage every R1 finding into
   exactly one bucket — this triage is what makes R3 bounded instead of open-ended:
   - **CRITICAL/HIGH:** must be fixed inside this plan's R3, no exceptions, treat as blocking priority
     over any other unstarted phase in this document (matches LNCH-1's own precedent — a HIGH/CRITICAL
     tenant-isolation or auth hole outranks scheduling convenience).
   - **MEDIUM:** fixed in R3 if the remaining time budget allows; otherwise logged as a new, dated
     entry in `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (a `REG-18`-and-up item, not silently dropped).
   - **LOW:** always logged as a new dated register entry rather than fixed inline, the same way
     LNCH-1's own low-severity residue became tracked follow-ups instead of scope creep mid-round.

**Tier B — likely, but not guaranteed in one pass (R3–R4).**

4. **R3 — Implement.** Reproduce RED first for every CRITICAL/HIGH (and any MEDIUM taken on) finding.
   Small bounded commits. A pre-existing bug found along the way gets its own commit+test before the
   planned fix.
5. **R4 — Re-review.** Confirm every CRITICAL/HIGH finding from R1 is closed, and — per the LNCH-1
   pattern where nearly every round's fix created the next round's finding — specifically check
   whether R3's fixes changed any decision logic in a way that needs its own look (e.g. a tightened
   tenant check that might now reject a legitimate cross-tenant SUPERUSER operation). **If R4 itself
   finds a new CRITICAL/HIGH issue created by an R3 fix, that is not a failure of this plan — it is
   the exact pattern LNCH-1 documented five times over, and it means a further round is genuinely
   warranted, not that R3 was done carelessly.** Name it as a new dated item rather than quietly
   re-opening R3.

**Verification bar:** live rehearsal against real Postgres (not just H2/suite), and specifically a
real multi-tenant scenario with two live tenants and cross-tenant attack attempts executed and
observed to fail correctly — not just asserted by a unit test. Keep a verification ledger for this
round the way `lnch1-evidence/hardening-verification-ledger.md` served as LNCH-1's tiebreaker, stored
under `NPDev_General__OutsideRepo` per the evidence-location guardrail.

**Effort:** L. This is genuinely the largest remaining item in the register by both LOC-in-scope and
uncertainty (findings are unknown until R1 runs) — do not compress it to fit a smaller estimate.
**Definition of "substantially advanced" if this phase runs out of budget before R4 closes:** Tier A
complete (R0–R2 done, findings documented and triaged) plus every CRITICAL/HIGH finding fixed and
re-reviewed in R3/R4, with any remaining MEDIUM/LOW findings logged as new register items rather than
lost. That state is a legitimate, valuable stopping point — it is not the same as "nothing happened,"
and it is not the same as REG-16 being fully closed either. Report it as what it is.

---

### 11.2 Phase P7 — REG-6: `ColumnFacts` — unify column semantics in `SchemaLifecycleExecutor`

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.6 (corrected 2026-07-21).

**What is actually true (re-verified):** `SchemaLifecycleExecutor.java` is **2,884 lines / ~176 KB**
(corrected from an earlier ≈145 KB estimate — never full-read this file; Grep to a method, Read with
`offset`/`limit`), ~55 methods, ~8 independently-invoked passes (rename, relax, backfill+tighten,
bond-refusal, classify, schema-ahead detection, destructive recreation, unique-constraint
application). Two overlapping "platform column" sets confirmed with **different contents**:
`PLATFORM_MANAGED_COLUMNS` (4 entries: `id`, `version`, `row_version`, `tenant_id`) vs.
`RESERVED_BUSINESS_COLUMN_NAMES` (3 entries, no `id`), plus a fourth copy mirrored across test
fixtures. **Correction:** the drift risk between the two main sets is **already CI-guarded** by
`PlatformColumnContractTest` (parses the executor's source as text, since RuntimeHost can't depend on
the generator module, and fails on divergence from what the emitter actually appends). This lowers
urgency from "unguarded landmine" to "structural complexity that will bite the next new pass."

### Orientation

| File | What matters here |
|---|---|
| `NPDevRuntimeHost/.../db/SchemaLifecycleExecutor.java` | The ~8 passes and the two hand-copied sets. |
| `NPDevGenerator/.../SchemaRealizationEmitter.java` | `fullColumnNames` — the actual source of truth both executor-side sets hand-copy from. |
| `SchemaDeltaReport.java` | Where classify/delta-report semantics live — the third independent notion T-B2 found disagreeing with the others. |
| `PlatformColumnContractTest.java`, `AdditiveColumnMirrorContractTest.java`, `NoMultiEntryMapOfInGeneratedManifestEmittersTest.java` | The three conformance tests currently pinning symptoms of the duplication — these are your regression net during the refactor, and some become retirable once `ColumnFacts` exists (see step 3). |
| Both proof matrices (`SchemaLifecycleExecutorProofMatrixTest.java`, `...PostgresProofMatrixTest.java`) | The full existing behavior contract — **no behavior change is permitted** by this refactor; every one of these tests must stay green with unchanged expectations. |

### Steps

1. Introduce one `ColumnFacts` projection, computed once per (table, column) when the manifest loads:
   `{ isPlatformManaged, isAdditiveEligible, isRequiredByModel, isBond, isPrimaryKey, declaredType,
   renamedFrom, literalDefault }`.
2. Migrate the ~8 passes to consume it **one at a time**, keeping each pass's existing tests green
   throughout. This is a refactor — if any test's *expectation* needs to change (not just its
   internals), the refactor changed semantics and must be reworked, not the test.
3. Once all passes are migrated, collapse the three platform-column sets into the projection's
   `isPlatformManaged`, and retire the conformance tests that exist only to pin duplicate copies —
   **keep** the emitter-side reserved-name validation (`RESERVED_BUSINESS_COLUMN_NAMES`'s actual job
   of rejecting a colliding *model field* at generation time is a different concern from the
   executor's runtime semantics and should not be collapsed away).
4. Do this **before** adding any new pass to the executor — that is the whole rationale for this
   phase's priority.

### Definition of Done

- `ColumnFacts` exists and every pass reads from it instead of re-deriving.
- Both proof matrices pass unchanged (same test count, same assertions).
- The now-redundant conformance tests are retired with a comment explaining why (pointing back to
  `ColumnFacts`), and the reserved-name emitter validation is confirmed still present and untouched.

**Effort:** M. **Risk:** medium — this touches the most-reviewed, most-fixed subsystem in the
platform; lean hard on guardrail #7 (fixture-vs-production mirroring) and #5 (reproduce RED — for a
refactor, that means running the full matrix RED against a deliberately-broken intermediate state at
least once, to confirm the tests actually catch a real regression, not just pass vacuously).

---

### 11.3 Phase P8 — steady-state hygiene: REG-1, REG-5, REG-4

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.1, §1.5, §1.4.

#### REG-1 — corpus flip, full remaining batch

**Superseded 2026-07-21 — this sub-phase was stale.** Its original text (6 recommended / 27 blanket /
5 InMemory-N/A of 38, "next batch is 4 apps") was written before the sample-app cleanup (commits
`6e5d7a9`, `e32f9bc`) removed 18 unreferenced definitions and the register was re-verified against the
live filesystem the same day. **Re-verified fresh for this review, not carried forward:**
**6 recommended / 9 blanket / 5 InMemory-N/A of 20** total definitions.

**Of the 9 remaining blanket-posture apps, only 2 are blanket on purpose:**

| App | Pool | Disposition |
|---|---|---|
| `WmsOffice`, `WordLab`, `AuxScreen`, `Pigmentampa` | AppGen `_official` | Flip — no reason to stay blanket |
| `invoice-bonds-demo` | AppGen | Flip — real, load-bearing, no reason to stay blanket |
| `restaurant-saas-multitenant` | NPDevSamples | Flip — real, catalog-registered, no reason to stay blanket |
| `superuser-admin-console` | NPDevSamples | Flip — real, cited across beta docs, no reason to stay blanket |
| `lnch1-rehearsal` | AppGen | **Leave blanket** — deliberately, its `README.md` explains it exists to rehearse upgrades on a definition shaped like what actually shipped |
| `simple-user-registry-h2local-freshdb` | AppGen | **Leave blanket** — deliberately, it's the cited "freshdb" CI pattern in `docs/SCHEMA_EVOLUTION.md`/`LNCH1_CLOSEOUT_PLAN.md`; flipping it would defeat the scenario it exists to test |

**This phase now targets all 7 flip-worthy apps, not just the 4 `_official` ones.** The earlier
4-app-only scope would have left REG-1 at 5 remaining blanket apps (2 deliberate + 3 real,
unaddressed) after "completion" — a partial result presented as if it were the natural stopping
point. Flipping all 7 is barely more work per the same proven per-app recipe, and it is what actually
reaches REG-1's real "done" state: **zero unintentional blanket-posture apps.**

**Steps** (per app, never in bulk — `WmsOffice`, `WordLab`, `AuxScreen`, `Pigmentampa`,
`invoice-bonds-demo`, `restaurant-saas-multitenant`, `superuser-admin-console`):
1. Edit `db.definition.json` → `strategy: KeepExistingIfCompatible`, `allowDestructiveRecreate: false`,
   `destructiveRecreateConfirmation: ""`. Copy exact field values from the already-proven
   `simple-user-registry-h2local` rather than inventing them.
2. Regenerate → boot → take one additive change through it → confirm the boot log says `skipping
   destructive recreation`.
3. **Watch the shared-output-root trap:** several `simple-user-registry-*` apps share one
   `scenario.name` and therefore one build root/container/port — verify the landed manifest carries
   the right app's concept shape, per app. (Less relevant for this batch's names, but re-check —
   `restaurant-saas-multitenant` has its own dedicated harness dir; don't let its output collide with
   anything else that happens to build in the same session.)
4. Leave `lnch1-rehearsal` and `simple-user-registry-h2local-freshdb` on the blanket posture
   deliberately, per the table above — do not flip these two.
5. Rebuild the AI-authoring corpus (`python scripts/ai/build_knowledge.py`) and re-run the recount,
   updating the numbers in `docs/SCHEMA_EVOLUTION.md` (which still shows the pre-cleanup 6/27/5-of-38
   figures as of this review — that update is now part of this phase's DoD, not a separate pointer).

**DoD:** 7/7 apps flipped and live-proven (one boot + one additive change each, per guardrail #8 —
live, not just suite); `docs/SCHEMA_EVOLUTION.md` recount table updated to
**13 recommended / 2 blanket / 5 InMemory-N/A of 20** (the 2 remaining blanket apps being the
deliberately-kept fixtures); corpus rebuilt. At that DoD, REG-1 is **fully closed** — every remaining
blanket-posture definition in the platform is blanket for a documented reason, not by default.
**Effort:** M (up slightly from the original M-for-4-apps estimate, since scope grew to 7 — but each
app follows the identical, already-proven recipe, so this is linear, not open-ended, work).

#### REG-5 — `GATE-OBS-1a` governance decision

**Q2, with a default — do not leave this indefinitely blocked.** If an owner and a choice among
(a)/(b)/(c) below haven't been assigned by the time this phase is reached, **default to (a) — rewrite
the surface-convergence checks in `scripts/quality/run-runtimehost-gate.ps1` (~lines 114–121,
~243–253 — **VERIFY**) against the exact-list model the beta-0 manifest refactor introduced.** This is
the default because it's the option that keeps the check *meaningful* rather than either silently
restoring a superseded convention (c) or giving up the check's original purpose (b) — reversible any
time the owner weighs in with a different preference. Alternatives, if chosen instead: (b) formally
retire the checks with a dated comment explaining what they used to assert and why it no longer
applies; (c) restore the old "package == support bucket" convention. Whichever path, make the check
blocking again or delete it — "advisory forever" is the failure mode this phase exists to prevent.
Record the decision (including whether it was the default or an explicit owner choice) in
`docs/OPEN_GAPS_AND_ROADMAP.md`. **Effort:** S. **DoD:** the check is either blocking again with an
exact-list-model implementation, or formally retired/restored with a dated rationale — "advisory,
unowned" is no longer a valid end state for this phase.

#### REG-4 — `SandboxedPluginExecutionEngineTest` flake

**Location confirmed:** `NPDevRuntimeHost/src/test/java/com/finalexec/SandboxedPluginExecutionEngineTest.java`.
This item is honestly best-effort (see §0) — a flake by definition does not reproduce on command. The
steps below are ordered to maximize the odds it reproduces **inside this plan's window**, rather than
passively hoping it happens to occur.

**Steps:**
1. **Force it, don't wait for it — this is the primary step, not a fallback.** Run the full suite
   repeatedly under parallel load (the committed local tuning: `org.gradle.parallel=true`,
   `workers.max=4`) — e.g. a loop of 10–20 runs — specifically to trigger the ~1-in-5-under-load
   failure rate the register records. Only fall back to "wait for organic occurrence during other
   work" if a reasonable number of forced attempts (document how many) genuinely doesn't reproduce it.
2. When it fails, read what the self-diagnosis instrumentation emits — it was added specifically so
   the next failure is informative. Do not re-derive the timing assumption by hand first.
3. Confirm which of the two previously-narrowed mechanisms it is.
4. If it's a fixed timeout under contention: raise it and **quote the measured margin in the comment**
   (e.g. "observed max 1.9s under 4-way parallelism; timeout 5s"). Never invent a tolerance that
   doesn't follow from a measurement.
5. Record the measurement's configuration (serial vs. parallel, Gradle properties, `--rerun-tasks` or
   not) per guardrail #9 — the committed local tuning is local-only, CI doesn't use it.

**DoD (full close):** root cause confirmed against real instrumentation output (not re-guessed); fix's
tolerance value is traceable to a specific measurement. **DoD (if forcing genuinely doesn't reproduce
it):** the forcing attempt is documented (how many runs, what configuration) so the next session
doesn't repeat wasted effort, and this is reported honestly as "still open, better instrumented" —
not silently marked done. **Effort:** S/M.

---

## 12. Phase P9 — the human-blocked trio: REG-13, REG-14, REG-17

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.5, §2.6, §3.2.

All three share one blocker (**Q4** — one person who is not you and has not seen this project) and
can close in a single combined session once that person exists. Do not attempt to simulate or
shortcut this with an AI session standing in for the human — the friction *is* the result being
measured (register's own words for REG-13).

**Preconditions:** REG-17 additionally needs P5 (CI green) and P4 (cross-platform scripts) done first
— it's specifically about a third party reproducing verification on hardware you don't control, which
requires the build to actually work cross-platform first.

**Steps, once Q4 is answered:**
1. Give the person `docs/TUTORIAL_FIRST_APP.md`, the MCP toolbox, and `docs/archive/programme-history/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`
   — nothing else. Do not help them. Record friction (REG-13, ADR-0006's Definition of Done).
2. Same session, second pass: have them build the tutorial app from `docs/DSL_REFERENCE.md` and
   `docs/TUTORIAL_FIRST_APP.md` alone, no MCP assistance this time — this is REG-14's distinct DoD
   (newcomer *documentation* quality, not AI-assisted authoring).
3. If P5/P4 are done: have the same person clone the repo fresh, build it, and run the quality gates
   on their own machine (Linux/macOS if available — that's the real test of REG-11/REG-17's claims),
   recording every point they had to ask a question. This is REG-17's DoD.

**DoD:** three friction logs exist, each with concrete, specific friction points (not "it was fine")
— per the retrospective's own §6 discipline, "the friction is the result." Feed anything surfaced back
into `docs/LAUNCH_READINESS_GAPS.md` as new dated findings rather than silently fixing them mid-session.

**Effort:** S + S + M, but scheduling-bound, not skill-bound.

---

## 13. Phase P10 — REG-12 (export/print) and REG-15 (trademark/release)

### 13.1 REG-12 — Excel/PDF/print export beyond CSV

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.4. Slice 1 (CSV) is DONE — grounded and
confirmed 2026-07-21 in `NPDevRuntimeHost/.../api/ConceptQueryController.java`: `exportCsv(...)`
(`@GetMapping("/{concept}/export.csv")`), with helpers `csvFilename`, `toCsvRow`, `csvEscape` — this
is your concrete precedent for how Slice 2 should be wired (same controller, same
filtered/sorted-view semantics, same streaming approach for volume).

**Slice 2 (print stylesheet / print render mode) — do this first, pure frontend:**
1. Add a print-oriented CSS stylesheet and a print render mode for declared panels
   (`npdev-templates/business-ui-app.mustache`'s grid toolbar is the analogous injection point to
   where the CSV export button lives — **VERIFY** exact location, follow the same UI pattern).
2. Cover the GeneXus-migration audience's actual need first: pick-list/packing-slip-style printable
   output for a declared Panel, not a generic "print this page" — that's what makes this not a
   nice-to-have for the WMS-class apps this platform targets.
3. Verify in browser (ScrapForAI) against a real generated app with real data, not just visual
   inspection of the stylesheet.

**Slice 3 (server-side PDF, a `document` PAGE/procedure kind) — blocked on Q6, do not start inside
this phase.** If greenlit, it deserves its own phased plan (`docs/LNCH-12-DOCUMENT-ADAPTER-PLAN.md`
or similar) the way LNCH-1 got one — it's an `XL`-shaped addition (a new pluggable adapter pair, a
new PAGE kind) and pre-planning it here would violate this plan's own principle of not designing
ahead of a decision.

**DoD (Slice 2 only, for this phase):** a declared panel can be printed with a dedicated print
render mode, verified live in a real browser against a real app.

**Effort:** L overall (Slice 2 alone is more modest; Slice 3 is the bulk of the L and is gated on Q6).

### 13.2 REG-15 — trademark clearance and release tag

**Full detail:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.7. Both remaining pieces are yours
(**Q5**): a professional trademark clearance search (not more web searching — the two preliminary
findings already on file, "NP DEV Soluções em T.I." and NPDEV LIMITED UK Companies House #14176093,
are not a clearance), and the decision to cut a release tag (deferred three times already; HEAD is
`beta1-184-gc7e3519`). Note `run-release-checklist-gate.ps1` refuses an untagged release by design,
and P1/REG-3 no longer blocks the larger release gate independently once that phase lands.

**DoD:** a clearance result is on file (favorable or not), and a tag decision is made (cut, or
explicitly deferred again with a stated reason/date). **Effort:** S, entirely gated on your time and
counsel's.

---

## 14. Boundaries — REG-7, REG-8: no action, do not "fix" without a decision

These are deliberate limits, already documented with rationale in the register. Listed here only so
this plan is a complete map of all 17 items — **do not create a phase for them.**

- **REG-7 (`LNCH-1-B6`)** — no migration advisory lock for multi-instance deployments. Boundary
  because single-instance is the stated deployment posture (`docs/DEPLOYMENT.md`) and Docker Compose
  enforces it in practice. Only revisit if horizontal scaling of a single app+database enters the
  roadmap — then wrap `SchemaLifecycleExecutor.migrate(Flyway)` in a `pg_advisory_lock` (Postgres) /
  single-row lock-table `SELECT ... FOR UPDATE` (H2, which has no advisory-lock primitive).
- **REG-8 (`LNCH-1-B9`)** — the schema-ahead detector can't see a pure column *drop* by a newer build
  (no residue exists to detect from live schema shape alone). WONTFIX for v1 by scope, not
  impossibility — `npdev_schema_history` already has what a fix would need (a
  newer-than-this-build `to_fingerprint` row) if this is ever revisited.

---

## 15. Effort roll-up and minimum bar

| Effort | Items | Closure certainty (see §0) |
|---|---|---|
| S | P1 (REG-3), REG-5 | Certain full close (both) |
| S/M | P2 (REG-2), P3 (REG-9), P4 (REG-11), REG-4, REG-10, REG-13, REG-15 | REG-3/REG-9 certain; REG-2 substantial-advance-certain; REG-11 code-certain/item-owner-gated; REG-4 best-effort; REG-10/REG-13/REG-15 owner-gated |
| M | P7 (REG-6), P8/REG-1 (7-app batch), REG-14, REG-17 | REG-6/REG-1 certain full close; REG-14/REG-17 owner-gated |
| L | P6 (REG-16), REG-12 | REG-16 substantial-advance-certain (Tier A) / likely (Tier B); REG-12 Slice 2 certain, item PARTIAL by design |

**If effort runs short, the non-negotiable core is P1 → P2 → P3.** These three are: nearly free
(P1), a correctness-and-diagnosis prerequisite for the highest-severity remaining item (P2), and the
last P0 in the entire launch ledger (P3). Everything from P6 onward is high-value but each is
independently schedulable and none blocks the platform's basic honesty the way REG-9 (secrets) does.
P6 (REG-16) specifically should **not** be compressed to fit a deadline — per the retrospective's own
§10 lesson #10, review effort has its highest return on a subsystem that's never been looked at, and
compressing it defeats the point.

**If there is room beyond the core, prioritize the other certain-full-closure items next** — P7
(REG-6), P8's REG-1 (now the full 7-app batch) and REG-5 — before Tier B of P6 or anything
owner-gated. They are bounded, carry no risk of stalling on someone else's schedule, and each one
completed is one more item that moves from the register's open list to genuinely closed, not merely
advanced. This is the concrete way to satisfy "close as many items as possible": spend the certain
budget first, then spend the best-effort/likely budget (REG-2's fix, REG-16's Tier B, REG-4), and
treat the six owner-gated items (REG-10, REG-13, REG-14, REG-17, REG-15, REG-12 Slice 3) as things to
hand off with the friction already minimized — not as things this plan can fail to deliver on, since
it was never able to deliver them alone.

---

*Companion documents: `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (the corrected source of truth this plan
sequences) · `docs/archive/programme-history/LNCH1_PROGRAMME_RETROSPECTIVE.md` (the methodology this plan reuses) ·
`docs/LAUNCH_READINESS_GAPS.md` (the 24-item ledger these items trace back to) ·
`docs/SCHEMA_EVOLUTION.md` (relevant to P3/P7/P8's REG-1 flip) ·
`docs/OPEN_GAPS_AND_ROADMAP.md` (runtime/generator items, updated by P1 and P8).*
