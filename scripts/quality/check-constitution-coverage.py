#!/usr/bin/env python3
"""Path A realignment, Phase 0 (P0.1): the Constitution as structured data, not prose.

scripts/policy/constitution.json lists NPDev's constitutional laws (Path A: the model is
the sole durable truth; runtime artifacts are disposable projections of it). Each law
declares an `enforcement` level -- enforced | declared | aspirational -- and this checker's
whole job is to make that claim honest: a law CAN be aspirational, but it cannot claim
`enforced` for free. `enforced` requires a named `checker` (a repo-relative file, optionally
with a pattern that must appear in it) that actually exists -- so a law flips to `enforced`
only when something machine-checkable backs it, not when someone edits a status string.

Also validates: `laws` is a non-empty array; every entry has id/law/enforcement/checker;
ids are unique; enforcement is one of the three allowed values; a non-null checker's path
must exist (an aspirational or declared law may name a checker in progress, but a dangling
path is still a lie about the tree).

Usage: python scripts/quality/check-constitution-coverage.py [--calibrate]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CONSTITUTION_PATH = REPO_ROOT / "scripts" / "policy" / "constitution.json"

VALID_ENFORCEMENT = {"enforced", "declared", "aspirational"}
REQUIRED_LAW_FIELDS = ("id", "law", "enforcement", "checker")


def load_laws(path: Path) -> tuple[list | None, list[str]]:
    if not path.exists():
        return None, [f"{path} not found"]
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, [f"{path} is not valid JSON: {exc}"]
    laws = doc.get("laws")
    if not isinstance(laws, list) or not laws:
        return None, [f"{path} has no non-empty 'laws' array"]
    return laws, []


def validate_checker(root: Path, law_id: str, checker: dict) -> list[str]:
    errors = []
    if not isinstance(checker, dict) or "path" not in checker:
        return [f"law '{law_id}': checker must be an object with a 'path' key, got {checker!r}"]
    rel = checker["path"]
    p = root / rel
    if not p.exists():
        errors.append(f"law '{law_id}': checker path does not exist: {rel}")
        return errors
    pattern = checker.get("pattern")
    if pattern:
        regex = re.compile(pattern)
        matched = False
        if p.is_file():
            matched = bool(regex.search(p.read_text(encoding="utf-8", errors="replace")))
        else:
            for f in p.rglob("*"):
                if f.is_file() and regex.search(f.read_text(encoding="utf-8", errors="replace")):
                    matched = True
                    break
        if not matched:
            errors.append(
                f"law '{law_id}': checker pattern /{pattern}/ not found under {rel}"
            )
    return errors


def validate(root: Path, laws: list) -> list[str]:
    errors: list[str] = []
    seen_ids: set[str] = set()
    for entry in laws:
        if not isinstance(entry, dict):
            errors.append(f"law entry is not an object: {entry!r}")
            continue
        missing = [f for f in REQUIRED_LAW_FIELDS if f not in entry]
        if missing:
            errors.append(f"law entry missing field(s) {missing}: {entry!r}")
            continue
        law_id = entry["id"]
        if law_id in seen_ids:
            errors.append(f"duplicate law id: {law_id}")
        seen_ids.add(law_id)

        enforcement = entry["enforcement"]
        if enforcement not in VALID_ENFORCEMENT:
            errors.append(
                f"law '{law_id}': enforcement {enforcement!r} not one of {sorted(VALID_ENFORCEMENT)}"
            )

        checker = entry["checker"]
        if enforcement == "enforced":
            if not checker:
                errors.append(
                    f"law '{law_id}': claims enforcement=enforced with no checker -- "
                    "either name a real checker or demote to 'declared'/'aspirational'"
                )
            else:
                errors.extend(validate_checker(root, law_id, checker))
        elif checker:
            # A declared/aspirational law may still name a checker it's building toward;
            # that checker must at least exist, so the field can't rot into a dangling claim.
            errors.extend(validate_checker(root, law_id, checker))
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--calibrate", action="store_true", help="prove this checker can fail")
    args = ap.parse_args()

    if args.calibrate:
        bad_laws = [
            {"id": "calibration-law", "law": "x", "enforcement": "enforced", "checker": None}
        ]
        errors = validate(REPO_ROOT, bad_laws)
        if not errors:
            print("CALIBRATION FAILED: an enforced law with no checker was not flagged")
            return 1
        dangling = validate(
            REPO_ROOT,
            [
                {
                    "id": "calibration-law-2",
                    "law": "x",
                    "enforcement": "enforced",
                    "checker": {"path": "scripts/policy/__does_not_exist__.json"},
                }
            ],
        )
        if not dangling:
            print("CALIBRATION FAILED: an enforced law with a dangling checker path was not flagged")
            return 1
        print("Calibration OK: both a missing checker and a dangling checker path are caught.")
        return 0

    laws, load_errors = load_laws(CONSTITUTION_PATH)
    if load_errors:
        for e in load_errors:
            print(f"FAIL: {e}")
        return 1

    errors = validate(REPO_ROOT, laws)
    enforced = sum(1 for law in laws if law.get("enforcement") == "enforced")
    declared = sum(1 for law in laws if law.get("enforcement") == "declared")
    aspirational = sum(1 for law in laws if law.get("enforcement") == "aspirational")
    print(
        f"{len(laws)} law(s): enforced={enforced} declared={declared} aspirational={aspirational}"
    )
    if errors:
        print(f"FAIL: {len(errors)} problem(s):")
        for e in errors:
            print(f"  - {e}")
        return 1
    print("PASS: every enforced law names a real, existing checker.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
