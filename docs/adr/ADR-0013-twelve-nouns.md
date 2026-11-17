# ADR-0013 Twelve nouns, one meaning each

## Status

**ACCEPTED — 2026-11-15.** Written in P2 of the FORM plan; the owner said yes at the P2-T6 review ("Go on") with the proposed
defaults: transaction rule `steps` | `single`, behaviour construct stays `flows`, strict expressions on writes. The P3 merges may start. NPDev has no users and no data to
preserve (CLAUDE.md "Stability policy"), so every change this ADR implies is made directly in this repo's
own corpus; none needs an `npdev migrate` codemod.

## Context

A model has 40 top-level keys. Two step languages (flows and procedures) carry synonyms for the same
step; five screen constructs (`panels`, `autoPanels`, `guidePages`, `documents`, `selectors`) overlap;
three mechanisms split a model across files (`fragments`, `contexts`, `packs`); and four keys are dead
or aliases. An author, human or AI, has to learn all of it before writing a correct model, and a weaker
model guesses the wrong spelling. The measurement behind this is in the concept map.

## Decision

1. The language has **twelve nouns**: Concept, Query, Flow, Event, Function, Capability, Role,
   Experience, Screen, Pack, Module, Property.
2. Every top-level key of `model.schema.json` is a noun or a **part** of one, and has a recorded fate
   (KEEP, KEEP_AS_PART, MERGE, MOVE, RENAME, REMOVE) in `scripts/policy/concept-map.json`, with the
   evidence that supports it.
3. Fates decided from evidence in P2:
   - `aggregates` merge into `concepts[].composition` and a Screen `workbench`.
   - `ruleProfiles` is removed. The names select nothing today (all invariants always run; the names only
     reach the trace). The owner confirmed no conditional-invariant feature is wanted.
   - `model` is removed (an alias of `namespace`).
   - `settings` is folded into `experiences[].locale` and `experiences[].strings`; its two unread
     sub-keys are dropped. Decided by the owner.
4. Aliases live **only** in the desire-path normalizer (`NPDevCli/npdev_desire_paths.py`), are rewritten
   before validation and never appear in the corpus.

## Consequences

- The merges happen in P3 (behaviour: flows absorb procedures and orchestration rules) and P5 (screens
  and experiences), each as one commit that moves the corpus with it and adds a `BREAKING.md` line.
- `dslVersion` becomes 3.0.0 when the first merge lands.
- A procedure converted to a flow changes nothing it did: procedures and flows already share the same
  per-step atomicity (see ADR-0014).

## Enforced by

`scripts/quality/check-concept-map.py`, run by `run-ai-knowledge-gate.ps1` and registered in
`scripts/quality/verification-cadence.json` as `check-concept-map` (core level): it fails when a schema key
has no fate, when the map names a key the schema lacks, or when a fate points at no noun. Run by hand with
`--after-phase P3` (or `P5`) at the end of that phase, it also fails while a key whose fate is
MERGE/REMOVE/MOVE/RENAME is still in the schema, which is how a phase proves it removed what it said it
would. The alias-in-corpus check is added with the first P3 merge and is not in place yet.
