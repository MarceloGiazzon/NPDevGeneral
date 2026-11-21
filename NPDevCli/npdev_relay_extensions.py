"""REG-355: a Java class a browser AI wrote for a FLOW, added to an app through the platform's own hash-pinned extension seam.

The generator reads author Java only through `untrusted-extension-manifest.json` beside the model, one entry per class
(kind `flow`, relativePath, sha256, className, requiredRole) and the model's `flows[].metadata.untrustedExtensionEntrypoint`
naming that same relativePath; it then runs the platform's source policy and the kernel's bytecode inspector over it. This module
adds nothing to those safeguards, it only makes the three pieces agree so the safeguards can run:

  extensions/<Class>.java       the source, written by the relay (kind `java-extension`, accepted only when the machine's
                                `relay.codeFromBrowserAi` is `all`, and only after the owner ticks it)
  untrusted-extension-manifest  the entry is made HERE (the sha256 is computed, never trusted from the AI), merged with the
                                entries already there, written in the same all-or-nothing step as the source
  flows[].metadata.untrustedExtensionEntrypoint
                                written by the AI in its model.json; checked here BEFORE anything is accepted

The class contract the AI is told: `public final class <Class> { public Map<String, Object> execute(NPDevProcedureContext ctx) }`,
where the context offers tenantId(), actorId() and saveMany(concept, records).
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

KIND = "java-extension"
MANIFEST = "untrusted-extension-manifest.json"
MANIFEST_SCHEMA = "npdev-untrusted-extension-manifest.v1"
PATH_RE = re.compile(r"^extensions/([A-Za-z_][A-Za-z0-9_]*)\.java$")
MAX_CHARS = 30000
CONTRACT = "public final class <Name> { public Map<String, Object> execute(NPDevProcedureContext ctx) { ... } }"


def is_extension_path(path: str) -> bool:
    return bool(PATH_RE.match(path or ""))


def class_name(path: str) -> str:
    return PATH_RE.match(path).group(1)


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _flows_and_roles(model: dict, fragments: dict) -> tuple[list, list]:
    """Every flow and role name the answer declares: in model.json and in the fragments/*.json it sends."""
    flows, roles = [], []
    for doc in [model] + [_load(text) for path, text in sorted(fragments.items()) if path.endswith(".json")]:
        if not isinstance(doc, dict):
            continue
        flows += [f for f in doc.get("flows") or [] if isinstance(f, dict)]
        roles += [r for r in doc.get("roles") or [] if isinstance(r, dict) and isinstance(r.get("name"), str)]
    return flows, roles


def _load(text):
    try:
        return json.loads(text)
    except (TypeError, ValueError):
        return None


def _entrypoint(flow: dict) -> str:
    meta = flow.get("metadata")
    value = meta.get("untrustedExtensionEntrypoint") if isinstance(meta, dict) else None
    return value if isinstance(value, str) else ""


def role_for(roles: list) -> str | None:
    """The role the class is bound to: a role named admin (any case) if the app has one, else the first role declared.
    None when the app declares no role at all: a class with no role would be callable by anyone."""
    names = [r["name"] for r in roles]
    for name in names:
        if name.strip().lower() == "admin":
            return name
    return names[0] if names else None


def check(sent: dict, model: dict, fragments: dict, base_dir) -> dict:
    """{errors: [sentence], findings: []} for the answer's extensions/*.java. `sent` is {path: text} of those files, `fragments` the
    answer's other json files, `base_dir` the app folder on disk (None for a new app). Every sentence is addressed to the AI."""
    errors: list[str] = []
    flows, roles = _flows_and_roles(model, fragments)
    by_path: dict[str, list[str]] = {}
    for flow in flows:
        entry = _entrypoint(flow)
        if entry:
            by_path.setdefault(entry, []).append(str(flow.get("name")))
    for path, text in sorted(sent.items()):
        name = class_name(path)
        if len(text) > MAX_CHARS:
            errors.append(f"{path} - too long ({len(text)} characters, the limit is {MAX_CHARS}); keep the class small")
        if not re.search(r"\bpublic\s+final\s+class\s+" + re.escape(name) + r"\b", text):
            errors.append(f"{path} - the file must declare `public final class {name}` (the class name is the file name)")
        if not re.search(r"Map\s*<\s*String\s*,\s*Object\s*>\s+execute\s*\(\s*NPDevProcedureContext\s+\w+\s*\)", text):
            errors.append(f"{path} - the class must have `public Map<String, Object> execute(NPDevProcedureContext ctx)`; the context "
                          "offers tenantId(), actorId() and saveMany(concept, records)")
        users = by_path.get(path, [])
        if len(users) != 1:
            errors.append(f"{path} - exactly one flow must name it in its metadata, "
                          f"{{\"untrustedExtensionEntrypoint\": \"{path}\"}} (the flow keeps one placeholder step); "
                          + ("none does" if not users else f"{len(users)} do: {', '.join(users)}"))
    if sent and role_for(roles) is None:
        errors.append("the app declares no role, and a Java class must be bound to one: declare roles[] first (for example an `admin` role)")
    for path, users in sorted(by_path.items()):
        if path in sent:
            continue
        if not (base_dir and (Path(base_dir) / path).is_file()):   # a class the app already has (any path) is fine
            errors.append(f"flow {', '.join(users)} names {path} but the answer does not send that file and the app does not have it; "
                          f"send extensions/<Name>.java or remove the metadata")
    return {"errors": errors, "findings": []}


def manifest_text(root, items: list, model: dict, fragments: dict) -> str:
    """The new text of untrusted-extension-manifest.json: the entries already there, plus one per accepted class (a class sent
    again replaces its entry). `items` is [{path, content}]."""
    path = Path(root) / MANIFEST
    try:
        doc = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        doc = {}
    if not isinstance(doc, dict) or doc.get("schemaVersion") != MANIFEST_SCHEMA or not isinstance(doc.get("entries"), list):
        doc = {"schemaVersion": MANIFEST_SCHEMA, "policyVersion": "relay", "expectedOutcome": "pass", "entries": []}
    flows, roles = _flows_and_roles(model, fragments)
    role = role_for(roles)
    entries = {e.get("relativePath"): e for e in doc["entries"] if isinstance(e, dict)}
    for item in items:
        users = [str(f.get("name")) for f in flows if _entrypoint(f) == item["path"]]
        if len(users) != 1 or role is None:
            raise ValueError(f"{item['path']} is not bound to exactly one flow and one role")
        entries[item["path"]] = {
            "entryId": "flow-" + re.sub(r"[^a-z0-9]+", "-", users[0].lower()).strip("-"),
            "kind": "flow", "relativePath": item["path"], "language": "java", "sha256": _sha(item["content"]),
            "runtimeBinding": f"flow:{users[0]}", "className": class_name(item["path"]), "method": "execute",
            "requiredRole": role, "tenantScoped": True, "inputs": [], "outputs": [], "dependencies": [], "tests": [],
            "truthStatus": "T2"}
    doc["entries"] = [entries[k] for k in sorted(entries, key=str)]
    return json.dumps(doc, indent=2) + chr(10)


def forget(root, rel: str) -> dict:
    """Take one class out of the manifest and say which flows still name it (the model is the author's: it is never edited here).
    Returns {manifest: removed|absent|none, stillNamedBy: [file names]}."""
    base = Path(root)
    path = base / MANIFEST
    state = "none"
    try:
        doc = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        doc = None
    if isinstance(doc, dict) and isinstance(doc.get("entries"), list):
        kept = [e for e in doc["entries"] if not (isinstance(e, dict) and e.get("relativePath") == rel)]
        state = "removed" if len(kept) != len(doc["entries"]) else "absent"
        if state == "removed":
            if kept:
                doc["entries"] = kept
                path.write_text(json.dumps(doc, indent=2) + chr(10), encoding="utf-8")
            else:
                path.unlink()
    named = []
    for candidate in [base / "model.json"] + sorted(base.glob("fragments/*.json")):
        try:
            if rel in candidate.read_text(encoding="utf-8-sig"):
                named.append(candidate.name)
        except OSError:
            continue
    return {"manifest": state, "stillNamedBy": named}
