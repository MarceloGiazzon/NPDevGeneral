#!/usr/bin/env python3
"""Pack-composition coverage check (PACK-8 / PACK-10 roadmap close-out).

check-dsl-coverage.py scans model.json + context fragments under the DSL-corpus roots, so pack-
composition features whose declarations live in pack.json (or in probe apps that find_models()
deliberately excludes) have no in-gate witness. This script closes that gap: it walks the same
corpus roots (AppGen/apps -- external to the repo -- plus NPDevSamples) and reports how many real
declarations exercise the three pack-composition surfaces:

  - packs[].from            remote pack import (OCI/git+ coordinate), PACK-8 / PK-5
  - concept.satelliteOf     satellite extension of a base concept, PACK-10 / PK-6
  - (pack-level) extends    a pack inheriting another pack, pack-composition depth

Unlike check-dsl-coverage.py it can INCLUDE NPDevSamples/probes/ (--probes): those are the live
pack-composition proofs (e.g. probes/p6-satellite-extension), kept out of DSL coverage only
because a probe is a narrow fixture, not general DSL evidence.

Corpus roots mirror check-dsl-coverage.py: the external AppGen apps directory (resolved from
$NPDEV_APPGEN_APPS or a sibling of the repo root, normally absent on a bare CI checkout) plus
NPDevSamples. Output dirs, .claude worktrees, and
storage probes (unless --probes) are excluded.

Exit status is informational (0 unless --strict and something has zero witnesses): this is a
visibility report, not the authoritative DSL gate -- check-dsl-coverage.py remains that.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

def _default_appgen_root() -> Path:
    """Layer 2 (app definitions) lives OUTSIDE the repo and is not a git repo, so CI never has it.
    Resolve it from the environment, then by walking up from this repo root looking for a sibling
    AppGen/apps -- by CONTENTS, never by assuming a drive letter (REG-144)."""
    from_env = os.environ.get("NPDEV_APPGEN_APPS")
    if from_env:
        return Path(from_env).expanduser().resolve()
    here = Path(__file__).resolve()
    for ancestor in here.parents:
        candidate = ancestor.parent / "AppGen" / "apps"
        if candidate.is_dir():
            return candidate
        if (ancestor / "NPDevContract").is_dir() and (ancestor / "NPDevKernel").is_dir():
            # the repo root, identified by contents -- stop walking
            return ancestor.parent / "AppGen" / "apps"
    return Path("AppGen") / "apps"


DEFAULT_APPGEN_ROOT = _default_appgen_root()


def _packs_iter(obj: object) -> list[dict]:
    if isinstance(obj, dict):
        raw = obj.get("packs")
        if isinstance(raw, list):
            return [p for p in raw if isinstance(p, dict)]
    return []


def _scan_root(root: Path, include_probes: bool, from_use: set, sat_use: set, ext_use: set) -> None:
    if not root.exists():
        return

    for f in sorted(root.rglob("model.json")) + sorted(root.rglob("pack.json")):
        rel = f.relative_to(root).as_posix()
        if "Output" in rel or ".claude" in rel or "/worktrees/" in rel:
            continue
        if rel.startswith("probes/") and not include_probes:
            continue
        try:
            data = json.loads(f.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            continue
        if not isinstance(data, dict):
            continue
        for p in _packs_iter(data):
            if p.get("from"):
                from_use.add(rel)
        for c in (data.get("concepts") if isinstance(data, dict) else None) or []:
            if isinstance(c, dict) and c.get("satelliteOf"):
                sat_use.add(rel)
        if rel.endswith("pack.json"):
            if data.get("extends"):
                ext_use.add(rel)
            if data.get("satelliteOf"):
                sat_use.add(rel)


def scan(roots: list[Path], include_probes: bool) -> tuple[set[str], set[str], set[str]]:
    from_use: set[str] = set()
    sat_use: set[str] = set()
    ext_use: set[str] = set()
    for root in roots:
        _scan_root(root, include_probes, from_use, sat_use, ext_use)
    return from_use, sat_use, ext_use


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--samples-root", default=str(Path(__file__).resolve().parents[2] / "NPDevSamples"))
    ap.add_argument("--appgen-root", default=str(DEFAULT_APPGEN_ROOT))
    ap.add_argument("--probes", action="store_true", help="include NPDevSamples/probes as witnesses")
    ap.add_argument("--strict", action="store_true", help="exit 1 if any surface has zero witnesses")
    args = ap.parse_args(argv[1:])

    roots = [Path(args.appgen_root), Path(args.samples_root)]
    from_use, sat_use, ext_use = scan(roots, include_probes=args.probes)

    # packs[].from is a REMOTE coordinate: resolving one needs a live git/OCI source at resolve time,
    # so it cannot exist as a static corpus fixture -- there are zero in NPDevSamples by construction,
    # and the only witness on a developer machine is the untracked external AppGen tree. That is what
    # made this check pass locally and fail on every CI run after it was wired in with --strict on
    # 2026-08-23. Its real proof is a Java test that builds a git repo and resolves a git+file://
    # coordinate end to end:
    #   NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/PackFromCoordinateResolutionTest.java
    # Reported here for visibility; not required.
    rows = [
        ("packs.from", from_use, False),
        ("concept.satelliteOf", sat_use, True),
        ("pack.extends", ext_use, True),
    ]
    print(f"Pack-coverage check: roots={[str(r) for r in roots]}, probes={'on' if args.probes else 'off'}\n")
    fails = 0
    for name, users, required in rows:
        example = f"  [{sorted(users)[0]}]" if users else ""
        marker = "OK" if users else "ZERO"
        note = "" if required else "  (reported, not required -- proven by PackFromCoordinateResolutionTest)"
        print(f"  {name.ljust(18)}  {len(users):2d} file(s)  [{marker}]{example}{note}")
        if not users and args.strict and required:
            fails += 1

    if fails:
        print("\nFAIL: --strict given and the surfaces above have no corpus witness.", file=sys.stderr)
        return 1
    print("\nOK: pack-composition coverage report generated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))