# Remainder closure plan — ledger truth, blind spot #8, deferred residuals, decision briefs

> **STATUS: HISTORICAL** (corrected 2026-07-29, docs/REMEDIATION_PLAN.md R-P2) — last changed
> 2026-07-27; its completion state has been cross-checked, not re-run: Phase 1 (ledger truth) and
> Phase 2 (the instrument, blind spot #8) shipped as `scripts/quality/check-narrative-status-drift.py`,
> which exists and runs; every Phase 3 item (REG-52, REG-53, the REG-49 residual, the REG-51 residual)
> is `status: DONE` in `ledger/items/*.yml`; Phase 4's D4 decision is answered (REG-17, DONE) and C1
> (repo visibility) was resolved public per `docs/archive/programme-history/...` history. Phase 5 was
> explicitly conditional/not-scheduled by this plan's own design, not a completion gate. Treat nothing
> here as an open commitment: check `docs/OPEN_ITEMS.md` (authoritative) before acting on any item.
>
> **Original banner, 2026-07-27:** Promoted from
> `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-ai-review\PLAN_REMAINDER_CLOSURE_2026-07-27.md`
> on owner approval ("full implement"). Same flow as `archive/programme-history/EXTERNAL_AI_DELEGATION_PLAN.md` /
> `archive/programme-history/REG48_50_CLOSURE_PLAN.md`.
>
> Baseline: `97a2491` on `beta1-vision-spine`, pushed. Register: 47 REG rows, **zero open**.

**Scope.** Everything in the "what remains" survey that does **not** require owner authority — plus
written decision briefs for the four that do. Nothing here marks an owner decision as made.

---

## 0. What this plan is really about

Two of the five categories are ordinary engineering. The interesting one is **blind spot #8**, which
has now appeared **twice in 24 hours**:

| Instance | Where | The stale claim | Caught by |
|---|---|---|---|
| 1 | `NPDEV_OPEN_ITEMS_REGISTER.md` §REG-48…52 intro | "REG-50 … **remains OPEN**, pending an owner decision" — 10 lines above its own `~~REG-50~~` **DONE** row | a human reading it (me), not the checker |
| 2 | `docs/adr/ADR-0009-external-ai-delegation.md` header | "**DRAFT — 2026-07-26** … D3, D4, D5 remain pending" — while its own decision table says **D3 ANSWERED 2026-07-27: approved** and its decision block says APPROVED WITH CONDITIONS | a human reading it, not the checker |

`check-register-consistency.py` passed both with **exit 0, "0 contradictions"** — correctly, by its
own contract: it parses **summary rows** (`| **REG-nn** |`) and **detail sections**
(`### N.N REG-nn — …`). A status claim made in a **narrative paragraph** is invisible to it.

Two independent occurrences in a day, one of them in the governance document that authorizes egress,
makes this systemic rather than a slip. It is the same family as blind spots #1–#7 — *the instrument
verified the shape it declared, over a scope that excluded where the drift actually lives.*

---

## 1. Phase 1 — Ledger truth *(zero risk, ~1 hour)*

| # | Task | Detail |
|---|---|---|
| **1.1** | Fix the ADR-0009 header | Status `DRAFT — 2026-07-26` → its real ratified state; delete "D3, D4, D5 remain pending" (D3 answered 2026-07-27 in its own table); the header also still says the plan is "currently at `…__OutsideRepo\`, promoted on owner approval" — it *is* promoted. **Do not touch the decision block** — it is correct |
| **1.2** | File **REG-53** — schema-diff `maxLength` narrowing | Currently exists only in a chat message. **Verify against live code before filing a status** — the REG-49 lesson: a claim confirmed by re-reading the artefact that produced it is not confirmed. Establish whether narrowing is (a) undiffed entirely, (b) diffed but unclassified, or (c) deliberately out of `SchemaDiffEngine`'s missing-only scope like FK/index (G8) |
| **1.3** | Resolve the **struck-but-open** convention collision | REG-52 is `~~struck~~` — this register's convention for *closed* — while its text says "FILED, not fixed". Precedent exists: the checker deliberately treats `COMPLETE` as **not** a closure keyword. Do the same for `FILED`, and un-strike REG-52's row |

---

## 2. Phase 2 — The instrument (blind spot #8) *(the real work)*

**Do not** add a naive status-keyword scan over prose. "A gate that cries wolf gets bypassed" is this
project's own lesson #4, and narrative text is full of legitimate status words.

### 2.1 · Design — a claim needs *both* an id and a verdict, in one sentence

> **Rule P1 (per-id prose claim).** Inside a document that owns id rows, a **sentence** containing
> both an id (`REG-nn`, `D-n`, `LNCH-nn`) **and** a status keyword must agree with that id's
> authoritative row.
>
> **Rule P2 (document self-status).** A document's declared header status must not contradict its
> own internal decision/approval block.

Requiring **both** signals is what keeps the false-positive rate down: prose that merely *discusses*
an item never fires; only prose that *asserts its state* does.

### 2.2 · False-positive suppression — historical narration is legitimate

The register deliberately records history ("REG-49 **turned out to be** a false positive", "this row
**previously read** zero adversarial review"). Suppress a claim when its sentence carries a past-tense
or superseded marker: `was`, `were`, `previously`, `originally`, `until`, `no longer`, `had been`,
`turned out`, `is now`, `corrected`. Same discipline as the existing plan-tense markers — and the
exclusion list is itself an asserted, tested artefact, not an ad-hoc regex.

### 2.3 · **Calibrate before trusting it** *(non-negotiable — P4's lesson)*

A detector's silence means nothing without a control:

| Control | Expectation |
|---|---|
| Register intro at `97a2491^` (pre-fix) | **must fire** on "REG-50 … remains OPEN" |
| `ADR-0009` header as of today | **must fire** on "D3 … remain pending" |
| Whole `docs/**` corpus at HEAD after Phase 1 | measure the false-positive count |

If it cannot catch the two instances that motivated it, it does not ship. If the corpus FP rate is
high, narrow the rule — do not relax the controls.

### 2.4 · Ship it **reporting, not blocking**, at first
Per lesson #4 and the `security-pattern-sweep.py` precedent: report + exit 0 for one cycle, promote to
blocking once the FP rate is observed at zero on a clean tree.

---

## 3. Phase 3 — Deferred engineering residuals

| # | Item | Work | Verification |
|---|---|---|---|
| **3.1** | **REG-52** | Make `TenantIsolationPolicy.STRICT_EQUALS.normalize()` lowercase, matching `ExecutionContext.normalizeTenantId()`'s REG-25 canonicalization | RED-first on the specific bypass path — a `sameTenant` call fed a tenantId that never went through `ExecutionContext`'s constructor. GATE-KERNEL |
| **3.2** | **REG-53** | Scope decided by 1.2's finding. If genuinely undiffed: narrowing must reach the Impact Report as `MANUAL_REVIEW`, like every other narrowing | RED-first: a model narrowing a string field must produce a diff item; both engines |
| **3.3** | **REG-49 residual** | Automated behavioural test of the delete-flow arm. Blocked previously on wiring `GeneratedCrudRuntimeSupport`'s `PermissionEvaluator` — that wiring is the task | **Behaviour, not shape** (lesson #2): assert the denial *propagates out of* `delete()` before `enforceWithDeleteFlow` runs, not that a call is emitted |
| **3.4** | **REG-51 residual** | Gate check flagging *existing* run records whose pack provenance is unresolved | Extends `run-external-ai-gate.ps1`; defence-in-depth behind the build-time refusal that already ships |

**Sequencing note:** 3.1 and 3.2 are independent; 3.3 is the largest (test-harness wiring, not logic);
3.4 is small and can ride with Phase 2's gate work.

---

## 4. Phase 4 — Decision briefs *(AI drafts, owner rules — never both)*

One brief per decision: options, consequences, what each unblocks, and a recommendation. Written to
`docs/archive/programme-history/DECISION_BRIEFS_2026-07.md`, each ending in an unfilled owner-verdict line.

| # | Decision | The options, in brief |
|---|---|---|
| **C1** | **The repo is private** | (a) make it public — note this is arguably just *executing* ADR-0007, which already ratified Apache-2.0 source-first distribution, so the current private state is a **ratified decision not carried out**; (b) scoped read access / a published snapshot mirror for reviewers; (c) accept that REG-17 and every external-review premise stay structurally capped. **Recommend (a) or (b)** — this one item silently blocks M4, REG-17 and the whole external-review rationale |
| **D4** | REG-17 DoD ruling | (a) automated external repro (GH runners, achieved) + a blind external-AI operator **closes** it; (b) a literal human third party is still required; (c) deferred until C1 resolves — they are entangled, since a third party cannot clone a private repo |
| **D5** | E5 real participants | (a) permanently open with an honest label; (b) schedule N sessions post-launch; (c) an AI persona walkthrough recorded as **explicitly not a substitute**. ADR-0009 forbids (c) being counted as closure |
| **F8** | Row-level authz TOCTOU | (a) row locking; (b) optimistic CAS/version column; (c) accept with a documented revisit trigger. All three change a **published port**, which is why it was never a patch |

---

## 5. Phase 5 — Unblocked only by a decision *(conditional, not scheduled)*

| Trigger | Then |
|---|---|
| C1 resolved (a) or (b) | Run M4's clean-container half for real; advance REG-17 with the transcript |
| D4 answered | Move REG-17 to its ruled state — closed, or open with the reason recorded |
| D5 answered | Update `POST_BETA0_HUMAN_ACTION_REGISTER.md`'s row to the ruled state |
| F8 answered | Implement the chosen shape RED-first in the port + both adapters |

---

## 6. Order, effort, and what to skip

| Order | Phase | Why here | Effort |
|---|---|---|---|
| 1 | **1.1 + 1.3** | Two stale ledger claims, minutes each. Fix before building a detector *for* them, so the detector's controls run against a clean tree | S |
| 2 | **1.2** | Filing REG-53 is cheap; the verification is the real content | S |
| 3 | **2.1–2.4** | The systemic item. Calibrate, then ship reporting-only | M |
| 4 | **4** | Briefs are cheap and unblock the largest item (C1) — write them early so the answers can arrive while Phase 3 runs | S |
| 5 | **3.1, 3.2, 3.4** | Ordinary engineering, independent of each other | M |
| 6 | **3.3** | Largest; harness wiring | M |
| 7 | **5** | Only on answers | — |

**Explicitly not in this plan:** the 17 accepted boundaries, G8 (FK/index diffing, missing-only by
design), the REG-4 flake class, and "base rate ≠ 0" — each is a recorded design position with a
revisit trigger, not an open defect. The Java `ReviewPackBuilder` provenance scope note stays as
recorded: `product-app` reviews live model/config, a content class never exposed to the stale-emitted-
code hazard, and its golden-hash parity tests pass unchanged.

**Standing rules throughout:** RED before GREEN · both adapters · real Postgres where Postgres
semantics matter · behaviour not shape · findings filed before fixed · no row moves on a re-read of
the artefact that produced the claim · no owner decision recorded as made by anyone but the owner.
