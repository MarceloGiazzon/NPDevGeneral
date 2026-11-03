"""The 'author' stage of the AI authoring benchmark: build the chat messages, extract a model.json.

Knowledge delivery -- the simpler, robust option (BENCH-2 decision): INLINE the core-context bundle
that the MCP server's `npdev_core_context` tool serves (scripts/ai/build_core_context.py: the
authoring contract + authoring schemas + three verified golden models, ~55 KB of text). A plain
OpenAI-compatible chat endpoint (local Ollama, free tiers) has no MCP client, so asking the model to
call MCP tools would exclude most free backends and add a tool-calling failure mode that has nothing
to do with NPDev. Validation feedback is relayed the way docs/AUTHORING_WITH_AI.md describes the
"without MCP" path: the runner runs `npdev validate model` itself and pastes the typed report back.

Ablation `no-knowledge`: the bundle is omitted; the model gets only the output-format contract.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]

FORMAT_CONTRACT = (
    "You are authoring the JSON model of an application for NPDev, a model-driven app platform "
    "(a JSON model is validated, then a Spring Boot app is generated from it). Reply with the COMPLETE "
    "model as ONE JSON document inside a single ```json fenced block, and nothing inside the block "
    "except that JSON. Keep any explanation outside the block and short. When you are given validator, "
    "build, boot or acceptance errors, reply with the COMPLETE corrected model again (never a diff)."
)


def load_core_context(build_root: Path) -> str:
    """The core-context bundle text, (re)built by scripts/ai/build_core_context.py when absent.
    Reads bundle.json -- never bundle.md (no script reads a .md file)."""
    bundle = build_root / "npdev-ai" / "core-context" / "bundle.json"
    if not bundle.exists():
        subprocess.run([sys.executable, str(REPO_ROOT / "scripts" / "ai" / "build_core_context.py")],
                       cwd=str(REPO_ROOT), capture_output=True, timeout=300, check=True)
    return json.loads(bundle.read_text(encoding="utf-8"))["markdown"]


def load_knowledge_file(path: Path) -> str:
    """--knowledge-file: a bundle.json-shaped file (its "markdown" key) or plain text, used verbatim."""
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".json":
        return json.loads(text)["markdown"]
    return text


def describe_knowledge(knowledge: str | None, source: str | None) -> dict:
    """What the run taught, for summary.json: an A/B between knowledge variants must say which is which."""
    import hashlib
    body = (knowledge or "").encode("utf-8")
    return {"source": source or ("core-context bundle" if knowledge else "none"), "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest()[:16] if knowledge else None}


def system_prompt(knowledge: str | None) -> str:
    if not knowledge:
        return FORMAT_CONTRACT
    return FORMAT_CONTRACT + "\n\n# NPDev authoring knowledge (contract, schemas, golden examples)\n\n" + knowledge


def initial_messages(prompt_text: str, knowledge: str | None) -> list[dict]:
    return [{"role": "system", "content": system_prompt(knowledge)},
            {"role": "user", "content": "Build this application:\n\n" + prompt_text.strip()}]


def _clip(error_text: str) -> str:
    error_text = error_text.strip()
    if len(error_text) > 6000:
        error_text = error_text[:3000] + "\n...[truncated]...\n" + error_text[-2800:]
    return error_text


def repair_messages(history: list[dict], reply_text: str, stage: str, error_text: str) -> list[dict]:
    """Append the model's last reply and the failing stage's error text as the next user turn."""
    note = (f"The model you wrote FAILED at the '{stage}' stage. Error text follows. Fix the cause and reply "
            f"with the COMPLETE corrected model.json in one ```json block.\n\n{_clip(error_text)}")
    return history + [{"role": "assistant", "content": reply_text}, {"role": "user", "content": note}]


def change_messages(prompt_text: str, knowledge: str | None, model_text: str, request: str) -> list[dict]:
    """BENCH-4 (evolution): an app that already works is changed. The model sees the ORIGINAL request, its own
    working model.json as its previous answer, and the owner's new request. The system prompt carries the same
    knowledge as the first call: every token counts, and a fair comparison cannot skip the cost of teaching the DSL."""
    note = ("The application now works. The owner wants one more change:\n\n" + request.strip()
            + "\n\nReply with the COMPLETE updated model.json in one ```json block. Keep everything that already works.")
    return [{"role": "system", "content": system_prompt(knowledge)},
            {"role": "user", "content": "Build this application:\n\n" + prompt_text.strip()},
            {"role": "assistant", "content": "```json\n" + model_text.strip() + "\n```"},
            {"role": "user", "content": note}]


GOLDEN_MARKER = "\n## Golden example:"


def slim_knowledge(knowledge: str | None) -> str | None:
    """The bundle without its golden examples: a repair turn already has the author's own model to edit."""
    if not knowledge or GOLDEN_MARKER not in knowledge:
        return knowledge
    return knowledge[: knowledge.index(GOLDEN_MARKER)].rstrip() + "\n"


def stateless_repair_messages(prompt_text: str, knowledge: str | None, model_text: str, stage: str,
                              error_text: str, trail: list[str]) -> list[dict]:
    """F6 repair turn: two messages whatever the loop number -- slim knowledge, the request, one line per
    earlier failed attempt, the CURRENT model and its errors. Measured on the haiku55 runs, re-sent history
    and the full bundle were 67 % of all tokens."""
    parts = ["Build this application:\n\n" + prompt_text.strip()]
    if trail:
        parts.append("Earlier attempts (do not reintroduce these errors):\n" + "\n".join(trail))
    parts.append(f"Your current model.json FAILED at the '{stage}' stage:\n```json\n{model_text.strip()}\n```")
    parts.append("Errors:\n" + _clip(error_text))
    parts.append("Reply with the COMPLETE corrected model.json in one ```json block.")
    return [{"role": "system", "content": system_prompt(slim_knowledge(knowledge))},
            {"role": "user", "content": "\n\n".join(parts)}]


_FENCE = re.compile(r"```(?:json|JSON)?\s*\n(.*?)```", re.DOTALL)


def _balanced_object(text: str) -> str | None:
    start = text.find("{")
    while start != -1:
        depth, in_str, esc = 0, False, False
        for i in range(start, len(text)):
            ch = text[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[start:i + 1]
        start = text.find("{", start + 1)
    return None


def extract_model(reply: str) -> tuple[str | None, str]:
    """(model_json_text, error). Prefers the largest fenced block that parses as a JSON object, else
    the first balanced {...} in the reply. The text is returned pretty-printed and re-validated."""
    candidates = [m.group(1).strip() for m in _FENCE.finditer(reply)]
    candidates.sort(key=len, reverse=True)
    balanced = _balanced_object(reply)
    if balanced:
        candidates.append(balanced)
    last_error = "no JSON object found in the reply"
    for cand in candidates:
        try:
            obj = json.loads(cand)
        except ValueError as exc:
            last_error = f"the JSON in the reply does not parse: {exc}"
            continue
        if isinstance(obj, dict):
            return json.dumps(obj, indent=2), ""
        last_error = "the JSON in the reply is not an object"
    return None, last_error
