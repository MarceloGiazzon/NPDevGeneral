"""Sample data a browser AI sends (REG-323 F4): `seeds/<id>.json`, checked BEFORE it is written.

What a seed file is: `seeds/<id>.json` beside model.json (NPDevContract/schemas/seed.schema.json). The CLI stages every one of them
into the generated app's `npdev-seed/data-seeds` (npdev_cli.stage_seeds_into_final_app) and `SeedDataService` runs one ON DEMAND
(the app's "Load sample data" action), saving each record through the governed write path. `records[]` holds
`{concept, data, alias?, id?}` or a bulk block (`repeatOver` / `count`); a `smart` seed may use `$ref:<alias>` and
`$gen:<generator>[:args]` values in `data`, a `raw` seed may use neither.

Why a check of our own, the same reason as for themes and pages: staging FAILS GENERATION of the whole app when a seed's `id` is
missing or differs from its file name, and every other mistake an AI makes here (a concept the model does not have, a `$ref:` to an
alias nobody declared, a generator that does not exist, a `raw` seed with templating, a `count` of a million) is found only when
the owner clicks "Load sample data" -- so each is told to the AI first, in plain words, before anything is written. The rules are
those of seed.schema.json and SeedDataService; relay-ingest-policy.json (`seeds`) holds the lists, and tests/test_relay_seeds.py
pins them to both.

Not done here: a `$file:` value is refused (a chat cannot send the file it names), data field VALUES are not type-checked (the
write path does it, and says which field), and a seed that duplicates a record already in the app is not detected (running a seed
twice adds twice, by design). Stdlib only; nothing is written here.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

POLICY_PATH = Path(__file__).resolve().parent / "relay-ingest-policy.json"
SEED_DIR = "seeds/"
FILE_ID = re.compile(r"^[a-zA-Z0-9_-]+$")          # seed.schema.json: id
ALIAS = re.compile(r"^[a-zA-Z0-9_]+$")             # seed.schema.json: record.alias
GEN = re.compile(r"^\$gen:([a-zA-Z0-9_-]+)(?::(.*))?$", re.S)   # SeedDataService.GEN_TOKEN


def load_policy() -> dict:
    return json.loads(POLICY_PATH.read_text(encoding="utf-8"))["seeds"]


def is_seed_path(path) -> bool:
    return isinstance(path, str) and path.startswith(SEED_DIR) and path.endswith(".json") and len(path) > len(SEED_DIR) + len(".json")


def _stem(path: str) -> str:
    return path[len(SEED_DIR):-len(".json")]


def _concepts(model) -> dict | None:
    """{concept name: [its field names]} -- or None when the model cannot say (a concept may come from a fragment or a pack)."""
    if not isinstance(model, dict) or any(model.get(k) for k in ("packs", "fragments", "contexts")):
        return None
    items = model.get("concepts")
    if not isinstance(items, list) or any(not (isinstance(i, dict) and isinstance(i.get("name"), str)) for i in items):
        return None
    out: dict = {}
    for item in items:
        fields = item.get("fields")
        out[item["name"]] = ([f["name"] for f in fields if isinstance(f, dict) and isinstance(f.get("name"), str)]
                             if isinstance(fields, list) else None)
    return out


def _expanded(record: dict) -> int:
    """How many rows one record makes: 1, `count`, or the product of the repeatOver ranges."""
    count = record.get("count")
    if isinstance(count, int) and not isinstance(count, bool) and count > 0:
        return count
    repeat = record.get("repeatOver")
    vars_ = repeat.get("vars") if isinstance(repeat, dict) else None
    if not isinstance(vars_, dict) or not vars_:
        return 1
    total = 1
    for bounds in vars_.values():
        if isinstance(bounds, list) and len(bounds) == 2 and all(isinstance(b, int) and not isinstance(b, bool) for b in bounds):
            total *= max(bounds[1] - bounds[0] + 1, 0)
    return total


def _check_record(record, where: str, kind: str, state: dict, policy: dict, concepts: dict | None, errors: list, findings: list) -> None:
    if not isinstance(record, dict):
        errors.append(f'{where} is not an object - use {{"concept": "Customer", "data": {{...}}}}')
        return
    extra = sorted(k for k in record if k not in policy["recordKeys"])
    if extra:
        errors.append(f'{where}: "{extra[0]}" is not a record key - a record takes only {", ".join(policy["recordKeys"])} '
                      '(the field values go inside "data")')
    concept = record.get("concept")
    if not (isinstance(concept, str) and concept.strip()):
        errors.append(f'{where} needs a "concept" (the name of a concept of the model)')
        concept = None
    data = record.get("data")
    if data is not None and not isinstance(data, dict):
        errors.append(f'{where}: "data" must be an object of field values')
        data = None
    bulk = [k for k in ("repeatOver", "count") if k in record]
    if len(bulk) == 2:
        errors.append(f'{where}: "repeatOver" and "count" are two ways to make many rows - use only one')
    if bulk and data is None:
        errors.append(f'{where}: a "{bulk[0]}" block needs "data" (the template of each row)')
    if bulk and "alias" in record:
        errors.append(f'{where}: a block that makes many rows cannot have an "alias" (a later record could not tell which row)')
    if bulk and "id" in record:
        errors.append(f'{where}: a block that makes many rows cannot share one "id"')
    alias = record.get("alias")
    if alias is not None and not (isinstance(alias, str) and ALIAS.match(alias)):
        errors.append(f'{where}: the alias must be letters, digits and underscores')
        alias = None
    if "count" in record:
        count = record["count"]
        if isinstance(count, bool) or not isinstance(count, int) or count < 1:
            errors.append(f'{where}: "count" must be a whole number of 1 or more')
    if "repeatOver" in record:
        repeat = record["repeatOver"]
        vars_ = repeat.get("vars") if isinstance(repeat, dict) else None
        ok = isinstance(vars_, dict) and bool(vars_) and set(repeat) == {"vars"}
        if ok:
            for name, bounds in vars_.items():
                if not (isinstance(bounds, list) and len(bounds) == 2 and all(isinstance(b, int) and not isinstance(b, bool) for b in bounds)
                        and bounds[0] <= bounds[1]):
                    ok = False
        if not ok:
            errors.append(f'{where}: "repeatOver" must be {{"vars": {{"i": [1, 10]}}}} - each variable a [min, max] pair of whole numbers')
    if kind == "raw":
        for key in ("alias", "repeatOver", "count"):
            if key in record:
                errors.append(f'{where}: a "raw" seed saves every record exactly as written and cannot use "{key}" - '
                              'make the seed "smart" or write the records out')
    for field, value in (data or {}).items():
        if not isinstance(value, str):
            continue
        if value.startswith("$file:"):
            errors.append(f'{where}: "{field}" names a file ({value}) - a chat cannot send the file, so leave that field out')
        elif value.startswith("$ref:") or value.startswith("$gen:"):
            if kind == "raw":
                errors.append(f'{where}: "{field}" uses {value.split(":", 1)[0]}: but a "raw" seed does not template - '
                              'make the seed "smart"')
            elif value.startswith("$ref:"):
                name = value[len("$ref:"):]
                if name not in state["aliases"]:
                    errors.append(f'{where}: "{field}" refers to "{name}" but no EARLIER record has that alias - '
                                  'declare it before this record')
            else:
                match = GEN.match(value)
                if not match or match.group(1) not in policy["generators"]:
                    shown = match.group(1) if match else value
                    errors.append(f'{where}: "{field}" uses a generator "{shown}" that does not exist - use one of '
                                  + ", ".join(policy["generators"]))
                elif match.group(1) == "ref-pick-random":
                    wanted = (match.group(2) or "").strip()
                    if not wanted or wanted not in state["concepts"]:
                        errors.append(f'{where}: "{field}" picks a random "{wanted}" but no EARLIER record saves that concept - '
                                      'declare those records first')
    if concept is not None:
        if concepts is not None and concept not in concepts:
            errors.append(f'{where}: the model has no concept "{concept}" - the concepts are {", ".join(sorted(concepts)[:12])}')
        elif concepts is None and concept not in state["concepts"]:   # first record for it: say once that it was not checked
            findings.append({"code": "seed-concept-unchecked", "severity": "warning", "detail": {"concept": concept}})
        fields = (concepts or {}).get(concept)
        if fields and data:
            unknown = sorted(k for k in data if k not in fields and k != "id")
            if unknown:
                findings.append({"code": "seed-field-unknown", "severity": "warning",
                                 "detail": {"record": where, "concept": concept, "fields": unknown[:5]}})
        state["concepts"].add(concept)
    if alias:
        state["aliases"].add(alias)


def check(answer_files: dict, model, app_dir=None) -> dict:
    """`answer_files` is {path: text} of the answer's files; only seeds/*.json are read. Returns {"errors": [lines for the AI],
    "findings": [{code, severity, detail}], "seeds": [seed paths the answer adds or replaces]}. Nothing is written."""
    policy = load_policy()
    concepts = _concepts(model)
    errors: list = []
    findings: list = []
    sent = {path: text for path, text in answer_files.items() if is_seed_path(path)}
    for path in sorted(sent):
        stem = _stem(path)
        mine: list = []
        rows = _check_file(path, stem, sent[path], policy, concepts, mine, findings)
        errors += mine
        if rows is not None and not mine:
            replaced = bool(app_dir) and (Path(app_dir) / path).is_file()
            findings.append({"code": "seed-replaced" if replaced else "seed-added", "severity": "info",
                             "detail": {"path": path, "records": rows}})
    if any(f["code"] in ("seed-added", "seed-replaced") for f in findings):
        findings.append({"code": "seed-on-demand", "severity": "info", "detail": {}})   # it is run from "Load sample data"
    return {"errors": errors, "findings": findings, "seeds": sorted(sent)}


def _check_file(path: str, stem: str, text: str, policy: dict, concepts: dict | None, errors: list, findings: list):
    """The number of rows the seed makes when it is clean, else None (problems are in `errors`)."""
    if "/" in stem or not FILE_ID.match(stem):
        errors.append(f'{path}: a seed goes directly under seeds/ and its file name is its id - letters, digits, - and _ only '
                      '(seeds/demo-customers.json)')
        return None
    if len(text.encode("utf-8")) > policy["maxFileBytes"]:
        errors.append(f'{path} is larger than {policy["maxFileBytes"]} bytes - make it smaller (a "count" block with $gen: values '
                      'makes many rows from one record)')
        return None
    try:
        seed = json.loads(text)
    except (ValueError, RecursionError) as exc:
        errors.append(f"{path} is not valid JSON: {str(exc)[:120]}")
        return None
    if not isinstance(seed, dict):
        errors.append(f'{path} must be an object like {{"id": "{stem}", "label": "...", "records": [...]}}')
        return None
    before = len(errors)
    extra = sorted(k for k in seed if k not in policy["fileKeys"])
    if extra:
        errors.append(f'{path}: "{extra[0]}" is not allowed in a seed file - it takes only {", ".join(policy["fileKeys"])}')
    seed_id = seed.get("id")
    if not isinstance(seed_id, str) or not seed_id:
        errors.append(f'{path} needs an "id" equal to its file name: "id": "{stem}" (without it the whole app cannot be generated)')
    elif seed_id != stem:
        errors.append(f'{path}: the "id" ({seed_id}) must equal the file name ({stem}) - otherwise the whole app cannot be generated')
    label = seed.get("label")
    if not (isinstance(label, str) and label.strip()):
        errors.append(f'{path} needs a "label" (the name the owner sees when choosing sample data)')
    if seed.get("description") is not None and not isinstance(seed.get("description"), str):
        errors.append(f'{path}: "description" must be text')
    kind = seed.get("kind", "smart")
    if kind not in policy["kinds"]:
        errors.append(f'{path}: "kind" must be {" or ".join(policy["kinds"])}, not {kind!r}')
        kind = "smart"
    records = seed.get("records")
    if not isinstance(records, list):
        errors.append(f'{path} needs "records": a list of {{"concept": "...", "data": {{...}}}}')
        return None
    state = {"aliases": set(), "concepts": set()}
    for index, record in enumerate(records):
        _check_record(record, f"{path} record {index}", kind, state, policy, concepts, errors, findings)
    total = sum(_expanded(r) for r in records if isinstance(r, dict))
    if total > policy["maxExpandedRecords"]:
        errors.append(f'{path} would make {total} rows - the most one seed may make is {policy["maxExpandedRecords"]}')
    if not records:
        findings.append({"code": "seed-empty", "severity": "warning", "detail": {"path": path}})
    return total if len(errors) == before else None


def changed(app_dir, answer_files: dict) -> list:
    """The answer's seed files that are new or hold other JSON than the app's now (re-indenting alone is not a difference). A
    seed-only answer leaves model.json identical and is still a change."""
    out: list = []
    for path, text in sorted((answer_files or {}).items()):
        if not is_seed_path(path):
            continue
        try:
            if json.loads((Path(app_dir) / path).read_text(encoding="utf-8")) != json.loads(text):
                out.append(path)
        except (OSError, ValueError):
            out.append(path)   # absent, or one of them is not JSON: the check says what is wrong with it
    return out
