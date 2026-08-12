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
```

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

- `scripts/quality/generate_open_items.py` — renders OPEN_ITEMS.md.
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
