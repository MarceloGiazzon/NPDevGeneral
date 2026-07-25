# ONE PLAN — close every remaining open item

> **Written:** 2026-07-25 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
> **Scope:** this single document closes **everything** still open — the 4 unreviewed security surfaces,
> REG-36, REG-37, and the 4 non-blocking evidence/process items. Nothing else remains after it.
> **Cost: 3 working sessions** (was 6). Every fact verified against the live tree on 2026-07-25.

---

## 0. How this got compressed from 6 sessions to 3 — read this before you batch anything

The register forbids "mechanizing" the adversarial review, and that rule is **not** being dropped. But
the rule protects **depth per surface**, not *calendar boxes*. Two things make 3 sessions safe where
naive batching would not be:

**(a) A mechanical sweep runs FIRST, across all four surfaces at once.** The bug classes already found
in this codebase are *pattern-matchable*, not judgement calls:

| Bug class | Found as | Grep-able signature | Current count |
|---|---|---|---|
| Security guard present in only ONE branch of a template conditional | **LNCH13-F1 (CRITICAL)** | `{{#hasX}}…{{/hasX}}{{^hasX}}…{{/hasX}}` where an `enforce*` call sits in one side only | 2 template files carry `{{#hasX}}` at all |
| Schema/SQL error swallowed into a security negative | **REG-39** | `catch (…Exception)` in an auth path returning false/null/empty | 17 catch sites in `com/finalexec/auth` |
| Unparameterised SQL / concatenated identifier | *(not yet found — that's the point)* | `"SELECT…" +`, `String.format` into SQL, `+ tableName +` | 23 candidate sites in `NPDevKernel/adapters` |
| Read without a tenant predicate | REG-16 Tier A family | SQL read with no `tenant_id` in `WHERE` | sweep computes |
| Unbounded caller-influenced input | **REG-36** | store/persist of a caller value with no length bound | sweep computes |

Pattern-matching *is* legitimately batchable. Doing it once across all four surfaces finds the known
classes everywhere, cheaply, and tells you **where the deep review should concentrate**.

**(b) The remaining four surfaces pair naturally by threat model.** Reviewing two surfaces that share a
mental model is *more* effective than splitting them — you carry the same attacker questions across both
instead of context-switching:

- **Output/rendering trust boundary** — generated code (Round 3) + export/PDF (Round 6). Both answer:
  *what happens to author- and user-controlled content on the way out?*
- **State/execution trust boundary** — flow/`await` orchestration (Round 4) + Postgres adapter SQL
  (Round 5). Both answer: *who owns durable state, and does identity survive it correctly?*

**What does NOT change (the guardrails that make this safe):**
1. **A CRITICAL or HIGH finding STOPS the session immediately.** Report, remediate, re-verify. Do not
   start the paired surface. (This is why Round 2 succeeded.)
2. **Each surface still gets its own scope list and its own findings document.** Two surfaces per
   session, never one blended review.
3. **If depth runs out, split the session.** Finishing surface A well and deferring B beats skimming
   both. Say so in the commit rather than pretending.
4. **Prove behaviour, not shape.** A structural assertion shows a call is emitted, not that it *denies*.
   Model: `RowLevelAuthorizationAttackTest` — real gateway, real policy, both adapters, assert the denial
   **and** that the guarded side effect never ran.

---

## 1. Global rules

- **Never `git add .`** — stage by explicit path. No pushing, no branch switching.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Pre-commit: `rm -rf NPDevRuntimeHost/runtime-data && pwsh -File scripts/hygiene/clean-workspace-state.ps1`
- **After changing kernel/adapter Java you MUST restage jars**, or RuntimeHost tests silently run against
  a stale jar and *appear* to fail (this cost a full diagnosis cycle on 2026-07-25):
  ```bash
  pwsh -File scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir 'D:/WorkSpace/NPDev/Build/runtimehost-libs'
  ```
  **Use forward slashes and quote the path.** In Git Bash, `D:\WorkSpace\...` loses its backslashes
  (`\W`,`\N`,`\B` are escapes) and the script writes jars *inside the repo*.
- Gates: **GATE-H2** `cd NPDevRuntimeHost && ./gradlew test --tests "com.finalexec.*" -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs` ·
  **GATE-PG** (+ `--tests "com.finalexec.db.*PostgresProofMatrixTest" -PincludePostgresMatrix`, Docker up) ·
  **GATE-KERNEL** `cd NPDevKernel && ./gradlew test` · **GATE-GEN** `cd NPDevGenerator && ./gradlew :generator:test` ·
  **GATE-AI** `pwsh -File scripts/quality/run-ai-knowledge-gate.ps1` (includes the register self-check).
- **STOP and report** on: a CRITICAL/HIGH finding · a gate red for a reason you did not cause · a
  documented fact here proving false.

---

## 2. SESSION 1 — Sweep + the two kernel MEDIUMs

### 2.1 The cross-surface mechanical sweep (do this first; it steers sessions 2–3)
Write `scripts/quality/security-pattern-sweep.py` implementing the five patterns in §0(a). It **reports**,
it does not fail a build — its output is a worklist, and a noisy blocking gate would get bypassed.

For each hit emit `file:line · pattern · the 1-line reason it might be a problem`. Then triage by hand
into: **(i)** genuine finding → rate + file · **(ii)** safe, with the reason recorded so the next sweep
does not re-litigate it · **(iii)** needs deep review → hand to session 2 or 3.

Write the triage to `docs/SECURITY_PATTERN_SWEEP_2026-07.md`. **This document is the input to sessions
2 and 3** — it tells you which of the four surfaces actually carries risk.

> Start with pattern 1 (guard-in-one-branch). It is the shape that produced the only CRITICAL found so
> far, and there are just 2 template files carrying `{{#hasX}}` conditionals — so it is minutes of work
> for the highest-value class.

### 2.2 REG-37 — circuit-breaker failure counter is not atomic · MED
`KernelRunner.onCapabilityFailure` does a plain get-then-put with no CAS or lock, in **both**
`InProcCircuitBreakerStateStore` (bare `ConcurrentHashMap`) and `JdbcCircuitBreakerStateStore` (blind
`UPDATE … SET consecutive_failures = ?` computed client-side). Concurrent failures undercount, so the
breaker opens late — degrading a resilience guarantee under exactly the load it exists for.
**Fix:** in-proc → `compute`/`merge`; JDBC → `UPDATE … SET consecutive_failures = consecutive_failures + 1`
computed **in SQL**, or a CAS on a version column.
**Verify:** N threads record a failure concurrently ⇒ counter is exactly N. RED before the fix (a plain
get-then-put reliably undercounts under contention). **Gates:** GATE-KERNEL + GATE-PG.

### 2.3 REG-36 — unbounded idempotency key · MED
A caller-influenced key (via a model author's `idempotencyKeyField`) is stored unbounded while the cached
success *value* is already bounded by `KernelRunner.IDEMPOTENCY_RESULT_MAX_CHARS`.
**Fix:** bound symmetrically — SHA-256 hex above a small threshold.
**Verify:** an oversized key is digested, **and two distinct oversized keys do not collide into one
idempotency record** (assert distinct outcomes, not merely distinct strings). **Gates:** GATE-KERNEL + GATE-PG.

**Session 1 done when:** sweep triage committed; REG-36 + REG-37 fixed with runtime tests; both marked
CLOSED in the register (the self-check enforces the summary row matching).

---

## 3. SESSION 2 — Output/rendering trust boundary (Rounds 3 + 6)

**Shared attacker question:** *what happens to author- and user-controlled content on the way out?*

### 3.1 Round 3 — generator codegen **output** (review the emitted app, not the emitter)
Scope in writing: the generated service/controller/repository sources and the templates that emit them.
Attack questions:
- Any **other** `{{#hasX}}/{{^hasX}}` pair where a security call lives in only one branch? (the LNCH13-F1 shape — the sweep pre-answers this)
- Where does emitted code interpolate model-author strings into **SQL / JPQL / HTML / JS**? Is anything escaped by convention rather than by construction?
- Does **every** generated write endpoint route through the concept gateway — or does any persist directly through `conceptStore`?
- Does generated read/list/query code honour `access.read`, or only the endpoints someone remembered?

### 3.2 Round 6 — export/PDF path
Scope in writing: the export/render entry points and their content pipeline. Attack questions:
- Can exported content trigger an **outbound fetch** (SSRF) — remote images, stylesheets, fonts?
- Is the output path attacker-influenceable (**traversal**)? Is the filename derived from user data?
- Is there a **size/time bound**, or can one export exhaust the host?
- **Does export honour row-level `access.read` scope, or export everything the tenant has?** ← the highest-consequence question in this session; a scope-blind export is a bulk data leak.

**Deliverables:** `docs/REG16_CODEGEN_OUTPUT_ADVERSARIAL_REVIEW.md` and
`docs/REG16_EXPORT_PDF_ADVERSARIAL_REVIEW.md` (separate documents — one per surface, per §0 guardrail 2).
Triage: CRITICAL/HIGH ⇒ fix now + runtime test · MEDIUM ⇒ dated `REG-nn` row · INFO ⇒ doc only.

---

## 4. SESSION 3 — State/execution trust boundary (Rounds 4 + 5)

**Shared attacker question:** *who owns durable state, and does identity survive it correctly?*

### 4.1 Round 4 — flow/`await` orchestration + `DefaultProcedureExecutor`
- Can a **resumed** flow run under a *different* actor's or tenant's context than the one that suspended it? (the highest-value question here)
- Is the resume token unguessable, **single-use**, and expiring? What authorizes a resume?
- Is loop iteration bounded (`maxLoopIterations`) under adversarial input? What about nested loops?
- On partial failure mid-flow, is durable state left readable/writable by the wrong actor?

### 4.2 Round 5 — durable-state Postgres adapters' own SQL
- Is **every** statement parameterised? (23 concat candidates already identified — the sweep triages them)
- Is `tenant_id` in the `WHERE` of every read **and** part of the key of every write?
- Any string-concatenated **identifier** reachable from model-author input?
- Do the idempotency / circuit-breaker / bulkhead / claim / mark stores each scope by tenant *in the key*?

**Deliverables:** `docs/REG16_FLOW_ORCHESTRATION_ADVERSARIAL_REVIEW.md` and
`docs/REG16_POSTGRES_ADAPTER_SQL_ADVERSARIAL_REVIEW.md`. Same triage rule.

---

## 5. The non-blocking tail — fold into any session with spare capacity

| Item | Action | Cost |
|---|---|---|
| **REG-39 healthy-pack live control** | Redo with a **fresh empty DB dir** so Flyway has no prior V1 checksum; confirm the log shows `Tomcat started` and no `StartupValidator` failure. Replace the artifact; un-strike the correction note in that run's `SUMMARY.md` | 30 min |
| **Conversion hooks: no Postgres live proof** | Repeat the P7.7 live proof against Postgres (the engine the docs recommend for DDL-bearing conversions) | 30 min |
| **9 unparseable-status items** | `python scripts/quality/check-register-consistency.py --verbose` names them. Reword each `**Status:**` to start with a recognised keyword, or hand-verify and record why it stays ambiguous | 30 min |
| **F4 — row-level authz TOCTOU** | Currently INFO with honest reasoning. Re-rate **only** if `expectedRowVersion` CAS ever becomes the default; otherwise leave and note the trigger | — |

---

## 6. Definition of done — the whole programme

- Sweep triage document exists; every hit is finding / safe-with-reason / escalated.
- All four surfaces have a findings document; **every CRITICAL/HIGH remediated with a runtime test**;
  every MEDIUM is a dated register row.
- REG-36, REG-37 closed. `§3.10`'s "Rounds not yet done" list is **empty**.
- All five gates green, including GATE-AI (register self-check).
- `OPEN_ITEMS_SNAPSHOT.md` §1 (blocking) is **empty**.

**Then, and only then, the honest claim changes** from *"no known CRITICAL issues in the reviewed
surfaces"* to *"every launch surface has had an adversarial review"* — which is what "stable and
complete" actually requires.
