#!/usr/bin/env python3
"""Declarative pack probes (FORM P6-T3, rung 4 part A): run `packs/<id>/probes/*.json` against a booted consumer app.

The probe file format is NPDevContract/schemas/pack-workshop/probe.schema.json. This module only drives a base URL with
one static API key per identity (the same `X-Api-Key` mechanism the DOM sample probes use); booting the app is the
caller's business. Nothing here reads markdown; stdlib only.
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.request
import uuid
from pathlib import Path

SCHEMA_PATH = Path(__file__).resolve().parents[1] / "NPDevContract" / "schemas" / "pack-workshop" / "probe.schema.json"
VARIABLE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")
MISSING = object()


class ProbeError(Exception):
    """The probe file itself is wrong (not the pack): bad shape, unknown identity, unknown variable."""


def check_shape(doc) -> list[str]:
    """Schema errors plus the cross-reference rules the schema cannot say, as author-readable lines."""
    import npdev_jsonschema
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    lines = [f"probe {e.get('path') or '/'} - {e.get('message')}" for e in npdev_jsonschema.validate(schema, doc)]
    if lines:
        return lines
    names = [u["name"] for u in doc["users"]]
    if len(set(names)) != len(names):
        lines.append("probe users - a name is used twice - give every identity its own name")
    for index, step in enumerate(doc["steps"]):
        if step["as"] not in names:
            lines.append(f"probe steps[{index}].as - '{step['as']}' is not in users {names} - use one of those names")
    return lines


def api_keys_env(users: list[dict], keys: dict[str, str]) -> str:
    """NPDEV_AUTH_API_KEYS value: `<key>=dev:<name>:<ROLE>` per identity, ';'-separated (the format the DOM probes use)."""
    parts = []
    for user in users:
        role = user.get("role", "USER")
        parts.append(f"{keys[user['name']]}=dev:{user['name']}:{role}")
    return ";".join(parts)


def new_keys(users: list[dict]) -> dict[str, str]:
    return {u["name"]: "probe-" + uuid.uuid4().hex for u in users}


def dotted(value, path: str):
    """`content.0.name` -> value, or MISSING."""
    current = value
    for part in path.split("."):
        if isinstance(current, list):
            if not part.isdigit() or int(part) >= len(current):
                return MISSING
            current = current[int(part)]
        elif isinstance(current, dict):
            if part not in current:
                return MISSING
            current = current[part]
        else:
            return MISSING
    return current


def _substitute(value, variables: dict):
    if isinstance(value, str):
        def swap(match):
            name = match.group(1)
            if name not in variables:
                raise ProbeError(f"variable '${{{name}}}' is used before any step saved it")
            return str(variables[name])
        return VARIABLE.sub(swap, value)
    if isinstance(value, list):
        return [_substitute(v, variables) for v in value]
    if isinstance(value, dict):
        return {k: _substitute(v, variables) for k, v in value.items()}
    return value


def http_call(base: str, key: str, method: str, path: str, body):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base.rstrip("/") + path, data=data, method=method,
                                     headers={"X-Api-Key": key, "Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, _parse(response.read())
    except urllib.error.HTTPError as error:
        try:
            return error.code, _parse(error.read())
        finally:
            error.close()


def _parse(raw: bytes):
    text = raw.decode("utf-8", "replace")
    if not text.strip():
        return None
    try:
        return json.loads(text)
    except ValueError:
        return text


def _row_count(payload):
    if isinstance(payload, dict) and isinstance(payload.get("totalElements"), int):
        return payload["totalElements"]
    if isinstance(payload, list):
        return len(payload)
    return None


def _judge(expect: dict, status: int, payload) -> list[str]:
    problems = []
    wanted = expect["status"] if isinstance(expect["status"], list) else [expect["status"]]
    if status not in wanted:
        problems.append(f"status {status}, wanted {' or '.join(map(str, wanted))}")
    for path, want in (expect.get("json") or {}).items():
        got = dotted(payload, path)
        if got is MISSING or got != want:
            problems.append(f"{path} is {'missing' if got is MISSING else repr(got)}, wanted {want!r}")
    for path in expect.get("has") or []:
        if dotted(payload, path) is MISSING:
            problems.append(f"{path} is missing from the response")
    rows = expect.get("rows")
    if rows:
        count = _row_count(payload)
        if count is None:
            problems.append("the response is not a list, so rows cannot be counted")
        else:
            if "exact" in rows and count != rows["exact"]:
                problems.append(f"{count} row(s), wanted exactly {rows['exact']}")
            if "min" in rows and count < rows["min"]:
                problems.append(f"{count} row(s), wanted at least {rows['min']}")
            if "max" in rows and count > rows["max"]:
                problems.append(f"{count} row(s), wanted at most {rows['max']}")
    return problems


def run_probe(base: str, doc: dict, keys: dict[str, str], call=None) -> list[dict]:
    """Run every step in order. Returns [{name, as, ok, detail}]. A failed step does not stop the run (the author wants
    every red at once), but a step that needs a variable a failed step never saved is reported as skipped-by-dependency."""
    call = call or (lambda key, method, path, body: http_call(base, key, method, path, body))
    variables: dict = {"rand": uuid.uuid4().hex[:8]}
    results = []
    for step in doc["steps"]:
        try:
            path = _substitute(step["path"], variables)
            body = _substitute(step["body"], variables) if "body" in step else None
        except ProbeError as exc:
            results.append({"name": step["name"], "as": step["as"], "ok": False, "detail": str(exc)})
            continue
        status, payload = call(keys[step["as"]], step["method"], path, body)
        problems = _judge(step["expect"], status, payload)
        for name, where in (step.get("save") or {}).items():
            found = dotted(payload, where)
            if found is MISSING:
                problems.append(f"cannot save '{name}': {where} is missing from the response")
            else:
                variables[name] = found
        results.append({"name": step["name"], "as": step["as"], "ok": not problems,
                        "detail": "; ".join(problems)[:400]})
    return results
