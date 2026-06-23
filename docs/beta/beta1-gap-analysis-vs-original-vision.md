# Gap analysis: original beta1-vision-spine ask (A/B/C/D) vs current implementation

**Date:** 2026-06-23 (original analysis). **Updated:** 2026-06-23 (post 5-track
closure pass — see "2026-06-23 update" note at the top of each section).
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

**Remaining open items, if continuing this line of work** (not a commitment):
(1) Adapters/Event/Code/Panel-Procedure in area A are untouched and remain a
separately-scoped integration project; (2) C.2's "install a pack from the
running UI" gap (the catalog is now browsable, but not actionable); (3) C.1's
bonded-field widget variety (picker-only). None of these were in the 5-track
plan's scope — they were re-confirmed still open, not newly discovered.
