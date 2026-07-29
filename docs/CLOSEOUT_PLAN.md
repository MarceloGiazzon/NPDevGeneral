# Closeout Plan

> **STATUS: CLOSED except G1's revocation and G5 (both owner-only).** Written 2026-07-29 against
> `beta1-vision-spine` @ `5892370` (repo public, tag `beta1.2`, ledger **67 items / 0 open**,
> knowledge gate **15/15 green**, tree clean, all pushed). G2/G3/G4 done 2026-07-29 — full evidence
> in each item's own Definition of Done below. One new gap (REG-67) found and filed, not fixed,
> while calibrating G4's Rule T2b — see that item's own note.
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\CLOSEOUT_PLAN.md" "D:\WorkSpace\NPDev\NPDev_General\docs\CLOSEOUT_PLAN.md"
> ```
>
> **Scope:** the five items still open after `FINAL_OPEN_ITEMS_PLAN.md` closed F1–F10. One is a
> live security exposure; three are one finding seen from three angles; one is the owner's.
> **~1.5 days plus one owner action.**
>
> Facts are **MEASURED** (git, source, gates — 2026-07-29) or **PROPOSED**.
> Each item gives **What · Why · Where · How · DoD**.

---

## Item index

| # | Item | Class | Sev | Effort | Phase |
|---|---|---|---|---|---|
| **G1** | GitHub PAT sitting in plaintext chat history | 🔴 security | **HIGH** | 15 min | 1 |
| **G2** | Generate-coverage seam — features parse but are never emitted | gap | MED | 4 hr | 2 |
| **G3** | 65 test files hand-build compiled objects, bypassing the authoring path | systemic | MED | 3 hr | 2 |
| **G4** | REG-62's title contradicts its own DONE status (Rule T2 scope gap) | drift | LOW | 1 hr | 3 |
| **G5** | Three outreach conversations | strategic | ★ | owner | 4 |

---

# Phase 1 — Security 🔴

## G1 · Revoke the exposed GitHub token

**What.** A GitHub Personal Access Token (`ghp_msPO…`) was pasted into this chat and reused this
session via `$env:GH_TOKEN` to read real PR-gate run numbers. It is sitting in plaintext in the
conversation history.

**Why it matters.** This is the only open item with a blast radius outside the repo.

- Chat transcripts persist and are not a secret store. Anything in one is disclosed for as long as
  the transcript exists.
- The token has been used across at least two sessions, so its scopes are demonstrably live — it can
  read repo data and, depending on scopes, push, open PRs, or alter settings on a **now-public**
  repository.
- **Rotation stops being optional the moment a credential is written down in plaintext.** It does not
  matter that the use was legitimate or that nothing bad has happened; the exposure is the event, not
  the misuse.
- The session flagged it twice, unprompted and correctly. Two flags with no revocation is worse than
  one — it means the risk is known and carried.

This is not a criticism of the work: using the token was the only way to get F2b's real 6m14s
measurement, and disclosing the reuse was the right call. It just has to end with a revocation.

**Where.** GitHub → Settings → Developer settings → Personal access tokens. Any local
`GH_TOKEN`/`GITHUB_TOKEN` environment variable, shell profile, or `gh` config that holds it.

**How to solve.**

1. **Revoke it now**, before anything else in this plan. Revocation is instant and reversible only in
   the sense that you can mint a replacement — there is no cost to doing it early.
2. Mint a replacement with the **narrowest scopes** the workflow actually needs. From this
   engagement's usage that is read-only repo/actions access for reading run numbers; `repo` +
   `read:org` only if PR creation and merging are needed again.
3. **Do not paste the replacement into chat.** Set it in the environment once
   (`$env:GH_TOKEN` in a profile, or `gh auth login`), and reference it by name only.
4. Check the audit log for use you do not recognise: GitHub → Settings → Security log, filtered to
   the token. On a public repo this is a five-minute check worth doing once.
5. Note in `SECURITY.md` (or `CONTRIBUTING.md`) that credentials are never pasted into an agent
   session — a one-line convention that prevents the recurrence rather than the instance.

**Definition of done.**
- [ ] `ghp_msPO…` **revoked** on GitHub — confirmed in the token list, not assumed — **owner action,
      cannot be done by an agent: no `gh`/API path revokes a classic PAT, only the GitHub web UI
      (Settings → Developer settings → Personal access tokens)**. Flagged directly to the user at the
      start of this session's work.
- [ ] Replacement (if any) is narrowly scoped and was never typed into a chat — owner action
- [ ] Security log checked for unrecognised use — owner action
- [x] The "never paste credentials into an agent session" convention is written down — `SECURITY.md`
      § "Credentials in agent sessions"

---

# Phase 2 — The testing seam 🟡

**G2 and G3 are one finding from two ends. Do them together; G2 is most of G3's fix.**

---

## G2 · Every DSL feature parses; almost none are proven to *generate*

**What.** The 15-check gate proves every DSL feature is exercised by a corpus model that **parses and
validates**. Nothing proves those features **emit code**. `dsl-conformance-max` — the fixture built
specifically to carry the rare features — is explicitly *"validated, not run."*

**Why it matters.** MEASURED:

| Stage | Coverage |
|---|---|
| Parse + validate | **29/29 corpus models**, and every tracked DSL feature (checks 11 and 15) |
| **Generate** | **`simple-contact-intake` only** — the single sample the PR gate generates |

So `forEach`, `onFailure`, `selectors`, `documents`, `guidePages`, `flow.schedule`, `flow.hooks`,
`aggregates`, `autoPanels` and `generatedAction` are parse-proven from a real model and
**never generate-proven from one.** A change that breaks the *emitter* for any of them passes 15/15.

This is not hypothetical: **F4 was exactly this failure**, one layer over. `generatedAction` was
proven at runtime by a packaged-app test and rejected by the validator, and the two halves never met
for as long as the feature had existed.

**Where.** `NPDevSamples/dsl-conformance-max/` (currently parse-only) ·
`scripts/quality/run-ai-knowledge-gate.ps1` (would gain a check) ·
`NPDevSamples/scripts/generate-sample-app.ps1` · `scripts/appgen/Build-NpdevApp.ps1`
(`-GenerateOnly` exists, line 36).

**How to solve.** The fixture was built for exactly this and is unusually cheap to generate — one
concept tree, InMemory, no web assets, no boot required.

1. Add a gate check: **generate `dsl-conformance-max` with `-GenerateOnly`** and fail if generation
   fails. Not a build, not a boot — emission only.
2. Assert the emitted output actually *contains* the rare features, not just that the command exited
   0. A generator that silently skips `selectors` would otherwise still pass. Cheapest useful
   assertion: the emitted `compiled-metadata.json` carries non-empty `selectors`, `documents`,
   `aggregates`, `autoPanels` catalogs, and the emitted flow code references the `forEach` /
   `onFailure` / `generatedAction` steps.
3. **Watch the cost.** The corpus-parse check already costs ~118 s; a generation adds more. If the
   combined gate approaches its new 20-minute cap, move *this* check to the PR gate (which has
   6m14s of 60 used, per F2b) rather than the knowledge gate.
4. Update the fixture's README and manifest — "validated, not run" becomes "validated and
   generated," which is the honest description and the point of the change.

**Definition of done.**
- [x] `dsl-conformance-max` is generated by a gate on every relevant PR, not just parsed — new step in
      `npdev-pr-gate.yml`, `scripts/quality/check-dsl-conformance-generates.py`
- [x] The check asserts the rare features are *present in the output*, not merely that generation
      exited 0 — 11 assertions against the compiled model (aggregates, autoPanels, documents,
      domainTypes, externalAi, guidePages, a selector-generated panel, step.forEach,
      step.generatedAction, step.onFailureSteps, flow.schedule); flow.hooks/flow.specializes
      deliberately not asserted at this layer (no compiled-output marker survives — see the script's
      own docstring), left to check-dsl-coverage.py's source-level check
- [x] Calibrated: deliberately break one emitter path and confirm the gate goes RED — real, live
      proof (not just a synthetic self-test): temporarily forced
      `CompiledModelCanonicalJson`'s `aggregates` field to an empty array, rebuilt, regenerated —
      check correctly failed naming "aggregates" missing; reverted (byte-identical diff confirmed),
      rebuilt, regenerated — green again
- [x] Measured runtime recorded; the check lives in whichever workflow has budget — ~10s measured
      locally (warm daemon); wired into `npdev-pr-gate.yml` (6m14s/60min used per F2b), not
      `ai-knowledge-gate.yml` (whose `paths:` filter deliberately excludes `NPDevGenerator/**` —
      widening it to cover a real generation check would repeat F1's own mistake)
- [x] Fixture README/manifest no longer say "validated, not run" — README.md, manifest.json,
      sample-catalog.json all updated to "validated and generated"

---

## G3 · 65 test files hand-build compiled objects, bypassing parser and validator

**What.** MEASURED — test files constructing `CompiledModel` / `CompiledFlow` directly:

| Module | Files |
|---|---|
| `NPDevGenerator` | **32** |
| `NPDevRuntimeHost` | 20 |
| `NPDevKernel` | 14 |
| `NPDevContract` | 6 |
| **Total** | **65** |

These validate the **compiled contract**: given a compiled shape, does the emitter/runtime do the
right thing? That is legitimate and fast, and most of them should stay exactly as they are.

**Why it matters.** They start *downstream of the authoring path*, so they cannot detect that the
authoring path can no longer produce the shape they test.

`TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest` builds and boots a real packaged app with
a `generatedAction`-shaped compiled step — and passed for the entire time no model could express one,
because it hand-constructs `CompiledModel` and never touches `JsonModelParser` or `SemanticValidator`.
**A green runtime proof coexisted with a broken authoring path, indefinitely.**

The gap is not the 65 tests. It is that **nothing joins the two ends.**

**Where.** The 65 files (inventory above) · `NPDevSamples/dsl-conformance-max/` (the join point) ·
`scripts/quality/check-dsl-coverage.py` (the natural place to extend).

**How to solve.** **Do not rewrite the 65 tests.** They are correct for what they test, and
converting them to full-path tests would make the suite far slower for little gain. Join the ends
instead:

1. **G2 is most of the fix** — generating the fixture makes the authoring path produce real compiled
   shapes for every rare feature, which is precisely the join that was missing.
2. Add the missing direction to `check-dsl-coverage.py`: for each tracked DSL feature, assert there
   is **at least one model-driven path** exercising it (corpus fixture), not only a hand-built one.
   Today the check counts corpus models — which is right — but the *reason* it matters should be in
   its docstring, because the next maintainer will otherwise assume unit coverage suffices.
3. Record the pattern once, in `CONTRIBUTING.md`, as a testing convention:

   > A test that hand-builds `CompiledModel` proves the **compiled contract**. It does **not** prove
   > a model can express that shape. Any feature reachable from `model.json` needs a corpus fixture
   > too — see `NPDevSamples/dsl-conformance-max`.

4. Optionally spot-check the highest-value few: does any *other* hand-built test assert a compiled
   shape no model can currently produce? `generatedAction` was found by accident; a targeted look at
   the 32 generator tests would show whether it was the only one.

**Definition of done.**
- [x] The convention is written in `CONTRIBUTING.md` — "A test that hand-builds `CompiledModel`
      proves the compiled contract, not the authoring path"
- [x] `check-dsl-coverage.py`'s docstring states *why* corpus coverage is not redundant with unit
      tests
- [x] G2's generation check exists, closing the join for every tracked feature
- [x] A spot-check of the 32 generator tests is recorded: **no other orphaned shapes found.**
      Surveyed all 31 test files matching `new CompiledModel(`/`new CompiledFlow(` under
      `NPDevGenerator/generator/src/test` (dbconfig: 18, emitters: 9, provenance: 2, schemaevolution:
      1, top-level: 2 — one short of the plan's own cited 32, not chased further). Every one tests
      emission-correctness for an already-authorable shape (bond FKs, tenant/composite unique
      indexes, additive migration behavior, reserved-column collisions, business/concept renaming,
      file-field JSON handles, trusted-action/generatedAction generation, migration-plan
      classification, sandbox/CSP security hardening against malicious input) rather than a second
      REG-65-class DSL surface unreachable from authoring. The systematic `check-dsl-coverage.py` gate
      (all 12 canonical `flowStep.type` values + every top-level schema section, checked against the
      REAL corpus on every PR) is a stronger, ongoing guarantee for the structural-surface question
      than a one-time manual read could be; this spot-check targeted the remaining, narrower question
      (an exotic hand-built shape unrelated to a tracked feature) and found none.

---

# Phase 3 — Ledger hygiene 🟢

## G4 · REG-62's title contradicts its own status, and Rule T2 cannot see it

**What.** MEASURED — `ledger/items/REG-62.yml`:

```yaml
title:  "allowedActions typed (C8 done); cross-referencing it against declared workbench actions
         still blocked on a typed-actions prerequisite"
status: DONE
```

F9 shipped the cross-reference, so the title's "still blocked" clause is stale. It renders into the
generated `docs/OPEN_ITEMS.md`, where a reader scanning titles sees a blocked item marked DONE.

**Why it matters.** Small on its own — one stale clause — but it is a **scope gap in Rule T2**, which
exists for exactly this contradiction class. T2 compares an item's strikethrough marker against its
**status text**; it does not read the **title**. So the one field a human scans first is the one field
the gate does not check.

I scanned all 67 ledger items: **this is the only instance.** Fixing it is 2 minutes; closing the
class is an hour and stops the next one.

**Where.** `ledger/items/REG-62.yml` (title) · `scripts/quality/check-register-consistency.py`
(Rule T2) · `docs/OPEN_ITEMS.md` (regenerated output).

**How to solve.**

1. Rewrite the title to describe the closed state, e.g.
   *"allowedActions is a typed array and is cross-referenced against the surface's declared actions."*
2. Extend Rule T2 to check the **title** as well as the status text: a `DONE`/`WITHDRAWN` item whose
   title contains `still blocked` · `not fixed` · `remains open` · `blocked on` · `unresolved` is a
   contradiction.
3. **Calibrate before fixing the title** — run the extended rule against the current ledger, confirm
   it fires on REG-62 (RED), then fix the title and confirm GREEN. That ordering gives a free, real
   calibration fixture instead of a synthetic one.
4. Regenerate `docs/OPEN_ITEMS.md`.

**Definition of done.**
- [x] REG-62's title describes its closed state — "allowedActions is a typed array and is
      cross-referenced against the surface's declared actions"
- [x] Rule T2 covers titles; calibrated RED against the pre-fix ledger, GREEN after — new Rule T2b
      (`ledger_title_status_contradiction_gaps`), calibrated against the REAL REG-62 @ commit
      `9c3c423` (fired) plus a synthetic mechanism control, both directions
- [x] `docs/OPEN_ITEMS.md` regenerated; `generate_open_items.py --check` clean
- [x] A re-scan of all 67 items reports zero title/status contradictions — 68 after REG-67 (filed
      incidentally, see below) was added; still zero contradictions

  **New gap found and filed while calibrating T2b, out of this item's own scope, not fixed here:**
  Rule T1/T2's own `--calibrate` real-instance controls read bare `git show HEAD:<path>`, which
  silently stopped proving anything once `docs/EXECUTION_TREES.md`/`NPDEV_OPEN_ITEMS_REGISTER.md`
  were edited again after 2026-07-28 (confirmed: reproduces identically against the unmodified,
  pre-this-session script). `main()`'s actual blocking checks are unaffected (they run against the
  live working tree, not HEAD); only the optional `--calibrate` self-test is stale. Filed as
  `ledger/items/REG-67.yml` (OPEN, LOW) rather than silently left broken or fixed as unplanned scope
  creep.

---

# Phase 4 — Owner ⬥

## G5 ★ · Three real conversations

**What.** Show NPDev to three specific people who fit a named scenario, and write down what they hit
in their first hour.

**Why it matters.** It is the last open item that can change *what gets built next*, and it has
slipped every plan in this sequence. The case has only strengthened:

- **B20** (bounded contexts) explicitly defers its own trigger to these conversations. The project is
  deferring design decisions to evidence it is not collecting.
- **REG-63** is what an internal-only feedback loop produces: 17 models broken for ~3 weeks, found by
  accident during unrelated work. An external user hits that class on day one.
- The repo is now in genuinely good shape to be seen — 15/15 gates, 0 open ledger items, a public
  `SECURITY.md`, `CONTRIBUTING.md`, an honest `SCREEN_TAXONOMY.md`. **The hygiene argument for
  delaying is spent.**

Everything else in this plan is maintenance on a healthy codebase. This is the only item with
strategic information on the other side of it.

**Where.** GitHub repo settings (description, topics) · the staged pitch ·
`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`.

**How to solve.**
1. Repo description + topics (`spec-driven-development`, `code-generation`, `spring-boot`,
   `workflow-engine`, `schema-migration`).
2. A short written pitch: the SDD framing, the three differentiators (schema evolution · durable flow
   engine · AI-authoring substrate), and the honest UI limitation from `SCREEN_TAXONOMY.md`.
3. **Three conversations, not a broadcast** — one legacy-4GL/GeneXus shop, one internal-tools team,
   one AI-app-builder skeptic.
4. **Ask each to regenerate an app.** That is the path REG-63 broke and the fastest test of whether
   the corpus work actually held for someone who is not you.
5. Record first-hour friction in the template.

**Definition of done.**
- [ ] Description and topics set — **owner-only, per this item's own framing; not attempted by an agent**
- [ ] Pitch written
- [ ] Three people have cloned, generated, and run something
- [ ] Their first-hour friction is written down in the friction log
- [ ] At least one deferred roadmap question (B20 is the obvious candidate) answered by evidence rather than deferred again

---

# Sequencing

```
NOW (15 min)     G1  revoke the token  🔴  ← the only item with external blast radius

DAY 1 (~half)    G4  REG-62 title + Rule T2 title coverage
                     └─ calibrate BEFORE fixing the title (free real fixture)

DAY 1-2 (~1 d)   G2  generate dsl-conformance-max in a gate  ★
                 G3  the convention + docstring + spot-check
                     └─ G2 is most of G3's fix; do them in one sitting

ANYTIME ⬥        G5  three conversations
```

## Why this order

**G1 first and immediately.** It takes fifteen minutes, it is the only item that can hurt anything
outside this machine, and it has now been flagged twice without action.

**G4 before G2** only because its calibration is *free right now* — REG-62 is a real, live instance of
the contradiction Rule T2 should catch. Fix the title first and you have to synthesize a fixture
instead. Same reasoning as capturing the corpus gate's RED run before migrating.

**G2 and G3 together.** They are one seam. Generating the fixture *is* the join; the convention and
docstring are what stop the seam reopening.

**G5 whenever the owner is ready** — it blocks nothing technical and unblocks everything strategic.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| G1 deferred again as "probably fine" | **Medium** | It has been flagged twice already. Fifteen minutes, no dependencies, do it first |
| G2's check passes on a generator that silently skips a feature | **Medium** | Assert feature **presence in the output**, not just exit 0. Calibrate by breaking one emitter path |
| G2 pushes the knowledge gate past its 20-min cap | **Medium** | Measure; move the check to the PR gate, which has 6m14s of 60 used |
| G3 read as "rewrite the 65 tests" | **Medium** | The plan says explicitly not to. They are correct for what they test; only the join was missing |
| G4's Rule T2 extension produces false positives on legitimate titles | Low | Match a small closed phrase set; calibrate against all 67 items before wiring blocking |
| G5 slips again | **High** | It has slipped every plan. Three conversations, not a project |

## Overall definition of done

- [ ] The exposed token is revoked and the convention recorded — convention done; **revocation is an
      owner action, flagged directly to the user, not completable by an agent**
- [x] Every tracked DSL feature is proven to **generate**, not merely parse — with the assertion
      calibrated (synthetic + a real, live RED-then-GREEN proof against an actual emitter regression)
- [x] The hand-built-vs-authored testing seam is documented, and the join exists in a gate
- [x] Zero title/status contradictions across all 68 ledger items, gate-enforced (Rule T2b)
- [ ] **Three people outside this machine have regenerated an app, and what they hit is written down**
      — G5, explicitly owner-only; out of agent scope, not attempted here

**STATUS: CLOSED except G1's revocation and G5 (both owner-only).** G2, G3, G4 fully executed
2026-07-29 — full evidence in each item's own Definition of Done above.

---

*Companions: `docs/FINAL_OPEN_ITEMS_PLAN.md` (predecessor, F1–F10 closed) ·
`ledger/items/REG-62.yml`, `REG-63.yml`, `REG-65.yml` ·
`NPDevSamples/dsl-conformance-max/Input/README.md` (G2's subject) ·
`scripts/quality/check-dsl-coverage.py` · `scripts/quality/check-register-consistency.py` (G4) ·
`docs/ACCEPTED_BOUNDARIES.md` B20 → G5 · `SECURITY.md` (G1).*
