#!/usr/bin/env python3
"""SessionStart hook: put the last handoff in front of the agent automatically.

Wired in .claude/settings.json:

    "hooks": {
      "SessionStart": [
        { "hooks": [ { "type": "command",
                       "command": "python scripts/hooks/session-start-handoff.py" } ] }
      ]
    }

Why this exists
---------------
Close-Session.ps1 records where a session stopped, and Prepare-Session.ps1 reads
it back -- but only if somebody remembers to run it.  That makes resuming a
category-3 rule: it depends on a human or an agent recalling a habit, which is
exactly the class of rule that fails under load.

This hook removes the recall step.  Whatever is printed here is added to the
session's context at startup, so a fresh session already knows where the last one
stopped without anyone typing anything.

Kept deliberately small.  This text is billed on every request for the whole
session, so it carries the resume point and nothing else; the full record stays
in ledger/session-state/ for /hist to read on demand.

Fails silent.  No handoff, unreadable JSON, any error at all -> print nothing and
exit 0.  A session that starts is always better than a session that errors.

After /clear it also runs the /prep work.  Marcelo's "c.." routine is close ->
/clear -> /prep; /clear cannot be triggered by the agent, but a session started
by it arrives here with source "clear", so Prepare-Session.ps1 runs now and its
orientation is appended -- the separate /prep becomes unnecessary.
"""
from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STATE = REPO / "ledger" / "session-state" / "current.json"
PREP = REPO / "scripts" / "ai" / "Prepare-Session.ps1"
STALE_HOURS = 72
PREP_TIMEOUT_SECONDS = 100


def started_by_clear() -> bool:
    try:
        return json.loads(sys.stdin.read() or "{}").get("source") == "clear"
    except Exception:
        return False


def prep_output() -> str:
    try:
        result = subprocess.run(
            ["pwsh", "-NoProfile", "-File", str(PREP)], cwd=str(REPO),
            capture_output=True, text=True, timeout=PREP_TIMEOUT_SECONDS)
        return result.stdout.strip()
    except Exception:
        return ""


def main() -> int:
    from_clear = started_by_clear()
    if not STATE.exists():
        return 0

    try:
        rec = json.loads(STATE.read_text(encoding="utf-8"))
    except Exception:
        return 0

    next_step = (rec.get("nextStep") or "").strip()
    if not next_step:
        return 0

    summary = (rec.get("summary") or "").strip()
    plan = (rec.get("plan") or "").strip()
    blocked = (rec.get("blocked") or "").strip()
    verified = (rec.get("verified") or "").strip()
    head = (rec.get("head") or "").strip()

    age_note = ""
    try:
        closed = datetime.fromisoformat(str(rec["closedAt"]).replace("Z", "+00:00"))
        if closed.tzinfo is None:
            closed = closed.replace(tzinfo=timezone.utc)
        hours = (datetime.now(timezone.utc) - closed).total_seconds() / 3600
        if hours > STALE_HOURS:
            age_note = (f"  STALE: written {hours/24:.0f} days ago -- confirm with the user "
                        f"before acting on it.\n")
        else:
            age_note = f"  (written {hours:.0f}h ago, at {head})\n"
    except Exception:
        pass

    out = ["Handoff from the previous session (ledger/session-state/current.json):"]
    if age_note:
        out.append(age_note.rstrip("\n"))
    if summary:
        out.append(f"  did:      {summary}")
    out.append(f"  NEXT:     {next_step}")
    if plan:
        out.append(f"  plan:     {plan}")
    if blocked:
        out.append(f"  BLOCKED:  {blocked}")
    if verified:
        out.append(f"  verified: {verified}")
    out.append("")
    out.append("Do NOT reconstruct this from git history or by reading files. If the user's "
               "first message is a bare 'continue' or similar, this is what they mean. "
               "NEXT is the default action after a prep: state it in one sentence and "
               "start it (checking first that it is not already done) rather than asking "
               "whether to proceed.")

    if from_clear:
        prep = prep_output()
        if prep:
            out.append("")
            out.append("This session started from /clear, so /prep already ran -- its output "
                       "follows. Do not ask the user to run /prep. Treat it exactly as a /prep: "
                       "on the user's first message, state NEXT in one sentence and start it.")
            out.append(prep)

    sys.stdout.write("\n".join(out) + "\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        sys.exit(0)
