"""`npdev db provision` -- create a NEW database container when there is nothing to adopt.

The New-app screen's "create one for me": the user picked Postgres, MySQL or SQL Server, Docker is
running, and no server of that engine is up. `npdev db detect` (RUN-37) is deliberately read-only, so
creating the container is this separate, explicit command -- never a side effect of looking.

What it does, in order, and stops at the first thing that fails:
  1. asks Docker for every container (names and published ports), so the new one cannot collide;
  2. picks a free host port (the engine's default if it is free, else the next free one) and a name
     (`npdev-<engine>`, then `npdev-<engine>-2`, ...);
  3. pulls the image if it is not already on this machine;
  4. `docker run -d` it, published on 127.0.0.1 ONLY (a development database with a generated
     password is not something to offer to the LAN) with `--restart unless-stopped`, so it is back
     after Docker restarts -- NPDev will not start an adopted server for the user;
  5. waits until the engine can SERVE, not merely until the container exists;
  6. makes sure the app's database exists (SQL Server has no env variable for it).

The container is handed back as a server the user ADOPTS: the Manager fills host, port, user and
password into the form and ticks "this server is mine". The app's own `_ops/Start-Environment` is the
other way to get a container (created at first run, owned by the app); it needs a generated app, which
does not exist yet at New-app time, so it cannot be used here.

The image, environment, engine arguments and readiness probe below mirror
`NPDevGenerator/.../npdev/engine-profiles.json` (rule E15: that file is where engine facts live).
`NPDevCli/tests/test_db_provision.py` reads the JSON and fails the moment the two disagree, so this is
a pinned copy, not a second source of truth. The one deliberate difference: a fresh container here
has ONLY the engine's admin account (postgres / root / sa) -- the profile's per-app user is something
the app's own toolbox creates for a container it owns.
"""
from __future__ import annotations

import json
import re
import secrets
import shutil
import string
import subprocess
import time
from typing import Callable

import npdev_db_discovery
import npdev_engines

SQLCMD = "/opt/mssql-tools18/bin/sqlcmd"

# `{password}` and `{database}` are filled in at run time. Keys are CLI engine spellings.
CONTAINERS: dict[str, dict] = {
    "postgres": {
        "image": "postgres:16",
        "containerPort": 5432,
        "adminUser": "postgres",
        "env": {"POSTGRES_PASSWORD": "{password}", "POSTGRES_DB": "{database}"},
        "runArgs": [],
        # A real query over TCP, not pg_isready: the image first runs a TEMPORARY server that listens on the
        # unix socket only, creates the database, stops it and starts the real one. pg_isready over the
        # socket answers "ready" during that first phase -- seconds before anything can connect. Found by
        # running this against a real Postgres, not by a test: a scripted Docker cannot have this quirk.
        # It also proves the database the app needs exists.
        "ready": {"exec": ["psql", "-h", "127.0.0.1", "-U", "postgres", "-d", "{database}", "-tAc", "select 1"],
                  "env": {"PGPASSWORD": "{password}"}, "timeoutSeconds": 60},
        "createDatabase": None,
    },
    "mysql": {
        "image": "mysql:8.4",
        "containerPort": 3306,
        "adminUser": "root",
        "env": {"MYSQL_ROOT_PASSWORD": "{password}", "MYSQL_DATABASE": "{database}"},
        # NOT optional: the legacy three-byte 'utf8' silently truncates anything outside the BMP.
        "runArgs": ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci"],
        # -h 127.0.0.1 forces TCP, which the temporary init-time server does not offer: so this
        # answers only once the REAL server is up. The password travels in the exec's environment.
        "ready": {"exec": ["mysqladmin", "ping", "-h", "127.0.0.1", "-uroot"],
                  "env": {"MYSQL_PWD": "{password}"}, "timeoutSeconds": 90},
        "createDatabase": None,
    },
    "sqlserver": {
        "image": "mcr.microsoft.com/mssql/server:2022-latest",
        "containerPort": 1433,
        "adminUser": "sa",
        "env": {"ACCEPT_EULA": "Y", "MSSQL_SA_PASSWORD": "{password}", "MSSQL_PID": "Developer"},
        "runArgs": [],
        "ready": {"exec": [SQLCMD, "-S", "localhost", "-U", "sa", "-C", "-Q", "SELECT 1"],
                  "env": {"SQLCMDPASSWORD": "{password}"}, "timeoutSeconds": 180},
        # No MSSQL_DATABASE variable exists: the database is created after the server answers.
        "createDatabase": {
            "exec": [SQLCMD, "-S", "localhost", "-U", "sa", "-C", "-b", "-Q",
                     "IF DB_ID('{database}') IS NULL CREATE DATABASE [{database}]"],
            "env": {"SQLCMDPASSWORD": "{password}"},
        },
    },
}

# How far past the engine's default port to look for a free one.
PORT_SEARCH_SPAN = 100
CONTAINER_NAME = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]*$")

# (args after `docker`, timeout seconds) -> (exit code, stdout, stderr)
DockerRun = Callable[[list[str], float], tuple[int, str, str]]


class ProvisionError(Exception):
    """A refusal or failure with a stable `code` the Manager can branch on, and a `fix` in words."""

    def __init__(self, code: str, message: str, fix: str = ""):
        super().__init__(message)
        self.code = code
        self.fix = fix


def _docker(args: list[str], timeout: float) -> tuple[int, str, str]:
    if not shutil.which("docker"):
        return 127, "", npdev_db_discovery.DOCKER_NOT_ON_PATH
    try:
        done = subprocess.run(["docker", *args], capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, "", f"timed out after {int(timeout)}s"
    except OSError as exc:
        return 127, "", str(exc)
    return done.returncode, done.stdout, done.stderr


def new_password() -> str:
    """24 letters and digits with at least one upper, one lower and one digit.

    Letters and digits only, on purpose: it goes into a JDBC URL-adjacent config, a `docker -e` and
    a form field, and SQL Server's strong-password policy (three of four character classes) makes a
    weak one EXIT the container during start-up, which surfaces as "connection refused".
    """
    rng = secrets.SystemRandom()
    pool = string.ascii_letters + string.digits
    chars = [secrets.choice(string.ascii_uppercase), secrets.choice(string.ascii_lowercase),
             secrets.choice(string.digits)] + [secrets.choice(pool) for _ in range(21)]
    rng.shuffle(chars)
    return "".join(chars)


def _fill(template: str, password: str, database: str) -> str:
    return template.replace("{password}", password).replace("{database}", database)


def _exec_args(container: str, spec: dict, password: str, database: str) -> list[str]:
    args = ["exec"]
    for key, value in spec.get("env", {}).items():
        args += ["-e", f"{key}={_fill(value, password, database)}"]
    args.append(container)
    return args + [_fill(part, password, database) for part in spec["exec"]]


def _existing(docker: DockerRun) -> tuple[set[str], set[int]]:
    """(container names, host ports published by ANY container, running or not)."""
    code, out, err = docker(["ps", "-a", "--no-trunc", "--format", "{{json .}}"], 20)
    if code != 0:
        raise ProvisionError(
            "docker-unavailable",
            f"Docker did not answer: {(err or '').strip() or 'exit ' + str(code)}",
            "Install or start Docker Desktop, then try again.")
    names: set[str] = set()
    ports: set[int] = set()
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except ValueError:
            continue
        raw = row.get("Names")
        raw = raw[0] if isinstance(raw, list) and raw else raw
        if isinstance(raw, str) and raw:
            names.add(raw.split(",")[0])
        text = row.get("Ports")
        text = ", ".join(p for p in text if isinstance(p, str)) if isinstance(text, list) else text
        for mapping in npdev_db_discovery.parse_published_ports(text if isinstance(text, str) else ""):
            ports.add(mapping["hostPort"])
    return names, ports


def pick_name(engine: str, requested: str | None, taken: set[str]) -> str:
    if requested:
        if not CONTAINER_NAME.match(requested):
            raise ProvisionError("bad-name", f"'{requested}' is not a valid container name.",
                                 "Use letters, digits, '_', '.' or '-', starting with a letter or digit.")
        if requested in taken:
            raise ProvisionError("name-taken", f"A container named '{requested}' already exists.",
                                 "Pick another name, or use that container.")
        return requested
    base = f"npdev-{engine}"
    candidate, n = base, 1
    while candidate in taken:
        n += 1
        candidate = f"{base}-{n}"
    return candidate


def pick_port(default: int, requested: int | None, claimed: set[int],
              listening: Callable[[str, int, float], bool]) -> int:
    def free(port: int) -> bool:
        return port not in claimed and not listening("127.0.0.1", port, 0.3)

    if requested is not None:
        if not 1 <= requested <= 65535:
            raise ProvisionError("bad-port", f"{requested} is not a valid port.", "Use a port from 1 to 65535.")
        if not free(requested):
            raise ProvisionError("port-in-use", f"Port {requested} is already in use on this machine.",
                                 "Pick another port, or leave it blank and NPDev will pick a free one.")
        return requested
    for port in range(default, min(default + PORT_SEARCH_SPAN, 65536)):
        if free(port):
            return port
    raise ProvisionError("no-free-port", f"No free port found from {default} to {default + PORT_SEARCH_SPAN - 1}.",
                         "Stop something that listens on these ports, or pass --port.")


def _tail(text: str, lines: int = 12) -> str:
    return "\n".join((text or "").strip().splitlines()[-lines:])


def provision(engine_key: str, *, app_name: str = "", port: int | None = None, name: str | None = None,
              docker: DockerRun = _docker,
              listening: Callable[[str, int, float], bool] = npdev_db_discovery._tcp_listening,
              sleep: Callable[[float], None] = time.sleep,
              clock: Callable[[], float] = time.monotonic,
              password: str | None = None) -> dict:
    try:
        engine = npdev_engines.resolve(engine_key)
    except ValueError as exc:
        raise ProvisionError("unknown-engine", str(exc)) from exc
    spec = CONTAINERS.get(engine["key"])
    if spec is None:
        raise ProvisionError(
            "not-containerized",
            f"{engine['externalName']} does not run in a Docker container, so there is nothing to create.",
            "H2 and InMemory need no server; for anything else, point the app at one you run.")

    database = npdev_engines.database_name_for_app(app_name)
    password = password or new_password()
    names, claimed = _existing(docker)
    container = pick_name(engine["key"], name, names)
    host_port = pick_port(spec["containerPort"], port, claimed, listening)

    pulled = False
    code, _, _ = docker(["image", "inspect", spec["image"]], 30)
    if code != 0:
        code, _, err = docker(["pull", spec["image"]], 1800)
        if code != 0:
            raise ProvisionError(
                "image-pull-failed",
                f"Could not download {spec['image']}: {_tail(err, 4) or 'exit ' + str(code)}",
                "Check the internet connection and that Docker Desktop is signed in if it asks, then try again.")
        pulled = True

    run = ["run", "-d", "--name", container, "--restart", "unless-stopped",
           "-p", f"127.0.0.1:{host_port}:{spec['containerPort']}"]
    for key, value in spec["env"].items():
        run += ["-e", f"{key}={_fill(value, password, database)}"]
    run.append(spec["image"])
    run += spec["runArgs"]
    code, _, err = docker(run, 120)
    if code != 0:
        raise ProvisionError(
            "container-start-failed",
            f"Docker could not start the container: {_tail(err, 4) or 'exit ' + str(code)}",
            "Free the port or the name it complains about, then try again.")

    def abandon(why_code: str, message: str, fix: str) -> ProvisionError:
        # A half-built container left behind is the next "port in use" / "name taken" -- and it is
        # ours, created seconds ago, so removing it is not touching anything the user owns.
        docker(["rm", "-f", container], 60)
        return ProvisionError(why_code, message, fix)

    ready = spec["ready"]
    deadline = clock() + ready["timeoutSeconds"]
    while True:
        code, _, _ = docker(_exec_args(container, ready, password, database), 30)
        if code == 0:
            break
        state_code, state, _ = docker(["inspect", "-f", "{{.State.Status}}", container], 15)
        if state_code == 0 and state.strip() in ("exited", "dead"):
            _, logs, logs_err = docker(["logs", "--tail", "15", container], 20)
            raise abandon("not-ready", f"The {engine['externalName']} container stopped while starting:\n"
                          f"{_tail(logs + logs_err)}",
                          "Read the lines above; the most common cause is a refused password or a full disk.")
        if clock() >= deadline:
            _, logs, logs_err = docker(["logs", "--tail", "15", container], 20)
            raise abandon("not-ready", f"{engine['externalName']} did not accept connections within "
                          f"{ready['timeoutSeconds']}s:\n{_tail(logs + logs_err)}",
                          "It may only need longer on a slow machine: try again.")
        sleep(2)

    create = spec["createDatabase"]
    if create is not None:
        code, _, err = docker(_exec_args(container, create, password, database), 60)
        if code != 0:
            raise abandon("database-create-failed",
                          f"The server is up but creating the database '{database}' failed: {_tail(err, 4)}",
                          "Try again.")

    return {
        "engine": engine["key"],
        "name": container,
        "image": spec["image"],
        "host": "127.0.0.1",
        "port": host_port,
        "user": spec["adminUser"],
        "password": password,
        "database": database,
        "restartPolicy": "unless-stopped",
        "imagePulled": pulled,
        "portChanged": host_port != spec["containerPort"],
    }


def render(provisioned: dict) -> str:
    return "\n".join([
        f"created {provisioned['engine']} container '{provisioned['name']}' ({provisioned['image']})",
        f"  host      {provisioned['host']}:{provisioned['port']}",
        f"  user      {provisioned['user']}",
        f"  password  {provisioned['password']}   (shown once -- it is not stored anywhere else)",
        f"  database  {provisioned['database']}",
        "adopt it: npdev init <name> --engine {0} --db-host {1} --db-port {2} --db-user {3} "
        "--db-password <password> --externally-provisioned".format(
            provisioned["engine"], provisioned["host"], provisioned["port"], provisioned["user"]),
    ])
