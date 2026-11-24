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
import os
import re
import shutil
import subprocess
import sys
import time
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


def ptrack_exe() -> str | None:
    """The ptrack executable, or None when this machine has none (then there is nothing to report)."""
    found = shutil.which("ptrack")
    if found:
        return found
    local = os.environ.get("LOCALAPPDATA")
    for cand in ([Path(local) / "Programs" / "ptrack" / "ptrack.exe"] if local else []) + [Path.home() / ".local" / "bin" / "ptrack"]:
        if cand.is_file():
            return str(cand)
    return None


def ptrack_coverage_gap(settings: dict, tool: str = "PowerShell") -> str | None:
    """Why the ptrack hook in this settings.json would NOT see `tool`, or None when it does (or none is installed
    here -- another settings file may carry it). Claude Code matchers are regexes over the tool name."""
    entries = [e for e in ((settings.get("hooks") or {}).get("PreToolUse") or [])
               if any("ptrack" in str(h.get("command", "")).lower() for h in (e.get("hooks") or []))]
    for entry in entries:
        matcher = entry.get("matcher")
        if matcher is None or matcher == "" or re.search(rf"^(?:{matcher})$", tool):
            return None
    if entries:
        return f"its matcher {entries[0].get('matcher')!r} does not match the {tool} tool"
    return None


def tracker_notice() -> str:
    """What ptrack -- the owner's 'never be blind on a long process' tracker -- knows RIGHT NOW, so a new session starts
    informed instead of rediscovering it (2026-11-24: a 13 h 50 min run burned CPU unseen and the next session found it
    by accident). Silent when there is nothing to say: this text is billed on every request."""
    exe = ptrack_exe()
    if not exe:
        return ""
    lines = []
    try:
        jobs = json.loads(subprocess.run([exe, "list", "--json"], capture_output=True, text=True, timeout=8).stdout or "[]")
        now_ms = time.time() * 1000
        for job in [j for j in jobs if j.get("state") in ("running", "paused")][:6]:
            hours = max(0.0, (now_ms - float(job.get("started_ms") or now_ms)) / 3.6e6)
            lines.append(f"  RUNNING {job.get('id')} [{job.get('activity') or job.get('state')}] {hours:.1f} h  "
                         f"{str(job.get('label') or job.get('command') or '')[:70]}")
    except Exception:
        pass
    gaps = []
    for path in (Path.home() / ".claude" / "settings.json", REPO / ".claude" / "settings.json", REPO / ".claude" / "settings.local.json"):
        try:
            gap = ptrack_coverage_gap(json.loads(path.read_text(encoding="utf-8")))
        except Exception:
            continue
        if gap:
            gaps.append(f"  WARNING ({path.name}): the ptrack hook is blind to the PowerShell tool -- {gap}. "
                        "A long run started from PowerShell is invisible to the owner.")
    if not lines and not gaps:
        return ""
    head = ["ptrack (the owner's tracker -- never be blind on a long process):"] + lines + gaps
    head.append("  Long runs: Bash tool + run_in_background + scripts/ai/run_digest.py; `ptrack list` must show the job.")
    return "\n".join(head)


def main() -> int:
    try:
        notice = tracker_notice()
    except Exception:
        notice = ""
    if notice:
        sys.stdout.write(notice + "\n\n")
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
