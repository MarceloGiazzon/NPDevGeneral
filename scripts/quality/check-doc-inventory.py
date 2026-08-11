#!/usr/bin/env python3
"""The process-document ban: a plan/checklist/findings/handoff/register may not enter the repo.

WHY THIS EXISTS
---------------
Measured 2026-08-11, at the close of the docs-decoupling work: **302 tracked `.md` files, 59,754
lines, of which 265 files / 39,705 lines were read by nothing at all** -- 88% of the files and 66%
of the lines served no machine purpose and no reader's purpose. They were an agent's working state,
externalised.

The mechanism that produced them is specific and it repeats: a session has no memory across runs, so
it writes a plan before and a closure report after. The next session cannot tell which of those are
still true, so it writes new ones rather than trusting them. Eventually someone writes a gate that
READS one, and from that moment the document cannot be deleted -- `check-register-consistency.py`
parses four such documents today, and `extract_platform_status.py` parsed a fifth as a database
until it was inverted to `ledger/gaps.yml`.

Reorganising them does not help: the 2026-08-11 pass moved 50 files into subdirectories and the
tracked total went 301 -> 302. The only thing that works is not creating them.

THE RULE
--------
Process/programme markdown is BANNED. A plan, checklist, findings log, handoff, retrospective,
session digest, snapshot or status register is WORKING STATE, not documentation:

  * durable MACHINE truth  -> structured data under `ledger/` (YAML/JSON), with a schema
  * durable HUMAN docs     -> `docs/`, few and curated, generated where possible
  * everything else        -> the evidence directory OUTSIDE the repo, or nowhere

WHAT THIS CHECKS (four rules)
-----------------------------
R1  BAN.      A tracked `.md` matching a `processDocPatterns` entry must be in `exempt` (durable,
              with a written reason) or `legacy` (frozen pre-ban set). Otherwise it fails: this is a
              new process document, and it does not belong in the repo.
R2  RATCHET.  The `legacy` set may only SHRINK. If more files match it than `frozenCount` declares,
              someone widened the escape hatch instead of using the evidence directory.
R3  HONESTY.  A `legacy` or `exempt` entry naming a file that no longer exists must be removed from
              the policy in the same commit -- otherwise the list rots into a permanent excuse, the
              way `LEDGER_EXCLUSIONS` did before R-P2 pruned it.
R4  REASONS.  Every `exempt` entry carries a non-empty reason. "Durable" is a claim someone has to
              make in writing, not a default.

CALIBRATE BEFORE TRUSTING IT (this repo's standing rule -- a gate nobody proved is a claim)
--------------------------------------------------------------------------------------------
    python scripts/quality/check-doc-inventory.py --calibrate

USAGE
-----
    python scripts/quality/check-doc-inventory.py             # check, exit 1 on findings
    python scripts/quality/check-doc-inventory.py --calibrate # self-test, exit 1 on failure
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

# REG-144: exact arithmetic from this file's own location, never a walk looking for a directory NAME.
REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / "scripts" / "policy" / "doc-inventory-policy.json"


def load_policy(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def tracked_markdown(root: Path) -> list[str]:
    out = subprocess.run(
        ["git", "-C", str(root), "ls-files", "*.md"],
        capture_output=True, text=True, check=False,
    )
    return sorted(f for f in out.stdout.split("\n") if f.strip())


def evaluate(policy: dict, tracked: list[str], exists) -> list[str]:
    """Returns a list of finding strings. Pure -- `exists` is injected so calibration can fake a tree."""
    patterns = [(re.compile(p["pattern"]), p["describes"]) for p in policy["processDocPatterns"]]
    exempt = {e["path"]: e.get("reason", "") for e in policy["exempt"]}
    legacy_files = set(policy["legacy"]["files"])
    frozen = policy["legacy"]["frozenCount"]
    findings: list[str] = []

    # R1 -- the ban itself
    matched_legacy = 0
    for f in tracked:
        hit = next(((rx, why) for rx, why in patterns if rx.search(f)), None)
        if not hit:
            continue
        if f in exempt:
            continue
        if f in legacy_files:
            matched_legacy += 1
            continue
        findings.append(
            f"BANNED process document: {f} (matches '{hit[0].pattern}' -- {hit[1]}). "
            f"This is working state, not documentation. Write it to the evidence directory outside "
            f"the repo, or record it as structured data under ledger/. If it is genuinely durable "
            f"reference material, add it to 'exempt' in scripts/policy/doc-inventory-policy.json "
            f"with a written reason."
        )

    # R2 -- ratchet: the frozen set may only shrink
    if matched_legacy > frozen:
        findings.append(
            f"RATCHET BROKEN: {matched_legacy} legacy process documents present but frozenCount is "
            f"{frozen}. The legacy list may only shrink -- it is a record of what predates the ban, "
            f"not a place to add new files."
        )

    # R3 -- honesty: no entry may name a file that is gone
    for f in sorted(legacy_files):
        if not exists(f):
            findings.append(f"STALE legacy entry: {f} no longer exists. Remove it from the policy "
                            f"and lower frozenCount in the same commit.")
    for f in sorted(exempt):
        if not exists(f):
            findings.append(f"STALE exempt entry: {f} no longer exists. Remove it from the policy.")

    # R4 -- every exemption is a written claim
    for f, reason in sorted(exempt.items()):
        if not reason.strip():
            findings.append(f"UNJUSTIFIED exempt entry: {f} has no reason. 'Durable' must be argued "
                            f"in writing, not assumed.")
    return findings


def calibrate() -> int:
    ok = True

    def report(label: str, findings: list[str], expect_fire: bool) -> None:
        nonlocal ok
        fired = bool(findings)
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        if fired and not passed:
            for f in findings[:2]:
                print(f"           {f}")

    base = {
        "processDocPatterns": [{"pattern": r"_PLAN\.md$", "describes": "a plan"}],
        "exempt": [],
        "legacy": {"frozenCount": 1, "files": ["docs/archive/OLD_PLAN.md"]},
    }
    always = lambda _f: True  # noqa: E731

    print("Calibration -- the ban must fire on a new process document and stay quiet otherwise:")
    report("a NEW *_PLAN.md appears at docs/ root",
           evaluate(base, ["docs/archive/OLD_PLAN.md", "docs/SHINY_NEW_PLAN.md"], always), True)
    report("only the frozen legacy plan is present",
           evaluate(base, ["docs/archive/OLD_PLAN.md"], always), False)
    report("a normal product doc is untouched by the ban",
           evaluate(base, ["docs/archive/OLD_PLAN.md", "docs/GETTING_STARTED.md"], always), False)

    exempted = dict(base, exempt=[{"path": "docs/GOOD_PLAN.md", "reason": "durable, because X"}])
    report("a new *_PLAN.md that IS exempted with a reason",
           evaluate(exempted, ["docs/archive/OLD_PLAN.md", "docs/GOOD_PLAN.md"], always), False)

    unjustified = dict(base, exempt=[{"path": "docs/GOOD_PLAN.md", "reason": "   "}])
    report("an exemption with a blank reason (R4)",
           evaluate(unjustified, ["docs/archive/OLD_PLAN.md", "docs/GOOD_PLAN.md"], always), True)

    report("ratchet: more legacy files present than frozenCount (R2)",
           evaluate(dict(base, legacy={"frozenCount": 0, "files": ["docs/archive/OLD_PLAN.md"]}),
                    ["docs/archive/OLD_PLAN.md"], always), True)

    report("stale legacy entry naming a deleted file (R3)",
           evaluate(base, [], lambda _f: False), True)

    print("\nCalibration -- against the REAL policy and working tree:")
    policy = load_policy(POLICY_PATH)
    real = evaluate(policy, tracked_markdown(REPO_ROOT), lambda f: (REPO_ROOT / f).exists())
    report("real repo (must be clean, or the ban is already violated)", real, False)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--calibrate", action="store_true", help="run the required controls and exit")
    args = parser.parse_args(argv)

    if args.calibrate:
        return calibrate()

    policy = load_policy(POLICY_PATH)
    tracked = tracked_markdown(REPO_ROOT)
    findings = evaluate(policy, tracked, lambda f: (REPO_ROOT / f).exists())

    frozen = policy["legacy"]["frozenCount"]
    print("Process-document ban (scripts/policy/doc-inventory-policy.json)")
    print(f"  tracked .md: {len(tracked)} | exempt: {len(policy['exempt'])} | "
          f"legacy frozen at: {frozen} (may only shrink)")
    for f in findings:
        print(f"  {f}")
    print(f"\n{len(findings)} blocking finding(s).")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
