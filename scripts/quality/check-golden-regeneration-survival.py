#!/usr/bin/env python3
r"""Path A P5.4 (NPDEV_PATH_A_REALIGNMENT_PLAN.md): "Generate, customize, change the model,
regenerate, custom code survives. The claim is worthless without the test." P5.3 built the
classify/enforce mechanism (FinalAppAssembler) and proved it against a hand-built extension-
inventory.json fixture (FinalAppAssemblerRegenerationConflictTest). This checker is the OTHER half:
the same claim, proven end to end through the REAL generator + assembler pipeline, on a real corpus
fixture -- NPDevSamples/probes/path-a-regeneration-survival.

What it actually does (real subprocess calls, no mocking):

  1. Generate + assemble the probe for real (first pass -- establishes "a previous generation
     happened", P5.3's baseline).
  2. Read the REAL extension-inventory.json this produced, find the 'survival-hook' javaHook owner
     (declared regenerationIntent=preserve in the fixture's own customization-provenance.json), and
     hand-edit its real generated .java file -- append a marker line, standing in for a user
     customization.
  3. Swap model.v2.json (adds a field to SurvivalRecord) over model.json, standing in for "the model
     changed elsewhere". Restored in a finally block no matter what happens, so a crash here never
     leaves the committed corpus fixture mutated.
  4. Generate + assemble again for real (the regenerate).
  5. Assert: the hand-edited marker survived verbatim in the regenerated hook file (KeepCustom), AND
     the new field from model.v2.json shows up in the regenerated entity (ordinary regeneration still
     works for everything else), AND neither pass failed/blocked.

    python check-golden-regeneration-survival.py
    python check-golden-regeneration-survival.py --calibrate   # assertion-logic self-test only
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATE_SCRIPT = REPO_ROOT / "NPDevSamples" / "scripts" / "generate-sample-app.ps1"
SAMPLE_ID = "probes/path-a-regeneration-survival"
INPUT_ROOT = REPO_ROOT / "NPDevSamples" / SAMPLE_ID / "Input"
OUTPUT_APP_ROOT = REPO_ROOT / "NPDevSamples" / SAMPLE_ID / "Output" / "App"
GENERATED_MOUNT = OUTPUT_APP_ROOT / "npdev-generated"
INVENTORY_PATH = GENERATED_MOUNT / "src" / "main" / "resources" / "npdev" / "extension-inventory.json"
ENTITY_PATH = GENERATED_MOUNT / "src" / "main" / "java" / "com" / "npdev" / "generated" / "entities" / "SurvivalRecord.java"
OWNER = "survival-hook"
MARKER = "// HAND-EDITED MARKER (check-golden-regeneration-survival.py)"
NEW_FIELD_MARKER = "note"


def generate() -> None:
    result = subprocess.run(
        [
            "pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", str(GENERATE_SCRIPT),
            "-SampleId", SAMPLE_ID,
            "-NPDevRoot", str(REPO_ROOT),
        ],
        cwd=REPO_ROOT,
    )
    if result.returncode != 0:
        print(f"FAIL: generate+assemble of {SAMPLE_ID} failed (exit {result.returncode}) -- see the "
              f"generator output above. If this is the SECOND pass, this is exactly what a real Block/"
              f"AskUser outcome looks like -- check whether that's expected before treating it as a bug.",
              file=sys.stderr)
        sys.exit(1)


def hook_generated_path() -> str:
    if not INVENTORY_PATH.is_file():
        print(f"FAIL: extension-inventory.json not found at {INVENTORY_PATH} after generation", file=sys.stderr)
        sys.exit(1)
    inventory = json.loads(INVENTORY_PATH.read_text(encoding="utf-8"))
    for entry in inventory.get("entries", []):
        if entry.get("owner") == OWNER:
            paths = entry.get("generatedPaths") or []
            if not paths:
                print(f"FAIL: owner '{OWNER}' has no generatedPaths in extension-inventory.json -- "
                      f"P5.3's generatedPaths attribution regressed", file=sys.stderr)
                sys.exit(1)
            return paths[0]
    print(f"FAIL: no extension-inventory.json entry for owner '{OWNER}'", file=sys.stderr)
    sys.exit(1)


def hand_edit_hook_file(relative_path: str) -> None:
    hook_file = GENERATED_MOUNT / relative_path
    if not hook_file.is_file():
        print(f"FAIL: generated hook file not found at {hook_file}", file=sys.stderr)
        sys.exit(1)
    with hook_file.open("a", encoding="utf-8") as f:
        f.write(f"\n{MARKER}\n")


def marker_survived(relative_path: str) -> bool:
    hook_file = GENERATED_MOUNT / relative_path
    return hook_file.is_file() and MARKER in hook_file.read_text(encoding="utf-8")


def new_field_present() -> bool:
    return ENTITY_PATH.is_file() and NEW_FIELD_MARKER in ENTITY_PATH.read_text(encoding="utf-8")


def run_real_scenario() -> list[str]:
    problems: list[str] = []

    print(f"Generating {SAMPLE_ID} for real (first pass)...")
    generate()

    relative_hook_path = hook_generated_path()
    hand_edit_hook_file(relative_hook_path)

    model_path = INPUT_ROOT / "model.json"
    model_v2_path = INPUT_ROOT / "model.v2.json"
    original_model_json = model_path.read_text(encoding="utf-8")
    try:
        shutil.copyfile(model_v2_path, model_path)
        print(f"Generating {SAMPLE_ID} for real (second pass -- the regenerate)...")
        generate()
    finally:
        model_path.write_text(original_model_json, encoding="utf-8")

    if not marker_survived(relative_hook_path):
        problems.append(
            f"the hand-edited marker did NOT survive regeneration in {relative_hook_path} -- "
            f"KeepCustom is not actually preserving a declared customization")
    if not new_field_present():
        problems.append(
            f"the model.v2.json field '{NEW_FIELD_MARKER}' did not appear in the regenerated entity -- "
            f"either the model swap did not take effect, or regeneration stopped applying real changes")

    return problems


SYNTHETIC_HOOK_CONTENT_CLEAN = "public final class SurvivalHook {\n}\n"
SYNTHETIC_HOOK_CONTENT_WITH_MARKER = SYNTHETIC_HOOK_CONTENT_CLEAN + f"\n{MARKER}\n"
SYNTHETIC_ENTITY_WITH_FIELD = "private String note;\n"
SYNTHETIC_ENTITY_WITHOUT_FIELD = "private String summary;\n"


def calibrate() -> int:
    ok = True

    def report(label: str, passed: bool, expected: bool) -> None:
        nonlocal ok
        good = passed == expected
        ok = ok and good
        print(f"  [{'PASS' if good else 'FAIL'}] {label}")

    print("Calibration -- assertion logic must detect marker presence/absence and field presence/absence:")
    report("marker present -> detected", MARKER in SYNTHETIC_HOOK_CONTENT_WITH_MARKER, expected=True)
    report("marker absent -> not detected", MARKER in SYNTHETIC_HOOK_CONTENT_CLEAN, expected=False)
    report("new field present -> detected", NEW_FIELD_MARKER in SYNTHETIC_ENTITY_WITH_FIELD, expected=True)
    report("new field absent -> not detected", NEW_FIELD_MARKER in SYNTHETIC_ENTITY_WITHOUT_FIELD, expected=False)

    if not ok:
        print("\nFAIL: the assertion logic did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: assertion logic behaves correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    problems = run_real_scenario()

    print("\ngolden regeneration-survival checks:")
    if problems:
        for p in problems:
            print(f"  - {p}")
        print(f"\nFAIL: {len(problems)} problem(s).", file=sys.stderr)
        return 1
    print("  OK: the hand-edited customization survived regeneration verbatim, and the model change "
          "elsewhere still took effect.")
    print("\nOK: golden regeneration-survival proof passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
