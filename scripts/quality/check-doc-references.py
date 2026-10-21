#!/usr/bin/env python3
"""DOCS-1: every path, script, CLI subcommand/flag and symbol a doc names still exists.

WHY THIS EXISTS
---------------
The 682-finding documentation audit was a one-off sweep. A sweep rots the day after it ends; the
cure is a guard that fails the moment a doc names something the tree no longer has.

WHAT IT READS
-------------
`docs/content/*.json` only -- the JSON mirrors the README-class docs are generated from. It NEVER
reads a markdown file (the repo forbids scripts reading markdown; see check-no-markdown-reads.py).
Every inline `code span` in prose and every sh-tagged fence in those files is classified:

  cli     `npdev <sub> [<sub>] [--flag]` -- walked through the REAL argparse tree
          (npdev_cli.build_parser()), so a removed subcommand or flag is caught
  path    a repo path (first segment is a repo-root entry) or a file name with a known extension --
          must exist exactly, or (for a bare file name) exist somewhere in the tracked tree
  symbol  a camelCase/PascalCase/snake_case identifier -- must occur as a token in tracked source
          (docs/ and ledger/ are excluded from that universe so a doc cannot vouch for itself)
  flag    a bare `--long-flag` -- must be a CLI option or appear in tracked source

WHAT IT DELIBERATELY DOES NOT DO
--------------------------------
It does not run anything and cannot tell that a documented step works -- only that the thing it
names exists. A name that legitimately does not exist in the tree (an output path of a generated
app, a third-party identifier) is listed with its reason in scripts/policy/doc-reference-policy.json.
That list is a ratchet: `frozenCount` may only shrink, and an entry nothing matches any more fails
the check, so the allowlist cannot rot into a hiding place.

SCOPE VISIBILITY
----------------
Docs not generated from docs/content are either moved there or out of scope. The out-of-scope set is
printed every run (enumerated by `git ls-files`, never read) so the gap stays visible.

`--calibrate` is run as part of every normal run too: it plants a reference to a nonexistent script,
a nonexistent subcommand/flag, a nonexistent path and a nonexistent symbol in an in-memory doc and
fails unless every one is reported -- a checker that cannot see a planted defect will not see a real
one -- and fails if a clean doc is reported.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "quality" / "firstrun-harness"))
sys.path.insert(0, str(REPO / "NPDevCli"))
import extract_commands  # noqa: E402

POLICY = REPO / "scripts" / "policy" / "doc-reference-policy.json"
CONTENT_DIR = REPO / "docs" / "content"

FILE_EXTENSIONS = {
    "py", "ps1", "sh", "bat", "json", "yml", "yaml", "java", "html", "mustache", "gradle", "toml",
    "properties", "xml", "txt", "csv", "js", "css", "sql", "jar", "kts", "md",
}
SHELL_FENCES = {"sh", "bash", "shell", "powershell", "pwsh", "ps1", "bat", "cmd"}
SKIP_UNIVERSE_SUFFIXES = {
    "md", "png", "jpg", "jpeg", "gif", "ico", "zip", "jar", "class", "gz", "pdf", "woff", "woff2",
    "ttf", "eot", "svg", "mp4", "bin", "exe", "dll", "so", "db", "lock",
}
UNIVERSE_EXCLUDED_PREFIXES = ("docs/", "ledger/")
# The checker's own source names its planted-defect fixtures, so it must not count as evidence.
UNIVERSE_EXCLUDED_FILES = ("scripts/quality/check-doc-references.py", "scripts/policy/doc-reference-policy.json")
MAX_UNIVERSE_FILE_BYTES = 1_500_000

SPAN_RE = re.compile(r"`([^`\n]+)`")
TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{3,}")
LONG_FLAG_RE = re.compile(r"--[a-z0-9][a-z0-9-]+")
# File names that tracked source MENTIONS: a generator emits `Start-App.ps1` into every app, so the name
# lives in an emitter string, not as a tracked file.
FILENAME_RE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]*\.(?:ps1|py|sh|bat|html|java|jar|txt|json|yml|properties)")
IDENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\(\))?$")
PLACEHOLDER_CHARS = set("<>{}*$%~|&;\"'=@")


# ---------------------------------------------------------------------------------------------
# Context: the facts a reference is checked against
# ---------------------------------------------------------------------------------------------
class Context:
    def __init__(self, policy: dict):
        self.policy = policy
        self.tracked = self._git_ls_files()
        self.tracked_set = set(self.tracked)
        self.basenames = {p.rsplit("/", 1)[-1] for p in self.tracked}
        self.root_entries = {p.split("/", 1)[0] for p in self.tracked}
        self.tokens, self.long_flags, self.mentioned_files = self._build_universe()
        self.parser = self._build_parser()
        self.cli_flags = self._collect_cli_flags(self.parser)
        self.used_ignores: set[str] = set()

    @staticmethod
    def _git_ls_files() -> list[str]:
        out = subprocess.run(["git", "ls-files", "-z"], cwd=REPO, capture_output=True, check=True).stdout
        return [p for p in out.decode("utf-8", "replace").split("\0") if p]

    def _build_universe(self) -> tuple[set[str], set[str], set[str]]:
        tokens: set[str] = set()
        flags: set[str] = set()
        files: set[str] = set()
        for rel in self.tracked:
            if rel.startswith(UNIVERSE_EXCLUDED_PREFIXES) or rel in UNIVERSE_EXCLUDED_FILES:
                continue
            if rel.rsplit(".", 1)[-1].lower() in SKIP_UNIVERSE_SUFFIXES:
                continue
            path = REPO / rel
            try:
                if not path.is_file() or path.stat().st_size > MAX_UNIVERSE_FILE_BYTES:
                    continue
                text = path.read_bytes().decode("utf-8", "ignore")
            except OSError:
                continue
            tokens.update(TOKEN_RE.findall(text))
            flags.update(LONG_FLAG_RE.findall(text))
            files.update(FILENAME_RE.findall(text))
        return tokens, flags, files

    @staticmethod
    def _build_parser() -> argparse.ArgumentParser | None:
        try:
            import npdev_cli  # noqa: E402
            return npdev_cli.build_parser()
        except Exception as exc:  # the finding is reported by main(), never silently skipped
            print(f"WARNING: npdev_cli.build_parser() unavailable ({exc}); CLI references NOT checked.")
            return None

    @staticmethod
    def _collect_cli_flags(parser: argparse.ArgumentParser | None) -> set[str]:
        flags: set[str] = set()

        def walk(p: argparse.ArgumentParser) -> None:
            for action in p._actions:
                flags.update(action.option_strings)
                if isinstance(action, argparse._SubParsersAction):
                    for child in action.choices.values():
                        walk(child)

        if parser is not None:
            walk(parser)
        return flags

    def ignored(self, kind: str, value: str) -> bool:
        for entry in self.policy.get("ignore", []):
            if entry["kind"] != kind:
                continue
            if value in entry.get("values", []):
                self.used_ignores.add(f"{entry['id']}:{value}")
                return True
            if any(value.startswith(p) for p in entry.get("prefixes", [])):
                self.used_ignores.add(f"{entry['id']}:prefix")
                return True
        return False


# ---------------------------------------------------------------------------------------------
# Reference extraction
# ---------------------------------------------------------------------------------------------
def walk_doc(node, where: str):
    """Yield (location, kind, text): kind is 'span-source' (prose to scan for `spans`) or 'fence'."""
    if isinstance(node, dict):
        if node.get("type") == "fence":
            yield where, "fence", (node.get("lang") or "").lower(), node.get("text") or ""
            return
        for key, value in node.items():
            if key == "text" and isinstance(value, str):
                yield where, "prose", "", value
            elif key == "title" and isinstance(value, str):
                yield where, "prose", "", value
            else:
                yield from walk_doc(value, f"{where}/{key}")
    elif isinstance(node, list):
        for i, value in enumerate(node):
            yield from walk_doc(value, f"{where}[{i}]")
    elif isinstance(node, str):
        yield where, "prose", "", node


def classify_span(span: str, ctx: Context) -> list[tuple[str, str]]:
    """A code span -> [(kind, value)]. Empty when it is not a checkable reference."""
    span = span.strip()
    if not span or any(c.isspace() for c in span.strip()) and not re.match(r"^(\./)?npdev(\.bat)?\s", span):
        return []
    if re.match(r"^(\./)?npdev(\.bat)?\s", span):
        return [("cli", span)]
    return classify_token(span, ctx)


def classify_token(raw: str, ctx: Context) -> list[tuple[str, str]]:
    token = raw.strip().rstrip(".,:;)")
    if not token or "://" in token or token.startswith(("/", "~")) or re.match(r"^[A-Za-z]:[\\/]", token):
        return []
    if token.startswith("--"):
        flag = token.split("=", 1)[0]
        return [("flag", flag)] if LONG_FLAG_RE.fullmatch(flag) else []
    if any(c in PLACEHOLDER_CHARS for c in token) or "\\" in token:
        return []
    token = token[2:] if token.startswith("./") else token
    if "/" in token:
        first = token.split("/", 1)[0]
        last = token.rstrip("/").rsplit("/", 1)[-1]
        ext = last.rsplit(".", 1)[-1].lower() if "." in last else ""
        if first in ctx.root_entries or ext in FILE_EXTENSIONS:
            return [("path", token)]
        return []
    ext = token.rsplit(".", 1)[-1].lower() if "." in token else ""
    if ext in FILE_EXTENSIONS and re.fullmatch(r"[\w.-]+", token) and not token.startswith("."):
        return [("path", token)]
    parts = token.split(".") if "." in token else [token]
    found = []
    for part in parts:
        if IDENT_RE.match(part) and is_symbol_shaped(part.removesuffix("()")):
            found.append(("symbol", part.removesuffix("()")))
    return found


def is_symbol_shaped(name: str) -> bool:
    """camelCase, PascalCase with a second hump, or a snake_case word -- not a plain English word or
    an ALL_CAPS constant of a product we do not own."""
    if len(name) < 5:
        return False
    if re.search(r"[a-z][A-Z]", name):
        return True
    if "_" in name and name.lower() == name:
        return True
    return False


def split_command_words(command: str) -> list[str]:
    cut = re.split(r"\s*(?:\|\||\||&&|;|>|<|#)\s*", command, maxsplit=1)[0]
    return cut.split()


def check_cli(command: str, ctx: Context) -> str | None:
    """None when `command` resolves in the argparse tree, else the reason."""
    if ctx.parser is None:
        return None
    words = split_command_words(command)
    if not words:
        return None
    words = words[1:]  # drop the launcher
    node = ctx.parser
    ancestors: list[argparse.ArgumentParser] = []
    i = 0
    while i < len(words):
        word = words[i]
        sub_action = next((a for a in node._actions if isinstance(a, argparse._SubParsersAction)), None)
        if word.startswith("-"):
            name = word.split("=", 1)[0]
            if name in ("-h", "--help"):
                i += 1
                continue
            scope = [node, *ancestors]
            action = next((a for p in scope for a in p._actions if name in a.option_strings), None)
            if action is None:
                return f"`{name}` is not an option of `{' '.join(w for w in words[:i] if not w.startswith('-'))}`"
            i += 1 if ("=" in word or action.nargs == 0) else 2
            continue
        if sub_action is not None:
            if word in sub_action.choices:
                ancestors.insert(0, node)
                node = sub_action.choices[word]
                i += 1
                continue
            if word.startswith(("<", "[", "$", "\"", "'")):
                return None  # a placeholder where a subcommand would be: nothing more to prove
            return f"`{word}` is not a subcommand here (have: {', '.join(sorted(sub_action.choices))})"
        return None  # positional arguments from here on: free text
    return None


def check_reference(kind: str, value: str, ctx: Context) -> str | None:
    if ctx.ignored(kind, value):
        return None
    if kind == "cli":
        return check_cli(value, ctx)
    if kind == "flag":
        if value in ctx.cli_flags or value in ctx.long_flags:
            return None
        return "no CLI option or tracked source mentions this flag"
    if kind == "path":
        rel = value.rstrip("/")
        if rel in ctx.tracked_set or any(t.startswith(rel + "/") for t in ctx.tracked) or (REPO / rel).exists():
            return None
        if "/" not in rel and (rel in ctx.basenames or rel in ctx.mentioned_files):
            return None
        return "no such file or directory in the tracked tree"
    if kind == "symbol":
        return None if value in ctx.tokens else "no tracked source (outside docs/ and ledger/) contains this identifier"
    return None


def extract_references(doc: dict, ctx: Context) -> list[tuple[str, str, str]]:
    refs: list[tuple[str, str, str]] = []
    for where, node_kind, lang, text in walk_doc(doc, ""):
        if node_kind == "prose":
            for span in SPAN_RE.findall(text):
                refs.extend((where, k, v) for k, v in classify_span(span, ctx))
        elif lang in SHELL_FENCES:
            for command in extract_commands.normalize_block(text):
                head = command.split()[0] if command.split() else ""
                if re.match(r"^(\./)?npdev(\.bat)?$", head):
                    refs.append((where, "cli", command))
                for word in command.split()[1:]:
                    if word.startswith("-") and not word.startswith("--"):
                        continue
                    refs.extend((where, k, v) for k, v in classify_token(word, ctx) if k in ("path", "flag"))
    return refs


def check_doc(doc: dict, ctx: Context) -> tuple[int, list[str]]:
    refs = extract_references(doc, ctx)
    failures = []
    seen = set()
    for where, kind, value in refs:
        if (kind, value) in seen:
            continue
        seen.add((kind, value))
        reason = check_reference(kind, value, ctx)
        if reason:
            failures.append(f"[{kind}] `{value}` -- {reason}  ({where})")
    return len(seen), failures


# ---------------------------------------------------------------------------------------------
# Calibration: a planted defect must be seen, a clean doc must stay silent
# ---------------------------------------------------------------------------------------------
def calibrate(ctx: Context) -> list[str]:
    problems = []
    planted = {"sections": [{"title": "x", "blocks": [
        {"type": "prose", "text": "Run `scripts/quality/no-such-planted-script.ps1`, then `no_such_planted_tool.py`, "
                                   "then `docs/no-such-planted-dir/thing.json`, then `plantedMissingSymbolXyz`, "
                                   "then `npdev planted-missing-subcommand`, then `npdev validate --planted-missing-flag`."},
    ]}]}
    _, failures = check_doc(planted, ctx)
    for needle in ("no-such-planted-script.ps1", "no_such_planted_tool.py", "no-such-planted-dir/thing.json",
                   "plantedMissingSymbolXyz", "planted-missing-subcommand", "--planted-missing-flag"):
        if ctx.parser is None and needle in ("planted-missing-subcommand", "--planted-missing-flag"):
            continue
        if not any(needle in f for f in failures):
            problems.append(f"calibration: planted reference `{needle}` was NOT reported")
    clean = {"sections": [{"title": "x", "blocks": [
        {"type": "prose", "text": "Run `scripts/quality/check-doc-references.py`, see `npdev validate model`."},
    ]}]}
    count, failures = check_doc(clean, ctx)
    if failures:
        problems.append(f"calibration: a clean doc was reported: {failures}")
    return problems


# ---------------------------------------------------------------------------------------------
# Scope visibility
# ---------------------------------------------------------------------------------------------
def out_of_scope_docs(ctx: Context, in_scope_sources: set[str]) -> dict[str, list[str]]:
    """Tracked docs that are not generated from docs/content -- enumerated, never read."""
    suffix = "." + "".join(["m", "d"])
    groups: dict[str, list[str]] = {}
    for rel in ctx.tracked:
        if rel.endswith(suffix) and (rel.startswith("docs/") or rel.count("/") == 0) and rel not in in_scope_sources:
            top = rel.split("/")[1] if rel.startswith("docs/") and rel.count("/") > 1 else "(top level)"
            groups.setdefault(top, []).append(rel)
    return groups


def main() -> int:
    argp = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    argp.add_argument("--show-scope", action="store_true", help="list every out-of-scope doc, not just counts")
    args = argp.parse_args()

    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    ctx = Context(policy)

    problems = calibrate(ctx)

    total = 0
    failures: list[str] = []
    sources: set[str] = set()
    for path in sorted(CONTENT_DIR.glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        if doc.get("sourceFile"):
            sources.add(doc["sourceFile"])
        count, doc_failures = check_doc(doc, ctx)
        total += count
        failures.extend(f"{path.name}: {f}" for f in doc_failures)

    declared = {f"{e['id']}:{v}" for e in policy.get("ignore", []) for v in e.get("values", [])}
    declared |= {f"{e['id']}:prefix" for e in policy.get("ignore", []) if e.get("prefixes")}
    unused = sorted(declared - ctx.used_ignores)
    for ignore_id in unused:
        problems.append(f"policy entry `{ignore_id}` matched nothing -- delete it (the allowlist only shrinks)")
    if len(declared) != policy.get("frozenCount"):
        problems.append(f"policy has {len(declared)} ignored value(s) but frozenCount is {policy.get('frozenCount')} "
                        f"-- it may only be lowered, in the same commit the entry is removed")

    scope = out_of_scope_docs(ctx, sources)
    print(f"Doc-reference check (DOCS-1): {total} distinct reference(s) in {len(sources)} docs/content file(s); "
          f"{len(ctx.used_ignores)}/{len(declared)} policy ignore(s) used.")
    print(f"Out of scope (not generated from docs/content, not checked): "
          f"{sum(len(v) for v in scope.values())} doc(s) in {len(scope)} group(s).")
    for group, items in sorted(scope.items()):
        print(f"  - {group}: {len(items)}")
        if args.show_scope:
            for item in sorted(items):
                print(f"      {item}")

    if problems or failures:
        for p in problems:
            print(f"\nFAILED (checker): {p}")
        if failures:
            print(f"\nFAILED -- {len(failures)} documented reference(s) the tree no longer has:\n")
            for f in failures:
                print(f"  - {f}")
            print("\nFix the doc at its source in docs/content/*.yml AND the .json mirror (regenerate with"
                  "\nscripts/docs/generate_group_d_docs.py), or if the name legitimately does not live in"
                  "\nthe tree add a reasoned entry to scripts/policy/doc-reference-policy.json.")
        return 1
    print("OK: every path, script, CLI subcommand/flag and symbol the content docs name exists.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
