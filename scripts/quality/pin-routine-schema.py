#!/usr/bin/env python3
"""Pin the ScrapForAI routine schema FROM THE ENGINE into `schemas/ai/scrapforai-routine.schema.json`.

MONITOR_PLAN A3. The engine owns the routine vocabulary (`src/schemas/explorationRequest.schema.ts`,
Zod) and enforces it at runtime; this repo pins what the engine served so `npdev explore validate`
and `check-routine-corpus-conformance.py` can answer offline.

IT REFUSES TO GUESS. If no engine is answering, this exits 2 with instructions rather than deriving
a schema by reading the engine's source. A schema that is a READING of the code is not the contract
the engine ENFORCES, and the difference is invisible until something is rejected in production --
which is exactly how the five constraints A3 documents (label <= 160, selector <= 500, value <= 5000,
stepId <= 80, evaluate/watch behind ALLOW_EVALUATE) were discovered on 2026-08-10.

    python scripts/quality/pin-routine-schema.py                # engine on its default port
    python scripts/quality/pin-routine-schema.py --port 3000    # wherever it is listening

Never hand-edit the pinned file. A hand edit is a second opinion about a contract this repo does not
own, and it will be silently wrong in whichever direction made a routine pass.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_OUT = REPO_ROOT / "schemas" / "ai" / "scrapforai-routine.schema.json"
META_OUT = REPO_ROOT / "schemas" / "ai" / "scrapforai-routine.schema.meta.json"
DEFAULT_PORT = 3010


def fetch(url: str, timeout: float = 5.0):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except Exception:
        return None


def engine_identity(root: Path | None) -> dict:
    identity: dict = {"name": "scrapforai", "version": None, "commit": None}
    if root is None or not root.is_dir():
        return identity
    package = root / "package.json"
    if package.is_file():
        try:
            payload = json.loads(package.read_text(encoding="utf-8"))
            identity["name"] = payload.get("name") or identity["name"]
            identity["version"] = payload.get("version")
        except Exception:
            pass
    try:
        completed = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=root,
                                   capture_output=True, text=True, timeout=5)
        if completed.returncode == 0:
            identity["commit"] = completed.stdout.strip()
    except Exception:
        pass
    return identity


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--engine-root", default=None,
                        help="Only to record the engine's version/commit in the .meta.json. "
                             "Detection is `npdev monitor engine`'s job, never a path typed here.")
    args = parser.parse_args()

    base = f"http://127.0.0.1:{args.port}"
    schema = fetch(f"{base}/v1/schema/routine.request.json")
    if schema is None:
        print(f"REFUSING TO GUESS: no engine answered {base}/v1/schema/routine.request.json")
        print()
        print("Start the engine and re-run. Find it with:")
        print("    npdev monitor engine --json")
        print()
        print("A schema derived from the engine's SOURCE is a reading of the code, not the contract")
        print("the engine enforces -- and the difference only shows up when something is rejected.")
        return 2

    SCHEMA_OUT.parent.mkdir(parents=True, exist_ok=True)
    SCHEMA_OUT.write_text(json.dumps(schema, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    existing = {}
    if META_OUT.is_file():
        try:
            existing = json.loads(META_OUT.read_text(encoding="utf-8"))
        except Exception:
            existing = {}
    root = Path(args.engine_root).expanduser() if args.engine_root else None
    existing.update({
        "schemaVersion": "npdev-pinned-schema-meta.v1",
        "pins": SCHEMA_OUT.name,
        "pinnedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": f"live-engine GET {base}/v1/schema/routine.request.json",
        "engine": engine_identity(root),
    })
    META_OUT.write_text(json.dumps(existing, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # Through npdev_explore, so the count printed here and the vocabulary the assistant's context
    # pack reports come from ONE extractor. A private copy here undercounted by missing $ref-hoisted
    # consts -- and a pin script that reports the wrong number is a pin script nobody can check.
    sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
    import npdev_explore  # noqa: E402

    actions = npdev_explore.schema_actions(schema)
    print(f"pinned  {SCHEMA_OUT.relative_to(REPO_ROOT)}  ({SCHEMA_OUT.stat().st_size} bytes)")
    print(f"meta    {META_OUT.relative_to(REPO_ROOT)}")
    print(f"actions {len(actions)}: {', '.join(actions)}")
    print()
    print("Now run: python scripts/quality/check-routine-corpus-conformance.py")
    return 0


def _walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk(value)


if __name__ == "__main__":
    raise SystemExit(main())
