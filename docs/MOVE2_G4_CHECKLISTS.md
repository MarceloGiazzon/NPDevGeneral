# Move 2 G4 — the 4 remaining operator consoles: results

> Frozen from each `*.original.html` before authoring its replacement panel(s), per
> `docs/MOVE2_PANEL_ACTIONS_PLAN.md` G4 ("keep each `*.original.html` until its replacement passes a
> behaviour diff"). Lighter-weight than Move 1's 20-item checklist (that one earned its granularity
> as the first-ever attempt; these four reuse everything Move 1+2 already proved and focus on what's
> actually new per screen). **All four originals are preserved, unmodified** — none has been deleted;
> none of the four new panels reaches full parity, so deletion isn't warranted yet (same rule Move 1
> followed for `crossdocking.html`).

## Result summary

| Screen | New panel(s) | Converts | Doesn't convert |
|---|---|---|---|
| `conferencia-fiscal.html` | `ConferenciaFiscalNfePanel`, `ConferenciaFiscalRomaneioPanel` | History (master-detail, editable items, row-scoped Cancelar) | Import wizard (parse → review → N+1-write confirm) |
| `movimentacao-livre.html` | `MovimentoLivrePanel` | Header + item list (1 level), add-item, row-scoped Confirmar | Origem/Destino position allocation (2nd nesting level), Sugerir suggestion flows |
| `centro-trabalho.html` | *(not authored — see below)* | — | Same two blockers as movimentacao-livre, plus a Record-type toggle and a Planning layer |
| `inventario.html` | `InventarioHistoricoPanel` | History (master-detail, row-scoped Confirmar) | 3 file-upload/CSV wizards (Gerar Template, Importar Contagem, Recebimento por Arquivo) |

**A real, additional platform bug was found and fixed while authoring this batch — REG-71**
(`scope: "row"` + `binding: "conceptMutation"` blanked every other required field to null; two
stacked causes, both fixed, both regression-tested with a real semantic-validation gateway, not a
noop one). See `ledger/items/REG-71.yml`.

## The two classes of "doesn't convert," found consistently across all four screens

**Class A — multi-step wizard with an intermediate review, then an N+1-write confirm.**
`conferencia-fiscal.html`'s Import (parse → edit → confirm) and all three of `inventario.html`'s
CSV flows share this shape: step 1 returns proposed data for the user to review/edit; step 2 commits
it as multiple separate writes (one header + a loop of item creates). Panel has no declared surface
for either half: `inputFields` (G3) collects flat scalars from text boxes, not a variable-length
array of structured rows for review; and a panel action invokes exactly one flow/procedure/
conceptMutation call, never a loop of creates. **Not a bug — genuinely new surface area**, distinct
from anything G1–G3 closed. Named as a residual in `docs/MOVE1_PANEL_GAPS.md`'s G2/G3 notes; now
confirmed by three independent screens, which is the roadmap's own threshold ("≥2 consoles") for
being worth a real design pass, if pursued.

**Class B — nesting deeper than 1 level.** `movimentacao-livre.html` and `centro-trabalho.html`
both drill Movimento → MovimentoItem → MovimentoItemPosicao (Origem/Destino allocation) — a second
nesting level. `PanelValidation.validatePanels` already enforces "nesting is limited to one level"
(pre-existing, deliberate — see the comment above `validatePanelRowOps`) — this is not a bug to fix,
it's Panel's documented boundary. The platform's own answer for this exact shape (multi-level
master-detail with reactive per-node editing) is the **Aggregate Workbench** primitive, not Panel —
consistent with how the codebase already treats the distinction elsewhere. `centro-trabalho.html`'s
own source comment independently corroborates this: it explicitly says it chose the hand-authored
client-side pattern over Panel because "Panel nesting is capped at 1 level." Also blocked, same
class: the `Sugerir*` suggestion flows, whose input is a *computed* array built from cached
reference data (candidate locations/lots) — not a field a user types, and not a dataSource's own
rows either.

## conferencia-fiscal.html (22,897 B) — frozen 2026-07-29

Two independent modes selected by a `#tipoSel` toggle (Nfe / Romaneio) — effectively two consoles
sharing one page. Each mode has an **Import** wizard (parse → review/edit → confirm) and a
**History** master-detail list (documents → items).

| # | Behaviour | Source | Result |
|---|---|---|---|
| F1 | Reference data (entidades, produtos) loads on open, used to populate selects | `loadReferenceData` | n/a — not needed; Panel's own `entidades`/`produtos` concepts are separate nav entries, not selects inside this panel |
| F2 | History loads per selected `tipo`; switching `tipo` reloads different concepts entirely | `loadHistory`, `reloadAll` | **differs** — two separate panels (Nfe/Romaneio) instead of one toggle; no "swap dataSource set by a UI control" mechanism exists, so this was the natural declarative shape |
| F3 | Import step 1 (parse) | `parseNfe`/`parseRomaneio` | **cannot-express** — Class A |
| F4 | Parse result review/edit | `renderParseResult` | **cannot-express** — Class A |
| F5 | Confirm disabled until resolved | `renderParseResult` | **cannot-express** — Class A |
| F6 | Import step 2 (confirm, N+1 writes) | `confirmImport` | **cannot-express** — Class A |
| F7 | History master-detail, editable items, per-row Save | `renderHistory`, `renderItemRow` | **works** — verified live (nested `documentos`→`itens`, `quantidade`/`valorUnitarioCentavos` editable) |
| F8 | Cancelar visible only while active | `renderHistory` | **works** — verified live (`visibleWhen: situacao == 'Importada'`/`'Importado'`) |
| F9 | Cancelar raw concept update | `cancelDocument` | **works** — verified live, `status: OK`, real invariants evaluated, REG-71 fix confirmed |

## movimentacao-livre.html (23,392 B) — frozen 2026-07-29

`MovimentoLivrePanel`: `movimentos` (concept `Movimento`) + `itens` (child, concept `MovimentoItem`,
`rowOps: [add]`). Verified live: panel loads with real data; `+ Add row` created a real
`MovimentoItem` under the right parent (`createRow`'s existing parent-FK injection); `confirmarMovimentacao`
(`scope: row`, `visibleWhen: tipo == 'MovtoLivre' && situacao == 'Pendente'`) executed the real
`ConfirmarMovimentacao` flow against a freshly-created `MovtoLivre` movimento, `status: OK`.
**Not attempted:** Origem/Destino position allocation (Class B), `Sugerir` flows (Class B).

**New, minor, cosmetic observation found live:** a child dataSource (`itens`) renders correctly
nested under each parent row, but ALSO renders a second time as its own flat, unnested section at
the bottom of the panel (all items across all parents, no grouping) — `entry.data`'s top-level keys
include every dataSource, nested or not, and the generic renderer iterates all of them. Not blocking
(the correct nested view is also present and correct); noted for a possible future cleanup, not
attempted here.

## centro-trabalho.html (30,862 B) — evaluated by reading, not authored

Read in full rather than authored+built, a deliberate scoping call given the size of this session:
it is a structural superset of `movimentacao-livre.html` (same author, same code comment naming it
as the reused pattern) — Record-type toggle (Recebimento/Expedicao, same "no conditional dataSource
by UI toggle" shape as conferencia-fiscal's `tipo`) → Movimento → MovimentoItem → MovimentoItemPosicao
(the identical Class B 2-level nesting) → the same `Sugerir` flows, plus a "Planning layer"
(RecebimentoLote/ExpedicaoItem, a simple addable list — the one part of this screen that likely
*could* convert, unattempted). No new mechanism beyond what `movimentacao-livre.html` already
exercised with a real build+live-verify; re-running the same proof a third time would not have
added evidence. If this screen is prioritized later, its Planning-layer list is the part worth
authoring first.

## inventario.html (28,031 B) — frozen 2026-07-29

Three sequential file/CSV wizards (Gerar Template → Importar Contagem; a separate Recebimento por
Arquivo upload+preview+confirm) — all Class A, none attempted — plus a read/confirm **Historico**
section, which is a clean 1-level master-detail (`InventarioArquivo` → `InventarioArquivoLinha`).
`InventarioHistoricoPanel` authored for the Historico half only. Verified live: nested `inventarios`
→ `linhas` (8 real lines under a real historical `InventarioArquivo`); `confirmar` (`scope: row`,
`visibleWhen: situacao == 'Conferido'`) executed against a freshly-seeded `Conferido` record,
`status: OK`, real lifecycle transition `Conferido → Confirmado`, REG-71 fix confirmed here too.
