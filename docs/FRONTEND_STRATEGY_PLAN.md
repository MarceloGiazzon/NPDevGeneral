# Frontend Strategy — Contract, Coverage & Provenance

> **STATUS: ACTIVE.** Live backlog. Written 2026-07-27 against `beta1-vision-spine` @ `8bc3715`.
>
> **Supersedes** the separate `2C_CONTRACT_PATH_PLAN.md` and `2D_AGGREGATE_WORKBENCH_PLAN.md`, and
> replaces `docs/EXECUTION_TREES.md` §2.C **and** §2.D — including the "Week-5 fork" between them,
> which does not exist (Part 0).
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\FRONTEND_STRATEGY_PLAN.md" `
>           "D:\WorkSpace\NPDev\NPDev_General\docs\FRONTEND_STRATEGY_PLAN.md"
> ```
> Working scripts already staged and tested: `<scratchpad>\2c-staging\`.
>
> Facts are **MEASURED** (source, git, or a real generated app, 2026-07-27) or **PROPOSED**.
> They are labelled. Do not conflate them.

---

# Part 0 — Why these unify

## 0.1 The two errors that kept them apart

`EXECUTION_TREES.md` presented 2.C and 2.D as rival strategies with a decision point at Week 5. Both
halves of that framing were wrong, for different reasons.

**Error 1 — 2.D was scoped as unbuilt.** MEASURED: `docs/architecture/AGGREGATE_WORKBENCH_PLAN.md`
(411 lines) records P0/P1/P4/P6/P7/Polish as DONE and verified live 2026-07-12; the three PARTIAL
phases all closed afterwards — **AW-P2** `cd3cbcf`+`7e1096e` (2026-07-24), **AW-P3** `ff4acba`,
**AW-P5** `0762536`, reconciled by `88e28a1`. The shipped `workbench-page.html.mustache` is 458 lines
and already carries BandRegion, parallel grids, per-region dirty tracking and revert, debounced
recompute with caret restoration, lifecycle gating, band pickers, and procedure-over-aggregate.
Server: `AggregateRuntime` 274 lines (`load`/`commit`/`invoke`).

**Error 2 — they were treated as covering the same screens.** They do not.

MEASURED: WmsOffice declares **2 aggregates** (`Expedicao`, `Recebimento`) with 2 bound autoPanels —
precisely the GeneXus screens ADR-0004 named as its conformance target. Those are generated. Its
**13 hand-written screens are a different class entirely**:

| Screen | Size | Tables | Viz refs | Inputs | Flow calls | Class |
|---|---|---|---|---|---|---|
| `mapa-armazem` | 19.6 KB | 0 | **21** | 5 | 0 | spatial map |
| `analytics` | 11.5 KB | 0 | **10** | 2 | 0 | dashboard |
| `inventario` | 28.0 KB | 0 | 10 | 2 | **4** | operator console |
| `conferencia-fiscal` | 22.9 KB | 0 | 8 | 2 | 2 | operator console |
| `relatorios` | 10.5 KB | 0 | 8 | 2 | 0 | report launcher |
| `centro-trabalho` | 30.9 KB | 0 | 4 | 2 | 1 | operator console |
| `movimentacao-livre` | 23.4 KB | 0 | 4 | 2 | 1 | free-form entry |
| `crossdocking` | 12.7 KB | 1 | 1 | 4 | 3 | operator console |
| `usuarios-roles` | 9.5 KB | 1 | 1 | 2 | 0 | admin |
| `novo-estabelecimento` | 16.3 KB | 0 | 0 | 5 | 0 | wizard |
| `excluir-estabelecimento` | 9.8 KB | 0 | 0 | 2 | 0 | wizard |
| `seed-data` | 8.7 KB | 0 | 0 | 2 | 0 | admin tool |
| `login` | 6.8 KB | 0 | 0 | 4 | 0 | auth (generated) |

**Not one is a master-detail-detail transaction.** ADR-0004's own "Out (deferred)" list names reports
and document attach. The workbench was never scoped for these.

## 0.2 The unifying insight

> **The contract is the substrate. The workbench is one consumer of it. The taxonomy routes
> screens to consumers. Provenance binds every screen back to the model — regardless of who wrote it.**

```
                     compiled-metadata  (11 catalogs + `invocations` = 12)
                     ────────────────┬────────────────────
                                     │  ONE contract
        ┌────────────────────┬───────┴────────┬────────────────────┐
        ▼                    ▼                ▼                    ▼
  Aggregate Workbench   AutoPanel        AI-generated         Hand-written
  (transaction class)   (list/detail)    screen               screen
  ✅ SHIPPED            ✅ SHIPPED       ⬜ 2.C                ⬜ today: opaque
        │                    │                │                    │
        └────────────────────┴────────┬───────┴────────────────────┘
                                      ▼
                        panel.json  — ONE provenance model
                                      ▼
                    impact gate · regeneration · truth model
```

## 0.3 What makes this a real merge, not a concatenation

**MEASURED: for generated screens, provenance is nearly free.** `AutoPanelExpander` (590 lines)
already computes, per surface, a descriptor containing:

```
fields · columns · collection · concept · actions · procedure · recompute
aggregate · bands · lifecycle · allowedActions · fkFields · picker · prompt
```

and already stamps a provenance-shaped key:

```java
// AutoPanelExpander.java:511 / :549
metadata.put("generatedBy", "selector");   // and "autoPanel"
metadata.put("surface", surface);
metadata.put("concept", concept);
```

**The generator already knows exactly what it emitted.** So the same `panel.json` manifest that 2.C
proposed for hand-written screens can be *derived* for generated ones with no inference at all. That
gives **one manifest format, three producers**:

| Producer | Mechanism | Confidence | Effort |
|---|---|---|---|
| **Generator** (workbench / AutoPanel / selector) | derive from the descriptor | exact | **low** — the data exists |
| **AI agent** | emit per the prompt contract | declared | prompt rule |
| **Human** (hand-written) | infer, then confirm | inferred → confirmed | bootstrapper |

And **one gate** treats all three uniformly. That is the merge.

## 0.4 Where they meet in code: slots

ADR-0004's **L5 `layoutSlot`** — "swap a generated region for a hand-authored panel, keeping data
wiring" — is literally a hand-written fragment *inside* a generated screen. It needs the contract
(to know what data it is wired to) and provenance (so a field rename finds it). It is unbuildable
under either plan alone and trivial under the merged one.

## 0.5 The replacement block for `EXECUTION_TREES.md`

Replace **both** §2.C and §2.D with:

```
├─ 2.CD ═══ FRONTEND STRATEGY — contract, coverage, provenance ═══   [~3 wks]
│    ⚠️ The old 2.C-vs-2.D "fork" does not exist. They cover disjoint screen
│       classes and share one substrate. See docs/FRONTEND_STRATEGY_PLAN.md.
│
│    F1  Screen taxonomy — which primitive covers which class        [1 day] ★★
│    F2  Contract substrate — `invocations` catalog + bundle + docs   [5 days]
│    F3  Provenance — one manifest, three producers                   [4 days] ★
│    F4  Impact gate — a field rename names the screens it breaks     [2 days] ★★
│    F5  Workbench: re-verify + 4 residuals (it is DONE, not to-build)[2.5 days]
│    F6  Coverage roadmap — build only what F1 proves recurs          [gated]
```

---

# Part 1 — F1 · The screen taxonomy  ★★

**1 day. Do this first — it routes everything else.**

## Why first

Six of WmsOffice's 13 hand-written screens are **operator consoles** (flow-driven, few inputs, heavy
status display, 1–4 flow invocations). That was invisible until the screens were measured as a set.
Without the taxonomy, every new screen is an ad-hoc judgment call — which is how 13 screens got
hand-written one decision at a time.

## Deliverable — `docs/SCREEN_TAXONOMY.md`

| Class | Example | Primitive | Status |
|---|---|---|---|
| **Transaction** (master-detail-detail, per-region buffers, computed cells, lifecycle) | `NovoCentroExpedicao` | Aggregate Workbench | ✅ generated |
| **Selection / list** | `TelaExpedicao` | AutoPanel Selection | ✅ generated |
| **Detail / form** | concept edit | AutoPanel Detail | ✅ generated |
| **Prompt / picker** | Seleciona Ruas | `selectors[]` / `bandPickers` | ✅ generated (two mechanisms — F5-R4) |
| **Auth** | `login` | generated | ✅ |
| **Operator console** | `inventario`, `crossdocking`, `centro-trabalho` | ❌ none | hand-written → F2/F3 |
| **Dashboard / analytics** | `analytics`, `relatorios` | ❌ none | hand-written → F2/F3 |
| **Spatial / map** | `mapa-armazem` | ❌ none | hand-written → F2/F3 |
| **Wizard** | `novo-estabelecimento` | ❌ none | hand-written → F2/F3 |
| **Admin tool** | `seed-data`, `usuarios-roles` | partial (ControlPanel) | hand-written |

## Method

1. Classify all 13 WmsOffice screens — the §0.1 table is the measured raw data.
2. Classify the other four official apps' screens the same way.
3. **Promotion rule: a class appearing in ≥ 2 apps with ≥ 2 screens is a primitive candidate.**
   Nothing is a candidate without it — this is the discipline that keeps F6 evidence-driven.
4. Record candidates as ADR stubs. **Build none of them yet.**

## Acceptance

- Every screen in all five official apps has a class.
- Each class names its covering primitive, or is explicitly `hand-written → contract`.
- Candidates listed with the ≥2/≥2 evidence shown.
- **README's limitations section can now name generated vs hand-written classes precisely**, instead
  of the current vague "custom business screens are hand-written."

---

# Part 2 — F2 · The contract substrate

**5 days. ~80% already exists.**

## 2.1 What ships today — MEASURED

`compiled-metadata.json` carries **11 catalogs**, built at
`CompiledMetadataCanonicalJson.toCanonicalObject()` lines 72–82:

```java
catalogs.set("concepts", …);    catalogs.set("procedures", …);  catalogs.set("panels", …);
catalogs.set("domainTypes", …); catalogs.set("fields", …);      catalogs.set("enums", …);
catalogs.set("references", …);  catalogs.set("actions", …);     catalogs.set("transitions", …);
catalogs.set("layout", …);      catalogs.set("validation", …);
// ← line 83: insert `invocations`
```

`layout` already carries **`visibleWhen` / `enabledWhen` / `readonlyWhen` / `requiredWhen`** —
declarative field-level reactivity, compiled. `actions` carries `label`, `confirmationText`,
`successMessage`, `failureHint`, `dangerLevel`, `permissionHint`. `references` carries a complete
picker spec. All of it is served, permission-filtered, at `/api/v1/runtime/metadata/ui/*` via
`PermissionAwareUiMetadataService`.

## 2.2 F2.1 — The `invocations` catalog · **2 days** · ✅ DONE 2026-07-28

> **Correction, filed the same day this shipped.** This section's original Java sketch and JSON
> examples (below) were written from the model's *shape*, not verified source, and several were
> factually wrong once actually implemented and checked against a real generated app (WmsOffice,
> regenerated 2026-07-28) — the EXACT mistake this feature's own "finding that justifies it"
> paragraph warns about, made again while designing the feature meant to prevent it:
> - `POST /api/concepts/ExpenseRequest` (line ~259, generic CRUD) **does not exist**. The real path
>   is keyed by TABLE name, not concept name: `POST /api/concepts/expense_requests`.
> - `execution.successStatus: 202` for flow-execute is only sometimes true. The real controller
>   returns **200 on synchronous completion, 202 only when the flow parks on an `awaitEvent`, and
>   422 (not 400) on invariant/validation failure**.
> - `requiredPermission: "expenserequest:create"` has the halves backwards — the real format is
>   `"create:expenserequest"` (operation first).
> - `getInvocableProcedures()` (line 337) does not exist on `CompiledAggregate`, and **no closed
>   aggregate↔procedure binding exists anywhere in the platform** — `AggregateRuntime.invoke`
>   accepts any model-global procedure name. The shipped implementation emits one templated
>   `aggregateInvoke` entry per aggregate naming this explicitly, rather than inventing or guessing
>   a curated list.
> - The `isStartEndpoint()` gate (line 323) would have emitted **zero** flow entries — no sample in
>   this repo sets it, and the real flow-execute route has no such filter. Every flow is executable.
>
> The shipped implementation (`CompiledMetadataCanonicalJson.toInvocationCatalog` and its ~15
> helpers) corrects all of these; see each helper's own javadoc for the specific controller/line it
> verifies against. **The critical test passed with zero mismatches**: every one of 343 real paths
> (main + aliases) across all 252 invocation entries WmsOffice's model produces matched a real
> `@RequestMapping` in the generated app, across both source trees — proven by a fresh
> `extract-routes.py` run (which itself needed a real bug fixed: its regex could not parse a path
> variable nested inside a multi-value `@PostMapping` array, silently dropping every flow-execute
> route). `InvocationCatalogRouteConformanceTest` (`NPDevContract/dsl`) is the permanent, committed
> regression form of this proof, run against the in-repo `medium-expense-approval` sample.

### The finding that justifies it

I tried to establish NPDev's REST surface by reading source and produced **two confidently wrong
answers** before landing on the truth. A generated app has **two** source trees:

```
App/src/main/java/com/finalexec/**      ← RuntimeHost template copy   (I searched only this)
App/npdev-generated/src/main/java/**    ← the MODEL-GENERATED code    (hash-guarded)
```

Every per-concept and per-flow controller lives in the second. If a careful reader with grep and full
repo access needs three passes to answer *"how do I create an Expense?"*, an agent handed the data
model has no chance — and a hand-written screen that got it right two years ago cannot notice when it
stops being right.

### The true surface — MEASURED

```
A. Per-concept typed controllers   @RequestMapping("/api/areas"), ("/api/lotes"), … (1 per concept)
B. Generic CRUD                    GET|POST /api/concepts/{conceptName}
                                   GET|PUT|DELETE /api/concepts/{conceptName}/{id}
C. Per-flow                        POST /api/v1/flows/{flowName}/execute   (+ /api/flows/… alias)
                                   POST /api/v1/executions/{executionId}/resume
D. Execution gateway               POST /api/v1/execute/flow          → 202 ACCEPTED
                                   POST /api/v1/execute/panel-action  → 202 ACCEPTED
E. Query / aggregate / panel / files
                                   GET  /api/v1/concepts/{concept}/page
                                   GET  /api/v1/concepts/{concept}/export.csv
                                   GET  /api/v1/runtime/aggregate/{name}/{rootId}
                                   POST /api/v1/runtime/aggregate/{name}
                                   POST /api/v1/runtime/aggregate/{name}/invoke/{procedure}
                                   POST|DELETE .../panels/{p}/dataSources/{ds}/rows[/{id}]
                                   POST /api/files/{concept}/{field}
```

### The decision this forces

**There are at least three ways to create a record**, with different semantics: direct CRUD (A/B)
writes the row; flow execution (C/D) runs invariants, orchestration and compensation. Using direct
CRUD on a flow-backed concept **silently bypasses business rules**.

> **DESIGN DECISION 1.** The 12th catalog is **`invocations`**, not `routes` — it answers *"how do I
> do X correctly?"*
>
> **DESIGN DECISION 2.** Every entry carries **`preferred: true|false`**, and when false,
> **`prefer: "<id>"` + `preferReason`.** This encodes *"do not bypass the flow"* in machine-readable
> form and is the single most valuable field in the catalog.

```jsonc
{ "id": "createDirect:ExpenseRequest", "kind": "createDirect", "concept": "ExpenseRequest",
  "method": "POST", "path": "/api/concepts/ExpenseRequest",
  "preferred": false,
  "prefer": "flow:SubmitExpense",
  "preferReason": "ExpenseRequest creation is flow-backed; the direct route bypasses the flow's invariants, orchestration and compensation." }

{ "id": "flow:SubmitExpense", "kind": "flow", "intent": "create", "concept": "ExpenseRequest",
  "label": "Submit expense", "preferred": true,
  "method": "POST", "path": "/api/v1/flows/SubmitExpense/execute",
  "pathAliases": ["/api/flows/SubmitExpense/execute"],
  "body": { "shape": "flowInput",
            "inputFields": [ {"name":"amount","type":"decimal","required":true,
                              "fieldPath":"ExpenseRequest.amount"} ] },
  "execution": { "async": true, "successStatus": 202,
                 "statusRoute": "/api/v1/executions/{executionId}/links",
                 "correlationField": "executionId" },
  "requiredPermission": "expenserequest:create",
  "errors": [ {"status":400,"meaning":"IllegalArgumentException -- invalid input"},
              {"status":403,"meaning":"SecurityException -- permission or row-scope denied"},
              {"status":503,"meaning":"IllegalStateException -- runtime unavailable"} ] }
```

> The `errors` block is MEASURED, not invented — it is the exact mapping in
> `DirectExecutionGatewayController:60-68`.

**`preferred` is derivable today**: for each concept, a `CompiledFlow` whose `concept` matches with
mode create/update/delete makes the corresponding `*Direct` entry non-preferred.

### Implementation

Insert at `CompiledMetadataCanonicalJson.java:83`:

```java
catalogs.set("invocations", toInvocationCatalog(model));
```

Builder matching the file's idiom (compare `toTransitionCatalog:506`):

```java
    /**
     * F2.1: the intent -> invocation catalog.
     *
     * <p>A concept typically has SEVERAL write routes with different semantics: direct CRUD
     * ({@code /api/concepts/{name}}, {@code /api/{pluralTable}}) writes the row; flow execution
     * ({@code /api/v1/flows/{flow}/execute}) runs invariants, orchestration and compensation.
     * Entries whose concept is flow-backed are emitted with {@code preferred:false} and a
     * {@code prefer} pointer, so a consumer cannot silently bypass business rules.
     *
     * <p>Deterministic ordering by id -- the generator's determinism contract. Use LinkedHashMap,
     * never a multi-entry Map.of (see NoMultiEntryMapOfInGeneratedManifestEmittersTest).
     */
    private static ArrayNode toInvocationCatalog(CompiledModel model) {
        List<ObjectNode> entries = new ArrayList<>();
        Map<String, Set<String>> flowBacked = flowBackedModes(model);   // concept -> {create,update,delete}

        for (CompiledConcept concept : sortedConcepts(model)) {
            entries.add(listInvocation(concept));
            entries.add(pagedQueryInvocation(concept));
            entries.add(exportCsvInvocation(concept));
            entries.add(readInvocation(concept));
            for (String mode : List.of("create", "update", "delete")) {
                entries.add(directMutationInvocation(concept, mode, flowBacked));
            }
        }
        for (CompiledFlow flow : sortedFlows(model)) {
            if (flow.isStartEndpoint()) { entries.add(flowInvocation(model, flow)); }
        }
        for (CompiledPanel panel : sortedPanels(model)) {
            for (CompiledPanelAction a : sortedPanelActions(panel)) {
                entries.add(panelActionInvocation(panel, a));
            }
            for (CompiledPanelDataSource ds : sortedDataSources(panel)) {
                if (hasRowOp(ds, "add"))    { entries.add(panelRowCreateInvocation(panel, ds)); }
                if (hasRowOp(ds, "delete")) { entries.add(panelRowDeleteInvocation(panel, ds)); }
            }
        }
        for (CompiledAggregate agg : sortedAggregates(model)) {
            entries.add(aggregateReadInvocation(agg));
            entries.add(aggregateCommitInvocation(agg));
            for (String proc : sortStrings(agg.getInvocableProcedures())) {
                entries.add(aggregateInvokeInvocation(agg, proc));
            }
        }
        for (CompiledConcept concept : sortedConcepts(model)) {
            for (CompiledField f : sortedFields(concept)) {
                if (isUploadWidget(f)) { entries.add(fileUploadInvocation(concept, f)); }
            }
        }
        entries.sort(Comparator.comparing(n -> n.path("id").asText("")));
        return toArray(entries);
    }
```

### ★ The test that keeps it honest

**Assert every `invocations[].path` matches a real `@RequestMapping`/`@*Mapping` in the generated
app — across BOTH source trees.** This is the single most important test in the whole plan: it is
what prevents the catalog drifting from reality, and it is the automated version of the mistake I
made by hand.

### Acceptance
- 12 catalogs; the other 11 **byte-identical**; regeneration deterministic.
- Every path verified against both trees by test.
- `medium-expense-approval` yields `flow:SubmitExpense` (`preferred:true`, 202) **and**
  `createDirect:ExpenseRequest` (`preferred:false`, `prefer:"flow:SubmitExpense"`).

## 2.3 F2.2 — The bundle endpoint · **1 day** · ✅ DONE 2026-07-28

> **Correction, filed the same day this shipped.** The example response below shows `transitions`
> and `invocations` as ordinary arrays alongside `fields`/`actions`, implying they were already
> reachable through `RuntimeMetadataService`'s generic catalog mechanism. They were not: F2.1's
> `invocations` catalog and the pre-existing `transitions` catalog both lived in
> `compiled-metadata.json`, but `MetadataManifestAssetEmitter` (a hardcoded 9-entry list that
> predates F2.1) never split either one into its own `npdev/metadata/*.manifest.json` file, so there
> was no manifest for `RuntimeMetadataService.catalog(...)` to load. Fixed as part of this task (now
> 11 manifests, not 9) rather than treated as pre-existing infrastructure to merely "compose."
>
> **Also corrected:** this section implies all eight non-`fields`/`actions` catalogs would gain
> permission-aware filtering "for free" by composing `PermissionAwareUiMetadataService`. In reality
> that service only filters `fields` and `actions` — nothing in the platform filters
> `layout`/`enums`/`references`/`transitions`/`validation`/`invocations` by actor. The shipped bundle
> passes those six through unfiltered from `RuntimeMetadataService` rather than inventing six new
> permission filters, which would be a materially larger task than "compose the existing filters."

```
GET /api/v1/runtime/metadata/ui/bundle[?concept=X|?panel=Y]
```

```jsonc
{ "schemaVersion": "npdev-ui-contract.v1", "modelHash": "sha256:…",
  "generatedAt": "…", "namespace": "…", "permissionAware": true,
  "scope": { "concept": "ExpenseRequest" },
  "concept": {…}, "fields": [], "layout": [], "enums": [], "references": [],
  "actions": [], "transitions": [], "validation": [], "invocations": [],
  "apiBase": "/api/v1", "auth": { "scheme": "bearer", "tenantHeader": "X-Tenant-Id" } }
```

Add to `RuntimeUiMetadataController` beside `/actions`, `/fields`, `/preview/{c}`, `/panels/{p}`;
compose the **existing** filters in `PermissionAwareUiMetadataService` rather than duplicating them.

> **`modelHash` is the load-bearing field** — F3 records it, F4 compares it. **Reuse the existing
> compiled-model fingerprint** (`SchemaLifecycleExecutor` already has one); do not mint a second hash.

**Acceptance.** Each bundle array equals the individual endpoint's output for the same caller (the
anti-drift assertion). Two roles → different `filteredCount`.

## 2.4 F2.3 — `docs/UI_CONTRACT.md` + schema + agent prompt · **2 days** · ✅ DONE 2026-07-28

> **Correction, filed the same day this shipped.** The staged draft below was carried over, not
> verbatim, because building F2.1/F2.2 first surfaced real errors in it: "Everything is
> permission-filtered" is false (only `fields`/`actions` are — the shipped `UI_CONTRACT.md` states
> this as its own dedicated warning section); the "twelve catalogs" table implied all twelve are in
> the bundle, when `panels`/`procedures`/`domainTypes`/plural-`concepts` are not; the direct-CRUD
> path example (`POST /api/{pluralTable}`) doesn't exist (real path: `/api/concepts/{tableName}`,
> same F2.1 correction). Also found, while writing the doc, a real gateway route neither this plan
> nor F2.1 ever catalogued: `POST /api/v1/execute/flow` (`DirectExecutionGatewayController`) is a
> real, working, untyped flow-execution path — but it is not represented in the `invocations`
> catalog at all, so an agent following only the catalog would never discover it. Documented as a
> known gap. `schemas/ui-contract.schema.json` shipped as ONE file (not the 4-way `model.schema.json`
> mirror, per this section's own "decide this explicitly" note) and was validated with the
> `jsonschema` library against a real captured `GET .../bundle?concept=Area` response from a live
> regenerated WmsOffice.

Full ~80%-complete draft of `UI_CONTRACT.md` and the 8-rule agent prompt are in the staged
`2C_CONTRACT_PATH_PLAN.md` §2.C.3 — carry them over verbatim. The two rules that matter most:

> **There is more than one way to write a record, and they are not equivalent.** Never choose the
> route yourself — obey `preferred`/`prefer`.
>
> **Flow-backed writes return 202 Accepted and are asynchronous.** A UI that shows "Saved!" on a 202
> is lying. Follow `execution.statusRoute`. Direct CRUD returns 200/201 synchronously — check
> `execution.successStatus` per entry rather than assuming.

---

# Part 3 — F3 · Provenance: one manifest, three producers  ★  ✅ DONE 2026-07-28

**4 days. This is where the merge pays.**

> **Correction, filed the same day this shipped.** "For generated screens provenance is nearly free"
> (§3.2, unchanged below) was half right: `AutoPanelExpander` really does already stamp
> `metadata.generatedBy`/`concept`, exactly as this section claims. What it omitted: nothing ever
> serialized `CompiledPanel.metadata()` into `compiled-metadata.json`'s `panels` catalog, so that
> stamp never reached an HTTP consumer before this task closed the gap
> (`CompiledMetadataCanonicalJson#toPanelProvenance`, new). Also, §3.4's staged
> `bootstrap-panel-provenance-v2.py` had a real, never-yet-run bug: panel-action invocation ids use a
> COLON (`panelAction:<panel>:<action>`, `CompiledMetadataCanonicalJson#panelActionInvocation`), not
> the DOT the script assumed — every real panel-action `invokes` would have silently come back empty.
> Fixed and committed as `scripts/quality/bootstrap-panel-provenance.py`, then run for real against 3
> genuine WmsOffice screens and confirmed by hand — see `docs/adr/ADR-0010-panel-provenance-manifests
> .md` for the full account, including two genuine inference errors the human-review step caught
> (a `name`/`label` HTML-token false positive, and a field spread into a bare flow-payload literal
> that the writes-heuristic missed).

## 3.1 The manifest — PROPOSED

```jsonc
{
  "schemaVersion": "npdev-panel-provenance.v1",
  "panel": "Inventario",
  "screen": "web/inventario.html",
  "screenClass": "operator-console",          // ← from F1's taxonomy
  "producer": "human",                        // generator | agent | human
  "generatedFrom": {
    "modelHash": "sha256:9f2c…",
    "generatedAt": "2026-07-27T10:28:50Z",
    "generator": "claude-opus-5",
    "bundleScope": { "concept": "Inventario" }
  },
  "reads":   ["Inventario.quantidade", "Inventario.status", "Produto.descricao"],
  "writes":  ["Inventario.quantidade"],
  "invokes": ["flow:ConfirmarInventario"],
  "calls":   ["GET /api/v1/concepts/Inventario/page", "POST /api/v1/flows/ConfirmarInventario/execute"],
  "enums":   ["Inventario.status"],
  "slotOf":  null,                            // ← reserved for ADR-0004 L5 layoutSlot
  "confirmed": true,
  "unresolved": []
}
```

## 3.2 Producer 1 — the generator (**new; nearly free**)

MEASURED: `AutoPanelExpander` (590 lines) already computes per surface —
`fields · columns · collection · concept · actions · procedure · recompute · aggregate · bands ·
lifecycle · allowedActions · fkFields · picker · prompt` — and already stamps:

```java
metadata.put("generatedBy", "autoPanel");   // :549   (and "selector" at :511)
metadata.put("surface", surface);
metadata.put("concept", concept);
```

**Extend `generatedBy` into a full manifest.** No inference: `reads` = descriptor fields + columns;
`invokes` = declared actions/procedures resolved to `invocations[].id`; `producer: "generator"`;
`confirmed: true` by construction.

**Effort: ~1 day.** Emit alongside each generated surface. Every workbench and AutoPanel screen
immediately joins the truth model — which no separate 2.C plan would have delivered.

## 3.3 Producer 2 — the AI agent

The prompt (F2.3) requires the manifest as a second output; `producer: "agent"`, `confirmed: true`
(the agent declares what it used, it does not guess).

## 3.4 Producer 3 — the human (inference + confirmation)

**Working script, already staged and run:**
`<scratchpad>\2c-staging\bootstrap-infer-panel-provenance.py` (95 lines).

**Real results, MEASURED 2026-07-27** against three genuine WmsOffice screens using a proxy bundle
built from its 26 concept files (158 fields, 15 flows):

```
inventario.panel.json        reads=13  calls=18  unresolved=0
centro-trabalho.panel.json   reads=10            unresolved=0
crossdocking.panel.json      reads=1             unresolved=0
```

`inventario` correctly recovered `Inventario*.quantidadeContada`, `…quantidadeEsperada`,
`…divergente`, `Lote.dataValidade`, `Produto.qtdCaixasPorPallet`, plus 18 API calls including four
`/api/flows/*/execute` invocations.

> **`confirmed: false` is the key design choice.** An inferred manifest is a hypothesis. The gate
> enforces only confirmed ones, so the bootstrapper may be wrong without breaking a build — the same
> refuse-vs-warn calibration REG-51 settled, one notch softer because the input is inferred.

**Known refinement:** the current regex reports `invokes=0` because WmsOffice screens call
`/api/flows/{Name}/execute` rather than a `flowName:` body field. Add that pattern — it is a
two-line change and the reason to run the bootstrapper against real screens before trusting it.

---

# Part 4 — F4 · The impact gate  ★★

**2 days. This is the demo that sells the whole strategy.**

| Condition | Result |
|---|---|
| **Confirmed** manifest references a field not in the model | **FAIL** |
| **Confirmed** manifest references an unknown `invocations[].id` | **FAIL** |
| Confirmed manifest's `modelHash` ≠ current | **WARN** (drift, not breakage) |
| Screen with no manifest | **WARN**, listed |
| `confirmed: false` | **REPORT** only |

**Working script, already staged, calibration passing:**
`<scratchpad>\2c-staging\check-panel-provenance-impact.py` (109 lines).

```
$ python check-panel-provenance-impact.py --metadata … --calibrate
  [PASS] stale confirmed manifest (fired)
  [PASS] correct manifest (silent)
OK: both controls behave correctly.
```

Wire into `run-ai-knowledge-gate.ps1` — this repo's prior art for lightweight gates.

## The money demo

```
rename Inventario.quantidade → quantidadeContada
  SemanticValidator      ✅ passes
  migration planner      ✅ perfect ALTER, data preserved
  entity/REST/authz      ✅ regenerated
  BEFORE:  inventario.html  💥 silently broken, found by a warehouse operator
  AFTER:   build FAILS — "web/inventario.html reads Inventario.quantidade, which no longer exists.
                           Run `npdev panel regenerate Inventario`."
```

**No competitor can do this.** Lovable/v0/Bolt have no model to diff against; OutSystems/Mendix
cannot emit source you own. It is the schema-evolution insight lifted one layer up — and because F3
covers generated screens too, it protects the workbench surfaces as well.

---

# Part 5 — F5 · The workbench: verify + close residuals

**2.5 days. It is DONE — this is verification, not construction.**

## F5-V.1 Suites · **2 hr**

```powershell
.\gradlew :NPDevContract:dsl:test --rerun-tasks --console=plain
cd NPDevGenerator ; .\gradlew :generator:test :generator:behaviorTest --console=plain
```
Grep for `@Disabled` on workbench tests — a disabled acceptance test is how "DONE" quietly decays.

## F5-V.2 ★ Live re-verification, both aggregates · **4 hr** — **DONE 2026-07-28**

Every "verified live" claim dated **2026-07-12**; ~150 commits had landed since, including
REG-48/50/52/53 — all touching paths the commit boundary uses. Re-verified live against WmsOffice,
tenant `trial`, a freshly re-created `admin`/`admin123` account (the original identity data was
wiped by an unrelated REG-58/REG-59 destructive-migration recovery earlier the same session — see
`docs/NPDEV_OPEN_ITEMS_REGISTER.md`), `127.0.0.1` not `localhost`.

**Correction:** the real generated Aggregate Workbench pages are `npdev-workbench/ExpedicaoWorkbench.html`
/ `RecebimentoWorkbench.html`, not `centro-trabalho.html` (a separate, older hand-authored console
with no draft/commit model — this doc already correctly filed it under "hand-written operator
console" above; a prior AI session's carried-over context had mislabeled it, now corrected).

| # | Step | Proves | Result |
|---|---|---|---|
| 1 | Header renders with status chip | HeaderRegion + lifecycle | ✅ PASS |
| 2 | Select parent row → band renders for that row only | P4 BandRegion + cascade | ✅ PASS |
| 3 | Edit band cell → ~450 ms → computed update, **caret holds** | C4/C7 | ⛔ not testable — WmsOffice declares no `recompute` |
| 4 | Edit second region → only it is dirty | C8 | ✅ PASS |
| 5 | Revert one region → other survives | C8 | ✅ PASS |
| 6 | Band picker multi-select returns rows | C6 | ⛔ not testable — no nested `collections`/`bandPickers` declared |
| 7 | Invoke procedure → draft patched, not persisted | P6 | ⛔ not testable — no `transaction.metadata.actions` declared |
| 8 | Advance state → action rail changes | AW-P5 | ✅ PASS |
| 9 | Terminal state → read-only | C9 | ✅ PASS |
| 10 | Commit → reload → tree persisted incl. bands | P6 cascade | ✅ PASS |
| 11 | **Delete band row, commit, reload** | **REG-48 on the aggregate cascade** | ✅ **PASS** |

**7/11 PASS; 3/11 couldn't be exercised** because WmsOffice's currently-deployed model doesn't
declare the features those three steps need — a model-authoring gap on this one app, not a platform
regression (the underlying `recompute`/`bandPickers`/`actions` mechanisms are exercised elsewhere in
the corpus). None of the 11 steps produced a genuine failure.

> **Step 11 mattered most, and it held.** REG-48 reordered `enforcePermission`/`enforceRowWritable`
> ahead of `evaluateRuleProfiles` in `DefaultConceptGateway.delete()`. `AggregateRuntime.commit()`
> cascades through that gateway; this was the first re-run of that exact cascade against the fix,
> and the first live check since the repo went public. Deleted a Recebimento `lotes` band row,
> committed, reloaded (real page reload, not SPA state) — row stayed gone. Independently confirmed
> server-side: `GET /api/recebimento_lotes/<deleted-id>` → 404, `GET /api/recebimento_lotes` →
> exactly the 2 surviving rows, root record untouched.

Incidental finding (not a formal step, not a regression in scope here): the post-commit "Saved."
message is unobservable — `commitDraft()`'s success handler sets it then immediately calls
`render()`, which rebuilds `#app` (including a fresh blank message span) before a user could see it.
Filed as REG-60 (low severity, cosmetic).

Evidence → `..\NPDev_General__OutsideRepo\aw-reverify-2026-07\` (`RESULTS.md` + the 4 ScrapForAI
routine files used).

## F5-V.3 Status header · **1 hr**

`AGGREGATE_WORKBENCH_PLAN.md` has **no `> **STATUS:**` line in its first 8 lines**, so
`check-register-consistency.py`'s planning-document check does not cover it. Add
`STATUS: EXECUTED` naming the closing commits and the re-verification evidence path.

## The four residuals

### F5-R1 ★ `allowedActions` is an untyped metadata escape hatch · **4 hr**

MEASURED: gating works, but the producer reads a **CSV string from a generic metadata map** —
`AutoPanelExpander.java:310` — and `grep "allowedActions" model.schema.json` → **no matches**.
A typo (`GerarDemenda`) is not a validation error; it silently yields a missing button in
production. Same class as REG-52/REG-53: the model is "valid," the app is wrong.

**Fix:** typed `allowedActions` array on the lifecycle state, in **all four schema mirrors** (the
4th carries `canonicalSchema`+`deprecated` keys that must be preserved); validate every entry
resolves to a declared action — unknown = **error**. Post-T1.15 this belongs in
`LifecycleValidation.java`. RED-first test required.

> **RISK RETIRED (pre-checked 2026-07-27): 0 of 27 corpus models use `allowedActions`.**
> No fallback window, no codemod, no `BREAKING.md` entry needed.

### F5-R2 `computed[]` is warn-only and evaluated nowhere · **2 hr**

MEASURED: `ff4acba` folded `computed[]` into `recompute:` — a correct call (client/server CEL parity
is expensive and the round-trip has no performance pressure; only one row's cells are ever live).
But the template consumes `metadata.computed` **0 times**, and the validator only *warns*. A
declared expression nothing evaluates is a lie in the model.

> **RISK RETIRED (pre-checked): 0 of 27 corpus models declare `computed[]`.**
> Recommendation upgraded to **delete the field in DSL 2.0** — free, and consistent with 2.A's
> one-mechanism posture.

### F5-R3 The plan documents a `display` toggle that does not exist · **30 min**

`AGGREGATE_WORKBENCH_PLAN.md` §4 still shows `band.display: selected|all|paged`; `ff4acba` found it
was never implemented anywhere and closed it without code. **Corpus scanned 2026-07-27: 0 files in
`golden-ai-scenarios/**` or `knowledge/**` teach it** — so the drift is confined to that one
document. One-paragraph edit; no corpus rebuild.

### F5-R4 `selectorRef` owner-descoped — record the boundary · **30 min**

`7e1096e` descoped `selectorRef` unification, so `bandPickers` and `selectors[]` remain two
mechanisms by decision. Record in `docs/ACCEPTED_BOUNDARIES.md` — a boundary living only in a commit
message is invisible to everyone who did not write it.

---

# Part 6 — F6 · Coverage roadmap (gated on F1)

**Build nothing here until F1 proves a class recurs (≥2 apps, ≥2 screens).**

| ID | Item | Trigger | Effort |
|---|---|---|---|
| **F6-1** ★ | **`layoutSlot`** (ADR-0004 L5) — hand-authored region inside a generated screen, with its own `panel.json` (`slotOf`) | Any workbench needs one bespoke region | 3 days |
| F6-2 | Operator-console primitive | F1 confirms recurrence (6/13 in WmsOffice alone) | 2 wks |
| F6-3 | Dashboard primitive | ≥2 apps need charts | 2 wks |
| F6-4 | 3rd nesting level | A real app needs it — WmsOffice does **not** | 1 wk |
| F6-5 | Client-side CEL evaluator | `recompute:` proves too slow (`ff4acba` argues it will not) | 1–2 wks |

> **F6-1 is the keystone and the literal merge point.** A slot is a hand-written fragment inside a
> generated screen: it needs the contract to know its data wiring and provenance to survive a rename.
> F3's schema must reserve `"slotOf": "<panel>"` from day one.

---

# Part 7 — Sequencing, risk, DoD

## Order

```
F1  taxonomy  ★★                                    [1 day]   ← routes everything
     │
     ├─► F2.1 invocations catalog                   [2 days]  ⚠️ needs 2.A.0 (createConcept/updateConcept)
     │     └─► F2.2 bundle                          [1 day]
     │           ├─► F2.3 UI_CONTRACT + schema + prompt   [2 days]
     │           └─► F3  provenance — 3 producers   [4 days]
     │                 │   F3.2 generator (free)  F3.3 agent  F3.4 human
     │                 └─► F4 impact gate  ★★       [2 days]
     │                       └─► money demo: rename a field
     │
     └─► F5  workbench verify + residuals           [2.5 days]  (independent — run in parallel)
                                                     ≈ 3 weeks
```

**F5 is fully parallel to F1–F4** — different subsystems, no shared files.

## Cross-plan interactions

| Item | Interaction |
|---|---|
| **2.A.0** (`createConcept`/`updateConcept`) | F2.1's `intent` inference needs it. **Do 2.A.0 first.** |
| **2.A DSL 2.0** | F5-R1 (typed `allowedActions`) and F5-R2 (delete `computed[]`) are schema changes across 4 mirrors — **land them inside 2.A**, one codemod, one `BREAKING.md` entry |
| **T1.15** | Post-split, lifecycle validation lives in `LifecycleValidation.java` (272 lines) |
| **2.B.3** (`GeneratedCrudRuntimeSupport`) | Owns panel row ops — do not run it during F5-V.2 or F2.1 |
| **REG-48** | F5-V.2 step 11 is the aggregate-cascade re-check the fix never got |
| **CORE C-3** (durable demo) | Shares the async-202 story; best test of `execution.statusRoute` |

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| `invocations` drifts from real routes | **High** | The dual-tree path-assertion test (F2.1). Most important test in the plan |
| Only one source tree gets searched again | **High** | Same test. It is the automated form of the mistake made by hand |
| Bootstrapper inference wrong | **High** | `confirmed:false` default; unique-leaf matching; `unresolved` list |
| 12th catalog breaks metadata determinism | Medium | Sort by id; `LinkedHashMap` never multi-entry `Map.of`; byte-comparison test |
| `modelHash` duplicates an existing fingerprint | Medium | **Resolved 2026-07-28**: reused verbatim via new `RuntimeMetadataService.schemaFingerprint()`; documented that it only covers schema shape, not the other 6 bundle catalogs |
| Bundle leaks across permission boundaries | Low / **severe** | Compose existing filters (`fields`/`actions` only — the other 6 catalogs have no per-actor filter anywhere in the platform, so they carry no permission boundary to leak); assert two roles differ |
| Live re-verify finds a 150-commit regression | Medium | That is the point. File a REG row |
| F1 becomes a naming exercise | Medium | The ≥2/≥2 rule; no candidate without evidence |
| The false fork persists in planning | **High** | Apply Part 0.5's replacement block **first** |

## Definition of done

**F1** — every screen in all five official apps classified; each class names its primitive or is
`hand-written → contract`; candidates carry ≥2/≥2 evidence; README limitations made precise.

**F2** — 12 catalogs, other 11 byte-identical, deterministic; **every path asserted against both
source trees**; `preferred`/`prefer` correct for `medium-expense-approval`; bundle carries
`modelHash` and permission-filters `fields`/`actions` (the only two catalogs with any per-actor
filter in the platform — the other six are unfiltered pass-through, by design, not oversight);
bundle arrays equal per-endpoint output; `UI_CONTRACT.md` states the multi-route reality and
202/async.

**F3** — one manifest schema, three producers; **every generated surface emits one automatically**;
all 13 WmsOffice screens have one, ≥3 confirmed by hand; `slotOf` reserved.

**F4** — `--calibrate` passes both directions; wired into the knowledge gate; **a real field rename
names the exact screens that break**.

**F5** — `EXECUTION_TREES.md` §2.C/§2.D replaced; `AGGREGATE_WORKBENCH_PLAN.md` has a STATUS header;
both aggregates re-verified live 11/11 incl. step 11; `allowedActions` typed and validated;
`computed[]` resolved; no doc or corpus teaches the phantom `display` toggle; picker coexistence
recorded in `ACCEPTED_BOUNDARIES.md`.

---

*Supersedes `2C_CONTRACT_PATH_PLAN.md` + `2D_AGGREGATE_WORKBENCH_PLAN.md`.
Companions: `docs/architecture/AGGREGATE_WORKBENCH_PLAN.md` (authoritative workbench record) ·
`docs/adr/ADR-0004-aggregate-workbench.md` · `docs/adr/ADR-0005-auto-panel-patterns.md` ·
`docs/DSL2_AND_DECOMPOSITION_PLAN.md` (F5-R1/R2 belong in 2.A) · `docs/FLOWS.md` (why writes are
async) · `docs/EXECUTION_TREES.md` (§2.C+§2.D to be replaced per Part 0.5).
Staged working scripts: `2c-staging/bootstrap-infer-panel-provenance.py`,
`2c-staging/check-panel-provenance-impact.py`.*
