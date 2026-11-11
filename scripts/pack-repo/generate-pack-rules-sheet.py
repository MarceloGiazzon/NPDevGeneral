#!/usr/bin/env python3
"""DOM-0: one reviewable page of business rules, generated from a pack.

A domain pack (inventory, sales, purchasing, ...) is written by an engineer but has to be
signed off by someone who knows the business. This script renders `NPDevContract/packs/<id>/pack.json`
as a single self-contained HTML page -- no JSON, no expression syntax a reviewer has to decode:

  * what each concept holds (fields, which are required / unique, allowed values, bounds, defaults);
  * lifecycles as "from -> to via action" tables;
  * invariants (the rules that refuse a bad record), with the expression beside a plain restatement
    of the names it uses;
  * who may read / write each concept (logical role tokens shown as role names);
  * the derived queries (what is filtered, grouped, summed);
  * flows and their human tasks;
  * "Decisions and gaps": the pack's own `metadata.reviewNotes` -- what the pack deliberately does NOT
    enforce. A rules sheet that hid its gaps would defeat the sign-off.

Everything is read from the pack, so the sheet cannot drift from it. Output defaults to
`<Build root>/reports/pack-rules-sheets/<pack>.html` (never inside the repo).

    python scripts/pack-repo/generate-pack-rules-sheet.py inventory
    python scripts/pack-repo/generate-pack-rules-sheet.py path/to/pack.json --out some/dir/sheet.html
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKS_DIR = REPO_ROOT / "NPDevContract" / "packs"
DEFAULT_OUT_DIR = REPO_ROOT.parent / "Build" / "reports" / "pack-rules-sheets"

sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
from npdev_pack_sheet import render  # noqa: E402  (P4.4: the rendering core lives in the CLI, shared with the relay preview)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("pack", help="A built-in pack id (NPDevContract/packs/<id>) or a path to a pack.json.")
    ap.add_argument("--out", help="Output .html file (default: <Build root>/reports/pack-rules-sheets/<pack>.html).")
    args = ap.parse_args()
    path = Path(args.pack)
    if not path.is_file():
        path = PACKS_DIR / args.pack / "pack.json"
    if not path.is_file():
        print(f"generate-pack-rules-sheet: no pack at {args.pack} or {path}", file=sys.stderr)
        return 2
    pack = json.loads(path.read_text(encoding="utf-8-sig"))
    out = Path(args.out) if args.out else DEFAULT_OUT_DIR / f"{pack['pack']}.html"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(render(pack).encode("utf-8"))
    print(f"generate-pack-rules-sheet: wrote {out.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
