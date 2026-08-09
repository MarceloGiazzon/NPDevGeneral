#!/usr/bin/env python3
"""E15: the environment toolbox must be the SAME for every engine -- proven by generating them.

WHAT THIS PROVES THAT check-engine-parity.py DOES NOT
----------------------------------------------------
That checker reads the generator's SOURCE and fails when an engine is special-cased. Useful, and
not the same claim: source with no `-eq 'Postgres'` in it could still emit different scripts per
engine through some other route. This generates a real app for each server engine and compares the
emitted toolbox.

    the five operations are BYTE-IDENTICAL across engines   <- the parity claim, directly
    each app's resolved-db-plan.json carries a COMPLETE profile
    no emitted file contains an absolute author path        <- E17

Byte-identical is the strong form and it is achievable because the scripts are parameterised: one
script, N data files. If a future change makes them differ, that is either a real regression or a
deliberate decision that has to be argued -- and either way someone should see it.

WHY THIS RUNS WITHOUT DOCKER
----------------------------
Generation needs no database. So this is deterministic, takes seconds, and can gate every push --
where `verify-engine-parity.ps1` (which actually RUNS Create/Stop/Status against real containers) is
the slower runtime half. Both are needed: this proves the scripts are the same, that one proves they
work.

USAGE
    python scripts/quality/run-engine-toolbox-parity.py
    python scripts/quality/run-engine-toolbox-parity.py --engines postgres,mysql,sqlserver
    python scripts/quality/run-engine-toolbox-parity.py --report <path>

Exit 0 = the toolbox is identical across engines. Exit 1 = it is not. Exit 2 = usage/setup problem.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import re
from pathlib import Path

# The five user-facing operations. These are the parity contract: a user doing the same thing with a
# different engine must get the same script, differing only in the data it reads.
OPERATIONS = [
    "Create-Environment.ps1",
    "Stop-Environment.ps1",
    "Status-Environment.ps1",
    "Print-DbConnectionInfo.ps1",
    "Reset-Environment.ps1",
]

# Every field a SERVER profile must carry into resolved-db-plan.json for those scripts to work. A
# missing one is how Create-Environment reports success before the database can serve.
REQUIRED_PROFILE_FIELDS = [
    "kind", "image", "guiLabel", "containerEnv", "readyProbe", "ensureDatabase",
]

DEFAULT_ENGINES = ["postgres", "mysql", "sqlserver"]

# A drive-rooted path. Deliberately narrow: this asks about paths baked into an EMITTED script, and
# every one of those is drive-rooted on the platform where this defect class lives (REG-144's
# family). Stops at a quote or whitespace so it captures the path and not the rest of the line.
#
# The lookbehind is not decoration: without it this matched the `p:/` inside
# `http://localhost:$($plan.serverPort)` and reported the smoke test's own base URL as an author
# path. A drive letter is a SINGLE character, so anything alphanumeric before it means this is a
# scheme, not a drive -- and `(?!/)` rejects the `//` that follows every URL scheme.
ABSOLUTE_PATH = re.compile(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/](?!/)[^'\"\s]*")


def repo_root() -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144)."""
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if all((candidate / m).is_dir() for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit("could not identify the repo root by contents")


def generate(root: Path, probe: Path, engine: str, work: Path) -> Path:
    """Generate one app for `engine` and return its _ops directory."""
    sys.path.insert(0, str(root / "NPDevCli"))
    import npdev_engines  # noqa: PLC0415 - path must be set first

    staged = work / "Input"
    if staged.exists():
        shutil.rmtree(staged)
    shutil.copytree(probe / "Input", staged)
    definition = npdev_engines.db_definition_for(
        engine, database_name=f"npdev_parity_{engine}", host="127.0.0.1",
        port=None, username="npdev", password="npdev")
    (staged / "db.definition.json").write_text(
        json.dumps(definition, indent=2) + "\n", encoding="utf-8")

    output = work / "App"
    completed = subprocess.run(
        [sys.executable, str(root / "NPDevCli" / "npdev_cli.py"), "generate", "app",
         "--model", str(staged / "model.json"),
         "--config", str(staged / "config.json"),
         "--output", str(output),
         "--require-db-definition"],
        cwd=str(root), capture_output=True, text=True)
    if completed.returncode != 0:
        raise SystemExit(
            f"generation failed for {engine} (exit {completed.returncode}).\n"
            f"{completed.stdout[-2000:]}\n{completed.stderr[-2000:]}")
    ops = output.parent / "_ops"
    if not ops.is_dir():
        raise SystemExit(f"{engine}: no _ops directory was emitted at {ops}")
    return ops


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engines", default=",".join(DEFAULT_ENGINES))
    parser.add_argument("--probe", default=None)
    parser.add_argument("--work", default=None)
    parser.add_argument("--report", default=None)
    args = parser.parse_args(argv)

    root = repo_root()
    probe = Path(args.probe) if args.probe else root / "NPDevSamples" / "probes" / "engine-probe"
    engines = [e.strip() for e in args.engines.split(",") if e.strip()]
    work_root = Path(args.work) if args.work else Path(
        os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build")) / "toolbox-parity"
    work_root.mkdir(parents=True, exist_ok=True)

    findings: list[str] = []
    per_engine: dict[str, dict] = {}
    for engine in engines:
        ops = generate(root, probe, engine, work_root / engine)
        plan = json.loads((ops / "resolved-db-plan.json").read_text(encoding="utf-8"))
        profile = plan.get("profile") or {}
        missing = [f for f in REQUIRED_PROFILE_FIELDS if not profile.get(f)]
        if missing:
            findings.append(f"{engine}: resolved-db-plan.json profile is missing {missing}")
        per_engine[engine] = {
            "opsDir": str(ops),
            "scripts": {op: digest(ops / op) for op in OPERATIONS if (ops / op).is_file()},
            "profileKind": profile.get("kind"),
            "readyProbeTimeoutSeconds": (profile.get("readyProbe") or {}).get("timeoutSeconds"),
        }
        for op in OPERATIONS:
            if not (ops / op).is_file():
                findings.append(f"{engine}: {op} was not emitted at all")

    # The parity claim itself: one script, N data files.
    reference = engines[0]
    for op in OPERATIONS:
        digests = {e: per_engine[e]["scripts"].get(op) for e in engines}
        distinct = {d for d in digests.values() if d}
        if len(distinct) > 1:
            findings.append(
                f"{op} DIFFERS between engines: {digests}. A user doing the same thing with a "
                f"different engine would read a different script -- which is the defect this "
                f"whole workstream exists to remove. If the difference is deliberate, it belongs "
                f"in the PROFILE (data), not in the script (code).")

    # E17 on the emitted artefacts -- and the discrimination IS the check.
    #
    # An emitted script legitimately names absolute paths: Build-FinalApp.ps1 has to Set-Location to
    # the app it builds, and that app is somewhere. Those are right by construction on the machine
    # that generated them. What is NOT legitimate is a path to somewhere this app has nothing to do
    # with -- the author's build root, baked into the emitter as a literal and shipped to a user for
    # whom it is simply wrong.
    #
    # So the question is not "is there an absolute path" (there always is) but "does this path lie
    # outside everything the plan itself records". The first version of this check asked the naive
    # question and reported nine findings, one of which was a COMMENT quoting the old hardcoded path
    # while explaining its removal -- a checker crying wolf at its own fix.
    for engine in engines:
        ops = Path(per_engine[engine]["opsDir"])
        plan = json.loads((ops / "resolved-db-plan.json").read_text(encoding="utf-8"))
        known = [str(plan[key]).replace("/", "\\").lower()
                 for key in ("finalAppPath", "opsRoot", "runtimeHostLibsDir", "resolvedDataRoot")
                 if plan.get(key)]
        for script in sorted(ops.glob("*.ps1")):
            for number, line in enumerate(
                    script.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                code = line.split("#", 1)[0]      # a comment that MENTIONS a path is not a path
                for match in ABSOLUTE_PATH.finditer(code):
                    found = match.group(0).replace("/", "\\").lower()
                    if any(found.startswith(root) for root in known):
                        continue
                    findings.append(
                        f"{engine}: {script.name}:{number} names an absolute path belonging to "
                        f"neither this app nor its libs ({match.group(0)}) -- it would ship to the "
                        f"user's machine and be wrong there (E17)")

    report = {
        "schemaVersion": "npdev-engine-toolbox-parity.v1",
        "engines": engines,
        "ok": not findings,
        "operations": OPERATIONS,
        "perEngine": per_engine,
        "findings": findings,
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print("Engine toolbox parity")
    print("=" * 78)
    for op in OPERATIONS:
        marks = {per_engine[e]["scripts"].get(op) for e in engines}
        state = "IDENTICAL" if len(marks) == 1 else "DIFFERS  "
        print(f"  {state}  {op}")
    for engine in engines:
        info = per_engine[engine]
        print(f"    {engine:<10} kind={info['profileKind']:<8} "
              f"readyProbeTimeout={info['readyProbeTimeoutSeconds']}s")
    if findings:
        print(f"\nFAILED: {len(findings)} parity finding(s)\n")
        for finding in findings:
            print(f"  - {finding}")
        return 1
    print(f"\nOK: the five operations are byte-identical across {', '.join(engines)}.")
    print("    One script, one data file per engine -- which is what makes them the same.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
