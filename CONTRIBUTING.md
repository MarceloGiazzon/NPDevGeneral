# Contributing to NPDev

Thanks for looking at this project. NPDev is pre-1.0 and deliberately unstable (see `README.md`'s
"Stability policy" and `BREAKING.md`) — expect the DSL, generated code layout, and internal APIs to
keep moving. That said, the conventions below are stable and enforced by CI, not suggestions.

## Before you start

- Read `CLAUDE.md` — it's the repo guide (module map, where build output goes, large files to avoid
  reading in full, environment notes). It applies to human contributors as much as to an AI agent.
- Check `docs/NPDEV_OPEN_ITEMS_REGISTER.md` for known open items before filing a new one — it's a
  machine-parsed contract (see its own header), not just a changelog.

## Build output and evidence never go in the repo

- Generated/build artifacts go to `D:\WorkSpace\NPDev\Build` (or `$NPDEV_BUILD_ROOT` if you've set
  it) — never inside this repo. See `docs/BUILD_OUTPUT_LOCATION_POLICY.md`.
- Scratch files, screenshots, and verification evidence go outside the repo too.
- A pre-commit hook (`scripts/hooks/pre-commit.ps1` → `Test-WorkspaceSlimness.ps1`) enforces this;
  if it blocks your commit, run `pwsh -File scripts/hygiene/clean-workspace-state.ps1` first.

## Git hygiene

- Stage files by path. Don't use `git add -A` / `git add .` — it's easy to pull in something that
  shouldn't be committed.
- Keep commits focused: a refactor and a bug fix in the same commit make both harder to review and
  to revert independently.

## Breaking changes ship their migration in the same commit

Every breaking change to the model DSL, generated code layout, or internal APIs gets a one-line
entry in `BREAKING.md`, **in the same commit**, alongside the `npdev migrate` codemod that rewrites
existing models automatically. Never land the break first and the codemod later — see `BREAKING.md`
itself for the standing rule and examples.

## When you add a DSL feature, fixture it in the same commit

`NPDevSamples/dsl-conformance-max` exists because a 2026-07-29 corpus-wide measurement found the
DSL corpus exercised roughly 60% of `model.schema.json` — seven schema sections and flow-step kinds
(including `generatedAction`, which turned out to be entirely unreachable — REG-65) had **zero**
models using them, so a schema/parser/compiler change that broke any of them would have passed the
corpus-parse gate clean. See its own `README.md` for what it covers and why.

**Rule:** a new `flowStep.type`, top-level schema section, or similar DSL surface gets a minimal,
real example added to `dsl-conformance-max` in the same commit that ships it — not a promise to
follow up later. That is what turns "the corpus covers most of the schema" from something someone
has to go measure into something the gate tells you (`scripts/quality/validate-corpus.py`, run on
every PR via `ai-knowledge-gate.yml`).

## A plan may not close with an unresolved deferral that has no tracking id

`docs/INVOCATION_TOPOLOGY_PLAN.md` T4: `docs/DSL2_AND_DECOMPOSITION_PLAN.md` closed with its own
Definition of Done recording "`AppGen/apps` deferred as a non-git external directory — owner's
call", with no ledger item attached. The plan closed and the deferral closed with it — 17 corpus
models stayed broken for ~3 weeks (REG-63) because nothing tracked that the migration tool's proof
had a real, unstated gap.

Deferring scope is fine and often the right call. Deferring **without a tracking id** is what
failed. If a plan marked `EXECUTED`/`DONE`/`CLOSED` in its `STATUS:` banner says `deferred` / `out
of scope` / `not covered` / `left for later`, cite either a `REG-nn` (`docs/OPEN_ITEMS.md`, a real
gap) or a `B-nn` (`docs/ACCEPTED_BOUNDARIES.md`, a permanent deliberate boundary — don't file a REG
for something that isn't a gap or bug) in the same paragraph. `check-register-consistency.py`
enforces this; a reviewed false positive (prose narrating a pre-existing, already-tracked claim
rather than a new scope cut) goes in `scripts/quality/plan-deferral-citation-allowlist.json`, not a
silent rewrite of the check.

## A test that hand-builds `CompiledModel` proves the compiled contract, not the authoring path

A test that constructs `CompiledModel` / `CompiledFlow` objects directly (bypassing
`JsonModelParser`/`SemanticValidator` entirely) proves the emitter/runtime does the right thing
**given that compiled shape**. It does **not** prove a real `model.json` can produce that shape.

This is not hypothetical: `TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest` built and
booted a real packaged app with a `generatedAction`-shaped compiled step and passed for the entire
time no model could actually express one (`FlowValidation` rejected the schema's own canonical enum
value as "unsupported" — REG-65, `docs/FINAL_OPEN_ITEMS_PLAN.md` F4). A green runtime proof coexisted
with a broken authoring path, indefinitely, because nothing joined the two ends.

**Any feature reachable from `model.json` needs a corpus fixture too** — see
`NPDevSamples/dsl-conformance-max` and `scripts/quality/check-dsl-coverage.py` (proves every DSL
feature parses) plus `scripts/quality/check-dsl-conformance-generates.py` (proves it also
generates — `docs/CLOSEOUT_PLAN.md` G2/G3). Do **not** rewrite a hand-built compiled-object test to
go through the full authoring path instead — that would make the suite far slower for little gain.
The fix is to make sure the authoring path is *also* exercised somewhere, not to convert every
compiled-contract test into an end-to-end one.

## When a new app lands

Re-run the screen classifier and refresh `docs/SCREEN_TAXONOMY.md`'s per-screen table (F1/F6,
`docs/FRONTEND_STRATEGY_PLAN.md`):

```
python <scratchpad>/helpers/classify-screens.py --apps-root D:/WorkSpace/NPDev/AppGen/apps --format md
```

**Trigger:** a hand-written screen *class* only earns a new NPDev primitive once it reaches
`SCREEN_TAXONOMY.md`'s own promotion rule — **≥ 2 apps with ≥ 2 screens each**. That threshold can
only be crossed by a new app or a new hand-written screen in an existing one, so this is the moment
that matters, not a periodic calendar check. F6 was gated "nothing recurs yet" as of 2026-07-28
(zero classes cleared the rule); skipping this re-check on the next app is how a recurring class
would go unnoticed indefinitely — see `docs/REMEDIATION_PLAN.md` R-G4.

## Gates that must pass

These aren't optional CI noise — they catch real drift:

```powershell
python scripts/quality/check-register-consistency.py
pwsh -NoProfile -File scripts/quality/run-ai-knowledge-gate.ps1
```

Module-specific gates (`run-generator-gate.ps1`, `run-runtimehost-gate.ps1`, `run-frontend-gate.ps1`)
live in `scripts/quality/` — run the one for whatever you touched. The PR gate on GitHub runs the
core subset automatically; the full set is documented in `CLAUDE.md`.

## A new script under `scripts/` declares what it is and what invokes it

`docs/INVOCATION_TOPOLOGY_PLAN.md` T2: every check exists to catch a bug, but a check nobody
invokes is a check that exists only on paper — four separate real findings in this repo were
exactly that shape. `scripts/quality/run-script-inventory-check.ps1` (step `[17/17]` of
`run-ai-knowledge-gate.ps1`) fails if a script under `scripts/` has no entry in either policy file:

- **Classification** (`scripts/policy/script-inventory-policy.json`, pattern-based): `canonical` /
  `helper` / `deprecated` / `one-time-repair` / `outside-repo-only`. Usually inferred automatically
  from the script's directory — add a `classificationRules` pattern if a new top-level `scripts/`
  subdirectory needs one.
- **Invocation** (`scripts/policy/script-invocation-declarations.json`, one entry per script path):
  what actually invokes it, and the gate checks the declaration against reality (basename/stem
  presence — cheap and static, same limitation `check-test-task-coverage.py`'s own docstring
  accepts).
  - `ci-gate` — must be named in a `.github/workflows/*.yml`, or in a `scripts/quality/run-*.ps1`
    gate runner that is itself named in a workflow.
  - `manual-runbook` — must be referenced by at least one `*.md` doc somewhere in the repo. If you
    add a manual tool, document it (a runbook, `CLAUDE.md`, whatever's the natural home) in the same
    commit — an undocumented "manual" tool is indistinguishable from an abandoned one.
  - `orchestrated` — must be referenced by another script (a helper module, a dot-sourced/imported
    file).
  - `retired` — must declare a non-empty `reason` and `date`.

## Reporting bugs / requesting features

Open a GitHub issue. If it's a **security** issue, don't — see `SECURITY.md` instead.

For a bug in generated app behavior, the most useful report includes:
- The app's `model.json` (or the minimal slice that reproduces it)
- The command you ran and its full output
- Which gate or test failed, if any

## Security

See `SECURITY.md` for the vulnerability disclosure process. Please don't open public issues for
security problems.
