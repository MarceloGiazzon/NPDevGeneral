#!/usr/bin/env python3
"""Claude Code status-line meter: live context budget, at zero token cost.

Claude Code renders the status line in the terminal; its output is NEVER sent to
the model.  So an advisory printed here costs nothing per turn, unlike the same
advice written into CLAUDE.md, which is re-billed on every request forever.

Measured 2026-08-30 over 14 days of this project: the main thread averaged
412,348 tokens of context per request, and the 35% of requests made above 500k
consumed 53.5% of all spend.  Cost is (requests x average context), so the
cheapest possible intervention is the one that tells a human to start a new
session before the next expensive call, without itself entering the context.

Reads the harness JSON payload on stdin (session_id, transcript_path, model,
workspace), tails the transcript for the most recent `usage` record, and prints
one line.  Never raises: a status line that throws is worse than no status line,
so every failure path degrades to a short, still-useful string.

Wire it up in .claude/settings.json:

    "statusLine": { "type": "command",
                    "command": "python scripts/ai/session_meter.py" }
"""
from __future__ import annotations

import json
import os
import sys

# Thresholds in tokens.  Derived from the measured spend distribution, not taste:
# below GOOD the context is cheap enough to ignore; above STOP a call costs
# roughly 3x what the same call costs in a fresh session.
GOOD = 150_000
FINE = 250_000
WARN = 400_000

# Read only the tail of the transcript.  These files reach 20 MB in a long
# session and the status line re-renders constantly.
TAIL_BYTES = 600_000

RESET = "\x1b[0m"
DIM = "\x1b[2m"
GREEN = "\x1b[32m"
CYAN = "\x1b[36m"
YELLOW = "\x1b[33m"
RED = "\x1b[31m"
BOLD = "\x1b[1m"


def _tail_lines(path: str, nbytes: int = TAIL_BYTES) -> list[str]:
    """Last complete JSONL lines of `path`, cheaply."""
    size = os.path.getsize(path)
    with open(path, "rb") as fh:
        if size > nbytes:
            fh.seek(size - nbytes)
            fh.readline()  # discard the partial line we landed inside
        blob = fh.read()
    return blob.decode("utf-8", errors="replace").splitlines()


def current_context(transcript_path: str) -> int:
    """Tokens in the most recent request's context window, or 0 if unknown.

    The context of a request is what the API billed for it: fresh input plus
    both cache tiers.  Scanning backwards means we stop at the newest record
    rather than parsing the whole session.
    """
    if not transcript_path or not os.path.exists(transcript_path):
        return 0
    try:
        lines = _tail_lines(transcript_path)
    except OSError:
        return 0
    for line in reversed(lines):
        if '"usage"' not in line:
            continue
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        msg = rec.get("message")
        if not isinstance(msg, dict):
            continue
        usage = msg.get("usage")
        if not isinstance(usage, dict):
            continue
        total = ((usage.get("input_tokens") or 0)
                 + (usage.get("cache_creation_input_tokens") or 0)
                 + (usage.get("cache_read_input_tokens") or 0))
        if total:
            return total
    return 0


def verdict(tokens: int) -> tuple[str, str, str]:
    """(colour, bar, advice) for a context size."""
    if tokens == 0:
        return DIM, "-----", ""
    if tokens < GOOD:
        filled = max(1, round(5 * tokens / GOOD))
        return GREEN, "#" * filled + "-" * (5 - filled), ""
    if tokens < FINE:
        return CYAN, "#####", ""
    if tokens < WARN:
        return YELLOW, "#####", "land this step, then /clear"
    return RED, "#####", "/clear now - calls cost ~3x here"


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        payload = {}

    transcript = payload.get("transcript_path") or ""
    model = (payload.get("model") or {})
    model_name = model.get("display_name") or model.get("id") or "claude"

    workspace = payload.get("workspace") or {}
    cwd = workspace.get("current_dir") or payload.get("cwd") or os.getcwd()
    leaf = os.path.basename(cwd.rstrip("/\\")) or cwd

    tokens = current_context(transcript)
    colour, bar, advice = verdict(tokens)

    if tokens:
        size = f"{tokens / 1000:.0f}k"
    else:
        size = "--"

    parts = [
        f"{DIM}{model_name}{RESET}",
        f"{DIM}{leaf}{RESET}",
        f"{colour}[{bar}] {size}{RESET}",
    ]
    if advice:
        parts.append(f"{colour}{BOLD}{advice}{RESET}")

    sys.stdout.write(f"{DIM} | {RESET}".join(parts))


if __name__ == "__main__":
    try:
        main()
    except Exception:  # never let the status line break the terminal
        sys.stdout.write("npdev")
