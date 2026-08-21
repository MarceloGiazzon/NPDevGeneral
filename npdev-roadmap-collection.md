# NPDev — The Roadmap Collection (2026-08-18)

Ten roadmaps in three waves, ordered so a growing dev/analyst/tester force works in parallel on disjoint code surfaces. Everything here is a code feature; every definition of done is proven against a running generated app, a browser routine, or CI — never a document.

**Provenance:** built from **8 analyst surveys** of the real repo · **3 strategist proposals** (adoption / team-multiplier / depth-first) · **1 adversarial critique** with repo spot-checks. Ledger ground truth: **340+ items — ALL DONE** (2026-08-21, closeout session completed Waves 1-4).

## How to read this

A **wave** is a start order, not a calendar: wave-1 roadmaps start day one; a wave-2 roadmap starts when its crew frees up or its named blocker lands. Roadmaps inside a wave run in **parallel** — they were deliberately grouped by disjoint code surfaces so crews don't collide. Items inside a roadmap are in implementation order; **Blocked by: none** means it can start the moment someone is free.

## Execution map

| Wave 1 — day one | Wave 2 — as crews free | Wave 3 — ecosystem & ops |
|---|---|---|
| **R1 · Fast Inner Loop** (NPDevCli · NPDevMcp · dsl validators) | **R4 · One Expression Language** (dsl grammar · kernel expression sites) | **R8 · Pack Ecosystem** (ModelSourceResolver · generator/packs · pack CLI) |
| **R2 · Flow Engine Promises** (kernel resume/scheduler · adapters) | **R5 · Business-Data Semantics** (gateway/storage · schema · emitters) | **R9 · Team Server** (runbook/deployment emitters · ops CLI) |
| **R3 · Tester-to-Builder Harness** (npdev_explore · monitor · SeedDataService) | **R6 · Flows Reach the Real World** (kernel adapters: webhook · mail · documents) | **R10 · Kill the Frozen Editor Bundle** (generator templates · runtime admin) |
| | **R7 · Demo-Quality Generated App** (business-ui-app.mustache · shell.js) | |

Two long-running **design tracks** start in wave 1 but land in wave 3: PACK-9 role binding (R8.9) and the PACK-10 extension design (R8.11). Neither waits on other items' code — the critique confirmed both serializing dependencies proposed for them were false.

### ⚠ Pull forward — do these first, out of wave order

- **R9.1 — fix the emitted backup scripts** (S). Every current-generation app ships a `backup.sh` that fails on first use: it execs into a nonexistent `postgres` service and reads undefined variables (`DockerDeploymentEmitter.java:611–679`). Data-loss adjacent; a one-day fix must not queue behind two waves.
- **R8.1 — remote-pack $ref fragment fix** (S). Any multi-file remote pack — the normal shape — dies with a misleading "escapes the model root" error. The ledger itself names this "next slice's first fix".
- **R10.0 — the editor decision** (owner call, not engineering). Demote the shipped frozen editor to read-only, revive it, or replace it. This one decision gates R10 and stops the only one-way door in the authoring loop (drafts that silently drop 14 DSL sections).

---

# Wave 1 — Three crews, day one

The velocity multiplier (R1), the honesty repairs (R2), and the harness that lets the incoming testers hammer apps in parallel (R3). The critique's sharpest finding: two of three strategists buried the harness at the end while writing every earlier definition-of-done in terms of it — it must run first.

## R1 · Fast Inner Loop — 2S · 4M · 1L

**Goal:** cut validate → generate → build → boot from minutes to seconds, for humans and AI agents alike. Every later roadmap iterates through this loop — it pays for itself across the whole collection.

**Crew A:** `NPDevCli`, `NPDevMcp`, `NPDevContract/dsl` validators. Items 1–4 and 6 are mutually independent — splittable across 4 people on day one.

### R1.1 · Warm standalone validator — kill the Gradle fork `M`
Every semantic validate, classify, and re-sign currently forks a Gradle build (mostly `--no-daemon`: a single-use JVM per call) — the dominant latency in every authoring cycle. Package `ModelValidatorMain` + `ModelChangeClassifierMain` as a self-contained fat jar staged the runtimehost-libs way; switch `npdev_cli.py` `run_validate_semantic` (~:5471), the classify/re-sign path (~:4388), and `NPDevMcp/server.py` `tool_validate` to invoke it directly.
- **Blocked by:** none
- **Enables:** sub-second `npdev validate` for MCP agents; a genuinely interactive `npdev dev`; cheap iteration on every DSL change every other roadmap makes.
- **Done when:** `npdev validate model` completes with no Gradle wrapper process spawned (verified by process inspection), emits a byte-identical `npdev-validation-report.v2`, and the CLI prints the measured cycle time; dev-loop and MCP use the same path.

### R1.2 · Incremental dev-loop builds — drop `clean`, share a warm daemon `S`
Change `_build_phase` (`npdev_cli.py:4365` — the critique corrected the file two proposals miscited) from `clean build -x test --no-daemon` to an incremental build on a session-scoped daemon, keeping clean-build as the automatic fallback. Determinism is already gate-proven, so incremental output is safe to trust.
- **Blocked by:** none
- **Enables:** dev-loop cycles in seconds; shrinks the Windows stop-before-build downtime window.
- **Done when:** a one-field model edit rebuilds the generated app without `clean` on a warm daemon, and `npdev dev` prints a before/after timing pair.

### R1.3 · MCP parity: the four missing agent-context tools `S`
Add `npdev_validate_structural`, `npdev_get_constrained_schema`, `npdev_core_context`, and `npdev_app_context` to the MCP registry — thin plumbing over four backends that already exist and work. MCP-only agents simply cannot reach them today.
- **Blocked by:** none
- **Enables:** remote/sandboxed agents — exactly the client an external adopter's AI tooling will be — get the full authoring substrate without a repo checkout.
- **Done when:** the four tools appear in the registry with tests mirroring the blind-agent eval, and a blind agent reaches the constrained schemas with no filesystem access.

### R1.4 · suggestedFix + helpKey on every ERROR diagnostic `M`
`ValidationDiagnostic` already carries the fields and the dev loop already renders them — but only 2 of ~16 validator classes set them. Populate all of them, wiring `helpKey` to knowledge-card ids so `npdev_search_fix` links precedent fixes.
- **Blocked by:** none
- **Enables:** every validation error becomes a self-repairing instruction — measurably shorter fix loops for agents and for the incoming juniors.
- **Done when:** a corpus run over the 28 golden-scenario expected failures asserts 100% suggestedFix coverage, enforced by a unit test so no new ERROR rule ships bare.

### R1.5 · Member-level scaffolding: `npdev add concept|panel|flow|procedure` `M`
Tooling currently jumps from `npdev init` (whole app) straight to hand-editing `model.json`. A new verb writes a schema-valid member into the correct model array (reusing `MODEL_ARRAY_KEYS` knowledge), seeding required fields, with `--from` copying an exemplar out of the RAG corpus.
- **Blocked by:** R1.1 (soft — makes the scaffold's immediate validate instant)
- **Enables:** new analysts author members without memorizing the 4×-mirrored schema; agents stop learning required fields by failure; a surface the Manager or a future editor can call.
- **Done when:** `npdev add concept Order --from <exemplar>` writes a member that passes validation with zero errors on first try, covered by a CLI test.

### R1.6 · `npdev impact` — one change-preview report `M`
"What breaks if I change this?" takes four separate invocations today (migration diff, xref usage, author diff-gate, pack diff), so people routinely check only one. Compose them into a single typed JSON report — one CLI verb, one MCP tool, one schema under `schemas/ai/`.
- **Blocked by:** R1.1 (soft — three of the four backends are Gradle tasks; the composed command is only fast once the warm validator lands)
- **Enables:** safe agent-driven refactors; a one-call pre-regenerate ritual for every teammate; impact-before-save for any future editor.
- **Done when:** one command returns a single schema-validated report (migration classification + xref usages + pack diff) for a model pair, verified in CI.

### R1.7 · Hot metadata swap: METADATA_ONLY edits into the running JVM `L`
A signature-verified, SUPERUSER-gated "reload compiled model" admin endpoint (agent-proxy gating precedent; controller in `com.finalexec.api` + manifest), invalidatable kernel/runtimehost metadata caches, and the dev loop's existing fast path pushing re-signed `compiled-model.json` into the live app. Requires a runtimehost-libs restage per the standing rule.
- **Blocked by:** R1.1 + R1.2 (the loop this extends)
- **Enables:** zero-restart feedback for the most common edit class (labels, panels, layout) — the live-reload demo moment that sells a model-driven platform.
- **Done when:** editing a panel label with `npdev dev` running updates the served UI with the same app PID, proven by a browser routine.

## R2 · A Flow Engine That Keeps Its Promises — 2S · 4M · 1L

**Goal:** the durable flow engine is the platform's differentiator, and today its observable semantics lie: a quiet human wait dies terminal-STUCK at ~75 minutes while the docs promise weeks; a 24-hour reminder fires instantly; a capability call can hang a thread forever. Fix these before any real app encodes assumptions against the broken versions.

**Crew B:** kernel `ResumeCoordinator` / `KernelRunner` / scheduler + adapters. One roadmap on purpose — splitting this seam across waves (as one proposal did) creates merge contention on the same files. Items 1, 2, 3, 7 are independent starters.

### R2.1 · Kill the ~75-minute STUCK ceiling `M`
In `ResumeCoordinator.java:476–480`, a sweep that finds no candidate event calls `persistResumeBackoff("missing_event")` — so 20 quiet misses (~75 min of cumulative backoff) permanently strand exactly the long human waits `docs/FLOWS.md` advertises, and a late event can never revive the flow. A quiet miss must only re-schedule eligibility; only real exceptions count toward STUCK. Also revisit the eligibility skip on the synchronous wake path (:85–87) so an approval arriving mid-backoff wakes the instance promptly.
- **Blocked by:** none
- **Enables:** approvals lasting days or weeks actually work — the engine's entire pitch; the honest ground R2.5's timeouts stand on.
- **Done when:** a controllable-clock kernel test proves a flow still WAITING_EVENT (not STUCK) after simulated days, then resumes with sub-sweep latency when the event arrives; exception-path STUCK tests stay green.

### R2.2 · Un-stick operation + stuck-instance REST surface `S`
Wire the already-existing `ExecutionSummaryStore.listStuckSummaries` into `GET /api/executions/stuck`, add `POST /api/executions/{id}/unstick` (reset attempt count, back to WAITING_EVENT, clear eligibility), SUPERUSER-gated, in `com.finalexec.api` + the controllers manifest; CLI parity verb.
- **Blocked by:** none
- **Enables:** operational recovery without SQL surgery for genuinely-transient-error STUCKs — needed on day one of the first real deployment.
- **Done when:** a deliberately-stuck instance is listed, un-stuck, and completes when its event publishes — proven against a **running** app, since gate-run controller tests never compile.

### R2.3 · Automatic drain of the scheduled-event table `S`
The durable table, `due_at`, and the multi-instance `claimScheduledEvent` all exist — but nothing polls it, so a delayed event in an unattended app never fires. Add a `@Scheduled` runner in resume-bootstrap-spring calling the existing `processDueScheduledEvents`.
- **Blocked by:** none
- **Enables:** "send follow-up 24h after appointment" happens unattended; the substrate R2.4 needs.
- **Done when:** a booted app with a due scheduled event fires it within one tick with no REST poke, proven by an integration test with a fast tick.

### R2.4 · Make the flow-step SCHEDULE_EVENT genuinely deferred `M`
The canonical demo's `delayMinutes: 1440` reminder fires **instantly** today — `KernelRunner.java:1689–1811` publishes immediately and treats the delay as advisory metadata. Route it through the durable schedule table + the new drain, keeping immediate publish for delay=0. Observable behavior changes: BREAKING.md entry in the same commit.
- **Blocked by:** R2.3
- **Enables:** modeled reminders and follow-ups from flows fire at the modeled time; removes the trap that bites the first real author.
- **Done when:** a clock-controlled test proves delay>0 publishes only after the delay; conformance covers both delay=0 and delay>0; BREAKING.md entry present.

### R2.5 · Durable await timeouts + onTimeout branch `L`
The single biggest missing automation primitive: "wait for approval, but escalate after N hours". Add `timeout` + `onTimeout` to `awaitMatch`/`awaitEvent` (schema mirrored to all 4 copies), a deadline column checked by the existing 2-second sweep/claim infrastructure, and resume-into-timeout-branch execution.
- **Blocked by:** R2.1 (a wait must survive to its declared deadline before deadlines mean anything)
- **Enables:** SLA escalation, reminder mails, auto-cancellation — every approval workflow with a deadline; the flow demo that separates NPDev from CRUD generators.
- **Done when:** a crash-injection restart test proves the timeout branch runs durably after a JVM restart; conformance covers the new field and the coverage gate detects it.

### R2.6 · Non-zero capability timeout default, context-propagating executor (RUN-4) `M`
Any capability adapter without its own deadline can hang a request thread forever (`CapabilityExecutionPolicy.defaults()` is still `timeoutMs=0`). The confirmed blocker: the timeout path runs on `ForkJoinPool.commonPool`, silently dropping the MDC correlationId and Spring request context — an audit-identity downgrade. Build a dedicated bounded executor with capture/restore, do the call-graph trace the ledger asks for first, then flip the default (its DSL mirror is twin-pair-pinned — both move together).
- **Blocked by:** RUN-4's own recorded prerequisite (the call-graph trace)
- **Enables:** no generated app can hang forever on a misbehaving adapter, regardless of who wrote it; per-step `timeoutMs` becomes trustworthy. Closes RUN-4.
- **Done when:** a hanging-adapter kernel test fails with TIMEOUT while the log line still carries the correlationId and the execution context is not anonymous; RUN-4 flips DONE.

### R2.7 · Cron fire claim for multi-instance deployments `M`
The cron scheduler is per-JVM and in-memory: scale a compose file to two replicas and every scheduled flow double-fires. Add a `cron_fire` claim table keyed (flowName, tenant, scheduledFireTime) using the proven `selectForUpdateSkipLocked` + lease pattern from RUN-2.
- **Blocked by:** none
- **Enables:** safe horizontal scaling of generated apps with scheduled flows; completes the multi-instance-safety story RUN-2 started.
- **Done when:** two scheduler instances against one H2 database fire a cron flow exactly once per window — two-real-threads test with its own RED control.

## R3 · Tester-to-Builder Harness — 1S · 6M · 2L

**Goal:** turn the incoming testers into independent app-hammering builders. With no real users, team-built apps are the usage proxy — this roadmap is what makes N testers productive against N apps concurrently, and it produces the browser routines every other roadmap's definition-of-done leans on.

**Crew C:** `npdev_explore.py`, `npdev_monitor.py`, `SeedDataService`. Items 1, 2, 3, 6 are independent starters. Note: R3.3 deliberately does **not** wait for R3.2 — existing hand-written seed files on the canary suffice for v1 (a critique correction to one proposal's false blocker).

### R3.1 · `npdev explore suite` — whole routine corpus, one verdict `S`
Loop the app's `browser-routines/` in definition order, composing the existing run + verdict + run-record machinery; one suite-scoped summary; respect the per-app run lock so runs are serial within an app, parallel across apps.
- **Blocked by:** none
- **Enables:** each tester points at their app and gets a full red/green sweep; the substrate for `npdev test`, coverage, and per-sample CI browser jobs.
- **Done when:** one command runs every routine, records each run plus a summary, exits nonzero on any red — and two testers on two different apps don't interfere.

### R3.2 · Generative seeds (`$gen` tokens) + `npdev seed` verb `M`
Add `$gen:<generator>` value tokens (name, words, date-range, decimal-range, enum-pick, ref-pick-random) and a `count` shorthand to `SeedDataService`'s expansion, RNG seeded by seed id for reproducibility; a CLI verb wrapping the existing admin seed endpoints. Mirror the seed schema.
- **Blocked by:** none
- **Enables:** demo-ready apps in one command; referents for generated routines; realistic corpora for bench and load-shaped testing.
- **Done when:** a seed declaring 5k records across 3 concepts (including a reference field) loads on H2 in under a minute, reproducibly across two runs; the CLI verb works against a running app.

### R3.3 · `npdev explore generate` — a CRUD routine per concept, from the model `M`
Emit create/list/edit/delete routines per concept from the deterministic selectors the emitter already guarantees (`#concept-<Name>`, `[name=field]`, panel action buttons) and the probe's field inventory; enum fields select options, references wait for seeded referents, numerics fill valid values. The conformance gate and verdict function pick the output up for free.
- **Blocked by:** none — v1 uses existing hand-written seed files; `$gen` referents arrive with R3.2
- **Enables:** every new app is instantly hammered: N concepts → N green routines with zero hand-authoring; "browser-verified" becomes a measured claim.
- **Done when:** for gift-idea-tracker and one WmsOffice-class app, generated routines pass corpus conformance and run green against the live app — including a concept with an enum, a reference field, and a required numeric.

### R3.4 · `npdev test` — one verb, one verdict per app `M`
Compose three existing layers: a model-derived REST smoke plan (GET every concept endpoint), `*.scenario.json` acceptance, and the browser routine suite — one JSON report in the existing report family, zero per-app configuration.
- **Blocked by:** R3.1; R3.3 (supplies the browser layer for apps with no hand-written routines)
- **Enables:** one green/red per app for testers and per-app CI; the command an external adopter runs to trust their own app.
- **Done when:** against a booted sample, all three layers run with no per-app config files, one report is written, and the exit code is nonzero if any layer is red.

### R3.5 · `npdev explore coverage` — what the corpus actually touches `M`
Cross the probe's concept/URL/flow inventory with routine selector references and run history into a per-app table: concept → referencing routines → last green run, with an explicit UNCOVERED section. Flow coverage comes from acceptance scenarios' paths.
- **Blocked by:** R3.1; R3.3
- **Enables:** a growing tester team knows where to aim; staleness alerts via the existing cadence-ledger pattern; a Manager coverage screen later.
- **Done when:** the coverage table is verified against a sample where one concept deliberately has no routine — and says so.

### R3.6 · Real visual regression — pixel diff, thresholds, diff images `M`
Upgrade the baseline comparison from sha256 equality (cry-wolf on any anti-aliasing change) to a pure-Python pixel diff with per-screenshot thresholds and a stored diff PNG in the content-addressed blob dir; per-screenshot re-baseline via `explore accept`.
- **Blocked by:** none
- **Enables:** a usable UI-regression net for the analysts reviewing R7's UI changes.
- **Done when:** a 1-pixel change passes below threshold; a moved button fails with a written diff-image path; accepting one screenshot re-baselines only that one.

### R3.7 · `npdev bench` — per-app latency probe with saved baselines `M`
The only latency measurement anywhere is the nightly synthetic scale ladder. A tester who suspects "this panel got slow" on a real app has nothing — and RUN-1's "no full-table fetch on 100k rows" definition-of-done has no tool to measure with. Probe concept list/panel/query endpoints at seeded row counts, save p50/p95 baselines per app, diff on re-run. *(All three strategists missed this; the critique surfaced it.)*
- **Blocked by:** R3.2 (the seeded data that makes numbers meaningful)
- **Enables:** perf regressions caught by testers on real apps; the measurement tool R5.2's done-when needs.
- **Done when:** `npdev bench` on the canary at 100k seeded rows reports p50/p95 per endpoint and flags a regression against the saved baseline.

### R3.8 · `npdev monitor clone` — isolated instances for parallel testers `L`
Stamp out N instances of a built app without regeneration (distinct port, distinct H2 file, same jars) by orchestrating the seams `resolved-db-plan.json` and the _ops toolbox already parameterize; an `instanceOf` field so discovery lists clones as instances of the parent. H2-only in v1.
- **Blocked by:** R3.1 (the payoff is concurrent suites); a port-allocation policy decision
- **Enables:** true parallel hammering without lock queues or data trampling; per-PR ephemeral instances in CI; A/B of two model versions side by side.
- **Done when:** three instances boot on distinct ports with isolated data, discovery lists them as instances, and two concurrent suites against two instances both record green with no crosstalk.

### R3.9 · Routine recorder in the generated shell `L`
A record mode in the generated UI itself that logs clicks/fills/selects as routine steps using the canonical selectors (via a data-attribute registry, not DOM paths) and exports schema-conformant JSON. Sidesteps the externally-owned ScrapForAI engine entirely; `explore validate` guarantees recorded = runnable.
- **Blocked by:** R3.3 (shares the canonical-selector registry work)
- **Enables:** non-programmer analysts author routines by clicking — the only way the corpus scales with the team instead of with one engineer; recorded sessions become pinnable bug repros.
- **Done when:** record a create-edit flow in a running app, export, pass `explore validate`, replay green — with no hand edits.

---

# Wave 2 — Depth and demo quality, as crews free

R4 and R5 are the pre-1.0 clock: every model written against today's crippled expression dialects, and every schema shape that fossilizes, is a codemod now and a production-data migration later. R6 gives the flow engine a real world to touch. R7 makes team-built apps the credible usage proxy — sequenced after the harness so every item is proven by a routine, not by hand.

## R4 · One Expression Language Everywhere — 2M · 2L

**Goal:** the platform owns one capable grammar (ComputedExpression: arithmetic, boolean logic, lambdas, functions) but exposes four crippled dialects where authors actually write logic. Unifying is mostly deleting artificial ceilings — the runtime already evaluates the full grammar.

**Crew A after R1**, plus kernel expression call sites (a different KernelRunner region than R2's resume work — parallel-safe). R4.4 is independent of R4.2: invariants evaluate through the gateway semantic policy, a different call site (critique-verified).

### R4.1 · Lift the 5-function ceiling on default/derived expressions `M`
`lineTotal = quantity * unitPrice` — the single most common derived field in any business app — is refused today by an author-time whitelist of five string functions (`FieldValueValidation.java:86–90`), while the runtime already evaluates the full grammar. Widen the validator; keep cycle detection and the nested/id refusals. Pure widening, no codemod.
- **Blocked by:** none
- **Enables:** declarative line math without procedures; `nextNumber()` and `role()` as future function registrations ride the same widened path.
- **Done when:** a conformance field declaring `derivedExpression "quantity * unitPrice"` validates, generates, and persists the computed value on a booted app and in the CI engine matrix; coverage gains a detector.

### R4.2 · ComputedExpression in flow branches and orchestration conditions `M`
The two places business logic actually branches are hand-rolled `==`/`!=`-only evaluators (`KernelRunner.evaluateCondition:2825`; the orchestration twin at :1829 of the generated-CRUD support). Replace with ComputedExpression, keeping the legacy evaluator as fallback exactly as the invariant engine already does; validate branch conditions at compile time.
- **Blocked by:** none
- **Enables:** `amount > 1000 && category == "travel"` where it matters; the one-grammar promise the docs already make becomes true.
- **Done when:** a branch using `&&` and `>` executes correctly in a kernel test; every existing sample's conditions evaluate identically; a malformed condition refuses at validate time.

### R4.3 · Query predicate grammar v2: OR, IN, contains, null tests, reference paths `L`
Queries today are AND-combined single-field comparisons with an explicit OR refusal. Add OR-groups, IN lists, contains/startsWith, is-null, and reference-path left sides reusing groupBy's proven 3-hop join resolution — all SQL through the SqlDialect seam so four engines render it correctly.
- **Blocked by:** R4.1 (sequence within the same dsl module to avoid parser merge churn)
- **Enables:** real report filters ("open or overdue", `customer.region == 'SUR'`, name contains) without procedure workarounds; pickers and selectors inherit the same grammar; feeds R7.3's filter UI.
- **Done when:** a corpus query combining OR-groups, a reference path, and a date comparison returns correct rows on a live H2 app and in the CI multi-engine matrix.

### R4.4 · Declarative cross-concept invariants at aggregate scope `L`
WMS/ERP-core rules like "sum of allocation qty ≤ order-line qty" currently need imperative procedures. Add `aggregates[].invariants` (schema mirrored 4 ways) binding the aggregate tree's collections into the expression environment, evaluated pre-commit in the same transaction slot `aggregate.onValidate` uses.
- **Blocked by:** none — evaluates through the gateway semantic policy, independent of R4.2's call sites
- **Enables:** business rules live in the model — inspectable, validatable, MCP-checkable — instead of code.
- **Done when:** an invariant like `lines.all(l => l.qty > 0) && lines.sum(qty) <= header.totalQty` vetoes a bad commit atomically on a live app with the failing rule named in the API error; conformance + coverage updated.

## R5 · Business-Data Semantics Before They Fossilize — 3M · 5L

**Goal:** audit, fast uniqueness, sequences, soft delete, field-level security, per-locale labels, printable documents, effective-dated values — each is a pure codemod against team-built apps today and a production-data migration after 1.0. The heaviest roadmap in the collection, and the one with the clearest deadline logic.

**Crew B after R2**, plus schema/emitter hands. Items 1, 2, 5, 6, 7 are independent starters; 3 needs R4.1; 4 needs 2.

### R5.1 · Audit trail end-to-end; retire the inert `auditPolicy` knob `M`
"Who changed what, when" is a hard ERP requirement — and the pieces already exist unconsumed (`AuditLogStore` port, audit-inproc/audit-postgres adapters). Record create/update/delete with actor, timestamp, and before/after field diff in the gateway write path; emit a generated history endpoint + panel. The schema-declared `auditPolicy` knob is consumed by nothing — enforce it or remove it by codemod so no inert knob ships in 1.0.
- **Blocked by:** none
- **Enables:** audit history on every generated app; the foundation soft-delete restore and effective-dated history build on.
- **Done when:** an audited concept shows field-diff history in a generated view on a live app; an unaudited concept writes nothing (RED control); the codemod ships in the same commit.

### R5.2 · Index-backed uniqueness checks (RUN-1 item 4) `M`
Every create/update of a concept with a unique invariant still loads the entire tenant table into the JVM — the platform's worst remaining data-scale landmine. Replace with an indexed SQL pushdown through the dialect seam, pinning the exact current semantics (trim + lowercase strings, cross-type comparison) on all four engines. Decide once, deliberately, before soft delete complicates it with "unique among live rows".
- **Blocked by:** none (the semantics design is the work)
- **Enables:** unique-constrained concepts stay fast at real volumes; closes RUN-1; the semantics baseline R5.4 reuses.
- **Done when:** create/update on a 100k-row concept performs an indexed lookup (measured with `npdev bench` — no full-table fetch), with dialect-conformance tests proving semantics unchanged on H2/PG/MySQL/SQLServer; RUN-1 flips DONE.

### R5.3 · Declarative numbering: `sequences[]` + `nextNumber()` `M`
Nothing in the DSL or kernel can express INV-2026-0001 — a guaranteed first-week ask from any ERP author. Add `sequences:[{name, format, scope}]` (4 schema mirrors) and a `nextNumber()` function in the registry; allocation SQL becomes new SqlDialect methods, running inside the same gateway transaction as the insert.
- **Blocked by:** R4.1 (defaultExpression must accept function calls beyond the 5-string whitelist)
- **Enables:** human-readable document numbers, per-tenant, per-year — on records and on R5.7's printed PDFs.
- **Done when:** two concurrent creates never collide on a live app; correct on all four engines in the CI matrix; conformance exercises it.

### R5.4 · Soft delete with filtered reads and restore `L`
Physical deletes are wrong for most business records, and the repo has zero soft-delete support. `concept.softDelete:true` adds a `deletedAt` column (the schema lifecycle must classify it on regeneration of an **existing** app, not boot-once); delete flips the timestamp; queries/pickers/reference resolution exclude deleted rows; a restore action exists; uniqueness applies among live rows only.
- **Blocked by:** R5.2 (unique-among-live-rows must reuse that pushdown's decided semantics)
- **Enables:** restore UI, audit pairing, and the death of hand-rolled "inactive" status fields.
- **Done when:** on an existing app **with pre-existing data**: regenerate migrates the schema, delete flips the flag, grids exclude the row, restore brings it back, and a unique value on a deleted row can be reused — all live, codemod in the same commit.

### R5.5 · Server-enforced field-level access `L`
`visibleWhen`/`readonlyWhen` are documented presentation-only — any authenticated writer can PATCH any field the row rule allows, a real gap for cost and payroll fields. Add `field.access {read, write}` expressions evaluated server-side in the gateway semantic policy (which already evaluates per-record access), completing the ladder: role ceiling → row scope → field scope. UI metadata carries the resolved flags so screens hide/disable coherently.
- **Blocked by:** none
- **Enables:** "cost price invisible to warehouse operators" from one model; role-differentiated screens without forked panels.
- **Done when:** a non-manager PATCH on a manager-write field is rejected — proven by an attack test against a **running** app — and the field renders read-only in a browser routine.

### R5.6 · Per-locale label maps `L`
The team's own apps are authored in Portuguese against English platform defaults. Every label site accepts string **or** per-locale map, resolved server-side into the UI metadata bundle by user locale (the safe path given the frozen React bundle has no producer). A breaking schema change that is trivial now and brutal after 1.0 — codemod in the same commit, 4 mirrors.
- **Blocked by:** none
- **Enables:** bilingual deployments and per-user locale.
- **Done when:** a demo app renders fully in pt-BR and en by switching user locale live in a browser routine; the codemod converts an existing corpus model losslessly; the schema-mirror checker stays green.

### R5.7 · Author-controlled document templates: bands, line items, logo `L`
The canonical ERP artifact — an invoice with header, line-item table, totals, and a logo — is inexpressible: `documents[]` today is only name/concept/title/pageSize/margins. Extend it to bind an aggregate (header + collections as bands) with field bindings reusing the panel-binding shapes, rendered by the existing SSRF-hardened PDF adapter.
- **Blocked by:** none — pairs naturally with R5.3 for numbered invoices
- **Enables:** per-app invoices, pick lists, and delivery notes with zero custom code.
- **Done when:** an aggregate-bound document with bands and a logo serves a correct multi-line PDF from a live app, verified by a routine; conformance carries one.

### R5.8 · Effective-dated values — decide the shape now, prototype thin `L`
Price lists, tax rates, assignments — the survey's one true differentiator versus CRUD generators, and precisely the schema-shape change that becomes a production-data migration after 1.0. Decide the model shape (validFrom/validTo + as-of resolution) and prove it thin: one flagged concept, as-of read parameter, on one sample. Full UI/editing depth can trail; the shape cannot. *(All three strategists dropped this; the critique restored it.)*
- **Blocked by:** none (its own design pass is the first step)
- **Enables:** price-list-class modeling; the 1.0 schema freezes with temporal semantics reserved, not bolted on.
- **Done when:** an as-of read returns the rate effective on a given date on a live sample app, and the decided shape is recorded as the ledger item's guard.

## R6 · Flows Reach the Real World — 3M · 1L

**Goal:** webhook and notification adapters are in-memory fakes; external systems cannot trigger flows; "nightly report emailed as PDF" cannot compose from its existing pieces. The capability vocabulary 1.0 freezes must be one that actually touches external systems.

**Kernel-adapter crew** (Crew B split, or a new hire lane — the adapter pattern is well-established by external-ai-http). Items 1 and 3 start whenever capacity frees; 2 needs R2.1.

### R6.1 · `webhook-http` — a real outbound webhook adapter `M`
Today `webhook.post` appends to an in-memory list — a modeled app cannot call any external system. New adapter cloning external-ai-http's proven posture: fail-closed destination allowlist, timeouts, bounded retry, plus HMAC request signing and a delivery-record table reusing the schedule table's claim/attempt shape.
- **Blocked by:** none
- **Enables:** flows and orchestration rules call external REST endpoints; the outbound half of app-to-app eventing.
- **Done when:** a flow capability call delivers a signed POST to a real local test server, retry-on-hang proven the RUN-4 hanging-socket way; an unlisted host is denied fail-closed with a named error.

### R6.2 · Inbound `webhooks[]` — model-declared endpoints, HMAC, payload mapping `M`
The "payment confirmation" use case the flow docs cite as the engine's reason to exist has no door: third parties would need NPDev JWT auth and envelope shape. A new top-level model array (source, HMAC secret ref, event name, field mapping) generating `POST /api/hooks/{source}`. Full REG-108 four-place threading + the PACK-11 fifth place + 4 schema mirrors apply.
- **Blocked by:** R2.1 (the waiting flow must survive until the third-party webhook arrives)
- **Enables:** payment processors, Git hosts, and forms trigger flows directly.
- **Done when:** a raw HMAC-signed curl POST (no NPDev auth) resumes a waiting flow in a booted app; a wrong signature is rejected 401; conformance declares a source.

### R6.3 · documentRender capability + MIME mail — scheduled reports end-to-end `M`
Every piece of "nightly report emailed as PDF" exists separately — cron flows, SSRF-hardened PDF rendering, real SMTP — and cannot compose: the renderer is REST-only (no flow step can call it) and the mail adapter is plain-text-only. Expose the renderer as a flow-callable capability; extend mail to MIME multipart (HTML body + attachments from flow state).
- **Blocked by:** none
- **Enables:** the most-requested class of business automation for WMS-style apps; ad-hoc "email me this record as PDF".
- **Done when:** a cron-scheduled sample flow renders a concept list to PDF and emails it with the attachment, verified against a GreenMail-style double in a booted app.

### R6.4 · Cross-app event bridge — first MessagingCapability implementation `L`
Apps generated by the same platform cannot talk to each other except by hand-rolled REST: the EventBus is in-JVM and `MessagingCapability` has zero implementations. A model-declared subscription delivers matching events over R6.1 into the peer's R6.2 endpoint, with idempotency-keyed exactly-once consumption. No broker dependency.
- **Blocked by:** R6.1; R6.2
- **Enables:** multi-app business processes — order app notifies warehouse app — which the platform currently lacks entirely.
- **Done when:** two apps side by side: an event in app A durably resumes a flow in app B, surviving B being down at publish time; a duplicate-delivery test proves exactly-once consumption.

## R7 · Demo-Quality Generated App — 1S · 6M · 3L

**Goal:** the generated UI a builder demos to their boss must feel like a real product: helpful feedback on every save, filters and drill-down, spreadsheet import, honest async status, keyboard-first data entry, and a layout that survives a tablet.

**Crew D:** `business-ui-app.mustache` / `shell.js` templates — disjoint from every other lane. Starts once R3.1–R3.3 exist so every item lands with a routine proving it. Items 1, 2, 3, 9, 10 are independent starters.

### R7.1 · Toasts + inline field-level validation errors `M`
All feedback today is a single status-bar line. Replace it with dismissible toasts; render field-addressable 400/422 detail as per-field highlights with preserved input; catch `requiredWhen` violations pre-submit (the predicates are already evaluated client-side). The most visible polish gap a new user hits on their first bad save.
- **Blocked by:** none
- **Enables:** R7.7's async surface has somewhere to land; the CSV wizard reuses the per-row error rendering.
- **Done when:** a routine submits an invalid form and asserts per-field highlights, preserved input, and a dismissible toast; a valid save shows success.

### R7.2 · Empty-state onboarding calls-to-action `S`
A fresh app renders "(empty)" and looks dead. Render "No X yet" + a Create button + (when seed files exist and permission allows) a "Load sample data" action wired to the existing seed endpoints — the machinery exists but is invisible from where users land.
- **Blocked by:** none
- **Enables:** self-serve first-run experience for the incoming wave of app-builders.
- **Done when:** a zero-row canary renders the CTA on every grid and loading seeds through it populates the grid — browser-verified.

### R7.3 · Structured per-column filters + filter-honoring export `M`
The server already supports rich operators with SQL pushdown; the UI offers one substring search. Render type-appropriate operator+value controls per filterable column, and make Export CSV honor the active filters — resolving the disclosed "export ignores your search" mismatch.
- **Blocked by:** none (R4.3 later widens what the filters can express)
- **Enables:** saved views and dashboard drill-down (both need URL-addressable filtered-grid state).
- **Done when:** per-column filters round-trip against the concept API, export matches the on-screen rows, and a routine proves both on the canary.

### R7.4 · Saved views per user via the property cascade `M`
Name/save/select/delete filter+sort+column sets per grid, persisted through the existing properties API at user scope — zero new server endpoints; workspace-scoped views are shared with the team.
- **Blocked by:** R7.3
- **Enables:** shareable team views; per-role landing defaults; daily-driver ergonomics.
- **Done when:** a view persists across sessions, and a workspace-scoped view saved by user A is visible to user B in a live two-session browser test.

### R7.5 · Dashboard drill-down: gadget click-through to a filtered grid `M`
Every chart is a dead end today. Attach each KPI/bar/line/table datum's groupBy keys as a click target navigating to the source concept's grid pre-filtered to that slice.
- **Blocked by:** R7.3
- **Enables:** dashboards become operational tools instead of status posters.
- **Done when:** clicking a bar segment in a routine navigates to the grid showing exactly the matching rows.

### R7.6 · Generic CSV import wizard on every concept grid `L`
A business user's first act in a fresh app is "load my spreadsheet". Generator-emitted parse → per-row-validated preview → atomic commit, generalizing the propose-review-commit pattern already proven by three hand-authored WmsOffice wizards; upload via the existing files endpoint or paste.
- **Blocked by:** R7.1 (soft — reuses per-row error rendering)
- **Enables:** migration from spreadsheets and legacy systems — the adoption moment.
- **Done when:** any grid's Import round-trips its own Export unchanged; a file with bad rows shows per-row errors and commits only accepted rows atomically.

### R7.7 · Async flow-execution progress surface (202 tracking) `M`
The UI contract itself warns "a UI that shows Saved! on a 202 is lying" — and the contract half (invocations carry `execution.statusRoute`) already shipped with no consumer. Show a pending indicator polling the status route, resolve to a success/failure toast, keep a recent-executions rail in the shell.
- **Blocked by:** R7.1
- **Enables:** flow-backed writes become the trustworthy default authoring choice; pairs with R2.5's deadline-approval demo.
- **Done when:** against a flow parked on awaitEvent, a routine sees pending → publishes the event → sees the completion toast and the execution in the rail.

### R7.8 · Bulk selection + bulk actions on grids `L`
Checkbox column, select-all-on-page, and an actions bar (delete, set-field-on-selected, lifecycle transition) backed by a new batched endpoint for atomicity and audit — building on the proven single-record patch primitive, permission-filtered via the actions catalog.
- **Blocked by:** design decision: batched endpoint (recommended) vs client-side loop
- **Enables:** import-fix workflows and admin cleanup without SQL — day-one expectations for anyone managing more than a screenful.
- **Done when:** select-all + delete + set-field work with a confirm dialog reporting per-row outcomes, permission-filtered, routine-proven.

### R7.9 · Keyboard-first data entry in Panel/Workbench editing `M`
The sole keydown handler in the entire generated SPA is Enter-to-search — while the platform's declared target is GeneXus-style warehouse operator consoles, where heads-down Tab/Enter/arrow cell navigation **is** the product. Focus management, arrow navigation between cells/rows, Enter-to-commit-and-advance in workbench and inline-edit grids. *(All three strategists missed this; the critique restored it.)*
- **Blocked by:** none
- **Enables:** operator throughput — the core loop of the WMS-class apps NPDev exists to recreate.
- **Done when:** a routine drives a complete create+edit on a workbench sample entirely via keyboard events — zero mouse clicks after login.

### R7.10 · Responsive/mobile pass on grid, forms, and workbench `L`
One 760px breakpoint exists; the 58KB workbench template has none. Collapse grids to cards, stack forms single-column, independent band scrolling, sidebar becomes a drawer. Warehouse-floor tablets are the natural device for the target consoles.
- **Blocked by:** none
- **Enables:** floor-device operation; credible demos on whatever device gets pulled out.
- **Done when:** routines run green at 390px and 768px viewports on the canary and a workbench-bearing sample.

---

# Wave 3 — Ecosystem, operations, and the last structural debt

Packs become the unit of independent team work (R8), one office box hosts the whole portfolio unattended (R9), and the frozen editor bundle stops decaying (R10). Several first items are S-sized with no prerequisites — pull them into idle capacity any time.

## R8 · Pack Ecosystem: Distribute, Bind, Link — 2S · 5M · 3L · 1XL

**Goal:** the fetch/cache/lock substrate is live-proven; what's missing is the front door (discover, publish), the trust layer (signing, pinning), and the three structural mechanisms — role binding, sealed-jar linking, in-place variation — that must land before packs published to NPR bake concrete role literals and fork-based variation into the nascent ecosystem.

**Pack crew:** `ModelSourceResolver`, `generator/packs`, pack CLI verbs. R8.9 (PACK-9) and R8.11's design pass are **design tracks that start in wave 1**; neither waits on other items' code. The catalog index format is one shared decision for R8.4 + R8.5 — design them together.

### R8.1 · Remote-pack $ref fragment resolution from cache `S` — *pull forward*
Any fragment-structured remote pack — the normal shape for a non-trivial pack — dies with a misleading "escapes the model root" error. In `resolveJsonRefUnderRoot`, skip the app-root boundary specifically for cache-resident packs while keeping the pack's-own-directory boundary.
- **Blocked by:** none
- **Enables:** realistically-sized packs become distributable; NPR can hold fragment-structured packs.
- **Done when:** a git+file:// pack with fragments fetches and generates fully offline from cache; a fragment escaping the pack's own directory still refuses (RED control).

### R8.2 · `npdev pack export` — retire the PowerShell extraction script `S`
The only path from a working app concept to a reusable pack is an external one-concept PowerShell script — violating the standing "the platform emits solutions" rule. A real CLI verb: multi-member export with refs rewritten to intra-pack form.
- **Blocked by:** none
- **Enables:** analysts extract packs without PowerShell; the ecosystem gets seeded with real packs; a later Store "export as pack" button calls the same path.
- **Done when:** an exported pack validates and composes back into a throwaway model cleanly; the .ps1 is deleted or delegates.

### R8.3 · `npdev pack verify` — the pack conformance harness `L`
Nothing today proves a pack works before it's published or consumed. Generalize the proven proof-script shape into one command: compose a minimal app with the pack + declared deps, generate + build + boot on H2, smoke every contributed concept endpoint and panel.
- **Blocked by:** R8.2 (soft — verify wants real multi-member packs to prove itself against)
- **Enables:** "this pack is green" becomes a fact the catalog and team rely on; a publish-gate extension; per-pack CI.
- **Done when:** green on identity, workspace, and labeling; a pack with a deliberately broken internal reference fails with a named error (RED control).

### R8.4 · `npdev pack search` + the NPR catalog index `M`
Discovery is the ecosystem's missing front door: every fetch/cache/lock primitive is proven and the NPR repo already exists. A generated `catalog-index.json` at the repo root; search fetches + caches it; `pack add --from-catalog <name>` writes the correct coordinate and lock; the generated app's Store panel gains remote entries.
- **Blocked by:** the one-time index-format decision (shared with R8.5)
- **Enables:** add-by-name instead of hand-written git coordinates; the place publisher identity lands when signing arrives.
- **Done when:** search returns NPR-hosted packs; add-from-catalog produces a lock that generates offline; the Store panel lists remote entries.

### R8.5 · `npdev pack publish --push` — close the author loop `M`
Extend the existing publish gate (semver honesty, migration chains) to commit the pack + regenerated index to the catalog repo over git, deferring OCI entirely (its fetch is still a stub). Refuse any mutation of an already-published version before any push.
- **Blocked by:** R8.4 (shared index format)
- **Enables:** the fully closed CLI loop: export → verify → publish → search → add; immutability enforced at the moment of publication.
- **Done when:** a push lands a pack a fresh machine consumes by name; a re-publish mutating a published version is refused locally.

### R8.6 · Digest pinning for coordinates + transitive `from` `M`
A mutable git tag can change pack content under a teammate's fresh clone — the lock protects one machine only. Pin coordinates to digests in the lock and verify on fresh resolution; and let a pack declare its **own** remote deps so transitive reuse doesn't require vendoring. *(Both are declared PACK-8 gaps absent from all three proposals — critique-restored.)*
- **Blocked by:** none
- **Enables:** team-wide reproducibility; pack-on-pack reuse.
- **Done when:** a mutated tag is refused against the lock on a fresh clone; a pack declaring its own remote dep composes end-to-end.

### R8.7 · Pack signing + trust policy `M`
A pack fetched from a public repo is executable model content verified only by a content digest. Detached signatures, a `trust:{mode}` config, and `--allow-unsigned` recorded in the lock — shipped in the same wave as distribution, because the cheap moment for signing is before distribution scales. *(Critique-restored: all three proposals shipped the front door with zero authenticity.)*
- **Blocked by:** R8.4 + R8.5 (the publish/consume pipeline it signs)
- **Enables:** safe third-party pack consumption — the precondition for an ecosystem beyond the team.
- **Done when:** a tampered pack is refused with a named error; consuming an unsigned pack requires the explicit flag and the lock records it.

### R8.8 · Pack-declared seed data `M`
Packs that work out of the box on first boot: country codes, units of measure, demo datasets. A `seeds` member threaded through the REG-108 four places + the PACK-11 fifth (reference rewriting), targets rewritten to pack-qualified names, executed on first boot through the governed save path.
- **Blocked by:** none — pairs with R3.2 so pack seeds can be generative
- **Enables:** showcase packs for the catalog; satellite packs shipping their own lookup tables.
- **Done when:** a pack's seeds insert on first boot; a seed naming a concept the pack doesn't own is a compile error; regeneration doesn't duplicate rows.

### R8.9 · PACK-9: `role()` token + compile-time roleBindings `L` — *design track, starts wave 1*
The last hard blocker to genuinely reusable packs: internal role checks hardcode concrete names, so composing one pack into two apps with different role vocabularies is impossible without forking. Resolve the prototype's 3 open design questions (binding syntax for structured fields, `role()` semantics in expressions, rewrite-pass timing), then implement the compile-time rewrite in the composer. *(Critique-verified: does **not** wait on R4.1 — the prototype is a composer rewrite, not a runtime function consumer.)*
- **Blocked by:** its own design pass (the ledger's verdict: "NOT small/mechanical")
- **Enables:** security-aware packs — approval flows, admin panels — any app adopts by mapping roles instead of forking. Closes the only OPEN pack item.
- **Done when:** one pack writing `role('approver')` composed into two apps mapping different concrete roles enforces correctly at runtime in both (live-verified); an unbound role refuses at composition naming pack and key; PACK-9 flips DONE.

### R8.10 · BUILD-2: sealed-pack jar app-linking + `npdev pack seal` `L`
The ledger survey's single highest-leverage open item: every consuming app still regenerates and recompiles every pack's concepts. The builder, ABI manifest, and byte-identical double-seal proof all exist — what's missing is the linking: generated component/entity-scan entries + a build.gradle dependency on the jar instead of generated sources, plus the CLI wrapper.
- **Blocked by:** none
- **Enables:** a pack becomes an artifact you fetch, verify, and **link** — immutability, faster builds, and with R8.4–8.7 the complete pack-as-product story.
- **Done when:** an app generated with the identity pack as a sealed jar (no identity sources in its tree) boots and serves identity CRUD; the jar is byte-identical across two independent builds; BUILD-2 flips DONE.

### R8.11 · PACK-10 steps 2–5: in-place extension, conflict refusal, UI composition `XL`
Variation without duplication: an extension pack additively patches fields/panels onto a base pack's concepts via the diff engine, with computed sealedness, hard refusal on collisions naming both packs, and generated-UI merge with app-owned ordering — the part the ledger itself calls "most likely to be underestimated". *(Critique-verified: the design pass runs in parallel with R8.10's implementation — only the sealedness-interaction decision is shared.)*
- **Blocked by:** the sealedness-interaction decision with R8.10; its own design pass
- **Enables:** one base pack + N thin variant packs instead of N forks — hospital vs store variants of one vertical.
- **Done when:** an extension adding a nullable field composes (impact shows ADD only); a colliding field refuses naming both packs; extending a sealed pack refuses; the merged panel renders with app-controlled ordering — all in a live generate→build→boot proof.

## R9 · Team Server — 3S · 6M · 1L

**Goal:** one office box hosts the whole team's app portfolio unattended: bundles that deploy with only Docker, service supervision, working engine-aware backups with retention and restore-proof, shared TLS ingress, safe upgrades, and someone finding out **before** a human notices the app died at 2am.

**Ops crew:** `OperationalRunbookEmitter` / `DockerDeploymentEmitter` + ops CLI verbs. Ordering fix from the critique: the migration advisory lock (R9.3) lands **before** the automation that multiplies simultaneous boots (R9.5/R9.6) — not after, as one proposal had it.

### R9.1 · Fix the emitted backup/restore scripts, engine-aware `S` — *pull forward, day one*
Verified still broken: `DockerDeploymentEmitter.java:639/:676` execs into a nonexistent `postgres` service and reads undefined `POSTGRES_*` vars — every current-generation app ships a backup script that fails on first use. Exec into the `database` service the engine-generic compose actually emits, read the .env vars, and derive dump/restore from the engine profile so MySQL gets mysqldump and a new engine is a JSON row.
- **Blocked by:** none
- **Enables:** an honest backup claim; R9.9's scheduling; MySQL parity on the deploy path. A backup script that fails on first use is worse than none.
- **Done when:** on freshly generated Postgres **and** MySQL apps, backup dumps the actual resolved database and restore round-trips seeded records, proven by extending the docker-linux proof.

### R9.2 · Log rotation across runtime host, launchers, and compose `S`
Rolling size+time-capped file logging in the runtime host (keeping the correlationId pattern), retention pruning in the launchers, and log caps on every emitted compose service — today every path is unbounded.
- **Blocked by:** none
- **Enables:** months-long unattended runs with bounded disk; log exports that stay shippable.
- **Done when:** a soak past the cap shows old segments deleted while log tailing still works, on both the bare-launcher and compose paths.

### R9.3 · Migration advisory lock — a real mutex for concurrent boots `M`
Schema evolution's collision handling is detect-and-refuse, explicitly not a lock, and first-ever boots are unprotected. Advisory lock on Postgres + a lock table on H2, closing the interleaved-migration race — **before** service supervision and restart automation make simultaneous boots routine.
- **Blocked by:** none — must land before R9.5/R9.6
- **Enables:** safe restart/redeploy automation; the eventual multi-instance story.
- **Done when:** two deliberately simultaneous boots against one fresh database serialize cleanly in a test that currently hits the unprotected first-boot window; same proof on H2.

### R9.4 · Promote guarded Start/Stop into the emitter + POSIX twins `M`
The duplicate-PID guard, port-conflict guard, and log archiving live only in the AppGen PowerShell layer — a documented two-writers drift hazard. Move them into `OperationalRunbookEmitter` so every generation path gets them, delete the PowerShell copies, and add .sh twins (the toolbox has exactly one POSIX script today).
- **Blocked by:** none
- **Enables:** one _ops contract everywhere; Monitor/Manager Start/Stop working identically on all apps; non-Docker Linux operation; the launcher R9.6 supervises.
- **Done when:** every app gets the guarded launchers (+ .sh twins), the PowerShell copies are deleted, determinism and engine-parity checks stay green, and a Manager-created app shows working Start/Stop in the Monitor.

### R9.5 · `npdev package` + `npdev upgrade` — deploy without the toolchain `L`
Today the deploy host must be a full NPDev dev machine. One bundle (jar, compose, Caddyfile, env example, backup scripts, runbooks, compiled-model baseline); upgrade reuses the migration-diff engine + destructive-ack flow that currently lives only in the platform-repo build script, with the ack threaded through `--apply`.
- **Blocked by:** R9.1 (the bundle must ship working scripts); R9.3; R9.4
- **Enables:** team servers with only Docker installed; testers deploying their own instances; a future registry/CI pipeline has an artifact shape.
- **Done when:** on a machine with only Docker, the package boots via compose; upgrade prints the same plan and ack token the build script does, and apply boots the upgraded app.

### R9.6 · `npdev service install` — supervision without Docker `M`
Outside Docker's restart policy, nothing restarts a crashed app or starts one at boot — and a Windows box in the office is this platform's likeliest small-team shape. Emit and register a systemd unit or Windows service wrapper around the app's own emitted launcher; clean uninstall.
- **Blocked by:** R9.3; R9.4 (wraps the emitted launcher)
- **Enables:** unattended hosting; meaningful uptime; safe overnight reboots of the office box.
- **Done when:** after install, a kill -9 of the app is followed by an automatic restart observable via the Monitor probe; uninstall removes it cleanly on both OSes.

### R9.7 · Multi-app ingress: one shared reverse proxy per box `M`
Each app's Caddyfile claims 80/443 exclusively, so exactly one app per box gets TLS. Generate a single Caddy config from the Monitor's app inventory mapping hostname-or-path to app port; make the hardcoded proxy ports configurable.
- **Blocked by:** none — the Monitor already knows every app, port, and health state
- **Enables:** one box serving the whole portfolio over HTTPS with sensible URLs instead of memorized ports.
- **Done when:** two default-generated apps serve simultaneously over TLS on one machine through the shared ingress, verified live.

### R9.8 · `externallyProvisioned` settable through supported authoring `S`
The refusal that protects a pre-existing team database from `db reset` exists — but is reachable only by hand-editing JSON. `npdev init --external-db` and a Manager checkbox write it properly. A data-deletion incident waiting, as analysts point apps at office DB servers.
- **Blocked by:** none
- **Enables:** safe adoption against existing databases; removes the scariest caveat from the Manager manual.
- **Done when:** on an app created with the flag, both the CLI reset and the Manager's Reset button refuse with the STOR-14 message, proven live against a hand-started server.

### R9.9 · Scheduled backups + retention + restore-verification `M`
Manual-only backup means no answer to "what if the disk dies" on a server running for months. A compose sidecar profile running the fixed backup on schedule, a retention cap pruning old dumps, and a scratch-restore verify mode proving a dump actually restores. *(Critique-restored: both proposals that fixed backup.sh stopped at manual.)*
- **Blocked by:** R9.1
- **Enables:** a real disaster-recovery posture for unattended team servers.
- **Done when:** the sidecar dumps nightly, prunes past retention, and the verify mode restores the latest dump into a scratch database green — all observable in the app's logs.

### R9.10 · Observability profile: scrape config, dashboard, down-alert `M`
`/actuator/prometheus` already exists, gated and wired — and nothing consumes it, so "the app went down at 2am" is discoverable only by a human opening the Monitor. One compose profile (Prometheus + provisioned Grafana dashboard) and one liveness alert rule delivered via the existing mail adapter. *(Critique-restored.)*
- **Blocked by:** none
- **Enables:** the unattended-hosting story every ops item claims to want, completed.
- **Done when:** the profile scrapes a running app, the provisioned dashboard shows its metrics, and killing the app fires the alert email in a test.

## R10 · Kill the Frozen Editor Bundle — 2S · 1L + one owner decision

**Goal:** the 450KB editor bundle has had no in-repo producer since 2026-08-17, silently drops 14 DSL sections on draft round-trips, and widens its gap with every DSL change the other roadmaps make. Close the one-way door, replace the viewer with an emitted schema-driven surface, then delete the frozen artifact.

**Small crew, or ride-along capacity.** R10.1 is the cheapest high-leverage move in the whole collection once the decision is made.

### R10.0 · The editor decision: demote, revive, or replace — *owner call, pull forward*
Not engineering — a recorded decision. The recommendation embedded in this collection: **demote to read-only and double down on CLI/MCP authoring** (R1), replacing the viewer with an emitted surface (R10.2). Reviving the parked React source means maintaining a hand-built editor against a DSL that every other roadmap is actively growing.
- **Blocked by:** none
- **Enables:** R10.1 onward.
- **Done when:** the decision is recorded as a ledger item with its guard.

### R10.1 · Delete the draft write-back endpoints; shipped editor becomes read-only `S`
Drafts saved through the in-app editor die inside the app — no path ever pulls one back into `model.json`, and round-trips drop 14 DSL sections. Remove the three draft endpoints (sync-status stays — it only reports drift). This closes the only one-way door in the authoring loop before the incoming team starts losing work through it. BREAKING.md entry in the same commit.
- **Blocked by:** R10.0
- **Enables:** no silent app-vs-source divergence; CLI/MCP becomes the only write path until the emitted surface exists.
- **Done when:** a regenerated app 404s the draft endpoints; BREAKING.md entry ships; the runtime-surface evidence check stays green.

### R10.2 · Emit a schema-driven model surface from the generator `L`
A mustache-emitted surface whose sections are **enumerated from the compiled model** rather than hardcoded — porting the viewer panes the bundle serves today, so a new DSL section appears in every generated app the day it lands, by construction.
- **Blocked by:** R10.1
- **Enables:** every DSL addition from R2/R4/R5/R6 (timeouts, invariants, webhooks, sequences, soft delete) is visible in every app immediately; the precondition for R10.3.
- **Done when:** a newly added DSL section appears in the emitted surface of a regenerated app with zero frozen-asset changes — browser-verified.

### R10.3 · Delete `static-react/` from the template tree `S`
Remove the frozen bundle, its size-watchlist entries, and the emitter hook that copies it. The generator's only unversioned frozen input is gone; no generated app ships a lossy editor again.
- **Blocked by:** R10.2
- **Enables:** the decay stops permanently.
- **Done when:** the directory is deleted, record-surfaces and determinism checks pass, a fresh app boots serving the emitted surface, and the full T2 gate run is green.

---

## Deliberately not scheduled

- **REG-163 — already DONE.** One strategist proposal scheduled it as an open architecture decision. The critique verified the ledger: closed 2026-08-15, VERIFIED_LIVE, the headline test already revived and passing. This is exactly the stale-blocker-citation failure the repo guide warns about — nothing to schedule. Only QUAL-5's four untried test-revival candidates remain in that cluster, as test hygiene, not a roadmap slot. REG-180 (the un-profiled-boot 404) is genuinely open and lives at wave-1 adjacency — a design item for whoever touches the runtime surface next; both quick fixes are proven-reverted traps.
- **Nightly scale ladder (SCALE-1 remainder) — maintenance track, not a feature slot.** Real and worth fixing (open the failed run's artifact first — the repo's own history says never trust a never-green ladder), but it's verification infrastructure with zero user-facing surface. It rides the maintenance cadence, per the standing features-over-gates default.
- **packs[].from corpus-coverage detector — ride-along, not a slot.** Gate wiring that ships in the same commit as R8.1's fragment fixture, per the standing "DSL feature → corpus coverage in the same commit" rule.
- **runtime-support rename (RUN-5) — WONTFIX stands.** Measured blast radius (19 files) worse than a cosmetic misnomer. Revisit only if the module is touched for unrelated reasons.

## How this was built

Eight analyst agents surveyed the repo (ledger, DSL, generated-app UX, orchestration, packs, authoring tooling, operations, verification), each returning facts with file-path evidence. Three strategists proposed full roadmap collections under different lenses (first external user · team multiplier · depth first). An adversarial critic spot-checked every claim against the repo — its verified corrections are embedded above where they changed an ordering, a blocker, or an item's existence; seven critique-restored items appear where all three strategists missed the same gap. Ledger ground truth at synthesis time: 264 items — 253 DONE, 8 PARTIAL (PACK-8, PACK-10, QUAL-5, PROC-1, BUILD-2, RUN-1, RUN-4, SCALE-1), 2 OPEN (PACK-9, REG-180), 1 WONTFIX (RUN-5). **All items closed by 2026-08-21 closeout session (Waves 1-4 complete, 340+ items ALL DONE).**
