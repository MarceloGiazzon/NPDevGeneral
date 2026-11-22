#!/usr/bin/env python3
"""Probes derived from a pack's own declarations (FORM P6, rung 4 without the authoring AI).

The wave-1 deliveries stopped at `secure` because the browser AIs never delivered probes/*.json. A pack already says
who may do what, so the workshop can write the probes itself. This module derives, per concept, one probe file in
the existing `npdev-pack-probe.v1` format (NPDevContract/schemas/pack-workshop/probe.schema.json):

  * a role the pack's `access.write` rule admits creates a row (and the parent rows its required references need)
    and reads it back -- the pack is usable at all;
  * a DECOY role -- a role token the consumer grants full coarse access to but the pack never names -- is refused
    on create (and on read when `access.read` is a closed set of roles). The decoy is what makes the verdict about
    the PACK: the consumer app is not the gate for that identity, so only the pack's own rule can say no.

Only what can be proven from a role-only rule is derived. A rule with `&&`, parentheses, a field comparison or any
term this module does not know is NOT guessed at: the concept is listed in `skipped` with the reason, never given a
probe that could be wrong. Likewise a concept whose required fields cannot be filled from the declaration alone (a
domain type, a short maxLength, a parent the acting role may not create). Lifecycle, invariant and `sensitive` probes
are not derived yet -- they need a way to update a row that the probe format does not have.

The REST paths are not computed here (the plural rule lives in Java): the probe carries a placeholder path and
`resolve_paths` fills it from the consumer's compiled-metadata.json `catalogs.invocations`, which names the exact
create and read paths of every concept. Stdlib only; nothing here reads markdown.
"""

from __future__ import annotations

import re
from typing import Iterable

SCHEMA_VERSION = "npdev-pack-probe.v1"
ALL = "*"                       # a rule that admits every signed-in identity
DECOY_NAME = "decoy"
DECOY_ROLE = "PROBEDECOY"        # not a role any pack names; the consumer still grants it full coarse access
OUTSIDER_STATUSES = [401, 403, 404]
MAX_PARENT_DEPTH = 3
PLACEHOLDER = re.compile(r"^/__derive__/(create|read)/(.+)$")

_LITERAL = re.compile(r"^\$user\.roles\.contains\('([A-Za-z0-9_]+)'\)$")
_LOGICAL = re.compile(r"^\$user\.roles\.contains\(role\('([A-Za-z0-9_]+)'\)\)$")


def parse_access(expr) -> frozenset | str | None:
    """The set of role names a role-only rule admits, ALL for `true`, or None when the rule is anything else.

    `role('x')` is the pack's logical role x; the workshop consumer binds it to X (compose_scratch does `r.upper()`)."""
    if not isinstance(expr, str) or not expr.strip():
        return None
    roles: set[str] = set()
    for term in (part.strip() for part in expr.split("||")):
        if term == "true":
            return ALL
        if term == "$user.isSystem":
            continue
        match = _LITERAL.match(term)
        if match:
            roles.add(match.group(1))
            continue
        match = _LOGICAL.match(term)
        if match:
            roles.add(match.group(1).upper())
            continue
        return None
    return frozenset(roles)


def _rule(concept: dict, key: str):
    """A concept's parsed rule: ALL when it declares none, a role set, or None when it is not role-only."""
    expr = (concept.get("access") or {}).get(key)
    return ALL if expr is None else parse_access(expr)


def _admits(rule, role: str) -> bool:
    return rule == ALL or (isinstance(rule, frozenset) and role in rule)


def _bound(field: dict, key: str, default):
    value = field.get(key)
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else default


def _is_reference(field: dict) -> bool:
    return field.get("type") == "reference"


def _needs_value(field: dict) -> bool:
    return bool(field.get("required")) and not field.get("id") and field.get("name") != "id" \
        and "default" not in field and "defaultExpression" not in field


def build_body(concept: dict, ref_vars: dict | None = None) -> tuple[dict | None, str]:
    """A create body from the declaration alone, or (None, why not). Only required fields without a default are sent;
    a required reference takes `${var}` from `ref_vars` (the id a parent step saved)."""
    ref_vars = ref_vars or {}
    body: dict = {}
    for field in concept.get("fields") or []:
        name, kind = field.get("name"), field.get("type")
        if not _needs_value(field):
            continue
        if kind == "string":
            if _bound(field, "maxLength", 1000) < 9:
                return None, f"field {name} allows fewer than 9 characters"
            body[name] = "p${rand}"
        elif kind == "enum":
            values = field.get("enumValues") or []
            if not values:
                return None, f"enum field {name} lists no values"
            body[name] = values[0]
        elif kind == "boolean":
            body[name] = True
        elif kind in ("integer", "int", "long"):
            low, high = _bound(field, "min", 1), _bound(field, "max", 10**6)
            body[name] = int(max(low, 1)) if max(low, 1) <= high else int(low)
        elif kind == "decimal":
            low, high = _bound(field, "min", 1), _bound(field, "max", 10**6)
            body[name] = float(max(low, 1)) if max(low, 1) <= high else float(low)
        elif kind == "date":
            body[name] = "2026-01-15"
        elif kind == "datetime":
            body[name] = "2026-01-15T10:00:00Z"
        elif _is_reference(field) and name in ref_vars:
            body[name] = "${" + ref_vars[name] + "}"
        else:
            return None, f"required field {name} has type {kind!r}, which needs a row this module cannot make"
    return body, ""


def _find(ref: str, pack_id: str, packs: dict) -> tuple[str, dict] | None:
    """`Warehouse` (this pack) or `products::Product` (a composed pack) -> (pack id, concept)."""
    owner, name = ref.split("::", 1) if "::" in ref else (pack_id, ref)
    for concept in (packs.get(owner) or {}).get("concepts") or []:
        if isinstance(concept, dict) and concept.get("name") == name:
            return owner, concept
    return None


def _create_steps(pack_id: str, concept: dict, packs: dict, actor: dict, counter: list, save_as: str, depth: int,
                  body_why: list) -> list[dict] | None:
    """The steps that create `concept` as `actor`, parents first. None (and a reason in body_why) when it cannot be done."""
    ref_vars: dict[str, str] = {}
    steps: list[dict] = []
    for field in concept.get("fields") or []:
        if not (_needs_value(field) and _is_reference(field)):
            continue
        target = (field.get("reference") or {}).get("target") or ""
        found = _find(target, pack_id, packs)
        if found is None or depth >= MAX_PARENT_DEPTH:
            body_why.append(f"required reference {field['name']} -> {target or '?'} cannot be resolved to a concept it can create")
            return None
        owner, parent = found
        if not _admits(_rule(parent, "write"), actor["role"]):
            body_why.append(f"the parent {owner}::{parent['name']} is not creatable by {actor['role']} (its access.write is "
                            f"not role-only or does not name that role)")
            return None
        counter[0] += 1
        var = f"parent{counter[0]}"
        sub = _create_steps(owner, parent, packs, actor, counter, var, depth + 1, body_why)
        if sub is None:
            return None
        steps.extend(sub)
        ref_vars[field["name"]] = var
    body, why = build_body(concept, ref_vars)
    if body is None:
        body_why.append(why)
        return None
    label = f"{pack_id}::{concept['name']}"
    steps.append({"name": f"{actor['name']} creates a {label}", "as": actor["name"], "method": "POST",
                  "path": f"/__derive__/create/{label}", "body": body, "expect": {"status": [200, 201]},
                  "save": {save_as: "id"}})
    return steps


def derive_concept(pack_id: str, concept: dict, packs: dict | None = None) -> tuple[dict | None, str]:
    """One probe document for one concept, or (None, why it was skipped)."""
    packs = packs or {}
    name = concept.get("name")
    write, read = _rule(concept, "write"), _rule(concept, "read")
    if write is None:
        return None, "access.write is not a role-only rule (it uses && , parentheses, a field or an unknown term)"
    if read is None:
        return None, "access.read is not a role-only rule (it uses && , parentheses, a field or an unknown term)"
    if write != ALL and not write:
        return None, "access.write admits no role the probe can act as"
    writer = DECOY_ROLE if write == ALL else sorted(write)[0]
    users = [{"name": writer.lower(), "role": writer}, {"name": DECOY_NAME, "role": DECOY_ROLE}]
    if write == ALL:
        users = [{"name": DECOY_NAME, "role": DECOY_ROLE}]
    actor = users[0]
    packs = dict(packs)
    packs.setdefault(pack_id, {"concepts": [concept]})
    why: list[str] = []
    steps = _create_steps(pack_id, concept, packs, actor, [0], "rowId", 0, why)
    if steps is None:
        return None, why[-1]
    label = f"{pack_id}::{name}"
    if _admits(read, writer):   # reading back is only a fair expectation when the pack admits this role to read
        steps.append({"name": f"{actor['name']} reads the {label} back", "as": actor["name"], "method": "GET",
                      "path": f"/__derive__/read/{label}", "expect": {"status": 200}})
    if write != ALL:
        own = next(s for s in reversed(steps) if s["method"] == "POST")
        # a DIFFERENT text value from the writer's row: with the same one a unique field answers 409 and that would hide
        # whether the access rule refused the decoy or not
        decoy_body = {k: (v.replace("p${rand}", "d${rand}") if isinstance(v, str) else v) for k, v in own["body"].items()}
        steps.append({"name": f"a role the pack does not name cannot create a {label}", "as": DECOY_NAME, "method": "POST",
                      "path": f"/__derive__/create/{label}", "body": decoy_body, "expect": {"status": OUTSIDER_STATUSES}})
    if isinstance(read, frozenset) and read:
        steps.append({"name": f"a role the pack does not name cannot read the {label}", "as": DECOY_NAME, "method": "GET",
                      "path": f"/__derive__/read/{label}", "expect": {"status": OUTSIDER_STATUSES}})
    return {"schemaVersion": SCHEMA_VERSION, "pack": pack_id, "users": users, "steps": steps,
            "description": f"DERIVED from {pack_id}'s own declaration of {name} (access rules, required fields); not written "
                           f"by the authoring AI."}, ""


def derive(pack_id: str, pack_doc: dict, packs: dict | None = None) -> tuple[dict[str, dict], list[str]]:
    """{file name: probe doc} for the pack's own concepts, plus one line per concept that could not be derived.
    `packs` is {pack id: pack.json doc} of the composed packs, so a parent that lives in a dependency can be created."""
    known = dict(packs or {})
    known[pack_id] = pack_doc
    docs: dict[str, dict] = {}
    skipped: list[str] = []
    for concept in pack_doc.get("concepts") or []:
        if not isinstance(concept, dict) or "name" not in concept:
            continue
        doc, why = derive_concept(pack_id, concept, known)
        if doc is None:
            skipped.append(f"{concept['name']}: {why}")
        else:
            docs[f"derived-{concept['name']}.json"] = doc
    return docs, skipped


def resolve_paths(doc: dict, invocations: Iterable[dict]) -> str:
    """Fill every placeholder path in `doc` (in place) from compiled-metadata `catalogs.invocations`.
    Returns '' on success, else the reason (a concept the metadata does not know)."""
    create, read = {}, {}
    for entry in invocations:
        concept = entry.get("concept") or ""
        if entry.get("kind") == "createDirect":
            create[concept] = entry.get("path")
        elif entry.get("kind") == "read":
            read[concept] = entry.get("path")
    for step in doc["steps"]:
        match = PLACEHOLDER.match(step["path"])
        if not match:
            continue
        kind, label = match.groups()
        path = (create if kind == "create" else read).get(label)
        if not path:
            return f"the consumer's metadata has no {kind} route for {label}"
        # the row a step reads is the one the LAST create saved as rowId; a parent's id is its own variable
        step["path"] = path.replace("{id}", "${rowId}")
    return ""
