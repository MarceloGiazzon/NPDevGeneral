"""The session-aware half of `npdev ai relay`: start a conversation, read an answer, decide what happens next.

npdev_relay.py is only the command-line surface. This module owns the pipeline, so the Manager, the bench
and the tests all go through the same code:

  start()   compose round 1 and open a session (change/fix: remember the model the AI was shown)
  ingest()  text stage (npdev_relay_ingest) -> desire paths -> validation -> [authoring gate]
            -> one outcome + `next` (what the user does next) -> everything saved in the session

Every decision the Manager shows comes out of ingest() as data (`outcome`, `findings`, `next`, `preview`):
the UI decides nothing. Stdlib only.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

import npdev_desire_paths as desire_paths
import npdev_relay_ingest as ri
import npdev_relay_prompts as rp
import npdev_relay_scrub as scrub
import npdev_relay_session as rs
import npdev_relay_targets as rt
from npdev_ai_config import AiConfigError

# Outcomes whose follow-up is a short text for the SAME chat (the AI can fix them), by compose_same_chat.
SAME_CHAT_OUTCOMES = ("TRUNCATED", "ELIDED", "BAD_JSON", "PARTIAL", "FILE_LINK_ONLY", "NO_FILES", "NEEDS_FIX")
MAX_ERROR_LINES = 20
DUPLICATE_SUFFIX_RE = re.compile(r"\s*\(\d+\)(?=\.[A-Za-z0-9]+$)")  # "model (1).json": a browser's duplicate download


class RelayStageError(AiConfigError):
    """A broken invocation (no such conversation, no model file ...), as opposed to an outcome."""


# --------------------------------------------------------------------------- helpers

def build_knowledge() -> tuple[str, dict]:
    """The compact authoring bundle the bench measures -- exactly what `npdev ai context` prints."""
    scripts_ai = str(Path(__file__).resolve().parents[1] / "scripts" / "ai")
    if scripts_ai not in sys.path:
        sys.path.insert(0, scripts_ai)
    import build_core_context

    try:
        return build_core_context.build_bundle(compact=True)
    except (OSError, ValueError) as exc:
        raise AiConfigError(f"cannot build the authoring context: {exc}") from None


def find_model_file(app_dir) -> Path:
    """model.json of an app, given the folder the Manager knows it by: the model folder itself, the generated
    `<name>-app` folder (its model sits next to it), or an AppGen output root (`App` beside `Input`)."""
    if not app_dir:
        raise RelayStageError("say which app to change")
    base = Path(app_dir)
    if base.is_file():
        return base
    import npdev_relay_authoring as authoring  # the one resolver check, apply and undo use too

    found = authoring.resolve_model_path(app_dir)
    if found is None:
        raise RelayStageError("the app's model was not found; pick the app again")
    return found


def _read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as exc:
        raise RelayStageError(f"cannot read {path.name} ({type(exc).__name__})") from None


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _cli_module():
    """npdev_cli, whether this process IS npdev_cli (run as a script) or imported it."""
    main = sys.modules.get("__main__")
    if main is not None and hasattr(main, "run_validate_semantic"):
        return main
    import npdev_cli

    return npdev_cli


def _wrong_session_error(exc: Exception) -> RelayStageError:
    return RelayStageError(str(exc))


def load_session(code: str) -> dict:
    try:
        return rs.load(code)
    except rs.SessionNotFound:
        raise
    except rs.RelaySessionError as exc:
        raise _wrong_session_error(exc) from None


# --------------------------------------------------------------------------- start / prompts

def _profile(target_id: str):
    """The chat AI's profile (its answer budget among other numbers), or None when the list does not know it."""
    try:
        return rt.find(target_id)
    except rt.RelayTargetsError:
        return None


def start(*, intent: str, ask: str, app_dir=None, new_app_name=None, target: str = "generic",
          language: str = "English", interview: bool = False, phase: str = "mvp", errors: str = "",
          answer_size=None) -> dict:
    """Open a session and compose round 1. change/fix need the app (its model is what the AI is shown).

    answer_size: None/"auto" (the default: the prompt is exactly the measured one), "small"/"medium"/"large" or a
    number of characters -- a new app is then asked to start with its core so the answer fits. The returned
    `wholeFileFits` says, for a change or a fix, whether this model can be written out whole in ONE answer."""
    try:
        size = rt.parse_answer_size(answer_size)
    except rt.RelayTargetsError as exc:
        raise RelayStageError(str(exc)) from None
    ask = (ask or "").strip()
    if intent not in rs.INTENTS:
        raise RelayStageError(f"unknown intent {intent!r}")
    if intent == "create" and not ask:
        raise RelayStageError("say what the app should do")
    if intent == "change" and not ask:
        raise RelayStageError("say what should change")
    model_text = ""
    if intent in ("change", "fix"):
        model_text = _read_text(find_model_file(app_dir))
    # The text of a failed build or boot goes into a prompt that leaves the computer: scrubbed HERE, whoever the caller is.
    errors, redactions = scrub.scrub_errors(errors, app_dir) if errors else (errors, {})
    bundle, manifest = build_knowledge()
    profile = _profile(target)
    budget = rt.answer_budget(profile, 0, size) if profile else None
    try:
        session = rs.create(intent, ask, target, language, app_dir, phase, interview=interview,
                            new_app_name=new_app_name, base_model_text=model_text if model_text else None,
                            knowledge_sha256=manifest["contentSha256"], answer_size=size)
    except rs.RelaySessionError as exc:
        raise RelayStageError(str(exc)) from None
    text = rp.compose_round1(rp.load_prompts(), code=session["code"], intent=intent, ask=ask, language=language,
                             knowledge=bundle, model=model_text, errors=errors, interview=interview,
                             budget=budget if size is not None else None)
    rs.add_round(session, text, "round1")
    result = {"ok": True, "code": session["code"], "intent": intent, "round": 1,
              "bytes": len(text.encode("utf-8")), "knowledgeSha256": manifest["contentSha256"], "prompt": text,
              "errorRedactions": redactions, "answerBudgetChars": budget}
    if intent in ("change", "fix") and profile:
        # The plan's rule (T4.2): above 60% of the budget the model cannot be rewritten whole in one answer.
        result["modelChars"] = len(model_text)
        result["wholeFileFits"] = rt.whole_file_fits(profile, len(model_text), 0, size)
    return result


def stored_prompt(code: str, round_number: int | None = None) -> dict:
    session = load_session(code)
    number = round_number or len(session["rounds"])
    if number < 1 or number > len(session["rounds"]):
        raise RelayStageError("this conversation has no such round")
    path = rs.round_dir(session, number) / "prompt.txt"
    text = _read_text(path)
    return {"ok": True, "code": session["code"], "round": number, "bytes": len(text.encode("utf-8")),
            "promptKind": session["rounds"][number - 1]["promptKind"], "prompt": text, "path": str(path)}


def write_attachment(code: str, round_number: int | None = None) -> dict:
    """The prompt as a file to attach to a chat, kept in the session folder (never in Downloads, where the
    downloads watcher would read it back). `chatMessage` is the one short line to write in the chat beside it.
    The round is recorded as delivered by attachment."""
    found = stored_prompt(code, round_number)
    session = load_session(code)
    target = rs.round_dir(session, found["round"]) / f"npdev-prompt-{session['code']}-{found['round']}.txt"
    target.write_bytes(found["prompt"].encode("utf-8"))
    rs.set_delivery(session, found["round"], "attachment")
    return {"ok": True, "code": session["code"], "round": found["round"], "bytes": found["bytes"], "file": str(target),
            "chatMessage": rp.attachment_message(rp.load_prompts())}


def _targets_error(exc: Exception) -> RelayStageError:
    return RelayStageError(str(exc))


def targets_list() -> dict:
    """The chat AIs this NPDev knows, with where to open each and how much text it takes pasted."""
    try:
        return {"ok": True, "schemaVersion": rt.SCHEMA_VERSION, "targets": rt.profiles()}
    except rt.RelayTargetsError as exc:
        raise _targets_error(exc) from None


def choose_target(code: str, target_id: str) -> dict:
    """The person picked a chat AI in step 2: remember it on the conversation and say whether the latest prompt
    goes into that chat as a plain paste (`fits`) or should be offered as a file to attach. The CLI decides; the
    Manager only draws it."""
    try:
        profile = rt.find(target_id)
    except rt.RelayTargetsError as exc:
        raise _targets_error(exc) from None
    found = stored_prompt(code)
    chars = len(found["prompt"])
    fitting = rt.fits(profile, chars)
    try:
        rs.set_target(code, target_id, chars=chars, fits=fitting)
    except rs.SessionNotFound:
        raise
    except rs.RelaySessionError as exc:
        raise _wrong_session_error(exc) from None
    return {"ok": True, "code": found["code"], "round": found["round"], "target": profile, "chars": chars,
            "fits": fitting, "delivery": "paste" if fitting else "attachment"}


def session_summary(session: dict) -> dict:
    return {"code": session["code"], "intent": session["intent"], "state": session["state"],
            "phase": session["phase"], "target": session["target"], "language": session["language"],
            "appDir": session.get("appDir"), "newAppName": session.get("newAppName"),
            "ask": session.get("ask", ""), "createdAt": session.get("createdAt"), "updatedAt": session.get("updatedAt"),
            "round": len(session["rounds"]), "rounds": session["rounds"], "trail": session.get("trail", [])}


def status(code: str) -> dict:
    session = load_session(code)
    last = None
    for entry in reversed(session["rounds"]):
        path = rs.round_dir(session, entry["round"]) / "result.json"
        if entry.get("outcome") and path.is_file():
            try:
                last = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                last = None
            break
    return {"ok": True, "session": session_summary(session), "lastResult": last}


def list_sessions() -> dict:
    return {"ok": True, "sessions": [session_summary(s) for s in rs.list_open()]}


CSV_COLUMNS = ("date", "target", "plan", "prompt_id", "intent", "round", "prompt_bytes", "paste_ok", "answer_chars", "copy_method",
               "outcome", "findings", "final", "rounds_to_pass", "minutes", "notes")
# How the answer reached the Manager, in the words the calibration log uses. The clipboard watcher cannot tell the AI's Copy
# button from a hand-made selection, so it says "clipboard" and the person corrects the row if it matters.
COPY_METHOD = {"clipboard-watch": "clipboard", "paste": "manual-paste", "drop": "drop", "file-picker": "file", "downloads-watch": "download"}


def _minutes(session: dict):
    try:
        from datetime import datetime
        a = datetime.fromisoformat(str(session.get("createdAt")).replace("Z", "+00:00"))
        b = datetime.fromisoformat(str(session.get("updatedAt")).replace("Z", "+00:00"))
        return round((b - a).total_seconds() / 60, 1)
    except (TypeError, ValueError):
        return None


def _round_rows(session: dict) -> list:
    """One calibration-log row per round of a session. Columns this program cannot know (plan, prompt_id, notes) are left blank
    for the person; `paste_ok` is `yes` when an answer came back for a round that was pasted, `attachment` when it went as a file."""
    rounds = session["rounds"]
    passed_at = next((r["round"] for r in rounds if r.get("outcome") == rs.READY_OUTCOME), None)
    state = session.get("state")
    final = "passed" if passed_at else ("abandoned" if state == "abandoned" else "open")
    rows = []
    for entry in rounds:
        number = entry["round"]
        directory = rs.round_dir(session, number)
        answer_chars = None
        try:
            answer_chars = len((directory / "answer-raw.txt").read_text(encoding="utf-8"))
        except OSError:
            pass
        codes = []
        try:
            stored = json.loads((directory / "result.json").read_text(encoding="utf-8"))
            codes = sorted({f.get("code") for f in stored.get("findings") or [] if f.get("severity") in ("error", "warning") and f.get("code")})
        except (OSError, ValueError):
            pass
        delivery = entry.get("delivery")
        rows.append({
            "date": str(session.get("createdAt") or "")[:10], "target": session.get("target"), "plan": "", "prompt_id": "",
            "intent": session.get("intent"), "round": number, "prompt_bytes": entry.get("promptBytes"),
            "paste_ok": "attachment" if delivery == "attachment" else ("yes" if entry.get("outcome") else ""),
            "answer_chars": answer_chars, "copy_method": COPY_METHOD.get(entry.get("answerSource"), ""),
            "outcome": entry.get("outcome") or "", "findings": " ".join(codes), "final": final,
            "rounds_to_pass": passed_at if passed_at else "", "minutes": _minutes(session), "notes": "",
        })
    return rows


def stats() -> dict:
    """What the conversations on this computer say about each chat AI: the log rows for the calibration run (ACCEPTANCE section C),
    and, per target, how many conversations passed, how many rounds they took, the largest prompt that got an answer and the
    largest complete answer. Read-only, from the stored sessions; nothing here is sent anywhere."""
    rows, by_target = [], {}
    for session in rs.list_all():
        session_rows = _round_rows(session)
        rows.extend(session_rows)
        t = by_target.setdefault(session.get("target"), {"target": session.get("target"), "conversations": 0, "passed": 0, "roundsToPass": [],
                                                           "outcomes": {}, "largestPromptWithAnAnswer": 0, "largestReadyAnswer": 0})
        if not session["rounds"]:
            continue
        t["conversations"] += 1
        if session_rows and session_rows[0]["final"] == "passed":
            t["passed"] += 1
            t["roundsToPass"].append(session_rows[0]["rounds_to_pass"])
        for row in session_rows:
            if row["outcome"]:
                t["outcomes"][row["outcome"]] = t["outcomes"].get(row["outcome"], 0) + 1
                if row["paste_ok"] == "yes" and row["prompt_bytes"]:
                    t["largestPromptWithAnAnswer"] = max(t["largestPromptWithAnAnswer"], row["prompt_bytes"])
                if row["outcome"] == rs.READY_OUTCOME and row["answer_chars"]:
                    t["largestReadyAnswer"] = max(t["largestReadyAnswer"], row["answer_chars"])
    summary = []
    for t in by_target.values():
        rounds = t.pop("roundsToPass")
        t["averageRoundsToPass"] = round(sum(rounds) / len(rounds), 2) if rounds else None
        t["passedInTwoRoundsOrFewer"] = sum(1 for r in rounds if r <= 2)
        t["suggestedOutputBudgetChars"] = int(t["largestReadyAnswer"] * 0.8) if t["largestReadyAnswer"] else None
        summary.append(t)
    summary.sort(key=lambda t: str(t["target"]))
    rows.sort(key=lambda r: (r["date"], str(r["target"]), r["round"]))
    return {"ok": True, "columns": list(CSV_COLUMNS), "rows": rows, "byTarget": summary}


def abort(code: str) -> dict:
    try:
        session = rs.abandon(code)
    except rs.SessionNotFound:
        raise
    except rs.RelaySessionError as exc:
        raise _wrong_session_error(exc) from None
    return {"ok": True, "code": session["code"], "state": session["state"]}


# --------------------------------------------------------------------------- answers from files

def _fence_for(text: str) -> str:
    """A code fence longer than any run of backticks inside `text`, so the block cannot close early."""
    longest = max((len(m) for m in re.findall(r"`+", text)), default=0)
    return "`" * max(3, longest + 1)


def _block(label: str, text: str, language: str = "") -> str:
    fence = _fence_for(text)
    return f"NPDEV-FILE {label}\n{fence}{language}\n{text.strip()}\n{fence}"


def raw_from_files(paths: list[str], code: str, round_number: int, phase: str = "mvp") -> tuple[str, list[dict]]:
    """Turn files the user brought (a model.json the AI let them download, a zip of files ...) into one answer text.

    A file that already carries NPDEV markers is the answer as is. A JSON object without markers becomes one
    block labelled with the file NAME (a browser's " (1)" duplicate suffix removed). A .zip is read in memory
    by npdev_relay_zip (never extracted): each allowed member becomes a block labelled with its path. Other
    text is appended as it is: it may be a copy of the answer saved by the user.
    """
    import npdev_relay_zip as relay_zip

    findings: list[dict] = []
    blocks: list[str] = []
    named = 0
    for item in paths:
        path = Path(item)
        if path.suffix.lower() == ".zip":
            archive = relay_zip.read_zip(path, ri.load_policy(), phase)
            findings.extend(archive["findings"])
            for member in archive["entries"]:
                if member["status"] == "denied" or member["text"] is None:
                    findings.append({"code": "zip-entry-denied", "severity": "warning",
                                     "detail": {"path": member["path"], "reason": member["reason"]}})
                    continue
                language = "json" if member["path"].endswith(".json") else ""
                blocks.append(_block(member["path"], member["text"], language))
                named += 1
            continue
        text = _read_text(path).replace("\r\n", "\n")
        if ri.BEGIN_RE.search(text):
            blocks.append(text)
            continue
        label = DUPLICATE_SUFFIX_RE.sub("", path.name)
        try:
            value = json.loads(text)
        except ValueError:
            value = None
        if isinstance(value, dict) and path.suffix.lower() == ".json":
            blocks.append(_block(label, text, "json"))
            named += 1
        else:
            blocks.append(text)
    if not blocks:
        return "", findings
    if named:
        raw = f"NPDEV-BEGIN {code}-{round_number}\n" + "\n".join(blocks) + f"\nNPDEV-END {code}-{round_number} files={named}\n"
    else:
        raw = "\n".join(blocks)
    return raw, findings


# --------------------------------------------------------------------------- ingest

def stateless_ingest(raw: str, code: str, round_number: int, phase: str) -> dict:
    """No session on disk (the Prompter's paste box): the text stage only, nothing saved. The short same-chat
    follow-up still comes back in `next` -- it needs only the code and the round, not a stored conversation."""
    result = ri.ingest_text(raw, session=code, expected_round=round_number, policy=ri.load_policy(), phase=phase)
    outcome = result["outcome"]
    if outcome in ("PROMPT_ECHO", "WRONG_SESSION", "STALE_ROUND", "QUESTIONS"):
        result["next"] = {"kind": "user-action", "messageId": outcome}
    elif outcome in SAME_CHAT_OUTCOMES:
        errors = "\n".join(_finding_lines(result))
        text = rp.compose_same_chat(rp.load_prompts(), code=code, rnd=round_number + 1, outcome=outcome, errors=errors)
        result["next"] = {"kind": "copy-prompt", "messageId": outcome, "prompt": text}
    return result


def ingest(code: str, raw: str, *, source: str | None = None, round_number: int | None = None,
           pre_findings: list[dict] | None = None) -> dict:
    """Read one answer for an open session and save everything. Raises SessionNotFound when `code` is unknown."""
    session = load_session(code)
    if session.get("state") == "abandoned":
        raise RelayStageError("this conversation was closed")
    if not session["rounds"]:
        raise RelayStageError("this conversation has no prompt yet")
    number = round_number or len(session["rounds"])
    duplicate = _stored_duplicate(session, raw)
    if duplicate is not None:
        return duplicate
    result = ri.ingest_text(raw, session=session["code"], expected_round=number, policy=ri.load_policy(),
                            phase=session["phase"])
    for finding in pre_findings or []:
        result["findings"].append(finding)
    if result["outcome"] == ri.PARSED:
        _after_parsed(session, number, result)
    _attach_next(session, number, result)
    _check_going_in_circles(session, number, result)
    rs.record_answer(session, number, raw, result, source)
    nxt = result.get("next") or {}
    if result["outcome"] not in rs.NOT_AN_ANSWER and nxt.get("kind") == "copy-prompt" and nxt.get("prompt"):
        kind = "fresh-chat" if nxt.get("messageId") in ("GOING_IN_CIRCLES", "LIMIT_REACHED") else "same-chat"
        rs.add_round(session, nxt["prompt"], kind)
    return result


def _stored_duplicate(session: dict, raw: str):
    """The same answer arriving twice (clipboard watcher AND a manual paste) must give the SAME result, not a
    confusing 'previous answer' for the second copy."""
    wanted = _sha256(raw)
    for entry in session["rounds"]:
        if not entry.get("outcome"):
            continue
        directory = rs.round_dir(session, entry["round"])
        try:
            same = _sha256((directory / "answer-raw.txt").read_text(encoding="utf-8")) == wanted
            if same:
                stored = json.loads((directory / "result.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if same:
            stored.setdefault("findings", []).append({"code": "duplicate-answer", "severity": "info"})
            return stored
    return None


# --------------------------------------------------------------------------- after the text stage

def _after_parsed(session: dict, number: int, result: dict) -> None:
    """PARSED -> desire paths -> validation -> READY / NEEDS_FIX (a missing validator keeps PARSED)."""
    entry = next((f for f in result["files"] if f["path"] == "model.json" and f["status"] == "allowed"), None)
    if entry is None:
        return
    try:
        model = json.loads(entry["content"])
    except ValueError:
        return
    if not isinstance(model, dict):
        result["outcome"] = "NEEDS_FIX"
        result["errorLines"] = ["the model must be a JSON object - found something else - start from the example"]
        return
    fixed, fixes = desire_paths.fix_model(model)
    result["desirePathFixes"] = fixes
    if fixes:
        text = json.dumps(fixed, indent=2, ensure_ascii=False) + "\n"
        entry["content"], entry["bytes"], entry["sha256"] = text, len(text.encode("utf-8")), _sha256(text)
    import npdev_relay_files as files_mod

    _, unsupported = files_mod.answer_files(result)
    if unsupported:  # allowed by the ingest policy, but nothing here can apply them yet: never accept part of an answer
        result["outcome"] = "NEEDS_FIX"
        result["errorLines"] = [f"{path} - NPDev cannot apply this kind of file yet - send only model.json, "
                                "npdev-submission.json and the files under fragments/ and contexts/" for path in unsupported]
        return
    model_path = _validation_model_path(session, number, result, entry, fixed)
    report = run_validation(model_path, rs.round_dir(session, number) / "validation-report.json")
    if report is None:
        result["findings"].append({"code": "validator-unavailable", "severity": "warning"})
        return
    result["validation"] = report
    if report.get("status") == "failed":
        result["outcome"] = "NEEDS_FIX"
        result["errorLines"] = validation_lines(report)
        return
    finish_ready(session, number, result, entry)


def _validation_model_path(session: dict, number: int, result: dict, entry: dict, model: dict) -> Path:
    """The model.json the validator reads. Usually the answer's model.json written alone; when the model is split (the
    answer carries fragments, points at some, or the app's own model is made of several files) a folder holding the
    app's current files with the answer's on top, so every `$ref` resolves."""
    import npdev_relay_authoring as authoring
    import npdev_relay_files as files_mod

    extra, _ = files_mod.answer_files(result)
    base = authoring.resolve_model_path(session.get("appDir")) if session.get("intent") != "create" else None
    try:
        split = bool(extra) or authoring.is_split_root(model) or (base is not None and files_mod.is_split(base))
        if split:
            return files_mod.stage(base, extra, entry["content"], rs.round_dir(session, number) / "stage")
    except files_mod.TreeError:
        pass  # cannot be staged: the validator below says what is wrong, as it always has
    directory = rs.round_dir(session, number) / "files"
    directory.mkdir(parents=True, exist_ok=True)
    model_path = directory / "model.json"
    model_path.write_bytes(entry["content"].encode("utf-8"))
    return model_path


def _ai_manifest(result: dict):
    """The AI's light manifest (npdev-submission.json), parsed, or None when it sent none or sent junk."""
    for item in result["files"]:
        if item["path"] == "npdev-submission.json" and item["status"] == "allowed":
            try:
                value = json.loads(item["content"])
            except ValueError:
                return None
            return value if isinstance(value, dict) else None
    return None


def finish_ready(session: dict, number: int, result: dict, model_entry: dict) -> None:
    """The validated model is acceptable on its own. Create is READY after a plain-words preview; change/fix
    also pass the repo's authoring gate (renames, removals and security changes need the AI's manifest and,
    where only the owner can decide, the owner's yes)."""
    import npdev_relay_authoring as authoring
    import npdev_relay_files as files_mod

    work = rs.round_dir(session, number) / "gate"
    extra, _ = files_mod.answer_files(result)
    outcome = authoring.evaluate_change(session, model_entry["content"], _ai_manifest(result), work, extra_files=extra)
    result["outcome"] = outcome["outcome"]
    result["findings"].extend(outcome["findings"])
    if outcome["preview"] is not None:
        import npdev_relay_preview_pt as preview_pt  # the sentences in the person's language; codes and params stay

        result["preview"] = preview_pt.localize_preview(outcome["preview"], session.get("language"))
    if outcome["errors"]:
        result["errorLines"] = list(outcome["errors"])
    text = outcome.get("candidateModelText")
    if text and text != model_entry["content"]:  # the gate bumped the version: keep what apply will write
        model_entry["content"], model_entry["bytes"], model_entry["sha256"] = text, len(text.encode("utf-8")), _sha256(text)
    if outcome["outcome"] == "PARSED":  # not the AI's fault: base moved, or the gate could not run here
        result["next"] = outcome["next"]
        if outcome.get("baseMoved"):
            result["baseMoved"] = True


def run_validation(model_path: Path, report_path: Path):
    """The standalone Java validator's report as a dict, or None when it cannot run on this machine (no Java,
    no staged jar and no Gradle) -- the AI did nothing wrong then, so that is never a NEEDS_FIX."""
    cli = _cli_module()
    try:
        cli.run_validate_semantic(model_path, report_path, quiet=True)
    except (Exception, SystemExit):  # noqa: BLE001 -- CliError and friends: all mean "could not validate here"
        return None
    try:
        return json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def validation_lines(report: dict) -> list[str]:
    """One line per error: where - what - how to fix it. Same wording the AI sees in the CLI's own loop."""
    lines: list[str] = []
    for diagnostic in report.get("diagnostics") or []:
        if diagnostic.get("severity", "error") != "error":
            continue
        parts = [str(diagnostic.get("path") or ""), str(diagnostic.get("message") or "")]
        if diagnostic.get("hint"):
            parts.append(str(diagnostic["hint"]))
        elif diagnostic.get("suggestedFix"):
            parts.append(str(diagnostic["suggestedFix"]))
        line = " - ".join(p.strip() for p in parts if p and p.strip())
        if line and line not in lines:
            lines.append(line)
    if len(lines) > MAX_ERROR_LINES:
        extra = len(lines) - MAX_ERROR_LINES
        lines = lines[:MAX_ERROR_LINES] + [f"... and {extra} more"]
    return lines


# --------------------------------------------------------------------------- what happens next

def _finding_lines(result: dict) -> list[str]:
    lines = []
    for finding in result.get("findings") or []:
        if finding.get("severity") in ("warning", "error"):
            detail = finding.get("detail")
            lines.append(f"- {finding['code']}" + (f" {json.dumps(detail, ensure_ascii=False)}" if detail else ""))
    return lines


def _error_text(result: dict) -> str:
    lines = list(result.get("errorLines") or [])
    if result["outcome"] != "NEEDS_FIX":
        lines = _finding_lines(result)
    for fix in result.get("desirePathFixes") or []:
        lines.append(f"(NPDev already rewrote {fix.get('path')}: you wrote {json.dumps(fix.get('wrote'))}, "
                     f"the right form is {json.dumps(fix.get('now'))} - write it that way)")
    return "\n".join(lines)


def _fresh_chat(session: dict, number: int, errors: str, budget: int | None = None) -> str:
    bundle, _ = build_knowledge()
    base = session.get("baseModelSha256") and rs.session_dir(session["code"]) / "base-model.json"
    model = _read_text(base) if base and base.is_file() else ""
    return rp.compose_fresh_chat(rp.load_prompts(), code=session["code"], rnd=number + 1, ask=session["ask"],
                                 language=session["language"], knowledge=bundle, model=model, errors=errors,
                                 trail=list(session.get("trail") or []), budget=budget)


CUT_OFF_OUTCOMES = ("TRUNCATED", "ELIDED")


def _cutoff_budget(session: dict, result: dict):
    """For a REPEATED cut-off of a NEW app: the smaller number of characters to ask for now, else None.

    The first cut-off keeps the measured follow-up ("send it again complete"): it may have been a fluke, and the format
    contract already lets the AI say "too long" in a sentence. From the second one in a row, the same chat asked for the
    same size would be cut at the same place, so each repeat halves the size (never below MIN_ANSWER_CHARS). A change
    or a fix must return the complete file, so it cannot shrink: `start` reports `wholeFileFits` for those instead."""
    if result["outcome"] not in CUT_OFF_OUTCOMES or session.get("intent") != "create":
        return None
    streak = 0
    for line in reversed(session.get("trail") or []):
        if str(line).split(":", 1)[0] not in CUT_OFF_OUTCOMES:
            break
        streak += 1
    profile = _profile(session.get("target") or "generic")
    if streak == 0 or profile is None:
        return None
    return rt.answer_budget(profile, streak, session.get("answerSize"))


def _attach_next(session: dict, number: int, result: dict) -> None:
    outcome = result["outcome"]
    if result.get("next"):  # already decided by a later stage (the authoring gate)
        return
    if outcome == "READY":
        result["next"] = {"kind": "apply", "messageId": "READY"}
        return
    if outcome in ("PROMPT_ECHO", "WRONG_SESSION", "STALE_ROUND", "QUESTIONS"):
        result["next"] = {"kind": "user-action", "messageId": outcome}
        return
    errors = _error_text(result)
    if outcome == "LIMIT_REACHED":
        fresh = _fresh_chat(session, number, errors)
        result["next"] = {"kind": "copy-prompt", "messageId": "LIMIT_REACHED", "prompt": fresh, "freshChatPrompt": fresh}
        return
    if outcome in SAME_CHAT_OUTCOMES:
        budget = _cutoff_budget(session, result)
        text = rp.compose_same_chat(rp.load_prompts(), code=session["code"], rnd=number + 1, outcome=outcome,
                                    errors=errors, budget=budget)
        result["next"] = {"kind": "copy-prompt", "messageId": outcome, "prompt": text,
                          "freshChatPrompt": _fresh_chat(session, number, errors, budget)}
        return
    if outcome == "PARSED":  # text fine, but this machine cannot validate: the Manager's "Java missing" assistant
        result["next"] = {"kind": "user-action", "messageId": "JAVA_MISSING"}


def _check_going_in_circles(session: dict, number: int, result: dict) -> None:
    """The same mistake on two consecutive rounds: stop repeating the same message to the same AI."""
    signature = rs.error_signature(result)
    if signature is None or number < 2 or result["outcome"] in rs.NOT_AN_ANSWER:
        return
    if session["rounds"][number - 2].get("errorSignature") != signature:
        return
    budget = _cutoff_budget(session, result)
    if budget is not None and budget > rt.MIN_ANSWER_CHARS:
        return  # a repeated cut-off is not "the same mistake": the follow-up already asks for something smaller
    result["findings"].append({"code": "going-in-circles", "severity": "warning"})
    fresh = _fresh_chat(session, number, _error_text(result), budget)
    result["next"] = {"kind": "copy-prompt", "messageId": "GOING_IN_CIRCLES", "prompt": fresh, "freshChatPrompt": fresh}
