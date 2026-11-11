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


# --------------------------------------------------------------------------- how long an ANSWER may be
#
# `outputBudgetChars` is a placeholder until the owner's calibration run (see the module docstring), so everything
# below is "probably", and a prompt built from it must never be the default: only an explicit answer size, or a
# repeated cut-off, may put a number in front of the AI.

MIN_ANSWER_CHARS = 4000          # below this an app's core no longer fits; stop shrinking and change chat instead
WHOLE_FILE_SHARE = 0.6           # plan T4.2: a model above this share of the budget cannot be rewritten whole
SIZE_WORDS = {"small": 4, "medium": 2, "large": 1}   # the budget divided by this
AUTO = "auto"


def parse_answer_size(value):
    """None for "automatic" (nothing is added to any prompt), else "small"/"medium"/"large" or a number of characters."""
    if value is None or (isinstance(value, str) and value.strip().lower() == AUTO):
        return None
    if isinstance(value, bool):
        raise RelayTargetsError("The answer size must be auto, small, medium, large or a number of characters.")
    if isinstance(value, int):
        number = value
    else:
        text = str(value).strip().lower()
        if text in SIZE_WORDS:
            return text
        if not text.isdigit():
            raise RelayTargetsError("The answer size must be auto, small, medium, large or a number of characters.")
        number = int(text)
    if number < MIN_ANSWER_CHARS:
        raise RelayTargetsError(f"The answer size must be at least {MIN_ANSWER_CHARS} characters.")
    return number


def answer_budget(profile: dict, shrink: int = 0, size=None) -> int:
    """How many characters of model.json to ask this chat for: its budget (or the chosen size of it), halved once per
    repeated cut-off, never below MIN_ANSWER_CHARS."""
    size = parse_answer_size(size)
    if isinstance(size, int):
        base = size
    elif size in SIZE_WORDS:
        base = profile["outputBudgetChars"] // SIZE_WORDS[size]
    else:
        base = profile["outputBudgetChars"]
    return max(MIN_ANSWER_CHARS, base // (2 ** max(0, shrink)))


def whole_file_fits(profile: dict, model_chars: int, shrink: int = 0, size=None) -> bool:
    """Can a model of `model_chars` characters be written out WHOLE in one answer from this chat? A change or a fix
    must return the complete file, so a big model that does not fit needs fragments or element upserts (REG-323)."""
    return model_chars <= WHOLE_FILE_SHARE * answer_budget(profile, shrink, size)
