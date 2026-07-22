# Model Contract

The model contract describes business concepts authored by the NPDev user.

A model should define neutral platform structure: namespace, version, concepts, fields, invariants, flows, orchestration metadata, plugin needs, and UI metadata.

`concepts` is the canonical write format. The legacy top-level `entities` key is accepted only as a read-compatible import alias for immediately previous stable inputs.

A model should not require NPDev core code to know domain vocabulary from a generated app. Those words belong in model files, generated artifacts, examples, samples, or tests.

Canonical schema path: schemas/authoring/model.schema.json. This file is kept byte-identical
to `schemas/model.schema.json`, `dsl/resources/Schemas/model.schema.json`, and the classpath
copy at `dsl/src/main/resources/schema/model.schema.json` (different consumers resolve the
schema from different relative roots, so one logical schema exists as four physical files).
`StructuralSchemaAssetConformanceTest` (in `NPDevContract/dsl`) asserts all four stay aligned —
run it after any schema edit; it is the only thing that catches drift between copies.

## Split model files

`model.json` may use NPDev-local include refs to keep authored models small. This is not JSON Schema remote `$ref` behavior. NPDev treats an object shaped exactly like `{ "$ref": "relative/path.json" }` as a local source include when it appears in a supported model array or in the top-level `fragments` array.

Use portable forward-slash paths in JSON:

```json
{ "$ref": "concept/product.json" }
```

Recommended layout:

```text
examples/store-app/model.json
examples/store-app/concept/user.json
examples/store-app/concept/consumer.json
examples/store-app/concept/product.json
examples/store-app/plugin/signXML.json
examples/store-app/plugin/sendMail.json
examples/store-app/plugin/GoogleDrive.json
```

Concept refs in `concepts` point to one normal concept object. Top-level `fragments` point to partial model files that may contribute sections such as `capabilities`, `bindings`, `flows`, `procedures`, `panels`, `events`, and `metadata`.

Resolution is deterministic: inline root entries stay first, array-local refs expand at their declaration position, and top-level fragments append after root inline entries in listed order. Nested refs resolve relative to the file that contains the ref.

## Bonds and truth levels

A bond is a pure pointer from a source reference field to a target anchor. The source field
is authored as `type: "reference"` with `reference.target`; `reference.via` selects the
target anchor and defaults to `id`; `reference.onDelete` is `restrict`, `cascade`, or
`nullify` and defaults to `restrict`.

Anchors are target fields that are either the id field, `connectable: "anchor"`, or unique.
Natural-key anchors such as `skuId` are valid and drive the generated Java/SQL type of the
source field. Bonds remain loose typed ID/value columns; generated code does not use JPA
relation objects.

`reference.multiple: true` means a pure-pointer set relation. Generated storage uses an
auto-synthesized junction table and explicit set operations; authors do not create join
concepts and link-carried data is not supported.

Concepts may declare `truthLevel` from `T0` through `T6`. Authoring validation only warns
when a higher-truth concept points to a lower-truth concept. Release validation is separate:
promotion blocks when the reachable bond closure is below the requested truth level, and
T4+ promotion requires evidence from existing NPDev proof artifacts.

Before enabling generated FK constraints against existing data, run a bond inspection or
equivalent precheck and clean dangling source values. The CLI command is:

```bash
npdev inspect bonds --model path/to/model.json
```
