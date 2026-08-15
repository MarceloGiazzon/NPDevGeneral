#!/usr/bin/env python3
"""C5 (docs/CORPUS_INTEGRITY_PLAN.md, N5): walk every tracked `.md` file and fail on any relative
link whose target does not resolve on disk.

Same "nothing looked" shape as C1/C4 (the corpus-parse gate): this repo has reorganized docs twice
(the T1.15/2.A split, and R-P2's programme-history archival) and both times produced dangling links
that only ad-hoc `grep`/manual checks ever found, weeks apart. A relative markdown link that breaks
because its target moved is exactly the kind of silent rot nothing else in this repo's gate suite
notices -- `check-schema-mirror-consistency.py` watches JSON, `check-register-consistency.py`
watches status prose, but no gate has ever watched a link resolve.

Only checks that the FILE resolves, not that an in-file `#anchor` fragment exists -- resolving GitHub
-style heading slugs correctly is real extra complexity this repo has never needed (every known
break to date, both reorg rounds, was a moved/renamed FILE, never a renamed heading), so this stays
deliberately narrower than a full link-checker rather than growing speculative scope.

Skips: absolute URLs (http/https/mailto/etc.), bare same-file anchors (`#foo`), fenced code blocks
and inline code spans (a link INSIDE a code example is documentation, not a real link), and anything
under `node_modules`/`build`/`.git`.

    python check-markdown-links.py
    python check-markdown-links.py --root docs
    python check-markdown-links.py --calibrate
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
EXCLUDED_DIR_NAMES = {"node_modules", "build", ".git", ".gradle"}

# Matches [text](target) but not ![image](target) (images point at binary assets, a different and
# much noisier surface this check does not cover).
LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
FENCED_CODE_PATTERN = re.compile(r"```.*?```", re.DOTALL)
INLINE_CODE_PATTERN = re.compile(r"`[^`\n]*`")


def find_markdown_files(root: Path) -> list[Path]:
    # REG-148: this used to be a raw root.rglob("*.md") -- a real filesystem walk, contradicting
    # this module's own docstring ("walk every tracked .md file"). A git worktree checked out under
    # .claude/worktrees/ (gitignored, a normal location for parallel background-agent work) is a
    # real directory on disk, so its own .md files -- including ones on a stale/in-progress commit
    # with a genuinely broken relative link -- got swept into the scan and failed the gate for
    # content nobody committed to any branch actually being verified. git ls-files makes "tracked"
    # the literal enumeration mechanism, not just a claim in a comment.
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "ls-files", "-z", "--", "*.md"],
        capture_output=True, text=True, check=True,
    )
    resolved_root = root.resolve()
    files = []
    for rel in result.stdout.split("\0"):
        if not rel:
            continue
        p = (REPO_ROOT / rel).resolve()
        if any(part in EXCLUDED_DIR_NAMES for part in p.parts):
            continue
        try:
            p.relative_to(resolved_root)
        except ValueError:
            continue  # outside the requested --root scope
        files.append(p)
    return sorted(files)


def _strip_code(text: str) -> str:
    text = FENCED_CODE_PATTERN.sub("", text)
    text = INLINE_CODE_PATTERN.sub("", text)
    return text


def _is_checkable(target: str) -> bool:
    target = target.strip()
    if not target or target.startswith("#"):
        return False
    if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target):  # any URL scheme (http:, mailto:, tel:, ...)
        return False
    return True


def find_broken_links(path: Path) -> list[tuple[str, str]]:
    """Returns (raw_target, reason) pairs for links in `path` that don't resolve."""
    broken = []
    text = _strip_code(path.read_text(encoding="utf-8-sig", errors="replace"))
    for match in LINK_PATTERN.finditer(text):
        target = match.group(1).strip()
        if not _is_checkable(target):
            continue
        file_part = target.split("#", 1)[0].strip()
        if not file_part:
            continue
        resolved = (path.parent / file_part).resolve()
        if not resolved.exists():
            broken.append((target, str(resolved)))
    return broken


def check(root: Path) -> tuple[dict[Path, list[tuple[str, str]]], int]:
    files = find_markdown_files(root)
    results: dict[Path, list[tuple[str, str]]] = {}
    for f in files:
        broken = find_broken_links(f)
        if broken:
            results[f] = broken
    return results, len(files)


def calibrate() -> int:
    """Must FAIL on a link to a file that doesn't exist, PASS on one that does, and ignore URLs/
    same-file anchors/code spans -- same required-controls discipline as this repo's other
    --calibrate scripts."""
    import tempfile

    ok = True
    with tempfile.TemporaryDirectory(prefix="npdev-md-link-calibrate-") as tmp:
        tmp_path = Path(tmp)
        (tmp_path / "real.md").write_text("# Real\n", encoding="utf-8")
        good = tmp_path / "good.md"
        good.write_text(
            "See [real](real.md) and [anchor](#section) and <https://example.com/x.md> "
            "and `[fake](nope.md)` inline code and:\n```\n[fake](nope.md)\n```\n",
            encoding="utf-8",
        )
        bad = tmp_path / "bad.md"
        bad.write_text("See [missing](does-not-exist.md).\n", encoding="utf-8")

        def report(label: str, path: Path, expect_fail: bool) -> None:
            nonlocal ok
            broken = find_broken_links(path)
            fired = bool(broken)
            passed = fired == expect_fail
            ok = ok and passed
            print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
            for target, resolved in broken:
                print(f"           {target} -> {resolved}")

        print("Calibration -- must catch a broken relative link, ignore URLs/anchors/code:")
        report("well-formed file (real link + anchor + URL + code-span/block distractors)", good, expect_fail=False)
        report("file with a genuinely broken relative link", bad, expect_fail=True)

    # REG-148: find_markdown_files must enumerate git-TRACKED files only -- an untracked file (the
    # exact shape of a stray .claude/worktrees/ checkout) must never be scanned, however broken its
    # links are.
    with tempfile.TemporaryDirectory(prefix="npdev-md-link-calibrate-git-") as tmp:
        tmp_path = Path(tmp)
        subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
        subprocess.run(["git", "config", "user.email", "calibrate@example.invalid"], cwd=tmp_path, check=True)
        subprocess.run(["git", "config", "user.name", "calibrate"], cwd=tmp_path, check=True)
        (tmp_path / "tracked.md").write_text("# Tracked\n", encoding="utf-8")
        subprocess.run(["git", "add", "tracked.md"], cwd=tmp_path, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=tmp_path, check=True)
        (tmp_path / "untracked.md").write_text(
            "See [missing](does-not-exist.md).\n", encoding="utf-8",
        )

        global REPO_ROOT
        real_repo_root = REPO_ROOT
        REPO_ROOT = tmp_path
        try:
            found = {p.name for p in find_markdown_files(tmp_path)}
        finally:
            REPO_ROOT = real_repo_root

        passed = found == {"tracked.md"}
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] find_markdown_files enumerates tracked files only "
              f"(found: {sorted(found)})")

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=str(REPO_ROOT))
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    root = Path(args.root)
    results, count = check(root)
    total_broken = sum(len(v) for v in results.values())
    print(f"Markdown link check: {count} file(s) scanned under {root}, {total_broken} broken link(s) "
          f"in {len(results)} file(s).")
    if results:
        print("\nFAIL: the following relative links do not resolve:", file=sys.stderr)
        for path, broken in sorted(results.items()):
            rel = path.relative_to(REPO_ROOT) if path.is_relative_to(REPO_ROOT) else path
            for target, resolved in broken:
                print(f"  - {rel}: [{target}] -> {resolved}", file=sys.stderr)
        return 1
    print("OK: every relative markdown link resolves.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
