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
| WmsOffice | ~~`analytics`~~ | ~~11,455~~ | **dashboard** | `guidePageGadget` ¹⁰ (kpi/bar/table) | **REPLACED 2026-08-01** (Move 10 B2, LC-B2) — now a ~1.1 KB thin host page (`<meta name="npdev-guide-page">` + shell.css/shell.js, zero page-specific JS). Behavioural parity: 2 of 3 original widgets replaced faithfully (Ocupacao por Rua → table gadget; Movimentos por Tipo e Situacao → bar gadget) + 1 new (Locais Ocupados kpi) · 1 named cannot-express (Estoque por Produto needs a cross-concept join B1's single-concept aggregate query does not support — **REG-105**, accepted boundary). Frozen reference kept as `analytics.original.html`. See `move10-b2-charts.txt` | confirmed |
| WmsOffice | ~~`centro-trabalho`~~ | ~~30,862~~ | **operator-console** | `panel` ⁴ (Movimento header/item half, unchanged) + Aggregate Workbench ⁶ (2-level Movimento nesting + Sugerir, unchanged; NEW: `RecebimentoWorkbench`/`ExpedicaoWorkbench` with Planning-layer `lotes`/`itens` add-row, lifecycle transitions, `Criar Movimento`) | **REPLACED — hand-written page DELETED 2026-08-01** (Move 13 P1.2). C2 (Planning layer) CLOSED: `RecebimentoLote`/`ExpedicaoItem` add-row live-verified via REST+DB (create, invariant-gated lifecycle transitions) and real-browser screenshot on both aggregates — zero new platform primitive, exactly the "plain 1-level addable list" this doc's own prior entry predicted. C1 (record-type toggle) is now **moot by design**, not "closed by `transaction.uiState`" as this row previously claimed — that claim was itself imprecise: Move 11 W6/REG-99 shipped a DIFFERENT toggle (`Movimento`'s own `$ui.detalhe` Completo/Resumo control, confirmed against REG-99's own predicate/options in `docs/OPEN_ITEMS.md`), not a Recebimento/Expedicao record-type selector. Since Recebimento and Expedicao now live on two SEPARATE generated Workbench panels, no single surface ever needs a client-side toggle between them — the shape C1 worried about no longer exists. "Sugerir auto-apply" is CLOSED, not unclear: `Movimento`'s existing `SugerirDestino`/`SugerirOrigem` actions already declare `applyTo: {mode:"appendRow"}` (shipped Move 3 G2, confirmed by reading `model.json` directly), which auto-applies the suggestion into a new position row with no manual copy-paste step. Frozen reference kept as `centro-trabalho.original.html`. See `NPDev_General__OutsideRepo/move13/p1-console-conversion-report.md` | confirmed |
| WmsOffice | ~~`conferencia-fiscal`~~ | ~~22,897~~ | **operator-console** | `panel` ⁴ (History halves) + Aggregate Workbench ⁷ ⁸ (both Import wizards) | **REPLACED — hand-written page DELETED 2026-07-31** (Move 10 W1.1). Behavioural parity: 10 works · 3 differs (cosmetic) · 1 n/a · **0 cannot-express**. Romaneio Import authored this move as a direct mirror of NF-e Import. Frozen reference kept as `conferencia-fiscal.original.html`. See `docs/MOVE10_W1_CHECKLISTS.md` | confirmed |
| WmsOffice | ~~`crossdocking`~~ | ~~12,748~~ | **operator-console** ² | `panel` ³ | **REPLACED — hand-written page DELETED 2026-07-30** (Move 8 Part A). Behavioural parity: 15 works · 4 cosmetic differs · 1 n/a · **0 cannot-express**. C10 fully closed (Move 4 `patchConcept` + Move 5 `callProcedure`). Frozen reference kept as `crossdocking.original.html`. See "Checklist re-run after Moves 4–7" below | confirmed |
| WmsOffice | `excluir-estabelecimento` | 9,820 | detail-form | AutoPanel Detail | generated-equivalent | confirmed |
| WmsOffice | ~~`inventario`~~ | ~~28,031~~ | **operator-console** | `panel` ⁴ (Historico half) + Aggregate Workbench ⁷ ⁸ (Importar Contagem, Gerar Template, Recebimento por Arquivo) | **REPLACED — hand-written page DELETED 2026-08-01** (Move 13 P1.1). Recebimento por Arquivo's 3 previously-named blockers (REG-92 multi-line input, no concept-level home for preview columns, no propose→commit state carry) all closed exactly the way ⁸'s own re-assessment predicted: an `AnalisarArquivoRecebimentoDraftProcedure` propose step + `InventarioArquivoAggregate.onCommit`'s new `ProcessarRecebimentoArquivoOnCommitProcedure`, no new platform primitive. 4 real, distinct bugs found and fixed along the way (all app-layer authoring, not platform code): `InventarioArquivoLinha` was missing 2 persisted fields the capability's own parse result already returned (`quantidade`, `ruaId` — silently dropped on commit, the second one causing the allocation step to see every candidate bin as a null-vs-null non-match), `quantidadeEsperada` was schema-required for a field only the OTHER wizard populates, and a capability parameter typed `String` (`encontrarOcupacao`) hit the same "value sourced from listConcepts/mapList isn't guaranteed to be `java.lang.String`" type mismatch this codebase's own `somarQuantidadePorLocalLote` doc comment already named — fixed by folding the lookup into the new `alocarRecebimentoPorRua` capability method (`Object`-typed) instead of a second capabilityCall. Live-verified via REST+DB (Lote find-or-create by natural key, bin allocation in the correct rua, `LocalArmazenagem.situacao` flip to `Ocupado`, bad rows correctly producing zero side effects) and real-browser (the `csvText` action field is a real `<textarea>`, confirming REG-92 does not recur here; preview correctly renders one `OK!` row and one alerted row). **0 `cannot-express`.** Frozen reference kept as `inventario.original.html`. See `NPDev_General__OutsideRepo/move13/p1-console-conversion-report.md` | confirmed |
| WmsOffice | `login` | 6,773 | auth | generated login | generated-equivalent | confirmed |
| WmsOffice | `mapa-armazem` | 19,650 | spatial-map | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `movimentacao-livre` | 23,392 | operator-console | `panel` ⁴ (header+item half) + Aggregate Workbench ⁶ (2-level nesting + Sugerir + M6 banner) | **CONVERTED and DELETED 2026-07-31 (Move 11 W5 / Wave −1.3)** ⁹ — 0 `cannot-express`. M6's balanced-quantity banner was never an authoring gap: it was already declared and silently rendering 0 (REG-95). M11 is `differs`, not `cannot-express` — the flow IS reachable from `MovimentoLivrePanel.confirmarMovimentacao` | confirmed |
| WmsOffice | `novo-estabelecimento` | 16,347 | detail-form | AutoPanel Detail | generated-equivalent | confirmed |
| WmsOffice | `relatorios` | 10,540 | dashboard | none | hand-written → contract (F2/F3) | confirmed |
| WmsOffice | `seed-data` | 8,674 | admin-tool | ControlPanel (partial) | hand-written | confirmed |
| WmsOffice | ~~`usuarios-roles`~~ | ~~9,500~~ | **auth** | ControlPanel Users section | **REPLACED — hand-written page DELETED, Move 11 W3 (RC-B2)** — the raw, ungoverned `identity_roles` picker (any table row, not just model-declared ones; a grant/revoke did not invalidate the user's live session) was replaced by the platform ControlPanel's Users section, which enforces the model's declared `roles[]` vocabulary and bumps `token_version` so a change is live on the user's very next request — a genuine security fix, not just a UI port. See `WmsOffice/README.md`. **Deleted without a frozen `.original.html` at the time** — restored retroactively, Move 12 P3.1 (item 9), from the pre-deletion backup at `NPDev_General__OutsideRepo/move11/wmsoffice-definition-backup-before-movlivre-deletion/web/usuarios-roles.html` (byte-identical, 9,500 B) | confirmed |
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
| WmsOffice | 6 ⁷ ⁸ | 9 ⁵ | 6 ⁷ ⁸ | 0 |
| WordLab | *(not scanned — no `web/` dir; declared surfaces alone drive its entire UI)* |
| Claude Support Desk | *(not scanned — no `web/` dir; declared surfaces alone drive its entire UI)* |

⁵ Was 4 before 2026-07-29. Move 1 added `CrossDockingConsolePanel` (see ³ above, now fully working
after Move 2 G1-G3). Move 2 G4 added 4 more: `ConferenciaFiscalNfePanel`, `ConferenciaFiscalRomaneioPanel`,
`MovimentoLivrePanel`, `InventarioHistoricoPanel` — each a partial conversion of its screen (see ⁴
above and `docs/MOVE2_G4_CHECKLISTS.md`). Still 9 at Move 10 W1: that move added an *aggregate* and
its `autoPanel` (`RomaneioAggregate`), not a `panels[]` entry — which is why the autoPanel/aggregate
columns moved 5 → 6 and this one did not.

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

**Updated 2026-07-31 (Move 8, then re-checked Move 9 D1):** the stock-ledger residual is now
**closed** — `Movimento` declares `aggregate.onCommit: RecomputarOcupacaoOnCommitProcedure` (Move 8),
live-verified including a rollback proof. See `docs/MOVE3_G2_CHECKLISTS.md` M8/M9. The `Sugerir`
auto-apply residual remains open. Neither console reaches full parity yet: `movimentacao-livre` is
still blocked by M6 (balanced-quantity banner, no display-only-recompute mechanism) and M11
(`ConfirmarMovimentacao`'s generic-transition-vs-real-flow gap); `centro-trabalho` by those same two
plus its own C1 (record-type toggle, cannot-express) and C2 (Planning layer, not attempted). 0 B
newly eligible for deletion from this re-check.

**Superseded 2026-07-31 (Move 11), see ⁹ for the current state.** Two of the three blockers named in
the paragraph above turned out to be misdiagnosed, and one was closed by new platform work:
M6's stated cause ("no display-only-recompute mechanism") was wrong — the mechanism existed and the
declared banner was silently rendering 0 (REG-95); M11 is `differs`, not a parity blocker;
C1 is closed by `transaction.uiState` + `$ui.<name>` (Move 11 W6). `movimentacao-livre` is DELETED;
`centro-trabalho` remains, blocked only by C2.

**Superseded 2026-08-01 (Move 13 P1.2) — `centro-trabalho` DELETED, and this footnote's own C1 claim
corrected.** C2 (Planning layer) closed: `Recebimento`/`Expedicao` autoPanels gained a
`transaction` block, giving `RecebimentoLote`/`ExpedicaoItem` generic add-row editing — live-verified
via REST+DB (create, then invariant-gated lifecycle transitions through to the terminal stage) and a
real-browser screenshot showing the `+ add row` control, the lifecycle button, and a new
`Criar Movimento` action on both aggregates' Workbench panels. **The "C1 is closed by
`transaction.uiState`" line directly above is corrected, not just superseded**: re-checking what
Move 11 W6/REG-99 actually shipped (`docs/OPEN_ITEMS.md`'s own REG-99 entry: predicate
`$ui.detalhe == 'Completo'`, options `["Completo","Resumo"]`) shows it is `Movimento`'s own
Completo/Resumo detail-density toggle, not a Recebimento/Expedicao record-type selector — this
footnote conflated the two. The actual resolution for C1 is simpler: Recebimento and Expedicao now
have their OWN separate Workbench panels (`RecebimentoWorkbench`/`ExpedicaoWorkbench`), so no single
surface ever needs a client-side toggle between "Destino-only" and "Origem-only" positions — the
shape C1 worried about doesn't arise. "Sugerir auto-apply" (named "open" two updates above, then
"genuinely unclear" per `NPDev_General__OutsideRepo/move12/part2-decision-package.md`) is confirmed
CLOSED, not unclear: `Movimento`'s existing `SugerirDestino`/`SugerirOrigem` actions already declare
`applyTo: {mode:"appendRow", collection:"posicoes", map:{...}}` (shipped Move 3 G2 itself, per this
footnote's own opening paragraph — "closed a previously-unused generator seam... to wire the
suggestions as real clickable Workbench buttons" — confirmed by reading `model.json` directly, this
move). Both consoles this footnote covers are now fully accounted for: `movimentacao-livre` DELETED
(Move 11), `centro-trabalho` DELETED (Move 13 P1.2). Full detail:
`NPDev_General__OutsideRepo/move13/p1-console-conversion-report.md`.

⁷ **2026-07-29, `CAPABILITY_ROADMAP.md` Move 3 G3-G4** (`docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md`,
results in `docs/MOVE3_G3_FINDINGS.md` / `docs/MOVE3_G4_FINDINGS.md` /
`docs/MOVE3_G4_INVENTARIO_FINDINGS.md`). G3 composed the propose->review->commit triad for real for
the first time: a new `DocumentoFiscalAggregate` + `ParseNfeProcedure` express
`conferencia-fiscal.html`'s NF-e Import wizard, live-verified (REST success/failure paths + real
browser). The one new mechanism needed — workbench-action `inputFields` (mirroring Move 2 G3's
`panelAction.inputFields`) — needed no schema change (the metadata blob was already unstructured
JSON) and is corpus-covered in `dsl-conformance-max`. G4 then: (a) partially closed C10
(`docs/MOVE1_PANEL_GAPS.md`) — fixed the situacao-transition half of Concluir/Cancelar (crossdocking
14/20 -> 15/20), found+fixed REG-74 (the plugin-mount pipeline only ever scanned flow steps for
capability usage, so a capability referenced only by a procedure could never boot), and filed REG-75
(open gap: no procedure mechanism reads a sibling record, patches one field, and writes it back —
why the Recebimento/Expedicao flag-sync half of C10 stays unfixed); (b) proved the G3 pattern
generalizes with a second real Class A console — a new `InventarioArquivoAggregate` +
`ImportarContagemProcedure` express `inventario.html`'s Importar Contagem wizard, live-verified —
and found+fixed REG-76 along the way (workbench `inputFields` rendered a single-line `<input>`,
which silently strips newlines, breaking any multi-line paste; G3's own XML test never triggered it
by luck of using single-line sample data). `inventario`'s other two wizards were assessed and
named, not silently skipped: Gerar Template is a genuine shape mismatch (generate+download, not
persist); Recebimento por Arquivo's commit half was blocked by REG-75 at G3/G4 time (closed Move 4,
see the update immediately below). No console reached full parity in G3/G4 either — 0 B deleted from
either `conferencia-fiscal.html` or `inventario.html`.

**Updated 2026-07-31 (re-checked Move 9 D1):** both blockers named above are now closed at the
platform level — REG-75 (`patchConcept`) closed Move 4, and the generate-and-download shape
(`panelAction.resultAs: "download"`) shipped Move 5 Wave 4 — but neither wizard has actually been
authored yet against `inventario`, so this remains an **authoring gap, not a platform blocker**.
Same status for `conferencia-fiscal`'s Romaneio Import: the NF-e Import pattern (G3, above) now
applies directly, unauthored. No new deletions from this re-check.

⁸ **2026-07-31, Move 10 Wave 1** (`docs/MOVE10_W1_CHECKLISTS.md`). The Move 9 D1 re-check
immediately above claimed all three remaining items were "authoring gap only". Doing the authoring
found that **true for two of them and wrong for the third**:

- **`conferencia-fiscal`'s Romaneio Import — correct, and it converted.** A new `RomaneioAggregate`
  + `ParseRomaneioProcedure` mirror `DocumentoFiscalAggregate`/`ParseNfeProcedure` exactly; no new
  mechanism was needed. Live-verified at REST, at the database, and in a real browser (parse →
  auto-match by product name → duplicate-number rejection → atomic header+items commit → the row
  appearing in the existing History panel). That took the console to **0 `cannot-express`**, so
  `conferencia-fiscal.html` was deleted — the second console to reach the metric's bar.
- **`inventario`'s Gerar Template — correct, but the item was mis-stated.** It was not "not
  attempted": Move 5 Wave 4 had already declared it, and it was *behaviourally incomplete* in three
  ways nobody had checked against the original (`produtoId` column hard-coded to `""`, no
  zero-quantity filter, and it never created the `Gerado` `InventarioArquivo` header row that wizard
  2 selects — leaving the two wizards disconnected). All three closed and live-verified.
- **`inventario`'s Recebimento por Arquivo — the premise was WRONG.** Three independent mechanism
  gaps, none of them authoring: (1) **REG-92**, panel-action `inputFields` still render
  `<input type="text">` and silently collapse newlines — REG-76's fix was never mirrored from the
  Workbench to the Panel — proven live (a 3-line paste arrives with `newlines: 0`), so the wizard
  fails at "give the server a CSV"; (2) the preview table's columns (`ruaCodigo`, `alerta`, `ok`, …)
  are declared on no concept, so no band can render them; (3) nothing carries parsed state from a
  preview action to a confirm action, so "Confirmar commits exactly what you previewed" is not
  expressible. `inventario.html` is **not** deleted.

Four platform bugs were found in the course of this: **REG-89** (fixed — `patchConcept`'s
author-time "id is required" rule was never relaxed for `createIfMissing`, making REG-77's shipped
create-only path unreachable from any model), **REG-93** (fixed — the panel-provenance impact gate
failed on manifests whose screen had been *deleted*, and had therefore been RED since Move 8's own
crossdocking deletion, structurally punishing the bytes-deleted metric for succeeding), **REG-90**
(fixed — `Rebuild-And-Restage.ps1` accepted `-BuildRoot` but never passed it to the build step),
and **REG-91** (open — a claim table with NOT NULL columns makes an app permanently unbootable
behind the opaque message "No data is available").

**Superseded 2026-08-01 (Move 13 P1.1) — `inventario` DELETED, all three named blockers closed.**
Following `NPDev_General__OutsideRepo/move12/part2-decision-package.md`'s re-assessment (all three
re-classified authoring-only, no new primitive needed), Recebimento por Arquivo was actually authored
against the Aggregate Workbench pattern: (1) REG-92 does not recur here — a fresh action
(`AnalisarArquivoRecebimentoDraftProcedure`'s `csvText` inputField) renders as a real `<textarea>`,
confirmed live in a real browser (Playwright `fill` against `textarea[placeholder='csvText']`
succeeded, which a single-line `<input>` could not have matched); (2) the preview columns
(`ruaCodigo`, `produtoCodigo`, `dataValidade`, `alerta`, `ok`, `quantidade`, `quantidadeAvulsa`,
`posicoes`, `linha`, `ruaId`) are now declared fields on `InventarioArquivoLinha`, so the aggregate's
`linhas` band renders them directly — live-verified via browser screenshot (2 rows, one `OK!`, one
alerted); (3) state carries from propose to commit via the Aggregate Workbench draft exactly as
`ImportarContagemProcedure` already proved, with a new `origem` discriminator field on
`InventarioArquivo` letting the shared `onCommit` hook (`ProcessarRecebimentoArquivoOnCommitProcedure`)
tell this commit apart from the other two wizards' (a null/missing `origem` is a pure no-op, so
Importar Contagem/Gerar Template are byte-for-byte unaffected). Live-verified end to end via REST+DB:
an accepted row correctly finds-or-creates its `Lote` by natural key, allocates a bin in the correct
rua, upserts `LocalArmazenagemLote`, and flips `LocalArmazenagem.situacao` to `Ocupado`; a
simultaneously-rejected row (bad rua code) correctly produces zero side effects. **0 `cannot-express`.**
Full detail, including the 4 real app-layer bugs found and fixed along the way, in
`NPDev_General__OutsideRepo/move13/p1-console-conversion-report.md`.

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
- **1 of 5 (WmsOffice) carries real hand-written surface area**: 13 pages ORIGINALLY, 8 of which
  (`operator-console` ×5, `dashboard` ×2, `spatial-map` ×1) had no generated equivalent at the time
  this breakdown was written, plus 1 `admin-tool` page partially covered by ControlPanel. This is
  the one app where "custom business screens are hand-written" is actually true and substantial —
  and it is the corpus F2/F3's contract substrate (`invocations`, provenance, the impact gate) is
  built to bring under contract, not replace. **Stale as a live count** (this classification predates
  the byte-metric restatement below): 4 of these 13 are now removed and 1 (`analytics`) is a shim —
  see "Move 3 final metric" below for the current, reconciled figures. The class breakdown itself
  (which primitive covers which shape) is unaffected by a given screen's current byte count.

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

## Move 3 final metric — bytes deleted (2026-07-29, supersedes the ratio above)

Move 3's own §6 (`docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md`) retired the hand-written/model **ratio**
as the target metric — it rewards the model growing even when nothing is replaced, exactly what
happened above (1.02x → 0.77x from panels being *added*, zero files shrinking). The replacement:
**bytes of `web/*.html` removed after a console reaches full behavioural parity** — a console's
original is removed **only** at parity; partial conversion counts zero, by design, so the metric
cannot be gamed by partial work.

**Eligible set, restated (Move 12 P3.2, 2026-08-01):** the console-only framing above (5 operator
consoles, 117,930 B) was correct for its own scope but had gone silent on everything else — by this
move, dashboards (`analytics`, LC-B2) and auth screens (`usuarios-roles`, RC-B2) had ALSO become
convertible, and one of them (`usuarios-roles`) had already been converted without ever being added
to this metric. The eligible set is now **all 13 originally hand-written `web/*.html` screens**,
matching the byte figures the per-screen table above already carries:

```
baseline (13 screens, original sizes, 2026-07-29 measurement)   210,689 B
live today (AppGen/apps/_official/WmsOffice/web/*.html,
            excluding *.original.html, 2026-08-01 Move 13 P1
            re-measurement)                                      72,910 B
removed                                                          137,779 B   (= 210,689 - 72,910)
```

(Prior measurement, before Move 13 P1's two conversions: live 131,803 B, removed 78,886 B — both
superseded by the figures above.)

**One tally, three columns — screen, bytes removed, mechanism.** Three distinct mechanisms exist and
are now distinguished rather than folded into one "deleted" bucket:

| Screen | Bytes removed | Mechanism |
|---|---|---|
| `crossdocking` | 12,748 | deleted — frozen `.original.html` kept from the moment of deletion (Move 8 Part A, 2026-07-30) |
| `conferencia-fiscal` | 22,897 | deleted — frozen `.original.html` kept from the moment of deletion (Move 10 W1.1, 2026-07-31) |
| `movimentacao-livre` | 23,392 | deleted — frozen `.original.html` kept from the moment of deletion (Move 11 W5, 2026-07-31) |
| `usuarios-roles` | 9,500 | deleted — **no** frozen `.original.html` at deletion time (Move 11 W3, RC-B2); restored retroactively from an outside-repo backup, Move 12 P3.1 (item 9) |
| `analytics` | 10,349 | **shim** — reduced from 11,455 B to a 1,106 B guide-page host (`<meta name="npdev-guide-page">` + shell.css/shell.js), not deleted outright; the 1,106 B residual is still counted as live hand-written below (Move 10 B2, LC-B2, 2026-08-01) |
| `inventario` | 28,031 | deleted — frozen `.original.html` kept from the moment of deletion (Move 13 P1.1, 2026-08-01) |
| `centro-trabalho` | 30,862 | deleted — frozen `.original.html` kept from the moment of deletion (Move 13 P1.2, 2026-08-01) |
| **Total removed** | **137,779** | reconciles exactly to `210,689 - 72,910` |

**The shim judgment, stated explicitly (item 10):** does a shim count as "converted"? **Yes, for the
screen class** — `analytics` no longer carries any hand-rolled dashboard logic; every number it
shows is server-fed through `guidePageGadget`, the same primitive a from-scratch dashboard would
use. **But the residual 1,106 B stays counted as live hand-written bytes**, not zeroed out — it is
still a physical custom file, not a fully generated surface. This is deliberately un-gameable: an
author cannot claim a screen "deleted" by shrinking it to a near-empty shell while leaving one byte
on disk; the metric only ever credits the bytes actually removed, whatever remains still counts.

**6 of 13 originally hand-written screens are now removed** (5 fully deleted-and-frozen, 1
deleted-without-a-frozen-original-until-retroactively-restored) **and 1 reduced to a shim** — not "3
of 5 operator consoles," which undercounted by ignoring the two non-operator-console classes this
same session made convertible. **Move 13 P1 (2026-08-01) closes out the operator-console class
entirely: all 5 of 5 are now converted** (`crossdocking`, `conferencia-fiscal`, `movimentacao-livre`
earlier; `inventario`, `centro-trabalho` this move) — zero operator consoles remain hand-written.

**Per-screen table and tally agree** (verified by re-reading both after this edit): the per-screen
table above shows `analytics` at its current 1,106 B with a REPLACED status, `crossdocking`/
`conferencia-fiscal`/`movimentacao-livre`/`usuarios-roles`/`inventario`/`centro-trabalho` all struck
through with their removed sizes, and every other screen's live size unchanged — the same numbers
this tally's "live today" figure sums to (verified live: `72,910` B, measured directly from
`AppGen/apps/_official/WmsOffice/web/*.html` excluding `*.original.html`, 2026-08-01).

⁹ **2026-07-31, Move 11 W5 + Wave −1.3** (`MOVE11_CLOSE_REMAINING_SPEC.md`,
`MASTER_AI_PLATFORM_PROGRAMME_v2.md`). The last blocker was not what three moves of notes said it
was. M6's balanced-quantity banner had been recorded `cannot-express` since Move 3 for "no
computed/derived-display mechanism in the generic Workbench" — but the mechanism shipped in Move 5
Wave 2B, the banner was **already declared** in this app's own model, and it had been rendering `0`
from the day it shipped: `evaluateDerived` split its path on `.` before matching, so every
`filter(...)` form — including the function's own documented example — silently evaluated to zero
(**REG-95**). Fixed by tokenizing instead of splitting, and proven in a real browser with two
distinct non-zero totals (`Total Itens: 25 | Total Origem: 7 | Total Destino: 25`), deliberately
seeded so a green could not be confused with the bug's own answer of 0.

M11 (`ConfirmarMovimentacao` flow vs. the Workbench's generic lifecycle transition) stays **differs**,
as Move 3 recorded it — the original console's Confirmar is reproduced by
`MovimentoLivrePanel.confirmarMovimentacao`, which is flow-bound and does emit `MovimentoConfirmado`
(verified live in Move 2 G4). The Workbench's generic transition is a second path that writes the
status field without the event; making an `onCommit` hook emit it conditionally is blocked by
**REG-96** (a procedure's branch predicate is truthiness-only), filed rather than half-built.

What the deletion changed: `menu.json`'s flat `{"kind":"PAGE","target":"movimentacao-livre.html"}`
became a submenu of two `{"kind":"BUSINESS","target":"__panel-<Name>__"}` children
(`MovimentoLivrePanel`, `MovimentoWorkbench`) — the same convention conferencia-fiscal's deletion
used, no new one invented; `pages.json`'s companion-page registration was removed;
`movimentacao-livre.original.html` is kept frozen as the reference, and
`movimentacao-livre.panel.json` is kept as the provenance record. **No flows were deleted**, unlike
crossdocking: every flow this console called (`ConfirmarMovimentacao`, `SugerirDestino`,
`SugerirOrigem`, `SyncOcupacao`) is also called by `centro-trabalho.html`, which remains — checked
before deleting, precisely because the crossdocking rule says to remove now-callerless flows.

**Zero bytes deleted, across every gate this move ran (G1 through G4).** This is not a shortfall in
execution — every console above gained real, live-verified capability across Move 2 and Move 3 — it
is the metric doing exactly what §6 designed it to do: refuse to reward partial conversion. Four
real platform bugs were found and fixed in the course of this work (REG-72, REG-73, REG-74, REG-76)
and one real gap was found, precisely diagnosed, and left open rather than forced (REG-75) — that is
this move's actual yield, and the bytes-deleted metric correctly reports that as "not yet realized
in deletable form," which is the honest state of things: every remaining gap is either a genuinely
new platform mechanism (REG-75's read-patch-write; a declarative cross-aggregate write hook) or
scope not attempted (Romaneio Import, the Planning-layer list, Gerar Template's download affordance),
not a small polish pass away from parity.

¹⁰ **2026-08-01, Move 10 B2 (LC-B2, `MOVE10_AI_LOWCODE_PLAN.md` Part B.2).** The class `dashboard`'s
`none` primitive closes: `guidePageGadget.type` gained a closed catalog (`kpi`/`bar`/`line`/`table`,
alongside the pre-existing rail types `recent-items`/`context-info`/`page-fragment`), each
query-bound chart type binding to a named `groupBy`/`aggregates` query (Move 10 B1) via
`{query, x, y, series}`, validated at compile time (query must exist, must resolve to an aggregate
query, `x`/`y`/`series` must each name a real `groupBy` field or `aggregates` output — 12 unit tests,
including the RED "bound to a nonexistent query/field" cases), rendered server-fed (no client-side
arithmetic, no charting library from a CDN — bar/line are hand-rolled inline SVG in `shell.js`).
Live-verified via ScrapForAI against a real JWT-authenticated session: the kpi/bar/table gadgets on
WmsOffice's new `AnalyticsDashboard` GuidePage rendered real numbers that matched a tenant-scoped
SQL cross-check exactly (screenshot + full transcript in `move10-b2-charts.txt`). `relatorios`
(the other `dashboard`-class screen) was left untouched — the DoD required replacing one, not both,
and its 3 client-side reports need `where`-filter support this move did not touch.

## Checklist re-run after Moves 4–7 (2026-07-30) — supersedes the table above for `crossdocking`

The block above was written at `ed94669` (Move 3) and was **four moves stale**: it still cites
REG-75 and C10's flag-sync half as open, both of which closed in Moves 4–5. Re-running the same
20-item `crossdocking` checklist from `docs/MOVE1_PANEL_GAPS.md` against the model at HEAD +
Moves 6–7's working tree:

```
crossdocking.html   12,748 B    15 works · 4 differs (cosmetic) · 1 n/a · 0 cannot-express
```

**Changed since the Move 3 record — two items:**

| Item | Was | Now | Closed by |
|---|---|---|---|
| **C10** — one action also writing two sibling records | `cannot-express` | **works** | Move 4 `patchConcept` + Move 5 Wave 1 `callProcedure` |
| **C14–C16** residual — "doesn't yet override `situacao` on click" | works, with residual | **works**, residual closed | Move 5 Wave 6 (`b5abd2d`) |

All three console write paths now carry the full three-record write, verified at the declaration:

```
ativar    → flow AtivarCrossDocking → createConcept + callProcedure(SetCrossDockingFlagsProcedure)
                                      → patch Recebimento.crossDockingAtivo = true
                                      → patch Expedicao.crossDockingAtivo   = true
concluir  → procedure ConcluirCrossDockingProcedure  (scope: row, visibleWhen situacao == 'Ativo')
                                      → patch CrossDocking.situacao = "Concluido"
                                      → patch Recebimento/Expedicao.crossDockingAtivo = false
cancelar  → procedure CancelarCrossDockingProcedure  (same shape, situacao = "Cancelado")
```

**Method, stated honestly:** this is a *declaration-level* re-run — it confirms the mechanisms are
declared and bound. The corresponding *live* evidence already exists and is not re-derived here:
Move 5 Wave 1 live-verified Ativar's flag-set, and Move 5 Wave 6 live-verified the
Concluir/Cancelar `situacao` transitions and flag-clears via REST + a real browser click. What was
missing was never the verification — it was this record being updated.

**Arithmetic note:** the Move 3 block reports "14 works, 5 differs, 1 cannot-express, 1 n/a" = 21
against a 20-item list. C13 was double-counted (it reads "**works**, differs in styling"). Counted
once each, the Move 3 state was 14 works · 4 differs · 1 cannot-express · 1 n/a = 20.

**The 4 cosmetic differences remain, and no shipped mechanism closes them:** C2 (generic `Loading…`
instead of a live "N cross-docking(s)" count), C8 (no client-side pre-submit validation — relies on
the flow's server-side check), C11 (failures surface in the status banner but invalid fields are
not preserved/highlighted inline), C18 (one banner line replaces the original's timestamped,
coloured log). C13's styling difference (`situacao` as plain text, not a coloured badge) also
stands. None is behavioural.

### Deletion verdict

**DONE — `crossdocking.html` was deleted 2026-07-30 (Move 8 Part A).** `crossdocking` had reached
behavioural parity — 0 `cannot-express` items — making it the first console eligible for deletion
under the metric's own rule. `crossdocking.original.html` (12,748 B) stays frozen alongside the
deletion, as the reference copy for closing the 4 remaining cosmetic items (C2, C8, C11, C18).

**What the deletion changed, concretely:**
- `definition/menu.json` — the `"Cross-Docking"` entry now points at the declared panel itself
  (`{ "kind": "BUSINESS", "target": "__panel-CrossDockingConsolePanel__" }`, the same section-hash
  convention `business-ui-app.mustache`'s `panelSectionRef`/`sectionId` already use for every
  declared Panel), not the deleted `.html` file.
- `definition/pages.json` — the `crossdocking.html` companion-page registration was removed outright.
- `ConcluirCrossDocking`/`CancelarCrossDocking` (the two incomplete-duplicate **flows** — see below)
  were deleted in the same change, since deleting the HTML left them with no caller.
- Live-verified end to end (menu → panel → Ativar → Concluir/Cancelar, both sibling flags checked at
  the database, not by eye): `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\move8-a5-crossdocking-live.txt`.

Both re-points landed on the declared panel's own route, `CrossDockingConsolePanel.route =
"/crossdocking"`. That route only became a genuinely navigable URL in **Move 5 Wave 6** (`52d27c4`) —
so the Tier-3 `panel.route` fix, filed as cleanup, is the specific earlier change that made this
first deletion of the engagement possible.

### Finding surfaced by the re-run: two paths, one incomplete — DELETED with the HTML (item N1)

`ConcluirCrossDocking` and `CancelarCrossDocking` existed as **flows** whose only step was
`updateConcept` — they set `situacao` and did **not** touch the sibling `crossDockingAtivo` flags.
They were not orphaned at the time: `crossdocking.html` called all three flow endpoints directly
(`flows/{Ativar,Concluir,Cancelar}CrossDocking/execute`) and performed the flag writes itself as
separate PUTs. So both paths were complete at the time — the original by hand, the panel by
procedure.

**Deleting `crossdocking.html` would have left those two flows with no caller**, becoming an
incomplete duplicate of the procedures: a future author binding a panel action to
`flow: "ConcluirCrossDocking"` would have silently lost the flag-sync, with no validation error.
Per this exact warning, both flows were deleted in the SAME change as the HTML (Move 8 Part A3) —
the deletion did not trade a dead file for a live trap. `AtivarCrossDocking` (a real, complete flow
using `createConcept` + `callProcedure`) and all three `*Procedure` procedures were left untouched.

## Methodology

`python <scratchpad>/helpers/classify-screens.py --apps-root D:/WorkSpace/NPDev/AppGen/apps --format md`
— walks every `web/*.html` under the apps root (also checked `NPDevSamples` separately: zero
hand-written `web/` folders found there), scores nine regex signal families per file (visualisation,
table, form, flow-invocation, wizard, editable-grid, auth, admin, report/export), classifies by a
fixed most-specific-first rule order, and cross-references each app's declared
`autoPanels`/`panels`/`aggregates`/`selectors`. Read-only; nothing in this repo or `AppGen/apps` was
written by the tool. One classification (`crossdocking`) was overridden by hand per the footnote
above; the tool's own design explicitly expects this ("no judgment — a human names the classes").
