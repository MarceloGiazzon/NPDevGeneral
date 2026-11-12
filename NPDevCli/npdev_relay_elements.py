"""Browser-AI relay, F4 / T4.2: turn an element-upsert answer (npdev-elements.json) into the whole model.

A model too big to be written out again in ONE answer cannot be changed by a chat AI that must return model.json
whole. The AI returns only the root-array ELEMENTS it created or changed (each COMPLETE), the ones to remove and the
renames; this module applies that to the model the AI was shown and hands back the full model. Everything after it
(desire paths, validation, the authoring gate, the preview, apply, undo) sees an ordinary model.json -- so an upsert
answer and the equivalent whole-file answer are checked by exactly the same rules and expand to the same model.

Pure functions, no I/O: `expand(base, elements, policy)`. What an upsert may touch lives in the `elements` section of
relay-ingest-policy.json (identity keys, whole-file-only keys); nothing about the model's shape is written here.

Common AI-answer situations this absorbs on purpose (each has a test, and a finding the person can read):
  * the key spelled in the singular or in another case ("concept", "Flows")        -> root-key-fixed
  * one element sent as an object instead of a list                                 -> element-wrapped
  * the same element twice in one answer                                            -> element-duplicate-last-wins
  * an element that is already exactly as it is                                     -> element-unchanged
  * an element that lost most of its content (an AI that thinks an upsert merges)   -> element-shrank
  * a removal of something that is not there                                        -> remove-missing
  * a name both removed and upserted                                                -> remove-and-upsert-same-name
  * a rename, with or without the renamed element's new content                     -> renamed / rename-*
Anything that cannot be applied safely is an ERROR written for the AI in the repo's `where - what - what to send`
shape; it never half-applies (`expand` returns model None when there is any error).
"""
from __future__ import annotations

import copy
import json

RENAME_FROM_KEYS = ("from", "old", "oldName", "was")
RENAME_TO_KEYS = ("to", "new", "newName", "now")
RENAMED_FROM_ROOTS = ("concepts",)  # the root arrays whose elements carry `renamedFrom` in the model schema


def _finding(code: str, severity: str, **detail) -> dict:
    return {"code": code, "severity": severity, **({"detail": detail} if detail else {})}


def _name(element) -> str | None:
    value = element.get("name") if isinstance(element, dict) else None
    return value.strip() if isinstance(value, str) and value.strip() else None


def _size(element) -> int:
    return len(json.dumps(element, ensure_ascii=False, sort_keys=True))


def _inline_names(array) -> dict:
    """{name: index} of the elements written inline (an entry that is only a `$ref` has no name here)."""
    return {n: i for i, e in enumerate(array) if (n := _name(e)) is not None}


def accepted_keys(cfg: dict) -> list:
    return list(cfg["identityKeys"])


def resolve_key(key, cfg: dict):
    """(root key, fixed) for the spelling the AI used, or (None, False). Exact first; then case, singular/plural and
    y/ies -- accepted only when exactly one known key matches."""
    known = list(cfg["identityKeys"]) + list(cfg["wholeFileOnly"])
    if key in known:
        return key, False
    folded = str(key).strip().casefold()
    forms = {folded, folded + "s", folded + "es"}
    if folded.endswith("s"):
        forms.add(folded[:-1])
    if folded.endswith("es"):
        forms.add(folded[:-2])
    if folded.endswith("y"):
        forms.add(folded[:-1] + "ies")
    matches = {k for k in known if k.casefold() in forms}
    if len(matches) == 1:
        return matches.pop(), True
    return None, False


def _bad_key(key, cfg: dict, where: str) -> str:
    reason = None
    resolved, _ = resolve_key(key, cfg)
    if resolved is not None:
        reason = cfg["wholeFileOnly"].get(resolved)
    if reason is not None:
        return (f"{where}.{key} - {resolved} cannot be changed element by element ({reason}) - "
                "send the complete model.json instead")
    return (f"{where}.{key} - not a part of the model that can be sent this way - use one of: "
            + ", ".join(accepted_keys(cfg)))


def _as_list(value, finding_ctx: list, key: str, what: str):
    """A list of the value; one object stands for a list of one (finding); anything else is None."""
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        finding_ctx.append(_finding("element-wrapped", "info", key=key, what=what))
        return [value]
    return None


def _parse_upserts(elements: dict, cfg: dict, errors: list, findings: list) -> dict:
    """{root key: [element, ...]} in the order sent, duplicates already resolved (last wins)."""
    raw = elements.get("upsert")
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        errors.append("upsert - must be an object whose keys are the model's lists (for example concepts) and whose "
                      "values are lists of complete elements - send it that way")
        return {}
    out: dict = {}
    for key, value in raw.items():
        root, fixed = resolve_key(key, cfg)
        if root is None or root not in cfg["identityKeys"]:
            errors.append(_bad_key(key, cfg, "upsert"))
            continue
        if fixed:
            findings.append(_finding("root-key-fixed", "info", written=key, used=root))
        items = _as_list(value, findings, root, "upsert")
        if items is None:
            errors.append(f"upsert.{key} - must be a list of complete elements - send a list")
            continue
        bucket = out.setdefault(root, {})
        for index, element in enumerate(items):
            where = f"upsert.{key}[{index}]"
            if not isinstance(element, dict):
                errors.append(f"{where} - an element must be an object - send the complete element")
                continue
            name = _name(element)
            if name is None:
                errors.append(f"{where} - the element has no name - every element needs its name, it is how NPDev "
                              "finds the one to replace")
                continue
            if name in bucket:
                findings.append(_finding("element-duplicate-last-wins", "warning", key=root, name=name))
                del bucket[name]  # re-insert so the LAST one also keeps the last position
            bucket[name] = element
    return {key: list(bucket.values()) for key, bucket in out.items()}


def _parse_removals(elements: dict, cfg: dict, errors: list, findings: list) -> dict:
    """{root key: [name, ...]}. A name can be a string or an object that has one."""
    raw = elements.get("remove")
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        errors.append("remove - must be an object whose keys are the model's lists and whose values are lists of "
                      "names, for example {\"concepts\": [\"OldThing\"]} - send it that way")
        return {}
    out: dict = {}
    for key, value in raw.items():
        root, fixed = resolve_key(key, cfg)
        if root is None or root not in cfg["identityKeys"]:
            errors.append(_bad_key(key, cfg, "remove"))
            continue
        if fixed:
            findings.append(_finding("root-key-fixed", "info", written=key, used=root))
        items = value if isinstance(value, list) else [value]
        names = []
        for index, item in enumerate(items):
            name = item.strip() if isinstance(item, str) else _name(item)
            if not name:
                errors.append(f"remove.{key}[{index}] - not a name - list the names of the elements to remove")
                continue
            if name not in names:
                names.append(name)
        out.setdefault(root, [])
        out[root].extend(n for n in names if n not in out[root])
    return out


def _parse_renames(elements: dict, errors: list) -> list:
    raw = elements.get("renames")
    if raw is None:
        return []
    if not isinstance(raw, list):
        errors.append("renames - must be a list of {\"from\": ..., \"to\": ...} - send it that way")
        return []
    out = []
    for index, entry in enumerate(raw):
        source = next((entry[k].strip() for k in RENAME_FROM_KEYS if isinstance(entry, dict) and isinstance(entry.get(k), str)
                       and entry[k].strip()), None)
        target = next((entry[k].strip() for k in RENAME_TO_KEYS if isinstance(entry, dict) and isinstance(entry.get(k), str)
                       and entry[k].strip()), None)
        if source is None or target is None:
            errors.append(f"renames[{index}] - needs both from and to - write {{\"from\": \"OldName\", \"to\": \"NewName\"}}")
            continue
        out.append({"from": source, "to": target})
    return out


def _in_fragment_error(key: str, name: str, how: str) -> str:
    return (f"{how}.{key} - '{name}' is kept in a separate file of the app, so it cannot be changed from here - "
            "send the complete model.json and that file instead")


def expand(base, elements, policy: dict, *, composed=None) -> dict:
    """Apply `elements` to `base`.

    base      the model as the AI was shown it (the app's model.json, parsed).
    elements  the parsed npdev-elements.json.
    policy    relay-ingest-policy.json (its `elements` section).
    composed  optional, for an app made of several files: the one model they compose into. Elements that live there
              and not in `base` are kept in a file of their own and are refused (a clear error), never duplicated.

    Returns {"ok", "model", "errors", "findings", "changes", "manifest"}; `model` is None when ok is False.
    `manifest` = what the AI's own npdev-submission.json would say about the removals and renames of CONCEPTS (the
    authoring gate asks for them); the caller merges it under whatever the AI sent."""
    cfg = policy["elements"]
    errors: list = []
    findings: list = []
    changes = {"added": 0, "replaced": 0, "removed": 0, "renamed": 0, "unchanged": 0}
    manifest = {"renames": [], "deliberateRemovals": []}

    def failed() -> dict:
        return {"ok": False, "model": None, "errors": errors, "findings": findings, "changes": changes, "manifest": manifest}

    if not isinstance(base, dict):
        errors.append("model.json - the app's current model is not a JSON object - send the complete model.json")
        return failed()
    if not isinstance(elements, dict):
        errors.append("npdev-elements.json - must be a JSON object with upsert and/or remove - send it that way")
        return failed()
    unknown = sorted(set(elements) - {"upsert", "remove", "renames", "request"})
    for key in unknown:
        findings.append(_finding("elements-key-ignored", "warning", key=key))
    upserts = _parse_upserts(elements, cfg, errors, findings)
    removals = _parse_removals(elements, cfg, errors, findings)
    renames = _parse_renames(elements, errors)
    if not (upserts or removals or renames) and not errors:
        errors.append("npdev-elements.json - it changes nothing (no upsert, remove or renames) - send the elements "
                      "to create or change")
    if errors:
        return failed()

    model = copy.deepcopy(base)
    arrays: dict = {}

    def array(key: str) -> list:
        if key not in arrays:
            current = model.get(key)
            if current is not None and not isinstance(current, list):
                errors.append(f"model.json.{key} - is not a list in the current model - send the complete model.json")
                arrays[key] = []
            else:
                arrays[key] = current if isinstance(current, list) else []
        return arrays[key]

    def in_fragment(key: str, name: str) -> bool:
        if not isinstance(composed, dict) or not isinstance(composed.get(key), list):
            return False
        return name in _inline_names(composed[key]) and name not in _inline_names(array(key))

    # Renames first: they decide where the renamed element sits and which old element disappears.
    handled_renames: set = set()
    for rename in renames:
        old, new = rename["from"], rename["to"]
        keys_with_new = [k for k, items in upserts.items() if any(_name(e) == new for e in items)]
        keys_with_old = [k for k in cfg["identityKeys"] if old in _inline_names(array(k))]
        keys_in_files = [k for k in cfg["identityKeys"] if in_fragment(k, old)]
        pool = [k for k in keys_with_old if not keys_with_new or k in keys_with_new] or keys_with_old
        if len(pool) > 1:
            errors.append(f"renames - '{old}' exists in more than one list ({', '.join(pool)}) so NPDev cannot tell which "
                          "one is renamed - send the complete model.json")
            continue
        if not pool:
            if keys_in_files:
                errors.append(_in_fragment_error(keys_in_files[0], old, "renames"))
            elif any(new in _inline_names(array(k)) for k in cfg["identityKeys"]):
                findings.append(_finding("rename-already-applied", "info", old=old, new=new))
            else:
                findings.append(_finding("rename-source-missing", "warning", old=old, new=new))
            continue
        key = pool[0]
        items = array(key)
        index = _inline_names(items)[old]
        replacement = next((e for e in upserts.get(key, []) if _name(e) == new), None)
        if replacement is None:  # rename only: the same element under its new name
            replacement = copy.deepcopy(items[index])
            replacement["name"] = new
            findings.append(_finding("rename-only", "info", key=key, old=old, new=new))
        else:
            replacement = copy.deepcopy(replacement)
            upserts[key] = [e for e in upserts[key] if _name(e) != new]
        if key in RENAMED_FROM_ROOTS and not replacement.get("renamedFrom"):
            replacement["renamedFrom"] = old
        existing_new = _inline_names(items).get(new)
        if existing_new is not None and existing_new != index:
            errors.append(f"renames - '{new}' already exists in {key}, so '{old}' cannot be renamed to it - choose "
                          "another name or send the complete model.json")
            continue
        items[index] = replacement
        changes["renamed"] += 1
        handled_renames.add((key, old))
        if key == "concepts":
            manifest["renames"].append({"kind": "concept", "from": old, "to": new})
        findings.append(_finding("renamed", "info", key=key, old=old, new=new))

    # Removals.
    for key, names in removals.items():
        items = array(key)
        for name in names:
            if any(_name(e) == name for e in upserts.get(key, [])):
                findings.append(_finding("remove-and-upsert-same-name", "warning", key=key, name=name))
                continue
            index = _inline_names(items).get(name)
            if index is None:
                if in_fragment(key, name):
                    errors.append(_in_fragment_error(key, name, "remove"))
                else:
                    findings.append(_finding("remove-missing", "warning", key=key, name=name))
                continue
            del items[index]
            changes["removed"] += 1
            if key == "concepts":
                manifest["deliberateRemovals"].append({"kind": "concept", "name": name})

    # Upserts: replace in place (the element keeps its position) or append.
    for key, new_items in upserts.items():
        items = array(key)
        for element in new_items:
            name = _name(element)
            index = _inline_names(items).get(name)
            if index is not None:
                old = items[index]
                if old == element:
                    changes["unchanged"] += 1
                    findings.append(_finding("element-unchanged", "info", key=key, name=name))
                    continue
                before, after = _size(old), _size(element)
                if before >= cfg["shrinkMinChars"] and after < before * cfg["shrinkWarnRatio"]:
                    findings.append(_finding("element-shrank", "warning", key=key, name=name, charsBefore=before,
                                             charsAfter=after))
                items[index] = copy.deepcopy(element)
                changes["replaced"] += 1
            elif in_fragment(key, name):
                errors.append(_in_fragment_error(key, name, "upsert"))
            else:
                items.append(copy.deepcopy(element))
                changes["added"] += 1

    if errors:
        return failed()
    for key, items in arrays.items():
        if items or key in model:
            model[key] = items
    return {"ok": True, "model": model, "errors": [], "findings": findings, "changes": changes, "manifest": manifest}
