"""One definition of "secret" for everything that lets text leave the machine (SUP-4).

The rules are DATA -- ``redaction-rules.json`` next to this file -- and the Rust side of the
Manager embeds the very same file (``NPDevManager/src/redact.rs``, ``include_str!``). Both read the
SAME test vectors (``redaction-test-vectors.json``), so the two engines cannot drift: a rule added
here without a vector, or changed in only one engine, fails a test.

Portability contract (enforced by ``load_rules``): no lookaround, no backreferences, exactly one
named group ``secret``. Only that group's span is replaced by the rule's mask, so there are no
replacement strings, which differ between Python ``re`` and Rust ``regex``.

Order: known secret values (longest first), then the rules in file order. Rules carry a ``class``:
``secret`` (credentials) or ``privacy`` (home folders, e-mail addresses). A caller that needs the
text to stay functional (the Monitor's JSON for the Manager UI, which reads paths from it) asks for
``classes=("secret",)``; a report that leaves the machine takes both.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

_HERE = Path(__file__).resolve().parent
RULES_PATH = _HERE / "redaction-rules.json"
VECTORS_PATH = _HERE / "redaction-test-vectors.json"

_FORBIDDEN = ("(?=", "(?!", "(?<=", "(?<!", "\\1", "\\2")

_cache: dict | None = None


def load_rules(path: Path = RULES_PATH) -> dict:
    """Parse the rules file once. Raises ValueError for a rule that would not run in Rust."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    compiled = []
    for rule in data["rules"]:
        if any(tok in rule["pattern"] for tok in _FORBIDDEN):
            raise ValueError(f"rule {rule['id']}: lookaround/backreference is not portable to Rust regex")
        rx = re.compile(rule["pattern"])
        if "secret" not in rx.groupindex:
            raise ValueError(f"rule {rule['id']}: needs a named group `secret`")
        compiled.append((rule["id"], rule.get("class", "secret"), rule["mask"], rx))
    return {
        "compiled": compiled,
        "key_name": re.compile(data["secretKeyNamePattern"]),
        "min_len": int(data.get("knownValueMinLength", 6)),
        "known_mask": data.get("knownValueMask", "<redacted:known-secret>"),
    }


def _rules() -> dict:
    global _cache
    if _cache is None:
        _cache = load_rules()
    return _cache


def secret_key_name() -> "re.Pattern[str]":
    """The key-NAME pattern (``password``, ``token`` ...) shared with the Rust side."""
    return _rules()["key_name"]


def redact_text(text: str, known=(), classes=("secret", "privacy")) -> tuple[str, dict]:
    """Returns (redacted text, {rule id: count}). ``known`` are secret VALUES to scrub first."""
    r = _rules()
    counts: dict[str, int] = {}
    for value in sorted({k for k in known if k and len(k) >= r["min_len"]}, key=lambda v: (-len(v), v)):
        n = text.count(value)
        if n:
            text = text.replace(value, r["known_mask"])
            counts["known-secret"] = counts.get("known-secret", 0) + n
    for rid, cls, mask, rx in r["compiled"]:
        if cls not in classes:
            continue

        def sub(m, mask=mask, rid=rid):
            start, end = m.span("secret")
            if start < 0:
                return m.group(0)
            counts[rid] = counts.get(rid, 0) + 1
            base = m.start(0)
            whole = m.group(0)
            return whole[: start - base] + mask + whole[end - base:]

        text = rx.sub(sub, text)
    return text, counts


def redact_value(value, known=(), classes=("secret",)):
    """Walk JSON-shaped data: a secret-NAMED key masks its whole value; every string runs the text
    rules. Equal to or stricter than the Monitor's former key-name-only redaction."""
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if isinstance(key, str) and secret_key_name().search(key):
                out[key] = "<redacted>" if item not in (None, "", 0) else item
            else:
                out[key] = redact_value(item, known, classes)
        return out
    if isinstance(value, list):
        return [redact_value(item, known, classes) for item in value]
    if isinstance(value, str):
        return redact_text(value, known, classes)[0]
    return value


def split_counts(counts: dict) -> tuple[int, int]:
    """(secrets hidden, personal details shortened) for the one-line summary."""
    classes = {rid: cls for rid, cls, _m, _r in _rules()["compiled"]}
    secrets = personal = 0
    for rid, n in counts.items():
        if rid == "known-secret" or classes.get(rid, "secret") == "secret":
            secrets += n
        else:
            personal += n
    return secrets, personal
