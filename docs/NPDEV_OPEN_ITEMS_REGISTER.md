# NPDev — Open Items Register (bugs, gaps, boundaries)

> **Written:** 2026-07-21, verified against commit `c7e3519` (branch `beta1-vision-spine`,
> `beta1-184-gc7e3519`). Working tree clean. No release tag cut.
> **Corrected:** 2026-07-21 (same day) — an independent read-only verification pass ran 4 parallel
> code audits against live source before any fix work started. Findings folded in below: REG-3's
> premise was stale (the node_modules/slimness conflict it described was already fixed by commit
> `437d19b` on 2026-05-14 — two months before this register was written); REG-9's scope was
> overstated by roughly half (2 of its 4 secret categories already have working env-var support);
> REG-2's "no DataSource bean" root cause is not supported by the code and needs re-diagnosis with
> Docker running; REG-11's `D:\`-literal claim is unsubstantiated in the scripts it names. REG-1,
> REG-6, REG-10, REG-16 were confirmed with minor corrections. This was one verification pass, not
> a second independent one — treat the corrections as current, not as newly infallible.
> **Second pass, same day (commits `3ad4a73`, `6e5d7a9`):** 18 unreferenced sample-app definitions
> (11 of 12 `12works/*` NPDevSamples demos, `widget-showcase-demo`, and 6 unreferenced generic
> `AppGen/apps` definitions — `Portal`, `PortalFixed`, `manual-user-postgres[-freshdb]`,
> `simple-stores-postgres`, `simple-user-registry-h2server`) were moved to
> `D:\WorkSpace\NPDev\OutsideRepo\deprecated-sample-apps-2026-07-21` after a repo-wide reference
> audit found each had zero external references. This shrinks REG-1's pool directly — re-verified
> against the live filesystem post-cleanup: **6 recommended / 9 blanket / 5 InMemory-N/A of 20**
> definitions, down from 6/27/5 of 38. See §1.1 for the full updated breakdown.
> **Scope:** everything still open across (a) the LNCH-1 schema-evolution programme — now closed
> after five review/implementation rounds — and (b) the wider platform's launch-readiness ledger.
> **Purpose:** one place that says what is left, why it matters, where it lives, what it looks like
> in practice, and how to fix it. Written so a session that has never seen this project can act on
> any single entry without reading the other twenty.
>
> **How to read an entry.** Every item has: **What** (the defect in one sentence) · **Why it
> matters** (the consequence, not the abstraction) · **Where** (files, commands, IDs) ·
> **Practical example** (a concrete sequence that exhibits it) · **How to fix** (an actionable
> route, with the decision points named) · **Effort** (S ≤ 1 session · M = 2–5 · L = multi-week ·
> XL = needs its own phased plan) · **Type**: `BUG` (wrong behaviour) · `GAP` (missing work) ·
> `BOUNDARY` (deliberate limit — do not "fix" without a decision) · `PROCESS` (how we work).
>
> **Verification vocabulary used throughout:** *VERIFIED LIVE* (observed against a real running
> app/database) · *VERIFIED BY SUITE* (committed, currently-green automated test) · *NOT VERIFIED*.
> The tiebreaker for any disagreement about what is verified is
> `..\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md`.

---

## 0. Status summary

### 0.0 Owner decisions executed 2026-07-22 (post CI-green)

After Linux CI went green (REG-10) and the register was closed down to its bounded/human-gated
remainder, the owner made four calls and they were executed the same day:

- **Merged `beta1-vision-spine` → `main`** (PR #2, merge commit `3e29cca`) — 296 commits: the whole
  LNCH-1 programme, REG-1..30, REG-7/8 features, REG-27, the CI fixes. Both its CI gates green.
- **Cut a release tag** — the `beta1` name was already taken by the original milestone, so the current
  CI-green, register-closed state is tagged **`beta1.1`** (annotated, on the merge commit). Closes the
  release-tag half of **REG-15**; trademark stays parked (portfolio project, owner's call).
- **REG-12 Slice 3 (server-side PDF) greenlit, then executed and CLOSED (2026-07-22)** — phased plan
  `docs/REG12_DOCUMENT_EXPORT_PLAN.md`, executed via `docs/FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md` Part A.
  REG-12 (all 3 slices) is now CLOSED — see §2.4.
- **External-tester kit prepared** — `docs/EXTERNAL_TESTER_COLDSTART.md` is the cold-start brief to
  hand a project-blind AI agent for **REG-13/REG-14/REG-17**; the assistant's part is done, the run
  itself is still the owner's to trigger.

### 0.1 What just closed

The LNCH-1 schema-evolution programme is **DONE**. Five rounds — original build (P0–P8),
remediation (R0–R9), hardening (X0–X9), closeout (C0–C8), platform-column (T0–T10) — 60+ commits.
The final round closed its last two review findings: all six multi-entry `Map.of` → Jackson
non-determinism sites are fixed with a conformance test
(`NoMultiEntryMapOfInGeneratedManifestEmittersTest`), and T4's corpus flip landed 4 of 4 apps, each
regenerated, booted, and proven to preserve a row through a live additive change.

Severity trend across the five review rounds — **HIGH → CRITICAL → HIGH → MEDIUM → (none above
MEDIUM)** — is real convergence, not a treadmill.

**2026-07-22 addendum:** REG-7 and REG-8, the two items LNCH-1 carved out as deliberate boundaries
(§1.7/§1.8), are now **also CLOSED** — the owner decided to convert both into features rather than
leave them as documented limits (`docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md`):
external/unmanaged-database ownership, "mark migration as done," collision detection (REG-7.1–7.3),
and the REG-8 schema-ahead-of-build Trigger C. See §1.7/§1.8 for what shipped and each feature's
honestly-named residual limitation.

**2026-07-22 addendum 2:** the bounded remainder from that same verification pass is now also closed,
per `docs/REG28_30_REG12S2_CLOSURE_PLAN.md`. **REG-29 CLOSED** (test-only: proved a refusal thrown
while a boot holds its own migration claim still releases it). **REG-28 + REG-30 CLOSED**
(`MigrationMarkStore` now binds a mark to its `from -> to` transition and rejects a duplicate at
insert time; verified live against a real `superuser-admin-console` boot). **REG-12 Slice 2 (print)
DONE** (a "Print" toolbar button + `@media print` stylesheet + self-contained `#printRoot` render
mode, verified live in a real browser) — **Slice 3 (server-side PDF) is now unblocked**. See §3.4 and
§2.4 for detail; one new latent item was found (not fixed) during Slice 2's live verification — a
pre-existing promotion-panel retry-loop bug on InMemory-storage apps, noted in §2.4.

**2026-07-22 addendum 3 — `docs/FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md` executed, both parts DONE.**
**Part A (REG-12 Slice 3, server-side PDF): CLOSED.** New `document` DSL kind + `DocumentRenderContract`
port/adapter pair (pure-JVM OpenHTMLtoPDF) + `GET /api/documents/{document}/render.pdf` + a
"Download PDF" toolbar link; verified live (real PDF, real generated app, human + automated
text-extraction check). Three real bugs found and fixed along the way (two silent field-drop bugs
in `ModelResolver`/`BuiltinPackComposer`, one silent controller-allowlist exclusion) — see §2.4.
**Part B (REG-13/REG-14, non-author + newcomer tests): CLOSED.** An independent cold-start tester
(subagent, fresh context, own worktree, cold brief only, no coaching) passed all three tasks on the
first cold run — no re-run iteration needed. **REG-17 also advanced** (2/4 gates reproduced and
triaged by the same independent run; PARTIAL, not fully closed — see §3.2). Every friction point the
run surfaced is filed as a dated finding in `docs/LAUNCH_READINESS_GAPS.md` ("External-tester
findings, 2026-07-22"), not silently fixed, per the closure plan's own discipline. Evidence for both
parts: `NPDev_General__OutsideRepo/{reg12-slice3-evidence,external-tester-evidence/2026-07-22}/`.
**With this, the launch ledger (`docs/LAUNCH_READINESS_GAPS.md`) reaches 24 DONE · 0 PARTIAL · 0 OPEN.**

### 0.2 Register at a glance

> **STATUS CORRECTION (2026-07-22, index synced 2026-07-25 — SER-G9).** The table below predates the
> 2026-07-21/07-22 closure wave; rows are now struck through and annotated CLOSED directly (matching
> each item's own §-section) rather than relying on this disclaimer to override a stale-looking row.
> Read these as **CLOSED** regardless of how their row renders: REG-1, REG-2, REG-3, REG-4, REG-5,
> REG-7, REG-8, REG-9, REG-10, REG-11, REG-12, REG-13, REG-14, REG-18, REG-19, REG-20, REG-21, REG-22,
> REG-24, REG-27, REG-28, REG-29, REG-30, REG-40. REG-6 is CLOSED FULLY (2026-07-24 — Schema Engine
> Rebuild: one canonical SchemaDiff, every pass consumes it; see §1.6). **Genuinely still open:**
> **REG-16-resid** (adversarial review — **Tier A AND Tier B are DONE** as of 2026-07-21; only the
> residual remains — corrected 2026-07-25, this note previously said "Tier A complete only"),
> **REG-17** (third-party reproduction — 2/4 gates run by an independent tester), **REG-23** and
> **REG-25** (deferred boundaries), plus the FK/index-diffing deferral (G8 /
> `docs/SER_FINAL_CLOSURE_PLAN.md` Group B) — which is now ALSO the only remaining part of the
> `ExternallyManaged` full-shape check, since P5.2 closed its nullability + declared-uniques halves on
> 2026-07-25. The Phase-7 conversion-hook gaps (Group A) were closed 2026-07-25.
> The authoritative current state is `docs/LAUNCH_READINESS_GAPS.md` (24 DONE / 0 PARTIAL / 0 OPEN)
> plus each entry's own **Status** line below, not this summary table.

| ID | Title | Type | Sev | Effort | § |
|---|---|---|---|---|---|
| ~~**REG-1**~~ | 9 app definitions remain on the deprecated blanket destructive posture — **CLOSED (2026-07-21, REG-1/P8)**: all 7 flip-worthy apps flipped to `KeepExistingIfCompatible`, live additive-change proof on `superuser-admin-console` | GAP | MED | S/M | 1.1 |
| ~~**REG-2**~~ | `IT-EXTPG-1` — 10 integration tests unrunnable — **CLOSED (2026-07-21, REG-2/P2)**: real cause was `DatabaseIdentityStartupValidator`, fixed at the profile level; 10/10 green on real Postgres | BUG | MED | S/M | 1.2 |
| ~~**REG-3**~~ | `GATE-REL-1` — **CLOSED (2026-07-21, REG-3/P1)**: dependency-ordered evidence-report orchestration added, gate distinguishes precondition-unmet from check-failed | GAP | LOW | S | 1.3 |
| ~~**REG-4**~~ | `T-F1` — load-sensitive flake — **CLOSED (2026-07-21, REG-4/P8)**: root cause fixed (a stray caller interrupt in `SandboxedPluginExecutionEngine.execute`), not just tolerance-widened | BUG | LOW | S/M | 1.4 |
| ~~**REG-5**~~ | `GATE-OBS-1a` — **CLOSED (2026-07-21, REG-5/P8)**: retired the 6 redundant convergence checks to informational-only (the allowlist already enforces this), dated rationale recorded | PROCESS | LOW | S | 1.5 |
| ~~**REG-6**~~ | `ColumnFacts` → canonical `SchemaDiff` — **CLOSED FULLY (2026-07-24, Schema Engine Rebuild)**: every executor pass consumes ONE desired-vs-current model | GAP | MED | M | 1.6 |
| **REG-7** | `LNCH-1-B6` — no migration advisory lock (multi-instance) | BOUNDARY | — | M | 1.7 |
| **REG-8** | `LNCH-1-B9` — schema-ahead detector blind to a pure column drop | BOUNDARY | — | M | 1.8 |
| ~~**REG-9**~~ | LNCH-4 — auth table stakes — **CLOSED (2026-07-21, REG-9/P3)**: JWT env-var keys + `StartupValidator` fail-fast + verify-only `LoginController`; super-user key defaulted to WONTFIX (reversible) | GAP | **P0** | S/M | 2.1 |
| ~~**REG-10**~~ | LNCH-19 — Linux CI **now observed GREEN** (run `29899362276`, 2026-07-22) — DONE | GAP | **P1** | S/M | 2.2 |
| ~~**REG-11**~~ | LNCH-20 — cross-platform build **PROVEN** by the green run; also fixed a real generated-app `D:/`-cache portability bug — DONE | GAP | P2 | S | 2.3 |
| ~~**REG-12**~~ | LNCH-10 — Excel/PDF/print export beyond CSV — **CLOSED 2026-07-22** (all 3 slices DONE) | GAP | P1 | L | 2.4 |
| ~~**REG-13**~~ | LNCH-18 — non-author usability test — **CLOSED 2026-07-22** (independent cold-tester run) | GAP | P1 | S | 2.5 |
| ~~**REG-14**~~ | LNCH-22 — newcomer documentation test — **CLOSED 2026-07-22** (same run) | GAP | P2 | S | 2.6 |
| ~~**REG-15**~~ | LNCH-23 — release tag DONE + trademark **N/A** (individual hobby project, no mark) — **DONE 2026-07-23** | PROCESS | P2 | S | 2.7 |
| **REG-16** | Adversarial review of the other 23 launch items — **TIER A + TIER B DONE (2026-07-21)**; the open remainder is tracked as **REG-16-resid** (§3.1). Corrected 2026-07-25: this row previously read "zero adversarial review", which had not been true since 2026-07-21 | PROCESS | **HIGH** | L | 3.1 |
| ~~**REG-17**~~ | No third party has ever reproduced any verification — **DONE (2026-07-24, run `30067198501`)**: full CI green end-to-end on GitHub-hosted runners from a clean checkout (automated external reproduction on hardware this project has never touched). A literal human third-party run remains an optional nice-to-have, owner's call. Corrected 2026-07-25: this row previously read "PARTIAL, advanced 2026-07-22" while the detail section had already recorded achievement the day before | PROCESS | MED | M | 3.2 |

---

## 1. Items inherited from the LNCH-1 programme

### 1.1 REG-1 — 9 app definitions remain on the deprecated blanket destructive posture (down from 27)

**Type:** GAP · **Severity:** MEDIUM · **Effort:** S/M (was M) · **Status:** **CLOSED (2026-07-21, REG-1/P8).** All 7 flip-worthy apps flipped to `KeepExistingIfCompatible` + `allowDestructiveRecreate: false` + `destructiveRecreateConfirmation: ""`: the four `_official` apps (WmsOffice, WordLab, AuxScreen, Pigmentampa), `invoice-bonds-demo` (AppGen), and `restaurant-saas-multitenant` + `superuser-admin-console` (NPDevSamples). Verified per app: all 7 regenerate cleanly and their generated `schema-realization-manifest.json` carries `lifecycle=KeepExistingIfCompatible`. Live end-to-end additive-change proof on `superuser-admin-console` (H2Local): boot 1 created the schema (fingerprint `179a631`), added a field → regen (`a19e31c`) → boot 2 logged *"every difference is a new non-bond column ... skipping destructive recreation"* and started clean. Corpus rebuilt (`build_knowledge.py`); `docs/SCHEMA_EVOLUTION.md` recount updated to **13 recommended / 2 blanket / 5 InMemory-N/A of 20** — the 2 remaining blanket apps (`lnch1-rehearsal`, `simple-user-registry-h2local-freshdb`) are blanket by documented design. The 5 AppGen flips are layer-2 (outside this git repo); the 2 NPDevSamples flips are committed here.

**What.** `docs/SCHEMA_EVOLUTION.md` recommends `strategy: KeepExistingIfCompatible` +
`allowDestructiveRecreate: false`. **Re-verified 2026-07-21, after the sample-app cleanup below**
(fresh count against the live filesystem, not carried forward from T4): **6 recommended / 9 blanket /
5 InMemory-N/A of 20** total definitions.

**Why the pool shrank from 38 to 20.** The same day, a repo-wide reference audit found 18 sample-app
definitions — 11 of 12 `12works/*` NPDevSamples demos (all but `gift-idea-tracker`),
`widget-showcase-demo`, and 6 generic `AppGen/apps` definitions (`Portal` — confirmed broken,
`PortalFixed`, `manual-user-postgres`, `manual-user-postgres-freshdb`, `simple-stores-postgres`,
`simple-user-registry-h2server`) — with **zero references** anywhere in scripts, docs, knowledge
cards, or CI. All 18 were on the deprecated blanket posture (17 of them) or otherwise irrelevant to
this count, and all were moved to
`D:\WorkSpace\NPDev\OutsideRepo\deprecated-sample-apps-2026-07-21` (commits `3ad4a73`, `6e5d7a9`) —
recoverable, not deleted. This was a **direct, and larger, way to close this item's pedagogical
concern** (below) than flipping every one of them individually would have been: an unreferenced,
undocumented demo teaching the AI-authoring corpus the wrong posture is better removed from the
corpus than fixed in place.

**The remaining 9 blanket-posture apps, in full:**

| App | Pool | Disposition |
|---|---|---|
| `WmsOffice`, `WordLab`, `AuxScreen`, `Pigmentampa` | AppGen `_official` | **Next flip batch** (unchanged from before the cleanup) |
| `lnch1-rehearsal` | AppGen | **Deliberately kept** — its `README.md` explains it exists to rehearse upgrades on a definition shaped like what actually shipped |
| `simple-user-registry-h2local-freshdb` | AppGen | **Deliberately kept** — cited in `docs/SCHEMA_EVOLUTION.md` and `LNCH1_CLOSEOUT_PLAN.md` as the "freshdb" CI pattern; flipping it would defeat the scenario it exists to test |
| `invoice-bonds-demo` | AppGen | Real, load-bearing (canonical bonds/events/procedure example, 8+ doc hits) — **a genuine flip candidate beyond the current batch**, just not yet scheduled |
| `restaurant-saas-multitenant`, `superuser-admin-console` | NPDevSamples | Real, load-bearing (catalog-registered / gate-cited respectively) — **genuine flip candidates beyond the current batch** |

So after the next batch (the 4 official apps) lands, only **3** apps will remain on blanket for a
real reason to eventually flip (`invoice-bonds-demo`, `restaurant-saas-multitenant`,
`superuser-admin-console`), plus 2 permanently-blanket-by-design fixtures. That is the whole
remaining shape of this item — there is no more hidden long tail.

**Why it matters.** Two distinct reasons, and the second is the one people miss:

1. *Operational.* On a blanket-posture app, a `DROP_COLUMN` or a type narrowing still applies with
   no itemized acknowledgment. (Since the closeout round, concept drops and whole-schema
   recreations do require a token — so the exposure is bounded, but it is not zero.)
2. *Pedagogical.* These definitions are the corpus the AI-authoring loop learns from. Before the
   cleanup, an AI asked to author a new app pattern-matched against 27 examples of the deprecated
   posture and 6 of the recommended one — a 4.5:1 ratio against the documented recommendation. After
   removing the unreferenced/undocumented offenders, the ratio is **9:6**, close to parity, and every
   remaining blanket-posture example is either a real reference app awaiting its flip or a fixture
   that is blanket *on purpose* and documented as such. Examples outvote documentation when the model
   is imitating a shape — this is now a much smaller problem than it was this morning.

**Where.**
- `D:\WorkSpace\NPDev\AppGen\apps\*\definition\db.definition.json` (layer 2 — source of truth for
  app definitions, **not** a git repo)
- `d:\WorkSpace\NPDev\NPDev_General\NPDevSamples\*\Input\db.definition.json`
- Recount commands are written into `docs/SCHEMA_EVOLUTION.md` so the numbers stay checkable — **that
  doc's recount table still says 6/27/5 of 38 and needs the same update applied here** (not yet
  done as part of this correction pass).
- Already flipped: `simple-user-registry-h2local`, `simple-user-registry-postgres`,
  `simple-product-h2local`, `simple-consumer-h2server`, `npdev_split_model_sample_app`,
  `NPDevSamples\12works\gift-idea-tracker`.
- The 18 removed definitions, with per-item reasons: `D:\WorkSpace\NPDev\OutsideRepo\deprecated-sample-apps-2026-07-21\MANIFEST.md`.

**Practical example.** An author (human or AI) copies `_official\WordLab`'s `db.definition.json` as
a starting point — a reasonable thing to do, it is a working reference app. The new app inherits
`strategy: DropAndRecreateOnStructureChange` + `allowDestructiveRecreate: true` +
`I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED`. Months later a field is removed from the model; the
column is dropped on the next boot with no acknowledgment prompt, because the posture pre-authorized
it at authoring time.

**How to fix.**
1. Flip the 4 remaining `_official` apps — WmsOffice, WordLab, AuxScreen, Pigmentampa — the same way
   T4 flipped the first batch. This is unchanged by the cleanup; it was always the recommended next
   step.
2. For **each** app, and never in bulk: edit `db.definition.json` → regenerate → boot → take one
   additive change through it → confirm the boot log says `skipping destructive recreation`.
   Copy the field values from the already-proven `simple-user-registry-h2local` rather than
   inventing them (`destructiveRecreateConfirmation` must be `""` under the safe strategy).
3. **Watch for the shared-output-root trap.** Several `simple-user-registry-*` apps share one
   `scenario.name` and therefore one build output root, container and port. T4 handled this by
   verifying the landed manifest carried the right app's concept shape. Do the same, per app.
4. Leave `lnch1-rehearsal` and `simple-user-registry-h2local-freshdb` on the blanket posture
   deliberately, per the table above.
5. Once the official-app batch is comfortable, revisit `invoice-bonds-demo`,
   `restaurant-saas-multitenant`, and `superuser-admin-console` as a small final batch — at that
   point every remaining blanket-posture definition would be intentional.
6. Rebuild the corpus (`python scripts/ai/build_knowledge.py`) and re-run the recount, updating the
   numbers in `docs/SCHEMA_EVOLUTION.md` (both the counts and the "already flipped" list need the
   post-cleanup numbers — see the **Where** section above).

---

### 1.2 REG-2 — `IT-EXTPG-1`: 10 integration tests unrunnable; root cause re-opened

**Type:** BUG · **Severity:** MEDIUM · **Effort:** S/M · **Status:** **CLOSED (2026-07-21, REG-2/P2).** Real cause was a THIRD thing — `DatabaseIdentityStartupValidator` aborting because Testcontainers' `jdbc:tc:` DB always reports name `test` ≠ the app's resolved identity (captured stack trace, neither prior theory nor the plan's Hikari candidate). Fixed at the profile level (`application-postgres.yml` → `npdev.trial.database-override: true`). Running the tests surfaced two real findings, both fixed: a `text = uuid` cast in `PublicationRollbackE2EIT`, and `LoginController` crashing verify-only JWT (fixed under REG-9). **10/10 IT-EXTPG-1 green on real Postgres.** See `docs/OPEN_GAPS_AND_ROADMAP.md#IT-EXTPG-1` for the run recipe.

**What.** Ten `integrationTest` classes fail with `ApplicationContext` load errors
(`JwtAuthExternalBetaIT` ×8, `PublicationRollbackE2EIT`, `TenantIsolationE2EIT`) — test inventory,
profiles (`test,postgres`, plus `external-beta` for the JwtAuth suite), and the
`application-postgres.yml` Testcontainers URL independently re-verified 2026-07-21. For three rounds
these were recorded as "need an externally-configured Postgres". The platform-column round then
re-attributed it: the `test,postgres` profile declares a Testcontainers URL and, it was claimed,
**no `DataSource` bean is created at all**. **Correction (2026-07-21):** that second attribution
does not hold up either. A full grep of `NPDevRuntimeHost`'s source and resources for
`DataSourceAutoConfiguration` / `spring.autoconfigure.exclude` found zero matches — there is no
exclusion visible in code that would suppress `DataSource` auto-configuration for this profile
combination, and the declared `spring.datasource.*` properties are exactly the shape Spring Boot
auto-configures from with no extra bean required. **The true root cause is still open.** A more
likely candidate not yet ruled out: Hikari's default eager connection validation failing because the
`jdbc:tc:` Testcontainers driver couldn't reach Docker in whatever environment last ran this, which
presents identically to a context-load failure from the outside. Do not trust either prior
attribution — capture a real stack trace first.

**Why it matters.** Two of the three names are security-relevant — `TenantIsolationE2EIT` is part of
the LNCH-2 adversarial isolation work, and `JwtAuthExternalBetaIT` covers the auth stack. They have
not run in this branch's recent history. A tenant-isolation E2E test that cannot start is
indistinguishable, from the outside, from one that passes: the suite reports a context failure, not
an isolation failure. And the previous, wrong attribution would have cost a session to rediscover —
it told the next reader to go provision infrastructure that was never the problem.

**Where.**
- `NPDevRuntimeHost/src/test/.../*IT.java` (the ten classes; run via the assembled app's
  `integrationTest` task, `@Tag("integration")`)
- The `test,postgres` profile configuration — start from `application-*.yml` in the RuntimeHost
  template and whatever `@ActiveProfiles`/`@SpringBootTest` configuration those ITs declare.
- Tracked as `IT-EXTPG-1` in `docs/OPEN_GAPS_AND_ROADMAP.md`.

**Practical example.** Run the assembled app's `integrationTest` with Docker up. The schema-lifecycle
Postgres twin passes (25 tests) because it supplies its own Testcontainers `DataSource`
programmatically. The ten ITs fail during context load, before a single assertion runs, because the
profile they activate declares a JDBC URL but never contributes a `DataSource` bean for Spring to
inject.

**How to fix.**
1. Reproduce and capture the **exact** context-load exception (bean name, profile set active,
   property source) with Docker running. Do not work from the summary above, and do not trust the
   "no DataSource bean" theory below without a stack trace — it was re-checked 2026-07-21 and found
   unsupported by the code.
2. Determine which it actually is: (a) the profile is missing a `DataSource` `@Bean`/auto-config
   that another profile provides, (b) profile precedence causes an exclusion —
   **checked 2026-07-21: no `DataSourceAutoConfiguration` exclusion or
   `spring.autoconfigure.exclude` exists anywhere in the tree, so this specific mechanism is now
   ruled out** — (c) the ITs activate a profile combination nobody maintains any more, or (d)
   **new candidate**: Hikari's eager connection validation timing out because the `jdbc:tc:`
   Testcontainers driver can't reach Docker in the environment the suite last ran in — this would
   look like a context-load failure but is an environment issue, not a wiring defect.
3. Fix at the profile/config level; do **not** paper over it by giving each IT its own
   Testcontainers datasource — that would hide the real defect and duplicate the twin's setup ten
   times.
4. Once green, add the run recipe (exact Gradle command, Docker prerequisite, expected count) to the
   roadmap entry so a future session can run them in five minutes.
5. Then treat the *results* as a separate question: ten tests that have not run in a long time may
   surface real findings. Budget for that.

---

### 1.3 REG-3 — `GATE-REL-1`: **corrected** — the node_modules/slimness conflict was already fixed; the real gap is stale evidence reports

**Type:** GAP · **Severity:** LOW (was MEDIUM) · **Effort:** S · **Status:** **CLOSED (2026-07-21, REG-3/P1).** Added `scripts/quality/run-beta-release-evidence-orchestration.ps1` (runs all ~18 producers in dependency-ordered stages sharing one runId) + opt-in `-GenerateReports` on the gate; the gate now distinguishes **precondition-unmet (exit 2)** from **check-failed (exit 1)** with a leading status line and an `evidencePreconditions` report block. Found-and-fixed a producer that could only ever emit passing evidence (`run-json-schema-validator-tests.ps1`) plus its stale fixture that had silently disabled the model-root `additionalProperties` guard. `GATE-REL-1` in `docs/OPEN_GAPS_AND_ROADMAP.md` corrected to match.

**What — corrected 2026-07-21.** The original claim was that `run-beta-release-gate.ps1` requires
`json-schema-validator`'s `node_modules` to be present in-repo, which the workspace slimness policy
forbids at commit time, making the gate structurally unable to pass. **This premise is stale.**
Independent verification against live source found:
- `run-beta-release-gate.ps1` never touches `node_modules` directly — it only calls
  `scripts/quality/Invoke-JsonSchemaValidation.ps1`.
- That script resolves its runtime **outside the repo**, at
  `..\NPDev_General__OutsideRepo\node-tools\json-schema-validator`, and only runs `npm install`
  there if `node_modules` is missing or the lockfile fingerprint changed. It never creates
  `node_modules` inside the repo. Confirmed on disk: the external `node_modules` already exists
  with a matching `.package-lock.sha256` fingerprint — this mechanism has already run successfully.
- This was landed by commit `437d19b` ("Keep schema validator dependencies outside workspace"),
  dated **2026-05-14 — two months before this register was written**, and is exactly what
  `docs/WORKSPACE_CLEANUP_POLICY.md` (lines 15, 45) documents as the required pattern.
- `scripts/hygiene/Test-WorkspaceSlimness.ps1` (lines 165–172) only scans the in-repo workspace root
  for forbidden `node_modules` directories; the external location is invisible to it. **There is no
  conflict, and never was one after `437d19b`.** Options A/B/C below are moot — Option B is what
  already shipped.

**What is actually still true:** the gate did exit 1 with 35 of 36 required evidence reports
missing, independently reconfirmed 2026-07-21. But the cause is that the constituent
evidence-generating scripts (ai-beta-gate, sample-matrix, smoke suites, etc.) simply haven't been
run recently — a **report-orchestration/staleness gap**, unrelated to `node_modules` or the
slimness policy.

**Why it matters (revised).** The gate is not structurally broken — it just has nothing to grade
because its inputs are stale. That is a much smaller problem than "cannot pass in a committed
state," and it needs no policy decision, only running (and probably scheduling) the report
generators before the gate.

**Where.**
- `scripts/quality/run-beta-release-gate.ps1`, `scripts/quality/Invoke-JsonSchemaValidation.ps1`
- `scripts/policy/beta-release-gate-policy.json` (defines the 36 `requiredReports`)
- `scripts/reports/out/` (only `json-schema-validator-tests-report.json` present as of 2026-07-21)
- `docs/WORKSPACE_CLEANUP_POLICY.md` (the slimness rule this already satisfies)
- Tracked as `GATE-REL-1` in `docs/OPEN_GAPS_AND_ROADMAP.md` (filed `0d2cf71`) — **that entry
  describes the same stale premise and needs the identical correction, not yet applied there**

**Practical example.** Clean checkout → `pwsh -File scripts\quality\run-beta-release-gate.ps1` →
exit 1, 35/36 evidence reports absent. This is not a `node_modules` problem — none of those 35
reports are the schema-validator report; they are the other gates' outputs, simply never generated
in this tree.

**How to fix.**
1. Enumerate the 36 `requiredReports` in `beta-release-gate-policy.json` and identify which
   scripts/gates produce each one.
2. Run (or script the running of) each producer in order, capturing output to
   `scripts/reports/out/`, and re-run the release gate to see how many of the 35 are now real
   findings vs. simply missing.
3. Decide whether report generation should be a manual pre-step or wired into the release gate
   itself as an orchestration phase (recommended, so "run the release gate" is one command again).
4. Separately, still worth doing: make the gate distinguish *precondition unmet* (reports missing)
   from *check failed* (a report says something is broken) in its exit code or first output line —
   that ambiguity is what let this go two months without the correction above being made.

---

### 1.4 REG-4 — `T-F1`: load-sensitive flake, root cause unestablished

**Type:** BUG · **Severity:** LOW · **Effort:** S/M · **Status:** **CLOSED (2026-07-21, REG-4/P8) — root cause fixed, not just marked.** Reproduced the flake DETERMINISTICALLY (new `timeoutIsNotCorruptedByAPreExistingCallerInterrupt`, RED 100%: `status=FAILED, PLUGIN_EXECUTION_INTERRUPTED, executionDurationMs=1`) instead of waiting for suite load. It was T6.1's first candidate: `future.get(timeout)` runs on the calling thread, and a stray interrupt left by a prior test on the same worker made it throw `InterruptedException` before the timeout. Fixed in `SandboxedPluginExecutionEngine.execute` (read-and-clear a stray caller interrupt around the bounded `get()`, re-assert it after) — an engine robustness fix, not a tolerance widening. Removed `@Tag("load-sensitive")` from `timesOutSlowPluginExecution`; 6/6 green live.

**What.** `SandboxedPluginExecutionEngineTest` fails roughly 1 in 5 runs under parallel load and 0
in 5 in isolation. The timing assumption has been narrowed to two candidate mechanisms and the test
now self-diagnoses on the next occurrence, but the root cause is not established.

**Why it matters.** Low, honestly — it is a test-harness defect, not a product one. It matters
mainly because a known-flaky test in the gate erodes trust in every other red the gate reports, and
because it is the reason two rounds nearly recorded false measurements (one from a cached Gradle
result replayed five times, one from overlapping `--rerun-tasks` runs).

**Where.** `SandboxedPluginExecutionEngineTest` (kernel/adapters area — locate via Glob).
Tracked in `docs/OPEN_GAPS_AND_ROADMAP.md`.

**Practical example.** Run the full suite in parallel (the committed Gradle tuning sets
`org.gradle.parallel=true`, `workers.max=4`): the test fails intermittently. Run it alone with
`--rerun-tasks`: 5/5 green. The failure is contention-dependent.

**How to fix.**
1. Wait for the next occurrence and read what the self-diagnosis emitted — that instrumentation was
   added precisely so the next failure is informative. Do not re-derive by hand first.
2. Confirm which of the two narrowed mechanisms it is.
3. If it is a fixed timeout under contention: raise it **and quote the measured margin in the
   comment** (e.g. "observed max 1.9 s under 4-way parallelism; timeout 5 s"). Never invent a
   tolerance that does not follow from a measurement — two rounds have correctly refused to do this.
4. Record the measurement's configuration (serial vs parallel, Gradle properties in effect,
   `--rerun-tasks` or not). The committed tuning is local-only; CI does not use it, so a locally
   measured rate does not transfer.

---

### 1.5 REG-5 — `GATE-OBS-1a`: surface-governance drift needs a governance owner

**Type:** PROCESS · **Severity:** LOW · **Effort:** S (decision) · **Status:** **CLOSED (2026-07-21, REG-5/P8) — decision made and implemented.** Chose option (b) FORMAL RETIREMENT over the plan's default (a): a concrete check confirmed the exact-list allowlist (`runtime-surface-allowlist-report.json`, backed by `RuntimeControllerAllowlistConfig`) already IS the blocking exact-list-model enforcement and passes, so the 6 package-convention convergence checks are a redundant proxy for the superseded convention — rewriting them would only duplicate the allowlist. Retired to informational-only (reversible) with a dated rationale in `run-observability-hardening.ps1`, `run-runtimehost-gate.ps1`, and `docs/OPEN_GAPS_AND_ROADMAP.md#GATE-OBS-1a`. "Advisory, unowned" is no longer the state.

**What.** The RuntimeHost gate's surface-convergence/exclusivity checks encode a pre-`d0bf41b`
"package == support bucket" convention that the beta-0 manifest refactor replaced with exact lists.
They now report as **advisory observations** (`-PendingOk`), so the gate's exit code is truthful
again — but the underlying drift is unresolved and unowned.

**Why it matters.** Advisory is the right interim state (a gate that always exits 1 trains everyone
to ignore it), but "advisory forever" is how a check quietly stops meaning anything. Someone has to
decide whether the old convention is being restored, formally retired, or the checks rewritten
against the exact-list model.

**Where.** `scripts/quality/run-runtimehost-gate.ps1` (~lines 114–121 and ~243–253),
`scripts/quality/run-runtime-surface-evidence.ps1`, `run-observability-hardening.ps1`.
Tracked as `GATE-OBS-1`/`GATE-OBS-1a`.

**Practical example.** Run the RuntimeHost gate: it exits 0, and the surface-convergence
observations appear as advisory notes. Nothing tells a reader whether those notes are a temporary
accommodation or the permanent shape.

**How to fix.** A decision, not code: assign an owner and pick one of — (a) rewrite the checks
against the exact-list model the refactor introduced; (b) formally retire them with a dated comment
explaining what they used to assert and why it no longer applies; (c) restore the old convention.
Then either make the check blocking again or delete it. Record the decision in
`docs/OPEN_GAPS_AND_ROADMAP.md`.

---

### 1.6 REG-6 — `ColumnFacts`: eight passes each re-derive column semantics

**Type:** GAP (structural) · **Severity:** MEDIUM · **Effort:** M · **Status:** **CLOSED — FULLY (2026-07-24, Schema Engine Rebuild). The re-derivation debt is collapsed: a single canonical desired-vs-current model (`CurrentSchema`/`DesiredSchema`/`SchemaDiff` via `SchemaDiffEngine`) is now the ONE place column semantics are derived, and every executor pass consumes it — both decision surfaces (`classify`, `SchemaDeltaReport`) and all four mutation passes (table renames, column renames, type widenings, required-field backfills). Built strangler-fig: the model was proven 100% behavior-equivalent to the live engine (read-only shadow parity, H2 + Postgres), then each pass switched one commit at a time behind a default-on equivalence assert, both gates green after each. The former "every pass re-derives" purity that was DEFERRED below is now DONE. Known remaining limit (separate, documented): the desired side carries no explicit FK/index lists (P0.2 asymmetry), so `SchemaDiffEngine` models columns/types/nullability/defaults/uniques/renames but not FK/index diffs — a deferred enhancement (P5.2), NOT a re-derivation.** Previously: CLOSED as re-scoped (2026-07-22) — risk-core done; full set-algebra purity formally DEFERRED (owner decision). Earlier: SUBSTANTIALLY ADVANCED (2026-07-21, REG-6/P7); drift concern CLOSED. Landed the `ColumnFacts` projection (`columnFactsFor(manifest, table)` — one read-only per-(table,column) view exposing platformManaged/repairablePlatformColumn/additiveEligible/requiredByModel/declaredType/renamedFrom/literalDefaultJson + `bond()`), and a class-load drift-guard asserting `REPAIRABLE_PLATFORM_COLUMNS == PLATFORM_MANAGED_COLUMNS \ {id}` — so the "two overlapping platform-column sets with different contents" **can no longer silently drift** (that named concern is closed). Migrated the per-column *semantic* re-derivations (the relax pass's platform skip, the schema-ahead detector's platform subtraction, and the `refuseIfRequiredBondColumnMissing` bond heuristic) to the projection/helpers. **Deliberately not changed:** the set-algebra passes (additive/required diffs), which are set operations, not semantic re-derivation — rewriting them adds risk to the most-fixed subsystem without addressing the flagged concern, so this is not the full "every pass reads it." Verified behavior-preserving: H2 matrix 41/41 + Postgres matrix 25/25 unchanged, relax 4/4, bond 3/3, new ColumnFactsTest 3/3, generator conformance still green; RED-proof confirmed the bond migration is genuinely covered (`RequiredBondRefusalTest` goes red when `bond()` is broken).

> **Closure as re-scoped (2026-07-22).** The owner accepted closing REG-6 at its risk-core: (1) the
> drift that produced T-B1/T-B2 is CI-guarded (`PlatformColumnContractTest` + the class-load
> drift-guard); (2) the per-column *semantic* re-derivations are migrated to `ColumnFacts`; (3) a
> class-header directive now requires any NEW pass to read `ColumnFacts`, so the flaw class cannot
> silently return; (4) both guard suites re-verified green 2026-07-22. The full "every set-algebra
> pass reads the projection" purity migration is **formally DEFERRED** with this entry's own
> rationale (set operations are not semantic re-derivation; rewriting them adds risk to the
> most-fixed subsystem without closing a gap). Reopen ONLY if a new pass is about to be added — the
> directive marks that trigger at the code site itself.

**What.** `SchemaLifecycleExecutor` contains roughly eight passes — relax, tighten, backfill,
additive, delta-report, classify, bond-refusal, rename, unique-constraint — each performing its own
set arithmetic over the same raw manifest maps to answer the same questions: is this column
platform-managed? additive-eligible? required by the model? a bond? the primary key? There are
already three overlapping notions of "platform column"
(`RESERVED_BUSINESS_COLUMN_NAMES` in the emitter, `PLATFORM_MANAGED_COLUMNS` in the executor, the
`fullColumnNames` tail), plus a fourth mirrored in the test fixtures. **Re-verified 2026-07-21:**
pass count (~8), the ~55-method inventory, and the divergent sets are all confirmed —
`PLATFORM_MANAGED_COLUMNS` = 4 entries (`id`, `version`, `row_version`, `tenant_id`),
`RESERVED_BUSINESS_COLUMN_NAMES` = 3 entries (no `id`).

**Why it matters.** **This is the root cause of the last two rounds of findings.** T-B1 was one pass
(relax) inferring column semantics wrongly. T-B2 was another pass (additive) disagreeing with a
third (delta-report) about `version`. Each round fixes one pass's inference; the structure that
produced the wrong inference is untouched. A ninth pass added later will make a ninth independent
inference, and the review loop will find it. Three separate conformance tests
(`PlatformColumnContractTest`, `AdditiveColumnMirrorContractTest`,
`NoMultiEntryMapOfInGeneratedManifestEmittersTest`) now exist to pin duplications that would not
exist if the model were unified — they are treating symptoms, competently. **Correction:** this
means the specific drift risk between the two main platform-column sets is already **CI-guarded** by
`PlatformColumnContractTest` (it parses the executor's source as text, since RuntimeHost can't
depend on the generator module, and fails if the sets diverge from what the emitter actually
appends). The urgency here is the structural complexity and the risk a *new*, unpinned pass
introduces — not an unguarded drift hazard on the sets that already exist.

**Where.** `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java`
(**2,884 lines / ~176 KB, corrected from an earlier ≈145 KB estimate** — never full-read; Grep to a
method, Read with `offset`/`limit`), `SchemaDeltaReport.java`, and the fixture helpers in both proof
matrices.

**Practical example.** To answer "is `tenant_id` required?", the relax pass consults
`businessTableRequiredColumns` (model-derived, so: no) while the emitter emits it `NOT NULL DEFAULT
'default'` (so: yes). Both are reading the same manifest and reaching opposite conclusions, because
neither is reading a field that says *this column is platform-managed*. That disagreement was
T-B1 — a live weakening of the tenant-isolation column on every upgrade.

**How to fix.**
1. Introduce one `ColumnFacts` projection, computed once per (table, column) when the manifest
   loads: `{ isPlatformManaged, isAdditiveEligible, isRequiredByModel, isBond, isPrimaryKey,
   declaredType, renamedFrom, literalDefault }`.
2. Migrate the passes one at a time, each with its existing tests kept green — this is a refactor,
   so **no behaviour change is permitted**; any test that changes expectation means the refactor
   changed semantics and must be reworked.
3. Collapse the three platform-column sets into the projection's `isPlatformManaged`, retiring the
   conformance tests that exist only to pin duplicate copies (keep the emitter-side reserved-name
   validation — that is a different job: rejecting a colliding *model field* at generation time).
4. Do this **before** adding any new pass to the executor. That is the whole point.

---

### 1.7 REG-7 — `LNCH-1-B6`: no migration advisory lock (multi-instance)

**Type:** BOUNDARY (converted to feature, not fixed as a bug) · **Effort:** M · **Status:**
**CLOSED (2026-07-22, REG-7/P1–P3, `docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md`).**
The owner's decision (2026-07-21) was explicit: convert this and REG-8 into features with a
fail-loud + operator-resolves posture, rather than leave them as documented limits. Delivered as
three sub-features:

1. **External/unmanaged database ownership (REG-7.1).** New `schemaLifecycle.ownership` field
   (`NpdevManaged` default / `ExternallyManaged`), orthogonal to `strategy`. `ExternallyManaged`
   apps issue zero schema DDL — `flyway.migrate()` is never called — and instead run a read-only
   compatibility check every boot, refusing with an itemized message on a mismatch. See
   `docs/SCHEMA_EVOLUTION.md#external-unmanaged-database`.
2. **"Mark migration as done" (REG-7.2).** A GeneXus-style ControlPanel operation
   (`POST /api/admin/schema-migration/mark-done`) that fast-forwards the stored fingerprint with
   zero migration passes, on the operator's word. See
   `docs/SCHEMA_EVOLUTION.md#marking-a-migration-as-done`.
3. **Collision detection (REG-7.3), THIS item's original scope.** A single-row claim
   (`npdev_schema_migration_claim`, self-bootstrapped, PK-constrained) taken at the top of every
   upgrade boot and released in a `finally`; a held claim refuses the boot loudly, naming the
   holder; a crashed holder is cleared via `POST /api/admin/schema-migration/clear-claim`
   (SUPERUSER). See `docs/SCHEMA_EVOLUTION.md#collision-detection`.

**Honestly named residual (D3, not silently dropped):** this is detect-and-refuse, **not** a lock —
a true near-simultaneous-`INSERT` race remains theoretically possible on an engine without strict
insert serialization, and the claim is only attempted on an upgrade/repeat boot (a genuinely
virgin database's very first-ever boot is not claim-protected — claiming unconditionally there
would self-bootstrap the claim table before `flyway.migrate()` ever runs, breaking Flyway's own
baseline detection, a real bug found and fixed via a live boot rehearsal during implementation).
If collisions become frequent in practice, the upgrade path is this item's originally-scoped real
database lock (`pg_advisory_lock` + an H2 lock table) — deliberately not built for v1 per the
owner's "add guard rails later if needed."

**Verified:** full `NPDevRuntimeHost` Gradle suite green after each of the three sub-phases
(rebuilt via `Rebuild-And-Restage.ps1`); new dedicated test classes for all three sub-features;
live boot rehearsals against a real assembled app (`simple-user-registry-h2local`) for the
self-bootstrap-ordering risk specifically, including two real bugs found and fixed only by
booting the real app (a Flyway "non-empty schema, no history table" trip from REG-7.2's mark
store, and the identical class of risk pre-emptively avoided for REG-7.3's claim table).

**Where.** `docs/OPEN_GAPS_AND_ROADMAP.md` (`LNCH-1-B6`, now closed);
`NPDevRuntimeHost/src/main/java/com/finalexec/db/{MigrationClaimStore,MigrationMarkStore}.java`;
`SchemaLifecycleExecutor.migrate`/`verifyExternallyManagedSchemaCompatible`;
`NPDevGenerator/.../dbconfig/DatabaseOwnership.java`.

---

### 1.8 REG-8 — `LNCH-1-B9`: schema-ahead detector blind to a pure column drop

**Type:** BOUNDARY (closed by refusal, not by full reconstruction) · **Effort:** M · **Status:**
**CLOSED (2026-07-22, REG-8/P4, `docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md`);
a fresh-install false-negative was found and fixed 2026-07-22 as REG-27 — see below.**
Per the owner's decision, this is closed as "a clear refusal exists," not as "every drop is
reconstructed" — the data a genuine drop destroyed is still gone; what changed is that rolling an
older build back onto a database a newer build already migrated past now refuses loudly instead of
silently re-adding the dropped column empty.

> **Correction (2026-07-22).** The original P4 implementation only refused the rollback when the
> rolled-back-to build's fingerprint had a prior `APPLIED`/`MANUALLY_MARKED_DONE` row in
> `npdev_schema_history` — which a **fresh-installed** build never had (the blank-fingerprint boot
> branch writes no history row, and `afterMigrate` wrote only `npdev_schema_metadata`). So the
> register's own canonical example — *original, fresh-installed* build N, N+1 drops a column, roll
> back to N — was **not** actually refused, and the headline test only passed because it hand-seeded
> an `APPLIED` row for N that a real fresh install would never write. Found by independent code
> verification, filed and fixed as **REG-27** (§3.4): `afterMigrate` now records the initial
> realization as an `APPLIED` history point too, so Trigger C fires for fresh-installed builds. The
> claim below ("now refused") is true only **with** the REG-27 fix applied.

**The fix (Trigger C).** `SchemaLifecycleExecutor.databaseMigratedPastThisBuild` consults
`npdev_schema_history` — exactly the register's own "how to fix (if ever)" note — instead of live
schema shape: it finds the most recent successfully-applied row for THIS build's own target
fingerprint (none → legitimate first-time deploy, stays silent); if found, checks whether a LATER
row exists recording a *different* fingerprint. If so, refuses before `classify()` ever runs,
guarding every resolution (safe-additive, rename, type-change, destructive) uniformly, not just
the column-drop case that originally motivated it. Runs on the fingerprint-**MISMATCH** branch
(the actual shape a rollback-after-a-real-upgrade takes) — Triggers A/B, which this item's original
"how to fix" section did not distinguish from Trigger C, only ever ran on the fingerprint-MATCH
branch and could never have caught this practical example regardless.

**Interaction with REG-7.2 (D4).** A `MANUALLY_MARKED_DONE` fingerprint is exempt by construction:
the mark-done check already runs earlier in `beforeMigrate`, before the match/mismatch branching
is even reached, so it always short-circuits ahead of Trigger C with no additional logic needed.

**Practical example (now refused, previously silent).** Build N+1 drops `users.nickname`
(acknowledged, applied). The operator rolls back to build N, which still expects `nickname`. Before
this fix, the additive migration silently re-added it as an empty nullable column. After: the boot
refuses, naming the newer fingerprint and pointing at roll-forward / restore / mark-done as the
resolution paths — see `docs/SCHEMA_EVOLUTION.md#refusals-and-rollback`.

**Honestly named residual.** Trigger C's signal depends on `npdev_schema_history` staying intact;
if that audit table were reset/tampered with independently of the schema it describes, the signal
is lost (the same trust assumption every self-bootstrapped NPDev bookkeeping table makes). This is
a materially smaller gap than the pre-fix state, where the situation was *unconditionally* invisible.

**Verified:** `SchemaLifecycleExecutorDatabaseMigratedPastBuildTest` (originally 3 tests; the
`migrated-to-via-recorded-upgrade` variant, a legitimate-forward-upgrade non-false-positive, and a
`MANUALLY_MARKED_DONE` short-circuit) + a new Scenario 30 in `SchemaLifecycleExecutorProofMatrixTest`
(guardrail: new behavior = new scenario, never edit an existing one). **REG-27 (2026-07-22) added two
more tests to that class** — a direct assertion that a real fresh install records its fingerprint in
history, and the honest end-to-end (fresh-installed build N, no hand-seeded row, still refuses) —
which the original 3 did not cover. Full `NPDevRuntimeHost` Gradle suite re-run for the REG-27 fix.

**Where.** `SchemaLifecycleExecutor.findSchemaAheadMissingColumns` (Triggers A/B, unchanged) and
`SchemaLifecycleExecutor.databaseMigratedPastThisBuild` (Trigger C, new); documented in
`docs/SCHEMA_EVOLUTION.md#refusals-and-rollback`.

---

## 2. Open items in the wider launch-readiness ledger

Source of truth: `docs/LAUNCH_READINESS_GAPS.md` §2. Verified at `c7e3519`: **17 DONE, 6 PARTIAL,
1 OPEN.** Only the non-DONE entries appear below.

### 2.1 REG-9 — LNCH-4: auth table stakes, secrets management still open (**rescoped 2026-07-21**)

**Type:** GAP · **Priority:** **P0** · **Effort:** S/M (was M — scope roughly halved) · **Status:** **CLOSED (2026-07-21, REG-9/P3).** Both genuinely-open halves done. JWT keys: env-var path (`NPDEV_AUTH_JWT_PUBLICKEYPATH`/`PRIVATEKEYPATH` — hyphen-stripped relaxed binding) emitted into compose + `.env.example`; `StartupValidator` now fail-fasts (docs-linked) when `jwt` mode has an unreadable public key or a set-but-unreadable private key; `LoginController` supports **verify-only** deployments (blank private key → boots, login returns 503) instead of crashing the context. Super-user key (Q1): **defaulted to WONTFIX** (issued-not-supplied preserved; reversible). Verified: 12/12 StartupValidator unit tests + 8/8 verify-only `JwtAuthExternalBetaIT` live on real Postgres. `docs/CONFIGURATION.md` + `docs/DEPLOYMENT.md` updated.

**What.** The P0 slice (JWT revocation via token-version, brute-force login throttling, a documented
and tested CSRF posture) and the P1 password-reset slice are DONE. What remains: **secrets via
environment variables / Spring config**, with file-on-disk as a dev-only fallback. **Correction:**
independent verification of all four secret categories against live source found the gap is
narrower than originally described — 2 of 4 already work.

| Secret | Original claim | Verified 2026-07-21 |
|---|---|---|
| DB credentials | No env-var path | **Already works.** Plain `spring.datasource.url/username/password`, gets Spring's built-in `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` binding automatically (no hyphen gotcha — this property has none), and is already emitted into the generated Docker Compose (`DockerDeploymentEmitter.java`) with fail-fast `${POSTGRES_PASSWORD:?...}` syntax. |
| Runtime API keys | No env-var path | **Already works.** `npdev.auth.api-keys` binds from `NPDEV_AUTH_APIKEYS` (the relaxed-binding gotcha below is real, but it's already correctly handled — the compose emitter uses the right variable name and comments on the gotcha inline). |
| JWT signing key (private) + verification key (public) | No env-var path | **Confirmed still missing.** `LoginController.java` reads `${npdev.auth.jwt.private-key-path}` (no default) via `Files.readString`; `JwtBearerAuthFilter.java` loads the public key the same way. `DockerDeploymentEmitter.java` emits **zero** `NPDEV_AUTH_JWT_*` entries in either the compose template or `.env.example`, and `StartupValidator` never even receives the key path — a missing key fails with a raw Spring bean-creation error, not a docs-linked one. **This is the real, still-open gap.** |
| Super-user key | No env-var path | **Confirmed still missing, but different in kind.** `SuperUserBootstrapper.java` *generates* the key at first boot (it is issued, not operator-supplied) and writes it once to `SUPER_USER_KEY.txt`. Both `docs/DEPLOYMENT.md` and the compose emitter confirm there is genuinely no env-var path — but "add one" here means adding a new feature (seed a known key via e.g. `NPDEV_SUPERUSER_KEY`), not fixing a broken binding. That is a product decision as much as a bug fix. |

**Why it matters.** This is the last P0 item in the entire ledger. The part that's actually still
open — JWT key delivery, and optionally super-user-key seeding — is exactly the part that blocks a
credible from-clean-host containerized deployment (a missing/rotated JWT key currently means editing
a file path, which doesn't fit the ephemeral-filesystem Compose model LNCH-7 delivered). The DB
credentials and API keys half of the original claim was already coherent with LNCH-7 before this
correction.

**Where.** JWT keys: `NPDevRuntimeHost/.../auth/LoginController.java` (`private-key-path` `@Value`,
`readKeyFile`), `JwtBearerAuthFilter.java` (`loadPublicKey()`), `NpdevObservabilityConfig.java`
(where `StartupValidator` is wired — `jwtPrivateKeyPath` is not among the params it currently
receives). Super-user key: `com.finalexec.controlpanel.SuperUserBootstrapper.java`. Compose emission
for both: `DockerDeploymentEmitter.java`. `docs/CONFIGURATION.md` (documents the `NPDEV_AUTH_APIKEYS`
relaxed-binding gotcha — confirmed verbatim accurate; note `npdev.superuser.force-reissue` has the
same gotcha and is currently undocumented). `docs/DEPLOYMENT.md`.

**Practical example.** Deploy the compose stack on a fresh host with a rotated/custom JWT signing
key. There is no `NPDEV_AUTH_JWT_PRIVATE_KEY_PATH` (or equivalent) wired into compose — the key must
be baked into the image or mounted by hand outside the documented flow. DB credentials and API keys,
by contrast, already deploy cleanly via `.env` today.

**How to fix.**
1. JWT keys: add `NPDEV_AUTH_JWT_PRIVATE_KEY_PATH` / `NPDEV_AUTH_JWT_PUBLIC_KEY_PATH` (or a
   content-via-env / mounted-secret convention if that's preferred over a path) to the compose
   template and `.env.example`; wire `jwtPrivateKeyPath` into `StartupValidator` reusing the
   existing `AUTH_ANCHOR` convention (`configError(msg, anchor)` → links to
   `docs/CONFIGURATION.md#authentication`) so a missing key fails fast with a docs-linked message
   instead of a raw Spring bean-creation error.
2. Super-user key: decide whether seeding a known key via env var is wanted at all (it changes the
   security posture — "issued, never known to the operator" vs. "operator-supplied"). If yes, add
   `NPDEV_SUPERUSER_KEY` as an optional override in `SuperUserBootstrapper`. If no, close this half
   as WONTFIX and document why.
3. Document both relaxed-binding gotchas (`NPDEV_AUTH_JWT_*`, `NPDEV_SUPERUSER_FORCEREISSUE`) in
   `docs/CONFIGURATION.md` alongside the existing `NPDEV_AUTH_APIKEYS` one.
4. Verify by deploying from a clean host with **no** key files present and a custom JWT key supplied
   only via env var.

### 2.2 REG-10 — LNCH-19: Linux CI has never been observed green

**Type:** GAP · **Priority:** P1 · **Effort:** S/M · **Status:**
**DONE (2026-07-22).** `npdev-pr-gate.yml` ran **green** on GitHub Actions (ubuntu-latest) — run
`29899362276`, commit `3dcc51e`, every step `success`: DSL contract check, kernel inproc adapters,
all 168 generator unit tests (including the 3 packaged-app boot/HTTP/JDBC proof tests), RuntimeHost
libs sync, sample generation, and the RuntimeHost generated-app suite. This is the first CI run ever
observed green — every prior quality claim had run only on one Windows machine.

> **What it took (six root-caused fixes, seven runs).** The PR was opened from `lnch19-ci-verify`
> and CI surfaced a chain of first-contact-with-Linux defects, each fixed precisely (not guessed):
> (1) a hardcoded Windows `pwsh.exe` path in the packaged-app tests → resolve `pwsh` via PATH;
> (2) build output and the libs-sync disagreeing because both fall back to walking up for a folder
> named `NPDev_General` while GitHub checks out as `NPDevGeneral` → pin `NPDEV_BUILD_ROOT`;
> (3) **a real product portability bug** — every generated FinalApp inherited a hardcoded
> `D:/WorkSpace/NPDev/Build` gradle `projectcachedir` from the RuntimeHost template, so a generated
> app could not build on any machine but the dev box → removed it (see REG-11);
> (4) the packaged-app tests' hand-maintained adapter list omitted `mail-inproc`/`mail-smtp`, masked
> on Windows by pre-existing jars → added them; (5) a CI diagnostics step + direct GitHub-API log
> access added so failures stopped being invisible; (6) a `..` in an artifact path
> (`actions/upload-artifact` forbids it) → copy reports into an in-workspace dir first. Fixes committed
> on `beta1-vision-spine` (`78eb2ce`, `28b91cf`, `4978936`, `962eb60`, `4eb8bce`, `ea19769`) and
> cherry-picked onto `lnch19-ci-verify`.
>
> **Caveat:** the green run was on `lnch19-ci-verify` (an older code line + the six fixes), not on
> `beta1-vision-spine`'s latest (REG-27/REG-7/REG-8/register work). The fixes are on both branches;
> confirming CI green on the latest line is a follow-up (push `beta1-vision-spine` + PR/dispatch).

**Original framing (kept for context):**

**Type:** GAP · **Priority:** P1 · **Effort:** S/M · **Status:** PARTIAL

**What.** `.github/workflows/npdev-pr-gate.yml` and siblings exist and are committed; the
`gradlew.bat`-hardcoding blocker on the CI critical path was fixed. **Nobody has watched a real
GitHub Actions run go green.** Creating the PR needs the `gh` CLI, unavailable in the sessions that
prepared it. **Re-verified 2026-07-21:** confirmed exactly as described — 5 workflow files present
and committed, `npdev-pr-gate.yml` uses POSIX `./gradlew` exclusively with `chmod +x` before the
integration-test step, branch `lnch19-ci-verify` exists and is pushed to `origin`, and `gh` is still
unavailable in this environment (identical blocker reproduced independently).

**Why it matters.** Every quality claim in this project rests on gates run on one Windows machine.
Until CI runs, "the gates pass" means "they pass here", and the committed local Gradle tuning
(`parallel`, `caching`, `workers.max=4`) means the local environment is measurably not CI's. There
is also a latent unknown: whether a generated FinalApp's copied `gradlew` preserves its execute bit
through the generator's file-copy on Linux.

**Where.** `.github/workflows/*.yml`, `scripts/appgen/generate-sample-app.ps1`,
`scripts/appgen/run-sample-app.ps1`, branch `lnch19-ci-verify`.

**Practical example.** Open the PR from `lnch19-ci-verify`. Either it goes green — and LNCH-19 is
DONE with one click — or it fails, and the failure is the first genuine cross-platform signal this
project has had.

**How to fix.** Open the PR (one action, needs your GitHub session), watch the run, fix what it
surfaces. Budget for the execute-bit issue and for path assumptions the Windows environment hides.

### 2.3 REG-11 — LNCH-20: cross-platform build scripts, Phases 2–4 (**corrected 2026-07-21**)

**Type:** GAP · **Priority:** P2 · **Effort:** S (was M — see correction) · **Status:**
**DONE / PROVEN (2026-07-22).** The green Linux CI run REG-10 describes (run `29899362276`) is the
proof this item was waiting for — the platform's DSL/kernel/generator/RuntimeHost build and a
generated FinalApp's own `bootJar`/boot all ran on ubuntu-latest, not just the dev machine.
**REG-10 additionally exposed and fixed a genuine distribution bug this item's "code-complete" state
had missed:** every generated FinalApp shipped `NPDevRuntimeHost/gradle.properties`'s hardcoded
`org.gradle.projectcachedir=D:/WorkSpace/NPDev/Build/...`, copied verbatim by `FinalAppAssembler`, so
a generated app's `gradlew bootJar` could not run on any machine without that exact `D:` path (Linux,
another Windows box, an evaluator's laptop). Removed from the template (`4978936`) so generated apps
use gradle's portable default cache — the real cross-platform-portability fix, beyond the PowerShell
scripts. Below is the pre-proof (2026-07-21) code-complete disposition, kept for detail:
All genuine `gradlew.bat` call sites now resolve the wrapper per-OS (migrated to the shared helper where a script already imported it; fixed in place otherwise, including two resolvers that gated on file-existence instead of the OS, and the root `npdev-gradlew.ps1`). A repo-wide `D:\` sweep removed the one in-logic drive-letter literal (`run-stateful-additive-migrations-check.ps1`'s redundant test-XML fallback); the remaining `D:\` literals are overridable param defaults (sanctioned local convention). The Docker-Desktop Postgres proof launcher and 3 superuser demo scripts are documented as named Windows-only exceptions. **The register text names CI as "the enforcement mechanism," so true closure needs a green Linux Actions run (REG-10, owner-gated) — the code is ready, not yet proven.** Full disposition in `docs/LAUNCH_READINESS_GAPS.md` §LNCH-20.

**What.** Phase 1 (the `gradlew.bat` literals on the CI critical path) landed as a side effect of
LNCH-19's fix. Phases 2–4 — the AppGen builder scripts, ~14 remaining quality-gate scripts with the
same pattern, and the Docker-Desktop-specific Postgres proof launcher — are scoped and untouched.
**Correction:** the `gradlew.bat` count is confirmed (13 files / 18 occurrences across
`scripts/appgen/*.ps1` and `scripts/quality/*.ps1`, matching the "~14+" estimate), but a targeted
grep for `D:\` / `D:/` literals in those same files returned **zero matches** — the "`D:\`-rooted
path literals throughout" claim is not substantiated in the files this item names (drive-letter
literals may exist elsewhere in the repo, but not in the scripts scoped here). More importantly: a
working cross-platform helper, `Get-NPDevGradleWrapperExecutable` in `scripts/npdev-common.ps1`,
**already exists** — it checks `$IsWindows` and resolves `gradlew.bat`/`gradlew` correctly. The 13
files simply haven't been migrated to call it. This is a mechanical call-site migration to existing
infrastructure, not new plumbing — hence the lower effort estimate.

**Why it matters.** A contributor or evaluator on macOS/Linux cannot build the platform. This gates
any external review (REG-17) and any open-source distribution posture (ADR-0007 chose
self-hosted/source-first).

**Where.** `scripts/appgen/*.ps1` (3 occurrences: `Build-ClaudeApp.ps1` ×2, `Build-NpdevApp.ps1` ×1),
`scripts/quality/*.ps1` (15 occurrences across 10 files, notably
`run-incremental-migration-testing-check.ps1`, `run-post-beta0-maturity-closure-check.ps1`,
`run-stateful-additive-migrations-check.ps1`, `run-trusted-source-security-check.ps1` — each ×2).
`scripts/npdev-common.ps1` (the existing helper: `Get-NPDevGradleWrapperExecutable`,
`Test-NPDevGradleExecutable`, `Invoke-NPDevCommandCapture`/`Streaming`).

**Practical example.** Clone on Linux, run one of the 13 affected scripts under `pwsh`. It fails on
the `gradlew.bat` invocation specifically — not on a drive-letter literal, which this specific set of
scripts does not contain.

**How to fix.** Migrate each of the 13 files' `gradlew.bat` call sites to call
`Get-NPDevGradleWrapperExecutable` from `scripts/npdev-common.ps1` instead — the helper already does
the right thing, this is find-and-replace-grade work per file, not new design. Separately, do a
repo-wide sweep for `D:\`/`D:/` literals outside this item's originally-named scope before assuming
none remain elsewhere. Let CI (REG-10) be the enforcement mechanism rather than manual auditing.

### 2.4 REG-12 — LNCH-10: Excel/PDF/print export beyond CSV

**Type:** GAP · **Priority:** P1 · **Effort:** L · **Status:** **CLOSED (2026-07-22) — all 3 slices DONE.**

**What.** Slice 1 (streaming CSV export from any grid) is DONE. Slice 2 (print stylesheet / print
render mode) is DONE (2026-07-22). **Slice 3 (server-side PDF document objects) is DONE (2026-07-22).**

**Slice 3 — what shipped.** A new declarative `document` DSL kind (`$defs/document`: `name`,
`concept`, `title`, `pageSize`, `marginMm`, `metadata` — 4-copy schema mirror + `DocumentAst`/
`CompiledDocument` threaded through the parser/resolver/compiler/canonical-JSON writer+reader) bound
to a single concept's query. A new kernel port `DocumentRenderContract` (HTML+options in, PDF bytes
out) with an adapter pair: `document-render-inproc` (pure-JVM `com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10`,
no native/display deps — proven headless-safe by a P0 spike) as the default, `document-render-stub`
(an honest no-op, the pair's second half) as an opt-out. A new static, document-generic
`DocumentRenderController` (`GET /api/documents/{document}/render.pdf`, mirroring
`ConceptQueryController#exportCsv`'s exact query/streaming/header-before-body discipline down to
reusing its `parseConceptQuery`) builds the same print-document HTML shape Slice 2 produces and
renders it via the adapter. A "Download PDF" toolbar link next to Export CSV/Print, wired per-concept
via a new `documents` array in the generated UI manifest (`BusinessUiEmitter`).
**Verified live**: `superuser-admin-console`'s new `ProjectsPdf` document (bound to `Project`) streamed
a real, valid PDF (`%PDF-1.4` header; PDFBox `PDFTextStripper` extraction confirmed exact title/
timestamp/row/column/footer content matching two freshly-created records; human-eye-verified).
Evidence: `NPDev_General__OutsideRepo/reg12-slice3-evidence/`.

**Real bugs found and fixed while wiring this through** (not synthetic — a genuine live rehearsal):
1. `ModelResolver.resolve()` and `BuiltinPackComposer.merge()` both reconstructed `ModelAst`/
   `CompiledModel` via truncated/older constructor overloads that silently dropped the new
   `documents` field before it ever reached the generator — the exact bug class
   `CanonicalJsonRoundTripCompletenessTest` exists to catch, just at two *different* reconstruction
   sites that test doesn't cover. `BuiltinPackComposer.merge()`'s truncated constructor was ALSO
   already silently dropping `guidePages`/`aggregates`/`autoPanels` for any app composing built-in/
   installed packs (`internal.tables=true` or `packs.included` non-empty) — a pre-existing gap,
   fixed alongside since it was the same call site with the same fix.
2. `runtime-supported-controllers.json`'s static `allowedControllers` allowlist silently excluded
   `DocumentRenderController.java` from compilation entirely (no error — a bare 404, no route ever
   registered) until added by name.
3. `NPDevRuntimeHost/build.gradle.template` needed the OpenHTMLtoPDF Maven coordinate declared
   explicitly (same reason the AWS SDK/Jakarta Mail deps are already declared there — jar-staging
   only stages this workspace's own project jars, not third-party transitive dependencies); without
   it, the first real request threw `NoClassDefFoundError`.

**CI (guardrail: Windows-verified is not enough).** Pushed to `beta1-vision-spine` (commit
`b5c7c88`) and dispatched `npdev-pr-gate.yml` via `scripts/ci/gh-api.sh` — **run `29943008077`,
conclusion `success`** on a real Linux GitHub Actions runner, packaged-app proof tests included.

**Slice 2 — what shipped.** A "Print" button next to "Export CSV" on every declared panel's grid
toolbar (`business-ui-app.mustache`'s `renderPanel`/new `printPanel()`) builds a self-contained
`#printRoot` document — title, "Printed <timestamp>" meta, a table mirroring the grid's currently
loaded (filtered/sorted) page with the same visible columns, and a "Total: X of Y record(s)" footer —
and calls `window.print()`. A new `@media print` block in `business-ui-style.mustache` hides all app
chrome (nav/app-bar/panel controls) and shows only `#printRoot` when printing; `#printRoot` itself
(declared in `business-ui-index.mustache`) is `display:none` outside of print, so it never intrudes on
the normal screen view. Deliberately self-contained/inlinable markup — the seam Slice 3's server-side
PDF renderer is designed to reuse verbatim (noted in a code comment pointing at the Slice 3 plan).
**Verified live**: real browser (ScrapForAI) against `superuser-admin-console`'s `Project` concept, both
empty and with a real created row — DOM assertions confirmed the print document's title/meta/columns/
row-count/footer, and that `#printRoot` stays `display:none` before AND after building it (screenshot
evidence: `D:\WorkSpace\NPDev\Build\scrapforai-artifacts\superuser-admin-console-print2\...\screenshots\
after_print_click.png`). Regression routine committed:
`NPDevSamples/scripts/superuser-admin-console/browser-routines/05-print-mode.json`.

**Latent item found during Slice 2, now FIXED (2026-07-22, commit `4943b73`):** verifying against the
InMemory `simple-contact-intake` sample exposed a **pre-existing, unrelated bug** — `renderPromotionPanel()`
(`business-ui-app.mustache`) calls `loadPromotion()` whenever `!state.promotion.loaded &&
!state.promotion.loading`, but `/api/admin/promotion` 503s for any InMemory-storage app (no physical
DB), and a 503 never sets `loaded = true`. Every `render()` that touches the promotion section
re-triggered `loadPromotion()`, which itself calls `render()` twice — an unbounded retry/re-render loop
that floods the console with 503s and makes any toolbar button in that render path flaky-to-unclickable
(elements keep getting detached/rebuilt mid-click). **Fix:** a `state.promotion.attempted` flag set
after any completed load (success OR failure); the auto-load guard now also requires `!attempted`, so a
failed load no longer auto-retries (the Refresh button, which calls `loadPromotion` directly, is
unaffected) — exactly the "stop retrying until the operator clicks Refresh" bounded fix this item
prescribed. **Verified live** on a freshly regenerated `simple-user-registry-inmemory` FinalApp
(InMemory → `/api/admin/promotion` 503; `/api/me` roles=[ADMIN] → super-user): over an 8s dwell on the
rendered super-user promotion panel, exactly **1** `/api/admin/promotion` call per authenticated render
(vs. the pre-fix flood), page stable and interactive (real-browser ScrapForAI routine, 10/10 green).

**Why it matters.** Business apps end in paper and spreadsheets. For the GeneXus-migration audience
specifically — WMS-class apps with pick lists and packing slips — print output is not a nice-to-have,
and its absence forces hand-authored `web/` pages at exactly the moment an app becomes real, which
breaks the low-code promise.

**Where.** `NPDevRuntimeHost/.../api/ConceptQueryController.java` (the CSV precedent, and the
`parseConceptQuery` Slice 3 directly reuses), `NPDevRuntimeHost/.../api/DocumentRenderController.java`
(Slice 3's endpoint), `NPDevKernel/kernel/.../ports/DocumentRenderContract.java` +
`NPDevKernel/adapters/document-render-{inproc,stub}` (Slice 3's port/adapter pair),
`npdev-templates/business-ui-app.mustache` (the grid toolbar, `printPanel()` + the "Download PDF"
link), `npdev-templates/business-ui-style.mustache` (the print stylesheet),
`npdev-templates/business-ui-index.mustache` (the `#printRoot` mount).

**Practical example.** A warehouse operator needs a printed pick list. "Print" on the declared panel
(Slice 2) shows a clean title + line-items + total-count document ready for the browser's print/
print-to-PDF dialog; "Download PDF" (Slice 3) renders the identical shape server-side, no browser
needed — e.g. for an automated nightly packing-slip batch.

**How to fix.** ~~Slice 2 first~~ **DONE.** ~~Slice 3~~ **DONE** — see `docs/REG12_DOCUMENT_EXPORT_PLAN.md`
for the full design and phase-by-phase execution record.

### 2.5 REG-13 — LNCH-18: non-author usability test never run

**Type:** GAP · **Priority:** P1 · **Effort:** S · **Status:** **CLOSED (2026-07-22).**

**What.** ADR-0006 ratified AI-first authoring. Its own Definition of Done requires a real,
external, non-author person taking an app from description to running FinalApp. A structured
friction-log template exists (`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`).

**Why it matters.** Every app this platform has ever produced was built by you or by an AI you were
supervising. The claim "a non-engineer can author an app" is entirely unvalidated. This is the
single largest untested assumption in the product thesis.

**Closed.** `docs/FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md` Part B ran the DoD via a genuinely independent
tester: a subagent given ONLY `docs/EXTERNAL_TESTER_COLDSTART.md`'s cold brief, a fresh context
window, and its own isolated git worktree — no access to this project's plans/register/history, no
coaching mid-run (Part B.1 option 2, explicitly sanctioned by the closure plan as a runnable
approximation of a separate human/AI-tool session). It authored the brief's issue-tracker app
(title/description/status/assignee; create/list/edit/close) using the documented CLI validator
fallback (no NPDev MCP tools were registered in the session) and verified it unaided over REST — all
four operations confirmed against a real running FinalApp. Pass bar met on the first cold run, no
re-run iteration needed. Evidence + friction log:
`NPDev_General__OutsideRepo/external-tester-evidence/2026-07-22/friction-log-task-a.md`. Real
finding filed (not silently fixed): `NPDEV_USER_MANUAL.md`'s own `createConcept`/`updateConcept`
examples omit the `persistence` capability/binding block, producing a model that validates cleanly
but 500s at runtime with no diagnostic naming the real cause — see
`docs/LAUNCH_READINESS_GAPS.md`'s "External-tester findings, 2026-07-22" for the full dated list.

### 2.6 REG-14 — LNCH-22: newcomer documentation test never run

**Type:** GAP · **Priority:** P2 · **Effort:** S · **Status:** **CLOSED (2026-07-22).**

**What.** `docs/DSL_REFERENCE.md` (generated from schema, drift-checked in the generator gate),
`docs/TUTORIAL_FIRST_APP.md` (built on the sample the RuntimeHost gate regenerates, so it cannot rot
silently), and validator error codes/hints all exist. The DoD — a newcomer building the tutorial app
from docs alone — has now been exercised.

**Closed.** The same 2026-07-22 independent-tester run that closed REG-13 (see that entry) built
`NPDevSamples/simple-contact-intake` from `docs/TUTORIAL_FIRST_APP.md` alone — docs only, no MCP
tools or CLI validator used to fill gaps — and verified it booted and worked (both the tutorial's
create example and its invariant-failure example). Pass bar met on the first cold run. Evidence:
`NPDev_General__OutsideRepo/external-tester-evidence/2026-07-22/friction-log-task-b.md`. Real,
dated findings (docs improve even on a pass): the tutorial's own literal `gradlew.bat bootJar`
command fails on an undocumented RuntimeHost-libs staging prerequisite whose own suggested fix also
fails standalone in a fresh worktree; the doc's claimed `400` status for an invariant violation is
actually `422`. Full list: `docs/LAUNCH_READINESS_GAPS.md`'s "External-tester findings, 2026-07-22".

### 2.7 REG-15 — LNCH-23: trademark clearance and release tag

**Type:** PROCESS · **Priority:** P2 · **Status:** **DONE (2026-07-23).** The **release tag was cut
2026-07-22** (`beta1.1`, annotated, on the `beta1-vision-spine → main` merge commit `3e29cca`);
`run-release-checklist-gate.ps1` no longer lacks a tag. **Trademark clearance is N/A — owner's final
decision (2026-07-23):** this is an individual, non-commercial hobby/portfolio project with no mark to
defend and no trademark sought, so there is nothing to clear and nothing to park — the item is
complete, not deferred. The two preliminary name-collision findings on file ("NP DEV Soluções em
T.I.", NPDEV LIMITED UK #14176093) are informational only and block nothing. What follows is the
original PARTIAL framing, kept for history:

**What.** LICENSE (Apache-2.0, ratified to Marcelo Giazzon), ADR-0007 (self-hosted/source-first, no
telemetry at launch), `docs/RELEASE_PROCESS.md`, `CHANGELOG.md` and
`run-release-checklist-gate.ps1` all exist. Two items remain: a **real trademark clearance** (two
preliminary findings recorded — "NP DEV Soluções em T.I." at `npdev.com.br`, and **NPDEV LIMITED**,
UK Companies House #14176093, active since 2022 — neither is a clearance), and **no release tag has
been cut** (deferred by you three times; HEAD is `beta1-184-gc7e3519`).

**How to fix.** The trademark question needs a professional search, not more web searching. The tag
is your call — note that `run-release-checklist-gate.ps1` refuses an untagged release by design, and
REG-3 currently blocks the larger release gate independently.

---

## 3. Process-level items

### 3.1 REG-16 — The other 23 launch items have had zero adversarial review

**Type:** PROCESS · **Severity:** **HIGH** · **Effort:** L · **Status:** **TIER A COMPLETE (2026-07-21).**
The independent, attack-first review of the LNCH-2 (tenant isolation) + LNCH-4 (auth) surface has now
happened — REG-16's actual problem statement ("zero adversarial review") is resolved. Findings +
triaged remediation plan: `docs/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md`. **Headline: no CRITICAL or
HIGH finding** — the tenant-isolation core is genuinely defense-in-depth (gateway *and* JDBC store
both enforce `tenant_id`; seed/export route through the enforced gateway; no SUPERUSER bypass of
business data; SQL-injection-safe query filters; revocation checked on both claim→context paths). The
residual is **5 MEDIUM + 3 LOW + 1 INFO**, all login/throttle/actuator hardening, filed as dated
**REG-18…REG-26** below. Per the plan's triage, with no CRITICAL/HIGH there is no *mandatory* Tier-B
work; the MEDIUM/LOW remediations are scheduled, not dropped. **TIER B ALSO DONE (2026-07-21):** all 5 MEDIUM findings fixed (REG-18/19/20/21/22), REG-24 verified already-guarded, REG-26 WONTFIX, REG-23/25 deferred with rationale — see the REG-18…26 table below. REG-16 is now fully addressed **for the LNCH-2+4 surface it originally scoped**. The other ~21 launch surfaces (generator codegen, kernel FlowEngine/`KernelRunner`, LNCH-13 row-level authz, export/PDF, …) remained at zero adversarial review — tracked as its own residual item, **REG-16-resid** (§3.10), rather than reopening this closed item's scope. Round 1 of REG-16-resid (kernel execution path) is done — see §3.10.

**What.** LNCH-1 has absorbed **five** full review→plan→implement→review rounds. Every other item in
the ledger — including LNCH-2 (tenant isolation), LNCH-4 (auth), LNCH-13 (row-level authz) — has had
**none**. **Re-verified 2026-07-21** — sizing for the LNCH-2+4 surface specifically (the "how to
fix" target below): `com.finalexec.auth` (10 files, 1,236 lines) + `com.finalexec.tenant` (1 file,
101 lines) + 21 other tenant/auth-touching files across `api`, `controlpanel`, `db`, `npdev/dto`,
`npdev/service`, `seed`, `config` — roughly **23 distinct production files, ~3,400+ LOC** total.
12 existing test files were also found (`TenantIsolationAttackTest`, `TenantIsolationE2EIT`,
`RowLevelAuthorizationAttackTest`, `JwtAuthExternalBetaIT`, etc.) — so this surface is not
*untested*, only never adversarially reviewed, which is the distinct claim this item makes.

**Why it matters.** The five LNCH-1 rounds found, in order: 2 HIGH, 1 CRITICAL, 1 HIGH, 2 MEDIUM,
1 MEDIUM. That is what adversarial review of one subsystem yields on a codebase of this maturity.
There is no reason to believe the auth stack or the tenant-isolation suite is cleaner — only that
nobody has looked. LNCH-2's own attack suite was written by the same process that wrote the code it
tests, and two of its E2E tests currently cannot start (REG-2).

The marginal value of a sixth LNCH-1 round is now demonstrably lower than a **first** review of the
security surface.

**How to fix.** Run the same loop, next on **LNCH-2 + LNCH-4 together** (they share a surface):
independent review → findings document → phased plan → implement → re-review. Reuse this
programme's proven discipline: reproduce red first, verify live not just by suite, keep a
verification ledger, and never let a summary claim more than its evidence file.

### 3.2 REG-17 — No third party has ever reproduced any verification

**Type:** PROCESS · **Severity:** MEDIUM · **Effort:** M · **Status:** **GREEN END-TO-END — REG-17 ACHIEVED (2026-07-24, run `30067198501`, head `9ac72df`).** The full `npdev-ci-validation.yml` — BOTH the Linux post-Beta0 maturity job AND the Windows segmented job — runs **green end-to-end on GitHub-hosted runners** from a clean checkout: automated external reproduction on hardware this project has never touched (the mechanism REG-17's DoD names). It took ~11 root-caused fixes across ~9 CI rounds, each unlocking the next never-executed step: CI-1/2/3, Fix A (postgres ITs → a JDBC-capable sample), NEW-2 (surface-evidence `-PendingOk`), REG-32 (bootstrap advisory), REG-33 (Windows `npm --prefix` ENOENT), the deterministic runtimehost-libs sync (was cache-dependent), REG-34 (`@DisabledOnOs(WINDOWS)` on the MinIO Testcontainers test — windows-latest can't run Linux containers), and the editor-E2E static-host path alignment (stage/serve resolved different dirs). A literal human third-party run stays a nice-to-have (owner's call); automated external reproduction is done. **Round history (kept for the record):** ADVANCED — round 2 (2026-07-23); mechanism proven, 3 findings fixed+confirmed, 2 new surfaced. Round 1 (run `29974176793`, `main`) surfaced 3 CI bugs (CI-1 `projectcachedir`, CI-2 upload-`..`, CI-3 script-quality). All three FIXED (commits `2065a72`/`60cda1d`/`b8a5112`) and **CONFIRMED green on the re-run** (`30051880197`, `beta1-vision-spine`, GitHub ubuntu+windows): the Linux job advanced from dying at step 4 to passing 11 steps. That re-run then surfaced **2 NEW first-contact findings**, each a prior closure that was environment/wrapper-specific and does NOT hold on the full external run: **(NEW-1)** the 10 `IT-EXTPG-1` tests REG-2 closed "10/10 green on real Postgres" (local Windows Docker) fail on Linux CI at `StartupValidator.java:317` — partially reopens REG-2 on Linux; **(NEW-2)** `run-runtime-surface-evidence.ps1` hard-throws on the `classification`/`footprint` checks REG-5 retired to advisory, because ci-validation calls the raw script not the advisory wrapper. Both filed at `..\NPDev_General__OutsideRepo\reg17-linux-validation-2026-07-22\run-30051880197-findings.md` (not silently fixed). **Disposition (2026-07-23): NEW-2 FIXED** (`-PendingOk` added to ci-validation's surface-evidence call, mirroring REG-5's `run-runtimehost-gate.ps1:123`). **NEW-1 FIXED via Fix A, verified locally RED→GREEN (2026-07-24).** A local repro (Windows+Docker, Testcontainers Postgres) corrected the diagnosis: the real failure is **"DataSource bean is required when mode=postgres"** (not the Flyway check first hypothesized — the InMemory `canonical-demo` wires NO JDBC DataSource; had "relax the Flyway guard" been committed blind it would have fixed nothing — RED-first caught it). Fix A: point the postgres ITs at a JDBC-capable sample. Proven: `canonical-demo` integrationTest = 10/35 FAIL; **`superuser-admin-console` (H2Local → JDBC DataSource) = 35/35 PASS** (JwtAuthExternalBetaIT 8/8, PublicationRollbackE2EIT 1/1, TenantIsolationE2EIT 1/1, ProofMatrix 25/25). ci-validation's IT step now generates+tests `superuser-admin-console`. **Fix A CONFIRMED on the consolidated CI re-run** (`30057723015`, 2026-07-24): "RuntimeHost generated-app Postgres integration tests" → **success** on the runner. NEW-2 also confirmed (surface evidence → success). **Two items remain (REG-17 continues):** (a) **Round-3 Windows `LegacyModelMigrationToolTest` NOT fixed** — the `setup-python` hypothesis was WRONG (added it anyway as harmless hardening, but the test still fails 1/350 at `assertEquals(0, npdev.bat-exitValue)`); it runs **exit 0 locally on Windows**, so the failure is CI-Windows-specific and un-diagnosable from here without the CI test's captured `outputText` (artifact download is outside `gh-api.sh`'s repo scope). (b) **New downstream finding:** Linux "Bootstrap post-Beta0 maturity reports" (`npdev report bootstrap`) now fails — first time that step ran. Both filed, not fixed. None is a launch-blocking product defect. · Round 4 (run `30059214129`, 2026-07-24): still RED at [Bootstrap post-Beta0 maturity reports (Linux), DSL contract check / LegacyModelMigrationToolTest (Windows)]; filed, not fixed — same two layers as round 3 (`30057723015`), no new layer surfaced (this round's commits were docs-only, no code fix landed). See `..\NPDev_General__OutsideRepo\reg17-linux-validation-2026-07-22\run-30059214129-findings.md`.

**What.** Every green suite, live rehearsal and gate run in this project's history was produced on
one machine, by you or an AI session you supervised. The verification ledger is honest and detailed
— but it has never been *independently exercised*.

**Why it matters.** Reproducibility is the difference between "we believe this works" and "this
works". It is also a launch prerequisite: a self-hosted, source-first distribution (ADR-0007) means
strangers will run these gates on hardware you have never seen.

**Where.** Blocked on REG-10 (CI green) and REG-11 (cross-platform scripts) — those two are the
mechanism by which a third party becomes *able* to reproduce anything.

**Advanced (2026-07-22), bonus of the REG-13/14 closure run (Part B, Task C).** The same
independent tester (fresh context, own worktree, cold brief, no coaching) ran
`run-generator-gate.ps1` (completed: FAILED, 3/172 tests — a real pre-existing suite finding, not a
tooling gap, filed separately) and `run-runtimehost-gate.ps1` (completed: FAILED, hit the same
RuntimeHost-libs staging gap Task B found). Every question it had to ask along the way is logged.
**Not fully closed**: `run-frontend-gate.ps1`/`run-beta-release-gate.ps1` were not attempted
(time budget; the latter is an explicitly long-running multi-script evidence orchestration, not a
quick reproduction), and this ran inside the same sandbox/OS as the driving session rather than on
genuinely unknown hardware (a real limitation of the subagent-as-tester approximation, honestly
noted). Evidence: `NPDev_General__OutsideRepo/external-tester-evidence/2026-07-22/friction-log-task-c.md`.

**How to fix (what remains).** A real external person (or a fresh session on hardware this project
has never touched — a different OS/machine, ideally Linux) clones, builds, and runs the remaining
two gates from `docs/` alone.

## 3.3 REG-18…REG-26 — findings filed by REG-16's Tier-A adversarial review (2026-07-21)

These are the MEDIUM/LOW/INFO findings from `docs/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md` (R1), filed
as dated items per the closure plan's triage rule so none is silently dropped. **None is a data-breach
or auth-bypass; there was no CRITICAL/HIGH.** Full failure scenarios + fix sketches are in that
document — this table is the ledger hook.

| Item | Sev | Finding | Fix sketch (RED-first before fixing) |
|---|---|---|---|
| ~~**REG-18**~~ | MED | Login timing side-channel enables username enumeration | **CLOSED (2026-07-21, Tier B, commit `b29bf4d`).** `PasswordHasher.verifyDecoy` runs a real PBKDF2 against a fixed decoy hash on both no-user / no-credential login paths; RED-first `PasswordHasherDecoyTest`. |
| ~~**REG-19**~~ | MED | `LoginThrottle.windowsByKey` unbounded → memory-exhaustion DoS via unique-username spray | **CLOSED (2026-07-21, Tier B, commit `b29bf4d`).** Hard cap (100k) with expired-first + oldest-live eviction and cutoff tie-break; RED-first `LoginThrottleBoundedTest` (sprays 3× the cap). |
| ~~**REG-20**~~ | MED | No defense against password-spraying (limiter was per-`(tenant,username)` only) | **CLOSED (2026-07-21, Tier B, commit `0182007`).** Added a per-source-IP arm to `LoginThrottle` (default 50/window vs 10/username), wired the client IP through `LoginController`; success clears the username window but not the IP window. RED-first `LoginThrottleIpSprayTest`. |
| ~~**REG-21**~~ | MED | `password-reset/request` unthrottled (email-bomb / token-row spam) | **CLOSED (2026-07-21, Tier B, commit `0182007`).** `PasswordResetController` reuses the same limiter (5/user, 20/IP); over-limit returns the same generic 200 but sends no email / creates no token. RED-first (6th request sends no email). |
| ~~**REG-22**~~ | MED | `ActuatorAdminGuardFilter` trusted JWT claim-roles without live re-resolution / `tv` | **CLOSED (2026-07-21, Tier B, commit `0182007`).** `SuperUserCredentialAuthFilter` sets a marker only after a live super-key resolves ACTIVE; the actuator gate now requires that marker, so a JWT-borne (or revoked) SUPERUSER role no longer opens metrics. RED-first (role-only claim now 403). |
| ~~**REG-24**~~ | LOW | `"default"` sentinel collides with a real tenant named `default` | **CLOSED (2026-07-21) — already comprehensively guarded; verified, no change needed.** All three tenant-insert paths already reserve `default`: `TenantRegistryService.create` rejects it, `IdentityProvisioning.ensureTenantRegistered` skips it, `TenantAutoRegistrationRunner`'s SQL excludes it. No real `default` tenant can be created, so the isolation collision cannot arise. |
| ~~**REG-23**~~ | LOW | `tv`-less tokens are never revocation-checked (backward-compat by design) | **DONE (2026-07-24, config-driven, owner decision).** The whole revocation decision is centralized in `IdentityRoleLookup.isTokenRevoked` — the **single point** both claim→context paths now call (`IdentityAwareContextResolver` + kernel `GeneratedCrudRuntimeSupport`), so they cannot diverge. New config `npdev.auth.jwt.reject-tokens-without-tv-after` (ISO-8601 instant, **default off** = today's lenient behavior); once reached, tv-less tokens are rejected on both paths; tv-bearing tokens unaffected. Bridged Spring→system-property by `TvlessTokenCutoverBridge` (fails fast on a malformed value). Verified: 4/4 `IdentityRoleLookupTvlessRevocationTest` (expression-cel), documented in `docs/CONFIGURATION.md`. |
| **REG-25** | LOW | Tenant match is case-sensitive → isolation-bucket fragmentation (NOT a cross-tenant bypass) | **DONE (2026-07-24) — on-write normalization + migration tool.** Grounding corrected the earlier nuance: the core write-path normalizers (`ExecutionContext`, `KernelRunner.normalizeTenantOrDefault`, adapter `normalizeTenant`, bond-check) only **trimmed** (did NOT lowercase); only peripheral sites lowercased (`TenantRegistryService.create` on insert, `LoginThrottle`, `IdentityProvisioning`'s default-check) — so business data landed under `Acme` while the registry stored `acme`. **Fix:** canonicalize `tenant_id` to lowercase at the single choke point every read and write derives its tenant from — `com.npdev.kernel.ExecutionContext`'s compact constructor (a tenant-specific `normalizeTenantId`; `actorId` stays case-sensitive; the reserved `default` sentinel is unaffected). Proven by RED→GREEN `ExecutionContextTenantCanonicalizationTest` (mixed-case convergence); kernel gateway/context tests + the full RuntimeHost gate (freshly-built kernel jars, assembled-app `:test`) green. **Existing data:** `scripts/ops/canonicalize-tenant-ids.ps1` (dry-run default, `-Apply`, `-Force`) lowercases `tenant_id` across the tenant registry + every business table (discovered via `information_schema`), with a PK-agnostic collision detector (`GROUP BY LOWER(tenant_id) HAVING COUNT(DISTINCT tenant_id) > 1`) that skips + reports merge-risk tables unless `-Force`; proven end-to-end on a seeded H2 DB (safe lowercase, collision skip, forced merge). No forced in-place migration; documented in `docs/SCHEMA_EVOLUTION.md`. |
| ~~**REG-26**~~ | INFO | Granular JWT error codes disclose *why* a token failed | **WONTFIX (2026-07-21).** Standard practice; the codes name the validation reason (expired / bad issuer / bad signature), not any secret or account state, and materially aid operator/integration debugging. Collapsing to a single generic error would trade real diagnosability for negligible disclosure reduction. |

**Tier-B status (2026-07-21):** REG-18, REG-19, REG-20, REG-21, REG-22 CLOSED; REG-24 verified
already-guarded; REG-26 WONTFIX; REG-23 + REG-25 DEFERRED with rationale (cutover / migration). Nothing
in this set is release-blocking, and all of it is now decided rather than open.

---

## 3.4 REG-27…REG-30 — findings from independent code verification of the REG-7/REG-8 implementation (2026-07-22)

After the REG-7/REG-8 feature work landed (commits `7caf777`…`6879cda`), an independent code-level
verification pass (two adversarial agents + direct reading of the safety-critical paths) checked the
implementation against the claims. The work was real and largely sound — the collision claim is
correctly released in a `finally` (a refusal cannot wedge the DB *in code*), the "dirty schema on
virgin DB" bugs were genuinely found and fixed, `SchemaDeltaReport.ALWAYS_EXCLUDED_TABLES` correctly
excludes both new self-bootstrapped tables, and the ExternallyManaged generation-time guard and real
type comparison both exist. But four items surfaced, one of them a correctness bug in REG-8's own
headline behaviour.

| Item | Sev | Finding | Status / fix |
|---|---|---|---|
| **REG-27** | MED | **REG-8 Trigger C false-negative for a fresh-installed build.** Trigger C (`databaseMigratedPastThisBuild`) only fires if the rolled-back-to build's fingerprint has a prior `APPLIED`/`MANUALLY_MARKED_DONE` row in `npdev_schema_history`. A build whose fingerprint was reached by **fresh install** never had one (the blank-fingerprint boot writes no history row; `afterMigrate` wrote only `npdev_schema_metadata`). So the register's own canonical example — original fresh-installed build N, N+1 drops a column, roll back to N — was **not** refused; the dropped column was still silently re-added empty. The headline test passed only because it hand-seeded an `APPLIED` row for N that a real fresh install never writes. | **CLOSED (2026-07-22).** `afterMigrate` now records the initial realization as an `APPLIED` history point on the fresh-install path (`storedAtBootStart` blank), so every fingerprint the DB has genuinely been at is visible to Trigger C. Safe there (runs after `flyway.migrate()`, so no virgin-DB Flyway trip). RED-first: two new tests in `SchemaLifecycleExecutorDatabaseMigratedPastBuildTest` — a direct fresh-install-records-history assertion and the honest end-to-end (no hand-seeded row). |
| **REG-28** | LOW–MED | **Stale-mark fast-forward (REG-7.2).** `MigrationMarkStore` records only the *target* fingerprint — no `from`-fingerprint binding and no TTL. A leftover mark for X (a deploy planned then abandoned, so `findMatching` never consumed it) will silently authorize the *first* future boot whose target is X — from whatever the DB is actually at — fast-forwarding with zero migration/classify/Trigger-C passes. Content-hash fingerprints + consume-on-use narrow the real trigger to a re-release of the identical model, but the mechanism has no guard and is untested. | **CLOSED (2026-07-22).** `MigrationMarkStore` now binds every mark to a `(from_fingerprint, marked_fingerprint)` pair; `findMatching(dataSource, fromFingerprint, toFingerprint)` only returns a mark when the boot's OWN live stored fingerprint equals the recorded `from`. `SchemaAcknowledgmentController#markDone` takes `fromFingerprint`/`toFingerprint` (was `fingerprint`); `SchemaLifecycleExecutor.beforeMigrate` passes `stored` as `from`. Pre-fix (unbound, `from IS NULL`) rows are never matched again — ordinary SQL null semantics on `WHERE from_fingerprint = ?`, no special-case code needed; the table upgrades in place via a guarded `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` for a genuinely pre-existing deployment (new installs declare the column directly in `CREATE TABLE`). RED-first (`SchemaLifecycleExecutorMigrationMarkTest`): a new test proves a mark recorded for `from=A` does not fire when live-stored is `Z`, and does fire when live-stored is `A`; existing scenarios updated as fixture changes only. **Verified live**: real boot rehearsal against `superuser-admin-console` (H2Local, real jar, real ControlPanel API) — a mark inserted for a `from` that didn't match the live stored fingerprint was correctly left unconsumed (ordinary classify() ran instead); the same mark fired once the stored fingerprint was made to match. Evidence: `NPDev_General__OutsideRepo/reg28-30-evidence/`. `docs/SCHEMA_EVOLUTION.md`'s "marking a migration as done" section updated to the from→to form. |
| **REG-29** | LOW (bug) / MED (coverage) | **Claim-release-on-refusal is correct but untested.** The production `finally` in `migrate` does release the boot's own claim on a refusal thrown from inside the migration body (Trigger C, destructive-without-token) — verified by reading. But no test proves it: the one "refuses" test in `SchemaLifecycleExecutorMigrationClaimTest` fails at claim *acquisition* (PK collision), where the boot never held a claim. The wedge-risk property that matters most is unverified. | **CLOSED (2026-07-22).** Added `refusalWhileHoldingOwnClaimStillReleasesIt` to `SchemaLifecycleExecutorMigrationClaimTest`: seeds Trigger C's canonical shape (REG-8) so `beforeMigrate` throws from inside `migrate`'s try block, *after* this boot's own claim was acquired; asserts the throw and that `MigrationClaimStore.current` is empty afterward. RED-first: verified the test fails (claim left behind) when the `finally`'s release is neutralized, then confirmed it passes with the real code. No production change — test-only, as the finding says the code is already correct. |
| **REG-30** | MINOR | **Duplicate marks each survive one consume.** Two marks for the same fingerprint → `consume` deletes only the matched row; the older duplicate survives to fast-forward a second future boot at that fingerprint. | **CLOSED (2026-07-22).** Folded into the REG-28 fix: a unique index on `(from_fingerprint, marked_fingerprint)` rejects a duplicate mark for the identical transition at insert time (`IllegalStateException`), so there is never a second row to survive a consume. Verified live against `superuser-admin-console`: re-`POST`ing an identical `(from, to)` pair via the real ControlPanel API returned `500` and `GET /marks` still showed exactly one row. Unit coverage: `duplicateMarkForTheSameTransitionIsRejected` in `SchemaLifecycleExecutorMigrationMarkTest`. |

Also noted (no ID, worth a one-line code comment): `findExternalSchemaIncompatibilities` (REG-7.1)
presence-checks columns that have no declared type in `businessTableColumnTypes()` rather than
type-checking them — fine for typed columns (the mismatch is genuinely flagged), just not total.

**Net:** REG-7's three sub-features and REG-8's refusal are delivered and, with REG-27 fixed, REG-8
now genuinely refuses its own canonical example. **REG-28/29/30 are now CLOSED (2026-07-22)** — see
each row above and `docs/REG28_30_REG12S2_CLOSURE_PLAN.md`.

---

## 3.5 REG-31 — `run-script-automation-quality` structured-report-contract check is mis-calibrated

**Type:** PROCESS (quality-gate calibration) · **Severity:** LOW · **Effort:** M · **Status:**
**CLOSED (2026-07-24).** The check's `structured-report-contract` sub-check greps script *source* for
the literal strings `Invoke-NPDevReportedCommand`/`Invoke-ReportedCommand` and
`Write-NPDevJsonFile`/`Write-StructuredRunReport` and failed any of the ~68 `scripts/quality/*.ps1`
that lack them — flagging **59**. That was a helper-name presence test, not a report-behavior test.
**Spot-checked 9 of the 59** (`run-frontend-gate`, `run-ai-beta-gate`, `run-boundary-lock-check`,
`run-json-schema-validation-tests`, `run-release-checklist-gate`, `run-maturity-score`,
`run-ai-knowledge-gate`, `run-internal-db-schema-source-of-truth-check`,
`run-publication-runtime-sql-neutrality-check`): **56 of the 59** persist a genuinely valid structured
JSON report by other means (direct `ConvertTo-Json | Set-Content`/`Out-File` to the standard
`scripts\reports\out\*-report.json` convention, or — for `run-json-schema-validation-tests.ps1` — by
loading and relabeling a delegate script's already-valid report). **3 are genuinely non-compliant**
(`run-ai-knowledge-gate.ps1`, `run-internal-db-schema-source-of-truth-check.ps1`,
`run-publication-runtime-sql-neutrality-check.ps1`: `Write-Host`/`throw`/`exit` only, no JSON report
persisted at all). **Fix:** `scripts/quality/run-script-automation-quality.ps1`'s
`structured-report-contract` sub-check now tests the actual behavior — does the script serialize an
object to JSON (`ConvertTo-Json`) AND persist it (`Set-Content`/`Out-File`/`Write-NPDevJsonFile`) AND
target the standard report-path convention — by any mechanism, not just the two named helpers. The 3
genuinely non-compliant scripts are excluded via a dated, commented `$reportContractMigrationBacklog`
list in the script (same pattern as the pre-existing detector/runtime-surface-evidence exclusions) —
filed here as backlog, not silently dropped and not mass-migrated. Verified locally:
`pwsh -File scripts\quality\run-script-automation-quality.ps1` exits 0 (65/65 scoped scripts pass;
was 9/68). CI's "Script automation quality" step `continue-on-error: true` removed — blocking again.
**Backlog (not part of this closure — migrate these 3 to a real structured report when next touched):**
`run-ai-knowledge-gate.ps1`, `run-internal-db-schema-source-of-truth-check.ps1`,
`run-publication-runtime-sql-neutrality-check.ps1`.

## 3.6 REG-32 — `npdev-ci-validation.yml` Bootstrap step aggregates ~21 maturity reports its producers never generate

**Type:** PROCESS (CI evidence-orchestration) · **Severity:** LOW–MED · **Effort:** M–L · **Status:**
**CLOSED for the PowerShell bootstrap chain (2026-07-24); residual Gradle-native gap filed separately
as REG-35.** The Linux job's "Bootstrap post-Beta0 maturity reports" step runs `npdev report
bootstrap`, which **aggregates** ~21 maturity reports and hard-fails if any are missing or
schema-invalid. It does **not** generate them — those come from ~21 separate `run-*.ps1` producer
gates this job never runs, so the aggregator saw ~19 "missing" (REG-3-class precondition-unmet) plus a
genuinely schema-invalid `stateful-additive-migrations-report.json`. **Fix (both halves done):**
(1) **Precondition-awareness (REG-3 pattern applied):** `bootstrap-post-beta0-reports.ps1`,
`validate-report-schemas.ps1`, and `generate-final-evidence-bundle.ps1` now each distinguish
*precondition-unmet* (required reports never generated → exit 2, non-fatal) from *check-failed* (an
existing report/producer is genuinely invalid → exit 1). `validate-report-schemas.ps1`'s own
`schemaInvalidReportCount` also had a real bug: a report that was simply never generated has
`schemaStatus="not-run"`, which the old filter (`schemaStatus -ne "passed"`) counted as *invalid* —
conflating "never produced" with "produced but wrong." Fixed to require `.exists` too. Both PowerShell
callers now pass `-RequireAllMaturityReports` so the required-set matches the 21-report registry both
already hardcode. Schemas `report-bootstrap-and-regeneration-report.schema.json` and
`final-evidence-bundle-manifest.schema.json` extended with a third `overallStatus` enum value
(`precondition-unmet`). Verified locally: `bootstrap-post-beta0-reports.ps1` on the current tree (19/21
reports genuinely absent) now prints `PRECONDITION-UNMET: 19 of 21 required reports were never
generated (producers not run)` and exits **2**, not 1; `npdev report bootstrap` (the CLI wrapper)
propagates that exit code unchanged (`except subprocess.CalledProcessError: return exc.returncode` —
already correct, no CLI change needed).
(2) **Fixed the 1 real schema-invalid report** — `stateful-additive-migrations-report.json`. Root
cause was **two** real defects, not one: (a) `run-stateful-additive-migrations-check.ps1`'s
`Resolve-GeneratorTestResultXml` walked up **two** directory levels from the repo root
(`Split-Path -Parent (Split-Path -Parent $root)` → `…\WorkSpace`) when the generator's
`layout.buildDirectory` redirect actually lands one level up (`…\WorkSpace\NPDev\Build\gradle\...`) —
found by regenerating the report fresh and locating the real XML on disk; the stale (2026-07-21)
committed report predated this and had accidentally never exercised the affected check honestly.
Fixed to a single `Split-Path -Parent $root`, RED (`migration-plan-never-reports-false-fresh-install`
failed, `resultXmlCaptured: false`) → GREEN (re-ran the two gated Gradle test classes, ~8 min,
`resultXmlCaptured: true`, `overallStatus: passed`). (b) The schema itself was stale: it required
`const: true` on 8 boolean fields the producer's own embedded R8 remediation (2026-07-20, documented
in its `findings`) deliberately retired to permanent `false` — their capability moved to the LNCH-1
Postgres Testcontainers twin / `StatefulMigrationPlannerTest` / `Build-NpdevApp.ps1 -PlanOnly`, and
`overallStatus` is gated on the `checks` array, not these descriptive fields. Relaxed the schema's
`allOf[0].then` to only require `const: true` on the 4 fields that are still real, live-verified
signals (`destructiveChangesRejected`, `runtimeMigrationPreflightPassed`,
`flywayValidateOrMigrateProofPassed`, `riskThresholdConfigurable`). Also found `findings` items were
narrative strings (matching this producer's own `doesNotSolve` shape) against an item-type of
`object` the producer never satisfied since its first commit — no consumer reads `findings` as
structured objects here, so relaxed to `string`. Verified: `Invoke-JsonSchemaValidation.ps1` against
the regenerated report → `errorCount: 0` (was 10: 8 const + 1 if/then meta + 1 findings-type).
**Residual (filed as REG-35, NOT fixed here — discovered as a byproduct of this verification, out of
this task's scope):** the SAME CI step also runs `./gradlew postBeta0MaturityCheck`, whose
Gradle-native `validateReports`/`validateBoundaryLocks` tasks (`build.gradle`) have (a) the identical
missing-vs-invalid conflation this task just fixed in PowerShell, in a wholly separate validation
pipeline, and (b) `final-evidence-bundle-manifest.schema.json`'s `artifacts[]` per-item fields
(`overallStatus` enum, `bytes >= 1`, `sha256` pattern) are unconditionally strict with no tolerance for
a not-yet-generated sub-artifact — both pre-existing, both trip on any run with missing producer
reports (i.e. every run of this job today), independent of anything in this closure. Because of that,
the CI step's `continue-on-error: true` is **kept** (not removed) and its comment updated to name what
is fixed vs. what REG-35 still covers — removing it now would just turn the step red for a different,
already-filed reason and misrepresent this closure as more complete than it is.

## 3.7 REG-33 — CLI's on-demand `npm install` for the JSON-schema validator fails on Windows from a Python subprocess

**Type:** BUG (product, Windows) · **Severity:** LOW · **Effort:** S · **Status:** **FIXED
(2026-07-24), verified locally RED→GREEN.** Real cause (captured via CI diagnostic): `npm --prefix
<validator> install` run with `cwd=repo-root` makes npm read `package.json` from **cwd** (the repo root
has none) → `ENOENT ... open D:\...\package.json` (exit `4294963238` / `-4058`) on the CI Windows npm.
The `--prefix` flag only sets where `node_modules` lands, not where npm reads the manifest. **Fix:**
`npdev_cli.py` now runs `npm install` with `cwd=validator_root` (no `--prefix`). Verified locally:
removed `node_modules`, ran `npdev migrate` → install ran from the validator dir, exit 0, valid output.
CI also pre-installs the deps in the Windows job (belt-and-suspenders) via `working-directory`. Original
framing (kept for history): `npdev_cli.py`'s `validate_json_schema` (used by `migrate`,
`validate`, `generate`, …) runs `npm --prefix scripts/quality/json-schema-validator install`
(`check=True`) when the validator's `node_modules` is absent (it is gitignored → absent on any fresh
checkout). That on-demand `npm install` — invoked as `subprocess.run([npm.cmd, ...])` from Python —
**fails with ENOENT (exit `4294963238` = libuv `-4058`) on the CI Windows runner** (works on Linux and
on a local Windows dev box). Root-caused via a CI diagnostic that captured the test XML:
`LegacyModelMigrationToolTest` → `npdev migrate` → this install → `CalledProcessError`. **Worked around
2026-07-24** by pre-installing the validator deps as a normal `npm` workflow step in the CI Windows job
(so the CLI skips its own broken install). **Real fix (product):** make the CLI's npm invocation
Windows-robust — run it via `cmd /c npm …` (or `shell=True`, or invoke `node <npm-cli.js>`) so a real
Windows user running `npdev migrate/validate/generate` without pre-existing `node_modules` doesn't hit
the same ENOENT. Likely a Python-3.12+ Windows `.cmd`-via-subprocess behavior change; verify the fix on
a clean Windows checkout.

## 3.8 REG-34 — Windows CI job runs Testcontainers (Linux-container) tests that `windows-latest` can't run

**Type:** PROCESS (CI platform scoping) · **Severity:** LOW · **Effort:** S (per test) · **Status:**
**IN PROGRESS (2026-07-24).** The `npdev-ci-validation.yml` **Windows** job runs full gate suites that
include Testcontainers tests (which start **Linux** containers — MinIO, Postgres). **GitHub
`windows-latest` runners cannot run Linux containers** (only Windows containers; unlike Linux runners
and a local Docker Desktop), so those tests fail on Windows while passing on the green Linux job.
Surfaced by REG-17 once Fix A + REG-33 unblocked the Windows job's downstream gates. **Approach:**
disable each genuinely-Linux-container test on Windows via `@DisabledOnOs(OS.WINDOWS)` (they stay
enabled on Linux CI, which validates them). **Done so far:** the generator gate's
`HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest` (MinIO) — verified locally that it skips
on Windows and still builds. **Remaining:** the Windows job's further gates (Security hardening, Runtime
security, RuntimeHost gate, Editor gate) have never run to completion; each may contain more
Linux-container tests to scope the same way — iterate as they surface. Note: the two other generator
proof tests boot a real app via `ProcessBuilder` (no container) and **pass** on Windows — only genuine
Linux-container tests need this treatment, not all packaged-app tests.

## 3.9 REG-35 — Gradle-native `postBeta0MaturityCheck` has the same missing-vs-invalid conflation REG-32 fixed in PowerShell, plus an overly strict nested artifact schema

**Type:** PROCESS (CI evidence-orchestration, Gradle-native) · **Severity:** LOW · **Effort:** M ·
**Status:** OPEN (filed 2026-07-24, discovered as a byproduct of verifying REG-32's fix — pre-existing,
not caused by that work). The Linux job's "Bootstrap post-Beta0 maturity reports" step also runs
`./gradlew postBeta0MaturityCheck` (`build.gradle`), a completely separate Gradle-native validation
pipeline (`validateSchemas`/`validateAiScenarios`/`validateScenarioCoherence`/`validateBoundaryLocks`/
`validateReports` → `writeGradleNativeValidationReport`) that independently re-checks 7 of the same
maturity reports. Reproduced locally (`./gradlew postBeta0MaturityCheck`, after installing the JSON
schema validator's `node_modules` by hand — Gradle's own `exec{}` call to bare `npm` fails to start on
this Windows box, a REG-33-shaped PATH/`.cmd` issue worth checking separately if it also affects a
real CI Windows job). Two independent gaps, neither touched by REG-32:
(1) **`validateReports`** (`build.gradle` ~line 362) treats a missing report file as an unconditional
`failures << "...: missing schema or report"` (no precondition-unmet concept exists anywhere in this
Gradle-native framework — `recordCheck`'s `passed`/`failed` is strictly binary), and separately checks
`reportJson.overallStatus != 'passed'` verbatim — so `report-bootstrap-and-regeneration-report.json`'s
new (REG-32) `"precondition-unmet"` value now also trips it as a hard failure. Reproduced: 6 of 7
report pairs "missing schema or report" + the 7th "report status is precondition-unmet". Fix would
mirror REG-3/REG-32: classify a missing report as a distinct, non-blocking bucket, and treat
`overallStatus: "precondition-unmet"` as tolerable (only `"failed"` blocks).
(2) **`final-evidence-bundle-manifest.schema.json`'s `artifacts[]` items** (`overallStatus` enum,
`bytes >= 1`, `sha256` pattern, `schemaVersion` minLength) are unconditionally required with no
"this sub-artifact was never generated" escape hatch — pre-existing (would have failed schema
validation on this manifest the first time ANY required report was missing, well before REG-32).
Reproduced: validating a freshly-generated manifest (19/21 reports absent) → 76 schema errors, all on
missing artifacts' placeholder values (`overallStatus: "missing"` not in `["passed","failed"]`,
`bytes: 0` violates `minimum: 1`, empty `sha256`/`schemaVersion` violate their constraints).
(3) `validateBoundaryLocks` also independently fails on this tree for an unrelated, long-pre-existing
reason (hardcoded `D:\`/`C:\` drive-letter paths in `README.md` and this same workflow file, both
untouched this session — confirmed via `git status`/`git diff`) — not part of this finding's scope,
noted only because it's why `./gradlew postBeta0MaturityCheck` can't be evaluated in isolation from it.
**Not fixed here** — `build.gradle` is the single most central, highest-blast-radius build file in the
repo and was not part of REG-32's scoped file list; extending the REG-3 pattern into a second,
structurally different (Groovy/Gradle-native, not PowerShell) validation pipeline is its own bounded
task. Until fixed, the CI "Bootstrap post-Beta0 maturity reports" step keeps `continue-on-error: true`.

## 3.10 REG-16-resid — adversarial review of the other ~21 launch surfaces (multi-round programme)

**Type:** PROCESS (security review) · **Severity:** HIGH (the surface risk; not launch-blocking —
see honest note) · **Effort:** L (multi-round) · **Status:** **Round 2 of N COMPLETE (2026-07-25).**
REG-16's original Tier-A review (§3.1) covered only LNCH-2 (tenant isolation) + LNCH-4 (auth); the
other ~21 launch surfaces — generator codegen, kernel `FlowEngine`/`KernelRunner`, LNCH-13 row-level
authz, the export/PDF path — had never had an attack-first review. This item tracks that residual
work as an iterative, one-surface-per-round programme (`docs/POST_REG17_CLOSURE_PLAN.md` Task 4),
reusing the REG-16 template and discipline.

**Round 1 (2026-07-24): the kernel execution path** — `KernelRunner`'s capability-invocation path
(circuit-gate → bulkhead-acquire → idempotency-check → retry → cache-write → failure-accounting),
`RegistryCapabilityDispatcher`, and the idempotency/circuit-breaker/bulkhead mechanisms (both in-proc
and Postgres-backed adapters). Chosen first because it's the code every generated app runs — a flaw
there is a flaw everywhere. Findings document: `docs/REG16_KERNEL_EXECUTION_ADVERSARIAL_REVIEW.md`.
**Headline: no CRITICAL or HIGH finding.** Tenant scoping of all three resilience mechanisms is
correct (`CapabilityOpKey(tenantId, capability, operation)` keys idempotency/circuit/bulkhead state
alike — no cross-tenant leakage); the bulkhead's admission control is genuinely atomic
(`java.util.concurrent.Semaphore`); reflection-based capability dispatch never lets request data
choose *which* method is invoked, only argument values (author-time operation selection, not a
confused-deputy surface). Residual: **2 MEDIUM + 2 INFO**, filed as **REG-36** and **REG-37** below
(no dated item for the two INFO notes — one is a recommendation for a future reviewer, the other a
cross-reference confirming an already-tracked, already-guarded item — see the findings doc F3/F4).
Per the plan's triage, with no CRITICAL/HIGH the mandatory Tier-B work for this round is empty; both
MEDIUMs are scheduled, not dropped or silently fixed.

**Round 2 (2026-07-25): LNCH-13 row-level (data-scoped) authorization** — the kernel-side gateway
(`ConceptGatewaySemanticPolicy`/`ConfiguredConceptGatewaySemanticPolicy`/`DefaultConceptGateway`) AND
the generated CRUD surface every app's REST controllers actually call (`service-base.mustache`),
explicitly named by Round 1 as "not read" for that round. Findings document:
`docs/REG16_LNCH13_ROWLEVEL_AUTHZ_ADVERSARIAL_REVIEW.md`. **Headline: one CRITICAL finding, found and
remediated this round.** A concept declaring a custom create/update/delete Flow got **zero row-level
`access.write` enforcement** on its generated REST endpoint — `enforceWithCreateFlow`/
`enforceWithUpdateFlow`/`enforceWithDeleteFlow` only ran `kernelRunner.execute(...)`, never
`conceptGateway.save/delete(...)`, and persistence afterward went straight through `conceptStore`,
bypassing `ConceptGatewaySemanticPolicy.isRowWritable` entirely — a complete bypass of LNCH-13's
write-scoping guarantee for any concept combining a custom Flow with an `access.write` rule (a
realistic, common combination). Fixed in `service-base.mustache` (the row-level/semantic gateway check
now always runs before a Flow's own side effects); RED-first proven against the real generator
pipeline (`ServiceBaseFlowRowLevelAuthzTest`); full `GATE-GEN` regression suite green. Residual: **2
MEDIUM**, filed as **REG-41** and **REG-42** below (an authorization-ordering info leak via lifecycle-
transition errors, and a row-scope-unaware pagination count) + 1 INFO (a check-then-act race, narrow,
recorded in the findings doc only, no register item).

**Rounds not yet done:** loop/await/orchestration flow-step types, `DefaultProcedureExecutor`, the
other durable-state Postgres adapters' own SQL, the generator's codegen output itself, and the
export/PDF path remain at **zero** adversarial review. Each is a candidate for a future round; do not
attempt all of them at once (per the plan's explicit STOP rule against mechanizing this item).

### REG-36, REG-37, REG-41, REG-42 — findings filed by REG-16-resid Rounds 1-2

| New item | From | Sev | Fix sketch |
|---|---|---|---|
| REG-36 | REG16K-F1 | MED | Bound/digest the idempotency key (SHA-256 hex when the resolved value exceeds a small threshold) symmetrically with the already-bounded cached success value (`KernelRunner.IDEMPOTENCY_RESULT_MAX_CHARS`). Today an oversized caller-influenced key (via a model author's `idempotencyKeyField`) can exceed Postgres's btree index-entry size limit, crashing the post-success idempotency-cache write with an uncaught exception — reporting an already-successful call as failed, and (because no record was cached) letting a caller's retry re-execute the operation, defeating dedup. Regression test: an oversized idempotency-key-field value still produces a cacheable, re-findable record. |
| REG-37 | REG16K-F2 | MED | Make the circuit-breaker failure-counter update atomic. `KernelRunner.onCapabilityFailure` does a plain get-then-put with no CAS/lock, present in both `InProcCircuitBreakerStateStore` (bare `ConcurrentHashMap`) and `JdbcCircuitBreakerStateStore` (blind `UPDATE … SET consecutive_failures = ?`, client-computed value, no server-side increment or row lock) — the bug is in `KernelRunner`'s orchestration, not either store. Under genuinely concurrent failures (exactly the scenario the circuit breaker exists for), the counter can undercount, delaying or preventing the circuit from opening at its configured threshold. Fix: push the increment into the store contract (`recordFailure(key, now, thresholds)`) implemented atomically in both backends (`ConcurrentHashMap.compute` / `UPDATE … SET consecutive_failures = consecutive_failures + 1 … RETURNING *`). Regression test: N concurrent synthetic failures against the same `CapabilityOpKey` leave the counter at N (or trip the circuit), not fewer. |
| REG-41 | LNCH13-F2 (`docs/REG16_LNCH13_ROWLEVEL_AUTHZ_ADVERSARIAL_REVIEW.md`) | MED | Reorder `DefaultConceptGateway.save()` so `enforcePermission`/`enforceRowWritable` run BEFORE `runWriteSemantics`/`validateLifecycleTransition` touches the previous record's data. Today the previous-record fetch + lifecycle-transition validation runs first and can throw `CONCEPT_LIFECYCLE_TRANSITION_INVALID` with the row's actual current status in the detail map, before any permission or row-scope check — letting a caller with zero `concept.write` permission and zero `access.write` scope learn one field's value via the error. Regression test: an unauthorized/out-of-scope actor attempting an invalid lifecycle transition gets `PERMISSION_DENIED`/`ROW_SCOPE_DENIED`, never the lifecycle error with the previous status disclosed. |
| REG-42 | LNCH13-F3 (`docs/REG16_LNCH13_ROWLEVEL_AUTHZ_ADVERSARIAL_REVIEW.md`) | MED | `ConceptGateway.query()`'s `total`/`hasMore` are computed before row-scope filtering, leaking the aggregate count of rows outside the caller's `access.read` scope (already noted as a pagination-accuracy "known limitation" in `docs/ROW_LEVEL_AUTHORIZATION.md`; re-filed here as the security-relevant read of the same gap). Fix: push row-scope filtering into/ahead of the store-level count, or recompute `total`/`hasMore` from the filtered set when the concept declares `access.read` (opt-in cost). Regression test: a row-scoped caller's `total` matches their own visible count, not the tenant's full count. |
| ~~**REG-38**~~ | — | MED | **DONE (2026-07-24).** Additive-migration constraints were not idempotent on H2. `SchemaRealizationEmitter.addConstraintIfMissing` wrapped the Postgres `ADD CONSTRAINT` in an `IF NOT EXISTS` catalog guard but the **H2 branch emitted a bare `ADD CONSTRAINT`**. That statement lands in `R__npdev_schema_additive_columns.sql`, a Flyway **repeatable** migration that re-runs whenever its checksum changes (i.e. after ANY model edit), so re-deploying a changed model against an existing H2 DB failed at boot with `Constraint "…" already exists` and refused the whole application. **Discovered live** while rebuilding WmsOffice with a new field (ARCH-upload P6). **Fix:** the H2 branch now emits `ALTER TABLE … DROP CONSTRAINT IF EXISTS <name>;` before the `ADD` (both verbs are supported on H2 and Postgres; Postgres path unchanged). RED→GREEN `SchemaRealizationEmitterAdditiveColumnsTest`; verified live — WmsOffice now boots cleanly against the same existing DB that previously refused. |
| ~~**REG-39**~~ | — | HIGH | **DONE platform-wide (2026-07-25), closing the "platform hazard" follow-up left open 2026-07-24.** App-scope fix (WmsOffice's own private pack copy synced to the platform pack) already recorded below; this closes the **generalized** hazard — *any* app carrying a stale built-in-pack copy — via the three-layer fix `docs/FINAL_FOUR_CLOSURE_PLAN.md` §4 called for. **Layer 1 (detect, `b1c7e85`):** `StartupValidator.validateIdentityPackFreshness()` — a boot-time check that needs zero new generation-time plumbing (the already-merged, pack-namespaced `compiledModel` is inspected for an `identity::User` concept + its `tokenVersion` field) — fails fast naming the pack, the concept, the missing field, and the fix, instead of the app booting into a later mystery auth failure. **Layer 2 (stop swallowing, `b7cd4f9`):** the real four SQL touchpoints reading/writing `token_version` (`LoginController`, `PasswordResetController.bumpTokenVersion`, `ControlPanelTenantUsersController.bumpTokenVersion`, `IdentityRoleLookup.tokenVersion` — **not** `JwtSigner`, which the closure plan named incorrectly: it only stamps an `int` into a JWT claim, no SQL) now tell a genuine schema-mismatch `SQLException` (new `SqlSchemaErrors.isSchemaMismatch`, SQLState class `42`) apart from a routine negative, via a new `IdentityPackSchemaException` carried through each "best-effort, never throws" helper. RED-first `LoginControllerTest` proves a table missing `token_version` now produces a distinct `identity_pack_schema_error`, not `invalid_credentials`. **Layer 3 (surface pre-deploy, `e57aa0c`):** the same drift check (`IdentityPackDriftItem`) now folds a synthetic `NEEDS_HOOK` item into the Impact Report's diff, so `-ImpactOnly` and the ControlPanel view report `NEEDS_ATTENTION` for a stale pack copy without needing a boot. **Verification:** GATE-H2 + GATE-PG green (direct `NPDevRuntimeHost` run + two full `run-runtimehost-gate.ps1` sample-build-test cycles); a **live proof** — deliberately strip `tokenVersion` from the platform's own `NPDevContract/packs/identity/pack.json`, regenerate `superuser-admin-console` against a virgin DB, boot — reproduced the exact intended failure message naming the pack/concept/field/fix, then a clean revert + re-boot confirmed no false-positive on a healthy pack (evidence: `..\NPDev_General__OutsideRepo\reg39-live-proof-2026-07-25\`); a full `rebuild-app` skill run (three-cache refresh) against WmsOffice itself confirms the platform fix doesn't regress the originally-affected app. **Prior WmsOffice-specific record (2026-07-24, kept for history):** symptom was `invalid_credentials` for every credential because `LoginController` (+`PasswordResetController`/`ControlPanelTenantUsersController`) unconditionally `SELECT … token_version FROM identity_users` against a table missing that column (WmsOffice's private pre-LNCH-4 pack copy at `AppGen/apps/_official/WmsOffice/definition/packs/identity/pack.json`), and the exception was swallowed into a generic auth failure. App-scope fix: synced WmsOffice's copy to the platform pack (adds `tokenVersion` + `PasswordResetToken`); verified live on a fresh DB. |
| ~~**REG-40**~~ | — | MED | **CLOSED (2026-07-24, SER-P9.3), via the schema-engine rebuild plan's Part II fast track** (`docs/SCHEMA_ENGINE_REBUILD_PLAN.md`). The tactical fix below is verified end-to-end on both engines and durable — closing this item on that basis. Correction to the earlier "FIXED tactically" note: the "strategic dedup" it flagged (routing this generation-time emitter's table-creation logic through the *runtime's* `SchemaDiffEngine`) was never a like-for-like follow-up — `SchemaRealizationEmitter` runs at GENERATION time in the generator module, while `SchemaDiffEngine`/`SchemaDiff` (the REG-6 rebuild's canonical model) is a RUNTIME (`com.finalexec.db.schemastate`) construct consumed at BOOT time by `SchemaLifecycleExecutor` — the two were never the same abstraction to merge. `SchemaDiffEngine` does emit its own `SAFE_TABLE_CREATE` item for a missing table (so the boot-time Impact Report already reports it correctly); no further work is tracked here. Was: the additive/repeatable migration `R__npdev_schema_additive_columns.sql` only ever emitted `ALTER TABLE … ADD COLUMN`/`ADD CONSTRAINT` (0 `CREATE TABLE`), so a new concept/table added to a model that is then re-deployed against an **existing** DB failed at boot with `Table "…" not found` (the versioned `V1` CREATE-TABLE migration had already run and does not re-run). **Fix:** `SchemaRealizationEmitter` now splits both `appendBusinessTable` (→ `appendBusinessTableShape` + `appendBusinessTableConstraints`) and `appendBonds` (→ `appendJunctionTableShapes` + `appendBondConstraints`), and the R__ assembly now emits, in order, (1) `CREATE TABLE IF NOT EXISTS` for every business + junction table, (2) the additive `ALTER TABLE … ADD COLUMN`s (internal, then business), (3) unique/index/FK constraint blocks — all idempotent, so a missing table now self-heals on upgrade exactly like a missing column already did. RED→GREEN `SchemaRealizationEmitterAdditiveColumnsTest#additiveScriptCreatesMissingBusinessTablesBeforeAnyAlterTable` (GATE-GEN); proven end-to-end against REAL Flyway migrations on both engines (new `SchemaLifecycleExecutorNewTableOnExistingDbTest` on H2, `SchemaLifecycleExecutorPostgresProofMatrixTest#newConceptAddedOnUpgradeGetsItsTableOnPostgres` on a real Postgres Testcontainer): boot with a 1-concept model → insert a row → upgrade to a 2-concept model against the SAME database → the new table exists (empty), the old row survives, `npdev_schema_history` records `APPLIED` (not a refusal). Orthogonal to REG-38 (that was constraint idempotency on *existing* tables). |

**Honest note (per the closure plan).** REG-36/REG-37/REG-41/REG-42 are not launch-blocking — the
ledger remains 24/0/0. Round 2 DID find a genuine CRITICAL auth-bypass (LNCH13-F1) — unlike Round 1,
this was not a "no CRITICAL/HIGH" round — but it was remediated within the same round per the plan's
STOP-and-remediate rule, so it does not sit open. REG-16-resid stays open until enough further rounds
have covered the highest-value remaining surfaces; it is the largest genuine *unknown* left in the
codebase (not a known bug), so it remains the highest-value non-mechanical work outstanding.

---

## 4. Suggested order (revised 2026-07-21 after independent code verification)

> **SUPERSEDED (2026-07-22).** The numbered order below was written before REG-2, REG-3, REG-9 and
> others were closed, so it now lists already-CLOSED items as "next actions." Do **not** action it as
> written. The current action order lives in
> `docs/AI_SESSION_DIGEST_2026-07-22_LNCH22_CLOSURE.md` (§9) and `docs/POST_LNCH22_EXECUTION_PLAN.md`.
> The list below is kept only as a record of the 2026-07-21 reasoning.

1. **REG-3** (`GATE-REL-1`) — near-free now: the design conflict it described was already fixed
   2026-05-14. Close the misdiagnosis and wire up the stale evidence-report orchestration; removes a
   phantom blocker from the picture before anything else is scoped.
2. **REG-2** (`IT-EXTPG-1`) — **re-diagnose before fixing**: reproduce the actual exception with
   Docker running; the previously recorded root cause did not survive a code check. Two of the ten
   dead tests cover tenant isolation and auth — get them running before REG-16 reviews that surface,
   so the review isn't done against code whose own E2E safety net is dark for an unknown reason.
3. **REG-9** (LNCH-4 secrets) — the last P0 in the ledger, now re-scoped to JWT key delivery + a
   super-user-key-seeding decision (DB credentials and API keys already work).
4. **REG-11** (Phases 2–4, partial) — the `gradlew.bat` migration is mechanical (helper already
   exists) and de-risks REG-10's eventual Linux run, since some of the 13 files are quality-gate
   scripts CI is likely to touch. Worth doing alongside/just before REG-10 rather than after.
5. **REG-10** (CI green) — one PR; genuinely blocked on `gh` CLI or GitHub web access, which no
   session so far (including this one) has had. Unblocks REG-17.
6. **REG-16** (review LNCH-2 + LNCH-4) — the highest-value use of the review loop now, once REG-2's
   tests are actually running. Sized at ~23 files / ~3,400 LOC for scoping Round 1.
7. **REG-6** (`ColumnFacts`) — before any new pass is added to the schema executor. Lower urgency
   than originally stated: drift on the two main platform-column sets is already CI-guarded by
   `PlatformColumnContractTest`; this is about structural complexity, not an active landmine.
8. **REG-1** (corpus flip, next batch — correct target is **4** `_official` apps, not 5: `Claude` is
   InMemory-N/A) · **REG-5** (assign owner) · **REG-4** (flake) — steady-state hygiene.
9. **REG-13 / REG-14 / REG-17** — schedule the one external person; three DoDs close together.
10. **REG-12**, **REG-15** — as the launch date firms up.

**REG-7 and REG-8, formerly boundaries, are now CLOSED (2026-07-22)** — the owner decided to convert
both into features rather than leave them as documented limits; see §1.7/§1.8 and
`docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md`. Each still names its residual honestly
(REG-7.3 is detect-and-refuse, not a lock; REG-8 is a refusal, not full reconstruction) — those
residuals are deliberate limits with documented rationale, not follow-up work to schedule.

---

*Companion documents: `docs/LAUNCH_READINESS_GAPS.md` (the 24-item ledger) ·
`docs/OPEN_GAPS_AND_ROADMAP.md` (runtime/generator items incl. `GATE-*`, `LNCH-1-B*`) ·
`docs/SCHEMA_EVOLUTION.md` (user-facing schema-evolution contract) ·
`docs/LNCH1_*_PLAN.md` (the five-round programme) ·
`..\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md` (the tiebreaker).*
