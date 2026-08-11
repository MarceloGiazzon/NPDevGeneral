#!/usr/bin/env python3
"""ADR decision-implementation check (S4 Phase B): does an ADR's ACCEPTED decision actually have
live code behind it?

WHY THIS EXISTS
----------------
ADR-0011 D4 ("no physical table prefixing") was ratified by the owner in S2's own gate, recorded in
the ADR -- and `ModelCompiler` did not do it. Nothing caught this until the S3 codemod was run
against real content and produced a wrong table name. This is the "declared-but-unwired" defect
family (REG-70, `generatedAction`, `createIfMissing`, `ReleaseGateValidator`, `field.sensitive`)
showing up in a DECISION RECORD rather than a feature declaration -- six controls exist
(check-twin-pair-consistency.py, check-x0-evaluator-coverage.py, check-dsl-coverage.py, ...) and
none of them asks "is an accepted ADR decision actually implemented?" This is that check.

THE SHAPE -- deliberately narrow, opt-in, machine-checkable
-------------------------------------------------------------
This does NOT parse ADR prose. `scripts/policy/adr-decision-checks.json` declares each opted-in
decision explicitly:

    {"id": "ADR-0011-D4", "adr": "docs/adr/ADR-0011-bounded-contexts.md",
     "file": "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/ModelCompiler.java",
     "contains": "tableNameSource", "why": "..."}

Each entry is a claim: "the named file's text contains this substring." Same "text pattern, not
full AST" convention security-pattern-sweep.py and check-twin-pair-consistency.py already use, for
the same reason -- a substring check is auditable at a glance and cannot silently rot into parsing
the wrong thing.

md-zero-2026-08-11 PLAN.md Phase 7: this used to glob `docs/adr/ADR-*.md` and parse ```decision-check
fenced blocks out of the prose -- a real script reading .md content as data, discovered only while
building the zero-markdown-reads gate (the plan's own 37-coupling audit missed it). The claim moved
here; the ADR stays narrative-only, with a one-line pointer back to this file at each decision.

A decision with no entry here is simply not checked -- this is the opt-in, not a gap. Per
S4_SPEC.md Phase B: start with ADR-0011's four decisions (D1-D4) only; do NOT retrofit this across
the other ADRs in one sweep -- the value is in the pattern existing and covering the newest
decisions, not in prose-parsing a decade of older ones.

USAGE
-----
    python scripts/quality/check-adr-decision-implementation.py             # blocking: exit 1 on any unimplemented/malformed decision
    python scripts/quality/check-adr-decision-implementation.py --calibrate  # self-test (required before trusting this checker)
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / "scripts" / "policy" / "adr-decision-checks.json"

REQUIRED_KEYS = ("id", "adr", "file", "contains", "why")


class MalformedEntryError(Exception):
    pass


def load_decisions(policy_path: Path) -> tuple[list[dict], list[str]]:
    """Returns (valid entries, malformed-entry failure messages) -- one bad entry does not hide
    every other result, same convention as the .md-parsing version this replaced."""
    data = json.loads(policy_path.read_text(encoding="utf-8"))
    entries: list[dict] = []
    failures: list[str] = []
    for i, raw in enumerate(data.get("decisions", [])):
        try:
            missing = [k for k in REQUIRED_KEYS if not str(raw.get(k, "")).strip()]
            if missing:
                raise MalformedEntryError(f"missing/empty required key(s): {missing}")
            entries.append(raw)
        except MalformedEntryError as exc:
            failures.append(f"{policy_path.name}[{i}] ({raw.get('id', '?')!r}): {exc}")
    return entries, failures


def verify_entry(entry: dict, root: Path) -> str | None:
    """Returns a failure message, or None if the claim holds."""
    target = root / entry["file"]
    if not target.is_file():
        return (f"{entry['id']}: declared file does not exist: {entry['file']} "
                f"(the decision-check entry itself is stale)")
    text = target.read_text(encoding="utf-8", errors="replace")
    if entry["contains"] not in text:
        return (f"{entry['id']}: {entry['file']} no longer contains "
                f"{entry['contains']!r} -- this decision was accepted but its implementation is "
                f"missing or was reverted")
    return None


def run(root: Path, policy_path: Path) -> tuple[list[dict], list[str]]:
    """Returns (checked entries, failure messages) -- duplicate ids and per-entry verification
    failures are both collected before returning, same "report everything found, not just the
    first" convention as check-twin-pair-consistency.py."""
    entries, failures = load_decisions(policy_path)

    seen_ids: dict[str, int] = {}
    for i, entry in enumerate(entries):
        entry_id = entry["id"]
        if entry_id in seen_ids:
            failures.append(f"duplicate decision-check id {entry_id!r} (entries {seen_ids[entry_id]} and {i})")
        else:
            seen_ids[entry_id] = i

    for entry in entries:
        failure = verify_entry(entry, root)
        if failure:
            failures.append(failure)

    return entries, failures


def main_check(root: Path, policy_path: Path) -> int:
    print("ADR decision-implementation check (S4 Phase B):")
    entries, failures = run(root, policy_path)
    print(f"  found {len(entries)} decision-check entr{'y' if len(entries) == 1 else 'ies'} in {policy_path.relative_to(root) if policy_path.is_relative_to(root) else policy_path}")
    for entry in entries:
        status = "OK" if not any(entry["id"] in f for f in failures) else "FAILED"
        print(f"  [{status}] {entry['id']} -- {entry['file']} contains {entry['contains']!r}")

    if failures:
        print()
        print(f"FAIL: {len(failures)} decision-implementation issue(s):")
        for f in failures:
            print(f"  - {f}")
        return 1

    print()
    print(f"OK: all {len(entries)} declared ADR decision(s) are implemented as claimed.")
    return 0


def calibrate() -> int:
    """Required before trusting this checker (same discipline every other check-*.py's --calibrate
    uses): proves, on synthetic tempfiles only (never the real ADR corpus), that
      1. a decision whose implementation is present passes,
      2. the SAME decision, with its implementation reverted, fails -- this is the literal
         "revert D4 and prove RED" proof the spec requires, run automatically on every gate
         invocation instead of once by hand.
      3. a malformed entry (missing/empty required key) is reported, not silently skipped.
      4. a duplicate id across two entries is reported.
    """
    import tempfile

    ok = True

    def report(label: str, failures: list[str], expect_fire: bool) -> None:
        nonlocal ok
        fired = bool(failures)
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for f in failures:
            print(f"           {f}")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        target = root / "Target.java"
        policy_path = root / "adr-decision-checks.json"

        def write_policy(decisions: list[dict]) -> None:
            policy_path.write_text(json.dumps({"decisions": decisions}), encoding="utf-8")

        base_entry = {
            "id": "ADR-9999-D1", "adr": "docs/adr/ADR-9999-fixture.md",
            "file": "Target.java", "contains": "marker_string", "why": "fixture",
        }

        write_policy([base_entry])
        target.write_text("class Target { void marker_string() {} }\n", encoding="utf-8")
        _, implemented_failures = run(root, policy_path)
        report("implementation present -- must NOT fire", implemented_failures, expect_fire=False)

        target.write_text("class Target { /* reverted */ }\n", encoding="utf-8")
        _, reverted_failures = run(root, policy_path)
        report("implementation reverted (the D4 RED proof) -- MUST fire", reverted_failures, expect_fire=True)

        write_policy([{**base_entry, "why": "  "}])
        target.write_text("class Target { void marker_string() {} }\n", encoding="utf-8")
        _, malformed_failures = run(root, policy_path)
        report("malformed entry (empty 'why') -- MUST fire", malformed_failures, expect_fire=True)

        write_policy([base_entry, dict(base_entry)])
        _, dup_failures = run(root, policy_path)
        report("duplicate id across two entries -- MUST fire", dup_failures, expect_fire=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required -- this checker does not ship "
              "until it does.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly, including the RED-reverted case. Safe to run against the real ADR corpus.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(REPO_ROOT), help="repo root (default: this repo)")
    parser.add_argument("--policy", default=str(POLICY_PATH), help="path to adr-decision-checks.json")
    parser.add_argument("--calibrate", action="store_true", help="run the required self-test controls and exit")
    args = parser.parse_args(argv)

    if args.calibrate:
        return calibrate()

    return main_check(Path(args.root).resolve(), Path(args.policy).resolve())


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
