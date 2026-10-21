"""TRUTH-1: `npdev truth` -- per-concept truth level (T0..T6) joined with the evidence behind it.

Nothing here is hand-maintained. The rollup is a JOIN over files the platform already writes:

    <app>/.../npdev/box/box-manifest.json        every concept's DECLARED level (+ generated = T2 evidence)
    <app>/.../npdev/provenance-index.json        how many generated artifacts back each concept
    <app>/.../npdev-build-info.properties        when this build was made (evidence older than it is STALE)
    <reports>/behavior-probes-*.json             ENF-1 probe runs: steps carry the routes they hit -> concept T4
    <reports>/rest-smoke-*.json                  REST smoke checks: path per check -> concept T4
    <reports>/journeys-*.json, shell-scenarios-* identity journeys / golden browser runs: app-wide evidence
    <app>/_ops/exploration-runs/runs.jsonl       browser runs recorded by `npdev explore`: app-wide, and per
                                                 concept when the run visited that concept's route

The level a concept is SUPPORTED at is derived, never declared:

    T2 generated      the concept has a box in the generated manifest
    T3 runs locally   the app is serving right now (live), or any fresh passing test ran against it
    T4 tested         a FRESH, PASSING probe/smoke/browser run hit the concept's own route
    T5 evidence-backed  T4 from two or more different kinds of evidence (probe + smoke + browser)
                      (a FRESH FAILING run on the concept caps it at T3; a probe pinned to a known defect --
                      status known-failure -- is shown, never counted and never caps)
    T6 release approved  no automatic source: a release decision is a human act, never inferred

"Truth never blocks creation; it constrains release claims." This module only DISPLAYS. Missing inputs
degrade to "unknown"/absent entries; no function here raises for a missing or unreadable file. The
app-wide promotion stage (S0..S8, PromotionStateService) is a separate ladder and is carried as
evidence only -- it is never mixed into the per-concept T0..T6 level.

Stdlib-only (R9), like npdev_monitor, so it imports on a machine that has the CLI and nothing else.
The Java twin is `business-ui-box-view-controller.mustache` (GET /api/admin/box/truth); the two
implement the same rules, pinned by twin-pair rule `truth-rollup-java-python`.
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = "npdev-truth-rollup.v1"
ROLLUP_FILENAME = "truth-rollup.json"

LEVEL_CODES = ("T0", "T1", "T2", "T3", "T4", "T5", "T6")
LEVEL_LABELS = {
    "T0": "Idea", "T1": "Declared", "T2": "Generated", "T3": "RunsLocally",
    "T4": "Tested", "T5": "EvidenceBacked", "T6": "ReleaseApproved",
}
_ENUM_NAMES = {
    "T0": "T0_IDEA", "T1": "T1_DECLARED", "T2": "T2_GENERATED", "T3": "T3_RUNS_LOCALLY",
    "T4": "T4_TESTED", "T5": "T5_EVIDENCE_BACKED", "T6": "T6_RELEASE_APPROVED",
}
# Counts as support: an observation or a passing run. "failed", "known-failure", "stale", "undated" are SHOWN but
# never counted -- a red or out-of-date result must not be able to raise a level.
COUNTED_STATUSES = ("observed", "passed")
# Kinds that exercised a concept's own route -- the only ones that can lift a concept to T4/T5.
TEST_KINDS = ("probe", "smoke", "browser")

BOX_MANIFEST_RELATIVES = (
    Path("npdev-generated") / "src" / "main" / "resources" / "npdev" / "box" / "box-manifest.json",
    Path("src") / "main" / "resources" / "npdev" / "box" / "box-manifest.json",
    Path("build") / "resources" / "main" / "npdev" / "box" / "box-manifest.json",
)
PROVENANCE_RELATIVES = (
    Path("npdev-generated") / "src" / "main" / "resources" / "npdev" / "provenance-index.json",
    Path("src") / "main" / "resources" / "npdev" / "provenance-index.json",
    Path("build") / "resources" / "main" / "npdev" / "provenance-index.json",
)
BUILD_INFO_RELATIVES = (
    Path("src") / "main" / "resources" / "npdev-build-info.properties",
    Path("npdev-generated") / "src" / "main" / "resources" / "npdev-build-info.properties",
    Path("build") / "resources" / "main" / "npdev-build-info.properties",
)
# (file-name fragment, kind) -- which reports under the reports directory are evidence at all.
REPORT_FAMILIES = (
    ("behavior-probes", "probe"),
    ("rest-smoke", "smoke"),
    ("journeys", "journey"),
    ("shell-scenarios", "browser-suite"),
)
_MAX_RUN_JSON_BYTES = 2_000_000


# ---------------------------------------------------------------------------------------------
# levels
# ---------------------------------------------------------------------------------------------

def normalise_level(value) -> str | None:
    """Two data formats exist for the same fact: the box manifest's own `truthLevel` uses the enum
    name ("T2_GENERATED"), the model's `declaredTruthLevel` the code ("T2"). Both -- and the label
    ("Generated") -- map to the code. Anything else is unknown (None), never an error."""
    if value is None:
        return None
    text = str(value).strip().lower()
    if not text:
        return None
    for code in LEVEL_CODES:
        if text in (code.lower(), LEVEL_LABELS[code].lower(), _ENUM_NAMES[code].lower()):
            return code
    return None


def rank(code: str | None) -> int:
    return LEVEL_CODES.index(code) if code in LEVEL_CODES else -1


# ---------------------------------------------------------------------------------------------
# small readers (all total: a missing/unreadable file is None, never a raise)
# ---------------------------------------------------------------------------------------------

def _read_json(path: Path | None):
    if path is None:
        return None
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except Exception:  # noqa: BLE001 - evidence is best-effort; unreadable means absent
        return None


def _first_existing(root: Path, relatives) -> Path | None:
    for relative in relatives:
        candidate = root / relative
        if candidate.is_file():
            return candidate
    return None


def _read_properties(path: Path | None) -> dict:
    props: dict = {}
    if path is None:
        return props
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    except OSError:
        pass
    return props


_FRACTION = re.compile(r"(\.\d{6})\d+")


def parse_time(value) -> datetime | None:
    """ISO-8601 -> aware UTC. The PowerShell reports write 7 fractional digits; Python parses at most
    6, so the tail is trimmed. Unparseable -> None (an undated record, shown as such)."""
    if not value or not isinstance(value, str):
        return None
    text = _FRACTION.sub(r"\1", value.strip())
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        moment = datetime.fromisoformat(text)
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc)


def _iso(moment: datetime | None) -> str | None:
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ") if moment else None


def _mtime_iso(path: Path) -> str | None:
    try:
        return _iso(datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc))
    except OSError:
        return None


def sample_id_from_build_info(props: dict) -> str | None:
    """`npdev.model.sourcePath=.../NPDevSamples/<sampleId>/Input/model.json` -> `<sampleId>`."""
    source = (props.get("npdev.model.sourcePath") or "").replace("\\", "/")
    parts = [p for p in source.split("/") if p]
    if len(parts) >= 3 and parts[-1].lower() == "model.json" and parts[-2].lower() == "input":
        return parts[-3]
    return None


# ---------------------------------------------------------------------------------------------
# route -> concept
# ---------------------------------------------------------------------------------------------

def route_index(boxes: list[dict]) -> dict[str, str]:
    """`/api/<table>` and `/api/concepts/<table|ConceptName>` both name a concept; the table AND the
    concept name are accepted as the segment. Case-sensitive, exact -- a route that does not match
    exactly maps to no concept rather than to a guess."""
    index: dict[str, str] = {}
    for box in boxes:
        name = box.get("conceptName")
        if not name:
            continue
        if box.get("table"):
            index[str(box["table"])] = name
        index[str(name)] = name
    return index


def concept_for_route(path: str, index: dict[str, str]) -> str | None:
    if not isinstance(path, str):
        return None
    text = path.split("?", 1)[0].split("#", 1)[0]
    segments = [s for s in text.split("/") if s]
    if len(segments) < 2 or segments[0] != "api":
        return None
    candidate = segments[2] if segments[1] == "concepts" and len(segments) > 2 else segments[1]
    return index.get(candidate)


# ---------------------------------------------------------------------------------------------
# evidence gathering (each returns raw records; concepts[] names which concepts it touches)
# ---------------------------------------------------------------------------------------------

def _record(evidence_id, kind, level, status, summary, observed_at, source_path, sample_id,
            concepts=None, detail=None) -> dict:
    return {
        "id": evidence_id,
        "kind": kind,
        "level": level,
        "status": status,
        "summary": summary,
        "observedAt": observed_at,
        "source": {"path": str(source_path) if source_path else None, "sampleId": sample_id},
        "concepts": sorted(set(concepts or [])),
        "detail": detail or {},
    }


def _report_time(doc: dict, path: Path) -> str | None:
    """When the run FINISHED. Wrapper reports stamp `generatedAt` at the START of the script (before
    generate + build), which would always look older than the build the run itself made -- so the
    nested probe run's own stamp, then `finishedAt`, then the file's last write win over it."""
    for candidate in ((doc.get("probes") or {}).get("generatedAt") if isinstance(doc.get("probes"), dict) else None,
                      doc.get("finishedAt")):
        parsed = parse_time(candidate)
        if parsed:
            return _iso(parsed)
    return _mtime_iso(path) or _iso(parse_time(doc.get("generatedAt")))


def _probe_evidence(doc: dict, path: Path, sample_id, index: dict[str, str]) -> list[dict]:
    run = doc.get("probes") if isinstance(doc.get("probes"), dict) else doc
    results = run.get("results") if isinstance(run, dict) else None
    observed = _report_time(doc, path)
    out = []
    for result in results or []:
        if not isinstance(result, dict) or not result.get("id"):
            continue
        routes = []
        concepts = set()
        for step in result.get("steps") or []:
            hit = concept_for_route(step.get("path"), index)
            routes.append({"method": step.get("method"), "path": step.get("path"), "status": step.get("status"),
                           "concept": hit})
            if hit:
                concepts.add(hit)
        raw = result.get("status")
        # a probe pinned to a known defect (`knownFailure`) is green only while it still fails: it is a
        # red check, but not a regression -- its own status, shown and never counted, never a cap
        status = "passed" if raw == "passed" else "known-failure" if raw == "known-failure" else "failed"
        summary = f"behaviour probe {result['id']}: {raw}"
        if result.get("knownFailure"):
            summary += f" ({result['knownFailure']})"
        if not routes:
            summary += " (report predates route recording -- rerun to attribute it to concepts)"
        out.append(_record(
            f"probe:{result['id']}", "probe", "T4", status, summary, observed, path, sample_id,
            concepts, {"probe": result["id"], "file": result.get("source"), "probeStatus": raw,
                       "keywords": result.get("keywords") or [], "knownFailure": result.get("knownFailure"), "routes": routes,
                       "failures": (result.get("failures") or [])[:3]}))
    return out


def _smoke_evidence(doc: dict, path: Path, sample_id, index: dict[str, str]) -> list[dict]:
    observed = _report_time(doc, path)
    out = []
    for check in doc.get("checks") or []:
        if not isinstance(check, dict) or not check.get("id"):
            continue
        hit = concept_for_route(check.get("path"), index)
        if not hit:
            continue
        status = "passed" if check.get("status") == "passed" else "failed"
        out.append(_record(
            f"smoke:{check['id']}", "smoke", "T4", status,
            f"REST smoke {check['id']}: {check.get('status')} ({check.get('method')} {check.get('path')})",
            observed, path, sample_id, [hit],
            {"check": check["id"], "method": check.get("method"), "path": check.get("path"),
             "expectedStatus": check.get("expectedStatus"), "actualStatus": check.get("actualStatus")}))
    return out


def _suite_evidence(doc: dict, path: Path, sample_id, kind: str) -> list[dict]:
    """Journeys / golden browser suite: a whole-app verdict, so app-wide evidence (no concepts)."""
    observed = _report_time(doc, path)
    status = "passed" if doc.get("status") == "passed" else "failed"
    if kind == "journey":
        rest = doc.get("rest") if isinstance(doc.get("rest"), dict) else {}
        detail = {"restTotal": rest.get("total"), "restFailed": rest.get("failed"),
                  "stages": doc.get("stages")}
        summary = f"identity journeys: {doc.get('status')} ({rest.get('total', '?')} REST checks)"
        ident = f"journey:{doc.get('sampleId') or sample_id}"
    else:
        routines = doc.get("routines") or []
        detail = {"routines": [{"routine": r.get("routine"), "status": r.get("status")}
                               for r in routines if isinstance(r, dict)]}
        summary = f"golden browser scenarios: {doc.get('status')} ({len(routines)} routines)"
        ident = f"browser-suite:{doc.get('sampleId') or sample_id}"
    return [_record(ident, kind, "T4", status, summary, observed, path, sample_id, [], detail)]


def _exploration_evidence(ops_dir: Path | None, sample_id, boxes: list[dict]) -> list[dict]:
    if ops_dir is None:
        return []
    index_path = ops_dir / "exploration-runs" / "runs.jsonl"
    if not index_path.is_file():
        return []
    latest: dict[str, dict] = {}
    try:
        for line in index_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            definition = (row.get("definition") or {}).get("path") or (row.get("definition") or {}).get("scenarioName") \
                or row.get("runId")
            latest[str(definition)] = row  # runs are appended in time order: the last one wins
    except OSError:
        return []
    out = []
    for definition, row in sorted(latest.items()):
        run_id = row.get("runId")
        run_json = ops_dir / "exploration-runs" / str(run_id) / "run.json"
        concepts = set()
        try:
            if run_json.is_file() and run_json.stat().st_size <= _MAX_RUN_JSON_BYTES:
                text = run_json.read_text(encoding="utf-8")
                for box in boxes:
                    name, table = box.get("conceptName"), box.get("table")
                    if name and (f"#concept-{name}" in text or (table and f"/api/{table}" in text)):
                        concepts.add(name)
        except OSError:
            pass
        green = (row.get("verdict") or {}).get("green")
        out.append(_record(
            f"exploration:{run_id}", "browser", "T4", "passed" if green else "failed",
            f"browser run {Path(str(definition)).name or definition}: {'green' if green else 'not green'}",
            _iso(parse_time(row.get("startedAt"))), run_json if run_json.is_file() else index_path, sample_id,
            concepts, {"runId": run_id, "definition": definition, "driver": row.get("driver"),
                       "stepCount": row.get("stepCount")}))
    return out


def gather_external_evidence(reports_dir: Path | None, ops_dir: Path | None, sample_id: str | None,
                             boxes: list[dict]) -> list[dict]:
    """Newest matching report per family for this app's sample, plus the app's own exploration runs."""
    index = route_index(boxes)
    evidence: list[dict] = []
    if reports_dir is not None and Path(reports_dir).is_dir() and sample_id:
        for fragment, kind in REPORT_FAMILIES:
            candidates = []
            for path in sorted(Path(reports_dir).glob("*.json")):
                if fragment not in path.name.lower() or path.name.lower().endswith(".plan-validation.json"):
                    continue
                doc = _read_json(path)
                if not isinstance(doc, dict):
                    continue
                report_sample = doc.get("sampleId") or doc.get("scenarioId")
                if report_sample != sample_id:
                    continue
                candidates.append((_report_time(doc, path) or "", path, doc))
            if not candidates:
                continue
            _, path, doc = max(candidates, key=lambda c: (c[0], str(c[1])))
            if kind == "probe":
                evidence += _probe_evidence(doc, path, sample_id, index)
            elif kind == "smoke":
                evidence += _smoke_evidence(doc, path, sample_id, index)
            else:
                evidence += _suite_evidence(doc, path, sample_id, kind)
    evidence += _exploration_evidence(ops_dir, sample_id, boxes)
    return evidence


# ---------------------------------------------------------------------------------------------
# judging + rollup
# ---------------------------------------------------------------------------------------------

def judge(record: dict, built_at: datetime | None) -> dict:
    """Freshness is decided HERE and again by the Java twin, against the build the app is running:
    a result observed before this build was made says nothing about it. `observed` evidence (the live
    request itself) is exempt -- it is the build answering right now."""
    out = dict(record)
    if out["status"] == "observed":
        return out
    observed = parse_time(out.get("observedAt"))
    original = out["status"]
    if observed is None:
        out["status"] = "undated"
        out["observedStatus"] = original
        out["staleReason"] = "the evidence carries no usable timestamp, so it cannot be tied to this build"
    elif built_at is not None and observed < built_at:
        out["status"] = "stale"
        out["observedStatus"] = original
        out["staleReason"] = f"observed {_iso(observed)}, before this build was made ({_iso(built_at)})"
    return out


def supported_level(evidence: list[dict], live: bool) -> str | None:
    """See the module docstring for the ladder. T6 is never produced here. A FRESH failing test (status
    failed) on the concept caps it at T3: a red run contradicts "tested". A known-failure probe is not a
    regression and caps nothing."""
    best = -1
    counted = [e for e in evidence if e["status"] in COUNTED_STATUSES]
    if any(e["kind"] == "generated" for e in counted):
        best = 2
    if live:
        best = max(best, 3)
    kinds = {e["kind"] for e in counted if e["kind"] in TEST_KINDS}
    if kinds:
        best = max(best, 4)
        if len(kinds) >= 2:
            best = max(best, 5)
    if best > 3 and any(e["status"] == "failed" and e["kind"] in TEST_KINDS for e in evidence):
        best = 3
    return LEVEL_CODES[best] if best >= 0 else None


def _claim(declared: str | None, supported: str | None) -> str:
    if declared is None or supported is None:
        return "unknown"
    if rank(declared) > rank(supported):
        return "overclaim"
    if rank(declared) < rank(supported):
        return "underclaim"
    return "ok"


def _lowest(levels: list[str | None]) -> str | None:
    known = [lvl for lvl in levels if lvl is not None]
    return min(known, key=rank) if known else None


def build_rollup(final_app_root, *, ops_dir=None, reports_dir=None, live: bool = False,
                 sample_id: str | None = None, built_at: str | None = None) -> dict:
    """The whole join. `built_at` overrides the build-info timestamp (the jar's mtime is the
    fallback the caller may pass when the app predates build-info). Never raises for missing inputs."""
    root = Path(final_app_root)
    manifest_path = _first_existing(root, BOX_MANIFEST_RELATIVES)
    provenance_path = _first_existing(root, PROVENANCE_RELATIVES)
    build_info_path = _first_existing(root, BUILD_INFO_RELATIVES)
    manifest = _read_json(manifest_path) or {}
    provenance = (_read_json(provenance_path) or {}).get("specNodes") or {}
    props = _read_properties(build_info_path)
    built = parse_time(built_at) or parse_time(props.get("npdev.builtAt"))
    sample = sample_id or sample_id_from_build_info(props)
    boxes = [b for b in (manifest.get("boxes") or []) if isinstance(b, dict) and b.get("conceptName")]
    external = [judge(e, built) for e in gather_external_evidence(
        Path(reports_dir) if reports_dir else None, Path(ops_dir) if ops_dir else None, sample, boxes)]

    concepts = []
    for box in sorted(boxes, key=lambda b: str(b["conceptName"]).lower()):
        name = box["conceptName"]
        artifacts = (provenance.get(name) or {}).get("artifacts") or []
        evidence = [_record(
            f"generated:{name}", "generated", "T2", "observed",
            (f"generated: {len(artifacts)} artifact(s) in the provenance index" if artifacts
             else "generated: present in the box manifest (no provenance artifacts listed)"),
            None, manifest_path, sample, [name],
            {"artifacts": len(artifacts), "table": box.get("table"),
             "manifestLevel": normalise_level(box.get("truthLevel"))})]
        if live:
            evidence.append(_record(
                f"runtime:{name}", "runtime", "T3", "observed",
                "runs locally: the app is serving requests right now", None, None, sample, [name],
                {"how": "the app is up and answering (health endpoint / the request this rollup came from)"}))
        evidence += [dict(e) for e in external if name in e.get("concepts", [])]
        declared = normalise_level(box.get("declaredTruthLevel"))
        supported = supported_level(evidence, live)
        concepts.append({
            "conceptName": name,
            "displayName": box.get("displayName") or name,
            "table": box.get("table"),
            "module": box.get("module"),
            "declaredLevel": declared,
            "supportedLevel": supported,
            "claim": _claim(declared, supported),
            "evidence": evidence,
        })

    histogram = {code: 0 for code in LEVEL_CODES}
    for concept in concepts:
        if concept["supportedLevel"]:
            histogram[concept["supportedLevel"]] += 1
    lowest_supported = _lowest([c["supportedLevel"] for c in concepts])
    lowest_declared = _lowest([c["declaredLevel"] for c in concepts])
    unique_external = {e["id"]: e for e in external}
    app_wide = [e for e in external if not e.get("concepts")]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "app": {
            "namespace": (manifest.get("application") or {}).get("name"),
            "sampleId": sample,
            "builtAt": _iso(built),
            "live": bool(live),
            "promotion": None,
            "sources": {
                "boxManifest": str(manifest_path) if manifest_path else None,
                "provenanceIndex": str(provenance_path) if provenance_path else None,
                "buildInfo": str(build_info_path) if build_info_path else None,
                "reportsDir": str(reports_dir) if reports_dir else None,
            },
        },
        "summary": {
            "conceptCount": len(concepts),
            "lowestSupported": lowest_supported,
            "lowestDeclared": lowest_declared,
            "lowestSupportedConcepts": sorted(c["conceptName"] for c in concepts
                                              if lowest_supported and c["supportedLevel"] == lowest_supported),
            "supportedHistogram": histogram,
            "overclaims": sum(1 for c in concepts if c["claim"] == "overclaim"),
            "staleEvidence": sum(1 for e in unique_external.values() if e["status"] == "stale"),
            "failedEvidence": sum(1 for e in unique_external.values() if e["status"] == "failed"),
            "knownFailureEvidence": sum(1 for e in unique_external.values() if e["status"] == "known-failure"),
            "testedConcepts": sum(1 for c in concepts if rank(c["supportedLevel"]) >= 4),
        },
        "concepts": concepts,
        "appEvidence": app_wide,
        "note": ("Declared levels are the model's claim; supported levels are derived from evidence. "
                 "T6 (ReleaseApproved) is never derived. Display only -- nothing is blocked by this."),
    }


def summarize(rollup: dict, rollup_path: str | None = None) -> dict:
    """The compact form the Monitor card carries -- enough for a badge and a link, no evidence bodies."""
    s = rollup.get("summary") or {}
    return {
        "lowestSupported": s.get("lowestSupported"),
        "lowestDeclared": s.get("lowestDeclared"),
        "conceptCount": s.get("conceptCount", 0),
        "supportedHistogram": s.get("supportedHistogram"),
        "overclaims": s.get("overclaims", 0),
        "staleEvidence": s.get("staleEvidence", 0),
        "failedEvidence": s.get("failedEvidence", 0),
        "knownFailureEvidence": s.get("knownFailureEvidence", 0),
        "testedConcepts": s.get("testedConcepts", 0),
        "live": bool((rollup.get("app") or {}).get("live")),
        "rollupPath": rollup_path,
    }


def unknown_summary(reason: str) -> dict:
    """What a card shows when no rollup could be built: an honest 'unknown', never an error."""
    return {"lowestSupported": None, "lowestDeclared": None, "conceptCount": 0, "supportedHistogram": None,
            "overclaims": 0, "staleEvidence": 0, "failedEvidence": 0, "knownFailureEvidence": 0,
            "testedConcepts": 0, "live": False, "rollupPath": None, "unknownReason": reason}


# ---------------------------------------------------------------------------------------------
# rendering + CLI entry
# ---------------------------------------------------------------------------------------------

def render_text(rollup: dict) -> str:
    app, summary = rollup["app"], rollup["summary"]
    lines = [
        f"Truth rollup for {app.get('namespace') or '(unknown app)'}"
        f"  live={app.get('live')}  built={app.get('builtAt') or 'unknown'}",
        f"  concepts: {summary['conceptCount']}   lowest supported: {summary['lowestSupported'] or 'unknown'}"
        f"   lowest declared: {summary['lowestDeclared'] or 'unknown'}"
        f"   overclaims: {summary['overclaims']}   stale evidence: {summary['staleEvidence']}"
        f"   failed evidence: {summary['failedEvidence']}   known failures: {summary['knownFailureEvidence']}",
        "",
        f"  {'concept':<44} {'declared':<9} {'supported':<10} {'claim':<10} evidence",
    ]
    for c in rollup["concepts"]:
        tests = [e for e in c["evidence"] if e["kind"] not in ("generated", "runtime")]
        stale = sum(1 for e in tests if e["status"] == "stale")
        failed = sum(1 for e in tests if e["status"] == "failed")
        known = sum(1 for e in tests if e["status"] == "known-failure")
        note = (f"{len(tests)} test run(s)" + (f", {stale} stale" if stale else "") + (f", {failed} failed" if failed else "")
                + (f", {known} known failure(s)" if known else ""))
        lines.append(f"  {c['conceptName']:<44} {c['declaredLevel'] or '?':<9} {c['supportedLevel'] or '?':<10} "
                     f"{c['claim']:<10} {note}")
    return "\n".join(lines)


def default_reports_dir(repo_root: Path) -> Path:
    """The gitignored in-repo folder every run-*.ps1 report lands in, derived from the repo root the
    CLI itself resolved (by contents, REG-144) -- never a literal drive path."""
    return Path(repo_root).resolve() / "scripts" / "reports" / "out"


def rollup_for_app(app_dir, repo_root, *, reports_dir=None, live: bool | None = None,
                   sample_id: str | None = None, probe_record: dict | None = None) -> tuple[dict | None, str, dict]:
    """Resolve the app via the Monitor's own discovery and build its rollup. Returns (rollup, reason,
    probe record); rollup is None with a reason when the directory is not a generated app."""
    import npdev_monitor  # local import: npdev_monitor imports this module at load time

    record = probe_record or npdev_monitor.probe_app(Path(app_dir), origin="explicit", health_timeout=1.5)
    if record.get("status") != "ok":
        return None, record.get("detail") or "not a generated NPDev app", record
    final_root = record.get("finalAppRoot") or record.get("appDir")
    ops_dir = record.get("opsDir")
    if live is None:
        live = record.get("health") == "running"
    rollup = build_rollup(
        final_root, ops_dir=ops_dir,
        reports_dir=reports_dir if reports_dir is not None else default_reports_dir(repo_root),
        live=live, sample_id=sample_id,
        built_at=None if _first_existing(Path(final_root), BUILD_INFO_RELATIVES) else record.get("builtAt"))
    if not rollup["concepts"]:
        return rollup, "no box manifest found under the app (not generated yet?)", record
    return rollup, "", record


def run(args, repo_root: Path) -> int:
    """`npdev truth --app <dir> [--json] [--write] [--out PATH] [--reports-dir DIR] [--sample-id ID]`."""
    rollup, reason, record = rollup_for_app(
        args.app, repo_root,
        reports_dir=Path(args.reports_dir) if getattr(args, "reports_dir", None) else None,
        sample_id=getattr(args, "sample_id", None))
    if rollup is None:
        print(json.dumps({"ok": False, "error": reason}, indent=2))
        return 2
    written = None
    out_path = getattr(args, "out", None)
    if getattr(args, "write", False) and not out_path:
        out_path = str(Path(record.get("opsDir") or (Path(args.app).expanduser().resolve() / "_ops")) / ROLLUP_FILENAME)
    if out_path:
        target = Path(out_path).expanduser()
        target.parent.mkdir(parents=True, exist_ok=True)
        # bytes, not write_text: write_text turns "\n" into CRLF on Windows (feedback_pathlib_write_text_crlf)
        target.write_bytes((json.dumps(rollup, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
        written = str(target)
    if getattr(args, "json", False):
        print(json.dumps(rollup, indent=2, ensure_ascii=False))
    else:
        print(render_text(rollup))
        if written:
            print(f"\nwrote {written}")
        if reason:
            print(f"\nnote: {reason}")
    return 0
