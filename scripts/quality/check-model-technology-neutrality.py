#!/usr/bin/env python3
r"""W0.3 (NPDEV_ROADMAP_2026-09-12.md, Wave 0): the Constitution's 'technology-neutral-model' law
(scripts/policy/constitution.json) claims "the model itself carries no technology identifier" --
but until config.schema.json's runtime.binder field existed (P0.6), that claim was not even
checkable, since springProfile/gradleTask were unconditionally required and the model/config split
was never enforced anywhere. Now that config.json legitimately carries `runtime.binder` and its
Spring-specific fields, this checker makes the model's HALF of the claim mechanical: a model.json
must carry no runtime-shaped key, no capability binding naming a technology, and no JDBC URL --
those belong to config.json (or the resolved DB plan), never the model.

Forbidden-key list, JDBC pattern and the adapter-substring list all live in
scripts/policy/technology-neutral-model-policy.json (R3's spirit: facts live in data, not in this
script) so a new leak class can be added without touching Python.

Scans every model.json in the corpus -- AppGen/apps/**/definition/model.json and
NPDevSamples/**/Input/model.json -- mirroring validate-corpus.py's own find_models() label
convention (the same corpus universe check-dsl-coverage.py/check-engine-variant-families.py mirror
rather than import, since a hyphenated filename is not an importable module name).

    python check-model-technology-neutrality.py
    python check-model-technology-neutrality.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / "scripts" / "policy" / "technology-neutral-model-policy.json"


def _default_appgen_root() -> Path:
    """Layer 2 (app definitions) lives OUTSIDE the repo and is not a git repo (REG-144: by
    CONTENTS, never a hardcoded drive letter)."""
    import os
    from_env = os.environ.get("NPDEV_APPGEN_APPS")
    if from_env:
        return Path(from_env).expanduser().resolve()
    return REPO_ROOT.parent / "AppGen" / "apps"


DEFAULT_APPGEN_ROOT = _default_appgen_root()
DEFAULT_SAMPLES_ROOT = REPO_ROOT / "NPDevSamples"


def find_models(appgen_root: Path, samples_root: Path) -> list[tuple[str, Path]]:
    """Mirrors validate-corpus.py's find_models() label convention exactly, including its
    Output-dir exclusion (a generated model.json copy under NPDevSamples/**/Output/ must never
    enter the tracked corpus) -- deliberately INCLUDES probes, unlike dsl_coverage.corpus.find_models:
    a probe is still real authored content that could leak a technology identifier, even though it
    is excluded from DSL feature-coverage measurement for an unrelated reason (narrow-fixture bias)."""
    models: list[tuple[str, Path]] = []
    if appgen_root.exists():
        for p in sorted(appgen_root.rglob("model.json")):
            if "Output" in p.relative_to(appgen_root).parts:
                continue
            rel = p.relative_to(appgen_root).parts
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            models.append((f"AppGen/apps/{app}", p))
    if samples_root.exists():
        for p in sorted(samples_root.rglob("model.json")):
            if "Output" in p.relative_to(samples_root).parts:
                continue
            rel = p.relative_to(samples_root).parts
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            models.append((f"NPDevSamples/{app}", p))
    return models


def load_policy() -> dict:
    return json.loads(POLICY_PATH.read_text(encoding="utf-8"))


def scan_model(model: object, policy: dict) -> list[str]:
    """Walk a parsed model.json and return one message per violation found."""
    forbidden_keys = set(policy["forbiddenKeys"])
    jdbc_pattern = re.compile(policy["jdbcUrlPattern"])
    adapter_field = policy["bindingAdapterField"]
    forbidden_substrings = [s.lower() for s in policy["forbiddenAdapterSubstrings"]]

    violations: list[str] = []

    def walk(node: object, path: str) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                child_path = f"{path}.{key}" if path else str(key)
                if key in forbidden_keys:
                    violations.append(
                        f"{child_path}: forbidden runtime-shaped key '{key}' -- belongs in "
                        f"config.json's runtime block, not the model"
                    )
                if key == adapter_field and isinstance(value, str):
                    lowered = value.lower()
                    for sub in forbidden_substrings:
                        if sub in lowered:
                            violations.append(
                                f"{child_path}: adapter id '{value}' names a technology "
                                f"('{sub}') -- a binding names a capability, never a framework"
                            )
                if isinstance(value, str) and jdbc_pattern.match(value):
                    violations.append(f"{child_path}: JDBC URL literal in model ('{value}')")
                walk(value, child_path)
        elif isinstance(node, list):
            for i, item in enumerate(node):
                walk(item, f"{path}[{i}]")

    walk(model, "")
    return violations


def calibrate() -> int:
    """Must FAIL on a model carrying a runtime-shaped key, PASS on a clean one."""
    policy = load_policy()
    ok = True

    dirty = {
        "namespace": "demo",
        "concepts": [{"name": "Widget", "fields": []}],
        "capabilities": [
            {"binding": {"capability": "persistence", "adapter": "spring-jdbc-template"}}
        ],
        "config": {"springProfile": "prod"},
        "connection": "jdbc:postgresql://localhost:5432/demo",
    }
    dirty_violations = scan_model(dirty, policy)
    fired = len(dirty_violations) >= 3  # key, adapter substring, jdbc url
    ok = ok and fired
    print(f"  [{'PASS' if fired else 'FAIL'}] dirty model ({len(dirty_violations)} violation(s) found)")

    clean = {
        "namespace": "demo",
        "concepts": [{"name": "Widget", "fields": []}],
        "capabilities": [
            {"binding": {"capability": "persistence", "adapter": "repository"}}
        ],
    }
    clean_violations = scan_model(clean, policy)
    silent = len(clean_violations) == 0
    ok = ok and silent
    print(f"  [{'PASS' if silent else 'FAIL'}] clean model ({len(clean_violations)} violation(s) found, expected 0)")

    if not ok:
        print("\nCALIBRATION FAILED: the checker did not distinguish a dirty model from a clean one.")
        return 1
    print("\nOK: a technology-leaking model fails, a neutral one passes.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--appgen-root", default=str(DEFAULT_APPGEN_ROOT))
    ap.add_argument("--samples-root", default=str(DEFAULT_SAMPLES_ROOT))
    ap.add_argument("--calibrate", action="store_true", help="prove this checker can fail")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    policy = load_policy()
    models = find_models(Path(args.appgen_root), Path(args.samples_root))

    total_violations = 0
    for label, path in models:
        try:
            model = json.loads(path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError as exc:
            print(f"FAIL: {label} ({path}) is not valid JSON: {exc}")
            total_violations += 1
            continue
        for v in scan_model(model, policy):
            print(f"FAIL: {label}: {v}")
            total_violations += 1

    print(f"\n{len(models)} model(s) scanned.")
    if total_violations:
        print(f"FAIL: {total_violations} technology-neutrality violation(s) found.")
        return 1
    print("PASS: no model carries a runtime-shaped key, a technology-named adapter, or a JDBC URL.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
