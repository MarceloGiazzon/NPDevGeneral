"""Acceptance probe runner for the AI authoring benchmark (BENCH-2). Stdlib only.

Implements the template language declared in NPDevSamples/ai-bench/prompt.schema.json:
{concept:N} {id:pN} {field:C.f} {enum:C.f=key} {flow:N} {view:N} {unique} {uuid} {date:+N} {now}.
A probe that uses a template or logical name this runner cannot resolve is 'unresolved', not
'failed'; the scorecard counts both and neither passes acceptance.

The app is the source of truth for names: concepts/fields/collection routes come from the booted
app's metadata endpoint (GET /api/admin/runtime/metadata/concepts); flows and roles come from the
authored model.json (a flow is executed at /api/flows/<name>/execute).
"""
from __future__ import annotations

import datetime
import json
import re
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field


# ---------------------------------------------------------------------------------------------
# names
# ---------------------------------------------------------------------------------------------

def norm(name: str) -> str:
    """Case-insensitive, ignoring spaces/underscores/hyphens and one trailing plural 's'."""
    s = re.sub(r"[\s_\-]+", "", str(name or "")).lower()
    return s[:-1] if len(s) > 3 and s.endswith("s") else s


def name_matches(real: str, logical: str, aliases: list[str] | None = None) -> bool:
    want = {norm(logical)} | {norm(a) for a in (aliases or [])}
    return norm(real) in want


def words(name: str) -> list[str]:
    """camelCase / snake_case / spaced name -> lower-case words, one trailing plural 's' dropped each."""
    parts = re.findall(r"[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+", str(name or ""))
    return [w[:-1] if len(w) > 3 and w.endswith("s") else w for w in (p.lower() for p in parts)]


def words_contain(real: str, logical: str, aliases: list[str] | None = None) -> bool:
    have = words(real)
    for want in [logical, *(aliases or [])]:
        ww = words(want)
        if ww and all(w in have for w in ww):
            return True
    return False


# ---------------------------------------------------------------------------------------------
# catalog (what the app really exposes)
# ---------------------------------------------------------------------------------------------

@dataclass
class CatalogField:
    name: str
    type: str = ""
    required: bool = False
    enum_values: list[str] = field(default_factory=list)


@dataclass
class CatalogConcept:
    name: str
    collection: str
    fields: list[CatalogField] = field(default_factory=list)


@dataclass
class Catalog:
    concepts: list[CatalogConcept] = field(default_factory=list)
    flows: list[str] = field(default_factory=list)
    queries: list[str] = field(default_factory=list)
    roles: dict = field(default_factory=dict)   # real role name -> api key

    def find_concept(self, logical: str, aliases=None) -> CatalogConcept | None:
        for c in self.concepts:
            if name_matches(c.name.split(".")[-1], logical, aliases):
                return c
        return None

    def find_field(self, concept: CatalogConcept, logical: str, aliases=None) -> CatalogField | None:
        for f in concept.fields:
            if name_matches(f.name, logical, aliases):
                return f
        # No exact/alias match: accept the ONE field whose camelCase words contain a wanted name's words
        # ("minutes it takes to make" -> minutesToMake). The prompt never dictates field names, so a
        # descriptive name is not an authoring failure; an ambiguous containment still is.
        loose = [f for f in concept.fields if f.name.lower() != "id" and words_contain(f.name, logical, aliases)]
        return loose[0] if len(loose) == 1 else None

    def total_fields(self) -> int:
        return sum(len([f for f in c.fields if f.name.lower() != "id"]) for c in self.concepts)


def _pick(d: dict, *keys, default=None):
    for k in keys:
        if k in d and d[k] is not None:
            return d[k]
    return default


def catalog_from_metadata(concepts_payload, fields_payload=None) -> list[CatalogConcept]:
    """Parse GET /api/admin/runtime/metadata/concepts (items[].name, .fieldPaths) and, when given,
    .../metadata/fields (items[]: concept, fieldPath, parentPath, dslType, required, enumValues,
    referenceTarget). Top-level fields only. Tolerant of a missing fields catalog."""
    items = concepts_payload.get("items") if isinstance(concepts_payload, dict) else concepts_payload
    by_concept: dict[str, list[CatalogField]] = {}
    if isinstance(fields_payload, dict):
        for f in fields_payload.get("items") or []:
            if not isinstance(f, dict) or f.get("parentPath"):
                continue
            ev = [str(e.get("name") or e.get("key") or e.get("value") or e.get("label")) if isinstance(e, dict) else str(e)
                  for e in (f.get("enumValues") or [])]
            by_concept.setdefault(str(f.get("concept")), []).append(
                CatalogField(str(f.get("fieldPath") or f.get("segment")),
                             str(f.get("dslType") or f.get("schemaType") or ""),
                             bool(f.get("required")), ev))
    out: list[CatalogConcept] = []
    for item in items or []:
        if not isinstance(item, dict) or not item.get("name"):
            continue
        name = str(item["name"])
        fields = by_concept.get(name)
        if fields is None:
            fields = [CatalogField(str(fp)) for fp in (item.get("fieldPaths") or []) if "." not in str(fp)]
        out.append(CatalogConcept(name, "", fields))
    return out


# ---------------------------------------------------------------------------------------------
# http
# ---------------------------------------------------------------------------------------------

def http_call(base_url: str, method: str, path: str, body, api_key: str | None, timeout: float = 30.0):
    """Returns (status, parsed_json_or_text_or_None). status 0 = connection problem."""
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if api_key:
        headers["X-Api-Key"] = api_key
    req = urllib.request.Request(base_url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status, raw = resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        status, raw = exc.code, exc.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as exc:
        return 0, f"connection error: {exc}"
    try:
        return status, json.loads(raw) if raw.strip() else None
    except ValueError:
        return status, raw[:500]


def fetch_catalog(base_url: str, api_key: str | None, model: dict | None) -> tuple[Catalog, str]:
    cat = Catalog()
    base = "/api/admin/runtime/metadata/"
    status, concepts = http_call(base_url, "GET", base + "concepts", None, api_key)
    err = ""
    if status == 200 and isinstance(concepts, (dict, list)):
        _, fields = http_call(base_url, "GET", base + "fields", None, api_key)
        cat.concepts = catalog_from_metadata(concepts, fields)
    else:
        err = f"metadata endpoint returned {status}"
    # OpenAPI is the route truth: pick the real /api/<collection> segment, fall back to the port.
    _, api = http_call(base_url, "GET", "/v3/api-docs", None, api_key)
    segments, queries, flows = set(), [], []
    for path in ((api or {}).get("paths") or {}) if isinstance(api, dict) else []:
        m = re.match(r"^/api/(?:v1/)?([^/{]+)", path)
        if m:
            segments.add(m.group(1))
        q = re.match(r"^/api/queries/([^/{]+)$", path)
        if q:
            queries.append(q.group(1))
        f = re.match(r"^/api/(?:v1/)?flows/([^/{]+)/execute$", path)
        if f:
            flows.append(f.group(1))
    for c in cat.concepts:
        guess = rest_collection_name(c.name)
        c.collection = guess if (guess in segments or not segments) else next(
            (s for s in sorted(segments) if norm(s) == norm(guess)), guess)
    cat.queries = queries
    cat.flows = list(dict.fromkeys(
        flows + ([str(f.get("name")) for f in (model.get("flows") or []) if isinstance(f, dict) and f.get("name")]
                 if model else [])))
    return cat, err


# --- python port of SqlIdentifierSupport.restCollectionName, used only when the metadata gives no route
_IRREGULAR = {"person": "people", "child": "children", "man": "men", "woman": "women", "mouse": "mice",
              "goose": "geese", "foot": "feet", "tooth": "teeth"}


def _snake(name: str) -> str:
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "_", name)
    return re.sub(r"[^A-Za-z0-9]+", "_", s).strip("_").lower()


def rest_collection_name(concept_name: str) -> str:
    base = _snake(concept_name.split(".")[-1])
    if not base:
        return base
    head, _, last = base.rpartition("_")
    pre = head + "_" if head else ""
    if last in _IRREGULAR:
        return pre + _IRREGULAR[last]
    if last.endswith("s") and not last.endswith("ss"):
        return base
    if re.search(r"(s|x|z|ch|sh)$", last):
        return pre + last + "es"
    if re.search(r"[^aeiou]y$", last):
        return pre + last[:-1] + "ies"
    return pre + last + "s"


# ---------------------------------------------------------------------------------------------
# templates
# ---------------------------------------------------------------------------------------------

class Unresolved(Exception):
    pass


_TOKEN = re.compile(r"\{([a-z]+)(?::([^{}]*))?\}")


class Resolver:
    def __init__(self, acceptance: dict, catalog: Catalog):
        self.acc, self.cat = acceptance, catalog
        self.captured: dict[str, str] = {}
        self.unique = uuid.uuid4().hex[:8]
        self._concepts = {c["name"]: c for c in acceptance.get("concepts", [])}

    # logical -> real
    def concept(self, name: str) -> CatalogConcept:
        spec = self._concepts.get(name) or {"name": name}
        real = self.cat.find_concept(spec["name"], spec.get("aliases"))
        if real is None:
            raise Unresolved(f"concept '{name}' not found in the app")
        return real

    def field_name(self, ref: str) -> str:
        cname, _, fname = ref.partition(".")
        spec = self._concepts.get(cname) or {"name": cname, "fields": []}
        real_c = self.concept(cname)
        fspec = next((f for f in spec.get("fields", []) if f["name"] == fname), {"name": fname})
        real_f = self.cat.find_field(real_c, fspec["name"], fspec.get("aliases"))
        if real_f is None:
            raise Unresolved(f"field '{ref}' not found in the app")
        return real_f.name

    def enum_value(self, ref: str) -> str:
        left, _, key = ref.partition("=")
        cname, _, fname = left.partition(".")
        real_c = self.concept(cname)
        spec = self._concepts.get(cname) or {"fields": []}
        fspec = next((f for f in spec.get("fields", []) if f["name"] == fname), {"name": fname})
        real_f = self.cat.find_field(real_c, fspec["name"], fspec.get("aliases"))
        if real_f is None:
            raise Unresolved(f"field '{left}' not found in the app")
        aliases = []
        for ev in fspec.get("enumValues", []):
            if ev["key"] == key:
                aliases = ev.get("aliases", [])
        for value in real_f.enum_values:
            if name_matches(value, key, aliases):
                return value
        raise Unresolved(f"enum value '{key}' of '{left}' not found in the app (has {real_f.enum_values})")

    def flow(self, name: str) -> str:
        spec = next((f for f in self.acc.get("flows", []) if f["name"] == name), {"name": name})
        for real in self.cat.flows:
            if name_matches(real, spec["name"], spec.get("aliases")):
                return real
        raise Unresolved(f"flow '{name}' not found in the authored model")

    def token(self, kind: str, arg: str | None) -> str:
        arg = arg or ""
        if kind == "concept":
            return self.concept(arg).collection
        if kind == "id":
            if arg not in self.captured:
                raise Unresolved(f"no id captured from probe '{arg}'")
            return self.captured[arg]
        if kind == "field":
            return self.field_name(arg)
        if kind == "enum":
            return self.enum_value(arg)
        if kind == "flow":
            return "flows/" + self.flow(arg) + "/execute"  # /api/flows/<name>/execute
        if kind == "view":
            spec = next((v for v in self.acc.get("views", []) if v["name"] == arg), {"name": arg})
            for real in self.cat.queries:
                if name_matches(real, spec["name"], spec.get("aliases")):
                    return "queries/" + real
            raise Unresolved(f"view '{arg}' not found among the app's queries ({self.cat.queries})")
        if kind == "unique":
            return self.unique
        if kind == "uuid":
            return str(uuid.uuid4())
        if kind == "date":
            return (datetime.date.today() + datetime.timedelta(days=int(arg))).isoformat()
        if kind == "now":
            return datetime.datetime.now().replace(microsecond=0).isoformat()
        raise Unresolved(f"unknown template {{{kind}:{arg}}}")

    def text(self, s: str) -> str:
        return _TOKEN.sub(lambda m: self.token(m.group(1), m.group(2)), s)

    def value(self, v):
        if isinstance(v, str):
            return self.text(v)
        if isinstance(v, dict):
            return {self.text(k): self.value(x) for k, x in v.items()}
        if isinstance(v, list):
            return [self.value(x) for x in v]
        return v

    def path(self, hint: str) -> str:
        path = self.text(hint)
        if not path.startswith("/"):
            path = "/api/" + path
        return path


# ---------------------------------------------------------------------------------------------
# matching
# ---------------------------------------------------------------------------------------------

def _eq(a, b) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(float(a) - float(b)) < 1e-6
    if isinstance(a, (int, float)) and isinstance(b, str) or isinstance(b, (int, float)) and isinstance(a, str):
        try:
            return abs(float(a) - float(b)) < 1e-6
        except ValueError:
            return False
    return a == b


def subset(actual, expected) -> bool:
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(k in actual and subset(actual[k], v) for k, v in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and all(any(subset(a, e) for a in actual) for e in expected)
    return _eq(actual, expected)


def candidate_rows(resp) -> list:
    rows = [resp]
    if isinstance(resp, list):
        rows += resp
    elif isinstance(resp, dict):
        for v in resp.values():
            if isinstance(v, list):
                rows += v
            elif isinstance(v, dict):
                rows.append(v)
    return rows


def extract_id(resp) -> str | None:
    if isinstance(resp, dict):
        for k in ("id", "Id", "ID"):
            if resp.get(k) is not None:
                return str(resp[k])
        for v in resp.values():
            if isinstance(v, dict):
                got = extract_id(v)
                if got:
                    return got
    return None


# ---------------------------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------------------------

def run_probe(probe: dict, res: Resolver, base_url: str, keys: dict, admin_key: str) -> dict:
    out = {"id": probe["id"], "purpose": probe.get("purpose"), "state": "failed", "detail": ""}
    try:
        as_role = probe.get("as", "admin")
        if as_role == "admin":
            key = admin_key
        else:
            key = next((k for r, k in keys.items() if name_matches(r, as_role)), None)
            if key is None:
                raise Unresolved(f"role '{as_role}' not found in the authored model")
        path = res.path(probe["pathHint"])
        body = res.value(probe["body"]) if probe.get("body") is not None else None
        expect = res.value(probe["expectJsonContains"]) if probe.get("expectJsonContains") else None
    except Unresolved as exc:
        out["state"], out["detail"] = "unresolved", str(exc)
        return out
    method = probe["method"]
    status, resp = http_call(base_url, method, path, body, key)
    if status == 405 and method == "PATCH":
        status, resp = http_call(base_url, "PUT", path, body, key)
    out.update({"request": f"{method} {path}", "status": status})
    ok = status in probe["expectStatus"]
    detail = "" if ok else f"expected status {probe['expectStatus']}, got {status}: {json.dumps(resp)[:300]}"
    if ok and expect is not None and not any(subset(row, expect) for row in candidate_rows(resp)):
        ok, detail = False, f"response does not contain {json.dumps(expect)[:200]}; got {json.dumps(resp)[:300]}"
    if (probe.get("capture") or (probe["method"] == "POST" and probe.get("purpose") == "setup")) and 200 <= status < 300:
        got = extract_id(resp)
        if got:
            res.captured[probe["id"]] = got
    out["state"], out["detail"] = ("passed" if ok else "failed"), detail
    return out


def check_concepts(acceptance: dict, catalog: Catalog) -> list[str]:
    problems = []
    for spec in acceptance.get("concepts", []):
        real = catalog.find_concept(spec["name"], spec.get("aliases"))
        if real is None:
            problems.append(f"concept '{spec['name']}' is missing (the app has: "
                            f"{', '.join(c.name for c in catalog.concepts) or 'none'})")
            continue
        for f in spec.get("fields", []):
            if catalog.find_field(real, f["name"], f.get("aliases")) is None:
                problems.append(f"field '{spec['name']}.{f['name']}' ({f['type']}) is missing from concept "
                                f"{real.name} (it has: {', '.join(x.name for x in real.fields)})")
    mins = acceptance.get("minimums") or {}
    if len(catalog.concepts) < mins.get("concepts", 0):
        problems.append(f"app has {len(catalog.concepts)} concepts, minimum is {mins['concepts']}")
    if catalog.total_fields() < mins.get("fields", 0):
        problems.append(f"app has {catalog.total_fields()} fields, minimum is {mins['fields']}")
    return problems


def run_acceptance(acceptance: dict, base_url: str, admin_key: str, role_keys: dict, model: dict | None) -> dict:
    catalog, cat_err = fetch_catalog(base_url, admin_key, model)
    problems = check_concepts(acceptance, catalog)
    res = Resolver(acceptance, catalog)
    results = [run_probe(p, res, base_url, role_keys, admin_key) for p in acceptance.get("probes", [])]
    by_id = {r["id"]: r for r in results}
    rules = []
    for rule in acceptance.get("rules", []):
        states = [by_id.get(pid, {}).get("state", "unresolved") for pid in rule["check"].get("probes", [])]
        state = ("failed" if "failed" in states else "unresolved" if "unresolved" in states else "passed")
        rules.append({"id": rule["id"], "kind": rule["check"]["kind"], "state": state,
                      "description": rule["description"]})
    setup_bad = [r for r in results if r.get("purpose") == "setup" and r["state"] != "passed"]
    counts = {s: sum(1 for r in results if r["state"] == s) for s in ("passed", "failed", "unresolved")}
    ok = (not problems and not cat_err and not setup_bad and all(r["state"] == "passed" for r in rules))
    return {"ok": ok, "metadataError": cat_err, "conceptProblems": problems, "probes": results, "rules": rules,
            "probeCounts": counts, "ruleCounts": {s: sum(1 for r in rules if r["state"] == s)
                                                  for s in ("passed", "failed", "unresolved")},
            "catalogConcepts": [c.name for c in catalog.concepts]}


def feedback_text(acc_result: dict) -> str:
    lines = []
    if acc_result.get("metadataError"):
        lines.append("- " + acc_result["metadataError"])
    lines += ["- " + p for p in acc_result.get("conceptProblems", [])]
    for r in acc_result.get("rules", []):
        if r["state"] != "passed":
            lines.append(f"- rule {r['id']} {r['state']}: {r['description']}")
    for p in acc_result.get("probes", []):
        if p["state"] != "passed":
            lines.append(f"- probe {p['id']} {p['state']}: {p.get('request', '')} {p['detail']}")
    return "\n".join(lines) or "acceptance did not pass"
