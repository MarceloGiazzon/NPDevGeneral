#!/usr/bin/env python3
"""Run a command; write the full log to disk, print only its digest.

    python scripts/ai/run_digest.py -- ./gradlew :dsl:test
    python scripts/ai/run_digest.py --family npdev-gate -- pwsh -NoProfile -File scripts/quality/run-all-gates.ps1

The contract, in one line: *result first, drill down on failure only.*

Cost is (requests x average context).  A 13.5-minute gate run streamed whole into
an AI session raises the price of every later call in that session -- the output
is paid for once when produced and then again on every subsequent turn, because
each request re-bills the entire context.  The failing check names and their
assertion lines are the entire signal.  This wrapper keeps the full log on disk,
addressable and greppable, and puts a bounded digest on stdout.

Pattern knowledge lives in scripts/policy/output-digest-policy.json, never here:
to teach it a new tool, add a family to that file.

Exit code is the wrapped command's own, so this composes inside gates and CI.

A wrapped command is never a black box.  The digest keeps the output off the terminal, so while it runs this
prints `[run] ...` at once and `[hb] ...` every 30 s (RUNNING / QUIET = slow but computing / STALLED? = no output
and no CPU), which is what a background job's log -- `ptrack tail -f` -- shows.  Tune it in the policy's
`heartbeat` section, with $NPDEV_HEARTBEAT_SECONDS, or per run with --heartbeat N (0 = off).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

POLICY = Path(__file__).resolve().parents[1] / "policy" / "output-digest-policy.json"
OUTPUT_DRAIN_GRACE_SECONDS = 5


def repo_root() -> Path:
    """The directory holding the three module dirs.

    Identified by CONTENTS, never by directory name -- REG-144: GitHub checks
    this repo out as `NPDevGeneral`, so any walk looking for a directory literally
    named `NPDev_General` falls through to a different fallback per call site.
    """
    here = Path(__file__).resolve()
    for cand in [here.parent.parent.parent, *here.parents]:
        if all((cand / m).is_dir() for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return cand
    return here.parent.parent.parent


def build_root() -> Path:
    env = os.environ.get("NPDEV_BUILD_ROOT")
    return Path(env) if env else repo_root().parent / "Build"


def log_dir() -> Path:
    """Logs are build artifacts: they belong in the Build root, never the repo."""
    d = build_root() / "digest-logs"
    d.mkdir(parents=True, exist_ok=True)
    return d


def jobs_module():
    """NPDevCli/npdev_jobs.py -- the job records (JOBS-1). Optional: a missing or broken module must never stop
    the command from running, so every use below is wrapped."""
    cli = str(repo_root() / "NPDevCli")
    if cli not in sys.path:
        sys.path.insert(0, cli)
    try:
        import npdev_jobs
        return npdev_jobs
    except Exception:
        return None


def load_policy() -> dict:
    with open(POLICY, encoding="utf-8") as fh:
        return json.load(fh)


def pick_family(policy: dict, command: str, forced: str | None) -> dict:
    fams = policy["families"]
    if forced:
        for f in fams:
            if f["name"] == forced:
                return f
        raise SystemExit(f"unknown family '{forced}'; have: {', '.join(f['name'] for f in fams)}")
    low = command.lower()
    for f in fams:
        if any(d.lower() in low for d in f.get("detect", [])):
            return f
    return next(f for f in fams if f["name"] == "generic")


def compile_all(pats: list[str]) -> list[re.Pattern]:
    out = []
    for p in pats:
        try:
            out.append(re.compile(p))
        except re.error:
            pass
    return out


def any_match(pats: list[re.Pattern], line: str) -> bool:
    return any(p.search(line) for p in pats)


def digest(lines: list[str], fam: dict, defaults: dict, failed: bool) -> list[str]:
    """Select the lines worth putting in front of a reader."""
    verdict = compile_all(fam.get("verdict", []))
    failure = compile_all(fam.get("failure", []))
    noise = compile_all(fam.get("noise", []))

    before = defaults["contextBefore"]
    after = defaults["contextAfter"]
    cap = defaults["failureLines"] if failed else defaults["successLines"]

    keep: set[int] = set()

    # Verdict lines always survive -- they are the answer to "what happened".
    for i, ln in enumerate(lines):
        if any_match(verdict, ln):
            keep.add(i)

    # On failure, pull the failing lines and a window around each.
    if failed:
        anchors = [i for i, ln in enumerate(lines) if any_match(failure, ln)]
        # Cluster: consecutive stack frames are one failure, not thirty.
        for i in anchors:
            for j in range(max(0, i - before), min(len(lines), i + after + 1)):
                keep.add(j)

    if not keep:
        # Nothing matched; fall back to the tail, which is where verdicts live.
        keep = set(range(max(0, len(lines) - cap), len(lines)))

    ordered = sorted(keep)

    # Drop declared noise, then cap. Keep the LAST `cap` lines: for both a build
    # and a test run the conclusion is at the end, and a truncated head is more
    # useful than a truncated tail.
    ordered = [i for i in ordered if not any_match(noise, lines[i])]
    truncated = len(ordered) > cap
    if truncated:
        ordered = ordered[-cap:]

    out: list[str] = []
    prev = None
    maxlen = defaults["maxLineLength"]
    for i in ordered:
        if prev is not None and i > prev + 1:
            out.append(f"  ... {i - prev - 1} lines")
        ln = lines[i].rstrip()
        if len(ln) > maxlen:
            ln = ln[:maxlen] + " ..."
        out.append(f"  {ln}")
        prev = i
    if truncated:
        out.insert(0, "  (digest capped; full log has everything)")
    return out


# ------------------------------------------------------------------------------------------ the pulse
# A wrapped command must never be a black box. The digest keeps its output off the terminal by design, so without
# a pulse a background job's log stays at 0 bytes until the very end and nobody can tell slow from dead.
# Values live in scripts/policy/output-digest-policy.json -> "heartbeat"; these are only the fallback if that
# section is missing.
HEARTBEAT_FALLBACK = {"stdoutSeconds": 30.0, "quietAfterSeconds": 60.0, "stalledAfterSeconds": 300.0,
                      "cpuActiveRatio": 0.01, "lastLineChars": 60}
HEARTBEAT_ENV = {"stdoutSeconds": "NPDEV_HEARTBEAT_SECONDS", "quietAfterSeconds": "NPDEV_HEARTBEAT_QUIET_AFTER",
                 "stalledAfterSeconds": "NPDEV_HEARTBEAT_STALLED_AFTER"}


def heartbeat_settings(policy: dict, cli: dict, env: dict | None = None) -> dict:
    """policy section < environment < command line. A bad value is ignored, never fatal."""
    env = os.environ if env is None else env
    cfg = dict(HEARTBEAT_FALLBACK)
    cfg.update({k: v for k, v in (policy.get("heartbeat") or {}).items() if k in HEARTBEAT_FALLBACK})
    for key, name in HEARTBEAT_ENV.items():
        raw = env.get(name)
        if raw not in (None, ""):
            try:
                cfg[key] = float(raw)
            except ValueError:
                pass
    for key, value in cli.items():
        if value is not None:
            cfg[key] = float(value)
    return cfg


class TreeCpu:
    """CPU seconds burned by the command's whole process tree, or None when psutil is missing.

    A child that exits takes its CPU time out of the live tree, so the last reading per process is kept: the
    total only ever grows, which is what "is it still computing?" needs."""

    def __init__(self, pid: int):
        self._seen: dict[tuple[int, float], float] = {}
        try:
            import psutil
            self._psutil = psutil
            self._root = psutil.Process(pid)
        except Exception:
            self._psutil = None

    @property
    def available(self) -> bool:
        return self._psutil is not None

    def total(self) -> float | None:
        if self._psutil is None:
            return None
        try:
            procs = [self._root, *self._root.children(recursive=True)]
        except Exception:
            procs = []
        for p in procs:
            try:
                t = p.cpu_times()
                self._seen[(p.pid, p.create_time())] = t.user + t.system
            except Exception:
                continue  # it exited between listing and reading
        return sum(self._seen.values())


class Pulse:
    """Decides RUNNING / QUIET / STALLED? from two clocks: the last output line and the last time CPU advanced.

    RUNNING   output within quietAfterSeconds
    QUIET     no output for a while but the tree still burns CPU: slow, not dead (or CPU unknown: cannot tell)
    STALLED?  no output AND no CPU for stalledAfterSeconds: probably hung. Advisory -- nothing is ever killed
              (a Gradle daemon is not always a descendant, so its work can look like silence)."""

    def __init__(self, cfg: dict, now: float):
        self.cfg = cfg
        self.started = now
        self.last_output = now
        self.last_active = now
        self._cpu: float | None = None
        self._cpu_at = now

    def output(self, now: float) -> None:
        self.last_output = now

    def sample(self, now: float, cpu_total: float | None) -> dict:
        if cpu_total is not None and self._cpu is not None:
            dt = now - self._cpu_at
            if dt > 0 and (cpu_total - self._cpu) / dt >= self.cfg["cpuActiveRatio"]:
                self.last_active = now
        if cpu_total is not None:
            self._cpu, self._cpu_at = cpu_total, now
        quiet = max(0.0, now - self.last_output)
        if quiet < self.cfg["quietAfterSeconds"]:
            phase = "RUNNING"
        elif cpu_total is None:
            phase = "QUIET"
        elif now - max(self.last_output, self.last_active) >= self.cfg["stalledAfterSeconds"]:
            phase = "STALLED?"
        else:
            phase = "QUIET"
        return {"phase": phase, "elapsedSeconds": now - self.started, "quietSeconds": round(quiet, 1),
                "cpuSeconds": None if cpu_total is None else round(cpu_total, 1)}


def clock(seconds: float) -> str:
    s = int(seconds)
    return f"{s // 3600}:{(s % 3600) // 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60:02d}:{s % 60:02d}"


def opening_line(shown: str, pid: int, logpath: Path, cfg: dict, cpu_on: bool) -> str:
    return (f"[run] {shown[:80]} | pid={pid} | log={logpath} | heartbeat={cfg['stdoutSeconds']:g}s "
            f"quiet={cfg['quietAfterSeconds']:g}s stalled={cfg['stalledAfterSeconds']:g}s cpu={'on' if cpu_on else 'off'}")


def heartbeat_line(snap: dict, lines: int, last_line: str, cfg: dict) -> str:
    out = f"[hb] {clock(snap['elapsedSeconds'])} {snap['phase']} lines={lines} quiet={snap['quietSeconds']:g}s"
    if snap["cpuSeconds"] is not None:
        out += f" cpu={snap['cpuSeconds']:g}s"
    chars = int(cfg["lastLineChars"])
    tail = " ".join(last_line.split()).replace('"', "'")[:chars]
    if chars > 0 and tail:
        out += f' last="{tail}"'
    return out


def say(text: str) -> None:
    """A heartbeat is best-effort: a closed or broken stdout must never stop the command it reports on."""
    try:
        print(text, flush=True)
    except (OSError, ValueError):
        pass


def tolerate_console_encoding() -> None:
    # A cp1252 Windows console cannot encode a check mark or a box-drawing glyph; printing one used to
    # raise UnicodeEncodeError after the command had finished, losing the verdict (QUAL-63).
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except (AttributeError, ValueError):
            pass


def main() -> int:
    tolerate_console_encoding()
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--family", help="force a policy family instead of auto-detecting")
    ap.add_argument("--label", help="name for the log file (default: derived from the command)")
    ap.add_argument("--tail", type=int, help="also print the last N raw lines")
    ap.add_argument("--keep-going", action="store_true",
                    help="always exit 0 (report-only; the digest still shows the real code)")
    ap.add_argument("--heartbeat", type=float, metavar="SECONDS",
                    help="print a [hb] line this often while the command runs, even if it prints nothing "
                         "(0 = off; default: policy heartbeat.stdoutSeconds, or $NPDEV_HEARTBEAT_SECONDS)")
    ap.add_argument("--quiet-after", type=float, metavar="SECONDS",
                    help="no output for this long = QUIET (default: policy, or $NPDEV_HEARTBEAT_QUIET_AFTER)")
    ap.add_argument("--stalled-after", type=float, metavar="SECONDS",
                    help="no output and no CPU for this long = STALLED? (default: policy, or $NPDEV_HEARTBEAT_STALLED_AFTER)")
    ap.add_argument("command", nargs=argparse.REMAINDER)
    args = ap.parse_args()

    cmd = args.command
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        ap.error("no command given; use: run_digest.py -- <command ...>")

    # Resolve the executable up front (PATHEXT-aware on Windows, so npm.cmd works) and say so plainly
    # when it is missing, instead of a CreateProcess traceback that names no command.
    resolved = shutil.which(cmd[0])
    if resolved is None:
        print(f"run_digest: command not found on PATH: {cmd[0]}", file=sys.stderr)
        return 127
    policy = load_policy()
    joined = " ".join(cmd)  # as typed: family matching and the echo must not see the resolved path
    argv = [resolved] + cmd[1:]
    fam = pick_family(policy, joined, args.family)
    defaults = policy["defaults"]
    cfg = heartbeat_settings(policy, {"stdoutSeconds": args.heartbeat, "quietAfterSeconds": args.quiet_after,
                                      "stalledAfterSeconds": args.stalled_after})

    label = args.label or re.sub(r"[^A-Za-z0-9._-]+", "-", Path(cmd[0]).name + "-" + "-".join(cmd[1:3]))[:60]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    logpath = log_dir() / f"{stamp}-{label}.log"

    started = time.time()
    lines: list[str] = []
    jobs = jobs_module()
    job_id = f"{stamp}-{label}"[:80]
    broot = build_root()

    def job(action):
        """Never let bookkeeping break the run it describes."""
        if jobs is None:
            return
        try:
            action()
        except Exception:
            pass

    with open(logpath, "w", encoding="utf-8", errors="replace") as log:
        log.write(f"# {joined}\n# started {stamp}\n\n")
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, errors="replace", bufsize=1)
        assert proc.stdout is not None
        job(lambda: jobs.write_record(broot, jobs.new_record(
            job_id, fam.get("title") or label, cmd, os.getcwd(), str(logpath), proc.pid, os.getpid(), jobs.launcher_info())))

        drained = threading.Event()
        pulse = Pulse(cfg, time.monotonic())
        cpu = TreeCpu(proc.pid)
        hb_every = cfg["stdoutSeconds"]
        if hb_every > 0:
            shown_cmd = " ".join(joined.split())
            say(opening_line(shown_cmd, proc.pid, logpath, cfg, cpu.available))

        def pump() -> None:
            for raw in proc.stdout:
                if drained.is_set():
                    return  # the log is closed; whatever the daemon still prints is not this run's
                log.write(raw)
                log.flush()
                os.fsync(log.fileno())
                lines.append(raw.rstrip("\n"))
                pulse.output(time.monotonic())

        # Read on a thread and stop at the COMMAND's exit, not at EOF: a Gradle daemon the command
        # forked inherits the pipe and outlives it, so EOF never came and the runner hung forever
        # after a finished run-all-gates (found live 2026-10-28). The grace lets the tail drain.
        reader = threading.Thread(target=pump, daemon=True)
        reader.start()
        # a heartbeat every few seconds while the command runs: it is what lets `npdev jobs` tell a job that is
        # quietly working from one whose writer died (JOBS-1). The last line is redacted by the jobs module.
        # Two cadences share one wait: the registry record (jobs.HEARTBEAT_SECONDS, `npdev jobs` / the status line)
        # and the stdout line (cfg stdoutSeconds, what a background log or `ptrack tail` shows).
        registry_every = jobs.HEARTBEAT_SECONDS if jobs else 0
        ticks = [x for x in (registry_every, hb_every) if x > 0]
        tick = min(ticks) if ticks else None
        next_registry = time.monotonic() + registry_every
        next_stdout = time.monotonic() + hb_every
        while True:
            try:
                code = proc.wait(timeout=tick)
                break
            except subprocess.TimeoutExpired:
                now = time.monotonic()
                due_registry = registry_every > 0 and now >= next_registry - 0.05
                due_stdout = hb_every > 0 and now >= next_stdout - 0.05
                if not (due_registry or due_stdout):
                    continue
                snap = pulse.sample(now, cpu.total())
                snapshot = lines[-1] if lines else ""
                count = len(lines)
                if due_registry:
                    next_registry = now + registry_every
                    job(lambda: jobs.heartbeat(broot, job_id, count, snapshot, snap))
                if due_stdout:
                    next_stdout = now + hb_every
                    say(heartbeat_line(snap, count, snapshot, cfg))
        reader.join(timeout=OUTPUT_DRAIN_GRACE_SECONDS)
        drained.set()
        lines = list(lines)
        job(lambda: jobs.finish(broot, job_id, code))
    elapsed = time.time() - started

    failed = code != 0
    mark = "FAILED" if failed else "ok"

    shown = " ".join(joined.split())
    if len(shown) > 140:
        shown = shown[:140] + " ..."
    print(f"$ {shown}")
    print(f"  {mark}  exit={code}  {elapsed:.0f}s  {len(lines)} lines  family={fam['name']}")
    print(f"  log: {logpath}")
    print()
    for ln in digest(lines, fam, defaults, failed):
        print(ln)

    if args.tail:
        print(f"\n  --- last {args.tail} raw lines ---")
        for ln in lines[-args.tail:]:
            print(f"  {ln.rstrip()[:defaults['maxLineLength']]}")

    if failed:
        print(f"\n  full output: grep -n -i 'error\\|fail' '{logpath}'")

    return 0 if args.keep_going else code


if __name__ == "__main__":
    sys.exit(main())
