# Invocation Topology Plan

> **STATUS: ACTIVE.** Live backlog. Written 2026-07-29 against `beta1-vision-spine` @ `3f9fa8d`
> (repo public, tag `beta1.2`, ledger **68 items / 1 open**, knowledge gate 15/15, tree clean).
>
> **Scope:** the master failure pattern behind four separate findings, REG-67 plus its unfiled
> second instance, and the remaining open items. **~2.5 days plus one owner action.**
>
> Facts are **MEASURED** (git, source, gates, live script runs — 2026-07-29) or **PROPOSED**.
> Each item gives **What · Why · Where · How · DoD**.

---

# Part 0 — The pattern, named

## 0.1 Four findings, one shape

| # | Finding | The break |
|---|---|---|
| 1 | `:generator:behaviorTest` | Wired to Gradle `check`; CI invoked `test` |
| 2 | 17 broken corpus models | A deferral recorded in a plan's DoD; the plan closed and took the deferral with it |
| 3 | corpus + markdown-link gates | Wired to a workflow whose `paths:` filter excluded the guarded files |
| 4 | 10 `--calibrate` self-tests | Correct, complete, and invoked by nothing |

Every one is the same sentence: **the check exists and is correct; the thing that invokes it doesn't.**

Not one was a logic bug. Every check, once run, behaved exactly as designed. What failed each time
was the **edge** between an executable artifact and something that runs it.

## 0.2 The four ways an edge breaks

```
   ARTIFACT              INVOKER                TRIGGER
   (test task,     ──►   (gate step,      ──►   (paths filter,
    script,              gradle lifecycle,       schedule, event)
    --calibrate mode)    workflow step)

   (A) no invoker at all           → finding 4, and 23 orphan quality scripts
   (B) invoker never triggers      → finding 3
   (C) invoked via a lifecycle CI doesn't call → finding 1
   (D) no artifact — only an intent → finding 2
```

## 0.3 What already exists

`check-test-task-coverage.py` solves **(A)+(C) for Gradle `Test` tasks only** — the fix built after
finding 1. It is exactly the right idea, applied to one artifact type. The other three artifact
types (quality scripts, workflow triggers, calibration modes) have no equivalent.

There is also **prior art nobody is using**: `scripts/quality/run-script-inventory-check.ps1` plus
`scripts/policy/script-inventory-policy.json`, which already define a classification vocabulary —
`canonical · helper · deprecated · one-time-repair · outside-repo-only` — and pattern rules for
assigning it. MEASURED: that script is **itself an orphan** (no invoker anywhere) **and undocumented**
(0 doc references). A script-inventory checker that nothing inventories is the pattern in miniature.

## 0.4 Measured scale

```
scripts (all)                        152
  invoked by something               110
  ORPHAN — no caller anywhere         42
scripts/quality/                      94
  ORPHAN                              23
--calibrate modes shipped             10
  invoked in CI                        0
  ALREADY ROTTED (fail today)          2   ← REG-67 + one unfiled
```

**Most orphans are legitimate.** Spot-checking documentation references:
`run-stateful-additive-migrations-check.ps1` (10 doc refs), `run-release-checklist-gate.ps1` (7),
`run-app-upgrade-contract-gate.ps1` (6), `run-external-ai-gate.ps1` (5). These are *documented
manual/runbook tools*, not abandonment — they just have no declaration saying so, which is why they
are indistinguishable from the genuinely dead ones.

**The goal is not to wire everything into CI.** It is to make every artifact's invocation status a
*declared, checked fact* instead of something a person has to go measure — which is how all four
findings were actually discovered.

---

# Part 1 — 🔴 The rotted calibrations (half a day)

## T1 · Two `--calibrate` self-tests fail today; only one is filed

**What.** REG-67 (OPEN, LOW) records that `check-register-consistency.py --calibrate` fails because
its real-instance controls read `git show HEAD:<path>` — a moving target — and the bug-shaped text
they look for no longer exists at today's HEAD.

**MEASURED: it is not one script, it is two.** I ran all eight runnable calibration modes:

```
check-dsl-coverage                exit=0
check-engine-variant-families     exit=0
check-markdown-links              exit=0
check-narrative-status-drift      exit=1   ← FAILS, NOT FILED
check-panel-provenance-schema     exit=0
check-test-task-coverage          exit=0
check-workflow-yaml-syntax        exit=0
check-register-consistency        exit=1   ← REG-67
```

`check-narrative-status-drift.py` fails on the **identical root cause** — its Rule P2 control reads
*"ADR-0009 @ HEAD (pre-fix header, real git revision)"* and now reports `silent` instead of `fired`,
because ADR-0009 has been edited since.

**Why it matters.** A calibration is the only evidence a gate can detect the bug it exists for.
"Passes on a clean tree" and "would fire on a dirty one" are different claims, and the second is now
**unproven for two gates and unverifiable-by-CI for all ten**.

There is a sharper point on `check-narrative-status-drift.py`: its own docstring says it
*"ships REPORTING ONLY … never blocking, until a clean-tree run proves the detector works."*
By its own stated contract it should not be shipping — and it runs as step 7/15 on every gate
invocation.

REG-67 is right that impact is bounded (blocking checks read the working tree, not HEAD, and are
unaffected). But bounded impact plus zero detection is exactly how it went unnoticed until someone
happened to run it by hand while building an unrelated rule.

**Where.** `scripts/quality/check-register-consistency.py` (`calibrate()`) ·
`scripts/quality/check-narrative-status-drift.py` (Rule P2 control) ·
`scripts/quality/run-ai-knowledge-gate.ps1` (would gain a step).

**How to solve.**

1. **Pin every real-instance control to a fixed commit SHA**, not `HEAD`. G4's own new Rule T2b
   control already does this correctly — it names `REG-62 @ 9c3c423`. Use the same durability
   everywhere: `git show <sha>:<path>`, with a comment saying which bug shape that SHA holds.
   Find each with `git log -S '<the stale wording>' -- <path>`.
2. **File the second instance** as its own ledger item (or widen REG-67 to name both, with the
   measured evidence). Do not fold it silently into REG-67's fix — the count is the finding.
3. ★ **Add one CI step that runs all ten `--calibrate` modes.** Pure-Python, seconds, no external
   dependency, and it would have caught both rots the day they happened:

```powershell
# run-ai-knowledge-gate.ps1 — new step [16/16]
# Every gate ships a --calibrate self-test proving it can still detect the bug it exists for.
# Nothing ran them, so two rotted silently (REG-67 + narrative-drift, both pinned to a moving
# HEAD). A calibration nobody runs is a claim, not evidence.
foreach ($s in $calibratableScripts) {
    & $py $s --calibrate
    if ($LASTEXITCODE -ne 0) { $failures += "$s --calibrate FAILED: it can no longer prove it detects its own target bug" }
}
```

4. Make the list **derived, not hand-maintained**: any `scripts/quality/*.py` whose argparse declares
   `--calibrate` must appear. A hand-list would drift the same way everything else in this plan did.

**Definition of done.**
- [ ] All ten `--calibrate` modes exit 0 on a clean tree
- [ ] Every real-instance control is pinned to a fixed SHA, with the bug shape named in a comment
- [ ] Both rot instances filed; REG-67 closed with the true count (2, not 1)
- [ ] A CI step runs every `--calibrate`, with its script list **derived from argparse**, not hand-written
- [ ] `check-narrative-status-drift.py` either passes calibration or stops running until it does — per its own docstring

---

# Part 2 — 🟡 The topology gate (1 day)

## T2 · Declare and check every artifact's invocation status

**What.** Generalize `check-test-task-coverage.py` from Gradle `Test` tasks to every executable
quality artifact, using the classification vocabulary that already exists.

**Why it matters.** MEASURED: 42 of 152 scripts have no caller; 23 of 94 quality scripts. Most are
documented manual tools — but **nothing distinguishes "manual by design" from "abandoned"**, so
answering the question costs a bespoke script every time. That cost is why all four findings were
discovered late and by accident.

`scripts/policy/script-inventory-policy.json` already defines the vocabulary —
`canonical · helper · deprecated · one-time-repair · outside-repo-only` — and
`run-script-inventory-check.ps1` already consumes it. **Both are orphaned and the checker is
undocumented.** The design work is done; the wiring is not.

**Where.** `scripts/quality/run-script-inventory-check.ps1` (revive) ·
`scripts/policy/script-inventory-policy.json` (extend) ·
`scripts/quality/check-test-task-coverage.py` (the model to follow) ·
`scripts/quality/run-ai-knowledge-gate.ps1` (invoker).

**How to solve.**

1. **Extend the vocabulary with an invocation axis.** Classification says *what a script is*; add
   what invokes it:

   | `invocation` | Meaning | Checked how |
   |---|---|---|
   | `ci-gate` | must be invoked by a workflow or gate script | invoker exists **and** its trigger covers the guarded paths |
   | `manual-runbook` | run by a human, deliberately | must be referenced by ≥1 doc |
   | `orchestrated` | called by another script only | caller exists |
   | `retired` | kept for history, runs nowhere | must name a reason + date |

2. **Revive `run-script-inventory-check.ps1`** to assert every script has both a `classification`
   and an `invocation`, and that the declaration matches reality. Unclassified → **fail**.
3. **Seed it against today's state**: classify the 42 orphans. Expect most to land `manual-runbook`
   (they are documented) and a few `retired`. `run-beta0-final-release-check_v2.ps1` next to
   `run-beta0-final-release-check.ps1` is the obvious `retired` candidate.
4. **Wire it into the knowledge gate** — and, pointedly, that makes the script-inventory checker no
   longer an orphan itself.
5. **Calibrate**: add a script with no classification, confirm RED; classify it, confirm GREEN.

**Definition of done.**
- [ ] Every script under `scripts/` declares a `classification` **and** an `invocation`
- [ ] A new script with neither fails the gate
- [ ] The 42 current orphans are each classified, not silently allowlisted
- [ ] `run-script-inventory-check.ps1` runs in CI and is documented
- [ ] Calibrated RED→GREEN

---

## T3 · Close the trigger half — paths coverage

**What.** F1 fixed `ai-knowledge-gate.yml`'s `paths:` filter by hand. Nothing checks that it *stays*
correct as gates are added.

**Why it matters.** This is break-type **(B)**, and the hand-fix is exactly as durable as the
hand-maintained list it replaced. Add a gate that guards `NPDevGenerator/**` tomorrow and the filter
silently under-covers again — the same way it did for `NPDevContract/schemas/**` while the corpus
gate was supposed to be protecting it.

**Where.** `.github/workflows/*.yml` (`paths:` filters) · a new check.

**How to solve.** Have each gate script **declare what it guards**, then assert the invoking
workflow's `paths:` covers it:

```python
# check-corpus-parses / validate-corpus.py — machine-readable header
# GUARDS: NPDevContract/schemas/**, NPDevSamples/**/model.json, AppGen/apps/**
```

The checker parses `GUARDS:` from every gate script, resolves which workflow invokes it, and fails
if a guarded glob is not covered by that workflow's `paths:`. Start with the ~15 knowledge-gate
scripts; do not attempt every workflow at once.

> **Cheaper alternative if this proves fiddly:** drop `paths:` from `ai-knowledge-gate.yml` entirely
> so it runs on every PR. It is minutes, not hours, and "always runs" cannot under-cover. Take this
> if T3 looks like more than a day — a correct gate that always runs beats a clever filter that
> sometimes doesn't. Record the choice in `ACCEPTED_BOUNDARIES.md` either way.

**Definition of done.**
- [ ] Every knowledge-gate script declares `GUARDS:`, or the filter is removed and that is recorded
- [ ] Adding a gate whose guarded paths are uncovered fails the build
- [ ] Verified by an observed GitHub run, not by reasoning

---

## T4 · Close the intent half — deferrals need tracking items

**What.** Break-type **(D)**: finding 2 had no artifact at all. A deferral was recorded in
`DSL2_AND_DECOMPOSITION_PLAN.md`'s Definition of Done (*"AppGen/apps deferred as a non-git external
directory — owner's call"*), the plan closed, and the deferral closed with it. 17 models stayed broken
for ~3 weeks.

**Why it matters.** It is the only one of the four with no executable artifact to point a gate at, so
it needs a *convention*, not a script. It is also the most expensive of the four by a wide margin.

**Where.** `CONTRIBUTING.md` · the plan-document template · `check-register-consistency.py`'s
planning-document status check (the natural enforcement point).

**How to solve.**

1. **Convention:** *a plan may not close with an unresolved deferral that has no ledger item.*
   Deferring is fine and often right — deferring **without a tracking id** is what failed.
2. **Enforce it cheaply.** The planning-document check already requires a `STATUS:` header on every
   plan. Extend it: a plan marked `EXECUTED`/`DONE` whose text contains `deferred` · `out of scope` ·
   `not covered` · `left for later` must cite a `REG-nn` within the same paragraph.
3. **Calibrate against the real instance** — `DSL2_AND_DECOMPOSITION_PLAN.md` at the commit where it
   was marked DONE is a live historical fixture. Pin it by SHA (per T1's rule).

**Definition of done.**
- [ ] The convention is in `CONTRIBUTING.md` and the plan template
- [ ] A plan closing with an untracked deferral fails the gate
- [ ] Calibrated against the real DSL-2.0 instance, pinned by SHA

---

# Part 3 — 🟢 Remaining open items

## T5 · REG-67 — close it with the corrected scope

Covered by **T1**. Note explicitly on closing: the filed scope (one script) was **half** the real
scope (two), found by running the class rather than the instance. That is the lesson worth recording
in the item, not just the fix.

**DoD.** REG-67 closed; second instance filed and fixed; both calibrations green; ledger back to 0 open.

---

## T6 ⬥ · Revoke the exposed GitHub token

**What.** `ghp_msPO…` remains live in this conversation's history.

**Why.** G1 correctly established that classic PATs have **no API/CLI revocation path** — only the
GitHub web UI can delete one — so this was always the owner's action, and the session was right to
say so and to write the prevention convention into `SECURITY.md` instead.

**That leaves the actual revocation outstanding, now flagged three times.** The convention prevents
the next exposure; it does nothing about this one.

**Where.** GitHub → Settings → Developer settings → Personal access tokens.

**How.** Delete the token. Mint a narrowly-scoped replacement only if needed, set it in the
environment, never in chat. Check Settings → Security log for unrecognised use.

**DoD.**
- [ ] Token deleted — confirmed in the token list
- [ ] Replacement (if any) narrowly scoped and never typed into a chat
- [ ] Security log checked

---

## T7 ⬥ · Three outreach conversations

Unchanged. Still the only open item that can change *what gets built next*, and now the only one
gating strategic questions the project keeps deferring to it (B20's own text names these
conversations as its trigger).

**One new argument from this plan:** the invocation-topology pattern is a *closed-loop-development*
failure — four instances of "nobody ran it" found by internal audit, late each time. External users
run things you did not think to run, on day one. That is the same corrective force, from outside.

**DoD.** Description + topics set · pitch written · three people have cloned, generated **and
regenerated** an app · first-hour friction recorded in `NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` · at
least one deferred roadmap question answered by evidence.

---

# Part 4 — Sequencing

```
NOW (15 min)     T6  ⬥ revoke the token — third flag, still the only external blast radius

DAY 1 (~half)    T1  pin calibrations to SHAs; file instance #2; add the run-all-calibrate step  ★
                     └─ this alone closes the class that produced findings 1, 3 and 4's detection gap

DAY 2 (~1 day)   T2  invocation classification + revive run-script-inventory-check  ★
                 T3  GUARDS: declarations, OR drop the paths filter (pick fast, do not gold-plate)

DAY 3 (~half)    T4  deferral-needs-a-ledger-item convention + gate
                 T5  close REG-67 with corrected scope

ANYTIME ⬥        T7  three conversations
```

## Why this order

**T1 first among the technical work.** It is half a day and it closes the *detection* gap for the
whole class — after it, a rotted or unwired check announces itself instead of waiting for someone to
notice by hand. Everything else in this plan is a specific instance of what T1 makes visible in
general.

**T3 has a deliberate escape hatch.** If declaring `GUARDS:` across 15 scripts looks like more than a
day, delete the `paths:` filter and run the gate on every PR. It is pure-Python and minutes long. A
correct gate that always runs beats a clever filter that sometimes doesn't — and the whole point of
this plan is to stop optimising invocation into fragility.

**T4 last of the technical items** because it is a convention, not a mechanism, and conventions are
better written after the mechanisms they complement exist.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| T1's run-all step is added with a hand-maintained script list | **Medium** | DoD requires the list be derived from argparse — a hand-list drifts exactly like everything else here |
| T2 classifies the 42 orphans by blanket-allowlisting them | **Medium** | DoD says each is classified, not allowlisted; expect mostly `manual-runbook`, and treat a large `retired` count as a signal to actually delete |
| T3 gold-plates into a week | **Medium** | The escape hatch is explicit and pre-approved: drop the filter, record it |
| Reviving `run-script-inventory-check.ps1` surfaces a large backlog of unclassified scripts | **High** | That backlog *is* the finding. Seed the classifications in the same commit; do not defer them — see T4 |
| T6 deferred a fourth time | **Medium** | 15 minutes, no dependencies, owner-only. It is first in the sequence for that reason |
| T7 slips again | **High** | It has slipped every plan in this sequence |

## Overall definition of done

- [ ] All ten `--calibrate` modes pass and **run in CI**, with SHA-pinned controls
- [ ] Every script declares what it is **and** what invokes it; a new one without both fails
- [ ] Trigger coverage is either checked or made unnecessary by removing the filter — recorded either way
- [ ] A plan cannot close with an untracked deferral
- [ ] Ledger back to **0 open**
- [ ] The exposed token is revoked
- [ ] **Three people outside this machine have regenerated an app, and what they hit is written down**

---

*Companions: `docs/CLOSEOUT_PLAN.md` (predecessor) · `ledger/items/REG-67.yml` ·
`scripts/quality/check-test-task-coverage.py` (the model for T2) ·
`scripts/quality/run-script-inventory-check.ps1` + `scripts/policy/script-inventory-policy.json`
(existing prior art, orphaned) · `SECURITY.md` (T6) · `docs/ACCEPTED_BOUNDARIES.md` B20 → T7.*
