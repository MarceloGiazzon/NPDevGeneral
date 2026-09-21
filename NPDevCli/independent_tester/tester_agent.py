#!/usr/bin/env python3
"""REG-234 -- in-container driver for the independent cold-start tester (EXT-9).

Runs INSIDE Dockerfile.independent-tester, never on the host. Calls the Anthropic API directly via
the Tool Runner (client.beta.messages.tool_runner) -- NOT Claude Code -- so this agent never sees
NPDev_General/CLAUDE.md or any other project instructions: its only knowledge of NPDev is whatever
brief.json and the repo it clones for itself tell it. That is the entire point of this file.

NPDevCli/npdev_cli.py's run_tester() never imports this module or the `anthropic` package -- the
host-side CLI stays stdlib-only (see its own docstring). This file only ever runs where the
Dockerfile has already `pip install`-ed `anthropic` for it.

Env vars (all set by NPDevCli's run_tester(), never read from anywhere else):
  NPDEV_TESTER_ANTHROPIC_API_KEY  required.
  NPDEV_TESTER_TASK               "A" | "B" | "C" | "all" (default "all").
  NPDEV_TESTER_MAX_TURNS          loop-runaway guard, default 200 (the host's --timeout-minutes
                                   wall-clock cap is the backstop if this is set too high).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

WORK_DIR = Path("/work")
OUTPUT_DIR = WORK_DIR / "output"
BRIEF_PATH = Path(__file__).resolve().parent / "brief.json"


def _load_brief() -> dict:
    return json.loads(BRIEF_PATH.read_text(encoding="utf-8"))


def _resolve_scoped_path(path: str) -> Path:
    """Every tool call is scoped under /work -- refuse anything that would escape it, so a
    confused or adversarial tool call can't touch the container's own agent code or /etc."""
    root = WORK_DIR.resolve()
    candidate = Path(path)
    resolved = candidate.resolve() if candidate.is_absolute() else (root / candidate).resolve()
    if resolved != root and root not in resolved.parents:
        raise ValueError(f"refusing to touch a path outside /work: {path}")
    return resolved


def _run_shell_impl(cmd: str, cwd: str = "/work", timeout_s: int = 1800) -> str:
    """Real implementation behind the run_shell tool -- kept as a plain function (no `anthropic`
    import needed) so it's unit-testable without the SDK installed."""
    try:
        resolved_cwd = _resolve_scoped_path(cwd)
    except ValueError as exc:
        return json.dumps({"error": str(exc)})
    try:
        proc = subprocess.run(
            cmd, shell=True, cwd=str(resolved_cwd), capture_output=True, text=True,
            timeout=timeout_s, errors="replace",
        )
        return json.dumps({
            "exit_code": proc.returncode,
            "stdout": proc.stdout[-20000:],
            "stderr": proc.stderr[-20000:],
        })
    except subprocess.TimeoutExpired:
        return json.dumps({"error": f"command timed out after {timeout_s}s"})


def _read_file_impl(path: str) -> str:
    """Real implementation behind the read_file tool -- see _run_shell_impl's docstring."""
    try:
        resolved = _resolve_scoped_path(path)
    except ValueError as exc:
        return f"error: {exc}"
    if not resolved.is_file():
        return f"error: not a file: {path}"
    return resolved.read_text(encoding="utf-8", errors="replace")[:100000]


def _write_file_impl(path: str, content: str) -> str:
    """Real implementation behind the write_file tool -- see _run_shell_impl's docstring."""
    try:
        resolved = _resolve_scoped_path(path)
    except ValueError as exc:
        return f"error: {exc}"
    resolved.parent.mkdir(parents=True, exist_ok=True)
    resolved.write_text(content, encoding="utf-8")
    return f"wrote {len(content)} bytes to {path}"


def make_tools():
    """Built at call time (not import time) so this module can be imported for unit testing
    without requiring the `anthropic` package to be installed on the host running the tests.
    Each tool is a thin, schema-carrying wrapper around the _*_impl function above -- the
    docstrings below are what the Tool Runner turns into the tool's input_schema, so keep them
    accurate even though the logic itself lives in the impl functions."""
    from anthropic import beta_tool

    @beta_tool
    def run_shell(cmd: str, cwd: str = "/work", timeout_s: int = 1800) -> str:
        """Run a shell command and return its stdout, stderr, and exit code as JSON.

        Args:
            cmd: The shell command to execute (runs via `sh -c`).
            cwd: Working directory, must be under /work. Defaults to /work.
            timeout_s: Kill the command after this many seconds (default 1800 = 30 minutes).
        """
        return _run_shell_impl(cmd, cwd, timeout_s)

    @beta_tool
    def read_file(path: str) -> str:
        """Read a text file's contents (truncated to 100000 characters).

        Args:
            path: Path to read, absolute or relative to /work.
        """
        return _read_file_impl(path)

    @beta_tool
    def write_file(path: str, content: str) -> str:
        """Write (or overwrite) a text file, creating parent directories as needed.

        Args:
            path: Path to write, absolute or relative to /work.
            content: The full file content to write.
        """
        return _write_file_impl(path, content)

    return [run_shell, read_file, write_file]


def build_user_message(brief: dict, task: str, repo_url: str = None, repo_ref: str = None) -> str:
    """repo_url/repo_ref override brief.json's own defaults -- passed from the
    NPDEV_TESTER_REPO_URL/NPDEV_TESTER_REF env vars run_tester() sets from `npdev tester`'s
    --ref flag, so a run against a feature branch actually tells the agent to clone that branch
    instead of always saying "main" regardless of what was requested."""
    user_brief = brief["userBrief"].format(
        repoUrl=repo_url or brief["repoUrl"],
        repoRef=repo_ref or brief["repoRef"],
    )
    parts = [user_brief, ""]
    tasks = brief["tasks"]
    order = ["A", "B", "C"] if task == "all" else [task]
    for key in order:
        entry = tasks[key]
        parts.append(f"## Task {key} -- {entry['title']} ({entry['id']})")
        parts.append(entry["instruction"])
        parts.append("")
    parts.append(brief["deliverable"])
    return "\n".join(parts)


def main() -> int:
    api_key = os.environ.get("NPDEV_TESTER_ANTHROPIC_API_KEY")
    if not api_key:
        print("NPDEV_TESTER_ANTHROPIC_API_KEY is not set", file=sys.stderr)
        return 2

    task = os.environ.get("NPDEV_TESTER_TASK", "all")
    max_turns = int(os.environ.get("NPDEV_TESTER_MAX_TURNS", "200"))

    from anthropic import Anthropic

    brief = _load_brief()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    transcript_path = OUTPUT_DIR / "transcript.jsonl"

    client = Anthropic(api_key=api_key)
    user_message = build_user_message(
        brief, task,
        repo_url=os.environ.get("NPDEV_TESTER_REPO_URL"),
        repo_ref=os.environ.get("NPDEV_TESTER_REF"),
    )

    runner = client.beta.messages.tool_runner(
        model="claude-opus-5",
        max_tokens=16000,
        system=brief["systemPrompt"],
        tools=make_tools(),
        messages=[{"role": "user", "content": user_message}],
    )

    total_usage = {"input_tokens": 0, "output_tokens": 0}
    turns = 0
    with transcript_path.open("w", encoding="utf-8") as transcript:
        for message in runner:
            turns += 1
            transcript.write(json.dumps(message.to_dict()) + "\n")
            transcript.flush()
            if message.usage:
                total_usage["input_tokens"] += message.usage.input_tokens or 0
                total_usage["output_tokens"] += message.usage.output_tokens or 0
            if turns >= max_turns:
                print(f"independent-tester: hit max_turns={max_turns}, stopping loop", file=sys.stderr)
                break

    (OUTPUT_DIR / "usage.json").write_text(
        json.dumps({**total_usage, "turns": turns}, indent=2), encoding="utf-8"
    )
    print(f"independent-tester: done, {turns} turn(s), usage={total_usage}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
