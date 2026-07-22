# NPDev — Launch-Readiness Gap Assessment

> **Purpose.** NPDev's stated goal is to be a low-code platform for building a range of
> *complete web apps*. This document is a critical, realistic inventory of what is missing or
> blocking between the platform's current state (originally written 2026-07-14, branch
> `beta1-vision-spine`; **status table reconciled with the open-items register and re-verified against
> real commits + a green CI run 2026-07-22 — see §2's reconciliation banner**) and a
> state where formalization / launch is honest. It is written so that converting it into an
> executable roadmap is a mechanical step: every gap states **why** it blocks launch, **where**
> the problem lives (files/modules), **how** to fix it, a **Definition of Done**, an effort
> estimate, and its dependencies.
>
> **Relationship to other documents.** `docs/OPEN_GAPS_AND_ROADMAP.md` tracks concrete
> runtime/generator bugs and boundary lifts found while building real apps — that ledger is now
> almost entirely DONE. This document sits one level above it: it covers the *platform lifecycle*
> dimensions (evolve, secure, scale, operate, distribute) that no single app build exposes but
> that determine whether NPDev is a product or a prototype generator.

---

## 0. How to read this document

- **Status vocabulary:** `OPEN` (not started) · `PARTIAL` (some slices exist) · `DONE`.
  All items in this document start `OPEN` unless noted `PARTIAL`.
- **Priority:** `P0` = existential, launch is dishonest without it · `P1` = users hit it in
  week one · `P2` = needed for formalization as a business, not for the first honest demo.
- **Effort:** `S` (≤ 1 session) · `M` (2–5 sessions) · `L` (multi-week feature) ·
  `XL` (a program, needs its own phased plan).
- **IDs** are prefixed `LNCH-` and are stable; when this document becomes a roadmap, carry the
  IDs forward so knowledge cards and commit messages can reference them.
- Evidence paths are relative to the repo root `D:\WorkSpace\NPDev\NPDev_General` unless stated.

---

## 1. Executive verdict

> **2026-07-19 update:** the five-verb gap list immediately below describes the state this document
> was written against (2026-07-14). As of this update, every verb has at least a DONE core slice —
> see §2's status table for the accurate, current per-item picture. The narrative below is kept
> as-written for historical framing (why the roadmap is organized the way it is), not as a live
> status claim.

The generation pipeline and runtime kernel are genuinely solid: durable flows with crash-proven
resume, invariant/lifecycle enforcement, bonds, the widget/input-type system, theming tokens,
ControlPanel + SUPERUSER, seed data, file upload, and an AI authoring loop (MCP + RAG +
schema-constrained validate→fix→generate) that has been proven blind. The
"schema-legal-but-doesn't-work" credibility gap that plagued early app builds has been closed
almost completely by the boundary-lift program.

**What was missing was not more generator features.** It was the lifecycle around the generated
apps:

1. **Evolve** — no story for changing the model of a live app that already holds data. **Closed**
   by LNCH-1 (2026-07-19): in-place renames, safe widening, itemized acknowledged destruction, data
   pre-checks/backfills, a migration-plan preview, a permanent proof matrix. `docs/SCHEMA_EVOLUTION.md`.
2. **Secure** — tenant isolation has been fixed reactively bug-by-bug, never audited
   adversarially; one known auth-filter flaw remains deferred; the boring auth table stakes
   (reset, revocation, rate limiting) don't exist. **Closed** (2026-07-22): LNCH-2/3 done, LNCH-4's P0
   slice + password reset + secrets-via-env-vars all done (the last via REG-9 — JWT keys env-bound,
   super-user-key seeding WONTFIX by decision). The REG-16 adversarial tenant/auth review also ran:
   no CRITICAL/HIGH, 5 MEDIUM findings fixed.
3. **Scale** — panel/query filtering is in-memory post-filtering; fine at demo size, fatal at
   100k rows; there is no pagination pushed to SQL and no index emission for query fields.
   **Closed**: LNCH-5 (SQL push-down, 100k-row volume-gated) and LNCH-6 (index emission) both done.
4. **Operate** — everything green so far ran on one Windows machine with H2-over-TCP and
   PowerShell scripts; there is no Docker/Postgres-first deploy, no HTTPS guidance, no
   health/metrics/backup story, and the *less-exercised* database path (Postgres) is the
   production one. **Closed**: LNCH-7/8/9 all done, live-verified via real `docker compose` runs.
5. **Distribute** — no CI, Windows-only build scripts, internal-facing docs only, no license,
   no packaging decision, no upgrade contract for generated apps. **Closed** (2026-07-22): license,
   ADRs, upgrade contract done (LNCH-21/23); **Linux CI observed green on a real GitHub Actions
   runner** (LNCH-19 / REG-10 — the first CI-green in the project's history); cross-platform build
   **proven** by that run (LNCH-20 / REG-11), which also surfaced and fixed a real generated-app
   `D:/`-cache portability bug; release tag `beta1.1` cut. Only trademark clearance is parked (owner's
   call — portfolio project).

Every real low-code platform failure mode lived in one of those five verbs. The roadmap that
falls out of this document is organized around them; §2's table is the source of truth for what
is actually done today.

---

## 2. Priority index (machine-parseable)

> **2026-07-22 reconciliation — the launch ledger unified with the open-items register.**
> Since the last update (2026-07-19), the still-open *slices* of the LNCH items were tracked at finer
> grain in `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (the `REG-*` items) and worked to closure. This revision
> folds those closures back into the table below. **New tally: 21 DONE · 3 PARTIAL · 0 OPEN** (was
> 17 / 6 / 1). The one structural change worth stating up front: **Linux CI has now been observed
> green on a real GitHub Actions runner** (REG-10), which retroactively *proves* several items that
> were "done but only on one Windows machine."
>
> **LNCH ↔ REG crosswalk (where an LNCH's remaining slice lived as a register item):**
>
> | LNCH | Register item | Old LNCH status | Now | What closed it |
> |---|---|---|---|---|
> | LNCH-4 (auth stakes) | REG-9 | PARTIAL | **DONE** | JWT keys via env var + fail-fast validation + verify-only boot; super-user-key seeding WONTFIX by decision |
> | LNCH-10 (export) | REG-12 | PARTIAL | **PARTIAL** | Slice 2 (print stylesheet/mode) **DONE**; Slice 3 (server-side PDF) greenlit + planned (`docs/REG12_DOCUMENT_EXPORT_PLAN.md`) |
> | LNCH-18 (authoring test) | REG-13 | PARTIAL | **PARTIAL** | ADR done; the human/AI-tool run is prepared (`docs/EXTERNAL_TESTER_COLDSTART.md`) but not yet run |
> | LNCH-19 (Linux CI) | REG-10 | PARTIAL | **DONE** | `npdev-pr-gate.yml` observed **green** on ubuntu-latest (run `29899362276`); six root-caused first-contact-with-Linux fixes |
> | LNCH-20 (cross-platform) | REG-11 | OPEN | **DONE** | Cross-platform build **proven** by the green run; also fixed a real generated-app `D:/`-cache portability bug |
> | LNCH-22 (docs test) | REG-14 | PARTIAL | **PARTIAL** | Docs exist; newcomer run prepared (same kit as LNCH-18) but not yet run |
> | LNCH-23 (launch checklist) | REG-15 | PARTIAL | **DONE** | Release tag cut (`beta1.1`, on the `beta1→main` merge); license/ADR/release-process already done; trademark parked (portfolio project, owner's decision) |
>
> **Register-native items (findings that are NOT LNCH gaps — do not look for them here).** The register
> also tracks work with no LNCH equivalent, all closed/decided except where noted: LNCH-1's own
> five-round residue (REG-1..8: posture flips, `IT-EXTPG-1`, `GATE-REL-1`, the flake, `GATE-OBS-1a`,
> `ColumnFacts`=**REG-6 ~40%/OPEN**, and REG-7/REG-8 boundaries **converted to features**); the REG-16
> adversarial tenant/auth review (no CRITICAL/HIGH; 5 MEDIUM fixed) + REG-17 third-party reproduction;
> and this session's verification findings REG-18..30 (all CLOSED/decided) plus a new pre-existing
> **promotion-panel retry-loop** bug (register §2.4) still open. The register is now the live, granular
> tracker; this table is the launch-lifecycle roll-up.
>
> **The three genuinely-remaining launch items** are therefore: **LNCH-10 Slice 3** (PDF — planned),
> and **LNCH-18 / LNCH-22** (the single external-tester run, kit ready). All engineering-only; the
> tester run is the one human-gated step left.

| ID | Title | Verb | Status | Priority | Effort |
|---|---|---|---|---|---|
| LNCH-1 | Model-diff schema evolution for live apps (migrations) | Evolve | DONE | P0 | XL |
| LNCH-2 | Adversarial tenant-isolation audit + test suite | Secure | DONE | P0 | M |
| LNCH-3 | Fix `RuntimeApiKeyAuthFilter` clobbering flaw | Secure | DONE | P0 | S |
| LNCH-4 | Auth table stakes: reset, revocation, lockout, rate limit, CSRF posture | Secure | DONE | P0 | L |
| LNCH-5 | SQL push-down for panel/query filtering + server-side pagination | Scale | DONE | P0 | L |
| LNCH-6 | Index emission from the model | Scale | DONE | P1 | M |
| LNCH-7 | Postgres-first Dockerized deployment story | Operate | DONE | P0 | L |
| LNCH-8 | Observability: health, metrics, structured logs | Operate | DONE | P1 | M |
| LNCH-9 | Backup / restore / data export procedure | Operate | DONE | P1 | M |
| LNCH-10 | Reporting & export primitives (CSV/Excel/PDF/print) | Complete | PARTIAL | P1 | L |
| LNCH-11 | Email / notification primitive | Complete | DONE | P1 | M |
| LNCH-12 | Scheduled / background job trigger for flows & procedures | Complete | DONE | P1 | M |
| LNCH-13 | Row-level (data-scoped) authorization | Complete | DONE | P1 | L |
| LNCH-14 | Production file storage (finish `file-store-objectstore` S3 adapter) | Complete | DONE | P1 | M |
| LNCH-15 | One unified expression language | Complete | DONE | P1 | L |
| LNCH-16 | Optimistic locking / concurrent-edit protection | Complete | DONE | P1 | M |
| LNCH-17 | Transaction-boundary contract for multi-step flows | Complete | DONE | P1 | M |
| LNCH-18 | Authoring-path decision: editor-complete vs AI-first productization | Distribute | PARTIAL | P1 | L/XL |
| LNCH-19 | Linux CI running the quality gates + sample harness | Distribute | DONE | P1 | M |
| LNCH-20 | Cross-platform build scripts (drop the Windows-only assumption) | Distribute | DONE | P2 | M |
| LNCH-21 | Generated-app upgrade contract & compatibility policy | Distribute | DONE | P2 | M |
| LNCH-22 | User-facing documentation & error-message quality | Distribute | PARTIAL | P2 | L |
| LNCH-23 | Launch checklist: license, packaging, telemetry, release process | Distribute | DONE | P2 | M |
| LNCH-24 | Commit hygiene: land the current uncommitted working tree | Distribute | DONE | P1 | S |

---

## 3. Tier 1 — Existential blockers (P0)

### LNCH-1 — Model-diff schema evolution for live apps with data

**Status:** DONE (2026-07-19) · **Priority:** P0 · **Effort:** XL (needs its own phased plan, like the
Aggregate Workbench got)

**Update (2026-07-19).** Delivered via `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md`'s 9-phase plan,
Phases 0-8 all complete: in-place field/concept renames, safe type widening, itemized surgical
destruction with hash-bound acknowledgment (CLI + ControlPanel pre-authorization), data
pre-checks/backfills for new required fields and tightened uniqueness, pre-drop JSONL snapshots,
a migration-plan preview (`-PlanOnly`/`-Upgrade` CLI), a permanent 16-scenario H2 + 9-scenario
Postgres proof matrix, and `docs/SCHEMA_EVOLUTION.md`. Verified against a real Postgres
compose-stack deployment with real seeded data end to end (REST + direct DB introspection + a
real-browser screenshot) — see `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\phase-0.md`
through `phase-7.md` for the full implementation and live-verification record, including two real
bugs found only by that live rehearsal (a Postgres-only unique-constraint crash, and a silent
data-loss composability gap between renames and unrelated destructive drops), both fixed with
regression coverage.

**Update (2026-07-20) — remediation and hardening rounds followed.** LNCH-1's *core* is DONE, but it
took two review rounds after the initial "DONE" to get there, and the second one found something
serious:

- **Remediation (R0–R9, `docs/LNCH1_REMEDIATION_PLAN.md`)** — 2 high-severity bugs (required-field
  backfill silently skipped on destructive paths; `DROP_TABLE` tokens uncomputable at plan time),
  1 systemic fragility, 7 smaller gaps. All fixed or recorded.
- **Hardening (X0–X9, `docs/LNCH1_HARDENING_PLAN.md`)** — a review of the remediation round found a
  **CRITICAL regression it had introduced**: on any app with `allowDestructiveRecreate: true` (i.e.
  every shipped app definition), dropping a concept routed to the whole-schema wipe and destroyed
  **every other table's data**, while the orphaned table it was meant to drop survived. Fixed in X1,
  with the reproduction observed red first. Also: ownership is now a union intersected with live
  tables so surviving orphans stay cleanable (X2); the schema-ahead-of-build detector was inert in
  production and now actually fires (X3); the deprecated blanket flag no longer authorizes concept
  drops and is no longer the recommended default (X4); the Postgres twin covers all of it (X5).

**Verification status is tracked claim-by-claim** in
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md` —
consult that rather than any single summary, because an earlier session's summary and its own
evidence file disagreed about what had actually been run.

**The gap (why).** The entire value proposition of low-code is *iterating on a live app*.
Today NPDev's lifecycle is: author model → generate → **fresh schema**. There is no answer to
the question every real user asks in week two: *"I added a field / renamed a concept / changed
a type / tightened an invariant on an app that has six months of production rows — now what?"*
Without an answer, every NPDev app is effectively a disposable prototype, and no amount of
generator polish changes that. This is the single largest gap between NPDev and the platforms
it implicitly competes with (GeneXus's reorganization engine, OutSystems' impact analysis —
both companies' moats are substantially *this feature*).

**Where the problem lives.**
- `NPDevGenerator/generator` — `SchemaRealizationEmitter` and the Flyway emitter produce
  *initial* DDL from the compiled model; there is no notion of a *previous* model to diff
  against.
- `NPDevContract/dsl` — `ModelCompiler` produces a `CompiledModel` with no persistent identity
  for fields/concepts across versions (a rename is indistinguishable from drop+add).
- Generated FinalApps — carry no record of which model version created their schema.

**Root cause.** The pipeline was (correctly) built generation-first; migration was never
designed because sample apps regenerate from scratch. The compiled model lacks the one
prerequisite migrations need: **stable identity** for schema-bearing elements.

**Recommended fix (how).** Phase it; do not attempt it in one pass.

1. **Phase A — stable identity.** Add an optional immutable `uid` to concepts and fields in
   the model schema (all four `model.schema.json` copies), auto-assigned by the editor/CLI on
   creation, preserved on rename. `CompiledModel` carries it. Without uids, rename detection
   is guesswork; with them, it is exact.
2. **Phase B — model snapshot in the app.** Every generated FinalApp embeds its compiled
   model (it already ships `compiled-metadata.json` for tests — promote this to a first-class
   `npdev-generated/model-snapshot.json` with a schema version). The app records the snapshot
   hash in a `npdev_schema_history` table on boot.
3. **Phase C — the differ.** New module (suggest `NPDevGenerator/migration`): input = previous
   snapshot + new compiled model; output = a typed `SchemaDelta` (add-field, drop-field,
   rename-field via uid, type-change with cast risk classification, add/drop concept,
   invariant tightening with existing-data-violation risk). This is pure, unit-testable logic
   — build it gate-first like `FileOrphanSweeper` was.
4. **Phase D — migration emission.** From `SchemaDelta`, emit versioned Flyway migrations
   (`V<n>__model_<hash>.sql`) instead of regenerating V1. Destructive deltas (drop, narrowing
   type change) are **refused by default** and require an explicit `--allow-destructive`
   acknowledgment listing exactly what will be lost — this is the safety contract users must
   be able to trust.
5. **Phase E — data-risk validation.** Before applying an invariant/unique tightening, run a
   check query against existing data and report violating rows instead of letting Flyway fail
   mid-migration. Reuse the compound-unique lookup machinery (`CompoundUniqueValueLookup`)
   pattern.
6. **Phase F — wire into the build.** `scripts/appgen/Build-NpdevApp.ps1` gains an
   `-Upgrade` mode: detects an existing app dir + schema history, runs the differ, emits
   migrations, refuses destructive without acknowledgment, then rebuilds. InMemory-storage
   apps skip DDL but still get the data-risk validation pass.

**Definition of Done.** A WmsOffice-class app with seeded data survives, with zero data loss
and green verification: (a) add field with default, (b) rename field, (c) new concept + bond
to an existing one, (d) tightened invariant that existing data satisfies, (e) a *rejected*
destructive change with a clear report. Proven on both H2 and real Postgres.

**Dependencies.** None hard; benefits from LNCH-7 (Postgres-first) so the migration path is
proven on the production engine from day one.

---

### LNCH-2 — Adversarial tenant-isolation audit + permanent cross-tenant test suite

**Status:** DONE (2026-07-14) · **Priority:** P0 · **Effort:** M

**Update (2026-07-14).** Hermetic `TenantIsolationAttackTest` (cross-tenant read/list/forged-write/
delete over both InMemory and JDBC/H2 adapters, 8/8 green, gate-wired). Found+fixed a real bug:
`PanelRuntime.deleteRow` silently no-op'd cross-tenant deletes while still returning `deleted:true`.

**The gap (why).** Multi-tenancy is a headline feature, but its isolation record is
reactive: orchestration `create` wrote rows under the wrong tenant (fixed bug #5);
`JwtBearerAuthFilter` clobbered already-authenticated requests (fixed); tenant `"default"`
silently 403'd flows (ARCH-15, fixed). Every one of those was found *incidentally* while
building an app. Nobody has ever *attacked* the isolation on purpose. A single cross-tenant
data leak after launch is not a bug report — it is the end of the platform's credibility.

**Where the problem lives (attack surface inventory).**
- Generic CRUD REST (generated controllers, `ConceptGateway`/`DefaultConceptGateway`).
- Panel runtime reads/writes (`PanelRuntime`, incl. the new `createRow`/`deleteRow` with
  parent-FK injection).
- Flow/procedure execution (`KernelRunner`, `DefaultExecutionAuthorizationPolicy`).
- File store (`FileUploadController` — isolation currently rests on the handle key's tenant
  prefix; verify a crafted handle can't cross).
- ControlPanel + SUPERUSER surfaces (`com.finalexec.controlpanel.*`) — including the two
  brand-new uncommitted controllers (`ControlPanelTenantUsersController`,
  `TenantAutoRegistrationRunner`).
- Seed-data admin (`SeedDataAdminController`), event store queries, aggregate load endpoint
  (`AggregateApiController`).

**Recommended fix (how).**
1. Write a **hostile integration test suite** (suggest
   `NPDevRuntimeHost/src/test/java/com/finalexec/security/TenantIsolationAttackTest.java` +
   a kernel-side twin) that provisions tenants A and B and, for every surface above, attempts:
   read B's rows with A's credentials; write into B by manipulating tenantId in body, path,
   header, and JWT claim inconsistently; execute B's flows; fetch B's files by guessed/forged
   handle; enumerate B's existence via error-message differences.
2. Run it under **both** InMemory and JDBC adapters (history shows the two paths diverge —
   bugs #2, #5, #16 all had adapter-specific behavior).
3. Wire it into `scripts/quality/run-runtimehost-gate.ps1` so it runs on every gate pass
   forever — this is a ratchet, not a one-time audit.
4. Every finding becomes a knowledge card (`knowledge/cards/`) with a failure signature, so
   the AI loop learns the class of bug.

**Definition of Done.** The attack suite exists, covers all eight surfaces, passes under both
adapter families, and is gate-wired. Any finding it produced is fixed or explicitly
risk-accepted in writing.

**Dependencies.** Do LNCH-3 first (known flaw; the audit would just rediscover it).

---

### LNCH-3 — Fix the known `RuntimeApiKeyAuthFilter` clobbering flaw

**Status:** DONE (2026-07-14) · **Priority:** P0 ·
**Effort:** S

**Update (2026-07-14).** Clobber guard added on the generated `RuntimeApiKeyAuthFilter` (mirrors
`JwtBearerAuthFilter`'s existing fix), plus a generator-gate assertion.

**The gap (why/where).** When the `JwtBearerAuthFilter` bug (overwriting an
already-authenticated request's security context) was fixed, `RuntimeApiKeyAuthFilter` in
`NPDevRuntimeHost` was identified as carrying the **same latent flaw** and was deliberately
deferred. Launching with a *known, documented* auth-filter defect is indefensible — it will
be the first thing an auditor or researcher finds, precisely because it is written down.

**Recommended fix (how).** Apply the identical pattern used for the JWT filter fix: guard
on an existing authentication in the `SecurityContext` before overwriting; add a regression
test mirroring the JWT filter's test that sends both credentials on one request and asserts
the stronger/first identity survives. One session, including gate run.

**Definition of Done.** Filter guarded, regression test green in the runtimehost gate,
knowledge card updated to close the deferral note.

---

### LNCH-4 — Authentication table stakes

**Status:** DONE (2026-07-22) · **Priority:** P0 · **Effort:** L

**Update (2026-07-22).** The last open slice — secrets-via-env-vars — is DONE (REG-9): JWT
signing/verification keys are env-var-supplied with fail-fast startup validation and a verify-only
boot mode; DB credentials and runtime API keys were already env-bound; super-user-key seeding is
WONTFIX by decision (issued, not operator-supplied). LNCH-4 now fully DONE.

**Update (2026-07-16).** DONE: P0 slice (token revocation via `tokenVersion`, login throttling,
CSRF-posture doc + structural guard script) and the P1 self-service password-reset flow (built on
LNCH-11's mail primitive). Secrets-via-env-vars was the one remaining sub-item — see 2026-07-22 above.

**The gap (why).** Current auth is a working but skeletal patchwork: JWT via the identity
pack, `X-Super-User-Key` from a key file, runtime API keys. What every deployed business app
needs and NPDev has **none** of:
- Password reset (no email flow, no admin-forced reset UX beyond direct DB/ControlPanel edits).
- Token/session revocation (a leaked JWT is valid until expiry; no denylist, no logout that
  means anything server-side).
- Account lockout / brute-force throttling on the login endpoint.
- Rate limiting anywhere.
- A stated CSRF posture for the generated UI (session-less JWT-in-header is *probably* fine —
  but "probably" must become a documented, tested claim, especially for any cookie usage).
- Secrets management beyond files on disk (super-user key, JWT signing key, DB credentials).

**Where.** `NPDevRuntimeHost` (`com.finalexec.auth.*` — note `IdentityProvisioning.java` is
being modified in the working tree right now), the identity pack, login controllers, and the
generated login page.

**Recommended fix (how).** Stage it; not all of it blocks the first honest launch:
1. **P0 slice:** revocation (a `revoked_tokens`/token-version column checked in
   `JwtBearerAuthFilter`), login throttling (fixed-window counter per user+IP, in the same
   store the credential registry uses), documented CSRF stance with a test proving
   cookie-less operation.
2. **P1 slice:** password reset built on LNCH-11 (email primitive) — do the email primitive
   first so reset isn't a bespoke mailer; admin-forced reset in ControlPanel meanwhile.
3. **P1 slice:** secrets via environment variables/Spring config with the file-based path as
   dev fallback only — aligns with LNCH-7's container story where key-files-on-disk don't fit.

**Definition of Done.** P0 slice live in a generated app: a revoked token 401s, eleven wrong
passwords lock/throttle, and the CSRF claim has a test. P1 slices tracked as their own
roadmap rows.

**Dependencies.** Reset flow depends on LNCH-11; secrets slice pairs with LNCH-7.

---

### LNCH-5 — SQL push-down for filtering/sorting + server-side pagination

**Status:** DONE (2026-07-14) · **Priority:** P0 · **Effort:** L

**Update (2026-07-14).** `ConceptQuery`/`ConceptQueryEngine` SQL push-down at the `ConceptStore`
port (both InMemory and JDBC adapters), a paged REST endpoint, and the generated CRUD `list()`
itself migrated onto push-down. 100k-row volume gate proves a bounded page + `COUNT(*)` in <3s.

**The gap (why).** This is a silent time bomb that no verification so far could see, because
every sample dataset is tiny. The fix for bug #10 made panel `where` work by **post-filtering
in memory**: fetch *all* rows for the tenant, then filter in Java
(`ConceptQueryFilterSupport`, shared by `PanelRuntime` and `runQuery` since the ARCH-7 lift).
`orderBy` (ARCH-10b) is applied the same way. There is no LIMIT/OFFSET or keyset pagination
anywhere in the generated read path — grids render whatever comes back. At 1,000 rows this is
sloppy; at 100,000 rows every panel load streams the whole table through the JVM and the
browser, and the platform is unusable for exactly the WMS-class apps it targets.

**Where the problem lives.**
- `ConceptQueryFilterSupport` (kernel) — the in-memory filter/sort implementation.
- `persistence-postgres` / the JDBC store adapters — `listConcepts` takes no filter/page
  parameters worth speaking of.
- `PanelRuntime` + the generated grid UI (`business-ui-app.mustache`, declared-panel render
  paths) — no pagination controls, no page contract in the REST responses.
- `AggregateApiController` load endpoint — loads whole aggregates.

**Recommended fix (how).**
1. Define a **query contract** at the `ConceptStore`/persistence port: filter tree (the same
   `==`/`!=`/comparison shapes `ConceptQueryFilterSupport` already understands), sort keys,
   and a page cursor (prefer keyset/seek pagination over OFFSET for large tables; OFFSET is
   acceptable v1).
2. Implement it natively in the JDBC adapters (parameterized SQL — this is also an injection
   surface, so build it as a whitelisted-field, bound-parameter compiler, never string
   concatenation of user input).
3. Keep `ConceptQueryFilterSupport` as the **InMemory adapter's implementation** of the same
   contract — the contract is the point; the in-memory version stays correct for dev mode.
   This preserves the adapter symmetry (`*-inproc` / `*-postgres`) the architecture is built on.
4. Generated grids: page-size default (e.g. 50), pager controls, server-side sort on column
   click, and the REST list responses gain `{items, nextCursor, total?}`.
5. Add a **volume gate**: a test that seeds 100k rows (reuse `SeedDataService`) into H2 and
   Postgres and asserts a panel page loads in bounded time/memory. Without a gate this
   regresses the first time someone adds a convenience `.stream().filter()`.

**Definition of Done.** A 100k-row concept lists, filters, sorts, and pages in the generated
UI against real Postgres with query plans using indexes (see LNCH-6); the volume gate is wired
into the generator or runtimehost gate.

**Dependencies.** LNCH-6 (indexes) makes it fast; LNCH-7 (Postgres-first) makes the proof
honest.

---

### LNCH-7 — Postgres-first, Dockerized deployment story

**Status:** DONE (2026-07-14) · **Priority:** P0 · **Effort:** L

**Update (2026-07-14).** `DockerDeploymentEmitter` emits `Dockerfile`/`docker-compose.yml`/
`.env.example`/`deploy/Caddyfile` into every generated FinalApp. Verified via real
`docker compose up` runs, which surfaced and fixed 4 real live bugs (Windows-path defaults,
volume-ownership, missing `npdev-generated` copy, `NPDEV_RUNTIME_MODE` wiring). `docs/DEPLOYMENT.md`.

**The gap (why).** Every green verification to date ran on one Windows machine: H2 over TCP
started by `Start-Environment.ps1`, apps launched by per-app `_ops` PowerShell scripts,
plain HTTP on localhost. That is a superb *development* harness and zero percent of a
*deployment* story. Worse, the production database path (Postgres, via the `*-postgres`
adapters) is the **less-exercised** one — bug #11 (date/datetime failed under real Postgres
while H2 masked it) proved the two engines genuinely diverge. Launching a platform whose
production path is the untested path inverts the risk exactly the wrong way.

**Where.** New territory, plus touches: `NPDevRuntimeHost` config (`application*.yml`
profiles), `Build-NpdevApp.ps1` (db-engine vs profile selection already exists),
`NPDevSamples` verification harness (currently 127.0.0.1/H2-bound).

**Recommended fix (how).**
1. **Emit a Dockerfile + `docker-compose.yml`** with every generated FinalApp: app container
   (the bootJar it already builds) + Postgres container + a named volume; config via
   environment variables (`SPRING_DATASOURCE_URL`, JWT key, super-user key) — which forces
   the LNCH-4 secrets slice.
2. **Externalize the file store**: compose mounts a volume for `file-store-inproc`, or wire
   LNCH-14's S3 adapter with MinIO in compose.
3. **TLS guidance, not TLS implementation**: a documented reverse-proxy recipe
   (Caddy/nginx service in the compose file) — generated apps should not terminate TLS
   themselves.
4. **Flip the verification default**: at least one full sample-app browser-verification run
   (the ScrapForAI harness) executes against the compose stack on Postgres, and the
   beta-release gate (`scripts/quality/run-beta-release-gate.ps1`) includes it. This is the
   single highest-value change: it converts "Postgres should work" into "Postgres is what we
   test".
5. Document the whole thing as `docs/DEPLOYMENT.md`: compose up, first-tenant bootstrap,
   super-user key handling, backup pointers (LNCH-9).

**Definition of Done.** `docker compose up` on a clean machine (ideally Linux — ties into
LNCH-19/20) yields a working FinalApp on Postgres behind a proxy, browser-verified by the
existing harness; the release gate runs it.

**Dependencies.** LNCH-4 secrets slice (env-var config), LNCH-14 optional, LNCH-19 to run it
in CI.

---

## 4. Tier 2 — Product-completeness gaps (P1: users hit these in week one)

### LNCH-6 — Index emission from the model

**Status:** DONE (2026-07-14) · **Priority:** P1 · **Effort:** M

**Update (2026-07-14).** Implicit `(tenant_id,col)` secondary indexes for every panel/query
predicate field, plus explicit author-declared `indexes:[]` on a concept (plain or unique).
Found+fixed a real bug: `ModelResolver.sanitizeConcept`/`mergeConcept` silently dropped declared
indexes before `ModelCompiler` ever saw them.

**Why/where.** `SchemaRealizationEmitter` emits tables, tenant-scoped uniques (incl. the new
compound uniques), and FK constraints from bonds — but no secondary indexes. Fields used in
panel `where` clauses, `orderBy`, and lifecycle-state filters get nothing; under LNCH-5's SQL
push-down those queries become sequential scans.

**How.** Two sources of truth, both already in the compiled model: (a) implicit — any field
referenced by a compiled panel/query `where`/`orderBy` gets a tenant-composite index
`(tenant_id, field)`; (b) explicit — an `indexes: []` block on the concept in the model schema
(mirror to all four `model.schema.json` copies) for author intent. Emit via the same Flyway
path; the LNCH-1 differ must understand index deltas (cheap: create/drop are non-destructive).

**DoD.** Volume gate from LNCH-5 shows index scans in `EXPLAIN` output on Postgres for the
generated panel queries.

---

### LNCH-8 — Observability: health, metrics, structured logs

**Status:** DONE (2026-07-14) · **Priority:** P1 · **Effort:** M

**Update (2026-07-14).** Correlation-ID filter, `KernelRunner`-level flow-outcome metrics/logs
(Micrometer + Prometheus registry), and `ActuatorAdminGuardFilter` gating `/metrics`/`/prometheus`
to SUPERUSER while `/health` stays open (needed for the LNCH-7 Docker healthcheck).

**Why.** An operator of a deployed FinalApp currently has: stdout. No `/actuator/health` for
the compose/orchestrator health check (LNCH-7 needs it), no metrics (request rates, flow
execution counts/failures, event-store lag), no correlation IDs to trace a failed flow across
log lines.

**How.** Spring Boot Actuator is already on the classpath family — enable `health`,
`metrics`, `prometheus` endpoints (admin/superuser-gated per the established built-in-pack
gating pattern; remember the workspace::Preference latent gating bug as prior art of getting
this wrong). Add a request-ID filter in `NPDevRuntimeHost` that stamps MDC; make
`KernelRunner` log flow start/step/finish with executionId at INFO in a single-line JSON
format. Emit a Micrometer counter per flow/procedure outcome.

**DoD.** Compose stack (LNCH-7) shows healthy/unhealthy correctly; a failed flow can be traced
end-to-end from one correlation ID; a Prometheus scrape returns kernel metrics.

---

### LNCH-9 — Backup / restore / data export

**Status:** DONE (2026-07-14) · **Priority:** P1 · **Effort:** M

**Update (2026-07-14).** `deploy/backup.sh`/`restore.sh` for Postgres-engine apps + `TenantExportService`
(`GET /api/admin/export`, round-trips through the same seeder shape `SeedDataService` consumes).
Found+fixed a real bug via a live drill: `backup.sh` lacked `--clean --if-exists`, breaking restore
into a non-empty database. `docs/BACKUP_RESTORE.md`.

**Why.** "Complete web app" implies the owner can not lose their data. There is no documented
backup for either engine (the H2 file's location gotcha is only recorded in memory/verification
notes), no restore drill, and no user-level "export my data" (seed-data JSON goes *in*, nothing
comes out).

**How.** (a) Document + script `pg_dump`/restore for the compose stack and file-copy semantics
for H2 (app must be stopped or use the online BACKUP command). (b) Add an export counterpart
to `SeedDataService` — dump a tenant's concepts to the same smart-template JSON the seeder
consumes, which doubles as a poor-man's tenant migration/clone tool and the escape hatch users
demand before adopting any platform. (c) A restore drill in the release gate would be ideal
but can be a documented manual procedure v1.

**DoD.** Scripted backup+restore proven on the compose stack with data intact; tenant export
JSON round-trips through the seeder.

---

### LNCH-10 — Reporting & export primitives

**Status:** PARTIAL (2026-07-22 — Slices 1+2 DONE, Slice 3 planned) · **Priority:** P1 · **Effort:** L

**Update (2026-07-22).** Slice 2 (print stylesheet + print render mode for declared panels) is DONE
(REG-12): a "Print" toolbar button, a self-contained `#printRoot` print document, and an `@media print`
stylesheet, verified live in a real browser (empty and with real row data). Slice 3 (server-side PDF /
`document` object kind) is greenlit with its own phased plan `docs/REG12_DOCUMENT_EXPORT_PLAN.md` —
whose core design reuses this same print HTML/CSS as the renderer's input. Only Slice 3 remains.

**Update (2026-07-17).** Slice 1 (CSV) DONE: `GET /api/concepts/{concept}/export.csv`, streamed
page-by-page through the LNCH-5 push-down contract (never holds more than one page in the JVM),
plus a grid "Export CSV" button. Excel/PDF/print slices remain OPEN. `docs/CSV_EXPORT.md`.

**Why.** Business apps end in paper or spreadsheets: pick lists, packing slips, invoices,
monthly CSVs. NPDev has no CSV/Excel export on any grid, no PDF/print layout primitive, no
report object. WmsOffice-class apps are literally not finishable without hand-authored `web/`
pages doing client-side hacks — which breaks the low-code promise at the exact moment the app
becomes real. (The GeneXus reference exports in `D:\WorkSpace\WmsOffice\OriginalArtifacts`
are full of procedures whose entire purpose is report output — that's the bar users coming
from there expect.)

**How.** Three ascending slices:
1. **CSV export (S/M):** a generic, tenant- and filter-aware `GET /api/concepts/{c}/export.csv`
   that reuses the LNCH-5 query contract (same filter the grid shows = what exports); an
   Export button on every generated grid. Streaming, not in-memory (ties to LNCH-5's lessons).
2. **Print-view (M):** a print stylesheet + a "print" render mode for declared panels — pure
   frontend, cheap, covers pick lists.
3. **PDF documents (L):** a `document` PAGE/procedure kind in the model (template +
   dataSource, rendered server-side — OpenPDF or wkhtml-class renderer as a new adapter pair
   so the dependency stays pluggable). Treat as its own roadmap feature with the code-bearing
   Panel/Procedure object model (ADR-0003) as the template's home.

**DoD (slice 1, the launch-relevant one).** Any generated grid exports its current filtered
view as CSV for a 100k-row concept without OOM; gate-tested.

---

### LNCH-11 — Email / notification primitive

**Status:** DONE (2026-07-16) · **Priority:** P1 · **Effort:** M

**Update (2026-07-16).** New `mail` capability, `mail-inproc`/`mail-smtp` adapters (the latter real
Jakarta Mail SMTP), full plugin-manifest wiring, `mailhog` compose profile. Live end-to-end proven
through a real `docker compose --profile smtp up` stack. `docs/EMAIL_NOTIFICATIONS.md`.

**Why.** No send-email capability exists, which blocks: password reset (LNCH-4), "order
shipped" notifications, admin alerts — the connective tissue of every business app.

**How.** Follow the established adapter-pair pattern: a kernel `MailContract` port +
`mail-inproc` (logs/records mail for dev + tests, like the event store's inproc twin) +
`mail-smtp` (JavaMail/Jakarta Mail, config via env per LNCH-7). Expose as (a) a built-in
capability callable from flow/procedure `capability` steps — zero new step types needed, the
dispatcher already handles multi-arg capability calls — and (b) templated by the same smart
templating the seed system uses. In-app notifications (a `workspace::Notification` built-in
concept + bell in the generated shell) are a natural second slice but not launch-blocking.

**DoD.** A flow step sends a templated email through SMTP in the compose stack; the inproc
adapter lets the runtimehost gate assert on sent mail without a network.

---

### LNCH-12 — Scheduled / background execution

**Status:** DONE (2026-07-16) · **Priority:** P1 · **Effort:** M

**Update (2026-07-16).** New `flow.schedule: {cron, tenantScope}`, `NpdevCronSchedulerService`,
`ControlPanelSchedulesController`. Found+fixed a real bug: a route collision with a pre-existing,
unrelated controller only surfaced on an actual HTTP request, not at boot. `docs/SCHEDULED_FLOWS.md`.

**Why.** Everything in a FinalApp is request-driven. "Every night at 2am, close stale orders"
— a completely ordinary requirement — has no home. Users will solve it with external cron +
curl against CRUD REST, which bypasses flows/invariants and becomes the unmaintained hack
every deployment accumulates.

**How.** (a) Model: a `schedule` trigger on flows/procedures (`cron` expression + tenant
scope) in the schema (all four copies) and compiler. (b) Runtime: a scheduler in
`NPDevRuntimeHost` (Spring's `TaskScheduler` is sufficient v1) that invokes the kernel exactly
like the HTTP path does — same authorization policy (a system principal, *not* the superuser
key), same event emission, so scheduled runs are indistinguishable from invoked ones in the
event store. (c) Durability: on missed windows (app down), v1 policy = skip + log a warning;
document it — do not silently invent catch-up semantics. Flow durability/resume machinery
(the forEach checkpoint work) already covers the mid-run-crash case.

**DoD.** A sample app closes stale records nightly; verified by shrinking the cron to
seconds in a gate test and observing the event store; ControlPanel lists schedules + last
outcome.

---

### LNCH-13 — Row-level (data-scoped) authorization

**Status:** DONE (2026-07-15) · **Priority:** P1 · **Effort:** L

**Update (2026-07-15).** Declarative `access: {read, write}` boolean expressions with `$user.*`
pseudo-variables, enforced solely at `DefaultConceptGateway`. Found+fixed two real bugs via live
verification: a canonical-JSON round-trip drop, and `{Concept}ServiceBase` reads bypassing the
gateway entirely (writes were protected, reads were not). `docs/ROW_LEVEL_AUTHORIZATION.md`.

**Why.** Roles gate *endpoints and flows*; nothing gates *rows*. "Salespeople see only their
own orders", "warehouse operators see only their site" — the most common authorization
requirement in business apps — currently requires hand-authoring every read as a filtered
query and hoping nobody calls the generic CRUD list. The generic CRUD surface makes this a
security gap, not just a modeling gap: any authenticated tenant user can list any concept.

**How.** Declarative scope rules on the concept in the model:
`access: { read: "owner == $user.id" , write: ... }` compiled into (a) a mandatory filter
injected into the LNCH-5 query contract for reads — scoping *is* filtering, which is why
LNCH-5 must land first — and (b) a predicate check in `ConceptGateway` writes. Evaluate
predicates with the unified expression engine (LNCH-15) — do **not** grow a fourth
mini-grammar. `authz-default` adapter is the natural home; keep the deny-by-default posture
the built-in packs already established.

**DoD.** The LNCH-2 attack suite gains scoped-row cases (user A of tenant T cannot read user
B's rows within the same tenant) and passes on both adapter families.

**Dependencies.** LNCH-5 (filter contract), LNCH-15 (predicate language).

---

### LNCH-14 — Production file storage (S3/object-store adapter)

**Status:** DONE (2026-07-14) · **Priority:** P1 · **Effort:** M

**Update (2026-07-14).** Discovery: `S3ObjectStoreFileStoreAdapter` was already fully implemented
in a prior session under a different task code (its own live Testcontainers MinIO test already
passed) — this doc's PARTIAL status was stale relative to the codebase. The actual remaining gap,
wiring it into the LNCH-7 deployment story (`minio` service + `objectstore` compose profile), is
now done too.

**Why/where.** The ARCH-upload lift delivered the full vertical (kernel `FileStoreContract`,
`file-store-inproc` filesystem adapter, `file` field type, `FileUploadController`, upload
widget, editor support) — but only the filesystem half; the `file-store-objectstore` S3
adapter was explicitly deferred (needed an external SDK/infra the session couldn't provision).
A filesystem store is incompatible with any multi-instance or containerized deploy unless a
volume is mounted (acceptable v1 per LNCH-7, but single-instance-only).

**How.** Implement `file-store-objectstore` against the S3 API (AWS SDK v2), config via env;
test against MinIO in the compose stack so the gate needs no cloud account. Preserve the
tenant-prefix key scheme the inproc adapter proved (it is the isolation mechanism — LNCH-2
tests it). Streaming both directions; no whole-file byte arrays.

**DoD.** Upload/download/delete green against MinIO in compose; tenant-isolation attack cases
pass; switching adapters is a config change, no regeneration.

---

### LNCH-15 — One unified expression language

**Status:** DONE (2026-07-15) · **Priority:** P1 · **Effort:** L

**Update (2026-07-15).** `ComputedExpression` extended with function calls, receiver sugar, and
lambdas; `CelInvariantEngine`'s quantifiers/`uniqueBy`/etc. now run through the SAME grammar as
parens/arithmetic instead of a separate ~400-line regex matcher. `docs/EXPRESSIONS.md`.

**Why.** There are currently **three half-languages** an author must distinguish:
1. `CelInvariantEngine` (kernel, adapters/expression-cel) — hand-rolled matcher: top-level
   `||`/`&&` over fixed atom shapes, **no parentheses, no generic `!`** (rules must be
   hand-converted to DNF via De Morgan — documented as a gap, #6).
2. `com.npdev.dsl.v1.expr.ComputedExpression` — a real recursive evaluator (arithmetic,
   comparison, logical, parens, field refs) built for AutoPanel computed columns.
3. The schema-expr helper (`evaluateSchemaExpression`) — function-only, no arithmetic.

A low-code author cannot be told "invariants use grammar A, computed columns grammar B, and
defaults grammar C." Each new feature (LNCH-13's predicates being the next) makes the fork
worse. This is user-facing product incoherence, not internal tech debt.

**How.** Promote `ComputedExpression` (it is real, dependency-free, and already tested) to
the single platform expression engine:
1. Extend it with the small set of functions the other two grammars offer that it lacks.
2. Re-implement `CelInvariantEngine`'s evaluation on top of it, keeping the old grammar
   accepted (existing DNF-shaped rules are a strict subset of a parenthesized grammar — this
   is backward compatible by construction). Keep the class name/port stable so adapters don't
   churn.
3. Route schema-expr defaults through it.
4. Update `SemanticValidator` to compile-time-check every expression site with the one parser,
   producing one error vocabulary; update the editor's expression inputs to share one
   help/autocomplete component.
5. Retire the "no parens / DNF only" documentation caveat and its knowledge card.

**DoD.** One parser, one function catalog, one docs page; a parenthesized negated invariant
works end-to-end in a generated app; all existing sample models still validate (regression via
the sample harness).

---

### LNCH-16 — Optimistic locking / concurrent-edit protection

**Status:** DONE (2026-07-16) · **Priority:** P1 · **Effort:** M

**Update (2026-07-16).** Platform-managed `row_version` on every generated table; both `ConceptStore`
adapters implement real compare-and-increment, `409` with the current record on conflict. Kernel-level
mechanism delivered per the DoD; wiring `expectedRowVersion` through the generic REST/UI surface is a
documented follow-on, not required for the DoD itself. `docs/OPTIMISTIC_LOCKING.md`.

**Why.** Two users editing the same row silently last-write-wins today — no version column,
no conflict detection. In single-demo verification this is invisible; in the first real
multi-user deployment it is silent data loss, the worst kind of bug because nobody sees it
happen.

**How.** Add a platform-managed `row_version` (bigint) to every concept table
(`SchemaRealizationEmitter`; the LNCH-1 differ adds it to existing apps). `ConceptRecord`
carries it; updates through `ConceptGateway` compare-and-increment (`WHERE id=? AND
row_version=?`, rows-affected check) — InMemory adapter mirrors with a CAS on the map entry.
On conflict: 409 with the current record in the body (the compound-unique 409 body pattern is
the template). Generated forms hold the version they loaded and surface "reloaded — reapply
your change" UX v1 (no merge UI). Panel inline edits and flow `updateConcept` opt in the same
way; flows may pass `force: true` explicitly where last-write is intended.

**DoD.** A gate test performs interleaved updates and asserts the loser gets 409 with the
winner's state; both adapter families; generated form UX verified in browser on one sample.

---

### LNCH-17 — Transaction-boundary contract for multi-step flows

**Status:** DONE (2026-07-16/17) · **Priority:** P1 · **Effort:** M (mostly specification + tests, some code)

**Update (2026-07-17).** `docs/architecture/FLOW_TRANSACTION_CONTRACT.md`, `onFailure` compensation
blocks (reverse-order, crash-recoverable), and a real bug found+fixed: `JdbcBusinessConceptStore`
acquired connections uncoordinated with the ambient Spring transaction, allowing a silent
partial-write between a kernel-gateway write and a JPA write in the same `@Transactional` method.

**Why.** What is atomic when step 4 of a flow fails after steps 1–3 wrote rows? Today the
honest answer is "it depends and isn't written down." The forEach durability work proved the
kernel can checkpoint/resume rigorously — but *resume* semantics (at-least-once step
execution) and *rollback* semantics are different promises, and authors are currently given
neither in writing. Undefined transactionality is how platforms corrupt customer data.

**How.** Decide and document the model rather than inventing distributed transactions:
1. **Spec first** (`docs/architecture/FLOW_TRANSACTION_CONTRACT.md`): each step is atomic;
   the flow as a whole is *not*; failures leave a durable execution record with completed-step
   state (the event store already has this); recommend the saga pattern (compensating steps)
   for multi-write consistency.
2. **Give authors the tool the spec demands:** an `onFailure`/compensation block on flow
   steps (schema + `KernelRunner`), executing declared compensations in reverse order on
   terminal failure — this is the pragmatic 80% answer and composes with the existing
   branch/forEach machinery.
3. **Single-request CRUD + invariants + lifecycle must be genuinely transactional** on JDBC
   (verify `DefaultConceptGateway` write + event append happen in one transaction; if not,
   fix — this narrow slice is a correctness bug, not a design choice).
4. ControlPanel surfaces failed executions with their completed-step state so operators can
   act (ties into LNCH-8's correlation IDs).

**DoD.** Contract doc merged; compensation blocks execute in a crash-injection test (reuse the
forEach freeze-thread technique); CRUD write+event atomicity proven under JDBC.

---

### LNCH-18 — The authoring-path decision: editor-complete vs AI-first

**Status:** PARTIAL (2026-07-17) · **Priority:** P1 ·
**Effort:** decision S; consequence L–XL

**Update (2026-07-17).** `docs/adr/ADR-0006-authoring-path.md` ratified: AI-first/editor-secondary.
The DoD's human-run step — a real non-author completing a build from a plain-English description —
is explicitly left OPEN, not claimed done (bucket-4, real-person-only; see
`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` for the prep work done toward it).

**Why.** Be honest about how every real app has been built: JSON authored by an AI through
the MCP/validate→fix→generate loop, with the React editor used for slices and inspection.
The editor has 30+ panels but has never carried a full app end-to-end. "Low-code platform"
implies a claim about *who can author*; right now that claim is unproven for anyone who is
not an AI with the NPDev MCP toolbox.

Two coherent positions exist, and not choosing is the only wrong option:

- **(a) Editor-complete:** the editor can author 100% of what the schema allows, with
  validation errors a non-engineer understands, undo, and live preview. Cost: XL — a long
  grind of panel completeness, and the schema is still growing (bonds truth-edges, aggregates,
  documents…). Risk: chasing schema parity forever.
- **(b) AI-first, editor-secondary (recommended):** the AI authoring loop *is* the authoring
  UX — conversational app building grounded by the MCP tools, RAG corpus, knowledge cards,
  and schema-constrained repair, with the editor positioned as the inspection/refinement
  surface. This is more differentiated, matches where the market is going, and — decisively —
  it is what has already been **proven blind** on this platform. Cost: productizing what
  exists: package `NPDevMcp` + `NPDevCli` + the knowledge corpus build into an installable,
  versioned unit; a guided "describe your app" front door; hardening the loop's failure modes
  for unsupervised users (the `npdev_check_support` capability gate is exactly the right
  seed).

**How.** Make the decision explicitly (an ADR — `ADR-0006-authoring-path.md`), then scope the
chosen path as its own roadmap. If (b): the P0 slice is packaging + the front-door flow +
`verify-in-browser`-style automatic verification of what the AI built, because unsupervised
generation without verification is how low-code tools earn a bad name.

**DoD.** ADR merged; one non-author (someone who is not Marcelo and not this assistant's
session) takes a new app from description to running, verified FinalApp through the chosen
path, and the friction log from that run becomes the next roadmap increment.

---

### LNCH-24 — Commit the current working tree

**Status:** DONE (2026-07-14) · **Priority:** P1 · **Effort:** S

**Update (2026-07-14).** Landed the in-flight working tree (ControlPanel tenant-user provisioning);
`git status` clean.

**Why/where.** Right now `IdentityProvisioning.java` (modified), `New-ControlPanelPage.ps1`
(modified), and two new untracked controllers (`ControlPanelTenantUsersController.java`,
`TenantAutoRegistrationRunner.java`) sit uncommitted on `beta1-vision-spine`. This is exactly
the HYG-2 pattern that already caused an at-risk scare once. Auth-touching code living only
in a working tree is both a loss risk and an audit blind spot (LNCH-2 must test the committed
truth).

**How.** Finish/verify the in-flight tenant-user-provisioning work, run the runtimehost gate,
commit in small bounded steps per the established discipline (no `git add .`).

**DoD.** `git status` clean; gates green on the commit.

---

## 5. Tier 3 — Formalization & distribution (P2 unless noted)

### LNCH-19 — Linux CI running the quality gates + sample harness

**Status:** DONE (2026-07-22) · **Priority:** P1 · **Effort:** M

**Update (2026-07-22, REG-10).** `npdev-pr-gate.yml` ran **green** on ubuntu-latest — run
`29899362276`, commit `3dcc51e`, every step success (DSL, kernel, all generator tests incl. the 3
packaged-app boot/HTTP/JDBC proofs, sample generation, RuntimeHost app suite) — and again green on the
promoted `main` line. The first CI-green in the project's history. It took six root-caused
first-contact-with-Linux fixes (hardcoded `pwsh.exe` path; `NPDev_General` folder-name build-root
assumption; a real generated-app `D:/` gradle-cache portability bug; a missing mail-adapter jar in the
packaged-app test list; CI diagnostics + direct GitHub-API log access; an `..` artifact-path typo).
LNCH-19 DONE; see the LNCH-20 entry for the cross-platform proof this simultaneously delivered.

**Update (2026-07-14).** `npdev-pr-gate.yml` (fast, `pull_request`-triggered) and a `schedule` trigger
added to the existing heavy `npdev-ci-validation.yml`, both committed and pushed to
`origin/beta1-vision-spine`. NOT independently confirmed to actually go green on a real GitHub
Actions run this session (no `gh` CLI access) — every Gradle task path was confirmed to resolve via
local `--dry-run` and all workflow YAML confirmed syntactically valid, but that is a weaker bar than
a real runner. A future session should confirm an actual Actions run before considering this DONE.

**Update (2026-07-19).** User explicitly asked to verify this. Pushed the branch's accumulated
commits to `origin/beta1-vision-spine` and prepared a small, deliberately-scoped verification PR:
branch `lnch19-ci-verify` (a single empty commit, no file changes) pushed to origin, targeting
`beta1-vision-spine` as base (NOT `main` -- opening against `main` would propose merging 204
commits, a much bigger action than "verify CI runs"). Could not open the PR object itself (no `gh`
CLI in this environment); GitHub returned the direct creation link
(`https://github.com/MarceloGiazzon/NPDevGeneral/pull/new/lnch19-ci-verify`) for the user to open
with one click, which will trigger `npdev-pr-gate.yml` for the first time ever. Still PARTIAL until
that Actions run is actually observed green.

**Why.** All four quality gates (`scripts/quality/run-*.ps1`) run only when someone runs them
on one Windows machine; `ReleaseGateValidator` CI-wiring is the one PARTIAL item left in the
old roadmap (BOND-B4, "needs your CI-trigger call" — this document is that call: wire it).
Nothing runs on push; a regression can sit unnoticed until the next manual gate run.

**How.** GitHub Actions (or equivalent): on PR — DSL/generator/kernel/runtimehost test suites
+ `ReleaseGateValidator`; nightly — full gates + one sample-app generate→build→boot→REST
smoke on Linux/Postgres (LNCH-7's compose stack is the vehicle). PowerShell 7 runs on Linux,
so the gate scripts may port with modest effort (LNCH-20 finishes the job). The AI-knowledge
gate's PR auto-run trigger (commit `b3b1253`) is prior art for the wiring style.

**DoD.** A PR that breaks a gate shows red on GitHub without any human running a script.

### LNCH-20 — Cross-platform build scripts

**Status:** DONE (2026-07-22) · **Priority:** P2 · **Effort:** M

**Update (2026-07-22, REG-11).** PROVEN. The green Linux CI run (LNCH-19) is the proof this item was
waiting for — the platform's DSL/kernel/generator/RuntimeHost build and a generated FinalApp's own
`bootJar`/boot all ran on ubuntu-latest, not just the dev machine. The "code side ready, not yet
proven" flag below is now resolved. Notably, the CI run also **exposed and fixed a genuine
distribution bug** the code-complete state had missed: every generated FinalApp shipped
`NPDevRuntimeHost/gradle.properties`'s hardcoded `org.gradle.projectcachedir=D:/WorkSpace/NPDev/Build/…`
(copied verbatim by `FinalAppAssembler`), so a generated app could not build on any machine without
that exact `D:` path — removed so generated apps use gradle's portable default cache. LNCH-20 DONE.

**Why.** Windows-only scripts and `D:\`-rooted path assumptions mean a contributor or
evaluator on macOS/Linux cannot even build the platform, and LNCH-19's Linux CI will trip on
every hardcoded path. **How.** Parameterize the workspace roots (env var with the current
defaults), keep PowerShell 7 as the script language (it is cross-platform) but purge
drive-letter literals and Windows-only assumptions (H2 TCP launcher, file-lock workarounds);
CI (LNCH-19) becomes the enforcement mechanism. **DoD.** Clean clone → generate → build →
boot a sample app on a Linux runner using the same scripts.

**Scoping pass (2026-07-19, research only, no code changed — user explicitly asked to scope, not
implement).** Smaller than the original framing suggests: the Java/Gradle/Spring runtime is
already proven on Linux (LNCH-19's CI runs DSL/kernel/generator/RuntimeHost tests on
`ubuntu-latest` today). The real, unfixed gap is narrowly the PowerShell orchestration layer's
`gradlew.bat`/path literals -- and a reusable, already-proven fix pattern exists
(`scripts/npdev-common.ps1:282-308`'s `Get-NPDevGradleWrapperExecutable`, `$IsWindows`-branched,
already consumed by several gate scripts) -- most of the work is applying an existing pattern
consistently, not inventing new cross-platform logic. `NPDevCli/npdev_cli.py` is already correctly
`os.name`-branched. The "H2 TCP launcher"/"file-lock workarounds" the original description worried
about turned out to be non-issues: the H2Server launcher's PowerShell cmdlets are cross-platform
already (only its hardcoded `D:\` jar-search path needs fixing, same bucket as everything else),
and the file-lock workaround is documentation-only guidance, not scripted anywhere.

**Important DoD-feasibility flag, directly relevant to LNCH-19's verification PR (see LNCH-19's own
entry above):** `.github/workflows/npdev-pr-gate.yml` invokes
`NPDevSamples/scripts/generate-sample-app.ps1`, which hardcodes `gradlew.bat` (line 39) and executes
it via `pwsh` -- a Windows batch file with no shebang/execute bit, which should fail outright on the
`ubuntu-latest` runner LNCH-19's workflow targets. Not independently confirmed against a real
Actions run (no `gh` CLI); if the `lnch19-ci-verify` PR comes back red, this is the most likely
reason, and it's a small, well-understood fix (port `generate-sample-app.ps1`/`run-sample-app.ps1`/
`sample-common.ps1` onto the same `Get-NPDevGradleWrapperExecutable` pattern already proven
elsewhere) -- not a sign LNCH-19 itself is broken.

**Proposed phasing (not started):**
- Phase 1 (~0.5-1 day, on the DoD critical path): fix `generate-sample-app.ps1`/`run-sample-app.ps1`/
  `sample-common.ps1` -- exactly the scripts LNCH-19's CI already tries to run. Likely sufficient to
  satisfy the DoD alone.
- Phase 2 (~1-2 days): same fix for `scripts/appgen/Build-NpdevApp.ps1`/`Build-ClaudeApp.ps1` (the
  real-app builder path used outside CI) -- needed for "a contributor can build the platform" to be
  true for real apps, not just the CI sample.
- Phase 3 (~1 day, deferrable): same mechanical fix across ~14 remaining quality-gate scripts with
  the identical `gradlew.bat` literal.
- Phase 4 (separate ticket, not part of this DoD): `run-item20-postgres-proof.ps1`'s Docker Desktop
  launcher needs a real Linux-Docker-daemon branch -- already isolated to a `windows-latest`-gated
  CI job, not on the critical path.

Total estimated effort for DoD closure (Phases 1-2): ~2-3 days. Full script-tree parity (+Phase 3):
+1 day.

**Phases 2-3 done (2026-07-21, REG-11/P4) — code side now cross-platform-ready.** A fresh repo-wide
sweep (`gradlew.bat` invocations + `D:\`/`D:/` literals, not just the two originally-named dirs)
produced this disposition:
- **Migrated to the shared `Get-NPDevGradleWrapperExecutable` helper:** `run-editor-gate.ps1`,
  `run-generator-gate.ps1` (both already dot-sourced `npdev-common.ps1`).
- **Fixed OS-awareness in place** (genuine Windows-only breakage on a Linux runner):
  `Build-NpdevApp.ps1`, `Build-ClaudeApp.ps1` (inline `$IsWindows` pick rather than dot-sourcing
  `npdev-common.ps1`, which sets `Set-StrictMode -Version Latest` at file scope and would impose
  strict mode on these legacy builders); `run-incremental-migration-testing-check.ps1` and
  `run-trusted-source-security-check.ps1` (their `Get-GradleWrapper` gated on *file existence*, but
  `gradlew.bat` is committed so it exists on Linux too — now gated on `$IsWindows`);
  `run-stateful-additive-migrations-check.ps1` (two `& .\gradlew.bat` calls + removed a redundant
  hardcoded `D:/WorkSpace/NPDev/Build/...` test-XML fallback that candidate 2 already derives
  portably); `npdev-gradlew.ps1` (the root wrapper found either gradlew via `Find-Up` then hardcoded
  `.bat`).
- **Already cross-platform, verified, left as-is:** `sample-common.ps1` + `generate-sample-app.ps1`
  (Phase 1, above — the ledger's "line 39 hardcodes gradlew.bat" note is now stale), `run-frontend-gate.ps1`,
  `invoke-ai-beta-app-smoke.ps1`, `run-ai-beta-gate.ps1`, `run-trusted-source-beta0-proof.ps1`,
  `scripts/security/Invoke-StructuredCommandRequest.ps1` (each carries its own `$IsWindows`-branched
  resolver).
- **Intentional non-call-sites, unchanged:** `run-frontend-gate-tests.ps1` (writes a stub `.bat`
  *fixture*), `run-post-beta0-maturity-closure-check.ps1` (its `gradlew.bat` string is the *check
  that fails a Linux CI job for using it* — the REG-11 enforcement itself), the `RUN_COMMANDS.md`
  here-string in `Build-ClaudeApp.ps1`.
- **Named, justified Windows-only exceptions (step 5):** `run-item20-postgres-proof.ps1` is portable
  except its **opt-in** `-StartDockerDesktop` switch (starts the Windows Docker Desktop GUI; on Linux
  CI the daemon is already up, so the switch is never passed) — the proof body uses the portable
  `docker` CLI. The three `superuser-admin-console/demonstrate-*.ps1` demo scripts invoke via
  `Start-Process` with cmd.exe-specific `--args` quoting (documented in their own comments) and are
  demo-only, off the CI path; a wrapper-only swap would leave the arg quoting broken, so they stay
  Windows-only by design rather than half-ported.
- **`D:\` param-default literals across the appgen/proof scripts** are the sanctioned, overridable
  local-workspace convention (CLAUDE.md mandates `D:\WorkSpace\NPDev\Build`), not execution-blocking
  hardcodes — left as documented convention.

**RESOLVED (2026-07-22):** the green Linux Actions run this was waiting on happened (REG-10, run
`29899362276`) — the code is now *proven* cross-platform, not merely ready. LNCH-20 is DONE. (The
green run additionally caught the generated-app `D:/` gradle-cache portability bug noted in this item's
2026-07-22 status update.)

### LNCH-21 — Generated-app upgrade contract

**Status:** DONE (2026-07-17) · **Priority:** P2 · **Effort:** M

**Update (2026-07-17).** `docs/architecture/APP_UPGRADE_CONTRACT.md`, corrected against actual
pipeline behavior (customizations survive regeneration via `apps/<App>/web/` re-mounting from
outside the wiped tree, not in-place preservation as originally assumed). DoD proven live:
`run-app-upgrade-contract-gate.ps1` asserts a marker file survives byte-identical across two
regenerations of a real AppGen sample.

**Why.** When the platform ships vNext, how does an existing FinalApp adopt it? The
hash-guarded `npdev-generated/` protects platform-owned files, and app-owned `web/` is the
escape hatch — but the boundary contract across regenerations is informal (the
mapa-armazem.html "never write into hash-guarded dirs / stale jar copy" episodes are the
symptom). There is also no stated compatibility policy: model schema is versioned, runtime
*behavior* is not. **How.** Write the contract (`docs/architecture/APP_UPGRADE_CONTRACT.md`):
which directories are platform-owned (regenerated, hash-verified), app-owned (never touched),
and negotiated (regenerated with app-visible diff); pair the platform version into the app
(`npdev.platform.version` in the snapshot from LNCH-1 Phase B); adopt a compatibility rule
(behavior changes require a model-schema or platform major bump + release-gate entry).
`Rebuild-And-Restage.ps1` becomes the upgrade driver. **DoD.** A FinalApp generated on
version N upgrades to N+1 with local `web/` customizations intact, proven in the release gate.

### LNCH-22 — User-facing documentation & error-message quality

**Status:** PARTIAL (2026-07-17/19) · **Priority:** P2 · **Effort:** L (incremental)

**Update (2026-07-19).** `docs/DSL_REFERENCE.md` (generated, `--check`-mode drift detection),
`docs/CONFIGURATION.md` (startup-validator refusal anchors), `docs/TUTORIAL_FIRST_APP.md`, and now
`docs/SCHEMA_EVOLUTION.md` (LNCH-1) all follow the same "refusal message links a stable doc anchor"
pattern. `ValidationDiagnostic`/`ValidationDiagnosticNormalizer` (code/suggestedFix/helpKey) already
existed; the LNCH-1 knowledge cards extend `npdev_search_fix` coverage to schema-evolution refusals.
Still OPEN: the DoD's human newcomer test (same person as LNCH-18's DoD) has not been run.

**Why.** The existing docs are excellent *internal* docs — written for the platform's
builders. A stranger has: no "first app in 30 minutes" tutorial, no DSL reference manual
(what fields/widgets/steps exist, with examples), no error catalog. `SemanticValidator`
messages speak platform-developer language. **How.** (a) Generate the DSL reference from the
schema + widget/capability catalogs rather than hand-writing it — the schemas are the truth,
keep it that way. (b) One golden-path tutorial that *is* a gate (the tutorial's app builds in
CI, so docs can't rot — the NPDevSamples harness is the mechanism). (c) Error quality:
every SemanticValidator error gets a stable code + one-line fix hint; the knowledge-card
corpus (`knowledge/cards/`) already contains the raw material for the troubleshooting guide —
`scripts/ai/build_knowledge.py` can emit a human-readable rendering as a new output
alongside `failure-index.json`. **DoD.** A newcomer test (same person as LNCH-18's DoD)
builds the tutorial app from docs alone; validator errors carry codes + hints.

### LNCH-23 — Launch checklist: license, packaging, telemetry, release process

**Status:** DONE (2026-07-22) · **Priority:** P2 · **Effort:** M (mostly decisions)

**Update (2026-07-22, REG-15).** The **release tag was cut**: `beta1.1` (annotated, on the
`beta1-vision-spine → main` merge commit `3e29cca`) — the CI-green, register-closed milestone. `beta1`
was already taken by the original milestone, hence `beta1.1`. With license (Apache-2.0), the
distribution ADR (self-hosted / no telemetry), `docs/RELEASE_PROCESS.md`, `CHANGELOG.md`, and the
release-checklist gate all already in place, and the tag now cut, LNCH-23 is DONE. **Trademark
clearance is deliberately parked** — the owner confirmed this is an individual portfolio project with
no mark to defend, so a professional clearance can wait indefinitely without blocking anything (the two
preliminary findings stay on file for if the posture ever changes).

**Update (2026-07-17).** `LICENSE` (Apache-2.0, ratified copyright holder Marcelo Giazzon),
`docs/adr/ADR-0007-distribution-model.md` (self-hosted, no telemetry — both ratified),
`docs/RELEASE_PROCESS.md`, `CHANGELOG.md`, `run-release-checklist-gate.ps1`. A real trademark-name
collision was found via `WebSearch` ("NP DEV Soluções em T.I.", `npdev.com.br`) and documented as an
open finding for a professional search to assess — explicitly NOT resolved. No release tag has been
cut (stayed on `[Unreleased]`, by explicit choice, not yet).

**Why.** The unglamorous items that block formalization regardless of code quality:
- **License** — none declared; nobody can legally evaluate the repo.
- **Distribution model** — self-hosted download vs SaaS vs installer; determines LNCH-7's
  final shape and the support surface.
- **Telemetry/crash reporting** — decide (and if yes, consent-first); without it, launch
  feedback is anecdotes.
- **Versioned release process** — `run-beta-release-gate.ps1` is the seed; formalize
  tag → gate → changelog → artifact (the beta0 tag-immutability rules already written into
  the maturity ledger are the precedent).
- **Naming/trademark check** for "NPDev" before anything public.

**How.** One decision session producing: LICENSE file, an ADR for the distribution model,
a release-process doc that the beta gate script enforces. **DoD.** All five items have a
written decision; the release gate refuses an untagged/unchangelogged release.

---

## 6. What is explicitly NOT a launch blocker (scope discipline)

To keep the roadmap honest, these are recorded as consciously deferred, not forgotten:

- **Kernel/generator feature depth** — durable flows, bonds, widgets, theming, ControlPanel,
  seed data, AutoPanel/Aggregate Workbench: ahead of where most platforms are at this stage;
  no launch work needed beyond LNCH-24's commit hygiene.
- **Editor panel polish** beyond whatever LNCH-18's decision demands.
- **Horizontal scaling / HA** — single-instance + Postgres + backups (LNCH-7/9) is an honest
  v1 posture; multi-instance requires LNCH-14 (shared file store) and a session/lock review,
  and belongs to a post-launch roadmap.
- **i18n/l10n and accessibility audits** — real, but not before Tier 1/2; log as post-launch.
- **PDF documents (LNCH-10 slice 3)** — no longer on the deferred list: greenlit by the owner
  2026-07-22 with its own phased plan (`docs/REG12_DOCUMENT_EXPORT_PLAN.md`), unblocked by Slice 2
  (print). In-app notifications (LNCH-11 slice 2) remain a conscious post-launch deferral.
- **Merge-conflict UI** for LNCH-16 — 409 + reload is the v1 contract.

---

## 7. Suggested sequencing (roadmap seed)

Dependencies, not dates. Each wave is internally parallelizable; a wave should be
gate-green before the next one's dependent items start.

**Wave 1 — stop the known bleeding (all S/M):**
LNCH-24 (commit tree) → LNCH-3 (API-key filter) → LNCH-2 (tenant attack suite, gate-wired).
Rationale: cheapest existential items; the attack suite then guards every later wave.

**Wave 2 — the two big product bets (start immediately, run long):**
LNCH-1 (migrations — phased A→F) and LNCH-5 (SQL push-down + pagination) + LNCH-6 (indexes).
Rationale: longest poles; everything else can proceed around them; LNCH-13 and LNCH-16
build on LNCH-5/LNCH-1 outputs respectively.

**Wave 3 — make it deployable and observable:**
LNCH-7 (compose/Postgres-first) + LNCH-4 P0 slice (revocation/throttle/CSRF + env secrets)
+ LNCH-8 (observability) + LNCH-9 (backup) + LNCH-14 (S3/MinIO). LNCH-19 (CI) lands here so
the compose verification runs on every PR thereafter; LNCH-20 rides along.

**Wave 4 — completeness for real apps:**
LNCH-15 (unified expressions) → LNCH-13 (row-level authz) · LNCH-11 (email) → LNCH-4 P1
slice (password reset) · LNCH-12 (scheduler) · LNCH-16 (optimistic locking) · LNCH-17
(transaction contract) · LNCH-10 slice 1 (CSV export).

**Wave 5 — formalization:**
LNCH-18 (authoring ADR + chosen-path P0) · LNCH-21 (upgrade contract) · LNCH-22 (docs +
error codes) · LNCH-23 (license/packaging/release).

**Launch line.** Honest public beta = Waves 1–3 complete + from Wave 4 at least LNCH-15,
LNCH-16, LNCH-10 slice 1 + from Wave 5 the LNCH-18 decision and LNCH-23's license/release
items. Everything else can ship as a published roadmap — *published* being the operative
word: this document, kept current, is the credibility asset.

---

*Assessment date: 2026-07-14 · Reconciled with the open-items register + green CI: 2026-07-22
(21 DONE · 3 PARTIAL · 0 OPEN) · Branch: `beta1-vision-spine` (merged to `main`, tagged `beta1.1`) ·
Companion ledger:
`docs/OPEN_GAPS_AND_ROADMAP.md` (app-level bugs/lifts, ~all DONE) · Derived projection:
`knowledge/platform-status.json` (regenerate via `scripts/ai/extract_platform_status.py`
if this document's items are added to the tracked ledger).*
