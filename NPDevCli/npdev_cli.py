#!/usr/bin/env python3
"""Portable NPDev command entrypoint."""

from __future__ import annotations

import argparse
import contextlib
import copy
import shutil
import hashlib
import json
import os
import re
import secrets
import shlex
import signal
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import NamedTuple

# W5 (storage/FULL_SUPPORT_PLAN.md): the engine list, its per-engine defaults, and the honesty
# status for each, in one module. `npdev init --engine`, `npdev engines`, doctor's database
# checks and the Manager's picker all read it, so none of them can hold a private copy that
# drifts. Imported plainly (not lazily) because argparse needs engine_keys() to build --engine's
# choices at parser-construction time.
import npdev_engines
# MONITOR_PLAN A2/D9/D10. Imported plainly, like npdev_engines and for the same reason: argparse
# needs OPS_SCRIPTS for `monitor ops --script`'s choices and DEFAULT_ENGINE_PORT for two defaults at
# parser-construction time. Stdlib-only inside (R9), so this import cannot fail on a machine that
# has the CLI zip and nothing else.
import npdev_monitor
# `inspect bonds --diagram` renders the SAME bonds/concepts inspect_bonds() already computed as a
# self-contained SVG/HTML page. Stdlib-only inside, same rule as the two imports above.
import npdev_diagram
import npdev_rename_cascade

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


def _document_kind(raw: dict, path: Path) -> str:
    """R1.6 (`npdev impact`): 'pack' or 'model', read from the ROOT document's own declared shape
    -- never resolve_split_model, because a pack.json's own top-level scalar keys (`pack`,
    `category`, `author`, ...) are not in model.schema.json's ROOT_SCALAR_KEYS and resolve_split_model
    would refuse a genuine pack.json outright (it validates against the MODEL shape only). The two
    document kinds are told apart the same way a human would: pack.schema.json REQUIRES a top-level
    string `pack` identifier (the pack's own id) that model.schema.json never defines at all --
    a model's own pack DEPENDENCIES live under the plural `packs` array instead, so there is no
    collision. `classifyModelChange`/`modelXref`/`authorDiffGate` all parse via JsonModelParser,
    which cannot read a pack.json (different required top-level shape); `packDiff` (PackDiffEngine)
    is the reverse -- it has no model.json input at all. This is why `impact` runs a different pair
    of legs per kind rather than always attempting all four."""
    if not isinstance(raw, dict):
        raise CliError(f"{path}: must be a JSON object")
    pack_id = raw.get("pack")
    if isinstance(pack_id, str) and pack_id.strip():
        return "pack"
    return "model"


# R1.5 (roadmap 2026-08-18 R1.5): the 4 of ModelSourceResolver.java's 18 MODEL_ARRAY_KEYS entries
# `npdev add` scaffolds -- not a Python port of the resolver (nothing here composes fragments/
# packs; that stays resolve_split_model's job below), just the kind-name -> top-level-array-key
# agreement for the four member kinds this verb supports.
ADD_MEMBER_ARRAY_KEYS = {
    "concept": "concepts",
    "panel": "panels",
    "flow": "flows",
    "procedure": "procedures",
}

# FlowValidation.BUILTIN_CAPABILITY_OPERATIONS / PackValidation (dsl module): these capability
# names/types resolve even when the model declares no capabilities[] at all -- e.g. every flow's
# createConcept/updateConcept step is backed by the builtin "persistence" capability's "save"
# operation for free, which is exactly why the default flow/procedure stubs below need no
# capabilities[] scaffolding of their own. Mirrored here (lowercase) so --from's self-containment
# scan does not misreport a capabilityCall step as broken just because the model has no
# capabilities[] declared.
ADD_BUILTIN_CAPABILITIES = {
    "persistencecapability", "persistence", "messagingcapability", "emailcapability",
    "fiscalcapability", "signaturecapability", "eventbus", "invariantengine",
}


def _add_humanize_label(name: str) -> str:
    """PascalCase/camelCase/snake_case -> "Spaced label" -- seeds a UX-friendly ui.label/title on
    a scaffolded member so `npdev add`'s own output doesn't ship the missing_concept_label /
    missing_field_label warning every hand-authored corpus model already avoids."""
    spaced = re.sub(r"(?<!^)(?=[A-Z])", " ", name.replace("_", " ")).strip()
    spaced = re.sub(r"\s+", " ", spaced)
    return (spaced[:1].upper() + spaced[1:]) if spaced else name


def _add_kebab_route(name: str) -> str:
    spaced = re.sub(r"(?<!^)(?=[A-Z])", "-", name)
    spaced = re.sub(r"[_\s]+", "-", spaced)
    return "/" + re.sub(r"-+", "-", spaced).strip("-").lower()


def _add_lower_first(name: str) -> str:
    return (name[:1].lower() + name[1:]) if name else name


def validate_json_schema(schema: Path, instance: Path) -> dict:
    root = repo_root()
    validator_root = root / "scripts" / "quality" / "json-schema-validator"
    validator_script = validator_root / "validate-json-schema.mjs"
    node_modules = validator_root / "node_modules"
    # REG-165: a PRESENT-but-broken node_modules (e.g. an interrupted npm install leaving
    # node_modules/ajv/ with only a LICENSE file, no index.js) previously passed this check --
    # Test-Path only looked at the directory, never a specific declared dependency -- so it was
    # never repaired, and every subsequent command failed the same undiagnosable way. Check the one
    # entry point the validator script actually imports, not just the directory's existence.
    ajv_entry = node_modules / "ajv" / "index.js"
    if not validator_script.exists():
        raise CliError(f"JSON Schema validator wrapper not found: {validator_script}")
    if not node_modules.exists() or not ajv_entry.exists():
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
        if not detail and completed.stderr.strip():
            # REG-165: the Node subprocess crashing before it ever printed its JSON result (a
            # missing dependency, a syntax error in the validator script, any uncaught exception)
            # previously fell back to output="{}" -- status None, errors [] -- which read as
            # BYTE-IDENTICAL to "0 errors, still failed" from a genuinely invalid model. Surface the
            # crash text so a broken validator reads as a broken validator, not an unexplained
            # schema failure. Prefer a line naming an Error type over a bare stack frame ("    at
            # ...") -- Node's own uncaught-exception format puts the message before the trace, but a
            # stack frame alone is not a useful error message on its own.
            stderr_lines = [line.strip() for line in completed.stderr.strip().splitlines() if line.strip()]
            error_line = next((line for line in stderr_lines if "Error" in line), None)
            detail = f"validator subprocess crashed: {error_line or stderr_lines[-1]}"
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
    # The 10 real schema array keys this Python whitelist had drifted from -- the exact REG-108
    # failure shape the docstring above already warns about (a THIRD independent copy of "which
    # top-level keys exist", now caught stale against NPDevContract/schemas/model.schema.json's
    # real top-level key list). Found 2026-08-15 when `inspect bonds` refused a real model
    # (WmsOffice) over its `aggregates` key. roles/propertyScopes/properties are the exact three
    # REG-108 named on the Java side; conversions/documents/guidePages/aggregates/autoPanels/
    # selectors/contexts were never added here at all.
    "conversions",
    "documents",
    "guidePages",
    "aggregates",
    "autoPanels",
    "selectors",
    "roles",
    "propertyScopes",
    "properties",
    "contexts",
}
ROOT_SCALAR_KEYS = {
    "$schema", "schemaVersion", "dslVersion", "namespace", "model", "version",
    # Same drift fix as MODEL_ARRAY_KEYS above, for the object-shaped (not array-shaped) top-level
    # keys. `packs` stays listed here so the REGISTRY of pack imports survives into the resolved
    # model verbatim (model.schema.json's `packs` property expects exactly the raw
    # `{"$ref", "as"}` entries) -- REG-186 added the separate composition of pack-CONTRIBUTED
    # members into the model's own arrays below, which is a different thing from passing the
    # declaration through.
    "packs", "provides", "externalAi", "settings",
}
FRAGMENT_KEYS = MODEL_ARRAY_KEYS | {"metadata", "fragments"}

# REG-186: the two top-level arrays whose entries are a $ref PLUS identity keys, rather than the
# bare `{"$ref": "..."}` this resolver's generic fragment composition uses everywhere else. Before
# this table existed, `ref_value`'s exactly-one-key rule rejected every one of them, so any model
# declaring `contexts[]` was unreadable by `inspect app`, `inspect bonds` AND
# `validate model --structural-only` -- all three failed with the same bogus
# `$ref object must be exactly { "$ref": "relative/path.json" }`.
#
# key -> the additional keys an entry of that array may legally carry alongside "$ref"
REF_ENTRY_EXTRA_KEYS = {
    "contexts": frozenset({"name", "physicallyIsolate"}),
    "packs": frozenset({"as", "allowSideBySide"}),
}
# A `packs[]` entry is `$ref` (a local file, hermetic by design) OR `from` (PK-5's remote
# coordinate: `oci://...` / `git+https://...`, resolved ONLY out of the local content-addressed
# cache via npdev.lock at generate time). model.schema.json's `packRef` makes the two mutually
# exclusive. Nothing build-free can expand a `from` pack, so its members are not composed and the
# declaration is passed through untouched -- but that is a REPORTED limit, never a silent one:
# `inspect app` says how many contributions it could not read.
REMOTE_PACK_KEY = "from"

# npdev-qualifier-rule -- twin-pair token, see scripts/quality/twin-pair-registry.json.
# ------------------------------------------------------------------------------------
# REG-186: composing a pack/context contribution means rewriting each contributed member's bare
# name to its `qualifierId::name` form, and the qualifier is chosen the same way on both sides:
#   * a CONTEXT qualifies by the name declared in the model's own `contexts[]` entry;
#   * a PACK qualifies by its `as` alias when the import declares one, otherwise by the `pack`
#     identifier the pack file declares for itself.
# That rule lives in Java in ModelSourceResolver (`memberRewriteMap` / `resolvePackQualifier`),
# which is authoritative -- the generator only ever reads the Java-resolved model. This Python copy
# exists ONLY so the build-free introspection commands can see pack/context members without a
# Gradle run; it must never drift from the Java one, which is why both carry this token and
# check-twin-pair-consistency.py fails the ai-knowledge gate if either drops it.
QUALIFIER_SEPARATOR = "::"

# Composed, not concatenated: these two keys name CONTRIBUTIONS whose members are merged into the
# model's other arrays under a qualifier, so they must not go through the generic `resolve_array`
# path (which would splice the referenced file's whole body in as if it were a context/pack entry
# and lose the `name`/`as` that decides the qualifier).
QUALIFIED_COMPOSITION_KEYS = ("packs", "contexts")


def resolve_member_reference(target: str | None, known_names, referrer: str | None = None) -> str | None:
    """npdev-qualifier-rule -- resolve a possibly-UNQUALIFIED member reference against the composed
    model's (possibly qualified) member names, returning the real name or None.

    Mirrors ModelSourceResolver.resolveUnqualifiedReferences, in the same order and with the same
    refusals:
      1. an exact match wins outright -- an already-qualified reference is never re-interpreted;
      2. otherwise a bare reference made FROM inside a contribution resolves within that same
         contribution first (`shipping::DeliveryAttempt` -> `Shipment` means
         `shipping::Shipment`), which is the ordinary intra-context case and the one that made
         REG-186's first composed `inspect bonds` run report a real bond as anchorless;
      3. otherwise it resolves only if EXACTLY ONE contribution offers that bare name. Two or more
         is an ambiguity, and this returns None rather than picking -- Java throws there, and a
         silent pick is precisely the failure mode this whole plan exists to remove.
    """
    if not isinstance(target, str) or not target:
        return None
    names = set(known_names)
    if target in names:
        return target
    if QUALIFIER_SEPARATOR in target:
        return None
    if referrer and QUALIFIER_SEPARATOR in referrer:
        own = referrer.split(QUALIFIER_SEPARATOR, 1)[0] + QUALIFIER_SEPARATOR + target
        if own in names:
            return own
    candidates = [n for n in names
                  if QUALIFIER_SEPARATOR in n
                  and n.split(QUALIFIER_SEPARATOR, 1)[1] == target]
    return candidates[0] if len(candidates) == 1 else None


def _warn_on_fragment_schema_violations(fragment_path: Path, fragment: dict) -> None:
    """PACK-12: check one composed fragment against `model-fragment.schema.json`, reporting to
    stderr rather than refusing.

    A WARNING on purpose. The composer's own `unsupported model fragment key` check above already
    refuses keys that genuinely cannot compose; this adds precision about the ones that can, and a
    schema that was itself wrong until PACK-12 corrected it has not earned the right to fail
    somebody's build. Silent on any environment where the validator is unavailable -- the split
    resolver runs in the fast, Gradle-free path, and making it depend on a validator would trade a
    real capability for a diagnostic.
    """
    try:
        import jsonschema  # noqa: PLC0415 -- optional, and deliberately not a hard dependency
    except ImportError:
        return
    schema_path = repo_root() / "NPDevContract" / "schemas" / "model-fragment.schema.json"
    if not schema_path.is_file():
        return
    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        # Local `$ref`s into sibling schema files are not resolvable without a registry, and the
        # composer has already checked the shape they describe. Checking the fragment's own KEYS is
        # the part that has no other owner.
        keys_only = {
            "type": "object",
            "additionalProperties": schema.get("additionalProperties", False),
            "properties": {key: {} for key in schema.get("properties", {})},
        }
        jsonschema.validate(fragment, keys_only)
    except jsonschema.ValidationError as violation:
        print(f"npdev: warning: {fragment_path}: {violation.message}", file=sys.stderr)
    except Exception:
        # A broken schema file must not break composition -- it is a diagnostic, not a gate.
        return


def resolve_split_model(path: Path, collect_sources: set[Path] | None = None,
                       collect_uncomposed: list[str] | None = None) -> dict:
    """Compose a (possibly $ref-split) model into one dict.

    `collect_sources`, when given, is populated with every file that contributed -- the root
    plus every fragment reached through $ref. `npdev dev` uses it as its watch set: a model is
    a graph since bounded contexts (S3), so watching model.json alone misses fragment edits.
    Exposed as an out-parameter rather than a second traversal on purpose -- two walks of the
    same $ref graph would drift, which is REG-108's exact shape.

    `collect_uncomposed` (REG-186), when given, is populated with the coordinate of every
    contribution whose members this build-free resolver could NOT compose -- today exactly the
    remote `packs[].from` coordinates, which only `npdev generate` can expand out of the
    lockfile-backed cache. Same out-parameter reasoning; a caller that reports member counts
    should say how many contributions it could not read, because "0 of them" and "we did not
    look" print identically otherwise.
    """
    root_path = Path(path).expanduser().resolve(strict=True)
    root_dir = root_path.parent.resolve(strict=True)
    seen: set[Path] = set() if collect_sources is None else collect_sources
    seen.add(root_path)
    uncomposed: list[str] = [] if collect_uncomposed is None else collect_uncomposed

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

    def ref_value(value: object, label: str, extra_allowed: frozenset[str] = frozenset()) -> str | None:
        if isinstance(value, dict) and "$ref" in value:
            # REG-186: `extra_allowed` is the per-key shape table (REF_ENTRY_EXTRA_KEYS), not a
            # blanket relaxation -- an unknown sibling key is still an error, and the error text is
            # unchanged for the ordinary bare-`$ref` case so existing diagnostics still read the same.
            if set(value.keys()) - ({"$ref"} | set(extra_allowed)):
                allowed = ", ".join(f'"{k}"' for k in sorted(extra_allowed))
                fail(label, '$ref object must be exactly { "$ref": "relative/path.json" }'
                     + (f" (plus any of: {allowed})" if extra_allowed else ""))
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
                if child_key in REF_ENTRY_EXTRA_KEYS:
                    # REG-186: `packs`/`contexts` entries are `{"$ref", "as"}` and
                    # `{"name", "$ref", "physicallyIsolate"}` -- deliberately wider shapes than this
                    # resolver's generic `{"$ref"}`-only fragment convention, because an import
                    # needs its qualifier recorded alongside the path. Each is shape-checked against
                    # its own REF_ENTRY_EXTRA_KEYS row by `compose_qualified` below, so walking them
                    # here with the generic rule would reject them for having exactly the keys they
                    # are required to have. (Only `packs` was skipped before; `contexts` was not,
                    # which is the whole of REG-186's hard failure.)
                    continue
                validate_refs(child_value, f"{label}/{child_key}")
        elif isinstance(value, list):
            for index, child in enumerate(value):
                validate_refs(child, f"{label}/{index}")

    def resolve_array(key: str, values: object, source: Path, depth: int, stack: list[Path]) -> list:
        if not isinstance(values, list):
            fail(str(source), f"{key} must be an array")
        out: list = []
        for index, item in enumerate(values):
            ref = ref_value(item, f"{source}/{key}/{index}", REF_ENTRY_EXTRA_KEYS.get(key, frozenset()))
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
        # PACK-12: `NPDevContract/schemas/model-fragment.schema.json` had NO consumer, and had
        # drifted into declaring 14 keys with `additionalProperties: false` and no `concepts` -- so
        # as written it rejected the most ordinary fragment there is, while this composer merged
        # one without complaint. Correcting it without wiring it would leave exactly the condition
        # that let it drift. `collect_sources` already knows every fragment file visited; this is
        # the point at which one is in hand.
        _warn_on_fragment_schema_violations(fragment_path, fragment)
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

    def compose_qualified(kind_key: str, entries: object, resolved: dict) -> list:
        """REG-186: merge every member a `packs[]`/`contexts[]` entry contributes into the model's
        own arrays, each member's bare `name` rewritten to `qualifier::name`, and return the
        registry of declarations to keep under `kind_key` itself.

        npdev-qualifier-rule -- mirrors ModelSourceResolver.memberRewriteMap / resolvePackQualifier;
        see QUALIFIER_SEPARATOR's comment for why this second copy exists and what pins it.
        """
        if not isinstance(entries, list):
            fail(str(root_path), f"{kind_key} must be an array")
        extra_allowed = REF_ENTRY_EXTRA_KEYS[kind_key]
        registry: list = []
        seen_qualifiers: set[str] = set()
        for index, entry in enumerate(entries):
            label = f"{root_path}/{kind_key}/{index}"
            if not isinstance(entry, dict):
                fail(label, f"{kind_key} entry must be a JSON object carrying a $ref")
            if kind_key == "packs" and REMOTE_PACK_KEY in entry and "$ref" not in entry:
                # Remote coordinate -- not expandable without the lockfile-backed pack cache. Keep
                # the declaration verbatim and record that its members were not composed.
                registry.append(dict(entry))
                uncomposed.append(str(entry.get(REMOTE_PACK_KEY)))
                continue
            ref = ref_value(entry, label, extra_allowed)
            if ref is None:
                fail(label, f"{kind_key} entry must declare a $ref")
            child_path = include_path(ref, root_path)
            content = read_fragment(child_path, 1, [root_path])
            if not isinstance(content, dict):
                fail(str(child_path), f"{kind_key} target must be an object")

            if kind_key == "contexts":
                qualifier = entry.get("name")
                if not isinstance(qualifier, str) or not qualifier.strip():
                    fail(label + "/name", "Context 'name' must be a non-blank string")
                qualifier = qualifier.strip()
            else:
                # A pack import's `as` alias wins over the pack file's own `pack` identifier --
                # that is the alias's entire purpose (side-by-side imports of the same pack).
                alias = entry.get("as")
                if alias is not None and (not isinstance(alias, str) or not alias.strip()):
                    fail(label + "/as", "Pack alias 'as' must be a non-blank string")
                declared = content.get("pack")
                if alias is None and (not isinstance(declared, str) or not declared.strip()):
                    fail(str(child_path) + "/pack",
                         "Pack file must declare a non-blank string 'pack' identifier")
                qualifier = (alias or declared).strip()
            if qualifier in seen_qualifiers:
                fail(label, f"duplicate {kind_key[:-1]} qualifier: {qualifier}")
            seen_qualifiers.add(qualifier)

            for member_key in MODEL_ARRAY_KEYS:
                if member_key in QUALIFIED_COMPOSITION_KEYS or member_key not in content:
                    continue
                members = resolve_array(member_key, content[member_key], child_path, 1, [root_path])
                qualified = []
                for member in members:
                    if isinstance(member, dict) and isinstance(member.get("name"), str):
                        member = dict(member)
                        member["name"] = qualifier + QUALIFIER_SEPARATOR + member["name"]
                    qualified.append(member)
                resolved.setdefault(member_key, []).extend(qualified)

            # The registry entry keeps ONLY the declaration keys, never the composed body -- the
            # members are already merged above, and model.schema.json's `context`/`packRef` defs are
            # `additionalProperties: false`. `physicallyIsolate` is emitted only when true, matching
            # ModelSourceResolver's own note that a model never declaring it must resolve unchanged.
            keep = {"$ref": ref}
            if kind_key == "contexts":
                keep = {"name": qualifier, "$ref": ref}
                if entry.get("physicallyIsolate") is True:
                    keep["physicallyIsolate"] = True
            else:
                if entry.get("as") is not None:
                    keep["as"] = entry["as"]
                if entry.get("allowSideBySide") is not None:
                    keep["allowSideBySide"] = entry["allowSideBySide"]
            registry.append(keep)
        return registry

    raw = read_json(root_path)
    if not isinstance(raw, dict):
        fail(str(root_path), "root model must be an object")
    unsupported = set(raw.keys()) - (ROOT_SCALAR_KEYS | MODEL_ARRAY_KEYS | {"metadata", "fragments"})
    if unsupported:
        fail(str(root_path), "unsupported model top-level key: " + sorted(unsupported)[0])
    validate_refs(raw, str(root_path))

    resolved = {key: raw[key] for key in ROOT_SCALAR_KEYS if key in raw
                and key not in QUALIFIED_COMPOSITION_KEYS}
    for key in MODEL_ARRAY_KEYS:
        if key in raw and key not in QUALIFIED_COMPOSITION_KEYS:
            resolved[key] = resolve_array(key, raw[key], root_path, 0, [root_path])
    # REG-186: packs before contexts, matching ModelSourceResolver's own order -- a context is
    # allowed to reference a pack-contributed member, so the pack members must already be present.
    for key in QUALIFIED_COMPOSITION_KEYS:
        if key in raw:
            resolved[key] = compose_qualified(key, raw[key], resolved)
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
            # REG-186: once pack/context members are composed under a qualifier, a bare target name
            # written inside a contribution no longer matches the composed concept map directly.
            # Resolve it the way the Java resolver does rather than reporting a real bond as
            # anchorless.
            resolved_target = resolve_member_reference(target, concepts.keys(), concept_name)
            if resolved_target is not None:
                target = resolved_target
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

    diagram_path = getattr(args, "diagram", None)
    if diagram_path:
        # model.json's own filename stem is just "model" for every app -- prefer the model's
        # declared namespace, then the app directory name (parent of definition/model.json), and
        # only fall back to the file stem if neither is available.
        model_label = (
            model.get("namespace")
            or model_path.parent.parent.name
            or model_path.stem
        )
        html = npdev_diagram.render_bonds_diagram_html(concepts, bonds, model_label=str(model_label))
        target = Path(diagram_path).expanduser()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(html, encoding="utf-8")
        print(str(target))


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


def run_migrate_rename(args: argparse.Namespace) -> int:
    """B1.2 (docs/ACCEPTED_BOUNDARIES.md B1): declare a field rename for authors editing model.json
    directly rather than through NPDevEditor's Field Details panel. NPDevEditor already stamps
    `renamedFrom` automatically at its own single choke point (`updateField` in editorUtils.ts) --
    this gives the hand-editing path the identical choke point, so a rename is a DECLARED event
    either way, never a diff the engine has to guess at (docs/ACCEPTED_BOUNDARIES.md B1: a diff alone
    cannot tell 'renamed a->b' from 'dropped a, added b', and guessing wrong destroys data).

    Mirrors editorUtils.ts's `applyFieldUpdate` exactly: preserves the ORIGINAL pre-rename name across
    a chain of renames (a field already carrying `renamedFrom` keeps that value, not the immediately
    prior name), and clears `renamedFrom` if the field is renamed back to that original name -- a name
    that nets out unchanged is not a rename. Dry-run by default; --write applies and schema-validates.
    """
    model_path = Path(args.model).expanduser().resolve()
    model = read_json(model_path)

    if "." not in args.field:
        raise CliError(f"expected <Concept>.<oldField>, got: {args.field!r}")
    concept_name, old_field = args.field.split(".", 1)
    new_field = args.new_name

    concepts = model.get("concepts", [])
    concept = next((c for c in concepts if c.get("name") == concept_name), None)
    if concept is None:
        available = ", ".join(sorted(c.get("name", "") for c in concepts)) or "(none)"
        raise CliError(f"no concept named '{concept_name}' in {model_path}. Available: {available}")

    fields = concept.get("fields", [])
    field = next((f for f in fields if f.get("name") == old_field), None)
    if field is None:
        available = ", ".join(sorted(f.get("name", "") for f in fields)) or "(none)"
        raise CliError(f"concept '{concept_name}' has no field named '{old_field}'. Available: {available}")

    if old_field == new_field:
        raise CliError("old and new field names are identical -- nothing to rename")
    collision = next((f for f in fields if f.get("name") == new_field), None)
    if collision is not None:
        raise CliError(f"concept '{concept_name}' already has a field named '{new_field}' -- pick a different name")

    original_name = field.get("renamedFrom") or old_field
    if new_field == original_name:
        field.pop("renamedFrom", None)
        summary = f"'{old_field}' -> '{new_field}' (back to its original name -- renamedFrom cleared)"
    else:
        field["renamedFrom"] = original_name
        summary = f"'{old_field}' -> '{new_field}' (renamedFrom: '{original_name}')"
    field["name"] = new_field

    verb = "CHANGED" if args.write else "WOULD CHANGE"
    print(f"  [{verb}] {concept_name}.{summary}")

    # XREF-3: without --cascade this is the historical behaviour, kept exactly -- stamp the rename
    # and leave every panel/query/procedure pointing at the old name. That is now a WARNING rather
    # than silence, because REG-185 turned those orphans into hard validation errors: an author who
    # renames without cascading will find out at the next `validate model`, and would rather find
    # out here.
    # getattr, not args.cascade: this function is called directly by tests and by other CLI paths
    # that build their own Namespace, and a new flag must not make an existing caller crash. The
    # default is the historical behaviour, which is also the safe one.
    cascade = getattr(args, "cascade", False)
    if not cascade:
        print("  NOTE: references to this field elsewhere in the model were NOT updated.")
        print("        Run with --cascade to update them, or `npdev inspect usage --model "
              f"{model_path} --of {concept_name}.{new_field}` to see what still needs changing.")
    else:
        edits = _cascade_rename(model, model_path, concept_name, old_field, new_field)
        for edit in edits:
            print(f"  [{verb}] {edit}")
        if not edits:
            print("  (nothing else in this model references that field)")

    if not args.write:
        print("Dry run -- pass --write to apply.")
        return 0

    candidate = write_temp_model(model, model_path)
    validate_json_schema(repo_root() / "NPDevContract" / "schemas" / "model.schema.json", candidate)

    if cascade:
        # Fail closed. The rewrite above is re-checked against a freshly built index over the
        # CANDIDATE file, not over the in-memory dict it produced, so a bug in the rewriting cannot
        # produce a written model that silently still refers to the old name.
        after = load_model_xref(candidate)
        left = npdev_rename_cascade.remaining_references(
            after.get("edges") or [], concept_name, old_field)
        if left:
            raise CliError(
                "refusing to write: after the cascade, "
                + str(len(left)) + " reference(s) still point at "
                + f"{concept_name}.{old_field} -- "
                + "; ".join(str(e.get("path")) for e in left[:5]))
        introduced = [e for e in (after.get("edges") or []) if e.get("resolution") == "UNRESOLVED"]
        if introduced:
            raise CliError(
                "refusing to write: the cascade would leave "
                + str(len(introduced)) + " unresolved reference(s) -- "
                + "; ".join(f"{e.get('path')} -> {e.get('toName')}" for e in introduced[:5]))

    model_path.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    print(str(model_path))
    return 0


def _cascade_rename(model: dict, model_path: Path, concept_name: str,
                    old_field: str, new_field: str) -> list[str]:
    """XREF-3: rewrite every reference to `concept_name.old_field`, or refuse and change nothing.

    The index is built from the model ON DISK -- i.e. BEFORE the in-memory rename above -- which is
    the whole point: it has to describe the world the references were written against. `model` is
    then edited in memory and only written by the caller, after the post-check.
    """
    report = load_model_xref(model_path)
    edges = report.get("edges") or []

    rewritable, refusals = npdev_rename_cascade.plan_cascade(edges, concept_name, old_field)
    refusals += npdev_rename_cascade.trusted_source_refusals(model, rewritable)
    if refusals:
        raise CliError(
            "cannot cascade this rename safely -- nothing was changed:\n  - "
            + "\n  - ".join(refusals)
            + "\nFix the listed sites by hand and re-run. (A rename that rewrites what it can see "
              "and leaves what it cannot is worse than one that refuses: it looks finished.)")

    try:
        return npdev_rename_cascade.apply_cascade(model, rewritable, old_field, new_field)
    except npdev_rename_cascade.CascadeRefusal as refusal:
        raise CliError("cannot cascade this rename safely -- nothing was written: " + refusal.reason)


def run_migrate_db_lifecycle(args: argparse.Namespace) -> int:
    """STOR-16: rewrite `schemaLifecycle.strategy: RecreateOnAppStart` to `Ephemeral`.

    Ships in the same commit as the deprecation, per the standing convention that a breaking change
    to the model DSL, generated code layout or internal APIs carries its `npdev migrate` codemod
    rather than landing the break first and the codemod later.
    """
    from dsl_v2_migration import migrate_db_definition  # lazy, matching run_migrate_dsl2

    targets: list[Path] = []
    for entry in args.input:
        path = Path(entry).expanduser()
        if path.is_dir():
            targets.extend(sorted(path.rglob("db.definition.json")))
        elif path.exists():
            targets.append(path)
        else:
            raise CliError(f"not found: {path}")
    if not targets:
        raise CliError("no db.definition.json found under: " + ", ".join(str(e) for e in args.input))

    changed = 0
    for target in targets:
        doc = read_json(target)
        result = migrate_db_definition(doc)
        if not result.changed:
            continue
        changed += 1
        verb = "CHANGED" if args.write else "WOULD CHANGE"
        for change in result.changes:
            print(f"  [{verb}] {target}: {change}")
        for note in result.ambiguities:
            print(f"  [NOTE]    {target}: {note}")
        if args.write:
            target.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")

    print(f"{changed} of {len(targets)} definition(s) use the deprecated spelling.")
    if changed and not args.write:
        print("Dry run -- pass --write to apply.")
    return 0


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
        # STOR-16: was "RecreateOnAppStart", a name with no code path behind it. `Ephemeral` is the
        # behaviour that name always claimed, and for InMemory the two are the same statement.
        "strategy": "Ephemeral",
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


# The scaffolded README's one sentence about version history, in both of its truths. It is a pair of
# constants rather than inline prose because the README is written BEFORE `git init` is attempted and
# corrected after (see run_init), so a wording edit made in only one of the two places would quietly
# stop the correction from applying and put the "already a git repository" claim back on a machine
# that has no git.
_README_GIT_SENTENCE = ("This directory is already a git repository with one commit for exactly "
                        "that reason -- keep committing as you change the model.")
_README_NO_GIT_SENTENCE = ("git is not installed on this machine, so this directory is NOT a git "
                           "repository yet -- install git, run `git init` here, and commit as you "
                           "change the model.")


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

    # STOR-15: validate --externally-provisioned against the chosen engine HERE, beside the other
    # preconditions, not at the db.definition.json write further down. Measured: refusing at the
    # write site still left a fully scaffolded directory behind (model.json, config.json, the git
    # repo) with a non-zero exit -- the caller is told it failed while the files exist, which is the
    # half-scaffolded state `npdev init` refuses everywhere else. Every other precondition in this
    # function is checked before anything is created; this one belongs with them.
    if getattr(args, "externally_provisioned", False):
        try:
            probe = npdev_engines.resolve(getattr(args, "engine", "h2local"))
        except ValueError as exc:
            raise CliError(str(exc)) from exc
        if not probe["server"]:
            raise CliError(
                f"--externally-provisioned is not valid for {probe['externalName']}: it is an "
                "embedded engine, so there is no server for anyone to have provisioned -- the "
                "database is a file (or memory) belonging to this app alone. Choose a server engine "
                "(postgres, mysql, sqlserver, h2server) if you mean to connect to a database you "
                "already run."
            )

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
        try:
            db_def = npdev_engines.db_definition_for(
                engine["key"],
                database_name=db_name,
                host=getattr(args, "db_host", None),
                port=getattr(args, "db_port", None),
                username=getattr(args, "db_user", None),
                password=getattr(args, "db_password", None),
                externally_provisioned=bool(getattr(args, "externally_provisioned", False)),
            )
        except ValueError as exc:
            # STOR-15: --externally-provisioned on an embedded engine. Refused before anything is
            # written, so the user does not end up with a half-scaffolded app and an error from a
            # layer they never invoked.
            raise CliError(str(exc)) from exc
        (target / "db.definition.json").write_text(json.dumps(db_def, indent=2) + "\n", encoding="utf-8")

    # QUAL-3: DECLARE this app's identity instead of letting the generator infer it from where the
    # file happens to sit.
    #
    # `UserDatabaseDefinitionLoader.resolveAppId` reads this manifest first and only falls back to
    # walking two directories up from db.definition.json. That fallback is correct for the corpus
    # layouts (`<App>/definition/...`, `<App>/Input/...`) and wrong here, because `npdev init`
    # writes the definition directly into the app directory -- so two levels up is the PARENT
    # FOLDER. Measured: two apps scaffolded into `D:\Apps` both resolved to appId `Apps`, hence the
    # same container name and the same data root, and `npdev db reset` for either destroyed the
    # other's data while reporting success.
    #
    # Fixing this by guessing from the directory NAME was tried and measured to be worse -- 25
    # corpus definitions live in a directory called `Input` and would have collapsed onto one
    # identity. Path shape cannot distinguish an app directory from a wrapper directory. This can:
    # the app says who it is.
    manifest_path = target / "manifest.json"
    if not manifest_path.exists():
        manifest_path.write_text(
            json.dumps({"id": target.name, "title": target.name}, indent=2) + "\n", encoding="utf-8")

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

    readme_text = (
        f"# {target.name}\n\n"
        f"An NPDev app, scaffolded by `npdev init`.\n\n"
        f"**`model.json` is this application.** Everything else -- the database schema, the REST "
        f"API, the admin screens -- is generated from it and is disposable: delete it, regenerate "
        f"it, nothing is lost as long as model.json (and db.definition.json, which says how your "
        f"data persists) survive. {_README_GIT_SENTENCE}\n\n"
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
        f"for a destructive change.\n"
    )
    (target / "README.md").write_text(readme_text, encoding="utf-8")

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

    git = _scaffold_git_history(target)
    if not git.initialised:
        # The README written above promised a repository -- it has to, because git only sees files
        # that already exist, so it is written first and the answer arrives second. Nothing else in
        # the scaffold is affected, but a file that says "this directory is already a git
        # repository" sitting in a directory that is not one is exactly the quiet lie the notice
        # beside it exists to avoid. Both sentences are single constants precisely so that editing
        # the wording cannot make this substitution silently stop applying. Patches the in-memory
        # `readme_text` this process already built above, never reads the file back off disk
        # (md-zero-2026-08-11 PLAN.md Phase 7).
        patched = readme_text.replace(_README_GIT_SENTENCE, _README_NO_GIT_SENTENCE)
        (target / "README.md").write_text(patched, encoding="utf-8")

    git_note = git.notice

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
                # Was a hardcoded `True`, which stopped being true the moment git could be absent
                # (W1.2). The Manager reads this key; a scaffold that reports a repository it does
                # not have would make the UI lie on exactly the machine the Manager is built for.
                "gitInitialised": git.initialised,
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
            # Non-null when git had no identity and one was substituted for this repo alone, and
            # (since W1.2) when git is absent and there is no repository at all. The Manager can
            # surface it beside "created." -- a substitution nobody is told about is a worse
            # surprise than the substitution itself, and a missing repository nobody is told about
            # is the same defect with the volume turned up.
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
    print("\ngit: initialized, 1 commit" if git.initialised
          else "\ngit: not installed -- no repository created")
    if git_note:
        print(f"\n  ! {git_note}")
    print(f"\nNext:\n  cd {target.name}\n  npdev run app")
    return 0


class GitScaffold(NamedTuple):
    """What `_scaffold_git_history` actually managed to do, DECLARED rather than inferred.

    The caller needs to know two different things -- "is there a repository?" and "was something
    substituted that the user must be told about?" -- and they are independent. Reading the answer
    back off the filesystem (`(target / ".git").exists()`) would work today and would be exactly the
    kind of guess `_managed_jdk`'s docstring argues against, so the function says so instead.
    """
    initialised: bool
    notice: str | None


def _scaffold_git_history(target: Path) -> GitScaffold:
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

    GIT ABSENT ENTIRELY is the same defect one level up, found 2026-08-10 by taking git off PATH.
    The Manager installs a private JDK and a private Python and NEVER installs git, while
    docs/MANAGER.md advertised "no Java, no Python, no git" -- so on the machine this command exists
    for, `["git", ...]` raises FileNotFoundError, which `main()` catches nowhere (it handles CliError
    and CalledProcessError), and the Manager's **Create** button dies with a raw traceback at the
    first step. The scaffold itself does not need git: every file is already written by the time this
    runs. So the missing repository is REPORTED and the command succeeds, on the same reasoning as
    the identity substitution above and the same shape `_platform_release_tag` already uses for
    `git describe`. It is the history that is missing, not the app.
    """
    message = f"npdev init: scaffold {target.name}"
    try:
        subprocess.run(["git", "init", "--quiet"], cwd=target, check=True)
        subprocess.run(["git", "add", "."], cwd=target, check=True)
        first = subprocess.run(["git", "commit", "--quiet", "-m", message],
                               cwd=target, capture_output=True, text=True)
    except FileNotFoundError:
        return GitScaffold(False, (
            "git is not installed on this machine, so the scaffold was created without a "
            "repository. Everything the app needs is here and `npdev run app` works as normal -- "
            "only the version history is missing. Install git from https://git-scm.com/downloads "
            "and run `git init && git add . && git commit -m \"scaffold\"` in this folder to get it."))

    if first.returncode == 0:
        return GitScaffold(True, None)

    stderr = (first.stderr or "") + (first.stdout or "")
    if "ident" not in stderr.lower() and "author identity" not in stderr.lower():
        # A different failure -- an empty commit, a hook, a broken repo. Not this function's problem
        # to paper over, and inventing an identity would not fix it anyway.
        raise CliError(
            f"could not create the first commit in {target}: {stderr.strip() or 'git failed'}")

    # FOUND IN CI, 2026-08-11 (ai-knowledge-gate run 31481184751): `-c user.name=`/`-c user.email=`
    # alone is not enough. Git resolves author/committer identity from the GIT_AUTHOR_*/
    # GIT_COMMITTER_* environment variables FIRST when they are explicitly set -- including set to
    # an empty string, which is different from unset -- and only falls back to `user.name`/
    # `user.email` config (`-c` included) when those variables are absent. A machine that exports
    # them empty (some CI runners; anything upstream that sanitizes identity by blanking rather than
    # unsetting) makes the `-c` overrides silently inert: git reports "empty ident name (for <>) not
    # allowed" instead of using the fallback. This passed on every local run because Windows
    # subprocess environments cannot represent "set to empty string" (an empty value is dropped from
    # the block entirely, which is indistinguishable from unset) -- the bug only reproduces on a
    # POSIX runner, which is exactly what caught it. Fix: also force the four identity variables in
    # the retry's own environment, so the fallback wins regardless of what the ambient environment
    # set them to.
    fallback_name, fallback_email = "NPDev", "npdev@localhost"
    fallback_env = dict(os.environ)
    fallback_env["GIT_AUTHOR_NAME"] = fallback_name
    fallback_env["GIT_AUTHOR_EMAIL"] = fallback_email
    fallback_env["GIT_COMMITTER_NAME"] = fallback_name
    fallback_env["GIT_COMMITTER_EMAIL"] = fallback_email
    retried = subprocess.run(
        ["git", "-c", f"user.name={fallback_name}", "-c", f"user.email={fallback_email}",
         "commit", "--quiet", "-m", message],
        cwd=target, capture_output=True, text=True, env=fallback_env)
    if retried.returncode != 0:
        raise CliError(
            f"git has no configured identity and the fallback commit also failed in {target}: "
            f"{(retried.stderr or '').strip()}\n"
            f"Set one and commit by hand:\n"
            f"  git config --global user.name \"Your Name\"\n"
            f"  git config --global user.email \"you@example.com\"")
    return GitScaffold(True, (
        f"git has no configured user.name/user.email on this machine, so the first commit was "
        f"authored as {fallback_name} <{fallback_email}> -- in this repository only, nothing "
        f"global was changed. Set your own with:\n"
        f"  git config --global user.name \"Your Name\"\n"
        f"  git config --global user.email \"you@example.com\""))


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


def _emit_static_pages(repo_root_path: Path, final_app_out: Path, config_path: Path) -> None:
    """control-panel.html, app-tree.html, app-tree-v2.html, agent-prompter.html and
    properties.html -- InfoPageEmitter's info.html links to all five unconditionally, but
    GeneratorMain itself only emits info.html. AppGen's Build-NpdevApp.ps1/Build-ClaudeApp.ps1
    always run the matching scripts/appgen/New-*Page.ps1 as a post-generation step (cheap: reads
    model.json/config.json only, no live app/DB needed); `npdev generate app`/`npdev dev` never
    did, so every app made through this path had five dead links on its own info.html. Mirrors
    those builders' calls exactly, run from this repo checkout the same way they are.
    """
    shell = _find_powershell()
    if shell is None:
        print("npdev: skipping control-panel.html/app-tree.html/app-tree-v2.html/"
              "agent-prompter.html/properties.html -- no PowerShell found (looked for `pwsh`, "
              "then `powershell`). info.html's links to these pages will 404 until PowerShell 7 "
              "(https://aka.ms/powershell) is installed and this app is regenerated.",
              file=sys.stderr)
        return

    static_dir = final_app_out / "src" / "main" / "resources" / "static"
    app_folder = config_path.parent
    app_id = final_app_out.name
    try:
        cfg = json.loads(config_path.read_text(encoding="utf-8"))
        app_id = str(cfg.get("scenario", {}).get("name") or app_id)
    except (OSError, json.JSONDecodeError):
        pass

    scripts_dir = repo_root_path / "scripts" / "appgen"
    # Port baked into control-panel.html only matters for its file:// fallback (same-origin '' is
    # used whenever the page is actually served) -- 8080 matches this command's own printed
    # "3. Open: http://localhost:8080" default dev step, not a live/resolved port.
    pages = [
        ("control-panel.html", scripts_dir / "New-ControlPanelPage.ps1",
         ["-StaticDir", str(static_dir), "-AppId", app_id, "-Port", "8080",
          "-OutRoot", str(final_app_out)]),
        ("app-tree.html", scripts_dir / "New-AppTreePage.ps1",
         ["-AppFolder", str(app_folder), "-StaticDir", str(static_dir), "-AppId", app_id]),
        ("app-tree-v2.html", scripts_dir / "New-AppTreePageV2.ps1",
         ["-AppFolder", str(app_folder), "-StaticDir", str(static_dir), "-AppId", app_id]),
        ("agent-prompter.html", scripts_dir / "New-AgentPrompterPage.ps1",
         ["-StaticDir", str(static_dir), "-AppId", app_id]),
        ("properties.html", scripts_dir / "New-PropertiesAdminPage.ps1",
         ["-StaticDir", str(static_dir), "-AppId", app_id]),
    ]
    for label, script, script_args in pages:
        command = [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script), *script_args]
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
        if completed.returncode != 0:
            detail = (completed.stderr or completed.stdout or "unknown error").strip()
            raise CliError(f"failed to emit {label}: {detail}")
        print(f"npdev: emitted {label}", file=sys.stderr)


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
        # R10 (EXT-1, "custom-screen mount"): explicit-only, no filesystem-convention guessing --
        # same discipline as every other path flag above. Absent means no mount, unchanged
        # behavior (the FinalAppAssembler.Options.webAssetsRoot() default). Gives `npdev generate
        # app` the identical mount Build-NpdevApp.ps1's apps/<App>/web convention already has,
        # instead of that PowerShell script being the only caller with the capability at all.
        web_assets = getattr(args, "web_assets", None)
        if web_assets:
            web_assets_path = Path(web_assets).expanduser().resolve()
            if not web_assets_path.is_dir():
                raise CliError(f"--web-assets not found or not a directory: {web_assets_path}")
            generator_args += ["--webAssetsRoot", str(web_assets_path)]
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

    _emit_static_pages(root, final_app_out, config)

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
            # REG-188: this refusal is now thrown by MigrationMutex (R9.3/STOR-17) -- a session-scoped
            # lock held by an OPEN DATABASE CONNECTION, not a row. `clear-claim` only clears
            # MigrationClaimStore's row and cannot free a live connection-held lock, so telling an
            # operator to run it here used to send them to delete a row that was no longer the lock
            # -- the boot would just wait out its budget and refuse again. `clear-claim` still has a
            # real job: a genuine leftover row from before an upgrade to R9.3, with no boot actually
            # holding the mutex.
            suggested_fix="Wait for the holder to finish (a boot waits up to "
                           "-Dnpdev.schema.lock.waitSeconds, default 300s, before giving up on its "
                           "own); if it is genuinely hung rather than just slow, find and stop that "
                           "process -- the lock is released when its database connection closes, "
                           "which killing the process does. Only use POST "
                           "/api/admin/schema-migration/clear-claim (SUPERUSER) or the ControlPanel "
                           "schema-migration screen if you have confirmed no instance is actually "
                           "migrating and this is a leftover claim row from before R9.3 -- it clears "
                           "that row, not a live connection-held lock, and does nothing to free one.",
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
    # BESIDE THE MODEL, not in the current directory. `--help` has always said "default: beside the
    # model", and this looked in the CWD -- identical whenever the model IS in the CWD, which is
    # every no-flag invocation, so the disagreement stayed invisible. It surfaces the moment anyone
    # follows README's own quickstart from the clone:
    #     ./npdev dev --model ../my-app/model.json
    #     -> CONFIG_NOT_FOUND: no config.json in the current directory (/work/src)
    # `npdev init` scaffolds model.json and config.json side by side, so the model's own directory
    # is the only place the config could sensibly be. Found by the first-run harness.
    model_dir = Path(args.model).expanduser().resolve().parent
    if not args.config:
        candidate = model_dir / "config.json"
        if not candidate.exists():
            return _diag(
                "GENERATE", "CONFIG_NOT_FOUND",
                f"--config not given and no config.json beside the model ({model_dir}).",
                suggested_fix="Pass --config explicitly, or point --model at a directory `npdev init` created "
                              "(it scaffolds model.json and config.json together).",
            )
        args.config = str(candidate)
    if not args.output:
        # Sibling directory, not a subdirectory: generated code is disposable and should not sit
        # inside the same folder as the model it was generated from (docs/YOUR_FIRST_APP.md's own
        # manual convention -- my-library -> ../my-library-app -- so this matches what anyone who
        # read that page already expects, rather than inventing a second convention here).
        #
        # Sibling of THE MODEL, for the same reason as the config above. Deriving it from the CWD
        # sent `--model ../my-app/model.json` run from the clone to `<clone>-app` -- a directory
        # beside the repository, named after the repository, for an app called something else.
        args.output = str(model_dir.parent / f"{model_dir.name}-app")
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
        # W1.3: JAVA_HOME's java, not PATH's. The build above already ran on the Manager's private
        # JDK (Gradle reads JAVA_HOME); starting the result with a bare `java` would be the one step
        # that ignores it.
        java = java_launcher()
        if java is None:
            result["diagnostics"].append(_diag(
                "BOOT", "JAVA_NOT_FOUND",
                "No Java runtime was found: JAVA_HOME is unset or does not contain bin/java, and "
                "there is no `java` on PATH.",
                suggested_fix="Install a JDK 17+, or set JAVA_HOME to one. Run `npdev doctor` to "
                              "see which Java NPDev can find.",
            ))
            return result
        ensure_api_key(final_app_out)
        with open(log_path, "w", encoding="utf-8") as log_file:
            boot_proc = subprocess.Popen(
                [java, "-jar", str(jar_path), f"--server.port={args.port}",
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
        # A POINTER, not the credential itself (CodeQL py/clear-text-logging-sensitive-data,
        # caught in PR #100 review): this JSON is unconditionally printed to stdout by main()
        # below, which is exactly a clear-text log sink. A caller that needs to authenticate reads
        # the file this names -- the same file every other launcher on this platform already
        # provisions into, so nothing new is exposed that `secrets/api-key.env` didn't already hold.
        result["apiKeyFile"] = str(final_app_out / "secrets" / "api-key.env")
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
        if completed.returncode != 0:
            return False, output
        try:
            _emit_static_pages(root, final_app_out, config)
        except CliError as exc:
            return False, output + f"\n{exc}"
        return True, output


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
    # BT-1: runtimehost-core is the app-independent half of RuntimeHost's source tree
    # (scripts/proofs/classify_runtimehost_sources.py), an independent Gradle project nested under
    # NPDevRuntimeHost/ (see its build.gradle header for why it isn't a kernel-style subproject).
    # Its own build depends on the kernel/generator/dsl jars staged below, so it must build AFTER
    # them -- see the two-phase staging below.
    runtimehost_core_root = root / "NPDevRuntimeHost" / "runtimehost-core"
    kernel_wrapper = gradle_wrapper(kernel_root)
    generator_wrapper = gradle_wrapper(generator_root)
    runtimehost_core_wrapper = gradle_wrapper(runtimehost_core_root)
    if not kernel_wrapper.exists():
        raise CliError(f"Kernel Gradle wrapper not found: {kernel_wrapper}")
    if not generator_wrapper.exists():
        raise CliError(f"Generator Gradle wrapper not found: {generator_wrapper}")
    if not runtimehost_core_wrapper.exists():
        raise CliError(f"runtimehost-core Gradle wrapper not found: {runtimehost_core_wrapper}")

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

        def _stage_jars(source_jars: dict[str, Path]) -> tuple[list[str], list[str]]:
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
            return copied, up_to_date

        # BT-1: stage kernel/generator/dsl jars NOW, before building runtimehost-core -- its own
        # build depends on them via the identical npdevRuntimeHostLibsDir fileTree mechanism a
        # generated app uses, so they must already be in libs_dir before its compileJava runs.
        _stage_jars(_discover_runtimehost_source_jars(root, build_root))

        out.narrate("npdev setup: [1/3] building runtimehost-core jar (+ sources jar)")
        runtimehost_core_command = [
            str(runtimehost_core_wrapper), "jar", "sourcesJar",
            f"-PnpdevBuildRoot={build_root}", f"-PnpdevRuntimeHostLibsDir={libs_dir}",
            *gradle_project_cache_args("runtimehost-core"), "--no-daemon", "--console=plain",
        ]
        if os.name == "nt" and runtimehost_core_wrapper.suffix.lower() == ".bat":
            runtimehost_core_command = ["cmd.exe", "/c"] + runtimehost_core_command
        out.run(runtimehost_core_command, runtimehost_core_root)

        # Re-discover: runtimehost-core's freshly built jar (+ sources jar) is now ALSO visible
        # under the external Gradle build root (same layout.buildDirectory redirection convention
        # kernel/generator/dsl already use), so this second pass picks it up too. This IS the
        # authoritative set the manifest/report below reflects.
        source_jars = _discover_runtimehost_source_jars(root, build_root)
        if not source_jars:
            raise CliError("No RuntimeHost jars were discovered under build/libs after local jar build.")

        copied, up_to_date = _stage_jars(source_jars)

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

    # JDBC drivers, on BOTH jar paths. The build path used to leave Postgres in the Gradle module
    # cache as a side effect of compiling the RuntimeHost template; the download path stages prebuilt
    # jars, runs no Gradle, and left nothing -- so `db test-connection` and doctor's credential/
    # charset checks could not run at all, and said so ("verdict": "unverified"). That is the CLI
    # being honest about an absent precondition, and setup is the thing that is supposed to supply it:
    # this command's own output tells the user "Run `npdev setup` ... to fetch it". Now that is true
    # however setup got its jars. Best-effort: an offline machine still completes setup.
    drivers_started = _time.monotonic()
    out.event("jdbc-drivers", "started")
    driver_results: dict[str, str] = {}
    for engine_key in sorted(_JDBC_DRIVER_COORDINATES):
        jar, outcome = _ensure_jdbc_driver(engine_key)
        driver_results[engine_key] = outcome
        out.narrate(f"npdev setup: [2/3] JDBC driver {engine_key}: {outcome}")
    out.event("jdbc-drivers", "done", seconds=round(_time.monotonic() - drivers_started, 1),
              **driver_results)

    # Clean the source builds' own build/ dirs afterward -- build output does not belong inside
    # the repo tree (this repo's own standing policy); scoped to exactly the four source roots
    # walked above (runtimehost-core redirects layout.buildDirectory outside the repo like the
    # other three, so this normally finds nothing there -- it only fires for a stray local build).
    for source_root_name in ("NPDevContract", "NPDevGenerator", "NPDevKernel",
                              "NPDevRuntimeHost/runtimehost-core"):
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


def java_launcher() -> str | None:
    """The `java` an app should actually be STARTED with: JAVA_HOME's binary first, PATH second.

    W1.3, 2026-08-10. Both launch sites (`npdev run app`'s BOOT phase and `dev_loop.boot`) spawned a
    bare `["java", "-jar", ...]`, which resolves through PATH and nothing else. That defeats the
    Manager's entire M3 thesis at the last step: its private JDK is handed to child processes as
    JAVA_HOME ONLY (`NPDevManager/src/npdev.rs`), which Gradle honours and a bare `java` cannot see.
    On a machine with no system Java -- the machine the Manager exists for -- generate and build
    succeed with the private JDK and then the app fails to start, with the working JDK sitting right
    there unused.

    Same precedence `npdev doctor`'s java checks already use, fixed there under Phase 0 I4b for
    exactly this reason: PATH-only was the wrong question there too.

    Returns None when there is no java anywhere, so a caller can report that as a diagnostic instead
    of letting `Popen` raise FileNotFoundError into a stderr stream the Manager discards -- the
    failure shape that kept this invisible.
    """
    java_home = os.environ.get("JAVA_HOME", "").strip()
    if java_home:
        candidate = _resolve_java_home_binary(java_home)
        if candidate.exists():
            return str(candidate)
    return shutil.which("java")


def ensure_api_key(app_root: Path) -> str:
    """Provision (or reuse) `<app_root>/secrets/api-key.env` and export it into this process's
    environment, before the BOOT phase below (and `dev_loop.boot`, injected with this module the
    same way it is with `java_launcher`) spawns `java -jar`.

    T1/C2 (application-dev.yml): the `dev` profile no longer seeds a known admin key --
    StartupValidator refuses to boot until one is supplied externally via
    NPDEV_AUTH_API_KEYS/NPDEV_AUTH_APIKEYS. Every launcher this platform ships already provisions
    one this way (OperationalRunbookEmitter's Ensure-NpdevApiKey / ensure_npdev_api_key) except this
    one -- `npdev run app` and `npdev dev` are the platform's own flagship one-command paths
    (README's Quickstart, docs/YOUR_FIRST_APP.md), and until this function existed neither ever
    wrote or read `secrets/api-key.env`, so both died inside StartupValidator with no key the moment
    C2 shipped. Same file, same `NPDEV_AUTH_API_KEYS=<key>=dev:developer:admin` line, same
    "present-but-unusable is treated as absent" rule (REG-157) as every other launcher -- neither the
    generated app's own `_ops` toolbox nor a human ever needs to know which launcher wrote the file.

    Both spellings are set directly on `os.environ`, not returned via an `env=` dict, because the
    BOOT phase's `subprocess.Popen` (and `dev_loop.boot`'s) already inherit the ambient environment
    with no explicit `env=` override -- mutating here is the smallest change that reaches both.
    Returns the bare credential (the part before the mapping's own `=`) for a caller that wants to
    make an authenticated request against the app it just started.
    """
    secrets_dir = app_root / "secrets"
    key_file = secrets_dir / "api-key.env"
    needs_generation = True
    if key_file.exists():
        for raw_line in key_file.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if line and not line.startswith("#") and "=" in line:
                needs_generation = False
                break
    if needs_generation:
        secrets_dir.mkdir(parents=True, exist_ok=True)
        key_file.write_text(
            f"NPDEV_AUTH_API_KEYS={secrets.token_hex(32)}=dev:developer:admin", encoding="utf-8")

    live_key = None
    for raw_line in key_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name == "NPDEV_AUTH_API_KEYS":
            os.environ["NPDEV_AUTH_API_KEYS"] = value
            os.environ["NPDEV_AUTH_APIKEYS"] = value
            live_key = value.split("=", 1)[0]
    if live_key is None:
        raise SystemExit(f"{key_file} carries no usable NPDEV_AUTH_API_KEYS mapping")
    return live_key


def _managed_jdk() -> bool:
    """True when the NPDev Manager set JAVA_HOME to its own private JDK for this process.

    The Manager exports `NPDEV_MANAGED_JDK=1` beside `JAVA_HOME` (see NPDevManager/src/npdev.rs).
    It is a DECLARATION, deliberately, rather than doctor inferring ownership from the shape of the
    path -- "is this directory ours?" answered by guessing is what eleven build-root resolvers got
    wrong in REG-144.

    Only java-home-agreement consults it, and only to decide whether a JAVA_HOME/PATH disagreement
    is the Manager's design (it is) or a user's misconfiguration (it is not).
    """
    return os.environ.get("NPDEV_MANAGED_JDK", "").strip() not in ("", "0", "false", "False")


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


def _probe_tcp(host: str, port: int, timeout: float = 3.0) -> OSError | None:
    """None if something accepted a TCP connection on host:port; the OSError otherwise.

    One probe, two callers, on purpose. `database-reachable` needs the exception text (its whole
    value is naming WHY), and `docker-present` needs only the yes/no -- but they must never come to
    different conclusions about the same host and port in the same run of doctor.
    """
    import socket

    try:
        with socket.create_connection((host, port), timeout=timeout):
            return None
    except OSError as exc:
        return exc


def _containerized_engine_target(app_path: str | None) -> tuple[dict, dict] | None:
    """(engine, database) for an app whose engine NPDev would containerize, else None.

    None covers three genuinely different situations, and all three mean the same thing for the
    docker check: no app is in scope (a machine with no NPDev app on it is not a broken machine),
    the definition is unreadable (`database-engine-support` reports that, and one cause should not
    produce two red lines), or the engine has nothing to containerize.
    """
    definition_path = _find_db_definition(app_path)
    if definition_path is None:
        return None
    try:
        definition = json.loads(definition_path.read_text(encoding="utf-8"))
        database = definition.get("database") or {}
        engine = npdev_engines.resolve(database.get("engine") or "h2local")
    except (json.JSONDecodeError, OSError, ValueError):
        return None
    return (engine, database) if engine.get("containerized") else None


def _engine_requiring_docker(app_path: str | None) -> str | None:
    """This app's engine name if it needs Docker, else None.

    STOR-14: an externally-provisioned server is not NPDev's to create, so Docker is not required to
    use it however containerized the engine is in general -- the same distinction the doctor check
    makes, kept here so a second caller cannot reach the opposite conclusion.
    """
    target = _containerized_engine_target(app_path)
    if target is None or target[1].get("externallyProvisioned"):
        return None
    return target[0]["externalName"]


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

    return _database_checks_for(
        engine, database,
        source=str(definition_path),
        fix_host_port=f"Start {engine['externalName']}, or correct host/port in {definition_path}.",
        fix_credentials=f"Correct database.username / database.password in {definition_path}.",
    )


def _database_checks_for(engine: dict, database: dict, *, source: str,
                         fix_host_port: str, fix_credentials: str) -> list[dict]:
    """The database checks themselves, for an ALREADY-RESOLVED engine + connection.

    Split out of `_database_checks` so the same five checks can answer the question BEFORE an app
    exists (`npdev db test-connection`, M13). The Manager's "Test connection" button sits beside the
    host/port/user/password fields on the *create* form, where there is no `db.definition.json` yet
    to point `--app` at -- so a check that can only read a file could not answer the one question
    being asked at that moment.

    The three `fix_*`/`source` strings are the only thing that differs between the two callers: an
    app-scoped run names the file to edit, an ad-hoc run names the fields the user just typed.
    Everything else -- the check ids, their order, and what each one distinguishes -- is shared on
    purpose. The Ready screen and the Test-connection button render the same records through the same
    code path, so they cannot drift into disagreeing about the same database.
    """
    checks: list[dict] = []
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
                               ("database-exists", "Database exists"),
                               ("database-privileges", "Database privileges"),
                               ("database-charset", "Database charset")):
            checks.append(_check(check_id, name, "pass", found=engine["externalName"],
                                 expected="n/a for an engine with no server"))
        return checks

    host = database.get("host") or "localhost"
    port = int(database.get("port") or engine["port"])

    exc = _probe_tcp(host, port)
    reachable = exc is None
    if exc is not None:
        checks.append(_check(
            "database-reachable", "Database reachable", "fail", found=f"{host}:{port}",
            expected="accepting connections",
            detail=f"Cannot reach {engine['externalName']} at {host}:{port} ({exc}). This is the "
                   f"most common first-run failure, and without this check it surfaces as a Spring "
                   f"stack trace after a full Gradle build.",
            fix=fix_host_port,
        ))
    else:
        checks.append(_check("database-reachable", "Database reachable", "pass",
                             found=f"{host}:{port}", expected="accepting connections"))

    if not reachable:
        # Everything below needs a connection. Reported as warn-with-a-reason rather than invented
        # failures: three red lines for one cause is noise that hides which one to fix.
        for check_id, name in (("database-credentials", "Database credentials"),
                               ("database-exists", "Database exists"),
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
        # WARN with the real reason, never a verdict. "No suitable driver found" reported as a
        # credentials failure is what the first CI run of this check produced, and a wrong diagnosis
        # is worse than no diagnosis: it sends the reader to fix a password that was never wrong.
        for check_id, name in (("database-credentials", "Database credentials"),
                               ("database-exists", "Database exists"),
                               ("database-privileges", "Database privileges"),
                               ("database-charset", "Database charset")):
            checks.append(_check(
                check_id, name, "warn", expected="checked with the app's own JDBC driver",
                detail=f"Not checked: {probe['unavailable']}",
            ))
        return checks

    if probe.get("databaseMissing"):
        # Credentials PROVEN good (the admin database accepted them); the database itself is absent.
        # A distinct first-run failure with a distinct fix -- NPDev creates TABLES at boot, never the
        # database -- and reporting it as a credentials problem sends the reader to the wrong file.
        checks.append(_check("database-credentials", "Database credentials", "pass",
                             found=database.get("username") or "(none)", expected="accepted"))
        checks.append(_check(
            "database-exists", "Database exists", "fail",
            found=database.get("databaseName") or "(none)", expected="present on the server",
            detail=f"{engine['externalName']} at {host}:{port} accepted these credentials, but the "
                   f"database '{database.get('databaseName')}' does not exist: "
                   f"{probe.get('databaseError', '')}. NPDev creates TABLES at boot; it never creates "
                   f"the database itself.",
            fix=f"Create it once, e.g. CREATE DATABASE {database.get('databaseName')};",
        ))
        for check_id, name in (("database-privileges", "Database privileges"),
                               ("database-charset", "Database charset")):
            checks.append(_check(
                check_id, name, "warn", expected="checked once the database exists",
                detail=f"Not checked: the database does not exist yet (see database-exists)."))
        return checks

    if probe.get("authenticated"):
        checks.append(_check("database-credentials", "Database credentials", "pass",
                             found=database.get("username") or "(none)", expected="accepted"))
        checks.append(_check("database-exists", "Database exists", "pass",
                             found=database.get("databaseName") or "(none)",
                             expected="present on the server"))
    else:
        checks.append(_check(
            "database-credentials", "Database credentials", "fail",
            found=database.get("username") or "(none)", expected="accepted",
            detail=f"{engine['externalName']} at {host}:{port} is running but rejected the "
                   f"credentials in {source}: {probe.get('authError', 'unknown error')}",
            fix=fix_credentials,
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


# Where each engine's JDBC driver comes from, as a Gradle-cache coordinate.
#
# MEASURED, not assumed (CI run 31272515969): the staged runtimehost-libs contain NPDev's OWN module
# jars and no third-party drivers at all, so the first version of this probe reported
# "No suitable driver found for jdbc:postgresql://..." as a CREDENTIALS failure. A missing driver is
# not a wrong password, and saying so was its own small version of the defect this plan is about.
#
# The drivers live in the machine's Gradle module cache, put there by the build that needed them:
# Postgres by `npdev setup` (the RuntimeHost template declares it), MySQL and SQL Server the first
# time an app for those engines is built. So the checks are REAL when the driver is there and say
# exactly why they cannot run when it is not -- never a guess dressed as a verdict.
_JDBC_DRIVER_COORDINATES = {
    "postgres": ("org.postgresql", "postgresql"),
    "mysql": ("com.mysql", "mysql-connector-j"),
    "sqlserver": ("com.microsoft.sqlserver", "mssql-jdbc"),
    "h2server": ("com.h2database", "h2"),
}


def _npdev_driver_cache_root() -> Path | None:
    """NPDev's OWN driver cache, laid out like a Gradle module cache so the search below needs no
    special case: <buildRoot>/jdbc-drivers/<group>/<artifact>/<version>/<artifact>-<version>.jar.

    Outside the repo, next to runtimehost-libs, because it is build output (this repo's standing
    policy) and because `npdev setup` already owns that directory."""
    try:
        return _ai_build_root() / "jdbc-drivers"
    except Exception:
        # Driver lookup must never be the thing that breaks doctor on a machine where the build root
        # cannot be resolved -- the Gradle/m2 roots below still work.
        return None


def _declared_jdbc_driver_version(group: str, artifact: str) -> str | None:
    """The version the RuntimeHost TEMPLATE declares for a driver, or None.

    Read rather than hardcoded so there is one source of truth: when the template bumps a driver,
    `npdev setup` fetches the bumped one and doctor probes with the same jar the app will use. A
    second copy of "42.7.4" here would be a version that drifts silently, which is the shape this
    repo keeps finding."""
    template = repo_root() / "NPDevRuntimeHost" / "build.gradle"
    if not template.is_file():
        return None
    pattern = re.compile(
        r"""['"]""" + re.escape(f"{group}:{artifact}") + r":([^'\"]+)['\"]")
    match = pattern.search(template.read_text(encoding="utf-8", errors="replace"))
    return match.group(1) if match else None


def _ensure_jdbc_driver(engine_key: str) -> tuple[Path | None, str]:
    """Make sure this machine has the engine's JDBC driver; return (jar, human-readable outcome).

    WHY SETUP HAS TO DO THIS. `_find_jdbc_driver_jar` searches the Gradle/Maven module caches, and
    the comment on _JDBC_DRIVER_COORDINATES says Postgres lands there via `npdev setup`. That is only
    true when setup BUILDS: `_try_download_runtimehost_libs` stages prebuilt jars and runs no Gradle,
    so on the download path nothing populates a module cache. Measured in the Manager harness
    (`jarsSource=download`), where `db test-connection` then reported `verdict: unverified` for
    correct credentials and the selftest's [8/9] failed -- the CLI was right, its precondition was
    absent. Setup's own printed advice ("Run `npdev setup` ... to fetch it") was false on that path.

    Downloads are best-effort by design: an offline machine still gets a working setup, and doctor
    still says exactly why the probe cannot run. Never raises."""
    existing = _find_jdbc_driver_jar(engine_key)
    if existing is not None:
        return existing, f"already present ({existing.name})"

    coordinate = _JDBC_DRIVER_COORDINATES.get(engine_key)
    if coordinate is None:
        return None, "no coordinate declared for this engine"
    group, artifact = coordinate
    version = _declared_jdbc_driver_version(group, artifact)
    if version is None:
        # h2 is the real case: the template lets the Spring BOM pick it, so there is no version to
        # resolve here and nothing to fetch. Not an error.
        return None, f"{group}:{artifact} declares no explicit version in the RuntimeHost template"

    cache_root = _npdev_driver_cache_root()
    if cache_root is None:
        return None, "could not resolve the NPDev build root"
    target_dir = cache_root / group / artifact / version
    target = target_dir / f"{artifact}-{version}.jar"
    if target.is_file():
        return target, f"already present ({target.name})"

    import urllib.error
    import urllib.request

    url = (f"https://repo1.maven.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/"
           f"{artifact}-{version}.jar")
    target_dir.mkdir(parents=True, exist_ok=True)
    staging = target_dir / f".{target.name}.part"
    try:
        urllib.request.urlretrieve(url, staging)
        # A proxy or a 404 page saved as a .jar is the failure worth catching: it would sit in the
        # cache forever and surface later as an unreadable driver rather than a missing one.
        with open(staging, "rb") as handle:
            if handle.read(2) != b"PK":
                raise ValueError(f"downloaded file is not a jar: {url}")
        os.replace(staging, target)
    except (urllib.error.URLError, OSError, ValueError) as exception:
        staging.unlink(missing_ok=True)
        return None, f"download failed ({exception})"
    return target, f"downloaded {artifact}-{version}.jar"


def _find_jdbc_driver_jar(engine_key: str) -> Path | None:
    """The engine's driver jar from this machine's Gradle module cache, or None.

    Deliberately not a Python driver per engine: the question doctor answers is "will THE APP be able
    to do this", and the only honest way to answer it is with the driver the app itself uses.
    """
    # An explicit override wins, and exists for one concrete reason: doctor's charset check must be
    # runnable WITHOUT first building an app for that engine (storage/OPEN_ITEMS_PLAN.md W9 -- "run
    # it independently and do not let it wait"). Without this, the only way to get a MySQL driver
    # onto a fresh machine is to build a MySQL app, which is exactly the dependency the charset
    # fixture is supposed to be free of.
    override = os.environ.get(f"NPDEV_JDBC_DRIVER_{engine_key.upper()}")
    if override:
        candidate = Path(override).expanduser()
        if candidate.is_file():
            return candidate
        # Named but absent is a MISTAKE, not a fallback: silently searching the cache instead would
        # let a CI job believe it tested with the driver it pinned.
        raise CliError(
            f"NPDEV_JDBC_DRIVER_{engine_key.upper()} points at {candidate}, which does not exist")

    coordinate = _JDBC_DRIVER_COORDINATES.get(engine_key)
    if coordinate is None:
        return None
    group, artifact = coordinate
    roots = [Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / group / artifact,
             Path.home() / ".m2" / "repository" / group.replace(".", "/") / artifact]
    # NPDev's own cache, populated by `npdev setup` on BOTH jar paths -- the download path runs no
    # Gradle and so leaves the two roots above empty. Searched last: a driver a real build resolved
    # outranks one setup fetched, because the build's is by definition the one the app will use.
    npdev_cache = _npdev_driver_cache_root()
    if npdev_cache is not None:
        roots.append(npdev_cache / group / artifact)
    for root in roots:
        if not root.is_dir():
            continue
        # Newest first: a machine that has built two app versions has two, and the later one is the
        # one a fresh build would use.
        jars = sorted((jar for jar in root.rglob("*.jar") if "sources" not in jar.name
                       and "javadoc" not in jar.name),
                      key=lambda jar: jar.stat().st_mtime, reverse=True)
        if jars:
            return jars[0]
    return None


def _run_database_probe(engine: dict, database: dict, host: str, port: int) -> dict:
    """Connect, try DDL, and read the charset -- using the driver the app itself would use."""
    libs = _default_runtimehost_libs_dir()
    if libs is None:
        return {"unavailable": "runtimehost jars are not staged -- run `npdev setup`"}
    driver_jar = _find_jdbc_driver_jar(engine["key"])
    if driver_jar is None:
        return {"unavailable": (
            f"the JDBC driver for {engine['externalName']} is not on this machine yet, so there is "
            f"nothing to connect with. `npdev setup` fetches it (it does so on both of its jar "
            f"paths) -- re-run setup, then re-run doctor. Building an app for this engine also "
            f"leaves one behind. (Reachability above is checked without a driver and is "
            f"unaffected.)")}
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
        separator = ";" if os.name == "nt" else ":"
        classpath = separator.join([str(Path(libs) / "*"), str(driver_jar)])
        try:
            completed = subprocess.run(
                [str(java_bin), "-cp", classpath, str(probe_file),
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
        String url = urlFor(engine, host, port, db);
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
            // A CONNECTION failure is not automatically a credentials failure, and the difference is
            // the whole value of the check. Measured (CI run 31272786548): pointing at a database
            // that does not exist yet reported "rejected the credentials", sending the reader to fix
            // a password that was never wrong.
            //
            // So: re-try against the engine's ADMIN database. If THAT connects, the credentials are
            // PROVEN good and the real problem is the missing database -- which is a different
            // first-run failure with a different fix (NPDev creates tables, not databases).
            String adminUrl = urlFor(engine, host, port, "");
            boolean credentialsOk = false;
            if (adminUrl != null && !adminUrl.equals(url)) {
                try (Connection admin = DriverManager.getConnection(adminUrl, user, password)) {
                    credentialsOk = admin != null;
                } catch (SQLException ignored) {
                    credentialsOk = false;
                }
            }
            if (credentialsOk) {
                out.append("\"authenticated\":true,\"databaseMissing\":true,\"databaseError\":\"")
                   .append(escape(exception.getMessage())).append("\"");
            } else {
                out.append("\"authenticated\":false,\"authError\":\"")
                   .append(escape(exception.getMessage())).append("\"");
            }
        }
        System.out.println(out.append("}"));
    }

    /** The JDBC URL for this engine; an EMPTY db means the engine's admin/default database. */
    private static String urlFor(String engine, String host, String port, String db) {
        return switch (engine) {
            case "postgres" -> "jdbc:postgresql://" + host + ":" + port + "/" + (db.isEmpty() ? "postgres" : db);
            case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/" + (db.isEmpty() ? "mysql" : db);
            case "sqlserver" -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName="
                    + (db.isEmpty() ? "master" : db) + ";encrypt=false;trustServerCertificate=true";
            case "h2server" -> "jdbc:h2:tcp://" + host + ":" + port + "/" + (db.isEmpty() ? "npdev_admin_probe" : db);
            default -> null;
        };
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


def run_db_test_connection(args: argparse.Namespace) -> int:
    """Answer "can NPDev actually use this database?" for a connection that is being TYPED, not one
    that has already been written to a file.

    M13 (storage/stabilize/STABILIZE_PLAN.md). `npdev doctor --app DIR` has run these same checks
    since W5.2, but only against an existing `db.definition.json`. The moment a user most needs the
    answer is earlier than that: standing in front of the host/port/user/password fields, before any
    app is scaffolded and long before a Gradle build. Today a wrong port at that moment is discovered
    minutes later, as a Spring stack trace, by someone who does not read Spring.

    Same checks, same ids, same order as doctor's -- deliberately. This shares
    `_database_checks_for` with doctor rather than reimplementing the probe, so "test connection said
    fine" and "Ready says fine" can never come to different conclusions about the same database.
    """
    try:
        engine = npdev_engines.resolve(args.engine)
    except ValueError as exc:
        raise CliError(str(exc)) from exc

    # The same shape `npdev init` would have written, built from what the user typed. Going through
    # db_definition_for (rather than assembling a dict here) means the per-engine defaults -- the
    # default port, the default username -- are the SAME ones the scaffolded app will get. A test
    # that silently probes a different port than the app will use is worse than no test.
    database = npdev_engines.db_definition_for(
        engine["key"],
        # Empty, not a placeholder name, when the user did not give one: the probe then connects to
        # the engine's ADMIN database, which answers "is this server usable" instead of "does a
        # database I just invented exist". A made-up name here would report `database-exists: fail`
        # on a perfectly good server -- a wrong verdict, which is worse than no verdict.
        database_name=args.db_name or "",
        host=args.db_host,
        port=args.db_port,
        username=args.db_user,
        password=args.db_password,
    )["database"]

    checks = _database_checks_for(
        engine, database,
        source="the connection details you entered",
        fix_host_port=f"Start {engine['externalName']}, or correct the host and port above.",
        fix_credentials="Correct the user and password above.",
    )

    # A check that COULD NOT RUN is not a check that passed.
    #
    # These four are the ones that actually answer "can this app use this database": the credentials,
    # the database's existence, the right to create tables, and whether the charset will silently
    # mangle text. When the JDBC driver is not on this machine yet -- an ORDINARY state, true of every
    # user who has not built an app -- all four report `warn` with "Not checked".
    #
    # `ok = not problems` counted only `fail`, so those four warns folded into success and the command
    # printed "Connection usable." for a WRONG PASSWORD against a reachable engine. Found by the
    # selftest's negative case at [8/9]; the positive case passed throughout, which is exactly why the
    # negative one exists.
    #
    # `database-engine-support` is deliberately NOT in this set: it warns for an experimental engine,
    # which is advisory and must not block a verdict.
    VERDICT_BEARING = (
        "database-credentials", "database-exists", "database-privileges", "database-charset",
    )
    problems = [c for c in checks if c["status"] == "fail"]
    unverified = [c for c in checks if c["status"] == "warn" and c["id"] in VERDICT_BEARING]
    ok = not problems and not unverified
    exit_code = 1 if (problems or unverified) else 0

    if getattr(args, "json", False):
        print(json.dumps({
            "schemaVersion": "npdev-cli-result.v1",
            "command": "db test-connection",
            "ok": ok,
            "exitCode": exit_code,
            "checks": checks,
            "engine": engine["externalName"],
            # Explicit, so a caller never has to infer "could not check" from a status count. The
            # Manager renders the CLI's own words; this lets it tell the two apart without parsing.
            "verdict": "usable" if ok else ("failed" if problems else "unverified"),
        }, indent=2))
        return exit_code

    print(f"npdev db test-connection -- {engine['externalName']}")
    print("=" * 60)
    for check in checks:
        mark = {"pass": "ok  ", "warn": "warn", "fail": "FAIL"}[check["status"]]
        print(f"  [{mark}] {check['name']}" + (f" -- {check['found']}" if check["found"] else ""))
        if check["status"] != "pass" and check["detail"]:
            print(f"         {check['detail']}")
        if check["status"] == "fail" and check["fix"]:
            print(f"         fix: {check['fix']}")
    print()
    if ok:
        print("Connection usable.")
    elif problems:
        print("This connection is NOT usable yet -- see the FAIL rows.")
    else:
        # NOT the same sentence as a failure. Nothing here says the settings are wrong -- it says
        # nobody checked them. Telling a user their password is bad when it was never tested is the
        # confident-wrong-diagnosis this project has already shipped once.
        names = ", ".join(c["name"] for c in unverified)
        print(f"Connection NOT VERIFIED -- {len(unverified)} check(s) could not run: {names}.")
        print("Your settings may be correct; NPDev could not test them. The reason is on each warn")
        print("row above -- usually that this machine has no JDBC driver for this engine yet.")
        print("Run `npdev setup` (or build an app once) to fetch it, then test again.")
    return exit_code


# M14: the five environment operations, mapped to the scripts the GENERATOR already emits.
#
# Nothing here knows how to start a database. Each entry names a generated script, and that script
# does the work by reading `resolved-db-plan.json` and branching on `profile.kind` -- which is what
# made the five byte-identical across Postgres, MySQL and SQL Server (E15). A second implementation
# in Python (or in the Manager's Rust) would be a new twin to drift, and this project has already
# paid for that class of bug more than once.
_DB_OPERATIONS = {
    "start": ("Start-Environment.ps1", "Start this app's database."),
    "stop": ("Stop-Environment.ps1", "Stop it, leaving the data in place."),
    "status": ("Status-Environment.ps1", "Say whether it is running."),
    "connection": ("Print-DbConnectionInfo.ps1", "Print the connection details, for DBeaver or psql."),
    "reset": ("Reset-Environment.ps1", "DELETE the data and start clean."),
}
_DB_RESET_CONFIRMATION = "I_UNDERSTAND_DB_DATA_WILL_BE_DELETED"


def _find_ops_root(app_path: str | None) -> tuple[Path, bool] | None:
    """The `_ops` toolbox for an app, and whether it is the pre-QUAL-3 SHARED one.

    Resolved HERE, once, rather than in the Manager: a second derivation in Rust would be a twelfth
    copy of "where does the build output live", and REG-144 is what eleven copies of that question
    already cost.

    Since QUAL-3 the generator writes the toolbox INSIDE the FinalApp
    (`OperationalRunbookEmitter`: `finalAppRoot.resolve("_ops")`), so an app can never share one.
    Two app-local shapes are accepted, because `--app` may reasonably name either directory:

        <app>/_ops           the FinalApp itself was given
        <app>-app/_ops       the model directory was given (`npdev init`'s own layout: `npdev init
                             D:\\Apps\\my-app` generates into `D:\\Apps\\my-app-app`)

    The legacy shared location is tried LAST and reported, never preferred. That ordering is the
    whole trap in this fix: an app generated before the change and one generated after can both be
    present, and a fallback consulted first would hand the NEW app the OLD shared toolbox -- which
    is the bug, reintroduced inside its own fix. Silence would be just as bad, so the caller
    announces it.
    """
    base = Path(app_path).expanduser().resolve() if app_path else Path.cwd()
    for candidate in (base / "_ops", base.parent / (base.name + "-app") / "_ops"):
        if candidate.is_dir():
            return candidate, False
    legacy = base.parent / "_ops"
    if legacy.is_dir():
        return legacy, True
    return None


def _find_powershell() -> str | None:
    """A PowerShell able to run the generated scripts, or None.

    `pwsh` first, then Windows PowerShell -- both were measured running the emitted
    `Status-Environment.ps1` unchanged, so requiring the 7.x install would have been a needless
    dependency on a machine that already has 5.1. Returns None rather than guessing: a
    "powershell not found" sentence is a fixable answer, and a silent no-op is not.
    """
    for candidate in ("pwsh", "powershell"):
        found = shutil.which(candidate)
        if found:
            return found
    return None


def run_db_operation(args: argparse.Namespace) -> int:
    """Drive one of the generated `_ops` scripts.

    M14 exists because the Manager's whole purpose is to remove the terminal, and the database
    toolbox was terminal-only: a user could pick MySQL in a window and then had to open PowerShell
    to start it. This is the CLI half -- the Manager calls these commands rather than the scripts,
    keeping `_ops` location and PowerShell discovery in one place.
    """
    operation = args.db_command
    script_name, _ = _DB_OPERATIONS[operation]

    if operation == "reset" and args.confirm != _DB_RESET_CONFIRMATION:
        # Refused HERE as well as in the script. The script's own guard is the real one; this exists
        # so the refusal is identical whether a human typed the command or a button sent it -- a
        # button is much easier to press than this token is to type.
        raise CliError(
            f"Reset refused: it DELETES this app's data. Re-run with --confirm {_DB_RESET_CONFIRMATION}")

    located = _find_ops_root(getattr(args, "app", None))
    if located is None:
        raise CliError(
            "no _ops toolbox found for this app. It is written when the app is generated -- run "
            "`npdev run app` (or `npdev dev`) once, then try again.")
    ops_root, is_legacy = located
    script = ops_root / script_name
    if not script.is_file():
        raise CliError(f"{script} does not exist. Regenerate the app to refresh its _ops toolbox.")

    shell = _find_powershell()
    if shell is None:
        raise CliError(
            "no PowerShell found (looked for `pwsh`, then `powershell`). The generated database "
            "toolbox is PowerShell, so these five operations need one. Install PowerShell 7 "
            "(https://aka.ms/powershell), or run the scripts in " + str(ops_root) + " yourself.")

    # WHICH app this toolbox actually describes, stated every time.
    #
    # `_ops` is written to the PARENT of the generated FinalApp root, so two apps scaffolded into
    # the same folder share ONE `_ops`, and the second generation overwrites the first's
    # resolved-db-plan.json -- container name included (QUAL-3). Nothing here can safely guess which
    # was intended, and a heuristic that refused the legitimate case would be worse than the
    # ambiguity. So it is made VISIBLE instead: every operation reports the appId and FinalApp path
    # it is about, which turns "Reset silently deleted the other app's data" into something the
    # operator can see before pressing the button a second time.
    if is_legacy:
        # Never silent. This toolbox is SHARED with every other app generated into the same folder
        # (QUAL-3), so it may describe a different app than the one asked about -- which is exactly
        # how Reset destroyed the wrong data. Say so before doing anything, on stderr so `--json`
        # stdout stays parseable.
        print(f"npdev: using the legacy SHARED toolbox at {ops_root} -- it may describe a different "
              f"app than the one you named. Regenerate this app to give it its own.", file=sys.stderr)

    target = {}
    plan_path = ops_root / "resolved-db-plan.json"
    if plan_path.is_file():
        try:
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            # PORT-2: finalAppPath is recorded RELATIVE to the FinalApp root ('.'), because an
            # absolute one made a copied app's toolbox operate the original. Resolved here against
            # the same anchor the PowerShell half uses -- the _ops directory's parent -- so this
            # line still prints the app a human can go and look at. An absolute value (an older
            # plan, or the legacy shared toolbox) is passed through unchanged.
            raw_app_path = str(plan.get("finalAppPath") or ".")
            final_app_path = (raw_app_path if os.path.isabs(raw_app_path)
                              else str((ops_root.parent / raw_app_path).resolve()))
            target = {"appId": plan.get("appId"), "finalAppPath": final_app_path,
                      "engine": plan.get("engine"), "containerName": plan.get("containerName")}
        except (json.JSONDecodeError, OSError):
            target = {}

    command = [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)]
    if operation == "reset":
        command += ["-Confirm", _DB_RESET_CONFIRMATION]

    completed = subprocess.run(command, capture_output=True, text=True, check=False)
    output = (completed.stdout or "") + (completed.stderr or "")

    if getattr(args, "json", False):
        print(json.dumps({
            "schemaVersion": "npdev-cli-result.v1",
            "command": f"db {operation}",
            "ok": completed.returncode == 0,
            "exitCode": completed.returncode,
            # The generated scripts print human text, not JSON. Passed through verbatim rather than
            # parsed into a shape this file invents: the script is the source of truth about what it
            # did, and re-describing its output here would be a second story to keep true.
            "output": output.strip(),
            "script": str(script),
            "target": target,
        }, indent=2))
        return completed.returncode

    if target.get("appId"):
        print(f"[{target['appId']} | {target.get('engine')} | {target.get('finalAppPath')}]")
    print(output, end="" if output.endswith("\n") else "\n")
    return completed.returncode


def _scrapforai_check() -> dict:
    """MONITOR_PLAN F1: the browser-exploration engine, reported as a FACT rather than as the
    presence of a setting.

    F1 originally said "the doctor row reports whether `scrapforai_root` is configured". That is the
    wrong question, and D9 replaced it: a machine with the engine installed and no setting is fine,
    and a machine with a setting pointing at a deleted directory is not. So the row reports what the
    four-step detection ACTUALLY FOUND -- running on :3010, installed but stopped, or not found.

    Always a `warn` at worst, never a `fail`. Browser exploration is an optional capability; a
    machine without it can still author, generate, build and run apps, and a doctor that goes red
    over an optional tool teaches people to ignore red."""
    engine = npdev_monitor.detect_engine(
        npdev_monitor.DEFAULT_ENGINE_PORT,
        os.environ.get("SCRAPFORAI_ROOT"),
        repo_root(),
    )
    if engine["state"] == "running":
        return _check("scrapforai-engine", "Browser exploration engine", "pass",
                      found=engine["endpoint"], expected="optional",
                      detail=f"running on {engine['endpoint']} -- `npdev explore run` will reuse it")
    if engine["state"] == "installed-stopped":
        return _check("scrapforai-engine", "Browser exploration engine", "pass",
                      found=engine["root"], expected="optional",
                      detail=f"installed at {engine['root']} (found via {engine['via']}), not running "
                             "-- `npdev explore run` starts it when needed")
    return _check("scrapforai-engine", "Browser exploration engine", "warn", expected="optional",
                  detail="not found on this machine -- browser explorations are unavailable until it "
                         "is installed. Everything else works without it.",
                  fix="install ScrapForAI, or set SCRAPFORAI_ROOT if it lives somewhere unusual")


def _git_present_check() -> dict:
    """git, reported as OPTIONAL -- a warning at worst, never a failure (W1.2, 2026-08-10).

    Two facts had to be reconciled. docs/MANAGER.md advertises a machine with nothing on it, and the
    Manager genuinely never installs git -- versions arrive as an HTTPS zip download
    (`versions.rs::install_version`). Meanwhile this check failed HARD when git was absent, which
    took the Ready screen red on precisely the machine the Manager exists for, and its detail named
    two reasons that are both false: nothing in the Manager's path clones anything, and `npdev init`
    now scaffolds without git and says so (`_scaffold_git_history`).

    `_scrapforai_check` already wrote down the rule this follows -- a doctor that goes red over an
    optional tool teaches people to ignore red -- and MANAGER.md's own check table has described git
    as history-only the whole time. This is the code agreeing with the document, not the document
    being edited down to match a hard failure nobody wanted.
    """
    git_path = shutil.which("git")
    if git_path is not None:
        return _check("git-present", "git", "pass", found=git_path,
                      expected="installed and on PATH")
    return _check(
        "git-present", "git", "warn", expected="optional -- version history only",
        detail="git not found on PATH. Nothing NPDev does needs it: apps scaffold, build and run "
               "without it, and `npdev init` tells you when it could not create a repository. Only "
               "your app's own version history is affected.",
        fix="Install git from https://git-scm.com/downloads to get a repository per app",
    )


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

    # The precedence itself lives in `java_launcher()` (W1.3) so that doctor and the two launch
    # sites cannot drift apart -- this used to be the only place that expressed it, which is exactly
    # how `npdev run app` ended up starting apps with a different java than doctor reported on.
    # java_home_bin/path_java are still read separately just above, because java-home-agreement is
    # about the DISAGREEMENT between them and needs both.
    launcher = java_launcher()
    java_bin = Path(launcher) if launcher else None

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
            if not same and _managed_jdk():
                # The Manager sets JAVA_HOME to its OWN private JDK, for the spawned process only,
                # and deliberately leaves PATH alone (M3). On any machine that already has Java --
                # which is most developers' -- that guarantees a disagreement, BY DESIGN. Reporting
                # it as a fault would fail doctor on the first Ready screen for every such user, and
                # the old fix text ("Set JAVA_HOME to your Java 17 installation") told them to change
                # something that was already correct.
                #
                # This reads a DECLARATION from the Manager (NPDEV_MANAGED_JDK), not the shape of the
                # path: "is this directory ours?" answered by guessing is REG-144's family.
                checks.append(_check(
                    "java-home-agreement", "JAVA_HOME", "pass", found=java_home,
                    expected="set by the Manager to its own private JDK",
                    detail=(
                        f"JAVA_HOME={java_home} is the Manager's private JDK and does not match the "
                        f"`java` on PATH ({path_java}). That is intended: the Manager sets JAVA_HOME "
                        f"for the processes it starts and never touches your PATH, so your own Java "
                        f"stays exactly as it was. Gradle follows JAVA_HOME, so builds use the "
                        f"private JDK."
                    ),
                ))
            elif not same:
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

    checks.append(_git_present_check())

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

    # Docker is "optional" IN GENERAL and false for the choice this particular user made. An app on
    # Postgres, MySQL or SQL Server gets its database created by `docker run`, so telling that user
    # Docker is optional -- on the Ready screen, before they find out the hard way -- is exactly the
    # true-in-general/false-for-you failure the capability work removed everywhere else.
    #
    # Keyed on `containerized`, not `needsServer`: H2Server is a server engine whose environment is a
    # Java process, so Docker really is optional for it.
    docker_target = _containerized_engine_target(getattr(args, "app", None))
    # STOR-14 (item 5): a DECLARATION BEATS AN INFERENCE the moment one is available. The port probe
    # further down is the right answer while there is nothing to declare -- it can only ever conclude
    # "something is listening", never "that something is your database". `externallyProvisioned: true`
    # is the user saying outright that this server is theirs, so Docker is not required to reach it
    # and reporting it as required (or as "probably fine, something answered") would be NPDev
    # guessing at a fact it has been told.
    docker_external = docker_target is not None and bool(docker_target[1].get("externallyProvisioned"))
    if docker_external:
        docker_target = None
    docker_engine = docker_target[0]["externalName"] if docker_target is not None else None
    docker_path = shutil.which("docker")
    if docker_external:
        checks.append(_check(
            "docker-present", "Docker", "pass",
            found=docker_path or "not installed",
            expected="not required -- this app's database is externally provisioned",
            detail="db.definition.json declares database.externallyProvisioned = true, so NPDev "
                   "never creates a container for this app. Whether the server is reachable and "
                   "usable is what the database checks below answer.",
        ))
    elif docker_engine is None:
        if docker_path is None:
            checks.append(_check(
                "docker-present", "Docker", "warn", expected="optional",
                detail="Docker not found -- only needed for the docker-compose run path.",
            ))
        else:
            checks.append(_check("docker-present", "Docker", "pass", found=docker_path, expected="optional"))
    elif docker_path is None:
        # Docker is how NPDev CREATES a database for you. It is not how an app REACHES one. An
        # externally-provisioned server -- a managed instance, or a PostgreSQL the user installed
        # themselves years ago -- makes this app perfectly runnable with no Docker anywhere, and
        # calling that machine broken is the same true-in-general/false-for-you failure the branch
        # above already avoids in the other direction.
        #
        # So: probe the port this app is actually configured to use, with the same probe
        # database-reachable uses, and DOWNGRADE to a warning when something answers.
        #
        # The message must state the INFERENCE, not assert a fact. The probe knows something is
        # listening on that port; it does not know it is your database. Both branches are named and
        # neither is assumed -- otherwise this trades one confident wrong answer for another, and
        # this codebase has already shipped a doctor that called a missing database a *credentials*
        # failure. `npdev db test-connection` is what actually settles it, and it is named here.
        docker_db = docker_target[1]
        docker_host = docker_db.get("host") or "localhost"
        docker_port = int(docker_db.get("port") or docker_target[0]["port"])
        if _probe_tcp(docker_host, docker_port, timeout=1.5) is None:
            checks.append(_check(
                "docker-present", "Docker", "warn",
                expected="optional when the database is already provided",
                detail=f"Docker not found. Something is already serving on {docker_host}:"
                       f"{docker_port}, so if that is your own {docker_engine} you do not need "
                       f"Docker to reach it -- `npdev db test-connection` will confirm. If you "
                       f"meant NPDev to create a database for you, install Docker or free the port.",
            ))
        else:
            checks.append(_check(
                "docker-present", "Docker", "fail", expected="required",
                detail=f"Docker not found, and this app's engine is {docker_engine} -- NPDev creates "
                       f"its database in a container, so `npdev db start` cannot work without it. "
                       f"Nothing is listening on {docker_host}:{docker_port} either, so there is no "
                       f"already-running server to use instead.",
                fix="Install Docker Desktop (https://docs.docker.com/get-docker/), point "
                    "db.definition.json at a database you already run, or choose H2Local, which "
                    "needs no server at all.",
            ))
    else:
        checks.append(_check("docker-present", "Docker", "pass", found=docker_path, expected="required",
                             detail=f"required by this app's engine ({docker_engine})"))

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
    checks.append(_scrapforai_check())

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


AI_TOOLS_VALIDATOR_MAIN = "com.npdev.dsl.v1.cli.ModelValidatorMain"
AI_TOOLS_CLASSIFIER_MAIN = "com.npdev.generator.schemaevolution.ModelChangeClassifierMain"


def _default_ai_tools_jar() -> Path | None:
    """R1.1 (warm standalone validator): the staged npdev-ai-tools.jar, or None to use Gradle.

    Every `npdev validate model --semantic` used to fork the Gradle wrapper to reach
    ModelValidatorMain, and so did every classifyModelChange call. Both Main classes are pure
    stdlib+Jackson+dsl -- no Spring, no database, no codegen -- so the whole Gradle layer was buying
    nothing but a classpath. NPDevGenerator/generator/build.gradle's `aiToolsJar` task packages both
    entry points with their runtime deps, and scripts/runtimehost/sync-runtimehost-libs.ps1 stages it
    here.

    Measured on this machine (canonical-demo, 2026-08-18, 8 interleaved A/B pairs of
    `npdev validate model --semantic`): median 4.61s -> 2.24s, min 3.67s -> 1.82s, 2.1x; the first
    call of a session cost 24.4s on the Gradle path because it also started the daemon. Roughly 0.4s
    of each figure is this CLI's own Python startup, which neither path avoids. Process inspection
    (Win32_Process sampled every 100ms for the duration of the call) counted 0 new Gradle processes
    on this path against 3 on the Gradle one -- `cmd.exe /c gradlew.bat`, the wrapper's own
    `-Dorg.gradle.appname=gradlew` client JVM, and the daemon-side JVM. Note the Gradle figure is
    already a WARM-daemon figure: gradle.properties sets org.gradle.daemon=true and this call site
    never passed --no-daemon, so what is being removed is the client bootstrap and per-call project
    configuration, not a single-use JVM.

    Returns None whenever the jar is absent, and EVERY caller then runs the Gradle path unchanged --
    a fresh checkout that has never run the sync script must keep working exactly as before, so this
    is an accelerator with a mandatory fallback, not a new prerequisite.

    NPDEV_AI_TOOLS_JAR overrides the location (same shape as NPDEV_RUNTIMEHOST_LIBS_DIR); pointing it
    at a path that does not exist is also the way to force the Gradle path back on for one run.
    Otherwise the build root comes from _ai_build_root() rather than a fourth hand-rolled copy of the
    same resolution -- REG-128 exists because there were two, and REG-144 because one of them matched
    the repo root by directory NAME. There is no hardcoded <drive>:/WorkSpace/... default here for
    the same reason _default_runtimehost_libs_dir() above has none.
    """
    override = os.environ.get("NPDEV_AI_TOOLS_JAR", "").strip()
    if override:
        candidate = Path(override).expanduser()
    else:
        candidate = _ai_build_root() / "ai-tools" / "npdev-ai-tools.jar"
    return candidate if candidate.is_file() else None


def _ai_tools_command(main_class: str, cli_args: list[str]) -> list[str] | None:
    """The `java -cp <jar> <main_class> ...` form of an AI-loop call, or None when it is unavailable.

    None means either "no staged jar" or "no java anywhere" (java_launcher() already prefers
    JAVA_HOME over PATH, W1.3 -- the Manager hands its private JDK over as JAVA_HOME alone). Both
    are ordinary states on a fresh machine, and both must land on the Gradle path rather than fail.
    """
    jar = _default_ai_tools_jar()
    if jar is None:
        return None
    java = java_launcher()
    if java is None:
        return None
    return [java, "-cp", str(jar), main_class, *cli_args]


# The classifier's own flag names, mapped to the Gradle PROPERTIES :generator:classifyModelChange
# reads (its JavaExec builds these exact flags back out of them). One table, so the two call sites
# below cannot drift into disagreeing about which spelling belongs to which path.
_CLASSIFIER_GRADLE_PROPERTIES = {
    "--current": "currentPath",
    "--baseline": "baselinePath",
    "--out": "reportOut",
    "--emitCompiledModelTo": "emitCompiledModelTo",
    "--emitMetadataTo": "emitMetadataTo",
}


def _classifier_command(root: Path, cli_args: list[str]) -> tuple[list[str], Path] | None:
    """R1.1: (command, cwd) for one ModelChangeClassifierMain call -- direct java when the staged
    jar is there, otherwise the unchanged :generator:classifyModelChange Gradle invocation. Returns
    None when neither is available (no jar AND no Gradle wrapper), which both callers already treat
    as "cannot classify"."""
    generator_root = root / "NPDevGenerator"
    direct = _ai_tools_command(AI_TOOLS_CLASSIFIER_MAIN, cli_args)
    if direct is not None:
        return direct, generator_root

    wrapper = gradle_wrapper(generator_root)
    if not wrapper.exists():
        return None
    command = [
        str(wrapper), *gradle_project_cache_args("generator"),
        ":generator:classifyModelChange", "--no-daemon", "--console=plain",
    ]
    for flag, value in zip(cli_args[0::2], cli_args[1::2]):
        command.append(f"-P{_CLASSIFIER_GRADLE_PROPERTIES[flag]}={value}")
    if os.name == "nt" and wrapper.suffix.lower() == ".bat":
        command = ["cmd.exe", "/c"] + command
    return command, generator_root


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
    ModelChangeClassifierMain rather than reimplementing the diff. R1.1: reached directly through the
    staged npdev-ai-tools.jar when it exists, else through :generator:classifyModelChange, whose task
    reads its arguments as Gradle PROPERTIES (-PcurrentPath=...), not JavaExec `args` --
    _classifier_command owns that translation for both call sites."""
    with tempfile.TemporaryDirectory(prefix="npdev-classify-") as tmp:
        report_path = Path(tmp) / "classification.json"
        resolved = _classifier_command(root, [
            "--current", str(current), "--baseline", str(baseline), "--out", str(report_path),
        ])
        if resolved is None:
            return None
        command, cwd = resolved
        try:
            _run_bounded(command, cwd, deadline)
        except _DeadlineExceeded:
            return None
        if not report_path.exists():
            return None
        try:
            report = json.loads(report_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return None
        return report.get("classification")


def _classify_model_change_report(root: Path, baseline: Path, current: Path, deadline: float) -> dict:
    """R1.6 (`npdev impact`): the same `_classifier_command`/`_run_bounded` R1.1 fast path
    `_classify_model_change` above already uses, but keeping the FULL parsed report (classification
    + classificationReasons + the MigrationPlan) instead of collapsing it to just the classification
    string -- `impact` is a preview report, not a boolean gate, so the reasons are the point. Raises
    CliError on any failure (no jar/no Gradle wrapper, a timeout, an unparseable report) rather than
    `_classify_model_change`'s `None`: that function's callers (`run_closed_loop`) treat "could not
    classify" as merely informational inside a larger pipeline that already stopped on a real
    failure elsewhere, but `impact` has no such earlier gate -- silently omitting this leg would be
    exactly the "looks complete but is not" failure STEP 3 of this item's own brief warns against."""
    with tempfile.TemporaryDirectory(prefix="npdev-impact-classify-") as tmp:
        report_path = Path(tmp) / "classification.json"
        resolved = _classifier_command(root, [
            "--current", str(current), "--baseline", str(baseline), "--out", str(report_path),
        ])
        if resolved is None:
            raise CliError("cannot classify the model change: no staged npdev-ai-tools.jar and no "
                            "Gradle wrapper found.")
        command, cwd = resolved
        try:
            completed = _run_bounded(command, cwd, deadline)
        except _DeadlineExceeded:
            raise CliError("migration classification exceeded the overall --timeout budget.")
        if not report_path.exists():
            detail = ((completed.stdout or "") + (completed.stderr or "")).strip()
            raise CliError("migration classification did not produce a report"
                            + (f": {detail[-500:]}" if detail else ""))
        try:
            return json.loads(report_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise CliError(f"migration classification report is not valid JSON: {exc}")


def _metadata_only_fast_path(
        root: Path, current_model: Path, final_app_out: Path, deadline: float) -> tuple[bool, str]:
    """Move 10 C2 (already implemented, Wave 1.3): swaps compiled-model.json + re-signs the
    generated-folder signature, skipping GENERATE+BUILD entirely for a METADATA_ONLY change.
    Both underlying tasks read Gradle PROPERTIES, not JavaExec `args` -- see their own build.gradle
    registrations (classifyModelChange / resignGeneratedFolder).

    R1.1 speeds up the classify half only: GeneratedFolderSignatureMain lives in
    :adapters:runtime-validation, which is not on :generator's runtime classpath and so is not in
    npdev-ai-tools.jar. Re-signing runs once per fast path, not once per keystroke in an authoring
    loop, so packaging a second jar to save one more fork is not worth the staging surface.

    One deliberate divergence between the two paths, in the direction of safety: classifyModelChange
    is registered with `ignoreExitValue = true`, so a `--emitCompiledModelTo` REFUSAL (the classifier
    exits 2 without writing) still leaves the GRADLE process exiting 0 and this function reporting
    success for a file it never wrote. Its build.gradle comment claims that exit code "must reach the
    caller" -- with ignoreExitValue it never did. The direct path propagates the real exit code, so a
    refusal is reported as a failure. The branch is unreachable in practice today (the only caller
    gates on classification == METADATA_ONLY first), which is why the swallowed code went unnoticed;
    it is left as-is on the Gradle side rather than changing behavior on the fallback path here."""
    generated_root = final_app_out / "npdev-generated"
    compiled_model_path = generated_root / "src" / "main" / "resources" / "npdev" / "compiled-model.json"
    with tempfile.TemporaryDirectory(prefix="npdev-metadata-only-") as tmp:
        report_path = Path(tmp) / "classification.json"
        resolved = _classifier_command(root, [
            "--current", str(current_model), "--baseline", str(current_model),
            "--out", str(report_path), "--emitCompiledModelTo", str(compiled_model_path),
        ])
        if resolved is None:
            return False, "no Gradle wrapper and no staged npdev-ai-tools.jar -- cannot classify."
        command, cwd = resolved
        try:
            completed = _run_bounded(command, cwd, deadline)
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


# ---------------------------------------------------------------------------
# R3.4: `npdev test` -- one verb, one verdict per app. Composition only: it OWNS no runner and no
# verdict of its own. The REST layer is derived from what the generator already published about the
# model; the other two layers are `run_acceptance` and `npdev_explore.run_suite`, called as-is, and
# their reports are embedded verbatim rather than re-summarised into a second vocabulary.
# ---------------------------------------------------------------------------

TEST_SCHEMA_VERSION = "npdev-test-report.v1"
TEST_REPORT_FILENAME = "npdev-test-report.json"


def _rest_smoke_layer(app_record: dict, timeout: float = 15.0) -> dict:
    """Layer 1: GET every concept endpoint the app itself publishes.

    The plan is MODEL-DERIVED with no per-app file, because `InfoPageEmitter` already wrote the
    model's concepts into the app's own `static/info.json` (`concepts: [{name, route}]`, sorted) and
    `probe_app(include_info=True)` already loads it. `/api/<route>` is the same URL that emitter
    publishes one line away in its own Concepts rows, so this composes a path the app is known to
    serve rather than inventing a convention.

    (`compiled-metadata.json`'s invocation catalog carries the sibling `/api/concepts/<table>` form
    and is equally model-derived. info.json wins here only because it needs no second file read: it
    is already in hand from the probe every layer of this command shares.)

    Check rows deliberately borrow `schemas/ai/ai-rest-smoke-result.schema.json`'s field names
    (id/status/method/path/expectedStatus/actualStatus/durationMs/failures) so the one REST-smoke
    vocabulary this repo has is not forked into a second one."""
    import time
    import urllib.error
    import urllib.request

    info = app_record.get("info") or {}
    concepts = [c for c in (info.get("concepts") or [])
                if isinstance(c, dict) and c.get("name") and c.get("route")]
    base_url = app_record.get("probeBaseUrl")
    layer: dict = {"layer": "rest-smoke", "source": "info.json concepts (model-derived)",
                   "baseUrl": base_url, "checks": [],
                   "counts": {"total": 0, "passed": 0, "failed": 0}}
    if not app_record.get("hasInfoJson"):
        layer.update(status="empty", green=None, detail=(
            "this app has no generated static/info.json, so there is no published concept list to "
            "derive a plan from. Regenerate it with a current generator."))
        return layer
    if not concepts:
        layer.update(status="empty", green=None,
                     detail="the app publishes no concepts, so there is no endpoint to GET.")
        return layer

    headers = {}
    if app_record.get("apiKey"):
        headers[app_record.get("authHeader") or "X-Api-Key"] = app_record["apiKey"]

    for concept in concepts:
        path = "/api/" + concept["route"]
        started = time.time()
        actual: int | None = None
        failures: list[str] = []
        request = urllib.request.Request(base_url + path, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                actual = response.status
                response.read()
        except urllib.error.HTTPError as exc:
            actual = exc.code
            failures.append(f"HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')[:200]}")
        except urllib.error.URLError as exc:
            failures.append(f"request failed: {exc.reason}")
        # First, so the expectation leads and the body follows. A request that never completed has
        # no status to compare and its own `request failed: <reason>` already says more than
        # "expected 200, got nothing" would.
        if actual is not None and actual != 200:
            failures.insert(0, f"expected HTTP 200, got {actual}")
        layer["checks"].append({
            "id": f"concept-list-{concept['name']}",
            "concept": concept["name"],
            "status": "passed" if not failures else "failed",
            "method": "GET", "path": path,
            "expectedStatus": 200, "actualStatus": actual,
            "durationMs": int((time.time() - started) * 1000),
            "failures": failures,
        })

    passed = sum(1 for c in layer["checks"] if c["status"] == "passed")
    layer["counts"] = {"total": len(layer["checks"]), "passed": passed,
                       "failed": len(layer["checks"]) - passed}
    layer["green"] = layer["counts"]["failed"] == 0
    layer["status"] = "green" if layer["green"] else "red"
    return layer


def acceptance_dirs(app_record: dict) -> list[Path]:
    """Where an app's `*.scenario.json` files can live, in precedence order, with no per-app config.

    Same layering `npdev_explore.definition_dirs` uses for routines and for the same reason: the app
    DEFINITION (layer 2) is the truth, but a FinalApp handed over on its own must still be testable,
    so the app's own copy is looked at too. The generated app root is listed first because a copy
    that travelled with the app is the one that describes THAT build."""
    directories = []
    for root in (app_record.get("finalAppRoot"), app_record.get("appDir"),
                 app_record.get("appDefinitionRoot")):
        if not root:
            continue
        candidate = Path(root) / "acceptance"
        if candidate not in directories:
            directories.append(candidate)
    return directories


def _acceptance_layer(app_record: dict) -> dict:
    """Layer 2: the existing `run_acceptance`, pointed at the first discovered scenario directory.

    `--base-url` mode, always: the app is already booted (this command refuses otherwise), and D2's
    own note says that mode exists precisely so a caller that already paid for a boot does not pay
    for a second one. Its report is embedded whole -- including its approved/unapproved rule, which
    is not restated here because there must be one place that decides it."""
    searched = acceptance_dirs(app_record)
    layer: dict = {"layer": "acceptance", "searched": [str(d) for d in searched],
                   "scenariosDir": None, "report": None}
    chosen = next((d for d in searched if d.is_dir() and any(d.glob("*.scenario.json"))), None)
    if chosen is None:
        layer.update(status="empty", green=None, detail=(
            "no *.scenario.json found in: " + ("; ".join(str(d) for d in searched) or "(nowhere to look)")))
        return layer

    layer["scenariosDir"] = str(chosen)
    report = run_acceptance(argparse.Namespace(
        base_url=app_record["probeBaseUrl"], scenarios=str(chosen),
        api_key=app_record.get("apiKey") or "dev-key",
        model=None, config=None, output=None, port=None, timeout=None,
    ))
    layer["report"] = report
    layer["green"] = bool(report.get("ok"))
    layer["status"] = "green" if layer["green"] else "red"
    return layer


def _browser_layer(root: Path, app_dir: Path, args: argparse.Namespace) -> dict:
    """Layer 3: `npdev_explore.run_suite`, verbatim.

    "No routines at all" is asked HERE, before calling the suite, because `run_suite` raises the same
    ExploreError for that as for an app-wide refusal, and those are different facts: an app that
    declares no browser routines has no browser coverage to report (`empty`), while an app whose
    engine or lock refuses is a refusal (`refused`, and not green). `explore generate` (R3.3) can
    fill that gap, but it is a separate, deliberate step, not something this layer calls itself --
    so `empty` stays the ORDINARY state for an app nobody has run it on, and must not read as either
    a pass or a failure.

    Everything else -- per-routine refusals, app-wide aborts, unreached routines reported as skipped
    -- is the suite's own decision and is inherited unchanged."""
    import npdev_explore

    layer: dict = {"layer": "browser", "report": None}
    definitions = npdev_explore.definition_files(app_dir)
    if not definitions:
        layer.update(status="empty", green=None, detail=(
            "this app declares no browser routines in: "
            + "; ".join(str(d) for d in npdev_explore.definition_dirs(app_dir))
            + " -- so no browser coverage was measured (not a pass, and not a failure)."))
        return layer
    try:
        # `keep_engine=False`, the suite's own default: a one-shot verb must not leave a process
        # behind, and `_stop_process` only ever stops an engine THIS run started, so an engine that
        # was already up is untouched either way. The cost is one engine startup per routine; an app
        # with enough routines for that to matter should pre-start an engine, or drive
        # `explore suite --keep-engine` directly, rather than have this command silently change what
        # is running on the machine.
        report = npdev_explore.run_suite(
            root, app_dir,
            engine_port=args.engine_port, configured_root=args.engine_root,
            api_key=args.engine_api_key)
    except npdev_explore.ExploreError as exc:
        layer.update(status="refused", green=False, detail=str(exc))
        return layer
    layer["report"] = report
    layer["green"] = bool(report.get("green"))
    layer["status"] = "green" if layer["green"] else "red"
    return layer


def run_test(args: argparse.Namespace) -> dict:
    """R3.4: compose the three layers against ONE booted app and roll them into one verdict.

    A layer that is `empty` is neither green nor red -- it measured nothing, says so, and is counted
    separately. `ok` is false when any layer is red or refused, which is what the exit code carries.

    Refusing outright (rather than writing a report full of zeros) when the app is not a healthy
    generated app is the D4/QUAL-4 rule: a tool problem must not be rendered as a test result.
    `probe_app` already owns that diagnosis, so its own sentence is the one the user gets."""
    import time

    root = repo_root()
    app_dir = Path(args.app_dir).expanduser().resolve()
    app_record = npdev_monitor.probe_app(app_dir, include_info=True)
    if not app_record.get("isAppRoot"):
        raise CliError(app_record.get("detail") or f"not a generated NPDev app: {app_dir}")
    if app_record.get("health") != "running":
        raise CliError(
            f"{app_record.get('name')} is not answering ({app_record.get('health')}): "
            f"{app_record.get('healthDetail')}. `npdev test` measures a RUNNING app -- start it "
            f"first (_ops/Start-App.ps1, or the Monitor's Start).")

    started_at = _utc_now()
    begin = time.time()
    layers = [
        _rest_smoke_layer(app_record),
        _acceptance_layer(app_record),
        _browser_layer(root, app_dir, args),
    ]
    counts = {
        "layers": len(layers),
        "green": sum(1 for layer in layers if layer["status"] == "green"),
        "red": sum(1 for layer in layers if layer["status"] in ("red", "refused")),
        "empty": sum(1 for layer in layers if layer["status"] == "empty"),
    }
    # A run in which NOTHING measured anything is not a pass -- `run_suite`'s rule ("a summary of
    # zero runs reads like a pass"), one level up. It is still reported rather than raised, because
    # unlike a suite there IS something to say: which three places were looked at and what was not
    # there, which is the actionable half of the answer.
    nothing_measured = counts["green"] == 0 and counts["red"] == 0
    green = counts["red"] == 0 and not nothing_measured
    report = {
        "schemaVersion": TEST_SCHEMA_VERSION,
        "command": "test",
        "ok": green,
        "green": green,
        "nothingMeasured": nothing_measured,
        "appDir": str(app_dir),
        "appName": app_record.get("name"),
        "baseUrl": app_record.get("probeBaseUrl"),
        "startedAt": started_at,
        "durationMs": int((time.time() - begin) * 1000),
        "counts": counts,
        "layers": layers,
    }

    # Written where the app's other run artifacts already live -- `_ops/smoke-test-report.json`, the
    # generated toolbox's own, is the neighbour.
    #
    # NOT passed through `npdev_monitor.redact()`, deliberately, unlike `explore`'s output (REG-153).
    # That function is key-NAME driven and its pattern includes `pass(word)?`, which matches the
    # ordinary word `passed` -- so redacting this report replaces `summary.passed` and
    # `counts.passed` with "<redacted>" and destroys the pass/fail evidence that IS the report.
    # Widening or narrowing a shared security pattern to suit one caller is the wrong trade, so the
    # guarantee here is the stronger one instead: no credential is put in. Every field is composed
    # explicitly from the three layer reports, and the one place this command holds the app's API key
    # is layer 1's request header, which never reaches the result. A test asserts exactly that.
    out = Path(args.report_out).expanduser() if args.report_out else app_dir / "_ops" / TEST_REPORT_FILENAME
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    report["reportPath"] = str(out)
    return report


def _test_human_summary(result: dict) -> str:
    verdict = "GREEN" if result["green"] else ("NOTHING MEASURED" if result["nothingMeasured"] else "RED")
    lines = [f"{verdict}  {result['appName']}  {result['baseUrl']}  ({result['durationMs']} ms)"]
    for layer in result["layers"]:
        lines.append(f"  [{layer['status']:<7}] {layer['layer']}")
        if layer.get("detail"):
            lines.append(f"      {layer['detail']}")
        if layer["layer"] == "rest-smoke":
            for check in layer["checks"]:
                if check["status"] != "passed":
                    lines.append(f"      {check['method']} {check['path']} -- "
                                 f"{'; '.join(check['failures'])}")
            if layer["counts"]["total"]:
                lines.append(f"      {layer['counts']['passed']}/{layer['counts']['total']} "
                             f"concept endpoint(s) answered 200")
        elif layer.get("report") and layer["layer"] == "acceptance":
            summary = layer["report"]["summary"]
            lines.append(f"      {summary['passed']}/{summary['approvedTotal']} approved scenario(s) "
                         f"passed, {summary['excludedUnapproved']} unapproved excluded")
            for scenario in layer["report"]["scenarios"]:
                if scenario["approved"] and scenario["outcome"] != "PASS":
                    lines.append(f"      {scenario['file']}: {scenario['outcome']}"
                                 + (f" -- {scenario['error']}" if scenario.get("error") else ""))
                    for assertion in scenario["assertions"]:
                        if not assertion["passed"]:
                            lines.append(f"        {assertion['path']} {assertion['operator']} "
                                         f"{assertion['expected']!r}, got {assertion['actual']!r}")
        elif layer.get("report") and layer["layer"] == "browser":
            counts = layer["report"]["counts"]
            lines.append(f"      {counts['green']}/{counts['total']} routine(s) green, "
                         f"{counts['red']} red, {counts['refused']} refused, {counts['skipped']} skipped")
            # Same rule `_explore_human_summary` follows: `refused` and `skipped` stay visually
            # distinct from `red`, and every non-green routine names its own reason here rather than
            # only in the JSON.
            for entry in layer["report"]["runs"]:
                if entry["outcome"] == "green":
                    continue
                lines.append(f"      [{entry['outcome']}] {entry['name']}")
                for reason in entry.get("reasons") or []:
                    lines.append(f"        {reason}")
    lines.append(f"  report: {result['reportPath']}")
    return "\n".join(lines)


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


SEED_SCHEMA_VERSION = "npdev-seed-cli.v1"


def run_seed(args: argparse.Namespace) -> dict:
    """R3.2: a thin CLI wrapper around `DataSeedAdminController`'s two EXISTING endpoints --
    `GET /api/admin/seeds` (list) and `POST /api/admin/seeds/{id}/run` -- no new server-side
    surface. Same `--app-dir` + `npdev_monitor.probe_app` shape `run_test` already uses: resolve
    the app, refuse (not report zeros) if it isn't a healthy running generated app, then call it
    with whatever `X-Api-Key` the app is actually configured to accept (`apiKey`/`authHeader` from
    the probe -- absent entirely for an `auth.mode=none` dev app, where ADMIN is granted to every
    anonymous caller so no key is needed at all).
    """
    import urllib.error
    import urllib.parse
    import urllib.request
    import time

    if args.seed_command not in ("list", "run"):
        raise CliError("usage: npdev seed {list|run}")

    app_dir = Path(args.app_dir).expanduser().resolve()
    app_record = npdev_monitor.probe_app(app_dir, include_info=True)
    if not app_record.get("isAppRoot"):
        raise CliError(app_record.get("detail") or f"not a generated NPDev app: {app_dir}")
    if app_record.get("health") != "running":
        raise CliError(
            f"{app_record.get('name')} is not answering ({app_record.get('health')}): "
            f"{app_record.get('healthDetail')}. `npdev seed` calls a RUNNING app's admin seed "
            f"endpoints -- start it first (_ops/Start-App.ps1, or the Monitor's Start).")

    base_url = app_record.get("probeBaseUrl")
    headers = {}
    if app_record.get("apiKey"):
        headers[app_record.get("authHeader") or "X-Api-Key"] = app_record["apiKey"]

    if args.seed_command == "list":
        seeds = _fetch_json(base_url + "/api/admin/seeds", headers)
        return {
            "schemaVersion": SEED_SCHEMA_VERSION, "command": "seed list", "ok": True,
            "appDir": str(app_dir), "appName": app_record.get("name"), "baseUrl": base_url,
            "seeds": seeds,
        }

    # seed run
    path = f"/api/admin/seeds/{urllib.parse.quote(args.id, safe='')}/run"
    if args.tenant_id:
        path += "?" + urllib.parse.urlencode({"tenantId": args.tenant_id})
    request = urllib.request.Request(base_url + path, method="POST", headers=headers)
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            report = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:500]
        raise CliError(f"POST {path} -> HTTP {exc.code}: {body}")
    except urllib.error.URLError as exc:
        raise CliError(f"POST {path} failed: {exc.reason}")
    return {
        "schemaVersion": SEED_SCHEMA_VERSION, "command": "seed run", "ok": bool(report.get("ok")),
        "appDir": str(app_dir), "appName": app_record.get("name"), "baseUrl": base_url,
        "durationMs": int((time.time() - started) * 1000), "report": report,
    }


def _seed_human_summary(result: dict) -> str:
    if result["command"] == "seed list":
        seeds = result.get("seeds") or []
        lines = [f"{result['appName']}  {result['baseUrl']}  -- {len(seeds)} seed(s) declared"]
        for seed in seeds:
            detail = f" -- {seed['description']}" if seed.get("description") else ""
            lines.append(f"  {seed['id']}  [{seed.get('kind', 'smart')}]  {seed.get('label')}{detail}")
        if not seeds:
            lines.append("  (this app declares no seeds under definition/seeds/)")
        return "\n".join(lines)

    # seed run
    report = result.get("report") or {}
    verdict = "OK" if report.get("ok") else "FAILED"
    lines = [f"{verdict}  seed '{report.get('seedId')}'  ({result['durationMs']} ms)"]
    for concept, count in (report.get("createdCounts") or {}).items():
        lines.append(f"  {concept}: {count} created")
    if not report.get("ok"):
        lines.append(f"  failed at record[{report.get('failedRecordIndex')}] "
                      f"({report.get('failedConcept')}, alias={report.get('failedAlias')}): "
                      f"{report.get('failureMessage')}")
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------
# R3.7: `npdev bench` -- per-app latency probe with saved baselines.
#
# PREMISES CHECKED FIRST (CLAUDE.md's own warning that several roadmap premises were false):
#   - The nightly scale ladder (`scripts/proofs/run-scale-proof.ps1`,
#     `scripts/policy/scale-proof-baseline.json`, `schemas/ai/scale-proof-report.schema.json`) DOES
#     measure latency+memory and DOES save baselines -- but it is a synthetic-model harness bound to
#     ONE hardcoded panel name (`ScaleProofPanel`) at model-synthesis time, PowerShell-driven, and
#     scoped under `scripts/proofs/` (a different agent's surface this round). It answers "does the
#     platform scale" as a maintenance/nightly concern, not "is THIS app slow right now" as a
#     tester's on-demand question. Extending it to accept an arbitrary already-generated app's
#     concept/panel plan would mean teaching a PowerShell proof script to read the same manifests
#     this module already reads in Python for `test`/`seed`/`explore generate` -- a bigger, riskier
#     change than the roadmap's own `M` sizing implies, for a genuinely different consumer. So this
#     is a NEW verb in the CLI (matching `test`/`seed`'s own shape), not an extension of the ladder;
#     the two intentionally share almost nothing except "measure HTTP latency with repeat samples".
#   - `npdev monitor probe` reports app identity/health, never per-request timing -- nothing to
#     extend there either.
#   - R3.2 (`npdev seed`, generative `$gen` tokens) is already DONE (QUAL-18/QUAL-19), so this item's
#     stated blocker is clear: an app can already be loaded to 100k+ rows before benching it.
#
# ENDPOINT PLAN, model-derived like `_rest_smoke_layer` and `explore generate` (no per-app config):
#   - "concept list" checks reuse the EXACT source `_rest_smoke_layer` already reads
#     (`info.json`'s `concepts: [{name, route}]`, `GET /api/<route>`) -- this is RUN-1's own surface,
#     the landmine RUN-16 fixed part of and this item exists to let people measure without hand-rolling
#     a timing comparison the way RUN-16 had to.
#   - "panel" checks read `generated-ui-manifest.json`'s `panels[]` (the same manifest `explore
#     generate` already parses) and GET `/api/runtime/metadata/ui/panels/<name>`
#     (`RuntimeUiMetadataController.loadPanel`, verified by reading the controller, not assumed).
#     This single generic endpoint IS the roadmap's "query" leg too: a panel's GET already executes
#     its declared dataSource queries server-side and returns their rows -- there is no separate
#     ad-hoc query endpoint outside a panel (confirmed by grep: no `/api/query*` mapping exists
#     anywhere in runtime-host). So "concept list/panel/query" collapses to two endpoint KINDS, not
#     three, and that collapse is stated here rather than silently assumed.
#
# MEASUREMENT HONESTY (the point of this item, and the RUN-16 lesson it exists to generalize):
#   - Every report states its OWN sample count, mean and stdev next to p50/p95 -- never a bare
#     number pretending to be exact.
#   - The regression signal is RELATIVE (new p50 >= --regression-threshold times the saved p50),
#     never an absolute millisecond ceiling. RUN-16 measured a plain "<300ms" absolute threshold
#     flaking at 365ms under ordinary multi-agent machine contention while the SAME code was 167ms
#     otherwise -- a swing from noise alone bigger than many real regressions. The default threshold
#     (1.5x) sits well above that measured noise band without being so loose it misses a real
#     regression.
#   - A failed sample is counted and reported, never silently dropped from the total -- `failures`
#     names the first few reasons and `failuresTruncated` says how many more were cut, so nothing
#     measured is hidden even when the printed list is bounded.
# ---------------------------------------------------------------------------------------------

BENCH_SCHEMA_VERSION = "npdev-bench-report.v1"
BENCH_REPORT_FILENAME = "npdev-bench-report.json"
BENCH_BASELINE_FILENAME = "bench-baseline.json"
BENCH_BASELINE_SCHEMA_VERSION = "npdev-bench-baseline.v1"
# Matches the scale-proof ladder's own latency phase (20 requests) -- not copied blindly, but there
# is no reason to invent a different default sample count for the same kind of measurement.
DEFAULT_BENCH_SAMPLES = 20
DEFAULT_BENCH_REGRESSION_THRESHOLD = 1.5


def _bench_plan(app_record: dict, final_app_root: Path, *,
                 concepts: list[str] | None, panels: list[str] | None) -> tuple[list[dict], list[str]]:
    """The endpoint plan: every concept-list endpoint the app publishes, plus every panel endpoint,
    unless narrowed by --concept/--panel. Returns (plan, warnings) -- a name that does not exist is a
    WARNING (visible in both the JSON and the human summary), not a silent no-op."""
    import npdev_explore

    warnings: list[str] = []
    plan: list[dict] = []

    info = app_record.get("info") or {}
    all_concepts = [c for c in (info.get("concepts") or [])
                     if isinstance(c, dict) and c.get("name") and c.get("route")]
    wanted_concepts = set(concepts) if concepts else None
    if wanted_concepts:
        missing = wanted_concepts - {c["name"] for c in all_concepts}
        if missing:
            warnings.append(f"--concept named {sorted(missing)}, not in info.json concepts -- skipped")
    for concept in all_concepts:
        if wanted_concepts and concept["name"] not in wanted_concepts:
            continue
        plan.append({"id": f"list:{concept['name']}", "kind": "concept-list",
                     "name": concept["name"], "path": "/api/" + concept["route"]})

    manifest_path = npdev_explore._manifest_path(final_app_root)
    all_panels: list[dict] = []
    if manifest_path is None:
        warnings.append(f"no generated-ui-manifest.json under {final_app_root} -- panel endpoints "
                         "skipped (concept-list checks still run; regenerate the app to add them)")
    else:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        all_panels = [p for p in (manifest.get("panels") or []) if isinstance(p, dict) and p.get("name")]
    wanted_panels = set(panels) if panels else None
    if wanted_panels:
        missing = wanted_panels - {p["name"] for p in all_panels}
        if missing:
            warnings.append(f"--panel named {sorted(missing)}, not in generated-ui-manifest.json "
                             "panels -- skipped")
    for panel in all_panels:
        if wanted_panels and panel["name"] not in wanted_panels:
            continue
        import urllib.parse
        plan.append({"id": f"panel:{panel['name']}", "kind": "panel", "name": panel["name"],
                     "path": "/api/runtime/metadata/ui/panels/" + urllib.parse.quote(panel["name"], safe="")})

    return plan, warnings


def _bench_measure_endpoint(base_url: str, headers: dict[str, str], path: str,
                             samples: int, timeout: float) -> tuple[list[float], list[str]]:
    """Repeat GET `path` `samples` times, wall-clock each one with a monotonic clock. Returns
    (latencies_ms for the requests that answered 2xx, failure reasons for the rest) -- a non-2xx or
    unreachable sample is a counted failure, never folded into the timing average."""
    import time
    import urllib.error
    import urllib.request

    latencies_ms: list[float] = []
    failures: list[str] = []
    for _ in range(samples):
        request = urllib.request.Request(base_url + path, headers=headers)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                response.read()
                status = response.status
        except urllib.error.HTTPError as exc:
            exc.read()
            failures.append(f"HTTP {exc.code}")
            continue
        except urllib.error.URLError as exc:
            failures.append(f"request failed: {exc.reason}")
            continue
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        if 200 <= status < 300:
            latencies_ms.append(elapsed_ms)
        else:
            failures.append(f"HTTP {status}")
    return latencies_ms, failures


def _bench_percentile(sorted_ms: list[float], pct: float) -> float:
    """Nearest-rank percentile over an already-sorted sample -- simple, deterministic, and needs no
    third-party stats library. `pct=50`/`pct=95` are the roadmap's own stated p50/p95."""
    n = len(sorted_ms)
    if n == 1:
        return sorted_ms[0]
    index = max(0, min(n - 1, round(pct / 100 * (n - 1))))
    return sorted_ms[index]


def _bench_stats(latencies_ms: list[float]) -> dict:
    """Never a bare number: sample count, mean and stdev travel with p50/p95 so a reader can judge
    the noise floor rather than trust one figure."""
    import statistics

    n = len(latencies_ms)
    if n == 0:
        return {"samples": 0, "minMs": None, "maxMs": None, "meanMs": None,
                "p50Ms": None, "p95Ms": None, "stdevMs": None}
    sorted_ms = sorted(latencies_ms)
    return {
        "samples": n,
        "minMs": round(sorted_ms[0], 3),
        "maxMs": round(sorted_ms[-1], 3),
        "meanMs": round(statistics.fmean(sorted_ms), 3),
        "p50Ms": round(_bench_percentile(sorted_ms, 50), 3),
        "p95Ms": round(_bench_percentile(sorted_ms, 95), 3),
        "stdevMs": round(statistics.pstdev(sorted_ms), 3) if n > 1 else 0.0,
    }


def _bench_baseline_path(app_dir: Path, override: str | None) -> Path:
    return Path(override).expanduser() if override else app_dir / "_ops" / BENCH_BASELINE_FILENAME


def _load_bench_baseline(path: Path) -> dict:
    """Absent or unreadable is a first-run, not an error -- `run_bench` establishes it. A corrupt
    file is treated the same way rather than raised, since a bench run must not be blockable by a
    hand-edited baseline file; it will be overwritten with a good one at the end of this run."""
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}
    endpoints = data.get("endpoints")
    return endpoints if isinstance(endpoints, dict) else {}


def _compare_to_baseline(stats: dict, baseline_entry: dict | None, threshold: float) -> dict:
    """RELATIVE comparison only (RUN-16's lesson) -- never an absolute ms ceiling. No baseline entry,
    or zero successful samples on either side, means "nothing to compare" rather than a manufactured
    regression."""
    if not baseline_entry or not stats.get("samples") or not baseline_entry.get("p50Ms"):
        return {"hasBaseline": bool(baseline_entry), "regressed": False}
    baseline_p50 = baseline_entry["p50Ms"]
    ratio = stats["p50Ms"] / baseline_p50
    return {
        "hasBaseline": True,
        "baselineP50Ms": baseline_p50,
        "baselineSamples": baseline_entry.get("samples"),
        "baselineMeasuredAt": baseline_entry.get("measuredAt"),
        "ratio": round(ratio, 3),
        "thresholdRatio": threshold,
        "regressed": ratio >= threshold,
    }


def run_bench(args: argparse.Namespace) -> dict:
    """R3.7: probe a RUNNING app's concept-list and panel/query endpoints with repeated samples,
    report p50/p95/mean/stdev per endpoint, and diff against a saved per-app baseline.

    Same refuse-rather-than-report-zeros rule `run_test`/`run_seed` already follow: a non-running
    app is a CliError, not a report full of failed samples that would look like a real measurement.
    """
    import time

    app_dir = Path(args.app_dir).expanduser().resolve()
    app_record = npdev_monitor.probe_app(app_dir, include_info=True)
    if not app_record.get("isAppRoot"):
        raise CliError(app_record.get("detail") or f"not a generated NPDev app: {app_dir}")
    if app_record.get("health") != "running":
        raise CliError(
            f"{app_record.get('name')} is not answering ({app_record.get('health')}): "
            f"{app_record.get('healthDetail')}. `npdev bench` measures a RUNNING app -- start it "
            f"first (_ops/Start-App.ps1, or the Monitor's Start).")

    final_app_root = Path(app_record["finalAppRoot"])
    plan, warnings = _bench_plan(app_record, final_app_root,
                                  concepts=args.concept or None, panels=args.panel or None)
    if not plan:
        raise CliError("nothing to bench: " + ("; ".join(warnings) if warnings else
                       "the app publishes no concepts and declares no panels."))

    base_url = app_record.get("probeBaseUrl")
    headers = {}
    if app_record.get("apiKey"):
        headers[app_record.get("authHeader") or "X-Api-Key"] = app_record["apiKey"]

    baseline_path = _bench_baseline_path(app_dir, args.baseline_path)
    baseline = _load_bench_baseline(baseline_path)
    baseline_existed = bool(baseline)

    started_at = _utc_now()
    begin = time.time()
    endpoints = []
    for entry in plan:
        latencies_ms, failures = _bench_measure_endpoint(
            base_url, headers, entry["path"], args.samples, args.timeout)
        stats = _bench_stats(latencies_ms)
        comparison = _compare_to_baseline(stats, baseline.get(entry["id"]), args.regression_threshold)
        endpoints.append({
            **entry,
            "stats": stats,
            "failedSamples": len(failures),
            # First few reasons only, with the drop COUNTED rather than hidden -- CLAUDE.md's own
            # "if you bound coverage, SAY what was dropped" rule.
            "failures": failures[:5],
            "failuresTruncated": max(0, len(failures) - 5),
            "baseline": comparison,
        })

    regressed = [e for e in endpoints if e["baseline"]["regressed"]]
    all_failed = [e for e in endpoints if e["stats"]["samples"] == 0]
    no_baseline = [e for e in endpoints if not e["baseline"]["hasBaseline"]]

    report = {
        "schemaVersion": BENCH_SCHEMA_VERSION,
        "command": "bench",
        "appDir": str(app_dir),
        "appName": app_record.get("name"),
        "baseUrl": base_url,
        "startedAt": started_at,
        "durationMs": int((time.time() - begin) * 1000),
        "samplesPerEndpoint": args.samples,
        "regressionThreshold": args.regression_threshold,
        "warnings": warnings,
        "endpoints": endpoints,
        "counts": {
            "total": len(endpoints),
            "measured": len(endpoints) - len(all_failed),
            "allFailed": len(all_failed),
            "noBaseline": len(no_baseline),
            "regressed": len(regressed),
        },
        "ok": not regressed and not all_failed,
        "baselinePath": str(baseline_path),
        "baselineExistedBefore": baseline_existed,
    }

    # The FIRST run against an app always establishes the baseline -- there is nothing yet to
    # protect, and refusing to record one would make the very next run useless too. After that, a
    # baseline is only overwritten with --update-baseline: the same explicit-promotion discipline
    # `explore accept` uses for one screenshot, so a bench run that catches a real regression does
    # not quietly erase the evidence by being run a second time.
    should_update = bool(args.update_baseline) or not baseline_existed
    if should_update:
        new_baseline = dict(baseline)
        for endpoint in endpoints:
            if endpoint["stats"]["samples"] == 0:
                continue  # never baseline an endpoint that produced zero successful samples
            new_baseline[endpoint["id"]] = {
                "name": endpoint["name"], "kind": endpoint["kind"], "path": endpoint["path"],
                "samples": endpoint["stats"]["samples"], "p50Ms": endpoint["stats"]["p50Ms"],
                "p95Ms": endpoint["stats"]["p95Ms"], "meanMs": endpoint["stats"]["meanMs"],
                "measuredAt": started_at,
            }
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(json.dumps(
            {"schemaVersion": BENCH_BASELINE_SCHEMA_VERSION, "appName": app_record.get("name"),
             "endpoints": new_baseline}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report["baselineUpdated"] = should_update

    # Same non-redaction guarantee `run_test` documents: every field here is composed explicitly
    # from measured facts, and the only place this command holds the app's API key is the request
    # header, which never reaches the report.
    out = Path(args.report_out).expanduser() if args.report_out else app_dir / "_ops" / BENCH_REPORT_FILENAME
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    report["reportPath"] = str(out)
    return report


def _bench_human_summary(result: dict) -> str:
    if result["counts"]["regressed"]:
        verdict = "REGRESSED"
    elif result["counts"]["allFailed"]:
        verdict = "FAILED"
    else:
        verdict = "OK"
    lines = [f"{verdict}  {result['appName']}  {result['baseUrl']}  "
             f"({result['counts']['measured']}/{result['counts']['total']} endpoint(s) measured, "
             f"{result['samplesPerEndpoint']} sample(s) each, {result['durationMs']} ms total)"]
    for warning in result["warnings"]:
        lines.append(f"  ! {warning}")
    for endpoint in result["endpoints"]:
        stats = endpoint["stats"]
        if not stats["samples"]:
            lines.append(f"  [FAILED]     {endpoint['kind']:<12} {endpoint['name']:<24} "
                         f"0/{result['samplesPerEndpoint']} sample(s) succeeded -- "
                         f"{'; '.join(endpoint['failures']) or 'no successful sample'}")
            continue
        baseline = endpoint["baseline"]
        tag = "REGRESSED" if baseline["regressed"] else ("no-baseline" if not baseline["hasBaseline"] else "ok")
        line = (f"  [{tag:<10}] {endpoint['kind']:<12} {endpoint['name']:<24} "
                f"p50={stats['p50Ms']}ms p95={stats['p95Ms']}ms mean={stats['meanMs']}ms "
                f"stdev={stats['stdevMs']}ms n={stats['samples']}")
        if baseline["hasBaseline"]:
            line += f"  (baseline p50={baseline['baselineP50Ms']}ms, ratio={baseline['ratio']}x)"
        if endpoint["failedSamples"]:
            line += f"  [{endpoint['failedSamples']} failed sample(s)]"
        lines.append(line)
    lines.append(f"  baseline: {result['baselinePath']} "
                 f"({'updated' if result['baselineUpdated'] else 'unchanged -- pass --update-baseline to promote this run'})")
    lines.append(f"  report: {result['reportPath']}")
    return "\n".join(lines)


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


def _render_group_e_content_doc(json_path: Path) -> str:
    """Reconstructs a Group E content/*.json mirror back into its rendered markdown text -- the
    exact inverse scripts/docs/generate_group_e_docs.py's own render() uses to write the .md,
    duplicated here (5 lines) rather than cross-imported, since NPDevCli and scripts/docs are
    siblings with no shared package (same rationale as build_core_context.py's own
    render_authoring_contract()). Reads the JSON mirror, never the .yml -- this runs via `npdev
    generate screen` on a real end-user machine, and PyYAML is a repo-dev/CI-only dependency
    (md-zero-2026-08-11 PLAN.md Phase 7, same fix as build_rag_index.py's own)."""
    doc = json.loads(json_path.read_text(encoding="utf-8"))
    parts = doc["preamble"].split("\n")
    for section in doc["sections"]:
        parts.append("#" * section["level"] + " " + section["title"])
        parts.extend(section["body"].split("\n"))
    return "\n".join(parts)


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

    prompt_content_path = root / "content" / "ui-generation-prompt.json"
    if not prompt_content_path.exists():
        raise CliError(f"reference prompt content not found: {prompt_content_path}")
    prompt_doc_text = _render_group_e_content_doc(prompt_content_path)
    assembled_prompt = (
        f"{prompt_doc_text}\n\n---\n\n"
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


def _pack_diff_report(old_pack: Path, new_pack: Path, out: Path | None) -> dict:
    """PK-4 Stage A, shared by `pack diff` and `impact` (R1.6): routes to the
    :NPDevContract:dsl:packDiff Gradle task (PackDiffMain), which diffs two pack.json documents --
    no database, no filesystem beyond the two files given -- and classifies every difference as
    ADDITIVE/BREAKING/PATCH via com.npdev.dsl.v1.pack.PackDiffEngine. `out`, when given, is also
    where the report is written; otherwise a temp file that is cleaned up once read."""
    root = repo_root()
    wrapper = gradle_wrapper(root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")
    if not old_pack.exists():
        raise CliError(f"old pack not found: {old_pack}")
    if not new_pack.exists():
        raise CliError(f"new pack not found: {new_pack}")

    with tempfile.TemporaryDirectory(prefix="npdev-pack-diff-") as temp_dir:
        report_target = out or (Path(temp_dir) / "pack-diff-report.json")
        report_target.parent.mkdir(parents=True, exist_ok=True)
        gradle_args = [
            str(wrapper),
            *gradle_project_cache_args("root"),
            ":NPDevContract:dsl:packDiff",
            f"-PoldPack={old_pack}",
            f"-PnewPack={new_pack}",
            f"-PreportOut={report_target}",
            "-q",
            "--console=plain",
        ]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            gradle_args = ["cmd.exe", "/c"] + gradle_args
        completed = subprocess.run(gradle_args, cwd=root, check=False, capture_output=True, text=True)
        if not report_target.exists():
            detail = (completed.stderr or completed.stdout or "").strip()
            raise CliError(
                "pack diff did not produce a report"
                + (f" (gradle exit {completed.returncode})" if completed.returncode else "")
                + (f": {detail[-500:]}" if detail else "")
            )
        return read_json(report_target)


def run_pack_diff(args: argparse.Namespace) -> int:
    """PK-4 Stage A: see `_pack_diff_report`. Purely informational, same as the Java CLI it wraps:
    exit code is always 0 once both files were readable and diffable, whatever the classification
    turns out to be -- `pack publish` below is what refuses.
    """
    old_pack = Path(args.old_pack).expanduser().resolve()
    new_pack = Path(args.new_pack).expanduser().resolve()
    written_report = Path(args.out).expanduser().resolve() if getattr(args, "out", None) else None
    report = _pack_diff_report(old_pack, new_pack, written_report)

    print(json.dumps(report, indent=2))
    return 0


def run_pack_publish(args: argparse.Namespace) -> int:
    """PK-4 Stage B: routes to :NPDevContract:dsl:packPublish (PackPublishMain), which wraps Stage
    A's diff with the version-bump-size refusal rule (com.npdev.dsl.v1.pack.PackPublishGate): a
    BREAKING finding requires at least a major bump, ADDITIVE at least a minor bump, PATCH-only at
    least a patch bump. With --write and an allowed, non-BREAKING decision, the Java side also
    rewrites <newPack.json> in place with an empty `migrations` chain entry
    (PackPublishGate.Decision#shouldWriteEmptyMigrationEntry) -- the same opt-in shape as
    `migrate dsl-2 --write` / `migrate rename --write` elsewhere in this file: without --write this
    only reports what would happen.

    Returns 0 when the publish is allowed, 2 when refused -- read from the report's own `allowed`
    field. The Gradle task itself always exits 0 (ignoreExitValue, same convention as validateModel
    and packDiff above), precisely so a refusal surfaces as a report instead of a Gradle build
    failure with no structured detail.
    """
    root = repo_root()
    wrapper = gradle_wrapper(root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")

    old_pack = Path(args.old_pack).expanduser().resolve()
    new_pack = Path(args.new_pack).expanduser().resolve()
    if not old_pack.exists():
        raise CliError(f"old pack not found: {old_pack}")
    if not new_pack.exists():
        raise CliError(f"new pack not found: {new_pack}")

    written_report = Path(args.out).expanduser().resolve() if getattr(args, "out", None) else None
    with tempfile.TemporaryDirectory(prefix="npdev-pack-publish-") as temp_dir:
        report_target = written_report or (Path(temp_dir) / "pack-publish-report.json")
        report_target.parent.mkdir(parents=True, exist_ok=True)
        gradle_args = [
            str(wrapper),
            *gradle_project_cache_args("root"),
            ":NPDevContract:dsl:packPublish",
            f"-PoldPack={old_pack}",
            f"-PnewPack={new_pack}",
            f"-PreportOut={report_target}",
            "-q",
            "--console=plain",
        ]
        if getattr(args, "write", False):
            gradle_args.append("-Pwrite")
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            gradle_args = ["cmd.exe", "/c"] + gradle_args
        completed = subprocess.run(gradle_args, cwd=root, check=False, capture_output=True, text=True)
        if not report_target.exists():
            detail = (completed.stderr or completed.stdout or "").strip()
            raise CliError(
                "pack publish did not produce a report"
                + (f" (gradle exit {completed.returncode})" if completed.returncode else "")
                + (f": {detail[-500:]}" if detail else "")
            )
        report = read_json(report_target)

    print(json.dumps(report, indent=2))
    print(report.get("message", ""))
    return 0 if report.get("allowed") else 2


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
    """Shared by `author diff-gate` and `author submit` (and, since R1.6, `impact`): invokes
    `:generator:authorDiffGate` (AuthoringDiffGate, AI_AUTHORING_CONTRACT-2026-07-31.md Part 9,
    piece E2 -- "the load-bearing piece") and returns the parsed report. Raises CliError with the
    report's own diagnostics on failure -- never a bare non-zero exit with no explanation (C7:
    diff-gate failures must be structured, actionable diagnostics, not prose).

    R1.6 fix: the Gradle subprocess is captured (matching `_classify_model_change_report`/
    `_pack_diff_report`'s own convention), not left to inherit this process's stdout. Measured live
    while building `impact`'s MCP tool: with the old bare `subprocess.run(..., check=True)`, the
    caller's captured stdout was ~20s of raw Gradle build log (daemon-fork notice, task-execution
    lines, `BUILD SUCCESSFUL in Ns`) followed by the JSON report -- harmless for a human at a
    terminal, but it broke `npdev_impact`'s own promise of ONE parseable report for any caller that
    reads stdout without a `--output` file (the MCP tool is exactly such a caller, and
    `npdev_author_diff_gate`'s MCP tool had the identical, pre-existing defect). `ignoreExitValue =
    true` on the `authorDiffGate` Gradle task (NPDevGenerator/generator/build.gradle) means a
    'refused' gate result (exit 2 inside the JVM) never made Gradle itself exit non-zero, so
    `check=True` was never actually the thing keeping refusals working -- switching to `check=False`
    with an explicit decision_report existence check (identical to `_pack_diff_report`'s own
    failure-diagnostic shape) preserves that behavior exactly while adding real detail on a genuine
    Gradle-level failure instead of pointing at now-absent inherited console output.
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
    completed = subprocess.run(command, cwd=generator_root, check=False, capture_output=True, text=True)

    if not decision_report.exists():
        detail = (completed.stderr or completed.stdout or "").strip()
        raise CliError(
            f"diff gate did not produce a report at {decision_report}"
            + (f" (gradle exit {completed.returncode})" if completed.returncode else "")
            + (f": {detail[-1000:]}" if detail else "")
        )
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

    if getattr(args, "release_candidate", False):
        if tier != "T3":
            raise CliError("--release-candidate belongs to T3, the declared release ceremony. "
                           "Re-run with --tier T3.")
        return run_release_candidate(args, root)

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


def run_release_candidate(args: argparse.Namespace, root: Path) -> dict:
    """S3: the seven steps that turn a sha into a handover, stopping at the first failure.

    Stopping matters. A gate that runs everything and reports a list lets a reader take the greens
    and skip the red -- which is how "gates green" got claimed three times while a checker sat
    failing. Here each step is a precondition for the next being MEANINGFUL: there is no point
    pricing artifacts for a sha whose CI never ran.
    """
    import release_candidate as rc

    py = sys.executable or "python"
    steps_run: list[str] = []
    verified: list[dict] = []

    def announce(number: int, what: str) -> None:
        steps_run.append(what)
        print(f"[{number}/7] {what}", file=sys.stderr)

    try:
        # 1 -----------------------------------------------------------------------------------
        announce(1, "tree clean, HEAD pushed, no untracked files")
        tag = args.tag or ""
        if not tag:
            raise rc.StepFailed("tag", "--tag is required: a manifest describes a TAG's artifacts, "
                                       "and a branch name moves")
        tree = rc.check_tree_state(root, tag)
        sha = tree["sha"]

        # 2 -----------------------------------------------------------------------------------
        announce(2, "T2 -- all four gates")
        gates = root / "scripts" / "quality" / "run-all-gates.ps1"
        t2_code = subprocess.run(["pwsh", "-NoProfile", "-File", str(gates)], cwd=root).returncode
        if t2_code != 0:
            raise rc.StepFailed("t2-gates", f"run-all-gates.ps1 exited {t2_code}")
        verified.append({
            "id": "t2-gates", "result": "pass-with-named-skip",
            "evidence": "local:scripts/quality/run-all-gates.ps1",
            "namedSkip": "3 packaged-app proof tests are skipped on a local run and are run by CI "
                         "(-PincludePackagedProofs runs them here).",
            "note": "Local greens are inputs; the CI run ids below are the evidence.",
        })

        # 3 -----------------------------------------------------------------------------------
        announce(3, "generator determinism -- same model twice, byte-for-byte")
        det_report = root / "scripts" / "reports" / "out" / "deterministic-generation-report.json"
        # Read the report T2's generator gate just produced rather than generating twice AGAIN:
        # re-running would double a multi-minute step to re-answer a question already answered in
        # this same invocation, and a second answer that disagreed would be the real story anyway.
        if not det_report.is_file():
            raise rc.StepFailed("determinism", f"no determinism report at {det_report}")
        det = json.loads(det_report.read_text(encoding="utf-8"))
        if det.get("overallStatus") != "passed":
            raise rc.StepFailed("determinism",
                                f"{det.get('differingFileCount')} file(s) differ between two runs: "
                                + ", ".join(d.get("path", "") for d in det.get("differingFiles", [])[:5]))
        verified.append({
            "id": "determinism", "result": "pass",
            "evidence": "local:scripts/hygiene/check-deterministic-generation.ps1",
            "note": (f"{det.get('firstFileCount')} files compared across two generations of "
                     f"{det.get('sampleId')}, 0 differing. Excludes npdev-build-info.properties and "
                     f"generation-run.json -- declared, non-reproducible provenance."),
        })

        # 4 -----------------------------------------------------------------------------------
        announce(4, "CI at this exact sha (main CI, engine support, conformance)")
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        verified.extend(rc.check_ci_at_sha(_CI_VALIDATION_REPO, sha, token))

        # 5 -----------------------------------------------------------------------------------
        announce(5, "first-run harness")
        # Built from a NORMALIZED copy of the committed blobs, not from the working tree.
        #
        # The harness `COPY`s its scripts out of the build context, so on a Windows checkout with
        # core.autocrlf=true `run-readme.sh` is baked in with CRLF and its own shebang becomes
        # `#!/usr/bin/env bash\r`. The container then dies with
        #   /usr/bin/env: 'bash\r': No such file or directory     (exit 127)
        # before it clones anything. Measured here, and already documented in the harness README as a
        # Windows working-tree artifact -- the committed blob is LF-only.
        #
        # Left alone, this gate would refuse on every Windows machine for a reason that has nothing
        # to do with the release, which is the "red you are trained to explain away" shape S1 exists
        # to remove. So the documented workaround is what the gate does: replay each file from
        # `git show HEAD:<path>`, strip CR, build against that.
        harness_rel = "scripts/quality/firstrun-harness"
        harness = root / "scripts" / "quality" / "firstrun-harness"
        with tempfile.TemporaryDirectory(prefix="npdev-firstrun-ctx-") as ctx:
            context = Path(ctx)
            for source in sorted(harness.iterdir()):
                if not source.is_file():
                    continue
                blob = subprocess.run(["git", "show", f"HEAD:{harness_rel}/{source.name}"],
                                      cwd=root, capture_output=True, check=False)
                if blob.returncode != 0:
                    raise rc.StepFailed("firstrun-harness",
                                        f"{source.name} is not committed at HEAD -- the harness must be "
                                        f"built from the released state, not from an uncommitted file")
                (context / source.name).write_bytes(blob.stdout.replace(b"\r\n", b"\n"))
            build = subprocess.run(["docker", "build", "-q", "-t", "npdev-firstrun", str(context)],
                                   cwd=root, capture_output=True, text=True, check=False)
        if build.returncode != 0:
            raise rc.StepFailed("firstrun-harness", f"could not build the harness image: {build.stderr.strip()[:300]}")
        run = subprocess.run(["docker", "run", "--rm", "-e", f"REPO_REF={tag}", "npdev-firstrun"],
                             cwd=root, capture_output=True, text=True, check=False)
        if run.returncode != 0:
            raise rc.StepFailed("firstrun-harness",
                                f"exited {run.returncode} -- it follows README.md literally on a machine "
                                f"with nothing installed, so a red here is a documentation defect a "
                                f"second machine WILL hit:\n{run.stdout.strip()[-1500:]}")
        verified.append({
            "id": "firstrun-harness", "result": "pass",
            "evidence": f"local:docker run npdev-firstrun (REPO_REF={tag})",
            "note": "Follows README.md literally in a container with no Java, Python or PowerShell.",
        })

        # 6 -----------------------------------------------------------------------------------
        announce(6, "both installers, published for this tag")
        launched = {}
        for entry in (args.launched or []):
            launched[entry] = True
        artifacts = rc.collect_artifacts(_CI_VALIDATION_REPO, tag, token, launched)
        names = [a["name"] for a in artifacts]
        missing = []
        if not any(n.endswith(".exe") for n in names):
            missing.append("a Windows installer (.exe)")
        if not any(n.endswith(".AppImage") for n in names):
            missing.append("a Linux AppImage")
        if missing:
            raise rc.StepFailed("artifacts", f"the release for {tag} is missing {' and '.join(missing)}. "
                                             f"Present: {', '.join(names) or '(none)'}")
        # `builtBy` is the CI run that produced the release. Recorded from main-ci's run at this sha
        # rather than left blank: the schema asks who built it precisely so a hand-uploaded artifact
        # cannot pass as a reproducible one.
        built_by = next((v["evidence"] for v in verified if v["id"] == "main-ci"), "")
        for artifact in artifacts:
            artifact["builtBy"] = built_by

        # 7 -----------------------------------------------------------------------------------
        announce(7, "emit STABILITY_MANIFEST.json")
        manifest = rc.build_manifest(
            sha=sha, tag=tag, tree=tree, verified=verified,
            not_verified=rc.not_verified_entries(artifacts),
            artifacts=artifacts, known_limitations=rc.known_limitations(),
            open_items=rc.open_items_at_head(root),
        )
    except rc.StepFailed as failure:
        # Named, with the step that stopped it. A release gate that fails vaguely gets overridden.
        print(f"\nRELEASE CANDIDATE REFUSED at step '{failure.step}':\n  {failure.detail}", file=sys.stderr)
        return {"ok": False, "tier": "T3", "releaseCandidate": True,
                "failedStep": failure.step, "detail": failure.detail, "stepsRun": steps_run}

    out_path = Path(args.manifest_out) if getattr(args, "manifest_out", None) else (root / "STABILITY_MANIFEST.json")
    out_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"\nWrote {out_path}", file=sys.stderr)
    return {"ok": True, "tier": "T3", "releaseCandidate": True, "manifestPath": str(out_path),
            "sha": manifest["sha"], "tag": manifest["tag"], "stepsRun": steps_run}


def run_validate_semantic(model_path: Path, report_out: Path | None) -> int:
    """Run full structural + semantic validation via the standalone Java validator.

    Runs ModelValidatorMain, which runs the exact validation the generator runs -- without
    generating -- and writes a typed npdev-validation-report.v2 report. Returns 0 when the model
    passes (or has warnings only), 2 when it has errors. The report is the loopable contract an
    AI-authoring agent self-corrects against; here we also echo it to stdout.

    R1.1: reached directly as `java -cp npdev-ai-tools.jar ModelValidatorMain ...` when that jar is
    staged, else through the :NPDevContract:dsl:validateModel Gradle task exactly as before. This is
    the single hottest call in the authoring loop -- it runs after every model edit -- and the Gradle
    layer contributed nothing but a classpath: the class is pure stdlib+Jackson+dsl. Measured
    4.61s -> 2.24s median on canonical-demo (see _default_ai_tools_jar for the full numbers), with a
    byte-identical report: the model path is the only environment-dependent value in it, and both
    paths hand the validator the same resolved absolute path.
    """
    root = repo_root()
    model = Path(model_path).expanduser().resolve()
    if not model.exists():
        raise CliError(f"model not found: {model}")

    written_report = Path(report_out).expanduser().resolve() if report_out else None
    with tempfile.TemporaryDirectory(prefix="npdev-validate-") as temp_dir:
        report_target = written_report or (Path(temp_dir) / "validation-report.json")
        report_target.parent.mkdir(parents=True, exist_ok=True)
        command = _ai_tools_command(
            AI_TOOLS_VALIDATOR_MAIN, [str(model), "--out", str(report_target)])
        if command is None:
            wrapper = gradle_wrapper(root)
            if not wrapper.exists():
                raise CliError(f"Gradle wrapper not found: {wrapper}")
            command = [
                str(wrapper),
                *gradle_project_cache_args("root"),
                ":NPDevContract:dsl:validateModel",
                f"-PmodelPath={model}",
                f"-PreportOut={report_target}",
                "-q",
                "--console=plain",
            ]
            if os.name == "nt" and wrapper.suffix.lower() == ".bat":
                command = ["cmd.exe", "/c"] + command
        # Capture the subprocess output: the Java validator echoes the report to stdout, but the
        # report FILE is the channel we read, and the CLI is the single stdout authority (printing
        # captured output too would emit two JSON docs). On failure, surface it in the error.
        completed = subprocess.run(command, cwd=root, check=False, capture_output=True, text=True)
        if not report_target.exists():
            detail = (completed.stderr or completed.stdout or "").strip()
            raise CliError(
                "validator did not produce a report"
                + (f" (validator exit {completed.returncode})" if completed.returncode else "")
                + (f": {detail[-500:]}" if detail else "")
            )
        report = read_json(report_target)

    _capture_validation(model, report)
    print(json.dumps(report, indent=2))
    _print_boundary_limits(report)
    return 2 if report.get("status") == "failed" else 0


def _run_pack_gradle_task(task: str, model_path: Path, extra_props: dict[str, str] | None = None) -> int:
    """PK-3: shared plumbing for npdev pack add|update|list|why -- same
    JavaExec-task-wrapping-a-small-Main-class shape run_validate_semantic already uses for
    `validate model`. Each Main class prints its own JSON report to stdout; this just forwards the
    Gradle task's own stdout (the report) and returns its exit code, since none of these four
    commands need the temp-file capture-and-reread dance validate does (no --releaseGate-style
    optional extra processing here).
    """
    root = repo_root()
    wrapper = gradle_wrapper(root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")
    model = Path(model_path).expanduser().resolve()
    if not model.exists():
        raise CliError(f"model not found: {model}")

    gradle_args = [
        str(wrapper),
        *gradle_project_cache_args("root"),
        f":NPDevContract:dsl:{task}",
        f"-PmodelPath={model}",
        "-q",
        "--console=plain",
    ]
    for key, value in (extra_props or {}).items():
        gradle_args.append(f"-P{key}={value}")
    if os.name == "nt" and wrapper.suffix.lower() == ".bat":
        gradle_args = ["cmd.exe", "/c"] + gradle_args
    completed = subprocess.run(gradle_args, cwd=root, check=False, capture_output=True, text=True)
    stdout = (completed.stdout or "").strip()
    if stdout:
        print(stdout)
    if completed.returncode not in (0, 2):
        detail = (completed.stderr or "").strip()
        raise CliError(f"pack {task} failed (gradle exit {completed.returncode})"
                        + (f": {detail[-500:]}" if detail else ""))
    try:
        report = json.loads(stdout) if stdout else {}
    except json.JSONDecodeError:
        report = {}
    return 2 if report.get("status") == "failed" else 0


def run_pack_add(args: argparse.Namespace) -> int:
    from_catalog = getattr(args, "from_catalog", None)
    if from_catalog:
        _add_pack_from_catalog(Path(args.model), from_catalog, args)
    return _run_pack_gradle_task("packAdd", Path(args.model))


def run_pack_update(args: argparse.Namespace) -> int:
    return _run_pack_gradle_task("packUpdate", Path(args.model))


def run_pack_list(args: argparse.Namespace) -> int:
    return _run_pack_gradle_task("packList", Path(args.model))


def run_pack_why(args: argparse.Namespace) -> int:
    return _run_pack_gradle_task("packWhy", Path(args.model), {"packId": args.pack_id})


# ---------------------------------------------------------------------------------------------
# R8.4: `npdev pack search` + the NPR catalog index.
#
# PREMISE CHECK (done before writing any of this): the fetch/cache/lock substrate this depends on
# is real and already live -- PackDependencyGraphWalker.resolveRemotePackFile, RemotePackFetcher
# and PackCache (PK-5) resolve a KNOWN `from` coordinate end to end, digest-verified, with
# `npdev generate`/`validate` never touching the network at all (only `pack add`/`update` do,
# gated by NetworkPolicy). What was actually missing is discovery: nothing let an author find a
# coordinate by NAME. `PackDependencyGraphWalker.defaultPackFile` was confirmed to resolve a
# TRANSITIVE dependency only as `<rootDir>/packs/<id>/pack.json` -- "still local files only, no
# registry yet" -- which is why `_add_pack_from_catalog` below only ever writes a DIRECT `from`
# entry into the app's own model; a transitive dependency published only in a pack's own `packs[]`
# still needs a locally-vendored copy (or its own resolvable `from`) exactly as PACK-13 measured.
#
# NETWORK ACCESS AS A DESIGN DECISION (not an implementation detail, per this task's own brief):
# `catalog-index.json` is fetched over plain read-only HTTPS (`urllib.request`, no credentials, no
# write access to anything) and CACHED locally (`_pack_catalog_cache_path()`). A stale cache is
# served with a loud, unmissable warning naming exactly why (`meta["fetchError"]`) -- never silent.
# An empty `packs: []` result must always mean "the catalog is genuinely empty", never "the network
# was unreachable and nobody said so" -- so `_load_pack_catalog` RAISES when there is neither a
# fresh fetch nor a usable cache, rather than returning an empty list that would look identical to
# a real empty catalog. This was verified against the REAL github.com/MarceloGiazzon/NPR repo while
# building this feature: the repo and its one published pack (`packs/user/pack.json`, tagged
# `v1.0.0` at the repo root) are real and fetchable, but no `catalog-index.json` exists there yet
# (a live 404) -- `run_pack_build_catalog` below is the tool that produces one; publishing it to
# NPR is left to the repo owner (or R8.5's future `pack publish --push`), not done by this command.
# ---------------------------------------------------------------------------------------------

PACK_CATALOG_SCHEMA_VERSION = "npdev-pack-catalog.v1"
# The real NPR pack repo (github.com/MarceloGiazzon/NPR) -- confirmed live and reachable while this
# was built (README.md + packs/user/pack.json fetched for real over HTTPS). It has no
# catalog-index.json yet, so a fresh `pack search` against the default URL today reports a clean
# 404-derived refusal (or a stale-cache warning on a second run) rather than a silent empty list --
# exactly the "never fail open" contract this module documents above.
DEFAULT_PACK_CATALOG_URL = "https://raw.githubusercontent.com/MarceloGiazzon/NPR/main/catalog-index.json"
ENV_PACK_CATALOG_URL = "NPDEV_PACK_CATALOG_URL"
ENV_PACK_CATALOG_CACHE = "NPDEV_PACK_CATALOG_CACHE"


def _pack_catalog_cache_path() -> Path:
    override = os.environ.get(ENV_PACK_CATALOG_CACHE)
    if override:
        return Path(override).expanduser()
    return Path.home() / ".npdev" / "catalog-cache.json"


def _pack_catalog_url(explicit: str | None) -> str:
    return explicit or os.environ.get(ENV_PACK_CATALOG_URL) or DEFAULT_PACK_CATALOG_URL


def _fetch_pack_catalog_http(url: str, *, timeout: float = 10.0) -> tuple[dict | None, str | None]:
    """One real HTTPS GET, no credentials, no retries beyond urllib's own. Returns
    (parsed_catalog, None) on success or (None, human-readable reason) on any failure -- never
    raises, so the caller can decide whether a failure means "fall back to cache" or "refuse"."""
    import urllib.error
    import urllib.request

    request = urllib.request.Request(url, headers={"User-Agent": "npdev-cli", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return None, f"HTTP {exc.code} from {url}"
    except urllib.error.URLError as exc:
        return None, f"could not reach {url}: {exc.reason}"
    except (TimeoutError, OSError, ValueError) as exc:
        return None, f"could not reach {url}: {exc}"
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        return None, f"{url} did not return valid JSON: {exc}"
    if not isinstance(parsed, dict) or not isinstance(parsed.get("packs"), list):
        return None, f"{url} is not a valid npdev pack catalog (expected an object with a 'packs' array)"
    return parsed, None


def _load_pack_catalog(url: str, *, refresh: bool) -> tuple[dict, dict]:
    """Returns (catalog, meta). `meta` is `{"status": "fresh"|"stale", "source", "fetchedAt",
    "fetchError", "cachePath"}` -- always present, always naming exactly what happened, so a
    caller can print the same staleness warning `pack search` and `pack add --from-catalog` both
    show. Raises CliError -- never returns an empty catalog -- when there is neither a fresh fetch
    nor a usable cache: see this section's own module-level doc for why."""
    cache_path = _pack_catalog_cache_path()
    cached_catalog: dict | None = None
    cached_source = None
    cached_fetched_at = None
    if cache_path.is_file():
        try:
            wrapper = json.loads(cache_path.read_text(encoding="utf-8"))
            if isinstance(wrapper, dict) and isinstance(wrapper.get("catalog"), dict):
                cached_catalog = wrapper["catalog"]
                cached_source = wrapper.get("source")
                cached_fetched_at = wrapper.get("fetchedAt")
        except (OSError, json.JSONDecodeError):
            cached_catalog = None  # a corrupt cache is treated as no cache, never a crash

    fetch_error = "network not attempted (--offline)"
    if refresh:
        fetched, fetch_error = _fetch_pack_catalog_http(url)
        if fetched is not None:
            fetched_at = _utc_now()
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(json.dumps(
                {"source": url, "fetchedAt": fetched_at, "catalog": fetched},
                indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            return fetched, {"status": "fresh", "source": url, "fetchedAt": fetched_at,
                              "fetchError": None, "cachePath": str(cache_path)}

    if cached_catalog is not None:
        return cached_catalog, {
            "status": "stale", "source": cached_source or url, "fetchedAt": cached_fetched_at,
            "fetchError": fetch_error, "cachePath": str(cache_path),
        }

    raise CliError(
        f"no cached pack catalog, and could not fetch a fresh one from {url}: {fetch_error}. "
        f"(The cache would live at {cache_path} after a successful fetch.) Refusing rather than "
        f"reporting zero results, which would look like 'no packs match' instead of 'could not check'."
    )


def run_pack_search(args: argparse.Namespace) -> int:
    """R8.4: the pack ecosystem's missing front door. Filters the cached/fetched catalog by a
    case-insensitive substring against pack id/description/category -- no query lists everything.
    """
    url = _pack_catalog_url(args.catalog_url)
    catalog, meta = _load_pack_catalog(url, refresh=not args.offline)
    query = (args.query or "").strip().lower()
    entries = [e for e in catalog.get("packs", []) if isinstance(e, dict)]
    matches = [
        e for e in entries
        if not query
        or query in str(e.get("pack", "")).lower()
        or query in str(e.get("description", "")).lower()
        or query in str(e.get("category", "")).lower()
    ]
    if meta["status"] == "stale":
        # To stderr (not stdout) so --json's stdout stays one clean parseable document, but ALWAYS
        # printed, never conditional on --json -- staleness must never be silent either way.
        print(f"WARNING: serving a CACHED catalog (fetched {meta.get('fetchedAt') or 'unknown time'}, "
              f"{meta.get('cachePath')}) -- could not reach {meta.get('source')}: {meta.get('fetchError')}",
              file=sys.stderr)
    result = {
        "schemaVersion": "npdev-pack-search.v1",
        "command": "pack search",
        "ok": True,
        "query": args.query or "",
        "catalogSource": meta.get("source"),
        "catalogStatus": meta["status"],
        "catalogFetchedAt": meta.get("fetchedAt"),
        "catalogStaleReason": meta.get("fetchError") if meta["status"] == "stale" else None,
        "count": len(matches),
        "results": matches,
    }
    _print_result(result, args)
    return 0


def run_pack_build_catalog(args: argparse.Namespace) -> int:
    """R8.4 (shared index format with R8.5): scans a local checkout of an NPR-shaped pack repo
    (`packs/<name>/pack.json` per pack -- the layout github.com/MarceloGiazzon/NPR already uses)
    and writes `catalog-index.json`, the artifact `pack search` fetches. Writes a LOCAL FILE ONLY
    -- publishing it (committing + pushing to the catalog repo) is a separate, manual step today,
    or R8.5's future `pack publish --push`; this command never touches git or the network.

    `--tag-template` exists because NPR's own convention today is a single repo-wide release tag
    (`v1.0.0`), confirmed live -- there is exactly one published pack as of this writing, so a real
    per-pack tagging scheme has never actually been exercised with two packs at different versions.
    Stated plainly rather than invented: whoever publishes a second pack to NPR must decide that
    scheme for real, and this template is the seam the decision plugs into.
    """
    repo_dir = Path(args.repo_dir).expanduser().resolve()
    packs_dir = repo_dir / "packs"
    if not packs_dir.is_dir():
        raise CliError(f"no packs/ directory under {repo_dir} -- expected {packs_dir}")

    repo_url = args.repository_url.rstrip("/")
    if repo_url.endswith(".git"):
        repo_url = repo_url[: -len(".git")]

    entries = []
    problems = []
    for pack_json in sorted(packs_dir.glob("*/pack.json")):
        try:
            data = read_json(pack_json)
        except CliError as exc:
            problems.append(f"{pack_json}: {exc} -- skipped")
            continue
        pack_id = data.get("pack")
        version = data.get("version")
        if not pack_id or not version:
            problems.append(f"{pack_json}: missing 'pack' or 'version' -- skipped")
            continue
        tag = args.tag_template.format(pack=pack_id, version=version)
        subpath = pack_json.parent.relative_to(repo_dir).as_posix()
        coordinate = f"git+{repo_url}.git//{subpath}@{tag}"
        entries.append({
            "pack": pack_id,
            "version": version,
            "description": data.get("description", ""),
            "category": data.get("category", "other"),
            "author": data.get("author", ""),
            "path": subpath,
            "from": coordinate,
        })

    catalog = {
        "schemaVersion": PACK_CATALOG_SCHEMA_VERSION,
        "repository": args.repository_url,
        "generatedAt": _utc_now(),
        "packs": entries,
    }
    out_path = Path(args.out).expanduser() if args.out else repo_dir / "catalog-index.json"
    out_path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    report = {
        "schemaVersion": "npdev-pack-catalog-build.v1",
        "command": "pack build-catalog",
        "ok": not problems,
        "repoDir": str(repo_dir),
        "outPath": str(out_path),
        "packCount": len(entries),
        "problems": problems,
    }
    print(json.dumps(report, indent=2))
    return 0 if not problems else 2


def _add_pack_from_catalog(model_path: Path, pack_name: str, args: argparse.Namespace) -> None:
    """R8.4: `npdev pack add --from-catalog <name>` -- resolve `name` against the catalog and
    write its `from` coordinate into the model's OWN `packs[]`, exactly the shape an author would
    hand-write from a coordinate they looked up themselves. Everything after this point (the
    network fetch, the digest, npdev.lock) is the ALREADY-PROVEN PK-5 machinery
    (PackDependencyGraphWalker/RemotePackFetcher/PackCache) invoked by the `packAdd` Gradle task
    `run_pack_add` runs immediately after calling this -- this function only ever edits JSON, never
    touches the network or the pack cache itself, so there remains exactly ONE resolver in this
    codebase, not a second one built alongside it.
    """
    model_path = Path(model_path).expanduser().resolve()
    if not model_path.is_file():
        raise CliError(f"model not found: {model_path}")
    url = _pack_catalog_url(getattr(args, "catalog_url", None))
    catalog, meta = _load_pack_catalog(url, refresh=not getattr(args, "offline", False))
    if meta["status"] == "stale":
        print(f"WARNING: resolving '{pack_name}' from a CACHED catalog (fetched "
              f"{meta.get('fetchedAt') or 'unknown time'}) -- could not reach {meta.get('source')}: "
              f"{meta.get('fetchError')}", file=sys.stderr)

    entries = [e for e in catalog.get("packs", []) if isinstance(e, dict)]
    match = next((e for e in entries if e.get("pack") == pack_name), None)
    if match is None:
        available = ", ".join(sorted(e.get("pack", "?") for e in entries)) or "(catalog is empty)"
        raise CliError(f"no pack named {pack_name!r} in the catalog ({meta.get('source')}). "
                        f"Available: {available}")
    coordinate = match.get("from")
    if not coordinate:
        raise CliError(f"catalog entry for {pack_name!r} has no 'from' coordinate -- malformed catalog")

    model = read_json(model_path)
    packs = model.setdefault("packs", [])
    if not isinstance(packs, list):
        raise CliError(f"{model_path}: top-level 'packs' is not an array, refusing to append")
    if any(isinstance(e, dict) and e.get("from") == coordinate for e in packs):
        print(f"'{pack_name}' is already declared in {model_path} at this coordinate -- nothing to add.")
        return
    if any(isinstance(e, dict) and e.get("as") == pack_name for e in packs):
        raise CliError(f"{model_path} already declares a pack aliased {pack_name!r} at a different "
                        f"coordinate -- resolve the conflict by hand before --from-catalog")
    packs.append({"from": coordinate})
    model_path.write_text(json.dumps(model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"added pack {pack_name!r} to {model_path}: {coordinate}")


def _pack_export_reference_targets(concept: dict):
    """Yields (fieldLabel, get, set) for every reference-bearing string this exporter rewrites:
    each `fields[].reference` (either the shorthand string form or the `{target: ...}` object
    form -- model.schema.json's `field.reference` is a `oneOf` of both) and the concept's own
    `satelliteOf` (PK-6: a pack-qualified reference to a satellite's base concept). Deliberately
    scoped to `concepts[]` only, matching both pre-existing single-concept export paths
    (NPDevSamples/scripts/packs/export-concept-to-pack.ps1 and the generated
    GeneratedPackCatalogController's /api/admin/packs/export) -- panels/queries/flows/etc. carry
    their own much larger reference vocabulary (ModelSourceResolver.rewriteKnownMemberReferenceFields)
    that no concept-export path has ever touched.
    """
    concept_name = concept.get("name", "?")
    satellite_of = concept.get("satelliteOf")
    if isinstance(satellite_of, str) and satellite_of:
        def _get_sat():
            return concept.get("satelliteOf")

        def _set_sat(value):
            concept["satelliteOf"] = value

        yield f"{concept_name}.satelliteOf", _get_sat, _set_sat
    for field in concept.get("fields", None) or []:
        if not isinstance(field, dict):
            continue
        field_name = field.get("name", "?")
        reference = field.get("reference")
        if isinstance(reference, str) and reference:
            def _get_str(f=field):
                return f.get("reference")

            def _set_str(value, f=field):
                f["reference"] = value

            yield f"{concept_name}.{field_name}.reference", _get_str, _set_str
        elif isinstance(reference, dict) and isinstance(reference.get("target"), str) and reference.get("target"):
            def _get_obj(f=field):
                return f["reference"]["target"]

            def _set_obj(value, f=field):
                f["reference"]["target"] = value

            yield f"{concept_name}.{field_name}.reference.target", _get_obj, _set_obj


def run_pack_export(args: argparse.Namespace) -> int:
    """R8.2: a real `npdev pack export` verb, replacing the ONLY-previously-real path from a
    working app concept to a reusable pack -- an external one-concept PowerShell script
    (NPDevSamples/scripts/packs/export-concept-to-pack.ps1) that copied a concept's raw JSON
    verbatim into a new pack.json with zero reference handling and zero schema validation.
    (A second, equally single-concept, equally reference-blind path also existed: the generated
    app's own `POST /api/admin/packs/export`, `GeneratedPackCatalogController.exportConceptToPack`
    -- out of scope here since it lives in a mustache template this task does not own, but the
    roadmap's "the ONLY path is a PowerShell script" premise was already false before this change.)

    Multi-member: `--concepts A,B,C` exports several concepts from the same source `concepts[]`
    array (an app model.json's own root-declared concepts, or another pack.json's) into one new
    pack.json, together.

    Reference handling is the substantive part (the roadmap's own framing). Every
    `fields[].reference` / `satelliteOf` string among the exported concepts is classified:
      - target's bare name is one of the OTHER exported concepts -> rewritten to INTRA-PACK form
        (the bare name), regardless of what qualifier it carried before. This is what lets a
        multi-concept subgraph compose cleanly under its new pack id.
      - target already carries a `otherPack::Name` qualifier and `otherPack` is a real sibling
        pack under NPDevContract/packs -- a genuine cross-pack dependency. Left AS-IS (cross-pack
        references are qualifier-stable regardless of which pack does the referencing -- see
        ModelSourceResolver's QUALIFIED_REF_NOOP), and `otherPack` is added to the new pack's
        own `packs[]` (PK-3 transitive dependency) with a `^major.minor` constraint read from that
        sibling pack's own current version, so composition can actually find it.
      - anything else (a bare name that is not among the exported concepts, or a qualifier naming
        an unknown pack) is NON-PORTABLE: it points at something that will not travel with the
        pack, and a pack cannot reach back into the app that imports it (packs compose INTO apps,
        never the reverse). Silently dropping such a reference, or silently leaving it dangling,
        would produce a pack.json that LOOKS exported but fails to compose later with no context
        connecting the failure back to this export. Consistent with this repo's house style
        (MON-18: "unsupported dependencies reported BY NAME rather than silently skipped"), this
        refuses the export by default, naming every offending concept.field -> target, unless the
        caller passes --allow-unresolved-refs -- which still does not drop anything: the reference
        is written through unchanged and the pack.json's own `metadata.unresolvedReferences`
        records exactly what was left dangling, as a durable, honest audit trail, not silence.
    """
    model_path = Path(args.model).expanduser().resolve()
    source = read_json(model_path)
    if not isinstance(source.get("concepts"), list):
        raise CliError(f"{model_path} has no top-level 'concepts' array to export from")

    concept_names = [name.strip() for name in str(args.concepts).split(",") if name.strip()]
    if not concept_names:
        raise CliError("--concepts must name at least one concept (comma-separated)")
    duplicate_requests = {name for name in concept_names if concept_names.count(name) > 1}
    if duplicate_requests:
        raise CliError(f"--concepts named the same concept more than once: {sorted(duplicate_requests)}")

    by_name = {}
    for candidate in source["concepts"]:
        if isinstance(candidate, dict) and isinstance(candidate.get("name"), str):
            by_name[candidate["name"]] = candidate
    missing = [name for name in concept_names if name not in by_name]
    if missing:
        available = ", ".join(sorted(by_name)) or "(none)"
        raise CliError(
            f"Concept(s) not found in {model_path}: {', '.join(missing)}. Available concepts: {available}"
        )

    require_identifier(args.pack, "pack name", r"^[a-z][a-z0-9_-]*$")

    root = repo_root()
    pack_schema_path = root / "NPDevContract" / "schemas" / "pack.schema.json"
    pack_schema = read_json(pack_schema_path)
    valid_categories = pack_schema.get("properties", {}).get("category", {}).get("enum") or []
    category = args.category or "other"
    if valid_categories and category not in valid_categories:
        raise CliError(f"category must be one of: {', '.join(valid_categories)}, got: {category}")

    forked_pack = (args.forked_from_pack or "").strip()
    forked_version = (args.forked_from_version or "").strip()
    if bool(forked_pack) != bool(forked_version):
        raise CliError("--forked-from-pack and --forked-from-version must both be set together "
                        "(forkedFrom.version is required by pack.schema.json)")

    packs_root = root / "NPDevContract" / "packs"
    out_root = Path(args.out_dir).expanduser().resolve() if getattr(args, "out_dir", None) else packs_root
    pack_dir = out_root / args.pack
    pack_json_path = pack_dir / "pack.json"
    if pack_json_path.exists():
        raise CliError(f"Pack already exists, refusing to overwrite: {pack_json_path} "
                        f"(choose a different --pack or remove it first)")

    export_set = set(concept_names)
    exported_concepts = [copy.deepcopy(by_name[name]) for name in concept_names]

    rewrites: list[dict] = []
    cross_pack_versions: dict[str, str] = {}
    unresolved: list[dict] = []
    for concept in exported_concepts:
        for field_label, get_target, set_target in _pack_export_reference_targets(concept):
            target = get_target()
            local_name = target.split("::", 1)[-1] if "::" in target else target
            if local_name in export_set:
                if target != local_name:
                    set_target(local_name)
                    rewrites.append({"field": field_label, "from": target, "to": local_name})
                continue
            if "::" in target:
                prefix = target.split("::", 1)[0]
                sibling_pack_json = packs_root / prefix / "pack.json"
                if sibling_pack_json.exists():
                    sibling = read_json(sibling_pack_json)
                    version = str(sibling.get("version", "")).strip()
                    parts = version.split(".")
                    if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
                        cross_pack_versions[prefix] = f"^{parts[0]}.{parts[1]}"
                    else:
                        cross_pack_versions.setdefault(prefix, "^0.0")
                    continue
            unresolved.append({"field": field_label, "target": target})

    if unresolved and not getattr(args, "allow_unresolved_refs", False):
        lines = "; ".join(f"{item['field']} -> {item['target']!r}" for item in unresolved)
        raise CliError(
            "Refusing to export: the following reference(s) point outside the exported concept set "
            f"and outside any known sibling pack under {packs_root}: {lines}. "
            "Either add the target concept to --concepts, restructure the source model to split the "
            "dependency via `satelliteOf` before exporting (see the WmsOffice pack-extraction recipe: "
            "packs cannot reach back into the app that imports them), or pass --allow-unresolved-refs "
            "to export anyway with the reference left as-is and recorded in the pack's own "
            "metadata.unresolvedReferences."
        )

    pack_doc: dict = {
        "$schema": "../../schemas/pack.schema.json",
        "dslVersion": "1.0.0",
        "pack": args.pack,
        "version": args.pack_version or "1.0.0",
        "description": args.description or f"Exported from concept(s) {', '.join(concept_names)} in {model_path}.",
        "category": category,
        "author": args.author,
    }
    if args.namespace:
        pack_doc["namespace"] = args.namespace
    if forked_pack:
        pack_doc["forkedFrom"] = {
            "pack": forked_pack,
            "version": forked_version,
            "originAuthor": (args.forked_from_author or "").strip(),
        }
    if cross_pack_versions:
        pack_doc["packs"] = [
            {"pack": pack_id, "version": version} for pack_id, version in sorted(cross_pack_versions.items())
        ]
    if unresolved:
        pack_doc["metadata"] = {"unresolvedReferences": unresolved}
    pack_doc["concepts"] = exported_concepts

    pack_dir.mkdir(parents=True, exist_ok=True)
    pack_json_path.write_text(json.dumps(pack_doc, indent=2) + "\n", encoding="utf-8")

    if unresolved:
        for item in unresolved:
            print(f"WARNING  unresolved reference left as-is: {item['field']} -> {item['target']!r}",
                  file=sys.stderr)

    report = {
        "exported": True,
        "pack": args.pack,
        "packJsonPath": str(pack_json_path),
        "concepts": concept_names,
        "rewrittenReferences": rewrites,
        "crossPackDependencies": pack_doc.get("packs", []),
        "unresolvedReferences": unresolved,
    }
    print(json.dumps(report, indent=2))
    return 0


def _add_known_names(resolved_model: dict, array_key: str) -> set[str]:
    return {
        member.get("name") for member in (resolved_model.get(array_key) or [])
        if isinstance(member, dict) and isinstance(member.get("name"), str)
    }


def _add_bare_name(value: str) -> str:
    return value.split("::", 1)[-1] if "::" in value else value


def _add_normalize_step_type(value: object) -> str:
    return (value or "").strip().lower() if isinstance(value, str) else ""


def _add_flatten_steps(steps):
    """Yield every step in a flow/procedure step list, including ones nested under branch/loop
    containers (`then`/`else`/`steps` -- both $defs.flowStep and $defs.procedureStep in
    model.schema.json use the identical nesting shape)."""
    for step in steps or []:
        if not isinstance(step, dict):
            continue
        yield step
        yield from _add_flatten_steps(step.get("then"))
        yield from _add_flatten_steps(step.get("else"))
        yield from _add_flatten_steps(step.get("steps"))


def _add_scan_concept_references(member: dict, known: dict[str, set[str]]) -> list[str]:
    missing = []
    for label, get_target, _set_target in _pack_export_reference_targets(member):
        target = get_target()
        if isinstance(target, str) and target and _add_bare_name(target) not in known["concepts"]:
            missing.append(f"{label} -> {target!r} (concept not found)")
    for extra_field in ("extends", "specializes"):
        target = member.get(extra_field)
        if isinstance(target, str) and target and _add_bare_name(target) not in known["concepts"]:
            missing.append(f"{member.get('name')}.{extra_field} -> {target!r} (concept not found)")
    return missing


def _add_scan_panel_references(member: dict, known: dict[str, set[str]]) -> list[str]:
    missing = []
    panel_name = member.get("name", "?")
    for data_source in member.get("dataSources", None) or []:
        if not isinstance(data_source, dict):
            continue
        label = f"{panel_name}.dataSources[{data_source.get('name', '?')}]"
        for field, kind_key, kind_label in (
            ("concept", "concepts", "concept"),
            ("query", "queries", "query"),
            ("procedure", "procedures", "procedure"),
            ("onRowLoad", "procedures", "procedure"),
        ):
            target = data_source.get(field)
            if isinstance(target, str) and target and _add_bare_name(target) not in known[kind_key]:
                missing.append(f"{label}.{field} -> {target!r} ({kind_label} not found)")
    for action in member.get("actions", None) or []:
        if not isinstance(action, dict):
            continue
        label = f"{panel_name}.actions[{action.get('name', '?')}]"
        target = action.get("procedure")
        if isinstance(target, str) and target and _add_bare_name(target) not in known["procedures"]:
            missing.append(f"{label}.procedure -> {target!r} (procedure not found)")
        target = action.get("flow")
        if isinstance(target, str) and target and _add_bare_name(target) not in known["flows"]:
            missing.append(f"{label}.flow -> {target!r} (flow not found)")
        if action.get("binding") == "conceptQuery":
            target = action.get("concept")
            if isinstance(target, str) and target and _add_bare_name(target) not in known["concepts"]:
                missing.append(f"{label}.concept -> {target!r} (concept not found)")
    return missing


# FlowValidation.java's own switch (validateFlowSteps): these step types resolve their `scope` /
# `procedure` / `capability` / `event` against the model exactly the way validated here -- see
# validatePersistenceMutationAliasStep, validateCallProcedureStep, validateEventStep.
_ADD_FLOW_CONCEPT_SCOPE_TYPES = {"createconcept", "updateconcept", "invariantcheck"}


def _add_scan_flow_references(member: dict, known: dict[str, set[str]]) -> list[str]:
    missing = []
    flow_name = member.get("name", "?")
    concept = member.get("concept") or (member.get("input") or {}).get("concept")
    if isinstance(concept, str) and concept and _add_bare_name(concept) not in known["concepts"]:
        missing.append(f"{flow_name}.concept -> {concept!r} (concept not found)")
    for step in _add_flatten_steps(member.get("steps")):
        step_type = _add_normalize_step_type(step.get("type"))
        label = f"{flow_name}.steps[{step.get('name', '?')}]"
        if step_type in _ADD_FLOW_CONCEPT_SCOPE_TYPES:
            scope = step.get("scope")
            if isinstance(scope, str) and scope and _add_bare_name(scope) not in known["concepts"]:
                missing.append(f"{label}.scope -> {scope!r} (concept not found)")
        if step_type == "callprocedure":
            procedure = step.get("procedure")
            if isinstance(procedure, str) and procedure and _add_bare_name(procedure) not in known["procedures"]:
                missing.append(f"{label}.procedure -> {procedure!r} (procedure not found)")
        if step_type == "capabilitycall":
            capability = step.get("capability")
            if (isinstance(capability, str) and capability
                    and capability.lower() not in ADD_BUILTIN_CAPABILITIES
                    and _add_bare_name(capability) not in known["capabilities"]):
                missing.append(f"{label}.capability -> {capability!r} (capability not found)")
        if step_type in {"emitevent", "awaitevent", "scheduleevent"}:
            event = step.get("event") or step.get("awaitEvent")
            if isinstance(event, str) and event and _add_bare_name(event) not in known["events"]:
                missing.append(f"{label}.event -> {event!r} (event not found)")
    return missing


# PackValidation.java's own PROCEDURE_*_STEP_TYPES constants -- mirrored here (lowercase, both the
# hyphenated and camelCase spellings the DSL accepts) so the scan agrees exactly with which step
# types actually get a concept/procedure/capability existence check at semantic-validation time.
_ADD_PROCEDURE_CONCEPT_STEP_TYPES = {
    "conceptquery", "readconcept", "read_concept", "listconcepts", "list_concepts",
    "runquery", "run_query", "conceptcreate", "conceptupdate", "saveconcept", "save_concept",
    "conceptdelete", "deleteconcept", "delete_concept", "patchconcept",
}
_ADD_PROCEDURE_CALL_STEP_TYPES = {"procedurecall", "callprocedure", "call_procedure"}
_ADD_PROCEDURE_CAPABILITY_STEP_TYPES = {"capabilitycall", "callcapability", "call_capability"}


def _add_scan_procedure_references(member: dict, known: dict[str, set[str]]) -> list[str]:
    missing = []
    procedure_name = member.get("name", "?")
    for step in _add_flatten_steps(member.get("steps")):
        step_type = _add_normalize_step_type(step.get("type"))
        label = f"{procedure_name}.steps[{step.get('name', '?')}]"
        if step_type in _ADD_PROCEDURE_CONCEPT_STEP_TYPES:
            concept = step.get("concept")
            if not (isinstance(concept, str) and _add_bare_name(concept) in known["concepts"]):
                missing.append(f"{label}.concept -> {concept!r} (concept not found)")
        if step_type in _ADD_PROCEDURE_CALL_STEP_TYPES:
            procedure = step.get("procedure")
            if not (isinstance(procedure, str) and _add_bare_name(procedure) in known["procedures"]):
                missing.append(f"{label}.procedure -> {procedure!r} (procedure not found)")
        if step_type in _ADD_PROCEDURE_CAPABILITY_STEP_TYPES:
            capability = step.get("capability")
            if not (isinstance(capability, str) and (
                    capability.lower() in ADD_BUILTIN_CAPABILITIES
                    or _add_bare_name(capability) in known["capabilities"])):
                missing.append(f"{label}.capability -> {capability!r} (capability not found)")
    return missing


def _add_scan_member_references(kind: str, member: dict, resolved_model: dict) -> list[str]:
    """--from's safety net: an exemplar copied out of another sample can carry references (a
    concept's own `reference`/`extends`/`specializes`, a panel's dataSource/action, a flow's
    concept/procedure/event, a procedure's concept/procedure/capability) that simply do not exist
    in the model being added to. Writing such a member anyway would produce something
    `npdev validate model` immediately rejects -- exactly what R1.5's own Done-When forbids
    ("passes validation with zero errors on first try"). Returns every dangling reference, by
    name, so the caller can refuse the same way MON-18/PACK-13 already do (named offenders, not a
    silent drop or a silent write), rather than shipping a member that looks right and isn't.

    Deliberately scoped to the reference shapes the real semantic validators (FlowValidation,
    PackValidation, PanelValidation, ConceptValidation) actually enforce -- not every field a step
    can carry (e.g. capabilityCall's operation-arity match is left to `npdev validate model`
    itself), matching PACK-13's own precedent of not attempting the full ~20-member reference
    vocabulary exhaustively.
    """
    known = {
        "concepts": _add_known_names(resolved_model, "concepts"),
        "queries": _add_known_names(resolved_model, "queries"),
        "procedures": _add_known_names(resolved_model, "procedures"),
        "flows": _add_known_names(resolved_model, "flows"),
        "events": _add_known_names(resolved_model, "events"),
        "capabilities": {c.get("name") for c in (resolved_model.get("capabilities") or [])
                         if isinstance(c, dict) and isinstance(c.get("name"), str)},
    }
    if kind == "concept":
        return _add_scan_concept_references(member, known)
    if kind == "panel":
        return _add_scan_panel_references(member, known)
    if kind == "flow":
        return _add_scan_flow_references(member, known)
    if kind == "procedure":
        return _add_scan_procedure_references(member, known)
    raise CliError(f"unsupported member kind: {kind}")


def _add_load_exemplar_member(root: Path, kind: str, from_spec: str, new_name: str) -> tuple[dict, str, Path]:
    """Resolves --from `<sample>` or `<sample>::<MemberName>` against NPDevSamples/<sample>/Input/
    model.json (the shape every sample but npdev-init-seed uses) or NPDevSamples/<sample>/
    model.json (npdev-init-seed's own flat shape -- the same two-candidate lookup `npdev init
    --from` already uses).

    PREMISE CHECK (R1.5's own roadmap text): "with `--from` copying an exemplar out of the RAG
    corpus" is FALSE as literally stated. `NPDevMcp/server.py`'s `tool_search_examples` reads
    `<Build>/npdev-ai/rag-index.json` -- ranked TEXT CHUNKS for an AI agent to read, built by
    `scripts/ai/build_knowledge.py` from `knowledge/cards/` + golden scenarios, and not guaranteed
    to exist in a fresh checkout (a Build-root artifact -- CLAUDE.md's own "ephemeral" tier). There
    is no structured concept/panel/flow/procedure JSON in it to copy; there is nothing there for
    `--from` to extract a member out of. The real, in-repo, always-present, already-proven source
    of a "real, working" member is `NPDevSamples/` -- the exact corpus `npdev init --from` already
    draws whole-app seeds from, and the one CLAUDE.md itself names as the DSL-feature reference
    corpus ("Adding a DSL feature? Add a real example to NPDevSamples/dsl-conformance-max").
    """
    sample = from_spec.split("::", 1)[0]
    member_name = from_spec.split("::", 1)[1] if "::" in from_spec else None
    sample_dir = root / "NPDevSamples" / sample
    array_key = ADD_MEMBER_ARRAY_KEYS[kind]
    candidate_paths = [sample_dir / "Input" / "model.json", sample_dir / "model.json"]
    for candidate_model in candidate_paths:
        if not candidate_model.exists():
            continue
        exemplar_model = read_json(candidate_model)
        candidates = [m for m in (exemplar_model.get(array_key) or [])
                      if isinstance(m, dict) and isinstance(m.get("name"), str)]
        if member_name:
            chosen = next((m for m in candidates if m["name"] == member_name), None)
            if chosen is None:
                available = ", ".join(sorted(m["name"] for m in candidates)) or "(none)"
                raise CliError(
                    f"--from {from_spec!r}: no {kind} named {member_name!r} in {candidate_model}. "
                    f"Available {array_key}: {available}"
                )
        else:
            if not candidates:
                raise CliError(
                    f"--from {from_spec!r}: {candidate_model} declares no {array_key} to copy from."
                )
            chosen = candidates[0]
        member = copy.deepcopy(chosen)
        source_name = member["name"]
        member["name"] = new_name
        # The member's own identity label is refreshed to match NAME (a concept named "Owner"
        # should not keep displaying the exemplar's "Canary owner"); everything else -- invariant
        # names, step names, field names -- is left exactly as copied. Those stay internally
        # consistent regardless of the rename (an invariant's own name is only ever referenced
        # from within its own concept, which is copied whole), so rewriting them would be
        # cosmetic-only churn with no correctness payoff, unlike the top-level display label.
        if kind == "concept" and isinstance(member.get("ui"), dict) and isinstance(member["ui"].get("label"), str):
            member["ui"]["label"] = _add_humanize_label(new_name)
        if kind == "panel" and isinstance(member.get("title"), str):
            member["title"] = _add_humanize_label(new_name)
        return member, source_name, candidate_model
    raise CliError(
        f"--from sample not found: {sample!r} "
        f"(looked for {candidate_paths[0]} and {candidate_paths[1]})"
    )


def _add_default_stub(kind: str, name: str, concept: str | None) -> dict:
    """No --from: a minimal, self-contained, schema-AND-semantically-valid member. Every shape
    here is lifted verbatim in structure from a real, currently-passing corpus fixture (`concept`
    from NPDevSamples/npdev-init-seed/model.json's own Patient; `flow`/`procedure` step shapes from
    NPDevSamples/npdev-canary/Input/model.json's CreateCanaryTask/SaveCanaryTaskProcedure; `panel`
    from dsl-conformance-max's WidgetOrderReviewPanel, which proves a concept-bound dataSource
    needs neither `layout` nor `fieldBindings`) rather than hand-derived from schema alone, so a
    subtle semantic-only rule (e.g. ConceptValidation's "must have exactly 1 id field",
    FlowValidation's "flow.concept or flow.input.concept is required") can't be missed by reading
    the JSON Schema in isolation.
    """
    if kind == "concept":
        return {
            "name": name,
            "ui": {"label": _add_humanize_label(name)},
            "fields": [
                {"name": "id", "type": "uuid", "id": True, "required": True},
                {"name": "label", "type": "string", "required": True, "ui": {"label": "Label"}},
            ],
        }
    if kind == "panel":
        return {
            "name": name,
            "route": _add_kebab_route(name),
            "title": _add_humanize_label(name),
            "dataSources": [
                {"name": _add_lower_first(concept), "concept": concept},
            ],
        }
    if kind == "flow":
        return {
            "name": name,
            "input": {"concept": concept, "mode": "create"},
            "steps": [
                {"name": f"save-{_add_lower_first(concept)}", "type": "createConcept",
                 "scope": concept, "input": "$input", "output": "$saved"},
                {"name": "return-saved", "type": "return", "value": "$saved"},
            ],
        }
    if kind == "procedure":
        return {
            "name": name,
            "parameters": [{"name": "id", "type": "uuid", "required": True}],
            "steps": [
                {"name": f"read-{_add_lower_first(concept)}", "type": "readConcept",
                 "concept": concept, "id": "$id", "target": "result"},
                {"name": "return-result", "type": "return", "value": "$result"},
            ],
        }
    raise CliError(f"unsupported member kind: {kind}")


def run_add_member(args: argparse.Namespace, kind: str) -> int:
    """R1.5: `npdev add concept|panel|flow|procedure NAME` writes one schema-valid member into the
    correct top-level model array (MODEL_ARRAY_KEYS knowledge -- ADD_MEMBER_ARRAY_KEYS above),
    seeding its required fields, refusing (naming the offender) rather than silently overwriting
    when NAME already exists -- MON-18/PACK-13 house style. `--from <sample>[::<Member>]` copies a
    real member out of NPDevSamples/ instead of writing a blank stub (see
    _add_load_exemplar_member's docstring for why that is NOT the RAG corpus the roadmap named).

    Existence checks (duplicate name, --concept, --from's reference scan) run against the
    FRAGMENT-COMPOSED view (`resolve_split_model`, the same resolver `npdev inspect app`/`npdev
    dev`'s watch-set use) rather than the raw root file alone, so a concept declared in a $ref'd
    fragment (bounded-context style, S3) is correctly seen as already existing. The new member is
    still appended to --model's OWN top-level array, never into a fragment -- authoring always
    targets the file the caller named.
    """
    model_path = Path(args.model).expanduser().resolve()
    if not model_path.exists():
        raise CliError(f"model not found: {model_path}")
    root_model = read_json(model_path)
    array_key = ADD_MEMBER_ARRAY_KEYS[kind]

    name = (args.name or "").strip()
    if not name:
        raise CliError(f"{kind} name must not be blank")
    if "::" in name:
        raise CliError(f"{kind} name must not contain '::' (that is the pack-qualifier separator): {name!r}")

    uncomposed: list[str] = []
    resolved_model = resolve_split_model(model_path, collect_uncomposed=uncomposed)

    existing_names = _add_known_names(resolved_model, array_key)
    if name in existing_names:
        raise CliError(
            f"Refusing to add: a {kind} named {name!r} already exists in {model_path} (composed "
            f"view -- it may come from a $ref fragment, not the root file itself). Choose a "
            f"different name, or edit the existing declaration directly."
        )

    concept = getattr(args, "concept", None)
    from_spec = getattr(args, "from_exemplar", None)
    source_note = None

    if kind != "concept":
        known_concepts = _add_known_names(resolved_model, "concepts")
        if concept:
            if concept not in known_concepts:
                raise CliError(
                    f"--concept {concept!r} not found in {model_path} (composed view). Existing "
                    f"concepts: {', '.join(sorted(known_concepts)) or '(none)'} -- add it first "
                    f"with `npdev add concept {concept}`."
                )
        elif not from_spec:
            raise CliError(
                f"--concept is required when scaffolding a {kind} without --from (every {kind} in "
                f"this DSL binds to an existing concept -- model.schema.json's own {kind} "
                f"definition and, for flow, JsonModelParser itself, both enforce it)."
            )

    if from_spec:
        member, source_name, source_path = _add_load_exemplar_member(repo_root(), kind, from_spec, name)
        missing = _add_scan_member_references(kind, member, resolved_model)
        if missing:
            raise CliError(
                f"Refusing to add: --from {from_spec!r} (source {kind} {source_name!r} in "
                f"{source_path}) references the following, which do not exist in {model_path}: "
                + "; ".join(missing)
                + f". Add the missing member(s) first (e.g. `npdev add concept <Name>`), or omit "
                  f"--from and let scaffolding seed a self-contained stub instead."
            )
        source_note = f"{from_spec} (source {kind}: {source_name}, {source_path})"
    else:
        member = _add_default_stub(kind, name, concept)

    root_model.setdefault(array_key, [])
    if not isinstance(root_model.get(array_key), list):
        raise CliError(f"{model_path}: top-level {array_key!r} is not an array, refusing to append")
    root_model[array_key].append(member)
    model_path.write_text(json.dumps(root_model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    report = {
        "added": True,
        "kind": kind,
        "name": name,
        "modelPath": str(model_path),
        "arrayKey": array_key,
        "concept": concept,
        "from": source_note,
        "uncomposedContributions": uncomposed,
    }
    print(json.dumps(report, indent=2))
    return 0


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
    uncomposed: list[str] = []
    model = resolve_split_model(model_path, collect_uncomposed=uncomposed)

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
            # REG-186: a build-free read cannot expand a remote `packs[].from` coordinate (only
            # `npdev generate` can, out of the lockfile-backed cache), so every count above
            # EXCLUDES whatever those packs contribute. Reported, never assumed away -- an empty
            # list means "there were none", which is a different claim from "we did not look".
            "uncomposedPacks": uncomposed,
        },
        args.output,
    )


# -------------------------------------------------------------------------------------------------
# -------------------------------------------------------------------------------------------------
# XREF-2: `npdev inspect usage` -- "who uses this field?", read from the Java-emitted index.
# -------------------------------------------------------------------------------------------------

# The kinds an `--of` argument may name explicitly, as `kind:Name`. A bare `--of` argument is
# matched against every kind, which is what an author actually types.
USAGE_TARGET_KINDS = (
    "field", "concept", "query", "procedure", "flow", "event", "capability", "generatedAction",
    "aggregate", "guidePage", "dataSource", "domainType", "invariant", "parameter",
)


def load_model_xref(model_path: Path) -> dict:
    """Build the model-wide reference index by running the :NPDevContract:dsl:modelXref Gradle task.

    Deliberately NOT a Python re-walk of the model. Pack/context composition, `qualifierId::Name`
    qualification, `extends` field inheritance, the groupBy join grammar and the interaction
    expression grammar all live on the Java side; a second implementation here is REG-108's exact
    shape, and this index exists precisely because a reference that nothing checks is a reference
    that silently breaks. Same plumbing as `run_validate_semantic`.
    """
    root = repo_root()
    wrapper = gradle_wrapper(root)
    if not wrapper.exists():
        raise CliError(f"Gradle wrapper not found: {wrapper}")
    model = Path(model_path).expanduser().resolve()
    if not model.exists():
        raise CliError(f"model not found: {model}")

    with tempfile.TemporaryDirectory(prefix="npdev-xref-") as temp_dir:
        report_target = Path(temp_dir) / "model-xref.json"
        gradle_args = [
            str(wrapper),
            *gradle_project_cache_args("root"),
            ":NPDevContract:dsl:modelXref",
            f"-PmodelPath={model}",
            f"-PreportOut={report_target}",
            "-q",
            "--console=plain",
        ]
        if os.name == "nt" and wrapper.suffix.lower() == ".bat":
            gradle_args = ["cmd.exe", "/c"] + gradle_args
        completed = subprocess.run(gradle_args, cwd=root, check=False, capture_output=True, text=True)
        if not report_target.exists():
            detail = (completed.stderr or completed.stdout or "").strip()
            raise CliError(
                "model-xref task did not produce a report"
                + (f" (gradle exit {completed.returncode})" if completed.returncode else "")
                + (f": {detail[-500:]}" if detail else "")
            )
        return read_json(report_target)


def _usage_matches(edge: dict, target: str) -> bool:
    """Does `edge` point at `target`?

    `target` is either `kind:Name`, a `Concept.field`, or a bare name. A bare CONCEPT name also
    matches edges pointing at that concept's FIELDS -- "who uses WidgetOrder?" that omitted every
    panel column reading a WidgetOrder field would be a useless answer.
    """
    wanted = target.strip()
    if ":" in wanted and not wanted.startswith("::"):
        prefix, _, rest = wanted.partition(":")
        if prefix in USAGE_TARGET_KINDS:
            return edge.get("toKind") == prefix and edge.get("toName") == rest
    if edge.get("toName") == wanted:
        return True
    if edge.get("toKind") == "field":
        return edge.get("ownerConcept") == wanted or str(edge.get("toName", "")).startswith(wanted + ".")
    return False


def _select_usage_edges(edges: list[dict], of: str | None) -> tuple[list[dict], str]:
    """The `--of`/no-`--of` half of `inspect usage`'s own selection rule, shared with `impact`
    (R1.6) so the two commands can never drift into disagreeing about what "usages of X" means.
    `--orphans` is NOT covered here -- it is `inspect usage`'s own mode, with no `impact` analogue
    (impact always looks at the whole model's unresolved TOTAL instead, regardless of `--of`)."""
    if of:
        return [e for e in edges if _usage_matches(e, of)], f"usagesOf:{of}"
    return edges, "all"


def _usage_report(report: dict, model_path_label: str, of: str | None, selected: list[dict], mode: str) -> dict:
    """The result shape `inspect usage` prints, built from an already-selected edge list -- shared
    so `impact`'s xrefUsage section is byte-for-byte the same shape, not a second rendering."""
    unresolved = [e for e in selected if e.get("resolution") == "UNRESOLVED"]
    undecidable = [e for e in selected if e.get("resolution") == "UNDECIDABLE"]
    return {
        "model": model_path_label,
        "mode": mode,
        "of": of,
        "counts": {
            "matched": len(selected),
            "resolved": len(selected) - len(unresolved) - len(undecidable),
            "unresolved": len(unresolved),
            "undecidable": len(undecidable),
        },
        # The whole-model totals stay visible even when `--of` narrowed the list: "3 usages" reads
        # very differently next to "and 1 unresolved reference elsewhere in this model".
        "modelTotals": report.get("summary"),
        "edges": selected,
    }


def inspect_usage(args: argparse.Namespace) -> int:
    model_path = Path(args.model).expanduser().resolve()
    report = load_model_xref(model_path)
    edges = report.get("edges") or []

    if args.orphans:
        selected = [e for e in edges if e.get("resolution") != "RESOLVED"]
        mode = "orphans"
    else:
        selected, mode = _select_usage_edges(edges, args.of)

    unresolved = [e for e in selected if e.get("resolution") == "UNRESOLVED"]

    result = _usage_report(report, str(model_path), args.of, selected, mode)

    if args.diagram:
        diagram_path = Path(args.diagram).expanduser()
        diagram_path.parent.mkdir(parents=True, exist_ok=True)
        diagram_path.write_text(
            npdev_diagram.render_usage_diagram_html(
                selected, model_label=model_path.stem, target=args.of or mode),
            encoding="utf-8",
        )
        result["diagram"] = str(diagram_path)

    write_or_print_json(result, args.output)

    # Exit 2 only for a real orphan, and only in --orphans mode: `inspect usage --of X` must still
    # answer on a model that happens to have an unresolved reference somewhere else entirely.
    # (2, not 1, is this CLI's "ran fine and reported a real structured problem" code.)
    if args.orphans and unresolved:
        return 2
    return 0


IMPACT_SCHEMA_VERSION = "npdev-impact-report.v1"


def _of_names_known_concept(resolved_model: dict, of: str) -> bool | None:
    """Best-effort existence pre-check for `impact --of`, in the fragment-COMPOSED view
    (resolve_split_model), matching `npdev add`'s own existence-check discipline (STEP 4 of this
    item's brief) so a concept declared only inside a $ref'd bounded-context fragment is not
    reported as a false miss. Returns True/False only for the two target shapes this can actually
    check (a bare Concept name, or Concept.field) -- None for a `kind:Name` target (procedure:X,
    query:Y, ...), which names something outside the `concepts` array and is left to `--of`'s own
    established "an unreferenced target is an empty answer, not an error" behavior."""
    wanted = of.strip()
    if ":" in wanted and not wanted.startswith("::"):
        prefix, _, _ = wanted.partition(":")
        if prefix in USAGE_TARGET_KINDS:
            return None
    concept_name = wanted.split(".", 1)[0]
    return concept_name in _add_known_names(resolved_model, "concepts")


def _impact_section(status: str, reason: str | None, report: dict | None) -> dict:
    return {"status": status, "reason": reason, "report": report}


def run_impact(args: argparse.Namespace) -> int:
    """R1.6: `npdev impact --baseline <p> --current <p> [--of <target>] [--manifest <j>]` -- ONE
    typed report composing the four separate "what breaks if I change this?" invocations that used
    to require four separate commands: migration classification (`migration diff`'s own
    :generator:classifyModelChange, via the R1.1 warm-jar fast path), xref usage (`inspect usage`'s
    own :NPDevContract:dsl:modelXref, shared selection logic), pack diff (`pack diff`'s own
    :NPDevContract:dsl:packDiff), and the AI Authoring Contract's diff-gate (`author diff-gate`'s
    own :generator:authorDiffGate). No new diffing/classification logic lives here -- every leg
    calls the exact function its own standalone command calls, so `impact` can never drift from
    what running the four commands separately would report.

    Deliberately does NOT generate, build, or boot anything (that is `npdev loop run`'s job, which
    already composes diff-gate + validate + classify + run + acceptance into the HEAVY pipeline);
    this is the light, structural preview meant to run in seconds, before any of that.

    subjectKind ('model' or 'pack') is read from --baseline/--current's own top-level shape
    (_document_kind) and decides which legs apply: JsonModelParser (migration classification, xref,
    authoring gate) cannot parse a pack.json, and PackDiffEngine (pack diff) has no model.json
    input -- so exactly one of the two document kinds' legs runs per call, and the other kind's legs
    report status 'notApplicable' with a reason, never silent omission (STEP 3 measurement honesty).
    """
    baseline_path = Path(args.baseline).expanduser().resolve()
    current_path = Path(args.current).expanduser().resolve()
    if not baseline_path.exists():
        raise CliError(f"baseline not found: {baseline_path}")
    if not current_path.exists():
        raise CliError(f"current not found: {current_path}")

    baseline_kind = _document_kind(read_json(baseline_path), baseline_path)
    current_kind = _document_kind(read_json(current_path), current_path)
    if baseline_kind != current_kind:
        raise CliError(
            f"--baseline ({baseline_path}) looks like a {baseline_kind}.json and --current "
            f"({current_path}) looks like a {current_kind}.json -- both must be the same kind of "
            f"document (a model.json pair, or a pack.json pair)."
        )
    subject_kind = current_kind
    of = getattr(args, "of", None)
    manifest = getattr(args, "manifest", None)
    if subject_kind == "pack" and of:
        raise CliError("--of only applies to a model.json pair (xref usage has no pack.json input).")
    if subject_kind == "pack" and manifest:
        raise CliError("--manifest only applies to a model.json pair (the AI Authoring Contract "
                        "diff-gate has no pack.json input).")

    import time  # local, matching _run_bounded/run_closed_loop's own convention

    root = repo_root()
    timeout = float(getattr(args, "timeout", 300) or 300)
    deadline = time.monotonic() + timeout

    limitations = [
        "xref usage cannot see a reference embedded as a literal INSIDE an expression string, e.g. "
        "nextNumber('name') inside a defaultExpression -- the target is real but invisible to "
        "reference-rewriting/discovery (R5.3's finding, reproduced live for that exact case).",
        "an UNDECIDABLE xref edge (an expression outside the interaction grammar, an untyped $var, "
        "...) means 'could not be checked', never 'checked and safe' -- see this report's own "
        "xrefUsage.report.counts.undecidable.",
        "pack diff (PACK-13) refuses a broken reference by NAME rather than silently dropping it, "
        "but only for references INSIDE the exported/diffed pack's own declared members -- a "
        "reference from outside the pack pointing INTO it is outside this report's view entirely.",
    ]
    problems_found = False

    if subject_kind == "model":
        # STEP 4: existence checks against the fragment-COMPOSED view, like `npdev add` -- a model
        # split into bounded-context fragments must not report a false "cannot compose" or a false
        # miss for --of. resolve_split_model itself raises CliError (naming the offending file) if
        # the CURRENT model cannot be composed at all, which is a much clearer failure than letting
        # two separate Gradle subprocesses fail cryptically a few seconds later.
        resolved_current = resolve_split_model(current_path)
        if of:
            known = _of_names_known_concept(resolved_current, of)
            if known is False:
                limitations.append(
                    f"--of {of!r} does not name a concept found in --current's fragment-composed "
                    f"view -- xrefUsage below will report 0 matches; this is not necessarily wrong "
                    f"(an empty answer is a real answer), but check for a typo or a missing fragment "
                    f"$ref first."
                )

        migration_report = _classify_model_change_report(root, baseline_path, current_path, deadline)
        migration_section = _impact_section("ran", None, migration_report)

        xref_report = load_model_xref(current_path)
        edges = xref_report.get("edges") or []
        selected, mode = _select_usage_edges(edges, of)
        xref_section_report = _usage_report(xref_report, str(current_path), of, selected, mode)
        xref_section = _impact_section("ran", None, xref_section_report)
        whole_model_unresolved = int((xref_report.get("summary") or {}).get("unresolved", 0))
        if whole_model_unresolved:
            problems_found = True

        diff_gate_args = argparse.Namespace(
            previous=str(baseline_path), submitted=str(current_path), manifest=manifest, output=None,
        )
        authoring_report = _run_authoring_gate(diff_gate_args, archive_dir=None)
        authoring_reason = None if manifest else (
            "ran with no --manifest, so it reports the same AUTHORING_MANIFEST_MISSING refusal "
            "`author diff-gate` itself would without one -- pass --manifest for a real compliance "
            "check (renames/removals/security-relevant deltas all declared)."
        )
        authoring_section = _impact_section("ran", authoring_reason, authoring_report)
        if authoring_report.get("status") != "passed":
            problems_found = True

        pack_section = _impact_section(
            "notApplicable",
            "--baseline/--current are model.json, not pack.json -- pack diff (PackDiffEngine) has "
            "no model.json input. If this app depends on packs, diff their own pack.json files "
            "directly with `npdev pack diff`.",
            None,
        )
    else:
        pack_report = _pack_diff_report(baseline_path, current_path, None)
        pack_section = _impact_section("ran", None, pack_report)

        not_applicable_reason = (
            "--baseline/--current are pack.json, not model.json -- JsonModelParser cannot parse a "
            "pack.json (different required top-level shape), so migration classification / xref "
            "usage / the AI Authoring Contract diff-gate have no input here."
        )
        migration_section = _impact_section("notApplicable", not_applicable_reason, None)
        xref_section = _impact_section("notApplicable", not_applicable_reason, None)
        authoring_section = _impact_section("notApplicable", not_applicable_reason, None)

    result = {
        "schemaVersion": IMPACT_SCHEMA_VERSION,
        "subjectKind": subject_kind,
        "baseline": str(baseline_path),
        "current": str(current_path),
        "of": of,
        "migrationClassification": migration_section,
        "xrefUsage": xref_section,
        "packDiff": pack_section,
        "authoringGate": authoring_section,
        "limitations": limitations,
        "problemsFound": problems_found,
    }
    write_or_print_json(result, getattr(args, "output", None))
    return 2 if problems_found else 0


# -------------------------------------------------------------------------------------------------
# MONITOR_PLAN A2/A4 command bodies. Thin: the modules hold the behaviour, these hold the argv-to-
# call translation and the ONE printing convention the rest of this file already uses (exit 0 = fully
# ok, exit 2 = ran fine and reported a real structured problem, exit 1 only for an unexpected raise).
# -------------------------------------------------------------------------------------------------

def _print_result(result: dict, args: argparse.Namespace, *, redact_output: bool = True) -> None:
    """--json prints the object; without it, a short human summary. Both, always -- a command that
    only speaks JSON is a command a person cannot use, and the CLI has to stay usable in a terminal
    for D1's promise ('nothing here is a dead end if you later want the command line') to be true.

    REG-153: redacted by DEFAULT before printing, either way. CodeQL's default-setup scan traced a
    real path into both branches here: `npdev_monitor.probe_app()` puts the app's live API key
    (read from `secrets/api-key.env`) in `record["apiKey"]`, and that record (or something built
    from it) reaches this function for several `monitor`/`explore` subcommands. `redact()` is the
    exact key-name-driven scrub already used for the log-bundle export and the AI-repair payload
    (`npdev_monitor.redact`, D10/E3-a) -- this was simply the one path that never called it.
    `redact_output=False` is a narrow, explicit opt-out for the ONE caller (`monitor probe`) whose
    entire job is answering "what is this app's real API key": `NPDevSamples/scripts/
    sample-common.ps1`'s `Get-NpdevLiveApiKey` shells out to exactly `monitor probe --json` and
    parses `.apiKey`, so redacting that command's output would trade a hardening for a real
    functional break. Every other caller of this function stays safe by default."""
    safe_result = npdev_monitor.redact(result) if redact_output else result
    if getattr(args, "json", False):
        print(json.dumps(safe_result, indent=2, ensure_ascii=False))
        return
    print(_human_summary(safe_result))


def _human_summary(result: dict) -> str:
    command = result.get("command", "")
    lines: list[str] = []
    if "apps" in result:
        for app in result["apps"]:
            mark = {"running": "UP", "stopped": "--", "starting": "..", "error": "!!"}.get(app.get("health"), "??")
            lines.append(f"  [{mark}] {app.get('name'):<24} {app.get('engine') or '-':<10} "
                         f"port {app.get('port') or '-':<6} {app.get('appDir')}")
        lines.insert(0, f"{len(result['apps'])} app(s) found:")
        for searched in result.get("searched", []):
            if not searched.get("exists"):
                lines.append(f"  (path not found: {searched['path']})")
    elif command == "monitor probe" or "health" in result:
        lines.append(f"{result.get('name')}  {result.get('health')}  {result.get('healthDetail') or ''}")
        for key in ("appDir", "opsDir", "modelPath", "jarPath", "dbFile", "superUserKeyFile", "logsDir"):
            if result.get(key):
                lines.append(f"  {key:<18} {result[key]}")
    elif "found" in result and "state" in result:
        lines.append(f"ScrapForAI engine: {result['state']}")
        lines.append(f"  via      : {result.get('via')}")
        lines.append(f"  endpoint : {result.get('endpoint')}")
        lines.append(f"  root     : {result.get('root')}")
        lines.append(f"  {result.get('detail')}")
    else:
        lines.append(json.dumps(result, indent=2, ensure_ascii=False))
    return "\n".join(lines)


def _split_paths(raw: str) -> list[str]:
    """';' always, and ':' too on POSIX. Never ':' on Windows -- 'D:\\Apps' would split into 'D' and
    '\\Apps', which is the classic way a path list silently loses every entry."""
    parts = raw.split(";")
    if os.name != "nt":
        expanded = []
        for part in parts:
            expanded.extend(part.split(":"))
        parts = expanded
    return [p for p in (p.strip() for p in parts) if p]


def run_monitor(args: argparse.Namespace) -> int:
    if args.monitor_command == "scan":
        result = npdev_monitor.scan_paths(
            _split_paths(args.paths), max_depth=args.depth,
            include_info=args.include_info, health_timeout=args.health_timeout)
        _print_result(result, args)
        return 0
    if args.monitor_command == "probe":
        result = npdev_monitor.probe_app(
            Path(args.app_dir), include_info=args.include_info, origin="explicit",
            health_timeout=args.health_timeout)
        result.setdefault("schemaVersion", "npdev-monitor-probe.v1")
        result.setdefault("command", "monitor probe")
        result["ok"] = result.get("status") == "ok"
        # REG-153: this is the one command whose job is answering "what is this app's real API
        # key" (see the comment on `record["apiKey"]` in `npdev_monitor.probe_app`) -- keep it
        # unredacted here, matching the documented, tested contract `Get-NpdevLiveApiKey` depends on.
        _print_result(result, args, redact_output=False)
        return 0 if result["ok"] else 2
    if args.monitor_command == "engine":
        result = npdev_monitor.detect_engine(args.port, args.root, repo_root())
        result["schemaVersion"] = "npdev-monitor-engine.v1"
        result["command"] = "monitor engine"
        result["ok"] = True
        _print_result(result, args)
        return 0
    if args.monitor_command == "engine-start":
        return _run_monitor_engine_start(args)
    if args.monitor_command == "logs":
        return _run_monitor_logs(args)
    if args.monitor_command == "ops":
        return _run_monitor_ops(args)
    raise CliError("usage: npdev monitor {scan|probe|engine|engine-start|logs|ops}")


def _run_monitor_engine_start(args: argparse.Namespace) -> int:
    """Start the engine and STAY as its parent until it exits.

    Staying is the point (R2). The engine outlives individual requests by design, so something has
    to own its lifetime; whoever runs this command does. The Manager spawns it inside its job object,
    so closing the window takes this process and the engine down together instead of leaving a
    browser-automation server listening on a user's machine.
    """
    # This module imports `time` per-function (there is no module-level import), and this one was
    # missing while the readiness loop below calls time.sleep -- so `npdev monitor engine-start`
    # raised NameError the moment it reached that loop, i.e. on every invocation that got as far as
    # waiting for the engine. Found when an agent had to bypass the verb and spawn the engine
    # process directly to do browser verification.
    import time
    root = Path(args.root).expanduser()
    if not npdev_monitor._engine_root_ok(root):
        raise CliError(
            f"{root} is not a ScrapForAI engine root. It must contain src/server.ts AND "
            "node_modules/.bin/tsx* -- checked by CONTENTS, never by directory name. "
            "Find one with `npdev monitor engine --json`."
        )
    api_key = args.api_key or os.environ.get("SCRAPFORAI_API_KEY") or "npdev-scrapforai-localkey-0001"
    artifact_dir = _ai_build_root() / "scrapforai-artifacts"
    artifact_dir.mkdir(parents=True, exist_ok=True)

    def emit(event: dict) -> None:
        print(json.dumps(event) if args.json else
              f"[{event.get('kind')}] {event.get('detail') or event.get('endpoint') or ''}", flush=True)

    argv = npdev_monitor.engine_start_command(str(root), args.port, args.allow_origin, api_key,
                                              str(artifact_dir))
    if not Path(argv[0]).exists():
        raise CliError(f"the engine launcher is missing: {argv[0]} -- run `npm install` in {root} once")
    env = dict(os.environ)
    env.update(npdev_monitor.engine_start_env(args.port, args.allow_origin, api_key, str(artifact_dir)))
    emit({"kind": "starting", "root": str(root), "port": args.port,
          "allowedOrigins": sorted(set(args.allow_origin))})
    process = subprocess.Popen(argv, cwd=str(artifact_dir), env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    endpoint = f"http://127.0.0.1:{args.port}"
    try:
        for _ in range(60):
            if process.poll() is not None:
                emit({"kind": "failed", "detail": f"the engine exited immediately (code {process.returncode})"})
                return 2
            status, _ = npdev_monitor._http_json(f"{endpoint}/health", timeout=1.0)
            if status is not None:
                emit({"kind": "ready", "endpoint": endpoint})
                break
            time.sleep(0.5)
        else:
            process.terminate()
            emit({"kind": "failed", "detail": f"the engine did not become ready on {endpoint} within 30s"})
            return 2
        process.wait()
        emit({"kind": "stopped", "exitCode": process.returncode})
        return 0
    except KeyboardInterrupt:
        process.terminate()
        emit({"kind": "stopped", "exitCode": None, "detail": "interrupted"})
        return 0


def _run_monitor_logs(args: argparse.Namespace) -> int:
    app_dir = Path(args.app_dir)
    if args.action == "export":
        if not args.out:
            raise CliError("npdev monitor logs export needs --out <file.zip>")
        result = npdev_monitor.export_logs(app_dir, Path(args.out), runs=args.runs)
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            print(f"wrote {result['zip']} ({result['bytes']} bytes, {len(result['included'])} entries)")
            print("Credentials are redacted. Nothing is sent anywhere -- send it yourself, deliberately.")
        return 0
    if args.follow:
        return _follow_logs(app_dir, args)
    result = npdev_monitor.collect_logs(app_dir, args.source, args.tail)
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        for source in result["sources"]:
            print(f"--- {source['source']} ({source['directory'] or 'no directory'}) ---")
            if source["detail"]:
                print(f"    {source['detail']}")
            for line in source["tail"]:
                print(line)
    return 0


def _follow_logs(app_dir: Path, args: argparse.Namespace) -> int:
    """`tail -f` over the NEWEST file of the chosen source. Emits JSON Lines under --json so the
    Manager can stream it through the same pattern `start_dev_streaming` already uses."""
    files = npdev_monitor._log_files(Path(app_dir).expanduser().resolve(),
                                     args.source if args.source != "all" else "app")
    if not files:
        message = npdev_monitor._no_logs_detail(args.source if args.source != "all" else "app")
        if args.json:
            print(json.dumps({"kind": "empty", "detail": message}))
        else:
            print(message)
        return 0
    target = files[-1]
    if args.json:
        print(json.dumps({"kind": "following", "file": str(target)}))
    else:
        print(f"--- following {target} (Ctrl-C to stop) ---")
    for line in npdev_monitor._tail(target, args.tail):
        print(json.dumps({"kind": "line", "text": line}) if args.json else line)
    try:
        with target.open("r", encoding="utf-8", errors="replace") as handle:
            handle.seek(0, os.SEEK_END)
            while True:
                line = handle.readline()
                if not line:
                    time.sleep(0.4)
                    continue
                text = line.rstrip("\n")
                print(json.dumps({"kind": "line", "text": text}) if args.json else text, flush=True)
    except KeyboardInterrupt:
        return 0


def _run_monitor_ops(args: argparse.Namespace) -> int:
    script = npdev_monitor.ops_script_path(Path(args.app_dir), args.script)
    if script is None:
        raise CliError(
            f"this app has no {npdev_monitor.OPS_SCRIPTS[args.script]} -- generate it first "
            "(the _ops toolbox is written by generation, and lives INSIDE the app)"
        )
    required_token = npdev_monitor.DESTRUCTIVE_OPS.get(args.script)
    if required_token and args.confirm != required_token:
        result = {
            "schemaVersion": "npdev-cli-result.v1", "command": f"monitor ops {args.script}",
            "ok": False, "exitCode": 2,
            "error": {"message": f"refused: {args.script} destroys data. Re-run with --confirm {required_token}"},
        }
        _print_result(result, args)
        return 2
    powershell = npdev_monitor.find_powershell()
    if powershell is None:
        raise CliError("no PowerShell found (pwsh or powershell) -- the generated runbook needs one")

    # D10 source 2: also append to <app>/logs/ops-<script>-<timestamp>.log, so a closed window is
    # not a lost run. The stream still goes to stdout for the caller.
    log_path = npdev_monitor.ops_log_path(Path(args.app_dir), args.script)
    command = [powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)]
    if required_token:
        command += ["-Confirm", required_token]
    captured: list[str] = []
    with log_path.open("w", encoding="utf-8") as log_file:
        log_file.write(f"# npdev monitor ops {args.script}\n# {script}\n# started {_utc_now()}\n")
        process = subprocess.Popen(command, cwd=str(script.parent), stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        for line in process.stdout:
            line = line.rstrip("\n")
            captured.append(line)
            log_file.write(line + "\n")
            log_file.flush()
            if args.json:
                print(json.dumps({"kind": "line", "text": line}), flush=True)
            else:
                print(line, flush=True)
        code = process.wait()
    result = {
        "schemaVersion": "npdev-cli-result.v1",
        "command": f"monitor ops {args.script}",
        "ok": code == 0,
        "exitCode": code,
        "script": str(script),
        "logFile": str(log_path),
        "output": "\n".join(captured),
    }
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"(exit {code}; captured to {log_path})")
    return 0 if code == 0 else 2


# ---------------------------------------------------------------------------------------------
# MON-22 follow-up: `npdev service install|uninstall` -- a THIN wrapper over the four scripts
# OperationalRunbookEmitter (R9.6) already writes into every generated app's `_ops`
# (Install-Service.ps1/Uninstall-Service.ps1 on Windows, install-service.sh/uninstall-service.sh
# elsewhere). This locates and invokes them; it never reimplements OS-level supervision itself --
# the same "wrap the launchers, do not reimplement them" rule those scripts document one layer
# down for Start-App.ps1/run-final-app.sh.
# ---------------------------------------------------------------------------------------------

def run_service_install(args: argparse.Namespace) -> int:
    return _run_service_op(args, "install")


def run_service_uninstall(args: argparse.Namespace) -> int:
    return _run_service_op(args, "uninstall")


def _is_windows_platform() -> bool:
    """A thin, separately-mockable wrapper around `os.name == "nt"`. Tests need to exercise the
    POSIX branch of `_service_script`/`_run_service_op` on a real Windows dev machine -- monkey-
    patching the actual `os.name` attribute globally is NOT safe for that (pathlib's own `Path()`
    factory reads the same shared `os` module and refuses to instantiate a `PosixPath` while the
    real OS is Windows, `pathlib._abc.UnsupportedOperation`, reproduced live while writing this
    module's own tests) -- so this indirection is the seam a test patches instead."""
    return os.name == "nt"


def _service_script(app_record: dict, op: str) -> tuple[Path, list[str]]:
    """The platform-appropriate emitted `_ops` service script for `op` ("install"/"uninstall"),
    and the base command used to invoke it. `_ops` lives INSIDE the FinalApp root since QUAL-3
    (`OperationalRunbookEmitter`: `finalAppRoot.resolve("_ops")`) -- the same anchor `npdev bench`
    uses (`app_record["finalAppRoot"]`), not the discovery-only `app_record["opsDir"]`, which can
    point at the pre-QUAL-3 legacy-shared location instead.
    """
    ops_dir = Path(app_record["finalAppRoot"]) / "_ops"
    if _is_windows_platform():
        name = "Install-Service.ps1" if op == "install" else "Uninstall-Service.ps1"
        script = ops_dir / name
        shell = _find_powershell()
        if shell is None:
            raise CliError(f"no PowerShell found (looked for `pwsh`, then `powershell`) -- {name} "
                            "is a PowerShell script and needs one")
        return script, [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)]
    name = "install-service.sh" if op == "install" else "uninstall-service.sh"
    script = ops_dir / name
    return script, ["sh", str(script)]


def _run_service_op(args: argparse.Namespace, op: str) -> int:
    """Resolves the app exactly the way `npdev bench` does (`npdev_monitor.probe_app`), refuses
    clearly if this is not a generated NPDev app or the script is missing (an app generated before
    R9.6, or missing this platform's twin), and passes --dry-run/--start/--profile straight
    through to the real script. Never touches OS service state itself.
    """
    app_dir = Path(args.app_dir).expanduser().resolve()
    app_record = npdev_monitor.probe_app(app_dir, include_info=False)
    if not app_record.get("isAppRoot"):
        raise CliError(app_record.get("detail") or f"not a generated NPDev app: {app_dir}")

    script, command = _service_script(app_record, op)
    if not script.is_file():
        raise CliError(
            f"{script} does not exist -- this app was generated before R9.6 (or this platform's "
            "twin script was never emitted for it). Regenerate the app to pick up the _ops "
            "service scripts, or supervise it another way (a Docker restart policy, an external "
            "process supervisor)."
        )

    is_windows = _is_windows_platform()

    if op == "uninstall" and args.dry_run:
        # Neither Uninstall-Service.ps1 nor uninstall-service.sh has a native dry-run mode -- both
        # are already an idempotent no-op when nothing is installed, which is the only case this
        # command's own tests exercise for real (installing a service is privileged and hard to
        # reverse). Rather than silently drop --dry-run or claim a flag the scripts do not have,
        # print exactly what WOULD run and stop -- nothing is executed.
        result = {
            "schemaVersion": "npdev-cli-result.v1", "command": f"service {op}", "ok": True,
            "dryRun": True, "script": str(script), "wouldRun": command,
            "note": f"{script.name} has no native dry-run mode; nothing was executed. Without "
                    "--dry-run this command is idempotent anyway: a no-op if nothing is installed "
                    "for THIS app, a named refusal if a DIFFERENT app's install already claims the "
                    "same name.",
        }
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            print(result["note"])
            print("would run: " + " ".join(command))
        return 0

    profile = getattr(args, "profile", None)
    if op == "install":
        if profile and is_windows:
            raise CliError(
                "Install-Service.ps1 (Windows) has no --profile option -- it always wraps "
                "Start-App.ps1 unchanged. --profile only applies to install-service.sh (systemd)."
            )
        if args.dry_run:
            command = command + (["-DryRun"] if is_windows else ["--dry-run"])
        if args.start:
            command = command + (["-Start"] if is_windows else ["--start"])
        if profile and not is_windows:
            command = command + ["--profile", profile]

    if not args.json:
        print("running: " + " ".join(command))
    completed = subprocess.run(command, cwd=str(script.parent), capture_output=True, text=True)
    output = ((completed.stdout or "") + (completed.stderr or "")).strip()
    result = {
        "schemaVersion": "npdev-cli-result.v1",
        "command": f"service {op}",
        "ok": completed.returncode == 0,
        "exitCode": completed.returncode,
        "script": str(script),
        "dryRun": bool(getattr(args, "dry_run", False)),
        "output": output,
    }
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(output)
        print(f"(exit {completed.returncode})")
    return 0 if completed.returncode == 0 else 2


def _explore_pairs(pairs: list[str]) -> dict:
    """`--var NAME=VALUE` / `--credential NAME=VALUE` -> a dict. One parser for both flags and for
    both `run` and `suite`, so a suite cannot interpret an override differently from a single run."""
    parsed: dict = {}
    for pair in pairs or []:
        name, _, value = pair.partition("=")
        if name:
            parsed[name] = value
    return parsed


def _explore_emitter(as_json: bool):
    """Progress events, streamed as they happen -- an exploration takes minutes and silence during
    it is indistinguishable from a hang."""
    if as_json:
        return lambda event: print(json.dumps(event), flush=True)
    return lambda event: print(
        f"  [{event.get('kind')}] "
        f"{event.get('phase') or event.get('state') or event.get('runId') or ''}"
        f"{(' ' + event['name']) if event.get('name') else ''}", flush=True)


def run_explore(args: argparse.Namespace) -> int:
    import npdev_explore

    root = repo_root()
    try:
        if args.explore_command == "list":
            result = npdev_explore.list_explorations(Path(args.app_dir), args.limit)
        elif args.explore_command == "show":
            result = npdev_explore.show_run(Path(args.app_dir), args.run)
        elif args.explore_command == "validate":
            result = npdev_explore.validate_routine(root, Path(args.file), args.base_url)
            result.setdefault("schemaVersion", "npdev-exploration-validate.v1")
            result.setdefault("command", "explore validate")
        elif args.explore_command == "preflight":
            result = npdev_explore.preflight(Path(args.app_dir), args.engine_port, args.engine_root, root)
            result["schemaVersion"] = "npdev-exploration-preflight.v1"
            result["command"] = "explore preflight"
        elif args.explore_command == "run":
            result = npdev_explore.run_exploration(
                root, Path(args.app_dir), Path(args.file),
                engine_port=args.engine_port, configured_root=args.engine_root, api_key=args.api_key,
                driver=args.driver, variables=_explore_pairs(args.var),
                credentials=_explore_pairs(args.credential), ledger_id=args.ledger_id,
                keep_engine=args.keep_engine, on_event=_explore_emitter(args.json))
            result["command"] = "explore run"
            result["ok"] = True
        elif args.explore_command == "suite":
            # R3.1. The roll-up decides the exit code (`ok` comes back False when anything is red,
            # refused or skipped), which is why -- unlike `run` above -- nothing forces ok=True here.
            result = npdev_explore.run_suite(
                root, Path(args.app_dir), only=args.only, stop_on_red=args.stop_on_red,
                engine_port=args.engine_port, configured_root=args.engine_root, api_key=args.api_key,
                driver=args.driver, variables=_explore_pairs(args.var),
                credentials=_explore_pairs(args.credential), ledger_id=args.ledger_id,
                keep_engine=args.keep_engine, on_event=_explore_emitter(args.json))
        elif args.explore_command == "record":
            payload = read_json(Path(args.from_file))
            result = npdev_explore.record_external(
                root, Path(args.app_dir) if args.app_dir else None, payload,
                driver=args.driver, scope=args.scope, suite=args.suite,
                definition_kind=args.definition_kind,
                routine_file=Path(args.routine_file) if args.routine_file else None,
                ledger_id=args.ledger_id,
                artifact_dir=Path(args.artifact_dir) if args.artifact_dir else None)
            result["command"] = "explore record"
            result["ok"] = True
        elif args.explore_command == "prune":
            result = npdev_explore.prune(Path(args.app_dir), keep_per_scenario=args.keep_per_scenario,
                                         red_days=args.red_days, dry_run=args.dry_run)
        elif args.explore_command == "pin":
            result = npdev_explore.pin_run(Path(args.app_dir), args.run, args.ledger, args.unpin)
        elif args.explore_command == "accept":
            result = npdev_explore.accept_baseline(Path(args.app_dir), args.run)
        elif args.explore_command == "context":
            result = npdev_explore.build_context_pack(root, Path(args.app_dir), args.exemplars)
        elif args.explore_command == "repair-payload":
            result = npdev_explore.build_repair_payload(
                root, Path(args.app_dir), args.prompt, run_id=args.run,
                include_page_text=args.include_page_text)
        elif args.explore_command == "coverage":
            result = npdev_explore.coverage(Path(args.app_dir))
        elif args.explore_command == "generate":
            result = npdev_explore.generate_routines(
                Path(args.app_dir), concepts=args.concept or None,
                out_dir=Path(args.out_dir) if args.out_dir else None,
                write=not args.dry_run)
        else:
            raise CliError("usage: npdev explore "
                           "{list|show|validate|preflight|run|suite|record|prune|pin|accept|context|"
                           "coverage|generate}")
    except npdev_explore.ExploreError as exc:
        # A refusal is a DIAGNOSED problem, not a crash -- and never rendered like a failed
        # exploration (D4). Exit 2, structured, with the sentence that says what to do.
        raise CliError(str(exc)) from exc

    # REG-153: `explore preflight`'s result embeds a whole `npdev_monitor.probe_app()` record
    # (under `result["app"]`), which carries the target app's live API key -- CodeQL's default-setup
    # scan traced exactly that into both print branches below. No `explore` subcommand's output is
    # a documented source of a credential for other tooling (unlike `monitor probe`, see
    # `_print_result`'s own REG-153 note), so redacting here is safety net with no known behaviour
    # cost.
    safe_result = npdev_monitor.redact(result)
    if args.json:
        print(json.dumps(safe_result, indent=2, ensure_ascii=False))
    else:
        print(_explore_human_summary(args.explore_command, safe_result))
    return 0 if result.get("ok", True) else 2


def _explore_human_summary(command: str, result: dict) -> str:
    lines: list[str] = []
    if command == "list":
        lines.append(f"{len(result['definitions'])} definition(s), {result['runCount']} run(s)")
        for definition in result["definitions"]:
            lines.append(f"  {definition['name']:<32} {definition['stepCount']:>3} steps  "
                         f"{'baseline' if definition['hasBaseline'] else ''}")
        for run in result["runs"][:10]:
            verdict = "GREEN" if (run.get("verdict") or {}).get("green") else "RED"
            lines.append(f"  {run['runId']:<40} {verdict:<6} {run.get('driver')}")
    elif command == "validate":
        lines.append("VALID" if result["valid"] else "INVALID")
        for error in result.get("errors", []):
            lines.append(f"  error   {error.get('path')} {error.get('message')}")
        for warning in result.get("warnings", []):
            lines.append(f"  {warning['level']:<7} {warning['message']}")
        if result.get("unassertedFormats"):
            lines.append(f"  note    these `format` values were NOT asserted: "
                         f"{', '.join(result['unassertedFormats'])}")
    elif command in ("run", "record"):
        verdict = result.get("verdict") or {}
        lines.append(f"{result.get('runId')}  status={result.get('status')}  "
                     f"{'GREEN' if verdict.get('green') else 'RED'}")
        for reason in verdict.get("reasons", []):
            lines.append(f"  why-not-green: {reason}")
        for excuse in verdict.get("excused", []):
            lines.append(f"  excused ({excuse['rule']}): {excuse['text'][:120]}")
    elif command == "suite":
        counts = result["counts"]
        lines.append(f"{'GREEN' if result['green'] else 'RED'}  "
                     f"{counts['green']}/{counts['total']} green, {counts['red']} red, "
                     f"{counts['refused']} refused, {counts['skipped']} skipped "
                     f"({result['durationMs']} ms)")
        for entry in result["runs"]:
            # `refused` and `skipped` stay visually distinct from `red`: a tool problem rendered as
            # a test result is the QUAL-4 lesson, and a shortened list with no rows for what did not
            # run is how a stopped suite gets misread as a smaller one.
            lines.append(f"  [{entry['outcome']:<7}] {entry['name']:<32} {entry.get('runId') or ''}")
            for reason in entry.get("reasons") or []:
                lines.append(f"      {reason}")
        if result.get("aborted"):
            lines.append(f"  aborted: {result['aborted']}")
        elif result.get("stoppedEarly"):
            lines.append(f"  stopped early: {result['stoppedEarly']}")
    elif command == "preflight":
        for check in result["checks"]:
            lines.append(f"  [{check['status']}] {check['name']} -- {check.get('detail')}")
    elif command == "prune":
        lines.append(f"kept {result['runsKept']} run(s); removed {result['blobsRemoved']} blob(s), "
                     f"{result['bytesFreed']} bytes")
        lines.append(f"  {result['recordsNote']}")
        for run_id, why in list(result["keptBecause"].items())[:20]:
            lines.append(f"  kept {run_id}: {why}")
    elif command == "coverage":
        summary = result["summary"]
        lines.append(f"concepts: {summary['conceptsCovered']}/{summary['conceptsTotal']} covered  "
                     f"flows: {summary['flowsCovered']}/{summary['flowsTotal']} covered")
        for concept in result["concepts"]:
            last_green = concept.get("lastGreenRun")
            status = "covered" if concept["covered"] else "UNCOVERED"
            lines.append(f"  [{status:<9}] concept {concept['name']:<24} "
                         f"routines={','.join(concept['referencingRoutines']) or '-'}  "
                         f"lastGreenRun={last_green['runId'] if last_green else '-'}")
        for flow in result["flows"]:
            status = "covered" if flow["covered"] else "UNCOVERED"
            lines.append(f"  [{status:<9}] flow    {flow['name']:<24} "
                         f"scenarios={','.join(flow['referencingScenarios']) or '-'}")
        if result["uncovered"]["concepts"] or result["uncovered"]["flows"]:
            lines.append("  UNCOVERED:")
            if result["uncovered"]["concepts"]:
                lines.append(f"    concepts: {', '.join(result['uncovered']['concepts'])}")
            if result["uncovered"]["flows"]:
                lines.append(f"    flows:    {', '.join(result['uncovered']['flows'])}")
        else:
            lines.append("  UNCOVERED: none")
    elif command == "generate":
        summary = result["summary"]
        lines.append(f"wrote {summary['written']} routine(s) ({summary['partial']} create+list only) "
                     f"for {summary['conceptsTotal']} concept(s); {summary['skipped']} skipped "
                     f"-- out: {result['outDir']}")
        for entry in result["written"]:
            note = f"  ({entry['partial']})" if entry.get("partial") else ""
            lines.append(f"  [written] {entry['concept']:<28} {entry['stepCount']:>3} steps  "
                         f"{Path(entry['file']).name}{note}")
        for row in result["skipped"]:
            lines.append(f"  [skipped] {row['concept']:<28} {row['reason']}")
    else:
        lines.append(json.dumps(result, indent=2, ensure_ascii=False))
    return "\n".join(lines)


def run_ai_generate_routine(args: argparse.Namespace) -> int:
    """E2. Send a COMPOSED payload to the user's own provider.

    NPDev ships no key and no default endpoint, so an unconfigured install cannot silently send
    anything anywhere. Two provider shapes, both entirely the user's:

      command  their own CLI, invoked with the payload's PATH substituted into the argv template.
      http     an endpoint they named, with a key they typed.

    The response is parsed leniently -- a provider that returns prose with a fenced JSON block is
    the common case -- and then VALIDATED against the pinned engine schema before being returned, so
    a plausible-looking hallucination is rejected here rather than at Play time.
    """
    import npdev_explore

    payload_path = Path(args.payload_file).expanduser()
    if not payload_path.is_file():
        raise CliError(f"no such payload file: {payload_path}")
    payload = read_json(payload_path)

    if args.provider == "command":
        if not args.command:
            raise CliError(
                "provider=command needs --command <argv part> (repeatable). Nothing is assumed: "
                "NPDev does not know which assistant you use and will not guess."
            )
        argv = [part.replace("{payload_file}", str(payload_path)) for part in args.command]
        completed = subprocess.run(argv, capture_output=True, text=True, timeout=args.timeout)
        if completed.returncode != 0:
            raise CliError(f"the assistant command exited {completed.returncode}: "
                           f"{(completed.stderr or completed.stdout).strip()[:600]}")
        raw = completed.stdout
    else:
        if not args.endpoint:
            raise CliError("provider=http needs --endpoint")
        import urllib.request
        body = json.dumps({
            "model": args.model,
            "prompt": payload.get("prompt"),
            "payload": payload,
        }).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        # NPDEV_AI_API_KEY is the preferred channel, and the same idiom this CLI already uses for
        # SCRAPFORAI_API_KEY. A key passed as `--api-key <value>` sits in the process's command line,
        # where any other process on the machine can read it out of the process listing for as long
        # as this one runs -- which is why the Manager stopped sending it that way. The flag still
        # works for direct CLI use and still wins when both are set.
        api_key = args.api_key or os.environ.get("NPDEV_AI_API_KEY")
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        request = urllib.request.Request(args.endpoint, data=body, method="POST", headers=headers)
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            raw = response.read().decode("utf-8", "replace")

    routine = _extract_json_object(raw)
    result = {
        "schemaVersion": "npdev-ai-generate-routine.v1",
        "command": "ai generate-routine",
        "ok": routine is not None,
        "provider": args.provider,
        "routine": routine,
        "raw": raw[:20000],
    }
    if routine is not None:
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as handle:
            json.dump(routine, handle)
            temp_name = handle.name
        try:
            validation = npdev_explore.validate_routine(repo_root(), Path(temp_name))
        finally:
            with contextlib.suppress(OSError):
                os.unlink(temp_name)
        result["validation"] = validation
        result["ok"] = bool(validation.get("valid"))
    else:
        result["error"] = {"message": "the assistant's answer contained no JSON object"}

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        print("VALID routine returned" if result["ok"] else "the assistant did not return a valid routine")
    return 0 if result["ok"] else 2


def _extract_json_object(text: str) -> dict | None:
    """The first balanced JSON object in a provider's answer.

    Providers wrap JSON in prose and fences far more often than not, so "parse the whole thing"
    fails on the common case. Brace-balanced scanning rather than a regex, because a routine
    contains nested objects and a non-greedy regex truncates at the first inner `}`.
    """
    text = text.strip()
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        pass
    depth = 0
    start = -1
    in_string = False
    escaped = False
    for index, char in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            if depth == 0:
                start = index
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0 and start >= 0:
                with contextlib.suppress(json.JSONDecodeError):
                    candidate = json.loads(text[start:index + 1])
                    if isinstance(candidate, dict) and "steps" in candidate:
                        return candidate
                start = -1
    return None


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
    init_parser.add_argument("--externally-provisioned", action="store_true",
                             help="This database server is YOURS, not NPDev's -- NPDev did not start "
                                  "it and must never start, stop or reset it. Use it when pointing "
                                  "at a server you already run. Without this flag the app's toolbox "
                                  "assumes the server is NPDev's own, and `Reset` DELETES the data "
                                  "root. Server engines only. (STOR-14/STOR-15)")

    engines_parser = subparsers.add_parser(
        "engines", help="List the database engines an app can use, what each needs, and which are "
                        "still experimental."
    )
    engines_parser.add_argument(
        "--json", action="store_true",
        help="Emit npdev-engine-list.v1 instead of the human table. This is what the Manager's "
             "engine picker is built from -- a hardcoded copy there would be free to drift.",
    )

    # M13: the same database checks `doctor --app` runs, but against a connection that has not been
    # written anywhere yet -- the state the user is in while typing it into a form.
    db_parser = subparsers.add_parser(
        "db", help="Work with an app's database before (and after) the app exists."
    )
    db_sub = db_parser.add_subparsers(dest="db_command")
    db_test = db_sub.add_parser(
        "test-connection",
        help="Check a database connection is reachable, authenticates, can create tables, and will "
             "not mangle unicode -- WITHOUT needing an app to exist yet.",
    )
    db_test.add_argument("--engine", required=True, help="Engine key: " + ", ".join(npdev_engines.engine_keys()))
    db_test.add_argument("--db-host", default=None, help="Defaults to localhost.")
    db_test.add_argument("--db-port", type=int, default=None, help="Defaults to the engine's port.")
    db_test.add_argument("--db-user", default=None, help="Defaults to the engine's usual admin user.")
    db_test.add_argument("--db-password", default=None)
    db_test.add_argument(
        "--db-name", default=None,
        help="The database to look for. Omit it and the check reports whether the SERVER is usable "
             "rather than whether one particular database exists -- which is the right question "
             "before `npdev init` has chosen a name.",
    )
    db_test.add_argument(
        "--json", action="store_true",
        help="Emit an npdev-cli-result.v1 object whose `checks` are the SAME records, with the same "
             "ids, that `doctor --json` emits for a database. The Manager renders both with one "
             "renderer for that reason.",
    )

    # M14: the five environment operations. Each RUNS the generated `_ops` script of the same name
    # rather than reimplementing it -- see _DB_OPERATIONS.
    for _op_name, (_script, _help) in _DB_OPERATIONS.items():
        _op_parser = db_sub.add_parser(_op_name, help=_help + f"  (runs _ops/{_script})")
        _op_parser.add_argument(
            "--app", default=None, metavar="DIR",
            help="The app directory. Defaults to the current directory.",
        )
        _op_parser.add_argument(
            "--json", action="store_true",
            help="Emit an npdev-cli-result.v1 object wrapping the script's own output verbatim.",
        )
        if _op_name == "reset":
            _op_parser.add_argument(
                "--confirm", default=None, metavar="TOKEN",
                help=f"Must be exactly {_DB_RESET_CONFIRMATION}. Reset DELETES this app's data; the "
                     f"token exists so it cannot happen by accident, from a terminal or a button.",
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

    migrate_rename = migrate_sub.add_parser(
        "rename", help="Declare a field rename (stamps renamedFrom) for a hand-edited model.json."
    )
    migrate_rename.add_argument("--model", required=True, help="path to the model.json to edit")
    migrate_rename.add_argument(
        "field", metavar="<Concept>.<oldField>",
        help="the field to rename, e.g. Order.customerName",
    )
    migrate_rename.add_argument("new_name", metavar="<newField>", help="the new field name")
    migrate_rename.add_argument(
        "--write", action="store_true",
        help="apply the edit in place (and schema-validate); without this flag, reports what would "
             "change and exits",
    )
    migrate_rename.add_argument(
        "--cascade", action="store_true",
        help="XREF-3: also rewrite every reference to the field -- panel columns and field "
             "bindings, query orderBy/where, procedure steps, predicates -- at the exact "
             "structural path the reference index reports, never by string replacement. Refuses "
             "and changes nothing if any reference cannot be followed (an undecidable expression, "
             "a hash-pinned trusted-source asset, a pack- or context-contributed member), and "
             "re-indexes the result before writing so a partial rewrite fails closed.",
    )

    # STOR-16: the codemod the RecreateOnAppStart deprecation warning names.
    migrate_db_lifecycle = migrate_sub.add_parser(
        "db-lifecycle",
        help="Rewrite the deprecated schemaLifecycle.strategy=RecreateOnAppStart to Ephemeral.",
    )
    migrate_db_lifecycle.add_argument(
        "--input", required=True, nargs="+",
        help="db.definition.json files, or directories to search recursively.",
    )
    migrate_db_lifecycle.add_argument(
        "--write", action="store_true",
        help="apply the edits; without this flag, reports what would change and exits",
    )

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

    # PK-3: transitive pack dependency resolution -- add/update both resolve the live pack graph
    # and (re)write npdev.lock (same operation, two names for UX clarity); list reads the
    # committed lock (or a live dry-run if none exists); why explains a version selection.
    # PK-4 Stage A/B (same subparser group -- one `pack` command, not two): pack.json diff
    # classification + publish gate (com.npdev.dsl.v1.pack.PackDiffEngine / PackPublishGate).
    # Different mechanism from `migration diff` below: that one classifies a model.json schema
    # change for the DB migration planner (MigrationPlanEmitter); this one classifies a pack.json
    # content change for PACK VERSIONING, no database involved either way.
    pack = subparsers.add_parser(
        "pack", help="Resolve/lock/inspect a model's transitive pack graph, or diff/publish a pack.json."
    )
    pack_sub = pack.add_subparsers(dest="pack_command")
    pack_add = pack_sub.add_parser("add", help="Resolve the pack graph and write npdev.lock.")
    pack_add.add_argument("--model", required=True, help="path to the model.json to resolve")
    # R8.4: resolve a pack by NAME against the catalog instead of hand-writing a 'from' coordinate.
    pack_add.add_argument(
        "--from-catalog", dest="from_catalog", default=None, metavar="<packId>",
        help="resolve <packId> against the pack catalog (see `npdev pack search`) and add its "
             "'from' coordinate to --model's own packs[] BEFORE resolving -- idempotent if already "
             "declared. The catalog lookup itself honors --catalog-url/--offline below; the "
             "resolve-and-lock step that follows always needs the network for a genuinely new pack "
             "regardless of --offline.")
    pack_add.add_argument("--catalog-url", dest="catalog_url", default=None,
                          help=f"override the catalog URL used by --from-catalog (default: "
                               f"${ENV_PACK_CATALOG_URL}, else {DEFAULT_PACK_CATALOG_URL})")
    pack_add.add_argument("--offline", action="store_true",
                          help="--from-catalog's own lookup only: never touch the network, use the "
                               "cached catalog (refuses if none is cached). Has no effect without "
                               "--from-catalog.")
    pack_update = pack_sub.add_parser("update", help="Re-resolve the pack graph and rewrite npdev.lock.")
    pack_update.add_argument("--model", required=True, help="path to the model.json to resolve")
    pack_list = pack_sub.add_parser("list", help="Print the current npdev.lock (or a live dry-run).")
    pack_list.add_argument("--model", required=True, help="path to the model.json to resolve")
    pack_why = pack_sub.add_parser("why", help="Explain why a pack resolved to its current version.")
    pack_why.add_argument("--model", required=True, help="path to the model.json to resolve")
    pack_why.add_argument("pack_id", metavar="<packId>", help="the pack id to explain")

    # R8.4: discovery -- the ecosystem's missing front door. `search` reads a generated
    # catalog-index.json (cached, offline-tolerant, never silently empty on a real fetch failure --
    # see the module doc above `run_pack_search`); `build-catalog` produces that file from a local
    # checkout of an NPR-shaped pack repo.
    pack_search = pack_sub.add_parser(
        "search", help="Search the NPR pack catalog by name/description/category (cached; offline-tolerant)."
    )
    pack_search.add_argument("query", nargs="?", default="",
                             help="substring to match against pack id/description/category "
                                  "(case-insensitive; default: list the whole catalog)")
    pack_search.add_argument("--catalog-url", dest="catalog_url", default=None,
                             help=f"override the catalog URL (default: ${ENV_PACK_CATALOG_URL}, "
                                  f"else {DEFAULT_PACK_CATALOG_URL})")
    pack_search.add_argument("--offline", action="store_true",
                             help="never touch the network -- use the cached catalog only, or "
                                  "refuse (never an empty result) if nothing is cached yet")
    pack_search.add_argument("--json", action="store_true")

    pack_build_catalog = pack_sub.add_parser(
        "build-catalog",
        help="Generate catalog-index.json from a local checkout of an NPR-shaped pack repo "
             "(packs/<name>/pack.json per pack). Writes a local file only -- publishing it is a "
             "separate, manual step today (or a future R8.5 `pack publish --push`).",
    )
    pack_build_catalog.add_argument("--repo-dir", dest="repo_dir", required=True,
                                    help="local checkout containing packs/<name>/pack.json")
    pack_build_catalog.add_argument("--repository-url", dest="repository_url",
                                    default="https://github.com/MarceloGiazzon/NPR",
                                    help="public repo URL baked into every entry's 'from' "
                                         "coordinate (default: the NPR pack repo)")
    pack_build_catalog.add_argument("--tag-template", dest="tag_template", default="v{version}",
                                    help="how each pack's release tag is named, templated with "
                                         "{pack}/{version} (default: v{version}, matching NPR's "
                                         "current single repo-wide release-tag convention)")
    pack_build_catalog.add_argument("--out", default="",
                                    help="where to write catalog-index.json (default: "
                                         "<repo-dir>/catalog-index.json)")

    # R8.2: multi-member "export a working concept into a reusable pack" verb, replacing the
    # external one-concept PowerShell script (NPDevSamples/scripts/packs/export-concept-to-pack.ps1)
    # as the real path -- see run_pack_export's own docstring for the reference-rewriting rules.
    pack_export = pack_sub.add_parser(
        "export", help="Export one or more concepts from a model/pack into a new, reusable pack.json."
    )
    pack_export.add_argument("--model", required=True,
                              help="path to the model.json (or pack.json) to export concepts from")
    pack_export.add_argument("--concepts", required=True,
                              help="comma-separated concept names to export together, e.g. Order,OrderLine")
    pack_export.add_argument("--pack", required=True, help="new pack identifier (pack.schema.json's pattern)")
    pack_export.add_argument("--author", required=True, help="attribution for who published this pack")
    pack_export.add_argument("--category", default="other", help="pack.schema.json category (default: other)")
    pack_export.add_argument("--description", default="", help="pack description (default: auto-generated)")
    # dest is deliberately NOT "version" -- the top-level parser already owns a global --version
    # (action="store_true", dest="version") and `main()` checks `if args.version:` before any
    # command dispatch; a same-named subparser dest here would silently overwrite it in the shared
    # Namespace and short-circuit every `pack export` call into the version banner (caught live).
    pack_export.add_argument("--version", dest="pack_version", default="1.0.0",
                              help="initial pack version (default: 1.0.0)")
    pack_export.add_argument("--namespace", default="", help="optional Java/package namespace")
    pack_export.add_argument("--out-dir", dest="out_dir", default="",
                              help="directory to write <pack>/pack.json under "
                                   "(default: NPDevContract/packs, the platform's own pack corpus)")
    pack_export.add_argument("--forked-from-pack", dest="forked_from_pack", default="",
                              help="attribution: pack this was forked from")
    pack_export.add_argument("--forked-from-version", dest="forked_from_version", default="",
                              help="attribution: version this was forked from")
    pack_export.add_argument("--forked-from-author", dest="forked_from_author", default="",
                              help="attribution: original author of the forked-from pack")
    pack_export.add_argument("--allow-unresolved-refs", dest="allow_unresolved_refs", action="store_true",
                              help="export even if a reference targets a concept outside the exported "
                                   "set and outside any known sibling pack; the reference is left as-is "
                                   "and recorded in the pack's own metadata.unresolvedReferences instead "
                                   "of refusing the export")

    pack_diff = pack_sub.add_parser(
        "diff", help="Classify every difference between two pack.json documents as ADDITIVE/BREAKING/PATCH."
    )
    pack_diff.add_argument("old_pack", metavar="<oldPack.json>")
    pack_diff.add_argument("new_pack", metavar="<newPack.json>")
    pack_diff.add_argument("--out", help="also write the JSON report to this path")

    pack_publish = pack_sub.add_parser(
        "publish", help="Refuse a pack.json publish whose version bump is smaller than the diff requires."
    )
    pack_publish.add_argument("old_pack", metavar="<oldPack.json>")
    pack_publish.add_argument("new_pack", metavar="<newPack.json>")
    pack_publish.add_argument("--out", help="also write the JSON report to this path")
    pack_publish.add_argument(
        "--write", action="store_true",
        help="apply the change: write an empty migrations chain entry into <newPack.json> when the "
             "publish is allowed and non-breaking; without this flag, only reports what would happen",
    )

    # R1.5 (roadmap 2026-08-18 R1.5): "npdev init" scaffolds a whole app; before this, growing one
    # meant hand-editing model.json against the 4x-mirrored schema with no help until `npdev
    # validate model` failed. `add` writes ONE schema-valid member into the correct top-level
    # array, reusing the kind -> array-key agreement ModelSourceResolver.MODEL_ARRAY_KEYS already
    # keys 18 ways (ADD_MEMBER_ARRAY_KEYS mirrors the 4 this verb supports).
    add = subparsers.add_parser(
        "add", help="Scaffold one schema-valid concept/panel/flow/procedure into an existing model.json."
    )
    add_sub = add.add_subparsers(dest="add_command")

    add_concept = add_sub.add_parser(
        "concept",
        help="Add a concept with a minimal valid field set (an id field + one more), or --from an exemplar.",
    )
    add_concept.add_argument("name", help="New concept name. Refused if one already exists (by this name).")
    add_concept.add_argument("--model", required=True, help="path to the model.json to add into")
    add_concept.add_argument(
        "--from", dest="from_exemplar", default=None, metavar="SAMPLE[::MEMBER]",
        help="Copy a real concept out of NPDevSamples/<SAMPLE> (Input/model.json, or model.json for "
             "npdev-init-seed's own flat layout -- the same lookup `npdev init --from` uses) instead "
             "of writing a blank stub, renamed to NAME. Omit ::MEMBER to take the sample's first "
             "concept. Refused if the exemplar references anything not already in --model.",
    )

    add_panel = add_sub.add_parser(
        "panel", help="Add a panel with a concept-bound dataSource, or --from an exemplar."
    )
    add_panel.add_argument("name", help="New panel name. Refused if one already exists (by this name).")
    add_panel.add_argument("--model", required=True, help="path to the model.json to add into")
    add_panel.add_argument(
        "--concept", default=None,
        help="Concept this panel's dataSource binds to -- must already exist in --model. Required "
             "unless --from supplies its own concept-bound content.",
    )
    add_panel.add_argument(
        "--from", dest="from_exemplar", default=None, metavar="SAMPLE[::MEMBER]",
        help="Copy a real panel out of NPDevSamples/<SAMPLE> instead of writing a blank stub, "
             "renamed to NAME. Omit ::MEMBER to take the sample's first panel.",
    )

    add_flow = add_sub.add_parser(
        "flow", help="Add a flow with a createConcept/return skeleton bound to --concept, or --from an exemplar."
    )
    add_flow.add_argument("name", help="New flow name. Refused if one already exists (by this name).")
    add_flow.add_argument("--model", required=True, help="path to the model.json to add into")
    add_flow.add_argument(
        "--concept", default=None,
        help="Concept this flow operates on -- must already exist in --model (JsonModelParser "
             "refuses any flow with neither flow.concept nor flow.input.concept). Required unless "
             "--from supplies its own concept binding.",
    )
    add_flow.add_argument(
        "--from", dest="from_exemplar", default=None, metavar="SAMPLE[::MEMBER]",
        help="Copy a real flow out of NPDevSamples/<SAMPLE> instead of writing a blank stub, "
             "renamed to NAME. Omit ::MEMBER to take the sample's first flow.",
    )

    add_procedure = add_sub.add_parser(
        "procedure",
        help="Add a procedure with a readConcept/return skeleton bound to --concept, or --from an exemplar.",
    )
    add_procedure.add_argument("name", help="New procedure name. Refused if one already exists (by this name).")
    add_procedure.add_argument("--model", required=True, help="path to the model.json to add into")
    add_procedure.add_argument(
        "--concept", default=None,
        help="Concept this procedure reads -- must already exist in --model. Required unless --from "
             "supplies its own concept-bound steps.",
    )
    add_procedure.add_argument(
        "--from", dest="from_exemplar", default=None, metavar="SAMPLE[::MEMBER]",
        help="Copy a real procedure out of NPDevSamples/<SAMPLE> instead of writing a blank stub, "
             "renamed to NAME. Omit ::MEMBER to take the sample's first procedure.",
    )

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
    inspect_bonds_parser.add_argument(
        "--diagram",
        help="Also render the same bonds as a self-contained ER-diagram HTML page at this path.",
    )

    inspect_app_parser = inspect_sub.add_parser("app")
    inspect_app_parser.add_argument("--model", required=True)
    inspect_app_parser.add_argument("--output")

    # XREF-2: the "what would I break?" surface. Reads the Java-emitted reference index
    # (:NPDevContract:dsl:modelXref), never a second Python walk of the model.
    inspect_usage_parser = inspect_sub.add_parser(
        "usage",
        help="Show every place a field/concept/procedure is referenced, or list unresolved references.",
    )
    inspect_usage_parser.add_argument("--model", required=True)
    inspect_usage_parser.add_argument(
        "--of",
        help="What to look up: WidgetOrder.lineCount, WidgetOrder, or kind:Name "
             "(procedure:EnrichRows, query:AllOrders, flow:PlaceOrder, ...).",
    )
    inspect_usage_parser.add_argument(
        "--orphans",
        action="store_true",
        help="List only references that do not resolve. Exits 2 if any is UNRESOLVED, so this "
             "works as a pre-commit hook on its own; UNDECIDABLE entries are listed but never "
             "fail, because 'we could not check this' is not the same claim as 'this is wrong'.",
    )
    inspect_usage_parser.add_argument(
        "--diagram",
        help="Also render the selected usages as a self-contained HTML page at this path.",
    )
    inspect_usage_parser.add_argument("--output")

    # R1.6 (roadmap 2026-08-18 R1.6): "what breaks if I change this?" used to take four separate
    # invocations (migration diff, inspect usage, author diff-gate, pack diff) -- composed here into
    # ONE typed report. See run_impact's own docstring for exactly what each leg reuses.
    impact = subparsers.add_parser(
        "impact",
        help="One change-preview report for a baseline/current pair: migration classification + "
             "xref usage + the AI Authoring Contract diff-gate (model.json pair), or pack diff "
             "(pack.json pair). No generate/build/boot -- see `npdev loop run` for the heavy pipeline.",
    )
    impact.add_argument("--baseline", required=True, help="Previous model.json or pack.json.")
    impact.add_argument("--current", required=True, help="Current (candidate) model.json or pack.json.")
    impact.add_argument(
        "--of",
        help="Model.json pairs only: narrow xref usage to WidgetOrder.lineCount / WidgetOrder / "
             "kind:Name, same grammar as `inspect usage --of`.",
    )
    impact.add_argument(
        "--manifest",
        help="Model.json pairs only: a npdev-authoring-submission.v1 manifest, for a real AI "
             "Authoring Contract compliance check. Omitting it still runs the authoring-gate leg, "
             "which then reports its own manifest-missing refusal (same as `author diff-gate`).",
    )
    impact.add_argument("--output")
    impact.add_argument("--timeout", type=float, default=300.0,
                         help="Overall budget in seconds for the Gradle-backed legs (default 300).")

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
    generate_app.add_argument(
        "--web-assets",
        help="R10 (EXT-1, 'custom-screen mount'): optional directory of hand-written screens "
             "(HTML/CSS/JS) to mount into the generated app's src/main/resources/static, served "
             "same-origin with no CORS. Same mechanism Build-NpdevApp.ps1's apps/<App>/web "
             "convention uses (FinalAppAssembler.mountWebAssets via --webAssetsRoot) -- omit for "
             "the previous, unchanged behavior.",
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

    # R3.4. No --scenarios, no --routines, no plan file: every input is discovered from the app the
    # --app-dir names. An option here that a user HAD to set per app would be the per-app config this
    # item exists to remove.
    test = subparsers.add_parser(
        "test",
        help="Run all three layers against ONE booted app -- model-derived REST smoke over every "
             "concept endpoint, *.scenario.json acceptance, and the browser routine suite -- and "
             "write one report. Exits nonzero if any layer is red.",
    )
    test.add_argument("--app-dir", required=True, help="A generated, RUNNING app (the one `npdev monitor probe` sees).")
    test.add_argument("--report-out", default=None,
                      help=f"Where to write the report. Default: <app-dir>/_ops/{TEST_REPORT_FILENAME}.")
    test.add_argument("--engine-port", type=int, default=npdev_monitor.DEFAULT_ENGINE_PORT,
                      help="Browser layer: the ScrapForAI engine's port.")
    test.add_argument("--engine-root", default=None, help="Browser layer: where the engine is installed.")
    test.add_argument("--engine-api-key", default=None, help="Browser layer: the ENGINE's key, not the app's.")
    test.add_argument("--json", action="store_true")

    # R3.2: no new server-side surface -- a thin wrapper around DataSeedAdminController's existing
    # GET /api/admin/seeds and POST /api/admin/seeds/<id>/run, same --app-dir + probe_app shape
    # `test` uses above.
    seed = subparsers.add_parser(
        "seed",
        help="List or run an app's declared seed/mock-data sets via its admin seed endpoints "
             "(GET/POST /api/admin/seeds).",
    )
    seed_sub = seed.add_subparsers(dest="seed_command")

    seed_list = seed_sub.add_parser("list", help="List the seeds this app declares.")
    seed_list.add_argument("--app-dir", required=True, help="A generated, RUNNING app (the one `npdev monitor probe` sees).")
    seed_list.add_argument("--json", action="store_true")

    seed_run = seed_sub.add_parser("run", help="Run one declared seed.")
    seed_run.add_argument("--app-dir", required=True, help="A generated, RUNNING app (the one `npdev monitor probe` sees).")
    seed_run.add_argument("--id", required=True, help="The seed's id, as shown by `npdev seed list`.")
    seed_run.add_argument("--tenant-id", default=None,
                          help="SUPERUSER only: target a specific tenant's seed run instead of the caller's own.")
    seed_run.add_argument("--timeout", type=float, default=120.0,
                          help="HTTP timeout in seconds -- a large seed (thousands of records) can take a while.")
    seed_run.add_argument("--json", action="store_true")

    # R3.7. No plan file, same as `test`/`seed`: every endpoint is discovered from the app's own
    # info.json + generated-ui-manifest.json.
    bench = subparsers.add_parser(
        "bench",
        help="Probe a RUNNING app's concept-list and panel/query endpoints with repeated samples, "
             "report p50/p95/mean/stdev per endpoint, and flag a regression against a saved per-app "
             "baseline. Relative threshold, not an absolute ms budget -- an absolute one flaked "
             "under ordinary machine load (RUN-16).",
    )
    bench.add_argument("--app-dir", required=True, help="A generated, RUNNING app (the one `npdev monitor probe` sees).")
    bench.add_argument("--concept", action="append", default=[],
                       help="Limit concept-list checks to this concept (repeatable). Default: every "
                            "concept the app publishes.")
    bench.add_argument("--panel", action="append", default=[],
                       help="Limit panel checks to this panel (repeatable). Default: every panel the "
                            "app declares.")
    bench.add_argument("--samples", type=int, default=DEFAULT_BENCH_SAMPLES,
                       help=f"GET requests per endpoint (default {DEFAULT_BENCH_SAMPLES}, matching "
                            "the scale-proof ladder's own latency phase). More samples narrow the "
                            "p95 estimate at the cost of run time.")
    bench.add_argument("--timeout", type=float, default=30.0, help="Per-request timeout in seconds.")
    bench.add_argument("--regression-threshold", type=float, default=DEFAULT_BENCH_REGRESSION_THRESHOLD,
                       dest="regression_threshold",
                       help="Flag a regression when the new p50 is at least this many times the "
                            f"saved baseline p50 (default {DEFAULT_BENCH_REGRESSION_THRESHOLD}x -- "
                            "relative, not an absolute ms budget; a fixed millisecond ceiling flaked "
                            "under ordinary multi-agent machine load, see ledger RUN-16).")
    bench.add_argument("--baseline-path", default=None,
                       help=f"Default: <app-dir>/_ops/{BENCH_BASELINE_FILENAME}.")
    bench.add_argument("--update-baseline", action="store_true",
                       help="Promote this run's measurements to the saved baseline. The FIRST run "
                            "against an app always establishes the baseline; after that, it is only "
                            "overwritten explicitly, the same promotion discipline `explore accept` "
                            "uses for one screenshot.")
    bench.add_argument("--report-out", default=None,
                       help=f"Where to write the report. Default: <app-dir>/_ops/{BENCH_REPORT_FILENAME}.")
    bench.add_argument("--json", action="store_true")

    # MON-22 follow-up: R9.6 (OperationalRunbookEmitter) writes Install-Service.ps1/
    # Uninstall-Service.ps1 and install-service.sh/uninstall-service.sh into every generated app's
    # _ops, but nothing in the CLI could reach them -- this is that thin wrapper. It locates the
    # platform-appropriate script the same way `npdev bench` locates the app (npdev_monitor.probe_app)
    # and never reimplements what the scripts do.
    service = subparsers.add_parser(
        "service",
        help="Install/uninstall OS-level supervision for a generated app (wraps the R9.6-emitted "
             "Install-Service.ps1/install-service.sh) -- restart-on-crash, start-at-boot.",
    )
    service_sub = service.add_subparsers(dest="service_command")

    service_install = service_sub.add_parser(
        "install",
        help="Register OS-level supervision around this app's own launcher (Windows: a Scheduled "
             "Task; Linux: a real systemd unit). PRIVILEGED and hard to reverse -- ALWAYS "
             "--dry-run first.",
    )
    service_install.add_argument("--app-dir", required=True,
                                 help="a generated NPDev app (the one `npdev monitor probe` sees)")
    service_install.add_argument(
        "--dry-run", action="store_true",
        help="RECOMMENDED FIRST STEP, DO THIS BEFORE A REAL INSTALL: preview the exact "
             "registration with zero side effects and no elevation needed. Without this flag, "
             "install needs an elevated shell (Windows) or root (Linux/systemd) and actually "
             "registers the supervisor.")
    service_install.add_argument("--start", action="store_true",
                                 help="also start the app/task immediately after a REAL "
                                      "(non-dry-run) install")
    service_install.add_argument("--profile", default=None,
                                 help="Spring profile to pass through -- install-service.sh "
                                      "(systemd) ONLY; Windows Install-Service.ps1 has no "
                                      "--profile and this is refused on Windows")
    service_install.add_argument("--json", action="store_true")

    service_uninstall = service_sub.add_parser(
        "uninstall",
        help="Remove the OS-level supervision `service install` added for this app. Idempotent -- "
             "a no-op if nothing is installed.",
    )
    service_uninstall.add_argument("--app-dir", required=True)
    service_uninstall.add_argument(
        "--dry-run", action="store_true",
        help="Preview only, execute nothing. The underlying Uninstall-Service.ps1/"
             "uninstall-service.sh has no native dry-run mode -- both are already an idempotent "
             "no-op when nothing is installed -- so this prints the command that WOULD run instead "
             "of running it.")
    service_uninstall.add_argument("--json", action="store_true")

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
    # S3. T3 already evaluates evidence; this is the form that PRODUCES the handover.
    verify.add_argument("--release-candidate", action="store_true",
                         help="T3 only: run the seven-step release-candidate gate and emit "
                              "STABILITY_MANIFEST.json. Stops at the first failure. Step 4 resolves "
                              "real CI runs AT THIS SHA and records their ids -- local greens are "
                              "inputs, CI run ids are the evidence.")
    verify.add_argument("--tag", default=None,
                         help="The tag this manifest describes. Required with --release-candidate: a "
                              "manifest about a branch describes nothing, because a branch moves.")
    verify.add_argument("--manifest-out", default=None,
                         help="Where to write the manifest (default: STABILITY_MANIFEST.json at the "
                              "repo root).")
    verify.add_argument("--launched", action="append", default=None, metavar="ASSET",
                         help="Name an artifact you have actually STARTED, not merely downloaded. "
                              "Repeatable. Nothing can infer this, so it defaults to false and the "
                              "manifest says so -- an AppImage that has never been launched is a "
                              "download, not an installer.")

    # ------------------------------------------------------------------------------------------
    # MONITOR_PLAN A2/D9/D10: `npdev monitor`. The Monitor screen is a WINDOW onto these verbs --
    # D1's rule is that every Monitor capability is a CLI verb with --json first, so a terminal user
    # gets it too and the Manager's fixtures can be CAPTURED rather than guessed.
    # ------------------------------------------------------------------------------------------
    monitor = subparsers.add_parser(
        "monitor", help="Discover, probe and read the logs of generated apps on this machine."
    )
    monitor_sub = monitor.add_subparsers(dest="monitor_command")

    monitor_scan = monitor_sub.add_parser(
        "scan", help="Find every generated app under one or more paths (read-only)."
    )
    monitor_scan.add_argument("--paths", required=True,
                              help="One or more directories to search, separated by ';' (or ':' on POSIX).")
    monitor_scan.add_argument("--depth", type=int, default=4,
                              help="How deep to descend before giving up on a branch (default 4).")
    monitor_scan.add_argument("--include-info", action="store_true",
                              help="Also inline each app's generated info.json (the inspector's data).")
    monitor_scan.add_argument("--health-timeout", type=float, default=1.0)
    monitor_scan.add_argument("--json", action="store_true")

    monitor_probe = monitor_sub.add_parser(
        "probe", help="Probe ONE app -- the Monitor's refresh unit."
    )
    monitor_probe.add_argument("--app-dir", required=True)
    monitor_probe.add_argument("--include-info", action="store_true")
    monitor_probe.add_argument("--health-timeout", type=float, default=1.0)
    monitor_probe.add_argument("--json", action="store_true")

    monitor_engine = monitor_sub.add_parser(
        "engine",
        help="Find the ScrapForAI engine: running service first, then a declared root, then derived "
             "candidates. Never a path literal (D9).",
    )
    monitor_engine.add_argument("--port", type=int, default=npdev_monitor.DEFAULT_ENGINE_PORT)
    monitor_engine.add_argument("--root", default=None,
                                help="A declared root (what manager.json holds, if anything). "
                                     "Optional BY DESIGN: detection must work with nothing.")
    monitor_engine.add_argument("--json", action="store_true")

    monitor_engine_start = monitor_sub.add_parser(
        "engine-start",
        help="Start a found-but-stopped ScrapForAI engine and stay as its parent, so it dies with "
             "whoever started it (R2). Streams JSON Lines: starting -> ready -> stopped.",
    )
    monitor_engine_start.add_argument("--root", required=True,
                                      help="An engine root ALREADY FOUND by `npdev monitor engine`. "
                                           "Verified by contents here too -- a caller that passes a "
                                           "plausible directory gets a refusal, not a spawn.")
    monitor_engine_start.add_argument("--port", type=int, default=npdev_monitor.DEFAULT_ENGINE_PORT)
    monitor_engine_start.add_argument("--allow-origin", action="append", default=[],
                                      help="Repeatable. R4: the SSRF allowlist is composed HERE, "
                                           "never by a UI -- two apps explored in one engine session "
                                           "need the union, and a caller that sends one origin "
                                           "silently breaks the other.")
    monitor_engine_start.add_argument("--api-key", default=None)
    monitor_engine_start.add_argument("--json", action="store_true")

    monitor_logs = monitor_sub.add_parser(
        "logs", help="Read (or export) an app's logs: its own runtime output, its ops runs, the Manager's."
    )
    # `export` as an optional positional keeps the plan's own spelling (`npdev monitor logs export`)
    # working while `npdev monitor logs` stays the viewer.
    monitor_logs.add_argument("action", nargs="?", choices=["export"], default=None)
    monitor_logs.add_argument("--app-dir", required=True)
    monitor_logs.add_argument("--source", default="all", choices=["app", "ops", "manager", "all"])
    monitor_logs.add_argument("--tail", type=int, default=200)
    monitor_logs.add_argument("--follow", action="store_true",
                              help="Stream new lines as they are written (Ctrl-C to stop).")
    monitor_logs.add_argument("--out", default=None, help="export only: the .zip to write.")
    monitor_logs.add_argument("--runs", type=int, default=5,
                              help="export only: how many recent exploration runs to include.")
    monitor_logs.add_argument("--json", action="store_true")

    monitor_ops = monitor_sub.add_parser(
        "ops", help="Run one of the app's own generated _ops scripts (the Monitor's run-command strip)."
    )
    monitor_ops.add_argument("--app-dir", required=True)
    monitor_ops.add_argument("--script", required=True, choices=sorted(npdev_monitor.OPS_SCRIPTS),
                             help="Which runbook script. An allowlist, not a filename: the Monitor "
                                  "sends this from a window.")
    monitor_ops.add_argument("--confirm", default=None,
                             help="The acknowledgement token a destructive script demands. The window "
                                  "must be at least as careful as the terminal.")
    monitor_ops.add_argument("--json", action="store_true")

    # ------------------------------------------------------------------------------------------
    # MONITOR_PLAN A4: `npdev explore`. The Scrap Manager screen calls these; so does the
    # PowerShell harness (C1) and the Playwright reporter (C2) -- one verdict, one store, three
    # drivers (R10).
    # ------------------------------------------------------------------------------------------
    explore = subparsers.add_parser(
        "explore", help="Browser explorations: definitions, runs, verdicts, history, retention."
    )
    explore_sub = explore.add_subparsers(dest="explore_command")

    explore_list = explore_sub.add_parser("list", help="Definitions and run history for one app.")
    explore_list.add_argument("--app-dir", required=True)
    explore_list.add_argument("--limit", type=int, default=100)
    explore_list.add_argument("--json", action="store_true")

    explore_show = explore_sub.add_parser("show", help="One full run record, with resolved blob paths.")
    explore_show.add_argument("--app-dir", required=True)
    explore_show.add_argument("--run", required=True)
    explore_show.add_argument("--json", action="store_true")

    explore_validate = explore_sub.add_parser(
        "validate",
        help="Schema-check a routine against the PINNED engine schema, plus the semantic lint. The UI "
             "never validates on its own -- it calls this, so 'valid here' means 'runs in the harness'.",
    )
    explore_validate.add_argument("--file", required=True)
    explore_validate.add_argument("--base-url", default="http://127.0.0.1:8080",
                                  help="Used only to COMPOSE the request the engine would receive.")
    explore_validate.add_argument("--json", action="store_true")

    explore_run = explore_sub.add_parser("run", help="Run a routine against a booted app and record it.")
    explore_run.add_argument("--app-dir", required=True)
    explore_run.add_argument("--file", required=True)
    explore_run.add_argument("--engine-port", type=int, default=npdev_monitor.DEFAULT_ENGINE_PORT)
    explore_run.add_argument("--engine-root", default=None)
    explore_run.add_argument("--api-key", default=None)
    explore_run.add_argument("--driver", default="cli",
                             choices=["cli", "monitor-ui", "harness", "ai-session", "playwright"])
    explore_run.add_argument("--var", action="append", default=[], metavar="NAME=VALUE",
                             help="Runtime variable override, repeatable.")
    explore_run.add_argument("--credential", action="append", default=[], metavar="NAME=VALUE",
                             help="Runtime credential override, repeatable (R7 Stage D -- e.g. "
                                  "--credential apiKey=<value> for a routine step that reads it via "
                                  "valueFromCredential). Unlike --var, values are redacted from the "
                                  "engine's own evidence output.")
    explore_run.add_argument("--ledger-id", default=None,
                             help="Link this run to a ledger item; a linked run keeps its blobs.")
    explore_run.add_argument("--keep-engine", action="store_true",
                             help="Leave a self-started engine running (R2: it then needs stopping).")
    explore_run.add_argument("--json", action="store_true")

    # R3.1. Same options as `run` minus `--file`, because a suite chooses its own files: it runs
    # every definition `explore list` shows, in that order.
    explore_suite = explore_sub.add_parser(
        "suite",
        help="Run EVERY routine the app declares, in `explore list` order, and roll the verdicts "
             "up. Exits nonzero if any routine is red, refused or skipped.",
    )
    explore_suite.add_argument("--app-dir", required=True)
    explore_suite.add_argument("--only", action="append", default=[], metavar="GLOB",
                               help="Run only definitions whose name matches this fnmatch pattern "
                                    "(e.g. --only 'login-*'), repeatable.")
    explore_suite.add_argument("--stop-on-red", action="store_true",
                               help="Stop at the first red routine. The rest are reported as "
                                    "skipped, never silently dropped.")
    explore_suite.add_argument("--engine-port", type=int, default=npdev_monitor.DEFAULT_ENGINE_PORT)
    explore_suite.add_argument("--engine-root", default=None)
    explore_suite.add_argument("--api-key", default=None)
    explore_suite.add_argument("--driver", default="cli",
                               choices=["cli", "monitor-ui", "harness", "ai-session", "playwright"])
    explore_suite.add_argument("--var", action="append", default=[], metavar="NAME=VALUE",
                               help="Runtime variable override applied to every routine, repeatable.")
    explore_suite.add_argument("--credential", action="append", default=[], metavar="NAME=VALUE",
                               help="Runtime credential override applied to every routine, "
                                    "repeatable. Redacted from the engine's evidence, unlike --var.")
    explore_suite.add_argument("--ledger-id", default=None,
                               help="Link every run in this suite to a ledger item.")
    explore_suite.add_argument("--keep-engine", action="store_true",
                               help="Leave a self-started engine running, so routines 2..N reuse it "
                                    "instead of paying the engine's startup each (R2: it then needs "
                                    "stopping).")
    explore_suite.add_argument("--json", action="store_true")

    explore_preflight = explore_sub.add_parser(
        "preflight", help="Report each precondition as its own row, without running anything (D4)."
    )
    explore_preflight.add_argument("--app-dir", required=True)
    explore_preflight.add_argument("--engine-port", type=int, default=npdev_monitor.DEFAULT_ENGINE_PORT)
    explore_preflight.add_argument("--engine-root", default=None)
    explore_preflight.add_argument("--json", action="store_true")

    explore_record = explore_sub.add_parser(
        "record",
        help="Record a result produced elsewhere (the PowerShell harness, the Playwright reporter) "
             "through the SAME verdict -- so PowerShell does no schema work and no driver keeps its "
             "own copy of the rules.",
    )
    explore_record.add_argument("--from-file", required=True, help="A driver result JSON document.")
    explore_record.add_argument("--app-dir", default=None)
    explore_record.add_argument("--routine-file", default=None)
    explore_record.add_argument("--driver", default="harness",
                                choices=["harness", "monitor-ui", "ai-session", "playwright", "cli"])
    explore_record.add_argument("--scope", default="app", choices=["app", "platform"])
    explore_record.add_argument("--suite", default=None, help="platform scope only.")
    explore_record.add_argument("--definition-kind", default="routine-json",
                                choices=["routine-json", "playwright-spec"])
    explore_record.add_argument("--ledger-id", default=None)
    explore_record.add_argument("--artifact-dir", default=None,
                                help="Where THIS driver wrote its screenshots. Told rather than "
                                     "assumed: the harness uses <build>/scrapforai-artifacts and "
                                     "`explore run` uses the app's own, and a wrong guess silently "
                                     "records a run with no evidence.")
    explore_record.add_argument("--json", action="store_true")

    explore_prune = explore_sub.add_parser(
        "prune", help="Blob retention. Records are NEVER deleted; pinned runs keep their blobs."
    )
    explore_prune.add_argument("--app-dir", required=True)
    explore_prune.add_argument("--keep-per-scenario", type=int, default=10)
    explore_prune.add_argument("--red-days", type=int, default=30)
    explore_prune.add_argument("--dry-run", action="store_true")
    explore_prune.add_argument("--json", action="store_true")

    explore_pin = explore_sub.add_parser("pin", help="Keep a run's evidence indefinitely.")
    explore_pin.add_argument("--app-dir", required=True)
    explore_pin.add_argument("--run", required=True)
    explore_pin.add_argument("--ledger", default=None, metavar="REG-nn")
    explore_pin.add_argument("--unpin", action="store_true")
    explore_pin.add_argument("--json", action="store_true")

    explore_accept = explore_sub.add_parser(
        "accept", help="Accept a run as the baseline (screenshot SHAs + extracted text)."
    )
    explore_accept.add_argument("--app-dir", required=True)
    explore_accept.add_argument("--run", required=True)
    explore_accept.add_argument("--json", action="store_true")

    explore_context = explore_sub.add_parser(
        "context", help="E1: the assistant's context pack -- concepts, routes, schema actions, gotchas, exemplars."
    )
    explore_context.add_argument("--app-dir", required=True)
    explore_context.add_argument("--exemplars", type=int, default=2)
    explore_context.add_argument("--json", action="store_true")

    explore_payload = explore_sub.add_parser(
        "repair-payload",
        help="E3-a: compose the EXACT bytes an assistant request would carry, and send nothing. "
             "Structure-only by default (no page text), credentials redacted, so it can be read "
             "before anyone decides to send it.",
    )
    explore_payload.add_argument("--app-dir", required=True)
    explore_payload.add_argument("--prompt", required=True)
    explore_payload.add_argument("--run", default=None,
                                 help="A red run to repair. Omit to compose a plain authoring request.")
    explore_payload.add_argument("--include-page-text", action="store_true",
                                 help="Opt in, per request, to including page TEXT. Off by default: "
                                      "on a tester's or a customer's machine that text is their real "
                                      "data. Recorded on the run either way.")
    explore_payload.add_argument("--json", action="store_true")

    explore_coverage = explore_sub.add_parser(
        "coverage",
        help="R3.5: per-app table, concept -> referencing routines -> last green run, plus "
             "flow -> referencing acceptance scenarios, with an explicit UNCOVERED section. Static "
             "(no engine, no HTTP call) -- reads info.json, routine/scenario files, and run history.",
    )
    explore_coverage.add_argument("--app-dir", required=True)
    explore_coverage.add_argument("--json", action="store_true")

    explore_generate = explore_sub.add_parser(
        "generate",
        help="R3.3: emit a create/list/edit/delete routine per concept, built from the app's own "
             "generated-ui-manifest.json (the same resolved widget/enum/reference facts its "
             "business UI renders from). Written into _ops/explorations by default, so `explore "
             "suite` picks the result up immediately.",
    )
    explore_generate.add_argument("--app-dir", required=True)
    explore_generate.add_argument("--concept", action="append", default=[],
                                  help="Limit to this concept (repeatable). Default: every concept "
                                       "in the manifest. A concept a selected one depends on via a "
                                       "required reference must be included too, or it is skipped.")
    explore_generate.add_argument("--out-dir", default=None,
                                  help="Default: <app>/_ops/explorations (the mirror `explore suite` "
                                       "already scans first).")
    explore_generate.add_argument("--dry-run", action="store_true",
                                  help="Report what would be written without writing any file.")
    explore_generate.add_argument("--json", action="store_true")

    # ------------------------------------------------------------------------------------------
    # E2: the provider. The CLI owns the CALL as well as the payload, so a terminal user can do
    # exactly what the window does -- and so the window stays a pipe (D1).
    # ------------------------------------------------------------------------------------------
    ai = subparsers.add_parser("ai", help="Assistant-backed authoring (bring your own provider).")
    ai_sub = ai.add_subparsers(dest="ai_command")
    ai_generate = ai_sub.add_parser(
        "generate-routine",
        help="Send a composed payload to YOUR provider and return the routine it proposes. "
             "NPDev ships no API key and no default endpoint.",
    )
    ai_generate.add_argument("--payload-file", required=True,
                             help="The file `npdev explore repair-payload` wrote. A file rather than "
                                  "an argument on purpose: a DOM excerpt on a command line lands in "
                                  "shell history and in every process listing.")
    ai_generate.add_argument("--provider", default="command", choices=["command", "http"])
    ai_generate.add_argument("--command", action="append", default=[],
                             help="provider=command: the argv, repeatable. `{payload_file}` is "
                                  "substituted with the payload's path.")
    ai_generate.add_argument("--endpoint", default=None, help="provider=http: the URL to POST to.")
    ai_generate.add_argument(
        "--api-key", default=None,
        help="provider key for provider=http. Prefer the NPDEV_AI_API_KEY environment variable: a "
             "key on the argv is readable in the machine's process listing while this runs.")
    ai_generate.add_argument("--model", default=None)
    ai_generate.add_argument("--timeout", type=float, default=120.0)
    ai_generate.add_argument("--json", action="store_true")

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
        if args.command == "migrate" and args.migrate_command == "db-lifecycle":
            return run_migrate_db_lifecycle(args)
        if args.command == "migrate" and args.migrate_command == "rename":
            return run_migrate_rename(args)
        if args.command == "migrate" and args.migrate_command == "dsl-2":
            return run_migrate_dsl2(args)
        if args.command == "migrate" and args.migrate_command == "bounded-contexts":
            return run_migrate_bounded_contexts(args)
        if args.command == "migration" and args.migration_command == "diff":
            run_migration_diff(args)
            return 0
        if args.command == "pack" and args.pack_command == "add":
            return run_pack_add(args)
        if args.command == "pack" and args.pack_command == "update":
            return run_pack_update(args)
        if args.command == "pack" and args.pack_command == "list":
            return run_pack_list(args)
        if args.command == "pack" and args.pack_command == "why":
            return run_pack_why(args)
        if args.command == "pack" and args.pack_command == "export":
            return run_pack_export(args)
        if args.command == "pack" and args.pack_command == "diff":
            return run_pack_diff(args)
        if args.command == "pack" and args.pack_command == "publish":
            return run_pack_publish(args)
        if args.command == "pack" and args.pack_command == "search":
            return run_pack_search(args)
        if args.command == "pack" and args.pack_command == "build-catalog":
            return run_pack_build_catalog(args)
        if args.command == "service" and args.service_command == "install":
            return run_service_install(args)
        if args.command == "service" and args.service_command == "uninstall":
            return run_service_uninstall(args)
        if args.command == "add" and args.add_command == "concept":
            return run_add_member(args, "concept")
        if args.command == "add" and args.add_command == "panel":
            return run_add_member(args, "panel")
        if args.command == "add" and args.add_command == "flow":
            return run_add_member(args, "flow")
        if args.command == "add" and args.add_command == "procedure":
            return run_add_member(args, "procedure")
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
        if args.command == "inspect" and args.inspect_command == "usage":
            return inspect_usage(args)
        if args.command == "impact":
            return run_impact(args)
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
        if args.command == "db" and args.db_command == "test-connection":
            return run_db_test_connection(args)
        if args.command == "db" and args.db_command in _DB_OPERATIONS:
            return run_db_operation(args)
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
        if args.command == "test":
            result = run_test(args)
            # R3.4's definition of done: nonzero when any layer is red. Same 0/2 mapping `run
            # app`/`acceptance run`/`explore suite` already use -- 1 stays reserved for a refusal,
            # which run_test raises as a CliError rather than reporting as a result.
            print(json.dumps(result, indent=2, ensure_ascii=False) if args.json
                  else _test_human_summary(result))
            return 0 if result.get("ok") else 2
        if args.command == "seed":
            result = run_seed(args)
            print(json.dumps(result, indent=2, ensure_ascii=False) if args.json
                  else _seed_human_summary(result))
            return 0 if result.get("ok") else 2
        if args.command == "bench":
            result = run_bench(args)
            print(json.dumps(result, indent=2, ensure_ascii=False) if args.json
                  else _bench_human_summary(result))
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
        if args.command == "monitor":
            return run_monitor(args)
        if args.command == "explore":
            return run_explore(args)
        if args.command == "ai" and args.ai_command == "generate-routine":
            return run_ai_generate_routine(args)
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
