"""The ONE Python validator of an NPDev theme (schema npdev-theme.v1, NPDevContract/themes/theme.schema.json).

A theme is DATA: 13 hex colours, two font lists from a safe character set, a radius and an elevation. It never contains
CSS. This module is the stdlib-Python twin of `validate()` in NPDevContract/themes/theme-engine.js and of the generator's
ThemeCatalog.java. Two callers read it, so there is one copy and not two:

  scripts/quality/check-themes.py   every built-in and seasonal theme of the repo (THM-1)
  NPDevCli/npdev_relay_themes.py    a theme a browser AI sends (REG-323 T4.3), checked BEFORE it is written -- a theme the
                                    generator refuses fails every generation of the app, so it must never get in

Messages are the engine's own, in plain words, and they name the colour pair (`colors: textMuted on bg is 3.1:1, needs
4.5:1`) so an AI can fix them. Nothing here reads a file; callers pass parsed JSON.

`ALLOWED_KEYS` is one of four places that must agree (twin-pair rule theme-allowed-keys-four-place).
"""
from __future__ import annotations

import json
import re

SCHEMA = "npdev-theme.v1"
MAX_THEME_BYTES = 20_000  # ThemeCatalog.MAX_THEME_BYTES: a larger themes/*.json fails generation
MAX_BASED_ON_DEPTH = 3
TOKENS = ["bg", "surface", "surfaceRaised", "border", "borderStrong", "text", "textMuted",
          "accent", "onAccent", "positive", "negative", "warning", "info"]
HEX = re.compile(r"^#[0-9a-fA-F]{6}$")
FONT = re.compile(r'^[A-Za-z0-9 ,"\'-]{1,200}$')
ID = re.compile(r"^[a-z0-9][a-z0-9-]{1,40}$")
ELEVATIONS = {"flat", "soft", "bevel", "glow"}
ALLOWED_KEYS = {"$schema", "schema", "id", "name", "names", "version", "author", "description", "descriptions", "license",
                "colorScheme", "basedOn", "colors", "darkColors", "typography", "shape"}
BUILT_IN_IDS = {"npdev-dark", "npdev-light", "retro", "synth", "farm", "warm", "windows", "high-contrast",
                "npdev-default"}
# (foreground, background, minimum) -- WCAG 2.2: 1.4.3 text 4.5:1, 1.4.11 UI components 3:1.
CONTRAST_RULES = [
    ("text", "bg", 4.5), ("text", "surface", 4.5), ("text", "surfaceRaised", 4.5),
    ("textMuted", "bg", 4.5), ("textMuted", "surface", 4.5),
    ("accent", "bg", 4.5), ("accent", "surface", 4.5), ("onAccent", "accent", 4.5),
    ("positive", "surface", 4.5), ("negative", "surface", 4.5),
    ("warning", "surface", 4.5), ("info", "surface", 4.5),
    ("borderStrong", "surface", 3.0), ("borderStrong", "bg", 3.0),
]


def _channel(c: int) -> float:
    c = c / 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_color: str) -> float:
    n = int(hex_color[1:], 16)
    return 0.2126 * _channel((n >> 16) & 255) + 0.7152 * _channel((n >> 8) & 255) + 0.0722 * _channel(n & 255)


def contrast_ratio(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def _object(value) -> dict:
    return value if isinstance(value, dict) else {}


def validate_structure(theme: object) -> list[str]:
    """Everything but contrast: schema, id, name, colour scheme, keys, colour tokens, fonts, shape."""
    if not isinstance(theme, dict):
        return ["not a JSON object"]
    errors: list[str] = []
    if theme.get("schema") != SCHEMA:
        errors.append(f'schema must be "{SCHEMA}"')
    if not ID.match(str(theme.get("id") or "")):
        errors.append(f"id must match {ID.pattern}")
    if not theme.get("name") or len(str(theme["name"])) > 40:
        errors.append("name is required (max 40 characters)")
    if theme.get("colorScheme") not in ("light", "dark"):
        errors.append("colorScheme must be light or dark")
    for key in theme:
        if key not in ALLOWED_KEYS:
            errors.append(f"unknown key: {key}")
    for set_name in ("colors", "darkColors"):
        color_set = theme.get(set_name)
        if color_set is None:
            continue
        if not isinstance(color_set, dict):
            errors.append(f"{set_name} must be an object of colour tokens")
            continue
        for key, value in color_set.items():
            if key not in TOKENS:
                errors.append(f"{set_name}.{key} is not a token")
            elif not HEX.match(str(value)):
                errors.append(f"{set_name}.{key} must be #rrggbb, got {json.dumps(value)}")
    if not theme.get("basedOn"):
        for token in TOKENS:
            if not _object(theme.get("colors")).get(token):
                errors.append(f"colors.{token} is required (or use basedOn)")
    if theme.get("darkColors") and theme.get("colorScheme") != "light":
        errors.append("a theme with darkColors must have colorScheme light")
    typography = _object(theme.get("typography"))
    for key in ("fontUi", "fontMono"):
        if key in typography and not FONT.match(str(typography[key])):
            errors.append(f"typography.{key} has characters outside [A-Za-z0-9 ,\"'-]")
    shape = _object(theme.get("shape"))
    if "radius" in shape:
        radius = shape["radius"]
        if not (isinstance(radius, int) and not isinstance(radius, bool) and 0 <= radius <= 24):
            errors.append("shape.radius must be an integer 0..24")
    if "elevation" in shape and shape["elevation"] not in ELEVATIONS:
        errors.append("shape.elevation must be flat, soft, bevel or glow")
    return errors


def contrast_errors(theme: dict, strict: bool = False) -> list[str]:
    """The WCAG floors, for every colour set that is complete. Names the pair that fails."""
    errors: list[str] = []
    for set_name in ("colors", "darkColors"):
        color_set = _object(theme.get(set_name))
        if not color_set or any(not HEX.match(str(color_set.get(t) or "")) for t in TOKENS):
            continue
        for fg, bg, floor in CONTRAST_RULES:
            minimum = 7.0 if strict and floor == 4.5 else floor
            ratio = round(contrast_ratio(color_set[fg], color_set[bg]) * 100) / 100
            if contrast_ratio(color_set[fg], color_set[bg]) < minimum:
                errors.append(f"{set_name}: {fg} on {bg} is {ratio}:1, needs {minimum:g}:1")
    return errors


def validate(theme: object, strict: bool = False) -> list[str]:
    """Same checks, same messages as validate() in theme-engine.js, on the theme's OWN colours. Returns the error list."""
    errors = validate_structure(theme)
    if isinstance(theme, dict):
        errors += contrast_errors(theme, strict)
    return errors


def resolve(theme: dict, catalog: dict) -> dict:
    """Flatten `basedOn` (ancestors first, depth at most 3, cycles refused) like ThemeCatalog.resolve. ValueError names why."""
    chain: list = []
    seen: list = []
    current = theme
    while current is not None:
        ident = str(current.get("id") or "")
        if ident in seen:
            raise ValueError(f"basedOn cycle at {ident}")
        seen.append(ident)
        chain.insert(0, current)
        if not current.get("basedOn"):
            break
        if len(chain) > MAX_BASED_ON_DEPTH:
            raise ValueError(f"basedOn chain deeper than {MAX_BASED_ON_DEPTH} (at {ident})")
        parent = str(current["basedOn"])
        current = catalog.get(parent)
        if current is None:
            raise ValueError(f'basedOn: unknown theme "{parent}"')
    flat: dict = {"colors": {}, "typography": {}, "shape": {}}
    for item in chain:
        for key, value in item.items():
            if key in ("colors", "typography", "shape"):
                flat[key].update(_object(value))
            elif key == "darkColors":
                flat.setdefault("darkColors", {}).update(_object(value))
            elif key != "basedOn":
                flat[key] = value
    return flat


def validate_in_catalog(theme: object, catalog: dict) -> list[str]:
    """ThemeCatalog.validate: structure, then -- for a theme that uses basedOn -- the flattened theme's structure and its
    contrast. `catalog` maps an id to a parsed theme (built-ins plus the app's own). high-contrast keeps its 7:1 floor."""
    errors = validate_structure(theme)
    if errors:
        return errors
    flat = theme
    if theme.get("basedOn"):
        try:
            flat = resolve(theme, catalog)
        except ValueError as exc:
            return [str(exc)]
        identity = dict(flat)
        for key in ("schema", "id", "name", "version", "colorScheme"):
            if key not in identity and key in theme:
                identity[key] = theme[key]
        errors += validate_structure(identity)
    return errors + contrast_errors(flat, strict=theme.get("id") == "high-contrast")
