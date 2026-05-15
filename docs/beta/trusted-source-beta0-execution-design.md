# Trusted-Source Beta0 Execution Design

Checkpoint: Items #18/#19 design only.

Status: proposed for human approval. This document does not approve release eligibility and does not implement trusted-source execution.

CP3 scope reconciliation uses Path B. Active trusted-source golden scenarios are deferred under `golden-ai-scenarios/deferred/trusted-source/`, the active beta scope no longer requires trusted-source scenario evidence, and this design remains review material rather than active execution proof.

## Scope Decision

Trusted-source panels and procedures should be treated as Beta0-deferred until this design is approved and the implementation checkpoint proves the pipeline end to end.

After approval, trusted-source support should be implemented through runtime generation and runtime execution, not through the structured command surface. Structured commands remain limited to bounded release operations such as schema validation, Gradle tasks, and localhost REST smoke checks. They may compile or test the generated runtime, but they must not become a generic custom-code execution API.

The Beta0 claim after implementation should be:

- Trusted-source procedure and panel source files are discovered only from declared scenario-local or generated-app-local roots.
- Source files are manifest-locked, hashed, admitted by policy, compiled or statically validated, packaged into the generated app/runtime, invoked through runtime APIs, and verified by smoke evidence.
- Tenant and role checks are enforced before runtime invocation, not merely recorded after execution.

Until the implementation checkpoint exists, release evidence must say trusted-source execution is not yet supported.

## Source Discovery

Trusted-source files are discovered from the `implementation.entrypoint` field already present in the AI model schema.

Discovery rules:

- The entrypoint must be relative.
- Absolute paths, drive-qualified paths, UNC paths, URI-like paths, and traversal segments are rejected.
- Normalized paths must remain under one of the allowed roots for the current scenario or generated app.
- Symlinks, junctions, or reparse points must resolve back under the same allowed root.
- Discovery records the source document, model path, declared entrypoint, normalized path, file size, and SHA-256 hash.

Allowed roots for design approval:

- Scenario-local trusted source root for AI beta fixtures.
- Generated app trusted source root created by the generator.
- Test fixture roots used only by regression tests.

No source file may be discovered from repository-wide script folders, user home directories, temp directories, dependency caches, or network locations.

## Admission And Hashing

Admission is a separate step from discovery.

Beta0 trusted-source fixtures must use mandatory manifest and hash locking. Every trusted-source file must have a manifest entry before it can be admitted. The manifest entry must include the declared source kind, relative path, normalized path, language, SHA-256 hash, expected runtime binding, and policy version. Missing manifest entries and hash mismatches fail closed.

Each trusted-source file must produce an admission record containing:

- source kind: panel or procedure;
- source path;
- normalized path;
- SHA-256 hash;
- manifest path;
- manifest entry id;
- manifest hash;
- manifest match status;
- language;
- declared class and method for Java procedures;
- declared panel route and data bindings for panels;
- admission policy version;
- admitted or rejected status;
- rejection reason when rejected.

Admission must fail closed when:

- the source file is missing;
- the manifest is missing;
- the source file has no manifest entry;
- the source hash does not match the manifest hash;
- the file extension does not match the declared language;
- the source path escapes the allowed root after normalization;
- the file uses unsupported external dependencies;
- inline code, external URLs, dynamic imports, eval-like execution, or shell/process APIs are declared;
- the trusted-source block lacks an associated tenant and role contract.

The implementation checkpoint must introduce a generated trusted-source manifest. The manifest is the producer/consumer boundary between normalization/generation and runtime execution.

## Compile And Build Path

Trusted-source procedures use Java and must compile as part of the generated runtime build.

Compile path:

- The generator copies admitted Java procedure source into a generated trusted-source package.
- The generated code compiles against a minimal trusted procedure API.
- Compilation failures are release-blocking for trusted-source scenarios.
- The report records compiler command, exit code, duration, source hashes, class files or jar artifact hashes, and diagnostics log path.

Trusted-source panels use static HTML and JavaScript files and must pass static admission and generated route smoke checks.

Panel build path:

- The generator copies admitted panel assets into a generated static asset area.
- The panel route is bound to the declared panel metadata.
- Static validation rejects external network URLs, dynamic script loading, inline eval, and missing data/action bindings.
- The report records source hashes, emitted asset paths, route bindings, and validation diagnostics.

No implementation should execute trusted panel JavaScript inside PowerShell or the structured command runner.

## Java Trusted Procedure Containment Model

Beta0 Java trusted procedures are not treated as arbitrary Java programs. A restricted `NPDevProcedureContext` is necessary but not sufficient, because ordinary Java source can call JDK APIs directly. Beta0 containment therefore uses mandatory static source validation before compilation, manifest/hash locking before admission, and bytecode/package inspection after compilation.

Chosen Beta0 enforcement mechanism:

- Strict source validation is the primary Beta0 containment mechanism.
- Source files are parsed before compilation and rejected if they contain forbidden imports, fully qualified forbidden type references, forbidden method calls, package declarations outside the generated trusted package, native methods, annotations that imply dynamic loading, static initializers, or dependency declarations.
- Compilation runs only after source validation passes and uses the generated trusted-source manifest as the file allowlist.
- Bytecode inspection is a secondary guard that rejects compiled classes referencing forbidden owners, methods, or packages in the constant pool.
- Runtime invocation only loads classes named in the manifest from the generated trusted-source output area.

This is sufficient for Beta0 because trusted-source fixtures are release-owned, local, manifest-locked, dependency-free, and intentionally small. Beta0 does not claim a general-purpose sandbox for arbitrary third-party Java. If future releases need arbitrary Java containment, they must move to a stronger isolation model such as process/container isolation, JVM-level instrumentation, or a reduced DSL.

Forbidden imports, type references, and API calls:

- `java.io.*`
- `java.nio.file.*`
- `java.net.*`
- `java.lang.Runtime`
- `java.lang.Process`
- `java.lang.ProcessBuilder`
- `java.lang.System.getenv`
- `java.lang.System.getProperties`
- `java.lang.System.setProperties`
- `java.lang.System.exit`
- `java.lang.reflect.*`
- `java.lang.invoke.*`
- `java.lang.Class`
- `java.lang.ClassLoader`
- `java.util.ServiceLoader`
- `java.lang.Thread`
- `java.lang.ThreadLocal`
- `java.util.Timer`
- `java.util.concurrent.*`
- `javax.script.*`
- `sun.*`
- `jdk.*`

Additional Java restrictions:

- No wildcard imports except allowlisted Java collections if the validator can resolve them safely.
- No static initializers.
- No `native` methods.
- No custom class loaders.
- No reflection or method handles.
- No thread creation, executors, futures, timers, or asynchronous background work.
- No direct filesystem, environment, network, process, or system property access.
- No arbitrary dependencies beyond the generated trusted procedure API and a small allowlist of immutable collection/value types.
- No annotations that load external processors, frameworks, scripts, or services.

Allowed Java surface for Beta0:

- the generated trusted procedure package;
- `NPDevProcedureContext` or equivalent;
- Java primitives, `String`, boxed numbers, `Boolean`, `BigDecimal`, `UUID`, `Instant`, and simple collection/value types such as `List`, `Map`, `Set`, and `Optional`;
- local helper methods inside the same trusted class when they pass the same static checks.

Negative tests must prove at least one blocked example for filesystem, process execution, environment variables, network APIs, reflection/class loading, threads/concurrency, `System.exit`, arbitrary dependency import, missing manifest entry, and hash mismatch.

## Runtime Invocation Model

Trusted-source procedures execute only through a generated runtime procedure endpoint or runtime service adapter.

Invocation rules:

- The caller provides a generated test identity with tenant and roles.
- Runtime authorization checks the required role before procedure execution.
- Runtime tenant scope checks the caller tenant and target data tenant before procedure execution.
- The procedure receives a restricted context object and cannot access raw process, filesystem, environment, network, or arbitrary class loading APIs through the supported API.
- Procedure output must be JSON-serializable.
- Standard output and standard error are captured as diagnostics, not mixed into the procedure result.

Trusted-source panels are invoked through generated UI routes and action bindings.

Panel containment rules:

- Generated routes must emit a Content Security Policy that allows scripts, styles, images, connect targets, and forms only from the generated app origin unless a narrower route-specific policy is configured.
- External scripts, external styles, external images, external fonts, external fetch URLs, external websocket URLs, and cross-origin form actions are rejected during admission.
- `eval`, `new Function`, dynamic `import(...)`, inline event handler attributes, `javascript:` URLs, and script loaders are rejected during admission.
- Panel JavaScript may call only same-origin generated endpoints that appear in the panel binding manifest.
- Panel actions must be server-side generated endpoints. They may not bypass server-side role and tenant checks.
- Server-side role and tenant enforcement is authoritative even if the panel hides or shows UI controls.
- A missing, wrong-role, or wrong-tenant request must fail before any procedure/action side effect.

Panel smoke rules:

- The route is reachable for an authorized tenant/role.
- The route is rejected for a missing or wrong role.
- The route is rejected or filtered for the wrong tenant.
- Declared data bindings and actions resolve to generated runtime endpoints.

## Procedure Context API

The design uses an `NPDevProcedureContext` or equivalent minimal API.

Required context capabilities:

- `tenantId`
- `actorId`
- `roles`
- `input`
- `readEntity`
- `listEntities`
- `saveEntity`
- `callProcedure`
- `emitEvent`
- `audit`
- `returnJson`

Required enforcement:

- Every entity access is tenant-filtered by the context.
- Every mutation requires the procedure role and side-effect policy.
- Bulk operations respect `maxAffectedRows`.
- Idempotency is enforced for side-effecting procedures when required by the procedure contract.
- Audit records include tenant, actor, roles, procedure id, source hash, and outcome.

Explicitly excluded from the Beta0 trusted context:

- raw SQL;
- filesystem access;
- process execution;
- environment-variable access;
- arbitrary network calls;
- reflection-based class loading;
- dependency download at runtime.

## Role And Tenant Enforcement

Role and tenant enforcement must be tested before a trusted-source scenario can count as release evidence.

Required positive checks:

- authorized user with correct tenant and role can invoke the trusted procedure;
- authorized user with correct tenant and role can view the trusted panel route;
- procedure result only includes tenant-scoped data for the caller tenant;
- panel data bindings only return tenant-scoped data for the caller tenant.

Required negative checks:

- missing role is rejected;
- wrong role is rejected;
- wrong tenant cannot read or mutate another tenant's data;
- tenant-scoped procedure cannot emit cross-tenant events;
- panel action cannot invoke a procedure outside the role/tenant contract;
- missing source, changed source hash, path traversal, and unsupported language all fail closed.

## Smoke Evidence

Trusted-source implementation must emit focused smoke evidence before release policy can treat it as blocking.

Required evidence:

- source discovery report;
- admission report;
- compile or static validation report;
- generated artifact manifest;
- procedure invocation report;
- panel route/action smoke report;
- role negative case report;
- tenant negative case report;
- hashes for source files and generated artifacts;
- command records with exit code, duration, log path, and run id.

The aggregate release gate may consume trusted-source evidence only after these reports are generated from the current source state and share the release run id.

## Report Schema Fields

The trusted-source implementation report should include:

- `schemaVersion`
- `runId`
- `generatedAt`
- `scriptPath` or producer path
- `overallStatus`
- `releaseBlocking`
- `trustedSourceSupportStatus`
- `policyVersion`
- `sourceDiscovery`
- `admission`
- `manifestLock`
- `javaContainment`
- `panelContainment`
- `compile`
- `runtimeInvocation`
- `panelSmoke`
- `procedureSmoke`
- `roleChecks`
- `tenantChecks`
- `negativeCases`
- `artifacts`
- `commands`
- `failures`
- `deferredItems`

Every blocking boolean must include a concrete source such as command, exit code, report path, artifact path, endpoint result, hash, or log path.

The current `scripts/reports/out/trusted-source-beta0-design-report.json` is checkpoint-only design evidence. It is generated for review in this design checkpoint and must not be treated as a release producer for trusted-source execution. A future implementation checkpoint must add a repo-owned producer script and schema before any trusted-source implementation report can become release evidence.

## Implementation Checkpoint Acceptance Criteria

The future implementation checkpoint is acceptable only if:

- no structured-command request type is added for trusted-source execution;
- trusted-source execution occurs through generated runtime paths;
- path traversal and source-root escape tests fail closed;
- every trusted source file has a manifest entry and SHA-256 hash;
- missing manifest entries and hash mismatches fail closed;
- Java source validation rejects forbidden imports, type references, and API calls;
- bytecode inspection rejects forbidden owners, methods, and packages after compilation;
- missing source and changed hash tests fail closed;
- unsupported language and external dependency tests fail closed;
- panel containment rejects external scripts, styles, images, fetch URLs, websocket URLs, eval, Function, dynamic import, inline event handlers, and javascript URLs;
- Java procedure compilation failure blocks trusted-source evidence;
- authorized procedure and panel smokes pass;
- missing-role and wrong-tenant smokes fail closed;
- report schema validation covers the new trusted-source evidence report;
- aggregate release eligibility remains false unless all blocking trusted-source reports are fresh, directly evidenced, and policy-aligned.
