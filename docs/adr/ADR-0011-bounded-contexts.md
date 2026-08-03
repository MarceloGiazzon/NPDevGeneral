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
- No corpus migration (S3) and no codemod (S3) — S2 ships the mechanism and exactly one fixture
  witness (`dsl-conformance-max`); the other 31 corpus models are untouched.
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
  `BusinessUiEmitter`'s `appName` fallback (`modelAst.getNamespace()`) is untouched.
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
