#!/usr/bin/env python3
"""`npdev explore` -- browser explorations: definitions, runs, verdicts, history, retention.

MONITOR_PLAN A4/D4/D5/D6/C1/C3/E1. The Scrap Manager screen is a window onto these verbs; the
PowerShell harness records through them (C1); the Playwright reporter records through them (C2).
That is deliberate and it is R10: **the verdict lives in exactly one function**, here. Two
implementations of "green" is how green quietly comes to mean different things per driver -- the
same failure mode the all-gates rule exists to prevent.

WHAT IS AND IS NOT OWNED HERE
-----------------------------
- The ROUTINE vocabulary is owned by the ScrapForAI engine. `schemas/ai/scrapforai-routine.schema.json`
  is a PIN of what the engine served, not a definition we wrote -- see that file's `.meta.json` for
  why inducing one from the corpus rejects 23 valid actions and misses five constraints.
- The RUN RECORD is ours: `schemas/ai/exploration-run.schema.json`. The engine has no opinion about
  our history.

STORAGE (D4, EXPLORATIONS_ANALYSIS.md 2.2)
------------------------------------------
    <app-definition>/explorations/*.json        definition = truth (layer 2)
    <app>/_ops/explorations/*.json              read-only mirror, carried by the app
    <app>/_ops/exploration-runs/runs.jsonl      append-only index, one line per run
    <app>/_ops/exploration-runs/<runId>/run.json
    <app>/_ops/exploration-runs/blobs/<sha>.png content-addressed screenshots

RETENTION (A4 `prune`, decided 2026-08-10)
------------------------------------------
Records are NEVER deleted. Only blobs are pruned, pinned runs are exempt, and a prune says what it
removed and what it kept. The original rule deleted the only evidence of a still-open bug on day 31,
silently.
"""

from __future__ import annotations

import fnmatch
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

import npdev_jsonschema
import npdev_monitor

RUN_SCHEMA_VERSION = "npdev-exploration-run.v1"
DEFAULT_TARGET_PATH = "/npdev-business-ui/"

# Keys a routine FILE may carry that are NPDev's, not the engine's. `targetPath` keeps a routine
# port-agnostic; everything else is passed through untouched.
_NPDEV_ONLY_ROUTINE_KEYS = {"targetPath", "$schema", "npdev"}
# The engine request keys the harness forwards. Kept in ONE list so `npdev explore validate` and
# `npdev explore run` compose the same request -- "valid in the UI" must mean "runs in the harness".
_FORWARDED_ROUTINE_KEYS = ("scenarioName", "options", "variables", "credentials", "caller")


class ExploreError(Exception):
    """A diagnosed, explainable refusal. Never used for a merely-red run: a routine that ran and
    failed is a RESULT, and D4 is emphatic that a tool problem must not be rendered like a test
    result."""


# ---------------------------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------------------------

def runs_root(app_dir: Path) -> Path:
    return Path(app_dir).expanduser().resolve() / "_ops" / "exploration-runs"


def blobs_dir(app_dir: Path) -> Path:
    return runs_root(app_dir) / "blobs"


def runs_index(app_dir: Path) -> Path:
    return runs_root(app_dir) / "runs.jsonl"


def mirror_dir(app_dir: Path) -> Path:
    return Path(app_dir).expanduser().resolve() / "_ops" / "explorations"


def definition_dirs(app_dir: Path) -> list[Path]:
    """The mirror first, then the app DEFINITION's own folder when the resolved plan names one.
    Layer 2 is the truth (`_ops` is regenerated and would lose them); the mirror is what a FinalApp
    zip carries, so it must still be discoverable on a machine that has only the app."""
    app_root = Path(app_dir).expanduser().resolve()
    directories = [mirror_dir(app_root)]
    # `app_definition_root` is the one place that knows the plan has two writers spelling this
    # differently -- asking it, rather than reading a single plan key here, is why an AppGen-built
    # app's definition-level routines are discoverable at all.
    definition_root = npdev_monitor.app_definition_root(app_root)
    if definition_root:
        directories.append(Path(definition_root) / "explorations")
    return directories


def baselines_dir(app_dir: Path) -> Path:
    for directory in definition_dirs(app_dir)[::-1]:
        if directory.parent.is_dir():
            return directory / "baselines"
    return mirror_dir(app_dir) / "baselines"


def config_path(app_dir: Path) -> Path:
    return Path(app_dir).expanduser().resolve() / "_ops" / "exploration-config.json"


# ---------------------------------------------------------------------------------------------
# Routine -> engine request. ONE composer (R10).
# ---------------------------------------------------------------------------------------------

def compose_engine_request(routine: dict, base_url: str, variables: dict | None = None,
                            credentials: dict | None = None) -> dict:
    """Exactly what `Invoke-ScrapRoutine` builds: drop NPDev's private `targetPath`, inject an
    absolute `targetUrl`, merge runtime variable/credential overrides over the routine's declared
    ones.

    A routine FILE is therefore never validated directly against the pinned schema -- the composed
    REQUEST is. Validating the file would report a missing `targetUrl` on every correct routine in
    the corpus.

    `credentials` is merged exactly like `variables` (R7 Stage D) -- e.g. the freshly-generated
    app's own live API key (see `npdev_monitor._read_live_api_key` / `Get-NpdevLiveApiKey` in
    `sample-common.ps1`), which a routine references via a step's `valueFromCredential` rather than
    a hardcoded `value` so a rotated per-app key never needs a routine-file edit. Kept as a SEPARATE
    bucket from `variables`, never folded in: the engine redacts `credentials` values from evidence
    (`collectKnownSecretValues` in ScrapForAILegacy) but not `variables` ones."""
    target_path = routine.get("targetPath", DEFAULT_TARGET_PATH)
    if not isinstance(target_path, str) or not target_path.startswith("/"):
        target_path = DEFAULT_TARGET_PATH
    request: dict = {"targetUrl": base_url.rstrip("/") + target_path}
    for key in _FORWARDED_ROUTINE_KEYS:
        if key in routine:
            request[key] = routine[key]
    if variables:
        merged = dict(request.get("variables") or {})
        merged.update(variables)
        request["variables"] = merged
    if credentials:
        merged = dict(request.get("credentials") or {})
        merged.update(credentials)
        request["credentials"] = merged
    request["steps"] = routine.get("steps")
    return request


def routine_schema(repo_root: Path) -> dict:
    path = repo_root / "schemas" / "ai" / "scrapforai-routine.schema.json"
    if not path.is_file():
        raise ExploreError(
            f"the pinned routine schema is missing: {path}\n"
            "It is pinned from a running engine, never hand-written -- "
            "run `python scripts/quality/pin-routine-schema.py` with the engine up."
        )
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ExploreError(f"the pinned routine schema is not valid JSON: {exc}") from exc


def run_schema(repo_root: Path) -> dict | None:
    path = repo_root / "schemas" / "ai" / "exploration-run.schema.json"
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


# ---------------------------------------------------------------------------------------------
# validate -- schema + semantic lint
# ---------------------------------------------------------------------------------------------

# Lint rules encode the durable gotchas that cost real sessions. Each is a WARNING unless it makes
# the routine structurally unrunnable, because a lint that blocks Play for a style opinion teaches
# users to bypass validation.
def lint_routine(routine: dict, request: dict) -> list[dict]:
    findings: list[dict] = []
    steps = routine.get("steps")
    if not isinstance(steps, list) or not steps:
        findings.append({"level": "error", "rule": "steps-required", "message": "a routine needs at least one step"})
        return findings

    first = steps[0] if isinstance(steps[0], dict) else {}
    if first.get("action") != "goto":
        findings.append({
            "level": "warning", "rule": "first-step-goto",
            "message": "the first step is usually `goto`: the engine opens targetUrl, but a routine "
                       "that starts by clicking assumes a page it never navigated to",
        })

    # R3, as a rule rather than a memory. `localhost` resolves to ::1 first on Windows while the app
    # binds IPv4, so a routine that hardcodes it fails on exactly the machine it was written on --
    # intermittently, which is worse.
    blob = json.dumps(routine)
    for match in set(re.findall(r"https?://localhost(?::\d+)?", blob)):
        findings.append({
            "level": "warning", "rule": "localhost-not-127-0-0-1",
            "message": f"{match} -- use 127.0.0.1: Windows resolves localhost to ::1 first, and the "
                       "app binds IPv4, so this can fail on the machine it was written on",
        })

    target_url = request.get("targetUrl", "")
    if target_url.startswith("http://localhost"):
        findings.append({
            "level": "warning", "rule": "localhost-not-127-0-0-1",
            "message": "the composed targetUrl uses localhost; the runner probes over 127.0.0.1",
        })

    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        if step.get("action") in {"evaluate", "watch"}:
            findings.append({
                "level": "warning", "rule": "requires-allow-evaluate",
                "message": f"step {index} uses `{step['action']}`, which the engine refuses unless "
                           "ALLOW_EVALUATE=true -- `npdev explore run` starts the engine with it off",
            })
        if not step.get("label"):
            findings.append({
                "level": "info", "rule": "label-missing",
                "message": f"step {index} has no label: the filmstrip and every failure message read "
                           "much better with one",
            })
    return findings


def validate_routine(repo_root: Path, routine_path: Path, base_url: str = "http://127.0.0.1:8080") -> dict:
    try:
        routine = json.loads(Path(routine_path).read_text(encoding="utf-8-sig"))
    except FileNotFoundError:
        raise ExploreError(f"no such routine file: {routine_path}")
    except json.JSONDecodeError as exc:
        return {
            "ok": False, "valid": False, "file": str(routine_path),
            "errors": [{"path": "/", "keyword": "json", "message": f"not valid JSON: {exc}"}],
            "warnings": [], "validator": "json-parse",
        }

    unknown_top = sorted(
        key for key in routine
        if key not in _NPDEV_ONLY_ROUTINE_KEYS and key not in _FORWARDED_ROUTINE_KEYS and key != "steps"
    )
    request = compose_engine_request(routine, base_url)
    schema = routine_schema(repo_root)
    errors = npdev_jsonschema.validate(schema, request)
    unasserted = npdev_jsonschema.unasserted_formats(schema, request)
    findings = lint_routine(routine, request)
    for key in unknown_top:
        findings.append({
            "level": "warning", "rule": "unknown-top-level-key",
            "message": f"`{key}` is neither an NPDev routine key nor forwarded to the engine -- it is "
                       "silently dropped when the routine runs",
        })

    blocking = [f for f in findings if f["level"] == "error"]
    return {
        "ok": not errors and not blocking,
        "valid": not errors and not blocking,
        "file": str(routine_path),
        "composedTargetUrl": request.get("targetUrl"),
        "stepCount": len(routine.get("steps") or []),
        "errors": errors + [{"path": "/", "keyword": f["rule"], "message": f["message"]} for f in blocking],
        "warnings": [f for f in findings if f["level"] != "error"],
        "validator": "pinned-engine-schema (schemas/ai/scrapforai-routine.schema.json)",
        # Never imply a check that did not happen.
        "unassertedFormats": unasserted,
    }


# ---------------------------------------------------------------------------------------------
# D5 -- THE verdict. One definition, one place.
# ---------------------------------------------------------------------------------------------

DEFAULT_ALLOWLIST_DOC = (
    "Narrow and CONDITIONAL, never a substring sweep. Every NPDev app emits a theme.css 404 when it "
    "declares no custom theme, and a 401 on the pre-auth first load, so with no allowlist every "
    "routine is red forever. But excuse 'theme.css 404' globally and an app that ships a REAL theme "
    "whose path later breaks loads unstyled, logs that same 404 and goes GREEN -- the excuse "
    "outliving the reason it was written for, structurally QUAL-4's continue-on-error.\n"
    "\n"
    "MON-12: an entry may be a plain string (substring of the console message TEXT, as above), or an "
    "object naming the exact request a routine intends to fail -- {\"urlContains\": \"/api/concepts/"
    "users\", \"status\": 409, \"note\": \"r7-1 deliberately provokes EmailUnique\"}. `urlContains` is "
    "REQUIRED (there is no object form with no URL fragment -- that would just be a blanket rule "
    "wearing a JSON object), matched against `location.url` for a console error and the origin+path "
    "for a network failure. `status` is optional and is read from the entry's own `status` field when "
    "present, else parsed out of the console text (\"...status of 409...\"). Both narrow the excuse to "
    "the one request it names; neither suppresses the error anywhere else it might appear."
)


def load_verdict_config(app_dir: Path | None) -> dict:
    """Per-app override (D5 rule 2), read from `_ops/exploration-config.json`. An app WITH a custom
    theme simply does not inherit the theme.css excuse -- it says so by setting `hasCustomTheme`."""
    config = {
        "hasCustomTheme": False,
        "inheritDefaults": True,
        "allowedConsoleErrorSubstrings": [],
        "strictNetwork": False,
    }
    if app_dir is None:
        return config
    stored = npdev_monitor._read_json(config_path(app_dir))
    if isinstance(stored, dict):
        for key in config:
            if key in stored:
                config[key] = stored[key]
    return config


def _text_of(entry: object) -> str:
    if isinstance(entry, dict):
        for key in ("text", "message", "failureText", "pathname"):
            value = entry.get(key)
            if isinstance(value, str) and value:
                return value
        return json.dumps(entry)
    return str(entry)


# MON-12: `text` is where a WebKit/Chromium console error puts its message, and for a failed
# resource that message is generic ("Failed to load resource: the server responded with a status of
# 409 ()") on every engine, every app, every request -- the discriminating fact is `location.url`, a
# sibling field `_text_of` never looks at. Measured on a real ScrapForAI run (r7-3, 2026-08-18):
# {"type": "error", "text": "Failed to load resource: ... status of 401 ()",
#  "location": {"url": "http://127.0.0.1:8199/api/me", "line": 0, "column": 0}}.
# A `networkFailure` entry names its request differently (`origin` + `pathname`, or occasionally a
# bare `url`), so this covers both shapes rather than adding a second lookup per kind.
def _url_of(entry: object) -> str:
    if not isinstance(entry, dict):
        return ""
    location = entry.get("location")
    if isinstance(location, dict) and isinstance(location.get("url"), str):
        return location["url"]
    if isinstance(entry.get("url"), str):
        return entry["url"]
    origin = entry.get("origin")
    pathname = entry.get("pathname")
    if origin or pathname:
        return f"{origin or ''}{pathname or ''}"
    return ""


_STATUS_IN_TEXT_RE = re.compile(r"status of (\d{3})")


def _status_of(entry: object, text: str) -> int | None:
    """The HTTP status, however this entry happens to carry it. A `networkFailure` has its own
    `status` field; a `consoleError` never does -- Chromium spells it only inside `text`."""
    if isinstance(entry, dict) and isinstance(entry.get("status"), int):
        return entry["status"]
    match = _STATUS_IN_TEXT_RE.search(text)
    return int(match.group(1)) if match else None


def _allowed_entry_matches(needle: object, entry: object, text: str) -> str | None:
    """Returns the excuse RULE NAME for one `allowedConsoleErrorSubstrings` entry against one
    evidence entry, or None. Two shapes (MON-12):

    - a plain string: substring of `text`, as it always was -- honest for an error whose TEXT is the
      identifying detail (a page error, a thrown message).
    - an object naming the request: `{"urlContains": "...", "status": 409, "textContains": "...",
      "note": "..."}`. `urlContains` is REQUIRED -- an object with none is not a narrower rule, it is
      a blanket rule dressed as one, so it is refused (never matches) rather than silently widened.
      `status`/`textContains` are additional AND-ed narrowing, not alternatives to `urlContains`."""
    if isinstance(needle, str):
        return f"app:{needle}" if needle and needle in text else None
    if not isinstance(needle, dict):
        return None
    url_contains = needle.get("urlContains")
    if not url_contains or url_contains not in _url_of(entry):
        return None
    text_contains = needle.get("textContains")
    if text_contains and text_contains not in text:
        return None
    wanted_status = needle.get("status")
    if wanted_status is not None and _status_of(entry, text) != wanted_status:
        return None
    label = needle.get("note") or url_contains
    return f"app:{label}" + (f" (status={wanted_status})" if wanted_status is not None else "")


def _default_excuse(kind: str, entry: object, config: dict, index: int) -> str | None:
    """Returns the RULE NAME that excuses this entry, or None. Conditional by construction."""
    text = _text_of(entry)
    lowered = text.lower()
    if kind == "consoleError":
        # MON-12 corollary, found proving this fix against a real run: the SAME text-vs-url gap this
        # item exists to close was already live in this default rule, not just in custom excuses. A
        # `consoleError`'s `text` is Chromium's generic "...status of 404 ()" -- it never contains
        # "theme.css", so `"theme.css" in lowered` never matched a real consoleError entry; only the
        # sibling `networkFailure` entry (which carries `pathname` and does not gate green unless
        # `strictNetwork`) was ever excused. Measured 2026-08-18 on a live run of r7-1: the excused
        # list held two `networkFailure` theme.css entries and zero `consoleError` ones, so the app's
        # OWN documented-benign 404 stayed red regardless of the allowlist. `_url_of` is the fix.
        if _url_of(entry).lower().endswith("theme.css") and not config.get("hasCustomTheme"):
            return "default:theme-css-404-when-no-custom-theme-declared"
        # The pre-auth first load. `index == 0` is deliberately not "any 401": an app that 401s on
        # its tenth request is broken, and that is precisely the case a blanket rule would hide.
        if "401" in lowered and index == 0:
            return "default:401-on-first-navigation"
    if kind == "networkFailure" and isinstance(entry, dict):
        status = entry.get("status")
        path = str(entry.get("pathname") or "")
        if status == 404 and path.endswith("theme.css") and not config.get("hasCustomTheme"):
            return "default:theme-css-404-when-no-custom-theme-declared"
        if status == 404 and path.endswith("favicon.ico"):
            return "default:favicon-404"
    return None


def evaluate_verdict(result: dict, config: dict) -> dict:
    """The ONE definition of green (D5). Applied by `run` AND by `record`, so a PowerShell harness
    run and a Manager run and a Playwright run cannot reach different verdicts about the same
    evidence."""
    evidence = result.get("evidence") or {}
    allowed = list(config.get("allowedConsoleErrorSubstrings") or [])
    inherit = bool(config.get("inheritDefaults", True))
    excused: list[dict] = []
    reasons: list[str] = []

    def survives(kind: str, entries: list, index_matters: bool = False) -> list:
        remaining = []
        for index, entry in enumerate(entries or []):
            text = _text_of(entry)
            rule = None
            for needle in allowed:
                rule = _allowed_entry_matches(needle, entry, text)
                if rule:
                    break
            if rule is None and inherit:
                rule = _default_excuse(kind, entry, config, index if index_matters else -1)
            if rule:
                excused.append({"kind": kind, "text": text[:500], "rule": rule})
            else:
                remaining.append(entry)
        return remaining

    console_errors = survives("consoleError", evidence.get("consoleErrors") or [], index_matters=True)
    page_errors = survives("pageError", evidence.get("pageErrors") or [])
    external = survives("unexpectedExternalRequest", evidence.get("unexpectedExternalRequests") or [])
    network = survives("networkFailure", evidence.get("networkFailures") or [])

    status = result.get("status")
    if status != "passed":
        failed_index = result.get("failedStepIndex")
        error = result.get("error") or {}
        detail = f"{error.get('type', '')}: {error.get('message', '')}".strip(": ")
        reasons.append(
            f"status={status}" + (f" at step #{failed_index}" if failed_index is not None else "")
            + (f" -- {detail}" if detail else "")
        )
    if console_errors:
        reasons.append(f"{len(console_errors)} console error(s) -- first: {_text_of(console_errors[0])[:200]}")
    if page_errors:
        reasons.append(f"{len(page_errors)} page error(s) -- first: {_text_of(page_errors[0])[:200]}")
    if external:
        reasons.append(f"{len(external)} unexpected external request(s) -- first: {_text_of(external[0])[:200]}")
    if network and config.get("strictNetwork"):
        reasons.append(f"{len(network)} network failure(s) (strictNetwork) -- first: {_text_of(network[0])[:200]}")

    return {
        "green": not reasons,
        "allowedConsoleErrorSubstrings": allowed,
        "excused": excused,
        "reasons": reasons,
    }


# ---------------------------------------------------------------------------------------------
# Blob store -- content-addressed, so an unchanged screen across 50 runs is ONE file
# ---------------------------------------------------------------------------------------------

def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def resolve_artifact(raw: str, artifact_dir: Path | None) -> Path | None:
    """Where the engine's screenshot actually is.

    MEASURED 2026-08-10: the engine returns `artifacts/job_<id>/screenshots/<name>.png` -- relative,
    with an `artifacts/` prefix that corresponds to ARTIFACT_DIR ITSELF, so the file really lives at
    `<ARTIFACT_DIR>/job_<id>/screenshots/<name>.png`. Treating the value as a path and calling
    `is_file()` on it therefore finds nothing, and the first green run recorded
    "the engine's artifact was gone before it could be stored" for a screenshot sitting on disk two
    directories away. A missing screenshot that is not actually missing is the worst kind: the run
    still goes green and the evidence quietly is not there.

    Candidates in order, each a real filesystem check rather than a guess about the engine's
    convention -- the convention can change, and this survives it."""
    candidate = Path(raw)
    if candidate.is_absolute() and candidate.is_file():
        return candidate
    if artifact_dir is None:
        return candidate if candidate.is_file() else None
    attempts = [artifact_dir / candidate]
    parts = candidate.parts
    if len(parts) > 1:
        # Strip the prefix segment that names the artifact root itself.
        attempts.append(artifact_dir / Path(*parts[1:]))
    attempts.append(Path.cwd() / candidate)
    for attempt in attempts:
        if attempt.is_file():
            return attempt
    # Last resort: the basename, anywhere under the artifact dir. Bounded by that directory, so it
    # cannot wander, and it keeps working if the engine reshapes its layout again.
    try:
        for found in artifact_dir.rglob(candidate.name):
            if found.is_file():
                return found
    except OSError:
        pass
    return None


def store_blob(app_dir: Path, source: Path) -> tuple[str, str, int] | None:
    """(relative blob path, sha256, bytes). Returns None when the source is gone -- the engine's
    artifact retention can beat us to it, and a missing screenshot is worth reporting as absent
    rather than crashing a run that otherwise succeeded."""
    try:
        if not source.is_file():
            return None
        digest = _sha256_file(source)
        target_dir = blobs_dir(app_dir)
        target_dir.mkdir(parents=True, exist_ok=True)
        target = target_dir / f"{digest}{source.suffix or '.bin'}"
        if not target.exists():
            shutil.copy2(source, target)
        return f"blobs/{target.name}", digest, target.stat().st_size
    except OSError:
        return None


# ---------------------------------------------------------------------------------------------
# Run records
# ---------------------------------------------------------------------------------------------

def _utc_now() -> str:
    return npdev_monitor._utc_now()


def new_run_id(stem: str) -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%S")
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", stem).strip("-") or "run"
    return f"{stamp}_{safe}"


def append_run(app_dir: Path, record: dict) -> Path:
    """Writes `<runId>/run.json` and appends ONE line to `runs.jsonl`. The index line is a SUMMARY,
    not the whole record: `runs.jsonl` is read on every Monitor refresh and every Scrap Manager
    load, and a file that grows by 40 KB per run stops being cheap to read."""
    root = runs_root(app_dir)
    run_dir = root / record["runId"]
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "run.json").write_text(json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
    summary = {
        "schemaVersion": RUN_SCHEMA_VERSION,
        "runId": record["runId"],
        "scope": record.get("scope"),
        "suite": record.get("suite"),
        "definition": {
            "kind": (record.get("definition") or {}).get("kind"),
            "path": (record.get("definition") or {}).get("path"),
            "scenarioName": (record.get("definition") or {}).get("scenarioName"),
            "contentSha256": (record.get("definition") or {}).get("contentSha256"),
        },
        "driver": record.get("driver"),
        "startedAt": record.get("startedAt"),
        "durationMs": record.get("durationMs"),
        "status": record.get("status"),
        "failedStepIndex": record.get("failedStepIndex"),
        "stepCount": len(record.get("steps") or []),
        "verdict": {
            "green": (record.get("verdict") or {}).get("green"),
            "excusedCount": len((record.get("verdict") or {}).get("excused") or []),
        },
        "baselineDiff": record.get("baselineDiff"),
        "pinned": bool(record.get("pinned")),
        "ledgerId": record.get("ledgerId"),
    }
    index = runs_index(app_dir)
    index.parent.mkdir(parents=True, exist_ok=True)
    with index.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(summary, ensure_ascii=False) + "\n")
    return run_dir


def read_index(app_dir: Path) -> list[dict]:
    index = runs_index(app_dir)
    if not index.is_file():
        return []
    rows = []
    for line in index.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def read_run(app_dir: Path, run_id: str) -> dict | None:
    path = runs_root(app_dir) / run_id / "run.json"
    return npdev_monitor._read_json(path)


def rewrite_index(app_dir: Path, rows: list[dict]) -> None:
    """Only ever used to UPDATE a row in place (pin). Never to remove one -- records are permanent."""
    index = runs_index(app_dir)
    index.parent.mkdir(parents=True, exist_ok=True)
    index.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# Single-flight (R7)
# ---------------------------------------------------------------------------------------------

class RunLock:
    """One exploration per app at a time. Two browsers driving the same app produce evidence neither
    of them can be trusted about. A stale lock (a killed process) times out rather than blocking
    forever -- a lock nobody can clear is worse than the collision it prevents."""

    def __init__(self, app_dir: Path, stale_after_seconds: int = 900):
        self.app_dir = Path(app_dir).expanduser().resolve()
        self.path = runs_root(app_dir) / ".run.lock"
        self.stale_after = stale_after_seconds
        self.acquired = False

    @staticmethod
    def held_by(app_dir: Path, stale_after_seconds: int = 900) -> tuple[str, int] | None:
        """(holder, age-in-seconds) while a LIVE lock is held, None when free or stale.

        Extracted so `run_suite` can ask "is someone else driving this app?" without owning a second
        copy of the staleness rule -- two staleness rules is how a suite comes to abort on a lock the
        lock itself would have cleared."""
        path = runs_root(app_dir) / ".run.lock"
        if not path.exists():
            return None
        age = int(time.time() - path.stat().st_mtime)
        if age >= stale_after_seconds:
            return None
        return path.read_text(encoding="utf-8", errors="replace").strip(), age

    def __enter__(self) -> "RunLock":
        self.path.parent.mkdir(parents=True, exist_ok=True)
        held = self.held_by(self.app_dir, self.stale_after)
        if held is not None:
            holder, age = held
            raise ExploreError(
                f"another exploration is already running for this app "
                f"(lock held {age}s by {holder}). Stop it first, or wait."
            )
        self.path.unlink(missing_ok=True)  # only ever a stale lock by now
        self.path.write_text(f"pid={os.getpid()} at={_utc_now()}", encoding="utf-8")
        self.acquired = True
        return self

    def __exit__(self, *_exc) -> None:
        if self.acquired:
            self.path.unlink(missing_ok=True)


# ---------------------------------------------------------------------------------------------
# Baselines (C3) -- hash compare only, never pixel diffing
# ---------------------------------------------------------------------------------------------

def baseline_path(app_dir: Path, definition_stem: str) -> Path:
    return baselines_dir(app_dir) / f"{definition_stem}.baseline.json"


def compute_baseline_diff(app_dir: Path, definition_stem: str, record: dict) -> dict | None:
    baseline = npdev_monitor._read_json(baseline_path(app_dir, definition_stem))
    if not baseline:
        return None
    before = {s.get("name"): s.get("sha256") for s in baseline.get("screenshots") or []}
    after = {s.get("name"): s.get("sha256") for s in (record.get("evidence") or {}).get("screenshots") or []}
    changed = sorted(name for name in before.keys() & after.keys() if before[name] != after[name])
    return {
        "comparedTo": baseline.get("runId"),
        "screenshotsChanged": len(changed),
        "screenshotsAdded": len(after.keys() - before.keys()),
        "screenshotsRemoved": len(before.keys() - after.keys()),
        "textChanged": json.dumps(baseline.get("extracted"), sort_keys=True)
                       != json.dumps(record.get("extracted"), sort_keys=True),
        "changed": changed,
    }


def accept_baseline(app_dir: Path, run_id: str) -> dict:
    record = read_run(app_dir, run_id)
    if record is None:
        raise ExploreError(f"no such run: {run_id}")
    stem = Path((record.get("definition") or {}).get("path") or run_id).stem
    target = baseline_path(app_dir, stem)
    target.parent.mkdir(parents=True, exist_ok=True)
    baseline = {
        "schemaVersion": "npdev-exploration-baseline.v1",
        "definition": record.get("definition"),
        "runId": run_id,
        "acceptedAt": _utc_now(),
        "screenshots": (record.get("evidence") or {}).get("screenshots") or [],
        "extracted": record.get("extracted"),
    }
    target.write_text(json.dumps(baseline, indent=2, ensure_ascii=False), encoding="utf-8")
    return {"ok": True, "command": "explore accept", "baseline": str(target), "runId": run_id,
            "screenshots": len(baseline["screenshots"])}


# ---------------------------------------------------------------------------------------------
# Retention (A4 prune)
# ---------------------------------------------------------------------------------------------

def prune(app_dir: Path, *, keep_per_scenario: int = 10, red_days: int = 30, dry_run: bool = False) -> dict:
    """Blobs only. Three rules, in order: records are never deleted; keep the last N runs per
    scenario plus every red run within `red_days`; a pinned or open-ledger-linked run keeps its
    blobs indefinitely.

    Prints what it removed AND what it kept-because-pinned. A silent prune is how evidence
    disappears without anyone deciding to lose it."""
    app_dir = Path(app_dir).expanduser().resolve()
    rows = read_index(app_dir)
    keep_runs: set[str] = set()
    kept_because: dict[str, str] = {}

    by_scenario: dict[str, list[dict]] = {}
    for row in rows:
        scenario = (row.get("definition") or {}).get("scenarioName") or \
                   (row.get("definition") or {}).get("path") or row.get("runId")
        by_scenario.setdefault(scenario, []).append(row)

    for scenario, scenario_rows in by_scenario.items():
        for row in scenario_rows[-keep_per_scenario:]:
            keep_runs.add(row["runId"])
            kept_because.setdefault(row["runId"], f"one of the last {keep_per_scenario} for {scenario}")

    cutoff = datetime.now(timezone.utc) - timedelta(days=red_days)
    for row in rows:
        if row.get("pinned"):
            keep_runs.add(row["runId"])
            kept_because[row["runId"]] = "pinned"
            continue
        if row.get("ledgerId"):
            keep_runs.add(row["runId"])
            kept_because[row["runId"]] = f"linked to ledger item {row['ledgerId']}"
            continue
        green = (row.get("verdict") or {}).get("green")
        if green is False:
            started = _parse_iso(row.get("startedAt"))
            if started and started >= cutoff:
                keep_runs.add(row["runId"])
                kept_because.setdefault(row["runId"], f"a red run less than {red_days} days old")

    referenced: set[str] = set()
    for row in rows:
        if row["runId"] not in keep_runs:
            continue
        record = read_run(app_dir, row["runId"])
        if not record:
            continue
        for shot in (record.get("evidence") or {}).get("screenshots") or []:
            if shot.get("blob"):
                referenced.add(Path(shot["blob"]).name)
        for step in record.get("steps") or []:
            if step.get("screenshot"):
                referenced.add(Path(step["screenshot"]).name)

    removed: list[dict] = []
    freed = 0
    directory = blobs_dir(app_dir)
    if directory.is_dir():
        for blob in sorted(directory.iterdir()):
            if not blob.is_file() or blob.name in referenced:
                continue
            size = blob.stat().st_size
            removed.append({"blob": blob.name, "bytes": size})
            freed += size
            if not dry_run:
                blob.unlink(missing_ok=True)

    return {
        "schemaVersion": "npdev-exploration-prune.v1",
        "command": "explore prune",
        "ok": True,
        "dryRun": dry_run,
        "recordsDeleted": 0,
        "recordsNote": "records are NEVER deleted -- runs.jsonl and every run.json are text and stay forever",
        "runsKept": len(keep_runs),
        "keptBecause": kept_because,
        "blobsRemoved": len(removed),
        "bytesFreed": freed,
        "removed": removed[:200],
    }


def _parse_iso(value: object) -> datetime | None:
    if not isinstance(value, str):
        return None
    text = value.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def pin_run(app_dir: Path, run_id: str, ledger_id: str | None, unpin: bool = False) -> dict:
    record = read_run(app_dir, run_id)
    if record is None:
        raise ExploreError(f"no such run: {run_id}")
    record["pinned"] = not unpin
    if ledger_id:
        record["ledgerId"] = ledger_id
    (runs_root(app_dir) / run_id / "run.json").write_text(
        json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
    rows = read_index(app_dir)
    for row in rows:
        if row.get("runId") == run_id:
            row["pinned"] = not unpin
            if ledger_id:
                row["ledgerId"] = ledger_id
    rewrite_index(app_dir, rows)
    return {"ok": True, "command": "explore pin", "runId": run_id,
            "pinned": not unpin, "ledgerId": record.get("ledgerId")}


# ---------------------------------------------------------------------------------------------
# list / show
# ---------------------------------------------------------------------------------------------

def definition_files(app_dir: Path) -> list[Path]:
    """Every routine definition an app has, in ONE order: `definition_dirs` order (mirror first),
    alphabetical within each directory, first spelling of a filename wins.

    Extracted from `list_explorations` so that `explore list` and `explore suite` iterate the same
    set in the same sequence BY CONSTRUCTION. A suite that ran a set the listing does not show, or
    in a different order, is the same class of drift R10 prevents for verdicts -- and here it would
    be silent, because both answers look plausible on their own.

    NOTE for the next reader: the R3.1 roadmap line says a suite should "loop the app's
    `browser-routines/`". That directory is a SEPARATE, ad-hoc convention -- the
    `NPDevSamples/**/demonstrate-browser.ps1` scripts glob it directly and never enter this module.
    `explorations/` (via `definition_dirs`) is the CLI's live discovery mechanism, so that is what a
    suite runs. Reconciling the two conventions is its own job, deliberately not done here."""
    app_dir = Path(app_dir).expanduser().resolve()
    files: list[Path] = []
    seen: set[str] = set()
    for directory in definition_dirs(app_dir):
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.json")):
            if path.name in seen:
                continue
            seen.add(path.name)
            files.append(path)
    return files


def list_explorations(app_dir: Path, limit: int = 100) -> dict:
    app_dir = Path(app_dir).expanduser().resolve()
    definitions = []
    for path in definition_files(app_dir):
        routine = npdev_monitor._read_json(path) or {}
        definitions.append({
            "name": path.stem,
            "file": str(path),
            "source": "mirror" if path.parent == mirror_dir(app_dir) else "app-definition",
            "scenarioName": routine.get("scenarioName"),
            "targetPath": routine.get("targetPath", DEFAULT_TARGET_PATH),
            "stepCount": len(routine.get("steps") or []),
            "contentSha256": sha256_text(json.dumps(routine, sort_keys=True)),
            "hasBaseline": baseline_path(app_dir, path.stem).is_file(),
        })

    rows = read_index(app_dir)
    runs = list(reversed(rows))[:limit]
    return {
        "schemaVersion": "npdev-exploration-list.v1",
        "command": "explore list",
        "ok": True,
        "appDir": str(app_dir),
        "definitions": definitions,
        "runs": runs,
        "runCount": len(rows),
    }


def show_run(app_dir: Path, run_id: str) -> dict:
    record = read_run(app_dir, run_id)
    if record is None:
        raise ExploreError(f"no such run: {run_id} (looked in {runs_root(app_dir)})")
    run_dir = runs_root(app_dir) / run_id
    resolved = dict(record)
    resolved["runDir"] = str(run_dir)
    resolved["blobsDir"] = str(blobs_dir(app_dir))
    for shot in (resolved.get("evidence") or {}).get("screenshots") or []:
        if shot.get("blob"):
            shot["resolvedPath"] = str(runs_root(app_dir) / shot["blob"])
    return {"schemaVersion": "npdev-exploration-show.v1", "command": "explore show", "ok": True, "run": resolved}


# ---------------------------------------------------------------------------------------------
# coverage -- R3.5: concept -> referencing routines -> last green run, PLUS flow -> referencing
# acceptance scenarios, each with an explicit UNCOVERED section.
# ---------------------------------------------------------------------------------------------

def _concept_selector_needle(name: str) -> str:
    """The routine-side half of `business-ui-app.mustache`'s `sectionId()`: a concept panel's DOM id
    is `"concept-" + conceptName.replace(/[^a-zA-Z0-9_-]/g, "-")`, and every deterministic routine
    step that touches that concept addresses it (or a field inside it) through that id -- see
    `#concept-GiftIdea`, `#concept-GiftIdea [name="idea"]` in the corpus. Mirrored here in Python so
    "does this routine reference this concept" asks the exact same question the generated page
    answers, rather than a second guess at it."""
    return "concept-" + re.sub(r"[^A-Za-z0-9_-]", "-", name or "")


def _flow_execute_path(name: str) -> str:
    """Mirrors `InfoPageEmitter.encodePathSegment()`: percent-encode everything outside the
    unreserved set. Not `urllib.parse.quote`, which form-encodes a space as `+` -- wrong inside a
    path segment and wrong against the URL the app actually serves."""
    out = []
    for byte in (name or "").encode("utf-8"):
        char = chr(byte)
        if char.isalnum() or char in "-._~":
            out.append(char)
        else:
            out.append(f"%{byte:02X}")
    return "/api/flows/" + "".join(out) + "/execute"


def _last_run(rows: list[dict], routine_names: set[str], *, green_only: bool) -> dict | None:
    """`rows` is `read_index()` order (append order, oldest first), so the LAST matching row is the
    most recent run. Never recomputes a verdict (R10) -- `green` is read straight off the row's own
    `verdict.green`, exactly as `evaluate_verdict` decided it when the run happened."""
    best = None
    for row in rows:
        stem = Path((row.get("definition") or {}).get("path") or "").stem
        if stem not in routine_names:
            continue
        if green_only and not (row.get("verdict") or {}).get("green"):
            continue
        best = row
    if best is None:
        return None
    return {
        "runId": best.get("runId"),
        "startedAt": best.get("startedAt"),
        "green": (best.get("verdict") or {}).get("green"),
        "routine": Path((best.get("definition") or {}).get("path") or "").stem,
    }


def coverage(app_dir: Path) -> dict:
    """R3.5: cross the app's own concept/flow inventory (`info.json`, `InfoPageEmitter`'s output --
    NOT the platform's `browser-routines/` corpus, a separate ad-hoc convention `definition_files`'s
    own docstring already distinguishes from this app-scoped one) against which routines/scenarios
    reference each one, plus run history for "last green run". Static: no engine, no HTTP call
    against the app -- everything here is either already on disk (routine/scenario files, run
    history) or already published at generation time (`info.json`), so this runs on a stopped app.

    CONCEPTS are matched against routine files by the deterministic selector `sectionId()` emits
    (`#concept-<Name>`) -- see `_concept_selector_needle`. FLOWS have no browser-UI surface at all
    (`business-ui-app.mustache` never renders a flow trigger), so flow coverage instead comes from
    acceptance scenarios' `when.path`, matched against the flow's real execute URL
    (`_flow_execute_path`) -- this is the roadmap's "flow coverage comes from acceptance scenarios'
    paths" line, verified against `InfoPageEmitter`/`_run_one_scenario` rather than assumed.

    No new discovery mechanism: routines come from `definition_files` (the same list/suite already
    walk) and scenario files are found through the same three-root layering
    (`finalAppRoot`/`appDir`/`appDefinitionRoot`, mirror-then-definition) `npdev_cli.acceptance_dirs`
    already uses for `npdev test`'s acceptance layer, ported here as a small self-contained helper so
    this module does not gain a dependency on the CLI module that depends on it.

    SCHEMA: none, deliberately, the same call `explore list`/`suite`/`preflight`/`prune` already
    made -- this is a computed report over data that is durable elsewhere (info.json, the routine
    files, `runs.jsonl`), not a new fact this command is the author of. A second stored shape of
    facts already recorded elsewhere is a thing that can disagree with them later."""
    app_dir = Path(app_dir).expanduser().resolve()
    app_record = npdev_monitor.probe_app(app_dir, include_info=True)
    if not app_record.get("isAppRoot"):
        raise ExploreError(app_record.get("detail") or f"not a generated NPDev app: {app_dir}")
    if not app_record.get("hasInfoJson"):
        raise ExploreError(
            f"{app_record.get('name')} has no info.json (regenerate the app) -- coverage needs the "
            "concept/flow inventory it publishes.")
    info = app_record.get("info") or {}
    concepts = info.get("concepts") or []
    flows = info.get("flows") or []

    # --- concept -> routines -------------------------------------------------------------------
    routine_haystacks: dict[str, str] = {}
    for path in definition_files(app_dir):
        routine = npdev_monitor._read_json(path)
        routine_haystacks[path.stem] = json.dumps(routine, ensure_ascii=False) if routine else ""
    run_rows = read_index(app_dir)

    concept_rows = []
    for concept in concepts:
        name = concept.get("name")
        needle = _concept_selector_needle(name)
        referencing = sorted(stem for stem, text in routine_haystacks.items() if needle in text)
        names = set(referencing)
        concept_rows.append({
            "name": name,
            "route": concept.get("route"),
            "selector": "#" + needle,
            "referencingRoutines": referencing,
            "lastRun": _last_run(run_rows, names, green_only=False) if referencing else None,
            "lastGreenRun": _last_run(run_rows, names, green_only=True) if referencing else None,
            "covered": bool(referencing),
        })

    # --- flow -> acceptance scenarios ------------------------------------------------------------
    final_app_root = app_record.get("finalAppRoot")
    scenario_dirs: list[Path] = []
    for root in (final_app_root, str(app_dir), app_record.get("appDefinitionRoot")):
        if not root:
            continue
        candidate = Path(root) / "acceptance"
        if candidate not in scenario_dirs:
            scenario_dirs.append(candidate)
    scenario_when_paths: dict[str, str] = {}
    seen_names: set[str] = set()
    for directory in scenario_dirs:
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.scenario.json")):
            if path.name in seen_names:
                continue
            seen_names.add(path.name)
            scenario = npdev_monitor._read_json(path) or {}
            scenario_when_paths[path.name] = str((scenario.get("when") or {}).get("path") or "")

    flow_rows = []
    for flow_name in flows:
        expected_path = _flow_execute_path(flow_name)
        referencing = sorted(name for name, when_path in scenario_when_paths.items()
                             if when_path == expected_path)
        flow_rows.append({
            "name": flow_name,
            "executePath": expected_path,
            "referencingScenarios": referencing,
            "covered": bool(referencing),
        })

    uncovered_concepts = [row["name"] for row in concept_rows if not row["covered"]]
    uncovered_flows = [row["name"] for row in flow_rows if not row["covered"]]

    return {
        "schemaVersion": "npdev-exploration-coverage.v1",
        "command": "explore coverage",
        "ok": True,
        "appDir": str(app_dir),
        "appName": app_record.get("name"),
        "concepts": concept_rows,
        "flows": flow_rows,
        # THE explicit UNCOVERED section (R3.5's own definition of done): a concept/flow with zero
        # referencing routines/scenarios is named here, not just inferable from `covered: false`
        # buried in a per-row field -- "says so" means its own row-set at top level.
        "uncovered": {
            "concepts": uncovered_concepts,
            "flows": uncovered_flows,
        },
        "summary": {
            "conceptsTotal": len(concept_rows),
            "conceptsCovered": len(concept_rows) - len(uncovered_concepts),
            "flowsTotal": len(flow_rows),
            "flowsCovered": len(flow_rows) - len(uncovered_flows),
        },
        "routineSources": [str(d) for d in definition_dirs(app_dir)],
        "scenarioSources": [str(d) for d in scenario_dirs],
    }


# ---------------------------------------------------------------------------------------------
# generate -- R3.3: a create/list/edit/delete routine per concept, from the model
# ---------------------------------------------------------------------------------------------
#
# The field inventory is NOT re-derived from model.json. WmsOffice-class apps compose concepts
# from pack fragments (`{"$ref": "concepts/Foo.json"}`) and field.widget is a per-app CASCADE
# (config.json `overrides` -> field's own `ui.widget` -> platform default, `BusinessUiEmitter
# .widget()`) -- reimplementing either in Python would be a second, driftable copy of resolution
# logic the generator already owns. `generated-ui-manifest.json` is the artefact the generator
# writes with BOTH already fully resolved: it is the exact JSON the running app's own business UI
# fetches (`state.manifest = await fetchJson("./generated-ui-manifest.json")`) to decide each
# field's widget, so building routines from it guarantees the selectors this module emits match
# what the browser actually renders, on every app, without hand-tracing the cascade.
#
# Selectors verified against `business-ui-app.mustache` (not assumed from the roadmap): a panel's
# id is `sectionId()` = `"concept-" + name.replace(/[^a-zA-Z0-9_-]/g, "-")` (mirrored already by
# `_concept_selector_needle`); a scalar field control is `[name="<field>"]`; a `select`-widget
# reference IS that `<select>`; a `lookup`-widget reference is a `.lookup-control` sibling group
# (hidden `[name=field]` + `.lookup-browse` button + `.lookup-display` span) opened via
# `openPickerDialog()` (`.picker-dialog .picker-search`, `.picker-records tbody tr.picker-row`).

_UNSUPPORTED_REFERENCE_WIDGETS = {"autocomplete", "multiselect", "image-select", "custom"}
_GENERATE_MARKER_NUMBER = "94017"
_GENERATE_EDIT_NUMBER = "94018"


def _manifest_path(final_app_root: Path) -> Path | None:
    """Where `BusinessUiEmitter` writes the cascade-resolved per-field manifest, mirroring
    `npdev_monitor._find_info_json`'s own two-candidate reasoning: the GENERATED source set is what
    a plain generation produces; the handwritten one is a fallback for an unusual layout."""
    for relative in (
        Path("npdev-generated") / "src" / "main" / "resources" / "static" / "npdev-business-ui"
        / "generated-ui-manifest.json",
        Path("src") / "main" / "resources" / "static" / "npdev-business-ui" / "generated-ui-manifest.json",
    ):
        candidate = Path(final_app_root) / relative
        if candidate.is_file():
            return candidate
    return None


def _enum_options(field: dict) -> list[tuple[str, str]]:
    """[(value, label), ...], deprecated options dropped, in the manifest's own declaration order --
    mirrors `createEnumSelect`'s own fallback (`enumOptions` when present, else `enumValues` 1:1)."""
    options = field.get("enumOptions") or []
    out = []
    for opt in options:
        if not isinstance(opt, dict) or opt.get("deprecated"):
            continue
        value = opt.get("value")
        if value in (None, ""):
            continue
        out.append((str(value), str(opt.get("label") or value)))
    if out:
        return out
    return [(str(v), str(v)) for v in (field.get("enumValues") or []) if v not in (None, "")]


def _field_kind(field: dict) -> tuple[str, object] | None:
    """(kind, extra) this generator knows how to fill, or None -- object/array/file fields and a
    reference rendered through a widget with no deterministic single-click selection (autocomplete's
    live-search, multiselect's bond editor, image-select's cards, a custom widget) are honestly out
    of scope rather than guessed at."""
    if field.get("id"):
        return None
    ftype = field.get("type")
    widget = field.get("widget")
    if ftype == "boolean":
        return ("boolean", None)
    if ftype == "enum":
        return ("enum", None) if _enum_options(field) else None
    if ftype == "reference":
        if widget in ("select", "lookup"):
            return ("reference", widget)
        return None
    if ftype in ("string", "uuid"):
        return ("text", None)
    if ftype in ("int", "integer", "long", "decimal"):
        return ("number", None)
    if ftype == "date":
        return ("date", None)
    if ftype == "datetime":
        return ("datetime", None)
    return None


def _dependency_target(field: dict) -> str | None:
    """A concept C can only be auto-created once its referenced row already exists. Only a REQUIRED
    reference with a supported widget is a hard dependency -- an optional one is simply left blank,
    same as the generated form itself allows."""
    if field.get("type") != "reference" or not field.get("required"):
        return None
    if field.get("widget") not in ("select", "lookup"):
        return None
    return (field.get("reference") or {}).get("targetConcept") or None


def _plan_concept(concept: dict) -> dict:
    """`fillable` is REQUIRED fields only -- not a smaller ambition than "numerics fill valid
    values", but the engine's own pinned schema (`scrapforai-routine.schema.json`) caps a routine
    at 50 steps, and WmsOffice-class concepts run to 18 fields; filling every optional one blew that
    cap on 2 of 33 concepts when this was tried (`InventarioArquivoLinha`: 18 fields, 69 steps).
    A form is valid with every required field filled and no optional ones touched, so this is a
    real reduction in scope, not a workaround -- and it still exercises an optional field's widget
    wherever a DIFFERENT concept happens to require that same field shape."""
    fields = concept.get("fields") or []
    fillable: list[tuple[dict, str, object]] = []
    unsupported_required: list[str] = []
    deps: set[str] = set()
    for field in fields:
        if field.get("id"):
            continue
        target = _dependency_target(field)
        if target:
            deps.add(target)
        if not field.get("required"):
            continue
        kind = _field_kind(field)
        if kind is None:
            unsupported_required.append(str(field.get("name")))
            continue
        fillable.append((field, kind[0], kind[1]))
    return {
        "name": concept.get("conceptName"),
        "displayName": concept.get("displayName") or concept.get("conceptName"),
        "fillable": fillable,
        "unsupportedRequired": unsupported_required,
        "deps": sorted(deps),
        "formPresentation": concept.get("formPresentation") or "standard",
    }


def _order_and_skip(plans: dict[str, dict]) -> tuple[list[str], dict[str, str]]:
    """Dependency-first order (a required reference's target must have a routine that runs earlier
    in the same `explore suite`, so it has already seeded a row) plus an honest reason for every
    concept this generator will not emit -- never a silent drop."""
    names = set(plans)
    skip_reason: dict[str, str] = {}
    for name, plan in plans.items():
        if plan["unsupportedRequired"]:
            skip_reason[name] = ("required field(s) with no supported auto-fill widget: "
                                 + ", ".join(plan["unsupportedRequired"]))
    for name, plan in plans.items():
        if name in skip_reason:
            continue
        missing = [d for d in plan["deps"] if d not in names]
        if missing:
            skip_reason[name] = ("requires concept(s) not included in this generation run: "
                                 + ", ".join(missing))

    order: list[str] = []
    visited: set[str] = set()

    def visit(name: str, stack: tuple[str, ...]) -> None:
        if name in visited or name in skip_reason:
            return
        if name in stack:
            skip_reason[name] = "circular required-reference chain: " + " -> ".join(stack + (name,))
            return
        for dep in plans[name]["deps"]:
            if dep in plans:
                visit(dep, stack + (name,))
        if name in skip_reason:
            return
        visited.add(name)
        order.append(name)

    for name in sorted(names):
        visit(name, ())

    # A dep that turned out circular (or was itself dropped) does not automatically stop its
    # dependent from having already been appended to `order` above -- propagate to a fixed point
    # rather than special-case the DFS.
    changed = True
    while changed:
        changed = False
        for name in list(order):
            if name in skip_reason:
                continue
            for dep in plans[name]["deps"]:
                if dep in skip_reason:
                    skip_reason[name] = f"depends on skipped concept {dep}: {skip_reason[dep]}"
                    changed = True
                    break
    order = [n for n in order if n not in skip_reason]
    for name in names:
        if name not in order and name not in skip_reason:
            skip_reason[name] = "not reachable (its dependency chain could not be resolved)"
    return order, skip_reason


def _pick_marker(fillable: list[tuple[dict, str, object]]) -> tuple[dict, str, bool] | None:
    """The (field, text, filterable) this concept's generated row will be found by --
    `assertTextContains` after create, and `tbody tr:has-text(marker)` to scope the Edit/Delete
    click to the ONE row this routine itself made, the same `:has-text()` idiom the hand-authored
    corpus already uses (e.g. `gift-idea-tracker`'s `01-giftidea-crud.json`).

    `filterable` matters past a few dozen existing rows: the grid pages at `list.pageSize` (20 by
    default) and `:has-text()`/`assertTextContains` only ever see the CURRENTLY RENDERED page, so a
    routine that never narrows the grid can miss its own freshly-created row on any app that already
    carries real data. MEASURED 2026-08-19 on a long-lived WmsOffice: `LocalArmazenagem`'s create
    genuinely succeeded (the modal closed) and the row was on some later page, but the unfiltered
    `assertTextContains` failed to find it. `business-concept-crud-controller.mustache`'s free-text
    `filter` param ORs a substring match across `filterable` fields only, so the caller (via
    `_build_concept_routine`) fills `.filters input[search]` + clicks Search with this marker
    whenever `filterable` is true, resetting the grid to page 0 with only matching rows -- and skips
    that step (accepting the page-1 risk on a fresh/lightly-used app) when it is not, since filtering
    by a value the server will never match narrows to nothing.

    Preference order: a required, FILTERABLE string field (both readable and searchable) > any
    filterable string field > a required string field with no filterable one available > a
    filterable ENUM field (its rendered LABEL, not the raw `selectOption` value -- the grid shows
    `enumOptions[].label` per `renderEnumBadge`) > a plain required numeric field as a last resort,
    unfilterable by the platform's own default (`isFilterable` on a numeric type), kept because a
    valid numeric fill still doubles as SOME marker on an app that has not accumulated enough data
    for pagination to matter yet. A concept with none of these gets no marker; its routine still
    proves create+list, honestly, and stops there (see `_build_concept_routine`)."""
    def filterable(field: dict) -> bool:
        return bool(field.get("filterable"))

    strings = [f for f, kind, _ in fillable if kind == "text"]
    candidates = ([f for f in strings if f.get("required") and filterable(f)]
                  or [f for f in strings if filterable(f)]
                  or [f for f in strings if f.get("required")] or strings)
    if candidates:
        field = candidates[0]
        return field, f"NPDEV-GEN-{field.get('concept')}", filterable(field)

    enums = [f for f, kind, _ in fillable if kind == "enum" and filterable(f)]
    if enums:
        field = enums[0]
        return field, _enum_options(field)[0][1], True

    numbers = [f for f, kind, _ in fillable if kind == "number"]
    candidates = [f for f in numbers if f.get("required")] or numbers
    if candidates:
        return candidates[0], _GENERATE_MARKER_NUMBER, False
    return None


def _pick_edit_target(fillable: list[tuple[dict, str, object]], marker_field: dict | None):
    """(field, kind, newValue, assertText) for the ONE field the edit step changes -- never the
    marker field itself, or the routine could no longer find its own row afterward. Enum preferred
    (a second declared option is a clean, unambiguous change); then numeric; then text."""
    enums = [f for f, kind, _ in fillable if kind == "enum" and f is not marker_field
             and len(_enum_options(f)) >= 2]
    if enums:
        field = enums[0]
        options = _enum_options(field)
        current = options[0][0]
        alt = next((o for o in options if o[0] != current), options[0])
        return field, "enum", alt[0], alt[1]
    numbers = [f for f, kind, _ in fillable if kind == "number" and f is not marker_field]
    if numbers:
        return numbers[0], "number", _GENERATE_EDIT_NUMBER, _GENERATE_EDIT_NUMBER
    texts = [f for f, kind, _ in fillable if kind == "text" and f is not marker_field]
    if texts:
        field = texts[0]
        value = f"npdev-gen-{field['name']}-edited"
        return field, "text", value, value
    return None, None, None, None


def _generate_slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", (name or "").lower()).strip("-") or "concept"


def _field_selector(form_scope: str, field_name: str) -> str:
    return f'{form_scope} [name="{field_name}"]'


def _create_field_steps(form_scope: str, field: dict, kind: str, extra: object,
                        value: str | None) -> list[dict]:
    selector = _field_selector(form_scope, field["name"])
    tag = f'{field["name"]}' + (" (required)" if field.get("required") else "")
    if kind in ("text", "number"):
        return [{"action": "fill", "selector": selector, "value": value, "label": tag}]
    if kind == "date":
        return [{"action": "fill", "selector": selector, "value": "2026-01-15", "label": tag}]
    if kind == "datetime":
        return [{"action": "fill", "selector": selector, "value": "2026-01-15T10:00", "label": tag}]
    if kind == "boolean":
        return [{"action": "check", "selector": selector, "label": tag}]
    if kind == "enum":
        return [{"action": "selectOption", "selector": selector, "value": _enum_options(field)[0][0],
                "label": f"{tag} enum"}]
    if kind == "reference":
        target = (field.get("reference") or {}).get("targetConcept") or "record"
        if extra == "select":
            # `assertCount` rather than `waitForSelector` on `option:not([value=""])`: Playwright's
            # strict mode requires a `waitFor`-style locator to resolve to exactly one element, and
            # an app with more than one existing referent (any real app after its first few runs)
            # fails that with "resolved to N elements" even though the field is genuinely ready --
            # MEASURED 2026-08-19 against a live gift-idea-tracker with 4 seeded Persons. `assertCount`
            # has no such uniqueness requirement and is what the hand-authored corpus's equivalent
            # step (`04-create-project-and-note-via-ui.json`) already uses for exactly this wait.
            return [
                {"action": "waitForTimeout", "timeoutMs": 600,
                 "label": f"Let the {target} options fetch settle -- {tag}"},
                {"action": "assertCount", "selector": f'{selector} option:not([value=""])',
                 "operator": ">", "count": 0,
                 "label": f"{target} referent options loaded (seeded earlier in this suite)"},
                {"action": "click", "selector": selector, "label": "Focus the select"},
                {"action": "press", "selector": selector, "key": "ArrowDown",
                 "label": "Pick the first real referent -- any valid option proves the round-trip"},
            ]
        # "lookup" (also the reference-field DEFAULT -- see FieldWidgetDefaults.defaultWidget): click
        # the sibling Browse... button (the field's own [name] input is `type=hidden`, so it is never
        # itself clickable), wait for the picker dialog's rows, pick the first -- again "any valid
        # option", never a specific id, matching the corpus's own `06-link-identity-user-role.json`.
        # `assertCount` for the same strict-mode reason as the "select" branch above: once more than
        # one referent has ever been seeded, `.picker-records tbody tr.picker-row` resolves to
        # several rows, and a `waitForSelector`/`locator.waitFor()` on it would refuse to pick one.
        return [
            {"action": "click", "selector": f'{selector} ~ .lookup-browse',
             "label": f"Open the {target} lookup picker -- {tag}"},
            {"action": "waitForSelector", "selector": ".picker-dialog .picker-search", "state": "visible",
             "label": "Picker dialog open"},
            {"action": "waitForTimeout", "timeoutMs": 600, "label": "Let the picker's row fetch settle"},
            {"action": "assertCount", "selector": ".picker-records tbody tr.picker-row",
             "operator": ">", "count": 0,
             "label": f"At least one {target} referent seeded earlier in this suite"},
            {"action": "click", "selector": ".picker-records tbody tr.picker-row >> nth=0",
             "label": "Select the first referent -- any valid option proves the round-trip"},
            {"action": "waitForSelector", "selector": f'{selector} ~ .lookup-display', "state": "visible",
             "label": "Selection round-tripped into the display"},
        ]
    return []


def _routine_document(concept_name: str, steps: list[dict], *, target_path: str = DEFAULT_TARGET_PATH,
                      variables: dict | None = None) -> dict:
    doc = {
        "scenarioName": f"npdev-generated-{_generate_slug(concept_name)}-crud",
        "targetPath": target_path,
        "options": {"headless": True, "screenshots": "always", "collectDomOnFailure": True},
        "steps": steps,
    }
    if variables:
        doc["variables"] = variables
    return doc


def _auth_preamble(auth: dict, origin: str | None) -> tuple[list[dict], str, dict]:
    """(steps, targetPath, variables) for however THIS app authenticates -- read from the manifest's
    own `auth` block rather than assumed, because a WmsOffice-class app is exactly the shape that is
    NOT apiKey: MEASURED 2026-08-19, every one of 5 generated routines timed out waiting for
    `#apiKey` against a live WmsOffice, because `auth.mode === "jwt"` there and the business UI
    keeps that field in the DOM but permanently `display:none` (`business-ui-app.mustache`:
    `el.apiKeyField.style.display = (jwt mode) ? "none" : ""`) -- so `waitForSelector(state:
    visible)` on it can never succeed.

    jwt mode needs a real login first (`/login.html` -- `#username`/`#password`/`#tenant`/
    `#loginBtn`/`#tokenBox`, the SAME generic page every jwt-mode app serves, per
    `wmsoffice-trusted-source-demo-routine.json`), THEN a second `goto` to the business UI, which
    picks the token up from the shared `npdev.shell.token` localStorage key
    (`business-ui-app.mustache`'s `bootstrap()`). `username`/`password` are real secrets --
    `valueFromCredential`, never a literal, and simply unfilled (an honest run-time refusal) if the
    caller does not supply them, exactly like `apiKey` already works. `tenant` is not a secret and
    most apps do not need one, so it is a `variables` default of "" rather than a required
    credential -- override with `--var tenant=<value>` for an app that does."""
    if (auth or {}).get("mode") == "jwt":
        login_path = auth.get("loginPath") or "/login.html"
        business_ui_url = f'{(origin or "").rstrip("/")}{DEFAULT_TARGET_PATH}'
        steps = [
            {"action": "goto", "url": "$targetUrl", "label": "Open the login page"},
            {"action": "waitForSelector", "selector": "#username", "state": "visible",
             "label": "Login form rendered"},
            {"action": "fill", "selector": "#base", "value": origin or "", "label": "Same-origin base"},
            {"action": "fill", "selector": "#username", "valueFromCredential": "username",
             "label": "Username"},
            {"action": "fill", "selector": "#password", "valueFromCredential": "password",
             "label": "Password"},
            {"action": "fill", "selector": "#tenant", "valueFromVariable": "tenant", "label": "Tenant"},
            {"action": "click", "selector": "#loginBtn", "label": "Submit login"},
            {"action": "waitForSelector", "selector": "#tokenBox", "state": "visible",
             "label": "Login succeeded, token stored"},
            {"action": "goto", "url": business_ui_url, "label": "Open the business UI"},
        ]
        return steps, login_path, {"tenant": ""}

    steps = [
        {"action": "goto", "url": "$targetUrl", "label": "Load the business UI"},
        {"action": "waitForSelector", "selector": "#apiKey", "state": "visible",
         "label": "API key field present"},
        {"action": "fill", "selector": "#apiKey", "valueFromCredential": "apiKey",
         "label": "Authenticate"},
        {"action": "reload", "label": "Reload authenticated"},
    ]
    return steps, DEFAULT_TARGET_PATH, {}


def _build_concept_routine(plan: dict, auth: dict | None = None,
                           origin: str | None = None) -> tuple[dict, str | None]:
    """Returns (routine, partialReason). `partialReason` is set (routine still emitted, valid, and
    runnable) when the concept has no field this generator can key a specific row on -- create and
    list are still proven; edit/delete are honestly left out rather than risk clicking the wrong
    row."""
    name = plan["name"]
    fillable = plan["fillable"]
    marker = _pick_marker(fillable)
    marker_field, marker_value, marker_filterable = marker if marker else (None, None, False)

    # `formPresentation: "modal"` (an app-level opt-out from the platform default, e.g. WmsOffice's
    # `Area`) renders the create/edit form into the SHARED `#modalRoot`, not nested inside the
    # concept's own panel -- `buildFormElement`'s field/button DOM is identical either way, only
    # WHERE it is attached differs (`openForm()`/`openModalInto()`). MEASURED 2026-08-19: every
    # `#concept-Area form`/`#concept-Area [name=...]` selector timed out even though the form was
    # genuinely open and correctly filled-out on screen, because it lived under `#modalRoot`
    # instead. `form_scope` is the one thing every field/button/form-visibility selector below is
    # built from, so a concept never needs a second code path for this -- only a different root.
    form_scope = "#modalRoot" if plan.get("formPresentation") == "modal" else f"#concept-{name}"

    preamble, target_path, routine_variables = _auth_preamble(auth or {}, origin)
    steps: list[dict] = list(preamble) + [
        {"action": "waitForSelector", "selector": f'#sideNav a[href="#concept-{name}"]', "state": "visible",
         "label": f"{plan['displayName']} nav link present"},
        {"action": "click", "selector": f'#sideNav a[href="#concept-{name}"]',
         "label": f"Open the {plan['displayName']} concept"},
        {"action": "waitForSelector", "selector": f"#concept-{name}", "state": "visible",
         "label": f"{plan['displayName']} panel visible"},
        {"action": "click", "selector": f'#concept-{name} .panel-actions button:has-text("New")',
         "label": "Open the create form"},
        {"action": "waitForSelector", "selector": f"{form_scope} form", "state": "visible",
         "label": "Create form visible"},
    ]

    number_counter = 0
    for field, kind, extra in fillable:
        if kind == "text":
            value = marker_value if field is marker_field else f"npdev-gen-{field['name']}"
        elif kind == "number":
            if field is marker_field:
                value = marker_value
            else:
                number_counter += 1
                value = str(number_counter)
        else:
            value = None
        steps.extend(_create_field_steps(form_scope, field, kind, extra, value))

    slug = _generate_slug(name)
    steps.append({"action": "screenshot", "name": f"{slug}_form_filled", "label": "Filled create form"})
    steps.append({"action": "click", "selector": f'{form_scope} button:has-text("Create")',
                 "label": "Submit the create form"})
    steps.append({"action": "waitForSelector", "selector": f"{form_scope} form", "state": "detached",
                 "label": "Modal closes only on a successful create"})
    steps.append({"action": "waitForTimeout", "timeoutMs": 800, "label": "Let the grid reload"})

    if marker_value is None:
        steps.append({"action": "screenshot", "name": f"{slug}_created", "label": "Row created"})
        steps.append({"action": "collect", "what": ["domText", "url"], "label": "Evidence"})
        return (_routine_document(name, steps, target_path=target_path, variables=routine_variables),
                "create+list only -- no text/numeric field available to key one row for edit/delete")

    if marker_filterable:
        # Narrows the grid to page 0 with only matching rows BEFORE asserting/clicking -- otherwise
        # both the list-verb assertion and the Edit/Delete row lookup only ever see whatever
        # happens to render on page 1, and on an app that already carries real data the freshly
        # created row usually is not there (see `_pick_marker`'s own note, MEASURED on WmsOffice).
        steps.append({"action": "fill", "selector": f'#concept-{name} .filters input[type="search"]',
                     "value": marker_value, "label": "Isolate this run's row (the table accumulates "
                     "rows across reruns)"})
        steps.append({"action": "click", "selector": f'#concept-{name} .filters button:has-text("Search")',
                     "label": "Apply the filter"})
        # A `waitForSelector` on the real table replacing `renderPanel`'s `.empty`/"Loading"
        # placeholder, not a fixed sleep -- MEASURED 2026-08-19 against a heavily-populated
        # WmsOffice `Rua`/`Entidade` (hundreds of rows from prior sessions): even a 1200ms fixed
        # wait was not always enough for the filtered fetch to land, so the very next step still
        # read "Loading". Waiting for `table.records` to attach scales with however long THIS
        # request actually takes, up to the step's own timeout, instead of guessing a duration.
        steps.append({"action": "waitForSelector", "selector": f"#concept-{name} table.records",
                     "state": "attached", "label": "Filtered grid finished loading"})

    steps.append({"action": "assertTextContains", "selector": f"#concept-{name}", "text": marker_value,
                 "label": "New row visible in the grid (list verb)"})
    # Bare (for `assertCount`, which has no uniqueness requirement) and `>> nth=0`-scoped (for
    # `click`, which -- like `waitForSelector` above -- is a strict-mode Playwright locator and
    # refuses to act on more than one match). The marker is a fixed literal, not a per-run
    # timestamp (determinism: the same model input must produce the same routine bytes), so a
    # rerun against an app that already carries an earlier run's row WILL have more than one
    # match here -- `nth=0` keeps Edit/Delete pointed at exactly one real row regardless.
    row_selector = f'#concept-{name} tbody tr:has-text("{marker_value}")'
    row_click_selector = f'{row_selector} >> nth=0'
    list_only_steps = list(steps) + [
        {"action": "screenshot", "name": f"{slug}_created", "label": "Row created"},
        {"action": "collect", "what": ["domText", "url"], "label": "Evidence"},
    ]

    tail: list[dict] = []
    edit_field, edit_kind, edit_new_value, edit_assert_text = _pick_edit_target(fillable, marker_field)
    if edit_field is not None:
        edit_selector = _field_selector(form_scope, edit_field["name"])
        tail.append({"action": "click", "selector": f'{row_click_selector} >> button:has-text("Edit")',
                     "label": "Re-open this row to edit it"})
        tail.append({"action": "waitForSelector", "selector": f"{form_scope} form", "state": "visible",
                     "label": "Edit form visible"})
        tail.append({"action": "waitForTimeout", "timeoutMs": 600, "label": "Let the form populate"})
        if edit_kind == "enum":
            tail.append({"action": "selectOption", "selector": edit_selector, "value": edit_new_value,
                        "label": "Change the enum to a different declared option"})
        else:
            tail.append({"action": "fill", "selector": edit_selector, "value": edit_new_value,
                        "label": "Change the value"})
        tail.append({"action": "click", "selector": f'{form_scope} button:has-text("Save")',
                    "label": "Submit the edit"})
        tail.append({"action": "waitForSelector", "selector": f"{form_scope} form", "state": "detached",
                    "label": "Modal closes only on a successful save"})
        tail.append({"action": "waitForTimeout", "timeoutMs": 800, "label": "Let the grid reload"})
        tail.append({"action": "assertTextContains", "selector": f"#concept-{name}", "text": edit_assert_text,
                    "label": "Edited value visible in the grid"})

    # MEASURED live against gift-idea-tracker (2026-08-19): `deleteRecord()` guards the DELETE call
    # behind `window.confirm("Delete this record?")`, and the ScrapForAI engine registers no
    # `page.on('dialog', ...)` handler anywhere in its runner -- so Playwright's documented default
    # (auto-DISMISS any unhandled native dialog) cancels every confirm this button raises. A row
    # created via REST, deleted via this exact click sequence through `npdev explore run`, was still
    # present afterward. That is a real, pre-existing gap in the browser-automation harness (not in
    # this generator, not in the generated app), and it lives outside this repo's NPDevCli scope --
    # so the assertion below states what ACTUALLY happens (the click is honored, the confirm is
    # cancelled, the row survives) rather than asserting a deletion this tooling cannot complete.
    tail.append({
        "action": "click", "selector": f'{row_click_selector} >> button:has-text("Delete")',
        "label": "Click Delete -- proves the affordance is wired; see the note above this step in "
                 "generate_routines() about why the confirm is expected to be cancelled",
    })
    tail.append({"action": "waitForTimeout", "timeoutMs": 1000, "label": "Let the cancelled confirm settle"})
    tail.append({"action": "assertCount", "selector": row_selector, "operator": ">=", "count": 1,
                "label": "Row still present: the auto-dismissed confirm leaves the record intact -- "
                         "a known ScrapForAI engine limitation (no dialog handling), not an app defect"})
    tail.append({"action": "screenshot", "name": f"{slug}_created", "label": "Final state"})
    tail.append({"action": "collect", "what": ["domText", "url"], "label": "Evidence"})

    # The pinned engine schema caps a routine at 50 steps. A concept with several required
    # reference fields (each needing 4-6 steps to seed-wait-and-pick) plus a jwt-mode login preamble
    # (10 steps, vs apiKey's 4) can exceed that once edit+delete are added -- MEASURED on WmsOffice's
    # `CrossDocking` (3 required references): 53 steps with edit+delete, 33 without. Same honest
    # fallback as "no marker field": create+list only, never a routine the engine would refuse.
    combined = steps + tail
    if len(combined) > 50:
        return (_routine_document(name, list_only_steps, target_path=target_path, variables=routine_variables),
                f"create+list only -- edit+delete would exceed the engine's 50-step cap "
                f"({len(combined)} steps)")
    return _routine_document(name, combined, target_path=target_path, variables=routine_variables), None


def generate_routines(app_dir: Path, *, concepts: list[str] | None = None,
                      out_dir: Path | None = None, write: bool = True) -> dict:
    """R3.3: emit one create/list/edit/delete routine per concept, built from
    `generated-ui-manifest.json` -- the same resolved facts (widget, enumOptions, reference target)
    the running app's own business UI reads. Written into `mirror_dir(app_dir)` by default (the
    first directory `definition_files()`/`explore suite` already scan), so a plain
    `npdev explore suite --app-dir <app>` picks the result up with no further wiring."""
    app_dir = Path(app_dir).expanduser().resolve()
    app_record = npdev_monitor.probe_app(app_dir)
    if not app_record.get("isAppRoot"):
        raise ExploreError(app_record.get("detail") or f"not a generated NPDev app: {app_dir}")
    final_app_root = Path(app_record["finalAppRoot"])
    manifest_path = _manifest_path(final_app_root)
    if manifest_path is None:
        raise ExploreError(
            f"no generated-ui-manifest.json under {final_app_root} -- regenerate the app "
            "(BusinessUiEmitter writes it under npdev-generated/.../static/npdev-business-ui/)."
        )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    all_concepts = [c for c in (manifest.get("concepts") or []) if c.get("conceptName")]

    if concepts:
        wanted = set(concepts)
        available = {c["conceptName"] for c in all_concepts}
        missing = wanted - available
        if missing:
            raise ExploreError(f"no such concept(s) in {manifest_path}: {', '.join(sorted(missing))}")
        all_concepts = [c for c in all_concepts if c["conceptName"] in wanted]

    plans = {c["conceptName"]: _plan_concept(c) for c in all_concepts}
    order, skip_reason = _order_and_skip(plans)

    auth = manifest.get("auth") or {}
    origin = app_record.get("probeBaseUrl")

    out_dir = Path(out_dir).expanduser().resolve() if out_dir else mirror_dir(app_dir)
    written: list[dict] = []
    partial: list[dict] = []
    for index, name in enumerate(order, start=1):
        routine, partial_reason = _build_concept_routine(plans[name], auth, origin)
        file_name = f"{index:02d}-{_generate_slug(name)}-crud.json"
        target = out_dir / file_name
        if write:
            out_dir.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(routine, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        entry = {"concept": name, "file": str(target), "scenarioName": routine["scenarioName"],
                 "stepCount": len(routine["steps"])}
        if partial_reason:
            entry["partial"] = partial_reason
            partial.append(entry)
        written.append(entry)

    return {
        "schemaVersion": "npdev-exploration-generate.v1",
        "command": "explore generate",
        "ok": True,
        "appDir": str(app_dir),
        "manifestPath": str(manifest_path),
        "outDir": str(out_dir),
        "written": written,
        "skipped": [{"concept": n, "reason": r} for n, r in sorted(skip_reason.items())],
        "summary": {
            "conceptsTotal": len(plans),
            "written": len(written),
            "partial": len(partial),
            "skipped": len(skip_reason),
        },
    }


# ---------------------------------------------------------------------------------------------
# run -- the orchestrator
# ---------------------------------------------------------------------------------------------

def _post_json(url: str, payload: dict, api_key: str, timeout: float = 180.0) -> tuple[int | None, object]:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, raw
    except Exception as exc:
        return None, str(exc)


def preflight(app_dir: Path, engine_port: int, configured_root: str | None,
              workspace_root: Path | None) -> dict:
    """D4: every precondition as its OWN row. A failed precondition is never rendered like a failed
    exploration -- the QUAL-4 lesson, which is that a tool problem dressed as a test result teaches
    people to distrust the tests."""
    checks = []
    app_record = npdev_monitor.probe_app(app_dir)
    checks.append({
        "id": "app-is-generated",
        "name": "the target is a generated NPDev app",
        "status": "pass" if app_record.get("isAppRoot") else "fail",
        "detail": app_record.get("detail") or app_record.get("appDir"),
    })
    healthy = app_record.get("health") == "running"
    checks.append({
        "id": "app-healthy",
        "name": "the app answers /actuator/health",
        "status": "pass" if healthy else "fail",
        "detail": app_record.get("healthDetail") or app_record.get("probeBaseUrl"),
        "fixCommand": None if healthy else "start the app (Monitor: Start, or _ops/Run-FinalApp.ps1)",
    })
    engine = npdev_monitor.detect_engine(engine_port, configured_root, workspace_root)
    checks.append({
        "id": "engine-available",
        "name": "the ScrapForAI engine is available",
        "status": "pass" if engine["found"] else "fail",
        "detail": engine["detail"],
        "found": engine["state"],
    })
    origin = app_record.get("probeBaseUrl")
    checks.append({
        "id": "origin-allowlisted",
        "name": "the app origin will be allowlisted for this run",
        # R4: the CLI owns the allowlist. The UI never composes origins, because two apps explored in
        # one engine session need the UNION and a UI that sends one origin silently breaks the other.
        "status": "pass" if origin else "fail",
        "detail": f"ALLOWED_TARGET_ORIGINS will include {origin}" if origin else "the app has no resolvable port",
    })
    return {"ok": all(c["status"] == "pass" for c in checks), "checks": checks,
            "app": app_record, "engine": engine}


def blocking_preflight(pre: dict) -> list[dict]:
    """The failed preconditions that make running IMPOSSIBLE, as opposed to merely absent.

    `engine-available` is deliberately excluded: `run_exploration` STARTS the engine when it is not
    already up, so its absence is a step, not a blocker. Shared by `run_exploration` and `run_suite`
    so the suite's "can anything run here?" question and the single run's are literally the same
    question -- a suite that refused on a precondition `run` tolerates (or vice versa) would be a
    second, disagreeing gate."""
    return [c for c in pre["checks"] if c["status"] == "fail" and c["id"] != "engine-available"]


def run_exploration(
    repo_root: Path,
    app_dir: Path,
    routine_file: Path,
    *,
    engine_port: int = npdev_monitor.DEFAULT_ENGINE_PORT,
    configured_root: str | None = None,
    api_key: str | None = None,
    driver: str = "cli",
    variables: dict | None = None,
    credentials: dict | None = None,
    ledger_id: str | None = None,
    keep_engine: bool = False,
    on_event=None,
) -> dict:
    """Verify healthy -> ensure the engine is up -> POST -> verdict -> persist -> prune.

    Returns the run record. A routine that RAN and failed returns ok=True with verdict.green=False:
    that is a result. Only a refusal (no app, no engine, a locked app) raises."""
    app_dir = Path(app_dir).expanduser().resolve()
    routine_file = Path(routine_file).expanduser().resolve()
    emit = on_event or (lambda _event: None)

    if not routine_file.is_file():
        raise ExploreError(f"no such routine file: {routine_file}")
    routine = json.loads(routine_file.read_text(encoding="utf-8-sig"))

    emit({"kind": "phase", "phase": "preflight"})
    pre = preflight(app_dir, engine_port, configured_root, Path(repo_root))
    emit({"kind": "preflight", "checks": pre["checks"], "ok": pre["ok"]})
    blocking = blocking_preflight(pre)
    if blocking:
        raise ExploreError("preflight failed: " + "; ".join(f"{c['name']} -- {c['detail']}" for c in blocking))

    app_record = pre["app"]
    engine = pre["engine"]
    base_url = app_record["probeBaseUrl"]
    api_key = api_key or os.environ.get("SCRAPFORAI_API_KEY") or "npdev-scrapforai-localkey-0001"

    with RunLock(app_dir):
        started_process = None
        endpoint = engine.get("endpoint")
        if not endpoint:
            if not engine["found"]:
                raise ExploreError(engine["detail"])
            emit({"kind": "phase", "phase": "starting-engine"})
            endpoint, started_process = _start_engine(engine["root"], engine_port, [base_url], api_key,
                                                      str(runs_root(app_dir) / ".engine-artifacts"))
            emit({"kind": "engine", "state": "started", "endpoint": endpoint})

        try:
            request = compose_engine_request(routine, base_url, variables, credentials)
            errors = npdev_jsonschema.validate(routine_schema(repo_root), request)
            if errors:
                raise ExploreError(
                    "the routine does not satisfy the engine's own schema, so the engine would "
                    "reject it:\n" + npdev_jsonschema.describe(errors)
                )
            emit({"kind": "phase", "phase": "running", "steps": len(request.get("steps") or [])})
            started_at = _utc_now()
            begin = time.time()
            status, body = _post_json(f"{endpoint}/v1/explorations/run", request, api_key)
            elapsed = int((time.time() - begin) * 1000)
        finally:
            if started_process is not None and not keep_engine:
                _stop_process(started_process)
                emit({"kind": "engine", "state": "stopped"})

        if status is None:
            raise ExploreError(f"the engine did not answer: {body}")
        if not isinstance(body, dict) or "steps" not in body:
            raise ExploreError(f"the engine refused the routine (HTTP {status}): {json.dumps(body)[:600]}")

        record = build_run_record(
            app_dir=app_dir,
            repo_root=repo_root,
            result=body,
            routine=routine,
            routine_file=routine_file,
            driver=driver,
            app_record=app_record,
            started_at=started_at,
            duration_ms=body.get("durationMs") or elapsed,
            engine_version=engine.get("state"),
            ledger_id=ledger_id,
        )
        append_run(app_dir, record)
        emit({"kind": "result", "runId": record["runId"], "green": record["verdict"]["green"]})

    # R8: retention is wired into `run` itself, not left as a chore nobody remembers.
    pruned = prune(app_dir)
    record["pruned"] = {"blobsRemoved": pruned["blobsRemoved"], "bytesFreed": pruned["bytesFreed"]}
    return record


# ---------------------------------------------------------------------------------------------
# suite (R3.1) -- every routine an app declares, one after another
# ---------------------------------------------------------------------------------------------

SUITE_SCHEMA_VERSION = "npdev-exploration-suite.v1"


def _suite_blocker(repo_root: Path, app_dir: Path, engine_port: int,
                   configured_root: str | None) -> str | None:
    """One sentence when a condition holds that would defeat EVERY remaining routine, else None.

    This is the classifier behind the refusal decision documented on `run_suite`. It asks only
    APP-WIDE questions -- is the app still there and answering, is someone else holding the lock --
    using the same `preflight`/`RunLock` rules a single run uses. It deliberately knows nothing
    about any individual routine, because that is precisely the distinction it exists to draw."""
    held = RunLock.held_by(app_dir)
    if held is not None:
        holder, age = held
        return (f"another exploration is already running for this app (lock held {age}s by "
                f"{holder}); every remaining routine would be refused the same way")
    pre = preflight(app_dir, engine_port, configured_root, Path(repo_root))
    blocking = blocking_preflight(pre)
    if blocking:
        return "preflight failed: " + "; ".join(f"{c['name']} -- {c['detail']}" for c in blocking)
    return None


def run_suite(
    repo_root: Path,
    app_dir: Path,
    *,
    only: list[str] | None = None,
    stop_on_red: bool = False,
    engine_port: int = npdev_monitor.DEFAULT_ENGINE_PORT,
    configured_root: str | None = None,
    api_key: str | None = None,
    driver: str = "cli",
    variables: dict | None = None,
    credentials: dict | None = None,
    ledger_id: str | None = None,
    keep_engine: bool = False,
    on_event=None,
) -> dict:
    """Run EVERY routine the app declares, in `definition_files` order, and roll the verdicts up.

    A plain sequential loop over `run_exploration`, on purpose. `run_exploration` already takes the
    per-app `RunLock` around each run, so "serial within an app, parallel across apps" is true here
    by construction -- a suite-level lock would be a second lock competing with the real one, and
    concurrency inside a suite is exactly what R7 forbids anyway.

    The verdict is NOT recomputed. Every entry reports the `green` that `evaluate_verdict` already
    decided inside `build_run_record`, verbatim, and a red entry carries that verdict's own
    `reasons`. R10 is not "one verdict function per command"; it is one verdict, full stop.

    REFUSAL HANDLING -- the decision, and why
    -----------------------------------------
    `run_exploration` raises `ExploreError` for a refusal and returns normally for a routine that
    ran and failed. Those refusals are not all the same shape, so the suite classifies them instead
    of picking one blanket policy:

      * APP-WIDE (the app is gone or unhealthy, another process holds the lock) -- ABORT. Every
        remaining routine would refuse identically, and N copies of one diagnosis buries the
        diagnosis. The remaining definitions are reported as `skipped` WITH the reason, so the
        output still says what did not run and why rather than silently shortening.
      * PER-ROUTINE (an unreadable routine file, a routine the engine's own schema rejects, a
        routine the engine refuses) -- RECORD IT AND CONTINUE. One bad routine file must not cost
        you the evidence for the other nine; that is the whole point of running a suite.

    The classifier is `_suite_blocker`, re-asked after each refusal, so the answer reflects the
    machine's state AT THAT MOMENT -- an app that dies at routine #4 aborts at #4 rather than being
    presumed healthy because it was healthy at #1.

    A refusal is counted SEPARATELY from a red run and never rendered as one (D4/QUAL-4: a tool
    problem dressed as a test result teaches people to distrust the tests). Both still make the
    suite not-green, because either way you did not get the evidence you asked for.

    When NOTHING could run -- no definitions matched, or the app is unhealthy before the first
    routine -- this raises `ExploreError` like any other refusal. There is no result to report, and
    a summary of zero runs reads like a pass.

    ENGINE REUSE: `keep_engine` is forwarded verbatim to every run. With the default (False) each
    run starts and stops its own engine, which is correct but pays the engine's startup once per
    routine; pass `keep_engine=True` and run #1 leaves the engine up for the rest to detect and
    reuse -- at the documented R2 cost that it is then yours to stop. No new engine plumbing here.
    """
    app_dir = Path(app_dir).expanduser().resolve()
    emit = on_event or (lambda _event: None)

    selected = definition_files(app_dir)
    if only:
        selected = [p for p in selected
                    if any(fnmatch.fnmatch(p.stem, pattern) or fnmatch.fnmatch(p.name, pattern)
                           for pattern in only)]
    if not selected:
        raise ExploreError(
            (f"no routine matched {only!r} in " if only else "no routines to run: ")
            + "; ".join(str(d) for d in definition_dirs(app_dir))
        )

    # Fail before starting an engine, not after. Same rules the loop uses to abort mid-way.
    blocker = _suite_blocker(repo_root, app_dir, engine_port, configured_root)
    if blocker is not None:
        raise ExploreError(blocker)

    started_at = _utc_now()
    begin = time.time()
    entries: list[dict] = []
    aborted: str | None = None
    stopped_early: str | None = None

    for index, path in enumerate(selected):
        emit({"kind": "suite", "phase": "routine", "name": path.stem,
              "index": index + 1, "of": len(selected)})
        try:
            record = run_exploration(
                repo_root, app_dir, path,
                engine_port=engine_port, configured_root=configured_root, api_key=api_key,
                driver=driver, variables=variables, credentials=credentials,
                ledger_id=ledger_id, keep_engine=keep_engine, on_event=on_event)
        except ExploreError as exc:
            entries.append({"name": path.stem, "file": str(path), "outcome": "refused",
                            "runId": None, "green": False, "status": None,
                            "durationMs": None, "reasons": [str(exc)]})
            emit({"kind": "suite", "phase": "refused", "name": path.stem, "detail": str(exc)})
            aborted = _suite_blocker(repo_root, app_dir, engine_port, configured_root)
            if aborted is not None:
                break
            continue

        verdict = record.get("verdict") or {}
        green = bool(verdict.get("green"))
        entries.append({
            "name": path.stem,
            "file": str(path),
            "outcome": "green" if green else "red",
            "runId": record.get("runId"),
            "green": green,
            "status": record.get("status"),
            "durationMs": record.get("durationMs"),
            "reasons": list(verdict.get("reasons") or []),
        })
        emit({"kind": "suite", "phase": "verdict", "name": path.stem,
              "runId": record.get("runId"), "green": green})
        if stop_on_red and not green:
            stopped_early = f"--stop-on-red: {path.stem} was red"
            break

    # Whatever the loop did not reach is REPORTED, not dropped. A suite that just gets shorter when
    # it stops early cannot be told apart from a suite that had fewer routines.
    skip_reason = aborted or stopped_early
    for path in selected[len(entries):]:
        entries.append({"name": path.stem, "file": str(path), "outcome": "skipped",
                        "runId": None, "green": False, "status": None,
                        "durationMs": None, "reasons": [skip_reason] if skip_reason else []})

    counts = {
        "total": len(entries),
        "green": sum(1 for e in entries if e["outcome"] == "green"),
        "red": sum(1 for e in entries if e["outcome"] == "red"),
        "refused": sum(1 for e in entries if e["outcome"] == "refused"),
        "skipped": sum(1 for e in entries if e["outcome"] == "skipped"),
    }
    green = counts["red"] == 0 and counts["refused"] == 0 and counts["skipped"] == 0

    # This summary is deliberately NOT persisted, so it gets no schema file: every run it names is
    # already durable at `<runId>/run.json` plus its `runs.jsonl` line, and a second stored copy of
    # facts derived from those is a thing that can disagree with them later. For the same reason the
    # per-run records keep `suite: None` -- `exploration-run.schema.json`'s `suite` property is
    # documented as platform-scoped (the editor/e2e suites, stored outside any app), and quietly
    # widening it to mean "an app-scoped `explore suite` ran this" would make the field mean two
    # things at once. `schemaVersion` here matches `list`/`preflight`/`prune`, which are versioned
    # command outputs with no schema file either.
    return {
        "schemaVersion": SUITE_SCHEMA_VERSION,
        "command": "explore suite",
        # `ok` is what the CLI turns into an exit code. Note this DIFFERS from `explore run`, which
        # reports ok=True for a red run because the caller is reading that one verdict. R3.1's
        # definition of done is that a suite exits nonzero when any routine is red -- a suite is used
        # as a gate, so its exit code has to carry the roll-up.
        "ok": green,
        "green": green,
        "appDir": str(app_dir),
        "startedAt": started_at,
        "durationMs": int((time.time() - begin) * 1000),
        "counts": counts,
        "aborted": aborted,
        "stoppedEarly": stopped_early,
        "runs": entries,
    }


def build_run_record(*, app_dir: Path, repo_root: Path, result: dict, routine: dict,
                     routine_file: Path | None, driver: str, app_record: dict,
                     started_at: str, duration_ms: int, engine_version: str | None,
                     ledger_id: str | None = None, scope: str = "app",
                     suite: str | None = None, definition_kind: str = "routine-json",
                     artifact_dir: Path | None = None) -> dict:
    """Engine result (or a Playwright reporter's equivalent) -> our run record. The three hashes are
    the heart of it: with definition.contentSha256 + target.modelSha256 + target.platform, every red
    run is attributable -- 'same routine, same model, new platform' names the culprit instead of
    starting an investigation."""
    stem = routine_file.stem if routine_file else (result.get("scenarioName") or "run")
    run_id = new_run_id(stem)

    steps = []
    for step in result.get("steps") or []:
        steps.append({
            "index": step.get("index"),
            "action": step.get("action"),
            "label": step.get("label"),
            "status": step.get("status"),
            "durationMs": int(step.get("durationMs") or 0) or None,
            "screenshot": None,
            "error": (step.get("error") or {}).get("message") if isinstance(step.get("error"), dict) else None,
        })

    evidence = dict(result.get("evidence") or {})
    stored_shots = []
    artifact_dir = artifact_dir or (runs_root(app_dir) / ".engine-artifacts")
    for shot in evidence.get("screenshots") or []:
        source = shot.get("path")
        entry = {"name": shot.get("name"), "blob": None, "sha256": None, "bytes": None}
        if source:
            resolved = resolve_artifact(str(source), artifact_dir)
            stored = store_blob(app_dir, resolved) if resolved else None
            if stored:
                entry["blob"], entry["sha256"], entry["bytes"] = stored
            else:
                # Name what was looked for. "gone" without a path is the kind of message that turns
                # a two-minute check into an investigation.
                entry["detail"] = (f"could not resolve the engine's artifact {source!r} under "
                                   f"{artifact_dir}")
        stored_shots.append(entry)
    evidence["screenshots"] = stored_shots
    # `console` and `network` are the FULL streams; the run record keeps the failure subsets plus a
    # count, so a run.json stays a few KB rather than a few hundred.
    evidence["consoleCount"] = len(evidence.pop("console", []) or [])
    evidence["networkCount"] = len(evidence.pop("network", []) or [])

    config = load_verdict_config(app_dir)
    verdict = evaluate_verdict({**result, "evidence": evidence}, config)

    definition_path = None
    if routine_file:
        try:
            definition_path = str(routine_file.relative_to(Path(app_dir)))
        except ValueError:
            definition_path = routine_file.name

    model_sha = None
    model_path = app_record.get("modelPath")
    if model_path and Path(model_path).is_file():
        try:
            model_sha = _sha256_file(Path(model_path))
        except OSError:
            model_sha = None

    record = {
        "schemaVersion": RUN_SCHEMA_VERSION,
        "runId": run_id,
        "scope": scope,
        "suite": suite,
        "definition": {
            "kind": definition_kind,
            "path": definition_path,
            "scenarioName": routine.get("scenarioName") or result.get("scenarioName"),
            "contentSha256": sha256_text(json.dumps(routine, sort_keys=True)),
        },
        "target": {
            "app": app_record.get("name"),
            "baseUrl": app_record.get("probeBaseUrl") or result.get("targetUrl"),
            "engine": app_record.get("engine"),
            "modelSha256": model_sha,
            "platform": _platform_identity(repo_root),
        },
        "driver": driver,
        "engineVersion": engine_version,
        "startedAt": started_at,
        "finishedAt": _utc_now(),
        "durationMs": int(duration_ms or 0),
        "status": result.get("status") or "error",
        "failedStepIndex": result.get("failedStepIndex"),
        "error": result.get("error"),
        "finalUrl": result.get("finalUrl"),
        "steps": steps,
        "evidence": evidence,
        "verdict": verdict,
        "extracted": result.get("extracted"),
        "baselineDiff": None,
        "pinned": False,
        "ledgerId": ledger_id,
        "notes": None,
    }
    record["baselineDiff"] = compute_baseline_diff(app_dir, Path(definition_path or stem).stem, record)
    return record


def _platform_identity(repo_root: Path) -> str | None:
    try:
        completed = subprocess.run(["git", "describe", "--tags", "--always"], cwd=repo_root,
                                   capture_output=True, text=True, timeout=5)
        return completed.stdout.strip() or None
    except Exception:
        return None


def _start_engine(root: str, port: int, origins: list[str], api_key: str, artifact_dir: str):
    Path(artifact_dir).mkdir(parents=True, exist_ok=True)
    argv = npdev_monitor.engine_start_command(root, port, origins, api_key, artifact_dir)
    if not Path(argv[0]).exists():
        raise ExploreError(
            f"the engine is installed at {root} but its launcher is missing ({argv[0]}). "
            "Run `npm install` there once."
        )
    env = dict(os.environ)
    env.update(npdev_monitor.engine_start_env(port, origins, api_key, artifact_dir))
    # Its own process group / session, so `_stop_process` can take the launcher AND the node server
    # it spawns down together. Without this the tree-kill has nothing to aim at on POSIX.
    spawn_kwargs: dict = {}
    if os.name != "nt":
        spawn_kwargs["start_new_session"] = True
    process = subprocess.Popen(argv, cwd=artifact_dir, env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **spawn_kwargs)
    endpoint = f"http://127.0.0.1:{port}"
    for _ in range(60):
        if process.poll() is not None:
            raise ExploreError(f"the engine exited immediately (code {process.returncode})")
        status, _ = npdev_monitor._http_json(f"{endpoint}/health", timeout=1.0)
        if status is not None:
            return endpoint, process
        time.sleep(0.5)
    _stop_process(process)
    raise ExploreError(f"the engine did not become ready on {endpoint} within 30s")


def _stop_process(process) -> None:
    """Kill the process TREE, not just the tracked child.

    MEASURED 2026-08-10, and it is R2 exactly: the launcher (`tsx`) spawns the real server as its
    own child, so `terminate()` on the tracked process left node still LISTENING on 3010 after the
    run finished. The next run then found a "running" engine via the service probe, reused it,
    correctly declined to stop something it had not started -- and the orphan outlived every run
    that came after it. A browser-automation server left listening on a user's machine by a tool
    that believed it had cleaned up is precisely what R2 is about.

    Same remedy the Manager already uses for `npdev dev`: a job/process-group kill rather than a
    signal to one PID."""
    try:
        pid = process.pid
    except Exception:
        return
    if os.name == "nt":
        try:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)],
                           capture_output=True, timeout=15)
        except Exception:
            pass
    else:
        try:
            os.killpg(os.getpgid(pid), signal.SIGTERM)
        except Exception:
            pass
    try:
        process.terminate()
    except Exception:
        pass
    try:
        process.wait(timeout=5)
    except Exception:
        try:
            process.kill()
        except Exception:
            pass


# ---------------------------------------------------------------------------------------------
# record -- an externally produced result, through the SAME verdict (C1, C2, R10)
# ---------------------------------------------------------------------------------------------

def record_external(repo_root: Path, app_dir: Path | None, payload: dict, *, driver: str = "harness",
                    scope: str = "app", suite: str | None = None,
                    definition_kind: str = "routine-json",
                    routine_file: Path | None = None, ledger_id: str | None = None,
                    artifact_dir: Path | None = None) -> dict:
    """The PowerShell harness and the Playwright reporter both call this rather than deciding for
    themselves. `Assert-RoutineGreen` stays for console UX; the RECORDED verdict comes from here."""
    if scope == "app":
        if app_dir is None:
            raise ExploreError("--app-dir is required for an app-scoped run record")
        app_record = npdev_monitor.probe_app(app_dir)
        target_dir = Path(app_dir).expanduser().resolve()
    else:
        app_record = {"name": suite, "probeBaseUrl": payload.get("targetUrl"), "engine": None, "modelPath": None}
        target_dir = platform_runs_root(repo_root, suite or "platform")
        (target_dir / "_ops" / "exploration-runs").mkdir(parents=True, exist_ok=True)

    # The DEFINITION is read from the file when one is named, not taken from the result: an engine
    # result carries no routine, and `definition.contentSha256` is the whole point of the attribution
    # triple -- a hash of "{}" would make every run look like it ran the same thing.
    routine: dict = {}
    if routine_file and Path(routine_file).is_file():
        try:
            routine = json.loads(Path(routine_file).read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError as exc:
            raise ExploreError(f"the routine file named is not valid JSON: {exc}") from exc
    elif isinstance(payload.get("routine"), dict):
        routine = payload["routine"]

    record = build_run_record(
        app_dir=target_dir,
        repo_root=repo_root,
        result=payload,
        routine=routine,
        routine_file=routine_file,
        driver=driver,
        app_record=app_record,
        started_at=payload.get("startedAt") or _utc_now(),
        duration_ms=payload.get("durationMs") or 0,
        engine_version=payload.get("engineVersion"),
        ledger_id=ledger_id,
        scope=scope,
        suite=suite,
        definition_kind=definition_kind,
        # Another driver's artifacts live wherever IT put them -- the harness uses
        # <build>/scrapforai-artifacts, `explore run` uses the app's own. Told, never assumed.
        artifact_dir=artifact_dir,
    )
    append_run(target_dir, record)
    return record


def platform_runs_root(repo_root: Path, suite: str) -> Path:
    """Platform-scoped runs (the editor e2e suites) belong to no app. Same schema, same screen, a
    scope filter -- EXPLORATIONS_ANALYSIS.md's scope note. Under the external Build root, never the
    repo (BUILD_OUTPUT_LOCATION_POLICY)."""
    build_root = os.environ.get("NPDEV_BUILD_ROOT")
    if build_root and build_root.strip():
        base = Path(build_root).expanduser().resolve()
    else:
        cursor = Path(repo_root).resolve()
        # npdev-build-root-resolution: by CONTENTS, never by directory name (REG-144).
        while cursor is not None and not all((cursor / n).is_dir()
                                             for n in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            cursor = cursor.parent if cursor.parent != cursor else None
        base = (cursor.parent / "Build") if cursor is not None else Path(repo_root).parent / "Build"
    root = base / "npdev-explorations" / "platform" / re.sub(r"[^A-Za-z0-9._-]+", "-", suite)
    root.mkdir(parents=True, exist_ok=True)
    return root


# ---------------------------------------------------------------------------------------------
# context -- E1, the assistant's context pack
# ---------------------------------------------------------------------------------------------

# The durable gotchas, in one place, because every one of them cost a session to learn and none of
# them is inferable from the schema.
DURABLE_GOTCHAS = [
    "Use 127.0.0.1, never localhost: Windows resolves localhost to ::1 first and generated apps bind IPv4.",
    "The business UI auto-authenticates from the manifest devKeyHint, but a deterministic routine "
    "fills #apiKey explicitly and then reloads.",
    "Concept sections are addressed as #concept-<Name> (the concept name, capitalised as in the model).",
    "Inputs are debounced -- a fill immediately followed by an assert can read the pre-debounce state; "
    "waitForSelector on the resulting row instead of sleeping.",
    "The first navigation legitimately logs a 401 (pre-auth) and, for an app with no custom theme, a "
    "theme.css 404. Both are excused by the default allowlist and RECORDED as excused.",
    "`evaluate` and `watch` are refused unless the engine runs with ALLOW_EVALUATE=true; "
    "`npdev explore run` starts it with that off.",
]


def build_context_pack(repo_root: Path, app_dir: Path, exemplars: int = 2) -> dict:
    app_dir = Path(app_dir).expanduser().resolve()
    app_record = npdev_monitor.probe_app(app_dir, include_info=True)
    info = app_record.get("info") or {}
    concepts = [row for row in (info.get("records") or []) if row.get("section") == "Concepts"]
    routes = [row for row in (info.get("records") or []) if row.get("section") in ("URLs", "Monitoring", "Flows")]

    picked = []
    for directory in definition_dirs(app_dir):
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.json"))[:exemplars]:
            routine = npdev_monitor._read_json(path)
            if routine:
                picked.append({"name": path.stem, "routine": routine})
        if picked:
            break
    if not picked:
        corpus = Path(repo_root) / "NPDevSamples" / "scripts" / "browser" / "browser-routines"
        for path in sorted(corpus.glob("*.json"))[:exemplars]:
            routine = npdev_monitor._read_json(path)
            if routine:
                picked.append({"name": path.stem, "routine": routine})

    return {
        "schemaVersion": "npdev-exploration-context.v1",
        "command": "explore context",
        "ok": True,
        "app": {
            "name": app_record.get("name"),
            "baseUrl": app_record.get("probeBaseUrl"),
            "health": app_record.get("health"),
            "engine": app_record.get("engine"),
        },
        "concepts": concepts,
        "routes": routes,
        "routineSchema": {
            "path": "schemas/ai/scrapforai-routine.schema.json",
            "authority": "the ScrapForAI engine (pinned, not written here)",
            "actions": _schema_actions(routine_schema(repo_root)),
        },
        "gotchas": DURABLE_GOTCHAS,
        "exemplars": picked,
    }


def build_repair_payload(repo_root: Path, app_dir: Path, prompt: str, *, run_id: str | None = None,
                         include_page_text: bool = False) -> dict:
    """E3-a: the EXACT bytes an assistant request would carry -- composed, and sent nowhere.

    Three layers, all required, because each one covers what the others cannot:

    1. **Structure-first by default.** The DOM excerpt is selectors, tags, attributes and the error;
       no text nodes. Most failures are selector problems, so this is usually sufficient, and it is
       the cheapest possible way to not send someone's data anywhere. `--include-page-text` is an
       explicit opt-in PER REQUEST, and which mode was used is recorded on the payload so a repair
       made from a text-included request is distinguishable later.
    2. **Redact before composing.** Credential-shaped keys and `password=`/`token=` pairs are
       replaced. This catches CREDENTIALS, not content -- which is exactly why layers 1 and 3 exist.
    3. **Show, then send.** This function returns the payload; nothing here transmits. `npdev ai
       generate-routine` sends what it is handed, so the bytes that leave are provably the bytes that
       were displayed.
    """
    app_dir = Path(app_dir).expanduser().resolve()
    context = build_context_pack(repo_root, app_dir)

    failure = None
    if run_id:
        record = read_run(app_dir, run_id)
        if record is None:
            raise ExploreError(f"no such run: {run_id}")
        evidence = record.get("evidence") or {}
        failed_index = record.get("failedStepIndex")
        steps = record.get("steps") or []
        failure = {
            "runId": run_id,
            "status": record.get("status"),
            "failedStepIndex": failed_index,
            "failedStep": steps[failed_index] if isinstance(failed_index, int) and failed_index < len(steps) else None,
            "error": record.get("error"),
            "verdict": record.get("verdict"),
            "consoleErrors": [_text_of(e)[:400] for e in (evidence.get("consoleErrors") or [])[:20]],
            "pageErrors": [_text_of(e)[:400] for e in (evidence.get("pageErrors") or [])[:20]],
            "networkFailures": (evidence.get("networkFailures") or [])[:20],
            "domExcerpt": _dom_excerpt(record, include_page_text=include_page_text),
        }
        definition_path = (record.get("definition") or {}).get("path")
        if definition_path:
            candidate = app_dir / definition_path
            if candidate.is_file():
                failure["routine"] = npdev_monitor._read_json(candidate)

    payload = {
        "schemaVersion": "npdev-assistant-payload.v1",
        "command": "explore repair-payload",
        "ok": True,
        "composedAt": _utc_now(),
        "egress": {
            "sentAnywhereByThisCommand": False,
            "mode": "structure-and-text" if include_page_text else "structure-only",
            "note": "Nothing has left this machine. `npdev ai generate-routine` sends exactly these "
                    "bytes, to the provider YOU configured, when you ask it to.",
        },
        "prompt": prompt,
        "app": context["app"],
        "concepts": context["concepts"],
        "routes": context["routes"],
        "routineSchema": context["routineSchema"],
        "gotchas": context["gotchas"],
        "exemplars": context["exemplars"],
        "failure": failure,
    }
    # Layer 2 last, over the WHOLE payload, so nothing composed above can smuggle a credential
    # through a field this function did not think about.
    return npdev_monitor.redact(payload)


def _dom_excerpt(record: dict, *, include_page_text: bool) -> dict:
    """Structure, not content. `extracted` is where a routine's `collect` steps put DOM text, so it is
    the one field that reliably carries a user's real data -- names, addresses, order lines."""
    extracted = record.get("extracted") or {}
    if include_page_text:
        return {"mode": "structure-and-text", "extracted": extracted}
    keys = sorted(extracted.keys()) if isinstance(extracted, dict) else []
    return {
        "mode": "structure-only",
        "extractedKeys": keys,
        "note": "page text withheld. The routine collected the keys above; their VALUES are this "
                "app's data and are not included unless you opt in per request.",
    }


def schema_actions(schema: dict) -> list[str]:
    """The engine's own action vocabulary, read out of the pin rather than listed by hand -- a
    hand-written list is how a corpus-induced schema loses actions.

    RESOLVES `$ref`, and that is not a detail. Zod's JSON Schema output hoists a repeated literal
    into `$defs` and refers to it, so some step variants carry `action: {const: "goto"}` inline while
    others carry `action: {$ref: "#/$defs/__schema26"}` where the const actually lives. The first
    version of this function only looked inline and reported 30 actions while the schema defines
    more -- including `fill` and `selectOption`, which the corpus USES on 846 steps. Undercounting
    here would hand the assistant (E1) a smaller vocabulary than the engine accepts: the exact
    defect A3 exists to prevent, reproduced one layer down."""
    actions: set[str] = set()

    def resolve(node):
        seen = 0
        while isinstance(node, dict) and "$ref" in node and seen < 10:
            ref = node["$ref"]
            if not ref.startswith("#/"):
                return node
            target: object = schema
            for token in ref[2:].split("/"):
                if isinstance(target, dict) and token in target:
                    target = target[token]
                else:
                    return node
            node = target
            seen += 1
        return node

    def walk(node):
        if isinstance(node, dict):
            action = resolve(node.get("action")) if "action" in node else None
            if isinstance(action, dict) and "const" in action:
                actions.add(action["const"])
            elif isinstance(action, dict) and isinstance(action.get("enum"), list):
                actions.update(str(v) for v in action["enum"])
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(schema)
    return sorted(actions)


# Kept as the private alias the module used before the $ref bug was found, so nothing that imported
# it silently starts getting the undercount back.
_schema_actions = schema_actions
