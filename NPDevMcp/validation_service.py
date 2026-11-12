#!/usr/bin/env python3
"""NPDev validation service -- validate a model.json that lives in an online AI's head.

A zero-dependency (Python stdlib) local HTTP service that takes the CONTENT of one model.json,
runs the real NPDev validator (the same `npdev validate model` an author runs) and answers with a
small JSON report an AI can read and act on (contract `npdev-validation-service.v1`).

Two doors on ONE core:

  Path A -- REST + a self-contained page (works with any AI that can drive a browser, and for you):
      GET  /                  the page (NPDevMcp/static/validate.html)
      GET  /api/health        liveness + load
      POST /api/validate      body = the model.json; query: maxDiagnostics, severity, full, pretty
      GET  /api/schema/model  the canonical model.schema.json

  Path B -- MCP over Streamable HTTP, STATELESS (only with --mcp; needs a public tunnel to be seen
      from an online chat AI):
      POST /mcp/<token>       JSON-RPC; a read-only allowlist of tools, never server.TOOL_HANDLERS

Run:  python NPDevMcp/validation_service.py [--mcp] [--port N]      (or `npdev validator serve`)

Design rules (see the owner's plan, VALIDATION_SERVICE_PLAN): binds 127.0.0.1 only; refuses to
start without the warm npdev-ai-tools.jar (the Gradle fallback is slow and locks); never logs a
model body or the token; never lets a local path leave the process; the validation journal
(_capture_validation) is OFF unless --capture.

This module prints NOTHING when imported (NPDevMcp/server.py uses stdout as its JSON-RPC channel
and must never import this module at the top level).
"""

from __future__ import annotations

import argparse
import hmac
import importlib
import json
import os
import re
import secrets
import shutil
import socket
import sys
import tempfile
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlsplit

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import pack_lint as _pack_lint  # noqa: E402
import server as _mcp_server  # noqa: E402  (after the sys.path line on purpose)

SERVICE_VERSION = "1.0.0"
CONTRACT = "npdev-validation-service.v1"
DEFAULT_PORT = 8780
DEFAULT_HOST = "127.0.0.1"
MAX_BODY_BYTES = 2 * 1024 * 1024
MCP_BODY_MARGIN = 1024 * 1024
DEFAULT_MAX_DIAGNOSTICS = 50
MCP_DEFAULT_MAX_DIAGNOSTICS = 20      # smaller default over MCP: every token counts there
MAX_DIAGNOSTICS_CEILING = 500
DEFAULT_MAX_CONCURRENT = 2
QUEUE_TIMEOUT_SECONDS = 30.0
VALIDATE_TIMEOUT_SECONDS = 60
RETRY_AFTER_SECONDS = 10
TOOL_TEXT_CAP = 200_000
SCHEMA_TEXT_CAP = 600_000
ACCESS_LOG_MAX_BYTES = 5 * 1024 * 1024
ACCESS_LOG_KEEP = 3
RATE_PER_MINUTE = 30
RATE_PER_HOUR = 300
MCP_LATEST_VERSION = "2025-06-18"
MCP_SUPPORTED_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
# Origins a browser-side MCP client may come from. A request with NO Origin header (a server-to-server
# connector call, the normal case) always passes; anything else must be one of these (DNS rebinding).
MCP_ALLOWED_ORIGIN_HOSTS = ("claude.ai", "chatgpt.com", "chat.mistral.ai")
LOCAL_HOSTS = ("localhost", "127.0.0.1", "[::1]", "::1")
JAR_MISSING_MESSAGE = (
    "The fast validator jar is missing. Run scripts/runtimehost/sync-runtimehost-libs.ps1 "
    "-BuildLocalJars once."
)

ValidateFn = Callable[[Path], "tuple[int, dict | None, str]"]


class Rejection(Exception):
    """An input or availability problem that becomes a `rejected` response (never a stack trace)."""

    def __init__(self, http_status: int, code: str, message: str, **extra: Any) -> None:
        super().__init__(message)
        self.http_status = http_status
        self.code = code
        self.message = message
        self.extra = extra


class ValidatorUnavailable(Exception):
    """The validator could not run (jar/java missing, timeout, no report)."""


# ---------------------------------------------------------------------------
# Pure helpers (no HTTP, no disk) -- unit tested directly
# ---------------------------------------------------------------------------

def _path_regex(path: "str | Path") -> "re.Pattern[str] | None":
    """A case-insensitive regex matching `path` with either slash, doubled slashes included."""
    parts = re.split(r"[\\/]+", str(path))
    if not any(parts):
        return None
    leading = r"[\\/]+" if parts[0] == "" else ""
    body = r"[\\/]+".join(re.escape(p) for p in parts if p != "")
    return re.compile(leading + body, re.IGNORECASE)


_HOME_DIR_PATTERNS = (
    re.compile(r"(?i)(?:[A-Za-z]:)?[\\/]+Users[\\/]+[^\\/\s\"']+"),
    re.compile(r"/(?:home|Users)/[^/\s\"']+"),
)


def sanitize(text: str, temp_dir: "str | Path | None" = None, *extra_roots: "str | Path",
             token: str = "<model-dir>") -> str:
    """Replace the scratch directory (any slash spelling, any letter case) with `<model-dir>`.

    `extra_roots` (e.g. the repo root) become `<npdev>`; whatever still looks like a per-user home
    directory becomes `<user-dir>`. Pure: the report that comes back from the validator carries the
    absolute path of the machine, and that must never leave the process.
    """
    if not isinstance(text, str) or not text:
        return text
    out = text
    if temp_dir is not None:
        rx = _path_regex(temp_dir)
        if rx is not None:
            out = rx.sub(token, out)
    for root in extra_roots:
        rx = _path_regex(root)
        if rx is not None:
            out = rx.sub("<npdev>", out)
    for rx in _HOME_DIR_PATTERNS:
        out = rx.sub("<user-dir>", out)
    return out


def _sanitize_deep(value: Any, fn: Callable[[str], str]) -> Any:
    if isinstance(value, str):
        return fn(value)
    if isinstance(value, list):
        return [_sanitize_deep(v, fn) for v in value]
    if isinstance(value, dict):
        return {k: _sanitize_deep(v, fn) for k, v in value.items()}
    return value


def next_step(status: str, summary: "dict | None" = None, rejected_code: "str | None" = None,
              **detail: Any) -> str:
    """One plain-English sentence telling the AI what to do next."""
    summary = summary or {}
    errors = int(summary.get("errors") or 0)
    warnings = int(summary.get("warnings") or 0)
    if status == "passed":
        return "The model is valid. You can stop."
    if status == "warning":
        return f"The model is valid. The {warnings} warning{'s' if warnings != 1 else ''} are optional improvements."
    if status == "failed":
        return (f"Fix the {errors} error{'s' if errors != 1 else ''} below and validate again. "
                "Warnings are optional.")
    code = rejected_code or ""
    if code == "invalid_json":
        where = ""
        if detail.get("line") is not None:
            where = f": line {detail['line']}, column {detail.get('column')}"
        return f"The body is not valid JSON{where}. Send the whole model.json as JSON."
    if code == "not_an_object":
        return "The model must be one JSON object ({...}) holding the whole model.json. Send the whole model.json."
    if code == "too_large":
        return "The model is larger than the 2 MB limit. Send a smaller model."
    if code == "unsupported_media_type":
        return "Send the model with Content-Type application/json (or text/plain)."
    if code == "length_required":
        return "Send a Content-Length header with the request body."
    if code == "busy":
        return "The validator is busy. Wait a few seconds and send the same request again."
    if code == "rate_limited":
        return "Too many calls. Wait a minute and send the same request again."
    if code == "validator_unavailable":
        return "The validator could not run on the owner's computer. Ask the owner to check the validation service."
    if code == "not_found":
        return "That address does not exist. Use POST /api/validate with the model.json as the body."
    if code == "method_not_allowed":
        return "That method is not allowed here. Use POST /api/validate with the model.json as the body."
    return "The validation service had an internal error. Try again; if it repeats, tell the owner."


def _service_block(elapsed_ms: int, validator: str) -> dict:
    return {"elapsedMs": int(elapsed_ms), "validator": validator, "serviceVersion": SERVICE_VERSION}


def rejected_payload(code: str, message: str, *, validator: str = "warm-jar", elapsed_ms: int = 0,
                     **extra: Any) -> dict:
    """The `rejected` shape of the response contract -- same keys as every other status."""
    payload: dict[str, Any] = {
        "contractVersion": CONTRACT,
        "status": "rejected",
        "code": code,
        "message": message,
        "summary": {"errors": 0, "warnings": 0},
        "nextStep": next_step("rejected", None, code, **extra),
        "diagnostics": [],
        "truncated": None,
        "autoFixable": [],
    }
    payload.update({k: v for k, v in extra.items() if v is not None})
    payload["service"] = _service_block(elapsed_ms, validator)
    return payload


_SEVERITY_ORDER = {"error": 0, "warning": 1}
_DIAGNOSTIC_FIELDS = ("severity", "code", "path", "message", "suggestedFix", "hint", "layer",
                      "concept", "section", "helpKey", "boundaryId")


def _empty(value: Any) -> bool:
    return value is None or value == "" or value == [] or value == {}


def build_service_response(report: dict, *, max_diagnostics: int = DEFAULT_MAX_DIAGNOSTICS,
                           severity: str = "all", temp_dir: "str | Path | None" = None,
                           extra_roots: "tuple" = (), elapsed_ms: int = 0,
                           validator: str = "warm-jar", full: bool = False) -> dict:
    """Turn one npdev-validation-report.v2 into the service response (pure; no I/O).

    Errors first, then warnings, cut at `max_diagnostics`; empty fields omitted; the machine path
    (`model`, and any occurrence of the scratch directory in a message) never survives.
    """
    clean = lambda text: sanitize(text, temp_dir, *extra_roots)  # noqa: E731
    raw = [d for d in (report.get("diagnostics") or []) if isinstance(d, dict)]
    errors = sum(1 for d in raw if d.get("severity") == "error")
    warnings = sum(1 for d in raw if d.get("severity") == "warning")
    rep_summary = report.get("summary") if isinstance(report.get("summary"), dict) else {}
    summary = {
        "errors": int(rep_summary.get("errors", errors) or 0),
        "warnings": int(rep_summary.get("warnings", warnings) or 0),
    }
    status = report.get("status")
    if status not in ("passed", "warning", "failed"):
        status = "failed" if summary["errors"] else ("warning" if summary["warnings"] else "passed")

    ordered = sorted(raw, key=lambda d: _SEVERITY_ORDER.get(d.get("severity"), 2))  # stable
    if severity == "error":
        ordered = [d for d in ordered if d.get("severity") == "error"]
    limit = max(1, min(int(max_diagnostics), MAX_DIAGNOSTICS_CEILING))
    shown = ordered[:limit]
    diagnostics = []
    for d in shown:
        item = {k: _sanitize_deep(d[k], clean) for k in _DIAGNOSTIC_FIELDS if not _empty(d.get(k))}
        diagnostics.append(item)
    omitted = len(ordered) - len(shown)

    payload: dict[str, Any] = {
        "contractVersion": CONTRACT,
        "status": status,
        "summary": summary,
        "nextStep": next_step(status, summary),
        "diagnostics": diagnostics,
        "truncated": {"shown": len(shown), "omitted": omitted} if omitted > 0 else None,
        "autoFixable": _sanitize_deep(report.get("autoFixable") or [], clean),
        "service": _service_block(elapsed_ms, validator),
    }
    if full:
        copy = _sanitize_deep(report, clean)
        if isinstance(copy, dict) and "model" in copy:
            copy["model"] = "model.json"
        payload["report"] = copy
    return payload


def unwrap_envelope(value: Any) -> dict:
    """The model object, accepting the `{"model": {...}}` envelope (exactly that one key)."""
    if isinstance(value, dict) and set(value.keys()) == {"model"}:
        inner = value["model"]
        if not isinstance(inner, dict):
            raise Rejection(422, "not_an_object",
                            "The `model` key of the envelope must hold the model object.")
        return inner
    if not isinstance(value, dict):
        raise Rejection(422, "not_an_object",
                        "The body must be one JSON object holding the whole model.json.")
    return value


def _reject_constant(name: str) -> Any:
    raise ValueError(f"{name} is not valid JSON")


def parse_model_text(raw: "bytes | str") -> dict:
    """bytes/str -> model object, or a Rejection with line/column for broken JSON."""
    if isinstance(raw, bytes):
        try:
            text = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            raise Rejection(422, "invalid_json", "The body is not valid UTF-8 text.")
    else:
        text = raw
    try:
        value = json.loads(text, parse_constant=_reject_constant)
    except json.JSONDecodeError as exc:
        raise Rejection(422, "invalid_json", f"Invalid JSON: {exc.msg}.",
                        line=exc.lineno, column=exc.colno)
    except RecursionError:
        raise Rejection(422, "invalid_json", "The JSON is nested too deeply.")
    except ValueError as exc:
        raise Rejection(422, "invalid_json", f"Invalid JSON: {exc}.")
    return unwrap_envelope(value)


def clamp_max_diagnostics(raw: Any, default: int = DEFAULT_MAX_DIAGNOSTICS) -> int:
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    return max(1, min(value, MAX_DIAGNOSTICS_CEILING))


def media_type_ok(content_type: "str | None") -> bool:
    if not content_type or not content_type.strip():
        return True
    base = content_type.split(";", 1)[0].strip().lower()
    return base in ("application/json", "text/plain", "text/json") or base.endswith("+json")


def origin_allowed(origin: "str | None") -> bool:
    """MCP DNS-rebinding guard: no Origin passes; otherwise a known chat host or a local page."""
    if origin is None or origin == "":
        return True
    try:
        parts = urlsplit(origin)
    except ValueError:
        return False
    host = (parts.hostname or "").lower()
    if not host or parts.scheme not in ("http", "https"):
        return False
    if host in LOCAL_HOSTS:
        return True
    if parts.scheme != "https":
        return False
    return any(host == allowed or host.endswith("." + allowed) for allowed in MCP_ALLOWED_ORIGIN_HOSTS)


class RateLimiter:
    """Per-key sliding windows (per minute AND per hour). `clock` is injectable -- tests never sleep."""

    def __init__(self, per_minute: int = RATE_PER_MINUTE, per_hour: int = RATE_PER_HOUR,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self.per_minute = per_minute
        self.per_hour = per_hour
        self.clock = clock
        self._hits: dict[str, deque] = {}
        self._lock = threading.Lock()

    def check(self, key: str) -> int:
        """0 when the call is allowed (and counted); else the seconds to wait before retrying."""
        now = self.clock()
        with self._lock:
            hits = self._hits.setdefault(key, deque())
            while hits and now - hits[0] > 3600:
                hits.popleft()
            minute = [t for t in hits if now - t <= 60]
            if len(minute) >= self.per_minute:
                return max(1, int(60 - (now - minute[0])) + 1)
            if len(hits) >= self.per_hour:
                return max(1, int(3600 - (now - hits[0])) + 1)
            hits.append(now)
            if len(self._hits) > 5000:  # bounded memory: forget idle keys
                for stale in [k for k, v in self._hits.items() if not v or now - v[-1] > 3600]:
                    self._hits.pop(stale, None)
            return 0


class TokenStore:
    """The MCP path token. File-backed tokens are re-read when the file changes, so
    `npdev validator rotate-token` takes effect without a restart."""

    def __init__(self, token: "str | None" = None, path: "Path | None" = None) -> None:
        self._static = token
        self._path = path
        self._cached: "str | None" = None
        self._mtime: float = -1.0
        self._lock = threading.Lock()

    def current(self) -> "str | None":
        if self._static is not None:
            return self._static
        if self._path is None:
            return None
        with self._lock:
            try:
                mtime = self._path.stat().st_mtime
                if mtime != self._mtime:
                    self._cached = self._path.read_text(encoding="utf-8").strip() or None
                    self._mtime = mtime
            except OSError:
                self._cached = None
            return self._cached

    def matches(self, candidate: str) -> bool:
        token = self.current()
        return bool(token) and hmac.compare_digest(token.encode("utf-8"), candidate.encode("utf-8"))


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def default_state_dir() -> Path:
    return _mcp_server.build_root() / "validation-service"


def default_token_file() -> Path:
    return default_state_dir() / "token.txt"


def load_or_create_token(path: Path, *, rotate: bool = False) -> str:
    """The token in `path`, creating (or, with rotate, replacing) it."""
    if not rotate and path.is_file():
        existing = path.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    token = generate_token()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(token + "\n", encoding="utf-8")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return token


# ---------------------------------------------------------------------------
# The real validator: the portable CLI as an isolated subprocess
# ---------------------------------------------------------------------------

def make_cli_validator(*, capture: bool = False, timeout: int = VALIDATE_TIMEOUT_SECONDS) -> ValidateFn:
    """`validate_fn(model_path) -> (exit_code, report | None, stderr)` over `npdev validate model`."""

    def run(model_path: Path) -> "tuple[int, dict | None, str]":
        report_path = model_path.parent / "report.json"
        env = dict(os.environ)
        if not capture:
            env["NPDEV_AI_CAPTURE"] = "0"
        result = _mcp_server.run_cli(
            ["validate", "model", str(model_path), "--json", "--report", str(report_path)],
            timeout=timeout, env=env)
        if "exitCode" not in result:
            raise ValidatorUnavailable("validation took too long")
        report: "dict | None" = None
        try:
            report = json.loads(report_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            try:
                report = json.loads(result.get("stdout") or "")
            except ValueError:
                report = None
        if not isinstance(report, dict):
            report = None
        return int(result["exitCode"]), report, result.get("stderr") or ""

    return run


# ---------------------------------------------------------------------------
# The service core
# ---------------------------------------------------------------------------

class ValidationService:
    def __init__(self, *, validate_fn: "ValidateFn | None" = None, validator_name: str = "warm-jar",
                 max_body_bytes: int = MAX_BODY_BYTES, max_concurrent: int = DEFAULT_MAX_CONCURRENT,
                 queue_timeout: float = QUEUE_TIMEOUT_SECONDS, tokens: "TokenStore | None" = None,
                 mcp: bool = False, rate_limiter: "RateLimiter | None" = None,
                 log_path: "Path | None" = None, quiet: bool = True,
                 remote_handlers: "dict[str, Callable[[dict], dict]] | None" = None,
                 echo: "Callable[[str], None] | None" = None) -> None:
        self.validate_fn = validate_fn or make_cli_validator()
        self.validator_name = validator_name
        self.max_body_bytes = max_body_bytes
        self.max_concurrent = max_concurrent
        self.queue_timeout = queue_timeout
        self.tokens = tokens or TokenStore()
        self.mcp_enabled = mcp
        self.rate_limiter = rate_limiter or RateLimiter()
        self.log_path = log_path
        self.quiet = quiet
        self.echo = echo or (lambda line: print(line, flush=True))
        self._semaphore = threading.BoundedSemaphore(max_concurrent)
        self._busy = 0
        self._busy_lock = threading.Lock()
        self._log_lock = threading.Lock()
        self.remote_handlers = remote_handlers if remote_handlers is not None else default_remote_handlers(self)
        self.repo_root = _mcp_server.repo_root()

    # -- health ------------------------------------------------------------
    def health(self) -> dict:
        return {"status": "ok", "validator": self.validator_name, "version": SERVICE_VERSION,
                "busy": self._busy, "maxConcurrent": self.max_concurrent, "mcp": self.mcp_enabled}

    # -- the one validation path both doors use ------------------------------
    def validate_model(self, model: dict, *, max_diagnostics: int = DEFAULT_MAX_DIAGNOSTICS,
                       severity: str = "all", full: bool = False) -> "tuple[int, dict]":
        started = time.monotonic()

        def elapsed() -> int:
            return int((time.monotonic() - started) * 1000)

        if not self._semaphore.acquire(timeout=self.queue_timeout):
            return 429, rejected_payload(
                "busy", "All validator slots stayed busy for too long.",
                validator=self.validator_name, elapsed_ms=elapsed())
        with self._busy_lock:
            self._busy += 1
        scratch = Path(tempfile.mkdtemp(prefix="npdev-validator-"))
        try:
            try:
                resolved = scratch.resolve()
            except OSError:
                resolved = scratch
            roots: "list[Path]" = [scratch] if resolved == scratch else [scratch, resolved]

            def clean(text: str) -> str:
                for root in roots:
                    text = sanitize(text, root, self.repo_root)
                return text

            model_path = resolved / "model.json"
            try:
                model_path.write_text(
                    json.dumps(model, indent=2, ensure_ascii=False, allow_nan=False), encoding="utf-8")
            except (ValueError, RecursionError):
                return 422, rejected_payload(
                    "invalid_json", "The model cannot be written as JSON (NaN, Infinity or too deep).",
                    validator=self.validator_name, elapsed_ms=elapsed())
            try:
                exit_code, report, stderr = self.validate_fn(model_path)
            except ValidatorUnavailable as exc:
                return 503, rejected_payload(
                    "validator_unavailable", clean(str(exc)) or "The validator is unavailable.",
                    validator=self.validator_name, elapsed_ms=elapsed())
            except Exception as exc:  # noqa: BLE001 -- anything else is an internal error, never a trace
                self._note_internal_error(exc)
                return 500, rejected_payload(
                    "internal_error", "The validation service had an internal error.",
                    validator=self.validator_name, elapsed_ms=elapsed())
            if report is None or exit_code not in (0, 2):
                detail = clean((stderr or "").strip()[-500:])
                return 503, rejected_payload(
                    "validator_unavailable", "The validator did not produce a report.",
                    validator=self.validator_name, elapsed_ms=elapsed(), detail=detail or None)
            report = _pack_lint.merge_into_report(report, _pack_lint.lint_model(model))
            payload = build_service_response(
                report, max_diagnostics=max_diagnostics, severity=severity, temp_dir=resolved,
                extra_roots=(scratch, self.repo_root), elapsed_ms=elapsed(),
                validator=self.validator_name, full=full)
            return 200, payload
        finally:
            with self._busy_lock:
                self._busy -= 1
            self._semaphore.release()
            shutil.rmtree(str(scratch), ignore_errors=True)

    def validate_raw(self, raw: "bytes | str", *, max_diagnostics: int = DEFAULT_MAX_DIAGNOSTICS,
                     severity: str = "all", full: bool = False) -> "tuple[int, dict]":
        try:
            model = parse_model_text(raw)
        except Rejection as exc:
            return exc.http_status, rejected_payload(exc.code, exc.message,
                                                     validator=self.validator_name, **exc.extra)
        return self.validate_model(model, max_diagnostics=max_diagnostics, severity=severity, full=full)

    def _note_internal_error(self, exc: BaseException) -> None:
        # The class name only: a message could carry a path or a fragment of someone's model.
        if not self.quiet:
            self.echo(f"internal error in validator: {type(exc).__name__}")

    # -- access log ----------------------------------------------------------
    def log_access(self, method: str, route: str, code: int, ms: int, body_size: int,
                   status: str, origin: str, ip: str) -> None:
        line = " ".join([
            datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), method, route, str(code),
            f"{ms}ms", f"{body_size}b", status or "-", origin or "-", ip or "-"])
        if not self.quiet:
            self.echo(line)
        if self.log_path is None:
            return
        with self._log_lock:
            try:
                self.log_path.parent.mkdir(parents=True, exist_ok=True)
                if self.log_path.exists() and self.log_path.stat().st_size > ACCESS_LOG_MAX_BYTES:
                    for index in range(ACCESS_LOG_KEEP, 0, -1):
                        older = self.log_path.with_name(f"{self.log_path.name}.{index}")
                        newer = self.log_path if index == 1 else self.log_path.with_name(
                            f"{self.log_path.name}.{index - 1}")
                        if newer.exists():
                            if older.exists():
                                older.unlink()
                            newer.rename(older)
                with self.log_path.open("a", encoding="utf-8") as handle:
                    handle.write(line + "\n")
            except OSError:
                pass  # logging must never break a request

    # -- MCP (Path B) --------------------------------------------------------
    def handle_remote_request(self, message: dict) -> "dict | None":
        """One JSON-RPC message -> its response (None for a notification). Stateless; the tool table is
        REMOTE_TOOL_SPECS -- never server.TOOL_HANDLERS."""
        method = message.get("method")
        msg_id = message.get("id")
        if method == "initialize":
            params = message.get("params") if isinstance(message.get("params"), dict) else {}
            requested = params.get("protocolVersion")
            version = requested if requested in MCP_SUPPORTED_VERSIONS else MCP_LATEST_VERSION
            return _mcp_server._result(msg_id, {
                "protocolVersion": version,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "npdev-validator", "version": SERVICE_VERSION},
                "instructions": MCP_INSTRUCTIONS,
            })
        if method == "ping":
            return _mcp_server._result(msg_id, {})
        if method == "tools/list":
            return _mcp_server._result(msg_id, {"tools": remote_tools_list()})
        if method == "tools/call":
            params = message.get("params")
            if not isinstance(params, dict):
                return _mcp_server._error(msg_id, -32602, "params must be an object")
            name = params.get("name")
            spec = REMOTE_TOOL_INDEX.get(name) if isinstance(name, str) else None
            handler = self.remote_handlers.get(name) if spec is not None else None
            if spec is None or handler is None:
                return _mcp_server._error(msg_id, -32602, f"unknown tool: {name}")
            arguments = params.get("arguments")
            if arguments is None:
                arguments = {}
            if not isinstance(arguments, dict):
                return _mcp_server._error(msg_id, -32602, "arguments must be an object")
            # Defence in depth: any key the spec does not list (e.g. `refresh`) is dropped here.
            allowed = {k: v for k, v in arguments.items() if k in spec["args"]}
            try:
                result = handler(allowed)
            except Exception as exc:  # noqa: BLE001
                self._note_internal_error(exc)
                result = _mcp_server._text_error("The tool failed on the host computer.")
            return _mcp_server._result(msg_id, _cap_result(result, SCHEMA_TEXT_CAP
                                                           if name == "npdev_get_schema" else TOOL_TEXT_CAP))
        if msg_id is None:
            return None
        return _mcp_server._error(msg_id, -32601, f"method not found: {method}")


# ---------------------------------------------------------------------------
# MCP tool table (the allowlist) and its handlers
# ---------------------------------------------------------------------------

MCP_INSTRUCTIONS = (
    "You are writing an NPDev model.json. Read npdev_core_context once. After every change call "
    "npdev_validate with the whole model. Fix errors, ignore warnings unless asked. Stop when "
    "status is passed or warning."
)

REMOTE_TOOL_SPECS: "list[dict]" = [
    {
        "name": "npdev_validate",
        "description": (
            "Validate an NPDev model.json. Pass the WHOLE model as `model`. Returns status "
            "(passed / warning / failed), the errors first, and `nextStep`. Call it after every "
            "change; fix every error and call again until status is passed or warning. Warnings "
            "are optional."),
        "args": ("model", "maxDiagnostics", "severity"),
        "inputSchema": {
            "type": "object",
            "properties": {
                "model": {"description": "The whole model.json, as a JSON object (or a string holding the JSON).",
                          "oneOf": [{"type": "object"}, {"type": "string"}]},
                "maxDiagnostics": {"type": "integer", "minimum": 1, "maximum": MAX_DIAGNOSTICS_CEILING,
                                   "description": f"At most this many diagnostics (default {MCP_DEFAULT_MAX_DIAGNOSTICS})."},
                "severity": {"type": "string", "enum": ["all", "error"],
                             "description": "`error` hides warnings."},
            },
            "required": ["model"],
        },
    },
    {
        "name": "npdev_core_context",
        "description": ("Read this once before writing a model: the rules and real examples of the "
                        "NPDev model.json format."),
        "args": ("variant",),
        "inputSchema": {
            "type": "object",
            "properties": {"variant": {"type": "string", "enum": ["compact", "full"],
                                       "description": "`compact` (default) is much smaller."}},
        },
    },
    {
        "name": "npdev_search_examples",
        "description": "Find real, working NPDev model snippets that match a short query.",
        "args": ("query", "limit"),
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string"},
                           "limit": {"type": "integer", "minimum": 1, "maximum": 10}},
            "required": ["query"],
        },
    },
    {
        "name": "npdev_search_fix",
        "description": ("Given one diagnostic message from npdev_validate, returns fixes that worked "
                        "before for the same kind of error."),
        "args": ("message", "path", "concept", "field", "limit"),
        "inputSchema": {
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "The diagnostic `message`."},
                "path": {"type": "string",
                         "description": "The diagnostic's own `path` value (a JSON location inside the model)."},
                "concept": {"type": "string"},
                "field": {"type": "string"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 10},
            },
            "required": ["message"],
        },
    },
    {
        "name": "npdev_check_support",
        "description": "Is a feature a known NPDev limit or gap? Pass free text or a ledger id.",
        "args": ("feature",),
        "inputSchema": {
            "type": "object",
            "properties": {"feature": {"type": "string"}},
            "required": ["feature"],
        },
    },
    {
        "name": "npdev_get_schema",
        "description": ("The canonical model.json JSON Schema. It is about 50k tokens: prefer "
                        "npdev_core_context."),
        "args": ("schema_name",),
        "inputSchema": {
            "type": "object",
            "properties": {"schema_name": {"type": "string", "enum": ["model"]}},
        },
    },
]
REMOTE_TOOL_INDEX = {spec["name"]: spec for spec in REMOTE_TOOL_SPECS}


def remote_tools_list() -> "list[dict]":
    return [{"name": s["name"], "description": s["description"], "inputSchema": s["inputSchema"]}
            for s in REMOTE_TOOL_SPECS]


def _cap_result(result: dict, cap: int) -> dict:
    content = result.get("content")
    if not isinstance(content, list):
        return result
    capped = []
    for block in content:
        if isinstance(block, dict) and isinstance(block.get("text"), str) and len(block["text"]) > cap:
            block = dict(block, text=block["text"][:cap] + "\n...[truncated]")
        capped.append(block)
    return dict(result, content=capped)


def _clamp_limit(args: dict, ceiling: int = 10) -> dict:
    out = dict(args)
    if "limit" in out:
        try:
            out["limit"] = max(1, min(int(out["limit"]), ceiling))
        except (TypeError, ValueError):
            out.pop("limit")
    return out


def _compact_args(args: dict) -> dict:
    """Only string arguments (npdev_check_support takes free text, nothing structured)."""
    return {k: v for k, v in args.items() if isinstance(v, str)}


def _remote_core_context(args: dict) -> dict:
    """Read-only: serves the prebuilt bundle; never runs a build per call (warmed at start-up)."""
    variant = "full" if args.get("variant") == "full" else "compact"
    out_dir = _mcp_server.build_root() / "npdev-ai" / "core-context"
    target = out_dir / ("bundle.json" if variant == "full" else "bundle-compact.json")
    if not target.is_file():
        return _mcp_server._text_error(
            "The authoring context is not prepared on the host computer. Ask the owner to restart the service.")
    try:
        return _mcp_server._text(json.loads(target.read_text(encoding="utf-8"))["markdown"])
    except (OSError, ValueError, KeyError):
        return _mcp_server._text_error("The authoring context on the host computer is unreadable.")


def _remote_get_schema(args: dict) -> dict:
    name = args.get("schema_name") or "model"
    if name != "model":
        return _mcp_server._text_error("Only the 'model' schema is available here.")
    return _mcp_server.tool_get_schema({"schema_name": "model"})


def default_remote_handlers(service: "ValidationService") -> "dict[str, Callable[[dict], dict]]":
    def validate(args: dict) -> dict:
        raw = args.get("model")
        max_diag = clamp_max_diagnostics(args.get("maxDiagnostics"), MCP_DEFAULT_MAX_DIAGNOSTICS)
        severity = "error" if args.get("severity") == "error" else "all"
        if isinstance(raw, (dict, str)):
            try:
                model = parse_model_text(raw) if isinstance(raw, str) else unwrap_envelope(raw)
            except Rejection as exc:
                payload = rejected_payload(exc.code, exc.message, validator=service.validator_name, **exc.extra)
                return _mcp_server._text_error(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
            code, payload = service.validate_model(model, max_diagnostics=max_diag, severity=severity)
        else:
            payload = rejected_payload("not_an_object", "`model` must be the whole model.json as an object.",
                                       validator=service.validator_name)
            code = 422
        text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        return _mcp_server._text_error(text) if payload.get("status") == "rejected" else _mcp_server._text(text)

    return {
        "npdev_validate": validate,
        "npdev_core_context": _remote_core_context,
        "npdev_search_examples": lambda a: _mcp_server.tool_search_examples(_clamp_limit(a)),
        "npdev_search_fix": lambda a: _mcp_server.tool_search_fix(_clamp_limit(a)),
        "npdev_check_support": lambda a: _mcp_server.tool_check_support(_compact_args(a)),
        "npdev_get_schema": _remote_get_schema,
    }


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

_PAGE_PATH = _HERE / "static" / "validate.html"
CORS_HEADERS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "600",
}


def _dump(payload: Any, pretty: bool = False) -> bytes:
    if pretty:
        return json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


class ValidatorHandler(BaseHTTPRequestHandler):
    server_version = "NPDevValidator/" + SERVICE_VERSION
    protocol_version = "HTTP/1.0"
    timeout = 30  # a stalled client must not pin a thread

    # BaseHTTPRequestHandler writes to stderr by default; the access log replaces it.
    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        return

    @property
    def service(self) -> ValidationService:
        return self.server.service  # type: ignore[attr-defined]

    # -- verbs ---------------------------------------------------------------
    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_POST(self) -> None:
        self._dispatch("POST")

    def do_OPTIONS(self) -> None:
        self._dispatch("OPTIONS")

    def do_DELETE(self) -> None:
        self._dispatch("DELETE")

    def do_PUT(self) -> None:
        self._dispatch("PUT")

    def do_PATCH(self) -> None:
        self._dispatch("PATCH")

    # -- plumbing --------------------------------------------------------------
    def _client_ip(self) -> str:
        forwarded = self.headers.get("CF-Connecting-IP")
        if forwarded:
            return forwarded.strip()[:64]
        chain = self.headers.get("X-Forwarded-For")
        if chain:  # the proxy appends the real peer last
            return chain.split(",")[-1].strip()[:64]
        return str(self.client_address[0]) if self.client_address else "-"

    def _send(self, status: int, body: bytes, content_type: str = "application/json; charset=utf-8",
              headers: "dict[str, str] | None" = None) -> None:
        self._code = status
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        except (ConnectionError, OSError):
            pass

    def _send_payload(self, status: int, payload: dict, *, cors: bool, pretty: bool = False,
                      extra_headers: "dict[str, str] | None" = None) -> None:
        headers: dict[str, str] = dict(CORS_HEADERS) if cors else {}
        if status == 429:
            headers["Retry-After"] = str(RETRY_AFTER_SECONDS)
        headers.update(extra_headers or {})
        self._vstatus = str(payload.get("status") or "-") if isinstance(payload, dict) else "-"
        self._send(status, _dump(payload, pretty), headers=headers)

    def _reject(self, status: int, code: str, message: str, *, cors: bool = True,
                extra_headers: "dict[str, str] | None" = None, **extra: Any) -> None:
        self._send_payload(status, rejected_payload(code, message, validator=self.service.validator_name,
                                                    **extra), cors=cors, extra_headers=extra_headers)

    def _read_body(self, limit: int) -> "bytes | None":
        """The request body, or None after a rejection was already sent (413/411/400)."""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = bytearray()
            seen = 0
            while True:
                size_line = self.rfile.readline(1024).strip()
                try:
                    size = int(size_line.split(b";")[0], 16)
                except ValueError:
                    self._reject(400, "bad_request", "Malformed chunked body.", cors=self._cors)
                    return None
                if size == 0:
                    while self.rfile.readline(1024).strip():  # trailers
                        pass
                    break
                seen += size
                if seen > 8 * limit:
                    break  # a sender that big is cut off without ceremony
                piece = self.rfile.read(size)
                if seen <= limit:
                    data.extend(piece)  # beyond the limit the bytes are read and dropped (drain)
                self.rfile.readline(8)
            self._body_size = seen
            if seen > limit:
                self._too_large(limit)
                return None
            return bytes(data)
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            self._reject(411, "length_required", "A Content-Length header is required.", cors=self._cors)
            return None
        try:
            length = int(raw_length)
            if length < 0:
                raise ValueError
        except ValueError:
            self._reject(400, "bad_request", "Invalid Content-Length.", cors=self._cors)
            return None
        self._body_size = length
        if length > limit:
            # Drain a bounded amount so the client can read our answer instead of a reset.
            remaining = min(length, 8 * limit)
            while remaining > 0:
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
            self._too_large(limit)
            return None
        return self.rfile.read(length) if length else b""

    def _too_large(self, limit: int) -> None:
        self._reject(413, "too_large", f"The body is larger than {limit} bytes.", cors=self._cors)

    # -- routing ---------------------------------------------------------------
    def _dispatch(self, method: str) -> None:
        started = time.monotonic()
        self._code = 0
        self._body_size = 0
        self._vstatus = "-"
        self._cors = False
        parts = urlsplit(self.path)
        route = parts.path or "/"
        logged_route = route
        try:
            if route.startswith("/mcp/") or route == "/mcp":
                logged_route = "/mcp/***"
                self._route_mcp(method, route)
            else:
                self._route_api(method, route, parse_qs(parts.query))
        except (ConnectionError, OSError):
            pass
        except Exception as exc:  # noqa: BLE001 -- last resort: no trace on the wire
            self.service._note_internal_error(exc)
            if not self._code:
                self._cors = route.startswith("/api/")
                self._reject(500, "internal_error", "The validation service had an internal error.",
                             cors=self._cors)
        finally:
            self.service.log_access(
                method, logged_route, self._code, int((time.monotonic() - started) * 1000),
                self._body_size, self._vstatus, self.headers.get("Origin") or "", self._client_ip())

    def _route_api(self, method: str, route: str, query: "dict[str, list[str]]") -> None:
        self._cors = route.startswith("/api/")
        if method == "OPTIONS" and self._cors:
            headers = dict(CORS_HEADERS)
            headers["Access-Control-Allow-Private-Network"] = "true"
            self._send(204, b"", headers=headers)
            return
        if route == "/" and method == "GET":
            self._serve_page()
            return
        if route == "/favicon.ico" and method == "GET":
            self._send(204, b"")
            return
        if route == "/api/health" and method == "GET":
            self._send_payload(200, self.service.health(), cors=True)
            return
        if route == "/api/schema/model" and method == "GET":
            schema = self.service.repo_root / "NPDevContract" / "schemas" / "model.schema.json"
            try:
                body = schema.read_bytes()
            except OSError:
                self._reject(503, "validator_unavailable", "The schema file is not available.")
                return
            self._send(200, body, headers=dict(CORS_HEADERS))
            return
        if route == "/api/validate":
            if method != "POST":
                self._reject(405, "method_not_allowed", "Use POST.", extra_headers={"Allow": "POST, OPTIONS"})
                return
            self._route_validate(query)
            return
        if route in ("/", "/api/health", "/api/schema/model"):
            self._reject(405, "method_not_allowed", "Use GET.", cors=self._cors,
                         extra_headers={"Allow": "GET, OPTIONS"})
            return
        self._reject(404, "not_found", "Unknown address.", cors=self._cors)

    def _serve_page(self) -> None:
        try:
            body = _PAGE_PATH.read_bytes()
        except OSError:
            self._reject(404, "not_found", "The page file is missing.", cors=False)
            return
        csp = ("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
               "connect-src *; base-uri 'none'; form-action 'none'")
        self._send(200, body, content_type="text/html; charset=utf-8",
                   headers={"Content-Security-Policy": csp})

    def _route_validate(self, query: "dict[str, list[str]]") -> None:
        if not media_type_ok(self.headers.get("Content-Type")):
            self._reject(415, "unsupported_media_type", "Content-Type must be JSON or plain text.")
            return
        body = self._read_body(self.service.max_body_bytes)
        if body is None:
            return
        first = lambda key, default=None: (query.get(key) or [default])[0]  # noqa: E731
        max_diag = clamp_max_diagnostics(first("maxDiagnostics"), DEFAULT_MAX_DIAGNOSTICS)
        severity = "error" if first("severity") == "error" else "all"
        full = first("full") in ("1", "true", "yes")
        pretty = first("pretty") in ("1", "true", "yes")
        status, payload = self.service.validate_raw(body, max_diagnostics=max_diag, severity=severity, full=full)
        self._send_payload(status, payload, cors=True, pretty=pretty)

    def _route_mcp(self, method: str, route: str) -> None:
        service = self.service
        token = route[len("/mcp/"):].strip("/") if route.startswith("/mcp/") else ""
        if not service.mcp_enabled or not token or not service.tokens.matches(token):
            self._reject(404, "not_found", "Unknown address.", cors=False)  # not 401: no confirmation
            return
        if method != "POST":
            self._send(405, b"", headers={"Allow": "POST"})
            return
        if not origin_allowed(self.headers.get("Origin")):
            self._send(403, _dump(_mcp_server._error(None, -32000, "Origin not allowed")))
            return
        body = self._read_body(service.max_body_bytes + MCP_BODY_MARGIN)
        if body is None:
            return
        try:
            message = json.loads(body.decode("utf-8-sig"))
        except (ValueError, RecursionError):
            self._send(200, _dump(_mcp_server._error(None, -32700, "Parse error")))
            return
        if isinstance(message, list):
            self._send(200, _dump(_mcp_server._error(None, -32600, "Batching is not supported")))
            return
        if not isinstance(message, dict):
            self._send(200, _dump(_mcp_server._error(None, -32600, "Invalid request")))
            return
        if "id" not in message or message.get("id") is None:
            self._send(202, b"")  # notification (or a client response): nothing to answer
            return
        if message.get("method") == "tools/call":
            wait = service.rate_limiter.check(self._client_ip())
            if wait:
                self._send(429, _dump(_mcp_server._error(message.get("id"), -32000, "Rate limit exceeded")),
                           headers={"Retry-After": str(wait)})
                return
        response = service.handle_remote_request(message)
        if response is None:
            self._send(202, b"")
            return
        self._vstatus = "mcp"
        self._send(200, _dump(response))


class ValidatorHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    # POSIX: SO_REUSEADDR only lets a TIME_WAIT port be reused. Windows: it would let a SECOND
    # process bind a port that is already listening, so it must stay off there.
    allow_reuse_address = os.name != "nt"
    request_queue_size = 32

    def __init__(self, address: "tuple[str, int]", service: ValidationService) -> None:
        self.service = service
        super().__init__(address, ValidatorHandler)


def make_server(service: ValidationService, host: str = DEFAULT_HOST, port: int = 0) -> ValidatorHTTPServer:
    return ValidatorHTTPServer((host, port), service)


# ---------------------------------------------------------------------------
# Start-up: toolchain check, warm-up, CLI entry
# ---------------------------------------------------------------------------

_cli_module: Any = None


def _npdev_cli() -> Any:
    global _cli_module
    if _cli_module is None:
        cli_dir = str(_mcp_server.repo_root() / "NPDevCli")
        if cli_dir not in sys.path:
            sys.path.insert(0, cli_dir)
        _cli_module = importlib.import_module("npdev_cli")
    return _cli_module


def find_toolchain() -> "tuple[str | None, str | None]":
    """(jar, java) exactly as `npdev validate model` would resolve them (read-only)."""
    cli = _npdev_cli()
    jar = cli._default_ai_tools_jar()
    java = cli.java_launcher()
    return (str(jar) if jar else None), (str(java) if java else None)


def port_is_free(host: str, port: int) -> bool:
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.settimeout(0.5)
        return probe.connect_ex((host, port)) != 0  # nobody answers -> free
    finally:
        probe.close()


def doctor_report(host: str = DEFAULT_HOST, port: int = DEFAULT_PORT) -> dict:
    problems: list[str] = []
    try:
        jar, java = find_toolchain()
    except Exception as exc:  # noqa: BLE001
        jar, java = None, None
        problems.append(f"could not inspect the toolchain: {type(exc).__name__}")
    if jar is None:
        problems.append(JAR_MISSING_MESSAGE)
    if java is None:
        problems.append("Java was not found. Install a JDK or set JAVA_HOME.")
    free = port_is_free(host, port)
    if not free:
        problems.append(f"Port {port} is already in use. Pick another with --port.")
    return {"jar": jar, "java": java, "port": port, "port_free": free, "ok": not problems,
            "problems": problems}


def warm_remote_artifacts(echo: Callable[[str], None]) -> None:
    """Make sure the read-only authoring artifacts exist, once, at start-up (never per call)."""
    out = _mcp_server.build_root() / "npdev-ai"
    try:
        result = _mcp_server.tool_core_context({"variant": "compact"})
        if result.get("isError"):
            echo("warning: authoring context not ready: " + result["content"][0]["text"][:200])
    except Exception as exc:  # noqa: BLE001
        echo(f"warning: authoring context not ready ({type(exc).__name__})")
    wanted = (("rag-index.json", "build_rag_index.py"), ("failure-index.json", "build_knowledge.py"))
    for artifact, script in wanted:
        if not (out / artifact).is_file():
            echo(f"building {artifact} once ({script}) ...")
            outcome = _mcp_server.run_ai_script(script, [])
            if not outcome.get("ok"):
                echo(f"warning: {script} failed; the matching tool will report it is not built")


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="validation_service.py",
        description="Local service that validates a model.json for online AIs (REST page + optional MCP).")
    parser.add_argument("--host", default=DEFAULT_HOST,
                        help="Address to bind (default 127.0.0.1; never expose this port directly).")
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("NPDEV_VALIDATOR_PORT") or DEFAULT_PORT))
    parser.add_argument("--mcp", action="store_true", help="Also serve MCP at /mcp/<token>.")
    parser.add_argument("--token-file", help="Where the MCP token lives (default: the Build folder).")
    parser.add_argument("--max-body-bytes", type=int, default=MAX_BODY_BYTES)
    parser.add_argument("--max-concurrent", type=int, default=DEFAULT_MAX_CONCURRENT)
    parser.add_argument("--capture", action="store_true",
                        help="Keep the validation journal ON (default: off, models of strangers stay out).")
    parser.add_argument("--quiet", action="store_true", help="No access lines on the console.")
    parser.add_argument("--i-know-this-is-public", dest="public_ok", action="store_true",
                        help="Allow --host 0.0.0.0 / a non-loopback address.")
    parser.add_argument("--doctor", action="store_true", help="Print {jar, java, port_free} as JSON and exit.")
    parser.add_argument("--rotate-token", action="store_true", help="Create a new MCP token and exit.")
    return parser


def main(argv: "list[str] | None" = None) -> int:
    args = build_arg_parser().parse_args(argv)
    token_file = Path(args.token_file).expanduser() if args.token_file else default_token_file()

    if args.doctor:
        report = doctor_report(args.host, args.port)
        print(json.dumps(report, indent=2))
        return 0 if report["ok"] else 1
    if args.rotate_token:
        token = load_or_create_token(token_file, rotate=True)
        print(f"New MCP token written to {token_file}. The running service picks it up on the next call.")
        print(f"Connector path: /mcp/{token}")
        return 0
    if args.host not in LOCAL_HOSTS and not args.public_ok:
        print("Refusing to bind a non-local address. Use 127.0.0.1 (a tunnel connects locally), "
              "or pass --i-know-this-is-public.", file=sys.stderr)
        return 1

    jar, java = find_toolchain()
    if jar is None:
        print(JAR_MISSING_MESSAGE, file=sys.stderr)
        return 1
    if java is None:
        print("Java was not found. Install a JDK or set JAVA_HOME.", file=sys.stderr)
        return 1

    tokens = TokenStore()
    token_text = None
    if args.mcp:
        token_text = load_or_create_token(token_file)
        tokens = TokenStore(path=token_file)
        print("Preparing the authoring context (once) ...", flush=True)
        warm_remote_artifacts(lambda line: print(line, flush=True))

    service = ValidationService(
        validate_fn=make_cli_validator(capture=args.capture), max_body_bytes=args.max_body_bytes,
        max_concurrent=args.max_concurrent, tokens=tokens, mcp=args.mcp, quiet=args.quiet,
        log_path=default_state_dir() / "logs" / "access.log")
    try:
        httpd = make_server(service, args.host, args.port)
    except OSError:
        print(f"Port {args.port} is already in use (or cannot be bound). Pick another with --port.",
              file=sys.stderr)
        return 1

    base = f"http://{args.host}:{args.port}"
    print(f"NPDev validator listening on {base} (page: /, API: /api/validate)", flush=True)
    if args.mcp:
        print(f"MCP connector (local): {base}/mcp/{token_text}", flush=True)
        print(f"To share with an online AI, run: cloudflared tunnel --url {base}", flush=True)
        print("Then add https://<your-tunnel>/mcp/<token> as a custom connector.", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
