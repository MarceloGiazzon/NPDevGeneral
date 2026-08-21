#!/usr/bin/env python3
"""PACK-10 live proof: first-class `extends` keyword end-to-end.

Generates, builds and boots `NPDevSamples/probes/p10-extension-pack` (clinicbase pack with Patient
concept, composed with a throwaway extension pack 'clinicext' that uses the FIRST-CLASS `extends`
keyword -- NOT metadata.extends -- to additively contribute a bloodType field onto clinicbase::Patient),
then exercises the real generated CRUD REST surface:

  1. POST a base Patient row through its generated endpoint (`/api/concepts/clinicbase_patients`).
     The Patient concept ends up with both name (from base) and bloodType (from extension) after
     composition -- proving the first-class `extends` keyword was accepted and processed.
  2. GET the Patient back and assert it carries BOTH the base field (name) AND the extension field
     (bloodType) -- the extension mechanism merged them additively at compile time, and the generated
     app exposes them as a single unified concept at runtime.
  3. RED control: the extension pack's bloodType field is nullable (required: false), so a Patient
     created WITHOUT bloodType must succeed -- proving the extension field is genuinely additive and
     optional, not a breaking change to the base concept's contract.

The compiled-model-level assertions (extension field merging, collision refusal, sealedness refusal)
are already covered by `PackExtensionComposerTest.java` (12 cases) -- this script does not repeat
them; it proves the parts a unit test cannot: a real HTTP boundary, a real H2 database, and a real
generated app that exposes the extended concept as a unified CRUD surface.

USAGE
    python scripts/proofs/run-pk10-first-class-extends-proof.py --engine h2local

Exit 0 = every assertion passed. Exit 1 = at least one failed.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
import uuid
from pathlib import Path

_ENGINE_PROOF = Path(__file__).resolve().parent.parent / "quality" / "run-engine-app-proof.py"
_spec = importlib.util.spec_from_file_location("npdev_engine_app_proof", _ENGINE_PROOF)
_engine_proof = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_engine_proof)

http = _engine_proof.http
log = _engine_proof.log

_PATIENT_ENDPOINT = "/api/concepts/clinicbase_patients"


def assertions(app: "_engine_proof.App", results: list[dict]) -> None:
    base = app.base()

    def record(name: str, ok: bool, detail: str) -> None:
        results.append({"assertion": name, "ok": bool(ok), "detail": detail})
        log(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")

    # 1. Base Patient endpoint works -- the first-class `extends` keyword was accepted by the schema
    # and processed by PackExtensionComposer, producing a unified Patient concept with both base and
    # extension fields. A unique name per run: H2Local is file-based and persists across runs.
    marker = uuid.uuid4().hex[:10]
    patient_name = f"probe-patient-{marker}"
    status, patient = http("POST", base + _PATIENT_ENDPOINT, {
        "name": patient_name,
        "bloodType": "O+",
    })
    if status not in (200, 201) or not patient or "id" not in patient:
        record("patient-created-with-extension-field", False,
               f"POST {_PATIENT_ENDPOINT} returned {status}: {patient}")
        return
    patient_id = patient["id"]
    record("patient-created-with-extension-field", True,
           f"clinicbase::Patient row created via {_PATIENT_ENDPOINT} with bloodType, id={patient_id}")

    # 2. GET the Patient back and assert it carries BOTH the base field (name) AND the extension
    # field (bloodType) -- the extension mechanism merged them additively at compile time.
    status, reread = http("GET", f"{base}{_PATIENT_ENDPOINT}/{patient_id}")
    has_name = isinstance(reread, dict) and reread.get("name") == patient_name
    has_blood_type = isinstance(reread, dict) and reread.get("bloodType") == "O+"
    record("patient-carries-both-base-and-extension-fields",
           status == 200 and has_name and has_blood_type,
           f"GET {_PATIENT_ENDPOINT}/{patient_id} -> {status}: {reread} "
           f"(name={reread.get('name') if isinstance(reread, dict) else None!r}, "
           f"bloodType={reread.get('bloodType') if isinstance(reread, dict) else None!r})")

    # 3. RED control: the extension field is nullable (required: false), so a Patient created
    # WITHOUT bloodType must succeed -- proving the extension is genuinely additive and optional.
    marker2 = uuid.uuid4().hex[:10]
    patient_name2 = f"probe-patient-no-ext-{marker2}"
    status2, patient2 = http("POST", base + _PATIENT_ENDPOINT, {
        "name": patient_name2,
    })
    ok = status2 in (200, 201) and patient2 is not None and patient2.get("name") == patient_name2
    record("patient-created-without-extension-field", ok,
           f"POST {_PATIENT_ENDPOINT} without bloodType returned {status2}: {patient2} "
           f"(the extension field is nullable, so the base concept's contract is not broken)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engine", default="h2local")
    parser.add_argument("--port", type=int, default=18392)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=None)
    parser.add_argument("--db-user", default=None)
    parser.add_argument("--db-password", default=None)
    parser.add_argument("--db-name", default="npdev_p10_first_class_extends")
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--report", default=None)
    parser.add_argument("--work", default=None,
                        help="Where to generate (default: <build root>/pk10-first-class-extends-proof).")
    args = parser.parse_args(argv)

    root = _engine_proof._repo_root()
    probe = root / "NPDevSamples" / "probes" / "p10-extension-pack"
    app_input = probe / "Input"
    if not (app_input / "model.json").exists():
        raise SystemExit(f"probe model not found at {app_input / 'model.json'}")

    build_root = Path(os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build"))
    work = Path(args.work) if args.work else build_root / "pk10-first-class-extends-proof" / args.engine
    work.mkdir(parents=True, exist_ok=True)
    output = work / "App"
    boot_log = work / "boot.log"
    if boot_log.exists():
        boot_log.unlink()

    staged_input = _engine_proof.stage_input(app_input, work)
    definition = _engine_proof.write_db_definition(staged_input, args)
    log(f"engine {args.engine}: {json.dumps(definition['database'])}")

    results: list[dict] = []

    app: "_engine_proof.App | None" = None
    failure: str | None = None
    try:
        _engine_proof.generate(root, staged_input, output, args.engine)
        jar = _engine_proof.build(output)
        app = _engine_proof.App(jar, output, args.port, boot_log)
        app.start(args.boot_timeout)
        assertions(app, results)
    except SystemExit as exc:
        failure = str(exc)
        log(f"ABORTED: {failure}")
    finally:
        if app is not None:
            app.stop()

    ok = failure is None and all(result["ok"] for result in results)
    if not ok:
        _engine_proof.print_boot_log_tail(boot_log)
    report = {
        "schemaVersion": "npdev-pk10-first-class-extends-proof.v1",
        "engine": args.engine,
        "ok": ok,
        "failure": failure,
        "assertions": results,
        "bootLog": str(boot_log),
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
