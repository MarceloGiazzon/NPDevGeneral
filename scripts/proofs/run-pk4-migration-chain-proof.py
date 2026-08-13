#!/usr/bin/env python3
"""PK-4 Stage C/D (PACK-6/PM3) real proof: a pack upgrade that SKIPS an intermediate version must
still replay that version's declared rename -- the guarantee the whole platform sells.

`renamechain@1.0.0 -> @3.0.0` directly, regenerating the SAME app straight from @1.0.0 to @3.0.0
with @2.0.0 NEVER generated in between. @2.0.0's chain entry renames `Doc.code -> Doc.docCode`;
@3.0.0 adds an unrelated `Doc.notes` field with an empty (auto-stamped) chain entry. A row seeded
through the generated app's own CRUD endpoint under @1.0.0 must survive, readable under the new
field name, after the in-place @3.0.0 regenerate.

Also runs the RED control the card's own proof section demands: delete the `1.0.0 -> 2.0.0` chain
entry from the @3.0.0 pack and regenerate -- must REFUSE naming the gap, not silently drop the
column and destroy the row's data. Both the success path and the RED control regenerate an app
that already has a real, TRACKED `npdev.lock` (migratedVersion=1.0.0) from its own prior @1.0.0
generate -- exactly `PackMigrationChainResolutionTest`'s own already-proven scope, not the
untracked-fallback path (`PackMigrationChain.earliestFromVersion`), which is a narrower, separately
tracked hardening item (see REG filed alongside this proof) for a chain whose OWN earliest hop goes
missing on a never-before-tracked app -- a real gap, but a different one than this card's headline
multi-hop-skip guarantee.

TWO REAL BUGS THIS SCRIPT ITSELF EXISTS BECAUSE OF, both found by running this for real rather than
assuming the harness would behave like run-tier-c-probes.py's E1/E2 vectors:
  1. H2Local's JDBC URL is `jdbc:h2:file:./data/...`, relative to the booted JVM's own working
     directory (the app's own output root) -- generating v1 and v3 into two DIFFERENT output
     directories (the pattern E1/E2 themselves use) produces two DIFFERENT physical database files,
     so nothing could ever survive a regenerate. This script always regenerates the SAME app in the
     SAME output directory, matching how a real user actually performs an upgrade.
  2. An app that has never been tracked in npdev.lock needs an explicit baseline written before the
     interesting regenerate, or it exercises the (separately tracked) untracked-fallback path instead
     of the tracked scenario this proof is actually about.

USAGE
    python scripts/proofs/run-pk4-migration-chain-proof.py --engine h2local
    python scripts/proofs/run-pk4-migration-chain-proof.py --engine postgres \
        --db-host 127.0.0.1 --db-port 5432 --db-user npdev --db-password npdev

Exit 0 = both the success path and the RED control behaved correctly. Exit 1 = a real failure.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import uuid
from pathlib import Path

_ENGINE_PROOF = Path(__file__).resolve().parent.parent / "quality" / "run-engine-app-proof.py"
_spec = importlib.util.spec_from_file_location("npdev_engine_app_proof", _ENGINE_PROOF)
_engine_proof = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_engine_proof)

http = _engine_proof.http
log = _engine_proof.log

_ENDPOINT = "/api/renamechain_docs"


def _rows_of(listed) -> list:
    """The generated list endpoint returns either a bare JSON array or a `{"content": [...]}`
    Page-shaped envelope depending on the concept's own pagination configuration -- handle both
    rather than assuming one, since guessing wrong here would silently under-count real proof
    evidence rather than fail loudly.
    """
    if isinstance(listed, list):
        return listed
    if isinstance(listed, dict):
        return listed.get("content") or []
    return []


def _prepare(root: Path, probe_input: Path, args, work: Path, database_name: str) -> Path:
    """Stage the probe and write an engine db.definition.json set to preserve data across a
    regenerate -- the same override run-tier-c-probes.py's own E1/E2 vectors apply, for the same
    reason: this proof measures PRESERVATION, so a recreate strategy would make it pass by having
    nothing to preserve.

    @param database_name unique PER PHASE (success-path vs red-control), not just per work
        directory. H2Local is file-based and already isolated by output directory alone, but a
        server engine (Postgres/MySQL/SQL Server) is a SHARED resource keyed only by database name
        -- two independent phases sharing one name would have the red control's own v1 step boot
        against a database the success path had already migrated all the way to v3, which is
        exactly what happened the first time this script ran against Postgres.
    """
    sys.path.insert(0, str(root / "NPDevCli"))
    import npdev_engines  # noqa: PLC0415

    staged = _engine_proof.stage_input(probe_input, work)
    definition = npdev_engines.db_definition_for(
        args.engine, database_name=database_name, host=args.db_host, port=args.db_port,
        username=args.db_user, password=args.db_password)
    definition["schemaLifecycle"]["strategy"] = "KeepExistingIfCompatible"
    definition["schemaLifecycle"]["allowDestructiveRecreate"] = False
    (staged / "db.definition.json").write_text(
        json.dumps(definition, indent=2) + "\n", encoding="utf-8")
    return staged


def _write_tracked_lock(staged_input: Path, migrated_version: str) -> None:
    """A real npdev.lock reflecting `renamechain` already having a genuine prior generate history at
    `migrated_version` -- what a real sequence of `npdev generate` calls would have produced, had
    v1's own pack.json carried a migrations key. resolvedVersion/digest/sourcePath are never
    validated for this app (checkLock's own staleness enforcement only runs when a resolved pack has
    a TRANSITIVE dependency of its own, which renamechain never does) -- only migratedVersion is
    actually consumed by PackDependencyGraphWalker.applyMigrationChains, so a placeholder digest is
    correct here, not a shortcut.
    """
    lock = {
        "schemaVersion": "npdev-lock.v1",
        "packs": {
            "renamechain": {
                "resolvedVersion": migrated_version,
                "digest": "sha256:" + ("0" * 64),
                "sourcePath": "pack.json",
                "migratedVersion": migrated_version,
            }
        },
    }
    (staged_input / "npdev.lock").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")


def _generate_build_boot(root: Path, staged_input: Path, output: Path, args, work: Path, label: str):
    _engine_proof.generate(root, staged_input, output, args.engine)
    jar = _engine_proof.build(output)
    boot_log = work / f"{label}.boot.log"
    app = _engine_proof.App(jar, output, args.port, boot_log)
    app.start(args.boot_timeout)
    return app


def run_success_path(root: Path, args, work: Path) -> dict:
    probe = root / "NPDevSamples" / "probes" / "p5-migration-chain"
    marker = f"code-{uuid.uuid4().hex[:10]}"
    app_output = work / "App"  # SAME output directory for both generates -- see module docstring #1.

    staged_v1 = _prepare(root, probe / "v1" / "Input", args, work / "stage-v1", "p5_migration_chain_success")
    app = _generate_build_boot(root, staged_v1, app_output, args, work, "v1")
    try:
        status, _ = http("POST", f"{app.base()}{_ENDPOINT}", {"code": marker})
        if status not in (200, 201):
            raise SystemExit(f"v1 seed failed with status {status} -- check the realized endpoint "
                              f"({_ENDPOINT}) or field name (code)")
    finally:
        app.stop()

    # The headline scenario: generate @3.0.0 DIRECTLY from @1.0.0. @2.0.0 is never generated at all.
    # A real tracked baseline (migratedVersion=1.0.0) is written first -- see module docstring #2.
    staged_v3 = _prepare(root, probe / "v3" / "Input", args, work / "stage-v3", "p5_migration_chain_success")
    _write_tracked_lock(staged_v3, "1.0.0")
    app = _generate_build_boot(root, staged_v3, app_output, args, work, "v3")
    try:
        status, listed = http("GET", f"{app.base()}{_ENDPOINT}?where=docCode:eq:{marker}")
        rows = _rows_of(listed)
        return {
            "phase": "success-path",
            "ok": status == 200 and len(rows) == 1,
            "detail": (f"a row written as `code` under renamechain@1.0.0 is readable as `docCode` "
                       f"after regenerating DIRECTLY to @3.0.0 (never generating @2.0.0): "
                       f"{len(rows)} row(s), status {status}. A skipped-hop bug would return zero "
                       f"here -- and would have destroyed the column's data with no error anywhere."),
        }
    finally:
        app.stop()


def run_red_control(root: Path, args, work: Path) -> dict:
    """Same tracked-baseline scenario as the success path, but the 1.0.0 -> 2.0.0 chain entry is
    deleted from the v3 pack before the second regenerate. Must refuse, not silently drop.
    """
    probe = root / "NPDevSamples" / "probes" / "p5-migration-chain"
    marker = f"code-{uuid.uuid4().hex[:10]}"
    app_output = work / "App"

    staged_v1 = _prepare(root, probe / "v1" / "Input", args, work / "stage-v1", "p5_migration_chain_red")
    app = _generate_build_boot(root, staged_v1, app_output, args, work, "v1")
    try:
        status, _ = http("POST", f"{app.base()}{_ENDPOINT}", {"code": marker})
        if status not in (200, 201):
            raise SystemExit(f"v1 seed failed with status {status} in the RED control setup")
    finally:
        app.stop()

    staged_v3 = _prepare(root, probe / "v3" / "Input", args, work / "stage-v3", "p5_migration_chain_red")
    _write_tracked_lock(staged_v3, "1.0.0")
    pack_path = staged_v3 / "pack.json"
    pack = json.loads(pack_path.read_text(encoding="utf-8"))
    if "1.0.0 -> 2.0.0" not in pack.get("migrations", {}):
        raise SystemExit("RED control setup error: the staged v3 pack.json has no "
                          "'1.0.0 -> 2.0.0' entry to delete -- fixture drifted from what this "
                          "script expects")
    del pack["migrations"]["1.0.0 -> 2.0.0"]
    pack_path.write_text(json.dumps(pack, indent=2) + "\n", encoding="utf-8")

    # A local, output-CAPTURING generate call rather than _engine_proof.generate -- that helper lets
    # the subprocess's stdout/stderr flow straight to the terminal and only raises a generic
    # "generation failed (exit N)" SystemExit, which is enough to prove a refusal happened but not
    # enough to prove it refused for the RIGHT reason (a missing hop) rather than an unrelated crash.
    completed = subprocess.run(
        [sys.executable, str(root / "NPDevCli" / "npdev_cli.py"), "generate", "app",
         "--model", str(staged_v3 / "model.json"),
         "--config", str(staged_v3 / "config.json"),
         "--output", str(app_output),
         "--require-db-definition"],
        cwd=str(root), check=False, capture_output=True, text=True)
    if completed.returncode == 0:
        return {
            "phase": "red-control",
            "ok": False,
            "detail": "generate SUCCEEDED with the 1.0.0 -> 2.0.0 hop missing -- the gap was NOT "
                      "caught. This is exactly the destructive failure this card exists to prevent.",
        }
    combined_output = completed.stdout + completed.stderr
    names_the_gap = "no migration chain entry starts at version 1.0.0" in combined_output
    return {
        "phase": "red-control",
        "ok": names_the_gap,
        "detail": (f"generate refused naming the exact missing hop (exit {completed.returncode})"
                   if names_the_gap else
                   f"generate refused (exit {completed.returncode}), but NOT for the expected reason "
                   f"(missing-hop refusal naming version 1.0.0) -- output tail: "
                   f"{combined_output[-800:]}"),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engine", required=True)
    parser.add_argument("--port", type=int, default=18340)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=None)
    parser.add_argument("--db-user", default=None)
    parser.add_argument("--db-password", default=None)
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--report", default=None)
    parser.add_argument("--only", default=None,
                        help="Comma-separated phases to run (success-path,red-control). Default: both.")
    args = parser.parse_args(argv)

    root = _engine_proof._repo_root()
    build_root = Path(os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build"))
    wanted = {p.strip() for p in args.only.split(",")} if args.only else {"success-path", "red-control"}

    results: list[dict] = []
    failure: str | None = None
    try:
        if "success-path" in wanted:
            work = build_root / "pk4-migration-proof" / args.engine / "success"
            work.mkdir(parents=True, exist_ok=True)
            results.append(run_success_path(root, args, work))
        if "red-control" in wanted:
            work = build_root / "pk4-migration-proof" / args.engine / "red"
            work.mkdir(parents=True, exist_ok=True)
            results.append(run_red_control(root, args, work))
    except SystemExit as exc:
        failure = str(exc)
        log(f"ABORTED: {failure}")

    ok = failure is None and all(r["ok"] for r in results)
    report = {
        "schemaVersion": "npdev-pk4-migration-proof.v1",
        "engine": args.engine,
        "ok": ok,
        "failure": failure,
        "results": results,
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
