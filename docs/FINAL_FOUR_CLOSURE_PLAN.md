# Final Four — Closure Plan for REG-16-resid, REG-17, AW-P2, REG-39

> **STATUS: EXECUTED (2026-07-25).** REG-16-resid, REG-17, AW-P2 and REG-39 all closed. Kept as a record.


> **Written:** 2026-07-25 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
> **Audience:** an executor with limited autonomy (AI or human). Every fact was verified against the live
> tree on 2026-07-25. **If reality does not match this document → STOP and report.**
>
> **Read this first.** Two of these four items are **not** what the tracking says they are. Verify before
> you build:
> - **AW-P2 is already DONE** (owner-confirmed 2026-07-24). Only a stale summary row makes it look open.
>   Its "plan" below is a 15-minute doc fix, not a feature.
> - **REG-16 is NOT "zero adversarial review"** — Tier A and Tier B are done, plus Round 1 of the
>   residual. The open item is **REG-16-resid**, a multi-round programme.
>
> Do the cheap truth-fixes (§3, §4) before the expensive work (§1, §2).

---

## 0. Global rules

1. **Never `git add .`** — stage by explicit path. Never push, never merge, never checkout another branch.
2. Commit trailer (last line of every body): `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
3. Pre-commit hygiene (the slimness hook blocks otherwise):
   ```bash
   cd /d/WorkSpace/NPDev/NPDev_General && rm -rf NPDevRuntimeHost/runtime-data && pwsh -File scripts/hygiene/clean-workspace-state.ps1
   ```
4. Evidence/scratch → `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`. Never inside the repo.
5. **STOP and report** if: a gate is red for a reason you did not cause; a documented fact here is false;
   or a review round finds a **CRITICAL/HIGH** security issue (that escalates — see §1.4).

---

## 1. REG-16-resid — adversarial review of the remaining ~21 launch surfaces

**Status:** Round 1 of N complete (2026-07-24, kernel execution path). **Severity:** HIGH (surface risk;
not launch-blocking). **Effort:** L, multi-round. **Register:** §3.10. **Plan of record:**
`docs/POST_REG17_CLOSURE_PLAN.md` Task 4.

### 1.1 What is already done — do NOT redo
- **REG-16 Tier A** (2026-07-21): LNCH-2 tenant isolation + LNCH-4 auth.
- **REG-16 Tier B** (2026-07-21): all 5 MEDIUM findings fixed (REG-18/19/20/21/22); REG-24 verified
  already-guarded; REG-26 WONTFIX; REG-23/25 deferred with rationale.
- **REG-16-resid Round 1** (2026-07-24): the kernel execution path — `KernelRunner`'s capability-invocation
  chain (circuit-gate → bulkhead-acquire → idempotency-check → retry → cache-write → failure-accounting),
  `RegistryCapabilityDispatcher`, and the idempotency/circuit-breaker/bulkhead mechanisms (in-proc **and**
  Postgres adapters). **No CRITICAL or HIGH.** Residual: 2 MEDIUM (REG-36, REG-37) + 2 INFO.
  Findings doc: `docs/REG16_KERNEL_EXECUTION_ADVERSARIAL_REVIEW.md`.

### 1.2 The remaining rounds (each is ONE session — do not batch)
The register explicitly forbids mechanizing this item. **One surface per round, in this order** (highest
blast-radius first):

| Round | Surface | Why this position |
|---|---|---|
| 2 | **LNCH-13 row-level authz** | An authz flaw is a direct data-exposure path — the highest-consequence unreviewed surface |
| 3 | **Generator codegen output itself** | A flaw here is reproduced into *every* generated app; reviewing the emitted code, not just the emitter |
| 4 | **Loop/await/orchestration flow-step types + `DefaultProcedureExecutor`** | Newest execution surface, least battle-tested; `await` + durable resume is the risky part |
| 5 | **The other durable-state Postgres adapters' own SQL** | Injection/tenant-scoping review of hand-written SQL |
| 6 | **Export/PDF path** | Untrusted-content rendering (SSRF/path traversal/resource exhaustion) |

### 1.3 The per-round procedure (copy Round 1 exactly)
1. **Scope it in writing first** — name the exact classes/files in scope. Anything not named is out of
   scope for this round; do not drift.
2. **Attack-first, not read-first.** For each mechanism ask: *what input does an attacker control, and
   what does the code do with it?* Round 1's template questions, reusable verbatim:
   - Is every piece of state **tenant-scoped**, and is the tenant part of the *key*, not just a filter?
   - Can request data choose **which** code path/method executes (confused deputy), or only argument values?
   - Is admission/concurrency control genuinely **atomic**, or a check-then-act race?
   - What happens on **partial failure** — is state left readable/writable by the wrong actor?
3. **Write a findings document** `docs/REG16_<SURFACE>_ADVERSARIAL_REVIEW.md`, following
   `REG16_KERNEL_EXECUTION_ADVERSARIAL_REVIEW.md`'s shape: headline verdict, per-mechanism analysis,
   then findings F1..Fn each rated CRITICAL/HIGH/MEDIUM/INFO with the concrete attack.
4. **Triage per the plan's rule:** CRITICAL/HIGH ⇒ mandatory Tier-B remediation **this round**;
   MEDIUM ⇒ file as a dated `REG-nn` item, scheduled not dropped; INFO ⇒ record in the doc only.
5. **File MEDIUMs into the register** as new `REG-nn` rows with a date, exactly like REG-36/REG-37.
6. Update §3.10's "Rounds not yet done" list, and commit:
   `docs(REG-16-resid): round <n> adversarial review — <surface>`

### 1.4 STOP conditions
- A **CRITICAL or HIGH** finding → stop the round, report to the owner immediately, and remediate before
  any further review work. Do not continue to the next surface.
- Tempted to review two surfaces at once → **don't**; the register forbids it by name.

### 1.5 Definition of done for REG-16-resid
All six rounds have a findings document, every CRITICAL/HIGH is remediated, every MEDIUM is a dated
register row, and §3.10 lists no surface under "Rounds not yet done".

---

## 2. REG-17 — independent third-party reproduction

**Status:** PARTIAL — 2/4 gates run and triaged by an independent tester (advanced 2026-07-22).
**Severity:** MED. **Effort:** M. **Register:** §3.2. **This is owner-scheduled: it needs a human who is
not the author.** An AI executor can prepare, but cannot *be* the third party.

### 2.1 What "done" means
A person who did not write NPDev, on hardware this project has never touched, follows only the written
docs and reproduces the verification claims — with their failures recorded rather than fixed silently.

### 2.2 Preparation an executor CAN do (do this first, unattended)
1. **Verify the entry-point docs are sufficient for a stranger.** Read `docs/GETTING_STARTED.md` end to
   end *as if you know nothing*; every command must be copy-pasteable with absolute paths and no implied
   local state. File any gap as a doc fix (this is exactly what REG-14's newcomer test did).
2. **Produce a one-page reproduction script** `docs/THIRD_PARTY_REPRODUCTION.md`: the 4 gates, the exact
   commands, the expected output for each, and a results table for the tester to fill in
   (`gate | expected | observed | pass/fail | notes`).
3. **Confirm the automated half still passes from a clean checkout** — the GitHub-hosted CI run is the
   closest existing proxy (REG-17's own history: run `30067198501`, both Linux and Windows jobs green).
4. **List the two known-open CI items** so the tester is not re-discovering them: the Windows
   `LegacyModelMigrationToolTest` failure and the Linux `npdev report bootstrap` failure.

### 2.3 The part only the owner can schedule
Hand `THIRD_PARTY_REPRODUCTION.md` + a clean clone to a person outside the project. **Their failures are
the deliverable** — resist fixing anything mid-run; record, then triage. File every finding as a dated
register row, as rounds 1–4 of REG-17 already did.

### 2.4 Definition of done
All 4 gates attempted by an independent party, results recorded in the register with a run date, and each
failure either fixed-and-reconfirmed or filed as a dated item.

---

## 3. AW-P2 — ⚠ ALREADY DONE, only the tracking is stale (15 minutes)

**Do not implement anything.** Verified 2026-07-25:
- `docs/OPEN_GAPS_AND_ROADMAP.md` **line 354**: `### AW-P2 — … ✅ DONE (2026-07-24)`, owner-confirmed.
  Both in-scope halves landed: picker unification (2026-07-13) and FK auto-Prompt (confirmed
  **already implemented** — `FieldWidgetDefaults.defaultWidget` resolves a reference field with no explicit
  widget to `LOOKUP` → `business-ui-app.mustache`'s `createLookupInput`, so every FK field auto-renders a
  browse/pick dialog with zero authoring). The original "FK fields lack a Prompt" premise was simply wrong.
- The only residual — an opt-in `field.ui.selectorRef` for a *custom, filtered* picker — was
  **owner-descoped** as an optional P4 follow-up, explicitly *not* part of AW-P2's closure.

**The actual gap:** `docs/OPEN_GAPS_AND_ROADMAP.md` **line 72** (the summary table) still says `PARTIAL`,
and `knowledge/platform-status.json` — which is **derived** from that file — inherited it, so the AI
knowledge substrate reports AW-P2 as partial too.

### 3.1 The fix
1. Edit line 72 of `docs/OPEN_GAPS_AND_ROADMAP.md`: `PARTIAL (…)` → `DONE (2026-07-24, owner-confirmed;
   selectorRef descoped to an optional P4 follow-up)`.
2. **Regenerate the derived projection — never hand-edit it:**
   ```bash
   cd /d/WorkSpace/NPDev/NPDev_General && python scripts/ai/extract_platform_status.py
   ```
3. Confirm `knowledge/platform-status.json` now shows AW-P2 as DONE, then rebuild the AI corpora:
   ```bash
   python scripts/ai/build_knowledge.py
   ```
4. Commit: `docs(AW-P2): summary row said PARTIAL while the item closed 2026-07-24 (+ regen platform-status)`

> **Generalize this.** AW-P2, REG-16, REG-6 and six others were all *closed items rendered as open*. When
> you touch any ledger, check the **detail section** before trusting the **summary row** — and remember
> `platform-status.json` is derived, so a stale summary row silently poisons the AI knowledge substrate.

---

## 4. REG-39 — the built-in-pack drift hazard (platform-wide)

**Status:** DONE for WmsOffice (2026-07-24); **the platform hazard remains**. **Severity:** MED.

### 4.1 The failure mode, precisely
WmsOffice login failed with `invalid_credentials` for *every* credential. Cause: `LoginController` (and
`JwtSigner`, `PasswordResetController`, `ControlPanelTenantUsersController`) unconditionally
`SELECT … token_version FROM identity_users`, the column did not exist in that app's **stale copy** of the
identity pack, the query threw, and the exception was **swallowed** into a generic auth failure.

Generalized: **an app carries its own copy of a built-in pack. When the platform's pack gains a column
that platform code then reads unconditionally, every app whose copy predates the bump breaks — and breaks
*misleadingly*, as an auth failure rather than a schema error.** Any future pack column can do this again.

### 4.2 The three-layer fix (do all three; layer 1 alone is what happened last time)
**Layer 1 — detect the drift (highest value, do first).**
Add a boot-time check comparing each app's built-in-pack copy against the platform's current pack
definition, and **fail fast with a precise message** (`identity pack is at version X, this platform build
requires Y — regenerate the app`) instead of failing later as a mystery auth error. Options in order of
preference: (a) a pack-version marker in the pack definition + a `StartupValidator` comparison (mirrors the
existing `StartupValidator` fail-fast style used for JWT keys under REG-9); (b) failing that, a
column-presence precondition check on the specific columns platform code reads unconditionally.

**Layer 2 — stop swallowing the exception.**
Grep the four named classes for the `token_version` read and any `catch` that converts a `SQLException`
into a generic auth failure. A schema error must surface as a schema error. Add a RED-first test: a table
missing `token_version` produces an error naming the column, **not** `invalid_credentials`.

**Layer 3 — make the drift visible without a boot.**
The Impact Report / `-ImpactOnly` surface (`docs/IMPACT_REPORTS.md`) already answers "what will this
upgrade do to my data?" — a pack-drift check belongs in the same pre-deploy answer. Report a stale pack
copy as a `NEEDS_ATTENTION` item.

### 4.3 Verification
- RED-first for layer 2 (the swallow is the bug that made this expensive to diagnose).
- A live proof: take an app with a deliberately stale pack copy, boot it, and confirm the message names
  the pack and the required version. Evidence → OutsideRepo.
- Gates: GATE-H2 + GATE-PG + one full app rebuild via the `rebuild-app` skill.

### 4.4 Definition of done
A stale built-in-pack copy fails fast at boot with a message naming the pack and the fix, no platform code
swallows a schema error into an auth error, and the register's REG-39 row records the platform-wide
closure (not just WmsOffice).

---

## 5. Suggested order

| # | Item | Why here | Effort |
|---|---|---|---|
| 1 | **§3 AW-P2** doc fix + regen | 15 min; also un-poisons the AI knowledge substrate | XS |
| 2 | **§4 REG-39** layers 1+2 | A real, diagnosed, reproducible platform hazard with a known fix shape | M |
| 3 | **§2.2 REG-17** preparation | Unattended prep that unblocks an owner-scheduled activity | S |
| 4 | **§1 REG-16-resid** Round 2 (row-level authz) | Highest-consequence unreviewed surface; one session | M |
| 5 | §1 Rounds 3–6 | One session each, in the §1.2 order | L |
| — | **§2.3 REG-17** execution | **Owner must schedule a human third party** | — |
