"""Pack operations for the Manager's "Ready-made parts" screen (P3): ONE JSON document per call, plain-words codes.

The Manager holds no pack logic (owner rule: it is for people who do not know what a lock or a digest is). It asks the CLI:

  npdev pack add --from-catalog <id> --model <m> --json      -> change(...)   adds a part and checks the app still validates
  npdev pack update --model <m> --json                       -> change(...)   brings every part up to what the catalog says
  npdev pack list --model <m>                                -> enrich(...)   the lock + `trustUntil` + `latestVersion`

`change` never leaves the app half-changed: model.json and npdev.lock are snapshotted first and put back when the pack is
refused (not trusted, no internet, not in the catalog) OR when the app stops validating with it. The CLI module is passed in
(it may be running as __main__), so there is exactly one copy of the resolver. Stdlib only.
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import re
import sys
import tempfile
from pathlib import Path

SCHEMA = "npdev-pack-change.v1"
LOCK = "npdev.lock"
_OFFLINE = ("could not resolve host", "unable to access", "failed to connect", "network is unreachable", "timed out",
            "unknownhost", "name resolution", "could not read from remote", "connection refused", "could not reach",
            "no cached pack catalog")


def _snapshot(model: Path) -> dict:
    lock = model.parent / LOCK
    return {model: model.read_bytes(), lock: lock.read_bytes() if lock.is_file() else None}


def _restore(snapshot: dict) -> None:
    for path, data in snapshot.items():
        if data is None:
            path.unlink(missing_ok=True)
        else:
            path.write_bytes(data)


def _classify(text: str) -> tuple[str, str]:
    """(code, one plain sentence in English). The Manager shows its own translated sentence per code; this one is the
    fallback and what a terminal user reads."""
    lowered = text.lower()
    if "unsigned" in lowered or "unknown_signer" in lowered or "bad_signature" in lowered:
        return "UNTRUSTED", "This part could not be checked and does not come from your catalog. It was not added."
    if any(hint in lowered for hint in _OFFLINE):
        return "OFFLINE", "NPDev could not reach the internet to get this part. Connect and try again."
    if "no pack named" in lowered:
        return "NOT_IN_CATALOG", "That part is not in the catalog."
    return "FAILED", "NPDev could not add this part."


def _first_line(text: str) -> str:
    for line in str(text).splitlines():
        if line.strip():
            return line.strip()[:300]
    return ""


def _validation_lines(report: dict) -> list:
    lines = []
    for diagnostic in (report or {}).get("diagnostics") or []:
        if diagnostic.get("severity", "error") != "error":
            continue
        where = str(diagnostic.get("path") or "")
        if re.match(r"^([A-Za-z]:[\\/]|/)", where):  # a file on this computer means nothing to the person
            where = ""
        message = re.sub(r"^(?:[A-Za-z]:[\\/]|/)\S*\s+/\S*:\s*", "", str(diagnostic.get("message") or ""))  # "<file> /packs: "
        parts = [where, message]
        line = " - ".join(p.strip() for p in parts if p and p.strip())
        if line and line not in lines:
            lines.append(line)
    return lines[:6]


def _validates(cli, model: Path):
    """(True, []) | (False, [lines]) | (None, []) when the validator cannot run here (then the add stands)."""
    with tempfile.TemporaryDirectory() as tmp:
        report_path = Path(tmp) / "report.json"
        try:
            cli.run_validate_semantic(model, report_path, quiet=True)
            report = json.loads(report_path.read_text(encoding="utf-8"))
        except (Exception, SystemExit):  # noqa: BLE001 -- no Java / no Gradle: not the pack's fault
            return None, []
    if report.get("status") == "failed":
        return False, _validation_lines(report)
    return True, []


def change(cli, action: str, args: argparse.Namespace) -> dict:
    """Run `pack add` (action "add") or `pack update` ("update") for the Manager. Returns the document `main` prints."""
    model = Path(args.model).expanduser().resolve()
    pack = getattr(args, "from_catalog", None) or ""
    doc = {"schemaVersion": SCHEMA, "command": f"pack {action}", "action": action, "pack": pack, "ok": False,
           "code": "FAILED", "message": "", "details": []}
    if not model.is_file():
        doc.update(code="NO_MODEL", message="This app has no model.json to change.")
        return doc
    snapshot = _snapshot(model)
    out, err = io.StringIO(), io.StringIO()
    runner = cli.run_pack_add if action == "add" else cli.run_pack_update
    args = argparse.Namespace(**{**vars(args), "managed": True})  # the runner must not come back here
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = runner(args)
    except (cli.CliError, OSError, ValueError, SystemExit) as exc:
        _restore(snapshot)
        doc["code"], doc["message"] = _classify(str(exc))
        doc["details"] = [_first_line(exc)]
        return doc
    if code != 0:
        _restore(snapshot)
        text = out.getvalue() + "\n" + err.getvalue()
        doc["code"], doc["message"] = _classify(text)
        doc["details"] = [_first_line(text)]
        return doc
    valid, lines = _validates(cli, model)
    if valid is False:
        _restore(snapshot)
        doc.update(code="INVALID", message="With this part the app would stop working, so it was not added.",
                   details=lines)
        return doc
    doc.update(ok=True, code="DONE" if snapshot[model] != model.read_bytes() or action == "update" else "ALREADY",
               message="Done.", validated=valid is True)
    if err.getvalue().strip():
        doc["warnings"] = [_first_line(err.getvalue())]
    return doc


def _version_key(version) -> tuple | None:
    match = re.match(r"^\s*v?(\d+)\.(\d+)\.(\d+)", str(version or ""))
    return tuple(int(g) for g in match.groups()) if match else None


def latest_versions(cli) -> dict:
    """{pack id: newest version} from the CACHED catalog only -- listing a lock never touches the network."""
    try:
        wrapper = json.loads(cli._pack_catalog_cache_path().read_text(encoding="utf-8"))
        entries = wrapper["catalog"]["packs"]
    except (OSError, ValueError, KeyError, TypeError):
        return {}
    newest: dict = {}
    for entry in entries:
        key = _version_key(entry.get("version")) if isinstance(entry, dict) else None
        if key and (entry["pack"] not in newest or key > newest[entry["pack"]][0]):
            newest[entry["pack"]] = (key, str(entry["version"]))
    return {pack: version for pack, (_key, version) in newest.items()}


def enrich(cli, packs: dict) -> None:
    """Adds to each entry of `pack list`'s report: `trustUntil` (the date the temporary trust rule that accepted an
    unsigned pack ends) and `latestVersion` (only when the cached catalog has a NEWER version than the locked one)."""
    rules = {str(r["id"]): str(r["until"]) for r in cli._load_pack_trust_defaults()}
    newest = latest_versions(cli)
    for pack_id, entry in (packs or {}).items():
        if not isinstance(entry, dict):
            continue
        accepted = str((entry.get("signature") or {}).get("acceptedBy") or "")
        if accepted.startswith("trust-defaults:") and accepted.split(":", 1)[1] in rules:
            entry["trustUntil"] = rules[accepted.split(":", 1)[1]]
        have, latest = _version_key(entry.get("resolvedVersion")), _version_key(newest.get(pack_id))
        if have and latest and latest > have:
            entry["latestVersion"] = newest[pack_id]


def main(cli, action: str, args: argparse.Namespace) -> int:
    doc = change(cli, action, args)
    print(json.dumps(doc, indent=2, ensure_ascii=False))
    return 0 if doc["ok"] else 2
