# Platform history — why the standing rules exist

**Status: historical.** Read this for narrative, never for status. Current rules live in
`CLAUDE.md`; current status lives in `ledger/`.

This file holds the reasoning that used to sit inline in `CLAUDE.md`. It was moved out on
2026-08-30: `CLAUDE.md` is re-billed on every model request and every subagent spawn, so narrative
kept there is paid for thousands of times a week, while the rule it justifies is read once. The
rules stayed; the stories moved here. Nothing was deleted.

---

## Why process documents are banned

Measured 2026-08-11: of 302 tracked `.md` files / 59,754 lines, **265 files / 39,705 lines were read
by nothing at all** — 88% of the files.

They accumulated one session at a time, because a run with no memory externalises its state as a
document, and the next run cannot tell which documents are still true. Then a gate reads one and it
can never be deleted. `check-register-consistency.py` parsed four such documents, until it too was
deleted; `extract_platform_status.py` parsed a fifth as a database until it was inverted to
`ledger/gaps.yml`.

**Reorganising does not fix this.** The 2026-08-11 pass moved 50 files into subdirectories and the
tracked total went 301 → 302. The number that actually moved was the one driven by the ratchet: the
`legacy` list of pre-ban files was frozen at 57 when the ban started and reached 28 by 2026-08-25,
as later waves fixed, removed, or converted files off it. Tracked `.md` total went 302 → 257 over the
same period — the same ongoing ratchet, not a new measurement methodology.

## Why no script may read a `.md` file

All 37 script↔markdown data couplings were inverted (md-zero-2026-08-11). The five surviving
exemptions are all markdown *linters* — link integrity, doc-entrypoint classification and its test
harness, pinned-download-link drift, hardcoded-path scanning. Their job **is** validating
hand-written prose, which is irreducible while 277 of 287 docs are hand-authored.

The `frozenCount: 5` pin was proven with a live RED (a synthetic 6th entry fails the gate) plus 22
`--calibrate` controls.

## Why the gate list is the shape it is

The three-gate shape was **measured at 811 s / ~13.5 min on 2026-08-08**, not the "seconds" the docs
claimed before that.

`run-kernel-quality-gate.ps1` was added 2026-08-25 (W3.2 / QUAL-32). The `kernelQualityGate` Gradle
task had existed and was invoked by nothing — no gate, no workflow — before that date, which is why
the recorded coverage floor had silently drifted 10 points from what the full suite actually
measures.

**GATE-SPLIT (2026-08-25 W4.5):** 11 of 42 checkers — the ones `run-weekly-paperwork-checks.ps1`
hosts — ran in no gate `run-all-gates.ps1` invoked at all. So "all gates green" was true on that
command while three of those eleven sat red in CI, unnoticed for a week. One of the three was two
gates contradicting each other.

The "never report gates green from a single gate" rule exists because that claim was made in three
consecutive move reports while a checker sat red.

## Why the canary boot timeout is 300 seconds

Two false REDs on 2026-08-08. The canary app starts in ~24 s; the rest of the budget goes on
`gradlew --no-daemon bootRun` forking a single-use Gradle daemon. That overhead — not the app —
produced "connection refused" while health, smoke, and acceptance all passed at a longer timeout.

The machine's RAM had been halved, which is also why `run-fast-gate.ps1` measures ~4.4 min against a
< 3 min design target.

## Why the scale ladder's 520-concept rung was misread

The knowledge card said 520 was informational because "CI runner physics, not an NPDev limit, is
expected to bound it first." **That was measured false (SCALE-2, 2026-08-17.)** The binding
constraint was NPDev's own generated code: `GeneratedConceptCrudController` took one constructor
parameter per concept, and the JVM caps a method at 255 (JVMS 4.3.3), so 255+ concepts emitted an app
that did not *compile* — a hard model-size ceiling, not a performance curve, reached long before any
runner resource limit.

**The ladder had been red on 260/520 every night since its first run and nobody had opened a failed
run**, so a never-green ladder was being read as headroom.

## Why the repo root is identified by contents, not name (REG-144)

Eleven copies of the external-build-root resolution walked up looking for a directory literally named
`NPDev_General`. GitHub checks this repo out as `NPDevGeneral`, so every copy fell through to its own
fallback — and those fallbacks started from different directories.

Measured in a real clone renamed to `NPDevGeneral`, running real Gradle: **three different build
roots in one checkout** (`<clone>/Build`, `<clone>/NPDevContract/Build`, `<clone>/../Build`). Gradle
wrote jars to one and `sync-runtimehost-libs.ps1` searched another, so three packaged-app proof tests
failed on Linux CI for twelve days.

**It passed locally the whole time because this machine's directory really is named `NPDev_General`
— the walks agreed by coincidence, not by construction, so no local gate could ever have caught it.**

A related instance: `run-fast-gate.ps1` repeated `Get-NPDevBuildRoot`'s answer as a literal while its
own docs claimed it called the function.

## Why the runtimehost-libs defaults had to be reconciled (LC-C4)

The sync script defaulted to `__OutsideRepo\runtimehost-libs` while `Build-NpdevApp.ps1` defaulted to
the Build root. Letting each use its own default meant the app silently kept a stale jar.

## Why `.npdev-root` alone does not identify this repo (MON-2)

Nothing had ever written a marker into a generated app: `git ls-files` showed exactly one, this
repo's own, while `CLAUDE.md` claimed generated apps carried one and `clean-sample-output.ps1`
retained `App\.npdev-root` as evidence. So a scan keyed on the marker pair found **zero** of the 118
apps in this machine's Build root. `OperationalRunbookEmitter` now writes it, and `npdev monitor`
accepts `_ops/resolved-db-plan.json` as the alternative half of the pair so older apps stay
discoverable.

## Why three seams must agree on spared directories

The Java layer (`FinalAppAssembler.PRESERVED_APP_DIRECTORIES`) runs LAST, in the same build, so a
directory added to a PowerShell `$SparedInsideApp` list and not to the Java one is spared and then
deleted again with no error. That is exactly what happened to `logs` between MONITOR_PLAN D10 and the
agent-proxy work.

## Why the generated-app test exclusion note flipped

Before commit `a4ea2ca1d` (2026-08-12), `build.gradle.template` excluded RuntimeHost tests naming
`com.npdev.generated.` unconditionally, and `CLAUDE.md` correctly said those tests did not run. The
commit moved the exclusion inside the `generatedRuntimeMountPresent()` guard, so they do run — but
the doc went unupdated long enough that a later audit (W5.4, 2026-08-25) re-discovered the
already-fixed state as if it were still live.

## Why twin-pair rules exist

REG-89, REG-104 and REG-112 were all "one place updated, its twin forgotten" bugs found
independently. `check-twin-pair-consistency.py` was written after the third so a fourth would not go
unnoticed.

REG-108's root cause was seams (1)+(2) of the four-place chain only — `roles` (RC-B1) and
`propertyScopes`/`properties` (RC-A1) had seams (3)/(4) from day one but were never added to (1)/(2),
so a pack (not an app root) declaring any of the three silently lost it. Root-only usage worked by
accident, which is why it hid.

## Why blocker-citation freshness was a rule

Move 15 Phase D item D1: five separate times a console/screen record said "blocked by X" while X had
already been closed in a later move. The mechanical gate that caught this,
`check-blocker-citation-freshness.py`, was deleted by md-zero-2026-08-11 Phase 2 along with the
closed-programme docs it scanned — 177 items across every ledger family it guarded were DONE.

## Why "MySQL and SqlServer are supported" was slow to claim

That line said "NOT supported" for a long time and was right to. **Eight** defects stood between a
complete dialect and a working app (STOR-4/5/7/9/10/11/12), every one found only by building the
artifact a user actually runs. Support was finally claimed on 2026-08-09 with CI run `31296993259`.

If you are tempted to widen a claim about an engine, that history is the argument for measuring
first.

## Why the DSL decomposition note exists

`SemanticValidator.java` and `TrustedSourceEmitter.java` were both on the do-not-full-read list until
the 2.B decomposition (2026-07-27/28) split them into orchestrators over sibling classes. They are
10 KB and 11 KB now. The note survives because their names still suggest large files.

## Why the editor was removed

`NPDevEditor/ui-react` source was parked outside the repo 2026-08-17; the frozen
`npdev-templates/static-react/` bundle it shipped was deleted 2026-08-20 (EDIT-12 / R10.3, owner
decision). Its replacement, `static/model-authoring.html` from `ModelAuthoringEmitter`, carries
starter templates, all seven scaffolding actions, and an editing surface, with zero calls back to the
app's own server.

## Where the token-consumption rules came from

Measured 2026-08-30 across 40 sessions and 242 subagent runs (2026-08-16 → 08-30):

| | requests | input-side tokens | avg context/request |
|---|---|---|---|
| Main sessions | 26,815 | 11.06 B | 412,348 |
| Subagents | 35,090 | 7.31 B | 208,330 |

81% of cost-weighted spend was cache **reads** — re-reading context already built. 35% of main-thread
requests happened above 500k context and consumed 53.5% of all spend. File content read across the
whole period totalled 11 MB, under 0.1% of the bill.

Specific wastes measured: 2,443 redundant same-session file re-reads (`npdev_cli.py` alone was read
371 times, 321 of them repeats); 9,016 of 19,221 shell calls opened with a redundant `cd` in five
different path spellings; 672 pure no-op polling calls (`true`, `echo waiting`, `sleep 1`) costing
319 M tokens; 1,028 permission rules of which 89% had no wildcard and so could match exactly once.

The `Session economics` block in `CLAUDE.md`, `scripts/ai/session_meter.py`,
`scripts/ai/run_digest.py`, `scripts/ai/build_symbol_map.py` and `scripts/ai/Prepare-Session.ps1`
all come from this measurement.
