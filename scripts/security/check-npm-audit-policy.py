#!/usr/bin/env python3
"""npm audit gate for this repo's npm projects, enforcing scripts/policy/frontend-npm-audit-policy.json.

TARGET, and why it changed
--------------------------
The original and only target was NPDevEditor/ui-react. That module was parked out of the repo
(see BREAKING.md), so the default is now scripts/quality/json-schema-validator -- the remaining
npm project that carries a committed package-lock.json. The policy itself is about severity
floors, not about which project is being audited, so it is unchanged.

WHY THIS EXISTS
---------------
R2 (MASTER-ROADMAP.md Step 9 / ledger SEC-2). scripts/policy/frontend-npm-audit-policy.json has
existed with zero consumers anywhere in the repo -- confirmed by a repo-wide grep for its own
filename, which found exactly one hit, and that hit was this card's own finding recorded in
ledger/items/SEC-2.yml, not a script or workflow reading it. This script is the first thing that
actually reads it: `npm audit`'s raw JSON on its own has no notion of this repo's risk tolerance
(should a moderate-severity vulnerability in a devDependency block CI? almost never; should ANY
vulnerability in a production dependency? almost always) -- that policy has to live somewhere, and
frontend-npm-audit-policy.json is where it was always meant to live.

POLICY SHAPE (scripts/policy/frontend-npm-audit-policy.json)
--------------------------------------------------------------
    packageManager: "npm"            -- informational; this script only supports npm.
    packageLockOnly: true            -- `npm audit` is run with --package-lock-only (no re-resolve).
    rules.failOnAnyProdVulnerability -- true: ANY vulnerability (any severity) in a PRODUCTION
                                        dependency fails the gate. Production vs. dev is determined
                                        by diffing a full audit against an --omit=dev audit (npm's
                                        JSON output does not otherwise label a finding prod/dev).
    rules.failOnSeverityAtOrAbove    -- a blanket floor (e.g. "high"): ANY vulnerability (prod OR
                                        dev) at or above this severity fails the gate, regardless of
                                        allowedDevPackages. This is intentionally NOT allowlistable.
    rules.requireAllowlistForDevSeverities
                                      -- severities (e.g. ["moderate"]) that are tolerated in a DEV
                                        dependency ONLY if the package name is listed in
                                        allowedDevPackages; otherwise they fail too. Severities named
                                        neither here nor covered by failOnSeverityAtOrAbove (e.g. an
                                        unlisted "low") pass silently -- this policy does not attempt
                                        to gate every possible finding, only the ones explicitly named.
    allowedDevPackages               -- package names exempted from the requireAllowlistForDevSeverities
                                        rule. Empty today (SEC-2's own finding); add a name only after
                                        reviewing the specific advisory, never pre-clear one.

Usage:
    python scripts/security/check-npm-audit-policy.py [--project-dir NPDevEditor/ui-react]
    python scripts/security/check-npm-audit-policy.py --audit-json <full-audit.json> --audit-json-prod <prod-only-audit.json>
    python scripts/security/check-npm-audit-policy.py --calibrate

Exit 0 = no policy violation. Exit 1 = at least one violation. Exit 2 = usage / policy file error.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

POLICY_PATH = "scripts/policy/frontend-npm-audit-policy.json"

SEVERITY_ORDER = ["info", "low", "moderate", "high", "critical"]


def _repo_root(explicit: str | None) -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144)."""
    if explicit:
        return Path(explicit).resolve()
    here = Path(__file__).resolve()
    for candidate in [here, *here.parents]:
        if (
            (candidate / "NPDevContract").is_dir()
            and (candidate / "NPDevGenerator").is_dir()
            and (candidate / "NPDevKernel").is_dir()
        ):
            return candidate
    # scripts/security/check-npm-audit-policy.py -> scripts/security -> scripts -> repo root
    return here.parents[2]


def load_policy(repo_root: Path) -> dict:
    path = repo_root / POLICY_PATH
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def run_npm_audit(project_dir: Path, extra_args: list[str]) -> dict:
    """Runs `npm audit --json --package-lock-only [extra_args]` in project_dir.

    npm audit exits non-zero when it FINDS vulnerabilities -- that is not a usage error, so exit
    code is deliberately ignored here; only whether it produced parseable JSON matters.
    """
    result = subprocess.run(
        ["npm", "audit", "--json", "--package-lock-only", *extra_args],
        cwd=project_dir,
        capture_output=True,
        text=True,
        shell=(sys.platform == "win32"),
    )
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(
            f"npm audit did not produce parseable JSON (exit {result.returncode}): {exc}\n"
            f"stdout: {result.stdout[:2000]}\nstderr: {result.stderr[:2000]}"
        ) from exc


def extract_vulnerabilities(audit_json: dict) -> dict[str, str]:
    """Returns {package_name: highest_severity} from npm 7+'s `vulnerabilities` object shape."""
    out: dict[str, str] = {}
    for name, entry in (audit_json.get("vulnerabilities") or {}).items():
        severity = str(entry.get("severity", "low")).lower()
        out[name] = severity
    return out


def evaluate(
    policy: dict, full_vulns: dict[str, str], prod_vulns: dict[str, str]
) -> list[str]:
    rules = policy.get("rules", {})
    fail_on_any_prod = bool(rules.get("failOnAnyProdVulnerability", False))
    severity_floor = str(rules.get("failOnSeverityAtOrAbove", "critical")).lower()
    allowlisted_dev_severities = {
        str(s).lower() for s in rules.get("requireAllowlistForDevSeverities", [])
    }
    allowed_dev_packages = set(policy.get("allowedDevPackages", []))

    def at_or_above_floor(severity: str) -> bool:
        try:
            return SEVERITY_ORDER.index(severity) >= SEVERITY_ORDER.index(severity_floor)
        except ValueError:
            # An unrecognized severity string is treated conservatively as meeting the floor.
            return True

    dev_only = {name: sev for name, sev in full_vulns.items() if name not in prod_vulns}

    violations: list[str] = []

    if fail_on_any_prod:
        for name, severity in sorted(prod_vulns.items()):
            violations.append(
                f"production dependency '{name}' has a {severity} vulnerability "
                f"(rules.failOnAnyProdVulnerability=true tolerates none)"
            )

    for name, severity in sorted(dev_only.items()):
        if at_or_above_floor(severity):
            violations.append(
                f"dev dependency '{name}' has a {severity} vulnerability, at/above the "
                f"{severity_floor} floor (rules.failOnSeverityAtOrAbove, not allowlistable)"
            )
        elif severity in allowlisted_dev_severities and name not in allowed_dev_packages:
            violations.append(
                f"dev dependency '{name}' has a {severity} vulnerability and is not in "
                f"allowedDevPackages (rules.requireAllowlistForDevSeverities includes '{severity}')"
            )

    return violations


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--repo-root", default=None, help="Override repo root (mainly for testing).")
    parser.add_argument(
        "--project-dir",
        default="scripts/quality/json-schema-validator",
        help="Directory containing package-lock.json (repo-relative unless absolute).",
    )
    parser.add_argument(
        "--audit-json",
        default=None,
        help="Skip running npm; read a full (dev+prod) `npm audit --json` result from this file.",
    )
    parser.add_argument(
        "--audit-json-prod",
        default=None,
        help="Skip running npm; read a prod-only (`--omit=dev`) `npm audit --json` result from this file.",
    )
    parser.add_argument(
        "--calibrate",
        action="store_true",
        help="Self-test: verify each rule's pass/fail branch is reachable against synthetic fixtures.",
    )
    args = parser.parse_args(argv)

    if args.calibrate:
        return run_calibration()

    repo_root = _repo_root(args.repo_root)
    policy_path = repo_root / POLICY_PATH
    if not policy_path.is_file():
        print(f"FAIL: missing {POLICY_PATH}", file=sys.stderr)
        return 2
    try:
        policy = load_policy(repo_root)
    except json.JSONDecodeError as exc:
        print(f"FAIL: {POLICY_PATH} is not valid JSON: {exc}", file=sys.stderr)
        return 2

    try:
        if args.audit_json:
            full_audit = json.loads(Path(args.audit_json).read_text(encoding="utf-8"))
        else:
            project_dir = Path(args.project_dir)
            if not project_dir.is_absolute():
                project_dir = repo_root / project_dir
            full_audit = run_npm_audit(project_dir, [])

        if args.audit_json_prod:
            prod_audit = json.loads(Path(args.audit_json_prod).read_text(encoding="utf-8"))
        elif args.audit_json:
            # No live project dir available in this mode; treat prod set as empty rather than
            # guessing -- callers wanting prod/dev separation must supply both fixtures explicitly.
            prod_audit = {"vulnerabilities": {}}
        else:
            project_dir = Path(args.project_dir)
            if not project_dir.is_absolute():
                project_dir = repo_root / project_dir
            prod_audit = run_npm_audit(project_dir, ["--omit=dev"])
    except RuntimeError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2

    full_vulns = extract_vulnerabilities(full_audit)
    prod_vulns = extract_vulnerabilities(prod_audit)

    print(f"npm audit policy check ({POLICY_PATH}):")
    print(f"  total vulnerable packages: {len(full_vulns)} (production: {len(prod_vulns)})")

    violations = evaluate(policy, full_vulns, prod_vulns)
    if violations:
        print("\nFAILED:", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1

    print("OK: no policy violation.")
    return 0


def run_calibration() -> int:
    """Self-test: prove all four rule branches (prod-any, severity-floor, dev-allowlisted,
    dev-unlisted-but-below-floor) are reachable, using in-memory fixtures. Never runs real npm."""
    ok = True
    policy = {
        "rules": {
            "failOnAnyProdVulnerability": True,
            "failOnSeverityAtOrAbove": "high",
            "requireAllowlistForDevSeverities": ["moderate"],
        },
        "allowedDevPackages": ["known-safe-dev-pkg"],
    }

    # Control 1: ANY prod vulnerability fails, even "low".
    v1 = evaluate(policy, full_vulns={"prod-pkg": "low"}, prod_vulns={"prod-pkg": "low"})
    pass1 = len(v1) == 1
    print(f"  [{'PASS' if pass1 else 'FAIL'}] a low-severity PROD vulnerability fails (violations: {v1})")
    ok = ok and pass1

    # Control 2: a dev vulnerability at/above the severity floor fails regardless of allowlist.
    v2 = evaluate(
        policy,
        full_vulns={"known-safe-dev-pkg": "high"},
        prod_vulns={},
    )
    pass2 = len(v2) == 1
    print(f"  [{'PASS' if pass2 else 'FAIL'}] a HIGH dev vulnerability fails even when the package is allowlisted (violations: {v2})")
    ok = ok and pass2

    # Control 3: a moderate dev vulnerability in an unlisted package fails.
    v3 = evaluate(policy, full_vulns={"unlisted-dev-pkg": "moderate"}, prod_vulns={})
    pass3 = len(v3) == 1
    print(f"  [{'PASS' if pass3 else 'FAIL'}] a MODERATE dev vulnerability in an unlisted package fails (violations: {v3})")
    ok = ok and pass3

    # Control 4: a moderate dev vulnerability in an ALLOWLISTED package passes.
    v4 = evaluate(policy, full_vulns={"known-safe-dev-pkg": "moderate"}, prod_vulns={})
    pass4 = len(v4) == 0
    print(f"  [{'PASS' if pass4 else 'FAIL'}] a MODERATE dev vulnerability in an allowlisted package passes (violations: {v4})")
    ok = ok and pass4

    # Control 5: a low dev vulnerability (named in neither rule) passes silently.
    v5 = evaluate(policy, full_vulns={"unlisted-dev-pkg": "low"}, prod_vulns={})
    pass5 = len(v5) == 0
    print(f"  [{'PASS' if pass5 else 'FAIL'}] a LOW dev vulnerability (unlisted, below the floor) passes (violations: {v5})")
    ok = ok and pass5

    if not ok:
        print("\nFAIL: calibration did not reproduce the expected PASS/FAIL pairs.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
