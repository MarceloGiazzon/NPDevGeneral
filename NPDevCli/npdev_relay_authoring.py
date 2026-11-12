"""Change/fix half of the browser-AI relay: turn the AI's light manifest into the repo's authoring
submission, run the repo's own authoring diff gate on it, and say the result in plain words.

Where it sits: `npdev ai relay ingest` (npdev_relay_ingest.py) classifies the text of an answer;
the CLI then validates the model (T1.4); for change/fix this module is T1.5; npdev_relay_apply.py
(T1.6) writes the result. Nothing here imports the session store: every function takes plain dicts
(the session as `npdev_relay_session` stores it) and file or directory paths, so it works whatever
holds them. Standard library only; the repo's CLI (npdev_cli) is imported lazily and only to reach
`_run_authoring_gate`, `java_launcher` and `repo_root`.

Public API (all return plain JSON-able dicts):

    translate_manifest(session, base_model, candidate_model, ai_manifest, *, previous_sha256=None)
        -> (submission, candidate_model, findings)
    run_gate(base_model_path, candidate_model_path, submission_path, out_dir, *, archive_dir=None)
        -> {"available", "status", "diagnostics", "report", "archivedPreviousModelTo", "reason"}
    build_preview(gate_report, base_model, candidate_model, ai_manifest, intent) -> preview
    evaluate_change(session, candidate_model_text, ai_manifest, work_dir, *, app_model_path=None)
        -> {"outcome", "preview", "findings", "errors", "candidateModelText", "submission",
            "baseMoved", "next", ...}
    evaluate_create(session, candidate_model_text, ai_manifest) -> the same shape, never a gate
    bump_version("2.9") -> "2.10"; compare_versions(a, b) mirrors AuthoringDiffGate.compareVersions

Outcomes of evaluate_change: READY (every check passed; `preview.warnings` may still hold items the
owner must confirm), NEEDS_FIX (the AI made a mistake it can fix; `errors` has one line per refusal:
`path - message - suggestedFix`), PARSED (NOT the AI's fault: the base moved, or the gate could not
run; a finding says which, `next` carries a message id, never a prompt).

The preview is built for a person who has never seen a model: no JSON, no paths. Every line has a
stable `code` plus `params` (the Manager translates by code; PREVIEW_TEMPLATES holds the English
text), and every warning that needs the owner's explicit yes has `needsConfirmation: true`. The
warning `code` is also what `relay apply --confirm-warning <code>` takes:
`REMOVAL:concept:<Name>`, `REMOVAL:field:<Concept>.<name>`, `RENAME:concept:<from>><to>`,
`RENAME:field:<Concept>.<from>><to>`, `SECURITY:<kind>:<subject>`.

Keep the code free of drive-letter-looking text (the portable-tooling scan fails on it).
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path

SUBMISSION_KIND = "npdev-authoring-submission.v1"
CONFIRMED_RATIONALE = "confirmed by the owner in the NPDev Manager"
SECURITY_CODE = "AUTHORING_UNDECLARED_SECURITY_CHANGE"
SHA_MISMATCH_CODE = "AUTHORING_SHA_MISMATCH"
MODEL_FILE_CANDIDATES = ("model.json", "Input/model.json", "definition/model.json", "input/model.json")
AI_NOTE_LIMIT = 10
AI_NOTE_CHARS = 300
LINES_PER_KIND = 8

# Top-level keys that never count as "the AI changed something".
_IGNORED_TOP_LEVEL = {"version", "$schema", "schemaVersion"}

# Singular / plural words for the plain-language preview. Anything not listed falls back to the key
# split into words.
ITEM_LABELS = {
    "concepts": ("kind of record", "kinds of records"),
    "flows": ("process", "processes"),
    "queries": ("search", "searches"),
    "procedures": ("action", "actions"),
    "panels": ("screen", "screens"),
    "guidePages": ("guide page", "guide pages"),
    "autoPanels": ("generated screen", "generated screens"),
    "documents": ("document", "documents"),
    "roles": ("role", "roles"),
    "events": ("event", "events"),
    "webhooks": ("webhook", "webhooks"),
    "aggregates": ("summary", "summaries"),
    "selectors": ("selector", "selectors"),
    "sequences": ("number sequence", "number sequences"),
    "seeds": ("starting data set", "starting data sets"),
    "domainTypes": ("custom data type", "custom data types"),
    "bindings": ("connection", "connections"),
    "capabilities": ("capability", "capabilities"),
    "conversions": ("conversion", "conversions"),
    "properties": ("setting", "settings"),
    "propertyScopes": ("setting level", "setting levels"),
    "orchestrationRules": ("automation rule", "automation rules"),
    "orchestrations": ("automation", "automations"),
    "ruleProfiles": ("rule profile", "rule profiles"),
    "contexts": ("area", "areas"),
}

# English text for every preview line. The Manager translates by `code`, filling the same `params`.
PREVIEW_TEMPLATES = {
    "CREATE_SUMMARY": "A new app with {counts_text}.",
    "CREATE_CONCEPT": "{label}: {count_text}{fields_part}.",
    "CREATE_NOTHING_DELETED": "Nothing is deleted: this is a new app.",
    "ADD_CONCEPT": "New kind of record: {label}, with {count_text}.",
    "ADD_FIELDS": "{concept}: new {field_word} {fields_text}.",
    "CHANGE_FIELD": "{concept}: the field {field} changes ({what_text}).",
    "CHANGE_CONCEPT": "{concept}: changes to its {what_text}.",
    "ADD_ITEM": "New {kind}: {label}.",
    "CHANGE_ITEM": "Changes to the {kind} {label}.",
    "CHANGE_SETTINGS": "Changes to the app settings ({what_text}).",
    "MORE": "...and {count_text} more.",
    "NO_CHANGES": "The AI sent back the same app: nothing would change.",
    "AI_ASSUMPTION": "The AI assumed: {text}",
    "REMOVAL:concept": "This removes the kind of record {label} and all the data stored in it.",
    "REMOVAL:field": "This removes the field {label} from {concept} and the data stored in it.",
    "RENAME:concept": "{from_label} is renamed to {to_label}. The data is kept.",
    "RENAME:field": "In {concept}, the field {from_label} is renamed to {to_label}. The data is kept.",
    "SECURITY:access.read": "Who can view {concept} changes: from {from_text} to {to_text}.",
    "SECURITY:access.write": "Who can change {concept} changes: from {from_text} to {to_text}.",
    "SECURITY:sensitive": "The field {field} of {concept} is now {to_text}.",
    "SECURITY:invariant": "The rules that keep the data of {concept} valid change.",
    "SECURITY:permissionRequirements": "The permissions needed to use {concept} change: from {from_text} to {to_text}.",
    "AI_SECURITY_NOTE": "The AI says about access: {text}",
    "COULD_NOT_EXPRESS": "The AI could not do this part: {text}",
}


# ----------------------------------------------------------------------------------------------
# Small helpers
# ----------------------------------------------------------------------------------------------

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_sha(value) -> str | None:
    """Bare lowercase hex from `abc...` or `sha256:abc...`; None when it is not a sha-256."""
    if not isinstance(value, str):
        return None
    text = value.strip().lower()
    if text.startswith("sha256:"):
        text = text[len("sha256:"):]
    return text if re.fullmatch(r"[0-9a-f]{64}", text) else None


def model_matches_base(model_bytes: bytes, expected_sha256) -> bool:
    """True when these bytes are the model the session started from.

    The session hashes the text it was handed; a file on disk may carry CRLF line endings or a BOM
    that the text did not, so the raw bytes and their normalised forms are all accepted."""
    expected = normalize_sha(expected_sha256)
    if not expected:
        return False
    no_bom = model_bytes[3:] if model_bytes.startswith(b"\xef\xbb\xbf") else model_bytes
    variants = {model_bytes, no_bom, model_bytes.replace(b"\r\n", b"\n"), no_bom.replace(b"\r\n", b"\n")}
    return any(sha256_bytes(v) == expected for v in variants)


def resolve_model_path(app_dir) -> Path | None:
    """The model.json of an app, given the folder the Manager knows it by.

    That is the model folder itself (plain init layout first, then the corpus layouts under it), or the generated
    `<name>-app` folder -- which is what the Manager's app list holds, and whose model sits BESIDE it in `<name>` --
    or an AppGen output root (`App` beside `Input`). One resolver for start, check, apply and undo: when they
    disagreed, a conversation started fine on a Manager-made app and every answer then came back "base-moved"."""
    if not app_dir:
        return None
    root = Path(str(app_dir).rstrip("\\/"))
    roots = [root]
    if root.name.endswith("-app") and len(root.name) > len("-app"):
        roots.append(root.with_name(root.name[: -len("-app")]))
    for folder in roots:
        for relative in MODEL_FILE_CANDIDATES:
            candidate = folder / relative
            if candidate.is_file():
                return candidate
    beside_input = root.parent / "Input" / "model.json"
    return beside_input if beside_input.is_file() else None


def atomic_write_bytes(path, data: bytes) -> None:
    """Write beside the target and move into place: a crash never leaves a half-written file."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=path.name + ".", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        for attempt in range(6):
            try:
                os.replace(tmp, path)
                return
            except PermissionError:
                if attempt == 5:
                    raise
                time.sleep(0.05 * (attempt + 1))  # Windows: a scanner may hold the target briefly
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def model_to_text(model: dict) -> str:
    return json.dumps(model, indent=2, ensure_ascii=False) + "\n"


def _finding(code: str, severity: str = "info", **detail) -> dict:
    out = {"code": code, "severity": severity}
    if detail:
        out["detail"] = detail
    return out


def _str(value) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _items(model, key) -> list:
    value = model.get(key) if isinstance(model, dict) else None
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def _concepts(model) -> dict:
    return {c["name"]: c for c in _items(model, "concepts") if isinstance(c.get("name"), str)}


def _fields(concept) -> dict:
    return {f["name"]: f for f in _items(concept, "fields") if isinstance(f.get("name"), str)}


def _label(obj, fallback: str) -> str:
    if isinstance(obj, dict):
        ui = obj.get("ui")
        for candidate in ((ui.get("label") if isinstance(ui, dict) else None), obj.get("title")):
            if _str(candidate):
                return candidate.strip()
    return fallback


def _clean_note(value, limit: int = AI_NOTE_CHARS) -> str | None:
    """One line of the AI's words, for display as plain text only."""
    if isinstance(value, dict):
        value = value.get("text") or value.get("requirement") or value.get("rationale") or value.get("description")
    if not isinstance(value, str):
        return None
    text = re.sub(r"[\x00-\x1f\x7f]+", " ", value)
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return None
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _pl(count: int, one: str, many: str) -> str:
    return f"{count} {one if count == 1 else many}"


def _join_words(items: list) -> str:
    items = [str(i) for i in items]
    if len(items) <= 1:
        return "".join(items)
    return ", ".join(items[:-1]) + " and " + items[-1]


def _humanize_key(key: str) -> str:
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", key).lower()


def _item_words(key: str) -> tuple:
    return ITEM_LABELS.get(key) or (_humanize_key(key), _humanize_key(key))


# ----------------------------------------------------------------------------------------------
# Versions (mirrors AuthoringDiffGate.compareVersions: dotted numeric, string fallback)
# ----------------------------------------------------------------------------------------------

def _int_or_none(text: str):
    return int(text) if re.fullmatch(r"[+-]?\d+", text) else None


def compare_versions(a: str, b: str) -> int:
    parts_a, parts_b = a.split("."), b.split(".")
    for i in range(max(len(parts_a), len(parts_b))):
        pa = parts_a[i] if i < len(parts_a) else "0"
        pb = parts_b[i] if i < len(parts_b) else "0"
        na, nb = _int_or_none(pa), _int_or_none(pb)
        if na is not None and nb is not None:
            cmp = (na > nb) - (na < nb)
        else:
            cmp = (pa > pb) - (pa < pb)
        if cmp:
            return cmp
    return 0


def bump_version(version) -> str:
    """The smallest sensible next version: "1.0" -> "1.1", "3" -> "4", "2.9" -> "2.10",
    "0.1.0" -> "0.1.1"; a last part that is not a plain number gets a ".1" appended instead."""
    text = str(version).strip() if version is not None else ""
    if not text:
        return "1"
    parts = text.split(".")
    if re.fullmatch(r"\d+", parts[-1]):
        parts[-1] = str(int(parts[-1]) + 1)
        bumped = ".".join(parts)
    else:
        bumped = text + ".1"
    return bumped if compare_versions(bumped, text) > 0 else text + ".1"


# ----------------------------------------------------------------------------------------------
# Step 1 - translate the AI's light manifest into npdev-authoring-submission.v1
# ----------------------------------------------------------------------------------------------

def _locate_rename(candidate: dict, from_name: str, to_name: str, concept: str | None):
    """(kind, concept) for a rename the candidate itself carries as renamedFrom, else (None, None)."""
    concepts = _concepts(candidate)
    target = concepts.get(to_name)
    if target is not None and _str(target.get("renamedFrom")) == from_name:
        return "concept", None
    scan = [concepts[concept]] if concept in concepts else list(concepts.values())
    for c in scan:
        field = _fields(c).get(to_name)
        if field is not None and _str(field.get("renamedFrom")) == from_name:
            return "field", c["name"]
    return None, None


def _translate_renames(ai: dict, candidate: dict, findings: list) -> list:
    out, seen = [], set()
    for entry in ai.get("renames") if isinstance(ai.get("renames"), list) else []:
        if not isinstance(entry, dict) or not _str(entry.get("from")) or not _str(entry.get("to")):
            findings.append(_finding("manifest-entry-ignored", "warning", section="renames"))
            continue
        old, new = entry["from"].strip(), entry["to"].strip()
        kind = str(entry.get("kind") or "").strip().lower()
        concept = _str(entry.get("concept"))
        inferred_kind, inferred_concept = _locate_rename(candidate, old, new, concept)
        if kind not in ("concept", "field"):
            kind = inferred_kind or ""
            if not kind and new in _concepts(candidate):
                kind = "concept"
            if not kind:
                for c in _concepts(candidate).values():
                    if new in _fields(c):
                        kind, inferred_concept = "field", c["name"]
                        break
            if not kind:
                findings.append(_finding("manifest-entry-ignored", "warning", section="renames", name=new))
                continue
            findings.append(_finding("rename-kind-inferred", "info", name=new, kind=kind))
        item = {"kind": kind, "from": old, "to": new}
        if kind == "field":
            concept = concept or inferred_concept
            if concept:
                item = {"kind": kind, "concept": concept, "from": old, "to": new}
        key = (item["kind"], item.get("concept"), old, new)
        if key not in seen:
            seen.add(key)
            out.append(item)
    return out


def _translate_removals(ai: dict, base: dict, candidate: dict, request: str, findings: list) -> list:
    out, seen = [], set()
    entries = ai.get("deliberateRemovals") if isinstance(ai.get("deliberateRemovals"), list) else []
    base_concepts, cand_concepts = _concepts(base), _concepts(candidate)
    for entry in entries:
        if isinstance(entry, str) and entry.strip():
            text = entry.strip()
            entry = {"kind": "field", "concept": text.split(".", 1)[0], "name": text.split(".", 1)[1]} \
                if "." in text else {"kind": "concept", "name": text}
        if not isinstance(entry, dict) or not _str(entry.get("name")):
            findings.append(_finding("manifest-entry-ignored", "warning", section="deliberateRemovals"))
            continue
        name = entry["name"].strip()
        concept = _str(entry.get("concept"))
        kind = str(entry.get("kind") or "").strip().lower()
        if kind not in ("concept", "field"):
            kind = "field" if concept else "concept"
        if kind == "field" and not concept:
            owners = [c for c, node in base_concepts.items()
                      if name in _fields(node) and name not in _fields(cand_concepts.get(c, {}))]
            if len(owners) == 1:
                concept = owners[0]
            else:
                findings.append(_finding("manifest-entry-ignored", "warning", section="deliberateRemovals", name=name))
                continue
        reason = _str(entry.get("reason")) or f"requested: {request}"
        item = {"kind": kind, "name": name, "reason": reason}
        if kind == "field":
            item = {"kind": kind, "concept": concept, "name": name, "reason": reason}
        key = (kind, concept, name)
        if key not in seen:
            seen.add(key)
            out.append(item)
    return out


def _translate_could_not_express(ai: dict) -> list:
    out = []
    entries = ai.get("couldNotExpress") if isinstance(ai.get("couldNotExpress"), list) else []
    for entry in entries:
        if isinstance(entry, str) and entry.strip():
            out.append({"requirement": entry.strip(), "why": "stated by the AI"})
        elif isinstance(entry, dict):
            requirement = _str(entry.get("requirement")) or _str(entry.get("text"))
            if not requirement:
                continue
            item = {"requirement": requirement, "why": _str(entry.get("why")) or "stated by the AI"}
            if _str(entry.get("workaroundOffered")):
                item["workaroundOffered"] = entry["workaroundOffered"].strip()
            out.append(item)
    return out


def translate_manifest(session, base_model, candidate_model, ai_manifest, *, previous_sha256=None):
    """The AI's light `npdev-submission.json` -> the strict `npdev-authoring-submission.v1`.

    Returns (submission, candidate_model, findings). `candidate_model` is a COPY, with its `version`
    bumped when it did not strictly increase (finding `version-bumped`). `securityChanges` always
    starts empty: the AI's own sentences about access only reach the preview, because the owner, not
    the AI, decides which security changes are accepted. `assumptions` never enters the manifest.
    `previous_sha256` is the sha-256 of the exact file the gate will be given as the previous model;
    when omitted the session's `baseModelSha256` is used."""
    findings: list = []
    candidate = copy.deepcopy(candidate_model)
    ai = ai_manifest if isinstance(ai_manifest, dict) else {}
    if not isinstance(ai_manifest, dict):
        findings.append(_finding("manifest-missing", "warning"))

    sha = normalize_sha(previous_sha256) or normalize_sha((session or {}).get("baseModelSha256"))
    if sha is None:
        raise ValueError("the session carries no baseModelSha256 and none was given")

    base_version = _str(base_model.get("version")) if isinstance(base_model.get("version"), str) else None
    if base_version is None and isinstance(base_model.get("version"), (int, float)) \
            and not isinstance(base_model.get("version"), bool):
        base_version = str(base_model["version"])
    cand_raw = candidate.get("version")
    cand_version = cand_raw.strip() if isinstance(cand_raw, str) and cand_raw.strip() else (
        str(cand_raw) if isinstance(cand_raw, (int, float)) and not isinstance(cand_raw, bool) else None)
    if base_version is None:
        submitted = cand_version or "1"
        candidate["version"] = submitted
    elif cand_version is not None and compare_versions(cand_version, base_version) > 0:
        submitted = cand_version
        if candidate.get("version") != submitted:
            candidate["version"] = submitted
            findings.append(_finding("version-normalized", "info", version=submitted))
    else:
        submitted = bump_version(base_version)
        candidate["version"] = submitted
        findings.append(_finding("version-bumped", "info", previous=base_version,
                                 fromVersion=cand_version, to=submitted))

    ask_line = next((ln.strip() for ln in str((session or {}).get("ask") or "").splitlines() if ln.strip()), "")
    request = _str(ai.get("request")) or ask_line or "Change requested through the browser AI relay"

    submission = {
        "recordKind": SUBMISSION_KIND,
        "previousModelSha256": sha,
        "previousModelVersion": base_version or "unversioned",
        "submittedModelVersion": submitted,
        "request": request,
        "renames": _translate_renames(ai, candidate, findings),
        "deliberateRemovals": _translate_removals(ai, base_model, candidate, request, findings),
        "securityChanges": [],
        "couldNotExpress": _translate_could_not_express(ai),
        "unchangedButSuspect": [],
    }
    return submission, candidate, findings


# ----------------------------------------------------------------------------------------------
# Step 2 - the repo's own diff gate
# ----------------------------------------------------------------------------------------------

def _load_cli():
    here = str(Path(__file__).resolve().parent)
    if here not in sys.path:
        sys.path.insert(0, here)
    import npdev_cli
    return npdev_cli


def _unavailable(reason: str, detail: str | None = None) -> dict:
    return {"available": False, "status": "unavailable", "reason": reason, "detail": detail,
            "diagnostics": [], "report": None, "archivedPreviousModelTo": None}


def gate_unavailable_reason() -> str | None:
    """Why the gate cannot run on this machine (no Java, no Gradle wrapper), or None."""
    cli = _load_cli()
    if cli.java_launcher() is None:
        return "java-missing"
    if not cli.gradle_wrapper(cli.repo_root() / "NPDevGenerator").exists():
        return "gradle-wrapper-missing"
    return None


def run_gate(base_model_path, candidate_model_path, submission_path, out_dir, *, archive_dir=None) -> dict:
    """Run `npdev author diff-gate` (or, with `archive_dir`, the `author submit` form that also
    archives the previous model on a pass) through the CLI's own `_run_authoring_gate`.

    Returns {"available": True, "status": "passed"|"failed", "diagnostics": [...], "report": <the raw
    report>, "archivedPreviousModelTo": str|None}; or {"available": False, "status": "unavailable",
    "reason": ...} when Java or the Gradle wrapper is missing or the gate could not produce a report
    (never a refusal: the AI did nothing wrong)."""
    paths = {"base": base_model_path, "candidate": candidate_model_path, "submission": submission_path}
    for label, value in paths.items():
        if not Path(value).is_file():
            raise FileNotFoundError(f"{label} file not found: {value}")
    reason = gate_unavailable_reason()
    if reason:
        return _unavailable(reason)
    cli = _load_cli()
    args = argparse.Namespace(
        previous=str(base_model_path), submitted=str(candidate_model_path), manifest=str(submission_path),
        output=str(out_dir), theirs=None, theirs_manifest=None, merged_out=None, merged_manifest_out=None)
    try:
        report = cli._run_authoring_gate(args, archive_dir=Path(archive_dir) if archive_dir else None)
    except cli.CliError as exc:
        return _unavailable("gate-did-not-run", str(exc)[-500:])
    diagnostics = [d for d in (report.get("diagnostics") or []) if isinstance(d, dict)]
    return {"available": True, "status": "passed" if report.get("status") == "passed" else "failed",
            "diagnostics": diagnostics, "report": report,
            "archivedPreviousModelTo": report.get("archivedPreviousModelTo"), "reason": None}


_SECURITY_RE = re.compile(
    r"^'(?P<kind>[^']+)' changed on '(?P<subject>.+?)' \(from (?P<from>&lt;absent&gt;|<absent>|\".*?\") to "
    r"(?P<to><absent>|\".*\")\) but is not declared", re.S)


def parse_security_diagnostic(diag: dict) -> dict | None:
    """One AUTHORING_UNDECLARED_SECURITY_CHANGE diagnostic -> {"kind", "concept", "subject", "from",
    "to", ...} or None when the message is not in the gate's known shape.

    The gate's own suggested fix names `Concept.field` for a `sensitive` change, but the gate only
    accepts the CONCEPT name in `securityChanges[].concept` (verified live), so `concept` is the part
    before the dot and `field` the rest."""
    if not isinstance(diag, dict) or diag.get("code") != SECURITY_CODE:
        return None
    match = _SECURITY_RE.match(str(diag.get("message") or ""))
    if not match:
        return None
    kind_raw = match.group("kind")
    kind = kind_raw.split(" (")[0].strip()
    scope = re.search(r"\((\w+)\)", kind_raw)

    def value(token: str):
        return None if token in ("<absent>", "&lt;absent&gt;") else token[1:-1]

    subject = _str(diag.get("path")) or match.group("subject")
    out = {"kind": kind, "subject": subject, "concept": subject, "from": value(match.group("from")),
           "to": value(match.group("to"))}
    if kind == "sensitive" and "." in subject:
        out["concept"], out["field"] = subject.split(".", 1)
    if scope:
        out["scope"] = scope.group(1)
    return out


def security_manifest_entries(pending: list, rationale: str = CONFIRMED_RATIONALE) -> list:
    out, seen = [], set()
    for delta in pending:
        key = (delta["kind"], delta["concept"])
        if key in seen:
            continue
        seen.add(key)
        entry = {"kind": delta["kind"], "concept": delta["concept"]}
        if delta.get("from") is not None:
            entry["from"] = str(delta["from"])
        if delta.get("to") is not None:
            entry["to"] = str(delta["to"])
        entry["rationale"] = rationale
        out.append(entry)
    return out


def gate_error_line(diag: dict) -> str:
    """One line per refusal: `path - message - suggestedFix`."""
    parts = [str(diag.get("path") or "model.json"), str(diag.get("message") or diag.get("code") or "refused")]
    if diag.get("suggestedFix"):
        parts.append(str(diag["suggestedFix"]))
    return " - ".join(p.replace("\n", " ").strip() for p in parts)


# ----------------------------------------------------------------------------------------------
# Step 3 - the plain-language preview
# ----------------------------------------------------------------------------------------------

def _line(code: str, **params) -> dict:
    return {"code": code, "params": params, "text": PREVIEW_TEMPLATES[code].format(**params)}


def _warning(code: str, template: str, needs: bool, *, schema_ack: bool = False, **params) -> dict:
    """`schema_ack`: applying this makes the app's own schema guard refuse to boot until someone gives it a separate,
    itemized acknowledgment of the data that would be dropped (docs/SCHEMA_EVOLUTION.md, "Acknowledging destructive
    changes"). It is a fact about the change, not a decision: a caller that cannot give that acknowledgment (the
    Manager's window) declines to offer Apply; `npdev ai relay apply` still writes the model."""
    family = template
    return {"code": code, "text": PREVIEW_TEMPLATES[family].format(**params), "needsConfirmation": needs,
            "needsSchemaAcknowledgment": schema_ack, "params": {"family": family, **params}}


def _attr_words(keys: list) -> str:
    words = {"type": "type", "required": "whether it is required", "unique": "whether it must be unique",
             "ui": "how it looks", "default": "default value", "reference": "what it points to",
             "values": "allowed values", "enum": "allowed values", "length": "length", "min": "smallest value",
             "max": "largest value", "invariants": "rules", "access": "access rules", "indexes": "indexes",
             "sensitive": "whether it is sensitive"}
    seen = []
    for key in keys:
        word = words.get(key, "settings")
        if word not in seen:
            seen.append(word)
    return _join_words(seen)


def _changed_keys(before: dict, after: dict, ignore=()) -> list:
    keys = [k for k in sorted(set(before) | set(after))
            if k not in ignore and before.get(k) != after.get(k)]
    return keys


def diff_models(base: dict, cand: dict) -> dict:
    """What differs between two models, by concept / field / named item. Rename markers
    (`renamedFrom`) are honoured exactly as the gate honours them."""
    base_c, cand_c = _concepts(base), _concepts(cand)
    renamed_concepts = {}
    for name, node in cand_c.items():
        previous = _str(node.get("renamedFrom"))
        if previous and previous in base_c and previous != name:
            renamed_concepts[name] = previous
    away = set(renamed_concepts.values())
    out = {
        "addedConcepts": [], "removedConcepts": [], "renamedConcepts": [],
        "addedFields": {}, "removedFields": [], "renamedFields": [], "changedFields": [],
        "changedConcepts": [], "addedItems": {}, "removedItems": {}, "changedItems": {}, "changedSettings": [],
    }
    for name, node in cand_c.items():
        previous_name = renamed_concepts.get(name, name)
        previous = base_c.get(previous_name)
        if previous is None:
            out["addedConcepts"].append({"name": name, "label": _label(node, name),
                                         "fields": [(f, _label(fd, f)) for f, fd in _fields(node).items()]})
            continue
        if previous_name != name:
            out["renamedConcepts"].append({"from": previous_name, "to": name,
                                           "from_label": _label(previous, previous_name), "to_label": _label(node, name)})
        base_f, cand_f = _fields(previous), _fields(node)
        renamed_fields = {}
        for fname, fnode in cand_f.items():
            old = _str(fnode.get("renamedFrom"))
            if old and old in base_f and old != fname:
                renamed_fields[fname] = old
        for fname, fnode in cand_f.items():
            old = renamed_fields.get(fname, fname)
            if old not in base_f:
                out["addedFields"].setdefault(name, []).append((fname, _label(fnode, fname)))
                continue
            if old != fname:
                out["renamedFields"].append({"concept": name, "from": old, "to": fname,
                                             "from_label": _label(base_f[old], old), "to_label": _label(fnode, fname)})
            keys = _changed_keys(base_f[old], fnode, ignore=("name", "renamedFrom", "uid"))
            if keys:
                out["changedFields"].append({"concept": name, "field": fname, "label": _label(fnode, fname), "keys": keys})
        away_fields = set(renamed_fields.values())
        for fname, fnode in base_f.items():
            if fname not in cand_f and fname not in away_fields:
                out["removedFields"].append({"concept": name, "name": fname, "label": _label(fnode, fname)})
        keys = _changed_keys(previous, node, ignore=("name", "fields", "renamedFrom", "uid"))
        if keys:
            out["changedConcepts"].append({"concept": name, "label": _label(node, name), "keys": keys})
    for name, node in base_c.items():
        if name not in cand_c and name not in away:
            out["removedConcepts"].append({"name": name, "label": _label(node, name)})

    for key in sorted((set(base) | set(cand)) - {"concepts"} - _IGNORED_TOP_LEVEL):
        before, after = base.get(key), cand.get(key)
        if before == after:
            continue
        if isinstance(before, list) or isinstance(after, list):
            def named(value):
                result = {}
                for position, item in enumerate(value if isinstance(value, list) else []):
                    if isinstance(item, dict):
                        ident = _str(item.get("name")) or _str(item.get("id")) or f"#{position + 1}"
                    else:
                        ident = str(item)
                    result[ident] = item
                return result
            b, a = named(before), named(after)
            for ident, item in a.items():
                if ident not in b:
                    out["addedItems"].setdefault(key, []).append((ident, _label(item, ident)))
                elif b[ident] != item:
                    out["changedItems"].setdefault(key, []).append((ident, _label(item, ident)))
            for ident, item in b.items():
                if ident not in a:
                    out["removedItems"].setdefault(key, []).append((ident, _label(item, ident)))
        else:
            out["changedSettings"].append(key)
    return out


def _is_empty_diff(diff: dict) -> bool:
    return not any(diff[k] for k in diff)


def _files_changed(model_path, extra_files, config_offers) -> bool:
    """True when the answer changes something besides the model's content: a theme file or a web page the app does not
    have yet or with different content (REG-323 T4.3), a pages.json entry that is new, or a config setting whose value would differ from what config.json says now
    (T4.5b, `config_offers` = [(setting id, value)]). A theme-only or setting-only answer leaves the model identical and
    is still a change."""
    import npdev_relay_pages as pages_mod
    import npdev_relay_seeds as seeds_mod
    import npdev_relay_themes as themes_mod

    root = Path(model_path).parent
    if themes_mod.changed(root, extra_files) or pages_mod.changed(root, extra_files) or seeds_mod.changed(root, extra_files):
        return True
    if not config_offers:
        return False
    current = themes_mod.read_config(root).get("defaults")
    current = current if isinstance(current, dict) else {}
    return any(key not in current or current[key] != value for key, value in config_offers)


def _ai_note_lists(ai_manifest) -> tuple:
    ai = ai_manifest if isinstance(ai_manifest, dict) else {}

    def notes(key):
        value = ai.get(key)
        if isinstance(value, str):
            value = [value]
        out = []
        for entry in value if isinstance(value, list) else []:
            text = _clean_note(entry)
            if text:
                out.append(text)
        return out[:AI_NOTE_LIMIT]

    return notes("assumptions"), notes("securityChanges"), notes("couldNotExpress")


def _security_text(delta: dict) -> tuple:
    kind = delta["kind"]
    family = "SECURITY:" + kind
    if family not in PREVIEW_TEMPLATES:
        family = "SECURITY:invariant"
    from_text = "the default" if delta.get("from") in (None, "") else str(delta["from"])
    to_text = "the default" if delta.get("to") in (None, "") else str(delta["to"])
    params = {"concept": delta["concept"], "field": delta.get("field") or "", "from_text": from_text,
              "to_text": to_text, "kind": kind}
    if kind == "sensitive":
        params["to_text"] = "marked as sensitive" if str(delta.get("to")) == "true" else "no longer marked as sensitive"
    if kind == "permissionRequirements":
        params["concept"] = delta["subject"]
    return family, params


def _add_ai_notes(lines: list, warnings: list, ai_manifest) -> None:
    assumptions, security, could_not = _ai_note_lists(ai_manifest)
    for text in assumptions:
        lines.append(_line("AI_ASSUMPTION", text=text))
    for index, text in enumerate(security, 1):
        warnings.append(_warning(f"AI_SECURITY_NOTE:{index}", "AI_SECURITY_NOTE", False, text=text))
    for index, text in enumerate(could_not, 1):
        warnings.append(_warning(f"COULD_NOT_EXPRESS:{index}", "COULD_NOT_EXPRESS", False, text=text))


def _capped(lines: list, built: list, count_label: tuple) -> None:
    lines.extend(built[:LINES_PER_KIND])
    if len(built) > LINES_PER_KIND:
        rest = len(built) - LINES_PER_KIND
        lines.append(_line("MORE", count=rest, count_text=str(rest)))


def build_create_preview(candidate_model: dict, ai_manifest=None) -> dict:
    """The preview for a brand-new app: counts in plain words, and nothing is ever deleted."""
    concepts = _items(candidate_model, "concepts")
    field_total = sum(len(_items(c, "fields")) for c in concepts)
    counts = [(len(concepts), ) + _item_words("concepts"), (field_total, "field", "fields")]
    count_keys = ["concepts", "fields"]  # what each count is, so a translation can name it in its own words
    for key in sorted(k for k, v in (candidate_model or {}).items()
                      if k != "concepts" and isinstance(v, list) and v and k in ITEM_LABELS):
        counts.append((len(candidate_model[key]),) + _item_words(key))
        count_keys.append(key)
    counts_text = _join_words([_pl(n, one, many) for n, one, many in counts if n])
    lines = [_line("CREATE_SUMMARY", counts_text=counts_text or "nothing in it yet",
                   concepts=len(concepts), fields=field_total,
                   counts=[[key, c[0]] for key, c in zip(count_keys, counts)])]
    built = []
    for concept in concepts:
        name = concept.get("name") if isinstance(concept.get("name"), str) else "?"
        fields = [(_label(f, f.get("name", "?"))) for f in _items(concept, "fields")]
        shown = fields[:6]
        fields_text = _join_words(shown) + (f" and {len(fields) - 6} more" if len(fields) > 6 else "")
        built.append(_line("CREATE_CONCEPT", label=_label(concept, name), count=len(fields),
                           count_text=_pl(len(fields), "field", "fields"),
                           fields_part=f" ({fields_text})" if fields else "", fields_text=fields_text,
                           fields_shown=shown, fields_more=max(0, len(fields) - 6)))
    _capped(lines, built, ("concepts",))
    lines.append(_line("CREATE_NOTHING_DELETED"))
    warnings: list = []
    _add_ai_notes(lines, warnings, ai_manifest)
    return {"sentences": [l["text"] for l in lines], "warnings": warnings, "dataLoss": False, "lines": lines}


def build_preview(gate_report, base_model, candidate_model, ai_manifest, intent) -> dict:
    """The plain-language preview of a change, matching the ingest-result schema's `preview`
    (`sentences`, `warnings[{code, text, needsConfirmation}]`, `dataLoss`) plus, additively:
    `lines[{code, params, text}]` (one per sentence, for translation) and `warnings[].params`.

    Sentences say what is added or changed. Removals, renames and security changes are never plain
    sentences: each is a warning with `needsConfirmation: true` (a rename keeps the data but is still
    confirmed). `gate_report` supplies the security changes: every AUTHORING_UNDECLARED_SECURITY_CHANGE
    diagnostic in it (any status) is one change the owner must confirm."""
    if intent == "create" or not isinstance(base_model, dict):
        return build_create_preview(candidate_model, ai_manifest)
    diff = diff_models(base_model, candidate_model)
    lines, warnings = [], []

    built = []
    for entry in diff["addedConcepts"]:
        n = len(entry["fields"])
        built.append(_line("ADD_CONCEPT", label=entry["label"], name=entry["name"], count=n,
                           count_text=_pl(n, "field", "fields")))
    _capped(lines, built, ("concepts",))
    built = []
    for concept, added in diff["addedFields"].items():
        labels = [label for _, label in added]
        built.append(_line("ADD_FIELDS", concept=concept, fields=[n for n, _ in added], count=len(labels), labels=labels,
                           field_word="field" if len(labels) == 1 else "fields", fields_text=_join_words(labels)))
    _capped(lines, built, ("fields",))
    built = [_line("CHANGE_FIELD", concept=e["concept"], field=e["label"], name=e["field"], keys=e["keys"],
                   what_text=_attr_words(e["keys"])) for e in diff["changedFields"]]
    _capped(lines, built, ("fields",))
    built = [_line("CHANGE_CONCEPT", concept=e["label"], name=e["concept"], keys=e["keys"],
                   what_text=_attr_words(e["keys"])) for e in diff["changedConcepts"]
             if [k for k in e["keys"] if k not in ("access", "invariants")]]
    _capped(lines, built, ("concepts",))
    built = []
    for key, added in diff["addedItems"].items():
        kind = _item_words(key)[0]
        built.extend(_line("ADD_ITEM", kind=kind, label=label, name=name, collection=key) for name, label in added)
    for key, changed in diff["changedItems"].items():
        kind = _item_words(key)[0]
        built.extend(_line("CHANGE_ITEM", kind=kind, label=label, name=name, collection=key) for name, label in changed)
    _capped(lines, built, ("items",))
    if diff["changedSettings"]:
        lines.append(_line("CHANGE_SETTINGS", keys=diff["changedSettings"],
                           what_text=_join_words([_humanize_key(k) for k in diff["changedSettings"]])))

    for entry in diff["removedConcepts"]:
        warnings.append(_warning(f"REMOVAL:concept:{entry['name']}", "REMOVAL:concept", True, schema_ack=True,
                                 kind="concept", name=entry["name"], label=entry["label"]))
    for entry in diff["removedFields"]:
        warnings.append(_warning(f"REMOVAL:field:{entry['concept']}.{entry['name']}", "REMOVAL:field", True, schema_ack=True,
                                 kind="field", concept=entry["concept"], name=entry["name"], label=entry["label"]))
    for key, removed in diff["removedItems"].items():
        for name, label in removed:
            warnings.append(_warning(f"REMOVAL:{key}:{name}", "REMOVAL:concept", True,
                                     kind=key, name=name, label=label))
    for entry in diff["renamedConcepts"]:
        warnings.append(_warning(f"RENAME:concept:{entry['from']}>{entry['to']}", "RENAME:concept", True,
                                 kind="concept", **{k: entry[k] for k in ("from", "to", "from_label", "to_label")}))
    for entry in diff["renamedFields"]:
        warnings.append(_warning(f"RENAME:field:{entry['concept']}.{entry['from']}>{entry['to']}", "RENAME:field", True,
                                 kind="field", **{k: entry[k] for k in ("concept", "from", "to", "from_label", "to_label")}))
    seen = set()
    for diag in (gate_report or {}).get("diagnostics") or []:
        delta = parse_security_diagnostic(diag)
        if not delta or (delta["kind"], delta["subject"]) in seen:
            continue
        seen.add((delta["kind"], delta["subject"]))
        family, params = _security_text(delta)
        warnings.append(_warning(f"SECURITY:{delta['kind']}:{delta['subject']}", family, True, **params))

    if _is_empty_diff(diff):
        lines.append(_line("NO_CHANGES"))
    _add_ai_notes(lines, warnings, ai_manifest)
    data_loss = any(w["code"].startswith("REMOVAL:") for w in warnings)
    return {"sentences": [l["text"] for l in lines], "warnings": warnings, "dataLoss": data_loss, "lines": lines,
            "needsSchemaAcknowledgment": any(w.get("needsSchemaAcknowledgment") for w in warnings)}


# ----------------------------------------------------------------------------------------------
# Step 4 - put it together
# ----------------------------------------------------------------------------------------------

def _result(outcome, findings, **extra) -> dict:
    out = {"outcome": outcome, "preview": None, "findings": findings, "errors": [], "candidateModelText": None,
           "submission": None, "baseMoved": False, "next": None}
    out.update(extra)
    return out


def _base_moved_result(findings, candidate_text, **detail) -> dict:
    findings.append(_finding("base-moved", "warning", **detail))
    return _result("PARSED", findings, candidateModelText=candidate_text, baseMoved=True,
                   next={"kind": "user-action", "messageId": "BASE_MOVED"})


SPLIT_ROOT_KEYS = ("fragments", "contexts", "packs")  # a model.json carrying any of these is made of more than itself


def is_split_root(model) -> bool:
    return isinstance(model, dict) and any(key in model for key in SPLIT_ROOT_KEYS)


def evaluate_create(session, candidate_model_text, ai_manifest, *, work_dir=None, extra_files=None) -> dict:
    """Create intent: there is nothing to diff against, so no gate and no base. Same result shape.

    A model made of fragments is composed first (`extra_files` = {relative path: text} the answer carried, staged under
    `work_dir`) and the preview describes the composed model."""
    findings: list = []
    try:
        candidate = json.loads(candidate_model_text)
    except (TypeError, ValueError) as exc:
        return _result("NEEDS_FIX", findings, errors=[f"model.json - not valid JSON ({exc}) - send the complete model.json"],
                       candidateModelText=candidate_model_text, next={"kind": "copy-prompt", "messageId": "NEEDS_FIX"})
    if isinstance(candidate, dict) and (extra_files or is_split_root(candidate)):
        import npdev_relay_files as files_mod  # lazy: that module imports this one

        if work_dir is None:
            raise ValueError("a model made of fragments needs a work folder to be staged in")
        try:
            staged = files_mod.stage(None, extra_files or {}, candidate_model_text, Path(work_dir) / "stage-candidate")
            candidate = files_mod.compose(staged)
        except files_mod.TreeError as exc:
            return _result("NEEDS_FIX", findings, errors=[f"{exc} - send the complete model.json and every file it points at"],
                           candidateModelText=candidate_model_text, next={"kind": "copy-prompt", "messageId": "NEEDS_FIX"})
    if not isinstance(candidate, dict) or not isinstance(candidate.get("concepts"), list):
        return _result("NEEDS_FIX", findings, errors=["model.json - it is not an app model (no concepts list) - send the complete model.json"],
                       candidateModelText=candidate_model_text, next={"kind": "copy-prompt", "messageId": "NEEDS_FIX"})
    return _result("READY", findings, preview=build_create_preview(candidate, ai_manifest),
                   candidateModelText=candidate_model_text, next={"kind": "apply", "messageId": "READY"})


def _evaluate(session, candidate_model_text, ai_manifest, work_dir, app_model_path, extra_files=None,
              config_offers=None) -> dict:
    """The whole check; the public dict plus private `_`-keys apply needs (paths, parsed models).

    `extra_files` = {relative path: text} of the fragments/contexts the answer carried beside model.json. When there are
    some, or the answer's or the app's model is split, both sides are staged as folders and composed, so the gate, the
    diff and the preview all see the ONE model the files make together; otherwise this is the single-file check."""
    extra_files = dict(extra_files or {})
    if (session or {}).get("intent") == "create":
        return evaluate_create(session, candidate_model_text, ai_manifest, work_dir=work_dir, extra_files=extra_files)
    findings: list = []
    needs_fix = {"kind": "copy-prompt", "messageId": "NEEDS_FIX"}
    try:
        candidate = json.loads(candidate_model_text)
    except (TypeError, ValueError) as exc:
        return _result("NEEDS_FIX", findings, errors=[f"model.json - not valid JSON ({exc}) - send the complete model.json"],
                       candidateModelText=candidate_model_text, next=needs_fix)
    split_answer = bool(extra_files) or is_split_root(candidate)
    if not isinstance(candidate, dict) or (not split_answer and not isinstance(candidate.get("concepts"), list)):
        return _result("NEEDS_FIX", findings, errors=["model.json - it is not an app model (no concepts list) - send the complete model.json"],
                       candidateModelText=candidate_model_text, next=needs_fix)

    model_path = Path(app_model_path) if app_model_path else resolve_model_path(session.get("appDir"))
    if model_path is None or not model_path.is_file():
        return _base_moved_result(findings, candidate_model_text, reason="app-model-missing")
    base_bytes = model_path.read_bytes()
    expected = session.get("baseModelSha256")
    if normalize_sha(expected) is None:
        findings.append(_finding("base-unchecked", "warning"))
    elif not model_matches_base(base_bytes, expected):
        return _base_moved_result(findings, candidate_model_text, reason="model-changed")
    try:
        base_model = json.loads(base_bytes.decode("utf-8-sig"))
    except ValueError:
        return _result("PARSED", findings + [_finding("app-model-unreadable", "error")],
                       candidateModelText=candidate_model_text, next={"kind": "user-action", "messageId": "APP_MODEL_UNREADABLE"})

    import npdev_relay_files as files_mod  # lazy: that module imports this one

    work = Path(work_dir)
    work.mkdir(parents=True, exist_ok=True)
    previous_sha = sha256_bytes(base_bytes)
    raw_candidate = candidate
    staged = split_answer
    if not staged:
        try:
            staged = files_mod.is_split(model_path)
        except files_mod.TreeError:
            staged = False  # the gate below says what is wrong with it
    if staged:
        # Both sides become folders, so every `$ref` resolves and the diff compares what the files make together.
        try:
            base_copy = files_mod.stage(model_path, {}, None, work / "stage-base")
            base_model = files_mod.compose(base_copy)
        except files_mod.TreeError as exc:
            return _result("PARSED", findings + [_finding("app-model-unreadable", "error", reason=str(exc))],
                           candidateModelText=candidate_model_text,
                           next={"kind": "user-action", "messageId": "APP_MODEL_UNREADABLE"})
        try:
            candidate_path = files_mod.stage(model_path, extra_files, candidate_model_text, work / "stage-candidate")
            candidate = files_mod.compose(candidate_path)
        except files_mod.TreeError as exc:
            return _result("NEEDS_FIX", findings, errors=[f"{exc} - send the complete model.json and every file it points at"],
                           candidateModelText=candidate_model_text, next=needs_fix)
        if not isinstance(candidate.get("concepts"), list):
            return _result("NEEDS_FIX", findings, errors=["model.json - it is not an app model (no concepts list) - send the complete model.json"],
                           candidateModelText=candidate_model_text, next=needs_fix)
    else:
        base_copy = work / "base-model.json"
        atomic_write_bytes(base_copy, base_bytes)

    submission, candidate2, translate_findings = translate_manifest(
        session, base_model, candidate, ai_manifest, previous_sha256=previous_sha)
    findings.extend(translate_findings)
    if staged:
        # Only a root scalar (the version) is ever changed by translation; it lives in model.json, never in a fragment.
        raw2 = copy.deepcopy(raw_candidate)
        if candidate2.get("version") != raw2.get("version"):
            raw2["version"] = candidate2["version"]
        candidate_text = candidate_model_text if raw2 == raw_candidate else model_to_text(raw2)
    else:
        candidate_text = candidate_model_text if candidate2 == candidate else model_to_text(candidate2)
    common = {"candidateModelText": candidate_text, "submission": submission}

    if _is_empty_diff(diff_models(base_model, candidate2)) and not _files_changed(model_path, extra_files, config_offers):
        findings.append(_finding("no-changes", "error"))
        return _result("NEEDS_FIX", findings, next=needs_fix, **common,
                       errors=["model.json - identical to the current model, nothing was changed - "
                               "apply the requested change and send the complete model.json"])

    if not staged:
        candidate_path = work / "candidate-model.json"
    submission_path = work / "submission.json"
    atomic_write_bytes(candidate_path, candidate_text.encode("utf-8"))  # staged: model.json again, with the bumped version
    atomic_write_bytes(submission_path, (json.dumps(submission, indent=2) + "\n").encode("utf-8"))
    private = {"_modelPath": model_path, "_baseCopy": base_copy, "_candidatePath": candidate_path,
               "_submissionPath": submission_path, "_baseModel": base_model, "_candidate": candidate2,
               "_previousSha": previous_sha, "_ai": ai_manifest if isinstance(ai_manifest, dict) else {},
               "_pending": [], "_extraFiles": extra_files, "_staged": staged}

    gate = run_gate(base_copy, candidate_path, submission_path, work / "gate")
    if not gate["available"]:
        findings.append(_finding("validator-unavailable", "warning", reason=gate.get("reason"), detail=gate.get("detail")))
        return _result("PARSED", findings, next={"kind": "user-action", "messageId": "VALIDATOR_UNAVAILABLE"},
                       **common, **private)

    diagnostics = gate["diagnostics"]
    if any(d.get("code") == SHA_MISMATCH_CODE for d in diagnostics):
        return {**_base_moved_result(findings, candidate_model_text, reason="gate-sha-mismatch"), **private}

    first_report = gate["report"]
    if gate["status"] != "passed":
        pending = [p for p in (parse_security_diagnostic(d) for d in diagnostics) if p]
        others = [d for d in diagnostics if d.get("code") != SECURITY_CODE or parse_security_diagnostic(d) is None]
        if not diagnostics:
            findings.append(_finding("gate-refused-without-reason", "error"))
            return _result("PARSED", findings, next={"kind": "user-action", "messageId": "VALIDATOR_UNAVAILABLE"},
                           **common, **private)
        if others:
            for d in others:
                findings.append(_finding(str(d.get("code") or "AUTHORING_REFUSED"), "error",
                                         path=d.get("path"), message=d.get("message")))
            return _result("NEEDS_FIX", findings, errors=[gate_error_line(d) for d in others], next=needs_fix,
                           **common, **private)
        # Only security changes are missing: those the owner decides, not the AI. Prove that nothing
        # else is wrong by running the gate once more with them declared.
        provisional = copy.deepcopy(submission)
        provisional["securityChanges"] = security_manifest_entries(pending, "pending the owner's confirmation")
        provisional_path = work / "submission-with-security.json"
        atomic_write_bytes(provisional_path, (json.dumps(provisional, indent=2) + "\n").encode("utf-8"))
        second = run_gate(base_copy, candidate_path, provisional_path, work / "gate-with-security")
        if not second["available"]:
            findings.append(_finding("validator-unavailable", "warning", reason=second.get("reason")))
            return _result("PARSED", findings, next={"kind": "user-action", "messageId": "VALIDATOR_UNAVAILABLE"},
                           **common, **private)
        if second["status"] != "passed":
            leftover = second["diagnostics"]
            for d in leftover:
                findings.append(_finding(str(d.get("code") or "AUTHORING_REFUSED"), "error",
                                         path=d.get("path"), message=d.get("message")))
            return _result("NEEDS_FIX", findings, errors=[gate_error_line(d) for d in leftover], next=needs_fix,
                           **common, **private)
        private["_pending"] = pending
        first_report = {"status": "failed", "diagnostics": diagnostics}

    preview = build_preview(first_report, base_model, candidate2, ai_manifest, session.get("intent"))
    if any(w["needsConfirmation"] for w in preview["warnings"]):
        findings.append(_finding("confirmation-required", "info",
                                 codes=[w["code"] for w in preview["warnings"] if w["needsConfirmation"]]))
    return _result("READY", findings, preview=preview, next={"kind": "apply", "messageId": "READY"},
                   candidateSha256=sha256_bytes(candidate_text.encode("utf-8")),
                   pendingSecurityChanges=private["_pending"], **common, **private)


def evaluate_change(session, candidate_model_text, ai_manifest, work_dir, *, app_model_path=None, extra_files=None,
                    config_offers=None) -> dict:
    """The change/fix check the CLI runs after ingest + validation.

    `session` is the session dict (needs intent, appDir, baseModelSha256, ask); `candidate_model_text`
    the AI's model.json text; `ai_manifest` the parsed `npdev-submission.json` (or None); `work_dir` a
    folder this call may write evidence into (base-model.json, candidate-model.json, submission.json,
    gate reports). `app_model_path` overrides where the app's current model.json is.

    Returns {"outcome": "READY"|"NEEDS_FIX"|"PARSED", "preview", "findings", "errors",
    "candidateModelText", "submission", "baseMoved", "next", "candidateSha256", "pendingSecurityChanges"}.
    The create intent is routed to evaluate_create. When the base moved there is no merge: S5 needs
    a manifest for the owner's own edits, which does not exist, so the answer is `baseMoved: True`
    and finding `base-moved`; the CLI offers a fresh round-1 prompt built from the current model."""
    result = _evaluate(session, candidate_model_text, ai_manifest, work_dir, app_model_path, extra_files, config_offers)
    return {k: v for k, v in result.items() if not k.startswith("_")}


def evaluate_for_apply(session, candidate_model_text, ai_manifest, work_dir, *, app_model_path=None, extra_files=None,
                       config_offers=None) -> dict:
    """evaluate_change plus the private paths, parsed models and pending security changes that apply needs
    (keys starting with an underscore). Never serialise this dict."""
    return _evaluate(session, candidate_model_text, ai_manifest, work_dir, app_model_path, extra_files, config_offers)
