# REG-48/49/50 closure + ADR-0009 remainder — implementation plan

> **STATUS: ACTIVE** — 2026-07-27. Promoted from
> `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-ai-review\PLAN_REG48_50_AND_REMAINDER_2026-07-27.md`
> on owner approval ("implement"). Same promotion flow as `EXTERNAL_AI_DELEGATION_PLAN.md`.
>
> Branch `beta1-vision-spine` pushed to origin at `10d3a88` (2026-07-27), 107 commits.

## Progress as of 2026-07-27

| Item | Status | Evidence |
|---|---|---|
| A0 — re-verify REG-50 live | **DONE** | Confirmed fresh against tracked platform source (untouched by the staleness class below): `TableColumns.unavailable()` on genuine `SQLException`, `resolveCriteriaColumn`/`normalizeCriteria` reach `toDbColumn` unsanitized on that path. |
| A1 — REG-48 fix | **DONE** | RED-first `RowLevelAuthorizationAttackTest#unauthorizedDeleteIsRowScopeDeniedBeforeInvariantValidationLeaksTheRowsLockedState` (new `Vault`/`locked` fixture), confirmed RED then GREEN on both adapters after reordering `delete()` to mirror REG-41. Full kernel suite + full generated-app (`auxscreen`) suite green. |
| A2 — REG-49 withdrawal | **DONE** | Withdrawn as a false positive (stale pack), evidence kept visible per this register's own convention. Residual delete-flow arm checked via a real generated concept (`Widget`/`RetireWidget`) + a hand-trace of the real exception hierarchy — confirmed safe, not exposed to the bug class. |
| B1 — pack provenance (REG-51) | **DONE** | `build-review-pack.py` now refuses to build a pack sliced from generated code older than the newest generator-template commit (owner chose refuse-over-warn). Proven both directions: refuses on the exact stale `wmsoffice` slice that produced REG-49; builds cleanly on freshly regenerated code. Schema extended. Java producer's `product-app` path scoped out (different content class, confirmed via its still-passing parity tests) — see REG-51's own register row for full detail. |
| B2 — re-run M1 on fresh code | **DONE** | `wmsoffice` regenerated fresh (2026-07-27), pack built and accepted (not refused) by B1's own new check. Real nvidia + gemini re-run: nvidia 0 findings; gemini raised 1 new HIGH claim ("null tenantId bypasses tenant isolation" in `enforceWithConceptGateway`/`enforceDeleteWithConceptGateway`) that manual verification found to be a **false positive** — `DefaultConceptGateway.normalizeTenant(null, context.tenantId())` returns the caller's own authenticated tenant (the intentional "no override" default), so `sameTenant` trivially passes; there is no bypass. Recorded here rather than filed as a register item, matching how calibration's own false/hallucinated findings were handled — not every gemini claim is real, and saying so in both directions is the honesty bar this feature runs on. |
| A3 — REG-50 (a/b/c) | **DONE** | (a) tri-state `TableColumns` (owner's tri-state decision), tenant-scoped methods now fail closed on genuine metadata-query failure. (b) unavailable-metadata identifier fallback routed through `SqlIdentifierSupport.safeSqlIdentifier` (an existing whitelist, not a third). Both RED→GREEN on a **real Postgres container** (`PostgresPersistenceCapabilityAdapterMetadataFailureTest`, REG-36 lesson applied) — RED confirmed via `git stash` of the pre-fix adapter, including a genuine live `PSQLException: Unterminated string literal` from the unfixed code. Full `persistence-postgres` + `kernel` suites green (one unrelated, independently-reproducing `KernelRunnerCapabilityPolicyTest` flake, REG-4's known class). (c) filed as its own row, **REG-52**, per this plan's own scoping — not fixed this pass (correctness, not security; direction is fail-closed). |
| C4 — plan-doc contradiction | **DONE** | One-line fix in `EXTERNAL_AI_DELEGATION_PLAN.md`. Why `check-register-consistency.py` didn't catch it: the checker is structural (strikethrough register rows, status-cell roadmap rows, plan status banners), not semantic — it has no general "does this prose sentence contradict that other prose sentence" check, and this drift was exactly that shape (one sentence listing "D3, D4, D5 remain open" vs. a separate §7 table cell saying D3 was answered). Not a gap worth closing generally; a prose-contradiction detector is a different, much bigger tool. |
| C5 — untracked `build.gradle` | **DONE** | Decision: **track it**, not `.gitignore`. Read the file in full: 524 lines of hand-authored, actively-maintained build logic (generated-runtime-mount detection, source-set filtering, schema-realization preflight, REG/LNCH-referenced comments throughout) -- clearly platform source, not a build artifact. Every sibling module's `build.gradle` is already tracked; this one had no git history at all, ever, with no `.gitignore` rule excluding it -- a plain omission. Staged (`git add`), not committed (no commit requested this pass). |
| C3 — M5 friction filed | **DONE** | Filed in `LAUNCH_READINESS_GAPS.md` as a new dated section (matching the 2026-07-22 external-tester findings' own style): neither tutorial doc mentions `ui.label` at all, so a blind author's model correctly validates with `status: warning` (4 missing-label warnings) they were never told to expect. Genuine success (correct concept/fields) alongside genuine friction (undocumented expectation) — both stated, not just the win. |
| C1 — M4 container-execution half | **DONE** | A real, clean `eclipse-temurin:17-jdk` Docker container (neither `git` nor `curl` preinstalled — friction #1) attempted an anonymous clone of the real origin URL: credential prompt, not a checkout. `GET api.github.com/repos/...` confirmed 404 for unauthenticated access — **the repository is private**, a more fundamental blocker than anything the AI's own command plan could have anticipated (it never had repo access to know). Halted the repro there rather than substituting this session's own credentials, which no genuine third party would have. Filed as a concrete addendum to REG-17 (not a status change — CI-based "ACHIEVED" is unaffected, since GitHub Actions never needed anonymous access either) explicitly deferring the fix (making the repo public) to the owner, same class as D4/D5. Run record `docs/external-ai-review/runs/M4-REPRO-BLIND.json` updated with the real attempt. |
| C2 — real M7-IMPACT-CONVERT run | **DONE** | Built a real evidence bundle from actual production code: `ImpactReport.java`'s `probe()` method falls back to a `MANUAL_REVIEW`-annotated worst-case row count for any non-character-length type narrowing (e.g. BIGINT→INTEGER), unlike VARCHAR narrowing which gets a precise `LENGTH()`-based count — backed by the real, currently-passing `ImpactReportH2Test#nonCharNarrowingIsManualReviewWorstCaseNonNull`. Asked nvidia to judge the claim that this fallback is genuinely necessary. Real result (after 2 transient timeouts, same class as earlier in the session): **"partially supported"** — correctly identified that a precise count IS achievable for BIGINT→INTEGER (a real, computable range predicate), but appropriately hedged that the evidence doesn't state whether the current design is a deliberate choice or an oversight, recommending further investigation rather than overclaiming either way. Independently verified as technically correct. A genuinely useful, well-calibrated M7 result — the product feature's actual value proposition, demonstrated. **Found and fixed a real bug along the way:** the vendor's claim text contained a `→` (U+2192) that crashed the script's own finding-print on Windows (`cp1252` console encoding) — verdict/run record were already safely written by that point; fixed by reconfiguring stdout/stderr to UTF-8 at `main()`'s entry. |

---

## 0. CORRECTION FIRST — REG-49 is a false positive

Verifying before planning the fix (the RED-first rule, applied to the *finding* rather than the fix)
showed REG-49 does not reproduce on current code. **The M1 pack sliced generated Java that predates
the fix for the very bug the vendor reported.**

| Evidence | Value |
|---|---|
| LNCH13-F1 fix commit `22fb5c8` | **2026-07-25 03:16:52 -0300** |
| `wmsoffice` emitted Java reviewed by M1 | generated **2026-07-25 02:14:41** — **62 minutes earlier** |
| Guard calls in the reviewed (stale) `CrossDockingServiceBase.java` | `enforceWithConceptGateway`: **1** · `LNCH-13` markers: **2** |
| Guard calls in `reg39-healthy-control`, generated 07:51 (post-fix) | `enforceWithConceptGateway`: **3** · `LNCH-13` markers: **6** |

On freshly generated code every mutation arm is guarded, on both flow-backed concepts:

| Concept | create | update | delete |
|---|---|---|---|
| `CrossDocking` | `enforceWithConceptGateway` :179 → flow :180 | `enforceWithConceptGateway` :225 → flow :226 | `enforceDeleteWithConceptGateway` :244 |
| `Movimento` | `enforceWithConceptGateway` :165 | `enforceWithConceptGateway` :210 → flow :211 | `enforceDeleteWithConceptGateway` :229 |

**So the vendor was right and wrong at once:** it correctly identified LNCH13-F1 — in code where
LNCH13-F1 had not yet been fixed. This is the *same behaviour calibration already recorded* ("gemini
… once correctly finding R3-F2's bug on the other mission's pack … present there because R3-F2 wasn't
fixed yet at that earlier commit"). Calibration saw the pattern; the live mission did not connect it.

**The manual verification did not catch it because it re-read the artefact the pack contained** — the
stale file — which confirms the pack's content, not the platform's live state. Content verified,
provenance not: the seventh blind spot's exact shape, in the instrument built to avoid it.

**REG-48 re-verified live and is REAL** — `DefaultConceptGateway.delete()` at
`NPDevKernel\kernel\src\main\java\com\npdev\kernel\concepts\DefaultConceptGateway.java:268-274`:
`evaluateRuleProfiles(...)` runs, then `enforcePermission` (:273), then `enforceRowWritable` (:274).
Exactly REG-41's shape, unfixed in `delete()`. REG-48 stands. (It is platform source read from the
repo, so it was never exposed to the staleness class.)

---

## 1. Group A — the security findings

### A0 · Live re-verification of REG-50 *(do first, ~20 min)*
REG-50 cites `PostgresPersistenceCapabilityAdapter` lines ~420/~455/~481/~562 — platform source, so
not exposed to the stale-generated-code class, but after §0 nothing gets planned on an unverified
row. Confirm `TableColumns.unavailable()` is genuinely returned on `SQLException` and that
`resolveCriteriaColumn`/`normalizeCriteria` reach `toDbColumn` unsanitised on that path.

### A1 · REG-48 — `delete()` authorization ordering  **[fix shape already determined]**
- **RED first:** extend `RowLevelAuthorizationAttackTest` with the delete-side twin of
  REG-41's `unauthorizedWriteIsRowScopeDeniedBeforeLifecycleValidationLeaksTheRowsStatus`; confirm it
  fails against current code on **both** adapters (InMemory + JDBC/H2).
- **Fix:** move `enforcePermission` + `enforceRowWritable` above `evaluateRuleProfiles` in `delete()`,
  keeping the `previous` fetch (the row-scope check needs it) — a literal mirror of REG-41's `save()`.
- **Verify:** GATE-KERNEL + GATE-H2 + GATE-PG. Register row → DONE with the RED→GREEN evidence.
- **Risk:** low. The precedent fix is proven and adjacent.

### A2 · REG-49 — **withdraw honestly, do not delete**
This register records mistakes rather than amending them away (the thread summary's own "two mistakes
I made, both recorded rather than amended away"). So:
- Rewrite the REG-49 row as **WITHDRAWN — FALSE POSITIVE (stale pack)** with the §0 evidence table,
  keeping the original claim visible.
- **One genuine residual to close:** `enforceWithDeleteFlow` exists in
  `service-base.mustache:696`, but neither verified concept exercises a **delete-backed flow**.
  Generate a concept with a custom delete flow and confirm the guard is emitted on that arm too —
  the "only one arm of a Mustache conditional" shape is exactly what LNCH13-F1 was.
- **Prove behaviour, not shape** (lesson #2): the confirmation must be a runtime attack test against a
  booted generated app, not a `grep` for the emitted call. The read-side twin of LNCH13-F1 shipped
  broken once precisely because a structural assertion showed the call was *emitted*, not that it *denied*.

### A3 · REG-50 — Postgres metadata fail-open + SQL identifier path
Three separable pieces:
- **(a) Fail-open metadata** — `loadTableColumns` cannot distinguish "queried, zero columns" from
  "query threw". **Needs your decision** (see §4): REG-43's precedent deliberately rejected blanket
  fail-closed because it bricked apps legitimately lacking the table; a tri-state that fails closed
  **only for tables that should be tenant-scoped** is the shape that matches that precedent.
- **(b) Identifier whitelist** — the unavailable-metadata fallback concatenates `toDbColumn(field)`
  into SQL unchecked. The brief's claim C5 says identifiers are safe "through two independent
  whitelists": **route this path through an existing whitelist, do not add a third.** Same reasoning
  that kept the pack redactor from becoming a fourth redaction derivation.
- **(c) MEDIUM** — `TenantIsolationPolicy.STRICT_EQUALS.normalize()` trims but does not lowercase,
  while `ExecutionContext.normalizeTenantId()` lowercases (REG-25). Direction is fail-closed
  (spurious mismatch), so it is a correctness/consistency fix, not a security fix. File as its own row
  rather than leaving it buried inside REG-50's prose.
- **RED first** on each: (a) a metadata-read failure injected on a tenant-scoped table must not fall
  back to the unscoped overload; (b) a hostile field name must be rejected, on a **real Postgres
  container** (the REG-36 lesson: H2-in-PG-mode does not enforce what Postgres does).

---

## 2. Group B — the defect §0 uncovered *(this is the real find)*

### B1 · **REG-51 (proposed) — pack provenance is unrecorded**
A pack records its own `manifestSha256`, but **nothing records which commit produced the generated
app it sliced**. A mission can therefore review code that no longer exists and return a verdict
indistinguishable from a live finding — which is exactly what happened, at HIGH severity.

- **Fix:** the pack manifest records, for any mission slicing emitted code — the generated app's
  source commit, its generation timestamp, and the newest commit touching the templates that produced
  it. The builder **refuses** (or warns loudly, per your call) when the sliced app predates a relevant
  template commit.
- **Applies to both producers** — Python `build-review-pack.py` and Java `ReviewPackBuilder` — and the
  existing byte-identical-manifest conformance test must be extended to cover the new fields, or the
  two silently diverge.
- **Gate:** `run-external-ai-gate.ps1` fails a run record whose pack has unknown provenance.

### B2 · Re-run M1-SEC-GENCODE against freshly generated code
Until B1 lands and M1 re-runs, **M1's real result is unknown** — the mission has never reviewed
current generated output. The plan's P5 row must be corrected to say so rather than carrying REG-49
as a find.

---

## 3. Group C — ADR-0009 remainder still genuinely open

| # | Item | State | Work |
|---|---|---|---|
| **C1** | `M4-REPRO-BLIND` container-execution half | Command plan produced; nobody executed it | Run the AI's plan in a clean Linux container, log friction. Advances REG-17/E2; **closure still gated on D4** |
| **C2** | `M7-IMPACT-CONVERT` | `NOT_RUN` (recorded honestly) | Build a pack from a real generated app's `MANUAL_REVIEW` Impact Report item and run it. **This is the mission that proves the product feature is a feature** — not gated by D3 |
| **C3** | M5 friction never filed | `EXTERNAL_TESTER_COLDSTART.md`'s "After the run" rule says friction becomes dated findings in `LAUNCH_READINESS_GAPS.md`; no such rows exist | File them (incl. the missing-UI-labels warning the blind author hit) |
| **C4** | Plan-doc contradiction | Banner (`:41`) says "D3, D4, D5 remain open"; §7 says **D3 ANSWERED 2026-07-27** | One-line fix. The kind of drift `check-register-consistency.py` exists to catch — worth asking why it did not |
| **C5** | `NPDevRuntimeHost/build.gradle` untracked | Untracked since before this thread | Track it or add to `.gitignore` — decide, don't leave it |

---

## 4. Decisions only you can make

| # | Decision | Why it is yours |
|---|---|---|
| **REG-50(a)** | Metadata-read failure: blanket fail-closed, or tri-state failing closed only for tenant-scoped tables? | REG-43 set the precedent that blanket fail-closed bricks legitimate apps. Every REG fix in this register records *"owner chose X"* |
| **B1** | Stale-pack provenance: builder **refuses**, or **warns and proceeds**? | Refuse is safer; warn is friendlier for a product feature app authors use |
| **D4** | REG-17 DoD ruling | `REG17_ONION_HARVEST_PLAN.md:214` — "the executor must never make this call" |
| **D5** | E5 real participants — permanently open, or schedule sessions? | The one item with no AI substitute |
| **F8** | Row-level authz TOCTOU — row locking/CAS in a **published port** | Carried from the previous thread as "owner decision, not a patch" |

---

## 5. Suggested order

1. **A0** re-verify REG-50 live *(≈20 min — nothing else is planned on an unverified row)*
2. **A1** REG-48 RED→GREEN *(smallest, precedent-proven, closes a real HIGH)*
3. **A2** REG-49 withdrawal + the delete-flow arm check *(honesty debt; do not let a false HIGH sit)*
4. **B1** pack provenance *(the systemic fix — prevents the next false finding)*
5. **B2** re-run M1 on fresh code *(only now is M1's result meaningful)*
6. **A3** REG-50 (a)+(b)+(c) *(largest; needs your decision on (a) first)*
7. **C3, C4, C5** *(small honesty/housekeeping — can be batched)*
8. **C1, C2** *(programme completion; C2 is the higher-value one)*

**Verification discipline throughout, per this project's proven rules:** RED before GREEN; both
adapters; real Postgres where Postgres semantics matter; behaviour not shape; findings filed before
fixed; and no row moves to DONE on a re-read of the same artefact that produced the claim.
