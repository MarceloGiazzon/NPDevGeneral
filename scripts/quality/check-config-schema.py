#!/usr/bin/env python3
"""config.json contract gate: every corpus config validates against the schema it points at.

WHY THIS EXISTS
---------------
Every `config.json` in this repo carries a `$schema` pointer to `config.schema.json`. **Nothing
validated it.** `Build-NpdevApp.ps1` only *writes* that property; the generator reads the file
without checking it; no gate looked.

Measured on 2026-08-08, the first time anything did:

    27 files, 93 errors

including `NPDevSamples/npdev-canary` -- the T1-frozen, shipped, working fast-gate canary -- failing
its own contract seven times. That is the declared-but-unenforced family this project already tracks,
in its purest form: a `$schema` pointer that lies.

**The schema was mostly the thing that was wrong**, which is why enforcement came second and the
contract was corrected first (storage/FULL_SUPPORT_PLAN.md W6.1, steps 1 and 2 before step 3):

  - `database` was `required`, and fifteen working apps have never had one. It is the scenario
    harness's provisioning block, not the app's persistence contract (that is db.definition.json).
  - `host`/`port`/`username`/`password` were required of every provider, including engines that do
    not listen on a port. Now conditional on the provider actually being a server engine -- so a
    postgres scenario with no host still fails, which is the half worth keeping.
  - `provider` allowed only `docker-postgres` and `postgres`, while DatabaseEngine has offered
    H2Local/H2Server/InMemory for a long time and MySQL/SqlServer since STOR-3.
  - `console` was forbidden, though four official apps declare it and
    run-app-upgrade-contract-gate.ps1 already branches on it.

After the correction: 27 files / 93 errors -> 1 file / 1 error, and that last one was a genuine typo
(`h2local-file`), fixed in the same commit. **Widening a schema nobody enforces is widening a
comment**, so both halves shipped together.

WHAT IT CHECKS
--------------
Every `config.json` under NPDevSamples/ (and, when present, the external AppGen apps directory --
layer 2, not a git repo, so it is checked when reachable and skipped with a printed note when not)
validates against NPDevContract/schemas/config.schema.json.

USAGE
    python scripts/quality/check-config-schema.py
    python scripts/quality/check-config-schema.py --json
    python scripts/quality/check-config-schema.py --appgen <dir>

Exit 0 = every reachable config validates. Exit 1 = at least one does not. Exit 2 = usage.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

SCHEMA = "NPDevContract/schemas/config.schema.json"


def _repo_root(explicit: str | None) -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144)."""
    if explicit:
        return Path(explicit).resolve()
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if all((candidate / m).is_dir()
               for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit("could not identify the repo root by contents. Pass --repo.")


def _configs(root: Path, appgen: Path | None) -> list[Path]:
    found = [p for p in sorted(root.joinpath("NPDevSamples").rglob("config.json"))
             if "Output" not in p.parts and "node_modules" not in p.parts]
    if appgen and appgen.is_dir():
        found += [p for p in sorted(appgen.rglob("config.json"))
                  if "Output" not in p.parts and "node_modules" not in p.parts]
    return found


def check(root: Path, appgen: Path | None) -> tuple[list[dict], int]:
    try:
        from jsonschema import Draft202012Validator
    except ImportError:  # pragma: no cover - environment problem, not a corpus problem
        raise SystemExit(
            "python package 'jsonschema' is required by this gate (pip install jsonschema). "
            "Refusing to skip: a validation gate that silently does nothing when its validator is "
            "missing is the exact shape of defect it was written to catch."
        )

    schema = json.loads((root / SCHEMA).read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)

    problems: list[dict] = []
    configs = _configs(root, appgen)
    for path in configs:
        try:
            instance = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            problems.append({
                "file": str(path), "path": "(file)", "message": f"not valid JSON: {exc}",
            })
            continue
        for error in sorted(validator.iter_errors(instance), key=lambda e: list(e.path)):
            problems.append({
                "file": str(path),
                "path": "/".join(str(part) for part in error.path) or "(root)",
                "message": error.message,
            })
    return problems, len(configs)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=None)
    parser.add_argument("--appgen", default=None,
                        help="External AppGen apps directory (layer 2). Defaults to "
                             "$NPDEV_APPGEN_APPS, then <build-root sibling>/AppGen/apps if present.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    root = _repo_root(args.repo)

    appgen: Path | None = None
    for candidate in (args.appgen, os.environ.get("NPDEV_APPGEN_APPS")):
        if candidate:
            appgen = Path(candidate).expanduser().resolve()
            break
    else:
        # Layer 2 lives OUTSIDE the repo and is not a git repo, so CI never has it. Look one level up
        # from the repo root -- by CONTENTS, never by assuming a drive letter (REG-144's rule applies
        # to every path in this repo, not just the build root).
        sibling = root.parent / "AppGen" / "apps"
        if sibling.is_dir():
            appgen = sibling

    problems, count = check(root, appgen)

    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-config-schema-report.v1",
            "ok": not problems,
            "configsChecked": count,
            "appGenAppsDir": str(appgen) if appgen else None,
            "problems": problems,
        }, indent=2))
        return 1 if problems else 0

    where = f" (+ AppGen apps at {appgen})" if appgen else " (AppGen apps dir not reachable -- skipped)"
    if not problems:
        print(f"check-config-schema: OK -- {count} config.json validated{where}.")
        return 0

    files = sorted({p["file"] for p in problems})
    print(f"check-config-schema: {len(problems)} error(s) across {len(files)} of {count} config.json{where}")
    current = None
    for problem in problems:
        if problem["file"] != current:
            current = problem["file"]
            print(f"\n  {current}")
        print(f"    {problem['path']}: {problem['message']}")
    print("\nEvery config.json carries a $schema pointer to NPDevContract/schemas/config.schema.json.")
    print("Either the file is wrong, or the schema is -- but the pointer must not be a lie.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
