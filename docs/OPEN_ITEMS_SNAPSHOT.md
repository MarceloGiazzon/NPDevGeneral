# Open Items — verified snapshot

> **As of:** 2026-07-25 · **HEAD:** `e612ec8` + this commit · **Branch:** `beta1-vision-spine`
> **Every row below was verified against the live tree**, not copied from a summary table. The register's
> own index is now machine-checked against its detail sections by
> `scripts/quality/check-register-consistency.py` (wired into `run-ai-knowledge-gate.ps1` step 1/4).
>
> **This file is a point-in-time snapshot, not a second source of truth.** The register
> (`NPDEV_OPEN_ITEMS_REGISTER.md`) remains authoritative; if they disagree, the register wins and this
> file is stale. It exists to answer "what is actually left?" at a glance without reading 1,300 lines.

---

## 1. Genuinely open — blocking a "stable and complete" release

| # | Item | Type | Sev | Where | Note |
|---|---|---|---|---|---|
| 1 | **REG-16-resid Round 3** — generator **codegen output** | SECURITY REVIEW | **HIGH** | `REG16_RESID_COMPLETION_PLAN.md` §2 | Zero adversarial review. A flaw here reproduces into **every** generated app. Round 2 found a CRITICAL in the first surface it examined properly — the base rate argues this cannot be skipped |
| 2 | **REG-16-resid Round 4** — flow/`await` orchestration + `DefaultProcedureExecutor` | SECURITY REVIEW | **HIGH** | same §2 | Zero review. Newest execution surface; durable-resume identity/authorization is the risky part |
| 3 | **REG-16-resid Round 5** — durable-state Postgres adapters' own SQL | SECURITY REVIEW | **HIGH** | same §2 | Zero review. Hand-written SQL: injection + tenant-scoping |
| 4 | **REG-16-resid Round 6** — export/PDF path | SECURITY REVIEW | **HIGH** | same §2 | Zero review. Untrusted-content rendering: SSRF / traversal / exhaustion; does export honour `access.read` scope? |
| 5 | **REG-37** — circuit-breaker failure counter is not atomic | BUG | MED | `REG16_RESID_COMPLETION_PLAN.md` §1.4 | Get-then-put with no CAS in **both** the in-proc and JDBC stores; the breaker opens late under exactly the load it exists for |
| 6 | **REG-36** — unbounded idempotency key | BUG | MED | same §1.3 | Caller-influenced key stored unbounded while the cached *value* is already bounded; storage/DoS vector |

**That is the whole blocking list — and all six close in 3 sessions**, not 6. See
**[`ONE_PLAN_CLOSE_EVERYTHING.md`](ONE_PLAN_CLOSE_EVERYTHING.md)**, the single plan that closes
everything on this page.

The compression is not "batch the reviews anyway". It works because (a) a **mechanical sweep** runs
first across all four surfaces — the bug classes already found here (guard-in-one-branch, swallowed
security exceptions, unparameterised SQL) are *grep-able patterns*, and pattern-matching genuinely
batches; and (b) the four surfaces **pair by threat model** — codegen output + export/PDF are both
"what happens to content on the way out", flow/`await` + Postgres adapter SQL are both "who owns
durable state, and does identity survive it". Reviewing a pair that shares a mental model is more
effective than splitting it, not less.

What does **not** change: a CRITICAL/HIGH still stops the session on the spot, each surface still gets
its own scope list and its own findings document, and if depth runs out you split rather than skim.

## 2. Open but NOT blocking

| # | Item | Type | Note |
|---|---|---|---|
| 7 | **REG-39** healthy-pack **live** control | EVIDENCE | The stale-pack case is fully live-proven. The healthy-pack control's log ended in a Flyway checksum mismatch (a test-procedure artifact — regeneration against a restored DB), so it never reached the check. False positives **are** covered at unit level (`shouldPassWhenIdentityPackCopyHasTokenVersion`, `shouldPassWhenModelHasNoIdentityPackAtAll`). Redo with a fresh empty DB dir — corrected in that run's `SUMMARY.md` |
| 8 | **Conversion hooks: no Postgres live proof** | EVIDENCE | Live-proven on H2 and unit-proven on PG. The docs steer DDL-bearing conversions to Postgres, so the recommended path is the one without a live run |
| 9 | **8 register + 1 roadmap items** with unparseable status phrasing | PROCESS | Named by the new checker (`--verbose`). Not drift — just phrasing it refuses to guess at (e.g. REG-16's "TIER A COMPLETE", where the tier is complete but the item is open). Hand-verify or reword |
| 10 | **REG-16-resid F4** — row-level authz is check-then-act (TOCTOU) | INFO | Rated INFO with honest reasoning (needs a second actor who already has write access; consistent with the platform's opt-in `expectedRowVersion` model). Revisit if CAS becomes the default |

## 3. Closed this session (verified by me, not claimed)

| Item | Evidence I checked |
|---|---|
| **REG-41** — authz ran *after* lifecycle validation, leaking row status via the error channel | Reorder confirmed in `DefaultConceptGateway`; previous-record *fetch* correctly left in place, only its *use* deferred. 18/18 security tests green |
| **REG-42** — `query()`'s `total`/`hasMore` leaked out-of-scope row counts | Re-query gated on `hasRowReadScope`, so unscoped concepts pay nothing. Green on both adapter families |
| **LNCH13-F1** (CRITICAL) — flow-backed CRUD bypassed row-level write authz | Fix unconditional on all three paths (create/update/delete); now has a **runtime** proof that denial happens *and* the flow's side effects never run |
| **G8** — FK/index diffing | Manifest carries FK/index; diff + `ExternallyManaged` both see them; missing-only by design |
| **AW-P2, REG-17, REG-6, REG-16 index row, Phase-5 ledger row** | All were closed-but-rendered-open; corrected |
| **BOND-B4** | **Found by the new checker on its first run** — summary said `DONE (2026-07-23)`, detail still said `PARTIAL` from 2026-07-12. Detail was stale; corrected |

## 4. Not gaps — deliberate boundaries

17 accepted non-goals with rationale, workaround and revisit trigger: **[`ACCEPTED_BOUNDARIES.md`](ACCEPTED_BOUNDARIES.md)**.
Check there before filing anything as a gap.

---

## 5. The honest headline

Four surfaces sit at **zero** adversarial review, and the one surface that *was* reviewed properly
yielded a CRITICAL authorization bypass that had been shipping. Until Rounds 3–6 are done, the accurate
statement is **"no known CRITICAL issues in the reviewed surfaces"** — not "secure". Everything else on
this list is either a contained MEDIUM or an evidence-tidiness item.
