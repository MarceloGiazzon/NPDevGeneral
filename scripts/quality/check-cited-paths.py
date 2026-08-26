#!/usr/bin/env python3
"""GATE: a repo-rooted path cited in a top-level document must exist on disk.

    python check-cited-paths.py
    python check-cited-paths.py --repo <repo>
    python check-cited-paths.py --calibrate

Exit 0 = every cited path resolves. Exit 1 = at least one does not. Exit 2 = bad usage.

WHY THIS EXISTS, GIVEN check-markdown-links.py ALREADY RUNS
    That checker validates markdown LINKS -- `[text](target)`. Nobody writes a path that way in
    this repo's prose. Every citation in CLAUDE.md, CONTRIBUTING.md, README.md, BREAKING.md and
    docs/README.md is a CODE SPAN: `scripts/quality/check-register-consistency.py`. A code span is
    not a link, so the link gate walks past all of them and reports green -- which is exactly what
    it did while 26 dead citations sat in those five files (measured 2026-08-23, finding C5).

    The worst of them was not decorative. CONTRIBUTING.md's "Gates that must pass" block opened
    with `python scripts/quality/check-register-consistency.py`, the FIRST command a new
    contributor is told to run, and that script had been deleted weeks earlier
    (md-zero-2026-08-11 Phase 2). The instruction did not degrade, it failed at the first line --
    and the only gate that looked at that file was checking a syntax it does not use.

    So this asks the other half of the question the link gate asks: not "does this link resolve"
    but "does this path, which the document states as fact, exist". Blocking, same rationale as
    every other checker in this gate -- a document whose first command does not run is not a
    documentation problem, it is a broken entry point.

THE RULE
    A token that looks like a path, is rooted at one of this repo's own top-level directories, and
    carries a known source/data extension, must resolve under the repo root. Anything else is left
    alone: a bare filename, a URL, an external path, a directory without an extension. That scope
    is deliberately narrow -- it is the shape a citation actually takes, and a wider net would
    manufacture findings nobody can act on.

    The exceptions live in scripts/policy/cited-path-exemptions.json, each with a reason, and they
    are exceptions of exactly two kinds: a citation that is not a path at all (an abbreviation with
    an elided middle, a `REG-nn` template) and a citation whose own surrounding prose already tells
    the reader the file is gone or lives outside this repo (BREAKING.md naming what it deleted; a
    layer-2 app definition that is legitimately absent on every CI checkout). A citation that is
    merely STALE is a defect in the DOCUMENT -- fix the document, never widen this list.

THIS SCRIPT READS MARKDOWN, AND THAT IS WORTH SAYING OUT LOUD
    CLAUDE.md's standing rule is that no script may read a `.md` file, with exactly five frozen
    exemptions, all of them markdown LINTERS -- scripts whose job IS validating hand-written prose.
    This is a sixth of that same kind, sitting directly beside check-markdown-links.py, which is
    one of the five. It cannot be inverted into structured data: the input it validates is the
    prose itself.

    2026-08-25 (remediation plan W5.2): check-no-markdown-reads.py's taint tracking DID catch this
    shape (this file's own read of a `.md` path, not the module-level DOCS tuple this docstring
    used to assume was invisible to it), and it went red on 2026-08-23. The owner-approved
    resolution named above happened: a sixth entry in
    scripts/policy/markdown-read-exemptions.json, frozenCount raised 5->6 in the same commit. See
    that file's own `why` for this entry.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
EXEMPTIONS_FILE = REPO_ROOT / "scripts" / "policy" / "cited-path-exemptions.json"

# Backslash, built from its code point: this file's own prose and regexes both have to talk about
# Windows-style separators, and a literal one is the single most common way to corrupt a line here.
BACKSLASH = chr(92)

PATH_RE = re.compile(
    r"[A-Za-z0-9_.\-/]+\.(?:py|ps1|sh|java|json|ya?ml|md|mustache|mjs|ts|tsx|gradle|template)"
)

# A citation only counts as a claim about THIS repo if it is rooted at one of this repo's own
# top-level directories. `pom.xml` in an example, `src/main/App.java` in a snippet, or any external
# project's path is not this checker's business.
REPO_TOP_LEVEL_DIRS = (
    "scripts/", "NPDevContract/", "NPDevGenerator/", "NPDevKernel/", "NPDevRuntimeHost/",
    "NPDevSamples/", "NPDevCli/", "NPDevMcp/", "NPDevManager/", "ledger/", "docs/",
    ".github/", "knowledge/", "schemas/", "content/", "npdev-templates/", "AppGen/",
)

# The documents a reader arrives through. Deliberately not repo-wide: these five are the entry
# points whose instructions someone follows literally on their first day.
DOCS = ("CLAUDE.md", "CONTRIBUTING.md", "README.md", "BREAKING.md", "docs/README.md")


def load_exemptions(path: Path) -> tuple[dict[str, str], str]:
    """Return ({cited path: reason}, placeholder segment). Missing file = no exemptions."""
    if not path.exists():
        return {}, ""
    data = json.loads(path.read_text(encoding="utf-8"))
    exempt = {e["path"]: e["reason"] for e in data.get("exemptions", [])}
    segment = str(data.get("placeholderRule", {}).get("segment", ""))
    return exempt, segment


def cited_paths(text: str) -> list[str]:
    """Every distinct repo-rooted path token in `text`, in first-seen order."""
    found: list[str] = []
    seen: set[str] = set()
    for match in PATH_RE.findall(text):
        normalized = match.replace(BACKSLASH, "/")
        if not normalized.startswith(REPO_TOP_LEVEL_DIRS) or normalized in seen:
            continue
        seen.add(normalized)
        found.append(normalized)
    return found


def dead_citations(text: str, repo: Path, exempt: dict[str, str], segment: str) -> list[str]:
    """Cited repo-rooted paths in `text` that do not exist under `repo` and are not exempt."""
    dead = []
    for path in cited_paths(text):
        if path in exempt:
            continue
        if segment and segment in path:
            continue
        if not (repo / path).exists():
            dead.append(path)
    return dead


def check(repo: Path, exempt: dict[str, str], segment: str) -> tuple[dict[str, list[str]], list[str]]:
    missing: dict[str, list[str]] = {}
    absent_docs: list[str] = []
    for doc in DOCS:
        path = repo / doc
        if not path.exists():
            absent_docs.append(doc)
            continue
        dead = dead_citations(path.read_text(encoding="utf-8", errors="replace"), repo, exempt, segment)
        if dead:
            missing[doc] = dead
    return missing, absent_docs


def calibrate() -> int:
    """Must FIRE on a doc citing a missing repo-rooted path and stay SILENT on one citing a real
    path -- the two controls differ in exactly that one variable, so a fire that is really a no-op
    (a regex that matches nothing, a scope that excludes everything) fails the silent control's
    twin below rather than passing unnoticed."""
    ok = True
    repo = REPO_ROOT
    exempt, segment = load_exemptions(EXEMPTIONS_FILE)

    # A real file and a missing one, cited the way the docs actually cite them: in a code span.
    real = "scripts/quality/check-cited-paths.py"
    gone = "scripts/quality/check-there-is-no-such-checker.py"
    assert (repo / real).exists(), "calibration's own real-path control names a file that is missing"
    assert not (repo / gone).exists(), "calibration's own missing-path control names a file that EXISTS"

    def report(label: str, text: str, expect_fire: bool, extra_exempt: dict[str, str] | None = None) -> None:
        nonlocal ok
        found = dead_citations(text, repo, {**exempt, **(extra_exempt or {})}, segment)
        fired = bool(found)
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for path in found:
            print(f"           {path}")

    print("Calibration -- must catch a cited path that does not exist, and only that:")
    report("a doc citing a MISSING repo-rooted path MUST fire",
           f"Run `python {gone}` before you start.\n", expect_fire=True)
    report("the same doc citing a REAL repo-rooted path MUST stay silent",
           f"Run `python {real}` before you start.\n", expect_fire=False)
    report("the missing path becomes silent once it is EXEMPT (proves the exemption list is read)",
           f"Run `python {gone}` before you start.\n", expect_fire=False,
           extra_exempt={gone: "calibration control"})
    report("an elided-middle abbreviation MUST stay silent",
           "See `NPDevKernel/kernel/.../NoSuchClass.java` for the details.\n", expect_fire=False)
    report("a missing path OUTSIDE this repo's top-level dirs MUST stay silent",
           "Compare with `some-other-project/src/main/Nope.java`.\n", expect_fire=False)

    # The firing control must not be an artefact of the synthetic text: the same sentence with the
    # ONE token swapped for a real file goes silent above, and a doc with no path token at all must
    # also stay silent.
    report("prose with no path token at all MUST stay silent",
           "The gate runs three checks and keeps going past a failure.\n", expect_fire=False)

    # And the real corpus scan must still be reachable -- a checker whose only working code path is
    # its own calibration is the failure mode this control set exists to rule out.
    with tempfile.TemporaryDirectory(prefix="npdev-cited-paths-calibrate-") as tmp:
        tmp_repo = Path(tmp)
        (tmp_repo / "docs").mkdir()
        (tmp_repo / "README.md").write_text(f"See `{gone}`.\n", encoding="utf-8")
        missing, absent = check(tmp_repo, {}, segment)
        fired = missing.get("README.md") == [gone]
        ok = ok and fired
        print(f"  [{'PASS' if fired else 'FAIL'}] check() over a synthetic repo reports the dead "
              f"citation per document ({len(absent)} of {len(DOCS)} docs absent there, as expected)")

    print(f"\nCalibration {'PASSED' if ok else 'FAILED'}.")
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=str(REPO_ROOT))
    ap.add_argument("--exemptions", default=str(EXEMPTIONS_FILE))
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    repo = Path(args.repo).resolve()
    exempt, segment = load_exemptions(Path(args.exemptions))
    missing, absent_docs = check(repo, exempt, segment)

    for doc in absent_docs:
        print(f"note: {doc} is not present in {repo} -- not scanned.")

    total = sum(len(v) for v in missing.values())
    scanned = len(DOCS) - len(absent_docs)
    print(f"Cited-path check: {scanned} document(s) scanned under {repo}, {len(exempt)} exemption(s) "
          f"declared, {total} dead citation(s).")

    if not missing:
        print("OK: every repo-rooted path cited in the entry-point documents exists.")
        return 0

    print("\nFAIL: these documents state a path as fact and the path does not exist:", file=sys.stderr)
    for doc, paths in sorted(missing.items()):
        print(f"\n  == {doc} -- {len(paths)} dead citation(s)", file=sys.stderr)
        for path in sorted(paths):
            print(f"       {path}", file=sys.stderr)
    print("\nFix the DOCUMENT: repoint the citation at where the file actually lives, or say plainly "
          "that it was deleted. Add to scripts/policy/cited-path-exemptions.json only if the "
          "citation is not a claim about a file at all -- with a reason.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
