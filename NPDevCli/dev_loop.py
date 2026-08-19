#!/usr/bin/env python3
"""`npdev dev` -- watch the model, rebuild and restart the app on every save.

WHY THIS IS A SEPARATE MODULE FROM run_app
------------------------------------------
`run_app` deliberately DISOWNS the JVM it boots:

    npdev_cli.py   _RUN_APP_CHILD_PROCESS = None
                   # READY: intentionally leave it running, not this run's to kill

That is correct for a one-shot command, but it means a loop built on `run_app` alone would
leak a JVM per cycle and could never stop the previous app -- there is no stop-by-port helper
anywhere in the CLI (`_is_port_in_use` only checks). So this module:

    REUSES  validate -> classify -> generate -> build    (stateless, already correct)
    OWNS    boot + stop                                   (it must hold the process handle)

THE TRUST INVARIANT
-------------------
The old app is stopped as LATE as possible. GENERATE and BUILD do not need the port; only the
boot does. So every failure before the swap leaves the previous app untouched and serving. A
dev loop that leaves a dead port after a typo is one people stop using.

Stdlib only -- the portable CLI has no third-party dependencies, so the watcher is mtime
polling rather than an OS notification API.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

DEBOUNCE_SECONDS = 0.4
POLL_SECONDS = 0.25
BOOT_READY_TIMEOUT = 180
STATE_DIR_NAME = ".npdev-dev"


# =====================================================================================
# Options
# =====================================================================================

@dataclass
class DevOptions:
    model: Path
    config: Path
    output: Path
    port: int = 8080
    profile: str = "dev"
    timeout: int = 420
    json_events: bool = False
    force_clean: bool = False

    @property
    def state_dir(self) -> Path:
        return self.model.parent / STATE_DIR_NAME

    @property
    def baseline(self) -> Path:
        return self.state_dir / "baseline-model.json"

    @property
    def app_log(self) -> Path:
        return self.state_dir / "app.log"

    @property
    def lockfile(self) -> Path:
        return self.state_dir / f"dev-{self.port}.lock"


# =====================================================================================
# Watch set
# =====================================================================================

def watch_set(options: DevOptions, cli) -> dict[Path, float]:
    """Every file whose change should trigger a cycle -> its mtime.

    Watches the RESOLVED model set (root + every $ref fragment), not one file, plus the
    config and db definition. Deliberately not a directory watch: editors write .swp/.tmp
    files, and a swap file triggering a full rebuild is how a dev loop becomes hated.
    """
    files: set[Path] = {options.model, options.config}
    db_def = options.config.parent / "db.definition.json"
    if db_def.exists():
        files.add(db_def)
    try:
        sources: set[Path] = set()
        cli.resolve_split_model(options.model, collect_sources=sources)
        files.update(sources)
    except Exception:  # noqa: BLE001
        # A model too broken to resolve is exactly when we still need to watch it, so the
        # next save can fix it. Fall back to the base set.
        pass
    return {f: _mtime(f) for f in files if f.exists()}


def _mtime(path: Path) -> float:
    try:
        return path.stat().st_mtime
    except OSError:
        return -1.0


def wait_for_change(watch: dict[Path, float], stop: "Stopper") -> list[Path]:
    """Block until a watched file changes, then let the burst settle.

    Editors write in bursts (truncate-then-write; some write twice). Coalescing a burst into
    one cycle is the difference between one rebuild and three.
    """
    while not stop.requested:
        time.sleep(POLL_SECONDS)
        changed = [p for p, was in watch.items() if _mtime(p) != was]
        if not changed:
            continue
        settled_at = time.monotonic()
        snapshot = {p: _mtime(p) for p in watch}
        while time.monotonic() - settled_at < DEBOUNCE_SECONDS:
            time.sleep(POLL_SECONDS / 2)
            now = {p: _mtime(p) for p in watch}
            if now != snapshot:
                snapshot, settled_at = now, time.monotonic()
        return changed
    return []


# =====================================================================================
# The app process -- owned here
# =====================================================================================

class AppProcess:
    def __init__(self, proc: subprocess.Popen, jar: Path, log_handle):
        self.proc = proc
        self.jar = jar
        self._log = log_handle

    def alive(self) -> bool:
        return self.proc.poll() is None

    def stop(self, timeout: float = 20.0) -> None:
        if self.alive():
            # SIGTERM first so Spring shuts down cleanly and releases the H2 file lock. A
            # hard kill can leave a lock behind and break the NEXT boot, which would look
            # like a dev-loop bug rather than a teardown bug.
            try:
                self.proc.terminate()
                self.proc.wait(timeout=timeout)
            except (subprocess.TimeoutExpired, OSError):
                try:
                    self.proc.kill()
                    self.proc.wait(timeout=10)
                except OSError:
                    pass
        try:
            self._log.close()
        except OSError:
            pass


def _health_ok(port: int) -> bool:
    """The same readiness definition run_app uses: /actuator/health reporting UP.

    Deliberately not a port check -- a socket accepts before the app serves.
    """
    try:
        request = urllib.request.Request(f"http://127.0.0.1:{port}/actuator/health")
        with urllib.request.urlopen(request, timeout=3) as response:
            return (json.loads(response.read().decode("utf-8", "replace")) or {}).get("status") == "UP"
    except (urllib.error.URLError, OSError, json.JSONDecodeError, ValueError):
        return False


def _reclaim_orphan(options: DevOptions, out: "Output") -> None:
    """Stop an app left behind by a dev loop that did not exit cleanly.

    Teardown cannot be guaranteed: a force-kill (or, on Windows, a SIGINT that never gets
    delivered -- MSYS `kill -INT` does not reach a native python process) runs no finally
    block, and the JVM keeps serving. Rather than leave the user to discover a stale app
    holding their port, record its pid and reclaim it on the next start. Self-healing beats
    a teardown path that cannot cover every exit.
    """
    pidfile = options.state_dir / "app.pid"
    try:
        pid = int(pidfile.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return
    if not _health_ok(options.port):
        pidfile.unlink(missing_ok=True)
        return
    out.note(f"an app from a previous dev session is still on :{options.port} -- stopping it")
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                           capture_output=True, timeout=20)
        else:
            os.kill(pid, signal.SIGTERM)
    except (OSError, subprocess.SubprocessError):
        pass
    for _ in range(20):
        if not _health_ok(options.port):
            break
        time.sleep(1)
    pidfile.unlink(missing_ok=True)


def boot(options: DevOptions, jar: Path, cli) -> AppProcess | None:
    options.state_dir.mkdir(parents=True, exist_ok=True)
    # W1.3: `cli.java_launcher()`, never a bare `["java", ...]`. The generate and build phases above
    # ran under whatever JAVA_HOME this process was given -- the Manager hands its private JDK to
    # child processes that way and touches nothing else (`NPDevManager/src/npdev.rs`) -- so a launch
    # that resolves java through PATH is the one step of the loop that cannot see it. `cli` is the
    # npdev_cli module this loop is already injected with, which keeps the resolution in ONE place
    # instead of growing a second copy here.
    java = cli.java_launcher()
    if java is None:
        # Written into the app log rather than raised: run_cycle's failure path says "boot failed --
        # see <log>", and before this the log was EMPTY because Popen raised FileNotFoundError past
        # it. A pointer to an empty file is how this defect stayed unreported.
        log = open(options.app_log, "w", encoding="utf-8")  # noqa: SIM115
        log.write("npdev dev: no Java runtime found -- JAVA_HOME is unset or has no bin/java, and\n"
                  "there is no `java` on PATH, so the built app cannot be started.\n"
                  "Install a JDK 17+ or set JAVA_HOME; `npdev doctor` shows what NPDev can find.\n")
        log.close()
        return None
    # T1/C2: `dev` no longer seeds a known key -- StartupValidator refuses to boot without one
    # supplied externally. Same injected-`cli` reasoning as `java_launcher()` two lines above: one
    # implementation in npdev_cli.py, called from both of this platform's raw-`java -jar` boot sites.
    cli.ensure_api_key(options.output)
    log = open(options.app_log, "w", encoding="utf-8")  # noqa: SIM115  (owned by AppProcess)
    proc = subprocess.Popen(
        [java, "-jar", str(jar),
         f"--server.port={options.port}",
         f"--spring.profiles.active={options.profile}"],
        cwd=str(options.output), stdout=log, stderr=subprocess.STDOUT,
    )
    app = AppProcess(proc, jar, log)
    (options.state_dir / "app.pid").write_text(str(proc.pid), encoding="utf-8")
    deadline = time.monotonic() + BOOT_READY_TIMEOUT
    while time.monotonic() < deadline:
        if not app.alive():
            app.stop()
            return None
        if _health_ok(options.port):
            return app
        time.sleep(1.0)
    app.stop()
    return None


# =====================================================================================
# One cycle
# =====================================================================================

@dataclass
class CycleResult:
    ok: bool
    phase: str
    fast: bool = False
    elapsed: float = 0.0
    build_seconds: float = 0.0


def run_cycle(options: DevOptions, current: AppProcess | None, out: "Output", cli) -> tuple[CycleResult, AppProcess | None]:
    started = time.monotonic()
    root = cli.repo_root()
    deadline = time.monotonic() + options.timeout

    # --- validate first: a bad model must never reach GENERATE -----------------------
    out.phase("validate")
    report = options.state_dir / "validation.json"
    try:
        rc = cli.run_validate_semantic(options.model, report)
    except Exception as exc:  # noqa: BLE001
        out.result("FAILED")
        out.detail(str(exc))
        return CycleResult(False, "VALIDATE", elapsed=time.monotonic() - started), current
    if rc != 0:
        out.result("FAILED")
        out.diagnostics(report)
        out.note("app left running on the previous model. fix and save again.")
        return CycleResult(False, "VALIDATE", elapsed=time.monotonic() - started), current
    out.result("ok")

    # --- classify: take the metadata-only fast path automatically ---------------------
    fast = False
    if options.baseline.exists() and options.output.exists():
        try:
            fast = cli._classify_model_change(root, options.baseline, options.model, deadline) == "METADATA_ONLY"
        except Exception:  # noqa: BLE001
            fast = False
    out.classification(fast)

    # --- generate + build ------------------------------------------------------------
    # On POSIX the old app keeps serving through GENERATE and BUILD, and only dies at the
    # swap below. On Windows it cannot: config.json's cleanOutputBeforeGenerate deletes the
    # output tree, and Windows refuses to delete a file another process holds open --
    # confirmed live:
    #     FileSystemException: ...\build\libs\FinalExec-0.1.0.jar:
    #     O arquivo ja esta sendo usado por outro processo   (at Files.deleteIfExists)
    # So on Windows we stop first and say so. The cheap 90% of the trust invariant survives
    # either way, because VALIDATE runs before this and a typo -- the common case by far --
    # never reaches GENERATE at all.
    if current is not None and os.name == "nt":
        out.note("stopping the app first (Windows cannot replace files a running app holds)")
        current.stop()
        current = None

    gen_args = argparse.Namespace(
        model=str(options.model), config=str(options.config), output=str(options.output),
        require_db_definition=False, port=options.port, profile=options.profile,
        timeout=options.timeout, baseline_model=None, keep_running=True,
    )
    out.phase("generate")
    try:
        gen_ok, gen_output = cli._generate_phase_captured(root, gen_args, options.output, deadline)
    except Exception as exc:  # noqa: BLE001
        gen_ok, gen_output = False, str(exc)
    if not gen_ok:
        out.result("FAILED")
        out.detail(cli._log_excerpt(gen_output) if hasattr(cli, "_log_excerpt") else gen_output[-800:])
        out.note("app left running on the previous build.")
        return CycleResult(False, "GENERATE", fast, time.monotonic() - started), current
    out.result("ok")

    out.phase("build")
    build_started = time.monotonic()
    try:
        # R1.2: incremental (`build -x test` on a warm daemon) by default, falling back to `clean`
        # automatically on failure -- `_build_phase` owns that fallback so this loop never has to.
        # `--clean-build` forces every cycle to skip straight to a clean build.
        build_ok, build_output, jar, fell_back = cli._build_phase(
            options.output, deadline, clean=options.force_clean)
    except Exception as exc:  # noqa: BLE001
        build_ok, build_output, jar, fell_back = False, str(exc), None, False
    build_seconds = time.monotonic() - build_started
    if not build_ok or jar is None:
        out.result("FAILED")
        if fell_back:
            out.note("an incremental build failed and the automatic `clean build` retry failed too")
        classified = cli._classify_build_failure(build_output) if build_output else None
        out.detail((classified or {}).get("message") or (build_output or "")[-800:])
        out.note("app left running on the previous build.")
        return CycleResult(False, "BUILD", fast, time.monotonic() - started, build_seconds), current
    out.result("ok")
    if fell_back:
        out.note("incremental build failed -- automatically retried with `clean build` (fell back)")

    # --- the swap: only now does the old app die -------------------------------------
    out.phase("restart")
    previous_jar = current.jar if current else None
    if current:
        current.stop()
    app = boot(options, Path(jar), cli)
    if app is None:
        out.result("FAILED")
        out.note(f"boot failed -- see {options.app_log}")
        if previous_jar and Path(previous_jar).exists():
            out.note("restoring the previous build")
            app = boot(options, Path(previous_jar), cli)
        return CycleResult(False, "BOOT", fast, time.monotonic() - started, build_seconds), app
    out.result("ok")

    options.state_dir.mkdir(parents=True, exist_ok=True)
    options.baseline.write_bytes(options.model.read_bytes())
    return CycleResult(True, "READY", fast, time.monotonic() - started, build_seconds), app


# =====================================================================================
# Output -- narration to stderr, events to stdout
# =====================================================================================

class Output:
    def __init__(self, json_events: bool):
        self.json_events = json_events
        self._open = False

    def _w(self, text: str = "") -> None:
        print(text, file=sys.stderr, flush=True)

    def event(self, **kw) -> None:
        if self.json_events:
            print(json.dumps({"ts": time.time(), **kw}), flush=True)

    def banner(self, o: DevOptions, persistent: bool) -> None:
        self._w()
        self._w(f"  model    {o.model}")
        self._w(f"  app      http://localhost:{o.port}")
        if persistent:
            self._w("  data     persists across restarts (db.definition.json)")
        else:
            self._w("  data     IN-MEMORY -- rows are lost on every restart.")
            self._w("           Set database.engine to H2Local in db.definition.json to keep them")
            self._w("           (that is what `npdev init` scaffolds).")
        self._w()
        self._w("  watching. edit the model and save. Ctrl+C to stop.")
        self._w()

    def changed(self, paths: list[Path]) -> None:
        self._w("  " + "-" * 61)
        self._w(f"  {time.strftime('%H:%M:%S')}  changed: {', '.join(p.name for p in paths)}")
        self.event(kind="changed", files=[str(p) for p in paths])

    def phase(self, name: str) -> None:
        print(f"  {time.strftime('%H:%M:%S')}  {name} ".ljust(52, "."), end=" ", file=sys.stderr, flush=True)
        self._open = True
        self.event(kind="phase", phase=name)

    def result(self, text: str) -> None:
        if self._open:
            print(text, file=sys.stderr, flush=True)
            self._open = False
        self.event(kind="result", result=text)

    def classification(self, fast: bool) -> None:
        self.note("change is METADATA_ONLY -- taking the fast path" if fast
                  else "structural change -- full rebuild")
        self.event(kind="classification", metadataOnly=fast)

    def diagnostics(self, report: Path) -> None:
        try:
            data = json.loads(report.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            self.note(f"see {report}")
            return
        errors = [d for d in (data.get("diagnostics") or []) if d.get("severity") == "error"]
        self._w()
        for d in errors[:8]:
            self._w(f"     {d.get('path') or d.get('section') or ''}")
            self._w(f"       {d.get('message', '')}")
            if d.get("suggestedFix"):
                self._w(f"       -> {d['suggestedFix']}")
            self._w()
        if len(errors) > 8:
            self._w(f"     ... and {len(errors) - 8} more")
            self._w()
        self.event(kind="diagnostics", errorCount=len(errors))

    def detail(self, text: str) -> None:
        for line in (text or "").strip().splitlines()[-8:]:
            self._w(f"       | {line}")

    def note(self, text: str) -> None:
        self._w(f"  {time.strftime('%H:%M:%S')}  {text}")

    def ready(self, r: CycleResult, port: int) -> None:
        self.note(f"ready in {r.elapsed:.1f}s   http://localhost:{port}")
        self.event(kind="ready", seconds=round(r.elapsed, 1), metadataOnly=r.fast)

    def build_timing(self, seconds: float, previous: float | None) -> None:
        """R1.2's own done-when: a before/after pair, so the incremental speedup is observable
        rather than asserted. `previous is None` on the very first cycle -- there is no "before" yet,
        so it says so instead of printing a lone number that looks like the whole feature."""
        if previous is None:
            self.note(f"build: {seconds:.1f}s (first build this session -- later saves reuse a warm daemon)")
        else:
            speedup = f", {previous / seconds:.1f}x faster" if seconds > 0 else ""
            self.note(f"build: {seconds:.1f}s   (previous: {previous:.1f}s{speedup})")
        self.event(kind="buildTiming", seconds=round(seconds, 1),
                   previousSeconds=round(previous, 1) if previous is not None else None)


# =====================================================================================
# Lifecycle
# =====================================================================================

class Stopper:
    """Ctrl+C must stop the loop AND the app it started, promptly.

    A flag alone is not enough: a cycle spends most of its time inside GENERATE/BUILD
    subprocesses, so a flag checked between phases can leave Ctrl+C looking ignored for
    45 seconds (observed live: "STILL RUNNING" 12s after SIGINT). The second interrupt is
    therefore escalated -- the first asks politely, the second stops waiting.
    """

    def __init__(self) -> None:
        self.requested = False
        self._on_stop = None

    def bind(self, on_stop) -> None:
        self._on_stop = on_stop

    def install(self) -> None:
        def handler(_signum, _frame):  # noqa: ANN001
            if self.requested:
                # Second Ctrl+C: tear the app down now and leave, mid-phase.
                if self._on_stop is not None:
                    try:
                        self._on_stop()
                    except Exception:  # noqa: BLE001
                        pass
                raise KeyboardInterrupt
            self.requested = True
            print("\n  stopping after this step -- press Ctrl+C again to stop now",
                  file=sys.stderr, flush=True)
        signal.signal(signal.SIGINT, handler)
        if hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, handler)


def _lock_holder(lockfile: Path) -> int | None:
    """The pid currently holding this port's dev lock, or None if free/stale."""
    try:
        pid = int(lockfile.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None
    if pid == os.getpid():
        return None
    # Liveness alone is not enough: operating systems recycle PIDs, so a dead dev loop's pid
    # can come back as something unrelated and block this port forever (observed live -- a
    # recycled pid held the lock after a force-kill). Require the holder to actually BE a
    # python process before believing it is us.
    try:
        if os.name == "nt":
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"],
                                 capture_output=True, text=True, timeout=10).stdout
            alive = str(pid) in out and "python" in out.lower()
        else:
            os.kill(pid, 0)
            cmdline = Path(f"/proc/{pid}/cmdline").read_bytes().decode("utf-8", "replace")
            alive = "python" in cmdline.lower() or not Path("/proc").exists()
    except (OSError, subprocess.SubprocessError):
        alive = False
    if alive:
        return pid
    lockfile.unlink(missing_ok=True)
    return None


def _is_persistent(config: Path) -> bool:
    """Does this app keep its data across restarts?

    Read, never rewritten: the engine is a generation-time input the user owns. `npdev init`
    already scaffolds H2Local + KeepExistingIfCompatible, so this is a warning path, not a
    default to override.
    """
    db_def = config.parent / "db.definition.json"
    try:
        engine = (json.loads(db_def.read_text(encoding="utf-8")).get("database") or {}).get("engine")
    except (OSError, json.JSONDecodeError, AttributeError):
        return False
    return isinstance(engine, str) and engine.lower() != "inmemory"


def dev(args: argparse.Namespace, cli) -> int:
    options = DevOptions(
        model=Path(args.model).expanduser().resolve(),
        config=Path(args.config).expanduser().resolve(),
        output=Path(args.output).expanduser().resolve(),
        port=args.port, profile=args.profile, timeout=args.timeout,
        json_events=getattr(args, "json", False),
        force_clean=getattr(args, "clean_build", False),
    )
    if not options.model.exists():
        print(f"npdev dev: model not found: {options.model}", file=sys.stderr)
        return 2
    options.state_dir.mkdir(parents=True, exist_ok=True)

    holder_pid = _lock_holder(options.lockfile)
    if holder_pid is not None:
        print(f"npdev dev is already running on port {options.port} (pid {holder_pid}).",
              file=sys.stderr)
        return 2
    # A lockfile whose owner is gone is stale, not a conflict: if we only checked existence,
    # one crash or force-kill would block this port forever and the user would have no idea
    # why (observed live after a Stop-Process during development).
    options.lockfile.write_text(str(os.getpid()), encoding="utf-8")

    stop = Stopper()
    out = Output(options.json_events)
    app: AppProcess | None = None
    # A mutable cell so the signal handler can reach whatever app is current right now --
    # `app` is rebound every cycle, so closing over the name is not enough.
    holder: dict[str, AppProcess | None] = {"app": None}
    stop.bind(lambda: holder["app"].stop() if holder["app"] else None)
    stop.install()
    # R1.2's own done-when: `npdev dev` prints a before/after timing pair, so the incremental
    # speedup is observable rather than just asserted. `None` on cycle 1 -- there is no "before" yet.
    previous_build_seconds: float | None = None
    try:
        out.banner(options, _is_persistent(options.config))
        _reclaim_orphan(options, out)
        result, app = run_cycle(options, None, out, cli)
        holder["app"] = app
        if result.build_seconds > 0:
            out.build_timing(result.build_seconds, previous_build_seconds)
            previous_build_seconds = result.build_seconds
        if result.ok:
            out.ready(result, options.port)
            # Printed once, on the first successful boot only -- every later cycle in this same
            # `npdev dev` process reuses the same secrets/api-key.env, so repeating it on every save
            # would just be noise. Same "print once, X-Api-Key: <key>" courtesy every other launcher
            # on this platform already gives (OperationalRunbookEmitter's Ensure-NpdevApiKey).
            key_file = options.output / "secrets" / "api-key.env"
            if key_file.exists():
                for raw_line in key_file.read_text(encoding="utf-8").splitlines():
                    line = raw_line.strip()
                    if line.startswith("NPDEV_AUTH_API_KEYS=") and "=" in line[len("NPDEV_AUTH_API_KEYS="):]:
                        out.note(f"X-Api-Key: {line.split('=', 2)[1]}  (saved to {key_file})")
                        break
        while not stop.requested:
            changed = wait_for_change(watch_set(options, cli), stop)
            if stop.requested or not changed:
                break
            out.changed(changed)
            result, app = run_cycle(options, app, out, cli)
            holder["app"] = app
            if result.build_seconds > 0:
                out.build_timing(result.build_seconds, previous_build_seconds)
                previous_build_seconds = result.build_seconds
            if result.ok:
                out.ready(result, options.port)
        return 0
    except KeyboardInterrupt:
        return 0
    finally:
        # Never orphan a JVM, on any exit path including an exception.
        if app is not None:
            out.note("stopping the app")
            app.stop()
        (options.state_dir / "app.pid").unlink(missing_ok=True)
        options.lockfile.unlink(missing_ok=True)


def add_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--model", help="Path to model.json (default: ./model.json).")
    parser.add_argument("--config", help="Path to config.json (default: beside the model).")
    parser.add_argument("--output", help="Where to generate the app (default: ../<dir>-app).")
    parser.add_argument("--port", type=int, default=8080, help="Server port (default 8080).")
    parser.add_argument("--profile", default="dev", help="Spring profile (default 'dev').")
    parser.add_argument("--timeout", type=int, default=420,
                        help="Wall-clock budget per GENERATE+BUILD cycle, in seconds.")
    parser.add_argument(
        "--clean-build", action="store_true",
        help="R1.2: force a full `clean build` every cycle instead of the default incremental "
             "`build` on a warm daemon. The default already falls back to `clean` automatically "
             "when an incremental build fails -- this flag is for when you want every cycle clean, "
             "not for recovering from a failure.",
    )
    parser.add_argument("--json", action="store_true",
                        help="Emit one JSON event per phase transition on stdout.")
