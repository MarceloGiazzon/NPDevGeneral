# Post-LNCH-22 Execution Plan (for a less-capable AI executor)

> **Written:** 2026-07-22 · **Branch of record:** `beta1-vision-spine` · **Repo root:**
> `D:\WorkSpace\NPDev\NPDev_General`
>
> **Who this is for.** An AI tool with limited autonomy. Every step is mechanical: exact file paths,
> exact text to find, exact text to write, exact commands, and the exact output that means "it
> worked." **Do not improvise.** If any step does not match what is written here, **STOP** and report
> (see [§7 STOP rules](#7-global-stop-and-escalation-rules)). Doing nothing is always safer than
> guessing.
>
> **What this plan does NOT ask you to do.** It never asks you to commit, push, merge, delete files,
> run destructive git commands, or "fix" anything not listed. Those are covered by
> [§6 Human-only actions](#6-human-only-actions-do-not-perform) and
> [§8 Do-not-touch list](#8-appendix-a--do-not-touch-deliberate-boundaries).

---

## 0. Global rules (read fully before Task 1)

1. **Always use absolute paths** exactly as written (e.g. `D:\WorkSpace\NPDev\NPDev_General\docs\...`).
   Never shorten or guess a path.
2. **Shell is PowerShell** on Windows. Run commands exactly as given.
3. **Never** run `git add .`, `git commit`, `git push`, `git merge`, `git reset`, `git checkout`, or
   any command that deletes or moves files, unless [§6](#6-human-only-actions-do-not-perform) says a
   human does it. This plan's file edits stay uncommitted for a human to review.
4. **Never edit anything under** `D:\WorkSpace\NPDev\Build` **or any folder named** `npdev-generated`.
   Those are generated/ephemeral. Editing them does nothing useful and can corrupt a build.
5. **Do one task at a time, in order.** Finish a task's Verification and mark it DONE before starting
   the next. Tasks 1, 2, 3 are independent — if one must STOP, you may still do the others.
6. **Do not full-read very large files.** If a file is over ~1500 lines, use Grep to find the line,
   then Read with `offset`/`limit`. The files this plan touches are safe to open at the given line
   ranges.
7. **Every edit uses the Edit tool** with the exact `old_string` (FIND) and `new_string` (REPLACE)
   blocks given. If the Edit tool reports the `old_string` was not found or is not unique, **STOP** —
   do not try a different string.
8. **After every command, check the exit code / output** against the "Expected" line. Anything else =
   STOP and report.

---

## 1. Task 0 — Preflight (must pass before any edit)

**Goal:** confirm you are in the right repo, on the right branch, with the target files present.

### Step 0.1 — Confirm the branch
Run:
```
git -C D:\WorkSpace\NPDev\NPDev_General rev-parse --abbrev-ref HEAD
```
**Expected output:** `beta1-vision-spine`
**If different:** STOP and report. Do NOT switch branches yourself.

### Step 0.2 — Confirm the three target files exist
Run:
```
Test-Path 'D:\WorkSpace\NPDev\NPDev_General\docs\NPDEV_OPEN_ITEMS_REGISTER.md'
Test-Path 'D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator\generator\src\main\resources\npdev-templates\business-ui-app.mustache'
Test-Path 'D:\WorkSpace\NPDev\NPDev_General\scripts\runtimehost\sync-runtimehost-libs.ps1'
```
**Expected output:** `True` three times.
**If any is `False`:** STOP and report.

### Step 0.3 — Record the starting state (for your final report)
Run:
```
git -C D:\WorkSpace\NPDev\NPDev_General status --short
```
Copy the output into your final report as "BEFORE". Do not act on it.

Preflight DONE when: branch is `beta1-vision-spine` and all three `Test-Path` results are `True`.

---

## 2. Task 1 — Fix the stale navigation in the Open Items Register (DOC ONLY, safest task)

**Why.** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` has two sections (§0.2 "at a glance" table and §4
"Suggested order") that were written **before** many items were closed on 2026-07-21/07-22. A reader
following them would redo already-finished work. You will insert two clearly-worded correction
banners. **You will NOT rewrite the table or the list** — only insert banners above them.

**File:** `D:\WorkSpace\NPDev\NPDev_General\docs\NPDEV_OPEN_ITEMS_REGISTER.md`

### Step 1.1 — Insert the §0.2 status-correction banner

Use the Edit tool with:

**FIND (`old_string`):**
```
### 0.2 Register at a glance

| ID | Title | Type | Sev | Effort | § |
```

**REPLACE (`new_string`):**
```
### 0.2 Register at a glance

> **STATUS CORRECTION (2026-07-22).** The table below predates the 2026-07-21/07-22 closure wave and
> is kept only for historical shape. Read these as **CLOSED** regardless of how their row renders
> here: REG-1, REG-2, REG-3, REG-4, REG-5, REG-7, REG-8, REG-9, REG-10, REG-11, REG-12, REG-13,
> REG-14, REG-18, REG-19, REG-20, REG-21, REG-22, REG-24, REG-27, REG-28, REG-29, REG-30. Still
> genuinely open or partial: **REG-6** (~40%, structural refactor, deliberately deferred), **REG-15**
> (release tag DONE, trademark parked), **REG-16** (adversarial review done for LNCH-2/LNCH-4 only),
> **REG-17** (PARTIAL — 2 of 4 gates reproduced), **REG-23** and **REG-25** (deferred boundaries).
> The authoritative current state is `docs/LAUNCH_READINESS_GAPS.md` (24 DONE / 0 PARTIAL / 0 OPEN)
> plus each entry's own **Status** line below, not this summary table.

| ID | Title | Type | Sev | Effort | § |
```

### Step 1.2 — Insert the §4 "superseded" banner

Use the Edit tool with:

**FIND (`old_string`):**
```
## 4. Suggested order (revised 2026-07-21 after independent code verification)
```

**REPLACE (`new_string`):**
```
## 4. Suggested order (revised 2026-07-21 after independent code verification)

> **SUPERSEDED (2026-07-22).** The numbered order below was written before REG-2, REG-3, REG-9 and
> others were closed, so it now lists already-CLOSED items as "next actions." Do **not** action it as
> written. The current action order lives in
> `docs/AI_SESSION_DIGEST_2026-07-22_LNCH22_CLOSURE.md` (§9) and `docs/POST_LNCH22_EXECUTION_PLAN.md`.
> The list below is kept only as a record of the 2026-07-21 reasoning.
```

### Step 1.3 — Verify Task 1
Run:
```
Select-String -Path 'D:\WorkSpace\NPDev\NPDev_General\docs\NPDEV_OPEN_ITEMS_REGISTER.md' -Pattern 'STATUS CORRECTION \(2026-07-22\)','SUPERSEDED \(2026-07-22\)' | Select-Object LineNumber, Line
```
**Expected output:** two lines, one matching `STATUS CORRECTION (2026-07-22)` and one matching
`SUPERSEDED (2026-07-22)`.
**If you see fewer than two:** STOP and report.

**Task 1 DONE** when both banners are present and the file still opens normally. Do not commit.

---

## 3. Task 2 — Fix the promotion-panel infinite retry loop (CODE, bounded)

**Why.** In the generated business UI, the "Promotion" panel auto-loads data. On apps that use
in-memory storage, the endpoint `/api/admin/promotion` returns HTTP 503, and the current code never
records that an attempt happened — so it retries forever on every re-render, flooding the console and
making toolbar buttons unclickable. The fix adds one `attempted` flag so a completed attempt (success
**or** failure) does not auto-retry; the manual **Refresh** button still works because it calls the
loader directly.

**File (the ONLY file you edit in this task):**
`D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator\generator\src\main\resources\npdev-templates\business-ui-app.mustache`

> ⚠️ This is a generator **template**, not generated output — editing it here is correct. Do NOT look
> for or edit any `app.js` under `Build` or `npdev-generated`.

Make **exactly three** edits, in this order.

### Step 2.1 — Add the `attempted` field to the promotion state initializer

**FIND (`old_string`):**
```
    promotion: { currentStage: "", history: [], loading: false, loaded: false },
```
**REPLACE (`new_string`):**
```
    promotion: { currentStage: "", history: [], loading: false, loaded: false, attempted: false },
```

### Step 2.2 — Set `attempted = true` when a load finishes (success or failure)

**FIND (`old_string`):**
```
    } catch (error) {
      setStatus(error.message, true);
    }
    state.promotion.loading = false;
    render();
  }
```
**REPLACE (`new_string`):**
```
    } catch (error) {
      setStatus(error.message, true);
    }
    // A completed attempt (success OR failure) must not auto-retry. On in-memory-storage apps
    // /api/admin/promotion returns 503 and never sets loaded=true, which previously made
    // renderPromotionPanel()'s auto-load guard re-trigger loadPromotion() on every render -- an
    // unbounded loop. The Refresh button (which calls loadPromotion directly) is unaffected.
    state.promotion.attempted = true;
    state.promotion.loading = false;
    render();
  }
```

### Step 2.3 — Add `attempted` to the auto-load guard

**FIND (`old_string`):**
```
    if (!state.promotion.loaded && !state.promotion.loading) {
```
**REPLACE (`new_string`):**
```
    if (!state.promotion.loaded && !state.promotion.loading && !state.promotion.attempted) {
```

### Step 2.4 — Verify the three edits are present
Run:
```
Select-String -Path 'D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator\generator\src\main\resources\npdev-templates\business-ui-app.mustache' -Pattern 'loaded: false, attempted: false','state\.promotion\.attempted = true;','!state\.promotion\.attempted' | Select-Object LineNumber, Line
```
**Expected output:** three matching lines (one per pattern).
**If fewer than three:** STOP and report.

### Step 2.5 — (OPTIONAL, only if you can run it cleanly) Prove the template still generates
This regenerates one real app to confirm the template is not broken. It can take several minutes and
produces a large build. **If you cannot run it, or it takes too long, or it errors, SKIP it and
report that the live rebuild is left to the human** — the edits themselves are the deliverable.

Run:
```
pwsh -File D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Rebuild-And-Restage.ps1 -AppFolder D:\WorkSpace\NPDev\AppGen\apps\wmsoffice -SkipLibs
```
**Expected:** the command finishes and the **last** line indicates success (exit code 0). The script
prints `==> Step 2/3` and `==> Step 3/3` stages.
**If it exits non-zero or throws:** do **not** try to fix the build. STOP, report the full error, and
state that the edits from 2.1–2.3 are in place but the rebuild was not confirmed.

**Task 2 DONE** when Step 2.4 shows all three edits. (Step 2.5 is a bonus, not required.)

> **Note for the human / capable agent (not for you to do):** the authoritative check is a live
> browser regression on an **in-memory-storage** app (e.g. the Claude Support Desk app, or the
> `simple-contact-intake` sample) using the `verify-in-browser` skill — confirm the Promotion panel
> loads once, shows the 503 error, and does **not** loop. That step needs judgment and is out of
> scope for this plan.

---

## 4. Task 3 — Write the "adapter registration" checklist (DOC ONLY, prevents a recurring break)

**Why.** Whenever a new adapter module is added under `NPDevKernel\adapters\`, its jar must be
registered in **four** places, or a freshly generated app fails to compile on a clean CI machine —
with a confusing symptom (a bare 404 or a `NoClassDefFoundError`, never a clear error). This has
already bitten three times (`mail-inproc`/`mail-smtp`, then `document-render-inproc`/
`document-render-stub`). You will capture the four places as a checklist so the next person cannot
miss one. **This task only creates one new doc file. It edits nothing.**

### Step 4.1 — Create the checklist file
Create a new file at:
`D:\WorkSpace\NPDev\NPDev_General\docs\ADAPTER_REGISTRATION_CHECKLIST.md`

with **exactly** this content:

```
# Adapter registration checklist

> When you add (or rename) an adapter module under `NPDevKernel\adapters\`, its jar must be listed in
> EVERY place below, or a freshly generated FinalApp will fail to compile on a clean machine / CI with
> a silent symptom (a bare 404 or a `NoClassDefFoundError`, not an obvious build error). This has
> already caused three incidents (`mail-inproc`/`mail-smtp`, `document-render-inproc`/`document-render-stub`).
>
> Do all of these in the same change. Then run the RuntimeHost gate and, if possible, a clean CI run.

## The four places to update

1. `NPDevGenerator\generator\src\test\java\com\npdev\generator\emitters\TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java`
   — add `":adapters:<your-adapter>:jar"` to the adapter-jar build list (kept in alphabetical order).
2. `NPDevGenerator\generator\src\test\java\com\npdev\generator\emitters\HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest.java`
   — same list, same entry.
3. `NPDevGenerator\generator\src\test\java\com\npdev\generator\emitters\HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.java`
   — same list, same entry.
4. `scripts\runtimehost\sync-runtimehost-libs.ps1` — the local jar-staging path; make sure the new
   adapter's jar is staged into the runtimehost-libs directory the generated app compiles against.

## Also check (if the adapter is imported by the RuntimeHost template)

- `NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevPluginConfig.java` imports some adapters
  (e.g. the mail adapters) unconditionally. If yours is imported there, its jar MUST exist or every
  generated app fails to compile — the same reason the three proof-test lists above exist.

## How to verify you got all of them

- Run `pwsh -File scripts\quality\run-runtimehost-gate.ps1` and confirm it passes.
- The real proof is a clean Linux CI run (`.github/workflows/npdev-pr-gate.yml`) — the dev machine
  often has stale jars that hide a missing entry.

## For a capable agent (future work, not part of this checklist)

Replace these three hand-maintained lists with a single source of truth — e.g. a test that enumerates
the directories under `NPDevKernel\adapters\` and asserts each appears in all three proof tests — so a
new adapter cannot be added without the guard failing loudly. Tracked as the "adapter-list fragility"
latent item in `docs/NPDEV_OPEN_ITEMS_REGISTER.md`.
```

### Step 4.2 — Verify Task 3
Run:
```
Test-Path 'D:\WorkSpace\NPDev\NPDev_General\docs\ADAPTER_REGISTRATION_CHECKLIST.md'
```
**Expected output:** `True`.

**Task 3 DONE** when the file exists.

---

## 5. Final report (always produce this)

After finishing (or stopping), report using this exact template:

```
POST-LNCH22 PLAN — EXECUTION REPORT
Branch: <output of Step 0.1>
BEFORE (git status --short): <output of Step 0.3>

Task 1 (register banners): DONE | STOPPED | SKIPPED
  - Step 1.3 verify output: <paste>
Task 2 (promotion loop fix): DONE | STOPPED | SKIPPED
  - Step 2.4 verify output: <paste>
  - Step 2.5 rebuild: RAN-OK | SKIPPED | FAILED (<error>)
Task 3 (adapter checklist): DONE | STOPPED | SKIPPED
  - Step 4.2 verify output: <paste>

AFTER (git status --short): <run `git -C D:\WorkSpace\NPDev\NPDev_General status --short` and paste>

Anything that STOPPED and why: <describe, do not fix>
Nothing was committed or pushed: CONFIRMED
```

Do **not** commit, push, or clean up. A human reviews the working-tree changes.

---

## 6. Human-only actions (do NOT perform)

These are important next steps, but they are **outside** an automated executor's remit because they
are hard to reverse or need credentials/judgment. List them in your report as "pending human," do not
attempt them:

1. **Update `main`.** The default branch (`main`, at merge commit `3e29cca`) does **not** contain the
   LNCH-22 runtimehost-libs fix (`2adf8ec`) or later work — everything since is only on
   `beta1-vision-spine`. Until a human merges/fast-forwards `beta1-vision-spine → main`, a real
   `git clone` and every new agent worktree (which branch off `main`) will reproduce the bug that was
   just fixed. **A human must do this git operation.**
2. **Confirm CI green on the development line.** The last green CI runs were on an older branch
   (`lnch19-ci-verify`), not on `beta1-vision-spine`'s head. A human must push and dispatch
   `.github/workflows/npdev-pr-gate.yml` and confirm it is green — this also settles whether the
   "3 of 172 generator tests failed" report seen earlier was a staging artifact or a real regression.
3. **REG-17 external reproduction.** A genuinely external person (or a fresh session on different
   hardware, ideally Linux) clones, builds, and runs the remaining gates from `docs/` alone. Do this
   only **after** item 1, or they will clone the stale branch.

---

## 7. Global STOP and escalation rules

STOP immediately (finish nothing further in the current task, write your report) if **any** of these
happen:

- Task 0 preflight fails (wrong branch, or a target file missing).
- An Edit tool call says the FIND text was not found, or matched more than once.
- A verification `Select-String` returns fewer matches than "Expected."
- Any command exits non-zero when the plan expected success, or throws an error you did not cause.
- You are tempted to edit a file, run a command, or take an action **not written in this plan** — do
  not. Report what you wanted to do instead.
- Anything looks different from this document in a way you cannot explain.

When you STOP: describe exactly what you observed (paste the tool output), say which step, and make no
further changes. **Never** attempt to repair a build error, resolve a merge, or "clean up" — those are
human decisions.

---

## 8. Appendix A — DO NOT TOUCH (deliberate boundaries)

These are **intentional limits**, already decided and documented. They look like bugs but are not.
**Do not try to "fix" any of them.** If you think one needs work, put it in your report as a
suggestion for a human — do not act.

| Area | The deliberate limit (leave as-is) |
|---|---|
| Migration collision (REG-7.3) | It is detect-and-refuse, **not** a database lock. Intentional for v1. |
| Schema rollback (REG-8) | It refuses loudly; it does **not** reconstruct dropped data. Intentional. |
| `tv`-less JWT tokens (REG-23) | Not revocation-checked, by design (backward compatibility). Deferred on purpose. |
| Case-sensitive tenant match (REG-25) | Deferred; fixing needs a data migration, not an inline tweak. |
| Granular JWT error codes (REG-26) | WONTFIX — kept for diagnosability. |
| Super-user key delivery | "Issued, not operator-supplied" is the chosen default (WONTFIX). |
| `ColumnFacts` refactor (REG-6) | ~40% done and **deliberately paused**. Do not continue it — it is all-or-nothing and needs full H2 + Postgres proof-matrix runs. |
| "AI Studio" builder | Floated only. No plan exists. Do not start any code. |

---

## 9. Appendix B — Why these three tasks (context, optional reading)

- **Task 1** removes an active trip-hazard: the register's own summary table and action list contradict
  its entry-level statuses, so the fastest win is to stop them misleading the next reader. Doc-only,
  zero build risk.
- **Task 2** is the only clean, filed, reproducible **bug** left that is small enough to fix
  mechanically. The loop was confirmed in `business-ui-app.mustache` (the auto-load guard re-fires
  after a 503 because no "attempted" state is recorded).
- **Task 3** converts tribal knowledge (the "add a new adapter in four places" trap that broke builds
  three times) into a checklist, so the fourth incident does not happen while a proper automated guard
  is still future work.

Everything larger (updating `main`, CI confirmation, REG-6, external reproduction) is either
human-gated (§6) or a deliberate boundary (§8).
```
