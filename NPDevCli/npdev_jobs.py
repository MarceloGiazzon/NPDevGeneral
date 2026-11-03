"""Long-running work you can see without asking (JOBS-1..4): job records, `npdev jobs`, the board.

WHY THIS EXISTS
---------------
Every "status?" sent to an AI agent re-bills the whole conversation, and the commands that take longest (the
quality gates, rebuilds, benchmarks) are exactly the ones people ask about. The cheapest status check is one that
never enters the chat. This module is the shared truth behind three such views -- the Claude Code status line, the
Manager's Activity > Tasks screen and a static `jobs board` page -- and it is also what catches the failure the
CLAUDE.md alert describes (a completion notification that never came: 6 h 22 min lost): a job whose writer died
shows up as LOST, in red, where the owner can see it.

THE RULE (Manager SPEC 1.1): no decision worth making lives in the UI that the CLI does not already make. WHETHER
a job is lost is decided HERE, once; the Manager and the status line only display it.

One record per job at <BuildRoot>/jobs/<jobId>.json (schema schemas/ai/job-record.schema.json), rewritten whole and
atomically (write a .tmp, then rename) by whoever launches it -- scripts/ai/run_digest.py is the choke point every long
agent command already passes through, so it is the writer; the Manager's own streaming runs may write records too.
`jobId` is the digest log's stem, so a record and its log pair up by name.
"""
from __future__ import annotations

import html
import json
import os
import re
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import npdev_redact

SCHEMA = "npdev-job.v1"
LOST_AFTER_SECONDS = 60          # running + writer gone + heartbeat older than this = lost
HEARTBEAT_SECONDS = 15
STATUSES = ("running", "paused", "succeeded", "failed", "cancelled", "lost")
FINISHED = ("succeeded", "failed", "cancelled", "lost")
EXCERPT_CHARS = 300


# --------------------------------------------------------------------------------------- time, paths

def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def jobs_dir(build_root: Path) -> Path:
    d = Path(build_root) / "jobs"
    d.mkdir(parents=True, exist_ok=True)
    return d


def record_path(build_root: Path, job_id: str) -> Path:
    if not re.fullmatch(r"[0-9]{8}T[0-9]{6}Z-[A-Za-z0-9._-]{1,60}", job_id):
        raise ValueError(f"not a job id: {job_id!r}")
    return Path(build_root) / "jobs" / f"{job_id}.json"


# ------------------------------------------------------------------------------------- the process

def pid_alive(pid: int | None) -> bool:
    if not pid or pid <= 0:
        return False
    if os.name == "nt":
        try:
            import ctypes
            from ctypes import wintypes
            kernel32 = ctypes.windll.kernel32
            kernel32.OpenProcess.restype = wintypes.HANDLE
            handle = kernel32.OpenProcess(0x1000, False, int(pid))          # PROCESS_QUERY_LIMITED_INFORMATION
            if not handle:
                return False
            try:
                code = wintypes.DWORD()
                if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                    return False
                return code.value == 259                                    # STILL_ACTIVE
            finally:
                kernel32.CloseHandle(handle)
        except Exception:
            return False
    try:
        os.kill(int(pid), 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def kill_tree(pid: int) -> bool:
    """Stop a process and everything it started. Windows: taskkill /T /F. Unix: the process group."""
    if not pid_alive(pid):
        return False
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True, timeout=30)
        else:
            try:
                os.killpg(os.getpgid(pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                os.kill(pid, signal.SIGKILL)
    except Exception:
        return False
    for _ in range(20):
        if not pid_alive(pid):
            return True
        time.sleep(0.25)
    return not pid_alive(pid)


# ------------------------------------------------------------------------------------ the records

def write_record(build_root: Path, rec: dict) -> None:
    """Atomic: a reader never sees half a record."""
    jobs_dir(build_root)                      # the folder may not exist yet: the first record creates it
    path = record_path(build_root, rec["jobId"])
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(rec, indent=2, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, path)


def read_record(build_root: Path, job_id: str) -> dict | None:
    try:
        return json.loads(record_path(build_root, job_id).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def redact_argv(argv: list[str]) -> list[str]:
    return [npdev_redact.redact_text(a, classes=("secret",))[0] for a in argv]


def launcher_info(env: dict | None = None) -> dict:
    env = os.environ if env is None else env
    if env.get("NPDEV_JOB_LAUNCHER") == "manager":
        return {"kind": "manager"}
    if env.get("CI", "").lower() == "true":
        return {"kind": "ci"}
    if env.get("CLAUDE_CODE_SESSION_ID"):
        return {"kind": "claude-code", "sessionId": env["CLAUDE_CODE_SESSION_ID"]}
    return {"kind": "terminal"}


def new_record(job_id: str, title: str, argv: list[str], cwd: str, log_path: str, pid: int | None,
               writer_pid: int | None, launcher: dict) -> dict:
    now = now_iso()
    kind = launcher.get("kind")
    return {
        "schema": SCHEMA, "jobId": job_id, "title": title[:120], "argv": redact_argv(argv), "cwd": cwd,
        "pid": pid, "writerPid": writer_pid, "status": "running", "exitCode": None, "startedAt": now,
        "endedAt": None, "heartbeatAt": now, "logPath": log_path,
        "progress": {"lines": 0, "lastLine": ""}, "launcher": launcher,
        # only the machine that started a job can stop or restart it
        "actions": ["openLog"] + (["stop", "restart"] if kind in ("claude-code", "terminal", "manager") else []),
    }


def heartbeat(build_root: Path, job_id: str, lines: int, last_line: str) -> None:
    rec = read_record(build_root, job_id)
    if not rec or rec.get("status") not in ("running", "paused"):
        return
    rec["heartbeatAt"] = now_iso()
    rec["progress"] = {"lines": lines, "lastLine": npdev_redact.redact_text(last_line, classes=("secret",))[0][:300]}
    write_record(build_root, rec)


def finish(build_root: Path, job_id: str, exit_code: int | None) -> None:
    """Record the end -- unless someone already did: `stop` writes `cancelled` and the writer, seeing the
    killed process exit non-zero a moment later, must not turn that into `failed`."""
    rec = read_record(build_root, job_id)
    if not rec or rec.get("status") in FINISHED:
        return
    rec["status"] = "succeeded" if exit_code == 0 else "failed"
    rec["exitCode"] = exit_code
    rec["endedAt"] = now_iso()
    rec["heartbeatAt"] = rec["endedAt"]
    write_record(build_root, rec)


def effective_status(rec: dict, now: float | None = None) -> str:
    """THE decision: a record that says running whose writer is gone and whose heartbeat has gone quiet for over a
    minute is `lost` -- the writer died and nobody wrote an end. (The wrapped command may still be running; the
    point is that nothing is watching it any more.)"""
    status = rec.get("status", "lost")
    if status not in ("running", "paused"):
        return status
    now = time.time() if now is None else now
    beat = parse_iso(rec.get("heartbeatAt")) or parse_iso(rec.get("startedAt")) or 0
    writer = rec.get("writerPid") or rec.get("pid")
    if not pid_alive(writer) and now - beat > LOST_AFTER_SECONDS:
        return "lost"
    return status


def view(rec: dict) -> dict:
    """The record plus what the CLI decided about it."""
    out = dict(rec)
    out["status"] = effective_status(rec)
    out["recordedStatus"] = rec.get("status")
    started = parse_iso(rec.get("startedAt"))
    end = parse_iso(rec.get("endedAt")) or (parse_iso(rec.get("heartbeatAt")) if out["status"] == "lost" else time.time())
    out["elapsedSeconds"] = round(max(0.0, (end or time.time()) - started), 1) if started else None
    return out


def list_jobs(build_root: Path, limit: int = 50, only_active: bool = False, max_age_seconds: float | None = None) -> list[dict]:
    rows = []
    for path in jobs_dir(build_root).glob("*.json"):
        try:
            if max_age_seconds is not None and time.time() - path.stat().st_mtime > max_age_seconds:
                continue            # a running job rewrites its record every 15 s, so an old file is not running
            rec = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(rec, dict) and rec.get("schema") == SCHEMA:
            rows.append(view(rec))
    rows.sort(key=lambda r: r.get("startedAt") or "", reverse=True)
    if only_active:
        rows = [r for r in rows if r["status"] in ("running", "paused", "lost")]
    return rows[:limit]


def summary(build_root: Path) -> dict:
    """One line's worth for the status line: how many running, how many lost, the newest running job."""
    # The status line asks this on every redraw: read only records touched in the last 25 hours (a running job is
    # rewritten every 15 s, and a loss only counts for a day), never the whole history.
    rows = list_jobs(build_root, limit=200, max_age_seconds=25 * 3600)
    running = [r for r in rows if r["status"] == "running"]
    # a lost job stays visible for a day, then stops nagging (its record and log are still there)
    recent = time.time() - 24 * 3600
    lost = [r for r in rows if r["status"] == "lost" and (parse_iso(r.get("startedAt")) or 0) > recent]
    newest = running[0] if running else None
    return {"running": len(running), "lost": len(lost),
            "newest": ({"title": newest["title"], "elapsedSeconds": newest["elapsedSeconds"],
                        "lastLine": (newest.get("progress") or {}).get("lastLine", "")} if newest else None)}


# ---------------------------------------------------------------------------------------- actions

def stop(build_root: Path, job_id: str) -> dict:
    rec = read_record(build_root, job_id)
    if rec is None:
        return {"ok": False, "error": f"no such job: {job_id}"}
    if "stop" not in (rec.get("actions") or []):
        return {"ok": False, "error": "this job cannot be stopped from here (it was started elsewhere)"}
    status = effective_status(rec)
    if status in ("succeeded", "failed", "cancelled"):
        return {"ok": False, "error": f"the job already ended ({status})"}
    killed = kill_tree(int(rec["pid"])) if rec.get("pid") else False
    rec["status"] = "cancelled"
    rec["endedAt"] = now_iso()
    rec["heartbeatAt"] = rec["endedAt"]
    write_record(build_root, rec)
    return {"ok": True, "jobId": job_id, "processStopped": killed, "status": "cancelled"}


def restart(build_root: Path, job_id: str, repo_root: Path) -> dict:
    """Run the same command again through run_digest.py in the background (a NEW job)."""
    rec = read_record(build_root, job_id)
    if rec is None:
        return {"ok": False, "error": f"no such job: {job_id}"}
    if "restart" not in (rec.get("actions") or []):
        return {"ok": False, "error": "this job cannot be restarted from here (it was started elsewhere)"}
    argv = rec.get("argv") or []
    if any("<redacted" in a for a in argv):
        return {"ok": False, "error": "the command contained a secret that was hidden in the record, so it cannot be "
                                      "replayed; run it again by hand"}
    if effective_status(rec) in ("running", "paused"):
        return {"ok": False, "error": "the job is still running; stop it first"}
    digest = Path(repo_root) / "scripts" / "ai" / "run_digest.py"
    kwargs: dict = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS
    else:
        kwargs["start_new_session"] = True
    env = dict(os.environ)
    env["NPDEV_BUILD_ROOT"] = str(build_root)
    proc = subprocess.Popen([sys.executable, str(digest), "--keep-going", "--", *argv], cwd=rec.get("cwd") or None,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, env=env, **kwargs)
    return {"ok": True, "startedFrom": job_id, "launcherPid": proc.pid}


# ------------------------------------------------- JOBS-4: which request started this job

def find_transcript(session_id: str, home: Path | None = None) -> Path | None:
    base = (home or Path.home()) / ".claude" / "projects"
    if not session_id or not re.fullmatch(r"[A-Za-z0-9-]{8,64}", session_id) or not base.is_dir():
        return None
    hits = list(base.glob(f"*/{session_id}.jsonl"))
    return hits[0] if hits else None


def _text_of(message: dict) -> str:
    content = message.get("content")
    if isinstance(content, str):
        return content
    parts = []
    for block in content or []:
        if isinstance(block, dict) and block.get("type") == "text":
            parts.append(str(block.get("text", "")))
    return "\n".join(parts)


def _norm_ws(text: str) -> str:
    return " ".join(str(text).split())


def _squash(text: str) -> str:
    """For matching a recorded argv against the shell command that produced it: the shell removed the quotes the
    command was typed with, so compare with quotes and whitespace gone from both sides."""
    return re.sub(r"[\s'\"`]+", "", str(text))


def prompt_excerpt(rec: dict, home: Path | None = None) -> dict:
    """Read-only. Finds the LAST tool call before the job started whose command contains this job's command, and the
    closest earlier user message, then returns both as redacted 300-character excerpts. The transcript is never
    modified, and only a claude-code job (which carries a session id) has one."""
    launcher = rec.get("launcher") or {}
    path = Path(launcher["transcriptPath"]) if launcher.get("transcriptPath") else find_transcript(launcher.get("sessionId", ""), home)
    if path is None or not path.is_file():
        return {"found": False, "reason": "no transcript for this job" if launcher.get("kind") == "claude-code"
                else "only jobs started by an AI agent have a prompt"}
    started = parse_iso(rec.get("startedAt")) or time.time()
    needle = _squash(" ".join(rec.get("argv") or []))
    last_user, command_user, command_text = "", None, None
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                stamp = parse_iso(entry.get("timestamp"))
                if stamp is not None and stamp > started + 5:
                    break
                message = entry.get("message") if isinstance(entry.get("message"), dict) else {}
                if entry.get("type") == "user" and message.get("role") == "user":
                    text = _text_of(message).strip()
                    if text and not text.startswith("<system-reminder>"):
                        last_user = text
                elif entry.get("type") == "assistant":
                    for block in message.get("content") or []:
                        if isinstance(block, dict) and block.get("type") == "tool_use":
                            cmd = (block.get("input") or {}).get("command")
                            if isinstance(cmd, str) and needle and needle in _squash(cmd):
                                command_text, command_user = cmd, last_user
    except OSError:
        return {"found": False, "reason": "the transcript could not be read"}

    def clean(text: str | None) -> str | None:
        if text is None:
            return None
        return npdev_redact.redact_text(_norm_ws(text), classes=("secret", "privacy"))[0][:EXCERPT_CHARS]

    if command_text is None:
        return {"found": False, "reason": "no tool call before the job started contained its command"}
    return {"found": True, "prompt": clean(command_user), "command": clean(command_text)}


# ------------------------------------------------------------------------------------- the board

def render_board(rows: list[dict], with_prompts: bool = True, home: Path | None = None) -> str:
    e = html.escape
    colour = {"running": "#1a73e8", "succeeded": "#1e7e34", "failed": "#b42323", "cancelled": "#6b7280",
              "lost": "#b42323", "paused": "#a3720a"}
    word = {"running": "Running", "succeeded": "Done", "failed": "Failed", "cancelled": "Stopped", "lost": "LOST",
            "paused": "Paused"}
    glyph = {"running": "▶", "succeeded": "✓", "failed": "✕", "cancelled": "■", "lost": "!", "paused": "‖"}
    out = ["<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=10><title>NPDev tasks</title>",
           "<style>body{font:15px system-ui,sans-serif;margin:24px;color:#1a1a1a;background:#fff}"
           "table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top}"
           "small{color:#555;display:block}.st{font-weight:700}.lost{background:#fdecec}</style>",
           f"<h1>NPDev tasks</h1><p><small>Refreshes every 10 seconds. Written {e(now_iso())}.</small></p>",
           "<table><tr><th>Task</th><th>Started by</th><th>Status</th><th>Started</th><th>Time</th><th>Last line</th></tr>"]
    for r in rows:
        status = r["status"]
        launcher = r.get("launcher") or {}
        who = {"claude-code": "AI agent", "manager": "Manager", "terminal": "Terminal", "ci": "CI"}.get(launcher.get("kind"), "?")
        prompt = ""
        if with_prompts and launcher.get("kind") == "claude-code":
            info = prompt_excerpt(r, home)
            if info.get("found") and info.get("prompt"):
                prompt = f"<small>for: {e(info['prompt'])}</small>"
        secs = r.get("elapsedSeconds")
        took = "" if secs is None else (f"{int(secs // 60)}m {int(secs % 60)}s" if secs >= 60 else f"{int(secs)}s")
        out.append(f"<tr class='{'lost' if status == 'lost' else ''}'><td><b>{e(r.get('title', ''))}</b>"
                   f"<small>{e(' '.join(r.get('argv') or [])[:140])}</small></td><td>{e(who)}{prompt}</td>"
                   f"<td class=st style='color:{colour.get(status, '#000')}'>{glyph.get(status, '')} {word.get(status, status)}</td>"
                   f"<td>{e(str(r.get('startedAt', '')))}</td><td>{e(took)}</td>"
                   f"<td>{e((r.get('progress') or {}).get('lastLine', ''))}</td></tr>")
    out.append("</table>")
    return "\n".join(out)


def write_board(build_root: Path, limit: int = 50, home: Path | None = None) -> Path:
    path = jobs_dir(build_root) / "board.html"
    path.write_text(render_board(list_jobs(build_root, limit), home=home), encoding="utf-8")
    return path


# ------------------------------------------------------------------------------------------ the CLI

def run(args, build_root: Path, repo_root: Path) -> int:
    """`npdev jobs list|show|stop|restart|board [--json]`. Output is always one JSON object when --json is given."""
    cmd = args.jobs_command
    as_json = bool(getattr(args, "json", False))

    def emit(obj: dict, human: str) -> int:
        print(json.dumps(obj, indent=2, ensure_ascii=False) if as_json else human)
        return 0 if obj.get("ok", True) else 2

    if cmd == "list":
        rows = list_jobs(build_root, args.limit, getattr(args, "active", False))
        lines = [f"{r['status']:<10} {r.get('title', '')[:50]:<50} {r['jobId']}" for r in rows] or ["no jobs recorded"]
        return emit({"ok": True, "schema": "npdev-jobs-list.v1", "jobs": rows, "summary": summary(build_root)}, "\n".join(lines))
    if cmd == "summary":
        s = summary(build_root)
        return emit({"ok": True, **s}, f"{s['running']} running, {s['lost']} lost")
    if cmd == "board":
        path = write_board(build_root, args.limit)
        return emit({"ok": True, "board": str(path)}, str(path))
    job_id = args.job_id
    if cmd == "show":
        rec = read_record(build_root, job_id)
        if rec is None:
            return emit({"ok": False, "error": f"no such job: {job_id}"}, f"no such job: {job_id}")
        v = view(rec)
        v["prompt"] = prompt_excerpt(rec)
        return emit({"ok": True, "job": v}, json.dumps(v, indent=2, ensure_ascii=False))
    if cmd == "stop":
        r = stop(build_root, job_id)
        return emit(r, "stopped" if r["ok"] else r["error"])
    if cmd == "restart":
        r = restart(build_root, job_id, repo_root)
        return emit(r, "restarted" if r["ok"] else r["error"])
    return emit({"ok": False, "error": f"unknown jobs command {cmd!r}"}, f"unknown jobs command {cmd!r}")
