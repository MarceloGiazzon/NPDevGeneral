"""REG-354: what CODE a browser AI may add to an app -- one machine setting, three answers.

`relay.codeFromBrowserAi` (a per-machine setting, never part of an app or a pack):

  never  (default)  every piece of code in an answer is quarantined: not accepted, not written, the person is told why.
  pages             JavaScript pages (web/*.js|mjs) and html/svg/css that carry a script are accepted.
  all               also Java capabilities (capabilities/*) and conversion hooks (conversion-hooks/*) are accepted by the relay.

Where the setting is read from, first match wins: the environment variable NPDEV_RELAY_CODE_MODE, then
`<NPDev home>/settings.json` {"relay": {"codeFromBrowserAi": "<mode>"}}. Anything else (a typo, a missing file) is `never`:
a setting that cannot be read can only ever make the relay stricter.

Accepting is never silent. For every accepted file the answer's preview carries a REQUIRED confirmation (`code:<path>`, and for
a web file a second one, `code-public:<path>`, because a file under web/ is served to anyone who has its address), apply refuses
without them, the code is described in words (what it reads, sends, runs) before anyone confirms, and an accepted file is recorded in
`browser-ai-code.json` beside the model with where it came from and its sha256, so it can be listed and removed (`remove_code`).
The scan describes; it never judges: a scan cannot prove code safe, and the text says so.

Java capabilities and conversion hooks are accepted by the RELAY in mode `all`, but nothing writes them into an app yet: the
generator reads such code only through its hash-pinned extension manifest and the model's references (REG-355). Until then an
answer carrying them is sent back with that sentence instead of being half applied.
"""
from __future__ import annotations

import fnmatch
import hashlib
import json
import os
import re
from pathlib import Path

MODES = ("never", "pages", "all")
DEFAULT = "never"
ENV_VAR = "NPDEV_RELAY_CODE_MODE"
SETTING = ("relay", "codeFromBrowserAi")
PROVENANCE_FILE = "browser-ai-code.json"
PROVENANCE_SCHEMA = "npdev-browser-ai-code.v1"

WEB_KINDS = ("js-page", "page-script")        # served to anyone who has the address
KINDS = ("js-page", "page-script", "java-extension", "java-capability", "conversion-hook")
JAVA_KINDS = ("java-extension", "java-capability", "conversion-hook")
# REG-355 wrote the first Java kind: a class bound to a flow (npdev_relay_extensions). A capability or a conversion hook is a
# different platform seam with no hash-pinned manifest of its own yet, so the relay still sends those back.
NOT_WRITTEN_YET = ("java-capability", "conversion-hook")

_POLICY_PATH = Path(__file__).resolve().parent / "relay-ingest-policy.json"


def _scan_policy() -> dict:
    return json.loads(_POLICY_PATH.read_text(encoding="utf-8"))["codeScan"]


# --------------------------------------------------------------------------- the setting

def npdev_home() -> Path:
    """The same rule as npdev_db_server.npdev_home, the Monitor and the Manager's state::home (NPDEV_MANAGER_HOME wins)."""
    override = os.environ.get("NPDEV_MANAGER_HOME")
    if override and override.strip():
        return Path(override).expanduser()
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        return Path(os.environ["LOCALAPPDATA"]) / "NPDev"
    return Path.home() / ".local" / "share" / "npdev"


def settings_path() -> Path:
    return npdev_home() / "settings.json"


def _read_settings() -> dict:
    try:
        data = json.loads(settings_path().read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def code_mode() -> str:
    """never | pages | all. The environment variable wins over the file; anything unreadable or unknown is `never`."""
    env = (os.environ.get(ENV_VAR) or "").strip().lower()
    if env:
        return env if env in MODES else DEFAULT
    node = _read_settings()
    for key in SETTING:
        node = node.get(key) if isinstance(node, dict) else None
    value = str(node).strip().lower() if node is not None else ""
    return value if value in MODES else DEFAULT


def where_set() -> str:
    """Which source decided the mode: env | file | default (for `npdev ai relay code-mode`)."""
    if (os.environ.get(ENV_VAR) or "").strip():
        return "env"
    node = _read_settings()
    for key in SETTING:
        node = node.get(key) if isinstance(node, dict) else None
    return "file" if node is not None else "default"


def set_code_mode(mode: str) -> Path:
    """Write the setting into settings.json, keeping every other key. ValueError for a mode that does not exist."""
    mode = (mode or "").strip().lower()
    if mode not in MODES:
        raise ValueError(f"the code setting is one of {', '.join(MODES)} - not {mode!r}")
    path = settings_path()
    data = _read_settings()
    section = data.get(SETTING[0])
    if not isinstance(section, dict):
        section = {}
    section[SETTING[1]] = mode
    data[SETTING[0]] = section
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, indent=2) + chr(10), encoding="utf-8")
    os.replace(tmp, path)
    return path


# --------------------------------------------------------------------------- what kind of code a path is

def kind_of(path: str, reason: str | None = None) -> str | None:
    """The code kind of a quarantined file: by its path glob, else `page-script` for a page whose text carries a script."""
    for glob, kind in _scan_policy()["kinds"].items():
        if fnmatch.fnmatchcase(path, glob):
            return kind
    if reason and reason.endswith("-has-script"):
        return "page-script"
    return None


def accepts(mode: str, kind: str | None) -> bool:
    if kind not in KINDS:
        return False
    if mode == "all":
        return True
    return mode == "pages" and kind in WEB_KINDS


def scan(text: str, kind: str) -> list[dict]:
    """What the code DOES, as [{id, en, pt}]: only the things that appear. Never a verdict."""
    language = "java" if kind in JAVA_KINDS else "js"
    found = []
    for rule in _scan_policy()["scan"][language]:
        if re.search(rule["regex"], text or "", re.I if language == "js" and rule["id"] == "sign-in-key" else 0):
            found.append({"id": rule["id"], "en": rule["en"], "pt": rule["pt"]})
    return found


def confirmations(path: str, kind: str) -> list[str]:
    """The confirmation codes apply requires before this file is written: `code:<kind>:<tag>` and, for a web file, `code-public:<kind>:<tag>`.
    The tag is a short hash of the path, not the path: the Manager only passes codes made of letters, digits and `_ - : . >`, and an AI
    may name a file anything."""
    tag = hashlib.sha256(path.encode("utf-8")).hexdigest()[:12]
    codes = [f"code:{kind}:{tag}"]
    if kind in WEB_KINDS:
        codes.append(f"code-public:{kind}:{tag}")
    return codes


def warning_lines(path: str, kind: str, found: list[dict], portuguese: bool) -> list[dict]:
    """The preview warnings for one accepted file: each is a REQUIRED tick (needsConfirmation) in the Manager."""
    what = ", ".join(item["pt" if portuguese else "en"] for item in found)
    kind_en = {"js-page": "JavaScript page", "page-script": "page with a script", "java-extension": "Java class for a flow",
               "java-capability": "Java capability", "conversion-hook": "conversion hook"}[kind]
    kind_pt = {"js-page": "página JavaScript", "page-script": "página com script", "java-extension": "classe Java de um fluxo",
               "java-capability": "capacidade em Java", "conversion-hook": "gancho de conversão"}[kind]
    lines = []
    ask = confirmations(path, kind)
    if portuguese:
        doing = f" Ela {what}." if what else " O que ela faz não foi reconhecido pela verificação."
        if kind == "java-extension":
            doing += (" Ela roda no servidor do app, com acesso aos dados dele; ao gerar o app a plataforma ainda a confere com a sua"
                      " própria política de código e pode recusá-la.")
        lines.append({"code": ask[0], "needsConfirmation": True, "params": {"family": "CODE", "path": path, "kind": kind},
                      "text": (f"A IA escreveu código: {path} ({kind_pt}).{doing} Nenhuma verificação prova que um código é seguro: "
                               f"só aceite se você confia nele. Marque para aceitar este arquivo.")})
        if kind in WEB_KINDS:
            lines.append({"code": ask[1], "needsConfirmation": True,
                          "params": {"family": "CODE", "path": path, "kind": kind},
                          "text": (f"{path} fica acessível a qualquer pessoa que tenha o endereço, sem entrar no app. "
                                   f"Marque para confirmar que isso é aceitável.")})
    else:
        doing = f" It {what}." if what else " What it does was not recognised by the check."
        if kind == "java-extension":
            doing += (" It runs on the app's server with access to its data; when the app is generated the platform checks it again"
                      " against its own code policy and may refuse it.")
        lines.append({"code": ask[0], "needsConfirmation": True, "params": {"family": "CODE", "path": path, "kind": kind},
                      "text": (f"The AI wrote code: {path} ({kind_en}).{doing} No check can prove code safe: accept it only if you "
                               f"trust it. Tick to accept this file.")})
        if kind in WEB_KINDS:
            lines.append({"code": ask[1], "needsConfirmation": True,
                          "params": {"family": "CODE", "path": path, "kind": kind},
                          "text": (f"{path} is served to anyone who has its address, without signing in to the app. "
                                   f"Tick to confirm that is acceptable.")})
    return lines


def briefing(mode: str) -> str:
    """The sentence a phase-f4 prompt adds when code is accepted on this machine (nothing for `never`: prompts stay byte-identical)."""
    if mode == "pages":
        return ("This computer accepts JavaScript pages. If a request truly needs behaviour a page cannot have without code, you may "
                "send it as web/<name>.js (or a web page with a script), keep it small and plain, and explain in one sentence what it "
                "does. The person must confirm each file before it is added.")
    if mode == "all":
        return ("This computer accepts code. If a request truly needs behaviour that data cannot express, you may send a JavaScript "
                "page (web/<name>.js, or a web page with a script); keep it small and plain and explain in one sentence what it does. "
                "The person must confirm each file before it is added. Server-side logic that a flow's steps cannot express may be "
                "sent as ONE Java class, extensions/<Name>.java, declared exactly as "
                "`public final class <Name> { public Map<String, Object> execute(NPDevProcedureContext ctx) { ... } }` (the context "
                "offers tenantId(), actorId() and saveMany(concept, records); import only java.util.List, Map, Set, Optional, UUID, java.math.BigDecimal and java.time.Instant - no other import, no static or wildcard import, no threads, files, network, reflection or System calls), with a flow in model.json that "
                "names it: {\"untrustedExtensionEntrypoint\": \"extensions/<Name>.java\"} in the flow's metadata and one placeholder "
                "step. The app must declare a role (the class is bound to it). Do not send other Java (capabilities/*) or "
                "conversion hooks: they cannot be added to an app from here.")
    return ""


# --------------------------------------------------------------------------- the record of accepted code

def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def read_provenance(app_dir) -> list[dict]:
    try:
        data = json.loads((Path(app_dir) / PROVENANCE_FILE).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return []
    items = data.get("files") if isinstance(data, dict) else None
    return [i for i in items if isinstance(i, dict)] if isinstance(items, list) else []


def provenance_text(app_dir, accepted: list[dict], session_code: str, accepted_at: str) -> str:
    """The new text of browser-ai-code.json: what was already recorded plus this answer's accepted files (a path accepted
    again replaces its old entry). `accepted` is [{path, kind, content, patterns}]."""
    entries = {e.get("path"): e for e in read_provenance(app_dir)}
    for item in accepted:
        entries[item["path"]] = {"path": item["path"], "kind": item["kind"], "origin": "browser-ai", "conversation": session_code,
                                 "acceptedAt": accepted_at, "sha256": _sha(item["content"]),
                                 "scan": [p["id"] for p in item.get("patterns") or []]}
    doc = {"schema": PROVENANCE_SCHEMA, "note": "Code a browser AI wrote and the owner accepted. Remove it with: npdev ai relay remove-code.",
           "files": [entries[k] for k in sorted(entries, key=str)]}
    return json.dumps(doc, indent=2) + chr(10)


def remove_code(app_dir, paths: list[str] | None = None) -> dict:
    """Delete the accepted code files (all of them, or the named ones) and their records. Returns {removed, missing, kept}."""
    root = Path(app_dir)
    entries = read_provenance(root)
    wanted = {p.replace(chr(92), "/") for p in paths} if paths else None
    removed, missing, kept = [], [], []
    java: dict = {}
    for entry in entries:
        rel = str(entry.get("path") or "")
        if wanted is not None and rel not in wanted:
            kept.append(entry)
            continue
        target = (root / rel).resolve()
        try:
            target.relative_to(root.resolve())
        except ValueError:
            kept.append(entry)       # a recorded path that leaves the app is never followed
            continue
        if target.is_file():
            target.unlink()
            removed.append(rel)
        else:
            missing.append(rel)
        if entry.get("kind") == "java-extension":   # REG-355: a class leaves with its manifest entry, or generation fails closed
            import npdev_relay_extensions as extensions_mod

            java[rel] = extensions_mod.forget(root, rel)
    if wanted is not None:
        unknown = sorted(wanted - {str(e.get("path")) for e in entries})
        missing += unknown
    record = root / PROVENANCE_FILE
    if kept:
        record.write_text(json.dumps({"schema": PROVENANCE_SCHEMA, "files": kept}, indent=2) + chr(10), encoding="utf-8")
    elif record.is_file():
        record.unlink()
    out = {"removed": removed, "missing": missing, "kept": [str(e.get("path")) for e in kept]}
    if java:   # per class: manifest removed|absent|none, and the files whose flows still name it (the model is the author's)
        out["javaExtensions"] = java
    return out
