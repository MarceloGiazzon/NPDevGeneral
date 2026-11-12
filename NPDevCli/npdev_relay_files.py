"""Files an answer carries besides model.json (fragments and contexts, REG-323 T4.1) and the all-or-nothing write of them.

A model can be split: model.json lists `fragments[]` / `contexts[]` and each entry is a `$ref` to another file next to it.
An answer for such a model is several files, so three things stop being "one file":

  answer_files(result)          which of the answer's files this phase can apply, and which it cannot
  reachable(model_path) / stage(...) / compose(...)
                                a folder the validators can read (the app's current tree + the answer's files), and the
                                ONE model the CLI's own composer makes from it -- what the diff and the preview compare
  plan(root, files) / run(root, ops) / restore_ops(...)
                                write (or delete) several files as one step: every file's previous bytes -- or ABSENT --
                                are kept first, and when any step fails the files already touched are put back
  save_backup(...) / load_backup(...) / changed_since(...)
                                the backup undo needs: a list with the previous bytes of every file, or ABSENT for a
                                file the answer created (undo deletes it)

Order matters: fragments are written BEFORE model.json (a model.json that points at a fragment never exists without it)
and undo goes the other way round (model.json first). Stdlib only; the CLI's composer does the composing.
"""
from __future__ import annotations

import fnmatch
import hashlib
import json
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

import npdev_relay_authoring as authoring

SUPPORTED = ("fragments/*.json", "contexts/*.json")
NOT_EXTRA = ("model.json", "npdev-submission.json", "npdev-elements.json")  # the last is EXPANDED into model.json (T4.2)
BACKUP_SCHEMA = "npdev-relay-backup.v1"
BACKUP_MANIFEST = "files.json"
BACKUP_TREE = "tree"
LOCK_FILE = "npdev.lock"  # remote packs resolve through it; a staged copy needs it beside model.json


class TreeError(Exception):
    """The model's files cannot be read or composed: the message is written for the AI or the owner."""


class TransactionError(Exception):
    """A write failed. Everything already written was put back unless `not_restored` names files that could not be."""

    def __init__(self, path: str, cause: BaseException, not_restored: list):
        super().__init__(f"{path}: {cause}")
        self.path, self.cause, self.not_restored = path, cause, not_restored


@dataclass
class Op:
    rel: str                 # path relative to the model's folder, forward slashes
    new: bytes | None        # None = delete the file
    before: bytes | None = None  # what is on disk now; None = the file does not exist (ABSENT)


# --------------------------------------------------------------------------- what an answer carries

def answer_files(result: dict) -> tuple[dict, list]:
    """({path: text} of the allowed files this phase can apply, [paths it cannot]) -- model.json and the manifest
    are not counted. A file that is allowed by the ingest policy but is neither a fragment nor a context (a theme, an
    HTML page, an element-upsert list ...) is reported, never dropped quietly."""
    supported: dict = {}
    unsupported: list = []
    for item in (result or {}).get("files") or []:
        path = item.get("path")
        if path == LOCK_FILE and item.get("status") == "generated" and isinstance(item.get("content"), str):
            supported[path] = item["content"]  # NPDev's own resolution of the answer's packs, never the AI's file
            continue
        if item.get("status") != "allowed" or not isinstance(path, str) or not isinstance(item.get("content"), str):
            continue
        if path in NOT_EXTRA:
            continue
        if any(fnmatch.fnmatchcase(path, pattern) for pattern in SUPPORTED):
            supported[path] = item["content"]
        else:
            unsupported.append(path)
    return supported, sorted(set(unsupported))


def safe_relative(rel) -> str:
    """The path as written, or TreeError: relative, no `..`, no drive, not empty. Callers join it to a folder."""
    text = str(rel).replace("\\", "/") if isinstance(rel, str) else ""
    parts = text.split("/")
    if not text or text.startswith("/") or any(part in ("", ".", "..") or ":" in part for part in parts):
        raise TreeError(f"{rel!r} is not a plain path inside the app")
    return text


# --------------------------------------------------------------------------- reading and composing a split model

def _composer():
    return authoring._load_cli()


def _plain(message, folder) -> str:
    """The composer's message without the folder it ran in: the AI reads it, and a temp path means nothing to it."""
    text = str(message)
    for variant in {str(folder), Path(folder).as_posix()}:
        text = text.replace(variant + os.sep, "").replace(variant + "/", "").replace(variant, ".")
    return text[-400:]


def reachable(model_path) -> list:
    """Relative paths (model.json first) of every file the model is composed from, plus npdev.lock when it is there.
    TreeError when the model cannot be composed."""
    cli = _composer()
    sources: set = set()
    try:
        cli.resolve_split_model(Path(model_path), collect_sources=sources)
    except (cli.CliError, OSError, ValueError) as exc:
        raise TreeError(_plain(exc, Path(model_path).resolve().parent)) from exc
    root = Path(model_path).resolve().parent
    out = [Path(model_path).resolve().relative_to(root).as_posix()]
    for source in sorted(sources, key=lambda p: p.as_posix()):
        try:
            rel = Path(source).resolve().relative_to(root).as_posix()
        except ValueError:  # a source outside the folder cannot be staged; the composer refuses those anyway
            continue
        if rel not in out:
            out.append(rel)
    if (root / LOCK_FILE).is_file() and LOCK_FILE not in out:
        out.append(LOCK_FILE)
    return out


def compose(model_path) -> dict:
    """The one model the CLI's composer makes from model.json and the files it points at. TreeError when it cannot."""
    cli = _composer()
    try:
        composed = cli.resolve_split_model(Path(model_path))
    except (cli.CliError, OSError, ValueError) as exc:
        raise TreeError(_plain(exc, Path(model_path).resolve().parent)) from exc
    if not isinstance(composed, dict):
        raise TreeError("the model did not compose into an object")
    return composed


def stage(model_path, overlay: dict, model_text: str | None, dest) -> Path:
    """A folder `dest` the validators can read: the app's current files (copied byte for byte, when `model_path` is
    given) with the answer's `overlay` {relative path: text} on top and, last, `model_text` as model.json. Returns
    dest/model.json. The folder is rebuilt from scratch on every call; never point it at a real app."""
    dest = Path(dest)
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True, exist_ok=True)
    if model_path is not None:
        root = Path(model_path).resolve().parent
        for rel in reachable(model_path):
            source = root / rel
            if rel == Path(model_path).name:
                rel = "model.json"
            authoring.atomic_write_bytes(dest / rel, source.read_bytes())
    for rel, text in (overlay or {}).items():
        authoring.atomic_write_bytes(dest / safe_relative(rel), text.encode("utf-8"))
    if model_text is not None:
        authoring.atomic_write_bytes(dest / "model.json", model_text.encode("utf-8"))
    return dest / "model.json"


# --------------------------------------------------------------------------- packs imported from the catalog (P2)

class PackError(Exception):
    """An answer imports packs that cannot be resolved here: the message is written for the AI (plain words, no paths)."""


_OFFLINE_HINTS = ("could not resolve host", "unable to access", "failed to connect", "network is unreachable",
                  "timed out", "unknownhost", "name resolution", "could not read from remote", "connection refused",
                  "could not reach")
OFFLINE_MESSAGE = ("This answer imports packs and NPDev could not download them (no internet). "
                   "Connect and try again, or ask for an answer without packs.")


def remote_pack_entries(model) -> list:
    """The model's own `packs[]` entries that name a remote coordinate (`from`). Local `$ref` packs need no lock."""
    entries = (model or {}).get("packs") if isinstance(model, dict) else None
    return [e for e in entries or [] if isinstance(e, dict) and isinstance(e.get("from"), str) and e["from"].strip()]


def pack_name(entry: dict) -> str:
    """A short name for an entry in a message: its alias, else the id inside the coordinate (`.../packs/<id>@<id>-<v>`)."""
    if isinstance(entry.get("as"), str) and entry["as"].strip():
        return entry["as"].strip()
    coordinate = str(entry.get("from") or "")
    tail = coordinate.rsplit("//", 1)[-1].split("@", 1)[0].rstrip("/")
    return tail.rsplit("/", 1)[-1] or coordinate


def _locked_sources(lock_path: Path) -> set:
    try:
        packs = json.loads(lock_path.read_text(encoding="utf-8")).get("packs")
    except (OSError, ValueError, AttributeError):
        return set()
    return {str(v.get("from")) for v in (packs.values() if isinstance(packs, dict) else []) if isinstance(v, dict)}


def _pack_failure(entries: list, text: str) -> PackError:
    lowered = text.lower()
    if any(hint in lowered for hint in _OFFLINE_HINTS):
        return PackError(OFFLINE_MESSAGE)
    names = ", ".join(sorted({pack_name(e) for e in entries}))
    if "unsigned" in lowered or "unknown_signer" in lowered or "bad_signature" in lowered:
        return PackError(f"packs[] - pack {names} is not signed by a key NPDev trusts - import only packs from the "
                         "list in the briefing, or model those concepts yourself")
    return PackError(f"packs[] - NPDev could not download the pack(s) {names}: use only a `from` coordinate copied from "
                     "the list in the briefing (never invent a pack), or model those concepts yourself")


def resolve_packs(staged_model, model) -> str | None:
    """Make the staged folder resolve the answer's remote packs: the same `pack update` the person would run, INTO the
    staged folder (the lock lands beside `staged_model`), refusing anything but a real signature or the temporary trust
    rule (`allow_unsigned` False). Returns the lock's text (to be written with the model on apply), or None when
    nothing needed resolving. PackError when it cannot -- never a partial lock."""
    import argparse
    import contextlib
    import io

    entries = remote_pack_entries(model)
    staged_model = Path(staged_model)
    lock_path = staged_model.parent / LOCK_FILE
    if not entries or all(str(e["from"]) in _locked_sources(lock_path) for e in entries):
        return None
    cli = _composer()
    args = argparse.Namespace(model=str(staged_model), allow_unsigned=False, strict=False)
    buffer = io.StringIO()
    try:
        with contextlib.redirect_stdout(buffer):
            code = cli.run_pack_update(args)
    except (cli.CliError, OSError, ValueError, SystemExit) as exc:
        raise _pack_failure(entries, str(exc)) from exc
    if code != 0 or not lock_path.is_file():
        raise _pack_failure(entries, buffer.getvalue())
    return lock_path.read_text(encoding="utf-8")


def is_split(model_path) -> bool:
    """True when the app's model is made of more than model.json. TreeError when it cannot be composed."""
    return len([rel for rel in reachable(model_path) if rel != LOCK_FILE]) > 1


# --------------------------------------------------------------------------- the all-or-nothing write

def _target(root: Path, rel: str) -> Path:
    target = (root / safe_relative(rel)).resolve()
    try:
        target.relative_to(root.resolve())
    except ValueError as exc:
        raise TreeError(f"{rel!r} leaves the app's folder") from exc
    return target


def plan(root, files: list) -> list:
    """[(relative path, bytes | None)] in write order -> [Op] with each file's current bytes (None = ABSENT).
    Nothing is written. TreeError for a path that is not a plain path inside `root`."""
    root = Path(root)
    ops = []
    for rel, new in files:
        target = _target(root, rel)
        before = target.read_bytes() if target.is_file() else None
        ops.append(Op(safe_relative(rel), new, before))
    return ops


def _missing_dirs(folder: Path, root: Path) -> list:
    missing = []
    while folder != root and not folder.exists() and root in folder.parents:
        missing.append(folder)
        folder = folder.parent
    return missing


def _prune_empty(folder: Path, root: Path) -> None:
    while folder != root and root in folder.parents:
        try:
            folder.rmdir()  # only an empty folder goes
        except OSError:
            return
        folder = folder.parent


def _put_back(root: Path, ops: list, created_dirs: list) -> list:
    """Undo `ops` (those already done) newest first. Returns the paths that could NOT be restored."""
    failed = []
    for op in reversed(ops):
        target = root / op.rel
        try:
            if op.before is None:
                if target.exists():
                    target.unlink()
            else:
                authoring.atomic_write_bytes(target, op.before)
        except OSError:
            failed.append(op.rel)
    for folder in sorted(created_dirs, key=lambda p: len(p.parts), reverse=True):
        _prune_empty(folder, root)
    return failed


def run(root, ops: list) -> None:
    """Do every op in order. When one fails, put the files already touched back and raise TransactionError (its
    `not_restored` is empty when the app is exactly as it was). A delete removes folders it leaves empty."""
    root = Path(root).resolve()
    done: list = []
    created: list = []
    current = ""
    try:
        for op in ops:
            current = op.rel
            target = _target(root, op.rel)
            if op.new is None:
                if target.exists():
                    target.unlink()
                done.append(op)
                _prune_empty(target.parent, root)
            else:
                created.extend(_missing_dirs(target.parent, root))
                authoring.atomic_write_bytes(target, op.new)
                done.append(op)
    except Exception as exc:  # noqa: BLE001 -- whatever the disk or a scanner threw: the app must end as it began
        raise TransactionError(current, exc, _put_back(root, done, created)) from exc


def restore_ops(ops: list) -> list:
    """For ops that `plan` made and `run` did: the ops that take them back, newest first -- each file to its
    previous bytes, or deleted when it was ABSENT."""
    return [Op(op.rel, op.before, op.new) for op in reversed(ops)]


# --------------------------------------------------------------------------- the backup undo reads

@dataclass
class Saved:
    rel: str
    before: bytes | None      # the bytes the file had before the answer (None = it did not exist: undo deletes it)
    after_sha256: str | None  # hash of what the answer wrote, to notice edits made since


def _norm_sha(data: bytes | None) -> str | None:
    if data is None:
        return None
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()  # a line-ending change alone is not an edit


def save_backup(directory, ops: list, applied_at: str) -> Path:
    """Write the previous bytes of every file in `ops` (under <directory>/tree/) and the list that names them.
    Done BEFORE the app is touched. Returns the list's path."""
    directory = Path(directory)
    entries = []
    for op in ops:
        item = {"path": op.rel, "before": None, "beforeSha256": None, "afterSha256": _norm_sha(op.new)}
        if op.before is not None:
            stored = f"{BACKUP_TREE}/{op.rel}"
            authoring.atomic_write_bytes(directory / stored, op.before)
            item["before"], item["beforeSha256"] = stored, _norm_sha(op.before)
        entries.append(item)
    manifest = {"schemaVersion": BACKUP_SCHEMA, "savedAt": applied_at, "files": entries}
    path = directory / BACKUP_MANIFEST
    authoring.atomic_write_bytes(path, (json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
    return path


def load_backup(manifest_path) -> list:
    """The saved list as [Saved], in the order the files were written. TreeError when the list or any saved copy is
    missing or is not one this version wrote."""
    path = Path(manifest_path)
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise TreeError("the backup list could not be read") from exc
    if (not isinstance(manifest, dict) or manifest.get("schemaVersion") != BACKUP_SCHEMA
            or not isinstance(manifest.get("files"), list)):
        raise TreeError("the backup list is not one this version wrote")
    saved = []
    for item in manifest["files"]:
        rel = safe_relative(item.get("path"))
        before = None
        if item.get("before"):
            try:
                before = (path.parent / safe_relative(item["before"])).read_bytes()
            except OSError as exc:
                raise TreeError(f"the saved copy of {rel} is missing") from exc
            if item.get("beforeSha256") and _norm_sha(before) != item["beforeSha256"]:
                raise TreeError(f"the saved copy of {rel} is damaged")
        saved.append(Saved(rel, before, item.get("afterSha256")))
    return saved


def changed_since(root, saved: list) -> list:
    """Paths whose bytes on disk are no longer what the answer wrote (edited, or gone) -- undo would overwrite work."""
    root = Path(root)
    changed = []
    for item in saved:
        target = _target(root, item.rel)
        current = target.read_bytes() if target.is_file() else None
        if _norm_sha(current) != item.after_sha256:
            changed.append(item.rel)
    return changed


def undo_ops(root, saved: list) -> list:
    """The ops that put every saved file back, model.json first (the reverse of the write order): each file to its
    saved bytes, or deleted when the answer created it. `before` is read from the disk now, so a failure half way
    through undo puts the app back as it is at this moment."""
    return plan(root, [(item.rel, item.before) for item in reversed(saved)])
