#!/usr/bin/env python3
"""QUAL-2: a `Files.list|walk|find|lines|newDirectoryStream` whose Stream is never closed.

WHY A CHECK AND NOT JUST THE TEN FIXES
--------------------------------------
There were ten. There will be an eleventh, because the leaking form reads exactly like the correct
one and the symptom does not appear where the mistake is:

    Files.list(dir).filter(...).forEach(...)      // leaks a directory handle
    try (var s = Files.list(dir)) { s.filter... } // does not

All five methods return a Stream holding an OS handle, and all five javadocs say to close it. On
POSIX a leak costs a file descriptor and nothing visible. On Windows the directory becomes
DELETE-PENDING, so its PARENT cannot be removed -- and the error names the parent, not the leak.

That is exactly how S1 presented: `ConversionHookEmitterTest` failed in JUnit's @TempDir teardown,
the error named `out\\src\\main\\resources\\db`, and it was attributed to "a Windows file-lock in the
harness, not the test body" for long enough that the local generator gate was permanently red and
people had learned to explain it away. It was one unclosed `Files.list` on line 128.

RED-PROVEN against ec20ae5^ (the commit before S1's fix): this reports that exact line. A checker
that cannot detect the defect that already happened will not detect the next one.

SCOPE
-----
Production and test sources both -- S1's instance was in a TEST, and a red gate nobody trusts is as
expensive as a leak. Generated output and build directories are excluded: nothing there is authored.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

ROOTS = [
    REPO / "NPDevRuntimeHost" / "src",
    REPO / "NPDevGenerator" / "generator" / "src",
    REPO / "NPDevKernel",
    REPO / "NPDevContract" / "dsl" / "src",
]

# Anything under these is generated, vendored, or build residue -- not authored here.
EXCLUDED_PARTS = ("build", "Output", "ArtifactNP", "node_modules", ".gradle", "bin", "out")

STREAM_FACTORIES = ("list", "walk", "find", "lines", "newDirectoryStream")
CALL = re.compile(r"\bFiles\.(" + "|".join(STREAM_FACTORIES) + r")\s*\(")


def is_excluded(path: Path) -> bool:
    return any(part in EXCLUDED_PARTS for part in path.parts)


def offending_lines(text: str) -> list[tuple[int, str]]:
    """Lines calling a stream factory outside a try-with-resources header.

    Deliberately simple and deliberately conservative. A call is accepted when its own line opens a
    resource block (`try (`), or continues one (`;` inside a multi-resource header). Anything else --
    a call whose result is filtered, forEach'd, counted or assigned -- is reported. Chaining straight
    off the factory is precisely the leaking form, so flagging it is the point rather than a
    limitation.
    """
    findings = []
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("*") or stripped.startswith("//"):
            continue  # javadoc or comment -- including the ones explaining this very rule
        if not CALL.search(line):
            continue
        before = line[: CALL.search(line).start()]
        # `try (var s = Files.list(x))` and the second resource of a multi-resource header.
        if re.search(r"\btry\s*\($", before.rstrip()) or "try (" in before or "try(" in before:
            continue
        findings.append((number, stripped))
    return findings


def main() -> int:
    scanned = 0
    failures: list[str] = []
    for root in ROOTS:
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            if is_excluded(path.relative_to(REPO)):
                continue
            scanned += 1
            text = path.read_text(encoding="utf-8", errors="replace")
            for number, line in offending_lines(text):
                relative = path.relative_to(REPO).as_posix()
                failures.append(f"{relative}:{number}\n      {line}")

    print(f"Closeable-stream check: {scanned} Java file(s) scanned.")
    if failures:
        print(f"\nFAILED -- {len(failures)} unclosed Files stream(s):\n")
        for failure in failures:
            print(f"  - {failure}")
        print("\n  Each returns a Stream holding an OS handle that must be closed:")
        print("      try (var paths = Files.list(dir)) { paths.filter(...)... }")
        print("  On Windows a leaked directory handle leaves the directory DELETE-PENDING, so its")
        print("  PARENT cannot be removed -- and the error names the parent, not this line. That")
        print("  misdirection kept the generator gate red for a morning (S1, QUAL-2).")
        return 1
    print("OK: every Files.list/walk/find/lines/newDirectoryStream is in a try-with-resources.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
