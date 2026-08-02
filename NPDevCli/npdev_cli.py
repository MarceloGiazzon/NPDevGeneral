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


def run_app(args: argparse.Namespace) -> dict:
    """Move 10 D1 (LC-D1, `npdev_build_and_run`): GENERATE -> BUILD -> BOOT -> READY, one command,
    structured output, five named failure classes, bounded with guaranteed teardown."""
    global _RUN_APP_CHILD_PROCESS
    import time
    import urllib.error
    import urllib.request

    result: dict = {"phase": "GENERATE", "ok": False, "diagnostics": [], "baseUrl": None, "logExcerpt": None}
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
        command = [str(wrapper), ":generator:run", "--no-daemon", "--console=plain", f"--args={args_str}"]
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


def _build_phase(app_root: Path, deadline: float) -> tuple[bool, str, Path | None]:
    wrapper = app_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.exists():
        return False, f"Gradle wrapper not found in generated app: {wrapper}", None
    env = dict(os.environ)
    env.setdefault("NPDEV_RUNTIMEHOST_LIBS_DIR", str(Path("D:/WorkSpace/NPDev/Build/runtimehost-libs")))
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
            str(wrapper), ":generator:classifyModelChange", "--no-daemon", "--console=plain",
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
            str(wrapper), ":generator:classifyModelChange", "--no-daemon", "--console=plain",
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
        str(kernel_wrapper), ":adapters:runtime-validation:resignGeneratedFolder",
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
    "Unapproved scenarios are visibly excluded from the pass count")."""
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

    command = [
        str(wrapper),
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
    return {
        "ok": exit_code == 0 and cadence_passed,
        "tier": tier,
        "gateExitCode": exit_code,
        "cadence": cadence_report,
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

    # AI_AUTHORING_CONTRACT-2026-07-31.md Part 9 (E2/E4/E5): the Custodian's diff gate.
    author = subparsers.add_parser("author")
    author_sub = author.add_subparsers(dest="author_command")
    author_diff_gate = author_sub.add_parser("diff-gate")
    author_diff_gate.add_argument("--previous", required=True)
    author_diff_gate.add_argument("--submitted", required=True)
    author_diff_gate.add_argument("--manifest", help="Submission manifest (npdev-authoring-submission.v1). Omitting it is itself refused (C1).")
    author_diff_gate.add_argument("--output", help="Directory the report is written under (default build/npdev-authoring).")
    author_submit = author_sub.add_parser("submit")
    author_submit.add_argument("--previous", required=True)
    author_submit.add_argument("--submitted", required=True)
    author_submit.add_argument("--manifest", required=True, help="Required (E4/C1): an Author cannot submit without one.")
    author_submit.add_argument("--output", help="Directory the report is written under (default build/npdev-authoring).")
    author_submit.add_argument("--archive-dir", dest="archive_dir",
                                help="Where the previous model is archived on acceptance (E5). Default: <previous's app root>/model-history/.")

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

    run = subparsers.add_parser("run")
    run_sub = run.add_subparsers(dest="run_command")
    run_app = run_sub.add_parser(
        "app", help="Move 10 D1 (LC-D1): generate + build + boot + health-check an app in one command."
    )
    run_app.add_argument("--model", required=True)
    run_app.add_argument("--config", required=True)
    run_app.add_argument("--output", required=True)
    run_app.add_argument(
        "--require-db-definition",
        action="store_true",
        help="Fail if db.definition.json is missing instead of defaulting to an InMemory database definition.",
    )
    run_app.add_argument("--port", type=int, default=8180, help="Server port to boot on (default 8180).")
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

    acceptance = subparsers.add_parser("acceptance")
    acceptance_sub = acceptance.add_subparsers(dest="acceptance_command")
    acceptance_run = acceptance_sub.add_parser(
        "run", help="Move 10 D2 (LC-D2): boot an app (via D1) and run declarative *.scenario.json acceptance scenarios against it."
    )
    acceptance_run.add_argument("--model", required=True)
    acceptance_run.add_argument("--config", required=True)
    acceptance_run.add_argument("--output", required=True)
    acceptance_run.add_argument("--scenarios", required=True, help="Directory containing *.scenario.json files.")
    acceptance_run.add_argument(
        "--require-db-definition", action="store_true",
        help="Fail if db.definition.json is missing instead of defaulting to an InMemory database definition.",
    )
    acceptance_run.add_argument("--port", type=int, default=8180)
    acceptance_run.add_argument("--timeout", type=int, default=420)
    acceptance_run.add_argument("--profile", default="dev")
    acceptance_run.add_argument(
        "--api-key", default="dev-key",
        help="X-Api-Key header value used for every seed/when HTTP call (default 'dev-key', which "
             "the 'dev' profile maps to a developer/admin identity). Empty string sends no header.",
    )
    acceptance_run.add_argument("--baseline-model")
    acceptance_run.add_argument("--keep-running", action="store_true")

    loop = subparsers.add_parser("loop")
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
    loop_run.add_argument("--port", type=int, default=8180)
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

    report = subparsers.add_parser("report")
    report_sub = report.add_subparsers(dest="report_command")
    report_sub.add_parser("bootstrap")

    verify = subparsers.add_parser("verify")
    verify.add_argument("--tier", required=True, choices=["T0", "T1", "T2", "T3"],
                         help="Fast Lane plan tiers -- T0 inner loop, T1 fast gate (one canary app), "
                              "T2 full gate (run-all-gates.ps1), T3 release ceremony.")
    verify.add_argument("--model-path", help="T0/T1: the model.json currently being edited.")
    verify.add_argument("--dsl-test-filter", help="T0/T1: --tests filter for gradlew :NPDevContract:dsl:test.")
    verify.add_argument("--generate-reports", action="store_true",
                         help="T3 only: also run the ~540s release-evidence orchestration, not just "
                              "evaluate what evidence already exists.")

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
        return exc.returncode
    except CliError as exc:
        print(f"npdev: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
