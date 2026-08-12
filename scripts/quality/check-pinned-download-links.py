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
scripts/policy/release-download-links.json declares `canonicalReleaseUrl` (must itself never be a
pin) and `knownLinkSites` -- the specific (file, anchor) pairs where a download link has actually
been found before. For each site, this script reads ONLY that file, finds the anchor text, and
fails if the markdown link immediately following it is not exactly `canonicalReleaseUrl`.

md-zero-2026-08-11 PLAN.md Phase 3 narrowed this from a 94-file sweep (README.md + every
docs/**/*.md, regex-scanned for ANY `releases/download/<tag>` or `releases/tag/<tag>` pattern) to
this targeted form -- see the policy JSON's own `why` for the coverage trade-off this accepts: a
brand new pinned link typed into some OTHER, undeclared doc is no longer caught automatically. Add
a `knownLinkSites` entry (with its own `why`) the day a new download link is added anywhere, the
same discipline `check-allowlist-citations.py` already expects of every other allowlist in this
repo.

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
import json
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / "scripts" / "policy" / "release-download-links.json"

# A release URL that names a tag. `latest` is explicitly NOT a pin -- it is the remedy.
PINNED = re.compile(
    r"releases/(?:download|tag)/(?!latest\b)([A-Za-z0-9._-]+)",
    re.IGNORECASE,
)

# The markdown link immediately following the anchor text: `anchor](URL)`.
LINK_AFTER_ANCHOR = re.compile(r"\]\(([^)]+)\)")


def findings(root: Path, policy: dict) -> list[str]:
    out: list[str] = []
    canonical = policy["canonicalReleaseUrl"]
    if PINNED.search(canonical):
        out.append(f"scripts/policy/release-download-links.json: canonicalReleaseUrl itself is "
                    f"pinned: {canonical}")

    for site in policy.get("knownLinkSites", []):
        rel_path = site["file"]
        anchor = site["anchor"]
        full_path = root / rel_path
        if not full_path.is_file():
            out.append(f"{rel_path}: declared knownLinkSites file does not exist on disk")
            continue
        text = full_path.read_text(encoding="utf-8", errors="replace")
        anchor_at = text.find(anchor)
        if anchor_at < 0:
            out.append(f"{rel_path}: anchor text {anchor!r} not found -- the surrounding prose "
                        f"moved or was reworded; update knownLinkSites")
            continue
        after = text[anchor_at + len(anchor):anchor_at + len(anchor) + 400]
        m = LINK_AFTER_ANCHOR.search(after)
        if not m:
            out.append(f"{rel_path}: no markdown link found immediately after anchor {anchor!r}")
            continue
        url = m.group(1)
        if url != canonical:
            out.append(f"{rel_path}: link after {anchor!r} is {url!r}, expected the canonical "
                        f"{canonical!r}")
    return out


def calibrate() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)

        base_policy = {
            "canonicalReleaseUrl": "https://github.com/x/y/releases/latest",
            "knownLinkSites": [{"file": "README.md", "anchor": "Download it", "why": "test"}],
        }

        def report(label: str, readme_text: str, policy: dict, expect_fire: bool) -> None:
            nonlocal ok
            (root / "README.md").write_text(readme_text, encoding="utf-8")
            found = findings(root, policy)
            fired = bool(found)
            passed = fired == expect_fire
            ok = ok and passed
            print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
            for f in found[:2]:
                print(f"           {f}")

        report(
            "a pinned download link at a known site fires",
            "[Download it](https://github.com/x/y/releases/download/beta1.7/app.exe)\n",
            base_policy, expect_fire=True,
        )
        report(
            "the canonical /releases/latest link stays quiet",
            "[Download it](https://github.com/x/y/releases/latest)\n",
            base_policy, expect_fire=False,
        )
        report(
            "a missing anchor (prose reworded) fires",
            "Nothing here matches.\n",
            base_policy, expect_fire=True,
        )
        pinned_canonical = dict(base_policy, canonicalReleaseUrl="https://github.com/x/y/releases/download/beta1.9/app.exe")
        report(
            "a pinned canonicalReleaseUrl itself fires, independent of the doc",
            "[Download it](https://github.com/x/y/releases/latest)\n",
            pinned_canonical, expect_fire=True,
        )
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args()

    if args.calibrate:
        print("Calibrating check-pinned-download-links:")
        return calibrate()

    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    found = findings(REPO_ROOT, policy)
    n = len(policy.get("knownLinkSites", []))
    print(f"Pinned-download-link check: {n} known link site(s) verified against "
          f"scripts/policy/release-download-links.json.")

    if not found:
        print("OK: no known link site names a specific release, and the canonical URL is not pinned.")
        return 0

    print(f"\nFAIL: {len(found)} finding(s).\n")
    for f in found:
        print(f"  {f}")
    print("\n  A pinned link still RESOLVES, so it fails silently -- a newcomer installs an old build")
    print("  and nothing tells them. Use /releases/latest, which never needs editing.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
