#!/usr/bin/env python3
"""Normalize a validator diagnostic into a stable failure SIGNATURE.

The failure-signature index (idea 2) keys precedents on the *shape* of an error, not its concrete
identifiers, so "concept Foo references unknown concept Bar" and "concept X references unknown
concept Y" collapse to one key. `normalize()` is applied both to authored `error-fix` knowledge-card
signatures (offline, by the builder) and to a live incoming message (online, by the
`npdev_search_fix` MCP tool), so the two match.

Rules (deterministic, dependency-free):
  1. Any quoted span ('...', "...", `...`) -> <ID>.
  2. If a concept/field value is supplied, its whole-word occurrences -> <C> / <F>.
  3. A JSON-pointer-ish `path` token (e.g. /concepts/3/fields/1) is dropped from the message tail.
  4. Lowercase, collapse whitespace, strip surrounding quotes/trailing punctuation.
"""

from __future__ import annotations

import re

_QUOTED = re.compile(r"(['\"`])(?:\\.|(?!\1).)*\1")
_PATH_TOKEN = re.compile(r"(?:^|\s)/[A-Za-z0-9_./\-\[\]]+")
_WS = re.compile(r"\s+")

# NPDev diagnostic messages name the offending node *unquoted* right after a structural keyword
# ("Flow CreateUser step save-user: references unknown capability persistance"). Templating the token
# after each keyword makes signatures name-agnostic so instances of the same error cluster together.
_STRUCT_KEYWORDS = (
    "flow", "step", "concept", "field", "entity", "capability", "panel", "procedure", "event",
    "invariant", "query", "state", "binding", "adapter", "role", "aggregate", "orchestration",
)
_KEYWORD_ID = re.compile(
    r"\b(" + "|".join(_STRUCT_KEYWORDS) + r")\s+(?!<)([A-Za-z_][\w.\-]*)",
    flags=re.IGNORECASE,
)

# Codes the validator reuses for many distinct errors -- too coarse to cluster on, so fall back to
# the normalized message for these.
GENERIC_CODES = {"semantic_validation_error", "structural_validation_error", "validation_error"}


def normalize(message: str, path: str | None = None, concept: str | None = None,
              field: str | None = None) -> str:
    """Return the normalized signature template for a diagnostic message."""
    if not message:
        return ""
    text = message.strip()

    # 1. quoted identifiers -> <ID>
    text = _QUOTED.sub("<ID>", text)

    # 2. explicit concept/field values -> <C> / <F> (whole-word, case-insensitive)
    for value, token in ((concept, "<C>"), (field, "<F>")):
        if value:
            text = re.sub(rf"\b{re.escape(value)}\b", token, text, flags=re.IGNORECASE)

    # 3. an unquoted identifier right after a structural keyword -> <id>
    text = _KEYWORD_ID.sub(lambda m: f"{m.group(1)} <id>", text)

    # 4. strip a JSON-pointer path token wherever it appears
    text = _PATH_TOKEN.sub(" ", text)
    if path:
        text = text.replace(path, " ")

    # 5. lowercase + collapse + tidy
    text = _WS.sub(" ", text).strip().lower()
    text = text.strip("'\"`").rstrip(".!?;: ").strip()
    return text


def diagnostic_signature(diag: dict) -> str:
    """The single, shared signature for a validation diagnostic (used by the CLI capture, the
    promoter, and search_fix). Prefers a specific `code`; for generic codes (or none) falls back to
    the normalized message so distinct errors don't collapse under one catch-all code."""
    code = diag.get("code")
    if code and code not in GENERIC_CODES:
        return f"code:{code}"
    sig = normalize(diag.get("message", ""), diag.get("path"), diag.get("concept"), diag.get("field"))
    if sig:
        return sig
    return f"code:{code}" if code else "unknown"


if __name__ == "__main__":  # tiny smoke: `python failure_signatures.py "msg"`
    import sys
    print(normalize(*sys.argv[1:2], *(sys.argv[2:3] or [None])))
