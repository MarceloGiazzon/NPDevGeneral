# Move 3 G2 — Class B (`movimentacao-livre.html`, `centro-trabalho.html`): results

> Per `docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md` G2: both consoles hand-roll
> **Movimento -> MovimentoItem -> MovimentoItemPosicao** (Origem/Destino allocation) because Panel's
> nesting is capped at 1 level (`centro-trabalho.html`'s own source comment says so explicitly).
> G2's job was to build the depth-2 `Movimento` aggregate + `MovimentoWorkbench` and work each
> console's behaviours to a verdict, same method as `docs/MOVE2_G4_CHECKLISTS.md`. **Both originals
> are preserved, unmodified** — neither reaches full parity, so deletion isn't warranted (same rule
> Move 1 and Move 2 G4 followed).

## Result summary

| Behaviour class | Verdict |
|---|---|
| Depth-2 load/commit (Movimento -> itens -> posicoes) | **works** — verified live (REST + real browser), transactional (G1/REG-72) |
| Add/edit/delete at any nesting level | **works**, generically — Workbench's native add-row/edit-cell/delete-row at header, section, and band level |
| Confirmar Movimentacao (situacao -> Concluido) | **differs** — Workbench's generic lifecycle-transition button does a raw field write, not the `ConfirmarMovimentacao` flow; misses the flow's `MovimentoConfirmado` event emission |
| Origem/Destino balance validation before confirm | **cannot-express either way** — was already client-side-only in the original screen (not server-enforced), and the Workbench has no equivalent guard at all |
| Origem/Destino as two visually distinct sides | **differs** — Workbench shows one merged `posicoes` band per item; Origem vs. Destino is a data value (`papel`), not a UI split |
| syncOcupacao (position write also updates the `LocalArmazenagemLote` stock ledger) | **cannot-express** — genuine gap, named below |
| Sugerir Destino / Sugerir Origem suggestion | **works** — REG-73 (real platform bug) found + fixed; wired as a real clickable Workbench action; suggestion not yet auto-applied to a new position row (named below) |
| Record-type toggle (centro-trabalho only: Recebimento/Expedicao -> Origem-only/Destino-only) | **cannot-express** — same "no conditional surface by UI toggle" shape as `conferencia-fiscal`'s `tipo` (Move 2 G4 finding), unchanged by the aggregate primitive |
| Planning layer (centro-trabalho only: RecebimentoLote/ExpedicaoItem addable list) | **not attempted** — a simple 1-level addable list, independent of the Class B blocker; unattempted here, same as Move 2 G4's assessment |

## movimentacao-livre.html (23,392 B) — Move 3 G2 re-evaluation, 2026-07-29

Move 2 G4 authored `MovimentoLivrePanel` for the 1-level parts (header + itens, `confirmarMovimentacao`
row action) and named Origem/Destino allocation + Sugerir as Class B blockers. G2 built the real
depth-2 `Movimento` aggregate + `MovimentoWorkbench` specifically to test whether those blockers are
answered by the platform's own multi-level primitive. Verdicts below are against the ORIGINAL
hand-written screen's behaviours (`web/movimentacao-livre.html`), not against `MovimentoLivrePanel`.

| # | Behaviour | Source | Result |
|---|---|---|---|
| M1 | Reference data (entidades/produtos/lotes/locais) resolves FK ids to human labels in selects | `loadReferenceData`, `produtoNome`, `loteLabel`, `localLabel` | **differs** — Workbench renders raw field values (a bare uuid for every FK column); no declarative id-to-label resolution mechanism exists for Workbench grid/band cells |
| M2 | Pending Movimento Livre dropdown, filtered `tipo == 'MovtoLivre' && situacao == 'Pendente'` | `loadPendentes` | **differs, equivalent** — the auto-generated `MovimentoSelection` panel (filters: `tipo`, `situacao`) is a separate list page (`MovimentoWorkbench.html` with no `id`), not an inline dropdown on the same page; same filtering power |
| M3 | Create new Movimento Livre header | `criarMovimento` | **works** — verified live: `POST /api/runtime/aggregate/Movimento` with no `id` creates a new root (`a64de5b4-363f-408f-afb7-689c1a4dd905`, `situacao: Pendente`) |
| M4 | Select movimento, load header + itens + posicoes together | `selectMovimento`, `loadItensEPosicoes` | **works** — verified live: REST depth-2 load, and real browser (`itens -> bands (row ...) -> posicoes` renders with real nested data, confirmed by screenshot after the domTextSnapshot's table-cell text extraction proved unreliable for dense tables — same known artifact as the G1 depth-2 proof) |
| M5 | Add item (produto/lote + quantidade) | `renderAddItemForm` | **works** — Workbench's native `+ add row` under the `itens` section covers this generically (any field, not just produto/lote/quantidade) |
| M6 | Balanced banner (Origem total == Destino total == item quantidade) | `renderItemBlock` | **cannot-express** — no computed/derived-display mechanism in the generic Workbench; would need a `recompute` procedure (the P3/C7 seam exists for cell-edit-triggered recompute, but wiring a *display-only* computed banner is a different shape, unattempted) |
| M7 | Origem/Destino as two side-by-side panels, each its own add-position form | `renderSide`, `renderAddPosicaoRow` | **differs** — Workbench renders ONE merged `posicoes` band per selected item row; Origem vs. Destino is the `papel` column's value, editable like any other cell, not a UI-level split |
| M8 | Edit position quantidade inline, save syncs the `LocalArmazenagemLote` stock ledger (`syncOcupacao`) | `renderPosicaoRow`, `syncOcupacao` | **works** — closed by Move 8 (2026-07-31), item G1: `Movimento` now declares `"onCommit": "RecomputarOcupacaoOnCommitProcedure"`, which runs inside the same commit transaction (REG-72) and does an **absolute recompute** (sum of all persisted `MovimentoItemPosicao` rows for the touched `(localArmazenagemId, loteId)` pair, not a delta) before writing `LocalArmazenagemLote.quantidade`. Editing a position in the Workbench and saving now updates the ledger with **no button press** — verified live via direct `POST /api/runtime/aggregate/Movimento` commits (idempotence proven: re-committing the identical tree twice left `quantidade` unchanged, not doubled) and a rollback proof (a deliberately-thrown capability error rolled back the ENTIRE commit — root, items, positions, and the ledger write all stayed unchanged; see `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\move8-b4-oncommit-rollback.txt`). The gap named below (was: "no declarative mechanism for cross-aggregate side effects on write") is closed; `onCommit` is that mechanism. |
| M9 | Add position (local + quantidade), syncs the ledger the same way | `renderAddPosicaoRow`, `syncOcupacao` | **works** — same fix as M8 (the row itself already added fine via `+ add row`; the ledger side effect now also fires automatically on commit, same `onCommit` hook, same live proof) |
| M10 | Sugerir Destino / Sugerir Origem — call the `alocacao` capability with a computed candidate array, autofill the add-position form | `sugerir` | **works, with a real bug found and fixed along the way (REG-73)**: `ProcedureRunner` never resolved a capability adapter from the model's `bindings` list, so EVERY procedure-side `capabilityCall` (not just these two) failed `CAPABILITY_BINDING_MISSING` even with a correct binding declared — a genuine, previously-undetected platform bug, unrelated to anything Move 2/3 added. Fixed, RED->GREEN proven in a real generated app build (temporarily reverted the fix, got a real `AssertionFailedError`, restored it, GREEN; 25/25 regression tests pass), and verified live twice: (1) direct REST calls to `POST .../invoke/SugerirDestinoProcedure` and `.../SugerirOrigemProcedure` both return correct results (`sucesso:true`, real ranking/FIFO output); (2) wired as real clickable Workbench buttons via the pre-existing but previously-unused `autoPanels[].transaction.metadata.actions` seam (`workbenchActions` in `AutoPanelExpander`, "the P6 seam before a first-class actions authoring slot") and confirmed live in a real browser — the buttons render, and clicking one runs the real suggestion end-to-end. **Residual, honestly named, not fixed here**: the suggestion result (`resultado.localArmazenagemId`, etc.) is folded into the draft as inert extra keys — `AggregateRuntime.invoke()` returns the procedure's full accumulated state (confirmed by reading `DefaultProcedureExecutor.execute` + `AggregateRuntime.invoke`), so the draft's real fields are NOT lost or corrupted (verified live: header/itens intact after clicking, confirmed by screenshot after the domTextSnapshot text-walker again produced a misleadingly blank read of the same dense table — caught before being reported as a false "draft destroyed" bug) — but nothing in the generic Workbench renderer knows to apply `resultado.localArmazenagemId` into a new `posicoes` row the way the original screen's inline autofill did. The mechanism works; the last-mile UX wiring does not exist yet. |
| M11 | Confirmar Movimentacao: client-side balance guard, then invoke the `ConfirmarMovimentacao` FLOW (which does `updateConcept` + `emitEvent(MovimentoConfirmado)`) | `confirmarMovimentacao` | **differs** — the Workbench's generic lifecycle-transition button (`-> Concluido`) does `store.editHeader(statusField, target); commitDraft(...)`, i.e. a raw field write via `AggregateRuntime.commit()`. It does NOT invoke the `ConfirmarMovimentacao` flow, so it (a) never emits `MovimentoConfirmado`, and (b) — same as the original screen — has no server-side balance check either way, since that flow never enforced the balance rule server-side to begin with (confirmed by reading `ConfirmarMovimentacao`'s 3 steps: `updateConcept`, `emitEvent`, `return` — no validation step). The generic transition mechanism is not wrong, but it is a materially different code path from what the original screen calls, and the event-emission difference is real. |

## centro-trabalho.html (30,862 B) — read, not re-authored, 2026-07-29

Move 2 G4 read this file in full (not authored) as "a structural superset of `movimentacao-livre.html`,
same author, same code comment naming it as the reused pattern," and deferred re-verification since
"re-running the same proof a third time would not have added evidence." That holds here too: every
Class B behaviour centro-trabalho shares with movimentacao-livre (Origem/Destino 2-level nesting,
Sugerir, syncOcupacao) has the identical verdict as M6-M11 above, verified once against the same
`Movimento` aggregate (both screens read/write the same `Movimento`/`MovimentoItem`/
`MovimentoItemPosicao` concepts). Two things unique to this screen, assessed by reading:

| # | Behaviour | Result |
|---|---|---|
| C1 | Record-type toggle (Recebimento -> Destino-only positions / Expedicao -> Origem-only positions) | **cannot-express** — the same "conditional dataSource/behaviour selected by a UI toggle" shape Move 2 G4 already named for `conferencia-fiscal.html`'s `tipo` selector; the aggregate primitive doesn't change this, since it's a client-side branch over which `papel` values are offered, not a data-shape question |
| C2 | Planning layer (RecebimentoLote / ExpedicaoItem, a simple addable list feeding the movement) | **not attempted** — a plain 1-level addable list, independent of every Class B blocker above; Move 2 G4 already identified this as "the part worth authoring first" if this screen is prioritized later. Still true, still not started. |

## Deletion eligibility (§6 metric)

Per the plan's own rule ("If and only if a console reaches parity, its `.original.html` is
deleted"): **neither console reaches parity.** Both still have a real, named `cannot-express` gap
(M6 for movimentacao-livre; the same plus C1 for centro-trabalho) — M6's computed balance banner
has no display-only-recompute mechanism in the generic Workbench, and C1's UI-toggle-selected
record type has no declarative home either, same shape Move 2 G4 already named for
`conferencia-fiscal.html`. (M8/M9's stock-ledger side effect, formerly listed here too, was closed
by Move 8 — see M8/M9 above — and no longer blocks parity for either screen.) **0 of the
54,254 B eligible (`movimentacao-livre.html` 23,392 B + `centro-trabalho.html` 30,862 B) is deleted.**
Both originals remain, unmodified, exactly as Move 1 and Move 2 G4 left every other unconverted
screen.

## What G2 actually closed, net of the above

- The depth-2 aggregate primitive itself: **proven sound** at both REST and real-browser level,
  transactionally safe (G1), on a brand-new corpus model (not just a fixture).
- The Sugerir hypothesis ("invoke() is the candidate mechanism, test it, name a gap if it doesn't
  fit"): **held**, but only after fixing a real, previously-undetected platform bug (REG-73) that
  had nothing to do with aggregates specifically — it broke every procedure-side capability call in
  the platform. Finding and fixing it was more valuable than the plan's own hypothesis test asked
  for.
- One real, honestly-named residual gap that was NOT fixed here, because it is its own design
  problem, not a small extension of what G2 already built:
  1. **Invoke-result-to-draft field mapping** (M10's residual) — `invoke()`'s current contract
     (return the full patched state) is right for the P6 "recompute over the SAME shape" use case
     it was designed for, but wrong for "run an unrelated capability and apply ONE field of its
     result somewhere in the draft" — a materially different, unaddressed use case.
  (A second gap used to be listed here — **cross-aggregate side effects on write**, M8/M9's
  `syncOcupacao` — but it was closed by Move 8, 2026-07-31, item G1: `aggregate.onCommit` is now a
  real, declarative, transactional mechanism for exactly this. See M8/M9 above.)
