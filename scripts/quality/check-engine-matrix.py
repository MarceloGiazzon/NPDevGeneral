#!/usr/bin/env python3
"""ENGM-1: every engine support claim is tied to a CI job that witnesses it.

"Supported" is cheap to write and expensive to be wrong about (storage-dialect-conformance.yml: "Never
claim MySQL support from a green local run alone"). scripts/policy/engine-capability-matrix.json is the
machine-readable claim: engines x capabilities, each cell {claim, witnessJob, note}. THIS checker is the
static half and needs no Docker, no app and no network:

  * the file must carry exactly the engines and capabilities listed below -- a dropped row or column
    is a finding, not a smaller matrix;
  * a cell has exactly the keys claim / witnessJob / note, and claim is supported | partial | unsupported;
  * supported / partial REQUIRE a witnessJob ("<workflow file>#<job id>"); unsupported must NOT carry one
    (a witness on an "unsupported" cell is a contradiction -- either the claim is stale or the witness is);
  * the witness must be REAL: the workflow file exists under .github/workflows/ and the job id is a key of
    its `jobs:` mapping (parsed with PyYAML, not grepped);
  * if the witness job fans out over `strategy.matrix.engine`, the cell's engine must be one of the legs
    (a Postgres claim cannot be witnessed by a job that only runs mysql and sqlserver).

It cannot prove the job still exercises the capability -- that is why the note says what the job covers and
the matrix is reviewed when a workflow changes. It does guarantee a claim never stands on a job that does
not exist.

    python check-engine-matrix.py
    python check-engine-matrix.py --matrix <path>   # check another copy (used to prove each rule can fail)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
MATRIX = REPO_ROOT / "scripts" / "policy" / "engine-capability-matrix.json"
WORKFLOWS = REPO_ROOT / ".github" / "workflows"

ENGINES = ["h2", "postgres", "mysql", "sqlserver"]
CAPABILITIES = [
    "boot",
    "additive_migration",
    "destructive_migration_ack",
    "promotion_target",
    "backup",
    "restore_drill",
    "pagination",
    "json_search",
    "flow_durability",
]
CLAIMS = {"supported", "partial", "unsupported"}
CELL_KEYS = {"claim", "witnessJob", "note"}


def load_jobs(workflow_file: str, cache: dict[str, dict | None]) -> dict | None:
    """The `jobs:` mapping of one workflow file, or None when the file is missing or unparseable."""
    if workflow_file in cache:
        return cache[workflow_file]
    path = WORKFLOWS / workflow_file
    jobs = None
    if path.is_file() and "/" not in workflow_file and "\\" not in workflow_file:
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
            if isinstance(doc, dict) and isinstance(doc.get("jobs"), dict):
                jobs = doc["jobs"]
        except yaml.YAMLError:
            jobs = None
    cache[workflow_file] = jobs
    return jobs


def matrix_engines(job: dict) -> list[str] | None:
    """The `strategy.matrix.engine` list of a job, or None when the job has no engine fan-out."""
    matrix = ((job or {}).get("strategy") or {}).get("matrix")
    if isinstance(matrix, dict) and isinstance(matrix.get("engine"), list):
        return [str(engine) for engine in matrix["engine"]]
    return None


def check_cell(engine: str, capability: str, cell, jobs_cache: dict, problems: list[str]) -> str | None:
    """Validate one cell; returns its claim when well-formed, else None."""
    where = f"{engine}.{capability}"
    if not isinstance(cell, dict):
        problems.append(f"{where}: cell is not an object")
        return None
    unknown = sorted(set(cell) - CELL_KEYS)
    missing = sorted(CELL_KEYS - set(cell))
    if unknown:
        problems.append(f"{where}: unknown cell key(s) {unknown} (allowed: {sorted(CELL_KEYS)})")
    if missing:
        problems.append(f"{where}: cell is missing key(s) {missing}")
    claim = cell.get("claim")
    witness = cell.get("witnessJob")
    if claim not in CLAIMS:
        problems.append(f"{where}: claim {claim!r} is not one of {sorted(CLAIMS)}")
        return None
    if not str(cell.get("note") or "").strip():
        problems.append(f"{where}: note is empty -- say honestly what the witness covers (or why there is none)")

    if claim == "unsupported":
        if witness is not None:
            problems.append(f"{where}: claims unsupported yet names witnessJob {witness!r} -- contradiction "
                            f"(drop the witness, or upgrade the claim)")
        return claim

    if not isinstance(witness, str) or not witness.strip():
        problems.append(f"{where}: claims {claim} with no witnessJob -- a support claim needs a CI job that proves it")
        return claim
    if witness.count("#") != 1 or not all(part.strip() for part in witness.split("#")):
        problems.append(f"{where}: witnessJob {witness!r} is not '<workflow file>#<job id>'")
        return claim
    workflow_file, job_id = (part.strip() for part in witness.split("#"))
    jobs = load_jobs(workflow_file, jobs_cache)
    if jobs is None:
        problems.append(f"{where}: witnessJob {witness!r}: workflow file .github/workflows/{workflow_file} "
                        f"does not exist or has no `jobs:` mapping")
        return claim
    if job_id not in jobs:
        problems.append(f"{where}: witnessJob {witness!r}: no job {job_id!r} in {workflow_file} "
                        f"(jobs: {sorted(jobs)})")
        return claim
    legs = matrix_engines(jobs[job_id])
    if legs is not None and engine not in legs:
        problems.append(f"{where}: witnessJob {witness!r} fans out over engines {legs} -- {engine} is not a leg, "
                        f"so this job cannot witness it")
    return claim


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--matrix", type=Path, default=MATRIX, help="matrix JSON to check (default: the repo file)")
    args = parser.parse_args()

    doc = json.loads(args.matrix.read_text(encoding="utf-8"))
    problems: list[str] = []

    declared = doc.get("capabilities")
    if declared != CAPABILITIES:
        problems.append(f"top-level `capabilities` is {declared!r}, expected exactly {CAPABILITIES}")

    engines = doc.get("engines")
    if not isinstance(engines, dict):
        problems.append("`engines` is missing or not an object")
        engines = {}
    for engine in ENGINES:
        if engine not in engines:
            problems.append(f"engine {engine!r} is missing from the matrix")
    for engine in sorted(set(engines) - set(ENGINES)):
        problems.append(f"engine {engine!r} is not a tracked engine {ENGINES}")

    jobs_cache: dict = {}
    tally = {"supported": 0, "partial": 0, "unsupported": 0}
    for engine in ENGINES:
        row = engines.get(engine)
        if not isinstance(row, dict):
            continue
        for capability in CAPABILITIES:
            if capability not in row:
                problems.append(f"{engine}.{capability}: capability is missing from the engine row")
        for capability in sorted(set(row) - set(CAPABILITIES)):
            problems.append(f"{engine}.{capability}: not a tracked capability {CAPABILITIES}")
        for capability in CAPABILITIES:
            if capability in row:
                claim = check_cell(engine, capability, row[capability], jobs_cache, problems)
                if claim in tally:
                    tally[claim] += 1

    total = sum(tally.values())
    print(f"{len(ENGINES)} engines x {len(CAPABILITIES)} capabilities = {total} cell(s): "
          f"{tally['supported']} supported, {tally['partial']} partial, {tally['unsupported']} unsupported; "
          f"{len(jobs_cache)} workflow file(s) parsed")
    for problem in problems:
        print(f"FAIL  {problem}")
    if problems:
        print(f"{len(problems)} blocking finding(s).")
        return 1
    print("0 blocking finding(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
