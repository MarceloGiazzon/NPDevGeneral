"""Set one app-wide default in an app's config.json: `npdev config set-default` (REG-323 T4.5b).

config.json's `defaults` envelope is `settingId -> scalar` (config.schema.json): the app's theme (`ui.theme.name`,
`ui.theme.choices`), its display mode and density and other behaviour settings live there as FLAT dotted keys, never nested.
Nothing in the CLI wrote them before; people edited the file by hand.

`set_default_text` is the pure half: the file's text in, the new text out, every other value untouched (key order, indent
width, line endings and the trailing newline are kept; an object written on one line is laid out one value per line). Two callers use it so there is ONE writer:
  * the command below, for a person at a terminal, and
  * the browser-AI relay (npdev_relay_config.WRITERS["config-default"]), which never writes a file by itself: it asks for the
    new text and puts it on disk through its own all-or-nothing transaction, so undo can put the old file back.
It validates the SHAPE only (a dotted setting id, a scalar value); which settings a chat may touch is the relay's allowlist.
Stdlib only.
"""
from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path

KEY = re.compile(r"^[a-z][A-Za-z0-9]*(\.[A-Za-z][A-Za-z0-9]*)+$")
TYPES = ("string", "boolean", "integer", "number")


def _indent_of(text: str):
    found = re.search(r'^([ \t]+)"', text, re.MULTILINE)
    return found.group(1) if found else 2


def set_default_text(config_text: str, key: str, value) -> str:
    """The text of config.json with `defaults[key] = value`. ValueError (plain words) when the key or value has the wrong
    shape or the file is not an object. Setting a key to the value it already has returns the text unchanged."""
    if not isinstance(key, str) or not KEY.match(key):
        raise ValueError(f"{key!r} is not a setting id (dotted, like ui.theme.name)")
    if value is None or not isinstance(value, (str, bool, int, float)):
        raise ValueError(f"the value of {key} must be text, true/false or a number")
    try:
        config = json.loads(config_text)
    except ValueError as exc:
        raise ValueError(f"config.json is not valid JSON: {str(exc)[:120]}") from exc
    if not isinstance(config, dict):
        raise ValueError("config.json is not a JSON object")
    defaults = config.get("defaults")
    if defaults is None:
        defaults = config["defaults"] = {}
    elif not isinstance(defaults, dict):
        raise ValueError('config.json "defaults" is not an object')
    if key in defaults and defaults[key] == value and type(defaults[key]) is type(value):
        return config_text
    defaults[key] = value
    crlf = "\r\n" in config_text
    out = json.dumps(config, indent=_indent_of(config_text), ensure_ascii=False)
    if config_text.endswith("\n"):
        out += "\n"
    return out.replace("\n", "\r\n") if crlf else out


def config_path(target) -> Path:
    """`target` is a config.json or the folder that holds it."""
    path = Path(target).expanduser()
    return path / "config.json" if path.is_dir() else path


def _write_atomic(path: Path, text: str) -> None:
    handle, temp = tempfile.mkstemp(dir=str(path.parent), prefix=".config-", suffix=".tmp")
    try:
        with os.fdopen(handle, "wb") as out:
            out.write(text.encode("utf-8"))
        os.replace(temp, path)
    except BaseException:
        try:
            os.unlink(temp)
        except OSError:
            pass
        raise


def convert(raw: str, kind: str):
    """The command line gives text; `kind` says what it stands for."""
    if kind == "string":
        return raw
    if kind == "boolean":
        if raw.strip().lower() in ("true", "false"):
            return raw.strip().lower() == "true"
        raise ValueError(f"{raw!r} is not true or false")
    try:
        return int(raw) if kind == "integer" else float(raw)
    except ValueError as exc:
        raise ValueError(f"{raw!r} is not a number") from exc


def run(target, key: str, raw_value: str, kind: str = "string", dry_run: bool = False) -> dict:
    """The command: read config.json, set the default, write it back atomically. Returns a JSON-able report; raises
    ValueError (plain words) when it cannot."""
    path = config_path(target)
    if not path.is_file():
        raise ValueError(f"no config.json at {path}")
    value = convert(raw_value, kind)
    before = path.read_text(encoding="utf-8")
    after = set_default_text(before, key, value)
    changed = after != before
    if changed and not dry_run:
        _write_atomic(path, after)
    return {"ok": True, "path": str(path), "key": key, "value": value, "changed": changed, "dryRun": dry_run}
