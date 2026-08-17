# NPDev — Design-Boundary Lift Roadmap

> **Generated:** 2026-07-13 · **Branch at capture:** `beta1-vision-spine`
> **Objective:** Turn every one of the six **accepted design boundaries** in §6 of
> `OPEN_GAPS_AND_ROADMAP.md` (generated, not committed since md-zero-2026-08-11 PLAN.md Phase 6) into a **supported platform feature**. When
> this roadmap closes, none of ARCH-6 / ARCH-7 / ARCH-loop / ARCH-upload / ARCH-compound-unique /
> ARCH-13 is a constraint anymore — each has a first-class, tested, live-verified implementation.
>
> Companion to the boundary definitions in `OPEN_GAPS_AND_ROADMAP.md §6` (generated, not committed since md-zero-2026-08-11 PLAN.md Phase 6).
> Same authoring contract as that document: every item carries a **stable ID** and the same six
> fields (What / Where / Why / How / Definition of Done / Verify) so an autonomous agent can pick up
> any item without re-deriving context.

---

## 0. How to read this document (agent instructions)

- Stable IDs: each boundary is a **feature** (`LIFT-EXPR`, `LIFT-UNIQUE`, `LIFT-ROWOPS`, `LIFT-QUERY`,
  `LIFT-UPLOAD`, `LIFT-LOOP`); each feature ships in numbered **phases** (`LIFT-UPLOAD-P1` …). Cite
  the phase ID in commits/PRs.
- **Status vocabulary:** `OPEN` (not started) · `IN-PROGRESS` · `DONE` (verified live) ·
  `BLOCKED` (waiting on another phase).
- **Locked design decisions (from the 2026-07-13 kickoff questions) — do not re-litigate:**
  1. **File storage → pluggable file-store adapter** (`*-inproc` filesystem for dev, object-store for
     prod), NOT a DB blob column.
  2. **Expression engine → extend the in-repo `ComputedExpression`** (dependency-free), NOT a CEL
     library, NOT a parser-only patch.
  3. **ARCH-7 → keep capabilities pure + add a query-backed step** (fix `where`, feed filtered rows in
     as input); do NOT relax the capability sandbox.
  4. **Authoring reach → full stack incl. NPDevEditor React UI** for every feature.
- **Global rules (identical to the prior roadmap — repeated so this doc stands alone):**
  - **Restage after kernel/adapter/generator Java change:**
    `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs`
    (pass `-RuntimeHostLibsDir` to both this and `Build-NpdevApp.ps1` or the app keeps a stale jar).
  - **Schema mirror ×4** — every `model.schema.json` edit mirrors to all four copies:
    `NPDevContract/schemas/model.schema.json`, `NPDevContract/schemas/authoring/model.schema.json`,
    `NPDevContract/dsl/src/main/resources/schema/model.schema.json`,
    `NPDevContract/dsl/resources/Schemas/model.schema.json`.
  - **Build output → `D:\WorkSpace\NPDev\Build`**; evidence/scratch →
    `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`. Never inside the repo.
  - **`npdev-generated/` is hash-guarded** — emit runtime from templates, never post-edit.
- **Verification bar (per feature, matching the prior roadmap):** DSL/kernel/generator JUnit green +
  a **live** generate→build→run→REST/browser check on an H2-backed sample (127.0.0.1, register a real
  tenant — never `"default"`, per ARCH-15). No feature is `DONE` on unit tests alone.

---

## 1. Priority index (machine-parseable)

Ordered by the recommended critical path (value × independence ÷ risk). The first four are largely
independent and low-to-medium risk; the last two are new subsystems.

| Order | Feature ID | Boundary lifted | Phases | Risk | Depends on |
|---|---|---|---|---|---|
| 1 | LIFT-EXPR | ARCH-6 (hand-rolled DNF invariant grammar) | P1–P5 | Medium | — |
| 2 | LIFT-UNIQUE | ARCH-compound-unique (multi-field unique rejected) | P1–P5 | Low-Med | — |
| 3 | LIFT-ROWOPS | ARCH-13 (declared Panel no create/delete-row) | P1–P4 | Low | — (reuses Workbench rowOps) |
| 4 | LIFT-QUERY | ARCH-7 (capability can't get live filtered data) | P1–P4 | Low-Med | — |
| 5 | LIFT-UPLOAD | ARCH-upload (no file-upload primitive) | P1–P6 | High | — (new subsystem) |
| 6 | LIFT-LOOP | ARCH-loop (no loop step in Flows) | P1–P7 | High | — (durable resume; P6 sequential await-in-loop DONE, P7 parallel await-in-loop DESIGNED-ONLY/deferred) |

**Critical path:** none of the six hard-depends on another, so they can parallelize. Recommended
serial order for a single implementer is the table order: build expression + validation muscle first
(LIFT-EXPR/UNIQUE), then the UI-shaped ones (LIFT-ROWOPS), then the runtime-shaped one (LIFT-QUERY),
then the two big subsystems (LIFT-UPLOAD, LIFT-LOOP) last where the most design care is needed.

**Phase-count total:** 27 phases across 6 features.

---

## 2. LIFT-EXPR — Unified expression engine (lifts ARCH-6)

**Decision locked:** extend the in-repo, dependency-free
[`ComputedExpression`](../../../NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/expr/ComputedExpression.java)
(already does arithmetic / comparison / logical / parens / field-refs for AutoPanel computed columns)
into the *single* expression grammar for both computed values **and** invariants — retiring the
hand-rolled DNF matcher in
[`CelInvariantEngine`](../../../NPDevKernel/adapters/expression-cel/src/main/java/com/npdev/adapters/expression/cel/CelInvariantEngine.java).

### LIFT-EXPR-P1 — Boolean-complete the evaluator
- **Status:** DONE (2026-07-13) · **Risk:** Medium
- **Note:** `ComputedExpression` already had parens/`&&`/`||`/comparisons/arithmetic/unary `!`/
  `null`/string literals before this phase (more complete than this doc assumed). Added: dotted
  field-path support (`cliente.tipo`), `evaluateBoolean(scope)`, strict null-equality semantics
  (`null == null` true, `null == ""` false — previously coerced via `stringify`), and two static-
  analysis APIs (`referencedFields`, `isBooleanShaped`) used by P3. Corpus-equivalence vs. the old
  matcher is exercised transitively by the full `CelInvariantEngineTest` suite staying green under
  P2's delegation (a dedicated standalone corpus test was judged redundant with that).
- **What:** Make `ComputedExpression` a total boolean+value evaluator: parentheses (have), `&&`/`||`
  (have), comparisons (have), arithmetic (have), plus **unary `!`**, `null` literal + null-safe
  comparison, string literals, and a typed `evaluateBoolean(scope)` entry point.
- **Where:** `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/expr/ComputedExpression.java` (+ its
  tokenizer/parser siblings in the same `expr/` package); tests beside the existing
  `ComputedExpression` tests in `NPDevContract/dsl/src/test/...`.
- **Why:** Invariants need the operators DNF forbade (`!`, parens) and null handling; the value path
  already needs arithmetic. One grammar removes the two-system split.
- **How:**
  1. Add unary `!` and `null` to the grammar + Pratt/recursive-descent parser; define null-comparison
     semantics (`x == null`, `null` propagation in arithmetic → error or null, pick and document).
  2. Add `evaluateBoolean(Map<String,Object> scope)` returning a strict boolean (non-boolean top-level
     result = parse-time type error).
  3. Property-test operator precedence + De Morgan equivalence against the OLD DNF matcher's outputs
     for a corpus of existing invariants, so semantics are a superset.
- **Definition of Done:** `(a > b && c != null) || !flag` and `total == pos*cxPad + cxAvulsas`
  evaluate correctly; every existing DNF invariant still yields the identical boolean.
- **Verify:** new `ComputedExpressionBooleanTest` green; corpus-equivalence test green.

### LIFT-EXPR-P2 — Retire CelInvariantEngine's matcher; delegate to ComputedExpression
- **Status:** DONE (2026-07-13, revised approach — see note) · **Risk:** Medium
- **Revision:** Deleting the matcher (as originally planned) turned out to be unsafe:
  `CelInvariantEngine` is not just a DNF comparison matcher — it also implements regex `.matches()`,
  `.uniqueBy()`, `.all()`/`.exists()` quantifiers, `conflicts()`/`overlapsProvider()`, `scope.exists()`,
  and `[*]` wildcard field paths, none of which `ComputedExpression` supports or was in scope to add.
  Deleting it would have silently broken those live invariant forms. Implemented instead:
  `evaluateExpression` tries `ComputedExpression.evaluateBoolean` first (via a `FieldPathScope`
  adapter reusing the existing reflection-based `readFieldValue` for field resolution); on
  `ExpressionException` (i.e., CEL-specific syntax `ComputedExpression` can't parse) it falls through
  to the untouched legacy matcher. Strict superset, zero behavior change for existing invariants —
  confirmed by the full `CelInvariantEngineTest` suite passing unchanged.
- **What:** Reimplement `CelInvariantEngine` to parse each invariant `expression` once (compile time)
  and evaluate via `ComputedExpression.evaluateBoolean` at runtime, deleting the hand-rolled top-level
  `||`/`&&`-over-fixed-atoms matcher.
- **Where:** `NPDevKernel/adapters/expression-cel/.../CelInvariantEngine.java`; wire the
  `:NPDevContract:dsl` dependency into the `expression-cel` adapter `build.gradle` if not already
  present (it consumes compiled types, so likely is).
- **Why:** Removes the parens/`!`/arithmetic ceiling at the enforcement point.
- **How:**
  1. Replace the matcher body with a compiled-expression cache keyed by invariant id.
  2. Map the invariant's field-scope (the record under validation) into the evaluator scope.
  3. Preserve the existing error/diagnostic shape (rule name, message) so callers don't change.
- **Definition of Done:** invariant enforcement uses `ComputedExpression`; the old matcher code is
  deleted; all existing invariant tests pass unchanged.
- **Verify:** `expression-cel` adapter tests green; a model with a parenthesized/arithmetic invariant
  rejects the violating record at runtime.

### LIFT-EXPR-P3 — Compile-time validation + authoring diagnostics
- **Status:** DONE (2026-07-13) · **Risk:** Low
- **Note:** scoped to the `ComputedExpression`-parseable subset (matches P2's superset boundary).
  `referencedFields(InvariantAst)` now tries `ComputedExpression.referencedFields` first (real field
  extraction for compound/paren/arithmetic expressions, not just single-comparison regex matches),
  falling back to the legacy single-shape regex extraction for CEL-specific syntax. A new
  `validateInvariantExpressionShape` rejects non-boolean-shaped expressions
  (`ComputedExpression.isBooleanShaped`) for the parseable subset; CEL-specific forms are left to
  runtime validation as before (no static grammar exists for them at the DSL layer).
- **What:** `SemanticValidator` parses every invariant `expression` at model-compile time and reports
  precise parse/type errors (unknown field, non-boolean result, bad arity) instead of a runtime
  surprise.
- **Where:** `NPDevContract/dsl/.../validation/SemanticValidator.java` (invariant validation path);
  reuse the P1 parser.
- **Why:** Authors get immediate, located feedback; matches how computed columns are already validated.
- **How:** add `validateInvariantExpression` invoked from the invariant loop; field references
  checked against the concept's compiled fields; surface as errors (blocking) vs warnings per policy.
- **Definition of Done:** a typo'd field or non-boolean invariant fails `validateModel` with a
  field-located message; valid arithmetic/paren expressions pass.
- **Verify:** `:NPDevContract:dsl:check` green incl. new negative cases.

### LIFT-EXPR-P4 — Remove the DNF workaround from generator + docs
- **Status:** DONE (2026-07-13) · **Risk:** Low
- **Note:** grepped `DNF`/`De Morgan`/"not supported" across docs+emitters. No generator emitter
  referenced DNF (invariants are server/kernel-enforced only, no client-side invariant hint emitted
  today). Fixed: stale comment in `PanelRuntime.applyQueryWhereFilter` that cited "CelInvariantEngine's
  documented DNF-only scope" (unrelated LIFT-QUERY code, comment only). Moved ARCH-6 from
  `OPEN_GAPS_AND_ROADMAP.md` (generated, not committed since md-zero-2026-08-11 PLAN.md Phase 6) §6 (boundaries) to §7 (fixed), with a changelog
  entry.
- **What:** Wherever generation or docs assumed "invariants must be DNF, no arithmetic," update to the
  new grammar; ensure any generator-emitted client-side invariant hint uses the same expression.
- **Where:** `NPDevGenerator/.../emitters/*` invariant emission; `docs/` references; the ARCH-6 note in
  `OPEN_GAPS_AND_ROADMAP.md §6` (generated, not committed since md-zero-2026-08-11 PLAN.md Phase 6).
- **Why:** Close the loop so authors aren't told to hand-DNF anymore.
- **How:** grep `DNF`/`De Morgan`/"not supported" in docs+emitters; update; move ARCH-6 from §6
  (boundaries) to §7 (fixed) in the prior roadmap.
- **Definition of Done:** no doc/emitter tells authors to write DNF; ARCH-6 marked lifted.
- **Verify:** generator gate green; docs grep clean.

### LIFT-EXPR-P5 — NPDevEditor invariant/expression authoring UI
- **Status:** DONE (2026-07-13) · **Risk:** Low-Med
- **Note:** *(doc-hygiene fix — this phase's code was already implemented and verified when LIFT-EXPR
  was completed; the status line here was simply never updated off its pre-work "BLOCKED on P3"
  placeholder until now.)* `computedExpressionTs.ts` is a TS port of the P1 grammar (client-side parse,
  no server round-trip needed); `InvariantsEditorSection.tsx` gained `describeExpression()` +
  inline validation rendering + a field-name datalist for autocomplete.
- **What:** The React authoring UI lets authors write full expressions (parens/`!`/arithmetic) with
  live parse validation + field autocomplete.
- **Where:** `NPDevEditor/ui-react/src/authoring/editors/invariants/computedExpressionTs.ts`,
  `InvariantsEditorSection.tsx`.
- **Why:** Full-stack decision — authors shouldn't hand-edit JSON for this.
- **How:** embed the expression input with client-side parse (port P1 grammar to TS **or** validate via
  a debounced `validateModel` round-trip), inline error rendering, concept-field autocomplete.
- **Definition of Done:** authoring a parenthesized arithmetic invariant in the editor shows live
  validation and round-trips to a working model.
- **Verify:** `computedExpressionTs.test.ts` green; `npmTest`/`npmBuild` green.

---

## 3. LIFT-UNIQUE — Compound (multi-field) unique invariants (lifts ARCH-compound-unique)

**Boundary today:** [`SemanticValidator.java:363`](../../../NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java#L363)
throws *"compound unique (multiple fields) not supported yet"*. Single-field `unique` works.

### LIFT-UNIQUE-P1 — Schema + DSL accept multi-field unique
- **Status:** DONE (2026-07-13) · **Risk:** Low
- **Note:** `model.schema.json`'s `fields: []` array already had no `maxItems` cap in any of the 4
  mirrors — the restriction was pure Java (`SemanticValidator.java:363`). Extended
  `CompiledInvariant` with an ordered `fields` list (new 5-arg ctor; old 4-arg ctor derives a
  1-element list, back-compat preserved), threaded through `ModelCompiler`,
  `CompiledModelCanonicalJsonReader`/`CompiledModelCanonicalJson` (round-trip), and replaced the
  throw with real validation (≥1 field, no duplicate field — unknown-field already covered by the
  existing generic invariant-fields-exist loop).
- **What:** Allow a `unique` invariant to declare `fields: [a, b, …]`; drop the rejection.
- **Where:** `model.schema.json` (×4 mirrors) unique-invariant shape; `JsonModelParser` /
  `ModelCompiler` invariant parsing; remove the throw at `SemanticValidator.java:363` and replace with
  a **positive** validation (all named fields exist, ≥1 field, no duplicate field).
- **Why:** Contract-first; everything downstream reads the compiled shape.
- **How:** extend the invariant AST/compiled type to carry an ordered field list (single-field becomes
  the 1-element case); mirror schema; add parse + validate tests.
- **Definition of Done:** a model with `unique (tenantId, email)` compiles; `unique ()` and unknown
  fields are rejected with clear messages.
- **Verify:** `:NPDevContract:dsl:check` green incl. new cases.

### LIFT-UNIQUE-P2 — Composite UNIQUE DDL
- **Status:** DONE (2026-07-13) · **Risk:** Low
- **Note:** emits a composite `ALTER TABLE ... ADD CONSTRAINT uq_<table>_<col>_<col> UNIQUE
  (tenant_id, col_a, col_b)` per compound-unique invariant, tenant-scoped the same way ordinary
  single-field uniques already are, via the existing `SqlIdentifierSupport.safeSqlIdentifier`
  hash-suffix naming and `addConstraintIfMissing` idempotent-DDL pattern.
- **What:** Emit a composite `UNIQUE (col_a, col_b)` constraint for each compound-unique invariant.
- **Where:** `NPDevGenerator/.../dbconfig/SchemaRealizationEmitter.java` (DDL emitter; FlywayEmitter is
  gone per BOND-B2); `SqlIdentifierSupport` for a stable, collision-safe constraint name (hash suffix
  for >63 chars, same pattern as junction tables).
- **Why:** DB-level enforcement is the source of truth for Postgres/H2 apps.
- **How:** in the per-concept DDL pass, group compound-unique invariants → one `ADD CONSTRAINT
  <name> UNIQUE (...)` per invariant; name via `SqlIdentifierSupport`.
- **Definition of Done:** generated DDL contains the composite constraint; H2 + Postgres both reject a
  duplicate `(tenantId, email)` pair.
- **Verify:** emitter test asserts the DDL; live H2 insert of a dup pair returns a constraint error.

### LIFT-UNIQUE-P3 — Runtime enforcement (InMemory + JDBC violation mapping)
- **Status:** DONE (2026-07-13) · **Risk:** Med
- **Note:** `mapDataIntegrityViolation` needed no change — it was already constraint-name-agnostic
  (detects "unique constraint"/"duplicate key" generically), so it already maps compound-DDL
  violations identically to single-field ones. New: `CelInvariantEngine.CompoundUniqueValueChecker`
  + `evaluateCompoundUniqueRule` (missing-field-in-group skips the check, same leniency as
  single-field); `RuntimeInvariantEngineFactory`/`GeneratedCrudRuntimeSupport` gained a 4th-arg
  `CompoundUniqueValueLookup` overload (default no-op, back-compat); `service-base.mustache` gained
  `existsUniqueCompoundInConceptStore` (AND-across-columns full scan via the same tenant-scoped
  `ConceptStore`, which is engine-agnostic — InMemory and JDBC/H2/Postgres share this one pre-check
  path, so "both stores" didn't need separate implementations). `PersistenceCapability<T,ID>` gained
  a default `existsUniqueCompound` (back-compat for adapters that don't implement it).
  `InMemoryPersistenceCapabilityAdapter`/`PostgresPersistenceCapabilityAdapter` (mentioned in this
  phase's original "Where") implement the *other*, unrelated `PersistenceCapabilityContract`
  interface with no live caller wiring `.unique()` into invariant enforcement — confirmed
  out-of-scope, not touched.
- **Verify:** new `CelInvariantEngineCompoundUniqueTest` (violation reported / different-group
  allowed / missing-field-skips-check) green; full `expression-cel` + `kernel` + `generator` +
  `NPDevContract:dsl` suites green with no regressions.

### LIFT-UNIQUE-P4 — Generator surface (client-side hint) + P5 — Editor
- **Status:** DONE (2026-07-13, no code needed — see note) · **Risk:** Low
- **Note:** both already satisfied by existing generic mechanisms, so nothing to build:
  - **P4:** `InvariantViolationException.toResponseBody()` already returns a per-violation
    `message`/`path`, which P3's `evaluateCompoundUniqueRule` already populates meaningfully
    (`"unique constraint violated for fields (orgId, email)"`, `path: "orgId, email"`). The
    generated UI's error-toast reads this generically for any 409 — no `business-ui-app.mustache`
    change needed, and the compiled `app.js` bundle is hash-guarded/never hand-edited per CLAUDE.md.
  - **P5:** `InvariantsEditorSection.tsx`'s "Fields" column was already a free-text comma-separated
    list (`joinTextList`/`parseTextList`) with no single-field restriction anywhere client-side —
    `tenantId, email` already round-trips today.

---

## 4. LIFT-ROWOPS — Declared-Panel create/delete-row (lifts ARCH-13)

**Boundary today:** the standalone declared `panel{}` (Tier 2) supports only per-row *update*.

**Correction (2026-07-13, research pass before P1):** this roadmap's premise that the Workbench has
a literal, portable `rowOps` compiled shape was wrong — a repo-wide grep found **no `rowOps` field
anywhere** (not in the schema, not in any `Compiled*` type, not in the Workbench template). The
Workbench's add/delete-row behavior is unconditional client JS gated only by a single `EDITABLE`
boolean (`workbench-page.html.mustache`), and its server-side persistence is a full-tree diff/
reconcile (`AggregateRuntime.commit`/`commitCollections`), not discrete create-row/delete-row calls.
So P1 *designed* a real `rowOps` shape from scratch (schema/DSL/compiled/validator) rather than
porting one, and P3 built discrete `createRow`/`deleteRow` runtime methods (a lighter mechanism
suited to a flat declared Panel, not the Workbench's heavier tree-commit machinery). P2's "shared
partial with the Workbench" idea was dropped for the same reason — there's no comparable Workbench
code to share, since the Workbench has no row-op *endpoints* to reuse, only inline unconditional JS.

### LIFT-ROWOPS-P1 — Schema + DSL: `rowOps` on a declared Panel
- **Status:** DONE (2026-07-13) · **Risk:** Low
- **What shipped:** `panelDataSource` (schema, ×4 mirrors) gained `rowOps: ["add","delete"]` (enum-
  constrained array) and `addFormFields: [string]`. `PanelDataSourceAst`/`CompiledPanelDataSource`
  carry both (back-compat constructors for existing 8-arg call sites); `CompiledPanelDataSource`
  exposes `supportsAdd()`/`supportsDelete()`. `SemanticValidator.validatePanelRowOps` rejects
  unsupported/duplicate rowOps values, rowOps on a non-concept (query/procedure) dataSource, and
  unknown `addFormFields`.
- **Verify:** new `PanelRowOpsValidationTest` (6 cases, incl. the schema-level enum rejection) green;
  full `:NPDevContract:dsl:test` green.

### LIFT-ROWOPS-P3 — Runtime: create/delete against CRUD
- **Status:** DONE (2026-07-13, built before P2 — the UI needed a real endpoint to call) · **Risk:** Low
- **What shipped:** `PanelRuntime.createRow`/`deleteRow` (new public methods) resolve the panel +
  named dataSource, reject if the op isn't in `rowOps`, and write through `ConceptGateway` directly
  (same tenant-enforcement path as every other panel/aggregate write — `DefaultConceptGateway`
  falls back to the caller's own context tenant when the request's tenantId is null, so cross-tenant
  writes are blocked without extra plumbing). A child (nested) dataSource's `createRow` requires
  `parentId` in the input and injects it into `childField` automatically. New REST endpoints:
  `POST/DELETE /api/runtime/metadata/ui/panels/{panelName}/dataSources/{dataSourceName}/rows[/{id}]`
  in `RuntimeUiMetadataController`.
- **Known pre-existing limitation inherited, not introduced:** like the existing `conceptMutation`
  panel-action path, these new methods write straight through `ConceptGateway` without invariant
  (required/unique/expression) re-validation — only the generated typed CRUD service
  (`service-base.mustache`'s `enforceWithKernel`) does that. Out of scope for this phase.
- **Verify:** new `PanelRuntimeRowOpsTest` (6 cases incl. parent-FK injection and cross-tenant-delete-
  blocked) written against real `DefaultConceptGateway` + `InMemoryConceptStore` — **not executed**:
  `NPDevRuntimeHost` has no standalone Gradle build in this repo (`build.gradle.template` only; it's
  a Spring Boot template compiled solely as part of a generated FinalApp). Code-reviewed and pattern-
  matched line-for-line against the already-passing `PanelRuntimeTest.java` fixtures in the same
  package. Live verification needs a full `Build-NpdevApp.ps1` cycle, not done this pass.

### LIFT-ROWOPS-P2 — Generator: emit add-form + delete control
- **Status:** DONE (2026-07-13) · **Risk:** Low-Med
- **What shipped:** `PanelRuntime.dataSourceSummary` now includes `rowOps`/`addFormFields`/
  `childField`, so the client already has everything from the existing `loadPanel` response — no new
  metadata endpoint needed. `business-ui-app.mustache` gained `createDeclaredPanelRow`/
  `deleteDeclaredPanelRow` (call the new P3 endpoints, then reload the panel) and
  `renderDeclaredPanelAddRowForm` (header form using `addFormFields`, falling back to declared table
  columns). Delete buttons + the add-row form are wired into **both** render paths: the declared-
  fieldBindings table (`renderDeclaredColumnsTable`) and the generic JSON-table fallback
  (`renderDeclaredPanelValueGeneric`, extended with optional panel/dataSource context) — a dataSource
  with `rowOps` gets working add/delete UI whether or not it has declared `fieldBindings`.
- **Verify:** full `:generator:test` green (no golden-file regressions); `node --check` on the
  mustache file with `{{...}}` tags stripped (syntax-clean); new
  `.declared-panel-add-row` CSS rule added to `business-ui-style.mustache`.

### LIFT-ROWOPS-P4 — NPDevEditor panel designer toggle
- **Status:** DONE (2026-07-13, larger scope than assumed — see note) · **Risk:** Low
- **Note:** the assumption that a `dataSources[]` editor already existed to bolt rowOps checkboxes
  onto was wrong — `PanelsEditorSection.tsx` only ever read/wrote a single legacy `panel.dataSource`
  field via a "Query data source" dropdown; the plural `dataSources[]` array was declared in
  `AuthoringPanel`'s TS type but had **no UI at all**. Built a real editor instead of a toggle: new
  `PanelDataSourcesEditor.tsx` (table of dataSources — name/concept/parentDataSource/parentField/
  childField/rowOps checkboxes/addFormFields free-text list — add/remove rows), wired into
  `PanelsEditorSection.tsx`. Extended `AuthoringPanelDataSource` with `parentDataSource`/
  `parentField`/`childField`/`rowOps`/`addFormFields` (previously missing entirely). Registered the
  new component in `ui-boundary.json` (a build-time governance plugin that fails the Vite build on
  any unlisted `.tsx` surface — discovered by running the actual build, not just `tsc`).
- **Verify:** `npx vite build` succeeds (this is the gate that actually caught the ui-boundary.json
  omission — `tsc --noEmit` alone would have passed silently); `npx vitest run` 27/27 green.

---

## 5. LIFT-QUERY — Query-backed capability input (lifts ARCH-7)

**Decision locked:** keep capabilities pure. Fix `listConcepts`/`runQuery` to honor `where`
server-side, then feed the filtered rows into the capability as ordinary input. The sandbox stays.

### LIFT-QUERY-P1 — Honor `where` in listConcepts / runQuery
- **Status:** DONE (2026-07-13) · **Risk:** Low-Med
- **Note:** `listConcepts` has no query reference at all in its DSL step shape (bare `conceptName`
  only — no `where` to apply there by construction; scoped this phase to `runQuery`, which does
  reference a named query). New shared kernel class `ConceptQueryFilterSupport`
  (`applyWhere`/`applyOrderBy`/`applyLimit`, operating on `ConceptRecord`) replaces the previously
  duplicated logic in `PanelRuntime` (deleted ~130 lines of its own copy) — one implementation used
  by both `DefaultProcedureExecutor.runQuery` and every declared-Panel dataSource load.
  `DefaultProcedureExecutor` gained an optional `Map<String,CompiledQuery>` constructor param
  (default empty, back-compat); `ProcedureRunner` wires `compiledModel.getQueries()` into it. Soft
  cap: `query.limit()` if declared, else 1000 rows.
- **What:** Procedure steps `listConcepts` and `runQuery` apply the query's declared `where` instead of
  passing a null filter and returning all rows.
- **Where:**
  [`DefaultProcedureExecutor.java`](../../../NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/DefaultProcedureExecutor.java)
  `listConcepts` (`:147`) / `runQuery` (`:148`); reuse the where-predicate shape already used by
  `PanelRuntime`'s `applyQueryWhereFilter` (extract it to a shared helper so both share one predicate
  implementation).
- **Why:** Without this, a capability can't get *filtered* live data — the root of ARCH-7.
- **How:**
  1. Factor `PanelRuntime.applyQueryWhereFilter` into a reusable predicate (kernel-side or a shared
     util) covering the common `field ==`/`!=`/comparison shapes.
  2. Apply it in both procedure steps; keep `orderBy` honored too (parity with ARCH-10b).
  3. Guard result size (soft cap / warning) so a capability isn't handed an unbounded list.
- **Definition of Done:** a `runQuery` with `where cliente == $input.cliente` returns only matching
  rows inside a procedure.
- **Verify:** `DefaultProcedureExecutor` tests + live procedure invoke returns filtered rows.

### LIFT-QUERY-P2 — Make results consumable as capability input
- **Status:** DONE (2026-07-13, research-first — see note) · **Risk:** Med
- **Note:** researched `RegistryCapabilityDispatcher`/`ArtifactLocalJavaSourceCapabilityHandler`
  before writing code: capability dispatch matches by name+arity only and invokes reflectively with
  no type coercion, and Java generic erasure means a `List<ConceptRecord>` already passes through
  a `List<...>`-typed parameter with zero new dispatcher code required. So P2 needed no production
  code — the "second half of ARCH-7" was already mechanically true, just unproven. What shipped:
  the missing end-to-end proof (new `DefaultProcedureExecutorQueryToCapabilityTest`, a
  `runQuery(where)` step whose filtered output flows into a `callCapability` step, asserted against
  a capability that structurally cannot access the DB — it closes over nothing but its arg, so
  "no data handle" is a fact about the test, not a convention).
- **Deferred, not needed for DoD:** `mapValue` is currently a pure rename/passthrough
  (`resolve(state, valueRef) -> putOutput`), not a per-row shaping/projection step. Real row-shaping
  (`ConceptRecord` → capability DTO) would be new work; not built since the DoD only requires the
  capability receive the filtered rows and process them, not that they be reshaped first.
- **What:** The `List<ConceptRecord>`/`List<Map>` output of a query step is passable into a
  `plugin:java-source` capability as a typed input argument.
- **Where:** `RegistryCapabilityDispatcher` / `CapabilityDispatcher` (arg binding — multi-arg by name +
  arity already works); a `mapValue` shaping step if the capability expects a specific row shape;
  `CapabilityExecutionPolicy` (confirm list inputs are permitted, sandbox unchanged).
- **Why:** The second half of ARCH-7 — results were "not importable" by a sandboxed capability.
- **How:** ensure the dispatcher marshals a list argument into the capability signature; document the
  expected input type; add a shaping example (`mapValue` from rows → capability DTO list).
- **Definition of Done:** a capability receives exactly the filtered rows as input and processes them;
  it performs no DB access itself (sandbox intact).
- **Verify:** integration test: runQuery(where) → capabilityCall(rows) → asserted output; capability
  has no data handle.

### LIFT-QUERY-P3 — Validation + P4 — docs/authoring
- **Status:** DONE (2026-07-13) · **Risk:** Low
- **Note (P3):** `callCapability` procedure steps had **no validation at all** before this (not
  existence of the capability, not the operation, not arity) — built from scratch, not extended.
  New `validateProcedureCapabilityCall` checks capability exists, operation exists, and arg count
  matches the operation's declared arity. Arity itself required a fallback: the JSON Schema's
  `capabilityOperation.input` doesn't actually accept a plain string array (only an object/schemaRef
  shape, or the bare-string operations shorthand with no declared input at all) — so arity derives
  from `getInput().size()` (legacy array form) OR `inputSchema.getProperties().size()` (the
  schema-valid authored form), and is skipped (not flagged) when neither is declared.
- **Note (P4):** found the editor's procedure step UI had **no capability/operation/args fields at
  all**, and `callCapability` wasn't even in its step-type dropdown — a bigger gap than "add a
  template." Added the fields to `ProcedureStepEditor.tsx`, added `callCapability` to
  `ProceduresEditorSection.tsx`'s `STEP_TYPES`, and added the literal "query → capability" preset
  button that inserts a wired `runQuery` + `callCapability` step pair.
- **Verify:** new `ProcedureCapabilityCallValidationTest` (4 cases) green, full `:NPDevContract:dsl:test`
  green; editor `vite build` (which enforces the ui-boundary + catches what `tsc` alone misses) +
  `vitest run` (27/27) green.

---

## 6. LIFT-UPLOAD — File-upload primitive (lifts ARCH-upload)

**Decision locked:** pluggable file-store adapter (`file-store-inproc` filesystem for dev,
`file-store-objectstore` S3-compatible for prod), mirroring NPDev's `*-inproc`/`*-postgres` adapter
pattern. Bytes never go in the primary DB; the record stores a **file handle** (store id + key +
content-type + size).

### LIFT-UPLOAD-P1 — FileStore port + two adapters
- **Status:** DONE for the `*-inproc` half (2026-07-13); **object-store adapter deferred** · **Risk:** Med
- **Note:** shipped `FileStoreContract`/`FileHandle` (kernel ports) and `file-store-inproc`
  (`FileSystemFileStoreAdapter`) — tenant-prefixed keys (`<tenantId>/<uuid>`), path-traversal-safe
  even against a malicious tenantId, streaming put/get (no full in-memory buffering). The
  `file-store-objectstore` (S3-compatible) adapter was deliberately not built this pass: it needs
  an external SDK dependency and infrastructure (localstack/minio) this session can't provision or
  verify, so it would have been unverified code. `file-store-inproc` is the dev/InMemory-app default
  the roadmap itself names, so nothing downstream (P2–P6) is blocked by the deferral.
- **Verify:** new `FileSystemFileStoreAdapterTest` (6 cases: round-trip, idempotent delete,
  tenant-prefixed keys, path-traversal rejection for both a malicious tenantId and a malicious
  handle, 5MB streamed content) — all green.

### LIFT-UPLOAD-P2 — Schema + DSL: `file` field type
- **Status:** DONE (2026-07-13) · **Risk:** Low-Med
- **Note:** `model.schema.json` (×4 mirrors) gained `"file"` in the field-type enum, a `file`
  metadata block (`contentTypes`/`maxSizeBytes`/`multiple`), and an `allOf` conditional forbidding
  `unique`/`reference`/`ref` on a file field. New `FileMetadataAst`/`CompiledFileMetadata` thread
  through `FieldAst`/`CompiledField` via back-compat constructor overloads (existing call sites
  unaffected). `SqlTypeSupport` maps `file` → `JSONB` (H2 renders it `JSON`) — same handle-not-blob
  treatment as `object`/`array`. Also had to add `"file"` to `SemanticValidator.KNOWN_TYPES` (a
  second, separate type-allowlist from the JSON Schema enum — both needed updating).
- **Verify:** new `FileFieldValidationTest` (4 cases) + `SchemaRealizationEmitterFileFieldTest`
  (asserts the JSON handle column) green; full `:NPDevContract:dsl:test` and `:generator:test` green.

### LIFT-UPLOAD-P3 — Multipart upload/download endpoints
- **Status:** DONE (2026-07-13) · **Risk:** Med-High
- **Note:** new `com.finalexec.api.FileUploadController` — `POST /api/files/{concept}/{field}`
  validates the field's declared `contentTypes`/`maxSizeBytes` (415/413 on violation), stores via
  `FileStoreContract`, returns the `FileHandle` JSON (the caller persists it on the record through
  ordinary CRUD, as designed — this controller never touches `ConceptGateway`).
  `GET`/`DELETE /api/files` take the handle as query params and enforce tenant isolation by
  comparing the key's `<tenantId>/` prefix against the caller's own context tenant, worded like a
  404 rather than 403 (never confirms a key exists in another tenant). `application.yml` gained
  `spring.servlet.multipart` config (Spring's 1MB/10MB defaults would otherwise silently reject
  anything bigger — the field's own `maxSizeBytes` is the intended limit, not Spring's).
- **Not built (documented gap):** orphan-cleanup on record delete/replace needs hooking every
  generated CRUD service's delete path — a larger cross-cutting change out of scope this pass.
- **Verify:** genuinely live — `TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest` generates
  a real FinalApp (copying this controller + config in), compiles it, and boots it over real HTTP.
  It initially **failed** (`package com.npdev.adapters.filestore.inproc does not exist`) because
  the test's own hardcoded adapter-jar build list didn't include the new `file-store-inproc`
  module — fixed by adding it there. Confirms compile + boot; does not itself exercise the upload
  endpoint's request/response body.

### LIFT-UPLOAD-P4 — Generator: file field UI
- **Status:** DONE (2026-07-13) · **Risk:** Med
- **Note:** `createInput`'s field-type dispatch (a widget-catalog switch, `field.widget` then
  `field.type`) gained a `file` case: `createFileInput` follows the existing `createLookupInput`
  hidden-input-plus-display convention — a hidden input holds the uploaded `FileHandle` JSON, a
  status label shows the filename, and a download link appears once a handle exists. Picking a file
  uploads immediately (multipart, outside the record's own JSON save) and only updates the hidden
  input on success, so the record's create/update POST still carries just the handle, never bytes.
  `renderFieldValue` (read-only list/detail display) also gained a `file` case (download link).
  `BusinessUiEmitter.manifestFields` now emits `concept`/`fileContentTypes`/`fileMaxSizeBytes` so
  the client has what it needs without a second metadata endpoint.
- **Verify:** same packaged-app boot test as P3 (real compile + boot of the generated client code);
  `node --check` on the mustache file with `{{...}}` stripped (syntax-clean); full `:generator:test`
  (139 tests) green.

### LIFT-UPLOAD-P5 — NPDevEditor: file field authoring
- **Status:** DONE (2026-07-13) · **Risk:** Low
- **Note:** `file` added to `FieldDetailsEditor.tsx`'s type dropdown, with a metadata sub-form
  (content types, max size, multiple) shown only for that type; the Unique/Anchor checkboxes are
  disabled (with an explanatory title) when type is `file`, mirroring the server-side schema
  constraint client-side instead of only discovering it after a failed save.
- **Verify:** `vite build` (enforces the ui-boundary lock) + `vitest run` (27/27) green.

### LIFT-UPLOAD-P6 — Wire `Add Doctos`/NFe attach (AW-Deferred) + docs
- **Status:** DEFERRED (2026-07-13) · **Risk:** Low
- **Why deferred:** the WMS/Expedicao AutoPanel model is app-side (`AppGen/apps`, layer 2 per
  CLAUDE.md — not a git repo, not in this session's reach) and proving attach+view needs a live
  browser check (ScrapForAI) against a running WMS instance, neither of which this session can
  do. The primitive itself (P1–P5) is real and verified; wiring it into the specific WMS deferred
  use case is a follow-up, not a blocker for calling ARCH-upload lifted.

---

## 7. LIFT-LOOP — Flow iteration (lifts ARCH-loop for Flows)

**Boundary today:** Procedures already loop (`forEach`/`loop` → `FOR_EACH` with `maxLoopIterations`).
**Flows** have `branch`/`if` but no iteration. Flows are **durable/event-sourced and resumable**, so
the hard part is loop state that survives resume. **Design default (not asked — sensible default):** a
**bounded `forEach`** over a collection (not a general `while`), with a per-iteration checkpoint and
the same `maxLoopIterations` safety cap procedures use.

### LIFT-LOOP-P1 — Schema + DSL: `forEach` flow step
- **Status:** DONE (2026-07-13) · **Risk:** Med
- **Note:** `model.schema.json` (×4 mirrors) gained `"forEach"`/`"loop"` (alias) to the flow-step
  `type` enum plus `collection`/`itemKey`/`steps` (nested body, reusing the same `flowStep` schema
  recursively)/`maxLoopIterations` (`minimum: 1`) properties, gated by an `allOf` conditional.
  `StepAst`/`CompiledFlowStep` gained the four new fields via the established back-compat-constructor
  pattern (new canonical ctor with the extra trailing params; the old canonical ctor becomes a
  delegating overload) — zero changes needed at existing call sites. `JsonModelParser.normalizeStepType`
  maps both `"forEach"` and `"loop"` to the canonical `"forEach"`; `ModelCompiler.compileFlowSteps`
  recurses into `loopSteps`. **Bug found and fixed during this phase:** `ModelResolver.cloneStep()`
  (used during flow specialization/hook-merge resolution, which runs unconditionally for every flow)
  was still calling the *old* 25-arg `StepAst` constructor, silently dropping the four new loop fields
  on every resolved flow — caught by `FlowForEachValidationTest.forEachCompilesAndValidates()`
  (`collectionRef` came back `null`), fixed by threading the new fields through `cloneStep()`'s
  `new StepAst(...)` call.
- **What:** Add a `forEach` flow step (collection ref, item var, ordered body steps).
- **Where:** flow-step `type` enum in `model.schema.json` (×4);
  [`CompiledFlowStep.java`](../../../NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledFlowStep.java);
  `JsonModelParser`/`ModelCompiler` flow parsing; `SemanticValidator`.
- **Why:** Contract first; mirror the procedure `forEach` shape for author familiarity.
- **How:** add the enum + a nested-steps body to the compiled flow step; validate collection + item
  scoping.
- **Definition of Done:** a flow with a `forEach` compiles and validates; malformed loops are rejected.
- **Verify:** new `FlowForEachValidationTest` (6 cases: compiles, `loop` alias, empty-body-rejected,
  missing-collection-rejected, nested-await-rejected, zero-iterations-rejected) green; full
  `:NPDevContract:dsl:test` green.

### LIFT-LOOP-P2 — FlowEngine durable execution
- **Status:** DONE (2026-07-13) · **Risk:** High
- **Note:** `KernelRunner.executeForEachStep` treats the whole loop as **one atomic top-level step
  position** (like `MAP`/`CAPABILITY_CALL`), deliberately *not* extending the flat
  step-index/`subList()` resume mechanism `BRANCH`'s nested then/else steps share with the top-level
  step list (a pre-existing fragility identified but not touched this pass). Durability is at
  iteration granularity instead: after each iteration's nested steps succeed, a step-scoped progress
  marker (`__forEachProgress.<stepName>`) is folded into `state` and checkpointed via the existing
  `StepProgressRecorder` callback **without** advancing the outer step index, so a crash mid-loop
  resumes by re-entering the same `forEach` step and skipping iterations already recorded done. Nested
  loop-body steps execute via a recursive `executeSteps` call using a no-op progress recorder (so they
  don't independently checkpoint) with their traces folded into the outer trace for observability.
  `maxLoopIterations` (default `ProcedureExecutionLimits.DEFAULT_MAX_LOOP_ITERATIONS` = 10,000 when
  unset) is enforced upfront against the resolved collection's size. **Small but necessary
  `resumeExecution` change:** the existing guard only allowed resuming a `WAITING_EVENT` instance;
  broadened to also accept `RUNNING` — a `forEach` crash never reaches `WAITING_EVENT` (there's no
  await), so without this, a durably-checkpointed mid-loop crash would have had no resume path at all.
  Nested `await` inside a loop body is rejected at validation time (P1) rather than supported, since
  durable resume of an in-flight await *inside* an iteration is a materially harder problem deferred
  to a later slice.
- **What:** Execute the loop body once per item with **resume-safe** iteration state — a mid-loop
  crash/await resumes at the right iteration+step, not from the top.
- **Where:** [`KernelRunner.java`](../../../NPDevKernel/kernel/src/main/java/com/npdev/kernel/KernelRunner.java)
  (`executeForEachStep`, `resumeExecution`); `FlowStepDefinition`.
- **Why:** This is the whole reason flows didn't have loops — durability is non-trivial.
- **Definition of Done:** a `forEach` over N items runs the body N times; killing the app mid-loop and
  resuming completes exactly the remaining iterations with no double-effects.
- **Verify:** new `KernelRunnerForEachDurabilityTest` — a genuine crash simulation (not just an
  exception: KernelRunner already treats any thrown `RuntimeException` from a step, including a store
  write failure, as a terminal flow failure it marks `FAILED` and rethrows, so a thrown exception
  models a *write failure*, not a process crash). The test runs the flow on a background thread against
  a `FlowInstanceStore` stub that, after a real durable write for iteration 2 of 4, freezes that thread
  forever on an uncounted `CountDownLatch` — so neither `KernelRunner`'s own failure-handling
  catch/finally nor anything else in the "process" ever runs again, faithfully modelling a hard kill
  right after a checkpoint commits. A **brand-new** `KernelRunner` sharing only the durable store (no
  reference to the frozen thread, the first runner, or its local `state` map) then calls
  `resumeExecution` and is asserted to process items 3 and 4 exactly once each, with items 1–2 never
  reprocessed (`["o1","o2","o3","o4"]` each exactly once across both runners) and the flow reaching
  `COMPLETED`. Full `:kernel:test` green (no regressions from the `resumeExecution` guard change).

### LIFT-LOOP-P3 — Generator/runtime wiring
- **Status:** DONE (2026-07-13) · **Risk:** Med
- **Note:** `CompiledModelFlowDefinitionProvider.toFlowSteps` (the sole place compiled DSL flow steps
  get projected to kernel `FlowStepDefinition`s for a generated app's runtime) gained a `"foreach"`
  case, recursing into the loop body via the same `toFlowSteps` call used for `branch`'s then/else.
  Confirmed to be the *only* such projection switch in the kernel tree (grepped for sibling
  `case "capability"`/`case "branch"` switches — none found elsewhere), so no other wiring point exists
  to miss.
- **What:** flow-compiled adapter + any generated flow dispatch handle the new step.
- **Where:** [`CompiledModelFlowDefinitionProvider.java`](../../../NPDevKernel/adapters/flow-compiled/src/main/java/com/npdev/adapters/flowcompiled/CompiledModelFlowDefinitionProvider.java).
- **Why:** End-to-end execution.
- **Definition of Done:** a generated app runs a flow `forEach` end-to-end.
- **Verify:** new `CompiledModelFlowDefinitionProviderTest.providerMapsCompiledForEachStepAndExecutesEachLoopIterationOnce`
  — compiles a real model with a `forEach` flow step, asserts the projected `FlowStepDefinition` shape,
  then actually executes it through a real `KernelRunner` and asserts each collection item's
  `emitEvent` fired exactly once, in order. Full `:adapters:flow-compiled:test` (9/9) and `:kernel:test`
  green. **Genuinely live:** `TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest` (the one real
  generate→compile→boot-over-HTTP proof path for RuntimeHost/generator changes, per this session's
  established pattern for LIFT-UPLOAD-P3/P4) had a `forEach` flow (`SumItem12NamesForEachFlow`, a
  `startEndpoint` flow with a `forEach` over `input.names` emitting one event per item) added to its
  fixture model; the packaged FinalApp compiles and boots over real HTTP with it present. Matches
  LIFT-UPLOAD-P3's own documented bar exactly (confirms compile + boot; does not itself issue an HTTP
  request against the new flow's endpoint).

### LIFT-LOOP-P4 — Validation hardening
- **Status:** DONE (2026-07-13) · **Risk:** Med
- **Note:** `SemanticValidator.validateForEachStep` hardened beyond P1's minimal shape: (1) itemKey
  can no longer shadow a reserved flow state key (`input`, `last`, `executionId`, `correlationId`,
  `causationId`, `tenantId`, `actorId`, `_npdevEntityName`) — `KernelRunner` writes
  `state.put(itemKey, item)` every iteration, so reusing one of these silently clobbers
  framework-critical state (e.g. `itemKey="input"` corrupts every later `input.*` reference in that
  same iteration); (2) itemKey can no longer equal its own collection ref's root segment (generalizing
  P1's narrow literal-`"item"`-only check); (3) a nested `forEach` reusing an *enclosing* loop's
  itemKey is now rejected (`checkNestedItemKeyShadowing`, recursing through nested `then`/`else`/
  `steps`); (4) `maxLoopIterations`, when present, is capped at 1,000,000 (schema only enforces
  `minimum: 1`, no ceiling) to catch clearly-nonsensical authored values.
- **What:** validate collection types, item-var shadowing, iteration cap presence.
- **Where:** `SemanticValidator.java` (`validateForEachStep`, `checkNestedItemKeyShadowing`).
- **Why:** Safe authoring — most of these are exactly the kind of "wrong item"/corrupted-state bugs
  that are silent at authoring time and only surface as confusing runtime behavior.
- **Definition of Done:** bad loops fail validation.
- **Verify:** `FlowForEachValidationTest` extended to 10 cases (4 new: excessive-maxLoopIterations,
  reserved-state-shadowing, self-shadowing-collection-root, nested-forEach-itemKey-shadowing) green;
  full `:NPDevContract:dsl:test` green.

### LIFT-LOOP-P5 — NPDevEditor flow-builder loop node
- **Status:** DONE (2026-07-13) · **Risk:** Med
- **Note:** `FlowStepsTable.tsx` was a flat, non-recursive table before this phase (confirmed via
  research: *no* nested step-list editor existed anywhere in `ui-react` — `branch`'s `then` field was
  declared in the authoring types but never rendered either, so this phase built the first nested
  step-list UI in the editor, not just a `forEach`-specific add-on). Refactored into a recursive
  `FlowStepList` component: a `forEach`-typed row (matched case-insensitively, same `"forEach"`/`"loop"`
  aliasing as the backend) now expands an inline sub-panel with Collection ref / Item key / Max loop
  iterations fields plus a nested `FlowStepList` for the loop body (add/remove loop steps, arbitrarily
  re-nestable since `forEach` can itself appear inside a loop body). A `<datalist>` of known flow-step
  types (including `forEach`) was added to the type input for discoverability without removing its
  existing free-text extensibility. `AuthoringFlowStep` (the authoring TS type, which serializes
  directly to the model JSON with no separate conversion layer) gained `collection`/`itemKey`/`steps`/
  `maxLoopIterations` fields matching the backend schema's JSON property names exactly. No new files
  were added (extended `FlowStepsTable.tsx` and `modelDocumentTypes.ts` in place), so no
  `ui-boundary.json` registration was needed.
- **What:** The flow designer offers a `forEach` node with a collection binding and a nested body.
- **Where:** `NPDevEditor/ui-react/src/authoring/editors/flows/FlowStepsTable.tsx`;
  `NPDevEditor/ui-react/src/authoring/editors/modelDocumentTypes.ts`. (Plain paths, not links:
  `NPDevEditor` was parked out of this repository on 2026-08-17 — see `BREAKING.md` — so these
  resolve in the parked tree, not here. This doc is archived historical narrative; the paths are
  kept for the record rather than for navigation.)
- **Why:** Full-stack reach.
- **Definition of Done:** authoring a loop in the flow builder round-trips to a working, resumable
  flow.
- **Verify:** new Vitest case in `editorRoundTripAndUx.test.ts` round-trips a `forEach` flow step
  (collection/itemKey/nested loop body/maxLoopIterations) through the same
  `createSynchronizedJsonSnapshot`/`applySynchronizedJsonDraft` draft-apply path the editor's JSON view
  uses, asserting zero validation issues and lossless field preservation. `npmTest` (28/28) and
  `npmBuild` (`vite build`, which enforces the `ui-boundary.json` lock) both green — run directly via
  `./gradlew npmTest npmBuild` after `run-frontend-gate.ps1`'s own report-serialization step hit a
  pre-existing, unrelated `ConvertTo-Json` crash (documented as a known gate-script bug, not a
  regression from this phase).

### LIFT-LOOP-P6 — sequential `await` inside a loop body (B15(A))
- **Status:** DONE (2026-08-03, Move 16 Phase B) · **Risk:** High
- **Note:** P1 rejected any `AWAIT_EVENT` nested in a `forEach` loop body outright (this row's own
  original mitigation: "reject nested await in P1; support deliberately in a later slice with its own
  resume test"). Move 9 A5 later investigated closing it and found a structural wall — but for the
  GENERAL (parallel, N-iterations-waiting-at-once) case: `FlowInstance` hard-codes one `correlationId`/
  `currentStepIndex`/`waitingForEventName` per row. That analysis never asked the narrower question
  this phase answered: what does the SEQUENTIAL-only case (at most one outstanding await at a time)
  actually need? Nothing new in the schema — `FlowInstance.state` is already a flexible JSON blob.
  Three mechanisms, entirely inside the existing single-slot shape: (1) a deterministic, non-blank
  per-iteration correlation id (`executionId + "::forEach:" + stepName + ":" + iterationIndex`,
  `FlowStateCodec.deriveForEachIterationCorrelationId`) overwrites `state.correlationId` before each
  iteration's body runs, discriminating iteration N's own reply from any other iteration's or flow
  instance's event of the same name — required, never silently derived-to-blank
  (`KernelRunner.matchesCorrelation` fails closed on a blank correlation — F9,
  FIRST_IMPRESSION_SPEC.md I7 — so a silently-blank derivation strands the iteration waiting
  forever instead of resolving against the wrong one); (2) a
  satisfaction marker plus a mid-iteration checkpoint pinned to the forEach step's own outer trace
  index close the crash window between "event consumed" and "outer iteration progress advanced" —
  without both, a crash there loses the marker and re-entry finds the one satisfying event already
  marked processed by the (separate) idempotency store, parking the flow WAITING forever; (3) two real
  bugs found and fixed by the restart-proof tests themselves, both previously unreachable since the
  validator rejected the whole shape: `ForEachStep`'s nested-failure handling used to unconditionally
  re-wrap ANY nested failure — including a legitimate `WAITING_EVENT` — as a generic terminal `FAILED`;
  and its `finally` block used to unconditionally wipe progress/correlation/marker state even when
  returning early for a genuine wait.
- **What:** a `forEach` loop body with exactly one reachable `await` step resumes correctly across a
  real process restart, with events arriving out of order.
- **Where:** [`FlowStateCodec.java`](../../../NPDevKernel/kernel/src/main/java/com/npdev/kernel/FlowStateCodec.java),
  [`ForEachStep.java`](../../../NPDevKernel/kernel/src/main/java/com/npdev/kernel/ForEachStep.java),
  [`AwaitEventStep.java`](../../../NPDevKernel/kernel/src/main/java/com/npdev/kernel/AwaitEventStep.java),
  [`ResumeCoordinator.java`](../../../NPDevKernel/kernel/src/main/java/com/npdev/kernel/ResumeCoordinator.java),
  [`FlowValidation.java`](../../../NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/FlowValidation.java)
  (`:470-478`, `countAwaitSteps` replacing `containsAwaitStep` — up to one reachable await is now
  allowed, two or more still rejected since that shape was never exercised by the restart-proof
  tests).
- **Why:** the single most-requested "why can't my loop wait for approval on each item" shape, without
  the invasive per-iteration-collection redesign the parallel case would need.
- **Definition of Done:** the required restart proof (this phase's own hard-stop rule, unchanged from
  Move 9: "a loop that resumes the wrong iteration is far worse than a validation error") passes for
  real, not just compiles.
- **Verify:** `KernelRunnerAwaitInLoopRestartProofTest`
  (`NPDevKernel/kernel/src/test/java/com/npdev/kernel/KernelRunnerAwaitInLoopRestartProofTest.java`),
  modeled on `KernelRunnerForEachDurabilityTest`'s real-thread-freeze technique. Two scenarios: a
  forEach over 3 items with brand-new `KernelRunner` instances (no in-memory state carried over)
  resuming across each parked iteration, with the LAST item's own reply event delivered and already
  sitting in the store BEFORE the currently-waiting (earlier) iteration's own reply arrives — each
  iteration resumes exactly once, using its own event, in order; and a thread frozen at the exact
  instant the mid-iteration checkpoint persists the satisfaction marker (event already durably
  consumed, idempotency store already marking it processed, outer progress not yet advanced) — a
  fresh runner resuming from that frozen point completes the iteration via the marker rather than
  re-querying and getting stuck. Full `NPDevKernel` test suite (kernel + every adapter, 133 tasks)
  green. `dsl-conformance-max` extended with a real corpus example (`ProcessWidgetOrderLineApprovals`,
  a `forEach` over `$input.skus` awaiting `WidgetOrderLineApproved` per item) and
  `FlowForEachValidationTest` extended (`singleAwaitInsideLoopBodyIsAllowed`,
  `twoAwaitsInsideLoopBodyIsRejected`, replacing the old blanket-rejection test).
- **Deliberately not done:** parallel awaits (N iterations genuinely waiting at once) — see this
  document's own risk register and `docs/ACCEPTED_BOUNDARIES.md` B15(B). Move 16 Phase C produced a
  costed design decision for that, not an implementation — see LIFT-LOOP-P7 below.

### LIFT-LOOP-P7 — Parallel awaits inside a loop (B15(B))
- **Status:** DONE (2026-08-03, S6 Phase B), scoped to a single-step loop body · **Risk:** was High;
  the storage decision below (state-blob, not a new table) removed the schema/adapter half of that
  risk entirely, leaving only the engine-coordination half, which the restart-proof test (B4)
  verifies directly. Costed but not implemented as of Move 16 Phase C (2026-08-03 morning); designed
  AND implemented same-day in S6 Phase B once picked back up.

**B0 — the storage decision, made and recorded.** Neither Option A nor Option B as originally
costed: re-reading the actual engine code (not just the design doc) before choosing found that a
BRAND NEW durable storage surface buys nothing at the discovery layer. All N iterations of one
`parallelAwait` forEach step await the SAME declared event name (only correlation differs per
iteration, exactly like B15(A)) — so `FlowInstanceStore.findWaitingByEvent` already discovers a
candidate instance via its EXISTING indexed column with zero schema change. What Option B's new
table would have bought — per-slot correlation discrimination — `state` already provides for free:
it round-trips through persistence in full on every checkpoint (the same property B15(A)'s single
`_npdev.await` slot already relies on). So B15(B) ships as **Option B's architecture** (one parent
`FlowInstance` row, N provisional wait slots, never N child flows) **implemented via the existing
state-blob** rather than a new `flow_instance_wait` port/adapter pair — see
`ParallelAwaitForEachStep`'s and `FlowStateCodec`'s own javadoc for the full reasoning. This is why
**B1 (storage + migration) needed no new schema, no new adapter, and no migration at all**: nothing
was added below the state-blob layer, so there is nothing for an existing in-flight `WAITING_EVENT`
row to need converting.

**Scope-down, deliberate and recorded (mirroring B15(A)'s own scope-downs):** the loop body must be
EXACTLY one `AWAIT_EVENT` step — no steps before or after it. B15(A)'s sequential model safely
re-runs an iteration's entire step list from scratch on every (re-)attempt because only ONE
iteration is ever in flight; with N iterations attempted independently in one pass, a step that
mutates a non-namespaced `state` key would silently clobber across iterations. Scoping to
await-only sidesteps that hazard entirely rather than solving it partially. A future lift that wants
pre/post-await steps needs its own design pass for that specific hazard.

**The question:** N loop iterations each independently awaiting their own event, genuinely
outstanding *at the same time* (not the one-at-a-time shape P6/B15(A) closed). Two storage designs
were costed; neither was built.

**Option A — one `FlowInstance` row per loop iteration** (a child row, keyed by a new
`(parentExecutionId, iterationIndex)` pair alongside today's `execution_id` primary key).
- *Reuses* everything `FlowInstance`-shaped already does correctly per-row: `ResumeCoordinator`'s
  matching, `resumeExecution`, idempotency (already keyed by `executionId` — a child row's own
  distinct `executionId` gets this for free), and — per B15(A)'s own investigation — `state_json`
  needs no new column, since each child row already gets its own independent `correlation_id`,
  `current_step_index`, `waiting_for_event_name`.
- *Costs:* the PARENT flow has no existing concept of "wait for N child executions, then resume."
  `resumeExecution`/`executeFlowInstance` (`NPDevKernel/kernel/.../KernelRunner.java`) would need a
  new join/barrier: something has to notice "the last of my N children just completed" and re-enter
  the parent's own forward step loop past the `forEach`. Compensation (`CompensationRunner`, LNCH-17)
  currently reverses ONE flow's own steps in order — undoing a partially-completed parallel loop
  means reversing across N child executions, an aggregation this class does not have today. Every
  place that currently assumes "one row = one flow" (`resumeAllWaitingExecutions`'s scheduled sweep,
  `flowInstanceStore.findWaitingEligibleToResume`, the `STUCK` detection in §2) would need to decide
  whether a child row counts on its own or only as part of its parent's aggregate state.

**Option B — a new `flow_instance_wait` table**, one row per `(parentExecutionId, iterationIndex)`,
purpose-built to track ONLY wait state (`awaitEventName`, `matchCorrelation`, `payloadMatchRefs`,
resolved-or-not, resolved payload) — the parent `FlowInstance` stays a SINGLE row, parked in
`WAITING_EVENT` for as long as ANY child wait slot is outstanding.
- *Costs less duplication* than Option A: no new executionId/compensation-chain/idempotency-scope
  per iteration — this is genuinely just "N wait slots," not N miniature flows. `AwaitEventStep`'s
  resolved payload would need to land at a per-iteration state key (e.g.
  `awaitRef + "." + iterationIndex`, mirroring how B15(A)'s satisfaction-marker key is already
  per-step, not per-flow) rather than the single `state.put(awaitRef, payload)` slot it uses today.
- *Costs:* a genuinely NEW adapter port + `-inproc`/`-postgres` pair, mirroring `FlowInstanceStore`'s
  own existing shape (`NPDevKernel/kernel/.../ports/FlowInstanceStore.java` and its two adapters) —
  new schema, new schema-mirror obligations, new resume-matching code path in `ResumeCoordinator`
  that has no existing analog to extend (unlike Option A, which mostly extends things that already
  exist).

**Decision (S6 B0, superseding "leaning, not a decision"):** shipped as Option B's architecture —
"one flow, one step, N provisional wait slots" — but via the state-blob refinement above, not a new
table. Option A (N child `FlowInstance` rows) was rejected for the same reason the original costing
found it more expensive: it would have needed a new parent/child join-barrier concept in
`resumeExecution` and an N-way aggregation `CompensationRunner` does not have today, for no benefit
this shape actually needs (nothing here wants independent compensation/idempotency identity per
iteration).

**Migration for existing in-flight `WAITING_EVENT` rows — checked before building, per this Move's
own instruction, and re-verified true after building:** parallel awaits is a NEW, additive
capability (opt-in via the new `parallelAwait` step attribute, not a change to today's
`forEach`/`await` semantics) implemented entirely inside `state` (see B0 above) — every existing
`WAITING_EVENT` row, whether a bare await or a B15(A) sequential loop, is a single-`FlowInstance` row
today and stays exactly that; nothing new was added below the state-blob layer, so there is nothing
for an existing row to need converting. Confirmed, not just claimed: the full `NPDevKernel` test
suite (kernel + every adapter) passes unmodified against the shipped code.

**Completion semantics (B3) — decided and recorded (in `ParallelAwaitForEachStep`'s own javadoc,
authoritative; summarized here):**
- **Join/barrier:** the loop step completes only once ALL N iterations resolve — the "wait for
  every reply" reading. No partial/best-effort completion.
- **Fail-fast vs. partial failure:** a genuine (non-waiting) failure from any iteration's await
  attempt fails the whole step immediately, matching `ForEachStep`'s existing sequential behavior
  for a real failure. No mixed-result reporting was introduced.
- **Unsatisfiable slot / per-iteration timeout:** parks forever, subject to the SAME
  resume-eligibility backoff and eventual `STUCK` detection every single-slot await already has —
  no new timeout concept. Per-slot timeouts are their own future design question (e.g. a scheduled
  event), deliberately left open, exactly as this write-up originally flagged it.

**What's built:** `FlowStepDefinition.forEach(..., parallelAwait)` (kernel step model),
`FlowStateCodec`'s `PARALLEL_AWAIT_STATE_KEY_PREFIX`/`PARALLEL_AWAIT_RESOLVED_KEY_PREFIX` (durable
per-iteration representation), `ParallelAwaitForEachStep` (the N-slot execution algorithm,
dispatched from `ForEachStep`), and `ResumeCoordinator`'s parallel-aware matching (both the
event-driven path and the scheduled-sweep pre-check) — `NPDevKernel/kernel/src/main/java/com/npdev/kernel/`.
**Verify:** `KernelRunnerParallelAwaitInLoopRestartProofTest` — N=3 iterations genuinely outstanding
at once, events delivered out of order across two full process restarts (brand-new `KernelRunner`
instances), each iteration resumes exactly once on its own event, the merged result lands in
iteration order regardless of arrival order; plus the mid-checkpoint freeze scenario proving a crash
right after one slot resolves does not re-query its already-processed event. Full `NPDevKernel`
suite (kernel + every adapter) green. **Deliberately not done:** steps before/after the await within
one iteration (see the scope-down above) — a future lift, not a mechanical extension of this one.

---

## 8. Sequencing & dependencies

```
Independent features (no hard cross-dependencies) — parallelizable:

LIFT-EXPR    P1 ─► P2 ─► P4
                └► P3 ─► P5
LIFT-UNIQUE  P1 ─► P2
                └► P3 ─► P4 ─► P5
LIFT-ROWOPS  P1 ─► P2 ─► (P3) ─► P4        (reuses Workbench rowOps JS)
LIFT-QUERY   P1 ─► P2 ─► P3 ─► P4
LIFT-UPLOAD  P1 ─► P2 ─► P3 ─► P4 ─► P6
                          └► P5
LIFT-LOOP    P1 ─► P2 ─► P3 ─► P4 ─► P6
                └► P5
(P7: costed design only, not implemented -- see LIFT-LOOP-P7's own "why deferred")
```

**Recommended single-implementer order:** LIFT-EXPR → LIFT-UNIQUE → LIFT-ROWOPS → LIFT-QUERY →
LIFT-UPLOAD → LIFT-LOOP. Rationale: front-load the low-risk validation/expression work (shared muscle,
fast wins), do the UI-shaped LIFT-ROWOPS while that context is warm, then the runtime-shaped
LIFT-QUERY, and finish with the two genuinely new subsystems (LIFT-UPLOAD storage, LIFT-LOOP durable
resume) where the design risk concentrates and deserves undivided attention.

**Shared refactors to do once, early (avoid duplication):**
- Extract `PanelRuntime.applyQueryWhereFilter` into a shared where-predicate — consumed by
  **LIFT-QUERY-P1** and already by panels.
- Extract Workbench row-op JS into a mustache partial — consumed by **LIFT-ROWOPS-P2**.
- `ComputedExpression` boolean-completion (**LIFT-EXPR-P1**) is the substrate for both invariant
  enforcement and any future client-side expression eval.

---

## 9. Cross-cutting concerns

- **Schema mirror ×4** on every schema change (LIFT-EXPR-P4? no; LIFT-UNIQUE-P1, LIFT-ROWOPS-P1,
  LIFT-UPLOAD-P2, LIFT-LOOP-P1 all touch schema).
- **Restage jars** after every kernel/adapter change (all features touch the kernel/adapters).
- **Tenant isolation** is a first-class acceptance criterion for LIFT-UPLOAD (file handles) and
  LIFT-QUERY (filtered rows must respect the caller's tenant) — never regress it; never use tenant
  `"default"` (ARCH-15).
- **Sandbox invariant** (LIFT-QUERY): the capability must remain a pure function — it receives rows as
  input and holds no data handle. This is a review gate, not just a test.
- **Durability invariant** (LIFT-LOOP): no loop may produce duplicated side effects across a resume.
- **Backward compatibility:** single-field `unique`, DNF invariants, existing panels/flows/procedures
  must all behave identically after each feature lands (superset, not replacement).
- **Testing tiers** (per the prior roadmap): DSL/JUnit → generator gate → runtimehost gate → frontend
  gate → live ScrapForAI browser/REST on an H2 sample. `DONE` requires the live tier.

---

## 10. Risk register

| Risk | Feature/Phase | Mitigation |
|---|---|---|
| Expression semantics drift from old DNF matcher | LIFT-EXPR-P1/P2 | Corpus-equivalence property test; keep the new grammar a strict superset; ship P2 only after equivalence green |
| Client/server expression parity (if invariants go client-side) | LIFT-EXPR-P5 | Prefer a debounced server `validateModel` round-trip over a second TS parser; if porting to TS, share a single test corpus |
| Compound-unique DB vs InMemory divergence | LIFT-UNIQUE-P3 | One `exists(fields[])` contract, tested on both stores; JDBC violation-name mapping pinned by test |
| Row-op JS duplicated between Workbench + Panel | LIFT-ROWOPS-P2 | Extract to a shared mustache partial before adding the Panel path |
| Unbounded query result handed to a capability | LIFT-QUERY-P1 | Soft size cap + warning; honor `where`+`orderBy`+`limit` |
| Multipart not enabled / large-file OOM | LIFT-UPLOAD-P3 | Confirm Spring multipart config; stream (no full buffering); enforce `maxSizeBytes` before read |
| Blob orphans on replace/delete | LIFT-UPLOAD-P3 | Cascade delete + a periodic orphan sweep; test replace+delete paths |
| Object-store infra unavailable in dev | LIFT-UPLOAD-P1 | Filesystem `*-inproc` adapter is the dev default; object-store behind config, tested against minio/localstack |
| Loop state corrupts durable resume (double effects) | LIFT-LOOP-P2 | Per-iteration idempotency keys reusing the engine's existing dedup; forced-resume test is a merge gate |
| `await` inside a loop (sequential, one outstanding at a time) | LIFT-LOOP-P1/P6 | **Closed, Move 16 Phase B (2026-08-03).** Rejected in P1; lifted in P6 with its own required restart-proof test (`KernelRunnerAwaitInLoopRestartProofTest`) per the mitigation this row always called for |
| `await` inside a loop (parallel, N outstanding at once) | LIFT-LOOP-P7 | **Closed, S6 Phase B (2026-08-03).** Move 16 Phase C costed the design; S6 picked it back up same-day, chose the state-blob refinement over a new wait-table (B0), and shipped it with its own required restart-proof test (`KernelRunnerParallelAwaitInLoopRestartProofTest`), scoped to a single-step (await-only) loop body |
| VS Code Gradle file-lock on regen | all | Bump build-root suffix (`-alt`/`-hNN`) per CLAUDE.md |

---

## 11. Immediate next actions

1. **LIFT-EXPR-P1** — boolean-complete `ComputedExpression` (unary `!`, `null`, string literals,
   `evaluateBoolean`) + the corpus-equivalence test. Lowest risk, unblocks the most.
2. In parallel: **LIFT-UNIQUE-P1** (schema/DSL accept `fields[]`, drop the
   `SemanticValidator.java:363` throw) and the two shared refactors in §8 (where-predicate extraction,
   Workbench row-op partial).
3. Stand up **LIFT-UPLOAD-P1** (FileStore port + filesystem adapter) early as a spike so the storage
   contract is proven before the endpoint/UI phases depend on it.

---

## 12. Changelog

- **2026-07-13** — Initial roadmap. Objective: lift all six accepted design boundaries
  (ARCH-6/7/loop/upload/compound-unique/13) into supported features. Design decisions locked via
  kickoff questions: pluggable file-store adapter · extend `ComputedExpression` · keep-pure +
  query-backed step · full-stack incl. NPDevEditor.
- **2026-07-13** — All 27 phases across all six features implemented and verified (LIFT-EXPR,
  LIFT-UNIQUE, LIFT-ROWOPS, LIFT-QUERY, LIFT-UPLOAD, LIFT-LOOP). **LIFT-LOOP done last** (§7,
  ARCH-loop lifted): `forEach` flow steps compile, validate, execute durably (genuine crash-and-resume
  proof — freeze the executing thread forever right after a checkpoint commits, then resume on a
  brand-new `KernelRunner` sharing only the durable store), project through the flow-compiled adapter,
  boot inside a real generated FinalApp, and are authorable in the flow builder with a recursive
  nested-step-list editor (the first one in `ui-react` — `branch`'s `then` had never been rendered
  either). Two deliberate, documented scope-downs across the whole roadmap: LIFT-UPLOAD's
  object-store adapter (needs external SDK/infra this session can't provision) and LIFT-UPLOAD-P6's
  WMS `Add Doctos` wiring (needs a live app in a layer-2, non-git app-definitions directory outside
  this session's reach) — both are additive follow-ups, not blockers; the `*-inproc` file-store default
  and the upload primitive itself are fully done. None of ARCH-6/7/loop/upload/compound-unique/13 is a
  constraint anymore.
