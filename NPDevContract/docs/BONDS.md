# NPDev Bonds — connections between concepts (v1)

A **bond** is a pure directional pointer from a *port* field on one concept to an
*anchor* field on another. It is the NPDev expression of a foreign key, made
first-class in the model and (in later increments) truth-aware.

## Vocabulary — "Id binds Id"

Connectability is **lexical**: a field participates in a bond if and only if its
name ends in `Id`.

| Name pattern        | Role    | Example              | Endpoint? |
|---------------------|---------|----------------------|-----------|
| `id`                | synthetic anchor | `Product.id`   | yes (target) |
| `<localKey>Id`      | natural anchor   | `Product.skuId`| yes (target) |
| `<concept>Id`       | port             | `Invoice.productId` | yes (source) |
| no `Id` suffix      | value            | `date`, `quantity`  | never |

A bond always relates an `Id` field on the source to an `Id` field on the target
(**Id binds Id**). The naming convention is documentation/lint guidance; it is not
hard-enforced in v1 to avoid breaking existing models.

## Authoring surface (DSL)

- **Anchor**: mark a target key with `"connectable": "anchor"`. The synthetic
  `id` field is an implicit anchor. An anchor must be `unique` (or be the id).
- **Port**: a `type: "reference"` field. It picks which anchor to bind with
  `via:` (defaults to the target's `id`), and its integrity behaviour with
  `onDelete:` (`restrict` | `cascade` | `nullify`; default `restrict`).

```json
{ "name": "Product", "fields": [
    { "name": "id",    "type": "uuid",   "id": true },
    { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" },
    { "name": "name",  "type": "string" }
]}

{ "name": "Invoice", "fields": [
    { "name": "id",        "type": "uuid", "id": true },
    { "name": "quantity",  "type": "int" },
    { "name": "customerId", "type": "reference", "reference": { "target": "Customer" } },
    { "name": "productId",  "type": "reference",
      "reference": { "target": "Product", "via": "skuId", "onDelete": "restrict" } }
]}
```

`customerId` omits `via` → binds `Customer.id`. `productId` binds the business key
`Product.skuId`, so the SKU travels with the invoice row instead of an opaque uuid.

## Three altitudes

| Altitude | Anchor | Port |
|----------|--------|------|
| Editor   | boundary docking point | edge origin (only `Id` fields) |
| DSL      | `connectable: anchor`  | `reference: { target, via?, onDelete? }` |
| Database | `UNIQUE` index         | N:1 → FK column; N:M → auto junction table |

"Available to connection" at the DB level **is** "has a unique constraint" — the
same fact expressed at two heights.

## Decisions locked (v1)

1. **Bonds are pure pointers** — no link-carried data, no association/join concepts.
2. **Truth-aware integrity, concept-level** — a bond inherits its source concept's
   truth; promotion forbids upward edges (a bond may not target a lower truth level).
3. **Cardinality**: default N:1; unique port → 1:1; `multiple:true` → 1:N / N:M.
   Inverse is derived (queried from the far side), never a second stored pointer.
4. **`onDelete` default `restrict`**; `onUpdate: cascade` auto only for `via` a
   mutable natural key; `via id` bonds are cascade-free.
5. **Scope**: bonds target concepts in the same compiled model (incl. resolved
   fragments/packs). Cross-pack truth propagation deferred.
6. **Defaults**: anchor ⇒ unique + non-null; `via` defaults to `id`, never
   auto-guessed; self/cyclic refs allowed within a truth level; existing
   `type: reference` fields migrate as N:1 / `via id` / `onDelete restrict`.

## Implementation status

**Done (contract layer, build-verified):**
- Schema: `connectable` on field; `via` + `onDelete` on `referenceDefinition`
  (all four schema copies kept aligned).
- AST: `FieldAst.connectable`, `ReferenceSemanticsAst.via` / `.onDelete`
  (additive constructors; existing call sites unchanged).
- Parser: parses the three keys.
- `SemanticValidator`: anchor must be unique/id; `via` must resolve to a
  connectable anchor on the target; `onDelete` value validated (schema also
  enforces the enum at parse time).
- Tests: `BondSemanticsSupportTest` (positive + 2 negative).

**Done (truth levels, build-verified):**
- `TruthLevel` enum (T0–T6, ranked, code+label, tolerant `fromString`).
- `EntityAst.truthLevel` (default T1 Declared) carried by `ConceptAst`; threaded
  through the resolver (sanitize/merge) so it survives specialization.
- Schema: `concept.truthLevel` enum (T0..T6) in all four copies.
- Parser reads `truthLevel`.
- **Truth-edge invariant** in `SemanticValidator.validateBondTruthEdge`: a bond
  whose source concept is *more* true than its target raises a **warning**
  ("no upward truth edges"). It is a warning, not an error, so it never blocks
  creation (truth is restrictive only at release). A release gate can later
  elevate it to a hard error.
- Tests: `TruthLevelSupportTest` (parse + default, upward warns, downward clean).

**Deferred (own increments):**
- **Release-gate elevation** — promote the truth-edge warning to a blocking error
  inside a release/promotion phase (the validator has no release-gate hook yet).
- **Earned vs declared truth** — today `truthLevel` is an authored/claimed level;
  a release gate should verify high levels (Tested/EvidenceBacked) against actual
  evidence rather than trusting the declaration.
- **Compiled model + canonical JSON** — thread `via`/`onDelete`/`connectable`
  through `CompiledField` / `CompiledReferenceSemantics` and the canonical
  reader/writer so the generator can consume them.
- **DB generator** — anchor → unique index; N:1 port → FK column; N:M → junction.
- **Editor** — port/anchor/value rendering as a relationship canvas.

## Roadmap to full support

Goal: a bond declared in the model becomes a **real, enforced relation** at every
altitude (DB integrity, runtime behaviour, editor), with truth-aware release gating.
Phases are ordered by dependency; each ends with a build-verified exit criterion and
out-of-repo evidence where it touches a running app. Effort: S ≤ ½ day, M ≈ 1–2 days,
L ≈ 3–5 days.

### Phase 0 — Contract + validation (DONE)
Schema, AST, parser, semantic validation, truth levels, truth-edge warning. Tested.

### Phase 1 — Compile propagation (un-drop bonds at the compile boundary) — **DONE**
*Was the blocker for everything physical: `via`/`onDelete`/`connectable`/`truthLevel`
used to be discarded at compile.*
- `CompiledReferenceSemantics`: added `via`, `onDelete` (additive ctor). ✅
- `CompiledField`: added `connectable`. `CompiledEntity`/`CompiledConcept`: added
  `truthLevel` (String code, default `"T1"`). ✅
- `ModelCompiler`: threads all four through. ✅
- `CompiledModelCanonicalJson` (writer) + `CompiledModelCanonicalJsonReader`
  (reader): serialize/parse symmetrically. ✅
- **Exit met:** `BondCompilePropagationTest` proves `via`/`onDelete`/`connectable`
  and concept `truthLevel` survive parse → compile → canonical JSON → read-back.
  Full suite: only the 4 pre-existing fragments/packs schema-alignment failures.
- **Not done (folded into Phase 2/7):** `ResolvedModelCanonicalJson` and
  `CompiledMetadataCanonicalJson` are one-way serializers consumed by the
  generator/manifest; left unchanged here to avoid digest churn, extended when
  those consumers actually read bonds. The compiled-model round-trip (the Phase 1
  exit criterion) is complete.

### Phase 2 — DB schema integrity (DDL / Flyway) — **DONE**
*Turned the "indexed UUID column" into a real foreign key.*
- `FlywayEmitter`: N:1/1:1 ports now emit, in a final FK pass after all tables/indexes,
  PostgreSQL-safe conditional `DO $$ ... pg_constraint ... END $$;` blocks whose body
  runs `ALTER TABLE child ADD CONSTRAINT fk_<t>_<col> FOREIGN KEY (col)
  REFERENCES target(anchor) ON DELETE <RESTRICT|CASCADE|SET NULL>`. ✅
- FK **column type bound to the resolved anchor's type** (`via skuId` string → `VARCHAR(255)`,
  default-`id` → `UUID`). ✅
- `connectable: anchor` (unique) → `UNIQUE` index so non-id anchors are referenceable;
  unique port (1:1) → unique FK column via the existing unique-index pass. ✅
- N:M (`multiple`) skipped (junction is Phase 4); only declared references get FKs (the
  `*Id`-uuid heuristic columns keep their plain index, no FK).
- **Exit met:** `FlywayEmitterBondsTest` asserts FK shape + anchor-typed column + ON
  DELETE + anchor unique index. The packaged-app runtime-proof test boots a real H2 app
  and runs the generated migration, so the FK DDL is proven to execute. Live
  insert-rejection / cascade is the explicit Phase 9 proof.
- **Decision (locked):** integrity at **both** — DB is source of truth (real FKs); the
  **app maps the violation to a clean domain error in Phase 3**.
- **Incidental fix:** pre-existing pack/fragment WIP had added public
  `JsonModelParser.parse(JsonNode)` overloads, making `flow-compiled` need Jackson on its
  compile classpath; added `jackson-databind` to `flow-compiled/build.gradle` (it
  genuinely uses that parser API). Unrelated to bonds; unblocked the runtime proof.

### Phase 3 — Persistence + DTO + repository wiring — **DONE**
*Typed FK columns now flow through generated Java layers.* Depends on Phase 2.
- Added `BondModelSupport` as the shared generator resolver for target, `via` anchor,
  effective Java/SQL type, cardinality, and delete/update policies.
- `EntityEmitter`, `DtoEmitter`, and `ServiceEmitter` type scalar reference fields by
  the resolved anchor; pure pointers remain loose ID columns, not JPA relations.
- `GeneratedCrudRuntimeSupport` validates references against the resolved anchor instead
  of assuming UUID/id, and generated services map DB FK/unique failures to structured
  `InvariantViolationException` diagnostics.
- Scalar bond finders emit as `/api/<source>/by/<field>/{value}` and service
  `listBy<Field>` methods, giving a derived inverse query without storing a reverse edge.
- **Proof:** `BondJavaEmitterTest`, `FlywayEmitterBondsTest`.

### Phase 4 — N:M (multiple pointers) + junction synthesis — **DONE**
*Pure-pointer many-to-many; the link still carries no data.* Depends on Phase 3.
- The semantic validator now accepts `reference.multiple=true`.
- `FlywayEmitter` synthesizes deterministic `<source_table>_<port_column>` junction
  tables with typed source/target columns, composite PK, indexes, and real FKs.
- Generated entity/DTO CRUD omits N:M ports as scalar fields; generated services and
  controllers expose explicit add/remove/list/replace set operations.
- **Proof:** `BondSemanticsSupportTest`, `BondJavaEmitterTest`, `FlywayEmitterBondsTest`.

### Phase 5 — Runtime integrity: onUpdate, restrict, inverse — **PARTIAL**
*Behaviours that aren't pure DDL.* Depends on Phase 2/3.
- Natural-key bonds now emit `ON UPDATE CASCADE` in generated FK DDL.
- DB `restrict`/`cascade`/`nullify` violations are mapped at the generated service
  boundary; scalar bond finder routes provide derived inverse lookup.
- Remaining deeper work: truth-side demotion/delete restriction as a runtime truth event.

### Phase 6 — Truth-aware release gate (hardening) — **DONE**
*Where bonds stop being ORM and become NPDev.* Depends on Phase 1.
- Added `ReleaseGateValidator`, a promotion-time validation entrypoint distinct from
  authoring validation.
- Authoring still warns for upward truth edges; release validation blocks promotion when
  reachable bond closure is below the requested truth level.
- T4+ promotion requires evidence through an `EvidenceProvider`; v1 includes an
  evidence-path provider for existing NPDev proof artifacts.
- **Proof:** `ReleaseGateValidatorTest`.

### Phase 7 — Editor: relationship canvas — **DONE (incremental v1)**
*The human surface.* Depends on Phase 1 (consumes the contract).
- Existing React authoring references UI now exposes `via`, `onDelete`, and `multiple`.
- Editor model types include `truthLevel`, `connectable`, `unique`, and bond semantics.
- Semantic graph displays concept truth levels and flags upward truth edges.
- Metadata reference catalogs include `via`, `onDelete`, anchor field/type, cardinality,
  source/target truth levels, and upward-edge state.
- **Proof:** `npm test`, `npm run build` in `NPDevEditor/ui-react`.

### Phase 8 — Migration, tooling, docs — **DONE**
*Don't break the verified FinalApps.* Cross-cutting.
- `npdev inspect bonds --model <model.json>` dumps relation graph facts and migration
  risks, including dangling-FK precheck reminders and upward truth edges.
- `MODEL-CONTRACT.md` documents bonds, N:M, release gate, and migration prechecks.

### Phase 9 — End-to-end proof on a sample app — **M**
*The honest finish line.* Depends on Phases 2–5.
- Take one verified FinalApp (e.g. Claude Support Desk), add a real N:1 bond
  (`via` natural key + `onDelete`), generate, build, run.
- Evidence (out-of-repo): FK rejects a violating row; cascade/restrict behaves; the
  business key is stored on the row; inverse query returns dependents.

### Critical path & sequencing
```
Phase 1 ─┬─ Phase 2 ─ Phase 3 ─ Phase 4
         ├─ Phase 5
         ├─ Phase 6
         └─ Phase 7
Phases 8/9 ride alongside once 2–5 exist.
```
Phase 1 unblocks all physical work. The minimal "a bond is a real enforced FK"
milestone = **Phase 1 + Phase 2 + Phase 3** (N:1 with `via` + `onDelete`, enforced at
the DB, usable from Java). N:M (4), runtime niceties (5), release gating (6), and the
editor (7) layer on after.

### Open decisions to settle before coding each phase
- **Phase 2 (LOCKED):** integrity at **both** layers — **DB is the source of truth**
  (real `FOREIGN KEY` constraints), and the **app maps the DB violation to a clean
  domain error** (the app-side mapping lands in Phase 3).
- **Phase 3:** loose typed id column vs JPA-managed relation.
- **Phase 4:** explicit junction repository vs `@ManyToMany`.
- **Phase 6:** what counts as "evidence" for earned truth at each level.

## Notes for the next session

- Pre-existing WIP on branch `beta0-no-false-green-release-hardening`: the
  classpath schema copy (`dsl/src/main/resources/schema/model.schema.json`) has
  `fragments`/`packs`/`localModelRef`/`packRef` that the canonical copy
  (`dsl/resources/Schemas/model.schema.json`) lacks, so
  `StructuralSchemaAssetConformanceTest.canonicalSchemaCopiesStayAligned` and 3
  related legacy/grammar tests fail independently of bonds. Bond keys are mirrored
  in both copies and are not the cause.
