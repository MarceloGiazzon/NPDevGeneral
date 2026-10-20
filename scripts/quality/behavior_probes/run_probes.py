#!/usr/bin/env python3
"""ENF-1: table-driven behaviour probes against a booted dsl-conformance-max app.

check-dsl-coverage.py proves a DSL feature appears in some model; this proves the runtime OBEYS it.
A probe file is JSON:

    {"id": "invariant-rejects-negative", "keywords": ["concept.invariants"],
     "description": "...", "steps": [
        {"name": "create", "method": "POST", "path": "/api/widget_orders", "body": {...},
         "expect": {"status": [200, 201], "json": {"$.id": {"exists": true}}},
         "save": {"orderId": "$.id"}}]}

Placeholders in path, body and header values:
    ${name}        a value saved by an earlier step in the same probe
    ${uuid}        a fresh UUID           ${rand}   a fresh 8-hex-digit token (unique per use)
Expectations:
    status   an int or a list of ints
    json     {"$.a.b[0].c": <literal | {"exists": bool} | {"matches": regex} | {"not": literal}
                              | {"in": [..]} | {"gt": n} | {"distinctFrom": "savedName"} | {"len": n}>}
    text     a substring the raw body must contain      notText   one it must NOT contain
A probe that exposes a REAL defect carries "knownFailure": "<ledger id>": it counts as green only while
it still fails (status "known-failure"); once the defect is fixed it turns red ("now passes -- remove
knownFailure") so the marker can never outlive the bug. Never weaken an assertion to make a probe pass.
A step marked "always": true (or, equivalently, "stopOnFail": false) is cleanup: it runs even after an earlier step failed (and its own status is not
checked unless it carries an `expect`). `text`/`notText` accept a string or a list and substitute placeholders.
`expect` may be omitted on a setup step (any status is accepted). A probe step may set
"auth": "none" to send no API key. Exit code is 0 only when every probe is green.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_.-]+)\}")


def resolve_path(doc, path: str):
    """Minimal JSONPath: $.a.b[0].c ; returns (found, value)."""
    if path in ("$", ""):
        return True, doc
    cur = doc
    for part in re.findall(r"[^.\[\]]+|\[\d+\]", path.lstrip("$").lstrip(".")):
        if part.startswith("["):
            index = int(part[1:-1])
            if not isinstance(cur, list) or index >= len(cur):
                return False, None
            cur = cur[index]
        else:
            if not isinstance(cur, dict) or part not in cur:
                return False, None
            cur = cur[part]
    return True, cur


class Probe:
    def __init__(self, base_url: str, api_key: str, spec: dict, source: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.spec = spec
        self.source = source
        self.saved: dict[str, object] = {}

    def subst(self, value):
        if isinstance(value, str):
            def repl(m):
                name = m.group(1)
                if name == "uuid":
                    return str(uuid.uuid4())
                if name == "rand":
                    return uuid.uuid4().hex[:8]
                if name not in self.saved:
                    raise KeyError(f"unknown placeholder ${{{name}}}")
                return str(self.saved[name])
            whole = PLACEHOLDER.fullmatch(value)
            if whole and whole.group(1) in self.saved:
                return self.saved[whole.group(1)]  # keep native type (number, bool, object)
            return PLACEHOLDER.sub(repl, value)
        if isinstance(value, list):
            return [self.subst(v) for v in value]
        if isinstance(value, dict):
            return {k: self.subst(v) for k, v in value.items()}
        return value

    def call(self, step: dict):
        method = step.get("method", "GET").upper()
        url = self.base_url + self.subst(step["path"])
        headers = {"Accept": "application/json"}
        if step.get("auth") != "none":
            headers["X-Api-Key"] = self.api_key
        for key, val in (step.get("headers") or {}).items():
            headers[key] = self.subst(val)
        data = None
        if "body" in step:
            data = json.dumps(self.subst(step["body"])).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as resp:
                return resp.status, resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as err:
            return err.code, err.read().decode("utf-8", "replace")

    def as_list(self, value) -> list[str]:
        """expect.text / notText: one string or a list, placeholders substituted."""
        if value is None:
            return []
        return [str(self.subst(v)) for v in (value if isinstance(value, list) else [value])]

    def check_json(self, doc, rules: dict) -> list[str]:
        problems = []
        for path, want in rules.items():
            found, got = resolve_path(doc, path)
            want = self.subst(want) if not isinstance(want, dict) else want
            if isinstance(want, dict) and len(want) == 1:
                op, arg = next(iter(want.items()))
                if op == "exists":
                    if found != bool(arg):
                        problems.append(f"{path}: exists={found}, wanted {arg}")
                elif not found:
                    problems.append(f"{path}: missing")
                elif op == "matches" and not re.search(arg, str(got)):
                    problems.append(f"{path}: {got!r} !~ /{arg}/")
                elif op == "not" and got == self.subst(arg):
                    problems.append(f"{path}: is {got!r}, must not be")
                elif op == "in" and got not in arg:
                    problems.append(f"{path}: {got!r} not in {arg}")
                elif op == "gt" and not (isinstance(got, (int, float)) and got > arg):
                    problems.append(f"{path}: {got!r} not > {arg}")
                elif op == "len" and (not hasattr(got, "__len__") or len(got) != arg):
                    problems.append(f"{path}: length {len(got) if hasattr(got, '__len__') else '?'} != {arg}")
                elif op == "distinctFrom" and got == self.saved.get(arg):
                    problems.append(f"{path}: {got!r} equals saved {arg!r}, wanted distinct")
                elif op not in ("exists", "matches", "not", "in", "gt", "len", "distinctFrom"):
                    problems.append(f"{path}: unknown operator {op}")
            elif not found:
                problems.append(f"{path}: missing, wanted {want!r}")
            elif got != want:
                problems.append(f"{path}: {got!r} != {want!r}")
        return problems

    def run(self) -> dict:
        failures: list[str] = []
        for index, step in enumerate(self.spec["steps"], 1):
            if failures and not (step.get("always") or step.get("stopOnFail") is False):
                continue  # an earlier step failed: only cleanup steps ("always": true) still run
            label = f"step {index} ({step.get('name', step.get('path'))})"
            try:
                status, body = self.call(step)
            except Exception as exc:  # noqa: BLE001 - a probe reports, never crashes the run
                failures.append(f"{label}: {exc}")
                break
            doc = None
            try:
                doc = json.loads(body) if body.strip() else None
            except ValueError:
                pass
            expect = step.get("expect")
            if expect is not None:
                want = expect.get("status")
                if want is not None and status not in (want if isinstance(want, list) else [want]):
                    failures.append(f"{label}: status {status}, wanted {want}; body {body[:300]!r}")
                for text in self.as_list(expect.get("text")):
                    if text not in body:
                        failures.append(f"{label}: body lacks {text!r}; body {body[:300]!r}")
                for text in self.as_list(expect.get("notText")):
                    if text in body:
                        failures.append(f"{label}: body must not contain {text!r}")
                if "json" in expect:
                    failures += [f"{label}: {p}" for p in self.check_json(doc, expect["json"])]
            for name, path in (step.get("save") or {}).items():
                found, value = resolve_path(doc, path)
                if found:
                    self.saved[name] = value
                else:
                    failures.append(f"{label}: cannot save {name}: {path} not in response; body {body[:200]!r}")
        known = self.spec.get("knownFailure")
        if known and failures:
            status = "known-failure"
        elif known:
            status, failures = "failed", [f"knownFailure {known} is set but the probe now passes -- remove the marker"]
        else:
            status = "passed" if not failures else "failed"
        return {"id": self.spec["id"], "source": self.source, "keywords": self.spec.get("keywords", []),
                "description": self.spec.get("description", ""), "knownFailure": known,
                "status": status, "failures": failures}


def load_probes(directory: Path, only: str) -> list[tuple[dict, str]]:
    probes = []
    for path in sorted(directory.glob("*.json")):
        if only and only not in path.name:
            continue
        doc = json.loads(path.read_text(encoding="utf-8"))
        for spec in (doc["probes"] if "probes" in doc else [doc]):
            probes.append((spec, path.name))
    return probes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--probes-dir", required=True)
    parser.add_argument("--report")
    parser.add_argument("--only", default="")
    args = parser.parse_args()
    api_key = os.environ.get("NPDEV_PROBE_API_KEY", "")
    if not api_key:
        print("NPDEV_PROBE_API_KEY is not set", file=sys.stderr)
        return 2
    results = []
    for spec, source in load_probes(Path(args.probes_dir), args.only):
        result = Probe(args.base_url, api_key, spec, source).run()
        results.append(result)
        label = {"passed": "PASS", "known-failure": "KNOWN"}.get(result["status"], "FAIL")
        print(f"{label}  {result['id']}" + (f"  (known failure {result['knownFailure']})" if result["knownFailure"] else ""))
        for failure in result["failures"]:
            print(f"        {failure}")
    failed = [r for r in results if r["status"] not in ("passed", "known-failure")]
    known = [r for r in results if r["status"] == "known-failure"]
    report = {"schemaVersion": "npdev-behavior-probes-run.v1", "total": len(results), "failed": len(failed),
              "status": "passed" if results and not failed else "failed", "results": results}
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"{len(results) - len(failed)}/{len(results)} behaviour probes passed ({len(known)} known failure(s))")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
