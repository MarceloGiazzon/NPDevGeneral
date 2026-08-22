# Bond Feature: Implementation Completion Plan

> **Branch:** `beta0-no-false-green-release-hardening`  
> **Date:** 2026-06-14  
> **Purpose:** Actionable implementation guide for Cursor to close the remaining bond-feature gaps and commit the full unstaged bond foundation.  
> **Prerequisite reading:** `NPDevContract/docs/PACKS-AND-BONDS-ANALYSIS.md`

---

## Gap Status Overview

| Gap | Title | Status | Phase |
|---|---|---|---|
| GAP-B1 | `resolveBond` silently drops unresolvable bonds | **ALREADY FIXED** — throws `IllegalStateException` | — |
| GAP-B2 | Duplicate anchor-resolution logic in `FlywayEmitter` | **OPEN** | Phase 1 |
| GAP-B3 | Junction table name truncation collision | **ALREADY FIXED** — hash suffix in `safeSqlIdentifier` | — |
| GAP-B4 | `ReleaseGateValidator` untracked, not CI-wired | **OPEN** | Phase 2 |
| GAP-B5 | `connectable: anchor` redundant with `unique` | **ALREADY FIXED** — validator requires both `unique` AND `connectable:anchor` | — |
| GAP-B6 | Cross-pack bond not tested end-to-end from JSON | **OPEN** | Phase 3 |
| GAP-B7 | Pack concept table name `::` handling untested | **OPEN** | Phase 4 |
| STRUCTURAL | Full bond feature is unstaged | **CRITICAL — BLOCKING** | Phase 0 |

**Do Phase 0 first.** All other phases depend on a committed, buildable bond foundation. Phases 1–4 are independent of each other once Phase 0 is done.

---

## Phase 0 — Commit the Bond Feature Foundation (CRITICAL)

### Why this is critical

Commits `0d4d3b9`, `2cd3b76`, and `11aa335` are already on the branch but they reference classes and types (`BondModelSupport`, `SqlIdentifierSupport`, `SqlTypeSupport`, `CompiledReferenceSemantics.via/onDelete`, etc.) that exist only in the **working tree**. A `git checkout` of any prior commit or a merge from another branch would break compilation entirely. The full bond feature must be committed before any other work.

### What is in the working tree

The following files are modified (`M`) or new (`??`) and all belong to the bond feature foundation. Commit them in two sequential commits as described below.

---

### Commit 0-A: "Bond feature — contract layer (AST, schema, compiled, validation)"

**Files to stage (all paths relative to repo root):**

**Contract — AST additions:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/FieldAst.java` — added `connectable` field and constructor overload
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/ReferenceSemanticsAst.java` — added `via` and `onDelete` fields
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/ConceptAst.java` — added `truthLevel` field
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/EntityAst.java` — updated for truthLevel
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/PresentationMetadataAst.java` — UI metadata additions
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/TruthLevel.java` *(new)*

**Contract — compiled layer:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledField.java` — added `connectable` field
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledReferenceSemantics.java` — added `via`, `onDelete` fields
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledConcept.java` — added `tableName`, `truthLevel` support
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledEntity.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledPresentationMetadata.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledMetadataCanonicalJson.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledModelCanonicalJson.java` — bond round-trip serialization
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledModelCanonicalJsonReader.java` — bond round-trip deserialization
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/SqlIdentifierSupport.java` *(new)*
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/SqlTypeSupport.java` *(new)*

**Contract — parser:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java` — reads `connectable`, `reference.via`, `reference.onDelete`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java` *(new)* — pack resolution + fragment resolver
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ResolvedModelSource.java` *(new)*

**Contract — compiler:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/ModelCompiler.java` — maps `connectable`, `via`, `onDelete` to compiled layer

**Contract — resolution:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/resolution/ModelResolver.java` — integrates `ModelSourceResolver`

**Contract — validation:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java` — `validateReferenceSemantics`, `validateBondTruthEdge`, `isConnectableAnchor`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/JsonModelSchemaValidator.java` — cross-file `$ref` resolver (maps `https://npdev.local/schema/*` → classpath)
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/JsonSchemaResourceValidator.java` *(new)*
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ReleaseGateValidator.java` *(new)*
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ValidationLayer.java` — added `RELEASE_GATE` layer

**Contract — schemas:**
- `NPDevContract/dsl/resources/Schemas/model.schema.json` — `connectable`, `via`, `onDelete` additions
- `NPDevContract/dsl/src/main/resources/schema/model.schema.json` — runtime copy (must be identical to above)
- `NPDevContract/dsl/src/main/resources/schema/pack.schema.json` *(new)*
- `NPDevContract/schemas/authoring/model.schema.json` — authoring copy
- `NPDevContract/schemas/model.schema.json` — canonical copy
- `NPDevContract/schemas/concept-fragment.schema.json` *(new)*
- `NPDevContract/schemas/model-fragment.schema.json` *(new)*
- `NPDevContract/schemas/pack-fragment.schema.json` *(new)*
- `NPDevContract/schemas/pack.schema.json` *(new)*

**Contract — samples and build:**
- `NPDevContract/dsl/resources/Models/official-samples/medium-expense-approval/model.json` — `schemaVersion` removed
- `NPDevContract/dsl/build.gradle` — `checkRuntimeSchemaCopies` and `checkSampleModelCopies` tasks
- `NPDevSamples/medium-expense-approval/Input/model.json` — matching sample copy

**Contract — tests:**
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/BondCompilePropagationTest.java` *(new)*
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/BondSemanticsSupportTest.java` *(new)*
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ModelSourceResolverTest.java` *(new)*
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ReleaseGateValidatorTest.java` *(new)*
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/SqlIdentifierSupportTest.java` *(new)*
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/SqlTypeSupportTest.java` *(new)*
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/TruthLevelSupportTest.java` *(new)*

**Suggested commit message:**
```
Bond feature — contract layer: AST, schema, compiled, validation, packs

Add connectable/via/onDelete to FieldAst, ReferenceSemanticsAst.
Add TruthLevel enum. Add SqlIdentifierSupport (safe SQL naming with
hash suffix for >63-char identifiers) and SqlTypeSupport (DSL→SQL
type mapping). Add ModelSourceResolver for pack resolution and
fragment inclusion. Add ReleaseGateValidator (bond truth-closure
gate), JsonSchemaResourceValidator. Extend SemanticValidator with
bond validation rules (connectable anchor, via resolution, truth-edge
warning). Add RELEASE_GATE to ValidationLayer. Update all compiled
types (CompiledField/ReferenceSemantics/Concept) for round-trip.
Add canonical schema additions (connectable, via, onDelete, packs
array). Add 7 new test classes.
```

---

### Commit 0-B: "Bond feature — generator layer (BondModelSupport, emitters, templates, kernel bonds)"

**Files to stage:**

**Generator — bond support:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/bonds/BondModelSupport.java` *(new — entire directory)*

**Generator — emitters updated for bonds:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/EntityEmitter.java` — uses anchor `javaType` for bond port fields
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/DtoEmitter.java` — uses anchor type for create/update DTOs
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/ServiceEmitter.java` — `listBy<Field>`, `addBondMember`, `mapDataIntegrityViolation`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/ControllerEmitter.java` — N:1 finder endpoint, N:M membership endpoints
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/BusinessUiEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/RuntimeApiEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/GeneratedPluginMountPlan.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/MetadataManifestAssetEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/PluginRequirementAssetEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/api/GeneratorFacade.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/UserDatabaseDefinitionLoader.java`

**Generator — templates:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-app.mustache`
- `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-index.mustache`
- `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-route-controller.mustache`
- `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-style.mustache`
- `NPDevGenerator/generator/src/main/resources/npdev-templates/controller-custom.mustache`

**Generator — samples:**
- `NPDevGenerator/resources/Models/official-samples/medium-expense-approval/model.json`
- `NPDevGenerator/resources/Models/official-samples/simple-contact-intake/model.json`
- `NPDevGenerator/resources/Models/official-samples/simple-user-registry/model.json`

**Generator — tests:**
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/BondJavaEmitterTest.java` *(new)*
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/FlywayEmitterBondsTest.java` *(already tracked — verify)*

**Generator — build:**
- `NPDevGenerator/build.gradle`

**Kernel — bonds runtime + tests:**
- `NPDevKernel/adapters/runtime-support/build.gradle`
- `NPDevKernel/adapters/flow-compiled/build.gradle`
- `NPDevKernel/adapters/persistence-inproc/src/main/java/com/npdev/adapters/persistence/inproc/InMemoryPersistenceCapabilityAdapter.java`
- `NPDevKernel/adapters/runtime-support/src/test/java/com/npdev/runtime/support/GeneratedCrudRuntimeSupportBondJdbcTest.java` *(new)*
- `NPDevKernel/build.gradle`

**Other:**
- `NPDevEditor/build.gradle`
- `NPDevEditor/ui-react/build-templates.ps1`
- `NPDevEditor/ui-react/scripts/export-to-generator.mjs`
- `NPDevEditor/ui-react/scripts/stage-playwright-static-host.mjs`
- `NPDevEditor/ui-react/src/authoring/designers/ReferencePickerDesigner.tsx`
- `NPDevEditor/ui-react/src/authoring/editors/fields/FieldDetailsEditor.tsx`
- `NPDevEditor/ui-react/src/authoring/editors/modelDocumentTypes.ts`
- `NPDevEditor/ui-react/src/authoring/graph/SemanticGraphPanel.tsx`
- `NPDevEditor/ui-react/src/authoring/graph/semanticGraph.ts`
- `NPDevEditor/ui-react/src/styles.css`
- `NPDevEditor/ui-react/vite.config.ts`
- `scripts/hygiene/Test-WorkspaceSlimness.ps1`
- `scripts/runtimehost/sync-runtimehost-libs.ps1`
- `build.gradle`

**Suggested commit message:**
```
Bond feature — generator + kernel: BondModelSupport, DDL, Java emitters, N:M runtime

Add BondModelSupport (central bond resolver: allBonds, resolveBond,
cardinality, junction table naming). Update all emitters: entity
field types use anchor javaType; DTO DTOs use anchor type; service
generates listBy<Field>, addBondMember, mapDataIntegrityViolation;
controller generates N:1 finder and N:M membership endpoints.
Kernel runtime: GeneratedCrudRuntimeSupport already has N:M JDBC
methods (addBondMember, listBondMembers, removeBondMember,
replaceBondMembers). Add test coverage: BondJavaEmitterTest,
GeneratedCrudRuntimeSupportBondJdbcTest.
```

### Verification after Phase 0

Run in sequence:
```powershell
# From repo root
.\npdev-gradlew.ps1 :NPDevContract:dsl:check
.\npdev-gradlew.ps1 :NPDevGenerator:generator:check
.\npdev-gradlew.ps1 :NPDevKernel:adapters:runtime-support:check
```

All three must pass (green) before proceeding.

---

## Phase 1 — Fix GAP-B2: Eliminate Duplicate Anchor-Resolution Logic in FlywayEmitter

### Problem

`FlywayEmitter` maintains private copies of four methods that duplicate logic already in `BondModelSupport`:

| Private method in FlywayEmitter | Equivalent in BondModelSupport/SqlTypeSupport |
|---|---|
| `resolveAnchorField(field, conceptsByName)` | `BondModelSupport.resolveBond(...).anchorField()` |
| `columnType(field, conceptsByName)` | `BondModelSupport.resolveBond(...).effectiveSqlType()` + `SqlTypeSupport.sqlType(field)` |
| `idFieldOrNull(concept)` | `BondModelSupport.idFieldOrNull(concept)` |
| `fieldByName(concept, name)` | `BondModelSupport.fieldByName(concept, name)` |

There are now **three** copies of anchor-resolution: `FlywayEmitter`, `BondModelSupport`, and `GeneratedCrudRuntimeSupport` (kernel). `Bond.junctionTable()` already has a comment warning that these must stay byte-identical. The fix is to eliminate the `FlywayEmitter` copies so only `BondModelSupport` (the canonical one) remains.

### File

**`NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/FlywayEmitter.java`**

### Changes

#### Step 1.1 — Replace `columnType` call site

**Locate** the per-concept column-emit loop (approximately line 66–73):

```java
for (CompiledField f : e.getFields()) {
    if (f == null || f.getName() == null || f.isId() || isManyToManyBond(e, f, conceptsByName)) {
        continue;
    }
    sb.append("ALTER TABLE ").append(table)
            .append(" ADD COLUMN IF NOT EXISTS ")
            .append(SqlIdentifierSupport.columnName(f)).append(" ").append(columnType(f, conceptsByName)).append(";\n");
}
```

**Replace** `columnType(f, conceptsByName)` with an inline resolution that delegates to `BondModelSupport`:

```java
.append(SqlIdentifierSupport.columnName(f))
.append(" ")
.append(BondModelSupport.resolveBond(e, f, conceptsByName)
        .map(Bond::effectiveSqlType)
        .orElseGet(() -> mapType(f)))
.append(";\n");
```

The `isManyToManyBond` guard above already ensures N:M bonds are skipped, so `resolveBond` here either returns a scalar bond (N:1 or 1:1) or empty for non-bond fields. The call to `resolveBond` may throw `IllegalStateException` for corrupted bond specs — this is desired (fail-fast at generation time).

#### Step 1.2 — Remove the four private methods

Delete the following private methods entirely from `FlywayEmitter`:

```java
// DELETE THIS METHOD (~lines 246-264):
private CompiledField resolveAnchorField(CompiledField field, Map<String, CompiledConcept> conceptsByName) { ... }

// DELETE THIS METHOD (~lines 266-275):
private String columnType(CompiledField field, Map<String, CompiledConcept> conceptsByName) { ... }

// DELETE THIS METHOD (~lines 277-283):
private static CompiledField idFieldOrNull(CompiledConcept concept) { ... }

// DELETE THIS METHOD (~lines 286-292):
private static CompiledField fieldByName(CompiledConcept concept, String name) { ... }
```

#### Step 1.3 — Verify `isDeclaredReference` usage

After removing the above, verify that the remaining private `isDeclaredReference(CompiledField field)` method at line ~237 is still referenced. It is used by nothing inside `FlywayEmitter` itself after the `columnType` removal — check by attempting compilation. If it is unused, **delete it too** and use `BondModelSupport.isDeclaredReference(field)` at any remaining call site if one exists. If no remaining call site, delete it outright.

#### Step 1.4 — Verify `isConnectableAnchor` usage

The `isConnectableAnchor(CompiledField f)` private method (line ~232) is still used in the unique-constraint loop:
```java
if (isConnectableAnchor(f)) {
    // ADD CONSTRAINT UNIQUE (not a unique index)
```

This method should **remain** — it is a local helper for a FlywayEmitter-specific DDL decision (CONSTRAINT vs INDEX). It does not duplicate bond resolution logic.

#### Step 1.5 — Add/update test coverage

**File:** `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/FlywayEmitterBondsTest.java`

The existing tests already cover the FK DDL output. Add one new test that verifies the column type for a N:1 bond comes from the anchor (not the port's own DSL type) — specifically that the `columnType` delegation produces the right result for a non-bond field side-by-side with a bond field:

```java
@Test
void columnTypeDelegatesAnchorTypeForBondPortAndOwnTypeForNonBondField() throws Exception {
    CompiledField skuAnchor = new CompiledField(
            "skuId", "string", "String", false, false, true,
            List.of(), null, null, null, null, List.of(), null, "anchor");
    CompiledConcept product = new CompiledConcept(
            "Product", "Product", "products",
            List.of(
                    new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                    skuAnchor
            )
    );
    CompiledReferenceSemantics viaSku = new CompiledReferenceSemantics(
            "Product", false, null, List.of(), List.of(), null, null, List.of(), null, null,
            "skuId", "restrict");
    CompiledConcept order = new CompiledConcept(
            "Order", "Order", "orders",
            List.of(
                    new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                    // bond port — should use anchor (string → VARCHAR(255))
                    new CompiledField("productId", "reference", "java.util.UUID", false, false, false,
                            List.of(), "Product", viaSku, null, null, List.of(), null, null),
                    // plain integer field — should use its own type (INTEGER)
                    new CompiledField("quantity", "int", "Integer", false, false, false)
            )
    );

    Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
    concepts.put(product.getName(), product);
    concepts.put(order.getName(), order);
    Path file = new FlywayEmitter().emitRepeatableSchema(
            new CompiledModel("default", "v1", concepts), tempDir);
    String sql = Files.readString(file);

    assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS product_id VARCHAR(255);"),
            "bond port should use anchor string→VARCHAR. SQL:\n" + sql);
    assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS quantity INTEGER;"),
            "plain int field should use its own type. SQL:\n" + sql);
}
```

#### Verification

```powershell
.\npdev-gradlew.ps1 :NPDevGenerator:generator:test --tests "*.FlywayEmitterBondsTest"
```

All tests must pass. If any test calls into `resolveAnchorField` or `columnType` that were just removed, compilation will fail — fix by ensuring the call site uses `BondModelSupport`.

**Suggested commit message:**
```
GAP-B2: remove duplicate anchor-resolution copies from FlywayEmitter

FlywayEmitter had private resolveAnchorField, columnType, idFieldOrNull,
fieldByName methods duplicating BondModelSupport. The column-type
calculation now delegates to BondModelSupport.resolveBond().effectiveSqlType()
for bond ports, and SqlTypeSupport.sqlType() for plain fields. Removes
the three-way sync risk between FlywayEmitter, BondModelSupport, and
GeneratedCrudRuntimeSupport.
```

---

## Phase 2 — Wire ReleaseGateValidator into the Test Suite (GAP-B4)

### Problem

`ReleaseGateValidator.java` exists in the working tree and has a unit test (`ReleaseGateValidatorTest.java`), but:
1. Both files are **untracked** (will be committed in Phase 0).
2. After Phase 0, the validator and test are committed, but the test is the **only** gate. The CI workflow (`ai-beta-gate.yml`) runs `run-traceable-local-release.ps1` — this script must invoke validation. The current script is unknown; verify it calls the DSL `check` task.
3. No end-to-end test exercises the full pipeline: JSON parse → SemanticValidator warns → ReleaseGateValidator blocks.

### Files

- `NPDevContract/dsl/build.gradle`
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ReleaseGateValidatorTest.java`
- `.github/workflows/npdev-ci-validation.yml` (the regular CI workflow)

### Step 2.1 — Verify `run-traceable-local-release.ps1` runs DSL tests

Locate `scripts/quality/run-traceable-local-release.ps1`. Confirm it runs:
```powershell
.\npdev-gradlew.ps1 :NPDevContract:dsl:check
```

If not, add that step. The `check` task already depends on `test`, which runs `ReleaseGateValidatorTest`.

### Step 2.2 — Extend `ReleaseGateValidatorTest` with more coverage

**File:** `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ReleaseGateValidatorTest.java`

The existing test covers `truth_closure_below_target` and `truth_evidence_missing`. Add the following cases:

**Test 2.2.1 — Promotion passes when all dependencies meet target truth level:**

```java
@Test
void releaseGatePassesWhenAllDependenciesMeetTarget() throws Exception {
    Path modelPath = Files.createTempFile("npdev-release-ok-", ".json");
    Files.writeString(modelPath, """
            {
              "namespace": "truth.ok",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Product",
                  "truthLevel": "T5",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ]
                },
                {
                  "name": "Invoice",
                  "truthLevel": "T5",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    {
                      "name": "productId",
                      "type": "reference",
                      "reference": { "target": "Product" }
                    }
                  ]
                }
              ]
            }
            """);

    ModelAst model = new JsonModelParser().parse(modelPath);
    // Provide evidence for both required concepts
    ValidationResult release = new ReleaseGateValidator().validatePromotion(
            model,
            "Invoice",
            TruthLevel.T3_INTEGRATED,
            ReleaseGateValidator.EvidenceProvider.none()  // T3 doesn't require evidence
    );

    assertFalse(release.hasErrors(),
            "Promotion should pass when all reachable bonds meet target truth. Errors: " + release.getErrors());
}
```

**Test 2.2.2 — Authoring warning does NOT block model parsing:**

```java
@Test
void semanticValidatorDoesNotBlockOnTruthEdgeViolation() throws Exception {
    Path modelPath = Files.createTempFile("npdev-truth-warn-", ".json");
    Files.writeString(modelPath, """
            {
              "namespace": "truth.warn",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "CoreEntity",
                  "truthLevel": "T1",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ]
                },
                {
                  "name": "AppEntity",
                  "truthLevel": "T5",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    {
                      "name": "coreId",
                      "type": "reference",
                      "reference": { "target": "CoreEntity" }
                    }
                  ]
                }
              ]
            }
            """);

    ModelAst model = new JsonModelParser().parse(modelPath);
    ValidationResult semantic = new SemanticValidator().validateWithWarnings(model);

    // WARNING only — not an error — so authoring is not blocked
    assertFalse(semantic.hasErrors(), "Truth edge should only warn, not error: " + semantic.getErrors());
    assertTrue(semantic.getWarnings().stream().anyMatch(w -> w.contains("no upward truth edges")),
            "Expected truth-edge warning. Warnings: " + semantic.getWarnings());
}
```

**Test 2.2.3 — Bond closure traverses multiple hops:**

```java
@Test
void bondClosureIncludesTransitiveDependencies() throws Exception {
    Path modelPath = Files.createTempFile("npdev-closure-", ".json");
    Files.writeString(modelPath, """
            {
              "namespace": "truth.chain",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Foundation",
                  "truthLevel": "T1",
                  "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }]
                },
                {
                  "name": "Middle",
                  "truthLevel": "T4",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "foundationId", "type": "reference", "reference": { "target": "Foundation" } }
                  ]
                },
                {
                  "name": "Top",
                  "truthLevel": "T5",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "middleId", "type": "reference", "reference": { "target": "Middle" } }
                  ]
                }
              ]
            }
            """);

    ModelAst model = new JsonModelParser().parse(modelPath);
    ValidationResult release = new ReleaseGateValidator().validatePromotion(
            model,
            "Top",
            TruthLevel.T5_EVIDENCE_BACKED,
            ReleaseGateValidator.EvidenceProvider.none()
    );

    // Both Middle (T4 < T5) and Foundation (T1 < T5) are in the closure
    assertTrue(release.hasErrors());
    long belowTargetCount = release.getDiagnostics().stream()
            .filter(d -> "truth_closure_below_target".equals(d.getCode()))
            .count();
    assertTrue(belowTargetCount >= 2,
            "Both transitive deps should be flagged. Diagnostics: " + release.getDiagnostics());
}
```

### Step 2.3 — Verify CI wiring

Open `.github/workflows/npdev-ci-validation.yml` and confirm that the `NPDevContract:dsl:check` task (or an equivalent that runs all DSL tests) is a required step. If the regular CI only runs a subset, add:

```yaml
- name: DSL contract check (bonds + release gate)
  run: |
    .\npdev-gradlew.ps1 :NPDevContract:dsl:check
```

The `ai-beta-gate.yml` manual gate is already sufficient for the beta0 tag flow. The regular CI workflow should also run DSL tests so that bond-related regressions are caught on every PR.

**Suggested commit message:**
```
GAP-B4: extend ReleaseGateValidator test coverage and verify CI wiring

Add three new test cases: passes when deps meet target, semantic layer
only warns (not errors) on truth violations, transitive bond closure
flags all below-target deps. Verify npdev-ci-validation.yml runs
DSL check task.
```

---

## Phase 3 — Cross-Pack Bond Integration Test (GAP-B6)

### Problem

A bond from a root model concept to a pack-namespaced concept (`catalog::Product`) has never been tested end-to-end from **JSON model file → `ModelSourceResolver` → `SemanticValidator` → `ModelCompiler` → `BondModelSupport.allBonds()` → `FlywayEmitter` DDL**. The test in `FlywayEmitterBondsTest.emitsPackNamespacedNaturalKeyBondSql` tests DDL generation at the `CompiledModel` level with a pre-built `CompiledConcept("cat::Product", ...)` — it does not test that the `::` name survives the JSON → parse → compile → generate pipeline intact.

### Files

**`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ModelSourceResolverTest.java`**

Add a new test at the end of the class.

**`NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/FlywayEmitterBondsTest.java`**

Optionally extend with a full-pipeline test (described below).

### Step 3.1 — Resolver test: pack concept with connectable anchor survives round-trip

Add this test to `ModelSourceResolverTest`:

```java
@Test
void packConceptWithConnectableAnchorIsPreservedInResolvedModel() throws Exception {
    // Pack file: catalog.json
    Path packFile = write("packs/catalog.json", """
            {
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
                }
              ]
            }
            """);

    // Root model: bonds to catalog::Product via skuId
    Path model = write("model.json", """
            {
              "namespace": "order.app",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "packs": [{ "$ref": "packs/catalog.json" }],
              "concepts": [
                {
                  "name": "Order",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    {
                      "name": "productId",
                      "type": "reference",
                      "required": true,
                      "reference": {
                        "target": "catalog::Product",
                        "via": "skuId",
                        "onDelete": "restrict"
                      }
                    }
                  ]
                }
              ]
            }
            """);

    // Step 1: resolver produces a merged JSON with catalog::Product and Order
    ResolvedModelSource source = new ModelSourceResolver().resolve(model);
    JsonNode concepts = source.resolvedRoot().get("concepts");

    assertEquals(2, concepts.size(), "Should have catalog::Product and Order after pack merge");

    // The pack concept must be namespaced
    boolean foundPackConcept = false;
    boolean foundOrderConcept = false;
    for (JsonNode concept : concepts) {
        String name = concept.get("name").asText();
        if ("catalog::Product".equals(name)) {
            foundPackConcept = true;
            // connectable must survive pack merge
            JsonNode fields = concept.get("fields");
            boolean skuAnchorFound = false;
            for (JsonNode field : fields) {
                if ("skuId".equals(field.get("name").asText())) {
                    assertEquals("anchor", field.get("connectable").asText(),
                            "connectable:anchor must survive pack merge");
                    skuAnchorFound = true;
                }
            }
            assertTrue(skuAnchorFound, "skuId anchor field should be present after merge");
        }
        if ("Order".equals(name)) {
            foundOrderConcept = true;
        }
    }
    assertTrue(foundPackConcept, "catalog::Product must be present after pack merge");
    assertTrue(foundOrderConcept, "Order must be present after pack merge");
}
```

### Step 3.2 — Semantic validator test: cross-pack bond passes validation

Add this test to `BondSemanticsSupportTest` (or `ModelSourceResolverTest`):

```java
@Test
void crossPackBondPassesSemanticValidation() throws Exception {
    // Using the same pack+model structure as above
    // Write pack file
    Path packFile = Files.createTempFile("catalog-pack-", ".json");
    Files.writeString(packFile, """
            {
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
                }
              ]
            }
            """);

    // Write the model in a temp directory so pack $ref resolves relatively
    Path tempDir = Files.createTempDirectory("npdev-cross-pack-");
    Path packsDir = tempDir.resolve("packs");
    Files.createDirectories(packsDir);
    Path catalog = packsDir.resolve("catalog.json");
    Files.copy(packFile, catalog);
    Path modelFile = tempDir.resolve("model.json");
    Files.writeString(modelFile, """
            {
              "namespace": "order.app",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "packs": [{ "$ref": "packs/catalog.json" }],
              "concepts": [
                {
                  "name": "Order",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    {
                      "name": "productId",
                      "type": "reference",
                      "required": true,
                      "reference": {
                        "target": "catalog::Product",
                        "via": "skuId",
                        "onDelete": "restrict"
                      }
                    }
                  ]
                }
              ]
            }
            """);

    // Full pipeline: parse (with pack resolution) → validate
    ModelAst model = new JsonModelParser().parse(modelFile);
    ValidationResult result = new SemanticValidator().validateWithWarnings(model);

    assertFalse(result.hasErrors(),
            "Cross-pack bond with valid anchor should pass validation. Errors: " + result.getErrors());
}
```

### Step 3.3 — Full pipeline test: cross-pack bond produces correct FK DDL

Add this test to `FlywayEmitterBondsTest` (or a new `CrossPackBondEndToEndTest` in the generator module):

```java
@Test
void crossPackBondProducesCorrectFkDdlThroughFullPipeline() throws Exception {
    // Build a CompiledModel that simulates what ModelCompiler would produce
    // after ModelSourceResolver resolves a pack with catalog::Product
    CompiledField skuAnchor = new CompiledField(
            "skuId", "string", "String", false, false, true,
            List.of(), null, null, null, null, List.of(), null, "anchor");

    // Pack concept: name includes the pack namespace as compiled by ModelCompiler
    // Table name must be derived by SqlIdentifierSupport.tableName() from "catalog::Product"
    // Expected: toSnakePlural("catalog::Product") → "catalog_products"
    CompiledConcept product = new CompiledConcept(
            "catalog::Product", "CatalogProduct", null, // tableName=null → derived from name
            List.of(
                    new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                    skuAnchor
            )
    );

    CompiledReferenceSemantics viaSku = new CompiledReferenceSemantics(
            "catalog::Product", false, null, List.of(), List.of(), null, null, List.of(), null, null,
            "skuId", "restrict");

    CompiledConcept order = new CompiledConcept(
            "Order", "Order", "orders",
            List.of(
                    new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                    new CompiledField("productId", "reference", "java.util.UUID", false, true, false,
                            List.of(), "catalog::Product", viaSku, null, null, List.of(), null, null)
            )
    );

    Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
    concepts.put(product.getName(), product);
    concepts.put(order.getName(), order);
    Path file = new FlywayEmitter().emitRepeatableSchema(
            new CompiledModel("catalog-app", "v1", concepts), tempDir);
    String sql = Files.readString(file);

    // Table name from "catalog::Product" must use "_" not "::" in SQL
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS catalog_products"),
            "catalog::Product must produce SQL table catalog_products. SQL:\n" + sql);
    assertFalse(sql.contains("::"),
            "No '::' characters should appear in generated SQL. SQL:\n" + sql);

    // FK from orders.product_id references catalog_products.sku_id
    assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS product_id VARCHAR(255);"),
            "Bond port should use anchor string type. SQL:\n" + sql);
    assertTrue(sql.contains(
                    "FOREIGN KEY (product_id) REFERENCES catalog_products (sku_id) ON UPDATE CASCADE ON DELETE RESTRICT"),
            "FK must reference catalog_products(sku_id). SQL:\n" + sql);
}
```

**Note:** This test requires `CompiledConcept` to accept `tableName=null` and auto-derive it from the concept name via `SqlIdentifierSupport.tableName()`. Verify the `CompiledConcept` constructor handles null `tableName` by falling back to `SqlIdentifierSupport.toSnakePlural(name)`. If it does not, a small null-guard is needed in `SqlIdentifierSupport.tableName()` — but looking at the current implementation, it already does:

```java
// SqlIdentifierSupport.tableName() (line 64-73):
String table = entity.getTableName();
if (table == null || table.isBlank()) {
    table = toSnakePlural(entity.getName());  // ← falls back to name
}
return safeSqlIdentifier(table);
```

And `toSnake()` already does:
```java
String trimmed = value.trim().replace("::", "_");  // ← :: becomes _
```

So `catalog::Product` → `toSnakePlural("catalog::Product")` → `catalog_products`. No fix needed — just a test.

**Verification:**
```powershell
.\npdev-gradlew.ps1 :NPDevContract:dsl:test --tests "*.ModelSourceResolverTest" --tests "*.BondSemanticsSupportTest"
.\npdev-gradlew.ps1 :NPDevGenerator:generator:test --tests "*.FlywayEmitterBondsTest"
```

**Suggested commit message:**
```
GAP-B6/B7: test cross-pack bond end-to-end and pack concept table naming

Add tests covering: pack merge preserves connectable:anchor fields;
cross-pack bond passes semantic validation; catalog::Product generates
catalog_products SQL table (:: → _); FK DDL references correct
cross-pack table and anchor column.
```

---

## Phase 4 — Pack Concept Table-Name Convention Test (GAP-B7)

### Problem

`SqlIdentifierSupport.toSnake()` already replaces `::` with `_` (line 26: `replace("::", "_")`), so `catalog::Product` auto-derives to `catalog_products` as the table name. However, there is no unit test that explicitly verifies this convention for pack-namespaced names. The gap is purely in test coverage — the production code is correct.

### File

`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/SqlIdentifierSupportTest.java` *(untracked — committed in Phase 0)*

### Changes

Add the following test cases to the existing `SqlIdentifierSupportTest` class:

```java
@Test
void toSnakeReplacesPackNamespaceSeparatorWithUnderscore() {
    assertEquals("catalog_product", SqlIdentifierSupport.toSnake("catalog::Product"));
    assertEquals("my_lib_order_item", SqlIdentifierSupport.toSnake("my_lib::OrderItem"));
}

@Test
void tableNameForPackNamespacedConceptUsesUnderscoreAndPlural() {
    // Simulate a compiled concept with no explicit tableName (null)
    // so tableName() falls back to toSnakePlural(name)
    CompiledConcept packConcept = new CompiledConcept(
            "catalog::Product", "CatalogProduct", null,
            List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
    );
    assertEquals("catalog_products", SqlIdentifierSupport.tableName(packConcept));
}

@Test
void tableNameForPackNamespacedConceptWithExplicitTableNamePassesThroughUnchanged() {
    // When ModelCompiler sets an explicit tableName on a pack concept,
    // SqlIdentifierSupport must use that, not re-derive it
    CompiledConcept packConcept = new CompiledConcept(
            "catalog::Product", "CatalogProduct", "cat_products",
            List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
    );
    assertEquals("cat_products", SqlIdentifierSupport.tableName(packConcept));
}

@Test
void junctionTableNameForPackNamespacedSourceConceptHasNoColons() {
    CompiledConcept source = new CompiledConcept(
            "catalog::Variant", "CatalogVariant", null,
            List.of(
                    new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                    new CompiledField("productId", "reference", "java.util.UUID", false, false, false)
            )
    );
    CompiledField portField = source.getFields().get(1);
    String junction = SqlIdentifierSupport.junctionTableName(source, portField);

    // Must contain no :: characters
    assertFalse(junction.contains("::"), "Junction table must not contain :: chars: " + junction);
    // Should follow snake_table_column pattern
    assertTrue(junction.startsWith("catalog_variants_"), "Should be prefixed by derived table name: " + junction);
}
```

**Verification:**
```powershell
.\npdev-gradlew.ps1 :NPDevContract:dsl:test --tests "*.SqlIdentifierSupportTest"
```

**Suggested commit message:**
```
GAP-B7: add SqlIdentifierSupport tests for pack-namespaced concept names

Verify that catalog::Product → catalog_products (:: replaced with _,
pluralized). Verify junction table names contain no :: characters.
Explicit tableName on a pack concept is preserved as-is.
```

---

## Implementation Order for Cursor

Execute phases in this order — do not skip to a later phase without completing Phase 0:

```
Phase 0-A  →  Phase 0-B  →  (Verify build green)  →  Phase 1  →  Phase 2  →  Phase 3 + Phase 4 (parallel)
```

Phases 1, 2, 3, 4 are mutually independent once Phase 0 is done.

---

## Appendix — Gaps Already Resolved (Do NOT re-implement)

### GAP-B1 — Silent bond drop (FIXED)

`BondModelSupport.resolveBond()` at lines 172–177 already **throws** `IllegalStateException` when `via` is set but the field is not found on the target concept. The analysis doc described an older version of this method.

```java
// Current code — throws, does NOT silently return empty:
if (anchor == null) {
    String via = sourceField.getReferenceSemantics() == null ? null : sourceField.getReferenceSemantics().getVia();
    throw new IllegalStateException("Declared bond " + sourceConcept.getName() + "."
            + sourceField.getName() + " has no resolvable target anchor"
            + (via == null || via.isBlank() ? "" : ": " + via));
}
```

No action needed.

### GAP-B3 — Junction table name collision on truncation (FIXED)

`SqlIdentifierSupport.safeSqlIdentifier()` uses a **hash suffix** for identifiers longer than 63 characters:

```java
return normalized.substring(0, LONG_IDENTIFIER_PREFIX_LENGTH) + "_" + shortHash(rawName);
```

The hash is of the **full original name**, so two different long names that share a 54-char prefix still get distinct suffixes. The `FlywayEmitterBondsTest.longJunctionIdentifiersUseStableHashSuffix()` test already pins this behavior:

```java
assertEquals(63, junction.length());
assertTrue(junction.matches("[a-z0-9_]+_[0-9a-f]{8}"), junction);
```

No action needed.

### GAP-B5 — `connectable: anchor` redundant with `unique` (FIXED)

`SemanticValidator.isConnectableAnchor()` at line 1390–1393 already requires **both** `unique: true` AND `connectable: "anchor"` for non-id anchor fields:

```java
private static boolean isConnectableAnchor(FieldAst field) {
    return field.isId()
            || (field.isUnique() && "anchor".equals(normalize(field.getConnectable())));
}
```

A `unique: true` field without `connectable: "anchor"` is **not** accepted as a valid `via` target. The error message also confirms this:

```
reference via must target a connectable anchor (the id field, or a non-id field
with unique=true and connectable:anchor)
```

No action needed.

---

## Reference: Key File Locations

| File | Role |
|---|---|
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/SqlIdentifierSupport.java` | Canonical SQL naming: tableName, columnName, junctionTableName, safeSqlIdentifier |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/SqlTypeSupport.java` | DSL type → SQL type mapping |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/bonds/BondModelSupport.java` | Bond resolver: allBonds, resolveBond, cardinality, Bond.effectiveSqlType() |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/FlywayEmitter.java` | Flyway DDL emitter — must delegate to BondModelSupport (Phase 1 target) |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java` | FinalApp schema emitter — already updated for bonds in commit 0d4d3b9 |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ReleaseGateValidator.java` | Truth-closure gate for beta0 release promotion |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java` | isConnectableAnchor, validateBondTruthEdge, validateReferenceSemantics |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java` | Pack resolution and fragment inclusion |
| `.github/workflows/ai-beta-gate.yml` | Manual-only beta0 gate (Docker + Windows CI) |
| `.github/workflows/npdev-ci-validation.yml` | Regular PR CI |
