#!/usr/bin/env python3
r"""Path A P0.3 (NPDEV_PATH_A_REALIGNMENT_PLAN.md): 119 files mention TrustedSource/javaHook/
trustLevel, 21 of them generator main classes, and the only way to answer "what escape hatches does
this app use" was grep. ExtensionInventoryEmitter (NPDevGenerator/generator/.../emitters/
ExtensionInventoryEmitter.java) now writes src/main/resources/npdev/extension-inventory.json on
every generation. This checker proves that artifact is actually produced, well-formed, and non-empty
for the real corpus fixtures that exercise each of the four escape hatches -- not just that the
generator command exited 0.

Generates two fixtures for real (NPDevSamples/scripts/generate-sample-app.ps1 -NoAssembleFinalApp --
emission only, no build/boot):
  - dsl-conformance-max: has a real conversions[].javaHook (0008-java-hook-order-summary) and one
    plugin:java-source mount (auditLog) -- proves the javaHook and pluginPackage categories.
  - probes/p7-plugin-controller: two plugin:java-controller mounts (adminTools, superOnlyTools) --
    proves the inProcessController category.

trustedSourceAsset has no fixture in either corpus location the generator actually builds from
(trusted-source-manifest.json only exists today under golden-ai-scenarios/, which the AI-authoring
validation pipeline consumes, not npdev generate) -- this checker asserts the key's shape (a
non-negative int) rather than a non-zero count for that one category, and says so in its output
rather than silently passing over the gap.

    python check-extension-inventory.py
    python check-extension-inventory.py --skip-generate   # assert against already-generated output
    python check-extension-inventory.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATE_SCRIPT = REPO_ROOT / "NPDevSamples" / "scripts" / "generate-sample-app.ps1"

CATEGORIES = ("trustedSourceAsset", "javaHook", "inProcessController", "pluginPackage")

FIXTURES = {
    "dsl-conformance-max": {
        "minCounts": {"javaHook": 1, "pluginPackage": 1},
    },
    "probes/p7-plugin-controller": {
        "minCounts": {"inProcessController": 1},
    },
}


def inventory_path(sample_id: str) -> Path:
    return (REPO_ROOT / "NPDevSamples" / sample_id / "Output" / "ArtifactNP"
            / "src" / "main" / "resources" / "npdev" / "extension-inventory.json")


def generate(sample_id: str) -> None:
    result = subprocess.run(
        [
            "pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", str(GENERATE_SCRIPT),
            "-SampleId", sample_id,
            "-NPDevRoot", str(REPO_ROOT),
            "-NoAssembleFinalApp",
        ],
        cwd=REPO_ROOT,
    )
    if result.returncode != 0:
        print(f"FAIL: generation of {sample_id} failed (exit {result.returncode}) -- see the "
              f"generator output above.", file=sys.stderr)
        sys.exit(1)


def check_inventory(inventory: dict, min_counts: dict) -> list[str]:
    problems = []
    if inventory.get("schemaVersion") != "1.0":
        problems.append(f"schemaVersion missing or unexpected: {inventory.get('schemaVersion')!r}")
    counts = inventory.get("counts")
    if not isinstance(counts, dict):
        problems.append("counts is missing or not an object")
        counts = {}
    entries = inventory.get("entries")
    if not isinstance(entries, list):
        problems.append("entries is missing or not an array")
        entries = []

    for category in CATEGORIES:
        value = counts.get(category, 0)
        if not isinstance(value, int) or value < 0:
            problems.append(f"counts.{category} is not a non-negative int: {value!r}")

    for category, minimum in min_counts.items():
        actual = counts.get(category, 0)
        if not isinstance(actual, int) or actual < minimum:
            problems.append(f"counts.{category} = {actual}, expected >= {minimum}")

    for index, entry in enumerate(entries):
        for field in ("category", "kind", "origin", "owner"):
            if not isinstance(entry, dict) or not isinstance(entry.get(field), str):
                problems.append(f"entries[{index}].{field} missing or not a string")

    computed = {}
    for entry in entries:
        if isinstance(entry, dict):
            category = entry.get("category")
            computed[category] = computed.get(category, 0) + 1
    for category in CATEGORIES:
        if counts.get(category, 0) != computed.get(category, 0):
            problems.append(
                f"counts.{category} ({counts.get(category, 0)}) does not match the number of "
                f"entries[] tagged that category ({computed.get(category, 0)})")

    return problems


SYNTHETIC_VALID = {
    "schemaVersion": "1.0",
    "counts": {"trustedSourceAsset": 1, "javaHook": 1, "inProcessController": 0, "pluginPackage": 1},
    "entries": [
        {"category": "trustedSourceAsset", "kind": "panel", "origin": "panels/x.html", "owner": "x"},
        {"category": "javaHook", "kind": "conversionJavaHook", "origin": "h.java#m", "owner": "conv1"},
        {"category": "pluginPackage", "kind": "plugin-id", "origin": "pkg", "owner": "cap"},
    ],
}

SYNTHETIC_INVALID_MISSING_KEY = {"counts": {}, "entries": []}
SYNTHETIC_INVALID_COUNT_MISMATCH = {
    "schemaVersion": "1.0",
    "counts": {"trustedSourceAsset": 0, "javaHook": 5, "inProcessController": 0, "pluginPackage": 0},
    "entries": [],
}


def calibrate() -> int:
    ok = True

    def report(label: str, inventory: dict, min_counts: dict, expect_clean: bool) -> None:
        nonlocal ok
        problems = check_inventory(inventory, min_counts)
        passed = (len(problems) == 0) == expect_clean
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({len(problems)} problem(s))")
        for p in problems:
            print(f"           {p}")

    print("Calibration -- assertion logic must catch a malformed/incomplete inventory and stay "
          "quiet on a well-formed one:")
    report("well-formed synthetic inventory", SYNTHETIC_VALID,
           {"javaHook": 1, "pluginPackage": 1}, expect_clean=True)
    report("missing schemaVersion/entries", SYNTHETIC_INVALID_MISSING_KEY, {}, expect_clean=False)
    report("counts disagree with entries[]", SYNTHETIC_INVALID_COUNT_MISMATCH, {}, expect_clean=False)

    if not ok:
        print("\nFAIL: the assertion logic did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: assertion logic behaves correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true")
    ap.add_argument("--skip-generate", action="store_true",
                     help="assert against already-generated output, for local iteration")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    all_problems: dict[str, list[str]] = {}
    for sample_id, fixture in FIXTURES.items():
        if not args.skip_generate:
            print(f"Generating {sample_id} for real (emission only, no build/boot needed)...")
            generate(sample_id)

        path = inventory_path(sample_id)
        if not path.exists():
            all_problems[sample_id] = [f"extension-inventory.json not found at {path}"]
            continue

        inventory = json.loads(path.read_text(encoding="utf-8"))
        all_problems[sample_id] = check_inventory(inventory, fixture["minCounts"])

    print("\nextension-inventory.json checks:")
    total_problems = 0
    for sample_id, problems in all_problems.items():
        marker = "OK" if not problems else f"{len(problems)} PROBLEM(S)"
        print(f"  {sample_id.ljust(32)} [{marker}]")
        for p in problems:
            print(f"    - {p}")
        total_problems += len(problems)

    print(
        "\nNote: trustedSourceAsset has no generatable corpus fixture today (trusted-source-"
        "manifest.json only exists under golden-ai-scenarios/, consumed by AI-authoring validation, "
        "not npdev generate) -- only its shape is checked here, not a non-zero count."
    )

    if total_problems:
        print(f"\nFAIL: {total_problems} problem(s) across {len(FIXTURES)} fixture(s).", file=sys.stderr)
        return 1
    print("\nOK: extension-inventory.json is present and well-formed for every checked fixture.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
