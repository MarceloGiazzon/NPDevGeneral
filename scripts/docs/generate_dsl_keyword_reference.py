#!/usr/bin/env python
"""Renders docs/DSL_KEYWORD_REFERENCE.md: EVERY keyword of the model DSL, what it means and where it may be used.

The document is generated, never hand-edited. Its sources, all structured data (no script reads markdown):

  * NPDevContract/schemas/model.schema.json  -- the keyword set, types, enums, required flags, defaults and the
    schema's own `description` text.
  * NPDevContract/schemas/pack.schema.json   -- the pack-file keywords.
  * scripts/policy/dsl-keyword-meanings/*.json -- the hand-written English meaning of every keyword whose schema
    node carries no `description`, the grouping of the `$defs` under the twelve nouns of ADR-0013, def-level
    summaries, legacy/retiring status and minimal examples.

THE KEYWORD SET IS THE BUDGET'S SLOT SET. A "slot" is counted exactly as scripts/quality/check-dsl-keyword-budget.py
counts it (every property declared directly under a model `$defs` entry, `<def>.<property>`, plus every top-level
`pack.schema.json` property, `pack:<property>`); this renderer imports that checker's own `measure()` so the two can
never disagree. The model's root properties (not slots, they are the top level of a model file) are documented in
their own section, ids `root:<property>`.

Staleness: scripts may not read markdown, so this records the SHA-256 of the render in
scripts/policy/dsl-keyword-reference-stamp.json and scripts/quality/check-dsl-keyword-reference.py re-renders in
memory and compares it to that stamp (the same pattern as docs/reference/ENGINE_MATRIX.md).

Usage: python scripts/docs/generate_dsl_keyword_reference.py
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path

WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
MODEL_SCHEMA = WORKSPACE_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
PACK_SCHEMA = WORKSPACE_ROOT / "NPDevContract" / "schemas" / "pack.schema.json"
MEANINGS_DIR = WORKSPACE_ROOT / "scripts" / "policy" / "dsl-keyword-meanings"
DOC_PATH = WORKSPACE_ROOT / "docs" / "DSL_KEYWORD_REFERENCE.md"
STAMP_PATH = WORKSPACE_ROOT / "scripts" / "policy" / "dsl-keyword-reference-stamp.json"
BUDGET_CHECKER = WORKSPACE_ROOT / "scripts" / "quality" / "check-dsl-keyword-budget.py"


# --------------------------------------------------------------------------- inputs

def _load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_schemas() -> tuple[dict, dict]:
    return _load_json(MODEL_SCHEMA), _load_json(PACK_SCHEMA)


def measure_slots(model: dict, pack: dict) -> set[str]:
    """The budget's own enumeration (check-dsl-keyword-budget.py `measure`), imported, not copied."""
    spec = importlib.util.spec_from_file_location("npdev_keyword_budget", BUDGET_CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.measure(model, pack)


def load_meanings(directory: Path = MEANINGS_DIR) -> dict:
    """Merge layout.json and every meanings-*.json: {layout, slots, defs, inline}."""
    merged: dict = {"layout": {}, "slots": {}, "defs": {}, "inline": {}}
    layout = directory / "layout.json"
    merged["layout"] = _load_json(layout)
    for path in sorted(directory.glob("meanings-*.json")):
        part = _load_json(path)
        for key in ("slots", "defs", "inline"):
            for name, value in (part.get(key) or {}).items():
                if name in merged[key]:
                    raise ValueError(f"{path.name}: duplicate {key} entry {name!r}")
                merged[key][name] = value
    return merged


# --------------------------------------------------------------------------- schema helpers

def _slug(heading: str) -> str:
    text = heading.strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def def_heading(name: str) -> str:
    return f"`{name}`"


def def_anchor(name: str) -> str:
    return _slug(name)


def _esc(text: str) -> str:
    """Make text safe inside a markdown table cell: escape pipes, and angle brackets outside code spans."""
    pieces = text.replace("\n", " ").strip().split("`")
    for index, piece in enumerate(pieces):
        piece = piece.replace("|", "\\|")
        if index % 2 == 0:
            piece = piece.replace("<", "&lt;").replace(">", "&gt;")
        pieces[index] = piece
    return "`".join(pieces)


def _collect_refs(node, acc: set[str]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                acc.add(value.rsplit("/", 1)[-1])
            elif key not in ("properties", "$defs"):
                _collect_refs(value, acc)
    elif isinstance(node, list):
        for item in node:
            _collect_refs(item, acc)


def build_usage(model: dict, pack: dict) -> dict[str, list[str]]:
    """def name -> where it is used: root keys, pack-file keys, and `<def>.<property>` of parent defs."""
    users: dict[str, list[str]] = {}

    def add(target: str, where: str) -> None:
        users.setdefault(target, [])
        if where not in users[target]:
            users[target].append(where)

    for def_name, definition in model.get("$defs", {}).items():
        for prop, node in (definition.get("properties") or {}).items():
            acc: set[str] = set()
            _collect_refs(node, acc)
            for ref in sorted(acc):
                add(ref, f"{def_name}.{prop}")
        extra: set[str] = set()
        for key, value in definition.items():
            if key != "properties":
                _collect_refs(value, extra)
        for ref in sorted(extra):
            add(ref, f"{def_name} (whole object)")
    for prop, node in (model.get("properties") or {}).items():
        acc = set()
        _collect_refs(node, acc)
        for ref in sorted(acc):
            add(ref, f"root:{prop}")
    for prop, node in (pack.get("properties") or {}).items():
        acc = set()
        _collect_refs(node, acc)
        for ref in sorted(acc):
            add(ref, f"pack:{prop}")
    return users


def fmt_usage(places: list[str]) -> str:
    """Join the places a kind is used; a key present both at the model root and in a pack file is said once."""
    root = {w[5:] for w in places if w.startswith("root:")}
    packs = {w[5:] for w in places if w.startswith("pack:")}
    both = root & packs
    out: list[str] = []
    for w in places:
        if w.startswith("root:") and w[5:] in both:
            out.append(f"`{w[5:]}` (model root and pack file)")
        elif w.startswith("pack:") and w[5:] in both:
            continue
        else:
            out.append(_fmt_where(w))
    return ", ".join(out)


def _fmt_where(where: str) -> str:
    if where.startswith("root:"):
        return f"`{where[5:]}` (model root)"
    if where.startswith("pack:"):
        return f"`{where[5:]}` (pack file)"
    if where.endswith(" (whole object)"):
        return f"`{where[:-15]}` (whole object)"
    return f"`{where}`"


def _value(v) -> str:
    return json.dumps(v, ensure_ascii=False)


def type_md(prop: dict, documented: set[str]) -> str:
    """Markdown for a property's type (links to the `$defs` section for a $ref)."""
    if "$ref" in prop:
        name = prop["$ref"].rsplit("/", 1)[-1]
        return f"[`{name}`](#{def_anchor(name)})" if name in documented else f"`{name}`"
    if "oneOf" in prop:
        rendered: list[str] = []
        for option in prop["oneOf"]:
            text = type_md(option, documented)
            if text not in rendered:
                rendered.append(text)
        if len(rendered) == 1 and len(prop["oneOf"]) > 1:
            return f"{rendered[0]} (one of {len(prop['oneOf'])} alternative shapes)"
        return " or ".join(rendered)
    if "if" in prop and "then" in prop and "else" in prop:
        return f"{type_md(prop['else'], documented)} or {type_md(prop['then'], documented)} (a `$ref` to a local JSON file)"
    if "const" in prop:
        return f"fixed `{_value(prop['const'])}`"
    if "enum" in prop:
        return "one of " + ", ".join(f"`{v}`" for v in prop["enum"])
    ptype = prop.get("type")
    if ptype == "array":
        return "array of " + type_md(prop.get("items", {}), documented)
    if ptype == "object":
        addl = prop.get("additionalProperties")
        if isinstance(addl, dict):
            return "map of name to " + type_md(addl, documented)
        return "object"
    if isinstance(ptype, list):
        return " or ".join(f"`{t}`" for t in ptype)
    if ptype:
        return f"`{ptype}`"
    return "any value"


def constraints_md(prop: dict) -> str:
    bits: list[str] = []
    if "default" in prop:
        bits.append(f"default `{_value(prop['default'])}`")
    for key, label in (("minimum", "min"), ("maximum", "max"), ("minLength", "min length"),
                       ("maxLength", "max length"), ("minItems", "min items")):
        if key in prop:
            bits.append(f"{label} {prop[key]}")
    if "pattern" in prop:
        bits.append(f"pattern `{prop['pattern']}`" if len(prop["pattern"]) <= 60 else "must match the schema's pattern")
    items = prop.get("items")
    if isinstance(items, dict) and "enum" in items:
        bits.append("items one of " + ", ".join(f"`{v}`" for v in items["enum"]))
    return "; ".join(bits)


def inline_objects(prop: dict) -> list[dict]:
    """Inline object schemas (not `$ref`s) nested in a property: the keys written inside it."""
    found: list[dict] = []

    def walk(node: dict) -> None:
        if not isinstance(node, dict):
            return
        if node.get("properties"):
            found.append(node)
            return
        if isinstance(node.get("items"), dict):
            walk(node["items"])
        for option in node.get("oneOf", []) or []:
            walk(option)
        addl = node.get("additionalProperties")
        if isinstance(addl, dict):
            walk(addl)

    walk(prop)
    return found


def _inline_md(slot: str, prop: dict, meanings: dict, documented: set[str]) -> str:
    parts = _inline_parts(slot, prop, meanings, documented)
    return ("Keys written inside it: " + " ; ".join(parts)) if parts else ""


def _inline_parts(slot: str, prop: dict, meanings: dict, documented: set[str]) -> list[str]:
    """One entry per key written inside an inline object of the property (unescaped markdown)."""
    parts: list[str] = []
    seen: set[str] = set()
    for obj in inline_objects(prop):
        required = set(obj.get("required", []))
        for sub, node in obj["properties"].items():
            text = scrub(meanings["inline"].get(f"{slot}.{sub}") or node.get("description") or "")
            kind = re.sub(r"\s+", " ", type_md(node, documented))
            cons = constraints_md(node)
            req = "required" if sub in required else "optional"
            head = f"`{sub}` ({kind}; {req}{'; ' + cons if cons else ''})"
            entry = f"{head}: {text}" if text else head
            if entry not in seen:
                seen.add(entry)
                parts.append(entry)
    return parts


# --------------------------------------------------------------------------- meaning resolution

_TICKET_PREFIX = re.compile(
    r"^(?:FORM P\d+(?:-T\d+[a-z]?)?|AI7-1|AIP-3|AGENT-1|B\d+(?:/B\d+)?|F6-\d(?: / F6-\d)?|Friction #\d|INT-1|LIFT-LOOP"
    r"|LNCH-\d+|MOB-2|Move \d+(?: Move [A-Z]| W\d| B\d)?|P3\.4|P4(?:\.1)?|P6|P8(?: prelude)?|PACK-\d+(?:/R\d+\.\d+)?"
    r"|PK-\d|PROC-\d(?:/PROC-\d)?|Path A P0\.4|R\d+\.\d+|REG-\d+|S7 B13[^:(]*|S8 W1\.2|Session \d+|W3\.1|WMS-16|Wave \d+)"
    r"(?:\s*\([^)]*\))?(?:\s*(?:Step \d|Stage [A-C/]+))?\s*:\s+")
_DOC_PAREN = re.compile(r"\s*\((?:[^()]*\.md[^()]*)\)")
_TAG_PAREN = re.compile(r"\s*\((?:FORM P|LIFT-|F6-|B\d|REG-|Wave |Move |S\d|Session |AIP-)[^()]*\)")
_MID_TAG = re.compile(r"(?<=[.;] )(?:R\d+\.\d+|B1[69]|F6-\d)\s*:\s+(\w)")


def scrub(text: str) -> str:
    """Drop internal ticket/plan tags from a schema description so the page reads as product documentation."""
    text = re.sub(r"\s+", " ", text).strip()
    previous = None
    while previous != text:
        previous = text
        text = _TICKET_PREFIX.sub("", text, count=1)
    text = _DOC_PAREN.sub(lambda m: m.group(0) if "docs/adr/" in m.group(0) else "", text)
    text = _TAG_PAREN.sub("", text)
    text = _MID_TAG.sub(lambda m: m.group(1).upper(), text)
    first = text.split(" ", 1)[0] if text else ""
    return (text[:1].upper() + text[1:]) if re.fullmatch(r"[a-z]+", first.rstrip(":;,.")) else text


def meaning_of(slot: str, prop: dict, meanings: dict) -> tuple[str, str]:
    """(text, source) with source in {'data', 'schema', 'missing'}. The data file wins over a schema description."""
    if slot in meanings["slots"]:
        return meanings["slots"][slot].strip(), "data"
    text = (prop.get("description") or "").strip()
    if text:
        return scrub(text), "schema"
    return "", "missing"


def coverage(model: dict, pack: dict, meanings: dict) -> dict:
    """Meaning source for every slot and root key, plus the data entries that name nothing."""
    result = {"slots": {}, "root": {}, "missing": [], "orphans": [], "inlineMissing": []}
    known: set[str] = set()
    for def_name, definition in model.get("$defs", {}).items():
        for prop, node in (definition.get("properties") or {}).items():
            slot = f"{def_name}.{prop}"
            known.add(slot)
            _, source = meaning_of(slot, node, meanings)
            result["slots"][slot] = source
            if source == "missing":
                result["missing"].append(slot)
    for prop, node in (pack.get("properties") or {}).items():
        slot = f"pack:{prop}"
        known.add(slot)
        _, source = meaning_of(slot, node, meanings)
        result["slots"][slot] = source
        if source == "missing":
            result["missing"].append(slot)
    for prop, node in (model.get("properties") or {}).items():
        slot = f"root:{prop}"
        known.add(slot)
        _, source = meaning_of(slot, node, meanings)
        result["root"][slot] = source
        if source == "missing":
            result["missing"].append(slot)
    for slot in meanings["slots"]:
        if slot not in known:
            result["orphans"].append(slot)
    # inline sub-keys
    inline_known: set[str] = set()
    containers = [(f"{d}.{p}", n) for d, dv in model.get("$defs", {}).items() for p, n in (dv.get("properties") or {}).items()]
    containers += [(d, dv) for d, dv in model.get("$defs", {}).items() if not dv.get("properties")]
    containers += [(f"pack:{p}", n) for p, n in (pack.get("properties") or {}).items()]
    containers += [(f"root:{p}", n) for p, n in (model.get("properties") or {}).items()]
    for slot, node in containers:
        for obj in inline_objects(node):
            for sub, subnode in obj["properties"].items():
                inline_known.add(f"{slot}.{sub}")
                if not (meanings["inline"].get(f"{slot}.{sub}") or subnode.get("description")):
                    result["inlineMissing"].append(f"{slot}.{sub}")
    for key in meanings["inline"]:
        if key not in inline_known:
            result["orphans"].append(f"inline:{key}")
    return result


# --------------------------------------------------------------------------- rendering

def _names(items) -> str:
    return ", ".join(f"`{n}`" for n in items)


def _cond_text(cond: dict) -> str:
    parts: list[str] = []
    for key, node in (cond.get("properties") or {}).items():
        if "const" in node:
            parts.append(f"`{key}` is `{node['const']}`")
        elif "enum" in node:
            parts.append(f"`{key}` is one of {_names(node['enum'])}")
    if cond.get("required"):
        req = cond["required"]
        parts.append(f"{_names(req)} {'is' if len(req) == 1 else 'are all'} present")
    if cond.get("anyOf"):
        parts.append(" or ".join(_cond_text(c) for c in cond["anyOf"]))
    return " and ".join(p for p in parts if p)


def _then_text(node: dict) -> list[str]:
    out: list[str] = []
    if node.get("required"):
        out.append(f"requires {_names(node['required'])}")
    for key in ("anyOf", "oneOf"):
        if node.get(key):
            alts = [_names(a["required"]) for a in node[key] if a.get("required")]
            if alts:
                word = "at least one of" if key == "anyOf" else "exactly one of"
                out.append(f"requires {word} " + ", ".join(alts))
    for sub in node.get("allOf", []) or []:
        out.extend(_then_text(sub))
    for key, spec in (node.get("properties") or {}).items():
        if spec is False:
            out.append(f"`{key}` is not allowed")
        elif isinstance(spec, dict):
            if "const" in spec:
                out.append(f"`{key}` must be `{_value(spec['const'])}`")
            elif "enum" in spec:
                out.append(f"`{key}` must be one of {_names(spec['enum'])}")
            elif spec.get("type"):
                out.append(f"`{key}` must be of type {spec['type']}")
            elif "minItems" in spec:
                out.append(f"`{key}` needs at least {spec['minItems']} {'entry' if spec['minItems'] == 1 else 'entries'}")
            elif isinstance(spec.get("properties"), dict):
                for sk, sv in spec["properties"].items():
                    if "enum" in sv:
                        out.append(f"each `{key}` entry has `{sk}` one of {_names(sv['enum'])}")
    return out


def conditional_rules(definition: dict) -> list[str]:
    """Plain-English lines for the def-level allOf / anyOf / oneOf rules of an object kind."""
    lines: list[str] = []
    for clause in definition.get("allOf", []) or []:
        if "if" in clause:
            when = _cond_text(clause["if"])
            then = "; ".join(_then_text(clause.get("then", {})))
            if when and then:
                lines.append(f"When {when}: {then}.")
        elif "not" in clause:
            text = (clause.get("description") or "").strip()
            lines.append(text if text else f"{_names(clause['not'].get('required', []))} may not all be present together.")
        elif "oneOf" in clause or "anyOf" in clause:
            for line in _then_text(clause):
                lines.append(line[0].upper() + line[1:] + ".")
    for key in ("anyOf", "oneOf"):
        alts = [a for a in definition.get(key, []) or [] if a.get("required")]
        if alts and "properties" in definition:
            word = "At least one of" if key == "anyOf" else "Exactly one of"
            lines.append(f"{word} " + ", ".join(_names(a["required"]) for a in alts) + " must be present.")
    return lines


def _row(slot: str, name: str, prop: dict, required: set[str], meanings: dict, documented: set[str]) -> str:
    text, _ = meaning_of(slot, prop, meanings)
    type_cell = type_md(prop, documented)
    cons = constraints_md(prop)
    if cons:
        type_cell += f" ({cons})"
    parts = _inline_parts(slot, prop, meanings, documented)
    body = _esc(text) if text else "(no description)"
    if parts:
        body += "<br>Keys written inside it:<br>" + "<br>".join(_esc(part) for part in parts)
    req = "required" if name in required else "optional"
    return f"| `{name}` | {_esc(type_cell)} | {req} | {body} |"


def _forms_md(def_name: str, definition: dict, meanings: dict, documented: set[str]) -> list[str]:
    """A kind without properties: list the forms it accepts (a string, an object with inline keys, an enum)."""
    lines: list[str] = []
    if "enum" in definition:
        lines += ["Value: " + type_md({"enum": definition["enum"]}, documented) + ".", ""]
    branches = definition.get("oneOf") or []
    if branches:
        lines += ["Accepted forms:", ""]
        for branch in branches:
            if branch.get("type") == "object":
                keys = _inline_md(def_name, branch, meanings, documented)
                lines.append(f"- an object. {_esc(keys)}")
            else:
                note = (branch.get("description") or "").strip()
                lines.append(f"- {type_md(branch, documented)}" + (f": {_esc(note)}" if note else ""))
        lines.append("")
    return lines


TABLE_HEAD = ["| Keyword | Type | Required | Meaning |", "|---|---|---|---|"]


def _def_section(def_name: str, definition: dict, meanings: dict, usage: dict, documented: set[str]) -> list[str]:
    info = meanings["defs"].get(def_name, {})
    lines = [f"### {def_heading(def_name)}", ""]
    status = info.get("status")
    if status:
        lines += [f"> **{status['label']}.** {status['note']}", ""]
    summary = info.get("summary") or scrub(definition.get("description") or "")
    if summary:
        lines += [_esc(summary), ""]
    where = usage.get(def_name, [])
    if where:
        lines += ["**Used in:** " + fmt_usage(where), ""]
    else:
        lines += ["**Used in:** not referenced by another schema node (reachable only through the parent noted above).", ""]
    required = set(definition.get("required", []))
    properties = definition.get("properties") or {}
    rules = conditional_rules(definition)
    if rules:
        lines += ["**Conditional rules:**", ""] + [f"- {_esc(rule)}" for rule in rules] + [""]
    if properties:
        lines += TABLE_HEAD
        for prop, node in properties.items():
            lines.append(_row(f"{def_name}.{prop}", prop, node, required, meanings, documented))
        lines.append("")
    else:
        forms = _forms_md(def_name, definition, meanings, documented)
        lines += forms if forms else ["No keywords of its own; it is a plain value (see the tables that reference it).", ""]
    example = info.get("example")
    if example is not None:
        lines += ["Example:", "", "```json", json.dumps(example, indent=2, ensure_ascii=False), "```", ""]
    return lines


def render(model: dict, pack: dict, meanings: dict) -> str:
    layout = meanings["layout"]
    defs = model.get("$defs", {})
    nouns = layout["nouns"]
    placed = [d for noun in nouns for d in noun["defs"]]
    documented = set(placed)
    missing_from_layout = [d for d in defs if d not in documented]
    if missing_from_layout:
        raise ValueError(f"layout.json places no section for $defs: {missing_from_layout}")
    unknown = [d for d in placed if d not in defs]
    if unknown:
        raise ValueError(f"layout.json names $defs the schema does not have: {unknown}")
    if len(placed) != len(set(placed)):
        raise ValueError("layout.json places a $defs entry twice")

    slots = measure_slots(model, pack)
    usage = build_usage(model, pack)
    cov = coverage(model, pack, meanings)
    from_data = sum(1 for s in slots if cov["slots"].get(s) == "data")
    from_schema = sum(1 for s in slots if cov["slots"].get(s) == "schema")
    model_slots = sum(1 for s in slots if not s.startswith("pack:"))
    pack_slots = len(slots) - model_slots

    out: list[str] = []
    out += [
        "# NPDev DSL keyword reference",
        "",
        "<!-- GENERATED by scripts/docs/generate_dsl_keyword_reference.py from NPDevContract/schemas/model.schema.json, "
        "NPDevContract/schemas/pack.schema.json and scripts/policy/dsl-keyword-meanings/*.json. Do not edit by hand: "
        "edit the schema or the meanings data, then run the script. -->",
        "",
        "The complete list of keywords an NPDev model (`model.json`) or pack (`pack.json`) may use: what each one means, "
        "where it may appear, whether it is required, and which values it accepts. Every keyword of the DSL is here; a "
        "key that is not here is rejected by the schema (`additionalProperties: false`).",
        "",
        f"Schema version `{model.get('version', 'unknown')}`, DSL version `{model['properties']['dslVersion']['const']}`. "
        f"**{len(slots)} keywords** are documented: {model_slots} declared by the model schema "
        f"({len(defs)} object kinds) and {pack_slots} by the pack schema, counted exactly as "
        "`scripts/quality/check-dsl-keyword-budget.py` counts them. The model root keys are listed separately in section 0.",
        "",
        "## How to read this reference",
        "",
        "- A model is a tree of **object kinds**. Each kind below is one `$defs` entry of the schema and has its own "
        "table of keywords. The heading is the kind's schema name; **Used in** says which parent keyword holds it "
        "(`concept.fields` means the `fields` array of a `concept`; `root:flows` / `flows` means the top-level `flows` array of "
        "a model file; `pack:` means a pack file).",
        "- **Required** is the JSON Schema `required` list of that kind. Some kinds add an either/or rule the schema "
        "expresses with `oneOf`/`allOf`; those are stated in the kind's summary.",
        "- **Type** names the JSON shape. A name in brackets is another object kind (a link to its section). "
        "\"one of\" lists the closed set of allowed values. Any array of object kinds at the model root also accepts "
        "`{\"$ref\": \"path/to/file.json\"}` entries that pull the item from a local file.",
        "- Entries marked *Legacy* or *Retiring* still validate but are on their way out; do not use them in new models.",
        "- Expressions (`where`, `visibleWhen`, `defaultExpression`, ...) use the one NPDev expression language "
        "(`docs/EXPRESSIONS.md`). Flows are described in `docs/FLOWS.md`, screens in `docs/SCREEN_TAXONOMY.md` and "
        "`docs/UI_CONTRACT.md`, packs in `docs/PACK_DESIGN.md`.",
        "",
        "## Contents",
        "",
        "- [0. Model root and pack file](#0-model-root-and-pack-file)",
    ]
    for index, noun in enumerate(nouns, start=1):
        title = f"{index}. {noun['title']}"
        out.append(f"- [{title}](#{_slug(title)}): " + ", ".join(f"[`{d}`](#{def_anchor(d)})" for d in noun["defs"]))
    out += ["", "## 0. Model root and pack file", ""]
    out += [re.sub(r"\s+", " ", layout["rootIntro"]), ""]

    out += ["### Model root keys", ""]
    root_required = set(model.get("required", []))
    for clause in model.get("allOf", []) or []:
        root_required |= set(clause.get("required", []))
    out += TABLE_HEAD
    for prop, node in (model.get("properties") or {}).items():
        out.append(_row(f"root:{prop}", prop, node, root_required, meanings, documented))
    out.append("")

    out += ["### Pack file keys", ""]
    out += [re.sub(r"\s+", " ", layout["packIntro"]), ""]
    out += TABLE_HEAD
    pack_required = set(pack.get("required", []))
    for prop, node in (pack.get("properties") or {}).items():
        out.append(_row(f"pack:{prop}", prop, node, pack_required, meanings, documented))
    out.append("")

    for index, noun in enumerate(nouns, start=1):
        out += [f"## {index}. {noun['title']}", ""]
        out += [re.sub(r"\s+", " ", noun["intro"]), ""]
        for def_name in noun["defs"]:
            out += _def_section(def_name, defs[def_name], meanings, usage, documented)

    out += [
        "## Coverage",
        "",
        f"- Keywords documented: {len(slots)} ({model_slots} model + {pack_slots} pack), the same set the keyword budget freezes.",
        f"- Meaning taken from the schema's own `description`: {from_schema}.",
        f"- Meaning written in `scripts/policy/dsl-keyword-meanings/`: {from_data}.",
        "- `scripts/quality/check-dsl-keyword-reference.py` fails when a keyword has no meaning, when the meanings data names "
        "a keyword the schema no longer has, or when this page is stale against the schema.",
        "",
    ]
    return "\n".join(out).rstrip("\n") + "\n"


def render_sha256(model: dict, pack: dict, meanings: dict) -> str:
    return hashlib.sha256(render(model, pack, meanings).encode("utf-8")).hexdigest()


def main() -> int:
    model, pack = load_schemas()
    meanings = load_meanings()
    cov = coverage(model, pack, meanings)
    if cov["missing"] or cov["orphans"]:
        print("keyword meanings are incomplete or stale:", file=sys.stderr)
        for slot in cov["missing"]:
            print(f"  no meaning: {slot}", file=sys.stderr)
        for slot in cov["orphans"]:
            print(f"  names nothing in the schema: {slot}", file=sys.stderr)
        return 1
    text = render(model, pack, meanings)
    DOC_PATH.write_bytes(text.encode("utf-8"))
    STAMP_PATH.write_bytes((json.dumps({"renderSha256": hashlib.sha256(text.encode("utf-8")).hexdigest()}, indent=2) + "\n").encode("utf-8"))
    print(f"wrote {DOC_PATH.relative_to(WORKSPACE_ROOT)} and {STAMP_PATH.relative_to(WORKSPACE_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
