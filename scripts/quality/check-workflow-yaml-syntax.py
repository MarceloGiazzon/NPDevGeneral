#!/usr/bin/env python3
"""Found by accident verifying docs/FINAL_OPEN_ITEMS_PLAN.md F1/F2 on a live PR, 2026-07-29: an
unquoted colon inside a step `name:` (`findings: persistence, idempotency`) made
npdev-pr-gate.yml invalid YAML -- GitHub scheduled ZERO jobs for it, silently, on every push and PR
since the commit that introduced it. `git log` shows failing runs going back hours before this was
noticed; nothing in this repo's own thorough gate suite checks that a workflow file still PARSES.

Same "nothing looked" shape as the corpus-parse gate (REG-63) and the markdown-link gate (N5): a
thing that used to work, silently stopped, and nothing looked. This is the cheapest possible version
of that fix -- yaml.safe_load on every `.github/workflows/*.yml`, nothing more. It does not validate
GitHub Actions schema (job/step shape), only that the file is syntactically valid YAML at all, which
is the failure mode that actually happened.

    python check-workflow-yaml-syntax.py
    python check-workflow-yaml-syntax.py --calibrate
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"


def check(root: Path) -> list[str]:
    errors = []
    for path in sorted(root.glob("*.yml")) + sorted(root.glob("*.yaml")):
        try:
            yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            errors.append(f"{path.relative_to(REPO_ROOT)}: {exc}")
    return errors


def calibrate() -> int:
    """Must FAIL on the exact real bug shape (an unquoted colon inside a step name), PASS on the
    quoted fix -- same required-controls discipline as this repo's other --calibrate scripts."""
    ok = True
    with tempfile.TemporaryDirectory(prefix="npdev-workflow-yaml-calibrate-") as tmp:
        tmp_dir = Path(tmp)
        good = tmp_dir / "good.yml"
        good.write_text(
            "jobs:\n  x:\n    steps:\n"
            '      - name: "Postgres adapter tests (findings: persistence, idempotency)"\n'
            "        run: echo ok\n",
            encoding="utf-8",
        )
        bad = tmp_dir / "bad.yml"
        bad.write_text(
            "jobs:\n  x:\n    steps:\n"
            "      - name: Postgres adapter tests (findings: persistence, idempotency)\n"
            "        run: echo ok\n",
            encoding="utf-8",
        )

        def report(label: str, path: Path, expect_fail: bool) -> None:
            nonlocal ok
            try:
                yaml.safe_load(path.read_text(encoding="utf-8"))
                fired = False
            except yaml.YAMLError:
                fired = True
            passed = fired == expect_fail
            ok = ok and passed
            print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

        print("Calibration -- must catch the real bug shape (unquoted colon in a step name):")
        report("quoted step name (the real fix)", good, expect_fail=False)
        report("unquoted colon in a step name (the real historical bug)", bad, expect_fail=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=str(WORKFLOWS_DIR))
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    root = Path(args.root)
    if not root.exists():
        print(f"Workflow YAML syntax check: {root} not present on this checkout -- 0 file(s) checked (PASS).")
        return 0

    errors = check(root)
    files = sorted(root.glob("*.yml")) + sorted(root.glob("*.yaml"))
    print(f"Workflow YAML syntax check: {len(files)} file(s) checked under {root}, {len(errors)} error(s).")
    if errors:
        print("\nFAIL: the following workflow file(s) are not valid YAML:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    print("OK: every workflow file is valid YAML.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
