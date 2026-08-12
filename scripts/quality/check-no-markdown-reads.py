#!/usr/bin/env python3
"""The zero-rule gate: no script may read a `.md` file's content.

md-zero-2026-08-11 PLAN.md's whole thesis, made mechanical. Markdown is output for humans. A
script that opens one and uses what it finds as a data source for program logic is the defect
Groups B-G of that plan spent six phases removing -- 37 couplings, ~23 scripts, all converted to
read JSON/YAML instead. This gate is what stops a 38th one from growing back.

WHAT COUNTS AS "READING MARKDOWN"
----------------------------------
A call to a content-read idiom (`open()`, `.read_text()`, `.read_bytes()`, `Path.open()`,
`.glob()`/`.rglob()` with an `.md` pattern in Python; `Get-Content`, `Select-String`,
`[System.IO.File]::ReadAllText/ReadAllLines` in PowerShell; `cat`/`grep`/`sed`/`awk` in shell)
whose argument resolves to a `.md` path -- as a LITERAL, through a VARIABLE assigned one earlier in
the same file, or through a CONTAINER (tuple/list/dict literal, or a for-loop unpacking one).

WHAT THIS DELIBERATELY DOES NOT CATCH (documented, not silently absent)
-------------------------------------------------------------------------
  * A `.md` path that arrives as DATA loaded from a JSON/YAML config at runtime (the value never
    appears as a literal anywhere in the scanned source). check-pinned-download-links.py's
    `knownLinkSites[].file` is exactly this shape -- it is on the exemption list regardless, so
    this blind spot costs nothing today, but a NEW script built the same way would not be caught by
    static analysis alone. No fully static scanner can close this without also parsing every
    referenced config file and cross-referencing which keys reach a read call.
  * Cross-function taint: a `.md` literal assigned in one function and passed as a parameter to
    another is not traced. Every real coupling found in this repo lived within one function or
    module scope; this is a documented simplification, not an oversight.
  * Path ENUMERATION alone (`git ls-files "*.md"`, a bare `Get-ChildItem -Filter *.md` with no
    subsequent content read) -- listing which paths exist and classifying by PATH PATTERN is not
    the same operation as reading a path's bytes and using its prose as data. check-doc-inventory.py
    does exactly this and is not on the exemption list because of it.

THE EXEMPTION LIST (scripts/policy/markdown-read-exemptions.json)
---------------------------------------------------------------------
The plan's own text says "zero allowlist entries." A real risk audit before this gate was written
found that premise false for this repo: only 10 of 287 tracked `.md` files are rendered from a
registry (Groups C/D/E/F's own work), so deleting the scripts that VALIDATE the other 277 --
link integrity, doc classification, script-mention verification, download-link pinning,
hardcoded-path scanning -- would remove real, working protection with nothing to replace it. Every
exemption entry requires a non-empty `why` (same discipline every other allowlist in this repo
already uses) and is a LINTER of markdown prose, never a consumer that mines it as a data source
for logic elsewhere -- see markdown-read-exemptions.json's own header for the full distinction.
An exemption naming a file that no longer exists fails this gate (same R3 discipline as
check-doc-inventory.py).

CALIBRATE BEFORE TRUSTING IT
------------------------------
    python scripts/quality/check-no-markdown-reads.py --calibrate

USAGE
-----
    python scripts/quality/check-no-markdown-reads.py             # exit 1 on any new finding
    python scripts/quality/check-no-markdown-reads.py --calibrate # self-test
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / "scripts" / "policy" / "markdown-read-exemptions.json"

SKIP_DIR_PARTS = ("/build/", "/target/", "/node_modules/", "/Output/", "/.git/", "/.gradle/", "/dist/")


def repo_root() -> Path:
    root = Path(__file__).resolve().parents[2]
    if not all((root / m).is_dir() for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
        print(f"error: {root} is not an NPDev checkout", file=sys.stderr)
        raise SystemExit(2)
    return root


def tracked_scannable_files(root: Path) -> list[Path]:
    out = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        print("error: git ls-files failed", file=sys.stderr)
        raise SystemExit(2)
    files: list[Path] = []
    for rel in out.stdout.splitlines():
        if not rel.strip():
            continue
        posix = "/" + rel.replace("\\", "/")
        if any(part in posix for part in SKIP_DIR_PARTS):
            continue
        if rel.endswith((".py", ".ps1", ".sh")):
            files.append(root / rel)
    return files


def looks_like_md_path(value: str) -> bool:
    """A literal path or glob pattern that names a markdown file -- covers "x.md", "*.md",
    "**/*.md", "ADR-*.md" and any f-string suffix ending the same way."""
    return bool(value) and value.lower().rstrip("'\"").endswith(".md")


# ------------------------------------------------------------------------------------------------
# Python: AST-based taint tracking, SCOPED per function/class body -- a module-level `TARGETS`
# tuple is visible to every function (matching generate_group_d_docs.py's own real shape), but a
# name assigned inside one function is invisible to an unrelated sibling function even if it
# happens to share a name (`target`, `path`, `p` are common enough in a 5000-line CLI that a flat,
# whole-module taint set produced real false positives here -- caught by hand-checking every real
# hit before trusting any of them, the same discipline this repo's --calibrate convention exists
# to enforce mechanically instead of by one-off inspection).
# ------------------------------------------------------------------------------------------------

READ_METHOD_NAMES = ("read_text", "read_bytes", "open")
GLOB_METHOD_NAMES = ("glob", "rglob")
SUBPROCESS_TEXT_TOOLS = ("cat", "grep", "sed", "awk", "type")

SCOPE_NODES = (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)
STMT_LIST_OWNERS = (ast.If, ast.For, ast.AsyncFor, ast.While, ast.Try, ast.With, ast.AsyncWith)


class PythonMarkdownReadScanner:
    def __init__(self, path: Path):
        self.path = path
        self.findings: list[tuple[int, str]] = []
        # Name -> its List/Tuple-of-Tuples literal, for positional taint in `for a, b in NAME:`
        # (see _positional_for_taint below). Whole-file, like `tainted` conceptually would be if
        # names weren't per-scope -- literal-list assignments in this codebase are always
        # module-level constants (TARGETS = [...]), so this stays deliberately simple.
        self.list_literals: dict[str, ast.AST] = {}

    def scan_module(self, tree: ast.Module) -> None:
        self._collect_list_literals(tree)
        self._scan_stmts(tree.body, set())

    def _collect_list_literals(self, tree: ast.Module) -> None:
        """First pass: record every `NAME = [literal, ...]` / `NAME = (literal, ...)` anywhere in
        the file (a plain ast.walk -- scope precision does not matter here: at worst a wrongly-
        collected literal only affects the OPTIMISTIC positional refinement below, never suppresses
        a real finding, since the general conservative taint rule is always the fallback)."""
        for node in ast.walk(tree):
            if isinstance(node, ast.Assign) and isinstance(node.value, (ast.List, ast.Tuple)):
                for target in node.targets:
                    if isinstance(target, ast.Name):
                        self.list_literals[target.id] = node.value

    # -- expression-level helpers (expressions never contain a nested statement scope, Lambda's
    # single-expression body included, so a plain ast.walk is safe here) --------------------------

    def _expr_is_md_tainted(self, node: ast.AST, tainted: set[str]) -> bool:
        for sub in ast.walk(node):
            if isinstance(sub, ast.Constant) and isinstance(sub.value, str) and looks_like_md_path(sub.value):
                return True
            if isinstance(sub, ast.Name) and sub.id in tainted:
                return True
        return False

    def _positional_for_taint(self, stmt: ast.For | ast.AsyncFor, tainted: set[str]) -> bool:
        """`for yaml_rel, json_rel, md_rel in TARGETS:` where TARGETS is a resolvable list of
        fixed-arity tuples: taint ONLY the unpacked names whose OWN position in every element-tuple
        is .md-tainted, not every name just because the tuple ALSO carries an .md value somewhere
        else in it. Without this, generate_group_d_docs.py's real `(yaml_rel, json_rel, md_rel)`
        shape false-positived on yaml_rel/json_rel purely for being unpacked alongside md_rel --
        found by hand-checking every real hit before trusting it (see the module docstring's own
        false-positive note). Returns True if it handled the taint (caller should not also apply the
        general conservative rule); False to fall back when the shape cannot be resolved this way,
        which still catches the plan's own named tuple-unpacking false-negative in the general case."""
        if not isinstance(stmt.target, ast.Tuple):
            return False
        target_names = stmt.target.elts
        if not all(isinstance(t, ast.Name) for t in target_names):
            return False
        elements: list[ast.AST] | None = None
        if isinstance(stmt.iter, ast.Name) and stmt.iter.id in self.list_literals:
            elements = self.list_literals[stmt.iter.id].elts
        elif isinstance(stmt.iter, (ast.List, ast.Tuple)):
            elements = stmt.iter.elts
        if elements is None:
            return False
        if not all(isinstance(e, ast.Tuple) and len(e.elts) == len(target_names) for e in elements):
            return False
        for i, name_node in enumerate(target_names):
            if any(self._expr_is_md_tainted(e.elts[i], tainted) for e in elements):
                tainted.add(name_node.id)
        return True

    def _mark_tainted(self, target: ast.AST, tainted: set[str]) -> None:
        if isinstance(target, ast.Name):
            tainted.add(target.id)
        elif isinstance(target, (ast.Tuple, ast.List)):
            for elt in target.elts:
                self._mark_tainted(elt, tainted)
        elif isinstance(target, ast.Starred):
            self._mark_tainted(target.value, tainted)

    def _scan_expr_for_calls(self, node: ast.AST | None, tainted: set[str]) -> None:
        if node is None:
            return
        for sub in ast.walk(node):
            if isinstance(sub, ast.Call):
                self._check_call(sub, tainted)

    def _check_call(self, node: ast.Call, tainted: set[str]) -> None:
        func = node.func
        if isinstance(func, ast.Name) and func.id == "open":
            if node.args and self._expr_is_md_tainted(node.args[0], tainted):
                self.findings.append((node.lineno, "open() on a path resolving to .md"))
            return
        if isinstance(func, ast.Attribute):
            if func.attr in READ_METHOD_NAMES:
                if self._expr_is_md_tainted(func.value, tainted):
                    self.findings.append((node.lineno, f".{func.attr}() on a path resolving to .md"))
                return
            if func.attr in GLOB_METHOD_NAMES:
                if node.args and self._expr_is_md_tainted(node.args[0], tainted):
                    self.findings.append((node.lineno, f".{func.attr}() with an .md glob pattern"))
                return
        self._check_subprocess_call(node, tainted)

    def _check_subprocess_call(self, node: ast.Call, tainted: set[str]) -> None:
        func = node.func
        is_subprocess = (
            isinstance(func, ast.Attribute) and func.attr in ("run", "check_output", "check_call", "Popen")
            and isinstance(func.value, ast.Name) and func.value.id == "subprocess"
        )
        if not is_subprocess or not node.args:
            return
        first = node.args[0]
        if not isinstance(first, (ast.List, ast.Tuple)) or not first.elts:
            return
        head = first.elts[0]
        if not (isinstance(head, ast.Constant) and isinstance(head.value, str)):
            return
        tool = Path(head.value).stem.lower()
        if tool not in SUBPROCESS_TEXT_TOOLS:
            return
        for arg in first.elts[1:]:
            if self._expr_is_md_tainted(arg, tainted):
                self.findings.append((node.lineno, f"subprocess {tool} on a path resolving to .md"))
                return

    # -- statement-level traversal: recurse into nested statement lists in place (same scope);
    # start a FRESH inherited-copy scope at each function/class boundary, so a local taint never
    # leaks to a sibling scope, matching normal Python scoping rules closely enough for this purpose --

    def _scan_stmts(self, stmts: list[ast.stmt], tainted: set[str]) -> None:
        for stmt in stmts:
            self._scan_stmt(stmt, tainted)

    def _scan_stmt(self, stmt: ast.stmt, tainted: set[str]) -> None:
        if isinstance(stmt, SCOPE_NODES):
            self._scan_stmts(stmt.body, set(tainted))
            return
        if isinstance(stmt, ast.Assign):
            self._scan_expr_for_calls(stmt.value, tainted)
            if self._expr_is_md_tainted(stmt.value, tainted):
                for target in stmt.targets:
                    self._mark_tainted(target, tainted)
            return
        if isinstance(stmt, ast.AnnAssign):
            if stmt.value is not None:
                self._scan_expr_for_calls(stmt.value, tainted)
                if self._expr_is_md_tainted(stmt.value, tainted):
                    self._mark_tainted(stmt.target, tainted)
            return
        if isinstance(stmt, (ast.For, ast.AsyncFor)):
            self._scan_expr_for_calls(stmt.iter, tainted)
            if not self._positional_for_taint(stmt, tainted) and self._expr_is_md_tainted(stmt.iter, tainted):
                self._mark_tainted(stmt.target, tainted)
            self._scan_stmts(stmt.body, tainted)
            self._scan_stmts(stmt.orelse, tainted)
            return
        if isinstance(stmt, (ast.If, ast.While)):
            self._scan_expr_for_calls(stmt.test, tainted)
            self._scan_stmts(stmt.body, tainted)
            self._scan_stmts(stmt.orelse, tainted)
            return
        if isinstance(stmt, ast.Try):
            self._scan_stmts(stmt.body, tainted)
            for handler in stmt.handlers:
                self._scan_stmts(handler.body, tainted)
            self._scan_stmts(stmt.orelse, tainted)
            self._scan_stmts(stmt.finalbody, tainted)
            return
        if isinstance(stmt, (ast.With, ast.AsyncWith)):
            for item in stmt.items:
                self._scan_expr_for_calls(item.context_expr, tainted)
            self._scan_stmts(stmt.body, tainted)
            return
        # Simple statement (Expr, Return, Raise, Assert, Delete, Import, Global, augmented
        # assignment, match, ...): none of these can themselves hold a nested statement scope
        # Python's grammar would let a def/class appear inside (those are always their own
        # statements in an enclosing body, never a sub-expression) -- match-case bodies are the
        # one real exception this simplification accepts; unseen in this repo's own source so far.
        self._scan_expr_for_calls(stmt, tainted)


def scan_python(path: Path, text: str) -> list[tuple[int, str]]:
    try:
        tree = ast.parse(text, filename=str(path))
    except SyntaxError:
        return []
    scanner = PythonMarkdownReadScanner(path)
    scanner.scan_module(tree)
    return scanner.findings


# ------------------------------------------------------------------------------------------------
# PowerShell / shell: regex line scan with a whole-file variable taint set (same simplification as
# the Python scanner -- flat, not scope-aware).
# ------------------------------------------------------------------------------------------------

PS_ASSIGN_RE = re.compile(r"\$(\w+)\s*=.*?([.\w*/\\-]+\.md)\b", re.IGNORECASE)
PS_READ_RE = re.compile(
    r"\b(Get-Content|Select-String|\[System\.IO\.File\]::Read(?:AllText|AllLines))\b", re.IGNORECASE,
)
SH_ASSIGN_RE = re.compile(r"^\s*(?:local\s+)?(\w+)=([.\w*/\\-]+\.md)\b", re.IGNORECASE)
SH_READ_RE = re.compile(r"\b(cat|grep|sed|awk)\b", re.IGNORECASE)
MD_LITERAL_LINE_RE = re.compile(r"[.\w*/\\-]*\.md\b", re.IGNORECASE)


def scan_powershell(text: str) -> list[tuple[int, str]]:
    findings: list[tuple[int, str]] = []
    tainted: set[str] = set()
    for lineno, line in enumerate(text.splitlines(), start=1):
        for m in PS_ASSIGN_RE.finditer(line):
            tainted.add(m.group(1).lower())
        read_match = PS_READ_RE.search(line)
        if not read_match:
            continue
        has_literal = bool(MD_LITERAL_LINE_RE.search(line))
        has_tainted_var = any(re.search(rf"\${re.escape(name)}\b", line, re.IGNORECASE) for name in tainted)
        if has_literal or has_tainted_var:
            findings.append((lineno, f"{read_match.group(1)} on a path resolving to .md"))
    return findings


def scan_shell(text: str) -> list[tuple[int, str]]:
    findings: list[tuple[int, str]] = []
    tainted: set[str] = set()
    for lineno, line in enumerate(text.splitlines(), start=1):
        m = SH_ASSIGN_RE.match(line)
        if m:
            tainted.add(m.group(1))
        read_match = SH_READ_RE.search(line)
        if not read_match:
            continue
        has_literal = bool(MD_LITERAL_LINE_RE.search(line))
        has_tainted_var = any(re.search(rf"\$\{{?{re.escape(name)}\b", line) for name in tainted)
        if has_literal or has_tainted_var:
            findings.append((lineno, f"{read_match.group(1)} on a path resolving to .md"))
    return findings


# ------------------------------------------------------------------------------------------------
# Exemption policy + orchestration
# ------------------------------------------------------------------------------------------------

def load_exemptions(policy_path: Path, root: Path) -> tuple[set[str], list[str]]:
    data = json.loads(policy_path.read_text(encoding="utf-8"))
    paths: set[str] = set()
    failures: list[str] = []

    # THE CEILING (a ratchet, not a limit). The owner accepted 5 markdown LINTERS on 2026-08-11 and
    # ruled the list may never grow. `frozenCount` must EQUAL the number of entries, so:
    #   more entries  -> fail: a new script started reading markdown and someone reached for the
    #                    escape hatch instead of inverting the data, which is the whole defect.
    #   fewer entries -> fail until frozenCount is lowered in the SAME commit, so the ceiling
    #                    ratchets DOWN with the list and can never be silently re-widened later.
    # Same shape as check-doc-inventory.py's legacy ratchet, for the same reason: an allowlist
    # nothing bounds is an allowlist that grows.
    declared = data.get("frozenCount")
    actual = len(data.get("exemptFiles", []))
    if declared is None:
        failures.append("policy has no 'frozenCount' -- the exemption ceiling is what stops this "
                        "list growing; it must be declared and equal the number of entries")
    elif actual > declared:
        failures.append(
            f"EXEMPTION CEILING BREACHED: {actual} exemptions but frozenCount is {declared}. This "
            f"list may never grow. Invert the markdown read into structured data instead -- see "
            f"md-zero-2026-08-11 PLAN.md. If a new markdown LINTER is genuinely unavoidable, that "
            f"is an owner decision, not a checker change.")
    elif actual < declared:
        failures.append(
            f"frozenCount is {declared} but only {actual} exemption(s) remain. Lower frozenCount to "
            f"{actual} in this same commit -- the ceiling ratchets DOWN and must never be left "
            f"above the real count, or it silently re-authorises the difference.")

    for entry in data.get("exemptFiles", []):
        path = entry.get("path", "")
        why = str(entry.get("why", "")).strip()
        if not why:
            failures.append(f"exemption {path!r} has no (or an empty) 'why'")
            continue
        if not (root / path).is_file():
            failures.append(f"exemption {path!r} names a file that no longer exists -- remove the entry")
            continue
        paths.add(path)
    return paths, failures


def scan_file(path: Path) -> list[tuple[int, str]]:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    if path.suffix == ".py":
        return scan_python(path, text)
    if path.suffix == ".ps1":
        return scan_powershell(text)
    if path.suffix == ".sh":
        return scan_shell(text)
    return []


def run(root: Path, policy_path: Path) -> tuple[list[tuple[str, int, str]], list[str], int]:
    """Returns (findings, policy failures, files scanned)."""
    exempt_paths, policy_failures = load_exemptions(policy_path, root)
    findings: list[tuple[str, int, str]] = []
    scanned = 0
    for file in tracked_scannable_files(root):
        rel = file.relative_to(root).as_posix()
        if rel in exempt_paths:
            continue
        scanned += 1
        for lineno, message in scan_file(file):
            findings.append((rel, lineno, message))
    return findings, policy_failures, scanned


def main_check(root: Path, policy_path: Path) -> int:
    print("Zero-markdown-reads gate (md-zero-2026-08-11 PLAN.md Phase 7):")
    findings, policy_failures, scanned = run(root, policy_path)
    exempt_count = len(json.loads(policy_path.read_text(encoding="utf-8")).get("exemptFiles", []))
    print(f"  {scanned} script(s) scanned (.py/.ps1/.sh), {exempt_count} exempted (see {policy_path.name})")

    if policy_failures:
        print(f"\nFAIL: {len(policy_failures)} exemption policy issue(s):")
        for f in policy_failures:
            print(f"  - {f}")
        return 1

    if findings:
        print(f"\nFAIL: {len(findings)} script(s) read markdown content with no exemption:")
        for rel, lineno, message in findings:
            print(f"  - {rel}:{lineno}: {message}")
        print("\nEither invert the coupling (move the fact into JSON/YAML, per md-zero-2026-08-11 "
              "PLAN.md's own pattern), or add a justified entry to "
              "scripts/policy/markdown-read-exemptions.json if this is genuinely a markdown linter.")
        return 1

    print("\nOK: 0 scripts read markdown content.")
    return 0


def calibrate() -> int:
    """Required before trusting this checker: proves, on synthetic in-memory source only (never
    the real repo), that each read idiom fires under literal/variable/tuple-unpack/glob shapes,
    stays silent on a non-.md read, and that the exemption mechanism actually suppresses a real hit."""
    import tempfile

    ok = True

    def report(label: str, findings: list, expect_fire: bool) -> None:
        nonlocal ok
        fired = bool(findings)
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

    py_cases = [
        ("literal open() on a .md path -- MUST fire",
         'open("docs/GETTING_STARTED.md").read()\n', True),
        ("literal .read_text() on a .md path -- MUST fire",
         'from pathlib import Path\nPath("README.md").read_text()\n', True),
        ("variable assigned a .md literal, then read -- MUST fire",
         'p = "docs/adr/ADR-0011.md"\nopen(p).read()\n', True),
        ("tuple-unpacked for-loop target, the plan's own noted false-negative -- MUST fire",
         'TARGETS = [("content/readme.yml", "README.md")]\n'
         'for yaml_rel, md_rel in TARGETS:\n'
         '    open(md_rel).read()\n', True),
        ("tuple-unpacked for-loop target, the OTHER (non-.md) position read -- must stay silent "
         "(the real generate_group_d_docs.py false positive positional tracking was built for: "
         "TARGETS = [(yaml_rel, json_rel, md_rel), ...], only yaml_path.read_text() was called, "
         "and whole-tuple tainting flagged it anyway for being unpacked alongside md_rel)",
         'TARGETS = [("content/readme.yml", "content/readme.json", "README.md")]\n'
         'for yaml_rel, json_rel, md_rel in TARGETS:\n'
         '    yaml_path = ROOT / yaml_rel\n'
         '    open(yaml_path).read()\n', False),
        (".glob() with an .md pattern -- MUST fire",
         'from pathlib import Path\nfor p in Path(".").rglob("*.md"): pass\n', True),
        ("subprocess grep on a .md literal -- MUST fire",
         'import subprocess\nsubprocess.run(["grep", "-l", "TODO", "README.md"])\n', True),
        ("a .json read -- must stay silent (not just \"any open() call\")",
         'import json\njson.loads(open("content/readme.json").read())\n', False),
        ("a variable assigned a .json literal, then read -- must stay silent",
         'p = "content/readme.json"\nopen(p).read()\n', False),
    ]
    for label, source, expect_fire in py_cases:
        report(label, scan_python(Path("fixture.py"), source), expect_fire)

    # A real false positive found against npdev_cli.py while building this scanner: the SAME
    # variable name ("target") tainted in one function and reused, unrelated, in a sibling function
    # -- must fire exactly once (scaffold(), not tail_log()), never leaking across scopes.
    scope_source = (
        'def scaffold():\n'
        '    target = "docs/GETTING_STARTED.md"\n'
        '    open(target).read()\n\n'
        'def tail_log(log_dir):\n'
        '    target = log_dir / "app.log"\n'
        '    target.open("r").read()\n'
    )
    scope_findings = scan_python(Path("fixture.py"), scope_source)
    scope_ok = len(scope_findings) == 1 and scope_findings[0][0] == 3
    ok = ok and scope_ok
    print(f"  [{'PASS' if scope_ok else 'FAIL'}] sibling-function variable-name reuse does not cross scopes "
          f"({'fired once, correct line' if scope_ok else f'got {scope_findings}'})")

    ps_cases = [
        ("Get-Content on a literal .md -- MUST fire", 'Get-Content -LiteralPath "README.md"\n', True),
        ("Get-Content on a tainted variable -- MUST fire",
         '$doc = "docs/GETTING_STARTED.md"\nGet-Content -LiteralPath $doc\n', True),
        ("Get-Content on a .json path -- must stay silent",
         'Get-Content -LiteralPath "content/readme.json"\n', False),
    ]
    for label, source, expect_fire in ps_cases:
        report(label, scan_powershell(source), expect_fire)

    sh_cases = [
        ("grep on a literal .md -- MUST fire", 'grep -m1 Requires README.md\n', True),
        ("cat on a .json path -- must stay silent", 'cat content/readme.json\n', False),
    ]
    for label, source, expect_fire in sh_cases:
        report(label, scan_shell(source), expect_fire)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel"):
            (root / m).mkdir()
        scripts_dir = root / "scripts" / "quality"
        scripts_dir.mkdir(parents=True)
        offender = scripts_dir / "offender.py"
        offender.write_text('open("README.md").read()\n', encoding="utf-8")
        (root / "README.md").write_text("# fixture\n", encoding="utf-8")

        policy_path = root / "exemptions.json"
        policy_path.write_text(json.dumps({"exemptFiles": [], "frozenCount": 0}), encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "add", "-A"], cwd=root, check=True)
        findings, failures, _ = run(root, policy_path)
        report("unexempted real hit -- MUST fire (the live RED)",
               findings, expect_fire=True)

        policy_path.write_text(json.dumps({
            "exemptFiles": [{"path": "scripts/quality/offender.py", "why": "fixture exemption"}], "frozenCount": 1
        }), encoding="utf-8")
        findings, failures, _ = run(root, policy_path)
        report("same hit, now exempted -- must go silent (the live GREEN)",
               findings, expect_fire=False)

        policy_path.write_text(json.dumps({
            "exemptFiles": [{"path": "scripts/quality/offender.py", "why": "   "}], "frozenCount": 1
        }), encoding="utf-8")
        _, failures, _ = run(root, policy_path)
        report("exemption with an empty 'why' -- MUST fire as a policy failure",
               failures, expect_fire=True)

        policy_path.write_text(json.dumps({
            "exemptFiles": [{"path": "scripts/quality/does-not-exist.py", "why": "stale"}], "frozenCount": 1
        }), encoding="utf-8")
        _, failures, _ = run(root, policy_path)
        report("exemption naming a file that no longer exists -- MUST fire as a policy failure",
               failures, expect_fire=True)

        # THE CEILING (owner ruling, 2026-08-11: 5 exemptions, may never grow). Three controls --
        # a ceiling nobody has watched fire is a number in a file, not a control.
        policy_path.write_text(json.dumps({
            "exemptFiles": [
                {"path": "scripts/quality/offender.py", "why": "fixture exemption"},
                {"path": "scripts/quality/check-no-markdown-reads.py", "why": "second fixture"},
            ],
            "frozenCount": 1,
        }), encoding="utf-8")
        _, failures, _ = run(root, policy_path)
        report("a 6th exemption appears (count > frozenCount) -- MUST fire as a policy failure",
               failures, expect_fire=True)

        policy_path.write_text(json.dumps({
            "exemptFiles": [{"path": "scripts/quality/offender.py", "why": "fixture exemption"}],
            "frozenCount": 4,
        }), encoding="utf-8")
        _, failures, _ = run(root, policy_path)
        report("list shrank but frozenCount left high -- MUST fire (the ratchet must go DOWN)",
               failures, expect_fire=True)

        policy_path.write_text(json.dumps({
            "exemptFiles": [{"path": "scripts/quality/offender.py", "why": "fixture exemption"}]
        }), encoding="utf-8")
        _, failures, _ = run(root, policy_path)
        report("policy with no frozenCount at all -- MUST fire (the ceiling cannot be optional)",
               failures, expect_fire=True)


    if not ok:
        print("\nFAIL: at least one control did not behave as required -- this checker does not ship "
              "until it does.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly, including the live RED/GREEN exemption proof. "
          "Safe to run against the real repo.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(REPO_ROOT), help="repo root (default: this repo)")
    parser.add_argument("--policy", default=str(POLICY_PATH), help="path to markdown-read-exemptions.json")
    parser.add_argument("--calibrate", action="store_true", help="run the required self-test controls and exit")
    args = parser.parse_args(argv)

    if args.calibrate:
        return calibrate()

    return main_check(Path(args.root).resolve(), Path(args.policy).resolve())


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
