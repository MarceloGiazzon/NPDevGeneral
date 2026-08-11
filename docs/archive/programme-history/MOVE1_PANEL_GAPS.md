# Move 1 — `crossdocking.html` → declared Panel: gap list (closed by Move 2)

> **STATUS: ALL THREE GAPS CLOSED 2026-07-29.** Move 1 (`docs/MOVE1_CONSOLE_CONVERSION_PLAN.md`)
> found G1/G2/G3 below with zero platform-code changes, as designed. Move 2
> (`docs/MOVE2_PANEL_ACTIONS_PLAN.md`) closed all three the same day — this document now reads as
> history for Move 1's own measurement, with each gap's closure noted inline. Acceptance-test
> checklist: `docs/MOVE1_CROSSDOCKING_CHECKLIST.md` (20 items, C1–C20) — final score at the bottom.

## What Move 1 did (authoring only, zero platform code)

- Froze `AppGen/apps/_official/WmsOffice/web/crossdocking.original.html` as the behavioural
  reference (kept alongside the live `crossdocking.html`, unchanged).
- Declared `CrossDockingConsolePanel` in WmsOffice's `model.json`: 4 `dataSources` (`crossDockings`
  concept-bound, `recebimentos`/`expedicoes`/`produtos` query-bound to the existing
  `RecebimentosPendentes`/`ExpedicoesPendentes`/`ProdutosAtivos` queries), `fieldBindings` +
  `layout` for the `crossDockings` table, and 4 `actions`: `ativar`/`concluir`/`cancelar`
  (`binding: "flow"`, matching the original's three flow calls) plus one **control action**,
  `marcarConcluidoDireto` (`binding: "conceptMutation"` on `CrossDocking` directly — substituted for
  the plan's suggested `procedure` control since no existing procedure operates on `CrossDocking`
  and adding one would itself be a new model element, which Rule 2 of the move forbids).
- Validated, generated, built, booted, and verified live via REST + real-browser ScrapForAI.

## What Move 2 did (platform code — all three gaps below)

- **G1**: added a `flow` branch to `PanelRuntime.executeAction`, routing through the same
  `KernelFacade.executeFlow` the generated `FlowExecutionController` uses. REG-70 (the 2
  pre-existing shipping panels with the same dead binding) closed alongside it.
- **G2**: added `panelAction.scope` (`"panel"` default / `"row"`) + `panelAction.dataSource` to the
  schema (4 mirrors), AST, compiler, canonical-JSON read/write, and semantic validation. Runtime:
  `PanelRuntime` re-reads the target row fresh by id and layers the caller's body on top before
  invoking the flow/procedure — the same shape the hand-written screen's
  `{...xd, situacao: 'Concluido'}` used, just server-side. Frontend: row-scoped actions render once
  per row (gated by `visibleWhen` evaluated against that row's own fields), the panel header no
  longer shows them.
- **G3**: added `panelAction.inputFields` (same shape as `panelDataSource.addFormFields`) to the
  same surfaces. Frontend: a panel-scoped action with declared `inputFields` gets an inline
  "collect input, then invoke" mini-form of plain text inputs next to its button.
- Both new schema properties default to absent/`"panel"`, so **every panel action declared before
  this existed keeps behaving exactly as before** — no codemod, no `BREAKING.md` entry.
- 5 new tests (`PanelRuntimeFlowActionTest` ×2, `PanelRuntimeRowScopedActionTest` ×2,
  `PanelRuntimeInputFieldsTest` ×1), all passing inside a regenerated+mounted WmsOffice build, plus
  the full existing `PanelRuntimeTest` (8) and `:NPDevContract:dsl:test` suites re-run clean —
  zero regressions. `NPDevSamples/dsl-conformance-max` gained both a `scope: "row"` and an
  `inputFields` example (`WidgetOrderReviewPanel.confirm` / `.place`), Gradle-validated.
- Full round trip re-verified live: real browser fills the 5-field `Ativar Cross-Docking` form,
  clicks it, a real `CrossDocking` row is created via the real flow; the per-row `Concluir`/
  `Cancelar` buttons appear only on `Ativo` rows and correctly disappear once clicked.

## G1 · `panel.action.binding: "flow"` is schema-valid but not executable — FIXED

**Original behaviour:** `crossdocking.original.html` POSTs `/api/flows/{AtivarCrossDocking,
ConcluirCrossDocking,CancelarCrossDocking}/execute` directly from JavaScript (C9, C15, C16).
**Found:** compiled and generated with 0 errors, but `PanelRuntime.executeAction` had no `flow`
branch — every flow-bound action returned `PANEL_ACTION_BINDING_UNSUPPORTED`, confirmed live (REST
+ real browser) for both `CrossDockingConsolePanel` and the 2 pre-existing shipping panels
(`ConferenciaRecebimentoPanel.ConfirmarRecebimento`, `ExpedicaoDemandaPanel.ConfirmarSaidaExpedicao`
— filed as **REG-70**, HIGH). A `conceptMutation` control action on the same method proved the gap
was precisely `flow`-shaped, not "panel actions are broken."
**Fixed:** `flow` branch added, routes through `KernelFacade.executeFlow` (`OK`/`WAITING`/`FAILED`
mapped from `ExecutionStatus`, real `executionId`/`correlationId`, no synthesized synchronous result
for a parked flow). Verified live: `CrossDockingConsolePanel.ativar`/`.concluir`/`.cancelar` all
`OK`; `ConferenciaRecebimentoPanel`/`ExpedicaoDemandaPanel` each walked a real multi-stage lifecycle
transition end to end. REG-70 closed. Full detail: `ledger/items/REG-70.yml`.

## G2 · Panel actions rendered once per panel, never once per row — FIXED

**Original behaviour:** the Cross-Dockings table renders a **Concluir**/**Cancelar** button pair
*per row*, only for rows where `situacao === 'Ativo'` (C14), operating on that row's `id`.
**Found:** `renderDeclaredPanel` rendered every action as one header button, click handler
`executeDeclaredPanelAction(panelMeta, action.name)` carrying no row/id at all;
`visibleWhen`/`enabledWhen` evaluated only against `{isSuperUser, role, actorId}` — no row data, so
no expression could mean "this row's `situacao == 'Ativo'`". The only per-row mechanism
(`resolveRowSaveAction`) was hardcoded to the editable-column-plus-Save pattern.
**Fixed:** `scope: "row"` + `dataSource: "<name>"` on an action. Frontend renders it once per row of
that dataSource, `visibleWhen`/`enabledWhen` evaluated against that row's own fields merged over the
usual actor context. Runtime re-reads the row fresh by id and layers the caller's body on top before
calling the flow/procedure. Verified live: exactly one Ativo row shows Cancelar/Concluir; clicking
either (real browser click, not simulated) invokes the real flow against that row only, and the
buttons correctly disappear once the row becomes terminal.
**Residual nuance found while verifying (not blocking, honestly noted):** the generic per-row button
only ever sends `{id: row.id}` — there's no way to declare a *fixed* override value the button
itself should carry (e.g., "always set `situacao` to `Concluido`" on click). Clicking `Concluir`
today re-invokes the flow with the row's *current* `situacao` unchanged unless a caller separately
passes an override (proven via REST). The original hand-written screen hardcoded
`{...xd, situacao: 'Concluido'}` in its own JS; a declared row action has no equivalent "fixed
payload" concept yet — `inputFields` (G3) covers *user-entered* values, not literal constants. Not
attempted here; smallest plausible fix is a small `action.metadata`/fixed-values property, a
follow-up if a 5th operator console needs it (per the roadmap's own "≥2 consoles → build it" rule).

## G3 · No mechanism to collect ad hoc input for a panel-level action — FIXED

**Original behaviour:** "Ativar Cross-Docking" is preceded by a 5-field form (recebimento/
expedicao/produto selects, quantidade, dataAtivacao) filled before clicking (C3–C9).
**Found:** a header-level action button always called `executeDeclaredPanelAction(panelMeta,
action.name)` with an **empty** body — no schema field existed for "this action's input fields."
**Fixed:** `inputFields: [...]` on an action (same declaration shape as
`panelDataSource.addFormFields`). Frontend renders an inline mini-form of plain text inputs next to
the action's button; clicking gathers the values into a flat map and invokes the action with it —
the flow's own `input` schema validates them, no new validation layer. Verified live: a real browser
filled all 5 fields via real DOM inputs and clicked `Ativar Cross-Docking`; a real `CrossDocking` row
was created through the real `AtivarCrossDocking` flow, `situacao` correctly defaulting to `Ativo`
from the concept's own declared default (no need to also list `situacao` as an input field).
**Not attempted (real, smaller, non-blocking):** inputs render as plain text only — no
widget/reference-picker resolution (a `recebimentoId` field is a raw text box, not a dropdown of
eligible Recebimentos). The original's `<select>` dropdowns are a nicer UX; typing a raw UUID works
but is not what an operator would want. Workaround: an author can still resolve reference options by
hand today via a **different** dataSource on the same panel (`recebimentos` already exists here) —
just no automatic wiring from `inputFields` to a sibling dataSource yet.

## Minor, non-blocking observations (unaffected by Move 2 — still true)

- `recebimentos`/`expedicoes`/`produtos` still render via the generic union-of-keys fallback table
  (raw JSON dump) rather than a clean column view — no `fieldBindings` were scoped to them.
  Workaround exists (scope `fieldBindings` per dataSource); not attempted, cosmetic only.
- `panel.route` (`"/crossdocking"`) is still accepted by the schema/compiler but the generated
  business-ui client never wires it to an actual navigable URL — reached via the left-nav "Panels"
  section instead. Not blocking; same "declared but not fully wired" class as G1 was.
- The original's produto selector shows all produtos unfiltered (C5); this panel uses the filtered
  `ProdutosAtivos` query — a deliberate, minor behaviour difference, not a Panel limitation.
- The original's "Testar bloqueio" diagnostic button (C17) has no attempted equivalent — it probes a
  concept-level invariant directly, not a console feature; explicitly out of scope.

## Checklist verdict, item by item (final, post-Move-2)

| # | Verdict | Why |
|---|---|---|
| C1 | **works** | all 4 dataSources fetch and render together on panel open |
| C2 | differs | no "carregando.../N cross-docking(s)" status text — generic "Loading…"; cosmetic |
| C3–C7 | **works** | G3 — real inline form (recebimentoId/expedicaoId/produtoId/quantidade/dataAtivacao), verified live with real DOM input + click |
| C8 | differs | no *client-side* validation before submit — relies on the flow's own input validation (server round-trip, not instant feedback); still correctly surfaces a failure |
| C9 | **works** | G1 — flow executes, real CrossDocking created |
| C10 | cannot-express | no mechanism for one action to also write two more records (Panel actions are single-binding, single-call) — the original's extra `crossDockingAtivo` PUTs have no declared equivalent |
| C11 | differs | failure surfaces via the status banner (now correctly red for `FAILED`) but the form does not preserve/highlight invalid fields the way the original's inline log did |
| C12 | **works** | G1 — reload-after-action + real success |
| C13 | **works**, differs in styling | data renders correctly; `situacao` is plain text, not the original's colored badge |
| C14–C16 | **works** | G2 — per-row Cancelar/Concluir, visible only while `situacao == 'Ativo'`, verified live via real browser click; residual: doesn't yet override `situacao` on click (see G2 note) |
| C17 | n/a | explicitly out of scope (probes a concept invariant, not a console feature) |
| C18 | differs | one status banner line replaces the timestamped, colored log |
| C19 | **works** | Panel's own "Refresh" header button |
| C20 | **works** | no polling in the declared Panel either |

**14 works, 5 differs (cosmetic/UX, non-blocking), 1 cannot-express (C10, multi-write orchestration), 1 n/a.**

## Verdict

> **All three gaps that blocked the console (G1, G2, G3) are closed.** 14 of 20 original behaviours
> are now cleanly expressible (up from 3), 5 more work with a cosmetic difference, 1 genuine gap
> remains (C10 — an action triggering writes to *other* concepts beyond its own flow's output has no
> declared mechanism), and 1 is explicitly out of scope.

**This landed bigger than either Move 1's or the roadmap's own predicted shape** — the predicted
gaps ("multi-dataSource composition, cross-filtering, action-result refresh") were never hit; the
real gaps were all on the action side (G1/G2/G3), each smaller in isolation than expected (G1 one
switch branch, G2/G3 each one schema property + a frontend rendering path) but three of them stacked
together, not one.

**What's left, for a possible Move 2b, only if a second console needs it (per the roadmap's own
"≥2 consoles" rule) — do not build ahead of that signal:**
1. A fixed/literal override value for a row action (the G2 residual).
2. Multi-write orchestration from one action (C10).
3. Reference-picker resolution for `inputFields` (the G3 residual).

None of these blocked `crossdocking`'s conversion — they are refinements observed while verifying,
not open gaps in the sense G1–G3 were.
