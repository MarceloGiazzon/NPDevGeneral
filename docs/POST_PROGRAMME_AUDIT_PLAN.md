# Post-programme audit — findings + implementation plan

> **Written:** 2026-07-25, after `ONE_PLAN_CLOSE_EVERYTHING.md` completed (HEAD `2f81695`).
> **Audience:** an executor with limited autonomy. Every fact was verified against the live tree —
> I ran all five gates myself and read the code, I did not take the summary on trust.
> **If reality does not match this document → STOP and report.**

## 0. Verification result — the programme's claims hold

| Claim | Verdict |
|---|---|
| 6 commits, working tree clean | ✅ `0116353`…`2f81695`, only untracked `build.gradle` (generated) |
| All five gates green | ✅ **I ran them**: GATE-H2, GATE-PG, GATE-KERNEL, GATE-GEN, GATE-AI |
| R3-F2 HIGH fixed with a runtime proof | ✅ Both halves: `userBCannotAuthorizeAWriteAgainstUserARow` (→ `ROW_SCOPE_DENIED`) **and** `authorizeWriteAllowsTheRowsOwnerAndPersistsNothing` — the second would catch a lazy deny-everything implementation |
| `authorizeWrite` defaults to **deny** | ✅ Default throws `AUTHORIZATION_UNAVAILABLE`. Genuinely fail-closed |
| Register checker at 0 unparseable / 0 contradictions | ✅ 17 + 19 cross-checked, both documents clean (was 12+18 with 9 unparseable) |
| Sweep self-test | ✅ 10/10 fixtures |
| REG-44/45/46 genuinely filed | ✅ All three are dated register rows with findings-doc references |

**One thing is better than reported:** the new `enforceBondMembershipWrite` calls `authorizeWrite`
**outside** `{{#kernelControlled}}` and throws if the gateway is null — so the newest write path is
fail-closed even when the older CRUD paths are not. That is the pattern to propagate (see F2).

---

## 1. Findings from this audit

| # | Finding | Type | Sev | Status |
|---|---|---|---|---|
| **F1** | **The sweep has ~307 un-allowlisted hits and no closure loop.** Rounds 3–6 were supposed to consume the routed hits; the rounds are done, but resolved hits were never written back to the allowlist. The sweep's own doc states the rule ("when a routed hit is resolved, add its fingerprint to the allowlist with the reason") — it just was not executed at the end. Next person to run it sees 307 "new" and learns nothing | PROCESS | **MED** | NEW |
| **F2** | **REG-44's description understates its blast radius.** The register says it "silently voids `access.write`". It also voids **every coarse CRUD permission check** — READ, LIST, CREATE, UPDATE, DELETE — across **13 call sites** in `service-base.mustache`, all gated on `{{#kernelControlled}}`. Row-level `access.read` does survive (gateway-enforced), which is what makes it hard to notice. **Severity MED is fair** (`crud.kernelControlled` defaults to `true` and no model in the repo sets it false), but the description should match the code | DOC / BUG | MED | NEW |
| **F3** | **Allowlist/doc count mismatch.** `SECURITY_PATTERN_SWEEP_2026-07.md` says 32 hits were triaged safe; the allowlist file has 6 top-level entries and the sweep matches ~48 of 355. Either fingerprints drifted when the code changed, or the count is wrong. Unreconciled, the allowlist silently decays | PROCESS | LOW | NEW |
| **F4** | **REG-44** — `crud.kernelControlled: false` voids authorization | BUG | MED | Open (theirs) |
| **F5** | **REG-45** — flow resume is tenant-scoped but not actor-scoped | BUG | MED | Open (theirs) |
| **F6** | **REG-46** — `PersistenceCapabilityContract` has no tenant parameter | BUG | MED | Open (theirs) |
| **F7** | **GATE-GEN packaged-app proofs are load-flaky** | PROCESS | LOW | Open (theirs). **I did not hit it this run**, consistent with their "load-flaky" diagnosis |
| **F8** | **REG-16-resid F4 — row-level authz is check-then-act (TOCTOU)** | INFO | — | Open by choice (theirs) |

Nothing else surfaced: no TODO/FIXME introduced, no new deferrals, no drift in either ledger.

---

## 2. Implementation plan

### 2.0 Rules (apply to every task)
- **Never `git add .`** — stage by explicit path. No pushing, no branch switching.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Pre-commit: `rm -rf NPDevRuntimeHost/runtime-data && pwsh -File scripts/hygiene/clean-workspace-state.ps1`
- **Changed kernel/adapter Java? Restage jars or tests silently run stale:**
  ```bash
  pwsh -File scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir 'D:/WorkSpace/NPDev/Build/runtimehost-libs'
  ```
  **Quoted, forward slashes.** In Git Bash `D:\WorkSpace\...` loses its backslashes and the script
  writes jars *inside the repo* while tests keep reading a day-old jar.
- Gates: **H2** `cd NPDevRuntimeHost && ./gradlew test --tests "com.finalexec.*" -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs` ·
  **PG** (+ `--tests "com.finalexec.db.*PostgresProofMatrixTest" -PincludePostgresMatrix`, Docker up) ·
  **KERNEL** `cd NPDevKernel && ./gradlew test` · **GEN** `cd NPDevGenerator && ./gradlew :generator:test` ·
  **AI** `pwsh -File scripts/quality/run-ai-knowledge-gate.ps1`
- **STOP and report** if: a gate is red for a reason you did not cause · a fact here proves false ·
  a task turns out to need a product/policy decision (F4–F6 already are — do not improvise one).

### 2.1 TASK 1 — close the sweep's loop (F1 + F3) · ~1 hour · **do this first**
The sweep is currently a 307-hit noise generator. Its value is entirely in the *"new"* count trending
to zero; at 307 nobody will read it, and a real hit will hide in the noise. That is the exact
failure mode its own design warned about.

1. Run `python scripts/quality/security-pattern-sweep.py --verbose > /tmp/sweep.txt`.
2. Work **pattern by pattern**, not hit by hit. For each of the five patterns, group hits by the
   *reason* they are safe, and write ONE allowlist rule per reason (the allowlist is fingerprint-keyed
   and reason-carrying — an entry without a reason is worse than no entry).
3. The 13 `conditional-guard-no-else` hits are **all REG-44** — one root cause, 13 sites. Allowlist
   them with the reason `"REG-44: every authz call in service-base.mustache is gated on
   {{#kernelControlled}} — tracked as one item, not 13"`, so they stop re-reporting and REG-44 stays
   the single place it is tracked.
4. Reconcile F3: if the doc's "32 safe" no longer matches the file, correct **whichever is wrong** and
   say which in the commit. If fingerprints drifted because code changed, re-triage those hits.
5. Update `SECURITY_PATTERN_SWEEP_2026-07.md` §4 to say the routing is **consumed** (rounds 3–6 are
   done) and record what each round resolved.
6. **DoD:** `--new`-count is small enough that a human will read it (target: under 20), every allowlist
   entry has a reason, GATE-AI green. **Do not allowlist anything you have not actually reasoned about**
   — a false "safe" is worse than a noisy hit.

### 2.2 TASK 2 — fix REG-44 (F4 + F2) · ~2 hours · needs one decision from the owner
**Ask first, then implement.** The decision: should `access.read`/`access.write` + `kernelControlled:
false` be a **compile error** or a **warning**? Recommend the compile error — a silently unenforced
security declaration is the worst of both worlds, and nothing in the repo relies on the combination.

Then:
1. **Validation** (`SemanticValidator`): reject (or warn on) a model declaring `access.*` with
   `crud.kernelControlled: false`. RED-first: a model with both must fail generation with a message
   naming the concept and both settings.
2. **Correct the register** (F2): REG-44's row currently says only "voids `access.write`". Restate it
   as: *voids every coarse CRUD permission check (READ/LIST/CREATE/UPDATE/DELETE) and row-level
   `access.write`, across 13 call sites; row-level `access.read` survives because the gateway enforces
   it.* Keep severity MED and say why (defaults `true`, no in-repo model sets it false).
3. **Optional, better:** follow `enforceBondMembershipWrite`'s pattern — move the row-level
   `enforceWithConceptGateway` calls **outside** `{{#kernelControlled}}` so writes are authorized
   regardless of the flag, leaving only the coarse role check gated. Guard with GATE-GEN + the
   `RowLevelAuthorizationAttackTest` suite.
4. **Gates:** GEN (validation), H2 + KERNEL (if templates change), AI (register consistency).

### 2.3 TASK 3 — REG-45 and REG-46 · **owner decisions, not patches**
Do **not** implement these without an explicit answer. Present the options and stop:
- **REG-45** (flow resume is tenant- but not actor-scoped): should resuming another user's suspended
  flow require the *originating actor*, or an override permission, or stay as-is with `RESUME_EXECUTIONS`
  being the whole gate? Each is defensible; the current behaviour is only wrong if the product says so.
- **REG-46** (`PersistenceCapabilityContract` has no tenant parameter): fixing it is a **breaking change
  to a published port**. Options: (a) add the parameter and version the port, (b) resolve the tenant
  from ambient context inside the adapter, (c) accept and document. This needs an API-contract call.

### 2.4 TASK 4 — GATE-GEN flakiness (F7) · ~30 min
`TrustedSourceEmitter…RuntimeProofTest` / `HardenGcDeleteReplaceCascade…RuntimeProofTest` boot two
Spring Boot apps in parallel forks and intermittently fail with *"Packaged app did not become healthy
on port N"* under load. **Not a regression** — passes on isolated re-run, and passed in my run.
Fix: raise the health-check timeout, or force these two tests onto a single fork
(`maxParallelForks = 1` for that test group). **DoD:** three consecutive full GATE-GEN runs green.

### 2.5 TASK 5 — leave F8 alone
REG-16-resid F4 (TOCTOU) is INFO with honest reasoning: it needs a second actor who *already* has
write access, and it matches the platform's opt-in `expectedRowVersion` model. **Revisit only if CAS
becomes the default.** Record the trigger; do not "fix" it now.

---

## 3. Suggested order

| # | Task | Why here |
|---|---|---|
| 1 | **§2.1** sweep closure loop | The tool is unusable at 307 hits; every later security pass depends on it being readable |
| 2 | **§2.2** REG-44 (after the owner's error-vs-warning call) | The only open item that is an actual security footgun |
| 3 | **§2.4** GATE-GEN flake | Cheap; stops burning a diagnosis cycle per occurrence |
| 4 | **§2.3** REG-45 / REG-46 | Blocked on owner decisions — raise them early, implement whenever answered |

## 4. The honest position after this audit

The programme's headline — *"every launch surface has had an adversarial review; no CRITICAL known;
one HIGH found and fixed"* — **is supported by the evidence.** I verified the gates, the fail-closed
default, and both halves of the runtime proof myself.

What this audit adds is smaller but real: **the sweep that found REG-43 is now too noisy to be read**,
and **REG-44 is described more narrowly than it behaves**. Neither is a live exposure — REG-44 defaults
safe and no model opts out — but both erode the tooling the next reviewer will depend on. The pattern
worth noticing is that both are *maintenance of the safety net*, not new holes: after a large security
programme, the thing most likely to rot first is the instrument you built to find the problems.
