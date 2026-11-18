#!/usr/bin/env python3
"""DSL keyword budget: the number of schema keywords may only go DOWN without an admission record.

    python -I scripts/quality/check-dsl-keyword-budget.py [--model-schema <p>] [--pack-schema <p>] [--policy <p>] [--admissions <dir>] [--list]

FORM P4-T1 (docs/adr/ADR-0013-twelve-nouns.md, the extension ladder). Every demand the platform could not express
became a new keyword, and a model that keeps growing keywords is a programming language written in JSON. This is the
ratchet that makes "add a keyword" a deliberate act.

WHAT IS COUNTED ("slots")
  * model.schema.json: every property declared DIRECTLY under each `$defs` entry, slot id `<def>.<property>`.
  * pack.schema.json:  every top-level property, slot id `pack:<property>` (that schema has no `$defs`).

THE RULES (scripts/policy/dsl-keyword-budget.json is the truth; no markdown is read)
  1. `frozenCount` must equal the measured slot count. Lower the schema -> lower `frozenCount` and drop the slot from
     `baselineSlots` in the same commit; the ceiling only ratchets down.
  2. A slot that is in the schema but in neither `baselineSlots` nor an admission record FAILS and is named.
  3. A slot becomes admitted by a record `scripts/policy/dsl-keyword-admissions/<name>.json`
     (schema npdev-keyword-admission.v1, see EXTENSION_LADDER admission test): `slots` lists the slot ids it covers
     and `answers` has all five of declarative / notLower / twice / enforced / taught with value true and a non-empty
     `why` (or `apps` / `probe` / `bundleLine` for the last three).
  4. A baseline slot the schema no longer has FAILS (remove it from the baseline and lower `frozenCount`).
  5. A record naming a slot the schema does not have FAILS, unless the record says `"status": "withdrawn"`.

Invoked by run-ai-knowledge-gate.ps1. Level core; triggers are the schemas, the policy and the admissions.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL = REPO_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
DEFAULT_PACK = REPO_ROOT / "NPDevContract" / "schemas" / "pack.schema.json"
DEFAULT_POLICY = REPO_ROOT / "scripts" / "policy" / "dsl-keyword-budget.json"
DEFAULT_ADMISSIONS = REPO_ROOT / "scripts" / "policy" / "dsl-keyword-admissions"
ANSWERS = ("declarative", "notLower", "twice", "enforced", "taught")
EVIDENCE_FIELD = {"twice": "apps", "enforced": "probe", "taught": "bundleLine"}


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def measure(model: dict, pack: dict) -> set[str]:
    slots: set[str] = set()
    for def_name, definition in (model.get("$defs") or {}).items():
        for prop in (definition.get("properties") or {}):
            slots.add(f"{def_name}.{prop}")
    for prop in (pack.get("properties") or {}):
        slots.add(f"pack:{prop}")
    return slots


def check_record(path: Path, record: dict) -> list[str]:
    problems = []
    name = path.name
    if record.get("schemaVersion") != "npdev-keyword-admission.v1":
        problems.append(f"{name}: schemaVersion must be npdev-keyword-admission.v1")
    if not isinstance(record.get("slots"), list) or not record["slots"]:
        problems.append(f"{name}: `slots` must list the slot ids this record admits (e.g. Flow.trigger)")
    answers = record.get("answers") or {}
    for key in ANSWERS:
        answer = answers.get(key)
        if not isinstance(answer, dict) or answer.get("value") is not True:
            problems.append(f"{name}: admission answer '{key}' must be an object with value true")
            continue
        evidence = EVIDENCE_FIELD.get(key)
        if evidence:
            if not answer.get(evidence):
                problems.append(f"{name}: answer '{key}' needs `{evidence}` (the evidence, not just a yes)")
        elif not str(answer.get("why", "")).strip():
            problems.append(f"{name}: answer '{key}' needs a non-empty `why`")
    return problems


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-schema", default=str(DEFAULT_MODEL))
    ap.add_argument("--pack-schema", default=str(DEFAULT_PACK))
    ap.add_argument("--policy", default=str(DEFAULT_POLICY))
    ap.add_argument("--admissions", default=str(DEFAULT_ADMISSIONS))
    ap.add_argument("--list", action="store_true", help="print the measured slots and exit 0")
    opts = ap.parse_args(argv[1:])

    current = measure(load(Path(opts.model_schema)), load(Path(opts.pack_schema)))
    if opts.list:
        print("\n".join(sorted(current)))
        return 0
    policy = load(Path(opts.policy))
    baseline = set(policy["baselineSlots"])
    errors: list[str] = []

    admitted: set[str] = set()
    adm_dir = Path(opts.admissions)
    for path in sorted(adm_dir.glob("*.json")) if adm_dir.is_dir() else []:
        record = load(path)
        withdrawn = record.get("status") == "withdrawn"
        if not withdrawn:
            errors.extend(check_record(path, record))
        for slot in record.get("slots", []):
            if slot not in current and not withdrawn:
                errors.append(f"{path.name}: admits '{slot}' but the schema has no such slot (mark the record withdrawn or fix the id)")
            admitted.add(slot)

    for slot in sorted(current - baseline - admitted):
        errors.append(
            f"NEW keyword slot '{slot}' has no admission record -- write scripts/policy/dsl-keyword-admissions/<name>.json "
            "answering the five admission questions (declarative, notLower, twice, enforced, taught), or solve the need at a lower rung "
            "(expression, function, step, code). See the extension ladder.")
    for slot in sorted(baseline - current):
        errors.append(f"baseline slot '{slot}' is no longer in the schema -- remove it from baselineSlots and lower frozenCount (the budget only falls)")
    if policy["frozenCount"] != len(current):
        errors.append(
            f"frozenCount is {policy['frozenCount']} but the schemas have {len(current)} keyword slots -- "
            + ("lower frozenCount (the ceiling ratchets down)" if len(current) < policy["frozenCount"] else "raising it needs admission records for every new slot"))

    if errors:
        print("DSL keyword budget: FAILED")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"DSL keyword budget: OK ({len(current)} slots = frozenCount {policy['frozenCount']}; {len(admitted)} admitted by record)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
