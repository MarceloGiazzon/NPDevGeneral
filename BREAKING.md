# Breaking changes

NPDev is pre-1.0 and deliberately unstable — see the "Stability policy" section in `README.md` for
why. Every breaking change to the model DSL, generated code layout, or internal APIs gets a
one-line entry here, in the same commit that makes the change, alongside the `npdev migrate`
codemod that rewrites existing models automatically.

## 2026-08-03 — `npdev migrate bounded-contexts` codemod; ADR-0011 D4 gap fixed (S3, docs/adr/ADR-0011-bounded-contexts.md addendum)

**Not a breaking change to any existing model — stated plainly, not overstated.** `contexts[]`
(S2, 2026-08-03) was already optional and backward-compatible; this entry is about the codemod that
now exists for authors who want to *adopt* it, plus a real bug fix underneath it.

**The codemod:** `npdev migrate bounded-contexts --input <definition-dir> [--write]`
(`NPDevCli/dsl_v2_migration_bounded_contexts.py`) wraps a model's whole authored content into one new
context. Dry-run by default. It physically relocates any `$ref`-referenced concept/plugin/fragment
files into a `contexts/<name>/` subtree that mirrors their original relative layout — every `$ref`
string stays byte-identical — rather than rewriting paths with `../`, which `model.schema.json`'s
`localModelRef` pattern forbids outright (a corrected premise from the drafting spec, not a design
choice with alternatives; see the ADR addendum for the full reasoning).

**The bug fix, found by running the codemod against real content:** ADR-0011's D4 ("no physical table
prefixing") was accepted but never implemented — a context-qualified concept's table was silently
prefixed exactly like a pack-qualified one (`SqlIdentifierSupport.toSnake` folds `::` into `_`
unconditionally). Fixed in `ModelCompiler.tableNameSource`, gated on the model's own declared
`contexts[]` names so pack-table-prefixing is completely unaffected. A second, smaller gap
(`flowStep.scope` never qualified alongside its concept) was fixed alongside it in
`ModelSourceResolver`. Both are live-proven on a WmsOffice scratch-copy trial
(`__OutsideRepo/s3/wmsoffice-migration-trial-evidence.txt`) — table names, DB schema, and generic-CRUD
REST routes are identical before/after a real migration; only concept identity and the generated Java
class name change, D1's intended qualified-identity consequence.

**No corpus-wide `npdev migrate` sweep** — `contexts[]` stays optional indefinitely (§0 of
`S3_SPEC.md`, confirmed). `AppGen/apps/pack-sample` was migrated for real (the only corpus model
combining a concept `$ref` and a pack `$ref`); every other corpus model, including live WmsOffice, is
untouched — the trial proved the codemod safe, it does not by itself make migrating WmsOffice useful,
and the owner's call was not to.

## 2026-08-02 — built-in `workspace` pack: `Preference` concept retired in favor of `PropertyValue` (RC-A2, Move 14 Phase B item B1)

`Preference(id, userId, category, prefKey, prefValue)` is replaced by
`PropertyValue(id, scopeType, scopeId, propKey, propValue)`, with a new unique index
`(tenant_id, scopeType, scopeId, propKey)` (`tenant_id` implicit, generator-injected on every
composite unique like all business tables). This is the storage layer for the scoped-property
cascade RC-A1 already declared in the DSL (`properties[]`/`propertyScopes[]`, Wave 6) but had
nothing to resolve against yet.

**Why the shape had to change, not just the name:** `Preference`'s `category`/`userId` pair could
not express the cascade's core rule — row presence is the is-set signal (a row with
`propValue = NULL` means explicitly set to null at that scope; no row at all means inherit from the
next-least-specific scope) — because nothing distinguished "this scope never declared an opinion"
from "this scope explicitly declared no value." `scopeType`/`scopeId` name an arbitrary declared
`propertyScopes[].name` and its resolved instance id directly, which is what RC-A3's resolver
(`PropertyResolver.resolve()`/`.explain()`, not yet built — next item) needs to walk the cascade
correctly.

**No `npdev migrate` codemod, deliberately** — same posture as the 2026-07-28 aggregate-boundary
entry below: there is nothing to mechanically rewrite because there are no witnesses. Measured, not
assumed, before writing this entry (Move 14 Phase B item B0, `__OutsideRepo/move13-helpers/
rc-a2-row-count-evidence-2026-08-02.txt`): zero corpus models (`AppGen/apps/**`, `NPDevSamples/**`)
declare `"Preference"` anywhere, and a live row count against every H2 database that actually
realizes the table (`wmsoffice`, plus a leftover `reg39-healthy-control` REG-39 fixture) returned 0
rows in both. `Preference` was realized as a table purely because the built-in `workspace` pack
declared it and `WmsOffice` includes that pack — nothing ever read or wrote it (no resolver existed
to). The next boot of any app including the `workspace` pack will see the old `workspace_preferences`
table as an orphaned/destructive schema diff through the existing schema-lifecycle acknowledgment
mechanism (LNCH-1 P6) — expected and correct, not a gap this entry needs to paper over.

**Swept:** the one private copy of the `workspace` pack (`AppGen/apps/_official/WmsOffice/
definition/packs/workspace/pack.json`, confirmed byte-identical to the built-in before this change —
Move 13's REG-39 drift hazard needs multiple copies and/or existing drift, neither present) was
updated identically in the same commit; `rc-a2-preflight.py`'s private-copy comparison confirms
`[IDENTICAL]` again after the sweep.

## 2026-08-01 — `queries[].where` grammar now accepts `:name` bind placeholders bound against a declared `parameters[]` (REG-101, Move 12 P1.4)

Widens, not breaks, the LC-P0 grammar directly below: a `:name` literal (previously always refused
as "neither a quoted string, a number, nor a boolean") now parses as a bind placeholder, resolved
against the query's declared `parameters[]` at compile time and against a caller-supplied value map
at runtime (`ConceptQueryPredicateCompiler.compile(where, parameters, boundParameters)`). An
unbound or undeclared placeholder is still refused by name (X0), never defaulted. No existing valid
`where` stops compiling — every accepted-before shape is still accepted — so no `npdev migrate`
codemod is needed; only new grammar became legal.

The grammar itself moved to `NPDevContract/dsl` (`com.npdev.dsl.v1.query.QueryPredicateGrammar`) so
`PackValidation.validateQueries` can refuse an uncompilable `where` at AUTHORING time, not just at
runtime — the durable fix REG-101's own detail asked for. `scripts/quality/check-query-predicate-compilable.py`
(the Python reimplementation of the same grammar, AI-knowledge gate step 22) and
`scripts/quality/query-predicate-allowlist.json` are both **deleted**: the corpus-wide check that
script existed for is now done by the real Java validator via `scripts/quality/validate-corpus.py`,
which already runs `SemanticValidator` over every corpus model.

`pack-sample`'s `SalesByStore` (`where: "storeId == :storeId"`, REG-101's own witness, filed
2026-07-31) is the proof: it now compiles clean and, once bound, returns exactly the matching
store's rows — proven live in
`ConceptQueryPredicateCompilerParameterSubstitutionTest`. REG-101 → DONE.

## 2026-07-31 — a declared `queries[].where` the engine cannot compile is now an ERROR, not silently unenforced (LC-P0)

`ConceptQueryFilterSupport` used to hand-parse a `where` with `indexOf("==")` and, per its own
javadoc, leave "a clause outside this shape … unenforced (rows pass through unfiltered)". It now
compiles the predicate with `ConceptQueryPredicateCompiler` and throws
`QUERY_PREDICATE_UNSUPPORTED` (a named `UnsupportedPredicateException`) for anything outside:

```
where   := clause ( "&&" clause )*
clause  := field op literal        op := == | != | >= | <= | > | <
literal := 'text' | number | true | false
```

**What now works that never did:** multi-clause `&&`, the ordered comparisons (`> >= < <=`), a
literal containing `&&`, and `>=` not being mis-read as `>`.

**What now fails loudly that used to return a wrong answer silently:** `||`, `in (...)`, functions
(`upper(x) == …`), nested paths (`a.b == …`), unquoted non-numeric literals, and unsubstituted
`$`/`:` references.

**There is no `npdev migrate` codemod, deliberately, and this is the one entry here without one.**
A codemod rewrites a declaration whose meaning is known; these declarations never had a working
meaning — the engine was ignoring them, over-filtering them to zero rows, or inverting them. There
is no correct automatic rewrite for "your filter never worked"; the author has to say what they
meant. What ships instead is a **detector**:
`scripts/quality/check-query-predicate-compilable.py` (AI-knowledge gate step 22) fails on any
corpus `where` that will now be refused, so this is found by a gate rather than by a running app.
(**Superseded 2026-08-01** — see the entry above this one: the detector and its allowlist are both
deleted, their job now done by the real Java validator at authoring time.)

Its first run found one: `pack-sample`'s `SalesByStore` declares `where: "storeId == :storeId"`
with a matching `parameters[]` entry that **nothing substitutes** — so that query has returned zero
rows for its whole life. Filed as **REG-101**, closed 2026-08-01.

Three prior behaviours are pinned as a before/after table in
`ConceptQueryFilterSupportRedTest`, including the one the finding itself got wrong: a 2-clause
`AND` returned **zero** rows, not "every row".

## Removal trigger (not yet a breaking change): the six retired `transaction.metadata` keys

`recompute`, `derived`, `computed`, `actions`, `visibleWhen`, and `bandPickers` under
`autoPanel.transaction.metadata` (retired below in favor of their typed replacements) now all emit
a deprecation WARNING when present (`PanelValidation`, Move 8 item G4) but still work as a
fallback — no removal date is set, since dates rot. **Trigger:** these six untyped keys are removed
entirely in the next breaking DSL change, whichever that turns out to be; when that change lands,
add the actual removal as its own dated entry here and extend `npdev migrate dsl-2` to reject
(not just rewrite) them. The corpus (`AppGen/apps` + `NPDevSamples`) is confirmed clean of all six
today.

## 2026-07-30 — Aggregate Workbench: `transaction.metadata.actions`/`.visibleWhen`/`.bandPickers` retired in favor of typed `transaction.actions`/`.visibleWhen`/`.bandPickers`

`autoPanel.transaction.metadata.actions` (a list of `{label?, procedure, inputFields?, applyTo?,
afterAction?, visibleWhen?}`), `.metadata.visibleWhen` (an object keyed by collection/band name, a
predicate string), and `.metadata.bandPickers` (an object keyed by band name, `{panel, label?,
columns?}`) are retired in favor of the typed, schema-validated `transaction.actions`/
`.visibleWhen`/`.bandPickers` — same shapes, now with `additionalProperties: false` so a typo'd key
(e.g. `actons`) fails at schema time instead of silently doing nothing. Both old keys still work
for this release (every read site in `AutoPanelExpander` accepts them as a fallback when the typed
slot is absent) — but new authoring should use the typed spelling; the fallback is expected to be
removed in a future release. When both a typed and untyped spelling are declared on the same
surface, the typed one wins entirely (it is not merged with the untyped list/map), matching the
precedent Move 6 set for `hooks`/`derivedFields`.

**Why:** docs/MOVE7_IMPLEMENTATION_SPEC.md W1 — the last three untyped `transaction.metadata` keys
left over after Move 6 typed `hooks`/`derivedFields`/`regions`. `transaction.actions[].procedure`
and `.afterAction` now also get real semantic validation (must name a declared procedure); a
`visibleWhen`/`bandPickers` key must name a real address/band derived from the aggregate's own
composition tree — the same class of check Move 6 already added for `transaction.regions`.

**Codemod:** `npdev migrate dsl-2 --input <path...> [--write]` (dry-run by default) now also
rewrites `transaction.metadata.actions` → `transaction.actions`, `.metadata.visibleWhen` →
`.visibleWhen`, and `.metadata.bandPickers` → `.bandPickers`, idempotently, dropping only the
malformed sub-fields (an unusable `applyTo`, a missing `procedure`/`panel`, a blank predicate) the
compiler always silently tolerated anyway, and reporting (not guessing) when both an old and new
spelling are present. See `NPDevCli/dsl_v2_migration.py`'s `_migrate_transaction_actions` /
`_migrate_transaction_visible_when` / `_migrate_transaction_band_pickers`.

**Migrated in this change:** no git-tracked corpus model declared `metadata.actions`,
`.visibleWhen`, or `.bandPickers` before this (all three were zero-witness in the tracked corpus;
`dsl-conformance-max` gains the first typed witness alongside this change).

## 2026-07-30 — Aggregate Workbench: `transaction.metadata.recompute`/`.derived` retired in favor of typed `transaction.hooks`/`.derivedFields`

`autoPanel.transaction.metadata.recompute` (a bare procedure name, or `{procedure}`) and
`.metadata.derived` (a list of `{name, expression, label?}`) are retired in favor of the typed,
closed-enum `transaction.hooks.onFieldChange` and the object-keyed `transaction.derivedFields`
(which also gains a `tier: "server"` option `.derived` never had). Both old keys still work for
this release — every read site accepts them as a fallback and `SemanticValidator` emits a
deprecation warning, not an error, when it sees either — but new authoring should use the typed
spelling; the fallback is expected to be removed in a future release.

Also new, additive (no retirement): `transaction.hooks.onLoad`/`.beforeAction` (no prior
untyped equivalent existed), `transaction.hooks.onValidate`/`.onCommit` (an alternate spelling of
the pre-existing `aggregate.onValidate`/`.onCommit` fields — a direct aggregate-level declaration
always wins if both are present), and a per-action `afterAction` (declared alongside, not instead
of, the pre-existing per-action `applyTo`, which it subsumes going forward but does not retire).

**Why:** docs/MOVE6_TYPED_SURFACE_PLAN.md §B — the same feature was typed when it attached to
`panelAction`/`procedure`/`flow`/`aggregate`, and untyped when it attached to
`autoPanel.transaction.metadata`, purely because of which object it happened to land on. A closed
`hooks` enum means an author's typo (e.g. `onRowLoad` for `onLoad`) fails at schema time instead of
silently doing nothing.

**Codemod:** `npdev migrate dsl-2 --input <path...> [--write]` (dry-run by default) now also
rewrites `transaction.metadata.recompute` → `transaction.hooks.onFieldChange` and
`transaction.metadata.derived` → `transaction.derivedFields`, idempotently, reporting (not
guessing) when both an old and new spelling are present with different values. `applyTo` →
`afterAction` is NOT migrated automatically — `afterAction` needs a real procedure written to
receive `{draft, result}`, which is an authoring decision, not a mechanical rewrite. See
`NPDevCli/dsl_v2_migration.py`'s `_migrate_autopanel`.

**Migrated in this change:** `NPDevSamples/dsl-conformance-max` (its only `transaction.metadata
.derived` witness); no other corpus model declared `recompute` or `derived` before this.

## 2026-07-28 — Aggregate transactional boundary enforced: a flow may not write two aggregates

A flow whose `createConcept`/`updateConcept`/`createEntity`/`updateEntity` steps write to concepts
owned by two DIFFERENT declared `aggregates[]` now fails semantic validation (previously silent —
`aggregates` carried `ownership` but nothing enforced it, so the construct was descriptive, not
load-bearing). DDD's core rule: one aggregate = one transaction = one consistency boundary. A
`referenced` (not `owned`) collection is unaffected — that is a normal cross-aggregate pointer, not
a boundary the rule cares about.

**Why:** docs/NEXT_EXECUTION_PLAN.md P6.1 (3.7). Cheap to enforce once written, and the exact class
of "the model says one thing, the runtime does another" gap this repo's own register keeps finding
(REG-52/53-shaped).

**No codemod, deliberately:** unlike a syntax/vocabulary rename, there is nothing safe to
mechanically rewrite here — splitting a boundary-crossing flow into two flows coordinated by a
domain event is a real design decision only the model's author can make correctly (same "refuse
rather than guess" posture as B1's no-automatic-rename-inference boundary in
`docs/ACCEPTED_BOUNDARIES.md`).

**Corpus impact, checked not assumed:** 0 — every git-tracked model in this repo (`NPDevSamples/**`,
`NPDevContract/dsl/resources/Models/**`, `NPDevGenerator/resources/Models/**`, test fixtures, full
`:dsl:test`/`:generator:test`/`:generator:behaviorTest` suites) and WmsOffice's real, currently
deployed model (`AppGen/apps/_official/WmsOffice`, validated directly via
`:NPDevContract:dsl:validateModel`) all pass with zero aggregate-boundary diagnostics. If your own
model trips this and needs a real fix: split the flow at the aggregate boundary, using an
`emitEvent` step in the first flow and an `orchestrationRules` trigger to start the second.

## 2026-07-27 — DSL 2.0: flowStep vocabulary narrowed to 12 canonical names

**"DSL 2.0" names this vocabulary-narrowing milestone, not a value you write anywhere.** It is
unrelated to the `dslVersion` field every model declares (`ModelAst.DEFAULT_DSL_VERSION`), which
stays `"1.0.0"` and means *model-format* version -- `dslVersion` has never changed and this change
does not bump it. Writing `"dslVersion": "2.0.0"` in a model is a mistake, not an upgrade; the
schema rejects it (`/dslVersion const: must be equal to constant`, or -- with `npdev validate
model`'s default semantic check -- the clearer `Unsupported dslVersion '2.0.0'. Supported value:
"1.0.0".`). If you're migrating a model to the new flowStep vocabulary, run `npdev migrate dsl-2`
below; don't touch `dslVersion`.

`model.schema.json`'s `flowStep.type` enum dropped from 23 accepted spellings to 12
(`invariantCheck`, `capabilityCall`, `generatedAction`, `emitEvent`, `scheduleEvent`, `return`,
`branch`, `awaitEvent`, `createConcept`, `updateConcept`, `map`, `forEach` — the camelCase of the
`FlowStepDefinition.Type` runtime enum, so a reader who sees a name in JSON needs no translation
table to find it in Java). Retired spellings: `validate`/`invariant`/`enforceInvariants`/
`evaluateInvariant`, `capability`/`callCapability`, `event`, `if`, `await`/`waitForEvent`/
`await_event`, `assign`, `loop`, `generated_action`, `createEntity`/`conceptCreate`,
`updateEntity`/`conceptUpdate`. Field aliases `cap`/`op`/`out`/`at`/`target` (on `flowHook`)/
`targetConcept`/`capabilityName`/`eventName`/`fieldMap` are also retired in favor of their longer,
unambiguous names; `orchestrationRule`'s scalar `action` is retired in favor of the always-a-list
`actions`.

**Why:** the alias vocabulary was 61% redundant relative to the 9 real runtime behaviors, and every
extra spelling was a way for an LLM authoring a model to produce an inconsistent one — the single
largest source of avoidable model variance in the AI-authoring path. Full rationale, corpus
measurements, and the naming decision: `docs/DSL2_AND_DECOMPOSITION_PLAN.md` §2.A.

**Codemod:** `npdev migrate dsl-2 --input <path...> [--write]` (dry-run by default). Structural,
idempotent, and refuses to touch anything it detects as a serialized compiled-model fixture rather
than an authored document. See `NPDevCli/dsl_v2_migration.py`'s module docstring for the full
design.

**Migrated in this change:** every git-tracked model in this repo (`NPDevSamples/**`,
`NPDevContract/dsl/resources/Models/**`, `NPDevGenerator/resources/Models/**`, test fixtures).
**Not yet migrated:** `AppGen/apps/**` — a non-git external directory, deliberately excluded from
this pass; run the same codemod there whenever that's reviewed directly.
