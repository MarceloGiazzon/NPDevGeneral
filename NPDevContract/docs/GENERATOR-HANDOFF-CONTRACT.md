# Generator Handoff Contract

The handoff contract is the package boundary between NPDevEditor and NPDevGenerator.

The Editor should export model.json, config.json, a validation report, and a handoff manifest. The Generator should be able to run from those files without depending on Editor internals.

Canonical schema paths:

- schemas/authoring/handoff-manifest.schema.json
- schemas/authoring/validation-report.schema.json
- schemas/generator/generation-options.schema.json
- schemas/generator/generation-report.schema.json
- schemas/generator/compiled-model.schema.json
- schemas/generator/generated-artifact-index.schema.json
