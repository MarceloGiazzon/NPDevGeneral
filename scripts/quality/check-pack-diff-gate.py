#!/usr/bin/env python3
r"""PK-4 Stage A/B, CI half: does a branch's own change to a built-in pack carry a correctly-sized
version bump?

Runs the REAL Java engine (com.npdev.dsl.v1.pack.PackDiffEngine / PackPublishGate, via the
`:NPDevContract:dsl:packPublish` Gradle task and PackPublishMain -- the exact same mechanism
`npdev pack publish` wraps) against each of this repo's own built-in packs
(NPDevContract/packs/identity/pack.json, NPDevContract/packs/workspace/pack.json), comparing the
CURRENT working-tree content against that pack's content at `git merge-base HEAD origin/main` -- the
commit this branch actually diverged from, not an arbitrary number of commits back in full history.
If the branch's own change to a pack's content isn't matched by a correctly-sized version bump (a
BREAKING change with only a patch bump, an ADDITIVE change with no minor bump, or ANY content change
with no bump at all), this fails -- the same rule Stage B enforces at publish time, now enforced in
CI so a PR can't land a built-in pack change out of sync with its own declared version.

Why merge-base and not "the previous commit that touched this path" (this script's first design,
before being run for real against this repo -- see the commit that replaced it): identity/pack.json
and workspace/pack.json each accumulated real ADDITIVE and BREAKING content changes across this
repo's history from BEFORE pack versioning was an enforced concept at all (both still read
"version": "1.0.0" today). A history-relative comparison ("2 commits back") would find that old,
pre-enforcement drift and fail FOREVER, on every single run, regardless of what any given PR
actually changed -- exactly the false-red this script's own module contract (and the PK-4 card that
asked for it) says must not happen: "the check should pass trivially" when there's nothing this
BRANCH did wrong to report. A merge-base-relative comparison asks the right question instead: did
*this branch's own diff* to the pack carry the bump its own content change requires. A branch that
never touches either pack.json (the overwhelming common case) always gets a trivial pass, and the
old pre-enforcement drift stops being anyone's problem to fix.

Trivial-pass cases, by design (never a false red):
  - The pack's content at the merge-base is byte-identical to the current working tree (this
    branch never touched the pack -- the common case on nearly every run).
  - The merge-base itself, or origin/main, cannot be resolved at all (no network, no origin remote,
    a shallow/detached checkout with no history) -- there being no established baseline to diff
    against is the same "nothing to compare" case the PK-4 card's own test plan describes, not a
    failure of the pack.

Mirrors validate-corpus.py's own established shape: calls gradlew directly (not a nested pwsh
wrapper -- see that script's docstring for why the nested route mangles -P... arguments), and passes
--project-cache-dir explicitly so this doesn't put Gradle's project cache inside the source tree
(the workspace-slimness violation the pre-commit hook guards against).

Exit 0 when every pack's own branch-relative change (if any) carried a correctly-sized version bump,
1 otherwise.

    python scripts/quality/check-pack-diff-gate.py
    python scripts/quality/check-pack-diff-gate.py --json pack-diff-gate-report.json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GRADLEW = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")

# The two real built-in packs (CLAUDE.md's own source-of-truth layer 1). A third corpus pack,
# NPDevContract/packs/project-tracker-demo/pack.json, is sample/demo content rather than a built-in
# platform pack with a stability contract to enforce, so it is deliberately not checked here.
CHECKED_PACKS = (
    REPO_ROOT / "NPDevContract" / "packs" / "identity" / "pack.json",
    REPO_ROOT / "NPDevContract" / "packs" / "workspace" / "pack.json",
)


def project_cache_dir() -> Path:
    """Same placement rule as validate-corpus.py's project_cache_dir(): NPDEV_BUILD_ROOT if set,
    else <parent-of-repo>/Build, under gradle-cache/<repo-dir-name>."""
    external_root = Path(os.environ["NPDEV_BUILD_ROOT"]) if os.environ.get("NPDEV_BUILD_ROOT") \
        else REPO_ROOT.parent / "Build"
    return external_root / "gradle-cache" / REPO_ROOT.name


def resolve_merge_base() -> str | None:
    """The commit this branch diverged from `origin/main`, or None if it cannot be determined (no
    network/remote/history -- see module docstring for why that is a trivial pass, not a failure).
    Best-effort `git fetch` first (check-record-surfaces.py's own established pattern): a failed
    fetch still leaves a possibly-stale `origin/main` to try against rather than aborting outright.
    """
    subprocess.run(["git", "fetch", "origin", "main"], cwd=REPO_ROOT, capture_output=True, text=True)
    result = subprocess.run(
        ["git", "merge-base", "HEAD", "origin/main"],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip() or None


def content_at(commit: str, path: Path) -> str | None:
    rel = path.relative_to(REPO_ROOT).as_posix()
    result = subprocess.run(
        ["git", "show", f"{commit}:{rel}"],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None  # the path did not exist at that commit -- nothing to diff against
    return result.stdout


def run_pack_publish(old_pack: Path, new_pack: Path, report_path: Path) -> dict:
    cmd = [
        str(GRADLEW),
        "--project-cache-dir", str(project_cache_dir()),
        ":NPDevContract:dsl:packPublish",
        f"-PoldPack={old_pack}",
        f"-PnewPack={new_pack}",
        f"-PreportOut={report_path}",
        "--console=plain", "-q",
    ]
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True, timeout=300)
    if not report_path.exists():
        return {
            "allowed": False,
            "message": f"packPublish did not produce a report (gradle exit {proc.returncode}): "
                       + (proc.stdout + proc.stderr).strip()[-800:],
        }
    return json.loads(report_path.read_text(encoding="utf-8"))


def check_one(pack_path: Path, merge_base: str | None, tmp_dir: Path) -> dict:
    label = pack_path.relative_to(REPO_ROOT).as_posix()
    current_content = pack_path.read_text(encoding="utf-8")

    if merge_base is None:
        return {"label": label, "status": "skipped", "reason": "could not resolve merge-base with origin/main"}

    old_content = content_at(merge_base, pack_path)
    if old_content is None:
        return {"label": label, "status": "skipped", "reason": "did not exist at the merge-base -- newly added on this branch"}
    if old_content == current_content:
        return {"label": label, "status": "skipped", "reason": "unchanged relative to origin/main's merge-base"}

    old_path = tmp_dir / (pack_path.stem + "-old.json")
    old_path.write_text(old_content, encoding="utf-8")
    report_path = tmp_dir / (pack_path.stem + "-report.json")

    report = run_pack_publish(old_path, pack_path, report_path)
    return {
        "label": label,
        "status": "allowed" if report.get("allowed") else "refused",
        "report": report,
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", default=None, help="also write the full result set to this path")
    args = ap.parse_args(argv[1:])

    merge_base = resolve_merge_base()
    results = []
    with tempfile.TemporaryDirectory(prefix="npdev-pack-diff-gate-") as tmp:
        tmp_dir = Path(tmp)
        for pack_path in CHECKED_PACKS:
            if not pack_path.exists():
                results.append({"label": str(pack_path), "status": "missing",
                                 "reason": "declared checked pack does not exist on disk"})
                continue
            results.append(check_one(pack_path, merge_base, tmp_dir))

    print(f"Pack diff gate: {len(results)} built-in pack(s) checked"
          + (f" against merge-base {merge_base[:12]}.\n" if merge_base else " (no merge-base resolved).\n"))
    for r in results:
        if r["status"] == "skipped":
            print(f"  SKIP     {r['label']}: {r['reason']}")
        elif r["status"] == "missing":
            print(f"  MISSING  {r['label']}: {r['reason']}")
        elif r["status"] == "allowed":
            report = r["report"]
            print(f"  OK       {r['label']}: {report.get('message')}")
        else:
            report = r.get("report", {})
            print(f"  FAIL     {r['label']}: {report.get('message', r.get('reason', 'refused'))}")

    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"\nFull results written to {args.json}")

    failing = [r for r in results if r["status"] in ("refused", "missing")]
    if failing:
        print(f"\nFAIL: {len(failing)} pack(s) changed on this branch without a correctly-sized version bump:", file=sys.stderr)
        for r in failing:
            report = r.get("report", {})
            print(f"  - {r['label']}: {report.get('message', r.get('reason'))}", file=sys.stderr)
        return 1

    print("\nOK: every built-in pack changed on this branch has a correctly-sized version bump.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
