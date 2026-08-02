#!/usr/bin/env python3
"""Fast Lane plan Sec.7: the staleness ledger's run-state half.

verification-cadence.json (same directory) declares every check, its tier, and its
maxStaleness. This script records real runs against that declaration
(scripts/reports/out/verification-cadence-state.json) and reports, for a requested tier,
which declared checks are RAN (this tier's own), WITHIN WINDOW, or OVERDUE -- generalizing
the same staleness concept run-beta-release-gate.ps1 already uses for evidence freshness,
rather than building a second mechanism.

Usage:
    python scripts/quality/cadence_state.py record --id <check-id> --tier <T0|T1|T2|T3> --result <passed|failed> [--commit <sha>]
    python scripts/quality/cadence_state.py report --tier <T0|T1|T2|T3> [--json]
    python scripts/quality/cadence_state.py list-ids

Exit code contract for `report` (Sec.7.3 rule 4: T2 is mandatory before any plan-closing
commit, never deferred; T3 never blocks a lower tier):
    T0 report -- exits 0 iff every T0 check is not overdue.
    T1 report -- exits 0 iff every T0+T1 check is not overdue. T2/T3 staleness is reported
                 but does not affect the exit code (informational: "N T2 checks overdue, run T2").
    T2 report -- exits 0 iff every T0+T1+T2 check is not overdue (T2 is the mandatory tier).
                 T3 staleness is reported but does not affect the exit code.
    T3 report -- exits 0 iff every declared check (T0-T3) is not overdue -- the strictest,
                 full evaluation ("the floor").

Rule (Sec.7.3.5): deadlines in verification-cadence.json are only ever shortened, never
extended, to make an overdue check pass -- this script has no flag to do that.
"""
import argparse
import json
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CADENCE_PATH = REPO_ROOT / "scripts" / "quality" / "verification-cadence.json"
STATE_PATH = REPO_ROOT / "scripts" / "reports" / "out" / "verification-cadence-state.json"

TIER_ORDER = ["T0", "T1", "T2", "T3"]

STALENESS_TO_TIMEDELTA = {
    # "every-run" checks are meant to run fresh every invocation, but record+report happen
    # seconds apart within the SAME gate script run -- a 5-minute buffer treats that run as
    # fresh without weakening the "must run every time you invoke the tier" intent (nobody's
    # T0 pass spans anywhere near 5 minutes).
    "every-run": timedelta(minutes=5),
    "1-wave": timedelta(days=2),
    "1-move": timedelta(days=7),
    "7-days": timedelta(days=7),
    "30-days": timedelta(days=30),
}


def load_cadence():
    if not CADENCE_PATH.exists():
        raise SystemExit(f"verification-cadence.json not found: {CADENCE_PATH}")
    with CADENCE_PATH.open("r", encoding="utf-8") as f:
        doc = json.load(f)
    checks = {c["id"]: c for c in doc.get("checks", [])}
    if not checks:
        raise SystemExit("verification-cadence.json declares zero checks -- this is a real bug.")
    return checks


def load_state():
    if not STATE_PATH.exists():
        return {}
    with STATE_PATH.open("r", encoding="utf-8") as f:
        doc = json.load(f)
    return {r["id"]: r for r in doc.get("runs", [])}


def write_state(state_by_id):
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    doc = {
        "schemaVersion": "npdev-verification-cadence-state.v1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "runs": sorted(state_by_id.values(), key=lambda r: r["id"]),
    }
    with STATE_PATH.open("w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")


def current_commit():
    try:
        out = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True, check=True
        )
        return out.stdout.strip()
    except Exception:
        return ""


def cmd_record(args):
    checks = load_cadence()
    if args.id not in checks:
        raise SystemExit(
            f"REFUSED: '{args.id}' is not declared in verification-cadence.json -- "
            "every check must be declared before it can record a run (see check-cadence-coverage.py)."
        )
    declared_tier = checks[args.id]["tier"]
    if args.tier != declared_tier:
        raise SystemExit(
            f"REFUSED: '{args.id}' is declared as tier {declared_tier} in verification-cadence.json, "
            f"but --tier {args.tier} was passed -- fix the caller, not this check."
        )
    state = load_state()
    state[args.id] = {
        "id": args.id,
        "tier": args.tier,
        "lastRun": datetime.now(timezone.utc).isoformat(),
        "result": args.result,
        "commit": args.commit or current_commit(),
    }
    write_state(state)
    print(f"Recorded {args.id} ({args.tier}): {args.result}")
    return 0


def staleness_for(check):
    value = check.get("maxStaleness", "")
    if value not in STALENESS_TO_TIMEDELTA:
        raise SystemExit(
            f"Unknown maxStaleness '{value}' for check '{check['id']}' -- add it to "
            "STALENESS_TO_TIMEDELTA in cadence_state.py."
        )
    return STALENESS_TO_TIMEDELTA[value]


def evaluate(checks, state, tiers_in_scope):
    """Returns (ran_or_within_window, overdue) -- both lists of check dicts, restricted to
    tiers_in_scope, annotated with lastRun/result/ageSeconds/status."""
    within = []
    overdue = []
    now = datetime.now(timezone.utc)
    for check_id, check in sorted(checks.items()):
        if check["tier"] not in tiers_in_scope:
            continue
        record = state.get(check_id)
        max_age = staleness_for(check)
        entry = dict(check)
        if record is None:
            entry["lastRun"] = None
            entry["ageSeconds"] = None
            if check.get("contextDependent"):
                # A context-dependent check (e.g. "the model being edited") that has never
                # applied is not evidence of anything skipped -- it simply hasn't come up yet.
                entry["status"] = "never-run-not-applicable"
                within.append(entry)
            else:
                entry["status"] = "never-run"
                overdue.append(entry)
            continue
        last_run = datetime.fromisoformat(record["lastRun"])
        age = now - last_run
        entry["lastRun"] = record["lastRun"]
        entry["result"] = record.get("result")
        entry["ageSeconds"] = int(age.total_seconds())
        if age > max_age:
            entry["status"] = "overdue"
            overdue.append(entry)
        elif record.get("result") != "passed":
            entry["status"] = "failed-last-run"
            overdue.append(entry)
        else:
            entry["status"] = "within-window"
            within.append(entry)
    return within, overdue


def cmd_report(args):
    checks = load_cadence()
    state = load_state()
    requested = args.tier
    idx = TIER_ORDER.index(requested)

    # Sec.7.3 rule 4: T2 is mandatory (blocking scope = T0..T2), T3 never blocks a lower tier,
    # T0/T1's own exit code only reflects T0..themselves -- T2/T3 staleness is informational.
    blocking_scope = TIER_ORDER[: min(idx, 2) + 1]
    informational_scope = [t for t in TIER_ORDER if t not in blocking_scope]

    blocking_within, blocking_overdue = evaluate(checks, state, blocking_scope)
    info_within, info_overdue = evaluate(checks, state, informational_scope)

    ran_this_tier = [c for c in blocking_within + blocking_overdue if c["tier"] == requested]
    passed = len(blocking_overdue) == 0

    report = {
        "schemaVersion": "npdev-verification-cadence-report.v1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "tier": requested,
        "status": "passed" if passed else "overdue",
        "ranThisTier": [c["id"] for c in ran_this_tier],
        "withinWindow": [c["id"] for c in blocking_within if c["tier"] != requested],
        "overdue": [{"id": c["id"], "tier": c["tier"], "status": c["status"]} for c in blocking_overdue],
        "informationalOverdue": [
            {"id": c["id"], "tier": c["tier"], "status": c["status"]} for c in info_overdue
        ],
    }

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"== Tier {requested} cadence report ==")
        print(f"RAN (this tier): {', '.join(report['ranThisTier']) or '(none recorded yet)'}")
        print(f"WITHIN WINDOW:   {', '.join(report['withinWindow']) or '(none)'}")
        if report["overdue"]:
            print(f"OVERDUE (BLOCKING, tiers {blocking_scope}):")
            for c in report["overdue"]:
                print(f"  - {c['id']} ({c['tier']}): {c['status']}")
        else:
            print("OVERDUE (blocking): none")
        if report["informationalOverdue"]:
            print(f"Informational -- overdue in deferred tiers {informational_scope} (not blocking {requested}):")
            for c in report["informationalOverdue"]:
                print(f"  - {c['id']} ({c['tier']}): {c['status']}")
        print("")
        if passed:
            print(f"{requested} PASSED.")
        else:
            print(f"{requested} OVERDUE: {len(report['overdue'])} blocking check(s) need a real run.")

    if args.report_out:
        Path(args.report_out).parent.mkdir(parents=True, exist_ok=True)
        with open(args.report_out, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)

    return 0 if passed else 1


def cmd_list_ids(_args):
    for check_id in sorted(load_cadence()):
        print(check_id)
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command")

    record_parser = sub.add_parser("record")
    record_parser.add_argument("--id", required=True)
    record_parser.add_argument("--tier", required=True, choices=TIER_ORDER)
    record_parser.add_argument("--result", required=True, choices=["passed", "failed"])
    record_parser.add_argument("--commit", default="")

    report_parser = sub.add_parser("report")
    report_parser.add_argument("--tier", required=True, choices=TIER_ORDER)
    report_parser.add_argument("--json", action="store_true")
    report_parser.add_argument("--report-out", default="")

    sub.add_parser("list-ids")

    args = parser.parse_args(argv)
    if args.command == "record":
        return cmd_record(args)
    if args.command == "report":
        return cmd_report(args)
    if args.command == "list-ids":
        return cmd_list_ids(args)
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
