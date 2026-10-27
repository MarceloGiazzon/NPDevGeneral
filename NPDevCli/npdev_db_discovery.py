"""`npdev db detect` -- find a database server the user already runs, instead of making them type it
(RUN-37). Stdlib only, read-only.

Two sources, shaped like `npdev ai detect`:
  * `docker ps -a` -- every container whose image is a known engine (or that publishes an engine's
    default port), with its REAL state. The state comes from Docker's `State` field (`running`,
    `exited`, ...), never from the free-text `Status` column, and only `running` counts as running:
    an exited Postgres is a stopped Postgres, not an available one.
  * a loopback connect to each server engine's default port, for a database that is not in Docker
    (or whose container was not found). A listener is reported as "something answers on 5432", not
    as "that is your Postgres" -- the same honesty doctor's `database-reachable` keeps.

What it never does: read a container's environment (that is where the password lives, so `docker
inspect` is not called), write anything, or start, stop or remove a container. Adopting a result is
the user's `npdev init --externally-provisioned ...`, which `init_args` spells out.

"docker is not on PATH" and "docker did not answer" are reported as such, never as "no databases".
"""
from __future__ import annotations

import json
import re
import shutil
import socket
import subprocess
from typing import Callable

import npdev_engines

# The LAST path segment of the image repository (tag and digest removed) -> engine key, matched
# EXACTLY. A substring match called `prom/mysqld-exporter`, `postgrest/postgrest`,
# `postgres-exporter` and `percona-server-mongodb` databases; a missed exotic image is still found
# by its published default port, a wrong guess is a "Use this" button on something that is not one.
IMAGE_ENGINES = {
    "postgres": "postgres", "postgresql": "postgres", "postgis": "postgres",
    "timescaledb": "postgres", "pgvector": "postgres",
    "mysql": "mysql", "mysql-server": "mysql", "mariadb": "mysql", "percona-server": "mysql",
    "azure-sql-edge": "sqlserver",
}
# `mcr.microsoft.com/mssql/server`: the final segment alone ("server") says nothing.
IMAGE_PATH_SUFFIXES = (("mssql/server", "sqlserver"),)

_PORT_MAPPING = re.compile(r"(?:(?P<bind>\[[^\]]+\]|[^\s,:]+):)?(?<![\d-])(?P<host>\d+)->(?P<container>\d+)/tcp")
_WILDCARD_BINDS = {"", "0.0.0.0", "::", "[::]"}


def engine_for_image(image: str) -> str | None:
    repository = (image or "").lower().split("@", 1)[0]
    if ":" in repository.rsplit("/", 1)[-1]:
        repository = repository.rsplit(":", 1)[0]
    for suffix, engine in IMAGE_PATH_SUFFIXES:
        if repository == suffix or repository.endswith("/" + suffix):
            return engine
    return IMAGE_ENGINES.get(repository.rsplit("/", 1)[-1])


def default_ports() -> dict[int, str]:
    """Default ports of the engines that run in a container, read from the registry so this cannot
    drift from `npdev engines`. `h2server` is a server but not containerized, and its 9092 is Kafka's
    default port -- offering every Kafka container as an H2 server is exactly the wrong guess."""
    return {npdev_engines.resolve(key)["port"]: key for key in npdev_engines.engine_keys()
            if npdev_engines.resolve(key).get("server") and npdev_engines.resolve(key).get("containerized")}


def parse_published_ports(ports: str) -> list[dict]:
    """`0.0.0.0:5433->5432/tcp, :::5433->5432/tcp` -> [{hostPort, containerPort, host}], de-duplicated
    across the IPv4/IPv6 pair. Port ranges and unpublished ports are skipped."""
    seen: set[tuple[int, int]] = set()
    published = []
    for match in _PORT_MAPPING.finditer(ports or ""):
        key = (int(match["host"]), int(match["container"]))
        if key in seen:
            continue
        seen.add(key)
        bind = (match["bind"] or "").strip("[]")
        published.append({"hostPort": key[0], "containerPort": key[1],
                          "host": "localhost" if bind in _WILDCARD_BINDS else bind})
    return published


def _run_docker_ps() -> tuple[str, str]:
    """(stdout, error). `error` is non-empty when docker is missing or did not answer."""
    if not shutil.which("docker"):
        return "", "docker is not on PATH"
    try:
        completed = subprocess.run(["docker", "ps", "-a", "--no-trunc", "--format", "{{json .}}"],
                                   capture_output=True, text=True, encoding="utf-8", errors="replace",
                                   timeout=10)
    except Exception as exc:  # timeout, permission, broken install
        return "", f"docker did not answer: {exc}"
    if completed.returncode != 0:
        return "", f"docker did not answer: {completed.stderr.strip() or completed.returncode}"
    return completed.stdout, ""


def _init_args(engine: str, host: str, port: int) -> list[str]:
    return ["--engine", engine, "--db-host", host, "--db-port", str(port), "--externally-provisioned"]


def _from_container(row: dict, ports_by_default: dict[int, str]) -> dict | None:
    # Docker emits strings; Podman's json can emit lists. Coerce rather than crash the whole command.
    image = row.get("Image") if isinstance(row.get("Image"), str) else ""
    names = row.get("Names")
    name = (names[0] if isinstance(names, list) and names else names) if names else ""
    name = name.split(",")[0] if isinstance(name, str) else ""
    ports = row.get("Ports")
    if isinstance(ports, list):
        ports = ", ".join(p for p in ports if isinstance(p, str))
    by_image = engine_for_image(image)
    published = parse_published_ports(ports if isinstance(ports, str) else "")
    by_port = next(((p, ports_by_default[p["containerPort"]]) for p in published
                    if p["containerPort"] in ports_by_default), None)
    engine = by_image or (by_port[1] if by_port else None)
    if engine is None:
        return None
    default = npdev_engines.resolve(engine)["port"]
    mapping = next((p for p in published if p["containerPort"] == default), None)
    state = (row.get("State") or "unknown").lower()
    record = {
        "source": "docker",
        "engine": engine,
        "name": name,
        "image": image,
        "matchedBy": "image" if by_image else "port",
        "state": state,
        "running": state == "running",
        "host": mapping["host"] if mapping else None,
        "port": mapping["hostPort"] if mapping else None,
        "initArgs": None,
        "note": None,
    }
    if mapping is None:
        record["note"] = (f"no published port for {default}/tcp -- not reachable from this machine "
                          "unless the app joins the container's Docker network")
    elif not record["running"]:
        record["note"] = f"container is {state}, not running -- NPDev will not start it for you"
    else:
        record["initArgs"] = _init_args(engine, mapping["host"], mapping["hostPort"])
    return record


def _tcp_listening(host: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def detect(timeout: float = 0.5, *, docker_ps: Callable[[], tuple[str, str]] = _run_docker_ps,
           listening: Callable[[str, int, float], bool] = _tcp_listening) -> dict:
    ports_by_default = default_ports()
    stdout, docker_error = docker_ps()
    databases: list[dict] = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except ValueError:
            continue
        record = _from_container(row, ports_by_default)
        if record:
            databases.append(record)

    covered = {d["port"] for d in databases if d["running"] and d["port"]}
    for port, engine in sorted(ports_by_default.items()):
        if port in covered or not listening("127.0.0.1", port, timeout):
            continue
        databases.append({
            "source": "listener", "engine": engine, "name": None, "image": None, "matchedBy": "port",
            "state": "listening", "running": True, "host": "localhost", "port": port,
            "initArgs": _init_args(engine, "localhost", port),
            "note": f"something answers on {port}; NPDev has not confirmed it is {engine} -- "
                    "`npdev db test-connection` does",
        })
    return {
        "docker": {"status": "ok" if not docker_error else "unavailable", "detail": docker_error or None},
        "databases": databases,
    }


def render(result: dict) -> str:
    lines = []
    if result["docker"]["status"] != "ok":
        lines.append(f"docker: {result['docker']['detail']} (containers were not checked)")
    for d in result["databases"]:
        where = f"{d['host']}:{d['port']}" if d["port"] else "no published port"
        label = d["name"] or "(no container)"
        lines.append(f"{d['engine']:<10} {label}  {where}  [{d['state']}]")
        if d["note"]:
            lines.append(f"           {d['note']}")
        if d["initArgs"]:
            lines.append("           adopt: npdev init <name> " + " ".join(d["initArgs"]))
    if not result["databases"]:
        lines.append("no database container or default-port listener found")
    return "\n".join(lines)
