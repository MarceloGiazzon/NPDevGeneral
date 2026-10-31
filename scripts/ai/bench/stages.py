"""Pipeline stage runners for the AI authoring benchmark (BENCH-2): validate, generate, build, boot.

Each stage returns a StageResult (ok, seconds, detail, logPath). Subprocesses are always run in a
process group / tree so a timeout or teardown leaves no orphaned Gradle or JVM. Stdlib only.
Everything is written under the bench run directory (Build/ai-bench/...), never inside the repo.
"""
from __future__ import annotations

import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
CLI = REPO_ROOT / "NPDevCli" / "npdev_cli.py"
CANARY_INPUT = REPO_ROOT / "NPDevSamples" / "npdev-canary" / "Input"

# Ports the rest of the platform's tooling owns; the bench never boots on these.
RESERVED_PORTS = {8120, 8091, 8421, 8190, 8110, 8080, 8099, 8103, 2526}


@dataclass
class StageResult:
    stage: str
    ok: bool
    seconds: float = 0.0
    detail: str = ""             # short human/model-facing error text when not ok
    log_path: str | None = None
    extra: dict = field(default_factory=dict)

    def to_json(self) -> dict:
        d = {"stage": self.stage, "ok": self.ok, "seconds": round(self.seconds, 2), "detail": self.detail,
             "log": self.log_path}
        d.update(self.extra)
        return d


def build_root() -> Path:
    env = os.environ.get("NPDEV_BUILD_ROOT")
    if env and env.strip():
        return Path(env).expanduser().resolve()
    cursor = REPO_ROOT
    return (cursor.parent / "Build").resolve()


# ---------------------------------------------------------------------------------------------
# process helpers
# ---------------------------------------------------------------------------------------------

def kill_tree(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True, timeout=30)
        else:
            import signal
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        pass


def run_logged(command: list[str], cwd: Path | str, log_path: Path, timeout: float,
               env: dict | None = None) -> tuple[int | None, bool]:
    """Run `command`, stream stdout+stderr to log_path. Returns (returncode, timed_out)."""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    kwargs: dict = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        proc = subprocess.Popen(command, cwd=str(cwd), stdout=log, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, env=env, **kwargs)
        try:
            proc.wait(timeout=timeout)
            return proc.returncode, False
        except subprocess.TimeoutExpired:
            kill_tree(proc)
            return None, True


def tail(path: Path | str | None, chars: int = 3500) -> str:
    if not path or not Path(path).exists():
        return ""
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    return text[-chars:]


def error_excerpt(path: Path | str | None, chars: int = 3500) -> str:
    """Log excerpt centered on the first ERROR/FAILED/error: line, else the tail -- what a model needs."""
    if not path or not Path(path).exists():
        return ""
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    # A Java stack trace states its reason last: the deepest `Caused by:` line. Lead with it. Without
    # this, a boot failure's 3500-char window was spent on Spring's banner and bean warnings, and the
    # one line that said what to fix ("no persistence capability binding is registered") never reached
    # the model -- haiku55-core bench-007 burned four repair loops (~90k tokens) blind.
    causes = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("Caused by:") and stripped[:300] not in causes:
            causes.append(stripped[:300])
    if causes:
        head = "Root cause (deepest last):\n" + "\n".join(causes[-3:]) + "\n\nLog excerpt:\n"
        chars = max(500, chars - len(head))
    else:
        head = ""
    for idx, line in enumerate(lines):
        low = line.lower()
        if "error:" in low or "failed" in low or "exception" in low or "> task" in low and "failed" in low:
            return head + "\n".join(lines[max(0, idx - 3): idx + 40])[:chars]
    return head + text[-chars:]


def free_port(avoid: set[int] | None = None) -> int:
    avoid = (avoid or set()) | RESERVED_PORTS
    import random
    for _ in range(400):
        port = random.randint(20000, 40000)
        if port in avoid:
            continue
        with socket.socket() as s:
            try:
                s.bind(("127.0.0.1", port))
            except OSError:
                continue
        return port
    raise RuntimeError("could not find a free port")


def java_launcher() -> str | None:
    home = os.environ.get("JAVA_HOME")
    if home:
        cand = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.exists():
            return str(cand)
    return shutil.which("java")


# ---------------------------------------------------------------------------------------------
# workspace
# ---------------------------------------------------------------------------------------------

def write_workspace(attempt_dir: Path, name: str, port: int, model_text: str) -> dict:
    """model.json + a config.json / db.definition.json derived from the frozen canary's (h2-local, wiped).
    A fresh attempt dir per attempt IS the wiped database: the H2 file lives under App/data."""
    attempt_dir.mkdir(parents=True, exist_ok=True)
    out = attempt_dir / "Output"
    config = json.loads((CANARY_INPUT / "config.json").read_text(encoding="utf-8"))
    config.pop("$schema", None)
    safe = "".join(ch if ch.isalnum() else "_" for ch in name).lower()[:40] or "bench"
    config["scenario"] = {"name": f"bench-{safe}", "description": "AI authoring benchmark run (BENCH-2).",
                          "outputRoot": str(out)}
    config["bootstrap"] = {"root": str(REPO_ROOT / "NPDevRuntimeHost"), "mergeStrategy": "clean-copy"}
    config["artifact"] = {"root": str(out / "ArtifactNP"), "generatedFolderName": "npdev-generated",
                          "libsFolderName": "libs", "metaFolderName": "npdev-meta"}
    config["finalExec"] = {"root": str(out / "App"), "deleteBeforeMount": True}
    config["database"] = {"provider": "h2-local", "database": f"bench_{safe}", "resetMode": "reset"}
    config["runtime"] = {"springProfile": "dev", "serverPort": port, "javaArgs": [], "gradleTask": "bootRun"}
    config.pop("trialDefaults", None)
    dbdef = json.loads((CANARY_INPUT / "db.definition.json").read_text(encoding="utf-8"))
    dbdef["database"]["databaseName"] = f"bench_{safe}"
    paths = {"model": attempt_dir / "model.json", "config": attempt_dir / "config.json",
             "db": attempt_dir / "db.definition.json", "app": out / "App"}
    paths["model"].write_text(model_text, encoding="utf-8")
    paths["config"].write_text(json.dumps(config, indent=2), encoding="utf-8")
    paths["db"].write_text(json.dumps(dbdef, indent=2), encoding="utf-8")
    return paths


# ---------------------------------------------------------------------------------------------
# stages
# ---------------------------------------------------------------------------------------------

def format_diagnostics(report: dict, limit: int = 25) -> str:
    out = []
    for d in (report.get("diagnostics") or [])[:limit]:
        if str(d.get("severity", "error")).lower() != "error":
            continue
        bits = [str(d.get("code", "")), str(d.get("path", "")), str(d.get("message", ""))]
        line = " | ".join(b for b in bits if b)
        if d.get("suggestedFix"):
            line += "  -> suggested fix: " + str(d["suggestedFix"])
        out.append("- " + line)
    return "\n".join(out)


def stage_validate(model_path: Path, log_dir: Path, timeout: float = 300) -> StageResult:
    started = time.monotonic()
    report = log_dir / "validate-report.json"
    log = log_dir / "validate.log"
    # a stale report from a previous attempt must never be mistaken for this one's
    if report.exists():
        report.unlink()
    rc, timed_out = run_logged([sys.executable, str(CLI), "validate", "model", str(model_path),
                                "--report", str(report)], REPO_ROOT, log, timeout)
    secs = time.monotonic() - started
    if timed_out:
        return StageResult("validate", False, secs, "validate timed out", str(log))
    detail, ok = "", rc == 0
    if report.exists():
        try:
            data = json.loads(report.read_text(encoding="utf-8"))
            ok = ok and data.get("status") == "passed"
            if not ok:
                detail = format_diagnostics(data) or tail(log, 1500)
            return StageResult("validate", ok, secs, detail, str(log),
                               {"errors": (data.get("summary") or {}).get("errors"),
                                "warnings": (data.get("summary") or {}).get("warnings")})
        except ValueError:
            pass
    if not ok:
        detail = tail(log, 2000)
    return StageResult("validate", ok, secs, detail, str(log))


def stage_generate(paths: dict, log_dir: Path, timeout: float = 900) -> StageResult:
    started = time.monotonic()
    log = log_dir / "generate.log"
    rc, timed_out = run_logged([sys.executable, str(CLI), "generate", "app", "--model", str(paths["model"]),
                                "--config", str(paths["config"]), "--output", str(paths["app"])],
                               REPO_ROOT, log, timeout)
    secs = time.monotonic() - started
    ok = rc == 0 and (paths["app"] / "build.gradle").exists()
    detail = "" if ok else ("generate timed out" if timed_out else error_excerpt(log))
    return StageResult("generate", ok, secs, detail, str(log))


def find_jar(app_root: Path) -> Path | None:
    for cand in app_root.rglob("FinalExec-*.jar"):
        if "build" + os.sep + "libs" in str(cand) and not cand.name.endswith("-plain.jar"):
            return cand
    return None


def stage_build(paths: dict, log_dir: Path, timeout: float = 1500) -> StageResult:
    started = time.monotonic()
    log = log_dir / "build.log"
    app = paths["app"]
    wrapper = app / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.exists():
        return StageResult("build", False, 0.0, f"Gradle wrapper not found in generated app: {wrapper}", str(log))
    env = dict(os.environ)
    env.setdefault("NPDEV_RUNTIMEHOST_LIBS_DIR", str(build_root() / "runtimehost-libs"))
    # same command `npdev run app` uses for a first build
    rc, timed_out = run_logged([str(wrapper), "--no-daemon", "--console=plain", "clean", "build", "-x", "test"],
                               app, log, timeout, env=env)
    secs = time.monotonic() - started
    jar = find_jar(app) if rc == 0 else None
    ok = rc == 0 and jar is not None
    detail = "" if ok else ("build timed out" if timed_out else error_excerpt(log))
    return StageResult("build", ok, secs, detail, str(log), {"jar": str(jar) if jar else None})


class RunningApp:
    def __init__(self, proc: subprocess.Popen, port: int, api_key: str, log_path: Path, role_keys: dict | None = None):
        self.proc, self.port, self.api_key, self.log_path = proc, port, api_key, log_path
        self.role_keys = role_keys or {}   # authored role name -> api key mapped to that role
        self.base_url = f"http://127.0.0.1:{port}"

    def stop(self) -> None:
        kill_tree(self.proc)


def stage_boot(paths: dict, port: int, log_dir: Path, timeout: float = 300, roles: list[str] | None = None) -> tuple[StageResult, RunningApp | None]:
    started = time.monotonic()
    log = log_dir / "boot.log"
    app = paths["app"]
    jar = find_jar(app)
    java = java_launcher()
    if jar is None or java is None:
        return StageResult("boot", False, 0.0, "no jar or no java found", str(log)), None
    key = secrets.token_hex(24)
    role_keys = {r: secrets.token_hex(24) for r in (roles or [])}
    mapping = ";".join([f"{key}=dev:developer:admin"]
                       + [f"{k}=dev:bench_{r.lower()}:{r}" for r, k in role_keys.items()])
    (app / "secrets").mkdir(parents=True, exist_ok=True)
    (app / "secrets" / "api-key.env").write_text(f"NPDEV_AUTH_API_KEYS={mapping}", encoding="utf-8")
    env = dict(os.environ)
    env["NPDEV_AUTH_API_KEYS"] = mapping
    env["NPDEV_AUTH_APIKEYS"] = mapping
    kwargs: dict = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    with open(log, "w", encoding="utf-8", errors="replace") as logf:
        proc = subprocess.Popen([java, "-jar", str(jar), f"--server.port={port}", "--spring.profiles.active=dev"],
                                cwd=str(app), stdout=logf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                env=env, **kwargs)
    running = RunningApp(proc, port, key, log, role_keys)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            break
        try:
            with urllib.request.urlopen(f"{running.base_url}/actuator/health", timeout=3) as resp:
                if json.loads(resp.read().decode("utf-8")).get("status") == "UP":
                    return StageResult("boot", True, time.monotonic() - started, "", str(log), {"port": port}), running
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(2)
    died = proc.poll() is not None
    running.stop()
    return (StageResult("boot", False, time.monotonic() - started,
                        ("app exited before becoming healthy (code %s)\n" % proc.returncode if died
                         else "app did not report /actuator/health UP in time\n") + error_excerpt(log),
                        str(log)), None)
