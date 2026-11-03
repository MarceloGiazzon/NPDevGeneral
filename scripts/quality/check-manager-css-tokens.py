#!/usr/bin/env python3
"""THM-2/THM-5 ratchet: hard-coded colours in the Manager's own UI may only go DOWN.

Every colour the Manager draws should come from a `--t-*` token (NPDevManager/ui/tokens.css), which the theme
engine sets from the user's chosen theme. A hex literal in a stylesheet or script is a colour that ignores
the theme: it is wrong on Light, wrong on High contrast, and invisible to the contrast checker.

What counts: a `#rgb`, `#rrggbb` or `#rrggbbaa` literal in NPDevManager/ui/*.css, *.js and index.html,
outside the files in the baseline's `exempt` list (tokens.css holds the defaults by definition; the
theme-engine and a11y.css are byte-identical twins shared with generated apps and carry documented
fallbacks). `&#10227;` entities and `#some-id` selectors are not colours and are not counted.

The ratchet: `scripts/policy/manager-css-token-baseline.json` carries `frozenCount`.
  * more literals than frozenCount  -> FAIL (a new hard-coded colour);
  * fewer literals than frozenCount -> FAIL until frozenCount is lowered in the same commit, so the ceiling
    only ever ratchets down and a cleaned screen cannot quietly grow back.
`perFile` is informational: it shows where the remaining literals are.

Usage: python scripts/quality/check-manager-css-tokens.py [--update]
  --update rewrites frozenCount/perFile to the current count (only allowed to LOWER it).
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "NPDevManager" / "ui"
BASELINE = ROOT / "scripts" / "policy" / "manager-css-token-baseline.json"

HEX = re.compile(r"(?<![\w&#-])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![\w-])")


def scan(exempt: set[str]) -> dict[str, int]:
    per_file: dict[str, int] = {}
    files = sorted(list(UI.glob("*.css")) + list(UI.glob("*.js")) + [UI / "index.html"])
    for path in files:
        if path.name in exempt or not path.exists():
            continue
        n = len(HEX.findall(path.read_text(encoding="utf-8")))
        if n:
            per_file[path.name] = n
    return per_file


def main() -> int:
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    per_file = scan(set(baseline["exempt"]))
    total = sum(per_file.values())
    frozen = baseline["frozenCount"]
    print(f"Manager hard-coded colours: {total} (frozenCount {frozen}); exempt files: {', '.join(baseline['exempt'])}")
    if "--update" in sys.argv:
        if total > frozen:
            print(f"REFUSED: --update may only lower the ceiling ({total} > {frozen})")
            return 1
        baseline["frozenCount"] = total
        baseline["perFile"] = per_file
        BASELINE.write_text(json.dumps(baseline, indent=2) + "\n", encoding="utf-8")
        print(f"baseline updated to {total}")
        return 0
    if total > frozen:
        worst = ", ".join(f"{k}: {v}" for k, v in sorted(per_file.items(), key=lambda kv: -kv[1])[:5])
        print(f"FAIL: {total - frozen} NEW hard-coded colour(s) ({total} > {frozen}). Use a --t-* token from "
              f"NPDevManager/ui/tokens.css instead of a literal. Largest files: {worst}")
        return 1
    if total < frozen:
        print(f"FAIL: only {total} literals remain but frozenCount is {frozen} -- lower frozenCount to {total} "
              f"(python scripts/quality/check-manager-css-tokens.py --update) in the same commit, so the "
              f"ceiling ratchets down")
        return 1
    print("OK: no new hard-coded colours.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
