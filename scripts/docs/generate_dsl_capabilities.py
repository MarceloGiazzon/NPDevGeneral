#!/usr/bin/env python
"""Renders docs/DSL_CAPABILITIES.html: the capabilities catalogue -- EVERY keyword of the model DSL, one row each,
with the evidence that it is a real, used and tested capability, a real example, and why it exists.

Audience: specialists and non-specialists. The page teaches (what a keyword is, how to read each column) and it is the
tool for keeping the language small: a keyword with no official-sample use, no test and no justification is a
candidate to remove; a keyword with all of them is a capability the platform can stand behind.

The page is generated, never hand-edited. It is a SIBLING of docs/DSL_KEYWORD_REFERENCE.md (that page is the written
reference, this one is the evidence table) and reuses its loaders, so the keyword set is the budget's own slot set
(`<def>.<property>`, `pack:<property>`) plus the model root keys (`root:<property>`). Sources, all structured data:

  * NPDevContract/schemas/model.schema.json, pack.schema.json -- the keywords, types, enums, defaults, descriptions.
  * scripts/policy/dsl-keyword-meanings/*.json  -- the hand-written meaning of each keyword, the grouping under the
    twelve nouns, the per-definition reference example, legacy status.
  * scripts/policy/dsl-keyword-admissions/*.json -- the written justification (five answers) of admitted keywords.
  * NPDevSamples/*/Input/model.json and NPDevContract/packs/*/pack.json -- the OFFICIAL samples. A schema-guided walk
    attributes every key of every sample to the exact definition that declares it, so a key called `label` is credited
    to `actionMetadata.label` only where it sits in an actionMetadata object.
  * The Java / Python / JS sources (implementation) and their test sources (tests) -- by NAME: the key written as a
    string literal or a property access. A name several keywords share is flagged `shared`, because its evidence
    cannot say which of them it proves.

Nothing here reads markdown. The output carries no date, so a re-render of unchanged inputs is byte-identical.

Usage: python scripts/docs/generate_dsl_capabilities.py [--check]
  --check  verify the existing page lists exactly the current keyword set (exit 1 if the schema moved on)
"""
from __future__ import annotations

import importlib.util
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "DSL_CAPABILITIES.html"
ADMISSIONS = ROOT / "scripts" / "policy" / "dsl-keyword-admissions"
STORIES = ROOT / "scripts" / "policy" / "dsl-keyword-stories"
REASONS = ROOT / "scripts" / "policy" / "dsl-keyword-reasons"
CORPUS_MODEL = "NPDevSamples/dsl-conformance-max"
# A keyword the parser never reads is dropped silently (the REG-108 class). "Implemented" is judged by WHICH LAYERS name
# the keyword (see LAYERS below), and only code that acts counts: a parser, an AST class or the compiled-model writer
# naming a keyword is plumbing, not behaviour.
# Models that exist to show or fix the language (scripts/quality/corpus-roles.json); repro cases and engine variants
# are corpus members too, but they prove nothing about what a keyword is FOR.
OFFICIAL_ROLES = {"showcase", "canary", "dsl-fixture"}
DSL_JAVA_PREFIX = "NPDevContract/dsl/src/main/java/"


def _load_reference_module():
    path = ROOT / "scripts" / "docs" / "generate_dsl_keyword_reference.py"
    spec = importlib.util.spec_from_file_location("npdev_keyword_reference", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["npdev_keyword_reference"] = module
    spec.loader.exec_module(module)
    return module


REF = _load_reference_module()

# --------------------------------------------------------------------------- code and test evidence

SKIP_PARTS = {"build", "node_modules", "target", ".git", "__pycache__", ".gradle", "Build", "dist", "vendor", "lib", "libs"}
CODE_EXT = {".java", ".py", ".js", ".mjs", ".mustache", ".html"}
MAIN_ROOTS = [
    "NPDevContract/dsl/src/main", "NPDevGenerator/generator/src/main", "NPDevGenerator/tools",
    # the DSL and generator unit tests are where most keywords are exercised; without them the page said "no test names it"
    # for keywords whose test sits right beside the parser (found 2026-11-22: a test written for 8 keywords did not count)
    "NPDevContract/dsl/src/test", "NPDevGenerator/generator/src/test",
    "NPDevKernel", "NPDevRuntimeHost", "NPDevCli", "NPDevManager/ui", "NPDevMcp",
]
# a leading "$" is allowed: "$ref" and "$schema" are keywords too (before 2026-11-22 they could never be found, so the five
# "$" slots always showed "no test names it")
STR_TOKEN = re.compile(r"""(?<![A-Za-z0-9_])\\?["']([$A-Za-z_][$A-Za-z0-9_]*)\\?["']""")
DOT_TOKEN = re.compile(r"\.([A-Za-z_][A-Za-z0-9_]*)")
JS_KEY_TOKEN = re.compile(r"(?:[{,]|^)[ \t]*([A-Za-z_$][A-Za-z0-9_$]*)[ \t]*:", re.M)
# Java reads most keywords through ACCESSORS, not through their quoted name: `flow.isStartEndpoint()`, `step.getOutput()`,
# a record accessor `hooks.onLoad()`. A scan of quoted names alone (what this page did before 2026-11-22) calls every
# such keyword "only named by the parser" or "named by nothing" even when the kernel acts on it, so accessors count too.
CALL_TOKEN = re.compile(r"\.([A-Za-z_][A-Za-z0-9_]*)\s*\(")
GETTER_TOKEN = re.compile(r"\b(?:get|is|has)([A-Z][A-Za-z0-9_]*)\s*\(")
MUSTACHE_TOKEN = re.compile(r"\{\{[#^/&>]?\s*([A-Za-z_][\w.]*)")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"(?m)(^|\s)//.*$")
PY_COMMENT = re.compile(r"(?m)^\s*#.*$")

# Where a name was found decides what it proves. The DSL module is a pipeline: the PARSER reads the JSON, the CARRIERS
# (AST, compiled model, resolver, the compiler's main class, the canonical-JSON writers) copy a value along without
# looking at it, the VALIDATORS check it, other DSL classes (expression engine, pack and reference tools, the screen
# expander) act on it while the model is BUILT, and only code outside the module (generator, kernel, runtime host, UI)
# acts on it when the app RUNS. Naming a keyword in a carrier proves nothing about behaviour.
DSL_V1 = DSL_JAVA_PREFIX + "com/npdev/dsl/v1/"
CARRIER_DIRS = (DSL_V1 + "ast/", DSL_V1 + "compiled/", DSL_V1 + "resolution/")
CARRIER_FILES = {DSL_V1 + "compiler/ModelCompiler.java"}
PARSER_FILE = DSL_V1 + "parser/JsonModelParser.java"
LAYERS = ("parser", "carried", "checked", "build", "runtime", "tooling")
# The resolver and the compiler's main class both COPY values and ACT on them. A keyword they test in a condition or read
# into a local variable is acted on; one only handed to a constructor is copied. Only these lines count as acting.
MIXED_FILES = {DSL_V1 + "resolution/ModelResolver.java", DSL_V1 + "compiler/ModelCompiler.java"}
CONTROL_LINE = re.compile(
    r"^\s*(?:\}\s*else\s*)?(?:if|while|switch)\s*\("
    r"|^\s*(?:final\s+)?[A-Za-z_][\w<>\[\],.? ]*\s+[a-z]\w*\s*=\s*"
)


def _layer_of(rel: str) -> str:
    if rel == PARSER_FILE:
        return "parser"
    if rel.startswith(CARRIER_DIRS) or rel in CARRIER_FILES:
        return "carried"
    if rel.startswith(DSL_V1 + "validation/"):
        return "checked"
    if rel.startswith("NPDevContract/dsl/"):
        return "build"
    if rel.startswith(("NPDevGenerator/", "NPDevKernel/", "NPDevRuntimeHost/")):
        return "runtime"
    return "tooling"


def _is_test(rel: str) -> bool:
    parts = rel.split("/")
    return "test" in parts or "tests" in parts or parts[-1].startswith("test_") or parts[-1].endswith("Test.java")


def _tokens_of(text: str, suffix: str) -> set[str]:
    tokens = set(STR_TOKEN.findall(text))
    if suffix == ".java":
        tokens |= set(CALL_TOKEN.findall(text))
        tokens |= {name[0].lower() + name[1:] for name in GETTER_TOKEN.findall(text)}
    if suffix in (".js", ".mjs", ".mustache", ".html"):
        tokens |= set(DOT_TOKEN.findall(text))
    if suffix in (".js", ".mjs"):
        # a JS test or template writes a keyword as an unquoted object key: `{ center: "me" }` (found 2026-11-22: the
        # map tests for mapSpec.center were invisible because only a quoted "center" counted)
        tokens |= set(JS_KEY_TOKEN.findall(text))
    if suffix in (".mustache", ".html"):
        for tag in MUSTACHE_TOKEN.findall(text):
            tokens |= set(tag.split("."))
    return tokens


def scan_sources() -> tuple[dict[str, dict[str, set[str]]], dict[str, set[str]]]:
    """token -> layer -> files for implementation files; token -> files for test files."""
    main: dict[str, dict[str, set[str]]] = {}
    test: dict[str, set[str]] = {}
    for base in MAIN_ROOTS:
        root = ROOT / base
        if not root.exists():
            continue
        for path in sorted(root.rglob("*")):
            if path.suffix not in CODE_EXT or not path.is_file():
                continue
            rel = path.relative_to(ROOT).as_posix()
            if set(rel.split("/")) & SKIP_PARTS or ".min." in path.name:
                continue
            try:
                if path.stat().st_size > 1_500_000:
                    continue
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            if path.suffix == ".py":
                text = PY_COMMENT.sub("", text)
            elif path.suffix in (".java", ".js", ".mjs"):
                text = LINE_COMMENT.sub(r"\1", BLOCK_COMMENT.sub("", text))
            tokens = _tokens_of(text, path.suffix)
            if _is_test(rel):
                for token in tokens:
                    test.setdefault(token, set()).add(rel)
            else:
                layer = _layer_of(rel)
                for token in tokens:
                    main.setdefault(token, {}).setdefault(layer, set()).add(rel)
                if rel in MIXED_FILES:
                    acting = "\n".join(line for line in text.splitlines() if CONTROL_LINE.match(line))
                    for token in _tokens_of(acting, path.suffix):
                        main.setdefault(token, {}).setdefault("build", set()).add(rel)
    return main, test


_SHORT_PREFIXES = (
    (DSL_V1, "dsl/"),
    ("NPDevGenerator/generator/src/main/java/com/npdev/generator/", "generator/"),
    ("NPDevGenerator/generator/src/main/resources/npdev-templates/", "templates/"),
    ("NPDevKernel/kernel/src/main/java/com/npdev/kernel/", "kernel/"),
    ("NPDevRuntimeHost/runtimehost-core/src/main/java/com/finalexec/", "runtimehost/"),
    ("NPDevRuntimeHost/src/main/java/com/finalexec/", "runtimehost-app/"),
)


def short_path(rel: str) -> str:
    """A repo path shortened for the page (the full path is one grep away; the page carries ~4,000 of them)."""
    for prefix, short in _SHORT_PREFIXES:
        if rel.startswith(prefix):
            return short + rel[len(prefix):]
    return rel


def support_of(prop: str, by_layer: dict[str, list[str]]) -> str:
    """What the platform does with a keyword, judged by the layers that name it (see LAYERS above):
    runs (code outside the DSL module acts on it), builds (the model pipeline acts on it before the app exists),
    tooling (only the CLI / AI tooling names it), checked (only a validator reads it), carried (only the parser and the
    compiled-model plumbing copy it), ignored (no code names it at all), editor (a file-structure key such as $ref)."""
    if prop.startswith("$"):
        return "editor"
    if by_layer.get("runtime"):
        return "runs"
    if by_layer.get("build") or (by_layer.get("parser") and not by_layer.get("carried")):
        # a keyword only the parser names and no AST / compiled class carries is consumed by the parser itself
        return "builds"
    if by_layer.get("tooling"):
        return "tooling"
    if by_layer.get("checked"):
        return "checked"
    if by_layer.get("parser") or by_layer.get("carried"):
        return "carried"
    return "ignored"


# --------------------------------------------------------------------------- official samples, schema-guided

def _members(node: dict) -> list:
    """The sub-schemas a node may stand for: allOf / oneOf / anyOf members and the then / else of an if."""
    out: list = []
    for combo in ("allOf", "oneOf", "anyOf"):
        out.extend(node.get(combo) or [])
    for branch in ("then", "else"):
        if isinstance(node.get(branch), dict):
            out.append(node[branch])
    return out


def _resolve(node, defs: dict, depth: int = 0) -> list[tuple[str | None, dict]]:
    """(definition name or None, object schema) pairs a node stands for, through $ref / allOf / oneOf / anyOf."""
    if not isinstance(node, dict) or depth > 14:
        return []
    out: list[tuple[str | None, dict]] = []
    if "$ref" in node:
        name = node["$ref"].rsplit("/", 1)[-1]
        target = defs.get(name)
        if target is None:
            return []
        out.append((name, target))
        if isinstance(target, dict) and "$ref" in target:
            # a definition that is only an alias of another one (uiField -> presentationMetadata): follow it,
            # or every keyword used through the alias shows as unused
            out.extend(_resolve(target, defs, depth + 1))
        for member in _members(target):
            out.extend(_resolve(member, defs, depth + 1))
        return out
    out.append((None, node))
    for member in _members(node):
        out.extend(_resolve(member, defs, depth + 1))
    return out


def _trim(value, budget: int = 90, depth: int = 0):
    """A container too long to read at a glance becomes an ellipsis marker; short ones are kept whole."""
    if isinstance(value, (dict, list)) and len(json.dumps(value, ensure_ascii=False)) > budget:
        if depth >= 1:
            return "..."
        if isinstance(value, dict):
            return {k: _trim(v, budget, depth + 1) for k, v in list(value.items())[:6]}
        return [_trim(v, budget, depth + 1) for v in value[:2]] + (["..."] if len(value) > 2 else [])
    return value


def _snippet(parent: dict, key: str) -> str:
    kept: dict = {}
    for k, v in parent.items():
        if k == key:
            kept[k] = _trim(v, 140)
        elif isinstance(v, (dict, list)):
            kept[k] = v if len(json.dumps(v, ensure_ascii=False)) <= 80 else "..."
        else:
            kept[k] = v
    text = json.dumps(kept, indent=2, ensure_ascii=False)
    if len(text) > 900:
        small = {key: kept[key]}
        for k, v in kept.items():
            if k != key and len(json.dumps(small, ensure_ascii=False)) < 500:
                small[k] = v
        text = json.dumps(small, indent=2, ensure_ascii=False)
    return text


class Usage:
    def __init__(self, slots: set[str], root_slots: set[str]) -> None:
        self.slots = slots
        self.root_slots = root_slots
        self.models: dict[str, dict[str, int]] = {}
        self.examples: dict[str, list[tuple[str, str, str]]] = {}
        self.other_models: set[str] = set()

    def record(self, slot: str, label: str, path: str, parent: dict, key: str) -> None:
        self.models.setdefault(slot, {}).setdefault(label, 0)
        self.models[slot][label] += 1
        bucket = self.examples.setdefault(slot, [])
        if len(bucket) < 8 and not any(b[0] == label for b in bucket):
            bucket.append((label, path, _snippet(parent, key)))


def _walk(inst, node, defs, path, label, usage: Usage, root_kind, seen: set, top: bool = False) -> None:
    if not isinstance(inst, (dict, list)):
        return
    marker = (id(inst), id(node))
    if marker in seen:
        return
    seen.add(marker)
    for def_name, obj in _resolve(node, defs):
        if isinstance(inst, dict):
            props = obj.get("properties") or {}
            for key, value in inst.items():
                child_path = f"{path}.{key}" if path else key
                if key in props:
                    if def_name and f"{def_name}.{key}" in usage.slots:
                        usage.record(f"{def_name}.{key}", label, path or "(root)", inst, key)
                    elif top and obj is node and f"{root_kind}:{key}" in (usage.slots | usage.root_slots):
                        usage.record(f"{root_kind}:{key}", label, "(root)", inst, key)
                    _walk(value, props[key], defs, child_path, label, usage, root_kind, seen)
                else:
                    additional = obj.get("additionalProperties")
                    if isinstance(additional, dict):
                        _walk(value, additional, defs, child_path, label, usage, root_kind, seen)
                    for pattern, sub in (obj.get("patternProperties") or {}).items():
                        if re.search(pattern, key):
                            _walk(value, sub, defs, child_path, label, usage, root_kind, seen)
        else:
            items = obj.get("items")
            if isinstance(items, dict):
                for index, element in enumerate(inst):
                    _walk(element, items, defs, f"{path}[{index}]", label, usage, root_kind, seen)


def official_usage(model: dict, pack: dict, slots: set[str], root_slots: set[str]) -> tuple[Usage, list[str]]:
    usage = Usage(slots, root_slots)
    defs = dict(model.get("$defs", {}))
    for name, value in (pack.get("$defs") or {}).items():
        defs.setdefault(name, value)
    labels: list[str] = []
    sys.path.insert(0, str(ROOT / "scripts" / "quality"))
    from dsl_coverage.constants import DEFAULT_APPGEN_ROOT, DEFAULT_SAMPLES_ROOT  # noqa: E402
    from dsl_coverage.corpus import find_models  # noqa: E402
    roles = json.loads((ROOT / "scripts" / "quality" / "corpus-roles.json").read_text(encoding="utf-8"))["roles"]
    for label, path in find_models(DEFAULT_APPGEN_ROOT, DEFAULT_SAMPLES_ROOT):
        role = roles.get(label, "unclassified")
        if role not in OFFICIAL_ROLES:
            usage.other_models.add(label)
        try:
            data = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            continue
        if not isinstance(data, dict):
            continue
        if role in OFFICIAL_ROLES:
            labels.append(label)
        _walk(data, model, defs, "", label, usage, "root", set(), top=True)
        if role in OFFICIAL_ROLES:
            # The pack files and context fragments a sample ships beside its model are pack-schema files (pack.schema.json
            # keys such as `experiences`, `seeds`, `imports`) and the sample really uses them. Until 2026-11-24 only the
            # built-in packs were read, so 13 pack keywords showed "no official sample" although the fixture exercises them.
            for extra in sorted(list((path.parent / "packs").glob("*/pack.json")) + list((path.parent / "contexts").glob("*.json"))):
                try:
                    extra_data = json.loads(extra.read_text(encoding="utf-8-sig"))
                except (OSError, ValueError):
                    continue
                if isinstance(extra_data, dict):
                    _walk(extra_data, pack, defs, "", label, usage, "pack", set(), top=True)
    for path in sorted(ROOT.glob("NPDevContract/packs/*/pack.json")):
        label = f"pack:{path.parent.name}"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        labels.append(label)
        _walk(data, pack, defs, "", label, usage, "pack", set(), top=True)
    return usage, labels


# --------------------------------------------------------------------------- the rows

def _kind(prop: dict) -> str:
    if not isinstance(prop, dict):
        return ""
    if "$ref" in prop:
        return "-> " + prop["$ref"].rsplit("/", 1)[-1]
    if "enum" in prop:
        values = [str(v) for v in prop["enum"]]
        return "one of: " + ", ".join(values[:8]) + (" ..." if len(values) > 8 else "")
    if "const" in prop:
        return f"exactly {prop['const']!r}"
    kind = prop.get("type")
    if kind == "object" and prop.get("properties"):
        names = list(prop["properties"])
        return "object {" + ", ".join(names[:6]) + (", ..." if len(names) > 6 else "") + "}"
    if kind == "array":
        inner = _kind(prop.get("items") or {})
        return f"list of {inner}" if inner else "list"
    if isinstance(kind, list):
        return " or ".join(kind)
    if kind:
        extra = []
        for key in ("minimum", "maximum", "minLength", "maxLength", "pattern", "format"):
            if key in prop:
                extra.append(f"{key} {prop[key]}")
        return kind + (f" ({', '.join(extra)})" if extra else "")
    for combo in ("oneOf", "anyOf"):
        if combo in prop:
            parts = [p for p in (_kind(m) for m in prop[combo]) if p]
            return "one of: " + " | ".join(parts[:5])
    if "properties" in prop:
        names = list(prop["properties"])
        return "object {" + ", ".join(names[:6]) + (", ..." if len(names) > 6 else "") + "}"
    return ""


def _has_key(node, key: str) -> bool:
    if isinstance(node, dict):
        return key in node or any(_has_key(v, key) for v in node.values())
    if isinstance(node, list):
        return any(_has_key(v, key) for v in node)
    return False


def load_admissions() -> dict[str, list[dict]]:
    by_slot: dict[str, list[dict]] = {}
    for path in sorted(ADMISSIONS.glob("*.json")):
        record = json.loads(path.read_text(encoding="utf-8"))
        if record.get("status") == "withdrawn":
            continue
        why = {}
        for question, answer in (record.get("answers") or {}).items():
            text = answer.get("why") or answer.get("apps") or answer.get("probe") or answer.get("bundleLine") or ""
            if text:
                why[question] = text if isinstance(text, str) else json.dumps(text, ensure_ascii=False)
        entry = {"record": path.name, "keyword": record.get("keyword", ""), "why": why, "note": record.get("$comment", "")}
        for slot in record.get("slots") or []:
            by_slot.setdefault(slot, []).append(entry)
    return by_slot


def load_stories() -> dict[str, str]:
    """slot id -> the everyday story (scripts/policy/dsl-keyword-stories/stories-*.json), owner request 2026-11-22."""
    stories: dict[str, str] = {}
    for path in sorted(STORIES.glob("stories-*.json")):
        for slot, text in (json.loads(path.read_text(encoding="utf-8")).get("stories") or {}).items():
            if slot in stories:
                raise SystemExit(f"{path.name}: duplicate story for {slot}")
            stories[slot] = text.strip()
    return stories


def load_reasons() -> dict[str, dict]:
    """slot id -> {record, why} for the one-line written reasons (scripts/policy/dsl-keyword-reasons/reasons-*.json).

    The lighter alternative to a five-question admission record for the keywords that predate the admission rule
    (REG-357): one honest sentence on what need the keyword serves and why no lower rung can do it."""
    reasons: dict[str, dict] = {}
    for path in sorted(REASONS.glob("reasons-*.json")):
        for slot, text in (json.loads(path.read_text(encoding="utf-8")).get("reasons") or {}).items():
            if slot in reasons:
                raise SystemExit(f"{path.name}: duplicate reason for {slot}")
            reasons[slot] = {"record": path.name, "why": text.strip()}
    return reasons


EVIDENCE = ROOT / "scripts" / "policy" / "dsl-keyword-evidence.json"


def load_evidence(nodes: set[str] | None = None) -> dict[str, dict]:
    """slot id -> {layer, files, why} for keywords whose acting code the token scan cannot see (REG-357).

    The scan above looks for the keyword's own name (or its getX/isX/hasX/.x() accessor). It is blind to code that reads a
    keyword through an accessor with another name (`document.subtotalsEnabled()` for `screen.subtotals`, `findPrompt(name)`
    for `externalAi.prompts`) and to a rule whose whole effect is that a model compiles or not (`flow.crossAggregateReason`
    waives a check). Each entry here is a reviewed claim, and it is verified on every run: the file must exist, hold the
    symbol, and the slot must be a keyword the schema has -- so a rename or a deletion makes the page fail, never go stale."""
    if not EVIDENCE.exists():
        return {}
    data = json.loads(EVIDENCE.read_text(encoding="utf-8"))
    out: dict[str, dict] = {}
    for slot, entry in (data.get("evidence") or {}).items():
        if nodes is not None and slot not in nodes:
            raise SystemExit(f"{EVIDENCE.name}: evidence for a keyword the schema does not have: {slot}")
        layer = entry.get("layer")
        if layer not in LAYERS:
            raise SystemExit(f"{EVIDENCE.name}: {slot}: layer must be one of {LAYERS}, got {layer!r}")
        if not entry.get("why", "").strip():
            raise SystemExit(f"{EVIDENCE.name}: {slot}: a reviewed claim needs a 'why'")
        files = []
        for item in entry.get("files") or []:
            path = ROOT / item["path"]
            if not path.is_file():
                raise SystemExit(f"{EVIDENCE.name}: {slot}: file not found: {item['path']}")
            if item["symbol"] not in path.read_text(encoding="utf-8", errors="ignore"):
                raise SystemExit(f"{EVIDENCE.name}: {slot}: symbol {item['symbol']!r} not in {item['path']}")
            files.append(item["path"])
        if not files:
            raise SystemExit(f"{EVIDENCE.name}: {slot}: at least one file with a symbol is required")
        out[slot] = {"layer": layer, "files": files, "why": entry["why"].strip()}
    return out


def build_rows() -> tuple[list[dict], dict]:
    model, pack = REF.load_schemas()
    meanings = REF.load_meanings()
    slots = REF.measure_slots(model, pack)
    root_slots = {f"root:{p}" for p in (model.get("properties") or {})}
    noun_of: dict[str, str] = {}
    for noun in meanings["layout"]["nouns"]:
        for def_name in noun.get("defs", []):
            noun_of[def_name] = noun["title"]
    usage, labels = official_usage(model, pack, slots, root_slots)
    main_tokens, test_tokens = scan_sources()
    admissions = load_admissions()
    stories = load_stories()
    reasons = load_reasons()
    legacy = meanings["slots"]
    legacy_ids = set(json.loads((REF.MEANINGS_DIR / "meanings-legacy.json").read_text(encoding="utf-8")).get("slots", {}))
    prop_count: dict[str, int] = {}
    for slot in slots | root_slots:
        prop_count[slot.split(".", 1)[-1].split(":", 1)[-1]] = prop_count.get(slot.split(".", 1)[-1].split(":", 1)[-1], 0) + 1

    nodes: dict[str, tuple[dict, str]] = {}
    for def_name, definition in model.get("$defs", {}).items():
        for prop, node in (definition.get("properties") or {}).items():
            nodes[f"{def_name}.{prop}"] = (node, noun_of.get(def_name, "Other"))
    for prop, node in (pack.get("properties") or {}).items():
        nodes[f"pack:{prop}"] = (node, "Pack file")
    for prop, node in (model.get("properties") or {}).items():
        nodes[f"root:{prop}"] = (node, "Model root")

    orphans = sorted(set(stories) - set(nodes))
    if orphans:
        raise SystemExit(f"stories for keywords the schema does not have: {orphans[:5]}")
    orphan_reasons = sorted(set(reasons) - set(nodes))
    if orphan_reasons:
        raise SystemExit(f"reasons for keywords the schema does not have: {orphan_reasons[:5]}")
    evidence = load_evidence(set(nodes))
    rows = []
    for slot in sorted(nodes):
        node, noun = nodes[slot]
        def_name, _, prop = slot.partition(".") if "." in slot else ("", "", slot.split(":", 1)[1])
        if slot.startswith(("pack:", "root:")):
            def_name, prop = slot.split(":", 1)[0], slot.split(":", 1)[1]
        meaning, source = REF.meaning_of(slot, node, meanings)
        everywhere = usage.models.get(slot, {})
        used = {k: v for k, v in everywhere.items() if k not in usage.other_models}
        elsewhere = len(everywhere) - len(used)
        by_layer = {layer: sorted(files) for layer, files in main_tokens.get(prop, {}).items()}
        if slot in evidence:
            claimed = evidence[slot]
            by_layer[claimed["layer"]] = sorted(set(by_layer.get(claimed["layer"], [])) | set(claimed["files"]))
        support = support_of(prop, by_layer)
        test_files = sorted(test_tokens.get(prop, set()))
        real_models = [k for k in used if k != CORPUS_MODEL]
        example = None
        all_candidates = usage.examples.get(slot, [])
        candidates = [c for c in all_candidates if c[0] not in usage.other_models] or all_candidates
        if candidates:
            pick = next((c for c in candidates if c[0] == CORPUS_MODEL), None) or min(candidates, key=lambda c: len(c[2]))
            example = {"from": pick[0], "at": pick[1], "json": pick[2]}
        else:
            ref_example = (meanings["defs"].get(def_name) or {}).get("example")
            if ref_example is not None and _has_key(ref_example, prop):
                example = {"from": "reference example", "at": def_name, "json": json.dumps(ref_example, indent=2, ensure_ascii=False)[:900]}
        just = list(admissions.get(slot, []))
        if slot in reasons:
            just.append({"record": reasons[slot]["record"], "keyword": slot, "why": {"reason": reasons[slot]["why"]}, "note": ""})
        shared = prop_count.get(prop, 1)
        row = {
            "id": slot,
            "def": def_name,
            "prop": prop,
            "noun": noun,
            "kind": _kind(node),
            "meaning": meaning,
            "meaningSource": source,
            "legacy": slot in legacy_ids,
            "shared": shared,
            "support": support,
            "layers": {layer: len(by_layer.get(layer, [])) for layer in LAYERS},
            "layerFiles": {layer: [short_path(f) for f in by_layer[layer][:3]] for layer in LAYERS if by_layer.get(layer)},
            "evidence": evidence[slot]["why"] if slot in evidence else "",
            "tests": len(test_files),
            "testFiles": test_files[:5],
            "samples": len(used),
            "real": len(real_models),
            "elsewhere": elsewhere,
            "sampleList": sorted(used)[:12],
            "uses": sum(used.values()),
            "corpus": CORPUS_MODEL in used,
            "example": example,
            "just": just,
            "story": stories.get(slot, ""),
        }
        gaps = []
        if support == "ignored":
            gaps.append("no platform code names it: the schema accepts it and the value is silently dropped")
        elif support == "carried":
            gaps.append("only plumbing names it (parser, syntax tree, compiled model): no code was found that acts on the value. Code that uses it under another name would not show here, so read the code before removing it")
        elif support == "checked":
            gaps.append("only a validator names it: no code was found that acts on the value. Code that uses it under another name would not show here, so read the code before removing it")
        elif support == "tooling":
            gaps.append("tooling only: only the CLI or AI tooling names it; no generator, kernel, runtime or UI code acts on it")
        if not row["samples"]:
            gaps.append("no official sample uses it")
        elif not row["real"]:
            gaps.append("only the coverage fixture (dsl-conformance-max) uses it; no real model does")
        if not row["tests"]:
            gaps.append("no test names it")
        if not row["example"]:
            gaps.append("no example")
        if not just:
            gaps.append("no written justification")
        if not row["story"]:
            gaps.append("no everyday story")
        row["gaps"] = gaps
        row["candidate"] = (not row["samples"]) and (not row["tests"])
        rows.append(row)
    summary = {
        "keywords": len(rows),
        "budgetSlots": len(slots),
        "rootKeys": len(root_slots),
        "officialSamples": labels,
    }
    return rows, summary


# --------------------------------------------------------------------------- page

PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NPDev capabilities catalogue</title>
<style>
:root{--bg:#fbfbf9;--surface:#fff;--ink:#1b1f24;--muted:#5b6672;--line:#d9dde2;--accent:#1f5fbf;--ok:#1f7a45;--warn:#a65b00;--bad:#b3261e;--chip:#eef2f7;--code:#f4f6f8}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#14181d;--surface:#1b2128;--ink:#e6e9ed;--muted:#9aa5b1;--line:#2c353f;--accent:#7fb0ff;--ok:#5fcf8e;--warn:#f0b35a;--bad:#ff8f87;--chip:#232b34;--code:#10151a}}
:root[data-theme="dark"]{--bg:#14181d;--surface:#1b2128;--ink:#e6e9ed;--muted:#9aa5b1;--line:#2c353f;--accent:#7fb0ff;--ok:#5fcf8e;--warn:#f0b35a;--bad:#ff8f87;--chip:#232b34;--code:#10151a}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1280px;margin:0 auto;padding:24px 16px 64px}
h1{font-size:1.7rem;margin:0 0 4px}h2{font-size:1.2rem;margin:32px 0 8px}
p,li{max-width:76ch}.lead{color:var(--muted);font-size:1.05rem;max-width:80ch}
code,pre{font-family:ui-monospace,Consolas,monospace;font-size:.88em}
code{background:var(--code);padding:1px 5px;border-radius:4px}
pre{background:var(--code);padding:12px;border-radius:8px;overflow:auto;margin:6px 0;border:1px solid var(--line)}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:16px 0}
.card{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:12px}
.card b{display:block;font-size:1.6rem}.card span{color:var(--muted);font-size:.85rem}
details.how{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:8px 16px;margin:12px 0}
details.how summary{cursor:pointer;font-weight:600}
dl{display:grid;grid-template-columns:max-content 1fr;gap:6px 16px;margin:8px 0}dt{font-weight:600}dd{margin:0;color:var(--muted)}
.bar{position:sticky;top:0;background:var(--bg);padding:10px 0;z-index:5;border-bottom:1px solid var(--line);display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.bar input[type=search],.bar select{font:inherit;padding:6px 10px;border:1px solid var(--line);border-radius:8px;background:var(--surface);color:var(--ink)}
.bar input[type=search]{min-width:240px;flex:1}
.chip{font:inherit;font-size:.85rem;padding:4px 10px;border:1px solid var(--line);border-radius:999px;background:var(--chip);color:var(--ink);cursor:pointer}
.chip[aria-pressed=true]{background:var(--accent);color:#fff;border-color:var(--accent)}
.count{color:var(--muted);font-size:.9rem;margin-left:auto}
.wrap{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:var(--surface);margin-top:10px}
table{border-collapse:collapse;width:100%;min-width:1080px}
th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top;font-size:.92rem}
th{position:sticky;top:0;background:var(--surface);cursor:pointer;white-space:nowrap;user-select:none}
th[aria-sort=ascending]::after{content:" \25B2"}th[aria-sort=descending]::after{content:" \25BC"}
tr.row{cursor:pointer}tr.row:hover{background:var(--chip)}
td.id{font-family:ui-monospace,Consolas,monospace;font-size:.86rem;word-break:break-all;min-width:170px}
.y{color:var(--ok);font-weight:600}.n{color:var(--bad);font-weight:600}.w{color:var(--warn);font-weight:600}
.badge{display:inline-block;font-size:.75rem;padding:1px 7px;border-radius:999px;background:var(--chip);color:var(--muted);margin-left:4px}
tr.detail td{background:var(--bg)}
.detail .grid>div{overflow-wrap:anywhere;min-width:0}
.detail h3{margin:12px 0 4px;font-size:.95rem}.detail .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:4px 24px}
.story{background:var(--chip);border-left:4px solid var(--accent);border-radius:6px;padding:4px 14px;margin:8px 0 4px}.story p{margin:4px 0 8px;font-size:1.02rem}
.gaps{margin:4px 0;padding-left:20px}.gaps li{color:var(--warn)}
.legend span{margin-right:14px;white-space:nowrap}
footer{margin-top:32px;color:var(--muted);font-size:.85rem}
@media (max-width:600px){.count{margin-left:0;width:100%}}
</style>
</head>
<body>
<main>
<h1>NPDev capabilities catalogue</h1>
<p class="lead">Everything the model language can say, one row per keyword. A <em>keyword</em> is a word you may write in a model file, such as <code>maxLength</code> on a field or <code>runAs</code> on a flow. This page shows, for each one, what it does, whether the platform really implements it, whether the official samples use it, whether a test covers it, a real example, and why it exists.</p>

<div class="cards" id="cards"></div>

<details class="how" open>
<summary>How to read this page</summary>
<p>Each row is one keyword. Click a row to open it: you get the plain explanation, the type of value it takes, a real example copied from an official sample, the tests and code that mention it, and the written reason it was admitted to the language.</p>
<dl>
<dt>Implemented</dt><dd>What the platform really does with the word, judged by which parts of the code name it. The model passes through a pipeline: the parser reads the JSON, plumbing classes (the syntax tree, the compiled model, the resolver, the compiled-model writers) copy a value along without looking at it, validators check it, other build-time classes act on it while the model is built, and the generator, kernel, runtime host and UI act on it when the app runs. Code is found by name, through quoted keys and through accessor calls such as <code>flow.expose()</code>.
<br><span class="y">runs</span> the generator, kernel, runtime or UI acts on it. <span class="y">builds</span> it acts while the model is parsed, resolved or compiled. <span class="w">tooling</span> only the command line or AI tooling names it. <span class="w">validated only</span> and <span class="w">plumbing only</span> only a validator, or only the parser and the compiled-model plumbing, name it: no code was found that acts on the value (code using it under another name would not show here, so confirm before removing). <span class="n">ignored</span> no code names it at all: the schema accepts it and the value is dropped. <span class="badge">file key</span> a structural key such as <code>$ref</code>.</dd>
<dt>Official samples</dt><dd>How many official models use it. Official means the models that exist to show or pin the language (roles <em>showcase</em>, <em>canary</em> and <em>dsl-fixture</em> in the corpus list) and the built-in packs. Each model is walked against the schema, so a word that appears in many places is credited only where it truly belongs. Use in repro cases and engine variants is shown in the detail, but does not count.</dd>
<dt>Real models</dt><dd>How many of those official models are real apps and packs, leaving out <code>dsl-conformance-max</code>. That model is the coverage fixture: it exists to touch every word once, so it proves a word can be written, not that anyone needs it. <span class="n">0</span> means only the fixture uses the word.</dd>
<dt>Tested</dt><dd>How many test files mention it. This is by name; the badge <span class="badge">shared</span> means other keywords have the same name, so the evidence cannot say which one it proves.</dd>
<dt>Corpus</dt><dd>It is used in <code>dsl-conformance-max</code>, the model the build gate generates every time.</dd>
<dt>Example</dt><dd>A real JSON example exists: from an official sample when one uses the word, otherwise from the reference example of its definition.</dd>
<dt>Story</dt><dd>A short story in everyday words: a real situation, the problem it causes, and what the keyword does about it. It is written so that someone who has never programmed can hold on to the idea.</dd>
<dt>Justified</dt><dd>A written admission record explains why the language needs this word (declarative, not expressible lower, used twice, enforced, taught). Older keywords predate that rule, so most show <span class="n">none yet</span>: that is the work list.</dd>
<dt>Status</dt><dd><span class="y">complete</span> means all six pieces exist (code, sample, test, example, story, written reason). Otherwise it lists what is missing. <span class="w">unused</span> marks a candidate for removal: no official sample and no test.</dd>
</dl>
<p>The language is meant to stay small. When a need is rare, the right answer is usually code the author writes (a function, a flow step, a JavaScript page or a Java class) and not one more keyword. Use the filters to find keywords that no sample, test or reason supports.</p>
</details>

<div class="bar" role="search">
<input type="search" id="q" placeholder="Search keyword, meaning, noun..." aria-label="Search">
<select id="noun" aria-label="Filter by noun"><option value="">All nouns</option></select>
<button class="chip" data-f="candidate" aria-pressed="false">Unused (candidates)</button>
<button class="chip" data-f="nocode" aria-pressed="false">No acting code found</button>
<button class="chip" data-f="nosample" aria-pressed="false">No sample</button>
<button class="chip" data-f="noreal" aria-pressed="false">No real model</button>
<button class="chip" data-f="notest" aria-pressed="false">No test</button>
<button class="chip" data-f="noexample" aria-pressed="false">No example</button>
<button class="chip" data-f="nostory" aria-pressed="false">No story</button>
<button class="chip" data-f="nojust" aria-pressed="false">Not justified</button>
<button class="chip" data-f="complete" aria-pressed="false">Complete only</button>
<button class="chip" data-f="legacy" aria-pressed="false">Legacy</button>
<span class="count" id="count" role="status"></span>
</div>

<div class="wrap"><table id="t">
<thead><tr>
<th data-k="id" scope="col">Keyword</th><th data-k="noun" scope="col">Noun</th><th data-k="kind" scope="col">Takes</th>
<th data-k="meaning" scope="col">What it does</th><th data-k="support" scope="col">Implemented</th><th data-k="samples" scope="col">Official samples</th><th data-k="real" scope="col">Real models</th>
<th data-k="tests" scope="col">Tested</th><th data-k="corpus" scope="col">Corpus</th><th data-k="hasExample" scope="col">Example</th>
<th data-k="hasStory" scope="col">Story</th><th data-k="hasJust" scope="col">Justified</th><th data-k="status" scope="col">Status</th>
</tr></thead><tbody id="rows"></tbody></table></div>
<footer>Generated by <code>scripts/docs/generate_dsl_capabilities.py</code> from the schemas, the keyword meanings, the admission records, the official samples and the platform's sources. Do not edit by hand. Source and test evidence is by name, so treat <span class="badge">shared</span> rows with care: a common word such as <code>label</code> is named by a great deal of unrelated code.</footer>
</main>
<script type="application/json" id="data">__DATA__</script>
<script>
(function(){
var D=JSON.parse(document.getElementById('data').textContent),R=D.rows,S=D.summary;
R.forEach(function(r){r.hasExample=!!r.example;r.hasStory=!!r.story;r.hasJust=r.just.length>0;r.status=r.gaps.length?(r.candidate?'unused':r.gaps.length+' gap'+(r.gaps.length>1?'s':'')):'complete'});
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e}
var cards=document.getElementById('cards');
function card(n,l){var c=el('div','card');c.appendChild(el('b',null,n));c.appendChild(el('span',null,l));cards.appendChild(c)}
card(S.keywords,'keywords listed');card(S.budgetSlots,'budget slots (limit only goes down)');
card(R.filter(function(r){return r.status==='complete'}).length,'complete');
card(R.filter(function(r){return r.candidate}).length,'unused: no sample, no test');
card(R.filter(function(r){return r.support==='carried'||r.support==='checked'||r.support==='ignored'}).length,'no acting code found');card(R.filter(function(r){return r.samples>0&&!r.real}).length,'only the coverage fixture uses it');
card(R.filter(function(r){return !!r.story}).length,'with an everyday story');card(R.filter(function(r){return !r.hasJust}).length,'without a written reason');
card(S.officialSamples.length,'official samples scanned');
var nouns=[];R.forEach(function(r){if(nouns.indexOf(r.noun)<0)nouns.push(r.noun)});nouns.sort();
var ns=document.getElementById('noun');nouns.forEach(function(n){var o=el('option',null,n);o.value=n;ns.appendChild(o)});
var active={},sortK='id',sortDir=1,open={};
var preds={candidate:function(r){return r.candidate},nocode:function(r){return r.support==='carried'||r.support==='checked'||r.support==='ignored'},noreal:function(r){return !r.real},nosample:function(r){return !r.samples},notest:function(r){return !r.tests},noexample:function(r){return !r.hasExample},nojust:function(r){return !r.hasJust},nostory:function(r){return !r.story},complete:function(r){return r.status==='complete'},legacy:function(r){return r.legacy}};
function yn(ok,txt,badIfNo){var s=el('span',ok?'y':(badIfNo?'n':'w'),ok?(txt||'yes'):'no');return s}
function tail(r,parent){
 var tr=el('tr','detail'),td=el('td');td.colSpan=13;
 var g=el('div','grid');
 if(r.story){var st=el('div','story');st.appendChild(el('h3',null,'In everyday words'));st.appendChild(el('p',null,r.story));td.appendChild(st)}
 var a=el('div');a.appendChild(el('h3',null,'What it does'));a.appendChild(el('div',null,r.meaning||'(no meaning written yet)'));
 a.appendChild(el('h3',null,'Takes'));a.appendChild(el('div',null,r.kind||'-'));
 if(r.legacy){a.appendChild(el('h3',null,'Status'));a.appendChild(el('div','w','Legacy / retiring keyword'))}
 g.appendChild(a);
 var b=el('div');b.appendChild(el('h3',null,'Real example'+(r.example?' ('+r.example.from+(r.example.at?' at '+r.example.at:'')+')':'')));
 if(r.example){var p=el('pre');p.textContent=r.example.json;b.appendChild(p)}else b.appendChild(el('div','w','No example exists yet: add one to dsl-conformance-max or remove the keyword.'));
 g.appendChild(b);
 var c=el('div');c.appendChild(el('h3',null,'Evidence'));
 c.appendChild(el('div',null,'Official samples: '+(r.samples?r.sampleList.join(', ')+(r.samples>r.sampleList.length?' ...':'')+' ('+r.uses+' uses)':'none')));
 c.appendChild(el('div',null,'Real models: '+(r.real?r.real+' (leaving out the coverage fixture)':'none: only the coverage fixture uses it')));
 [['parser','Parser reads it'],['carried','Copied along by'],['checked','Checked by'],['build','Acts while the model is built, in'],['runtime','Acts when the app runs, in'],['tooling','Tooling names it in']].forEach(function(p){var n=r.layers[p[0]],f=r.layerFiles[p[0]]||[];c.appendChild(el('div',null,p[1]+': '+(n?f.join(', ')+(n>f.length?' (+'+(n-f.length)+' more)':''):'no file')))});
 if(r.evidence)c.appendChild(el('div',null,'Reviewed evidence (verified on every build of this page): '+r.evidence));
 c.appendChild(el('div',null,'Tests: '+(r.tests?r.testFiles.join(', ')+(r.tests>r.testFiles.length?' (+'+(r.tests-r.testFiles.length)+' more)':''):'none found')));
 if(r.shared>1)c.appendChild(el('div','w','The name "'+r.prop+'" is shared by '+r.shared+' keywords, so code and test evidence is only indicative.'));
 g.appendChild(c);
 var d=el('div');d.appendChild(el('h3',null,'Why it exists'));
 if(r.just.length){r.just.forEach(function(j){d.appendChild(el('div',null,j.record.indexOf('reasons-')===0?'Written reason':'Admission record '+j.record+' ('+j.keyword+')'));Object.keys(j.why).forEach(function(q){var x=el('div');x.appendChild(el('b',null,q+': '));x.appendChild(document.createTextNode(j.why[q]));d.appendChild(x)})})}
 else d.appendChild(el('div','w','No written justification yet.'));
 g.appendChild(d);td.appendChild(g);
 if(r.gaps.length){var u=el('ul','gaps');r.gaps.forEach(function(x){u.appendChild(el('li',null,x))});td.appendChild(el('h3',null,'Missing'));td.appendChild(u)}
 tr.appendChild(td);return tr}
function render(){
 var q=document.getElementById('q').value.toLowerCase(),nn=ns.value;
 var rows=R.filter(function(r){
  if(nn&&r.noun!==nn)return false;
  for(var k in active)if(active[k]&&!preds[k](r))return false;
  return !q||(r.id+' '+r.meaning+' '+r.noun+' '+r.kind).toLowerCase().indexOf(q)>=0});
 rows.sort(function(a,b){var x=a[sortK],y=b[sortK];if(typeof x==='string')return x.localeCompare(y)*sortDir;return ((x>y)-(x<y))*sortDir});
 var tb=document.getElementById('rows');tb.textContent='';
 rows.forEach(function(r){
  var tr=el('tr','row');tr.tabIndex=0;
  var td=el('td','id',r.id);if(r.legacy)td.appendChild(el('span','badge','legacy'));if(r.shared>1)td.appendChild(el('span','badge','shared'));tr.appendChild(td);
  tr.appendChild(el('td',null,r.noun));tr.appendChild(el('td',null,r.kind));tr.appendChild(el('td',null,r.meaning));
  var c=el('td');var sl={runs:['y','runs'],builds:['y','builds'],tooling:['w','tooling'],checked:['w','validated only'],carried:['w','plumbing only'],ignored:['n','ignored'],editor:['badge','file key']}[r.support];c.appendChild(el('span',sl[0],sl[1]));tr.appendChild(c);
  c=el('td');c.appendChild(yn(r.samples>0,r.samples+(r.samples===1?' sample':' samples'),false));tr.appendChild(c);
  c=el('td');c.appendChild(r.samples>0?(r.real>0?yn(true,String(r.real)):el('span','n','0')):el('span','n','-'));tr.appendChild(c);
  c=el('td');c.appendChild(yn(r.tests>0,r.tests+(r.tests===1?' file':' files'),false));tr.appendChild(c);
  c=el('td');c.appendChild(yn(r.corpus,'yes',false));tr.appendChild(c);
  c=el('td');c.appendChild(yn(r.hasExample,'yes',false));tr.appendChild(c);
  c=el('td');c.appendChild(r.hasStory?yn(true,'yes'):el('span','n','none yet'));tr.appendChild(c);
  c=el('td');c.appendChild(r.hasJust?yn(true,'yes'):el('span','n','none yet'));tr.appendChild(c);
  c=el('td');c.appendChild(r.status==='complete'?el('span','y','complete'):el('span',r.candidate?'n':'w',r.status));tr.appendChild(c);
  var toggle=function(){if(open[r.id])delete open[r.id];else open[r.id]=1;render()};
  tr.addEventListener('click',toggle);tr.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();toggle()}});
  tb.appendChild(tr);if(open[r.id])tb.appendChild(tail(r))});
 document.getElementById('count').textContent=rows.length+' of '+R.length+' keywords';
 var ths=document.querySelectorAll('th');for(var i=0;i<ths.length;i++)ths[i].setAttribute('aria-sort',ths[i].getAttribute('data-k')===sortK?(sortDir>0?'ascending':'descending'):'none')}
document.querySelectorAll('.chip').forEach(function(b){b.addEventListener('click',function(){var f=b.getAttribute('data-f');active[f]=!active[f];b.setAttribute('aria-pressed',active[f]?'true':'false');render()})});
document.querySelectorAll('th').forEach(function(h){h.addEventListener('click',function(){var k=h.getAttribute('data-k');if(k===sortK)sortDir=-sortDir;else{sortK=k;sortDir=1}render()})});
document.getElementById('q').addEventListener('input',render);ns.addEventListener('change',render);
render();
})();
</script>
</body>
</html>
"""


def render() -> str:
    rows, summary = build_rows()
    data = json.dumps({"rows": rows, "summary": summary}, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    return PAGE.replace("__DATA__", data)


def main(argv: list[str]) -> int:
    if "--check" in argv:
        if not OUT.exists():
            print("docs/DSL_CAPABILITIES.html is missing: run python scripts/docs/generate_dsl_capabilities.py")
            return 1
        model, pack = REF.load_schemas()
        expected = REF.measure_slots(model, pack) | {f"root:{p}" for p in (model.get("properties") or {})}
        load_evidence(expected)  # a reviewed claim whose file or symbol vanished fails the check, never goes stale
        text = OUT.read_text(encoding="utf-8")
        found = set(re.findall(r'"id":"([^"]+)"', text))
        if found != expected:
            missing = sorted(expected - found)[:5]
            extra = sorted(found - expected)[:5]
            print(f"DSL capabilities page is stale against the schema: missing {missing}, extra {extra}. "
                  "Run python scripts/docs/generate_dsl_capabilities.py")
            return 1
        print(f"DSL capabilities page: OK ({len(found)} keywords match the schema)")
        return 0
    html = render()
    OUT.write_text(html, encoding="utf-8", newline="\n")
    print(f"wrote {OUT.relative_to(ROOT).as_posix()} ({len(html) // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
