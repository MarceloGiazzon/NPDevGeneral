# Ledger — structured open-items tracking

**Status: MIGRATION COMPLETE, 2026-07-29 (docs/REMEDIATION_PLAN.md R-P1).** This directory is the
single source of truth for NPDev's tracked bugs/gaps/boundaries. `docs/NPDEV_OPEN_ITEMS_REGISTER.md`
was archived-in-place, then moved out of the repo entirely by md-zero-2026-08-11 PLAN.md Phase 2
(git history keeps it; `__OutsideRepo/md-zero-2026-08-11/archived-programme-docs/` keeps a working
copy) once every item's `detail:` field below was confirmed self-sufficient on its own. All 64
tracked ids (REG-1 through REG-63, plus REG-16-resid) are migrated. To open a new item, add a
`ledger/items/<ID>.yml` file directly.

## Why

The prose register represented status two ways at once — `~~strikethrough~~` on the ID, and a
freeform bolded verdict sentence in the detail cell — and nothing prevented them from disagreeing.
REG-59 did exactly that (struck, but its own detail text still argued the underlying gap was open),
caught only by a purpose-built regex rule (`check-register-consistency.py`'s since-retired Rule T2
sibling, "Rule T3"). A single structured `status` field makes that class of contradiction impossible
by construction: there is only one place status lives.

## Schema

One YAML file per item, `ledger/items/<ID>.yml`:

```yaml
id: REG-61                      # required, matches the filename
title: One-line summary          # required
type: GAP | BUG | PROCESS | BOUNDARY   # required
severity: LOW | MEDIUM | HIGH | P0 | P1 | null   # null only for type: BOUNDARY
status: OPEN | PARTIAL | DONE    # required -- the single source of truth
opened: 2026-07-28               # required, ISO date
closed: 2026-07-28               # required iff status: DONE
verification: NOT_VERIFIED | UNIT_TESTED | VERIFIED_LIVE   # optional
source: where/how this was found         # required
surface: component tag, e.g. runtimehost/schema-lifecycle  # required
files: [path/one.java, path/two.java]    # optional
detail: |                        # required -- concise root-cause/fix/verification summary
  Multi-line prose.
guard:                           # optional -- present iff this item's DONE claim is falsifiable
  kind: test | script | manual   # what kind of proof backs the claim
  ref: path/to/Test.java#method or scripts/proofs/whatever.ps1   # where the proof lives
  asserts: one-line statement of what the proof actually checks
  provenRed: true                # whether a RED reproduction was captured before the fix
```

`guard` (MASTER-ROADMAP.md F6 / `R11`) exists so a `status: DONE` claim is falsifiable — it names the
test or script that proves it, not just a sentence asserting it. Filed OPEN items may leave it absent
(there is no proof yet); closing an item without one is a claim, not a proof. `R11`'s full card (ledger
restructuring, derived gate numbers) is separate, larger, and not implied by this field's presence.

**Enforced, not just documented, as of `check-done-item-guards.py` (R11's core mechanism, landed
2026-08-14).** The field existed from 992d47a8 onward but nothing checked it — a `guard` could be
absent from a DONE item, or present and pointing at nothing, and neither was ever caught. Two rules
now run on every gate pass (`scripts/quality/check-done-item-guards.py`, wired into
`run-ai-knowledge-gate.ps1` step [36/35]):

1. **Coverage, ratcheted.** Every `status: DONE` item must carry a `guard:` block, unless its id is
   in `scripts/policy/done-item-guard-policy.json`'s `legacy.ids` — the 177 DONE-without-guard items
   that predated the checker, frozen 2026-08-14. That list is shrink-only, exactly
   `scripts/policy/doc-inventory-policy.json`'s pre-ban legacy set: a **new** DONE item (filed or
   re-closed from that commit forward) cannot be grandfathered into it, so it must carry a real guard
   to close. An id leaves the legacy list (with `frozenCount` lowered in the same commit) once its
   item genuinely gains a guard, changes status away from `DONE`, or is deleted — leaving a
   no-longer-needed entry in place is itself a checker failure.
2. **Resolution.** Every `guard:` block present on *any* item, regardless of status, must actually
   resolve, checked mechanically:
   - `kind: test` — the file at `ref` (before any `#method` or trailing `(...)` note) must exist, and
     if a `#method` is given, that identifier must appear in the file.
   - `kind: script` — at least one concrete anchor (a repo-rooted `.py`/`.ps1`/`.sh` path, a
     fully-qualified Java test class, or `gradlew`) must be found in `ref`, and every anchor found
     must exist.
   - `kind: manual` — best-effort only: any *repo-rooted* path mentioned (starting with `scripts/`,
     `NPDevContract/`, `docs/`, etc.) must exist. Most manual guards name no file at all (a live-app
     HTTP interaction, a `gh api` call) and that is fine — see the checker's own module docstring
     ("HONEST LIMIT") for exactly what is and isn't checked, and why a bare filename or an app-relative
     path like `` `_ops\Run-FinalApp.ps1` `` (emitted per-app at generation time, never tracked in this
     repo) is deliberately not chased.

Three real guards were backfilled onto pre-existing DONE items in the same commit that added the
checker, to prove the ratchet resolves something real rather than only asserting it: `REG-142` (a
`script` guard pointing at `check-template-resource-shadowing.py`, itself already wired into the gate
as the regression control for that exact bug), `REG-108` (a `test` guard naming
`ModelSourceResolverTest#packContributedRolesPropertyScopesAndPropertiesAreMergedNotDropped`), and
`STOR-2` (a `script` guard pointing at `check-rollback-claims.py`, described in
`run-ai-knowledge-gate.ps1`'s own step comment as "the mechanical half of STOR-2").

`legacyDetailRef` (optional) no longer appears on any current item: it used to point into
`docs/NPDEV_OPEN_ITEMS_REGISTER.md`'s `#reg-N` anchors for the original, often multi-thousand-word
investigation narrative, and md-zero-2026-08-11 PLAN.md Phase 2 removed it from all 64 items in the
same commit that moved that doc out of the repo -- each item's `detail:` field was confirmed
self-sufficient first. `generate_open_items.py` still renders the field if a future item sets it
(e.g. pointing at something else entirely), but nothing currently does.

## Generating OPEN_ITEMS.md

```
python scripts/quality/generate_open_items.py           # writes <Build>/docs/OPEN_ITEMS.md
```

Renders OPEN_ITEMS.md from every `ledger/items/*.yml`, validating each file's schema first
(required fields, enum values, `status: DONE` requires `closed`). Never hand-edit the generated
file. `manual-runbook` (not wired into any gate -- confirmed by grep before this note was written;
the previous version of this line claimed otherwise). md-zero-2026-08-11 PLAN.md Phase 6: the
rendered doc is no longer committed to the repo at all (build output, per
`docs/BUILD_OUTPUT_LOCATION_POLICY.md`) -- there is nothing left to `--check` against, so that flag
is gone.

**Dependency note:** this script needs PyYAML (`import yaml`), same as the other ledger/gaps.yml
readers (`scripts/docs/generate_gaps_roadmap.py`, `scripts/ai/extract_platform_status.py`) and
`scripts/quality/check-workflow-yaml-syntax.py`; everything else under `scripts/` is stdlib-only.
Declared in `scripts/requirements.txt`; `.github/workflows/ai-knowledge-gate.yml` installs it
(`pip install -r scripts/requirements.txt`) before running any of them.

## What consumes the ledger now

- `scripts/quality/generate_open_items.py` — renders OPEN_ITEMS.md, and is reused by
  `check-done-item-guards.py` for schema-validated item loading (`from generate_open_items import
  load_items`) rather than re-implementing the same validation twice.
- `scripts/quality/check-done-item-guards.py` — enforces the `guard:` coverage ratchet and resolution
  rules described above, against `scripts/policy/done-item-guard-policy.json`.
- `scripts/external-review/build-review-pack.py`'s `FORBIDDEN_PATH_PATTERNS` names `OPEN_ITEMS\.md$`
  (excluding it from any external-AI review pack, since it carries the platform's own conclusions
  about itself) and `NPDEV_OPEN_ITEMS_REGISTER\.md$` (the archived register that pattern was
  originally written for, moved out of the repo in Phase 2 of the same plan -- that half is now
  moot, harmless, not cleaned up here). Both patterns scan the working tree a review pack is built
  from; OPEN_ITEMS.md not being tracked in git any more (Phase 6) makes its own pattern moot too
  the moment nobody generates a local copy before building a pack.

## History

Prototyped 2026-07-28 against a 9-item subset (REG-54–REG-62, that session's own work) to prove the
schema, the generator, and gate-compatibility before committing to a full cutover. Full migration —
the remaining ~54 ids, the register-archival banner, Rule T1's YAML repoint, and Rule T3's
retirement — landed 2026-07-29 (docs/REMEDIATION_PLAN.md R-P1). `check-register-consistency.py`
(the checker that ran Rule T1 against `docs/EXECUTION_TREES.md`, and the original summary-vs-detail
rule against `OPEN_GAPS_AND_ROADMAP.md`'s 19 items and `LAUNCH_READINESS_GAPS.md`'s 24) was itself
deleted by md-zero-2026-08-11 PLAN.md Phase 2, along with `EXECUTION_TREES.md`,
`NPDEV_OPEN_ITEMS_REGISTER.md` and `LAUNCH_READINESS_GAPS.md` -- all 177 items across every ledger
family it guarded were DONE. `OPEN_GAPS_AND_ROADMAP.md` itself is unaffected: it had already become
a generated projection of `ledger/gaps.yml` in this same branch's Phase 1, before Phase 2 removed
the checker that used to cross-check it by parsing its prose.
