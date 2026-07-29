# Screen taxonomy (F1, `docs/FRONTEND_STRATEGY_PLAN.md` / `docs/NEXT_EXECUTION_PLAN.md` P4.1)

> **STATUS: EXECUTED.** Measured 2026-07-28 against `D:\WorkSpace\NPDev\AppGen\apps` (the five
> official apps — WmsOffice, WordLab, AuxScreen, Pigmentampa, Claude Support Desk — live there under
> `_official/`) plus every other AppGen sample, and `NPDev_General\NPDevSamples` (zero hand-written
> `web/` folders found there — not reproduced in the table below).

## What this is

The measurement half of F1: which screen *classes* exist across NPDev's real apps, which NPDev
primitive already covers each class (if any), and which class recurs often enough — per the
plan's own encoded promotion rule — to justify building a new one. The tool
(`<scratchpad>/helpers/classify-screens.py`, staged, read-only, walks every app's `web/*.html`)
does the measurement; the paragraphs below are the human judgment layer the tool's own docs call
for ("classify-screens.py measures; a human names the classes").

**Promotion rule, as encoded:** a hand-written class in **≥ 2 apps** with **≥ 2 screens** is a
primitive candidate.

## Per-screen classification

**Manifest column** (R-G2, `docs/REMEDIATION_PLAN.md`): `confirmed` = a reviewed `*.panel.json` exists
and the impact gate (F4) enforces it; `n/a` = no hand-written screen to manifest (fully generated).
**15/15 hand-written screens confirmed** (13/13 WmsOffice 2026-07-29; AuxScreen/Pigmentampa closed
2026-07-29 by `docs/CORPUS_INTEGRITY_PLAN.md` C2/C3, see below) — up from 3/15 at the start of R-G2.

| App | Screen | Bytes | Class | Primitive | Status | Manifest |
|---|---|---|---|---|---|---|
| AuxScreen | `aux-screen` | 6,071 | detail-form | AutoPanel Detail | generated-equivalent | confirmed ¹ |
| Pigmentampa | `pigmentampa-editor` | 14,341 | detail-form | AutoPanel Detail | generated-equivalent | confirmed ¹ |
| WmsOffice | `analytics` | 11,455 | dashboard | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `centro-trabalho` | 30,862 | operator-console | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `conferencia-fiscal` | 22,897 | operator-console | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `crossdocking` | 12,748 | **operator-console** ² | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `excluir-estabelecimento` | 9,820 | detail-form | AutoPanel Detail | generated-equivalent | confirmed |
| WmsOffice | `inventario` | 28,031 | operator-console | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `login` | 6,773 | auth | generated login | generated-equivalent | confirmed |
| WmsOffice | `mapa-armazem` | 19,650 | spatial-map | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `movimentacao-livre` | 23,392 | operator-console | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `novo-estabelecimento` | 16,347 | detail-form | AutoPanel Detail | generated-equivalent | confirmed |
| WmsOffice | `relatorios` | 10,540 | dashboard | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `seed-data` | 8,674 | admin-tool | ControlPanel (partial) | hand-written | confirmed |
| WmsOffice | `usuarios-roles` | 9,500 | auth | generated login | generated-equivalent | confirmed |
| WordLab | *(none)* | — | — | — | **fully generated — no `web/` directory at all** | n/a |
| Claude Support Desk | *(none)* | — | — | — | **fully generated — no `web/` directory at all** | n/a |

¹ **Closed 2026-07-29 (`docs/CORPUS_INTEGRITY_PLAN.md` C2/C3).** Was blocked, R-G2's own 2026-07-29
finding: neither app could regenerate with the toolchain of the day (a pre-DSL-2.0 flow-step shape
the schema rejected outright). Measuring the real scope found this was 17 of 29 corpus models, not
2 (see REG-63) -- `npdev migrate dsl-2 --write` fixed all of them. Both apps now generate, build, and
boot clean; `aux-screen.panel.json` and `pigmentampa-editor.panel.json` were authored fresh
(bootstrap-panel-provenance.py + hand review) and confirmed live against each app's real bundle, 0
problems from `check-panel-provenance-impact.py`.

² **Human override of the mechanical classifier's default.** `classify-screens.py`'s
`operator-console` rule requires `flow-invocation signals ≥ 1 AND form-input signals ≤ 4`;
`crossdocking.html` has 3 flow invocations (`/api/flows/.../execute`) but 7 form-input elements,
so the mechanical pass placed it in `detail-form` on that threshold alone. Read by hand: it invokes
business flows to perform a cross-docking operation, the same shape as `centro-trabalho`/
`inventario`/`movimentacao-livre` — an operation console with a larger configuration form, not a
passive CRUD detail screen. Reclassified here. (`excluir-estabelecimento` and
`novo-estabelecimento` were also checked by hand: **zero** flow invocations each — genuinely
CRUD-shaped, the mechanical `detail-form` call stands.)

**Corrected count: WmsOffice has 5 of 13 screens classified operator-console** (not "6 of 13" as
an earlier planning note asserted — that figure does not survive a fresh, reasoned re-measurement
on this checkout, whichever methodology originally produced it; 5/13 is still the plurality class
and the strongest single-app signal).

## Declared (generated) surfaces per app

Counted separately so generated screens are never undercounted alongside hand-written ones —
these are what each app's `model.json` already declares (`autoPanels`/`panels`/`aggregates`/
`selectors`), regardless of whether a hand-written `web/` page also exists for the same concept.

| App | autoPanels | panels | aggregates | selectors |
|---|---|---|---|---|
| AuxScreen | 0 | 2 | 0 | 0 |
| Pigmentampa | 0 | 2 | 0 | 0 |
| WmsOffice | 2 | 4 | 2 | 0 |
| WordLab | *(not scanned — no `web/` dir; declared surfaces alone drive its entire UI)* |
| Claude Support Desk | *(not scanned — no `web/` dir; declared surfaces alone drive its entire UI)* |

## Promotion-rule verdict

**Mechanically: zero candidates.** No hand-written class reaches ≥ 2 apps — every genuinely
hand-written screen in the measured corpus (`operator-console`, `dashboard`, `spatial-map`,
`admin-tool`) lives in WmsOffice alone. `detail-form`/`auth` DO reach ≥ 2 apps (AuxScreen +
Pigmentampa + WmsOffice), but both are marked `generated-equivalent` — the classifier's own
judgment is that AutoPanel Detail / generated login already cover this shape, so they are not
candidates by the rule's own "hand-written" precondition.

**The honest reading, not a forced "yes":** `operator-console` is real, concentrated, unmet need —
the plurality class in NPDev's one substantial hand-authored app, and the class the plan's own
prose (correctly, if imprecisely on the count) singled out. But it is **one real second app away**
from mechanically qualifying, not already qualifying. Building a new primitive on an n=1 sample
would be designing from a single example, which is exactly what F1's own rule exists to prevent.
**Do not build `operator-console` as a primitive yet.** Revisit the rule the next time any app
(official or sample) accumulates ≥ 2 hand-written operator-console-shaped screens — F2/F3's
contract substrate (routing hand-written screens through `invocations`/provenance rather than a new
generated primitive) is the correct near-term answer for WmsOffice's existing five, matching what
this table's "Status" column already says for all of them.

## Bonus: precise generated-vs-hand-written accounting (per F1's own ask)

This replaces the README's previous vague "custom business screens are hand-written" with a
measured breakdown:

- **2 of 5 official apps are fully generated** (WordLab, Claude Support Desk) — zero hand-written
  screens, `model.json`'s declared surfaces alone drive the entire UI.
- **2 of 5 (AuxScreen, Pigmentampa) have exactly one hand-written page each**, and both are
  classified `generated-equivalent` — present as a physical custom file, but shaped like something
  AutoPanel Detail already produces.
- **1 of 5 (WmsOffice) carries real hand-written surface area**: 13 pages, 8 of which
  (`operator-console` ×5, `dashboard` ×2, `spatial-map` ×1) have no generated equivalent today, plus
  1 `admin-tool` page partially covered by ControlPanel. This is the one app where "custom business
  screens are hand-written" is actually true and substantial — and it is the corpus F2/F3's contract
  substrate (`invocations`, provenance, the impact gate) is built to bring under contract, not
  replace.

## Methodology

`python <scratchpad>/helpers/classify-screens.py --apps-root D:/WorkSpace/NPDev/AppGen/apps --format md`
— walks every `web/*.html` under the apps root (also checked `NPDevSamples` separately: zero
hand-written `web/` folders found there), scores nine regex signal families per file (visualisation,
table, form, flow-invocation, wizard, editable-grid, auth, admin, report/export), classifies by a
fixed most-specific-first rule order, and cross-references each app's declared
`autoPanels`/`panels`/`aggregates`/`selectors`. Read-only; nothing in this repo or `AppGen/apps` was
written by the tool. One classification (`crossdocking`) was overridden by hand per the footnote
above; the tool's own design explicitly expects this ("no judgment — a human names the classes").
