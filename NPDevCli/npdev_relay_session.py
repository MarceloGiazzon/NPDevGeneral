"""Per-user store for one browser-AI relay conversation ("Use the AI in your browser").

A session is a folder under the same per-user NPDev folder `npdev_ai_config.mine_path()` lives in
(`<that folder>/ai-relay/<code>/`), so it follows APPDATA / XDG_CONFIG_HOME / NPDEV_AI_CONFIG exactly
like the user's AI settings do and nothing here recomputes those variables:

    session.json                      the session (schemas/ai/ai-relay-session.schema.json)
    base-model.json                   model.json as it was when a change/fix session started (optional)
    transcript.jsonl                  one JSON object per event: kind, at, round, ...
    round-<n>/prompt.txt              the prompt the user copied
    round-<n>/answer-raw.txt          the answer exactly as it came back
    round-<n>/result.json             the ai-relay-ingest-result for that answer
    round-<n>/files/<path>            the files the answer carried (allowed + quarantine only; a denied
                                      file's bytes are never written anywhere)

Every write goes to a temporary file in the same folder and is moved into place with os.replace, so a
crash or a second process never leaves a half-written session.json. Standard library only: the Manager
ships a private Python without third-party packages.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

import npdev_ai_config
import npdev_relay_ingest as relay_ingest

SCHEMA_VERSION = "npdev-ai-relay-session.v1"
ROOT_DIR_NAME = "ai-relay"
INTENTS = ("create", "change", "fix", "pack")
TARGETS = ("chatgpt", "gemini", "claude", "grok", "kimi", "deepseek", "google-ai-mode", "generic")
PHASES = ("mvp", "f4", "pack")
PACK_ID_PATTERN = r"^[a-z][a-z0-9_-]*$"  # the same pattern pack.schema.json puts on `pack`
PROMPT_KINDS = ("round1", "same-chat", "fresh-chat")
DELIVERIES = ("paste", "attachment")
ANSWER_SOURCES = ("clipboard-watch", "paste", "drop", "file-picker", "downloads-watch")
CLOSED_STATES = ("done", "abandoned")
# Outcomes that say nothing about the prompt of the round: the user pasted our own prompt back, an answer
# of another session or of an earlier round, or the chat service's quota message. record_answer logs them
# in the transcript and leaves the round and the session exactly as they were (still waiting).
NOT_AN_ANSWER = frozenset({"PROMPT_ECHO", "WRONG_SESSION", "STALE_ROUND", "LIMIT_REACHED"})
READY_OUTCOME = "READY"
_CODE_RE = re.compile("^[" + relay_ingest.SESSION_ALPHABET + "]{4}$")
_MAX_CODE_ATTEMPTS = 1000


class RelaySessionError(Exception):
    """A session cannot be created, found, read or changed. The message is safe to show the user."""


class SessionNotFound(RelaySessionError):
    pass


# ----------------------------------------------------------------------------------------------
# Locations
# ----------------------------------------------------------------------------------------------

def root() -> Path:
    """The folder holding every relay session of this user (not created here)."""
    return Path(npdev_ai_config.mine_path()).parent / ROOT_DIR_NAME


def normalize_code(code: str) -> str:
    cleaned = (code or "").strip().upper()
    if not _CODE_RE.match(cleaned):
        raise RelaySessionError("That is not a conversation code.")
    return cleaned


def session_dir(code: str) -> Path:
    return root() / normalize_code(code)


def round_dir(session, number: int) -> Path:
    """`<root>/<code>/round-<n>/`. `session` is the session dict or its code."""
    code = session["code"] if isinstance(session, dict) else session
    if not isinstance(number, int) or isinstance(number, bool) or number < 1:
        raise RelaySessionError("A round number starts at 1.")
    return session_dir(code) / f"round-{number}"


# ----------------------------------------------------------------------------------------------
# Atomic file helpers
# ----------------------------------------------------------------------------------------------

def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _atomic_write(path: Path, data: bytes) -> None:
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
                # Windows: an antivirus scan or an indexer may hold the target for a moment.
                if attempt == 5:
                    raise
                time.sleep(0.05 * (attempt + 1))
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def _write_text(path: Path, text: str) -> None:
    _atomic_write(path, text.encode("utf-8", errors="replace"))


def _write_json(path: Path, value) -> None:
    _write_text(path, json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def append_event(code: str, kind: str, round_number=None, **fields) -> dict:
    """Append one line to transcript.jsonl. A failure here must not lose the session, so callers log last."""
    event = {"kind": kind, "at": _now(), "round": round_number}
    event.update(fields)
    path = session_dir(code) / "transcript.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"
    with open(path, "ab") as handle:
        handle.write(line.encode("utf-8", errors="replace"))
    return event


# ----------------------------------------------------------------------------------------------
# Error signature
# ----------------------------------------------------------------------------------------------

def error_signature(result: dict) -> str | None:
    """12 hex chars identifying WHAT is wrong with an answer, independent of how it is worded.

    sha1 of the sorted list of (outcome, finding codes, validation diagnostic code + path). Only warnings and
    errors count (an info note must not hide a repeated failure) and, among validation diagnostics, only
    errors (a warning appearing or disappearing between rounds is not the same failure changing).
    Two equal signatures on consecutive rounds mean the AI is going in circles (GOING_IN_CIRCLES).
    Returns None for a READY result: there is no error to sign.
    """
    outcome = result.get("outcome")
    if outcome == READY_OUTCOME:
        return None
    parts = [["outcome", str(outcome)]]
    for finding in result.get("findings") or []:
        if finding.get("severity", "warning") in ("warning", "error"):
            parts.append(["finding", str(finding.get("code"))])
    validation = result.get("validation") or {}
    for diagnostic in validation.get("diagnostics") or []:
        if diagnostic.get("severity", "error") == "error":
            parts.append(["diagnostic", str(diagnostic.get("code") or ""), str(diagnostic.get("path") or "")])
    parts.sort()
    blob = json.dumps(parts, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha1(blob.encode("utf-8")).hexdigest()[:12]


# ----------------------------------------------------------------------------------------------
# The session
# ----------------------------------------------------------------------------------------------

def _claim_new_code() -> str:
    """Pick a code whose folder does not exist yet and create that folder (mkdir is the atomic claim)."""
    root().mkdir(parents=True, exist_ok=True)
    for _ in range(_MAX_CODE_ATTEMPTS):
        code = relay_ingest.new_session_code()
        directory = root() / code
        if directory.exists():
            continue
        try:
            directory.mkdir()
        except FileExistsError:
            continue
        return code
    raise RelaySessionError("Could not find a free conversation code. Remove old conversations and try again.")


def create(intent: str, ask: str, target: str, language: str, app_dir, phase: str, *,
           interview: bool = False, new_app_name=None, base_model_text=None, knowledge_sha256=None,
           answer_size=None, pack_id=None, pack_base_text=None) -> dict:
    """Start a session: claim a free code, write session.json (state waiting-answer, no rounds yet).

    `base_model_text` (change/fix: the text of model.json right now) is saved as base-model.json and its
    sha256 recorded as baseModelSha256, so apply can tell whether the model moved on in the meantime.
    `pack_id` (intent pack) names the reusable part being written; `pack_base_text` (a NEW VERSION of an existing
    pack) is that pack's current pack.json, saved as base-pack.json with its sha256 in packBase.
    """
    if intent not in INTENTS:
        raise RelaySessionError(f"Unknown intent {intent!r}.")
    if intent == "pack":
        phase = "pack"
        if not pack_id or not re.fullmatch(PACK_ID_PATTERN, pack_id):
            raise RelaySessionError("A pack conversation needs a pack id: lowercase letters, digits, '-' or '_', starting with a letter.")
    elif pack_id or pack_base_text is not None:
        raise RelaySessionError("A pack id belongs to a pack conversation only.")
    if target not in TARGETS:
        raise RelaySessionError(f"Unknown target {target!r}.")
    if phase not in PHASES:
        raise RelaySessionError(f"Unknown phase {phase!r}.")
    code = _claim_new_code()
    now = _now()
    session = {
        "schemaVersion": SCHEMA_VERSION,
        "code": code,
        "createdAt": now,
        "updatedAt": now,
        "intent": intent,
        "target": target,
        "language": language,
        "interview": bool(interview),
        "phase": phase,
        "appDir": str(app_dir) if app_dir else None,
        "newAppName": new_app_name or None,
        "baseModelSha256": None,
        "ask": ask,
        "state": "waiting-answer",
        "trail": [],
        "rounds": [],
    }
    if knowledge_sha256:
        session["knowledgeSha256"] = knowledge_sha256
    if answer_size is not None:  # absent = automatic; an old session without it stays valid
        session["answerSize"] = answer_size
    if base_model_text is not None:
        data = base_model_text.encode("utf-8", errors="replace")
        _atomic_write(session_dir(code) / "base-model.json", data)
        session["baseModelSha256"] = _sha256(data)
    if pack_id:
        session["packId"] = pack_id
        session["packBase"] = None
        if pack_base_text is not None:
            data = pack_base_text.encode("utf-8", errors="replace")
            _atomic_write(session_dir(code) / "base-pack.json", data)
            session["packBase"] = _sha256(data)
    _write_json(session_dir(code) / "session.json", session)
    append_event(code, "session-created", None, intent=intent, target=target, phase=phase)
    return session


def load(code: str) -> dict:
    path = session_dir(code) / "session.json"
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise SessionNotFound("There is no conversation with that code.") from None
    except OSError as error:
        raise RelaySessionError(f"The conversation could not be read ({error.__class__.__name__}).") from None
    try:
        session = json.loads(text)
    except ValueError:
        raise RelaySessionError("The conversation file is damaged.") from None
    if not isinstance(session, dict) or session.get("code") != normalize_code(code):
        raise RelaySessionError("The conversation file is damaged.")
    return session


def save(session: dict) -> dict:
    session["updatedAt"] = _now()
    _write_json(session_dir(session["code"]) / "session.json", session)
    return session


def _round_entry(session: dict, number: int) -> dict:
    for entry in session["rounds"]:
        if entry["round"] == number:
            return entry
    raise RelaySessionError(f"This conversation has no round {number}.")


def add_round(session: dict, prompt_text: str, prompt_kind: str, delivery=None) -> dict:
    """Store a prompt as the next round and wait for its answer. Returns the round entry."""
    if prompt_kind not in PROMPT_KINDS:
        raise RelaySessionError(f"Unknown prompt kind {prompt_kind!r}.")
    if delivery is not None and delivery not in DELIVERIES:
        raise RelaySessionError(f"Unknown delivery {delivery!r}.")
    if session.get("state") == "abandoned":
        raise RelaySessionError("This conversation was abandoned.")
    number = len(session["rounds"]) + 1
    data = prompt_text.encode("utf-8", errors="replace")
    _atomic_write(round_dir(session, number) / "prompt.txt", data)
    entry = {"round": number, "promptKind": prompt_kind, "promptSha256": _sha256(data), "promptBytes": len(data)}
    if delivery:
        entry["delivery"] = delivery
    session["rounds"].append(entry)
    session["state"] = "waiting-answer"
    save(session)
    append_event(session["code"], "prompt-composed", number, promptKind=prompt_kind,
                 promptSha256=entry["promptSha256"], promptBytes=entry["promptBytes"], delivery=delivery)
    return entry


def _safe_target(files_root: Path, relative: str):
    """files_root/relative if it stays inside files_root and is a plain relative path, else None."""
    if not isinstance(relative, str) or not relative or relative.startswith(("/", "\\")):
        return None
    parts = relative.replace("\\", "/").split("/")
    if any(part in ("", ".", "..") or ":" in part for part in parts):
        return None
    target = files_root.joinpath(*parts)
    try:
        target.resolve().relative_to(files_root.resolve())
    except ValueError:
        return None
    return target


def record_answer(session: dict, round_number: int, raw: str, result: dict, source) -> dict:
    """Keep an answer: answer-raw.txt, result.json, its allowed/quarantine files, outcome and signature.

    Outcomes in NOT_AN_ANSWER are only logged (transcript kind answer-ignored); the round and the session
    stay as they were. Any other outcome fills the round's outcome / errorSignature / answerSource, sets the
    state to ready-to-apply for READY and answer-received otherwise, and for a non-READY outcome appends
    "<outcome>:<signature>" to session["trail"]. Returns the round entry.
    """
    if source is not None and source not in ANSWER_SOURCES:
        raise RelaySessionError(f"Unknown answer source {source!r}.")
    entry = _round_entry(session, round_number)
    outcome = result.get("outcome")
    code = session["code"]
    if outcome in NOT_AN_ANSWER:
        append_event(code, "answer-ignored", round_number, outcome=outcome, answerSource=source)
        return entry
    directory = round_dir(session, round_number)
    _write_text(directory / "answer-raw.txt", raw)
    _write_json(directory / "result.json", result)
    stored = 0
    for item in result.get("files") or []:
        if item.get("status") not in ("allowed", "quarantine") or not isinstance(item.get("content"), str):
            continue
        target = _safe_target(directory / "files", item.get("path"))
        if target is None:
            append_event(code, "file-skipped", round_number, path=str(item.get("path")), reason="unsafe-path")
            continue
        _write_text(target, item["content"])
        stored += 1
    signature = error_signature(result)
    entry["answerSource"] = source
    entry["outcome"] = outcome
    entry["errorSignature"] = signature
    if outcome == READY_OUTCOME:
        session["state"] = "ready-to-apply"
    else:
        session["state"] = "answer-received"
        session.setdefault("trail", []).append(f"{outcome}:{signature}")
    save(session)
    append_event(code, "answer-recorded", round_number, outcome=outcome, answerSource=source,
                 errorSignature=signature, filesStored=stored)
    return entry


def list_all() -> list:
    """Every session on disk, closed ones too, newest first. Damaged folders are skipped."""
    base = root()
    if not base.is_dir():
        return []
    found = []
    for child in base.iterdir():
        if not child.is_dir() or not _CODE_RE.match(child.name):
            continue
        try:
            found.append(load(child.name))
        except RelaySessionError:
            continue
    found.sort(key=lambda s: (s.get("updatedAt") or s.get("createdAt") or "", s["code"]), reverse=True)
    return found


def list_open() -> list:
    """Every session that is not done or abandoned, newest first. Damaged folders are skipped."""
    return [s for s in list_all() if s.get("state") not in CLOSED_STATES]


def abandon(code: str) -> dict:
    session = load(code)
    if session.get("state") != "abandoned":
        session["state"] = "abandoned"
        save(session)
        append_event(session["code"], "abandoned", None)
    return session


def set_target(code: str, target: str, **fields) -> dict:
    """Record which chat AI the person is taking this conversation to (chosen in step 2, after the prompt exists)."""
    if target not in TARGETS:
        raise RelaySessionError(f"Unknown target {target!r}.")
    session = load(code)
    if session.get("state") == "abandoned":
        raise RelaySessionError("This conversation was abandoned.")
    session["target"] = target
    save(session)
    append_event(session["code"], "target-chosen", None, target=target, **fields)
    return session


def set_delivery(session: dict, round_number: int, delivery: str) -> dict:
    """Record how a round's prompt reached the chat (pasted, or attached as a file). Returns the round entry."""
    if delivery not in DELIVERIES:
        raise RelaySessionError(f"Unknown delivery {delivery!r}.")
    entry = _round_entry(session, round_number)
    entry["delivery"] = delivery
    save(session)
    append_event(session["code"], "delivery-set", round_number, delivery=delivery)
    return entry
