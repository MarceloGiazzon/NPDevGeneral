# Remediation Plan — audit findings + the blocked tier

> **STATUS: EXECUTED (2026-07-29), except R-O2 which is owner-only.** Every item in the Definition
> of Done below is implemented and live-verified; see
> `docs/REMEDIATION_PLAN_IMPLEMENTATION_NOTES.md` for the evidence trail and where the
> implementation measured differently than this plan's original estimates (R-P2's "13" docs, the
> panel-provenance schema regex bug, the register-parsing edge cases Rule T3 itself surfaced).
> Written 2026-07-28 against `beta1-vision-spine` @ `40835a6` (tree clean, all pushed, repo public,
> tag `beta1.2`).
>
> **Scope:** the 10 findings from the 2026-07-28 deep audit, **plus** the TREE 3 / blocked-tier items
> that were outside that list (2.E full migration, 3.4, 3.5, 3.8).
>
> Facts are **MEASURED** (git, source, filesystem, gates, suites — run 2026-07-28) or **PROPOSED**.

---

# Part 0 — State

## 0.1 Verified healthy

| | |
|---|---|
| Commits | `38b4107→40835a6`, 8 commits, **all pushed**, tree clean |
| DSL suite | **1,080 tests / 0 failures** (was 355) |
| Gates | register-consistency OK · knowledge gate PASSED · **13/13** planning docs declare status |
| Rules T1/T2 | shipped and running — 0 contradictions |
| Test-task coverage | every custom `Test` task reachable from a workflow |
| Code hygiene | **0** TODO/FIXME in main source; 1 `@Disabled` (documented `@DisabledOnOs(WINDOWS)`) |
| REG-57 | genuinely fixed — the 5 s pre-kill sleep is **gone** (root cause: H2 MVStore 500 ms `WRITE_DELAY`) |
| REG-56 | genuinely fixed — `capabilityCall notify-approval` **restored** to the demo flow |
| F1 / F2.1 | `SCREEN_TAXONOMY.md` EXECUTED; invocations catalog + `InvocationCatalogRouteConformanceTest` (187 lines, dual-tree, 343 paths validated) |

## 0.2 Two things I expected to find and did not

- **3.5's blocker was already corrected** on 2026-07-28. The tree now says *"STALE BLOCKER,
  corrected… the REAL, current reason Postgres/full validation stays nightly-only is runtime cost
  (up to 120 min) — not a flake."* Accurate, and REG-4 is genuinely CLOSED (root cause fixed,
  `@Tag("load-sensitive")` removed and verified gone from source).
- **`OPEN_ITEMS.md` does not contradict the register.** It reports *"1 open/partial"*; the register
  has exactly **1** non-struck REG row (REG-62). They agree.

---

# Part 1 — 🔴 Drift and gate integrity (~1 day)

## R-D1 · `3.8`'s blocker is stale · ⚡ 10 min

MEASURED — `EXECUTION_TREES.md:325`:

```
└─ 3.8  Agent-driven frontend generation, productized
         ⛔ BLOCKED BY: 2.CD (the whole contract path, F1-F6)
```

F1–F5 are DONE; F6 is gated with **nothing to build** ("F1 found nothing recurs yet"). So the stated
blocker no longer holds. **3.8 is effectively unblocked** — and per the tree's own note it is *"a
prompt plus a CLI command, not a project."*

**Fix.** Replace with:

```
└─ 3.8  Agent-driven frontend generation, productized  ✅ UNBLOCKED 2026-07-28
         F1-F5 DONE; F6 gated with nothing to build. The contract (invocations catalog +
         bundle + UI_CONTRACT.md + AI prompt) and provenance (ADR-0010) all ship.
         → Now genuinely "a prompt plus a CLI command". Not yet scheduled.
         ⚠️ Depends on R-G1/R-G2: without the impact gate actually running, a generated
            screen joins the same unprotected population as the 12 unconfirmed ones.
```

## R-D2 ★ · Nothing cross-checks the ledger against the register · S 3 hr

MEASURED. `ledger/items/*.yml` holds 9 entries that **duplicate** register rows. I tested all 9 —
they agree today. But nothing keeps them agreeing.

The existing exclusion reasoning is correct *and* narrower than it reads: `OPEN_ITEMS.md` is excluded
because a summary-vs-detail contradiction is structurally impossible (both render from the same
`status` field). **True — and it covers `OPEN_ITEMS.md` ↔ YAML only.** It says nothing about
**YAML ↔ register**, which are two hand-maintained copies of the same facts. That is exactly the
dual-source problem 2.E exists to remove, temporarily doubled by the prototype.

**Fix — PROPOSED Rule T3 in `check-register-consistency.py`:**

```python
def ledger_register_agreement(root: Path) -> list[str]:
    """Rule T3: every ledger/items/REG-nn.yml must agree with that item's register row.

    The 2.E prototype (9 of ~57 tracked ids) deliberately keeps the prose register
    authoritative, which means the same fact is written twice. Rules T1/T2 compare a tree
    entry and a register row; nothing compared the YAML. Until the full cutover, this is the
    only thing standing between a partial migration and two diverging sources of truth.

    status: DONE      must be struck through (~~**REG-nn**~~) in the register
    status: OPEN|PARTIAL must appear as a plain (non-struck) row
    An id present in one and absent from the other is also a failure.
    """
```

**Acceptance.** RED against a deliberately flipped YAML `status`; GREEN on the current 9. Wire
blocking — this compares two machine-readable markers, so it has no false-positive risk.

**Note:** this rule *retires itself* when 2.E completes (Part 4) and the register stops being a
second copy. Say so in the docstring so nobody preserves it out of habit.

## R-D3 · The RuntimeHost metadata fixture is 3 months stale · S 2 hr

MEASURED. `NPDevRuntimeHost/src/test/resources/npdev/compiled-metadata.json` was last touched by
`fbec116` — the **original April baseline** — and carries **11** catalogs. The emitter now sets 12
(`CompiledMetadataCanonicalJson.java:85` adds `invocations`). `RuntimeMetadataServiceTest` reads this
fixture, so RuntimeHost's metadata service is exercised against a snapshot that predates a catalog it
is now expected to serve.

**Fix — do both:**
1. Regenerate the fixture from a current model so it carries all 12 catalogs.
2. **Add a completeness assertion** so staleness fails loudly next time: the fixture's catalog-name
   set must equal `CompiledMetadataCanonicalJson`'s emitted set. Without (2), the next catalog
   silently repeats this.

**Watch for:** regenerating may change unrelated assertions in `RuntimeMetadataServiceTest`. If it
does, that is information — those assertions were pinned to April's shape.

---

# Part 2 — 🔴 The impact gate protects almost nothing (~2 days)

**This is the most consequential finding. Treat R-G1 and R-G2 as one piece of work.**

## The situation, stated plainly

F4 was the payoff of the whole frontend programme: *rename a field, the build names the broken
screens.* The mechanism is **proven** — a real `CrossDocking.dataAtivacao → dataAtivacaoContada`
rename against a live 32-concept / 252-invocation bundle produced exit 1 naming the exact screen.

But MEASURED:

| | |
|---|---|
| Callers of `check-panel-provenance-impact.py` in any gate script or workflow | **0** |
| Confirmed manifests, WmsOffice | **3 of 13** screens |
| Confirmed manifests, AuxScreen / Pigmentampa | **0 of 1** each |
| Hand-written screens protected platform-wide | **3 of 15 (20%)** |

> **The demo proved the gun fires. Nobody loaded it.**

## R-G1 ★ · Give the gate somewhere to actually run · M 1 day

The non-wiring was a **reasoned decision, not an oversight** — the commit explains it well: the gate
needs `*.panel.json` files living in `AppGen/apps/*/web/` (a non-git workspace outside this repo) and
a live authenticated bundle (JWT login against a running FinalApp). Forcing it into
`run-ai-knowledge-gate.ps1` would make it either always find nothing, or require baking one app's
credentials into a platform-wide gate.

**That reasoning is right, and the conclusion should not be "a manual recipe in a plan document."**
A protection nobody runs is a protection that does not exist.

**PROPOSED — put it where the app already knows its own credentials:**

1. **Per-app `_ops` hook.** Every app's `_ops` toolbox already emits `Start-App.ps1` / `Stop-App.ps1`
   / `Test-App.ps1` and already holds that app's connection details. Emit
   **`Check-Provenance.ps1`** alongside them: logs in, fetches
   `GET /api/v1/runtime/metadata/ui/bundle`, runs the gate against that app's `web/`.
   This is the natural home — same category as `Test-App.ps1`, which the commit itself names.
2. **Wire it into the rebuild recipe.** `scripts/appgen/Rebuild-And-Restage.ps1` (and the
   `rebuild-app` skill) already refresh three caches; add a post-boot provenance check. **A field
   rename goes through a rebuild** — that is precisely the moment the check has to fire.
3. **Keep a repo-level static half.** A subset needs no live bundle: *does every `*.panel.json`
   validate against `schemas/panel-provenance.schema.json`, and is every `invokes[]` entry
   well-formed?* That much **can** run in `run-ai-knowledge-gate.ps1` with zero external dependency.
   Split the check: static half in CI, live half in `_ops`.

**Acceptance.** A field rename in a real app, followed by the normal rebuild, **fails** and names the
screens — without anyone remembering to run a script.

## R-G2 ★ · Raise coverage from 3/15 to 15/15 · M 1 day

Even with R-G1, the gate guards 20% of the surface. The remaining 12 screens are exactly the ones
most likely to break: `inventario`, `centro-trabalho`, `conferencia-fiscal`, `movimentacao-livre`,
`crossdocking`, `mapa-armazem`, `analytics`, `relatorios`, plus AuxScreen's and Pigmentampa's.

**Steps.**
1. Run `scripts/quality/bootstrap-panel-provenance.py` over all remaining screens against each app's
   live bundle → drafts with `confirmed: false`.
2. **Confirm them in batches**, reviewing `reads`/`writes`/`invokes` and clearing `unresolved`.
   Budget ~20 min per screen; the drafts do the recall, a human does the precision.
3. Record coverage in `SCREEN_TAXONOMY.md` — a `manifest: confirmed | draft | none` column per screen
   makes the 20% → 100% progress visible instead of implicit.

**Acceptance.** Every hand-written screen in all five official apps has a manifest; every one is
`confirmed: true` or carries a written reason why not.

> **Do R-G1 before R-G2.** Confirming 12 manifests that nothing ever reads is bookkeeping.

---

# Part 3 — 🟡 Small gaps (~half a day total)

## R-G3 · `CODE_OF_CONDUCT.md` · ⚡ 10 min
POST_PUBLIC P2.3 listed it; `SECURITY.md`, `CONTRIBUTING.md` and `.github/ISSUE_TEMPLATE` all landed,
this did not. Contributor Covenant, pointed at the same contact as `SECURITY.md`.

## R-G4 · F6 has no trigger · ⚡ 30 min
*"F1 found nothing recurs yet"* is honest, but nothing re-checks. `classify-screens.py` exists and is
not scheduled, so a class that starts recurring goes unnoticed indefinitely.
**Fix:** add a line to `CONTRIBUTING.md` / the app-creation recipe — *when a new app lands, re-run
the classifier and update `SCREEN_TAXONOMY.md`*; record the ≥2-apps/≥2-screens rule as the trigger.
Cheap, and it keeps F6 evidence-driven rather than forgotten.

## R-R1 · Route-conformance patterns are a hand-maintained mirror · S 2 hr (or accept)
`InvocationCatalogRouteConformanceTest` validates path **shapes** against `REAL_ROUTE_PATTERNS`
regexes, not against the actual controllers. If a controller's route changes, the patterns need
manual updating and the test cannot detect pattern↔controller divergence.

Accepted tradeoff — the DSL module genuinely cannot see a generated app. **But the generator module
can.** Options:
- **(a)** Add a generator-side test that runs `extract-routes.py`'s logic over a freshly generated
  sample and asserts every `REAL_ROUTE_PATTERNS` entry matches ≥1 real route (catches patterns that
  have gone stale), **or**
- **(b)** Record it in `ACCEPTED_BOUNDARIES.md` as a known limit with its rationale.

**Recommendation: (a)** — the helper already exists and the generator module already generates sample
apps in tests. If (a) proves awkward, (b) honestly.

## R-O1 · REG-62 (`allowedActions` typing) · as filed
LOW, open, correctly filed as a real prerequisite gap. Leave scheduled; no action beyond keeping it
visible in the register.

---

# Part 4 — 🔵 The blocked tier (previously outside the findings list)

## R-P1 ★ · 2.E — complete the ledger migration · M 3–4 days

MEASURED: **9 of ~57** unique tracked ids migrated. The prototype is honestly scoped ("prose register
stays authoritative") and its generator + `--check` drift guard work.

**Why finish it, in this plan's own terms:** Parts 1's R-D2 exists *only because* the migration is
partial. Rules T1, T2 and the proposed T3 are all regex-over-prose compensations for status living in
markdown. Finish 2.E and:
- R-D2's Rule T3 **retires itself** (one source, nothing to disagree with);
- Rules T1/T2 simplify to YAML field reads;
- **3.4 unblocks** (below).

**Steps.**
1. Migrate the remaining ~48 ids in batches by section, `--check` green after each.
2. Move each item's long narrative to `ledger/evidence/<id>/` — the prose is genuinely valuable
   (REG-49's withdrawal reasoning is a case study); its *length* is only a tax inside a table.
3. Flip authority: `ledger/items/*.yml` becomes the source of truth; `docs/OPEN_ITEMS.md` stays
   generated; the register becomes a generated view or is archived.
4. Repoint every consumer (`check-register-consistency.py`, `check-narrative-status-drift.py`,
   `run-script-automation-quality.ps1`, `npdev-ci-validation.yml`, and the `SchemaLifecycleExecutor`
   comment reference).
5. Retire Rule T3.

**Acceptance.** One source of truth. `--check` green. All consumers read YAML. No hand-edited status
anywhere.

## R-P2 · 3.4 — archive the 13 gate-hardwired process docs · S 30 min *(after R-P1)*

MEASURED, still wired today: `REMAINDER_CLOSURE_PLAN.md` (3 refs), `ONE_PLAN_CLOSE_EVERYTHING.md`
(2), `LNCH1_CLOSEOUT_PLAN.md` (1), `LNCH1_PLATFORM_COLUMN_PLAN.md` (1),
`REGISTER_CLOSURE_PLAN.md` (1), and the rest.

A finished programme's closure plan should not be a runtime dependency of CI. Once R-P1 repoints the
consumers at YAML, these references disappear and the docs `git mv` to
`docs/archive/programme-history/` — the same treatment the other 29 already got. **~30 minutes, and
it only becomes possible after R-P1.**

## R-P3 · 3.5 — Postgres adapters in the PR gate · ⬥ decision, then S

**Already correctly re-scoped** (2026-07-28): REG-4 is closed, and the real reason full validation
stays nightly is **runtime cost** (`npdev-ci-validation.yml`: *"up to 120min"*), not a flake.

So this is now a **cost/benefit decision, not a blocked task**:
- **(a)** Leave nightly. Postgres regressions surface within 24 h.
- **(b)** Promote a *subset* to PR — the adapters whose bugs have actually bitten
  (`persistence-postgres` after REG-50, `idempotency-postgres` after REG-36). Both had real findings
  that H2 could not have caught; a narrow Testcontainers run is a few minutes, not 120.
- **(c)** Promote all — rejected on cost.

**Recommendation: (b)**, scoped by evidence — the two adapters with a track record of real,
H2-invisible findings. Record the decision either way in `ACCEPTED_BOUNDARIES.md`.

## R-P4 · 3.8 — agent-driven frontend, productized · M 2–3 days *(after Part 2)*

Unblocked by R-D1. Everything it needs now ships: invocations catalog, bundle endpoint,
`UI_CONTRACT.md`, `docs/ai/UI_GENERATION_PROMPT.md`, provenance (ADR-0010), impact gate.

**Scope — genuinely small:**
1. `npdev generate screen --app <app> --concept <C> --out web/<name>.html` — fetch bundle, run the
   prompt, write the screen **and its `panel.json`** (`producer: "agent"`, `confirmed: true`).
2. Refuse to write a screen whose manifest fails the impact gate — generation and verification in one
   step.
3. Prove it: generate one real screen for an existing app, confirm it renders and its manifest passes.

> **Sequenced after Part 2 deliberately.** Without R-G1/R-G2, a generated screen joins the same
> unprotected 80% — you would be adding surface faster than you are protecting it.

---

# Part 5 — ⬥ R-O2 · Three real conversations (owner)

Unchanged and still the only item everything strategic is downstream of. Public ≠ known.

Smallest useful version: repo description + topics; a short written pitch (SDD framing + the three
differentiators + the honest UI limitation); **three conversations**, not a broadcast; record
first-hour friction in `NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`.

**One new argument for doing it now:** B20's own text says the bounded-context question *"likely
surfaces via P6.3's outreach conversations before it surfaces via more internal measurement."* The
project has started explicitly deferring design decisions to evidence it is not collecting.

---

# Part 6 — Sequencing

```
DAY 1 (~1 day)     R-D1  3.8 stale blocker                      ⚡ 10 min
                   R-D2  Rule T3 ledger↔register  ★             3 hr
                   R-D3  regenerate fixture + completeness test  2 hr
                   R-G3  CODE_OF_CONDUCT.md · R-G4 F6 trigger    40 min

DAY 2-3 (~2 days)  R-G1  gate gets a home (_ops + rebuild + static half)  ★★
                   R-G2  coverage 3/15 → 15/15                            ★
                     └─► a rename now fails without anyone remembering

DAY 4              R-R1  generator-side pattern-staleness test (or accept)  2 hr
                   R-P3  ⬥ decide 3.5 — recommend (b), evidence-scoped

WEEK 2             R-P1  2.E full migration  ★  [3-4 d]
                     └─► R-P2  3.4 archive the 13   [30 min]
                     └─► Rule T3 retires itself

WEEK 2-3           R-P4  3.8 agent-driven screen generation  [2-3 d]

OWNER ⬥            R-O2  three conversations — everything strategic is downstream
STAYS FILED        R-O1  REG-62 (LOW)
```

## Why this order

**Part 1 first** — a stale blocker misdirects, and Rule T3 is the only thing preventing the partial
migration from becoming two diverging sources while R-P1 waits a week.

**Part 2 before Part 4's R-P4** — the impact gate is the single highest-value thing built this month
and it currently guards 20% of the surface while running nowhere. Generating *more* screens before
fixing that makes the ratio worse.

**R-P1 before R-P2** — 3.4 is 30 minutes that is genuinely impossible until the consumers move.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| R-G1's `_ops` hook needs per-app credentials and gets skipped for the same reason CI did | **Medium** | `_ops` already holds them — that is why it is the right home. Prove it on WmsOffice before generalising |
| Regenerating the fixture (R-D3) breaks unrelated assertions | **Medium** | Expected. Those assertions were pinned to April's shape — treat breakage as information, not noise |
| Confirming 12 manifests is tedious and stalls at 6 | **Medium** | Do R-G1 first so each confirmation buys real protection; track the count in `SCREEN_TAXONOMY.md` |
| 2.E full migration stalls again mid-way | **Medium** | Batch by register section with `--check` green after each; Rule T3 keeps partial states honest |
| Rule T3 outlives its purpose | Low | Its docstring states it retires at cutover; R-P1 step 5 removes it |
| R-P4 generates screens faster than they can be confirmed | **Medium** | Step 2: refuse to write a screen whose manifest fails the gate |

## Definition of done

**Implementation record (2026-07-29):** every item below except R-O2's owner-only half is DONE,
live-verified, and gate-green. R-O2's "three conversations" cannot be executed by an agent — the
repo description/topics/pitch/friction-log prep is done; the conversations themselves are still the
owner's to have. See `docs/REMEDIATION_PLAN_IMPLEMENTATION_NOTES.md` for the full evidence trail.

- [x] **R-D1** 3.8 reads UNBLOCKED with its real dependency (Part 2) named — `docs/EXECUTION_TREES.md`
- [x] **R-D2** Rule T3 wired blocking, calibrated RED→GREEN, docstring states its retirement condition — then genuinely RETIRED once R-P1 completed (see R-P1: nothing left to cross-check)
- [x] **R-D3** fixture carries all 12 catalogs; a completeness assertion fails on the next stale catalog — `RuntimeMetadataServiceTest`, confirmed RED against the pre-fix fixture
- [x] **R-G1** a field rename in a real app fails the normal rebuild and names the screens — **with nobody remembering to run anything** — proven live on WmsOffice (positive + negative case), wired into `Rebuild-And-Restage.ps1` step 4 (default-on) + `run-ai-knowledge-gate.ps1` check 10/10 (static half)
- [x] **R-G2** 15/15 hand-written screens have a manifest; each `confirmed: true` or with a written reason — 13/13 WmsOffice confirmed; AuxScreen/Pigmentampa blocked with a written reason (filed as REG-63)
- [x] **R-G3/R-G4** CODE_OF_CONDUCT.md present; F6's re-check trigger recorded — `CONTRIBUTING.md`
- [x] **R-R1** pattern staleness is either tested or recorded as an accepted boundary — `RoutePatternStalenessTest` (generator module), calibrated RED→GREEN live
- [x] **R-P1** `ledger/items/*.yml` is the single source of truth; all consumers repointed; Rule T3 retired — 64/64 ids migrated, register archived-in-place, Rule T1 reads the ledger directly
- [x] **R-P2** the process docs archived; no script or workflow references a closure plan as a runtime dependency — measured 8 (not the plan's estimated 13), all archived, dead `LEDGER_EXCLUSIONS` entries removed
- [x] **R-P3** the nightly-vs-PR decision recorded with its reason — `docs/ACCEPTED_BOUNDARIES.md` B21, `persistence-postgres`/`idempotency-postgres` promoted to `npdev-pr-gate.yml`
- [x] **R-P4** one real screen generated from the contract, manifest passing the gate — `npdev generate screen`, proven live on WmsOffice (refusal case + success case, full CRUD verified against the running app)
- [ ] **R-O2** three people outside this machine have tried it, and what they hit is written down — **owner-only, not done**; pitch (`docs/PITCH.md`) and friction-log template (`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`) are ready

---

*Companions: `docs/EXECUTION_TREES.md` (R-D1) · `docs/NEXT_EXECUTION_PLAN.md` (predecessor) ·
`docs/FRONTEND_STRATEGY_PLAN.md` (F-series) · `docs/SCREEN_TAXONOMY.md` (R-G2/R-G4) ·
`docs/adr/ADR-0010-panel-provenance-manifests.md` · `ledger/README.md` (R-P1) ·
`docs/ACCEPTED_BOUNDARIES.md` (R-R1/R-P3) · `docs/NPDEV_OPEN_ITEMS_REGISTER.md`.*
