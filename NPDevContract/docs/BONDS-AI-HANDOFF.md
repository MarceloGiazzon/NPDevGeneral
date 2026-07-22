# NPDev Bonds — AI Handoff Digest

> **Audience:** an AI agent (Cursor) continuing the Bonds/Relations feature.
> **Status at handoff:** Phases 0–2 complete and build-verified. Phases 3–9 remain.
> **Companion docs:** [`BONDS.md`](BONDS.md) (design + full roadmap), `MODEL-CONTRACT.md` (DSL contract).
> Read this file first, then `BONDS.md` for the phase-by-phase roadmap detail.

---

## 0. How to use this document

This is a precise continuation brief. It restates the vision, every locked decision, the
exact pipeline seams, what is already implemented (with file paths + tests), where work
stopped, and what each remaining phase must do. Treat the **Locked Decisions** (§3) as
binding — they were agreed with the project owner; do not relitigate them, just implement.

When you finish a phase: update the matching "Phase N" section in [`BONDS.md`](BONDS.md)
to **DONE** with the files touched + the test that proves it, and keep this digest's
§6 "Status board" in sync.

---

## 1. Global vision (why bonds exist)

NPDev is a human+AI platform that generates real software from a declarative model
(`model.json`). Guiding sentence: *"Simple by default. Deep when needed. Personalizable
everywhere. Truthful always. Restrictive only at release time."*

A **bond** is NPDev's first-class expression of a relationship between concepts — a
foreign key, but made model-native and **truth-aware**. The goal of the feature is:
a relationship declared once in the model becomes a **real, enforced relation at every
altitude** — editor canvas, DSL contract, database constraint, runtime behaviour — with
truth-level integrity layered on top.

### Truth model (T0–T6)
Every concept carries a **truth level**: `T0 Idea → T1 Declared → T2 Generated →
T3 RunsLocally → T4 Tested → T5 EvidenceBacked → T6 ReleaseApproved`. Truth never blocks
creation; it constrains *release claims*. The bond-specific rule is **"no upward edges"**:
a bond may not point at a concept whose truth level is below the bond's source — a high-truth
concept may not depend on a less-true one. Today this is a *warning* (non-blocking); a
future release gate (Phase 6) elevates it to an error.

---

## 2. Vocabulary — "Id binds Id"

Connectability is **lexical**: a field participates in a bond iff its name ends in `Id`.

| Name pattern   | Role             | Example              | Endpoint?     |
|----------------|------------------|----------------------|---------------|
| `id`           | synthetic anchor | `Product.id`         | yes (target)  |
| `<localKey>Id` | natural anchor   | `Product.skuId`      | yes (target)  |
| `<concept>Id`  | port             | `Invoice.productId`  | yes (source)  |
| no `Id` suffix | value            | `date`, `quantity`   | never         |

- **Anchor** = a connectable target key. Marked `"connectable": "anchor"`; the `id` field is
  an implicit anchor. An anchor must be `unique` (or be the id) and non-null.
- **Port** = a `type: "reference"` field. Chooses which anchor to bind with `via:`
  (defaults to the target's `id`) and its integrity with `onDelete:`
  (`restrict` | `cascade` | `nullify`; default `restrict`).
- **Value** = any non-`Id` field; never a bond endpoint.

A bond always relates an `Id` field on the source to an `Id` field on the target. The
naming rule is documentation/lint guidance, **not** hard-enforced (avoids breaking existing
models).

### Authoring example
```json
{ "name": "Product", "truthLevel": "T3", "fields": [
    { "name": "id",    "type": "uuid",   "id": true },
    { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" },
    { "name": "name",  "type": "string" }
]}

{ "name": "Invoice", "fields": [
    { "name": "id",         "type": "uuid", "id": true },
    { "name": "quantity",   "type": "int" },
    { "name": "customerId", "type": "reference", "reference": { "target": "Customer" } },
    { "name": "productId",  "type": "reference",
      "reference": { "target": "Product", "via": "skuId", "onDelete": "restrict" } }
]}
```
`customerId` omits `via` → binds `Customer.id` (UUID column). `productId` binds the business
key `Product.skuId`, so the SKU value (a `VARCHAR`) travels with the invoice row.

### Three altitudes (one fact, expressed three ways)
| Altitude | Anchor                | Port                                   |
|----------|-----------------------|----------------------------------------|
| Editor   | boundary docking point| edge origin (only `Id` fields)         |
| DSL      | `connectable: anchor` | `reference: { target, via?, onDelete? }` |
| Database | `UNIQUE` index        | N:1 → FK column; N:M → junction table   |

"Available to connection" at the DB level **is** "has a unique constraint."

---

## 3. Locked decisions (binding — do not relitigate)

1. **Bonds are pure pointers** — no link-carried data, no association/join *concepts*.
   (Physical N:M still needs a junction *table*, but it is auto-synthesized and never
   authored — see Phase 4.)
2. **Truth-aware integrity, concept-level** — truth lives on the concept (not per-field);
   a bond inherits its source concept's truth. "No upward edges."
3. **Cardinality**: default **N:1**; unique port → **1:1** (enforced); `multiple:true` →
   **1:N / N:M**. Inverse is **derived** (queried from the far side), never a second stored
   pointer.
4. **`onDelete` default `restrict`**; `cascade`/`nullify` opt-in. `onUpdate: cascade` auto
   only for `via` a mutable natural key; `via id` bonds are cascade-free.
5. **Scope**: bonds target concepts in the **same compiled model** (incl. resolved
   fragments/packs). Cross-pack truth propagation deferred.
6. **Defaults**: anchor ⇒ unique + non-null; `via` defaults to `id`, never auto-guessed;
   self/cyclic refs allowed within a truth level; existing `type: reference` fields migrate
   as N:1 / `via id` / `onDelete restrict`.
7. **Integrity at BOTH layers** — **DB is the source of truth** (real `FOREIGN KEY`
   constraints); the **app maps the DB violation to a clean domain error** (app side =
   Phase 3).

---

## 4. Pipeline map (where a bond flows, with exact files)

All under repo root `d:\WorkSpace\NPDev\NPDev_General`.

```
model.json
  │  (1) JSON Schema validation         NPDevContract/dsl/src/main/resources/schema/model.schema.json  ← classpath copy the validator loads
  ▼
JsonModelParser                         NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java
  │  parses connectable / via / onDelete / truthLevel
  ▼
AST (FieldAst, ReferenceSemanticsAst,   NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/*.java
     ConceptAst/EntityAst, TruthLevel)
  ▼
ModelResolver (specialization merge)    NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/resolution/ModelResolver.java
  ▼
SemanticValidator                       NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java
  │  anchor rules, via resolution, onDelete enum, truth-edge warning
  ▼
ModelCompiler                           NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/ModelCompiler.java
  ▼
Compiled model (CompiledField,          NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/*.java
   CompiledReferenceSemantics,
   CompiledConcept/CompiledEntity)
  │  ⇄ canonical JSON round-trip
  │     writer CompiledModelCanonicalJson.java   reader CompiledModelCanonicalJsonReader.java
  ▼
Generator emitters                      NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/*.java
  │  FlywayEmitter (DB DDL/FK)  EntityEmitter / DtoEmitter / RepositoryEmitter / ServiceEmitter (Phase 3)
  │  BusinessUiEmitter (picker UI)  MetadataManifestAssetEmitter (catalog)
  ▼
Generated app (Spring + H2/Postgres + Flyway)  → boots, runs migration, enforces FKs
```

### Schema copies — KEEP ALL FOUR ALIGNED
Bond/truth keys must exist identically in **all four**:
- `NPDevContract/dsl/src/main/resources/schema/model.schema.json` ← the one the validator loads at runtime
- `NPDevContract/dsl/resources/Schemas/model.schema.json` ← "canonical" copy (alignment test compares against this)
- `NPDevContract/schemas/authoring/model.schema.json`
- `NPDevContract/schemas/model.schema.json`

Keys added so far: `field.connectable` (enum `["anchor"]`), `referenceDefinition.via`
(string), `referenceDefinition.onDelete` (enum restrict/cascade/nullify), `concept.truthLevel`
(enum `T0..T6`). Schemas use `additionalProperties: false`, so any new key MUST be added
to the classpath copy or parsing rejects it.

---

## 5. What is DONE (build-verified)

### Phase 0 — Contract + validation + truth levels
- **Schema** (all 4 copies): `connectable`, `via`, `onDelete`, `truthLevel`.
- **AST**: `FieldAst.connectable`; `ReferenceSemanticsAst.via`/`.onDelete`;
  `TruthLevel` enum (`ast/TruthLevel.java`, T0–T6 ranked, `code()`/`label()`,
  tolerant `fromString`/`fromStringOrDefault`); `EntityAst.truthLevel` (default
  `T1_DECLARED`) inherited by `ConceptAst`; threaded through `ModelResolver`
  (sanitize/merge).
- **Parser**: reads the four keys.
- **SemanticValidator**:
  - `connectable` must be `"anchor"`; an anchor field must be `unique` or the id.
  - `via` must resolve to a connectable anchor on the target.
  - `onDelete` validated (schema also enforces the enum).
  - `validateBondTruthEdge(...)`: emits a **warning** "…no upward truth edges" when a
    bond's source concept is more-true than its target (non-blocking; release gate
    elevates later).
- **Tests** (`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/`):
  `BondSemanticsSupportTest`, `TruthLevelSupportTest`, `ReferenceSemanticsSupportTest`.

### Phase 1 — Compile propagation (un-drop bonds at compile)
- `CompiledReferenceSemantics`: added `via`, `onDelete` (additive constructor).
- `CompiledField`: added `connectable`.
- `CompiledEntity`/`CompiledConcept`: added `truthLevel` (String code, default `"T1"`).
- `ModelCompiler`: threads all four into the compiled model.
- `CompiledModelCanonicalJson` (writer) + `CompiledModelCanonicalJsonReader` (reader):
  serialize/parse symmetrically (always-emit style, matching the file).
- **Test**: `BondCompilePropagationTest` proves `via`/`onDelete`/`connectable`/`truthLevel`
  survive **parse → compile → canonical JSON → read-back**.
- **Deliberately deferred**: `ResolvedModelCanonicalJson` and `CompiledMetadataCanonicalJson`
  are one-way serializers (no reader); left unchanged to avoid digest churn. Extend them in
  Phase 2/7 where their consumers actually read bonds. (The metadata manifest's `references`
  catalog is the natural place to surface `via`/`onDelete` for the editor/runtime.)

### Phase 2 — DB schema integrity (DDL / Flyway)
File: `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/FlywayEmitter.java`
- Final FK pass after all tables/indexes:
  PostgreSQL-safe conditional `DO $$ ... pg_constraint ... END $$;` blocks whose body
  runs `ALTER TABLE child ADD CONSTRAINT fk_<t>_<col> FOREIGN KEY (col)
  REFERENCES target(anchor) ON DELETE <RESTRICT|CASCADE|SET NULL>`.
  `onDelete` mapping: `cascade`→CASCADE, `nullify`→SET NULL, else→RESTRICT.
- FK **column type bound to the resolved anchor type** (helper `columnType(...)` +
  `resolveAnchorField(...)`): `via skuId` (string) → `VARCHAR(255)`; default `id` → `UUID`.
- `connectable: anchor` (unique) → `UNIQUE` index (referenceable); 1:1 (unique port) →
  unique FK column via the existing unique-index pass.
- Only **declared references** get FKs (`isDeclaredReference`); the `*Id`-uuid heuristic
  columns keep their plain index, no FK. `multiple` (N:M) skipped — Phase 4.
- **Test**: `FlywayEmitterBondsTest` (FK shape, anchor-typed column, ON DELETE, anchor
  unique index). The packaged-app runtime-proof test boots a real H2 app and runs the
  migration, so the FK DDL is proven to **execute** (live insert-rejection/cascade is the
  explicit Phase 9 proof).

### Incidental fix (not bonds, but required for green)
`NPDevKernel/adapters/flow-compiled/build.gradle` — added
`implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.2'`. Reason: pre-existing
uncommitted pack/fragment WIP added public `JsonModelParser.parse(JsonNode)` overloads;
`flow-compiled` calls that parser API and javac then needs Jackson on its compile classpath
(dsl exposes Jackson as `implementation`, so it doesn't leak). Unrelated to bonds.

---

## 6. Status board

| Phase | Title                                   | State | Proof |
|-------|-----------------------------------------|-------|-------|
| 0 | Contract + validation + truth levels        | ✅ DONE | Bond/Truth/Reference support tests |
| 1 | Compile propagation                         | ✅ DONE | `BondCompilePropagationTest` |
| 2 | DB schema integrity (FK DDL)                | ✅ DONE | `FlywayEmitterBondsTest` + runtime-proof boot |
| 3 | Persistence + DTO + repository wiring       | ✅ DONE | `BondJavaEmitterTest` + runtime support compile |
| 4 | N:M + junction synthesis                    | ✅ DONE | `BondSemanticsSupportTest`, `FlywayEmitterBondsTest`, `BondJavaEmitterTest` |
| 5 | Runtime integrity: onUpdate, restrict, inverse | 🟨 PARTIAL | `ON UPDATE CASCADE` DDL + generated bond finder routes |
| 6 | Truth-aware release gate                    | ✅ DONE | `ReleaseGateValidatorTest` |
| 7 | Editor: relationship canvas                 | ✅ DONE | `npm test`, `npm run build` in `NPDevEditor/ui-react` |
| 8 | Migration, tooling, docs                    | ✅ DONE | `npdev inspect bonds`, docs updated |
| 9 | End-to-end proof on a sample app            | ⬜ TODO | — |

**Critical path:** Phases 1–4 and 6–8 now have code and focused proof. Phase 5 still
needs full truth-side demotion/delete restriction as a runtime truth event. Phase 9 remains
the live sample-app proof.

---

## 7. Where work stopped & why

Current handoff point: Phases 3, 4, 6, 7, and 8 are implemented with focused tests.
Phase 5 is partially implemented: natural-key bonds emit `ON UPDATE CASCADE`, DB
integrity failures map to clean domain errors, and scalar bond finder routes provide
derived inverse lookup. The remaining Phase 5 gap is truth-side runtime restriction for
demoting/deleting depended-upon targets. Phase 9 still needs the live generated-app proof
with evidence stored outside the repo.

---

## 8. Remaining phases (what each must do)

> Full detail is in [`BONDS.md`](BONDS.md) under "Roadmap to full support". Summary here.

### Phase 3 — Persistence + DTO + repository wiring — DONE
- Added shared `BondModelSupport`; entity/DTO/service emitters use resolved anchor Java
  types for scalar bonds and keep loose ID columns.
- `GeneratedCrudRuntimeSupport` validates references by resolved anchor and maps DB
  FK/unique failures to structured domain errors.
- Generated scalar bond finder route: `/api/<source>/by/<field>/{value}`.

### Phase 4 — N:M (multiple pointers) + junction synthesis — DONE
- `multiple:true` validates; generated DDL creates deterministic junction tables and FKs.
- Generated explicit set endpoints add/remove/list/replace members; no `@ManyToMany`.

### Phase 5 — Runtime integrity: onUpdate, restrict, inverse — PARTIAL
- Natural-key FKs emit `ON UPDATE CASCADE`.
- DB restrict/cascade/nullify failures map cleanly at service boundaries.
- Scalar bond finder routes provide derived inverse lookup.
- Remaining: truth-side runtime restriction for demoting/deleting depended-upon targets.

### Phase 6 — Truth-aware release gate — DONE
- Added `ReleaseGateValidator`, `ValidationLayer.RELEASE_GATE`, truth-closure blocking,
  and v1 evidence-path hook.

### Phase 7 — Editor: relationship canvas — DONE (incremental v1)
- Existing reference designer exposes `via`, `onDelete`, and `multiple`.
- Semantic graph shows truth levels and upward-edge warnings.
- Metadata reference catalog carries bond/truth fields.

### Phase 8 — Migration, tooling, docs — DONE
- Added `npdev inspect bonds --model <model.json>` graph/risk dump.
- Updated `MODEL-CONTRACT.md` and this handoff.

### Phase 9 — End-to-end proof on a sample app — depends on 2–5
- Take a verified FinalApp (e.g. Claude Support Desk under AppGen), add a real N:1 bond
  (`via` natural key + `onDelete`), generate, build, run.
- **Evidence (store OUTSIDE the source repo):** FK rejects a violating row; cascade/restrict
  behaves; the business key is stored on the row; inverse query returns dependents.

---

## 9. Build & test instructions (multi-module gradle — important)

There is **no single root build** for these modules; each area has its own Gradle build
that maps sibling projects by path.

### Contract (dsl) module
```bash
cd d:/WorkSpace/NPDev/NPDev_General/NPDevContract/dsl
./gradlew test --console=plain
# single test:
./gradlew test --tests "com.npdev.dsl.v1.BondCompilePropagationTest"
```

### Generator module (depends on dsl via project(':dsl') mapped in NPDevGenerator/settings.gradle)
```bash
cd d:/WorkSpace/NPDev/NPDev_General/NPDevGenerator
./gradlew :generator:test --console=plain
# single test:
./gradlew :generator:test --tests "com.npdev.generator.emitters.FlywayEmitterBondsTest"
```
The generator build recompiles `:dsl` from source, so contract changes are picked up.
The runtime-proof test (`TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest`) **forks
a nested Gradle build** of a generated app and boots it on H2 — it is the closest thing to
an integration test and takes ~4–6 min.

### Listing failures from XML (PowerShell-friendly bash)
```bash
for f in generator/build/test-results/test/*.xml; do \
  grep -q '<failure\|<error' "$f" 2>/dev/null && basename "$f" | sed 's/TEST-//;s/.xml//'; done
```

---

## 10. Known pre-existing issues / landmines (NOT caused by bonds)

1. **4 dsl tests fail on this branch independent of bonds**:
   `StructuralSchemaAssetConformanceTest` ("Classpath model schema copy must stay aligned
   with canonical model schema"), `ContractSurfaceSupportTest`, `DslGrammarEvolutionTest`,
   `LegacySchemaRejectionTest`. Cause: pre-existing uncommitted **pack/fragment** WIP — the
   classpath schema copy has `fragments`/`packs`/`localModelRef`/`packRef` that the canonical
   copy lacks. Bond/truth keys are mirrored across both copies and are **not** the cause.
   Do not "fix" these as part of bonds.
2. **Substantial pre-existing uncommitted WIP** is in the working tree (pack/fragment
   resolution, generator scaffolding: `GeneratorMain`, `GeneratorFacade`, `BusinessUiEmitter`,
   `MetadataManifestAssetEmitter`, mustache templates, persistence adapters, `KernelRunner`,
   etc.). When attributing a test regression, `git stash -u` gives **pure HEAD**, which also
   removes that WIP — so a pass on pure-HEAD does NOT prove your change is the culprit. Bisect
   carefully or reason from the actual error.
3. **`JsonModelParser` now exposes public `parse(JsonNode)`** (pre-existing WIP). Any module
   that calls the parser needs Jackson on its compile classpath (dsl declares Jackson as
   `implementation`, so it does not leak transitively). This bit `flow-compiled` (fixed) and
   may bite other consumers if added later.

---

## 11. Conventions & discipline (follow these)

- **Build outputs** go to `D:\WorkSpace\NPDev\Build`, **never** inside the source repo.
- **Evidence/artifacts** for runs go OUTSIDE the repo. When finishing an app, hand off a
  Property/Value table with COMPLETE ABSOLUTE paths (physical paths, URLs, DB access,
  scripts, commands).
- **Never `git add .`**; stage explicit paths. Don't commit/push unless asked. End commit
  messages with the required `Co-Authored-By` line.
- **No regex/blind Java patches** — read the file, make exact edits.
- **Additive constructors pattern**: every Compiled*/Ast class here keeps existing
  constructors and adds a new overload (existing one delegates with defaults), so existing
  call sites never break. Follow this for any new field.
- **Canonical JSON style is always-emit** (`safe(...)` → `""` for null). Match it; do not
  switch to omit-when-default (would desync writer/reader and existing fixtures).
- **Small bounded steps**, build-verify each, report failures faithfully with the real
  output. Keep `BONDS.md` + this digest in sync as phases land.

---

## 12. Quick reference — files touched in Phases 0–2

Contract (`NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/`):
- `ast/TruthLevel.java` (new), `ast/FieldAst.java`, `ast/ReferenceSemanticsAst.java`,
  `ast/EntityAst.java`, `ast/ConceptAst.java`
- `parser/JsonModelParser.java`, `resolution/ModelResolver.java`,
  `validation/SemanticValidator.java`, `compiler/ModelCompiler.java`
- `compiled/CompiledField.java`, `compiled/CompiledReferenceSemantics.java`,
  `compiled/CompiledEntity.java`, `compiled/CompiledConcept.java`,
  `compiled/CompiledModelCanonicalJson.java`, `compiled/CompiledModelCanonicalJsonReader.java`

Schemas (all four): `dsl/src/main/resources/schema/model.schema.json`,
`dsl/resources/Schemas/model.schema.json`, `schemas/authoring/model.schema.json`,
`schemas/model.schema.json`

Tests (`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/`):
`BondSemanticsSupportTest`, `TruthLevelSupportTest`, `BondCompilePropagationTest`
(+ existing `ReferenceSemanticsSupportTest`, `CompiledModelCanonicalJsonReaderTest`).

Generator (`NPDevGenerator/generator/src/`):
- `main/java/com/npdev/generator/emitters/FlywayEmitter.java`
- `test/java/com/npdev/generator/emitters/FlywayEmitterBondsTest.java` (new)

Kernel (incidental, non-bond): `NPDevKernel/adapters/flow-compiled/build.gradle`.

Docs: `NPDevContract/docs/BONDS.md` (design + roadmap), this file.
