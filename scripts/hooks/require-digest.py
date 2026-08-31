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
import re
import os
import sys
from pathlib import Path

POLICY = Path(__file__).resolve().parents[1] / "policy" / "output-digest-policy.json"


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

    # Match only against text that could actually BE a command. Anything the shell
    # treats as literal data -- quoted arguments, heredoc bodies -- is stripped
    # first, because a command that merely MENTIONS an expensive tool runs nothing.
    #
    # Both cases were found the honest way, by this hook blocking real work: a
    # Close-Session call whose -NextStep text said "gradlew", and a git commit
    # whose heredoc message discussed run-ai-knowledge-gate. In a repo whose
    # commit messages describe its own gates, that is not an edge case.
    bare = re.sub(r"<<-?\s*'?([A-Za-z_][A-Za-z0-9_]*)'?\n.*?\n\1\b", " ", command,
                  flags=re.S)          # heredoc bodies
    bare = re.sub(r"'[^']*'|\"[^\"]*\"", " ", bare)   # quoted arguments
    low = bare.lower()

    for ok in enforce.get("exemptSubstrings", []):
        if ok.lower() in command.lower():
            return 0

    hit = next((p for p in enforce.get("expensiveCommands", []) if p.lower() in low), None)
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
