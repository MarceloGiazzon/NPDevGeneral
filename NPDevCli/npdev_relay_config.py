"""Browser-AI relay, F4 / T4.5: sort the changes of a configuration PROPOSAL (npdev-config-proposal.json).

The AI never sends config.json (the ingest policy denies it). It may PROPOSE property changes; each one lands in exactly
one of three places, decided by relay-config-allowlist.json:

  applicable  the property has an entry, the value passes the entry's type/values AND a writer is registered for it
  refused     the property is on the `neverAllowed` list (database, ports, secrets, identity, egress ...): shown by name only,
              the value is never echoed back, and nothing is ever offered
  suggestion  everything else: "the AI suggested changing X; you can do that in Settings", never applied

Two knobs have an entry (T4.5b): the app's theme, `ui.theme.name` and `ui.theme.choices`, which live in config.json's
`defaults` envelope. Everything else imaginable lives in model.json (see the allowlist's `located` list) and stays a
suggestion. An `applicable` item is only a PROPOSAL TO THE OWNER: this module writes nothing. The owner's tick on its preview
line (`confirmCode`), the writer call and undo are in npdev_relay_apply, which puts the writer's output on disk inside the
same all-or-nothing transaction as the model, so undo restores the old config.json. The writer is the CLI's own
(`npdev config set-default`, npdev_config_defaults.set_default_text): one writer, never a raw file write. A malformed proposal
never blocks the model it came with: it is reported and dropped. Pure functions plus the WRITERS registry.
"""
from __future__ import annotations

import fnmatch
import json
from pathlib import Path

import npdev_config_defaults

ALLOWLIST_PATH = Path(__file__).resolve().parent / "relay-config-allowlist.json"
# writer name -> callable(config_text, setting_id, value) -> new config text (pure; the CLI command uses the same one)
WRITERS: dict = {"config-default": npdev_config_defaults.set_default_text}
WHY_MAX = 300
VALUE_TEXT_MAX = 80
PREFIXES = ("config.json:", "config.json.", "config.", "config/", "defaults.", "defaults:")
CONFIRM_PREFIX = "config:"   # the code the owner confirms for one applicable item: "config:<property>"


def load_allowlist(path: Path | None = None) -> dict:
    return json.loads(Path(path or ALLOWLIST_PATH).read_text(encoding="utf-8"))


def normalize_property(raw) -> str:
    """The dotted path as the allowlist writes it: `Database/Host`, `config.database.host` and `database.host` are one."""
    text = str(raw).strip().replace("\\", "/").replace("/", ".").replace("[", ".").replace("]", "")
    text = text.replace('"', "").replace("'", "").strip(".")
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


def _theme_ids(value, context) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    if value.strip() == "*":
        return True
    known = (context or {}).get("themes") or ()
    return all(part.strip() in known for part in value.split(","))


def _value_ok(entry: dict, value, context: dict | None = None) -> bool:
    kind = entry.get("type")
    values = entry.get("values")
    if kind == "themeId":    # one theme that exists: built-in, the app's own, or one this answer carries
        return isinstance(value, str) and value in ((context or {}).get("themes") or ())
    if kind == "themeIds":   # "*" or a comma list of such themes
        return _theme_ids(value, context)
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


def evaluate(proposal, allowlist: dict | None = None, context: dict | None = None) -> dict:
    """{"items": [...], "errors": [...]}: one item per change, in the order sent. Never raises on AI input. `context` is
    what a value may need to be checked against: {"themes": the ids of the themes that exist or arrive with the answer}."""
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
        elif not _value_ok(entry, change["value"], context):
            items.append(_item(prop, "suggestion", "value-not-allowed", why, shown,
                               f"The AI suggested {shown} for {prop}, which is not a value NPDev accepts. You can set it in Settings."))
        elif entry.get("writer") not in WRITERS:
            items.append(_item(prop, "suggestion", "no-writer", why, shown,
                               f"The AI suggested changing {prop} to {shown}. You can do that in Settings."))
        else:
            canonical = str(entry["property"])   # the entry's own spelling is what gets written, whatever the AI typed
            item = _item(canonical, "applicable", "allowlisted", why, shown,
                         str(entry.get("preview") or "{property}: {value}").format(property=canonical, value=shown))
            item["value"] = change["value"]       # already passed the entry's type and values: bounded
            item["confirmCode"] = CONFIRM_PREFIX + canonical
            items.append(item)
    return {"items": items, "errors": errors}
