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
                   model: str = "", errors: str = "", interview: bool = False) -> str:
    """intent: create | change | fix. model: the current model.json text (change/fix)."""
    values = dict(code=code, round=1, language=language, ask=ask.strip(), model=model.strip(),
                  errors=clip(errors), files_expected=2, knowledge=knowledge.strip())
    body = "\n\n".join([
        _t(p["formatContract"], **values),
        _t(p["language"], **values),
        _t(p["interview"]["on" if interview else "off"], **values),
        _t(p["knowledgeHeading"], **values),
        _t(p["intent"][intent], **values),
    ])
    return _wrap(p, code, 1, body)


def compose_same_chat(p: dict, *, code: str, rnd: int, outcome: str, errors: str = "") -> str | None:
    """The short follow-up for the same chat, or None when the outcome needs the USER, not the AI
    (QUESTIONS, LIMIT_REACHED, STALE_ROUND, WRONG_SESSION, PROMPT_ECHO, READY)."""
    template = p["sameChat"].get(outcome)
    if template is None or outcome.startswith("_"):
        return None
    return _wrap(p, code, rnd, _t(template, code=code, round=rnd, errors=clip(errors), files_expected=2))


def compose_fresh_chat(p: dict, *, code: str, rnd: int, ask: str, language: str, knowledge: str, model: str,
                       errors: str, trail: list[str]) -> str:
    """Self-contained prompt for a NEW chat or ANOTHER AI (LIMIT_REACHED, the AI going in circles)."""
    values = dict(code=code, round=rnd, language=language, ask=ask.strip(), model=model.strip(),
                  errors=clip(errors), files_expected=2, knowledge=slim(knowledge).strip(),
                  trail="\n".join(f"- {t}" for t in trail) or "- (none)")
    body = "\n\n".join([
        _t(p["formatContract"], **values),
        _t(p["language"], **values),
        _t(p["interview"]["off"], **values),
        _t(p["knowledgeHeading"], **values),
        _t(p["freshChat"], **values),
    ])
    return _wrap(p, code, rnd, body)
