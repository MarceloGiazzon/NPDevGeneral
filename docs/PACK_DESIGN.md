# Designing a Pack

A pack is a self-contained, reusable domain module — concepts, capabilities, panels, queries, flows,
whatever a domain needs — composed into an app's model via `packs: [{ "$ref": "packs/<id>/pack.json" }]`,
the same way `NPDevSamples/pack9-role-binding-a` and `-b` both import the identical in-git `labeling`
pack. This doc covers the three design decisions that determine whether a pack stays reusable or
quietly forks: what to expose, how to wire behavior, and how a consumer should adapt it.

## Public surface vs `private` vs `extensionPoints`

Every member a pack declares (concept, capability, procedure, event, domain type, …) is **public by
default** — the pre-`private` behavior, still the default today. A pack narrows that surface with two
fields:

```json
{
  "pack": "identity",
  "concepts": [
    { "name": "User", "fields": [ { "name": "code", "domainType": "InternalCode" } ] }
  ],
  "domainTypes": [ { "name": "InternalCode", "baseType": "string" } ],
  "private": ["InternalCode"],
  "extensionPoints": []
}
```

- **`private`** — bare member names that exist for this pack's own internal use only. A reference to
  `identity::InternalCode` from *outside* the declaring pack — another pack, a bounded context, or the
  composing app's own root model — refuses composition, naming the pack and the member
  (`PACK_VISIBILITY`, enforced by `ModelSourceResolver` at the same choke point that qualifies every
  cross-pack reference). The pack's own members may still reference each other freely regardless of
  privacy; visibility only gates access from outside.
- **`extensionPoints`** — normally a subset of `private`: names a consumer *may* specialize (via
  `specializes`, see below) despite being private. This is narrower than public membership — an
  extension point still cannot be referenced directly as a domain type, a query concept, or a flow's
  scope, only named as the base of a specialization.

Concretely: specializing a private concept that is *not* listed in `extensionPoints` refuses composition
the same way a bare reference does; declaring it as an extension point makes that one specific
specialization succeed while every other form of outside access still refuses. See
`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/PackVisibilityTest.java` for the full set of
proven cases (bare reference, explicitly-qualified reference, cross-pack reference, and both
specialization outcomes) — it is the executable spec for this section, run it before assuming a new
edge case behaves one way or the other.

**When to use which:** default (everything public) for a pack whose whole point is to be built upon
directly. Reach for `private` the moment a pack has internal plumbing — a domain type, a helper
concept, an implementation-detail capability — that consumers should never name, because a public
member is a promise you now have to keep across every future version. Add `extensionPoints` only for
the specific private members you have deliberately designed to be specialized (see the next section)
— it is an opt-in per member, not a way to make `private` optional.

## Capability contracts vs implementations

A `capability` is the **contract**: a name, a type, and the operations it offers. A `binding` is the
**implementation**: which concrete adapter satisfies that contract for this app. They are declared
separately and wired by name, exactly like an interface and its injected implementation:

```json
{
  "capabilities": [
    { "name": "persistence", "type": "PersistenceCapability", "operations": ["save", "findById"] },
    { "name": "notification", "type": "NotificationCapability", "operations": ["send"] }
  ],
  "bindings": [
    { "capability": "persistence", "adapter": "repository" },
    { "capability": "notification", "adapter": "notification-inproc" }
  ]
}
```

(real, live example: `NPDevSamples/dsl-conformance-max/Input/model.json`.)

A pack should normally declare **capabilities, not bindings** — a pack that ships its own binding has
baked in one specific adapter (e.g. always `notification-inproc`) for every app that imports it, which
defeats the point of separating the contract from the implementation. Let the composing app supply the
binding via `requires.capabilities` (what the pack needs from its host) and its own `bindings` array;
the kernel's `CapabilityDispatcher` resolves the binding at runtime, so swapping an adapter — in-proc
for dev, a real queue in prod — never touches the pack or the app model, only the binding.

An operation's `input`/`output` can point at a `conceptRef`/`schemaRef` instead of an inline schema —
prefer that when the payload shape already exists as a concept, so the two never drift independently.
`policy` on an operation is purely resilience (retry, timeout, circuit breaker, bulkhead, idempotency)
— it says nothing about what the operation can fail with or whether it writes anything; that gap is
tracked separately as W4.1 in the roadmap, not something a pack author can express yet.

## When to specialize instead of forking

**Forking is the failure mode this whole document exists to prevent.**
`scripts/quality/check-pack-shared-not-forked.py` is the gate that catches it: it hashes every pack
consumed by more than one app under the same `(name, version)` and fails the moment two copies of the
"same" pack disagree — content copied and hand-edited at the point of use instead of changed once at
the pack's own source and re-versioned. If you find yourself about to copy a pack directory to tweak
one field, stop — one of these three mechanisms almost certainly says what you actually want, and all
three leave the base pack byte-for-byte reusable elsewhere:

| Need | Mechanism | Where it lives |
|---|---|---|
| A new type that behaves like an existing one, with extra fields/behavior, wherever the base is legitimately open to it | **`specializes`** (element-level: concept, capability, event, …) | Consuming app or pack, gated by `extensionPoints` if the base member is `private` |
| Add fields to an existing concept **in place** — every existing consumer of the base concept sees the new field on the same table, no second copy | **`extends`** (pack-level: `{"pack": "...", "concept": "..."}`) | A separate extension pack; composed additively by `PackExtensionComposer` |
| Attach new, optional data to an existing concept **without touching it at all** — a 1:1 FK-linked side table | **`satelliteOf`** (concept-level: a pack-qualified base reference) | A satellite concept in the consuming pack/app, enforced by `SemanticValidator` |

Some specifics worth knowing before you pick one:

- **`extends` is additive-only.** `PackExtensionComposer` patches new fields onto the base concept's
  own compiled shape (so every existing consumer sees the field on the same entity), but it refuses a
  field-shape collision rather than silently overwriting, and it refuses outright to extend any pack
  `PackSealednessAnalyzer` certifies sealed — a sealed pack ships as compiled bytecode with no JSON
  shape left to patch, and that is a property of the pack's *content*, not of whether this particular
  build happened to seal it.
- **`satelliteOf` never touches the base pack.** The base stays exactly as published; the satellite
  concept carries its own unique+required `type: reference` field pointing at the base as the 1:1
  anchor. Reach for this when the base concept can't or shouldn't change at all — e.g. it's sealed, or
  owned by a team you don't control — and what you need is genuinely separate data hanging off it, not
  a variant of the thing itself.
- **`specializes` is the right call when the result is conceptually a kind-of the base** — a variant
  type consumers will use *in place of* (or alongside) the base, not bolted-on data. This is also the
  one path a pack author can deliberately close off (leave the member out of `extensionPoints`, or out
  of `private` entirely if it should never be specialized) or open up narrowly (extension-point exactly
  the members designed for it, nothing else).
- `check-pack-shared-not-forked.py --strict` additionally requires at least one genuine SHARED witness
  in the corpus — a reuse gate with zero live reuse to point at is checking nothing. The current
  witnesses are the `labeling` pack (`dsl-conformance-max` + both `pack9-role-binding-*` apps) and
  `NPDevContract/packs/identity` mirrored into `NPDevSamples/probes/p6-satellite-extension`.

## See also

- [`docs/MANAGER.md`](MANAGER.md) § 9 · Packs — the Manager's Packs tab: what's resolved and locked
  for one app, signature/deprecation state, `pack why`, export-a-copy.
- `npdev pack --help` — `add` / `update` / `list` / `why` / `search` / `build-catalog` / `export` /
  `diff` / `publish` / `sign-keygen` / `verify`.
- `NPDevContract/schemas/pack.schema.json` — the authoritative field list (mirrored in three more
  places; see this repo's `CLAUDE.md` "model.schema.json is duplicated in 4 places" note — `pack.schema.json`
  itself has only the two copies checked by `check-schema-mirror-consistency.py`).
