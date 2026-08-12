# Post-Public Plan — what's open after TREE 1 + DSL 2.0 + decomposition

> **STATUS: DONE except the owner-only item.** Written 2026-07-28 against `beta1-vision-spine` @
> `b7a4f0f` (origin/main @ `89eb945`, tag `beta1.2`, repo **public**). Every assistant-executable
> item (Parts 1-4) closed 2026-07-28 — see the Definition of Done below. Only P2.4's actual outreach
> ("3 people outside this machine have tried it") remains, and it always was owner-only, not
> something the assistant does.
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\POST_PUBLIC_PLAN.md" "D:\WorkSpace\NPDev\NPDev_General\docs\POST_PUBLIC_PLAN.md"
> ```
>
> Written after an independent verification pass over the two closed plans. Facts are **MEASURED**
> (git, source, filesystem, gates run 2026-07-28) or **PROPOSED**.

---

# Part 0 — Verification of the closure claims

## 0.1 Confirmed DONE — MEASURED

| Claim | Evidence |
|---|---|
| Working tree clean | `git status --short` → empty |
| Everything pushed | `origin/beta1-vision-spine` = `b7a4f0f` = local HEAD |
| PR #5 merged | `89eb945` *"Merge PR #5: TREE1 launch-unblock + DSL 2.0 + god-file decomposition"* |
| `beta1.2` tagged on the merge | `git rev-list -n1 beta1.2` → `89eb9458206b` ✅ |
| TREE 1 all 16 tasks | `TREE1_LAUNCH_UNBLOCK_PLAN.md` → `STATUS: DONE (2026-07-28)` |
| DSL 2.0 + decomposition | `DSL2_AND_DECOMPOSITION_PLAN.md` → `STATUS: DONE (2026-07-28)` |
| **2.A alias collapse** | `flowStep.type` enum **23 → 12**; parser switch is 1:1 |
| **2.A.0 resolved** | `createConcept`/`updateConcept` kept as distinct kinds → 12, not 9 |
| **2.A.3 codemod** | `NPDevCli/dsl_v2_migration.py` + `tests/test_dsl_v2_migration.py` |
| **2.A.2 mirror gate** | knowledge gate now prints *"all four copies semantically identical (excusing `deprecated`/`canonicalSchema` on the legacy copy)"* — the check I proposed, shipped |
| **2.B.1** SemanticValidator | 4,244 → **202** + 12 `*Validation` classes |
| **2.B.2** TrustedSourceEmitter | 3,782 → **212** |
| **2.B.3** GeneratedCrudRuntimeSupport | 5,125 → **3,651**, 24 files, `crud/` package |
| **2.B.4** SchemaLifecycleExecutor | 3,739 → **2,120** + `BackfillPass`, `ColumnRenamePass`, `DestructiveRecreationPass`, `DesiredSchemaFactory`, `ClassificationReducer` — the `SchemaPass` design |
| **2.B.5 / CORE C-4** KernelRunner | 4,423 → **3,071** + `AwaitEventStep`, `BranchStep`, `CapabilityCallStep`, `EmitEventStep`, `CompensationRunner`, `FlowStateCodec`, `ResumeCoordinator` |
| Both gates | `check-register-consistency.py` → OK, **10/10** planning docs declare status; `run-ai-knowledge-gate.ps1` → **PASSED** |
| Repo public | `MarceloGiazzon/NPDevGeneral` |

**The closure claims hold.** 2.B.5 landed the step-kind classes flat in `com.npdev.kernel` rather
than a `flow/steps/` subpackage — different shape than the plan sketched, same outcome, and
**CORE C-4 is therefore also closed**: `FlowEngine`'s implementation is now findable by filename.

## 0.2 One correction to the report

> *"everything is pushed"* — true. `origin/beta1-vision-spine` matches HEAD exactly.
> What **is** stale is **local `main`** (`98a3cb9`) vs `origin/main` (`89eb945`). Cosmetic; one
> `git checkout main && git pull`. Worth doing so `git log main..HEAD` stops lying by 172 commits.

## 0.3 The finding CI caught is worth more than its fix

The report notes CI caught that `security-pattern-sweep-allowlist.json` keys fingerprints **by file
path**, so 2.B.3 and 2.B.4 silently orphaned **37** previously-cleared "safe" verdicts. Each was
traced to pre-split code, confirmed byte-identical, re-cleared, and the gotcha documented.

That was handled correctly. **But documenting a landmine is not defusing it.** The allowlist is
still path-keyed, so the *next* split — and Part 3 below proposes three more — re-orphans verdicts
again. See **P3.1**: this is a structural fix, not a note.

---

# Part 1 — 🔴 Stale docs that will misdirect the next session

**Highest priority. ~1 hour total. Do before anything else.**

## P1.1 `EXECUTION_TREES.md` §2.C/§2.D are known-wrong — MEASURED

Still on disk today:

```
193:├─ 2.D  ═══ FRONTEND OPTION (a) — FULL AGGREGATE WORKBENCH ═══     [4-8 weeks]
194:│    ├─ 2.D.0 SPIKE P3 FIRST ⚠️ GATE …                                   [3 days]
293:           │  ⬥ FORK — decide HERE, not now   │
296:Week 5     2.D.0 SPIKE P3 (3 days)
```

**Every line of that is false.** The Aggregate Workbench is DONE — `AGGREGATE_WORKBENCH_PLAN.md`
records P0/P1/P4/P6/P7/Polish complete, and AW-P2 (`cd3cbcf`, `7e1096e`), AW-P3 (`ff4acba`), AW-P5
(`0762536`) all closed afterwards, reconciled by `88e28a1`. There is no P3 spike to run, no 4–8
weeks of work, and **no fork** — 2.C and 2.D cover disjoint screen classes.

This is the exact failure mode the register's machine-contract header exists to prevent, one level
up: a planning document that a future session will action as written.

**Fix.** Replace §2.C **and** §2.D with the single block below (also in
`FRONTEND_STRATEGY_PLAN.md` §0.5):

```
├─ 2.CD ═══ FRONTEND STRATEGY — contract, coverage, provenance ═══   [~3 wks]
│    ⚠️ The old 2.C-vs-2.D "fork" does not exist. They cover disjoint screen
│       classes and share one substrate. See docs/FRONTEND_STRATEGY_PLAN.md.
│    ✅ Aggregate Workbench is DONE (2026-07-11 … 07-25) — see
│       docs/architecture/AGGREGATE_WORKBENCH_PLAN.md. It covers the
│       master-detail-detail TRANSACTION class only.
│
│    F1  Screen taxonomy — which primitive covers which class        [1 day] ★★
│    F2  Contract substrate — `invocations` catalog + bundle + docs   [5 days]
│    F3  Provenance — one manifest, three producers                   [4 days] ★
│    F4  Impact gate — a field rename names the screens it breaks     [2 days] ★★
│    F5  Workbench: re-verify + 4 residuals (DONE, not to-build)      [2.5 days]
│    F6  Coverage roadmap — build only what F1 proves recurs          [gated]
```

Also update the critical-path diagram (§"The critical path") — the Week-5 fork disappears.

**Effort.** ⚡ 20 min. **Priority: do this first** — it is the only item that actively misleads.

## P1.2 `AGGREGATE_WORKBENCH_PLAN.md` has no `STATUS:` header

MEASURED: no `> **STATUS:**` line in its first 8 lines, so
`check-register-consistency.py`'s planning-document check (now 10/10) does not cover it. It still
reads *"Reconciled 2026-07-12"* while three phases closed after that date.

```markdown
> **STATUS: EXECUTED.** All phases delivered 2026-07-11 … 2026-07-25 (P0/P1/P4/P6/P7/Polish;
> AW-P2 `7e1096e`, AW-P3 `ff4acba`, AW-P5 `0762536`, reconcile `88e28a1`).
> Kept as the authoritative design record. Residuals: `docs/FRONTEND_STRATEGY_PLAN.md` F5.
```

**Effort.** ⚡ 10 min. Makes the gate 11/11.

## P1.3 Sync local `main`

```powershell
git checkout main; git pull; git checkout beta1-vision-spine
```

**Effort.** ⚡ 2 min.

---

# Part 2 — 🔴 NEW: created by going public

**These did not exist as gaps 48 hours ago. The repo went public with none of them in place.**

## P2.1 🔒 No `SECURITY.md` — no disclosure path

MEASURED: `SECURITY.md` **missing**.

This project just closed **five** real security findings (REG-48 delete-authz ordering, REG-50
Postgres metadata fail-open, REG-51 pack provenance, REG-52 tenant normalization, REG-53 maxLength),
three of them surfaced by external AI review under ADR-0009. It is now a public repo with an
Apache-2.0 licence, a JWT/tenant-isolation/row-level-authz surface, and **nowhere for a researcher to
report a vulnerability except a public issue.**

That is the single highest-consequence gap created by publishing.

**PROPOSED `SECURITY.md`:**

```markdown
# Security Policy

## Supported versions
NPDev is **pre-1.0**; only the latest tag (`beta1.2`) receives fixes. See `BREAKING.md`.

## Reporting a vulnerability
**Do not open a public issue.** Use GitHub's private vulnerability reporting
(Security → Report a vulnerability) on this repository, or email <ADDRESS>.

Please include: affected version/commit, a reproduction, and the impact you believe it has.
We aim to acknowledge within 5 working days.

## Scope
In scope: the platform (generator, kernel, adapters, runtime host) and the code it generates —
in particular authorization, tenant isolation, schema migration, and the external-AI delegation
surface (ADR-0009).

Out of scope: the sample applications under `NPDevSamples/`, `AppGen/apps` definitions, and
committed **test** fixtures (see "Test keys" below).

## Test keys
`**/src/test/resources/npdev/security/test-jwt-*.pem` are throwaway RSA keypairs generated for
unit tests. They protect nothing, are used by no deployment, and are committed deliberately so
tests run without setup. Reports about them will be closed as out of scope.

## What we have already reviewed
Adversarial review history and known accepted boundaries:
`docs/NPDEV_OPEN_ITEMS_REGISTER.md`, `docs/ACCEPTED_BOUNDARIES.md`,
`docs/SECURITY_PATTERN_SWEEP_2026-07.md`.
```

**Also enable GitHub private vulnerability reporting** (Settings → Security). Free, and it is the
mechanism the file points at.

**Effort.** S (1 hr incl. the settings toggle). **Priority: highest in Part 2.**

## P2.2 Committed private keys will generate automated reports

MEASURED — five tracked key/secret-ish files, e.g.:

```
NPDevRuntimeHost/src/test/resources/npdev/security/test-jwt-private.pem     (28 lines, real PKCS#8 RSA)
NPDevKernel/adapters/runtime-validation/src/test/resources/.../test-jwt-private.pem
```

They are almost certainly harmless test fixtures — filename and `src/test/resources` path both say
so. **But on a public repo they will trigger GitHub secret scanning and drive-by researcher
reports**, and there is no marker beside them saying "disposable."

**Fix (do not delete them — tests need them):**
1. `README.md` in each `security/` fixture dir: *"Throwaway keypair, generated for tests, protects
   nothing, used by no deployment. Do not report."*
2. The `SECURITY.md` scope clause above (already drafted).
3. Confirm no non-test code path can load them — grep for the filenames outside `src/test`.

**Effort.** ⚡ 30 min.

## P2.3 No contributor scaffolding

MEASURED: `CONTRIBUTING.md` ❌ · `CODE_OF_CONDUCT.md` ❌ · `.github/ISSUE_TEMPLATE/` ❌
(`PULL_REQUEST_TEMPLATE.md` ✅ exists).

`CONTRIBUTING.md` matters most here and is unusually easy to write, because this project's
conventions are already unambiguous and enforced by gates:

- build output goes to `D:\WorkSpace\NPDev\Build` / `$NPDEV_BUILD_ROOT`, **never** in-repo
- evidence goes outside the repo
- no `git add .`; stage by path
- every breaking change ships its codemod in the same commit (`BREAKING.md`)
- `check-register-consistency.py` + `run-ai-knowledge-gate.ps1` must pass
- `docs/NPDEV_OPEN_ITEMS_REGISTER.md` is a **machine contract** — see its own header

An issue template that asks for "app model + generated output + which gate failed" would save a
round-trip on every future report.

**Effort.** S (2 hr).

## P2.4 ★ Nobody has been told

The repo is public. **That is not the same as anyone knowing it exists**, and TREE 3's 3.2 — *get 3
real humans using it* — remains open and remains the highest-value unknown in the project.

Every strategic question still outstanding (is the hand-written-UI gap a dealbreaker; is the durable
flow engine the killer feature or an unused subsystem; does anyone want to author a model or only to
prompt an agent) is answered by users and by nothing else. Four days of TREE 1 bought the ability to
publish honestly; publishing without telling anyone banks none of it.

**PROPOSED — smallest useful version:**
1. Repo description + topics (`spec-driven-development`, `code-generation`, `spring-boot`,
   `low-code`, `workflow-engine`, `schema-migration`).
2. A short written pitch — the SDD framing from the README, plus the three differentiators
   (schema evolution / durable flows / AI-authoring substrate) and the honest UI limitation.
3. **Show it to 3 specific people** who fit a named scenario (a GeneXus/legacy-4GL shop, an
   internal-tools team, an AI-app-builder skeptic). Not a broadcast — three conversations.
4. Record what they hit in the first hour in `docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`
   (the template already exists, `archive/programme-history/`).

**Effort.** ⬥ owner. **This is the item everything else is downstream of.**

---

# Part 3 — 🟡 Structural residuals from the decomposition

## P3.1 ★ The allowlist is still path-keyed — the landmine is documented, not defused

The 37 orphaned verdicts were the symptom. The cause is that
`security-pattern-sweep-allowlist.json` fingerprints **by file path**, so any future refactor that
moves code silently drops its clearances — and the sweep then either re-flags 37 known-safe patterns
(noise, which trains people to ignore it) or, worse, someone re-clears them in bulk without tracing.

Three more splits are plausible (P3.2 below), so this recurs.

**PROPOSED fix — key by content, not location:**

```jsonc
{
  "fingerprint": "sha256:<normalized matched snippet>",   // survives file moves
  "path": "NPDevKernel/.../GeneratedCrudRuntimeSupport.java",  // informational only
  "rule": "sql-string-concat",
  "clearedBy": "REG-16 Postgres adapter review",
  "clearedAt": "2026-07-21",
  "reason": "identifier passed through SqlIdentifierSupport.safeSqlIdentifier"
}
```

Match on `fingerprint` first, fall back to `path` for one release, and have the sweep **report**
(not fail) when a fingerprint matches at a new path — that is a move, and it should say so.

**RED-first proof:** re-run the sweep against the pre-2.B.3 tree, move the file, confirm the
clearance survives.

**Effort.** M (1 day). **Value: prevents a recurring 37-item manual trace.**

## P3.2 Three files remain above the plan's smell threshold

MEASURED, post-split:

| File | Before | After | Δ | Target |
|---|---|---|---|---|
| `SemanticValidator` | 4,244 | **202** | −95% | ✅ |
| `TrustedSourceEmitter` | 3,782 | **212** | −94% | ✅ |
| `SchemaLifecycleExecutor` | 3,739 | **2,120** | −43% | ⚠️ >800 |
| `KernelRunner` | 4,423 | **3,071** | −31% | ⚠️ >800 |
| `GeneratedCrudRuntimeSupport` | 5,125 | **3,651** | −29% | ⚠️ >800 |

The plan's own wording was *"Target ≤ 800 lines per resulting file. **Not a hard gate; a smell
threshold.**"* So this is a **soft miss, not a broken DoD** — and the two files that mattered most
for correctness (`SemanticValidator`, and `SchemaLifecycleExecutor` gaining a real `SchemaPass`
decomposition) got the deepest treatment.

**Recommendation: do not chase this now.** A second decomposition pass is pure cost until something
needs to change inside those files. Revisit `GeneratedCrudRuntimeSupport` (3,651, the largest
remaining) only when F2/F3 work touches panel row ops. Record the decision so it is not re-litigated.

**Effort.** ⚡ (a decision + one line in the plan).

---

# Part 4 — 🔵 Not started

## P4.1 The frontend work has no home in the repo

MEASURED: `docs/` contains no `FRONTEND_STRATEGY_PLAN.md`, no `SCREEN_TAXONOMY.md`, no `UI_CONTRACT.md`.

Staged and ready to move in:

```
<scratchpad>/FRONTEND_STRATEGY_PLAN.md          708 lines — unified 2.C+2.D, supersedes both
<scratchpad>/2C_CONTRACT_PATH_PLAN.md           superseded, keep for the UI_CONTRACT.md draft
<scratchpad>/2D_AGGREGATE_WORKBENCH_PLAN.md     superseded, keep for the F5 residual detail
<scratchpad>/helpers/  preflight-accessors.py · extract-routes.py · prototype-invocations.py
                       classify-screens.py · bootstrap-panel-provenance-v2.py · README.md
<scratchpad>/2c-staging/ check-panel-provenance-impact.py (calibration green)
                         bootstrap-infer-panel-provenance.py (v1)
                         wmsoffice-proxy-bundle.json + 3 real *.panel.json drafts
```

**Move `FRONTEND_STRATEGY_PLAN.md` into `docs/`** (it carries a `STATUS: ACTIVE` header, so the gate
will pick it up → 12/12) and the helpers into `scripts/frontend/` or leave them staged until F1
starts. **None of the helpers has been executed** — `preflight-accessors.py` is the first to run,
because it validates the plan's own Java sketch (it already found `getInvocableProcedures` does not
exist).

**Effort.** ⚡ 15 min to move; F1–F6 ≈ 3 weeks.

## P4.2 ★★ CORE C-3 — the durable-workflow demo still does not exist — **DONE 2026-07-28**

MEASURED: no sample app demonstrates suspend/restart/resume.

`docs/FLOWS.md` (T1.10) shipped and documents the engine well. **C-3 is the proof**: a flow parks on
`awaitEvent` → `docker restart` → the flow resumes and completes. You have a capability Temporal and
Camunda charge money for, it is now visible in a public repo, and there is still **zero runnable
evidence** an evaluator can execute.

`FLOWS.md` itself records the honest gap: *"no real sample model in this repo uses `FOR_EACH` or
`onFailure` (compensation)."*

This remains the highest ratio of evaluator-impact to effort anywhere in the backlog — and it just
got more valuable, because there is now an audience.

**Effort.** 1 week. **Priority: highest non-doc item in this plan.**

## P4.3 F5-V.2 — the workbench live re-verification — **DONE 2026-07-28**

Every workbench "verified live" claim dated 2026-07-12. **~180 commits** had landed since,
including the five security fixes and all five file splits. Step 11 of the F5 scenario (delete a band
row → commit → reload) is the aggregate-cascade check REG-48's reordered `delete()` never got.

**Result:** re-verified live on WmsOffice's real Aggregate Workbench (`npdev-workbench/ExpedicaoWorkbench.html`
/ `RecebimentoWorkbench.html`). 7/11 steps passed, including step 11 — the REG-48 delete-cascade
ordering holds, confirmed both in-browser and via a direct server-side check on the deleted row.
3/11 steps weren't exercisable because WmsOffice's model doesn't declare `recompute`/`bandPickers`/
`actions` — an authoring gap on that one app, not a platform regression. One low-severity cosmetic
finding filed (REG-60). Detail + evidence: `FRONTEND_STRATEGY_PLAN.md` F5-V.2.

**Effort.** 4 hr.

---

# Part 5 — Priority

```
NOW (≈2 hr)          P1.1 fix EXECUTION_TREES.md §2.C/§2.D   🔴 actively misleading
                     P1.2 STATUS header on AGGREGATE_WORKBENCH_PLAN.md
                     P1.3 git pull main
                     P4.1 move FRONTEND_STRATEGY_PLAN.md into docs/

THIS WEEK (≈1 day)   P2.1 SECURITY.md + enable private vuln reporting   🔒
                     P2.2 test-key README markers
                     P2.3 CONTRIBUTING.md + issue templates

THEN                 P4.2 CORE C-3 durable-workflow demo  ★★   [1 wk]
                     P3.1 content-keyed allowlist          ★    [1 day]
                     P4.3 workbench live re-verify              [4 hr]

OWNER                P2.4 tell 3 specific people  ⬥  ← everything strategic is downstream

GATED               F1…F6 frontend strategy  [~3 wks]  — start with F1 + preflight-accessors.py

DECIDED, NOT DOING  P3.2 second decomposition pass — soft target, revisit only on demand
```

## Why this order

**P1 first** because a wrong plan document is worse than a missing one: the next session — human or
AI — will action §2.D as written and spend weeks rebuilding a finished feature.

**P2 next** because the repo is public *now*. `SECURITY.md` is not paperwork on a codebase whose last
five closed items were security findings and whose disclosure path is currently "open a public issue."

**P4.2 before F1–F6** because the frontend programme is three weeks and the durable-workflow demo is
one, and the demo is what makes a visitor understand what NPDev is. Documentation says you have a
durable workflow engine; a container you can `docker restart` mid-flow *proves* it.

**P2.4 is the owner's and gates the rest.** Publishing without telling anyone banks none of TREE 1's
four days.

## Definition of done

- [x] `EXECUTION_TREES.md` has no reference to a 2.C/2.D fork, a P3 spike, or 4–8 weeks of workbench work
- [x] `AGGREGATE_WORKBENCH_PLAN.md` declares `STATUS: EXECUTED`; gate reports 12/12 planning documents declaring a status
- [x] local `main` == `origin/main`
- [x] `SECURITY.md` present; GitHub private vulnerability reporting enabled
- [x] Every committed test key sits beside a README saying it is disposable; `SECURITY.md` scopes them out
- [x] `CONTRIBUTING.md` + at least one issue template
- [x] `FRONTEND_STRATEGY_PLAN.md` in `docs/`, picked up by the register gate
- [x] A sample app parks a flow on an event, survives a real process restart, and resumes — with a runnable script (`NPDevSamples/durable-workflow-demo`, `run-durable-resume-demo.ps1`)
- [x] Allowlist fingerprints survive a file move, proven RED-first
- [x] Workbench re-verified live on both aggregates, incl. the band-row delete cascade (7/11 steps pass; 3/11 not exercisable due to a model-authoring gap, not a regression)
- [ ] **3 people outside this machine have tried it, and what they hit is written down** — owner-only, not for the assistant to do

---

*Companions: `docs/EXECUTION_TREES.md` (needs P1.1) · `docs/FRONTEND_STRATEGY_PLAN.md` (staged) ·
`docs/architecture/AGGREGATE_WORKBENCH_PLAN.md` (needs P1.2) · `docs/FLOWS.md` ·
`docs/NPDEV_OPEN_ITEMS_REGISTER.md` · `docs/SECURITY_PATTERN_SWEEP_2026-07.md` (P3.1) ·
`BREAKING.md`.*
