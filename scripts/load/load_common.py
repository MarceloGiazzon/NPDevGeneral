"""Shared plumbing for seed.py and run_load.py (LOAD-2 / LOAD-3): standard library only, so a load run
needs no tool install. The workload itself lives in NPDevSamples/load/workload.json."""
import itertools
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKLOAD = REPO / "NPDevSamples" / "load" / "workload.json"


def load_workload(target):
    spec = json.loads(WORKLOAD.read_text(encoding="utf-8"))
    if target not in spec["targets"]:
        raise SystemExit(f"unknown target '{target}'; workload has {sorted(spec['targets'])}")
    return spec, spec["targets"][target]


def parse_keys(pairs):
    """--key rep=abc... --key mgr=def... -> {'rep': 'abc...', 'mgr': 'def...'}"""
    keys = {}
    for pair in pairs:
        name, _, value = pair.partition("=")
        if not value:
            raise SystemExit(f"--key expects name=value, got '{pair}'")
        keys[name] = value
    return keys


def call(base, key, method, path, body=None, query=None, timeout=60):
    """One HTTP call. Returns (status, parsed-json-or-text, elapsed-ms); never raises on HTTP errors."""
    url = base + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"X-Api-Key": key, "Content-Type": "application/json"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode()
            status = response.status
    except urllib.error.HTTPError as error:
        raw, status = error.read().decode(), error.code
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return 0, str(error), (time.perf_counter() - started) * 1000
    elapsed = (time.perf_counter() - started) * 1000
    try:
        return status, (json.loads(raw) if raw.strip() else None), elapsed
    except ValueError:
        return status, raw, elapsed


def rows_of(payload):
    if isinstance(payload, dict):
        return payload.get("rows") or payload.get("items") or payload.get("content") or []
    return payload or []


class Counter:
    """Thread-safe unique-suffix source."""

    def __init__(self, prefix):
        self._prefix, self._next, self._lock = prefix, itertools.count(1), threading.Lock()

    def __call__(self):
        with self._lock:
            return f"{self._prefix}{next(self._next)}"


def percentile(sorted_values, fraction):
    if not sorted_values:
        return 0.0
    index = min(len(sorted_values) - 1, int(round(fraction * (len(sorted_values) - 1))))
    return sorted_values[index]
