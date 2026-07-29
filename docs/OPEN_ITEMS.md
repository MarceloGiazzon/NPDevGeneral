# Open Items — generated

> **GENERATED FILE — do not hand-edit.** Source: `ledger/items/*.yml`, the authoritative
> record for every tracked id. Regenerate with `python scripts/quality/generate_open_items.py`.
> See `ledger/README.md` for the schema. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` is archived-in-
> place (its prose investigation narrative, linked from each item's `legacyDetailRef`) and is
> no longer hand-edited for status.

**64 item(s) migrated: 4 open/partial, 60 done.**

| ID | Title | Type | Sev | Status | Opened |
|---|---|---|---|---|---|
| REG-1 | 9 app definitions remain on the deprecated blanket destructive posture (down from 27) | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-10 | LNCH-19: Linux CI observed green for the first time | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-11 | LNCH-20: cross-platform build scripts (gradlew.bat literals, portable cache dir) | GAP | LOW | DONE | 2026-07-21 |
| REG-12 | LNCH-10: Excel/PDF/print export beyond CSV -- all 3 slices shipped | GAP | HIGH | DONE | 2026-07-21 |
| REG-13 | LNCH-18: non-author usability test (ADR-0006 DoD) run for the first time | GAP | HIGH | DONE | 2026-07-21 |
| REG-14 | LNCH-22: newcomer documentation test run for the first time | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-15 | LNCH-23: trademark clearance N/A, release tag cut | PROCESS | LOW | DONE | 2026-07-21 |
| REG-16 | The other 23 launch items had zero adversarial review | PROCESS | HIGH | DONE | 2026-07-21 |
| REG-16-resid | Adversarial review of the other ~21 launch surfaces (6-round programme) | PROCESS | HIGH | DONE | 2026-07-24 |
| REG-17 | No third party had ever reproduced any verification | PROCESS | MEDIUM | DONE | 2026-07-21 |
| REG-18 | Login timing side-channel enables username enumeration | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-19 | LoginThrottle.windowsByKey unbounded -- memory-exhaustion DoS via unique-username spray | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-2 | IT-EXTPG-1: 10 integration tests unrunnable; root cause re-opened then found | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-20 | No defense against password-spraying (limiter was per-(tenant,username) only) | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-21 | password-reset/request endpoint unthrottled (email-bomb / token-row spam) | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-22 | ActuatorAdminGuardFilter trusted a JWT claim-role without live re-resolution or tokenVersion check | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-23 | tv-less (tokenVersion-less) JWTs are never revocation-checked, by backward-compat design | GAP | LOW | DONE | 2026-07-21 |
| REG-24 | "default" tenant sentinel collides with a real tenant literally named default | GAP | LOW | DONE | 2026-07-21 |
| REG-25 | Tenant match was case-sensitive -- isolation-bucket fragmentation (not a cross-tenant bypass) | BUG | LOW | DONE | 2026-07-21 |
| REG-26 | Granular JWT error codes disclose why a token failed validation (informational, WONTFIX) | GAP | LOW | DONE | 2026-07-21 |
| REG-27 | REG-8 Trigger C false-negative for a fresh-installed build (rollback silently re-added a dropped column) | BUG | MEDIUM | DONE | 2026-07-22 |
| REG-28 | Stale mark-done fast-forward (REG-7.2): a leftover mark could authorize an unrelated future boot | BUG | MEDIUM | DONE | 2026-07-22 |
| REG-29 | Claim-release-on-refusal was correct but untested (migration collision claim) | BUG | LOW | DONE | 2026-07-22 |
| REG-3 | GATE-REL-1: node_modules/slimness conflict was already fixed; the real gap was stale evidence reports | GAP | LOW | DONE | 2026-07-21 |
| REG-30 | Duplicate mark-done rows each survive one consume, letting a second future boot fast-forward | BUG | LOW | DONE | 2026-07-22 |
| REG-31 | run-script-automation-quality's structured-report-contract check was mis-calibrated (helper-name grep, not a behavior test) | PROCESS | LOW | DONE | 2026-07-24 |
| REG-32 | npdev-ci-validation.yml Bootstrap step aggregated ~21 maturity reports its producers never generated | PROCESS | MEDIUM | DONE | 2026-07-24 |
| REG-33 | CLI's on-demand npm install for the JSON-schema validator failed on Windows from a Python subprocess | BUG | LOW | DONE | 2026-07-24 |
| REG-34 | Windows CI job runs Testcontainers (Linux-container) tests that windows-latest can't run | PROCESS | LOW | PARTIAL | 2026-07-24 |
| REG-35 | Gradle-native postBeta0MaturityCheck has the same missing-vs-invalid conflation REG-32 fixed in PowerShell, plus an overly strict nested artifact schema | PROCESS | LOW | OPEN | 2026-07-24 |
| REG-36 | Oversized idempotency keys could exceed the Postgres btree index-entry size limit | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-37 | Circuit-breaker failure-count read-decide-write was not a single atomic critical section | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-38 | Additive-migration constraints were not idempotent on H2 -- redeploy failed with duplicate constraint | BUG | MEDIUM | DONE | 2026-07-24 |
| REG-39 | Stale built-in identity pack copy caused a silent, unhelpful auth failure -- fixed platform-wide | BUG | HIGH | DONE | 2026-07-24 |
| REG-4 | T-F1: load-sensitive SandboxedPluginExecutionEngine test flake, root cause fixed | BUG | LOW | DONE | 2026-07-21 |
| REG-40 | Additive migration never emitted CREATE TABLE -- a new concept on an existing DB failed to boot | BUG | MEDIUM | DONE | 2026-07-24 |
| REG-41 | DefaultConceptGateway.save() leaked a row's lifecycle status to an unauthorized caller before authz ran | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-42 | ConceptGateway.query() leaked a row-scoped count through total/hasMore pagination metadata | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-43 | TenantRegistryService.isActive silently fail-opened on any read failure, with no log at any level | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-44 | crud.kernelControlled=false silently removed ALL coarse permission/audit checks, not just access.write | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-45 | Flow resume was tenant-scoped but not actor-scoped -- any same-tenant user could resume another's flow | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-46 | Persistence capability port had no tenant parameter -- flow-step persistence writes were unscoped | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-47 | Correlation ids had no length cap -- an oversized caller-chosen id could hit the same btree limit as REG-36 | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-48 | DefaultConceptGateway.delete() had the same authz-after-invariant-eval ordering bug REG-41 fixed in save() | BUG | HIGH | DONE | 2026-07-27 |
| REG-49 | M1-SEC-GENCODE finding withdrawn as a false positive -- the reviewed pack was stale, not the platform | BUG | LOW | DONE | 2026-07-27 |
| REG-5 | GATE-OBS-1a: surface-governance drift checks were advisory and unowned | PROCESS | LOW | DONE | 2026-07-21 |
| REG-50 | PostgresPersistenceCapabilityAdapter fell back to UNSCOPED reads/writes on a transient metadata-read failure | BUG | HIGH | DONE | 2026-07-27 |
| REG-51 | External-AI review packs sliced from generated code carried no provenance -- exactly how REG-49 became a false positive | BUG | HIGH | DONE | 2026-07-27 |
| REG-52 | TenantIsolationPolicy.STRICT_EQUALS normalize() only trimmed, never lowercased -- inconsistent with ExecutionContext | BUG | MEDIUM | DONE | 2026-07-27 |
| REG-53 | SqlTypeSupport hardcoded VARCHAR(255) for every string/enum field, ignoring a declared maxLength | BUG | HIGH | DONE | 2026-07-27 |
| REG-54 | Two dead private methods left behind by the T2.B.4 SchemaLifecycleExecutor split | GAP | LOW | DONE | 2026-07-27 |
| REG-55 | Sandboxed plugin overload resolution matched by arg count only, not type -- "Ambiguous" false positive | BUG | MEDIUM | DONE | 2026-07-27 |
| REG-56 | Flow resume rebuilds ExecutionContext with the wrong actor/role, three different wrong ways | BUG | HIGH | DONE | 2026-07-28 |
| REG-57 | H2's default 500ms WRITE_DELAY can lose committed flow-instance checkpoints across a hard kill | BUG | HIGH | DONE | 2026-07-28 |
| REG-58 | Narrow-type DROP COLUMN crashed mid-migration on WmsOffice's real database -- composite unique index not dropped first | BUG | HIGH | DONE | 2026-07-28 |
| REG-59 | WmsOffice live database recovery record (REG-58 fix re-verification) -- recovery only, NOT the platform gap | GAP | MEDIUM | DONE | 2026-07-28 |
| REG-6 | ColumnFacts: eight SchemaLifecycleExecutor passes each re-derived column semantics independently | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-60 | Aggregate Workbench post-commit "Saved." confirmation is wiped by the next render before a user can see it | BUG | LOW | DONE | 2026-07-28 |
| REG-61 | Narrow-type recreate loses NOT NULL; no per-row-unique default expressible for a required UNIQUE column | GAP | HIGH | DONE | 2026-07-28 |
| REG-62 | allowedActions is an untyped, unvalidated CSV-in-metadata escape hatch -- a typo silently drops an action-rail button | GAP | LOW | OPEN | 2026-07-28 |
| REG-63 | AuxScreen and Pigmentampa's model.json use a pre-DSL-stabilization flow-step shape the current schema rejects | GAP | MEDIUM | OPEN | 2026-07-29 |
| REG-7 | LNCH-1-B6: no migration advisory lock (multi-instance) -- converted to a feature | BOUNDARY | — | DONE | 2026-07-21 |
| REG-8 | LNCH-1-B9: schema-ahead detector blind to a pure column drop on rollback | BOUNDARY | — | DONE | 2026-07-21 |
| REG-9 | LNCH-4: auth secrets management -- JWT key env-var delivery | GAP | HIGH | DONE | 2026-07-21 |

## Detail

### REG-1 — 9 app definitions remain on the deprecated blanket destructive posture (down from 27)

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `appgen/schema-lifecycle-policy`

A repo-wide audit found 18 unreferenced sample-app definitions still on the deprecated
DropAndRecreateOnStructureChange blanket posture; moved to an outside-repo archive (recoverable),
shrinking the tracked pool from 38 to 20. All 7 remaining flip-worthy apps (4 AppGen _official +
invoice-bonds-demo + 2 NPDevSamples) flipped to KeepExistingIfCompatible +
allowDestructiveRecreate:false. Verified per app: clean regeneration, manifest carries the new
lifecycle, and a live additive-change proof on superuser-admin-console showed the boot log
correctly skipping destructive recreation. 2 apps (lnch1-rehearsal,
simple-user-registry-h2local-freshdb) are deliberately kept on blanket by documented design.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-1`

### REG-10 — LNCH-19: Linux CI observed green for the first time

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `ci/pr-gate`

Every prior quality claim had run only on one Windows machine; nobody had watched a real GitHub
Actions run go green. npdev-pr-gate.yml ran green on ubuntu-latest (run 29899362276, commit
3dcc51e) -- every step success: DSL contract, kernel inproc adapters, all 168 generator unit tests
(incl. 3 packaged-app boot/HTTP/JDBC proof tests), RuntimeHost libs sync, sample generation, and the
RuntimeHost generated-app suite. Took six root-caused fixes across seven runs: a hardcoded Windows
pwsh.exe path, NPDEV_BUILD_ROOT disagreement (repo checked out as "NPDevGeneral" without the
underscore), a real product portability bug (every generated FinalApp inherited a hardcoded
D:/WorkSpace/NPDev/Build gradle projectcachedir -- see REG-11), a hand-maintained adapter list
missing mail-inproc/mail-smtp, missing CI diagnostics, and a ".." in an artifact upload path.
Caveat noted at closure: the green run was on an older branch line; confirming green on the latest
line was a scheduled follow-up.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-10`

### REG-11 — LNCH-20: cross-platform build scripts (gradlew.bat literals, portable cache dir)

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, corrected 2026-07-21
**Surface:** `scripts/cross-platform`

13 scripts (18 occurrences) hardcoded gradlew.bat instead of resolving per-OS; a working helper
(Get-NPDevGradleWrapperExecutable in scripts/npdev-common.ps1) already existed but these call sites
hadn't been migrated to it -- mechanical work, not new plumbing. A separate repo-wide D:\ literal
sweep of the scoped files found zero matches (that part of the original claim didn't hold).
Migrated all genuine gradlew.bat call sites to the shared helper. Closed/proven via REG-10's green
Linux CI run, which additionally exposed and fixed a real cross-platform DISTRIBUTION bug this
item's "code-complete" state had missed: every generated FinalApp shipped
NPDevRuntimeHost/gradle.properties's hardcoded org.gradle.projectcachedir=D:/WorkSpace/NPDev/Build/...,
copied verbatim by FinalAppAssembler, so a generated app's own gradlew bootJar could not run on any
machine but the original dev box. Removed from the template so generated apps use gradle's portable
default cache.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-11`

### REG-12 — LNCH-10: Excel/PDF/print export beyond CSV -- all 3 slices shipped

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `generator/export-pdf`

Slice 1 (streaming CSV export) pre-existed. Slice 2 (print stylesheet/render mode): a "Print" button
on every declared panel builds a self-contained #printRoot document and calls window.print(); a new
@media print block hides app chrome. Verified live via real-browser ScrapForAI. Found+fixed a
pre-existing unrelated bug during this verification: an InMemory-storage app's promotion panel
retried a 503'ing endpoint in an unbounded render loop; fixed with an "attempted" guard flag. Slice
3 (server-side PDF): a new declarative `document` DSL kind bound to a concept's query, a
DocumentRenderContract kernel port with a pure-JVM openhtmltopdf adapter (proven headless-safe by a
spike) plus an honest no-op stub adapter, a DocumentRenderController mirroring the CSV export
controller's discipline, and a "Download PDF" toolbar link. Verified live: a real PDF streamed with
exact title/timestamp/row/column/footer content confirmed via PDFBox text extraction. Found+fixed 3
real bugs during wiring: two model-reconstruction call sites silently dropping the new `documents`
field (and, at one of them, also pre-existing dropped guidePages/aggregates/autoPanels for
pack-composing apps), a static controller allowlist silently 404ing the new endpoint, and a missing
Gradle dependency declaration causing NoClassDefFoundError. Verified green on real Linux GitHub
Actions (run 29943008077), not just Windows.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-12`

### REG-13 — LNCH-18: non-author usability test (ADR-0006 DoD) run for the first time

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `process/external-validation`

ADR-0006 ratified AI-first authoring but its own Definition of Done -- a real, external, non-author
person taking an app from description to running FinalApp -- had never been exercised; every app the
platform had produced was built by the owner or a supervised AI. Closed by running the DoD via a
genuinely independent tester: a subagent given ONLY a cold-start brief, a fresh context window, and
its own isolated git worktree -- no access to this project's plans/register/history, no mid-run
coaching. It authored the brief's issue-tracker app using the documented CLI validator fallback (no
MCP tools registered) and verified all four CRUD operations unaided over REST. Pass bar met on the
first cold run. Real finding filed (not silently fixed): the user manual's own createConcept/
updateConcept examples omit the persistence capability/binding block, producing a model that
validates cleanly but 500s at runtime with no diagnostic naming the real cause.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-13`

### REG-14 — LNCH-22: newcomer documentation test run for the first time

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `process/external-validation`

The DoD -- a newcomer building the tutorial app from docs alone -- had never been exercised. Closed
by the same 2026-07-22 independent-tester run that closed REG-13: it built
NPDevSamples/simple-contact-intake from docs/TUTORIAL_FIRST_APP.md alone (docs only, no MCP tools or
CLI validator used to fill gaps), and verified it booted and worked (both the tutorial's create
example and its invariant-failure example). Pass bar met on the first cold run. Real, dated findings
filed even though the pass bar was met: the tutorial's literal `gradlew.bat bootJar` command fails
on an undocumented RuntimeHost-libs staging prerequisite whose own suggested fix also fails
standalone in a fresh worktree; the doc's claimed 400 status for an invariant violation is actually
422.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-14`

### REG-15 — LNCH-23: trademark clearance N/A, release tag cut

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-23)
**Verification:** NOT_VERIFIED
**Source:** LNCH-1 programme inheritance
**Surface:** `process/release`

Release tag beta1.1 (annotated) cut 2026-07-22 on the beta1-vision-spine -> main merge commit
3e29cca; run-release-checklist-gate.ps1 no longer lacks a tag. Trademark clearance: owner's final
decision (2026-07-23) is N/A -- this is an individual, non-commercial hobby/portfolio project with
no mark to defend and no trademark sought, so there is nothing to clear and nothing to park; the
item is complete, not deferred. Two preliminary name-collision findings on file ("NP DEV Soluções em
T.I.", NPDEV LIMITED UK #14176093) are informational only and block nothing.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-15`

### REG-16 — The other 23 launch items had zero adversarial review

**Type:** PROCESS · **Severity:** HIGH · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme, Tier A/B closed 2026-07-21; residual programme closed 2026-07-25
**Surface:** `process/adversarial-review`

LNCH-1 had absorbed five full review->plan->implement->review rounds; every other item in the
ledger (LNCH-2 tenant isolation, LNCH-4 auth, LNCH-13 row-level authz, ~23 other launch items) had
had none. Tier A (independent attack-first review of the LNCH-2+4 surface, ~23 files/~3,400 LOC):
headline no CRITICAL or HIGH finding -- tenant isolation is genuinely defense-in-depth. Residual 5
MEDIUM + 3 LOW + 1 INFO filed as REG-18..REG-26. Tier B (fixing those) also done same day. The
remaining ~21 launch surfaces (generator codegen, kernel FlowEngine/KernelRunner, LNCH-13
row-level authz, export/PDF, etc.) were tracked as a residual programme, REG-16-resid, rather than
reopening this item's scope -- that programme finished all six rounds 2026-07-25, closing this
item fully: no launch surface is left at zero adversarial review.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-16`

### REG-16-resid — Adversarial review of the other ~21 launch surfaces (6-round programme)

**Type:** PROCESS · **Severity:** HIGH · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** docs/archive/programme-history/POST_REG17_CLOSURE_PLAN.md Task 4, reusing the REG-16 template
**Surface:** `process/adversarial-review`

REG-16's original Tier-A review covered only LNCH-2 (tenant isolation) + LNCH-4 (auth); the other
~21 launch surfaces had never had an attack-first review. Six rounds, all complete 2026-07-25:
Round 1 (kernel execution path: KernelRunner/dispatcher/resilience mechanisms) -- no CRITICAL/HIGH,
2 MEDIUM filed as REG-36/REG-37. Round 2 (LNCH-13 row-level authz, kernel gateway + generated CRUD)
-- one CRITICAL found and fixed same round: a concept with a custom create/update/delete Flow got
ZERO row-level access.write enforcement on its generated endpoint (bypassed conceptGateway entirely);
fixed in service-base.mustache; 2 MEDIUM residual filed as REG-41/REG-42. Round 3 (generator codegen
OUTPUT) -- one HIGH found and fixed: every many-to-many bond emitted 4 HTTP endpoints with NO
authorization at all; fixed via a new ConceptGateway.authorizeWrite with a deliberately deny-by-
default; also fixed an XSS sink in the generated business UI; 1 MEDIUM residual filed as REG-44.
Round 4 (flow/await orchestration) -- no CRITICAL/HIGH; identity does not survive suspension, so no
confused-deputy authority to steal; 1 MEDIUM residual filed as REG-45. Round 5 (durable-state
adapters' own SQL, all *-postgres adapters) -- no CRITICAL/HIGH, ZERO SQL-injection findings (every
value bound, every identifier whitelisted); 1 MEDIUM residual filed as REG-46. Round 6 (export/PDF
path) -- no CRITICAL/HIGH, three findings all fixed in-round: CSV formula injection, an unbounded-
memory PDF export DoS, and an SSRF-capable PDF renderer with no URI policy (not reachable today,
fixed anyway). Net: 4 surfaces that stood at zero adversarial review now each have their own scope
list and findings document; every round's residual MEDIUM findings are individually tracked, not
silently dropped.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-16-resid`

### REG-17 — No third party had ever reproduced any verification

**Type:** PROCESS · **Severity:** MEDIUM · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme; multi-round CI reproduction effort
**Surface:** `ci/external-reproduction`

Every green suite/gate/rehearsal had been produced on one machine by the owner or a supervised AI,
never independently exercised. Closed via ~11 root-caused CI fixes across ~9 rounds: profile/config
fixes, a JDBC-capable sample for the Postgres integration tests (Fix A), surface-evidence advisory
wiring, npm ENOENT on Windows (REG-33), a deterministic runtimehost-libs sync, @DisabledOnOs on a
Linux-container test Windows can't run (REG-34), and an editor-E2E static-host path fix. Result:
npdev-ci-validation.yml (both the Linux maturity job and the Windows segmented job) runs green
end-to-end on GitHub-hosted runners from a clean checkout -- automated external reproduction on
hardware this project had never touched, the mechanism REG-17's DoD actually names. Owner's final
call (2026-07-27): the automated-repro + blind-AI-operator combination already achieved satisfies
REG-17's DoD intent; no further literal-human run is required to consider it closed. Residual,
filed not fixed: a genuinely clean container's anonymous `git clone` got a 404/credential prompt --
the repo was private at the time, so an uninvited third party could not have cloned it regardless of
CI's green status (repo visibility is an owner call, not an AI decision).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-17`

### REG-18 — Login timing side-channel enables username enumeration

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

PasswordHasher.verifyDecoy now runs a real PBKDF2 against a fixed decoy hash on both the
no-user and no-credential login paths, so response timing no longer discloses whether a username
exists. RED-first PasswordHasherDecoyTest.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-18`

### REG-19 — LoginThrottle.windowsByKey unbounded -- memory-exhaustion DoS via unique-username spray

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Hard cap (100k) added with expired-first + oldest-live eviction and a cutoff tie-break. RED-first
LoginThrottleBoundedTest sprays 3x the cap.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-19`

### REG-2 — IT-EXTPG-1: 10 integration tests unrunnable; root cause re-opened then found

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `runtimehost/integration-tests`

10 integrationTest classes (JwtAuthExternalBetaIT x8, PublicationRollbackE2EIT,
TenantIsolationE2EIT) failed with ApplicationContext load errors. Two prior theories (Testcontainers
Postgres profile config, a missing DataSource bean) were independently re-checked and found not to
hold up. The real cause was a THIRD mechanism: DatabaseIdentityStartupValidator aborting because
Testcontainers' jdbc:tc: DB always reports name "test", which never matches the app's resolved
identity. Fixed at the profile level (application-postgres.yml ->
npdev.trial.database-override:true). Running the suite surfaced two more real bugs, both fixed: a
text=uuid cast in PublicationRollbackE2EIT, and LoginController crashing verify-only JWT (fixed
under REG-9). 10/10 green on real Postgres.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-2`

### REG-20 — No defense against password-spraying (limiter was per-(tenant,username) only)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Added a per-source-IP arm to LoginThrottle (default 50/window vs 10/username), wired the client IP
through LoginController; a success clears the username window but not the IP window. RED-first
LoginThrottleIpSprayTest.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-20`

### REG-21 — password-reset/request endpoint unthrottled (email-bomb / token-row spam)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

PasswordResetController reuses the same limiter as login (5/user, 20/IP); over-limit returns the
same generic 200 but sends no email and creates no token. RED-first: the 6th request sends no email.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-21`

### REG-22 — ActuatorAdminGuardFilter trusted a JWT claim-role without live re-resolution or tokenVersion check

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

SuperUserCredentialAuthFilter now sets a marker only after a live super-key resolves ACTIVE; the
actuator gate requires that marker, so a JWT-borne (or revoked) SUPERUSER role no longer opens
metrics. RED-first: a role-only claim now 403s.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-22`

### REG-23 — tv-less (tokenVersion-less) JWTs are never revocation-checked, by backward-compat design

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Owner decision: config-driven cutover rather than an immediate hard break. The revocation decision
is centralized in IdentityRoleLookup.isTokenRevoked, the single point both claim-to-context paths
call, so they cannot diverge. New config npdev.auth.jwt.reject-tokens-without-tv-after (ISO-8601
instant, default off = today's lenient behavior); once reached, tv-less tokens are rejected on both
paths. Bridged Spring->system-property by TvlessTokenCutoverBridge (fails fast on a malformed
value). Verified: 4/4 IdentityRoleLookupTvlessRevocationTest.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-23`

### REG-24 — "default" tenant sentinel collides with a real tenant literally named default

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Verified already comprehensively guarded, no change needed. All three tenant-insert paths already
reserve "default": TenantRegistryService.create rejects it, IdentityProvisioning
.ensureTenantRegistered skips it, TenantAutoRegistrationRunner's SQL excludes it. No real "default"
tenant can ever be created, so the isolation collision this finding worried about cannot arise.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-24`

### REG-25 — Tenant match was case-sensitive -- isolation-bucket fragmentation (not a cross-tenant bypass)

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

The core write-path normalizers only trimmed tenant_id (did not lowercase); only peripheral sites
lowercased -- so business data could land under "Acme" while the registry stored "acme". Fix:
canonicalize tenant_id to lowercase at the single choke point every read/write derives its tenant
from -- ExecutionContext's compact constructor (actorId stays case-sensitive; the reserved "default"
sentinel unaffected). Proven RED->GREEN via ExecutionContextTenantCanonicalizationTest; full
RuntimeHost gate green. Existing-data fix: scripts/ops/canonicalize-tenant-ids.ps1 (dry-run
default, -Apply, -Force) lowercases tenant_id across the registry + every business table, with a
collision detector that skips + reports merge-risk tables unless -Force. Proven end-to-end on a
seeded H2 DB.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-25`

### REG-26 — Granular JWT error codes disclose why a token failed validation (informational, WONTFIX)

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** NOT_VERIFIED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md), severity INFO in the original register (this schema has no INFO level; mapped to LOW)
**Surface:** `runtimehost/auth`

WONTFIX. Standard practice; the error codes name the validation reason (expired / bad issuer / bad
signature), not any secret or account state, and materially aid operator/integration debugging.
Collapsing to a single generic error would trade real diagnosability for negligible disclosure
reduction.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-26`

### REG-27 — REG-8 Trigger C false-negative for a fresh-installed build (rollback silently re-added a dropped column)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** UNIT_TESTED
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

Trigger C (databaseMigratedPastThisBuild) only fired if the rolled-back-to build's fingerprint had
a PRIOR APPLIED/MANUALLY_MARKED_DONE row in npdev_schema_history. A build reached by FRESH INSTALL
never had one (the blank-fingerprint boot writes no history row), so the register's own canonical
example -- fresh-installed build N, N+1 drops a column, roll back to N -- was NOT actually refused;
the dropped column was silently re-added empty. The headline test had only passed because it
hand-seeded a history row a real fresh install never writes. Fix: afterMigrate now records the
initial realization as an APPLIED history point on the fresh-install path too, so every fingerprint
the DB has genuinely been at is visible to Trigger C. RED-first: two new tests in
SchemaLifecycleExecutorDatabaseMigratedPastBuildTest (a direct fresh-install-records-history
assertion, and the honest end-to-end with no hand-seeded row).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-27`

### REG-28 — Stale mark-done fast-forward (REG-7.2): a leftover mark could authorize an unrelated future boot

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

MigrationMarkStore recorded only the target fingerprint, no from-fingerprint binding and no TTL. A
leftover mark for X (a deploy planned then abandoned) would silently authorize the first future
boot whose target was X, from whatever the DB actually was at, fast-forwarding with zero
migration/classify/Trigger-C passes. Fix: MigrationMarkStore now binds every mark to a
(from_fingerprint, marked_fingerprint) pair; findMatching only returns a mark when the boot's own
live stored fingerprint equals the recorded "from". SchemaAcknowledgmentController#markDone takes
fromFingerprint/toFingerprint. RED-first SchemaLifecycleExecutorMigrationMarkTest proves a mark
recorded for from=A does not fire when live-stored is Z, and does fire when live-stored is A.
Verified live: real boot rehearsal against superuser-admin-console confirmed both the non-firing and
firing cases.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-28`

### REG-29 — Claim-release-on-refusal was correct but untested (migration collision claim)

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-22)
**Verification:** UNIT_TESTED
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

The production `finally` in migrate() does release the boot's own claim on a refusal thrown from
inside the migration body (Trigger C, destructive-without-token) -- verified correct by reading, but
no test proved it: the existing "refuses" test failed at claim ACQUISITION (a PK collision), where
the boot never held a claim in the first place -- the wedge-risk property that matters most was
unverified. No production change needed. Added
refusalWhileHoldingOwnClaimStillReleasesIt to SchemaLifecycleExecutorMigrationClaimTest: seeds
Trigger C's canonical shape so beforeMigrate throws from inside migrate's try block AFTER this
boot's own claim was acquired, asserts the throw and that the claim store is empty afterward.
RED-first: verified the test fails when the finally's release is neutralized, passes with the real
code.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-29`

### REG-3 — GATE-REL-1: node_modules/slimness conflict was already fixed; the real gap was stale evidence reports

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `quality-gates/release-gate`

Original claim: run-beta-release-gate.ps1 structurally cannot pass because json-schema-validator's
node_modules can't be committed under the workspace slimness policy. Independent verification found
this premise stale -- commit 437d19b (2026-05-14, two months before this register) already moved
that runtime outside the repo, and Test-WorkspaceSlimness.ps1 never scans that external location.
There was no conflict. The gate DID exit 1 (35 of 36 required evidence reports missing), but the
real cause is a report-orchestration/staleness gap: the constituent evidence-generating scripts
simply hadn't been run recently. Fix: added run-beta-release-evidence-orchestration.ps1 (runs all
~18 producers in dependency order sharing one runId) + opt-in -GenerateReports; the gate now
distinguishes precondition-unmet (exit 2) from check-failed (exit 1). Found and fixed a producer
that could only ever emit passing evidence, plus its stale fixture that had silently disabled the
model-root additionalProperties guard.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-3`

### REG-30 — Duplicate mark-done rows each survive one consume, letting a second future boot fast-forward

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

Two marks for the same fingerprint: consume() deleted only the matched row, so the older duplicate
survived to fast-forward a second future boot at that fingerprint. Folded into the REG-28 fix: a
unique index on (from_fingerprint, marked_fingerprint) rejects a duplicate mark for the identical
transition at insert time. Verified live against superuser-admin-console: re-POSTing an identical
(from, to) pair via the real ControlPanel API returned 500 and GET /marks still showed exactly one
row. Unit coverage: duplicateMarkForTheSameTransitionIsRejected.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-30`

### REG-31 — run-script-automation-quality's structured-report-contract check was mis-calibrated (helper-name grep, not a behavior test)

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** Quality-gate calibration review, 2026-07-24
**Surface:** `quality-gates/script-automation-quality`

The check greped script SOURCE for the literal helper names Invoke-NPDevReportedCommand/
Write-NPDevJsonFile and failed any of ~68 scripts lacking them -- flagging 59, a helper-name
presence test, not a report-behavior test. Spot-checked 9 of the 59: 56 of 59 persist a genuinely
valid structured JSON report by other means (direct ConvertTo-Json | Set-Content to the standard
report-path convention). Only 3 were genuinely non-compliant. Fix: the sub-check now tests actual
behavior (serializes to JSON AND persists it AND targets the standard report-path convention, by
ANY mechanism). The 3 genuinely non-compliant scripts excluded via a dated backlog list, not
silently dropped and not mass-migrated. Verified locally: exits 0, 65/65 scoped scripts pass (was
9/68). CI's continue-on-error removed -- blocking again.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-31`

### REG-32 — npdev-ci-validation.yml Bootstrap step aggregated ~21 maturity reports its producers never generated

**Type:** PROCESS · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** CI evidence-orchestration review, 2026-07-24
**Surface:** `ci/maturity-bootstrap`

Closed for the PowerShell bootstrap chain; residual Gradle-native gap filed separately as REG-35.
The Linux job's bootstrap step AGGREGATES ~21 maturity reports and hard-fails if any are missing or
schema-invalid, but does not GENERATE them -- ~19 were precondition-unmet (producers never run),
plus one genuinely schema-invalid report. Fix (both halves): (1) bootstrap-post-beta0-reports.ps1,
validate-report-schemas.ps1, and generate-final-evidence-bundle.ps1 now distinguish
precondition-unmet (exit 2, non-fatal) from check-failed (exit 1); also fixed a real bug in
validate-report-schemas.ps1 conflating "never produced" with "produced but wrong". (2) Fixed the
one real schema-invalid report (stateful-additive-migrations-report.json): two real defects, a
wrong directory-walk depth in the XML resolver, and a schema requiring const:true on 8 fields a
prior fix had deliberately retired to false. Verified: errorCount 0 (was 10). CI step's
continue-on-error kept intentionally (not removed) since REG-35's Gradle-native residual still
trips on the same tree.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-32`

### REG-33 — CLI's on-demand npm install for the JSON-schema validator failed on Windows from a Python subprocess

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** CI Windows diagnostic capture, 2026-07-24
**Surface:** `cli/json-schema-validator`

`npm --prefix <validator> install` run with cwd=repo-root makes npm read package.json from cwd (the
repo root has none) -> ENOENT on the CI Windows npm; --prefix only sets where node_modules lands,
not where npm reads the manifest. Fix: npdev_cli.py now runs npm install with
cwd=validator_root (no --prefix). Verified locally RED->GREEN: removed node_modules, ran
`npdev migrate`, install ran from the validator dir, exit 0. CI also pre-installs the deps in the
Windows job as belt-and-suspenders.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-33`

### REG-34 — Windows CI job runs Testcontainers (Linux-container) tests that windows-latest can't run

**Type:** PROCESS · **Severity:** LOW · **Status:** PARTIAL
**Verification:** VERIFIED_LIVE
**Source:** Surfaced by REG-17 once Fix A + REG-33 unblocked the Windows job's downstream gates
**Surface:** `ci/windows-job-scoping`

npdev-ci-validation.yml's Windows job runs full gate suites including Testcontainers tests that
start LINUX containers (MinIO, Postgres); windows-latest cannot run Linux containers at all
(Windows-container-only), so those tests fail on Windows while passing on the green Linux job.
Approach: disable each genuinely-Linux-container test on Windows via @DisabledOnOs(OS.WINDOWS)
(stays enabled and validated on Linux CI). Done so far: the generator gate's MinIO packaged-app
proof test, verified locally to skip on Windows and still build. Remaining: the Windows job's
further gates (Security hardening, Runtime security, RuntimeHost gate, Editor gate) had never run
to completion at filing time; each may contain more Linux-container tests to scope the same way,
to be iterated as they surface.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-34`

### REG-35 — Gradle-native postBeta0MaturityCheck has the same missing-vs-invalid conflation REG-32 fixed in PowerShell, plus an overly strict nested artifact schema

**Type:** PROCESS · **Severity:** LOW · **Status:** OPEN
**Verification:** VERIFIED_LIVE
**Source:** Discovered as a byproduct of verifying REG-32's fix, 2026-07-24 -- pre-existing, not caused by that work
**Surface:** `ci/maturity-bootstrap`

The same CI step also runs ./gradlew postBeta0MaturityCheck, a completely separate Gradle-native
validation pipeline that independently re-checks 7 of the same maturity reports. Two gaps neither
touched by REG-32: (1) validateReports treats a missing report file as an unconditional failure (no
precondition-unmet concept exists in this framework at all), and separately trips hard on REG-32's
new "precondition-unmet" overallStatus value -- reproduced: 6 of 7 report pairs "missing schema or
report", the 7th "precondition-unmet". (2) final-evidence-bundle-manifest.schema.json's artifacts[]
items are unconditionally required with no "never generated" escape hatch -- reproduced: a freshly
generated manifest (19/21 reports absent) produces 76 schema errors. Also noted: validateBoundaryLocks
independently fails on this tree for an unrelated, long-pre-existing reason (hardcoded drive-letter
paths), out of this finding's scope. Not fixed: build.gradle is the single highest-blast-radius
build file in the repo and extending the REG-3/REG-32 pattern into a second, structurally different
Gradle-native pipeline is its own bounded task. CI step keeps continue-on-error:true until fixed.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-35`

### REG-36 — Oversized idempotency keys could exceed the Postgres btree index-entry size limit

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 1 kernel-execution adversarial review, finding REG16K-F1
**Surface:** `kernel/idempotency`

A new IdempotencyKeys.bound(...) digests a key above 200 chars to npdev-sha256$<hex>, applied in
both stores at their key chokepoints. A naive digest would have introduced a NEW collision the
original bug didn't have (a caller submitting the literal short string "sha256(X)" would land on
X's record) -- fixed by also digesting any short key that already starts with the reserved prefix.
Correction found while building the control: the real trigger is size AFTER compression (an
oversized but highly compressible key inserts fine); verified on a real Postgres container: 8,000
incompressible chars throws, the compressible twin does not. idempotency-postgres was the ONLY
*-postgres adapter with no postgres-test-support dependency at all -- its one test ran H2 in
PostgreSQL mode, which does not enforce this limit; that gap is why the bug shipped and is now
closed (a real-Postgres PostgresIdempotencyKeyBoundTest added). Tests: IdempotencyKeysTest (6),
InProcIdempotencyStoreTest (+3).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-36`

### REG-37 — Circuit-breaker failure-count read-decide-write was not a single atomic critical section

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 1 kernel-execution adversarial review, finding REG16K-F2
**Surface:** `kernel/circuit-breaker`

The transition rule moved into a pure function CircuitBreakerTransitions.afterFailure, and the
read-decide-write is now one critical section owned by the store: ConcurrentHashMap.compute
in-proc, SELECT ... FOR UPDATE inside a transaction on JDBC. A SELECT ... FOR UPDATE cannot lock a
row that doesn't exist, so two concurrent FIRST failures would both compute 1; the JDBC path seeds
a CLOSED/zero row before locking -- that seed must NOT go through the upsert-despite-its-name
insertOrIgnore helper, which reset the counter on every call (caught by a test asserting the
counter's real value, not just its presence). RED->GREEN proven by reverting both stores to the
interface's documented non-atomic default: concurrency tests fail, deterministic lifecycle tests
still pass. Tests: InProcCircuitBreakerStateStoreTest (8 threads x 200),
JdbcCircuitBreakerStateStoreConcurrencyTest on H2, PostgresCircuitBreakerStateStoreTest on the real
engine.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-37`

### REG-38 — Additive-migration constraints were not idempotent on H2 -- redeploy failed with duplicate constraint

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** Discovered live while rebuilding WmsOffice with a new field (ARCH-upload P6)
**Surface:** `runtimehost/schema-lifecycle`

SchemaRealizationEmitter.addConstraintIfMissing wrapped Postgres's ADD CONSTRAINT in an IF NOT
EXISTS catalog guard but the H2 branch emitted a bare ADD CONSTRAINT. That statement lands in a
Flyway REPEATABLE migration that re-runs whenever its checksum changes (i.e. after any model edit),
so redeploying a changed model against an existing H2 DB failed at boot with "Constraint already
exists" and refused the whole application. Fix: the H2 branch now emits
ALTER TABLE ... DROP CONSTRAINT IF EXISTS <name> before the ADD (both verbs supported on H2 and
Postgres; Postgres path unchanged). RED->GREEN SchemaRealizationEmitterAdditiveColumnsTest; verified
live -- WmsOffice now boots cleanly against the same existing DB that previously refused.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-38`

### REG-39 — Stale built-in identity pack copy caused a silent, unhelpful auth failure -- fixed platform-wide

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** WmsOffice app-scope incident, generalized to a platform hazard
**Surface:** `runtimehost/identity-pack`

An app carrying a stale built-in-pack copy (missing a field like tokenVersion the pack now
declares) would boot then fail auth with a generic error, not a diagnosable one. Fixed platform-wide
via three layers: (1) detect -- StartupValidator.validateIdentityPackFreshness fails fast at boot
naming the pack/concept/missing-field/fix; (2) stop swallowing -- the 4 real SQL touchpoints reading/
writing token_version now distinguish a genuine schema-mismatch SQLException from a routine
negative via a new IdentityPackSchemaException, so a missing column produces a distinct diagnosable
error instead of a generic auth failure; (3) surface pre-deploy -- the same drift check folds a
synthetic NEEDS_HOOK item into the Impact Report, so -ImpactOnly / ControlPanel report
NEEDS_ATTENTION for a stale pack copy without needing a boot. Verified: a live proof stripped
tokenVersion from the platform's own identity pack, regenerated an app against a virgin DB, booted
-- reproduced the exact intended failure naming pack/concept/field/fix; clean revert + reboot
confirmed no false-positive on a healthy pack. A full rebuild-app run against WmsOffice confirmed no
regression on the originally-affected app.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-39`

### REG-4 — T-F1: load-sensitive SandboxedPluginExecutionEngine test flake, root cause fixed

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `kernel/plugin-execution`

SandboxedPluginExecutionEngineTest failed roughly 1 in 5 runs under parallel load, 0 in 5 in
isolation. Reproduced the flake DETERMINISTICALLY with a new test
(timeoutIsNotCorruptedByAPreExistingCallerInterrupt, RED 100%) instead of waiting for a suite-load
reoccurrence. Root cause: future.get(timeout) runs on the calling thread, and a stray interrupt left
by a prior test on the same worker thread made it throw InterruptedException before the timeout.
Fixed in SandboxedPluginExecutionEngine.execute (read-and-clear a stray caller interrupt around the
bounded get(), re-assert it after) -- an engine robustness fix, not a tolerance widening. Removed
@Tag("load-sensitive") from timesOutSlowPluginExecution; 6/6 green live.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-4`

### REG-40 — Additive migration never emitted CREATE TABLE -- a new concept on an existing DB failed to boot

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** Schema-engine rebuild plan Part II fast track
**Surface:** `runtimehost/schema-lifecycle`

The additive/repeatable migration only ever emitted ALTER TABLE ... ADD COLUMN/ADD CONSTRAINT (zero
CREATE TABLE), so a new concept/table added to a model then redeployed against an EXISTING DB failed
at boot with "Table not found" (the versioned V1 CREATE-TABLE migration had already run and doesn't
re-run). Fix: SchemaRealizationEmitter's R__ assembly now emits, in order, (1) CREATE TABLE IF NOT
EXISTS for every business + junction table, (2) additive ADD COLUMNs, (3) unique/index/FK
constraint blocks -- all idempotent, so a missing table now self-heals on upgrade exactly like a
missing column already did. RED->GREEN SchemaRealizationEmitterAdditiveColumnsTest; proven
end-to-end against real Flyway migrations on both engines (a new
SchemaLifecycleExecutorNewTableOnExistingDbTest on H2, a Postgres Testcontainers proof-matrix
scenario): boot with a 1-concept model, insert a row, upgrade to a 2-concept model against the SAME
database -- the new table exists empty, the old row survives, schema history records APPLIED not a
refusal. Orthogonal to REG-38 (that was constraint idempotency on EXISTING tables).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-40`

### REG-41 — DefaultConceptGateway.save() leaked a row's lifecycle status to an unauthorized caller before authz ran

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 2 LNCH-13 row-level authz adversarial review
**Surface:** `kernel/concept-gateway`

save() ran enforcePermission/enforceRowWritable AFTER runWriteSemantics/validateLifecycleTransition
touched the previous record's data. A caller with zero concept.write permission and zero
access.write row-scope could submit an unreachable lifecycle-transition target and learn the row's
real current status from the resulting error's "from" detail, since neither authorization gate had
run yet. Fix: reordered so the authorization gates run BEFORE the semantic-validation use of the
previous record's data (the previous-record fetch itself stays, still needed for the row-scope
check). RED->GREEN RowLevelAuthorizationAttackTest (both InMemory and JDBC/H2 adapters): confirmed
RED pre-fix (leaked the status via the lifecycle exception), GREEN after (ROW_SCOPE_DENIED, no
status disclosed).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-41`

### REG-42 — ConceptGateway.query() leaked a row-scoped count through total/hasMore pagination metadata

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 2 LNCH-13 row-level authz adversarial review
**Surface:** `kernel/concept-gateway`

total/hasMore were computed by the store BEFORE row-scope filtering, leaking the count of rows
outside the caller's access.read scope through pagination metadata even though the items array
correctly hid them. Fix: a new ConceptGatewaySemanticPolicy.hasRowReadScope(conceptName) (default
false) lets query() pay an extra bounded re-query cost only for concepts that actually declare
access.read -- an unpaged re-query (bounded by the existing MAX_LIMIT ceiling) with the same
filters/sorts, row-scope filtered, replaces total/hasMore; every other concept's query() is
unaffected. RED->GREEN: extended an existing test to assert total==1/hasMore==false (not the
tenant's real count of 2), confirmed RED pre-fix, GREEN after, both adapters.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-42`

### REG-43 — TenantRegistryService.isActive silently fail-opened on any read failure, with no log at any level

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** First run of scripts/quality/security-pattern-sweep.py (docs/SECURITY_PATTERN_SWEEP_2026-07.md §3)
**Surface:** `runtimehost/tenant-registry`

isActive (reached from TenantStatusFilter, the sole per-request chokepoint gating tenant disable)
ended `catch (SQLException e) { return true; }` with no log at any level -- once a DataSource
existed, any read failure (dropped table, exhausted pool, mid-migration rename) silently returned
every explicitly DISABLED tenant to full service, undetectably. MED not HIGH: needs BOTH an
operator-disabled tenant AND a concurrent DB fault; an attacker cannot trigger it, and a disabled
tenant still needs valid signed credentials. Fix is not blanket fail-closed (would brick any app
legitimately without an npdev_tenant table): missing-table SQLState -> fail OPEN, log INFO,
unchanged behavior; any OTHER SQL error -> fail CLOSED, log ERROR. RED->GREEN
TenantRegistryServiceTest (+3): exactly one test RED against pre-fix code, missing-table fail-open
test stays green, proving the fix discriminates rather than flipping everything closed.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-43`

### REG-44 — crud.kernelControlled=false silently removed ALL coarse permission/audit checks, not just access.write

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 3, R3-F3
**Surface:** `generator/authorization`

A model declaring access.read/access.write while crud.kernelControlled resolves false silently
removed every coarse CRUD permission check (READ/LIST/CREATE/UPDATE/DELETE) and mutation audit
across 13 emission sites -- not just access.write as the original wording assumed. Row-level
access.read survives (generated reads go through conceptGateway unconditionally), which is exactly
the asymmetry that made the combination look harmless when spot-checked. Fix: new
UnenforceableAccessRuleCheck, run from GeneratorFacade before any emitter, refuses to generate a
model with this contradiction. Not visible to SemanticValidator alone: the validator sees only the
model, while crud.kernelControlled comes from config.json -- the contradiction is only visible where
compiled model and resolved settings meet. Resolved per-concept (overridable at concept scope; an
app-level read would miss a targeted opt-out). 5 tests incl. the concept-scoped override and an
end-to-end check that nothing is emitted when generation is refused.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-44`

### REG-45 — Flow resume was tenant-scoped but not actor-scoped -- any same-tenant user could resume another's flow

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** UNIT_TESTED
**Source:** REG16-resid Round 4 flow/await orchestration adversarial review, R4-F1
**Surface:** `kernel/flow-orchestration`

DefaultExecutionAuthorizationPolicy.canResumeExecution now requires the same tenant AND that the
requester is the actor who started the flow. FlowInstance already carried actorId, so no schema/
contract change was needed. An instance with no recorded actor stays tenant-scoped only -- a blank
actorId is what a flow started anonymously, by the cron scheduler, or before this field existed
looks like; requiring equality against null would make every one of those permanently unresumable,
turning a data-scoping fix into an availability regression for exactly the stuck flows an operator
most needs to recover. Verified before tightening that only the HTTP resume endpoint consults this
policy -- the kernel's event-driven and scheduler resume paths do not -- so background recovery is
unaffected. 2 new tests; the pre-existing resume test still passes unchanged.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-45`

### REG-46 — Persistence capability port had no tenant parameter -- flow-step persistence writes were unscoped

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 5 durable-state-adapter SQL adversarial review, R5-F1
**Surface:** `kernel/persistence-port`

Generated CRUD was tenant-scoped but the persistence capability PORT itself carried no tenant
parameter, so the flow-step persistence route was unscoped -- the same hole existed in BOTH adapters
(in-memory and JDBC), meaning this was a port-level gap, not a backend difference, and dev/prod would
disagree about visibility only by accident. Fix: a new TenantScopedPersistenceCapabilityContract
beside the unchanged PersistenceCapabilityContract; both adapters implement it;
RegistryCapabilityDispatcher prepends the executing tenant from the flow's authenticated state --
supplied by the runtime, never declared by the model author (letting the author choose the tenant
would look enforced while being weaker than no scoping at all). Two real signature/dispatch
collisions found and resolved during implementation (a String-vs-TenantScope arity clash that had
been silently mis-routing/self-recursing, and an identical reflective-dispatch collision resolved
against the interface). Scoping applied only where a tenant_id column actually exists (read from the
live catalog). 7 tests incl. delete-is-not-an-existence-oracle and save-stamps-ownership-over-a-
payload-claim.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-46`

### REG-47 — Correlation ids had no length cap -- an oversized caller-chosen id could hit the same btree limit as REG-36

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** UNIT_TESTED
**Source:** Security-pattern-sweep closure round, 2026-07-25
**Surface:** `kernel/correlation-ids`

New CorrelationIds.require(...) caps a correlation id at 400 characters, called from
KernelRunner.normalizeCorrelationId -- the single chokepoint every correlation id passes on its way
into durable state, reached before the event envelope is built or flow state initialised, so nothing
is published/executed/persisted first. Rejects rather than digests (unlike REG-36) for two reasons:
a correlation id is caller-chosen tracing metadata with no legitimate oversized form, and callers
look it up again via @PathVariable on timeline/event-query controllers -- digesting would store an
id different from the one the caller holds. 6 tests incl. the exact boundary, trim-before-measure,
and a guard that the ceiling stays inside the btree limit alongside its composite-index companions.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-47`

### REG-48 — DefaultConceptGateway.delete() had the same authz-after-invariant-eval ordering bug REG-41 fixed in save()

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** First real ADR-0009 external-AI mission (M2-SEC-ROWAUTHZ), gemini finding F1
**Surface:** `kernel/concept-gateway`

delete() ran evaluateRuleProfiles (concept invariants against the previous record's data) BEFORE
enforcePermission/enforceRowWritable -- the identical bug class REG-41 already fixed in save(),
never applied to delete(). Fix: reordered delete() so the authorization gates run before
evaluateRuleProfiles, keeping the existing previous-record fetch (still needed for the row-scope
check) -- a literal mirror of REG-41's save() fix. RED->GREEN: a dedicated Vault concept with a
locked=='false' invariant, seeded LOCKED directly through the store; confirmed RED pre-fix (leaked
the vault's locked state to a caller with zero delete access via the invariant exception), GREEN
after (ROW_SCOPE_DENIED, invariant never evaluated). delete() is store-agnostic, so InMemory+H2
coverage is the complete adapter matrix for this bug. Re-verified live against the platform source
directly (never exposed to the REG-49 staleness class).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-48`

### REG-49 — M1-SEC-GENCODE finding withdrawn as a false positive -- the reviewed pack was stale, not the platform

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** First real ADR-0009 external-AI mission (M1-SEC-GENCODE), gemini finding F1
**Surface:** `process/external-ai-review`

WITHDRAWN as a false positive, not fixed as a bug. The reviewed pack's generated Java was 62 minutes
OLDER than the LNCH13-F1 fix commit it was reviewed against -- the vendor correctly identified
LNCH13-F1's exact shape, in code where LNCH13-F1 had not yet been fixed. On code generated AFTER the
fix, every mutation arm on both flow-backed concepts is properly guarded. Root cause (pack
provenance was unrecorded) tracked and fixed as REG-51 so this false-positive class cannot recur
silently. One genuine residual checked as part of the withdrawal: no previously-verified concept had
exercised a DELETE-backed flow specifically (only create/update). A careful manual trace of the real
generated exception hierarchy found the delete arm's structural shape differs from create/update
(unconditional gateway call, not an either/or swap) so it was never actually exposed to this bug
class -- but this was a manual trace, not an automated runtime assertion. Later closed for real
(2026-07-27): a new ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest generates a real ServiceBase for a
delete-mode flow, compiles it for real, and runs it against real (not mocked) gateway/policy/kernel
components, asserting the flow's own execute() call never happens when the gateway denies. RED->GREEN
confirmed twice by temporarily reordering the mustache template.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-49`

### REG-5 — GATE-OBS-1a: surface-governance drift checks were advisory and unowned

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** NOT_VERIFIED
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `quality-gates/runtimehost-gate`

The RuntimeHost gate's surface-convergence/exclusivity checks encoded a pre-d0bf41b
"package == support bucket" convention the beta-0 manifest refactor replaced with exact allowlists.
They had been demoted to advisory-only (-PendingOk) so the gate's exit code stayed truthful, but the
underlying drift was unowned. Decision (owner): formal RETIREMENT over the plan's default option --
a concrete check confirmed the exact-list allowlist (runtime-surface-allowlist-report.json, backed
by RuntimeControllerAllowlistConfig) already IS the blocking exact-list enforcement, so the 6
package-convention checks were a redundant proxy that would only duplicate the allowlist if
rewritten. Retired to informational-only (reversible) with a dated rationale in
run-observability-hardening.ps1, run-runtimehost-gate.ps1, and OPEN_GAPS_AND_ROADMAP.md.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-5`

### REG-50 — PostgresPersistenceCapabilityAdapter fell back to UNSCOPED reads/writes on a transient metadata-read failure

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** First real ADR-0009 external-AI mission (M3-SEC-TENANT), gemini findings F1+F2 (same root cause)
**Surface:** `kernel/persistence-postgres`

TableColumns.unavailable() was returned both on a genuine SQLException AND on "this table
legitimately has no such columns" -- indistinguishable, so a transient metadata-read failure on a
tenant-scoped table silently fell back to the UNSCOPED findById/delete/exists overloads instead of
failing closed. Fix (a): TableColumns is now tri-state (a distinct queryFailedResult()), set only on
a genuine thrown SQLException; a new enforceMetadataAvailableForTenantScoping throws before the
tenant-scoped overloads ever consult hasColumn -- fails closed only when scoping status is genuinely
unknown, matching REG-43's precedent (blanket fail-closed was rejected there for the same reason).
Fix (b): the unavailable-metadata fallback in identifier resolution now routes through the
platform's existing safe-identifier whitelist instead of an unsanitized path. RED->GREEN, all
against a REAL Postgres container (the REG-36 lesson -- H2-in-PG-mode wouldn't have caught this
either): a Proxy-wrapped Connection whose getMetaData() throws confirmed RED on all 3 tenant-scoped
methods pre-fix (silently fell back, no denial) and on a hostile-identifier case (a real Postgres
syntax-error confirmed), GREEN after (all 3 throw naming the table/operation; the hostile field name
coerces to a syntactically valid but nonexistent column). (c) split out as REG-52.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-50`

### REG-51 — External-AI review packs sliced from generated code carried no provenance -- exactly how REG-49 became a false positive

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** REG-49's own root cause, docs/archive/programme-history/REG48_50_CLOSURE_PLAN.md §2 (B1)
**Surface:** `process/external-ai-review`

A pack sliced from a GENERATED app's already-emitted code recorded no provenance at all -- nothing
distinguished "reflects the current generator" from "emitted before a relevant template fix landed",
which is exactly how REG-49 became a false positive (62 minutes stale). Fix: a new
resolve_provenance() -- for a --repo-root outside the platform repo, walks upward for the sliced
app's own build-info to read its real generation timestamp, computes the newest commit touching the
generator's templates/emitters via git log, and REFUSES the pack build outright (no pack written)
when the generated code predates that commit -- owner's explicit choice over warn-and-proceed, so
this false-positive class cannot recur silently. source.kind is now "generated-app" for this case
(previously miscategorized). Verified both directions on real artifacts: re-running the exact stale
slice that produced REG-49 now refuses with a message naming the stale-vs-fix gap; a freshly
regenerated sample builds cleanly. A secondary defence-in-depth gate (provenance_audit_gaps in
check-register-consistency.py) flags EXISTING run records with unresolved provenance when backing
evidence is still available locally -- never flagging a record whose evidence is simply absent.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-51`

### REG-52 — TenantIsolationPolicy.STRICT_EQUALS normalize() only trimmed, never lowercased -- inconsistent with ExecutionContext

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** M3-SEC-TENANT mission, gemini F3 -- filed separately from REG-50, not buried in its prose
**Surface:** `kernel/tenant-isolation`

STRICT_EQUALS.normalize() only trimmed (case-sensitive) while ExecutionContext.normalizeTenantId()
lowercases (per REG-25) -- a real inconsistency whenever STRICT_EQUALS compared a context-derived
tenantId (normalized) against a per-request tenantId that bypassed ExecutionContext's constructor.
Direction was fail-closed (a spurious case mismatch denied rather than wrongly allowed), so this was
a correctness/availability gap, not a security hole. Fix: normalize() now also lowercases, matching
ExecutionContext's REG-25 canonicalization exactly. RED->GREEN using the REAL STRICT_EQUALS (not a
case-sensitive test-double lambda used elsewhere in the same file): context tenant "Acme" (normalized
to acme), request tenant "ACME" (raw, unnormalized); confirmed RED pre-fix (denied a same-tenant
read), GREEN after.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-52`

### REG-53 — SqlTypeSupport hardcoded VARCHAR(255) for every string/enum field, ignoring a declared maxLength

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** Session live-code trace, 2026-07-27
**Surface:** `dsl/sql-type-mapping`

SqlTypeSupport.sqlType -- the single shared mapper feeding generator DDL, bond DDL, and database-
definition fingerprints -- mapped every string/enum field to a literal hardcoded VARCHAR(255),
never consulting the compiled maxLength. DefaultSchemaValidator DID enforce a declared maxLength at
write-time input validation, so any string field declared with maxLength>255 let the validator
accept input the database column was never actually widened to hold -- a real hard-failure
production mode ("value too long") with zero warning anywhere in the schema-evolution tooling (no
Impact Report entry, no migration-plan diff). Genuinely undiffed, not a documented design boundary.
Fix: SqlTypeSupport.sqlType now honors a declared maxLength for string/enum fields via a new
varcharType helper -- VARCHAR(<maxLength>) when declared, the same VARCHAR(255) default when not (no
existing model's DDL/fingerprint changes). No changes needed to SchemaDiffEngine/TypeChangeMatrix --
both already correctly compare and classify a VARCHAR(n)->VARCHAR(m) change once given two genuinely
different type strings; the bug was entirely upstream. RED->GREEN: a new test drives the REAL
pipeline end-to-end (not hand-written type strings) -- confirmed RED pre-fix (a 255->10 narrowing
produced NO diff item at all), GREEN after (correctly classified as a destructive narrowing).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-53`

### REG-54 — Two dead private methods left behind by the T2.B.4 SchemaLifecycleExecutor split

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-28)
**Verification:** UNIT_TESTED
**Source:** T2.B.4 file-split verification, 2026-07-27
**Surface:** `runtimehost/schema-lifecycle`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java`

While splitting SchemaLifecycleExecutor.java (docs/DSL2_AND_DECOMPOSITION_PLAN.md §2.B.4),
worse(SchemaChangeClassification, SchemaChangeClassification) and hasTypeChange(...) (both
private static) were found to have zero callers anywhere in com.finalexec.db, confirmed by
direct grep repo-wide before deleting, not just within the package. Both methods deleted; a
dangling {@link #hasTypeChange} javadoc reference and three test files' doc-comments that
referenced hasTypeChange()/classify() as if still live were updated to describe the historical
pre-SER-P4.8 behavior instead. NPDevRuntimeHost SchemaLifecycleExecutor* suite green after.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-54`

### REG-55 — Sandboxed plugin overload resolution matched by arg count only, not type -- "Ambiguous" false positive

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-28)
**Verification:** UNIT_TESTED
**Source:** T2.B.4 live rehearsal, 2026-07-27; seen 3 times before being fixed while building the CORE C-3 durable-workflow demo
**Surface:** `kernel/sandboxed-plugin-execution`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/plugin/SandboxedPluginExecutionEngine.java`

SandboxedPluginExecutionEngine.resolveOperation matched a candidate handler method by name +
parameter count only, so PostgresPersistenceCapabilityAdapter's two 2-argument save overloads
(save(Object,Object) and save(TenantScope,Object)) always threw "Ambiguous," regardless of the
actual runtime argument types -- in the real call path adaptCallForHandler enriches a 1-arg save
into 2 args by prepending the concept name as a String, which is never a TenantScope, so exactly
one overload was ever actually legal. Fix: resolveOperation now disambiguates same-name/
same-argCount candidates by checking which ones the actual argument values are assignable to
(boxing primitives first); falls back to the original errors only when that doesn't narrow to
exactly one method. RED->GREEN: new
SandboxedPluginExecutionEngineTest#disambiguatesOverloadsBySameArgCountByActualArgumentType,
confirmed RED against the pre-fix code, GREEN after. Full NPDevRuntimeHost suite 404/0, no
regression.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-55`

### REG-56 — Flow resume rebuilds ExecutionContext with the wrong actor/role, three different wrong ways

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found while building the CORE C-3 durable-workflow demo, 2026-07-28
**Surface:** `kernel/flow-resume`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/ExecutionContext.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/ResumeCoordinator.java`

Two filed hypotheses were refuted by tracing code, not guessed away. Actual root cause: a real
permission-context bug, confirmed live via a debug log -- the resumed flow's capability.invoke
check ran as roles=[user] (denied), moments after the SAME request's event.publish check had run
as roles=[admin] for the SAME actor/tenant. Three call sites each built a resume ExecutionContext
a different wrong way (the publisher's own context; ExecutionContext.of, which defaults to USER;
ExecutionContext.anonymous()) because FlowInstance never persisted roles in the first place. Fix:
new ExecutionContext.resuming(tenantId, actorId), granting the trusted resume-level role
(mirroring ExecutionContext.system's ADMIN trust for the cron scheduler), wired into all three
call sites; the now-unused caller-supplied-context parameter removed from
resumeWaitingExecutionsFor, updating its four callers. RED->GREEN, freshly reproduced on this
checkout: the notify-approval capabilityCall step re-added to the durable-workflow-demo model
reproduced CAPABILITY_FAILED on a real kill+restart before the fix; 3/3 clean runs after. Plus
ExecutionContextResumingTest (3/3) and the full NPDevKernel:kernel suite (163/163), no regression.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-56`

### REG-57 — H2's default 500ms WRITE_DELAY can lose committed flow-instance checkpoints across a hard kill

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found while building the CORE C-3 durable-workflow demo, 2026-07-28
**Surface:** `generator/database-config`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/UserDatabaseDefinitionLoader.java`

Ack-ordering was eliminated first, by code: flowInstanceStore.update(waiting) is a plain blocking
call on a fully synchronous, single-threaded servlet call chain, no thread hop or async layer
anywhere between the kernel and the JDBC statement. That leaves physical durability: H2's MVStore
defaults to a 500ms WRITE_DELAY, buffering committed writes in memory before flushing to disk,
and this was not set anywhere in the repo. A hard kill inside that window loses however many
commits landed since the last flush even though each JDBC call had already returned success --
a contiguous tail of at least three commits lost together (a signature consistent with a
time-windowed buffer loss, not one dropped write). Fix: ;WRITE_DELAY=0 added to the H2 JDBC URL
construction (UserDatabaseDefinitionLoader.jdbcUrl, both H2_LOCAL and H2_SERVER branches -- the
only production call site), forcing a physical flush on every commit. Postgres unaffected (COMMIT
is synchronous to WAL there). RED->GREEN, freshly reproduced: with the fix reverted and the
demo's workaround sleep removed, run-durable-resume-demo.ps1 reproduced the exact failure fresh;
with the fix restored, 3/3 clean passes. Plus UserDatabaseDefinitionLoaderWriteDelayTest (2/2).
The 5-second sleep workaround was deleted from run-durable-resume-demo.ps1.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-57`

### REG-58 — Narrow-type DROP COLUMN crashed mid-migration on WmsOffice's real database -- composite unique index not dropped first

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Live, real destructive migration on WmsOffice's production database, 2026-07-28
**Surface:** `runtimehost/schema-lifecycle`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/DestructiveRecreationPass.java`

WmsOffice's real, user-acknowledged destructive migration (26 DESTRUCTIVE_NARROW_TYPE items)
crashed 8/26 items in, on identity_password_reset_tokens.token_hash, with an H2
JdbcSQLSyntaxErrorException: column may be referenced by a unique index. The partially-migrated
database file was backed up immediately. Root cause: executeNarrowTypeDropAndRecreate issued a
plain ALTER TABLE ... DROP COLUMN with no regard for a unique index/constraint still referencing
that column -- every model field declared unique gets a tenant-scoped, COMPOSITE bootstrap index
(ux_<table>_<column> ON <table> (tenant_id, <column>)), and H2/Postgres both refuse to silently
drop a column that is only one of a composite index's columns (a single-column index sharing the
dropped column DOES get auto-dropped, which is why an initial single-column repro attempt failed
to reproduce -- the composite shape was the load-bearing detail). Several other columns in the
same batch were equally likely unique-constrained business keys and would have hit the identical
crash later in the same run. Fix: new dropIndexesReferencingColumn (portable
DatabaseMetaData#getIndexInfo, not a naming-convention assumption) finds and drops every index
touching the narrowed column before the DROP COLUMN/ADD COLUMN pair. Deliberately does not
recreate the constraint itself -- UniqueConstraintPass already idempotently re-adds any declared
unique constraint on every boot's afterMigrate, so recreating it here would race that pass.
RED->GREEN: new DestructiveRecreationPassNarrowTypeUniqueColumnTest reproduces the identical
exception byte-for-byte against the real composite-index shape with the fix disabled; passes
(2/2) with it restored. Full NPDevRuntimeHost suite 406/0, no regression. Not yet closed
end-to-end on WmsOffice itself as of this fix -- see REG-59 for the live-database recovery.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-58`

### REG-59 — WmsOffice live database recovery record (REG-58 fix re-verification) -- recovery only, NOT the platform gap

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found and resolved-on-live-data while re-verifying the REG-58 fix against WmsOffice's real, partially-migrated database, 2026-07-28
**Surface:** `runtimehost/schema-lifecycle`

THIS ROW COVERS THE MANUAL RECOVERY PERFORMED AGAINST WMSOFFICE'S REAL DATABASE ONLY -- it does
not cover the platform gap that recovery exposed; that gap is filed separately, OPEN, as REG-61.
DestructiveRecreationPass.executeNarrowTypeDropAndRecreate's ADD COLUMN never re-applies NOT NULL
even when the model declares the field required. BackfillPass DOES catch this on the next clean
boot (refuses to boot rather than silently leaving columns nullable) unless the model declares a
literal default to backfill with -- so the exposure window is only during a crashed/interrupted
boot, not permanent, correcting this filing's own first-draft framing. The deeper gap: the
sanctioned recovery mechanism (a literal default, backfilled via one UPDATE) cannot satisfy a
UNIQUE constraint across more than one existing row -- confirmed live (identity_roles.name 5
rows, identity_users.username 6 rows, both tenant-scoped unique). Resolved on WmsOffice's live
database via direct out-of-band SQL (not a model or platform-code change): backfilled all 18
blocked columns (flat placeholder for 16 non-unique, per-row-unique placeholder for the 2 unique
ones), then ALTER COLUMN ... SET NOT NULL directly. Verified via Impact-Only.ps1: verdict SAFE, 0
destructive/0 attention, then a real boot succeeded (/actuator/health UP). Consequence: WmsOffice's
identity/user data for its then-existing 6 users/5 roles are now placeholder values, not original
data -- the destructive DDL had already committed before backfill-refusal was reached, so this was
already true before the manual recovery; recovery only unblocked the boot.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-59`

### REG-6 — ColumnFacts: eight SchemaLifecycleExecutor passes each re-derived column semantics independently

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance; fully closed via the Schema Engine Rebuild
**Surface:** `runtimehost/schema-lifecycle`

SchemaLifecycleExecutor's roughly eight passes (relax, tighten, backfill, additive, delta-report,
classify, bond-refusal, rename, unique-constraint) each performed their own set arithmetic over the
same raw manifest to answer the same questions (is this column platform-managed? additive-eligible?
required?), with three-to-four overlapping and divergent notions of "platform column". This was the
root cause behind repeated prior findings (T-B1, T-B2): each round fixed one pass's inference while
the structure that produced the wrong inference stayed untouched. Initially closed re-scoped
(2026-07-22, risk-core only: a ColumnFacts projection + a class-load drift-guard, full set-algebra
purity deferred). FULLY closed 2026-07-24 via the Schema Engine Rebuild: a single canonical
CurrentSchema/DesiredSchema/SchemaDiff model (SchemaDiffEngine) is now the ONE place column
semantics are derived, consumed by both decision surfaces and all four mutation passes, built
strangler-fig with a proven 100% behavior-equivalence shadow-parity gate on H2 + Postgres before
each pass switched over. Remaining known limit (separate, documented, not a re-derivation): no
explicit FK/index diff (P0.2/P5.2, deferred enhancement).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-6`

### REG-60 — Aggregate Workbench post-commit "Saved." confirmation is wiped by the next render before a user can see it

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found during F5-V.2 live Aggregate Workbench re-verification, 2026-07-28
**Surface:** `generator/workbench-page-template`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/workbench-page.html.mustache`

commitDraft()'s success handler set msg.className="msg ok" on the CURRENT render's message
element, then immediately called render(), which rebuilds #app from scratch -- including a
fresh, blank <span class="msg"> -- wiping the confirmation before a user could ever see it.
invokeAction()'s success handler had the identical shape, so it was fixed too. Fix: a
module-level pendingMsg variable, set by the success handlers instead of mutating the doomed
message element directly; render() now applies any pending message to the freshly-created
<span class="msg"> before clearing it. Verified live (not just unit-tested): WmsOffice
regenerated + rebuilt, ExpedicaoWorkbench.html, real browser via ScrapForAI -- logged in as
trial/admin, opened a real PreExpedicao record, clicked Save, DOM readback + screenshot confirm
the green "Saved." text is visible next to the Save button after the re-render.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-60`

### REG-61 — Narrow-type recreate loses NOT NULL; no per-row-unique default expressible for a required UNIQUE column

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** UNIT_TESTED
**Source:** Split from REG-59 during its live-recovery filing, 2026-07-28
**Surface:** `runtimehost/schema-lifecycle`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/DestructiveRecreationPass.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/BackfillPass.java`

Both needs carried verbatim from REG-59's filing, both fixed. (a)
DestructiveRecreationPass.executeNarrowTypeDropAndRecreate now looks up the model's declared
required-ness for the narrowed column (via DesiredSchemaFactory) and re-applies NOT NULL directly
when the table is currently empty -- a zero-row table no longer needs the backfill dance at all. A
non-empty table still adds the column nullable exactly as before, leaving (b)'s refusal as the
correct next line of defense. New DestructiveRecreationPassRequiredColumnPreservationTest (3/3).
(b) BackfillPass now detects required + UNIQUE-constrained (single- or compound-field) + more
than one row that would receive the same literal, and refuses by name (table.column, affected row
count, a documented recovery recipe generalizing the out-of-band SQL WmsOffice used) instead of
proceeding to a confusing duplicate-key failure once UniqueConstraintPass re-adds the constraint
later. Did NOT invent a per-row-unique default expression language, per the plan's own scope
decision. New BackfillPassUniqueColumnRefusalTest (2/2), RED-first. docs/SCHEMA_EVOLUTION.md
documents the new refusal case and recipe. Full com.finalexec.db suite 273/273, no regression.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-61`

### REG-62 — allowedActions is an untyped, unvalidated CSV-in-metadata escape hatch -- a typo silently drops an action-rail button

**Type:** GAP · **Severity:** LOW · **Status:** OPEN
**Verification:** NOT_VERIFIED
**Source:** Investigated while closing F5-R1, 2026-07-28
**Surface:** `dsl/autopanel-lifecycle`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/AutoPanelExpander.java`

allowedActions (per-lifecycle-state action-rail gating, AW-P5) is authored today as a
comma-separated string inside a lifecycle state's generic metadata map
(AutoPanelExpander.java:310), not a typed schema field, and nothing validates an entry resolves
to a real declared action -- a typo silently yields a missing button in production, same class as
REG-52/REG-53. Investigation found the natural fix (typed array + validate against declared
actions) is blocked on a real prerequisite gap: AutoPanelSurfaceAst has no actions field at all --
an AutoPanel section's action list lives inside that surface's own untyped metadata, the same
escape hatch allowedActions itself uses. JSON Schema alone cannot validate against a per-model
dynamic set of action names, so typing the array without resolving the action side first would
look done without fixing the actual failure mode. Fix, when picked up: give AutoPanel section
actions a typed AST home first, then add the typed allowedActions array (all 4 model.schema.json
mirrors) plus a cross-reference check, likely in PanelValidation.validateAutoPanels (which already
has both the concept's lifecycle and the AutoPanel's surfaces in scope). Not urgent: 0 of 27
corpus models use allowedActions at all.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-62`

### REG-63 — AuxScreen and Pigmentampa's model.json use a pre-DSL-stabilization flow-step shape the current schema rejects

**Type:** GAP · **Severity:** MEDIUM · **Status:** OPEN
**Verification:** VERIFIED_LIVE
**Source:** Found while confirming panel-provenance manifests for R-G2 (docs/REMEDIATION_PLAN.md)
**Surface:** `dsl/model-schema-compatibility`

AppGen/apps/_official/AuxScreen and .../Pigmentampa's model.json both declare flows in a
pre-stabilization shape (top-level input/out per step, a type:"validate" step kind) that the CURRENT
model.schema.json rejects outright -- JsonModelParser.parse() fails schema validation before an AST
is even built. Confirmed live: Build-NpdevApp.ps1 -GenerateOnly fails identically for both apps, so
neither can be regenerated, and no compiled model/invocations catalog/live bundle can be produced
for either at all. Blocked both apps' single hand-written screen from getting a panel-provenance
manifest for R-G2 (filed there as a written reason, not silently skipped). Both existing
BUILT/running instances are unaffected -- this only blocks regeneration with the current toolchain.
Fix, when picked up: a real npdev migrate codemod for this flow-step shape (BREAKING.md's own
standing rule -- every DSL break ships its codemod in the same commit), likely a mechanical
type:"validate" -> invariantCheck rewrite plus flattening the top-level input/out/name properties;
needs a check of how many other AppGen apps (if any beyond these two) still carry this shape before
scoping the codemod's coverage.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-63`

### REG-7 — LNCH-1-B6: no migration advisory lock (multi-instance) -- converted to a feature

**Type:** BOUNDARY · **Severity:** — · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `runtimehost/schema-lifecycle`

Owner decision: convert this and REG-8 into features with a fail-loud + operator-resolves posture
rather than leave them as documented limits. Delivered as three sub-features: (1) external/unmanaged
database ownership -- a new schemaLifecycle.ownership field; ExternallyManaged apps issue zero
schema DDL and run a read-only compatibility check every boot; (2) "mark migration as done" -- a
ControlPanel operation that fast-forwards the stored fingerprint with zero migration passes, on the
operator's word; (3) collision detection (this item's original scope) -- a single-row claim table
taken at the top of every upgrade boot and released in a finally, a held claim refuses the boot
loudly naming the holder, a crashed holder is clearable via a SUPERUSER admin endpoint. Honestly
named residual: this is detect-and-refuse, NOT a true lock -- a near-simultaneous-INSERT race
remains theoretically possible on an engine without strict insert serialization; a genuinely virgin
database's first-ever boot is not claim-protected by design (claiming unconditionally there would
break Flyway's own baseline detection -- a real bug found and fixed via live boot rehearsal).
Verified: full RuntimeHost suite green after each sub-phase, dedicated test classes for all three,
live boot rehearsals against a real assembled app found and fixed two real ordering bugs.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-7`

### REG-8 — LNCH-1-B9: schema-ahead detector blind to a pure column drop on rollback

**Type:** BOUNDARY · **Severity:** — · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `runtimehost/schema-lifecycle`

Rolling an older build back onto a database a newer build had already migrated past (which dropped
a column) silently re-added the dropped column empty, instead of refusing. Owner decision: closed as
"a clear refusal exists," not "every drop is reconstructed" -- data a genuine drop destroyed stays
gone. Fix (Trigger C): SchemaLifecycleExecutor.databaseMigratedPastThisBuild consults
npdev_schema_history instead of live schema shape -- finds the most recent successfully-applied row
for this build's target fingerprint; if a LATER row records a different fingerprint, refuses before
classify() ever runs, guarding every resolution kind uniformly. CORRECTION found by independent code
verification and fixed as REG-27: the original implementation only refused when the rolled-back-to
build had a PRIOR history row, which a fresh-installed build never wrote -- so the register's own
canonical example (fresh-installed build N, N+1 drops a column, roll back to N) was not actually
refused until REG-27 made afterMigrate record the initial realization as an APPLIED history point
too. This item's DONE claim holds only with the REG-27 fix applied.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-8`

### REG-9 — LNCH-4: auth secrets management -- JWT key env-var delivery

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, rescoped 2026-07-21 (P0 priority)
**Surface:** `runtimehost/auth`

Original claim was that DB credentials, runtime API keys, JWT keys, and the super-user key all
lacked an env-var deployment path. Independent verification found the gap narrower: DB credentials
and runtime API keys already worked (Spring's built-in relaxed binding, already emitted into the
Docker Compose template). The real, confirmed-open gap was JWT keys: LoginController/
JwtBearerAuthFilter read key paths via @Value with no default and no env-var wiring, and
DockerDeploymentEmitter emitted zero NPDEV_AUTH_JWT_* entries -- a missing key failed with a raw
Spring bean-creation error, not a docs-linked one. Fix: NPDEV_AUTH_JWT_PUBLICKEYPATH/
PRIVATEKEYPATH emitted into compose + .env.example; StartupValidator fail-fasts with a docs-linked
message on an unreadable key; LoginController supports verify-only deployments (blank private key
boots, login returns 503) instead of crashing the context. Super-user key env-seeding defaulted to
WONTFIX (issued-not-supplied model preserved, reversible). Verified: 12/12 StartupValidator unit
tests + 8/8 verify-only JwtAuthExternalBetaIT live on real Postgres.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-9`

