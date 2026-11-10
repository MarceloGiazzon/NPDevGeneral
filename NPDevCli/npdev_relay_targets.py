"""The browser chat AIs "Use a browser AI" knows (D-5): data from relay-targets.json (next to this file).

A target profile is a few numbers and one address -- never a separate prompt. It says where to open the chat, how much
text that chat takes when pasted (`maxPasteChars`: over that, the prompt is offered as a file to attach instead), how
long its answer may be, and whether it can make files. The numbers are placeholders until the owner's calibration run
flips `calibrated` for a target, so a caller must treat them as "probably", never as a promise.

Only https addresses are accepted, and only from this file: the Manager opens a target's `url` and never an address
that came out of an AI's answer.
"""
from __future__ import annotations

import json
from pathlib import Path

SCHEMA_VERSION = "npdev-relay-targets.v1"
DEFAULT_TARGETS_PATH = Path(__file__).resolve().parent / "relay-targets.json"
TIERS = ("full", "short", "none")
DELIVERY_KINDS = ("paste", "attachment")


class RelayTargetsError(Exception):
    """The targets file is wrong, or a target is not in it. The message is safe to show the user."""


def load_targets(path: Path | None = None) -> dict:
    """The file as written, after checking its shape. A broken file is a defect, so it raises (never a silent fallback)."""
    source = Path(path or DEFAULT_TARGETS_PATH)
    try:
        data = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise RelayTargetsError(f"The list of AIs could not be read ({exc.__class__.__name__}).") from None
    _check(data)
    return data


def _check(data: dict) -> None:
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA_VERSION:
        raise RelayTargetsError("The list of AIs is not the version this NPDev reads.")
    defaults = data.get("defaults")
    if not isinstance(defaults, dict) or defaults.get("delivery") not in DELIVERY_KINDS:
        raise RelayTargetsError("The list of AIs has no usable defaults.")
    for key in ("maxPasteChars", "outputBudgetChars"):
        if not isinstance(defaults.get(key), int) or defaults[key] < 1:
            raise RelayTargetsError(f"The list of AIs has no usable default for {key}.")
    seen = set()
    for item in data.get("targets") or []:
        ident = item.get("id") if isinstance(item, dict) else None
        if not isinstance(ident, str) or not ident or ident in seen:
            raise RelayTargetsError("The list of AIs has a missing or repeated id.")
        seen.add(ident)
        if not isinstance(item.get("label"), str) or not item["label"]:
            raise RelayTargetsError(f"The AI {ident} has no name.")
        url = item.get("url")
        if url is not None and not (isinstance(url, str) and url.startswith("https://")):
            raise RelayTargetsError(f"The AI {ident} has an address that is not https.")
        if item.get("tier") not in TIERS:
            raise RelayTargetsError(f"The AI {ident} has an unknown tier.")
    if not seen:
        raise RelayTargetsError("The list of AIs is empty.")


def profiles(path: Path | None = None) -> list[dict]:
    """Every target, in file order, each with the defaults filled in."""
    data = load_targets(path)
    defaults = data["defaults"]
    result = []
    for item in data["targets"]:
        merged = {**defaults, **item}
        merged.setdefault("labels", {})
        merged.setdefault("notes", "")
        merged["calibrated"] = bool(item.get("calibrated"))
        merged["verifyUrl"] = bool(item.get("verifyUrl"))
        result.append(merged)
    return result


def ids(path: Path | None = None) -> tuple:
    return tuple(p["id"] for p in profiles(path))


def find(target_id: str, path: Path | None = None) -> dict:
    for profile in profiles(path):
        if profile["id"] == target_id:
            return profile
    raise RelayTargetsError("That AI is not in the list.")


def fits(profile: dict, chars: int) -> bool:
    """Does a text of `chars` characters go into this chat as a plain paste?"""
    return chars <= profile["maxPasteChars"]
