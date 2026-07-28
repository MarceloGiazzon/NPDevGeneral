# NPDev — Execution Trees

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

**Zero dependencies. ~4 working days. Unblocks publishing, which unblocks everything strategic.**

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
├─ 2.C  ═══ FRONTEND OPTION (b) — CONTRACT PATH ═══                [2 weeks] ⭐ RECOMMENDED
│    │    ~80% ALREADY BUILT. compiled-metadata.json ships 11 catalogs
│    │    (concepts · procedures · panels · domainTypes · fields · enums ·
│    │     references · actions · transitions · layout · validation), served
│    │     permission-filtered at /api/v1/runtime/metadata/ui/*.
│    │    `layout` already carries visibleWhen/enabledWhen/readonlyWhen/requiredWhen.
│    ├─ 2.C.1 Emit `routes` catalog (the 12th) — method/path/params/permission [2 days]
│    ├─ 2.C.2 GET /metadata/ui/bundle?concept=X — one call + modelHash         [1 day]
│    ├─ 2.C.3 docs/UI_CONTRACT.md + JSON Schema + example agent prompt         [2 days]
│    ├─ 2.C.4 panel.json provenance manifest (reads/writes/calls/generatedFrom)[3 days]
│    ├─ 2.C.5 Impact gate: build FAILS on a stale field reference in a screen  [2 days]
│    └─ 2.C.6 PROOF: regenerate inventario.html from the bundle alone          [2 days]
│         → Delivers: rename a field, learn exactly which screens break, regenerate them.
│           No competitor can do this. It is the schema-evolution insight, one layer up.
│
├─ 2.D  ═══ FRONTEND OPTION (a) — FULL AGGREGATE WORKBENCH ═══     [4-8 weeks]
│    ├─ 2.D.0 SPIKE P3 FIRST ⚠️ GATE — how much do the existing            [3 days]
│    │        visibleWhen/enabledWhen/readonlyWhen/requiredWhen already give us?
│    │        Answer decides 4 weeks vs 8 weeks. Do not start 2.D.2 before this.
│    ├─ 2.D.1 P5 Slots — hand-written HTML inside a generated region  [3 days] ★
│    │        DO THIS EVEN IF (a) IS DROPPED. It closes the governance hole alone.
│    ├─ 2.D.2 P1 Region taxonomy (header/filters/grid/detail/actions)  [3 days]
│    ├─ 2.D.3 P2 N-level nesting + selection cascade                   [1 week]
│    ├─ 2.D.4 P3 Two-tier reactivity ⚠️ DOES NOT COMPRESS             [1-3 weeks]
│    │        Field-level (recompute a total) vs region-level (reload children).
│    │        This is design uncertainty, not implementation. More tokens ≠ faster.
│    ├─ 2.D.5 P4 Lifecycle hooks + cross-region transactional actions  [1 week]
│    └─ 2.D.6 P6 ACCEPTANCE: rebuild centro-trabalho.html from model ONLY [1 week]
│             Count what you CANNOT express. ≤2 → ship it. ≥9 → slots were the product.
│
├─ 2.E  Ledger migration: prose register → ledger/items/*.yml       [3 days]
│    │    Unwires the 13 process docs currently hard-wired into gates.
│    │    docs/OPEN_ITEMS.md becomes GENERATED, never hand-edited.
│    └─ Unblocks 3.4.
│
└─ 2.F  Durable-workflow demo app                                  [1 week] ★★
         A flow parks on awaitEvent → `docker restart` → flow resumes and completes.
         You have a capability Temporal charges for and zero public evidence it exists.
         Highest ratio of (evaluator impact) / (effort) in the entire plan.
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
│        ⛔ BLOCKED BY: T1.4 · T1.5 · T1.6 · T1.8 · T1.9 (i.e. ~4 days of TREE 1)
│        → THE single unblock in this whole document. Everything strategic is downstream.
│
├─ 3.2  Get 3 real humans using it
│        ⛔ BLOCKED BY: 3.1
│        → Answers the questions no amount of internal review can:
│          Is the hand-written-HTML gap a dealbreaker or a shrug?
│          Is the durable flow engine the killer feature or an unused subsystem?
│          Does anyone want to author a model, or only to prompt an agent?
│
├─ 3.3  Fix REG-40 (additive migration never CREATEs new tables)
│        ⛔ NOT technically blocked — but scope AFTER 2.B.4 (ColumnFacts), or you
│          fix it inside a 3,739-line file and redo it during the split.
│        → User impact is high: "add a new entity to an existing app" currently fails.
│
├─ 3.4  Archive the remaining 13 gate-hardwired process docs
│        ⛔ BLOCKED BY: 2.E
│
├─ 3.5  Postgres adapters in the PR gate (currently nightly only)
│        ⛔ BLOCKED BY: REG-4 flake root cause (load-sensitive, still unresolved)
│
├─ 3.6  Bounded contexts / multi-namespace models
│        ⛔ BLOCKED BY: 2.A — land it in DSL 2.0 or pay for it twice
│        → Decides whether NPDev can model a company or only a department.
│
├─ 3.7  Aggregate transactional boundary enforcement (the core DDD rule:
│        one aggregate = one transaction = one consistency boundary)
│        ⛔ BLOCKED BY: 2.B.5 — needs a clean place to live
│        → Today `aggregates` carry `ownership` but nothing enforces it. Enforcing it
│          makes the construct load-bearing instead of descriptive.
│
└─ 3.8  Agent-driven frontend generation, productized
         ⛔ BLOCKED BY: 2.C (the whole contract path)
         → After 2.C this is a prompt plus a CLI command, not a project.
```

---

## The critical path

```
Week 1     TREE 1 (all of it, ~4 days)        ──────────►  🚀 3.1 PUBLISH
              ║                                                  │
              ║                                                  └──► 3.2 real users
Week 2     2.A DSL 2.0  ⚠️ must precede real production users          (feedback starts)
              ║
Week 3-4   2.C CONTRACT PATH        ║  2.F workflow demo (parallel, different subsystem)
              ║
           ┌──╨──────────────────────────────┐
           │  ⬥ FORK — decide HERE, not now   │
           └──┬──────────────────────────────┘
              ║
Week 5     2.D.0 SPIKE P3 (3 days)
              ║
        ┌─────╨─────────┐
        ▼               ▼
  P3 is small       P3 is real
  → run 2.D full    → 2.D.1 slots only (3 days), then STOP.
    (~4 weeks)         Contract + slots already covers the need.
```

### Why the fork is at Week 5 and not today

**2.C is required under both options.** Option (a) without a contract still leaves hand-written
screens ungoverned. Option (b) *is* the contract. So four productive weeks pass before the decision,
and you arrive at it holding two pieces of evidence you do not have now: the P3 spike result, and —
if 3.1/3.2 landed — actual human reaction to the generic admin UI.

**The relationship in one line:** the Workbench *generates* screens; the contract makes screens
*safe*. You need the second regardless. You need the first only where the second is not enough.

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
