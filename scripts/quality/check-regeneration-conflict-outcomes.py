#!/usr/bin/env python3
r"""Path A P5.3 (NPDEV_PATH_A_REALIGNMENT_PLAN.md; docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md's
Promotion Workflow -- "the generator must never silently overwrite protected human-authored resources",
allowed conflict outcomes KeepCustom / ReplaceGenerated / AskUser / Block): proves the mechanism is
real, not just described.

Two independent checks, both required:

  1. Static: FinalAppAssembler.java and ExtensionInventoryEmitter.java still contain the classify/
     enforce/hash machinery this checker names -- catches someone deleting the mechanism without
     touching this file.
  2. Behavioral: actually RUNS FinalAppAssemblerRegenerationConflictTest (Gradle, :generator module)
     and requires it to pass -- the real RED/GREEN proof that KeepCustom survives a regeneration and
     Block/AskUser abort before deleteTree touches anything, not merely that the words appear in
     source. See "Prose About a Defect Is Not The Defect" -- the same discipline applies in reverse to
     a claimed FIX.

    python check-regeneration-conflict-outcomes.py
    python check-regeneration-conflict-outcomes.py --skip-test   # static-only, for fast local iteration
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATOR_DIR = REPO_ROOT / "NPDevGenerator"

ASSEMBLER = GENERATOR_DIR / "generator/src/main/java/com/npdev/generator/assembly/FinalAppAssembler.java"
INVENTORY_EMITTER = GENERATOR_DIR / "generator/src/main/java/com/npdev/generator/emitters/ExtensionInventoryEmitter.java"
CONTENT_HASH = GENERATOR_DIR / "generator/src/main/java/com/npdev/generator/emitters/GeneratedContentHash.java"
PROOF_TEST = GENERATOR_DIR / "generator/src/test/java/com/npdev/generator/assembly/FinalAppAssemblerRegenerationConflictTest.java"

REQUIRED_IN_ASSEMBLER = (
    "resolveRegenerationConflicts",
    "restoreKeepCustomContent",
    "KeepCustom",
    "ReplaceGenerated",
    "AskUser",
    "Block",
)
REQUIRED_IN_INVENTORY_EMITTER = ("generatedPaths", "GeneratedContentHash")
REQUIRED_TEST_METHODS = (
    "keepCustomSurvivesRegenerationWhenIntentIsPreserve",
    "unchangedFileRegeneratesNormallyDespiteADeclaredPreserveIntent",
    "undeclaredDriftBlocksRegenerationBeforeAnythingIsDeleted",
    "declaredAskBlocksRegenerationUntilResolved",
)


def check_static() -> list[str]:
    problems: list[str] = []
    for path in (ASSEMBLER, INVENTORY_EMITTER, CONTENT_HASH, PROOF_TEST):
        if not path.is_file():
            problems.append(f"missing file: {path.relative_to(REPO_ROOT)}")

    if ASSEMBLER.is_file():
        text = ASSEMBLER.read_text(encoding="utf-8")
        for token in REQUIRED_IN_ASSEMBLER:
            if token not in text:
                problems.append(f"FinalAppAssembler.java no longer mentions '{token}'")

    if INVENTORY_EMITTER.is_file():
        text = INVENTORY_EMITTER.read_text(encoding="utf-8")
        for token in REQUIRED_IN_INVENTORY_EMITTER:
            if token not in text:
                problems.append(f"ExtensionInventoryEmitter.java no longer mentions '{token}'")

    if PROOF_TEST.is_file():
        text = PROOF_TEST.read_text(encoding="utf-8")
        for method in REQUIRED_TEST_METHODS:
            if method not in text:
                problems.append(f"{PROOF_TEST.name} is missing proof scenario '{method}'")

    return problems


def run_proof_test() -> list[str]:
    gradlew = str(GENERATOR_DIR / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew"))
    result = subprocess.run(
        [gradlew, ":generator:test",
         "--tests", "com.npdev.generator.assembly.FinalAppAssemblerRegenerationConflictTest",
         "--console=plain", "-q"],
        cwd=GENERATOR_DIR,
    )
    if result.returncode != 0:
        return [f"FinalAppAssemblerRegenerationConflictTest did not pass (gradlew exit {result.returncode})"]
    return []


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--skip-test", action="store_true",
                     help="static checks only, for fast local iteration")
    args = ap.parse_args(argv[1:])

    problems = check_static()
    if not args.skip_test:
        print("Running FinalAppAssemblerRegenerationConflictTest for real (Gradle, :generator)...")
        problems += run_proof_test()

    if problems:
        print("\nregeneration conflict outcomes checks:")
        for p in problems:
            print(f"  - {p}")
        print(f"\nFAIL: {len(problems)} problem(s).", file=sys.stderr)
        return 1
    print("OK: regeneration-conflict-outcome mechanism is present and its proof test passes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
