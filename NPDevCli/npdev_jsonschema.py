#!/usr/bin/env python3
"""A small, honest JSON Schema (draft 2020-12 subset) validator.

WHY THIS EXISTS RATHER THAN `import jsonschema`
------------------------------------------------
MONITOR_PLAN R9: `npdev monitor` / `npdev explore` must work on a machine where only the CLI zip is
installed -- the NPDev Manager ships a private Python with no third-party packages, and the repo's
canonical validator (`scripts/quality/json-schema-validator`, node + ajv) needs a clone AND node.
Neither is available there. A verb that answers correctly on the author's machine and silently not
at all on a user's is the defect this whole plan keeps closing.

WHY IT IS SAFE TO HAND-ROLL
---------------------------
It is not a general validator and does not pretend to be. It supports exactly the keyword set the
schemas it is pointed at actually use, MEASURED rather than assumed:

    schemas/ai/scrapforai-routine.schema.json  (pinned from the engine, 2026-08-10)
        $defs $ref $schema additionalProperties anyOf const default enum exclusiveMinimum
        format items maxItems maxLength maximum minItems minLength minimum oneOf pattern
        properties propertyNames required type

and, for our own `exploration-run.schema.json`, `allOf`/`not`/`exclusiveMaximum` as well.

THE RULE THAT MAKES IT TRUSTWORTHY
----------------------------------
An UNKNOWN keyword is an ERROR, never an ignore. A validator that silently skips what it does not
understand answers "valid" to a question it never asked -- the silent-answer family this codebase
has closed five times (REG-131, REG-136, `_emit_json_error`, ...). If the engine's schema grows a
keyword, this file fails loudly and gets one added, rather than quietly weakening.

`validate(schema, instance)` returns a list of error dicts, empty when valid. It never raises for a
merely-invalid instance; it raises `UnsupportedSchema` only when the SCHEMA uses something this
validator cannot honour.
"""

from __future__ import annotations

import re
from typing import Any

SUPPORTED = {
    # structural
    "$schema", "$id", "$defs", "$ref", "$comment",
    # annotations -- carry no assertion, safe to ignore BY NAME (not by silence)
    "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly",
    # assertions
    "type", "enum", "const",
    "properties", "required", "additionalProperties", "propertyNames", "patternProperties",
    "minProperties", "maxProperties",
    "items", "prefixItems", "minItems", "maxItems", "uniqueItems",
    "minLength", "maxLength", "pattern", "format",
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
    "anyOf", "allOf", "oneOf", "not",
}

ANNOTATION_ONLY = {
    "$schema", "$id", "$defs", "$comment",
    "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly",
}


class UnsupportedSchema(Exception):
    """The schema uses a keyword this validator cannot honour. Loud on purpose -- see the module
    docstring: silently ignoring an unknown assertion is answering a question that was never asked."""


def _err(path: str, keyword: str, message: str, **extra: Any) -> dict:
    out = {"path": path or "/", "keyword": keyword, "message": message}
    out.update(extra)
    return out


def _type_matches(value: Any, expected: str) -> bool:
    if expected == "null":
        return value is None
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "integer":
        # JSON Schema: 1.0 IS an integer. bool is a subclass of int in Python and is NOT.
        if isinstance(value, bool):
            return False
        return isinstance(value, int) or (isinstance(value, float) and value.is_integer())
    if expected == "number":
        return not isinstance(value, bool) and isinstance(value, (int, float))
    if expected == "string":
        return isinstance(value, str)
    if expected == "array":
        return isinstance(value, list)
    if expected == "object":
        return isinstance(value, dict)
    raise UnsupportedSchema(f"unknown type name {expected!r}")


_URI_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*:")


def _format_ok(fmt: str, value: str) -> bool:
    """Only the formats the pinned schema actually uses are ASSERTED; anything else is accepted but
    reported as unasserted by `unasserted_formats()` so a caller can say so rather than imply a check
    happened. `uri` matters because the engine's `targetUrl` is z.url() -- a routine with a relative
    URL is rejected by the engine at runtime, and a validator that let it through would be telling
    the user the opposite of what happens."""
    if fmt == "uri":
        return bool(_URI_RE.match(value))
    if fmt == "uri-reference":
        return True
    return True


ASSERTED_FORMATS = {"uri"}


def _resolve_ref(ref: str, root: dict, path: str) -> dict:
    if not ref.startswith("#"):
        raise UnsupportedSchema(f"only local $ref is supported, got {ref!r} (at {path})")
    pointer = ref[1:]
    if pointer.startswith("/"):
        pointer = pointer[1:]
    node: Any = root
    if pointer:
        for raw in pointer.split("/"):
            token = raw.replace("~1", "/").replace("~0", "~")
            if isinstance(node, list):
                node = node[int(token)]
            elif isinstance(node, dict) and token in node:
                node = node[token]
            else:
                raise UnsupportedSchema(f"$ref {ref!r} does not resolve (at {path})")
    if not isinstance(node, dict):
        raise UnsupportedSchema(f"$ref {ref!r} does not point at a schema object (at {path})")
    return node


def _validate(schema: Any, instance: Any, root: dict, path: str, errors: list, seen_formats: set) -> None:
    # Boolean schemas: `true` accepts everything, `false` rejects everything.
    if schema is True:
        return
    if schema is False:
        errors.append(_err(path, "false", "schema is `false`: nothing is valid here"))
        return
    if not isinstance(schema, dict):
        raise UnsupportedSchema(f"schema at {path} is neither an object nor a boolean")

    unknown = set(schema) - SUPPORTED
    if unknown:
        raise UnsupportedSchema(
            f"unsupported keyword(s) {sorted(unknown)} at {path or '/'} -- add support to "
            f"NPDevCli/npdev_jsonschema.py rather than letting them pass unchecked"
        )

    if "$ref" in schema:
        target = _resolve_ref(schema["$ref"], root, path)
        _validate(target, instance, root, path, errors, seen_formats)
        # draft 2020-12: siblings of $ref are also applied.
        siblings = {k: v for k, v in schema.items() if k != "$ref"}
        if siblings and set(siblings) - ANNOTATION_ONLY:
            _validate(siblings, instance, root, path, errors, seen_formats)
        return

    if "type" in schema:
        expected = schema["type"]
        names = expected if isinstance(expected, list) else [expected]
        if not any(_type_matches(instance, n) for n in names):
            errors.append(_err(path, "type", f"must be {' or '.join(names)}", found=_kind_of(instance)))
            return  # every other assertion below is about a type this value is not

    if "const" in schema and instance != schema["const"]:
        errors.append(_err(path, "const", f"must equal {schema['const']!r}", allowedValue=schema["const"]))

    if "enum" in schema and instance not in schema["enum"]:
        errors.append(_err(path, "enum", "must be one of the allowed values", allowedValues=schema["enum"]))

    if "not" in schema:
        sub: list = []
        _validate(schema["not"], instance, root, path, sub, seen_formats)
        if not sub:
            errors.append(_err(path, "not", "must NOT match the given schema"))

    for keyword in ("allOf",):
        if keyword in schema:
            for index, sub_schema in enumerate(schema[keyword]):
                _validate(sub_schema, instance, root, f"{path}/{keyword}[{index}]", errors, seen_formats)

    if "anyOf" in schema:
        branch_errors = []
        matched = False
        for index, sub_schema in enumerate(schema["anyOf"]):
            sub: list = []
            _validate(sub_schema, instance, root, path, sub, seen_formats)
            if not sub:
                matched = True
                break
            branch_errors.append((index, sub))
        if not matched:
            errors.append(_branch_failure(path, "anyOf", schema["anyOf"], branch_errors, instance, root))

    if "oneOf" in schema:
        matches = []
        branch_errors = []
        for index, sub_schema in enumerate(schema["oneOf"]):
            sub = []
            _validate(sub_schema, instance, root, path, sub, seen_formats)
            if not sub:
                matches.append(index)
            else:
                branch_errors.append((index, sub))
        if len(matches) != 1:
            if not matches:
                errors.append(_branch_failure(path, "oneOf", schema["oneOf"], branch_errors, instance, root))
            else:
                errors.append(_err(path, "oneOf", f"matched more than one allowed shape ({matches})"))

    if isinstance(instance, str):
        _validate_string(schema, instance, path, errors, seen_formats)
    elif isinstance(instance, bool):
        pass  # bool is an int in Python; it is never a JSON number
    elif isinstance(instance, (int, float)):
        _validate_number(schema, instance, path, errors)
    elif isinstance(instance, list):
        _validate_array(schema, instance, root, path, errors, seen_formats)
    elif isinstance(instance, dict):
        _validate_object(schema, instance, root, path, errors, seen_formats)


def _discriminator_value(branch: Any, root: dict) -> tuple[str, Any] | None:
    """If a branch pins one property to a `const`, return (property, value).

    Zod emits a step union as ~30 object branches each fixing `action` to a different literal, and
    that literal is the DISCRIMINATOR a human is using too: they wrote `"action": "waitForSelector"`
    and want to know what is wrong with THEIR step -- not to read why it is not a `goto`."""
    if not isinstance(branch, dict):
        return None
    if "$ref" in branch:
        try:
            branch = _resolve_ref(branch["$ref"], root, "")
        except UnsupportedSchema:
            return None
    properties = branch.get("properties")
    if not isinstance(properties, dict):
        return None
    for name in ("action", "kind", "type"):
        sub = properties.get(name)
        if isinstance(sub, dict) and "$ref" in sub:
            try:
                sub = _resolve_ref(sub["$ref"], root, "")
            except UnsupportedSchema:
                sub = None
        if isinstance(sub, dict) and "const" in sub:
            return name, sub["const"]
    return None


def _branch_failure(path: str, keyword: str, branches: list, branch_errors: list,
                    instance: Any, root: dict) -> dict:
    """A USABLE message for a large discriminated union.

    The naive version prints the first four branches' complaints, which for a 30-action step union
    reads: "must equal 'goto'; must equal 'reload'; must equal 'waitForLoadState'..." -- three facts
    the author already knows and none about the step they actually wrote. MONITOR_PLAN D3 has the UI
    show these messages VERBATIM, so an unusable message here is an unusable Validate button there.

    So: find the branch whose discriminator matches the instance, and report only that branch."""
    if isinstance(instance, dict):
        for index, branch in enumerate(branches):
            discriminator = _discriminator_value(branch, root)
            if discriminator and instance.get(discriminator[0]) == discriminator[1]:
                own = next((errs for i, errs in branch_errors if i == index), [])
                detail = "; ".join(f"{e['path']} {e['message']}" for e in own[:4]) or "unknown"
                return _err(
                    path, keyword,
                    f"is not a valid `{discriminator[1]}` step: {detail}",
                    matchedBranch=discriminator[1],
                )
        # A discriminator value that matches NO branch: name the vocabulary rather than dumping it.
        known = sorted({
            str(d[1]) for d in (_discriminator_value(b, root) for b in branches) if d
        })
        for name in ("action", "kind", "type"):
            if name in instance and known:
                return _err(
                    path, keyword,
                    f"`{name}: {instance[name]!r}` is not one of the {len(known)} the engine defines",
                    allowedValues=known,
                )
    detail = "; ".join(
        f"[{i}] " + ", ".join(f"{e['path']} {e['message']}" for e in errs[:2])
        for i, errs in branch_errors[:3]
    )
    return _err(path, keyword, f"matched none of the allowed shapes -- {detail}")


def _kind_of(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


def _validate_string(schema: dict, instance: str, path: str, errors: list, seen_formats: set) -> None:
    if "minLength" in schema and len(instance) < schema["minLength"]:
        errors.append(_err(path, "minLength", f"must be at least {schema['minLength']} characters (is {len(instance)})"))
    if "maxLength" in schema and len(instance) > schema["maxLength"]:
        errors.append(_err(path, "maxLength", f"must be at most {schema['maxLength']} characters (is {len(instance)})"))
    if "pattern" in schema and not re.search(schema["pattern"], instance):
        errors.append(_err(path, "pattern", f"must match {schema['pattern']}"))
    if "format" in schema:
        fmt = schema["format"]
        seen_formats.add(fmt)
        if not _format_ok(fmt, instance):
            errors.append(_err(path, "format", f"must be a valid {fmt}"))


def _validate_number(schema: dict, instance: float, path: str, errors: list) -> None:
    if "minimum" in schema and instance < schema["minimum"]:
        errors.append(_err(path, "minimum", f"must be >= {schema['minimum']}"))
    if "maximum" in schema and instance > schema["maximum"]:
        errors.append(_err(path, "maximum", f"must be <= {schema['maximum']}"))
    if "exclusiveMinimum" in schema and instance <= schema["exclusiveMinimum"]:
        errors.append(_err(path, "exclusiveMinimum", f"must be > {schema['exclusiveMinimum']}"))
    if "exclusiveMaximum" in schema and instance >= schema["exclusiveMaximum"]:
        errors.append(_err(path, "exclusiveMaximum", f"must be < {schema['exclusiveMaximum']}"))
    if "multipleOf" in schema:
        factor = schema["multipleOf"]
        if factor and abs((instance / factor) - round(instance / factor)) > 1e-9:
            errors.append(_err(path, "multipleOf", f"must be a multiple of {factor}"))


def _validate_array(schema: dict, instance: list, root: dict, path: str, errors: list, seen_formats: set) -> None:
    if "minItems" in schema and len(instance) < schema["minItems"]:
        errors.append(_err(path, "minItems", f"must have at least {schema['minItems']} item(s) (has {len(instance)})"))
    if "maxItems" in schema and len(instance) > schema["maxItems"]:
        errors.append(_err(path, "maxItems", f"must have at most {schema['maxItems']} item(s) (has {len(instance)})"))
    if schema.get("uniqueItems"):
        seen = []
        for item in instance:
            if item in seen:
                errors.append(_err(path, "uniqueItems", "items must be unique"))
                break
            seen.append(item)
    offset = 0
    if "prefixItems" in schema:
        for index, sub_schema in enumerate(schema["prefixItems"]):
            if index < len(instance):
                _validate(sub_schema, instance[index], root, f"{path}/{index}", errors, seen_formats)
        offset = len(schema["prefixItems"])
    if "items" in schema:
        for index in range(offset, len(instance)):
            _validate(schema["items"], instance[index], root, f"{path}/{index}", errors, seen_formats)


def _validate_object(schema: dict, instance: dict, root: dict, path: str, errors: list, seen_formats: set) -> None:
    for name in schema.get("required", []):
        if name not in instance:
            errors.append(_err(path, "required", f"is missing the required property {name!r}", missing=name))

    if "minProperties" in schema and len(instance) < schema["minProperties"]:
        errors.append(_err(path, "minProperties", f"must have at least {schema['minProperties']} propertie(s)"))
    if "maxProperties" in schema and len(instance) > schema["maxProperties"]:
        errors.append(_err(path, "maxProperties", f"must have at most {schema['maxProperties']} propertie(s)"))

    properties = schema.get("properties", {})
    pattern_properties = schema.get("patternProperties", {})
    for key, value in instance.items():
        matched = False
        if key in properties:
            matched = True
            _validate(properties[key], value, root, f"{path}/{key}", errors, seen_formats)
        for pattern, sub_schema in pattern_properties.items():
            if re.search(pattern, key):
                matched = True
                _validate(sub_schema, value, root, f"{path}/{key}", errors, seen_formats)
        if not matched and "additionalProperties" in schema:
            extra = schema["additionalProperties"]
            if extra is False:
                errors.append(_err(path, "additionalProperties", f"has an unexpected property {key!r}", unexpected=key))
            elif extra is not True:
                _validate(extra, value, root, f"{path}/{key}", errors, seen_formats)
        if "propertyNames" in schema:
            _validate(schema["propertyNames"], key, root, f"{path}/{key}<name>", errors, seen_formats)


def validate(schema: dict, instance: Any) -> list[dict]:
    """Returns a (possibly empty) list of error dicts: {path, keyword, message, ...}.

    Raises UnsupportedSchema when the SCHEMA is beyond this validator -- never for a merely invalid
    instance."""
    errors: list[dict] = []
    seen_formats: set[str] = set()
    _validate(schema, instance, schema if isinstance(schema, dict) else {}, "", errors, seen_formats)
    return errors


def unasserted_formats(schema: dict, instance: Any) -> list[str]:
    """Which `format` values were encountered but NOT actually asserted. The caller reports these so
    a user is never told a check happened that did not."""
    errors: list[dict] = []
    seen_formats: set[str] = set()
    _validate(schema, instance, schema, "", errors, seen_formats)
    return sorted(seen_formats - ASSERTED_FORMATS)


def describe(errors: list[dict], limit: int = 8) -> str:
    """One-line-per-error human text, in the shape the CLI's other validators print."""
    lines = []
    for error in errors[:limit]:
        line = f"{error.get('path', '/')} {error.get('keyword', '')}: {error.get('message', '')}".strip()
        if "allowedValues" in error:
            line += " (allowed: " + ", ".join(repr(v) for v in error["allowedValues"][:12]) + ")"
        lines.append(line)
    if len(errors) > limit:
        lines.append(f"... and {len(errors) - limit} more")
    return "\n".join(lines)
