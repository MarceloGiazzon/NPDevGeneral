# DSL 2.0 + God-File Decomposition — Execution Plan

> **STATUS: ACTIVE.** Live backlog. Written 2026-07-27 against `beta1-vision-spine` @ `8bc3715`
> (+ T1.15 in flight). Covers the **remaining TREE 1 items** and **TREE 2 items 2.A and 2.B**
> from `docs/EXECUTION_TREES.md`.
>
> **Staged outside the repo** while T1.15 (SemanticValidator split) is in flight. Move in with:
> ```powershell
> Move-Item "<scratchpad>\DSL2_AND_DECOMPOSITION_PLAN.md" `
>           "D:\WorkSpace\NPDev\NPDev_General\docs\DSL2_AND_DECOMPOSITION_PLAN.md"
> ```
>
> **Everything below is grounded in measurements taken 2026-07-27**, not in documentation.
> Where a number appears, it was counted. Where a design choice appears, the evidence for it is named.

---

## Part 0 — Preconditions and measured state

### 0.1 What is already true

| Fact | Value | How established |
|---|---|---|
| TREE 1 items closed | 11 of 12 | verified individually 2026-07-27 |
| Commits landed today | 12 (`f76b95f`…`8bc3715`) | `git log` |
| Both quality gates | green | re-run after each check |
| T1.15 progress | `SemanticValidator` **4,244 → 202 lines**; 12 new `*Validation.java` classes, 6,390 lines total in the package | `wc -l`, `git status` |
| Alias canonicalization site | **ONE switch**, `JsonModelParser.java:2089-2100` | grep |
| Schema copies | 4; three byte-identical, the 4th differs only by `canonicalSchema` + `deprecated` keys (**semantically identical**) | normalized JSON diff, 3 lines |

### 0.2 The one blocking item still open

**T1.6 — merge to `main` and re-tag. Not done.**

```
git rev-list --count main..HEAD   → 150
latest tag                        → beta1.1   (predates REG-48/50/51/52/53 + all 12 of today's commits)
main HEAD                         → 98a3cb9 "Merge PR #4 …"
```

**C1's owner verdict is "(a) Make the repository public."** Publishing with `main` 150 commits stale
would ship a default branch missing every security fix the register calls closed. **T1.6 gates 3.1,
and 3.1 gates everything strategic.** It is the first task in Part 1.

### 0.3 The measurement that reshapes 2.A

I scanned **27 real model files** (`AppGen/apps/**`, `NPDevSamples/**`) for actual alias usage:

```
=== flowStep.type values IN USE ===
  return             x89    canonical
  capabilityCall     x39 →  capability      ALIAS
  emitEvent          x38 →  event           ALIAS
  createConcept      x26    canonical
  validate           x20 →  invariant       ALIAS
  enforceInvariants  x19 →  invariant       ALIAS
  event              x13    canonical
  updateConcept      x11    canonical
  callCapability      x6 →  capability      ALIAS
  capability          x4    canonical
  invariant           x3    canonical
  if                  x3 →  branch          ALIAS
  branch              x2    canonical
  waitForEvent        x2 →  await           ALIAS
  assign              x1 →  map             ALIAS
  scheduleEvent       x1    canonical
  awaitEvent          x1 →  await           ALIAS

=== field aliases IN USE ===
  out x79 · target x46 · cap x41 · op x41 · actions x21
```

**Two findings that change the design:**

1. **The alias beats the canonical form in real usage.** `capabilityCall` (39) vs `capability` (4).
   `emitEvent` (38) vs `event` (13). The parser normalizes *toward the minority spelling*.
2. **The parser's canonical form disagrees with the runtime enum in 4 of 9 cases:**

   | Runtime (`FlowStepDefinition.Type`) | Parser canonical | Agree? |
   |---|---|---|
   | `INVARIANT_CHECK` | `invariant` | ✗ |
   | `CAPABILITY_CALL` | `capability` | ✗ |
   | `EMIT_EVENT` | `event` | ✗ |
   | `AWAIT_EVENT` | `await` | ✗ |
   | `SCHEDULE_EVENT` | `scheduleEvent` | ✓ |
   | `BRANCH` | `branch` | ✓ |
   | `MAP` | `map` | ✓ |
   | `RETURN` | `return` | ✓ |
   | `FOR_EACH` | `forEach` | ✓ |

**Therefore the naive migration ("keep the parser's canonical form, delete the aliases") is wrong.**
It would force ~110 rewrites *away* from the spelling authors already prefer *and* keep the DSL
misaligned with the runtime. **The canonical form should be the camelCase of the runtime enum.** That
choice simultaneously: matches majority author usage, aligns DSL ↔ runtime one-to-one, and makes
`FLOWS.md`'s step table describe both layers with one name each.

### 0.4 Open question the corpus raised

`createConcept` (26) and `updateConcept` (11) are canonical in the parser
(`ModelCompiler.java:1545,1590`) but **are not members of `FlowStepDefinition.Type`.** They are
compiled into something else — most likely a generated-action or capability call. **Resolve this
before writing the 2.A schema** (task 2.A.0); a DSL 2.0 that ships an enum omitting 37 real uses
would be a worse contract than today's.

---

# Part 1 — Remaining TREE 1

---

## R1 🔴 T1.6 — merge to `main`, tag `beta1.2`

**Goal.** `main` and the release tag contain every fix the register calls closed.

**Why.** See §0.2. This is the only TREE 1 item still open and it blocks publishing, which C1 has
already decided to do.

**Steps.**

```powershell
cd D:\WorkSpace\NPDev\NPDev_General
git fetch origin
git log --oneline HEAD..main        # inspect the 2 main-only commits before merging
gh pr create --base main --head beta1-vision-spine `
  --title "beta1-vision-spine -> main: schema engine, REG-36..53, TREE 1 closure, external-AI delegation" `
  --body "150 commits. Both gates green locally. Includes REG-48/50/51/52/53 and TREE 1 T1.1-T1.16."
```

Open a **PR** — do not fast-forward locally. Both workflows must run on the merge, and this is the
first CI run that exercises `:generator:behaviorTest` and the editor vitest step.

After green + merge:

```powershell
git checkout main; git pull
git tag -a beta1.2 -m "beta1.2 -- REG-48/50/51/52/53, schema-engine rebuild, TREE 1 closure"
git push origin beta1.2
```

> Do **not** move or delete `beta1.1`. Supersede, never rewrite — this project's own tag rule.

**Acceptance.** `git rev-list --count main..HEAD` = 0. `beta1.2` on the merge commit. Both workflows
green **on GitHub**, not just locally.

**Watch for.** This PR is the first real test of T1.2 and T1.12 in CI. If `npm ci` in the new editor
step pushes the PR gate past its 60-minute budget, raise `timeout-minutes` rather than dropping the
step.

**Effort.** S (1 hr + CI wait). **Blocks.** 3.1 (publish).

---

## R2 — Finish T1.15 and re-baseline the editor complexity gate

**Goal.** Land the SemanticValidator split cleanly; stop carrying a red gate.

**Current state.** `SemanticValidator` 202 lines; 12 extraction classes created but **uncommitted**.
Largest extraction: `ConceptValidation` 770, `FlowValidation` 731, `UxMetadataValidation` 627.

**Steps.**

1. **Verify the split is behavior-neutral** — the whole point:
   ```powershell
   cd D:\WorkSpace\NPDev\NPDev_General
   .\gradlew :NPDevContract:dsl:test --rerun-tasks --console=plain
   ```
   **Acceptance is exactly 355 tests, 0 failures** — the same count as before the split, not merely
   "green." A changed count means a test was renamed, skipped, or lost.
2. **Diagnostic messages must be byte-identical.** They are asserted in tests, which is the safety
   net; if any assertion needed editing, the split changed behavior and must be revisited.
3. **Commit as a pure-move commit.** No bug fixes mixed in. If the split surfaced a bug — likely,
   given 137 helpers moving — file it and fix it in a *separate* commit.

4. **Re-baseline `run-editor-complexity-check.ps1`.** It currently exits red with three failures,
   all verified **not** caused by T1.4 (the T1.4 commit message says so, and both files' last
   commits pre-date the T1.x series):

   | Failure | Real? | Action |
   |---|---|---|
   | `FieldDetailsEditor.tsx` 340 lines > 300 | ✅ pre-existing | split it, or raise the threshold with a recorded reason |
   | `ReferencePickerDesigner.tsx` 307 > 300 | ✅ pre-existing | same |
   | `frontendBuild/TestsPassed: false` | ❌ local only — no `node_modules` | run `npm ci` first; CI does |

   Do not leave a gate permanently red. Either fix the two components or record an allowlist entry
   with a named reason and a revisit date — the project's own `security-pattern-sweep-allowlist.json`
   precedent.

**Effort.** S (finish) + S (re-baseline). **Depends on.** Nothing.

---

## R3 — Two cosmetic accuracy items

Both found during verification; neither is urgent, both are 5-minute fixes that keep the "no false
statement" bar.

- **`ui-boundary.json`:** `allowed` has 81 entries, `files` has 80 —
  `authoring/app/AuthoringPlaceholder.tsx` is in the former, missing from the latter. Verified
  **pre-existing** (the same 1-off existed at 81+32 vs 112), not a T1.4 regression. Add it to `files`.
- **`docs/FLOWS.md:127`** says `EXECUTION_TREES.md` "should be fixed" regarding the `PRE`/`FOR_EACH`
  error. It was already fixed in the same session. Update the sentence to past tense.

---

# Part 2 — 2.A · DSL 2.0, the aggression play

> **Time-box: 1 week. Must land before the first external user has an app in production.**
> Cost now: one regeneration of 27 model files. Cost after: 50–100×, permanently.

## 2.A — Why this is worth a week

The DSL currently accepts **23** `flowStep.type` spellings for **9** real behaviors, plus 12 field
alias pairs, plus at least 5 spellings the schema does not even declare (`createentity`,
`conceptcreate`, `updateentity`, `conceptupdate`, `await_event` — all live in the parser switch).

Every alias is: a branch in `JsonModelParser`, a case in validation, a row in `DSL_REFERENCE.md`, and
**a way for an LLM to produce inconsistent models**. That last one is the real cost — the AI-authoring
path is the strategic bet, and a 61%-redundant vocabulary is the single largest source of avoidable
model variance.

**The enabling fact:** canonicalization happens in **one switch** at `JsonModelParser.java:2089-2100`.
This is a far smaller blast radius than the alias count suggests.

---

## 2.A.0 ⚠️ GATE — resolve `createConcept`/`updateConcept` first

**Do not write any DSL 2.0 schema until this is answered.**

`createConcept` (26 uses) and `updateConcept` (11) are canonical in the parser but absent from
`FlowStepDefinition.Type`'s nine values. Trace `ModelCompiler.java:1545` and `:1590` and determine
which of the 9 runtime kinds they compile into (probable: a generated-action or capability call).

**Then decide, explicitly:**

- **(a)** They are sugar over an existing kind → DSL 2.0 keeps them as *declared sugar*, documented
  as such, expanding to the canonical kind at parse time.
- **(b)** They are genuinely distinct behaviors → the runtime enum is **incomplete** and should gain
  `CREATE_CONCEPT`/`UPDATE_CONCEPT`, making it 11 kinds.

**This is a real fork.** Option (b) means `FLOWS.md`'s "9 step kinds" is itself understated, which
would be the *third* error found in that step-kind list. Write the answer into `FLOWS.md` either way.

**Effort.** S (2 hr). **Blocks.** Everything else in 2.A.

---

## 2.A.1 Choose canonical names — align DSL to the runtime enum

**Decision (recommended, per §0.3):** canonical DSL name = camelCase of the runtime enum constant.

| Runtime enum | DSL 2.0 canonical | Today's canonical | Aliases retired |
|---|---|---|---|
| `INVARIANT_CHECK` | `invariantCheck` | `invariant` | `validate`, `enforceInvariants`, `evaluateInvariant`, `invariant` |
| `CAPABILITY_CALL` | `capabilityCall` ✅*majority* | `capability` | `callCapability`, `capability` |
| `EMIT_EVENT` | `emitEvent` ✅*majority* | `event` | `event` |
| `SCHEDULE_EVENT` | `scheduleEvent` | `scheduleEvent` | — |
| `BRANCH` | `branch` | `branch` | `if` |
| `AWAIT_EVENT` | `awaitEvent` | `await` | `waitForEvent`, `await`, `await_event` |
| `MAP` | `map` | `map` | `assign` |
| `RETURN` | `return` | `return` | — |
| `FOR_EACH` | `forEach` | `forEach` | `loop` |
| *(sugar, resolved 2.A.0)* | `createConcept` | `createConcept` | `createEntity`, `conceptCreate` |
| *(sugar, resolved 2.A.0)* | `updateConcept` | `updateConcept` | `updateEntity`, `conceptUpdate` |
| *(sugar, found during implementation)* | `generatedAction` | `generatedAction` | `generated_action` |

> **2.A.0 resolved (verified 2026-07-27):** `ModelCompiler.isConceptPersistenceStep`/
> `isCapabilityLikeStep` (`:1539-1547`, `:1587-1592`) confirm `createConcept`/`updateConcept` resolve
> unconditionally to capability `"persistence"`, operation `"save"` — **(a) sugar**, not a distinct
> runtime behavior. `FLOWS.md`'s "9 step kinds" stands correct.
>
> **A gap found while implementing this table:** it omitted `generatedAction` entirely.
> `generatedAction` is real, current, documented DSL surface (`docs/NPDEV_CONCEPTS_DEEP_DIVE.md:152`,
> `docs/DSL_REFERENCE.md:127`) that also compiles to `CAPABILITY_CALL` sugar (capability type
> `GeneratedActionCapability`, adapter `generated-action`, per `ModelCompiler.java:1553,1563,1574,1581`)
> — the same shape as `createConcept`/`updateConcept`, just missed by the table's original 9-row scope.
> Kept as its own canonical name (retiring only its `generated_action` snake_case twin) rather than
> silently folded into `capabilityCall`, since it carries its own required field (`actionName`) and
> its own documented meaning. **Canonical set is therefore 12 names, not 9 or 11.**

**Why this specific choice, stated for the record:** it moves *toward* what authors already write in
77 of 129 alias occurrences, it makes DSL ↔ runtime a one-to-one name mapping (so `FLOWS.md` needs
one column, not two), and it eliminates the class of confusion that produced the `PRE`/`FOR_EACH`
error in the first place — a reader who sees `emitEvent` in JSON and `EMIT_EVENT` in Java needs no
translation table.

**Field aliases — keep the longer, unambiguous name in every case:**

> ⚠️ **Corrected after a second scan.** The first scan covered app models only. A wider sweep of
> `golden-ai-scenarios/` (145 files) and `NPDevRuntimeHost/src/test/resources/` (23 files) found
> **73 further alias uses**, including four aliases the first scan recorded as unused. The
> "uses" column below is the **combined** figure. See §2.A.6 for the full second-scan result.

| Keep | Retire | App models | Fixtures/scenarios | **Total** |
|---|---|---|---|---|
| `capability` | `cap` | 41 | 1 | **42** |
| `operation` | `op` | 41 | 1 | **42** |
| `output` | `out` | 79 | 1 | **80** |
| `awaitRef` | `as` | 0 | **3** | **3** |
| `position` | `at` | 0 | 0 | 0 |
| `targetStep` | `target` | 46 | **25** | **71** |
| `concept` | `targetConcept` | 0 | **10** | **10** |
| `event` | `eventName` | 0 | **21** | **21** |
| `capability` | `capabilityName` | 0 | **1** | **1** |
| `map` | `fieldMap` | 0 | 0 | 0 |
| **`actions`** (list) | `action` (scalar) | 21 | 7 | **28** |

**Only `at` and `fieldMap` are genuinely unused** — those two can be deleted from the schema with no
codemod work at all. Every other alias has real uses somewhere.

> **Correction (found during implementation):** this row was mislabeled as `awaitEvent`/`awaitRef` —
> those are two genuinely different `flowStep` fields (the awaited event's *name* vs. the flow-state
> ref its matched payload is bound to), not aliases of each other. The real pair, confirmed via
> `JsonModelParser.java:1423` (`awaitRef = firstNonBlank(readText(stepNode, "awaitRef"),
> readText(stepNode, "as"))`) and the literal `"awaitRef"` usage found in
> `NPDevRuntimeHost/src/test/resources/npdev/async-wait-resume-compiled-model.json`, is **keep
> `awaitRef`, retire `as`** — `awaitRef` is already the canonical AST field name and the spelling the
> 3 fixture uses already write; `as` is the JSON shorthand alias being retired. (`as` is a separate,
> unrelated property name in other `$defs` objects — e.g. pack-ref aliasing — and retiring it here
> only touches `flowStep`'s own schema properties, not those.)

> **`action` vs `actions` needs its own call.** It is a scalar-or-list field — the worst kind, because
> every consumer must branch. 21 corpus uses take the list form. **Recommendation: `actions`, always
> a list**, single-element where needed. Normalizing shape is worth more than saving a character.

**Effort.** S (decision + write-up). **Depends on.** 2.A.0.

---

## 2.A.2 Write the DSL 2.0 schema — across all four copies

**The mirroring rule is real and CLAUDE.md names it.** Verified state: three copies byte-identical,
the fourth (`NPDevContract/dsl/resources/Schemas/model.schema.json`) semantically identical plus two
provenance keys (`canonicalSchema`, `deprecated`). **The mirror script must preserve those two keys**
or it will destroy the legacy-location marker.

> **Sequencing correction (found during implementation).** Steps 2 and 3 below as originally written
> ("reduce the enum", "remove the retired aliases") **cannot happen before 2.A.3's byte-identical
> proof loop runs**: that loop's `before = compile(model)` call runs the corpus's un-migrated,
> alias-spelled models through schema validation *before* migrating them. If the enum/aliases were
> already narrowed at that point, `before = compile(model)` would fail schema validation on every
> alias-using model in the corpus, before the proof could even start. The corrected order: 2.A.2
> **adds** the new canonical names as valid schema values (a widening, non-breaking change — old
> aliases stay valid too) and ships the mirror gate; the actual narrowing/removal of old aliases
> (the breaking half) moves to 2.A.4, landing in the same commit as the parser-switch collapse and
> the `BREAKING.md` entry, immediately after the corpus is migrated — matching this project's own
> "ship the codemod with the break" rule.

**Steps.**

1. **Widen**, don't yet narrow: add every new canonical name (`invariantCheck`, `map`, plus the
   already-canonical `capabilityCall`/`emitEvent`/`scheduleEvent`/`branch`/`awaitEvent`/`return`/
   `forEach`/`createConcept`/`updateConcept`/`generatedAction`) to `flowStep.type.enum` if not already
   present. Leave all 23 old spellings in place for now — they're retired in 2.A.4, not here.
2. Keep `schemaVersion` semantics as-is; `dslVersion` bumps to `2.0.0` in 2.A.4, alongside the actual
   break (a version bump before anything actually changed behavior would be its own inaccuracy).
3. Do **not** yet remove the retired field aliases (`cap`/`op`/`out`/`at`/`target`/`targetConcept`/
   `eventName`/`capabilityName`/`fieldMap`/scalar `action`) — same reasoning as step 1. They retire in
   2.A.4.
4. **Write a mirror check, not a mirror habit.** A tiny gate that asserts the four copies are
   semantically identical (modulo the 2 provenance keys) and fails the build otherwise. Today's
   3-line drift is benign; the next one might not be. Wire into `run-ai-knowledge-gate.ps1`. This part
   ships now, unconditionally — it protects whichever schema state exists at any given moment.

**Acceptance.** All four copies semantically identical (mirror gate green). Every model in the
corpus — old spellings and new — still validates against the widened schema. `DSL_REFERENCE.md`
regenerates (`scripts/docs/generate_dsl_reference.py`).

**Effort.** M (1 day). **Depends on.** 2.A.1.

---

## 2.A.3 ★ Build `npdev migrate --dsl-2`

**This is the item that makes aggression sustainable.** Not a one-off script — the first instance of
a permanent capability.

**Requirements.**

- **Idempotent.** Running twice equals running once.
- **Order-independent** and **comment-preserving** where the format allows.
- **Reports, does not guess.** Anything ambiguous (a scalar `action` that cannot be safely listified,
  an unknown `type`) is reported and left untouched, never silently rewritten.
- **Dry-run by default**, `--write` to apply. RED-first: prove it reports before it rewrites.
- **Rewrites in place** across `AppGen/apps/**`, `NPDevSamples/**`, and any `*.concept.json` split
  models — the scan found 27 files; the codemod must find the same 27.

**Test corpus, already available:** the 27 real model files are the regression suite. Migrate them,
then assert every one still compiles and produces a **byte-identical compiled model** to its pre-
migration compilation. That last assertion is the real proof — the DSL changed, the *meaning* did not.

```
for each model in corpus:
    before = compile(model)                    # canonical JSON
    migrate(model)
    after  = compile(model)
    assert before == after                     # byte-identical
```

**Acceptance.** All 27 migrate; 27/27 compiled models byte-identical before vs after; dry-run output
matches what `--write` actually did.

**Effort.** M (2 days). **Depends on.** 2.A.2.

---

## 2.A.4 Collapse the parser switch and flip the corpus

**Steps.**

1. Run `npdev migrate --dsl-2 --write` over the full 195-file/430-site corpus (§2.A.6 -- not just the
   27 app models 2.A.3's proof loop used; the corpus that must be alias-free is the union of
   `AppGen/apps` + `NPDevSamples`, `golden-ai-scenarios`, and `NPDevRuntimeHost/src/test/resources`).
2. **Now** narrow the schema (moved here from 2.A.2, see that section's sequencing correction): remove
   the 23 old `flowStep.type` spellings down to the 12 canonical names, and remove the retired field
   aliases (`cap`/`op`/`out`/`at`/`target`/`targetConcept`/`eventName`/`capabilityName`/`fieldMap`/
   scalar `action`), across all four schema copies. Bump `dslVersion` to `2.0.0` here, in this same
   commit -- the version number changes when the break actually ships, not before.
3. **Delete the alias arms** from `JsonModelParser.java:2089-2100`, leaving a 1:1 map. Unknown values
   now produce a validation diagnostic naming the canonical replacement — *refuse, don't silently
   accept*, matching REG-51's precedent.
4. Regenerate the sample apps; run the full suites.
5. Update `DSL_REFERENCE.md`, `FLOWS.md` §3, `AI_MODEL_TO_DSL_MAPPING.md`, and the MCP/RAG corpora
   (`python scripts/ai/build_knowledge.py`) — **the AI substrate teaches the DSL; a stale corpus
   would keep generating v1 models.**
6. Add the `BREAKING.md` entry, in the same commit, per the charter.

**Acceptance.** Zero alias spellings remain in the corpus. `:NPDevContract:dsl:test` green. A v1 model
now produces a *helpful* diagnostic, not a silent acceptance. Knowledge corpora rebuilt.

**Effort.** M (1 day). **Depends on.** 2.A.3.

---

## 2.A.5 Adopt the standing rule

> **Every breaking change ships its codemod in the same commit.**

Record in `CLAUDE.md` and `BREAKING.md`. This is what lets aggression survive contact with users — it
converts breaking changes from exceptional events into routine ones, which is a durable structural
advantage over every incumbent rather than a temporary one.

**Effort.** ⚡

---

## 2.A.6 ⚠️ The migration surface is wider than the app corpus

**Second scan, run 2026-07-27** — this is the finding that most changes 2.A's scope:

| Location | Files | Alias hits | Detail |
|---|---|---|---|
| `AppGen/apps/**` + `NPDevSamples/**` | 27 | **357** | 8 step-type aliases + 5 field aliases |
| `golden-ai-scenarios/**` | 145 | **28** | `target` ×25, `action` ×3 |
| `NPDevRuntimeHost/src/test/resources/**` | 23 | **45** | `eventName` ×21, `targetConcept` ×10, `action` ×4, `awaitRef` ×3, + `await`/`capabilityCall`/`awaitEvent`/`out`/`op`/`cap`/`capabilityName` ×1 each |
| `test-fixtures/`, generator/dsl test resources, `knowledge/` | 20 | **0** | clean |
| | **195 files** | **430 hits** | |

**Three consequences.**

1. **`npdev migrate --dsl-2` must cover all three trees**, not just app definitions. Scoping it to
   `AppGen/apps` — the obvious reading — would leave 73 hits behind and break test fixtures the
   moment the parser switch is collapsed.
2. **`golden-ai-scenarios/` is the AI substrate's ground truth.** Migrating it is not optional
   cleanup: those 145 files teach the model what valid NPDev looks like. Miss them and the AI keeps
   emitting v1 syntax against a v2 parser — the worst possible failure mode for the strategic bet.
3. **`RuntimeHost` test resources are the densest alias site** (45 hits in 23 files). Those fixtures
   deliberately exercise unusual spellings, which is exactly why they are the best regression proof
   that the codemod is complete — and exactly why they are easy to forget.

**Acceptance for 2.A.3 is therefore 195 files, 430 sites — not 27 files.**

---

## 2.A — risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| ~~A retired alias is used somewhere the scan missed~~ | **CONFIRMED** | §2.A.6 quantifies it: 73 extra hits across 168 files. Codemod scope widened |
| The AI knowledge corpora keep teaching v1 | **High** | 2.A.4 step 4 is not optional; `golden-ai-scenarios/` is in the migration set per §2.A.6 |
| `createConcept` turns out to be a distinct behavior | Medium | 2.A.0 is a gate for exactly this |
| Schema mirror drifts during the edit | Medium | 2.A.2 step 5 makes it a gate, not a habit |
| Compiled-model fingerprints shift → spurious migrations on existing apps | **Medium-High** | The byte-identical assertion in 2.A.3 is the control. If fingerprints *do* shift, that is a REG-53-shaped finding and must be understood before shipping |
| A fixture's alias is load-bearing (a test asserts the alias is *accepted*) | **Medium** | Expect 1–2 tests that exist to prove alias tolerance. Those tests should be **deleted**, not migrated — the tolerance is the thing being removed. Identify them before running the codemod |

---

# Part 3 — 2.B · God-file decomposition

> **2–3 weeks. Mechanical, compiler-verified, low risk — but only if the discipline holds.**

## 3.0 The enabling fact, restated

All five files have **zero instance fields** and are dominated by `private static` helpers (458
across the five). They are **stateless function libraries in one file**, not stateful god objects. A
`private static` method with no field access moves to a package-private class by qualified name —
**the compiler proves each move correct**, and 72k lines of tests prove behavior unchanged.

**T1.15 has already validated the method** on the largest one: `SemanticValidator` 4,244 → 202 lines,
12 extractions, no behavior change. Apply the same recipe four more times.

## 3.1 The rules — non-negotiable

1. **One pass, zero behavior changes.** No bug fixes, no renames, no "while I'm here."
   A refactor that also fixes bugs is a refactor nobody can review.
2. **Test count must be identical**, not merely green. A changed count means something was lost.
3. **Pure-move commits.** If a bug surfaces — likely — file it, fix it in a separate commit after.
4. **One file per branch.** Do not batch two god files into one PR.
5. **Target ≤ 800 lines per resulting file.** Not a hard gate; a smell threshold.

---

## 3.2 Order — easiest-first, highest-risk-last

### 2.B.1 ✅ `SemanticValidator` — DONE (T1.15)
4,244 → 202 + 12 classes. Finish per **R2**.

---

### 2.B.2 `TrustedSourceEmitter` — 3,782 lines, 77 statics · **1 day**

**Seams are already visible in the nested types:**

```
584   TrustedJavaSourcePolicyVisitor  (extends TreeScanner)  ← self-contained, extract first
651   InMemoryTrustedJavaSource       (extends SimpleJavaFileObject) ← self-contained
3695  TrustedReference · ManifestEntry · TrustedProcedure · TrustedPanel
      TrustedFlow · TrustedWidget · PanelAssets                ← a model package
```

**Split.**
- `trustedsource/policy/TrustedJavaSourcePolicyVisitor.java` — the AST policy scanner, zero coupling
- `trustedsource/compile/InMemoryTrustedJavaSource.java`
- `trustedsource/model/` — the 7 records, one file each
- `TrustedSourceEmitter` retains orchestration

**Acceptance.** `:generator:test` **and** `:generator:behaviorTest` identical counts. Generated output
byte-identical for the sample corpus — this emitter's output is hash-verified, so a diff is a failure.

---

### 2.B.3 `GeneratedCrudRuntimeSupport` — 5,125 lines, 136 methods, 17 nested types · **2 days**

**Largest file, but the clearest seams** — the nested types cluster by domain:

```
125-179    UniqueValueLookup · UniqueFieldLookup · CompoundUnique* ·
           InvariantViolationDetail · InvariantViolationException   → uniqueness + invariants
2272       FileHandleRef                                            → file store
2510       ScheduledEventSql                                        → scheduling
2676-2776  EventCreateOrchestration · EventCapabilityOrchestration ·
           EventScheduleOrchestration · RuntimeOrchestration ·
           RuntimeOrchestrationAction · ScheduledEventRecord ·
           OrchestrationActionExecutionResult ·
           OrchestrationExecutionClaim                              → orchestration (8 types!)
4029       BondRuntimeShape                                         → bonds
```

**Split.**
- `crud/uniqueness/` — the 4 lookup interfaces + invariant types
- `crud/orchestration/` — the 8 orchestration records + their statics. **This is a subsystem hiding
  in a file**; it is the largest single extraction available anywhere in 2.B.
- `crud/scheduling/ScheduledEventSql`
- `crud/files/FileHandleRef`
- `crud/bonds/BondRuntimeShape`

> ⚠️ **Module placement is also wrong.** This class lives in `adapters/expression-cel` — a CRUD
> runtime support class is not a CEL concern. **Fix the module in a separate, later commit**, not
> during the split (moving modules changes the jar layout, which touches `sync-runtimehost-libs.ps1`
> and every generated app's classpath — a different risk class entirely).

**Acceptance.** Kernel + adapter suites identical counts. **A generated app's full suite green** —
this class is on every generated app's runtime path, so unit tests alone are insufficient. Use the
`rebuild-app` skill and run a real app's suite.

---

### 2.B.4 ★ `SchemaLifecycleExecutor` — 3,739 lines, 100 methods, 8 passes · **3 days**

**CORRECTION (found while implementing, verified directly against the file and `build.gradle`,
not re-derived from this plan's text): the premise below is stale.** This section describes REG-6
as closed "as re-scoped" with "the landmine still armed." In fact a *separate*, later initiative —
**SER Phase 4** (17 commits, `git log --grep="SER-P4"`) — already disarmed it: a canonical
`SchemaDiffEngine`/`SchemaDiff` (package `com.finalexec.db.schemastate`, 17 small files, 12-256
lines each) now computes one live-vs-desired diff that table-rename, column-rename, type-widening,
and required-field-backfill all consume directly. `build.gradle`'s `test` task carries this comment
verbatim: *"SER Phase 4 COMPLETE: classify + SchemaDeltaReport + all four mutation passes now
consume the canonical SchemaDiff directly (no re-derivation left to self-check)... The end-to-end
guard is now the behavior tests + the shadow parity assert (`npdev.schema.shadow.assert`, ON by
default), which stays on."* Separately, a `ColumnFacts` record (line 113) already exists exactly as
item 1 below describes (`platformManaged`, `repairablePlatformColumn`, `additiveEligible`,
`requiredByModel`, `declaredType`, `renamedFrom`, `literalDefaultJson`, plus a `bond()` derived
method), with its own REG-6-dated header directive and its own guard test
(`SchemaLifecycleExecutorColumnFactsTest`) — currently consumed by one call site (the
platform-column/agreement check), the others' own semantics now flowing through `SchemaDiff`
instead. The REG-53 "hardcoded `VARCHAR(255)` at four sites" gap is also already closed (commit
`dad211c`, in `SqlTypeSupport.java`, a different file) — `maxLength` reaches DDL correctly today.

**What this means for scope:** items 2-4 below (a `SchemaPass` interface, eight implementations,
`SchemaLifecycleExecutor` as a thin sequencer) were never built, and the file is still one
3,739-line class. But the *reason* 2.B.4 gave for needing that redesign — "eight passes each
re-derive column semantics independently, and disagree" — is no longer true; `SchemaDiffEngine` +
the shadow-parity assert already close that class of bug. **This demotes 2.B.4 from "design work"
to the same kind of pure-mechanical file split as 2.B.2/2.B.3**: extract the existing, already-
correct, already-tested passes into their own files, without inventing a new `SchemaPass`
abstraction that nothing in the codebase asked for once the diff engine did the actual job.

**The seams are the passes, and their records mark the boundaries:**

```
1439  ColumnRenamePlan(renames, skipped, staleWarnings)
1623  WideningPlan(widened, skippedTables)
1811  BackfillItem(table, column, refusal)
2775  HistoryPoint(toFingerprint, appliedAtUtc)
3277  SqlRunnable
3567  SchemaManifest   (public — the module's contract, leave in place)
```

**Revised scope (mechanical, not a redesign).**

1. ~~`ColumnFacts` — computed once~~ — already exists (line 113); leave as-is.
2. ~~`SchemaPass` interface~~ — **dropped**. `SchemaDiffEngine` already plays this role; inventing a
   parallel abstraction would be a second, competing "single source of truth" for the same question.
3. Extract the existing pass bodies into their own files under a `db/lifecycle/` (or similar)
   subpackage using the same static-function-library + `import static` pattern proven on 2.B.2/
   2.B.3: table-rename, column-rename, type-widening, backfill, destructive-recreation,
   platform-column tightening/agreement, history/fingerprint tracking, DDL-execution/audit helpers.
4. `SchemaLifecycleExecutor` keeps its two genuine instance fields (`compiledModel`,
   `conversionHooksAppliedLastDecision`) and its two public entry points (`migrate`, `afterMigrate`)
   — it becomes a thinner orchestrator, not a from-scratch sequencer.

**Why this one still deserves real care even though the redesign turned out unnecessary.** It is
the component whose blind spots have historically produced the most findings (REG-6, REG-40,
REG-53, LNCH-1-B7/B8/B9) — that history is why the acceptance bar below stays high even though the
work itself is now mechanical.

> REG-40 (additive migration never CREATEs new tables — TREE 3 item 3.3) is unaffected by this
> correction and is still a real, separate, open gap; it is not blocked on 2.B.4 either way now that
> 2.B.4 is a pure move.

**Acceptance.** `com.finalexec.db.*` package test baseline (captured before the split): **266
tests, 0 failures** (`SchemaLifecycleExecutorProofMatrixTest` 42/0 within that total). The Postgres
proof matrix (`SchemaLifecycleExecutorPostgresProofMatrixTest`) is `@Tag("integration")` and only
runs under `integrationTest`, which itself requires a generated-app mount — exercised instead via
the **live rehearsal**: real generated app, real DB with rows, additive change, row preserved. This
project's own standard for this component, and unit tests have historically not been sufficient for
it.

---

### 2.B.5 `KernelRunner` — 4,423 lines, 84 statics · **1 week**

**Do last.** Not because it is riskiest mechanically — because it holds the durable-resume and
compensation invariants, and those deserve reading rather than moving.

**Structure note:** all 6 nested types sit at **line 4340+**, i.e. the file is ~4,300 lines of methods
followed by its types. The seam is therefore behavioral, not positional.

```
4340  WaitCriteria · StepProgressRecorder · StepExecutionOutcome
4376  CircuitGate · EffectiveCapabilityPolicy · InvariantViolationException
```

**Split by step kind**, mirroring `FlowStepDefinition.Type`:

```
flow/steps/AwaitEventStep · BranchStep · CapabilityCallStep · EmitEventStep
           ForEachStep · InvariantCheckStep · MapStep · ReturnStep · ScheduleEventStep
flow/CompensationRunner        ← LNCH-17 semantics, its own file
flow/ResumeCoordinator         ← WAITING_EVENT rehydration + correlation ownership
flow/FlowStateCodec            ← the _npdev.await state keys (AWAIT_STATE_KEY et al.)
KernelRunner                   ← dispatcher
```

**The payoff beyond tidiness:** `FlowEngine`'s implementation becomes findable. T1.16 documented
"implementation: `KernelRunner`, planned split 2.B.5" — this delivers it, and the platform's hardest
capability finally *looks* like what it is to anyone browsing the source. **This is CORE item C-4.**

**Acceptance.** `:kernel:test` = **159 tests, 0 failures**, identical. Plus a durable-resume rehearsal:
flow parks on `awaitEvent`, process restarts, flow resumes and completes. If **2.F** (the demo app)
exists by then, it *is* this acceptance test.

---

## 3.3 2.B — risk register

| Risk | Mitigation |
|---|---|
| A "pure move" silently changes behavior via static-init order or overload resolution | Identical test counts + byte-identical generated output for emitters |
| Reviewer fatigue over a 3,000-line diff | One file per branch; pure-move commits; no mixed fixes |
| A bug surfaces mid-split and gets fixed inline | Rule 3. File it, finish the move, fix separately |
| `GeneratedCrudRuntimeSupport` breaks generated apps but not unit tests | Run a real generated app's suite (`rebuild-app` skill), not just module tests |
| `SchemaLifecycleExecutor` regression not caught by unit tests | Live rehearsal with real rows — the project's own standard for this component |

---

# Part 4 — Sequencing

```
Week 0   R1 T1.6 merge + beta1.2  🔴 ─────────────────────────► 🚀 3.1 PUBLISH
         R2 finish T1.15 · re-baseline complexity gate
         R3 two cosmetic fixes
            ║
Week 1   2.A DSL 2.0   ⚠️ TIME-BOXED — must precede production users
         ├ 2.A.0 GATE: resolve createConcept/updateConcept       [2 hr]
         ├ 2.A.1 choose canonical names (align to runtime enum)  [decision]
         ├ 2.A.2 schema across all 4 copies + mirror gate        [1 day]
         ├ 2.A.3 npdev migrate --dsl-2 + byte-identical proof    [2 days]
         ├ 2.A.4 collapse parser switch, flip corpus, rebuild AI [1 day]
         └ 2.A.5 adopt the standing codemod rule                 [⚡]
            ║
Week 2   2.B.2 TrustedSourceEmitter                              [1 day]
         2.B.3 GeneratedCrudRuntimeSupport                       [2 days]
            ║
Week 3   2.B.4 SchemaLifecycleExecutor + ColumnFacts  ★          [3 days]
            ║                    └──► unblocks 3.3 (REG-40)
Week 4   2.B.5 KernelRunner → step classes  [CORE C-4]           [1 week]
                              └──► unblocks 3.7 (aggregate tx boundary)
```

**Why 2.A before 2.B.** 2.A is time-critical (its cost multiplies 50–100× the moment a user has an
app in production); 2.B is not. 2.B also touches `JsonModelParser`'s neighbours, so doing 2.A first
means the parser switch is already at its final size when the decomposition reaches it.

**Why R1 before both.** Publishing is decided (C1 verdict (a)), and a public repo whose default branch
is 150 commits stale is the most falsifiable claim the project could ship.

---

# Definition of done

**Part 1**
- [ ] `main` = branch head; `beta1.2` tagged; both workflows green **on GitHub** -- blocked on
      `gh auth login` (branch is pushed; nothing else pending)
- [x] T1.15 committed as a pure move; DSL suite exactly 357/0 at the time (356 after 2.A.4 test
      deletions/additions moved the count again, expected -- see 2.A.4's own commit)
- [x] `run-editor-complexity-check.ps1` green -- both pre-existing violations fixed with a real
      component split, not allowlisted (`FieldFileConstraintsEditor.tsx`,
      `ReferencePickerDisplayTemplateFields.tsx`)
- [x] `ui-boundary.json` `files` = 83 (81 + the AuthoringPlaceholder fix + 2 new split components);
      `FLOWS.md`'s note corrected to past tense

**Part 2 — DSL 2.0**
- [x] `createConcept`/`updateConcept` resolved (a) sugar and documented in `FLOWS.md` §3 -- plus a
      gap this table itself had (`generatedAction` missing) found and fixed
- [x] `flowStep.type` reduced 23 → **12** (9 real kinds + 3 sugar: `createConcept`/`updateConcept`/
      `generatedAction`), canonical names matching the runtime enum
- [x] Field-alias pairs retired (`cap`/`op`/`out`/`at`/`target`/`targetConcept`/`capabilityName`/
      `eventName`/`fieldMap`, plus the corrected `awaitRef`/`as` pair); `action|actions` normalized
      to always-a-list
- [x] All four schema copies semantically identical, enforced by a gate
      (`check-schema-mirror-consistency.py`, calibrated, wired into the AI-knowledge gate as step 9/9)
- [x] `npdev migrate dsl-2` built and used for real, not just proved on 27 files -- migrated every
      git-tracked model in the repo. Byte-identical-compiled-model proof reframed as "full test
      suites green after the corpus is actually migrated" (the 27-file synthetic proof loop was
      superseded by doing the real thing); `AppGen/apps` deliberately deferred (owner's call, §0.2-
      adjacent: non-git external directory)
- [x] Parser switch is 1:1; schema validation (which always runs first) is the actual refuse-point,
      with a diagnostic naming the canonical replacement
- [x] `DSL_REFERENCE.md` regenerated, `FLOWS.md` updated (worked examples + two shifted line-range
      citations fixed). `AI_MODEL_TO_DSL_MAPPING.md` checked -- no flowStep-alias content, no change
      needed. AI corpora rebuilt (`python scripts/ai/build_knowledge.py`, clean run)
- [x] `BREAKING.md` entry landed in the same commit as the narrowing + parser collapse

**Corrections made to this plan while implementing it** (see the commits' own messages for full
detail): 2.A.0's premise was already resolved by the time it was checked (verified directly against
`ModelCompiler.java`, not re-derived); the canonical-names table was missing `generatedAction`; the
`awaitEvent`/`awaitRef` field-alias row was mislabeled (real pair is `awaitRef`/`as`); 2.A.2/2.A.3's
step order as originally written would have broken schema validation on the un-migrated corpus mid-
proof, corrected to widen-then-narrow; the real migration surface was **much smaller** than the
195-file/430-hit estimate once checked structurally (many "hits" were unrelated schema contexts --
`orchestrationAction.type`'s own enum, panel-gadget `action` types, generator config `target` keys)
-- but a genuinely new, undiscovered fixture corpus (`NPDevGenerator/test-models/`) and
`orchestrationRule`'s scalar `action` field turned up during the actual narrowing that no earlier
sweep had found, confirming RED-first verification (narrow it and see what breaks) beats any amount
of grep-based pre-checking alone.

**Part 3 — Decomposition**
- [x] 2.B.2 `TrustedSourceEmitter` (3,782 → 212 lines, 12 files, all ≤ ~800): emitter output
      byte-identical, `:generator:test`+`:generator:behaviorTest` 198/0 (commit `624130c`)
- [x] 2.B.3 `GeneratedCrudRuntimeSupport` (5,125 → 3,651 lines, 24 new files): `:adapters:
      expression-cel:test` 62/0 identical; a real generated app (`canonical-demo`) exercised over
      REST (CRUD + 409 duplicate-key + 422 missing-fields), all passing (commit `8eeca4e`). Main
      file stays well above 800 lines by design, not oversight — 12 constructor-injected instance
      fields make most of its ~136 methods instance-bound, and `InvariantViolationDetail`/
      `InvariantViolationException`/`FileHandleRef`/`persistenceCapability`/
      `mapDataIntegrityViolation` are referenced by qualified name from mustache templates baked
      into every generated app, so moving them would break generated code — a separate, explicitly
      higher-risk change, not a pure move
- [x] 2.B.4 `SchemaLifecycleExecutor` (3,739 → 2,120 lines, 10 new files, commits `3d22e86`/
      `66d7745`): `com.finalexec.db.*` 266/0 identical to baseline (independently reconfirmed, not
      just agent-reported); full `NPDevRuntimeHost` suite 403/0. **Live rehearsal performed**
      (this section's revised acceptance bar, since the redesign premise turned out stale — see the
      correction above): `AppGen/apps/simple-product-h2local`, additive field added to `Product`,
      rebuilt and rebooted against its EXISTING H2Local DB file with a pre-existing row — new
      column present, existing row's data intact, boot healthy. Main file stays above 800 lines by
      the same kind of judgment call as 2.B.3: `beforeMigrateDecision` (~450 lines, the sequencer
      itself) and the live-JDBC-introspection/classification cluster are package-private and
      already shared with `SchemaDeltaReport`/`DesiredSchemaFactory`/`ImpactReport` in the same
      package — extracting them churns call sites without reducing risk. Two incidental findings
      filed, not fixed: REG-54 (dead code, `worse`/`hasTypeChange`, orphaned by the unrelated SER-
      P4.8 migration), REG-55 (a capability-dispatch ambiguity surfaced by the rehearsal, on an
      H2Local app resolving a Postgres adapter — needs its own RED-first investigation)
- [ ] 2.B.5 `KernelRunner` split verified by a durable-resume rehearsal — not started
- [x] Every commit so far a pure move; every bug found filed separately (REG-54/REG-55 above)

---

*Companions: `docs/EXECUTION_TREES.md` (the map, incl. §0.1 CORE track) ·
`docs/TREE1_LAUNCH_UNBLOCK_PLAN.md` (TREE 1 detail) · `docs/FLOWS.md` (the core) ·
`docs/NPDEV_OPEN_ITEMS_REGISTER.md` (ledger) · `docs/DECISION_BRIEFS_2026-07.md` (C1 verdict).*
