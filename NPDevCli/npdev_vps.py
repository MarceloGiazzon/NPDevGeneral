"""OPS-9: `npdev host deploy --ssh user@host` for the `vps` target.

Ships ONLY what the emitted Dockerfile needs (it COPYs build/libs/<jar> and npdev-generated/, see
DockerDeploymentEmitter.dockerfile) plus the compose/proxy files, then runs `docker compose up -d
--build` on the server over plain OpenSSH. No registry, no agent on the server: Docker + Compose v2.

The server's .env is created once (random API key and database password) and never overwritten:
it holds the real database password the database volume was initialised with.
"""
from __future__ import annotations

import io
import re
import secrets
import shlex
import subprocess
import tarfile
from pathlib import Path

# Relative paths shipped to the server. Directories are added recursively.
SHIPPED = ("Dockerfile", "docker-compose.yml", ".dockerignore", ".env.example", "deploy", "npdev-generated")
REMOTE_BASE = "npdev-apps"  # under the ssh user's home
SERVER_ENGINES_WITH_BACKUP = ("Postgres", "MySQL")

_DOCKERFILE_JAR = re.compile(r"^\s*COPY\s+build/libs/(\S+\.jar)\s", re.MULTILINE)
_APP_ID = re.compile(r"[^A-Za-z0-9._-]")


def find_jar(app_dir: Path) -> Path | None:
    """The jar the Dockerfile COPYs (by name) if built, else the newest non-plain FinalExec jar."""
    libs = app_dir / "build" / "libs"
    if not libs.is_dir():
        return None
    dockerfile = app_dir / "Dockerfile"
    if dockerfile.is_file():
        named = _DOCKERFILE_JAR.search(dockerfile.read_text(encoding="utf-8", errors="replace"))
        if named and (libs / named.group(1)).is_file():
            return libs / named.group(1)
    jars = [p for p in libs.glob("FinalExec-*.jar") if not p.name.endswith("-plain.jar")]
    return max(jars, key=lambda p: p.stat().st_mtime) if jars else None


def safe_app_id(raw: str) -> str:
    cleaned = _APP_ID.sub("-", raw or "").strip("-.")
    return cleaned or "app"


def bundle_names(app_dir: Path) -> list[str]:
    """The relative paths build_bundle would ship (files only), for --dry-run and tests."""
    jar = find_jar(app_dir)
    names: list[str] = []
    for rel in SHIPPED:
        path = app_dir / rel
        if path.is_file():
            names.append(rel)
        elif path.is_dir():
            names.extend(sorted(p.relative_to(app_dir).as_posix() for p in path.rglob("*") if p.is_file()))
    if jar is not None:
        names.append(f"build/libs/{jar.name}")
    return names


def build_bundle(app_dir: Path, env_text: str | None) -> bytes:
    """tar.gz in memory: SHIPPED + build/libs/<jar> (+ .env when the caller decided to send one)."""
    jar = find_jar(app_dir)
    if jar is None:
        raise ValueError(f"{app_dir} has no build/libs/FinalExec-*.jar -- run _ops/Build-FinalApp.ps1 first")
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for rel in SHIPPED:
            path = app_dir / rel
            if path.exists():
                tar.add(path, arcname=rel)
        tar.add(jar, arcname=f"build/libs/{jar.name}")
        if env_text is not None:
            data = env_text.encode("utf-8")
            info = tarfile.TarInfo(".env")
            info.size = len(data)
            info.mode = 0o600
            tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def _random_password() -> str:
    # URL-safe alphabet (safe in .env and JDBC URLs); suffix satisfies engines that demand
    # upper + lower + digit (SQL Server) without weakening the 192 random bits in front.
    return secrets.token_urlsafe(24) + "Aa1"


def first_env(app_dir: Path, *, public_host: str | None, acme_email: str | None) -> str:
    """A filled .env from .env.example: NPDEV_AUTH_APIKEYS gets a random 64-hex key (the
    tenant:actor:ROLE part of the example is kept), every *_PASSWORD gets a random value, and the two
    OPS-8 proxy variables are set when given. Only ever sent when the server has no .env yet -- the
    server's .env is never overwritten."""
    lines = []
    for raw in (app_dir / ".env.example").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        commented = line.startswith("#")
        key, _, value = line.lstrip("# ").partition("=") if "=" in line else ("", "", "")
        if key == "NPDEV_AUTH_APIKEYS" and not commented:
            # Example value: <key>=<tenant>:<actor>:<ROLES>; keep what follows the first '='.
            _, _, mapping = value.partition("=")
            lines.append(f"NPDEV_AUTH_APIKEYS={secrets.token_hex(32)}={mapping or 'prod:deploy:ADMIN'}")
        elif key.endswith("_PASSWORD") and not commented:
            lines.append(f"{key}={_random_password()}")
        elif key == "NPDEV_PUBLIC_HOST" and commented and public_host:
            lines.append(f"NPDEV_PUBLIC_HOST={public_host}")
        elif key == "NPDEV_ACME_EMAIL" and commented and acme_email:
            lines.append(f"NPDEV_ACME_EMAIL={acme_email}")
        else:
            lines.append(raw)
    return "\n".join(lines) + "\n"


def remote_script(app_id: str, profiles: list[str], backup_service: bool = False) -> str:
    """Runs on the server: unpack the bundle from stdin, build and start. `backup_service` starts the
    nightly sidecar by name, so the one-shot `backup-verify` service of the same profile is NOT run."""
    target = f"$HOME/{REMOTE_BASE}/{shlex.quote(safe_app_id(app_id))}"
    profile_args = " ".join(f"--profile {shlex.quote(p)}" for p in profiles)
    script = (
        "set -e; "
        f"mkdir -p {target}; cd {target}; "
        "tar -xzf -; "
        f"docker compose {profile_args} up -d --build; "
    )
    if backup_service:
        script += "docker compose --profile backup up -d backup; "
    return script + "docker compose ps"


def ssh(host: str, script: str, *, stdin: bytes | None = None, dry_run: bool = False):
    command = ["ssh", "-o", "BatchMode=yes", host, script]
    if dry_run:
        return command
    return subprocess.run(command, input=stdin, capture_output=True, timeout=1800)


def remote_has_env(host: str, app_id: str) -> bool:
    probe = ssh(host, f"test -f $HOME/{REMOTE_BASE}/{shlex.quote(safe_app_id(app_id))}/.env && echo yes || echo no")
    if probe.returncode != 0:
        tail = (probe.stderr or b"").decode("utf-8", "replace").strip()[-400:]
        raise RuntimeError(f"ssh to {host} failed: {tail or 'no output'}")
    return b"yes" in probe.stdout
