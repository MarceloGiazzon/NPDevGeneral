# AI Custom Procedure Contract

A custom procedure beta asset can be either a declarative business routine or an explicit trusted-source routine.

Required fields:

- `schemaVersion`: `ai-custom-procedure.v1`
- `procedureId`
- `executionMode`: `governed`
- `trust`: `trusted` or `inproc`
- `inputs`
- `outputs`
- `sideEffects`
- either `steps` or `implementation`

Allowed step types:

- `readConcept`
- `listConcepts`
- `runQuery`
- `saveConcept`
- `callProcedure`
- `return`

Trusted-source procedures use an `implementation` block:

- `mode`: `trustedSource`
- `language`: `java`
- `entrypoint`: local `.java` source file inside the scenario directory
- `className`: Java class name to compile and load
- `method`: public method name, default `execute`

The Java method receives an `NPDevProcedureContext` argument and may return any JSON-serializable Java value made from maps, lists, strings, numbers, booleans, arrays, or `null`. The beta runner captures `System.out.print(...)` and `System.err.print(...)` output as diagnostics evidence while keeping the procedure result JSON clean.

Direct inline script fields, external command declarations, and external dependency graphs still fail. Free code must live in tracked source files so NPDev can hash, admit, execute, and attach evidence for the exact code that ran.
