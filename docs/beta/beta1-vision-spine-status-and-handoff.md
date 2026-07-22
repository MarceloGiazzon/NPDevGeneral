# beta1-vision-spine — Status & Handoff

**Branch:** `beta1-vision-spine` (cut from `beta0-no-false-green-release-hardening` at `fd7f8c8`)
**HEAD as of writing:** `5cb0f3f`
**Working tree:** clean
**Document purpose:** a complete, self-contained resume of the beta1-vision-spine effort, written so a new session (human or AI) can pick up exactly where this one left off without re-deriving context from git history or memory files. Detailed and "expensive" on purpose — this is meant to be read once, fully, before continuing.

---

## 1. Why this track exists

After beta0 closed, Marcelo asked for a rethink covering four observed gaps:

- **A.** NPDev core ↔ definition integration (adapters/flow/event/orchestration/tenancy/code/panel) — generated apps barely touched the kernel's own runtime machinery.
- **B.** Defaults vs. personalization at every level (config.json → field) — no cascading override system existed.
- **C.** Web UI — multi-level data editing, an overridable frame/shell, admin-in-app super-user mode, internal Menus/Preferences tables, richer field input types, an author "app store" + box view.
- **D.** DB schema-change admin — migrations were additive-only, no data-loss tracking.

**Key diagnosis at the time:** the contract/DSL layer already modeled a rich vocabulary (full Capability/Event/Orchestration/Flow/Panel/Procedure AST, `PresentationMetadata` with widgets/pickers/conditionals), but the generator/runtime only consumed a slice of it, via *forked* paths — business CRUD bypassed the kernel entirely while flow/event went through it; business UI and the internal admin UI were two separate worlds; the Flyway emitter was additive-only; `config.json` was closed (`additionalProperties: false`) with defaults scattered as inline fallbacks. The fix was framed as building the missing **spine**: a Resolution Pipeline (cascading defaults/overrides with provenance) + a Projection layer (one renderer, one execution path) + Change-as-data (migration plans tied to truth).

**3 locked decisions (Marcelo, 2026-06-18):**
1. Seal beta0, open this new track on `beta1-vision-spine`. Don't contaminate beta0.
2. Extend the existing manifest renderer (vanilla-JS, manifest-driven) — do NOT generate React, do NOT converge with the React Editor.
3. Add a structured `defaults`/`overrides` envelope alongside the existing closed schemas — NOT open `additionalProperties`.

**5 more gaps surfaced by Claude beyond Marcelo's original 4**, all of which became real work:
- **E.** Identity/Users/Roles/RBAC was completely absent — the hidden prerequisite for super-user mode, Menus, Preferences, and tenancy.
- **F.** Package/provenance/version identity — prerequisite for the "app store" idea.
- **G.** The UI-runtime fork itself (resolved directly by locked decision #2).
- **H.** Data lifecycle beyond DDL (seed/fixtures, promotion stages).
- **I.** Optimistic concurrency on business data ("did I lose an update?").
- **J.** Governance tension with the bounded beta0 roadmap and its immutable tag (resolved by locked decision #1).

**The 7-phase path agreed on:**
```
0. Decide & seal
1. Resolution Pipeline + overrides envelope
2. Identity/RBAC          ┐ (parallel-ish)
3. Kernel-unify business CRUD + optimistic concurrency  ┘
4. One renderer, richer (frame/shell, multi-level data, widgets)
5. Admin-in-app + internal Menus/Preferences
6. Change-as-data (migration plans + seed)
7. Package/provenance + store + box view
```

---

## 2. Where things stand: ALL SEVEN PHASES ARE DONE

Every phase below is committed, build-verified, and live-verified (booted a real generated app and exercised the behavior with real HTTP calls) — not just unit-tested. This is the single most important fact for a continuing session: **the roadmap that motivated this entire branch is complete.**

### Phase 0 — Decide & seal
Done by the 3 locked decisions above. Branch opened, decisions recorded.

### Phase 1 — Resolution Pipeline + overrides envelope (3 increments, CLOSED)
- **Increment 1**: pure cascade engine in `NPDevContract/dsl/.../com/npdev/dsl/v1/settings/` — `SettingKey<T>` (typed, has a platform default + coercion), `SettingScope` (`PLATFORM < APP < MODULE < CONCEPT < FIELD`), `SettingTarget` (selector chain like `field:Concept.field` → `concept:Concept` → `app`), `SettingLayer`/`SettingStore`, `SettingResolver` (first-match-wins with provenance), `ResolvedSetting` (value + sourceScope + sourceSelector + isOverridden), and the `NpdevSettings` registry of actual settings (`ui.generateBusinessUi`, `crud.kernelControlled`, `coda.allowed`, `log.enabled`, `log.level`, `field.widget`).
- **Increment 2**: wired into the real generation pipeline. `ConfigSettingsReader` reads a `defaults`/`overrides` envelope from `config.json` into a `SettingStore` (scope inferred from the selector prefix); `config.schema.json` (all 3 copies — `schemas/`, `schemas/authoring/`, `dsl/resources/Schemas/`) gained the optional envelope while staying closed; `GeneratorFacade` gates `BusinessUiEmitter` on the resolved `ui.generateBusinessUi` setting.
- **Increment 3**: legacy-alias bridge (`generator.emitUiAssets` now seeds the `ui.generateBusinessUi` app default instead of being dead/unconsumed); `SettingsManifestEmitter` writes `src/main/resources/npdev/resolved-settings.json` (every setting's resolved value + platform default + source scope/selector + overridden flag + description) into every generated app, deterministically.
- Committed as `76f780d`.

### Phase 2 — Identity/RBAC (MAJOR RECALIBRATION, then closed across several commits)
Grounding work revealed the kernel **already had** a full RBAC/tenant/audit concept-CRUD path (`DefaultConceptGateway`, `PermissionEvaluator`, `ExecutionContext`, `RolePermissions`, `dev.permissions.json` as a grant manifest, `authz-default`/`auth-context-jwt` adapters) — so this was never "build RBAC from scratch." The real gaps were: generated business CRUD bypassed the gateway entirely (direct JDBC), the context-service template stubbed a fixed `dev`/`developer` principal with all roles, there was no persistent Users/Roles *data*, and identity wasn't surfaced anywhere.

What shipped:
- Identity settings (`auth.mode`, `security.superUserRole`, `security.tenantIsolation`) added to `NpdevSettings`.
- Built-in **identity pack** (`NPDevContract/packs/identity/pack.json`): `User` (username unique, displayName, email, active), `Role` (name unique, description), `UserRole` (bonds to both, cascade/restrict on delete).
- Built-in **workspace pack** (`NPDevContract/packs/workspace/pack.json`): `Menu` (label, target, kind enum `INTERNAL`/`BUSINESS`, parentMenuId, requiredRole, ordinal, visible) and `Preference` (userId, category, prefKey, prefValue — a generic key/value model deliberately kept simple, "generic now, evolves").
- `BuiltinPackComposer`: compiles each platform pack in isolation (they live outside any app's model root, so the normal relative-`$ref` safety check can't be used directly) and merges the resulting `alias::Concept` entries into the app's `CompiledModel`. Gated by the `internal.tables` setting (default `false`).
- Super-user admin UI: `BusinessUiEmitter` marks built-in-pack concepts `admin: true`; the manifest renderer fetches `/api/me` (new `GeneratedMeController`), computes `isSuperUser`, and renders admin concepts under their own nav group only for the super-user role.
- Generate-verified and combined-boot-verified live (composed identity/workspace tables produced real DDL and working CRUD endpoints; `auth.mode=none` correctly disabled auth at runtime via Spring profile wiring).

Branch milestones: `a580e91` (pack composition) → `743af33` (auth.mode wire) → `a871b4d` (label fix) → `e5806fd` (super-user admin UI).

### Phase 3 — Kernel-unify business CRUD + optimistic concurrency (CLOSED)
- Optimistic concurrency: a real bug was found and root-caused — generated CRUD writes went through **two redundant paths** that both touch the DB. The kernel-gateway path's payload builder iterates only DSL-declared fields, so the kernel-injected `version` column was never in it, and the DDL had `version BIGINT NOT NULL` with no default — every create 500'd before the second (entity-based) path ever ran. **Fix was schema-level, not kernel-level** (deliberately — didn't special-case `version` into the generic payload builder): `version BIGINT NOT NULL DEFAULT 0`. This produced a durable lesson, restated several times later in the track: **any kernel-injected synthetic column (audit fields, tenant_id, version, etc.) that isn't sourced from a DSL field must have a DB-level DEFAULT**, because the gateway-write path's payload is DSL-fields-only by construction.
- Live-verified the full conflict-detection flow: create succeeds with `version:0`; update without a version in the body still succeeds (back-compat auto-increment); update with the correct current version succeeds; update with a stale version → HTTP 409 `version_conflict`.
- Kernel-controlled CRUD gating (real `checkCrudPermission` + real `DefaultConceptGateway` enforcement, not allow-all) confirmed live later (see "Gap A" entry below) — found and fixed one related bug: the raw-JSON-map create/update overloads checked required-fields *before* checking permission, leaking schema info pre-auth via a 400 instead of a 403. Fixed by moving the permission check to the top of those overloads.

Commits: `0aa2497`, `2b8d637` (version-column fix), later `e14dc9c` (permission-check-ordering fix).

### Phase 4 — One renderer, richer (CLOSED, 3 commits)
- `c059f95`: prerequisite stabilization (in-memory storage conflict detection, unsupported-expression hard-deny, `JavaTimeModule` registration).
- `c6d953c`: tab grouping, conditional fields (`visibleWhen`/`enabledWhen`/`requiredWhen`), multi-column forms, enum badges (icon/hint, no color yet).
- `8431ad1`: textarea/number/email/tel/url field widgets.
- `7902ac3`: closed the 3 remaining gaps — **multi-level** (nested-object field editing via a new `objectSchema` manifest node + `createObjectInput` form), **enum-badge colors** (CSS classes the JS already emitted but had no styling for), **frame/shell** (collapsible side-nav, pure client-side, no config-driven theming infrastructure built).

### Phase 5 — Admin-in-app + internal Menus/Preferences (CLOSED, 1 commit + 1 live bug found+fixed)
- Manifest renderer grew a Menu-driven nav overlay (sourced from `workspace::Menu` rows, filtered by visibility/role) and a dedicated, category-grouped "My Preferences" panel scoped to the current user.
- **Live bug found the first time this path was ever exercised end-to-end**: `POST`/`PUT` on any built-in pack concept failed with `400 "Unknown entity for runtime support: WorkspaceMenu"`. Root cause: the generated service template passed the bare Java class name instead of the compiled-model alias-qualified name (`workspace::Menu`) into 9 different `runtimeSupport.*` calls — invisible for ordinary app-authored concepts (where the two names happen to match) but fatal for `::`-prefixed pack concepts. Fixed at all 9 call sites (root cause, not a workaround).

Committed as `54f1b26`.

### Phase 6 — Change-as-data, safe-additive fast path (CLOSED, 1 commit + 2 live bugs + later test backfill)
Marcelo deliberately scoped this to the safe-additive fast path, not a full generic diff engine (that stays permanently quarantined per the existing `MigrationAuthorityQuarantineAssertions` design).
- New Flyway *repeatable* migration `R__npdev_schema_additive_columns.sql` (idempotent `ADD COLUMN IF NOT EXISTS` for every non-bond field), plus manifest columns describing which are additive-eligible.
- `SchemaLifecycleExecutor.isSafeAdditiveChange(...)` classifies a fingerprint mismatch as safe (skip destructive recreate) only when every live-DB-vs-manifest diff is one of those additive columns, or the table doesn't exist yet.
- **2 live bugs found during a real generate→build→boot→insert→edit-model→regenerate→reboot cycle**: (1) an unqualified `DatabaseMetaData.getColumns` call also matched H2's own `information_schema` system views, polluting the diff; (2) skipping the destructive recreate wasn't sufficient on its own — the *versioned* V1 migration gets fully regenerated (and re-checksummed) from the model on every pass, so adding a column changed its checksum even though it never re-ran, failing Flyway's `validateOnMigrate`. Fixed with `flyway.repair()` immediately before `flyway.migrate()` on the safe-additive path.
- Verified live: a real row survived a nullable-column addition with no destructive drop logged, a second row used the new column, and a final reboot with matching fingerprints stayed clean.
- **Test backfill done separately** (`22899bf`): `SchemaLifecycleExecutorAdditiveChangeTest` (5 cases, real H2) + `SchemaRealizationEmitterAdditiveColumnsTest`. Also deleted the now-dead `FlywayEmitter.java`/tests (confirmed zero references outside its own tests), porting the one test (`PackBondEmitterTest`) that actually exercised bond/FK SQL onto the real emitter instead of losing that coverage.

Committed as `f411bee`, then `22899bf`.

### Phase 7 — Provenance + store + box view (CLOSED, full scope in one pass, 1 commit)
- **Provenance**: `BuildInfoEmitter` writes `npdev-build-info.properties` (version, commit, generator tag, build timestamp) — but only at *assembly* time, into the assembled FinalApp's tree, never into the generator's own deterministic output root (a pre-existing test forbids exactly this file appearing there).
- **Store**: `PackCatalogEmitter` scans `NPDevContract/packs/*/pack.json` and emits `npdev/store/pack-catalog.json`; `GeneratedPackCatalogController` serves it at `GET /api/admin/packs`, gated to the super-user role.
- **Box view**: `BoxManifestEmitter` emits `npdev/box/box-manifest.json` (one entry per persisted concept: fields, bonds, admin flag, truth level — always `"T2_GENERATED"` at generation time); `GeneratedBoxViewController` serves `GET /api/admin/box` and is **the only place truth level is ever bumped**, to `"T3_RUNS_LOCALLY"`, because successfully serving the response *is* the evidence the app runs locally. T4+ is deliberately never claimed anywhere.
- UI: two new synthetic super-user-only nav sections ("Store", "Box View"), built with plain DOM construction (no `innerHTML`, to avoid introducing a new XSS surface that no other code path in that file has).
- **One real bug found while verifying**: the runtime API-key mapping format is `key=tenant:actor:role` entries separated by `;`, not `,` — a session mistake, not a code defect, but worth remembering.

Committed as `5fbb4eb`.

**At this point the original 7-phase roadmap was complete.**

---

## 3. Post-roadmap additions: S0–S8 promotion enforcement + the multitenancy track

Two more substantial pieces of work happened *after* the 7 phases closed, both still on `beta1-vision-spine`.

### S0–S8 promotion-stage enforcement (committed `08b2341`)
Implements `project_vision`'s "truth classification never blocks creation, it only blocks false release claims" as real, gate-enforced behavior — the first place the T0–T6/S0–S8 doctrine is enforced rather than just documented or displayed.
- New **append-only** internal table `npdev_promotion_state` (deliberately not a single mutable "current stage" flag — a rejected promotion attempt is still permanently recorded, not silently dropped).
- `PromotionStateService`: stages can only advance one at a time (checked against the highest *accepted* stage in history, no skipping); `S5_TESTED`/`S6_EVIDENCE_BACKED`/`S7_RELEASE_APPROVED`/`S8_RELEASED` require non-blank evidence; `S7`/`S8` additionally require the `ADMIN` role — and a non-admin's attempt is still recorded as a `REJECTED` event rather than bounced before the service sees it, so "who tried to fake a release and got denied" is part of the permanent record.
- `PromotionController`: `GET /api/admin/promotion` (current stage + full history), `POST /api/admin/promotion/advance`.
- **Governance mechanism discovered along the way**: `NPDevRuntimeHost/src/main/resources/npdev/runtime-supported-controllers.json` is a default-deny allowlist that silently excludes any new controller/service from compilation unless explicitly listed — the new controller/service were silently compiled out (zero error, just a 404) until added. Treated as a living allowlist (unlike the immutable beta0 tag), so just extended it.
- Live-verified the full S0→S8 sequence on a real boot, including: stage-skip rejection, missing-evidence rejection, a non-admin's S7 rejection appearing in history with `outcome:REJECTED`, and the terminal-stage no-further-advance check at S8.

### Real tenant isolation for business CRUD (also `08b2341`, same commit)
This was originally deferred, then actually fixed the same day. Investigating the "tenant isolation is a no-op" symptom found a much deeper gap: generated business tables had **no `tenant_id` column at all**. Fixed: schema emitter adds it to every table; entity/service templates stamp it from the real caller context; `JdbcBusinessConceptStore` (previously accepting but never using a `tenantId` parameter) actually filters by it now. **A second, deeper bug found while live-verifying**: the kernel-gateway write path builds its payload from DSL-fields-only data and never read the `ConceptRecord`'s own dedicated `tenantId` component — so even with the SQL filters in place, every insert tried to write `tenant_id=NULL`. Same bug class as the `version` NOT NULL bug from Phase 3. Fixed by having the JDBC store explicitly read `record.tenantId()` before its generic per-field loop.

### Deep-analysis-driven multitenancy track, T1–T5 (CLOSED)
After the roadmap was "done," a deep-analysis pass found tenant isolation was still a half-system: data-isolated only on the generic-CRUD path, no tenant/identity lifecycle, riding on a statically-baked auth model. Marcelo asked to finish it properly. Five phases, each committed and live-verified separately:

- **T1** (`ebafff2`): sealed remaining CRUD-path data holes — `tenant_id DEFAULT 'default'` on both the create-table and additive paths (fixes a legacy-rows-become-invisible bug on in-place upgrade); ordinary `unique:true` fields became `(tenant_id, col)` composite-unique (two tenants can now reuse the same email/username); removed a leftover debug printline.
- **T2** (`5709c87`): closed the flow-driven persistence tenant back door — the capability-plugin persistence path had no tenant concept at all because it runs on a sandboxed thread where the normal request-context-based tenant lookup can't reach; fixed by stamping `tenantId` from the flow's own kernel-tracked state before dispatch.
- **T3** (`edb7320`): made the identity pack load-bearing — `IdentityAwareContextResolver` now actually queries `identity_users`/`identity_roles`/`identity_user_roles` and lets persisted roles override claim-roles when a match exists (supplement-with-fallback: absent tables or no match leaves claim-roles standing, so non-pack apps are unaffected).
- **T3b** (`fd5a96d`): found T3 was only half-applied — generated business CRUD has its *own*, separate context resolver that T3 never touched. Extracted a shared `IdentityRoleLookup` helper both resolvers now use.
- **T4** (`65602b6`): runtime tenant registry + lifecycle. `npdev_tenant` internal table, `TenantRegistryService` (create/list/enable/disable, fail-open by design — only an explicitly disabled tenant is denied), `TenantAdminController` (`/api/admin/tenants`, ADMIN-gated), `TenantStatusFilter` (one per-request chokepoint denying disabled tenants across every endpoint).
- **T5** (`5deee95`): runtime API-key credential issuance, no restart required. `npdev_api_credential` stores a SHA-256 hash only (raw key shown exactly once at issuance, then unrecoverable — confirmed via direct DB read). `CredentialRegistryService`/`CredentialAdminController` (`/api/admin/credentials`). Modified the generated, signature-checked `RuntimeApiKeyAuthFilter` to consult this as a fallback (confirmed safe: the strict-execution signature hashes the *regenerated* tree, so it verifies post-generation tampering, not template evolution).

**Governance decision that unblocked T4/T5 (Marcelo, 2026-06-20): HYBRID.** Keep the permission *model* (which roles can do what) signed/static; make tenant existence + membership (who's in which tenant with which role) live data. This is why `npdev_tenant` could move off the forbidden-future-scope table list while the permission grants themselves stayed in the signed `dev.permissions.json` — until the fix described in section 4 below.

---

## 4. This session's work: a real sample, and 6 more real bugs

After the roadmap and the multitenancy track were both done, the question became "are we ready to build sample apps to prove this?" The answer was: not quite, because the *existing* samples had never been re-verified against the new stack, and the answer turned out to be no on two counts.

### Sweep 1 — regression sweep before building anything new (commit `6ed4e71`)
1. **Reserved-column collision**: `restaurant-saas-multitenant`'s model declared its own `tenantId` reference field (a pre-platform-tenancy, app-modeled "which restaurant" reference), colliding with the platform's own new `tenant_id` column — confirmed live as a `CREATE TABLE` with the same column listed twice, invalid SQL with no diagnosable generation-time error. Fixed with a fail-fast generator validation (`SchemaRealizationEmitter.validateNoReservedColumnCollision`); swept every other sample (none affected); renamed the field to `tenantRef`.
2. **Cross-tenant bond write**: a bond/FK column's DB constraint only checks the target row *exists*, never that it belongs to the writer's own tenant. Confirmed live: tenant "acme" created a row whose bond field pointed at tenant "beta"'s private data, and it succeeded with 200. Fixed with `GeneratedCrudRuntimeSupport.enforceBondTargetTenant`, wired into both create and update — worded as "not found" rather than "forbidden" so a caller can never distinguish "doesn't exist" from "exists in another tenant."
3. Filter/where query-param tenant-bypass was checked and found **already safe** — no code change.

### Sweep 2 — actually updating the sample to demonstrate platform tenancy (commit `bcb8981`)
This is where the bulk of the further bug-finding happened, because it was the first time a real, permanent sample was pushed through the *entire* new stack end-to-end.
1. The sample's own automation had **never worked** — `generate-sample-app.ps1` requires `Input/db.definition.json`, which this sample never had. Added it (H2Local), and switched the runtime profile from `dev,step0,trial` to `dev,trial` (the `step0` profile silently overrides any real database with an ephemeral one).
2. The populate/verify scripts referenced the pre-rename field name and wrong route slugs, and didn't unwrap the generated list endpoint's paged `{content:[...]}` response. Fixed all three.
3. **A real, broadly-impacting datetime bug**: every generated service's entity→Map conversion (`mapFromEntity()`) used an `ObjectMapper` with `JavaTimeModule` registered but `WRITE_DATES_AS_TIMESTAMPS` left at its default `true` — so an `OffsetDateTime` field silently became a bare epoch-seconds `BigDecimal` by the time the kernel-gateway write path persisted it, failing at the JDBC layer for any `datetime` field. **Fixed in the generator template** (`service-base.mustache`) — affects every generated service in every sample with a datetime field. Found and fixed the identical defect in `JacksonJsonCodec` (a kernel adapter used for flow/event JSON round-trips) while tracing this.

### A focused 4-item follow-up plan, all resolved this session
1. **Wildcard permission grants** (commit `6cec117`) — the most significant fix. `RuntimeApiEmitter` previously emitted every permission grant with hardcoded `tenantId="dev"`, so a platform tenant created at runtime via `/api/admin/tenants` authenticated fine but got 403 on every generated CRUD call (no grant matched its tenantId). Fixed by emitting a blank/wildcard `tenantId` instead — correct because these grants are role-based *capability* checks ("can an admin/user create this concept type"), not data access; row-level isolation is a separate, already-enforced mechanism untouched by this change. Live-verified: a brand-new tenant now authenticates *and* uses CRUD immediately, while two brand-new tenants still can't read/list/bond-write into each other's data.
2. **Duplicate tenant-create status code** (commit `5cb0f3f`) — was 503 (conflating "already exists" with "DB is down"), now a proper 409 via a new `TenantAlreadyExistsException` distinguished by SQLState class "23" (standardized across H2/Postgres).
3. **Swept every other sample for the datetime exposure** — only `canonical-demo` also has `datetime` fields; it uses the `InMemory` engine (so the old bug would have silently corrupted data rather than throwing — worse, not better, but caught by the same fix). Regenerated and live-verified an `Appointment.scheduledAt` value round-trips correctly through create and a subsequent GET.
4. **"Dangling docker-postgres config block"** — investigated and **retracted**. It's a schema-*required* field, validated by `scripts/quality/run-sample-matrix-tests.ps1`, present identically in every sample checked. It's an intentional, currently-underused declaration for a stricter deployment path, not dead config. Nothing to fix.

`demonstrate-platform-tenancy.ps1` (new script, part of `restaurant-saas-multitenant`) now exercises the **full** lifecycle end to end, idempotently: create tenant → issue credential → authenticate → use CRUD with no hand-authored grant → row isolation → cross-tenant read (404) → cross-tenant bond-write (422) → disable (403) → enable (restored) → revoke (401).

---

## 5. Current state: no open bugs, the structural gap is now CLOSED

As of HEAD (post-Increment-4), there are no known open bugs. What remains, explicitly:

**Deliberately deferred (not bugs):**
- Whether/how to seal beta1 (immutable tag, like beta0) before opening a beta2 track — never decided, not urgent. §6 above suggests this is the natural point to revisit it; asked Marcelo directly (see the end of §10).

**Closed in the §5/§6 cleanup pass (all 4 of the small deferred items above, fixed in one sweep):**
1. **Removed** the 2 leftover `NPDEV-UPGRADE-MARKER` debug printlns in `RegistryCapabilityDispatcher`/`KernelRunner` (kernel compiles and full `:kernel:test` suite stays green).
2. **`Menu`'s `INTERNAL`-kind nav routing expanded**: `resolveMenuTarget()` in `business-ui-app.mustache` now also recognizes `"store"`, `"boxview"`/`"box-view"`, and `"promotion"` (gated on `state.isSuperUser`, same as the sections themselves), in addition to `"preferences"`. A Menu row can now route to any of the four synthetic admin panels.
3. **Flow-capability `query()`/`list()` reads are now tenant-scoped**, mirroring how T2 already tenant-scoped `save()`. `KernelRunner`'s capability-call dispatch now stamps the caller's tenant into the `(concept, criteria)` args for `query`/`list` operations the same way it stamps it into the entity map for `save` — a flow author gets row-scoped reads for free instead of having to remember a `tenantId` criterion themselves. New test: `KernelRunnerCapabilityPolicyTest#stampsCallerTenantIntoFlowDrivenPersistenceQueryCriteria`. `findById`/`delete` (by globally-unique UUID) intentionally remain untouched, as before.
4. **The missing-ADMIN-role rejection (S7/S8) is now browser-verified too.** Confirmed `POST /api/admin/promotion/advance` has no blanket admin gate (only `GET` does) — `PromotionStateService`'s own check is what rejects it, so any authenticated, non-admin caller can legitimately attempt it. `demonstrate-promotion-lifecycle.ps1` now issues a REAL non-admin (`roles=["USER"]`) credential via the existing T4/T5 admin API (`POST /api/admin/tenants` + `POST /api/admin/credentials`) and attempts an S7 advance with it directly over HTTP between the "evidence-backed" and "release" browser routines (promotion routine `02-release-and-terminal.json` was split into `02-evidence-backed.json` + `03-release-and-terminal.json` to make room for this step). The rejection is then asserted both via the HTTP response (400) and via the audit-history API, **and** the next browser routine asserts the resulting `missing_role` event is visible in the real rendered history table — so both the credential mechanics and the browser-rendered result are proven.

All 4 fixes were regenerated, rebooted, and regression-swept against both samples (all 6 demonstration scripts: `superuser-admin-console`'s general 8-routine suite, its schema-evolution demo, its promotion-lifecycle demo, and `restaurant-saas-multitenant`'s browser + HTTP demos) — zero regression.

**The structural gap that motivated this document — RESOLVED (§10, Increments 1–4):**

> Phases 2, 4, 5, 6, 7, and S0–S8 promotion were all verified live, then thrown away on scratch FinalApps with no permanent, checked-in proof. **This is no longer true.**

Updated coverage matrix:

| Feature area | Permanent sample? |
|---|---|
| Tenancy (T1–T5) | ✅ `restaurant-saas-multitenant` |
| Bonds | ✅ (multiple existing samples) |
| Identity/RBAC packs (`internal.tables=true`) | ✅ `superuser-admin-console` (Increment 2) |
| Workspace packs (Menu/Preferences UI) | ✅ `superuser-admin-console` (Increment 2) |
| Store / Box View / provenance | ✅ `superuser-admin-console` (Increment 2) |
| Schema evolution (Phase 6 safe-additive) | ✅ `superuser-admin-console` (Increment 3) |
| S0–S8 promotion lifecycle | ✅ `superuser-admin-console` (Increment 4), admin-role gate excepted (above) |

All of the above are also now **browser-verified**, not just HTTP-verified — a strictly stronger proof than what §6 originally asked for.

---

## 6. NEXT STEP — building samples — DONE (kept for history; see §10 for what actually shipped)

Marcelo confirmed: build sample apps to probe every point above and prove it's real and functional, not just unit-tested. The agreed approach (now complete, via §10's Increments 2–4):

1. ✅ **One new (or expanded) permanent sample with `internal.tables=true`** exercising the super-user path for real: identity pack (User/Role/UserRole), workspace pack (Menu/Preferences), Store, and Box View — all in one checked-in `NPDevSamples` entry, mirroring how `restaurant-saas-multitenant` anchors tenancy today. This should include PowerShell automation scripts (generate/run/populate/verify/demonstrate, following the exact pattern established for the restaurant sample) so it's re-runnable and idempotent, not a one-off curl session. *(Shipped as Increment 2 — and went further than asked: browser-verified, not just HTTP.)*
2. ✅ **An S0→S8 promotion-lifecycle demonstration script** on that same sample, mirroring `demonstrate-platform-tenancy.ps1`'s structure: advance through stages, show the skip-rejection, the missing-evidence rejection, and pull the full audit history. *(Shipped as Increment 4, through a real new admin panel rather than HTTP — the role-gated S7/S8 rejection-then-acceptance is the one piece still only HTTP-proven, see §5.)*
3. ✅ **A real schema-evolution demonstration**: generate a sample, populate it, edit the model to add a nullable column, regenerate, reboot, and confirm (a) no data loss, (b) no destructive table recreate logged, (c) the new column is usable. *(Shipped as Increment 3.)*

**Lesson confirmed, not just predicted**: this kind of pass on *real*, multi-feature, permanent samples (not synthetic single-feature scratch models) is what caught essentially every bug listed in section 4 — and it kept paying off through Increments 2–4 too (the lookup-picker/modal bug in Increment 2 specifically).

The coverage matrix is now fully green with permanent samples — the roadmap is genuinely, demonstrably done, not just code-complete. **This is the natural point to revisit the beta1-seal decision** (deferred in §5 above).

---

## 7. Operational notes for whoever continues this (gotchas worth not re-learning)

- **`sync-runtimehost-libs.ps1 -BuildLocalJars`** must be re-run after any kernel/generator source change before a sample's `gradlew bootRun` will pick it up — it stages jars outside the repo at `<repo-parent>\NPDev_General__OutsideRepo\runtimehost-libs`. The assembled app's `build.gradle` depends on that directory as a flat `fileTree`, which means **no transitive dependency resolution** — a brand-new third-party dependency added to a kernel module (e.g. this session's `jackson-datatype-jsr310` addition to the `json-jackson` adapter) must already be on the classpath via some *other* declared dependency (e.g. Spring Boot's own starters), or it silently won't be available at runtime even though the jar with the fix compiled correctly.
- **`db.definition.json` is the actual authority for the database engine**, not `config.json`'s top-level `database` block (that block is a separate, schema-required declaration for a stricter/production deployment path that the trial/dev scripts don't consume). `generate-sample-app.ps1` will hard-fail if `Input/db.definition.json` is missing.
- **The `step0` Spring profile silently overrides any real database with an ephemeral one** — always use `dev,trial` (not `dev,step0,trial`) when a sample's `db.definition.json` declares a real physical engine (H2Local/H2Server/Postgres) and you actually want to exercise it.
- **PowerShell tool calls in this environment do not persist shell state between calls** (only working directory persists) — every variable, including ones holding API keys/credentials from a previous call, must be re-established within the same call if it's needed across multiple HTTP requests. This caused real confusion earlier in the session (objects appearing as `null` for no apparent reason) before being correctly diagnosed.
- **`powershell.exe` vs `pwsh.exe`**: invoking a script with a plain `powershell -NoProfile ...` launches the legacy Windows PowerShell 5.1, which doesn't have `$IsWindows` and other PowerShell-Core-only automatic variables. Scripts written assuming PowerShell 7+ (like `sync-runtimehost-libs.ps1`) must be invoked via `pwsh -NoProfile ...` explicitly.
- **Generated list endpoints return a paged wrapper `{content:[...], page, size, totalElements, ...}`**, except the one observed case of `/api/tenants` (the bare Tenant concept route) which returned a plain array in this session's testing — don't assume one shape; check, or handle both (see `Get-Rows` helper added to `demonstrate-platform-tenancy.ps1`).
- **Generated route slugs are snake_case derived from the table name**, not the bare lowercased concept name — e.g. `StaffMember` → `/api/staff_members`, not `/api/staffmembers`. Several of this session's bugs were exactly this mismatch in hand-written scripts.
- **Any kernel-injected synthetic column not sourced from a DSL field needs a DB-level DEFAULT.** This exact bug class recurred three times across the whole track (`version`, then `tenant_id` via the schema path, then `tenant_id` via the gateway's own `ConceptRecord`-vs-`dbRecord()` mismatch) — if a fourth synthetic column is ever added, check this first.
- Full kernel/generator/expression-cel/json-jackson test suites are green as of HEAD `5cb0f3f`. One observed `:generator:test` flake (`TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest`, a real-packaged-app-boot test) timed out once under heavy concurrent load from this session's own manual boots — reran in isolation and passed; not a real regression, but worth knowing this specific test is sensitive to machine load if it flakes again.

---

## 8. Quick reference: commit chain for this entire track

```
fd7f8c8 (base, cut from beta0-no-false-green-release-hardening)
76f780d  Phase 1+2 vision spine: resolution pipeline + identity/workspace built-in packs
039ec3c  Business UI CRUD: write grants, type coercion, reference picker (Marcelo's WIP)
cbb0233  editor node_modules junction script (Marcelo's WIP)
a580e91  Built-in pack composition + internal.tables gate
743af33  auth.mode wire
a871b4d  alias label fix
e5806fd  Super-user admin UI
0aa2497  Optimistic concurrency + kernel-controlled CRUD gating
2b8d637  Fix NOT NULL violation on generated version column
c059f95  In-memory storage fixes (Phase 4 prerequisite)
c6d953c  Tabs/conditionals/badges (Phase 4)
2afc19c  script fix
8431ad1  Widget types (Phase 4)
7902ac3  Multi-level/badge-colors/nav shell — Phase 4 CLOSED
e14dc9c  Gap A: permission-check-first fix
54f1b26  Phase 5: Menu nav overlay, My Preferences panel, entityName/conceptName bug fix
f411bee  Phase 6: safe-additive schema fast path
22899bf  Phase 6 test backfill + remove dead FlywayEmitter
5fbb4eb  Phase 7: provenance, pack store, box view — ORIGINAL 7-PHASE ROADMAP COMPLETE
08b2341  Real tenant isolation for business CRUD + S0-S8 promotion-stage enforcement
ebafff2  Tenancy T1: seal CRUD-path data-isolation holes
5709c87  Tenancy T2: close the flow-driven persistence tenant back door
edb7320  Tenancy T3: make the identity pack load-bearing for authorization
fd5a96d  Tenancy T3b: apply identity roles to the generated-CRUD context path too
65602b6  Tenancy T4: runtime tenant registry + lifecycle with disable enforcement
5deee95  Tenancy T5: runtime API-key credential issuance, no restart required — MULTITENANCY TRACK CLOSED
6ed4e71  Two real cross-tenant regressions found by sample-regression sweep, both fixed
bcb8981  Update restaurant-saas-multitenant for platform tenancy; fix datetime serialization bug found doing so
6cec117  Fix permission grants to use wildcard tenantId, not hardcoded "dev"
5cb0f3f  Fix duplicate platform tenant-create to return 409, not 503   <-- HEAD, current
```

---

## 9. One-paragraph resume-from-here summary (superseded by §10 — kept for history)

The beta1-vision-spine track is feature-complete: all 7 originally-scoped phases, the S0–S8 promotion gate, and a 5-phase multitenancy hardening track are done, committed, and individually live-verified. This session additionally found and fixed 6 more real bugs (2 in a pre-build regression sweep, 2 while updating a sample to actually demonstrate the new tenancy features, 2 more from a focused 4-item follow-up plan) — none remain open. The one thing genuinely missing is **permanent proof**: most phases were verified on scratch apps that no longer exist. The next, already-agreed step is to build (or expand) real, checked-in `NPDevSamples` entries — with the same kind of re-runnable PowerShell automation `restaurant-saas-multitenant` now has — that exercise identity/workspace packs, the store/box-view/provenance surfaces, and the S0–S8 promotion lifecycle, plus a real schema-evolution (add-a-column) demonstration. Building those samples is very likely to surface more real bugs, the way it did for tenancy, and that should be treated as expected and valuable, not a sign something is wrong.

**Update (current, see §10 for full detail):** that next step is done. Four browser-verification increments shipped on `superuser-admin-console` (identity/workspace/Store/Box-View, a lookup-picker modal bug found+fixed+swept, schema-evolution, and a new Promotion admin panel for the S0–S8 lifecycle) plus the restaurant sample's own browser harness — all green, zero regression. The coverage matrix in §5 is now fully ✅. What's left is the small deferred list in §5 (debug printlns, Menu INTERNAL routing, flow query/list tenant scoping, the admin-role-gate browser gap, and the now-ripe beta1-seal decision) — none of it blocking, none of it urgent.

---

## 10. Browser-based sample verification (ScrapForAI) — methodology ESTABLISHED, Increments 1–4 DONE

A real-browser verification layer was added to the sample methodology, using
Marcelo's `D:\WorkSpace\ScrapForAILegacy` exploration runner (referenced in
place, not vendored). It drives a booted generated app's vanilla-JS UI in a
headless browser and asserts on structured evidence (console/page/network
errors, screenshots) — the surfaces the HTTP-only `demonstrate-*` scripts
cannot reach. Full methodology + gotchas: `docs/beta/sample-browser-verification-methodology.md`.

**Increment 1 (de-risk the harness on the known-good restaurant sample) — DONE, green.**
- New reusable harness: `NPDevSamples/scripts/browser/scrapforai-harness.ps1`.
- 5 committed routines under `NPDevSamples/scripts/restaurant-saas-multitenant/browser-routines/`
  and an orchestrator `demonstrate-browser.ps1`, mirroring `demonstrate-platform-tenancy.ps1`.
- Verified live: regenerated the restaurant sample (it now emits the business UI
  under the `npdev-generated` mount, served at `/npdev-business-ui/`), booted it,
  and ran all 5 routines **green with 0 console/page/external errors**: super-user
  nav (Admin/Store/Box View visible via `api-dev`), the Store pack-catalog panel,
  the Box View panel (incl. the T3_RUNS_LOCALLY truth bump), a business-concept
  grid, and **a Tenant created through the rendered form** (the modal closes only
  on a successful server write, so that proves the UI write path).
- Evidence summaries: `Output/RunOutput/browser/*.json`; screenshots outside the
  repo under `D:\WorkSpace\NPDev\Build\scrapforai-artifacts\<sample>\`.

**Findings worth keeping (details in the methodology doc):**
- ScrapForAI's `inspect-dom` (and any compiled-function `page.evaluate`) is broken
  under tsx — `ReferenceError: __name is not defined` (esbuild `keepNames`). It
  does **not** affect routine actions (Playwright locators) or string `evaluate`
  steps. Discover selectors via `collect domText`/string `evaluate` until it is
  fixed in ScrapForAILegacy (run built `dist` via node, or disable `keepNames`).
- Talk to the scraper over `127.0.0.1` (IPv4 bind); set `ALLOWED_TARGET_ORIGINS`
  to the app origin (SSRF allowlist); locators are strict (single-match selectors,
  use the renderer's `#concept-<Name>` / `a[href="#concept-..."]` hooks);
  `assertTextContains` sees CSS-transformed `innerText` ("Admin" → "ADMIN").

**Increment 2 (headline super-user sample) — DONE, green, and it found a real bug.**
New permanent sample `NPDevSamples/superuser-admin-console` (`internal.tables=true`,
H2Local, port 8094): composed identity (User/Role/UserRole) + workspace
(Menu/Preference) packs alongside two ordinary business concepts (Project, Note —
Note.projectRef is a reference field, deliberately included to exercise the lookup
widget on plain business CRUD too). 8 committed routines under
`NPDevSamples/scripts/superuser-admin-console/browser-routines/` +
`demonstrate-browser.ps1` reusing the Increment-1 harness unchanged: super-user nav
(identity/workspace/Store/Box View all present), Store panel, Box View panel
(T3_RUNS_LOCALLY bump), Project+Note create-through-UI with a lookup reference,
Identity Role+User create, Identity UserRole **bond-link via the lookup picker**
(proves pack composition's reference write path end-to-end through the rendered
UI), Workspace Menu create, My Preferences add. All 8 green, 0 console/page/
external errors.

**Real generator bug found and fixed**: opening a reference field's lookup picker
(the "Browse…" button on any `reference`-typed field) inside a create/edit form
**destroyed the form** — `openModal()`/`closeModal()` in `business-ui-app.mustache`
unconditionally wipe `#modalRoot.innerHTML`, and the picker dialog reused the same
`openModal()`/`el.modalRoot`, so opening it wiped out the parent form's own modal
the instant it appeared (confirmed live: `document.querySelectorAll("#modalRoot
form").length` was `0` right after clicking Browse…). This is exactly the class of
bug the methodology exists to catch — invisible to HTTP tests, and easy to miss
even reading the code, only obvious once you actually click "Browse…" inside a
create form in a real browser. **Why it went undetected in Increment 1**: the
restaurant sample's only reference field (`StaffMember.tenantRef`) was never
exercised through a create-form routine; this is the first sample to drive a
lookup picker through the UI. **Fix** (generator templates, not generated output):
added a second top-level root `#pickerModalRoot` (`business-ui-index.mustache`),
refactored `openModal`/`closeModal` into root-parameterized `openModalInto`/
`closeModalIn` (`business-ui-app.mustache`), and pointed the picker dialog's
open/close at `el.pickerModalRoot` instead of `el.modalRoot` — so the picker now
stacks on top of (rather than replacing) the parent form's modal. Regenerated,
rebooted, re-verified: all 8 routines green afterward, including both
reference-field routines (Note→Project, UserRole→User/Role). Template-only change,
consumed at generation time — no kernel/runtimehost rebuild or libs resync needed.
**Regression sweep against restaurant-saas-multitenant — DONE, clean.** Regenerated
restaurant with the fix, rebooted, and re-ran both existing demonstrations:
`demonstrate-browser.ps1` (all 5 routines still green, 0 console/page/external
errors) and the HTTP `demonstrate-platform-tenancy.ps1` (full create/issue/
use-CRUD/isolate/disable/enable/revoke lifecycle still green). Zero regression —
confirms the fix was purely additive as expected.

**Increment 3 (schema-evolution browser demo) — DONE, green.** Per §6 item 3 ("the
most load-bearing un-sample-tested logic in the spine"): a new self-contained
script, `NPDevSamples/scripts/superuser-admin-console/demonstrate-schema-evolution.ps1`,
owns the full lifecycle itself (unlike the other `demonstrate-*` scripts, which
assume an already-running app) because it must interleave boot/stop/regenerate
within one run:
1. Deletes the sample's persisted H2 file
   (`D:\WorkSpace\NPDev\Build\databases\superuser-admin-console\*.mv.db` — resolved
   by `UserDatabaseDefinitionLoader`, lives outside the sample tree entirely, so
   regeneration never touches it) so every run starts from a guaranteed-fresh
   schema, independent of leftover state.
2. Generates + boots v1, asserts the boot log shows a fresh-schema init.
3. **Populates one row through the real rendered UI** (`evo-01-populate-before.json`
   — the row whose survival is the data-loss proof).
4. Stops the app, reads `Input/model.json`, adds a nullable `Project.internalCode`
   field via an in-memory JSON mutation (not a hand-maintained duplicate model file
   — avoids drift), writes it, regenerates (v2), reboots.
5. Asserts the v2 boot log contains `"skipping destructive recreation"` and does
   **not** contain `"NPDev destructive schema recreation"` — `SchemaLifecycleExecutor`
   (`NPDevRuntimeHost/.../db/SchemaLifecycleExecutor.java`) took the safe-additive
   path, not a destructive table recreate.
6. **Browser-verifies all three asks** (`evo-02-verify-after.json`): (a) the
   pre-evolution row still renders (no data loss); (b) already proven by the log
   assertion above; (c) opens that SAME pre-evolution row's edit form, confirms the
   new field renders on a record that predates it, fills+saves it, then does a full
   page **reload** (forcing a real server re-fetch, not client cache) and confirms
   the value renders in the grid — the new column is genuinely usable, not just
   accepted-and-silently-dropped.
7. **Always restores** `Input/model.json` to its original byte-exact content via
   `try`/`finally` (restore uses the raw original text, not a re-serialized
   round-trip, so it's byte-perfect regardless of JSON formatting differences) —
   the checked-in v1 model is never left mutated, even on failure.

All green: v1 fresh-init confirmed, v2 safe-additive confirmed (not destructive),
both browser routines passed with 0 console/page/external errors. One real bug hit
and fixed during authoring (script-only, not generator): `Start-Process` with a
multi-element `-ArgumentList` array silently mis-split the `--args="--spring.
profiles.active=... --server.port=..."` value at its embedded space when launching
`gradlew.bat` via cmd.exe, breaking the boot. Fixed by passing one pre-quoted
string instead (matching `run-sample-app.ps1`'s working invocation).

**Increment 4 (S0–S8 promotion-lifecycle browser demo) — DONE, green.** Marcelo
chose option (b): add a real "Promotion" admin panel to the generated business UI
first, mirroring exactly how Store/Box View were added (Phase 7), then browser-
verify through it — keeping Increment 4 consistent with the rest of the
methodology rather than falling back to HTTP-only.

**New generator feature**: a third super-user-only synthetic panel,
`PROMOTION_SECTION` (`business-ui-app.mustache`), alongside `STORE_SECTION`/
`BOX_VIEW_SECTION` — same pattern: nav link gated on `state.isSuperUser`, a
client-side-only section (no manifest/`BusinessUiEmitter` changes needed, exactly
like Store/Box View). Renders the current stage, an Advance control (a `<select>`
of the 9 `PROMOTION_STAGES` literals + an evidence input + button calling
`POST /api/admin/promotion/advance`), and the full audit history table (mixed
ACCEPTED/REJECTED rows, badge-colored). A rejected advance still gets recorded
server-side even though the HTTP call itself returns 400, so `advancePromotion()`
always reloads the history in a `finally`, regardless of outcome.
`PromotionController`/`PromotionStateService` were already in the
default-deny `runtime-supported-controllers.json` allowlist from the original S0–S8
work, so no allowlist change was needed.

**New permanent demonstration**:
`NPDevSamples/scripts/superuser-admin-console/demonstrate-promotion-lifecycle.ps1`
+ two routines (`browser-routines/promotion/01-rejections-and-early-stages.json`,
`02-release-and-terminal.json`). Like the schema-evolution script, it owns the app
lifecycle itself and deletes the persisted database first — `currentStage` is
computed server-side from the full ACCEPTED-event history, not a stored flag, so a
fresh DB deterministically starts at `S0_IDEA`. Both routines green, proving
through the real rendered panel: a stage-skip rejection, a missing-evidence
rejection, the full successful S1→S8 advance chain (with evidence from S5 on), the
terminal-stage re-skip rejection (no further advance past S8), and the mixed
accepted/rejected audit table. **Not covered** (documented, not a gap): the
missing-ADMIN-role rejection, because this sample's trial-mode `api-dev` principal
always carries ADMIN — that gate was already live-verified via a hand-crafted
non-admin API key earlier in this track (§3).

**Two real things found while building this (both fixed):**
1. **A latent routine cross-contamination bug**, caught before it could bite:
   the Increment-3 `evo-*.json` routines lived in the same flat
   `browser-routines/` folder that `demonstrate-browser.ps1` globs with
   `Get-ChildItem -Filter "*.json"` — a future run of the general demonstration
   against a normal (non-evolved) app would have picked up `evo-02` and failed on
   a field that only exists after the schema-evolution mutation. Fixed by moving
   the schema-evolution and promotion routines into their own subfolders
   (`browser-routines/schema-evolution/`, `browser-routines/promotion/`) —
   `Get-ChildItem` without `-Recurse` doesn't descend into them, so the general
   glob is naturally safe again.
2. **Chrome logs a failed `fetch()`'s HTTP status to the console as an `error`**
   even when application code catches and handles it correctly (`"Failed to load
   resource: the server responded with a status of 400 ()"`) — so a routine that
   *deliberately* exercises a rejection path will always show 1+ "console errors"
   under the harness's normal policy, a false positive. Added
   `Assert-RoutineGreen -AllowConsoleErrorSubstrings @(...)` to the harness: an
   opt-in allowlist of expected substrings, used only by the two promotion
   routines (which intentionally trigger 400s) — every other routine keeps the
   full-strength default policy.

**Regression-swept** both other samples after the template change: restaurant
(`demonstrate-browser.ps1` 5/5 green + `demonstrate-platform-tenancy.ps1` full
lifecycle green) and superuser-admin-console's own general
`demonstrate-browser.ps1` (8/8 still green). Zero regression.

**Cleanup pass: all 4 small deferred items from §5 closed.** After Increment 4,
Marcelo asked to fix the remaining deferred items rather than leave them open.
Detail is in §5's "Closed in the cleanup pass" entry; summary: removed 2 debug
printlns, expanded Menu `INTERNAL`-routing to cover Store/Box View/Promotion,
tenant-scoped flow-driven `query()`/`list()` reads (mirroring T2's `save()` fix,
new kernel test added), and closed the missing-ADMIN-role gap on
`superuser-admin-console` by issuing a real non-admin credential over HTTP and
asserting the rejection through the browser-rendered history table. Resynced
`runtimehost-libs` (kernel source changed), regenerated, and regression-swept all
6 demonstration scripts across both samples — zero regression.

**Open question for Marcelo**: with the coverage matrix in §5 now fully green and
all deferred items closed, is this the point to seal `beta1-vision-spine` with an
immutable tag (mirroring how `beta0-no-false-green-release-hardening` was sealed,
per `maturity_roadmap`) before opening a `beta2` track? Not urgent, but the
condition §6 named for revisiting it ("once that coverage matrix is fully green")
is now met.
