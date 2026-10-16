# AI Custom Procedure Contract

A custom procedure beta asset can be either a declarative business routine or an explicit untrusted-extension routine.

Required fields:

- `schemaVersion`: `ai-custom-procedure.v1`
- `procedureId`
- `executionMode`: `governed`
- `trust`: `untrustedExtension` or `inproc`
- `inputs`
- `outputs` (field types: `string`, `text`, `email`, `integer`, `number`, `boolean`, `date`, `datetime`, `uuid`, `array`, `object`)
- `sideEffectType`: `none`, `single-entity`, `bulk`, or `workflow`
- `requiredRole`
- `tenantScoped`
- `maxAffectedRows`
- either `steps` or `implementation`

Optional fields: `type`, `sideEffects` (`none`, `concept:<Concept>`, or `workflow:<id>`), `allowedEntities`, `idempotency`.

Allowed step types:

- `readConcept`
- `listConcepts`
- `runQuery`
- `saveConcept`
- `callProcedure`
- `return`

Untrusted-extension procedures use an `implementation` block:

- `mode`: `untrustedExtension`
- `language`: `java`
- `entrypoint`: local `.java` source file inside the scenario directory
- `className`: Java class name to compile and load
- `method`: public method name, default `execute`

The Java method receives an `NPDevProcedureContext` argument and may return any JSON-serializable Java value made from maps, lists, strings, numbers, booleans, arrays, or `null`. The beta runner captures `System.out.print(...)` and `System.err.print(...)` output as diagnostics evidence while keeping the procedure result JSON clean.

Direct inline script fields, external command declarations, and external dependency graphs still fail. Free code must live in tracked source files so NPDev can hash, admit, execute, and attach evidence for the exact code that ran.
