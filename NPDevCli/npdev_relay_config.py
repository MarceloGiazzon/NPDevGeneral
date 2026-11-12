"""Browser-AI relay, F4 / T4.5: sort the changes of a configuration PROPOSAL (npdev-config-proposal.json).

The AI never sends config.json (the ingest policy denies it). It may PROPOSE property changes; each one lands in exactly
one of three places, decided by relay-config-allowlist.json:

  applicable  the property has an entry, the value passes the entry's type/values AND a writer is registered for it
  refused     the property is on the `neverAllowed` list (database, ports, secrets, identity, egress ...): shown by name only,
              the value is never echoed back, and nothing is ever offered
  suggestion  everything else: "the AI suggested changing X; you can do that in Settings", never applied

No knob has an entry today (see the allowlist's `located` list: everything imaginable lives in model.json), so every
proposal is a suggestion or refused and NOTHING here writes anything. `applicable` is the sort's contract for the day a
knob does have an existing CLI command behind it; applying one (the owner's tick on its preview line, the writer call,
undo) is built WITH that first knob, not before. A malformed proposal never blocks the model it came with: it is
reported and dropped. Pure functions plus the (empty) WRITERS registry.
"""
from __future__ import annotations

import fnmatch
import json
from pathlib import Path

ALLOWLIST_PATH = Path(__file__).resolve().parent / "relay-config-allowlist.json"
WRITERS: dict = {}   # writer name -> callable(property, value); EMPTY: no knob has an existing CLI command behind it yet
WHY_MAX = 300
VALUE_TEXT_MAX = 80
PREFIXES = ("config.json:", "config.json.", "config.", "config/")


def load_allowlist(path: Path | None = None) -> dict:
    return json.loads(Path(path or ALLOWLIST_PATH).read_text(encoding="utf-8"))


def normalize_property(raw) -> str:
    """The dotted path as the allowlist writes it: `Database/Host`, `config.database.host` and `database.host` are one."""
    text = str(raw).strip().replace("\\", "/").replace("/", ".").replace("[", ".").replace("]", "").strip(".")
    lowered = text.casefold()
    for prefix in PREFIXES:
        if lowered.startswith(prefix):
            text = text[len(prefix):]
            lowered = text.casefold()
    return text


def _never(prop: str, allowlist: dict) -> bool:
    folded = prop.casefold()
    return any(fnmatch.fnmatchcase(folded, pattern.casefold()) for pattern in allowlist.get("neverAllowed", []))


def _value_text(value) -> str:
    text = json.dumps(value, ensure_ascii=False)
    return text if len(text) <= VALUE_TEXT_MAX else text[: VALUE_TEXT_MAX - 3] + "..."


def _value_ok(entry: dict, value) -> bool:
    kind = entry.get("type")
    values = entry.get("values")
    if kind == "boolean":
        return isinstance(value, bool)
    if kind == "string":
        return isinstance(value, str) and (not isinstance(values, list) or value in values)
    if kind == "enum":
        return isinstance(values, list) and value in values
    if kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            return False
        bounds = values if isinstance(values, dict) else {}
        return bounds.get("min", value) <= value <= bounds.get("max", value)
    return False


def _item(prop: str, status: str, reason: str, why, value, preview: str) -> dict:
    return {"property": prop, "status": status, "reason": reason, "why": why, "valueText": value, "preview": preview}


def evaluate(proposal, allowlist: dict | None = None) -> dict:
    """{"items": [...], "errors": [...]}: one item per change, in the order sent. Never raises on AI input."""
    allowlist = allowlist if allowlist is not None else load_allowlist()
    entries = {normalize_property(e["property"]).casefold(): e for e in allowlist.get("entries", [])}
    errors: list = []
    if isinstance(proposal, dict) and isinstance(proposal.get("changes"), dict):
        proposal = {**proposal, "changes": [proposal["changes"]]}  # one change as an object stands for a list of one
    if not isinstance(proposal, dict) or not isinstance(proposal.get("changes"), list) or not proposal["changes"]:
        return {"items": [], "errors": ["npdev-config-proposal.json - needs a non-empty \"changes\" list of "
                                        "{\"property\": ..., \"value\": ...} - it was ignored"]}
    items = []
    for index, change in enumerate(proposal["changes"]):
        if not isinstance(change, dict) or not isinstance(change.get("property"), str) or not change["property"].strip() \
                or "value" not in change:
            errors.append(f"npdev-config-proposal.json changes[{index}] - needs a property and a value - it was ignored")
            continue
        prop = normalize_property(change["property"])
        why = change["why"].strip()[:WHY_MAX] if isinstance(change.get("why"), str) and change["why"].strip() else None
        if _never(prop, allowlist):
            items.append(_item(prop, "refused", "never-from-a-chat", why, None,
                               f"The AI asked to change {prop}. NPDev never changes that from a chat, so nothing was changed."))
            continue
        shown = _value_text(change["value"])
        entry = entries.get(prop.casefold())
        if entry is None:
            items.append(_item(prop, "suggestion", "not-a-chat-setting", why, shown,
                               f"The AI suggested changing {prop} to {shown}. You can do that in Settings."))
        elif not _value_ok(entry, change["value"]):
            items.append(_item(prop, "suggestion", "value-not-allowed", why, shown,
                               f"The AI suggested {shown} for {prop}, which is not a value NPDev accepts. You can set it in Settings."))
        elif entry.get("writer") not in WRITERS:
            items.append(_item(prop, "suggestion", "no-writer", why, shown,
                               f"The AI suggested changing {prop} to {shown}. You can do that in Settings."))
        else:
            items.append(_item(prop, "applicable", "allowlisted", why, shown,
                               str(entry.get("preview") or "{property}: {value}").format(property=prop, value=shown)))
    return {"items": items, "errors": errors}
