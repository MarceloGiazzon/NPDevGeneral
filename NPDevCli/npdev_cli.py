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
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

VERSION = "0.9.0"


class CliError(Exception):
    pass


def repo_root() -> Path:
    env_root = os.environ.get("NPDEV_ROOT")
    if env_root:
        return Path(env_root).expanduser().resolve()
    return Path(__file__).resolve().parents[1]


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


def _ai_build_root() -> Path:
    env = os.environ.get("NPDEV_BUILD_ROOT")
    if env and env.strip():
        return Path(env).expanduser().resolve()
    cursor = repo_root()
    while cursor is not None and cursor.name != "NPDev_General":
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
        # cwd=repo-root (which has no package.json) npm ENOENTs on Windows (D:\...\package.json). REG-33.
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
            for error in errors[:5]
            if isinstance(error, dict)
        )
        raise CliError("canonical model schema validation failed" + (f": {detail}" if detail else ""))
    return result


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


def resolve_split_model(path: Path) -> dict:
    root_path = Path(path).expanduser().resolve(strict=True)
    root_dir = root_path.parent.resolve(strict=True)
    seen: set[Path] = set()

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


def write_temp_model(model: dict, target: Path) -> Path:
    temp = target.parent / (target.name + ".validation.tmp")
    temp.parent.mkdir(parents=True, exist_ok=True)
    temp.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    return temp


def gradle_wrapper(generator_root: Path) -> Path:
    if os.name == "nt":
        return generator_root / "gradlew.bat"
    return generator_root / "gradlew"


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
        command = [str(wrapper), ":generator:run", "--no-daemon", "--console=plain", f"--args={args_str}"]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            command = ["cmd.exe", "/c"] + command
        subprocess.run(command, cwd=generator_root, check=True)


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
    root = repo_root()
    generator_root = root / "NPDevGenerator"
    wrapper = gradle_wrapper(generator_root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")

    baseline = Path(args.baseline).expanduser().resolve()
    current = Path(args.current).expanduser().resolve()
    output = Path(args.output).expanduser().resolve() if args.output else root / "build" / "npdev-migration-diff"
    migrations = output / "db" / "migration"
    snapshot_dir = output / "db" / "schema-snapshots"
    decision_report = Path(args.decision_report).expanduser().resolve() if args.decision_report else output / "migration-diff-decision.json"

    if not baseline.exists():
        raise CliError(f"baseline snapshot not found: {baseline}")
    if not current.exists():
        raise CliError(f"current model not found: {current}")

    snapshot_dir.mkdir(parents=True, exist_ok=True)
    migrations.mkdir(parents=True, exist_ok=True)
    baseline_json = read_json(baseline)
    (snapshot_dir / "latest-storage-schema.json").write_text(json.dumps(baseline_json, indent=2) + "\n", encoding="utf-8")

    generator_args = [
        "--model",
        str(current),
        "--out",
        str(output),
        "--migrationsDir",
        str(migrations),
        "--migrationMode=additive-only",
        "--migrationPlanOnly",
        "--migrationRiskThreshold=" + args.migrationRiskThreshold,
        "--migrationDecisionReport",
        str(decision_report),
        "--no-assembleFinalApp",
        "--no-clean",
    ]
    command = [
        str(wrapper),
        ":generator:run",
        "--no-daemon",
        "--console=plain",
        "--args=" + gradle_args_value(generator_args),
    ]
    if os.name == "nt" and wrapper.suffix.lower() == ".bat":
        command = ["cmd.exe", "/c"] + command
    subprocess.run(command, cwd=generator_root, check=True)
    print(f"migration diff decision: {decision_report}")
    print(f"migration diff dry-run SQL: {output / 'db' / 'migration-plans' / 'latest-model-delta.sql'}")


def run_report_bootstrap() -> None:
    root = repo_root()
    script = root / "scripts" / "quality" / "bootstrap-post-beta0-reports.ps1"
    if not script.exists():
        raise CliError(f"report bootstrap script not found: {script}")
    subprocess.run(["pwsh", "-NoProfile", "-File", str(script)], cwd=root, check=True)


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
    return 2 if report.get("status") == "failed" else 0


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
    parser = argparse.ArgumentParser(prog="npdev")
    parser.add_argument("--version", action="store_true", help="Print the portable NPDev CLI version.")
    subparsers = parser.add_subparsers(dest="command")

    validate = subparsers.add_parser("validate")
    validate_sub = validate.add_subparsers(dest="validate_command")
    validate_model = validate_sub.add_parser("model")
    validate_model.add_argument("path")
    validate_model.add_argument(
        "--semantic",
        action="store_true",
        help="Run full structural + semantic validation and emit a typed npdev-validation-report.v2 report.",
    )
    validate_model.add_argument(
        "--report",
        help="Write the typed validation report to this path (in addition to stdout).",
    )

    normalize = subparsers.add_parser("normalize")
    normalize_sub = normalize.add_subparsers(dest="normalize_command")
    normalize_ai = normalize_sub.add_parser("ai-model")
    normalize_ai.add_argument("path")
    normalize_ai.add_argument("--output")

    migrate = subparsers.add_parser("migrate")
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

    migration = subparsers.add_parser("migration")
    migration_sub = migration.add_subparsers(dest="migration_command")
    migration_diff = migration_sub.add_parser("diff")
    migration_diff.add_argument("--baseline", required=True)
    migration_diff.add_argument("--current", required=True)
    migration_diff.add_argument("--output")
    migration_diff.add_argument("--decision-report", dest="decision_report")
    migration_diff.add_argument("--migrationRiskThreshold", choices=["SAFE_ADDITIVE", "BACKFILL_REQUIRED", "MANUAL_REVIEW"], default="SAFE_ADDITIVE")

    inspect = subparsers.add_parser("inspect")
    inspect_sub = inspect.add_subparsers(dest="inspect_command")
    inspect_bonds_parser = inspect_sub.add_parser("bonds")
    inspect_bonds_parser.add_argument("--model", required=True)
    inspect_bonds_parser.add_argument("--output")

    inspect_app_parser = inspect_sub.add_parser("app")
    inspect_app_parser.add_argument("--model", required=True)
    inspect_app_parser.add_argument("--output")

    generate = subparsers.add_parser("generate")
    generate_sub = generate.add_subparsers(dest="generate_command")
    generate_app = generate_sub.add_parser("app")
    generate_app.add_argument("--model", required=True)
    generate_app.add_argument("--config", required=True)
    generate_app.add_argument("--output", required=True)
    generate_app.add_argument(
        "--require-db-definition",
        action="store_true",
        help="Fail if db.definition.json is missing instead of defaulting to an InMemory database definition.",
    )

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

    report = subparsers.add_parser("report")
    report_sub = report.add_subparsers(dest="report_command")
    report_sub.add_parser("bootstrap")

    review = subparsers.add_parser("review")
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
            print(f"npdev {VERSION}")
            return 0
        if args.command == "validate" and args.validate_command == "model":
            if getattr(args, "semantic", False):
                report_out = Path(args.report).expanduser() if args.report else None
                return run_validate_semantic(Path(args.path).expanduser(), report_out)
            validate_official_model(Path(args.path).expanduser())
            print("model validation passed")
            return 0
        if args.command == "normalize" and args.normalize_command == "ai-model":
            write_or_print_json(normalize_ai_model(Path(args.path).expanduser()), args.output)
            return 0
        if args.command == "migrate" and args.migrate_command == "legacy-model":
            migrate_legacy_model(args)
            return 0
        if args.command == "migrate" and args.migrate_command == "dsl-2":
            return run_migrate_dsl2(args)
        if args.command == "migration" and args.migration_command == "diff":
            run_migration_diff(args)
            return 0
        if args.command == "inspect" and args.inspect_command == "bonds":
            inspect_bonds(args)
            return 0
        if args.command == "inspect" and args.inspect_command == "app":
            inspect_app(args)
            return 0
        if args.command == "generate" and args.generate_command == "app":
            run_generate(args)
            return 0
        if args.command == "generate" and args.generate_command == "screen":
            return run_generate_screen(args)
        if args.command == "report" and args.report_command == "bootstrap":
            run_report_bootstrap()
            return 0
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
        return exc.returncode
    except CliError as exc:
        print(f"npdev: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
