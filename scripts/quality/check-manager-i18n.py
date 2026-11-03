#!/usr/bin/env python3
"""I18N-1: every string the Manager's static pages draw has an entry in every shipped language catalogue.

WHY THIS EXISTS
---------------
The Manager speaks the language its picker says (NPDevManager/ui/i18n.js). A screen needs no code to
follow the picker -- the engine translates what the page draws from `ui/i18n/<lang>.json`, a flat
{ "English text": "translation" } catalogue -- which means a NEW English string with no catalogue entry is
silent: it just stays English in pt and nobody notices. This checker is the thing that notices.

WHAT IT CHECKS (static half; check-manager-ui-render.mjs checks what the screens draw at runtime)
  1. every text node and every title / aria-label / placeholder / alt in ui/index.html (outside
     translate="no", <code>, <pre>, <kbd>, <samp>, <script>, <style>, <textarea>) that reads as prose is a key
     in every catalogue listed in ui/navigation.json's `languages` except English (English IS the source text);
  2. every catalogue key's `{placeholder}` set is identical to its translation's -- a dropped placeholder
     loses the value the user was told about;
  3. every catalogue is valid JSON and has no empty translation;
  4. a catalogue key that no static page and no script mentions is stale and fails (a renamed English string
     leaves its old translation behind, which is how a catalogue rots).

  5. SCRIPT-DRAWN TEXT (ratchet). Every prose-looking string literal in the screens' scripts is looked up in the
     catalogue too. Static extraction over-reports (a literal is not always drawn) and under-reports (text built
     from pieces at runtime), so this is a RATCHET, not a zero: `scripts/policy/manager-i18n-baseline.json`
     holds `uncoveredScriptStrings`, which may only fall. New English in a script that is not translated raises
     the count and fails; translating (or deleting) strings lets the number come down. The render checker is
     the ground truth for what a screen really draws.

`--list` prints the strings with no entry (JSON) instead of failing, for authoring a translation.

The static "reads as prose" rule is looser than i18n.js's runtime `looksLikeProse` (see `looks_like_prose`).
"""
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "NPDevManager" / "ui"
SKIP_TAGS = {"script", "style", "code", "pre", "kbd", "samp", "textarea", "noscript"}
ATTRS = ("title", "aria-label", "placeholder", "alt")
# Words a page shows that are identifiers or API values, not prose: the same in every language.
NOT_TRANSLATED = {"my-app", "localhost", "postgres", "default", "none", "low", "medium", "high", "xhigh", "max", "CSV", "JSON",
                  "auto", "gpu", "cpu", "anthropic-messages", "gemini-native", "anthropic-work", "warehouse", "screenshot",
                  "toast show", "knob danger", "NPDEV · CRT-9000", "btn ghost", "pbadge verified", "pbadge unknown", "pbadge local",
                  "enginechip on", "enginechip off", "enginechip missing"}
# a placeholder that only carries a plural "s" may be dropped by a language that pluralises differently
OPTIONAL_PLACEHOLDERS = {"plural", "verb"}
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


def looks_like_prose(s: str) -> bool:
    s = s.strip()
    # a static page is authored text, so a lone word ("Re-check", "running") is a string too; i18n.js's runtime
    # `looksLikeProse` is stricter (lone Capitalised words only) because script-drawn data has identifiers.
    return bool(re.search(r"[A-Za-z]{3,}", s)) and bool(re.search(r"\s", s) or re.fullmatch(r"[A-Za-z][A-Za-z-]{2,}", s))


class Extract(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []  # (tag, skipped)
        self.found = {}  # string -> first line

    def _skipped(self):
        return any(s for _, s in self.stack)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        skip = tag in SKIP_TAGS or a.get("translate") == "no"
        if not (self._skipped() or skip):
            for name in ATTRS:
                v = a.get(name)
                if v and looks_like_prose(norm(v)):
                    self.found.setdefault(norm(v), self.getpos()[0])
        if tag not in VOID:
            self.stack.append((tag, skip))

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        if self._skipped():
            return
        t = norm(data)
        if t and looks_like_prose(t):
            self.found.setdefault(t, self.getpos()[0])


def static_strings() -> dict:
    p = Extract()
    p.feed((UI / "index.html").read_text(encoding="utf-8"))
    title = re.search(r"<title>(.*?)</title>", (UI / "index.html").read_text(encoding="utf-8"), re.S)
    if title and looks_like_prose(norm(title.group(1))):
        p.found.setdefault(norm(title.group(1)), 5)
    return p.found


# Scripts whose text is translated another way: `{en, pt}` objects of their own (t(...)), or whole-block help popups
# (help.js, checked separately below).
BILINGUAL_SCRIPTS = {"help.js", "appearance.js", "jobs.js", "home.js", "support.js", "workspace.js", "nav.js", "i18n.js", "theme-engine.js"}
_LIT = re.compile(
    r'"((?:[^"\\\n]|\\.)*)"' + "|" + r"'((?:[^'\\\n]|\\.)*)'" + "|" + r"`((?:[^`\\]|\\.)*)`")


def _script_placeholders(s: str) -> str:
    n = [0]

    def sub(m):
        e = m.group(1).strip()
        if re.fullmatch(r"[A-Za-z_]\w*", e):
            return "{" + e + "}"
        n[0] += 1
        return "{v%d}" % n[0]

    return re.sub(r"\$\{((?:[^{}]|\{[^{}]*\})*)\}", sub, s)


def script_fragments(s: str):
    """The text pieces a script literal puts on screen: placeholders for ${...}, HTML templates split at tags."""
    s = _script_placeholders(s).replace("\\\\", "\\")  # a source `\\` is one backslash on screen
    parts = re.split(r"<[^>]*>", s) if "<" in s and ">" in s else [s]
    for p in parts:
        p = re.sub(r"&\w+;", " ", p)
        p = re.sub(r"\s+", " ", p).strip()
        if not re.findall(r"[A-Za-z]{3,}", re.sub(r"\{\w+\}", "", p)):
            continue
        if not re.search(r"\s", p) and not re.fullmatch(r"[A-Z][a-z]+", p):
            continue
        if p.startswith(("http", "/", ".", "#", "data:", "npdev ", "--")):
            continue
        if re.search(r"[{};]\s*$", p) and ":" in p:
            continue
        bare = re.sub(r"\{\w+\}", "", p).strip()  # "check-row {v1}" is a CSS class list, "engine-local {v1}" too
        if re.fullmatch(r"[\w\-.:/\s,>#\[\]=\"'*+~()]+", bare) and bare.islower() and re.search(r"[-_.:>#\[\]=]", bare):
            continue
        # a template the extractor could not close is code, not text; a literal `\n` is a log line or a prompt body
        if "${" in p or "=>" in p or "===" in p or "\\n" in p or "); " in p or "; }" in p or "() {" in p \
                or p.startswith(";") or p.startswith("].") or p.endswith(";"):
            continue
        # what the Prompter sends TO an AI is an instruction in English, deliberately -- never translated
        if p.startswith(("You are helping extend an app", "Respond with ONLY")):
            continue
        yield p


def script_strings() -> dict:
    """{ "file.js": [fragment, ...] } for every non-bilingual screen script."""
    out = {}
    for f in sorted(UI.glob("*.js")):
        if f.name in BILINGUAL_SCRIPTS:
            continue
        src = f.read_text(encoding="utf-8")
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
        src = re.sub(r"(?m)^\s*//.*$", "", src)
        for q in ('"', "'", "`"):  # "a " + "b" is ONE string at runtime
            src = re.sub(re.escape(q) + r"\s*\+\s*" + re.escape(q), "", src)
        seen = []
        for m in _LIT.finditer(src):
            s = m.group(1) or m.group(2) or m.group(3) or ""
            if s == "use strict":
                continue
            for fr in script_fragments(s):
                if fr not in seen:
                    seen.append(fr)
        out[f.name] = seen
    return out


def placeholders(s: str):
    return sorted(re.findall(r"\{(\w+)\}", s))


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")  # the strings are not all ASCII, and a Windows console is cp1252
    nav = json.loads((UI / "navigation.json").read_text(encoding="utf-8"))
    langs = [l["id"] for l in nav["languages"] if l["id"] != "en"]
    strings = static_strings()
    # text the engine reads from script too: the corpus a catalogue key may legitimately come from
    js_text = "\n".join(p.read_text(encoding="utf-8") for p in sorted(UI.glob("*.js")))
    js_text += "\n" + "\n".join(p.read_text(encoding="utf-8") for p in sorted(UI.glob("*.json")) if p.name != "navigation.json")
    nav_text = (UI / "navigation.json").read_text(encoding="utf-8")
    html_text = (UI / "index.html").read_text(encoding="utf-8")
    problems = []
    listing = {}
    for lang in langs:
        path = UI / "i18n" / f"{lang}.json"
        if not path.exists():
            problems.append(f"{lang}: ui/i18n/{lang}.json does not exist (navigation.json offers the language)")
            continue
        try:
            cat = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            problems.append(f"{lang}: ui/i18n/{lang}.json is not valid JSON: {e}")
            continue
        keys = {norm(k): v for k, v in cat.items() if k != "_comment"}
        # (1) coverage of what the static page draws
        exact = {k for k in keys if "{" not in k}
        patterns = [re.compile("^" + "(.+?)".join(re.escape(x) for x in re.split(r"\{\w+\}", k)) + "$") for k in keys if "{" in k]
        for s, line in strings.items():
            if s in NOT_TRANSLATED:
                continue
            if s in exact or any(p.match(s) for p in patterns):
                continue
            listing.setdefault(lang, []).append(s)
            problems.append(f"{lang}: index.html:{line} draws {s!r} with no entry in ui/i18n/{lang}.json")
        # (2)(3) well-formed entries
        for k, v in keys.items():
            if not isinstance(v, str) or not v.strip():
                problems.append(f"{lang}: {k!r} has an empty translation")
            elif sorted(set(placeholders(k)) - OPTIONAL_PLACEHOLDERS) != sorted(set(placeholders(v)) - OPTIONAL_PLACEHOLDERS) or not set(placeholders(v)) <= set(placeholders(k)):
                problems.append(f"{lang}: {k!r} has placeholders {placeholders(k)} but its translation has {placeholders(v)}")
        # help popups are translated as whole blocks (help.js openHelp): every popup needs both, and every link label
        help_src = (UI / "help.js").read_text(encoding="utf-8")
        for hk in re.findall(r'^  "([a-z0-9-]+)": \{', help_src, flags=re.M):
            for part in ("title", "body"):
                if f"help:{hk}:{part}" not in keys:
                    problems.append(f"{lang}: help popup {hk!r} has no {part} (ui/i18n/{lang}.json key help:{hk}:{part})")
        for label in re.findall(r'label: "([^"]+)"', help_src):
            if label not in keys:
                problems.append(f"{lang}: help link label {label!r} has no entry in ui/i18n/{lang}.json")
        # (4) stale keys: a key none of whose words appears anywhere in the pages or scripts is a leftover from
        # English that was renamed or removed. Deliberately loose -- a runtime string is often source text glued to
        # data, so one word of four letters or more in the source is enough to keep a key alive.
        hay = norm(html_text) + " " + norm(js_text) + " " + norm(nav_text)
        for k in keys:
            words = re.findall(r"[A-Za-z]{4,}", re.sub(r"\{\w+\}", " ", k))
            if words and not any(w in hay for w in words):
                problems.append(f"{lang}: {k!r} is in the catalogue but no page or script draws it (renamed or removed English?)")
    # (5) script-drawn text: a ratchet
    baseline_path = ROOT / "scripts" / "policy" / "manager-i18n-baseline.json"
    baseline = json.loads(baseline_path.read_text(encoding="utf-8")) if baseline_path.exists() else {"uncoveredScriptStrings": 0}
    script_listing = {}
    uncovered = 0
    for lang in langs:
        path = UI / "i18n" / f"{lang}.json"
        if not path.exists():
            continue
        cat = json.loads(path.read_text(encoding="utf-8"))
        keys = {norm(k) for k in cat if k != "_comment"}
        pats = [re.compile("^" + "(.+?)".join(re.escape(x) for x in re.split(r"\{\w+\}", k)) + "$") for k in keys if "{" in k]
        for fname, frs in script_strings().items():
            for fr in frs:
                if fr in keys or fr in NOT_TRANSLATED or any(p.match(fr) for p in pats):
                    continue
                script_listing.setdefault(fname, []).append(fr)
                uncovered += 1
    if "--list" in sys.argv:
        print(json.dumps({"static": listing, "script": script_listing}, ensure_ascii=False, indent=2))
        return 0
    if "--write-baseline" in sys.argv:
        baseline_path.write_text(json.dumps({
            "comment": "I18N-1 ratchet: prose string literals in the Manager's screen scripts with no catalogue entry. May only fall; written by scripts/quality/check-manager-i18n.py --write-baseline (never raise it by hand).",
            "uncoveredScriptStrings": uncovered}, indent=2) + "\n", encoding="utf-8")
        print(f"baseline written: {uncovered}")
        return 0
    if uncovered > baseline["uncoveredScriptStrings"]:
        problems.append(f"scripts draw {uncovered} untranslated string(s), baseline is {baseline['uncoveredScriptStrings']}: translate the new English in NPDevManager/ui/i18n/<lang>.json (run with --list)")
    elif uncovered < baseline["uncoveredScriptStrings"]:
        problems.append(f"scripts now draw only {uncovered} untranslated string(s) but the baseline says {baseline['uncoveredScriptStrings']}: lower it with --write-baseline (the ratchet only falls)")
    if problems:
        for p in problems[:60]:
            print("FAIL " + p)
        if len(problems) > 60:
            print(f"... and {len(problems) - 60} more (run with --list for the strings missing a translation)")
        return 1
    print(f"OK: {len(strings)} static strings, each translated in {', '.join(langs) or '(no non-English language)'}; {uncovered} script string(s) still to translate (ratchet {baseline['uncoveredScriptStrings']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
