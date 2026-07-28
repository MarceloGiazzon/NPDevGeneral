# Breaking changes

NPDev is pre-1.0 and deliberately unstable — see the "Stability policy" section in `README.md` for
why. Every breaking change to the model DSL, generated code layout, or internal APIs gets a
one-line entry here, in the same commit that makes the change, alongside the `npdev migrate`
codemod that rewrites existing models automatically.

## 2026-07-27 — DSL 2.0: flowStep vocabulary narrowed to 12 canonical names

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
