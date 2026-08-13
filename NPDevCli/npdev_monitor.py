#!/usr/bin/env python3
"""`npdev monitor` -- read-only discovery, probing, engine detection and logs for generated apps.

MONITOR_PLAN A2/D9/D10. This module is the CLI half of The Monitor screen; the Manager's Tauri
commands are thin wrappers around these verbs (D1: no CLI behaviour lives in Rust). Everything here
therefore has to be usable, and useful, from a plain terminal.

THREE RULES THIS FILE IS BUILT AROUND
-------------------------------------
1. **Never resolve anything by NAME.** An app root is identified by CONTENTS -- a `.npdev-root`
   marker AND an `_ops` directory, the marker PAIR. REG-144 cost twelve days of red Linux CI because
   eleven resolvers walked up looking for a directory literally named `NPDev_General`; the marker
   alone is not enough either, because a generated FinalApp carries its own `.npdev-root`.
2. **No path literal, ever.** Not for the build root, not for the ScrapForAI engine. `Find-ScrapEngine`
   (the D9 reference implementation) exists precisely because the obvious way to write engine
   detection is to copy `scrapforai-harness.ps1:32`'s `$script:ScrapForAIDefaultRoot`, which names a
   drive that exists on one machine. Detection is service-first, then declared, then derived.
3. **Read-only.** `scan` and `probe` never mutate an app. `logs` reads; `logs export` writes only
   the zip it was asked for.

R9 (repo-less machines): stdlib only, psutil optional. The Manager ships a private Python with no
third-party packages, so an import that is merely usually present is an import that fails exactly
where this feature is most needed.
"""

from __future__ import annotations

import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = "npdev-monitor-scan.v1"

# The engine's own default. A PORT is not a path -- it is part of the protocol, the same way 8080 is,
# and `Find-ScrapEngine.ps1` carries the identical default.
DEFAULT_ENGINE_PORT = 3010

# Contents that identify a ScrapForAI engine root. Both, or it is not the engine -- the same two
# checks `Get-ScrapForAIRoot` / `Test-ScrapForAIReady` already make. Never the directory name.
_ENGINE_MARKER_FILE = Path("src") / "server.ts"
_ENGINE_MARKER_BIN_DIR = Path("node_modules") / ".bin"
_ENGINE_MARKER_BIN_PREFIX = "tsx"


# ---------------------------------------------------------------------------------------------
# App discovery
# ---------------------------------------------------------------------------------------------

def discovery_rule(path: Path) -> str | None:
    """Which contents-based rule (if any) says this directory is a generated NPDev app.

    `_ops/` is required by BOTH rules and is the load-bearing half: it is what the Monitor actually
    operates, and only generation writes it. The second half then has two acceptable forms:

      marker-pair          `.npdev-root` + `_ops`  -- MONITOR_PLAN D7's rule.
      resolved-plan        `_ops/resolved-db-plan.json` -- the generator's own signature.

    THE SECOND RULE IS NOT A WEAKENING, AND IT IS NOT OPTIONAL. Measured 2026-08-10: NOTHING emits
    `.npdev-root` into a generated app. It is written once, at this repo's own root, and tracked in
    git; `clean-sample-output.ps1` retains `App\\.npdev-root` as evidence and CLAUDE.md states that
    "a generated FinalApp carries its own `.npdev-root` marker" -- neither was true of any of the 30+
    apps in this machine's Build root, and `find -name .npdev-root` over all of them returns nothing.
    A marker-pair-only scan therefore finds ZERO apps, including every app a tester already has.

    So the emitter now writes the marker (making the documented invariant true going forward), AND
    discovery accepts the resolved plan, which is strictly STRONGER evidence than the marker anyway:
    a plan file names the appId, the engine and the port, whereas a marker is an empty assertion.
    Every record says which rule matched, because a discovery rule that silently varies is how a
    scan comes to mean something different than the person reading it thinks.

    `.npdev-root` alone is never enough -- this repo has one and is not an app, which is exactly why
    CLAUDE.md pairs it with the module directories elsewhere."""
    try:
        if not (path / "_ops").is_dir():
            return None
        if (path / ".npdev-root").is_file():
            return "marker-pair"
        if (path / "_ops" / "resolved-db-plan.json").is_file():
            return "resolved-plan"
        return None
    except OSError:
        return None


def is_app_root(path: Path) -> bool:
    return discovery_rule(path) is not None


# Directories that never contain a generated app and are expensive (or destructive of scan time) to
# walk. `build` is the big one: a built FinalApp's build tree is tens of thousands of files.
_SKIP_DIRS = {".git", "node_modules", "__pycache__", ".gradle", ".idea", ".vscode",
              "build", "data", "logs", "blobs", "target", "dist", "venv", ".venv"}


def _iter_app_roots(root: Path, max_depth: int) -> list[Path]:
    """Breadth-limited walk that KEEPS DESCENDING past a match.

    The obvious optimisation -- stop once a directory matches, since a FinalApp contains no other
    FinalApp -- is wrong here, and measurably so: a pre-QUAL-3 SHARED `_ops` sits at the OUTPUT ROOT
    beside the apps rather than inside one, so the output root matches first and the scan returns
    that single legacy entry while every real app underneath it stays invisible. Measured against
    this machine's Build root on 2026-08-10: 1 app found instead of the 30+ that are there.

    Skipping `build/` (and friends) is what makes descending affordable, and it is the honest
    trade -- a cheap scan that misses the apps is not cheap, it is wrong."""
    found: list[Path] = []
    frontier = [(root, 0)]
    while frontier:
        current, depth = frontier.pop(0)
        try:
            if not current.is_dir():
                continue
        except OSError:
            continue
        if is_app_root(current):
            found.append(current)
        if depth >= max_depth:
            continue
        try:
            children = sorted(p for p in current.iterdir() if p.is_dir())
        except OSError:
            continue
        for child in children:
            if child.name in _SKIP_DIRS or child.name.startswith("."):
                continue
            frontier.append((child, depth + 1))
    return found


# ---------------------------------------------------------------------------------------------
# Small facts about the machine
# ---------------------------------------------------------------------------------------------

def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class MachineSnapshot:
    """The facts that are about the MACHINE rather than about one app, gathered ONCE.

    This class is a performance fix with a correctness argument. `probe` originally asked the machine
    a fresh question per app: one `netstat -ano` and one `docker ps` each. Measured on this machine's
    Build root, 2026-08-10: **118 apps, 76 seconds** -- for a panel whose refresh loop is supposed to
    run every 30 seconds. A refresh that cannot finish inside its own interval is not slow, it is
    broken, and the window would have shown permanently stale cards while looking busy.

    Taking the snapshot once also makes a scan INTERNALLY CONSISTENT: 118 separate netstat calls
    describe 118 slightly different moments, so two apps could each be reported holding the same port.
    One snapshot cannot say that."""

    def __init__(self, *, want_docker: bool = True):
        self.ports: dict[int, list[int]] = {}
        self.enumerated = False
        self._docker: dict[str, dict] = {}
        self._docker_available = bool(shutil.which("docker")) if want_docker else False
        self._cmdlines: dict[int, str] | None = None
        self._collect_ports()

    def command_line(self, pid: int) -> str | None:
        """The command line of a process, for the identity check in `probe_app`. Collected LAZILY and
        once: it is a second machine-wide query, and most scans never need it (only apps whose port
        is occupied do)."""
        if self._cmdlines is None:
            self._cmdlines = _collect_command_lines()
        return self._cmdlines.get(pid)

    def _collect_ports(self) -> None:
        try:
            import psutil  # type: ignore

            for conn in psutil.net_connections(kind="inet"):
                if conn.laddr and conn.status == psutil.CONN_LISTEN and conn.pid:
                    self.ports.setdefault(conn.laddr.port, []).append(conn.pid)
            self.enumerated = True
            return
        except Exception:
            pass
        try:
            if os.name == "nt":
                out = subprocess.run(["netstat", "-ano", "-p", "TCP"],
                                     capture_output=True, text=True, timeout=20).stdout
                for line in out.splitlines():
                    parts = line.split()
                    if len(parts) >= 5 and parts[0].upper() == "TCP" and parts[3].upper() == "LISTENING":
                        try:
                            port = int(parts[1].rsplit(":", 1)[-1])
                            self.ports.setdefault(port, []).append(int(parts[4]))
                        except ValueError:
                            continue
                self.enumerated = True
                return
            out = subprocess.run(["ss", "-ltnp"], capture_output=True, text=True, timeout=20).stdout
            for line in out.splitlines():
                match = re.search(r":(\d+)\s", line)
                if not match:
                    continue
                port = int(match.group(1))
                for pid in re.finditer(r"pid=(\d+)", line):
                    self.ports.setdefault(port, []).append(int(pid.group(1)))
            self.enumerated = True
        except Exception:
            # Deliberately leaves `enumerated` False. The caller then falls back to a direct TCP
            # probe rather than reporting "not running" -- "we could not tell" and "it is stopped"
            # are different answers and only one of them is true.
            self.enumerated = False

    def listeners_on(self, port: int) -> list[int]:
        return sorted(set(self.ports.get(port, [])))

    def docker_state(self, container: str) -> dict | None:
        if not container:
            return None
        if container in self._docker:
            return self._docker[container]
        if not self._docker_available:
            state = {"containerName": container, "state": "unknown", "detail": "docker is not on PATH"}
        else:
            state = _docker_state(container)
        self._docker[container] = state
        return state


def _collect_command_lines() -> dict[int, str]:
    """pid -> command line, best effort. Empty when it cannot be determined, which the caller
    reports as `identity: unknown` -- never as a match and never as a mismatch."""
    try:
        import psutil  # type: ignore

        out: dict[int, str] = {}
        for process in psutil.process_iter(["pid", "cmdline"]):
            try:
                out[process.info["pid"]] = " ".join(process.info["cmdline"] or [])
            except Exception:
                continue
        return out
    except Exception:
        pass
    try:
        if os.name == "nt":
            completed = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 "Get-CimInstance Win32_Process | "
                 "Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"],
                capture_output=True, text=True, timeout=30)
            rows = json.loads(completed.stdout or "[]")
            if isinstance(rows, dict):
                rows = [rows]
            return {int(r["ProcessId"]): (r.get("CommandLine") or "")
                    for r in rows if r.get("ProcessId") is not None}
        completed = subprocess.run(["ps", "-eo", "pid=,args="], capture_output=True, text=True, timeout=20)
        out = {}
        for line in completed.stdout.splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) == 2 and parts[0].isdigit():
                out[int(parts[0])] = parts[1]
        return out
    except Exception:
        return {}


def _listeners_on(port: int) -> list[int]:
    """Single-app convenience. A `scan` never calls this -- it holds one MachineSnapshot for the
    whole sweep (see that class for why)."""
    if not port:
        return []
    return MachineSnapshot(want_docker=False).listeners_on(port)


def _tcp_open(host: str, port: int, timeout: float = 0.5) -> bool:
    if not port:
        return False
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _http_json(url: str, timeout: float = 1.0, headers: dict | None = None) -> tuple[int | None, object]:
    """(status, parsed-or-raw). A 4xx is a RESULT, not an exception: a 401 from an engine's status
    endpoint proves the engine is there, which is the whole point of the D9 service probe. Returns
    (None, error-text) only when nothing answered at all."""
    request = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", "replace")
            try:
                return response.status, json.loads(body)
            except json.JSONDecodeError:
                return response.status, body
    except urllib.error.HTTPError as exc:
        body = ""
        try:
            body = exc.read().decode("utf-8", "replace")
        except Exception:
            pass
        try:
            return exc.code, json.loads(body)
        except Exception:
            return exc.code, body
    except Exception as exc:  # URLError, socket.timeout, ssl, ...
        return None, str(exc)


# ---------------------------------------------------------------------------------------------
# The resolved plan -- the one file that knows what an app's database is
# ---------------------------------------------------------------------------------------------

def _read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception:
        return None


def _read_live_api_key(final_app_root: Path) -> str | None:
    """R7 Stage C: `secrets/api-key.env` holds the KEY that actually authenticates today
    (`Ensure-NpdevApiKey`, generated at first launch), which `resolved-db-plan.json`'s `apiKey` field
    -- baked in at generation time, before that file exists -- no longer is. Same
    `NAME=VALUE`-split-on-first-`=` parse the emitted PowerShell provisioner uses; the header value is
    the part of `VALUE` before its own `=` (the `key=tenantId:actorId:roles` encoding
    `RuntimeApiKeyAuthFilter` expects)."""
    key_file = final_app_root / "secrets" / "api-key.env"
    try:
        for raw_line in key_file.read_text(encoding="utf-8-sig").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            _, _, mapping = line.partition("=")
            api_key, _, _claims = mapping.partition("=")
            if api_key:
                return api_key
    except OSError:
        return None
    return None


def _resolve_app_relative(app_root: Path, raw: str | None) -> str | None:
    """PORT-1: the plan carries APP-RELATIVE paths ('data', '.') so a copied app finds its own
    database. Older plans carry absolute ones. Honour both, exactly as the emitted
    `Resolve-NpdevAppRelative` helper does -- a second, disagreeing rule here would be the twin-pair
    defect in a new place."""
    if not raw:
        return None
    candidate = Path(raw)
    if candidate.is_absolute():
        return str(candidate)
    return str((app_root / candidate).resolve())


def _find_info_json(app_root: Path) -> Path | None:
    """`InfoPageEmitter` writes into the GENERATED source set, not the app module's handwritten one.
    Both are on the classpath (`processResources` merges them), so both are plausible places to look
    -- generated first, because that is the one the emitter owns and the only one that exists in a
    plain generation."""
    for relative in (
        Path("npdev-generated") / "src" / "main" / "resources" / "static" / "info.json",
        Path("src") / "main" / "resources" / "static" / "info.json",
    ):
        candidate = app_root / relative
        if candidate.is_file():
            return candidate
    return None


def _newest_jar(app_root: Path) -> tuple[str | None, str | None]:
    libs = app_root / "build" / "libs"
    try:
        jars = sorted((p for p in libs.glob("*.jar")), key=lambda p: p.stat().st_mtime, reverse=True)
    except OSError:
        return None, None
    if not jars:
        return None, None
    return str(jars[0]), _iso(jars[0].stat().st_mtime)


def _docker_state(container: str) -> dict | None:
    """None means "we did not ask" (no container named) or "docker is not here". A container the
    plan names but docker cannot answer about is reported as state=unknown WITH the reason, never as
    stopped -- those are different facts and a Monitor card that shows a red dot for "docker is not
    installed" is lying about the app."""
    if not container:
        return None
    if not shutil.which("docker"):
        return {"containerName": container, "state": "unknown", "detail": "docker is not on PATH"}
    try:
        completed = subprocess.run(
            ["docker", "ps", "-a", "--filter", f"name=^/{container}$", "--format", "{{.State}}"],
            capture_output=True, text=True, timeout=8,
        )
    except Exception as exc:
        return {"containerName": container, "state": "unknown", "detail": str(exc)}
    state = completed.stdout.strip().splitlines()
    if not state:
        return {"containerName": container, "state": "absent", "detail": "no such container"}
    return {"containerName": container, "state": state[0], "detail": None}


# ---------------------------------------------------------------------------------------------
# probe -- one app
# ---------------------------------------------------------------------------------------------

def probe_app(app_dir: Path, *, include_info: bool = False, origin: str = "explicit",
              health_timeout: float = 1.0, snapshot: "MachineSnapshot | None" = None) -> dict:
    snapshot = snapshot or MachineSnapshot()
    app_root = Path(app_dir).expanduser().resolve()
    ops_dir = app_root / "_ops"
    plan = _read_json(ops_dir / "resolved-db-plan.json") or {}

    rule = discovery_rule(app_root)
    record: dict = {
        "name": plan.get("appId") or app_root.name,
        "appDir": str(app_root),
        "outputRoot": str(app_root.parent),
        "opsDir": str(ops_dir) if ops_dir.is_dir() else None,
        "origin": origin,
        "isAppRoot": rule is not None,
        "discoveredBy": rule,
        "probedAt": _utc_now(),
    }

    if rule is None:
        # An honest, structured refusal beats a half-filled card. Discovery is the whole contract, so
        # a directory that fails it is NAMED as such rather than probed anyway.
        record["status"] = "not-an-app"
        record["detail"] = (
            "not a generated NPDev app: needs an _ops directory, plus either a .npdev-root marker "
            "or _ops/resolved-db-plan.json. (A .npdev-root on its own is carried by the platform "
            "repo too, so it never identifies an app by itself.)"
        )
        record["health"] = "unknown"
        return record

    # Pre-QUAL-3 apps put `_ops` BESIDE the app (`<out>/_ops` + `<out>/<name>-app`) instead of inside
    # it. Reported, never silently normalised -- `_find_ops_root`'s own comment explains why a
    # fallback consulted first hands a NEW app the OLD shared toolbox. Every path below that means
    # "the app's own files" uses finalAppRoot; everything that means "the toolbox" uses app_root.
    final_app_root = app_root
    declared = _resolve_app_relative(app_root, plan.get("finalAppPath"))
    if declared and Path(declared).is_dir() and Path(declared).resolve() != app_root:
        final_app_root = Path(declared).resolve()
        record["opsLayout"] = "legacy-shared"
    else:
        record["opsLayout"] = "in-app"
    record["finalAppRoot"] = str(final_app_root)

    # --- portable facts from the plan -------------------------------------------------------
    engine = plan.get("engine")
    port = int(plan.get("serverPort") or 0)
    record["engine"] = engine
    record["storageMode"] = plan.get("storageMode")
    record["port"] = port or None
    record["externallyProvisioned"] = bool(plan.get("externallyProvisioned"))
    record["physicalDatabase"] = bool(plan.get("physicalDatabase"))
    record["schemaFingerprint"] = plan.get("schemaFingerprint")

    # Connection SUMMARY only -- never the password. This record is rendered in a window, copied into
    # chat windows, and (D10) included in an export bundle; the credential has no business in it.
    record["connection"] = {
        "engine": engine,
        "host": plan.get("host") or None,
        "port": plan.get("hostPort") or None,
        "database": plan.get("resolvedDatabaseName") or plan.get("requestedDatabaseName"),
        "username": plan.get("username") or None,
        "containerName": plan.get("containerName") or None,
        "driverClassName": plan.get("driverClassName") or None,
    }

    # D-b (close-the-gaps-2026-08-10): the API key the app ACTUALLY accepts, read from the plan.
    #
    # `InfoPageEmitter` publishes the row "API key header: X-Api-Key: dev-key" as a LITERAL, and that
    # is the right call for info.html -- the page is served unauthenticated, so it must never carry
    # an app's real key. But the value is not always `dev-key`: `OperationalRunbookEmitter` reads
    # `config.json`'s `trialDefaults.apiKey` and only falls back to `dev-key`, then writes the result
    # into the plan, which is what the app's own `Smoke-Test.ps1` puts in its header. So an app that
    # configured a different key has a published default that is simply wrong, and the reader has no
    # way to tell. The probe is the right place to answer it: it is already the surface for "facts
    # info.json deliberately does not carry".
    #
    # R7 Stage C: `plan.apiKey` is now a generation-time-only placeholder -- the real key is generated
    # at first launch into `secrets/api-key.env` and REPLACES `application-dev.yml`'s default outright
    # (env-var precedence on a String property, not a merge). `_read_live_api_key` is the actually-
    # working key once the app has been launched at least once; before that, the plan value is the
    # best available answer (the app hasn't picked a key yet either).
    #
    # Named `apiKey` on purpose -- `redact()`'s key pattern matches it, so the export bundle and the
    # assistant payload replace it with <redacted> with no extra rule. `authHeader` deliberately does
    # NOT match that pattern, so the header NAME survives redaction; knowing which header to send is
    # not a secret and is useless without the value.
    #
    # None, never an invented "dev-key", when the plan predates the field: an unresolvable input is
    # unknown, not a guess (the same X0 rule REG-131/REG-136 apply).
    record["authHeader"] = "X-Api-Key"
    record["apiKey"] = _read_live_api_key(final_app_root) or plan.get("apiKey") or None

    # --- PROBED facts: exactly the rows info.json deliberately does NOT carry (D2-a) ----------
    data_root = _resolve_app_relative(app_root, plan.get("resolvedDataRoot"))
    record["dataRoot"] = data_root
    db_name = plan.get("resolvedDatabaseName") or plan.get("requestedDatabaseName")
    record["dbFile"] = (
        str(Path(data_root) / f"{db_name}.mv.db") if data_root and db_name and str(engine).startswith("H2") else None
    )
    record["appDefinitionRoot"] = _resolve_app_relative(app_root, plan.get("appDefinitionRoot"))
    model_path = _resolve_app_relative(app_root, plan.get("modelPath"))
    if not model_path and record["appDefinitionRoot"]:
        candidate = Path(record["appDefinitionRoot"]) / "definition" / "model.json"
        model_path = str(candidate) if candidate.is_file() else None
    record["modelPath"] = model_path
    key_file = ops_dir / "SUPER_USER_KEY.txt"
    record["superUserKeyFile"] = str(key_file) if key_file.is_file() else None
    jar, built_at = _newest_jar(final_app_root)
    record["jarPath"] = jar
    record["builtAt"] = built_at
    record["logsDir"] = str(final_app_root / "logs") if (final_app_root / "logs").is_dir() else None

    info_json = _find_info_json(final_app_root)
    record["infoJsonPath"] = str(info_json) if info_json else None
    record["hasInfoJson"] = info_json is not None

    # --- liveness ---------------------------------------------------------------------------
    # R3: probe over 127.0.0.1, ALWAYS. `localhost` resolves to ::1 first on Windows while the app
    # binds IPv4, so a localhost probe of a perfectly healthy app reports it down. The user-facing
    # URL is reported separately, because that is the one they should click.
    record["probeBaseUrl"] = f"http://127.0.0.1:{port}" if port else None
    record["baseUrl"] = f"http://localhost:{port}" if port else None

    pids = snapshot.listeners_on(port) if port else []
    record["pid"] = pids[0] if pids else None
    record["pids"] = pids
    # When the snapshot could not enumerate at all, a direct connect is the only honest fallback --
    # but when it DID enumerate, its answer is authoritative and a per-app connect would just add
    # 0.5s x N to a scan for nothing.
    record["listening"] = bool(pids) or (
        (_tcp_open("127.0.0.1", port) if port else False) if not snapshot.enumerated else False
    )

    # WHOSE process is on that port. This check exists because the obvious implementation produced a
    # false GREEN in its first live test on 2026-08-10: a freshly built app was started, failed to
    # bind because a DIFFERENT app from an earlier session already held 8103, exited -- and the probe
    # reported `health: running`, because something healthy was indeed answering there. "A healthy
    # NPDev app is on this app's port" and "THIS app is running" are different claims, and a Monitor
    # card that conflates them is confidently wrong in exactly the situation a user opened it for.
    record["identity"] = "unknown"
    record["identityDetail"] = None
    if pids and jar:
        cmdline = snapshot.command_line(pids[0])
        if cmdline:
            haystack = cmdline.replace("/", "\\").lower()
            if str(final_app_root).replace("/", "\\").lower() in haystack:
                record["identity"] = "confirmed"
            else:
                record["identity"] = "mismatch"
                record["identityDetail"] = (
                    f"PID {pids[0]} holds port {port} but its command line does not mention this app: "
                    f"{cmdline[:220]}"
                )

    if not port:
        record["health"] = "unknown"
        record["healthDetail"] = "the resolved plan names no serverPort"
    elif not record["listening"]:
        record["health"] = "stopped"
        record["healthDetail"] = f"nothing is listening on 127.0.0.1:{port}"
    elif record["identity"] == "mismatch":
        # Its OWN state, not a flavour of running and not a flavour of error: the fix is to change a
        # port or stop the other app, and neither "running" nor "error" would say that.
        record["health"] = "port-conflict"
        record["healthDetail"] = (
            record["identityDetail"]
            + " -- this app is NOT the one serving. Stop the other process or give this app a "
              "different serverPort."
        )
    else:
        status, body = _http_json(f"http://127.0.0.1:{port}/actuator/health", timeout=health_timeout)
        if status is None:
            # Listening but not answering: the app is mid-boot, or wedged. Saying "starting" rather
            # than "failed" matters -- a generated app takes ~24s to come up, and a red card during
            # a normal start teaches the user to ignore red cards.
            record["health"] = "starting"
            record["healthDetail"] = f"port is open but /actuator/health did not answer: {body}"
        elif isinstance(body, dict) and body.get("status") == "UP":
            record["health"] = "running"
            record["healthDetail"] = (
                None if record["identity"] == "confirmed"
                # Never silently upgrade "probably" to "certainly": psutil is optional and the
                # PowerShell fallback can be blocked, so say which one this is.
                else "healthy on this app's port, but the owning process could not be identified"
            )
        else:
            record["health"] = "error"
            record["healthDetail"] = f"/actuator/health returned {status}: {json.dumps(body)[:300]}"

    record["docker"] = snapshot.docker_state(str(plan.get("containerName") or ""))

    # --- explorations at a glance (the EXPLORE light) ----------------------------------------
    record["explorations"] = _exploration_summary(app_root)

    if include_info:
        record["info"] = _read_json(info_json) if info_json else None

    record["status"] = "ok"
    return record


def _exploration_summary(app_root: Path) -> dict:
    """Enough for the Monitor card's EXPLORE light without loading the whole history: how many
    definitions exist, and the last run's verdict + age. STALE (the app was rebuilt after the last
    green run) is computed by the UI from builtAt vs lastRunAt -- same OVERDUE philosophy as the
    cadence ledger: staleness is visible, never a silent skip."""
    definitions = []
    for directory in (app_root / "_ops" / "explorations",):
        if directory.is_dir():
            definitions.extend(sorted(p.name for p in directory.glob("*.json")))
    index = app_root / "_ops" / "exploration-runs" / "runs.jsonl"
    last = None
    count = 0
    if index.is_file():
        try:
            for line in index.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                count += 1
                try:
                    last = json.loads(line)
                except json.JSONDecodeError:
                    continue
        except OSError:
            pass
    return {
        "definitionCount": len(definitions),
        "definitions": definitions,
        "runCount": count,
        "lastRunId": (last or {}).get("runId"),
        "lastRunAt": (last or {}).get("startedAt"),
        "lastRunGreen": ((last or {}).get("verdict") or {}).get("green"),
    }


# ---------------------------------------------------------------------------------------------
# scan -- many apps
# ---------------------------------------------------------------------------------------------

def scan_paths(paths: list[str], *, max_depth: int = 4, include_info: bool = False,
               health_timeout: float = 1.0) -> dict:
    snapshot = MachineSnapshot()
    seen: dict[str, dict] = {}
    searched = []
    for raw in paths:
        raw = raw.strip()
        if not raw:
            continue
        root = Path(raw).expanduser()
        searched.append({"path": str(root), "exists": root.is_dir()})
        if not root.is_dir():
            continue
        for app_root in _iter_app_roots(root.resolve(), max_depth):
            key = str(app_root)
            if key in seen:
                continue
            seen[key] = probe_app(app_root, include_info=include_info, origin="scan",
                                  health_timeout=health_timeout, snapshot=snapshot)
    apps = sorted(seen.values(), key=lambda a: (a.get("name") or "").lower())
    return {
        "schemaVersion": SCHEMA_VERSION,
        "command": "monitor scan",
        "ok": True,
        "scannedAt": _utc_now(),
        "searched": searched,
        "portsEnumerated": snapshot.enumerated,
        "apps": apps,
    }


# ---------------------------------------------------------------------------------------------
# D9 -- engine discovery. SERVICE-FIRST, then DECLARED, then DERIVED, then an honest not-found.
# ---------------------------------------------------------------------------------------------

def _engine_root_ok(root: Path | None) -> bool:
    if root is None:
        return False
    try:
        if not root.is_dir():
            return False
        if not (root / _ENGINE_MARKER_FILE).is_file():
            return False
        bin_dir = root / _ENGINE_MARKER_BIN_DIR
        if not bin_dir.is_dir():
            return False
        return any(p.name.startswith(_ENGINE_MARKER_BIN_PREFIX) for p in bin_dir.iterdir())
    except OSError:
        return False


def _derived_candidate_groups(workspace_root: Path | None):
    """Candidate locations computed from THIS machine at runtime, yielded in CHEAPEST-FIRST groups.

    Every entry is derived from something we were GIVEN -- a resolved workspace root, an environment
    variable, npm's own answer -- and none is typed in. That is the whole point:
    `scrapforai-harness.ps1:32` hardcodes an author's drive letter, and copying it into the Manager
    would ship a path that exists on one machine into the feature whose entire purpose is somebody
    else's machine.

    Grouped, and generated lazily, because the last group SHELLS OUT: `npm root -g` costs seconds on
    Windows. Asking it before the free filesystem checks made engine detection the slowest thing in
    the test suite and would have put the same delay in front of every Monitor refresh."""
    # 1. Siblings of the workspace root, walking up. Free.
    siblings: list[Path] = []
    cursor = workspace_root
    for _ in range(5):
        if cursor is None:
            break
        parent = cursor.parent
        if parent == cursor:
            break
        try:
            siblings.extend(sorted(p for p in parent.iterdir() if p.is_dir()))
        except OSError:
            pass
        cursor = parent
    yield siblings

    # 2. The Manager's own home (where F2/F3 would install one). Free.
    managed: list[Path] = []
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        managed.append(Path(local_app_data) / "NPDev" / "scrapforai")
    data_home = os.environ.get("XDG_DATA_HOME")
    if data_home:
        managed.append(Path(data_home) / "npdev" / "scrapforai")
    elif os.name != "nt":
        managed.append(Path.home() / ".local" / "share" / "npdev" / "scrapforai")
    yield managed

    # 3. A global npm install. Costs a subprocess, so it is asked last and only if nothing matched.
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm") or shutil.which("npm")
    if npm:
        try:
            out = subprocess.run([npm, "root", "-g"], capture_output=True, text=True, timeout=15).stdout.strip()
            if out:
                yield [Path(out.splitlines()[0].strip()) / "scrapforai"]
        except Exception:
            pass


def _derived_engine_candidates(workspace_root: Path | None) -> list[Path]:
    candidates: list[Path] = []
    for group in _derived_candidate_groups(workspace_root):
        candidates.extend(group)
    return candidates


def detect_engine(port: int = DEFAULT_ENGINE_PORT, configured_root: str | None = None,
                  workspace_root: Path | None = None) -> dict:
    """D9's four steps, in order, with the reason for each recorded on the answer."""
    endpoint = f"http://127.0.0.1:{port}"
    status, _ = _http_json(f"{endpoint}/v1/status", timeout=2.0)
    if status is not None and status < 500:
        # 401 counts. The engine's status endpoint is token-protected, so "refused my token" is
        # still proof it EXISTS and is RUNNING -- and this step deliberately never learns where it
        # lives, because it does not need to. (The PowerShell reference implementation gets this
        # subtly wrong: Invoke-WebRequest throws on 401 and its catch returns false.)
        return {
            "found": True, "state": "running", "via": "service-probe",
            "endpoint": endpoint, "port": port, "root": None,
            "detail": f"the engine answered /v1/status with HTTP {status}. Its location is not needed.",
        }

    for value, via in ((configured_root, "manager.json"), (os.environ.get("SCRAPFORAI_ROOT"), "SCRAPFORAI_ROOT")):
        if value and _engine_root_ok(Path(value).expanduser()):
            return {
                "found": True, "state": "installed-stopped", "via": via,
                "endpoint": None, "port": port, "root": str(Path(value).expanduser().resolve()),
                "detail": "a declared root, verified by CONTENTS (src/server.ts + node_modules/.bin/tsx). Offer Start.",
            }

    for group in _derived_candidate_groups(workspace_root):
        for candidate in group:
            if _engine_root_ok(candidate):
                return {
                    "found": True, "state": "installed-stopped", "via": "derived-candidate",
                    "endpoint": None, "port": port, "root": str(candidate.resolve()),
                    "detail": "found by scanning locations derived from this machine's own layout. "
                              "Offer Start, and offer to remember it.",
                }

    return {
        "found": False, "state": "not-found", "via": None,
        "endpoint": None, "port": port, "root": None,
        "detail": (
            f"no engine answered on 127.0.0.1:{port} and none was found on this machine. "
            "Set SCRAPFORAI_ROOT (or scrapforai_root in manager.json) if it is installed somewhere "
            "unusual, or install it -- exploration Play stays disabled until then."
        ),
    }


def engine_start_command(root: str, port: int, allowed_origins: list[str], api_key: str,
                         artifact_dir: str) -> list[str]:
    """The argv the Manager (or a terminal user) runs to start a found-but-stopped engine.

    Composed HERE, not in Rust and not in JS, for the reason R4 gives: the SSRF allowlist is the
    engine's only defence against a routine being pointed at something it should not reach, and two
    places composing origins is how one of them ends up composing them wrongly."""
    engine_root = Path(root)
    launcher = engine_root / "node_modules" / ".bin" / ("tsx.cmd" if os.name == "nt" else "tsx")
    return [str(launcher), str(engine_root / "src" / "server.ts")]


def engine_start_env(port: int, allowed_origins: list[str], api_key: str, artifact_dir: str) -> dict:
    origins = ",".join(sorted({o for o in allowed_origins if o}))
    return {
        "PORT": str(port),
        "HOST": "127.0.0.1",
        "NODE_ENV": "development",
        "SCRAPFORAI_API_KEY": api_key,
        "ALLOWED_TARGET_ORIGINS": origins,
        "ALLOWED_RESOURCE_ORIGINS": origins,
        "ARTIFACT_DIR": artifact_dir,
        "ALLOW_EVALUATE": "false",
        # A generated app renders a SPA that fetches /api/me and friends; the library defaults of
        # 10s/60s are too tight for a cold-cache first load. Same values the harness proved.
        "STEP_TIMEOUT_MS": "30000",
        "JOB_TIMEOUT_MS": "120000",
    }


# ---------------------------------------------------------------------------------------------
# D10 -- logs. VIEW, EXPORT, OPEN, per app.
# ---------------------------------------------------------------------------------------------

_LOG_SOURCES = ("app", "ops", "manager")


def _manager_logs_dir() -> Path | None:
    """The Manager's own log directory -- the one `state.rs:logs_dir()` creates. Derived from the
    same environment the Manager derives it from, never a literal, and honouring the same
    NPDEV_MANAGER_HOME override so a stub-mode run does not read the real one."""
    override = os.environ.get("NPDEV_MANAGER_HOME")
    if override and override.strip():
        return Path(override).expanduser() / "logs"
    if os.name == "nt":
        local_app_data = os.environ.get("LOCALAPPDATA")
        return Path(local_app_data) / "NPDev" / "logs" if local_app_data else None
    data_home = os.environ.get("XDG_DATA_HOME")
    base = Path(data_home) if data_home else Path.home() / ".local" / "share"
    return base / "npdev" / "logs"


def _log_files(app_root: Path, source: str) -> list[Path]:
    files: list[Path] = []
    logs_dir = app_root / "logs"
    if source in ("app", "all") and logs_dir.is_dir():
        files.extend(sorted(logs_dir.glob("app-*.log")))
    if source in ("ops", "all") and logs_dir.is_dir():
        files.extend(sorted(logs_dir.glob("ops-*.log")))
    if source in ("manager", "all"):
        manager_logs = _manager_logs_dir()
        if manager_logs and manager_logs.is_dir():
            files.extend(sorted(manager_logs.glob("*.log")))
    return files


def _tail(path: Path, lines: int) -> list[str]:
    try:
        with path.open("rb") as handle:
            handle.seek(0, io.SEEK_END)
            size = handle.tell()
            block = 8192
            data = b""
            while size > 0 and data.count(b"\n") <= lines:
                step = min(block, size)
                size -= step
                handle.seek(size)
                data = handle.read(step) + data
        text = data.decode("utf-8", "replace")
        return text.splitlines()[-lines:]
    except OSError as exc:
        return [f"(could not read {path.name}: {exc})"]


def collect_logs(app_dir: Path, source: str = "all", tail: int = 200) -> dict:
    app_root = Path(app_dir).expanduser().resolve()
    sources = []
    for name in (_LOG_SOURCES if source in ("all", None) else (source,)):
        files = _log_files(app_root, name)
        sources.append({
            "source": name,
            "directory": str(app_root / "logs") if name in ("app", "ops") else (
                str(_manager_logs_dir()) if _manager_logs_dir() else None),
            "files": [
                {
                    "name": path.name,
                    "path": str(path),
                    "bytes": path.stat().st_size,
                    "modifiedAt": _iso(path.stat().st_mtime),
                }
                for path in files
            ],
            "tail": _tail(files[-1], tail) if files else [],
            # The gap HANDOVER.md §5 sends a tester into. Saying so in words beats an empty list,
            # which reads as "nothing went wrong".
            "detail": None if files else _no_logs_detail(name),
        })
    return {
        "schemaVersion": "npdev-monitor-logs.v1",
        "command": "monitor logs",
        "ok": True,
        "appDir": str(app_root),
        "sources": sources,
    }


def _no_logs_detail(source: str) -> str:
    if source == "app":
        return ("no app run has been captured yet -- `_ops/Run-FinalApp.ps1` writes "
                "logs/app-<timestamp>.log from its first run onward")
    if source == "ops":
        return "no ops script has been run through `npdev` or the Manager for this app yet"
    return ("the Manager has written no log file here yet (it logs from beta1.15 onward; an older "
            "Manager created the directory and never wrote into it)")


_SECRET_KEYS = re.compile(
    r"(pass(word)?|pwd|secret|token|apikey|api[_-]?key|authorization|credential|privatekey)",
    re.IGNORECASE,
)


def redact(value: object) -> object:
    """E3-a / D10's redaction, in ONE place so the log bundle and the assistant payload cannot
    disagree about what a secret is. Key-name driven, like ScrapForAI's own `SECRET_KEY_PATTERN`:
    it catches CREDENTIALS, not content, which is why the assistant path additionally defaults to
    structure-only.

    `resolved-db-plan.json` carries a DB password and the entire point of `export` is that the file
    leaves the machine -- a log bundle is the MOST likely thing to be pasted into a chat window."""
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if isinstance(key, str) and _SECRET_KEYS.search(key):
                out[key] = "<redacted>" if item not in (None, "", 0) else item
            else:
                out[key] = redact(item)
        return out
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, str):
        # jdbc:...password=xyz and ?token=... shapes, which are values rather than keys.
        return re.sub(r"(?i)\b(password|pwd|token|api[_-]?key)=([^;&\s\"']+)", r"\1=<redacted>", value)
    return value


def export_logs(app_dir: Path, out_zip: Path, *, runs: int = 5) -> dict:
    """ONE file a tester can send. The support loop today is "tell me what happened" and the tester
    has nothing to send: `HANDOVER.md` §5 names a directory that does not exist, to collect files
    that are never written, for an app whose stdout was never captured. This is the artefact that
    was missing."""
    app_root = Path(app_dir).expanduser().resolve()
    out_zip = Path(out_zip).expanduser().resolve()
    out_zip.parent.mkdir(parents=True, exist_ok=True)

    included: list[str] = []
    skipped: list[dict] = []

    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as archive:
        for source in _LOG_SOURCES:
            for path in _log_files(app_root, source):
                try:
                    archive.write(path, f"logs/{source}/{path.name}")
                    included.append(f"logs/{source}/{path.name}")
                except OSError as exc:
                    skipped.append({"path": str(path), "reason": str(exc)})

        snapshot = probe_app(app_root, include_info=True)
        archive.writestr("probe.json", json.dumps(redact(snapshot), indent=2))
        included.append("probe.json")

        plan = _read_json(app_root / "_ops" / "resolved-db-plan.json")
        if plan is not None:
            archive.writestr("resolved-db-plan.redacted.json", json.dumps(redact(plan), indent=2))
            included.append("resolved-db-plan.redacted.json")

        info = app_root / "src" / "main" / "resources" / "static" / "info.json"
        if info.is_file():
            archive.write(info, "info.json")
            included.append("info.json")

        index = app_root / "_ops" / "exploration-runs" / "runs.jsonl"
        if index.is_file():
            try:
                lines = [line for line in index.read_text(encoding="utf-8").splitlines() if line.strip()]
                archive.writestr("exploration-runs.jsonl", "\n".join(lines[-runs:]) + "\n")
                included.append("exploration-runs.jsonl")
            except OSError as exc:
                skipped.append({"path": str(index), "reason": str(exc)})

        archive.writestr("README.txt", _export_readme(app_root))
        included.append("README.txt")

    return {
        "schemaVersion": "npdev-monitor-logs-export.v1",
        "command": "monitor logs export",
        "ok": True,
        "appDir": str(app_root),
        "zip": str(out_zip),
        "bytes": out_zip.stat().st_size,
        "included": included,
        "skipped": skipped,
        "redaction": "credential-shaped keys and password=/token= value pairs are replaced with <redacted>",
    }


def _export_readme(app_root: Path) -> str:
    return (
        "NPDev support bundle\n"
        "====================\n"
        f"app        : {app_root.name}\n"
        f"created    : {_utc_now()}\n"
        "\n"
        "What is in here:\n"
        "  logs/app/*      the generated app's own stdout+stderr, one file per run\n"
        "  logs/ops/*      output of _ops scripts run through npdev or the Manager\n"
        "  logs/manager/*  the NPDev Manager's own log\n"
        "  probe.json      what `npdev monitor probe` saw at export time\n"
        "  resolved-db-plan.redacted.json   the app's database plan, credentials removed\n"
        "  info.json       the app's own generated facts (URLs, flows, concepts)\n"
        "  exploration-runs.jsonl           the last few browser-exploration runs\n"
        "\n"
        "Credentials are redacted. Nothing here is sent anywhere by NPDev -- this file exists so\n"
        "YOU can send it, deliberately, to whoever is helping.\n"
    )


# ---------------------------------------------------------------------------------------------
# ops script execution -- the Monitor's run-command strip (B5), CLI half
# ---------------------------------------------------------------------------------------------

# Every script `OperationalRunbookEmitter` writes. An allowlist rather than "run whatever is named":
# the Monitor sends a script NAME from a window, and "run the file the caller named inside _ops" is
# one path-traversal away from running something else entirely.
OPS_SCRIPTS = {
    "create-environment": "Create-Environment.ps1",
    "start-environment": "Start-Environment.ps1",
    "stop-environment": "Stop-Environment.ps1",
    "status-environment": "Status-Environment.ps1",
    "build-finalapp": "Build-FinalApp.ps1",
    "run-finalapp": "Run-FinalApp.ps1",
    "smoke-test": "Smoke-Test.ps1",
    "print-db-connection-info": "Print-DbConnectionInfo.ps1",
    "reset-environment": "Reset-Environment.ps1",
}

# The two that destroy something. They carry the SAME acknowledgement token the generated script
# demands -- a button is far easier to press than that token is to type, so the window must be at
# least as careful as the terminal (M14's rule, restated for the Monitor).
DESTRUCTIVE_OPS = {"reset-environment": "I_UNDERSTAND_DB_DATA_WILL_BE_DELETED"}


def ops_script_path(app_dir: Path, key: str) -> Path | None:
    name = OPS_SCRIPTS.get(key)
    if not name:
        return None
    candidate = Path(app_dir).expanduser().resolve() / "_ops" / name
    return candidate if candidate.is_file() else None


def ops_log_path(app_dir: Path, key: str) -> Path:
    logs_dir = Path(app_dir).expanduser().resolve() / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return logs_dir / f"ops-{key}-{stamp}.log"


def find_powershell() -> str | None:
    for candidate in ("pwsh", "powershell"):
        found = shutil.which(candidate)
        if found:
            return found
    return None
