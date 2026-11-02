"""Desire paths: rewrite the shapes AI authors keep guessing into canonical model.json.

Every rule here comes from `scripts/ai/bench/run_bench.py mine` -- a construct several models wrote
the same wrong way, where the canonical meaning is unambiguous. The rewrite is deterministic and
fully reported: each change is one {"rule", "path", "wrote", "now"} entry, so a human (or the AI on
its next turn) sees exactly what NPDev changed. Anything without one clear canonical reading is left
alone for the validator to report -- above all anything security-relevant (a flow's requiredRole is
never dropped).
"""

from __future__ import annotations

import copy
import json
import re
from pathlib import Path
from typing import Any

MODE_SYNONYMS = {
    "list": "query", "read": "query", "get": "query", "find": "query", "search": "query",
    "browse": "query", "view": "query", "lookup": "query", "fetch": "query", "retrieve": "query",
    "add": "create", "new": "create", "insert": "create",
    "edit": "update", "patch": "update", "modify": "update",
    "remove": "delete", "destroy": "delete",
}

# role operation verbs -> the four the DSL knows. Anything not listed reads as a state change of an
# existing row (approve, resolve, decide...), which is an update.
READ_VERBS = {"read", "list", "view", "get", "search", "find", "query", "browse"}
CREATE_VERBS = {"create", "add", "new", "insert", "submit", "report", "open", "request"}
DELETE_VERBS = {"delete", "remove", "destroy"}
CRUD_ORDER = ["read", "create", "update", "delete"]


def _key(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", str(name).lower())


def _op(verb: str) -> str:
    v = _key(verb.split("-")[0].split("_")[0]) if verb else ""
    if v in READ_VERBS:
        return "read"
    if v in CREATE_VERBS:
        return "create"
    if v in DELETE_VERBS:
        return "delete"
    return "update"


def _ops(verbs) -> list[str]:
    found = {_op(str(v)) for v in verbs if str(v).strip()}
    return [o for o in CRUD_ORDER if o in found]


class _Concepts:
    def __init__(self, model: dict):
        self.names = [c["name"] for c in model.get("concepts", []) or []
                      if isinstance(c, dict) and isinstance(c.get("name"), str)]

    def match(self, written: str) -> str | None:
        """Exact (case/punctuation-insensitive), else the one concept the word prefixes or is in."""
        k = _key(written)
        if not k:
            return None
        for name in self.names:
            if _key(name) == k:
                return name
        for test in (lambda n: n.startswith(k), lambda n: k in n):
            hits = [n for n in self.names if test(_key(n))]
            if len(hits) == 1:
                return hits[0]
        return None


def fix_model(model: Any) -> tuple[Any, list[dict]]:
    """Return (a fixed deep copy, the list of fixes). A non-object model comes back untouched."""
    if not isinstance(model, dict):
        return model, []
    model = copy.deepcopy(model)
    fixes: list[dict] = []

    def note(rule: str, path: str, wrote, now) -> None:
        fixes.append({"rule": rule, "path": path, "wrote": wrote, "now": now})

    concepts = _Concepts(model)
    _fix_flow_modes(model, note)
    _fix_roles(model, concepts, note)
    _fix_lifecycles(model, note)
    _fix_data_sources(model, concepts, note)
    _fix_queries(model, note)
    _fix_rollups(model, note)
    _fix_orchestrations(model, note)
    _fix_field_types(model, note)
    _fix_field_derived(model, note)
    _fix_flow_steps(model, note)
    return model, fixes


def _fix_flow_modes(model: dict, note) -> None:
    for i, flow in enumerate(model.get("flows") or []):
        inp = flow.get("input") if isinstance(flow, dict) else None
        if not isinstance(inp, dict) or not isinstance(inp.get("mode"), str):
            continue
        written = inp["mode"]
        canonical = MODE_SYNONYMS.get(written.strip().lower())
        if canonical:
            inp["mode"] = canonical
            note("flow-input-mode", f"$.flows[{i}].input.mode", written, canonical)


def _fix_roles(model: dict, concepts: _Concepts, note) -> None:
    for i, role in enumerate(model.get("roles") or []):
        if not isinstance(role, dict):
            continue
        base = f"$.roles[{i}]"
        if "name" not in role:
            for alias in ("roleId", "id", "role", "roleName"):
                if isinstance(role.get(alias), str):
                    role["name"] = role.pop(alias)
                    note("role-name", f"{base}.{alias}", alias, "name")
                    break
        if "description" in role:
            note("role-description", f"{base}.description", role.pop("description"), None)

        access: dict[str, set] = {}
        raw = role.get("concepts")
        if isinstance(raw, list):
            role.pop("concepts")
            if all(isinstance(x, str) for x in raw):
                # ["read","create"] with no concept: the role's operations on every concept
                for name in concepts.names:
                    access.setdefault(name, set()).update(_ops(raw))
            else:
                for entry in raw:
                    if not isinstance(entry, dict):
                        continue
                    name = concepts.match(entry.get("concept") or entry.get("name") or "")
                    verbs = entry.get("permissions") or entry.get("operations") or entry.get("ops") or []
                    if name:
                        access.setdefault(name, set()).update(_ops(verbs))
            note("role-concepts-list", f"{base}.concepts", raw, None)
        elif isinstance(raw, dict):
            for written, verbs in raw.items():
                name = concepts.match(written) or written
                verbs = verbs if isinstance(verbs, list) else [verbs] if isinstance(verbs, str) else []
                ops = _ops(verbs)
                access.setdefault(name, set()).update(ops)
                if name != written:
                    note("role-concept-name", f"{base}.concepts.{written}", written, name)
                if sorted(map(str, verbs)) != sorted(ops):
                    note("role-concept-ops", f"{base}.concepts.{written}", verbs, ops)

        # "concept:verb" strings, in permissions[] or wrongly in grants[] (grants are platform
        # Permission names such as EXECUTE_FLOW; boot rejects anything else)
        for key in ("permissions", "grants"):
            items = role.get(key)
            if not isinstance(items, list):
                continue
            keep = []
            moved = []
            for item in items:
                if isinstance(item, str) and ":" in item:
                    prefix, verb = item.split(":", 1)
                    name = concepts.match(prefix)
                    if name:
                        access.setdefault(name, set()).add(_op(verb))
                        moved.append(item)
                        continue
                if key == "grants":
                    keep.append(item)
            if key == "permissions":
                role.pop("permissions")
                note("role-permissions", f"{base}.permissions", items, "concepts")
            elif moved:
                if keep:
                    role["grants"] = keep
                else:
                    role.pop("grants")
                note("role-grants-concept-ops", f"{base}.grants", moved, "concepts")

        if access:
            merged = {name: [o for o in CRUD_ORDER if o in ops] for name, ops in access.items()}
            merged = {name: ops for name, ops in merged.items() if ops}
            if merged:
                role["concepts"] = merged
            elif "concepts" in role and not role["concepts"]:
                role.pop("concepts")


def _fix_lifecycles(model: dict, note) -> None:
    for ci, concept in enumerate(model.get("concepts") or []):
        lc = concept.get("lifecycle") if isinstance(concept, dict) else None
        if not isinstance(lc, dict):
            continue
        base = f"$.concepts[{ci}].lifecycle"
        for alias in ("field", "statusFieldName", "stateField"):
            if alias in lc and "statusField" not in lc:
                lc["statusField"] = lc.pop(alias)
                note("lifecycle-status-field", f"{base}.{alias}", alias, "statusField")

        transitions = []
        for ti, t in enumerate(lc.get("transitions") or []):
            if not isinstance(t, dict):
                transitions.append(t)
                continue
            tpath = f"{base}.transitions[{ti}]"
            if "name" in t:
                name = t.pop("name")
                if "event" not in t:
                    t["event"] = name
                note("lifecycle-transition-name", f"{tpath}.name", "name", "event")
            if isinstance(t.get("action"), str):
                action = t.pop("action")
                t.setdefault("event", action)
                note("lifecycle-transition-action", f"{tpath}.action", action, "event")
            src = t.get("from")
            if isinstance(src, list):
                for one in src:
                    transitions.append({**t, "from": one})
                note("lifecycle-transition-from-list", f"{tpath}.from", src, f"{len(src)} transitions")
            else:
                transitions.append(t)
        if "transitions" in lc:
            lc["transitions"] = transitions

        states = lc.get("states")
        if isinstance(states, list) and any(isinstance(s, str) for s in states):
            lc["states"] = [{"value": s} if isinstance(s, str) else s for s in states]
            note("lifecycle-states-strings", f"{base}.states", states, "objects")
        initial = lc.pop("initial", None) if not isinstance(lc.get("initial"), bool) else None
        terminal: list = []
        for alias in ("final", "terminal", "finalStates", "terminalStates"):
            if alias in lc:
                value = lc.pop(alias)
                terminal += value if isinstance(value, list) else [value]
                note("lifecycle-terminal", f"{base}.{alias}", value, "states[].terminal")
        if initial is not None:
            note("lifecycle-initial", f"{base}.initial", initial, "states[].initial")
        if initial is None and not terminal:
            continue
        if not isinstance(lc.get("states"), list):
            order = [initial] if isinstance(initial, str) else []
            for t in transitions:
                if isinstance(t, dict):
                    order += [t.get("from"), t.get("to")]
            order += terminal
            seen = []
            for value in order:
                if isinstance(value, str) and value != "*" and value not in seen:
                    seen.append(value)
            lc["states"] = [{"value": v} for v in seen]
        for state in lc["states"]:
            if not isinstance(state, dict):
                continue
            if isinstance(initial, str) and state.get("value") == initial:
                state["initial"] = True
            if state.get("value") in terminal:
                state["terminal"] = True


def _fix_data_sources(model: dict, concepts: _Concepts, note) -> None:
    for pi, panel in enumerate(model.get("panels") or []):
        for di, ds in enumerate((panel or {}).get("dataSources") or [] if isinstance(panel, dict) else []):
            if not isinstance(ds, dict) or "kind" not in ds or "concept" in ds:
                continue
            if str(ds["kind"]).lower() in ("entity", "concept") and isinstance(ds.get("name"), str):
                name = concepts.match(ds["name"])
                if name:
                    ds.pop("kind")
                    ds["concept"] = name
                    note("datasource-kind-entity", f"$.panels[{pi}].dataSources[{di}].kind", "entity",
                         f"concept: {name}")


# queries[].where / orderBy / groupBy are strings (`where: "stock < 5"`, `orderBy: ["stock desc"]`,
# `groupBy: ["stylist"]`); models keep writing them as {field, op, value} / {field, direction} objects
# (bench tier 3, 2026-11-01). Only the unambiguous shapes are rewritten.
WHERE_OPS = {"eq": "==", "==": "==", "=": "==", "ne": "!=", "neq": "!=", "!=": "!=", "lt": "<", "<": "<",
             "lte": "<=", "<=": "<=", "gt": ">", ">": ">", "gte": ">=", ">=": ">="}
ORDER_DIRECTIONS = {"asc": "", "ascending": "", "desc": " desc", "descending": " desc"}
_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")


def _where_literal(value) -> str | None:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return json.dumps(value)
    if isinstance(value, str) and "'" not in value:
        return f"'{value}'"
    return None


def _fix_queries(model: dict, note) -> None:
    for qi, query in enumerate(model.get("queries") or []):
        if not isinstance(query, dict):
            continue
        where = query.get("where")
        if isinstance(where, dict) and set(where) <= {"field", "op", "operator", "value"} and "field" in where:
            op = WHERE_OPS.get(str(where.get("op", where.get("operator", "eq"))).strip().lower())
            literal = _where_literal(where.get("value"))
            if op and literal is not None and isinstance(where["field"], str) and _IDENT.fullmatch(where["field"]):
                now = f"{where['field']} {op} {literal}"
                query["where"] = now
                note("query-where-object", f"$.queries[{qi}].where", where, now)
        order = query.get("orderBy")
        if isinstance(order, list):
            for oi, item in enumerate(order):
                if not isinstance(item, dict) or "field" not in item or not isinstance(item["field"], str):
                    continue
                direction = str(item.get("direction", item.get("dir", item.get("order", "asc")))).strip().lower()
                if set(item) <= {"field", "direction", "dir", "order"} and direction in ORDER_DIRECTIONS:
                    order[oi] = item["field"] + ORDER_DIRECTIONS[direction]
                    note("query-orderby-object", f"$.queries[{qi}].orderBy[{oi}]", item, order[oi])
        group = query.get("groupBy")
        if isinstance(group, list):
            for gi, item in enumerate(group):
                if isinstance(item, dict) and set(item) == {"field"} and isinstance(item["field"], str):
                    group[gi] = item["field"]
                    note("query-groupby-object", f"$.queries[{qi}].groupBy[{gi}]", item, group[gi])


# Spellings of rollups / orchestration triggers+actions / field types, shared with
# npdev_validation_hints through one measured table (run_bench mine).
with open(Path(__file__).resolve().parent / "desire-path-aliases.json", encoding="utf-8") as _fh:
    _ALIASES = {o["id"]: o for o in json.load(_fh)["objects"]}
ROLLUP_KEY_ALIASES = _ALIASES["rollup"]["keyAliases"]
ACTION_KEY_ALIASES = _ALIASES["orchestrationAction"]["keyAliases"]
FIELD_TYPE_ALIASES = _ALIASES["fieldType"]["valueAliases"]
FIELD_DERIVED_ALIASES = _ALIASES["fieldDerived"]["keyAliases"]
FLOW_STEP_TYPE_ALIASES = _ALIASES["flowStepType"]["valueAliases"]
FLOW_STEP_KEY_ALIASES = _ALIASES["flowStep"]["keyAliases"]
SCOPED_STEP_TYPES = {"createConcept", "updateConcept"}
FIELD_TYPES = {"string", "uuid", "int", "integer", "long", "decimal", "boolean", "date", "datetime",
               "enum", "reference", "object", "array", "file"}
ROLLUP_FNS = {"count", "sum", "min", "max", "avg"}
ROLLUP_KEYS = set(_ALIASES["rollup"]["canonicalKeys"])


def _reference_target(field: dict) -> str | None:
    ref = field.get("reference", field.get("ref"))
    if isinstance(ref, dict):
        ref = ref.get("target")
    return ref if isinstance(ref, str) else None


def _lower_camel(name: str) -> str:
    return name[:1].lower() + name[1:]


def _fix_rollups(model: dict, note) -> None:
    by_name = {c["name"]: c for c in model.get("concepts") or []
               if isinstance(c, dict) and isinstance(c.get("name"), str)}
    for ci, concept in enumerate(model.get("concepts") or []):
        rollups = concept.get("rollups") if isinstance(concept, dict) else None
        if not isinstance(rollups, list):
            continue
        for ri, item in enumerate(rollups):
            if not isinstance(item, dict):
                continue
            path = f"$.concepts[{ci}].rollups[{ri}]"
            before = copy.deepcopy(item)
            # `field` + `name` together: the author's `field` is the aggregated child column
            aliases_field = [a for a, c in ROLLUP_KEY_ALIASES.items() if c == "field" and a in item]
            if aliases_field and "field" in item and "of" not in item:
                item["of"] = item.pop("field")
            renamed = False
            for alias, canonical in ROLLUP_KEY_ALIASES.items():
                if alias in item and canonical not in item:
                    item[canonical] = item.pop(alias)
                    renamed = True
            if renamed:
                note("rollup-key-alias", path, before, copy.deepcopy(item))
            # the child's reference field back to this concept, when exactly one exists
            if "via" not in item and isinstance(item.get("from"), str):
                child = by_name.get(item["from"])
                hits = [f.get("name") for f in (child or {}).get("fields") or []
                        if isinstance(f, dict) and f.get("type") == "reference"
                        and _reference_target(f) == concept.get("name") and isinstance(f.get("name"), str)]
                if len(hits) == 1:
                    item["via"] = hits[0]
                    note("rollup-via-inferred", f"{path}.via", None, hits[0])
            if "field" not in item and item.get("fn") in ROLLUP_FNS and isinstance(item.get("from"), str):
                if item["fn"] == "count":
                    derived = _lower_camel(item["from"]) + "Count"
                elif isinstance(item.get("of"), str) and item["of"]:
                    derived = item["fn"] + item["of"][:1].upper() + item["of"][1:]
                else:
                    derived = None
                if derived:
                    item["field"] = derived
                    note("rollup-field-derived", f"{path}.field", None, derived)


def _fix_orchestrations(model: dict, note) -> None:
    for oi, orch in enumerate(model.get("orchestrations") or []):
        if not isinstance(orch, dict):
            continue
        trigger = orch.get("trigger")
        if isinstance(trigger, dict) and "type" not in trigger and isinstance(trigger.get("event"), str):
            trigger["type"] = "event"
            note("orchestration-trigger-type", f"$.orchestrations[{oi}].trigger.type", None, "event")
        for ai, action in enumerate(orch.get("actions") or []):
            if not isinstance(action, dict):
                continue
            path = f"$.orchestrations[{oi}].actions[{ai}]"
            for alias, canonical in ACTION_KEY_ALIASES.items():
                if alias in action and canonical not in action:
                    action[canonical] = action.pop(alias)
                    note("orchestration-action-map-alias", f"{path}.{alias}", alias, canonical)
            if "type" not in action and isinstance(action.get("concept"), str) \
                    and "capability" not in action and "event" not in action:
                action["type"] = "create"
                note("orchestration-action-type", f"{path}.type", None, "create")


def _fix_field_types(model: dict, note) -> None:
    for ci, concept in enumerate(model.get("concepts") or []):
        for fi, field in enumerate(concept.get("fields") or [] if isinstance(concept, dict) else []):
            written = field.get("type") if isinstance(field, dict) else None
            if not isinstance(written, str) or written in FIELD_TYPES:
                continue
            canonical = FIELD_TYPE_ALIASES.get(written.strip().lower())
            if canonical:
                field["type"] = canonical
                note("field-type-alias", f"$.concepts[{ci}].fields[{fi}].type", written, canonical)


def _fix_field_derived(model: dict, note) -> None:
    """`computed` / `formula` / `calculated` -> the field's `derivedExpression` string."""
    for ci, concept in enumerate(model.get("concepts") or []):
        for fi, field in enumerate(concept.get("fields") or [] if isinstance(concept, dict) else []):
            if not isinstance(field, dict) or "derivedExpression" in field:
                continue
            for alias in FIELD_DERIVED_ALIASES:
                if alias not in field:
                    continue
                value = field[alias]
                if isinstance(value, dict) and set(value) == {"expression"}:
                    value = value["expression"]
                if isinstance(value, str) and value.strip():
                    wrote = field.pop(alias)
                    field["derivedExpression"] = value
                    note("field-derived-expression", f"$.concepts[{ci}].fields[{fi}].{alias}", wrote, value)
                break


def _fix_flow_steps(model: dict, note) -> None:
    for fi, flow in enumerate(model.get("flows") or []):
        if isinstance(flow, dict):
            _fix_step_list(flow.get("steps"), f"$.flows[{fi}].steps", note)


def _fix_step_list(steps, base: str, note) -> None:
    if not isinstance(steps, list):
        return
    for si, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        path = f"{base}[{si}]"
        written = step.get("type")
        if isinstance(written, str):
            canonical = FLOW_STEP_TYPE_ALIASES.get(written.strip().lower())
            if canonical and written != canonical:
                step["type"] = canonical
                note("flow-step-type-alias", f"{path}.type", written, canonical)
        if step.get("type") in SCOPED_STEP_TYPES:
            for alias, canonical in FLOW_STEP_KEY_ALIASES.items():
                if alias in step and canonical not in step and isinstance(step[alias], str):
                    step[canonical] = step.pop(alias)
                    note("flow-step-scope-alias", f"{path}.{alias}", alias, canonical)
        for nested in ("steps", "then", "else"):
            _fix_step_list(step.get(nested), f"{path}.{nested}", note)


def summary_lines(fixes: list[dict], limit: int = 12) -> list[str]:
    """One short line per fix -- what the next authoring turn is told NPDev corrected."""
    lines = []
    for f in fixes[:limit]:
        now = f["now"]
        lines.append(f"- {f['path']}: {_short(f['wrote'])} -> {_short(now) if now is not None else 'removed'}")
    if len(fixes) > limit:
        lines.append(f"- ... and {len(fixes) - limit} more")
    return lines


def _short(value, width: int = 80) -> str:
    text = value if isinstance(value, str) else json.dumps(value)
    return text if len(text) <= width else text[: width - 3] + "..."
