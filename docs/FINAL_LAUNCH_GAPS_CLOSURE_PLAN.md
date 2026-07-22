# Final Launch-Gaps Closure Plan — LNCH-10 Slice 3 (PDF) + LNCH-18 / LNCH-22 (external test)

> **Status:** APPROVED PLAN — not started. **High priority** (owner: Marcelo, 2026-07-22).
> **Written:** 2026-07-22, against `main` at `beta1.1` (dev on `beta1-vision-spine`).
> **Goal.** Close the **last three** non-DONE items on the launch ledger
> (`docs/LAUNCH_READINESS_GAPS.md` §2: 21 DONE · 3 PARTIAL · 0 OPEN) so that ledger reaches
> **24 DONE · 0 PARTIAL**. When this plan is executed to its Definitions of Done, these gaps are
> solved — the DoDs below are written to be airtight and independently checkable, not aspirational.
> **Audience.** An AI implementation session (or human) with no project history. Follow it to the
> letter; the DoDs are the contract.

---

## 0. Read this first — what "solved" means for each gap (do not skip)

The three gaps are **two different kinds of problem**, and conflating them is the one way this plan
fails. Be honest about which is which:

| Gap | Kind | Can "implementing the plan" close it? |
|---|---|---|
| **LNCH-10 Slice 3** (server-side PDF) | Pure engineering | **Yes, fully.** Build the feature to the DoD and it is DONE. |
| **LNCH-18** (a non-author authors an app) | **Evidence-of-independence** | **Only by actually running an independent tester and it succeeding.** The DoD *is* "an outsider did it." The plan makes that run turnkey and defines the exact pass bar + a fix-and-re-run loop; the *act of running it* is the closing step, not the building. |
| **LNCH-22** (a newcomer builds from docs alone) | **Evidence-of-independence** | Same as LNCH-18. Closes only when an independent run succeeds from the docs. |

**Therefore this plan's guarantee is conditional and stated plainly:** Part A (PDF) closes on
implementation. Part B (LNCH-18/22) closes **iff** you run the independent test to a successful
completion — and the plan makes that a concrete, runnable procedure with a defined success bar and a
loop that *drives it to success* (fix the doc/tool gap the outsider hit, re-run cold, repeat). It
cannot be closed by writing more docs and asserting it; it is closed by an outsider succeeding.
This honesty is the point — a plan that promised otherwise would be the exact "claim without evidence"
failure this project's whole discipline exists to prevent.

---

## PART A — LNCH-10 Slice 3: server-side PDF (pure engineering — closes on implementation)

The detailed phased design already exists: **`docs/REG12_DOCUMENT_EXPORT_PLAN.md`.** This section does
two things: (1) **locks the four owner decisions** that plan left open, so implementation is unblocked
with certainty, and (2) restates the **airtight DoD** that closes LNCH-10.

### A.1 Decisions locked (were Q1–Q4 in the REG-12 plan — now settled, revisable only with cause)

1. **PDF library = OpenHTMLtoPDF** (pure-JVM HTML+CSS → PDF, no native/display deps, runs headless on
   the Linux CI runner). Rationale: it consumes the exact print HTML/CSS Slice 2 already produces, and
   it fits the self-hosted / no-native-deps posture (ADR-0007). Accept its CSS-subset limits for v1.
2. **`document` = a new PAGE-style kind** bound to a concept query (like a declared panel), **not** a
   free-form procedure. Declarative, v1.
3. **First target = a printable declared panel** (pick-list / packing-slip shape) — reuses Slice 2's
   template + the CSV data path; highest value for the WMS-migration audience.
4. **Delivery = inline stream** `GET /{document}/render.pdf` (mirrors `export.csv` exactly). Storing to
   the file-store is a trivial later add, out of scope.

### A.2 Execution — follow `docs/REG12_DOCUMENT_EXPORT_PLAN.md` phases P0–P5

With A.1 locked, its P0 collapses to just the **library spike** (prove OpenHTMLtoPDF renders a grid
HTML+CSS to a valid PDF headless on a Linux container — kill-criterion if it needs native deps). Then
P1 (port + `document-render-inproc` adapter), P2 (schema `document` kind, 4-copy mirror + emission),
P3 (runtime wiring — reuse the CSV data path, render Slice 2's print HTML, stream PDF; **add the
`:adapters:document-render-*:jar` to the three packaged-app test adapter lists and the sync list** —
guardrail against the mail-adapter compile break that bit CI), P4 (live verify), P5 (docs + close).

### A.3 Airtight DoD for LNCH-10 (all must hold — this is the checklist that closes it)

- [ ] A real generated FinalApp declares a `document` on a grid and exposes `GET /{document}/render.pdf`.
- [ ] Hitting it streams a **valid PDF** (opens in a PDF viewer) that correctly renders the panel's
      **filtered/sorted** data with page breaks and no app chrome — verified by a human opening the file
      **and** an automated assertion (non-empty, `%PDF` header, expected page count / text extract).
- [ ] The renderer is a **pluggable adapter** (swap = config, no regeneration), unit-tested HTML→PDF.
- [ ] The generated app **still compiles and boots on Linux CI** (the packaged-app proof tests stay
      green after the new adapter jar is added to their lists) — dispatched and observed green via
      `scripts/ci/gh-api.sh`.
- [ ] `docs/DSL_REFERENCE.md` documents the `document` kind; `docs/LAUNCH_READINESS_GAPS.md` §2 flips
      **LNCH-10 → DONE**; the register REG-12 → DONE; evidence recorded under
      `NPDev_General__OutsideRepo/reg12-slice3-evidence/`.

When every box is checked, **LNCH-10 is solved.** No human-gated dependency.

---

## PART B — LNCH-18 + LNCH-22: the single independent-tester run (closes on a successful run)

These two share one run (register §2.5/§2.6 say to combine them). The kit already exists:
**`docs/EXTERNAL_TESTER_COLDSTART.md`.** This part makes the run **executable and airtight**, and — the
critical piece — defines the **success bar** and the **fix-and-re-run loop that drives it to closure.**

### B.1 The tester must be genuinely independent — this is non-negotiable

The DoD is "someone who has never seen the project succeeds." An agent (or person) that has this
conversation's context, or has read the plans/register/retrospective, **cannot** be the tester — its
success proves nothing. Acceptable testers, in order of preference:

1. **A fresh, separate AI-tool session** the owner launches (a new Claude Code / other agent with an
   empty context window), pointed *only* at `docs/EXTERNAL_TESTER_COLDSTART.md` + the repo. This is the
   owner's stated plan ("external AI tool") and the most genuinely cold.
2. **A subagent spawned with ONLY the cold brief as its prompt** (the `Agent` tool), given no other
   context from the driving session. A reasonable approximation of #1 and fully runnable inside a
   session — but the driver must pass it *nothing* beyond the brief + repo access, or independence is
   void.
3. A real human who has never seen NPDev (the original REG-13/14 intent). Slowest; strongest signal.

**Whichever is used, the driver does NOT help the tester mid-run.** Questions the tester asks, dead
ends it hits, assumptions it makes — those are the deliverable. Coaching it invalidates the result.

### B.2 The run — three tasks, each with a binary pass bar

Give the tester exactly what `EXTERNAL_TESTER_COLDSTART.md` specifies, then judge each task by a
**binary, checkable** bar (not "seemed fine"):

| Task | What the tester does | **PASS bar (binary)** | Closes |
|---|---|---|---|
| **A** | Author an app (the issue-tracker brief) via the MCP tools + docs, no source-reading, no help | A **running FinalApp** exists that serves the described concept (create/list/edit/close an issue works over REST or UI) — reached by the tester **unaided** | LNCH-18 |
| **B** | Build the tutorial app from `docs/TUTORIAL_FIRST_APP.md` + `docs/DSL_REFERENCE.md`, **docs only**, no MCP gap-filling | The **tutorial app builds and boots** following the docs alone | LNCH-22 |
| **C** | Clone fresh, get the quality gates to run on the tester's own machine (Linux ideally) | The gates **run** (pass or a triaged real failure); every question the tester had to ask is logged | LNCH-17-adjacent / REG-17 (bonus) |

Each task produces a **friction log** (`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`) — specific friction
points, not "it was fine."

### B.3 The fix-and-re-run loop — this is what *guarantees* closure

A first cold run may **fail** a pass bar (the docs were ambiguous, a tool errored, a step assumed
knowledge). **That is a finding, not a failure of this plan.** The loop:

1. Run the cold tester (B.1/B.2). Collect the friction logs.
2. For each PASS bar **not** met: the blocking friction point is a **real doc/tooling gap**. Fix it
   (improve the doc, the validator hint, the tutorial step, the MCP error message) — file each fix as a
   dated finding in `docs/LAUNCH_READINESS_GAPS.md`, do **not** silently patch.
3. **Re-run with a *new* cold tester** (fresh context — you cannot re-use the one that already learned
   the workaround; that would leak knowledge and void independence).
4. Repeat until a genuinely cold tester **meets all three pass bars from the materials alone.**

This loop **converges by construction**: each iteration removes a real barrier a stranger hit, and the
materials strictly improve. When a cold tester passes without help, the claim "an outsider can do it"
is *proven*, not asserted — which is exactly the DoD. Budget for 1–3 iterations; the tutorial and
MCP-authoring paths are already mature, so the first run may well pass Task A/B outright.

### B.4 Airtight DoD for LNCH-18 and LNCH-22

- [ ] **LNCH-18:** a genuinely independent tester (per B.1) took the issue-tracker app from
      description → **running FinalApp** using only the MCP tools + docs, **unaided** — Task A pass bar
      met. The friction log is recorded.
- [ ] **LNCH-22:** the same (or another) independent tester built the **tutorial app from docs alone**
      — Task B pass bar met. Friction log recorded.
- [ ] Every friction point the successful run still surfaced is filed as a dated finding (docs improve
      even on a pass).
- [ ] Evidence — the tester's transcript + the three friction logs — recorded under
      `NPDev_General__OutsideRepo/external-tester-evidence/<date>/`, with the tester's identity/kind
      stated (fresh session / subagent / human) so the independence claim is auditable.
- [ ] `docs/LAUNCH_READINESS_GAPS.md` §2 flips **LNCH-18 → DONE** and **LNCH-22 → DONE**; register
      REG-13 / REG-14 (and REG-17 if Task C also passed) → DONE.

When these boxes are checked, **LNCH-18 and LNCH-22 are solved** — because an outsider actually did it,
which is the only thing that could ever close them.

---

## Sequencing, effort, and the "definitely done" guarantee

**Order:** Part A and Part B are independent — run in parallel if two sessions are available.
Recommended if serial: **Part B first** (start the cold run early; its fix-and-re-run loop has the
longest wall-clock tail because each iteration needs a fresh tester), and do **Part A** (PDF, ~2–3
focused sessions of bounded engineering) alongside.

**Effort.** Part A: ~2–3 sessions (spike + adapter + schema/emission + wiring + live verify).
Part B: the *engineering* is ~zero (the kit exists); the cost is **running the cold tester and iterating
docs** — 1–3 iterations, each gated on standing up a fresh tester.

**The guarantee, stated exactly:**
- Execute Part A to its A.3 checklist → **LNCH-10 is solved.** Deterministic.
- Execute Part B's loop (B.3) until a cold tester meets the B.2 pass bars → **LNCH-18 and LNCH-22 are
  solved.** Guaranteed to converge because each iteration removes a real barrier; the only variable is
  how many iterations (bounded, small).
- Both parts done → `docs/LAUNCH_READINESS_GAPS.md` reaches **24 DONE · 0 PARTIAL**, and the launch
  ledger is fully closed. The register's remaining engineering items (REG-6 ColumnFacts, the new
  promotion-panel loop, the three latent items) are *not* launch-ledger gaps and are out of scope here.

---

## Guardrails

1. **Part A:** the schema executor / adapter conventions from `docs/REG7_REG8_...PLAN.md` §3 and the
   REG-12 plan §3 bind — 4-copy `model.schema.json` mirror, adapter-jar-in-test-lists, no hardcoded dev
   paths (Linux CI enforces), live-verify the PDF, and **run the packaged-app CI green via
   `scripts/ci/gh-api.sh` before calling it done** (Windows-verified is not enough — the whole session's
   lesson).
2. **Part B:** independence is sacred — the tester gets the brief + repo and **nothing else**; the
   driver never coaches; a re-run always uses a *fresh* tester; the tester's kind/identity is recorded
   so the closure is auditable. Never mark LNCH-18/22 DONE off a warmed-up or coached run.
3. **Both:** every gap flip in `LAUNCH_READINESS_GAPS.md` and the register cites its evidence path.

---

*Companion documents: `docs/REG12_DOCUMENT_EXPORT_PLAN.md` (Part A execution detail) ·
`docs/EXTERNAL_TESTER_COLDSTART.md` (Part B kit) · `docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (the log) ·
`docs/LAUNCH_READINESS_GAPS.md` §2 (the ledger these three close) ·
`docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.4/§2.5/§2.6 (REG-12/13/14).*
