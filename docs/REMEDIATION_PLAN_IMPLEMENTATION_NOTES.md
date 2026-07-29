# Remediation Plan — implementation notes

> **STATUS: HISTORICAL.** Written 2026-07-29 on completing `docs/REMEDIATION_PLAN.md`. Records what
> the implementation actually did, where it diverged from the plan's own estimates, and every new
> finding uncovered along the way (this repo's own culture: measure, don't assume — several of these
> were found only because implementing the plan exercised code paths nothing had exercised before).

## Part 1 — Drift and gate integrity

- **R-D1.** `docs/EXECUTION_TREES.md` 3.8 reworded from a stale blocker to `✅ UNBLOCKED`, then later
  `✅ DONE` once R-P4 shipped.
- **R-D2.** Rule T3 added to `scripts/quality/check-register-consistency.py`, calibrated RED→GREEN.
  Building it against the real register surfaced two real parsing gaps in the pre-existing script,
  fixed in place: `classify()`'s `CLOSED_WORDS` didn't include "FIXED" (REG-33's own Status line),
  and the orphan-detection didn't fall back to a *sectioned-but-unparseable* register entry (e.g.
  REG-16-resid's Status line reads "Round 2 of N COMPLETE" — "COMPLETE" is deliberately not a
  keyword, so this was stale prose in the register, not a ledger bug). Rule T3 was later **retired**
  once R-P1 completed, per its own documented retirement condition.
- **R-D3.** `NPDevRuntimeHost/.../compiled-metadata.json` was regenerated from the canonical-demo
  model via a one-off `CompiledMetadataCanonicalJson` test tool (deleted after use); a new
  completeness assertion in `RuntimeMetadataServiceTest` cross-checks it against the split manifest
  index. Confirmed RED against the pre-fix fixture (missing `invocations`) before committing the fix.
- **R-G3/R-G4.** `CODE_OF_CONDUCT.md` added (Contributor Covenant v2.1). `CONTRIBUTING.md` gained a
  "When a new app lands" section naming the classifier command and the ≥2-apps/≥2-screens trigger.

## Part 2 — The impact gate

- **R-G1.** `_ops/Check-Provenance.ps1` added to every app's ops toolbox (`Build-NpdevApp.ps1`);
  wired into `Rebuild-And-Restage.ps1` as a default-on step 4 (build → start → check, refuse on
  failure); the static half (`check-panel-provenance-schema.py`) wired into
  `run-ai-knowledge-gate.ps1` as check 10/10. **Proven live on WmsOffice**, both directions: the
  positive case (0 problems against the real, unmodified bundle) and the negative case (a captured
  real bundle with `CrossDocking.dataAtivacao` renamed produced exit 1 naming the exact screen).
- **R-G2.** 13/13 WmsOffice hand-written screens confirmed (10 bootstrapped-then-hand-corrected this
  session, 3 pre-existing). The bootstrapper's drafts needed real correction in every case: it
  systematically undercounts writes for the `apiSend(method, path, body)` positional-argument
  authoring style this app uses throughout (neither of its two mutating-detection heuristics ever
  fires for that shape), and several drafts carried a recurring `identity::Role.name` /
  `workspace::Menu.label` false-positive from `shell.js` chrome. AuxScreen and Pigmentampa (1 screen
  each) are **blocked, not skipped**: both apps' `model.json` use a pre-DSL-stabilization flow-step
  shape the current schema rejects outright, so neither can even be regenerated with the current
  toolchain — filed as **REG-63**, a genuine new finding, not assumed.
- **Schema bug found and fixed along the way:** `schemas/panel-provenance.schema.json`'s `reads`/
  `writes` regex never allowed a `<namespace>::` prefix, so it had never validated a pack-namespaced
  field (`identity::User.active`) until this session's `usuarios-roles.panel.json` became the first
  manifest ever to reference one. Fixed in the schema and in the new static checker's mirrored regex.

## Part 3 — Small gaps

- **R-R1.** Extracted the hand-maintained `REAL_ROUTE_PATTERNS` list into a shared production class
  (`RealRoutePatterns`, DSL module) so it is never hand-maintained twice. Added
  `RoutePatternStalenessTest` (generator module): generates a real sample app, reads the static
  "template tree" controllers directly (they're not model-specific, so no generation needed), and
  asserts every pattern matches a real extracted route. Calibrated RED (a deliberately bogus pattern
  correctly failed) and GREEN.
- **R-P3.** Recommendation (b) taken: `persistence-postgres` and `idempotency-postgres` promoted to
  `npdev-pr-gate.yml` (the two `*-postgres` adapters with a real, H2-invisible finding on record —
  REG-36, REG-50). Measured before promoting: neither task had run in **any** workflow at all before
  this (not just nightly-only) — a real, previously-invisible coverage gap, not just a cost
  tradeoff. ~34s locally for both suites together. Decision recorded in `docs/ACCEPTED_BOUNDARIES.md`
  B21.

## Part 4 — The blocked tier

- **R-P1.** All 64 tracked ids (REG-1 through REG-63, plus REG-16-resid) migrated to
  `ledger/items/*.yml`. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` is archived-in-place (kept for its
  `#reg-N` anchors and prose narrative, no longer hand-edited for status). Rule T1 now reads the
  ledger directly instead of parsing the register; Rule T3 is retired. A second latent bug found
  while migrating: `generate_open_items.py`'s required-field validator treated `severity: null` as
  "missing" even for `type: BOUNDARY`, where the schema explicitly allows it — never triggered before
  because no `BOUNDARY`-typed item (REG-7, REG-8) had ever been migrated until this pass.
- **R-P2.** Measured the real "gate-hardwired" set directly rather than trusting the plan's own "13"
  estimate (itself hedged as "and the rest"): 8 documents in `docs/` root already self-declared
  `HISTORICAL`/`EXECUTED` but never moved. All 8 archived to `docs/archive/programme-history/`; the 3
  with functional `LEDGER_EXCLUSIONS` entries had those entries removed (dead once archived, since
  the coverage sweep only scans `docs/*.md` non-recursively). `REMAINDER_CLOSURE_PLAN.md` was still
  banner-marked `ACTIVE`; cross-checked its 4 phases against the now-complete ledger before
  re-banering it `HISTORICAL` and archiving it too.
- **R-P4.** `npdev generate screen` added to `NPDevCli/npdev_cli.py`. Deliberately vendor-neutral —
  no LLM API is called directly from platform code (matching `docs/ai/UI_GENERATION_PROMPT.md`'s own
  "your model id" framing and the absence of any hardcoded AI-vendor call anywhere else in this
  repo's shipped code); `--model-command` is a pluggable, shlex-parsed local command, with a
  two-step `--from-response` fallback. Always fetches a fresh bundle and refuses to write anything
  whose manifest fails structural + impact-gate validation against it. **Proven live on WmsOffice**:
  generated a real `Produtos` (product catalog) screen. The first attempt was genuinely REFUSED
  (the draft manifest read `PerfilAlocacao.nome`, a different concept's field the concept-scoped
  bundle correctly doesn't expose) — not a staged demo, the tool's own gate caught a real authoring
  mistake. The corrected manifest then wrote both files and passed; the screen's exact CRUD calls
  (create/read/update/delete) were verified against the real running app. A real bug was found and
  fixed in the same pass: the prompt-only fallback path didn't create its output directory first.

## Part 5 — Owner-only

- **R-O2.** Not executable by an agent — "three real conversations" means three actual people.
  Prepared what doesn't require the owner's own voice: `docs/PITCH.md` already existed and already
  covers the SDD framing, the three differentiators, and the honest UI limitation — nothing to add.
  `docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` was sitting in `docs/archive/programme-history/` (a
  reusable template, not a historical record — restored to `docs/`). No GitHub repo metadata
  (description/topics) was touched — editing public-facing project representation is the owner's
  call, not an agent default action.
