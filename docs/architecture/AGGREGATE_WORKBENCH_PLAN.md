# Aggregate Workbench & AutoPanels — Implementation Plan

Companion to [ADR-0004](../adr/ADR-0004-aggregate-workbench.md) (aggregate + workbench substrate) and
[ADR-0005](../adr/ADR-0005-auto-panel-patterns.md) (the AutoPanel authoring tier). The ADRs record
the *decisions*; this document is the *executable plan*: phases, contracts, files touched, and
acceptance criteria to make NPDev generate the WmsOffice receiving/shipping screens as instances of
a reusable **AutoPanel** — with the multi-level editor as the Transaction surface of an
aggregate-bound AutoPanel.

Status: Proposed — 2026-07-11, **revised** for the AutoPanel tier (ADR-0005). P0 slice 1 (aggregate
contract) committed; P4 reactive core spike-proven (see §8).

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

---

## 5. Phased plan (re-sequenced for AutoPanels)

Each phase ships a **named platform primitive** (reusable by any app) and advances the WMS
conformance target. Phases are individually shippable; **P0→P1→P3→P4 is the critical path.** The big
change from the pre-ADR-0005 order: the AutoPanel + single-concept expansion (P1) lands broadly
useful value *before* the hard multi-level work, and the old "workbench page kind" becomes P1's
expansion target rather than a standalone phase.

### P0 — `Aggregate` model primitive
**Goal:** declare a composition tree; read it nested. **Status: slice 1 committed** (`0147868`).
- **Schema** (4 mirrors): `aggregates[]` + recursive `aggregateCollection`. ✅ done.
- **DSL** (`ast/`, `parser`, `resolution`, `validation`): `AggregateAst`/`AggregateCollectionAst`,
  parse, resolve-passthrough, `validateAggregates`. ✅ done.
- **Slice 2 (pending):** `CompiledAggregate` + `ModelCompiler` + `CompiledModel` (+ canonical JSON
  read/write) + kernel `AggregateController.load(aggregate, rootId) → tree` +
  `RuntimeApiEmitter` nested-read endpoint.
- **Acceptance:** `GET /api/aggregate/Expedicao/{id}` returns `itens[]→{origens[],destinos[]}` JSON.
- **Risk:** low. Foundation only.

### P1 — `AutoPanel` primitive + single-concept expansion  ← new heart (ADR-0005)
**Goal:** a slim concept-bound AutoPanel expands into wired Selection + Detail + Transaction
(single level), reading concept defaults. Delivers C1, C2 and a full CRUD applet for *any* concept.
- **Schema:** `autoPanels[]` (`name?`, `concept` | `aggregate`, `surfaces[]`, per-surface config
  blocks `selection`/`prompt`/`detail`/`transaction`, each optional). Introduce `workbenches[]` +
  `selectors[]` as the **expansion target** (also hand-authorable escape hatch).
- **DSL:** `AutoPanelAst`/`CompiledAutoPanel` + a **default-derivation** pass that reads the concept
  (fields, id, bonds, ui labels, widget/datatype defaults) to fill unspecified surface config.
- **Generator** (`BusinessUiEmitter` + new `autopanel-expander` + `workbench.mustache`): expand each
  AutoPanel into Selection (filtered list), Detail (view), Transaction (single-level form); wire
  routes, workspace-menu nav, permissions. Bootstrap the template-emitted reactive store runtime
  (`npdev-generated/` hash-guarded — emit, never post-edit).
- **Runtime** (`PanelRuntime` sibling `WorkbenchRuntime`): serve surface metadata + data.
- **Acceptance:** `autoPanels:[{concept:"Cliente"}]` yields a working list+detail+form applet;
  Expedicao's C1 (Selection) and C2 (Transaction header) live.
- **Risk:** medium-high (the default-derivation contract must be predictable + overridable).

### P2 — `Prompt` surface / `SelectorGrid` + FK wiring
**Goal:** C6. Closes platform gap #14 (modal picker).
- **Schema:** `selectors[]` full (`concept`, `multiSelect`, `filters`, `columns`, `returnMapping`);
  `prompt` surface config; `picker` ref usable from any form field.
- **Generator:** emit the modal `SelectorGrid`; auto-attach a Prompt to FK fields across all
  generated forms (from bonds); return-mapping appends/binds rows.
- **Acceptance:** C6 — Seleciona Ruas filters + multi-select → returns rows; FK fields get prompts.
- **Risk:** medium.

### P3 — Tier-A computed columns + reactive store
**Goal:** C4 — live client recompute, available to any generated surface.
- **Schema:** `computed[]` (`col`, `expr`) on grid/section/band regions.
- **DSL:** validate `expr` parses (reuse `expression-cel` grammar); expose row/item scope vars.
- **Generator:** emit computed-field registrations into the store; ship the client CEL evaluator
  (compile CEL→JS or bundle a small interpreter) in the emitted runtime.
- **Kernel:** reuse `expression-cel` adapter to re-validate the same expr server-side on commit.
- **Acceptance:** C4 — editing Pos/CxAvulsas updates Total/Sub-Total/Máximo/Pendente instantly;
  server recompute agrees. (Node core already proves the math: §8.)
- **Risk:** medium (client/server CEL parity).

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

### P5 — Lifecycle state machine + gating
**Goal:** C9.
- **Schema:** `lifecycles[]` (`states`, `transitions`, per-state `{editableSurfaces, allowedActions}`);
  `lifecycle` ref on AutoPanel/transaction.
- **DSL:** validate transitions reference declared states.
- **Kernel:** bind to FlowEngine; emit transition endpoints.
- **Generator:** emit status chip + surface/region enable-disable + action-rail gating from state.
- **Acceptance:** C9 — Estágio transitions gate editability & actions across all levels.
- **Risk:** medium.

### P6 — Procedure-over-aggregate + commit boundary (Tier B) + slots
**Goal:** C10, C11.
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

### P7 — WMS conformance via AutoPanels + generality proof
**Goal:** C12; prove no new primitives needed.
- Author Expedicao and Recebimento as **AutoPanels** (Recebimento = same shape, `grids:[destinos]`,
  `RecebimentoEstagio`); `CentroConferenciaRecebimento` as a confirm surface / override.
- **Acceptance:** C12 — both editors live from slim AutoPanel declarations with zero generator
  changes. Any change needed reveals a missing knob → fold back into P1–P6.
- **Risk:** low (validation phase).

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
| Client/server CEL parity drift | P2 | Single expr string, evaluated client-side + re-validated server-side via `expression-cel` |
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
