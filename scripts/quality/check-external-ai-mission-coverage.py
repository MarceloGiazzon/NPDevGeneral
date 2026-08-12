#!/usr/bin/env python3
"""ADR-0009 / P8: does every external-AI mission have a run record, and does a RUN record's
verdict still look trustworthy against its backing pack evidence?

WHY THIS EXISTS
---------------
Extracted from check-register-consistency.py by md-zero-2026-08-11 PLAN.md Phase 2. That script
was deleted (it guarded four closed-programme markdown ledgers, all 177 items DONE, and this
plan's rule is "no script may read a .md file"), but two of its functions never read markdown at
all -- they read scripts/external-review/missions.json and docs/external-ai-review/runs/*.json,
both JSON, and run-external-ai-gate.ps1 depends on them independently of the deleted checks. They
move here verbatim rather than being deleted with the rest of the file.

WHAT IT CHECKS (two things)
----------------------------
1. mission_run_coverage_gaps: every mission in missions.json has a run record (RUN, or an
   explicit NOT_RUN + reason) -- a mission with neither is indistinguishable from a mission
   nobody remembered, which is exactly how REG-16 sat at zero adversarial review long after Tier
   A and B were done. "Never checked" must never look the same as "checked, and here is why it
   was skipped."
2. provenance_audit_gaps: defence-in-depth BEHIND the build-time refusal build-review-pack.py's
   resolve_provenance() already enforces. Re-audits EXISTING run records against their backing
   pack file, when that evidence still happens to be on disk (packs are evidence, kept OUTSIDE
   the repo, not guaranteed to survive indefinitely or be present on every checkout). A run
   record whose pack file is not found locally is never flagged -- absence is not proof of
   anything wrong.

USAGE
-----
    python scripts/quality/check-external-ai-mission-coverage.py
    python scripts/quality/check-external-ai-mission-coverage.py --root <repo root>
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def mission_run_coverage_gaps(root: Path) -> list[str]:
    """ADR-0009 / P8: does every external-AI mission have a run record -- RUN or an explicit
    NOT_RUN reason?

    Same blind-spot shape every other check in this file exists to catch, one programme over: a
    mission with neither a run record nor a stated reason it hasn't run is indistinguishable from a
    mission nobody remembered, which is exactly how REG-16 sat at zero adversarial review long after
    Tier A and B were done. "Never checked" must never look the same as "checked, and here is why it
    was skipped." Silently absent (no file at all) is a gap here for that reason, not silence.

    A checkout without the external-AI review feature (missions.json absent) has nothing to check --
    this returns no gaps rather than erroring.
    """
    missions_file = root / "scripts" / "external-review" / "missions.json"
    if not missions_file.exists():
        return []
    runs_dir = root / "docs" / "external-ai-review" / "runs"

    missions = json.loads(missions_file.read_text(encoding="utf-8"))["missions"]
    gaps: list[str] = []
    for mission in missions:
        mission_id = mission["missionId"]
        run_file = runs_dir / f"{mission_id}.json"
        if not run_file.exists():
            gaps.append(
                f"mission {mission_id} (scripts/external-review/missions.json) has no run record at "
                f"docs/external-ai-review/runs/{mission_id}.json -- add one with runStatus RUN "
                f"(+ packManifestSha256 + verdictRecordKind/recordKind) or NOT_RUN (+ notRunReason)."
            )
            continue
        try:
            record = json.loads(run_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            gaps.append(f"docs/external-ai-review/runs/{mission_id}.json is not valid JSON: {exc}")
            continue
        status = record.get("runStatus")
        if status == "RUN":
            has_record_kind = record.get("verdictRecordKind") or record.get("recordKind")
            if not record.get("packManifestSha256") or not has_record_kind:
                gaps.append(
                    f"docs/external-ai-review/runs/{mission_id}.json says RUN but is missing "
                    f"packManifestSha256 or verdictRecordKind/recordKind -- see external-ai-run.schema.json."
                )
        elif status == "NOT_RUN":
            if not str(record.get("notRunReason", "")).strip():
                gaps.append(
                    f"docs/external-ai-review/runs/{mission_id}.json says NOT_RUN but notRunReason "
                    f"is blank -- state why, even if the reason is 'blocked on D3'."
                )
        else:
            gaps.append(
                f"docs/external-ai-review/runs/{mission_id}.json has runStatus '{status}', expected "
                f"RUN or NOT_RUN -- there is no third, silent option."
            )
    return gaps


def provenance_audit_gaps(root: Path) -> list[str]:
    """ADR-0009 / REG-51 residual: defence-in-depth BEHIND the build-time refusal
    build-review-pack.py's resolve_provenance() already enforces.

    That refusal stops a NEW pack from ever being built against stale generated-app output -- the
    exact class of false positive REG-49 turned out to be. This instead re-audits EXISTING run
    records (docs/external-ai-review/runs/*.json, tracked in the repo) against their backing pack
    file, when that evidence still happens to be on disk: packs are evidence, kept OUTSIDE the repo
    at <repo>__OutsideRepo/external-ai-review/packs/<missionId>/<packManifestSha256>.json, not
    guaranteed to survive indefinitely or be present on every checkout.

    A run record whose pack file is NOT found locally is never flagged: an absent file is not proof
    of anything wrong, and treating it as one would manufacture exactly the false-positive class
    this project's own lesson #4 warns against ("a gate that cries wolf gets bypassed"). Only a pack
    that IS found and reads source.stale: true, or is source.kind: "generated-app" without
    provenanceVerified: true, is a real finding: the run record's own verdict may be untrustworthy.
    """
    runs_dir = root / "docs" / "external-ai-review" / "runs"
    if not runs_dir.is_dir():
        return []
    packs_dir = root.parent / f"{root.name}__OutsideRepo" / "external-ai-review" / "packs"
    gaps: list[str] = []
    for run_file in sorted(runs_dir.glob("*.json")):
        try:
            record = json.loads(run_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue  # already reported by mission_run_coverage_gaps
        if record.get("runStatus") != "RUN":
            continue
        # Same discipline as mission_run_coverage_gaps' notRunReason: a limitation is not a gap once
        # it is disclosed in the TRACKED record itself, not only in external (not-guaranteed-present)
        # pack evidence -- that disclosure is the actual fix for the blind spot (info sitting in one
        # place, checked in another). Simple substring match, not a new enum: this mirrors the
        # existing note field's own free-text convention rather than inventing a stricter one.
        if "provenance" in str(record.get("note", "")).lower():
            continue
        mission_id = record.get("missionId", run_file.stem)
        pack_hash = record.get("packManifestSha256")
        if not pack_hash:
            continue
        pack_file = packs_dir / mission_id / f"{pack_hash}.json"
        if not pack_file.is_file():
            continue  # evidence not available on this checkout -- not a finding
        try:
            pack = json.loads(pack_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            gaps.append(f"docs/external-ai-review/runs/{run_file.name}: backing pack "
                        f"{pack_file} is not valid JSON: {exc}")
            continue
        source = pack.get("source", {})
        if source.get("stale"):
            gaps.append(
                f"docs/external-ai-review/runs/{run_file.name}: backing pack ({pack_file.name}) is "
                f"marked source.stale=true -- this run's verdict was produced from generated code "
                f"that predates a relevant template fix (the REG-49 false-positive class). Re-run "
                f"the mission against freshly generated output before trusting this record."
            )
        elif source.get("kind") == "generated-app" and not source.get("provenanceVerified"):
            gaps.append(
                f"docs/external-ai-review/runs/{run_file.name}: backing pack ({pack_file.name}) is "
                f"source.kind=generated-app but provenanceVerified is not true -- provenance was "
                f"never actually checked for this run."
            )
    return gaps


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    gaps = mission_run_coverage_gaps(root) + provenance_audit_gaps(root)

    print("External-AI mission coverage (run records + provenance audit)")
    if gaps:
        print(f"\nFAIL: {len(gaps)} gap(s):\n", file=sys.stderr)
        for gap in gaps:
            print(f"  - {gap}", file=sys.stderr)
        return 1
    print("OK: every mission has a run record, and no RUN record's backing pack is stale/unverified.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
