# NPDev Launch-Readiness & LNCH-1 — Programme Retrospective

> **Period covered:** 2026-07-14 → 2026-07-21 · **Branch:** `beta1-vision-spine`
> **End state:** `c7e3519` (`beta1-184-gc7e3519`), working tree clean, no release tag cut.
> **What this document is:** the complete record of one continuous working session — from
> "what is NPDev missing before launch?" through a 24-item launch ledger, a five-round
> review→plan→implement→review programme that took the platform's last existential P0 item
> (schema evolution for live apps) from *nonexistent* to *done and independently reviewed five
> times*, and out to a 17-item register of everything still open.
>
> **Why it exists:** the programme produced seven planning documents, 60+ commits and a large
> evidence trail, spread across the repo and `NPDev_General__OutsideRepo`. This is the single
> narrative that explains how they relate, what was found, what was learned, and what a future
> session should imitate or avoid. It is a retrospective, not a specification — nothing here
> overrides `docs/SCHEMA_EVOLUTION.md` (the contract), `docs/NPDEV_OPEN_ITEMS_REGISTER.md`
> (what is open), or the verification ledger (what is proven).

---

## 1. Executive narrative

The session began with an open question: *what is NPDev missing before it can honestly be called a
low-code platform?* The answer, after reading the gaps ledger and the maturity docs, was that the
missing work was **not more generator features**. The generation pipeline and runtime kernel were
already strong — durable flows with crash-proven resume, invariants, lifecycle enforcement, bonds,
widgets, theming, ControlPanel, seed data, file upload, and an AI-authoring loop proven blind. What
was missing was the *lifecycle around the generated apps*, organized as five verbs:

**Evolve · Secure · Scale · Operate · Distribute.**

That framing produced `docs/LAUNCH_READINESS_GAPS.md` — 24 items, `LNCH-1` … `LNCH-24`, each with a
why/where/how/DoD. Of those, one was singled out as the largest and most existential: **LNCH-1,
model-diff schema evolution for live apps.** Without it, every NPDev app is a disposable prototype,
because the entire value proposition of low-code is *iterating on a live app*.

LNCH-1 then absorbed the rest of the session across **five complete review→plan→implement→review
rounds**. Each round: an adversarial review of the previous round's implementation, a phased plan
written for an implementer with no project history, an implementation, then another review. The
severity of what each review found declined monotonically:

| Round | Plan | Phases | Highest-severity finding |
|---|---|---|---|
| 1 | `LNCH1_SCHEMA_EVOLUTION_PLAN.md` | P0–P8 | **HIGH** ×2 |
| 2 | `LNCH1_REMEDIATION_PLAN.md` | R0–R9 | **CRITICAL** ×1 |
| 3 | `LNCH1_HARDENING_PLAN.md` | X0–X9 | **HIGH** ×1 |
| 4 | `LNCH1_CLOSEOUT_PLAN.md` | C0–C8 | **MEDIUM** ×2 |
| 5 | `LNCH1_PLATFORM_COLUMN_PLAN.md` | T0–T10 | **MEDIUM** ×1, then none |

That curve — HIGH → CRITICAL → HIGH → MEDIUM → none-above-MEDIUM — is the programme's headline
result. It is genuine convergence, not a treadmill, and §5 explains why each round kept finding
*something* anyway.

The session closed with `docs/NPDEV_OPEN_ITEMS_REGISTER.md`: 17 items (`REG-1` … `REG-17`) covering
everything still open across LNCH-1's residue and the wider ledger, each with what/why/where/example/
how-to-fix.

---

## 2. What LNCH-1 actually delivered

**Before.** When a live app's model changed, the platform could do exactly two things: auto-apply a
purely additive column, or **drop every table and recreate the whole schema**, gated behind a single
blanket boolean set once at authoring time. Renames and type changes were *detected and correctly
classified* — then executed destructively anyway.

**After.** A complete, audited, token-gated evolution path:

- **In-place field renames** (`ALTER TABLE … RENAME COLUMN`) and **concept/table renames**
  (`RENAME TO`), declared via `renamedFrom`, zero data loss, idempotent on re-boot.
- **Safe type widening** in place (`INT→BIGINT`, `VARCHAR(n)` growth, `NUMERIC(p,s)` growth), with a
  `TypeChangeMatrix` that classifies WIDENING / NARROWING / INCOMPARABLE.
- **Surgical destruction**: only the itemized table/column is dropped, never a whole-schema wipe,
  gated on a SHA-256 acknowledgment token bound to the exact item set *and* the target fingerprint.
- **Two authorization channels**: a CLI-supplied token (`-AcknowledgeDestructive`) or a ControlPanel
  pre-authorization submitted on the *currently running* app before the new jar is deployed —
  because a refused boot has no server left to serve an authorization page.
- **Data pre-checks**: new unique constraints validated against existing rows (violating tuples
  reported, not a mid-migration failure); new required fields backfilled from a literal default and
  tightened to `NOT NULL`, or refused with a named remedy.
- **A migration plan preview** (`-PlanOnly`) that prints safe items plainly and destructive items in
  red with the token, exits non-zero when destruction is present, and never touches the database.
- **A full audit trail**: `npdev_schema_history` with write-before-execute / update-after semantics,
  one detailed row per mutating pass, `PARTIAL-CRASH` left honest when a pass dies mid-way.
- **A schema-ahead-of-build detector** that refuses to boot an *older* jar against a database a newer
  build already migrated — two orthogonal triggers, so it fires for ordinary renamed columns and not
  just exotic ones.
- **Ownership-gated concept drops**: the platform drops an orphaned table only when it can *prove*
  it created it (recorded per boot in `npdev_schema_metadata`), so a hand-created table in the same
  schema is never swept into a destructive pass.
- **Platform-column integrity**: `id`/`version`/`row_version`/`tenant_id` are never treated as
  model-optional, and an app loosened by an earlier build is repaired on the next boot.

**Verification apparatus built alongside it:** a 41-scenario H2 proof matrix, a 25-test Postgres
Testcontainers twin, a cross-producer token-agreement conformance test, three source-pinning
conformance tests, and repeated live rehearsals against real Postgres with real seeded data.

---

## 3. The five rounds, and what each review found

### Round 1 — original build (P0–P8) → review

The plan's first act was to discover it **was not greenfield**. `SchemaLifecycleExecutor` already
existed as a Spring `FlywayMigrationStrategy` that classified changes by live-DB introspection;
`renamedFrom` already existed on fields; and a quarantine test actively forbade recreating an older
`com.npdev.generator.migration` authority that had been deliberately killed. The plan therefore
built *on* the existing runtime authority rather than adding a competing one — and routed all new
generator code to `com.npdev.generator.schemaevolution` to respect the quarantine.

**Review findings (10):** two HIGH —
- **F1**: required-field backfill *and its refusal* were skipped on every destructive path, so a
  required field added in the same upgrade as any acknowledged drop landed permanently nullable with
  NULL legacy rows.
- **F2**: `DropTable.stableString()` embedded a live row count, which the generator (having no
  database) could not know — so the acknowledgment token printed by `-PlanOnly` could **never** match
  at boot for a concept drop, and the ControlPanel pre-authorization channel could not work at all
  for that case.

Plus F3 (type-string divergence between the two token producers), F4 (refusals were not
side-effect-free — a rolled-back old jar booted "clean" against a migrated schema), F5 (audit trail
thinner than specified), F6 (three documents telling three different stories about token agreement),
F7 (`renamedFrom` marker lifecycle undocumented, with a stale-marker data-loss edge), F8 (no
concurrency guard), F9 (an uncommitted, load-bearing fix), F10 (two gate issues).

### Round 2 — remediation (R0–R9) → review

**Finding X-B1 (CRITICAL).** The previous round's own fix created it. `LNCH-1-B7` had made
`classify()` escalate to DESTRUCTIVE when it found an orphaned (dropped-concept) table. But the
blanket `destructiveAllowed` flag then authorized the **whole-schema wipe** path — which drops
exactly the tables the *new* manifest lists. The orphan is not in that list. So: **dropping one
concept destroyed every still-modelled concept's data while the table it was meant to remove
survived.** The upgrade destroyed everything except its target.

**Finding X-B2 (HIGH).** The schema-ahead detector built the round before was **inert in
production**: it skipped every additive-eligible column, and in a real manifest nearly every column
is additive-eligible. Its test passed only because the fixture declared no additive columns — a shape
no real manifest has. Third occurrence of fixture-vs-production divergence in this feature's history.

Plus X-B3 (a surviving orphan silently lost its ownership record, so it could never be cleaned up
later), and eight further gaps and record defects.

### Round 3 — hardening (X0–X9) → review

**Finding C-B1 (HIGH).** X4.4 had just established that destroying *one* table's data requires a
token. But the UNKNOWN-item path still reached the whole-schema wipe under blanket authorization
alone — **destroying every table's data with no token.** The most destructive operation in the system
had the weakest authorization requirement, inverting the principle the round had just set.

Plus C-D1 (`PLATFORM_MANAGED_COLUMNS` an unpinned hand-copy of generator knowledge), C-D2 (the
"default for new apps: OFF" claim was documentation only — 33 shipped definitions taught the
opposite to the AI-authoring corpus), C-B2 (`LNCH-1-B8`), and three record defects.

**This round also corrected one of my own errors.** Two earlier plans asserted the blanket posture
was on in "every shipped app definition." It is not: `destructiveAllowed` requires **all four**
`schemaLifecycle` fields to line up, and the count was 15 samples + 18 AppGen definitions —
dominant, not universal. The claim had come from grepping one boolean.

### Round 4 — closeout (C0–C8) → review

**Finding T-B1 (MEDIUM), found in the project's own documentation.** The round had re-captured a
live rehearsal log into `docs/SCHEMA_EVOLUTION.md`. That log contained the line:

```
relaxed NOT NULL on no-longer-required column(s): [users.version, users.row_version, users.tenant_id]
```

`relaxNoLongerRequiredColumns` was stripping `NOT NULL` from the platform-managed columns on **every**
fingerprint-changing boot, because they appear in `businessTableColumns` but never in the
model-derived `businessTableRequiredColumns`. `tenant_id`'s `NOT NULL` is the guard the emitter
explicitly added so an upgrade cannot leave rows unreachable to every tenant-scoped read;
`row_version` NULL silently defeats LNCH-16's compare-and-swap. The bug was shipping *as
documentation*.

**Finding T-B2 (MEDIUM).** `version` was declared by `fullColumnNames` but omitted from
`additiveColumnNames`, so no migration could ever add it — a table missing it produced an UNKNOWN,
which after C1 meant a **boot refusal** recoverable only by a token-gated full wipe. It was also how
scenarios 24b/27 manufactured their UNKNOWN: the suite's canonical "unexplainable diff" was a
platform column shaped exactly like `row_version`, which *is* additive.

Plus T-M1 (ten uncommitted build-config files that shaped every local measurement) and T-P1 (C-D2
closed at 1 of 33 apps).

### Round 5 — platform-column (T0–T10) → review → closed

The implementation closed T-B1 with both halves (exclusion + a repair pass that backfills NULLs to
platform defaults and restores `NOT NULL`), proven **live** on a real Postgres database left nullable
by an earlier build and holding real rows. It closed T-B2, committed the build tuning, made the
RuntimeHost gate's exit code truthful, and — in a self-directed Phase T10 — root-caused
**GATE-DET-1**, a generator non-determinism that had gone unattributed for four rounds:
`java.util.Map.of(…)` produces an `ImmutableCollections.MapN` whose iteration order is randomized
per-JVM by `SALT`; Jackson serializes in iteration order; one manifest file differed byte-wise
between generations.

**Review finding:** the fix addressed **1 of 6** instances of that class — five more multi-entry
`Map.of` → Jackson sites remained, two of them in the schema manifest the executor itself reads.
Also flagged: if the six verification generations had shared a JVM they would have shared one salt
and been identical regardless of the fix, so the method needed confirming.

**Final implementation closed both**, added `NoMultiEntryMapOfInGeneratedManifestEmittersTest` to pin
the class, and completed T4's corpus flip 4-of-4 with per-app regenerate/boot/live-additive-change
proof. **Review found nothing further.**

---

## 4. Notable bugs found across the programme

Beyond the review findings, the implementation sessions themselves surfaced substantial defects —
several pre-existing and long-latent. The pattern worth noting: **almost every one was found by
raising the verification bar, not by reading code harder.**

| Bug | How it was found | Why it mattered |
|---|---|---|
| Every Postgres app with a unique field crash-looped on first boot | Real `docker compose up` | V1 emitted `CREATE UNIQUE INDEX`; the runtime later tried `ADD CONSTRAINT` with the same name. H2 tolerates it; Postgres shares a relation namespace and throws. Would have hit **every** generated Postgres app. |
| A rename beside an unrelated drop silently lost data | Real 3-change upgrade rehearsal | The app booted "successfully" with *both* the old populated column and a new empty one. No error — a green boot with corrupted data. |
| `LNCH-1-B7`: concept drop previewed, acknowledged, never executed | Live Postgres rehearsal | `-PlanOnly` promised a `DROP_TABLE`, demanded a token, the operator supplied it — and the table survived, because `classify()` only enumerated manifest-declared tables. |
| `LNCH-1-B8`: failed `-Upgrade` → false "fresh install", **exit 0** | Reproduced spontaneously during other work | A wrong plan presented as valid, with the documented "safe to proceed" signal. |
| `GATE-DET-1`: `Map.of` salt non-determinism | Four rounds of a red gate, then deliberate root-causing | 1-of-643-file byte diff between generations; unattributed for four rounds because the check reported pass/fail without naming the differing file. |
| `forEach` loop bodies silently lost at boot | Incidental, while touching canonical JSON | Pre-existing: every generated app dropped any `forEach` step's body. Fourth occurrence of the canonical-JSON field-loss class. |
| `JdbcBusinessConceptStore` not joining Spring transactions | Writing the transaction-boundary contract | Kernel-gateway write and JPA write in the same `@Transactional` method were two independent auto-commits — silent partial-write corruption on the default CRUD path. |
| `IT-EXTPG-1` mis-attributed for three rounds | Deliberate re-checking of an inherited claim | Recorded as "needs an external Postgres"; actually a Spring wiring bug. The old advice would have cost a session. |
| The stateful-migrations gate's proof capture was a silent no-op | A new step made XML presence load-bearing | Copied test XMLs from a path that does not exist (build dir is redirected per the output policy). Every copy had silently failed. |

---

## 5. Why five rounds — the meta-analysis

Midway through, the question was asked directly: *why do the points keep not closing?* The honest
answer had five parts, and it is the most transferable insight the programme produced.

**1. Each fix changes the decision logic, which creates the next round's surface.** The chain is
explicit:

```
B7 fix (drop orphan tables)  ─►  X-B1 (blanket flag routes concept drops to whole-schema wipe)
X1 fix (surgical routing) + X4.4 (token for concept drops)
                             ─►  C-B1 (one table needs a token; ALL tables doesn't)
C1 fix (token for the wipe)  ─►  T-B2 becomes a boot-blocker (missing `version` used to auto-recreate)
```

None were mistakes. Each was the correct fix. But correcting a decision tree while the invariants
around it are implicit re-illuminates a different corner every time.

**2. The root cause was structural, and no round fixed it.** `SchemaLifecycleExecutor` contains
~eight passes, each performing its own set arithmetic over the same raw manifest maps to answer the
same questions (is this column platform-managed? additive-eligible? required? a bond?). There were
already three overlapping notions of "platform column," plus a fourth mirrored in test fixtures.
T-B1 was one pass inferring wrongly. T-B2 was two passes disagreeing. **Each round fixed one pass's
inference; the structure that produced it was never unified.** This is now filed as `REG-6`
(`ColumnFacts`) — the single highest-leverage remaining refactor in the subsystem.

**3. The verification bar kept rising, exposing pre-existing defects.** Real Postgres found bugs H2
could never show. A real ControlPanel round-trip found `LNCH-1-B7`. A new gate step exposed a proof
capture that had been silently doing nothing. These were not regressions — they were old bugs
becoming visible as the instruments improved. Good news that guarantees findings keep appearing while
quality rises.

**4. Roughly half of later findings were not code.** Ledger self-contradictions, stale doc claims,
summary-vs-evidence mismatches, unrun gates, uncommitted config, test names that lied. Documents
drift the moment behaviour changes, and each round produced more documents.

**5. Some "open" items were deliberate deferrals** (one app flipped instead of four; `B9` WONTFIX;
`B6` out of scope). They correctly reappear as open. That is a tracked backlog, not a failure.

---

## 6. Methodology that emerged (the part worth reusing)

The programme converged on a specific working discipline. Each element earned its place by catching
something real.

**Planning**

- **Plans are written for an implementer with no project history.** Every plan opens with a file
  orientation map, a "read these in this order" list, and the guardrails that have historically been
  violated — with the consequence of each stated.
- **`VERIFY` markers over assertions.** Plans mark every claim the implementer must confirm against
  real code before acting. This repeatedly caught plan errors (see §7).
- **Design decisions are pre-made and marked "do NOT re-derive."** With a rationale, so an
  implementer does not "improve" a choice whose reasons are invisible from the code.
- **Questions for the owner are batched at phase zero**, never guessed mid-implementation.
- **A stated minimum bar.** Every plan names the non-negotiable core if effort runs short, so
  truncation is a decision rather than an accident.

**Implementing**

- **Reproduce RED first.** Every bug-fix scenario was run against the unfixed code and observed
  failing, with the failure captured, before the fix. A green test that was never red proves nothing.
- **Small bounded commits**, one per phase or sub-slice, with a `Verified:` line naming what actually
  ran. Never `git add .`.
- **A pre-existing bug found on the way gets its own commit and its own test**, before the feature
  commit that found it.
- **Fixture shapes must mirror production.** Fixture-vs-production divergence hid live bugs three
  separate times; the eventual answer was a shared `realisticAdditiveColumns` helper plus a
  conformance test pinning it to the emitter.

**Verifying**

- **Live > suite.** "The suite is green" was never accepted as closure for a data-integrity fix. The
  bar was a real app, real database, real data, output recorded.
- **Real Postgres > H2.** H2 is more permissive in exactly the ways that mattered; multiple bugs were
  invisible to it.
- **Measurement integrity is a first-class concern.** Two near-misses shaped this: a flake ratio
  nearly recorded from one cached Gradle result replayed five times (identical 0.276s), and a
  179-of-257 failure nearly attributed to a code change when it was caused by running two gates
  concurrently. Rule: record serial-vs-parallel, the Gradle properties in effect, and whether
  `--rerun-tasks` was used — every time.
- **A verification ledger is the tiebreaker.** Created after a session's *summary* claimed live
  rehearsals its own *evidence file* said were not performed. Every claim is marked VERIFIED LIVE /
  VERIFIED BY SUITE / NOT VERIFIED. If any document disagrees with the ledger, the ledger wins.
- **The summary must match the evidence file exactly** — no upgrade in confidence between the two.

**Recording**

- **Evidence outside the repo** (`NPDev_General__OutsideRepo\lnch1-evidence\`), one note per phase.
- **A test's `@DisplayName` is documentation.** If what a test does changes, its name changes in the
  same commit.
- **Name what is unfixed rather than burying it.** Every round ended with an explicit "still open"
  list, including items the round had intended to complete.

---

## 7. Corrections to my own work

Recorded because a retrospective that only catalogues the implementer's errors would be dishonest.
Every one of these was caught by an implementation session following the `VERIFY` discipline the
plans themselves demanded — which is the discipline working as designed.

| Error | Where | Corrected by |
|---|---|---|
| Claimed the blanket destructive posture was on in "every shipped app definition" | Hardening plan §0/§2 | My own re-check while writing the closeout plan: `destructiveAllowed` needs **all four** `schemaLifecycle` fields, and the count was 33 of ~38 |
| Gave commands that cannot work: `gradlew -p NPDevRuntimeHost test` | Hardening plan §3.1 | The implementer — `NPDevRuntimeHost` is a **template**, not a buildable subproject; its tests run inside an assembled app |
| A fixture helper that disagreed with the real additive-eligibility rule in two ways | Hardening plan §3.2 | The implementer, against the emitter source |
| Offered `strategy: RecreateOnAppStart` as a dev/CI escape hatch | Closeout plan §2.3 | The implementer's census: the strategy string is read in exactly one decision; it is **inert** and behaves identically to `KeepExistingIfCompatible` |
| Named two dependent scenarios for T2's blast radius; there were three | Platform-column plan §2.3 | The implementer found scenario 25 also relied on the missing-`version` UNKNOWN |
| Found the `Map.of` non-determinism class only *after* the implementer had root-caused and fixed one instance | Round-5 review | Sequencing, not error — but the class analysis should have come with the root cause |

The pattern: **my errors were consistently in environment specifics and command mechanics; the
implementer's were consistently in scope and verification method.** The `VERIFY` discipline exists
precisely because a planner working from reading cannot be trusted on execution details.

---

## 8. Artifacts produced

### 8.1 Documents

| Document | Contents |
|---|---|
| `docs/LAUNCH_READINESS_GAPS.md` | The 24-item launch ledger (`LNCH-1`…`LNCH-24`), five-verb framing, per-item why/where/how/DoD, five-wave sequencing |
| `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` | The original 9-phase build (P0–P8), guardrails still binding across all later rounds |
| `docs/LNCH1_REMEDIATION_PLAN.md` | R0–R9, fixing the first review's 10 findings |
| `docs/LNCH1_HARDENING_PLAN.md` | X0–X9, fixing the CRITICAL regression and 12 further findings |
| `docs/LNCH1_CLOSEOUT_PLAN.md` | C0–C8, fixing the last authorization hole and 6 findings |
| `docs/LNCH1_PLATFORM_COLUMN_PLAN.md` | T0–T9 (+T10 self-directed), fixing the platform-column bug class |
| `docs/NPDEV_OPEN_ITEMS_REGISTER.md` | 17 open items (`REG-1`…`REG-17`) with what/why/where/example/how-to-fix |
| `docs/SCHEMA_EVOLUTION.md` | **The user-facing contract** — mental model, rename declaration, required fields, tightened uniqueness, acknowledgment workflow with a verbatim real-run example, refusals and rollback, current limitations |
| `docs/architecture/FLOW_TRANSACTION_CONTRACT.md`, `docs/architecture/APP_UPGRADE_CONTRACT.md`, `docs/adr/ADR-0006`, `ADR-0007`, `docs/CONFIGURATION.md`, `docs/DEPLOYMENT.md`, `docs/RELEASE_PROCESS.md`, `docs/DSL_REFERENCE.md`, `docs/TUTORIAL_FIRST_APP.md` | Produced by the Waves 4–5 work that preceded LNCH-1 in this session |

### 8.2 Evidence trail (outside the repo)

`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\` — `phase-0.md`…`phase-8.md`,
`remediation-R0-R8.md`, `hardening-X0/X1/X2-X3/X6.md`, `hardening-final.md`,
**`hardening-verification-ledger.md` (the tiebreaker)**, `closeout-*.md`, `platcol-*.md`.

### 8.3 Test apparatus

| Suite | Start | End |
|---|---|---|
| H2 proof matrix (`SchemaLifecycleExecutorProofMatrixTest`) | 0 | **41 scenarios** |
| Postgres Testcontainers twin | 0 | **25 tests** |
| Assembled-app RuntimeHost suite | — | **257 tests, 0 failures** |
| Conformance/pinning tests added | 0 | **4** (`PlatformColumnContractTest`, `AdditiveColumnMirrorContractTest`, `NoMultiEntryMapOfInGeneratedManifestEmittersTest`, `TokenAgreementConformanceTest`) |

### 8.4 New tracked IDs

`LNCH-1-B6`…`B9`, `GATE-OBS-1`/`1a`, `GATE-DET-1`/`1a`, `GATE-REL-1`, `IT-EXTPG-1`, `T-F1` — all in
`docs/OPEN_GAPS_AND_ROADMAP.md`, with `REG-1`…`REG-17` in the new register.

---

## 9. Where the platform stands

**Launch ledger:** 17 DONE · 6 PARTIAL · 1 OPEN.

All five verbs now have a DONE core slice:

- **Evolve** — LNCH-1, closed after five rounds.
- **Secure** — LNCH-2/3 done, LNCH-4 P0 + password reset done; **secrets-via-env-vars still open
  (`REG-9`, the last P0)**.
- **Scale** — LNCH-5 (SQL push-down, 100k-row volume-gated) and LNCH-6 (index emission) done.
- **Operate** — LNCH-7/8/9 done, live-verified via real `docker compose`.
- **Distribute** — license, ADRs, upgrade contract and CI workflows exist; **CI has never been
  observed green (`REG-10`)**, and cross-platform scripts remain (`REG-11`).

**The three things that matter most next**, per the register's ordering:

1. **`REG-9`** — LNCH-4's secrets slice. The last P0, and it resolves an incoherence between two
   items both marked DONE: a Dockerized deployment whose secrets live in files on an ephemeral
   filesystem.
2. **`REG-2`** — the ten dead integration tests include `TenantIsolationE2EIT` and eight
   `JwtAuthExternalBetaIT`. A tenant-isolation E2E that fails at context load is externally
   indistinguishable from one that passes.
3. **`REG-16`** — LNCH-1 has had five adversarial reviews; the other 23 items have had **zero**. Five
   rounds on one subsystem found 2 HIGH, 1 CRITICAL, 1 HIGH, 2 MEDIUM, 1 MEDIUM. There is no reason
   to believe the auth stack is cleaner — only that nobody has looked.

---

## 10. Durable lessons

1. **A correct fix can create the next bug.** Budget for it. Sequence work so that a change to a
   decision tree is followed by a review of that tree, not by the next feature.
2. **Unify the model before adding the ninth pass.** Eight passes each re-deriving the same facts is
   how `T-B1` and `T-B2` happened. `REG-6` is the fix, and it should precede any new pass.
3. **Raise the verification bar deliberately, and expect it to surface old bugs.** Every escalation —
   H2 → real Postgres → real compose stack → real browser — found something the previous level could
   not. That is the instrument working, not the code degrading.
4. **Fixtures that do not mirror production hide live bugs.** Three occurrences here. The cure is a
   shared helper plus a conformance test pinning it to the production source.
5. **Duplicated knowledge needs a pin, not a comment.** Four conformance tests now exist for this.
   Each was added after the duplication had already drifted or was about to.
6. **Measurements need their configuration recorded.** A number without "serial or parallel, which
   properties, cached or `--rerun-tasks`" is not a measurement.
7. **One tiebreaker document, or the record will contradict itself.** The verification ledger exists
   because a summary and its own evidence file disagreed and there was nowhere to look.
8. **Name what is unfixed.** Every round in this programme ended with an honest open list. That is
   the reason the register in §8 could be written at all.
9. **A gate that always fails trains everyone to ignore it.** `GATE-OBS-1` sat red across four rounds
   before being converted to an advisory with a truthful exit code.
10. **Review effort has diminishing returns per subsystem and enormous returns per *new* subsystem.**
    The sixth LNCH-1 round would have been worth less than the first review of the auth stack.

---

*Companion documents: `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (what is open now) ·
`docs/LAUNCH_READINESS_GAPS.md` (the 24-item ledger) · `docs/SCHEMA_EVOLUTION.md` (the contract) ·
`docs/OPEN_GAPS_AND_ROADMAP.md` (runtime/generator items) ·
`..\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md` (the tiebreaker).*
