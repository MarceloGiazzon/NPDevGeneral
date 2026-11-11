"""The text half of `npdev ai relay ingest` (protocol npdev-relay-answer.v1).

What it does: takes whatever the user brought back from a browser chat AI (a copied answer, a pasted
code block, a downloaded file's text) and turns it into a classified result -- which files the answer
carries, which are safe to use, and, when the answer is not usable, WHY, as one outcome code the
Manager maps to one plain sentence and one next prompt.

What it does NOT do (npdev_relay.py does these AFTER this): run the DSL validator, run the
desire-path normaliser, run the authoring diff gate, write anything to disk. Outcome PARSED means
"the text is fine, go validate"; READY / NEEDS_FIX are decided by those later stages.

Stdlib only, no I/O except load_policy(). Every pattern lives in relay-ingest-policy.json (next to this
file), so a new "desire path" of some chat AI is a policy line plus a case in
tests/fixtures/relay/build_cases.py, never a literal in this file. Proven by tests/test_relay_ingest.py.
Keep the code free of drive-letter-looking text (the portable-tooling scan fails on it).
"""
from __future__ import annotations

import fnmatch
import hashlib
import json
import re
import secrets
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA_VERSION = "npdev-ai-relay-ingest-result.v1"
PROTOCOL = "npdev-relay-answer.v1"
DEFAULT_POLICY_PATH = Path(__file__).resolve().parent / "relay-ingest-policy.json"

# No 0/O, 1/I/L, U: a session code must survive being read, retyped or echoed by any AI.
SESSION_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

# Outcome codes, in precedence order (the first that applies wins). Keep in sync with
# schemas/ai/ai-relay-ingest-result.schema.json and the Manager's relay sentences.
PROMPT_ECHO = "PROMPT_ECHO"        # the user pasted OUR prompt back (clipboard still held it)
WRONG_SESSION = "WRONG_SESSION"    # an answer to a different session
STALE_ROUND = "STALE_ROUND"        # an answer to an earlier round of this session
LIMIT_REACHED = "LIMIT_REACHED"    # the chat service's quota/rate-limit message, no files
FILE_LINK_ONLY = "FILE_LINK_ONLY"  # the AI made a downloadable file / artifact / canvas, content not here
QUESTIONS = "QUESTIONS"            # the AI asked questions instead of answering
NO_FILES = "NO_FILES"              # prose only (or a refusal)
TRUNCATED = "TRUNCATED"            # the answer was cut off
ELIDED = "ELIDED"                  # "// rest unchanged" -- the AI left parts out on purpose
BAD_JSON = "BAD_JSON"              # a complete JSON block that does not parse even after safe repairs
PARTIAL = "PARTIAL"                # some files usable, some rejected (or the model is missing)
PARSED = "PARSED"                  # text is fine -> validate next

MANIFEST_KEYS = {
    "recordKind", "previousModelSha256", "previousModelVersion", "submittedModelVersion", "request",
    "renames", "deliberateRemovals", "securityChanges", "couldNotExpress", "unchangedButSuspect",
    "mergeOutcome", "assumptions",
}
JSON_LANGS = {"json", "jsonc", "json5"}
FILENAME_RE = re.compile(r"([A-Za-z0-9_./-]+\.(?:json|html|css|svg|js|mjs|java|txt))\b")
_DECOR = r"[ \t>*_`#~-]*"
BEGIN_RE = re.compile(rf"^{_DECOR}NPDEV-BEGIN[ \t]+([A-Z0-9]{{4}})-(\d+)", re.M | re.I)
END_RE = re.compile(rf"^{_DECOR}NPDEV-END[ \t]+([A-Z0-9]{{4}})-(\d+)(?:[ \t]+files[ \t]*=[ \t]*(\d+))?", re.M | re.I)
FILE_RE = re.compile(rf"^{_DECOR}NPDEV-FILE[ \t]*:?[ \t]*([^\s`*]+)", re.M | re.I)
FENCE_RE = re.compile(r"^[ \t]*(`{3,}|~{3,})[ \t]*([^\n`]*)$")
INFO_LABEL_RE = re.compile(r"(?:file|arquivo|path|archivo)[ \t]*[=:][ \t]*\"?([^\s\"]+)", re.I)
ZERO_WIDTH = dict.fromkeys(map(ord, "​‌‍⁠﻿"), None)


def new_session_code() -> str:
    return "".join(secrets.choice(SESSION_ALPHABET) for _ in range(4))


def load_policy(path: Path | None = None) -> dict:
    policy = json.loads(Path(path or DEFAULT_POLICY_PATH).read_text(encoding="utf-8"))
    flags = re.M | re.I
    policy["_elision"] = [(p["id"], re.compile(p["regex"], flags)) for p in policy["elisionPatterns"]]
    policy["_limit"] = [re.compile(p, flags) for p in policy["limitPatterns"]]
    policy["_link"] = [(p["id"], p["strength"], re.compile(p["regex"], flags)) for p in policy["fileLinkPatterns"]]
    policy["_html_script"] = [re.compile(p, flags) for p in policy["htmlScriptPatterns"]]
    policy["_echo_start"] = re.compile(policy["promptEcho"]["startRegex"], flags)
    policy["_echo_end"] = re.compile(policy["promptEcho"]["endRegex"], flags)
    return policy


# ----------------------------------------------------------------------------------------------
# Step 1: normalise the text
# ----------------------------------------------------------------------------------------------

def normalize_text(text: str) -> tuple[str, list[str]]:
    notes = []
    if text.startswith("﻿"):
        notes.append("bom")
        text = text[1:]
    if "\r" in text:
        notes.append("crlf")
    out = text.replace("\r\n", "\n").replace("\r", "\n")
    for nbsp in (" ", " "):
        if nbsp in out:
            out = out.replace(nbsp, " ")
            if "nbsp" not in notes:
                notes.append("nbsp")
    stripped = out.translate(ZERO_WIDTH)
    if stripped != out and "zero-width" not in notes:
        notes.append("zero-width")
    return stripped, notes


# ----------------------------------------------------------------------------------------------
# Step 3: blocks
# ----------------------------------------------------------------------------------------------

@dataclass
class Block:
    info: str
    body: str
    closed: bool
    start_line: int
    end_line: int
    fenced: bool = True
    label: str | None = None
    label_source: str | None = None


def split_fenced(lines: list[str]) -> list[Block]:
    blocks, i = [], 0
    while i < len(lines):
        m = FENCE_RE.match(lines[i])
        if not m:
            i += 1
            continue
        fence, info, start = m.group(1), m.group(2).strip(), i
        body, closed, i = [], False, i + 1
        while i < len(lines):
            s = lines[i].strip()
            if s and set(s) == {fence[0]} and len(s) >= len(fence):
                closed = True
                break
            body.append(lines[i])
            i += 1
        blocks.append(Block(info, "\n".join(body), closed, start, i))
        i += 1
    # An empty block is noise, unless it never closed: a fence opened at the very end is a cut answer.
    return [b for b in blocks if b.body.strip() or not b.closed]


def balanced_end(s: str, start: int) -> tuple[int | None, int]:
    """Scan from s[start] == '{' (or '['); return (index just past the matching close, 0) or
    (None, depth_left) when the text ends first. String literals and escapes are respected."""
    depth, in_str, esc = 0, False, False
    for i in range(start, len(s)):
        c = s[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c in "{[":
            depth += 1
        elif c in "}]":
            depth -= 1
            if depth == 0:
                return i + 1, 0
    return None, depth


def split_unfenced(text: str) -> list[Block]:
    """Fallback when the fences were lost (the user selected the rendered answer by hand): every
    top-level JSON object that starts a line becomes a block; one that never closes is truncated."""
    blocks, i = [], 0
    starts = [m.start(1) for m in re.finditer(r"(?m)^[ \t]*(\{)", text)]
    for pos in starts:
        if pos < i:
            continue
        end, _ = balanced_end(text, pos)
        line = text.count("\n", 0, pos)
        if end is None:
            blocks.append(Block("", text[pos:], False, line, text.count("\n"), fenced=False))
            break
        blocks.append(Block("", text[pos:end], True, line, text.count("\n", 0, end), fenced=False))
        i = end
    return blocks


def resolve_labels(blocks: list[Block], lines: list[str]) -> None:
    file_marks = [(idx, m.group(1)) for idx, line in enumerate(lines) for m in [FILE_RE.match(line)] if m]
    prev_end = -1
    for b in blocks:
        m = INFO_LABEL_RE.search(b.info)
        tokens = b.info.split()
        if m:
            b.label, b.label_source = m.group(1), "info-string"
        elif len(tokens) >= 2 and FILENAME_RE.fullmatch(tokens[1]):
            b.label, b.label_source = tokens[1], "info-string"
        else:
            marks = [name for idx, name in file_marks if prev_end < idx < b.start_line]
            if marks:
                b.label, b.label_source = marks[-1], "file-marker"
            else:
                for back in range(b.start_line - 1, max(prev_end, b.start_line - 4), -1):
                    text = lines[back].strip()
                    if not text:
                        continue
                    hm = FILENAME_RE.search(text)
                    if hm and len(text) <= 80:
                        b.label, b.label_source = hm.group(1), "heading"
                    break
        prev_end = b.end_line


# ----------------------------------------------------------------------------------------------
# Steps 5-6: elision, lenient JSON
# ----------------------------------------------------------------------------------------------

def find_elisions(body: str, policy: dict) -> list[dict]:
    hits = []
    for pid, rx in policy["_elision"]:
        m = rx.search(body)
        if m:
            line = body.count("\n", 0, m.start()) + 1
            hits.append({"pattern": pid, "line": line, "excerpt": m.group(0).strip()[:80]})
    return hits


def _strip_comments(s: str) -> tuple[str, bool]:
    out, i, n, in_str, esc, changed = [], 0, len(s), False, False, False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
        elif c == "/" and s[i + 1:i + 2] == "/":
            j = s.find("\n", i)
            i, changed = (n if j == -1 else j), True
            continue
        elif c == "/" and s[i + 1:i + 2] == "*":
            j = s.find("*/", i + 2)
            i, changed = (n if j == -1 else j + 2), True
            continue
        out.append(c)
        i += 1
    return "".join(out), changed


def _strip_trailing_commas(s: str) -> tuple[str, bool]:
    out, i, n, in_str, esc, changed = [], 0, len(s), False, False, False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
        elif c == ",":
            j = i + 1
            while j < n and s[j] in " \t\n":
                j += 1
            if j < n and s[j] in "}]":
                i, changed = i + 1, True
                continue
        out.append(c)
        i += 1
    return "".join(out), changed


def parse_json_lenient(body: str) -> tuple[object | None, list[str], str | None]:
    """(value, repairs, error). Only repairs that cannot change meaning: comments, trailing commas,
    typographic double quotes used as delimiters. Never guesses structure."""
    try:
        return json.loads(body), [], None
    except json.JSONDecodeError as exc:
        first = f"{exc.msg} at line {exc.lineno} column {exc.colno}"
    repaired, repairs = body, []
    repaired, changed = _strip_comments(repaired)
    if changed:
        repairs.append("removed-comments")
    repaired, changed = _strip_trailing_commas(repaired)
    if changed:
        repairs.append("removed-trailing-commas")
    candidates = [(repaired, repairs)]
    if "“" in repaired or "”" in repaired:
        candidates.append((repaired.replace("“", '"').replace("”", '"'), repairs + ["smart-quotes"]))
    for text, reps in candidates:
        if not reps:
            continue
        try:
            return json.loads(text), reps, None
        except json.JSONDecodeError:
            pass
    return None, [], first


# ----------------------------------------------------------------------------------------------
# Identification and path safety
# ----------------------------------------------------------------------------------------------

def sniff_kind(value: object) -> str | None:
    """Which protocol file a parsed JSON value is, from its SHAPE alone."""
    if not isinstance(value, dict):
        return None
    keys = set(value)
    if "concepts" in keys and keys & {"namespace", "model", "version"}:
        return "model.json"
    if keys and keys <= MANIFEST_KEYS and keys & {"renames", "deliberateRemovals", "couldNotExpress", "request"}:
        return "npdev-submission.json"
    if keys & {"upsert", "remove"} and keys <= {"upsert", "remove", "renames", "request"}:
        return "npdev-elements.json"
    changes = value.get("changes")
    if isinstance(changes, list) and changes and all(isinstance(c, dict) and "property" in c for c in changes):
        return "npdev-config-proposal.json"
    return None


def _looks_like_drive(p: str) -> bool:
    # Written without a literal drive pattern on purpose (portable-tooling scan).
    return len(p) >= 2 and p[0].isalpha() and p[1] == ":"


def check_path(raw: str, policy: dict, phase: str) -> tuple[str, str, str | None]:
    """(normalised_path, status, reason). status: allowed | quarantine | denied."""
    p = raw.strip().strip("`\"'").replace("\\", "/")
    if _looks_like_drive(p) or p.startswith("/"):
        return p, "denied", "absolute-path"
    changed = True
    while changed:
        changed = False
        for prefix in policy["pathPrefixesToStrip"]:
            if p.startswith(prefix):
                p, changed = p[len(prefix):], True
    segments = p.split("/")
    if not p or any(seg in ("", ".", "..") for seg in segments):
        return p, "denied", "path-traversal"
    for rule in policy["paths"]["denied"]:
        if fnmatch.fnmatchcase(p, rule["glob"]):
            return p, "denied", rule["reason"]
    phases = policy["paths"]["phases"]
    rules = phases[phase]
    if any(fnmatch.fnmatchcase(p, g) for g in rules["quarantine"]):
        return p, "quarantine", "code"
    if any(fnmatch.fnmatchcase(p, g) for g in rules["allowed"]):
        return p, "allowed", None
    # The pack phase is isolated both ways: pack.json in an app conversation, or model.json in a pack
    # conversation, is simply not allowed -- neither is "a later phase" of the other.
    later = [name for name, r in phases.items() if name != phase and "pack" not in (name, phase)
             and any(fnmatch.fnmatchcase(p, g) for g in r["allowed"] + r["quarantine"])]
    return p, "denied", ("later-phase" if later else "not-in-allowlist")


# ----------------------------------------------------------------------------------------------
# The whole pipeline
# ----------------------------------------------------------------------------------------------

@dataclass
class _Ctx:
    findings: list = field(default_factory=list)

    def add(self, code: str, severity: str, **detail) -> None:
        self.findings.append({"code": code, "severity": severity, **({"detail": detail} if detail else {})})


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def ingest_text(raw: str, *, session: str, expected_round: int, policy: dict, phase: str = "mvp") -> dict:
    ctx = _Ctx()
    session = session.upper()
    result = {"schemaVersion": SCHEMA_VERSION, "protocol": PROTOCOL, "phase": phase,
              "session": {"expected": session, "expectedRound": expected_round, "found": None, "foundRound": None},
              "outcome": None, "files": [], "findings": ctx.findings, "normalization": [], "prose": ""}

    def done(outcome: str) -> dict:
        result["outcome"] = outcome
        return result

    if len(raw) > policy["limits"]["maxAnswerChars"]:
        ctx.add("answer-too-large", "error", chars=len(raw))
        return done(NO_FILES)
    text, notes = normalize_text(raw)
    result["normalization"] = notes
    lines = text.split("\n")

    # Step 2: markers. Prompt echo first: right after COPY the clipboard holds our own prompt.
    if policy["_echo_start"].search(text) and policy["_echo_end"].search(text):
        return done(PROMPT_ECHO)
    begins = [(m.group(1).upper(), int(m.group(2))) for m in BEGIN_RE.finditer(text)]
    ends = [(m.group(1).upper(), int(m.group(2)), m.group(3)) for m in END_RE.finditer(text)]
    if begins:
        mine = [b for b in begins if b[0] == session]
        if not mine:
            result["session"]["found"], result["session"]["foundRound"] = begins[-1]
            return done(WRONG_SESSION)
        result["session"]["found"], result["session"]["foundRound"] = mine[-1]
        if mine[-1][1] < expected_round:
            return done(STALE_ROUND)
        if mine[-1][1] > expected_round:
            ctx.add("round-ahead", "warning", found=mine[-1][1])
    else:
        ctx.add("markers-missing", "info")

    # Step 3: blocks (fenced; unfenced fallback when the fences were lost).
    blocks = split_fenced(lines)
    if not blocks:
        blocks = split_unfenced(text)
        if blocks:
            ctx.add("fences-missing", "info")
    resolve_labels(blocks, lines)
    covered = set()
    for b in blocks:
        covered.update(range(b.start_line, b.end_line + 1))
    prose_lines = [ln for i, ln in enumerate(lines) if i not in covered and not
                   (BEGIN_RE.match(ln) or END_RE.match(ln) or FILE_RE.match(ln))]
    result["prose"] = "\n".join(prose_lines).strip()[: policy["limits"]["proseMaxChars"]]

    if not blocks:
        prose = result["prose"]
        if begins and not [e for e in ends if e[0] == session]:
            # BEGIN arrived, no file and no END: the answer stopped before its first file.
            ctx.add("cut-before-files", "error")
            return done(TRUNCATED)
        if any(rx.search(prose) for rx in policy["_limit"]):
            return done(LIMIT_REACHED)
        strong = [pid for pid, strength, rx in policy["_link"] if strength == "strong" and rx.search(prose)]
        if strong:
            ctx.add("file-link", "info", patterns=strong)
            return done(FILE_LINK_ONLY)
        if any(ln.strip().endswith("?") for ln in prose_lines):
            return done(QUESTIONS)
        weak = [pid for pid, strength, rx in policy["_link"] if strength == "weak" and rx.search(prose)]
        if weak:
            ctx.add("mentions-artifact", "info", patterns=weak)
        return done(NO_FILES)

    # Steps 4-6 per block: truncation, elision, parse.
    truncated, elided, bad_json = False, False, False
    files: dict[str, dict] = {}
    for b in blocks:
        lang = (b.info.split() or [""])[0].lower()
        stripped = b.body.strip()
        is_json = lang in JSON_LANGS or (lang in ("", "text", "plaintext") and stripped[:1] in "{[")
        entry = {"label": b.label, "labelSource": b.label_source, "fenced": b.fenced}
        if len(b.body) > policy["limits"]["maxFileChars"]:
            ctx.add("file-too-large", "error", label=b.label)
            continue
        if not b.closed:
            truncated = True
            ctx.add("unclosed-block", "error", label=b.label, line=b.start_line + 1)
            continue
        if is_json:
            hits = find_elisions(b.body, policy)
            if hits:
                elided = True
                ctx.add("elision", "error", label=b.label, hits=hits)
                continue
            value, repairs, error = parse_json_lenient(b.body)
            if value is None:
                start = stripped.find("{")
                _, depth = balanced_end(stripped, start) if start != -1 else (None, 0)
                if depth > 0:
                    truncated = True
                    ctx.add("unbalanced-json", "error", label=b.label, depthLeft=depth)
                else:
                    bad_json = True
                    ctx.add("json-parse-error", "error", label=b.label, error=error)
                continue
            kind = sniff_kind(value)
            name = b.label or kind
            if b.label and kind and Path(b.label).name != kind and b.label in (
                    "model.json", "npdev-submission.json", "npdev-elements.json", "npdev-config-proposal.json"):
                ctx.add("label-shape-mismatch", "warning", label=b.label, shape=kind)
                name = kind
            if name is None:
                ctx.add("unidentified-block", "warning", line=b.start_line + 1)
                continue
            content = json.dumps(value, indent=2, ensure_ascii=False) + "\n"
            entry.update({"kind": "json", "repairs": repairs, "content": content})
            for rep in repairs:
                ctx.add("json-repaired", "info", label=name, repair=rep)
        else:
            name = b.label
            if name is None:
                ctx.add("unidentified-block", "warning", line=b.start_line + 1, lang=lang)
                continue
            content = b.body if b.body.endswith("\n") else b.body + "\n"
            entry.update({"kind": "text", "repairs": [], "content": content})
        if name in files:
            ctx.add("duplicate-file-last-wins", "warning", path=name)
        files[name] = entry

    # END marker / file count.
    mine_end = [e for e in ends if e[0] == session]
    if begins and not mine_end:
        ctx.add("end-marker-missing", "warning")
    elif mine_end and mine_end[-1][2] is not None:
        declared = int(mine_end[-1][2])
        found_count = len(files) + sum(1 for f in ctx.findings if f["code"] in ("elision", "json-parse-error"))
        if declared > found_count:
            truncated = True
            ctx.add("file-count-short", "error", declared=declared, found=found_count)
        elif declared < found_count:
            ctx.add("file-count-over", "warning", declared=declared, found=found_count)

    if truncated:
        return done(TRUNCATED)
    if elided:
        return done(ELIDED)
    if bad_json:
        return done(BAD_JSON)

    # Path safety + code detection.
    rejected = 0
    for name, entry in files.items():
        path, status, reason = check_path(name, policy, phase)
        if status != "denied" and path.endswith((".html", ".htm")) and any(
                rx.search(entry["content"]) for rx in policy["_html_script"]):
            status, reason = "quarantine", "html-has-script"
        out = {"path": path, "status": status, "reason": reason, "kind": entry["kind"],
               "labelSource": entry["labelSource"], "fenced": entry["fenced"], "repairs": entry["repairs"],
               "bytes": len(entry["content"].encode("utf-8")), "sha256": _sha(entry["content"])}
        if status != "denied":
            out["content"] = entry["content"]
        else:
            rejected += 1
            ctx.add("path-denied", "error", path=path, reason=reason)
        if status == "quarantine":
            ctx.add("code-quarantined", "warning", path=path, reason=reason)
        result["files"].append(out)
    if len(result["files"]) > policy["limits"]["maxFiles"]:
        ctx.add("too-many-files", "error", count=len(result["files"]))
        return done(PARTIAL)

    usable = [f for f in result["files"] if f["status"] != "denied"]
    # The file that makes an answer usable: the model for an app, pack.json for a pack conversation.
    main_files = ("pack.json",) if phase == "pack" else ("model.json", "npdev-elements.json")
    has_model = any(f["path"] in main_files and f["status"] == "allowed" for f in usable)
    if not has_model:
        ctx.add("missing-pack" if phase == "pack" else "missing-model", "error")
        return done(PARTIAL if usable or rejected else NO_FILES)
    if rejected:
        return done(PARTIAL)
    return done(PARSED)
