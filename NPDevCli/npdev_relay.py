"""`npdev ai relay ...` -- use a chat AI in a browser (no API key, no MCP): compose the text to paste, read the
answer back, check it, apply it.

The engine lives in the CLI so the Manager, the bench and a terminal user all send the SAME measured text and
classify an answer the SAME way. This module is only the command-line surface; the pipeline is
npdev_relay_engine.py.

  npdev ai relay start   --intent create|change|fix --ask-file F [--app-dir D] [--new-app-name N] [--target T]
                         [--language L] [--interview] [--phase mvp|f4]
                         [--answer-size auto|small|medium|large|CHARS]      open a conversation, print round 1
  npdev ai relay prompt  --code C [--round N] [--as attachment]             print a stored prompt again
  npdev ai relay prompt  --intent ... --ask-file F [--model-file F] ...     stateless: compose, save nothing
  npdev ai relay ingest  --code C (--text-file F | --file F ...) [--source S]
                         read what the user brought back: outcome, files, findings, `next`
  npdev ai relay apply   --code C [--round N] [--confirm-warning CODE ...] [--parent-dir D]
                         write a READY answer: create the app, or change it (backup first)
  npdev ai relay undo    --code C [--force]   put back the model an applied answer replaced
  npdev ai relay status  --code C       one conversation, with the last answer's result
  npdev ai relay list                   the open conversations
  npdev ai relay abort   --code C       close a conversation

Every outcome is a RESULT (exit 0); only a broken invocation (unreadable file, unknown conversation, bad flags)
is an error (exit 2). Text always travels in files, never on argv (a prompt on a command line shows in process
listings). Stdlib only.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import npdev_relay_engine as engine
import npdev_relay_ingest as ingest_mod
import npdev_relay_prompts as prompts_mod
import npdev_relay_scrub as scrub_mod
import npdev_relay_session as sessions
from npdev_ai_config import AiConfigError

INTENTS = sessions.INTENTS
PHASES = sessions.PHASES
TARGETS = sessions.TARGETS
SOURCES = sessions.ANSWER_SOURCES
DEFAULT_LANGUAGE = "English"


# --------------------------------------------------------------------------- CLI surface

def add_arguments(ai_sub) -> None:
    """Register `ai relay`. Its own sub-parsers use dest="relay_command" -- never dest="command", which would
    overwrite args.command ("ai") and send every later `ai` call down the wrong path."""

    def json_flag(p):
        p.add_argument("--json", action="store_true", help="Answer with JSON (the Manager reads this).")

    relay = ai_sub.add_parser("relay", help="Use a browser chat AI: compose the text to paste, read the answer back.")
    rsub = relay.add_subparsers(dest="relay_command")

    start = rsub.add_parser("start", help="Open a conversation and print the round-1 prompt to paste into a chat AI.")
    start.add_argument("--intent", required=True, choices=INTENTS)
    start.add_argument("--ask-file", required=True, help="What the user wants ('-' = stdin). A file, not argv.")
    start.add_argument("--app-dir", help="The app (change and fix): its model folder or generated folder.")
    start.add_argument("--new-app-name", help="Create: the folder name for the new app.")
    start.add_argument("--target", default="generic", choices=TARGETS, help="Which chat AI the user is going to.")
    start.add_argument("--language", default=DEFAULT_LANGUAGE, help='Language the AI answers the user in.')
    start.add_argument("--interview", action="store_true", help="Let the AI ask a few questions first.")
    start.add_argument("--phase", default="mvp", choices=PHASES, help="Which files an answer may carry.")
    start.add_argument("--answer-size", default="auto", metavar="auto|small|medium|large|CHARS",
                       help="New app: how long an answer to ask the AI for. auto (default) states no size; small, medium "
                            "and large are a share of the chosen AI's answer budget; a number is exact characters.")
    start.add_argument("--errors-file", help="Fix: what went wrong (build or boot output). The CLI redacts secrets and anonymises local folders itself.")
    json_flag(start)

    prompt = rsub.add_parser("prompt", help="Print a prompt: a stored one (--code) or a new stateless one (--intent).")
    prompt.add_argument("--code", help="Session code. With no --intent: print that conversation's stored prompt.")
    prompt.add_argument("--round", type=int, help="With --code and no --intent: which round (default: the latest).")
    prompt.add_argument("--as", dest="deliver_as", choices=("text", "attachment"), default="text",
                        help="attachment: write the stored prompt to a file in the conversation folder to attach to the chat.")
    prompt.add_argument("--intent", choices=INTENTS, help="Compose a new round-1 prompt, saving nothing.")
    prompt.add_argument("--ask-file", help="What the user wants ('-' = stdin).")
    prompt.add_argument("--model-file", help="The app's current model.json (change and fix).")
    prompt.add_argument("--errors-file", help="What went wrong (fix). Secrets are redacted and local folders anonymised before it goes into the prompt.")
    prompt.add_argument("--language", default=DEFAULT_LANGUAGE)
    prompt.add_argument("--interview", action="store_true")
    prompt.add_argument("--output", help="Write the prompt to this file instead of stdout.")
    json_flag(prompt)

    ingest = rsub.add_parser("ingest", help="Read what the user brought back from the chat AI and classify it.")
    source = ingest.add_mutually_exclusive_group(required=True)
    source.add_argument("--text-file", help="The copied answer ('-' = stdin).")
    source.add_argument("--file", action="append", metavar="FILE",
                        help="A file the AI made (.json, .txt, .md or .zip); repeatable.")
    ingest.add_argument("--code", required=True, help="The session code the prompt carried.")
    ingest.add_argument("--round", type=int, help="The round this answer should belong to (default: the latest).")
    ingest.add_argument("--phase", choices=PHASES, default="mvp", help="Without a saved conversation: which files may come.")
    ingest.add_argument("--source", choices=SOURCES, help="How the answer reached the Manager.")
    json_flag(ingest)

    apply = rsub.add_parser("apply", help="Write an accepted (READY) answer: create the app, or change it with a backup.")
    apply.add_argument("--code", required=True)
    apply.add_argument("--round", type=int, help="Which round to apply (default: the latest).")
    apply.add_argument("--confirm-warning", action="append", default=[], metavar="CODE",
                       help="The owner's yes to one preview warning (repeatable); every warning that needs it must be named.")
    apply.add_argument("--parent-dir", help="Create: the folder the new app goes into.")
    json_flag(apply)

    undo = rsub.add_parser("undo", help="Put back the model an applied answer replaced.")
    undo.add_argument("--code", required=True)
    undo.add_argument("--force", action="store_true", help="Even if the app changed after the answer was applied.")
    json_flag(undo)

    status = rsub.add_parser("status", help="One conversation and its last result.")
    status.add_argument("--code", required=True)
    json_flag(status)

    listing = rsub.add_parser("list", help="The open conversations, newest first.")
    json_flag(listing)

    abort = rsub.add_parser("abort", help="Close a conversation.")
    abort.add_argument("--code", required=True)
    json_flag(abort)

    stats = rsub.add_parser("stats", help="What the conversations on this computer say about each chat AI (the calibration-log rows).")
    stats.add_argument("--csv", action="store_true", help="One row per round, in the calibration log's column order.")
    json_flag(stats)

    targets = rsub.add_parser("targets", help="The chat AIs this NPDev knows: where to open each and how much text it takes.")
    json_flag(targets)

    target = rsub.add_parser("target", help="Say which chat AI a conversation is going to; tells whether its prompt fits a paste.")
    target.add_argument("--code", required=True)
    target.add_argument("--target", required=True, help="One of the ids from `relay targets`.")
    json_flag(target)


def run(args) -> int:
    action = getattr(args, "relay_command", None)
    try:
        handler = {"start": _run_start, "prompt": _run_prompt, "ingest": _run_ingest, "status": _run_status,
                   "list": _run_list, "abort": _run_abort, "apply": _run_apply, "undo": _run_undo,
                   "targets": _run_targets, "target": _run_target, "stats": _run_stats}.get(action)
        if handler is None:
            raise AiConfigError("npdev ai relay needs a verb: start, prompt, ingest, apply, undo, status, list, abort, targets, target or stats (see --help)")
        return handler(args)
    except sessions.SessionNotFound as exc:
        return _fail(args, str(exc))
    except (AiConfigError, sessions.RelaySessionError) as exc:
        return _fail(args, str(exc))


def _fail(args, message: str) -> int:
    if getattr(args, "json", False):
        print(json.dumps({"ok": False, "error": message}))
    else:
        print(f"error: {message}", file=sys.stderr)
    return 2


# --------------------------------------------------------------------------- shared helpers

def read_input(path: str | None) -> str | None:
    """A text input: a file, or '-' for stdin. UTF-8, with or without a BOM."""
    if path is None:
        return None
    if path == "-":
        return sys.stdin.read().lstrip("﻿")
    try:
        return Path(path).read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as exc:
        raise AiConfigError(f"cannot read {path} ({type(exc).__name__})") from None


def emit_text(text: str) -> None:
    """A Windows console's code page cannot encode every character of a prompt; send UTF-8 bytes."""
    stream = getattr(sys.stdout, "buffer", None)
    if stream is None:
        sys.stdout.write(text)
        return
    sys.stdout.flush()
    stream.write(text.encode("utf-8"))
    stream.flush()


def normalize_code(code: str | None) -> str:
    value = (code or "").strip().upper()
    if len(value) != 4 or not value.isascii() or not value.isalnum():
        raise AiConfigError("a session code is 4 characters (letters and digits), e.g. K7Q2")
    return value


def _print_json(payload: dict) -> int:
    print(json.dumps(payload, indent=2))
    return 0


def _write_output(args, payload: dict, text: str) -> int:
    """--output: the prompt goes to a file (bytes: no CRLF translation) and the JSON carries `file`, not `prompt`."""
    target = Path(args.output)
    try:
        target.write_bytes(text.encode("utf-8"))
    except OSError as exc:
        raise AiConfigError(f"cannot write {target} ({type(exc).__name__})") from None
    payload = {**payload, "file": str(target.resolve())}
    payload.pop("prompt", None)
    if args.json:
        return _print_json(payload)
    print(f"wrote the prompt ({payload['bytes']} bytes), conversation {payload['code']}, to {target}")
    return 0


# --------------------------------------------------------------------------- start / prompt

def _run_start(args) -> int:
    payload = engine.start(
        intent=args.intent, ask=read_input(args.ask_file) or "", app_dir=args.app_dir, new_app_name=args.new_app_name,
        target=args.target, language=args.language or DEFAULT_LANGUAGE, interview=bool(args.interview),
        phase=args.phase, errors=read_input(args.errors_file) or "", answer_size=args.answer_size)
    if args.json:
        return _print_json(payload)
    emit_text(payload["prompt"])
    return 0


def _run_prompt(args) -> int:
    if args.intent is None:
        if not args.code:
            raise AiConfigError("say --intent (a new prompt) or --code (a stored one)")
        if args.deliver_as == "attachment":
            payload = engine.write_attachment(args.code, args.round)
            if args.json:
                return _print_json(payload)
            print(payload["file"])
            return 0
        payload = engine.stored_prompt(args.code, args.round)
        if args.output:
            return _write_output(args, payload, payload["prompt"])
        if args.json:
            return _print_json(payload)
        emit_text(payload["prompt"])
        return 0
    return _run_stateless_prompt(args)


def _run_stateless_prompt(args) -> int:
    ask = (read_input(args.ask_file) or "").strip()
    model = read_input(args.model_file) or ""
    errors, _redactions = scrub_mod.scrub_errors(read_input(args.errors_file) or "")  # leaves the computer in the prompt
    if args.ask_file is None:
        raise AiConfigError("say what the app should do (--ask-file)")
    if args.intent == "create" and not ask:
        raise AiConfigError("say what the app should do (--ask-file is empty)")
    if args.intent in ("change", "fix") and not model.strip():
        raise AiConfigError(f"intent {args.intent} needs the app's current model (--model-file)")
    if args.intent == "change" and not ask:
        raise AiConfigError("say what should change (--ask-file is empty)")
    code = normalize_code(args.code) if args.code else ingest_mod.new_session_code()
    bundle, manifest = engine.build_knowledge()
    text = prompts_mod.compose_round1(
        prompts_mod.load_prompts(), code=code, intent=args.intent, ask=ask, language=args.language or DEFAULT_LANGUAGE,
        knowledge=bundle, model=model, errors=errors, interview=bool(args.interview))
    payload = {"ok": True, "code": code, "intent": args.intent, "round": 1, "bytes": len(text.encode("utf-8")),
               "knowledgeSha256": manifest["contentSha256"]}
    if args.output:
        return _write_output(args, payload, text)
    if args.json:
        return _print_json({**payload, "prompt": text})
    emit_text(text)
    return 0


# --------------------------------------------------------------------------- ingest

def _run_ingest(args) -> int:
    code = normalize_code(args.code)
    if args.round is not None and args.round < 1:
        raise AiConfigError("--round starts at 1")
    try:
        session = sessions.load(code)
    except sessions.SessionNotFound:
        session = None
    if args.file:
        if session is None:
            raise AiConfigError("files can only be read into a saved conversation; start one first")
        number = args.round or len(session["rounds"])
        raw, pre = engine.raw_from_files(args.file, session["code"], number, session["phase"])
        result = engine.ingest(code, raw, source=args.source, round_number=args.round, pre_findings=pre) if raw else \
            _nothing_readable(session, number, pre)
    else:
        raw = read_input(args.text_file) or ""
        if session is None:  # the Prompter's paste box: text stage only, nothing saved
            result = engine.stateless_ingest(raw, code, args.round or 1, args.phase)
        else:
            result = engine.ingest(code, raw, source=args.source, round_number=args.round)
    if args.json:
        return _print_json(result)
    lines = [f"outcome: {result['outcome']}"]
    lines += [f"  {f['path']} ({f['status']})" for f in result["files"]]
    lines += [f"  finding: {f['code']}" for f in result["findings"]]
    print("\n".join(lines))
    return 0


def _nothing_readable(session: dict, number: int, findings: list) -> dict:
    """The files brought were all unreadable (a hostile zip ...): a NO_FILES result with the reasons."""
    result = ingest_mod.ingest_text("", session=session["code"], expected_round=number,
                                    policy=ingest_mod.load_policy(), phase=session["phase"])
    result["findings"].extend(findings)
    return result


# --------------------------------------------------------------------------- apply / undo

def _run_apply(args) -> int:
    import npdev_relay_apply

    session = engine.load_session(args.code)
    payload = npdev_relay_apply.apply_round(session, args.round, args.confirm_warning, parent_dir=args.parent_dir)
    return _report_action(args, payload, "applied" if payload.get("ok") else "not applied")


def _run_undo(args) -> int:
    import npdev_relay_apply

    session = engine.load_session(args.code)
    payload = npdev_relay_apply.undo(session, force=args.force)
    return _report_action(args, payload, "restored the previous model" if payload.get("ok") else "not undone")


def _report_action(args, payload: dict, label: str) -> int:
    """A refusal is a RESULT (exit 0, ok=false): the Manager shows its sentence; only a broken call exits 2."""
    if args.json:
        return _print_json(payload)
    print(label + ("" if payload.get("ok") else f": {payload.get('error')}"))
    return 0


# --------------------------------------------------------------------------- status / list / abort

def _run_status(args) -> int:
    payload = engine.status(args.code)
    if args.json:
        return _print_json(payload)
    s = payload["session"]
    print(f"{s['code']}: {s['intent']} - {s['state']} - round {s['round']}")
    return 0


def _run_list(args) -> int:
    payload = engine.list_sessions()
    if args.json:
        return _print_json(payload)
    for s in payload["sessions"]:
        print(f"{s['code']}  {s['intent']:<6} {s['state']:<16} round {s['round']}  {s['ask'][:60]}")
    if not payload["sessions"]:
        print("(no open conversations)")
    return 0


def _run_abort(args) -> int:
    payload = engine.abort(args.code)
    if args.json:
        return _print_json(payload)
    print(f"closed {payload['code']}")
    return 0


def _run_targets(args) -> int:
    payload = engine.targets_list()
    if args.json:
        return _print_json(payload)
    for t in payload["targets"]:
        print(f"{t['id']:<15} {t['label']:<16} {t['maxPasteChars']:>6} chars  {t['url'] or '(no address)'}")
    return 0


def _run_target(args) -> int:
    payload = engine.choose_target(args.code, args.target)
    if args.json:
        return _print_json(payload)
    verdict = "fits a paste" if payload["fits"] else "too long to paste: attach it as a file"
    print(f"{payload['code']} -> {payload['target']['id']}: {payload['chars']} characters, {verdict}")
    return 0


def _run_stats(args) -> int:
    payload = engine.stats()
    if args.json:
        return _print_json(payload)
    if args.csv:
        import csv
        import io
        buffer = io.StringIO()
        writer = csv.DictWriter(buffer, fieldnames=payload["columns"], lineterminator="\n")
        writer.writeheader()
        writer.writerows(payload["rows"])
        emit_text(buffer.getvalue())
        return 0
    if not payload["byTarget"]:
        print("(no conversations yet)")
    for t in payload["byTarget"]:
        average = "-" if t["averageRoundsToPass"] is None else t["averageRoundsToPass"]
        print(f"{str(t['target']):<15} {t['passed']}/{t['conversations']} passed  {average} rounds on average  "
              f"{t['passedInTwoRoundsOrFewer']} in two or fewer  largest prompt answered {t['largestPromptWithAnAnswer']}  "
              f"largest ready answer {t['largestReadyAnswer']}")
    return 0
