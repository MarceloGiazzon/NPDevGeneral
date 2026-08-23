#!/usr/bin/env python3
"""Move 14 Phase E item E1 (U2): the mirror-rule gate.

"A rule applied in one place, not mirrored to its twin" has hit three confirmed instances
(REG-89, REG-104, REG-112) -- the same threshold that earned X0 a permanent registry+gate
(docs/X0_SILENT_EXPRESSION_REGISTER.md). This is that gate for the twin-pair-divergence family.

Reads scripts/quality/twin-pair-registry.json (a human-curated list of "these locations must move
together" rules -- this script does not invent new pairs, it only checks the ones already named)
and fails when any rule's locations have diverged:

  - kind "sibling-group-in-list": a declared group of items that must ALL be present in, or ALL be
    absent from, a Groovy list literal (e.g. NPDevRuntimeHost/build.gradle's
    modelSpecificGeneratedAppTests) -- a partial match is exactly REG-112's shape.
  - kind "field-in-all-files": a declared field name that must appear (case-insensitive substring,
    matching this repo's other check-*.py's "text pattern, not full AST" convention -- see
    security-pattern-sweep.py's own docstring for the same reasoning) in EVERY listed file, or the
    field silently has no live path through one of them (REG-104's/REG-108's shape).

Usage: python scripts/quality/check-twin-pair-consistency.py
Exit 0 = every registered rule's locations agree. Exit 1 = at least one rule diverged.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def check_sibling_group_in_list(rule: dict, root: Path) -> list[str]:
    failures: list[str] = []
    list_file = root / rule["listFile"]
    text = list_file.read_text(encoding="utf-8")
    marker = rule["listMarker"]
    marker_index = text.find(marker)
    if marker_index == -1:
        return [f"{rule['id']}: marker '{marker}' not found at all in {rule['listFile']}"]
    open_bracket = text.find("[", marker_index)
    close_bracket = text.find("]", open_bracket)
    if open_bracket == -1 or close_bracket == -1:
        return [f"{rule['id']}: could not find a [...] list body after '{marker}' in {rule['listFile']}"]
    body = text[open_bracket:close_bracket]
    # Match within a single line only -- list entries are always one-line string literals, but an
    # unbounded [^'] class pairs a stray English apostrophe in a // comment (e.g. "manifest's") with
    # an unrelated quote elsewhere in the body, swallowing every real entry between them into one
    # bogus multi-line match. Confirmed live: this silently dropped PublicationRollbackE2EIT.java
    # (among others) from modelSpecificGeneratedAppTests' parsed contents in NPDevRuntimeHost/build.gradle.
    present = set(re.findall(r"'([^'\n]+)'", body)) | set(re.findall(r'"([^"\n]+)"', body))
    for group in rule["groups"]:
        members_present = [member for member in group if member in present]
        members_absent = [member for member in group if member not in present]
        if members_present and members_absent:
            failures.append(
                f"{rule['id']}: {rule['listMarker']} in {rule['listFile']} has "
                f"{members_present} but is MISSING its declared sibling(s) {members_absent} "
                f"-- either all of {group} belong in this list or none do"
            )
    return failures


def check_field_in_all_files(rule: dict, root: Path) -> list[str]:
    failures: list[str] = []
    case_sensitive = rule.get("caseSensitive", True)
    for field in rule["trackedFields"]:
        pattern = re.compile(re.escape(field), 0 if case_sensitive else re.IGNORECASE)
        present_in: list[str] = []
        absent_from: list[str] = []
        for rel_path in rule["files"]:
            full_path = root / rel_path
            if not full_path.exists():
                failures.append(f"{rule['id']}: declared file {rel_path} does not exist (registry is stale)")
                continue
            text = full_path.read_text(encoding="utf-8")
            (present_in if pattern.search(text) else absent_from).append(rel_path)
        if present_in and absent_from:
            failures.append(
                f"{rule['id']}: field '{field}' appears in {present_in} but is MISSING from "
                f"{absent_from} -- a field threaded through some of these but not all of them can "
                f"parse/compile clean and still never reach the generator/runtime"
            )
    return failures


CHECKERS = {
    "sibling-group-in-list": check_sibling_group_in_list,
    "field-in-all-files": check_field_in_all_files,
}


def run_self_test() -> bool:
    """Same convention security-pattern-sweep.py's own self-test uses: prove each checker function
    actually separates a known-divergent fixture from a known-consistent one, using synthetic
    tempfiles -- not the real repo -- so this never depends on the registry's current live state."""
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        (root / "a.gradle").write_text(
            "def modelSpecificGeneratedAppTests = [\n  'x/Foo.java',\n]\n", encoding="utf-8")
        diverged = check_sibling_group_in_list(
            {"id": "self-test-diverge", "listFile": "a.gradle", "listMarker": "modelSpecificGeneratedAppTests",
             "groups": [["x/Foo.java", "x/Bar.java"]]}, root)
        consistent = check_sibling_group_in_list(
            {"id": "self-test-consistent", "listFile": "a.gradle", "listMarker": "modelSpecificGeneratedAppTests",
             "groups": [["x/Foo.java"]]}, root)
        if not diverged or consistent:
            print("SELF-TEST FAILED: sibling-group-in-list did not separate diverged from consistent")
            return False

        (root / "f1.txt").write_text("this file mentions ROLES clearly", encoding="utf-8")
        (root / "f2.txt").write_text("this file mentions nothing relevant", encoding="utf-8")
        diverged2 = check_field_in_all_files(
            {"id": "self-test-diverge-2", "trackedFields": ["roles"], "caseSensitive": False,
             "files": ["f1.txt", "f2.txt"]}, root)
        consistent2 = check_field_in_all_files(
            {"id": "self-test-consistent-2", "trackedFields": ["roles"], "caseSensitive": False,
             "files": ["f1.txt"]}, root)
        if not diverged2 or consistent2:
            print("SELF-TEST FAILED: field-in-all-files did not separate diverged from consistent")
            return False

    print("Self-test: both checker shapes correctly separate a diverged fixture from a consistent one -- OK")
    return True


def main() -> int:
    if not run_self_test():
        return 1

    root = repo_root()
    registry_path = root / "scripts" / "quality" / "twin-pair-registry.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))

    print("Twin-pair consistency (Move 14 Phase E item E1 / U2):")
    all_failures: list[str] = []
    for rule in registry["rules"]:
        checker = CHECKERS.get(rule["kind"])
        if checker is None:
            all_failures.append(f"{rule['id']}: unknown rule kind '{rule['kind']}'")
            continue
        rule_failures = checker(rule, root)
        status = "OK" if not rule_failures else "DIVERGED"
        print(f"  [{status}] {rule['id']} (cites {rule.get('citedBy', '?')})")
        all_failures.extend(rule_failures)

    if all_failures:
        print()
        print(f"FAILED: {len(all_failures)} twin-pair divergence(s):")
        for failure in all_failures:
            print(f"  - {failure}")
        return 1

    print()
    print(f"OK: all {len(registry['rules'])} registered twin-pair rule(s) are consistent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
