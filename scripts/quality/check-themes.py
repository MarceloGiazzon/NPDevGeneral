#!/usr/bin/env python3
"""THM-1: every NPDev theme is valid DATA and meets its contrast floor.

A theme is JSON (schema npdev-theme.v1, NPDevContract/themes/theme.schema.json): 13 hex colours, two font
lists from a safe character set, a radius and an elevation. It never contains CSS. This checker is the
stdlib-Python twin of `validate()` in NPDevContract/themes/theme-engine.js (the engine the Manager and
every generated app run), so a theme that fails here would have failed in the user's window.

What it checks
--------------
1. Every built-in (NPDevContract/themes/*.json) and every seasonal test theme
   (NPDevSamples/themes/seasons/*.json): file name == `<id>.json`, structure, and WCAG 2.2 contrast
   (text 4.5:1, control edges 3:1, high-contrast 7:1). A failure NAMES THE PAIR, e.g.
   `npdev-light.json: colors: textMuted on bg is 3.1:1, needs 4.5:1`.
2. The nine built-in ids are all present (a deleted theme would silently shrink the gallery).
3. `NPDevManager/ui/tokens.css` defaults (`--t-*`) equal `npdev-dark.json` -- the window must look right
   before any script runs, and "Dark" is the default theme (owner decision D1).
4. The engine's own unit test (`theme-engine.test.cjs`) passes under Node; SKIPPED with the reason when
   Node is absent (never a silent pass).

The two byte-identical twins (theme-engine.js, a11y.css in the Manager) are pinned by twin-pair rules in
scripts/quality/twin-pair-registry.json, checked by check-twin-pair-consistency.py.

5. THM-10, the public catalogue repo (github.com/MarceloGiazzon/npdev-themes): with `--catalogue <repo>` the
   same `validate()` runs over `<repo>/themes/*.json` (each must also be self-contained, carry version, author
   and license, and not reuse a built-in id) and `<repo>/index.json` must equal what `build_index()` computes
   from those files -- sha256 over the raw file bytes. `--write-index` regenerates it. The catalogue's CI
   checks this repo out beside it and runs exactly this script, so there is one checker, not a copy.

Usage:  python scripts/quality/check-themes.py [--self-test]
        python scripts/quality/check-themes.py --catalogue <repo-dir> [--write-index]
Exit 0 = every theme valid; 1 = at least one failure (each named).
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
THEMES_DIR = ROOT / "NPDevContract" / "themes"
SEASONS_DIR = ROOT / "NPDevSamples" / "themes" / "seasons"
TOKENS_CSS = ROOT / "NPDevManager" / "ui" / "tokens.css"

SCHEMA = "npdev-theme.v1"
TOKENS = ["bg", "surface", "surfaceRaised", "border", "borderStrong", "text", "textMuted",
          "accent", "onAccent", "positive", "negative", "warning", "info"]
HEX = re.compile(r"^#[0-9a-fA-F]{6}$")
FONT = re.compile(r'^[A-Za-z0-9 ,"\'-]{1,200}$')
ID = re.compile(r"^[a-z0-9][a-z0-9-]{1,40}$")
ELEVATIONS = {"flat", "soft", "bevel", "glow"}
ALLOWED_KEYS = {"$schema", "schema", "id", "name", "names", "version", "author", "description", "license",
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


def validate(theme: object, strict: bool = False) -> list[str]:
    """Same checks, same messages as validate() in theme-engine.js. Returns the error list."""
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
        for key, value in color_set.items():
            if key not in TOKENS:
                errors.append(f"{set_name}.{key} is not a token")
            elif not HEX.match(str(value)):
                errors.append(f"{set_name}.{key} must be #rrggbb, got {json.dumps(value)}")
    if not theme.get("basedOn"):
        for token in TOKENS:
            if not (theme.get("colors") or {}).get(token):
                errors.append(f"colors.{token} is required (or use basedOn)")
    if theme.get("darkColors") and theme.get("colorScheme") != "light":
        errors.append("a theme with darkColors must have colorScheme light")
    typography = theme.get("typography") or {}
    for key in ("fontUi", "fontMono"):
        if key in typography and not FONT.match(str(typography[key])):
            errors.append(f"typography.{key} has characters outside [A-Za-z0-9 ,\"'-]")
    shape = theme.get("shape") or {}
    if "radius" in shape:
        radius = shape["radius"]
        if not (isinstance(radius, int) and not isinstance(radius, bool) and 0 <= radius <= 24):
            errors.append("shape.radius must be an integer 0..24")
    if "elevation" in shape and shape["elevation"] not in ELEVATIONS:
        errors.append("shape.elevation must be flat, soft, bevel or glow")
    for set_name in ("colors", "darkColors"):
        color_set = theme.get(set_name)
        if not color_set or any(not HEX.match(str(color_set.get(t) or "")) for t in TOKENS):
            continue
        for fg, bg, floor in CONTRAST_RULES:
            minimum = 7.0 if strict and floor == 4.5 else floor
            ratio = round(contrast_ratio(color_set[fg], color_set[bg]) * 100) / 100
            if contrast_ratio(color_set[fg], color_set[bg]) < minimum:
                errors.append(f"{set_name}: {fg} on {bg} is {ratio}:1, needs {minimum:g}:1")
    return errors


def theme_files() -> list[Path]:
    files = [p for p in sorted(THEMES_DIR.glob("*.json")) if p.name != "theme.schema.json"]
    files += sorted(SEASONS_DIR.glob("*.json"))
    return files


def check_all_themes() -> list[str]:
    failures: list[str] = []
    seen: set[str] = set()
    for path in theme_files():
        try:
            theme = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            failures.append(f"{path.name}: unreadable ({exc})")
            continue
        tid = str(theme.get("id")) if isinstance(theme, dict) else "?"
        if path.name != f"{tid}.json":
            failures.append(f"{path.name}: file name must be <id>.json (id is {tid!r})")
        if tid in seen:
            failures.append(f"{path.name}: duplicate theme id {tid!r}")
        seen.add(tid)
        strict = tid == "high-contrast"
        for err in validate(theme, strict=strict):
            failures.append(f"{path.name}: {err}")
    missing = BUILT_IN_IDS - seen
    if missing:
        failures.append(f"built-in themes missing: {sorted(missing)}")
    return failures


INDEX_SCHEMA = "npdev-theme-index.v1"
SWATCH_TOKENS = ["bg", "surface", "accent", "text", "positive"]
CATALOGUE_REQUIRED = ["version", "author", "license"]


def build_index(themes_dir: Path) -> dict:
    """The catalogue index: one entry per themes/<id>.json, sorted by id, sha256 over the RAW bytes (what the
    Manager downloads and hashes before it imports anything)."""
    entries = []
    for path in sorted(themes_dir.glob("*.json")):
        raw = path.read_bytes()
        theme = json.loads(raw.decode("utf-8"))
        colors = theme.get("colors") or {}
        entry = {
            "id": theme["id"], "name": theme["name"], "names": theme.get("names") or {},
            "version": theme.get("version", ""), "author": theme.get("author", ""),
            "license": theme.get("license", ""), "colorScheme": theme["colorScheme"],
            "swatches": [colors.get(t, "") for t in SWATCH_TOKENS],
            "file": f"themes/{path.name}", "sha256": hashlib.sha256(raw).hexdigest(),
        }
        entries.append(entry)
    return {"schema": INDEX_SCHEMA, "themes": entries}


def index_text(index: dict) -> str:
    return json.dumps(index, indent=2, ensure_ascii=False) + "\n"


def check_catalogue(repo: Path, write_index: bool) -> list[str]:
    themes_dir = repo / "themes"
    if not themes_dir.is_dir():
        return [f"{themes_dir} does not exist"]
    failures: list[str] = []
    seen: set[str] = set()
    for path in sorted(themes_dir.glob("*.json")):
        try:
            theme = json.loads(path.read_bytes().decode("utf-8"))
        except (OSError, ValueError) as exc:
            failures.append(f"{path.name}: unreadable ({exc})")
            continue
        tid = str(theme.get("id")) if isinstance(theme, dict) else "?"
        if path.name != f"{tid}.json":
            failures.append(f"{path.name}: file name must be <id>.json (id is {tid!r})")
        if tid in seen:
            failures.append(f"{path.name}: duplicate theme id {tid!r}")
        seen.add(tid)
        if tid in BUILT_IN_IDS:
            failures.append(f"{path.name}: {tid!r} is a built-in NPDev theme id; the Manager refuses to replace it")
        if isinstance(theme, dict):
            if theme.get("basedOn"):
                failures.append(f"{path.name}: catalogue themes must be self-contained (no basedOn)")
            for key in CATALOGUE_REQUIRED:
                if not theme.get(key):
                    failures.append(f"{path.name}: {key} is required in a catalogue theme")
        for err in validate(theme):
            failures.append(f"{path.name}: {err}")
    if failures:
        return failures
    wanted = index_text(build_index(themes_dir))
    index_path = repo / "index.json"
    if write_index:
        index_path.write_bytes(wanted.encode("utf-8"))
        print(f"  wrote {index_path} ({len(seen)} themes)")
        return []
    if not index_path.exists():
        return ["index.json does not exist (run with --write-index)"]
    if index_path.read_bytes().decode("utf-8") != wanted:
        return ["index.json is stale: it does not match themes/*.json (run with --write-index)"]
    print(f"  index.json matches {len(seen)} theme files (sha256 over raw bytes)")
    return []


def check_tokens_css_defaults() -> list[str]:
    if not TOKENS_CSS.exists():
        return [f"{TOKENS_CSS.relative_to(ROOT)} does not exist"]
    css = TOKENS_CSS.read_text(encoding="utf-8")
    dark = json.loads((THEMES_DIR / "npdev-dark.json").read_text(encoding="utf-8"))["colors"]
    names = {"bg": "--t-bg", "surface": "--t-surface", "surfaceRaised": "--t-surface-raised",
             "border": "--t-border", "borderStrong": "--t-border-strong", "text": "--t-text",
             "textMuted": "--t-text-muted", "accent": "--t-accent", "onAccent": "--t-on-accent",
             "positive": "--t-positive", "negative": "--t-negative", "warning": "--t-warning", "info": "--t-info"}
    failures = []
    for token, var in names.items():
        match = re.search(re.escape(var) + r":\s*(#[0-9a-fA-F]{6})\s*;", css)
        if not match:
            failures.append(f"tokens.css: default for {var} not found")
        elif match.group(1).lower() != dark[token].lower():
            failures.append(f"tokens.css: {var} is {match.group(1)} but npdev-dark.json says {dark[token]} "
                            f"(the pre-script look must equal the default theme)")
    return failures


def check_engine_test() -> list[str]:
    node = shutil.which("node")
    test = THEMES_DIR / "theme-engine.test.cjs"
    if not node:
        print("  SKIPPED theme-engine.test.cjs -- Node is not on PATH (the Python twin above still ran)")
        return []
    proc = subprocess.run([node, str(test)], capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        return [f"theme-engine.test.cjs failed: {(proc.stdout + proc.stderr).strip()[:600]}"]
    print(f"  {proc.stdout.strip()}")
    return []


def self_test() -> bool:
    """The checker must catch what it claims to: a planted low-contrast pair, a CSS-injecting value, a
    wrong file/id, and must NAME the pair."""
    base = json.loads((THEMES_DIR / "npdev-light.json").read_text(encoding="utf-8"))
    if validate(base):
        print(f"SELF-TEST FAILED: the real npdev-light theme does not validate: {validate(base)}")
        return False
    low = json.loads(json.dumps(base))
    low["colors"]["textMuted"] = "#bbbbbb"
    errs = validate(low)
    if not any(e.startswith("colors: textMuted on bg is") for e in errs):
        print(f"SELF-TEST FAILED: a planted low-contrast pair was not named: {errs}")
        return False
    hostile = json.loads(json.dumps(base))
    hostile["colors"]["bg"] = "red; background:url(https://evil.example/x)"
    hostile["typography"] = {"fontUi": "x; } body { display:none"}
    errs = validate(hostile)
    if not (any("colors.bg" in e for e in errs) and any("fontUi" in e for e in errs)):
        print(f"SELF-TEST FAILED: hostile values were not refused: {errs}")
        return False
    hc = json.loads((THEMES_DIR / "high-contrast.json").read_text(encoding="utf-8"))
    if validate(hc, strict=True):
        print(f"SELF-TEST FAILED: high-contrast fails its own 7:1 floor: {validate(hc, strict=True)}")
        return False
    if not validate(base, strict=True):
        print("SELF-TEST FAILED: npdev-light should NOT meet the 7:1 floor, so strict mode is not strict")
        return False
    print("Self-test: low contrast is named by pair, hostile CSS is refused, strict mode is strict -- OK")
    return True


def main() -> int:
    if not self_test():
        return 1
    if "--self-test" in sys.argv:
        return 0
    if "--catalogue" in sys.argv:
        repo = Path(sys.argv[sys.argv.index("--catalogue") + 1]).resolve()
        print(f"Theme catalogue check (THM-10): {repo}")
        failures = check_catalogue(repo, "--write-index" in sys.argv)
        if failures:
            print(f"\nFAILED: {len(failures)} problem(s):")
            for f in failures:
                print(f"  - {f}")
            return 1
        print("OK: every catalogue theme is valid data and the index matches.")
        return 0
    print("Theme check (THM-1):")
    failures: list[str] = []
    failures += check_all_themes()
    print(f"  {len(theme_files())} theme files checked (structure + contrast)")
    failures += check_tokens_css_defaults()
    failures += check_engine_test()
    if failures:
        print(f"\nFAILED: {len(failures)} problem(s):")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("OK: every theme is valid data and meets its contrast floor.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
