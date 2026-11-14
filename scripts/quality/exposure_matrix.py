#!/usr/bin/env python3
"""Exposure matrix (FORM P0-T4): for every user x every concept collection endpoint of a RUNNING app, what can it read?

A permanent NEGATIVE probe. The 2026-11-13 finding (a RIDER read every password hash and could PUT another user's
credential row while 76/76 acceptance checks stayed green) was invisible to positive tests: nobody had designed a check
for what a role must NOT see. This tool checks exactly that.

    python -I scripts/quality/exposure_matrix.py --base http://127.0.0.1:8150 \
        --app-dir <generated App dir> --admin-user admin \
        --user admin --user disp1 --user rider1 --user driver1 --password-env NPDEV_PROBE_PASSWORD \
        [--expect expect.json] [--write-probe] [--out matrix.json]

- Users: `--user name` (password from $NPDEV_PROBE_PASSWORD) or `--user name:password`.
- Concept endpoints are discovered from <app-dir>/npdev-generated/**/*.json ("/api/concepts/<route>" paths).
  Finding none is an error (exit 2), never a silent pass.
- SYSTEM concepts (identity Credential, reset tokens, MFA secrets, sessions) have no route any more. They are
  probed anyway, by name, for EVERY user including admin: each must answer non-2xx or zero rows.
- READ: GET /api/concepts/<route>?size=100 -> HTTP status + totalElements (page size is capped at 100).
- WRITE (only with --write-probe): for every endpoint a user is expected NOT to see ("none") or that looks sensitive,
  the probe PUTs the ADMIN's first row back with its own unchanged values. A 2xx is a violation. Any other status is
  fine here (a refusal is the wanted answer). It never runs a same-value PUT against an endpoint the user may use.
- --expect: {"<route>": {"<user>": "none" | "some" | "any"}, "*": {"<user>": "none"}}.
  "none" = non-2xx or 0 rows; "some" = readable with at least one row; "any" = not checked. "*" is the default for
  routes not listed, so a NEW concept is closed until someone opens it on purpose. Admin users are never held to "*".
  Without --expect, the probe only fails on the always-wrong cases: a non-admin reading a sensitive-looking route.
Exit codes: 0 ok, 1 violation, 2 usage/discovery/login problem.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

SENSITIVE = re.compile(r"credential|password|secret|token|session|mfa", re.IGNORECASE)
ENDPOINT = re.compile(r"/api/concepts/([a-z0-9_]+)(?=[\"/?])")
PAGE_CAP = 100
# identity pack system concepts, by their REST collection name (used even when the model file cannot be read)
FALLBACK_SYSTEM_ROUTES = ["credentials", "password_reset_tokens", "user_sessions", "user_mfas"]


def call(method, url, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, _parse(response.read())
    except urllib.error.HTTPError as error:
        try:
            return error.code, _parse(error.read())
        finally:
            error.close()


def _parse(raw):
    text = raw.decode("utf-8", errors="replace") if raw else ""
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        return None


def rows_of(payload):
    if isinstance(payload, dict):
        payload = payload.get("content", [])
    return payload if isinstance(payload, list) else []


def count_of(payload):
    """Total rows the caller can see (the page object's totalElements), not just this page."""
    if isinstance(payload, dict) and isinstance(payload.get("totalElements"), int):
        return payload["totalElements"]
    return len(rows_of(payload))


def plural_snake(name):
    snake = re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()
    if snake.endswith("y") and not snake.endswith(("ay", "ey", "oy", "uy")):
        return snake[:-1] + "ies"
    if snake.endswith(("s", "x", "ch", "sh")):
        return snake + "es"
    return snake + "s"


def discover(app_dir: Path):
    """(business routes, system routes) found in the generated app."""
    routes, system = set(), set(FALLBACK_SYSTEM_ROUTES)
    root = app_dir / "npdev-generated"
    if not root.is_dir():
        return [], sorted(system)
    for path in root.rglob("*.json"):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        routes.update(ENDPOINT.findall(text))
        if '"system"' in text:
            try:
                node = json.loads(text)
            except ValueError:
                continue
            for concept in (node.get("concepts") or []) if isinstance(node, dict) else []:
                if isinstance(concept, dict) and concept.get("system") is True and concept.get("name"):
                    system.add(plural_snake(str(concept["name"]).split("::")[-1]))
    return sorted(routes - system), sorted(system)


def login(base, user, password):
    status, body = call("POST", base + "/api/auth/login", body={"username": user, "password": password})
    if isinstance(body, dict) and body.get("token"):
        return body["token"], None
    reason = (body or {}).get("error") if isinstance(body, dict) else None
    if isinstance(body, dict) and body.get("mfaRequired"):
        reason = "mfaRequired"
    return None, f"login failed (HTTP {status}{', ' + str(reason) if reason else ''})"


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--app-dir", required=True)
    ap.add_argument("--user", action="append", required=True)
    ap.add_argument("--admin-user", action="append", default=None, help="users exempt from '*' (default: admin)")
    ap.add_argument("--password-env", default="NPDEV_PROBE_PASSWORD")
    ap.add_argument("--expect", default=None)
    ap.add_argument("--write-probe", action="store_true")
    ap.add_argument("--out", default=None)
    opts = ap.parse_args(argv[1:])
    default_password = os.environ.get(opts.password_env)
    admins = set(opts.admin_user or ["admin"])
    base = opts.base.rstrip("/")
    expect = json.loads(Path(opts.expect).read_text(encoding="utf-8")) if opts.expect else {}
    routes, system_routes = discover(Path(opts.app_dir))
    if not routes:
        print(f"no /api/concepts/<route> found under {opts.app_dir}/npdev-generated -- wrong --app-dir, or not generated",
              file=sys.stderr)
        return 2

    users, tokens, problems = [], {}, []
    for spec in opts.user:
        name, _, password = spec.partition(":")
        password = password or default_password
        if not password:
            print(f"no password for {name}: use name:password or set ${opts.password_env}", file=sys.stderr)
            return 2
        token, reason = login(base, name, password)
        if token is None:
            problems.append(f"{name}: {reason}")
            continue
        users.append(name)
        tokens[name] = token
    if problems:
        for p in problems:
            print("LOGIN " + p, file=sys.stderr)
        return 2

    matrix, violations = {}, []
    admin_rows = {}

    def read(name, route, token):
        status, payload = call("GET", f"{base}/api/concepts/{route}?size={PAGE_CAP}", token)
        ok = 200 <= status < 300
        return status, (count_of(payload) if ok else 0), (rows_of(payload) if ok else [])

    for user in users:
        for route in routes + [r for r in system_routes if r not in routes]:
            status, total, rows = read(user, route, tokens[user])
            cell = {"read": status, "rows": total}
            matrix.setdefault(route, {})[user] = cell
            readable = 200 <= status < 300 and total > 0
            if user in admins and rows and route not in admin_rows:
                admin_rows[route] = rows[0]
            if route in system_routes:
                if readable:
                    violations.append(f"{user} reads {total} row(s) of SYSTEM concept {route} (must be closed to everyone)")
                continue
            want = (expect.get(route) or {}).get(user)
            if not want and user not in admins:
                want = (expect.get("*") or {}).get(user)
            if want == "none" and readable:
                violations.append(f"{user} reads {total} row(s) of {route}, expected none")
            if want == "some" and not readable:
                violations.append(f"{user} cannot read {route} (HTTP {status}), expected some rows")
            if not want and user not in admins and readable and SENSITIVE.search(route):
                violations.append(f"{user} reads {total} row(s) of sensitive {route}")

    if opts.write_probe:
        for user in users:
            if user in admins:
                continue
            for route in routes + system_routes:
                want = (expect.get(route) or {}).get(user) or (expect.get("*") or {}).get(user)
                if not (want == "none" or SENSITIVE.search(route) or route in system_routes):
                    continue
                row = admin_rows.get(route)
                if not row or not row.get("id"):
                    continue
                body = {k: v for k, v in row.items() if k not in ("id", "tenantId")}
                status, _ = call("PUT", f"{base}/api/concepts/{route}/{row['id']}", tokens[user], body)
                matrix.setdefault(route, {}).setdefault(user, {})["write"] = status
                if 200 <= status < 300:
                    violations.append(f"{user} may WRITE {route} rows it must not touch (PUT {status})")

    width = max(len(r) for r in routes + system_routes) + 2
    print("endpoint".ljust(width) + "".join(u.rjust(12) for u in users))
    for route in routes + [r for r in system_routes if r not in routes]:
        tag = "  [system]" if route in system_routes else ""
        cells = []
        for u in users:
            c = matrix.get(route, {}).get(u)
            text = "-" if c is None else (str(c["rows"]) if 200 <= c["read"] < 300 else str(c["read"]))
            if c and "write" in c:
                text += "/w" + str(c["write"])
            cells.append(text.rjust(12))
        print(route.ljust(width) + "".join(cells) + tag)
    if opts.out:
        Path(opts.out).write_text(json.dumps({"matrix": matrix, "violations": violations}, indent=2), encoding="utf-8")
    for v in violations:
        print("VIOLATION " + v)
    print("OK" if not violations else f"{len(violations)} violation(s)")
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
