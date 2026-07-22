# NPDev DSL — Packs and Field Bonds: Deep Technical Analysis

> **Branch:** `beta0-no-false-green-release-hardening`  
> **Date:** 2026-06-14  
> **Scope:** Contract layer (AST/schema/compiled), Generator layer (emitters), Validation layer (SemanticValidator)  
> **Purpose:** Baseline for improvement work on both features

---

## Table of Contents

1. [Feature Overview](#1-feature-overview)
2. [Packs — Full Analysis](#2-packs--full-analysis)
   - 2.1 [What Packs Are](#21-what-packs-are)
   - 2.2 [Schema Contract](#22-schema-contract)
   - 2.3 [Resolution Pipeline](#23-resolution-pipeline)
   - 2.4 [What Works Correctly](#24-what-works-correctly)
   - 2.5 [Gaps and Issues](#25-gaps-and-issues)
3. [Field Bonds — Full Analysis](#3-field-bonds--full-analysis)
   - 3.1 [What Field Bonds Are](#31-what-field-bonds-are)
   - 3.2 [Schema Contract](#32-schema-contract)
   - 3.3 [Full Pipeline](#33-full-pipeline)
   - 3.4 [Validation Rules](#34-validation-rules)
   - 3.5 [Generator Behaviour](#35-generator-behaviour)
   - 3.6 [What Works Correctly](#36-what-works-correctly)
   - 3.7 [Gaps and Issues](#37-gaps-and-issues)
4. [Cross-Feature Interaction: Packs + Bonds](#4-cross-feature-interaction-packs--bonds)
5. [Improvement Action Items](#5-improvement-action-items)
   - 5.1 [Packs — Prioritised Actions](#51-packs--prioritised-actions)
   - 5.2 [Bonds — Prioritised Actions](#52-bonds--prioritised-actions)
6. [File Reference Map](#6-file-reference-map)

---

## 1. Feature Overview

| Feature | DSL Syntax | Purpose | Status |
|---|---|---|---|
| **Packs** | `packs: [{ "$ref": "path.json", "as": "alias" }]` | Reusable, versioned domain modules composed into app models | Contract + resolver done; tests partial |
| **Field Bonds** | `connectable: "anchor"` + `reference.via` + `reference.onDelete` | First-class referential integrity with natural-key support | Contract + validator + generator + DB DDL done; tests solid |

Both features are **unstaged** (in the working tree, not yet committed).

---

## 2. Packs — Full Analysis

### 2.1 What Packs Are

A **pack** is a named, versioned JSON file that groups concepts, domain types, capabilities, flows, and other model members for reuse across app models. A model imports packs via the `packs` array at the root. All concepts contributed by a pack are automatically namespaced to prevent name collisions.

**Authoring example (pack file):**

```json
{
  "$schema": "NPDevContract/schemas/pack.schema.json",
  "pack": "catalog",
  "version": "1.0",
  "dslVersion": "1.0.0",
  "concepts": [
    {
      "name": "Product",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" }
      ]
    },
    {
      "name": "Variant",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "productId", "type": "reference", "reference": { "target": "Product" } }
      ]
    }
  ],
  "domainTypes": [
    { "name": "Sku", "baseType": "string" }
  ]
}
```

**Consuming model example:**

```json
{
  "namespace": "my-app",
  "dslVersion": "1.0.0",
  "version": "1.0",
  "packs": [
    { "$ref": "packs/catalog.json" },
    { "$ref": "packs/billing.json", "as": "bill" }
  ],
  "concepts": [
    {
      "name": "Order",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "productId", "type": "reference", "reference": { "target": "catalog::Product" } }
      ]
    }
  ]
}
```

After resolution, the model contains concepts `catalog::Product`, `catalog::Variant`, and `Order`.

---

### 2.2 Schema Contract

**Pack file schema** — `NPDevContract/schemas/pack.schema.json`:

| Required fields | Optional arrays | Notes |
|---|---|---|
| `dslVersion` (const `"1.0.0"`) | `concepts`, `domainTypes`, `capabilities`, `customCapabilities` | `pack` field is the namespace identifier |
| `pack` (lowercase, no spaces) | `bindings`, `events`, `flows`, `orchestrationRules`, `orchestrations` | Validates with regex `^[a-z][a-z0-9_-]*$` |
| `version` | `queries`, `ruleProfiles`, `procedures`, `panels` | |
| | `metadata`, `fragments` | `fragments` is declared but **not resolved** — see Gap #1 |

**Model schema addition** — `NPDevContract/schemas/model.schema.json`:

- New `packs` array added at root level
- Each element is a `packRef`: `{ "$ref": "relative/path.json", "as?": "alias" }`
- `$ref` pattern: `^(?![A-Za-z][A-Za-z0-9+.-]*:)(?!/).*\.json$` (no URLs, no absolute paths — but see Gap #5 below)

**Model-fragment schema** — `NPDevContract/schemas/model-fragment.schema.json` and `pack-fragment.schema.json`:
- These existed before packs; `pack-fragment.schema.json` is simply an alias for `model-fragment.schema.json`

---

### 2.3 Resolution Pipeline

Resolution is handled by `ModelSourceResolver` (`NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java`).

#### Step-by-step

```
parse(modelJsonPath)
  └─ checkDeprecatedAuthoringShape(rawRoot)          # pre-validation on raw JSON
  └─ ModelSourceResolver.resolve(modelJsonPath)
       └─ validateRootAuthoringObject()               # malformed $ref check
       └─ resolveRoot()
            ├─ copy ROOT_SCALAR_KEYS verbatim
            ├─ resolveArray() for each MODEL_ARRAY_KEY
            ├─ process "fragments" array
            │    └─ resolveFragment() (recursive, depth-guarded, circular-detected)
            └─ resolvePacks()                         ← new
                 └─ for each pack ref:
                      ├─ resolvePackPath()            # path resolution
                      ├─ loadPackJson()               # reads raw JSON, no validation
                      ├─ determine packId (from pack.identifier or "as" alias)
                      ├─ mergePackConcepts()          # namespace + intra-pack ref rewrite
                      └─ mergePackNonConceptArrays()  # merge domainTypes, capabilities, etc.
  └─ JsonModelParser.parse(resolvedRoot)
```

#### Concept namespacing

`mergePackConcepts()` iterates every concept in the pack, prefixes its name with `packId::`, then calls `namespacePackFieldRefs()` on every field:

```java
// Before namespacing (inside pack file)
{ "name": "Variant", "fields": [
    { "type": "reference", "reference": { "target": "Product" } }
]}

// After namespacing (in resolved model)
{ "name": "catalog::Variant", "fields": [
    { "type": "reference", "reference": { "target": "catalog::Product" } }
]}
```

The rewrite logic in `namespacePackFieldRefs` handles three shapes:
1. `"ref": "Name"` — rewritten if `Name` is in the pack's own concept names
2. `"reference": "Name"` (string shorthand) — same condition
3. `"reference": { "target": "Name" }` — same condition

**Critical**: only intra-pack references are rewritten. A pack concept that references a concept from the root model or another pack must use the already-namespaced name — the resolver will NOT auto-qualify it.

#### Non-concept array merging

`mergePackNonConceptArrays()` appends `domainTypes`, `capabilities`, `customCapabilities`, `bindings`, `events`, `flows`, `orchestrationRules`, `orchestrations`, `queries`, `ruleProfiles`, `procedures`, and `panels` from the pack into the resolved model without any namespacing. Name collisions in these arrays surface only as downstream semantic validation errors (no pack attribution in the error message).

---

### 2.4 What Works Correctly

- **Namespace isolation**: `packId::ConceptName` prevents concept name collisions between packs and the root model.
- **Alias override**: `"as": "cat"` imports `pack.identifier = "catalog"` under the prefix `cat`, allowing import aliasing.
- **Intra-pack reference rewriting**: Both `ref: "Name"` (short form) and `reference: { target: "Name" }` (object form) are correctly rewritten for intra-pack sibling references.
- **Non-concept array merging**: domainTypes, capabilities, etc. from the pack are correctly appended (the fix comment in `mergePackNonConceptArrays` notes this was a prior bug).
- **Error messages**: Missing pack file, missing `pack` identifier, malformed `$ref` all produce clear IOExceptions.
- **Test coverage** (`ModelSourceResolverTest`): flat parse, inline refs, fragments, metadata collision, pack namespace rewriting, alias override, missing identifier rejection — all covered.

---

### 2.5 Gaps and Issues

#### GAP-P1 (Critical): Pack `fragments` are silently dropped

**File**: `ModelSourceResolver.java`, method `mergePackNonConceptArrays` (lines 287–304)

The pack schema declares `fragments` as a valid property, suggesting that pack authors can split their pack into multiple sub-files:

```json
{
  "pack": "catalog",
  "version": "1.0",
  "dslVersion": "1.0.0",
  "concepts": [...],
  "fragments": [{ "$ref": "sub-concepts.json" }]
}
```

However, `mergePackNonConceptArrays` only iterates `MODEL_ARRAY_KEYS` (a constant that does NOT include `fragments`). The `fragments` array is silently ignored — no error, no warning, no content included.

**Impact**: Pack authors who split their pack into multiple files using `fragments` will see an empty/partial pack without any diagnostic.

**Root cause**: The resolver's fragment-resolution path (`resolveFragment`) is only called for the root model's own `fragments` array. Pack fragment resolution was never wired into `resolvePacks`.

**Fix direction**: After loading the pack JSON node, run the fragment-resolution pass on it before calling `mergePackConcepts` / `mergePackNonConceptArrays`. The resolved pack node (with fragments merged) is then what gets decomposed. Security: restrict fragment resolution to files inside the same directory as the pack file (mirroring the `rootDirectory` guard for model fragments).

---

#### GAP-P2 (Medium): Pack files are not schema-validated at load time

**File**: `ModelSourceResolver.java`, method `loadPackJson` (lines 337–345)

```java
private static ObjectNode loadPackJson(Path packFile, ResolutionState state) throws IOException {
    JsonNode node = readJson(packFile);
    if (!node.isObject()) {
        throw error(packFile, "$", "Pack file must be a JSON object");
    }
    state.includedFiles.add(packFile);
    state.seenIncludedFiles.add(packFile);
    return (ObjectNode) node;
}
```

There is no call to `JsonModelSchemaValidator` or any structural check on the pack JSON. A pack file with:
- A typo in a key (`"concets"` instead of `"concepts"`) → silently produces no concepts
- An invalid `dslVersion` → not rejected
- Extra unknown properties → silently ignored

**Impact**: Pack authoring errors are very hard to diagnose. The pack appears to load successfully but contributes no content.

**Fix direction**: Run `JsonModelSchemaValidator.validate(packNode, packFile.toString())` against `pack.schema.json` after reading the file. Add a `JsonModelSchemaValidator` overload or a pack-specific schema reference.

---

#### GAP-P3 (Medium): Non-concept names are not namespaced — silent collision risk

**File**: `ModelSourceResolver.java`, method `mergePackNonConceptArrays` (lines 287–304)

Only `concepts` are namespaced. All other arrays (domainTypes, capabilities, bindings, events, etc.) are appended flat. If two packs both define a `domainType` named `"Sku"`, the model will contain two `domainType` entries with the same name. The semantic validator will catch the duplicate but will report it as:

```
Entity X field Y: domain type not found: Sku
```
or a generic duplicate-member error — with no indication of which pack introduced each definition.

**Impact**: Pack authors cannot use common type names (e.g., `Money`, `Email`, `Slug`) without risking collisions with other packs.

**Fix direction**: Either namespace non-concept names with `packId::TypeName` (consistent but requires updating all references inside the pack), or detect and report collisions with pack attribution in the error message.

---

#### GAP-P4 (Low): Pack `dslVersion` is not validated against the model's

**File**: `ModelSourceResolver.java`, method `resolvePacks`

The resolver reads the pack JSON and extracts the `pack` identifier, but never reads or validates the `dslVersion` field. A pack built for DSL `2.0.0` (or using unknown syntax) will silently compose into a `1.0.0` model.

**Impact**: Forward-compatibility problems and future syntax changes will produce obscure downstream errors rather than a clear "version mismatch" message.

**Fix direction**: Add a check: after loading a pack, read `packNode.get("dslVersion")` and verify it matches the model's DSL version. Emit an error if mismatched.

---

#### GAP-P5 (Low): Absolute paths allowed for packs but rejected for fragments — undocumented asymmetry

**File**: `ModelSourceResolver.java`, method `resolvePackPath` (lines 306–335)

Fragments strictly reject absolute paths:
```java
if (refPath.isAbsolute()) {
    throw error(referencingFile, "$ref", "Model include ref must be relative: " + ref);
}
```

Packs accept them:
```java
if (refPath.isAbsolute()) {
    candidate = refPath.normalize();
} else {
    ...
}
```

The model schema's `packRef.$ref` pattern also uses `(?!/)` (no leading slash), which should reject absolute paths at schema level — but only if the schema validator runs on the root model before the resolver uses the ref. This pattern relies on schema validation ordering.

**Impact**: The asymmetry is undocumented and potentially confusing. It could also be a security concern if pack files are loaded from user-controlled paths.

**Fix direction**: Either document the asymmetry and the rationale (e.g., "system packs can be installed at known absolute locations"), or align packs with fragments (require relative paths). If absolute paths remain allowed, add a rootDirectory containment check (like fragments have for relative paths).

---

#### GAP-P6 (Low): No depth or file-count protection for packs

Fragments are protected by `maxIncludeDepth: 32` and `maxIncludedFiles: 512`. Packs have no equivalent guard. While circular pack imports are not currently possible (only models can reference packs, packs cannot reference models), if pack-fragments (GAP-P1) are ever resolved recursively, the guard would be needed.

**Fix direction**: Once GAP-P1 is addressed (recursive pack fragment resolution), inherit the same depth/count guards used for fragments.

---

## 3. Field Bonds — Full Analysis

### 3.1 What Field Bonds Are

Field bonds are first-class connections between concepts that produce real database foreign keys, typed by the referenced anchor, and drive generated service/controller code. The design principle is **"Id binds Id"** — a port field binds an anchor key, which can be the target's `id` field or any `unique` field marked `connectable: "anchor"` (natural key).

**Components:**
| Term | Role |
|---|---|
| **Port** | The field on the source concept that declares the reference (e.g., `productId`) |
| **Anchor** | The field on the target concept being referenced (e.g., `skuId`, or the default `id`) |
| **`connectable: "anchor"`** | Marks a field as bondable; combined with `unique: true` |
| **`via`** | Names the anchor field; if absent, defaults to the target's `id` |
| **`onDelete`** | Referential integrity policy: `restrict` \| `cascade` \| `nullify` |
| **Cardinality** | MANY_TO_ONE (default), ONE_TO_ONE (port is `unique`), MANY_TO_MANY (port has `multiple: true`) |

---

### 3.2 Schema Contract

#### Field definition additions (in `model.schema.json` `$defs/field`)

```json
"connectable": {
  "type": "string",
  "enum": ["anchor"]
}
```

Only one value is valid: `"anchor"`. The enum is intentionally restricted to allow future values without breaking existing models.

#### Reference definition additions (in `$defs/referenceDefinition`)

```json
"via": {
  "type": "string",
  "minLength": 1
},
"onDelete": {
  "type": "string",
  "enum": ["restrict", "cascade", "nullify"]
}
```

`onDelete` is validated at parse time by the schema validator — unknown values (e.g., `"burn"`) are rejected before the AST is even built. This is verified by `BondSemanticsSupportTest.schemaRejectsUnknownOnDeletePolicyAtParseTime`.

---

### 3.3 Full Pipeline

```
JSON model file
  │
  ▼ JsonModelParser.parse()
  │  reads connectable → FieldAst.connectable
  │  reads reference.via → ReferenceSemanticsAst.via
  │  reads reference.onDelete → ReferenceSemanticsAst.onDelete
  │
  ▼ ModelAst (AST)
  │
  ▼ ModelCompiler.compile()
  │  maps to CompiledField.connectable
  │  maps to CompiledReferenceSemantics.via / .onDelete
  │
  ▼ CompiledModel
  │
  ▼ CompiledModelCanonicalJson.toJson()  ←→  CompiledModelCanonicalJsonReader.fromJson()
  │  (round-trip: all bond attributes survive serialization)
  │
  ▼ SemanticValidator.validate()
  │  validateReferenceSemantics()        # via / onDelete / displayField checks
  │  validateBondTruthEdge()             # warns on upward truth edges
  │
  ▼ BondModelSupport.allBonds(model)     (Generator layer)
  │  resolveBond() per field
  │  resolveAnchorField() → via lookup or id fallback
  │  cardinality determination
  │
  ├─▶ FlywayEmitter.emitRepeatableSchema()
  │     scalar bonds  → ALTER TABLE ADD COLUMN (anchor type) + ADD CONSTRAINT FK
  │     N:M bonds     → CREATE TABLE junction + two FKs
  │     anchor unique → CREATE UNIQUE INDEX on anchor column (when non-id)
  │
  ├─▶ EntityEmitter     → Java entity field with anchor javaType
  ├─▶ DtoEmitter        → create/update DTOs with anchor type
  ├─▶ ServiceEmitter    → listBy<Field>, mapDataIntegrityViolation
  └─▶ ControllerEmitter → GET /by/{field}/{value}, POST /{id}/{field}/{anchor}
```

---

### 3.4 Validation Rules

All validation lives in `SemanticValidator` (`NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java`).

#### Field-level: `connectable` validation

| Condition | Outcome |
|---|---|
| `connectable` is set but not `"anchor"` | ERROR: `connectable must be "anchor"` |
| `connectable = "anchor"` but field is not `id` AND not `unique` | ERROR: `connectable anchor field must be unique (or the id field)` |
| `connectable = "anchor"` + `unique: true` | VALID |
| `connectable = "anchor"` + `id: true` | VALID |

#### Reference-level: `via` validation (inside `validateReferenceSemantics`)

```
if via is set:
    find field named via on effective target entity
    if not found → ERROR: "via field not found on target"
    if field is not (id OR unique OR connectable=="anchor") → ERROR: "reference via must target a connectable anchor"
```

The error message reads: *"reference via must target a connectable anchor (a field marked connectable:anchor, the id, or a unique field)"*

This means `via` can technically point at ANY `unique` field on the target — even one not marked `connectable: anchor`. The `connectable` attribute is declarative intent, not a strict gate for `via` resolution. This is a deliberate design choice but can confuse authors (see Bond Gap #6).

#### Reference-level: `onDelete` validation

Handled at **schema level** (before semantic validation), not in `SemanticValidator`. The JSON Schema enum `["restrict", "cascade", "nullify"]` rejects unknown values at parse time.

#### Bond truth-edge validation

```java
private static void validateBondTruthEdge(EntityAst source, FieldAst port, EntityAst target, List<String> warnings) {
    if (targetTruth.rank() < sourceTruth.rank()) {
        warnings.add("Entity " + source.getName() + " field " + port.getName()
                + ": bond points at lower-truth concept " + target.getName()
                + " (" + sourceTruth.code() + " -> " + targetTruth.code() + ")...");
    }
}
```

This is a **WARNING**, not an error. The design intent is that truth constraints are enforced only at release gate time (by `ReleaseGateValidator`). During active development, authoring upward truth edges is allowed.

**Truth levels** (defined in `TruthLevel.java`): T0 through T6, where T0 is the most derived/lowest truth and T6 is the most foundational. A bond must point at equal or higher truth (same level or more foundational).

---

### 3.5 Generator Behaviour

#### BondModelSupport (`NPDevGenerator/generator/src/main/java/com/npdev/generator/bonds/BondModelSupport.java`)

This is the central resolver for bond metadata in the generator. Key methods:

| Method | Purpose |
|---|---|
| `allBonds(model)` | Returns all bonds across all concepts in the model |
| `resolveBond(concept, field, conceptsByName)` | Resolves a single field to a `Bond` object; returns `Optional.empty()` if not a bond |
| `resolveAnchorField(sourceField, target)` | Returns the anchor `CompiledField` (via lookup or id fallback) |
| `idField(concept)` | Returns the single id field; throws if missing or multiple |
| `Bond.effectiveJavaType()` | Returns the **anchor's** `javaType`, not the port's |
| `Bond.effectiveSqlType()` | Returns the **anchor's** SQL type mapping |
| `Bond.junctionTable()` | `sourceTable_sourceColumn`, truncated to 60 chars |
| `Bond.onDeleteSql()` | `CASCADE` / `SET NULL` / `RESTRICT` |
| `Bond.onUpdateSqlClause()` | `" ON UPDATE CASCADE"` if anchor is non-id; `""` if anchor is id |

**Cardinality rules:**

| Port field | Cardinality |
|---|---|
| `reference.multiple: true` | `MANY_TO_MANY` (junction table) |
| `unique: true` | `ONE_TO_ONE` |
| Default | `MANY_TO_ONE` |

#### FlywayEmitter — DDL generation

**Per-concept DDL order:**
1. `CREATE TABLE IF NOT EXISTS` (id column only)
2. `ALTER TABLE ADD COLUMN IF NOT EXISTS` per non-id, non-M:M field
3. NOT NULL constraints per required fields
4. `CREATE UNIQUE INDEX IF NOT EXISTS` per unique field (and per `connectable: anchor` non-id fields)
5. `ADD CONSTRAINT FK` for declared reference fields (N:1 / 1:1)

**After all concepts:**
6. `CREATE TABLE IF NOT EXISTS` junction tables (for each MANY_TO_MANY bond)
7. `ALTER TABLE ADD CONSTRAINT FK` for scalar bonds (N:1 / 1:1)

**Critical FK column type resolution:**

For a declared reference field (bond port), the column type is NOT taken from the port field's own type — it is resolved from the anchor:

```java
private String columnType(CompiledField field, Map<String, CompiledConcept> conceptsByName) {
    if (isDeclaredReference(field)) {
        CompiledField anchor = resolveAnchorField(field, conceptsByName);
        if (anchor != null) {
            return mapType(anchor);  // ← anchor's type, not port's type
        }
    }
    return mapType(field);
}
```

**Example**: `Invoice.productId` (type `reference`) bound via `via: "skuId"` where `Product.skuId` is type `string` → DDL column is `VARCHAR(255)`, not `UUID`.

**Junction table structure (MANY_TO_MANY):**

```sql
CREATE TABLE IF NOT EXISTS invoices_product_id (
  source_id UUID NOT NULL,      -- source concept's id type
  target_sku_id VARCHAR(255) NOT NULL,  -- anchor field's type
  PRIMARY KEY (source_id, target_sku_id)
);
CREATE INDEX IF NOT EXISTS idx_invoices_product_id_source_id ON invoices_product_id (source_id);
CREATE INDEX IF NOT EXISTS idx_invoices_product_id_target_sku_id ON invoices_product_id (target_sku_id);
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE c.conname = 'fk_invoices_product_id_source_id'
      AND t.relname = 'invoices_product_id'
      AND n.nspname = current_schema()
  ) THEN
    ALTER TABLE invoices_product_id ADD CONSTRAINT fk_invoices_product_id_source_id
      FOREIGN KEY (source_id) REFERENCES invoices (id) ON DELETE CASCADE;
  END IF;
END $$;
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE c.conname = 'fk_invoices_product_id_target_sku_id'
      AND t.relname = 'invoices_product_id'
      AND n.nspname = current_schema()
  ) THEN
    ALTER TABLE invoices_product_id ADD CONSTRAINT fk_invoices_product_id_target_sku_id
      FOREIGN KEY (target_sku_id) REFERENCES products (sku_id) ON UPDATE CASCADE ON DELETE CASCADE;
  END IF;
END $$;
```

The source-side FK is always `ON DELETE CASCADE` (a junction row is owned by its source). The target-side FK uses the authored `onDelete` policy. `ON UPDATE CASCADE` is added when the anchor is non-id (natural key that could be updated).

#### Java Emitters — Code generation

**EntityEmitter**: The entity Java field uses the **anchor's `javaType`**, not the declared DSL type.

```java
// Without bonds (plain reference type → UUID by default)
private java.util.UUID productId;

// With natural-key bond (anchor is string → String)
private String productId;
```

**ServiceEmitter**: Generates `listBy<FieldName>(value)` method and includes `mapDataIntegrityViolation` (maps FK violation exceptions to domain errors). For MANY_TO_MANY bonds:
- `listProductIdMembers(id)`
- `addProductIdMember(id, targetAnchor)`

**ControllerEmitter**:
- N:1 / 1:1: `@GetMapping("/by/productId/{value}")`
- N:M: `@GetMapping("/{id}/productId")` and `@PostMapping("/{id}/productId/{targetAnchor}")`

---

### 3.6 What Works Correctly

- **Full round-trip**: `connectable`, `via`, `onDelete` survive parse → compile → canonical JSON → read-back unchanged (`BondCompilePropagationTest`)
- **Schema-level `onDelete` validation**: Unknown policies rejected before AST construction
- **Semantic validation completeness**: Rejects non-unique anchors, `via` targeting non-anchor fields, unknown `connectable` values
- **Bond truth-edge warning**: Upward truth-level references generate warnings (non-blocking during dev)
- **FK column type follows anchor**: Natural-key (`string`) anchor produces `VARCHAR`, not `UUID`
- **Junction table synthesis**: Correct column naming, types, PKs, dual FKs for `multiple: true`
- **`ON UPDATE CASCADE` for natural keys**: Added when anchor is non-id (a key that can change)
- **Java type propagation**: Entity field type matches anchor's `javaType` — not hardcoded to UUID
- **Unique index on non-id anchors**: `CREATE UNIQUE INDEX` ensures the anchor column can be FK-referenced
- **Test coverage**: `BondSemanticsSupportTest`, `BondCompilePropagationTest`, `FlywayEmitterBondsTest`, `BondJavaEmitterTest` — all solid

---

### 3.7 Gaps and Issues

#### GAP-B1 (Medium): `resolveBond` silently drops unresolvable bonds

**File**: `BondModelSupport.java`, method `resolveBond` (lines 153–181)

```java
CompiledField anchor = resolveAnchorField(sourceField, target);
if (anchor == null) {
    return Optional.empty();  // ← silent drop
}
```

If `via` names a field that does not exist on the target concept, `resolveAnchorField` returns `null` and `resolveBond` returns `Optional.empty()`. The bond is silently absent from generated DDL.

**When does this happen in practice?**
- Semantic validator should catch `via` referencing a non-existent field before the generator runs
- BUT: if the generator is invoked directly (bypassing validation), or if a pack-namespacing edge case causes a name mismatch between what the validator saw and what the compiled model contains, a bond could be silently dropped from DDL without any error in the generated output

**Impact**: Missing FK in the database. The app runs but lacks referential integrity enforcement at the DB level, which only surfaces as data corruption over time.

**Fix direction**: Add a diagnostic log/exception in `resolveBond` when `anchor == null` after `via` was explicitly set. The generator should surface this as a generation-time warning, not a silent skip. Something like:
```java
if (anchor == null) {
    if (via != null && !via.isBlank()) {
        throw new IllegalStateException(
            "Bond resolution error: field '" + via + "' declared in via does not exist on " + target.getName());
    }
    return Optional.empty();
}
```

---

#### GAP-B2 (Medium): Duplicated anchor-resolution logic between FlywayEmitter and BondModelSupport

**Files**:
- `BondModelSupport.java`, methods `resolveAnchorField`, `fieldByName`, `idFieldOrNull`
- `FlywayEmitter.java`, private methods `resolveAnchorField`, `columnType`, `idFieldOrNull`, `fieldByName`

`FlywayEmitter` has its own private copy of the anchor resolution logic instead of delegating to `BondModelSupport`. The comment in `BondModelSupport.Bond.junctionTable()` explicitly warns:

> "MUST stay byte-identical to the runtime mirror in `GeneratedCrudRuntimeSupport.requireBondRuntimeShape` (NPDevKernel), otherwise the generated migration and the runtime SQL disagree and N:M CRUD hits a missing table."

This means there are at least **three copies** of naming/resolution logic: `BondModelSupport`, `FlywayEmitter`, and `GeneratedCrudRuntimeSupport` (kernel). Any change to one must be manually applied to the others.

**Impact**: This is the most likely source of a future DDL/runtime drift bug. A developer changing junction table naming in `BondModelSupport` who does not update `FlywayEmitter` and the kernel support will produce a migration that references a table the runtime looks for under a different name.

**Fix direction**:
1. `FlywayEmitter` should call `BondModelSupport.resolveAnchorField()` and `BondModelSupport.Bond.effectiveSqlType()` directly instead of maintaining private copies
2. The "byte-identical" comment should become a test — `FlywayEmitterBondsTest` already pinned the junction table name, which is the right approach; extend it to also verify that the generated name matches what the kernel's runtime support expects

---

#### GAP-B3 (Low): Junction table identifier truncation at 60 chars — no collision detection

**File**: `BondModelSupport.java`, method `truncateIdentifier` (line 317)

```java
public static String truncateIdentifier(String value) {
    return value == null ? "" : value.length() > 60 ? value.substring(0, 60) : value;
}
```

PostgreSQL's identifier limit is 63 bytes. The 60-char conservative truncation is intentional. But truncation can silently create two different bonds that produce the same junction table name:

```
VeryLongConceptName.veryLongFieldNam   ← truncated at 60
VeryLongConceptName.veryLongFieldNam2  ← also truncated to the same 60 chars
```

This would generate two `CREATE TABLE IF NOT EXISTS same_name` statements — the second silently does nothing, leaving the second bond with no junction table.

**Impact**: Silent data model incompleteness for models with long concept/field names.

**Fix direction**: Detect duplicate resolved identifiers in `allBonds()` or in the emitter. If two bonds resolve to the same junction table name, either error out or use a hash suffix for disambiguation (e.g., `name_<sha4>`).

---

#### GAP-B4 (Low): Truth-edge validation is a WARNING — release gate integration must be verified

**File**: `SemanticValidator.java`, method `validateBondTruthEdge` (lines 1368–1388)

The truth-edge violation adds to `semanticWarnings` (not `errors`). This is intentional: the design philosophy is that truth constraints are restrictive only at release, not during authoring. The `ReleaseGateValidator` (`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ReleaseGateValidatorTest.java` is an untracked new file) presumably promotes this to an error.

**Current risk**: `ReleaseGateValidator` is an untracked new file (not staged). If it is not wired into the CI pipeline's gate check before beta0 is tagged, the truth-edge constraint will be unenforced at release.

**Fix direction**: Verify `ReleaseGateValidator` is:
1. Committed and integrated (not just an untracked test file)
2. Called from the CI workflow (`ai-beta-gate.yml`) that is a required check before beta0 tagging
3. Tested end-to-end with a model that has a known truth-edge violation

---

#### GAP-B5 (Low): `connectable: "anchor"` is semantically redundant with `unique`

**Observed behaviour**: The `isConnectableAnchor` check in `SemanticValidator.validateReferenceSemantics` accepts a field as a valid `via` target if it is `id`, `unique`, OR `connectable == "anchor"`. This means a field that is `unique: true` but lacks `connectable: "anchor"` can still be used as a bond anchor — the model passes validation.

The `connectable` attribute is therefore **declarative intent**, not a strict gate for bond wiring. Authors who read the error message (which says *"or a unique field"*) may not realize they need `connectable: anchor` at all.

**Design question**: Should `connectable: "anchor"` be **required** for non-id anchor fields (i.e., `via` only resolves to id-or-connectable, not id-or-unique)? Or is it intentionally optional metadata?

**If optional (current state)**: The schema comment in `pack.schema.json` / `model.schema.json` should explain that `connectable` is informational, and that `unique: true` is sufficient for `via` targeting.

**If required**: The validator should be tightened: `isConnectableAnchor` should return `true` only for `id` or `(unique AND connectable=="anchor")`, not plain `unique`.

---

#### GAP-B6 (Info): No bond validation for cross-pack bonds is explicitly tested

When a root model concept bonds to a pack concept (e.g., `via` an anchor field inside `catalog::Product`), the validator sees the post-resolution AST where the concept is already named `catalog::Product`. The reference target `catalog::Product` normalises to `catalog::product`, which matches the entity in `entitiesByLower`. The bond validation should work correctly — but there is **no test** that verifies this cross-pack bond scenario end-to-end (parse → validate → compile → generate DDL).

**Fix direction**: Add a test case combining packs and bonds: a pack defining a concept with a connectable anchor, a root model concept that bonds to it via `via`, verifying that semantic validation passes and the generated DDL contains the correct FK.

---

## 4. Cross-Feature Interaction: Packs + Bonds

The two features interact when a root model bonds to a concept imported from a pack. The interaction chain is:

```
Pack file (catalog.json)
  Product.skuId: { connectable: "anchor", unique: true }

Model file (model.json)
  packs: [{ $ref: "catalog.json" }]
  Order.productId: { type: reference, reference: { target: "catalog::Product", via: "skuId" } }

After ModelSourceResolver.resolve():
  concepts: [
    { name: "catalog::Product", fields: [...skuId with connectable...] },
    { name: "Order", fields: [...productId with reference.target="catalog::Product"...] }
  ]

SemanticValidator:
  entitiesByLower["catalog::product"] → catalog::Product entity
  Order.productId.getReferenceTarget() = "catalog::Product"
  normalize("catalog::Product") = "catalog::product" → resolves ✓
  via="skuId" → found on catalog::Product ✓

BondModelSupport.resolveBond():
  conceptsByName.get("catalog::product") → catalog::Product ✓
  anchor = skuId ✓

FlywayEmitter:
  ALTER TABLE orders ADD COLUMN IF NOT EXISTS product_id VARCHAR(255);
  ALTER TABLE orders ADD CONSTRAINT fk_orders_product_id FOREIGN KEY (product_id) REFERENCES catalog__products (sku_id) ...
```

**Known risk**: The `safeTable()` method in `BondModelSupport` calls `concept.getTableName()`. For a pack concept named `catalog::Product`, the generated table name will depend on how `CompiledConcept.getTableName()` handles the `::` separator — likely producing `catalog::products` which is an invalid SQL table name. This needs explicit testing.

**Fix direction**: The `safeTable` method should strip or replace the pack namespace prefix from the table name. The convention could be: `catalog::Product` → table `catalog_products` (replacing `::` with `_`). This must be consistent between the DDL generator and the runtime persistence adapter.

---

## 5. Improvement Action Items

### 5.1 Packs — Prioritised Actions

| # | Priority | Issue | Action |
|---|---|---|---|
| P1 | **HIGH** | Pack `fragments` silently dropped | Implement fragment resolution inside `resolvePacks`; restrict to same directory as pack file |
| P2 | **HIGH** | Pack files not schema-validated | Validate pack JSON against `pack.schema.json` on load |
| P3 | **MEDIUM** | domainType/capability name collisions with no attribution | Either namespace non-concept members OR detect and attribute collisions in error messages |
| P4 | **LOW** | Pack `dslVersion` not validated | Check pack `dslVersion` matches model `dslVersion` on load |
| P5 | **LOW** | Absolute pack paths inconsistent with fragment policy | Document or align — add containment check if absolute paths remain allowed |
| P6 | **LOW** | No depth/file-count protection for pack fragments | Once P1 is implemented, add the same guards as for model fragments |
| P7 | **INFO** | Packs + bonds cross-scenario not tested | Add an integration test combining pack import + bond to a pack concept |

### 5.2 Bonds — Prioritised Actions

| # | Priority | Issue | Action |
|---|---|---|---|
| B1 | **MEDIUM** | Silent bond drop when anchor not found at generator time | Throw `IllegalStateException` in `resolveBond` when `via` is set but field not found |
| B2 | **MEDIUM** | Anchor-resolution logic duplicated in FlywayEmitter | Refactor `FlywayEmitter` to delegate to `BondModelSupport` methods; pin the identity with an existing test |
| B3 | **LOW** | Junction table name truncation can collide silently | Detect duplicate resolved names in `allBonds()` or emitter; error or use hash suffix |
| B4 | **LOW** | Truth-edge gate (`ReleaseGateValidator`) is untracked and may not be CI-wired | Commit, integrate into `ai-beta-gate.yml`, add end-to-end violation test |
| B5 | **LOW** | `connectable: anchor` semantically redundant with `unique` | Decide: make it required (tighten `isConnectableAnchor`) or document it as optional intent marker |
| B6 | **INFO** | Cross-pack bond not tested end-to-end | Add integration test: pack with anchor + root model bond via → validate + DDL |
| B7 | **INFO** | Pack concept table name with `::` separator untested | Test `safeTable()` for pack-namespaced concepts; establish `catalog::Product` → `catalog_products` convention |

---

## 6. File Reference Map

### Contract layer

| File | Role |
|---|---|
| `NPDevContract/schemas/pack.schema.json` | Pack authoring schema |
| `NPDevContract/schemas/pack-fragment.schema.json` | Pack fragment alias (→ model-fragment.schema.json) |
| `NPDevContract/schemas/model.schema.json` | Updated: added `packs`, `packRef`, `connectable`, `via`, `onDelete` |
| `NPDevContract/schemas/model-fragment.schema.json` | Fragment schema |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java` | Pack + fragment resolution |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ResolvedModelSource.java` | Resolution output record |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java` | `connectable` / `via` / `onDelete` parsing |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/FieldAst.java` | `connectable` field |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/ReferenceSemanticsAst.java` | `via`, `onDelete` fields |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/ConceptAst.java` | `truthLevel` field |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/TruthLevel.java` | TruthLevel enum (T0–T6) |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledField.java` | `connectable` field |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledReferenceSemantics.java` | `via`, `onDelete` fields |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java` | Bond semantic rules |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ReleaseGateValidator.java` | Truth-gate (untracked) |

### Generator layer

| File | Role |
|---|---|
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/bonds/BondModelSupport.java` | Central bond resolver + Bond data class |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/FlywayEmitter.java` | DDL: FK columns, unique indexes, FK constraints, junction tables |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/EntityEmitter.java` | Java entity field type resolution |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/DtoEmitter.java` | DTO field type resolution |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/ServiceEmitter.java` | listBy / member management methods |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/ControllerEmitter.java` | REST endpoints for bonds |

### Test files

| File | Covers |
|---|---|
| `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ModelSourceResolverTest.java` | Packs: namespace, alias, missing id, non-concept merge, inline refs, fragments |
| `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/BondSemanticsSupportTest.java` | Bonds: semantic rules (valid anchor, N:M, non-unique anchor error, bad onDelete) |
| `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/BondCompilePropagationTest.java` | Bonds: parse → compile → canonical JSON → read-back round-trip |
| `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/TruthLevelSupportTest.java` | TruthLevel enum correctness |
| `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ReleaseGateValidatorTest.java` | Truth-edge gate (untracked) |
| `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/FlywayEmitterBondsTest.java` | DDL: FK type, unique index, ON DELETE, junction table |
| `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/BondJavaEmitterTest.java` | Java: entity type, service methods, controller endpoints |

---

*End of analysis. This document is the baseline for improvement work on both features.*
