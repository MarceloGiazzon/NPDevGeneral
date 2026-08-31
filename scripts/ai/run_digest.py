#!/usr/bin/env python3
"""Run a command; write the full log to disk, print only its digest.

    python scripts/ai/run_digest.py -- ./gradlew :dsl:test
    python scripts/ai/run_digest.py --family npdev-gate -- pwsh -NoProfile -File scripts/quality/run-all-gates.ps1

The contract, in one line: *result first, drill down on failure only.*

Cost is (requests x average context).  A 13.5-minute gate run streamed whole into
an AI session raises the price of every later call in that session -- the output
is paid for once when produced and then again on every subsequent turn, because
each request re-bills the entire context.  The failing check names and their
assertion lines are the entire signal.  This wrapper keeps the full log on disk,
addressable and greppable, and puts a bounded digest on stdout.

Pattern knowledge lives in scripts/policy/output-digest-policy.json, never here:
to teach it a new tool, add a family to that file.

Exit code is the wrapped command's own, so this composes inside gates and CI.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

POLICY = Path(__file__).resolve().parents[1] / "policy" / "output-digest-policy.json"


def repo_root() -> Path:
    """The directory holding the three module dirs.

    Identified by CONTENTS, never by directory name -- REG-144: GitHub checks
    this repo out as `NPDevGeneral`, so any walk looking for a directory literally
    named `NPDev_General` falls through to a different fallback per call site.
    """
    here = Path(__file__).resolve()
    for cand in [here.parent.parent.parent, *here.parents]:
        if all((cand / m).is_dir() for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return cand
    return here.parent.parent.parent


def log_dir() -> Path:
    """Logs are build artifacts: they belong in the Build root, never the repo."""
    env = os.environ.get("NPDEV_BUILD_ROOT")
    base = Path(env) if env else repo_root().parent / "Build"
    d = base / "digest-logs"
    d.mkdir(parents=True, exist_ok=True)
    return d


def load_policy() -> dict:
    with open(POLICY, encoding="utf-8") as fh:
        return json.load(fh)


def pick_family(policy: dict, command: str, forced: str | None) -> dict:
    fams = policy["families"]
    if forced:
        for f in fams:
            if f["name"] == forced:
                return f
        raise SystemExit(f"unknown family '{forced}'; have: {', '.join(f['name'] for f in fams)}")
    low = command.lower()
    for f in fams:
        if any(d.lower() in low for d in f.get("detect", [])):
            return f
    return next(f for f in fams if f["name"] == "generic")


def compile_all(pats: list[str]) -> list[re.Pattern]:
    out = []
    for p in pats:
        try:
            out.append(re.compile(p))
        except re.error:
            pass
    return out


def any_match(pats: list[re.Pattern], line: str) -> bool:
    return any(p.search(line) for p in pats)


def digest(lines: list[str], fam: dict, defaults: dict, failed: bool) -> list[str]:
    """Select the lines worth putting in front of a reader."""
    verdict = compile_all(fam.get("verdict", []))
    failure = compile_all(fam.get("failure", []))
    noise = compile_all(fam.get("noise", []))

    before = defaults["contextBefore"]
    after = defaults["contextAfter"]
    cap = defaults["failureLines"] if failed else defaults["successLines"]

    keep: set[int] = set()

    # Verdict lines always survive -- they are the answer to "what happened".
    for i, ln in enumerate(lines):
        if any_match(verdict, ln):
            keep.add(i)

    # On failure, pull the failing lines and a window around each.
    if failed:
        anchors = [i for i, ln in enumerate(lines) if any_match(failure, ln)]
        # Cluster: consecutive stack frames are one failure, not thirty.
        for i in anchors:
            for j in range(max(0, i - before), min(len(lines), i + after + 1)):
                keep.add(j)

    if not keep:
        # Nothing matched; fall back to the tail, which is where verdicts live.
        keep = set(range(max(0, len(lines) - cap), len(lines)))

    ordered = sorted(keep)

    # Drop declared noise, then cap. Keep the LAST `cap` lines: for both a build
    # and a test run the conclusion is at the end, and a truncated head is more
    # useful than a truncated tail.
    ordered = [i for i in ordered if not any_match(noise, lines[i])]
    truncated = len(ordered) > cap
    if truncated:
        ordered = ordered[-cap:]

    out: list[str] = []
    prev = None
    maxlen = defaults["maxLineLength"]
    for i in ordered:
        if prev is not None and i > prev + 1:
            out.append(f"  ... {i - prev - 1} lines")
        ln = lines[i].rstrip()
        if len(ln) > maxlen:
            ln = ln[:maxlen] + " ..."
        out.append(f"  {ln}")
        prev = i
    if truncated:
        out.insert(0, "  (digest capped; full log has everything)")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--family", help="force a policy family instead of auto-detecting")
    ap.add_argument("--label", help="name for the log file (default: derived from the command)")
    ap.add_argument("--tail", type=int, help="also print the last N raw lines")
    ap.add_argument("--keep-going", action="store_true",
                    help="always exit 0 (report-only; the digest still shows the real code)")
    ap.add_argument("command", nargs=argparse.REMAINDER)
    args = ap.parse_args()

    cmd = args.command
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        ap.error("no command given; use: run_digest.py -- <command ...>")

    policy = load_policy()
    joined = " ".join(cmd)
    fam = pick_family(policy, joined, args.family)
    defaults = policy["defaults"]

    label = args.label or re.sub(r"[^A-Za-z0-9._-]+", "-", Path(cmd[0]).name + "-" + "-".join(cmd[1:3]))[:60]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    logpath = log_dir() / f"{stamp}-{label}.log"

    started = time.time()
    lines: list[str] = []
    with open(logpath, "w", encoding="utf-8", errors="replace") as log:
        log.write(f"# {joined}\n# started {stamp}\n\n")
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, errors="replace", bufsize=1)
        assert proc.stdout is not None
        for raw in proc.stdout:
            log.write(raw)
            lines.append(raw.rstrip("\n"))
        code = proc.wait()
    elapsed = time.time() - started

    failed = code != 0
    mark = "FAILED" if failed else "ok"

    shown = " ".join(joined.split())
    if len(shown) > 140:
        shown = shown[:140] + " ..."
    print(f"$ {shown}")
    print(f"  {mark}  exit={code}  {elapsed:.0f}s  {len(lines)} lines  family={fam['name']}")
    print(f"  log: {logpath}")
    print()
    for ln in digest(lines, fam, defaults, failed):
        print(ln)

    if args.tail:
        print(f"\n  --- last {args.tail} raw lines ---")
        for ln in lines[-args.tail:]:
            print(f"  {ln.rstrip()[:defaults['maxLineLength']]}")

    if failed:
        print(f"\n  full output: grep -n -i 'error\\|fail' '{logpath}'")

    return 0 if args.keep_going else code


if __name__ == "__main__":
    sys.exit(main())
