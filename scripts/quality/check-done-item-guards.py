#!/usr/bin/env python3
"""R11 core mechanism: a ledger `status: DONE` claim must name a guard, and the guard must RESOLVE.

WHY THIS EXISTS
----------------
Measured on the strategy-2026-08-12 audit's own numbers: 178/178 tracked items were DONE, 154 claimed
`verification: VERIFIED_LIVE`, and nothing anywhere re-tested any of them. `ledger/README.md` already
grew an optional `guard: {kind, ref, asserts, provenRed}` field (992d47a8, this session) so a DONE
claim COULD name a test/script/manual proof instead of just asserting one -- but the field was, and
without this checker still would be, purely decorative: nothing verified a `guard.ref` pointed at
something real, and nothing required a DONE item to carry one at all. A guard nobody checks is exactly
the same failure mode `check-ledger-status-reverse-freshness.py`'s own docstring names for the status
field itself: a claim that only looks like evidence.

THE RULE (three parts)
-----------------------
R1  RATCHET.  Every `status: DONE` item must have a `guard:` block, UNLESS its id is in the frozen
    `legacy` list (`scripts/policy/done-item-guard-policy.json`) -- the DONE-without-guard items that
    predate this checker. That list may only SHRINK (same ratchet shape as
    `scripts/policy/doc-inventory-policy.json`'s pre-ban legacy set): a NEW item cannot join it, so
    every DONE claim filed from this commit forward must carry a real guard.
R2  RESOLUTION.  Every item that DOES carry a `guard:` block -- DONE, PARTIAL or OPEN alike, because a
    stale ref is a bug regardless of the item's status -- must have its `ref` actually RESOLVE:
      - kind: test    -> the file exists, and (if `#method` is given) that identifier appears in it.
      - kind: script   -> at least one concrete script path / gradle test-class / gradlew anchor is
                           found in the ref, and every anchor found actually exists.
      - kind: manual   -> best-effort: any repo-rooted path mentioned (scripts/, NPDevContract/, ...)
                           must exist. Bare filenames and app-relative paths (`_ops\\Run-FinalApp.ps1`,
                           a per-app path that is emitted at generation time and never lives in this
                           repo) are not checked -- see HONEST LIMIT below.
R3  HONESTY.  A `legacy` entry whose item no longer NEEDS the exemption (the item file is gone, its
    status is no longer DONE, or it has since gained a real guard) is stale and must be removed --
    with `frozenCount` lowered in the same commit -- exactly `check-doc-inventory.py`'s R3.

HONEST LIMIT -- kind: manual guards
------------------------------------
A manual guard is, by definition, a human reproduction recipe -- "boot an app and curl it", "read a
CodeQL alert list". Most legitimate manual guards name NO repo file at all (verified: 20 of 23 today).
This checker cannot tell "no file needed" from "the file reference was silently dropped", so it only
holds a manual guard to the same bar as a hyperlink checker: IF it names something that looks like a
repo-rooted path, that path must exist. It does not require one to be present, and it does not chase
paths through `_ops\\` or bare filenames that are known to be per-app generated artifacts (they never
live in this repo's tree by design -- OperationalRunbookEmitter writes them at generation time).

CALIBRATE BEFORE TRUSTING IT
------------------------------
    python scripts/quality/check-done-item-guards.py --calibrate

USAGE
-----
    python scripts/quality/check-done-item-guards.py             # exit 1 on any finding
    python scripts/quality/check-done-item-guards.py --calibrate  # self-test, exit 1 on failure
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import sys
import tempfile
from pathlib import Path

# REG-144: exact arithmetic from this file's own location, never a walk looking for a directory NAME.
REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / "scripts" / "policy" / "done-item-guard-policy.json"
LEDGER_ITEMS_DIR = REPO_ROOT / "ledger" / "items"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_open_items import load_items  # noqa: E402  (reuses the one ledger schema validator)

# Top-level directories a "manual" or "script" guard's path-like token must start with to be treated
# as a repo-resolvable anchor at all. Anything else (a bare filename, an `_ops\` app-relative path) is
# either informal prose or a per-app generated artifact this repo never tracks -- see HONEST LIMIT.
REPO_TOP_LEVEL_DIRS = (
    "scripts/", "NPDevContract/", "NPDevGenerator/", "NPDevKernel/", "NPDevRuntimeHost/",
    "NPDevEditor/", "NPDevSamples/", "NPDevCli/", "NPDevMcp/", "NPDevManager/",
    "ledger/", "docs/", ".github/", "knowledge/", "schemas/", "content/",
)

# Fully-qualified Java class name: dotted lowercase package segments, final segment starting
# UPPERCASE (Java class-naming convention) -- deliberately excludes lowercase dotted phrases like
# "core.autocrlf" that read as a class reference syntactically but are not one.
FQCN_RE = re.compile(r"^(?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*$")
TEST_REF_RE = re.compile(r"^([^#]+?)(?:#([A-Za-z_][A-Za-z0-9_]*))?(?:\s*\(.*\))?$")
MANUAL_PATH_TOKEN_RE = re.compile(r"[A-Za-z0-9_./\\-]+\.(?:py|ps1|sh|java|ts|tsx)\b")


def _exists(rel: str, root: Path) -> bool:
    return (root / rel).exists()


def _glob_java_class(root: Path, class_name: str) -> bool:
    return bool(glob.glob(str(root / "**" / f"{class_name}.java"), recursive=True))


def resolve_test(ref: str, root: Path) -> tuple[bool, str]:
    m = TEST_REF_RE.match(ref.strip())
    path = (m.group(1) if m else ref).strip()
    method = m.group(2) if m else None
    if not path or not _exists(path, root):
        return False, f"guard.ref path does not exist: '{path}'"
    if method and not _exists(path, root):  # defensive; unreachable given the check above
        return False, f"guard.ref path does not exist: '{path}'"
    if method:
        content = (root / path).read_text(encoding="utf-8", errors="replace")
        if method not in content:
            return False, f"method '{method}' not found in {path}"
    return True, "ok"


def resolve_script(ref: str, root: Path) -> tuple[bool, str]:
    tokens = [t.strip().strip("`'\"(),.:;") for t in re.split(r"[;\s]+", ref) if t.strip()]
    anchors: list[tuple[str, str]] = []
    for t in tokens:
        if re.search(r"\.(py|ps1|sh)$", t) and t.startswith(REPO_TOP_LEVEL_DIRS):
            anchors.append(("file", t))
        elif FQCN_RE.match(t):
            anchors.append(("class", t.split(".")[-1]))
        elif t in ("gradlew", "./gradlew"):
            anchors.append(("gradlew", t))
    if not anchors:
        return False, (
            "no resolvable anchor (a repo-rooted .py/.ps1/.sh path, a fully-qualified test class, or "
            "gradlew) found in guard.ref -- a script guard must name something this checker can run, "
            "or it should be kind: manual instead"
        )
    problems = []
    for kind, val in anchors:
        if kind == "file" and not _exists(val, root):
            problems.append(f"missing file: {val}")
        elif kind == "class" and not _glob_java_class(root, val):
            problems.append(f"no .java file found anywhere in the repo for class: {val}")
        elif kind == "gradlew" and not (_exists("gradlew", root) or _exists("gradlew.bat", root)):
            problems.append("no gradlew wrapper at repo root")
    if problems:
        return False, "; ".join(problems)
    return True, "ok"


def resolve_manual(ref: str, root: Path) -> tuple[bool, str]:
    checked = 0
    problems = []
    for token in set(MANUAL_PATH_TOKEN_RE.findall(ref)):
        normalized = token.replace("\\", "/")
        if not normalized.startswith(REPO_TOP_LEVEL_DIRS):
            continue  # bare filename or app-relative path -- see HONEST LIMIT
        checked += 1
        if not _exists(normalized, root):
            problems.append(f"missing: {normalized}")
    if problems:
        return False, "; ".join(sorted(problems))
    return True, f"ok ({checked} repo-rooted anchor(s) checked)"


def resolve_guard(guard: dict, root: Path) -> tuple[bool, str]:
    kind = guard.get("kind")
    ref = guard.get("ref", "")
    if kind == "test":
        return resolve_test(ref, root)
    if kind == "script":
        return resolve_script(ref, root)
    if kind == "manual":
        return resolve_manual(ref, root)
    return False, f"unknown guard.kind '{kind}'"


def load_policy(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(items: list[dict], policy: dict, root: Path) -> list[str]:
    """Pure -- `root` is injected so calibration can point it at a fake tree."""
    findings: list[str] = []
    legacy_ids = set(policy["legacy"]["ids"])
    frozen = policy["legacy"]["frozenCount"]

    done_no_guard = [i["id"] for i in items if i.get("status") == "DONE" and not i.get("guard")]
    by_id = {i["id"]: i for i in items}

    # R1 -- ratchet: a DONE item with no guard must be in the frozen legacy set.
    matched_legacy = 0
    for item_id in done_no_guard:
        if item_id in legacy_ids:
            matched_legacy += 1
            continue
        findings.append(
            f"NO GUARD: {item_id} is status: DONE with no guard: block and is not in the frozen "
            f"legacy list (scripts/policy/done-item-guard-policy.json). Add a real guard (see "
            f"ledger/README.md's schema) -- the legacy list is shrink-only, so a new DONE item "
            f"cannot be grandfathered into it."
        )
    if matched_legacy > frozen:
        findings.append(
            f"RATCHET BROKEN: {matched_legacy} legacy DONE-without-guard item(s) present but "
            f"frozenCount is {frozen}. The legacy list may only shrink."
        )

    # R3 -- honesty: a legacy id that no longer needs the exemption is stale.
    for item_id in sorted(legacy_ids):
        item = by_id.get(item_id)
        if item is None:
            findings.append(f"STALE legacy entry: {item_id} -- no ledger/items/{item_id}.yml exists "
                             f"any more. Remove it and lower frozenCount in the same commit.")
        elif item.get("status") != "DONE":
            findings.append(f"STALE legacy entry: {item_id} is status: {item.get('status')}, not "
                             f"DONE any more -- only DONE items need this exemption. Remove it and "
                             f"lower frozenCount in the same commit.")
        elif item.get("guard"):
            findings.append(f"STALE legacy entry: {item_id} now has a guard: block -- it no longer "
                             f"needs the exemption. Remove it and lower frozenCount in the same "
                             f"commit.")

    # R2 -- resolution: every guard, on any item, must actually resolve.
    for item in items:
        guard = item.get("guard")
        if not guard:
            continue
        ok, msg = resolve_guard(guard, root)
        if not ok:
            findings.append(
                f"UNRESOLVABLE GUARD: {item['id']} ({item.get('status')}, guard.kind={guard.get('kind')}): "
                f"{msg} -- guard.ref='{guard.get('ref')}'"
            )

    return findings


def calibrate() -> int:
    ok = True

    def report(label: str, fired: bool, expect_fire: bool) -> None:
        nonlocal ok
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / "scripts" / "quality").mkdir(parents=True)
        (root / "scripts" / "quality" / "real-check.py").write_text("# real\n", encoding="utf-8")
        (root / "NPDevContract" / "dsl" / "src" / "test").mkdir(parents=True)
        test_file = root / "NPDevContract" / "dsl" / "src" / "test" / "RealTest.java"
        test_file.write_text("class RealTest {\n  void realMethod() {}\n}\n", encoding="utf-8")

        base_policy = {"legacy": {"frozenCount": 1, "ids": ["ZZZ-OLD"]}}

        def items_with(*extra):
            base = [{"id": "ZZZ-OLD", "status": "DONE", "guard": None}]
            return base + list(extra)

        print("Calibration -- guard coverage (R1):")
        report("a NEW DONE item with no guard and not in legacy MUST fire",
               bool(evaluate(items_with({"id": "ZZZ-NEW", "status": "DONE", "guard": None}),
                              base_policy, root)),
               True)
        report("the frozen legacy DONE-without-guard item alone MUST stay quiet",
               bool(evaluate(items_with(), base_policy, root)),
               False)
        report("a DONE item WITH a resolvable guard MUST stay quiet",
               bool(evaluate(items_with({
                   "id": "ZZZ-GOOD", "status": "DONE",
                   "guard": {"kind": "script", "ref": "scripts/quality/real-check.py",
                              "asserts": "x", "provenRed": False},
               }), base_policy, root)),
               False)

        print("Calibration -- guard resolution (R2):")
        report("kind: test with a real path + real method MUST stay quiet",
               bool(evaluate(items_with({
                   "id": "ZZZ-T1", "status": "OPEN",
                   "guard": {"kind": "test",
                              "ref": "NPDevContract/dsl/src/test/RealTest.java#realMethod",
                              "asserts": "x", "provenRed": True},
               }), base_policy, root)),
               False)
        report("kind: test with a real path but a RENAMED method MUST fire",
               bool(evaluate(items_with({
                   "id": "ZZZ-T2", "status": "OPEN",
                   "guard": {"kind": "test",
                              "ref": "NPDevContract/dsl/src/test/RealTest.java#goneMethod",
                              "asserts": "x", "provenRed": True},
               }), base_policy, root)),
               True)
        report("kind: test with a DELETED file MUST fire",
               bool(evaluate(items_with({
                   "id": "ZZZ-T3", "status": "OPEN",
                   "guard": {"kind": "test", "ref": "NPDevContract/dsl/src/test/Gone.java",
                              "asserts": "x", "provenRed": True},
               }), base_policy, root)),
               True)
        report("kind: script naming a real repo script MUST stay quiet",
               bool(evaluate(items_with({
                   "id": "ZZZ-S1", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "python scripts/quality/real-check.py",
                              "asserts": "x", "provenRed": False},
               }), base_policy, root)),
               False)
        report("kind: script naming a DELETED repo script MUST fire",
               bool(evaluate(items_with({
                   "id": "ZZZ-S2", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "python scripts/quality/gone-check.py",
                              "asserts": "x", "provenRed": False},
               }), base_policy, root)),
               True)
        report("kind: script with NO resolvable anchor at all MUST fire",
               bool(evaluate(items_with({
                   "id": "ZZZ-S3", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "run the thing and look at it",
                              "asserts": "x", "provenRed": False},
               }), base_policy, root)),
               True)
        report("kind: manual with no repo-rooted path token MUST stay quiet (the common, legitimate case)",
               bool(evaluate(items_with({
                   "id": "ZZZ-M1", "status": "OPEN",
                   "guard": {"kind": "manual",
                              "ref": "curl -H 'X-Api-Key: dev-key' /api/anything, then check Start-App.ps1",
                              "asserts": "x", "provenRed": True},
               }), base_policy, root)),
               False)
        report("kind: manual naming a real repo-rooted path MUST stay quiet",
               bool(evaluate(items_with({
                   "id": "ZZZ-M2", "status": "OPEN",
                   "guard": {"kind": "manual", "ref": "run scripts/quality/real-check.py by hand",
                              "asserts": "x", "provenRed": True},
               }), base_policy, root)),
               False)
        report("kind: manual naming a MISSING repo-rooted path MUST fire",
               bool(evaluate(items_with({
                   "id": "ZZZ-M3", "status": "OPEN",
                   "guard": {"kind": "manual", "ref": "run scripts/quality/gone-check.py by hand",
                              "asserts": "x", "provenRed": True},
               }), base_policy, root)),
               True)

        print("Calibration -- honesty (R3):")
        report("a legacy id whose item file no longer exists MUST fire",
               bool(evaluate([], {"legacy": {"frozenCount": 1, "ids": ["ZZZ-GHOST"]}}, root)),
               True)
        report("a legacy id whose item is no longer DONE MUST fire",
               bool(evaluate([{"id": "ZZZ-REOPEN", "status": "OPEN", "guard": None}],
                              {"legacy": {"frozenCount": 1, "ids": ["ZZZ-REOPEN"]}}, root)),
               True)
        report("a legacy id that has since gained a guard MUST fire",
               bool(evaluate([{"id": "ZZZ-GUARDED", "status": "DONE",
                                "guard": {"kind": "script", "ref": "scripts/quality/real-check.py",
                                          "asserts": "x", "provenRed": False}}],
                              {"legacy": {"frozenCount": 1, "ids": ["ZZZ-GUARDED"]}}, root)),
               True)
        report("RATCHET BROKEN: more legacy-matched items than frozenCount MUST fire",
               bool(evaluate(
                   [{"id": "ZZZ-A", "status": "DONE", "guard": None},
                    {"id": "ZZZ-B", "status": "DONE", "guard": None}],
                   {"legacy": {"frozenCount": 1, "ids": ["ZZZ-A", "ZZZ-B"]}}, root)),
               True)

    print("\nCalibration -- against the REAL ledger and policy:")
    try:
        real_items = load_items(REPO_ROOT / "ledger")
    except ValueError as exc:
        print(f"  FAIL: real ledger does not even validate: {exc}")
        return 1
    real_policy = load_policy(POLICY_PATH)
    real_findings = evaluate(real_items, real_policy, REPO_ROOT)
    report("real repo (must be clean, or the ratchet is already violated)", bool(real_findings), False)
    if real_findings:
        for f in real_findings[:5]:
            print(f"    {f}")

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
        print("Calibrating check-done-item-guards:")
        return calibrate()

    try:
        items = load_items(REPO_ROOT / "ledger")
    except ValueError as exc:
        print(f"error: ledger does not validate: {exc}", file=sys.stderr)
        return 2

    policy = load_policy(POLICY_PATH)
    findings = evaluate(items, policy, REPO_ROOT)

    done = sum(1 for i in items if i.get("status") == "DONE")
    guarded = sum(1 for i in items if i.get("guard"))
    frozen = policy["legacy"]["frozenCount"]
    print("Falsifiable-DONE guard check (scripts/policy/done-item-guard-policy.json)")
    print(f"  ledger items: {len(items)} | DONE: {done} | with guard: {guarded} | "
          f"legacy frozen at: {frozen} (may only shrink)")
    for f in findings:
        print(f"  {f}")
    print(f"\n{len(findings)} blocking finding(s).")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
