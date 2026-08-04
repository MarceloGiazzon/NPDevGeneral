# ADR-0011 Bounded Contexts (B20)

## Status

**APPROVED — 2026-08-03.** Design drafted as a spike (`__OutsideRepo\s1\b20-design.md`, S1_SPEC.md
§3) explicitly *not* as a unilateral resolution — `docs/HUMAN_VS_AI_VERIFICATION.md:402` names this
decision `👤 human`. The owner reviewed D1–D4 and the additional acyclicity question (§8 below) via
an explicit accept/adjust/override choice at the start of S2 and accepted all of them as written,
with cycles rejected per the recommendation. This document is the durable record CLAUDE.md's
stability policy requires for a DSL break of this size — the largest the platform has taken.

## Context

`namespace` on a model has always been cosmetic — MEASURED before this ADR (grep-verified): every
real consumer either passes it through unchanged (AST → compiled → canonical JSON), uses it as a
provenance/display-name fallback (`BusinessUiEmitter`'s `appName`), or sanitizes it into a filesystem
directory name. Nothing treats it as a scoping boundary, an identity qualifier, or anything checked
for uniqueness. `docs/ACCEPTED_BOUNDARIES.md` B20 recorded this as a deliberate non-decision: model
each business domain as its own app until real cross-domain-within-one-app demand appears.

That demand is the roadmap's own framing for this work: a third person authoring a real app hits
"naming a second domain area; two things want to be called `Order`" in hour one. B20 gives `namespace`
real semantics — declared contexts, qualified cross-context references, an explicit import graph —
without waiting for that pain to be felt on a live app first (the roadmap's stated principle:
anything that changes the *shape of a model* must land before an external author writes one).

## The four decisions, and the fifth

### D1 — Qualified-name syntax: `context::Concept`

**Decision:** cross-context references use `context::Concept` (double colon), identical to how a
pack-imported concept is already qualified `packId::ConceptName` today.

**Why:** `.` is already claimed by four live grammars (`$root.field`/`$ui.name` predicates,
the `$`-prefixed dotted state-reference convention shared by `patchConcept.set`/`mapList.select`/
`computeValue`/`return.valueRef`, and `ComputedExpression`'s nested-map field paths like
`cliente.tipo`), plus one *proposed* grammar that would collide directly: B27's own named syntax for
cross-concept groupBy joins, `groupBy: ["lote.produtoId"]`. Picking `.` for B20 would recreate, one
layer down, exactly the ordering trap the roadmap names for building joins before contexts. `::` has
exactly one live meaning today — pack-scoped concept-identity qualification
(`ModelSourceResolver.java`: `packId + "::" + name`) — consumed by `JavaIdentifierSupport.java`'s
existing `::`-split Java-identifier mangling. D1 reuses both, unmodified.

**Rejected:** `context.Concept` (the B27 collision above); a novel sigil like `context@Concept` (a
fourth distinct namespacing spelling with no parser/mangling code to reuse); structured object
references (`{"context": "...", "concept": "..."}` instead of a string) — rejected on authoring
ergonomics, since every reference site (`where`, `groupBy`, panel `dataSource` bindings) is a flat
string today.

```decision-check
id: ADR-0011-D1
file: NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java
contains: mergeQualifiedConcepts("Context",
```

### D2 — A context is a file/folder boundary, composed via the existing `$ref`/fragment machinery

**Decision:** one context = one physical fragment file. The root model declares
`contexts: [{"name": "inventory", "$ref": "contexts/inventory.model.json"}]`; the fragment is
pack-shaped (validated against `pack.schema.json`, resolved by the same nested-fragment/`$ref` logic
packs already use) rather than a bespoke second composition mechanism.

**Why:** decided with S5 (multi-author merge) in mind, per the spike's own instruction. A file
boundary gives two authors structurally disjoint edits at the git level for free; an in-model
declaration (`concepts[].context: "inventory"` on one shared document) reintroduces the single-document
merge problem S5 exists to shrink. Reusing `ModelSourceResolver`'s already-hardened fragment
resolution (relative-only `$ref`, absolute-path rejection, deterministic composition) avoids a second
implementation with its own bug surface.

**Rejected:** an in-model `context` field per concept (reintroduces the S5 merge problem); a bespoke
directory-of-files loader that does not reuse fragment/pack composition (duplicates hardened logic for
no benefit).

```decision-check
id: ADR-0011-D2
file: NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java
contains: loadPackJson(contextFile, state)
```

### D3 — Explicit `imports[]`; an undeclared cross-context reference is a compile error

**Decision:** a context fragment declares `imports: ["otherContext", ...]`. A `context::Concept`
reference is legal only if the referencing context's own `imports[]` names that context. Undeclared →
a named, thrown resolution error — never a silent resolve-to-nothing.

**Why:** the whole point of bounded contexts is making cross-boundary coupling visible and checkable.
The platform's standing X0 discipline (an input the evaluator cannot handle is an error, never a
default answer) argues directly against free reference. `imports[]` turns "does this app's context
graph have hidden coupling" into a mechanically enforced property, the same shift the four-place rule
and `check-twin-pair-consistency.py` already made for "did a field get threaded through everywhere it
needs to be."

**Rejected:** free reference (any context may reference any other's qualified names with no
declaration) — friendlier short-term, but reintroduces the undisciplined flat-vocabulary coupling B20
exists to fix, with `::` punctuation sprinkled on top.

```decision-check
id: ADR-0011-D3
file: NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java
contains: imports undeclared context '
```

### D4 — No physical table prefixing in v1

**Decision:** table names are derived exactly as today, from the bare concept name (ignoring any
context qualifier). A context is a DSL/compiler-level boundary only.

**Why:** this is the highest-consequence of the four. `docs/ACCEPTED_BOUNDARIES.md` B20 itself
measured that even WmsOffice — 32 concepts, the platform's largest single app — has zero sub-domain
pressure severe enough to want a second context; nobody has the same-name-collision problem table
prefixing would solve. WmsOffice also already has live data, and the roadmap's own organizing rule is
that anything changing durable runtime state must land before an app has live data — prefixing would
violate that in spirit on day one, for zero named beneficiaries. D3's explicit-import discipline
already defuses the actual collision case (two contexts in one app both declaring `Lote`) as a
*deliberate* act, catchable as a compile-time "duplicate concept" error (already implemented — see
`mergeQualifiedConcepts`) rather than a silent DB-level collision.

**Rejected:** always prefix — rejected as v1 because it converts B20 from a pure DSL change into a
DSL-and-data change on day one, the shape the roadmap explicitly wants to avoid shipping first.
Deferred to an explicit, opt-in v2 mechanism (e.g. a concept declaring `physicallyIsolate: true`) for
the app that actually needs two physically separate same-named tables.

**Update (S8 Wave 4, 2026-08-04): the v2 opt-in shipped, exactly as named above.** This v1 reasoning
stands unchanged — the default is still `false`, blanket prefixing was never built, and no existing
app's tables moved. See the Wave 4 addendum below for what shipped and two real gaps it found.

```decision-check
id: ADR-0011-D4
file: NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/ModelCompiler.java
contains: tableNameSource
```

### D8 — The import graph must be acyclic

**Decision (owner-accepted alongside D1–D4):** two contexts may not mutually import each other,
directly or transitively. A cycle is a named, thrown resolution error (`ModelSourceResolver`'s DFS
cycle detector, reporting the actual cycle path).

**Why:** consistent with D3's whole purpose. A cycle is exactly the shape of coupling bounded contexts
exist to make visible — allowing it would let the import graph degrade back into the same flat,
undifferentiated vocabulary B20 replaces, just with declared edges instead of none.

**Rejected:** allow cycles — no case was found where a real app needs mutual context dependency that
explicit, acyclic imports cannot express by extracting the shared concept into a third, common
context instead.

## What B20 does NOT do in v1 (say no to these; they are the schedule risk)

- No cross-context transactions — `saveConcept`/`patchConcept` stay single-context; a cross-context
  write is two separate calls with no new atomicity guarantee.
- No cross-context Bonds — `anchor`/`port` must be in the same context.
- No per-context permissions/roles — `roles[]`/`RolePermissions` stay app-global.
- No physical table prefixing (D4).
- No context-scoped generated navigation — `workspace::Menu`/ControlPanel stay flat.
- No runtime context rename/merge tooling — renaming a context is a codemod-shaped break like any
  other.
- No corpus migration (S3) and no codemod (S3) in S2 itself — S2 ships the mechanism and exactly one
  fixture witness (`dsl-conformance-max`); S3 (see addendum below) built the codemod and migrated
  exactly two more models for a concrete reason each. `contexts[]` remains optional indefinitely —
  see the addendum for why a corpus-wide migration was decided against.
- No groupBy join syntax (S4, B27) — a query still names exactly one `concept`; a context-qualified
  `query.concept` is the only reference site this increment enforces the import gate on.

## What is actually built (S2)

- Schema: `contexts[]` on all four `model.schema.json` copies (`{name, $ref}`, additional
  properties forbidden); `imports[]` on both `pack.schema.json` copies (context fragments are
  pack-shaped per D2). `check-schema-mirror-consistency.py` extended to also compare the two
  `pack.schema.json` copies as an independent group (S2 is the first real reason they could diverge).
- Parse: `JsonModelParser`/`ModelAst` gain a `ContextAst(name, ref)` list — metadata surviving
  post-composition, mirroring `RoleAst`'s shape.
- Resolve (`ModelSourceResolver`, the center of gravity): `resolveContexts` composes each declared
  context's fragment file end to end reusing `loadPackJson`/`resolvePackRoot` (schema validation +
  nested-fragment/`$ref` resolution, unchanged pack code), qualifies every concept/query/panel/flow
  `contextName::Member` (`mergeQualifiedConcepts`/`mergeQualifiedNonConceptArrays`, generalized from
  the pack-only `mergePackConcepts`/`mergePackNonConceptArrays` with a `kindLabel` for error messages
  and a `QualifiedReferenceValidator` hook), pre-validates the import graph (every import names a
  declared, non-self context) and its acyclicity (D8) before merging anything, then gate-checks every
  already-qualified reference a context's own content makes: a self-reference or a declared import
  resolves; a reference to a name that isn't a known context at all is assumed to be an (unrestricted,
  unchanged) pack reference; a reference to a known context NOT in `imports[]` is a named, thrown
  error.
- Compile (`ModelCompiler`/`CompiledModel`): `CompiledContext(name, ref)` registry, pass-through —
  qualification itself already happened at resolution, so this is metadata, not new semantic logic.
  `BusinessUiEmitter`'s `appName` fallback (`modelAst.getNamespace()`) is untouched. **Correction
  (S3, see addendum below): at the time this section was written, D4's table-name promise was not
  actually implemented here** — `ModelCompiler` derived the table name straight from the qualified
  concept name, and `SqlIdentifierSupport.toSnake` folds `::` into `_` rather than stripping it, so a
  context-qualified concept's table WAS prefixed exactly like a pack-qualified one. Fixed in S3.
- Canonical round-trip: `CompiledModelCanonicalJson`/`CompiledModelCanonicalJsonReader` read/write
  `contexts[]`, verified both by a dedicated fixture and by the existing generic
  `CanonicalJsonRoundTripCompletenessTest` (reflection-driven; it discovered `CompiledContext`
  automatically and round-tripped it with zero new test-authoring for that class).
- Tests: `BoundedContextResolutionTest` (7 cases) proves, RED then GREEN: two contexts compose with
  qualified names and an imported reference resolves; an undeclared cross-context reference fails
  named; an import of an undeclared context fails at declaration time; self-import is rejected; an
  import cycle is rejected (D8); a pack-qualified reference inside a context stays unrestricted; a
  model with no contexts is unaffected.
- Twin-pair registry: `bounded-context-four-place` (`contexts` threaded through
  parser/resolver/compiler/ModelResolver/canonical-writer-and-reader) and
  `bounded-context-imports-gate` (`imports` threaded through both `pack.schema.json` copies and the
  resolver) — two rules, not one, because their real required-file-sets differ (`imports` never needs
  to reach the compiler/canonical layer; it is spent entirely at resolution).
- Corpus: one two-context example added to `NPDevSamples/dsl-conformance-max` (`check-dsl-coverage.py`'s
  own witness requirement) — the only corpus change in S2; the other 31 models are S3's job.

## Consequence

`namespace` stops being the only recorded (if cosmetic) grouping a model has. A context is now a real
compiler-level boundary with an enforced, acyclic import graph — cheap today (zero corpus migration,
zero schema-breaking change for any existing app, since a model with no `contexts[]` behaves
identically) and the prerequisite for S4 (groupBy joins needing qualified references to exist first)
and S5 (multi-author merge, where two authors' disjoint contexts are what makes concurrent submission
tractable instead of a single 45 KB document's compare-and-swap).

## S3 addendum (2026-08-03) — the codemod, the corpus decision, and two gaps this trial found

**§0 decision: `contexts[]` stays optional indefinitely.** Migrating the other ~29 corpus models into
single-context wrappers would add indirection to apps with no second context, for no behavioral gain
— the recommendation `S3_SPEC.md` §0 made, confirmed. No corpus-wide migration was run.

**The codemod exists:** `npdev migrate bounded-contexts --input <definition-dir> [--write]`
(`NPDevCli/dsl_v2_migration_bounded_contexts.py`, `npdev_cli.py`). Dry-run by default. It wraps a
model's entire authored content into ONE new context, deriving the context's name from the model's
`namespace`/`model` field.

**A material correction to the spec's own technical premise, found before writing any code:**
`S3_SPEC.md` §2.3 proposed rewriting relocated `$ref`s by prepending `../` per directory level. This
is schema-invalid, not just risky — `model.schema.json`'s `localModelRef` pattern
(`^(?![A-Za-z][A-Za-z0-9+.-]*:)(?!/)(?!.*(?:^|/)\.\.(?:/|$)).*\.json$`) unconditionally forbids any
`..` segment, and `loadPackJson` schema-validates a context fragment's raw JSON — including its own
`$ref` strings — before resolving anything inside it. **The codemod instead physically relocates the
referenced files** into a `contexts/<name>/` subtree that mirrors their original relative layout, so
every `$ref` string stays byte-identical; only where it resolves *from* changes. A smaller correction:
`pack.schema.json` has no `packs` property, so pack `$ref`s can never move into a context and were
never really part of the "ref rewrite" risk the spec described (WmsOffice's 28 refs are 26 concept
refs that move + 2 pack refs that don't; pack-sample's 4 are 2 + 2 the same way).

**Two real implementation gaps, found empirically by running the codemod against real content —
not by inspection — and fixed in this same session, before the WmsOffice trial was allowed to pass:**

1. **D4 ("no physical table prefixing") was accepted here but never implemented.** Every
   context-qualified concept's table was silently prefixed exactly like a pack-qualified one
   (`SqlIdentifierSupport.toSnake` folds `::` into `_`, with no exception for a context qualifier).
   `BoundedContextResolutionTest` never asserted table-name behavior, so S2 shipped this unnoticed.
   Fixed in `ModelCompiler` (`tableNameSource`, gated on the model's own declared `contexts[]`
   names so pack-table-prefixing is completely unaffected); new test
   `BoundedContextTableNamingTest`. Live-proven on the WmsOffice trial: all 26 migrated concepts'
   table names are unchanged (`areas`, `armazems`, ... `usuarios`), while `identity::*`/
   `workspace::*` pack-qualified tables stayed prefixed as before.
2. **`flowStep.scope`** (the field `invariantCheck`/`createConcept`/`updateConcept` steps use to name
   their target concept — the same field `FlowValidation.collectConceptMutationScopes` reads for all
   three) **was never in `rewriteKnownConceptFields`'s rewrite table**, unlike `flow.concept`/
   `flow.input.concept`. A context-qualified concept's own flow steps stayed unqualified, and
   semantic validation then rejected the mismatch qualification itself introduced. First surfaced on
   `AppGen/apps/pack-sample`'s `ProcessSale` flow; the same class of bug reproduced on two WmsOffice
   flows before the fix. Fixed in `ModelSourceResolver`; new test
   `BoundedContextResolutionTest.flowStepScopeIsQualifiedAlongsideTheConceptItInvariantChecks`.

A third finding is specific to the codemod, not the resolver: **`aggregates[]` is not a
`pack.schema.json` property and can never move into a context**, but its `root`/
`collections[].concept` fields name a concept by bare name — the instant that concept moves into a
context, those references dangle (`root concept not found`). Found on the WmsOffice trial (13
dangling references across 5 aggregates). The codemod now qualifies these root-staying references as
part of the same migration (`_qualify_aggregates`), since every concept always moves as one atomic
unit — any bare reference left after a move can only mean the concept that just moved.

**Scope of "equivalent," stated precisely** (the WmsOffice trial's full evidence:
`__OutsideRepo/s3/wmsoffice-migration-trial-evidence.txt`): tables, the DB schema, and the
table-keyed generic-CRUD REST routes are identical before/after migration. Concept identity and the
generated Java class name change (`Area` → `wmsoffice_core::Area` / `WmsofficeCoreArea`) — the
intended, disclosed consequence of D1's qualified-identity model, not a defect and not something a
future codemod should try to hide.

**What actually migrated:** `dsl-conformance-max` (S2's own fixture, unchanged) and
`AppGen/apps/pack-sample` (migrated for real — the only corpus model combining a concept `$ref` and a
pack `$ref` in one document, keeping the "packs stay at root" path exercised by a committed artifact).
**Live WmsOffice was not migrated** — proven safe on a scratch copy only, per the owner's explicit
call; it has live data and `docs/ACCEPTED_BOUNDARIES.md` B20 already measured zero sub-domain
pressure for a second context. The other ~29 corpus models, including
`AppGen/apps/npdev_split_model_sample_app` (which would exercise the `fragments[]` relocation path —
confirmed working on a scratch copy, not committed), are unchanged: no `$ref`, no second context, no
forcing reason.

## S8 Wave 4 addendum (2026-08-04) — D4's own named v2 escape, shipped

**The v1 decision above is unchanged and still the default.** This addendum is additive: a context
may now opt OUT of it, one context at a time, by declaring `"physicallyIsolate": true` alongside
`name`/`$ref` in its `contexts[]` entry. Default `false`. A context that never declares the key, or
any model with no `contexts[]` at all (every existing app, WmsOffice included), compiles to
byte-identical table names as before this wave — verified by generating WmsOffice's full realized
schema before and after with a genuinely rebuilt generator jar cache on both sides (not a stale
cache, which would have made the comparison meaningless) and byte-comparing the DDL and
`schema-realization-manifest.json`; the only diff was an absolute source-path provenance string,
an artifact of the comparison method itself, not a schema change. Evidence:
`__OutsideRepo/wave4/w4-wmsoffice-no-change.txt`.

**What shipped:** `ContextAst`/`CompiledContext` gain a `physicallyIsolate` boolean (four-place
chain extended, not a new rule — `bounded-context-four-place` in the twin-pair registry now tracks
`physicallyisolate` alongside `contexts`). `ModelCompiler#tableNameSource` and the table-name-
collision validator (`ConceptValidation#validateTableNameCollisions`) both now resolve a
context-qualified name through one shared method, `SqlIdentifierSupport#contextAwareIdentifierSource`
— an isolating context keeps its qualifier (mangled by the SAME `"::" -> "_"` replacement a
pack-qualified name already gets, not a new deriver); a non-isolating one strips it, exactly as D4
v1 always did.

**Two real gaps found while building this, both empirically (not by inspection), both fixed in the
same session, mirroring S3's own addendum above:**

1. **The table-name-collision check (`ConceptValidation#validateTableNameCollisions`, REG-98) never
   actually caught D4 v1's own named collision scenario.** It hashed `concept.getName()` (the
   QUALIFIED name, e.g. `wms::Sale`) directly, a DIFFERENT string than what `ModelCompiler` actually
   compiles to (`sales`, the context qualifier D4 v1 strips) — so two DIFFERENT non-isolating
   contexts both declaring a concept named `Sale` silently compiled to the SAME real table with zero
   errors anywhere, undetected since S2. RED-verified directly against the pre-fix validator (not
   just the underlying string comparison) via a temporary `git stash` of the fix, confirming the
   collision test genuinely failed before and passes after. Now routes through the same
   `contextAwareIdentifierSource` the compiler uses, so the two can never drift apart again.
2. **`ModelSourceResolver`'s own malformed-`$ref` structural gate (`validateNoMalformedRef`) hard-
   coded which extra keys a `/contexts/N` entry may carry** (`$ref` + `name`, nothing else) — a
   check that runs BEFORE `model.schema.json`'s own `context` definition ever applies, and one none
   of "the three edit sites, all confirmed" named going into this wave. `physicallyIsolate` tripped
   it as "malformed" until widened. Found only by validating a REAL context-fragment-composing model
   (`dsl-conformance-max`) end to end — every AST-level Java test bypasses this resolver-level gate
   entirely by feeding `JsonModelParser` already-resolved JSON directly (`ContextAst`'s own documented
   contract). RED-verified directly against the pre-fix condition.

**A deliberate deviation from this wave's own planning spec, decided in-session:** the spec's own
worked collision matrix named "one context isolating, one not, same concept name" a compile error
("one still collides"). Built and verified instead: this is LEGAL, not an error — `wms::Sale`
(isolating) compiles to `wms_sales`; `logistics::Sale` (not isolating) compiles to `sales`; these are
genuinely different tables, no real collision exists, and flagging it would invent a restriction the
schema does not actually need. The only same-bare-name pairing that still collides is BOTH contexts
non-isolating (D4 v1's original scenario, now correctly caught per gap 1 above); BOTH isolating is
legal, per D4's own escape.

**Corpus witness:** `dsl-conformance-max` gained two NEW, dedicated contexts
(`contexts/isolated-a.json`, `contexts/isolated-b.json`), both `physicallyIsolate: true`, both
declaring a concept named `Ledger` — compiling to `isolated_a_ledgers`/`isolated_b_ledgers`. Added as
new contexts rather than flipping `billing`/`shipping`'s existing isolation, so this witness cannot
ripple into their already-exercised (non-isolating) table names. `check-dsl-coverage.py` gained
`contexts.physicallyIsolate` in the same commit (S6 Phase A's own `imports[]` lesson: a new context-
declaration property needs its own detector, not just the base `contexts` one).

**Tests:** `BoundedContextTableNamingTest` (+2: an isolating context keeps its mangled qualified
table name; explicit `physicallyIsolate: false` behaves exactly like absent). `BoundedContextResolutionTest`
(+2: the resolver's malformed-ref gate accepts it; absent defaults to `false` and is not emitted,
RED/GREEN both). `TableNameCollisionValidationTest` (+5: the four collision cases from this wave's
own matrix, plus a direct RED proof of gap 1 above).
