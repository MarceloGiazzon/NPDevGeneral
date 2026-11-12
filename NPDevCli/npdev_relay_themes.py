"""Themes a browser AI sends (REG-323 T4.3): `themes/<id>.json` beside model.json, checked BEFORE they are written.

Why a check of our own: the generator's ThemeEmitter reads `themes/*.json` next to model.json and FAILS GENERATION of the
whole app on one bad file ("The app's themes are not valid: ..."). A theme the relay let in would turn a chat answer into
an app that no longer builds. So an answer's themes get the generator's own rules, in the same words, here:

  one file per theme, named `<id>.json`, at most 20,000 bytes, valid JSON, not one of NPDev's own ids,
  structure + WCAG contrast of the theme as it will be USED (basedOn flattened, depth <= 3, no cycle).

Which theme the app USES is not decided here: `ui.theme.name` / `ui.theme.choices` live in config.json's `defaults` envelope
(flat keys), never in model.json (model.schema.json's settings.ui takes only pageRows and dateFormat), and a chat cannot
write config.json. A selection arrives as a config proposal the owner ticks (npdev_relay_config, T4.5b); a theme the app does
not select, and that no proposal selects, is saved with a `theme-not-selected` warning that names the setting, and a model
that carries `settings.ui.theme` is sent back with the way out (see `misplaced_selection`).

Cases an AI gets wrong, each answered in plain words and tested: the file sent under another name than its id, a theme
that reuses a built-in id, a low-contrast colour pair (named), a colour that is not #rrggbb, CSS smuggled into a colour or a
font, a basedOn that points nowhere or loops, a model that tries to select the theme from model.json, and (a WARNING, not an
error) a theme that is saved but that the app does not select. The validator itself is NPDevCli/npdev_theme_validate.py -- one copy, shared
with scripts/quality/check-themes.py. Stdlib only; nothing is written here.
"""
from __future__ import annotations

import fnmatch
import json
from pathlib import Path

import npdev_theme_validate as tv

THEME_GLOB = "themes/*.json"
BUILT_IN_DIR = Path(__file__).resolve().parent.parent / "NPDevContract" / "themes"
SETTING_NAME, SETTING_CHOICES = "ui.theme.name", "ui.theme.choices"  # keys of config.json `defaults`


def is_theme_path(path) -> bool:
    return isinstance(path, str) and fnmatch.fnmatchcase(path, THEME_GLOB)


def builtin_catalog() -> dict:
    """id -> parsed theme of NPDev's own themes, or {} when the contract folder is not beside the CLI."""
    out: dict = {}
    if BUILT_IN_DIR.is_dir():
        for path in sorted(BUILT_IN_DIR.glob("*.json")):
            if path.name == "theme.schema.json":
                continue
            try:
                theme = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if isinstance(theme, dict) and theme.get("id") in tv.BUILT_IN_IDS:
                out[theme["id"]] = theme
    return out


def app_catalog(app_dir) -> dict:
    """id -> parsed theme of the themes already in the app (`<app_dir>/themes/*.json`). A file that cannot be read is
    skipped: it is the app's own problem, not the answer's."""
    out: dict = {}
    folder = Path(app_dir) / "themes" if app_dir else None
    if folder is None or not folder.is_dir():
        return out
    for path in sorted(folder.glob("*.json")):
        try:
            theme = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(theme, dict) and isinstance(theme.get("id"), str):
            out[theme["id"]] = theme
    return out


def read_config(app_dir) -> dict:
    """The app's config.json (beside model.json), or {} when there is none or it cannot be read."""
    try:
        value = json.loads((Path(app_dir) / "config.json").read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {}
    return value if isinstance(value, dict) else {}


def selection(config) -> dict:
    """What the app's config.json `defaults` say about themes: {"name": str|None, "choices": [ids] | "*" | []}."""
    defaults = config.get("defaults") if isinstance(config, dict) else None
    defaults = defaults if isinstance(defaults, dict) else {}
    name = defaults.get(SETTING_NAME)
    raw = defaults.get(SETTING_CHOICES)
    text = raw.strip() if isinstance(raw, str) else ""
    choices = "*" if text == "*" else [part.strip() for part in text.split(",") if part.strip()]
    return {"name": name.strip() if isinstance(name, str) and name.strip() else None, "choices": choices}


def misplaced_selection(model) -> str | None:
    """A sentence for the AI when the model tries to pick the theme from model.json (`settings.ui.theme`, or the dotted
    keys under settings): there is no such setting there, and the app's config is not something a chat can change."""
    settings = model.get("settings") if isinstance(model, dict) else None
    if not isinstance(settings, dict):
        return None
    ui = settings.get("ui")
    found = isinstance(ui, dict) and any(str(key).startswith("theme") for key in ui)
    found = found or any(str(key).startswith("ui.theme") for key in settings)
    if not found:
        return None
    return ("settings.ui.theme - the theme is not chosen in model.json (settings.ui takes only pageRows and dateFormat); "
            "remove it, send the theme as themes/<id>.json, and say in your note which theme the owner should select")


def _ancestors(theme_id: str, catalog: dict) -> list:
    chain, current = [], theme_id
    while current and current not in chain and isinstance(catalog.get(current), dict):
        chain.append(current)
        base = catalog[current].get("basedOn")
        current = str(base) if base else None
    return chain


def known_ids(app_dir, answer_paths=()) -> set:
    """The theme ids a setting may name: NPDev's own, the app's (`themes/*.json` now) and those an answer carries (the
    file name is the id: `check` refuses a file whose name and id differ)."""
    out = set(tv.BUILT_IN_IDS) | set(app_catalog(app_dir))
    for path in answer_paths:
        if is_theme_path(path):
            out.add(path.rsplit("/", 1)[-1][: -len(".json")])
    return out


def changed(app_dir, answer_files: dict) -> list:
    """The answer's theme files that differ from the app's now (a new file, or different JSON -- re-indenting alone is not a
    difference). An answer that only changes a theme leaves model.json identical, and must not be refused as "nothing changed"."""
    out: list = []
    for path, text in sorted((answer_files or {}).items()):
        if not is_theme_path(path):
            continue
        try:
            current = json.loads((Path(app_dir) / path).read_text(encoding="utf-8"))
            if current != json.loads(text):
                out.append(path)
        except (OSError, ValueError):
            out.append(path)  # absent, or one of them is not JSON: the check above says what is wrong with it
    return out


def check(answer_themes: dict, model, app_dir=None, proposed=None) -> dict:  # model: the answer's parsed model.json (or None)
    """`answer_themes` is {path: text} of the answer's `themes/*.json`. Returns {"errors": [lines for the AI], "findings":
    [{code, severity, detail}], "themes": [ids the answer adds or replaces]}. Errors mean NEEDS_FIX; nothing is written."""
    errors: list = []
    findings: list = []
    builtins = builtin_catalog()
    parsed: dict = {}   # path -> theme that parsed, is named right and is not a built-in id
    for path in sorted(answer_themes):
        text = answer_themes[path]
        name = path.rsplit("/", 1)[-1]
        if len(text.encode("utf-8")) > tv.MAX_THEME_BYTES:
            errors.append(f"{path}: the theme file is larger than {tv.MAX_THEME_BYTES} bytes - a theme is colours, two font "
                          "lists, a radius and an elevation; nothing else belongs in it")
            continue
        try:
            theme = json.loads(text)
        except ValueError as exc:
            errors.append(f"{path}: not valid JSON: {str(exc)[:120]}")
            continue
        if not isinstance(theme, dict):
            errors.append(f"{path}: not a JSON object")
            continue
        ident = theme.get("id") if isinstance(theme.get("id"), str) else ""
        if name != f"{ident}.json":
            errors.append(f'{path}: the file name must be <id>.json (the theme\'s id is "{ident}") - '
                          f'rename the file to themes/{ident or "<id>"}.json or change the id')
            continue
        if ident in tv.BUILT_IN_IDS:
            errors.append(f'{path}: "{ident}" is one of NPDev\'s own themes; give yours a different id')
            continue
        parsed[path] = theme

    own = app_catalog(app_dir)
    replaced = sorted(t["id"] for t in parsed.values() if t["id"] in own)
    catalog = {**builtins, **own, **{t["id"]: t for t in parsed.values()}}
    for path in sorted(parsed):
        theme = parsed[path]
        if theme.get("basedOn") in tv.BUILT_IN_IDS and not builtins:
            problems = tv.validate_structure(theme)   # the built-in cannot be read here: its colours are not checked
            findings.append({"code": "theme-base-unchecked", "severity": "warning",
                             "detail": {"theme": theme["id"], "basedOn": theme["basedOn"]}})
        else:
            problems = tv.validate_in_catalog(theme, catalog)
        errors.extend(f"{path}: {problem}" for problem in problems)

    misplaced = misplaced_selection(model)
    if misplaced:
        errors.append(misplaced)
    config = read_config(app_dir)   # what the app selects NOW: the answer cannot change config.json
    if proposed:                    # ... unless the owner ticks a config proposal: that selection counts as used
        config = {"defaults": {**(config.get("defaults") if isinstance(config.get("defaults"), dict) else {}), **proposed}}
    chosen = selection(config)

    used: set = set()
    if chosen["choices"] == "*":
        used = set(catalog)
    else:
        for ident in ([chosen["name"]] if chosen["name"] else []) + chosen["choices"]:
            used.update(_ancestors(ident, catalog))
    for theme in sorted(parsed.values(), key=lambda t: t["id"]):
        code = "theme-replaced" if theme["id"] in replaced else "theme-added"
        findings.append({"code": code, "severity": "info", "detail": {"theme": theme["id"]}})
        if theme["id"] not in used:
            findings.append({"code": "theme-not-selected", "severity": "warning",
                             "detail": {"theme": theme["id"], "setting": SETTING_NAME, "where": "config.json defaults"}})
    return {"errors": errors, "findings": findings, "themes": sorted(t["id"] for t in parsed.values())}
