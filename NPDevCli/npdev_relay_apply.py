"""Apply an accepted answer of a browser-AI conversation, and take it back.

  apply_round(session, number, confirmed, parent_dir=None)
      create  scaffold the app the way `npdev init` does, then put the AI's model.json in place.
      change/fix  re-run the whole check on the model that is on disk NOW (the app may have moved since the
                  answer arrived), require the owner's yes for every warning that needs one, archive the
                  previous model (the backup), then write the new one.
      An answer may also carry fragments/*.json and contexts/*.json (REG-323 T4.1): they are written BEFORE
      model.json, all or nothing -- the previous bytes of every file (or ABSENT) are saved first, and a failed
      write puts every file already touched back.
      pack    write the AI's pack.json into <parent_dir>/<packId>/ (the pack workspace) with the all-or-nothing writer
              and a backup; a new VERSION of an existing pack must still find the old pack.json untouched. Nothing is
              published here.
  undo(session, force=False)
      put the archived model back (change/fix); with several files: put every file back and delete the ones the
      answer created. A created app is never deleted from here.

Nothing is written unless every check passes; every write is atomic. Code files an answer carried (quarantine)
and kinds of file this phase cannot apply yet (themes, pages, element upserts) are never written: the answer is
refused by name instead of applied in part. Stdlib only; the pipeline's own modules do the checking.
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import npdev_relay_authoring as authoring
import npdev_relay_engine as engine
import npdev_relay_files as files
import npdev_relay_session as rs

BACKUP_NAME = "model.before.json"


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _refuse(error: str, **extra) -> dict:
    return {"ok": False, "error": error, **extra}


def _remove_tree(path) -> None:
    """Delete a folder for good. The scaffold's `.git` holds read-only objects, which `rmtree` cannot remove on Windows
    unless told to clear the attribute -- `ignore_errors=True` alone left a half-made app behind."""
    def clear_and_retry(function, target, _error):
        os.chmod(target, stat.S_IWRITE)
        function(target)

    try:
        if sys.version_info >= (3, 12):
            shutil.rmtree(path, onexc=clear_and_retry)
        else:
            shutil.rmtree(path, onerror=clear_and_retry)
    except OSError:
        pass


def _stored_result(session: dict, number: int) -> dict | None:
    path = rs.round_dir(session, number) / "result.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _file_text(result: dict, name: str) -> str | None:
    for item in result.get("files") or []:
        if item.get("path") == name and item.get("status") == "allowed" and isinstance(item.get("content"), str):
            return item["content"]
    return None


def _round_entry(session: dict, number: int) -> dict:
    for entry in session["rounds"]:
        if entry["round"] == number:
            return entry
    raise engine.RelayStageError("this conversation has no such round")


def apply_round(session: dict, number: int | None, confirmed, parent_dir=None) -> dict:
    """Write the accepted answer of `number` (default: the latest). Returns {"ok": True, ...} or a refusal
    {"ok": False, "error": <code>, ...} that wrote nothing."""
    number = number or len(session["rounds"])
    entry = _round_entry(session, number)
    if entry.get("appliedAt"):
        return _refuse("already-applied", appliedAt=entry["appliedAt"])
    result = _stored_result(session, number)
    if result is None or result.get("outcome") != "READY":
        return _refuse("not-ready", outcome=(result or {}).get("outcome"))
    if session["intent"] == "pack":
        return _apply_pack(session, number, entry, result, parent_dir)
    model_text = _file_text(result, "model.json")
    if model_text is None:
        return _refuse("no-model")
    extra, unsupported = files.answer_files(result)
    if unsupported:
        return _refuse("files-not-supported", paths=unsupported)
    manifest = None
    manifest_text = _file_text(result, "npdev-submission.json")
    if manifest_text:
        try:
            manifest = json.loads(manifest_text)
        except ValueError:
            manifest = None
    if session["intent"] == "create":
        return _apply_create(session, number, entry, model_text, parent_dir, extra)
    return _apply_change(session, number, entry, model_text, manifest, set(confirmed or ()), extra)


# --------------------------------------------------------------------------- change / fix

def _apply_change(session: dict, number: int, entry: dict, model_text: str, manifest, confirmed: set,
                  extra: dict | None = None) -> dict:
    work = rs.round_dir(session, number) / "apply-work"
    evaluation = authoring.evaluate_for_apply(session, model_text, manifest, work, extra_files=extra)
    if evaluation["outcome"] != "READY":
        return _refuse("not-ready", outcome=evaluation["outcome"], baseMoved=bool(evaluation.get("baseMoved")),
                       errors=evaluation.get("errors") or [])
    needed = sorted(w["code"] for w in evaluation["preview"]["warnings"] if w.get("needsConfirmation"))
    missing = [code for code in needed if code not in confirmed]
    if missing:
        return _refuse("confirmation-required", missing=missing)
    submission = json.loads(json.dumps(evaluation["submission"]))
    pending = evaluation.get("_pending") or []
    if pending:
        submission["securityChanges"] = (submission.get("securityChanges") or []) + authoring.security_manifest_entries(pending)
    submission_path = work / "submission-final.json"
    authoring.atomic_write_bytes(submission_path, (json.dumps(submission, indent=2) + "\n").encode("utf-8"))
    base_copy, candidate_path = evaluation["_baseCopy"], evaluation["_candidatePath"]
    model_path = Path(evaluation["_modelPath"])
    candidate_bytes = Path(candidate_path).read_bytes()

    backup_dir = rs.session_dir(session["code"]) / "backup"
    backup_dir.mkdir(parents=True, exist_ok=True)
    gate = authoring.run_gate(base_copy, candidate_path, submission_path, work / "gate-final", archive_dir=backup_dir / "gate")
    if not gate["available"]:
        return _refuse("validator-unavailable", reason=gate.get("reason"))
    if gate["status"] != "passed":
        return _refuse("gate-refused", errors=[authoring.gate_error_line(d) for d in gate["diagnostics"]])

    if extra:
        return _apply_files(session, number, entry, model_path, candidate_bytes, extra, base_copy, backup_dir)

    backup_path = backup_dir / BACKUP_NAME
    authoring.atomic_write_bytes(backup_path, Path(base_copy).read_bytes())  # the model as the AI was shown it
    authoring.atomic_write_bytes(model_path, candidate_bytes)
    applied_at = _now()
    entry["appliedAt"], entry["backupPath"] = applied_at, str(backup_path)
    session["state"] = "done"
    rs.save(session)
    rs.append_event(session["code"], "applied", number, backupPath=str(backup_path))
    return {"ok": True, "code": session["code"], "round": number, "intent": session["intent"], "created": False,
            "appDir": session.get("appDir"), "modelPath": str(model_path), "backupPath": str(backup_path),
            "appliedAt": applied_at}


def _apply_files(session: dict, number: int, entry: dict, model_path: Path, candidate_bytes: bytes, extra: dict,
                 base_copy, backup_dir: Path) -> dict:
    """Write the answer's fragments and then model.json as one step. The previous bytes of every file (or ABSENT) are
    saved first; if any write fails the files already written are put back and nothing is recorded as applied."""
    root = model_path.parent
    try:
        ops = files.plan(root, [(rel, extra[rel].encode("utf-8")) for rel in sorted(extra)]
                         + [(model_path.name, candidate_bytes)])
    except files.TreeError as exc:
        return _refuse("unsafe-path", detail=str(exc))
    if ops[-1].before is None or ops[-1].before != Path(base_copy).read_bytes():
        return _refuse("not-ready", outcome="PARSED", baseMoved=True, errors=[])  # the model changed while we checked
    applied_at = _now()
    backup_path = backup_dir / BACKUP_NAME
    authoring.atomic_write_bytes(backup_path, ops[-1].before)  # model.json as it is on disk now
    manifest_path = files.save_backup(backup_dir, ops, applied_at)
    try:
        files.run(root, ops)
    except files.TransactionError as exc:
        return _refuse("write-failed", path=exc.path, detail=str(exc.cause)[-300:], notRestored=exc.not_restored)
    written = [op.rel for op in ops]
    entry["appliedAt"], entry["backupPath"], entry["backupManifestPath"] = applied_at, str(backup_path), str(manifest_path)
    session["state"] = "done"
    rs.save(session)
    rs.append_event(session["code"], "applied", number, backupPath=str(backup_path), files=written)
    return {"ok": True, "code": session["code"], "round": number, "intent": session["intent"], "created": False,
            "appDir": session.get("appDir"), "modelPath": str(model_path), "backupPath": str(backup_path),
            "backupManifestPath": str(manifest_path), "files": written, "appliedAt": applied_at}


# --------------------------------------------------------------------------- pack

def _lf(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n")


def _apply_pack(session: dict, number: int, entry: dict, result: dict, parent_dir) -> dict:
    """Put the accepted pack.json in `<parent_dir>/<packId>/pack.json`. Never overwrites a pack the conversation did not
    start from, and never a pack that was edited since the conversation began."""
    pack_text = _file_text(result, "pack.json")
    if pack_text is None:
        return _refuse("no-pack")
    pack_id = session.get("packId") or ""
    if not parent_dir:
        return _refuse("no-target", detail="say which folder holds your packs (--parent-dir)")
    parent = Path(parent_dir)
    rel = f"{pack_id}/pack.json"
    try:
        ops = files.plan(parent, [(rel, pack_text.encode("utf-8"))])
    except files.TreeError as exc:
        return _refuse("unsafe-path", detail=str(exc))
    base_path = rs.session_dir(session["code"]) / "base-pack.json"
    if session.get("packBase"):
        base_bytes = base_path.read_bytes() if base_path.is_file() else None
        if base_bytes is None or ops[0].before is None or _lf(ops[0].before) != _lf(base_bytes):
            return _refuse("not-ready", outcome="PARSED", baseMoved=True, errors=[])  # the pack changed while the AI worked
    elif ops[0].before is not None:
        return _refuse("pack-exists", detail=f'a pack "{pack_id}" is already in that folder: start a new-version conversation from it')
    backup_dir = rs.session_dir(session["code"]) / "backup"
    backup_dir.mkdir(parents=True, exist_ok=True)
    parent.mkdir(parents=True, exist_ok=True)
    applied_at = _now()
    manifest_path = files.save_backup(backup_dir, ops, applied_at)
    try:
        files.run(parent, ops)
    except files.TransactionError as exc:
        return _refuse("write-failed", path=exc.path, detail=str(exc.cause)[-300:], notRestored=exc.not_restored)
    pack_dir = parent / pack_id
    entry["appliedAt"], entry["backupManifestPath"] = applied_at, str(manifest_path)
    session["packDir"] = str(pack_dir)
    session["state"] = "done"
    rs.save(session)
    rs.append_event(session["code"], "applied", number, files=[rel])
    return {"ok": True, "code": session["code"], "round": number, "intent": "pack", "created": ops[0].before is None,
            "packId": pack_id, "packDir": str(pack_dir), "packPath": str(pack_dir / "pack.json"),
            "backupManifestPath": str(manifest_path), "files": [rel], "appliedAt": applied_at}


# --------------------------------------------------------------------------- create

def _plain_folder_name(name: str) -> bool:
    """One folder name and nothing that walks out of the parent folder: no separator, no drive, no `..`,
    no leading dot or dash. (The name is the person's own, but it is joined to a path, so it is checked.)"""
    if not name or len(name) > 100 or name in (".", "..") or name.startswith((".", "-")):
        return False
    return not any(ch in name for ch in '/\\:\0*?"<>|')


def _apply_create(session: dict, number: int, entry: dict, model_text: str, parent_dir, extra: dict | None = None) -> dict:
    target = session.get("appDir")
    wrapper = None  # the app's own folder, when this call is the one that makes it
    if not target:
        name = (session.get("newAppName") or "").strip()
        if not name or not parent_dir:
            return _refuse("no-target", detail="say where the new app goes (--parent-dir) and what it is called")
        if not _plain_folder_name(name):
            return _refuse("bad-name", detail="the new app's name must be one plain folder name")
        # BUILD-5: `<parent>/<name>/<name>`, the layout the Manager's New app screen makes. Generation writes the
        # app to `<model dir>-app` and its ArtifactNP to `<model dir>/../ArtifactNP`, so two apps whose model
        # folders sat side by side in one parent would share -- and overwrite -- a single ArtifactNP.
        wrapper = Path(parent_dir) / name
        target = str(wrapper / name)
        if wrapper.exists():
            wrapper = None
    target_path = Path(target)
    existed = target_path.exists()
    cli = engine._cli_module()
    init_args = argparse.Namespace(name=str(target_path), from_sample=None, engine="h2local", json=True,
                                   externally_provisioned=False)
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            code = cli.run_init(init_args)
    except Exception as exc:  # noqa: BLE001 -- CliError and friends: nothing usable was created
        return _refuse("init-failed", detail=str(exc)[-300:])
    if code not in (0, None):
        return _refuse("init-failed", detail=f"exit {code}")
    model_path = target_path / "model.json"
    extra = extra or {}

    def _abandon() -> None:
        if not existed:
            _remove_tree(wrapper or target_path)

    try:
        ops = files.plan(target_path, [(rel, extra[rel].encode("utf-8")) for rel in sorted(extra)]
                         + [("model.json", model_text.encode("utf-8"))])
        files.run(target_path, ops)  # fragments first, model.json last; all or nothing
    except files.TreeError as exc:
        _abandon()
        return _refuse("unsafe-path", detail=str(exc))
    except files.TransactionError as exc:
        _abandon()
        return _refuse("write-failed", path=exc.path, detail=str(exc.cause)[-300:], notRestored=exc.not_restored)
    written = [op.rel for op in ops]
    report = engine.run_validation(model_path, rs.round_dir(session, number) / "validation-after-apply.json")
    if report is not None and report.get("status") == "failed":
        if existed:
            with contextlib.suppress(files.TransactionError):
                files.run(target_path, files.restore_ops(ops))  # the folder was here before: leave it as the scaffold
        _abandon()
        return _refuse("validation-failed-after-write", errors=engine.validation_lines(report))
    history = _commit_model(target_path, f"npdev relay: model from a browser AI (conversation {session['code']})", written)
    applied_at = _now()
    entry["appliedAt"] = applied_at
    session["appDir"] = str(target_path)
    session["state"] = "done"
    rs.save(session)
    rs.append_event(session["code"], "applied", number, created=True)
    return {"ok": True, "code": session["code"], "round": number, "intent": "create", "created": True,
            "appDir": str(target_path), "modelPath": str(model_path), "backupPath": None, "appliedAt": applied_at,
            "files": written, "historyCommitted": history}


def _commit_model(target: Path, message: str, paths: list | None = None) -> bool:
    """Best effort: the scaffold has a first commit; put the AI's model (and fragments) in a second one. Never fatal."""
    if not (target / ".git").exists():
        return False
    try:
        subprocess.run(["git", "add", "--", *(paths or ["model.json"])], cwd=target, check=True, capture_output=True)
        done = subprocess.run(["git", "commit", "--quiet", "-m", message], cwd=target, capture_output=True, text=True)
        if done.returncode != 0:  # a fresh machine has no git identity: commit under one scoped to this repository
            done = subprocess.run(["git", "-c", "user.name=NPDev", "-c", "user.email=npdev@localhost", "commit", "--quiet",
                                   "-m", message], cwd=target, capture_output=True, text=True)
        return done.returncode == 0
    except (OSError, subprocess.CalledProcessError):
        return False


# --------------------------------------------------------------------------- undo

def undo(session: dict, force: bool = False) -> dict:
    applied = [e for e in session["rounds"] if e.get("appliedAt")]
    if not applied:
        return _refuse("nothing-to-undo")
    entry = applied[-1]
    if session["intent"] == "pack":
        pack_dir = session.get("packDir")
        if not pack_dir or not entry.get("backupManifestPath"):
            return _refuse("backup-missing")
        return _undo_files(session, entry, force, root=Path(pack_dir).parent)
    if session["intent"] == "create":
        return _refuse("create-undo-unsupported",
                       detail="a created app is removed from the Apps screen, never by taking an answer back")
    if entry.get("backupManifestPath"):
        return _undo_files(session, entry, force)
    backup = entry.get("backupPath")
    if not backup or not Path(backup).is_file():
        return _refuse("backup-missing")
    model_path = authoring.resolve_model_path(session.get("appDir"))
    if model_path is None:
        return _refuse("app-missing")
    result = _stored_result(session, entry["round"]) or {}
    applied_text = _file_text(result, "model.json")
    current = model_path.read_bytes()
    if not force and applied_text is not None and current.replace(b"\r\n", b"\n") != applied_text.encode("utf-8").replace(b"\r\n", b"\n"):
        return _refuse("app-changed-since-apply")
    authoring.atomic_write_bytes(model_path, Path(backup).read_bytes())
    entry["appliedAt"], entry["backupPath"] = None, None
    session["state"] = "answer-received"
    rs.save(session)
    rs.append_event(session["code"], "undone", entry["round"])
    return {"ok": True, "code": session["code"], "round": entry["round"], "modelPath": str(model_path)}


def _undo_files(session: dict, entry: dict, force: bool, root: Path | None = None) -> dict:
    """Put every file of a several-file answer back (model.json first), deleting the ones the answer created. Refuses,
    naming them, when a file was edited since; all or nothing, like the write."""
    model_path = None
    if root is None:
        model_path = authoring.resolve_model_path(session.get("appDir"))
        if model_path is None:
            return _refuse("app-missing")
        root = model_path.parent
    try:
        saved = files.load_backup(entry["backupManifestPath"])
        changed = files.changed_since(root, saved)
    except files.TreeError as exc:
        return _refuse("backup-missing", detail=str(exc))
    if changed and not force:
        return _refuse("app-changed-since-apply", changed=changed)
    try:
        files.run(root, files.undo_ops(root, saved))
    except files.TreeError as exc:
        return _refuse("backup-missing", detail=str(exc))
    except files.TransactionError as exc:
        return _refuse("undo-failed", path=exc.path, detail=str(exc.cause)[-300:], notRestored=exc.not_restored)
    entry["appliedAt"], entry["backupPath"], entry["backupManifestPath"] = None, None, None
    session["state"] = "answer-received"
    rs.save(session)
    rs.append_event(session["code"], "undone", entry["round"])
    return {"ok": True, "code": session["code"], "round": entry["round"], "modelPath": str(model_path) if model_path else None,
            "files": [item.rel for item in reversed(saved)]}
