#!/usr/bin/env python3
"""UI i18n catalogue: every platform-owned text is an id with English AND Portuguese, and no new literal sneaks past.

WHY THIS EXISTS -- REG-349
--------------------------
A generated app whose locale was Portuguese used to be mixed by construction: 11 shell strings translated, every
other platform text English. The owner's decision (2026-11-20) is that a supported language is supported
INTEGRALLY (field names, command names and documentation excepted). The texts live in
NPDevGenerator/generator/src/main/resources/npdev-i18n/<fragment>.<lang>.json (listed in index.json) plus the small
contract map NPDevContract/dsl/.../PlatformStrings.java; the browser looks them up with NPDevI18n.t(id, english, vars)
(templates/npdev-i18n.js.mustache), the shell with shellString(...), business-ui-app with platformString(...).

WHAT IT CHECKS (any failure exits 1)
  1. index.json lists fragments; each has a .en.json and a .pt.json, both flat {id: non-empty string}.
  2. en and pt have the SAME ids per fragment, and the SAME {placeholders} per id.
  3. ids are unique across all fragments + the contract map, shaped <fragment>.<area>.<name> (the `shell` fragment may
     use two segments), and start with their fragment name.
  4. pt differs from en unless the text is only allowed same-in-both tokens (policy: sameAsEnglishTokens).
  5. pt carries its accents: no word of policy.unaccentedPortuguese (nao, usuario, configuracao, ...).
  6. every call site NPDevI18n.t / platformString / shellString (and data-i18n markup) in the templates names an id
     that exists, passes a LITERAL English text, and that text equals the en catalogue value EXACTLY.
  7. no catalogue id is unused (dead catalogue), except the contract ids.
  8. RATCHET: literal English text that is drawn without going through the catalogue (textContent/innerHTML/
     placeholder/title/aria-label/alert/confirm/label: literals, static HTML text nodes). Per-file counts are frozen
     in scripts/policy/ui-i18n-policy.json `unrouted`; a count may only go DOWN (lower the baseline in the same
     commit), a new file with literals fails. The heuristic is deliberately blunt; a real exception is allowed with
     `/* i18n-ignore */` on the same line (use it for text that is truly not platform UI: a CSS value, a code
     sample, a log line shown only to developers).

Run:  python scripts/quality/check-ui-i18n.py [--list-unrouted]
"""
from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
I18N = ROOT / "NPDevGenerator/generator/src/main/resources/npdev-i18n"
TEMPLATES = ROOT / "NPDevGenerator/generator/src/main/resources/npdev-templates"
CONTRACT = ROOT / "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/PlatformStrings.java"
JAVA_ROOT = ROOT / "NPDevGenerator/generator/src/main/java"
POLICY = ROOT / "scripts/policy/ui-i18n-policy.json"

ID_RE = re.compile(r"^[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*){1,2}$")
PLACEHOLDER_RE = re.compile(r"\{([A-Za-z0-9_]+)\}")
STR = r"""("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')"""
CALL_RE = re.compile(r"""(?<![A-Za-z0-9_$.])(?:NPDevI18n\.t|platformString|shellString)\(\s*["']([A-Za-z0-9.]+)["']\s*,\s*""" + r"(" + STR + r")?")
DATA_I18N_RE = re.compile(r"""<([A-Za-z0-9]+)\b[^>]*?\bdata-i18n="([^"]+)"[^>]*>([^<]*)""")
DATA_ATTRS_RE = re.compile(r'\bdata-i18n-attrs="([^"]+)"')
SKIP_DIRS = {"vendor", "node_modules"}
SCAN_SUFFIXES = {".js", ".html", ".css"}


def load_policy() -> dict:
    return json.loads(POLICY.read_text(encoding="utf-8"))


def js_unquote(literal: str) -> str:
    body = literal[1:-1]
    return re.sub(r"\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)", lambda m: _esc(m.group(1)), body)


def _esc(code: str) -> str:
    if code[0] in "ux" and len(code) > 1:
        return chr(int(code[1:], 16))
    return {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "v": "\v", "0": "\0"}.get(code, code)


def contract_maps() -> tuple[dict[str, str], dict[str, str]]:
    text = CONTRACT.read_text(encoding="utf-8")
    en = dict(re.findall(r'\bd\.put\("([^"]+)",\s*"((?:[^"\\]|\\.)*)"\)', text))
    pt = dict(re.findall(r'\bpt\.put\("([^"]+)",\s*"((?:[^"\\]|\\.)*)"\)', text))
    return ({k: js_unquote('"' + v + '"') for k, v in en.items()}, {k: js_unquote('"' + v + '"') for k, v in pt.items()})


def iter_template_files():
    ui_mustache = set(load_policy()["uiMustacheFiles"])
    for path in sorted(TEMPLATES.rglob("*")):
        if not path.is_file() or (SKIP_DIRS & set(path.parts)):
            continue
        if path.suffix in SCAN_SUFFIXES or (path.suffix == ".mustache" and path.name in ui_mustache):
            yield path


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    policy = load_policy()
    errors: list[str] = []
    list_unrouted = "--list-unrouted" in sys.argv

    index = json.loads((I18N / "index.json").read_text(encoding="utf-8"))
    locales, fragments = index["locales"], index["fragments"]
    if locales[0] != "en" or "pt" not in locales:
        errors.append("index.json: locales must start with 'en' and include 'pt'")
    cat: dict[str, dict[str, str]] = {lang: {} for lang in locales}
    owner: dict[str, str] = {}
    contract_en, contract_pt = contract_maps()

    for fragment in fragments:
        per_lang: dict[str, dict] = {}
        for lang in locales:
            path = I18N / f"{fragment}.{lang}.json"
            if not path.exists():
                errors.append(f"{rel(path)}: missing")
                per_lang[lang] = {}
                continue
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                errors.append(f"{rel(path)}: invalid JSON ({exc})")
                data = {}
            if not isinstance(data, dict) or not all(isinstance(k, str) and isinstance(v, str) and v.strip() for k, v in data.items()):
                errors.append(f"{rel(path)}: must be a flat object of id -> non-empty string")
                data = {}
            per_lang[lang] = data
        en_ids = set(per_lang["en"])
        for lang in locales[1:]:
            ids = set(per_lang[lang])
            for missing in sorted(en_ids - ids):
                errors.append(f"{fragment}.{lang}.json: missing id {missing}")
            for extra in sorted(ids - en_ids):
                errors.append(f"{fragment}.{lang}.json: id {extra} has no English text")
        for key, en_text in per_lang["en"].items():
            if not ID_RE.match(key):
                errors.append(f"{fragment}: id '{key}' is not <fragment>.<area>.<name>")
            if not key.startswith(fragment + "."):
                errors.append(f"{fragment}: id '{key}' must start with '{fragment}.'")
            if key.count(".") == 1 and fragment != "shell":
                errors.append(f"{fragment}: id '{key}' has two segments (only the shell fragment may)")
            if key in owner or key in contract_en:
                errors.append(f"id '{key}' defined twice ({owner.get(key, 'contract PlatformStrings')} and {fragment})")
            owner[key] = fragment
            for lang in locales[1:]:
                other = per_lang[lang].get(key)
                if other is None:
                    continue
                if set(PLACEHOLDER_RE.findall(en_text)) != set(PLACEHOLDER_RE.findall(other)):
                    errors.append(f"{fragment}: id '{key}' has different {{placeholders}} in en and {lang}")
        for lang in locales:
            cat[lang].update(per_lang[lang])

    # contract ids: English + Portuguese must both exist and carry accents too.
    for key in contract_en:
        if key not in contract_pt:
            errors.append(f"PlatformStrings: id '{key}' has no Portuguese text")
    cat_en = {**contract_en, **cat["en"]}
    cat_pt = {**contract_pt, **cat["pt"]}

    same_tokens = set(policy["sameAsEnglishTokens"])
    unaccented = [(w, re.compile(r"(?<![A-Za-z])" + re.escape(w) + r"(?![A-Za-z])", re.IGNORECASE)) for w in policy["unaccentedPortuguese"]]
    for key, pt_text in cat_pt.items():
        en_text = cat_en.get(key)
        if en_text is None:
            continue
        if pt_text == en_text:
            words = re.findall(r"[A-Za-z]+", PLACEHOLDER_RE.sub("", en_text))
            if any(w not in same_tokens for w in words):
                errors.append(f"pt text for '{key}' is identical to the English ('{en_text}'): translate it or allow its tokens in the policy")
        for word, pattern in unaccented:
            if pattern.search(pt_text):
                errors.append(f"pt text for '{key}' has an unaccented word ({word}): '{pt_text}'")
                break

    # call sites
    used: set[str] = set()
    for path in iter_template_files():
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in CALL_RE.finditer(text):
            key, fallback_literal = match.group(1), match.group(3)
            used.add(key)
            line = text.count("\n", 0, match.start()) + 1
            if key not in cat_en:
                errors.append(f"{rel(path)}:{line}: id '{key}' is not in the catalogue")
                continue
            if fallback_literal is None:
                errors.append(f"{rel(path)}:{line}: id '{key}': the English text must be a plain string literal")
                continue
            if js_unquote(fallback_literal) != cat_en[key]:
                errors.append(f"{rel(path)}:{line}: id '{key}': English text in the call differs from the catalogue ('{js_unquote(fallback_literal)}' vs '{cat_en[key]}')")
        for match in DATA_I18N_RE.finditer(text):
            key, inner = match.group(2), match.group(3)
            used.add(key)
            line = text.count("\n", 0, match.start()) + 1
            if key not in cat_en:
                errors.append(f"{rel(path)}:{line}: data-i18n id '{key}' is not in the catalogue")
            elif "{{" not in inner and inner.strip() and re.sub(r"\s+", " ", inner.strip()) != cat_en[key] and "&" not in inner:
                errors.append(f"{rel(path)}:{line}: data-i18n '{key}': markup text differs from the catalogue ('{inner.strip()}' vs '{cat_en[key]}')")
        for match in DATA_ATTRS_RE.finditer(text):
            for pair in match.group(1).split(";"):
                if "=" in pair:
                    key = pair.split("=", 1)[1].strip()
                    used.add(key)
                    if key not in cat_en:
                        errors.append(f"{rel(path)}: data-i18n-attrs id '{key}' is not in the catalogue")
    java_text = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in JAVA_ROOT.rglob("*.java"))
    for key in sorted(cat["en"]):
        if key not in used and ('"' + key + '"') not in java_text:
            errors.append(f"catalogue id '{key}' ({owner[key]}) is used nowhere")

    # ratchet
    unrouted = count_unrouted(list_unrouted)
    baseline = policy["unrouted"]
    for path, count in sorted(unrouted.items()):
        allowed = baseline.get(path)
        if allowed is None:
            errors.append(f"{path}: {count} literal English text(s) drawn without the catalogue (new file; route them through NPDevI18n.t)")
        elif count > allowed:
            errors.append(f"{path}: {count} unrouted literal text(s), baseline {allowed}: route the new ones through NPDevI18n.t")
    shrunk = [p for p, c in baseline.items() if unrouted.get(p, 0) < c]
    for path in shrunk:
        errors.append(f"{path}: baseline {baseline[path]} but only {unrouted.get(path, 0)} found: lower it in scripts/policy/ui-i18n-policy.json (the ceiling only ratchets down)")
    total = sum(baseline.values())
    if total > policy["unroutedFrozenTotal"]:
        errors.append(f"policy `unrouted` totals {total} > unroutedFrozenTotal {policy['unroutedFrozenTotal']}: the baseline may not grow")
    elif total < policy["unroutedFrozenTotal"]:
        errors.append(f"policy `unrouted` totals {total} < unroutedFrozenTotal {policy['unroutedFrozenTotal']}: lower unroutedFrozenTotal in the same commit")

    if errors:
        print(f"check-ui-i18n: {len(errors)} problem(s)")
        for error in errors[:80]:
            print("  -", error)
        if len(errors) > 80:
            print(f"  ... and {len(errors) - 80} more")
        return 1
    print(f"check-ui-i18n: OK -- {len(cat_en)} ids ({len(contract_en)} contract + {len(cat['en'])} catalogue), "
          f"{len(locales)} languages, {sum(unrouted.values())} unrouted literal(s) within baseline")
    return 0


# --------------------------------------------------------------------------- unrouted-literal scan

LITERAL = r"""(?P<q>["'`])(?P<s>(?:(?!(?P=q)).)*[A-Za-z]{3,}(?:(?!(?P=q)).)*)(?P=q)"""
JS_PATTERNS = [
    re.compile(r"\b(?:textContent|innerText|innerHTML|placeholder|title|alt|ariaLabel)\s*=\s*" + LITERAL),
    re.compile(r"\b(?:textContent|innerText|innerHTML|placeholder|title|alt|ariaLabel|label|text|message|heading|tooltip|hint)\s*:\s*" + LITERAL),
    re.compile(r"\bsetAttribute\(\s*[\"'](?:title|aria-label|placeholder|alt)[\"']\s*,\s*" + LITERAL),
    re.compile(r"\b(?:alert|confirm|prompt)\(\s*" + LITERAL),
    re.compile(r"\b(?:notify|setStatus|toast|showMessage)\(\s*" + LITERAL),
]
HTML_TEXT = re.compile(r">([^<>{}\n]*[A-Za-z]{3,}[^<>{}\n]*)<")


def looks_like_text(s: str) -> bool:
    s = s.strip()
    if not s or "{{" in s or s.startswith(("http", "/", "#", ".", "<")):
        return False
    if re.fullmatch(r"[a-z0-9_\-:./#\[\]=]+", s):  # identifier / css / path
        return False
    if re.fullmatch(r"[A-Z0-9_]+", s):  # ENUM_CODE
        return False
    if re.fullmatch(r"[A-Za-z]+", s) and s.lower() == s:
        return False
    return bool(re.search(r"[A-Za-z]{3,}", s))


def count_unrouted(verbose: bool) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    english_only = set(load_policy()["englishOnlyDeveloperTools"])
    for path in iter_template_files():
        if path.suffix == ".css" or path.name in english_only:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        scripts_only = path.suffix == ".js" or path.name.endswith(".js.mustache") or path.name == "business-ui-app.mustache"
        hits: list[tuple[int, str]] = []
        lines = text.split("\n")
        in_script = scripts_only
        in_style = False
        for number, line in enumerate(lines, 1):
            lowered = line.lower()
            if "i18n-ignore" in line:
                continue
            if not scripts_only:
                if "<script" in lowered and "</script>" not in lowered:
                    in_script = True
                if "<style" in lowered and "</style>" not in lowered:
                    in_style = True
            stripped = line.strip()
            if stripped.startswith(("//", "*", "/*")):
                continue
            routed = bool(re.search(r"NPDevI18n\.t\(|platformString\(|shellString\(", line))
            if in_script or scripts_only:
                if not routed:
                    for pattern in JS_PATTERNS:
                        for m in pattern.finditer(line):
                            if looks_like_text(m.group("s")):
                                hits.append((number, m.group("s")))
            elif not in_style and "data-i18n" not in line:
                for m in HTML_TEXT.finditer(line):
                    if looks_like_text(m.group(1)):
                        hits.append((number, m.group(1)))
            if not scripts_only:
                if "</script>" in lowered:
                    in_script = False
                if "</style>" in lowered:
                    in_style = False
        if hits:
            counts[rel(path)] = len(hits)
            if verbose:
                for number, s in hits:
                    print(f"{rel(path)}:{number}: {s[:90]}")
    return dict(counts)


if __name__ == "__main__":
    sys.exit(main())
