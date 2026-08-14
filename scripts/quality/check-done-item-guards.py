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

THE RULE (four parts)
-----------------------
R1  COVERAGE.  Every `status: DONE` item must have a `guard:` block, UNLESS its id is in the frozen
    `legacy` list (`scripts/policy/done-item-guard-policy.json`) -- the DONE-without-guard items that
    predate this checker.
R2  RESOLUTION.  Every item that DOES carry a `guard:` block -- DONE, PARTIAL or OPEN alike, because a
    stale ref is a bug regardless of the item's status -- must have its `ref` actually RESOLVE:
      - kind: test    -> the file exists; if `#method` is given, that identifier must exist as a real
                          method DECLARATION (not a substring of a longer identifier, not a bare
                          mention in a comment or call site), and for a `.java` file it must be
                          annotated `@Test`/`@ParameterizedTest`/`@RepeatedTest`/`@TestFactory`/
                          `@TestTemplate` -- citing a private helper the test happens to call is not
                          the same claim as citing the test itself.
      - kind: script   -> at least one concrete anchor is found: a repo-rooted `.py`/`.ps1`/`.sh` path,
                          a fully-qualified Java test class (package AND simple name both verified
                          against the real file's path, not simple-name-only), or a Gradle task path
                          (`:Module:sub:task`, its module directory verified to exist). A bare mention
                          of the word "gradlew" with no specific task or class is NOT an anchor -- the
                          wrapper trivially exists in any checkout and proves nothing about the claim.
      - kind: manual   -> best-effort: any repo-rooted path mentioned (scripts/, NPDevContract/, ...)
                          must exist. Bare filenames and app-relative paths (`_ops\\Run-FinalApp.ps1`,
                          a per-app path that is emitted at generation time and never lives in this
                          repo) are not checked -- see HONEST LIMIT below.
R3  HONESTY.  A `legacy` entry whose item no longer NEEDS the exemption (the item file is gone, its
    status is no longer DONE, or it has since gained a real guard) is stale and must be removed --
    with `frozenCount` lowered in the same commit -- exactly `check-doc-inventory.py`'s R3.
R4  HISTORY-ANCHORED RATCHET.  R1 says the legacy list is "shrink-only", but comparing the CURRENT
    `legacy.ids` against the CURRENT `frozenCount` -- both declared in the same file, in the same
    commit -- cannot actually detect a new id added in that same commit: nothing pins either value to
    a point in git history. Proven by direct reproduction (independent review, 2026-08-14): add a
    brand-new `status: DONE`, no-`guard:` ledger item; add its id to `legacy.ids`; bump `frozenCount`
    to match -- R1/R2/R3 alone report zero findings. R4 closes this by comparing `legacy.ids` against
    its value at `git merge-base HEAD origin/main` (mirrors `check-pack-diff-gate.py`'s own established
    mechanism for the identical "did THIS branch's own diff do something it shouldn't" question). Any
    id present now but absent at the merge-base must have been an ALREADY-`DONE`-and-ALREADY-guardless
    item AT the merge-base (a real, honest documentation of pre-existing debt) -- an id whose ledger
    item did not exist at the merge-base at all, or existed but was not yet `DONE`, or already had a
    guard, is newly-manufactured debt laundered through the freeze list and FAILS. `frozenCount` is
    also required to equal `len(legacy.ids)` exactly, so the ceiling can never be pre-raised
    independent of the ids actually declared. If no merge-base can be resolved (no network, no `origin`
    remote, a shallow/detached checkout with no history) R4 is SKIPPED for that run, not failed --
    same "no baseline, nothing to compare" convention `check-pack-diff-gate.py` already established;
    this repo's own CI (`fetch-depth: 0` in `.github/workflows/ai-knowledge-gate.yml`) does not hit
    that case.

HONEST LIMIT -- kind: manual guards
------------------------------------
A manual guard is, by definition, a human reproduction recipe -- "boot an app and curl it", "read a
CodeQL alert list". Most legitimate manual guards name NO repo file at all (measured against the real
ledger: 11 of 18 today). This checker cannot tell "no file needed" from "the file reference was
silently dropped", so it only holds a manual guard to the same bar as a hyperlink checker: IF it names
something that looks like a repo-rooted path, that path must exist. It does not require one to be
present, and it does not chase paths through `_ops\\` or bare filenames that are known to be per-app
generated artifacts (they never live in this repo's tree by design -- OperationalRunbookEmitter writes
them at generation time).

HONEST LIMIT -- kind: test method-declaration matching
---------------------------------------------------------
The Java declaration/annotation scan is a regex heuristic, not a parser -- documented in the same
spirit as `check-ledger-status-reverse-freshness.py`'s own "HONEST LIMIT" section. It can, in principle,
be fooled by a comment line that happens to read like `SomeType methodName(` immediately following a
real `@Test` block for an unrelated method; in practice this has not been observed against the real
corpus and the annotation-adjacency requirement (skipping only blank lines and other `@`-annotations)
makes a false match require a specifically crafted comment in a specific position, not an accident.

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
import bisect
import glob
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

# REG-144: exact arithmetic from this file's own location, never a walk looking for a directory NAME.
REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_REL_PATH = "scripts/policy/done-item-guard-policy.json"
POLICY_PATH = REPO_ROOT / POLICY_REL_PATH
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
# A Gradle task path, e.g. ":NPDevContract:dsl:test" -- at least a module segment and a task segment.
GRADLE_TASK_PATH_RE = re.compile(r"^:[A-Za-z0-9_]+(?::[A-Za-z0-9_]+)+$")
TEST_REF_RE = re.compile(r"^([^#]+?)(?:#([A-Za-z_][A-Za-z0-9_]*))?(?:\s*\(.*\))?$")
MANUAL_PATH_TOKEN_RE = re.compile(r"[A-Za-z0-9_./\\-]+\.(?:py|ps1|sh|java|ts|tsx)\b")

# JUnit test-method annotations recognized by resolve_test's .java declaration scan.
JAVA_TEST_ANNOTATIONS = ("@Test", "@ParameterizedTest", "@RepeatedTest", "@TestFactory", "@TestTemplate")


def _exists(rel: str, root: Path) -> bool:
    return (root / rel).exists()


def _method_declaration_test_annotation_verdict(content: str, method: str) -> bool | None:
    """True: `method` is declared and a JUnit test annotation sits directly above it (skipping only
    blank lines and other `@`-annotations). False: a declaration was found but is NOT test-annotated
    (reads as a private helper). None: no declaration-shaped occurrence of `method` exists at all
    (renamed, removed, or only ever mentioned as a bare word/call site/comment).

    Matches against the FULL file content (not line-by-line) so a parameter list that wraps across
    multiple lines -- ordinary, common Java style, e.g. several `@TempDir Path` parameters -- is still
    found. `[^;{]` already matches newlines (a negated character class isn't `.`, so it needs no
    DOTALL), bounded to a generous-but-finite width so a single stray unmatched `(` can't scan the
    rest of the file looking for a `;`/`{`. `^` stays anchored to real line starts via MULTILINE, so
    this still requires the declaration's own line (not some unrelated earlier line) to start it."""
    lines = content.splitlines()
    line_starts = []
    offset = 0
    for line in lines:
        line_starts.append(offset)
        offset += len(line) + 1  # +1 for the '\n' splitlines() strips
    decl_re = re.compile(
        r"^[ \t]*(?:(?:public|private|protected|static|final|synchronized|abstract|default)\s+)*"
        r"[\w$.<>\[\]]+\s+" + re.escape(method) + r"\s*\([^;{]{0,4000}?\)",
        re.MULTILINE,
    )
    found_decl = False
    for m in decl_re.finditer(content):
        found_decl = True
        start_line_idx = bisect.bisect_right(line_starts, m.start()) - 1
        j = start_line_idx - 1
        while j >= 0 and (lines[j].strip() == "" or lines[j].strip().startswith("@")):
            if any(re.match(r"^\s*" + re.escape(ann) + r"\b", lines[j]) for ann in JAVA_TEST_ANNOTATIONS):
                return True
            j -= 1
    return False if found_decl else None


def resolve_test(ref: str, root: Path) -> tuple[bool, str]:
    m = TEST_REF_RE.match(ref.strip())
    path = (m.group(1) if m else ref).strip()
    method = m.group(2) if m else None
    if not path or not _exists(path, root):
        return False, f"guard.ref path does not exist: '{path}'"
    if not method:
        return True, "ok (no #method to verify)"

    content = (root / path).read_text(encoding="utf-8", errors="replace")
    if path.endswith(".java"):
        verdict = _method_declaration_test_annotation_verdict(content, method)
        if verdict is True:
            return True, "ok"
        if verdict is False:
            return False, (
                f"'{method}' is declared in {path} but is not annotated "
                f"@Test/@ParameterizedTest/@RepeatedTest/@TestFactory/@TestTemplate -- it reads as a "
                f"private helper, not the test method itself"
            )
        return False, f"'{method}' -- no method declaration found in {path} (renamed, removed, or never existed)"

    # Non-Java (e.g. a .test.ts file): no Java-shaped annotation check applies. Still closes the
    # original substring bug via a real identifier-boundary match.
    if not re.search(r"\b" + re.escape(method) + r"\b", content):
        return False, f"'{method}' not found as a whole identifier in {path}"
    return True, "ok (non-Java file -- identifier-boundary match only, no test-annotation check)"


def _java_class_file_matches(root: Path, fqcn: str) -> bool:
    """Verifies BOTH the simple class name AND its full package path against the real file location
    -- a guard citing a real class's simple name under the WRONG package must not resolve true."""
    parts = fqcn.split(".")
    class_name = parts[-1]
    package_suffix = "/".join(parts[:-1])
    target_suffix = f"{package_suffix}/{class_name}.java" if package_suffix else f"{class_name}.java"
    for candidate in glob.glob(str(root / "**" / f"{class_name}.java"), recursive=True):
        rel = Path(candidate).relative_to(root).as_posix()
        if rel.endswith(target_suffix):
            return True
    return False


def _gradle_module_dir_exists(root: Path, task_path: str) -> bool:
    # ":NPDevContract:dsl:test" -> module segments are every colon-segment except the last (the task).
    segments = [s for s in task_path.split(":") if s]
    module_segments = segments[:-1]
    if not module_segments:
        return False
    return root.joinpath(*module_segments).is_dir()


def resolve_script(ref: str, root: Path) -> tuple[bool, str]:
    # Note: ':' is deliberately NOT in the strip set -- a Gradle task path's leading colon must survive.
    tokens = [t.strip().strip("`'\"(),.;") for t in re.split(r"[;\s]+", ref) if t.strip()]
    anchors: list[tuple[str, str]] = []
    for t in tokens:
        if re.search(r"\.(py|ps1|sh)$", t) and t.startswith(REPO_TOP_LEVEL_DIRS):
            anchors.append(("file", t))
        elif FQCN_RE.match(t):
            anchors.append(("class", t))
        elif GRADLE_TASK_PATH_RE.match(t):
            anchors.append(("gradle-task", t))
    if not anchors:
        return False, (
            "no resolvable anchor (a repo-rooted .py/.ps1/.sh path, a fully-qualified test class, or "
            "a Gradle task path like ':NPDevContract:dsl:test') found in guard.ref -- a bare mention "
            "of 'gradlew' with no specific task or class proves nothing (the wrapper trivially exists "
            "in any checkout); a script guard must name something this checker can verify, or it "
            "should be kind: manual instead"
        )
    problems = []
    for kind, val in anchors:
        if kind == "file" and not _exists(val, root):
            problems.append(f"missing file: {val}")
        elif kind == "class" and not _java_class_file_matches(root, val):
            problems.append(f"no .java file at the claimed package path for class: {val}")
        elif kind == "gradle-task":
            if not _gradle_module_dir_exists(root, val):
                problems.append(f"Gradle task '{val}' names a module directory that does not exist")
            elif not (_exists("gradlew", root) or _exists("gradlew.bat", root)):
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


# --------------------------------------------------------------------------------------------------
# R4: history-anchored ratchet
# --------------------------------------------------------------------------------------------------

def resolve_merge_base(root: Path, base_ref: str = "origin/main") -> str | None:
    """The commit this checkout diverged from `origin/main`, or None if it cannot be determined --
    mirrors `check-pack-diff-gate.py`'s own `resolve_merge_base()` exactly (best-effort fetch first;
    a failed fetch still leaves a possibly-stale `origin/main` to try against rather than aborting)."""
    try:
        subprocess.run(["git", "fetch", "origin", "main"], cwd=str(root),
                        capture_output=True, text=True, timeout=60)
        result = subprocess.run(["git", "merge-base", "HEAD", base_ref], cwd=str(root),
                                 capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip() or None


def git_show(root: Path, rev: str, rel_path: str) -> str | None:
    """Content of `rel_path` at `rev`, or None if it did not exist there (or git itself is
    unavailable) -- `check-pack-diff-gate.py`'s `content_at()`, generalized to any path."""
    try:
        result = subprocess.run(["git", "show", f"{rev}:{rel_path}"], cwd=str(root),
                                 capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    return result.stdout


def check_history_anchored_ratchet(policy: dict, root: Path, merge_base: str | None) -> list[str]:
    """R4. See this module's docstring for the full rationale -- in short: R1's "shrink-only" claim
    about legacy.ids is only true if something outside the file being edited anchors it. This compares
    the current legacy.ids against their value at the merge-base with origin/main, and requires every
    id added since then to have already been a guard-less DONE item AT that merge-base (real,
    pre-existing debt) rather than newly manufactured in the same commit that freezes it."""
    findings: list[str] = []
    current_ids = set(policy["legacy"]["ids"])
    frozen = policy["legacy"]["frozenCount"]

    if frozen != len(current_ids):
        findings.append(
            f"frozenCount ({frozen}) does not equal legacy.ids' actual length ({len(current_ids)}) -- "
            f"they must match exactly, so the ceiling can never be pre-raised independent of the ids "
            f"actually declared."
        )

    if merge_base is None:
        return findings  # R4 skipped this run -- see resolve_merge_base()'s docstring; not a failure.

    baseline_raw = git_show(root, merge_base, POLICY_REL_PATH)
    baseline_ids: set[str] = set()
    if baseline_raw is not None:
        try:
            baseline_ids = set(json.loads(baseline_raw).get("legacy", {}).get("ids", []))
        except json.JSONDecodeError:
            findings.append(
                f"the policy file at merge-base {merge_base[:12]} is not valid JSON -- cannot verify "
                f"the history-anchored ratchet (R4)"
            )
            return findings

    for item_id in sorted(current_ids - baseline_ids):
        item_raw = git_show(root, merge_base, f"ledger/items/{item_id}.yml")
        if item_raw is None:
            findings.append(
                f"RATCHET VIOLATION (R4): '{item_id}' was added to legacy.ids, but no "
                f"ledger/items/{item_id}.yml existed at the merge-base with origin/main "
                f"({merge_base[:12]}) -- a brand-new item cannot be grandfathered into legacy; give "
                f"it a real guard: block instead."
            )
            continue
        try:
            item_at_base = yaml.safe_load(item_raw) or {}
        except yaml.YAMLError:
            findings.append(
                f"RATCHET VIOLATION (R4): '{item_id}' -- its ledger item at the merge-base does not "
                f"parse as YAML, so it cannot be verified as pre-existing debt."
            )
            continue
        if item_at_base.get("status") != "DONE":
            findings.append(
                f"RATCHET VIOLATION (R4): '{item_id}' was added to legacy.ids, but at the merge-base "
                f"its status was '{item_at_base.get('status')}', not DONE -- only an item that was "
                f"ALREADY a guard-less DONE item before this branch started may be backfilled into "
                f"legacy; this looks like newly-manufactured debt, not documented old debt."
            )
        elif item_at_base.get("guard"):
            findings.append(
                f"RATCHET VIOLATION (R4): '{item_id}' was added to legacy.ids, but at the merge-base "
                f"it already had a guard: block -- it never needed the exemption."
            )
        # else: legitimately pre-existing guard-less DONE debt -- fine to freeze now.

    return findings


def evaluate(items: list[dict], policy: dict, root: Path, merge_base: str | None) -> list[str]:
    """Pure -- `root`/`merge_base` are injected so calibration can point them at a fake tree/history."""
    findings: list[str] = []
    legacy_ids = set(policy["legacy"]["ids"])
    frozen = policy["legacy"]["frozenCount"]

    done_no_guard = [i["id"] for i in items if i.get("status") == "DONE" and not i.get("guard")]
    by_id = {i["id"]: i for i in items}

    # R1 -- coverage: a DONE item with no guard must be in the frozen legacy set.
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

    # R4 -- history-anchored ratchet (see its own docstring).
    findings.extend(check_history_anchored_ratchet(policy, root, merge_base))

    return findings


# --------------------------------------------------------------------------------------------------
# Calibration
# --------------------------------------------------------------------------------------------------

def _init_git_fixture(root: Path) -> None:
    """A disposable, hermetic git repo for R4's calibration controls -- explicit -c flags so it never
    depends on (or is broken by) the running machine's global git config (user identity, gpgsign)."""
    def run(*args: str) -> None:
        subprocess.run(["git", *args], cwd=str(root), check=True, capture_output=True, text=True)
    run("init", "-q")
    run("-c", "commit.gpgsign=false", "-c", "user.email=calibrate@example.com",
        "-c", "user.name=calibrate", "commit", "--allow-empty", "-q", "-m", "init")


def _git_commit_all(root: Path, message: str) -> None:
    subprocess.run(["git", "add", "-A"], cwd=str(root), check=True, capture_output=True, text=True)
    subprocess.run(["git", "-c", "commit.gpgsign=false", "-c", "user.email=calibrate@example.com",
                     "-c", "user.name=calibrate", "commit", "-q", "-m", message],
                    cwd=str(root), check=True, capture_output=True, text=True)


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
        test_file.write_text(
            "class RealTest {\n"
            "  @Test\n"
            "  void realMethod() {}\n"
            "\n"
            "  void realMethodExtra() {}\n"  # substring trap: must NOT match a guard for 'realMethod'
            "\n"
            "  void privateHelperMethod() {}\n"  # declared, but no @Test above it
            "}\n",
            encoding="utf-8",
        )
        (root / "NPDevContract" / "dsl").mkdir(parents=True, exist_ok=True)
        (root / "NPDevKernel" / "kernel").mkdir(parents=True, exist_ok=True)
        (root / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")

        base_policy = {"legacy": {"frozenCount": 1, "ids": ["ZZZ-OLD"]}}

        def items_with(*extra):
            base = [{"id": "ZZZ-OLD", "status": "DONE", "guard": None}]
            return base + list(extra)

        def ev(items, policy):
            return evaluate(items, policy, root, merge_base=None)  # R4 not under test in this block

        print("Calibration -- guard coverage (R1):")
        report("a NEW DONE item with no guard and not in legacy MUST fire",
               bool(ev(items_with({"id": "ZZZ-NEW", "status": "DONE", "guard": None}), base_policy)),
               True)
        report("the frozen legacy DONE-without-guard item alone MUST stay quiet",
               bool(ev(items_with(), base_policy)),
               False)
        report("a DONE item WITH a resolvable guard MUST stay quiet",
               bool(ev(items_with({
                   "id": "ZZZ-GOOD", "status": "DONE",
                   "guard": {"kind": "script", "ref": "scripts/quality/real-check.py",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               False)

        print("Calibration -- guard resolution (R2):")
        report("kind: test with a real path + real @Test method MUST stay quiet",
               bool(ev(items_with({
                   "id": "ZZZ-T1", "status": "OPEN",
                   "guard": {"kind": "test",
                              "ref": "NPDevContract/dsl/src/test/RealTest.java#realMethod",
                              "asserts": "x", "provenRed": True},
               }), base_policy)),
               False)
        report("kind: test with a RENAMED method MUST fire",
               bool(ev(items_with({
                   "id": "ZZZ-T2", "status": "OPEN",
                   "guard": {"kind": "test",
                              "ref": "NPDevContract/dsl/src/test/RealTest.java#goneMethod",
                              "asserts": "x", "provenRed": True},
               }), base_policy)),
               True)
        report("kind: test with a DELETED file MUST fire",
               bool(ev(items_with({
                   "id": "ZZZ-T3", "status": "OPEN",
                   "guard": {"kind": "test", "ref": "NPDevContract/dsl/src/test/Gone.java",
                              "asserts": "x", "provenRed": True},
               }), base_policy)),
               True)
        report("kind: test citing a SUBSTRING of a longer real method name MUST fire "
               "(reviewer issue #2: 'realMethod' must not match inside 'realMethodExtra')",
               bool(ev(items_with({
                   "id": "ZZZ-T4", "status": "OPEN",
                   "guard": {"kind": "test", "ref": "NPDevContract/dsl/src/test/RealTest.java#realMethod",
                              "asserts": "x", "provenRed": True},
               }), {"legacy": {"frozenCount": 1, "ids": ["ZZZ-OLD"]}})),
               False)  # realMethod itself IS real and @Test-annotated -- sanity check, must stay quiet
        report("kind: test citing a DECLARED-BUT-NOT-@Test method (private helper) MUST fire "
               "(reviewer issue #2: confirm @Test-annotated, not just present)",
               bool(ev(items_with({
                   "id": "ZZZ-T5", "status": "OPEN",
                   "guard": {"kind": "test",
                              "ref": "NPDevContract/dsl/src/test/RealTest.java#privateHelperMethod",
                              "asserts": "x", "provenRed": True},
               }), base_policy)),
               True)
        report("kind: script naming a real repo script MUST stay quiet",
               bool(ev(items_with({
                   "id": "ZZZ-S1", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "python scripts/quality/real-check.py",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               False)
        report("kind: script naming a DELETED repo script MUST fire",
               bool(ev(items_with({
                   "id": "ZZZ-S2", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "python scripts/quality/gone-check.py",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               True)
        report("kind: script with NO resolvable anchor at all MUST fire",
               bool(ev(items_with({
                   "id": "ZZZ-S3", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "run the thing and look at it",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               True)
        report("kind: script whose ref is bare prose mentioning 'gradlew' with no task/class MUST "
               "fire (reviewer issue #4: gradlew alone proves nothing)",
               bool(ev(items_with({
                   "id": "ZZZ-S4", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "just run gradlew and see what happens",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               True)
        report("kind: script with a real Gradle task path (module dir exists) MUST stay quiet",
               bool(ev(items_with({
                   "id": "ZZZ-S5", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "./gradlew :NPDevKernel:kernel:test",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               False)
        report("kind: script with a Gradle task path naming a NONEXISTENT module MUST fire",
               bool(ev(items_with({
                   "id": "ZZZ-S6", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "./gradlew :NPDevNoSuchModule:test",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               True)
        report("kind: script citing a real class's simple name under the WRONG package MUST fire "
               "(reviewer issue #3: package qualifier must match, not just the simple name)",
               bool(ev(items_with({
                   "id": "ZZZ-S7", "status": "OPEN",
                   "guard": {"kind": "script", "ref": "gradlew --tests wrong.pkg.RealTest",
                              "asserts": "x", "provenRed": False},
               }), base_policy)),
               True)
        report("kind: manual with no repo-rooted path token MUST stay quiet (the common, legitimate case)",
               bool(ev(items_with({
                   "id": "ZZZ-M1", "status": "OPEN",
                   "guard": {"kind": "manual",
                              "ref": "curl -H 'X-Api-Key: dev-key' /api/anything, then check Start-App.ps1",
                              "asserts": "x", "provenRed": True},
               }), base_policy)),
               False)
        report("kind: manual naming a real repo-rooted path MUST stay quiet",
               bool(ev(items_with({
                   "id": "ZZZ-M2", "status": "OPEN",
                   "guard": {"kind": "manual", "ref": "run scripts/quality/real-check.py by hand",
                              "asserts": "x", "provenRed": True},
               }), base_policy)),
               False)
        report("kind: manual naming a MISSING repo-rooted path MUST fire",
               bool(ev(items_with({
                   "id": "ZZZ-M3", "status": "OPEN",
                   "guard": {"kind": "manual", "ref": "run scripts/quality/gone-check.py by hand",
                              "asserts": "x", "provenRed": True},
               }), base_policy)),
               True)

        print("Calibration -- honesty (R3):")
        report("a legacy id whose item file no longer exists MUST fire",
               bool(ev([], {"legacy": {"frozenCount": 1, "ids": ["ZZZ-GHOST"]}})),
               True)
        report("a legacy id whose item is no longer DONE MUST fire",
               bool(ev([{"id": "ZZZ-REOPEN", "status": "OPEN", "guard": None}],
                       {"legacy": {"frozenCount": 1, "ids": ["ZZZ-REOPEN"]}})),
               True)
        report("a legacy id that has since gained a guard MUST fire",
               bool(ev([{"id": "ZZZ-GUARDED", "status": "DONE",
                          "guard": {"kind": "script", "ref": "scripts/quality/real-check.py",
                                    "asserts": "x", "provenRed": False}}],
                       {"legacy": {"frozenCount": 1, "ids": ["ZZZ-GUARDED"]}})),
               True)
        report("RATCHET BROKEN: more legacy-matched items than frozenCount MUST fire",
               bool(ev(
                   [{"id": "ZZZ-A", "status": "DONE", "guard": None},
                    {"id": "ZZZ-B", "status": "DONE", "guard": None}],
                   {"legacy": {"frozenCount": 1, "ids": ["ZZZ-A", "ZZZ-B"]}})),
               True)

    print("Calibration -- history-anchored ratchet (R4), against a REAL disposable git repo:")
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / "ledger" / "items").mkdir(parents=True)
        (root / "scripts" / "policy").mkdir(parents=True)
        policy_path = root / POLICY_REL_PATH

        # Baseline commit ("origin/main"): one pre-existing guard-less DONE item, correctly frozen,
        # plus one item that is guard-less DONE but NOT yet in legacy (a forgotten, legitimate
        # backfill candidate for later).
        (root / "ledger" / "items" / "OLD-1.yml").write_text("id: OLD-1\nstatus: DONE\n", encoding="utf-8")
        (root / "ledger" / "items" / "OLD-2.yml").write_text("id: OLD-2\nstatus: DONE\n", encoding="utf-8")
        policy_path.write_text(json.dumps({"legacy": {"frozenCount": 1, "ids": ["OLD-1"]}}), encoding="utf-8")
        _init_git_fixture(root)
        _git_commit_all(root, "baseline")
        subprocess.run(["git", "branch", "origin/main"], cwd=str(root), check=True,
                        capture_output=True, text=True)

        merge_base = resolve_merge_base(root)
        if merge_base is None:
            print("  [FAIL] could not resolve a merge-base in the disposable git fixture -- the R4 "
                  "calibration itself is broken, not just untested")
            ok = False
        else:
            def r4(policy: dict) -> list[str]:
                return check_history_anchored_ratchet(policy, root, merge_base)

            # --- Reviewer's exact reproduction: a brand-new item, never seen before, laundered
            # through legacy in the very commit that creates it.
            (root / "ledger" / "items" / "NEW-1.yml").write_text("id: NEW-1\nstatus: DONE\n", encoding="utf-8")
            attack_policy = {"legacy": {"frozenCount": 2, "ids": ["OLD-1", "NEW-1"]}}
            report("REVIEWER'S ATTACK: a brand-new DONE-without-guard item grandfathered into "
                   "legacy in the same commit that creates it MUST fire",
                   bool(r4(attack_policy)), True)

            # --- A legitimate late backfill: OLD-2 really was already guard-less DONE at the
            # merge-base, just missed by the initial freeze -- adding it later is honest.
            legit_policy = {"legacy": {"frozenCount": 2, "ids": ["OLD-1", "OLD-2"]}}
            report("a legitimate backfill of an item that WAS ALREADY guard-less DONE at the "
                   "merge-base MUST stay quiet",
                   bool(r4(legit_policy)), False)

            # --- An item that existed at merge-base but was OPEN there, marked DONE-without-guard
            # and immediately frozen in the same branch: still an attack, just less obvious than a
            # brand-new file.
            (root / "ledger" / "items" / "WASOPEN-1.yml").write_text(
                "id: WASOPEN-1\nstatus: OPEN\n", encoding="utf-8")
            _git_commit_all(root, "add WASOPEN-1 as OPEN (still before the 'attack' edit)")
            wasopen_attack_policy = {"legacy": {"frozenCount": 2, "ids": ["OLD-1", "WASOPEN-1"]}}
            report("an item that was OPEN (not DONE) at the merge-base, frozen into legacy anyway, "
                   "MUST fire",
                   bool(r4(wasopen_attack_policy)), True)

            # --- frozenCount lying about the ids count, with no id-set change at all.
            mismatch_policy = {"legacy": {"frozenCount": 5, "ids": ["OLD-1"]}}
            report("frozenCount not equal to len(legacy.ids) MUST fire even with no id added",
                   bool(r4(mismatch_policy)), True)

            report("merge-base unresolved (None) MUST skip R4 silently, not fail",
                   bool(check_history_anchored_ratchet(attack_policy, root, None)), False)

    print("\nCalibration -- against the REAL ledger and policy:")
    try:
        real_items = load_items(REPO_ROOT / "ledger")
    except ValueError as exc:
        print(f"  FAIL: real ledger does not even validate: {exc}")
        return 1
    real_policy = load_policy(POLICY_PATH)
    real_merge_base = resolve_merge_base(REPO_ROOT)
    print(f"  real merge-base with origin/main: {real_merge_base[:12] if real_merge_base else '(unresolved)'}")
    real_findings = evaluate(real_items, real_policy, REPO_ROOT, real_merge_base)
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
    merge_base = resolve_merge_base(REPO_ROOT)
    findings = evaluate(items, policy, REPO_ROOT, merge_base)

    done = sum(1 for i in items if i.get("status") == "DONE")
    guarded = sum(1 for i in items if i.get("guard"))
    frozen = policy["legacy"]["frozenCount"]
    print("Falsifiable-DONE guard check (scripts/policy/done-item-guard-policy.json)")
    print(f"  ledger items: {len(items)} | DONE: {done} | with guard: {guarded} | "
          f"legacy frozen at: {frozen} (may only shrink)")
    print(f"  R4 history anchor: merge-base with origin/main = "
          f"{merge_base[:12] if merge_base else 'UNRESOLVED (R4 skipped this run)'}")
    for f in findings:
        print(f"  {f}")
    print(f"\n{len(findings)} blocking finding(s).")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
