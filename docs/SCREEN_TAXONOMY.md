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
| WmsOffice | `centro-trabalho` | 30,862 | operator-console | none (evaluated ⁶, not authored) | hand-written → contract; Aggregate Workbench answers the 2-level nesting, still 2 named gaps (⁶) | confirmed |
| WmsOffice | `conferencia-fiscal` | 22,897 | operator-console | `panel` ⁴ (History half) | **partially converted** — History works, Import wizard cannot-express | confirmed |
| WmsOffice | `crossdocking` | 12,748 | **operator-console** ² | `panel` ³ | **converted** (14/20 checklist items work) | confirmed |
| WmsOffice | `excluir-estabelecimento` | 9,820 | detail-form | AutoPanel Detail | generated-equivalent | confirmed |
| WmsOffice | `inventario` | 28,031 | operator-console | `panel` ⁴ (Historico half) | **partially converted** — Historico works, 3 CSV wizards cannot-express | confirmed |
| WmsOffice | `login` | 6,773 | auth | generated login | generated-equivalent | confirmed |
| WmsOffice | `mapa-armazem` | 19,650 | spatial-map | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `movimentacao-livre` | 23,392 | operator-console | `panel` ⁴ (header+item half) + Aggregate Workbench ⁶ (2-level nesting + Sugerir) | **partially converted** — position nesting + Sugerir now work via Workbench; stock-ledger side effect (`syncOcupacao`) still cannot-express | confirmed |
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

³ **2026-07-29, `CAPABILITY_ROADMAP.md` Moves 1+2** (`docs/MOVE1_CONSOLE_CONVERSION_PLAN.md` +
`docs/MOVE2_PANEL_ACTIONS_PLAN.md`, findings in `docs/MOVE1_PANEL_GAPS.md`): Move 1 declared
`crossdocking.html` as `CrossDockingConsolePanel`, authoring-only, and found reads converted
cleanly but actions were blocked by three stacked gaps — G1 (`binding: "flow"` schema-valid,
compiler-accepted, unimplemented at runtime; also affected two already-shipping panels, REG-70),
G2 (panel actions rendered once per panel, never once per row), G3 (no mechanism to collect ad hoc
user input for a non-row-scoped action). Move 2 closed all three the same day (schema + compiler +
runtime + generated-frontend changes, backward-compatible defaults, 5 new tests, 2 new
`dsl-conformance-max` examples) and re-verified live: a real browser fills the 5-field Ativar form
and creates a real CrossDocking via the real flow; per-row Concluir/Cancelar buttons appear only on
`Ativo` rows and execute against the correct row. **14 of the original 20 checklist behaviours now
work** (up from 3), confirming none of the three was a hard architectural limit.

**Corrected count: WmsOffice has 5 of 13 screens classified operator-console** (not "6 of 13" as
an earlier planning note asserted — that figure does not survive a fresh, reasoned re-measurement
on this checkout, whichever methodology originally produced it; 5/13 is still the plurality class
and the strongest single-app signal).

⁴ **2026-07-29, `CAPABILITY_ROADMAP.md` Move 2 G4** (`docs/MOVE2_PANEL_ACTIONS_PLAN.md`, results in
`docs/MOVE2_G4_CHECKLISTS.md`). All 5 operator-console screens have now been attempted or evaluated.
Two consistent, non-blocking-per-se boundaries found across the remaining four (none of them a hard
architectural wall — see the detail doc): **Class A** (multi-step wizard: parse/preview → an N+1-
write confirm — `conferencia-fiscal`'s Import, all 3 of `inventario`'s CSV flows) has no declared
Panel surface for either half; **Class B** (nesting deeper than 1 level — `movimentacao-livre` /
`centro-trabalho`'s Movimento→MovimentoItem→MovimentoItemPosicao) hits `PanelValidation`'s existing,
deliberate 1-level nesting cap — the Aggregate Workbench primitive is the platform's answer for that
shape, not Panel. `centro-trabalho.html` was evaluated by close reading rather than authored+built:
it is a structural superset of `movimentacao-livre.html` (same two blockers, no new mechanism), and
a third live-verification of an already-proven boundary would not have added evidence. A real
platform bug (REG-71: `scope: "row"` + `conceptMutation` blanked required fields) was found and
fixed while authoring this batch.

## Declared (generated) surfaces per app

Counted separately so generated screens are never undercounted alongside hand-written ones —
these are what each app's `model.json` already declares (`autoPanels`/`panels`/`aggregates`/
`selectors`), regardless of whether a hand-written `web/` page also exists for the same concept.

| App | autoPanels | panels | aggregates | selectors |
|---|---|---|---|---|
| AuxScreen | 0 | 2 | 0 | 0 |
| Pigmentampa | 0 | 2 | 0 | 0 |
| WmsOffice | 3 ⁶ | 9 ⁵ | 3 ⁶ | 0 |
| WordLab | *(not scanned — no `web/` dir; declared surfaces alone drive its entire UI)* |
| Claude Support Desk | *(not scanned — no `web/` dir; declared surfaces alone drive its entire UI)* |

⁵ Was 4 before 2026-07-29. Move 1 added `CrossDockingConsolePanel` (see ³ above, now fully working
after Move 2 G1-G3). Move 2 G4 added 4 more: `ConferenciaFiscalNfePanel`, `ConferenciaFiscalRomaneioPanel`,
`MovimentoLivrePanel`, `InventarioHistoricoPanel` — each a partial conversion of its screen (see ⁴
above and `docs/MOVE2_G4_CHECKLISTS.md`).

⁶ **2026-07-29, `CAPABILITY_ROADMAP.md` Move 3 G1-G2** (`docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md`,
results in `docs/MOVE3_G2_CHECKLISTS.md`). G1 fixed a real non-atomicity bug in `AggregateRuntime.commit`
(REG-72) and proved depth-2 recursion (root -> collection -> nested collection) sound, both RED->GREEN
and live. G2 added WmsOffice's first depth-2 aggregate, `Movimento` (-> `itens` -> `posicoes`),
answering Class B's "nesting past 1 level" blocker for `movimentacao-livre`/`centro-trabalho` —
verified live via REST and a real browser (nested band rendering + real cell values, confirmed by
screenshot, not just the DOM-text dump, which mis-reads dense tables as blank). Assessing the
`Sugerir*` suggestion flows against `invoke()` (procedure-over-draft) found and fixed a second real
platform bug (REG-73: `ProcedureRunner` never resolved a capability adapter from the model's
`bindings`, so every procedure-side `capabilityCall` failed regardless of aggregates) and closed a
previously-unused generator seam (`autoPanels[].transaction.metadata.actions`) to wire the suggestions
as real clickable Workbench buttons, live-verified. Two real, still-open residuals named (not fixed):
a position edit's stock-ledger side effect (`syncOcupacao`) has no cross-aggregate write mechanism,
and a `Sugerir` result isn't auto-applied into a new position row by the generic renderer. Neither
console reaches parity — 0 B deleted, both originals unchanged (see `docs/MOVE3_G2_CHECKLISTS.md`
§"Deletion eligibility").

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

## Ratio re-measurement (Move 2 G4 close, 2026-07-29)

`CAPABILITY_ROADMAP.md`'s target: WmsOffice's hand-written-to-model ratio, `1.02x → ~0.45x`, with a
Move 2 Definition-of-Done threshold of **below 0.6x**.

```
model     (AppGen/apps/_official/WmsOffice/definition/**/*.json, full tree)   274,488 B
hand-written (AppGen/apps/_official/WmsOffice/web/*.html, excluding
              this session's *.original.html backups)                        210,689 B
ratio = hand-written / model                                                    0.77x
```

**Methodology note:** this counts the full `definition/` tree (`model.json` plus every `$ref`'d
`concepts/*.json`/`packs/*.json` fragment), the same shape `model.json`'s own `$ref` structure
implies the roadmap's original 265,384 B figure measured. The two numbers are not confirmed
byte-for-byte reconcilable to the original measurement script (not available to re-run here) — treat
this as a fresh, independently-reasoned measurement with a stated method, not a certified diff.

**The ratio improved (1.02x baseline → 0.77x here) entirely from the model growing** — 5 new panels
across Move 1 and Move 2 G4, real declared capability — **not from any hand-written file shrinking**.
Zero bytes of hand-written HTML were deleted this session, correctly: per Move 1's own rule ("keep
the original until its replacement passes"), none of the five operator consoles reached full
parity — `crossdocking` is 14/20 checklist items, the other four are partial conversions with named,
real remaining gaps (Class A wizards, Class B nesting). **The DoD's `< 0.6x` threshold is not yet
met**, and it cannot be met without deletions that the evidence doesn't yet support making.

**What closing the gap requires**, honestly, not assumed: not more schema/runtime work (G1-G3 are
already closed and sufficient for everything Class A/B don't need) — it requires either (a) a real
design pass on Class A (structured multi-row input + multi-write actions) and Class B (already has
an answer: Aggregate Workbench, not Panel) if a second/third console justifies it per the roadmap's
own "≥2 consoles" promotion rule — both classes already clear that bar (Class A: 2 screens; Class B:
2 screens) — or (b) accepting the remaining hand-written surface as correctly-scoped bespoke UI and
moving to Move 3's contract-generation path for it instead.

## Methodology

`python <scratchpad>/helpers/classify-screens.py --apps-root D:/WorkSpace/NPDev/AppGen/apps --format md`
— walks every `web/*.html` under the apps root (also checked `NPDevSamples` separately: zero
hand-written `web/` folders found there), scores nine regex signal families per file (visualisation,
table, form, flow-invocation, wizard, editable-grid, auth, admin, report/export), classifies by a
fixed most-specific-first rule order, and cross-references each app's declared
`autoPanels`/`panels`/`aggregates`/`selectors`. Read-only; nothing in this repo or `AppGen/apps` was
written by the tool. One classification (`crossdocking`) was overridden by hand per the footnote
above; the tool's own design explicitly expects this ("no judgment — a human names the classes").
