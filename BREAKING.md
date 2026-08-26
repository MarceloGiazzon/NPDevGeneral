# Breaking changes

NPDev is pre-1.0 and deliberately unstable — see the "Stability policy" section in `README.md` for
why. Every breaking change to the model DSL, generated code layout, or internal APIs gets a
one-line entry here, in the same commit that makes the change, alongside the `npdev migrate`
codemod that rewrites existing models automatically.

## 2026-08-25 — generated `listBy*` reference finders return `ConceptListSlice<T>`, not `List<T>` (RUN-28)

**What changes.** Every bonded field's generated `ServiceBase.listBy<Field>(Object value)` method
(the one backing `GET /api/{route}/by/{name}/{value}`) now returns
`com.npdev.kernel.concepts.ConceptListSlice<T>` instead of a raw `List<T>`. The finder also no
longer fetches the whole tenant table and filters in Java — it pushes an `EQ_CI` (case-insensitive,
trimmed, string-cast equality, the exact rule the old Java-side comparison applied) filter down
through `ConceptGateway#query`, the same pushdown path the plain paginated `page()` endpoint and
`PanelRuntime` already use. This was the platform's last unbounded read path (RUN-1/R8a deliberately
left it unbounded because the old fetch-then-filter shape could not signal truncation; it now can).

**Who is affected.** The generated REST endpoint's JSON response is UNCHANGED — the generated
controller still returns the plain array via `slice.records()`, with truncation surfaced
out-of-band via the same `X-List-Truncated`/`X-List-Limit` headers the plain `list()` endpoint
already sets, so an existing HTTP client sees no shape change. Only a caller of the generated
`...ServiceBase.listBy<Field>(...)` Java method directly — a hand-written custom controller or
procedure that bypasses the generated controller — sees the return type change from `List<T>` to
`ConceptListSlice<T>`; call `.records()` on the result to get the list back.

**Codemod.** None, and none is needed: no model file changes shape, and the REST wire contract is
unchanged. A hand-written caller of the generated service method directly needs a one-line edit
(`.records()`), the same migration `ConceptListSlice`'s own introduction (RUN-1/R8a, `listCapped()`)
already established as the pattern for this exact situation.

## 2026-08-20 — a generated app no longer serves `/npdev-ui-react/` (EDIT-12 / R10.3)

**What changes.** The frozen React editor bundle (`npdev-templates/static-react/` — `app.css`,
`app.js`, `AuthoringApp.js`, `ReactWorkbenchApp.js`, `index.html`, plus
`static-react-manifest.json`) was deleted, along with `RuntimeApiEmitter.emitOptionalReactUiAssets()`
and the `/npdev-ui-react` redirect in the UI redirect controller. **A generated app now returns 404
for that route.** The bundle had no in-repo producer after `NPDevEditor` was parked on 2026-08-17,
so it was decaying: `ReactWorkbenchApp.js`'s editor tabs already 404'd against endpoints R10.1
deleted, and its remaining surface duplicated the vanilla Operator UI at `/npdev-ui/`.

**Who is affected.** Anyone who opened `/npdev-ui-react/` in a generated app, or who linked to it.
The Operator UI at `/npdev-ui/` is unchanged and is emitted unconditionally.

**What replaces it.** `static/model-authoring.html`, emitted by `ModelAuthoringEmitter` — a
self-contained page covering both starter templates, all seven scaffolding actions
(concept/field/flow/panel/invariant/state/transition), and an editing surface (rename/delete a
concept, rename/retype/remove a field, remove a state) with `renamedFrom` recorded so a rename is
not a data-destroying drop-and-create. It reads and writes a local folder through
`window.showDirectoryPicker` and contains no `/api/` call of any kind, so R10.1's deleted
write-back door stays shut by construction.

**Residue, deliberately not built:** reference-typed and enum fields in the add-field forms,
multi-step flow authoring, and editing a flow's or panel's internals once created.

**Codemod.** None, and none is needed: no model file changes shape and no generated source
references the route once regenerated. Regenerate any app built before 2026-08-20 to drop the dead
route and the operator-UI button that pointed at it.

## 2026-08-19 — every label site accepts a per-locale object, not just a plain string (R5.6)

**What changes.** All 13 label-shaped fields in the model schema (`property.label`,
`workbenchAction.label`, `workbenchBandPicker.label`, `transactionDerivedField.label`,
`transactionUiState.label`, `lifecycleState.label`, `lifecycleTransition.actionLabel`,
`enumOption.label`/`displayLabel`, `presentationMetadata.label`/`shortLabel` — which covers both
`field.ui` and `concept.ui` via the shared `uiField`/`presentationMetadata` def, plus
`domainType.ui.label` at the AST layer even though it shares the same schema def — `actionMetadata.label`,
and `panelAction.label`) now accept `$defs/localizableLabel`: a plain string (**unchanged — every
existing model keeps validating and behaving exactly as before, a widening not a replacement**) OR
an object `{"default": "...", "<locale>": "...", ...}`. `default` is required whenever the object
form is used — the deterministic terminal fallback. Resolution order, implemented in
`com.npdev.kernel.i18n.LabelResolver` (new): exact locale-tag match (case-insensitive) → same-
language match ignoring region (`pt-BR` request against a declared `pt` entry, or vice versa) →
`default`. Never blank, never a random map entry.

Threaded through the full four-place chain (`JsonModelParser` → `ModelCompiler` →
`ModelResolver`'s specialize/extend merge, which now does a whole-value override — a specialization
declaring ANY label content, text or locale map, replaces the base's entirely, matching this
codebase's existing "override wins, no partial merge" convention elsewhere in the resolver → the
`CompiledModelCanonicalJson`/`Reader` canonical pair, which writes the object form only when a
label actually carries locale overrides so a plain-string label's canonical JSON is byte-identical
to before). Every existing `label`/`labelLocales`-shaped getter (`CompiledProperty.label()`, etc.)
keeps its old signature; `labelLocales()`/`getLabelLocales()` is a new, additive accessor. Records
constructed positionally outside `NPDevContract/dsl` (`CompiledProperty`, `CompiledPanelAction`,
`CompiledStateMachineState`, `CompiledStateTransition` — confirmed by grep across
generator/kernel/runtimehost test fixtures) keep their pre-existing constructor overload
unchanged; the widened shape is a new trailing-arg overload only.

**Server-side locale is not wired end to end yet.** Nothing upstream of `ExecutionContext` carries
a user locale today (no JWT claim, no header, no session field — confirmed by inspection of
`JwtAuthenticatedContextResolver` and the `RuntimeContextService` mustache template). This change
adds `ExecutionContext.locale()` (additive method, reads the existing generic `tags` map's
`"locale"` key — the same pattern `correlationId()`/`idempotencyKey()` already use — deliberately
NOT a new record component, since `ExecutionContext`'s canonical constructor is called positionally
by callers this change does not own) and `LabelResolver`, both in `NPDevKernel/kernel`. Wiring an
actual `Accept-Language`/`X-Tag-locale` header into `ExecutionContext.withTag("locale", ...)`, and
calling `LabelResolver` from the UI-metadata bundle builder, is a `NPDevRuntimeHost` change (that
module's `RuntimeUiMetadataController`/`RuntimeMetadataService`/`PanelRuntime` currently read
labels as raw strings off the generically-parsed compiled-metadata JSON tree) — out of scope here
and not yet done.

**Fixed the same day (EDIT-13, 2026-08-19).** The RuntimeHost half above landed same-day: the
generated `RuntimeContextService` now populates `ExecutionContext`'s `locale` tag from an explicit
`X-Tag-locale` header or `Accept-Language`, and `RuntimeUiMetadataController`/`RuntimeMetadataService`/
`PanelRuntime` resolve through `LabelResolver` before serving — proven live against a booted app
(three calls to the UI-metadata bundle endpoint: no header → default text, `X-Tag-locale: pt-BR` and
`Accept-Language: pt-BR,...` → the pt-BR text). See `ledger/items/EDIT-13.yml`. This paragraph is
left in place, not deleted, because it is the accurate record of what R5.6 itself shipped; the fix
landed as a separate same-day item.

**Codemod.** `NPDevCli/dsl_v2_migration.py`'s new `migrate_label_locales(doc, locale)` — wired to
`npdev migrate label-locales --input <files-or-dirs> --locale <tag> [--write]` on 2026-08-25
(`ledger/items/CLI-1.yml`; a stale copy of this paragraph, unfixed for six days, was what a
2026-08-23 audit read to (correctly, at the time) flag the gap) — widens every plain-string label site it
finds into `{"default": <original text>, "<locale>": <original text>}`, structurally (walks the
same shapes the parser recognizes, never a blind string replace), losslessly (the original text
survives byte-for-byte as both `default` and the locale entry), and idempotently (an
already-widened site is left alone). It is a no-op when `locale` is blank — this codemod does not
guess what locale an app's existing plain strings are in. Round-tripped against
`AppGen/apps/payment-webhook-rehearsal/definition/model.json` (18 real label sites): all 18
widened, collapsing the widened form back to a plain string reproduces the original document
byte-for-byte, a second run makes zero further changes, and `:dsl:validateModel` against both the
original and the widened file report the identical single pre-existing warning (unrelated to
labels) and zero errors.

## 2026-08-19 — remote packs must be signed, or accepted with an explicit flag (R8.7)

**What changes.** `npdev pack add` and `npdev pack update` now refuse a remote pack (any entry with
a non-empty `from` git coordinate) whose detached Ed25519 signature is missing, signed by an
untrusted key, or invalid — three distinct named refusals (`UNSIGNED`, `UNKNOWN_SIGNER`,
`BAD_SIGNATURE`). Every pack published before this change is unsigned, so **the next `pack add` or
`pack update` on any existing app with a remote pack coordinate will fail** until it is either
re-published signed or accepted once with `--allow-unsigned`, which records the decision permanently
in `npdev.lock` so a teammate can see the pack was taken on trust. Local (`$ref`) packs are entirely
unaffected — the gate only ever inspects entries with a `from`.

The default trust mode is `warn`, not `enforce`, precisely so this is a one-flag migration rather
than a hard wall; `enforce` (set in `npdev-trust.json` beside `npdev.lock`) additionally makes
`--allow-unsigned` inert. `UNKNOWN_SIGNER` and `BAD_SIGNATURE` are never bypassable in either mode.

**Blast radius, measured not estimated.** Running the full CLI suite after the gate landed broke
exactly four pre-existing tests, all of which call `pack add`/`pack update` against unsigned fixture
packs: `test_pack_catalog.PackAddFromCatalogRoundTripTest`, two in `test_pack_lock_tamper_guard.py`,
and `test_pack_publish_push.PackPublishPushRoundTripTest`. Each needed one line
(`allow_unsigned=True`) — which is exactly the migration a real consumer performs.

**Codemod.** None, and deliberately so: this is not a model rewrite. The migration is a human
decision about whether to trust an unsigned publisher, which is the whole point of the feature — a
codemod that silently added `--allow-unsigned` everywhere would remove the decision it exists to
force. Publishers close the loop with `npdev pack sign-keygen` then
`npdev pack publish --push --sign-with <keyfile>`.

## 2026-08-19 — `queries[].auditPolicy` / `procedures[].auditPolicy` removed from the schema (R5.1)

**What changes.** The `auditPolicy` field (`none`/`read`/`write`) on a `query` or `procedure`
declaration is no longer accepted — both object shapes have `additionalProperties: false`, so a
model that still declares it fails schema validation. It was schema-declared, parsed, compiled,
and round-tripped through the canonical JSON, but no validator, compiler pass, or kernel/runtime
code path ever read it to decide anything. Retired rather than enforced: the platform's real audit
trail is unconditional, not opt-in per query/procedure — every concept create/update/delete/restore
reached through the governed gateway is logged automatically (actor, timestamp, outcome, and — new
in this same change — a field-level before/after diff), regardless of what any query or procedure
declares. `tracePolicy` is unaffected and still works exactly as before.

**Codemod.** `python NPDevCli/npdev_cli.py migrate dsl-2 --write --input <model.json or directory>`
strips the dead key from every `queries[]`/`procedures[]` entry that declares it (structural, not a
blind string replace — every other field on the entry is left untouched). Already run against the
5 in-repo corpus models that declared it (medium-expense-approval's 3 copies, npdev-canary,
engine-probe); re-validated against the updated schema.

## 2026-08-19 — a generated app's background launcher moved into the emitter, and its log file moved with it (R9.4)

**What changes.** The duplicate-PID guard, port-conflict guard and log archiving used to exist only
in `Build-NpdevApp.ps1`'s inlined `$StartApp` text, so only AppGen-built apps had guarded launchers.
They now live in `OperationalRunbookEmitter`, which means **every** generated app gets
`Start-App.ps1`/`Stop-App.ps1` — plus new POSIX twins `start-app.sh`/`stop-app.sh`, where the
toolbox previously had exactly one `.sh` script.

**Your existing invocations still work.** `_ops\Start-App.ps1` and `_ops\Stop-App.ps1` exist at both
known paths — the outer `<OutRoot>\_ops` and `<OutRoot>\App\_ops` — under the same names and the same
calling convention. The outer pair are now two-line shims delegating to `App\_ops`, the same pattern
already used for `Run-FinalApp.ps1`/`Build-FinalApp.ps1`, so the guard logic exists in exactly one
place without breaking `Rebuild-And-Restage.ps1`, `New-AppConsole.ps1` or `Update-AppMetadata.ps1`,
all of which invoke those scripts by name at the outer path.

**What actually changes for you: the log file moved.** `Start-App.ps1` now writes stdout to
`App\logs\app-<UTC timestamp>.log` instead of a fixed `_ops\app.out.log`, and **no longer writes
`app.out.history.log` at all**. Anything tailing or parsing either of those two fixed filenames needs
to look in `App\logs\` and pick the newest `app-*.log` — which is what `New-AppConsole.ps1`'s own
status tail was changed to do in this same commit. The new location is deliberate: it is the `logs`
directory every regeneration already spares, and it shares R9.2's newest-20 retention pruning, so
background and foreground launches draw on one bounded budget rather than two unbounded ones.

**Codemod.** None — no model file changes shape, and no script invocation changes. The only migration
is the log path above, which is a read-side concern for tooling you own.

## 2026-08-18 — a `scheduleEvent` flow step with a non-zero delay no longer fires immediately (R2.4)

**What changes.** A flow step `"type": "scheduleEvent"` with `delaySeconds`/`delayMinutes`/`delayMs`
greater than zero used to publish its event **during the flow execution**, stamping the delay onto
the envelope as metadata "for a consumer to honor" — with no consumer that honored it. The canonical
demo's `AppointmentReminderDue`, modelled at `delayMinutes: 1440`, arrived instantly. It now writes a
PENDING `npdev_scheduled_event` row and is published by the R2.3 drain once `due_at` passes, so the
reminder actually arrives 24 hours later.

**`delaySeconds: 0` is unchanged in every respect** — same publish, same ordering, same synchronous
resume of waiting instances, byte-for-byte the old path. Zero-delay was deliberately *not* routed
through the table, which would have added a scheduler tick of latency to every model already using
it.

**Who is affected.** Any model with a `scheduleEvent` step whose delay is greater than zero. Because
the event is no longer published at execution time, within that execution it is now absent from
`ExecutionResult.getEmittedEvents()` and from the event store; a downstream step can no longer read
`$lastEvent` from a delayed schedule; and the step's trace info reports `scheduledEventName`/
`scheduledEventId` instead of `emittedEventName`/`emittedEventId`. There is also a new failure mode:
a delayed step returns `EVENT_PERSIST_FAILED` naming `DeferredEventScheduler` when no durable
scheduler is bound or the row cannot be written. That is deliberate — there is **no publish-now
fallback**, because silently firing a 24-hour reminder immediately is the exact defect this change
removes.

**Codemod.** None, and none is needed: no model file changes shape. Every affected model is already
spelled correctly — it simply now behaves the way it always read as behaving. A model that silently
depended on the instant fire should reduce its delay to `0`, which is a one-value edit and is the
honest expression of that intent.

## 2026-08-18 — the model/rule/orchestration editor draft endpoints are deleted (R10.1)

**What changes.** Every generated app's `AdminController` used to expose
`GET`/`POST`/`DELETE /api/admin/model/editor/draft`, `.../model/rules/draft`, and
`.../model/orchestration/draft` — server-side draft state, held in a plain static field, that a
save never wrote back into `model.json`. That was the only one-way door in the authoring loop: a
draft could silently diverge from the source model forever, and a full round-trip through it
dropped 14 of the DSL's root sections. All nine mappings (three draft kinds × GET/POST/DELETE) are
now deleted outright, along with their backing helpers (`DraftKind`, `saveDraft`,
`readDraftOrModel`, `buildEmptyDraft`) and the now-unused `NPDevModelProvider` dependency they were
the only consumer of. `GET /api/admin/model/export` and `GET /api/admin/model/ui` (`/ui-model`) are
unaffected — they are a separate, read-only projection of the compiled model, not the draft
mechanism. `POST /api/admin/model/sync-status` also stays; it only reports drift and never held
draft state.

**Who is affected.** The shipped React editor bundle (`static-react/`, no in-repo producer since
2026-08-17) called these endpoints to save drafts from its Model/Rule/Orchestration editor panels;
those saves now 404. This is an accepted, deliberate consequence of the owner decision recorded in
`ledger/items/EDIT-3.yml` (Roadmap Collection 2026-08-18, R10.0): demote the shipped editor to a
read-only viewer and make CLI/MCP authoring (`npdev validate`/`npdev add`/hand-editing `model.json`)
the only write path, until R10.2 replaces the viewer with a surface emitted from the compiled model
itself. No model DSL shape changed and no existing `model.json` needs rewriting, so there is no
`npdev migrate` codemod for this entry.

## 2026-08-18 — `schemaLifecycle.strategy: RecreateOnAppStart` is deprecated in favour of `Ephemeral` (STOR-16)

**What changes.** There is now a fourth `schemaLifecycle.strategy`, `Ephemeral`, meaning *this
app's data is disposable*: on every start NPDev drops the tables the manifest declares it owns and
recreates them from the model — no diff, no impact report, no acknowledgment token. It exists
because there was no posture that said that. `DropAndRecreateOnStructureChange` +
`allowDestructiveRecreate` authorizes itemized column drops and type narrowings **only**; dropping a
concept, or any diff that cannot be itemized, still refuses and demands a token, which is right for
a database whose contents matter and useless for a throwaway one.

`RecreateOnAppStart` is now a **deprecated alias** of it, accepted and warned about rather than
removed. Measured 2026-08-17: the runtime read `strategy` at exactly one line, comparing it to
`DropAndRecreateOnStructureChange` — so despite the name, `RecreateOnAppStart` had no code path and
recreated nothing. `Ephemeral` is the behaviour that name always claimed.

**Who is affected.** Any app whose `db.definition.json` says `RecreateOnAppStart`. All 7 in the
corpus are `InMemory`, where "drop and recreate on boot" and "memory is empty on boot" are the same
statement — which is what made re-pointing the name safe by measurement rather than by assumption.
An app moving to `Ephemeral` on a **physical** engine is a real change in what happens to real rows,
and must declare it: `allowDestructiveRecreate: true`,
`destructiveRecreateConfirmation: "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START"`, and
`scope: "NpdevOwnedTablesOnly"`. The existing `I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED` token does
**not** authorize it: that one says "this particular change deletes data", and an author who typed it
once for a column drop has not agreed that every future boot starts from empty.

The wipe is scoped to manifest-declared NPDev-owned tables, never to the live schema, so an app
sharing a database cannot take a neighbour's tables with it. `ownership: ExternallyManaged` +
`Ephemeral` is refused at generation time.

**Codemod.** `npdev migrate db-lifecycle --input <dir> --write` rewrites the strategy string in every
`db.definition.json` it finds. It changes only that string: rewriting someone's
`destructiveRecreateConfirmation` on their behalf would be signing a sentence they never read, so a
physical-engine definition is migrated and then *told* what it still has to declare.

## 2026-08-17 — `NPDevEditor` is no longer part of this repository

**What changes.** The authoring editor's source tree (`NPDevEditor/`, its standalone Gradle build
and the `ui-react` npm project) has been parked outside this repo while other work takes priority.
Nothing about a generated app changes: the editor still ships in every FinalApp at
`/npdev-ui-react/`, served from the **built bundle committed under**
`NPDevGenerator/generator/src/main/resources/npdev-templates/static-react/`, which the generator
copies in exactly as before. That bundle is now a frozen input with no in-repo producer.

**Who is affected.** Anyone who built the editor from source in this repo. `npm run dev` inside
`NPDevEditor/ui-react` is no longer available here. Removed alongside it: the `frontend` gate
(`run-all-gates.ps1` now runs three T2 gates, not four), `run-editor-gate.ps1`,
`run-editor-complexity-check.ps1`, `run-frontend-gate*.ps1`, `Setup-EditorNodeModules.ps1`,
`statezip-npdev-editor.ps1`, and `check-generated-bundle-freshness.py` (its only declared
source→artefact pair was the editor's; with no source tree the question it asked has no subject,
and leaving it running against an empty pair list would have passed vacuously).

The npm-audit CI job and its dependabot entry were **retargeted, not deleted** — they now cover
`scripts/quality/json-schema-validator`, the remaining npm project with a committed lockfile, so
that security posture is preserved rather than silently lost.

**No codemod.** This changes no model DSL, no generated code layout and no runtime API, so there is
nothing for `npdev migrate` to rewrite.

## 2026-08-14 — the generated CRUD `list()` REST endpoint no longer returns an unbounded table (RUN-1/R8a)

**What changes.** `GET /api/{route}` (the typed per-entity list endpoint) and `GET
/api/concepts/{conceptName}` (the generic concepts controller's free-text-search fallback) now cap
at `ConceptQuery.MAX_LIMIT` (1000 rows — the same ceiling the platform's existing paginated `page()`
path already enforces per page) instead of materializing an entire tenant table into the JVM and
serializing all of it. Both responses now carry `X-List-Truncated` (`"true"`/`"false"`) and
`X-List-Limit` response headers so a caller can tell a genuinely-complete list from a silently
partial one — previously there was no signal at all. A concept with more than 1000 rows for a given
tenant is the only case affected; every existing concept under the cap is byte-identical.

**Who is affected.** Any generated app's hand-written or generated frontend code that calls the
plain list endpoint and assumes it always returns literally everything (rather than switching to
the already-existing paginated `GET /api/concepts/{conceptName}?page=&size=` route, or the new
`X-List-Truncated` header, once a concept grows past 1000 rows). `ConceptStore` gains a new default
method (`findAllCapped`) and `ConceptGateway` gains a new default method (`listCapped`) — additive,
source-compatible for every existing implementation; `com.finalexec.db.AuditingConceptStoreDecorator`
/ `TenantControlledConceptStoreDecorator` (both in `runtimehost-core`) each gain a forwarding
override of `findAllCapped`, following the same pattern their `query`/`aggregate` overrides already
established. The generated `{{entityName}}ServiceBase.list()` keeps its existing
`List<{{entityName}}>` signature; a new sibling `listCapped()` (returning
`ConceptListSlice<{{entityName}}>`) is what the REST layer now calls for the truncation flag.

**No `npdev migrate` codemod applies** — this is a runtime-behavior change, not a model/DSL
construct; nothing in an authored `model.json` needs rewriting. **Regenerating the app is the
migration**, same as any other generated-code-layout change.

**The two full-tenant-scan uniqueness pre-checks** (`existsUniqueInConceptStore`/
`existsUniqueCompoundInConceptStore` in `service-base.mustache`) are **explicitly NOT touched by
this change** — see `ledger/items/RUN-1.yml` for why (their `uniqueValuesEqual` case-insensitive/
whitespace-trimmed comparison semantics do not translate cleanly to a portable indexed SQL lookup
across all four engines without a real risk of silently changing which values collide; deferred as
its own follow-up rather than forced under this pass).

**Reference finders (`listBy*`, the auto-generated `GET /api/{route}/by/{name}/{value}` foreign-key
lookup every bonded field gets) are also explicitly NOT capped.** They filter AFTER fetching, so
capping their fetch would silently drop a legitimate match instead of just returning fewer rows —
worse than the bug this change fixes, and with no way to signal it (`listBy*` returns a raw
`List<{{entityName}}>`, not a `ConceptListSlice`). They keep calling the platform's original
unbounded `conceptGateway.list(...)` fetch, byte-identical to before this change.

## 2026-08-13 — a pack-derived concept's physical table name depends on the pack's own id + major version, never the importing app's alias (PK-2)

**What changes.** `packRef.as` overrides the LOGICAL namespace prefix only (`packId::Name` ->
`alias::Name`, unchanged, still alias-able) — it no longer flows into the PHYSICAL SQL table name.
A pack-derived concept's table now derives from `{realPackId}_v{majorVersion}_{plural}` instead of
`{aliasOrPackId}_{plural}`. Two apps importing the same pack under different aliases previously got
two incompatible physical schemas for identical data (`identity_users` vs `auth_users` for the same
`identity` pack); they now both produce `identity_v1_users`. REST routes are unaffected — they were
already, and remain, derived from the alias-preserving convention (`ControllerEmitter`/the generated
business UI), permanently decoupled from a pack's physical table name so a version bump never
silently breaks a client's bookmarked URL.

**Who is affected.** Every existing generated app that imports any pack (today: `identity`,
`workspace`, or a third-party pack). `SqlIdentifierSupport` gains two new methods
(`physicalTableNameSource`, `aliasPreservingTableName`); `ModelAst`'s canonical constructor grows one
new parameter (`physicalQualifierByConceptName`, a `Map<String,String>`, side-channel only — never
round-tripped through the compiled model's canonical JSON, since it's consumed once at compile time).

**No manual `npdev migrate` step exists, and none is needed.** `SchemaRealizationEmitter` now
detects a pack-driven physical-name change automatically (comparing the concept's real table name
against what the pre-PK-2, alias-preserving derivation would have produced) and emits it into the
same `businessTableRenames` manifest key `renamedFrom`-driven renames already use — the existing
schema-lifecycle engine performs an in-place `ALTER TABLE ... RENAME`, not a drop-and-add, proven
safe and idempotent by `SchemaLifecycleExecutorTableRenameTest`. **Regenerating the app is the
migration.** Expect `UserDatabaseDefinitionLoader.fingerprintInputs`'s hash to show a mismatch on the
first post-upgrade boot — that is expected, not a red flag, and resolves via the same rename path.

## 2026-08-13 — a generated app's own source tree no longer contains most of RuntimeHost (BT-1)

**What changes.** `NPDevRuntimeHost/src/main/java` (311 files) is split: 244 app-independent files
(no `com.npdev.generated.` reference — `scripts/proofs/classify_runtimehost_sources.py`) move into
a new, independently-built module, `NPDevRuntimeHost/runtimehost-core`, shipped as a precompiled
`runtimehost-core-<version>.jar` (+ a `-sources.jar`) staged alongside every other kernel/adapter
jar (`scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars`, `npdev setup`) and consumed
via the SAME `implementation fileTree(dir: npdevRuntimeHostLibsDir, ...)` dependency a generated
app's `build.gradle.template` already declares. The other 67 files (app-coupled, plus
`FinalExecApplication.java` — Spring Boot's Gradle plugin only auto-detects a `@SpringBootApplication`
class from the app's own compiled sourceSet, not a dependency jar — and 17 files deliberately
excluded from compilation today by the manifest-driven allowlist, which would otherwise have shipped
unconditionally once physically split out) still live in `NPDevRuntimeHost/src/main/java` and are
still copied into, and recompiled by, every generated app exactly as before.

**Who is affected.** A developer debugging a generated app who is used to finding RuntimeHost's own
source directly under `<app>/src/main/java/com/finalexec/...` — auth filters, control-panel
controllers, monitoring, scheduling, publication/source-mutation services, and 241 other files —
will no longer find it there; that source now lives only in `NPDevRuntimeHost/runtimehost-core`
(if working from a full platform checkout) or in the staged `runtimehost-core-<version>-sources.jar`
next to the app's other dependency jars (attach it in an IDE the same way any other sources jar is
attached). Nothing about a model's own authored files, a generated app's REST surface, or its
runtime behavior changes — every relocated class keeps its original package
(`com.finalexec.api`/`com.finalexec.npdev.service`/etc.), so `com.finalexec`-qualified references
in application code, tests, or documentation are unaffected. The 3 controllers named in
`runtime-supported-controllers.json`'s `allowedControllers` that are app-independent
(`RuntimeMetadataValidationController`, `RuntimeSchedulesController`, `StorageSummaryController`)
moved too; their routes are unchanged and were verified live (booted a real assembled app, hit
`GET /api/runtime/schedules`, `POST /api/runtime/metadata/validate`, `GET /api/admin/storage/summary`,
all 200).

**Why.** Every generated app recompiled all 311 files on every build, even though 244 of them never
vary per model. `scripts/proofs/run-scale-proof.ps1`'s 100-concept rung measured a ~25% faster
`gradlew build` and ~48% faster boot after this change (see the commit that records the measurement
for the full before/after table and its caveats) — the number `BT-2` (sealed-pack precompilation, a
much larger future card) depends on to decide whether it's worth doing at all.

**No codemod.** Nothing in a model, an authored `config.json`/`db.definition.json`, or the shape of
a generated app's REST API changes — regenerate with `npdev generate app` (after
`sync-runtimehost-libs.ps1 -BuildLocalJars` or `npdev setup` once, to stage the new jar) and an
existing model's generated app looks and behaves identically, just with a smaller own source tree.

## 2026-08-13 — `field.type`/`domainType.baseType`/`schemaObject.type` gain `decimal`; `SchemaAst`/`CompiledSchema` each grow two constructor params (R5)

**What changes.** A new DSL field type, `decimal`, joins the closed enum on `field.type`,
`domainType.baseType`, `eventPayloadField.type` and `$defs.schemaObject.type` in all four mirrored
copies of `model.schema.json`. A `decimal` field compiles to `java.math.BigDecimal`, persists as
`NUMERIC(p,s)` (precision/scale default to 19,4 when not declared; declare them via new `precision`/
`scale` sibling properties on `field`/`schemaObject`, the same shape as the existing `maxLength`).
`SchemaAst`'s and `CompiledSchema`'s canonical constructors each grow two new `Integer` parameters
(`precision`, `scale`), inserted right after `maxLength`.

**Who is affected.** Any code outside this repo constructing `SchemaAst`/`CompiledSchema` via their
19-parameter canonical constructor directly (rather than one of the shorter delegating overloads,
which are unchanged) would no longer compile. In this repo, the three call sites that use the
canonical constructor (`JsonModelParser.parseSchema`, `ModelCompiler.toCompiledSchema`/
`mergeSchemas`) were updated in the same commit; every other constructor caller found in this repo
uses a shorter overload and needed no change. No existing model is affected — `decimal` is a new
enum value and `precision`/`scale` are new optional properties, so every model that validated before
this change still validates identically.

**Why.** M1 ("it can model a business") needs an exact numeric type — every existing sample worked
around its absence by encoding money as `priceCents`-style integers, which cannot express a tax rate
or an exchange rate without inventing a second workaround per use.

**No codemod.** Purely additive: a new enum value and two new optional schema properties. No
existing model references `decimal`, so there is nothing to rewrite.

## 2026-08-10 — `SqlDialect.requiresOrderByForPagination()` is removed (STOR-13)

**What changes.** The method is gone from the `SqlDialect` interface and from all four
implementations. Nothing replaces it: `requireOrderedForPagination(String)` already demands an
`ORDER BY` of every engine, so the per-engine answer could never change an outcome.

**Who is affected.** Any implementation of `SqlDialect` outside this repo, which would no longer
compile against the interface — delete the override. In this repo, the four dialects and one
anonymous test stub, all updated in the same commit. No caller is affected, because there was none:
that is the defect this removes.

**Why.** Shipping an unconditional rule alongside a flag that reads like it gates the rule invites
the next reader to write a conditional that cannot exist. Conformance vector P3 pins the refusal to
every engine on purpose — injecting an order on the one engine that needs it hides the difference
from the model and still returns overlapping pages.

**No codemod.** A codemod rewrites models and call sites; this method had neither.

## 2026-08-10 — `_ops/resolved-db-plan.json` records app paths RELATIVE to the app (PORT-2)

**What changes.** `finalAppPath` and `opsRoot` were absolute paths on the generating machine; they
are now `"."` and `"_ops"`, resolved against the app directory at read time — the same treatment
`resolvedDataRoot` received in PORT-1. `Run-FinalApp.ps1`, `Build-FinalApp.ps1` and
`README_RUNBOOK.md` no longer name an absolute app location either. `runtimeHostLibsDir` stays
absolute (it is a machine-level cache, not part of the app) but is now overridable via
`NPDEV_RUNTIMEHOST_LIBS` and is dropped when the recorded cache is not present locally.

**Who is affected.** Anything reading `resolved-db-plan.json` and expecting `finalAppPath`/`opsRoot`
to be absolute. In this repo that is `NPDevCli/npdev_cli.py`, updated in the same commit.
`NPDevManager` mentions the file only in a doc comment and parses none of these fields.

**Why.** A copied or shared app's toolbox operated the ORIGINAL app: `_ops/Run-FinalApp.ps1` ran the
jar at the path the app was generated in. Not a failure — a silent success against the wrong
artefact. Someone who copied an app, edited it, and pressed run was running the copy they had not
edited.

**No codemod.** Nothing in a model references these paths, and a regenerated app is correct by
construction. An app generated before this change keeps working where it was generated; copy it and
it will not.

## 2026-08-10 — a generated app keeps its database BESIDE ITSELF, at `<FinalApp>/data` (PORT-1)

**What changes.** The data root, and therefore `spring.datasource.url`, is app-relative instead of an
absolute path on the machine that generated the app:

| | before | after |
|---|---|---|
| `spring.datasource.url` (H2Local) | `jdbc:h2:file:D:/…/Build/databases/<app>/<db>;…` | `jdbc:h2:file:./data/<db>;…` |
| `spring.datasource.url` (H2Server) | `jdbc:h2:tcp://host:port/D:/…/<db>;…` | `jdbc:h2:tcp://host:port/./data/<db>;…` |
| `npdev.database.data-root` / `_ops` plan `resolvedDataRoot` | absolute | `data`, or `data/<generated name>` |
| H2Server `-baseDir` | the data root | the FinalApp directory |

**Who is affected.** Everyone with an existing app on H2Local or H2Server: **your database does not
move itself.** The app will create a new, empty one at `<FinalApp>/data` on the next boot. To keep
your data, copy `<Build>/databases/<appId>/*` into `<FinalApp>/data/` before running it, or start the
app with `--spring.datasource.url=` pointing at the old file. Server engines (Postgres, MySQL, SQL
Server) are unaffected — their URLs are built from host/port and never contained a path.

Also: a `db.definition.json` that declares `database.h2FilePath`, or an H2Server
`database.jdbcUrl` containing an absolute path, is now REFUSED at generation time — NPDev derives an
app-relative path and cannot honour or verify an absolute one. Remove `h2FilePath` (nothing reads it),
or rewrite the URL's path as `./data/<databaseName>`. The refusal message says both.

**Why.** The absolute path was resolved at BOOT, so a generated app handed to anyone else tried to
open its database on a drive they may not have. It is the most serious of PORT-1's six leaks and the
only one that stops an app working. Proved fixed by copying an entire built FinalApp to a path
sharing no ancestry with the workspace and booting it there.

**Regeneration keeps your data.** `<FinalApp>/data` is now spared by both wipes (`Build-NpdevApp.ps1`
and the generator's `deleteBeforeMount`), so the schema-evolution paths that only run against an
existing database still have one.

**No codemod.** Nothing in a model references these paths — the value is derived, and the two
`db.definition.json` fields above are refused with a message naming the fix rather than rewritten,
because choosing where someone's existing database should end up is not a decision a codemod can make
for them. Same reasoning as the `wmsoffice` entry below.

## 2026-08-10 — the `wmsoffice` profile's JWT key paths are now relative and overridable (PORT-1)

**What changes.** `application-wmsoffice.yml`, which ships in every generated app via the shared
RuntimeHost template, had `public-key-path` and `private-key-path` set to absolute paths under the
AUTHOR's build directory. They are now
`${NPDEV_WMSOFFICE_KEYS_DIR:./wmsoffice-keys}/jwt-{public,private-pkcs8}.pem`.

**Who is affected.** Only an app that activates the `wmsoffice` Spring profile — for everyone else
the file was, and remains, inert. If you activate it, either put the keys in `./wmsoffice-keys`
beside the running app or set `NPDEV_WMSOFFICE_KEYS_DIR`.

**Why.** The file's own comment argued it was "inert for any app that doesn't activate the profile,
so it's safe to ship". Inert is not the same as harmless: every generated app, for every user,
carried one machine's filesystem layout and named its key material. Found by the first out-of-tree
generation this repo has run (`scripts/hygiene/check-out-of-tree-generation.ps1`), which reproduced
it independently as F7 from the third-person trial.

**No codemod.** Nothing in a model or in generated code references these paths; the change is a
default in a template resource.

## 2026-08-09 — an app's `_ops` toolbox and its database identity are now per-APP, not per-FOLDER (QUAL-3)

**What changes.** Two things move, and they are one defect:

1. `_ops` is emitted INSIDE the FinalApp (`<FinalApp>/_ops`), not beside it (`<FinalApp>/../_ops`).
2. `npdev init` now writes a `manifest.json` declaring the app's id. The generator already prefers
   that manifest over inferring an id from the directory layout, so `npdev init D:\Apps\my-app` now
   yields `my-app` where it previously yielded `Apps`, the parent folder. `containerName`, the
   database name and the data root all derive from it.

**Why.** Both encoded "the toolbox/identity belongs to the parent directory". Measured with two real
apps generated into one folder: both resolved to `appId=qual3`, `containerName=npdev-qual3` and data
root `Build/databases/qual3`, and both shared one `_ops/resolved-db-plan.json`. `npdev db status
--app <app-a>` answered about `app-b`. They were not two apps sharing a toolbox — they were one
database with two front doors, and `npdev db reset` for either destroyed the other's data while
reporting success. The acknowledgement token does not protect against this: the user types it
correctly, for the app they intend, and different data is deleted.

**Identity is DECLARED, not inferred better.** The obvious fix — "if the definition's directory is
not called `definition/`, that directory is the app" — was implemented, measured, and reverted: 25
corpus definitions live in a directory called `Input` with no manifest, and that rule collapsed all
25 onto `appId=Input`, a wider collision than the one being fixed and inside the corpus rather than
a user's folder. It also broke `UserDatabaseDefinitionDeclaredConnectionTest`. Path shape cannot
tell an app directory from a wrapper directory, so it is no longer asked to.

**Codemod: none, and none is possible.** No model content changes. Instead the READER carries the
compatibility: `_find_ops_root` prefers the app-local toolbox and only falls back to the legacy
shared location when no app-local one exists, printing `using the legacy SHARED toolbox at <path> --
it may describe a different app than the one you named`. Regenerating an app moves it to the new
layout. An existing app can also be fixed by hand by adding a `manifest.json` with an `id`.

**What breaks, and for whom.** An app generated before this change keeps working via that fallback,
with the warning. Once it gains a manifest its database identity changes (`npdev-Apps` →
`npdev-my-app`), so it connects to a NEW, empty database; the old one still exists under its old
name and can be dumped and restored if it held anything. Every corpus layout
(`<App>/definition/...`, `<App>/Input/...`) is unaffected — those already carry a manifest or
resolve correctly through the unchanged fallback, pinned by `AppIdentityIsolationTest`.

## 2026-08-09 — SQL identifiers are now QUOTED when the target engine reserves them (STOR-6)

**What changes.** A model field named `order`, or a concept whose table realizes to `rows`, now
emits `` `order` ``/`[order]`/`"order"` in the engine's own quoting syntax, and the runtime queries
it the same way. Nothing else moves: quoting is CONDITIONAL, so an identifier no engine reserves is
emitted exactly as before.

**Codemod: none, and none is possible.** A model that hits this could not generate a runnable schema
before, so there is no existing behaviour to migrate. Measured over the corpus, 4 models x 3
engines: 10 of 12 emit byte-identical DDL, and the 2 that move (`rank` on MySQL, `plan` on SQL
Server) move from broken to working.

**What breaks, and for whom.** `SqlDialect` gained abstract `isReservedIdentifier(String)`. Any
implementation outside this repo must add it. There is no default: a dialect that silently answered
"nothing is reserved" would restore this defect for its engine while every test stayed green, which
is the X0 rule this interface exists to enforce.

**Three seams, not two.** The generator emits the DDL, `JdbcBusinessConceptStore` reads and writes
rows, and `SchemaLifecycleExecutor.quotedIdentifier` serves the 40 schema-lifecycle sites that only
run when a column CHANGES on an existing database — the third was found by a live run, not by the
plan. They are pinned together by the twin-pair rule `sql-identifier-quoting-three-seams`, because
quoting one alone is worse than quoting none: the app builds, boots, and cannot find its own table.
`SchemaLifecycleExecutor.safeIdentifier` deliberately stays UNQUOTED for the places a name goes into
a string literal (`information_schema` guards, SQL Server's `sp_rename`).

## 2026-08-09 — `db.definition.json`: a `jdbcUrl` / `h2FilePath` that CONTRADICTS the real connection is now refused (STOR-8)

**What changes.** `database.jdbcUrl` and `database.h2FilePath` are still accepted. A value that
DISAGREES with the connection NPDev will actually make now fails at generation time, naming both the
declared value and the real one.

**Codemod: delete the key, or fix it.** There is nothing to migrate mechanically — a contradicting
value was already not being honoured, so removing it changes no behaviour. `npdev migrate` needs no
rule here, and the refusal message tells you which of the two you meant.

**Why this is a fix and not a restriction.** Both fields read as authoritative. `h2FilePath` is
consulted by nothing at all; `jdbcUrl` is consulted only for H2Server, where `resolveHost`/
`resolveHostPort` parse the host and port out of it and everything else is ignored. So a user who
pointed `jdbcUrl` at an existing production database got **no error, no warning, and a connection to
a different database** — and could then write to it. That is the X0 silent-answer rule broken in the
storage layer, where it is least visible and most expensive.

**The blanket refusal was measured and rejected.** The obvious change was to refuse both fields
outright. **Twelve app definitions set one of them — four of them official samples** (AuxScreen,
Pigmentampa, WmsOffice, WordLab) — and every one declares exactly what NPDev composes anyway.
Refusing the field would have broken all twelve to fix a hazard none of them has. So the guard is on
DISAGREEMENT, and options are ignored when comparing (`MODE=`, `DB_CLOSE_ON_EXIT=`) because failing
on those would be the noisy gate this project refuses everywhere else.

**Honouring an explicit URL remains unbuilt, deliberately.** It is a feature, and it raises a real
question — does an explicit URL bypass the identity check that stops two apps sharing a database? —
which deserves its own design rather than being smuggled into a cleanup.

## 2026-08-08 — `database.engine` gains `MySQL` and `SqlServer` (storage/PLAN.md S4b/S5)

**Not a breaking change — widened, not narrowed. No codemod needed, and that is a claim, not an
omission.** Every existing `db.definition.json` validates identically: `Postgres`, `InMemory`,
`H2Local` and `H2Server` keep their exact meaning, their exact conditional requirements, and their
exact generated output. The enum grew by two values; nothing was renamed, removed or retyped, so
there is no model text for `npdev migrate` to rewrite.

Threaded: `schemas/ai/user-db-definition.schema.json` (enum + a host/port/username/password
requirement for each new engine, matching Postgres's), `DatabaseEngine` (two new values on the
EXISTING `storageMode` axis — both `jdbc`, because that second string is the split a document engine
will use, not a dialect name), `UserDatabaseDefinitionLoader` (driver, JDBC URL, default port,
container naming), and `SqlDialects` (registry).

**`MySQL` and `SqlServer` are SUPPORTED** as of 2026-08-09, run `31296993259` -- and this entry
spent a long time saying the opposite, correctly, so the change is worth stating precisely.

The bar was never "the dialect passes unit tests". It was: **a generated app boots, serves and
persists on this engine, in CI.** That is now true for both, in the same run, in the same job as
Postgres:

| assertion | MySQL 8.4 | SQL Server 2022 |
|---|---|---|
| boots -- schema realized by NPDev's own engine | pass | pass |
| non-BMP unicode round-trips (`cafe (coffee) (rocket)`) | pass | pass |
| filtered + ordered + paginated query | pass | pass |
| rows survive a restart | pass | pass |
| Tier C: nullable column added, rows preserved (E1) | pass | pass |
| Tier C: `renamedFrom` MOVES data (E2) | pass | pass |
| Tier C: nullability and unique/non-unique enforced (I2/I3) | pass | pass |
| the five `_ops` operations, byte-identical to Postgres's | pass | pass |

**Eight defects stood between "the dialect is complete" and this**, each invisible until the one
before it was fixed, and every one of them found by building the artifact a user actually runs:

| # | what | id |
|---|---|---|
| 1 | the app template declared no MySQL/SQL Server **JDBC driver** | STOR-4 |
| 2 | NPDev's own internal tables key on `TEXT`, which neither engine can index | STOR-4 |
| 3 | the realization script is written in Postgres/H2 **guarded-DDL idioms** | STOR-5 |
| 4 | a text column has THREE roles and only two were ever asked about (`TEXT DEFAULT` is MySQL error 1101) | STOR-7 |
| 5 | a row lock is a suffix on three engines and a **table hint** on SQL Server | STOR-9 |
| 6 | five more two-engine assumptions between "it boots" and "it works" -- a Postgres-by-default dialect probe, UUID bound as a serialized Java object, timestamps read back unbindable, a schema differ comparing the catalog against a type the emitter never wrote, a two-way column rename | STOR-10 |
| 7 | a Postgres-only SQLSTATE, so an app booted once and **never again** | STOR-12 |
| 8 | on MySQL a create violating `unique: true` returned **200 and overwrote the row that held the value** | STOR-11 |

**#8 is the one to read.** It was known and documented -- `MySqlUpsertStrategy`'s javadoc described
the divergence exactly -- and then closed with "nothing in NPDev's generated schema puts a second
unique index on a table it also upserts by id today". That sentence was false when written: any
field declaring `unique: true` produces exactly that shape. A record correct about the ENGINE and
wrong about NPDEV is the more dangerous half, because it turns a live hazard into a closed question.

**What is still true and worth knowing** (these are differences, not defects, and each is declared
at the point of choice by `npdev engines` and by the generated `.env.example`):

- **MySQL commits implicitly on DDL.** A migration that fails partway CANNOT be rolled back --
  earlier steps are already permanent. NPDev reports this truthfully (`PartialApplicationTruth`)
  rather than claiming a rollback it did not perform.
- **SQL Server has no suffix row cap** (`TOP` is a prefix), so `SqlDialect.rowLimit()` throws there
  rather than returning a plausible wrong answer. Boundary **B29**; zero production call sites --
  every real site asks `rowLimited()`, which every engine answers.
- **Identifiers are not quoted** (`STOR-6`, open): a field named `value`, `order` or `rows` produces
  DDL the engine rejects. This is engine-INDEPENDENT -- it bites H2 and Postgres too -- and is not a
  MySQL/SQL Server limitation.


`npdev engines` marks MySQL and SQL Server EXPERIMENTAL and says why **at the point of choice**, in
the CLI, in the Manager's dropdown and in `docs/reference/USING_MYSQL_AND_SQL_SERVER.md` — all from one
registry, so none of them can drift into claiming otherwise.

## 2026-08-08 — `build.javaVersion`'s upper enum removed (ROUND2_PLAN.md R1c)

**Not a breaking change — widened, not narrowed.** Every config that validated under the old
`enum: [17, 21]` still validates identically. What's new: any integer `>= 17` is now accepted at
the schema/validation layer, with no upper bound — the 3rd-party user who originally asked for "a
newer Java version" (below) wanted this future-proofed against every Java version to come, not
renegotiated every time a new JDK ships.

Threaded: all 3 `config.schema.json` mirrors (`build.javaVersion.enum` → `build.javaVersion.minimum:
17`), `GeneratorMain.resolveJavaVersion` (the `SUPPORTED_APP_JAVA_VERSIONS` allowlist replaced with a
floor-only check), `GeneratorMainJavaVersionResolutionTest` (new cases: accepts 25, accepts a
version that doesn't exist yet, rejects below the floor).

**What still gates a value above 21 in practice, and everything that had to move to make it real:**
every generated app's bundled Gradle wrapper (`NPDevRuntimeHost/gradle/wrapper`) moved 8.5 → 9.5.1,
`foojay-resolver-convention` 0.8.0 → 1.0.0, `org.springframework.boot` plugin 3.3.2 → 3.5.16 (its
`bootJar` task called a Copy API method Gradle 9 changed), ArchUnit 1.3.0 → 1.4.2 and Mockito
5.11.0 → 5.23.0 / Byte Buddy → 1.17.7 (both couldn't read/instrument Java 25's class file format).
Two more bugs surfaced only by actually booting a packaged jar as a real external process — the
`application-step0.yml` "zero-setup trial" profile never cleared an inherited
`spring.autoconfigure.exclude=DataSourceAutoConfiguration,...` (an empty-string YAML override was
silently ignored; fixed with proper `exclude: []` list syntax), and three `NpdevObservabilityConfig`
beans (`traceSummaryStore`/`executionSummaryStore`/`eventMetaStore`) registered a dual-interface
adapter instance under two type-assignable bean names, breaking any plain
`TraceStore`/`FlowInstanceStore`/`EventStore` injection once a real `DataSource` was available for
the first time. Full chain, live-verification, and how each was isolated as genuinely new (not
pre-existing): `REG-143`. Platform modules (dsl/kernel/generator/adapters/runtimehost source) are
unaffected — they stay on Gradle 8.5 / Java 17; only the template shipped inside every *generated
app* moved.

No `npdev migrate` codemod needed — nothing here requires rewriting an existing config to keep
working.

## 2026-08-07 — `config.json` gains an optional `build` block (deps-and-java/PLAN.md, per-app Java level + declared dependencies)

**Not a breaking change — added, not modified.** `config.json`'s new `build.javaVersion` (originally
17 or 21, default 17 — see the entry above for the same-day widening) and
`build.repositories[]`/`build.dependencies[]` are all optional; an app with no `build` block
generates and behaves exactly as before this change. No `npdev migrate` codemod needed — the
stability policy's codemod rule is for changes that require rewriting an EXISTING model/config to
keep working, and nothing here does.

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

**Why:** the Move 7 implementation spec, W1 (a working document that was never committed to this
repo — unlike its Move 6 sibling there is no git history for it) — the last three untyped
`transaction.metadata` keys
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

**Why:** docs/archive/programme-history/MOVE6_TYPED_SURFACE_PLAN.md §B — the same feature was typed when it attached to
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

**Why:** docs/maintainers/NEXT_EXECUTION_PLAN.md P6.1 (3.7). Cheap to enforce once written, and the exact class
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
measurements, and the naming decision: the DSL2-and-decomposition plan §2.A (moved out of the repo
by md-zero-2026-08-11 Phase 2; git history keeps it).

**Codemod:** `npdev migrate dsl-2 --input <path...> [--write]` (dry-run by default). Structural,
idempotent, and refuses to touch anything it detects as a serialized compiled-model fixture rather
than an authored document. See `NPDevCli/dsl_v2_migration.py`'s module docstring for the full
design.

**Migrated in this change:** every git-tracked model in this repo (`NPDevSamples/**`,
`NPDevContract/dsl/resources/Models/**`, `NPDevGenerator/resources/Models/**`, test fixtures).
**Not yet migrated:** `AppGen/apps/**` — a non-git external directory, deliberately excluded from
this pass; run the same codemod there whenever that's reviewed directly.
