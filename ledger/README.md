# Ledger — structured open-items tracking

**Status: PROTOTYPE, 2026-07-28.** This directory is the first slice of the 2.E ledger migration
(`docs/NEXT_EXECUTION_PLAN.md` Part 5): `docs/NPDEV_OPEN_ITEMS_REGISTER.md` remains the
**authoritative** source until migration is complete for every entry — this proves the schema,
the generator, and gate-compatibility on a real subset (REG-54 through REG-62, this session's own
work, verified for fidelity against the source register) before committing to a full cutover.

## Why

The prose register represents status two ways at once — `~~strikethrough~~` on the ID, and a
freeform bolded verdict sentence in the detail cell — and nothing prevents them from disagreeing.
REG-59 did exactly that (struck, but its own detail text still argued the underlying gap was open),
caught only by a purpose-built regex rule (`check-register-consistency.py`'s Rule T2). A single
structured `status` field makes that class of contradiction impossible by construction: there is
only one place status lives.

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
legacyDetailRef: docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-61   # required until full narrative migrates
```

`legacyDetailRef` is temporary scaffolding for this prototype phase: the full, often
multi-thousand-word investigation narrative for older entries stays in the prose register rather
than being bulk-copied (error-prone, and not the actual problem this migration solves — see "Why"
above). New entries filed directly in the ledger should eventually drop this field once the prose
register is retired.

## Generating `docs/OPEN_ITEMS.md`

```
python scripts/quality/generate_open_items.py           # regenerate
python scripts/quality/generate_open_items.py --check    # exit 1 if stale (CI form)
```

Regenerates `docs/OPEN_ITEMS.md` from every `ledger/items/*.yml`, validating each file's schema
first (required fields, enum values, `status: DONE` requires `closed`). Never hand-edit that file.

**Dependency note:** this script needs PyYAML (`import yaml`), the only script under `scripts/`
that does — everything else here is stdlib-only. Not yet declared in a `requirements.txt` (none
exists in this repo) since nothing wires this script into a CI gate yet; do that before relying on
it in CI, or CI's Python may not have it installed.

## What's NOT done yet (honest status, not a backlog to silently claim)

- Only 9 of 60+ `NPDEV_OPEN_ITEMS_REGISTER.md` entries are migrated (REG-54–REG-62). The other ~53,
  plus `OPEN_GAPS_AND_ROADMAP.md`'s 19 and `LAUNCH_READINESS_GAPS.md`'s 24, are not.
- `check-register-consistency.py`'s T1/T2 rules and `ledger_coverage_gaps` still read the PROSE
  register — they have not been repointed at `ledger/items/*.yml`. Doing so before migration is
  complete would make the gate blind to the 90%+ of items still only in prose.
- No gate enforces `ledger/items/*.yml` schema validity yet (a `check-ledger-schema.py` companion to
  `check-panel-provenance-impact.py`'s pattern would be the natural next step).
- The "13 process docs hard-wired into gates" the plan names as unblocked by this work were not
  identified or unwired in this prototype pass — that needs its own investigation.

**Cutover is a separate, later decision**: migrate the remaining entries (mechanical, one YAML file
per row), point the gates at `ledger/items/*.yml` instead of the prose tables, then retire the prose
register in favor of the generated `docs/OPEN_ITEMS.md`. Not done here — deliberately, to avoid
leaving the repo's actual governance source of truth in a half-migrated, ambiguous state.
