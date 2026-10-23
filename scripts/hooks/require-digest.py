#!/usr/bin/env python3
"""PreToolUse hook: refuse expensive commands that are not wrapped in the digest runner.

Wired in .claude/settings.json:

    "hooks": {
      "PreToolUse": [
        { "matcher": "Bash|PowerShell",
          "hooks": [ { "type": "command",
                       "command": "python scripts/hooks/require-digest.py" } ] }
      ]
    }

Why a hook and not a line in CLAUDE.md
-------------------------------------
A rule written as prose in CLAUDE.md is billed on every one of ~60,000 requests a
fortnight AND still fails, because it relies on an agent recalling it while under
load.  This hook is billed nothing and cannot forget.  That asymmetry is the
whole argument: a rule a machine can check belongs in a machine, not in a prompt.

What it does
------------
Reads the harness JSON on stdin, looks at the command, and exits 2 (which blocks
the call and shows stderr to the agent) when the command matches a declared
expensive pattern without going through scripts/ai/run_digest.py.

Everything it knows lives in scripts/policy/output-digest-policy.json under
`enforcement` -- add a command family there, never here.

Escape hatches, in order of preference:
  * add -DryRun / -ListOnly / --help  (declared in exemptSubstrings)
  * set NPDEV_DIGEST_BYPASS=1         (deliberate, visible, per-shell)

Fails OPEN.  A hook that blocks work because it itself broke is worse than no
hook, so any unexpected error exits 0 and lets the command through.
"""
from __future__ import annotations

import json
import os
import re
import shlex
import sys
from pathlib import Path

POLICY = Path(__file__).resolve().parents[1] / "policy" / "output-digest-policy.json"


# AIP-5 (5.1): match the PROGRAM each simple command runs, never a word that merely appears in its
# arguments. The earlier rule (strip quotes and heredocs, then substring-match what is left) still
# blocked `grep Build-NpdevApp ...` and `ls digest-logs/...gradlew...log`: a command that names a gate
# runs nothing. Launchers are looked through -- `env`, `sudo`, `VAR=x`, PowerShell's `&`, `pwsh -File`,
# `python -m`, and the command string of `bash -c` / `pwsh -Command`, which is checked recursively.
WRAPPERS = {"env", "sudo", "time", "nice", "nohup", "exec", "command", "xargs", "timeout", "&", ".", "call",
            "start", "start-process", "invoke-expression", "iex"}
INTERPRETERS = {"python", "python3", "py", "pwsh", "powershell", "bash", "sh", "zsh", "node", "cmd", "uv"}
STRING_FLAGS = {"-c", "-command", "/c", "/k"}
SCRIPT_FLAGS = {"-file", "-f", "-m"}
FLAGS_WITH_VALUE = {"-executionpolicy", "-ep", "-workingdirectory", "-wd", "-x", "-w", "-inputformat",
                    "-outputformat", "-filepath", "run"}
ASSIGNMENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")


def split_commands(text: str) -> list[str]:
    """Simple commands, split on ; & | newlines and ( ) outside quotes."""
    parts, current, quote = [], [], None
    for ch in text:
        if quote:
            current.append(ch)
            if ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
            current.append(ch)
        elif ch in ";&|\n()":
            parts.append("".join(current))
            current = []
            if ch == "&":
                current.append(" & ")  # PowerShell's call operator also starts a command
        else:
            current.append(ch)
    parts.append("".join(current))
    return [p for p in (part.strip() for part in parts) if p and p != "&"]


def tokens(segment: str) -> list[str]:
    try:
        return shlex.split(segment, posix=True)
    except ValueError:
        return segment.split()


def name(token: str) -> str:
    base = re.split(r"[\\/]", token)[-1].lower()
    return base[:-4] if base.endswith(".exe") else base


def programs(command: str, depth: int = 0) -> list[list[str]]:
    """For each simple command: the words from the program actually run onwards (lowercased)."""
    found = []
    for segment in split_commands(command):
        words = tokens(segment)
        i = 0
        while i < len(words):
            word, low = words[i], name(words[i])
            if ASSIGNMENT.match(word) or low in WRAPPERS:
                i += 1
                if low == "timeout" and i < len(words) and re.match(r"^[0-9.]+[smhd]?$", words[i]):
                    i += 1
                continue
            if low in INTERPRETERS:
                i += 1
                nested = None
                while i < len(words) and (words[i].startswith("-") or words[i].lower() in ("/c", "/k", "run")):
                    flag = words[i].lower()
                    if flag in STRING_FLAGS and i + 1 < len(words):
                        nested = " ".join(words[i + 1:])
                        break
                    if flag in SCRIPT_FLAGS:
                        i += 1
                        break
                    i += 2 if flag in FLAGS_WITH_VALUE else 1
                if nested is not None:
                    if depth < 3:
                        found.extend(programs(nested, depth + 1))
                    i = len(words)
                    break
                if i >= len(words):
                    break  # a bare interpreter: nothing it names runs
            break
        if i < len(words):
            found.append([w.lower() for w in words[i:]])
    return found


def first_expensive(command: str, patterns: list[str]) -> str | None:
    """The first declared pattern a program in `command` runs. A one-word pattern matches the
    program's own name or path; `npm test` matches the program and its next word."""
    bare = re.sub(r"<<-?\s*'?([A-Za-z_][A-Za-z0-9_]*)'?\n.*?\n\1\b", " ", command, flags=re.S)  # heredoc bodies
    for words in programs(bare):
        for pattern in patterns:
            want = pattern.lower().split()
            if len(words) >= len(want) and want[0] in words[0] and words[1:len(want)] == want[1:]:
                return pattern
    return None


def main() -> int:
    if os.environ.get("NPDEV_DIGEST_BYPASS"):
        return 0

    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # fail open

    tool = payload.get("tool_name") or ""
    if tool not in ("Bash", "PowerShell"):
        return 0

    tool_input = payload.get("tool_input") or {}
    command = str(tool_input.get("command") or "")
    if not command.strip():
        return 0

    try:
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        enforce = policy["enforcement"]
    except Exception:
        return 0  # fail open

    for ok in enforce.get("exemptSubstrings", []):
        if ok.lower() in command.lower():
            return 0

    hit = first_expensive(command, enforce.get("expensiveCommands", []))
    if not hit:
        return 0

    # Blocked. stderr is what the agent is shown.
    sys.stderr.write(
        f"BLOCKED: '{hit}' is a declared expensive command and is not wrapped in the "
        f"digest runner.\n\n{enforce['message']}\n\n"
        f"(policy: scripts/policy/output-digest-policy.json -> enforcement)\n"
    )
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        sys.exit(0)  # fail open, always
