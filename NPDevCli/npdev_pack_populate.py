#!/usr/bin/env python3
"""Workshop rung 'populated': fill the throwaway app a pack was tested in with the pack's own demo data.

The pack ships a `demo/` folder (formato X6 of the Mockuper integration plan):

    demo/identities.json   who the data is created as: name, role, and the environment variable that holds the key
    demo/mockuper.yaml     Mockuper's own config (demand, overrides, output.api.identities / tables)
    demo/cache/            the AI answers Mockuper needs, generated ONCE by the author, so every run here is offline

`npdev pack workshop test <pack> --populate` runs, while the probe consumer is up:

    1. /v3/api-docs of the running app is saved, and the row count of every creatable route is read as an ADMIN reader
    2. `mockuper run -c demo/mockuper.yaml --openapi <saved> --cache-only --cache-dir demo/cache --target api --apply`
       with the app URL and one throwaway key per identity ONLY in that process's environment (never in a file)
    3. the count of every route is read again: at least one route must have grown by at least what Mockuper reports it
       created (proof the rows were stored), and Mockuper must have reported no refusal (it stops at the first 4xx the app answers)

Mockuper is a separate project and is only ever started as a process. Its command is `NPDEV_MOCKUPER_CMD` (a command line)
or `mockuper` on PATH; without it the rung is SKIPPED, never failed. The run only ever targets a loopback address: the
throwaway app of the workshop is the one place `--apply` is allowed (Mockuper AGENTS rule 9, owner decision D2). Mockuper
inherits NOTHING of the owner's environment except an allowlist (child_env): it reads the app URL and every key from the
environment by NAME, so a demo naming some other variable must find it empty, not a real secret.

A rung that cannot be climbed here (no Mockuper, no cache hit, no time, a dying app, no count confirms the rows) is `skipped`;
only a measured contradiction between the demo and the pack is `fail`: the app ANSWERED a 4xx to a row (Mockuper exit 6 with
"HTTP 4xx", or exit 5), rows were created as an identity the demo does not declare, or nothing was created. A count that grew
by less than was created is a note, not a failure: the reader goes through the same read rules as everyone. Stdlib only.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import shutil
import subprocess
import time
from pathlib import Path
from urllib.parse import urlparse

DEMO_DIR = "demo"
IDENTITIES_FILE = "identities.json"
CONFIG_FILE = "mockuper.yaml"
CACHE_DIR = "cache"
USER_PREFIX = "demo-"
READER = {"name": "workshop-reader", "role": "ADMIN"}   # reads counts and /v3/api-docs; never creates anything
BUDGET_SECONDS = 180          # owner rule (2026-11-14): nothing runs longer than 5 minutes; over budget = skipped
COUNT_BUDGET_SECONDS = 45     # for ALL the count reads before the load, and again for those after it
ENV_COMMAND = "NPDEV_MOCKUPER_CMD"
LOCAL_HOSTS = ("127.0.0.1", "localhost", "::1")
SCHEMA_PATH = Path(__file__).resolve().parents[1] / "NPDevContract" / "schemas" / "pack-workshop" / "demo.schema.json"
SUMMARY_FILE = "summary.json"
EVIDENCE_FILES = (SUMMARY_FILE, "plan.json", "requests.jsonl", "created.jsonl", "mockuper.log")
# Mockuper's exit codes that mean "the DATA does not fit the app" (IntegrityViolationError, TargetAPIError); every other
# non-zero code (config, schema, provider/cache, crash) says nothing about the pack and only skips the rung.
DATA_EXIT_CODES = (5, 6)


class PopulateUnavailable(Exception):
    """The rung cannot be climbed on this machine or with this demo (no Mockuper, stale cache, no time): skipped."""


def demo_dir(pack_dir: Path) -> Path | None:
    folder = pack_dir / DEMO_DIR
    return folder if folder.is_dir() else None


def static_problems(pack_dir: Path) -> list[str]:
    """'where - what - how to fix' lines for a demo/ folder that cannot work; [] when there is none or it is sound.
    Cheap (no app, no Mockuper), so the workshop runs it at the first rung whether or not --populate was asked."""
    folder = demo_dir(pack_dir)
    if folder is None:
        return []
    import npdev_jsonschema
    problems: list[str] = []
    identities_path = folder / IDENTITIES_FILE
    config_path = folder / CONFIG_FILE
    doc = None
    if not identities_path.is_file():
        problems.append(f"demo/{IDENTITIES_FILE} - it is missing - list who the demo data is created as (schema: demo.schema.json)")
    else:
        try:
            doc = json.loads(identities_path.read_text(encoding="utf-8"))
        except ValueError as exc:
            problems.append(f"demo/{IDENTITIES_FILE} - it is not valid JSON ({exc}) - fix the syntax")
    if doc is not None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        for error in npdev_jsonschema.validate(schema, doc)[:10]:
            problems.append(f"demo/{IDENTITIES_FILE} - {error.get('path') or '$'}: {error.get('message')} - fix that member")
        if not problems:
            names = [i["name"] for i in doc["identities"]]
            envs = [i["keyEnv"] for i in doc["identities"]] + [doc["targetUrlEnv"]]
            if len(set(names)) != len(names):
                problems.append(f"demo/{IDENTITIES_FILE} - two identities share a name - give each identity its own name")
            if len(set(envs)) != len(envs):
                problems.append(f"demo/{IDENTITIES_FILE} - two entries use the same environment variable - give each key and the URL its own")
    if not config_path.is_file():
        problems.append(f"demo/{CONFIG_FILE} - it is missing - add Mockuper's config (output.api with base_url_env and the identities)")
    elif doc is not None and not problems:
        # comments stripped: a name in a comment must not count as "mentioned" nor as "used"
        text = "\n".join(line.split("#", 1)[0] for line in config_path.read_text(encoding="utf-8", errors="replace").splitlines())
        declared = {doc["targetUrlEnv"]} | {i["keyEnv"] for i in doc["identities"]}
        for env in sorted(declared):
            if not re.search(rf"\b{re.escape(env)}\b", text):
                problems.append(f"demo/{CONFIG_FILE} - it never mentions {env}, which demo/{IDENTITIES_FILE} says carries a value "
                                f"- name it as base_url_env / the identity's value_env (the workshop sets it for the run)")
        # Mockuper reads every *_env member from the process environment BY NAME: any variable other than the ones the workshop
        # sets would be read from whatever the machine holds. The process gets an allowlist (child_env) AND the yaml may name only these.
        for member, name in sorted(set(re.findall(r"\b([A-Za-z]+_env)\s*:\s*[\"']?([A-Za-z_][A-Za-z0-9_]*)", text))):
            if name not in declared:
                problems.append(f"demo/{CONFIG_FILE} - {member}: {name} names a variable that demo/{IDENTITIES_FILE} does not declare "
                                f"- only the app URL and the identities' keys are set for the run; declare it there or remove it")
        if re.search(r"(?im)^\s*(token|password|secret|api_?key)\s*:\s*[\"']?[A-Za-z0-9+/_=-]{12,}", text):
            problems.append(f"demo/{CONFIG_FILE} - it seems to hold a credential - never write one: use *_env members that name an "
                            f"environment variable")
    if not (folder / CACHE_DIR).is_dir() or not any((folder / CACHE_DIR).iterdir()):
        problems.append(f"demo/{CACHE_DIR}/ - it is missing or empty - run Mockuper once with the AI and `mockuper cache export` the "
                        f"answers here; the workshop runs it offline (--cache-only)")
    return problems


def load_demo(pack_dir: Path) -> dict | None:
    """{folder, targetUrlEnv, identities} of a sound demo/ (static_problems() is []), or None when the pack has none."""
    folder = demo_dir(pack_dir)
    if folder is None:
        return None
    doc = json.loads((folder / IDENTITIES_FILE).read_text(encoding="utf-8"))
    return {"folder": folder, "targetUrlEnv": doc["targetUrlEnv"], "identities": doc["identities"]}


def user_name(identity: str) -> str:
    return USER_PREFIX + identity


def demo_users(demo: dict) -> list[dict]:
    """The app identities the demo needs: `demo-<name>` with the declared role."""
    return [{"name": user_name(i["name"]), "role": i["role"]} for i in demo["identities"]]


def merge_users(users: list[dict], demo: dict) -> tuple[list[dict], str]:
    """The probe users plus the demo's. Returns (merged, clash text or ''): a name is shared only with the same role."""
    merged = list(users)
    known = {u["name"]: u for u in users}
    if READER["name"] in known:   # the reader is added next to the users with a key of its own: its name must be free
        return users, f"a user is named '{READER['name']}', which the workshop keeps for the identity that reads the demo back"
    for user in demo_users(demo):
        other = known.get(user["name"])
        if other is None:
            merged.append(user)
        elif other.get("role") != user["role"]:
            return users, (f"the demo identity '{user['name']}' has the role {user['role']!r} but a probe user of the same name has "
                           f"{other.get('role')!r}")
    return merged, ""


def resolve_command(environ=None, which=shutil.which) -> list[str]:
    environ = os.environ if environ is None else environ
    text = (environ.get(ENV_COMMAND) or "").strip()
    if text:
        return [part.strip("\"'") for part in shlex.split(text, posix=(os.name != "nt"))]
    found = which("mockuper")
    if found:
        return [found]
    raise PopulateUnavailable(f"Mockuper is not installed here: put `mockuper` on PATH or set {ENV_COMMAND} to its command line")


# ---- what talks to the app and to Mockuper (each replaceable in tests) -------------------------------------------------

def default_fetch(base_url: str, key: str) -> bytes:
    import urllib.request
    request = urllib.request.Request(base_url.rstrip("/") + "/v3/api-docs", headers={"X-Api-Key": key, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def default_count(base_url: str, key: str, path: str) -> int | None:
    import npdev_pack_probe
    try:
        status, payload = npdev_pack_probe.http_call(base_url, key, "GET", path, None)
    except OSError:
        return None
    return npdev_pack_probe._row_count(payload) if status == 200 else None


def _kill_tree(proc: subprocess.Popen) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        return
    import signal
    try:
        os.killpg(proc.pid, signal.SIGKILL)   # start_new_session made proc the leader of its own group
    except OSError:
        proc.kill()


# What Mockuper's process may inherit. NOTHING else of the owner's environment: Mockuper reads `base_url_env` and every
# `value_env` straight from it, so a demo naming OPENROUTER_API_KEY or HTTPS_PROXY would otherwise send a real secret (or
# the rows) to wherever that variable points, past the loopback rule. The names the demo declares are added by child_env.
INHERITED_ENV = ("PATH", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE",
                 "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "LANG", "LC_ALL", "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE",
                 # where uv keeps its cache and Python: EXACT names, never the UV_ prefix (UV_PUBLISH_TOKEN, UV_INDEX_*_PASSWORD, a
                 # user:token@ in UV_INDEX_URL and UV_ENV_FILE would all pass it)
                 "UV_CACHE_DIR", "UV_PYTHON_INSTALL_DIR", "UV_TOOL_DIR", "UV_PYTHON", "UV_OFFLINE", "UV_NO_SYNC", "UV_FROZEN",
                 "UV_LINK_MODE", "UV_PROJECT_ENVIRONMENT")
INHERITED_PREFIXES = ("XDG_",)


def child_env(demo: dict, base_url: str, keys: dict[str, str], environ=None) -> dict[str, str]:
    environ = os.environ if environ is None else environ
    env = {name: value for name, value in environ.items()
           if name.upper() in INHERITED_ENV or name.upper().startswith(INHERITED_PREFIXES)}
    env.update({"PYTHONUTF8": "1", "NO_PROXY": "127.0.0.1,localhost,::1", demo["targetUrlEnv"]: base_url})
    for identity in demo["identities"]:
        env[identity["keyEnv"]] = keys[user_name(identity["name"])]
    return env


def error_block(stderr: str, secrets: list[str]) -> str:
    """The error Mockuper printed, not the retry warnings before it: from its last `erro:` line (what / onde / como corrigir)."""
    lines = [line.rstrip() for line in (stderr or "").splitlines() if line.strip()]
    start = max((i for i, line in enumerate(lines) if line.startswith("erro:")), default=None)
    block = lines[start:start + 3] if start is not None else lines[-1:]
    return _scrub(" | ".join(part.strip() for part in block), secrets)[:400]


# The app answered 4xx. Read ONLY on the `erro: ... falhou (HTTP nnn)` line (the response preview that follows is the app's own text and
# may say anything); 408/425/429 are what Mockuper itself retries (a busy app), 5xx and "rede" (no answer) are not a refusal either.
APP_REFUSAL = re.compile(r"\(HTTP 4(?!08\)|25\)|29\))\d\d\)")


def default_runner(args: list[str], env: dict, cwd: Path, timeout: float) -> tuple[int, str, str]:
    """Run Mockuper to the end. Returns (exit code, stdout, stderr); raises PopulateUnavailable if it cannot start or runs out of time."""
    # On POSIX the launcher (`uv run`) has a child that keeps the pipes open: own session, so the whole group can be killed.
    group = {} if os.name == "nt" else {"start_new_session": True}
    try:
        proc = subprocess.Popen(args, cwd=str(cwd), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                encoding="utf-8", errors="replace", **group)
    except OSError as exc:
        raise PopulateUnavailable(f"Mockuper could not start ({type(exc).__name__}: {exc})") from None
    try:
        out, err = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _kill_tree(proc)
        try:
            proc.communicate(timeout=10)   # never block the workshop (and the app's stop()) on a pipe a grandchild still holds
        except subprocess.TimeoutExpired:
            pass
        raise PopulateUnavailable(f"Mockuper did not finish inside the {BUDGET_SECONDS} s budget") from None
    return proc.returncode, out, err


# ---- the rung ----------------------------------------------------------------------------------------------------------

def creatable_paths(api_docs: dict) -> list[str]:
    """Collection routes the app lets a client both create in (POST) and list (GET): the ones whose row count can be checked."""
    paths = api_docs.get("paths") if isinstance(api_docs, dict) else None
    return sorted(p for p, ops in (paths or {}).items() if "{" not in p and isinstance(ops, dict) and "get" in ops and "post" in ops)


def _jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rows.append(json.loads(line))
    return rows


def _newest_run_dir(workdir: Path) -> Path | None:
    runs = workdir / ".mockuper" / "runs"
    found = sorted((p for p in runs.iterdir() if p.is_dir()), key=lambda p: p.stat().st_mtime) if runs.is_dir() else []
    return found[-1] if found else None


def _scrub(text: str, secrets: list[str]) -> str:
    for secret in sorted({s for s in secrets if s}, key=len, reverse=True):
        text = text.replace(secret, "***")
    return text


def _norm(path: str) -> str:
    """A route as a key: the api-docs and the Mockuper config may spell the same route with or without a trailing slash."""
    return path.strip().rstrip("/") or "/"


def _result(result: str, detail: str, feedback: list[str] | None = None, evidence: str | None = None) -> dict:
    return {"result": result, "detail": detail, "feedback": feedback or [], "evidence": evidence}


def populate(demo: dict, run: Path, base_url: str, keys: dict[str, str], *, fetch=None, count=None, runner=None,
             environ=None, which=shutil.which) -> dict:
    """Climb the rung against the booted app. Returns {"result": pass|fail|skipped, "detail", "feedback", "evidence"}.
    Never raises for anything the machine or the demo can cause."""
    fetch, count, runner = fetch or default_fetch, count or default_count, runner or default_runner
    host = urlparse(base_url).hostname
    if host not in LOCAL_HOSTS:
        return _result("skipped", f"refusing to send demo data to {host!r}: only the throwaway app on this machine is ever filled")
    out = run / "populate"
    out.mkdir(parents=True, exist_ok=True)
    reader_key = keys[READER["name"]]
    secrets = list(keys.values())
    try:
        command = resolve_command(environ, which)
        raw = fetch(base_url, reader_key)
        api_docs = json.loads(raw)
    except PopulateUnavailable as exc:
        return _result("skipped", str(exc))
    except (OSError, ValueError) as exc:
        return _result("skipped", f"the app's /v3/api-docs could not be read ({type(exc).__name__}: {exc})")
    docs_file = out / "api-docs.json"
    docs_file.write_bytes(raw)
    deadline = time.monotonic() + COUNT_BUDGET_SECONDS

    def timed_count(route: str) -> int | None:   # the count reads are bounded too: a slow app must not carry the rung past the 5-minute rule
        return count(base_url, reader_key, route) if time.monotonic() < deadline else None
    routes = {_norm(p): p for p in creatable_paths(api_docs)}   # normalised path -> the route as the api-docs spells it
    before = {key: n for key, route in routes.items() if (n := timed_count(route)) is not None}

    env = child_env(demo, base_url, keys, environ)
    folder = demo["folder"]
    args = [*command, "run", "-c", str(folder / CONFIG_FILE), "--openapi", str(docs_file), "--cache-only",
            "--cache-dir", str(folder / CACHE_DIR), "--target", "api", "--apply"]
    started = time.monotonic()
    try:
        code, stdout, stderr = runner(args, env, out, BUDGET_SECONDS)
    except PopulateUnavailable as exc:
        return _result("skipped", str(exc))
    log = _scrub(f"exit code {code}\n--- stdout\n{stdout}\n--- stderr\n{stderr}\n", secrets)
    (out / "mockuper.log").write_text(log, encoding="utf-8")
    problem = error_block(stderr or stdout, secrets)
    # exit 6 (TargetAPIError) is also what Mockuper raises when the app never answered ("HTTP rede") or answered 5xx/429: a slow or
    # dying app says nothing about the pack, so only an answered 4xx is the app REFUSING the demo's row
    refused = code == 5 or (code == 6 and APP_REFUSAL.search(problem.split(" | ")[0]) is not None)
    if refused:
        return _result("fail", f"Mockuper stopped with exit code {code}: {problem}",
                       [f"demo/{CONFIG_FILE} - while loading the demo into the pack's own app: {problem} - fix the demo "
                        f"(overrides in demo/{CONFIG_FILE}, or the cached answers) or the pack's rule that refuses it, then send the "
                        f"pack again with a higher version"], "populate/mockuper.log")
    if code != 0:
        return _result("skipped", f"Mockuper exited with code {code} before the demo was fully loaded: {problem}",
                       [f"(warning) the demo was not loaded - Mockuper exited with code {code}: {problem or 'see populate/mockuper.log'}"],
                       "populate/mockuper.log")

    run_dir = _newest_run_dir(out)
    if run_dir is None:
        return _result("skipped", "Mockuper finished but left no run folder, so nothing could be checked", evidence="populate/mockuper.log")
    for name in ("plan.json", "requests.jsonl", "created.jsonl"):
        if (run_dir / name).is_file():
            (out / name).write_text(_scrub((run_dir / name).read_text(encoding="utf-8"), secrets), encoding="utf-8")
    created = _jsonl(run_dir / "created.jsonl")
    requests = _jsonl(run_dir / "requests.jsonl")
    per_table: dict[str, int] = {}
    per_identity: dict[str, int] = {}
    for row in created:
        per_table[row["table"]] = per_table.get(row["table"], 0) + 1
        if row.get("as"):
            per_identity[row["as"]] = per_identity.get(row["as"], 0) + 1
    table_path = {r["table"]: r["path"] for r in requests if r.get("method") == "POST" and r.get("table") and r.get("path")}
    declared = {i["name"] for i in demo["identities"]}
    strangers = sorted(set(per_identity) - declared)
    problems: list[tuple[str, str]] = []   # (what the verdict says, the line for the author)
    notes: list[str] = []
    checked: dict[str, dict] = {}
    unchecked: list[str] = []
    confirmed = 0
    # Tables that POST to the same route share its count: the growth is compared with what ALL of them created, never once per table
    made_by_route: dict[str, int] = {}
    tables_by_route: dict[str, list[str]] = {}
    for table, made in sorted(per_table.items()):
        key = _norm(table_path.get(table, ""))
        if key not in before:
            unchecked.append(table)
            continue
        made_by_route[key] = made_by_route.get(key, 0) + made
        tables_by_route.setdefault(key, []).append(table)
    deadline = time.monotonic() + COUNT_BUDGET_SECONDS
    for key, made in sorted(made_by_route.items()):
        names = ", ".join(tables_by_route[key])
        after = timed_count(routes[key])
        if after is None:
            unchecked.extend(tables_by_route[key])
            continue
        growth = after - before[key]
        checked[routes[key]] = {"table": names, "before": before[key], "after": after, "created": made}
        # The reader goes through the SAME access rules as everyone (ADMIN is not a row-level bypass), so a route whose read rule
        # is owner-scoped may show it less than was created, and a flow or trigger may add rows of its own. Growth of at least
        # `made` PROVES the rows were stored; less than that proves nothing either way, so it is a note, never a rejection.
        if growth >= made:
            confirmed += 1
            if growth > made:
                notes.append(f"{routes[key]}: the app shows {growth - made} more new row(s) of {names} than Mockuper created (a flow or trigger of the pack)")
        else:
            notes.append(f"{routes[key]}: the reader sees {growth} new row(s) of the {made} created for {names} (a read rule may hide the rest)")
    if strangers:
        problems.append((f"Mockuper created rows as {strangers}, which demo/{IDENTITIES_FILE} does not declare",
                         f"demo/{IDENTITIES_FILE} - Mockuper created rows as {strangers}, names this file does not declare - make "
                         f"output.api.identities in demo/{CONFIG_FILE} and this file name the same identities"))
    if not created:
        problems.append(("Mockuper reported success but created nothing",
                         f"demo/{CACHE_DIR}/ - the demo loaded nothing: the cached answers hold no rows for this API - regenerate the "
                         f"cache with Mockuper and the AI against the pack's current API"))
    rows_total = sum(per_table.values())
    line = f"{rows_total} row(s) in {len(per_table)} table(s) by {len(per_identity)} identit{'y' if len(per_identity) == 1 else 'ies'}, 0 refusals"
    summary = {"rows": rows_total, "tables": per_table, "byIdentity": per_identity, "requests": len(requests), "refusals": 0,
               "countsChecked": checked, "countsUnchecked": unchecked, "countsConfirmed": confirmed, "notes": notes,
               "problems": [what for what, _ in problems], "line": line, "seconds": round(time.monotonic() - started, 1)}
    (out / SUMMARY_FILE).write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    evidence = f"populate/{SUMMARY_FILE}"
    if problems:
        return _result("fail", "; ".join(what for what, _ in problems)[:400], [for_author for _, for_author in problems], evidence)
    warnings = [f"(warning) demo load - {n}" for n in notes] + [f"(warning) demo load - the count of {t} could not be read back" for t in unchecked]
    if not confirmed:
        why = "no route's count could be read back" if not checked else "no route's count grew by what Mockuper created"
        return _result("skipped", f"{line} were sent, but the app did not confirm them: {why}", warnings, evidence)
    detail = line + (f"; {len(notes)} note(s)" if notes else "") + (f"; the count of {', '.join(unchecked)} could not be read back" if unchecked else "")
    return _result("pass", detail, warnings, evidence)
