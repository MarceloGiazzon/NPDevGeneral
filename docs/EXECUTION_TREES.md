# NPDev — Execution Trees

> **STATUS UPDATE (2026-07-28):** TREE 1 is fully DONE (`docs/TREE1_LAUNCH_UNBLOCK_PLAN.md`, all 16
> tasks) — the repo is merged to `main` (`89eb945`), tagged `beta1.2`, and **public**. TREE 2's 2.A
> (DSL 2.0) and 2.B (all five god-file splits, including 2.B.5/CORE C-4) are also DONE
> (`docs/DSL2_AND_DECOMPOSITION_PLAN.md`). The 2.C/2.D frontend fork below never existed as a real
> choice — see the corrected 2.CD section and `docs/FRONTEND_STRATEGY_PLAN.md`. What's actually next:
> `docs/POST_PUBLIC_PLAN.md`.
>
> **Written:** 2026-07-27, against branch `beta1-vision-spine` (`97a2491` + uncommitted working tree).
> **Purpose:** one place that says what to do next, grouped by *what blocks it* rather than by
> subsystem. Three trees:
>
> | Tree | Shape | Rule |
> |---|---|---|
> | **[TREE 1 — NOW](#tree-1--now)** | fast, simple, **zero dependencies** | any item can start this hour, in any order |
> | **[TREE 2 — BIG](#tree-2--big)** | multi-week, real design work | needs a dedicated run; don't interleave |
> | **[TREE 3 — BLOCKED](#tree-3--blocked)** | small work, real dependencies | listed with the exact thing that unblocks it |
>
> **The detailed, executable plan for TREE 1 lives in
> [TREE1_LAUNCH_UNBLOCK_PLAN.md](TREE1_LAUNCH_UNBLOCK_PLAN.md).** This document is the map;
> that one is the instructions.
>
> **Status vocabulary:** ⬜ not started · 🟡 in progress · ✅ done · ⛔ blocked · ⬥ decision required.

---

## 0. The one-paragraph thesis

The platform's engineering is stronger than its packaging. Three capabilities are genuinely
differentiated — **schema evolution**, the **durable flow/event engine**, and the
**SDD/AI-authoring substrate**. All three are backend. The honest gap is the UI: for anything past
CRUD you drop out of the model into hand-written HTML that participates in none of the platform's
guarantees. TREE 1 fixes what the project *claims*; TREE 2 fixes what it *is*; TREE 3 is everything
waiting on one of those two. The fork between the two frontend strategies is deliberately deferred
to Week 5, because **both of them need the same next step**.

---

## 0.1 The CORE track — read this before the trees

> **Owner note, 2026-07-27:** the flow / event / orchestration core is the platform's *mechanics*,
> and earlier drafts of this analysis under-weighted it. That was an analysis error, not a project
> error. This section exists so the core is never again scattered across three trees as incidental
> line items.

**What the core actually is** (verified against source, not documentation):

| Element | Evidence |
|---|---|
| Step kinds implemented | **9** in `FlowStepDefinition` — `AWAIT_EVENT · BRANCH · CAPABILITY_CALL · EMIT_EVENT · FOR_EACH · INVARIANT_CHECK · MAP · RETURN · SCHEDULE_EVENT` (not `PRE` — that's a checkpoint value of `INVARIANT_CHECK`, not its own step kind; see `docs/FLOWS.md` §3) |
| Flow-instance statuses | **6** — `RUNNING · WAITING_EVENT · COMPLETED · FAILED · FAILED_PERMANENT · STUCK` |
| Durable suspend/resume | `JdbcFlowInstanceStore` · `NpdevFlowInstanceTable` · `ResumeBootstrapRunner` |
| Event correlation + ownership | `JdbcCorrelationOwnershipStore` / `PostgresCorrelationOwnershipStore` |
| Compensation (saga) | LNCH-17 — a crash mid-compensation resumes into *finish compensating*, not re-run forward |
| Resumable `forEach` | LIFT-LOOP-P2 — one flat step index; completed iterations skipped on resume; nested `AWAIT_EVENT` rejected at compile time |
| Scheduling | `flowSchedule` cron + tenant scope; `ResumeSchedulerRunner` |
| Hooks | before/after step injection without editing the flow |
| Orchestration rules | event-triggered `create` / `callCapability` / `scheduleEvent` |
| Rule profiles | lifecycle phases — `beforeCommit · afterCommit · interactive · headless · query` |
| Ports | `FlowEngine` (`startFlow` / `resumeFlow` / `ResumeOutcome`) — a clean hexagonal port |

**This is a durable, event-correlated, compensating workflow engine** — the category Temporal,
Camunda, and Zeebe sell — embedded in a code generator behind a declarative front end. It is
arguably the hardest thing in the repo and the least visible.

**The problem is not the engine. It is that the engine has no surface:**

- **One documentation page** (`SCHEDULED_FLOWS.md`) covers the scheduling corner only. No `FLOWS.md`.
- **No demo.** `medium-expense-approval` — the sample literally named for approval flows — is
  **1 concept, 5 fields, 1 flow**. Nothing in the sample corpus shows a flow parking on an event,
  surviving a restart, and resuming.
- **No discoverability.** `FlowEngine.java` is a correct 26-line port, but nothing in it points at
  the implementation, which lives inside `KernelRunner.java`'s 4,423 lines. Anyone exploring by
  filename finds the interface and stops.
- **Its adversarial review is filed as bookkeeping.** `REG16_FLOW_ORCHESTRATION_ADVERSARIAL_REVIEW.md`
  now sits in `archive/programme-history/` among 28 process docs, where it reads as process rather
  than as evidence for a headline capability.

**Consolidated core workstream — the same items, gathered so they are visible as one effort:**

| ID | Task | Tree | Effort | Value |
|---|---|---|---|---|
| **C-1** | `docs/FLOWS.md` — state machine, all 9 step kinds, `awaitEvent` in depth, compensation, limits | TREE 1 · T1.10 | 1 day | ★★ |
| **C-2** | `FlowEngine` javadoc → point at the implementation and at `FLOWS.md` | TREE 1 · T1.16 | ⚡ | discoverability |
| **C-3** | Durable-workflow demo app: park on event → `docker restart` → resume → complete | TREE 2 · 2.F | 1 wk | ★★ |
| **C-4** | Split `KernelRunner` into step-kind classes; make `FlowEngine`'s implementation findable | TREE 2 · 2.B.5 | 1 wk | structural |
| **C-5** | Promote the flow/orchestration adversarial review out of `archive/` and cite it from `FLOWS.md` | TREE 1 · T1.10 | ⚡ | evidence |
| **C-6** | Collapse the 14 synonym `flowStep.type` values (23 → 9, matching the 9 real kinds) | TREE 2 · 2.A.1 | in 2.A | DSL clarity |
| **C-7** | Aggregate transactional boundary enforcement (one aggregate = one transaction) | TREE 3 · 3.7 | ⛔ 2.B.5 | DDD correctness |

**Recommended minimum this month: C-1 + C-2 + C-5** — that is one day plus fifteen minutes, and it
converts the platform's least-visible strength into something an evaluator can find. **C-3 is the
proof**, and it is the single highest ratio of evaluator-impact to effort anywhere in this document.

**Why the core matters strategically, not just technically.** Every AI app-builder currently shipping
(Lovable, v0, Bolt, Replit Agent) can generate a beautiful screen. **None of them has durable
workflow, and none can get it cheaply** — a resumable, compensating, event-correlated engine is a
6–12 month backend effort that does not demo well, and their incentives are demos. The frontend gap
in §2.C/2.D is real; the core is the thing on the other side of the trade that they cannot answer.

---

## Tree 1 — NOW

**✅ ALL 16 TASKS DONE (2026-07-28).** Tree diagram below kept as historical record of the plan as
written; see [TREE1_LAUNCH_UNBLOCK_PLAN.md](TREE1_LAUNCH_UNBLOCK_PLAN.md) for what actually happened.

Full detail, per-task commands, and acceptance criteria: **[TREE1_LAUNCH_UNBLOCK_PLAN.md](TREE1_LAUNCH_UNBLOCK_PLAN.md)**.

```
TREE 1 — NOW  (~4 days)
│
├─ PHASE A — Stop the bleeding (do first, ~2 hours)
│   ├─ T1.1  Commit the working tree                                    [30 min] 🔴
│   ├─ T1.2  Wire :generator:behaviorTest into both workflows           [10 min] 🔴
│   └─ T1.3  Gate: every Test task must appear in ≥1 workflow           [2 hr]
│
├─ PHASE B — Make the claims true (~1 day)
│   ├─ T1.4  Resolve the 32 deferred editor panels                      [1 hr]
│   ├─ T1.5  Fix CLAUDE.md "30+ panels" → the real number              [5 min]
│   ├─ T1.6  Merge beta1-vision-spine → main, re-tag                    [1 hr] 🔴
│   └─ T1.7  Register machine-contract warning header                   [15 min]
│
├─ PHASE C — Build the front door (~2 days)
│   ├─ T1.8  Rewrite README around the SDD framing                      [4 hr] ★
│   ├─ T1.9  Breaking Change Charter                                    [30 min]
│   └─ T1.10 docs/FLOWS.md — the durable engine finally gets a door     [1 day] ★★
│
├─ PHASE D — Owner decisions (blocking, not AI-substitutable)
│   └─ T1.11 Fill 4 verdict lines in DECISION_BRIEFS_2026-07.md         [⬥ owner]
│
└─ PHASE E — Cheap hardening (optional, same window)
    ├─ T1.12 Editor vitest into the PR gate                             [1 hr]
    ├─ T1.13 TruthLevel: make load-bearing or delete                    [⬥ decision]
    ├─ T1.14 Verify the LNCH-4 auth-sibling claim (named class absent)  [2 hr] 🔒
    ├─ T1.15 Split SemanticValidator (137 statics, 0 fields)            [1 day]
    └─ T1.16 FlowEngine javadoc → implementation + FLOWS.md  [CORE C-2]  [⚡]
```

**Core coverage inside TREE 1:** T1.10 delivers **C-1** and **C-5**; T1.16 delivers **C-2**.
See [§0.1 — The CORE track](#01-the-core-track--read-this-before-the-trees).

**Exit criterion for TREE 1:** the repo can be made public without any statement in it being false.

---

## Tree 2 — BIG

**Multi-week. Real design risk. Do these one at a time, on a branch, with nothing else interleaved.**

**✅ 2.A and 2.B (all five splits, incl. 2.B.5/CORE C-4) are DONE (2026-07-27/28)** — see
[DSL2_AND_DECOMPOSITION_PLAN.md](DSL2_AND_DECOMPOSITION_PLAN.md) for what actually happened,
including two corrected premises (2.A.0's createConcept/updateConcept sugar, and 2.B.4's
`SchemaLifecycleExecutor`, whose "build ColumnFacts+SchemaPass from scratch" framing below turned
out stale — a separate already-complete initiative had solved it). Tree diagram below kept as the
plan's original record.

```
TREE 2 — BIG
│
├─ 2.A  DSL 2.0 — the aggression play                              [1 week] ⚠️ TIME-BOXED
│    │    Must land BEFORE the first external user has an app in production.
│    │    Cost now: one regeneration. Cost after: 50-100×.
│    ├─ 2.A.1 Collapse flowStep type 23 → 9 canonical values
│    │        (14 of 23 are pure synonyms — 61% alias debt;
│    │         includes `generatedAction` AND `generated_action`)
│    ├─ 2.A.2 Kill 12 field-alias pairs
│    │        flowStep:  capability|cap · operation|op · output|out · awaitEvent|awaitRef
│    │        flowHook:  position|at · targetStep|target
│    │        orchAction:operation|op · concept|targetConcept · event|eventName ·
│    │                   capability|capabilityName · map|fieldMap
│    │        orchRule:  action|actions        ← scalar-or-list, worst kind
│    ├─ 2.A.3 Build `npdev migrate --dsl-2` codemod
│    ├─ 2.A.4 Regenerate all 20 app definitions (the corpus IS the regression test)
│    └─ 2.A.5 Adopt permanently: every breaking change ships its codemod in the same commit
│
├─ 2.B  God-file decomposition                                     [2-3 weeks]
│    │    KEY FACT: all five have **zero instance fields** and are dominated by
│    │    `private static` helpers (458 across the five). They are stateless function
│    │    libraries, not stateful god objects → the compiler proves each move correct.
│    │    Rule: ONE pass, ZERO behavior changes, no bug fixes mixed in.
│    ├─ 2.B.1 SemanticValidator      4,244 L · 137 statics → 6 classes   [1 day]
│    ├─ 2.B.2 TrustedSourceEmitter   3,782 L ·  77 statics → by artifact [1 day]
│    ├─ 2.B.3 GeneratedCrudRuntimeSupport 5,125 L · 17 nested types      [2 days]
│    │        (also: it lives in adapters/expression-cel — wrong module, fix while there)
│    ├─ 2.B.4 SchemaLifecycleExecutor 3,739 L · 100 methods · 8 passes    [3 days] ★
│    │        → ColumnFacts computed ONCE + SchemaPass per pass. This is REG-6's
│    │          original design. Correctness work, not tidiness.
│    └─ 2.B.5 KernelRunner           4,423 L ·  84 statics → step classes [1 week]
│             → FlowEngine.java (currently a 26-line stub) becomes the real thing
│
├─ 2.CD ═══ FRONTEND STRATEGY — contract, coverage, provenance ═══   [~3 wks]
│    │    ⚠️ The old 2.C-vs-2.D "fork" does not exist. They cover disjoint screen
│    │       classes and share one substrate. See docs/FRONTEND_STRATEGY_PLAN.md.
│    │    ✅ Aggregate Workbench is DONE (2026-07-11 … 07-25) — see
│    │       docs/architecture/AGGREGATE_WORKBENCH_PLAN.md. It covers the
│    │       master-detail-detail TRANSACTION class only.
│    │
│    │    F1  Screen taxonomy ✅ DONE 2026-07-28 — docs/SCREEN_TAXONOMY.md. Measured, not
│    │        guessed: zero classes reach the ≥2-app/≥2-screen promotion threshold today.
│    │        operator-console is the strongest single-app signal (WmsOffice, 5/13 screens)
│    │        but is one real second app away from qualifying -- not built as a primitive yet.
│    │    F2  Contract substrate — `invocations` catalog + bundle + docs   [5 days]
│    │        F2.1 ✅ DONE 2026-07-28 -- invocations catalog shipped, 252 real entries
│    │        verified live against WmsOffice with zero path mismatches (found + fixed a real
│    │        bug in extract-routes.py along the way -- see register).
│    │        F2.2 ✅ DONE 2026-07-28 -- bundle endpoint shipped, composing fields/actions
│    │        verbatim (anti-drift proven live against WmsOffice) + 6 unfiltered catalogs;
│    │        found invocations+transitions were never split into their own manifest files
│    │        (fixed, 9->11 manifests). modelHash reuses SchemaLifecycleExecutor's fingerprint.
│    │        F2.3 ✅ DONE 2026-07-28 -- docs/UI_CONTRACT.md, schemas/ui-contract.schema.json
│    │        (validated against a real live bundle response), docs/ai/UI_GENERATION_PROMPT.md.
│    │        F2 (contract substrate) is now fully closed.
│    │    F3  Provenance ✅ DONE 2026-07-28 -- schemas/panel-provenance.schema.json,
│    │        ADR-0010, all 3 producers shipped. Generator producer needed real work, not
│    │        "nearly free": CompiledPanel.metadata() was stamped but never serialized
│    │        anywhere (fixed). Human bootstrapper had a real dot-vs-colon id bug (fixed,
│    │        committed as scripts/quality/bootstrap-panel-provenance.py); run for real +
│    │        confirmed by hand against 3 live WmsOffice screens.
│    │    F4  Impact gate ✅ DONE 2026-07-28 -- check-panel-provenance-impact.py shipped,
│    │        calibrated, and money-demo'd for real (simulated field rename against a
│    │        confirmed WmsOffice manifest -> FAIL naming the exact screen, exit 1).
│    │        Correction: NOT wired into run-ai-knowledge-gate.ps1 (that gate is static/
│    │        repo-level; this needs a live authenticated bundle) -- it's a per-app
│    │        post-deploy tool, documented recipe instead of a fragile auto-wired script.
│    │    F5  Workbench: re-verify + 4 residuals (DONE, not to-build)      [2.5 days]
│    │    F6  Coverage roadmap — build only what F1 proves recurs          [gated -- F1 found
│    │        nothing recurs yet, so F6 has nothing to build until that changes]
│
├─ 2.E  Ledger migration: prose register → ledger/items/*.yml       [3 days]
│    │    Unwires the 13 process docs currently hard-wired into gates.
│    │    docs/OPEN_ITEMS.md becomes GENERATED, never hand-edited.
│    └─ Unblocks 3.4.
│
└─ 2.F  Durable-workflow demo app  ✅ DONE 2026-07-28 (0384966, CORE C-3)
         NPDevSamples/durable-workflow-demo + NPDevSamples/scripts/run-durable-resume-demo.ps1
         Park on awaitEvent → hard kill → new JVM → publish → same execution resumes. One command.
         ✅ REG-57 CLOSED 2026-07-28: the 5s pre-kill delay is gone (H2 WRITE_DELAY=0 fix,
            root-caused not guessed -- see register). 3/3 clean with the sleep removed.
         ✅ REG-56 CLOSED 2026-07-28: the notify-approval capabilityCall step is back in the
            demo's model (ExecutionContext.resuming fix -- resume now runs under a trusted
            system role instead of losing the flow's original permission level). 3/3 clean
            across a real kill+restart, capability call included.
         The demo now demonstrates the FULL path, not a narrowed one.
```

### Estimate calibration

Not all work compresses at your measured velocity (483 commits / 46 active days; peak 73/day):

| Work type | Compresses with better models + more tokens? | Why |
|---|---|---|
| **Mechanical** (emit a catalog, split a class, wire an endpoint) | ✅ 3–5× | The spec is known; it is typing |
| **Integrative** (make 4 subsystems agree) | ⚠️ 1.5–2× | Bounded by how fast *you* can read the diff |
| **Design-uncertain** (2.D.4 two-tier reactivity) | ❌ ~1× | You are finding an answer, not implementing one |
| **Wall-clock** (sync libs → regenerate → bootJar → boot → browser-verify) | ❌ 1× | 20–40 min per cycle. Physics. |

**The binding constraint is your review capacity, not token budget.** 73 commits in a day is a real
throughput ceiling on attention; the 32 deferred panels and the 53 process docs are what exceeding it
looks like.

---

## Tree 3 — BLOCKED

**Small work. Each line names the exact thing that unblocks it.**

```
TREE 3 — BLOCKED
│
├─ 3.1  🚀 PUBLISH THE REPO PUBLICLY
│        ✅ DONE 2026-07-28 — repo is public, main merged (89eb945), tagged beta1.2.
│
├─ 3.2  Get 3 real humans using it
│        ⬥ UNBLOCKED, owner action — see docs/POST_PUBLIC_PLAN.md P2.4.
│        → Answers the questions no amount of internal review can:
│          Is the hand-written-HTML gap a dealbreaker or a shrug?
│          Is the durable flow engine the killer feature or an unused subsystem?
│          Does anyone want to author a model, or only to prompt an agent?
│
├─ 3.3  Fix REG-40 (additive migration never CREATEs new tables)
│        ✅ DONE 2026-07-24 (SER-P9) — REG-40 is CLOSED in the register; the fix is
│          recorded twice in DATABASES_AND_MIGRATIONS.md (lines 416, 513).
│
├─ 3.4  Archive the remaining 13 gate-hardwired process docs
│        ⛔ BLOCKED BY: 2.E
│
├─ 3.5  Postgres adapters in the PR gate (currently nightly only)
│        ⛔ STALE BLOCKER, corrected 2026-07-28: this row used to cite the REG-4 flake as an
│        open blocker — REG-4 was CLOSED 2026-07-21 (root cause fixed, not just
│        tolerance-widened; see register §1.4). The REAL, current reason Postgres/full
│        validation stays nightly-only is runtime cost, per `npdev-ci-validation.yml`'s own
│        header: "too slow/expensive for every PR (up to 120min)" — not a flake. Re-scope as
│        a deliberate cost/time tradeoff, or as scoping a fast Postgres PR-subset, not as
│        "blocked."
│
├─ 3.6  Bounded contexts / multi-namespace models
│        ✅ UNBLOCKED — 2.A (DSL 2.0) is DONE 2026-07-27.
│        → Decides whether NPDev can model a company or only a department. Not yet scheduled.
│
├─ 3.7  Aggregate transactional boundary enforcement (the core DDD rule:
│        one aggregate = one transaction = one consistency boundary)
│        ✅ UNBLOCKED — 2.B.5 (KernelRunner split, CORE C-4) is DONE 2026-07-28.
│        → Today `aggregates` carry `ownership` but nothing enforces it. Enforcing it
│          makes the construct load-bearing instead of descriptive. Not yet scheduled.
│
└─ 3.8  Agent-driven frontend generation, productized
         ⛔ BLOCKED BY: 2.CD (the whole contract path, F1-F6) — see docs/FRONTEND_STRATEGY_PLAN.md
         → After 2.CD this is a prompt plus a CLI command, not a project.
```

---

## The critical path

```
Week 1     TREE 1 (all of it, ~4 days)  ✅ DONE 2026-07-28  ──►  🚀 3.1 PUBLISH ✅ DONE
              ║                                                  │
              ║                                                  └──► 3.2 real users  ⬥ owner
Week 2     2.A DSL 2.0  ✅ DONE 2026-07-27                             (feedback starts)
              ║
Week 3-4   2.CD FRONTEND STRATEGY   ║  2.F / C-3 workflow demo (parallel, different subsystem)
              ║                        (2.B god-file split ✅ DONE 2026-07-28, incl. 2.B.5/C-4)
              ║
        (no fork — see docs/FRONTEND_STRATEGY_PLAN.md; the old 2.C-vs-2.D
         decision point never existed as a real choice, see 2.CD above)
```

**There is no Week-5 fork.** The 2.C-vs-2.D framing above was itself wrong (see 2.CD): the
Aggregate Workbench (former "2.D") is DONE, not a 4-8 week bet, and the contract path (former "2.C")
is not an alternative to it — they cover disjoint screen classes (Transaction-class
master-detail-detail vs. everything else) and share one substrate. `docs/FRONTEND_STRATEGY_PLAN.md`
is now the single plan for this area; there is nothing left to decide between.

---

## Sequencing warning

TREE 2 is ~10 weeks of work. TREE 1 is ~4 days.

The project's own history predicts TREE 2 gets done brilliantly and TREE 1 gets deferred — that
pattern has now repeated five times (LNCH-1 five rounds, REG-16-resid six rounds, the schema-engine
rebuild, REG-48/50/51, and REMAINDER_CLOSURE_PLAN). Each was good work. None of it was TREE 1.

**Invert it.** The 4 days of TREE 1 unblock publishing, and publishing is the only source of the
information that decides everything in TREE 2.

---

*Companions: [TREE1_LAUNCH_UNBLOCK_PLAN.md](TREE1_LAUNCH_UNBLOCK_PLAN.md) (executable detail) ·
[NPDEV_OPEN_ITEMS_REGISTER.md](NPDEV_OPEN_ITEMS_REGISTER.md) (bug/gap ledger) ·
[DECISION_BRIEFS_2026-07.md](DECISION_BRIEFS_2026-07.md) (4 open owner verdicts) ·
[OPEN_GAPS_AND_ROADMAP.md](OPEN_GAPS_AND_ROADMAP.md) (runtime/generator items).*
