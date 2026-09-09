"""npdev host -- tunnel provider abstraction (H9 of NPDEV_HOST_IMPLEMENTATION_PLAN.md).

No provider-specific code anywhere else in the `npdev host` surface -- `npdev host share`/`down`
call only `start`/`stop`/`is_alive`/`available_providers` below.

Implementation notes that would otherwise cost an afternoon (LANDMINES #12):
  - `cloudflared tunnel --url http://127.0.0.1:<port>` prints its URL on STDERR, not stdout -- both
    streams are read here.
  - Never pipe a tunnel's output through a shell/`tail`: the child never exits, so a shell pipe
    buffers forever and the URL never appears. Both streams are read directly, off background
    threads, with a deadline.
  - ngrok's URL is read from its own local API (`http://127.0.0.1:4040/api/tunnels`), not scraped
    from output -- ngrok's stdout/stderr are discarded entirely.

R9 (repo-less machines, matching npdev_monitor.py's own rule): stdlib only, psutil optional.

Testing: `register_provider(name, starter)` lets a test inject a FAKE provider -- the suite must
never require a real tunnel binary to run (H9 warning).
"""

from __future__ import annotations

import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Callable

_CLOUDFLARED_URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _pump_lines(stream, sink: "queue.Queue[str]") -> None:
    try:
        for raw in iter(stream.readline, ""):
            if not raw:
                break
            sink.put(raw)
    except (OSError, ValueError):
        pass


def _start_cloudflared(port: int, *, timeout: float = 30.0, scheme: str = "http") -> dict:
    binary = shutil.which("cloudflared")
    if not binary:
        raise RuntimeError("cloudflared is not on PATH -- install it, or pass --provider ngrok")

    cmd = [binary, "tunnel", "--url", f"{scheme}://127.0.0.1:{port}"]
    if scheme == "https":
        # The shared ingress (H11) terminates its OWN TLS with a self-signed "tls internal"
        # cert -- correct for a browser hitting this box directly, but cloudflared would refuse
        # to trust it without this flag.
        cmd.append("--no-tls-verify")

    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
    )
    lines: "queue.Queue[str]" = queue.Queue()
    for stream in (proc.stdout, proc.stderr):
        threading.Thread(target=_pump_lines, args=(stream, lines), daemon=True).start()

    deadline = time.monotonic() + timeout
    url = None
    while time.monotonic() < deadline:
        try:
            line = lines.get(timeout=0.25)
        except queue.Empty:
            if proc.poll() is not None:
                break
            continue
        match = _CLOUDFLARED_URL_RE.search(line)
        if match:
            url = match.group(0)
            break

    if url is None:
        proc.terminate()
        raise RuntimeError(f"cloudflared did not print a *.trycloudflare.com URL within {timeout}s")

    return {"url": url, "pid": proc.pid, "provider": "cloudflared", "startedAt": _now_iso()}


def _start_ngrok(port: int, *, timeout: float = 30.0, scheme: str = "http") -> dict:
    binary = shutil.which("ngrok")
    if not binary:
        raise RuntimeError("ngrok is not on PATH -- install it, or pass --provider cloudflared")

    target = f"https://127.0.0.1:{port}" if scheme == "https" else str(port)
    proc = subprocess.Popen(
        [binary, "http", target, "--log=stdout"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )

    deadline = time.monotonic() + timeout
    url = None
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            break
        try:
            with urllib.request.urlopen("http://127.0.0.1:4040/api/tunnels", timeout=1.5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            for entry in data.get("tunnels", []):
                if entry.get("proto") == "https":
                    url = entry.get("public_url")
                    break
        except (urllib.error.URLError, OSError, ValueError):
            pass
        if url:
            break
        time.sleep(0.5)

    if url is None:
        proc.terminate()
        raise RuntimeError(
            f"ngrok did not expose an https tunnel within {timeout}s "
            "(checked http://127.0.0.1:4040/api/tunnels)"
        )

    return {"url": url, "pid": proc.pid, "provider": "ngrok", "startedAt": _now_iso()}


_PROVIDER_STARTERS: dict[str, Callable[..., dict]] = {}


def register_provider(name: str, starter: Callable[..., dict]) -> None:
    """Registers (or overrides) a provider's starter function -- `starter(port, *, timeout)`. This
    is how a test injects a FAKE provider so the suite never needs a real tunnel binary."""
    _PROVIDER_STARTERS[name] = starter


register_provider("cloudflared", _start_cloudflared)
register_provider("ngrok", _start_ngrok)


def available_providers() -> list[str]:
    """Provider names found on PATH right now (registered fakes are not reported here -- this
    answers 'what can `share` actually launch', not 'what is registered')."""
    return [name for name in ("cloudflared", "ngrok") if shutil.which(name)]


def start(provider: str, port: int, *, timeout: float = 30.0, scheme: str = "http") -> dict:
    """Spawns `provider`'s tunnel pointed at `<scheme>://127.0.0.1:<port>`. Returns
    `{"url", "pid", "provider", "startedAt"}` -- write it straight into `data/host-state.json` via
    `npdev_host.write_state`. `scheme="https"` is for H11's shared-ingress target (its `:443` site
    terminates its own self-signed TLS); plain per-app ports stay `http` (the default)."""
    starter = _PROVIDER_STARTERS.get(provider)
    if starter is None:
        raise ValueError(f"unknown tunnel provider {provider!r} (known: {sorted(_PROVIDER_STARTERS)})")
    return starter(port, timeout=timeout, scheme=scheme)


def is_alive(state: dict) -> bool:
    """Whether the process named in `state["pid"]` is still running. Never assumes -- a state
    dict outlives the process that wrote it (a machine restart, a crash) and this is read back by
    a LATER, unrelated CLI invocation with no live handle to the original `Popen`."""
    pid = state.get("pid")
    if not pid:
        return False
    try:
        import psutil  # type: ignore

        return psutil.pid_exists(int(pid))
    except ImportError:
        pass
    if os.name == "nt":
        try:
            result = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}"], capture_output=True, text=True, timeout=5,
            )
            return str(pid) in result.stdout
        except OSError:
            return False
    try:
        os.kill(int(pid), 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def stop(state: dict) -> bool:
    """Stops the tunnel `state` describes. Safe to call when nothing is up (returns True, a no-op)
    -- `npdev host down` must never fail just because there was nothing to stop."""
    pid = state.get("pid")
    if not pid or not is_alive(state):
        return True

    try:
        import psutil  # type: ignore

        process = psutil.Process(int(pid))
        process.terminate()
        try:
            process.wait(timeout=5)
        except psutil.TimeoutExpired:
            process.kill()
        return True
    except ImportError:
        pass

    if os.name == "nt":
        try:
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, timeout=10)
        except OSError:
            return False
    else:
        import signal

        try:
            os.kill(int(pid), signal.SIGTERM)
            time.sleep(0.5)
            if is_alive(state):
                os.kill(int(pid), signal.SIGKILL)
        except (OSError, ProcessLookupError):
            pass

    return not is_alive(state)
