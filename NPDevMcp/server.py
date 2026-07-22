#!/usr/bin/env python3
"""NPDev MCP server -- lets an external AI *drive* NPDev instead of guessing at its JSON.

A zero-dependency Model Context Protocol server (JSON-RPC 2.0 over stdio, newline-delimited)
that exposes the NPDev authoring pipeline as typed tools. It wraps the portable CLI
(NPDevCli/npdev_cli.py) and the canonical schemas, so an authoring agent can:

  - npdev_validate       : full structural + semantic validation -> typed diagnostic report
                           (the self-correction loop -- catches cross-reference errors at author
                           time, not build time)
  - npdev_inspect_app    : what concepts/flows/events already exist (avoid duplicating)
  - npdev_inspect_bonds  : bond/anchor/onDelete analysis + migration risks
  - npdev_get_schema     : exact authoring grammar for an object type
  - npdev_list_schemas   : discover available schemas
  - npdev_search_examples: retrieve real, working example snippets (RAG; reads the Phase 3 index)
  - npdev_search_fix      : given a validator diagnostic, retrieve precedent fixes by failure signature
  - npdev_check_support   : is a feature a known gap/constraint/lifted boundary? (queries the ledger projection)
  - npdev_migration_diff : classify a schema change as safe-additive vs destructive
  - npdev_generate       : run the real generator (slow/mutating -- gate in your client)

Zero third-party deps on purpose: it runs under any Python 3.9+ with no install step, and is
deterministically testable by piping JSON-RPC frames to stdin.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

SERVER_NAME = "npdev-mcp"
SERVER_VERSION = "0.2.0"
DEFAULT_PROTOCOL_VERSION = "2024-11-05"

# Share ONE signature normalizer with the offline builder (scripts/ai/failure_signatures.py) so
# an authored card signature and a live diagnostic normalize identically -- otherwise lookups miss.
_SCRIPTS_AI = Path(__file__).resolve().parents[1] / "scripts" / "ai"
if str(_SCRIPTS_AI) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_AI))
try:
    from failure_signatures import normalize as normalize_signature
except Exception:  # pragma: no cover - only if scripts/ai is absent
    normalize_signature = None


def repo_root() -> Path:
    env_root = os.environ.get("NPDEV_ROOT")
    if env_root:
        return Path(env_root).expanduser().resolve()
    # NPDevMcp/ sits directly under the repo root.
    return Path(__file__).resolve().parents[1]


def cli_path() -> Path:
    return repo_root() / "NPDevCli" / "npdev_cli.py"


def run_cli(args: list[str], timeout: int = 600) -> dict[str, Any]:
    """Invoke the portable CLI and capture its result. Returns a structured envelope."""
    cmd = [sys.executable, str(cli_path()), *args]
    try:
        completed = subprocess.run(
            cmd,
            cwd=str(repo_root()),
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": f"cli timed out after {timeout}s", "args": args}
    return {
        "ok": completed.returncode in (0, 2),  # 2 == validation reported errors (a valid result)
        "exitCode": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }


def schema_dir() -> Path:
    return repo_root() / "schemas" / "ai"


def build_root() -> Path:
    """External build root (NPDEV_BUILD_ROOT, else <repo-parent>/Build) -- where generated AI
    artifacts (the RAG index, derived schemas, core-context bundle) are written."""
    env_root = os.environ.get("NPDEV_BUILD_ROOT")
    if env_root and env_root.strip():
        return Path(env_root).expanduser().resolve()
    root = repo_root()
    cursor = root
    while cursor is not None and cursor.name != "NPDev_General":
        cursor = cursor.parent if cursor.parent != cursor else None
    if cursor is not None and cursor.parent is not None:
        return cursor.parent / "Build"
    return root.parent / "Build"


# ---------------------------------------------------------------------------
# Tool implementations
# ---------------------------------------------------------------------------

def tool_validate(arguments: dict[str, Any]) -> dict[str, Any]:
    model = arguments.get("model_path")
    if not model:
        return _text_error("model_path is required")
    result = run_cli(["validate", "model", model, "--semantic"])
    stdout = (result.get("stdout") or "").strip()
    if stdout:
        # stdout is the typed npdev-validation-report.v2 JSON -- pass it through verbatim.
        return _text(stdout)
    return _text_error(result.get("stderr") or result.get("error") or "validation produced no report")


def tool_inspect_app(arguments: dict[str, Any]) -> dict[str, Any]:
    model = arguments.get("model_path")
    if not model:
        return _text_error("model_path is required")
    result = run_cli(["inspect", "app", "--model", model])
    return _passthrough(result)


def tool_inspect_bonds(arguments: dict[str, Any]) -> dict[str, Any]:
    model = arguments.get("model_path")
    if not model:
        return _text_error("model_path is required")
    result = run_cli(["inspect", "bonds", "--model", model])
    return _passthrough(result)


# Guessable aliases for schemas whose real name isn't obvious from context. Found via a blind
# MCP-only authoring run (2026-07-03): an agent given no filesystem access to NPDev needed
# "the config npdev_generate wants" and "the db.definition.json shape", and neither
# "config"/"generator-config" nor "db-definition" resolved -- it had to browse the full schema
# list and pattern-match ai-generator-config / user-db-definition by guessing at field shapes.
SCHEMA_ALIASES = {
    "config": "ai-generator-config",
    "generator-config": "ai-generator-config",
    "db-definition": "user-db-definition",
    "db.definition": "user-db-definition",
}


def tool_list_schemas(_arguments: dict[str, Any]) -> dict[str, Any]:
    directory = schema_dir()
    names = sorted(
        p.name[: -len(".schema.json")]
        for p in directory.glob("*.schema.json")
    )
    return _text(json.dumps(
        {
            "schemas": names,
            "directory": str(directory),
            "specialCaseNames": {
                "model": "the full canonical model.json schema (lives outside this directory)",
            },
            "aliases": SCHEMA_ALIASES,
        },
        indent=2,
    ))


def tool_get_schema(arguments: dict[str, Any]) -> dict[str, Any]:
    name = arguments.get("schema_name")
    if not name:
        return _text_error("schema_name is required (use npdev_list_schemas to discover names)")
    # Special-case the full canonical model schema, which lives outside schemas/ai.
    if name in ("model", "model.schema", "full-model"):
        candidate = repo_root() / "NPDevContract" / "schemas" / "model.schema.json"
    else:
        safe = name.replace("\\", "/").split("/")[-1]
        if safe.endswith(".schema.json"):
            safe = safe[: -len(".schema.json")]
        safe = SCHEMA_ALIASES.get(safe, safe)
        candidate = schema_dir() / f"{safe}.schema.json"
    if not candidate.exists():
        return _text_error(f"schema not found: {name} (looked at {candidate})")
    return _text(candidate.read_text(encoding="utf-8"))


def tool_search_examples(arguments: dict[str, Any]) -> dict[str, Any]:
    query = (arguments.get("query") or "").strip()
    if not query:
        return _text_error("query is required")
    limit = int(arguments.get("limit") or 5)
    index_path = build_root() / "npdev-ai" / "rag-index.json"
    if not index_path.exists():
        return _text_error(
            "example index not built yet -- run: python scripts/ai/build_rag_index.py "
            f"(expected at {index_path})"
        )
    index = json.loads(index_path.read_text(encoding="utf-8"))
    matches = _rank_chunks(index.get("chunks", []), query, limit)
    return _text(json.dumps({"query": query, "matches": matches}, indent=2))


def tool_search_fix(arguments: dict[str, Any]) -> dict[str, Any]:
    """Retrieve precedent fixes for a validator diagnostic, keyed by normalized failure signature."""
    message = (arguments.get("message") or "").strip()
    if not message:
        return _text_error("message is required (paste the validator diagnostic text)")
    if normalize_signature is None:
        return _text_error("failure_signatures normalizer unavailable (scripts/ai not found)")
    limit = int(arguments.get("limit") or 5)
    index_path = build_root() / "npdev-ai" / "failure-index.json"
    if not index_path.exists():
        return _text_error(
            "failure index not built yet -- run: python scripts/ai/build_knowledge.py "
            f"(expected at {index_path})"
        )
    index = json.loads(index_path.read_text(encoding="utf-8"))
    signatures = index.get("signatures", [])
    query_sig = normalize_signature(message, arguments.get("path"),
                                    arguments.get("concept"), arguments.get("field"))

    exact = [s for s in signatures if s.get("signature") == query_sig]
    if exact:
        matches = [{"match": "signature", "signature": s["signature"], "examples": s["examples"]}
                   for s in exact]
        return _text(json.dumps({"query": message, "normalized": query_sig, "matches": matches[:limit]},
                                indent=2))

    # Keyword fallback: rank signatures by term overlap across signature + example message/fix text.
    terms = [t for t in _tokenize(message) if t]
    scored = []
    for sig in signatures:
        haystack = (sig.get("signature", "") + " " + " ".join(
            f"{ex.get('message','')} {ex.get('fix','')}" for ex in sig.get("examples", [])
        )).lower()
        score = sum(haystack.count(term) for term in terms)
        if score > 0:
            scored.append((score, sig))
    scored.sort(key=lambda pair: pair[0], reverse=True)
    matches = [{"match": "keyword", "score": score, "signature": sig["signature"],
                "examples": sig["examples"]} for score, sig in scored[:limit]]
    return _text(json.dumps({"query": message, "normalized": query_sig, "matches": matches}, indent=2))


def tool_check_support(arguments: dict[str, Any]) -> dict[str, Any]:
    """Cheap pre-check: is a feature a KNOWN gap/constraint/lifted item? Never fabricates a positive."""
    feature = (arguments.get("feature") or "").strip()
    if not feature:
        return _text_error("feature is required (a ledger id like 'ARCH-10b' or free text like 'panel orderBy')")
    caps_path = build_root() / "npdev-ai" / "capabilities.json"
    if not caps_path.exists():
        return _text_error(
            "capabilities projection not built yet -- run: python scripts/ai/build_knowledge.py "
            f"(expected at {caps_path})"
        )
    caps = json.loads(caps_path.read_text(encoding="utf-8"))
    items = caps.get("items", [])
    cards = caps.get("cards", [])

    # 1. Exact ledger-id hit.
    for item in items:
        if str(item.get("id", "")).lower() == feature.lower():
            return _text(json.dumps({"resolvedBy": "ledger-id", "result": item,
                                     "disclaimer": _CAPS_DISCLAIMER}, indent=2))

    # 2. Keyword match over ledger items + constraint/gap cards.
    terms = [t for t in _tokenize(feature) if t]
    item_hits = _score_records(items, terms, ("id", "title", "notes", "category"))
    card_hits = _score_records(cards, terms, ("id", "title", "body", "keywords", "appliesTo"))
    if item_hits or card_hits:
        return _text(json.dumps({
            "resolvedBy": "keyword",
            "ledgerItems": item_hits[:5],
            "knowledgeCards": card_hits[:5],
            "disclaimer": _CAPS_DISCLAIMER,
        }, indent=2))

    # 3. Nothing tracked -- explicitly UNKNOWN, never "supported".
    return _text(json.dumps({
        "resolvedBy": "none",
        "result": "unknown",
        "message": (
            f"No tracked gap, constraint, or lifted boundary matches '{feature}'. This is NOT a "
            "guarantee of support -- absence of a known gap only means nothing is recorded. To check "
            "whether the shape is AUTHORABLE, fetch the object-type grammar via npdev_get_schema."
        ),
        "disclaimer": _CAPS_DISCLAIMER,
    }, indent=2))


_CAPS_DISCLAIMER = (
    "Status reflects the gaps ledger as of the last knowledge build. DONE/LIFTED = fixed or now "
    "supported; OPEN/PARTIAL = still gapped; BOUNDARY = accepted constraint."
)


def _score_records(records: list[dict[str, Any]], terms: list[str],
                   keys: tuple[str, ...]) -> list[dict[str, Any]]:
    # Coverage-first: a record matching MORE distinct query terms ranks above one that merely repeats
    # a single term many times (so 'panel orderBy' surfaces the orderBy item, not a panel-heavy one),
    # with total frequency as the tie-breaker.
    scored = []
    for rec in records:
        haystack = " ".join(
            " ".join(map(str, v)) if isinstance(v := rec.get(k, ""), list) else str(v)
            for k in keys
        ).lower()
        counts = [haystack.count(term) for term in terms]
        coverage = sum(1 for c in counts if c > 0)
        if coverage > 0:
            scored.append(((coverage, sum(counts)), rec))
    scored.sort(key=lambda pair: pair[0], reverse=True)
    return [rec for _, rec in scored]


def tool_migration_diff(arguments: dict[str, Any]) -> dict[str, Any]:
    baseline = arguments.get("baseline")
    current = arguments.get("current")
    if not baseline or not current:
        return _text_error("baseline and current are required")
    args = ["migration", "diff", "--baseline", baseline, "--current", current]
    threshold = arguments.get("risk_threshold")
    if threshold:
        args += ["--migrationRiskThreshold", threshold]
    result = run_cli(args)
    return _passthrough(result)


def tool_generate(arguments: dict[str, Any]) -> dict[str, Any]:
    model = arguments.get("model")
    config = arguments.get("config")
    output = arguments.get("output")
    if not (model and config and output):
        return _text_error("model, config and output are required")
    result = run_cli(["generate", "app", "--model", model, "--config", config, "--output", output])
    return _passthrough(result)


# A directly-relevant maintainer finding should outrank an incidental doc keyword hit, so
# knowledge-card chunks (idea 1) get a fixed relevance boost over doc/sample chunks.
KNOWLEDGE_CARD_BOOST = 1.5


def _rank_chunks(chunks: list[dict[str, Any]], query: str, limit: int) -> list[dict[str, Any]]:
    terms = [t for t in _tokenize(query) if t]
    scored = []
    for chunk in chunks:
        haystack = " ".join(
            str(chunk.get(k, "")) for k in ("title", "text", "keywords", "objectType", "source")
        ).lower()
        score = sum(haystack.count(term) for term in terms)
        if score > 0:
            if chunk.get("objectType") == "knowledge-card":
                score *= KNOWLEDGE_CARD_BOOST
            scored.append((score, chunk))
    scored.sort(key=lambda pair: pair[0], reverse=True)
    return [
        {
            "score": score,
            "title": chunk.get("title"),
            "objectType": chunk.get("objectType"),
            "source": chunk.get("source"),
            "text": chunk.get("text"),
        }
        for score, chunk in scored[:limit]
    ]


def _tokenize(text: str) -> list[str]:
    return [
        token
        for token in "".join(c.lower() if c.isalnum() else " " for c in text).split()
        if len(token) > 1
    ]


# ---------------------------------------------------------------------------
# Tool registry + MCP result helpers
# ---------------------------------------------------------------------------

TOOLS: list[dict[str, Any]] = [
    {
        "name": "npdev_validate",
        "description": (
            "Validate a draft model.json with the FULL structural + semantic pipeline (the same "
            "checks the generator runs) WITHOUT generating. Returns a typed npdev-validation-report.v2 "
            "JSON with per-diagnostic path/concept/field/suggestedFix. This is the authoring "
            "self-correction loop: write model.json -> validate -> fix -> re-validate, before any build."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "model_path": {"type": "string", "description": "Absolute or repo-relative path to model.json."}
            },
            "required": ["model_path"],
        },
    },
    {
        "name": "npdev_inspect_app",
        "description": (
            "Read-only summary of an existing app model: concepts (with fields), flows, events, "
            "panels, procedures, capabilities, queries, and counts. Use before adding to a model so "
            "you extend existing concepts instead of duplicating them."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"model_path": {"type": "string"}},
            "required": ["model_path"],
        },
    },
    {
        "name": "npdev_inspect_bonds",
        "description": (
            "Analyze all references/bonds in a model: source/target, via anchor, cardinality, "
            "onDelete, truth-edge direction, and migration risks (missing anchor, dangling-FK precheck)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"model_path": {"type": "string"}},
            "required": ["model_path"],
        },
    },
    {
        "name": "npdev_list_schemas",
        "description": "List the available NPDev authoring schema names (grammar for each object type).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "npdev_get_schema",
        "description": (
            "Return the exact JSON Schema for an object type (e.g. 'ai-model', 'custom-panel', "
            "'custom-procedure', or 'model' for the full canonical model schema). Use to ground "
            "field names instead of guessing."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"schema_name": {"type": "string"}},
            "required": ["schema_name"],
        },
    },
    {
        "name": "npdev_search_examples",
        "description": (
            "Retrieve real, working NPDev example snippets (from docs + verified sample apps) "
            "relevant to a query, e.g. 'cascade delete bond' or 'flow that waits for an event'. "
            "Grounds authoring in idiomatic precedent."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20},
            },
            "required": ["query"],
        },
    },
    {
        "name": "npdev_search_fix",
        "description": (
            "Given a validator diagnostic you just received, retrieve PRECEDENT fixes -- how this "
            "same class of error was resolved before -- keyed by a normalized failure signature "
            "(identifiers templated out, so 'concept Foo references unknown Bar' matches 'concept X "
            "references unknown Y'). Use this the moment npdev_validate reports an error, before "
            "guessing a correction. Falls back to keyword ranking when no exact signature matches."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "The diagnostic message text to resolve."},
                "path": {"type": "string", "description": "Optional JSON pointer from the diagnostic."},
                "concept": {"type": "string", "description": "Optional concept name to template out."},
                "field": {"type": "string", "description": "Optional field name to template out."},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20},
            },
            "required": ["message"],
        },
    },
    {
        "name": "npdev_check_support",
        "description": (
            "Cheap pre-check BEFORE committing to a model shape: is a feature a KNOWN gap, "
            "constraint, or already-lifted boundary? Accepts a ledger id ('ARCH-10b') or free text "
            "('panel orderBy', 'file upload'). Returns the tracked status (DONE/LIFTED/OPEN/PARTIAL/"
            "BOUNDARY) with notes, or an explicit 'unknown' -- it never fabricates a 'supported'. "
            "For whether a shape is AUTHORABLE at all, use npdev_get_schema instead."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "feature": {"type": "string", "description": "A ledger id or a free-text feature phrase."},
            },
            "required": ["feature"],
        },
    },
    {
        "name": "npdev_migration_diff",
        "description": (
            "Classify a schema change (baseline storage snapshot -> current model) as safe-additive "
            "vs backfill-required vs manual-review, and produce a dry-run migration plan. Use before "
            "changing an existing app's concepts."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "baseline": {"type": "string", "description": "Path to the baseline storage-schema snapshot."},
                "current": {"type": "string", "description": "Path to the current model.json."},
                "risk_threshold": {
                    "type": "string",
                    "enum": ["SAFE_ADDITIVE", "BACKFILL_REQUIRED", "MANUAL_REVIEW"],
                },
            },
            "required": ["baseline", "current"],
        },
    },
    {
        "name": "npdev_generate",
        "description": (
            "Run the REAL NPDev generator to produce a runnable app from a validated model + config. "
            "Slow and writes to disk -- gate this behind confirmation in your client. Validate first."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "model": {"type": "string", "description": "Path to a validated model.json."},
                "config": {
                    "type": "string",
                    "description": (
                        "Path to a JSON file conforming to the 'ai-generator-config' schema "
                        "(fetch it via npdev_get_schema with schema_name 'ai-generator-config' or "
                        "the alias 'config'). Required shape: {schemaVersion:'ai-generator-config.v1', "
                        "scenario, target:{runtime:'spring-boot',profile:'ai-beta-local'}, "
                        "database:{mode:'embedded-test'|'docker-postgres'}, output:{directory}}. "
                        "This is NOT the same as the richer config.json format used by the human "
                        "Build-AppGenApp.ps1 pipeline (scenario/generator/bootstrap/artifact/"
                        "finalExec/runtime/trialDefaults) that npdev_search_examples may surface -- "
                        "that shape will NOT work here. "
                        "NOTE: the 'output.directory' field INSIDE this config is NOT the same thing "
                        "as this tool's own top-level 'output' argument below -- they are two distinct "
                        "paths. The config's internal output.directory is a relative internal staging "
                        "path matching ^out/generated/<name>$ (e.g. 'out/generated/my-app'); this "
                        "tool's own 'output' argument is the final assembled-app directory and can be "
                        "any absolute or repo-relative path you choose."
                    ),
                },
                "output": {
                    "type": "string",
                    "description": (
                        "Directory to assemble the final generated app into. NOT the same as the "
                        "'output.directory' field required INSIDE the config file (see the 'config' "
                        "argument above) -- that is a separate, differently-shaped internal staging "
                        "path. This argument is the one that actually matters to you: it's where the "
                        "finished, runnable app ends up."
                    ),
                },
            },
            "required": ["model", "config", "output"],
        },
    },
]

TOOL_HANDLERS = {
    "npdev_validate": tool_validate,
    "npdev_inspect_app": tool_inspect_app,
    "npdev_inspect_bonds": tool_inspect_bonds,
    "npdev_list_schemas": tool_list_schemas,
    "npdev_get_schema": tool_get_schema,
    "npdev_search_examples": tool_search_examples,
    "npdev_search_fix": tool_search_fix,
    "npdev_check_support": tool_check_support,
    "npdev_migration_diff": tool_migration_diff,
    "npdev_generate": tool_generate,
}


def _text(text: str) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}], "isError": False}


def _text_error(text: str) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}], "isError": True}


def _passthrough(result: dict[str, Any]) -> dict[str, Any]:
    stdout = (result.get("stdout") or "").strip()
    if result.get("ok") and stdout:
        return _text(stdout)
    detail = (result.get("stderr") or result.get("error") or stdout or "command failed").strip()
    return _text_error(detail)


# ---------------------------------------------------------------------------
# JSON-RPC 2.0 stdio loop (MCP)
# ---------------------------------------------------------------------------

def handle_request(message: dict[str, Any]) -> dict[str, Any] | None:
    method = message.get("method")
    msg_id = message.get("id")

    if method == "initialize":
        params = message.get("params") or {}
        protocol_version = params.get("protocolVersion") or DEFAULT_PROTOCOL_VERSION
        return _result(msg_id, {
            "protocolVersion": protocol_version,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        })

    if method in ("notifications/initialized", "initialized"):
        return None  # notification -- no response

    if method == "ping":
        return _result(msg_id, {})

    if method == "tools/list":
        return _result(msg_id, {"tools": TOOLS})

    if method == "tools/call":
        params = message.get("params") or {}
        name = params.get("name")
        arguments = params.get("arguments") or {}
        handler = TOOL_HANDLERS.get(name)
        if handler is None:
            return _error(msg_id, -32602, f"unknown tool: {name}")
        try:
            return _result(msg_id, handler(arguments))
        except Exception as exc:  # tool crash -> reported as tool error, not a protocol error
            return _result(msg_id, _text_error(f"{type(exc).__name__}: {exc}"))

    if msg_id is None:
        return None  # unknown notification
    return _error(msg_id, -32601, f"method not found: {method}")


def _result(msg_id: Any, result: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def _error(msg_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def main() -> int:
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            sys.stdout.write(json.dumps(_error(None, -32700, "parse error")) + "\n")
            sys.stdout.flush()
            continue
        response = handle_request(message)
        if response is not None:
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
