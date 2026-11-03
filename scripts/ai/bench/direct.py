"""The "AI writes the app directly" arm of the token benchmark (BENCH-1). Stdlib only.

WHY THIS EXISTS
---------------
The NPDev arm of the benchmark measures how many tokens an AI needs to author an NPDev model that boots and
passes acceptance. That number means nothing without the competitor on the same terms: the same prompts, the
same model, the same repair-loop budget and the SAME acceptance probes, but the model writes a whole
application (Python, Node or Spring Boot) instead of a model.json. This module is that route.

Fairness rules, enforced here and written into the report:
  * The model is TOLD everything the probes check (the probe contract in NPDevSamples/ai-bench/direct-arm.json:
    the port, /v3/api-docs, REST collections, X-Api-Key roles, 4xx on bad input, /api/flows/<name>/execute,
    persistence). NPDev never wins by hiding the test from the competitor.
  * Same loop cap, and the failing stage's error text is fed back, as in the NPDev arm.
  * Every token counts, input and output separately.

SAFETY (owner decision D3, 2026-11-03): this runs code an AI wrote. Always: its own working folder under the
build root, an environment cleared down to PATH, PORT and the role keys, a wall-clock timeout, the whole process
tree killed afterwards, dependencies limited to the stack's allowlist and installed with scripts disabled, and a
refusal to run at all as an administrator. The full 30-prompt run is meant for WSL or Docker; the local profile
keeps Docker off, so a pilot runs locally under these limits.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import backends  # noqa: E402
import probes  # noqa: E402
import stages  # noqa: E402

SPEC_PATH = stages.REPO_ROOT / "NPDevSamples" / "ai-bench" / "direct-arm.json"
FIXTURE_APP = Path(__file__).resolve().parent / "fixtures" / "direct-python-app.py"
FILE_HEADER = re.compile(r"^=== FILE: (.+?) ===[ \t]*$", re.MULTILINE)
MAX_FILES = 200
MAX_BYTES = 800_000


# ---------------------------------------------------------------------------------------------
# the arm definition and its prompts
# ---------------------------------------------------------------------------------------------

def load_spec(path: Path = SPEC_PATH) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def arm_ids(spec: dict | None = None) -> list[str]:
    return [s["id"] for s in (spec or load_spec())["stacks"]]


def stack_for(spec: dict, arm_id: str) -> dict:
    for s in spec["stacks"]:
        if s["id"] == arm_id:
            return s
    raise KeyError(f"no direct-arm stack {arm_id!r} (known: {', '.join(arm_ids(spec))})")


def system_prompt(spec: dict, stack: dict) -> str:
    deps = ", ".join(stack["allowedDependencies"]) or "none (standard library only)"
    return (spec["systemPrompt"]
            .replace("{probeContract}", "\n".join("- " + c for c in spec["probeContract"]))
            .replace("{stackLabel}", stack["label"])
            .replace("{allowedDependencies}", deps))


def initial_messages(spec: dict, stack: dict, prompt_text: str) -> list[dict]:
    return [{"role": "system", "content": system_prompt(spec, stack)},
            {"role": "user", "content": "Build this application:\n\n" + prompt_text.strip()}]


def _clip(text: str) -> str:
    text = (text or "").strip()
    return text if len(text) <= 6000 else text[:3000] + "\n...[truncated]...\n" + text[-2800:]


def repair_messages(spec: dict, history: list[dict], reply_text: str, stage: str, feedback: str) -> list[dict]:
    note = spec["repairPrompt"].replace("{stage}", stage).replace("{feedback}", _clip(feedback))
    return history + [{"role": "assistant", "content": reply_text}, {"role": "user", "content": note}]


# ---------------------------------------------------------------------------------------------
# files: parse what the model wrote, never trust a path it chose
# ---------------------------------------------------------------------------------------------

def _safe_path(raw: str) -> str | None:
    """A relative, forward-slash path inside the app folder, or None. Rejects absolute paths (POSIX, a
    drive letter, UNC), any `..`, NUL and empty names."""
    p = raw.strip().strip("`'\"").replace("\\", "/")
    if not p or "\x00" in p or p.startswith("/") or re.match(r"^[A-Za-z]:", p):
        return None
    parts = [x for x in p.split("/") if x not in ("", ".")]
    if not parts or any(x == ".." for x in parts):
        return None
    return "/".join(parts)


def _strip_fence(body: str) -> str:
    text = body.strip("\n")
    m = re.match(r"^```[A-Za-z0-9_+-]*\n(.*?)\n?```\s*$", text, re.DOTALL)
    return (m.group(1) if m else text).rstrip("\n") + "\n"


def parse_files(reply: str) -> tuple[dict[str, str], str]:
    """`=== FILE: <path> ===` blocks -> ({path: content}, error). A reply with no block, an unsafe path, too many
    files or too many bytes is an author-stage failure with a message the model can act on."""
    headers = list(FILE_HEADER.finditer(reply or ""))
    if not headers:
        return {}, "no `=== FILE: <path> ===` block found in the reply"
    files: dict[str, str] = {}
    total = 0
    for i, h in enumerate(headers):
        path = _safe_path(h.group(1))
        if path is None:
            return {}, f"unsafe file path {h.group(1)!r}: use a relative path inside the project, with no `..`"
        end = headers[i + 1].start() if i + 1 < len(headers) else len(reply)
        content = _strip_fence(reply[h.end():end].lstrip("\r\n"))
        files[path] = content
        total += len(content.encode("utf-8"))
        if len(files) > MAX_FILES or total > MAX_BYTES:
            return {}, f"the project is too large (limit {MAX_FILES} files, {MAX_BYTES // 1000} KB)"
    return files, ""


def write_files(app_dir: Path, files: dict[str, str]) -> list[str]:
    root = app_dir.resolve()
    written = []
    for rel, content in files.items():
        target = (root / rel).resolve()
        if root != target and root not in target.parents:      # belt and braces after _safe_path
            raise ValueError(f"path escapes the project: {rel}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8", newline="\n")
        written.append(rel)
    return written


def read_tree(app_dir: Path, limit_bytes: int = MAX_BYTES) -> dict[str, str]:
    """Every text file the model wrote (for the evolution phase's 'here is your app' message)."""
    out: dict[str, str] = {}
    total = 0
    skip = {"node_modules", ".git", "__pycache__", "build", ".gradle", "data.db", "app.db"}
    for path in sorted(app_dir.rglob("*")):
        if not path.is_file() or any(part in skip for part in path.relative_to(app_dir).parts):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        total += len(text)
        if total > limit_bytes:
            break
        out[path.relative_to(app_dir).as_posix()] = text
    return out


# ---------------------------------------------------------------------------------------------
# running AI-written code safely
# ---------------------------------------------------------------------------------------------

def is_admin() -> bool:
    if os.name == "nt":
        try:
            import ctypes
            return bool(ctypes.windll.shell32.IsUserAnAdmin())
        except Exception:
            return False
    return hasattr(os, "geteuid") and os.geteuid() == 0


def clean_env(extra: dict[str, str]) -> dict[str, str]:
    """PATH, the few variables a process needs to start at all, and what the contract names -- nothing else.
    No keys, no tokens, no HOME of the user running the benchmark."""
    keep = ["PATH"]
    if os.name == "nt":
        keep += ["SystemRoot", "SYSTEMDRIVE", "COMSPEC", "PATHEXT", "TEMP", "TMP", "windir"]
    env = {k: os.environ[k] for k in keep if k in os.environ}
    env.update(extra)
    return env


def role_keys_for(acceptance: dict) -> dict[str, str]:
    """One random key per role the acceptance names, plus `admin`. The harness is the only holder."""
    names = {str(r.get("name")) for r in acceptance.get("roles", []) if r.get("name")}
    names |= {str(p["as"]) for p in acceptance.get("probes", []) if p.get("as") and p["as"] != "admin"}
    keys = {n: secrets.token_hex(16) for n in sorted(names)}
    keys["admin"] = secrets.token_hex(16)
    return keys


def stage_install(stack: dict, app_dir: Path, log_dir: Path, timeout: float = 600) -> stages.StageResult:
    started = time.monotonic()
    allowed = set(stack["allowedDependencies"])
    log = log_dir / "install.log"
    if stack["id"] == "direct-python":
        req = app_dir / "requirements.txt"
        lines = [ln.strip() for ln in req.read_text(encoding="utf-8").splitlines()] if req.exists() else []
        if any(ln and not ln.startswith("#") for ln in lines):
            return stages.StageResult("install", False, 0.0,
                                      "third-party dependencies are not allowed for this stack: remove requirements.txt "
                                      "entries and use only the Python standard library", str(log))
        return stages.StageResult("install", True, 0.0)
    if stack["id"] == "direct-node":
        pkg = app_dir / "package.json"
        if not pkg.exists():
            return stages.StageResult("install", False, 0.0, "package.json is missing", str(log))
        try:
            declared = set((json.loads(pkg.read_text(encoding="utf-8")).get("dependencies") or {}))
        except ValueError as exc:
            return stages.StageResult("install", False, 0.0, f"package.json is not valid JSON: {exc}", str(log))
        extra = sorted(declared - allowed)
        if extra:
            return stages.StageResult("install", False, 0.0,
                                      f"dependencies not allowed: {', '.join(extra)} (allowed: {', '.join(sorted(allowed))})", str(log))
        npm = shutil.which("npm")
        if not npm:
            return stages.StageResult("install", False, 0.0, "npm is not installed on this machine", str(log))
        # --ignore-scripts: a dependency's install script is code too, and the allowlist already names the packages
        rc, timed_out = stages.run_logged([npm, "install", "--ignore-scripts", "--no-audit", "--no-fund"], app_dir, log,
                                          timeout, env=dict(os.environ))
        ok = rc == 0 and not timed_out
        return stages.StageResult("install", ok, time.monotonic() - started,
                                  "" if ok else ("npm install timed out\n" if timed_out else "") + stages.error_excerpt(log), str(log))
    return stages.StageResult("install", True, 0.0)   # direct-spring: dependencies are resolved by the build


def _boot_command(stack: dict, app_dir: Path) -> list[str] | None:
    cmd = list(stack["boot"])
    if stack["id"] == "direct-python":
        return [sys.executable] + cmd[1:]
    if stack["id"] == "direct-spring":
        wrapper = app_dir / ("gradlew.bat" if os.name == "nt" else "gradlew")
        return [str(wrapper)] + cmd[1:] if wrapper.exists() else None
    return cmd


def stage_boot(stack: dict, app_dir: Path, port: int, log_dir: Path, timeout: float, role_keys: dict[str, str]
               ) -> tuple[stages.StageResult, stages.RunningApp | None]:
    started = time.monotonic()
    log = log_dir / "boot.log"
    cmd = _boot_command(stack, app_dir)
    if cmd is None:
        return stages.StageResult("boot", False, 0.0, "the project has no Gradle wrapper (gradlew) to start it", str(log)), None
    env = clean_env({"PORT": str(port), "NPDEV_BENCH_ROLE_KEYS": json.dumps(role_keys)})
    kwargs: dict = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    log.parent.mkdir(parents=True, exist_ok=True)
    try:
        with open(log, "w", encoding="utf-8", errors="replace") as logf:
            proc = subprocess.Popen(cmd, cwd=str(app_dir), stdout=logf, stderr=subprocess.STDOUT,
                                    stdin=subprocess.DEVNULL, env=env, **kwargs)
    except OSError as exc:
        return stages.StageResult("boot", False, 0.0, f"could not start the app: {exc}", str(log)), None
    running = stages.RunningApp(proc, port, role_keys["admin"], log, {k: v for k, v in role_keys.items() if k != "admin"})
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            break
        try:
            with urllib.request.urlopen(f"{running.base_url}/v3/api-docs", timeout=3) as resp:
                if resp.status == 200:
                    return stages.StageResult("boot", True, time.monotonic() - started, "", str(log), {"port": port}), running
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(0.5)
    died = proc.poll() is not None
    running.stop()
    return (stages.StageResult("boot", False, time.monotonic() - started,
                               ("the app exited before serving GET /v3/api-docs (exit code %s)\n" % proc.returncode if died
                                else "the app did not answer 200 on GET /v3/api-docs in time\n") + stages.error_excerpt(log),
                               str(log)), None)


# ---------------------------------------------------------------------------------------------
# one attempt, one prompt
# ---------------------------------------------------------------------------------------------

def run_attempt(n: int, files: dict[str, str], prompt: dict, prompt_dir: Path, stack: dict, args,
                stage_log: list, previous_dir: Path | None, acceptance: dict | None = None) -> dict:
    """write -> install -> boot -> acceptance. A repair reply carries only the changed files, so the previous
    attempt's tree is the starting point and the reply is applied on top of it."""
    attempt_dir = prompt_dir / f"attempt-{n}"
    app_dir = attempt_dir / "app"
    if previous_dir is not None and previous_dir.exists():
        shutil.copytree(previous_dir, app_dir, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("node_modules", "__pycache__", "*.db", "build", ".gradle"))
    app_dir.mkdir(parents=True, exist_ok=True)
    written = write_files(app_dir, files)
    acc_spec = acceptance or prompt["acceptance"]
    out = {"stages": stage_log, "failingStage": None, "feedback": "", "appDir": str(app_dir),
           "filesWritten": len(written), "linesWritten": sum(c.count("\n") for c in files.values())}
    running = None

    def fail(res: stages.StageResult) -> dict:
        out["stages"].append(res.to_json())
        out["failingStage"], out["feedback"] = res.stage, res.detail
        return out

    try:
        res = stage_install(stack, app_dir, attempt_dir, getattr(args, "build_timeout", 600))
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        role_keys = role_keys_for(acc_spec)
        res, running = stage_boot(stack, app_dir, stages.free_port(), attempt_dir, getattr(args, "boot_timeout", 120), role_keys)
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        started = time.monotonic()
        acc = probes.run_acceptance(acc_spec, running.base_url, role_keys["admin"], running.role_keys, None)
        (attempt_dir / "acceptance.json").write_text(json.dumps(acc, indent=2), encoding="utf-8")
        res = stages.StageResult("acceptance", acc["ok"], time.monotonic() - started,
                                 "" if acc["ok"] else probes.feedback_text(acc), str(attempt_dir / "acceptance.json"),
                                 {"probeCounts": acc["probeCounts"], "ruleCounts": acc["ruleCounts"],
                                  "conceptProblems": len(acc["conceptProblems"])})
        out["acceptance"] = acc
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        return out
    finally:
        if running is not None:
            running.stop()


def _first_error_line(feedback: str) -> str:
    for line in (feedback or "").splitlines():
        line = line.strip()
        if line:
            return line[:160]
    return ""


def run_prompt_direct(prompt: dict, backend, arm_id: str, run_dir: Path, args, spec: dict | None = None) -> dict:
    """The direct arm's twin of run_bench.run_prompt: the same result shape, so scorecard, report, matrix and
    compare read both arms identically."""
    spec = spec or load_spec()
    stack = stack_for(spec, arm_id)
    prompt_dir = run_dir / prompt["id"]
    prompt_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    messages = initial_messages(spec, stack, prompt["prompt"])
    attempts: list[dict] = []
    tokens_in = tokens_out = 0
    author_seconds = 0.0
    passed = False
    stuck_limit = getattr(args, "stop_when_stuck", 0)
    stuck_limit = stuck_limit if isinstance(stuck_limit, int) else 0
    signatures: list[tuple] = []
    stopped_stuck = False
    last_dir: Path | None = None
    final: dict = {}
    for n in range(0, args.max_loops + 1):
        stage_log: list[dict] = []
        try:
            completion = backend.complete(messages)
        except backends.BackendError as exc:
            stage_log.append(stages.StageResult("author", False, 0.0, str(exc)).to_json())
            attempts.append({"attempt": n, "stages": stage_log, "failingStage": "author", "feedback": str(exc),
                             "backendError": True, "infraKind": backends.classify_backend_error(str(exc))})
            break
        tokens_in += completion.prompt_tokens
        tokens_out += completion.completion_tokens
        author_seconds += completion.seconds
        adir = prompt_dir / f"attempt-{n}"
        adir.mkdir(parents=True, exist_ok=True)
        (adir / "author-reply.txt").write_text(completion.text, encoding="utf-8")
        files, error = parse_files(completion.text)
        stage_log.append(stages.StageResult("author", bool(files), completion.seconds, error, str(adir / "author-reply.txt"),
                                            {"promptTokens": completion.prompt_tokens,
                                             "completionTokens": completion.completion_tokens}).to_json())
        if not files:
            attempt = {"attempt": n, "stages": stage_log, "failingStage": "author", "feedback": error}
        else:
            attempt = run_attempt(n, files, prompt, prompt_dir, stack, args, stage_log, last_dir)
            attempt["attempt"] = n
            last_dir = Path(attempt["appDir"])
        attempts.append(attempt)
        if attempt["failingStage"] is None:
            passed, final = True, attempt
            break
        signatures.append((attempt["failingStage"], _first_error_line(attempt["feedback"])))
        if stuck_limit >= 2 and len(signatures) >= stuck_limit and len(set(signatures[-stuck_limit:])) == 1:
            stopped_stuck = True
            break
        messages = repair_messages(spec, messages, completion.text, attempt["failingStage"], attempt["feedback"])
    wall = time.monotonic() - started
    first = attempts[0]
    infra_kind = None if passed else attempts[-1].get("infraKind")
    result = {
        "promptId": prompt["id"], "tier": prompt.get("tier"), "title": prompt.get("title"), "arm": arm_id,
        "passed": passed, "outcome": "pass" if passed else ("infra" if infra_kind else "fail"), "infraKind": infra_kind,
        "loopsUsed": len(attempts) - 1, "stoppedStuck": stopped_stuck,
        "firstAttemptValid": bool(first["failingStage"] not in ("author", "install") and not first.get("backendError")),
        "firstAttemptPassed": first["failingStage"] is None,
        "firstAttemptFailingStage": first["failingStage"],
        "failingStage": None if passed else attempts[-1]["failingStage"],
        "tokens": {"prompt": tokens_in, "completion": tokens_out, "total": tokens_in + tokens_out},
        "costUsd": backend.info.cost(tokens_in, tokens_out),
        "wallSeconds": round(wall, 2), "authorSeconds": round(author_seconds, 2),
        "ablation": "none", "repairMode": "history", "backend": backend.info.describe(),
        "filesWritten": (final or attempts[-1]).get("filesWritten", 0),
        "linesWritten": (final or attempts[-1]).get("linesWritten", 0),
        "attempts": [{"attempt": a["attempt"], "failingStage": a["failingStage"],
                      "feedbackExcerpt": (a.get("feedback") or "")[:600], "stages": a["stages"]} for a in attempts],
    }
    # the passing app stays on disk for the evolution phase; a failing tree is large and useless
    if not getattr(args, "keep_apps", False) and not passed:
        for a in attempts:
            shutil.rmtree(prompt_dir / f"attempt-{a['attempt']}" / "app", ignore_errors=True)
    result["_finalAppDir"] = final.get("appDir") if passed else None
    (prompt_dir / "result.json").write_text(json.dumps({k: v for k, v in result.items() if not k.startswith("_")}, indent=2),
                                            encoding="utf-8")
    return result


# ---------------------------------------------------------------------------------------------
# evolution (BENCH-4): change an app that already passes
# ---------------------------------------------------------------------------------------------

class DirectEvolution:
    """The direct arm's adapter for evolve.apply_changes: the model sees its own working files and the owner's
    change request, and replies with ONLY the files that change; the reply is applied on top of the last good tree."""

    def __init__(self, spec: dict, stack: dict, prompt: dict, app_dir: Path, args):
        self.spec, self.stack, self.prompt, self.dir, self.args = spec, stack, prompt, Path(app_dir), args

    def messages(self, change: dict) -> list[dict]:
        tree = read_tree(self.dir)
        blocks = "".join(f"=== FILE: {path} ===\n{text}" for path, text in tree.items())
        note = ("The application now works. The owner wants one more change:\n\n" + change["request"].strip()
                + "\n\nReply with ONLY the files that change, in the same `=== FILE: path ===` format, each with its full new content. "
                  "Keep everything that already works.")
        return initial_messages(self.spec, self.stack, self.prompt["prompt"]) + [
            {"role": "assistant", "content": blocks}, {"role": "user", "content": note}]

    def repair(self, messages: list[dict], reply: str, stage: str, feedback: str) -> list[dict]:
        return repair_messages(self.spec, messages, reply, stage, feedback)

    def attempt(self, n: int, reply: str, prompt: dict, change_dir: Path, stage_log: list) -> dict:
        files, error = parse_files(reply)
        if not files:
            stage_log.append(stages.StageResult("author", False, 0.0, error).to_json())
            return {"failingStage": "author", "feedback": error, "stages": stage_log}
        return run_attempt(n, files, prompt, change_dir, self.stack, self.args, stage_log, self.dir, prompt["acceptance"])

    def commit(self, attempt: dict) -> None:
        self.dir = Path(attempt["appDir"])


# ---------------------------------------------------------------------------------------------
# the fake backend for --dry-run
# ---------------------------------------------------------------------------------------------

class FakeDirectBackend:
    """Deterministic --dry-run backend for the direct arm: always replies with a known-good tiny Python app
    (fixtures/direct-python-app.py) that satisfies the dry-run fixture prompt's acceptance, so the whole
    route -- parse, write, boot under the cleared environment, OpenAPI catalog, probes -- is testable with no
    model. `replies` scripts a sequence (a broken app first, then the good one) to exercise the repair loop."""

    FAKE_PROMPT_TOKENS = 1000
    FAKE_COMPLETION_TOKENS = 700

    def __init__(self, replies: list[str] | None = None):
        self.info = backends.BackendInfo("fake-dry-run", "fake", "fake://dry-run", "fake-direct", 0.0, 0.0, False)
        self._replies = list(replies or [])
        self.calls: list[list[dict]] = []

    @staticmethod
    def good_reply() -> str:
        return "=== FILE: app.py ===\n" + FIXTURE_APP.read_text(encoding="utf-8")

    @staticmethod
    def evolved_reply() -> str:
        """The same app after the dry-run fixture's change c1 (a `notes` field on tasks)."""
        source = FIXTURE_APP.read_text(encoding="utf-8")
        assert source.count('"status": "string"}') == 1, "fixture app changed: update evolved_reply"
        return "=== FILE: app.py ===\n" + source.replace('"status": "string"}', '"status": "string", "notes": "string"}')

    def complete(self, messages: list[dict]) -> backends.Completion:
        self.calls.append(messages)
        text = self._replies.pop(0) if self._replies else self.good_reply()
        return backends.Completion(text, self.FAKE_PROMPT_TOKENS, self.FAKE_COMPLETION_TOKENS, 0.0)
