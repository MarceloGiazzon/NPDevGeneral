# Move 3 G3 — Class A (`conferencia-fiscal.html`'s NF-e Import wizard): result

> Per `docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md` G3: "compose and find out, not wire up a known-good
> path." The composition was attempted for real, not assumed. **It works** — proven live, both REST
> and real browser, both the success and failure paths. One new, small, generically-useful declared
> surface was needed (workbench action `inputFields`), built and corpus-covered per the standing
> rule. One deliberate, honestly-named scope reduction was made (see "What was left out" below) —
> not a silent skip.

## The composition, as built

`conferencia-fiscal.html`'s NF-e Import is: paste XML → parse (client calls the `ImportarNfe` flow)
→ review/edit in a table → **Confirmar Importacao** (2 sequential `POST`s: one header, one per item —
true N+1, no transaction). G3 re-expresses this as:

1. **New aggregate** `DocumentoFiscalAggregate` (root `DocumentoFiscal`, collection `itens` ->
   `DocumentoFiscalItem`) — reuses the existing concepts, no schema change to them.
2. **New procedure** `ParseNfeProcedure` — calls the same `fiscalImport.importarNfe` capability the
   original flow calls, then flattens the parse result's fields onto the draft with five `mapValue`
   steps using **dotted-path refs** (`$parseResult.numero`, etc. — `mapValue`'s existing resolver
   already supports this; no new step type needed). Wrapped in an `if`/`then`/`else` on
   `$parseResult.sucesso`: success flattens `numero`/`chave`/`dataEmissao`/`emitenteCnpj`/`itens`
   onto the draft; failure maps only `$parseResult.motivo` to a `parseError` key, leaving everything
   else untouched (verified: a failed parse does not corrupt or blank an existing draft).
3. **New declared surface**: workbench-action `inputFields` (`autoPanels[].transaction.metadata
   .actions[].inputFields`), mirroring `panelAction.inputFields` (Move 2 G3) exactly — a list of
   scalar field names the client collects via an inline mini-form and merges into the draft body
   posted to `/invoke/{procedure}`. This is what lets a brand-new, empty draft (no `xml` field of
   its own) seed a procedure call with free-form input. No schema-file change was needed
   (`autoPanelSurface.metadata` is already unstructured JSON, same as the pre-existing `bandPickers`/
   `recompute` seams) — but it's a real new mechanism, implemented in
   `AutoPanelExpander.workbenchActions` (compiler) and `workbench-page.html.mustache` (client), and
   corpus-covered in `dsl-conformance-max` (`WidgetOrderWorkbench`'s new `RenameOrderStatusProcedure`
   action, `inputFields: ["newStatus"]`) per the standing "add a DSL feature, add a real example"
   rule.
4. **Commit** — unchanged: `AggregateRuntime.commit()`, G1's transaction boundary, one atomic write
   for the header + every item (replacing the original's 2 sequential unguarded `POST`s).

## Live verification

**REST, success path**: `POST /api/runtime/aggregate/DocumentoFiscalAggregate/invoke/ParseNfeProcedure`
with a real NF-e XML body returned a correctly-flattened draft (`numero`, `chave`, `dataEmissao`,
`emitenteCnpj`, `itens: [{codigo, descricao, quantidade, valorUnitarioCentavos, produtoId: null,
registrado: false}]`). Then `POST /api/runtime/aggregate/DocumentoFiscalAggregate` (no `id`, header
+ resolved `produtoId` + `entidadeId` + `situacao` filled in) created a real `DocumentoFiscal` +
nested `DocumentoFiscalItem` in one atomic call (`id: 2cdd900c-b354-4932-a7fc-6c4704ab8241`).

**REST, failure path**: invalid XML (no items) returned `{"parseError":"Nenhum item encontrado na
NF-e", "itens":[], ...}` with `sucesso:false` — the `then` branch never ran, confirming a failed
parse cannot silently corrupt an in-progress draft.

**Real browser** (`move3-g3-parse-nfe-routine.json`, `job_o6-6mbVhfGqS`, routine green, 0 console/page
errors): opened `DocumentoFiscalAggregateWorkbench.html?id=new`, typed a real NF-e XML into the
action's inline `xml` input, clicked **Importar (Parse NF-e)** — the draft populated live with
`chave: NFE-BROWSER-555`, `dataEmissao: 2026-07-29`, `emitenteCnpj: 99988877000166`, `numero: 555`,
and a real editable `itens` row (`quantidade: 4`, `valorUnitarioCentavos: 700`), confirmed by
screenshot (the DOM-text-dump extraction again mis-read the dense header table as blank — same
known artifact from G1/G2, always cross-checked against the actual screenshot in this session).

## What was left out — named, not silently dropped

`produtosConhecidos` (auto-match a parsed line to a real `Produto` by name) and `chavesJaImportadas`
(reject a duplicate `chave` before it's re-imported) are the original screen's own convenience
features, computed client-side from already-loaded reference data. **`ParseNfeProcedure` omits both**
(the capability defaults them to empty lists when absent — confirmed safe by reading
`FiscalImportCapability.importarNfe`'s `listOf`/`rawListOf` null-handling, not assumed): every parsed
item starts unmatched (`produtoId: null`, `registrado: false`, resolved manually during review,
same UI affordance the generic grid already has), and a duplicate `chave` is not rejected server-side.

**Why, precisely**: procedures have `LIST_CONCEPTS`/`RUN_QUERY` to fetch `Produto`/`DocumentoFiscal`
rows, but **no step type to reshape a fetched list into a differently-keyed array**
(`{codigo, produtoId}` from `{id, nome}`) — `forEach` runs nested steps per item with no accumulator,
and `mapValue` resolves one ref to one target, not a collection transform. This is a real, narrow,
separate platform gap (a list-comprehension/transform step for procedures) — named here rather than
worked around by quietly keeping this one piece hand-authored in client JS, which would have
undermined the point of the composition test.

## Verdict

**G3 succeeded.** The propose (invoke) -> review (editable draft) -> commit (atomic) composition is
real and works, proven at both REST and real-browser level, success and failure paths both. Per the
plan's own gate: **G4 may proceed.**
