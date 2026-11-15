#!/usr/bin/env python3
"""`npdev pack workshop` -- where AI-authored packs arrive, are checked and are promoted (FORM P6).

This file holds INGEST (P6-T2). `test`, `status`, `feedback` and `promote` land in later tasks and share this layout.

The workshop is layer 2 (definitions), like AppGen/apps: OUTSIDE the repo. Root = `--root`, else $NPDEV_PACK_WORKSHOP,
else a `PackWorkshop` folder next to the Build root. Never a hardcoded drive.

    <root>/inbox/<sha12>__<zip name>              immutable copy of every accepted delivery (duplicates found by hash)
    <root>/packs/<area>/<packId>/<version>/       pack.json, handoff.json, example-model.json, probes/*.json
    <root>/batches/<batchId>.json                 one record per batch, one entry per delivery

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
import stat
import sys
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


def main(cli, args: argparse.Namespace) -> int:
    command = getattr(args, "workshop_command", None)
    if command != "ingest":
        print("usage: npdev pack workshop ingest [<zip> ...] [--downloads]", file=sys.stderr)
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
