"""S3 (B20 part 2, docs/adr/ADR-0011-bounded-contexts.md): wraps an existing model's whole
authored content into ONE new bounded context -- the codemod a third person needs the first time
they split a domain (S3_SPEC.md Section 2). Reads/writes raw, author-facing model documents; the
CLI layer (`npdev_cli.py`, `migrate bounded-contexts`) does the file-walking/argument-handling.

DESIGN PRINCIPLES (all load-bearing, mirrors dsl_v2_migration.py's discipline)
-----------------------------------------------------------------------------
1. Reports, does not guess. A model that already declares `contexts[]` is left untouched and
   reported as an ambiguity -- this tool never decides which existing context "the" content belongs
   to. A `$ref` this tool cannot confidently relocate (missing file, escapes the model root, already
   absolute) is a hard error for the whole migration, never a best-effort partial move (X0).

2. Every `$ref` STRING stays byte-identical. `model.schema.json`'s `localModelRef` definition
   (govern every $ref-typed array item: concepts, flows, queries, panels, fragments, and the context
   entry's own $ref) has the pattern
   `^(?![A-Za-z][A-Za-z0-9+.-]*:)(?!/)(?!.*(?:^|/)\\.\\.(?:/|$)).*\\.json$` -- the third negative
   lookahead unconditionally forbids any `..` segment, and `loadPackJson`
   (ModelSourceResolver.java) schema-validates a context fragment's RAW json (before resolving
   anything inside it) against exactly that pattern. So the naive "prepend ../ per directory level"
   rewrite is schema-invalid, not just risky -- this module instead PHYSICALLY RELOCATES the files a
   moved `$ref` points at into a `contexts/<name>/` subtree that mirrors their original relative
   layout, so no `$ref` string ever needs to change.

3. Only content `pack.schema.json` actually supports moves. `contexts[]` fragments are pack-shaped
   (D2) and schema-validated as such -- moving a field the schema doesn't recognize as a pack
   property (e.g. `guidePages`, present in `ModelSourceResolver.MODEL_ARRAY_KEYS` but absent from
   `pack.schema.json`) would fail validation. `roles`/`propertyScopes`/`properties` ARE schema-legal
   pack properties but are deliberately NOT moved -- ADR-0011's own v1 non-goal: "No per-context
   permissions/roles -- roles[]/RolePermissions stay app-global." `packs[]` can never move at all:
   `pack.schema.json` has no `packs` property, so a context fragment cannot declare pack imports of
   its own; every pack `$ref` always stays at the (unmoved) model root.

4. Table names/behavior are unaffected. ADR-0011 D4 (fixed alongside this codemod, S3 Increment 0)
   means the migrated model's tables/generic-CRUD REST routes/DB schema stay identical. Concept
   `name`/generated Java class name DO change (D1's `context::Concept` qualification is the whole
   point of a context) -- an accepted, disclosed consequence, not a bug this tool works around.
"""

from __future__ import annotations

import json
import re
import shutil
from dataclasses import dataclass, field as dataclass_field
from pathlib import Path

# pack.schema.json properties this codemod will actually move into the new context fragment --
# ModelSourceResolver.MODEL_ARRAY_KEYS minus {roles, propertyScopes, properties} (ADR-0011's own
# v1 non-goal, kept app-global) minus {guidePages} (in MODEL_ARRAY_KEYS but NOT a pack.schema.json
# property -- moving it would fail schema validation) plus {fragments} (a pack.schema.json property,
# resolved before the MODEL_ARRAY_KEYS merge so it's absent from that Java set, but still legitimately
# movable content).
MOVABLE_KEYS = (
    "concepts", "domainTypes", "capabilities", "customCapabilities", "bindings", "events", "flows",
    "orchestrationRules", "orchestrations", "queries", "ruleProfiles", "procedures", "panels",
    "fragments",
)

DEFAULT_CONTEXT_DSL_VERSION = "1.0.0"


class UnresolvableRefError(Exception):
    """A $ref this tool cannot confidently relocate -- X0: an error, never a best-effort guess."""


@dataclass
class MigrationResult:
    changed: bool = False
    skipped: bool = False
    changes: list[str] = dataclass_field(default_factory=list)
    ambiguities: list[str] = dataclass_field(default_factory=list)


@dataclass
class MigrationPlan:
    result: MigrationResult
    context_name: str | None = None
    context_ref: str | None = None          # relative to base_dir, e.g. "contexts/wms.model.json"
    root_doc: dict | None = None
    context_doc: dict | None = None
    file_moves: list[tuple[Path, Path]] = dataclass_field(default_factory=list)
    context_dir: Path | None = None         # None for the flat (no-$ref) shape


def _infer_model_name(namespace: str) -> str:
    """Ports FileSystemModelRepository.inferModelName's namespace-sanitizing branch EXACTLY
    (namespace.trim().replaceAll("[^a-zA-Z0-9._-]", "_")) -- the codemod always has a namespace by
    the time it runs (JsonModelParser already requires one), so the file-stem fallback branch of the
    Java method is not needed here."""
    return re.sub(r"[^a-zA-Z0-9._-]", "_", namespace.strip())


def _context_entry_name(namespace: str, result: MigrationResult) -> str:
    """The {name, $ref} entry's `name` -- schema pattern ^[A-Za-z][A-Za-z0-9_-]*$
    (model.schema.json's `context` def), stricter than inferModelName's filesystem-safety sanitizer
    (which allows dots). This collapse is a NECESSARY additional step for schema conformance, not a
    second sanitizer for the same job -- inferModelName answers "is this safe as a filename",  this
    answers "is this legal as a context identifier", two different questions with two different
    answers for e.g. a dotted namespace like "sample.splitmodel.store"."""
    base = _infer_model_name(namespace)
    fixed = re.sub(r"[^A-Za-z0-9_-]", "_", base)
    if not re.match(r"^[A-Za-z]", fixed):
        fixed = "ctx_" + fixed
    if fixed != base:
        result.changes.append(
            f"context name: sanitized '{base}' -> '{fixed}' (context.name schema pattern forbids "
            f"dots and requires a leading letter)"
        )
    return fixed


def _context_pack_id(context_name: str) -> str:
    """The context fragment's own internal `pack` field -- required by pack.schema.json (pattern
    ^[a-z][a-z0-9_-]*$, lowercase-start) but never read for qualification: confirmed against
    ModelSourceResolver.resolveContexts, which qualifies using the ROOT {name, $ref} entry's `name`
    only, never the fragment's own `pack` field. Lowercased purely to satisfy the schema."""
    lowered = context_name.lower()
    if not re.match(r"^[a-z]", lowered):
        lowered = "ctx_" + lowered
    return lowered


def _is_local_ref_item(item) -> bool:
    return isinstance(item, dict) and set(item.keys()) == {"$ref"}


def _has_any_ref_item(doc: dict) -> bool:
    for key in MOVABLE_KEYS:
        array = doc.get(key)
        if isinstance(array, list) and any(_is_local_ref_item(item) for item in array):
            return True
    return False


def _discover_relocations(
        entry_file: Path,
        base_dir: Path,
        context_dir: Path,
        moves: dict[Path, Path],
        visited: set[Path],
        result: MigrationResult,
) -> None:
    """Recursively finds every file reachable from entry_file via $ref (any array-valued key --
    a moved fragment isn't constrained to MOVABLE_KEYS' names, e.g. a plugin fragment's own
    top-level key is "capabilities" or "fragments") and plans moving each one to the SAME relative
    sub-path under context_dir it currently has under base_dir -- so every $ref string inside stays
    byte-identical; only where it resolves FROM changes."""
    entry_file = entry_file.resolve()
    if entry_file in visited:
        return
    visited.add(entry_file)

    try:
        rel = entry_file.relative_to(base_dir.resolve())
    except ValueError as exc:
        raise UnresolvableRefError(
            f"{entry_file} resolves outside the model root {base_dir} -- refusing to relocate"
        ) from exc

    moves[entry_file] = context_dir / rel

    if not entry_file.is_file():
        raise UnresolvableRefError(f"$ref target does not exist: {entry_file}")

    try:
        nested_doc = json.loads(entry_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise UnresolvableRefError(f"$ref target is not valid JSON: {entry_file} ({exc})") from exc

    if not isinstance(nested_doc, dict):
        return

    for value in nested_doc.values():
        if not isinstance(value, list):
            continue
        for item in value:
            if not _is_local_ref_item(item):
                continue
            ref = item["$ref"]
            if Path(ref).is_absolute():
                raise UnresolvableRefError(f"{entry_file}: $ref '{ref}' is absolute -- refusing to relocate")
            nested_file = (entry_file.parent / ref).resolve()
            _discover_relocations(nested_file, base_dir, context_dir, moves, visited, result)


def _qualify_bare_concept_ref(value: str, context_name: str) -> str:
    if isinstance(value, str) and value and "::" not in value:
        return f"{context_name}::{value}"
    return value


def _qualify_aggregate_collection(collection: dict, context_name: str, path: str, result: MigrationResult) -> None:
    old = collection.get("concept")
    if isinstance(old, str):
        new = _qualify_bare_concept_ref(old, context_name)
        if new != old:
            collection["concept"] = new
            result.changes.append(f"{path}.concept: qualified '{old}' -> '{new}'")
    for i, nested in enumerate(collection.get("collections") or []):
        if isinstance(nested, dict):
            _qualify_aggregate_collection(nested, context_name, f"{path}.collections[{i}]", result)


def _qualify_aggregates(root_doc: dict, context_name: str, result: MigrationResult) -> None:
    """`aggregates[]` is NOT a pack.schema.json property (unlike concepts/flows/queries/...), so it
    can never be declared inside a context fragment and always stays at the model root, unmoved --
    but its `root`/`collections[].concept` fields name a concept by BARE name, and every concept just
    moved into (and got qualified by) the new context. Left alone, `aggregates[]` would dangle
    ("root concept not found") the instant ANY concept moves -- found empirically on the WmsOffice
    trial (13 dangling references across 5 aggregates), not by inspection. Every concept in this
    codemod always moves as one atomic unit (no partial split), so any bare (not already `::`-
    qualified) reference here can only mean a concept that just moved -- qualify it unconditionally."""
    aggregates = root_doc.get("aggregates")
    if not isinstance(aggregates, list):
        return
    for i, aggregate in enumerate(aggregates):
        if not isinstance(aggregate, dict):
            continue
        path = f"aggregates[{i}] ({aggregate.get('name', '?')})"
        old_root = aggregate.get("root")
        if isinstance(old_root, str):
            new_root = _qualify_bare_concept_ref(old_root, context_name)
            if new_root != old_root:
                aggregate["root"] = new_root
                result.changes.append(f"{path}.root: qualified '{old_root}' -> '{new_root}'")
        for j, collection in enumerate(aggregate.get("collections") or []):
            if isinstance(collection, dict):
                _qualify_aggregate_collection(collection, context_name, f"{path}.collections[{j}]", result)


def plan_migration(doc: dict, base_dir: Path) -> MigrationPlan:
    """Pure planning (bar the read-only filesystem probing needed to discover the $ref graph) --
    computes what WOULD change, performs no writes/moves. `base_dir` is the directory containing the
    model's own root document (e.g. an AppGen app's `definition/` directory)."""
    result = MigrationResult()

    if doc.get("contexts"):
        result.skipped = True
        result.ambiguities.append(
            "model already declares contexts[] -- left untouched, this tool does not guess which "
            "existing context the content belongs to"
        )
        return MigrationPlan(result=result)

    namespace = doc.get("namespace") or doc.get("model")
    if not isinstance(namespace, str) or not namespace.strip():
        result.skipped = True
        result.ambiguities.append("model has no namespace/model field to derive a context name from")
        return MigrationPlan(result=result)

    context_name = _context_entry_name(namespace, result)
    context_pack_id = _context_pack_id(context_name)

    root_doc = dict(doc)
    context_doc: dict = {
        "dslVersion": doc.get("dslVersion", DEFAULT_CONTEXT_DSL_VERSION),
        "pack": context_pack_id,
        "version": doc.get("version") or DEFAULT_CONTEXT_DSL_VERSION,
    }
    if not doc.get("version"):
        result.changes.append(f"context '{context_name}': no root version field -- defaulted to '{DEFAULT_CONTEXT_DSL_VERSION}'")

    moved_any_key = False
    for key in MOVABLE_KEYS:
        if key in root_doc:
            value = root_doc.pop(key)
            context_doc[key] = value
            moved_any_key = True
            result.changes.append(f"moved top-level '{key}' into context '{context_name}'")

    if not moved_any_key:
        result.skipped = True
        result.ambiguities.append("model has no movable content (no concepts/flows/queries/etc.) -- nothing to migrate")
        return MigrationPlan(result=result)

    if "concepts" in context_doc:
        _qualify_aggregates(root_doc, context_name, result)

    plan = MigrationPlan(result=result, context_name=context_name, root_doc=root_doc, context_doc=context_doc)

    if _has_any_ref_item(context_doc):
        # Nested shape: the context gets its own subtree so relocated files' $ref strings stay
        # byte-identical (S3_SPEC.md's own worked example doesn't apply -- see module docstring
        # principle 2 for why "../" is schema-invalid here).
        context_dir = base_dir / "contexts" / context_name
        context_ref = f"contexts/{context_name}/context.model.json"
        plan.context_dir = context_dir
        plan.context_ref = context_ref

        moves: dict[Path, Path] = {}
        visited: set[Path] = set()
        for key in MOVABLE_KEYS:
            array = context_doc.get(key)
            if not isinstance(array, list):
                continue
            for item in array:
                if not _is_local_ref_item(item):
                    continue
                entry_file = (base_dir / item["$ref"]).resolve()
                _discover_relocations(entry_file, base_dir, context_dir, moves, visited, result)
        plan.file_moves = sorted(moves.items())
        for src, dst in plan.file_moves:
            result.changes.append(f"relocate {src.relative_to(base_dir.resolve())} -> {dst.relative_to(base_dir.resolve())}")
    else:
        # Flat shape: pure structural move, nothing to relocate (S3_SPEC.md §2.4's "a model with no
        # $ref migrates as a pure structural move").
        plan.context_ref = f"contexts/{context_name}.model.json"

    root_doc["contexts"] = list(root_doc.get("contexts") or []) + [
        {"name": context_name, "$ref": plan.context_ref}
    ]
    result.changes.append(f"root: added contexts[] entry '{context_name}' -> '{plan.context_ref}'")
    result.changed = True
    return plan


def apply_migration(plan: MigrationPlan, base_dir: Path, model_file: Path) -> None:
    """Performs the file moves and JSON writes a plan describes. Only ever called under --write."""
    if plan.result.skipped:
        return

    for src, dst in plan.file_moves:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))

    context_path = base_dir / plan.context_ref
    context_path.parent.mkdir(parents=True, exist_ok=True)
    context_path.write_text(json.dumps(plan.context_doc, indent=2) + "\n", encoding="utf-8")

    model_file.write_text(json.dumps(plan.root_doc, indent=2) + "\n", encoding="utf-8")


def migrate_document(doc: dict, base_dir: Path) -> MigrationPlan:
    """Top-level entry point matching dsl_v2_migration.py's naming convention. `base_dir` is the
    directory containing `doc`'s own file (an AppGen app's `definition/` directory, typically)."""
    try:
        return plan_migration(doc, base_dir)
    except UnresolvableRefError as exc:
        result = MigrationResult(skipped=True)
        result.ambiguities.append(str(exc))
        return MigrationPlan(result=result)
