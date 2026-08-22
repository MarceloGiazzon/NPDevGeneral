#!/usr/bin/env python3
"""Generate, build, boot and EXERCISE a real app on a real engine -- gap A, exit criteria E3/E4/E5.

WHAT THIS PROVES THAT NOTHING ELSE DID
--------------------------------------
Tier B proved 13 conformance vectors over raw JDBC. It never generated an app, never realized a
schema through NPDev's own engine, never booted Spring, never served a request. Those are the paths
a user actually uses, and until this script ran, **no generated application had ever booted on MySQL
or SQL Server.** A third person picking MySQL would have been the first.

Five assertions, in the order they can fail:

  1. **It boots.** Schema realization ran against the real engine. That alone is new information.
  2. **Non-BMP unicode survives a round trip.** POST a record whose label is `café ☕ 🚀`, GET it
     back, compare exactly. This is conformance J2 promoted to APPLICATION level -- the exact thing
     SQL Server failed at Tier B, because the vector hand-wrote `VARCHAR(4000)` instead of asking the
     dialect. SqlServerDialect.portableColumnType already returned NVARCHAR and was never asked. If
     the EMITTER makes the same mistake, this is where it shows, and nowhere earlier.
  2b. **SQL-reserved identifiers round-trip** (STOR-6). The probe carries a column named `order` and
     one named `value`, and a concept whose table is `rows`. Booting proves the GENERATOR quoted them
     -- an unquoted `order` is a syntax error in CREATE TABLE on every engine. Reading the row back
     proves the RUNTIME quoted them too, and that is the half that fails silently: the app builds,
     boots, and then cannot find its own table. Both seams are a registered twin pair.
  3. **The query path returns the right rows.** Filtered and ordered, so pagination goes through the
     dialect -- 23 of the original 41 sites, and the one where SQL Server binds (offset, limit) in
     the REVERSED order. A wrong page here is silent on three engines and wrong on the fourth.
  4. **A restart preserves rows.** Stop the app, start it again, read. This is E5, and it is the one
     whose result was genuinely unknown before it ran: boot-time schema realization deciding to
     "converge" a schema it already created is a different code path on every engine.

WHY IT IS PYTHON AND NOT THE EXISTING HARNESS
---------------------------------------------
`invoke-ai-beta-app-smoke.ps1` uses `Get-CimInstance Win32_Process` for teardown, which is
Windows-only; this has to run on ubuntu-latest, where the engine containers are. Rewriting that
harness to be portable would be a much larger change than the proof warrants, and it is load-bearing
for the fast gate.

USAGE
    python scripts/quality/run-engine-app-proof.py --engine mysql --port 18310 \
        --db-host 127.0.0.1 --db-port 3306 --db-user npdev --db-password npdev \
        --report Build/reports/engine-app-proof-mysql.json

Exit 0 = every assertion passed. Exit 1 = at least one failed. Exit 2 = usage/setup problem.
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# The label that decides whether this engine can hold what a user will actually type. Deliberately
# spans three widths: plain ASCII, a BMP accented character, and TWO non-BMP astral characters --
# MySQL's legacy three-byte `utf8` accepts the first two and silently mangles the third, and SQL
# Server's VARCHAR (as opposed to NVARCHAR) loses all of them.
UNICODE_LABEL = "café ☕ 🚀"


def _repo_root() -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144)."""
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if all((candidate / m).is_dir()
               for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit("could not identify the repo root by contents")


# A unicode proof that cannot PRINT unicode is not a proof. Windows consoles default to cp1252, and
# the first local rehearsal of this script died with UnicodeEncodeError on the coffee cup -- after
# the assertion had been evaluated but before its verdict was recorded, so the run reported nothing
# at all. backslashreplace rather than a plain replace: an escaped `☕` in a log still says which
# character it was, where a `?` is indistinguishable from the data loss being tested for.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    except (AttributeError, OSError):  # pragma: no cover - a redirected stream may not support it
        pass


def log(message: str) -> None:
    print(f"[engine-proof] {message}", flush=True)


# T1/C2 (application-dev.yml): `dev` no longer seeds a known key -- StartupValidator refuses to boot
# without one supplied externally. This used to be the `dev` profile's old built-in convenience
# credential; `App.start()` now overwrites it via `ensure_api_key()` before every boot, the same
# contract every other launcher on this platform follows (OperationalRunbookEmitter's
# Ensure-NpdevApiKey / ensure_npdev_api_key). The placeholder below is never actually sent -- it
# exists so a caller that reads API_KEY before any app has booted gets an obviously-wrong value
# rather than None.
API_KEY = "unset-call-ensure_api_key-first"


def ensure_api_key(app_root: Path) -> str:
    """Provision (or reuse) `<app_root>/secrets/api-key.env` and export it into this process's
    environment, exactly like every other launcher on this platform (OperationalRunbookEmitter's
    Ensure-NpdevApiKey / ensure_npdev_api_key -- same file, same `NPDEV_AUTH_API_KEYS=<key>=
    dev:developer:admin` line, same "present but unusable is treated as absent" rule from REG-157).

    `App.start()` below spawns `java -jar` with no explicit `env=`, so it inherits whatever this
    process's environment already has -- mutating `os.environ` here, before that Popen call, is
    what makes the boot see the key with no other plumbing. Returns the bare credential (the part
    before the mapping's own `=`) for the `X-Api-Key` header `http()` sends.
    """
    secrets_dir = app_root / "secrets"
    key_file = secrets_dir / "api-key.env"
    needs_generation = True
    if key_file.exists():
        for raw_line in key_file.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if line and not line.startswith("#") and "=" in line:
                needs_generation = False
                break
    if needs_generation:
        secrets_dir.mkdir(parents=True, exist_ok=True)
        key_file.write_text(
            f"NPDEV_AUTH_API_KEYS={secrets.token_hex(32)}=dev:developer:admin", encoding="utf-8")

    live_key = None
    for raw_line in key_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name == "NPDEV_AUTH_API_KEYS":
            os.environ["NPDEV_AUTH_API_KEYS"] = value
            os.environ["NPDEV_AUTH_APIKEYS"] = value
            live_key = value.split("=", 1)[0]
    if live_key is None:
        raise SystemExit(f"{key_file} carries no usable NPDEV_AUTH_API_KEYS mapping")
    return live_key


def http(method: str, url: str, body: dict | None = None, timeout: int = 30):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    request.add_header("X-Api-Key", API_KEY)
    # Charset stated explicitly. A default-encoded body is how a unicode test ends up proving what
    # the HTTP client did rather than what the database did.
    if data is not None:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read().decode("utf-8")
            return response.status, (json.loads(payload) if payload.strip() else None)
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", errors="replace")
        try:
            return error.code, json.loads(payload) if payload.strip() else None
        except json.JSONDecodeError:
            return error.code, {"raw": payload[:2000]}


def stage_input(probe_input: Path, work: Path) -> Path:
    """Copy the probe's `Input/` into the build root before touching anything.

    The engine is chosen at RUN time, so this proof has to write a `db.definition.json` -- and writing
    it beside the checked-in probe would mutate the repo, leave a stale engine-specific file behind
    for whoever reads it next, and trip the workspace-slimness commit hook. BUILD_OUTPUT_LOCATION_POLICY
    says generated artifacts never live inside the repo; a file this script generates is one.
    """
    staged = work / "Input"
    if staged.exists():
        shutil.rmtree(staged)
    shutil.copytree(probe_input, staged)
    return staged


def write_db_definition(app_input: Path, args) -> dict:
    """The db.definition.json this proof runs against, built by the CLI's own engine registry.

    Deliberately NOT hand-written here: `npdev init --engine` is the supported way a user selects an
    engine, so a proof that writes its own definition would be proving a file shape no user produces.
    """
    sys.path.insert(0, str(_repo_root() / "NPDevCli"))
    import npdev_engines  # noqa: PLC0415 - path must be set first

    definition = npdev_engines.db_definition_for(
        args.engine,
        database_name=args.db_name,
        host=args.db_host,
        port=args.db_port,
        username=args.db_user,
        password=args.db_password,
    )
    (app_input / "db.definition.json").write_text(
        json.dumps(definition, indent=2) + "\n", encoding="utf-8")
    return definition


def generate(root: Path, app_input: Path, output: Path, engine: str | None = None) -> None:
    log(f"generating into {output}")
    completed = subprocess.run(
        [sys.executable, str(root / "NPDevCli" / "npdev_cli.py"), "generate", "app",
         "--model", str(app_input / "model.json"),
         "--config", str(app_input / "config.json"),
         "--output", str(output),
         "--require-db-definition"],
        cwd=str(root), check=False)
    if completed.returncode != 0:
        raise SystemExit(f"generation failed (exit {completed.returncode})")
    lint_emitted_sql(root, output, engine)


def lint_emitted_sql(root: Path, output: Path, engine: str | None) -> None:
    """Scan the schema script we just emitted BEFORE trying to boot on it.

    <p>Flyway stops at the first statement it cannot execute, so a script with three unportable
    constructs costs three ~12-minute CI rounds to discover -- which is exactly how STOR-5 and STOR-7
    were found, one error message at a time. The linter reports every construct in one pass, here,
    seconds after generation and before a single container starts.

    Silent when the engine has no SQL dialect (InMemory) or is unknown to the linter; a missing
    engine name must not turn into a skipped check that nobody notices, so the name is resolved
    through the same registry the rest of the harness uses.
    """
    dialect = _PORTABILITY_DIALECTS.get((engine or "").strip().lower())
    if dialect is None:
        return
    completed = subprocess.run(
        [sys.executable, str(root / "scripts" / "quality" / "check-emitted-sql-portability.py"),
         "--search-root", str(output), "--generated-for", dialect, "--allow-empty"],
        cwd=str(root), check=False)
    if completed.returncode != 0:
        raise SystemExit(
            f"the emitted schema script contains SQL {dialect} cannot run (see the scan above). "
            f"Booting would fail inside Flyway at the FIRST of them; this reports all of them.")


# Engine key -> the dialect the linter knows it by. InMemory maps to None on purpose: it stores
# nothing in SQL, so there is no script and nothing to check.
_PORTABILITY_DIALECTS = {
    "postgres": "postgres",
    "h2local": "h2",
    "h2server": "h2",
    "mysql": "mysql",
    "sqlserver": "sqlserver",
    "inmemory": None,
}


def build(app_root: Path) -> Path:
    log("building bootJar")
    gradlew = app_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.exists():
        raise SystemExit(f"generated app has no gradle wrapper at {gradlew}")
    if os.name != "nt":
        gradlew.chmod(0o755)
    completed = subprocess.run(
        [str(gradlew), "bootJar", "--no-daemon", "--console=plain"],
        cwd=str(app_root), check=False)
    if completed.returncode != 0:
        raise SystemExit(f"build failed (exit {completed.returncode})")
    jars = sorted((app_root / "build" / "libs").glob("*.jar"))
    if not jars:
        raise SystemExit("build succeeded but produced no jar")
    return jars[0]


class App:
    """A booted generated app, started and stopped explicitly so a restart can be proven."""

    def __init__(self, jar: Path, app_root: Path, port: int, log_path: Path):
        self.jar, self.app_root, self.port, self.log_path = jar, app_root, port, log_path
        self.process: subprocess.Popen | None = None

    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def start(self, boot_timeout: int) -> None:
        global API_KEY
        API_KEY = ensure_api_key(self.app_root)
        log(f"starting {self.jar.name} on port {self.port}")
        handle = self.log_path.open("a", encoding="utf-8")
        self.process = subprocess.Popen(
            [shutil.which("java") or "java", "-jar", str(self.jar),
             "--spring.profiles.active=dev",
             f"--server.port={self.port}"],
            cwd=str(self.app_root), stdout=handle, stderr=subprocess.STDOUT,
            # A process group so stop() can take the whole tree down. A boot that leaves a JVM
            # holding the port makes the RESTART assertion fail for a reason that has nothing to do
            # with the engine -- and that failure looks exactly like a real one.
            start_new_session=(os.name != "nt"))
        deadline = time.time() + boot_timeout
        while time.time() < deadline:
            if self.process.poll() is not None:
                raise SystemExit(
                    f"the app exited before it became healthy (exit {self.process.returncode}). "
                    f"Boot log: {self.log_path}\n" + self._tail())
            try:
                status, payload = http("GET", f"{self.base()}/actuator/health", timeout=5)
                if status == 200 and (payload or {}).get("status") == "UP":
                    log(f"healthy after {int(boot_timeout - (deadline - time.time()))}s")
                    return
            except (urllib.error.URLError, OSError, TimeoutError):
                pass
            time.sleep(2)
        raise SystemExit(f"the app never became healthy within {boot_timeout}s. "
                         f"Boot log: {self.log_path}\n" + self._tail())

    def stop(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        log("stopping")
        try:
            if os.name == "nt":
                self.process.terminate()
            else:
                os.killpg(os.getpgid(self.process.pid), signal.SIGTERM)
            self.process.wait(timeout=60)
        except (subprocess.TimeoutExpired, ProcessLookupError, PermissionError):
            self.process.kill()

    def _tail(self, lines: int = 60) -> str:
        if not self.log_path.exists():
            return "(no boot log)"
        return "\n".join(self.log_path.read_text(encoding="utf-8", errors="replace")
                         .splitlines()[-lines:])


def assertions(app: App, results: list[dict]) -> None:
    """The things this proof exists to establish. Each records its own verdict."""
    base = app.base()
    concept = "/api/concepts/probe_records"

    def record(name: str, ok: bool, detail: str) -> None:
        results.append({"assertion": name, "ok": bool(ok), "detail": detail})
        log(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")

    # 1. Boots -- already true if we got here, but recorded so the report is complete rather than
    # implying it by the absence of a row.
    record("boots", True, "schema realization ran against the real engine and /actuator/health is UP")

    # 2. Non-BMP unicode, application level (conformance J2 promoted).
    #
    # `order` and `value` ride along on the SAME payload rather than getting their own record: they
    # are SQL-reserved (STOR-6), so this one write and read passes through the runtime's quoting on
    # the INSERT column list, the SELECT column list and the WHERE clause. Carrying them here means
    # every assertion below exercises them too -- a reserved column on a concept nothing queries
    # proves the emitted DDL and nothing whatsoever about the runtime that has to read it back.
    payload = {
        "code": "UNI-1",
        "label": UNICODE_LABEL,
        "quantity": 7,
        "total": 700,
        "active": True,
        "recordedAt": "2026-08-08T12:00:00Z",
        "order": 42,
        "value": "reserved-on-h2",
    }
    status, created = http("POST", base + concept, payload)
    if status not in (200, 201):
        record("unicode-round-trip", False, f"create returned {status}: {created}")
    else:
        status, listed = http("GET", base + concept + "?where=code:eq:UNI-1")
        rows = (listed or {}).get("content") or []
        got = rows[0].get("label") if rows else None
        record("unicode-round-trip", got == UNICODE_LABEL,
               f"stored {UNICODE_LABEL!r}, read back {got!r}"
               + ("" if got == UNICODE_LABEL else
                  " -- the engine or the emitted column type is losing characters SILENTLY; on MySQL "
                  "check utf8mb4, on SQL Server check that the emitter asks "
                  "SqlServerDialect.portableColumnType (NVARCHAR) rather than writing VARCHAR"))

        # 2b. STOR-6, the RUNTIME half. The generator seam is already proven by the fact that the
        # app booted at all -- an unquoted `order` column is a syntax error in CREATE TABLE on all
        # four engines. This is the OTHER seam: the app builds, boots, and then cannot read its own
        # table. Both halves have to agree, which is why they are a registered twin pair.
        reserved_order = rows[0].get("order") if rows else None
        reserved_value = rows[0].get("value") if rows else None
        ok = reserved_order == 42 and reserved_value == "reserved-on-h2"
        record("reserved-identifier-round-trip", ok,
               f"order={reserved_order!r}, value={reserved_value!r} (expected 42 / 'reserved-on-h2')"
               + ("" if ok else
                  " -- the DDL quoted these and the runtime did not, or the reverse. Check that "
                  "SchemaRealizationEmitter.sqlId and JdbcBusinessConceptStore.sqlId are BOTH "
                  "asking SqlDialect.identifier()"))

    # 3. Query path: filtered and ordered, so pagination is built by the dialect.
    for index in range(3):
        http("POST", base + concept, {
            "code": f"Q-{index}", "label": f"query row {index}", "quantity": index + 1,
            "total": (index + 1) * 100, "active": True,
            "recordedAt": "2026-08-08T12:00:00Z",
        })
    # `/page`, not the plain list endpoint: this is ConceptQueryController's server-side paged,
    # filtered, sorted surface -- the one that pushes LIMIT/OFFSET down to the store, which is the
    # dialect code path under test. The plain endpoint filters but does not page, so asserting on it
    # would have proved nothing about pagination while looking like it did.
    #
    # Two details this took a local rehearsal to get right, both of which would have burned a CI run:
    # the path segment here is the CONCEPT NAME (ConceptQueryRequest.conceptName), not the realized
    # table name the generated CRUD controller uses on the SAME base path; and the response key is
    # `items`, not the `content` the CRUD endpoint returns.
    status, page = http("GET", base + "/api/concepts/ProbeRecord/page"
                                      "?page=0&size=2&sort=quantity&direction=asc")
    rows = (page or {}).get("items") or []
    quantities = [row.get("quantity") for row in rows]
    record("query-pagination", status == 200 and len(rows) == 2 and quantities == sorted(quantities),
           f"status={status}, page size {len(rows)}, quantities {quantities} "
           f"(SQL Server binds offset/limit in the REVERSED order -- a wrong page here is silent on "
           f"the other engines)")


def restart_assertion(app: App, results: list[dict], boot_timeout: int) -> None:
    """E5: stop, start, read. The genuinely unknown one."""
    before_status, before = http("GET", app.base() + "/api/concepts/probe_records?where=code:eq:UNI-1")
    before_rows = len((before or {}).get("content") or [])
    app.stop()
    time.sleep(3)
    app.start(boot_timeout)
    after_status, after = http("GET", app.base() + "/api/concepts/probe_records?where=code:eq:UNI-1")
    after_rows = (after or {}).get("content") or []
    label = after_rows[0].get("label") if after_rows else None
    ok = before_status == 200 and after_status == 200 and len(after_rows) == before_rows > 0
    results.append({
        "assertion": "restart-preserves-rows",
        "ok": bool(ok and label == UNICODE_LABEL),
        "detail": f"{before_rows} row(s) before restart, {len(after_rows)} after; label {label!r}. "
                  f"Boot-time schema realization must CONVERGE an existing schema rather than "
                  f"recreate it -- a different code path on every engine.",
    })
    log(f"{'PASS' if results[-1]['ok'] else 'FAIL'}  restart-preserves-rows: {results[-1]['detail']}")


def print_boot_log_tail(boot_log: Path, lines: int = 120) -> None:
    """On ANY failure, print the app's own log -- not only when the app failed to BOOT.

    The harness used to surface the boot log only in the "it never became healthy" path. When the app
    booted and a WRITE returned 500, all CI showed was:

        create returned 500: {'status': 500, 'error': 'Internal Server Error',
                              'path': '/api/concepts/probe_records'}

    which names no cause at all -- the stack trace was in a file on a runner that then went away. That
    cost a full ~12-minute round to learn nothing, on top of the round that produced it. The whole
    point of this exercise is that a failure has to say why the first time.
    """
    try:
        content = boot_log.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as error:
        log(f"could not read the boot log at {boot_log}: {error}")
        return
    log(f"---- last {min(lines, len(content))} line(s) of {boot_log} ----")
    for line in content[-lines:]:
        print(line, flush=True)
    log("---- end of boot log ----")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engine", required=True)
    parser.add_argument("--probe", default=None,
                        help="Probe app directory (default: NPDevSamples/probes/engine-probe).")
    parser.add_argument("--port", type=int, default=18310)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=None)
    parser.add_argument("--db-user", default=None)
    parser.add_argument("--db-password", default=None)
    parser.add_argument("--db-name", default="npdev_engine_probe")
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--report", default=None)
    parser.add_argument("--work", default=None, help="Where to generate (default: <build root>/engine-proof).")
    args = parser.parse_args(argv)

    root = _repo_root()
    probe = Path(args.probe) if args.probe else root / "NPDevSamples" / "probes" / "engine-probe"
    app_input = probe / "Input"
    if not (app_input / "model.json").exists():
        raise SystemExit(f"probe model not found at {app_input / 'model.json'}")

    # NEVER inside the repo (BUILD_OUTPUT_LOCATION_POLICY): a generated Output/ trips the slimness
    # hook and blocks commits. NPDEV_BUILD_ROOT is what CI sets; the sibling fallback is arithmetic
    # from this file, not an ancestor walk looking for a directory named NPDev_General (REG-144).
    build_root = Path(os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build"))
    work = Path(args.work) if args.work else build_root / "engine-proof" / args.engine
    work.mkdir(parents=True, exist_ok=True)
    output = work / "App"
    boot_log = work / "boot.log"
    if boot_log.exists():
        boot_log.unlink()

    staged_input = stage_input(app_input, work)
    definition = write_db_definition(staged_input, args)
    log(f"engine {args.engine}: {json.dumps(definition['database'])}")

    results: list[dict] = []
    app: App | None = None
    failure: str | None = None
    try:
        generate(root, staged_input, output, args.engine)
        jar = build(output)
        app = App(jar, output, args.port, boot_log)
        app.start(args.boot_timeout)
        assertions(app, results)
        restart_assertion(app, results, args.boot_timeout)
    except SystemExit as exc:
        failure = str(exc)
        log(f"ABORTED: {failure}")
    finally:
        if app is not None:
            app.stop()

    ok = failure is None and all(result["ok"] for result in results)
    if not ok:
        print_boot_log_tail(boot_log)
    report = {
        "schemaVersion": "npdev-engine-app-proof.v1",
        "engine": args.engine,
        "ok": ok,
        "failure": failure,
        "assertions": results,
        "bootLog": str(boot_log),
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(f"\n### Engine app proof -- {args.engine}: "
                         f"{'PASS' if ok else 'FAIL'}\n\n")
            handle.write("| assertion | result | detail |\n|---|---|---|\n")
            for result in results:
                handle.write(f"| {result['assertion']} | "
                             f"{'pass' if result['ok'] else '**FAIL**'} | {result['detail']} |\n")
            if failure:
                handle.write(f"\n**Aborted:** {failure}\n")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
