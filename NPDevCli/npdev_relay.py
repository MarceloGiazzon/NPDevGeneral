"""`npdev ai relay ...` -- use a chat AI in a browser (no API key, no MCP): compose the text to paste, read the
answer back.

The engine lives here, in the CLI, so the Manager, the bench and a terminal user all send the SAME measured
text and classify an answer the SAME way:

  npdev ai relay prompt --intent create|change|fix --ask-file F [--model-file F] [--errors-file F]
        -> the round-1 prompt (NPDEV-PROMPT <code>-1 ... NPDEV-PROMPT-END <code>-1) to paste into a chat AI
  npdev ai relay ingest --text-file F --code K7Q2 [--round N] [--phase mvp|f4]
        -> what the user brought back, classified: one outcome, the files it carries, the findings

Both are stateless: the session code is the only thing that ties an answer to a prompt. Every outcome is a
RESULT (exit 0); only a broken invocation (unreadable file, bad flags) is an error (exit 2).
Text always travels in files, never on argv (a prompt on a command line shows in process listings).
Stdlib only.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import npdev_relay_ingest as ingest_mod
import npdev_relay_prompts as prompts_mod
from npdev_ai_config import AiConfigError

INTENTS = ("create", "change", "fix")
PHASES = ("mvp", "f4")
DEFAULT_LANGUAGE = "English"


# --------------------------------------------------------------------------- CLI surface

def add_arguments(ai_sub) -> None:
    """Register `ai relay`. Its own sub-parsers use dest="relay_command" -- never dest="command", which would
    overwrite args.command ("ai") and send every later `ai` call down the wrong path."""

    def json_flag(p):
        p.add_argument("--json", action="store_true", help="Answer with JSON (the Manager reads this).")

    relay = ai_sub.add_parser("relay", help="Use a browser chat AI: compose the text to paste, read the answer back.")
    rsub = relay.add_subparsers(dest="relay_command")

    prompt = rsub.add_parser("prompt", help="Compose the prompt to paste into a chat AI (round 1).")
    prompt.add_argument("--intent", required=True, choices=INTENTS)
    prompt.add_argument("--ask-file", required=True, help="What the user wants ('-' = stdin). A file, not argv.")
    prompt.add_argument("--model-file", help="The app's current model.json (change and fix).")
    prompt.add_argument("--errors-file", help="What went wrong (fix): build or boot output, already redacted.")
    prompt.add_argument("--language", default=DEFAULT_LANGUAGE,
                        help='Language the AI should talk to the user in, e.g. "Portuguese (Brazil)".')
    prompt.add_argument("--code", help="Session code (4 characters). Default: a new one.")
    prompt.add_argument("--interview", action="store_true", help="Let the AI ask a few questions before it answers.")
    prompt.add_argument("--output", help="Write the prompt to this file instead of stdout.")
    json_flag(prompt)

    ingest = rsub.add_parser("ingest", help="Read what the user brought back from the chat AI and classify it.")
    ingest.add_argument("--text-file", required=True, help="The copied answer ('-' = stdin).")
    ingest.add_argument("--code", required=True, help="The session code the prompt carried.")
    ingest.add_argument("--round", type=int, default=1, help="The round this answer should belong to.")
    ingest.add_argument("--phase", choices=PHASES, default="mvp", help="Which files an answer may carry.")
    json_flag(ingest)


def run(args) -> int:
    action = getattr(args, "relay_command", None)
    try:
        if action == "prompt":
            return _run_prompt(args)
        if action == "ingest":
            return _run_ingest(args)
        raise AiConfigError("npdev ai relay needs a verb: prompt or ingest (see --help)")
    except AiConfigError as exc:
        if getattr(args, "json", False):
            print(json.dumps({"ok": False, "error": str(exc)}))
        else:
            print(f"error: {exc}", file=sys.stderr)
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


# --------------------------------------------------------------------------- prompt

def _run_prompt(args) -> int:
    ask = (read_input(args.ask_file) or "").strip()
    model = read_input(args.model_file) or ""
    errors = read_input(args.errors_file) or ""
    if args.intent == "create" and not ask:
        raise AiConfigError("say what the app should do (--ask-file is empty)")
    if args.intent in ("change", "fix") and not model.strip():
        raise AiConfigError(f"intent {args.intent} needs the app's current model (--model-file)")
    if args.intent == "change" and not ask:
        raise AiConfigError("say what should change (--ask-file is empty)")
    code = normalize_code(args.code) if args.code else ingest_mod.new_session_code()
    bundle, manifest = build_knowledge()
    text = prompts_mod.compose_round1(
        prompts_mod.load_prompts(), code=code, intent=args.intent, ask=ask, language=args.language or DEFAULT_LANGUAGE,
        knowledge=bundle, model=model, errors=errors, interview=bool(args.interview))
    payload = {"ok": True, "code": code, "intent": args.intent, "round": 1, "bytes": len(text.encode("utf-8")),
               "knowledgeSha256": manifest["contentSha256"]}
    if args.output:
        target = Path(args.output)
        try:
            target.write_bytes(text.encode("utf-8"))  # bytes: no CRLF translation, matches `bytes` above
        except OSError as exc:
            raise AiConfigError(f"cannot write {target} ({type(exc).__name__})") from None
        payload["file"] = str(target.resolve())
        print(json.dumps(payload, indent=2) if args.json else f"wrote the round-1 prompt ({payload['bytes']} bytes), "
                                                                 f"session {code}, to {target}")
        return 0
    if args.json:
        print(json.dumps({**payload, "prompt": text}, indent=2))
    else:
        emit_text(text)
    return 0


# --------------------------------------------------------------------------- ingest

def _run_ingest(args) -> int:
    raw = read_input(args.text_file) or ""
    code = normalize_code(args.code)
    if args.round < 1:
        raise AiConfigError("--round starts at 1")
    result = ingest_mod.ingest_text(raw, session=code, expected_round=args.round,
                                    policy=ingest_mod.load_policy(), phase=args.phase)
    if args.json:
        print(json.dumps(result, indent=2))
        return 0
    lines = [f"outcome: {result['outcome']}"]
    lines += [f"  {f['path']} ({f['status']})" for f in result["files"]]
    lines += [f"  finding: {f['code']}" for f in result["findings"]]
    print("\n".join(lines))
    return 0


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()
