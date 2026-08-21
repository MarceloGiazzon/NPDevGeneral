# Aggregate Workbench & AutoPanels — Implementation Plan

> **STATUS: EXECUTED.** All phases delivered 2026-07-11…2026-07-25 — P0/P1/P4/P6/P7/Polish (this
> doc's own 2026-07-12 reconciliation, below) plus the three that were PARTIAL there: AW-P2
> (`cd3cbcf`, closed DONE by `7e1096e` — FK auto-Prompt was already-implemented, `selectorRef`
> owner-descoped), AW-P3 (`ff4acba` — folded `computed[]` into `recompute:`, closed the DOM-weight
> risk), AW-P5 (`0762536` — per-state `allowedActions` gating), reconciled by `88e28a1`. Kept as the
> authoritative design record (ADRs + phase contracts below are still the reference). **Live
> re-verification DONE 2026-07-28** (`docs/FRONTEND_STRATEGY_PLAN.md` F5-V.2): the REG-48 `delete()`
> reordering holds up through `AggregateRuntime.commit()`'s cascade-delete of a band row (server-side
> confirmed, not just DOM) — 7/11 scenario steps passed live against WmsOffice's
> `ExpedicaoWorkbench.html`/`RecebimentoWorkbench.html`, 3/11 not exercisable because WmsOffice's own
> model doesn't declare `recompute`/`bandPickers`/`actions` (an authoring gap on that one app, not a
> platform regression). One low-severity cosmetic finding filed (REG-60: post-commit confirmation
> message gets wiped by a subsequent re-render).

Companion to [ADR-0004](../adr/ADR-0004-aggregate-workbench.md) (aggregate + workbench substrate) and
[ADR-0005](../adr/ADR-0005-auto-panel-patterns.md) (the AutoPanel authoring tier). The ADRs record
the *decisions*; this document is the *executable plan*: phases, contracts, files touched, and
acceptance criteria to make NPDev generate the WmsOffice receiving/shipping screens as instances of
a reusable **AutoPanel** — with the multi-level editor as the Transaction surface of an
aggregate-bound AutoPanel.

Status (historical, 2026-07-12 reconciliation): P0, P1, P4, P6, P7, and Polish are DONE (verified
live, all committed). P2, P3, P5 were PARTIAL at that point — see the STATUS header above for how
each closed afterward. See §5 for per-phase evidence (commit hashes, file paths).

> **Realignment note.** This plan was originally workbench-first. Per ADR-0005 the primary authoring
> surface is now the **AutoPanel** (a slim, concept/aggregate-bound pattern that the generator
> expands into Selection/Prompt/Detail/Transaction surfaces). The Aggregate Workbench is the
> Transaction surface of an *aggregate-bound* AutoPanel; the region taxonomy is its expansion
> vocabulary; `panel{}`/`workbench{}` are the per-surface escape hatch.

---

## 1. Scope

**In:** the Aggregate Workbench primitive and everything needed to generate:
`TelaExpedicao` / `TelaRecebimento` (filtered lists) and `NovoCentroExpedicao` / `NovoCentroRec`
(3-level editors) + `CentroConferenciaRecebimento` (confirm screen).

**Out (deferred, known boundaries):** `Imprimir` (report generation), `Add Doctos` / NFe attach
(file-upload boundary), `Histórico` viewer. These get stub actions, not implementations.

**Source of truth:** `WmsLabs_Mod_GX17.xml` (`TelaExpedicao` L105969, `NovoCentroExpedicao`
L104035, receiving editor `NovoCentroRec`) and screenshots at
`D:\WorkSpace\WmsOffice\OriginalArtifacts\Galery\multi-level`.

---

## 2. Conformance target — what "done" means

The editor must reproduce, generated from a model declaration:

| # | Behavior (from screenshots) | Phase | Surface |
|---|---|---|---|
| C1 | Filtered list with 9 filters + per-row open/print actions | P1 | Selection |
| C2 | Header form (Cliente/Veículo/…) with status line Fase/Situação | P1 | Transaction |
| C6 | "Seleciona Ruas" modal: filters + multi-select → returns rows | P2 | Prompt |
| C4 | `Total = Pos×CxPad + CxAvulsas`, Sub-Total, Máximo, Pendente Origem recompute on keystroke | P3 | Transaction |
| C3 | Items master grid; selecting a row drives the band | P4 | Transaction/Section |
| C5 | ORIGEM/DESTINO rows: add via "Adicionar Locais", delete per row | P4 | Band |
| C7 | Band: one card per item, two parallel grids (Exp) / one grid (Rec) | P4 | Band |
| C8 | Per-region independent Modo Edição / Desfazer / Salvar | P4 | Transaction |
| C9 | Estágio gates which regions/actions are editable; status chip | P5 | all |
| C10 | Gerar Demanda / Confirmar Movimentação run server allocation, patch draft | P6 | Transaction |
| C11 | Commit persists the whole tree (cascade insert/update/delete) | P6 | Transaction |
| C12 | Recebimento = same AutoPanel, 1 destino grid, different lifecycle | P7 | all |

---

## 3. Architecture recap (see ADR-0004 + ADR-0005 for rationale)

**Three authoring tiers** (ADR-0005): *Default* (concept only) → **AutoPanel** (slim pattern,
generator-expanded, the primary surface) → *Free panel/workbench* (escape hatch). Value target:
~80% AutoPanel, ~15% AutoPanel-configured, ~5% bespoke free panels/procedures.

**Five substrate layers** (ADR-0004) that AutoPanel expansion emits into: **L1** `Aggregate` model
primitive · **L2** Region/Surface taxonomy (Selection/Prompt/Detail/Transaction; regions
Header/Grid/Band/SelectorGrid/ActionRail) · **L3** two-tier reactivity (Tier-A client CEL computed /
Tier-B procedure-over-aggregate) · **L4** declared lifecycle · **L5** extension slots.

**Subsumption:** an aggregate-bound AutoPanel's *Transaction* surface **is** the Aggregate Workbench.

---

## 4. DSL surface (target)

**Primary surface — an AutoPanel.** Slim, concept/aggregate-bound; the generator expands it into
Selection/Prompt/Detail/Transaction reading concept defaults. Minimal → maximal:

```jsonc
// Tier: default+ — a full CRUD applet for one concept, all defaults from the concept
"autoPanels": [ { "concept": "Cliente" } ],

// Rich — WMS shipping; override only what defaults can't infer, bind to an aggregate for multi-level
"autoPanels": [{
  "name": "ExpedicaoWorkWith", "aggregate": "Expedicao", "lifecycle": "ExpedicaoEstagio",
  "surfaces": ["selection","prompt","detail","transaction"],   // default: all
  "selection": { "filters": ["cliente","situacao","estagio","expedicaoId"] },
  "prompt":    { "labelField": "cliente" },
  "transaction": {
    "recompute": { "action": "reCalcularSaldos", "procedure": "procRecalcExpedicao" },
    "sections": [
      { "collection": "itens", "computed": [{ "col": "total", "expr": "pos*cxPad + cxAvulsas" }],
        "rowOps": ["add","delete"], "actions": ["gerarDemanda"],
        "band": { "grids": ["origens","destinos"], "display": "selected", "picker": "SelecionaRuas" } }
    ]
  }
  // escape hatch, per surface:  "transaction": { "panel": "CustomExpedicaoEditor" }
}]
```

**Expansion target (generated, or hand-authored as the escape hatch).** The AutoPanel above expands
into the `aggregate{}` + `workbench{}` + `selectors{}` below — which authors may also write directly
for the bespoke 20%:

```jsonc
"aggregates": [{
  "name": "Expedicao", "root": "Expedicao",
  "collections": [
    { "name": "itens", "concept": "ExpedicaoItem", "via": "bondExpedicaoItem",
      "childField": "expedicaoId", "ownership": "owned", "orderBy": "produtoId",
      "collections": [                                   // 2nd level — lifts the L587 limit
        { "name": "origens",  "concept": "MovtoOrigem",  "via": "bondItemOrigem",  "childField": "itemSeq", "ownership": "owned" },
        { "name": "destinos", "concept": "MovtoDestino", "via": "bondItemDestino", "childField": "itemSeq", "ownership": "owned" }
      ] }
  ]
}],
"workbenches": [{
  "name": "CentroExpedicao", "route": "/expedicao/{id}",
  "aggregate": "Expedicao", "lifecycle": "ExpedicaoEstagio",
  "regions": [
    { "kind": "header", "fields": ["cliente","veiculo","motorista","origem","equipe","observacao"],
      "actions": ["salvar","cancelar","suspender","confirmarSaida"] },
    { "kind": "grid", "collection": "itens", "rowSelectContext": "item",
      "columns": ["produto","pos","qtdCx","total","alocado","info"],
      "computed": [{ "col": "total", "expr": "pos * cxPad + cxAvulsas" }],
      "rowOps": ["add","delete"], "actions": ["gerarDemanda","acoesPorItem"] },
    { "kind": "band", "forEach": "itens", "display": "selected", "collapsible": true,
      "grids": [
        { "collection": "origens", "picker": "SelecionaRuas",
          "columns": ["local","validade","pos","cxAvulsas","total","maximo"],
          "computed": [{ "col": "total", "expr": "pos*cxPad + cxAvulsas" },
                       { "col": "maximo", "expr": "maxPos*cxPad" }] },
        { "collection": "destinos", "picker": "SelecionaRuas",
          "columns": ["local","pos","cxAvulsas","total"] } ] }
  ],
  "recompute": { "action": "reCalcularSaldos", "procedure": "procRecalcExpedicao" }
}],
"selectors": [{
  "name": "SelecionaRuas", "concept": "LocalArmazenagem", "multiSelect": true,
  "filters": ["area","rua"], "columns": ["rua","disponivelQtd","maxPos"],
  "returnMapping": { "local": "rua", "maxPos": "maxPos" }
}]
```

`display` ∈ `selected` (default) | `all` | `paged`. Recebimento reuses this with
`grids: [destinos]` and `lifecycle: "RecebimentoEstagio"`.

> **F5-R3 (`docs/FRONTEND_STRATEGY_PLAN.md`), recorded 2026-07-28: `display` was never
> implemented.** `ff4acba` found no code path reads it — `BandRegion` always renders in the
> `selected` mode this doc calls the default, with no `all`/`paged` alternative anywhere in the
> generator or runtime. It was closed without code, not deferred. Corpus-checked 2026-07-27: 0
> files in `golden-ai-scenarios/**` or `knowledge/**` teach a client to expect it, so this was
> confined to this document. Kept here as a recorded design intent (a future band-display-mode
> feature could still pick this field name up), not as documentation of current behavior.

---

## 5. Phased plan (re-sequenced for AutoPanels)

Each phase ships a **named platform primitive** (reusable by any app) and advances the WMS
conformance target. Phases are individually shippable; **P0→P1→P3→P4 is the critical path.** The big
change from the pre-ADR-0005 order: the AutoPanel + single-concept expansion (P1) lands broadly
useful value *before* the hard multi-level work, and the old "workbench page kind" becomes P1's
expansion target rather than a standalone phase.

### P0 — `Aggregate` model primitive ✅ DONE (reconciled 2026-07-12)
**Goal:** declare a composition tree; read it nested. **Status: slice 1 committed** (`0147868`).
- **Schema** (4 mirrors): `aggregates[]` + recursive `aggregateCollection`. ✅ done.
- **DSL** (`ast/`, `parser`, `resolution`, `validation`): `AggregateAst`/`AggregateCollectionAst`,
  parse, resolve-passthrough, `validateAggregates`. ✅ done.
- **Slice 2 — DONE:** `CompiledAggregate`/`CompiledAggregateCollection` wired into `ModelCompiler`/
  `CompiledModel` with canonical JSON round-trip (committed `887ab34`, "P0(2a): compile aggregates
  into CompiledModel + canonical JSON round-trip"). Nested-read endpoint landed as a hand-written
  RuntimeHost controller rather than a generator-emitted one — `AggregateApiController`
  (`GET /api/runtime/aggregate/{aggregateName}/{rootId}`) backed by `AggregateRuntime`, committed
  `f57b84c` ("P0(2b): AggregateRuntime + nested-read endpoint (runtime host)"). `RuntimeApiEmitter`
  itself emits nothing aggregate-specific — the read path is fixed platform code, not per-app
  generated code, which satisfies the acceptance criterion without matching the plan's original
  file-location guess.
- **Acceptance:** `GET /api/runtime/aggregate/Expedicao/{id}` returns `itens[]→{origens[],destinos[]}`
  JSON. ✅ met (verified live per P4/P6/P7 evidence).
- **Risk:** low. Foundation only.

### P1 — `AutoPanel` primitive + single-concept expansion  ← new heart (ADR-0005) ✅ DONE (reconciled 2026-07-12)
**Goal:** a slim concept-bound AutoPanel expands into wired Selection + Detail + Transaction
(single level), reading concept defaults. Delivers C1, C2 and a full CRUD applet for *any* concept.
- **Schema:** `autoPanels[]` present in all 4 schema mirrors. ✅ done.
- **DSL:** `AutoPanelAst`/`AutoPanelSurfaceAst`/`AutoPanelComputedAst`/`CompiledAutoPanel` + the
  default-derivation pass lives in `compiler/AutoPanelExpander.java` (reads concept fields/id/bonds/
  ui labels/widget defaults); validated in `SemanticValidator`. Committed `ee4b083` ("P1(1): AutoPanel
  primitive contract — schema + AST + validation + compiled").
- **Generator:** expansion + emission live in `BusinessUiEmitter.java` + template
  `workbench-page.html.mustache` — there is no separately-named `autopanel-expander` module (the
  expansion pass is DSL-side, in `AutoPanelExpander`); routes/nav/permissions wired.
- **Runtime:** `PanelRuntime` (not a separately-named `WorkbenchRuntime` — the plan's proposed name
  never landed; `WorkbenchRuntimeTest.java` actually tests `PanelRuntime`) serves surface metadata +
  data, committed `96c0773` ("P4(runtime): PanelRuntime serves the aggregate Workbench").
- **Acceptance:** `autoPanels:[{concept:"Cliente"}]` yields a working list+detail+form applet;
  Expedicao's C1 (Selection) and C2 (Transaction header) are live. ✅ met — all functional pieces
  exist and are committed; only class/file *names* differ from the plan's proposal.
- **Risk:** medium-high (the default-derivation contract must be predictable + overridable) — proved
  out; every derived default is inspectable via the compiled descriptor.

### P2 — `Prompt` surface / `SelectorGrid` + FK wiring — PARTIAL (reconciled 2026-07-12)
**Goal:** C6. Closes platform gap #14 (modal picker).
- **Schema:** full `selectors[]` (`concept`, `multiSelect`, `filters`, `columns`, `returnMapping`) +
  `SelectorAst` + `AutoPanelExpander.expandSelector` + `SemanticValidator.validateSelectors`.
  Committed `3757336` ("P2(3): standalone selectors[] primitive -> reusable picker panel"). ✅ done.
- **Still missing:** no dedicated `SelectorGrid` component — a selector expands into a generic
  picker panel, not a distinct modal grid class. The `bandPickers.<band>` mechanism the Polish pass
  shipped (`AutoPanelExpander.bandPickers()`, `workbench-page.html.mustache`'s `openBandPicker`) is a
  **separate, ad hoc** in-browser modal that references an existing panel *by name* via
  `transaction.metadata.bandPickers` — it is not fed by `selectors[]` and does not close this gap.
  FK-field auto-attach of a Prompt from bonds is still driven by the pre-existing `promptsByConcept`
  mechanism, not by `selectors[]`.
- **Acceptance:** C6 — Seleciona Ruas filters + multi-select → returns rows (met via `bandPickers`,
  not via this primitive); FK fields auto-getting a `selectors[]`-backed Prompt — **not met**.
- **Remaining scope for AW-P2:** either (a) declare `bandPickers` as sugar that compiles to a
  `selectors[]` reference so there's one mechanism, or (b) wire automatic FK→Prompt attachment from
  bonds using `selectors[]`. Re-scope AW-P2 to just this; drop the "SelectorGrid class" framing.
- **Risk:** medium → now low-medium (schema/validation risk retired; remaining work is wiring, not design).

### P3 — Tier-A computed columns + reactive store — PARTIAL (reconciled 2026-07-12)
**Goal:** C4 — live client recompute, available to any generated surface.
- **Schema:** `computed[]` (`col`, `expr`) — `model.schema.json:442`. ✅ done.
- **DSL:** `AutoPanelComputedAst`/`CompiledAutoPanelComputed`; expression engine
  `NPDevContract/dsl/.../expr/ComputedExpression.java` + `ComputedExpressionTest`/
  `ComputedExpressionValidationTest`, committed `8756812` ("P3(2): ComputedExpression engine + real
  computed-expr validation"). `AutoPanelExpander.withComputed()` writes `metadata.computed` into the
  compiled descriptor. Server-side re-validation: `842801c` ("P3(3): server-side computed columns in
  PanelRuntime"). ✅ done.
- **Still missing:** the generated `workbench-page.html.mustache` never consumes `metadata.computed` —
  no client CEL/expression evaluator wired into the emitted page. What Polish shipped instead
  (`recompute: <procedure>` under `transaction.metadata`, debounced invoke-on-edit) is a **server
  round-trip via a procedure**, not a client-side evaluation of the declared `computed[]` expression —
  it satisfies the *live-recompute-on-keystroke* UX (C4) but does not evaluate `computed[]` itself, so
  the two mechanisms currently coexist without one subsuming the other.
- **Acceptance:** C4 — editing Pos/CxAvulsas updates Total/Sub-Total/Máximo/Pendente instantly and
  server recompute agrees — ✅ met, via the `recompute:` procedure path, not the `computed[]` client
  evaluator.
- **Remaining scope for AW-P3:** decide whether `computed[]` client-eval is still needed given
  `recompute:` already delivers the UX, or fold `computed[]` into `recompute` as its declarative
  source (procedure derived from the expression) to avoid two competing mechanisms.
- **Risk:** medium (client/server CEL parity) — now mostly moot since the server-round-trip path sidesteps parity entirely; revisit only if `recompute`'s network round-trip proves too slow for dense grids.

### P4 — Multi-level Transaction: Sections→Bands over an aggregate  ← hardest
**Goal:** C3, C5, C7, C8 — an aggregate-bound AutoPanel's Transaction becomes the Aggregate
Workbench. **Lifts the one-level nesting cap at
[`SemanticValidator.java:587`](../../NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java#L587)**
(owned aggregate collections only; the cap stays for procedure-bound panel dataSources).
- **Schema:** `transaction.sections[]` (per owned collection) with `band` (`grids[]`, `display`,
  `collapsible`, `picker`) and `rowOps` ∈ [`add`,`delete`].
- **Generator:** emit the master Grid (row-select drives sections), the Band renderer (repeat card
  per parent row per `display` mode; 1..N parallel grids; per-card computed Sub-Total/Pendente/
  Endereçamento-OK), row add/delete via store + Prompt, and per-region dirty buffers driving
  independent Modo Edição/Desfazer/Salvar.
- **Kernel** (`AggregateController.patch`): honor inserts/deletes with bond cascade.
- **Acceptance:** C3, C5, C7, C8 — full Expedicao Transaction: items master grid + ORIGEM/DESTINO
  band + three independent edit lifecycles on one page. (Reactive-store mechanics spike-proven, §8.)
- **Risk:** **high** — the primitive stands or falls here. De-risked by the spike; validate the
  `display: all` DOM-weight path with virtualization before committing.

### P5 — Lifecycle state machine + gating — PARTIAL (reconciled 2026-07-12)
**Goal:** C9.
- **Delivered:** region-level editable/read-only gating driven by the **pre-existing singular**
  `lifecycle` construct (`LifecycleAst`/`CompiledLifecycle`, unchanged) — `AutoPanelExpander
  .lifecycleDescriptor` projects the root concept's lifecycle into the workbench descriptor
  (statusField, states with editable/terminal flags, transitions); the template renders a status chip
  and makes the whole panel read-only in non-editable/terminal states. Committed `4f133b1` ("P5(1):
  lifecycle gating — status chip + per-state editability in the workbench").
- **Still missing (the plan's actual new-primitive scope):**
  1. No `lifecycles[]` **array** schema construct — P5 deliberately reused the existing singular
     `lifecycle`, so there's no new DSL surface or its validation.
  2. No per-state `allowedActions` — only whole-panel editable/read-only toggles by state; individual
     actions/regions are not independently gated.
  3. No dedicated `/transition` endpoint and no FlowEngine binding — P6's transition buttons drive
     declared *procedures* directly (`AggregateApiController`, `952f39e`), not a lifecycle-declared
     transition list through a kernel-owned transition path.
- **Acceptance:** C9 — Estágio transitions gate editability (region-level) ✅ met; action-level gating
  across all levels — **not met**.
- **Remaining scope for AW-P5:** narrow to per-state `allowedActions` (action-rail gating) only, since
  region editability and the status chip already ship. Decide whether `lifecycles[]` (plural, new
  schema) is still worth adding or whether the existing singular `lifecycle` should just grow an
  optional `allowedActions` map per state — the latter is less invasive and reuses everything P5
  already committed.
- **Risk:** medium → now low (state machine + validation infrastructure already exists via the
  singular `lifecycle`; remaining work is additive, not new design).

### P6 — Procedure-over-aggregate + commit boundary (Tier B) + slots ✅ DONE (verified live 2026-07-12)
**Goal:** C10, C11.
**Result:** Slice 1 (commit 472cf38) — lifecycle transition actions (the "Confirmar" half of C10):
a button per transition from the current state sets the status field + commits; the persistence
adapter enforces the legal transition. Slice 2 (commit 952f39e) — procedure-over-aggregate invoke:
extracted a shared `ProcedureRunner` @Service (breaking the AggregateRuntime↔PanelRuntime cycle),
added `AggregateRuntime.invoke` + `POST /api/runtime/aggregate/{name}/invoke/{proc}` returning the
patched draft WITHOUT persisting, and a workbench invoke-button per `transaction.metadata.actions`
entry (review-then-Save). Verified live on H2: invoke patches the draft (404 until commit), commit
persists, unknown procedure 400s. App-side bespoke procedures (procGerarDemanda etc.) are authoring,
not platform. C11 (commit boundary + cascade) landed in P4's AggregateRuntime.commit reconcile.
- **Schema:** action `binding: procedure`; `transaction.recompute.procedure`;
  `procedureSlot`/`validationSlot`.
- **Kernel** (`AggregateController`): `invoke(procedure, draft) → tree` (procedures may loop —
  flows can't); `commit(draft)` with cascade + validation slots (Máximo enforcement,
  "Endereçamento OK!").
- **Generator:** emit action dispatch → invoke/commit; wire slots.
- **App code (bespoke, the ~5%):** `procRecalcExpedicao`, `procGerarDemanda`,
  `procConfirmaMovimentacao` as procedures.
- **Acceptance:** C10, C11 — Gerar Demanda/Confirmar run server allocation, return patched draft;
  commit persists the whole tree.
- **Risk:** medium (business logic is app-side, not platform).

### P7 — WMS conformance via AutoPanels + generality proof ✅ DONE (verified live 2026-07-12)
**Goal:** C12; prove no new primitives needed.
**Result:** Authored Expedicao (itens→{origens,destinos}, 5-state estagio lifecycle) and Recebimento
(itens→{destinos}, 3-state estagio lifecycle) as slim `autoPanels`. Both `ExpedicaoWorkbench.html`
and `RecebimentoWorkbench.html` emitted with **zero generator changes** (C12 met). Live on H2:8199 —
descriptors carry differing bands (2 vs 1) + differing lifecycles (5/4 vs 3/2 states/transitions);
create + Estágio transition (predemanda→demandagerada) round-trip persisted the whole tree intact.
- Author Expedicao and Recebimento as **AutoPanels** (Recebimento = same shape, `grids:[destinos]`,
  `RecebimentoEstagio`); `CentroConferenciaRecebimento` as a confirm surface / override.
- **Acceptance:** C12 — both editors live from slim AutoPanel declarations with zero generator
  changes. Any change needed reveals a missing knob → fold back into P1–P6.
- **Risk:** low (validation phase).

### Polish (post-P7) ✅ DONE (verified live in-browser 2026-07-12, commit fb16649)
Three descriptor-driven client primitives on the served workbench page, all declared under
`transaction.metadata` and verified via ScrapForAI on an H2 app:
- **Reactive recompute (C7):** `recompute: <procedure>` → debounced (450ms) invoke on every cell edit,
  patches derived fields in place, restores caret via `data-fkey` focus keys.
- **Band row picker (C6 "Seleciona Ruas"):** `bandPickers.<band> = {panel,label,columns}` → modal that
  fetches the source Selection panel and appends picked rows (overlapping columns copied).
- **Per-region edit buffers (C8):** store keeps a baseline; each region bar shows a "revert" that
  discards only that region's staged edits; invoke/recompute `patch()` in place (baseline preserved).
The in-browser pass caught + fixed a real bug (`clone(undefined)` aborted revert on a new record).

---

## 6. Cross-cutting

**Design system.** A Workbench visual language on `--np-*` tokens (see
[platform theming memory]): region banding by role, dense editable-grid style, derived-cell
affordance, lifecycle chip, left action rails. Generated default, per-app themeable. Land in P1,
refine through P4.

**Extension slots (L5) & the escape hatch.** Every AutoPanel surface is overridable by pointing it
at a free `panel{}`/`workbench{}` (`"transaction": { "panel": "CustomEditor" }`) — the primary
Tier-3 seam. Finer slots introduced progressively: `computeSlot` (P3), `procedureSlot`+
`validationSlot`+`recompute` (P6), `layoutSlot` (post-P7). Promotion rule: a surface override or slot
filled the same way across ≥2 apps becomes an AutoPanel/generator default.

**Testing strategy.**
- *Reactive core:* node tests (the §8 spike pattern) — fast, deterministic, no browser.
- *DSL/compiler:* JUnit beside existing `PanelDataSourceNestingValidationTest`.
- *Generator:* `scripts/quality/run-generator-gate.ps1` + emitter tests
  (`BusinessUiEmitterPanelManifestTest` pattern).
- *Runtime:* `run-runtimehost-gate.ps1` + `PanelRuntimeTest` sibling `WorkbenchRuntimeTest`.
- *Frontend:* `run-frontend-gate.ps1`.
- *End-to-end:* real-browser via ScrapForAI (see [ScrapForAI memory]) against a live FinalApp
  (127.0.0.1, SSRF allowlist gotchas noted there).

**Schema mirror discipline.** Every schema change mirrors to all 4 copies (CLAUDE.md §"model.schema.json
duplicated in 4 places").

**Restage discipline.** After kernel/adapter Java changes, run
`scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars` with matching `-RuntimeHostLibsDir`
before regenerating an app, or the running app keeps a stale jar.

---

## 7. Sequencing & dependencies

```
P0 ──► P1 ──────► P3 ──► P4 ──► P5 ──► P6 ──► P7
   (aggregate)  (AutoPanel   │  (multi-level  (lifecycle)(Tier-B)(WMS)
                single-conc.) │   Transaction)
                    └► P2 (Prompt/SelectorGrid) ─┘   [feeds P4 rows]
```
Critical path: **P0 → P1 → P3 → P4**. P1 (AutoPanel single-concept) is broadly useful on its own and
delivers a CRUD applet for every concept. P2 (Prompt) and P3 (computed) can proceed in parallel once
P1's store bootstrap lands; both feed P4. P5 parallelizes after P4; P6 procedures are app-side and
can start against P4 stubs.

---

## 8. Current state — P4 spike (proven)

`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\spikes\p4-aggregate-workbench\`:
`workbench-core.js` (reactive store + Tier-A computed), `workbench-spike.html` (Header + Items +
Band with 2 parallel grids + picker + per-region buffers), `test-workbench-core.js`
(**17/17 node assertions pass**: computed math matches screenshots — F031=294 Cx, balanced 430 Cx;
edit→recompute; per-region undo; row add/delete; save baseline). Not committed. This validates the
L2/L3/L4 client mechanics ahead of P4 and the P2 store.

---

## 9. Risk register

| Risk | Phase | Mitigation |
|---|---|---|
| N-level nesting destabilizes existing panel validation | P4 | Keep 1-level cap for procedure-bound dataSources; new rule only for owned aggregate collections |
| Client/server CEL parity drift | P2 | Single expr string, evaluated client-side + re-validated server-side via `runtime-support` |
| `display:all` DOM weight with live cells | P4 | `paged` virtualization + soft item-count warning in generator |
| Hash-guarded bundle blocks runtime injection | P1 | Runtime emitted by templates, never post-gen patched |
| Allocation logic leaks into platform | P6 | Confined to app-side procedures via slots |
| AutoPanel default-derivation unpredictable/opaque | P1 | Defaults must be inspectable (expand-to-workbench is viewable) + every surface overridable; derive from existing widget/bond systems, don't reinvent |
| VS Code Gradle file-lock on regen | all | Bump build-root suffix (`-alt`/`-hNN`) per CLAUDE.md |

---

## 10. Immediate next actions

1. ✅ ADR-0004/0005 + this plan committed; P0 slice 1 (aggregate contract) committed (`0147868`).
2. **Finish P0 slice 2**: `CompiledAggregate` + `ModelCompiler` + `CompiledModel` (+ canonical JSON)
   + kernel `AggregateController.load` + nested-read endpoint; generator-gate green.
3. **P1 kickoff — AutoPanel primitive**: `autoPanels[]` schema (4 mirrors) + `AutoPanelAst` +
   default-derivation pass; expand a single-concept AutoPanel (`{concept:"Cliente"}`) into
   Selection/Detail/Transaction. This is the ADR-0005 heart and the new broad-value milestone.
4. In parallel: add the `display` toggle (`selected`/`all`/`paged`) to the spike to A/B band modes
   before freezing the P4 `transaction.sections.band` shape.
