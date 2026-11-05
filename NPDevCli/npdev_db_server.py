"""`npdev db provision --where private|service` -- a database server WITHOUT Docker.

The Manager's audience is not technical, so "you need Docker" cannot be the only answer. Two ways,
presented side by side by the Manager (its "?" explains which to pick):

  private   NPDev's OWN copy of Postgres, like its own Java and Python: a 23 MB download (verified
            against Maven Central's sha256) unpacked under the NPDev home, a data folder initialised
            with a generated password, listening on 127.0.0.1 only. No administrator prompt and
            nothing else on the computer changes. It is started now, and again each time the person
            signs in to Windows (one per-user "Run" entry, removable). Uninstalling = deleting the folder.

  service   The official installer, run by winget in a VISIBLE window that Windows asks permission
            for (administrator), installing Postgres as a Windows service that starts with the
            computer. The CLI waits for that window, then creates the app's database.

Postgres only, today. MySQL's official no-install archive is ~250 MB with no published sha256, and SQL
Server has no portable form at all and its Express setup cannot be driven silently reliably enough to
offer to a beginner without being proven on a clean machine; for both, the Manager says Docker is the
way, for now.

Either way the result has the SAME shape as a Docker `provision()` -- engine, name, host, port, user,
password, database -- so the Manager fills the New-app form exactly as it does for a container.
The admin password of the server is kept in `<npdev home>/db-servers/servers.json` (the person's own
profile), because every later app on the same server needs it too; a container's is not kept because
each app gets its own container.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import socket
import subprocess
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Callable

import npdev_engines
from npdev_db_provision import ProvisionError, new_password, pick_port

# Postgres binaries as the embedded-postgres projects ship them: a jar holding one .txz of bin/lib/share.
# Pinned version + the sha256 Maven Central publishes next to the jar.
PG_PRIVATE = {
    "version": "16.15",
    "url": "https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/"
           "16.15.0/embedded-postgres-binaries-windows-amd64-16.15.0.jar",
    "sha256": "51c7812dc1af47c9a2ccb64fe74efb88c515cff2da347ce72aab92b4cc8e1191",
}
PG_SERVICE = {
    "wingetId": "PostgreSQL.PostgreSQL.16",
    "installDir": r"C:\Program Files\PostgreSQL\16",
}
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "NPDev Postgres"
SUPPORTED = ("postgres",)

NO_WINDOW = 0x0800_0000 if os.name == "nt" else 0


def npdev_home() -> Path:
    """Same rule as npdev_monitor._npdev_home and the Manager's state::home (NPDEV_MANAGER_HOME wins)."""
    override = os.environ.get("NPDEV_MANAGER_HOME")
    if override and override.strip():
        return Path(override).expanduser()
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        return Path(os.environ["LOCALAPPDATA"]) / "NPDev"
    return Path.home() / ".local" / "share" / "npdev"


def servers_dir() -> Path:
    return npdev_home() / "db-servers"


def _registry_path() -> Path:
    return servers_dir() / "servers.json"


def load_registry() -> dict:
    try:
        return json.loads(_registry_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"servers": []}


def _save_registry(reg: dict) -> None:
    _registry_path().parent.mkdir(parents=True, exist_ok=True)
    tmp = _registry_path().with_suffix(".tmp")
    tmp.write_text(json.dumps(reg, indent=2), encoding="utf-8")
    tmp.replace(_registry_path())


def _find(reg: dict, engine: str, kind: str) -> dict | None:
    return next((s for s in reg["servers"] if s.get("engine") == engine and s.get("kind") == kind), None)


def _upsert(reg: dict, server: dict) -> None:
    reg["servers"] = [s for s in reg["servers"]
                      if not (s.get("engine") == server["engine"] and s.get("kind") == server["kind"])] + [server]
    _save_registry(reg)


def _listening(host: str, port: int, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _run(cmd: list[str], *, timeout: float, input_text: str | None = None, env: dict | None = None):
    return subprocess.run(cmd, input=input_text, capture_output=True, text=True, timeout=timeout,
                          creationflags=NO_WINDOW, env=env)


def _run_leaving_children(cmd: list[str], *, timeout: float, env: dict | None = None):
    """Like `_run`, for a command that leaves a long-lived child behind (`pg_ctl start` leaves the server).

    The output goes to a temporary FILE, never a pipe. The server inherits whatever standard handles the
    command had; with a pipe, `subprocess.run` can only return once the SERVER exits (the pipe has no EOF
    until then), and after its own timeout it blocks forever in `communicate()` on Windows. That is how
    `db provision --where private` hung for good with a perfectly healthy server already running. A file
    has no EOF to wait for. Found by the first real run on a real machine (2026-11-05)."""
    with tempfile.TemporaryFile(mode="w+", errors="replace") as out:
        done = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                              timeout=timeout, creationflags=NO_WINDOW, env=env)
        out.seek(0)
        return subprocess.CompletedProcess(cmd, done.returncode, out.read(), "")


# ---- private ------------------------------------------------------------------------------------------


def _pg_root() -> Path:
    return servers_dir() / f"postgres-{PG_PRIVATE['version']}"


def _bin(name: str, root: Path | None = None) -> Path:
    return (root or _pg_root()) / "bin" / (name + (".exe" if os.name == "nt" else ""))


def download_verified(url: str, sha256: str, fetch: Callable[[str], bytes] | None = None) -> bytes:
    data = (fetch or (lambda u: urllib.request.urlopen(u, timeout=120).read()))(url)
    got = hashlib.sha256(data).hexdigest()
    if got != sha256:
        raise ProvisionError("download-corrupt", f"The downloaded file does not match its published checksum ({got}).",
                             "Try again; if it keeps happening the download is being altered on the way.")
    return data


def unpack_postgres_jar(data: bytes, dest: Path) -> None:
    """jar -> the single .txz inside -> bin/ lib/ share/ under `dest`. Refuses any path that escapes `dest`."""
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        txz = next((n for n in jar.namelist() if n.endswith(".txz")), None)
        if txz is None:
            raise ProvisionError("download-corrupt", "The Postgres package has no binaries inside.", "Try again.")
        payload = jar.read(txz)
    staging = dest.with_name(dest.name + ".partial")
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:xz") as tar:
        root = staging.resolve()
        for member in tar.getmembers():
            target = (staging / member.name).resolve()
            if root not in target.parents and target != root:
                raise ProvisionError("download-corrupt", f"Refusing an archive path outside the folder: {member.name}")
        tar.extractall(staging, filter="data") if hasattr(tarfile, "data_filter") else tar.extractall(staging)
    shutil.rmtree(dest, ignore_errors=True)
    staging.replace(dest)


def _ensure_private_binaries(fetch=None) -> bool:
    if _bin("postgres").exists() and _bin("initdb").exists() and _bin("pg_ctl").exists():
        return False
    data = download_verified(PG_PRIVATE["url"], PG_PRIVATE["sha256"], fetch)
    unpack_postgres_jar(data, _pg_root())
    return True


def _init_cluster(data_dir: Path, password: str, port: int) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        pwfile = Path(tmp) / "pw"
        pwfile.write_text(password + "\n", encoding="utf-8")
        result = _run([str(_bin("initdb")), "-D", str(data_dir), "-U", "postgres", f"--pwfile={pwfile}",
                       "-A", "scram-sha-256", "-E", "UTF8", "--no-instructions"], timeout=300)
    if result.returncode != 0:
        shutil.rmtree(data_dir, ignore_errors=True)
        raise ProvisionError("init-failed", f"Postgres could not prepare its data folder: {result.stderr.strip()[-600:]}",
                             "Check there is free disk space, then try again.")
    with (data_dir / "postgresql.conf").open("a", encoding="utf-8") as conf:
        conf.write(f"\n# NPDev: reachable from this computer only.\nlisten_addresses = '127.0.0.1'\nport = {port}\n")


def _pg_ctl(server: dict, action: str, timeout: float = 90) -> subprocess.CompletedProcess:
    args = [str(_bin("pg_ctl")), action, "-D", server["dataDir"], "-w", "-t", "60"]
    if action == "start":
        args += ["-l", str(Path(server["dataDir"]).parent / "server.log")]
    if action == "stop":
        args += ["-m", "fast"]
    if action == "start":
        return _run_leaving_children(args, timeout=timeout)
    return _run(args, timeout=timeout)


def start_private(server: dict) -> None:
    if _listening("127.0.0.1", server["port"]):
        return
    result = _pg_ctl(server, "start")
    if result.returncode != 0 and not _listening("127.0.0.1", server["port"]):
        raise ProvisionError("start-failed", f"NPDev's Postgres did not start: {(result.stderr or result.stdout).strip()[-600:]}",
                             f"Its log is {Path(server['dataDir']).parent / 'server.log'}.")


def _create_database_private(server: dict, database: str) -> None:
    """Create `database` with the server's single-user mode: the downloaded package has no psql, and a
    private server belongs to NPDev, so a few seconds' stop/start is acceptable. Done once per database."""
    if database in server.get("databases", []):
        return
    was_running = _listening("127.0.0.1", server["port"])
    if was_running:
        _pg_ctl(server, "stop")
    result = _run([str(_bin("postgres")), "--single", "-D", server["dataDir"], "postgres"],
                  input_text=f'CREATE DATABASE "{database}";\n', timeout=120)
    text = (result.stdout or "") + (result.stderr or "")
    if "ERROR" in text and "already exists" not in text:
        start_private(server)
        raise ProvisionError("database-create-failed", f"Creating the database '{database}' failed: {text.strip()[-600:]}", "Try again.")
    server.setdefault("databases", []).append(database)
    start_private(server)


def autostart_command(server: dict) -> str:
    log = Path(server["dataDir"]).parent / "server.log"
    return f'"{_bin("pg_ctl")}" start -D "{server["dataDir"]}" -l "{log}"'


def _register_autostart(server: dict) -> bool:
    if os.name != "nt":
        return False
    import winreg  # noqa: PLC0415 -- Windows only
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.SET_VALUE) as key:
        winreg.SetValueEx(key, RUN_VALUE, 0, winreg.REG_SZ, autostart_command(server))
    return True


def provision_private(engine_key: str, *, app_name: str = "", port: int | None = None,
                      autostart: bool = True, fetch=None) -> dict:
    engine = _supported(engine_key)
    database = npdev_engines.database_name_for_app(app_name)
    reg = load_registry()
    server = _find(reg, engine, "private")
    downloaded = _ensure_private_binaries(fetch)
    if server is None or not Path(server["dataDir"], "PG_VERSION").exists():
        chosen = pick_port(5432, port, set(), lambda h, p, t: _listening(h, p, t))
        server = {"engine": engine, "kind": "private", "version": PG_PRIVATE["version"], "host": "127.0.0.1",
                  "port": chosen, "user": "postgres", "password": new_password(),
                  "dataDir": str(_pg_root().parent / "postgres-data"), "databases": []}
        _init_cluster(Path(server["dataDir"]), server["password"], chosen)
        _upsert(reg, server)
    start_private(server)
    _create_database_private(server, database)
    server["autostart"] = _register_autostart(server) if autostart else False
    _upsert(reg, server)
    return _result(server, database, name="NPDev's own Postgres", image=f"PostgreSQL {PG_PRIVATE['version']} (NPDev's own copy)",
                   restart="starts when you sign in to Windows" if server["autostart"] else "start it with npdev db server start",
                   downloaded=downloaded)


# ---- service ------------------------------------------------------------------------------------------


def service_install_script(password: str, port: int) -> str:
    """The PowerShell that opens ONE elevated, visible window running winget, and waits for it to close."""
    override = (f"--mode unattended --unattendedmodeui minimal --superpassword {password} "
                f"--serverport {port} --disable-components stackbuilder")
    inner = (f"winget install -e --id {PG_SERVICE['wingetId']} --accept-package-agreements "
             f"--accept-source-agreements --override '{override}'")
    escaped = inner.replace("'", "''")
    return ("$p = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList "
            f"'-NoProfile','-ExecutionPolicy','Bypass','-Command','{escaped}'; exit $p.ExitCode")


def provision_service(engine_key: str, *, app_name: str = "", port: int | None = None,
                      runner: Callable[..., subprocess.CompletedProcess] = _run) -> dict:
    engine = _supported(engine_key)
    if os.name != "nt":
        raise ProvisionError("unsupported-os", "Installing a Windows service is only possible on Windows.",
                             "Use your system's package manager, or Docker.")
    database = npdev_engines.database_name_for_app(app_name)
    reg = load_registry()
    server = _find(reg, engine, "service")
    if server is None or not _listening("127.0.0.1", server["port"]):
        chosen = pick_port(5432, port, set(), lambda h, p, t: _listening(h, p, t))
        password = new_password()
        result = runner(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                         service_install_script(password, chosen)], timeout=3600)
        text = (result.stderr or "") + (result.stdout or "")
        if "canceled" in text or "cancelled" in text:
            raise ProvisionError("admin-declined", "The administrator prompt was declined, so nothing was installed.",
                                 "Press the button again and answer Yes to Windows' question.")
        deadline = time.monotonic() + 60
        while not _listening("127.0.0.1", chosen) and time.monotonic() < deadline:
            time.sleep(2)
        if not _listening("127.0.0.1", chosen):
            raise ProvisionError("service-not-answering",
                                 f"The installer finished (exit {result.returncode}) but Postgres does not answer on port {chosen}.",
                                 "Restart the computer and try again; if it still fails, use NPDev's own copy instead.")
        server = {"engine": engine, "kind": "service", "host": "127.0.0.1", "port": chosen, "user": "postgres",
                  "password": password, "installDir": PG_SERVICE["installDir"], "databases": []}
        _upsert(reg, server)
    if database not in server.get("databases", []):
        psql = Path(server["installDir"]) / "bin" / "psql.exe"
        env = dict(os.environ, PGPASSWORD=server["password"])
        result = runner([str(psql), "-h", "127.0.0.1", "-p", str(server["port"]), "-U", "postgres", "-d", "postgres",
                         "-tAc", f'CREATE DATABASE "{database}"'], timeout=60, env=env)
        if result.returncode != 0 and "already exists" not in (result.stderr or ""):
            raise ProvisionError("database-create-failed", f"Creating the database '{database}' failed: {(result.stderr or '').strip()}",
                                 "Try again.")
        server.setdefault("databases", []).append(database)
        _upsert(reg, server)
    return _result(server, database, name="Postgres (Windows service)", image="PostgreSQL 16 (official installer)",
                   restart="starts with Windows", downloaded=False)


# ---- shared -------------------------------------------------------------------------------------------


def _supported(engine_key: str) -> str:
    try:
        engine = npdev_engines.resolve(engine_key)
    except ValueError as exc:
        raise ProvisionError("unknown-engine", str(exc)) from exc
    if engine["key"] not in SUPPORTED:
        raise ProvisionError("no-docker-free-option",
                             f"{engine['externalName']} can only be created for you with Docker, for now.",
                             "Use the Docker assistant, or pick Postgres, which NPDev can install without Docker.")
    return engine["key"]


def _result(server: dict, database: str, *, name: str, image: str, restart: str, downloaded: bool) -> dict:
    return {
        "engine": server["engine"], "where": server["kind"], "name": name, "image": image,
        "host": server["host"], "port": server["port"], "user": server["user"], "password": server["password"],
        "database": database, "restartPolicy": restart, "imagePulled": downloaded, "portChanged": server["port"] != 5432,
    }


def status() -> dict:
    """What NPDev installed without Docker, and whether each answers. Never prints a password."""
    out = []
    for s in load_registry()["servers"]:
        out.append({k: s.get(k) for k in ("engine", "kind", "version", "host", "port", "user", "databases", "autostart")}
                   | {"running": _listening(s.get("host", "127.0.0.1"), int(s.get("port", 0) or 0))})
    return {"servers": out, "supportedEngines": list(SUPPORTED)}


def start_all() -> list[str]:
    """Start NPDev's own servers that are not answering (the Manager calls this when it opens)."""
    started = []
    for s in load_registry()["servers"]:
        if s.get("kind") == "private" and Path(s["dataDir"], "PG_VERSION").exists() and not _listening("127.0.0.1", s["port"]):
            start_private(s)
            started.append(f"{s['engine']}:{s['port']}")
    return started
