# Move 10 Wave 1 — the two "authoring-only" consoles: results

> Per `<scratchpad>/MOVE10_CONSOLE_PARITY_SPEC.md` W1.1–W1.3. Re-runs the frozen checklists in
> `docs/MOVE2_G4_CHECKLISTS.md` for `conferencia-fiscal.html` and `inventario.html` against the model
> as authored this move, and records a verdict for every behaviour — `works` / `differs` /
> `cannot-express` — with no silent skips.
>
> **Outcome: `conferencia-fiscal.html` reached behavioural parity and was DELETED (22,897 B).
> `inventario.html` did NOT and was kept**, with its one remaining wizard's blockers named precisely
> and filed (REG-92 plus two named mechanism gaps).

## The spec's premise, tested

Move 9 D1 recorded both consoles as having "no platform blocker left — authoring gap only". That
held for **three of the four** unattempted items and was **wrong for the fourth**:

| Item | Premise | Reality |
|---|---|---|
| `conferencia-fiscal` Romaneio Import | authoring gap only | **correct** — a direct mirror of `ParseNfeProcedure`, no new mechanism |
| `inventario` Gerar Template | authoring gap only | **correct**, but it was also **already declared and incomplete** — see below |
| `inventario` Recebimento por Arquivo | authoring gap only | **wrong** — three independent mechanism gaps, one of them a live silent-corruption bug (REG-92) |

Two platform bugs had to be fixed before the authoring could even be expressed or verified
(REG-89, REG-93); two more were found and filed open (REG-91, REG-92); one build-tooling bug was
fixed (REG-90).

---

## `conferencia-fiscal.html` (22,897 B) — DELETED 2026-07-31

The frozen 9-item checklist from `docs/MOVE2_G4_CHECKLISTS.md`, re-run:

| # | Behaviour | Move 2 G4 | Now | Evidence |
|---|---|---|---|---|
| F1 | Reference data loads on open, populates selects | n/a | **n/a** | Unchanged — `Entidade`/`Produto` are their own nav entries, not selects inside this surface |
| F2 | History loads per selected `tipo`; switching reloads different concepts | differs | **differs** | Four menu entries (NF-e/Romaneio × Importar/Historico) instead of one in-page toggle. Still no "swap dataSource set by a UI control" mechanism |
| F3 | Import step 1 (parse) | cannot-express | **works** | NF-e: Move 3 G3. Romaneio: this move — REST, DB and real browser |
| F4 | Parse result review/edit | cannot-express | **works** | The draft's `itens` band is editable; screenshot shows 2 parsed rows with real `produtoId`/`quantidade` |
| F5 | Confirm disabled until every item resolved | cannot-express | **differs** | The Workbench's Save is always enabled; an unresolved item is refused at commit instead. Verified: committing an item with `produtoId: null` returned HTTP 500 and **nothing persisted — not even the header** |
| F6 | Import step 2 (confirm, N+1 writes) | cannot-express | **works** | One atomic aggregate commit replacing the original's two unguarded sequential POSTs. DB-verified: 1 `romaneios` row + 3 `romaneio_items` rows |
| F7 | History master-detail, editable items, per-row Save | works | **works** | Move 2 G4, unchanged |
| F8 | Cancelar visible only while active | works | **works** | Move 2 G4, unchanged |
| F9 | Cancelar raw concept update | works | **works** | Move 2 G4, unchanged |

Romaneio-specific behaviours the 9-item list does not name individually, checked against
`conferencia-fiscal.original.html` line by line:

| # | Behaviour | Source | Verdict |
|---|---|---|---|
| R1 | Items auto-matched to a `Produto` **by name** | `produtosConhecidos()` | **works** — `ParseRomaneioProcedure` maps `codigo: $p.nome` (deliberately NOT `$p.codigo` like the NF-e twin; several seeded `Produto` rows have a null `codigo`, and the screen's own placeholder says "casado por nome de Produto") |
| R2 | Duplicate romaneio number rejected before import | `numerosJaImportados` | **works** — re-parsing an already-imported number returns `parseError: "Romaneio ja importado anteriormente (numero duplicado): ROM-M10-001"` and leaves the draft untouched |
| R3 | `dataEmissao` defaults to today | `new Date().toISOString().slice(0,10)` | **works** — computed server-side in the capability instead of in the browser (no procedure expression yields "today"). Same value, different tier |
| R4 | Unmatched item flagged, product chosen manually | `renderParseResult` | **works** — unmatched items come back `produtoId: null, registrado: false` and the band's `produtoId` cell is editable |
| R5 | "Todos os itens tem produto registrado" banner | `renderParseResult` | **differs** — `todosRegistrados` is returned but rendered as no banner. Cosmetic, same class as crossdocking's accepted C2 |

**Totals: 10 works · 3 differs · 1 n/a · 0 cannot-express.**

### Deletion verdict — DONE

0 `cannot-express` items, so `conferencia-fiscal.html` (22,897 B) is eligible under the metric's own
rule and was deleted. `conferencia-fiscal.original.html` (22,897 B) stays frozen as the reference for
the 3 remaining cosmetic items.

What the deletion changed:

- `definition/menu.json` — the flat `{"kind": "PAGE", "target": "conferencia-fiscal.html"}` entry
  became a four-child submenu, each child using the existing
  `{"kind": "BUSINESS", "target": "__panel-<PanelName>__"}` convention:
  `DocumentoFiscalAggregateWorkbench`, `ConferenciaFiscalNfePanel`, `RomaneioAggregateWorkbench`,
  `ConferenciaFiscalRomaneioPanel`. (Aggregate workbenches appear in the generated UI manifest as
  declared panels named `<Aggregate>Workbench`, so the same convention covers them — no new
  convention was invented.)
- `definition/pages.json` — the `conferencia-fiscal.html` companion-page registration was removed.
- **The `ImportarNfe` and `ImportarRomaneio` flows were deleted in the same change.** Both were
  one-step `capabilityCall` wrappers whose only caller was this HTML. Left behind they would have
  become exactly the trap `docs/SCREEN_TAXONOMY.md` names for crossdocking: a future author binding
  an action to `flow: "ImportarNfe"` would silently get no product auto-match and no duplicate-key
  rejection (those live in the capability's 3-arg overloads, which only the procedures call), with
  no validation error. `ParseNfeProcedure`/`ParseRomaneioProcedure` and both capability classes are
  untouched.
- `conferencia-fiscal.panel.json` is **kept** — it is the provenance record of what the frozen
  original touched. The impact gate now correctly treats a manifest whose screen is gone as
  *retired* rather than failing on it (REG-93).

---

## `inventario.html` (28,031 B) — NOT deleted

| # | Behaviour | Move 2 G4 | Now | Evidence |
|---|---|---|---|---|
| I1 | Historico master-detail (`InventarioArquivo` → `InventarioArquivoLinha`) | works | **works** | Move 2 G4, unchanged |
| I2 | Row-scoped Confirmar, gated on `situacao == 'Conferido'` | works | **works** | Move 2 G4, unchanged |
| I3 | Wizard 2 — Importar Contagem (paste counts → review → commit) | works | **works** | Move 3 G4, unchanged |
| I4 | Wizard 1 — Gerar Template | cannot-express | **works** (3 differs) | This move — see below |
| I5 | Wizard 3 — Recebimento por Arquivo | cannot-express | **cannot-express** | This move — see below |

### I4 — Gerar Template: now works, with 3 cosmetic differences

`resultAs: "download"` had shipped in Move 5 Wave 4 and a `gerarTemplate` action was already
declared — but re-reading the original's `generateTemplate()` line by line found the declared version
was **behaviourally incomplete in three ways**, all closed this move:

1. **The CSV's `produtoId` column was hard-coded to `""`.** The original looked each occupancy row's
   `loteId` up in a client-side map to fill it. `mapList`'s `select` is a literal-vs-`$ref` transform
   per item with no cross-list lookup, so the procedure now ships the `Lote` `{id, produtoId}` pairs
   as a second list and the capability's 3-arg overload does the join. Without this the template was
   unusable — `produtoId` is what identifies *what* is being counted.
2. **Zero-quantity bins were emitted**; the original filtered them out (`Number(o.quantidade) > 0`).
3. **The `Gerado` `InventarioArquivo` header row was never created.** The original creates it, and
   wizard 2 (Importar Contagem) then *selects* it — without it the two wizards were disconnected.
   Now created via `patchConcept` + `createIfMissing`, which required fixing **REG-89** first.

Verified: `Content-Disposition: attachment; filename="template-contagem.csv"`, `Content-Type:
text/csv`, `Content-Length: 188`, a real populated `produtoId` column; and the sibling
`InventarioArquivo` write confirmed at the database, not by eye — including once via a real browser
click (`Action "gerarTemplate" completed: OK`, row count 2 → 3).

Remaining differences, all cosmetic: the Entidade is a free-text UUID box rather than a `<select>`
(panel-action `inputFields` has no reference-picker resolution); the file downloads directly instead
of showing a preview `<textarea>` plus a named "Baixar CSV" link; and the filename is the fixed
`template-contagem.csv` rather than per-inventory `inventario-<id>.csv`.

### I5 — Recebimento por Arquivo: still `cannot-express`, three independent blockers

This is where the spec's "authoring gap only" premise breaks. Each blocker was established by test
or by reading the runtime, not assumed:

1. **REG-92 — no multi-line input to a Panel action.** Proven live in a real browser: a 3-line value
   typed into a declared panel action's `inputField` arrives as
   `{"tagName":"INPUT","type":"text","value":"LINHA-1 LINHA-2 LINHA-3","newlines":0}`. REG-76 fixed
   exactly this on the *Workbench* side (`<textarea>`) and it was never mirrored to the *Panel* side.
   The wizard's first step is "give the server a CSV", so it fails at step one — silently, with
   mangled data rather than an error.
2. **The preview table has no persisted-field home.** The original's preview shows `linha`,
   `ruaCodigo`, `produtoCodigo`, `dataValidade`, `posicoes`, `quantidadeAvulsa`, `quantidade`,
   `alerta`, `ok`. `InventarioArquivoLinha` declares **none** of them, so the aggregate's `linhas`
   band cannot render it. The only surface that could is a Panel action's generic "Last action
   result" table — which is gated behind blocker 1.
3. **Nothing carries parsed state from a preview action to a confirm action.** The Workbench carries
   a draft between `invoke()` and commit; a Panel's `lastActionResult` is client-only and is never
   posted back. Two independent panel actions would each have to re-parse, so the original's actual
   guarantee — *Confirmar commits exactly what you previewed, and is disabled until every line is
   `OK!`* — is not expressible.

Beyond those three, the commit half's scale is worth recording even though it is not itself a hard
blocker: picking candidate bins needs a join across `LocalArmazenagem` × `LocalArmazenagemLote` ×
`Lote` with arithmetic filtering, sorting and slicing, none of which `mapList` can do. It *is*
expressible by pushing the join into the app's own capability Java plus a nested `forEach` — the
exact shape `RecomputarOcupacaoOnCommitProcedure` already proves in this same model — but that is
rewriting the console's orchestration in Java, not authoring it, and it would leave the declared
model a thin wrapper over bespoke code. Not attempted, deliberately, and named here rather than
half-built.

---

## Bytes

```
Total eligible          117,930 B
Deleted                  12,748 B   crossdocking       (Move 8)
                       + 22,897 B   conferencia-fiscal (Move 10 W1)
                        ---------
                         35,645 B
Remaining eligible       82,285 B
```

**2 of 5 operator consoles now fully converted and deleted** (was 1 of 5).
