#!/usr/bin/env python3
"""Promotion evidence: what a workshop run PROVED about one pack@version, gathered in one folder.

`npdev pack workshop promote` builds it in the run that reached the rung (`<run>/evidence/`) so the owner reads it
BEFORE `--confirm`, then copies it to `npr-staging/packs/<pack>/<version>/evidence/` so it travels with the pack:

    evidence.html          one self-contained page: summary line, ladder, probes, exposure matrix, screenshots, logs
    evidence.json          the same summary as data (schemaVersion npdev-pack-evidence.v1)
    probes.json            probe results, as the run wrote them
    probe-plan.json        every step joined with what it EXPECTED (declared before the run) and what it got
    derived-probes/        probes derived from the pack's own access rules, when the run derived any
    exposure-matrix.json   what every identity could read
    test-verdict.json      the verdict of the run the evidence comes from
    *.txt / validate.json  the static, compose, validate and access-lint output
    logs/*.log             generate, build and start of the throwaway consumer (tail only)
    populate/              what Mockuper loaded from the pack's demo/ and the counts read back (rung 'populated', `test --populate`)
    screens/               screenshots per identity + manifest.json + gallery.html (rung 5, `test --screens`)

Throwaway probe API keys are scrubbed from every text file copied. Nothing here reads markdown; stdlib only.
"""

from __future__ import annotations

import html
import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path

EVIDENCE_DIR = "evidence"
SCHEMA_VERSION = "npdev-pack-evidence.v1"
LOG_TAIL_BYTES = 200_000
TEXT_SUFFIXES = {".json", ".txt", ".log", ".html"}
PROBE_KEY = re.compile(r"probe-[0-9a-f]{32}")
RUN_FILES = ("probes.json", "exposure-matrix.json", "validate.json", "validate.txt", "static.txt", "compose.txt", "access-lint.txt")
NOT_EVIDENCE_STATUSES = ("reviewed", "published")


def source_run(root: Path, pack_id: str, version: str) -> Path | None:
    """The newest run of pack@version that a TEST wrote (a promote writes a run too, with no evidence in it)."""
    runs = root / "runs" / f"{pack_id}@{version}"
    if not runs.is_dir():
        return None
    for stamp in sorted((p.name for p in runs.iterdir() if (p / "verdict.json").is_file()), reverse=True):
        verdict = _load(runs / stamp / "verdict.json")
        if isinstance(verdict, dict) and verdict.get("status") not in NOT_EVIDENCE_STATUSES:
            return runs / stamp
    return None


def _load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _scrub(text: str) -> str:
    return PROBE_KEY.sub("probe-<scrubbed>", text)


def _copy_text(src: Path, dst: Path, tail: int | None = None) -> None:
    raw = src.read_bytes()
    if tail is not None and len(raw) > tail:
        raw = b"[... only the last %d bytes are kept ...]\n" % tail + raw[-tail:]
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(_scrub(raw.decode("utf-8", "replace")), encoding="utf-8")


def _copy_tree(src: Path, dst: Path) -> None:
    for path in sorted(src.rglob("*")):
        if not path.is_file():
            continue
        target = dst / path.relative_to(src)
        if path.suffix.lower() in TEXT_SUFFIXES:
            _copy_text(path, target)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)


def probe_plan(run: Path, pack_dir: Path | None) -> list[dict]:
    """Every probe step joined with its declaration: who, what, the status it EXPECTED, and whether it got it."""
    results = _load(run / "probes.json") or {}
    plan = []
    for file, rows in results.items():
        derived = (run / "derived-probes" / file).is_file()
        doc = _load(run / "derived-probes" / file) if derived else (_load(pack_dir / "probes" / file) if pack_dir else None)
        steps = (doc or {}).get("steps") or []
        roles = {u.get("name"): u.get("role", "") for u in (doc or {}).get("users") or []}
        for index, row in enumerate(rows):
            step = steps[index] if index < len(steps) and steps[index].get("name") == row.get("name") else \
                next((s for s in steps if s.get("name") == row.get("name")), {})
            expected = (step.get("expect") or {}).get("status")
            plan.append({"file": file, "derived": derived, "name": row.get("name", ""), "as": row.get("as", ""),
                         "role": roles.get(row.get("as"), ""), "method": step.get("method", ""), "path": step.get("path", ""),
                         "expected": expected, "ok": bool(row.get("ok")), "detail": row.get("detail", "")})
    return plan


def _expects_refusal(expected) -> bool:
    wanted = expected if isinstance(expected, list) else [expected]
    return bool(wanted) and all(isinstance(s, int) and s >= 400 for s in wanted)


def summarize(verdict: dict, plan: list[dict], exposure: dict | None, shots: list[dict], demo: dict | None = None) -> dict:
    matrix = (exposure or {}).get("matrix") or {}
    identities = sorted({who for cells in matrix.values() for who in cells})
    passed = sum(1 for p in plan if p["ok"])
    refusals = [p for p in plan if _expects_refusal(p["expected"])]
    summary = {
        "probeSteps": len(plan), "probeStepsPassed": passed,
        "refusalsExpected": len(refusals), "refusalsHeld": sum(1 for p in refusals if p["ok"]),
        "exposureRoutes": len(matrix), "exposureIdentities": len(identities),
        "exposureViolations": len((exposure or {}).get("violations") or []),
        "screenshots": len(shots), "screenshotIdentities": len({s.get("user") for s in shots}),
        "demoRows": (demo or {}).get("rows", 0), "demoIdentities": len((demo or {}).get("byIdentity") or {}),
        "demoRefusals": (demo or {}).get("refusals", 0),
    }
    parts = [f"{passed}/{len(plan)} probe steps passed",
             f"{summary['refusalsHeld']}/{len(refusals)} expected refusals held" if refusals else "no step expects a refusal"]
    if demo:
        parts.append(f"demo data: {demo.get('line', '')}")
    if matrix:
        parts.append(f"{len(matrix)} route(s) read as {len(identities)} identities, {summary['exposureViolations']} violation(s)")
    parts.append(f"{len(shots)} screenshot(s) for {summary['screenshotIdentities']} identities" if shots else "no screenshots")
    summary["line"] = " · ".join(parts)
    summary["rung"] = verdict.get("status", "")
    return summary


def build(root: Path, pack_id: str, version: str, pack_dir: Path | None) -> dict:
    """(Re)build `<run>/evidence/` from the newest test run. Returns {run, folder, page, summary}; raises LookupError
    when no test run exists."""
    run = source_run(root, pack_id, version)
    if run is None:
        raise LookupError(f"{pack_id}@{version} has no test run to take evidence from")
    out = run / EVIDENCE_DIR
    if out.exists():
        shutil.rmtree(out)
    out.mkdir()
    verdict = _load(run / "verdict.json") or {}
    for name in RUN_FILES:
        if (run / name).is_file():
            _copy_text(run / name, out / name)
    _copy_text(run / "verdict.json", out / "test-verdict.json")
    if (run / "derived-probes").is_dir():
        _copy_tree(run / "derived-probes", out / "derived-probes")
    logs = run / "consumer" / "logs"
    log_names = []
    if logs.is_dir():
        for log in sorted(logs.glob("*.log")):
            _copy_text(log, out / "logs" / log.name, tail=LOG_TAIL_BYTES)
            log_names.append(log.name)
    shots: list[dict] = []
    if (run / "screens" / "manifest.json").is_file():
        _copy_tree(run / "screens", out / "screens")
        manifest = _load(run / "screens" / "manifest.json") or []
        shots = [row for row in manifest if isinstance(row, dict) and "file" in row]
    demo = None
    if (run / "populate" / "summary.json").is_file():   # rung 'populated': what Mockuper created and what was read back
        import npdev_pack_populate
        for name in npdev_pack_populate.EVIDENCE_FILES:
            if (run / "populate" / name).is_file():
                _copy_text(run / "populate" / name, out / "populate" / name)
        demo = _load(run / "populate" / "summary.json")
    plan = probe_plan(run, pack_dir)
    (out / "probe-plan.json").write_text(json.dumps(plan, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    exposure = _load(run / "exposure-matrix.json")
    pack = (_load(pack_dir / "pack.json") if pack_dir else None) or {}
    summary = summarize(verdict, plan, exposure, shots, demo)
    doc = {"schemaVersion": SCHEMA_VERSION, "pack": pack_id, "version": version, "run": run.name,
           "builtAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "summary": summary, "logs": log_names}
    (out / "evidence.json").write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    page = out / "evidence.html"
    page.write_text(_scrub(render(pack_id, version, run.name, pack.get("description", ""), verdict, plan, exposure, shots,
                                  log_names, summary, demo)), encoding="utf-8")
    return {"run": str(run), "folder": str(out), "page": str(page), "summary": summary}


_CSS = """
:root{color-scheme:light dark;--fg:#1d1d1f;--muted:#6e6e73;--line:#d2d2d7;--bg:#fff;--card:#f5f5f7;--ok:#1a7f37;--bad:#c62828;--refuse:#8250df}
@media (prefers-color-scheme:dark){:root{--fg:#f5f5f7;--muted:#a1a1a6;--line:#3a3a3c;--bg:#161618;--card:#1f1f22;--ok:#3fb950;--bad:#ff7b72;--refuse:#bc8cff}}
body{font:14px/1.5 system-ui,sans-serif;margin:0;background:var(--bg);color:var(--fg)}
main{max-width:1200px;margin:0 auto;padding:2rem 1.5rem 4rem}
h1{font-size:1.6rem;margin:0 0 .25rem}h2{font-size:1.15rem;margin:2.5rem 0 .75rem;border-bottom:1px solid var(--line);padding-bottom:.3rem}
.muted{color:var(--muted)}.lead{font-size:1.05rem;background:var(--card);border-radius:10px;padding:.9rem 1.1rem;margin:1rem 0}
table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;padding:.35rem .5rem;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:600}code{font:12px ui-monospace,monospace}
.ok{color:var(--ok);font-weight:600}.bad{color:var(--bad);font-weight:600}.refuse{color:var(--refuse)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:1rem}
figure{margin:0;border:1px solid var(--line);border-radius:8px;overflow:hidden;background:var(--card)}
img{width:100%;display:block}figcaption{padding:.4rem .7rem;font-size:12px}
"""


def _e(value) -> str:
    return html.escape("" if value is None else str(value))


def _status_text(expected) -> str:
    if expected is None:
        return ""
    return " or ".join(map(str, expected)) if isinstance(expected, list) else str(expected)


def render(pack_id: str, version: str, stamp: str, description: str, verdict: dict, plan: list[dict], exposure: dict | None,
           shots: list[dict], log_names: list[str], summary: dict, demo: dict | None = None) -> str:
    title = f"{pack_id}@{version} — promotion evidence"
    out = [f"<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>",
           f"<title>{_e(title)}</title><style>{_CSS}</style><main>",
           f"<h1>{_e(pack_id)}@{_e(version)}</h1>",
           f"<div class=muted>rung <b>{_e(summary['rung'])}</b> · test run {_e(stamp)}</div>"]
    if description:
        out.append(f"<p>{_e(description)}</p>")
    out.append(f"<div class=lead>{_e(summary['line'])}</div>")
    out.append("<p class=muted>Each probe step below declared, before the run, who acts and what status the app must answer; "
               "the result column is what the running app answered. A step expecting 4xx is a refusal the pack's rules must hold.</p>")

    out.append("<h2>Ladder</h2><table><tr><th>step</th><th>result</th><th>seconds</th><th>detail</th></tr>")
    for step in verdict.get("steps") or []:
        cls = {"pass": "ok", "fail": "bad"}.get(step.get("result"), "muted")
        out.append(f"<tr><td>{_e(step.get('step'))}</td><td class={cls}>{_e(step.get('result'))}</td>"
                   f"<td>{_e(step.get('seconds', ''))}</td><td>{_e(step.get('detail', ''))}</td></tr>")
    out.append("</table>")
    warnings = [line for line in verdict.get("feedbackForAi") or [] if line.startswith("(warning)")]
    if warnings:
        out.append("<p class=muted>Warnings (not blocking):</p><ul>" + "".join(f"<li>{_e(w[len('(warning)'):].strip())}</li>" for w in warnings) + "</ul>")

    out.append("<h2>Probes: expected before, observed during the run</h2>")
    if not plan:
        out.append("<p class=muted>No probe results in this run.</p>")
    else:
        out.append("<table><tr><th>#</th><th>step</th><th>as</th><th>request</th><th>expected</th><th>result</th></tr>")
        for index, p in enumerate(plan, 1):
            refusal = _expects_refusal(p["expected"])
            result = "<span class=ok>as expected</span>" if p["ok"] else f"<span class=bad>FAILED</span> {_e(p['detail'])}"
            who = _e(p["as"]) + (f" <span class=muted>({_e(p['role'])})</span>" if p["role"] else "")
            source = " <span class=muted>(derived)</span>" if p["derived"] else ""
            out.append(f"<tr><td>{index}</td><td>{_e(p['name'])}{source}</td><td>{who}</td>"
                       f"<td><code>{_e(p['method'])} {_e(p['path'])}</code></td>"
                       f"<td class={'refuse' if refusal else ''}>{_e(_status_text(p['expected']))}{' refusal' if refusal else ''}</td>"
                       f"<td>{result}</td></tr>")
        out.append("</table>")

    if demo:
        out.append("<h2>Demo data: what Mockuper loaded, and what the app counted back</h2>")
        out.append(f"<p>{_e(demo.get('line', ''))}. Files: <a href='populate/summary.json'>summary.json</a>, "
                   "<a href='populate/plan.json'>plan.json</a>, <a href='populate/requests.jsonl'>requests.jsonl</a>, "
                   "<a href='populate/created.jsonl'>created.jsonl</a>.</p>")
        counts = demo.get("countsChecked") or {}
        by_identity = demo.get("byIdentity") or {}
        out.append("<table><tr><th>route</th><th>table</th><th>created</th><th>count before</th><th>count after</th></tr>")
        for path, row in sorted(counts.items()):
            ok = row["after"] - row["before"] == row["created"]
            out.append(f"<tr><td><code>{_e(path)}</code></td><td>{_e(row['table'])}</td><td>{_e(row['created'])}</td>"
                       f"<td>{_e(row['before'])}</td><td class={'ok' if ok else 'bad'}>{_e(row['after'])}</td></tr>")
        out.append("</table>")
        if by_identity:
            out.append("<p class=muted>Rows created per identity: " + ", ".join(f"{_e(k)} {_e(v)}" for k, v in sorted(by_identity.items())) + "</p>")

    matrix = (exposure or {}).get("matrix") or {}
    if matrix:
        identities = sorted({who for cells in matrix.values() for who in cells})
        out.append("<h2>Exposure matrix: what each identity could read</h2><table><tr><th>route</th>"
                   + "".join(f"<th>{_e(i)}</th>" for i in identities) + "</tr>")
        for route, cells in sorted(matrix.items()):
            row = [f"<td><code>{_e(route)}</code></td>"]
            for who in identities:
                cell = cells.get(who) or {}
                status = cell.get("read")
                text = f"{status} · {cell.get('rows', 0)} row(s)" if status == 200 else _e(status)
                row.append(f"<td class={'ok' if status == 200 else 'refuse'}>{text}</td>")
            out.append("<tr>" + "".join(row) + "</tr>")
        out.append("</table>")
        for line in (exposure or {}).get("violations") or []:
            out.append(f"<p class=bad>violation: {_e(line)}</p>")

    out.append("<h2>Screens per identity</h2>")
    if not shots:
        out.append(f"<p class=muted>This run took no screenshots. Run <code>npdev pack workshop test {_e(pack_id)}@{_e(version)} --screens</code> "
                   "and promote again to see every role's screens here.</p>")
    else:
        by_user: dict[str, list[dict]] = {}
        for shot in shots:
            by_user.setdefault(shot.get("user", ""), []).append(shot)
        for user, rows in by_user.items():
            out.append(f"<h3>{_e(user)} <small class=muted>role {_e(rows[0].get('role', ''))}</small></h3><div class=grid>")
            for shot in rows:
                extra = f" · {shot['rows']} row(s)" if shot.get("rows") is not None else ""
                src = "screens/" + str(shot["file"])
                out.append(f"<figure><a href='{_e(src)}'><img loading=lazy src='{_e(src)}' alt='{_e(shot.get('screen'))}'></a>"
                           f"<figcaption>{_e(shot.get('screen'))}{_e(extra)}</figcaption></figure>")
            out.append("</div>")

    out.append("<h2>Logs and raw files</h2><ul>")
    for name in log_names:
        out.append(f"<li><a href='logs/{_e(name)}'>logs/{_e(name)}</a></li>")
    for name in ("probe-plan.json", "probes.json", "exposure-matrix.json", "test-verdict.json", "evidence.json"):
        out.append(f"<li><a href='{name}'>{name}</a></li>")
    out.append("</ul></main></html>\n")
    return "\n".join(out)
