#!/usr/bin/env python3
"""Tier C: the four vectors that genuinely need a generated app -- E1, E2, I2, I3 (exit criterion E7).

WHY THESE FOUR AND NOT THE OTHER SIXTEEN
----------------------------------------
`storage/PROBE_APPS.md` measured it: 16 of the 20 behavioural vectors need only a connection and a
hand-made table, which is what Tier B does in seconds with no Docker. Four need a **realized** schema
-- one produced by NPDev's own generator and schema engine, not by a hand-written CREATE TABLE. A
hand-made table would test the test's SQL, not NPDev's.

    E1  adding a NULLABLE column preserves existing rows      p2-evolve v1 -> v2
    E2  `renamedFrom` MOVES the data rather than dropping it  p3-rename  v1 -> v2
    I2  a nullable field is genuinely nullable in the realized schema   p4-constraints
    I3  a `unique: true` field is enforced; a plain index is not        p4-constraints

E1 and E2 are the two probes where "you can throw the data away" does NOT apply: they exist to prove
data SURVIVES a change, so both use `KeepExistingIfCompatible` and neither may recreate.

ASSERTED THROUGH BEHAVIOUR, NOT THE CATALOG
-------------------------------------------
I2 and I3 could be checked by reading `information_schema`. They are not, for the same reason Tier B
asserts behaviour rather than SQL text: a catalog read is spelled differently on every engine, so the
test would need a per-engine branch and would then be testing its own introspection instead of the
platform's. "Inserting a duplicate email is REJECTED and a duplicate region is ACCEPTED" is one
assertion that every engine can satisfy in its own way -- and it is the property a user actually
depends on.

USAGE
    python scripts/quality/run-tier-c-probes.py --engine mysql --db-host 127.0.0.1 \
        --db-port 3306 --db-user npdev --db-password npdev --report <path>

Exit 0 = every vector passed. Exit 1 = at least one failed. Exit 2 = usage/setup problem.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
import uuid
from pathlib import Path

_ENGINE_PROOF = Path(__file__).resolve().parent / "run-engine-app-proof.py"
_spec = importlib.util.spec_from_file_location("npdev_engine_app_proof", _ENGINE_PROOF)
_engine_proof = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_engine_proof)

http = _engine_proof.http
log = _engine_proof.log


def _prepare(root: Path, probe_input: Path, args, database_name: str, work: Path) -> Path:
    """Stage the probe into the build root and write the engine's db.definition.json THERE.

    Never beside the checked-in probe: the engine is a run-time choice, so that file is a generated
    artifact, and BUILD_OUTPUT_LOCATION_POLICY keeps generated artifacts out of the repo (a stray one
    also trips the slimness commit hook). Returns the staged Input directory.
    """
    sys.path.insert(0, str(root / "NPDevCli"))
    import npdev_engines  # noqa: PLC0415

    staged = _engine_proof.stage_input(probe_input, work)

    definition = npdev_engines.db_definition_for(
        args.engine, database_name=database_name, host=args.db_host, port=args.db_port,
        username=args.db_user, password=args.db_password)
    # E1/E2 measure PRESERVATION, so a recreate strategy would make them pass by having nothing to
    # preserve. Stated here rather than relied on from the probe's checked-in file, because that file
    # is what a future edit would quietly change.
    definition["schemaLifecycle"]["strategy"] = "KeepExistingIfCompatible"
    definition["schemaLifecycle"]["allowDestructiveRecreate"] = False
    (staged / "db.definition.json").write_text(
        json.dumps(definition, indent=2) + "\n", encoding="utf-8")
    return staged


def _boot(root: Path, probe_input: Path, work: Path, port: int, boot_timeout: int,
          engine: str | None = None):
    output = work / "App"
    boot_log = work / "boot.log"
    _engine_proof.generate(root, probe_input, output, engine)
    jar = _engine_proof.build(output)
    app = _engine_proof.App(jar, output, port, boot_log)
    app.start(boot_timeout)
    return app


def vector_e1(root: Path, args, work: Path, results: list[dict]) -> None:
    """E1: adding a nullable column preserves existing rows."""
    probe = root / "NPDevSamples" / "probes" / "p2-evolve"
    database = f"npdev_p2_{args.engine}"
    marker = f"e1-{uuid.uuid4().hex[:8]}"

    staged_v1 = _prepare(root, probe / "v1" / "Input", args, database, work / "p2-v1")
    app = _boot(root, staged_v1, work / "p2-v1", args.port, args.boot_timeout, args.engine)
    try:
        status, _ = http("POST", f"{app.base()}/api/concepts/evolve_rows", {"label": marker})
        if status not in (200, 201):
            raise RuntimeError(f"v1 seed failed with {status}")
    finally:
        app.stop()

    # v2 adds a nullable `note`. The SAME database, a DIFFERENT model -- which is the whole vector:
    # boot-time schema realization must ALTER, not recreate.
    staged_v2 = _prepare(root, probe / "v2" / "Input", args, database, work / "p2-v2")
    app = _boot(root, staged_v2, work / "p2-v2", args.port, args.boot_timeout, args.engine)
    try:
        status, listed = http("GET", f"{app.base()}/api/concepts/evolve_rows?where=label:eq:{marker}")
        rows = (listed or {}).get("content") or []
        survived = len(rows) == 1
        has_note = survived and "note" in rows[0]
        results.append({
            "vector": "E1",
            "ok": bool(survived and has_note),
            "detail": (f"seeded 1 row under v1, found {len(rows)} after evolving to v2; the new "
                       f"nullable column is {'present' if has_note else 'ABSENT'} on the returned "
                       f"row. A recreate would have passed the column check and lost the row -- both "
                       f"halves are the vector."),
        })
    finally:
        app.stop()


def vector_e2(root: Path, args, work: Path, results: list[dict]) -> None:
    """E2: `renamedFrom` moves the data instead of dropping the column and adding a new one."""
    probe = root / "NPDevSamples" / "probes" / "p3-rename"
    database = f"npdev_p3_{args.engine}"
    marker = f"978-{uuid.uuid4().hex[:9]}"

    staged_v1 = _prepare(root, probe / "v1" / "Input", args, database, work / "p3-v1")
    app = _boot(root, staged_v1, work / "p3-v1", args.port, args.boot_timeout, args.engine)
    try:
        status, _ = http("POST", f"{app.base()}/api/concepts/books", {"isbn": marker})
        if status not in (200, 201):
            # The concept's table name is derived; if it is not `books` this vector cannot run, and
            # saying so beats reporting a rename failure that is really a wrong URL.
            raise RuntimeError(f"v1 seed failed with {status} -- check the realized table name")
    finally:
        app.stop()

    staged_v2 = _prepare(root, probe / "v2" / "Input", args, database, work / "p3-v2")
    app = _boot(root, staged_v2, work / "p3-v2", args.port, args.boot_timeout, args.engine)
    try:
        status, listed = http("GET", f"{app.base()}/api/concepts/books?where=isbn13:eq:{marker}")
        rows = (listed or {}).get("content") or []
        results.append({
            "vector": "E2",
            "ok": status == 200 and len(rows) == 1,
            "detail": (f"a row written as `isbn` under v1 is readable as `isbn13` under v2: "
                       f"{len(rows)} row(s), status {status}. A rename mis-handled as "
                       f"drop-plus-add would return zero -- and would have destroyed the column's "
                       f"data with no error anywhere."),
        })
    finally:
        app.stop()


def vectors_i2_i3(root: Path, args, work: Path, results: list[dict]) -> None:
    """I2 (nullability) and I3 (unique vs plain index), asserted through behaviour."""
    probe = root / "NPDevSamples" / "probes" / "p4-constraints"
    staged = _prepare(root, probe / "Input", args, f"npdev_p4_{args.engine}", work / "p4")
    app = _boot(root, staged, work / "p4", args.port, args.boot_timeout, args.engine)
    try:
        base = app.base()
        email = f"probe-{uuid.uuid4().hex[:8]}@example.test"
        region = f"region-{uuid.uuid4().hex[:6]}"

        # I2: `nickname` is declared not-required, so the realized column must accept its absence.
        status, _ = http("POST", f"{base}/api/concepts/accounts",
                         {"email": email, "region": region})
        results.append({
            "vector": "I2",
            "ok": status in (200, 201),
            "detail": (f"a row omitting the optional `nickname` was accepted with status {status}. "
                       f"A realized schema that made it NOT NULL would reject this -- the diff would "
                       f"then propose changes that are not needed, or miss ones that are."),
        })

        # I3a: `email` is unique -- a second row with the same value must be REFUSED.
        status_dup, _ = http("POST", f"{base}/api/concepts/accounts",
                             {"email": email, "region": region})
        # I3b: `region` carries a NON-unique index -- a second row sharing it must be ACCEPTED.
        status_shared, _ = http("POST", f"{base}/api/concepts/accounts",
                                {"email": f"other-{uuid.uuid4().hex[:8]}@example.test",
                                 "region": region})
        results.append({
            "vector": "I3",
            "ok": status_dup >= 400 and status_shared in (200, 201),
            "detail": (f"duplicate unique `email` -> {status_dup} (must be >=400); duplicate "
                       f"non-unique `region` -> {status_shared} (must be 2xx). Getting only the "
                       f"first half right would mean every index is unique; only the second, that "
                       f"none is. Both are silent until a user hits them."),
        })
    finally:
        app.stop()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engine", required=True)
    parser.add_argument("--port", type=int, default=18330)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=None)
    parser.add_argument("--db-user", default=None)
    parser.add_argument("--db-password", default=None)
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--report", default=None)
    parser.add_argument("--only", default=None,
                        help="Comma-separated vector ids to run (E1,E2,I2,I3). Default: all.")
    args = parser.parse_args(argv)

    root = _engine_proof._repo_root()
    build_root = Path(os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build"))
    work = build_root / "tier-c" / args.engine
    work.mkdir(parents=True, exist_ok=True)

    wanted = {v.strip().upper() for v in args.only.split(",")} if args.only else {"E1", "E2", "I2", "I3"}
    results: list[dict] = []
    failure: str | None = None
    try:
        if "E1" in wanted:
            vector_e1(root, args, work, results)
        if "E2" in wanted:
            vector_e2(root, args, work, results)
        if wanted & {"I2", "I3"}:
            vectors_i2_i3(root, args, work, results)
    except (SystemExit, RuntimeError) as exc:
        failure = str(exc)
        log(f"ABORTED: {failure}")

    ok = failure is None and all(result["ok"] for result in results)
    report = {
        "schemaVersion": "npdev-tier-c-report.v1",
        "engine": args.engine,
        "ok": ok,
        "failure": failure,
        "vectors": results,
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(f"\n### Tier C -- {args.engine}: {'PASS' if ok else 'FAIL'}\n\n")
            handle.write("| vector | result | detail |\n|---|---|---|\n")
            for result in results:
                handle.write(f"| {result['vector']} | "
                             f"{'pass' if result['ok'] else '**FAIL**'} | {result['detail']} |\n")
            if failure:
                handle.write(f"\n**Aborted:** {failure}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
