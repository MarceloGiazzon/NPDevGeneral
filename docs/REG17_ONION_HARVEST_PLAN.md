# REG-17 Onion — harvest & escalate loop (for a less-capable AI executor)

> **Written:** 2026-07-24 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
>
> **READ THIS FIRST — what this plan is and is NOT.**
> REG-17 ("no third party has reproduced the verification") is closed only when the full CI validation
> workflow `npdev-ci-validation.yml` (Linux job + Windows job) runs **green end-to-end** on GitHub's
> runners. It is an **onion**: every fix so far has unlocked the *next* step that had never executed on
> CI, which then failed. Diagnosing each layer needs judgment (the capable agent who set this up got
> two hypotheses wrong before verifying). **This plan does NOT diagnose or fix anything.** It runs the
> safe, mechanical half of the loop: **dispatch the CI, record what passed/failed, file NEW failures,
> and STOP for a capable agent when a fix is needed.** You (the executor) are the crank-turner and
> record-keeper, not the fixer.
>
> **This plan alone cannot close REG-17.** See §8 for exactly what more is required.

---

## 0. Global rules

1. Absolute paths exactly as written. Shell is PowerShell unless a step says `bash`.
2. Git: only the exact `git add <named files>` + `git commit` blocks given. **NEVER** `git add .`,
   never push (a human/capable-agent pushes), never edit a workflow or any code file.
3. **You never fix a CI failure.** You record it and stop. If you feel the urge to "just fix" a red
   step — don't. That is the capable agent's job (§7).
4. Never edit under `D:\WorkSpace\NPDev\Build` or any `npdev-generated` folder.
5. After every command, compare to "Expected". Divergence → STOP and report.
6. CI runs are long (up to ~120 min job timeout, though most failures happen in the first ~15 min).
   Poll patiently; do not cancel a run.

---

## 1. Task 0 — Preflight

```
git -C D:\WorkSpace\NPDev\NPDev_General rev-parse --abbrev-ref HEAD
git -C D:\WorkSpace\NPDev\NPDev_General status --short
```
**Expected:** `beta1-vision-spine`; clean (or only untracked docs). If the branch differs or tracked
files are modified → STOP (a fix may be mid-flight; a human should sort it out first).

**Sanity-check the API helper works (read-only):**
```
bash scripts/ci/gh-api.sh GET 'actions/workflows/npdev-ci-validation.yml/runs?per_page=1' | python -c "import sys,json;r=json.load(sys.stdin)['workflow_runs'][0];print('last run',r['id'],r['head_branch'],r['head_sha'][:7],r['status'],r['conclusion'])"
```
**Expected:** one line describing the most recent run. If it errors → STOP (token/tooling issue for a human).

---

## 2. Task 1 — Is a NEW run needed?

You only dispatch a fresh CI run if the current branch head has commits the last CI run did not test
(i.e. a capable agent has landed a fix since the last run).

```
git -C D:\WorkSpace\NPDev\NPDev_General rev-parse --short HEAD
```
Compare `HEAD` to the `head_sha` from the Task 0 sanity check.
- **HEAD == last run's head_sha** → the latest code was already tested. **Skip to Task 3** (harvest
  that existing run); do not dispatch a duplicate.
- **HEAD != last run's head_sha** → new code exists. **Go to Task 2** (dispatch).

---

## 3. Task 2 — Dispatch a run (only if Task 1 said to)

⚠️ Dispatching needs the branch pushed. If `git status` in Task 0 showed unpushed commits, a human
must push first — you do not push. Confirm the remote is current:
```
git -C D:\WorkSpace\NPDev\NPDev_General rev-parse --short origin/beta1-vision-spine
```
If `origin/beta1-vision-spine` != `HEAD` → STOP and report "remote is behind HEAD; a human must push
before I can dispatch." Otherwise dispatch:
```
bash scripts/ci/gh-api.sh POST 'actions/workflows/npdev-ci-validation.yml/dispatches' '{"ref":"beta1-vision-spine"}'
```
**Expected:** empty output (HTTP 204). Any JSON with a `message` field → STOP.

Wait ~30s, then capture the run id:
```
bash scripts/ci/gh-api.sh GET 'actions/workflows/npdev-ci-validation.yml/runs?per_page=1' | python -c "import sys,json;r=json.load(sys.stdin)['workflow_runs'][0];print('RUN_ID='+str(r['id']),'sha='+r['head_sha'][:7],'status='+r['status'])"
```
Record `RUN_ID`. Confirm `sha` matches HEAD. Proceed to Task 3.

---

## 4. Task 3 — Poll until the run completes

Using the `RUN_ID` (from Task 2, or the last run's id from Task 0 if you skipped dispatch), poll every
~10 minutes until `status=completed`:
```
bash scripts/ci/gh-api.sh GET "actions/runs/RUN_ID" | python -c "import sys,json;d=json.load(sys.stdin);print('status='+d['status'],'conclusion='+str(d['conclusion']))"
```
(Replace `RUN_ID`.) Repeat until `status=completed`. Do not cancel. Then go to Task 4.

---

## 5. Task 4 — Harvest the per-step results (the core of this plan)

```
bash scripts/ci/gh-api.sh GET "actions/runs/RUN_ID/jobs" | python -c "
import sys,json
for j in json.load(sys.stdin)['jobs']:
    print('JOB:',j['name'],'->',j['conclusion'])
    for s in j['steps']:
        mark = '  FAIL >>' if s['conclusion']=='failure' else '   ok   ' if s['conclusion']=='success' else '   ..   '
        print(mark, s['conclusion'], '|', s['name'])
"
```
Save the **entire** output — it is the record. Then classify:

- **conclusion=success for BOTH jobs** → REG-17's automated reproduction is GREEN. Go to Task 5
  (Outcome GREEN). This is the big one — but read §8 before calling REG-17 "closed."
- **any job conclusion=failure** → note **every** step whose conclusion is `failure`. For each failing
  step, pull its message (best-effort — the console truncates, but capture what's there):
```
bash scripts/ci/gh-api.sh GET "actions/jobs/JOB_ID/logs" | grep -iE "FAILED|What went wrong|Exception|Error:|tests completed|Process completed with exit code|throw " | grep -viE "deprecat|warning:" | tail -25
```
(Get `JOB_ID` from the jobs output's first field per job; run once per failing job.) Go to Task 5
(Outcome RED).

---

## 6. Task 5 — Record the outcome (append to the findings file, then STOP)

Findings file (create if missing):
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\reg17-linux-validation-2026-07-22\run-RUN_ID-findings.md`
(use the real RUN_ID in the filename).

### Outcome GREEN
Write into that file: the run URL (`https://github.com/MarceloGiazzon/NPDevGeneral/actions/runs/RUN_ID`),
the full Task-4 step map, and one line: `ALL STEPS GREEN — REG-17 automated reproduction achieved.`
Then update the register — file `docs\NPDEV_OPEN_ITEMS_REGISTER.md`, find the REG-17 §3.2 status line
(begins `**Type:** PROCESS · **Severity:** MEDIUM · **Effort:** M · **Status:**`) and set its status to:
`**GREEN END-TO-END (2026-…, run RUN_ID)** — full CI validation (Linux + Windows) passed on GitHub
runners from a clean checkout. Owner decision needed on whether this automated external reproduction
satisfies REG-17's DoD or a literal human third-party run is still required (see docs/REG17_ONION_HARVEST_PLAN.md §8).`
Commit:
```
git -C D:\WorkSpace\NPDev\NPDev_General add docs/NPDEV_OPEN_ITEMS_REGISTER.md
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "REG-17: full CI validation GREEN end-to-end (run RUN_ID) — pending owner DoD decision"
```
Then STOP and report Outcome GREEN. **Do not declare REG-17 CLOSED yourself** — that is an owner call (§8).

### Outcome RED
Append to the findings file: the run URL, the full Task-4 step map, and for **each** failing step a
block:
```
FINDING (run RUN_ID): <job name> / <step name> — FAILURE
  captured log (may be truncated): <paste the grep output>
  status: NOT diagnosed, NOT fixed — needs a capable agent (see handoff below)
```
Update the register REG-17 §3.2 status line to note the round: append
`· Round N (run RUN_ID, 2026-…): still RED at [<failing step names>]; filed, not fixed.`
Commit:
```
git -C D:\WorkSpace\NPDev\NPDev_General add docs/NPDEV_OPEN_ITEMS_REGISTER.md
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "REG-17 round N: harvest run RUN_ID — <N> steps still red; filed, not fixed"
```
Then STOP and produce the **capable-agent handoff** (Task 6). **Do not attempt any fix.**

---

## 7. Task 6 — Capable-agent handoff (what you hand off; you do NOT do this)

In your final report, for each RED finding, hand the capable agent this checklist (they will act on it;
you will not):
1. **Fetch the full failing test/step output** — the console truncates it. The message lives in
   `build/test-results/**/*.xml` (JUnit `<failure>`/`<error>` bodies) or `scripts/reports/out/*.json`.
   These are inside the run's uploaded artifact, which `gh-api.sh` cannot download (out of repo scope).
   The capable agent must either (a) add an `if: failure()` step to the job that prints those files
   into the log (so `gh-api.sh` can read them), or (b) obtain artifact-download access.
2. **Reproduce locally where possible, RED-first** — e.g. a Linux/Docker step reproduces via
   Testcontainers on any OS; a Windows-CI-only failure may NOT reproduce on a local Windows box (the
   `LegacyModelMigrationToolTest` one runs exit-0 locally — it is CI-environment-specific).
3. **Fix one layer, verify, commit, push** — then this plan re-runs from Task 0 (a new HEAD → new
   dispatch → new harvest). Repeat until Outcome GREEN.

### Known open layers as of 2026-07-24 (run 30057723015) — for the capable agent
- **Linux job:** `Bootstrap post-Beta0 maturity reports` fails (`npdev report bootstrap` exit 1,
  `report-bootstrap-and-regeneration-report.json`) — first time it ran. Steps after it: none but the
  always() upload. So Linux may be one layer from green.
- **Windows job:** `DSL contract check` fails (`LegacyModelMigrationToolTest` 1/350; `npdev.bat migrate`
  exits non-zero on the runner but exit-0 locally — CI-specific). **Behind it, never-executed:**
  `Preflight generator source completeness`, `Generator gate`, `Security hardening maturity evidence`,
  `Runtime security consistency evidence`, `RuntimeHost gate`, `Editor gate`. The Windows job is the
  deeper onion — expect several more layers there.
- Already CONFIRMED green and not to be touched: Linux DSL/kernel/generator/CLI/postgres-ITs
  (Fix A), NEW-2 surface-evidence, Windows setup-python + surface-evidence.

---

## 8. HONEST: can this be closed, and what more is needed?

**This plan cannot close REG-17.** It advances it — keeps the record honest and surfaces the next
layer — but closure requires all of the following, none of which a mechanical executor can do:

1. **Capable-agent diagnosis + fixes, iteratively, one layer per round.** An unknown-but-finite number
   of never-executed CI steps remain (Windows especially). Each may surface a real finding. Budget
   several rounds of ~10–15 min CI + diagnosis each.
2. **A way to read the CI's captured failure output.** Several findings are CI-environment-specific and
   NOT locally reproducible (e.g. the Windows `npdev.bat` one). Diagnosing them needs the test's
   captured `outputText`, which today is only in the uploaded artifact `gh-api.sh` can't fetch. Someone
   must add on-failure log-dump steps to the workflow **or** enable artifact download.
3. **Possibly a real Windows CI/dev environment** to reproduce Windows-only failures the local Windows
   box can't (the local machine ran the failing test exit-0).
4. **An owner decision on the Definition of Done.** REG-17's DoD names *both* "CI green on hardware
   this project has never touched" **and** "a real external person." A full green
   `npdev-ci-validation.yml` run is the strong **automated** proxy and is likely sufficient — but only
   the owner can rule that CI-green closes REG-17 versus requiring a literal independent human/third
   party. **The executor must never make this call** (Task 5 Outcome GREEN stops at "pending owner
   decision").
5. **Time and patience.** This is convergent (severity/scope shrinks each round — 3 CI bugs → 2 → 1+new
   → …), not a treadmill, but it is genuinely multi-round.

**Bottom line:** REG-17 is closeable in principle — the mechanism is proven and every finding so far
has been real and fixable — but closure = (capable-agent fixes across all remaining layers) + (CI
failure-output access) + (a final green end-to-end run) + (an owner DoD ruling). This plan gets you a
faithful record and the next layer each round; it does not, and cannot, get you to green by itself.

---

## 9. STOP rules
STOP (write the report, change nothing more) if: the branch/tree is unexpected (Task 0) · the remote
is behind HEAD (Task 3) · any `gh-api.sh` call returns a JSON `message` error · a commit fails on the
slimness hook (run `pwsh -File scripts\hygiene\clean-workspace-state.ps1`, then retry the commit once;
if it still fails, STOP) · you are tempted to fix a red step, edit a workflow/code file, or push.

## 10. Do-not-touch
Any workflow YAML or code file (fixes are the capable agent's job) · `SchemaLifecycleExecutor` /
`StartupValidator` / any product code · the confirmed-green steps in §7 · REG-7.3/REG-8/REG-23/REG-25/
REG-26 boundaries · pushing to any branch.
