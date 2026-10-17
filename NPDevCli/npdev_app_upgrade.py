"""OPS-12: `npdev upgrade --app <dir>` -- a guarded in-place upgrade of a generated app.

    PREFLIGHT  app is a generated app; model/config inferred like `npdev run app`; app stopped
               (and not supervised by an installed service that would restart the old JVM)
    VALIDATE   structural + semantic model validation -- refuse on errors (nothing is copied or
               touched yet), suggest `npdev migrate dsl-2`
    BACKUP     code (jar + npdev-generated + _ops) and data (H2: a copy of data/ while the app is
               stopped; Postgres/MySQL: deploy/backup.sh) into <app>/data/backups/pre-upgrade-<UTC>/
    BUILD      cli.run_app's GENERATE + BUILD phases (same code path, never a second copy)
    IMPACT     java -Dnpdev.schema.lifecycle.mode=REPORT_ONLY -jar <new jar>: 0 safe, 2 attention,
               3 destructive (refuse unless --allow-destructive); writes nothing
    BOOT/READY cli.run_app's BOOT/READY (applies the schema migration for real)
    ROLLBACK   on any failure after BACKUP: restore jar + generated code (+ embedded data), boot the
               old jar, health-check it -> ROLLED_BACK, or ROLLBACK_FAILED with the backup path

`cli` is the npdev_cli module, injected exactly the way dev_loop.py receives it (no circular import).
Server-engine (Postgres/MySQL) DATA is never auto-restored: the migration may be fine and the failure
elsewhere, so the exact restore command is printed instead.

Never put an API key or a DB password in the result: the diagnostics name files, not secrets.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

PHASES = ("PREFLIGHT", "BACKUP", "VALIDATE", "BUILD", "IMPACT", "BOOT", "READY", "ROLLBACK")

UPGRADED = "UPGRADED"
REFUSED = "REFUSED"
ROLLED_BACK = "ROLLED_BACK"
ROLLBACK_FAILED = "ROLLBACK_FAILED"

_CODE_ITEMS = ("npdev-generated", "_ops")


# --------------------------------------------------------------------------------------------
# Backup / restore primitives
# --------------------------------------------------------------------------------------------
def backup_dir_for(app_root: Path) -> Path:
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    return app_root / "data" / "backups" / f"pre-upgrade-{stamp}"


def backup_code(app_root: Path, dest: Path, find_jar) -> Path | None:
    jar = find_jar(app_root)
    (dest / "code").mkdir(parents=True, exist_ok=True)
    if jar is not None:
        shutil.copy2(jar, dest / "code" / jar.name)
    for name in _CODE_ITEMS:
        source = app_root / name
        if source.is_dir():
            shutil.copytree(source, dest / "code" / name)
    return jar


def backup_embedded_data(data_root: Path, dest: Path) -> None:
    """App is STOPPED here, so a file copy of data/ is consistent. backups/ itself is skipped."""
    if data_root.is_dir():
        shutil.copytree(data_root, dest / "data", ignore=shutil.ignore_patterns("backups"))
    else:
        (dest / "data").mkdir(parents=True, exist_ok=True)


def backup_server_data(app_root: Path) -> subprocess.CompletedProcess | None:
    script = app_root / "deploy" / "backup.sh"
    if not script.is_file() or shutil.which("bash") is None:
        return None
    return subprocess.run(["bash", str(script)], cwd=str(app_root), capture_output=True, text=True,
                          timeout=1800)


def _copy_server_dump(app_root: Path, dest: Path, completed: subprocess.CompletedProcess) -> Path | None:
    """backup.sh writes <app>/backups/<db>-<ts>.sql, and a regeneration wipes everything outside
    data/logs/secrets -- so the dump is copied INTO the backup dir before the build can delete it."""
    match = re.findall(r"Done:\s*(\S+)", completed.stdout or "")
    if not match:
        return None
    dump = Path(match[-1])
    if not dump.is_absolute():
        dump = app_root / dump
    if not dump.is_file():
        return None
    target = dest / "db-dump"
    target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(dump, target / dump.name)
    return target / dump.name


def restore(app_root: Path, dest: Path, embedded: bool, data_root: Path | None = None) -> None:
    data_root = data_root or (app_root / "data")
    if embedded and (dest / "data").is_dir():
        if data_root.is_dir():
            for child in data_root.iterdir():
                if child.name != "backups":
                    shutil.rmtree(child) if child.is_dir() else child.unlink()
        shutil.copytree(dest / "data", data_root, dirs_exist_ok=True)
    libs = app_root / "build" / "libs"
    if libs.is_dir():
        for stale in libs.glob("FinalExec-*.jar"):
            stale.unlink()
    for jar in (dest / "code").glob("FinalExec-*.jar"):
        libs.mkdir(parents=True, exist_ok=True)
        shutil.copy2(jar, libs / jar.name)
    for name in _CODE_ITEMS:
        if (dest / "code" / name).is_dir():
            shutil.rmtree(app_root / name, ignore_errors=True)
            shutil.copytree(dest / "code" / name, app_root / name)


def impact_exit_code(java: str, jar: Path, app_root: Path, profile: str, timeout: int = 600) -> int:
    completed = subprocess.run(
        [java, "-Dnpdev.schema.lifecycle.mode=REPORT_ONLY", "-jar", str(jar),
         f"--spring.profiles.active={profile}"],
        cwd=str(app_root), capture_output=True, text=True, timeout=timeout)
    return completed.returncode


# --------------------------------------------------------------------------------------------
# Preflight helpers
# --------------------------------------------------------------------------------------------
def _service_slug(app_id: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9_.-]", "-", app_id or "")
    return slug or "app"


def service_installed(app_id: str, is_windows: bool) -> bool:
    """Best effort: the supervisor `Install-Service.ps1` / `install-service.sh` registers is named
    `npdev-<slug(appId)>` (a Scheduled Task, or a systemd unit). If it is registered, it would restart
    the OLD jar in the middle of the upgrade. A probe that cannot run says 'not installed'."""
    name = f"npdev-{_service_slug(app_id)}"
    try:
        if is_windows:
            probe = ["schtasks", "/Query", "/TN", name]
        else:
            probe = ["systemctl", "is-enabled", f"{name}.service"]
        return subprocess.run(probe, capture_output=True, text=True, timeout=20).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _stop_app(cli, app_root: Path, record: dict, wait_seconds: int = 60) -> bool:
    """Run the app's own _ops stop script, then wait until nothing listens on its port."""
    ops = app_root / "_ops"
    if cli._is_windows_platform():
        script = ops / "Stop-App.ps1"
        shell = cli._find_powershell()
        command = [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)] if shell else None
    else:
        script = ops / "stop-app.sh"
        command = ["sh", str(script)]
    if command is None or not script.is_file():
        return False
    try:
        subprocess.run(command, cwd=str(app_root), capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError):
        return False
    port = record.get("port")
    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        if not port or not cli._is_port_in_use(int(port)):
            return True
        time.sleep(1)
    return False


# --------------------------------------------------------------------------------------------
# The orchestrator
# --------------------------------------------------------------------------------------------
def _run_app_args(args: argparse.Namespace, app_root: Path, port: int, remaining: int, **overrides):
    values = dict(vars(args))
    values.update(
        output=str(app_root), port=port, timeout=max(int(remaining), 30), baseline_model=None,
        keep_running=False, clean=False, require_db_definition=False,
        stop_after_build=False, skip_generate_and_build=False)
    values.update(overrides)
    return argparse.Namespace(**values)


def _first_errors(report: dict, limit: int = 5) -> list[dict]:
    errors = [d for d in report.get("diagnostics", [])
              if isinstance(d, dict) and str(d.get("severity", "error")).lower() == "error"]
    return errors[:limit]


def upgrade_app(args: argparse.Namespace, cli) -> dict:
    started = time.monotonic()
    timeout = int(getattr(args, "timeout", None) or 900)

    def remaining() -> int:
        return max(int(timeout - (time.monotonic() - started)), 30)

    result: dict = {"phase": "PREFLIGHT", "ok": False, "outcome": REFUSED, "backupDir": None,
                    "diagnostics": [], "baseUrl": None}

    def refuse(code: str, message: str, fix: str | None = None, phase: str | None = None) -> dict:
        result["phase"] = phase or result["phase"]
        result["diagnostics"].append(cli._diag(result["phase"], code, message, suggested_fix=fix))
        return result

    # ---- PREFLIGHT ---------------------------------------------------------------------------
    app_input = Path(args.app).expanduser().resolve()
    record = cli.npdev_monitor.probe_app(app_input, include_info=False)
    if not record.get("isAppRoot"):
        return refuse("NOT_AN_APP", record.get("detail") or f"not a generated NPDev app: {app_input}",
                      "Point --app at the generated app directory (the one holding _ops/).")
    app_root = Path(record.get("finalAppRoot") or app_input)
    port = getattr(args, "port", None) or record.get("port") or 8080
    engine = str(record.get("engine") or "")
    is_embedded = engine.upper().startswith("H2")
    is_memory = engine.lower() == "inmemory"
    data_root = Path(record["dataRoot"]) if (is_embedded and record.get("dataRoot")) else app_root / "data"

    listening = bool(record.get("listening")) or cli._is_port_in_use(int(port))
    if listening:
        if not getattr(args, "stop", False):
            return refuse("APP_RUNNING",
                          f"the app is running (something is listening on port {port}); a file copy of "
                          "its data is only consistent while it is stopped.",
                          "Stop it first (_ops/Stop-App.ps1), or pass --stop to let upgrade do it.")
        if not _stop_app(cli, app_root, record):
            return refuse("STOP_FAILED", f"could not stop the app on port {port} with its _ops stop script.",
                          "Stop it manually, then re-run.")
    if service_installed(str(record.get("name") or ""), cli._is_windows_platform()):
        return refuse("SERVICE_INSTALLED",
                      "an installed service/scheduled task supervises this app and would restart the old "
                      "jar in the middle of the upgrade.",
                      "Stop the service first (_ops/Uninstall-Service.ps1 or uninstall-service.sh), upgrade, "
                      "then install it again.")

    infer_args = _run_app_args(args, app_root, int(port), remaining())
    failure = cli._infer_run_app_paths(infer_args)
    if failure is not None:
        fallback = record.get("modelPath")
        if not getattr(args, "model", None) and fallback and Path(fallback).is_file() \
                and (Path(fallback).parent / "config.json").is_file():
            infer_args.model = fallback
            infer_args.config = getattr(args, "config", None) or str(Path(fallback).parent / "config.json")
            infer_args.output = str(app_root)
        else:
            result["diagnostics"].append(failure)
            return result
    infer_args.output = str(app_root)
    model_path = Path(infer_args.model).expanduser().resolve()

    # ---- VALIDATE ----------------------------------------------------------------------------
    result["phase"] = "VALIDATE"
    try:
        with tempfile.TemporaryDirectory(prefix="npdev-upgrade-validate-") as tmp:
            report_path = Path(tmp) / "report.json"
            cli.run_validate_semantic(model_path, report_path, quiet=True)
            report = json.loads(report_path.read_text(encoding="utf-8")) if report_path.is_file() else {}
    except Exception as exc:  # CliError or an unreadable report -- nothing was changed yet
        return refuse("VALIDATION_UNAVAILABLE", f"could not validate the model: {exc}", None)
    if report.get("status") == "failed":
        errors = _first_errors(report)
        retired = any(re.search(r"retired|alias|deprecated|legacy", f"{e.get('code', '')} {e.get('message', '')}", re.I)
                      for e in errors)
        for e in errors:
            result["diagnostics"].append(cli._diag(
                "VALIDATE", str(e.get("code") or "VALIDATION_ERROR"), str(e.get("message") or ""),
                suggested_fix=e.get("suggestedFix")))
        if retired:
            result["diagnostics"].append(cli._diag(
                "VALIDATE", "RETIRED_SYNTAX", "the model uses retired DSL syntax.",
                suggested_fix=f"npdev migrate dsl-2 --input {model_path} --write"))
        return result  # nothing was changed yet, so there is nothing to roll back

    # ---- BACKUP ------------------------------------------------------------------------------
    result["phase"] = "BACKUP"
    java = cli.java_launcher()
    if java is None:
        return refuse("JAVA_NOT_FOUND", "No Java runtime was found (JAVA_HOME / PATH).",
                      "Install a JDK 17+, or set JAVA_HOME. `npdev doctor` shows what is found.")
    if not is_embedded and not is_memory and not getattr(args, "skip_db_backup", False):
        # Fail BEFORE any build: a server database cannot be rolled back by this command.
        script = app_root / "deploy" / "backup.sh"
        if not script.is_file() or shutil.which("bash") is None:
            return refuse("NO_DB_BACKUP",
                          f"engine {engine or 'unknown'} is a server database and there is no deploy/backup.sh "
                          "(or no bash) to dump it with.",
                          "back up the database yourself, then pass --skip-db-backup")
    dest = backup_dir_for(app_root)
    dest.mkdir(parents=True, exist_ok=True)
    result["backupDir"] = str(dest)
    old_jar = backup_code(app_root, dest, cli._find_jar)
    server_dump: Path | None = None
    if is_embedded:
        backup_embedded_data(data_root, dest)
    elif not is_memory and not getattr(args, "skip_db_backup", False):
        completed = backup_server_data(app_root)
        if completed is None or completed.returncode != 0:
            return refuse("DB_BACKUP_FAILED",
                          "deploy/backup.sh failed: " + ((completed.stderr or "")[-300:] if completed else "not runnable"),
                          "Fix the backup (is the compose database up?), or pass --skip-db-backup after "
                          "backing the database up yourself.")
        server_dump = _copy_server_dump(app_root, dest, completed)
    if old_jar is None:
        result["diagnostics"].append(cli._diag(
            "BACKUP", "NO_PREVIOUS_JAR",
            "no previous build was found under build/libs, so there is nothing to roll back to.",
            suggested_fix="A failed upgrade will leave the app stopped (ROLLBACK_FAILED)."))

    # ---- helpers that need the backup ----------------------------------------------------------
    def rollback(reason_phase: str) -> dict:
        result["phase"] = "ROLLBACK"
        result["ok"] = False
        try:
            restore(app_root, dest, embedded=is_embedded, data_root=data_root)
        except OSError as exc:
            result["outcome"] = ROLLBACK_FAILED
            result["diagnostics"].append(cli._diag(
                "ROLLBACK", "RESTORE_FAILED", f"restoring the backup failed: {exc}. Backup kept at {dest}.",
                suggested_fix=f"Restore manually from {dest}."))
            return result
        if server_dump is not None:
            result["diagnostics"].append(cli._diag(
                "ROLLBACK", "DB_RESTORE_MANUAL",
                f"the database was NOT restored automatically (the migration may be fine and the failure "
                f"elsewhere). If you need the old data back: bash deploy/restore.sh \"{server_dump}\" --yes",
                suggested_fix=None))
        if old_jar is None:
            result["outcome"] = ROLLBACK_FAILED
            result["diagnostics"].append(cli._diag(
                "ROLLBACK", "NO_PREVIOUS_JAR", f"no previous jar to boot. Backup kept at {dest}."))
            return result
        old = cli.run_app(_run_app_args(args, app_root, int(port), 240, model=str(model_path),
                                        config=infer_args.config, skip_generate_and_build=True))
        if old.get("ok"):
            result["outcome"] = ROLLED_BACK
            result["baseUrl"] = old.get("baseUrl")
        else:
            result["outcome"] = ROLLBACK_FAILED
            result["diagnostics"].append(cli._diag(
                "ROLLBACK", "OLD_VERSION_DID_NOT_BOOT",
                f"the restored previous version did not become healthy. Backup kept at {dest}.",
                suggested_fix=f"See {app_root / 'npdev-run-app-boot.log'} and restore manually from {dest}."))
            if old.get("logExcerpt"):
                result["logExcerpt"] = old["logExcerpt"]
        return result

    def fail_and_rollback(code: str, message: str, fix: str | None, phase: str, extra: dict | None = None) -> dict:
        result["diagnostics"].append(cli._diag(phase, code, message, suggested_fix=fix))
        if extra and extra.get("logExcerpt"):
            result["logExcerpt"] = extra["logExcerpt"]
        return rollback(phase)

    # ---- BUILD (regenerate + build) -----------------------------------------------------------
    result["phase"] = "BUILD"
    built = cli.run_app(_run_app_args(args, app_root, int(port), remaining(),
                                      model=str(model_path), config=infer_args.config, stop_after_build=True))
    if not built.get("ok"):
        for d in built.get("diagnostics") or []:
            result["diagnostics"].append(d)
        return fail_and_rollback("UPGRADE_BUILD_FAILED", "regenerate/build failed; rolling back.",
                                 None, "BUILD", built)
    new_jar = Path(built["jarPath"]) if built.get("jarPath") else cli._find_jar(app_root)

    # ---- IMPACT --------------------------------------------------------------------------------
    result["phase"] = "IMPACT"
    cli.ensure_api_key(app_root)  # StartupValidator needs a key even in REPORT_ONLY
    try:
        code = impact_exit_code(java, new_jar, app_root, getattr(args, "profile", "dev") or "dev",
                                timeout=remaining())
    except (OSError, subprocess.SubprocessError) as exc:
        return fail_and_rollback("IMPACT_CHECK_FAILED", f"the schema-impact check could not run: {exc}",
                                 None, "IMPACT")
    if code == 2:
        result["diagnostics"].append(cli._diag(
            "IMPACT", "NEEDS_ATTENTION",
            "the schema impact check reports changes that need attention (not destructive).",
            suggested_fix="Review the migration before relying on it."))
    elif code == 3:
        if not getattr(args, "allow_destructive", False):
            result["diagnostics"].append(cli._diag(
                "IMPACT", "DESTRUCTIVE_SCHEMA_CHANGE",
                "the schema impact check reports a DESTRUCTIVE change (data would be dropped).",
                suggested_fix="Re-run with --allow-destructive if that is intended."))
            rolled = rollback("IMPACT")
            # Refused, not failed: the old version is back and nothing was applied.
            rolled["outcome"] = REFUSED if rolled["outcome"] == ROLLED_BACK else rolled["outcome"]
            return rolled
        result["diagnostics"].append(cli._diag(
            "IMPACT", "DESTRUCTIVE_ALLOWED", "destructive schema change allowed by --allow-destructive."))
    elif code != 0:
        return fail_and_rollback("IMPACT_CHECK_FAILED",
                                 f"the schema-impact check exited with {code}; rolling back.",
                                 None, "IMPACT")

    # ---- BOOT + READY --------------------------------------------------------------------------
    result["phase"] = "BOOT"
    booted = cli.run_app(_run_app_args(args, app_root, int(port), remaining(), model=str(model_path),
                                       config=infer_args.config, skip_generate_and_build=True))
    if not booted.get("ok"):
        for d in booted.get("diagnostics") or []:
            result["diagnostics"].append(d)
        return fail_and_rollback("UPGRADE_BOOT_FAILED", "the new version did not become healthy; rolling back.",
                                 None, "BOOT", booted)
    result.update(phase="READY", ok=True, outcome=UPGRADED, baseUrl=booted.get("baseUrl"))
    for key in ("openUrl", "apiKeyFile"):
        if booted.get(key):
            result[key] = booted[key]
    return result
