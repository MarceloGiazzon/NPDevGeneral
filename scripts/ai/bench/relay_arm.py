"""The `relay` arm of the AI authoring benchmark (T1.7 of "Usar IA do navegador"). Stdlib only.

WHAT IT MEASURES
----------------
`npdev ai relay` lets a person carry a prompt to ANY chat AI in a browser and carry the answer back. The
bench's other arms call a model through an API with a system prompt; a browser chat has no system role and no
tool loop, only a conversation the person pastes into. This arm plays the person, exactly as the product will:

  * ONE user message, composed by NPDevCli/npdev_relay_prompts.compose_round1 (the same text the CLI and the
    Manager hand to the person): format contract + the COMPACT authoring bundle + the request. No system role.
  * the reply (optionally degraded by the "copy fidelity" mutator, below) goes through
    NPDevCli/npdev_relay_ingest.ingest_text, the same ingest the product uses;
  * outcome PARSED -> files[model.json].content is the candidate and goes through the bench's ordinary
    validate -> generate -> build -> boot -> acceptance stages (run_bench.run_attempt, passed in);
  * any other outcome, or a failing stage -> compose_same_chat(...) is the repair turn, sent in the SAME
    conversation (the earlier turns are the conversation history). Rounds are bounded by --max-loops like the
    other arms.

Outcomes that need the PERSON, not the AI (QUESTIONS, WRONG_SESSION, STALE_ROUND, PROMPT_ECHO: compose_same_chat
returns None) end the prompt as an author-stage failure with the outcome recorded: the bench has no person to
answer, and with `interview: off` a question is the AI not following the protocol. LIMIT_REACHED (the chat
service's quota message) is an infrastructure outcome, excluded from pass rates like a quota error.

COPY FIDELITY (--relay-copy-fidelity)
-------------------------------------
What reaches ingest in real life is not always what the AI wrote: a person copies from the rendered page, or a
chat UI re-types quotes. The mutator degrades each REPLY (never the conversation history, which holds what the
AI really said) before ingest sees it:
  fences        drop every ``` / ~~~ fence line
  markers       drop the NPDEV-BEGIN / NPDEV-FILE / NPDEV-END lines
  smart-quotes  straight double quotes become typographic ones (open after a space or bracket, close otherwise)
  copy-button   a "Copy" line after every code block and one at the very end (the chat UI's button text)
`all` is the four together; they are applied in the order above whatever order they are given.

Everything the arm decides is recorded in result.json ("relay": session, per-round ingest outcome and finding
codes) and summary.json ("relay": copy fidelity, prompt-file and ingest-policy hashes, outcome histogram,
first-round parse rate). The prompt text is measured: changing NPDevCli/relay-prompts.json or the bundle changes
what this arm measured, so the hashes are in the summary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import backends  # noqa: E402
import stages  # noqa: E402

REPO_ROOT = stages.REPO_ROOT
sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
import npdev_relay_ingest as ingest  # noqa: E402
import npdev_relay_prompts as relay_prompts  # noqa: E402

ARM_ID = "relay"
REPAIR_MODE = "same-chat"
LANGUAGE = "English"          # the bench prompts are English; the product passes the person's language
KNOWLEDGE_SOURCE = "core-context compact bundle (build_core_context.build_bundle(compact=True))"
COPY_MUTATIONS = ("copy-button", "markers", "smart-quotes", "fences")   # also the order they are applied in


# ---------------------------------------------------------------------------------------------
# inputs: the knowledge bundle and the session code
# ---------------------------------------------------------------------------------------------

def pack_ask(prompt: dict) -> str:
    """The request of a pack conversation exactly as the engine composes it: a `Pack id:` line, then the person's words."""
    return f"Pack id: {prompt['packId']}\n{prompt['prompt']}"


def pack_section() -> str:
    """The contract's "Writing a pack" section (what a pack conversation appends after the compact bundle)."""
    scripts_ai = str(REPO_ROOT / "scripts" / "ai")
    if scripts_ai not in sys.path:
        sys.path.insert(0, scripts_ai)
    import build_core_context
    return build_core_context.pack_section()


def compact_knowledge() -> str:
    """The compact bundle, built exactly like `npdev ai context` (npdev_ai._run_context) builds it."""
    scripts_ai = str(REPO_ROOT / "scripts" / "ai")
    if scripts_ai not in sys.path:
        sys.path.insert(0, scripts_ai)
    import build_core_context
    bundle, _manifest = build_core_context.build_bundle(compact=True)
    return bundle


def session_code(run_id: str, prompt_id: str) -> str:
    """A 4-character session code. Derived from the run and the prompt, not random: a rerun of the same
    prompt sends the same text, so an A/B between two runs differs only in what the AI answered."""
    digest = hashlib.sha256(f"{run_id}/{prompt_id}".encode("utf-8")).digest()
    alphabet = ingest.SESSION_ALPHABET
    return "".join(alphabet[b % len(alphabet)] for b in digest[:4])


# ---------------------------------------------------------------------------------------------
# copy fidelity
# ---------------------------------------------------------------------------------------------

def parse_copy_fidelity(spec: str | None) -> tuple[str, ...]:
    """'all' | 'none' | 'fences,markers' -> the mutations in application order. argparse `type=`."""
    spec = (spec or "").strip().lower()
    if spec in ("", "none"):
        return ()
    names = [part.strip() for part in spec.split(",") if part.strip()]
    if names == ["all"]:
        return COPY_MUTATIONS
    unknown = [n for n in names if n not in COPY_MUTATIONS]
    if unknown:
        raise argparse.ArgumentTypeError(
            f"unknown copy-fidelity mutation {unknown[0]!r}: use none, all or a comma list of {', '.join(COPY_MUTATIONS)}")
    return tuple(m for m in COPY_MUTATIONS if m in names)


def strip_fences(text: str) -> str:
    return "\n".join(ln for ln in text.split("\n") if not ingest.FENCE_RE.match(ln))


def strip_markers(text: str) -> str:
    return "\n".join(ln for ln in text.split("\n")
                     if not (ingest.BEGIN_RE.match(ln) or ingest.END_RE.match(ln) or ingest.FILE_RE.match(ln)))


_OPENING_AFTER = frozenset(" \t\n([{,:=")


def smart_quotes(text: str) -> str:
    out = []
    for i, ch in enumerate(text):
        if ch == '"':
            out.append("“" if (text[i - 1] if i else "\n") in _OPENING_AFTER else "”")
        else:
            out.append(ch)
    return "".join(out)


def copy_button(text: str) -> str:
    out, inside = [], False
    for ln in text.split("\n"):
        out.append(ln)
        if ingest.FENCE_RE.match(ln):
            inside = not inside
            if not inside:
                out.append("Copy")
    return "\n".join(out).rstrip("\n") + "\nCopy\n"


_MUTATORS = {"copy-button": copy_button, "markers": strip_markers, "smart-quotes": smart_quotes, "fences": strip_fences}


def mutate_copy(reply: str, mutations) -> str:
    """The reply as it reaches ingest after a lossy copy. No mutations -> the reply, untouched."""
    chosen = set(mutations or ())
    for name in COPY_MUTATIONS:
        if name in chosen:
            reply = _MUTATORS[name](reply)
    return reply


# ---------------------------------------------------------------------------------------------
# ingest result -> what the next prompt needs
# ---------------------------------------------------------------------------------------------

def candidate_model(result: dict, name: str = "model.json") -> str | None:
    """files[model.json].content of a PARSED ingest result (the text the bench validates); `name` is pack.json for a
    pack case."""
    for f in result.get("files") or []:
        if f.get("path") == name and f.get("status") == "allowed" and f.get("content"):
            return f["content"]
    return None


def pack_attempt(n: int, pack_text: str, prompt: dict, prompt_dir: Path, args, stage_log: list) -> dict:
    """The pack case's twin of run_bench.run_attempt: the checks `npdev ai relay` makes on a pack answer (shape, id,
    no secret / binding / NPDev-owned field), then the model validator on a probe application that imports it by local
    `$ref`. Same result shape: {"stages", "failingStage", "feedback", "modelText"}."""
    import npdev_relay_pack as relay_pack
    out = {"stages": stage_log, "failingStage": None, "feedback": "", "modelText": pack_text}
    pack_id = prompt["packId"]
    try:
        pack = json.loads(pack_text)
    except ValueError:
        pack = None
    errors = relay_pack.static_errors(pack, pack_id, pack_text)
    if errors:
        stage_log.append(stages.StageResult("check", False, 0.0, "\n".join(errors)).to_json())
        out.update(failingStage="check", feedback="\n".join(errors))
        return out
    stage_log.append(stages.StageResult("check", True, 0.0, "").to_json())
    attempt_dir = prompt_dir / f"attempt-{n}"
    model_path = relay_pack.stage_probe(attempt_dir / "probe", pack_id, pack_text, relay_pack.probe_model(pack_id))
    res = stages.stage_validate(model_path, attempt_dir)
    stage_log.append(res.to_json())
    if not res.ok:
        out.update(failingStage="validate", feedback=res.detail)
    return out


def findings_text(result: dict) -> str:
    """The error-level ingest findings as plain lines: the $errors of the BAD_JSON / PARTIAL follow-ups."""
    lines = []
    for f in result.get("findings") or []:
        if f.get("severity") != "error":
            continue
        detail = ", ".join(f"{k}={v}" for k, v in (f.get("detail") or {}).items())
        lines.append(f"- {f['code']}" + (f" ({detail[:300]})" if detail else ""))
    return "\n".join(lines)


def needs_fix_errors(stage: str, feedback: str) -> str:
    """The $errors of the NEEDS_FIX follow-up: which stage failed, then that stage's own error text."""
    return f"The '{stage}' stage failed:\n{(feedback or '').strip()}"


def _first_error_line(feedback: str) -> str:
    for line in (feedback or "").splitlines():
        line = line.strip()
        if line and not line.startswith(("NPDev rewrote", "- $.", "- ...")):
            return line[:160]
    return ""


# ---------------------------------------------------------------------------------------------
# one prompt
# ---------------------------------------------------------------------------------------------

def run_prompt_relay(prompt: dict, backend, knowledge: str | None, run_dir: Path, args, attempt_runner) -> dict:
    """The relay arm's twin of run_bench.run_prompt: the same result shape, so scorecard, report, matrix and
    compare read every arm identically. `attempt_runner` is run_bench.run_attempt (passed in: run_bench imports
    this module)."""
    prompt_dir = run_dir / prompt["id"]
    prompt_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    prompts = relay_prompts.load_prompts()
    policy = ingest.load_policy()
    is_pack = prompt.get("intent") == "pack"       # P6: a case that asks for a PACK, not an application
    file_name = "pack.json" if is_pack else "model.json"
    attempt_runner = pack_attempt if is_pack else attempt_runner
    if is_pack:
        knowledge = (knowledge or "").rstrip() + "\n\n" + pack_section()      # only a pack conversation pays for it
    asked = set(getattr(args, "relay_copy_fidelity", None) or ())
    mutations = tuple(m for m in COPY_MUTATIONS if m in asked)      # recorded in the order they are applied
    code = session_code(run_dir.name, prompt["id"])
    messages = [{"role": "user", "content": relay_prompts.compose_round1(
        prompts, code=code, intent="pack" if is_pack else "create",
        ask=pack_ask(prompt) if is_pack else prompt["prompt"], language=LANGUAGE,
        knowledge=knowledge or "")}]
    attempts: list[dict] = []
    rounds: list[dict] = []
    tokens_in = tokens_out = 0
    author_seconds = 0.0
    passed = False
    stuck_limit = getattr(args, "stop_when_stuck", 0)
    stuck_limit = stuck_limit if isinstance(stuck_limit, int) else 0
    signatures: list[tuple[str, str]] = []
    stopped_stuck = False
    needs_person: str | None = None
    for n in range(0, args.max_loops + 1):
        rnd = n + 1
        stage_log: list[dict] = []
        try:
            completion = backend.complete(messages)
        except backends.BackendError as exc:
            stage_log.append(stages.StageResult("author", False, 0.0, str(exc)).to_json())
            attempts.append({"attempt": n, "stages": stage_log, "failingStage": "author", "feedback": str(exc),
                             "backendError": True, "infraKind": backends.classify_backend_error(str(exc))})
            break   # the model is unreachable: repairing cannot help
        expected = backends._estimate_tokens(messages)
        if backend.info.kind != "fake" and completion.prompt_tokens and completion.prompt_tokens < 0.6 * expected:
            detail = (f"context truncated: the backend reports {completion.prompt_tokens} prompt tokens for a "
                      f"prompt of ~{expected}+ (raise the server's context window, e.g. OLLAMA_CONTEXT_LENGTH)")
            stage_log.append(stages.StageResult("author", False, completion.seconds, detail).to_json())
            attempts.append({"attempt": n, "stages": stage_log, "failingStage": "author", "feedback": detail,
                             "backendError": True, "infraKind": "context-truncated"})
            tokens_in += completion.prompt_tokens
            tokens_out += completion.completion_tokens
            break
        tokens_in += completion.prompt_tokens
        tokens_out += completion.completion_tokens
        author_seconds += completion.seconds
        adir = prompt_dir / f"attempt-{n}"
        adir.mkdir(parents=True, exist_ok=True)
        reply_path = adir / "author-reply.txt"
        reply_path.write_text(completion.text, encoding="utf-8")
        seen = mutate_copy(completion.text, mutations)
        if seen != completion.text:
            (adir / "author-reply-copied.txt").write_text(seen, encoding="utf-8")   # what ingest actually saw
        res = ingest.ingest_text(seen, session=code, expected_round=rnd, policy=policy, phase="pack" if is_pack else "mvp")
        (adir / "ingest-result.json").write_text(json.dumps(res, indent=2), encoding="utf-8")
        outcome = res["outcome"]
        model_text = candidate_model(res, file_name) if outcome == ingest.PARSED else None
        if outcome == ingest.PARSED and model_text is None:
            outcome = ingest.PARTIAL      # parsed, but no allowed model.json to validate
        errors = findings_text(res)
        detail = "" if model_text is not None else (f"ingest outcome {outcome}" + (f":\n{errors}" if errors else ""))
        stage_log.append(stages.StageResult(
            "author", model_text is not None, completion.seconds, detail, str(reply_path),
            {"promptTokens": completion.prompt_tokens, "completionTokens": completion.completion_tokens,
             "ingestOutcome": outcome, "round": rnd}).to_json())
        rounds.append({"round": rnd, "ingestOutcome": outcome,
                       "findings": sorted({f["code"] for f in res.get("findings") or []})})
        if model_text is None:
            attempt = {"attempt": n, "stages": stage_log, "failingStage": "author", "feedback": detail}
            follow_outcome = outcome
            if outcome == ingest.LIMIT_REACHED:      # the chat service's quota message: not an authoring failure
                attempt.update(backendError=True, infraKind="quota")
        else:
            attempt = attempt_runner(n, model_text, prompt, prompt_dir, args, stage_log)
            attempt["attempt"] = n
            follow_outcome = "NEEDS_FIX"
            errors = needs_fix_errors(attempt["failingStage"] or "", attempt.get("feedback") or "")
        attempts.append(attempt)
        if attempt["failingStage"] is None:
            passed = True
            break
        if attempt.get("infraKind"):
            break
        signatures.append((attempt["failingStage"], _first_error_line(attempt["feedback"])))
        if stuck_limit >= 2 and len(signatures) >= stuck_limit and len(set(signatures[-stuck_limit:])) == 1:
            stopped_stuck = True
            break
        if n == args.max_loops:
            break
        follow_up = relay_prompts.compose_same_chat(prompts, code=code, rnd=rnd + 1, outcome=follow_outcome,
                                                    errors=errors, intent="pack" if is_pack else None)
        if follow_up is None:
            needs_person = outcome      # this outcome needs the person; the bench has none
            break
        messages = messages + [{"role": "assistant", "content": completion.text},
                               {"role": "user", "content": follow_up}]
    wall = time.monotonic() - started
    infra_kind = None if passed else attempts[-1].get("infraKind")
    first = attempts[0]
    first_valid = first["failingStage"] not in ("author", "validate")
    passed_model = next((a.get("modelText") for a in reversed(attempts) if a["failingStage"] is None), None)
    result = {
        "promptId": prompt["id"], "tier": prompt.get("tier"), "title": prompt.get("title"), "arm": ARM_ID,
        "passed": passed,
        "outcome": "pass" if passed else ("infra" if infra_kind else "fail"),
        "infraKind": infra_kind,
        "loopsUsed": len(attempts) - 1,
        "stoppedStuck": stopped_stuck,
        "firstAttemptValid": bool(first_valid and not first.get("backendError")),
        "firstAttemptPassed": first["failingStage"] is None,
        "firstAttemptFailingStage": first["failingStage"],
        "failingStage": None if passed else attempts[-1]["failingStage"],
        "tokens": {"prompt": tokens_in, "completion": tokens_out, "total": tokens_in + tokens_out},
        "costUsd": backend.info.cost(tokens_in, tokens_out),
        "wallSeconds": round(wall, 2), "authorSeconds": round(author_seconds, 2),
        "ablation": "none", "repairMode": REPAIR_MODE,
        "backend": backend.info.describe(),
        "relay": {"session": code, "copyFidelity": list(mutations), "rounds": rounds,
                  "stoppedNeedsPerson": needs_person},
        "attempts": [{"attempt": a["attempt"], "failingStage": a["failingStage"],
                      "feedbackExcerpt": (a.get("feedback") or "")[:600], "stages": a["stages"]} for a in attempts],
    }
    if passed and passed_model:
        (prompt_dir / "final-model.json").write_text(passed_model, encoding="utf-8")
    (prompt_dir / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    result["_modelText"] = passed_model
    return result


# ---------------------------------------------------------------------------------------------
# summary block
# ---------------------------------------------------------------------------------------------

def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def summarize(args, results: list[dict]) -> dict:
    """summary.json["relay"]: what was measured (hashes of the prompt file and the ingest policy, the copy
    fidelity) and how ingest fared (outcome histogram over every round, first-round parse rate, mean rounds)."""
    histogram: dict[str, int] = {}
    with_rounds = [r for r in results if (r.get("relay") or {}).get("rounds")]
    for r in with_rounds:
        for rd in r["relay"]["rounds"]:
            histogram[rd["ingestOutcome"]] = histogram.get(rd["ingestOutcome"], 0) + 1
    first_parsed = sum(1 for r in with_rounds if r["relay"]["rounds"][0]["ingestOutcome"] == ingest.PARSED)
    return {
        "copyFidelity": list(getattr(args, "relay_copy_fidelity", None) or ()),
        "language": LANGUAGE, "repairMode": REPAIR_MODE,
        "promptsSha256": _sha(relay_prompts.DEFAULT_PROMPTS_PATH),
        "ingestPolicySha256": _sha(ingest.DEFAULT_POLICY_PATH),
        "ingestOutcomeHistogram": dict(sorted(histogram.items())),
        "firstRoundParsedRate": round(first_parsed / len(with_rounds), 4) if with_rounds else None,
        "meanRoundsPerPrompt": (round(sum(len(r["relay"]["rounds"]) for r in with_rounds) / len(with_rounds), 3)
                                if with_rounds else None),
    }


# ---------------------------------------------------------------------------------------------
# the --dry-run backend
# ---------------------------------------------------------------------------------------------

_HEADER = re.compile(r"^NPDEV-PROMPT[ \t]+([A-Z0-9]{4})-(\d+)", re.M)


def good_reply(code: str, rnd: int, model_text: str) -> str:
    """A protocol-perfect answer (NPDEV-BEGIN/FILE/END, two fenced JSON files) for the given model."""
    manifest = json.dumps({"request": "dry run", "renames": [], "deliberateRemovals": [], "securityChanges": [],
                           "couldNotExpress": [], "assumptions": []})
    return (f"NPDEV-BEGIN {code}-{rnd}\nHere is the application you asked for.\n"
            f"NPDEV-FILE model.json\n```json\n{model_text.strip()}\n```\n"
            f"NPDEV-FILE npdev-submission.json\n```json\n{manifest}\n```\n"
            f"NPDEV-END {code}-{rnd} files=2\n")


class FakeRelayBackend(backends.FakeBackend):
    """Deterministic --dry-run / test backend that answers like a well-behaved chat AI: it reads the session code
    and round from the newest user message and replies with a protocol-perfect answer carrying the frozen canary
    model. `replies` scripts a sequence instead; @CODE@ and @ROUND@ in a scripted reply are filled in."""

    def complete(self, messages: list[dict]) -> backends.Completion:
        self.calls.append(messages)
        last_user = next((m["content"] for m in reversed(messages) if m.get("role") == "user"), "")
        found = _HEADER.search(str(last_user))
        code, rnd = (found.group(1), int(found.group(2))) if found else ("XXXX", 1)
        if self._replies:
            text = self._replies.pop(0).replace("@CODE@", code).replace("@ROUND@", str(rnd))
        else:
            text = good_reply(code, rnd, self._good.read_text(encoding="utf-8"))
        return backends.Completion(text, self.FAKE_PROMPT_TOKENS, self.FAKE_COMPLETION_TOKENS, 0.0)
