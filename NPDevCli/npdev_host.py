"""npdev host -- plan model (H3 of NPDEV_HOST_IMPLEMENTATION_PLAN.md).

Pure functions: no argparse, no printing, so every piece of this is testable with no subprocess.
`npdev_cli.py` is the only caller that prints or parses arguments (`host` subcommands, H4+).

The three-file split this module implements (see the plan's section 1 for the full rationale):

  host.definition.json   AUTHORED by the user (`npdev host plan`). Lives beside db.definition.json
                          in the app DEFINITION directory -- NOT inside the generated app, because
                          regeneration deletes and re-emits `_ops/` wholesale. Analogue of
                          db.definition.json.
  _ops/host-plan.json    GENERATED, every run, by HostPlanEmitter (Java, H13). The authored
                          definition merged with resolved facts from the compiled model and
                          `_ops/resolved-db-plan.json`. Never hand-edited. `resolve_plan` below is
                          the Python-side twin of that merge, used for a plan preview before/without
                          a build. Analogue of `_ops/resolved-db-plan.json`.
  data/host-state.json   Live state (tunnel URL/PID) written by `npdev host share`/`down`. `data/`
                          is one of the three directories regeneration spares, which is exactly why
                          this lives there and not in `_ops/`.

Every write goes through `Path.write_bytes`, never the encoding-translating text-write helper on
`pathlib.Path` -- that one emits CRLF on Windows and has silently corrupted generated artifacts in
this repo before (LANDMINES #3).
"""

from __future__ import annotations

import json
import os
import shutil
import socket
from pathlib import Path
from typing import Any

from npdev_jsonschema import describe as _describe_schema_errors
from npdev_jsonschema import validate as _validate_schema
from npdev_static_pages import _detect_definition_dir

HOST_DEFINITION_FILENAME = "host.definition.json"
HOST_PLAN_FILENAME = "host-plan.json"
HOST_STATE_FILENAME = "host-state.json"

# Baked-in-at-generation-time engine -> the Spring profile / npdev.runtime.mode pair it needs
# (LANDMINES #5, #6). No environment variable changes which engine an app was generated with.
_ENGINE_RUNTIME_PROFILES: dict[str, dict[str, str]] = {
    "Postgres": {"profile": "postgres", "mode": "postgres"},
    "MySQL": {"profile": "mysql", "mode": "mysql"},
    "SqlServer": {"profile": "sqlserver", "mode": "sqlserver"},
}


def _repo_root() -> Path:
    """By CONTENTS, never by directory name (REG-144) -- matches
    helpers/check_task.py's own predicate rather than inventing another."""
    for candidate in Path(__file__).resolve().parents:
        if all((candidate / marker).is_dir() for marker in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise RuntimeError("could not identify the NPDev repo root by contents from " + __file__)


def _schema_path(name: str) -> Path:
    return _repo_root() / "schemas" / "ai" / name


def _hosting_targets_path() -> Path:
    return _repo_root() / "scripts" / "policy" / "hosting-targets.json"


def _read_json_bytes(path: Path) -> dict | None:
    if not path.is_file():
        return None
    return json.loads(path.read_bytes().decode("utf-8"))


def _write_json_bytes(path: Path, data: dict) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, sort_keys=True) + "\n"
    path.write_bytes(text.encode("utf-8"))
    return path


def _validate_or_raise(schema_name: str, instance: dict, what: str) -> None:
    schema = _read_json_bytes(_schema_path(schema_name))
    if schema is None:
        raise FileNotFoundError(f"schema not found: {_schema_path(schema_name)}")
    errors = _validate_schema(schema, instance)
    if errors:
        raise ValueError(f"{what} does not satisfy {schema_name}: {_describe_schema_errors(errors)}")


def definition_path(app_dir: Path) -> Path:
    """The app DEFINITION directory -- where host.definition.json lives, beside
    db.definition.json. Reuses the platform's own detection rather than inventing a second
    heuristic (H3 warning)."""
    return _detect_definition_dir(Path(app_dir))


def read_definition(app_dir: Path) -> dict | None:
    return _read_json_bytes(definition_path(app_dir) / HOST_DEFINITION_FILENAME)


def write_definition(app_dir: Path, definition: dict) -> Path:
    _validate_or_raise("host-definition.schema.json", definition, "host.definition.json")
    return _write_json_bytes(definition_path(app_dir) / HOST_DEFINITION_FILENAME, definition)


def read_plan(app_dir: Path) -> dict | None:
    """`_ops/host-plan.json` -- written by HostPlanEmitter (Java, H13) on every generation.
    Never hand-edited; read it, never merge into it."""
    return _read_json_bytes(Path(app_dir) / "_ops" / HOST_PLAN_FILENAME)


def read_state(app_dir: Path) -> dict:
    """`data/host-state.json` -- live tunnel state. `{}` when absent (nothing is up)."""
    return _read_json_bytes(Path(app_dir) / "data" / HOST_STATE_FILENAME) or {}


def write_state(app_dir: Path, state: dict) -> Path:
    return _write_json_bytes(Path(app_dir) / "data" / HOST_STATE_FILENAME, state)


def load_targets() -> dict:
    return _read_json_bytes(_hosting_targets_path()) or {"targets": []}


def _target_by_id(target_id: str | None) -> dict | None:
    if not target_id:
        return None
    for target in load_targets().get("targets", []):
        if target.get("id") == target_id:
            return target
    return None


def resolve_plan(app_dir: Path, definition: dict) -> dict:
    """Merges the authored `host.definition.json` with resolved facts from
    `_ops/resolved-db-plan.json` into the full host plan -- the Python-side twin of what
    HostPlanEmitter (Java, H13) writes to `_ops/host-plan.json` on every generation. Raises
    `FileNotFoundError` when the app has not been generated yet: never guess the engine or port
    (H4 warning).
    """
    app_dir = Path(app_dir)
    db_plan = _read_json_bytes(app_dir / "_ops" / "resolved-db-plan.json")
    if db_plan is None:
        raise FileNotFoundError(
            f"no _ops/resolved-db-plan.json under {app_dir} -- this app has not been generated "
            "yet. Generate it first, then run `npdev host plan` again."
        )

    rung = int(definition.get("rung", 0))
    target_id = definition.get("target")
    target = _target_by_id(target_id)
    engine = db_plan.get("engine")
    physical = bool(db_plan.get("physicalDatabase", False))

    reachable_by = dict(definition.get("reachableBy") or {"kind": "nobody"})
    reachable_kind = reachable_by.get("kind", "nobody")

    data_kind = target.get("databaseKind") if target is not None else "this-machine"
    if data_kind == "ephemeral":
        survives_restart, survives_machine_dying = False, False
    elif data_kind == "managed":
        survives_restart, survives_machine_dying = True, True
    else:
        survives_restart, survives_machine_dying = physical, False

    plan: dict[str, Any] = {
        "schemaVersion": "npdev-host-plan.v1",
        "appId": db_plan.get("appId") or app_dir.name,
        "rung": rung,
        "target": target_id,
        "runsOn": {
            "kind": "container" if (target is not None and target.get("needsBinary") == "docker")
                    else ("platform" if target is not None else "this-machine"),
            "port": int(db_plan.get("serverPort", 8080)),
            "portFromEnv": (target.get("injectsPortVar") if target is not None and target.get("injectsPortVar")
                             else "SERVER_PORT"),
            "memoryMb": target.get("appMemoryMb") if target is not None else None,
        },
        "dataLivesOn": {
            "kind": data_kind,
            "engine": engine,
            "databaseName": db_plan.get("resolvedDatabaseName"),
            "survivesRestart": survives_restart,
            "survivesThisMachineDying": survives_machine_dying,
        },
        "reachableBy": {
            "kind": reachable_kind,
            "provider": reachable_by.get("provider"),
            "through": reachable_by.get("through", "shared-ingress"),
            "domain": reachable_by.get("domain"),
            "terminatesTlsElsewhere": reachable_kind != "nobody",
        },
        "authMode": definition.get("authMode", "apikey"),
        "acknowledged": list(definition.get("acknowledged", [])),
    }

    spring_profiles = db_plan.get("defaultSpringProfiles")
    if spring_profiles:
        plan["springProfiles"] = spring_profiles

    plan["requiredEnv"] = required_env(plan)
    _validate_or_raise("host-plan.schema.json", plan, "resolved host plan")
    return plan


def required_env(plan: dict) -> dict[str, dict]:
    """The complete environment contract for `plan`: which variables this deployment shape needs,
    and for each, whether NPDev already resolved it (`source: npdev`, a concrete `value`) or an
    operator must supply it (`source: you`, `value: null`) -- never a secret's actual value, only
    the fact that one is required and why (H14 depends on this list being exhaustive, not a
    hardcoded per-command copy).
    """
    env: dict[str, dict] = {}
    data_lives_on = plan.get("dataLivesOn") or {}
    reachable_by = plan.get("reachableBy") or {}
    engine = data_lives_on.get("engine")
    data_kind = data_lives_on.get("kind", "this-machine")
    reachable_kind = reachable_by.get("kind", "nobody")
    target = _target_by_id(plan.get("target"))

    if reachable_kind != "nobody":
        env["NPDEV_AUTH_APIKEYS"] = {
            "value": None,
            "source": "you",
            "why": "An app with no key refuses to boot (fail-closed by design) -- anyone with the "
                   "link reaches your data without one. Mint with `npdev host keys --new`.",
        }

    profile = _ENGINE_RUNTIME_PROFILES.get(engine)
    if profile is not None:
        env["SPRING_PROFILES_ACTIVE"] = {
            "value": plan.get("springProfiles") or f"prod,{profile['profile']}",
            "source": "npdev",
            "why": "Selects which properties file loads. Must agree with NPDEV_RUNTIME_MODE or "
                   "StartupValidator refuses to boot, naming the missing side.",
        }
        env["NPDEV_RUNTIME_MODE"] = {
            "value": profile["mode"],
            "source": "npdev",
            "why": "Selects which adapter beans wire. Baked to the engine this app was generated "
                   "with -- no environment variable changes the engine itself.",
        }
        env["NPDEV_STORAGE_MODE"] = {
            "value": "jdbc",
            "source": "npdev",
            "why": "Required alongside NPDEV_RUNTIME_MODE for any real (non-InMemory) engine.",
        }

    if data_kind == "managed":
        for var in ("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"):
            env[var] = {
                "value": None,
                "source": "you",
                "why": "The managed database's own connection details -- this app's local "
                       "resolved-db-plan.json only describes its LOCAL database, not the one "
                       "your hosting target provisions.",
            }

    if target is not None and not target.get("persistentDisk", True):
        env["NPDEV_SUPERUSER_BOOTSTRAPKEYHASH"] = {
            "value": None,
            "source": "you",
            "why": "No persistent disk on this target -- SUPER_USER_KEY.txt does not survive a "
                   "restart. Without this you are locked out of ControlPanel after the first one. "
                   "Mint the hash with `npdev admin hash-key`.",
        }

    return env


# ---------------------------------------------------------------------------------------------
# H5: the 12-check engine (Appendix A). Design rule, not negotiable: no finding without a
# consequence -- every non-`ok` finding's `detail` states what happens to a real user, in plain
# language, BEFORE it names a property.
# ---------------------------------------------------------------------------------------------

Finding = dict  # {"id", "status" (ok|warn|fix), "title", "detail", "fix", "fixable"}

_PROD_PROPERTIES_REL = Path("src", "main", "resources", "application-prod.properties")
_MAIN_PROPERTIES_REL = Path("src", "main", "resources", "application.properties")
_KNOWN_DEFAULT_KEY_FRAGMENTS = ("dev-key",)


def _finding(check_id: str, status: str, title: str, detail: str, *,
             fix: str | None = None, fixable: bool = False) -> Finding:
    return {"id": check_id, "status": status, "title": title, "detail": detail, "fix": fix, "fixable": fixable}


def _final_app_root(app_dir: Path, probe: dict) -> Path:
    final_app_root = probe.get("finalAppRoot")
    return Path(final_app_root) if final_app_root else Path(app_dir)


def _read_text(path: Path) -> str:
    try:
        return path.read_bytes().decode("utf-8", errors="replace")
    except OSError:
        return ""


def _check_app_running(probe: dict) -> Finding:
    if probe.get("health") == "running":
        return _finding("app-running", "ok", "App is running",
                         "The app is up and answering health checks.")
    port = probe.get("port")
    where = f":{port}" if port else "its configured port"
    return _finding(
        "app-running", "fix", "App is running",
        f"Nothing is listening on {where}. Start it with `_ops/Start-App.ps1`.",
    )


def _check_auth_fail_closed(final_app_root: Path) -> Finding:
    text = _read_text(final_app_root / "secrets" / "api-key.env")
    keys: list[str] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        _, _, value = line.partition("=")
        keys.extend(k for k in value.split(";") if k)
    live_keys = [k for k in keys if not any(default in k for default in _KNOWN_DEFAULT_KEY_FRAGMENTS)]
    if live_keys:
        return _finding("auth-fail-closed", "ok", "Auth is fail-closed",
                         f"{len(live_keys)} API key(s) configured, none a known default.")
    return _finding(
        "auth-fail-closed", "fix", "Auth is fail-closed",
        "Anyone with the link reaches your data without a credential.",
        fix="Mint a key with `npdev host keys --new`", fixable=True,
    )


def _check_db_reachable(plan: dict, probe: dict) -> Finding:
    engine = (plan.get("dataLivesOn") or {}).get("engine")
    if not probe.get("physicalDatabase") or engine in (None, "InMemory"):
        return _finding("db-reachable", "ok", "Database reachable",
                         "No physical database is used (InMemory storage).")
    connection = probe.get("connection") or {}
    host, port = connection.get("host"), connection.get("port")
    if not host or not port:
        return _finding("db-reachable", "ok", "Database reachable",
                         "No physical database is used (InMemory storage).")
    try:
        with socket.create_connection((host, int(port)), timeout=1.5):
            pass
        return _finding("db-reachable", "ok", "Database reachable", f"Connected to {host}:{port}.")
    except OSError as exc:
        return _finding(
            "db-reachable", "fix", "Database reachable",
            f"The app will boot and then fail every request. Could not reach {host}:{port} ({exc}).",
        )


def _check_schema_current(probe: dict) -> Finding:
    if not probe.get("physicalDatabase"):
        return _finding("schema-current", "ok", "Schema is current",
                         "No physical schema to drift (InMemory storage).")
    if probe.get("health") == "running":
        return _finding("schema-current", "ok", "Schema is current",
                         "The app booted successfully against this schema, already running its "
                         "configured schema-lifecycle guard.")
    return _finding(
        "schema-current", "warn", "Schema is current",
        "The app has not booted since its last change -- schema drift cannot be confirmed until "
        "it does. Requests could hit columns that do not exist yet.",
    )


def _check_forwarded_headers(final_app_root: Path, plan: dict) -> Finding:
    if (plan.get("reachableBy") or {}).get("kind", "nobody") == "nobody":
        return _finding("forwarded-headers", "ok", "Forwarded headers handled",
                         "Not reachable by anyone else yet -- nothing terminates TLS in front of this app.")
    text = _read_text(final_app_root / _PROD_PROPERTIES_REL)
    if "server.forward-headers-strategy=framework" in text:
        return _finding("forwarded-headers", "ok", "Forwarded headers handled",
                         "server.forward-headers-strategy=framework is set in the prod profile.")
    return _finding(
        "forwarded-headers", "fix", "Forwarded headers handled",
        "Behind a tunnel this app thinks it lives at http://localhost:8080 and will send your "
        "users there. Sign-in redirects break for everyone but you.",
        fix="Add server.forward-headers-strategy=framework to application-prod.properties", fixable=True,
    )


def _check_health_details(final_app_root: Path, plan: dict) -> Finding:
    if (plan.get("reachableBy") or {}).get("kind", "nobody") == "nobody":
        return _finding("health-details", "ok", "Health endpoint is detailed",
                         "Not reachable by anyone else yet -- no one can see the detail.")
    text = _read_text(final_app_root / _PROD_PROPERTIES_REL)
    if "management.endpoint.health.show-details=when-authorized" in text:
        return _finding("health-details", "ok", "Health endpoint is detailed",
                         'show-details=when-authorized -- an anonymous caller sees only {"status":"UP"}.')
    return _finding(
        "health-details", "fix", "Health endpoint is detailed",
        "/actuator/health returns your JDBC URL and disk figures to anyone holding the link, with no key.",
        fix="Set management.endpoint.health.show-details=when-authorized in application-prod.properties",
        fixable=True,
    )


def _check_port_binding(final_app_root: Path) -> Finding:
    text = _read_text(final_app_root / _MAIN_PROPERTIES_REL)
    if "${PORT:" in text:
        return _finding("port-binding", "ok", "Port binding",
                         "server.port honours $PORT, so a PaaS-injected port is routable.")
    return _finding(
        "port-binding", "fix", "Port binding",
        "The host cannot route to you. Users get 502 and there is no log line.",
        fix="Set server.port=${PORT:${SERVER_PORT:8080}} in application.properties", fixable=True,
    )


def _check_memory_posture(final_app_root: Path, plan: dict) -> Finding:
    target = _target_by_id(plan.get("target"))
    memory_mb = target.get("appMemoryMb") if target else None
    if not memory_mb or memory_mb > 512:
        return _finding("memory-posture", "ok", "Memory posture set", "No tight memory ceiling on this target.")
    text = _read_text(final_app_root / "Dockerfile")
    if "JAVA_TOOL_OPTIONS" in text:
        return _finding("memory-posture", "ok", "Memory posture set",
                         "JAVA_TOOL_OPTIONS caps heap for this target's memory ceiling.")
    return _finding(
        "memory-posture", "fix", "Memory posture set",
        "The JVM will be killed under load with no stack trace.",
        fix="Regenerate this app -- the emitted Dockerfile now sets JAVA_TOOL_OPTIONS (P3)", fixable=True,
    )


def _check_data_durability(plan: dict, definition: dict) -> Finding:
    if (plan.get("dataLivesOn") or {}).get("survivesRestart", True):
        return _finding("data-durability", "ok", "Data survives a restart",
                         "This deployment's data survives an app restart.")
    if "data-disappears-on-restart" in set(definition.get("acknowledged") or []):
        return _finding("data-durability", "ok", "Data survives a restart",
                         "Acknowledged: this deployment's data does not survive a restart.")
    return _finding(
        "data-durability", "warn", "Data survives a restart",
        "This app's data disappears when it restarts.",
    )


def _check_key_retrievable(plan: dict) -> Finding:
    target = _target_by_id(plan.get("target"))
    persistent = target.get("persistentDisk", True) if target is not None else True
    if persistent:
        return _finding("key-retrievable", "ok", "Super-user key retrievable",
                         "This target has a persistent disk -- SUPER_USER_KEY.txt survives a restart.")
    if os.environ.get("NPDEV_SUPERUSER_BOOTSTRAPKEYHASH") or os.environ.get("NPDEV_SUPERUSER_BOOTSTRAPKEYRAW"):
        return _finding("key-retrievable", "ok", "Super-user key retrievable",
                         "A bootstrap key property is set -- the super-user key does not depend on a "
                         "file surviving.")
    return _finding(
        "key-retrievable", "fix", "Super-user key retrievable",
        "The credential exists in the database and the file does not. Nobody can sign in, ever.",
        fix="Mint one with `npdev admin hash-key` and set NPDEV_SUPERUSER_BOOTSTRAPKEYHASH", fixable=True,
    )


def _check_engine_matches_target(plan: dict) -> Finding:
    target = _target_by_id(plan.get("target"))
    if target is None:
        return _finding("engine-matches-target", "ok", "Engine matches target", "No external target selected.")
    requires = target.get("requiresEngine")
    engine = (plan.get("dataLivesOn") or {}).get("engine")
    if not requires or requires == engine:
        return _finding("engine-matches-target", "ok", "Engine matches target",
                         f"This app's engine ({engine}) matches {target['id']}'s requirement.")
    return _finding(
        "engine-matches-target", "fix", "Engine matches target",
        "The engine is chosen when the app is generated, not by an environment variable at boot.",
        fix=f"Set database.engine to {requires!r} in db.definition.json and regenerate",
    )


def _check_tunnel_binary(plan: dict) -> Finding:
    reachable_by = plan.get("reachableBy") or {}
    if reachable_by.get("kind") != "tunnel":
        return _finding("tunnel-binary", "ok", "Tunnel provider present", "This plan does not use a tunnel.")
    provider = reachable_by.get("provider") or "cloudflared"
    if shutil.which(provider):
        return _finding("tunnel-binary", "ok", "Tunnel provider present", f"{provider} is on PATH.")
    return _finding(
        "tunnel-binary", "fix", "Tunnel provider present",
        f"Install {provider}, or pass --provider ngrok.",
    )


def run_checks(app_dir: Path, *, plan: dict, remote: str | None = None) -> list[Finding]:
    """The 12-check catalogue (Appendix A), in a stable order. `remote` is accepted for
    forward-compatibility with H15's `npdev host check --target <remote>` -- these local checks
    ignore it; H15 adds the remote-URL variants alongside these, never inside them.

    Reuses `npdev_monitor.probe_app` for health/port/ops-location/db-plan resolution rather than
    writing a second health prober (H5 warning).
    """
    import npdev_monitor

    app_dir = Path(app_dir)
    probe = npdev_monitor.probe_app(app_dir)
    final_app_root = _final_app_root(app_dir, probe)
    definition = read_definition(app_dir) or {}

    return [
        _check_app_running(probe),
        _check_auth_fail_closed(final_app_root),
        _check_db_reachable(plan, probe),
        _check_schema_current(probe),
        _check_forwarded_headers(final_app_root, plan),
        _check_health_details(final_app_root, plan),
        _check_port_binding(final_app_root),
        _check_memory_posture(final_app_root, plan),
        _check_data_durability(plan, definition),
        _check_key_retrievable(plan),
        _check_engine_matches_target(plan),
        _check_tunnel_binary(plan),
    ]


# ---------------------------------------------------------------------------------------------
# H6: --fix. Idempotent and additive -- each fixer only acts on a finding whose status is not
# already `ok`, and every write is read-check-then-write so appending a property that is already
# present is impossible, not just avoided by convention.
# ---------------------------------------------------------------------------------------------


def _upsert_property_line(path: Path, line: str, *, replace_prefix: str | None = None) -> bool:
    """Appends `line` unless it is already present verbatim; when `replace_prefix` names an
    existing line's exact text, that line is replaced in place instead of appending a second,
    conflicting one. Returns whether the file changed."""
    text = _read_text(path)
    lines = text.splitlines()
    if line in lines:
        return False
    if replace_prefix is not None:
        for i, existing in enumerate(lines):
            if existing.strip() == replace_prefix:
                lines[i] = line
                path.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
                return True
    new_text = (text if (not text or text.endswith("\n")) else text + "\n") + line + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(new_text.encode("utf-8"))
    return True


def _fix_forwarded_headers(final_app_root: Path) -> str:
    path = final_app_root / _PROD_PROPERTIES_REL
    _upsert_property_line(path, "server.forward-headers-strategy=framework")
    return f"Set server.forward-headers-strategy=framework in {path}"


def _fix_health_details(final_app_root: Path) -> str:
    path = final_app_root / _PROD_PROPERTIES_REL
    _upsert_property_line(path, "management.endpoint.health.show-details=when-authorized")
    return f"Set management.endpoint.health.show-details=when-authorized in {path}"


def _fix_port_binding(final_app_root: Path) -> str:
    path = final_app_root / _MAIN_PROPERTIES_REL
    _upsert_property_line(path, "server.port=${PORT:${SERVER_PORT:8080}}", replace_prefix="server.port=8080")
    return f"Set server.port=${{PORT:${{SERVER_PORT:8080}}}} in {path}"


def _fix_memory_posture(final_app_root: Path) -> str:
    path = final_app_root / "Dockerfile"
    text = _read_text(path)
    marker = "ENV SERVER_PORT="
    lines = text.splitlines()
    for i, existing in enumerate(lines):
        if existing.strip().startswith(marker):
            lines.insert(i + 1, 'ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 '
                                 '-XX:+ExitOnOutOfMemoryError -Xss256k"')
            path.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
            return f"Set JAVA_TOOL_OPTIONS in {path}"
    return f"Could not find an anchor to insert JAVA_TOOL_OPTIONS in {path} -- regenerate this app instead"


def mint_api_key(final_app_root: Path, *, tenant: str = "dev", actor: str = "developer",
                  role: str = "admin") -> tuple[str, Path]:
    """Mints a fresh API key into `secrets/api-key.env`, in the SAME format
    OperationalRunbookEmitter's `Ensure-NpdevApiKey`/`ensure_npdev_api_key` already use --
    `NPDEV_AUTH_APIKEYS=<key>=<tenant>:<actor>:<role>` (do not invent a second key store, H12
    warning). Returns `(raw_key, path)`. Overwrites any existing content -- the caller decides
    when that is wanted: `npdev host keys --new` is an explicit request; the `auth-fail-closed`
    `--fix` only calls this when no usable key exists yet.
    """
    import secrets as _secrets

    key = _secrets.token_urlsafe(24).replace("-", "").replace("_", "")
    path = Path(final_app_root) / "secrets" / "api-key.env"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(f"NPDEV_AUTH_APIKEYS={key}={tenant}:{actor}:{role}\n".encode("utf-8"))
    return key, path


def list_api_keys(final_app_root: Path) -> list[dict]:
    """Every key currently in `secrets/api-key.env`, MASKED -- never the raw value."""
    text = _read_text(Path(final_app_root) / "secrets" / "api-key.env")
    entries: list[dict] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        _, _, value = line.partition("=")
        for item in value.split(";"):
            if not item or "=" not in item:
                continue
            key, _, rest = item.partition("=")
            parts = rest.split(":")
            masked = (key[:4] + "…" + key[-4:]) if len(key) > 10 else "…"
            entries.append({
                "masked": masked,
                "tenant": parts[0] if len(parts) > 0 else None,
                "actor": parts[1] if len(parts) > 1 else None,
                "role": parts[2] if len(parts) > 2 else None,
            })
    return entries


def _fix_auth_fail_closed(final_app_root: Path) -> str:
    key, path = mint_api_key(final_app_root)
    return f"Minted a new API key into {path} (X-Api-Key: {key})"


def _fix_key_retrievable(app_dir: Path) -> str:
    import hashlib
    import secrets as _secrets

    raw = _secrets.token_urlsafe(32)
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    path = Path(app_dir) / "secrets" / "superuser-bootstrap.env"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(f"NPDEV_SUPERUSER_BOOTSTRAPKEYHASH={digest}\n".encode("utf-8"))
    return (f"Minted a super-user bootstrap key, saved its hash to {path}. "
            f"THE RAW KEY IS SHOWN ONCE, SAVE IT NOW: {raw}")


_FIXERS = {
    "auth-fail-closed": lambda app_dir, final_app_root: _fix_auth_fail_closed(final_app_root),
    "forwarded-headers": lambda app_dir, final_app_root: _fix_forwarded_headers(final_app_root),
    "health-details": lambda app_dir, final_app_root: _fix_health_details(final_app_root),
    "port-binding": lambda app_dir, final_app_root: _fix_port_binding(final_app_root),
    "memory-posture": lambda app_dir, final_app_root: _fix_memory_posture(final_app_root),
    "key-retrievable": lambda app_dir, final_app_root: _fix_key_retrievable(app_dir),
}


def apply_fixes(app_dir: Path, *, plan: dict) -> tuple[list[str], list[Finding]]:
    """Applies every currently-fixable finding for `app_dir`, then re-runs the checks so the
    caller sees the NEW state, never the old one (H6 rule). Idempotent by construction: a finding
    already `ok` is skipped, so running this twice in a row changes nothing the second time.
    """
    import npdev_monitor

    app_dir = Path(app_dir)
    probe = npdev_monitor.probe_app(app_dir)
    final_app_root = _final_app_root(app_dir, probe)

    changes: list[str] = []
    for finding in run_checks(app_dir, plan=plan):
        if finding["status"] == "ok" or not finding["fixable"]:
            continue
        fixer = _FIXERS.get(finding["id"])
        if fixer is None:
            continue
        changes.append(fixer(app_dir, final_app_root))

    return changes, run_checks(app_dir, plan=plan)


# ---------------------------------------------------------------------------------------------
# H10: the publish gate. `share`/`deploy` call this before doing anything irreversible.
# ---------------------------------------------------------------------------------------------


def preflight(app_dir: Path, plan: dict) -> list[Finding]:
    """Findings that BLOCK a publish action (`share`/`deploy`) -- every `fix`-status finding, plus
    `data-durability` specifically when its status is `warn` (ephemeral data, not yet acknowledged
    in `host.definition.json`'s `acknowledged` list). That one escalation is deliberate: run_checks
    reports an unacknowledged ephemeral-data condition as a `warn` because nothing can fix it
    automatically, but publishing before the user has acknowledged it in words is exactly the
    mistake this gate exists to catch.

    An empty return means clear to proceed. The caller must print each blocking finding's own
    `title` + `detail` + `fix` -- "preflight failed" with no more detail is not an acceptable
    message (H10 warning).
    """
    blocking: list[Finding] = []
    for finding in run_checks(app_dir, plan=plan):
        if finding["status"] == "fix":
            blocking.append(finding)
        elif finding["id"] == "data-durability" and finding["status"] == "warn":
            blocking.append(finding)
    return blocking
