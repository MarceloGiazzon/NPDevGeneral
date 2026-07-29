# ADR-0010 Panel Provenance Manifests

## Status
Accepted - 2026-07-28

## Context

WmsOffice measures **model JSON 259,503 B vs hand-written HTML/JS/Java 257,659 B** — a 0.99 ratio.
32 concepts declared, and 13 hand-written screens (`inventario.html` 28 KB, `centro-trabalho.html`
31 KB, …). Those files are opaque text to the platform: nothing records that `inventario.html` reads
`Inventario.quantidade`, or that it invokes `flow:GerarTemplateInventario`.

Today, a rename silently breaks a hand-written screen at runtime, found by an operator, not a build:

```
rename Inventario.quantidade -> quantidadeContada
  SemanticValidator          passes
  migration planner          perfect ALTER, data preserved
  entity / REST / authz      regenerated correctly
  inventario.html            silently broken -- found by a warehouse operator
```

The escape hatch (a hand-written screen) sits **outside the truth model** the rest of the platform
enforces. That is the actual gap this ADR closes — not "you must write HTML," but "a screen must
declare what it depends on, so the model can tell it when that dependency changes."

## Decision

Every screen — generated, AI-authored, or hand-written — may carry a `<name>.panel.json` provenance
manifest (`schemas/panel-provenance.schema.json`, `schemaVersion: npdev-panel-provenance.v1`)
recording what it `reads`, `writes`, and `invokes` against the UI contract
(`docs/UI_CONTRACT.md`, F2). **One manifest shape, three producers:**

| Producer | How | Confirmed by construction? |
|---|---|---|
| **generator** | `CompiledMetadataCanonicalJson#toPanelProvenance`, derived from `AutoPanelExpander`'s already-stamped `metadata.generatedBy`/`concept` plus the panel's own compiled fields/actions | Yes — the compiler cannot lie about what it wired |
| **agent** | Required second output of `docs/ai/UI_GENERATION_PROMPT.md` (F2.3) | Yes — the agent declares what it used, it does not guess |
| **human** | `scripts/quality/bootstrap-panel-provenance.py` infers a `confirmed: false` draft from grepping the screen source against a real bundle; a human reviews and flips it to `true` | No, until reviewed |

### `confirmed: false` is the key design choice for the human producer

An inferred manifest is a **hypothesis**, not a fact. A future impact gate (F4,
`check-panel-provenance-impact.py`) enforces only `confirmed: true` manifests — an unconfirmed one is
*reported*, not build-failed. This is deliberately one notch softer than REG-51's refuse-vs-warn
calibration, because the input here is inferred, not declared. The alternative — trusting an inferred
manifest outright — would fail builds for the WRONG reason (a bad inference, not a real break),
which erodes trust in the gate faster than having no gate at all.

**Measured, not assumed:** running the bootstrapper against three real WmsOffice screens
(`inventario.html`, `crossdocking.html`, `centro-trabalho.html`) surfaced a real false-positive class
worth naming — bare `name`/`label` HTML tokens (`<meta name="viewport">`, `<label>` tags) matched the
platform's own `identity::Role.name`/`workspace::Menu.label` field paths by coincidence, since the
field-index only drops ambiguous *concept* collisions, not collisions with generic HTML vocabulary.
A human reviewer removed both from all three drafts. Separately, `crossdocking.html`'s
`dataAtivacao` field is spread directly into a flow's request payload as a bare object literal
(`{...xd, situacao: 'Concluido'}`-style calls elsewhere, and a bare-literal `apiSend(..., {
recebimentoId, ..., dataAtivacao, ... })` for the activation flow) — the heuristic's writes-detection
(keyed on an `input:`/`body:`/`payload:` labeled block) missed it, correctly classifying it as a read
instead of a write, until the human reviewer reclassified it. Both corrections are exactly what
`confirmed: false` + `_evidence` exist to catch: the script is expected to be wrong sometimes, cheaply
and safely.

### Generator producer: real work, not free

The plan that proposed this ADR assumed provenance for generated panels was "nearly free" because
`AutoPanelExpander` "already stamps `generatedBy`." That stamp exists on `CompiledPanel.metadata()`,
but `CompiledMetadataCanonicalJson#toPanelCatalog` **never serialized it** — so it never reached any
HTTP consumer before this ADR's implementation. Closing that gap, plus deriving `reads`/`writes`/
`invokes` from already-compiled data (`panelFields`, `fieldBindings().editable()`, `panel.actions()`),
is the actual "nearly free" work — it was not free until the serialization gap was found and closed.

A read-only "selection"/table surface carries its columns on `CompiledPanel.layout()` with an EMPTY
`fieldBindings()` (only the editable "form" surface populates bindings) — confirmed against
`AutoPanelExpanderTest`'s own assertions, not assumed. Deriving `reads` from bindings alone would have
silently produced an empty `reads` array for every read-only generated surface; the shipped
implementation reads from `panelFields` (the same helper the `fields` catalog key already uses) so
this bug never shipped in the first place.

`modelHash` is deliberately absent for the generator producer: `CompiledMetadataCanonicalJson` runs
at model-compile time, in `NPDevContract/dsl`, before the schema fingerprint exists — that fingerprint
is computed later, in `NPDevGenerator`, from the resolved database config
(`UserDatabaseDefinitionLoader#fingerprintInputs`). A future F4 impact gate should treat a
generator-producer manifest with no `modelHash` as always-re-derive, never stale-compared.

## Consequences

- A hand-written screen now has a place to declare its dependencies, closing the "escape hatch
  outside the truth model" gap this ADR names.
- Generated (AutoPanel/selector) panels carry confirmed provenance automatically — no author action
  needed, and no risk of a stale hand-maintained manifest for those surfaces.
- The human/agent producers depend on F2 (the UI contract bundle, `docs/UI_CONTRACT.md`) being live;
  a manifest's `reads`/`invokes` are meaningless without a real bundle to validate them against.
- `slotOf` is reserved, not populated, for [ADR-0004](ADR-0004-aggregate-workbench.md)'s L5
  `layoutSlot` — the workbench and the contract meet there once that concept ships.
- The impact gate (F4) that actually enforces `confirmed: true` manifests is separately scoped and
  not part of this ADR's implementation.
