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

## Reporting bugs / requesting features

Open a GitHub issue. If it's a **security** issue, don't — see `SECURITY.md` instead.

For a bug in generated app behavior, the most useful report includes:
- The app's `model.json` (or the minimal slice that reproduces it)
- The command you ran and its full output
- Which gate or test failed, if any

## Security

See `SECURITY.md` for the vulnerability disclosure process. Please don't open public issues for
security problems.
