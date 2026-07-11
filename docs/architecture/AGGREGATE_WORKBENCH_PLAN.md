# Aggregate Workbench — Implementation Plan

Companion to [ADR-0004](../adr/ADR-0004-aggregate-workbench.md). The ADR records the *decision*;
this document is the *executable plan*: phases, contracts, files touched, and acceptance criteria
to make NPDev generate the WmsOffice receiving/shipping multi-level editors as a reusable primitive.

Status: Proposed — 2026-07-11. P4 reactive core spike-proven (see §8).

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

| # | Behavior (from screenshots) | Phase |
|---|---|---|
| C1 | Filtered list with 9 filters + per-row open/print actions | P1 |
| C2 | Header form (Cliente/Veículo/…) with status line Fase/Situação | P1 |
| C3 | Items master grid; selecting a row drives the band | P1 |
| C4 | `Total = Pos×CxPad + CxAvulsas`, Sub-Total, Máximo, Pendente Origem recompute on keystroke | P2 |
| C5 | ORIGEM/DESTINO rows: add via "Adicionar Locais", delete per row | P3 |
| C6 | "Seleciona Ruas" modal: filters + multi-select → returns rows | P3 |
| C7 | Band: one card per item, two parallel grids (Exp) / one grid (Rec) | P4 |
| C8 | Per-region independent Modo Edição / Desfazer / Salvar | P4 |
| C9 | Estágio gates which regions/actions are editable; status chip | P5 |
| C10 | Gerar Demanda / Confirmar Movimentação run server allocation, patch draft | P6 |
| C11 | Commit persists the whole tree (cascade insert/update/delete) | P6 |
| C12 | Recebimento = same workbench, 1 destino grid, different lifecycle | P7 |

---

## 3. Architecture recap (see ADR-0004 for rationale)

Five layers: **L1** `Aggregate` model primitive · **L2** Region taxonomy (Header/Grid/Band/
SelectorGrid/ActionRail) · **L3** two-tier reactivity (Tier-A client CEL computed / Tier-B
procedure-over-aggregate) · **L4** declared lifecycle · **L5** extension slots.
Default/manual split target: ~80% generated, ~15% DSL-configured, ~5% bespoke procedures.

---

## 4. DSL surface (target)

The whole Expedicao editor from one `workbench{}` block plus one `aggregate{}` block:

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

## 5. Phased plan

Each phase ships a **named platform primitive** (reusable by any app) and advances the WMS
conformance target. Phases are individually shippable; P0→P4 are the critical path.

### P0 — `Aggregate` model primitive
**Goal:** declare a composition tree; read it nested.
- **Schema** (`model.schema.json` + 3 mirrors): add `aggregates[]` (`name`, `root`,
  recursive `collections[]` with `name`, `concept`, `via`/bond, `childField`, `ownership`,
  `orderBy`, nested `collections`).
- **DSL** (`NPDevContract/dsl/.../ast/`, `.../compiled/`, `ModelCompiler`, `SemanticValidator`):
  new `AggregateAst` / `CompiledAggregate`; validate root & collections resolve to real concepts
  and bonds, ownership consistent, no cycles, arbitrary depth allowed.
- **Kernel** (`GeneratedCrudRuntimeSupport.java` + new `AggregateController` in NPDevRuntimeHost):
  `load(aggregate, rootId) → tree` via bond joins.
- **Generator** (`RuntimeApiEmitter.java`): emit the nested-read endpoint.
- **Acceptance:** `GET /api/aggregate/Expedicao/{id}` returns `itens[]→{origens[],destinos[]}` JSON.
- **Risk:** low. Foundation only.

### P1 — Header + Grid regions + `workbench` page kind + lists
**Goal:** Levels 1–2 render, generated; both list screens.
- **Schema:** add `workbenches[]` (page kind, precedent = `workspace::Menu`) with `regions[]`;
  region base + `header`/`grid` kinds; `selectors[]` stub. Lists reuse existing `panel`.
- **DSL:** `WorkbenchAst`/`CompiledWorkbench`, `RegionAst`; validate region.collection ∈ aggregate.
- **Generator** (`BusinessUiEmitter.java` + new `workbench.mustache`; extend
  `business-ui-app.mustache`): emit region shell, `ActionRail` from declared actions, and bootstrap
  the **reactive store runtime** (template-emitted asset — `npdev-generated/` is hash-guarded, never
  post-edited). List screens via existing panel emitter path.
- **Runtime** (`PanelRuntime.java` sibling `WorkbenchRuntime`): serve workbench metadata + data.
- **Acceptance:** C1, C2, C3 live for Expedicao against seeded data.
- **Risk:** medium (new page kind, store bootstrap).

### P2 — Tier-A computed columns + reactive store
**Goal:** live client recompute (C4).
- **Schema:** `computed[]` (`col`, `expr`) on grid/band-grid regions.
- **DSL:** validate `expr` parses (reuse `expression-cel` grammar); expose row/item scope vars.
- **Generator:** emit computed-field registrations into the store; ship the client CEL evaluator
  (compile CEL→JS, or bundle a small interpreter) in the emitted runtime.
- **Kernel:** reuse `expression-cel` adapter to re-validate the same expr server-side on commit.
- **Acceptance:** C4 — editing Pos/CxAvulsas updates Total/Sub-Total/Máximo/Pendente instantly;
  server recompute agrees. (Node core already proves the math: §8.)
- **Risk:** medium (client CEL evaluation parity with server).

### P3 — Row add/delete + `SelectorGrid` picker
**Goal:** C5, C6. Closes platform gaps #13 (add/delete) & #14 (modal picker).
- **Schema:** `rowOps` ∈ [`add`,`delete`] on grid; `selectors[]` full (`concept`, `multiSelect`,
  `filters`, `columns`, `returnMapping`); `picker` ref on band-grids.
- **Generator:** emit add/delete handlers on the store; emit the modal `SelectorGrid` component
  (filtered, multi-select) and the return-mapping that appends rows.
- **Kernel** (`AggregateController.patch/commit`): honor inserts/deletes with bond cascade.
- **Acceptance:** C5, C6 — "Adicionar Locais" opens Seleciona Ruas, filter+check+Confirma appends
  rows; per-row delete removes.
- **Risk:** medium.

### P4 — `BandRegion` (2nd nesting, parallel grids) + per-region buffers  ← hardest
**Goal:** C7, C8. **Lifts the one-level nesting cap at
[`SemanticValidator.java:587`](../../NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java#L587).**
- **Schema:** `band` region (`forEach`, `display`, `collapsible`, `grids[]`).
- **DSL:** allow N-level aggregate nesting for owned collections; keep the 1-level cap only for
  *procedure-bound* panel dataSources (the original safety reason).
- **Generator:** emit the band renderer over the store — repeat card per parent row per `display`
  mode; two parallel grids; per-card computed (Sub-Total, Pendente, Endereçamento OK); per-region
  dirty buffers driving independent Modo Edição/Desfazer/Salvar.
- **Acceptance:** C7, C8 — full Expedicao card with ORIGEM+DESTINO; three independent edit
  lifecycles on one page. (Reactive-store mechanics spike-proven, §8.)
- **Risk:** **high** — the primitive stands or falls here. De-risked by the spike; validate the
  `display: all` DOM-weight path with virtualization before committing.

### P5 — Lifecycle state machine + gating
**Goal:** C9.
- **Schema:** `lifecycles[]` (`states`, `transitions`, per-state `{editableRegions, allowedActions}`);
  `lifecycle` ref on workbench.
- **DSL:** validate transitions reference declared states.
- **Kernel:** bind to FlowEngine; emit transition endpoints.
- **Generator:** emit status chip + region enable/disable + action-rail gating from state.
- **Acceptance:** C9 — Estágio transitions gate editability & actions across all three levels.
- **Risk:** medium.

### P6 — Procedure-over-aggregate + commit boundary (Tier B) + slots
**Goal:** C10, C11.
- **Schema:** action `binding: procedure`; `recompute.procedure`; `procedureSlot`/`validationSlot`.
- **Kernel** (`AggregateController`): `invoke(procedure, draft) → tree` (procedures may loop —
  flows can't); `commit(draft)` with cascade + validation slots (Máximo enforcement,
  "Endereçamento OK!").
- **Generator:** emit action dispatch → invoke/commit; wire slots.
- **App code (bespoke, the ~5%):** `procRecalcExpedicao`, `procGerarDemanda`,
  `procConfirmaMovimentacao` as procedures.
- **Acceptance:** C10, C11 — Gerar Demanda/Confirmar run server allocation, return patched draft;
  commit persists the whole tree.
- **Risk:** medium (business logic is app-side, not platform).

### P7 — Recebimento + generality proof
**Goal:** C12; prove no new primitives needed.
- Author `NovoCentroRec` as the same workbench (`grids: [destinos]`, `RecebimentoEstagio`) and
  `CentroConferenciaRecebimento` as a confirm workbench.
- **Acceptance:** C12 — both receiving screens live with zero generator changes. If any change is
  needed, it reveals a missing knob → fold back into P1–P6.
- **Risk:** low (validation phase).

---

## 6. Cross-cutting

**Design system.** A Workbench visual language on `--np-*` tokens (see
[platform theming memory]): region banding by role, dense editable-grid style, derived-cell
affordance, lifecycle chip, left action rails. Generated default, per-app themeable. Land in P1,
refine through P4.

**Extension slots (L5).** Introduced progressively: `computeSlot` (P2), `rowOps`/picker (P3),
`procedureSlot`+`validationSlot`+`recompute` (P6), `layoutSlot` (post-P7). Promotion rule: a slot
filled the same way across ≥2 apps becomes a generator default.

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
P0 ──► P1 ──► P2 ──► P3 ──► P4 ──► P5 ──► P6 ──► P7
        │            (P3,P4 both consume P2's store; can overlap)
        └─► lists (C1) shippable immediately on today's panel primitive
```
Critical path: **P0 → P1 → P2 → P4**. P3 and P5 can be developed in parallel branches once P2
lands. P6 business procedures are app-side and can start against P4 stubs.

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
| VS Code Gradle file-lock on regen | all | Bump build-root suffix (`-alt`/`-hNN`) per CLAUDE.md |

---

## 10. Immediate next actions

1. Commit ADR-0004 + this plan on a feature branch.
2. **P0**: schema `aggregates[]` (4 mirrors) + `AggregateAst`/`CompiledAggregate` + validator +
   nested-read endpoint; unit + generator-gate green.
3. In parallel: add `display` toggle to the spike to A/B the band modes before freezing the P4
   schema shape.
