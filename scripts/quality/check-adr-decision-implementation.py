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
This does NOT parse ADR prose. An ADR opts a decision into checking with an explicit fenced block:

    ```decision-check
    id: ADR-0011-D4
    file: NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/ModelCompiler.java
    contains: tableNameSource
    ```

Each block is a claim: "the named file's text contains this substring." Same "text pattern, not
full AST" convention security-pattern-sweep.py and check-twin-pair-consistency.py already use, for
the same reason -- a substring check is auditable at a glance and cannot silently rot into parsing
the wrong thing.

A decision with no `decision-check` block is simply not checked -- this is the opt-in, not a gap.
Per S4_SPEC.md Phase B: start with ADR-0011's four decisions (D1-D4) only; do NOT retrofit this
across the other 10 ADRs in one sweep -- the value is in the pattern existing and covering the
newest decisions, not in prose-parsing a decade of older ones.

SCOPE
-----
Every docs/adr/ADR-*.md file is scanned for ```decision-check blocks (glob, not a hand-list --
a future ADR opts in for free by adding a block, no checker change needed). Today only ADR-0011
has any.

USAGE
-----
    python scripts/quality/check-adr-decision-implementation.py             # blocking: exit 1 on any unimplemented/malformed decision
    python scripts/quality/check-adr-decision-implementation.py --calibrate  # self-test (required before trusting this checker)
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ADR_GLOB = "docs/adr/ADR-*.md"

BLOCK_RE = re.compile(r"```decision-check\s*\n(.*?)```", re.DOTALL)
REQUIRED_KEYS = ("id", "file", "contains")


class MalformedBlockError(Exception):
    pass


def parse_block(raw: str) -> dict:
    """A block is a handful of `key: value` lines -- not YAML, deliberately (no escaping rules to
    get wrong, matching this repo's other check-*.py's "simplest thing that cannot lie" bias).
    `contains` takes everything after the first ': ' on its line, so a marker itself may contain a
    colon (an identifier never does in this codebase, but no reason to forbid it)."""
    fields: dict[str, str] = {}
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        if ":" not in line:
            raise MalformedBlockError(f"line is not 'key: value': {line!r}")
        key, _, value = line.partition(":")
        key = key.strip()
        value = value.strip()
        if key not in REQUIRED_KEYS:
            raise MalformedBlockError(f"unknown key {key!r} (expected one of {REQUIRED_KEYS})")
        fields[key] = value
    missing = [k for k in REQUIRED_KEYS if k not in fields]
    if missing:
        raise MalformedBlockError(f"block is missing required key(s): {missing}")
    return fields


def find_blocks(adr_text: str, adr_rel_path: str) -> tuple[list[dict], list[str]]:
    """Every ```decision-check block in one ADR file. Returns (parsed blocks, parse failures) --
    a malformed block is a finding, not a crash, so one bad block doesn't hide every other result."""
    blocks: list[dict] = []
    failures: list[str] = []
    for match in BLOCK_RE.finditer(adr_text):
        try:
            fields = parse_block(match.group(1))
        except MalformedBlockError as exc:
            failures.append(f"{adr_rel_path}: malformed decision-check block: {exc}")
            continue
        fields["_source"] = adr_rel_path
        blocks.append(fields)
    return blocks, failures


def collect_all_blocks(root: Path) -> tuple[list[dict], list[str]]:
    blocks: list[dict] = []
    failures: list[str] = []
    for adr_path in sorted(root.glob(ADR_GLOB)):
        rel = adr_path.relative_to(root).as_posix()
        text = adr_path.read_text(encoding="utf-8", errors="replace")
        found, block_failures = find_blocks(text, rel)
        blocks.extend(found)
        failures.extend(block_failures)
    return blocks, failures


def verify_block(block: dict, root: Path) -> str | None:
    """Returns a failure message, or None if the claim holds."""
    target = root / block["file"]
    if not target.is_file():
        return (f"{block['_source']}: {block['id']}: declared file does not exist: {block['file']} "
                f"(the decision-check block itself is stale)")
    text = target.read_text(encoding="utf-8", errors="replace")
    if block["contains"] not in text:
        return (f"{block['_source']}: {block['id']}: {block['file']} no longer contains "
                f"{block['contains']!r} -- this decision was accepted but its implementation is "
                f"missing or was reverted")
    return None


def run(root: Path) -> tuple[list[dict], list[str]]:
    """Returns (checked blocks, failure messages) -- duplicate ids and per-block verification
    failures are both collected before returning, same "report everything found, not just the
    first" convention as check-twin-pair-consistency.py."""
    blocks, failures = collect_all_blocks(root)

    seen_ids: dict[str, str] = {}
    for block in blocks:
        block_id = block["id"]
        if block_id in seen_ids:
            failures.append(
                f"{block['_source']}: duplicate decision-check id {block_id!r} "
                f"(also declared in {seen_ids[block_id]})"
            )
        else:
            seen_ids[block_id] = block["_source"]

    for block in blocks:
        failure = verify_block(block, root)
        if failure:
            failures.append(failure)

    return blocks, failures


def main_check(root: Path) -> int:
    print("ADR decision-implementation check (S4 Phase B):")
    blocks, failures = run(root)
    print(f"  found {len(blocks)} decision-check block(s) across {ADR_GLOB}")
    for block in blocks:
        status = "OK" if not any(block["id"] in f for f in failures) else "FAILED"
        print(f"  [{status}] {block['id']} -- {block['file']} contains {block['contains']!r}")

    if failures:
        print()
        print(f"FAIL: {len(failures)} decision-implementation issue(s):")
        for f in failures:
            print(f"  - {f}")
        return 1

    print()
    print(f"OK: all {len(blocks)} declared ADR decision(s) are implemented as claimed.")
    return 0


def calibrate() -> int:
    """Required before trusting this checker (same discipline every other check-*.py's --calibrate
    uses): proves, on synthetic tempfiles only (never the real ADR corpus), that
      1. a decision whose implementation is present passes,
      2. the SAME decision, with its implementation reverted, fails -- this is the literal
         "revert D4 and prove RED" proof the spec requires, run automatically on every gate
         invocation instead of once by hand.
      3. a malformed block (missing key) is reported, not silently skipped.
      4. a duplicate id across two ADR files is reported.
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
        (root / "docs" / "adr").mkdir(parents=True)
        target = root / "Target.java"

        adr_text = (
            "# ADR-9999 Fixture\n\n"
            "### D1 fixture decision\n\n"
            "```decision-check\n"
            "id: ADR-9999-D1\n"
            "file: Target.java\n"
            "contains: marker_string\n"
            "```\n"
        )
        (root / "docs" / "adr" / "ADR-9999-fixture.md").write_text(adr_text, encoding="utf-8")

        target.write_text("class Target { void marker_string() {} }\n", encoding="utf-8")
        _, implemented_failures = run(root)
        report("implementation present -- must NOT fire", implemented_failures, expect_fire=False)

        target.write_text("class Target { /* reverted */ }\n", encoding="utf-8")
        _, reverted_failures = run(root)
        report("implementation reverted (the D4 RED proof) -- MUST fire", reverted_failures, expect_fire=True)

        malformed_adr = (root / "docs" / "adr" / "ADR-9998-malformed.md")
        malformed_adr.write_text(
            "```decision-check\n"
            "id: ADR-9998-D1\n"
            "file: Target.java\n"
            "```\n",
            encoding="utf-8",
        )
        target.write_text("class Target { void marker_string() {} }\n", encoding="utf-8")
        _, malformed_failures = run(root)
        report("malformed block (missing 'contains') -- MUST fire", malformed_failures, expect_fire=True)
        malformed_adr.unlink()

        dup_adr = (root / "docs" / "adr" / "ADR-9997-dup.md")
        dup_adr.write_text(
            "```decision-check\n"
            "id: ADR-9999-D1\n"
            "file: Target.java\n"
            "contains: marker_string\n"
            "```\n",
            encoding="utf-8",
        )
        _, dup_failures = run(root)
        report("duplicate id across two ADR files -- MUST fire", dup_failures, expect_fire=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required -- this checker does not ship "
              "until it does.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly, including the RED-reverted case. Safe to run against the real ADR corpus.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(REPO_ROOT), help="repo root (default: this repo)")
    parser.add_argument("--calibrate", action="store_true", help="run the required self-test controls and exit")
    args = parser.parse_args(argv)

    if args.calibrate:
        return calibrate()

    return main_check(Path(args.root).resolve())


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
