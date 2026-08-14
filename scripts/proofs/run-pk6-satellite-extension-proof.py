#!/usr/bin/env python3
"""PK-6 Step 1 (PACK-ROADMAP.md card PK-6, "satellite concepts") real proof: an extension pack
declares a concept with a declared 1:1 relationship to a base pack concept (`satelliteOf:
"identity::User"`) WITHOUT modifying the base pack, and that relationship is a REAL, enforced FK --
not just validated JSON.

Generates, builds and boots `NPDevSamples/probes/p6-satellite-extension` (the built-in `identity`
pack composed, transitively, with a throwaway extension pack `clinical` that declares
`clinical::UserClinicalProfile` with `satelliteOf: "identity::User"`), then exercises the real
generated CRUD REST surface:

  1. POST a base `identity::User` row through its generated endpoint
     (`/api/concepts/identity_users` -- route names are ALIAS/logical, not version-qualified; see
     PACK-ROADMAP.md PK-2's own "routes are logical, tables are physical" recommendation).
  2. POST a satellite `clinical::UserClinicalProfile` row through ITS generated endpoint
     (`/api/concepts/clinical_user_clinical_profiles`) referencing that user's id -- the 1:1 anchor
     bond, a REAL foreign key, not a satelliteOf-flavoured mock.
  3. GET the base user back and assert it carries NONE of the satellite's fields -- the base pack
     stays untouched at runtime, not just in the compiled model.
  4. RED control: POST a second satellite row referencing a user id that does not exist. Must be
     REFUSED (never silently accepted, never a 500) -- the real bond-integrity mapping
     (`bond_target_not_found`) generated CRUD already provides for every reference field, now proven
     for a satelliteOf-declared one too.
  5. DELETE the base user through its own generated endpoint, then GET the satellite row and record
     the ACTUAL observed outcome. PK-6's own card names this explicitly as a risk to "decide and test
     explicitly" -- the probe's anchor field declares `onDelete: cascade`, and a prior version of this
     proof only ever checked that COMPILED value, never whether cascade genuinely fires through the
     generated app at runtime. This assertion reports the true outcome (cascaded away, orphaned, or
     the delete itself blocked) rather than assuming the declared behavior took effect.

Also asserts the probe's checked-in copy of the identity pack is still byte-identical to the real
built-in pack (`NPDevContract/packs/identity/pack.json`) -- this probe intentionally embeds a static
copy (so `validate-corpus.py`'s standalone `validateModel` pass and this proof both resolve without
any run-time staging step), and a drifted copy would silently prove something other than the shipped
artifact.

The compiled-model-level assertions (physical table names, base concept's field set, satelliteOf
surviving parse -> resolve -> compile) are already covered by
`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/PackSatelliteExtensionResolutionTest.java` -- this
script does not repeat them; it proves the parts a unit test cannot: a real HTTP boundary, a real H2
database, and a real FK constraint.

USAGE
    python scripts/proofs/run-pk6-satellite-extension-proof.py --engine h2local

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

_USER_ENDPOINT = "/api/concepts/identity_users"
_SATELLITE_ENDPOINT = "/api/concepts/clinical_user_clinical_profiles"
_NIL_UUID = "00000000-0000-0000-0000-000000000000"


def _assert_identity_copy_fresh(root: Path, staged_input: Path, results: list[dict]) -> None:
    """Compares CONTENT, not bytes: `$schema` is a relative path from the file's own location, so
    the probe's copy (nested several directories deeper than the real pack) legitimately spells it
    differently -- excusing exactly that one key mirrors check-schema-mirror-consistency.py's own
    "semantic, not byte-identical" comparison discipline for the mirrored model.schema.json copies.
    """
    real = root / "NPDevContract" / "packs" / "identity" / "pack.json"
    staged = staged_input / "packs" / "identity" / "pack.json"
    real_doc = json.loads(real.read_text(encoding="utf-8"))
    staged_doc = json.loads(staged.read_text(encoding="utf-8"))
    real_doc.pop("$schema", None)
    staged_doc.pop("$schema", None)
    ok = real_doc == staged_doc
    results.append({
        "assertion": "probe-identity-copy-is-fresh",
        "ok": ok,
        "detail": ("the probe's checked-in copy of the identity pack matches the real built-in pack "
                   "(ignoring the location-relative $schema key)" if ok else
                   f"the probe's checked-in copy at {staged} has DRIFTED from the real built-in pack "
                   f"at {real} -- refresh NPDevSamples/probes/p6-satellite-extension/Input/packs/"
                   f"identity/pack.json from the real file"),
    })
    log(f"{'PASS' if ok else 'FAIL'}  probe-identity-copy-is-fresh: {results[-1]['detail']}")


def assertions(app: "_engine_proof.App", results: list[dict]) -> None:
    base = app.base()

    def record(name: str, ok: bool, detail: str) -> None:
        results.append({"assertion": name, "ok": bool(ok), "detail": detail})
        log(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")

    # 1. Base pack's own generated CRUD endpoint still works, untouched by the extension. A unique
    # username per run: H2Local is file-based and persists across runs against the same --db-name,
    # so a fixed username would collide with a prior run's row on identity::User's own unique
    # constraint (exactly what the first, unfixed version of this script did).
    marker = uuid.uuid4().hex[:10]
    status, user = http("POST", base + _USER_ENDPOINT, {
        "username": f"satellite-probe-user-{marker}",
        "displayName": "Satellite Probe User",
        "email": f"satellite-probe-{marker}@example.com",
        "active": True,
    })
    if status not in (200, 201) or not user or "id" not in user:
        record("base-user-created", False, f"POST {_USER_ENDPOINT} returned {status}: {user}")
        return
    user_id = user["id"]
    record("base-user-created", True, f"identity::User row created via {_USER_ENDPOINT}, id={user_id}")

    # 2. Satellite's own generated CRUD endpoint, with a REAL 1:1 FK to the base row.
    status, satellite = http("POST", base + _SATELLITE_ENDPOINT, {
        "userId": user_id,
        "bloodType": "O+",
        "allergyNotes": "none",
    })
    ok = status in (200, 201) and satellite is not None and satellite.get("userId") == user_id
    record("satellite-row-references-base-row", ok,
           f"POST {_SATELLITE_ENDPOINT} returned {status}: {satellite}")
    satellite_id = satellite.get("id") if ok and isinstance(satellite, dict) else None

    # 3. The base row itself carries none of the satellite's fields -- satelliteOf composes at the
    # generated-app boundary WITHOUT touching the base pack's own concept, not merely in the schema.
    status, reread_user = http("GET", f"{base}{_USER_ENDPOINT}/{user_id}")
    leaked = isinstance(reread_user, dict) and (
        "bloodType" in reread_user or "allergyNotes" in reread_user)
    record("base-user-carries-no-satellite-fields", status == 200 and not leaked,
           f"GET {_USER_ENDPOINT}/{user_id} -> {status}: {reread_user}")

    # 4. RED control: a satellite row referencing a user id that does not exist must be REFUSED.
    status, refusal = http("POST", base + _SATELLITE_ENDPOINT, {
        "userId": _NIL_UUID,
        "bloodType": "A-",
    })
    refused_cleanly = status in (400, 404, 409, 422) and isinstance(refusal, dict)
    record("dangling-satellite-reference-is-refused", refused_cleanly,
           f"POST {_SATELLITE_ENDPOINT} with a nonexistent userId returned {status}: {refusal} "
           f"(must be a clean 4xx refusal, never 200/201/500 -- a satellite's anchor bond is a REAL "
           f"foreign key, not a soft validation)")

    # 5. onDelete:cascade is DECLARED on the anchor field and CompiledField-level tested
    # (PackSatelliteExtensionResolutionTest), but neither of those observes what actually happens at
    # runtime when the base row is deleted through the real generated app -- PK-6's own card names
    # this exact question as a risk to "decide and test explicitly". Report the true outcome, not an
    # assumed one.
    if satellite_id is None:
        record("base-row-delete-cascades-to-satellite", False,
               "skipped -- no satellite id captured from assertion 2 (it must have failed)")
        return
    delete_status, delete_response = http("DELETE", f"{base}{_USER_ENDPOINT}/{user_id}")
    if delete_status not in (200, 204):
        record("base-row-delete-cascades-to-satellite", False,
               f"DELETE {_USER_ENDPOINT}/{user_id} returned {delete_status}: {delete_response} -- "
               f"the base row delete itself was refused, so cascade behavior could not be observed "
               f"at all")
        return
    get_status, satellite_after_delete = http("GET", f"{base}{_SATELLITE_ENDPOINT}/{satellite_id}")
    cascaded = get_status in (404, 410)
    outcome = (
        "the satellite row was genuinely removed -- onDelete:cascade fires end to end through the "
        "generated app, not just at the compiled-model level" if cascaded else
        "the satellite row SURVIVED the base row delete -- onDelete:cascade for a satelliteOf "
        "anchor bond did NOT take effect at runtime; this is a real, previously-unverified gap, "
        "not a pass"
    )
    record("base-row-delete-cascades-to-satellite", cascaded,
           f"DELETE {_USER_ENDPOINT}/{user_id} returned {delete_status}; "
           f"GET {_SATELLITE_ENDPOINT}/{satellite_id} afterward returned {get_status}: "
           f"{satellite_after_delete} -- {outcome}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engine", default="h2local")
    parser.add_argument("--port", type=int, default=18391)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=None)
    parser.add_argument("--db-user", default=None)
    parser.add_argument("--db-password", default=None)
    parser.add_argument("--db-name", default="npdev_p6_satellite_extension")
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--report", default=None)
    parser.add_argument("--work", default=None,
                        help="Where to generate (default: <build root>/pk6-satellite-proof).")
    args = parser.parse_args(argv)

    root = _engine_proof._repo_root()
    probe = root / "NPDevSamples" / "probes" / "p6-satellite-extension"
    app_input = probe / "Input"
    if not (app_input / "model.json").exists():
        raise SystemExit(f"probe model not found at {app_input / 'model.json'}")
    if not (app_input / "npdev.lock").exists():
        raise SystemExit(f"probe has no committed npdev.lock at {app_input / 'npdev.lock'} -- "
                         f"'clinical' pulls 'identity' in TRANSITIVELY (PK-3), and generation reads "
                         f"the lock, not the constraints")

    build_root = Path(os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build"))
    work = Path(args.work) if args.work else build_root / "pk6-satellite-proof" / args.engine
    work.mkdir(parents=True, exist_ok=True)
    output = work / "App"
    boot_log = work / "boot.log"
    if boot_log.exists():
        boot_log.unlink()

    staged_input = _engine_proof.stage_input(app_input, work)
    definition = _engine_proof.write_db_definition(staged_input, args)
    log(f"engine {args.engine}: {json.dumps(definition['database'])}")

    results: list[dict] = []
    _assert_identity_copy_fresh(root, staged_input, results)

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
        "schemaVersion": "npdev-pk6-satellite-extension-proof.v1",
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
