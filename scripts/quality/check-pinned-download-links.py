#!/usr/bin/env python3
"""A download link in an install guide must not name a version.

WHY THIS EXISTS
---------------
Found twice on 2026-08-10, in the two documents a new person reads FIRST:

    README.md:17        releases/download/beta1.7/NPDev.Manager_0.1.0_x64-setup.exe
    docs/MANAGER.md:59  the same link, in the numbered install steps

`beta1.7` was five releases old. Both links still resolved -- GitHub happily serves an old asset --
so a newcomer following the front page installed a Manager from 2026-08-06 and hit defects that had
already been fixed, with nothing anywhere telling them they were not on the current build. Nobody
noticed for five releases because the link WORKS; it is only the wrong answer.

This is not a staleness problem that better discipline fixes. A version-pinned download link goes
stale BY CONSTRUCTION on the very next release, and the release process has no reason to touch
prose. The durable answer is to never pin: `/releases/latest` is always right and never needs
editing.

THE RULE
--------
Fail when a user-facing document contains a GitHub release URL that names a specific tag:

    .../releases/download/<tag>/<asset>     <- pinned, always goes stale
    .../releases/tag/<tag>                  <- same

`/releases/latest` and `/releases` are fine, and are the fix.

SCOPE, and why some files are excused
-------------------------------------
Scanned: README.md and docs/*.md -- what a newcomer actually reads.

Excused, because naming a specific release is their JOB, not a mistake:
  docs/RELEASE_PROCESS.md   describes publishing a given tag
  BREAKING.md               a historical record; entries are ABOUT particular versions
  docs/OPEN_ITEMS.md        GENERATED from ledger/items/*.yml -- a defect register has to be able to
                            quote the defect. This check fired on it within minutes of shipping,
                            against PORT-4's own row, which cites the very URL it exists to forbid.
  docs/archive/**           frozen by definition; rewriting history to satisfy a linter is worse

PROSE ABOUT A DEFECT IS NOT THE DEFECT. That sentence had to be learned three separate times on
2026-08-10 -- by check-out-of-tree-generation.ps1 (twice: a gradle.properties comment and a
Create-Environment.ps1 comment, both explaining why a path must not be hardcoded), and then again
here. Any checker that greps for a bad string will eventually flag the document explaining why the
string is bad. Decide where the record lives, excuse it explicitly, and say why -- do not make the
record lie to keep a linter quiet.

CALIBRATE BEFORE TRUSTING IT
----------------------------
    python scripts/quality/check-pinned-download-links.py --calibrate

USAGE
-----
    python scripts/quality/check-pinned-download-links.py            # exit 1 on any finding
    python scripts/quality/check-pinned-download-links.py --calibrate  # self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# A release URL that names a tag. `latest` is explicitly NOT a pin -- it is the remedy.
PINNED = re.compile(
    r"releases/(?:download|tag)/(?!latest\b)([A-Za-z0-9._-]+)",
    re.IGNORECASE,
)

EXCUSED_NAMES = {"RELEASE_PROCESS.md", "BREAKING.md", "OPEN_ITEMS.md"}
EXCUSED_DIR_PARTS = {"archive"}


def scanned_files(root: Path) -> list[Path]:
    files = []
    readme = root / "README.md"
    if readme.is_file():
        files.append(readme)
    docs = root / "docs"
    if docs.is_dir():
        for p in sorted(docs.rglob("*.md")):
            if p.name in EXCUSED_NAMES:
                continue
            if EXCUSED_DIR_PARTS & set(p.parts):
                continue
            files.append(p)
    return files


def findings(root: Path) -> list[tuple[str, int, str, str]]:
    out = []
    for path in scanned_files(root):
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for idx, line in enumerate(lines):
            for m in PINNED.finditer(line):
                out.append((path.relative_to(root).as_posix(), idx + 1, m.group(1), line.strip()[:110]))
    return out


def calibrate() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / "docs").mkdir()

        (root / "README.md").write_text(
            "[Download](https://github.com/x/y/releases/download/beta1.7/app.exe)\n", encoding="utf-8")
        (root / "docs" / "GOOD.md").write_text(
            "[Download](https://github.com/x/y/releases/latest)\n", encoding="utf-8")
        (root / "docs" / "RELEASE_PROCESS.md").write_text(
            "publish to https://github.com/x/y/releases/tag/beta1.9\n", encoding="utf-8")
        # The register that RECORDS this defect must be able to quote it. Real regression: this
        # check failed on docs/OPEN_ITEMS.md minutes after shipping, over PORT-4's own row.
        (root / "docs" / "OPEN_ITEMS.md").write_text(
            "PORT-4 cites releases/download/beta1.7/app.exe as the defect\n", encoding="utf-8")

        got = {f[0] for f in findings(root)}
        for label, cond in (
            ("a pinned download link fires", "README.md" in got),
            ("/releases/latest stays quiet", "docs/GOOD.md" not in got),
            ("RELEASE_PROCESS.md is excused", "docs/RELEASE_PROCESS.md" not in got),
            ("the generated defect register may quote the defect", "docs/OPEN_ITEMS.md" not in got),
        ):
            print(f"  {'PASS' if cond else 'FAIL'}  {label}")
            ok &= cond
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args()

    if args.calibrate:
        print("Calibrating check-pinned-download-links:")
        return calibrate()

    found = findings(REPO_ROOT)
    n = len(scanned_files(REPO_ROOT))
    print(f"Pinned-download-link check: {n} user-facing document(s) scanned.")

    if not found:
        print("OK: no install link names a specific release.")
        return 0

    print(f"\nFAIL: {len(found)} version-pinned release link(s).\n")
    for rel, line, tag, text in found:
        print(f"  {rel}:{line}  pins '{tag}'")
        print(f"    {text}")
    print("\n  A pinned link still RESOLVES, so it fails silently -- a newcomer installs an old build")
    print("  and nothing tells them. Use /releases/latest, which never needs editing.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
