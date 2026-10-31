#!/usr/bin/env python3
"""BENCH-1: the AI authoring benchmark's prompt set is complete, well-formed and honest.

WHAT IT CHECKS (stdlib only; reads JSON only, never a markdown file)
--------------------------------------------------------------------
  * NPDevSamples/ai-bench/prompts/bench-NNN-<slug>.json -- exactly 30 files, ids bench-001..bench-030
    contiguous and unique, exactly 10 per tier (simple-crud, workflow-roles, integration-report), file
    name prefix == id.
  * each file validates against NPDevSamples/ai-bench/prompt.schema.json (a built-in minimal draft
    2020-12 subset validator: type enum const required properties additionalProperties items
    prefixItems min/max* pattern uniqueItems oneOf $ref; the schema uses nothing else, and an unknown
    schema keyword fails the run rather than being ignored).
  * the prompt and title never contain the words NPDev, DSL, JSON, schema, API, REST, Gradle, and a
    prompt is 3 to 8 sentences (the benchmark asks the question as a business owner would).
  * cross-references inside the acceptance resolve: probes named by rules exist, every assert probe is
    used by a rule, setup probes expect only 2xx, rest-reject / role-denied probes expect only 4xx,
    rest-accept probes only 2xx, lifecycle keys are real enum keys, role names are declared, and every
    template ({concept:..}, {id:..}, {field:..}, {enum:..}, {flow:..}, {view:..}) names something the
    acceptance declares (an {id:pN} must point at an EARLIER POST probe).
  * minimums equal what the acceptance itself names: above it is a requirement the model can never see
    ("app has 6 fields, minimum is 7" with no word on WHICH field -- 18 of 30 prompts did this, and
    bench-006 asked for 7 fields while describing 6).
  * tier shape: workflow-roles needs a lifecycle and a role-denied rule; integration-report needs a
    derived-value rule; every prompt needs a rest-reject and a rest-accept rule.
  * NPDevSamples/ai-bench/index.json lists exactly the prompt files with the same id/tier/title.

Overrides (used to red-prove the checker against a doctored COPY outside the repo):
  --root <dir>   the ai-bench directory (default NPDevSamples/ai-bench next to this repo's root)
  env NPDEV_AI_BENCH_DIR does the same. Exit 0 = clean, 1 = findings.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TIERS = ("simple-crud", "workflow-roles", "integration-report")
PER_TIER = 10
TOTAL = 30
BANNED = ("NPDev", "DSL", "JSON", "schema", "API", "REST", "Gradle")
TEMPLATE = re.compile(r"\{(concept|id|field|enum|flow|view|unique|uuid|date|now|any)(?::([^{}]*))?\}")
FOUR_XX = range(400, 500)


# ---------------------------------------------------------------- minimal JSON Schema validator
class SchemaError(Exception):
    pass


KNOWN = {"$schema", "$id", "title", "description", "type", "enum", "const", "required", "properties",
         "additionalProperties", "items", "prefixItems", "minItems", "maxItems", "minLength", "maxLength",
         "minimum", "maximum", "pattern", "uniqueItems", "oneOf", "$ref", "$defs", "default"}


def _is_type(v, t):
    if t == "object":
        return isinstance(v, dict)
    if t == "array":
        return isinstance(v, list)
    if t == "string":
        return isinstance(v, str)
    if t == "integer":
        return isinstance(v, int) and not isinstance(v, bool)
    if t == "number":
        return isinstance(v, (int, float)) and not isinstance(v, bool)
    if t == "boolean":
        return isinstance(v, bool)
    if t == "null":
        return v is None
    raise SchemaError("unsupported type " + t)


def validate(value, schema, root, path, errs):
    if schema is True:
        return
    if schema is False:
        errs.append(f"{path}: not allowed")
        return
    for k in schema:
        if k not in KNOWN:
            raise SchemaError(f"schema keyword not supported by this checker: {k}")
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/"):
            raise SchemaError("only local $ref supported: " + ref)
        node = root
        for part in ref[2:].split("/"):
            node = node[part]
        validate(value, node, root, path, errs)
    if "type" in schema:
        types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(_is_type(value, t) for t in types):
            errs.append(f"{path}: expected {types}, got {type(value).__name__}")
            return
    if "enum" in schema and value not in schema["enum"]:
        errs.append(f"{path}: {value!r} not in {schema['enum']}")
    if "const" in schema and value != schema["const"]:
        errs.append(f"{path}: expected {schema['const']!r}")
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errs.append(f"{path}: shorter than {schema['minLength']}")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errs.append(f"{path}: longer than {schema['maxLength']} ({len(value)})")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errs.append(f"{path}: {value!r} does not match {schema['pattern']}")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errs.append(f"{path}: below minimum {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errs.append(f"{path}: above maximum {schema['maximum']}")
    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            errs.append(f"{path}: fewer than {schema['minItems']} items")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errs.append(f"{path}: more than {schema['maxItems']} items")
        if schema.get("uniqueItems") and len({json.dumps(x, sort_keys=True) for x in value}) != len(value):
            errs.append(f"{path}: items not unique")
        pre = schema.get("prefixItems", [])
        for i, item in enumerate(value):
            if i < len(pre):
                validate(item, pre[i], root, f"{path}[{i}]", errs)
            elif "items" in schema:
                validate(item, schema["items"], root, f"{path}[{i}]", errs)
    if isinstance(value, dict):
        for req in schema.get("required", []):
            if req not in value:
                errs.append(f"{path}: missing required '{req}'")
        props = schema.get("properties", {})
        for k, v in value.items():
            if k in props:
                validate(v, props[k], root, f"{path}.{k}", errs)
            else:
                ap = schema.get("additionalProperties", True)
                if ap is False:
                    errs.append(f"{path}: unexpected property '{k}'")
                elif isinstance(ap, dict):
                    validate(v, ap, root, f"{path}.{k}", errs)
    if "oneOf" in schema:
        good = 0
        for sub in schema["oneOf"]:
            e = []
            validate(value, sub, root, path, e)
            if not e:
                good += 1
        if good != 1:
            errs.append(f"{path}: matches {good} of the oneOf branches (need exactly 1)")


# ---------------------------------------------------------------- semantic checks
def strings(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for k, v in node.items():
            yield k
            yield from strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from strings(v)


def semantic(p, fname):
    errs = []
    a = p["acceptance"]
    concepts = {c["name"]: c for c in a["concepts"]}
    if len(concepts) != len(a["concepts"]):
        errs.append("duplicate concept names")
    roles = {r["name"] for r in a.get("roles", [])}
    flows = {r["name"] for r in a.get("flows", [])}
    views = {r["name"] for r in a.get("views", [])}
    fields = {}
    nfields = 0
    for c in a["concepts"]:
        names = [f["name"] for f in c["fields"]]
        if len(set(names)) != len(names):
            errs.append(f"concept {c['name']}: duplicate field names")
        nfields += len(names)
        fields[c["name"]] = {f["name"]: f for f in c["fields"]}
        for f in c["fields"]:
            if f["type"] == "reference" and f.get("references") not in concepts:
                errs.append(f"{c['name']}.{f['name']}: reference target {f.get('references')!r} is not an acceptance concept")
            if f["type"] == "enum" and not f.get("enumValues"):
                errs.append(f"{c['name']}.{f['name']}: enum field without enumValues")
            if f["type"] != "enum" and f.get("enumValues"):
                errs.append(f"{c['name']}.{f['name']}: enumValues on a non-enum field")
    if a["minimums"]["concepts"] < len(concepts):
        errs.append("minimums.concepts is below the number of acceptance concepts")
    if a["minimums"]["fields"] < nfields:
        errs.append(f"minimums.fields ({a['minimums']['fields']}) is below the declared field count ({nfields})")
    if a["minimums"]["fields"] > nfields:
        errs.append(f"minimums.fields ({a['minimums']['fields']}) is above the declared field count ({nfields}): "
                    f"a hidden requirement -- declare the extra field in the acceptance instead")
    if a["minimums"]["concepts"] > len(concepts):
        errs.append("minimums.concepts is above the number of acceptance concepts: declare the concept instead")

    probes = {}
    order = []
    for pr in a["probes"]:
        if pr["id"] in probes:
            errs.append(f"duplicate probe id {pr['id']}")
        probes[pr["id"]] = pr
        order.append(pr["id"])
    rule_ids = [r["id"] for r in a["rules"]]
    if len(set(rule_ids)) != len(rule_ids):
        errs.append("duplicate rule ids")

    def resolve_template(pid, kind, arg, seen_before):
        if kind in ("unique", "uuid", "now"):
            return
        if kind == "any":
            if not re.fullmatch(r"[0-9]+", arg or ""):
                errs.append(f"probe {pid}: bad {{any:}} argument {arg!r}")
            return
        if kind == "date":
            if not re.fullmatch(r"[+-]\d+", arg or ""):
                errs.append(f"probe {pid}: bad {{date:}} argument {arg!r}")
        elif kind == "concept":
            if arg not in concepts:
                errs.append(f"probe {pid}: {{concept:{arg}}} names no acceptance concept")
        elif kind == "flow":
            if arg not in flows:
                errs.append(f"probe {pid}: {{flow:{arg}}} names no acceptance flow")
        elif kind == "view":
            if arg not in views:
                errs.append(f"probe {pid}: {{view:{arg}}} names no acceptance view")
        elif kind == "id":
            if arg not in seen_before:
                errs.append(f"probe {pid}: {{id:{arg}}} does not name an earlier probe")
            elif probes[arg]["method"] != "POST":
                errs.append(f"probe {pid}: {{id:{arg}}} points at a non-POST probe")
        elif kind == "field":
            m = re.fullmatch(r"([A-Za-z0-9]+)\.([A-Za-z0-9]+)", arg or "")
            if not m or m.group(1) not in fields or m.group(2) not in fields[m.group(1)]:
                errs.append(f"probe {pid}: {{field:{arg}}} is not a declared concept.field")
        elif kind == "enum":
            m = re.fullmatch(r"([A-Za-z0-9]+)\.([A-Za-z0-9]+)=([A-Za-z][A-Za-z0-9]*)", arg or "")
            ok = False
            if m and m.group(1) in fields and m.group(2) in fields[m.group(1)]:
                ok = any(v["key"] == m.group(3) for v in fields[m.group(1)][m.group(2)].get("enumValues", []))
            if not ok:
                errs.append(f"probe {pid}: {{enum:{arg}}} is not a declared enum key")

    seen = []
    for pr in a["probes"]:
        pid = pr["id"]
        who = pr.get("as", "admin")
        if who != "admin" and who not in roles:
            errs.append(f"probe {pid}: acts as undeclared role {who!r}")
        for s in strings([pr["pathHint"], pr.get("body"), pr.get("expectJsonContains"), pr.get("expectJsonLacks")]):
            for m in TEMPLATE.finditer(s):
                resolve_template(pid, m.group(1), m.group(2), seen)
            stripped = TEMPLATE.sub("", s)
            if re.search(r"\{[^{}]*\}", stripped):
                errs.append(f"probe {pid}: unknown template in {s!r}")
        codes = pr["expectStatus"]
        if pr["purpose"] == "setup" and not all(200 <= c < 300 for c in codes):
            errs.append(f"probe {pid}: a setup probe must expect only 2xx")
        if pr["method"] == "GET" and pr.get("body"):
            errs.append(f"probe {pid}: GET with a body")
        seen.append(pid)

    used = set()
    kinds = set()
    for r in a["rules"]:
        c = r["check"]
        kinds.add(c["kind"])
        for pid in c["probes"]:
            used.add(pid)
            if pid not in probes:
                errs.append(f"rule {r['id']}: probe {pid} does not exist")
                continue
            pr = probes[pid]
            if pr["purpose"] != "assert":
                errs.append(f"rule {r['id']}: probe {pid} is a setup probe")
            codes = pr["expectStatus"]
            if c["kind"] in ("rest-reject", "role-denied") and not all(x in FOUR_XX for x in codes):
                errs.append(f"rule {r['id']} ({c['kind']}): probe {pid} must expect only 4xx")
            if c["kind"] in ("rest-accept", "derived-value") and not all(200 <= x < 300 for x in codes):
                errs.append(f"rule {r['id']} ({c['kind']}): probe {pid} must expect only 2xx")
            if c["kind"] == "role-denied" and pr.get("as", "admin") != c["role"]:
                errs.append(f"rule {r['id']}: probe {pid} must act as role {c['role']}")
            if c["kind"] == "derived-value" and not pr.get("expectJsonContains"):
                errs.append(f"rule {r['id']}: derived-value probe {pid} needs expectJsonContains")
        if c["kind"] == "role-denied":
            if c["role"] not in roles:
                errs.append(f"rule {r['id']}: role {c['role']!r} is not declared in acceptance.roles")
            if c["concept"] not in concepts:
                errs.append(f"rule {r['id']}: unknown concept {c['concept']!r}")
        if c["kind"] == "lifecycle":
            f = fields.get(c["concept"], {}).get(c["field"])
            if not f or f["type"] != "enum":
                errs.append(f"rule {r['id']}: {c['concept']}.{c['field']} is not an enum field")
            else:
                keys = {v["key"] for v in f["enumValues"]}
                for pair in c.get("allowed", []) + c.get("forbidden", []):
                    for k in pair:
                        if k not in keys:
                            errs.append(f"rule {r['id']}: state key {k!r} not in {sorted(keys)}")
            if not c.get("allowed") and not c.get("forbidden"):
                errs.append(f"rule {r['id']}: lifecycle rule lists no allowed or forbidden move")
        if c["kind"] == "derived-value":
            if c["concept"] in concepts:
                if c["field"] not in fields[c["concept"]]:
                    errs.append(f"rule {r['id']}: {c['concept']}.{c['field']} is not declared")
            elif c["concept"] not in views:
                errs.append(f"rule {r['id']}: {c['concept']!r} is neither a concept nor a view")
    for pid, pr in probes.items():
        if pr["purpose"] == "assert" and pid not in used:
            errs.append(f"assert probe {pid} is not referenced by any rule")

    for need in ("rest-reject", "rest-accept"):
        if need not in kinds:
            errs.append(f"no {need} rule")
    tier = p["tier"]
    if tier == "workflow-roles":
        for need in ("lifecycle", "role-denied"):
            if need not in kinds:
                errs.append(f"tier workflow-roles needs a {need} rule")
    if tier == "integration-report" and "derived-value" not in kinds:
        errs.append("tier integration-report needs a derived-value rule")

    # prompt hygiene
    text = p["title"] + "\n" + p["prompt"]
    for w in BANNED:
        if re.search(r"\b" + re.escape(w) + r"s?\b", text, re.I):
            errs.append(f"prompt/title contains the banned word {w!r}")
    sentences = [s for s in re.split(r"(?<=[.!?])\s+", p["prompt"].strip()) if s]
    if not 3 <= len(sentences) <= 8:
        errs.append(f"prompt has {len(sentences)} sentences (need 3 to 8)")
    if not re.fullmatch(r"bench-\d{3}-[a-z0-9]+(-[a-z0-9]+)*\.json", fname) or not fname.startswith(p["id"] + "-"):
        errs.append(f"file name {fname!r} must be <id>-<slug>.json")
    return errs


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=os.environ.get("NPDEV_AI_BENCH_DIR") or str(REPO / "NPDevSamples" / "ai-bench"))
    args = ap.parse_args()
    root = Path(args.root)
    findings = []

    def bad(where, msg):
        findings.append(f"{where}: {msg}")

    try:
        schema = json.loads((root / "prompt.schema.json").read_bytes().decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        print(f"FAIL cannot read {root / 'prompt.schema.json'}: {e}")
        return 1
    pdir = root / "prompts"
    files = sorted(pdir.glob("*.json")) if pdir.is_dir() else []
    prompts = {}
    for f in files:
        try:
            data = json.loads(f.read_bytes().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            bad(f.name, f"not valid JSON: {e}")
            continue
        errs = []
        try:
            validate(data, schema, schema, "$", errs)
        except SchemaError as e:
            print(f"FAIL checker limitation: {e}")
            return 1
        for e in errs:
            bad(f.name, "schema: " + e)
        if errs:
            continue
        for e in semantic(data, f.name):
            bad(f.name, e)
        if data["id"] in prompts:
            bad(f.name, f"duplicate id {data['id']}")
        prompts[data["id"]] = (data, f.name)

    if len(files) != TOTAL:
        bad("prompts", f"expected exactly {TOTAL} prompt files, found {len(files)}")
    want = {f"bench-{i:03d}" for i in range(1, TOTAL + 1)}
    if set(prompts) != want and len(prompts) == len(files):
        bad("prompts", f"ids must be bench-001..bench-030 contiguous; missing {sorted(want - set(prompts))} extra {sorted(set(prompts) - want)}")
    by_tier = {t: 0 for t in TIERS}
    for d, _ in prompts.values():
        by_tier[d["tier"]] += 1
    for t, n in by_tier.items():
        if n != PER_TIER:
            bad("prompts", f"tier {t} has {n} prompts, expected {PER_TIER}")
    titles = [d["title"].lower() for d, _ in prompts.values()]
    if len(set(titles)) != len(titles):
        bad("prompts", "duplicate titles")

    idx_path = root / "index.json"
    try:
        idx = json.loads(idx_path.read_bytes().decode("utf-8"))
        rows = idx.get("prompts", [])
        got = {r.get("id"): r for r in rows}
        if len(got) != len(rows):
            bad("index.json", "duplicate ids")
        for pid, (d, fname) in prompts.items():
            r = got.get(pid)
            if not r:
                bad("index.json", f"missing {pid}")
            elif (r.get("tier"), r.get("title"), r.get("file")) != (d["tier"], d["title"], "prompts/" + fname):
                bad("index.json", f"{pid}: tier/title/file differ from the prompt file")
        for pid in set(got) - set(prompts):
            bad("index.json", f"lists {pid} which has no valid prompt file")
    except Exception as e:  # noqa: BLE001
        bad("index.json", f"unreadable: {e}")

    if findings:
        print(f"FAIL check-ai-bench-prompts: {len(findings)} finding(s)")
        for f in findings:
            print("  " + f)
        return 1
    print(f"OK check-ai-bench-prompts: {len(prompts)} prompts ({', '.join(f'{t}={n}' for t, n in by_tier.items())}), index in sync")
    return 0


if __name__ == "__main__":
    sys.exit(main())
