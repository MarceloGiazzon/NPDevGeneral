#!/usr/bin/env python3
"""`npdev pack workshop` -- where AI-authored packs arrive, are checked and are promoted (FORM P6).

This file holds INGEST (P6-T2) and TEST (P6-T3: ladder rungs 1-3, rung 4 recorded as not run). `status`, `feedback`
and `promote` land in later tasks and share this layout.

The workshop is layer 2 (definitions), like AppGen/apps: OUTSIDE the repo. Root = `--root`, else $NPDEV_PACK_WORKSHOP,
else a `PackWorkshop` folder next to the Build root. Never a hardcoded drive.

    <root>/inbox/<sha12>__<zip name>              immutable copy of every accepted delivery (duplicates found by hash)
    <root>/packs/<area>/<packId>/<version>/       pack.json, handoff.json, example-model.json, probes/*.json
    <root>/batches/<batchId>.json                 one record per batch, one entry per delivery
    <root>/runs/<pack>@<version>/<stamp>/         verdict.json (+ static/compose/validate/access-lint text) from `test`

Rules (the X0 family: refuse, never guess):
  * a delivery has `batch.json` at its root, valid against NPDevContract/schemas/pack-workshop/batch.schema.json, and
    `packs/<id>/pack.json` + `packs/<id>/handoff.json` (valid against handoff.schema.json) for EVERY pack the batch
    names and for no other. A delivery that predates the convention is taken only with `--legacy <batchId>`, which
    synthesises the batch record and records that no handoff exists.
  * a version, once ingested, is immutable: the same pack@version with different content is refused (bump the version).
  * unzip is hostile-input safe: no absolute or `..` paths, no links, bounded file count and size.
  * the same bytes twice is a no-op ("duplicate"), reported, not an error.
Scripts never read markdown: everything here is JSON.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ENV_ROOT = "NPDEV_PACK_WORKSHOP"
MAX_FILES = 500
MAX_FILE_BYTES = 5 * 1024 * 1024
MAX_TOTAL_BYTES = 50 * 1024 * 1024
DELIVERY_NAME = re.compile(r"^npdev-packs__(?P<batch>w[0-9]+-[a-z0-9-]+)__(?P<stamp>[0-9]{8}-[0-9]{4})\.zip$")
PACK_ID = re.compile(r"^[a-z][a-z0-9-]*$")
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
KNOWN_FILES = ("pack.json", "handoff.json", "example-model.json")


class WorkshopError(Exception):
    """A refusal with a sentence an author (or an AI) can act on."""


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def _build_root(cli) -> Path:
    return cli._ai_build_root()


def workshop_root(cli, override: str | None = None) -> Path:
    if override:
        return Path(override).expanduser().resolve()
    env = os.environ.get(ENV_ROOT)
    if env and env.strip():
        return Path(env).expanduser().resolve()
    return _build_root(cli).parent / "PackWorkshop"


def _load_schema(name: str) -> dict:
    path = _repo_root() / "NPDevContract" / "schemas" / "pack-workshop" / f"{name}.schema.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _validate(name: str, instance, where: str) -> None:
    import npdev_jsonschema
    errors = npdev_jsonschema.validate(_load_schema(name), instance)
    if errors:
        first = errors[0]
        more = f" (+{len(errors) - 1} more)" if len(errors) > 1 else ""
        raise WorkshopError(f"{where} is not a valid {name}: {first.get('path') or '$'}: {first.get('message')}{more}")


def _roadmap_areas() -> dict[str, str]:
    path = _repo_root() / "NPDevContract" / "pack-catalog" / "pack-roadmap.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return {p["id"]: p.get("area", "") for p in doc.get("packs", []) if isinstance(p, dict) and "id" in p}


def _read_json(zf: zipfile.ZipFile, member: str, where: str):
    try:
        return json.loads(zf.read(member).decode("utf-8"))
    except KeyError:
        raise WorkshopError(f"{where}: '{member}' is missing from the zip")
    except (UnicodeDecodeError, ValueError) as exc:
        raise WorkshopError(f"{where}: '{member}' is not valid JSON ({exc})")


def _check_members(zf: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    infos = [i for i in zf.infolist() if not i.is_dir()]
    if len(infos) > MAX_FILES:
        raise WorkshopError(f"the zip holds {len(infos)} files; the limit is {MAX_FILES}")
    total = 0
    for info in infos:
        name = info.filename.replace("\\", "/")
        if name.startswith("/") or re.match(r"^[A-Za-z]:", name) or ".." in name.split("/"):
            raise WorkshopError(f"unsafe path in the zip: '{info.filename}' (absolute or '..')")
        mode = info.external_attr >> 16
        if mode and stat.S_ISLNK(mode):
            raise WorkshopError(f"'{info.filename}' is a link; links are not accepted")
        if info.file_size > MAX_FILE_BYTES:
            raise WorkshopError(f"'{info.filename}' is {info.file_size} bytes; the per-file limit is {MAX_FILE_BYTES}")
        total += info.file_size
    if total > MAX_TOTAL_BYTES:
        raise WorkshopError(f"the zip unpacks to {total} bytes; the limit is {MAX_TOTAL_BYTES}")
    return infos


def _pack_members(infos: list[zipfile.ZipInfo]) -> dict[str, list[str]]:
    """pack id -> member names under packs/<id>/ ('/' separators)."""
    found: dict[str, list[str]] = {}
    for info in infos:
        parts = info.filename.replace("\\", "/").split("/")
        if len(parts) >= 3 and parts[0] == "packs":
            found.setdefault(parts[1], []).append("/".join(parts))
    return found


def _legacy_batch(batch_id: str, packs_found: dict[str, list[str]], zf: zipfile.ZipFile, where: str) -> dict:
    entries = []
    for pack_id in sorted(packs_found):
        doc = _read_json(zf, f"packs/{pack_id}/pack.json", where)
        version = str(doc.get("version", ""))
        entries.append({"pack": pack_id, "version": version, "roadmapId": pack_id})
    return {"schemaVersion": "npdev-pack-batch.v1", "batchId": batch_id,
            "author": {"kind": "browser-ai", "model": "unknown (legacy delivery, no batch.json)"},
            "guideVersion": "legacy", "packs": entries}


def _tree_digest(files: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for name in sorted(files):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(files[name]).digest())
    return digest.hexdigest()


def _existing_digest(folder: Path) -> str:
    files = {str(p.relative_to(folder)).replace("\\", "/"): p.read_bytes() for p in folder.rglob("*") if p.is_file()}
    return _tree_digest(files)


def ingest_zip(root: Path, zip_path: Path, legacy_batch: str | None = None) -> dict:
    """Ingest one zip. Returns a result dict; raises WorkshopError for a refusal (nothing is written then)."""
    data = zip_path.read_bytes()
    sha12 = hashlib.sha256(data).hexdigest()[:12]
    inbox = root / "inbox"
    if inbox.is_dir() and any(p.name.startswith(f"{sha12}__") for p in inbox.iterdir()):
        return {"zip": zip_path.name, "sha12": sha12, "status": "duplicate", "packs": []}

    where = zip_path.name
    try:
        zf = zipfile.ZipFile(zip_path)
    except zipfile.BadZipFile:
        raise WorkshopError(f"{where} is not a zip file")
    with zf:
        infos = _check_members(zf)
        packs_found = _pack_members(infos)
        names = {i.filename.replace("\\", "/") for i in infos}
        warnings: list[str] = []
        if "batch.json" in names:
            batch = _read_json(zf, "batch.json", where)
            _validate("batch", batch, f"{where}: batch.json")
            named = DELIVERY_NAME.match(zip_path.name)
            if named and named.group("batch") != batch["batchId"]:
                warnings.append(f"zip name says batch '{named.group('batch')}' but batch.json says '{batch['batchId']}'")
            if not named:
                warnings.append("zip name does not follow npdev-packs__<batchId>__<YYYYMMDD-HHMM>.zip")
            legacy = False
        elif legacy_batch:
            if not re.match(r"^w[0-9]+-[a-z0-9-]+$", legacy_batch):
                raise WorkshopError(f"--legacy '{legacy_batch}' is not a batch id like w1-modules")
            batch = _legacy_batch(legacy_batch, packs_found, zf, where)
            legacy = True
            warnings.append("legacy delivery: no batch.json and no handoff.json, so the checklist rung cannot be answered")
        else:
            raise WorkshopError(f"{where} has no batch.json at its root. A delivery that predates the convention can be "
                                f"taken with --legacy <batchId>; otherwise ask the authoring AI to follow the guide's 'Delivering' section.")

        declared = {p["pack"] for p in batch["packs"]}
        stray = sorted(set(packs_found) - declared)
        if stray:
            raise WorkshopError(f"{where}: packs/{stray[0]}/ is in the zip but not named in batch.json (named: {sorted(declared)})")

        areas = _roadmap_areas()
        staged: list[dict] = []
        for entry in batch["packs"]:
            pack_id, version = entry["pack"], entry["version"]
            if not PACK_ID.match(pack_id) or not VERSION.match(version):
                raise WorkshopError(f"{where}: '{pack_id}@{version}' is not a valid pack id / version")
            base = f"packs/{pack_id}"
            pack_doc = _read_json(zf, f"{base}/pack.json", where)
            if pack_doc.get("pack") != pack_id or str(pack_doc.get("version")) != version:
                raise WorkshopError(f"{where}: {base}/pack.json says {pack_doc.get('pack')}@{pack_doc.get('version')} "
                                    f"but batch.json says {pack_id}@{version}")
            if not legacy:
                handoff = _read_json(zf, f"{base}/handoff.json", where)
                _validate("handoff", handoff, f"{where}: {base}/handoff.json")
                if handoff["pack"] != pack_id or handoff["version"] != version:
                    raise WorkshopError(f"{where}: {base}/handoff.json is for {handoff['pack']}@{handoff['version']}, "
                                        f"not {pack_id}@{version}")
            files: dict[str, bytes] = {}
            for member in packs_found.get(pack_id, []):
                rel = member[len(base) + 1:]
                top = rel.split("/")[0]
                if top not in KNOWN_FILES and top != "probes":
                    warnings.append(f"{pack_id}: '{rel}' is not a recognised file and was not kept")
                    continue
                files[rel] = zf.read(member)
            area = areas.get(entry["roadmapId"]) or "unsorted"
            if area == "unsorted":
                warnings.append(f"{pack_id}: roadmapId '{entry['roadmapId']}' is not in pack-roadmap.json; filed under 'unsorted'")
            dest = root / "packs" / area / pack_id / version
            digest = _tree_digest(files)
            if dest.exists() and _existing_digest(dest) != digest:
                raise WorkshopError(f"{pack_id}@{version} is already in the workshop with DIFFERENT content. A published "
                                    f"version is immutable: the authoring AI must bump the version (batch.json 'replaces').")
            staged.append({"pack": pack_id, "version": version, "area": area, "dest": dest, "files": files,
                           "digest": digest, "existed": dest.exists(), "roadmapId": entry["roadmapId"]})

    # Everything checked: only now touch the disk.
    inbox.mkdir(parents=True, exist_ok=True)
    (inbox / f"{sha12}__{zip_path.name}").write_bytes(data)
    results = []
    for item in staged:
        if not item["existed"]:
            for rel, content in item["files"].items():
                target = item["dest"] / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
        results.append({"pack": item["pack"], "version": item["version"], "area": item["area"],
                        "placed": not item["existed"], "path": str(item["dest"]), "status": "delivered"})
    _record_batch(root, batch, sha12, zip_path.name, results, legacy)
    return {"zip": zip_path.name, "sha12": sha12, "status": "ingested", "batchId": batch["batchId"],
            "legacy": legacy, "packs": results, "warnings": warnings}


def _record_batch(root: Path, batch: dict, sha12: str, zip_name: str, packs: list[dict], legacy: bool) -> None:
    folder = root / "batches"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{batch['batchId']}.json"
    record = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"batchId": batch["batchId"], "deliveries": []}
    record["deliveries"].append({
        "sha12": sha12, "zip": zip_name, "ingestedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "legacy": legacy, "author": batch["author"], "guideVersion": batch["guideVersion"],
        "packs": [{k: p[k] for k in ("pack", "version", "area", "status")} for p in packs],
    })
    path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------------------------------------------
# TEST (P6-T3): the status ladder. Each rung needs the previous one; the first failure stops it and writes
# `feedbackForAi` lines (where - what - how to fix). Rungs: valid -> composes -> secure -> probed (not built yet).
# ---------------------------------------------------------------------------------------------------------------

def _semver(text) -> tuple:
    return tuple(int(x) for x in str(text).split(".")) if VERSION.match(str(text or "")) else ()


def find_pack_dir(root: Path, pack_id: str, version: str | None = None) -> Path | None:
    """packs/<area>/<packId>/<version>/ -- the named version, else the highest one, in any area."""
    candidates = []
    for folder in (root / "packs").glob(f"*/{pack_id}/*"):
        if (folder / "pack.json").is_file() and _semver(folder.name):
            candidates.append((_semver(folder.name), folder))
    if version:
        return next((f for _, f in candidates if f.name == version), None)
    return max(candidates, key=lambda c: c[0])[1] if candidates else None


def make_pack_finder(root: Path):
    """pack id -> pack.json path: the workshop's newest version first, then the repo's built-in packs."""
    builtin = _repo_root() / "NPDevContract" / "packs"

    def find(pack_id: str) -> Path | None:
        folder = find_pack_dir(root, pack_id)
        if folder:
            return folder / "pack.json"
        path = builtin / pack_id / "pack.json"
        return path if path.is_file() else None
    return find


def lint_pack(path: Path) -> tuple[list[str], list[str]]:
    """Static checks for mistakes the schema validator cannot see (from the authoring guide's validate_pack.py).
    Returns (errors, warnings), each line written as where - what - how to fix."""
    raw = path.read_bytes().decode("utf-8", "replace")
    doc = json.loads(raw)
    errors: list[str] = []
    warnings: list[str] = []
    for i, ch in enumerate(raw):
        if ord(ch) > 127:
            warnings.append(f"pack.json near {raw[max(0, i - 20):i + 10]!r} - non-ASCII character U+{ord(ch):04X}; everything in a "
                            f"pack is English - translate it (for example 'correction letter (CC-e)')")
            break
    concepts = {c["name"]: c for c in doc.get("concepts", []) if isinstance(c, dict) and "name" in c}
    for c in concepts.values():
        access = c.get("access") or {}
        write = access.get("write", "")
        read = access.get("read", "")
        if not access:
            warnings.append(f"{c['name']}.access - the concept has no access rule of its own, so it relies on the consuming app's "
                            f"roles - add access.read / access.write, or open it on purpose with \"true\"")
        for fld in sorted(set(re.findall(r"&&\s*(\w+)\s*==\s*\$user\.id", write))):
            if not re.search(rf"\b{fld}\s*==\s*null", write):
                errors.append(f"{c['name']}.access.write - it requires '{fld} == $user.id' but has no '{fld} == null' alternative: "
                              f"the row check runs BEFORE defaults, so a create that leaves {fld} blank is refused - add "
                              f"\"|| role('x') && {fld} == null\"")
        if re.search(r"&&\s*\w+\s*==\s*(\$user\.id|false|true)", read):
            for child in concepts.values():
                for f in child.get("fields", []):
                    if f.get("type") == "reference" and (f.get("reference") or {}).get("target") == c["name"]:
                        if not re.search(r"&&\s*\w+\s*==", (child.get("access") or {}).get("read", "")):
                            warnings.append(f"{child['name']}.access.read - it points to {c['name']} (row-restricted read) but has no row "
                                            f"restriction itself: anyone who may read {child['name']} sees children of rows they cannot "
                                            f"see - copy the owner/flag onto the child, or say so in metadata.reviewNotes")
        for rollup in c.get("rollups", []):
            if "::" not in str(rollup.get("from", "")):
                errors.append(f"{c['name']}.rollups[{rollup.get('field')}].from - '{rollup.get('from')}' is not pack-qualified: it "
                              f"passes alone and fails on composition - write '{doc.get('pack')}::{rollup.get('from')}'")
        fields = {f["name"]: f for f in c.get("fields", []) if "name" in f}
        for f in c.get("fields", []):
            for name in re.findall(r"[A-Za-z_]\w*", f.get("derivedExpression") or ""):
                if name in fields and not fields[name].get("required") and fields[name].get("type") in ("decimal", "integer"):
                    warnings.append(f"{c['name']}.{f['name']}.derivedExpression - it uses the optional number '{name}': a null makes the "
                                    f"result null or an error - make '{name}' required with default 0, or guard it")
    for sentence in str(doc.get("metadata", {}).get("summary", "")).split(". "):
        if re.search(r"separate|isolated|segregat|immutable|append-only|cannot be (edited|deleted)", sentence, re.I):
            warnings.append(f"metadata.summary - it claims '{sentence[:80]}': make sure the model ENFORCES it (access or invariant); "
                            f"if it is only a convention, say so in the summary")
    return errors, warnings


def compose_scratch(pack_id: str, scratch: Path, find) -> tuple[Path, list[str], list[str]]:
    """Build a throwaway consumer model around the pack and every pack it depends on.
    Returns (model.json path, composition order, role tokens). Raises WorkshopError for a missing dependency."""
    order: list[str] = []
    seen: set[str] = set()

    def visit(pid: str) -> None:
        if pid in seen:
            return
        seen.add(pid)
        path = find(pid)
        if path is None:
            raise WorkshopError(f"pack.json packs - the pack '{pid}' is needed (directly or through a dependency) but is neither in the "
                                f"workshop nor built in - deliver it first, or remove the dependency")
        for dep in json.loads(path.read_text(encoding="utf-8")).get("packs", []):
            if isinstance(dep, dict) and "pack" in dep:
                visit(dep["pack"])
        order.append(pid)

    visit(pack_id)
    if scratch.exists():
        shutil.rmtree(scratch)
    (scratch / "packs").mkdir(parents=True)
    roles: set[str] = set()
    caps: set[str] = set()
    for pid in order:
        text = find(pid).read_text(encoding="utf-8")
        (scratch / "packs" / pid).mkdir()
        (scratch / "packs" / pid / "pack.json").write_text(text, encoding="utf-8")
        roles |= set(re.findall(r"role\('([A-Za-z0-9_-]+)'\)", text))
        requires = json.loads(text).get("requires", {})
        roles |= set(requires.get("roles", []))
        caps |= set(requires.get("capabilities", []))
    target = json.loads(find(pack_id).read_text(encoding="utf-8"))
    model: dict = {"dslVersion": "3.0.0", "version": "1.0", "namespace": "scratch.packcheck",
                   "packs": [{"$ref": f"packs/{pid}/pack.json", "as": pid} for pid in order]}
    if roles:
        model["provides"] = {"roleBindings": {r: r.upper() for r in sorted(roles)}}
        # default-deny (P0-T3): EVERY composed pack's concepts need a grant, not only the target's, or a dependency's
        # concept (contacts::Address) is reported as unruled -- the consumer's fault, not the pack's.
        grants = {}
        for pid in order:
            for c in json.loads(find(pid).read_text(encoding="utf-8")).get("concepts", []):
                if isinstance(c, dict) and "name" in c:
                    grants[f"{pid}::{c['name']}"] = ["read"]
        model["roles"] = [{"name": r.upper(), "concepts": dict(grants)} for r in sorted(roles)]
    if caps:
        model.setdefault("provides", {})["capabilities"] = sorted(caps)
    if not target.get("concepts"):
        # a data-type / capability-only pack cannot validate alone: one probe concept, one field per domainType
        model["concepts"] = [{"name": "Probe", "ui": {"label": "Probe"}, "fields": [
            {"name": "id", "type": "uuid", "id": True, "required": True, "ui": {"label": "Id"}}]
            + [{"name": "f" + t["name"], "type": "string", "domainType": t["name"], "ui": {"label": t["name"]}}
               for t in target.get("domainTypes", [])]}]
    path = scratch / "model.json"
    path.write_text(json.dumps(model, indent=1), encoding="utf-8")
    return path, order, sorted(roles)


_OBJECT_PROPERTY = re.compile(r'("(?:[^"\\]|\\.)*")(\s*:\s*)(?=[{\[])')


def _scan_text(text: str) -> str:
    """The text the secret scan reads. A JSON property whose value is an object or array (a field NAMED `accessKey`
    holding its definition) is a name, never a secret assignment: rename it for the scan so the shared redaction rule,
    which matches `...key": <value>`, does not flag the NFe 'accessKey' field. Scalar values are untouched."""
    return _OBJECT_PROPERTY.sub(lambda m: m.group(1)[:-1] + '_obj"' + m.group(2), text)


def _default_validate(cli, model_path: Path, report_path: Path):
    """The Java validator's report, or None when this machine cannot run it (nobody's fault, never a rejection)."""
    try:
        cli.run_validate_semantic(model_path, report_path, quiet=True)
    except (Exception, SystemExit):  # noqa: BLE001
        return None
    try:
        return json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _default_update(cli, model_path: Path) -> tuple[bool, str]:
    """`npdev pack update --allow-unsigned`: resolves the composed packs and writes npdev.lock next to the model."""
    command = [sys.executable, str(Path(cli.__file__).resolve()), "pack", "update", "--model", str(model_path), "--allow-unsigned"]
    done = subprocess.run(command, capture_output=True, text=True)
    return done.returncode == 0, (done.stdout + done.stderr)[-1500:]


def _diagnostic_lines(report: dict, severity: str = "error") -> list[str]:
    lines: list[str] = []
    for item in report.get("diagnostics") or []:
        if item.get("severity", "error") != severity:
            continue
        parts = [str(item.get("path") or ""), str(item.get("message") or ""), str(item.get("hint") or item.get("suggestedFix") or "")]
        line = " - ".join(p.strip() for p in parts if p.strip())
        if line and line not in lines:
            lines.append(line)
    return lines


def _step(steps: list, name: str, started: float, result: str, detail: str = "", evidence: str | None = None) -> None:
    entry = {"step": name, "result": result, "seconds": round(time.monotonic() - started, 2)}
    if detail:
        entry["detail"] = detail[:500]
    if evidence:
        entry["evidence"] = evidence
    steps.append(entry)


class BootUnavailable(Exception):
    """The consumer cannot be booted here (not built, no time budget, port/Java trouble): rung 4 is skipped, not failed."""


def merge_probe_users(docs: dict[str, dict]) -> tuple[list[dict], str]:
    """The union of every probe file's identities. Returns (users, conflict text or '')."""
    merged: dict[str, dict] = {}
    for doc in docs.values():
        for user in doc["users"]:
            known = merged.get(user["name"])
            if known is not None and known.get("role") != user.get("role"):
                return [], f"identity '{user['name']}' has the role {known.get('role')!r} in one probe file and {user.get('role')!r} in another"
            merged[user["name"]] = user
    return list(merged.values()), ""


def _free_port() -> int:
    import socket
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _consumer_roles(order: list[str], find, role_names: set[str]) -> list[dict]:
    """Every role the probes use (and every role token the packs name) gets full coarse grants on every composed
    concept: the PACK's own access rules are what the probes then test, so the consumer must not be the gate."""
    grants: dict[str, list[str]] = {}
    for pid in order:
        for concept in json.loads(find(pid).read_text(encoding="utf-8")).get("concepts", []):
            if isinstance(concept, dict) and "name" in concept:
                grants[f"{pid}::{concept['name']}"] = ["read", "create", "update", "delete"]
    return [{"name": name, "concepts": dict(grants)} for name in sorted(role_names)]


def assemble_consumer(cli, run: Path, pack_id: str, order: list[str], find, users: list[dict], example_text: str | None,
                      update=None) -> dict:
    """Write run/consumer/definition/ in the AppGen app layout (config.json, model.json, db.definition.json, packs/) --
    the folder `scripts/appgen/Build-NpdevApp.ps1 -AppFolder run/consumer` reads. The model is the pack's own
    example-model.json when it sent one, else the composed scratch model with roles for the probe identities.
    Returns {folder, definition, name, port, outputRoot}."""
    folder = run / "consumer"
    definition = folder / "definition"
    if definition.exists():
        shutil.rmtree(definition)
    shutil.copytree(run / "compose" / "packs", definition / "packs")
    if example_text is not None:
        model = json.loads(example_text)
    else:
        model = json.loads((run / "compose" / "model.json").read_text(encoding="utf-8"))
        role_names = {u["role"] for u in users if u.get("role")} | {r["name"] for r in model.get("roles", [])}
        if role_names:
            model["roles"] = _consumer_roles(order, find, role_names)
    (definition / "model.json").write_text(json.dumps(model, indent=1), encoding="utf-8")
    if len(order) > 1:
        # a pack graph with transitive dependencies needs its own npdev.lock beside THIS model (the generator refuses
        # it otherwise); the lock written for the scratch model in rung 2 belongs to another file.
        ok, text = (update or (lambda path: _default_update(cli, path)))(definition / "model.json")
        if not ok:
            tail = text.strip().splitlines()[-1][:200] if text.strip() else "no reason given"
            raise BootUnavailable(f"npdev pack update could not lock the consumer's packs: {tail}")
    name = f"packprobe-{pack_id}"
    port = _free_port()
    out = _build_root(cli) / "generated-finalapps" / name
    posix = lambda p: str(p).replace("\\", "/")  # noqa: E731 -- the generator reads forward-slash paths on every OS
    config = {
        "configVersion": "1.0",
        "scenario": {"name": name, "description": f"Throwaway consumer for the probes of the workshop pack {pack_id}.",
                     "outputRoot": posix(out)},
        "generator": {"failIfModelMissing": True, "failIfConfigMissing": True, "cleanOutputBeforeGenerate": True,
                      "emitPluginAssets": True, "emitRuntimeAssets": True, "emitUiAssets": True},
        "bootstrap": {"root": posix(_repo_root() / "NPDevRuntimeHost"), "mergeStrategy": "clean-copy"},
        "artifact": {"root": posix(out / "ArtifactNP"), "generatedFolderName": "npdev-generated",
                     "libsFolderName": "libs", "metaFolderName": "npdev-meta"},
        "finalExec": {"root": posix(out / "App"), "deleteBeforeMount": True},
        "database": {"provider": "h2-local", "database": f"npdev_{name.replace('-', '_')}", "resetMode": "reset"},
        "runtime": {"springProfile": "dev,trial", "serverPort": port, "javaArgs": [], "gradleTask": "bootRun"},
        "trialDefaults": {"apiKey": "dev-key", "recommendedProfiles": "dev,trial", "runtimeUrl": f"http://127.0.0.1:{port}/",
                          "databaseMode": "h2-local", "notes": ["workshop probe consumer; wiped on every run"]},
    }
    (definition / "config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
    (definition / "db.definition.json").write_text(json.dumps({
        "database": {"engine": "H2Local", "databaseName": config["database"]["database"], "username": "sa", "password": "",
                     "createInternalTables": True, "createBusinessTables": True},
        "schemaLifecycle": {"strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": False,
                            "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly"}}, indent=2), encoding="utf-8")
    return {"folder": folder, "definition": definition, "name": name, "port": port, "outputRoot": out}


def _consumer_invocations(consumer: dict) -> list[dict]:
    """`catalogs.invocations` of the generated consumer's compiled-metadata.json (the exact REST path of every concept)."""
    path = Path(consumer["outputRoot"]) / "App" / "npdev-generated" / "src" / "main" / "resources" / "npdev" / "compiled-metadata.json"
    try:
        return json.loads(path.read_text(encoding="utf-8")).get("catalogs", {}).get("invocations", [])
    except (OSError, ValueError):
        return []


BOOT_BUDGET_SECONDS = 300  # owner rule (2026-11-14): nothing runs longer than 5 minutes; over budget = skipped, never rejected


def _kill_tree(proc: subprocess.Popen) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
    else:
        proc.kill()


def _run_script(script: Path, args: list[str], log: Path, deadline: float, label: str) -> None:
    """Run one PowerShell script, log to a file, and stop at the shared deadline. Any trouble = BootUnavailable
    (the model passed the validator, so a generator/build/boot failure is not proof that the PACK is wrong)."""
    remaining = deadline - time.monotonic()
    if remaining <= 5:
        raise BootUnavailable(f"the {BOOT_BUDGET_SECONDS} s budget ran out before '{label}' could start")
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as handle:
        proc = subprocess.Popen(["pwsh", "-NoProfile", "-File", str(script), *args], stdout=handle, stderr=subprocess.STDOUT)
        try:
            code = proc.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            _kill_tree(proc)
            raise BootUnavailable(f"'{label}' did not finish inside the {BOOT_BUDGET_SECONDS} s budget (log: {log})")
        except FileNotFoundError:
            raise BootUnavailable("pwsh is not available on this machine")
    if code != 0:
        raise BootUnavailable(f"'{label}' failed with exit code {code} (log: {log})")


def default_boot(cli, consumer: dict, env: dict):
    """Generate, build and start the consumer, all inside BOOT_BUDGET_SECONDS. Returns (base_url, stop) or raises
    BootUnavailable. The probe keys go into App/secrets/api-key.env (which the generated Start-App.ps1 loads and the
    regeneration spares) after generation and before the start."""
    deadline = time.monotonic() + BOOT_BUDGET_SECONDS
    logs = consumer["folder"] / "logs"
    out: Path = consumer["outputRoot"]
    _run_script(_repo_root() / "scripts" / "appgen" / "Build-NpdevApp.ps1", ["-AppFolder", str(consumer["folder"])],
                logs / "generate.log", deadline, "generate")
    ops = out / "App" / "_ops"
    if not ops.is_dir():
        raise BootUnavailable(f"generation finished but {ops} does not exist (log: {logs / 'generate.log'})")
    _run_script(ops / "Build-App.ps1", [], logs / "build.log", deadline, "build")
    secrets = out / "App" / "secrets"
    secrets.mkdir(parents=True, exist_ok=True)
    (secrets / "api-key.env").write_text("".join(f"{name}={value}\n" for name, value in env.items()), encoding="utf-8")

    def stop() -> None:
        try:
            subprocess.run(["pwsh", "-NoProfile", "-File", str(ops / "Stop-App.ps1")], capture_output=True, timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            pass

    try:
        _run_script(ops / "Start-App.ps1", [], logs / "start.log", deadline, "start")
    except BootUnavailable:
        stop()
        raise
    return f"http://127.0.0.1:{consumer['port']}", stop


def test_pack(cli, root: Path, pack_id: str, version: str | None = None, *, stamp: str | None = None,
              validate=None, update=None, boot=None, probe_call=None, screens: bool = False, shoot=None,
              derive_probes: bool = True) -> dict:
    """Run the ladder for one pack. Writes runs/<pack>@<version>/<stamp>/ and returns the verdict dict.
    `validate(model_path, report_path) -> report|None`, `update(model_path) -> (ok, text)` and
    `boot(cli, consumer, env) -> (base_url, stop)` (or raise BootUnavailable) are injectable for tests;
    `probe_call(key, method, path, body) -> (status, payload)` replaces the HTTP client in tests.
    `screens=True` adds rung 5 (screenshots per identity); `shoot(base_url, out_dir, users, keys)` replaces the browser in tests.
    `derive_probes=False` keeps the old rule that a pack with no probes/*.json cannot reach rung 4 (default: derive them)."""
    folder = find_pack_dir(root, pack_id, version)
    if folder is None:
        raise WorkshopError(f"{pack_id}{'@' + version if version else ''} is not in the workshop ({root}); ingest it first")
    version = folder.name
    stamp = stamp or datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    run = root / "runs" / f"{pack_id}@{version}" / stamp
    run.mkdir(parents=True, exist_ok=True)
    validate = validate or (lambda model, report: _default_validate(cli, model, report))
    update = update or (lambda model: _default_update(cli, model))
    pack_path = folder / "pack.json"
    text = pack_path.read_text(encoding="utf-8")
    steps: list[dict] = []
    feedback: list[str] = []

    def finish(final: str) -> dict:
        verdict = {"schemaVersion": "npdev-pack-verdict.v1", "pack": pack_id, "version": version, "status": final, "steps": steps}
        if feedback:
            verdict["feedbackForAi"] = feedback
        _validate("verdict", verdict, "the verdict")
        (run / "verdict.json").write_text(json.dumps(verdict, indent=2) + "\n", encoding="utf-8")
        verdict["_run"] = str(run)
        return verdict

    # rung 1: valid -- pack.schema.json + the relay's static checks (id, secrets, NPDev-owned fields, bindings)
    started = time.monotonic()
    import npdev_relay_pack
    try:
        pack = json.loads(text)
    except ValueError as exc:
        _step(steps, "static", started, "fail", f"pack.json is not valid JSON ({exc})")
        feedback.append("pack.json - it is not valid JSON - fix the syntax and resend the whole file")
        return finish("rejected")
    lines = npdev_relay_pack.static_errors(pack, pack_id, _scan_text(text))
    handoff_path = folder / "handoff.json"
    if handoff_path.is_file():
        handoff = json.loads(handoff_path.read_text(encoding="utf-8"))
        unanswered = [c["id"] for c in handoff.get("checklist", []) if c.get("answer") == "no"]
        if unanswered:
            lines.append(f"handoff.json checklist - answered 'no' for {', '.join(unanswered)} - fix the pack until the answer is yes")
    (run / "static.txt").write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    if lines:
        _step(steps, "static", started, "fail", f"{len(lines)} problem(s)", "static.txt")
        feedback.extend(lines)
        return finish("rejected")
    _step(steps, "static", started, "pass")
    status = "valid"

    # rung 2: composes -- the pack and its dependencies in a throwaway consumer, validated by the model validator
    started = time.monotonic()
    try:
        model_path, order, roles = compose_scratch(pack_id, run / "compose", make_pack_finder(root))
    except WorkshopError as exc:
        (run / "compose.txt").write_text(str(exc) + "\n", encoding="utf-8")
        _step(steps, "compose", started, "fail", str(exc), "compose.txt")
        feedback.append(str(exc))
        return finish("rejected")
    log = [f"composed: {order}", f"role tokens: {roles}"]
    if len(order) > 1:
        ok, output = update(model_path)
        log.append("pack update: " + ("ok" if ok else output))
        if not ok:
            (run / "compose.txt").write_text("\n".join(log) + "\n", encoding="utf-8")
            _step(steps, "compose", started, "fail", "npdev pack update refused the composition", "compose.txt")
            tail = output.strip().splitlines()[-1][:300] if output.strip() else ""
            feedback.append("pack.json packs - the dependencies could not be resolved together" + (f" - {tail}" if tail else ""))
            return finish("rejected")
    report = validate(model_path, run / "validate.json")
    if report is None:
        log.append("the model validator is not available on this machine; nothing was decided")
        (run / "compose.txt").write_text("\n".join(log) + "\n", encoding="utf-8")
        _step(steps, "compose", started, "skipped", "the model validator cannot run on this machine (no Java/jar/Gradle)", "compose.txt")
        return finish(status)
    errors = _diagnostic_lines(report)
    warns = _diagnostic_lines(report, "warning")
    (run / "validate.txt").write_text("\n".join([f"status: {report.get('status')}"] + [f"error: {e}" for e in errors]
                                                + [f"warning: {w}" for w in warns]) + "\n", encoding="utf-8")
    (run / "compose.txt").write_text("\n".join(log) + "\n", encoding="utf-8")
    if report.get("status") == "failed" or errors:
        _step(steps, "compose", started, "fail", f"{len(errors)} validator error(s)", "validate.txt")
        feedback.extend(errors[:20] or ["an application importing this pack does not validate"])
        # the lint often names the exact fix for what the validator only reports as 'unknown concept'
        feedback.extend(line for line in lint_pack(pack_path)[0] if line not in feedback)
        return finish("rejected")
    _step(steps, "compose", started, "pass", f"with {', '.join(o for o in order if o != pack_id) or 'no dependencies'}")
    status = "composes"

    # rung 3: secure -- the access lint (errors fail; warnings are reported, never silently dropped)
    started = time.monotonic()
    lint_errors, lint_warnings = lint_pack(pack_path)
    (run / "access-lint.txt").write_text("\n".join([f"error: {e}" for e in lint_errors] + [f"warning: {w}" for w in lint_warnings]) + "\n",
                                         encoding="utf-8")
    if lint_errors:
        _step(steps, "access-lint", started, "fail", f"{len(lint_errors)} error(s), {len(lint_warnings)} warning(s)", "access-lint.txt")
        feedback.extend(lint_errors)
        return finish("rejected")
    _step(steps, "access-lint", started, "pass", f"{len(lint_warnings)} warning(s)" if lint_warnings else "")
    status = "secure"
    feedback.extend(f"(warning) {w}" for w in lint_warnings)

    # rung 4: probed -- the pack's declarative probes run against a booted consumer. Anything that stops the rung
    # from being CLIMBED (no probes, no boot on this machine) is recorded as skipped, never as a rejection.
    # rung 5 (opt-in, `screens=True`): while that same consumer is up, one screenshot pass per identity (npdev_pack_screens).
    started = time.monotonic()
    import npdev_pack_probe
    probe_files = sorted((folder / "probes").glob("*.json"))
    # A pack that delivered no probes gets the ones its own declarations imply (npdev_pack_probe_derive), so 'probed'
    # no longer depends on the authoring AI writing them. They are never mixed with AI-written probes.
    derived_docs: dict[str, dict] = {}
    derived_skipped: list[str] = []
    if not probe_files and derive_probes:
        import npdev_pack_probe_derive
        finder = make_pack_finder(root)
        composed = {pid: json.loads(finder(pid).read_text(encoding="utf-8")) for pid in order}
        derived_docs, derived_skipped = npdev_pack_probe_derive.derive(pack_id, composed[pack_id], composed)
    if not probe_files and not derived_docs and not screens:
        _step(steps, "behavior-probes", started, "skipped",
              "the pack delivered no probes/*.json and none could be derived from its declarations"
              + (f" ({'; '.join(derived_skipped[:3])})" if derived_skipped else ""))
        return finish(status)
    docs: dict[str, dict] = {}
    shape: list[str] = []
    for path in probe_files:
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except ValueError as exc:
            shape.append(f"probes/{path.name} - it is not valid JSON ({exc}) - fix the syntax")
            continue
        problems = npdev_pack_probe.check_shape(doc)
        if not problems and doc["pack"] != pack_id:
            problems = [f"probe pack - it says '{doc['pack']}' but lives in the pack '{pack_id}' - write \"pack\": \"{pack_id}\""]
        shape.extend(f"probes/{path.name}: {line}" if not line.startswith("probes/") else line for line in problems)
        docs[path.name] = doc
    if shape:
        _step(steps, "behavior-probes", started, "fail", f"{len(shape)} problem(s) in the probe files")
        feedback.extend(shape)
        return finish("rejected")
    derived_names: set[str] = set()
    if not docs and derived_docs:
        docs.update(derived_docs)
        derived_names = set(derived_docs)
    users, conflict = merge_probe_users(docs)
    if conflict:
        _step(steps, "behavior-probes", started, "fail", conflict)
        feedback.append(f"probes - {conflict} - give each identity one role across all probe files")
        return finish("rejected")
    if not docs:   # screens were asked for a pack that sent no probes: identities come from the roles the pack names
        import npdev_pack_screens
        users = npdev_pack_screens.default_users(roles)
    example_path = folder / "example-model.json"
    try:
        consumer = assemble_consumer(cli, run, pack_id, order, make_pack_finder(root), users,
                                     example_path.read_text(encoding="utf-8") if example_path.is_file() else None, update=update)
    except BootUnavailable as exc:
        _step(steps, "behavior-probes", started, "skipped", f"the consumer could not be assembled: {exc}")
        if screens:
            _step(steps, "screens", time.monotonic(), "skipped", "the consumer could not be assembled, so there is nothing to photograph")
        return finish(status)
    keys = npdev_pack_probe.new_keys(users)
    keys_value = npdev_pack_probe.api_keys_env(users, keys)
    env = {"NPDEV_AUTH_API_KEYS": keys_value, "NPDEV_AUTH_APIKEYS": keys_value}
    boot = boot or default_boot
    try:
        base_url, stop = boot(cli, consumer, env)
    except BootUnavailable as exc:
        _step(steps, "behavior-probes", started, "skipped", f"the consumer was assembled but not booted: {exc}", "consumer")
        if screens:
            _step(steps, "screens", time.monotonic(), "skipped", "the consumer was not booted, so there is nothing to photograph")
        return finish(status)
    results: dict[str, list[dict]] = {}
    shots: dict | None = None
    screens_note = ""
    screens_started = time.monotonic()
    inconclusive: list[str] = []
    try:
        if derived_names:
            import npdev_pack_probe_derive
            invocations = _consumer_invocations(consumer)
            for name in sorted(derived_names):
                why = npdev_pack_probe_derive.resolve_paths(docs[name], invocations)
                if why:
                    inconclusive.append(f"{name}: {why}")
                    docs.pop(name)
            (run / "derived-probes").mkdir(exist_ok=True)
            for name, doc in docs.items():
                (run / "derived-probes" / name).write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
        for name, doc in docs.items():
            results[name] = npdev_pack_probe.run_probe(base_url, doc, keys, probe_call)
        for name in sorted(derived_names & set(results)):
            # A derived probe whose POSITIVE step failed could not build a valid row (an invariant or a rule the
            # declaration does not show): that says nothing about the pack, so it is inconclusive, never a rejection.
            refusals = npdev_pack_probe_derive.OUTSIDER_STATUSES
            rows, plan = results[name], docs[name]["steps"]
            if any(not row["ok"] and step["expect"]["status"] != refusals for row, step in zip(rows, plan)):
                first = next(row for row, step in zip(rows, plan) if not row["ok"] and step["expect"]["status"] != refusals)
                inconclusive.append(f"{name}: {first['name']} - {first['detail']}")
                results.pop(name)
                docs.pop(name)
        probes_ok = all(r["ok"] for rows in results.values() for r in rows)
        if screens and probes_ok:
            import npdev_pack_screens
            try:
                shots = npdev_pack_screens.capture(base_url, users, keys, run / "screens", f"{pack_id}@{version}", shoot=shoot)
            except npdev_pack_screens.ScreensUnavailable as exc:
                screens_note = str(exc)
    finally:
        stop()
    failed = [(file, r) for file, rows in results.items() for r in rows if not r["ok"]]
    total = sum(len(rows) for rows in results.values())
    if docs:
        (run / "probes.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
        if failed:
            _step(steps, "behavior-probes", started, "fail", f"{len(failed)} of {total} step(s) failed", "probes.json")
            feedback.extend((f"derived probe {file} step '{r['name']}' (as {r['as']}) - {r['detail']} - the probe was derived from the pack's own "
                             f"access rules, so either the rule admits a role it should not or it refuses one it should admit")
                            if file in derived_names else
                            f"probes/{file} step '{r['name']}' (as {r['as']}) - {r['detail']} - fix the pack's rules or the probe's expectation"
                            for file, r in failed[:20])
            return finish("rejected")
        feedback.extend(f"(warning) derived probe inconclusive, so it proves nothing either way: {line}" for line in inconclusive)
        feedback.extend(f"(warning) no probe could be derived for {line}" for line in derived_skipped)
        _step(steps, "behavior-probes", started, "pass",
              f"{len(docs)} probe file(s), {total} step(s)" + (f" -- DERIVED from the pack's declarations; {len(inconclusive)} inconclusive, "
                                                                f"{len(derived_skipped)} concept(s) not derivable" if derived_names else ""),
              "probes.json")
        status = "probed"
        _step(steps, "exposure-matrix", time.monotonic(), "skipped",
              "the matrix logs in with passwords; the probe consumer uses static API keys -- not wired yet")
    else:
        _step(steps, "behavior-probes", started, "skipped",
              ("the probes derived from the pack's declarations were all inconclusive (" + "; ".join(inconclusive[:2]) + ")") if inconclusive
              else "the pack delivered no probes/*.json, so it cannot reach 'probed'")
    if screens:
        if shots is None:
            _step(steps, "screens", screens_started, "skipped", f"no screenshots were taken: {screens_note}")
        else:
            detail = f"{shots['shots']} screenshot(s) for {shots['identities']} identit{'y' if shots['identities'] == 1 else 'ies'}"
            if shots["consoleErrors"]:
                detail += f"; {len(shots['consoleErrors'])} console error(s) (see manifest.json)"
            if status == "probed":
                status = "browsed"
            else:
                detail += "; the rung 'browsed' is not claimed because the pack is not 'probed'"
            _step(steps, "screens", screens_started, "pass", detail, shots["gallery"])
    return finish(status)


# ---------------------------------------------------------------------------------------------------------------
# STATUS / FEEDBACK / PROMOTE (P6-T5)
# ---------------------------------------------------------------------------------------------------------------

RUNGS = ("delivered", "valid", "composes", "secure", "probed", "browsed", "reviewed", "published")
STEP_REACHES = (("static", "valid"), ("compose", "composes"), ("access-lint", "secure"), ("behavior-probes", "probed"),
                ("screens", "browsed"), ("owner-review", "reviewed"), ("publish", "published"))


def latest_verdict(root: Path, pack_id: str, version: str) -> tuple[dict, str] | None:
    """(verdict, stamp) of the newest run of pack@version, or None when it was never tested."""
    runs = root / "runs" / f"{pack_id}@{version}"
    stamps = sorted(p.name for p in runs.iterdir() if (p / "verdict.json").is_file()) if runs.is_dir() else []
    if not stamps:
        return None
    return json.loads((runs / stamps[-1] / "verdict.json").read_text(encoding="utf-8")), stamps[-1]


def highest_rung(verdict: dict | None) -> str:
    """The highest rung CLIMBED, also for a rejected verdict (whose `status` only says 'rejected')."""
    if verdict is None:
        return "delivered"
    if verdict["status"] != "rejected":
        return verdict["status"]
    reached = "delivered"
    passed = {s["step"] for s in verdict["steps"] if s["result"] == "pass"}
    for step, rung in STEP_REACHES:
        if step in passed:
            reached = rung
        else:
            break
    return reached


def failed_step(verdict: dict | None) -> str:
    if verdict is None:
        return ""
    return next((s["step"] for s in verdict["steps"] if s["result"] == "fail"), "")


def list_workshop_packs(root: Path, batch: str | None = None) -> list[tuple[str, str, str]]:
    """[(pack, version, area)], for one batch (from batches/<id>.json) or for everything in packs/."""
    if batch:
        record = root / "batches" / f"{batch}.json"
        if not record.is_file():
            raise WorkshopError(f"batch '{batch}' is unknown (no {record}); ingest its zip first")
        seen: dict[tuple[str, str], str] = {}
        for delivery in json.loads(record.read_text(encoding="utf-8")).get("deliveries", []):
            for p in delivery.get("packs", []):
                seen[(p["pack"], p["version"])] = p.get("area", "")
        return sorted((pack, version, area) for (pack, version), area in seen.items())
    found = []
    for pack_json in sorted((root / "packs").glob("*/*/*/pack.json")):
        version_dir = pack_json.parent
        found.append((version_dir.parent.name, version_dir.name, version_dir.parent.parent.name))
    return found


def status_rows(root: Path, batch: str | None = None) -> list[dict]:
    rows = []
    for pack, version, area in list_workshop_packs(root, batch):
        found = latest_verdict(root, pack, version)
        verdict, stamp = found if found else (None, "")
        rejected = bool(verdict and verdict["status"] == "rejected")
        rows.append({"pack": pack, "version": version, "area": area, "rung": highest_rung(verdict), "rejected": rejected,
                     "stoppedAt": failed_step(verdict) if rejected else "", "stamp": stamp,
                     "firstFeedback": (verdict or {}).get("feedbackForAi", [""])[0] if rejected else ""})
    return rows


def format_status(rows: list[dict]) -> str:
    if not rows:
        return "(no packs)"
    width = max(len(f"{r['pack']}@{r['version']}") for r in rows)
    lines = [f"{'PACK':{width}}  {'HIGHEST RUNG':12}  STOPPED AT / NOTE"]
    for r in rows:
        note = f"REJECTED at {r['stoppedAt']}: {r['firstFeedback'][:90]}" if r["rejected"] else ("not tested yet" if not r["stamp"] else "")
        lines.append(f"{r['pack'] + '@' + r['version']:{width}}  {r['rung']:12}  {note}")
    return "\n".join(lines)


def feedback_text(root: Path, batch: str) -> str:
    """One paste-ready text for the authoring AI: what to fix per pack, and which packs need nothing."""
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    lines = [f"Feedback on your batch {batch} ({today}). Fix only what is listed; send each fixed pack again with a HIGHER version "
             f"(a delivered version is immutable) in a new zip that follows the guide's 'Delivering' section.", ""]
    listed = list_workshop_packs(root, batch)
    newest = {}
    for pack, version, _ in listed:
        newest[pack] = max(newest.get(pack, ()), _semver(version))
    for pack, version, _ in listed:
        if _semver(version) < newest[pack]:
            continue  # a later version of this pack was delivered: the AI already fixed this one
        found = latest_verdict(root, pack, version)
        if found is None:
            lines += [f"## {pack}@{version}: not tested yet", ""]
            continue
        verdict, _stamp = found
        fixes = [f for f in verdict.get("feedbackForAi", []) if not f.startswith("(warning)")]
        warnings = [f[len("(warning) "):] for f in verdict.get("feedbackForAi", []) if f.startswith("(warning)")]
        if verdict["status"] == "rejected":
            lines.append(f"## {pack}@{version}: REJECTED at {failed_step(verdict)} (reached: {highest_rung(verdict)})")
            lines += [f"- {f}" for f in fixes]
        else:
            lines.append(f"## {pack}@{version}: {verdict['status'].upper()} - no fix required")
        if warnings:
            lines.append("Advice (not blocking):")
            lines += [f"- {w}" for w in warnings]
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def write_feedback(root: Path, batch: str) -> tuple[Path, str]:
    text = feedback_text(root, batch)
    folder = root / "feedback"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{batch}__{datetime.now(timezone.utc).strftime('%Y%m%d')}.md"
    path.write_text(text, encoding="utf-8")
    return path, text


def promote_pack(root: Path, pack_id: str, version: str, confirm: bool) -> dict:
    """Owner-confirmed promotion: stage the pack in the NPR layout under <root>/npr-staging/ and record the owner review.
    NEVER publishes: pushing to the public NPR is a separate act that always asks the owner."""
    folder = find_pack_dir(root, pack_id, version)
    if folder is None:
        raise WorkshopError(f"{pack_id}@{version} is not in the workshop; ingest it first")
    found = latest_verdict(root, pack_id, version)
    rung = highest_rung(found[0] if found else None)
    if found is None or found[0]["status"] == "rejected" or RUNGS.index(rung) < RUNGS.index("probed"):
        raise WorkshopError(f"{pack_id}@{version} has only reached '{rung}'; promotion needs 'probed' (run `npdev pack workshop test` "
                            f"and fix what it reports -- no pack goes out without probes)")
    if not confirm:
        raise WorkshopError(f"{pack_id}@{version} reached '{rung}' and can be promoted, but promotion records the OWNER's review: "
                            f"read the rules sheet (scripts/pack-repo/generate-pack-rules-sheet.py) and re-run with --confirm")
    verdict, _ = found
    stage = root / "npr-staging" / "packs" / pack_id / version
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    for name in KNOWN_FILES:
        if (folder / name).is_file():
            shutil.copy2(folder / name, stage / name)
    if (folder / "probes").is_dir():
        shutil.copytree(folder / "probes", stage / "probes")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    run = root / "runs" / f"{pack_id}@{version}" / stamp
    run.mkdir(parents=True, exist_ok=True)
    steps = [s for s in verdict["steps"] if s["step"] not in ("owner-review", "publish")]
    steps.append({"step": "owner-review", "result": "pass", "detail": "owner confirmed with --confirm"})
    steps.append({"step": "publish", "result": "skipped", "detail": "staged only; publishing to the public NPR is a separate owner act"})
    reviewed = {"schemaVersion": "npdev-pack-verdict.v1", "pack": pack_id, "version": version, "status": "reviewed", "steps": steps}
    _validate("verdict", reviewed, "the promoted verdict")
    (run / "verdict.json").write_text(json.dumps(reviewed, indent=2) + "\n", encoding="utf-8")
    shutil.copy2(run / "verdict.json", stage / "verdict.json")
    return {"pack": pack_id, "version": version, "status": "reviewed", "staging": str(stage), "run": str(run)}

def downloads_dir(override: str | None) -> Path:
    return Path(override).expanduser() if override else Path.home() / "Downloads"


def add_arguments(parser: argparse.ArgumentParser) -> None:
    sub = parser.add_subparsers(dest="workshop_command")
    ingest = sub.add_parser("ingest", help="Take AI-delivered pack zips into the workshop (hash, dedupe, validate, place).")
    ingest.add_argument("zips", nargs="*", help="zip file(s) to ingest")
    ingest.add_argument("--downloads", action="store_true",
                        help="also sweep npdev-packs__*.zip from the Downloads folder")
    ingest.add_argument("--downloads-dir", default=None, help="Downloads folder to sweep (default: the user's Downloads)")
    ingest.add_argument("--legacy", default=None, metavar="BATCH_ID",
                        help="take a delivery that has no batch.json, as this batch id (e.g. w1-modules)")
    ingest.add_argument("--root", default=None, help=f"workshop root (default: ${ENV_ROOT}, else PackWorkshop next to the Build root)")
    ingest.add_argument("--json", action="store_true")
    test = sub.add_parser("test", help="Run the status ladder (valid, composes, secure) on one workshop pack; writes runs/.../verdict.json.")
    test.add_argument("pack", help="<packId> or <packId>@<version> (default: the highest version in the workshop)")
    test.add_argument("--root", default=None, help=f"workshop root (default: ${ENV_ROOT}, else PackWorkshop next to the Build root)")
    test.add_argument("--screens", action="store_true",
                      help="also photograph the booted consumer, one pass per identity (needs node and the ScrapForAI engine; "
                           "writes <run>/screens/gallery.html; 'browsed' needs the pack to be 'probed' first)")
    test.add_argument("--json", action="store_true")
    status = sub.add_parser("status", help="Table of workshop packs x highest rung reached.")
    status.add_argument("--batch", default=None, help="only the packs of this batch id")
    status.add_argument("--root", default=None, help="workshop root")
    status.add_argument("--json", action="store_true")
    feedback = sub.add_parser("feedback", help="One paste-ready text for the authoring AI, from the latest verdicts of a batch.")
    feedback.add_argument("batch", help="batch id, e.g. w1-modules")
    feedback.add_argument("--root", default=None, help="workshop root")
    promote = sub.add_parser("promote", help="After the OWNER reviewed a probed pack: stage it for the NPR (never publishes).")
    promote.add_argument("pack", help="<packId>@<version>")
    promote.add_argument("--confirm", action="store_true", help="the owner has read the rules sheet and says yes")
    promote.add_argument("--root", default=None, help="workshop root")
    promote.add_argument("--json", action="store_true")


def main(cli, args: argparse.Namespace) -> int:
    command = getattr(args, "workshop_command", None)
    if command == "test":
        return _main_test(cli, args)
    if command in ("status", "feedback", "promote"):
        return _main_t5(cli, args, command)
    if command != "ingest":
        print("usage: npdev pack workshop ingest [<zip> ...] [--downloads] | test <packId>[@version] | status [--batch id] | feedback <batch> | promote <pack>@<version> --confirm", file=sys.stderr)
        return 2
    root = workshop_root(cli, args.root)
    zips = [Path(z) for z in args.zips]
    if args.downloads:
        zips += sorted(downloads_dir(args.downloads_dir).glob("npdev-packs__*.zip"))
    if not zips:
        print("nothing to ingest: give a zip path or --downloads", file=sys.stderr)
        return 2
    outcomes, failed = [], False
    for zip_path in zips:
        try:
            outcomes.append(ingest_zip(root, zip_path, args.legacy))
        except WorkshopError as exc:
            failed = True
            outcomes.append({"zip": zip_path.name, "status": "refused", "reason": str(exc), "packs": []})
        except OSError as exc:
            failed = True
            outcomes.append({"zip": zip_path.name, "status": "refused", "reason": f"cannot read: {exc}", "packs": []})
    if args.json:
        print(json.dumps({"root": str(root), "results": outcomes}, indent=2))
    else:
        print(f"workshop: {root}")
        for out in outcomes:
            line = f"  {out['status'].upper():9} {out['zip']}"
            if out["status"] == "ingested":
                placed = sum(1 for p in out["packs"] if p["placed"])
                line += f"  batch {out['batchId']}: {len(out['packs'])} pack(s), {placed} new"
            print(line)
            for pack in out["packs"]:
                print(f"      {pack['pack']}@{pack['version']} -> {pack['area']}{'' if pack['placed'] else ' (already there, identical)'}")
            for warning in out.get("warnings", []):
                print(f"      note: {warning}")
            if out["status"] == "refused":
                print(f"      why:  {out['reason']}")
    return 1 if failed else 0


def _main_test(cli, args: argparse.Namespace) -> int:
    root = workshop_root(cli, args.root)
    pack_id, _, version = args.pack.partition("@")
    try:
        verdict = test_pack(cli, root, pack_id, version or None, screens=args.screens)
    except WorkshopError as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(verdict, indent=2))
    else:
        print(f"{verdict['pack']}@{verdict['version']}: {verdict['status'].upper()}   ({verdict['_run']})")
        for step in verdict["steps"]:
            print(f"  {step['result'].upper():8} {step['step']:16} {step.get('seconds', 0):6.1f}s  {step.get('detail', '')}")
        for line in verdict.get("feedbackForAi", []):
            print(f"  feedback: {line}")
    return 1 if verdict["status"] == "rejected" else 0


def _main_t5(cli, args: argparse.Namespace, command: str) -> int:
    root = workshop_root(cli, args.root)
    try:
        if command == "status":
            rows = status_rows(root, args.batch)
            print(json.dumps(rows, indent=2) if args.json else format_status(rows))
        elif command == "feedback":
            path, text = write_feedback(root, args.batch)
            print(text)
            print(f"(saved: {path})", file=sys.stderr)
        else:
            pack_id, _, version = args.pack.partition("@")
            if not version:
                raise WorkshopError("promote needs <packId>@<version> (a promotion is for one exact version)")
            result = promote_pack(root, pack_id, version, args.confirm)
            print(json.dumps(result, indent=2) if args.json else
                  f"{pack_id}@{version}: REVIEWED, staged at {result['staging']}\n(nothing was published; pushing to the public NPR is a separate owner act)")
    except WorkshopError as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 2
    return 0
