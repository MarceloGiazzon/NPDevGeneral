#!/usr/bin/env python3
"""A committed generated artefact must not be older than the source it is generated from.

WHY THIS EXISTS
---------------
PORT-1 found `$NPDevRoot = 'D:\\WorkSpace\\NPDev_General'` inside
`npdev-templates/static-react/assets/AuthoringApp.js` -- an author path AND a hardcoded repo folder
name, the exact pair REG-144 had eliminated from eleven resolution sites.

The SOURCE was already correct. `NPDevEditor/ui-react/src/authoring/pipeline/pipelineHandoff.ts`
emitted `$env:NPDEV_ROOT` and carried a REG-144 comment saying so. What shipped was the BUNDLE,
committed under `npdev-templates/` and never rebuilt after that fix. A string the source had deleted
went on being distributed to every generated app.

Nothing could have caught it. CLAUDE.md tells every reader the bundle is "generated bundle, ignore
entirely" -- correct advice for REVIEWING it (it is 141 KB of minified output), and exactly why a
stale copy is invisible. Reviewers skip it, greps skip it, and no gate compared it to its source.
PORT-1 fixed the instance by rebuilding; this is the class.

THE RULE
--------
For each declared (source tree -> committed artefact) pair, the artefact's most recent commit must
not be OLDER than the source tree's most recent commit. If it is, the source changed and nobody
rebuilt, so what ships no longer matches what the code says.

Deliberately git-timestamp based, not hash based. Hashing would mean either running the build in the
gate (minutes, plus node_modules the workspace-slimness policy forbids) or recording an input digest
that must itself enumerate every build input -- vite config, package-lock, the toolchain -- and
which silently under-reports the moment one is forgotten. "Was it rebuilt after the source changed?"
is the actual question, and git already knows.

KNOWN IMPRECISION, stated rather than hidden: a source commit that could not possibly affect the
bundle (a test, a comment) still makes this fail, and the remedy is a rebuild that produces a
byte-identical artefact. That is a real cost, paid deliberately -- the alternative failure mode is
shipping a stale artefact for weeks, which is what actually happened. Test files are excluded to
keep the common case quiet.

CALIBRATE BEFORE TRUSTING IT
----------------------------
    python scripts/quality/check-generated-bundle-freshness.py --calibrate

Builds a REAL throwaway git repo and drives the actual comparison through real commits -- an
assertion that `a > b` would prove nothing about the plumbing.

USAGE
-----
    python scripts/quality/check-generated-bundle-freshness.py
    python scripts/quality/check-generated-bundle-freshness.py --calibrate
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# (label, source tree, committed artefact, how to rebuild)
#
# NPDevEditor (the only entry this list ever had) was removed from the repo 2026-08-22. Its source
# tree no longer exists anywhere in the checkout, so "is the artefact older than its source" is no
# longer an answerable question -- there is no source left to compare against. The committed
# static-react bundle now ships frozen, with no in-repo way to rebuild it.
PAIRS: list[tuple[str, str, str, str]] = []

# Source changes that cannot reach the artefact. Keeps the common case from crying wolf.
EXCLUDE_GLOBS = (":(exclude)*.test.ts", ":(exclude)*.test.tsx",
                 ":(exclude)*.spec.ts", ":(exclude)*.spec.tsx",
                 ":(exclude)**/__tests__/**")


def last_commit_epoch(repo: Path, path: str, exclude: bool = False) -> int | None:
    args = ["git", "-C", str(repo), "log", "-1", "--format=%ct", "--", path]
    if exclude:
        args.extend(EXCLUDE_GLOBS)
    try:
        out = subprocess.run(args, capture_output=True, text=True, check=False).stdout.strip()
    except OSError:
        return None
    return int(out) if out.isdigit() else None


def check(repo: Path, pairs) -> list[tuple[str, str, str, int, int, str]]:
    stale = []
    for label, src, art, howto in pairs:
        s = last_commit_epoch(repo, src, exclude=True)
        a = last_commit_epoch(repo, art)
        if s is None or a is None:
            continue
        if s > a:
            stale.append((label, src, art, s, a, howto))
    return stale


def calibrate() -> int:
    """Drive the real comparison through a real git repo -- not a restatement of `a > b`.

    GIT_COMMITTER_DATE is set on every commit, not just --date. This matters and it was a real bug:
    `git log --format=%ct` reports the COMMITTER date, while `--date` sets only the AUTHOR date. The
    first version passed when run by hand and FAILED inside the gate, because under load all four
    commits landed in the same wall-clock second, making "source newer than artefact" false. A
    calibration that depends on how busy the machine is proves nothing -- it would have gone green
    again on the next run and been believed.
    """
    ok = True
    with tempfile.TemporaryDirectory() as td:
        repo = Path(td)
        def run(*a, when: str | None = None):
            env = dict(os.environ)
            if when:
                env["GIT_COMMITTER_DATE"] = when
                env["GIT_AUTHOR_DATE"] = when
            return subprocess.run(["git", "-C", str(repo), *a],
                                  capture_output=True, check=False, env=env)

        def commit(msg: str, when: str):
            run("add", "-A")
            r = run("commit", "-q", "-m", msg, when=when)
            return r.returncode == 0

        run("init", "-q")
        run("config", "user.email", "c@example.com")
        run("config", "user.name", "c")
        run("config", "commit.gpgsign", "false")

        (repo / "src").mkdir()
        (repo / "out").mkdir()
        pairs = [("t", "src", "out", "rebuild")]

        (repo / "src" / "a.ts").write_text("v1\n", encoding="utf-8")
        committed = commit("src v1", "2020-01-01T00:00:00 +0000")
        (repo / "out" / "bundle.js").write_text("built from v1\n", encoding="utf-8")
        committed &= commit("build", "2020-01-02T00:00:00 +0000")

        # If git refused to commit at all, every later assertion would pass vacuously (no commits ->
        # no timestamps -> no findings). Prove the fixture exists before trusting anything it says.
        print(f"  {'PASS' if committed else 'FAIL'}  the throwaway git fixture actually commits")
        ok &= committed

        fresh_ok = len(check(repo, pairs)) == 0
        print(f"  {'PASS' if fresh_ok else 'FAIL'}  an artefact built AFTER its source is fresh")
        ok &= fresh_ok

        (repo / "src" / "a.ts").write_text("v2\n", encoding="utf-8")
        commit("src v2", "2020-01-03T00:00:00 +0000")
        stale_fires = len(check(repo, pairs)) == 1
        print(f"  {'PASS' if stale_fires else 'FAIL'}  a source change with no rebuild fires")
        ok &= stale_fires

        (repo / "src" / "a.test.ts").write_text("t\n", encoding="utf-8")
        commit("test only", "2020-01-04T00:00:00 +0000")
        (repo / "out" / "bundle.js").write_text("built from v2\n", encoding="utf-8")
        commit("rebuild", "2020-01-05T00:00:00 +0000")
        (repo / "src" / "b.test.ts").write_text("t2\n", encoding="utf-8")
        commit("test only 2", "2020-01-06T00:00:00 +0000")
        test_quiet = len(check(repo, pairs)) == 0
        print(f"  {'PASS' if test_quiet else 'FAIL'}  a test-only source change stays quiet")
        ok &= test_quiet
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args()

    if args.calibrate:
        print("Calibrating check-generated-bundle-freshness:")
        return calibrate()

    stale = check(REPO_ROOT, PAIRS)
    print(f"Generated-artefact freshness: {len(PAIRS)} declared pair(s) checked.")

    if not stale:
        print("OK: every committed generated artefact is at least as new as its source.")
        return 0

    print(f"\nFAIL: {len(stale)} artefact(s) older than the source they are generated from.\n")
    for label, src, art, s, a, howto in stale:
        print(f"  {label}")
        print(f"    source   {src}   last commit {s}")
        print(f"    artefact {art}   last commit {a}   <-- OLDER")
        print(f"    rebuild:  {howto}")
        print()
    print("  A stale committed artefact ships a version of the code that no longer exists.")
    print("  PORT-1: the bundle carried a path the source had already deleted, and shipped it")
    print("  to every generated app, because nothing compared the two.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
