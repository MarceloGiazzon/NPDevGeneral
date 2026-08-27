#!/usr/bin/env python3
"""`npdev probe` -- an occasional runtime execution-reach diagnostic for a generated app.

VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27, Phase P1. Boots a generated app with the JaCoCo Java
agent attached (via JAVA_TOOL_OPTIONS -- the JVM reads it automatically, so NO change to anything
NPDev emits is needed), lets a human or a browser routine drive it, then reports which code was
actually EXECUTED at runtime -- as opposed to covered by tests.

THE METRIC IS "EXECUTION REACH", NEVER "COVERAGE" (S6.4). scripts/policy/coverage-baseline.json has
been mis-written nine times by things that looked like coverage numbers; a differently named metric
in a differently named schema cannot be accidentally compared to a floor or fed to
check-coverage-ratchet.py. Nothing under scripts/policy/ is ever touched by this module.

SHAPE: the report is npdev-execution-reach-report.v1, emitted under
<workspace>__OutsideRepo/probe-runs/<runId>/ -- NOT inside the app, because regeneration wipes app
directories that are not data/logs/secrets. Tooling (jacocoagent.jar, jacococli.jar) is staged
under <workspace>__OutsideRepo/java-tools/jacoco/, mirroring Invoke-JsonSchemaValidation.ps1's
node-tools staging pattern.

P1 scope (this file): start/stop/dump + raw reach report. The 2x2 intersection with a test report
(--baseline, P2) and the one-shot `run` over the routine corpus (P3) are deliberately NOT here yet;
`run` is wired as start->drive-pause->stop so the shape exists.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

REPORT_SCHEMA = "npdev-execution-reach-report.v1"
AGENT_VERSION = "0.8.9"
DEFAULT_AGENT_PORT = 6300
STAGED_AGENT_NAME = f"jacocoagent-{AGENT_VERSION}.jar"
STAGED_CLI_NAME = f"jacococli-{AGENT_VERSION}-nodeps.jar"

REPO_ROOT = Path(__file__).resolve().parents[1]


class ProbeError(Exception):
    pass


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def outside_repo_root() -> Path:
    """The `<workspace>__OutsideRepo` evidence root -- sibling of this repo, never inside it
    (workspace-slimness + never into a generated app)."""
    return REPO_ROOT.parent / (REPO_ROOT.name + "__OutsideRepo")


def tools_dir() -> Path:
    return outside_repo_root() / "java-tools" / "jacoco"


def probe_runs_dir() -> Path:
    return outside_repo_root() / "probe-runs"


def _active_state_path() -> Path:
    return probe_runs_dir() / "active.json"


# ---------------------------------------------------------------------------------------------
# Tool staging (kept OUT of the repo -- See Invoke-JsonSchemaValidation.ps1's node-tools pattern)
# ---------------------------------------------------------------------------------------------

def _candidate_cache_roots() -> list[Path]:
    env = os.environ.get("GRADLE_USER_HOME")
    roots = [Path(env).expanduser()] if env else []
    roots += [Path.home() / ".gradle"]
    build = REPO_ROOT.parent / "Build"
    roots += [build / "gradle-home", build / "gradle-user-home", build / "gradle-cache"]
    seen: set[Path] = set()
    return [r for r in roots if r not in seen and not seen.add(r) and r.is_dir()]


def find_agent_dist_jar() -> Path | None:
    """The org.jacoco.agent DIST jar (jacocoagent.jar lives INSIDE it) already in a local Gradle
    cache -- never downloaded, never guessed. Confirmed present on this machine 2026-08-27 (S6.1
    fact 3)."""
    needle = f"org.jacoco.agent-{AGENT_VERSION}.jar"
    for root in _candidate_cache_roots():
        candidates = sorted(
            (root / "caches" / "modules-2" / "files-2.1" / "org.jacoco" / "org.jacoco.agent").glob(
                "*/**/*.jar")) if (root / "caches" / "modules-2" / "files-2.1" / "org.jacoco" /
                                   "org.jacoco.agent").exists() else []
        for jar in candidates:
            if jar.name == needle:
                return jar
    return None


def ensure_jacocoagent() -> Path:
    """Extract jacocoagent.jar from the dist jar into the staged tools dir. The agent jar is a
    *dist* jar -- the actual agent is jacocoagent.jar inside it (S6.1 fact 3)."""
    dist = find_agent_dist_jar()
    if dist is None:
        raise ProbeError(
            f"org.jacoco.agent-{AGENT_VERSION}.jar not found in any Gradle cache searched "
            f"({[str(r) for r in _candidate_cache_roots()]}). Run `npdev doctor` once or point "
            "GRADLE_USER_HOME at your cache; the probe never downloads the agent."
        )
    target = tools_dir() / STAGED_AGENT_NAME
    if target.exists():
        return target
    tools_dir().mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(dist) as zf:
            data = zf.read("jacocoagent.jar")
    except (KeyError, zipfile.BadZipFile) as exc:
        raise ProbeError(f"{dist.name} is not a valid agent dist jar: {exc}") from exc
    target.write_bytes(data)
    return target


def ensure_jacococli() -> Path:
    """Stage the jacococli runnable jar (org.jacoco.cli + deps, Maven Central). The CLI is NOT in
    any local cache (verified 2026-08-27, S6.1 'not yet verified, do this first' -- this is that
    first fetch, and it is the ONE network fetch the whole probe needs)."""
    target = tools_dir() / STAGED_CLI_NAME
    if target.exists() and target.stat().st_size > 0:
        return target
    tools_dir().mkdir(parents=True, exist_ok=True)
    url = (f"https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/{AGENT_VERSION}/"
           f"org.jacoco.cli-{AGENT_VERSION}-nodeps.jar")
    print(f"npdev probe: staging jacococli from Maven Central (one-time) -> {target}", file=sys.stderr)
    try:
        urllib.request.urlretrieve(url, target)
    except Exception as exc:
        raise ProbeError(f"could not fetch {url}: {exc}") from exc
    if target.stat().st_size == 0:
        raise ProbeError(f"downloaded {target.name} is empty -- delete it and retry")
    return target


# ---------------------------------------------------------------------------------------------
# App boot (S6.1 fact 1: the generated runnable is a bare `java -jar`, and the JVM reads
# JAVA_TOOL_OPTIONS from the environment, so the agent needs NO change to anything NPDev emits)
# ---------------------------------------------------------------------------------------------

def _read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception:
        return {}


def _resolve_final_app(app_dir: Path) -> tuple[Path, int]:
    """(finalAppRoot, port). Identified by CONTENTS, never by name (monitor's REG-144 discipline):
    the plan names finalAppPath/appRoot; given an App module directory, its parent hosts _ops."""
    app_dir = app_dir.expanduser().resolve()
    plan = _read_json(app_dir / "_ops" / "resolved-db-plan.json")
    port = int(plan.get("serverPort") or 0) or 8080
    declared = plan.get("finalAppPath") or plan.get("appRoot")
    if isinstance(declared, str) and declared.strip():
        candidate = Path(declared.strip()).expanduser()
        if candidate.is_dir():
            final_app = candidate.resolve()
        else:
            final_app = app_dir
    elif (app_dir / "App" / "build" / "libs").is_dir():
        # Given the finalapp root (which hosts _ops AND the App module) -- pick the module.
        final_app = app_dir / "App"
    else:
        # Given the App module dir itself.
        final_app = app_dir
    if not (final_app / "build" / "libs").is_dir():
        raise ProbeError(
            f"no build/libs under {final_app} -- build the app first "
            "(the probe instruments the RUNNING app, so a build must exist)."
        )
    return final_app, port


def _newest_runnable_jar(final_app: Path) -> Path:
    libs = final_app / "build" / "libs"
    jars = [p for p in libs.glob("FinalExec-*.jar")
            if "plain" not in p.name and p.is_file()]
    if not jars:
        raise ProbeError(f"no runnable FinalExec-*.jar under {libs}")
    return sorted(jars, key=lambda p: p.stat().st_mtime, reverse=True)[0]


def _runtimehost_libs() -> list[Path]:
    """The prebuilt runtimehost MODULE jars the generated app consumes (runtimehost-core-0.1.0.jar,
    kernel-0.1.0.jar, the adapters, etc.) under the shared build root. These carry classes that
    exist ONLY in staged jars -- the whole point of the probe is that build-time test coverage
    cannot see them (S6.1 fact 2), so the report MUST attribute lines to them explicitly.

    Bare third-party distribution jars next to them (h2-2.3.232.jar, ...) are deliberately NOT
    included: h2 2.3.x ships as a multi-release jar (META-INF/versions/21/org/h2/...), and an
    analysis pass over it makes JaCoCo 0.8.9's CoverageBuilder refuse 'can't add different class
    with same name' (hit live on 2026-08-27 with org/h2/util/Utils21). The module jars are the
    load-bearing attribution surface."""
    env = os.environ.get("NPDEV_BUILD_ROOT")
    roots = [Path(env).expanduser()] if env else []
    roots += [REPO_ROOT.parent / "Build"]
    for root in roots:
        libs = root / "runtimehost-libs"
        if not libs.is_dir():
            continue
        jars = sorted(
            p for p in libs.glob("*.jar")
            if p.name.endswith("-0.1.0.jar") and "-sources" not in p.name and p.is_file())
        if jars:
            return jars
    return []


def new_run_id() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]


def _write_active(state: dict) -> None:
    probe_runs_dir().mkdir(parents=True, exist_ok=True)
    _active_state_path().write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")


def _read_active() -> dict:
    if not _active_state_path().exists():
        raise ProbeError("no active probe session -- run `npdev probe start --app <dir>` first")
    return _read_json(_active_state_path())


# ---------------------------------------------------------------------------------------------
# The verbs
# ---------------------------------------------------------------------------------------------

def run_probe_start(app_dir: str, agent_port: int, wait_seconds: float, json_out: bool) -> int:
    agent = ensure_jacocoagent()
    final_app, port = _resolve_final_app(Path(app_dir))
    jar = _newest_runnable_jar(final_app)
    run_id = new_run_id()
    run_dir = probe_runs_dir() / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin" / "java.exe" if java_home and Path(java_home).exists() else "java"
    # output=tcpserver, NOT output=file: file mode only flushes on clean JVM exit, and a probe
    # session is precisely where the app gets killed or times out (S6.2). TCP mode is also what
    # makes `dump` work at all.
    agent_opts = (f"-javaagent:{agent}=output=tcpserver,address=localhost,"
                  f"port={agent_port}")
    env = dict(os.environ)
    prior = env.get("JAVA_TOOL_OPTIONS", "")
    env["JAVA_TOOL_OPTIONS"] = (prior + " " + agent_opts).strip()
    cmd = [str(java), "-jar", str(jar), f"--server.port={port}"]
    process = subprocess.Popen(
        cmd, cwd=final_app, env=env,
        stdout=(subprocess.DEVNULL if json_out else None),
        stderr=subprocess.DEVNULL if json_out else None,
        creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
    )
    state = {
        "schemaVersion": "npdev-probe-session.v1",
        "runId": run_id,
        "appDir": str(final_app),
        "jarPath": str(jar),
        "agentPort": agent_port,
        "serverPort": port,
        "agentJar": str(agent),
        "execPath": str(run_dir / "session.exec"),
        "pid": process.pid,
        "startedAt": _utc_now(),
    }
    _write_active(state)
    if json_out:
        print(json.dumps(state, indent=2, ensure_ascii=False))
    else:
        print(f"probe {run_id}: booting {final_app.name} with JaCoCo agent on port {agent_port} "
              f"(jar {jar.name}, server port {port})")
        print(f"probe {run_id}: DRIVE IT NOW -- exercise the app, then `npdev probe dump` for a "
              "mid-session snapshot or `npdev probe stop` to finish.")
    if wait_seconds:
        time.sleep(wait_seconds)
    return 0


def run_probe_dump(*, json_out: bool = False) -> int:
    state = _read_active()
    cli = ensure_jacococli()
    dump_to = state.get("execPath") or str(Path(state.get("runId", "dump")) / "dump.exec")
    result = _jacococli_dump(cli, int(state["agentPort"]), dump_to)
    if json_out:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        print(f"probe {state['runId']}: dumped execution data ({result.get('execBytes', 0)} bytes) "
              "to session.exec -- app is still running.")
    return 0


def run_probe_stop(baseline: str | None, json_out: bool) -> int:
    state = _read_active()
    pid = int(state["pid"])
    # 1. One final pull from the still-running agent (cleanest exec data).
    cli = ensure_jacococli()
    exec_path = state["execPath"]
    try:
        _jacococli_dump(cli, int(state["agentPort"]), exec_path)
    except ProbeError as exc:
        print(f"npdev probe: final dump failed ({exc}) -- the app may already be down; "
              "continuing with whatever the agent flushed.", file=sys.stderr)
    if not Path(exec_path).exists() or Path(exec_path).stat().st_size == 0:
        raise ProbeError(
            "no execution data captured for this session (the agent never got a dump and the app "
            "is already down). Re-run `npdev probe start`, exercise the app, then `probe stop`."
        )
    # 2. Stop the app (the probe owns the process it started; taskkill takes the whole tree).
    _terminate(pid)
    # 3. The reach report.
    report = build_reach_report(
        exec_path=state["execPath"],
        run_id=state["runId"],
        app_dir=state["appDir"],
        test_report_xml=baseline,
    )
    out = Path(report["reportPath"])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    _active_state_path().unlink(missing_ok=True)
    if json_out:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print(_reach_human_table(report))
    return 0


def run_probe_run(app_dir: str, duration_seconds: float, agent_port: int, json_out: bool) -> int:
    """P3-shaped one-shot: start, let the caller's routine drive for N seconds, stop. The routine
    corpus driver itself is Phase P3 (optional); this keeps the verb's shape real."""
    run_probe_start(app_dir, agent_port, wait_seconds=duration_seconds, json_out=False)
    state = _read_active()
    print(f"probe {state['runId']}: expiring one-shot after {duration_seconds:g}s -- stopping.", file=sys.stderr)
    return run_probe_stop(baseline=None, json_out=json_out)


def _jacococli_dump(cli: Path, agent_port: int, exec_path: str) -> dict:
    """Pull execution data from the RUNNING agent (tcpserver mode) without stopping the app."""
    completed = subprocess.run(
        ["java", "-jar", str(cli), "dump", "--address", "localhost",
         "--port", str(agent_port), "--destfile", str(exec_path), "--quiet"],
        capture_output=True, text=True, timeout=60,
    )
    if completed.returncode != 0:
        raise ProbeError("jacococli dump failed: " + (completed.stderr.strip() or completed.stdout.strip()))
    size = Path(exec_path).stat().st_size if Path(exec_path).exists() else 0
    return {"execPath": exec_path, "execBytes": size, "appStillRunning": True}


def _terminate(pid: int) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       capture_output=True, text=True, check=False)
    else:
        subprocess.run(["kill", "-9", str(pid)], check=False)


# ---------------------------------------------------------------------------------------------
# The reach report -- renamed metric on purpose (S6.4); see the module docstring.
# ---------------------------------------------------------------------------------------------

def build_reach_report(exec_path: str, run_id: str, app_dir: str,
                       test_report_xml: str | None) -> dict:
    """Run jacococli report over the exec file + the staged runtimehost jars, then build the
    npdev-execution-reach-report.v1 document. `test_report_xml` is the P2 hook: the 2x2
    reached&tested intersection is computed when it is supplied; until then the report leads with
    the raw executed/not-executed split.

    classfiles = the staged runtimehost libs ONLY (the app's own runnable FinalExec jar is a
    Spring Boot FAT jar whose BOOT-INF/lib nests copies of its dependencies, and feeding it whole
    to jacococli makes CoverageBuilder refuse duplicate class names -- hit live on 2026-08-27 with
    org/h2/util/Bits). The staged jars are plain, non-nested, and carry the classes that exist
    ONLY in prebuilt jars -- SchemaVerifyMain is the witness the P1 acceptance names -- so they
    are also exactly what the probe must attribute to prove the load-bearing claim.
    """
    cli = ensure_jacococli()
    xml_out = str(Path(exec_path).with_suffix(".xml"))
    classfiles = [str(p) for p in _runtimehost_libs()]
    if not classfiles:
        raise ProbeError("no staged runtimehost jars found to attribute the exec file against -- "
                         "expected <build root>/runtimehost-libs/*.jar (excluding -sources)")
    report_args = ["java", "-jar", str(cli), "report", str(exec_path), "--xml", xml_out, "--quiet"]
    for cf in classfiles:
        report_args += ["--classfiles", cf]
    completed = subprocess.run(report_args, capture_output=True, text=True, timeout=180)
    if completed.returncode != 0:
        raise ProbeError("jacococli report failed: " + (completed.stderr.strip() or completed.stdout.strip()))

    classes = _parse_jacoco_xml(xml_out)
    # The raw jacococli XML is an intermediate: it speaks jacoco's own covered/missed vocabulary,
    # which must not leak into probe output (S6.4 acceptance: grep for 'coverage' over the probe's
    # own files returns nothing). The reach report below carries everything the XML contributed.
    Path(xml_out).unlink(missing_ok=True)
    baseline = None
    intersection = None
    if test_report_xml:
        baseline = _parse_test_baseline(test_report_xml)
        intersection = _intersect_reach_and_tests(classes, baseline)

    totals = {
        "reachedLines": sum(c["reachedLines"] for c in classes),
        "unreachedLines": sum(c["unreachedLines"] for c in classes),
        "reachedInstructions": sum(c["reachedInstructions"] for c in classes),
        "unreachedInstructions": sum(c["unreachedInstructions"] for c in classes),
    }
    return {
        "schemaVersion": REPORT_SCHEMA,
        "probeRunId": run_id,
        "generatedAt": _utc_now(),
        "appDir": app_dir,
        "classFiles": classfiles,
        "execPath": exec_path,
        "reportPath": str(Path(exec_path).with_suffix(".reach.json")),
        "metric": "execution-reach",   # NEVER "coverage" -- see S6.4 and the module docstring
        "intersectionWithTestReport": baseline,
        "intersection": intersection,
        "totals": totals,
        "classes": classes,
    }


def _parse_jacoco_xml(xml_path: str) -> list[dict]:
    """Per-class LINE/INSTRUCTION counters from jacococli's --xml report. `reached` = executed at
    runtime (Jacoco's 'covered' in the exec-file sense); naming stays reach-flavoured."""
    tree = ElementTree.parse(xml_path)
    classes = []
    for cls in tree.getroot().iter("class"):
        counters = {c.get("type"): c for c in cls.findall("counter")}
        line = counters.get("LINE")
        instr = counters.get("INSTRUCTION")
        name = (cls.get("name") or "").replace("/", ".")
        classes.append({
            "name": name,
            "reachedLines": int(line.get("covered") or 0) if line is not None else 0,
            "unreachedLines": int(line.get("missed") or 0) if line is not None else 0,
            "reachedInstructions": int(instr.get("covered") or 0) if instr is not None else 0,
            "unreachedInstructions": int(instr.get("missed") or 0) if instr is not None else 0,
        })
    classes.sort(key=lambda c: c["name"])
    return classes


def _parse_test_baseline(xml_path: str | Path) -> dict:
    """Per-class tested LINE counts from a JaCoCo test report.

    `xml_path` may be a single report XML or a DIRECTORY: a directory is aggregated over every
    jacocoTestReport.xml under it -- the same 'N reports aggregated' shape run-kernel-quality-gate's
    coverage ratchet already uses -- because a real probe baseline must cover the SAME class set as
    the reach report's classfiles (the staged module jars), and no single on-disk test report does
    (verified live: the runtimehost report covers the app's sources; the kernel+adapters reports
    cover the modules the probe attributes). Where a class appears in several module reports,
    testedLines is the MAX -- union semantics, never a sum that would overstate coverage."""
    path = Path(xml_path)
    xmls = sorted(path.glob("**/jacocoTestReport.xml")) if path.is_dir() else [path]
    if not xmls:
        raise ProbeError(f"no jacocoTestReport.xml under baseline {xml_path}")
    by_class: dict[str, dict] = {}
    for xml_file in xmls:
        tree = ElementTree.parse(str(xml_file))
        for cls in tree.getroot().iter("class"):
            counters = {c.get("type"): c for c in cls.findall("counter")}
            line = counters.get("LINE")
            name = (cls.get("name") or "").replace("/", ".")
            tested = int(line.get("covered") or 0) if line is not None else 0
            prior = by_class.get(name)
            if prior is None or tested > prior["testedLines"]:
                by_class[name] = {"testedLines": tested}
    return {"schemaVersion": "jacoco-test-baseline.v1", "source": str(path), "classes": by_class}


def _intersect_reach_and_tests(classes: list[dict], baseline: dict) -> dict:
    """P2 shape + A5's non-overlap honesty. For classes present in BOTH inputs, the 2x2 over LINE
    counters: reached&tested, reached&UNTESTED (THE headline), tested&unreached, neither.

    Classes present in only ONE input are reported as `unknown`, in both directions, NEVER folded
    into a bucket -- an unmeasured class read as 'not executed' (or the reverse) would be the same
    conflation the whole design exists to prevent. The probe's classfiles are the staged module jars
    only (the fat jar and H2's multi-release jar break JaCoCo 0.8.9 -- hit live), so the reach
    report and a test baseline routinely cover different class sets; the unknown rows exist to make
    that visible rather than silent."""
    by_name = {c["name"]: c for c in classes}
    baseline_classes = baseline.get("classes") or {}
    cells = {"reachedTested": 0, "reachedUntested": 0, "testedUnreached": 0, "neither": 0}
    reach_only: list[str] = []
    for entry in classes:
        if entry["name"] not in baseline_classes:
            reach_only.append(entry["name"])
    baseline_only = sorted(name for name in baseline_classes if name not in by_name)
    for name in sorted(n for n in baseline_classes if n in by_name):
        entry = by_name[name]
        reached = entry["reachedLines"]
        tested = int((baseline_classes[name] or {}).get("testedLines") or 0)
        total = reached + entry["unreachedLines"]
        cells["reachedTested"] += min(reached, tested)
        cells["reachedUntested"] += max(0, reached - tested)
        cells["testedUnreached"] += max(0, tested - reached)
        cells["neither"] += max(0, total - max(reached, tested))
    return {
        "overlapping": cells,
        "unknownReachOnly": {"count": len(reach_only), "classes": sorted(reach_only)},
        "unknownBaselineOnly": {"count": len(baseline_only), "classes": baseline_only},
    }


def _reach_human_table(report: dict) -> str:
    """Leads with reached & UNTESTED when the intersection exists (that row is the reason the tool
    exists); otherwise an honest raw split with the P2 pointer. Never a bare percentage."""
    lines = [f"EXECUTION REACH -- probe run {report['probeRunId']}"]
    totals = report["totals"]
    if report.get("intersection"):
        ov = report["intersection"]["overlapping"]
        lines.append(f"  reached & tested        {ov['reachedTested']:>10,} lines")
        lines.append(f"  reached & UNTESTED      {ov['reachedUntested']:>10,} lines   <- THE HEADLINE: users hit it, nothing checks it")
        lines.append(f"  unreached but tested     {ov['testedUnreached']:>10,} lines")
        lines.append(f"  unreached & untested     {ov['neither']:>10,} lines")
        reach_unknown = report["intersection"]["unknownReachOnly"]["count"]
        baseline_unknown = report["intersection"]["unknownBaselineOnly"]["count"]
        if reach_unknown or baseline_unknown:
            lines.append(
                f"  unknown (no overlap)      {reach_unknown:,} classes reach-only, "
                f"{baseline_unknown:,} baseline-only -- never counted as reached or unreached")
    else:
        lines.append(f"  executed at runtime      {totals['reachedLines']:>10,} lines")
        lines.append(f"  not executed             {totals['unreachedLines']:>10,} lines")
        lines.append("  (the 'reached & UNTESTED' headline needs --baseline <jacoco-test-report.xml>"
                     " or a directory of jacocoTestReport.xml -- run `npdev probe stop --baseline` "
                     "to see the full 2x2)")
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="probe_command", required=True)

    start = sub.add_parser("start", help="Boot the app with the JaCoCo agent attached.")
    start.add_argument("--app", required=True, help="The generated app dir (root or App module).")
    start.add_argument("--agent-port", type=int, default=DEFAULT_AGENT_PORT)
    start.add_argument("--wait", type=float, default=0.0,
                       help="P1 helper for `run`: hold the session open N seconds before returning.")
    start.add_argument("--json", action="store_true")

    dump = sub.add_parser("dump", help="Mid-session execution-data snapshot; the app keeps running.")
    dump.add_argument("--json", action="store_true")

    stop = sub.add_parser("stop", help="Final dump, stop the app, emit the reach report.")
    stop.add_argument("--baseline", default=None,
                      help="P2: a JaCoCo TEST report (an XML file, or a DIRECTORY of "
                           "jacocoTestReport.xml to aggregate -- the kernel gate's N-reports "
                           "shape) to intersect against; non-overlapping classes are reported as "
                           "unknown, never as unreached.")
    stop.add_argument("--json", action="store_true")

    run = sub.add_parser("run", help="One-shot: start, hold N seconds, stop (P3 driver pending).")
    run.add_argument("--app", required=True)
    run.add_argument("--seconds", type=float, default=30.0)
    run.add_argument("--agent-port", type=int, default=DEFAULT_AGENT_PORT)
    run.add_argument("--json", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.probe_command == "start":
        return run_probe_start(args.app, args.agent_port, args.wait, args.json)
    if args.probe_command == "dump":
        return run_probe_dump(json_out=args.json)
    if args.probe_command == "stop":
        return run_probe_stop(args.baseline, args.json)
    if args.probe_command == "run":
        return run_probe_run(args.app, args.seconds, args.agent_port, args.json)
    return 2


if __name__ == "__main__":
    sys.exit(main())