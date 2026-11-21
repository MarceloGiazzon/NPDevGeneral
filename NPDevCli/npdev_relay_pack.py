"""Relay, intent "pack" (P4.3): what NPDev checks in a browser AI's pack.json before it may be kept.

The pipeline mirrors an app's (PARSED -> validate -> READY / NEEDS_FIX) with the checks a PACK needs:

  1. pack.json is a JSON object and passes pack.schema.json;
  2. its `pack` id is the one the conversation was opened for (the AI never chooses the folder);
  3. no secret in it (the shared redaction rules), no code-binding and no field NPDev fills itself;
  4. a NEW VERSION of an existing pack is newer than it AND its bump is big enough for what changed (the same
     PackPublishGate `pack publish` uses -- ADDITIVE needs a minor, BREAKING a major);
  5. it COMPOSES: a probe consumer model imports it by local `$ref` and the model validator accepts it (so does the
     AI's own example-model.json when it sent one).

Every refusal is a line written for the AI (where - what - how to fix). Nothing is written to a pack workspace here:
that is apply (P4.5). The module takes the engine's validator and the CLI module as arguments, so there is one copy
of each and no import cycle.
"""
from __future__ import annotations

import io
import contextlib
import json
import re
import argparse
from pathlib import Path

import npdev_pack_sheet as pack_sheet
import npdev_redact
import npdev_relay_session as rs

SCHEMA_PATH = Path(__file__).resolve().parent.parent / "NPDevContract" / "schemas" / "pack.schema.json"
# NPDev fills these when a pack is published; an AI that writes them is guessing at a trust or history it cannot know.
NPDEV_OWNED = ("signature", "migrations", "firstPublishedVersion", "forkedFrom")
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
PROBE_ALIAS = "probe"


def _semver(text) -> tuple | None:
    match = SEMVER.match(str(text or "").strip())
    return tuple(int(part) for part in match.groups()) if match else None


# The keys whose string is read by a person on a screen. A pack is published for everyone, so these are English (REG-347).
HUMAN_TEXT_KEYS = ("label", "description", "title", "placeholder", "hint", "message", "emptyText", "displayName")
MAX_LANGUAGE_LINES = 4


def _roles_errors(pack: dict) -> list[str]:
    """REG-350: `roles[]` that name the pack's own concepts can never resolve (inside an application they are `alias::Name`,
    which the author cannot know), so say so first, with the way out, instead of letting the validator's generic line send the
    AI guessing."""
    roles = pack.get("roles")
    own = {c.get("name") for c in pack.get("concepts") or [] if isinstance(c, dict) and c.get("name")}
    if not isinstance(roles, list):
        return []
    for role in roles:
        named = [n for n in (role.get("concepts") or {}) if n in own] if isinstance(role, dict) and isinstance(role.get("concepts"), dict) else []
        if named:
            return [f'pack.json roles - a pack cannot grant its own concept "{named[0]}" to a role by name (the name only exists inside '
                    f'the application that imports it) - remove "roles" and write who may read or write inside each concept\'s '
                    f'"access", for example $user.roles.contains(\'Manager\')']
    return []


def _non_english_texts(node, path: str = "") -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    if isinstance(node, dict):
        for key, value in node.items():
            here = f"{path}/{key}"
            if key in HUMAN_TEXT_KEYS and isinstance(value, str):
                if any(ch.isalpha() and ord(ch) > 127 for ch in value):
                    found.append((here, value))
            else:
                found.extend(_non_english_texts(value, here))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            found.extend(_non_english_texts(value, f"{path}/{index}"))
    return found


def _language_errors(pack: dict) -> list[str]:
    """REG-347: a letter outside plain ASCII in a text a person reads means the AI wrote it in the person's language (an
    accent is the cheap, certain sign). Said once per text, at most MAX_LANGUAGE_LINES of them."""
    found = _non_english_texts(pack)
    lines = [f'pack.json {where} - "{value[:60]}" is not English: every text in a pack is English (it is published for everyone) - '
             f'write it in English' for where, value in found[:MAX_LANGUAGE_LINES]]
    if len(found) > MAX_LANGUAGE_LINES:
        lines.append(f"pack.json - {len(found) - MAX_LANGUAGE_LINES} more texts are not English: translate all of them")
    return lines


def static_errors(pack, pack_id: str, text: str) -> list[str]:
    """Checks 1-3: nothing here needs Java or the network."""
    if not isinstance(pack, dict):
        return ["pack.json - it must be one JSON object - start from the example"]
    lines: list[str] = []
    for key in ("dslVersion", "pack", "version"):  # the schema's `required`, always checked
        if key not in pack:
            lines.append(f'pack.json {key} - this field is required - add "{key}"')
    if SCHEMA_PATH.is_file():
        import npdev_jsonschema

        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        try:
            for item in npdev_jsonschema.validate(schema, pack):
                lines.append(f"pack.json {item.get('path') or '/'} - {item.get('message')}")
        except npdev_jsonschema.UnsupportedSchema:
            # pack.schema.json points into model.schema.json (an external $ref the stdlib validator does not follow):
            # the members' own shape is then checked by the model validator on the probe below.
            pass
    if pack.get("pack") != pack_id:
        lines.append(f'pack.json pack - this conversation is for the pack "{pack_id}", not "{pack.get("pack")}" - '
                     f'write "pack": "{pack_id}"')
    if _semver(pack.get("version")) is None:
        lines.append('pack.json version - a pack version looks like "1.0.0" (three numbers) - write it that way')
    if pack.get("bindings"):
        lines.append("pack.json bindings - a reusable pack must not bind to an adapter of one application - "
                     "remove \"bindings\" and describe the need as a capabilities contract")
    for key in NPDEV_OWNED:
        if key in pack:
            lines.append(f'pack.json {key} - NPDev fills this when the pack is published - remove "{key}"')
    lines.extend(_roles_errors(pack))
    lines.extend(_language_errors(pack))
    _, counts = npdev_redact.redact_text(text, classes=("secret",))
    if counts:
        lines.append("pack.json - it contains something that looks like a key, password or token - "
                     "remove it; a reusable pack never carries secrets")
    return lines


def version_errors(new_pack: dict, base_text: str, work: Path, cli) -> tuple[list[str], dict | None]:
    """Check 4 for a NEW VERSION: (error lines, the publish gate's report or None when it could not run)."""
    try:
        base = json.loads(base_text)
    except ValueError:
        return [], None
    old_v, new_v = _semver(base.get("version")), _semver(new_pack.get("version"))
    if old_v and new_v and new_v <= old_v:
        return [f'pack.json version - the current pack is {base.get("version")}; a new version must be higher - '
                f'raise "version" (patch for a fix, minor for something added, major for something removed or renamed)'], None
    work.mkdir(parents=True, exist_ok=True)
    old_path, new_path, report_path = work / "old-pack.json", work / "new-pack.json", work / "publish-report.json"
    old_path.write_text(base_text, encoding="utf-8")
    new_path.write_text(json.dumps(new_pack, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    args = argparse.Namespace(old_pack=str(old_path), new_pack=str(new_path), out=str(report_path), write=False,
                              push=False, sign_with=None, catalog_repo=None, remote=None)
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            cli.run_pack_publish(args)
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (Exception, SystemExit):  # noqa: BLE001 -- no Gradle/Java here: not the AI's fault, same as the validator
        return [], None
    if report.get("allowed"):
        return [], report
    return [f"pack.json version - {str(report.get('message') or 'the version bump is too small for what changed').strip()}"], report


def probe_model(pack_id: str, pack: dict | None = None) -> dict:
    """The smallest application that imports the pack by the same local `$ref` a consumer uses.

    When the pack itself is known, the probe also satisfies what the pack REQUIRES of an application (the capabilities it
    names) and declares the property scopes its properties are settable at, so the probe never fails for a thing every
    real consumer supplies."""
    model = {"namespace": "relay.probe", "dslVersion": "3.0.0", "version": "1.0",
             "packs": [{"$ref": f"packs/{pack_id}/pack.json", "as": PROBE_ALIAS}],
             # The probe's own concept is opened on purpose: a pack that declares roles makes every concept without an access
             # rule a validation problem, and `Probe` is the relay's, not the author's -- no chat AI can fix it.
             "concepts": [{"name": "Probe", "access": {"read": "true", "write": "true"},
                           "fields": [{"name": "id", "type": "uuid", "id": True, "required": True}]}]}
    if isinstance(pack, dict):
        needed = (pack.get("requires") or {}).get("capabilities") or []
        if needed:
            model["provides"] = {"capabilities": list(needed)}
        scopes = []
        for prop in pack.get("properties") or []:
            for scope in prop.get("settableAt") or []:
                if scope not in scopes:
                    scopes.append(scope)
        if scopes:
            model["propertyScopes"] = [{"name": scope} for scope in scopes]
    return model


def stage_probe(folder: Path, pack_id: str, pack_text: str, model: dict, *, siblings: Path | None = None) -> Path:
    """Write the probe application. `siblings` is a folder of other packs (the pack's own parent folder): the packs this one
    depends on, directly or through each other, are copied beside it so the probe can resolve `packs[]` the way a consumer does."""
    (folder / "packs" / pack_id).mkdir(parents=True, exist_ok=True)
    (folder / "packs" / pack_id / "pack.json").write_text(pack_text, encoding="utf-8")
    if siblings is not None:
        pending = [pack_text]
        while pending:
            try:
                declared = json.loads(pending.pop()).get("packs") or []
            except ValueError:
                declared = []
            for dep in declared:
                dep_id = dep.get("pack") if isinstance(dep, dict) else None
                source = siblings / str(dep_id) / "pack.json" if dep_id else None
                target = folder / "packs" / str(dep_id) / "pack.json"
                if source is not None and source.is_file() and not target.exists():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    dep_text = source.read_text(encoding="utf-8-sig")
                    target.write_text(dep_text, encoding="utf-8")
                    pending.append(dep_text)
    staged = sorted((folder / "packs").glob("*/pack.json"))
    if siblings is not None:
        # Every role('x') a staged pack names must be bound by the application, as any real consumer does.
        import re

        named = sorted({m for f in staged for m in re.findall(r"role\('([^']+)'\)", f.read_text(encoding="utf-8-sig"))})
        bindings = dict(((model.get("provides") or {}).get("roleBindings") or {}))
        for name in named:
            bindings.setdefault(name, "PROBE_" + re.sub(r"[^A-Za-z0-9]+", "_", name).upper())
        if bindings:
            model.setdefault("provides", {})["roleBindings"] = bindings
    path = folder / "model.json"
    path.write_text(json.dumps(model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if len(staged) > 1:
        # A pack graph with transitive dependencies needs an npdev.lock beside the model; a consumer gets it from
        # `npdev pack update`, the probe writes the same entries (resolved version + sha256 of the file bytes).
        import hashlib

        entries = {}
        for pack_file in staged:
            raw = pack_file.read_bytes()
            entries[pack_file.parent.name] = {
                "resolvedVersion": json.loads(raw.decode("utf-8-sig")).get("version"),
                "digest": "sha256:" + hashlib.sha256(raw).hexdigest(),
                "sourcePath": f"packs/{pack_file.parent.name}/pack.json",
            }
        (folder / "npdev.lock").write_text(
            json.dumps({"schemaVersion": "npdev-lock.v1", "packs": entries}, indent=2) + "\n", encoding="utf-8")
    return path


def after_parsed(session: dict, number: int, result: dict, *, validate, cli) -> None:
    """PARSED -> checks 1-5 -> READY / NEEDS_FIX. A machine that cannot validate keeps PARSED (the AI did nothing wrong)."""
    entry = next((f for f in result["files"] if f["path"] == "pack.json" and f["status"] == "allowed"), None)
    if entry is None:
        return
    pack_id = session.get("packId") or ""
    try:
        pack = json.loads(entry["content"])
    except ValueError:
        pack = None
    errors = static_errors(pack, pack_id, entry["content"])
    work = rs.round_dir(session, number)
    base_path = rs.session_dir(session["code"]) / "base-pack.json"
    report = None
    if not errors and session.get("packBase") and base_path.is_file():
        errors, report = version_errors(pack, base_path.read_text(encoding="utf-8"), work / "pack-gate", cli)
    if errors:
        result["outcome"] = "NEEDS_FIX"
        result["errorLines"] = errors
        return
    models = [("probe", probe_model(pack_id, pack))]
    example = next((f for f in result["files"] if f["path"] == "example-model.json" and f["status"] == "allowed"), None)
    if example is not None:
        try:
            models.append(("example-model.json", json.loads(example["content"])))
        except ValueError:
            result["outcome"] = "NEEDS_FIX"
            result["errorLines"] = ["example-model.json - it is not valid JSON - fix it or leave this file out"]
            return
    for label, model in models:
        if not isinstance(model, dict):
            result["outcome"] = "NEEDS_FIX"
            result["errorLines"] = [f"{label} - it must be one JSON object"]
            return
        folder = work / "pack-probe" / label.replace(".json", "")
        path = stage_probe(folder, pack_id, entry["content"], model)
        verdict = validate(path, work / f"validation-{label.replace('.json', '')}.json")
        if verdict is None:
            result["findings"].append({"code": "validator-unavailable", "severity": "warning"})
            return
        if verdict.get("status") == "failed":
            result["outcome"] = "NEEDS_FIX"
            result["errorLines"] = _lines(verdict, label)
            result["validation"] = verdict
            return
    result["validation"] = verdict
    base = None
    if session.get("packBase") and base_path.is_file():
        try:
            base = json.loads(base_path.read_text(encoding="utf-8"))
        except ValueError:
            base = None
    result["pack"] = {"id": pack_id, "version": pack.get("version"), "baseVersion": (base or {}).get("version"),
                      "gate": (report or {}).get("overallClassification")}
    result["preview"] = pack_sheet.plain_preview(pack, base, report)
    result["outcome"] = "READY"


def _lines(report: dict, label: str) -> list[str]:
    lines: list[str] = []
    for item in report.get("diagnostics") or []:
        if item.get("severity", "error") != "error":
            continue
        parts = [str(item.get("path") or ""), str(item.get("message") or ""),
                 str(item.get("hint") or item.get("suggestedFix") or "")]
        line = " - ".join(p.strip() for p in parts if p.strip())
        if line:
            lines.append(line if label == "probe" else f"{label} {line}")
    return lines[:20] or [f"{label} - an application importing this pack does not validate"]
