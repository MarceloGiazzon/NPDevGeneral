# Model Contract

The model contract describes business concepts authored by the NPDev user.

A model should define neutral platform structure: namespace, version, concepts, fields, invariants, flows, orchestration metadata, plugin needs, and UI metadata.

`concepts` is the canonical write format. The legacy top-level `entities` key is accepted only as a read-compatible import alias for immediately previous stable inputs.

A model should not require NPDev core code to know domain vocabulary from a generated app. Those words belong in model files, generated artifacts, examples, samples, or tests.

Canonical schema path: schemas/authoring/model.schema.json.
