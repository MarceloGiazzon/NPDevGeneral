#!/usr/bin/env python3
"""Every checked-in browser routine must satisfy the PINNED engine routine schema.

MONITOR_PLAN A3, and specifically the half of A3 worth keeping. The original A3 said "write
`scrapforai-routine.schema.json` from the real routines"; measurement on 2026-08-10 said the engine
defines 32 actions and the corpus uses 9, so a schema induced from examples would reject 23 valid
actions and miss five constraints that only appear when the engine rejects you at runtime. So the
schema is PINNED from the engine (`scripts/quality/pin-routine-schema.py`) and the corpus becomes a
CONFORMANCE TEST against it. **Any rejection here is a defect in the ROUTINE, not the schema.**

TWO THINGS THIS CHECKS, AND WHY BOTH
------------------------------------
1. **Conformance.** Each routine, composed into the request the engine would actually receive
   (`npdev explore validate` composes it identically -- one composer, R10), validates against the pin.
2. **One corpus, one location.** A routine file that lives outside a `browser-routines/` directory is
   a failure. This is not tidiness: before 2026-08-10 the corpus was split -- 19 files loose in
   `NPDevSamples/scripts/browser/` and 1 in `NPDevSamples/scripts/browser/browser-routines/` -- and a
   glob written against either location passes while silently ignoring the other. A conformance test
   that misses a file is worse than no conformance test, because it reports green about work it never
   looked at.

Run standalone, or via `run-ai-knowledge-gate.ps1`. `--self-test` proves it can still detect a
broken routine (a checker that cannot detect the defect that already happened will not detect the
next one).
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))

import npdev_explore  # noqa: E402  (path must be set first)
import npdev_jsonschema  # noqa: E402

PINNED_SCHEMA = REPO_ROOT / "schemas" / "ai" / "scrapforai-routine.schema.json"
CORPUS_GLOB = "**/browser-routines/**/*.json"
SEARCH_ROOTS = ("NPDevSamples",)
# A routine is recognisable by CONTENT (`steps` that carry an `action`), never by filename -- the
# corpus already contains `01-populate-before.json` and `move1-xdock-routine.json`, so a name-based
# rule would miss half of it. Same instinct as REG-144, one layer down.
BASE_URL = "http://127.0.0.1:8080"


def looks_like_routine(payload: object) -> bool:
    if not isinstance(payload, dict):
        return False
    steps = payload.get("steps")
    if not isinstance(steps, list) or not steps:
        return False
    return any(isinstance(step, dict) and "action" in step for step in steps)


def find_corpus(root: Path) -> list[Path]:
    files: list[Path] = []
    for search_root in SEARCH_ROOTS:
        base = root / search_root
        if base.is_dir():
            files.extend(sorted(base.glob(CORPUS_GLOB)))
    return files


def find_strays(root: Path) -> list[Path]:
    """Routine-shaped JSON that is NOT under a `browser-routines/` directory -- the split-corpus
    regression this check exists to prevent recurring."""
    strays: list[Path] = []
    for search_root in SEARCH_ROOTS:
        base = root / search_root
        if not base.is_dir():
            continue
        for path in base.rglob("*.json"):
            parts = {p.name for p in path.parents}
            if "browser-routines" in parts or "build" in parts or "node_modules" in parts:
                continue
            try:
                payload = json.loads(path.read_text(encoding="utf-8-sig"))
            except (OSError, json.JSONDecodeError):
                continue
            if looks_like_routine(payload):
                strays.append(path)
    return strays


def check_one(schema: dict, path: Path) -> list[str]:
    try:
        routine = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        return [f"not valid JSON: {exc}"]
    except OSError as exc:
        return [f"unreadable: {exc}"]
    if not looks_like_routine(routine):
        return []
    request = npdev_explore.compose_engine_request(routine, BASE_URL)
    errors = npdev_jsonschema.validate(schema, request)
    return [f"{e['path']} {e['keyword']}: {e['message']}" for e in errors]


def run(root: Path) -> int:
    if not PINNED_SCHEMA.is_file():
        print(f"FAIL: the pinned routine schema is missing: {PINNED_SCHEMA}")
        print("      It is pinned from a RUNNING engine, never hand-written:")
        print("      python scripts/quality/pin-routine-schema.py --port <engine port>")
        return 1
    schema = json.loads(PINNED_SCHEMA.read_text(encoding="utf-8"))

    failures = 0
    strays = find_strays(root)
    for stray in strays:
        failures += 1
        print(f"FAIL {stray.relative_to(root)}")
        print("     a routine outside a `browser-routines/` directory. One corpus, one location --")
        print("     a split corpus is a glob that silently misses files, which is how a conformance")
        print("     test passes while ignoring a routine.")

    corpus = find_corpus(root)
    checked = 0
    for path in corpus:
        problems = check_one(schema, path)
        if problems:
            failures += 1
            print(f"FAIL {path.relative_to(root)}")
            for problem in problems[:6]:
                print(f"     {problem}")
        else:
            checked += 1

    print()
    print(f"routine corpus: {checked}/{len(corpus)} conform to the pinned engine schema"
          f"{f', {len(strays)} stray file(s)' if strays else ''}")
    if failures:
        print("A rejection here is a defect in the ROUTINE. The schema is the engine's, pinned, and")
        print("must never be hand-edited to make a routine pass.")
        return 1
    return 0


def self_test() -> int:
    """RED-proof, in-process. Copies the corpus rules onto temp files: one routine that must pass,
    one broken routine that must fail, and one stray in the wrong directory that must fail."""
    schema = json.loads(PINNED_SCHEMA.read_text(encoding="utf-8"))
    ok = True

    good = {"targetPath": "/npdev-business-ui/",
            "steps": [{"action": "goto", "url": "http://127.0.0.1:8080/", "label": "open"}]}
    bad = {"targetPath": "/npdev-business-ui/",
           "steps": [{"action": "teleport", "url": "http://127.0.0.1:8080/"}]}

    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        routines = base / "NPDevSamples" / "x" / "browser-routines"
        routines.mkdir(parents=True)
        (routines / "good.json").write_text(json.dumps(good), encoding="utf-8")
        if check_one(schema, routines / "good.json"):
            print("  FAIL  a valid routine was rejected -- the checker would red the whole corpus")
            ok = False
        else:
            print("  PASS  a valid routine passes")

        (routines / "bad.json").write_text(json.dumps(bad), encoding="utf-8")
        if not check_one(schema, routines / "bad.json"):
            print("  FAIL  an invalid action was ACCEPTED -- this checker proves nothing")
            ok = False
        else:
            print("  PASS  an unknown action is rejected")

        stray_dir = base / "NPDevSamples" / "x" / "somewhere-else"
        stray_dir.mkdir(parents=True)
        (stray_dir / "loose-routine.json").write_text(json.dumps(good), encoding="utf-8")
        if not find_strays(base):
            print("  FAIL  a routine outside browser-routines/ was not reported -- the split-corpus")
            print("        regression would come back unnoticed")
            ok = False
        else:
            print("  PASS  a routine outside browser-routines/ is reported")

    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(REPO_ROOT))
    parser.add_argument("--self-test", action="store_true",
                        help="Prove the checker can still tell a broken routine from a good one.")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(Path(args.root).resolve())


if __name__ == "__main__":
    raise SystemExit(main())
