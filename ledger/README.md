# Ledger — structured open-items tracking

**Status: MIGRATION COMPLETE, 2026-07-29 (docs/REMEDIATION_PLAN.md R-P1).** This directory is the
single source of truth for NPDev's tracked bugs/gaps/boundaries. `docs/NPDEV_OPEN_ITEMS_REGISTER.md`
is now **archived-in-place**: it stays on disk (its `#reg-N` anchors are linked from every
`legacyDetailRef` below, and its prose investigation narrative is genuinely valuable history) but is
no longer hand-edited for status. All 64 tracked ids (REG-1 through REG-63, plus REG-16-resid) are
migrated. To open a new item, add a `ledger/items/<ID>.yml` file directly.

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
legacyDetailRef: docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-61   # the archived register's full narrative
```

`legacyDetailRef` is permanent, not a migration-era scaffold: the full, often multi-thousand-word
investigation narrative for every entry stays in the archived prose register rather than being
bulk-copied into `detail` (error-prone, and not what this migration was solving — see "Why" above).
A genuinely new entry (no register history to point at) may omit this field.

## Generating `docs/OPEN_ITEMS.md`

```
python scripts/quality/generate_open_items.py           # regenerate
python scripts/quality/generate_open_items.py --check    # exit 1 if stale (CI form)
```

Regenerates `docs/OPEN_ITEMS.md` from every `ledger/items/*.yml`, validating each file's schema
first (required fields, enum values, `status: DONE` requires `closed`). Never hand-edit that file.
Wired into `run-ai-knowledge-gate.ps1` (`--check` form), blocking.

**Dependency note:** this script needs PyYAML (`import yaml`), same as
`scripts/quality/check-register-consistency.py`'s `load_ledger_items` (feeds Rule T1) — together the
only two scripts under `scripts/` that do; everything else here is stdlib-only. Declared in
`scripts/requirements.txt`; `.github/workflows/ai-knowledge-gate.yml` installs it
(`pip install -r scripts/requirements.txt`) before running either script.

## What consumes the ledger now

- `scripts/quality/generate_open_items.py` — renders `docs/OPEN_ITEMS.md`.
- `scripts/quality/check-register-consistency.py`'s Rule T1 — cross-checks `docs/EXECUTION_TREES.md`
  mentions of an id against that id's `status` here (reads the YAML directly, not the archived
  register).
- `scripts/external-review/build-review-pack.py` — excludes `docs/OPEN_ITEMS.md` (like the archived
  register before it) from any external-AI review pack, since it carries the platform's own
  conclusions about itself.

## History

Prototyped 2026-07-28 against a 9-item subset (REG-54–REG-62, that session's own work) to prove the
schema, the generator, and gate-compatibility before committing to a full cutover. Full migration —
the remaining ~54 ids, the register-archival banner, Rule T1's YAML repoint, and Rule T3's
retirement — landed 2026-07-29 (docs/REMEDIATION_PLAN.md R-P1). `OPEN_GAPS_AND_ROADMAP.md`'s 19
items and `LAUNCH_READINESS_GAPS.md`'s 24 items are a separate ledger family, not part of this
migration's scope, and remain their own prose documents (both still cross-checked by
`check-register-consistency.py`'s original summary-vs-detail rule).
