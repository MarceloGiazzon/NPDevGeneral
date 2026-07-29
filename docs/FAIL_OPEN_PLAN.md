# Fail-Open Plan

> **STATUS: ACTIVE.** Live backlog. Written 2026-07-29 against `beta1-vision-spine` @ `deff9d3`
> (`origin/main` `4abe231` = `beta1.3`, repo public, ledger **70 items / 0 open**, knowledge gate
> **18 steps**, DSL suite 372/0).
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\FAIL_OPEN_PLAN.md" "D:\WorkSpace\NPDev\NPDev_General\docs\FAIL_OPEN_PLAN.md"
> ```
>
> **Scope:** the 5 items open after `RECORD_SURFACES_PLAN.md` closed P1–P6, plus one structural
> finding surfaced while scoping them. **~half a day plus two owner actions.**
>
> Facts are **MEASURED** (git, source, gates — 2026-07-29) or **PROPOSED**.
> Each item gives **What · Why · Where · How · DoD**.

---

## Item index

| # | Item | Class | Sev | New? | Effort |
|---|---|---|---|---|---|
| **R1** | Calibration controls **fail open** — an unreachable revision is skipped, not failed | class residual | 🟡 MED | 🆕 | 45 min |
| **R2** | 3 DSL features are only covered by **non-git** models — allowlisted rather than fixtured | coverage | 🟡 MED | 🆕 | 2 hr |
| **R3** | Allowlists are no longer empty and have no review trigger | governance | 🟢 LOW | 🆕 | 30 min |
| **R4** | Local branch 4 commits behind `origin/main`; the detector measures only *ahead* | hygiene | 🟢 LOW | 🆕 | 15 min |
| **R5** | 🔒 Token live in chat **and** in `Downloads\ghToken.txt` | security | 🔴 **HIGH** | ⏳ ×5 | 15 min ⬥ |
| **R6** | Three outreach conversations | strategic | ★ | ⏳ ×9 | ⬥ |

---

# R1 · Calibration controls fail open

**What.** A `--calibrate` control whose pinned git revision cannot be read is **not reported at all** —
neither PASS nor FAIL. The script then exits 0 because nothing failed.

**Why it matters.** MEASURED — the mechanism, in `check-register-consistency.py`:

```python
def git_show(path, revision="HEAD") -> str | None:
    try:
        return subprocess.run([...], check=True).stdout
    except subprocess.CalledProcessError as exc:
        print(f"  ERROR: could not read {revision} revision of {path.name}", file=sys.stderr)
        return None                                   # ← prints, returns, does not fail
...
if head_tree_text is not None and head_register_summary is not None:
    report(..., expect_fire=True)                     # ← control simply does not run
```

**This is exactly how the pre-`fetch-depth` rot went unnoticed.** The last session fixed the
*condition* (a shallow clone, now `fetch-depth: 0`) but not the *behaviour*. Any recurrence
re-creates the silent skip: a rebase orphaning a pinned SHA, a fork without full history, a runner
with a partial fetch, a future workflow edit dropping the setting.

It is the third layer of one lesson — **T1** pinned the SHAs, **fetch-depth** made them reachable,
**R1** makes unreachability loud. Without R1 the class is not closed, only its current trigger.

**Scope is narrower than "every calibrate script."** MEASURED — guarded `report()` calls per script:

| Script | `report()` | git-dependent | guarded ifs |
|---|---|---|---|
| `check-register-consistency.py` | 16 | 9 | **6** ← highest risk |
| `check-panel-provenance-schema.py` | 5 | 0 | **2** |
| `check-narrative-status-drift.py` | 5 | 4 | 0 |
| `check-record-surfaces.py` | 5 | 5 | 0 |
| all others | 3 each | 0–2 | 0 |

Only two scripts can currently skip a control. The git-dependent ones with zero guards would throw
instead — loud, which is fine.

**Where.** `scripts/quality/check-register-consistency.py` (`calibrate()`, ~lines 855–905) ·
`scripts/quality/check-panel-provenance-schema.py`.

**How to solve.** Assert the **control count**, not the control outcomes:

```python
EXPECTED_CONTROLS = 16   # every report() this calibrate() intends to run

def calibrate() -> int:
    reported = 0
    ...                                   # report() increments reported
    if reported != EXPECTED_CONTROLS:
        print(f"FAIL: expected {EXPECTED_CONTROLS} controls, {reported} reported -- "
              f"{EXPECTED_CONTROLS - reported} skipped (unreachable revision?). "
              f"A skipped control proves nothing; treat it as a failure.")
        return 1
```

Same discipline as the empty-allowlist convention already used here: **absence must be loud.**

**Calibrate the calibration.** Temporarily pin one control to a bogus SHA (`deadbeef`), confirm the
run goes **RED** with the skipped-count message, then restore and confirm GREEN. That is the RED-first
proof this repo requires everywhere else, applied to the layer that proves the others.

**Definition of done.**
- [ ] Both scripts with guarded controls assert an expected control count
- [ ] A deliberately bogus pinned SHA produces a loud FAIL, not a quiet exit 0 — proven RED→GREEN
- [ ] The gate's run-all-calibrate step still passes on a clean tree
- [ ] The convention is stated once in `CONTRIBUTING.md`: *a control that cannot run is a failure, not a skip*

---

# R2 · Three DSL features are covered only by non-git models

**What.** The first non-empty entries in `dsl-coverage-allowlist.json` are three DSL features cleared
because their only corpus examples live outside git:

```jsonc
"fragments":           { "why": "Only real example is AppGen/apps/npdev_split_model_sample_app,
                                 a non-git developer-machine-only directory (CLAUDE.md 'Layers')
                                 -- absent on a bare CI checkout. REG-69." }
"packs":               { "why": "Only real example is AppGen/apps/_official/WmsOffice, absent on a
                                 bare CI checkout. Same shape. REG-69." }
"step.updateConcept":  { "why": "Only real example is AppGen/apps/_official/WmsOffice … REG-69." }
```

**Why it matters.** The reasoning is correct and the filing is honest — but the *conclusion* is worth
revisiting, because it exposes something structural the allowlist hides:

> **The corpus is smaller in CI than it is locally.** MEASURED: 29 corpus models = ~9 in-git
> (`NPDevSamples/`) + ~20 out-of-git (`AppGen/apps`, CLAUDE.md Layer 2, deliberately non-git).
> Every gate that reads `AppGen/apps` is therefore weaker on a bare checkout than on your machine —
> which is exactly the environment a contributor, a CI runner, and a stranger all use.

This is the same shape as the 17-model break (REG-63): work deferred to the non-git layer becomes
invisible to everything that checks. There it cost three weeks; here it is being institutionalised as
an allowlist entry.

**`dsl-conformance-max` exists precisely so coverage does not depend on non-git models** — and it is
in git. Allowlisting these three concedes the exact gap the fixture was built to close.

**Where.** `NPDevSamples/dsl-conformance-max/Input/model.json` · `scripts/quality/dsl-coverage-allowlist.json` ·
`ledger/items/REG-69.yml`.

**How to solve.** Add the three features to the fixture instead of excusing them. Feasibility differs:

| Feature | Feasibility | Approach |
|---|---|---|
| **`step.updateConcept`** | ✅ **Trivial** — it is a canonical step type | Add an `updateConcept` step to `RecordOrderLines`; validate |
| **`packs`** | ⚠️ Moderate | Declare a minimal pack. Check whether a pack needs out-of-tree assets; if it does, that is itself a finding |
| **`fragments`** | ⚠️ Moderate | Needs one `$ref`-ed sibling file — the shape `npdev_split_model_sample_app` proves works |

**Do `updateConcept` first** — it is minutes and removes an allowlist entry outright. Then attempt
`packs` and `fragments`; if either genuinely cannot be expressed in an in-git fixture, *that* is the
finding to file, and the allowlist entry then rests on a proven limit rather than an untried one.

**Definition of done.**
- [ ] `step.updateConcept` covered by `dsl-conformance-max`; its allowlist entry deleted
- [ ] `packs` and `fragments` either covered, or their allowlist entries updated to cite a *proven*
      impossibility rather than "the only example is non-git"
- [ ] REG-69 records the in-git/out-of-git corpus split explicitly — it is the durable lesson
- [ ] Corpus gate still 29/29 (30/30 if the fixture gains a sibling `$ref` file)

---

# R3 · Allowlists are no longer empty and nothing reviews them

**What.** Every gate shipped so far carried an empty allowlist and the convention *"never pre-clear
speculatively."* That invariant is now gone. MEASURED:

```
corpus-parse-allowlist.json                 0
test-task-coverage-allowlist.json           0
dsl-coverage-allowlist.json                 3   ← new (REG-69)
plan-deferral-citation-allowlist.json       8
security-pattern-sweep-allowlist.json     281
```

**Why it matters.** An empty allowlist is self-policing: any entry is visible. A populated one needs a
review trigger, or it becomes the place findings go to be forgotten — and 281 entries is already past
the point where anyone re-reads them casually.

None of this is wrong today. The `security-pattern-sweep` entries were built by triage and survived a
path-keying migration (the 37 orphaned verdicts). The 8 deferral citations came from T4's real-document
triage. The 3 new ones are reasoned. **The gap is that nothing causes them to be re-examined.**

**Where.** The five allowlist files · `scripts/quality/run-ai-knowledge-gate.ps1`.

**How to solve.** The cheapest useful discipline, not an audit:

1. **Require an expiry or a review-by marker** on new entries — a date, or the REG id whose closure
   should retire it. The three new DSL entries already cite REG-69; make that the norm.
2. **Report counts in the gate output** — *"allowlists: corpus 0, dsl-coverage 3, deferral 8,
   security-sweep 281"*. A number that grows in the log is noticed; a file nobody opens is not.
3. **Fail on growth without a citation** — a new entry lacking a `why` **and** a REG/B id is rejected.
   That preserves the original discipline without demanding anyone re-read 281 rows.

Explicitly **not** proposed: auditing the 281 security entries. They were triaged once, survived a
migration, and re-litigating them has no trigger.

**Definition of done.**
- [ ] New allowlist entries require `why` + a REG/B citation; enforced
- [ ] The gate prints allowlist counts every run
- [ ] The convention is recorded in `CONTRIBUTING.md`

---

# R4 · Local branch is behind, and the detector only looks one way

**What.** MEASURED: `beta1-vision-spine` is an ancestor of `origin/main`, **4 commits behind** — the
normal post-merge state after PR #7. `check-record-surfaces.py` measures commits **ahead** of
`origin/main` only.

**Why it matters.** Low, but the asymmetry will bite. The detector's rationale was *"main is what a
stranger clones"* — correct for the ahead direction. A working branch that sits behind produces
confusing merges and lets someone build on a stale base without noticing; that is how a 71-commit gap
starts in the other direction.

**Where.** `scripts/quality/check-record-surfaces.py`.

**How to solve.**
1. `git checkout beta1-vision-spine && git pull` — one command.
2. Extend the check to report **both** directions: fail on >50 ahead (existing), and **warn** on any
   behind, since being behind is normal briefly but never desirable for long.

**Definition of done.**
- [ ] Local branch is current with `origin/main`
- [ ] The detector reports ahead **and** behind; warn-on-behind, fail-on-far-ahead
- [ ] Calibrated: a synthetic behind-state produces the warning

---

# R5 ⬥ 🔒 · Revoke the token — fifth flag, and it is now on disk too

**What.** `ghp_msPO…` remains live. It is in this conversation's history **and** in
`Downloads\ghToken.txt`.

**Why it matters.** Only open item with a blast radius outside this machine, on a **public** repo. It
has been flagged five times across four sessions. The file on disk is new information and changes the
remediation order.

**How to solve — order matters:**

1. **Revoke first.** GitHub → Settings → Developer settings → Personal access tokens → delete.
2. **Then delete `Downloads\ghToken.txt`.** Deleting the file first accomplishes nothing — the token
   stays valid, and the chat copy still exists. Only revocation invalidates it.
3. Replacement only if needed: narrowly scoped, set in the environment, never typed into a chat and
   never written to a file in a synced or scanned folder.
4. Check Settings → Security log for unrecognised use.

**Definition of done.**
- [ ] Token absent from the GitHub token list
- [ ] `Downloads\ghToken.txt` deleted **after** revocation
- [ ] Security log checked
- [ ] Any replacement never entered in chat or stored in a plaintext file

---

# R6 ⬥ ★ · Three outreach conversations — ninth plan

**What.** Unchanged: 3.2 → P2.4 → P6.3 → R-O2 → F11 → G5 → T7 → P8 → here.

**Why it matters.** `docs/HUMAN_VS_AI_VERIFICATION.md` settled the argument on this project's own
data: the AI loop is the **better tester** (70 items, 14 HIGH, 0 regressions, 3 real security findings
from external review, 3 onboarding defects from one blind run) and **cannot** produce three signals at
any budget — abandonment, domain fit, worth. B20 explicitly waits on it.

R2 sharpens the case: the DSL-coverage allowlist exists because part of the corpus lives outside git —
a decision made for local convenience that a contributor would hit immediately and question. **That
is precisely the class of finding only an outsider produces**, and it took nine plans of internal
work to surface indirectly.

**How to solve.** Per that document §5.1: **one conversation, zero installs.** A GeneXus/legacy-4GL
shop; one question: *"Would this have helped on your last migration, and if not, what would it have
needed?"* Then §5.2's untried simulation — an agent given an unfamiliar domain and no NPDev-specific
instructions.

**And the queue rule that document names:** this item must not live in the same backlog as engineering
tasks. It has no DoD an agent can execute, so it loses every prioritisation contest — nine times now.

**Definition of done.**
- [ ] One conversation held; the answer written down
- [ ] §5.2's domain simulation run
- [ ] B20 triggered or consciously re-deferred **with the new evidence**

---

# Sequencing

```
NOW (15 min)    R5  ⬥ revoke, THEN delete Downloads\ghToken.txt   🔒

DAY 1 (~half)   R1  fail-open guard  ★  ← closes the third and last layer of the calibrate lesson
                     └─ calibrate it with a bogus SHA before fixing anything else
                R2  updateConcept into the fixture (minutes); then packs / fragments
                R3  allowlist citation rule + counts in the gate log
                R4  git pull; extend the detector to report "behind"

ANYTIME ⬥       R6  one conversation, zero installs
```

## Why this order

**R1 first among the technical work.** It is the guard that makes every other calibration trustworthy;
until it exists, a green `--calibrate` is a weaker claim than it appears. It is also the natural
completion of a lesson traced across three sessions.

**R2's `updateConcept` before the harder two** — it is minutes, removes an allowlist entry outright,
and proves the approach before spending time on `packs`/`fragments`.

**R5 before everything.** Fifteen minutes, no dependencies, and it now has two copies.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| R1's expected-count constant drifts as controls are added | **Medium** | Derive it if cheap; otherwise the count mismatch is itself the loud failure — self-correcting |
| R2's `packs`/`fragments` prove genuinely inexpressible in-git | Medium | That is a legitimate finding — update the allowlist to cite a *proven* limit, not an untried one |
| R3 turns into an audit of 281 security entries | **Medium** | Explicitly out of scope. New entries only |
| R4's warn-on-behind becomes noise right after every merge | Medium | Warn, never fail; being briefly behind is normal |
| R5 deferred a sixth time | Medium | Two copies now, one on disk |
| R6 slips a tenth plan | **High** | Take it out of this queue — `HUMAN_VS_AI_VERIFICATION.md` §6 |

## Overall definition of done

- [ ] A calibration control that cannot run **fails loudly** — proven with a bogus SHA
- [ ] No DSL feature is allowlisted merely because its only example is non-git
- [ ] Allowlist growth requires a citation; counts appear in every gate run
- [ ] Branch state is reported in both directions, and the local branch is current
- [ ] The token is revoked **and** the on-disk copy deleted, in that order
- [ ] **One conversation has happened, and what it said is written down**

---

*Companions: `docs/RECORD_SURFACES_PLAN.md` (predecessor) · `docs/INVOCATION_TOPOLOGY_PLAN.md`
(R1 closes its third layer) · `docs/HUMAN_VS_AI_VERIFICATION.md` (R6's reasoning and queue rule) ·
`ledger/items/REG-69.yml` (R2) · `docs/ACCEPTED_BOUNDARIES.md` B20/B26/B27 · `SECURITY.md` (R5).*
