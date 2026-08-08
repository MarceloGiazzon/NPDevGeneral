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
  - npdev_build_and_run  : GENERATE + BUILD + BOOT + health-check in one call (Move 10 D1)

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
    # npdev-build-root-resolution: identify the repo root by its CONTENTS, not its name -- see
    # scripts/npdev-common.ps1's Get-NPDevBuildRoot comment for the CI failure the name match caused.
    cursor = root
    while cursor is not None and not all(
            (cursor / name).is_dir() for name in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
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
    result = run_cli(args)
    return _passthrough(result)


def tool_author_submit(arguments: dict[str, Any]) -> dict[str, Any]:
    """AI_AUTHORING_CONTRACT-2026-07-31.md Part 9, E4: wraps `npdev author submit` (the E2 diff
    gate + E5 archival) so an Author literally cannot submit a model change against an existing
    app without a manifest -- `manifest` is a REQUIRED argument here, not merely encouraged.
    """
    previous = arguments.get("previous")
    submitted = arguments.get("submitted")
    manifest = arguments.get("manifest")
    if not (previous and submitted and manifest):
        return _text_error("previous, submitted and manifest are all required (C1: an Author "
                            "cannot submit without a manifest).")
    args = ["author", "submit", "--previous", previous, "--submitted", submitted, "--manifest", manifest]
    if arguments.get("archive_dir"):
        args += ["--archive-dir", arguments["archive_dir"]]
    result = run_cli(args)
    return _passthrough(result)


def tool_author_diff_gate(arguments: dict[str, Any]) -> dict[str, Any]:
    """A pure check with no archival -- for an Author to test a candidate submission before
    committing to `npdev_author_submit`. `manifest` is optional here (omitting it still runs the
    gate, which itself reports the C1 refusal) so an Author can also see what a bare, undiffed
    submission looks like without that being a tool-level error.
    """
    previous = arguments.get("previous")
    submitted = arguments.get("submitted")
    if not (previous and submitted):
        return _text_error("previous and submitted are required.")
    args = ["author", "diff-gate", "--previous", previous, "--submitted", submitted]
    if arguments.get("manifest"):
        args += ["--manifest", arguments["manifest"]]
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


def tool_build_and_run(arguments: dict[str, Any]) -> dict[str, Any]:
    """Move 10 D1 (LC-D1): wraps `npdev run app` -- the CLI is the real implementation (testable
    without an MCP client); this tool just runs it and passes its structured JSON straight through.
    """
    model = arguments.get("model")
    config = arguments.get("config")
    output = arguments.get("output")
    if not (model and config and output):
        return _text_error("model, config and output are required")
    args = ["run", "app", "--model", model, "--config", config, "--output", output]
    port = arguments.get("port")
    if port:
        args += ["--port", str(port)]
    timeout = int(arguments.get("timeout") or 420)
    args += ["--timeout", str(timeout)]
    if arguments.get("baseline_model"):
        args += ["--baseline-model", arguments["baseline_model"]]
    if arguments.get("keep_running"):
        args += ["--keep-running"]
    # A few seconds of slack over the CLI's own --timeout so run_cli's OWN timeout (which would
    # produce an unstructured {"ok": false, "error": "cli timed out..."} envelope, not this tool's
    # richer phase/diagnostics shape) never fires first -- the CLI's own bounded teardown should
    # always be what ends an over-budget run.
    result = run_cli(args, timeout=timeout + 60)
    return _passthrough(result)


def tool_build_review_pack(arguments: dict[str, Any]) -> dict[str, Any]:
    mission_id = arguments.get("mission_id")
    if not mission_id:
        return _text_error("mission_id is required")
    args = ["review", "pack", "--mission-id", mission_id]
    if arguments.get("commit"):
        args += ["--commit", arguments["commit"]]
    if arguments.get("repo_root"):
        args += ["--repo-root", arguments["repo_root"]]
    paths = arguments.get("paths")
    if paths:
        args += ["--paths", *paths]
    result = run_cli(args)
    return _passthrough(result)


def tool_ingest_review_verdict(arguments: dict[str, Any]) -> dict[str, Any]:
    mission_id = arguments.get("mission_id")
    vendor_id = arguments.get("vendor_id")
    verdict_file = arguments.get("verdict_file")
    if not (mission_id and vendor_id and verdict_file):
        return _text_error("mission_id, vendor_id and verdict_file are required")
    args = [
        "review", "ingest",
        "--mission-id", mission_id,
        "--vendor-id", vendor_id,
        "--verdict-file", verdict_file,
    ]
    if arguments.get("pack_manifest_sha256"):
        args += ["--pack-manifest-sha256", arguments["pack_manifest_sha256"]]
    result = run_cli(args)
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
            "Classify a model change (baseline model.json -> current model.json) as METADATA_ONLY / "
            "SAFE_ADDITIVE / BACKFILL_REQUIRED / MANUAL_REVIEW, with a per-item reason list -- no live "
            "database involved, no app build. Use before changing an existing app's concepts/fields."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "baseline": {"type": "string", "description": "Path to the previous model.json the app was last generated from."},
                "current": {"type": "string", "description": "Path to the current (candidate) model.json."},
            },
            "required": ["baseline", "current"],
        },
    },
    {
        "name": "npdev_author_diff_gate",
        "description": (
            "AI Authoring Contract Custodian check (pure, no archival): verifies a candidate model.json "
            "against its previous version and a submission manifest -- every removed concept/field is "
            "accounted for by a renamedFrom marker or a declared removal, no hallucinated renames, no "
            "rename bundled with a shape change, version strictly increased, no undeclared access/"
            "permissionRequirements/invariant/sensitive change. Use to self-correct BEFORE calling "
            "npdev_author_submit. manifest is optional here -- omitting it just reports the refusal "
            "that would happen anyway."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "previous": {"type": "string", "description": "Path to the previous model.json (I1)."},
                "submitted": {"type": "string", "description": "Path to the candidate model.json."},
                "manifest": {"type": "string", "description": "Path to a npdev-authoring-submission.v1 manifest JSON file."},
            },
            "required": ["previous", "submitted"],
        },
    },
    {
        "name": "npdev_author_submit",
        "description": (
            "AI Authoring Contract submission (E4): runs the same check as npdev_author_diff_gate, and "
            "on a PASS archives the previous model verbatim (so the next iteration can diff against it). "
            "manifest is REQUIRED -- an Author literally cannot call this without one (C1). Does NOT "
            "itself write the submitted model into place or contact a live app: a passing result still "
            "needs Owner acknowledgment for BACKFILL_REQUIRED/MANUAL_REVIEW changes (route via the "
            "app's existing schema-acknowledgment flow, not a second mechanism)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "previous": {"type": "string", "description": "Path to the previous model.json (I1)."},
                "submitted": {"type": "string", "description": "Path to the candidate model.json."},
                "manifest": {"type": "string", "description": "Path to a npdev-authoring-submission.v1 manifest JSON file."},
                "archive_dir": {"type": "string", "description": "Where the previous model is archived on acceptance. Default: <app root>/model-history/."},
            },
            "required": ["previous", "submitted", "manifest"],
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
    {
        "name": "npdev_build_review_pack",
        "description": (
            "ADR-0009: builds a redacted, chunked external-AI review pack for one mission "
            "(scripts/external-review/missions.json) -- no egress happens here, the pack is only "
            "written locally for review. Fails closed if the sanitizer finds a secret-shaped pattern "
            "or a requested path is a findings/conclusions doc. Never sends anything to a vendor."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "mission_id": {"type": "string", "description": "e.g. 'M2-SEC-ROWAUTHZ' -- see missions.json."},
                "commit": {"type": "string", "description": "Optional: override the mission's pinned git commit."},
                "repo_root": {"type": "string", "description": "Optional: repo root the mission's paths are relative to."},
                "paths": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Optional additional/override repo-relative paths (required for M1/M6, which declare none in missions.json).",
                },
            },
            "required": ["mission_id"],
        },
    },
    {
        "name": "npdev_build_and_run",
        "description": (
            "Move 10 D1: GENERATE -> BUILD -> BOOT -> READY in one call -- the AI loop's missing "
            "half (npdev_generate stops at GENERATE and never builds/boots/health-checks). Returns "
            "structured JSON: {phase, ok, diagnostics[{phase,code,message,suggestedFix,helpKey}], "
            "baseUrl, logExcerpt}. Five named failure codes an agent can branch on without parsing a "
            "stack trace: PORT_IN_USE, SCHEMA_IMPACT_UNACKNOWLEDGED, JAR_NOT_FOUND, STALE_CACHE, "
            "MIGRATION_CLAIM_HELD (anything else falls back to GENERATE_FAILED/BUILD_FAILED/"
            "BOOT_TIMEOUT/BOOT_FAILED). Bounded by --timeout across the WHOLE pipeline (not just "
            "boot) with guaranteed teardown -- a run that fails or times out before READY never "
            "leaves an orphaned JVM. On READY the JVM is deliberately left running (that's the "
            "point) at baseUrl. Slow and writes to disk -- gate this behind confirmation in your "
            "client, same as npdev_generate."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "model": {"type": "string", "description": "Path to a validated model.json."},
                "config": {
                    "type": "string",
                    "description": "Path to an ai-generator-config.v1 JSON file -- same shape npdev_generate takes.",
                },
                "output": {
                    "type": "string",
                    "description": "Directory to assemble the final generated app into (same meaning as npdev_generate's own 'output').",
                },
                "port": {"type": "integer", "description": "Server port to boot on. Default 8080."},
                "timeout": {
                    "type": "integer",
                    "description": "Overall wall-clock budget in seconds across GENERATE+BUILD+BOOT. Default 420.",
                },
                "baseline_model": {
                    "type": "string",
                    "description": (
                        "Optional: a previously-generated model.json to diff 'model' against. If the "
                        "change classifies as METADATA_ONLY (Move 10 C1), takes the C2 fast path "
                        "(swap compiled-model.json + re-sign, skip the full build) automatically."
                    ),
                },
                "keep_running": {
                    "type": "boolean",
                    "description": "Skip the PORT_IN_USE pre-flight refusal (default: refused outright if something is already listening on 'port').",
                },
            },
            "required": ["model", "config", "output"],
        },
    },
    {
        "name": "npdev_ingest_review_verdict",
        "description": (
            "ADR-0009: validates a verdict JSON file (must carry recordKind:'external-ai-verdict', "
            "noRepoAccess:true, autoApplied:false) and, only if it passes, records a RUN entry at "
            "docs/external-ai-review/runs/<mission>.json for the external-AI-review gate. Never "
            "auto-applies anything a verdict recommends -- it only files the record."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "mission_id": {"type": "string"},
                "vendor_id": {"type": "string", "description": "e.g. 'openai', 'gemini', 'xai'."},
                "verdict_file": {"type": "string", "description": "Path to the verdict JSON file."},
                "pack_manifest_sha256": {
                    "type": "string",
                    "description": "Required unless the verdict file itself carries packManifestSha256.",
                },
            },
            "required": ["mission_id", "vendor_id", "verdict_file"],
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
    "npdev_author_diff_gate": tool_author_diff_gate,
    "npdev_author_submit": tool_author_submit,
    "npdev_generate": tool_generate,
    "npdev_build_and_run": tool_build_and_run,
    "npdev_build_review_pack": tool_build_review_pack,
    "npdev_ingest_review_verdict": tool_ingest_review_verdict,
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
