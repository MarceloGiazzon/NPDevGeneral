"""XREF-3: rewrite every reference to a renamed field, driven by the model-wide reference index.

`npdev migrate rename` has always done the half that breaks nothing -- stamp `renamedFrom`, change
the field's own name -- and none of the half that does. Measured 2026-08-17 by reading
`run_migrate_rename` in full: it never touches `panels`, `procedures`, `queries`, `flows`,
`aggregates`, or any expression string. Because REG-185 used to make the resulting orphans silent,
the outcome validated `passed` and failed at runtime. REG-117 is the same bug in production:
`workspace::Preference` was renamed to `PropertyValue` and the panel referencing it vanished from
the nav, with no error anywhere.

Three properties make this safe enough to run on a real model:

1. **Edits happen at a known JSON pointer, never by string replacement across the file.** Each edge
   in the index carries the exact structural `path` of the reference it describes
   (`panels[OrdersPanel].fieldBindings[2].field`). A blind `s/birthDay/birthDate/` over a model
   would also rewrite a label, a description, a seed value and an unrelated concept's field.

2. **Anything the tool cannot see is a refusal, not a silent skip.** An UNDECIDABLE reference
   mentioning the old name, a hash-pinned trusted-source asset, a pack- or context-contributed
   member living outside the model root, a path this rewriter does not know how to edit -- each
   stops the whole operation and is listed. Rewriting a file while knowingly leaving behind a
   reference you could not follow is worse than not rewriting it, because it looks finished.

3. **The result is re-indexed before anything is written.** The rewrite is applied to a candidate
   copy; the index is rebuilt over that copy; and the write is refused unless the candidate has
   zero remaining references to the old name AND no unresolved reference that the original did not
   already have. So a bug in the rewriting below fails closed.

Stdlib only, by the same rule as `npdev_diagram.py` / `npdev_engines.py` / `npdev_monitor.py`.
"""

from __future__ import annotations

import re
from typing import Any, Callable

# -------------------------------------------------------------------------------------------------
# Path parsing
# -------------------------------------------------------------------------------------------------

# `panels[OrdersPanel]`, `fieldBindings[2]`, `collections[lines]`, `hop[0]`
_STEP_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)(?:\[([^\]]*)\])?")


class CascadeRefusal(Exception):
    """Raised with a human-readable reason and the list of sites that caused it."""

    def __init__(self, reason: str, sites: list[str] | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.sites = sites or []


def _split_path(path: str) -> tuple[list[str], str | None]:
    """Split an edge path into its dotted steps and an optional trailing expression-token index.

    `panels[P].fieldBindings[0].visibleWhen#2` -> (["panels[P]", "fieldBindings[0]", "visibleWhen"], "2")

    The `#N` suffix means "the Nth identifier inside the expression string at this path", which is
    how the index addresses a reference that lives inside free text rather than as its own JSON value.
    """
    token_index = None
    if "#" in path:
        path, _, token_index = path.partition("#")
    return [step for step in path.split(".") if step], token_index


def _resolve(document: Any, steps: list[str]) -> tuple[Any, Any]:
    """Walk `steps` and return (container, key) for the FINAL step, so the caller can read and write
    the value in place. Raises CascadeRefusal naming the step that could not be followed -- most
    often because the referencing object lives in a $ref fragment rather than the root model file.
    """
    container: Any = document
    key: Any = None
    walked: list[str] = []

    for step in steps:
        match = _STEP_RE.fullmatch(step)
        if match is None:
            raise CascadeRefusal(f"cannot parse reference path segment {step!r}")
        name, selector = match.group(1), match.group(2)
        walked.append(step)

        if container is None:
            raise CascadeRefusal("reference path leaves the model root at " + ".".join(walked))

        if not isinstance(container, dict) or name not in container:
            raise CascadeRefusal(
                "reference path is not present in this model file at " + ".".join(walked)
                + " -- the referencing object is probably contributed by a $ref fragment, a "
                  "context or a pack, which this file cannot rewrite")
        if selector is None:
            key, container_next = name, container[name]
            if step is steps[-1]:
                return container, name
            container = container_next
            continue

        collection = container[name]
        if not isinstance(collection, list):
            raise CascadeRefusal(f"expected an array at {'.'.join(walked)}")
        if selector.isdigit():
            index = int(selector)
            if index >= len(collection):
                raise CascadeRefusal(f"index out of range at {'.'.join(walked)}")
        else:
            index = next((i for i, item in enumerate(collection)
                          if isinstance(item, dict) and item.get("name") == selector), None)
            if index is None:
                raise CascadeRefusal(
                    f"no member named {selector!r} in this model file at {'.'.join(walked)}"
                    " -- probably contributed by a pack or a context")
        if step is steps[-1]:
            return collection, index
        container = collection[index]

    raise CascadeRefusal("empty reference path")


# -------------------------------------------------------------------------------------------------
# Value rewriting
# -------------------------------------------------------------------------------------------------

def _rename_identifier(text: str, old: str, new: str) -> str:
    """Replace `old` with `new` only where it appears as a WHOLE identifier.

    A word-boundary regex, not `str.replace`: renaming `status` must not turn `statusLabel` into
    `statusLabelX`, and must not touch the `status` inside a quoted literal like `'status'`... which
    a bare boundary match WOULD touch, so quoted spans are masked out first.
    """
    spans: list[tuple[int, int]] = []
    for quote in ("'", '"'):
        for match in re.finditer(quote + r"[^" + quote + r"]*" + quote, text):
            spans.append(match.span())

    def in_quotes(position: int) -> bool:
        return any(start <= position < end for start, end in spans)

    out = []
    cursor = 0
    for match in re.finditer(r"\b" + re.escape(old) + r"\b", text):
        if in_quotes(match.start()):
            continue
        out.append(text[cursor:match.start()])
        out.append(new)
        cursor = match.end()
    out.append(text[cursor:])
    return "".join(out)


def _rename_dotted_segment(text: str, old: str, new: str) -> str:
    """`orders.birthDay` -> `orders.birthDate`; `shipment.invoice.status` segment-wise."""
    return ".".join(new if segment == old else segment for segment in text.split("."))


def _rename_sorted(text: str, old: str, new: str) -> str:
    """`birthDay desc` -> `birthDate desc`. The direction suffix is part of the value, not the name."""
    parts = text.split()
    if not parts:
        return text
    if parts[0] == old:
        parts[0] = new
        return " ".join(parts)
    return _rename_dotted_segment(text, old, new)


def _rewriter_for(site: str, token_index: str | None) -> Callable[[str, str, str], str]:
    """Pick how the string at a site should be transformed.

    Deliberately keyed on the site rather than guessing from the value's shape: `"birthDay desc"`
    and `"orders.birthDay"` and `"birthDay == 'x'"` are three different things that all contain the
    old name, and only the site says which.
    """
    if token_index is not None or site.endswith("predicate") or site in {
        "query.where", "query.having",
    }:
        return _rename_identifier
    if site in {"query.orderBy", "aggregate.collections.orderBy"}:
        return _rename_sorted
    if site in {"panel.fieldBindings.source", "query.groupBy", "query.groupBy.join"}:
        return _rename_dotted_segment
    return lambda text, old, new: new if text == old else _rename_dotted_segment(text, old, new)


# -------------------------------------------------------------------------------------------------
# The cascade
# -------------------------------------------------------------------------------------------------

# Sites whose value is a procedure input / parameter name rather than a concept field. Renaming a
# CONCEPT FIELD must not rewrite these -- the two namespaces are unrelated, and the index already
# distinguishes them with toKind: "parameter".
_PARAMETER_KINDS = frozenset({"parameter"})


def plan_cascade(edges: list[dict], concept: str, old_field: str) -> tuple[list[dict], list[str]]:
    """Split the index's edges into (rewritable, refusal reasons) for `concept.old_field`.

    Returns edges to rewrite plus a list of human-readable refusals. An empty refusal list is the
    only case in which any writing may happen.
    """
    target = f"{concept}.{old_field}"
    rewritable: list[dict] = []
    refusals: list[str] = []

    for edge in edges:
        names_target = (
            edge.get("toName") == target
            or (edge.get("ownerConcept") == concept
                and str(edge.get("toName", "")).endswith("." + old_field))
        )
        mentions_old_name = re.search(r"\b" + re.escape(old_field) + r"\b",
                                      str(edge.get("toName", ""))) is not None

        if edge.get("resolution") == "UNDECIDABLE" and mentions_old_name:
            refusals.append(
                f"UNDECIDABLE reference at {edge.get('path')} ({edge.get('site')}) mentions "
                f"'{old_field}' -- this tool cannot tell whether it points at the field being "
                f"renamed. Fix it by hand, then re-run.")
            continue
        if not names_target:
            continue
        if edge.get("toKind") in _PARAMETER_KINDS:
            # Not a field reference at all; a same-named procedure input keeps its own name.
            continue
        if edge.get("resolution") != "RESOLVED":
            refusals.append(
                f"reference at {edge.get('path')} is {edge.get('resolution')} -- refusing to "
                f"rewrite a model that is already inconsistent here")
            continue
        if "::" in str(edge.get("fromName", "")):
            refusals.append(
                f"{edge.get('fromKind')} {edge.get('fromName')} is contributed by a pack or "
                f"context, whose source file is outside this model root -- rewrite it there "
                f"(path: {edge.get('path')})")
            continue
        rewritable.append(edge)

    return rewritable, refusals


def trusted_source_refusals(model: dict, edges: list[dict]) -> list[str]:
    """Trusted-source panels/procedures are hash-pinned external assets. Rewriting the manifest
    entry would invalidate the hash and the app would refuse to load the asset at boot -- so name
    the entry and stop, rather than producing a model that generates and then fails to run."""
    pinned = set()
    for key in ("panels", "procedures"):
        for member in model.get(key) or []:
            if not isinstance(member, dict):
                continue
            metadata = member.get("metadata") or {}
            if isinstance(metadata, dict) and metadata.get("trustedSourceEntrypoint"):
                pinned.add((key, member.get("name")))
    refusals = []
    for edge in edges:
        owner = (edge.get("fromKind", "") + "s", edge.get("fromName"))
        if owner in pinned:
            refusals.append(
                f"{edge.get('fromKind')} {edge.get('fromName')} is a trusted-source asset "
                f"(metadata.trustedSourceEntrypoint) -- its content is hash-pinned, so rewriting "
                f"the reference at {edge.get('path')} would invalidate the manifest entry")
    return refusals


def apply_cascade(model: dict, edges: list[dict], old_field: str, new_field: str) -> list[str]:
    """Rewrite every edge in `edges` inside `model` (mutated in place). Returns one description per
    edit. Any edge that cannot be resolved or whose value does not actually change raises
    CascadeRefusal -- "we edited nothing here" is a bug report, not a success.
    """
    edits: list[str] = []
    seen_paths: set[str] = set()
    for edge in edges:
        path = str(edge.get("path", ""))
        # Several edges can legitimately describe ONE string: the two halves of a dotted
        # `fieldBindings[].source`, or a groupBy join's hops and its final field. Rewriting such a
        # string once is correct; rewriting it twice would then find nothing to change and (rightly)
        # refuse, so the de-duplication has to happen here rather than being treated as a conflict.
        if path in seen_paths:
            continue
        seen_paths.add(path)
        steps, token_index = _split_path(path)
        container, key = _resolve(model, steps)
        before = container[key]
        if isinstance(before, dict) and isinstance(before.get("field"), str):
            # A `groupBy` entry in its object form, {"field": ..., "bucket": ...}. The entry is what
            # the path addresses because the bare-string form is equally legal there.
            container, key = before, "field"
            before = before["field"]
        if not isinstance(before, str):
            raise CascadeRefusal(f"expected a string at {path}, found {type(before).__name__}")
        after = _rewriter_for(str(edge.get("site", "")), token_index)(before, old_field, new_field)
        if after == before:
            raise CascadeRefusal(
                f"the value at {path} is {before!r} and rewriting it for '{old_field}' changed "
                f"nothing -- the index and this rewriter disagree about what that site means, "
                f"which is exactly the condition under which a partial rewrite would be silent")
        container[key] = after
        edits.append(f"{path}: {before!r} -> {after!r}")
    return edits


def remaining_references(edges: list[dict], concept: str, old_field: str) -> list[dict]:
    """Edges in a REBUILT index that still point at the old name. Must be empty before writing."""
    target = f"{concept}.{old_field}"
    return [e for e in edges
            if e.get("toName") == target and e.get("toKind") == "field"]
