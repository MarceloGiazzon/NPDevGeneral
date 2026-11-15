#!/usr/bin/env python3
"""Run the checks `select_checks.py` selects (FORM P1-T4) -- the executor half of "fewer gates".

select_checks.py only SELECTS. This runs the selection, using each registry entry's `run` field
(an argv list, executed from the repo root; `{python}` expands to this interpreter, `{gradlew}` to the root Gradle wrapper). Entries with
no `run` (null) are manual runbooks / CI-only: they are listed as NOT RUN and never silently
counted as green.

    python scripts/quality/run_selected.py                      # profile checkLevel, changes vs origin/main + working tree
    python scripts/quality/run_selected.py --level core
    python scripts/quality/run_selected.py --level high         # whole levels run regardless of triggers
    python scripts/quality/run_selected.py --dry-run            # print the plan, run nothing
    python scripts/quality/run_selected.py --stop-on-failure

- Entries that share one `run` command (e.g. several that live inside run-fast-gate.ps1) run it ONCE;
  the result is recorded against every entry id that shares it.
- Each result is recorded in the cadence ledger (cadence_state.py record), so `npdev verify` staleness stays honest.
- Exit 0 iff nothing failed. "Nothing selected" is a pass: nothing changed, nothing to prove.
  Entries that could not run here are reported on their own line and do not fail the run.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import select_checks  # noqa: E402


def gradlew() -> str:
    """The wrapper lives at the repo root (root Gradle wires :NPDevContract:dsl)."""
    return str(REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))


def expand(argv: list[str]) -> list[str]:
    tokens = {"{python}": sys.executable, "{gradlew}": gradlew()}
    return [tokens.get(part, part) for part in argv]


def record(check: dict, passed: bool, seconds: float) -> None:
    cmd = [sys.executable, str(HERE / "cadence_state.py"), "record", "--id", check["id"], "--tier", check["tier"],
           "--result", "passed" if passed else "failed", "--duration-seconds", f"{seconds:.1f}"]
    subprocess.run(cmd, cwd=REPO_ROOT, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--level", default=None, help="profile name or registry level (default: the profile's checkLevel; CI=true: full)")
    ap.add_argument("--base", default="origin/main", help="revision the committed changes are measured from")
    ap.add_argument("--changed", nargs="*", default=None, help="explicit changed paths instead of asking git")
    ap.add_argument("--dry-run", action="store_true", help="print the plan and run nothing")
    ap.add_argument("--stop-on-failure", action="store_true")
    opts = ap.parse_args(argv[1:])

    profile = select_checks.load_profile()
    level = opts.level or ("full" if os.environ.get("CI") == "true" else profile["checkLevel"])
    level_names = select_checks.levels_for(level, profile)
    changed = ([p.replace("\\", "/") for p in opts.changed] if opts.changed is not None
               else select_checks.changed_paths(opts.base))
    selected = select_checks.select(select_checks.load_checks(), level_names, changed)
    budget = sum(select_checks.BUDGET_SECONDS[name] for name in level_names)

    runnable: dict[tuple[str, ...], list[dict]] = {}
    manual: list[dict] = []
    for check in selected:
        run = check.get("run")
        if run:
            runnable.setdefault(tuple(run), []).append(check)
        else:
            manual.append(check)

    estimate = sum(max(c["costSeconds"] for c in group) for group in runnable.values())
    print(f"== NPDev checks: level {level} ({', '.join(level_names)}); {len(changed)} changed path(s) ==")
    print(f"{len(selected)} selected -> {len(runnable)} command(s) to run, ~{estimate}s of a {budget}s budget; "
          f"{len(manual)} not runnable here")
    if estimate > budget:
        slow = sorted(runnable.items(), key=lambda kv: -max(c["costSeconds"] for c in kv[1]))[:3]
        print("-- OVER BUDGET; slowest: " + ", ".join(
            f"{'+'.join(c['id'] for c in group)} ({max(c['costSeconds'] for c in group)}s)" for _, group in slow))
    for run, group in runnable.items():
        print(f"  run   {' + '.join(c['id'] for c in group)}  <-  {' '.join(run)}")
    for check in manual:
        print(f"  MANUAL {check['id']}  <-  {check['invokedBy']}")

    if opts.dry_run:
        return 0

    results: list[tuple[str, bool, float]] = []
    for run, group in runnable.items():
        label = " + ".join(c["id"] for c in group)
        print(f"\n--- {label} ---", flush=True)
        started = time.monotonic()
        code = subprocess.run(expand(list(run)), cwd=REPO_ROOT).returncode
        seconds = time.monotonic() - started
        passed = code == 0
        for check in group:
            record(check, passed, seconds)
        results.append((label, passed, seconds))
        print(f"--- {label}: {'PASSED' if passed else 'FAILED (exit %d)' % code} in {seconds:.0f}s ---", flush=True)
        if not passed and opts.stop_on_failure:
            print("--stop-on-failure: not running the remaining checks.")
            break

    ran = {label for label, _, _ in results}
    skipped = [" + ".join(c["id"] for c in group) for run, group in runnable.items()
               if " + ".join(c["id"] for c in group) not in ran]
    print("\n== Summary ==")
    for label, passed, seconds in results:
        print(f"  {'passed' if passed else 'FAILED':7} {seconds:6.0f}s  {label}")
    for label in skipped:
        print(f"  not-run         {label}")
    for check in manual:
        print(f"  manual          {check['id']}  (no `run` command: runbook or CI only)")
    failed = [label for label, passed, _ in results if not passed]
    if failed or skipped:
        print(f"\nNOT GREEN: {len(failed)} failed, {len(skipped)} not run.")
        return 1
    if not selected:
        print("Nothing selected: no changed path needs a check at this level.")
    else:
        print(f"\nGREEN: {len(results)} command(s) passed" + (f"; {len(manual)} manual check(s) NOT run." if manual else "."))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
