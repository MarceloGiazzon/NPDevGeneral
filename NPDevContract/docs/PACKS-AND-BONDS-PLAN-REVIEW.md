# Pack + Field Bond Implementation Plan — Review & Observations

> **Branch:** `beta0-no-false-green-release-hardening`
> **Date:** 2026-06-14
> **Reviewed artifact:** "Pack + Field Bond Implementation Plan"
> **Purpose:** Concrete, code-verified observations to incorporate **before** executing the plan
> **Companion doc:** [PACKS-AND-BONDS-ANALYSIS.md](PACKS-AND-BONDS-ANALYSIS.md)

---

## Verdict

The plan is sound and its priorities match the real gaps in the codebase. However, code verification surfaced:

- **2 correctness blockers** that would break (or silently false-green) if work started as written — items **A1** and **A2**.
- **2 items already implemented** in the codebase — items **B1** and **B2** — which should be re-scoped from "build" to "add tests," shrinking the estimate.
- Several **refinements** (C) and **proof/sequencing** notes (D).

The only things that would actually *fail* on day one are **A1** (pack validation silently passing) and **A2** (where the pack schema physically lives). Everything in C/D is refinement.

---

## Severity Legend

| Tag | Meaning |
|---|---|
| 🔴 **BLOCKER** | Will break or produce a false green if implemented as written |
| 🟡 **RE-SCOPE** | Already implemented; convert to test-only work |
| 🟠 **REFINE** | Plan is directionally correct but needs a precise correction |
| 🔵 **PROOF** | Sequencing / end-to-end proof hardening |

---

## A. Correctness Blockers (resolve before coding)

### 🔴 A1 — Pack schema cross-file `$ref` will not resolve → silent false-green risk

This is the most important finding and directly threatens the branch's "no false green" goal.

**Evidence**
- `JsonModelSchemaValidator` loads **one self-contained schema from a string** via `factory.getSchema(schemaJson)`.
  See [JsonModelSchemaValidator.java:53-64](../dsl/src/main/java/com/npdev/dsl/v1/validation/JsonModelSchemaValidator.java#L53-L64).
- `model.schema.json` works today because it only uses internal `#/$defs/...` references.
- `pack.schema.json` uses **cross-file references**, e.g. `{ "$ref": "model.schema.json#/$defs/concept" }`.
  See [pack.schema.json:43-46](../../schemas/pack.schema.json#L43-L46).

**Why it breaks**
With the string-overload loader and a `$id` of `https://npdev.local/schema/npdev-model.schema.json`, networknt resolves the cross-file ref against that base `$id` URI and attempts to *fetch* `model.schema.json`. With no resolver mapping configured, it will either throw, or — worse — resolve to an empty / unconstrained schema, meaning **every pack validates as "valid."** That is precisely a false green.

**Required action**
- Either **(a)** configure a `JsonSchemaFactory` with a `SchemaMapper` / `SchemaLoader` that maps `https://npdev.local/schema/*` to the bundled classpath resources, **or**
- **(b)** make `pack.schema.json` fully self-contained (no cross-file `$ref`).
- **Mandatory regression test:** feed a structurally-invalid pack and assert it **FAILS**. This test is what proves the refs resolve instead of silently passing. Without it, A1 can regress invisibly.

---

### 🔴 A2 — Schema exists in 4+ copies with no sync step

**Evidence — discovered copies of `model.schema.json`:**

| Path | Role |
|---|---|
| `NPDevContract/schemas/model.schema.json` | Authoring (likely canonical) |
| `NPDevContract/schemas/authoring/model.schema.json` | Authoring variant |
| `NPDevContract/dsl/src/main/resources/schema/model.schema.json` | **Runtime** (loaded by the validator) |
| `NPDevContract/dsl/resources/Schemas/model.schema.json` | Additional copy |
| `NPDevContract/schemas/archive/model-1.0.0.schema.json` | Archived legacy |

There is **no copy/sync task in `dsl/build.gradle`** (only the `json-schema-validator` dependency line is present), so these copies are maintained by hand or by an external step.

**Why it matters**
The plan says "Add `pack.schema.json` to DSL runtime resources." Correct target is `dsl/src/main/resources/schema/pack.schema.json`. But you must also:
- **Co-locate** `pack.schema.json` next to the runtime `model.schema.json` so the sibling `$ref` from A1 can resolve from the classpath.
- Decide the **canonical source** and update **all** copies (or introduce a real sync Gradle task). Leaving the authoring copy and the runtime copy to drift is a future trap.

**Required action**
- Place `pack.schema.json` in the runtime resources dir alongside `model.schema.json`.
- Add (or document) the sync mechanism so authoring and runtime copies cannot diverge.

---

## B. Already Implemented — Re-scope as "add tests," not "build"

### 🟡 B1 — `onDelete: nullify` validation already exists

The plan does not mention it, but the validator **already** rejects:
- `nullify` on a `required` field (SET NULL cannot apply to a NOT NULL column), and
- `nullify` on a `multiple` (N:M) bond (the junction key cannot be null).

See [SemanticValidator.java:1325-1334](../dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java#L1325-L1334).

**Action:** No build work required. Confirm test coverage exists for both cases; add if missing.

---

### 🟡 B2 — `cat::Product → CatProduct / cat_products` already works

`ModelCompiler.toPascal` and `toSnakePlural` already split on `::` and produce `CatProduct` / `cat_products`.
See [ModelCompiler.java:559-587](../dsl/src/main/java/com/npdev/dsl/v1/compiler/ModelCompiler.java#L559-L587).

The plan frames this as new compiler work; it is actually **test-only**.

**The real risk is downstream uniformity.** The `::` sanitization is centralized only for `className` / `tableName`. The dangerous sites are the ones that may still read the raw `concept.getName()` (which retains `::`):
- bond FK / constraint / junction-table names
- REST routes
- `listBy<X>` and member-management method names
- event names, OpenAPI operationIds, audit resource types

The plan's test intent — "no invalid `::` in file names, class names, routes, SQL identifiers, or method names" — is exactly right. Make it a **repo-wide assertion**.

**Concrete latent bug to fix:** `BondModelSupport.safeTable()` falls back to `concept.getName().toLowerCase() + "s"` when `getTableName()` is blank.
See [BondModelSupport.java:254-264](../../NPDevGenerator/generator/src/main/java/com/npdev/generator/bonds/BondModelSupport.java#L254-L264).
For a pack concept that fallback yields the invalid identifier `cat::products`. Harden the fallback to sanitize `::`.

---

## C. Refinements to Specific Changes

### 🟠 C1 — Hash-suffix identifier algorithm: nail the arithmetic and scope

- **Reserve space for the suffix.** PostgreSQL identifier limit is **63**. An 8-hex suffix + `_` separator = 9 chars, so the truncated base must be **≤ 54** (not 60).
- **Hash the full pre-truncation identifier**, not the already-truncated string — otherwise two long names sharing the first N chars can still collide.
- **Conditional only.** Apply the suffix **only when the name exceeds the limit**, so short names stay readable (`invoices_product_id`, not `invoices_product_id_a1b2c3d4`). The threshold is the **63** limit, not the old 60.
- **Migration note:** changing the algorithm changes table/index/constraint names. This is safe **only because** bonds/packs are net-new (unstaged, beta0). State this assumption explicitly so nobody applies the same change later to an evolving, already-deployed table.

Suggested shape:
```
normalized = snake_normalize(rawName)
if length(normalized) > 63:
    identifier = normalized[:54] + "_" + sha256(rawName).hex[:8]
else:
    identifier = normalized
```

### 🟠 C2 — Prefer a Contract-level identifier util over generator + runtime duplication

The plan accepts duplicating the SQL-identifier algorithm across `BondModelSupport` (generator module) and `GeneratedCrudRuntimeSupport` (kernel module), pinned by parity tests.

Cleaner approach: the kernel must not depend on the generator, **but both depend on the Contract layer**. Put the canonical algorithm (junction-table name, anchor resolution, SQL-identifier normalization) in `NPDevContract`'s compiled package and have both call it. This **deletes** the parity-test problem instead of policing it.

If duplication is retained for now: make the parity test a **golden snapshot of actual emitted names** (a fixed expectation), not merely "two functions return equal." A drift in either side then fails against the snapshot.

### 🟠 C3 — Pack-fragment boundary contradicts absolute pack paths

The plan says pack fragments "must stay under the pack directory **and model root**." But `resolvePackPath` currently **allows absolute pack paths** — a pack can live *outside* the model root.
See [ModelSourceResolver.java:306-335](../dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java#L306-L335).

If a pack is outside the model root, its fragments cannot also be "under model root." This is an internal inconsistency. Pick one:
- **Option 1:** Constrain pack fragments to the **pack's own directory** only (drop the model-root clause). Supports shared / absolute pack libraries.
- **Option 2:** Constrain **packs themselves** to the model root too (align with fragment policy) and drop absolute-path support.

This interacts with GAP-P5 in the analysis doc — resolve it deliberately rather than leaving the asymmetry.

### 🟠 C4 — Breaking change (strict anchor) needs a repo-wide sweep

"Update fixtures that currently rely on plain `unique`" understates the blast radius. Before flipping `isConnectableAnchor` to require `unique && connectable == "anchor"`:

- Grep the **entire repo** — including `AppGen\apps`, sample apps, and generator/kernel test fixtures — for reference fields whose `via` targets a `unique`-but-not-`connectable` field.
- Any FinalApp bonding to a natural key without `connectable: "anchor"` will start failing validation.
- Land the semantics change **and all fixture updates in the same commit**, so there is no broken intermediate state.

---

## D. Proof & Sequencing

### 🔵 D1 — End-to-end proof must exercise real behavior, not "it generates"

On a no-false-green branch, "compiles" is not "works." The end-to-end proof should explicitly:
- Trigger a **real FK violation mapped to a domain error** (the `mapDataIntegrityViolation` path).
- Perform a **real N:M membership insert** against the generated junction table.
- Name the specific sample app and the concrete assertions.

### 🔵 D2 — Add the missing cross-pack bond test

The plan tests pack resolution and bond DDL separately, but not the join — which is the headline proof of the whole work package. Add one end-to-end test:

> A pack defines a `connectable` anchor → a root concept bonds `via` it → validate → generated DDL contains the FK to `cat_products(sku_id)`.

---

## Consolidated Action Checklist

| # | Tag | Action |
|---|---|---|
| A1 | 🔴 | Configure schema factory for cross-file `$ref` (or make pack schema self-contained); add invalid-pack-fails test |
| A2 | 🔴 | Place `pack.schema.json` in runtime resources beside `model.schema.json`; establish/ document schema sync |
| B1 | 🟡 | `nullify` rules already exist — confirm/add tests only |
| B2 | 🟡 | `::` naming already exists — convert to repo-wide naming assertion; fix `safeTable()` fallback |
| C1 | 🟠 | Hash-suffix: base ≤ 54, hash full name, conditional on > 63; document migration assumption |
| C2 | 🟠 | Move identifier algorithm to Contract layer (or golden-snapshot parity test) |
| C3 | 🟠 | Resolve pack-fragment vs absolute-pack-path boundary inconsistency |
| C4 | 🟠 | Repo-wide sweep for plain-`unique` anchors; update all fixtures in one commit |
| D1 | 🔵 | E2E proof must hit real FK violation + real N:M insert |
| D2 | 🔵 | Add cross-pack bond end-to-end test |

---

## Code-Verified Facts (reference)

| Claim | Status | Location |
|---|---|---|
| Validator loads a single self-contained schema from a string | Confirmed | `JsonModelSchemaValidator.java:53-64` |
| `pack.schema.json` uses cross-file `$ref` into `model.schema.json` | Confirmed | `pack.schema.json:43-46` |
| `model.schema.json` exists in 4+ locations, no build sync | Confirmed | repo-wide glob |
| `onDelete: nullify` vs required AND vs multiple already validated | Confirmed | `SemanticValidator.java:1325-1334` |
| `::` → `CatProduct` / `cat_products` already in compiler | Confirmed | `ModelCompiler.java:559-587` |
| `safeTable()` fallback uses raw `getName()` (invalid for pack concepts) | Confirmed | `BondModelSupport.java:254-264` |
| Absolute pack paths currently allowed | Confirmed | `ModelSourceResolver.java:306-335` |

---

*End of review. Incorporate A and B before execution; treat C and D as in-flight refinements.*
