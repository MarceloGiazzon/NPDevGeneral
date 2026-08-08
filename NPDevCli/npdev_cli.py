#!/usr/bin/env python3
"""Portable NPDev command entrypoint."""

from __future__ import annotations

import argparse
import contextlib
import shutil
import hashlib
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

# W5 (storage/FULL_SUPPORT_PLAN.md): the engine list, its per-engine defaults, and the honesty
# status for each, in one module. `npdev init --engine`, `npdev engines`, doctor's database
# checks and the Manager's picker all read it, so none of them can hold a private copy that
# drifts. Imported plainly (not lazily) because argparse needs engine_keys() to build --engine's
# choices at parser-construction time.
import npdev_engines

VERSION = "0.9.0"
# REG-130: the three numbers a bug report could cite, none of them derived from the others.
# Keep in sync by hand (there is no single source of truth yet -- see REG-130's own ledger entry
# for the two shapes considered and why "keep three, explain them" was chosen over collapsing to
# one file): NPDevContract/dsl/build.gradle's own `version` and model.schema.json's own
# properties.dslVersion.const.
DSL_COMPILER_VERSION = "0.1.0"
DSL_MODEL_FORMAT_VERSION = "1.0.0"


class CliError(Exception):
    pass


def repo_root() -> Path:
    env_root = os.environ.get("NPDEV_ROOT")
    if env_root:
        return Path(env_root).expanduser().resolve()
    return Path(__file__).resolve().parents[1]


def _platform_release_tag() -> str:
    """REG-130: the citable identifier for --version's own 'platform' line -- the most recent git
    tag reachable from HEAD, read live from the actual checkout (this CLI is a portable wrapper
    run directly from a clone, not a packaged/versioned artifact, so the checkout's own git state
    IS the accurate answer). Falls back to a plain, honest 'unknown' rather than guessing when git
    is unavailable or the checkout has no tags at all -- an unresolvable input is an error/unknown,
    never a wrong default (the same X0 rule REG-131/REG-136 apply elsewhere)."""
    try:
        completed = subprocess.run(
            ["git", "describe", "--tags", "--always"],
            cwd=repo_root(), capture_output=True, text=True, timeout=5,
        )
        tag = completed.stdout.strip()
        return tag if completed.returncode == 0 and tag else "unknown (no git tag reachable)"
    except (OSError, subprocess.SubprocessError):
        return "unknown (git unavailable)"


# --- validate/fix loop capture (idea 2, live increment) -------------------------------------------
# Best-effort, failure-isolated instrumentation of the validate loop: every semantic validation is
# journaled per model identity; when a diagnostic that was present in a prior run disappears, the
# {resolved diagnostics, model diff} pair is written as a raw candidate under <Build>/npdev-ai/capture.
# scripts/ai/promote_candidates.py later clusters recurring candidates into draft knowledge cards for
# human review. Disable with NPDEV_AI_CAPTURE=0. Writes ONLY to the external Build root, never the repo.
_CAPTURE_DISABLED = {"0", "false", "no", "off", ""}

try:  # share the ONE signature implementation so capture sigs match the rest of the pipeline
    sys.path.insert(0, str(repo_root() / "scripts" / "ai"))
    from failure_signatures import diagnostic_signature as _diagnostic_signature  # type: ignore
except Exception:  # portability: if scripts/ai is absent, capture simply no-ops
    _diagnostic_signature = None


def _is_npdev_repo_root(candidate: Path) -> bool:
    """True when this directory holds the three top-level modules -- the repo root identified by
    what it contains, so any clone name works. Same predicate as WorkspaceRootLocator.java."""
    return all((candidate / name).is_dir()
               for name in ("NPDevContract", "NPDevGenerator", "NPDevKernel"))


def _ai_build_root() -> Path:
    env = os.environ.get("NPDEV_BUILD_ROOT")
    if env and env.strip():
        return Path(env).expanduser().resolve()
    # npdev-build-root-resolution: identify the repo root by its CONTENTS, not its name -- see
    # scripts/npdev-common.ps1's Get-NPDevBuildRoot comment for the CI failure the name match caused.
    cursor = repo_root()
    while cursor is not None and not _is_npdev_repo_root(cursor):
        cursor = cursor.parent if cursor.parent != cursor else None
    if cursor is not None and cursor.parent is not None:
        return cursor.parent / "Build"
    return repo_root().parent / "Build"


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _diag_sig(diag: dict) -> str:
    return _diagnostic_signature(diag) if _diagnostic_signature else "unknown"


def _trunc(value: object, limit: int = 300) -> object:
    try:
        text = json.dumps(value, ensure_ascii=False)
    except Exception:
        text = str(value)
    return value if len(text) <= limit else text[:limit] + "...<truncated>"


_MISSING = object()


def _json_diff(before: object, after: object, path: str = "$", ops: list | None = None) -> list:
    """Compact structural diff of two JSON values (add/remove/change ops), size-bounded."""
    if ops is None:
        ops = []
    if before is None and after is not None:
        ops.append({"op": "add", "path": path, "after": "<entire model>"})
        return ops
    if len(ops) > 200:
        return ops
    if isinstance(after, dict) and isinstance(before, dict):
        for key in sorted(set(before) | set(after)):
            child = f"{path}.{key}"
            if key not in before:
                ops.append({"op": "add", "path": child, "after": _trunc(after[key])})
            elif key not in after:
                ops.append({"op": "remove", "path": child, "before": _trunc(before[key])})
            else:
                _json_diff(before[key], after[key], child, ops)
    elif isinstance(after, list) and isinstance(before, list):
        for i in range(max(len(before), len(after))):
            child = f"{path}[{i}]"
            bv = before[i] if i < len(before) else _MISSING
            av = after[i] if i < len(after) else _MISSING
            if bv is _MISSING:
                ops.append({"op": "add", "path": child, "after": _trunc(av)})
            elif av is _MISSING:
                ops.append({"op": "remove", "path": child, "before": _trunc(bv)})
            elif bv != av:
                _json_diff(bv, av, child, ops)
    elif before != after:
        ops.append({"op": "change", "path": path, "before": _trunc(before), "after": _trunc(after)})
    return ops


def _capture_validation(model_path: Path, report: dict) -> None:
    """Journal this validation; emit a candidate when a prior diagnostic was resolved. Never raises."""
    if os.environ.get("NPDEV_AI_CAPTURE", "1").strip().lower() in _CAPTURE_DISABLED:
        return
    if _diagnostic_signature is None:
        return
    try:
        status = report.get("status")
        errors = [d for d in report.get("diagnostics", [])
                  if isinstance(d, dict) and str(d.get("severity", "error")).lower() == "error"]
        try:
            raw = read_json(Path(model_path).expanduser())
        except Exception:
            raw = {}
        namespace = str((raw.get("namespace") or raw.get("model") or "")).strip() if isinstance(raw, dict) else ""
        key = namespace or str(Path(model_path).expanduser().resolve())
        key_hash = hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]

        cap = _ai_build_root() / "npdev-ai" / "capture"
        journal_dir = cap / "journal"
        cand_dir = cap / "candidates"
        journal_dir.mkdir(parents=True, exist_ok=True)
        cand_dir.mkdir(parents=True, exist_ok=True)
        journal_path = journal_dir / f"{key_hash}.json"

        cur_sigs = [_diag_sig(d) for d in errors]
        cur_sig_set = set(cur_sigs)

        prev = None
        if journal_path.exists():
            with contextlib.suppress(Exception):
                prev = read_json(journal_path)

        if isinstance(prev, dict):
            prev_sigs = prev.get("signatures", []) or []
            prev_errors = prev.get("errors", []) or []
            prev_map = dict(zip(prev_sigs, prev_errors))
            resolved = sorted(set(prev_sigs) - cur_sig_set)
            diff = _json_diff(prev.get("model"), raw)
            if resolved and diff:
                candidate = {
                    "schemaVersion": "capture-candidate.v1",
                    "capturedAt": _utc_now(),
                    "modelKey": key,
                    "outcomeBefore": prev.get("status"),
                    "outcomeAfter": status,
                    "resolvedSignatures": resolved,
                    "resolvedDiagnostics": [prev_map[s] for s in resolved if s in prev_map],
                    "remainingSignatures": sorted(cur_sig_set),
                    "diff": diff,
                }
                out = cand_dir / f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{key_hash}-{uuid.uuid4().hex[:8]}.json"
                out.write_text(json.dumps(candidate, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        if status == "passed":
            with contextlib.suppress(Exception):
                journal_path.unlink()  # cycle complete -- start fresh on the next failing edit
        else:
            journal_path.write_text(json.dumps({
                "updatedAt": _utc_now(), "modelKey": key, "status": status,
                "signatures": cur_sigs, "errors": errors, "model": raw,
            }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    except Exception:
        return  # capture must never break validation


def read_json(path: Path) -> dict:
    try:
        with path.open("r", encoding="utf-8-sig") as handle:
            return json.load(handle)
    except FileNotFoundError as exc:
        raise CliError(f"file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise CliError(f"invalid JSON in {path}: {exc}") from exc


def require_identifier(value: object, label: str, pattern: str) -> None:
    if not isinstance(value, str) or not re.match(pattern, value):
        raise CliError(f"{label} is missing or invalid: {value!r}")


def validate_json_schema(schema: Path, instance: Path) -> dict:
    root = repo_root()
    validator_root = root / "scripts" / "quality" / "json-schema-validator"
    validator_script = validator_root / "validate-json-schema.mjs"
    node_modules = validator_root / "node_modules"
    if not validator_script.exists():
        raise CliError(f"JSON Schema validator wrapper not found: {validator_script}")
    if not node_modules.exists():
        npm = shutil.which("npm.cmd" if os.name == "nt" else "npm") or shutil.which("npm")
        if not npm:
            raise CliError("npm is required to install the canonical JSON Schema validator dependencies")
        # Run `npm install` FROM the validator dir (cwd), not `--prefix <dir>` from the repo root:
        # `--prefix` sets where node_modules lands but npm still reads package.json from cwd, so with
        # cwd=repo-root (which has no package.json) npm ENOENTs on Windows (<drive>:\...\package.json). REG-33.
        subprocess.run([npm, "install", "--silent"], cwd=validator_root, check=True)
    completed = subprocess.run(
        [
            "node",
            str(validator_script),
            "--schema",
            str(schema),
            "--instance",
            str(instance),
        ],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    output = completed.stdout.strip() or "{}"
    try:
        result = json.loads(output)
    except json.JSONDecodeError as exc:
        raise CliError("canonical schema validator did not return JSON") from exc
    if completed.returncode != 0 or result.get("status") != "passed":
        errors = result.get("errors") or []
        detail = "; ".join(
            f"{error.get('path', '/')} {error.get('keyword', '')}: {error.get('message', '')}".strip()
            + _allowed_value_suffix(error)
            for error in errors[:5]
            if isinstance(error, dict)
        )
        raise CliError("canonical model schema validation failed" + (f": {detail}" if detail else ""))
    return result


def _allowed_value_suffix(error: dict) -> str:
    # F4/F5: ajv's default message text for "const"/"enum" violations never names the
    # value(s) it wanted ("must be equal to constant", "must be equal to one of the
    # allowed values") -- exactly the defect that made a wrong dslVersion undiagnosable
    # and turned a type-name typo into a search. Both keywords carry the answer in
    # `params`; surface it instead of leaving the reader to search the schema for it.
    params = error.get("params") if isinstance(error.get("params"), dict) else {}
    if "allowedValue" in params:
        return f" (expected: {params['allowedValue']!r})"
    if "allowedValues" in params and isinstance(params["allowedValues"], list):
        return f" (allowed: {', '.join(repr(v) for v in params['allowedValues'])})"
    return ""


def validate_official_model(path: Path) -> None:
    root = repo_root()
    model = Path(path).expanduser().resolve()
    schema = root / "NPDevContract" / "schemas" / "model.schema.json"
    resolved = resolve_split_model(model)
    with tempfile.TemporaryDirectory(prefix="npdev-model-") as temp_dir:
        resolved_path = Path(temp_dir) / "resolved-model.json"
        resolved_path.write_text(json.dumps(resolved, indent=2) + "\n", encoding="utf-8")
        validate_json_schema(schema, resolved_path)


MODEL_ARRAY_KEYS = {
    "concepts",
    "domainTypes",
    "capabilities",
    "customCapabilities",
    "bindings",
    "events",
    "flows",
    "orchestrationRules",
    "orchestrations",
    "queries",
    "ruleProfiles",
    "procedures",
    "panels",
}
ROOT_SCALAR_KEYS = {"$schema", "schemaVersion", "dslVersion", "namespace", "model", "version"}
FRAGMENT_KEYS = MODEL_ARRAY_KEYS | {"metadata", "fragments"}


def resolve_split_model(path: Path, collect_sources: set[Path] | None = None) -> dict:
    """Compose a (possibly $ref-split) model into one dict.

    `collect_sources`, when given, is populated with every file that contributed -- the root
    plus every fragment reached through $ref. `npdev dev` uses it as its watch set: a model is
    a graph since bounded contexts (S3), so watching model.json alone misses fragment edits.
    Exposed as an out-parameter rather than a second traversal on purpose -- two walks of the
    same $ref graph would drift, which is REG-108's exact shape.
    """
    root_path = Path(path).expanduser().resolve(strict=True)
    root_dir = root_path.parent.resolve(strict=True)
    seen: set[Path] = set() if collect_sources is None else collect_sources
    seen.add(root_path)

    def fail(label: str, message: str) -> None:
        raise CliError(f"{label}: {message}")

    def include_path(ref: object, referencing: Path) -> Path:
        if not isinstance(ref, str) or not ref.strip():
            fail(str(referencing), "$ref must be a non-blank string")
        normalized_ref = ref.replace("\\", "/")
        if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", normalized_ref):
            fail(str(referencing), f"model include ref must be local, not a URL: {ref}")
        candidate_ref = Path(ref)
        if candidate_ref.is_absolute():
            fail(str(referencing), f"model include ref must be relative: {ref}")
        if candidate_ref.suffix.lower() != ".json":
            fail(str(referencing), f"model include ref must point to a .json file: {ref}")
        try:
            candidate = (referencing.parent / candidate_ref).resolve(strict=True)
        except FileNotFoundError as exc:
            raise CliError(f"{referencing}: referenced model fragment not found: {ref}") from exc
        if root_dir not in [candidate, *candidate.parents]:
            fail(str(referencing), f"referenced model fragment escapes the model root: {ref}")
        if not candidate.is_file():
            fail(str(referencing), f"referenced model fragment is not a file: {ref}")
        return candidate

    def ref_value(value: object, label: str) -> str | None:
        if isinstance(value, dict) and "$ref" in value:
            if set(value.keys()) != {"$ref"}:
                fail(label, '$ref object must be exactly { "$ref": "relative/path.json" }')
            ref = value["$ref"]
            if not isinstance(ref, str) or not ref.strip():
                fail(label, "$ref must be a non-blank string")
            return ref
        return None

    def read_fragment(fragment_path: Path, depth: int, stack: list[Path]) -> object:
        if depth > 32:
            fail(str(fragment_path), "maximum model include depth exceeded: 32")
        if fragment_path in stack:
            fail(str(fragment_path), "circular model include detected")
        if fragment_path not in seen:
            seen.add(fragment_path)
            if len(seen) > 512:
                fail(str(fragment_path), "maximum model include file count exceeded: 512")
        data = read_json(fragment_path)
        validate_refs(data, str(fragment_path))
        return data

    def validate_refs(value: object, label: str) -> None:
        if isinstance(value, dict):
            ref_value(value, label)
            for child_key, child_value in value.items():
                validate_refs(child_value, f"{label}/{child_key}")
        elif isinstance(value, list):
            for index, child in enumerate(value):
                validate_refs(child, f"{label}/{index}")

    def resolve_array(key: str, values: object, source: Path, depth: int, stack: list[Path]) -> list:
        if not isinstance(values, list):
            fail(str(source), f"{key} must be an array")
        out: list = []
        for index, item in enumerate(values):
            ref = ref_value(item, f"{source}/{key}/{index}")
            if ref is None:
                out.append(item)
                continue
            child_path = include_path(ref, source)
            child = read_fragment(child_path, depth + 1, [*stack, source])
            if isinstance(child, dict) and isinstance(child.get(key), list):
                out.extend(resolve_array(key, child[key], child_path, depth + 1, [*stack, source]))
            else:
                out.append(child)
        return out

    def resolve_model_fragment(fragment_path: Path, depth: int, stack: list[Path]) -> dict:
        fragment = read_fragment(fragment_path, depth, stack)
        if not isinstance(fragment, dict):
            fail(str(fragment_path), "model fragment must be an object")
        unsupported = set(fragment.keys()) - FRAGMENT_KEYS
        if unsupported:
            fail(str(fragment_path), "unsupported model fragment key: " + sorted(unsupported)[0])
        resolved_fragment: dict = {}
        for key in MODEL_ARRAY_KEYS:
            if key in fragment:
                resolved_fragment[key] = resolve_array(key, fragment[key], fragment_path, depth + 1, [*stack, fragment_path])
        if "metadata" in fragment:
            if not isinstance(fragment["metadata"], dict):
                fail(str(fragment_path), "metadata must be an object")
            resolved_fragment["metadata"] = dict(fragment["metadata"])
        for index, nested in enumerate(fragment.get("fragments") or []):
            ref = ref_value(nested, f"{fragment_path}/fragments/{index}")
            if ref is None:
                fail(f"{fragment_path}/fragments/{index}", "fragment entry must be a $ref object")
            append_fragment(resolved_fragment, resolve_model_fragment(include_path(ref, fragment_path), depth + 1, [*stack, fragment_path]), set())
        return resolved_fragment

    def append_fragment(target: dict, fragment: dict, root_metadata_keys: set[str]) -> None:
        for key in MODEL_ARRAY_KEYS:
            if key in fragment:
                target.setdefault(key, [])
                target[key].extend(fragment[key])
        if "metadata" in fragment:
            metadata = target.setdefault("metadata", {})
            for meta_key, meta_value in fragment["metadata"].items():
                if meta_key in root_metadata_keys:
                    continue
                if meta_key in metadata:
                    fail(str(root_path), f"duplicate fragment metadata key: {meta_key}")
                metadata[meta_key] = meta_value

    raw = read_json(root_path)
    if not isinstance(raw, dict):
        fail(str(root_path), "root model must be an object")
    unsupported = set(raw.keys()) - (ROOT_SCALAR_KEYS | MODEL_ARRAY_KEYS | {"metadata", "fragments"})
    if unsupported:
        fail(str(root_path), "unsupported model top-level key: " + sorted(unsupported)[0])
    validate_refs(raw, str(root_path))

    resolved = {key: raw[key] for key in ROOT_SCALAR_KEYS if key in raw}
    for key in MODEL_ARRAY_KEYS:
        if key in raw:
            resolved[key] = resolve_array(key, raw[key], root_path, 0, [root_path])
    root_metadata_keys = set((raw.get("metadata") or {}).keys()) if isinstance(raw.get("metadata"), dict) else set()
    if "metadata" in raw and not isinstance(raw["metadata"], dict):
        fail(str(root_path), "metadata must be an object")
    for index, fragment_ref in enumerate(raw.get("fragments") or []):
        ref = ref_value(fragment_ref, f"{root_path}/fragments/{index}")
        if ref is None:
            fail(f"{root_path}/fragments/{index}", "fragment entry must be a $ref object")
        append_fragment(resolved, resolve_model_fragment(include_path(ref, root_path), 1, [root_path]), root_metadata_keys)
    if "metadata" in raw:
        metadata = resolved.setdefault("metadata", {})
        metadata.update(raw["metadata"])
    return resolved


def ai_type_to_dsl(value: str) -> str:
    return {
        "text": "string",
        "email": "string",
        "integer": "integer",
        "boolean": "boolean",
        "date": "date",
        "datetime": "datetime",
        "uuid": "uuid",
    }.get(value, "string")


def normalize_ai_model(path: Path) -> dict:
    model = read_json(path)
    if model.get("schemaVersion") != "ai-model.v1":
        raise CliError("ai-model schemaVersion must be ai-model.v1")
    app = model.get("app") or {}
    app_name = app.get("name") or "npdev-app"
    concepts = []
    for entity in model.get("entities") or []:
        require_identifier(entity.get("name"), "entity.name", r"^[A-Z][A-Za-z0-9]*$")
        fields = []
        for field in entity.get("fields") or []:
            require_identifier(field.get("name"), "field.name", r"^[a-zA-Z][A-Za-z0-9]*$")
            fields.append(
                {
                    "name": field["name"],
                    "type": ai_type_to_dsl(str(field.get("type", "string"))),
                    "required": bool(field.get("required", False)),
                }
            )
        if not fields:
            raise CliError(f"entity {entity['name']} must contain fields")
        concepts.append({"name": entity["name"], "fields": fields})
    if not concepts:
        raise CliError("ai-model entities must contain at least one entity")
    flows = []
    for flow in model.get("flows") or []:
        require_identifier(flow.get("name"), "flow.name", r"^[A-Z][A-Za-z0-9]*$")
        flows.append(
            {
                "name": flow["name"],
                "input": {
                    "concept": flow.get("entity"),
                    "mode": flow.get("operation", "create"),
                },
                "steps": [
                    {
                        "name": "return-input",
                        "type": "return",
                        "value": "$input",
                    }
                ],
            }
        )
    namespace = re.sub(r"[^a-z0-9]+", ".", app_name.lower()).strip(".") or "npdev.app"
    return {
        "dslVersion": "1.0.0",
        "version": "1.0",
        "namespace": namespace,
        "concepts": concepts,
        "flows": flows,
    }


def write_or_print_json(value: dict, output: str | None) -> None:
    text = json.dumps(value, indent=2)
    if output:
        target = Path(output).expanduser()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text + "\n", encoding="utf-8")
        print(str(target))
    else:
        print(text)


def inspect_bonds(args: argparse.Namespace) -> None:
    model_path = Path(args.model).expanduser().resolve()
    model = resolve_split_model(model_path)
    concepts = {concept.get("name"): concept for concept in model.get("concepts") or [] if isinstance(concept, dict)}
    bonds: list[dict] = []
    risks: list[dict] = []

    for concept_name, concept in concepts.items():
        fields = concept.get("fields") or []
        if not isinstance(fields, list):
            continue
        for field in fields:
            if not isinstance(field, dict):
                continue
            reference = field.get("reference") if isinstance(field.get("reference"), dict) else {}
            target = reference.get("target") or field.get("referenceTarget")
            if field.get("type") != "reference" and not target:
                continue
            via = reference.get("via") or "id"
            on_delete = reference.get("onDelete") or "restrict"
            target_concept = concepts.get(target)
            anchor = find_anchor(target_concept, via)
            multiple = bool(reference.get("multiple"))
            bond = {
                "sourceConcept": concept_name,
                "sourceField": field.get("name"),
                "targetConcept": target,
                "via": via,
                "anchorFound": anchor is not None,
                "anchorType": anchor.get("type") if isinstance(anchor, dict) else None,
                "cardinality": "many-to-many" if multiple else ("one-to-one" if field.get("unique") else "many-to-one"),
                "onDelete": on_delete,
                "sourceTruthLevel": concept.get("truthLevel", "T1"),
                "targetTruthLevel": target_concept.get("truthLevel", "T1") if isinstance(target_concept, dict) else None,
            }
            source_rank = truth_rank(bond["sourceTruthLevel"])
            target_rank = truth_rank(bond["targetTruthLevel"])
            bond["upwardTruthEdge"] = target_rank is not None and source_rank > target_rank
            bonds.append(bond)
            if not bond["anchorFound"]:
                risks.append({"kind": "missing_anchor", "bond": f"{concept_name}.{field.get('name')}"})
            if not multiple:
                risks.append({
                    "kind": "dangling_fk_precheck_required",
                    "bond": f"{concept_name}.{field.get('name')}",
                    "detail": "Before enabling FK constraints, verify all non-null source values exist on the target anchor."
                })
            if bond["upwardTruthEdge"]:
                risks.append({"kind": "upward_truth_edge", "bond": f"{concept_name}.{field.get('name')}"})

    write_or_print_json(
        {
            "model": str(model_path),
            "bondCount": len(bonds),
            "bonds": bonds,
            "migrationRisks": risks,
        },
        args.output,
    )


def find_anchor(concept: dict | None, via: str) -> dict | None:
    if not isinstance(concept, dict):
        return None
    for field in concept.get("fields") or []:
        if not isinstance(field, dict):
            continue
        if via == "id" and field.get("id"):
            return field
        if field.get("name") == via and (field.get("id") or field.get("connectable") == "anchor" or field.get("unique")):
            return field
    return None


def truth_rank(value: object) -> int | None:
    if not isinstance(value, str):
        return None
    match = re.match(r"^T([0-6])$", value.strip(), re.IGNORECASE)
    return int(match.group(1)) if match else None


def migrate_legacy_model(args: argparse.Namespace) -> None:
    source = Path(args.input).expanduser().resolve()
    target = Path(args.output).expanduser().resolve()
    model = read_json(source)
    if "entities" in model and "concepts" not in model:
        model["concepts"] = model.pop("entities")
    elif "entities" in model:
        del model["entities"]
    schema_value = str(model.get("$schema", "")).replace("\\", "/")
    if schema_value.endswith("/model-" + "1.0.0" + ".schema.json") or schema_value.endswith("model-" + "1.0.0" + ".schema.json"):
        model["$schema"] = "NPDevContract/schemas/model.schema.json"
    if not model.get("$schema"):
        model["$schema"] = "NPDevContract/schemas/model.schema.json"
    model.setdefault("dslVersion", "1.0.0")
    model.pop("schemaVersion", None)
    validate_json_schema(repo_root() / "NPDevContract" / "schemas" / "model.schema.json", write_temp_model(model, target))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    print(str(target))


def run_migrate_dsl2(args: argparse.Namespace) -> int:
    """2.A.3 (docs/DSL2_AND_DECOMPOSITION_PLAN.md): rewrite flowStep.type spellings and field
    aliases to their DSL 2.0 canonical form, across one or more files/directories. Dry-run by
    default (reports what would change); pass --write to apply. See dsl_v2_migration.py's module
    docstring for the full design: idempotent, structural (never a blind key-value replace), and
    refuses to touch anything it detects as a serialized compiled-model fixture rather than a raw
    authored document.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from dsl_v2_migration import migrate_document  # local import: keep this optional dependency

    inputs = [Path(p).expanduser().resolve() for p in args.input]
    files: list[Path] = []
    for p in inputs:
        if p.is_dir():
            files.extend(sorted(p.rglob("*.json")))
        elif p.is_file():
            files.append(p)
        else:
            print(f"npdev migrate dsl-2: input not found: {p}", file=sys.stderr)
            return 2

    changed_count = 0
    compiled_skipped_count = 0
    ambiguous_count = 0
    unchanged_count = 0
    invalid_count = 0
    report_entries = []

    for f in files:
        try:
            doc = read_json(f)
        except CliError as exc:
            invalid_count += 1
            print(f"  [SKIP] {f}: {exc}", file=sys.stderr)
            continue
        if not isinstance(doc, dict):
            continue

        result = migrate_document(doc)
        report_entries.append({
            "file": str(f),
            "changed": result.changed,
            "isCompiled": result.is_compiled,
            "changes": result.changes,
            "ambiguities": result.ambiguities,
        })

        if result.is_compiled:
            compiled_skipped_count += 1
            continue
        if result.ambiguities:
            ambiguous_count += 1
            for a in result.ambiguities:
                print(f"  [AMBIGUOUS] {f}: {a}")
        if result.changed:
            changed_count += 1
            verb = "CHANGED" if args.write else "WOULD CHANGE"
            for c in result.changes:
                print(f"  [{verb}] {f}: {c}")
            if args.write:
                f.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
        else:
            unchanged_count += 1

    print(
        f"\n{len(files)} file(s) scanned: {changed_count} changed, {compiled_skipped_count} "
        f"compiled-model (skipped), {ambiguous_count} with ambiguities left untouched, "
        f"{unchanged_count} already canonical, {invalid_count} invalid JSON (skipped)"
    )
    if not args.write and changed_count > 0:
        print("Dry run -- pass --write to apply.")

    if args.report:
        report_path = Path(args.report).expanduser()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report_entries, indent=2) + "\n", encoding="utf-8")
        print(f"Report written: {report_path}")

    return 1 if invalid_count > 0 else 0


def run_migrate_bounded_contexts(args: argparse.Namespace) -> int:
    """S3 (docs/adr/ADR-0011-bounded-contexts.md, S3_SPEC.md Section 2): wraps an existing model's
    whole content into one new bounded context. Dry-run by default (reports every relocation/change);
    pass --write to actually relocate files and write JSON. See
    dsl_v2_migration_bounded_contexts.py's module docstring for why this relocates files rather than
    rewriting $ref strings with '../' (schema-forbidden, not just risky).
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from dsl_v2_migration_bounded_contexts import apply_migration, migrate_document  # local import: keep this optional dependency

    dirs = [Path(p).expanduser().resolve() for p in args.input]
    model_files: list[Path] = []
    for d in dirs:
        if not d.is_dir():
            print(f"npdev migrate bounded-contexts: input is not a directory: {d}", file=sys.stderr)
            return 2
        model_file = d / "model.json"
        if not model_file.is_file():
            print(f"npdev migrate bounded-contexts: no model.json found directly in {d}", file=sys.stderr)
            return 2
        model_files.append(model_file)

    changed_count = 0
    skipped_count = 0
    invalid_count = 0
    report_entries = []

    for model_file in model_files:
        base_dir = model_file.parent
        try:
            doc = read_json(model_file)
        except CliError as exc:
            invalid_count += 1
            print(f"  [SKIP] {model_file}: {exc}", file=sys.stderr)
            continue
        if not isinstance(doc, dict):
            continue

        plan = migrate_document(doc, base_dir)
        result = plan.result
        report_entries.append({
            "model": str(model_file),
            "changed": result.changed,
            "skipped": result.skipped,
            "changes": result.changes,
            "ambiguities": result.ambiguities,
        })

        for a in result.ambiguities:
            print(f"  [AMBIGUOUS] {model_file}: {a}")
        if result.skipped:
            skipped_count += 1
            continue

        changed_count += 1
        verb = "CHANGED" if args.write else "WOULD CHANGE"
        for c in result.changes:
            print(f"  [{verb}] {model_file}: {c}")
        if args.write:
            apply_migration(plan, base_dir, model_file)

    print(
        f"\n{len(model_files)} model(s) scanned: {changed_count} changed, {skipped_count} skipped "
        f"(already contexts[]/no movable content/unresolvable ref), {invalid_count} invalid JSON (skipped)"
    )
    if not args.write and changed_count > 0:
        print("Dry run -- pass --write to apply.")

    if args.report:
        report_path = Path(args.report).expanduser()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report_entries, indent=2) + "\n", encoding="utf-8")
        print(f"Report written: {report_path}")

    return 1 if invalid_count > 0 else 0


def write_temp_model(model: dict, target: Path) -> Path:
    temp = target.parent / (target.name + ".validation.tmp")
    temp.parent.mkdir(parents=True, exist_ok=True)
    temp.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    return temp


def gradle_wrapper(generator_root: Path) -> Path:
    if os.name == "nt":
        return generator_root / "gradlew.bat"
    return generator_root / "gradlew"


def gradle_project_cache_args(module_key: str) -> list[str]:
    """org.gradle.projectcachedir is a Gradle START PARAMETER, read from gradle.properties before
    any -P/env override can take effect -- the root/dsl/generator/kernel gradle.properties files
    hardcode it to this machine's own <drive>:/WorkSpace/NPDev/Build/gradle-project-caches/<module>
    (intentional dev-machine build-output policy: keeps Gradle's own cache out of the repo tree,
    per this repo's own "never write build artifacts inside the repo" rule). On any machine
    without that exact path, Gradle fails before the build even starts ("Cannot convert URL
    '...' to a file"), breaking the first command in README's own Quickstart. --project-cache-dir
    is the one reliable override (a start parameter, not a build-script property, so it beats the
    properties file). Computed the same way _ai_build_root() is, so this is a no-op on the
    author's own machine (identical resolved path) and portable everywhere else."""
    return ["--project-cache-dir", str(_ai_build_root() / "gradle-project-caches" / module_key)]


def gradle_args_value(args: list[str]) -> str:
    if os.name == "nt":
        return subprocess.list2cmdline(args)
    return shlex.join(args)


# Default db.definition.json used when a sample/app doesn't ship one. InMemory needs no
# host/port/username (UserDatabaseDefinitionLoader.validate skips those checks for it) and is the
# only engine proven compatible with the ai-beta-local smoke profile today: its resolved database
# name is blank by design, so DatabaseIdentityStartupValidator's identity check is a no-op. Matches
# NPDevSamples/canonical-demo/Input/db.definition.json exactly. sample-matrix-policy.json's
# requiredInputFiles list doesn't include db.definition.json either -- this default aligns the CLI
# with what the samples' own policy already treats as optional.
DEFAULT_DB_DEFINITION = {
    "database": {
        "engine": "InMemory",
        "createInternalTables": True,
        "createBusinessTables": True,
    },
    "schemaLifecycle": {
        "strategy": "RecreateOnAppStart",
        "allowDestructiveRecreate": True,
        "destructiveRecreateConfirmation": "I_UNDERSTAND_INMEMORY_DATA_IS_EPHEMERAL",
        "scope": "NpdevOwnedLogicalStoresOnly",
    },
}


def run_review_pack(args: argparse.Namespace) -> None:
    """ADR-0009 / P6: shells out to the platform pack producer -- one implementation, not a second
    copy of the chunk/sanitize/manifest algorithm inside the CLI itself."""
    root = repo_root()
    script = root / "scripts" / "external-review" / "build-review-pack.py"
    if not script.exists():
        raise CliError(f"pack producer not found: {script}")
    command = [sys.executable, str(script), "--mission-id", args.mission_id]
    if args.commit:
        command += ["--commit", args.commit]
    if args.paths:
        command += ["--paths", *args.paths]
    if args.repo_root:
        command += ["--repo-root", args.repo_root]
    if args.output_dir:
        command += ["--output-dir", args.output_dir]
    subprocess.run(command, cwd=root, check=True)


def run_review_ingest(args: argparse.Namespace) -> None:
    """ADR-0009 / P6: shells out to the platform producer's --ingest-verdict-file mode -- the single
    place the honesty-field validation (recordKind/noRepoAccess/autoApplied) lives on the Python side."""
    root = repo_root()
    script = root / "scripts" / "external-review" / "build-review-pack.py"
    if not script.exists():
        raise CliError(f"pack producer not found: {script}")
    command = [
        sys.executable, str(script),
        "--mission-id", args.mission_id,
        "--ingest-verdict-file", args.verdict_file,
        "--vendor-id", args.vendor_id,
    ]
    if args.pack_manifest_sha256:
        command += ["--pack-manifest-sha256", args.pack_manifest_sha256]
    subprocess.run(command, cwd=root, check=True)


def _run_with_phase_narration(command: list[str], cwd: Path, markers: list[tuple[str, str]]) -> None:
    """P2 (FIRST_IMPRESSION_PLAN.md I4): a cold `generate app` shells out to ONE Gradle task that
    silently does Gradle-dependency-resolution + compile + generate + assemble in a single JVM
    invocation -- from the caller's side that is ~10 minutes with nothing to look at beyond
    Gradle's own raw task-by-task chatter, which means nothing to a newcomer.

    Rather than teach GeneratorMain.java a new phase-marker vocabulary (bigger, riskier, needs its
    own Java-side verification), this streams the child's stdout back out UNCHANGED line-for-line
    -- so anything parsing our stdout sees exactly what it always has -- and separately watches for
    a few of GeneratorMain's OWN existing milestone lines (already printed today, just easy to miss
    in the noise), printing one friendly phase-transition line to STDERR the first time each is
    seen. `markers` is an ordered list of (substring-to-watch-for, message-to-print); stderr, not
    stdout, carries the narration.
    """
    process = subprocess.Popen(
        command, cwd=cwd, stdout=subprocess.PIPE, stderr=None,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    remaining = list(markers)
    assert process.stdout is not None
    for line in process.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        while remaining and remaining[0][0] in line:
            _, message = remaining.pop(0)
            print(message, file=sys.stderr)
    process.wait()
    if process.returncode != 0:
        raise subprocess.CalledProcessError(process.returncode, command)


def run_init(args: argparse.Namespace) -> int:
    """I3: scaffold a new app directory from a small, corpus-registered seed (NPDevSamples/
    npdev-init-seed -- 2 concepts + 1 bond, derived from canonical-demo's own Patient/Appointment
    rather than a hand-written second fixture, so it stays covered by the same DSL-coverage gate
    canonical-demo is) and give it a git history from the first commit. The model IS the app --
    losing model.json loses the application, so a scaffold with no history is a trap, not a
    convenience (see docs/YOUR_FIRST_APP.md's own step 4 for the same rule stated for a human)."""
    root = repo_root()
    target = Path(args.name).expanduser().resolve()

    try:
        target.relative_to(root)
        raise CliError(
            f"refusing to scaffold inside this repo ({root}) -- {target} would never be found by "
            "anyone cloning NPDev fresh, and risks being swept up by this repo's own git history. "
            "Pick a directory outside the repo."
        )
    except ValueError:
        pass  # not inside root -- the expected case

    if target.exists() and any(target.iterdir()):
        raise CliError(f"refusing to scaffold into a non-empty directory: {target}")

    seed_dir = root / "NPDevSamples" / (args.from_sample or "npdev-init-seed")
    seed_model_path = seed_dir / "model.json"
    seed_config_path = seed_dir / "config.json"
    if not seed_model_path.exists() or not seed_config_path.exists():
        raise CliError(
            f"seed directory {seed_dir} does not have both model.json and config.json -- "
            f"{'pick a different --from' if args.from_sample else 'this is an npdev bug, not a user error'}."
        )

    token = re.sub(r"[^a-zA-Z0-9]+", "", target.name) or "app"
    db_name = re.sub(r"[^a-zA-Z0-9]+", "_", target.name).strip("_").lower() or "npdev_app"

    target.mkdir(parents=True, exist_ok=True)

    model = json.loads(seed_model_path.read_text(encoding="utf-8"))
    model["namespace"] = token
    (target / "model.json").write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")

    shutil.copy2(seed_config_path, target / "config.json")

    # W5.1 (storage/FULL_SUPPORT_PLAN.md, exit criterion E9). Until this flag existed, `npdev init`
    # had no way to choose an engine at all: a user who wanted MySQL had to hand-edit
    # db.definition.json, which is precisely the "you must know the internals" experience the Manager
    # exists to remove. The default is `h2local`, which is byte-for-byte what the seed already wrote,
    # so an existing invocation is unchanged.
    engine_key = getattr(args, "engine", None) or "h2local"
    try:
        engine = npdev_engines.resolve(engine_key)
    except ValueError as exc:
        raise CliError(str(exc)) from exc

    seed_db_def_path = seed_dir / "db.definition.json"
    if seed_db_def_path.exists() or engine["key"] != "h2local":
        db_def = npdev_engines.db_definition_for(
            engine["key"],
            database_name=db_name,
            host=getattr(args, "db_host", None),
            port=getattr(args, "db_port", None),
            username=getattr(args, "db_user", None),
            password=getattr(args, "db_password", None),
        )
        (target / "db.definition.json").write_text(json.dumps(db_def, indent=2) + "\n", encoding="utf-8")

    # The scenario config's own database block must agree with the engine just chosen. It was
    # previously copied verbatim from the seed, which declares `docker-postgres` -- so every app
    # `npdev init` has ever scaffolded carried a provisioning block describing an engine it does not
    # use. Harmless until something reads it; W6.1 made config.json enforceable, and an enforceable
    # contract that contradicts the app beside it is worse than no contract.
    _align_config_database(target / "config.json", engine, db_name,
                           host=getattr(args, "db_host", None),
                           port=getattr(args, "db_port", None),
                           username=getattr(args, "db_user", None),
                           password=getattr(args, "db_password", None))

    (target / "README.md").write_text(
        f"# {target.name}\n\n"
        f"An NPDev app, scaffolded by `npdev init`.\n\n"
        f"**`model.json` is this application.** Everything else -- the database schema, the REST "
        f"API, the admin screens -- is generated from it and is disposable: delete it, regenerate "
        f"it, nothing is lost as long as model.json (and db.definition.json, which says how your "
        f"data persists) survive. This directory is already a git repository with one commit for "
        f"exactly that reason -- keep committing as you change the model.\n\n"
        f"## Run it\n\n"
        f"```sh\n"
        f"cd {target.name}\n"
        f"npdev run app\n"
        f"```\n\n"
        f"(`npdev run app` with no flags looks for `model.json`/`config.json` in the current "
        f"directory and generates the app into a sibling `{target.name}-app` folder.)\n\n"
        f"Open http://localhost:8080 and use API key `dev-key` (the `dev` profile's built-in "
        f"convenience credential -- see docs/YOUR_FIRST_APP.md for what that means and why "
        f"SUPER_USER_KEY.txt is a different, unrelated thing).\n\n"
        f"## Next\n\n"
        f"Edit `model.json` -- add a field, add a concept -- then `npdev run app` again; it "
        f"regenerates the SAME app in place rather than starting over. See docs/YOUR_FIRST_APP.md "
        f"for the full walkthrough, including how to declare a rename so NPDev does not mistake it "
        f"for a destructive change.\n",
        encoding="utf-8",
    )

    (target / ".gitignore").write_text(
        "# Generated app output lives in a SIBLING directory by convention (see README.md) and\n"
        "# is disposable -- if one ever ends up nested here by accident, don't track it.\n"
        "*-app/\n"
        "\n"
        "# `npdev dev`'s working state: the dev database, boot log, and the baseline it diffs\n"
        "# each save against. Disposable -- delete it and the next `npdev dev` rebuilds it.\n"
        ".npdev-dev/\n"
        "\n"
        "# OS/editor noise\n"
        ".DS_Store\n"
        "Thumbs.db\n"
        "*.swp\n",
        encoding="utf-8",
    )

    git_note = _scaffold_git_history(target)

    created_files = [name for name in
                     ("model.json", "config.json", "db.definition.json", "README.md", ".gitignore")
                     if (target / name).exists()]

    # W5.3's rule, applied to the CLI first: an experimental engine says so AT THE POINT OF CHOICE,
    # not in a changelog. An interface that silently offers MySQL is the silent-answer defect wearing
    # a dropdown.
    notice = npdev_engines.honesty_notice(engine["key"])

    if getattr(args, "json", False):
        # I3: one object, nothing else on stdout -- absolute paths only, since the Manager has no
        # shared working directory to resolve a relative one against.
        result = {
            "schemaVersion": "npdev-cli-result.v1",
            "command": "init",
            "ok": True,
            "exitCode": 0,
            "created": {
                "directory": str(target),
                "files": created_files,
                "gitInitialised": True,
                "nextCommand": "npdev dev",
            },
            # Additive: every key above is byte-identical to before, so the Manager's existing
            # contract is untouched.
            "engine": {
                "key": engine["key"],
                "externalName": engine["externalName"],
                "status": engine["status"],
                "honestyNotice": notice,
            },
            # Non-null only when git had no identity and one was substituted for this repo alone.
            # The Manager can surface it beside "created." -- a substitution nobody is told about is
            # a worse surprise than the substitution itself.
            "gitIdentityNotice": git_note,
        }
        print(json.dumps(result, indent=2))
        return 0

    print(f"Created {target}")
    for name in created_files:
        print(f"  {name}")
    print(f"\ndatabase: {engine['externalName']} -- {engine['summary']}")
    if notice:
        print(f"\n  ! {notice}")
    print(f"\ngit: initialized, 1 commit")
    if git_note:
        print(f"\n  ! {git_note}")
    print(f"\nNext:\n  cd {target.name}\n  npdev run app")
    return 0


def _scaffold_git_history(target: Path) -> str | None:
    """`git init` + first commit -- and never fail the whole scaffold because git has no identity.

    FOUND IN CI, 2026-08-08 (engine-support run 31272295843), and it is a real third-person defect
    rather than a CI quirk. `git commit` on a machine with no `user.name`/`user.email` exits 128 with
    "Author identity unknown" -- and a machine with no git identity is precisely a FRESH machine, the
    one this command exists for. Before this, `npdev init` propagated that exit code and left a
    half-scaffolded directory: files written, no repository, and a git error the user has to decode.
    The Manager shells straight to this command, so it would have failed there too, on a first run.

    The scaffold's git history is not decoration -- README and YOUR_FIRST_APP both say so: the model
    IS the application, and a scaffold with no history is a trap rather than a convenience. So the
    commit is retried with an identity scoped to THAT repository only (`-c`, which never writes to
    the user's global config), and the substitution is REPORTED rather than done quietly. A tool that
    silently commits under a name the user did not choose is a small surprise; one that does it
    without saying so is a bigger one.
    """
    subprocess.run(["git", "init", "--quiet"], cwd=target, check=True)
    subprocess.run(["git", "add", "."], cwd=target, check=True)
    message = f"npdev init: scaffold {target.name}"

    first = subprocess.run(["git", "commit", "--quiet", "-m", message],
                           cwd=target, capture_output=True, text=True)
    if first.returncode == 0:
        return None

    stderr = (first.stderr or "") + (first.stdout or "")
    if "ident" not in stderr.lower() and "author identity" not in stderr.lower():
        # A different failure -- an empty commit, a hook, a broken repo. Not this function's problem
        # to paper over, and inventing an identity would not fix it anyway.
        raise CliError(
            f"could not create the first commit in {target}: {stderr.strip() or 'git failed'}")

    fallback_name, fallback_email = "NPDev", "npdev@localhost"
    retried = subprocess.run(
        ["git", "-c", f"user.name={fallback_name}", "-c", f"user.email={fallback_email}",
         "commit", "--quiet", "-m", message],
        cwd=target, capture_output=True, text=True)
    if retried.returncode != 0:
        raise CliError(
            f"git has no configured identity and the fallback commit also failed in {target}: "
            f"{(retried.stderr or '').strip()}\n"
            f"Set one and commit by hand:\n"
            f"  git config --global user.name \"Your Name\"\n"
            f"  git config --global user.email \"you@example.com\"")
    return (f"git has no configured user.name/user.email on this machine, so the first commit was "
            f"authored as {fallback_name} <{fallback_email}> -- in this repository only, nothing "
            f"global was changed. Set your own with:\n"
            f"  git config --global user.name \"Your Name\"\n"
            f"  git config --global user.email \"you@example.com\"")


def _align_config_database(config_path: Path, engine: dict, database_name: str, *,
                           host=None, port=None, username=None, password=None) -> None:
    """Make the scaffolded config.json's `database` block describe the engine actually chosen.

    The seed's config declares `docker-postgres`, so before W5.1 every scaffolded app shipped a
    provisioning block for an engine it does not use. That was invisible while nothing validated
    config.json; now that something does, it would be a contradiction between two files a user owns.

    An engine that provisions nothing (h2local, in-memory) gets NO database block at all rather than
    an invented one -- config.schema.json makes the section optional for exactly this case, and
    fifteen working corpus apps have always omitted it.
    """
    if not config_path.exists():
        return
    config = json.loads(config_path.read_text(encoding="utf-8"))

    if not engine["server"]:
        config.pop("database", None)
    else:
        config["database"] = {
            "provider": engine["provider"],
            "host": host or "localhost",
            "port": int(port) if port else engine["port"],
            "database": database_name,
            "username": username if username is not None else "postgres" if engine["key"] == "postgres" else "sa",
            "password": password if password is not None else "",
            # `preserve`, not `reset`: a scaffold that wipes a server database the user pointed it at
            # would be the single worst first-run surprise this command could produce.
            "resetMode": "preserve",
        }

    trial = config.get("trialDefaults")
    if isinstance(trial, dict):
        trial["databaseMode"] = engine["provider"]

    config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")


def run_generate(args: argparse.Namespace) -> None:
    root = repo_root()
    generator_root = root / "NPDevGenerator"
    wrapper = gradle_wrapper(generator_root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")
    model = Path(args.model).expanduser().resolve()
    config = Path(args.config).expanduser().resolve()
    final_app_out = Path(args.output).expanduser().resolve()
    artifact_out = final_app_out.parent / "ArtifactNP"
    schema_realization = artifact_out / "schema-realization"
    runtime_host = root / "NPDevRuntimeHost"
    if not runtime_host.exists():
        raise CliError(f"NPDevRuntimeHost not found: {runtime_host}")

    with contextlib.ExitStack() as stack:
        db_definition = config.parent / "db.definition.json"
        if not db_definition.exists():
            if getattr(args, "require_db_definition", False):
                raise CliError(f"db.definition.json not found alongside config: {db_definition}")
            temp_dir = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="npdev-default-db-")))
            db_definition = temp_dir / "db.definition.json"
            db_definition.write_text(json.dumps(DEFAULT_DB_DEFINITION, indent=2) + "\n", encoding="utf-8")
            print(
                f"npdev: db.definition.json not found alongside config ({config.parent}) -- "
                f"using default InMemory database definition. Pass --require-db-definition to "
                f"disable this default and fail instead.",
                file=sys.stderr,
            )

        generator_args = [
            "--config", str(config),
            "--model", str(model),
            "--out", str(artifact_out),
            "--dbDefinitionPath", str(db_definition),
            "--schemaRealizationDir", str(schema_realization),
            "--runtimeHostTemplate", str(runtime_host),
            "--finalAppOut", str(final_app_out),
            "--assembleFinalApp",
            "--clean",
            "--cleanFinalApp",
        ]
        args_str = " ".join(f'"{item}"' if " " in item else item for item in generator_args)
        command = [str(wrapper), *gradle_project_cache_args("generator"), ":generator:run",
                   "--no-daemon", "--console=plain", f"--args={args_str}"]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            command = ["cmd.exe", "/c"] + command
        print("[1/4] compiling + resolving the generator (first run downloads Gradle "
              "dependencies -- can take several minutes) ...", file=sys.stderr)
        _run_with_phase_narration(command, generator_root, [
            ("DB definition:", "[2/4] compiling the model ..."),
            ("Generation OK.", "[3/4] emitting the application ..."),
            ("Final app assembly OK.", "[4/4] assembling the final app ..."),
        ])

    # W3 (FIRST_IMPRESSION_PLAN.md I3): `generate app` used to end with assembly diagnostics and
    # nothing else -- a newcomer had a directory full of files and no stated next step. Print one
    # to stdout (machine-readable output from THIS command is diagnostics/paths already printed
    # above; this closing block is human-facing, matching README's own documented sequence exactly
    # so the two never drift apart).
    jar_name = "FinalExec-0.1.0.jar"
    print(
        f"\n"
        f"Your app is ready:  {final_app_out}\n"
        f"\n"
        f"  1. Build:   cd {final_app_out} && ./gradlew bootJar\n"
        f"  2. Run:     java -jar build/libs/{jar_name} --spring.profiles.active=dev\n"
        f"  3. Open:    http://localhost:8080\n"
        f"  4. Log in:  the key in SUPER_USER_KEY.txt (created in this folder on first start)\n"
        f"\n"
        f"  Docker instead? cp .env.example .env && docker compose up\n"
    )


# ---------------------------------------------------------------------------
# Move 10 D1 (LC-D1): `npdev run app` -- generate + build + boot + health-check, one command,
# structured JSON output, five named failure classes, guaranteed teardown.
# ---------------------------------------------------------------------------

# Process this invocation started and is responsible for tearing down, reachable from the signal
# handler below (which can only take (signum, frame) -- no closure access to a local variable).
_RUN_APP_CHILD_PROCESS: subprocess.Popen | None = None


def _run_app_signal_handler(signum, _frame) -> None:  # noqa: ANN001 - signal handler signature
    if _RUN_APP_CHILD_PROCESS is not None and _RUN_APP_CHILD_PROCESS.poll() is None:
        _RUN_APP_CHILD_PROCESS.kill()
        _RUN_APP_CHILD_PROCESS.wait(timeout=10)
    raise SystemExit(130 if signum == signal.SIGINT else 143)


def _diag(phase: str, code: str, message: str, suggested_fix: str | None = None,
          help_key: str | None = None) -> dict:
    """One diagnostic vocabulary across validate/build/boot (D1's own DoD line): same shape as
    ValidationDiagnostic (code/message/suggestedFix/helpKey), so an agent needs no second parser."""
    return {
        "phase": phase,
        "code": code,
        "message": message,
        "suggestedFix": suggested_fix,
        "helpKey": help_key,
    }


def _write_run_app_progress(final_app_out: Path, phase: str) -> None:
    """REG-111 fix (Fast Lane plan item 4b, 2026-08-01): `npdev run app`'s own phase field
    (GENERATE/BUILD/BOOT/READY) was already computed for the final JSON result, but only ever
    visible once the whole call returns -- an agent or human waiting on a long generate/build/boot
    cycle had no way to tell "still working normally" from "silently stuck" without reaching past
    the tool into raw filesystem/process state. Writes that same field to a small sidecar file on
    every transition -- something a caller can tail -f or poll cheaply -- reusing the shape that
    already exists (result["phase"]) rather than inventing a new one. Best-effort: a failure to
    write progress must never fail the run itself.
    """
    try:
        final_app_out.mkdir(parents=True, exist_ok=True)
        sidecar = final_app_out / "npdev-run-app-progress.json"
        sidecar.write_text(
            json.dumps({
                "schemaVersion": "npdev-run-app-progress.v1",
                "phase": phase,
                "updatedAt": datetime.now(timezone.utc).isoformat(),
                "pid": os.getpid(),
            }, indent=2),
            encoding="utf-8",
        )
    except OSError:
        pass


def _is_port_in_use(port: int) -> bool:
    import socket

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.5)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def _classify_build_failure(output: str) -> dict | None:
    # STALE_CACHE: NPDevKernel/adapters/runtime-validation's verifyNpdevRuntimeHostLibs task
    # (build.gradle) refuses to build against a runtimehost-libs directory with no manifest --
    # exactly the error this session hit directly regenerating WmsOffice after -SkipLibs.
    if "Missing NPDev RuntimeHost libs manifest" in output:
        return _diag(
            "BUILD", "STALE_CACHE",
            "The runtimehost-libs cache this build root points at has no manifest (missing or "
            "wiped by a regenerate).",
            suggested_fix="Run scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars, or "
                           "scripts/appgen/Rebuild-And-Restage.ps1, before retrying.",
            help_key="runtimehost-libs-dir-mismatch",
        )
    return None


def _classify_boot_failure(log_text: str) -> dict | None:
    # SCHEMA_IMPACT_UNACKNOWLEDGED: SchemaLifecycleExecutor's boot-time refusal for a destructive
    # schema change with no matching acknowledgment token (LNCH-1 Phase 4).
    if "requiring an explicit, itemized acknowledgment" in log_text:
        return _diag(
            "BOOT", "SCHEMA_IMPACT_UNACKNOWLEDGED",
            "The model change includes destructive schema item(s) with no matching acknowledgment "
            "token.",
            suggested_fix="See docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes -- set the "
                           "generated manifest's destructiveAcknowledgment to the token the boot log "
                           "names, or submit it via the ControlPanel schema-migration screen.",
            help_key="schema-impact-unacknowledged",
        )
    # MIGRATION_CLAIM_HELD: MigrationClaimStore refuses a concurrent migration (REG-7.3/B4).
    if "Another NPDev instance is currently migrating this database" in log_text:
        return _diag(
            "BOOT", "MIGRATION_CLAIM_HELD",
            "Another NPDev instance already holds the migration claim on this database.",
            suggested_fix="Wait for it to finish, or if it crashed mid-migration, clear the stale "
                           "claim via POST /api/admin/schema-migration/clear-claim (SUPERUSER) or the "
                           "ControlPanel schema-migration screen.",
            help_key="migration-claim-held",
        )
    if "Web server failed to start" in log_text and "port" in log_text.lower():
        return _diag(
            "BOOT", "PORT_IN_USE",
            "The target port was already bound by another process when the JVM tried to start "
            "(race: the pre-flight check passed but something else grabbed it first).",
            suggested_fix="Pick a different --port, or stop whatever is already listening.",
            help_key="port-in-use",
        )
    return None


def _log_excerpt(text: str, around: str | None = None, window: int = 40) -> str:
    lines = text.splitlines()
    if around:
        for i, line in enumerate(lines):
            if around in line:
                start = max(0, i - window // 2)
                return "\n".join(lines[start:i + window // 2])
    return "\n".join(lines[-window:])


def _infer_run_app_paths(args: argparse.Namespace) -> dict | None:
    """I3: `npdev init my-app && cd my-app && npdev run app` must work with no flags -- the model
    IS the app (see docs/YOUR_FIRST_APP.md), so the directory holding it is enough context to find
    it and its config, and to name a sensible place to put the generated code. Mutates args in
    place; returns a diagnostic dict (GENERATE-phase, same shape run_app's own _diag produces) if
    inference cannot find something explicit flags would have supplied, or None on success.
    Never overrides an explicitly-passed flag -- CWD-inference is a default, not an override."""
    cwd = Path.cwd()
    if not args.model:
        candidate = cwd / "model.json"
        if not candidate.exists():
            return _diag(
                "GENERATE", "MODEL_NOT_FOUND",
                f"--model not given and no model.json in the current directory ({cwd}).",
                suggested_fix="Pass --model explicitly, or run this from a directory `npdev init` "
                              "created (it scaffolds model.json alongside config.json).",
            )
        args.model = str(candidate)
    if not args.config:
        candidate = cwd / "config.json"
        if not candidate.exists():
            return _diag(
                "GENERATE", "CONFIG_NOT_FOUND",
                f"--config not given and no config.json in the current directory ({cwd}).",
                suggested_fix="Pass --config explicitly, or run this from a directory `npdev init` created.",
            )
        args.config = str(candidate)
    if not args.output:
        # Sibling directory, not a subdirectory: generated code is disposable and should not sit
        # inside the same folder as the model it was generated from (docs/YOUR_FIRST_APP.md's own
        # manual convention -- my-library -> ../my-library-app -- so this matches what anyone who
        # read that page already expects, rather than inventing a second convention here).
        args.output = str(cwd.parent / f"{cwd.name}-app")
    return None


def run_app(args: argparse.Namespace) -> dict:
    """Move 10 D1 (LC-D1, `npdev_build_and_run`): GENERATE -> BUILD -> BOOT -> READY, one command,
    structured output, five named failure classes, bounded with guaranteed teardown."""
    global _RUN_APP_CHILD_PROCESS
    import time
    import urllib.error
    import urllib.request

    result: dict = {"phase": "GENERATE", "ok": False, "diagnostics": [], "baseUrl": None, "logExcerpt": None}
    inference_failure = _infer_run_app_paths(args)
    if inference_failure is not None:
        result["diagnostics"].append(inference_failure)
        return result
    deadline = time.monotonic() + args.timeout
    root = repo_root()
    final_app_out = Path(args.output).expanduser().resolve()
    boot_proc: subprocess.Popen | None = None
    _write_run_app_progress(final_app_out, result["phase"])

    old_handlers = {}
    for sig in (getattr(signal, "SIGINT", None), getattr(signal, "SIGTERM", None)):
        if sig is not None:
            try:
                old_handlers[sig] = signal.signal(sig, _run_app_signal_handler)
            except (ValueError, OSError):
                pass  # not the main thread / unsupported on this platform -- best-effort only

    try:
        # --- Optional C2 fast path: METADATA_ONLY change against an explicit baseline -----------
        if args.baseline_model and final_app_out.exists():
            classification = _classify_model_change(root, Path(args.baseline_model).expanduser().resolve(),
                                                      Path(args.model).expanduser().resolve(), deadline)
            if classification == "METADATA_ONLY":
                fast_ok, fast_message = _metadata_only_fast_path(
                    root, Path(args.model).expanduser().resolve(), final_app_out, deadline)
                if fast_ok:
                    result["phase"] = "BOOT"
                    _write_run_app_progress(final_app_out, result["phase"])
                else:
                    result["diagnostics"].append(_diag(
                        "GENERATE", "METADATA_ONLY_FAST_PATH_FAILED", fast_message))
                    return result

        if result["phase"] == "GENERATE":
            # --- PORT_IN_USE pre-flight (before spending any time generating/building) ----------
            if not args.keep_running and _is_port_in_use(args.port):
                result["diagnostics"].append(_diag(
                    "BOOT", "PORT_IN_USE",
                    f"Port {args.port} is already in use before this run even started.",
                    suggested_fix="Pick a different --port, or stop whatever is already listening.",
                    help_key="port-in-use",
                ))
                return result

            # --- GENERATE --------------------------------------------------------------------
            gen_ok, gen_output = _generate_phase_captured(root, args, final_app_out, deadline)
            if not gen_ok:
                result["diagnostics"].append(_diag(
                    "GENERATE", "GENERATE_FAILED", "The generator did not produce a final app.",
                    suggested_fix="See logExcerpt for the generator's own error.",
                ))
                result["logExcerpt"] = _log_excerpt(gen_output)
                return result

            # --- BUILD ---------------------------------------------------------------------
            result["phase"] = "BUILD"
            _write_run_app_progress(final_app_out, result["phase"])
            build_ok, build_output, jar_path = _build_phase(final_app_out, deadline)
            if not build_ok:
                classified = _classify_build_failure(build_output)
                result["diagnostics"].append(classified or _diag(
                    "BUILD", "BUILD_FAILED", "gradlew clean build failed.",
                    suggested_fix="See logExcerpt for the build's own error.",
                ))
                result["logExcerpt"] = _log_excerpt(build_output)
                return result
            if jar_path is None:
                result["diagnostics"].append(_diag(
                    "BUILD", "JAR_NOT_FOUND",
                    "Build reported success but no runnable FinalExec-*.jar was found under build/libs.",
                    suggested_fix="Check for a bootJar/assemble task misconfiguration.",
                ))
                result["logExcerpt"] = _log_excerpt(build_output)
                return result

        else:
            jar_path = _find_jar(final_app_out)
            if jar_path is None:
                result["phase"] = "BUILD"
                _write_run_app_progress(final_app_out, result["phase"])
                result["diagnostics"].append(_diag(
                    "BUILD", "JAR_NOT_FOUND",
                    "METADATA_ONLY fast path expected a previously-built jar but found none.",
                ))
                return result

        # --- BOOT ------------------------------------------------------------------------
        result["phase"] = "BOOT"
        _write_run_app_progress(final_app_out, result["phase"])
        base_url = f"http://127.0.0.1:{args.port}"
        log_path = final_app_out / "npdev-run-app-boot.log"
        with open(log_path, "w", encoding="utf-8") as log_file:
            boot_proc = subprocess.Popen(
                ["java", "-jar", str(jar_path), f"--server.port={args.port}",
                 f"--spring.profiles.active={args.profile}"],
                cwd=str(final_app_out), stdout=log_file, stderr=subprocess.STDOUT,
            )
        _RUN_APP_CHILD_PROCESS = boot_proc

        healthy = False
        while time.monotonic() < deadline:
            if boot_proc.poll() is not None:
                break  # JVM exited on its own -- definitely not healthy
            try:
                request = urllib.request.Request(f"{base_url}/actuator/health")
                with urllib.request.urlopen(request, timeout=3) as response:
                    body = json.loads(response.read().decode("utf-8"))
                    if body.get("status") == "UP":
                        healthy = True
                        break
            except (urllib.error.URLError, OSError, json.JSONDecodeError):
                pass
            time.sleep(2)

        log_text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        if not healthy:
            classified = _classify_boot_failure(log_text)
            result["diagnostics"].append(classified or _diag(
                "BOOT", "BOOT_TIMEOUT" if boot_proc.poll() is None else "BOOT_FAILED",
                "The app did not report /actuator/health status UP within the time budget."
                if boot_proc.poll() is None else
                f"The JVM exited on its own (code {boot_proc.returncode}) before reporting healthy.",
                suggested_fix="See logExcerpt for the boot log around the first error.",
            ))
            result["logExcerpt"] = _log_excerpt(log_text, around="ERROR") or _log_excerpt(log_text)
            return result

        # --- READY -------------------------------------------------------------------------
        result["phase"] = "READY"
        result["ok"] = True
        result["baseUrl"] = base_url
        _write_run_app_progress(final_app_out, result["phase"])
        _RUN_APP_CHILD_PROCESS = None  # READY: intentionally leave it running, not this run's to kill
        return result
    finally:
        for sig, handler in old_handlers.items():
            try:
                signal.signal(sig, handler)
            except (ValueError, OSError):
                pass
        # Guaranteed teardown: any path that returned before READY (or the boot loop's own
        # `break`/timeout falling through) must not leave an orphaned JVM behind.
        if boot_proc is not None and _RUN_APP_CHILD_PROCESS is boot_proc and boot_proc.poll() is None:
            boot_proc.kill()
            try:
                boot_proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
        _RUN_APP_CHILD_PROCESS = None


class _DeadlineExceeded(Exception):
    """Raised by _run_bounded when the overall --timeout budget is already spent."""


def _run_bounded(command: list[str], cwd, deadline: float, **kwargs) -> subprocess.CompletedProcess:
    """subprocess.run with its timeout derived from the run's own overall deadline, so GENERATE/
    BUILD/classify subprocesses can never overrun --timeout even though they're not the boot health
    loop -- D1's own DoD line ("Bounded: a timeout, a hard kill, and a guaranteed teardown") applies
    to the whole pipeline, not just the final health-poll."""
    import time

    remaining = deadline - time.monotonic()
    if remaining <= 1:
        raise _DeadlineExceeded()
    try:
        return subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                               timeout=remaining, **kwargs)
    except subprocess.TimeoutExpired as exc:
        raise _DeadlineExceeded() from exc


def _generate_phase_captured(
        root: Path, args: argparse.Namespace, final_app_out: Path, deadline: float) -> tuple[bool, str]:
    """Same command shape as run_generate(), but captures output instead of streaming/raising so a
    failure becomes a GENERATE-phase diagnostic instead of an uncaught CalledProcessError."""
    generator_root = root / "NPDevGenerator"
    wrapper = gradle_wrapper(generator_root)
    if not wrapper.exists():
        return False, f"Gradle wrapper not found: {wrapper}"
    model = Path(args.model).expanduser().resolve()
    config = Path(args.config).expanduser().resolve()
    artifact_out = final_app_out.parent / "ArtifactNP"
    schema_realization = artifact_out / "schema-realization"
    runtime_host = root / "NPDevRuntimeHost"
    if not runtime_host.exists():
        return False, f"NPDevRuntimeHost not found: {runtime_host}"

    with contextlib.ExitStack() as stack:
        db_definition = config.parent / "db.definition.json"
        if not db_definition.exists():
            if getattr(args, "require_db_definition", False):
                return False, f"db.definition.json not found alongside config: {db_definition}"
            temp_dir = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="npdev-default-db-")))
            db_definition = temp_dir / "db.definition.json"
            db_definition.write_text(json.dumps(DEFAULT_DB_DEFINITION, indent=2) + "\n", encoding="utf-8")

        generator_args = [
            "--config", str(config),
            "--model", str(model),
            "--out", str(artifact_out),
            "--dbDefinitionPath", str(db_definition),
            "--schemaRealizationDir", str(schema_realization),
            "--runtimeHostTemplate", str(runtime_host),
            "--finalAppOut", str(final_app_out),
            "--assembleFinalApp",
            "--clean",
            "--cleanFinalApp",
        ]
        args_str = " ".join(f'"{item}"' if " " in item else item for item in generator_args)
        command = [str(wrapper), *gradle_project_cache_args("generator"), ":generator:run",
                   "--no-daemon", "--console=plain", f"--args={args_str}"]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            command = ["cmd.exe", "/c"] + command
        try:
            completed = _run_bounded(command, generator_root, deadline)
        except _DeadlineExceeded:
            return False, "GENERATE exceeded the overall --timeout budget."
        output = (completed.stdout or "") + (completed.stderr or "")
        return completed.returncode == 0, output


def _find_jar(app_root: Path) -> Path | None:
    for candidate in app_root.rglob("FinalExec-*.jar"):
        if "build" + os.sep + "libs" in str(candidate) and not candidate.name.endswith("-plain.jar"):
            return candidate
    return None


def _discover_runtimehost_source_jars(root: Path, external_build_root: Path) -> dict[str, Path]:
    """Mirrors sync-runtimehost-libs.ps1's own discovery exactly: build/libs/*.jar under
    NPDevContract/NPDevGenerator/NPDevKernel, PLUS whatever the external npdevBuildRoot's own
    gradle/ tree holds, skipping npdev-migrations-* (those are staged separately), keeping the
    newest by mtime when a name collides between the two locations."""
    by_name: dict[str, Path] = {}

    def consider(jar: Path) -> None:
        if jar.name.startswith("npdev-migrations-"):
            return
        existing = by_name.get(jar.name)
        if existing is None or jar.stat().st_mtime > existing.stat().st_mtime:
            by_name[jar.name] = jar

    for source_root_name in ("NPDevContract", "NPDevGenerator", "NPDevKernel"):
        source_root = root / source_root_name
        if not source_root.is_dir():
            continue
        for jar in source_root.rglob("*.jar"):
            if "/build/libs/" in jar.as_posix():
                consider(jar)

    external_gradle_root = external_build_root / "gradle"
    if external_gradle_root.is_dir():
        for jar in external_gradle_root.rglob("*.jar"):
            if "/libs/" in jar.as_posix():
                consider(jar)

    return by_name


class _SetupOutput:
    """I2: narration goes wherever the human already saw it (stdout, byte-identical) unless
    --json is passed, in which case narration moves to stderr and JSON Lines events take stdout
    instead -- the same split `dev_loop.py`'s own `Output` class already established for `npdev
    dev`, reused rather than inventing a second convention (§9 prohibition)."""

    def __init__(self, json_mode: bool):
        self.json_mode = json_mode
        self.events: list[dict] = []

    def narrate(self, text: str) -> None:
        print(text, file=sys.stderr if self.json_mode else sys.stdout)

    def event(self, phase: str, status: str, **extra) -> None:
        if not self.json_mode:
            return
        payload = {"schemaVersion": "npdev-cli-event.v1", "phase": phase, "status": status, **extra}
        self.events.append(payload)
        print(json.dumps(payload), flush=True)

    def run(self, command: list, cwd: Path) -> None:
        """Run a subprocess whose own stdout must not leak onto OUR stdout in --json mode (Gradle
        output and build_knowledge.py's own JSON both inherit stdio by default, which would land
        raw text between JSON Lines events -- the exact violation of "nothing but JSON on stdout"
        this class exists to prevent). In human mode, subprocess.run is left completely untouched
        (inherited, streamed) so the human output stays byte- and timing-identical to before."""
        if not self.json_mode:
            subprocess.run(command, cwd=cwd, check=True)
            return
        completed = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
        if completed.stdout:
            self.narrate(completed.stdout.rstrip("\n"))
        if completed.stderr:
            self.narrate(completed.stderr.rstrip("\n"))
        if completed.returncode != 0:
            raise subprocess.CalledProcessError(completed.returncode, command)


# I5: prebuilt-jars Release asset -- the repo builds this from a tag push (see
# .github/workflows/publish-runtimehost-libs.yml); `npdev setup` tries it first and only falls
# back to a local build when there is nothing to download or it cannot be trusted (§9: the
# fallback is not optional -- a fork, an offline machine, or an untagged commit must still work).
_RUNTIMEHOST_LIBS_RELEASE_REPO = "MarceloGiazzon/NPDevGeneral"


def _current_git_tag(root: Path) -> str | None:
    """The tag exactly at HEAD, or None -- an untagged commit (any ordinary feature-branch
    checkout) has nothing published to download, so this is the gate that sends `setup` to the
    local-build path without ever attempting a network call."""
    try:
        completed = subprocess.run(
            ["git", "describe", "--tags", "--exact-match", "HEAD"],
            cwd=root, capture_output=True, text=True, timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    tag = completed.stdout.strip()
    return tag if completed.returncode == 0 and tag else None


def _try_download_runtimehost_libs(tag: str, libs_dir: Path, out: "_SetupOutput",
                                    base_url: str | None = None) -> bool:
    """Best-effort download of `runtimehost-libs-<tag>.zip` (+ sibling `SHA256SUMS`) from this
    tag's GitHub Release, checksum-verified before anything is extracted. Returns False -- never
    raises -- on ANY failure (no release for this tag, no matching asset, network error, checksum
    mismatch, a corrupt zip): the caller always still has the local-build fallback, which is the
    one thing §9 forbids removing. `base_url` overrides the real GitHub Release URL -- only ever
    passed in a test, to prove the checksum-mismatch/corruption path against a local server."""
    import urllib.error
    import urllib.request
    import zipfile

    base = base_url or f"https://github.com/{_RUNTIMEHOST_LIBS_RELEASE_REPO}/releases/download/{tag}"
    zip_name = f"runtimehost-libs-{tag}.zip"

    with tempfile.TemporaryDirectory(prefix="npdev-setup-download-") as tmp:
        zip_path = Path(tmp) / zip_name
        try:
            with urllib.request.urlopen(f"{base}/SHA256SUMS", timeout=30) as resp:
                sums_text = resp.read().decode("utf-8", errors="replace")
        except (urllib.error.URLError, OSError, ValueError):
            out.narrate(f"npdev setup: no published SHA256SUMS for {tag} -- building locally instead.")
            return False

        expected_hash = None
        for line in sums_text.splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].lstrip("*") == zip_name:
                expected_hash = parts[0].lower()
                break
        if expected_hash is None:
            out.narrate(f"npdev setup: SHA256SUMS has no entry for {zip_name} -- building locally instead.")
            return False

        try:
            urllib.request.urlretrieve(f"{base}/{zip_name}", zip_path)
        except (urllib.error.URLError, OSError, ValueError):
            out.narrate(f"npdev setup: could not download {zip_name} -- building locally instead.")
            return False

        actual_hash = hashlib.sha256(zip_path.read_bytes()).hexdigest()
        if actual_hash != expected_hash:
            out.narrate(
                f"npdev setup: checksum mismatch for {zip_name} (expected {expected_hash[:12]}..., "
                f"got {actual_hash[:12]}...) -- building locally instead."
            )
            return False

        try:
            with zipfile.ZipFile(zip_path) as zf:
                zf.extractall(libs_dir)
        except (zipfile.BadZipFile, OSError) as exc:
            out.narrate(f"npdev setup: could not extract {zip_name} ({exc}) -- building locally instead.")
            return False

    if not (libs_dir / "runtimehost-libs-manifest.json").exists():
        out.narrate(f"npdev setup: {zip_name} had no manifest inside -- building locally instead.")
        return False

    out.narrate(f"npdev setup: downloaded and verified {zip_name} (sha256 {expected_hash[:12]}...) -> {libs_dir}")
    return True


_CI_VALIDATION_REPO = "MarceloGiazzon/NPDevGeneral"
_CI_VALIDATION_WORKFLOW_FILE = "npdev-ci-validation.yml"


def _check_remote_ci_status(branch: str = "main", lookback: int = 20) -> dict:
    """CI_RED_PLAN.md I3: the mechanical fix for "a scheduled gate failed for twelve days and
    nobody was looking" -- `NPDev CI Validation` failed on EVERY run from 2026-07-25 through
    2026-08-05 (12 days, including nightly scheduled runs on `main`) while local T2 stayed green
    throughout, because nothing connected the two. Queries the live GitHub Actions run history for
    `branch` so `verify --tier T2/T3` can never again report a green local run while the same
    branch's remote CI is red. Best-effort: any network failure marks `checked: false` rather than
    failing the whole `verify` call -- an offline `verify` is still a real local claim, just not
    one that could cross-check the remote signal that day.
    """
    import urllib.error
    import urllib.request

    url = (
        f"https://api.github.com/repos/{_CI_VALIDATION_REPO}/actions/workflows/"
        f"{_CI_VALIDATION_WORKFLOW_FILE}/runs?branch={branch}&per_page={lookback}"
    )
    request = urllib.request.Request(url, headers={"User-Agent": "npdev-cli", "Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError):
        return {"checked": False, "branch": branch}

    runs = data.get("workflow_runs", [])
    if not runs:
        return {"checked": False, "branch": branch}

    latest = runs[0]
    conclusion = latest.get("conclusion")
    failing_since = None
    if conclusion != "success":
        failing_since = latest.get("created_at")
        for run in runs[1:]:
            if run.get("conclusion") == "success":
                break
            failing_since = run.get("created_at")

    return {
        "checked": True,
        "branch": branch,
        "latestConclusion": conclusion,
        "latestRunUrl": latest.get("html_url"),
        "failingSince": failing_since,
    }


def run_setup(args: argparse.Namespace) -> int:
    """I4: a portable port of scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars --
    that script is 265 lines with zero Windows-specific constructs (measured); keeping `pwsh` as a
    hard prerequisite on macOS/Linux for "mkdir, run gradle twice, copy some jars" is an accident
    of history, not a real Windows dependency. The .ps1 stays exactly as-is for maintainers --
    this does not call it and it does not call this, per the plan's own explicit prohibition.

    Step 2 (the AI knowledge index) matters more than it looks: NPDevMcp/server.py already errors
    correctly when the index is missing ("index not built yet -- run: python scripts/ai/
    build_knowledge.py") -- that good error is currently the user's first hint the step exists.
    Folding it in here means they never have to see it.

    Phase 0 I2: --json wraps the same two phases (jar build, knowledge index) with started/done
    events carrying elapsed seconds, ending with one npdev-cli-result.v1 object -- setup is long
    (~573s), so the Manager needs progress, not just a final verdict."""
    import time as _time

    out = _SetupOutput(getattr(args, "json", False))
    root = repo_root()
    kernel_root = root / "NPDevKernel"
    generator_root = root / "NPDevGenerator"
    kernel_wrapper = gradle_wrapper(kernel_root)
    generator_wrapper = gradle_wrapper(generator_root)
    if not kernel_wrapper.exists():
        raise CliError(f"Kernel Gradle wrapper not found: {kernel_wrapper}")
    if not generator_wrapper.exists():
        raise CliError(f"Generator Gradle wrapper not found: {generator_wrapper}")

    build_root = _ai_build_root()
    os.environ["NPDEV_BUILD_ROOT"] = str(build_root)
    libs_dir = build_root / "runtimehost-libs"
    libs_dir.mkdir(parents=True, exist_ok=True)

    jars_started = _time.monotonic()
    out.event("jars", "started")

    # I5: try a prebuilt Release asset before spending the ~9 of setup's ~10 minutes compiling --
    # only possible on a tagged commit (nothing published for a feature branch), and skippable
    # with --build-local. Every failure mode inside _try_download_runtimehost_libs falls through
    # to the exact same local build below; §9 forbids a download-only setup.
    jars_source = "build"
    if getattr(args, "build_local", False):
        out.narrate("npdev setup: --build-local passed -- skipping the download, building locally.")
    else:
        tag = _current_git_tag(root)
        if tag and _try_download_runtimehost_libs(tag, libs_dir, out):
            jars_source = "download"

    if jars_source == "download":
        pass  # _try_download_runtimehost_libs already staged the jars + manifest into libs_dir
    else:
        out.narrate(f"npdev setup: [1/3] building Kernel/Contract runtime jars (npdevBuildRoot={build_root})")
        kernel_command = [str(kernel_wrapper), "jar", f"-PnpdevBuildRoot={build_root}",
                           *gradle_project_cache_args("kernel"), "--no-daemon", "--console=plain"]
        if os.name == "nt" and kernel_wrapper.suffix.lower() == ".bat":
            kernel_command = ["cmd.exe", "/c"] + kernel_command
        out.run(kernel_command, kernel_root)

        out.narrate("npdev setup: [1/3] building Generator and CLI jars")
        generator_command = [str(generator_wrapper), ":generator:jar", ":tools:npdev-cli:jar",
                              f"-PnpdevBuildRoot={build_root}", *gradle_project_cache_args("generator"),
                              "--no-daemon", "--console=plain"]
        if os.name == "nt" and generator_wrapper.suffix.lower() == ".bat":
            generator_command = ["cmd.exe", "/c"] + generator_command
        out.run(generator_command, generator_root)

        source_jars = _discover_runtimehost_source_jars(root, build_root)
        if not source_jars:
            raise CliError("No RuntimeHost jars were discovered under build/libs after local jar build.")

        copied, up_to_date = [], []
        for name, source in sorted(source_jars.items()):
            target = libs_dir / name
            if target.exists():
                same_size = target.stat().st_size == source.stat().st_size
                same_time = abs(target.stat().st_mtime - source.stat().st_mtime) < 1
                if same_size and same_time:
                    up_to_date.append(name)
                    continue
            shutil.copy2(source, target)
            copied.append(name)

        missing = [name for name in source_jars if not (libs_dir / name).exists()]
        if missing:
            raise CliError(f"RuntimeHost libs sync failed; missing after copy: {', '.join(missing)}")

        manifest = {
            "schemaVersion": "npdev-runtimehost-libs-manifest.v1",
            "generatedAt": _utc_now(),
            "runtimeHostLibsLocation": "external-local-cache",
            "requiredStagedJars": sorted(source_jars),
        }
        (libs_dir / "runtimehost-libs-manifest.json").write_text(
            json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        out.narrate(f"npdev setup: {len(copied)} jar(s) copied, {len(up_to_date)} already current -> {libs_dir}")
    out.event("jars", "done", seconds=round(_time.monotonic() - jars_started, 1), source=jars_source)

    # Clean the source builds' own build/ dirs afterward -- build output does not belong inside
    # the repo tree (this repo's own standing policy); scoped to exactly the three source roots
    # walked above, nothing else.
    for source_root_name in ("NPDevContract", "NPDevGenerator", "NPDevKernel"):
        source_root = root / source_root_name
        if not source_root.is_dir():
            continue
        for build_dir in sorted((p for p in source_root.rglob("build") if p.is_dir()),
                                 key=lambda p: len(str(p)), reverse=True):
            shutil.rmtree(build_dir, ignore_errors=True)

    knowledge_started = _time.monotonic()
    out.event("knowledge-index", "started")
    build_knowledge = root / "scripts" / "ai" / "build_knowledge.py"
    if build_knowledge.exists():
        out.narrate("npdev setup: [2/3] building the AI knowledge index")
        out.run([sys.executable, str(build_knowledge)], root)
        knowledge_note = str(build_root / "npdev-ai")
        out.event("knowledge-index", "done", seconds=round(_time.monotonic() - knowledge_started, 1))
    else:
        out.narrate(f"npdev setup: [2/3] skipped -- not found: {build_knowledge}")
        knowledge_note = "skipped"
        out.event("knowledge-index", "skipped")

    out.narrate(f"npdev setup: [3/3] done")
    # I5: the one new trailing line -- "which path was taken" must be visible in the human output
    # too (§7 DoD), so this is additive-only rather than reworking the block above it.
    out.narrate(f"\nRuntimehost jars: {libs_dir}\nKnowledge index:  {knowledge_note}\nJars source:      {jars_source}")

    if out.json_mode:
        result = {
            "schemaVersion": "npdev-cli-result.v1",
            "command": "setup",
            "ok": True,
            "exitCode": 0,
            "jarsSource": jars_source,
            "events": out.events,
        }
        print(json.dumps(result))
    return 0


def _resolve_java_home_binary(java_home: str) -> Path:
    return Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")


def _check(id_: str, name: str, status: str, *, found: str | None = None,
           expected: str | None = None, detail: str | None = None, fix: str | None = None,
           fixCommand: str | None = None) -> dict:
    """I1: one record shape for every doctor check -- see manager/helpers/checks-contract.json
    (outside this repo) for the frozen id list. `status` drives BOTH renderers: the human summary
    filters to fail/warn (a passing check has always printed nothing, and that must keep being
    true byte-for-byte), while --json includes every record, pass included, so the Manager's Ready
    screen has something to show on a healthy machine."""
    return {
        "id": id_, "name": name, "status": status,
        "found": found, "expected": expected,
        "detail": detail, "fix": fix, "fixCommand": fixCommand,
    }


def _storage_capability_matrix() -> tuple[str | None, str]:
    """The storage capability matrix, READ OUT OF THE DIALECTS by running them.

    storage/PLAN.md S3 requires this in `npdev doctor` and requires it *generated from the code,
    never maintained as a doc that drifts*. A table of which engine supports what, kept by hand in
    this file, is the twin-pair defect family in its purest form: the day it disagrees with
    SqlDialect.capabilities(), either a valid model gets rejected or an impossible one gets accepted
    -- and the table looks authoritative either way.

    So there is no table here. This runs SqlDialects' own main against the staged kernel jar. When
    the jars are not staged it returns None and a REASON, and doctor prints the reason. It never
    falls back to a remembered copy, because a remembered copy is the thing being avoided.

    Returns (matrix_text_or_None, reason_when_None).
    """
    libs = _default_runtimehost_libs_dir()
    if libs is None:
        return None, "jars not staged -- run `npdev setup`"
    # The staged jar is `kernel-<version>.jar` (the Gradle project is :kernel, so the artifact is
    # not prefixed) and it needs :core and :dsl alongside it. A wildcard classpath over the staged
    # libs directory is both correct and immune to the artifact ever being renamed -- which is the
    # sort of detail a hardcoded jar name gets wrong silently, reporting "unavailable" on a machine
    # that is in fact perfectly set up.
    if not sorted(Path(libs).glob("kernel-*.jar")):
        return None, f"no kernel jar in {libs} -- run `npdev setup`"
    classpath = str(Path(libs) / "*")
    java_home = os.environ.get("JAVA_HOME")
    java_bin = _resolve_java_home_binary(java_home) if java_home else None
    if java_bin is None or not java_bin.exists():
        path_java = shutil.which("java")
        java_bin = Path(path_java) if path_java else None
    if java_bin is None:
        return None, "no Java found -- see the java-present check above"
    try:
        completed = subprocess.run(
            [str(java_bin), "-cp", classpath, "com.npdev.kernel.storage.sql.SqlDialects"],
            capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        return None, f"could not run the kernel jar ({exc})"
    if completed.returncode != 0:
        return None, f"the kernel jar exited {completed.returncode}: {completed.stderr.strip()[:200]}"
    return completed.stdout, ""


def _summarize_capability_matrix(matrix: str) -> list[str]:
    """One line per engine: its name, then only what it CANNOT do.

    Listing absences rather than the whole grid is the useful shape for doctor, whose stated design
    goal is one screen with no scrolling. What a reader needs here is "is anything missing on the
    engine I chose"; an engine that supports everything should say so in one short line rather than
    ten identical `yes` rows. The full grid is `npdev capabilities`."""
    rows = [line for line in matrix.splitlines() if line.strip() and not line.startswith("-")]
    if len(rows) < 2:
        return ["(matrix produced no rows)"]
    engines = rows[0].split()[1:]
    missing: dict[str, list[str]] = {engine: [] for engine in engines}
    for row in rows[1:]:
        parts = row.split()
        if len(parts) != len(engines) + 1:
            continue
        for engine, verdict in zip(engines, parts[1:]):
            if verdict != "yes":
                missing[engine].append(parts[0])
    return [
        f"{engine}: supports everything NPDev asks for" if not missing[engine]
        else f"{engine}: cannot do {', '.join(missing[engine])}"
        for engine in engines
    ]


def run_capabilities(args: argparse.Namespace) -> int:
    """Print the full storage capability grid.

    Nothing here knows which engine supports what -- it runs the dialects and prints their answer.
    See _storage_capability_matrix() for why that indirection is the point rather than an
    inconvenience."""
    matrix, reason = _storage_capability_matrix()
    if matrix is None:
        if getattr(args, "json", False):
            print(json.dumps({
                "schemaVersion": "npdev-cli-result.v1",
                "command": "capabilities",
                "ok": False,
                "exitCode": 1,
                "detail": reason,
            }, indent=2))
        else:
            print(f"npdev capabilities: unavailable -- {reason}")
        return 1
    if getattr(args, "json", False):
        # Re-run for the machine-readable form rather than parsing the human grid: parsing your own
        # output back is how a renderer and its data quietly disagree.
        libs = _default_runtimehost_libs_dir()
        java_home = os.environ.get("JAVA_HOME")
        java_bin = _resolve_java_home_binary(java_home) if java_home else None
        if java_bin is None or not java_bin.exists():
            path_java = shutil.which("java")
            java_bin = Path(path_java) if path_java else None
        completed = subprocess.run(
            [str(java_bin), "-cp", str(Path(libs) / "*"),
             "com.npdev.kernel.storage.sql.SqlDialects", "--json"],
            capture_output=True, text=True, timeout=60, check=False)
        print(completed.stdout, end="")
        return 0 if completed.returncode == 0 else 1
    print("npdev capabilities -- what each storage engine can do")
    print("=" * 60)
    print(matrix, end="")
    print()
    print("Generated from the dialects themselves (SqlDialects.capabilityMatrix), so it cannot")
    print("disagree with what the generator actually refuses. A model needing a capability its")
    print("engine lacks is refused at GENERATION time, naming the model element.")
    return 0


def _find_db_definition(explicit: str | None) -> Path | None:
    """The app's db.definition.json, from an explicit path or the current directory.

    Doctor has always been a machine check with no app in scope. The database checks below only make
    sense for a specific app, so they run when one is FINDABLE and are skipped -- visibly, as a
    recorded pass with a stated reason -- when it is not. A machine with no NPDev app on it is not a
    broken machine.
    """
    if explicit:
        candidate = Path(explicit).expanduser().resolve()
        if candidate.is_dir():
            candidate = candidate / "db.definition.json"
        return candidate if candidate.is_file() else None
    here = Path.cwd() / "db.definition.json"
    return here if here.is_file() else None


def _database_checks(app_path: str | None) -> list[dict]:
    """W5.2 (E10): is this app's database reachable, usable, and able to store what it will be given?

    Doctor's ten pre-existing checks are all about the MACHINE -- Java, Python, git, disk, staged
    jars. None of them touches a database, so the single commonest first-run failure ("Postgres is
    not running") surfaced as a Spring stack trace during boot, after a Gradle build, with the real
    cause several hundred lines up.

    Each check below distinguishes a failure the previous one cannot:

        database-reachable      the host/port refuses          -> "it is not running"
        database-credentials    it answers, auth is rejected   -> "the password is wrong"
        database-privileges     it authenticates, DDL denied   -> "this user cannot own a schema"
        database-charset        it works, and mangles unicode  -> the silent one

    The last is the reason this is worth building rather than letting boot fail: on MySQL's legacy
    three-byte `utf8`, an insert of anything outside the BMP SUCCEEDS and the data is already wrong.
    Nothing errors, ever. That is the only database problem here that no stack trace would have
    reported.
    """
    checks: list[dict] = []
    definition_path = _find_db_definition(app_path)
    if definition_path is None:
        return checks

    try:
        definition = json.loads(definition_path.read_text(encoding="utf-8"))
        database = definition.get("database") or {}
        engine = npdev_engines.resolve(database.get("engine") or "h2local")
    except (json.JSONDecodeError, OSError, ValueError) as exc:
        checks.append(_check(
            "database-engine-support", "Database engine", "fail",
            found=str(definition_path), expected="a readable db.definition.json",
            detail=f"Could not read this app's database definition ({definition_path}): {exc}",
            fix="Fix db.definition.json, or run `npdev init` to scaffold a valid one.",
        ))
        return checks

    notice = npdev_engines.honesty_notice(engine["key"])
    checks.append(_check(
        "database-engine-support", "Database engine",
        "pass" if notice is None else "warn",
        found=engine["externalName"], expected="a supported engine",
        # W5.3's honesty rule reaching doctor: an experimental engine is a WARNING, never a failure.
        # Failing would tell a user their working machine is broken; saying nothing would be the
        # footnote-in-BREAKING.md problem this whole item exists to fix.
        detail=None if notice is None else notice,
    ))

    if not engine["server"]:
        # H2Local / InMemory have nothing to reach, authenticate against, or mis-encode. Recording a
        # pass rather than omitting the checks keeps the Manager's Ready screen shape stable across
        # engines -- a row that vanishes reads as "not checked".
        for check_id, name in (("database-reachable", "Database reachable"),
                               ("database-credentials", "Database credentials"),
                               ("database-privileges", "Database privileges"),
                               ("database-charset", "Database charset")):
            checks.append(_check(check_id, name, "pass", found=engine["externalName"],
                                 expected="n/a for an engine with no server"))
        return checks

    host = database.get("host") or "localhost"
    port = int(database.get("port") or engine["port"])

    import socket
    reachable = False
    try:
        with socket.create_connection((host, port), timeout=3):
            reachable = True
    except OSError as exc:
        checks.append(_check(
            "database-reachable", "Database reachable", "fail", found=f"{host}:{port}",
            expected="accepting connections",
            detail=f"Cannot reach {engine['externalName']} at {host}:{port} ({exc}). This is the "
                   f"most common first-run failure, and without this check it surfaces as a Spring "
                   f"stack trace after a full Gradle build.",
            fix=f"Start {engine['externalName']}, or correct host/port in "
                f"{definition_path}.",
        ))
    else:
        checks.append(_check("database-reachable", "Database reachable", "pass",
                             found=f"{host}:{port}", expected="accepting connections"))

    if not reachable:
        # Everything below needs a connection. Reported as warn-with-a-reason rather than invented
        # failures: three red lines for one cause is noise that hides which one to fix.
        for check_id, name in (("database-credentials", "Database credentials"),
                               ("database-privileges", "Database privileges"),
                               ("database-charset", "Database charset")):
            checks.append(_check(
                check_id, name, "warn", expected="checked once the server is reachable",
                detail=f"Not checked: {host}:{port} is not reachable (see database-reachable).",
            ))
        return checks

    # A real connection needs a JDBC driver, which lives in the staged runtimehost jars rather than
    # in Python. Rather than add a Python driver per engine -- three more dependencies, three more
    # ways to be wrong about what the app itself will do -- these run through the SAME jars the app
    # boots with, via a tiny Java probe. When the jars are not staged, that is reported as the
    # reason, not as a database failure.
    probe = _run_database_probe(engine, database, host, port)
    if probe.get("unavailable"):
        for check_id, name in (("database-credentials", "Database credentials"),
                               ("database-privileges", "Database privileges"),
                               ("database-charset", "Database charset")):
            checks.append(_check(
                check_id, name, "warn", expected="checked with the app's own JDBC driver",
                detail=f"Not checked: {probe['unavailable']}",
                fix="Run npdev setup.", fixCommand="npdev setup",
            ))
        return checks

    if probe.get("authenticated"):
        checks.append(_check("database-credentials", "Database credentials", "pass",
                             found=database.get("username") or "(none)", expected="accepted"))
    else:
        checks.append(_check(
            "database-credentials", "Database credentials", "fail",
            found=database.get("username") or "(none)", expected="accepted",
            detail=f"{engine['externalName']} at {host}:{port} is running but rejected the "
                   f"credentials in {definition_path}: {probe.get('authError', 'unknown error')}",
            fix=f"Correct database.username / database.password in {definition_path}.",
        ))
        return checks

    if probe.get("canCreateTable"):
        checks.append(_check("database-privileges", "Database privileges", "pass",
                             expected="can CREATE TABLE"))
    else:
        checks.append(_check(
            "database-privileges", "Database privileges", "fail", expected="can CREATE TABLE",
            detail=f"The user can connect but cannot CREATE TABLE: {probe.get('ddlError', 'unknown')}. "
                   f"NPDev realizes its schema at boot, so a read-only user fails late and "
                   f"confusingly -- after the build, during startup.",
            fix="Grant schema-creation rights to this user, or point at a database it owns.",
        ))

    charset = probe.get("charset")
    if charset is None:
        checks.append(_check("database-charset", "Database charset", "pass",
                             expected="n/a for this engine"))
    elif probe.get("charsetOk"):
        checks.append(_check("database-charset", "Database charset", "pass", found=charset,
                             expected="utf8mb4"))
    else:
        checks.append(_check(
            "database-charset", "Database charset", "fail", found=charset, expected="utf8mb4",
            detail=f"MySQL is using '{charset}'. MySQL's legacy three-byte 'utf8' SILENTLY MANGLES "
                   f"anything outside the BMP -- an emoji or many CJK characters insert without any "
                   f"error and come back wrong. Nothing will ever report this at runtime.",
            fix="Start MySQL with --character-set-server=utf8mb4 "
                "--collation-server=utf8mb4_unicode_ci, or ALTER DATABASE ... CHARACTER SET utf8mb4.",
        ))
    return checks


def _run_database_probe(engine: dict, database: dict, host: str, port: int) -> dict:
    """Connect, try DDL, and read the charset -- using the app's OWN staged JDBC drivers.

    Deliberately not a Python driver per engine. The question doctor is answering is "will the app be
    able to do this", and the only honest way to answer it is with the driver the app will use.
    """
    libs = _default_runtimehost_libs_dir()
    if libs is None:
        return {"unavailable": "runtimehost jars are not staged, so the JDBC drivers the app uses "
                               "are not available to check with -- run `npdev setup`"}
    java_home = os.environ.get("JAVA_HOME")
    java_bin = _resolve_java_home_binary(java_home) if java_home else None
    if java_bin is None or not java_bin.exists():
        path_java = shutil.which("java")
        java_bin = Path(path_java) if path_java else None
    if java_bin is None:
        return {"unavailable": "no Java found (see the java-present check above)"}

    source = _DATABASE_PROBE_SOURCE
    with tempfile.TemporaryDirectory(prefix="npdev-dbprobe-") as temp_dir:
        probe_file = Path(temp_dir) / "NpdevDatabaseProbe.java"
        probe_file.write_text(source, encoding="utf-8")
        try:
            completed = subprocess.run(
                [str(java_bin), "-cp", str(Path(libs) / "*"), str(probe_file),
                 engine["key"], host, str(port),
                 database.get("databaseName") or "", database.get("username") or "",
                 database.get("password") or ""],
                capture_output=True, text=True, timeout=45, check=False)
        except (OSError, subprocess.SubprocessError) as exc:
            return {"unavailable": f"could not run the database probe ({exc})"}
    try:
        return json.loads(completed.stdout.strip() or "{}")
    except json.JSONDecodeError:
        return {"unavailable": "the database probe produced no readable result: "
                               + (completed.stderr.strip()[:200] or "(no output)")}


# Single-file source, run by `java <file>` (JEP 330) against the staged jars. Kept as a string rather
# than a checked-in .java file on purpose: it is not part of any module's compilation, and a stray
# source file inside a module's tree is exactly the sort of thing that ends up in a jar.
_DATABASE_PROBE_SOURCE = r"""
import java.sql.*;

/** doctor's database probe: connect, attempt DDL, read the charset. Prints ONE JSON object. */
public class NpdevDatabaseProbe {
    public static void main(String[] args) {
        String engine = args[0], host = args[1], port = args[2];
        String db = args[3], user = args[4], password = args[5];
        String url = switch (engine) {
            case "postgres" -> "jdbc:postgresql://" + host + ":" + port + "/" + (db.isEmpty() ? "postgres" : db);
            case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/" + (db.isEmpty() ? "mysql" : db);
            case "sqlserver" -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName="
                    + (db.isEmpty() ? "master" : db) + ";encrypt=false;trustServerCertificate=true";
            case "h2server" -> "jdbc:h2:tcp://" + host + ":" + port + "/" + db;
            default -> null;
        };
        if (url == null) { System.out.println("{\"unavailable\":\"no JDBC url for engine " + engine + "\"}"); return; }

        StringBuilder out = new StringBuilder("{");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            out.append("\"authenticated\":true");
            // A uniquely-named table, created and dropped. Not a permissions VIEW query: what matters
            // is whether this user can actually do it here, and every engine spells that question
            // differently while all four answer the real one identically.
            String probeTable = "npdev_doctor_probe_" + Math.abs(user.hashCode() % 100000);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + probeTable + " (id INT)");
                out.append(",\"canCreateTable\":true");
                try { statement.execute("DROP TABLE " + probeTable); } catch (SQLException ignored) { }
            } catch (SQLException ddl) {
                out.append(",\"canCreateTable\":false,\"ddlError\":\"").append(escape(ddl.getMessage())).append("\"");
            }
            if ("mysql".equals(engine)) {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT @@character_set_database, @@collation_database")) {
                    if (rows.next()) {
                        String charset = rows.getString(1);
                        out.append(",\"charset\":\"").append(escape(charset)).append("\"");
                        out.append(",\"charsetOk\":").append("utf8mb4".equalsIgnoreCase(charset));
                    }
                } catch (SQLException ignored) { }
            }
        } catch (SQLException exception) {
            out.append("\"authenticated\":false,\"authError\":\"").append(escape(exception.getMessage())).append("\"");
        }
        System.out.println(out.append("}"));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
"""


def run_engines(args: argparse.Namespace) -> int:
    """List the engines an app can persist to, and say plainly which are not yet proven.

    W5.3's first requirement is that the Manager's engine picker be "driven by the CLI's engine list,
    not a hardcoded copy" -- `--json` is that list. The human form exists for the same reason
    `npdev capabilities` does: the person choosing should be able to see the answer without opening
    a source file.
    """
    listing = npdev_engines.as_json()
    if getattr(args, "json", False):
        print(json.dumps(listing, indent=2))
        return 0

    print("npdev engines -- where an app can store its data")
    print("=" * 72)
    for engine in listing["engines"]:
        mark = " " if engine["status"] == "supported" else "!"
        port = f":{engine['defaultPort']}" if engine["needsServer"] else ""
        print(f"\n{mark} {engine['key']:<10} {engine['externalName']}{port}")
        print(f"    {engine['summary']}")
        if engine["honestyNotice"]:
            # Printed for EVERY experimental engine, every time. The point of this command is that a
            # user cannot pick MySQL without being told what "selectable" does and does not mean --
            # a caveat shown only on request is a caveat nobody reads.
            print(f"    ! {engine['honestyNotice']}")
    print()
    print("  npdev init my-app --engine postgres --db-host localhost --db-user me --db-password ...")
    print()
    print("A '!' means experimental: " + listing["statusMeaning"]["experimental"])
    return 0


def run_doctor(args: argparse.Namespace) -> int:
    """I5: one screen, no scrolling -- exit non-zero listing only what MUST be fixed, warnings
    separate and never blocking. Every check here exists because this project already hit the
    failure mode it guards: JAVA_HOME/`java`-on-PATH disagreement is the classic silent Gradle
    mismatch (Gradle follows JAVA_HOME, not PATH); a hardcoded runtimehost-libs path (REG-131)
    broke `run app` for everyone but its author -- `doctor` naming the fix (`npdev setup`) instead
    of a bare "missing" is the same lesson at authoring time instead of failure time.

    Phase 0 I1: accumulates one `_check()` record per id (java-present, java-version,
    java-home-agreement, python-version, git-present, disk-space, runtimehost-jars,
    ai-knowledge-index, docker-present, pwsh-present -- the checks-contract.json list) and renders
    it two ways. When java itself is missing, java-version/java-home-agreement cannot be evaluated
    at all; they are recorded as status "pass" rather than invented failures, because today's human
    output prints nothing else in that case and MUST NOT gain new lines -- the Manager will see the
    real story once java-present's own fix is applied and doctor is re-run."""
    checks: list[dict] = []

    # Phase 0 I4b (the Manager's own M3 thesis): resolve JAVA_HOME first, PATH second -- the same
    # order Gradle itself uses. A private-JDK Manager sets ONLY JAVA_HOME for the processes it
    # starts and deliberately never touches PATH (manager/SPEC.md/DESIGN.md), so a doctor that
    # only ever called `shutil.which("java")` reported java-present FAIL on exactly that machine
    # shape even with a perfectly good JDK 17 sitting where JAVA_HOME pointed -- confirmed live
    # against a real extracted Temurin 17 with PATH stripped of java entirely. Every other check
    # below now runs against whichever binary this resolves, not a bare "java" command name, so it
    # still works when PATH has none at all.
    java_home = os.environ.get("JAVA_HOME")
    java_home_bin = _resolve_java_home_binary(java_home) if java_home else None
    path_java = shutil.which("java")

    if java_home_bin is not None and java_home_bin.exists():
        java_bin = java_home_bin
    elif path_java is not None:
        java_bin = Path(path_java)
    else:
        java_bin = None

    if java_bin is None:
        checks.append(_check(
            "java-present", "Java", "fail", expected="installed and on PATH",
            detail="Java not found on PATH -- NPDev requires Java 17+ (Gradle needs it for everything).",
            fix="Install Java 17 from https://adoptium.net/temurin/releases/",
        ))
        checks.append(_check("java-version", "Java 17+", "pass", expected="17+"))
        checks.append(_check("java-home-agreement", "JAVA_HOME", "pass",
                             expected="set, and agreeing with the java on PATH"))
    else:
        checks.append(_check("java-present", "Java", "pass", found=str(java_bin),
                             expected="installed and on PATH"))
        try:
            version_output = subprocess.run(
                [str(java_bin), "-version"], capture_output=True, text=True, timeout=10,
            ).stderr
        except (OSError, subprocess.SubprocessError):
            version_output = ""
        match = re.search(r'version "(\d+)', version_output)
        found_version = match.group(1) if match else "unknown"
        # deps-and-java/PLAN.md W1.6, widened by ROUND2_PLAN.md R1c: platform modules are pinned at
        # 17, but the GENERATED app's own toolchain is Gradle-resolved (any integer >= 17,
        # config.json's build.javaVersion, no upper enum) -- so any Java >= 17 on PATH/JAVA_HOME can
        # drive the build; requiring exactly 17 was a false negative on a newer-JDK-only machine, one
        # Gradle's own toolchain auto-detection (backed by the foojay resolver, W1.5) already handles
        # correctly while doctor kept reporting FAIL.
        found_version_int = int(found_version) if found_version.isdigit() else None
        if found_version_int is None:
            checks.append(_check(
                "java-version", "Java 17+", "fail", found=found_version, expected="17+",
                detail=f"Could not determine the Java version at {java_bin} (`java -version` output "
                       f"did not match the expected pattern).",
                fix="Install Java 17 (or newer). Other versions may be installed alongside it.",
            ))
        elif found_version_int < 17:
            checks.append(_check(
                "java-version", "Java 17+", "fail", found=found_version, expected="17+",
                detail=f"Java {found_version} found ({java_bin}) -- NPDev requires Java 17 or newer.",
                fix="Install Java 17 (or newer). Other versions may be installed alongside it.",
            ))
        elif found_version_int == 17:
            checks.append(_check("java-version", "Java 17+", "pass", found=found_version, expected="17+"))
        else:
            # >17: correct for platform work (which stays pinned at 17 regardless) and for a
            # generated app that requested build.javaVersion=21, but a generated app at the 17
            # DEFAULT needs Gradle to auto-provision a 17 toolchain it doesn't have locally -- this
            # doctor command has no Gradle invocation of its own to confirm that will succeed, so it
            # warns (network-dependent, opt-out-able per settings.gradle.template) rather than
            # claiming a guarantee it cannot back up, or failing a machine that is very likely fine.
            checks.append(_check(
                "java-version", "Java 17+", "warn", found=found_version, expected="17+",
                detail=f"Java {found_version} found ({java_bin}) -- fine for NPDev's own platform "
                       f"work (pinned at 17 regardless) and for a generated app whose config.json "
                       f"requests build.javaVersion=21 (the only supported value above 17). "
                       f"A generated app at the 17 default needs Gradle to auto-provision a 17 "
                       f"toolchain (via the foojay resolver, registered by default) the first time it "
                       f"builds -- that needs network access once, then Gradle caches it.",
            ))

        if java_home and path_java is None:
            # Nothing on PATH to disagree with -- the Manager's own shape (JAVA_HOME set, PATH
            # deliberately untouched). This must be green, not a warning: that is the whole point
            # of I4b.
            checks.append(_check("java-home-agreement", "JAVA_HOME", "pass", found=java_home,
                                 expected="set, and agreeing with the java on PATH"))
        elif java_home:
            try:
                if os.name == "nt":
                    same = str(java_home_bin.resolve()).lower() == str(Path(path_java).resolve()).lower()
                else:
                    same = java_home_bin.resolve() == Path(path_java).resolve()
            except OSError:
                same = False
            if not same:
                checks.append(_check(
                    "java-home-agreement", "JAVA_HOME", "fail", found=java_home,
                    expected="agreeing with the java on PATH",
                    detail=(
                        f"JAVA_HOME disagrees with the `java` on PATH -- JAVA_HOME={java_home} "
                        f"(-> {java_home_bin}) but PATH resolves java to {path_java}. Gradle follows "
                        f"JAVA_HOME, so a build can silently use a different JDK than this check just "
                        f"looked at."
                    ),
                    fix="Set JAVA_HOME to your Java 17 installation.",
                ))
            else:
                checks.append(_check("java-home-agreement", "JAVA_HOME", "pass", found=java_home,
                                     expected="set, and agreeing with the java on PATH"))
        else:
            checks.append(_check(
                "java-home-agreement", "JAVA_HOME", "warn",
                expected="set, and agreeing with the java on PATH",
                detail="JAVA_HOME is not set -- Gradle falls back to PATH, which works today "
                       "but is worth setting explicitly so the two can never disagree.",
                fix="Set JAVA_HOME to your Java 17 installation.",
            ))

    if sys.version_info < (3, 9):
        checks.append(_check(
            "python-version", "Python 3.9+", "fail", found=sys.version.split()[0], expected="3.9+",
            detail=f"Python {sys.version.split()[0]} found -- NPDev's CLI needs Python 3.9+.",
            fix="Install Python 3.9 or newer from https://www.python.org/downloads/",
        ))
    else:
        checks.append(_check("python-version", "Python 3.9+", "pass",
                             found=sys.version.split()[0], expected="3.9+"))

    git_path = shutil.which("git")
    if git_path is None:
        checks.append(_check(
            "git-present", "git", "fail", expected="installed and on PATH",
            detail="git not found on PATH -- required to clone NPDev and for `npdev init`.",
            fix="Install git from https://git-scm.com/downloads",
        ))
    else:
        checks.append(_check("git-present", "git", "pass", found=git_path, expected="installed and on PATH"))

    required_gb = 5
    try:
        free_gb = shutil.disk_usage(repo_root()).free / (1024 ** 3)
        if free_gb < required_gb:
            checks.append(_check(
                "disk-space", "Disk space", "fail",
                found=f"{free_gb:.1f} GB", expected=f">= {required_gb} GB",
                detail=f"{free_gb:.1f} GB free -- NPDev's Gradle caches and a generated "
                       f"app's own build need roughly {required_gb} GB free.",
                fix="Free up disk space.",
            ))
        else:
            checks.append(_check("disk-space", "Disk space", "pass",
                                 found=f"{free_gb:.1f} GB", expected=f">= {required_gb} GB"))
    except OSError:
        checks.append(_check(
            "disk-space", "Disk space", "warn", expected=f">= {required_gb} GB",
            detail="could not determine free disk space.",
        ))

    if _default_runtimehost_libs_dir() is None:
        checks.append(_check(
            "runtimehost-jars", "NPDev jars", "fail", expected="staged",
            detail="Runtimehost jars not staged -- run `npdev setup`.",
            fix="Run npdev setup.", fixCommand="npdev setup",
        ))
    else:
        checks.append(_check("runtimehost-jars", "NPDev jars", "pass", expected="staged",
                             found=str(_default_runtimehost_libs_dir())))

    knowledge_index = _ai_build_root() / "npdev-ai" / "rag-index.json"
    if not knowledge_index.exists():
        checks.append(_check(
            "ai-knowledge-index", "AI knowledge index", "warn", expected="built",
            detail="AI knowledge index not built -- run `npdev setup` (needed for the MCP "
                   "tools, not for generate/build/run).",
            fix="Run npdev setup.", fixCommand="npdev setup",
        ))
    else:
        checks.append(_check("ai-knowledge-index", "AI knowledge index", "pass", expected="built",
                             found=str(knowledge_index)))

    docker_path = shutil.which("docker")
    if docker_path is None:
        checks.append(_check(
            "docker-present", "Docker", "warn", expected="optional",
            detail="Docker not found -- only needed for the docker-compose run path.",
        ))
    else:
        checks.append(_check("docker-present", "Docker", "pass", found=docker_path, expected="optional"))

    pwsh_path = shutil.which("pwsh")
    if pwsh_path is None:
        checks.append(_check(
            "pwsh-present", "PowerShell 7", "warn", expected="optional",
            detail="PowerShell 7 (pwsh) not found -- fine for the portable `npdev` path "
                   "(`npdev setup` replaces it); still needed for this repo's own "
                   "maintainer scripts under scripts/.",
        ))
    else:
        checks.append(_check("pwsh-present", "PowerShell 7", "pass", found=pwsh_path, expected="optional"))

    checks.extend(_database_checks(getattr(args, "app", None)))

    problems = [c["detail"] for c in checks if c["status"] == "fail"]
    warnings = [c["detail"] for c in checks if c["status"] == "warn"]
    ok = not problems

    if getattr(args, "json", False):
        matrix, matrix_reason = _storage_capability_matrix()
        result = {
            "schemaVersion": "npdev-cli-result.v1",
            "command": "doctor",
            "ok": ok,
            "exitCode": 1 if problems else 0,
            "checks": checks,
            # Additive: `checks` is byte-identical to before, so the Manager's contract is untouched.
            # Deliberately NOT a check -- an engine lacking a capability is not a broken machine, and
            # making it one would tie doctor's exit code to which engines happen to be registered.
            "storageCapabilities": matrix if matrix is not None else None,
            "storageCapabilitiesUnavailableReason": matrix_reason or None,
        }
        print(json.dumps(result, indent=2))
        return result["exitCode"]

    print("npdev doctor")
    print("=" * 60)
    if problems:
        print(f"\nMUST FIX ({len(problems)}):")
        for p in problems:
            print(f"  [FAIL] {p}")
    if warnings:
        print(f"\nWarnings ({len(warnings)}, not blocking):")
        for w in warnings:
            print(f"  [WARN] {w}")
    if not problems and not warnings:
        print("\nEverything checked out.")

    # S3: the storage capability matrix, generated from the dialects rather than written down.
    # Compact per-engine summary here to keep doctor's "one screen, no scrolling" promise;
    # `npdev capabilities` prints the full grid. Informational, never part of the exit code.
    matrix, matrix_reason = _storage_capability_matrix()
    print("\nStorage engines:")
    if matrix is None:
        print(f"  (capability matrix unavailable: {matrix_reason})")
    else:
        for line in _summarize_capability_matrix(matrix):
            print(f"  {line}")
        print("  Full grid: npdev capabilities")
    return 1 if problems else 0


def _mcp_server_entry() -> dict:
    """The two things a user gets wrong in a hand-written MCP config: the absolute path and the
    Python interpreter. `sys.executable` is the interpreter ALREADY running this process -- if
    invoked via the npdev/npdev.bat wrapper (python3 then python, the same resolution order a GUI
    client cannot replicate on its own), it is already the correctly-resolved one."""
    server_path = repo_root() / "NPDevMcp" / "server.py"
    return {
        "command": sys.executable,
        "args": [str(server_path)],
        "env": {"NPDEV_ROOT": str(repo_root())},
    }


def _claude_desktop_config_path() -> Path | None:
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "Claude" / "claude_desktop_config.json"
    if os.name == "nt":
        appdata = os.environ.get("APPDATA")
        return Path(appdata) / "Claude" / "claude_desktop_config.json" if appdata else None
    return Path.home() / ".config" / "Claude" / "claude_desktop_config.json"


def _merge_mcp_config(config_path: Path, entry: dict) -> tuple[dict, str | None]:
    """Merge semantics are non-negotiable (I6): a convenience command that destroys a user's other
    configured MCP servers is unforgivable. Missing file -> create with just our entry. Existing,
    parseable file -> back it up FIRST, then touch ONLY mcpServers.npdev, every other key stays
    byte-identical. Existing, UNPARSEABLE file -> stop and raise, do not write -- a hand-edited
    config that no longer parses is the user's in-progress work, not ours to overwrite."""
    if not config_path.exists():
        return {"mcpServers": {"npdev": entry}}, None

    raw = config_path.read_text(encoding="utf-8")
    try:
        config = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise CliError(
            f"{config_path} exists but is not valid JSON ({exc}) -- refusing to touch it. Fix it "
            f"by hand, or use `npdev mcp install --print` and paste the block in yourself."
        )

    backup_path = config_path.with_name(config_path.name + f".bak-{datetime.now().strftime('%Y%m%d%H%M%S')}")
    shutil.copy2(config_path, backup_path)
    config.setdefault("mcpServers", {})
    config["mcpServers"]["npdev"] = entry
    return config, str(backup_path)


def run_mcp_install(args: argparse.Namespace) -> int:
    """I6: writes/merges an MCP client config pointing at NPDevMcp/server.py. The largest
    built-but-unreachable capability in the project before this -- 16 MCP tools and no config
    template anywhere, so a non-specialist could not connect a client."""
    entry = _mcp_server_entry()
    server_path = Path(entry["args"][0])
    if not server_path.exists():
        raise CliError(f"MCP server not found: {server_path}")

    if args.print_only or not args.client:
        print(json.dumps({"mcpServers": {"npdev": entry}}, indent=2))
        if args.client:
            target = ".mcp.json" if args.client == "claude-code" else "claude_desktop_config.json"
            print(f"\nPaste this into {target} yourself, or re-run without --print to write it.")
        else:
            print("\n(no --client given -- printed only. Pass --client claude-code or "
                  "--client claude-desktop to write the file, or paste this block into "
                  "any other MCP client's own settings.)")
        return 0

    if args.client == "claude-code":
        config_path = Path.cwd() / ".mcp.json"
    else:
        config_path = _claude_desktop_config_path()
        if config_path is None or not config_path.parent.exists():
            print(f"Could not find (or safely create) {config_path or 'a Claude Desktop config path'} "
                  f"on this platform -- not guessing, not creating the directory tree.")
            print("Paste this into your client's MCP settings yourself:\n")
            print(json.dumps({"mcpServers": {"npdev": entry}}, indent=2))
            return 1

    config, backup_path = _merge_mcp_config(config_path, entry)
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")

    print(f"Wrote {config_path}")
    if backup_path:
        print(f"Backed up the previous file to {backup_path}")
    print(f"\nServer: {entry['command']} {server_path}")
    print("Restart your client, then confirm NPDev's tools appear in its tool list.")
    return 0


def _default_runtimehost_libs_dir() -> Path | None:
    """Mirrors scripts/npdev-common.ps1's Get-NPDevRuntimeHostLibsDir convention exactly (NOT a
    third resolution order -- REG-128 already exists because there were two): NPDEV_BUILD_ROOT env
    override, else <repo>.parent/Build, then /runtimehost-libs. Returns None when the derived
    directory does not exist -- REG-131/X0: an unresolvable input is an error, never a wrong
    default. A hardcoded <drive>:/WorkSpace/... fallback here silently pointed `npdev run app` at a
    directory that only exists on the author's own machine, breaking it for everyone else."""
    build_root_env = os.environ.get("NPDEV_BUILD_ROOT")
    build_root = (Path(build_root_env).expanduser().resolve() if build_root_env and build_root_env.strip()
                  else repo_root().parent / "Build")
    candidate = build_root / "runtimehost-libs"
    return candidate if candidate.is_dir() else None


def _build_phase(app_root: Path, deadline: float) -> tuple[bool, str, Path | None]:
    wrapper = app_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.exists():
        return False, f"Gradle wrapper not found in generated app: {wrapper}", None
    env = dict(os.environ)
    if "NPDEV_RUNTIMEHOST_LIBS_DIR" not in env:
        derived = _default_runtimehost_libs_dir()
        if derived is not None:
            env["NPDEV_RUNTIMEHOST_LIBS_DIR"] = str(derived)
        # else: leave unset -- the generated build.gradle's own "Missing NPDev RuntimeHost libs
        # manifest in <path>. Run scripts/runtimehost/sync-runtimehost-libs.ps1" error fires
        # instead of a wrong path producing a confusing downstream failure.
    command = [str(wrapper), "--no-daemon", "--console=plain", "clean", "build", "-x", "test"]
    try:
        completed = _run_bounded(command, str(app_root), deadline, env=env)
    except _DeadlineExceeded:
        return False, "BUILD exceeded the overall --timeout budget.", None
    output = (completed.stdout or "") + (completed.stderr or "")
    if completed.returncode != 0:
        return False, output, None
    return True, output, _find_jar(app_root)


def _classify_model_change(root: Path, baseline: Path, current: Path, deadline: float) -> str | None:
    """Move 10 C1 (already implemented, Wave 1.2): shells out to the existing, real
    ModelChangeClassifierMain (:generator:classifyModelChange) rather than reimplementing the diff.
    That task reads its arguments as Gradle PROPERTIES (-PcurrentPath=...), not JavaExec `args`."""
    generator_root = root / "NPDevGenerator"
    wrapper = gradle_wrapper(generator_root)
    if not wrapper.exists():
        return None
    with tempfile.TemporaryDirectory(prefix="npdev-classify-") as tmp:
        report_path = Path(tmp) / "classification.json"
        command = [
            str(wrapper), *gradle_project_cache_args("generator"),
            ":generator:classifyModelChange", "--no-daemon", "--console=plain",
            f"-PcurrentPath={current}", f"-PbaselinePath={baseline}", f"-PreportOut={report_path}",
        ]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            command = ["cmd.exe", "/c"] + command
        try:
            _run_bounded(command, generator_root, deadline)
        except _DeadlineExceeded:
            return None
        if not report_path.exists():
            return None
        try:
            report = json.loads(report_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return None
        return report.get("classification")


def _metadata_only_fast_path(
        root: Path, current_model: Path, final_app_out: Path, deadline: float) -> tuple[bool, str]:
    """Move 10 C2 (already implemented, Wave 1.3): swaps compiled-model.json + re-signs the
    generated-folder signature, skipping GENERATE+BUILD entirely for a METADATA_ONLY change.
    Both underlying tasks read Gradle PROPERTIES, not JavaExec `args` -- see their own build.gradle
    registrations (classifyModelChange / resignGeneratedFolder)."""
    generator_root = root / "NPDevGenerator"
    wrapper = gradle_wrapper(generator_root)
    generated_root = final_app_out / "npdev-generated"
    compiled_model_path = generated_root / "src" / "main" / "resources" / "npdev" / "compiled-model.json"
    with tempfile.TemporaryDirectory(prefix="npdev-metadata-only-") as tmp:
        report_path = Path(tmp) / "classification.json"
        command = [
            str(wrapper), *gradle_project_cache_args("generator"),
            ":generator:classifyModelChange", "--no-daemon", "--console=plain",
            f"-PcurrentPath={current_model}", f"-PbaselinePath={current_model}",
            f"-PreportOut={report_path}", f"-PemitCompiledModelTo={compiled_model_path}",
        ]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            command = ["cmd.exe", "/c"] + command
        try:
            completed = _run_bounded(command, generator_root, deadline)
        except _DeadlineExceeded:
            return False, "classifyModelChange exceeded the overall --timeout budget."
        if completed.returncode != 0:
            return False, (completed.stdout or "") + (completed.stderr or "")

    kernel_root = root / "NPDevKernel"
    kernel_wrapper = gradle_wrapper(kernel_root)
    sign_command = [
        str(kernel_wrapper), *gradle_project_cache_args("kernel"),
        ":adapters:runtime-validation:resignGeneratedFolder",
        "--no-daemon", "--console=plain", f"-PgeneratedRoot={generated_root}",
    ]
    if os.name == "nt" and kernel_wrapper.suffix.lower() == ".bat":
        sign_command = ["cmd.exe", "/c"] + sign_command
    try:
        completed = _run_bounded(sign_command, kernel_root, deadline)
    except _DeadlineExceeded:
        return False, "resignGeneratedFolder exceeded the overall --timeout budget."
    if completed.returncode != 0:
        return False, (completed.stdout or "") + (completed.stderr or "")
    return True, "metadata-only fast path applied"


# ---------------------------------------------------------------------------
# Move 10 D2 (LC-D2): declarative acceptance scenarios -- boots via D1, seeds `given` through the
# generic concept CRUD API, executes `when`, asserts `then` with a minimal JSONPath grammar, and
# reports in a shape that generalizes golden-ai-scenarios/*/ai-verification-report.json (the same
# schemaVersion FAMILY -- checks/scenarios with real per-assertion actual values, not a second
# vocabulary reinvented from scratch).
# ---------------------------------------------------------------------------

_ACCEPTANCE_OPERATORS = ("equals", "allEqual", "lessThan", "greaterThan", "count")


def _resolve_json_path(root: object, path: str) -> list:
    """Minimal JSONPath: $ / $status / $.a.b / $.a[*].b / $.a[2].b -- always returns a list of
    matches (a non-wildcard scalar path returns a 1-element list, or 0 if any segment misses)."""
    if path == "$status":
        return [root] if not isinstance(root, (dict, list)) else []
    if not path.startswith("$"):
        raise ValueError(f"path must start with $ or be the literal $status: {path}")
    tokens = re.findall(r"\.([^.\[\]]+)|\[(\*|\d+)\]", path[1:])
    current = [root]
    for name, index in tokens:
        nxt = []
        for item in current:
            if name:
                if isinstance(item, dict) and name in item:
                    nxt.append(item[name])
            elif index == "*":
                if isinstance(item, list):
                    nxt.extend(item)
            elif index:
                idx = int(index)
                if isinstance(item, list) and idx < len(item):
                    nxt.append(item[idx])
        current = nxt
    return current


def _eval_assertion(root: object, assertion: dict) -> dict:
    path = assertion["path"]
    op = next((k for k in _ACCEPTANCE_OPERATORS if k in assertion), None)
    if op is None:
        return {"path": path, "operator": None, "expected": None, "actual": None, "passed": False,
                "error": f"no recognized operator (one of {_ACCEPTANCE_OPERATORS})"}
    expected = assertion[op]
    resolved = _resolve_json_path(root, path)
    if op == "equals":
        actual = resolved[0] if len(resolved) == 1 else resolved
        passed = actual == expected
    elif op == "allEqual":
        actual = resolved
        passed = len(resolved) > 0 and all(v == expected for v in resolved)
    elif op == "count":
        actual = len(resolved)
        passed = actual == expected
    elif op == "lessThan":
        actual = resolved[0] if resolved else None
        passed = actual is not None and actual < expected
    else:  # greaterThan
        actual = resolved[0] if resolved else None
        passed = actual is not None and actual > expected
    return {"path": path, "operator": op, "expected": expected, "actual": actual, "passed": passed}


def _run_one_scenario(base_url: str, scenario: dict, filename: str, api_key: str = "") -> dict:
    import urllib.error
    import urllib.request

    name = scenario.get("name", filename)
    approved = bool(scenario.get("approved", False))
    result: dict = {"name": name, "file": filename, "approved": approved, "outcome": "ERROR",
                     "assertions": [], "error": None}
    auth_headers = {"X-Api-Key": api_key} if api_key else {}

    for given in scenario.get("given", []):
        seed_path = given["path"]
        for row in given.get("rows", []):
            body = json.dumps(row).encode("utf-8")
            req = urllib.request.Request(base_url + seed_path, data=body, method="POST",
                                          headers={"Content-Type": "application/json", **auth_headers})
            try:
                urllib.request.urlopen(req, timeout=10)
            except urllib.error.HTTPError as exc:
                result["error"] = (f"seed POST {seed_path} failed: HTTP {exc.code} "
                                    f"{exc.read().decode('utf-8', 'replace')[:300]}")
                return result
            except urllib.error.URLError as exc:
                result["error"] = f"seed POST {seed_path} failed: {exc.reason}"
                return result

    when = scenario["when"]
    method = when.get("method", "GET").upper()
    when_path = when["path"]
    when_body = json.dumps(when["body"]).encode("utf-8") if "body" in when else None
    headers = {**auth_headers, **(when.get("headers") or {})}
    if when_body is not None:
        headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(base_url + when_path, data=when_body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            status = response.status
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read().decode("utf-8", "replace")
    except urllib.error.URLError as exc:
        result["error"] = f"when {method} {when_path} failed: {exc.reason}"
        return result
    try:
        response_body = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        response_body = None

    all_passed = True
    for assertion in scenario.get("then", []):
        root = status if assertion["path"] == "$status" else response_body
        evaluated = _eval_assertion(root, assertion)
        result["assertions"].append(evaluated)
        if not evaluated["passed"]:
            all_passed = False
    result["outcome"] = "PASS" if all_passed else "FAIL"
    return result


def run_acceptance(args: argparse.Namespace) -> dict:
    """Move 10 D2 (LC-D2): boots via D1 (run_app), then runs every *.scenario.json under
    --scenarios against the live app. Unapproved scenarios still execute (informational -- an
    Author's proposal is visible) but are excluded from the pass/fail summary (D2's own DoD:
    "Unapproved scenarios are visibly excluded from the pass count").

    Move 14 Phase D item D1: --base-url (getattr, so every pre-existing caller that never set
    it is unaffected) skips the boot entirely and runs scenarios against an ALREADY-RUNNING app
    -- for a caller (e.g. the T1 fast gate) that already paid for a generate+build+boot cycle for
    its own smoke check and does not want a SECOND one just to add behavioural assertions.
    --model/--config/--output are meaningless in that mode and are not required.
    """
    base_url = getattr(args, "base_url", None)
    if base_url:
        boot_result = {"ok": True, "baseUrl": base_url, "skipped": "boot skipped -- --base-url was supplied"}
    else:
        if not (args.model and args.config and args.output):
            raise CliError("acceptance run: --model/--config/--output are required unless --base-url is given.")
        boot_args = argparse.Namespace(
            model=args.model, config=args.config, output=args.output,
            require_db_definition=getattr(args, "require_db_definition", False),
            port=args.port, timeout=args.timeout,
            profile=getattr(args, "profile", "dev"),
            baseline_model=getattr(args, "baseline_model", None),
            keep_running=getattr(args, "keep_running", False),
        )
        boot_result = run_app(boot_args)
        if not boot_result.get("ok"):
            return {
                "schemaVersion": "npdev-acceptance-report.v1",
                "ok": False,
                "boot": boot_result,
                "scenarios": [],
                "summary": {"total": 0, "approvedTotal": 0, "passed": 0, "failed": 0, "excludedUnapproved": 0},
            }
        base_url = boot_result["baseUrl"]
    scenarios_dir = Path(args.scenarios).expanduser().resolve()
    scenario_files = sorted(scenarios_dir.glob("*.scenario.json"))
    api_key = getattr(args, "api_key", "dev-key")
    results = [
        _run_one_scenario(base_url, json.loads(sf.read_text(encoding="utf-8")), sf.name, api_key)
        for sf in scenario_files
    ]
    approved = [r for r in results if r["approved"]]
    passed = sum(1 for r in approved if r["outcome"] == "PASS")
    failed = len(approved) - passed
    return {
        "schemaVersion": "npdev-acceptance-report.v1",
        "ok": failed == 0 and boot_result.get("ok", False),
        "baseUrl": base_url,
        "boot": boot_result,
        "scenarios": results,
        "summary": {
            "total": len(results), "approvedTotal": len(approved),
            "passed": passed, "failed": failed, "excludedUnapproved": len(results) - len(approved),
        },
    }


# ---------------------------------------------------------------------------
# Move 10 D3 (LC-D3): wire the closed loop -- diff-gate -> validate -> classify -> run+acceptance,
# stopping at the EARLIEST gate that catches a problem. Pure integration: every step below reuses
# an already-built, already-proven piece (E2's AuthoringDiffGate, ModelValidatorMain, C1's
# ModelChangeClassifierMain, D1's run_app, D2's run_acceptance) in-process -- no new gate logic.
# ---------------------------------------------------------------------------

def run_closed_loop(args: argparse.Namespace) -> dict:
    report: dict = {
        "schemaVersion": "npdev-closed-loop-report.v1",
        "ok": False,
        "stoppedAt": None,
        "diffGate": None,
        "validate": None,
        "classification": None,
        "run": None,
        "acceptance": None,
    }

    # 1. diff-gate (contract E2) -- refuses undiffed/undeclared changes.
    diff_gate_args = argparse.Namespace(
        previous=args.previous, submitted=args.submitted, manifest=getattr(args, "manifest", None),
        output=getattr(args, "diff_gate_output", None),
    )
    diff_report = _run_authoring_gate(diff_gate_args, archive_dir=None)
    report["diffGate"] = diff_report
    if diff_report.get("status") != "passed":
        report["stoppedAt"] = "diffGate"
        return report

    # 2. validate model -- refuses an illegal model (schema + full semantic validation).
    with tempfile.TemporaryDirectory(prefix="npdev-loop-validate-") as tmp:
        validate_report_path = Path(tmp) / "validation-report.json"
        exit_code = run_validate_semantic(Path(args.submitted), validate_report_path)
        report["validate"] = read_json(validate_report_path) if validate_report_path.exists() else None
        if exit_code != 0:
            report["stoppedAt"] = "validate"
            return report

    # 3. classify (C1) -- informational; also threaded into run+acceptance as --baseline-model so
    #    a METADATA_ONLY change automatically takes the C2 fast path.
    import time

    root = repo_root()
    deadline = time.monotonic() + args.timeout
    report["classification"] = _classify_model_change(
        root, Path(args.previous).expanduser().resolve(), Path(args.submitted).expanduser().resolve(), deadline)

    # 4/5. run app (D1) + acceptance (D2) -- run_acceptance() already does both: it boots via
    # run_app() internally, then executes every scenario against the live app.
    acceptance_args = argparse.Namespace(
        model=args.submitted, config=args.config, output=args.output,
        require_db_definition=getattr(args, "require_db_definition", False),
        port=args.port, timeout=args.timeout, profile=getattr(args, "profile", "dev"),
        baseline_model=args.previous, keep_running=getattr(args, "keep_running", False),
        scenarios=args.scenarios, api_key=getattr(args, "api_key", "dev-key"),
    )
    acceptance_report = run_acceptance(acceptance_args)
    report["run"] = acceptance_report.get("boot")
    report["acceptance"] = acceptance_report
    if not acceptance_report.get("boot", {}).get("ok"):
        report["stoppedAt"] = "run"
        return report
    if not acceptance_report.get("ok"):
        report["stoppedAt"] = "acceptance"
        return report

    report["ok"] = True
    return report


def _fetch_json(url: str, headers: dict[str, str]) -> dict:
    import urllib.error
    import urllib.request

    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:500]
        raise CliError(f"GET {url} -> HTTP {exc.code}: {body}")
    except urllib.error.URLError as exc:
        raise CliError(f"GET {url} failed: {exc.reason}")


def _screen_auth_headers(args: argparse.Namespace) -> dict[str, str]:
    if getattr(args, "token_file", None):
        token = Path(args.token_file).expanduser().read_text(encoding="utf-8").strip()
        return {"Authorization": f"Bearer {token}"}
    if getattr(args, "api_key", None):
        return {"X-Api-Key": args.api_key}
    return {}


def _load_quality_module(root: Path, module_name: str, filename: str):
    """Import a scripts/quality/*.py module by path (that directory is not a package, matching how
    other tools in this repo load sibling scripts -- e.g. npdev_cli.py's own failure_signatures
    import above)."""
    import importlib.util

    spec = importlib.util.spec_from_file_location(module_name, root / "scripts" / "quality" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_generate_screen(args: argparse.Namespace) -> int:
    """R-P4 (docs/REMEDIATION_PLAN.md, 3.8 'agent-driven frontend generation, productized'): fetch
    the live UI-contract bundle, hand it plus docs/ai/UI_GENERATION_PROMPT.md to an agent, and refuse
    to write anything whose manifest fails the same impact gate `_ops/Check-Provenance.ps1` runs live
    against a real app -- generation and verification in one step.

    No LLM vendor is baked into this CLI -- `docs/ai/UI_GENERATION_PROMPT.md` itself is written
    vendor-neutral ("your model id"), and nothing else in this repo calls out to a specific AI
    provider from shipped platform code either (ADR-0009's external-AI subsystem is a pluggable
    kernel PORT, not a hardcoded vendor call). `--model-command` is the same pattern here: an
    operator-supplied command (parsed with shlex, never a shell string -- this repo's own
    subprocess.run calls never use shell=True) that reads the assembled prompt on stdin and must
    print `{"html": "...", "panelJson": {...}}` to stdout. Omit it to get a two-step flow instead:
    this command writes the assembled prompt next to `--out` and exits 3; feed that file to whatever
    agent you're using by hand, then re-run with `--from-response <file>` holding its JSON reply.
    """
    root = repo_root()
    out_path = Path(args.out).expanduser().resolve()
    base_url = args.app.rstrip("/")
    concept = args.concept
    headers = _screen_auth_headers(args)

    bundle_url = f"{base_url}/api/v1/runtime/metadata/ui/bundle?concept={concept}"
    print(f"npdev: fetching {bundle_url}", file=sys.stderr)
    bundle = _fetch_json(bundle_url, headers)

    prompt_doc_path = root / "docs" / "ai" / "UI_GENERATION_PROMPT.md"
    if not prompt_doc_path.exists():
        raise CliError(f"reference prompt not found: {prompt_doc_path}")
    assembled_prompt = (
        f"{prompt_doc_path.read_text(encoding='utf-8')}\n\n---\n\n"
        f"## Task\n\nConcept: {concept}\nOutput screen file: {out_path.name}\n\n"
        f"## Live bundle (the ONLY source of truth -- see \"Contract\" above)\n\n"
        f"```json\n{json.dumps(bundle, indent=2)}\n```\n"
    )

    response_json: object = None
    if args.from_response:
        response_json = json.loads(Path(args.from_response).expanduser().read_text(encoding="utf-8"))
    elif args.model_command:
        command = shlex.split(args.model_command)
        completed = subprocess.run(command, input=assembled_prompt, capture_output=True, text=True)
        if completed.returncode != 0:
            raise CliError(f"--model-command exited {completed.returncode}:\n{completed.stderr[-2000:]}")
        try:
            response_json = json.loads(completed.stdout)
        except json.JSONDecodeError as exc:
            raise CliError(f"--model-command did not print a valid JSON {{html, panelJson}} object: {exc}")
    else:
        prompt_path = Path(str(out_path) + ".prompt.txt")
        prompt_path.parent.mkdir(parents=True, exist_ok=True)
        prompt_path.write_text(assembled_prompt, encoding="utf-8")
        print(
            f"npdev: no --model-command / --from-response given -- wrote the assembled prompt + live "
            f"bundle to {prompt_path}. Feed it to an agent, save its "
            f'{{"html": ..., "panelJson": ...}} response as JSON, then re-run with '
            f"--from-response <file>.",
            file=sys.stderr,
        )
        return 3

    if not isinstance(response_json, dict) or "html" not in response_json or "panelJson" not in response_json:
        raise CliError('agent response must be a JSON object with "html" and "panelJson" keys')
    html = response_json["html"]
    panel = response_json["panelJson"]
    if not isinstance(html, str) or not html.strip():
        raise CliError('agent response "html" must be a non-empty string')
    if not isinstance(panel, dict):
        raise CliError('agent response "panelJson" must be a JSON object')

    # Force the fields this command, not the agent, is responsible for getting right --
    # docs/ai/UI_GENERATION_PROMPT.md's required-output shape plus R-P4's producer/confirmed contract.
    panel.setdefault("schemaVersion", "npdev-panel-provenance.v1")
    panel["producer"] = "agent"
    panel["confirmed"] = True
    panel.setdefault("screen", f"web/{out_path.name}")
    panel.setdefault("calls", [])
    panel.setdefault("slotOf", None)
    panel.setdefault("screenClass", None)
    panel.setdefault("unresolved", [])
    generated_from = panel.setdefault("generatedFrom", {})
    if not isinstance(generated_from, dict):
        generated_from = {}
        panel["generatedFrom"] = generated_from
    generated_from["modelHash"] = bundle.get("modelHash", generated_from.get("modelHash", ""))
    generated_from.setdefault(
        "generatedAt", datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    )
    generated_from.setdefault("generator", args.model_command or "external-agent")
    generated_from.setdefault("bundleScope", {"concept": concept})

    schema_checker = _load_quality_module(root, "npdev_cli_panel_schema_check", "check-panel-provenance-schema.py")
    impact_checker = _load_quality_module(root, "npdev_cli_panel_impact_check", "check-panel-provenance-impact.py")

    schema_errors = schema_checker.validate_manifest(out_path, panel)
    if schema_errors:
        for err in schema_errors:
            print(f"npdev: REFUSED -- {err}", file=sys.stderr)
        raise CliError(f"generated manifest fails structural validation ({len(schema_errors)} error(s)) -- nothing written")

    fields, invocations = impact_checker.model_surface(bundle)
    impact_problems: list[str] = []
    for ref in list(panel.get("reads", [])) + list(panel.get("writes", [])):
        if ref not in fields:
            impact_problems.append(f"references field '{ref}', which the live model does not have")
    for inv in panel.get("invokes", []):
        if inv not in invocations:
            impact_problems.append(f"references invocation '{inv}', which does not exist in the live bundle")
    if impact_problems:
        for problem in impact_problems:
            print(f"npdev: REFUSED -- {problem}", file=sys.stderr)
        raise CliError(
            f"generated manifest fails the impact gate ({len(impact_problems)} problem(s)) against the "
            f"live bundle -- nothing written. Either the agent hallucinated a field/route, or the model "
            f"changed under it; regenerate against a fresh bundle."
        )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(html, encoding="utf-8")
    panel_path = out_path.with_suffix(".panel.json")
    panel_path.write_text(json.dumps(panel, indent=2) + "\n", encoding="utf-8")
    print(f"npdev: wrote {out_path}")
    print(f"npdev: wrote {panel_path} (producer=agent, confirmed=true, verified against the live bundle)")
    return 0


def run_migration_diff(args: argparse.Namespace) -> None:
    """REG-102 fix: route through the REAL, working `:generator:classifyModelChange` Gradle task
    (ModelChangeClassifierMain, MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.2), which diffs two
    model.json snapshots via MigrationPlanEmitter (no live database) and classifies the result as
    METADATA_ONLY / SAFE_ADDITIVE / BACKFILL_REQUIRED / MANUAL_REVIEW.

    Previously this called `:generator:run` with five `--migration*`-prefixed flags
    (`--migrationsDir`, `--migrationMode`, `--migrationPlanOnly`, `--migrationRiskThreshold`,
    `--migrationDecisionReport`) that GeneratorMain's own arg parser unconditionally rejects (a
    guard against a different, retired thing -- model.json declaring migrationManagement/
    migrations/schemaEvolution config keys). Reproduced live: every invocation threw
    CONFIG_MIGRATIONS_DISABLED on the first flag. See ledger/items/REG-102.yml.
    """
    root = repo_root()
    generator_root = root / "NPDevGenerator"
    wrapper = gradle_wrapper(generator_root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")

    baseline = Path(args.baseline).expanduser().resolve()
    current = Path(args.current).expanduser().resolve()
    output = Path(args.output).expanduser().resolve() if args.output else root / "build" / "npdev-migration-diff"
    decision_report = Path(args.decision_report).expanduser().resolve() if args.decision_report else output / "migration-diff-decision.json"

    if not baseline.exists():
        raise CliError(f"baseline snapshot not found: {baseline}")
    if not current.exists():
        raise CliError(f"current model not found: {current}")

    decision_report.parent.mkdir(parents=True, exist_ok=True)

    generator_args = [
        "--current",
        str(current),
        "--baseline",
        str(baseline),
        "--out",
        str(decision_report),
    ]
    command = [
        str(wrapper),
        *gradle_project_cache_args("generator"),
        ":generator:classifyModelChange",
        "--no-daemon",
        "--console=plain",
        "--args=" + gradle_args_value(generator_args),
    ]
    if os.name == "nt" and wrapper.suffix.lower() == ".bat":
        command = ["cmd.exe", "/c"] + command
    subprocess.run(command, cwd=generator_root, check=True)
    report = read_json(decision_report)
    print(f"migration diff classification: {report.get('classification')}")
    for reason in report.get("classificationReasons", []):
        print(f"  - {reason}")
    print(f"migration diff decision report: {decision_report}")


def _add_merge_args(parser: argparse.ArgumentParser) -> None:
    """S5 (element-granularity authoring merge, __OutsideRepo\\s5\\S5_SPEC.md I5): "surface it
    where the diff gate already lives -- do not build a second entry point." Passing --theirs
    treats --previous as the shared BASE, --submitted/--manifest as OURS (already landed), and
    --theirs/--theirs-manifest as the incoming THEIRS submission; the gate then attempts an
    element-granularity merge instead of refusing outright on a stale base.
    """
    parser.add_argument("--theirs", help="S5: the incoming THEIRS model.json, also based on --previous. Triggers merge mode.")
    parser.add_argument("--theirs-manifest", dest="theirs_manifest", help="S5: THEIRS' own submission manifest.")
    parser.add_argument("--merged-out", dest="merged_out", help="S5: write the merged model.json here on a successful merge.")
    parser.add_argument("--merged-manifest-out", dest="merged_manifest_out",
                         help="S5: write the synthesized merged manifest (mergeOutcome) here on a successful merge.")


def _run_authoring_gate(args: argparse.Namespace, archive_dir: Path | None) -> dict:
    """Shared by `author diff-gate` and `author submit`: invokes
    `:generator:authorDiffGate` (AuthoringDiffGate, AI_AUTHORING_CONTRACT-2026-07-31.md Part 9,
    piece E2 -- "the load-bearing piece") and returns the parsed report. Raises CliError with the
    report's own diagnostics on failure -- never a bare non-zero exit with no explanation (C7:
    diff-gate failures must be structured, actionable diagnostics, not prose).
    """
    root = repo_root()
    generator_root = root / "NPDevGenerator"
    wrapper = gradle_wrapper(generator_root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")

    previous = Path(args.previous).expanduser().resolve()
    submitted = Path(args.submitted).expanduser().resolve()
    manifest = Path(args.manifest).expanduser().resolve() if args.manifest else None
    output = Path(args.output).expanduser().resolve() if args.output else root / "build" / "npdev-authoring"
    decision_report = output / "authoring-diff-gate-report.json"

    if not previous.exists():
        raise CliError(f"previous model not found: {previous}")
    if not submitted.exists():
        raise CliError(f"submitted model not found: {submitted}")
    if manifest is not None and not manifest.exists():
        raise CliError(f"manifest not found: {manifest}")
    decision_report.parent.mkdir(parents=True, exist_ok=True)

    generator_args = ["--previous", str(previous), "--submitted", str(submitted), "--out", str(decision_report)]
    if manifest is not None:
        generator_args += ["--manifest", str(manifest)]
    if archive_dir is not None:
        generator_args += ["--archiveDir", str(archive_dir)]

    # S5 (I5): --theirs triggers element-granularity merge mode alongside the base gate check.
    theirs = getattr(args, "theirs", None)
    if theirs:
        theirs_path = Path(theirs).expanduser().resolve()
        if not theirs_path.exists():
            raise CliError(f"theirs model not found: {theirs_path}")
        generator_args += ["--theirs", str(theirs_path)]
        theirs_manifest = getattr(args, "theirs_manifest", None)
        if theirs_manifest:
            theirs_manifest_path = Path(theirs_manifest).expanduser().resolve()
            if not theirs_manifest_path.exists():
                raise CliError(f"theirs manifest not found: {theirs_manifest_path}")
            generator_args += ["--theirsManifest", str(theirs_manifest_path)]
        merged_out = getattr(args, "merged_out", None)
        if merged_out:
            generator_args += ["--mergedOut", str(Path(merged_out).expanduser().resolve())]
        merged_manifest_out = getattr(args, "merged_manifest_out", None)
        if merged_manifest_out:
            generator_args += ["--mergedManifestOut", str(Path(merged_manifest_out).expanduser().resolve())]

    command = [
        str(wrapper),
        *gradle_project_cache_args("generator"),
        ":generator:authorDiffGate",
        "--no-daemon",
        "--console=plain",
        "--args=" + gradle_args_value(generator_args),
    ]
    if os.name == "nt" and wrapper.suffix.lower() == ".bat":
        command = ["cmd.exe", "/c"] + command
    subprocess.run(command, cwd=generator_root, check=True)

    if not decision_report.exists():
        raise CliError(f"diff gate did not produce a report at {decision_report} -- see Gradle output above.")
    return read_json(decision_report)


def run_author_diff_gate(args: argparse.Namespace) -> int:
    """E2: `npdev author diff-gate --previous <m> --submitted <m> [--manifest <j>]` -- a pure
    check, no archival. Use `author submit` once the change is ready to accept.

    Returns 0 when the gate passes, 2 when it refuses (same convention as `validate model`:
    a refusal is a valid, loopable result an Author self-corrects against, not a system error --
    CliError/exit 1 is reserved for a genuine usage problem, e.g. a missing file).
    """
    report = _run_authoring_gate(args, archive_dir=None)
    _print_authoring_report(report)
    return 0 if report.get("status") == "passed" else 2


def run_author_submit(args: argparse.Namespace) -> int:
    """E4: `npdev author submit --previous <m> --submitted <m> --manifest <j> [--archiveDir <d>]`
    -- wraps E2 so an Author literally cannot submit without a manifest (--manifest is required,
    unlike the bare diff-gate check), and archives the previous model on acceptance (E5). Does
    NOT itself write the submitted model into place or contact a live app -- that is the caller's
    job once this gate has passed; C5 requires routing a MANUAL_REVIEW/BACKFILL_REQUIRED result
    through the EXISTING SchemaAcknowledgmentController flow, not a second acknowledgment
    mechanism built here.

    Returns 0/2 by the same convention as run_author_diff_gate.
    """
    if not args.manifest:
        raise CliError("author submit requires --manifest (C1: an Author cannot submit without one).")
    archive_dir = Path(args.archive_dir).expanduser().resolve() if args.archive_dir else (
        Path(args.previous).expanduser().resolve().parent.parent / "model-history"
    )
    report = _run_authoring_gate(args, archive_dir=archive_dir)
    _print_authoring_report(report)
    if report.get("status") != "passed":
        print("REFUSED: the previous model was NOT archived and nothing was accepted.", file=sys.stderr)
        return 2
    archived_to = report.get("archivedPreviousModelTo")
    if archived_to:
        print(f"previous model archived: {archived_to}")
    print("Gate passed. This tool does not itself apply the submitted model or contact a live app -- "
          "route the result per C4/C5 (METADATA_ONLY/SAFE_ADDITIVE may proceed; BACKFILL_REQUIRED/"
          "MANUAL_REVIEW need Owner acknowledgment through the existing SchemaAcknowledgmentController).")
    return 0


def _print_authoring_report(report: dict) -> None:
    print(f"authoring diff gate: {report.get('status')}")
    for diag in report.get("diagnostics", []):
        code = diag.get("code", "")
        message = diag.get("message", "")
        suggested = diag.get("suggestedFix")
        print(f"  [{diag.get('severity', '?')}] {code}: {message}")
        if suggested:
            print(f"      fix: {suggested}")
    merge = report.get("merge")
    if merge is not None:
        print(f"merge: {merge.get('status')}")
        for diag in merge.get("diagnostics", []):
            print(f"  [error] {diag.get('code', '')}: {diag.get('message', '')}")
        if merge.get("status") == "merged":
            print(f"  mergedModelVersion: {merge.get('mergedModelVersion')}")
            print(f"  mergedModelSha256: {merge.get('mergedModelSha256')}")
        for element in merge.get("elementsFromOurs", []):
            print(f"  from ours: {element.get('arrayKey')}[{element.get('name')}]")
        for element in merge.get("elementsFromTheirs", []):
            print(f"  from theirs: {element.get('arrayKey')}[{element.get('name')}]")


def run_report_bootstrap() -> None:
    root = repo_root()
    script = root / "scripts" / "quality" / "bootstrap-post-beta0-reports.ps1"
    if not script.exists():
        raise CliError(f"report bootstrap script not found: {script}")
    subprocess.run(["pwsh", "-NoProfile", "-File", str(script)], cwd=root, check=True)


def run_verify(args: argparse.Namespace) -> dict:
    """Fast Lane plan item 6 (2026-08-01): one entry point for all four verification tiers,
    reading the same staleness ledger every tier writes to (scripts/quality/cadence_state.py)
    rather than leaving "green" to mean whichever script someone happened to run -- the
    invocation-topology class again, one level up.

    T0/T1 shell out to run-fast-gate.ps1 (-Tier T0 skips the canary+corpus checks). T2 shells out
    to run-all-gates.ps1 (betaRelease deferred by default, per item 4). T3 shells out to
    run-beta-release-gate.ps1 directly -- -GenerateReports also runs the ~540s evidence
    orchestration, not just an evaluate-what-exists pass.
    """
    root = repo_root()
    tier = args.tier
    py = sys.executable or "python"
    cadence_script = root / "scripts" / "quality" / "cadence_state.py"

    if tier in ("T0", "T1"):
        script = root / "scripts" / "quality" / "run-fast-gate.ps1"
        if not script.exists():
            raise CliError(f"fast gate script not found: {script}")
        cmd = ["pwsh", "-NoProfile", "-File", str(script), "-Tier", tier]
        if getattr(args, "model_path", None):
            cmd += ["-ModelPath", args.model_path]
        if getattr(args, "dsl_test_filter", None):
            cmd += ["-DslTestFilter", args.dsl_test_filter]
        exit_code = subprocess.run(cmd, cwd=root).returncode
    elif tier == "T2":
        script = root / "scripts" / "quality" / "run-all-gates.ps1"
        if not script.exists():
            raise CliError(f"run-all-gates.ps1 not found: {script}")
        exit_code = subprocess.run(["pwsh", "-NoProfile", "-File", str(script)], cwd=root).returncode
    elif tier == "T3":
        script = root / "scripts" / "quality" / "run-beta-release-gate.ps1"
        if not script.exists():
            raise CliError(f"run-beta-release-gate.ps1 not found: {script}")
        cmd = ["pwsh", "-NoProfile", "-File", str(script)]
        generate_reports = bool(getattr(args, "generate_reports", False))
        if generate_reports:
            cmd.append("-GenerateReports")
        exit_code = subprocess.run(cmd, cwd=root).returncode
        gate_result = "passed" if exit_code == 0 else "failed"
        subprocess.run(
            [py, str(cadence_script), "record", "--id", "betaRelease", "--tier", "T3", "--result", gate_result],
            cwd=root, check=False,
        )
        if generate_reports:
            subprocess.run(
                [py, str(cadence_script), "record", "--id", "beta-release-evidence", "--tier", "T3", "--result", gate_result],
                cwd=root, check=False,
            )
    else:
        raise CliError(f"unknown tier: {tier}")

    report_proc = subprocess.run(
        [py, str(cadence_script), "report", "--tier", tier, "--json"],
        cwd=root, capture_output=True, text=True, check=False,
    )
    cadence_report = None
    if report_proc.stdout.strip():
        try:
            cadence_report = json.loads(report_proc.stdout)
        except json.JSONDecodeError:
            cadence_report = None

    cadence_passed = bool(cadence_report) and cadence_report.get("status") == "passed"

    # CI_RED_PLAN.md I3: T2/T3 are the tiers used to claim "closing a Move" / "release-ready" --
    # exactly the claims that stayed green locally for 12 days while main's own scheduled CI run
    # was red. Cross-check it here so that claim can no longer be made without also being true.
    remote_ci = _check_remote_ci_status() if tier in ("T2", "T3") else None
    remote_ci_ok = True
    if remote_ci is not None and remote_ci.get("checked"):
        remote_ci_ok = remote_ci.get("latestConclusion") == "success"
        if not remote_ci_ok:
            print(
                f"REMOTE CI ON main: FAILING since {remote_ci.get('failingSince')} -- {remote_ci.get('latestRunUrl')}",
                file=sys.stderr,
            )

    return {
        "ok": exit_code == 0 and cadence_passed and remote_ci_ok,
        "tier": tier,
        "gateExitCode": exit_code,
        "cadence": cadence_report,
        "remoteCi": remote_ci,
    }


def run_validate_semantic(model_path: Path, report_out: Path | None) -> int:
    """Run full structural + semantic validation via the standalone Java validator.

    Invokes the :NPDevContract:dsl:validateModel Gradle task (ModelValidatorMain), which runs
    the exact validation the generator runs -- without generating -- and writes a typed
    npdev-validation-report.v2 report. Returns 0 when the model passes (or has warnings only),
    2 when it has errors. The report is the loopable contract an AI-authoring agent self-corrects
    against; here we also echo it to stdout.
    """
    root = repo_root()
    wrapper = gradle_wrapper(root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")
    model = Path(model_path).expanduser().resolve()
    if not model.exists():
        raise CliError(f"model not found: {model}")

    written_report = Path(report_out).expanduser().resolve() if report_out else None
    with tempfile.TemporaryDirectory(prefix="npdev-validate-") as temp_dir:
        report_target = written_report or (Path(temp_dir) / "validation-report.json")
        report_target.parent.mkdir(parents=True, exist_ok=True)
        gradle_args = [
            str(wrapper),
            *gradle_project_cache_args("root"),
            ":NPDevContract:dsl:validateModel",
            f"-PmodelPath={model}",
            f"-PreportOut={report_target}",
            "-q",
            "--console=plain",
        ]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            gradle_args = ["cmd.exe", "/c"] + gradle_args
        # Capture the subprocess output: the Java validator echoes the report to stdout, but the
        # report FILE is the channel we read, and the CLI is the single stdout authority (printing
        # captured output too would emit two JSON docs). On failure, surface it in the error.
        completed = subprocess.run(gradle_args, cwd=root, check=False, capture_output=True, text=True)
        if not report_target.exists():
            detail = (completed.stderr or completed.stdout or "").strip()
            raise CliError(
                "validator did not produce a report"
                + (f" (gradle exit {completed.returncode})" if completed.returncode else "")
                + (f": {detail[-500:]}" if detail else "")
            )
        report = read_json(report_target)

    _capture_validation(model, report)
    print(json.dumps(report, indent=2))
    _print_boundary_limits(report)
    return 2 if report.get("status") == "failed" else 0


def _print_boundary_limits(report: dict) -> None:
    """REG-135: a diagnostic carrying boundaryId is hitting a NAMED, accepted NPDev design limit
    (docs/ACCEPTED_BOUNDARIES.md), not a mistake in the model -- render it distinctly from an
    ordinary ERROR so the model author reads "not wrong, unsupported" instead of "broken". Printed
    to stderr, after the JSON report: the report's own contract (npdev-validation-report.v2) is
    unchanged, this is purely additive human-facing narration, same pattern as generate app's
    stderr-only phase narration."""
    for diag in report.get("diagnostics", []) or []:
        boundary_id = diag.get("boundaryId")
        if not boundary_id:
            continue
        print(
            f"\n  LIMIT   {diag.get('message', '')} (boundary {boundary_id})\n"
            f"          This is a designed limit, not a mistake in your model.\n"
            f"          -> details: docs/ACCEPTED_BOUNDARIES.md#{boundary_id}",
            file=sys.stderr,
        )
        _record_boundary_hit(boundary_id, diag.get("code", ""))


def _record_boundary_hit(boundary_id: str, code: str) -> None:
    """REG-135 step 4 ("count first, write second" -- writing all 11 boundaries' userFacingText
    before knowing which ones actually fire is the same measurement-classified-as-documentation
    mistake that produced five stale console records earlier in this project, per PLAN.md's own
    text). One JSON line per firing, best-effort -- a write failure here must never affect the
    validate command's own exit code or report."""
    try:
        log_path = _ai_build_root() / "npdev-ai" / "boundary-hits" / "boundary-hits.jsonl"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({
                "boundaryId": boundary_id, "code": code, "at": _utc_now(),
            }) + "\n")
    except OSError:
        pass


def inspect_app(args: argparse.Namespace) -> None:
    """Read-only introspection of an app model -- what concepts/flows/events/etc. already exist.

    Gives an authoring agent the "does X already exist?" surface so it extends the model instead
    of duplicating a concept. Structural summary only; use `inspect bonds` for bond/anchor detail
    and `validate model --semantic` for correctness.
    """
    model_path = Path(args.model).expanduser().resolve()
    model = resolve_split_model(model_path)

    def names(key: str) -> list:
        return [item.get("name") for item in (model.get(key) or [])
                if isinstance(item, dict) and item.get("name")]

    concepts = []
    bond_count = 0
    for concept in model.get("concepts") or []:
        if not isinstance(concept, dict):
            continue
        fields = []
        id_field = None
        for field in concept.get("fields") or []:
            if not isinstance(field, dict):
                continue
            reference = field.get("reference") if isinstance(field.get("reference"), dict) else None
            target = (reference or {}).get("target") or field.get("referenceTarget")
            if field.get("type") == "reference" or target:
                bond_count += 1
            if field.get("id"):
                id_field = field.get("name")
            fields.append({
                "name": field.get("name"),
                "type": field.get("type"),
                "required": bool(field.get("required", False)),
                "unique": bool(field.get("unique", False)),
                "id": bool(field.get("id", False)),
                "referenceTarget": target,
            })
        concepts.append({
            "name": concept.get("name"),
            "truthLevel": concept.get("truthLevel", "T1"),
            "idField": id_field,
            "fieldCount": len(fields),
            "fields": fields,
        })

    flows = []
    for flow in model.get("flows") or []:
        if not isinstance(flow, dict):
            continue
        input_node = flow.get("input") if isinstance(flow.get("input"), dict) else {}
        flows.append({
            "name": flow.get("name"),
            "concept": flow.get("concept") or input_node.get("concept"),
            "stepCount": len(flow.get("steps") or []),
        })

    write_or_print_json(
        {
            "model": str(model_path),
            "namespace": model.get("namespace") or model.get("model"),
            "version": model.get("version"),
            "counts": {
                "concepts": len(concepts),
                "bonds": bond_count,
                "flows": len(flows),
                "events": len(model.get("events") or []),
                "panels": len(model.get("panels") or []),
                "procedures": len(model.get("procedures") or []),
                "capabilities": len(model.get("capabilities") or []),
                "queries": len(model.get("queries") or []),
            },
            "concepts": concepts,
            "flows": flows,
            "events": names("events"),
            "panels": names("panels"),
            "procedures": names("procedures"),
            "capabilities": names("capabilities"),
            "queries": names("queries"),
        },
        args.output,
    )


def build_parser() -> argparse.ArgumentParser:
    # P3 (FIRST_IMPRESSION_PLAN.md I4): the top-level command list used to show all 13 commands
    # with zero descriptions -- a newcomer could not tell which four are theirs. The epilog is the
    # single highest-value string here: it answers "where do I start?", which the bare command list
    # never did. RawDescriptionHelpFormatter is required or argparse collapses the epilog's line
    # breaks into one paragraph.
    parser = argparse.ArgumentParser(
        prog="npdev",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "New here?  npdev init my-app && cd my-app && npdev run app\n"
            "           then open http://localhost:8080\n"
            "\n"
            "Docs: docs/GETTING_STARTED.md, docs/YOUR_FIRST_APP.md"
        ),
    )
    parser.add_argument("--version", action="store_true", help="Print the portable NPDev CLI version.")
    subparsers = parser.add_subparsers(dest="command")

    init_parser = subparsers.add_parser(
        "init", help="Scaffold a new NPDev app directory: model, config, a real database, a README, "
                     "and a first git commit (I3)."
    )
    init_parser.add_argument("name", help="Directory to create. Must not exist inside this repo, and "
                                           "must be empty or not yet exist.")
    init_parser.add_argument(
        "--from", dest="from_sample", default=None, metavar="SAMPLE",
        help="Name of a directory under NPDevSamples/ to derive the seed from instead of the "
             "default. Must contain model.json and config.json.",
    )
    init_parser.add_argument(
        "--json", action="store_true",
        help="Emit a single npdev-cli-result.v1 JSON object on stdout instead of the human "
             "summary (Phase 0 I3 -- the human summary is unchanged either way).",
    )
    # W5.1 / E9. The choices come from npdev_engines so the flag, `npdev engines`, doctor's database
    # checks and the Manager's picker cannot disagree about what exists.
    init_parser.add_argument(
        "--engine", default="h2local", choices=npdev_engines.engine_keys(), metavar="ENGINE",
        help="Which database engine this app persists to (default: h2local -- a file, no server to "
             "install). Server engines also accept --db-host/--db-port/--db-user/--db-password. "
             "Run `npdev engines` for what each one is and which are experimental.",
    )
    init_parser.add_argument("--db-host", default=None,
                             help="Database host for a server engine (default: localhost).")
    init_parser.add_argument("--db-port", default=None, type=int,
                             help="Database port for a server engine (default: the engine's standard port).")
    init_parser.add_argument("--db-user", default=None,
                             help="Database user for a server engine.")
    init_parser.add_argument("--db-password", default=None,
                             help="Database password for a server engine.")

    engines_parser = subparsers.add_parser(
        "engines", help="List the database engines an app can use, what each needs, and which are "
                        "still experimental."
    )
    engines_parser.add_argument(
        "--json", action="store_true",
        help="Emit npdev-engine-list.v1 instead of the human table. This is what the Manager's "
             "engine picker is built from -- a hardcoded copy there would be free to drift.",
    )

    setup_parser = subparsers.add_parser(
        "setup", help="Build the runtimehost jars a generated app needs to compile, and the AI "
                      "knowledge index -- no pwsh required (I4)."
    )
    setup_parser.add_argument(
        "--json", action="store_true",
        help="Emit one npdev-cli-event.v1 JSON object per line on stdout as each phase starts/"
             "finishes, then a final npdev-cli-result.v1 object -- narration moves to stderr "
             "(Phase 0 I2). Without this flag, output is unchanged.",
    )
    setup_parser.add_argument(
        "--build-local", action="store_true",
        help="Skip the prebuilt-jars download and always build the runtimehost jars locally "
             "(Phase 0 I5), even on a tagged commit with a published release.",
    )

    doctor_parser = subparsers.add_parser(
        "doctor", help="Check this machine is ready for NPDev -- Java 17, JAVA_HOME agreement, "
                       "Python, git, disk space, staged jars (I5)."
    )
    doctor_parser.add_argument(
        "--json", action="store_true",
        help="Emit a single npdev-cli-result.v1 JSON object (all checks, including passing ones) "
             "on stdout instead of the human summary (Phase 0 I1 -- the human summary is "
             "unchanged either way).",
    )
    doctor_parser.add_argument(
        "--app", default=None, metavar="DIR",
        help="An app directory (or a db.definition.json path) to run the database checks against. "
             "Defaults to the current directory; when no app is found the database checks are "
             "skipped -- a machine with no NPDev app on it is not a broken machine (W5.2).",
    )

    capabilities_parser = subparsers.add_parser(
        "capabilities", help="Show what each storage engine can do -- read from the dialects, so "
                             "it always matches what the generator refuses."
    )
    capabilities_parser.add_argument(
        "--json", action="store_true",
        help="Emit the matrix as JSON (npdev-storage-capability-matrix.v1) instead of the grid.",
    )

    dev_parser = subparsers.add_parser(
        "dev", help="Watch the model and rebuild + restart the app on every save -- the "
                    "change-a-field loop, automatic."
    )
    from dev_loop import add_arguments as _dev_add_arguments  # local import, as elsewhere
    _dev_add_arguments(dev_parser)

    mcp = subparsers.add_parser(
        "mcp", help="Connect an AI client to NPDev's MCP tools (I6)."
    )
    mcp_sub = mcp.add_subparsers(dest="mcp_command")
    mcp_install = mcp_sub.add_parser(
        "install", help="Write (merge, never overwrite) an MCP client config pointing at "
                        "NPDevMcp/server.py."
    )
    mcp_install.add_argument("--client", choices=["claude-code", "claude-desktop"], default=None)
    mcp_install.add_argument(
        "--print", dest="print_only", action="store_true",
        help="Print the config block instead of writing any file (default when --client is omitted).",
    )

    validate = subparsers.add_parser(
        "validate", help="Check a model is correct -- structure and meaning -- without generating."
    )
    validate_sub = validate.add_subparsers(dest="validate_command")
    validate_model = validate_sub.add_parser(
        "model", help="Check a model's structure AND meaning. Exits non-zero on any error."
    )
    validate_model.add_argument("path")
    validate_model.add_argument(
        "--semantic",
        action="store_true",
        help="No-op: full structural + semantic validation (npdev-validation-report.v2) is now the "
             "default. Kept as a documented alias so existing scripts and the MCP tool keep working "
             "unchanged.",
    )
    validate_model.add_argument(
        "--structural-only",
        action="store_true",
        help="Skip semantic validation and run JSON-Schema structural checks only (the fast path; "
             "no Gradle invocation). The success message says explicitly that semantic checks did "
             "not run.",
    )
    validate_model.add_argument(
        "--report",
        help="Write the typed validation report to this path (in addition to stdout). Ignored with "
             "--structural-only, which has no typed report to write.",
    )

    normalize = subparsers.add_parser(
        "normalize", help="Rewrite an AI-authored model into canonical form."
    )
    normalize_sub = normalize.add_subparsers(dest="normalize_command")
    normalize_ai = normalize_sub.add_parser("ai-model")
    normalize_ai.add_argument("path")
    normalize_ai.add_argument("--output")

    migrate = subparsers.add_parser(
        "migrate", help="Rewrite existing models after a breaking DSL change (npdev migrate dsl-2)."
    )
    migrate_sub = migrate.add_subparsers(dest="migrate_command")
    migrate_legacy = migrate_sub.add_parser("legacy-model")
    migrate_legacy.add_argument("--input", required=True)
    migrate_legacy.add_argument("--output", required=True)

    migrate_dsl2 = migrate_sub.add_parser("dsl-2")
    migrate_dsl2.add_argument(
        "--input", required=True, nargs="+",
        help="one or more files or directories (searched recursively for *.json) to migrate",
    )
    migrate_dsl2.add_argument(
        "--write", action="store_true",
        help="apply changes in place; without this flag, reports what would change and exits",
    )
    migrate_dsl2.add_argument("--report", help="write a JSON report of every file's outcome to this path")

    migrate_bc = migrate_sub.add_parser("bounded-contexts")
    migrate_bc.add_argument(
        "--input", required=True, nargs="+",
        help="one or more directories, each containing exactly one model.json to migrate",
    )
    migrate_bc.add_argument(
        "--write", action="store_true",
        help="apply changes in place (relocates files and writes JSON); without this flag, reports "
             "what would change and exits",
    )
    migrate_bc.add_argument("--report", help="write a JSON report of every model's outcome to this path")

    migration = subparsers.add_parser(
        "migration", help="Classify a schema change as safe or destructive; dry-run the migration plan."
    )
    migration_sub = migration.add_subparsers(dest="migration_command")
    migration_diff = migration_sub.add_parser("diff")
    migration_diff.add_argument("--baseline", required=True)
    migration_diff.add_argument("--current", required=True)
    migration_diff.add_argument("--output")
    migration_diff.add_argument("--decision-report", dest="decision_report")

    # AI_AUTHORING_CONTRACT-2026-07-31.md Part 9 (E2/E4/E5): the Custodian's diff gate.
    author = subparsers.add_parser(
        "author", help="Submit a model change through the authoring gate, or diff it against a baseline."
    )
    author_sub = author.add_subparsers(dest="author_command")
    author_diff_gate = author_sub.add_parser("diff-gate")
    author_diff_gate.add_argument("--previous", required=True)
    author_diff_gate.add_argument("--submitted", required=True)
    author_diff_gate.add_argument("--manifest", help="Submission manifest (npdev-authoring-submission.v1). Omitting it is itself refused (C1).")
    author_diff_gate.add_argument("--output", help="Directory the report is written under (default build/npdev-authoring).")
    _add_merge_args(author_diff_gate)
    author_submit = author_sub.add_parser("submit")
    author_submit.add_argument("--previous", required=True)
    author_submit.add_argument("--submitted", required=True)
    author_submit.add_argument("--manifest", required=True, help="Required (E4/C1): an Author cannot submit without one.")
    author_submit.add_argument("--output", help="Directory the report is written under (default build/npdev-authoring).")
    author_submit.add_argument("--archive-dir", dest="archive_dir",
                                help="Where the previous model is archived on acceptance (E5). Default: <previous's app root>/model-history/.")
    _add_merge_args(author_submit)

    inspect = subparsers.add_parser(
        "inspect", help="Show what a model already contains: concepts, fields, flows, events, bonds."
    )
    inspect_sub = inspect.add_subparsers(dest="inspect_command")
    inspect_bonds_parser = inspect_sub.add_parser("bonds")
    inspect_bonds_parser.add_argument("--model", required=True)
    inspect_bonds_parser.add_argument("--output")

    inspect_app_parser = inspect_sub.add_parser("app")
    inspect_app_parser.add_argument("--model", required=True)
    inspect_app_parser.add_argument("--output")

    generate = subparsers.add_parser(
        "generate", help="Generate a complete, runnable app (or a single screen) from a model."
    )
    generate_sub = generate.add_subparsers(dest="generate_command")
    generate_app = generate_sub.add_parser(
        "app", help="Generate a full Spring Boot app from a model + config."
    )
    generate_app.add_argument("--model", required=True)
    generate_app.add_argument("--config", required=True)
    generate_app.add_argument("--output", required=True)
    generate_app.add_argument(
        "--require-db-definition",
        action="store_true",
        help="Fail if db.definition.json is missing instead of defaulting to an InMemory database definition.",
    )

    run = subparsers.add_parser(
        "run", help="Generate, build, boot and health-check an app in one command."
    )
    run_sub = run.add_subparsers(dest="run_command")
    run_app = run_sub.add_parser(
        "app", help="Move 10 D1 (LC-D1): generate + build + boot + health-check an app in one command."
    )
    run_app.add_argument(
        "--model", required=False,
        help="Defaults to ./model.json in the current directory (I3: so `npdev init my-app && "
             "cd my-app && npdev run app` works with no flags).",
    )
    run_app.add_argument(
        "--config", required=False,
        help="Defaults to ./config.json in the current directory.",
    )
    run_app.add_argument(
        "--output", required=False,
        help="Defaults to a sibling directory named '<current-directory-name>-app' -- the same "
             "convention docs/YOUR_FIRST_APP.md's own manual steps use (e.g. my-library -> "
             "../my-library-app), so the inferred path is not a surprise to anyone who read that page.",
    )
    run_app.add_argument(
        "--require-db-definition",
        action="store_true",
        help="Fail if db.definition.json is missing instead of defaulting to an InMemory database definition.",
    )
    run_app.add_argument(
        "--port", type=int, default=8080,
        help="Server port to boot on (default 8080 -- matches application.yml's own default and "
             "the generated Dockerfile/.env.example, per FIRST_IMPRESSION_PLAN.md I3: one port "
             "everywhere, not a CLI-only 8180 nobody else uses).",
    )
    run_app.add_argument(
        "--profile", default="dev",
        help="Spring profile to activate at boot (--spring.profiles.active=<profile>). Default 'dev' -- "
             "the generated app's dev-key API-key mapping and other dev-only conveniences live in "
             "application-dev.yml/.properties and are NOT loaded under no active profile at all.",
    )
    run_app.add_argument(
        "--timeout", type=int, default=420,
        help="Overall wall-clock budget in seconds across GENERATE+BUILD+BOOT (default 420). Exceeding it "
             "is reported as a BOOT_TIMEOUT diagnostic and guarantees teardown of any JVM this run started.",
    )
    run_app.add_argument(
        "--baseline-model",
        help="Optional: a previously-generated model.json to diff --model against. If the change "
             "classifies as METADATA_ONLY (Move 10 C1), takes the C2 fast path (swap compiled-model.json "
             "+ re-sign, skip full BUILD) instead of a full GENERATE+BUILD. Omit for a first-time "
             "generation or when you want the full pipeline unconditionally.",
    )
    run_app.add_argument(
        "--keep-running", action="store_true",
        help="Do not stop a pre-existing instance already listening on --port before attempting to boot "
             "(default: PORT_IN_USE is refused outright, matching a fresh CI-style run's expectations).",
    )

    acceptance = subparsers.add_parser(
        "acceptance", help="Run the acceptance suite against a running app."
    )
    acceptance_sub = acceptance.add_subparsers(dest="acceptance_command")
    acceptance_run = acceptance_sub.add_parser(
        "run", help="Move 10 D2 (LC-D2): boot an app (via D1) and run declarative *.scenario.json acceptance scenarios against it."
    )
    acceptance_run.add_argument(
        "--base-url",
        help="Move 14 Phase D item D1: run scenarios against an ALREADY-RUNNING app at this base URL "
             "instead of booting one -- for a caller that already has one up (e.g. the T1 fast gate "
             "reusing its own canary boot) and does not want a second generate+build+boot cycle. "
             "When given, --model/--config/--output are not required and are ignored.",
    )
    acceptance_run.add_argument("--model", required=False)
    acceptance_run.add_argument("--config", required=False)
    acceptance_run.add_argument("--output", required=False)
    acceptance_run.add_argument("--scenarios", required=True, help="Directory containing *.scenario.json files.")
    acceptance_run.add_argument(
        "--require-db-definition", action="store_true",
        help="Fail if db.definition.json is missing instead of defaulting to an InMemory database definition.",
    )
    acceptance_run.add_argument("--port", type=int, default=8080)
    acceptance_run.add_argument("--timeout", type=int, default=420)
    acceptance_run.add_argument("--profile", default="dev")
    acceptance_run.add_argument(
        "--api-key", default="dev-key",
        help="X-Api-Key header value used for every seed/when HTTP call (default 'dev-key', which "
             "the 'dev' profile maps to a developer/admin identity). Empty string sends no header.",
    )
    acceptance_run.add_argument("--baseline-model")
    acceptance_run.add_argument("--keep-running", action="store_true")

    loop = subparsers.add_parser(
        "loop", help="Run the closed-loop authoring/validation cycle end to end."
    )
    loop_sub = loop.add_subparsers(dest="loop_command")
    loop_run = loop_sub.add_parser(
        "run", help="Move 10 D3 (LC-D3): the closed AI loop end to end -- diff-gate -> validate -> "
                    "classify -> run+acceptance, stopping at the earliest gate that refuses."
    )
    loop_run.add_argument("--previous", required=True, help="The app's currently-live/previously-accepted model.json.")
    loop_run.add_argument("--submitted", required=True, help="The Author's newly-submitted model.json.")
    loop_run.add_argument("--manifest", help="Submission manifest (npdev-authoring-submission.v1). Omitting it is itself refused at the diff-gate (C1).")
    loop_run.add_argument("--diff-gate-output", help="Directory the diff-gate's own report is written under.")
    loop_run.add_argument("--config", required=True)
    loop_run.add_argument("--output", required=True)
    loop_run.add_argument("--scenarios", required=True, help="Directory containing *.scenario.json acceptance scenarios.")
    loop_run.add_argument("--require-db-definition", action="store_true")
    loop_run.add_argument("--port", type=int, default=8080)
    loop_run.add_argument("--timeout", type=int, default=420)
    loop_run.add_argument("--profile", default="dev")
    loop_run.add_argument("--api-key", default="dev-key")
    loop_run.add_argument("--keep-running", action="store_true")

    generate_screen = generate_sub.add_parser(
        "screen", help="Generate one hand-written screen against a live app's UI contract (R-P4)."
    )
    generate_screen.add_argument("--app", required=True, help="Base URL of the running app, e.g. http://localhost:8100")
    generate_screen.add_argument("--concept", required=True, help="Concept the bundle should be scoped to")
    generate_screen.add_argument("--out", required=True, help="Where to write the screen, e.g. web/inventario.html")
    generate_screen.add_argument("--api-key", help="X-Api-Key value, for apiKey-mode apps")
    generate_screen.add_argument("--token-file", help="Path to a file holding a JWT bearer token, for jwt-mode apps")
    generate_screen.add_argument(
        "--model-command",
        help="Shell-parsed (shlex) command that reads the assembled prompt on stdin and prints "
             '{"html": ..., "panelJson": ...} JSON to stdout. Omit for the two-step flow (see --from-response).',
    )
    generate_screen.add_argument(
        "--from-response",
        help='Path to a JSON file with {"html": ..., "panelJson": ...}, e.g. produced by hand from '
             "the prompt this command writes when --model-command is omitted.",
    )

    report = subparsers.add_parser(
        "report", help="Produce or bootstrap the evidence and status reports."
    )
    report_sub = report.add_subparsers(dest="report_command")
    report_sub.add_parser("bootstrap", help="Regenerate the maintainer status reports under scripts/reports/out/.")

    verify = subparsers.add_parser(
        "verify", help="Run a verification tier: T0 inner loop, T1 fast gate, T2 full, T3 release."
    )
    verify.add_argument("--tier", required=True, choices=["T0", "T1", "T2", "T3"],
                         help="Fast Lane plan tiers -- T0 inner loop, T1 fast gate (one canary app), "
                              "T2 full gate (run-all-gates.ps1), T3 release ceremony.")
    verify.add_argument("--model-path", help="T0/T1: the model.json currently being edited.")
    verify.add_argument("--dsl-test-filter", help="T0/T1: --tests filter for gradlew :NPDevContract:dsl:test.")
    verify.add_argument("--generate-reports", action="store_true",
                         help="T3 only: also run the ~540s release-evidence orchestration, not just "
                              "evaluate what evidence already exists.")

    review = subparsers.add_parser(
        "review", help="Build a review pack, or ingest a review verdict."
    )
    review_sub = review.add_subparsers(dest="review_command")
    review_pack = review_sub.add_parser("pack")
    review_pack.add_argument("--mission-id", required=True)
    review_pack.add_argument("--commit", help="override the mission's pinned commit")
    review_pack.add_argument("--paths", nargs="*", help="additional/override repo-relative paths")
    review_pack.add_argument("--repo-root")
    review_pack.add_argument("--output-dir")

    review_ingest = review_sub.add_parser("ingest")
    review_ingest.add_argument("--mission-id", required=True)
    review_ingest.add_argument("--vendor-id", required=True)
    review_ingest.add_argument("--verdict-file", required=True)
    review_ingest.add_argument(
        "--pack-manifest-sha256",
        help="required unless the verdict file itself carries packManifestSha256",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.version:
            # REG-130 (firstrun-helpers/close-five/PLAN.md I4): three numbers, no stated
            # relationship, and a user filing a bug could not tell which one to cite. Shape 2 from
            # the plan's own two options: keep all three (collapsing to one file couples the CLI's
            # release cadence to the DSL jar's, and touches the release process) but make --version
            # name each one AND mark which is citable.
            print(f"npdev {VERSION}                 (portable CLI)")
            print(f"  model format  dslVersion {DSL_MODEL_FORMAT_VERSION}")
            print(f"  DSL compiler  {DSL_COMPILER_VERSION}")
            print(f"  platform      {_platform_release_tag()}  <- cite THIS in a bug report")
            return 0
        if args.command == "validate" and args.validate_command == "model":
            if getattr(args, "structural_only", False):
                validate_official_model(Path(args.path).expanduser())
                print("schema validation passed (semantic checks NOT run -- re-run without "
                      "--structural-only)")
                return 0
            # F1: full structural + semantic validation is the default -- a schema-only pass used
            # to print an unqualified "model validation passed" for a model with real semantic
            # errors (e.g. an invariant referencing a field that doesn't exist). --semantic is kept
            # as a no-op alias: it already asked for this, so it changes nothing.
            report_out = Path(args.report).expanduser() if args.report else None
            return run_validate_semantic(Path(args.path).expanduser(), report_out)
        if args.command == "normalize" and args.normalize_command == "ai-model":
            write_or_print_json(normalize_ai_model(Path(args.path).expanduser()), args.output)
            return 0
        if args.command == "migrate" and args.migrate_command == "legacy-model":
            migrate_legacy_model(args)
            return 0
        if args.command == "migrate" and args.migrate_command == "dsl-2":
            return run_migrate_dsl2(args)
        if args.command == "migrate" and args.migrate_command == "bounded-contexts":
            return run_migrate_bounded_contexts(args)
        if args.command == "migration" and args.migration_command == "diff":
            run_migration_diff(args)
            return 0
        if args.command == "author" and args.author_command == "diff-gate":
            return run_author_diff_gate(args)
        if args.command == "author" and args.author_command == "submit":
            return run_author_submit(args)
        if args.command == "inspect" and args.inspect_command == "bonds":
            inspect_bonds(args)
            return 0
        if args.command == "inspect" and args.inspect_command == "app":
            inspect_app(args)
            return 0
        if args.command == "init":
            return run_init(args)
        if args.command == "setup":
            return run_setup(args)
        if args.command == "dev":
            # Reuse run_app's CWD inference rather than a second rule: `npdev init my-app &&
            # cd my-app && npdev dev` must work with no flags, and two ways of finding
            # model.json would drift.
            inference_error = _infer_run_app_paths(args)
            if inference_error is not None:
                print(json.dumps({"ok": False, "diagnostics": [inference_error]}, indent=2))
                return 2
            from dev_loop import dev as _dev_run  # local import, as elsewhere
            return _dev_run(args, sys.modules[__name__])
        if args.command == "capabilities":
            return run_capabilities(args)
        if args.command == "engines":
            return run_engines(args)
        if args.command == "doctor":
            return run_doctor(args)
        if args.command == "mcp" and args.mcp_command == "install":
            return run_mcp_install(args)
        if args.command == "generate" and args.generate_command == "app":
            run_generate(args)
            return 0
        if args.command == "run" and args.run_command == "app":
            result = run_app(args)
            print(json.dumps(result, indent=2))
            # Matches the platform's established run_cli convention (0 = fully ok, 2 = ran fine and
            # reported a real, structured problem -- never exit 1 for a diagnosed failure, only for
            # a genuinely unexpected exception, which the except clauses below still catch).
            return 0 if result.get("ok") else 2
        if args.command == "acceptance" and args.acceptance_command == "run":
            result = run_acceptance(args)
            print(json.dumps(result, indent=2))
            return 0 if result.get("ok") else 2
        if args.command == "loop" and args.loop_command == "run":
            result = run_closed_loop(args)
            print(json.dumps(result, indent=2))
            return 0 if result.get("ok") else 2
        if args.command == "generate" and args.generate_command == "screen":
            return run_generate_screen(args)
        if args.command == "report" and args.report_command == "bootstrap":
            run_report_bootstrap()
            return 0
        if args.command == "verify":
            result = run_verify(args)
            print(json.dumps(result, indent=2))
            return 0 if result.get("ok") else 2
        if args.command == "review" and args.review_command == "pack":
            run_review_pack(args)
            return 0
        if args.command == "review" and args.review_command == "ingest":
            run_review_ingest(args)
            return 0
        parser.print_help()
        return 2
    except subprocess.CalledProcessError as exc:
        print(f"npdev command failed with exit code {exc.returncode}", file=sys.stderr)
        _emit_json_error(args, f"npdev command failed with exit code {exc.returncode}")
        return exc.returncode
    except CliError as exc:
        print(f"npdev: {exc}", file=sys.stderr)
        _emit_json_error(args, str(exc))
        return 1


def _emit_json_error(args: argparse.Namespace | None, message: str) -> None:
    """When --json was requested, a FAILURE must still produce one parseable object on stdout.

    Found while diagnosing the Linux AppImage selftest's step 5/5 ("npdev setup: no JSON output").
    That was not stdout pollution and not a container quirk: every command's JSON emit is the LAST
    statement on its success path, and every error path raises straight past it to main()'s handler,
    which printed prose to stderr and nothing at all to stdout. So `npdev setup --json` on any
    failure produced an EMPTY stdout -- and the Manager's Install screen, which parses exactly that,
    had nothing to show the user but a generic failure.

    That is the silent-answer family inverted: not a wrong answer to a machine caller, but no answer.
    A caller that asked for JSON gets JSON, success or failure.
    """
    if args is None or not getattr(args, "json", False):
        return
    print(json.dumps({
        "schemaVersion": "npdev-cli-result.v1",
        "command": getattr(args, "command", None),
        "ok": False,
        "exitCode": 1,
        "error": {"message": message},
    }))


if __name__ == "__main__":
    raise SystemExit(main())
