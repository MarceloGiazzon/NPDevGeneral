"""Composer for relay prompts: pure functions over relay-prompts.json (next to this file).

The knowledge text is the SAME compact bundle `npdev ai context` prints: get it with
    sys.path.insert(0, <repo>/scripts/ai); import build_core_context
    bundle, manifest = build_core_context.build_bundle(compact=True)
(exactly what npdev_ai._run_context does) and record manifest["contentSha256"] in the session.

Round 1 always carries the knowledge. A same-chat follow-up never does (the chat already has it). A
fresh-chat follow-up carries the SLIM knowledge (the bundle without golden examples), like the
bench's stateless repair (scripts/ai/bench/author.py, slim_knowledge / stateless_repair_messages).
"""
from __future__ import annotations

import json
from pathlib import Path
from string import Template

DEFAULT_PROMPTS_PATH = Path(__file__).resolve().parent / "relay-prompts.json"
GOLDEN_MARKER = "\n## Golden example:"   # same marker as scripts/ai/bench/author.py
ERRORS_CAP = 6000                        # same clip budget as the bench's _clip()


def load_prompts(path: Path | None = None) -> dict:
    return json.loads(Path(path or DEFAULT_PROMPTS_PATH).read_text(encoding="utf-8"))


def attachment_message(p: dict) -> str:
    """The one short line to write in the chat next to an attached prompt file (the file carries everything else)."""
    return p["attachmentMessage"]


def slim(knowledge: str) -> str:
    return knowledge[: knowledge.index(GOLDEN_MARKER)].rstrip() + "\n" if GOLDEN_MARKER in knowledge else knowledge


def clip(text: str) -> str:
    text = text.strip()
    return text if len(text) <= ERRORS_CAP else text[:3000] + "\n...[truncated]...\n" + text[-2800:]


def _t(template: str, **values) -> str:
    return Template(template).substitute(**values)


def _wrap(p: dict, code: str, rnd: int, body: str) -> str:
    return "\n\n".join([_t(p["header"], code=code, round=rnd), body.strip(), _t(p["footer"], code=code, round=rnd)]) + "\n"


def compose_round1(p: dict, *, code: str, intent: str, ask: str, language: str, knowledge: str,
                   model: str = "", errors: str = "", interview: bool = False, budget: int | None = None) -> str:
    """intent: create | change | fix. model: the current model.json text (change/fix).

    budget: characters of model.json to ask for. None (the default) adds NOTHING, so the prompt stays byte-identical
    to the one the bench measured. Only a NEW app can be asked to start small: a change or a fix must return the
    complete file, so a size limit there would contradict the format contract."""
    values = dict(code=code, round=1, language=language, ask=ask.strip(), model=model.strip(),
                  errors=clip(errors), files_expected=2, knowledge=knowledge.strip())
    if intent == "pack":
        # A pack conversation swaps the format contract (pack.json, not model.json) and adds the pack rules after the
        # shared authoring knowledge; the language, interview and knowledge parts are the same ones an app gets.
        parts = [
            _t(p["pack"]["formatContract"], **values),
            _t(p["language"], **values),
            _t(p["interview"]["on" if interview else "off"], **values),
            _t(p["knowledgeHeading"], **values),
            _t(p["intent"]["pack"], **values),
            _t(p["pack"]["rules"], **values),
        ]
        return _wrap(p, code, 1, "\n\n".join(parts))
    parts = [
        _t(p["formatContract"], **values),
        _t(p["language"], **values),
        _t(p["interview"]["on" if interview else "off"], **values),
        _t(p["knowledgeHeading"], **values),
        _t(p["intent"][intent], **values),
    ]
    if budget and intent == "create":
        parts.append(_t(p["sizeLimit"], budget=budget, **values))
    return _wrap(p, code, 1, "\n\n".join(parts))


def compose_same_chat(p: dict, *, code: str, rnd: int, outcome: str, errors: str = "",
                      budget: int | None = None) -> str | None:
    """The short follow-up for the same chat, or None when the outcome needs the USER, not the AI
    (QUESTIONS, LIMIT_REACHED, STALE_ROUND, WRONG_SESSION, PROMPT_ECHO, READY).

    budget: given only for a REPEATED cut-off of a new app (TRUNCATED/ELIDED): asking the same chat for the same
    size again would be cut at the same place, so the follow-up asks for a smaller core instead."""
    if budget and outcome in ("TRUNCATED", "ELIDED"):
        return _wrap(p, code, rnd, _t(p["smaller"], code=code, round=rnd, budget=budget, files_expected=2))
    template = p["sameChat"].get(outcome)
    if template is None or outcome.startswith("_"):
        return None
    return _wrap(p, code, rnd, _t(template, code=code, round=rnd, errors=clip(errors), files_expected=2))


def compose_fresh_chat(p: dict, *, code: str, rnd: int, ask: str, language: str, knowledge: str, model: str,
                       errors: str, trail: list[str], budget: int | None = None) -> str:
    """Self-contained prompt for a NEW chat or ANOTHER AI (LIMIT_REACHED, the AI going in circles).
    budget: only after repeated cut-offs of a new app -- the same size limit as `compose_round1`."""
    values = dict(code=code, round=rnd, language=language, ask=ask.strip(), model=model.strip(),
                  errors=clip(errors), files_expected=2, knowledge=slim(knowledge).strip(),
                  trail="\n".join(f"- {t}" for t in trail) or "- (none)")
    parts = [
        _t(p["formatContract"], **values),
        _t(p["language"], **values),
        _t(p["interview"]["off"], **values),
        _t(p["knowledgeHeading"], **values),
        _t(p["freshChat"], **values),
    ]
    if budget:
        parts.append(_t(p["sizeLimit"], budget=budget, **values))
    return _wrap(p, code, rnd, "\n\n".join(parts))
