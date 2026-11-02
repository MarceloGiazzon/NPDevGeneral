"""'Did you mean' hints for validator diagnostics (raw JSON-Schema text is hard to act on).

A `json_schema_additionalproperties` diagnostic says only that a key is not allowed; the hint names
the canonical key to use instead and shows the canonical example. The ONLY alias source is desire-path-aliases.json, the table npdev_desire_paths
also rewrites models with -- add a spelling there, never here. Pure functions, stdlib only.
"""

from __future__ import annotations

import difflib
import json
import re
from pathlib import Path

ALIASES_FILE = Path(__file__).resolve().parent / "desire-path-aliases.json"

_INDEX = re.compile(r"\[\d+\]")
_QUOTED = re.compile(r"'([^']*)'")
_TOKEN = re.compile(r"\.?([^.\[\]]+)|\[(\d+)\]")

SCHEMA_FILE = Path(__file__).resolve().parent.parent / "NPDevContract" / "schemas" / "model.schema.json"
ALLOWED_CAP = 20
ENUM_CAP = 12
_ENUM_LIST = re.compile(r"\[(?:\s*\"[^\"]*\"\s*,?)+\s*\]")

_table: list[dict] | None = None
_schema: dict | None = None
_allowed_cache: dict[str, list[str] | None] = {}


def _objects() -> list[dict]:
    global _table
    if _table is None:
        try:
            _table = json.loads(ALIASES_FILE.read_text(encoding="utf-8")).get("objects", [])
        except (OSError, ValueError):
            _table = []
    return _table


def _entry_for(path: str) -> dict | None:
    pattern = _INDEX.sub("[]", path)
    return next((o for o in _objects() if o.get("path") == pattern), None)


def _walk(model, path: str):
    """The value at a `$.a[0].b` path in model, or None when it is not there."""
    node = model
    for name, idx in _TOKEN.findall(path[1:] if path.startswith("$") else path):
        try:
            node = node[int(idx)] if idx != "" else node[name]
        except (KeyError, IndexError, TypeError):
            return None
    return node


def _load_schema() -> dict:
    global _schema
    if _schema is None:
        try:
            _schema = json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            _schema = {}
    return _schema


def _deref(node, depth: int = 0):
    """Follow local $ref pointers ("#/$defs/x") to the node they name."""
    while isinstance(node, dict) and isinstance(node.get("$ref"), str) and depth < 20:
        target: object = _load_schema()
        for part in node["$ref"].lstrip("#/").split("/"):
            target = target.get(part) if isinstance(target, dict) else None
        node, depth = target, depth + 1
    return node if isinstance(node, dict) else {}


def _variants(node, depth: int = 0) -> list[dict]:
    """The node plus every allOf/anyOf/oneOf branch under it, refs resolved."""
    node = _deref(node)
    out = [node]
    if depth < 6:
        for key in ("allOf", "anyOf", "oneOf"):
            for branch in node.get(key) or []:
                out += _variants(branch, depth + 1)
        for key in ("then", "else"):  # the model's `if $ref then localModelRef else concept` item shape
            if isinstance(node.get(key), dict):
                out += _variants(node[key], depth + 1)
    return out


def _schema_at(path: str):
    """Best-effort schema node for a `$.a[0].b` path, or None."""
    nodes = [_load_schema()]
    for name, idx in _TOKEN.findall(path[1:] if path.startswith("$") else path):
        nxt = []
        for node in nodes:
            for v in _variants(node):
                if idx != "":
                    if "items" in v:
                        nxt.append(v["items"])
                elif name in (v.get("properties") or {}):
                    nxt.append(v["properties"][name])
                elif isinstance(v.get("additionalProperties"), dict):
                    nxt.append(v["additionalProperties"])
        if not nxt:
            return None
        nodes = nxt
    return nodes[0] if len(nodes) == 1 else {"anyOf": nodes}


def allowed_keys(path: str) -> list[str] | None:
    """Property names the schema allows at a model path (cached), or None when it cannot tell."""
    pattern = _INDEX.sub("[]", path)
    if pattern not in _allowed_cache:
        node = _schema_at(path)
        keys: list[str] = []
        for v in _variants(node) if node is not None else []:
            if "$ref" in (v.get("properties") or {}) and len(v.get("properties")) == 1:
                continue  # the localModelRef branch of `if $ref`
            keys += [k for k in (v.get("properties") or {}) if k not in keys]
        _allowed_cache[pattern] = keys or None
    return _allowed_cache[pattern]


def _capped(items: list[str], cap: int) -> str:
    return ", ".join(items[:cap]) + (", ..." if len(items) > cap else "")


def shorten_enum_message(message: str, cap: int = ENUM_CAP) -> str:
    """Cap a long `["a", "b", ...]` enumeration inside a validator message to `cap` values."""
    def cut(m: re.Match) -> str:
        try:
            values = json.loads(m.group(0))
        except ValueError:
            return m.group(0)
        if len(values) <= cap:
            return m.group(0)
        return "[" + ", ".join(json.dumps(v) for v in values[:cap]) + f", ... ({len(values)} values)]"
    return _ENUM_LIST.sub(cut, message)


def _generic_hint(code: str, path: str, message: str, model, where: str) -> str | None:
    if code == "json_schema_additionalproperties":
        found = _QUOTED.search(message)
        key = found.group(1) if found else None
        allowed = allowed_keys(path)
        if not key or not allowed:
            return None
        close = difflib.get_close_matches(key, allowed, n=1, cutoff=0.6)
        head = f"{where}: '{key}' is not allowed here" + (f" -- did you mean '{close[0]}'?" if close else ".")
        return f"{head} Allowed here: {_capped(allowed, ALLOWED_CAP)}."
    if code == "json_schema_enum":
        listed = _ENUM_LIST.search(message)
        try:
            values = json.loads(listed.group(0)) if listed else []
        except ValueError:
            values = []
        actual = _walk(model, path) if model is not None else None
        values = [v for v in values if isinstance(v, str)]
        if not values or not isinstance(actual, str):
            return None
        close = difflib.get_close_matches(actual, values, n=1, cutoff=0.6)
        return (f"{where}: '{actual}' is not allowed" + (f" -- did you mean '{close[0]}'?" if close else ".")
                + f" Allowed: {_capped(values, ENUM_CAP)}.")
    return None


def _short(path: str) -> str:
    return path[2:] if path.startswith("$.") else path


def _tail(entry: dict, example: bool) -> str:
    out = ""
    if example and entry.get("example") is not None:
        out += " Example: " + json.dumps(entry["example"])
    if example and entry.get("note"):
        out += "  (" + entry["note"] + ")"
    return out


def _a(entry: dict) -> str:
    label = entry.get("label", entry["id"])
    return ("an " if label[:1] in "aeiou" else "a ") + label


def hint_for(diagnostic: dict, model: dict | None = None, *, example: bool = True) -> str | None:
    """One-line hint for one diagnostic, or None when the table has nothing to say."""
    code = str(diagnostic.get("code", ""))
    path = str(diagnostic.get("path", ""))
    message = str(diagnostic.get("message", ""))
    where = _short(path)

    if code == "json_schema_enum":
        # the enum path is the VALUE ("...fields[0].type"), which is the table entry's own path
        entry = _entry_for(path)
        if not entry or not entry.get("valueAliases"):
            return _generic_hint(code, path, message, model, where)
        actual = _walk(model, path) if model is not None else None
        if not isinstance(actual, str):
            found = re.search(r"(?:value|was|found) '([^']*)'", message)
            actual = found.group(1) if found else None
        right = entry["valueAliases"].get(str(actual).lower()) if actual is not None else None
        if not right:
            return _generic_hint(code, path, message, model, where)
        return f"{where}: '{actual}' is not {_a(entry)} -- use '{right}'." + (
            f" {entry['note']}" if example and entry.get("note") else "")

    entry = _entry_for(path)
    if not entry:
        return _generic_hint(code, path, message, model, where)
    key = _QUOTED.search(message)
    key = key.group(1) if key else None
    aliases = entry.get("keyAliases") or {}
    canonical = entry.get("canonicalKeys") or []

    if code == "json_schema_additionalproperties" and key:
        if key in aliases:
            head = f"{where}: '{key}' is not {_a(entry)} key -- use '{aliases[key]}'."
        elif not canonical:
            return _generic_hint(code, path, message, model, where)
        else:
            close = difflib.get_close_matches(key, canonical, n=1, cutoff=0.6)
            head = (f"{where}: '{key}' is not {_a(entry)} key -- did you mean '{close[0]}'?" if close
                    else f"{where}: '{key}' is not {_a(entry)} key. Keys: {', '.join(canonical)}.")
        return head + _tail(entry, example)

    if code == "json_schema_required" and key:
        obj = _walk(model, path) if model is not None else None
        wrong = next((k for k in obj if aliases.get(k) == key), None) if isinstance(obj, dict) else None
        head = (f"{where}: required '{key}' is missing -- rename '{wrong}' to '{key}'." if wrong
                else f"{where}: required '{key}' is missing.")
        return head + _tail(entry, example)
    return None


def add_hints(report: dict, model: dict | None = None) -> dict:
    """Set diagnostic["hint"] on every diagnostic that has one. Several schema errors on one object
    repeat the same example, so only the first hint per path carries it; identical hints collapse."""
    shown: set[str] = set()
    seen: set[tuple[str, str]] = set()
    for d in report.get("diagnostics") or []:
        if not isinstance(d, dict):
            continue
        path = str(d.get("path", ""))
        bare = hint_for(d, model, example=False)
        if not bare or (path, bare) in seen:
            continue
        seen.add((path, bare))
        d["hint"] = bare if path in shown else hint_for(d, model)
        shown.add(path)
    return report
