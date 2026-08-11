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
    plan = npdev_monitor._read_json(app_root / "_ops" / "resolved-db-plan.json") or {}
    definition_root = npdev_monitor._resolve_app_relative(app_root, plan.get("appDefinitionRoot"))
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

def compose_engine_request(routine: dict, base_url: str, variables: dict | None = None) -> dict:
    """Exactly what `Invoke-ScrapRoutine` builds: drop NPDev's private `targetPath`, inject an
    absolute `targetUrl`, merge runtime variable overrides over the routine's declared ones.

    A routine FILE is therefore never validated directly against the pinned schema -- the composed
    REQUEST is. Validating the file would report a missing `targetUrl` on every correct routine in
    the corpus."""
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
    "outliving the reason it was written for, structurally QUAL-4's continue-on-error."
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


def _default_excuse(kind: str, entry: object, config: dict, index: int) -> str | None:
    """Returns the RULE NAME that excuses this entry, or None. Conditional by construction."""
    text = _text_of(entry)
    lowered = text.lower()
    if kind == "consoleError":
        if "theme.css" in lowered and not config.get("hasCustomTheme"):
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
                if needle and needle in text:
                    rule = f"app:{needle}"
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
        self.path = runs_root(app_dir) / ".run.lock"
        self.stale_after = stale_after_seconds
        self.acquired = False

    def __enter__(self) -> "RunLock":
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            age = time.time() - self.path.stat().st_mtime
            if age < self.stale_after:
                holder = self.path.read_text(encoding="utf-8", errors="replace").strip()
                raise ExploreError(
                    f"another exploration is already running for this app "
                    f"(lock held {int(age)}s by {holder}). Stop it first, or wait."
                )
            self.path.unlink(missing_ok=True)
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

def list_explorations(app_dir: Path, limit: int = 100) -> dict:
    app_dir = Path(app_dir).expanduser().resolve()
    definitions = []
    seen: set[str] = set()
    for directory in definition_dirs(app_dir):
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.json")):
            if path.name in seen:
                continue
            seen.add(path.name)
            routine = npdev_monitor._read_json(path) or {}
            definitions.append({
                "name": path.stem,
                "file": str(path),
                "source": "mirror" if directory == mirror_dir(app_dir) else "app-definition",
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
    blocking = [c for c in pre["checks"] if c["status"] == "fail" and c["id"] != "engine-available"]
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
            request = compose_engine_request(routine, base_url, variables)
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
                    routine_file: Path | None = None, ledger_id: str | None = None) -> dict:
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

    routine = payload.get("routine") or {}
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
