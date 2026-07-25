# Open Items — verified snapshot

> **As of:** 2026-07-25 (end of the `ONE_PLAN_CLOSE_EVERYTHING.md` programme) · **Branch:** `beta1-vision-spine`
> **Every row below was verified against the live tree**, not copied from a summary table. The register's
> own index is machine-checked against its detail sections by
> `scripts/quality/check-register-consistency.py`, and the security pattern sweep is machine-checked
> against the real historical bugs it claims to catch by `security-pattern-sweep.py --self-test` —
> both wired into `run-ai-knowledge-gate.ps1`.
>
> **This file is a point-in-time snapshot, not a second source of truth.** The register
> (`NPDEV_OPEN_ITEMS_REGISTER.md`) remains authoritative; if they disagree, the register wins and this
> file is stale.

---

## 1. Genuinely open — blocking a "stable and complete" release

**Empty.**

All six items that stood here on 2026-07-25 morning are closed:

| Was | Outcome |
|---|---|
| REG-16-resid **Round 3** — generator codegen output | **DONE** — found a **HIGH** (R3-F2), fixed with a runtime proof |
| REG-16-resid **Round 4** — flow/`await` orchestration | **DONE** — no CRITICAL/HIGH |
| REG-16-resid **Round 5** — durable-state adapter SQL | **DONE** — no CRITICAL/HIGH, zero injection findings |
| REG-16-resid **Round 6** — export/PDF | **DONE** — no CRITICAL/HIGH, 3 findings all fixed in-round |
| **REG-37** — circuit-breaker counter not atomic | **CLOSED** — atomic in both stores, concurrency-proven RED→GREEN |
| **REG-36** — unbounded idempotency key | **CLOSED** — bounded symmetrically, proven on a real Postgres |

**REG-16 itself is now closed**, since its residual programme has no unreviewed surface left.

## 2. Open but NOT blocking

| # | Item | Type | Sev | Note |
|---|---|---|---|---|
| 1 | **REG-44** — `crud.kernelControlled: false` silently voids `access.write` | BUG | MED | A model can declare `access.write` and disable the only thing that enforces it, and compile clean. `access.read` *stays* enforced, which is what makes it hard to notice. Fix is cheap; whether it should be a compile error or a warning is a product decision |
| 2 | **REG-45** — flow resume is tenant-scoped but not actor-scoped | BUG | MED | Any holder of `RESUME_EXECUTIONS` can resume another user's suspended flow and receive its accumulated state. Needs a policy decision (require the originating actor? an override permission?) before it can be fixed |
| 3 | **REG-46** — `PersistenceCapabilityContract` has no tenant parameter | BUG | MED | The flow-step persistence route is unscoped while generated CRUD is tenant- **and** row-scoped. Fixing it is a breaking change to a published port |
| 4 | **REG-16-resid F4** — row-level authz is check-then-act (TOCTOU) | INFO | — | Rated INFO with honest reasoning; revisit only if `expectedRowVersion` CAS becomes the default |
| 5 | **REG-47** — unbounded caller-supplied `correlationId` in index key material | BUG | MED | REG-36's failure mode on a different key: only `trim()`ed, then written into `TEXT` columns that are btree index key material across **8 indexes in 4 tables**, including the primary key of `npdev_correlation_owner`. **Found by the sweep-closure pass**, in the one hit group Rounds 3–6 never systematically covered. Recommended fix is to *reject* (400), not digest — unlike an idempotency key, a correlation id has no legitimate oversized form, and digesting would silently change an id the caller later looks up on several endpoints |

REG-44/45/46 are **filed rather than improvised**: each needs a decision (product, policy, or
API-contract) that a review round is the wrong place to make. REG-47 is filed because it was found
after the programme closed, and its fix — reject vs. digest — is a small contract choice worth stating
before implementing.

## 3. Closed by this programme — verified, not claimed

| Item | What was actually proven |
|---|---|
| **R3-F2 (HIGH)** — m2m bond endpoints had **zero** authorization | Four HTTP endpoints per bond, in every generated app, with no permission check, no row-level gate, no tenant predicate and no audit. New `ConceptGateway.authorizeWrite` (defaulting to **deny**); RED→GREEN behaviourally on both adapter families *and* structurally (4/4 RED pre-fix) |
| **R3-F1 (MED)** — XSS sinks in the generated UI | `text()` is a null-coalescer, not an escaper, and fed `innerHTML`. Sink removed rather than escaped; test asserts against the **emitted asset** and whitelists the one safe `innerHTML` form |
| **R6-F2 (MED)** — CSV formula injection | The only finding here whose impact **crosses users**. Neutralized without corrupting negative numbers |
| **R6-F1 (MED)** — PDF export had no total-row bound | Its javadoc claimed a bound that did not exist. Now capped and 413-rejected |
| **R6-F3 (LOW)** — renderer fetched external resources | SSRF confirmed **real** against a live local HTTP server, then closed |
| **REG-43 (MED)** — silent fail-open tenant gate | Found by the new sweep on its first run. Missing-table still fails open; anything else now fails closed and logs |
| **REG-36 / REG-37** | See §1. REG-36's own write-up was corrected in the process (the Postgres btree limit bites *after compression*) |
| **REG-7 / REG-8** | Found closed-but-unstruck by the improved register checker |
| **REG-39 healthy-pack live control** | Redone on a genuinely fresh DB: `Tomcat started`, zero `StartupValidator` failures |
| **Conversion hooks on Postgres** | Live-proven end to end on a real container: real row counts, hook-claim matching, DDL, data conversion (1999¢→$19) and the `HOOK_APPLIED` audit row |
| **9 unparseable-status items** | Now **zero**, in both documents — seven were checker blind spots, and fixing them exposed two real drifts |
| **Sweep triage loop closed** | Was 307 permanent "new" hits — the exact noise failure its own design warned about. Now **355 hits, 355 cleared, 0 new**, every entry carrying a reason and grouped by root cause rather than by site |
| **GATE-GEN packaged-app flake** | Health-check deadline 2 → 6 min with a diagnostic failure message. Root cause was fork contention (two Spring Boot apps booting at once), not the app. **Three consecutive full GATE-GEN runs green** |

## 4. Not gaps — deliberate boundaries

17 accepted non-goals with rationale, workaround and revisit trigger: **[`ACCEPTED_BOUNDARIES.md`](ACCEPTED_BOUNDARIES.md)**.
Check there before filing anything as a gap.

---

## 5. The honest headline

The previous version of this page said:

> *"Four surfaces sit at zero adversarial review… Until Rounds 3–6 are done, the accurate statement is
> **'no known CRITICAL issues in the reviewed surfaces'** — not 'secure'."*

**Rounds 3–6 are done.** Every launch surface has now had an adversarial review with its own scope
list and its own findings document. So the accurate statement changes to:

> **Every launch surface has had an adversarial review.** No CRITICAL issue is known anywhere. One HIGH
> was found — in generated code, reproducing into every app — and was fixed with a runtime proof rather
> than deferred. Four MEDIUMs remain open: three need a decision rather than a patch, and REG-47 was
> filed after the programme closed.

That is a materially stronger claim than the one this page carried this morning, and it is the one the
work supports — no more.

**What it still does not mean.** An adversarial review is a competent read by someone actively trying
to break the thing; it is not a proof of absence. The single most instructive result of this programme
is that Round 3 found a complete authorization bypass on a write surface that had been shipping — in
code adjacent to a CRITICAL fixed only days earlier. The base rate for "one more careful look finds
something" is not yet zero.

**Confirmed again immediately afterwards.** Closing the security sweep's triage loop — pure maintenance,
no new review — turned up **REG-47**, in the one hit group the six rounds never systematically covered.
It was found only because those 29 hits were not waved through as "same class as REG-36, already
fixed". Two lessons worth keeping: the instrument built to find problems is the thing most likely to
rot first, and *the group a reviewer is most tempted to bulk-clear is the group nobody has read*.
