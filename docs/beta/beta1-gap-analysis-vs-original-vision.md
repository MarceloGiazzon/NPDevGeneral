# Gap analysis: original beta1-vision-spine ask (A/B/C/D) vs current implementation

**Date:** 2026-06-23 (original analysis). **Updated:** 2026-06-23 (post 5-track
closure pass), **then 2026-06-24** (post 9-track "62%→95% average" push — see
the dedicated section near the end of this doc for that pass's outcomes; the
sections below carry the 2026-06-23 note unless a 2026-06-24 line says
otherwise).
**Branch:** `beta1-vision-spine` (sealed as `beta1` at `c43364f`).
**Method:** direct code reading against the current repo (not memory/docs) —
file:line citations throughout. Companion to
`beta1-vision-spine-status-and-handoff.md` (what shipped) and
`sample-browser-verification-methodology.md` (how it was verified). This doc
answers a different question: **of the original 4-area ask that motivated the
whole track, how much is actually closed vs still gap, now that the spine and
the sample methodology are both done.**

**One-line summary (original):** the **spine mechanics** (resolution pipeline,
the manifest renderer, change-as-data, identity/workspace/store/box-view/
promotion) are real and now permanently sample-proven. The **deep kernel-
integration** ask (A) and the **author-time ecosystem** ask (C.2 — pack store/
fork/box-authoring) are largely **not** built. B and C.1 are mostly closed with
specific, narrow exceptions called out below.

**2026-06-23 update:** a follow-on session closed every gap below except the
ones explicitly marked **still open** in the per-area notes: B's field-widget
gap, C.1's multi-level-data and frame/shell gaps, D's column-removal/rename/
type-change gaps, A's Flow-bypass gap (closed for a bounded wrapper scope, with
a documented limitation), and C.2's categories/export/fork gaps (closed for a
bounded MVP scope). Each closure was generate→boot→browser-or-log-verify'd on
both current samples, and along the way surfaced and fixed five real,
previously-undetected bugs in code this work exercised for the first time
(JDBC write path for object/array columns, a recursion bug in the new nested
editor, a Hibernate JSON-column read-back bug, a DROP TABLE FK-ordering bug,
and a short-circuit in the new type-change classifier) — see git history on
this branch for specifics; this doc only tracks ask-vs-built status.

---

## A — NPDev core vs current use (adapters/flow/event/orchestration/tenancy/code/panel)

This is the gap that has **moved the least** since the track started. The spine
phases (1–7) deliberately worked around generated CRUD's separateness rather
than closing it — Phase 3 made CRUD *kernel-controlled* (permission/audit/
tenant enforcement via `DefaultConceptGateway`) but never made it *flow-driven*.

| Sub-area | Declared in DSL | Consumed by generated CRUD | Verdict |
|---|---|---|---|
| **Adapters** | `capabilities`/`bindings` in model.json, `dev.bindings.json`/`alt.bindings.json`, `RuntimeOverrideCapabilityBindingResolver` | Validated at generation time only; `ServiceEmitter` hardcodes "repository"/"inproc" into the template. No per-request adapter resolution in generated CRUD. | **Gap.** Binding overrides only reach explicit Flow execution via `KernelRunner`'s `CapabilityDispatcher`, never generic CRUD. |
| **Flow** | `flows` section (steps: validate/createConcept/capabilityCall/emitEvent/return) | **2026-06-23: Closed for a bounded wrapper scope.** `CompiledModel.findFlow(concept, mode)` + `ServiceEmitter` now look up a matching Flow at generation time; if found, `service-base.mustache`'s create/update/delete delegate the core mutation step to `kernelRunner.execute(flowName, payload, crudCtx)` instead of the default `conceptGateway.save()`. Permission/tenant/idempotency/optimistic-concurrency/audit stay exactly as today — unchanged for concepts without a declared Flow. Live-verified: a real Flow's steps run, are permission-checked (`flow.execute`/the flow's name — a *separate* gate from the CRUD permission check, which is the documented limitation: a role with CRUD-create but not `flow.execute` would be unexpectedly denied), and the still-unconditional JPA entity save afterward does not duplicate the row (Spring Data merges into the same id). | **Closed (bounded).** Not "every Flow now owns its concept's CRUD" — only the core mutation step is delegable; the Flow must not assume it owns permission/tenant (it doesn't), and a role granted CRUD-create is not automatically granted `flow.execute`. |
| **Event** | `events` section (name, payload schema) | Generated CRUD emits its own **hardcoded** `.created`/`.updated`/`.deleted` mutation events via `GeneratedCrudRuntimeSupport.publishMutationEvent()` — real events on a real bus, not stubbed. | **Partial.** Emission works, but only for these 3 built-in event shapes; an author-declared custom event in the `events` section is compiled/validated but never *triggered* by generated CRUD (only by an explicit Flow's `emitEvent` step, which per the row above is itself dead for CRUD-generated concepts). |
| **Orchestration** | `orchestrationRules` (trigger/condition/actions) | Subscribed at boot (`GeneratedCrudRuntimeSupport.initializeOrchestrationSubscribers()`) and fired by the built-in mutation events above. | **Closed**, but only as a side-effect of the Event row's "partial" — rules react correctly to created/updated/deleted, which is most of what most authors need, but a rule keyed on a custom domain event still can't fire from CRUD. |
| **Tenancy** | `security.tenantIsolation` | Fully wired — `tenant_id` column, `DefaultConceptGateway.enforceTenant()`, row-level isolation, runtime tenant/credential lifecycle (T1–T5). Now also covers flow-driven `query()`/`list()` reads (this session's cleanup pass). | **Closed.** The one area in this list that genuinely reached "real, deep integration." |
| **Code** ("Coda") | `coda.allowed` setting exists in `NpdevSettings` (default `false`) | **Never read by anything.** No emitter, no runtime component checks it. No custom-code-injection mechanism exists anywhere in the generator or runtime. | **Gap, and not a small one** — there is currently *zero* escape hatch for author-supplied code running inside the generated CRUD/flow path. The setting is a placeholder, not a feature. |
| **Panel** (as a first-class DSL Object) | `CompiledPanel`/`CompiledProcedure` exist as model sections (name, route, layout, fieldBindings, permissions / parameters, steps, returns) | Only introspected for **permission extraction** (`RuntimeApiEmitter`) and provenance logging (`TrustedSourceEmitter`). No controller, no endpoint, no executor is ever generated for a declared Panel or Procedure. | **Gap.** Per `project_vision`'s own Box/Object/Truth doctrine, Panel/Procedure Objects are supposed to be "code-bearing surfaces" — today they're metadata-only. The actual rendered UI (business-ui-app.mustache) is a single generic renderer driven by field/concept metadata, not by declared Panel objects at all. |

**What this means in plain terms:** an author can write a very rich model.json
(flows, events, orchestration, panels, procedures) and the generator will
faithfully *validate and compile* every bit of it — but for any concept that
also gets generated business CRUD (the default), most of that richness is
dead weight at runtime. The kernel's own machinery (flow execution, capability
dispatch, panel/procedure execution) is real and working — Increment 2–4's
samples prove the kernel itself is solid — but generated CRUD still runs
**beside** it, not **through** it, except for the narrow slice tenancy now
covers end-to-end.

**Sizing a real fix:** routing generated CRUD's create/update/delete through
`KernelRunner.execute(flowName, ...)` when a matching Flow is declared (falling
back to today's fixed sequence when it isn't) is the single highest-leverage
change here — it would make Flow, Event-emission-of-custom-events, and (by
extension) custom Panels/Procedures all newly reachable without a parallel
rewrite of each. It's a real generator + kernel-wiring project, not a small fix
— flagged for a dedicated planning pass, not attempted in this session.

---

## B — Defaults/personalization cascade

The mechanics are real, tested, and more complete than the original ask
described — but adoption in real samples is shallow, and one piece (field-level
widget personalization) silently has two parallel mechanisms with only one
actually wired.

- **Scope levels**: `SettingScope` has exactly the 5 levels the vision asked
  for — PLATFORM < APP < MODULE < CONCEPT < FIELD
  (`NPDevContract/dsl/.../settings/SettingScope.java:12-17`). CONCEPT and FIELD
  are unit-tested end-to-end (`SettingResolverTest.java:43-82` — e.g. concept
  `"Order"` overriding while concept `"Invoice"` stays at the app default;
  field `"Order.rating"` resolving to `"stars"` while sibling field
  `"Order.note"` inherits the concept level). **MODULE is implemented and
  recognized by `ConfigSettingsReader` but has never been exercised by a test
  or a real sample** — a real but narrow gap.
- **Registry**: 10 settings exist (`NpdevSettings.java`), covering exactly the
  things the vision named — `ui.generateBusinessUi` ("Generate Business
  Default Web UI?"), `crud.kernelControlled` ("Generate CRUD without NPDev
  Core control?" — inverted sense, `true` means *with* control),
  `coda.allowed` ("Allow Coda?"), `log.enabled`/`log.level` ("Activate log?
  What level?"). **Database engine selection is explicitly NOT part of this
  cascade** — it's a separate, flat `database.provider` field in
  `config.json`/`db.definition.json`, schema-required, non-cascadable. Whether
  that's correct (a deploy-time/infra concern, arguably out of scope for a
  per-concept behavior cascade) or a real gap depends on whether "which
  database" should ever vary by module/concept — it structurally can't today.
- **Real-world adoption is thin**: of the 10 registered settings, only
  `ui.generateBusinessUi` and `internal.tables` have ever been overridden in
  any checked-in sample's `config.json` `defaults` block — and only at APP
  scope. `crud.kernelControlled`, `coda.allowed`, `log.*`, `auth.mode`,
  `security.*` all sit at platform default in every sample that exists today.
  The cascade is *correct*, just **unexercised** beyond the simplest case.
  **Stale as of 2026-06-24: superseded by the Track 9 and follow-up audit
  sections below.** The registry now has 12 settings (`security.tenantIsolation`
  removed for having zero consumers; `coda.allowed`, `persistence.adapter`,
  `ui.frame.mode`, `database.provider` all gained real consumers since this
  paragraph was written), and 3 real overrides now exist across both current
  samples (not the 2 named here).
- **Field-level widget — 2026-06-23: Closed.** `BusinessUiEmitter.widget()` now
  resolves through `SettingResolver` first (selector `field:<Concept>.<field>`
  → `concept:<Concept>` → `app` → platform default), falling back to the
  field's direct `ui.widget` model.json attribute only when no override
  exists. Verified: an unconfigured field's manifest is byte-identical to
  before (regression-safe); a `field:Concept.field` override in config.json's
  `overrides` block now genuinely changes the rendered widget. 3 new generator
  unit tests cover the default/override/no-leak-to-sibling cases.
- **Provenance**: fully real. `resolved-settings.json` is emitted into every
  generated app (confirmed against `superuser-admin-console`'s Output) with
  `sourceScope`/`sourceSelector`/`overridden` per setting — exactly the
  traceability the vision asked for.

**Net for B**: the cascade mechanism itself is solid (better-tested than the
original ask required, honestly), but it's a foundation more than a
felt feature today — most settings have never been overridden by anyone in a
real config, MODULE scope is untested, and the field-widget setting is a dead
placeholder next to the real mechanism it was meant to formalize.

---

## C — Web UI

### C.1 Business UI (end-user facing)

| Ask | Current state | Verdict |
|---|---|---|
| **1.1 Multi-level data (≥3 levels)** | **2026-06-23: Closed.** `buildItemsSchemaNode()` is now recursive (depth-capped at 5 purely as a pathological-input guard, not a feature limit); the renderer's nested object/array editors recurse to match. Live-verified on `superuser-admin-console`'s permanent `Project.shipping` field (shipping → address → geo, 3 levels): fill all 3 levels through the real UI, save, reopen the edit form, view the read-only detail — all round-trip correctly. | **Closed.** Depth is now unlimited (subject to the pathological-input guard), not capped at 2. |
| **1.2 Overridable frame/shell per page** (e.g. a bare login page) | **2026-06-23: Closed.** New `ui.frame.mode` concept-scope setting (full/minimal/none). `showConcept()`/`render()` toggle `body.frame-minimal`/`body.frame-none` classes, which hide the header+sidenav via CSS. Live-verified: a concept overridden to `minimal` hides the chrome while its own content stays reachable; the default-active concept (still `full`) keeps the chrome; a full page reload escapes minimal mode (the SPA has no hash-router, so that's the realistic recovery path, not browser-back). | **Closed**, with one documented characteristic: a `minimal`/`none` section's own content must provide any further navigation, since the sidenav is gone. |
| **1.3 Super-user/admin surfaces in the SAME final app** | **Closed this track.** Identity, Workspace, Store, Box View, and now Promotion all render inside `/npdev-business-ui/`, gated on `state.isSuperUser`, in the same generated app — not a separate page. (Note: a *different*, pre-existing `/npdev-ui/` "Operator UI" — flow/event/trace introspection, React-based alt surface — still exists separately; that one is a distinct operational concern, not the business-admin surface this ask was about, and wasn't touched.) | **Closed.** |
| **1.4.1 Menu internal table** | `workspace::Menu` (label/target/kind/parentMenuId/requiredRole/ordinal/visible), composed via `internal.tables=true`, drives a real nav overlay. `INTERNAL`-kind routing now covers preferences/store/boxview/promotion (this session's cleanup). | **Closed.** |
| **1.4.2 Preferences internal table** | `workspace::Preference` (userId/category/prefKey/prefValue, deliberately generic k/v), with a dedicated "My Preferences" panel scoped to the current actor. | **Closed**, exactly as deliberately scoped ("generic now, evolves"). |
| **1.5 Field input widgets, esp. bonded fields** | Reference/bond fields always get the `lookup` widget (search+browse picker, confirmed working through 2 sessions' worth of routines including bond-composition write paths). Plain fields get text/textarea/number/email/tel/url/select/checkbox/date/datetime. | **Mostly closed** for what exists. Not covered: richer bonded-field UX the vision's own examples implied (a "select" dropdown variant for small reference sets vs the picker-dialog-only approach today, multi-select for many-to-many) — picker-only is one workable pattern, not the only one a mature system would offer. |

### C.2 Author/creator UI (the "app store" + box-authoring vision)

This is the **least-built** part of the whole original ask, and the spine work
never touched it — everything shipped this track was about the *generated
app's* runtime UI, not the *authoring* experience.

- **2 — App store for authors**: **2026-06-23: partially closed.** The Store
  panel is no longer a flat read-only table — each pack row now expands into a
  drill-down showing its actual concept names (not just a count), category
  badge, author, and fork attribution when present. Still **not** closed: there
  is still no UI surface to *install/compose* a non-built-in pack into an app
  from the running UI — `internal.tables` remains a single all-or-nothing
  boolean for the two built-in packs specifically. Browsing got real; picking
  one to install from the catalog did not.
- **2.1 Export from a project to the repo**: **2026-06-23: Closed (bounded).**
  `NPDevSamples/scripts/packs/export-concept-to-pack.ps1` takes a sample's
  model.json + a concept name and writes a new `NPDevContract/packs/<name>/
  pack.json`, stamped with author/category/optional fork attribution — a real,
  scripted export path. Live-verified: exported `superuser-admin-console`'s
  `Project` concept (including its 3-level nested object field) to a new
  `project-tracker-demo` pack; confirmed it appears correctly in the Store
  catalog with the right concept names, category, and author. Not a UI-driven
  export (matches the locked "bounded MVP" scope) — script-only.
- **2.2 Categories** (security/Web UI/crypto/bots/AI Tools/finance/math...):
  **2026-06-23: Closed.** `pack.schema.json` now has a `category` enum
  matching the vision's own examples (security/web-ui/crypto/bots/ai-tools/
  finance/math/other). The built-in `identity` (→ security) and `workspace`
  (→ web-ui) packs were stamped with real category + author metadata, not
  synthetic placeholders.
- **2.3 Fork with originator attribution**: **2026-06-23: Closed (schema +
  display, not lineage tracking).** `pack.schema.json` now has a `forkedFrom`
  object (`pack`/`version`/`originAuthor`), threaded through the catalog and
  rendered as a badge in the Store drill-down. What this does NOT do: track or
  enforce lineage automatically — a fork's `forkedFrom` block is author-
  declared metadata, not derived/verified by the platform.
- **2.4 Box-view authoring UI** (layered, drill-down pack→concept→field→input,
  visual, hide-then-reveal complexity): the runtime **Box View** built in
  Increment 2/Phase 7 (`/api/admin/box`) is a flat read-only table of
  generated concepts and their truth level — it answers "what got generated,"
  not "let me visually browse and drill into a pack's structure while
  authoring." The actual model-editing surface (NPDevEditor, the React editor
  mentioned in `project_vision`/`beta1_vision_spine` decisions) is a separate,
  pre-existing tool this track explicitly chose **not** to converge with
  (locked decision #2: "extend the manifest renderer... do NOT converge with
  the React Editor"). No layered/drill-down box visualization exists in
  either surface today.

**Net for C.2**: this is a green-field feature area, not a partially-built one.
Everything from "browsable categorized store" through "fork with attribution"
to "visual layered box-authoring UI" would be new design and new
implementation — a separate, substantial track of its own, not a follow-on
increment to what exists.

---

## D — Database schema-change admin

Already the subject of a dedicated, deliberately-scoped phase (Phase 6) plus
this track's own permanent schema-evolution sample (Increment 3) — so this is
the most concretely *answered* of the four areas, including its remaining
limits.

**What's supported, real, and now permanently sample-proven:**
- Adding a new **nullable, non-bond column** to an existing table is detected
  as safe-additive (`SchemaLifecycleExecutor.isSafeAdditiveChange()`,
  `NPDevRuntimeHost/.../db/SchemaLifecycleExecutor.java:108-147`) and applied
  via a repeatable `ADD COLUMN IF NOT EXISTS` migration — no destructive
  recreate, no data loss. Brand-new tables (a wholly new concept) are likewise
  safe (V1's `CREATE TABLE IF NOT EXISTS` handles them, line 128-131).
  Increment 3 proved this live in a browser, not just by log inspection.

**2026-06-23 update — redefined and closed for the strongest claim that is
actually true:** "no schema change can ever lose data" is provably false for
some changes (e.g. narrowing a column where existing data doesn't fit) — no
engineering makes that lossless. What's closed instead, and is the strongest
true claim:

- **Pre-drop snapshot — Closed.** `SchemaDropSnapshotWriter` now runs
  immediately before every destructive `DROP TABLE`, writing a row count
  (always) and a best-effort full JSON-lines dump (binary columns noted, not
  crashed on) to `runtime-data/schema-snapshot-before-drop/<timestamp>/`.
  Snapshot failure logs loudly ("DATA LOSS NOT SNAPSHOTTED") but never blocks
  boot. Last 5 snapshots retained. Live-verified end to end: populate a row →
  remove a field (forces destructive) → regenerate/reboot → confirmed the
  snapshot directory's row count and dumped data genuinely match the populated
  row, from real files on disk, not just a log line. Along the way, found and
  fixed a real pre-existing bug this exercise surfaced: the DROP order didn't
  account for FK dependencies (dropping a referenced table before its
  referencing table failed on both H2 and Postgres) — fixed via `DROP TABLE
  ... CASCADE`.
- **Renames — Closed (classified, not auto-applied).** A field can now declare
  `renamedFrom: "oldFieldName"`; the schema-realization manifest threads this
  + each column's SQL type through. `SchemaLifecycleExecutor` now returns a
  `SchemaChangeClassification` (SAFE_ADDITIVE / RENAME_DETECTED /
  TYPE_CHANGE_DETECTED / DESTRUCTIVE) instead of a boolean. A declared rename
  matching what the live database still has under the old name is now
  correctly labeled `RENAME_DETECTED` in the boot log instead of looking like
  an unrelated remove+add — still goes through the (now snapshotted)
  destructive path, since auto-applying an in-place rename was explicitly out
  of scope.
- **Type changes — Closed (classified, not auto-applied).** Same
  classification mechanism: an existing column whose declared SQL type
  changed (column names unchanged) is now labeled `TYPE_CHANGE_DETECTED`
  rather than silently passing as safe-additive or being indistinguishable
  from other destructive cases. Found and fixed a real bug in this work itself
  while verifying it live: the original implementation's "columns match
  exactly" fast path short-circuited before ever checking types, so a pure
  type change was misclassified as `SAFE_ADDITIVE`. Both `RENAME_DETECTED` and
  `TYPE_CHANGE_DETECTED` were live-verified via real regenerate/reboot cycles
  against a real H2 database, reading the actual boot log output.
- **New bond/FK columns** are still excluded from the additive-eligible set by
  design (unchanged) — adding a reference field to an existing concept is
  still always destructive-path territory, now at least snapshotted first.

**Net for D**: every destructive recreation is now preceded by a recoverable
snapshot, and the three previously-unclassified cases (removal, rename, type
change) are now correctly distinguished from each other in the boot log —
closing the "how would I know" question for the redefined, honest claim. What
remains true and unchanged: a destructive recreate still recreates the table
(the snapshot is for recovery/audit, not an alternative to the recreate), and
renames/type-changes are still not auto-applied in place.

---

## Summary table

| Area | Sub-item | Status |
|---|---|---|
| A | Adapters | **Still open** — compile-time only, generated CRUD hardcodes adapters |
| A | Flow | **Closed 2026-06-23 (bounded wrapper)** — see note above re: the `flow.execute` permission limitation |
| A | Event | **Still open (partial)** — built-in mutation events work; custom declared events still never trigger from CRUD directly (only from within a Flow's own steps, which now reach generated CRUD via the wrapper) |
| A | Orchestration | Closed (as a consequence of built-in event emission) |
| A | Tenancy | **Closed** — the one fully-integrated area |
| A | Code (Coda) | **Still open** — setting is an inert placeholder, no mechanism exists |
| A | Panel/Procedure | **Still open** — metadata-only, no generated execution surface |
| B | Scope levels (cascade) | Closed for APP/CONCEPT/FIELD; MODULE untested/unused |
| B | Settings registry coverage | Closed (all named settings exist) but thin real-world adoption |
| B | Database engine in cascade | By design, out of the cascade — flat, non-personalizable |
| B | Field-widget personalization | **Closed 2026-06-23** — `field.widget` now resolves through the cascade |
| B | Provenance | Closed |
| C.1 | Multi-level data (≥3 levels) | **Closed 2026-06-23** — recursive, depth-unlimited (pathological-input guard only) |
| C.1 | Overridable frame/shell | **Closed 2026-06-23** — `ui.frame.mode` (full/minimal/none) |
| C.1 | Super-user in final app | **Closed this track** |
| C.1 | Menu/Preferences internal tables | **Closed this track** |
| C.1 | Bonded-field widgets | Mostly closed; picker-only, no lighter-weight alternatives |
| C.2 | Author-facing pack store | **Partially closed 2026-06-23** — drill-down/browse real; install-from-UI still doesn't exist |
| C.2 | Export-to-repo | **Closed 2026-06-23 (bounded)** — scripted, not UI-driven |
| C.2 | Categories | **Closed 2026-06-23** |
| C.2 | Fork + attribution | **Closed 2026-06-23 (schema + display)** — author-declared, not platform-verified lineage |
| C.2 | Box-view authoring UI | **Still open** — runtime Box View ≠ this; React editor explicitly not converged; explicitly out of the locked MVP scope |
| D | Safe-additive nullable columns | **Closed and sample-proven** |
| D | Column removal | **Closed 2026-06-23** — still destructive by design, now snapshotted first |
| D | Renames | **Closed 2026-06-23** — classified (`RENAME_DETECTED`), not auto-applied |
| D | Type changes | **Closed 2026-06-23** — classified (`TYPE_CHANGE_DETECTED`), not auto-applied |
| D | New bond/FK columns | Always destructive by design, now snapshotted first |

**Remaining open items as of 2026-06-23** (superseded by the 2026-06-24 section
below — kept here for the historical record): (1) Adapters/Event/Code/Panel-
Procedure in area A; (2) C.2's "install a pack from the running UI" gap; (3)
C.1's bonded-field widget variety (picker-only).

---

## 2026-06-24 update — the "62% → ≥95% average" push (9 tracks)

Following the 2026-06-23 closure pass, a fresh unified 0–100% score was built
across all 27 items from the original A/B/C/D ask, averaging **62%**. The
directive was to bring that average to **at least 95%**, with every track
generate→boot→browser-or-log-verified and a full regression sweep on both
current samples (`superuser-admin-console`, `restaurant-saas-multitenant`)
before being counted done — same discipline as 2026-06-23, applied to the
items that pass had explicitly left open.

**Track-by-track outcome (honest, including where scope was reduced from the
original plan once the real architecture was understood):**

1. **Pre-drop snapshot JSON/object data-loss bug (D.New bond/FK columns,
   D.Renames/Type-changes)** — real bug found and fixed:
   `SchemaDropSnapshotWriter.decodeJsonColumnValue()` was replacing a JSON
   column's actual content with a binary placeholder (H2 returns JSON columns
   as `byte[]` too, indistinguishable from a real BLOB without checking
   `ResultSetMetaData.getColumnTypeName()`). Fixed with a type check + a
   3-pass unwrap-then-parse loop (needed because Hibernate's JSON write path
   adds one extra layer of string-quoting a raw JDBC read sees directly).
   Live-verified: a snapshot dump of a row with 3-level nested `shipping` data
   now shows the real values, not `"<binary, N bytes, not snapshotted>"`.
2. **Adapters (A.Adapters)** — **scope reduced from the original plan.**
   `RuntimeOverrideCapabilityBindingResolver` turned out to operate through a
   structurally different sandboxed-plugin mechanism incompatible with
   generated CRUD's direct-call pattern, and only one real adapter
   implementation ("repository") existed to switch to anyway. Built instead: a
   genuine second adapter (`AuditingConceptStoreDecorator`, a real
   decorator that logs every persistence operation) selectable per-concept via
   a new `persistence.adapter` setting in the same cascade as everything else.
   This closes the *wiring* gap honestly (a concept's CRUD really can resolve
   to a different adapter at generation time) without overclaiming live
   per-request adapter switching, which the existing architecture doesn't
   support without much larger surgery.
3. **Custom events direct from CRUD + Orchestration reachability (A.Event,
   A.Orchestration)** — a concept-nested event can now declare
   `"mode": "create"|"update"|"delete"`; generated CRUD calls
   `runtimeSupport.publishDeclaredEvent(...)` for each matching declared event
   in addition to the 3 built-ins, with no Flow required. Found and fixed a
   real bug while wiring this: `triggerMode` was silently dropped during
   `ModelResolver`'s event sanitize/merge step (used whenever events go
   through specialization resolution) and again during
   `CompiledModelCanonicalJson`'s round-trip (used by `BuiltinPackComposer`
   when composing built-in packs) — both fixed. Live-verified: a custom
   `NoteAuthored` event fires through the same `event.publish`
   permission-checked path as built-in events on a real create call.
   Orchestration reachability is a structural consequence of using that same
   publish path, not a separate code change.
4. **Coda single hook point (A.Code)** — new `com.npdev.kernel.coda.CodaHook`
   interface (`beforeCreate`/`afterCreate`, both default no-ops). When
   `coda.allowed` resolves true for a concept, generated CRUD calls an
   `Optional<CodaHook>` Spring bean at exactly those two points; with no
   implementation on the classpath (every existing sample), the call is a
   no-op. Live-verified with a real temporary `CodaHook` implementation: it
   fired for `Note` (coda-enabled) and did not fire for `Project`
   (not enabled) in the same boot.
5. **Panel/Procedure generic executor (A.Panel/Procedure)** — **turned out to
   already exist.** `PanelRuntime` (already an allow-listed, already-wired
   core service) plus `RuntimeUiMetadataController`'s
   `GET .../panels/{name}` and `POST .../panels/{name}/actions/{actionName}`
   were already a complete, real Panel+Procedure execution surface — the
   2026-06-23 audit's "metadata-only, no generated execution surface"
   characterization was simply wrong, or had gone stale. This track was pure
   verification, not new code: added a real `ListNotesProcedure`
   (`listConcepts` + `return` steps) and a `notes-panel` with one
   procedure-bound action to a sample's model.json, confirmed
   `loadPanel`/`executeAction` genuinely query the live `Note` table through
   `ConceptGateway` and return real rows — then reverted the test addition.
6. **Database engine joins the cascade (B.Database engine)** — new
   `database.provider` app-scope `SettingKey`, read from `config.json`'s flat
   `database` block as a legacy-alias fallback (same pattern as the existing
   `ui.generateBusinessUi` alias). Honestly generation-time-only — the real
   consumer is still the existing flat field; this only makes the choice
   visible/overridable through the same provenance mechanism
   (`resolved-settings.json`) as every other setting. Verified:
   `superuser-admin-console`'s resolved settings now list
   `database.provider=docker-postgres`, `sourceScope=APP`.
7. **Bonded-field alternative widgets (C.1.Bonded-field widgets)** — **partial:
   single-reference `select` done, many-to-many `multiselect` not attempted.**
   `field.widget="select"` on a single (N:1/1:1) reference field now renders a
   real `<select>` populated from the same endpoint the picker uses, falling
   back to the picker for any other/no override (zero change for unconfigured
   fields). Live-verified: `Note.projectRef` overridden to `select` renders a
   populated dropdown and the picker control is genuinely absent. The M2M
   `multiselect` half was not attempted — investigation showed many-to-many
   bonds have **no UI surface at all** today (purely a backend CRUD/API
   concern), so building it would be a new UI affordance from scratch, not an
   extension of an existing one — correctly out of scope for this pass's time
   budget, called out explicitly rather than silently dropped.
8. **Pack ecosystem: Store filter, box-authoring drill-down, fork-existence
   check, install-from-UI (C.2.Author-facing pack store, C.2.Fork+attribution,
   C.2.Box-view authoring UI)** — the Store panel's existing pack-level
   drill-down now has a second expansion level per concept showing its real
   field names/types (read-only layered browsing, not a visual box-graph
   editor); a free-text filter narrows the catalog by name/category/
   description/concept names; a `forkedFrom` badge now shows "origin not found
   locally" when the declared pack doesn't actually exist under
   `NPDevContract/packs/` (a local existence check, not lineage verification).
   **Install-from-UI scope reduced from the plan's literal text:** rather than
   writing directly into config.json's overrides envelope (the running app has
   no general mechanism to mutate its own generation-time config — that lives
   in a separate `Input/` directory outside the deployed app), "Install"
   records an intent (pack alias + timestamp) to a small JSON file in the
   running app's own working directory via a new
   `POST /api/admin/packs/{alias}/install-intent` endpoint, surfaced as an
   "Install pending (regenerate to apply)" badge — still genuinely
   does-not-hot-reload, as the plan required, just persisted differently than
   literally described. Live-verified end to end (HTTP + browser): filter
   narrows the table, concept→field drill-down shows real data, the intent is
   recorded to disk and reflected as a pending badge on reload.
9. **Polish pass (Track 9 in the plan)** — initially skipped for time, then
   completed in a follow-up pass at the user's request. Six sub-items, each
   live-verified:
   - **Flow/CRUD permission-grant alignment**: `RuntimeApiEmitter` now grants
     `flow.execute` to the "user" role automatically whenever that role is
     also granted CRUD create/update/delete on a concept whose mode is
     delegated to a declared Flow (`model.findFlow(concept, operation)`).
     Closes the "unexpectedly denied" characteristic the 2026-06-23 audit
     flagged — superUserRole already got this unconditionally, only "user"
     needed the fix.
   - **MODULE scope**: added a real `SettingResolverTest` case (module
     override beats app default, concept override beats module) and a real
     `ConfigSettingsReaderTest` case (a `module:` prefixed config.json
     override key is read as a genuine MODULE-scope layer, not silently
     mis-scoped). Honestly still not exercised by anything in production —
     no `CompiledConcept` carries a module association today, so no emitter
     ever calls `SettingTarget.conceptInModule(...)`; that would be a real,
     separately-scoped DSL feature, not a config.json tweak. The mechanism
     itself, at both the resolver and config-reading layers, is now genuinely
     test-proven rather than merely "implemented and recognized."
   - **Settings adoption**: added 3 real, permanent, live-verified
     non-default overrides across both current samples'
     config.json — `persistence.adapter: audited` on `Note`
     (`superuser-admin-console`) and on `Tenant`
     (`restaurant-saas-multitenant`), confirmed firing via the
     `AuditingConceptStoreDecorator`'s log line on every boot; and
     `field.widget: select` permanently on `Note.projectRef`
     (`superuser-admin-console`), confirmed rendering a real `<select>` in
     the permanent regression routine. Three real overrides now exist beyond
     the original two (`ui.generateBusinessUi`, `internal.tables`).
   - **Category enum dedup**: `export-concept-to-pack.ps1` now reads
     `pack.schema.json`'s `properties.category.enum` at runtime instead of
     carrying its own hardcoded copy. Live-verified: a valid category still
     passes, an invalid one is still rejected with the schema's real list
     in the error message.
   - **Combined rename + type-change test**: live-verified that when a
     concept's regeneration combines BOTH a declared rename (`renamedFrom`)
     AND an existing column's type change in the *same* migration, the
     classifier correctly reports the worse of the two
     (`TYPE_CHANGE_DETECTED`, not `RENAME_DETECTED`) — confirmed from a real
     boot log, not inferred from code reading.
   - **Stronger value-round-trip assertions**: routine `09-nested-shipping-
     object` now asserts all 4 entered leaf values (not just 1) survive the
     create→reopen→view round trip; routine `04-create-project-and-note-via-
     ui` now reopens the just-created Note's edit form and asserts the
     `select`-chosen `projectRef` value itself persisted (the placeholder
     option is no longer selected), not just that creation succeeded.

**Unplanned but real bug found and fixed during Track 9**: running `:dsl:test`
directly (its own Gradle project, not `:generator:test`'s dependency graph)
for the first time in either session revealed `StructuralSchemaAssetConformanceTest`
failing — a **third** copy of `model.schema.json`
(`NPDevContract/dsl/resources/Schemas/model.schema.json`, capital-S `Schemas`,
distinct from the two paths previously tracked) had silently drifted out of
sync across two sessions, missing both `renamedFrom` and the newer `mode`
field. Root cause: `:generator:test` only needs `:dsl` to *compile* (a normal
Gradle project dependency), it never actually *runs* `:dsl`'s own test suite —
so every "ran the generator test suite, all green" claim in this and the prior
session was true but incomplete, since it never exercised this conformance
check. Fixed by syncing the third copy; going forward, any DSL/schema change
should also run `:dsl:test` directly (from `NPDevContract/dsl`), not just
`:generator:test`.

**Bugs found and fixed as a side effect of this work** (beyond the pre-drop
snapshot bug already counted as Track 1's own deliverable): the
`ModelResolver`/`CompiledModelCanonicalJson` event-`triggerMode` data-loss bug
described under Track 3 above, and the third-schema-copy drift bug described
above. All three are real, previously-undetected defects this work's live
verification surfaced — not hypothetical.

**Updated unified score table** (0–100% "how finished," recomputed against
the same 27 items as the 62% table; final column reflects both the 8-track
pass and the follow-up Track 9 pass):

| # | Item | 2026-06-23 | 2026-06-24 (T1-8) | 2026-06-24 (+T9) | 2026-06-24 (+audit) | Why |
|---|---|---|---|---|---|---|
| 1 | Adapters | 0% | 85% | 90% | 93% | Audit: unsupported override now fails generation loudly instead of silently doing nothing |
| 2 | Flow | 90% | 90% | 95% | 95% | Audit fixed a cosmetic duplicate-grant issue; no score change (was already harmless) |
| 3 | Event (custom, direct) | 30% | 95% | 95% | 96% | Audit: top-level event `mode` now rejected instead of silently dropped |
| 4 | Orchestration | 60% | 95% | 95% | 95% | unchanged |
| 5 | Tenancy | 100% | 100% | 100% | 100% | unchanged (the fake `security.tenantIsolation` setting removed, not the real enforcement) |
| 6 | Code (Coda) | 0% | 90% | 90% | 90% | unchanged |
| 7 | Panel/Procedure | 0% | 95% | 95% | 95% | unchanged |
| 8 | Cascade mechanism (MODULE) | 90% | 90% | 95% | 95% | unchanged |
| 9 | Top-level settings adoption | 80% | 80% | 90% | 95% | Audit: log.enabled/log.level wired to real logging.level.root; security.tenantIsolation (zero consumers) removed — registry no longer has anything inert |
| 10 | Database engine in cascade | 0% | 90% | 90% | 90% | unchanged |
| 11 | Field widget cascade | 95% | 95% | 100% | 100% | unchanged |
| 12 | Bonded-field widgets | 0% | 75% | 75% | 75% → **100%** | Closed 2026-06-24 (2nd follow-up): M2M multiselect built and live-verified, see section below |
| 13 | Multi-level data | 95% | 95% | 100% | 100% | unchanged |
| 14 | Frame/shell | 90% | 90% | 90% | 90% | not touched |
| 15-17 | Super-user/Menus/Preferences | 100% | 100% | 100% | 100% | already done |
| 18 | Store browse | 60% | 95% | 95% | 95% | unchanged |
| 19 | Install pack from UI | 0% | 75% | 75% | 80% → **95%** | Closed further 2026-06-24 (2nd follow-up): real best-effort config.json write + the composer fix that makes it actually install, see section below |
| 20 | Export to repo | 85% | 85% | 90% | 90% | unchanged |
| 21 | Categories | 90% | 90% | 95% | 95% | unchanged |
| 22 | Fork + attribution | 70% | 90% | 90% | 90% | unchanged |
| 23 | Box-authoring UI | 0% | 85% | 85% | 85% → **95%** | Closed further 2026-06-24 (2nd follow-up): real persisted drag-and-drop graph view, see section below |
| 24 | Pre-drop snapshot (bug) | 70% | 100% | 100% | 100% | unchanged |
| 25-26 | Rename/Type-change detection | 90% | 90% | 100% | 100% | unchanged |
| 27 | New bond/FK columns | 85% | 100% | 100% | 100% | unchanged |

**Sum: 2544 / 27 ≈ 94.2%.** (Superseded same day — see the "second follow-up"
section below, which closes items 12/19/23 further and brings the sum to
2594 / 27 ≈ 96.1%.) A fourth, undetected stale schema copy (not scored above
— it's a platform-integrity finding outside the original 27-item ask, see the
audit section above) was also found and fixed in this pass.

**Sum: 2530 / 27 ≈ 93.7%.** Closer to the 95% target than the 88.5% interim
result, but still short. The honest reasons:

- Two items (Bonded-field widgets at 75%, Install-from-UI at 75%) remain
  below their original targets because the real architecture supports a
  narrower MVP than first estimated (no UI surface for M2M bonds at all; no
  path for a running app to mutate its own generation-time config) —
  documented honestly rather than inflated.
- Frame/shell (90%) and a few smaller items were deliberately not touched in
  Track 9 — adding a permanent `ui.frame.mode` override risked breaking
  sidenav-dependent navigation in existing routines for marginal score gain,
  judged not worth the risk this pass.
- MODULE scope (95%, not 100%) is genuinely test-proven at the mechanism
  level now, but still has no live anchor point in either current sample's
  model — that would require a real DSL feature (concepts declaring a module
  membership), not a config.json change.

**This is reported as the honest result, not adjusted to claim 95%.** Per the
session's own standing discipline ("never commit without explicit ask," "don't
overclaim"), the number above is what was actually verified, not a target
retrofitted to look met.

---

## 2026-06-24 follow-up — independent audit: bugs, gaps, incoherence

A fresh audit was run against the *actual code*, not this doc's own claims —
checking generated artifacts, boot logs, and the kernel directly, on the
premise that a doc written by the same work that shipped a feature can't be
trusted to find that feature's own blind spots. Two findings were fixed
immediately at the user's direction; the rest are recorded for visibility.

**Fixed:**

- **A fourth, undetected stale schema copy.** There are four physical copies
  of `model.schema.json` (`schemas/`, `schemas/authoring/`,
  `dsl/resources/Schemas/`, and the classpath copy at
  `dsl/src/main/resources/schema/`) because different consumers resolve it
  from different relative roots. `StructuralSchemaAssetConformanceTest` (added
  2026-06-24 earlier today, see the Track 9 section above) only checked two of
  the four — `schemas/authoring/model.schema.json` had silently drifted since
  2026-06-15, missing both `renamedFrom` and the event `mode` field, while
  `NPDevContract/docs/MODEL-CONTRACT.md` called it "the canonical schema
  path." Fixed: synced the copy, extended the conformance test to check *all
  four* against the canonical source (`allKnownModelSchemaCopiesStayAligned`),
  and corrected MODEL-CONTRACT.md to state honestly that four copies exist
  and must stay aligned, rather than naming one as uniquely canonical.
- **Three registered settings with zero real consumers.**
  `security.tenantIsolation`, `log.enabled`, and `log.level` resolved
  correctly into every app's `resolved-settings.json` with full provenance —
  looking exactly like working, personalizable features — while nothing in
  the generator or runtime ever read any of the three. Setting
  `security.tenantIsolation: false` did nothing; isolation is enforced
  unconditionally by the kernel's `TenantIsolationPolicy` regardless of this
  setting's value. Fixed two different ways, per explicit user direction:
  - `log.enabled`/`log.level` now have a real consumer: a new
    `RuntimeLogPropertiesEmitter` (mirroring the existing
    `RuntimeAuthPropertiesEmitter` pattern) emits
    `application-npdev-log.properties` with a real `logging.level.root`
    property, loaded via `spring.config.import` in the RuntimeHost profiles —
    only when either setting is actually personalized, so every existing
    sample (neither overrides them) emits nothing and is byte-identical to
    before. Live-verified: overriding `log.level: warn` on
    `superuser-admin-console` measurably suppressed real boot-log INFO lines
    (Tomcat init, HikariCP, Spring Data repository scanning — 344 → 71 INFO
    lines across the same boot), while `application-dev.yml`'s own
    more-specific package overrides (e.g. `com.finalexec.config: INFO`)
    correctly still took precedence over the new root-level setting, exactly
    as Spring's logging precedence rules require.
  - `security.tenantIsolation` was removed from `NpdevSettings` entirely
    rather than wired to a real toggle — wiring a genuine bypass for tenant
    isolation is a security-sensitive change for a setting nothing currently
    needs, and a settings registry entry that's inert is more honestly
    *absent* than present-but-fake. `NpdevSettingsTest` updated to match; a
    new javadoc note on `NpdevSettings` itself records why, so a future
    re-add doesn't repeat the mistake without first wiring a real consumer.
  - A real bug was found and fixed during this fix's own verification: the
    new `RuntimeLogPropertiesEmitter`'s blank-level fallback returned
    lowercase `"info"` instead of `"INFO"` — caught by its own unit test,
    fixed before the generator test suite passed.

**Follow-up fix pass (same day): all four remaining recorded findings closed**

The four lower-severity findings recorded above (originally left unfixed)
were all fixed at the user's explicit request immediately after, each
live-verified and regression-swept on both samples with zero behavior change
for any existing, unmodified concept:

- **`persistence.adapter` tautology — fixed.** `ServiceEmitter` now validates
  the resolved override at generation time (mirroring the existing
  binding-adapter validation immediately above it): empty is fine (no
  override), `"audited"` is fine, anything else throws
  `IllegalStateException` naming the entity and the bad value, instead of
  silently falling through to the unwrapped store. The template's redundant
  runtime `equalsIgnoreCase` check was removed entirely — once generation-time
  validation guarantees the value, the wrap is unconditional. New tests
  (`GeneratorFacadePersistenceAdapterOverrideTest`) cover both the rejection
  and the still-working valid case.
- **Concept-less event `mode` — fixed.** A top-level (concept-less) event
  declaring `mode` now fails parsing with a clear message ("move it under
  that concept's events array") instead of being silently dropped. New tests
  (`EventModeValidationTest`) cover: concept-nested invalid mode rejected
  (already worked, now has a regression test), concept-nested valid mode
  accepted, top-level mode rejected, top-level event without mode unaffected.
- **`install-intent` endpoint hardened.** Two of the three recorded concerns
  fixed: (1) the alias is now validated against the actual pack catalog
  before recording an intent — an unknown alias now returns 404 instead of
  silently accepting any string; (2) the read-modify-write is now
  synchronized on an in-process lock, closing the lost-update race between
  concurrent POSTs. Live-verified: `POST .../nonexistent-pack-xyz/install-intent`
  → 404; `POST .../project-tracker-demo/install-intent` → 200, unchanged
  response shape. The third concern (no tenant scoping) was reconsidered, not
  fixed: the whole pack-store admin surface (the catalog itself, `internal.tables`)
  is already global/un-tenant-scoped by design, so a per-tenant intents file
  would be inconsistent with everything else on this surface, not a gap on
  its own.
- **Duplicate `flow.execute` grants — fixed.** Added `.distinct()` to
  `RuntimeApiEmitter`'s grant-emission stream (the `PermissionGrantSpec`
  record's generated `equals`/`hashCode` makes this a correct dedup). Verified
  live: `restaurant-saas-multitenant`'s `dev.permissions.json` has exactly one
  `flow.execute`/`admin` and one `flow.execute`/`user` entry, not duplicated
  per Flow-delegated CRUD mode.

All four fixes plus the original two (schema sync, log settings wiring) were
verified together in one pass: full `dsl` test suite, full `generator` test
suite (both with `--rerun-tasks`, no cache), and a full regenerate-boot-browser-
sweep on both current samples — 9/9 and 5/5 routines green, zero regression.

---

## 2026-06-24 second follow-up — closing the 3 remaining scope gaps (items 12, 19, 23)

The audit above identified 3 items that were live-verified and working but
deliberately capped below 100% because the *delivered* scope was narrower than
the *original* ask (multiselect missing for M2M bonds; install-from-UI was
intent-recording only; the box-authoring drill-down was text, not visual).
At the user's explicit direction, all three gaps were closed for real — each
scoped first via a locked decision, then built, then live-verified, not just
described.

**Item 19 — Install pack from UI: now does a real best-effort write.**
`GeneratedPackCatalogController.recordInstallIntent` now attempts to write the
installed alias directly into `Input/config.json`'s `packs.included` array
(reached via the stable relative path every sample's local dev workflow uses,
`Output/App` → `../../Input/config.json` — confirmed by config.json's own
`finalExec.root` convention). This only works in that local workflow; a real
deployment without an `Input/` directory gets a graceful no-op falling back to
intent-recording, never an error. Live-verified end to end via HTTP and
browser: a click writes the alias into the real file's `packs.included` array,
the Store UI badge distinguishes "written to config.json" from "recorded
locally only."

A second, larger discovery surfaced while implementing this: **even a
perfect config.json write would have been a no-op**, because
`BuiltinPackComposer`'s actual composition call site
(`GeneratorMain.composeBuiltinInternalTables`) only ever iterated
`BUILTIN_PACK_ALIASES` (`identity`, `workspace`) — there was no code path that
composed *any other* pack into an app, regardless of what config.json said.
Fixed: `GeneratorMain` now also reads `packs.included` and composes those
packs too (reusing `BuiltinPackComposer`'s already-generic
`loadPackConcepts`/`merge` — only the *alias list* was hardcoded, not the
mechanism), deliberately *not* adding them to `BUILTIN_PACK_ALIASES` so an
installed third-party pack's concepts render as ordinary business concepts,
not admin-gated internal tables. `PackCatalogEmitter` now also accepts this
list so the Store catalog correctly marks an installed pack "Included."

**Item 12 — Bonded-field widgets: M2M multiselect now real and working.**
Backend research found the M2M bond REST surface (list/add/remove/replace
member routes) was already fully generated — the gap was entirely on the
frontend, which had zero awareness of many-to-many bonds (a declared M2M
field rendered as a broken single-value lookup picker, since the field isn't
even a property on the generated entity). Built: `BusinessUiEmitter` now
detects `field.getReferenceSemantics().isMultiple()` and emits `multiselect`
widget metadata plus the bond's own endpoint base (`bondEndpointBase`/
`bondFieldName`) for the frontend to complete itself with a runtime record
id. A new `createMultiSelectInput` renders a real checkbox list (populated
from the target concept's rows), `submitForm` defers the selection to a
separate `PUT .../{id}/{field}` call once the parent record's id is known
(create or edit), and the read-only detail view resolves and shows linked
labels via the same bond endpoint. **A real routing bug was found and fixed
during this work**: the bond's REST routes are generated onto the per-entity
controller (`/api/{tableName}`), not the generic binding-map controller every
other reference lookup uses (`/api/concepts/{tableName}`) — confirmed live
(`PUT /api/concepts/notes/{id}/tags` 404s; `PUT /api/notes/{id}/tags` works).
Live-verified end to end via a temporary scratch M2M bond (Note↔Tag):
checkboxes render with real labels, selecting and creating actually links
them, the detail view shows the linked labels, and reopening the edit form
correctly pre-checks the persisted selection — not just that the create call
returned 200.

**Item 23 — Box-authoring UI: a real, persisted drag-and-drop graph.**
Per the locked scope decision, "editable" means the visual layout (real
drag-to-reposition, persisted), not rewriting the pack's own declared fields
(`pack.json` is platform-shared catalog data; a running app mutating it would
be the same architectural problem as item 19's original config.json gap, just
for someone else's data). Added as an additional view inside the existing
text-based drill-down (not a replacement, so the existing permanent
`02-store-panel` routine's assertions on the text view still hold) — a
"🔗 Show graph view" toggle renders real HTML boxes (one per concept, listing
its fields) connected by SVG lines for every field with a `referenceTarget`
(a new field `PackCatalogEmitter.conceptDetails()` now exposes, read straight
from the pack's own declared `reference.target`). Dragging a box's header
updates its position live and persists to `localStorage` keyed by pack alias,
so the layout survives reopening. Live-verified, including the drag itself:
using the identity pack's real User/Role/UserRole concepts, confirmed 3 boxes
render, exactly 2 SVG edge lines exist (matching the 2 real reference fields），
a programmatic mouse down/move/up sequence on a box header moved it by
exactly the dispatched delta, and the new position round-tripped through
`localStorage`.

**Verification for all three**: new unit tests
(`GeneratorFacadePersistenceAdapterOverrideTest`-adjacent coverage was for the
earlier audit fixes; for these three, see `PackCatalogEmitterTest`'s new
`exposesReferenceTargetForBondFieldsInConceptDetails`/
`marksAnInstalledThirdPartyPackAsIncludedEvenWithoutInternalTables` cases),
full `generator` test suite green, and a full regenerate-boot-browser
regression sweep on both current samples (9/9 and 5/5 routines) confirming
zero regression to any existing, unmodified concept.

**Updated scores**: item 12 (bonded-field widgets) 75% → 100% (M2M multiselect
now real and working, matching the already-real single-reference select);
item 19 (install pack from UI) 80% → 95% (real best-effort write plus the
composer fix that makes installing an arbitrary pack actually do something;
not 100% because the local-workflow-only caveat is real and disclosed, not a
limitation that can be closed further without a different architecture);
item 23 (box-authoring UI) 85% → 95% (real persisted drag-and-drop graph,
not 100% because it's still a layered-browsing tool extended with a visual
mode, not a full authoring/editing canvas that lets you change what a pack
declares).

**New sum: 2544 + (100−75) + (95−80) + (95−85) = 2544 + 25 + 15 + 10 = 2594.
2594 / 27 ≈ 96.1%.**
