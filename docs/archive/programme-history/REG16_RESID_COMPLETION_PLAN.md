# REG-16-resid — Completion Plan (4 filed MEDIUMs + Rounds 3–6)

> **STATUS: EXECUTED (2026-07-25).** All four MEDIUMs closed and Rounds 3-6 completed (compressed into 3 sessions per `ONE_PLAN_CLOSE_EVERYTHING.md`). Kept as a record.


> **Written:** 2026-07-25 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
> **Audience:** an executor with limited autonomy. Every fact verified against the live tree on 2026-07-25.
> **If reality does not match this document → STOP and report.**
>
> **Everything still outstanding from the adversarial-review programme is in this one document**: the four
> filed MEDIUM findings (REG-36, REG-37 from Round 1; REG-41, REG-42 from Round 2) and the four
> not-yet-started rounds (3–6). Nothing else from `FINAL_FOUR_CLOSURE_PLAN.md` remains open.

---

## 0. Rules that govern this whole programme

1. **One surface per round. Never batch.** The register forbids mechanizing this item by name. Two
   surfaces in one session produces a shallow review of both.
2. **A CRITICAL or HIGH finding STOPS the round.** Report to the owner, remediate, re-verify — do not
   continue to the next surface. (Round 2 did exactly this, correctly.)
3. **Triage rule:** CRITICAL/HIGH ⇒ fix this round · MEDIUM ⇒ file as a dated `REG-nn` row, scheduled not
   dropped · INFO ⇒ record in the findings doc only.
4. **Prove behaviour, not shape.** Round 2's lesson: a structural assertion over generated source shows a
   call is emitted, not that it *denies*. Every security fix needs a runtime test that exercises the
   attack. `RowLevelAuthorizationAttackTest` is the model to copy — real gateway, real policy bridge,
   both adapter families, assert the denial **and** that the guarded side effect never ran.
5. **Never `git add .`** — stage by explicit path. No pushing. Commit trailer:
   `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
6. Pre-commit: `rm -rf NPDevRuntimeHost/runtime-data && pwsh -File scripts/hygiene/clean-workspace-state.ps1`
7. Gates: **GATE-H2** (`cd NPDevRuntimeHost && ./gradlew test --tests "com.finalexec.*" -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs`),
   **GATE-PG** (add `--tests "com.finalexec.db.*PostgresProofMatrixTest" -PincludePostgresMatrix`, Docker up),
   **GATE-KERNEL** (`cd NPDevKernel && ./gradlew test`), **GATE-GEN** (`cd NPDevGenerator && ./gradlew :generator:test`).

---

## 1. The four filed MEDIUM findings

Do these **before** Round 3. They are known, scoped, and each is a contained fix — leaving them open
while opening new surfaces is how a backlog becomes permanent.

### 1.1 REG-41 — authorization runs *after* lifecycle validation, leaking row status · MED
**Source:** LNCH13-F2 · `docs/REG16_LNCH13_ROWLEVEL_AUTHZ_ADVERSARIAL_REVIEW.md`
**Defect:** `DefaultConceptGateway.save()` fetches the previous record and runs
`runWriteSemantics`/`validateLifecycleTransition` **before** `enforcePermission`/`enforceRowWritable`. A
caller with no write access to a row can therefore learn its lifecycle state from the *error message*
(e.g. "cannot transition from ARCHIVED") — an unauthorized read via an error channel.
**Fix:** reorder `save()` so permission + row-scope enforcement precede any use of the previous record's
data. Keep the previous-record *fetch* where it is if enforcement needs it — it is the **use** of that
data in validation-before-authorization that leaks.
**Verify (runtime, per rule 4):** add to `RowLevelAuthorizationAttackTest` — user B attempts a write to
user A's row in a lifecycle state that would fail validation, and assert the exception is
`ROW_SCOPE_DENIED`, **not** a lifecycle error, and that its message contains no state name.
**Gates:** GATE-KERNEL + GATE-H2.

### 1.2 REG-42 — `query()`'s `total`/`hasMore` leak the count of out-of-scope rows · MED
**Source:** LNCH13-F3 · same doc
**Defect:** `ConceptGateway.query()` computes `total`/`hasMore` **before** row-scope filtering, so a
caller learns how many rows exist outside their `access.read` scope. Already noted as a pagination
"known limitation" in `docs/ROW_LEVEL_AUTHORIZATION.md` — this promotes it from limitation to defect.
**Fix (choose one, record which and why):**
(a) push the row-scope predicate into the count query so `total` counts only visible rows — correct and
efficient, but requires the scope expression to be expressible in SQL for every adapter; or
(b) compute `total` from the filtered result set, accepting the paging cost.
**Do not** simply omit `total` — pagination UIs depend on it.
**Verify:** extend `userBQueryNeverReturnsUserARow` — assert `page.total() == 1` (only B's row), not 2.
**Gates:** GATE-KERNEL + GATE-H2 + GATE-PG (both store adapters).

### 1.3 REG-36 — unbounded idempotency key · MED
**Source:** REG16K-F1 (Round 1) · `docs/REG16_KERNEL_EXECUTION_ADVERSARIAL_REVIEW.md`
**Defect:** a caller-influenced idempotency key (via a model author's `idempotencyKeyField`) is stored
unbounded, while the cached success value is already bounded by
`KernelRunner.IDEMPOTENCY_RESULT_MAX_CHARS`. An oversized key is a storage/DoS vector.
**Fix:** bound the key symmetrically — SHA-256 hex when the resolved value exceeds a small threshold.
**Verify:** a key far over the threshold is digested; two distinct oversized keys must not collide into
one idempotency record (assert distinct outcomes, not just distinct strings).
**Gates:** GATE-KERNEL (+ GATE-PG for the JDBC store).

### 1.4 REG-37 — circuit-breaker failure counter is not atomic · MED
**Source:** REG16K-F2 (Round 1) · same doc
**Defect:** `KernelRunner.onCapabilityFailure` does a plain get-then-put with no CAS or lock, in **both**
`InProcCircuitBreakerStateStore` (bare `ConcurrentHashMap`) and `JdbcCircuitBreakerStateStore` (blind
`UPDATE … SET consecutive_failures = ?` computed client-side). Concurrent failures undercount, so the
breaker opens late — it degrades a resilience guarantee under exactly the load it exists for.
**Fix:** in-proc → `compute`/`merge` (atomic); JDBC → `UPDATE … SET consecutive_failures =
consecutive_failures + 1` computed **in SQL**, or a CAS on a version column.
**Verify:** concurrent failure recording from N threads yields exactly N (RED before the fix — a plain
get-then-put reliably undercounts under contention).
**Gates:** GATE-KERNEL + GATE-PG.

---

## 2. Rounds 3–6 — the remaining unreviewed surfaces

Order is by blast radius. **One per session.**

| Round | Surface | Why it ranks here | Primary attack questions |
|---|---|---|---|
| **3** | **Generator codegen output** — the emitted app code, not the emitter | A flaw here is reproduced into **every** generated app; Round 2's CRITICAL was exactly this shape (a template conditional, not a runtime bug) | Where does emitted code interpolate model-author strings into SQL/JPQL/HTML/JS? Any other `{{#hasX}}/{{^hasX}}` pair where the *guard* lives in only one branch? Does every generated endpoint route writes through the gateway? |
| **4** | **Flow/await orchestration + `DefaultProcedureExecutor`** | Newest execution surface, least battle-tested; durable `await` resume is the risky part | Can a resumed flow run under a *different* actor's context than the one that suspended it? Is the resume token unguessable and single-use? What authorizes a resume? Is loop iteration bounded (`maxLoopIterations`) under adversarial input? |
| **5** | **Durable-state Postgres adapters' own SQL** | Hand-written SQL across several adapters, each a potential injection/tenant-scoping hole | Is every statement parameterised? Is `tenant_id` in the WHERE of *every* read and the key of every write? Any string-concatenated identifier reachable from model-author input? |
| **6** | **Export/PDF path** | Renders untrusted content; classic SSRF/traversal/exhaustion surface | Can content cause an outbound fetch (SSRF)? Is the output path attacker-influenceable (traversal)? Is there a size/time bound? Does export honour row-level `access.read` scope, or export everything? |

### 2.1 Per-round procedure (unchanged from Rounds 1–2)
1. **Scope in writing first** — name the exact classes/files. Anything unnamed is out of scope this round.
2. **Attack-first.** For each mechanism: *what input does an attacker control, and what does the code do
   with it?* Reuse the standing four: tenant-scoping (is tenant part of the **key**, not just a filter?),
   confused deputy (can request data choose *which* code path runs?), atomicity (check-then-act races?),
   partial failure (is state left readable/writable by the wrong actor?).
3. **Write `docs/REG16_<SURFACE>_ADVERSARIAL_REVIEW.md`** following the existing two: headline verdict,
   per-mechanism analysis, findings `F1..Fn` each rated with a concrete attack.
4. **Triage** per rule 3. File MEDIUMs as dated `REG-nn` rows in the register's Rounds table.
5. **Runtime-prove any fix** (rule 4).
6. Update §3.10's "Rounds not yet done" list. Commit `docs(REG-16-resid): round <n> — <surface>`.

### 2.2 Definition of done for REG-16-resid
All six rounds have a findings document; every CRITICAL/HIGH is remediated **with a runtime test**; every
MEDIUM is either fixed or a dated register row; §3.10 lists no surface under "Rounds not yet done".

---

## 3. Suggested sequence

| # | Work | Rationale | Effort |
|---|---|---|---|
| 1 | **§1.1 REG-41** + **§1.2 REG-42** | Same file, same subsystem, same test class — one focused session while the Round-2 context is fresh | M |
| 2 | **§1.4 REG-37** then **§1.3 REG-36** | Both kernel-resilience; REG-37 first (it degrades a guarantee under load; REG-36 is a storage bound) | M |
| 3 | **Round 3** — codegen output | Highest blast radius of the unreviewed surfaces, and Round 2 proved this shape of bug is live | M |
| 4 | **Round 4** — flow/await orchestration | Newest, least-tested execution surface | M |
| 5 | **Round 5** — Postgres adapter SQL | Mechanical but broad | M |
| 6 | **Round 6** — export/PDF | Smallest blast radius of the four | S/M |

**Do not compress this into fewer sessions.** The programme's value comes from depth per surface; the
one CRITICAL found so far surfaced because Round 2 looked closely at a single thing.
