#!/usr/bin/env python3
"""FORM P5-T3: the experience client (npdev-templates/experience/*) -- static contract plus a node test.

Static: files.json lists exactly the files in the directory (the server emitter copies each verbatim to
static/x/<path>), every listed file exists, nothing outside the directory is referenced, the client stays small,
and no module can turn data into markup (innerHTML/outerHTML/insertAdjacentHTML/document.write/eval/new Function)
or load anything from another origin (no CDN). Every relative import must resolve to a listed file.

Behaviour: NPDevGenerator/generator/src/test/js/experience-client.test.mjs, plain node with a stub DOM and a stub
fetch, no npm dependencies. The client files are copied into a temp dir with a package.json declaring ES modules,
and the platform's browser expression engine is rendered there exactly like check-client-expr-parity.py does.

Needs `node`. Without it the node half SKIPs locally and FAILS under CI=true (the static half always runs).

    python scripts/quality/check-experience-client.py
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT = ROOT / "NPDevGenerator/generator/src/main/resources/npdev-templates/experience"
TEST = ROOT / "NPDevGenerator/generator/src/test/js/experience-client.test.mjs"
ENGINE = ROOT / "NPDevGenerator/generator/src/main/resources/npdev-templates/npdev-expr.js.mustache"
STDLIB = ROOT / "NPDevContract/dsl/src/main/resources/expr/functions-stdlib.json"
TABLE_MARKER = "/*@stdlib-table*/[]/*@end*/"
# 90 KB -> 100 KB: map.js (13.3 KB, P5-T6), pwa.js (2.0 KB, P5-T7) and their small hooks in detail/form/dashboard/style.css
# take ~17 KB; the live-updates module added in the same wave takes the rest of the headroom's difference.
# 100 KB -> 104 KB (FORM P5-T9a): picker.js (5.7 KB, the `picker` archetype that replaces the old selectors[] -- a selection
# control, search, Done hand-off, takePicked) plus its two-line registration in core.js/app.js. The headroom left before it
# was ~4.3 KB, so the budget rises by 4 KB, not by the whole file.
# 104 KB -> 108 KB (FORM P5-T9b): document.js (4.0 KB, the `document` archetype that replaces the old documents[] -- a card with
# authenticated PDF/CSV download buttons onto the existing server renderers, plus a per-record list for an aggregate-bound
# document) and its one-line registration in core.js/app.js. Nothing else grew; the budget rises by 4 KB, what was added.
# 108 KB -> 112 KB (FORM P5, the keyword-admission audit): custom.js (2.9 KB, the `custom` archetype -- the screens[].component
# keyword had a validator and no renderer) plus the `params` query-string hook in core.js/api.js (a query screen's params were
# validated and never sent). The budget rises by 4 KB, about what was added.
# REG-349: every platform text now carries its id and English text (NPDevI18n.t(id, english)), ~80 ids, about +5 KB.
MAX_TOTAL_BYTES = 120 * 1024
# Vendored third-party code (Leaflet, FORM P5-T6, owner decision D12) is pinned and hash-verified, not reviewed line by
# line, so it has its OWN budget and the budget above keeps meaning "our code". Leaflet 1.9.4 needs 170,256 bytes
# (leaflet.js 147,552 + leaflet.css 14,806 + 5 marker/layer PNGs 6,503 + LICENSE 1,395) plus VENDOR.json (< 1 KB): 172 KB.
MAX_VENDOR_BYTES = 172 * 1024
VENDOR = "vendor/"
MANIFEST = "files.json"

FORBIDDEN = [
    (re.compile(r"\binnerHTML\b|\bouterHTML\b|\binsertAdjacentHTML\b|\bdocument\.write\b"), "writes markup"),
    (re.compile(r"\beval\s*\(|\bnew\s+Function\b|\bsetTimeout\s*\(\s*['\"]"), "evaluates strings as code"),
    # The one exact URI allowed is the SVG namespace: document.createElementNS needs it as an identifier and
    # nothing is ever fetched from it (the icons are drawn with it, see core.js iconNode).
    (re.compile(r"https?://(?!www\.w3\.org/2000/svg[\"'])|(?<![:\w])//cdn\.|\bunpkg\.com\b|\bjsdelivr\b"), "references another origin"),
]
IMPORT = re.compile(r"""(?:^|\s)import\s+(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]""")


def static_checks() -> list[str]:
    problems: list[str] = []
    manifest_path = CLIENT / MANIFEST
    if not manifest_path.is_file():
        return [f"{manifest_path.relative_to(ROOT)} is missing"]
    try:
        listed = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return [f"{MANIFEST} is not valid JSON: {exc}"]
    if not isinstance(listed, list) or not all(isinstance(p, str) for p in listed):
        return [f"{MANIFEST} must be a JSON array of relative file paths"]
    for path in listed:
        if path.startswith("/") or "\\" in path or ".." in Path(path).parts or re.match(r"^[A-Za-z]:", path):
            problems.append(f"{MANIFEST}: '{path}' must be a relative path inside the experience directory")
    if len(set(listed)) != len(listed):
        problems.append(f"{MANIFEST} lists a file twice")
    present = {p.relative_to(CLIENT).as_posix() for p in CLIENT.rglob("*") if p.is_file()} - {MANIFEST}
    for missing in sorted(set(listed) - present):
        problems.append(f"{MANIFEST} lists '{missing}' but the file does not exist")
    for extra in sorted(present - set(listed)):
        problems.append(f"'{extra}' is in the experience directory but not in {MANIFEST} (the server would not ship it)")

    total = 0
    vendor_total = 0
    for rel in sorted(set(listed) & present):
        file = CLIENT / rel
        data = file.read_bytes()
        if rel.startswith(VENDOR):
            vendor_total += len(data)  # verified byte for byte below, so no style/origin rules apply to it
            continue
        total += len(data)
        if file.suffix in (".js", ".html", ".css"):
            text = data.decode("utf-8")
            if not text.isascii():
                problems.append(f"{rel}: contains non-ASCII characters (use \\u escapes so the bytes survive any charset)")
            for pattern, why in FORBIDDEN:
                for match in pattern.finditer(text):
                    line = text.count("\n", 0, match.start()) + 1
                    problems.append(f"{rel}:{line}: {why} ({match.group(0).strip()!r})")
        if file.suffix == ".js":
            for spec in IMPORT.findall(text):
                if not spec.startswith("./"):
                    problems.append(f"{rel}: import '{spec}' must be a relative './' module of this directory")
                elif spec[2:] not in listed:
                    problems.append(f"{rel}: imports '{spec}' which is not listed in {MANIFEST}")
        if rel == "index.html":
            for url in re.findall(r"""(?:src|href)=["']([^"']+)["']""", text):
                if not url.startswith("/"):
                    problems.append(f"index.html: '{url}' must be an absolute URL (the page is served at /x/<experience>/)")
    problems.extend(vendor_checks(listed))
    if vendor_total > MAX_VENDOR_BYTES:
        problems.append(f"the vendored files are {vendor_total} bytes; the budget is {MAX_VENDOR_BYTES}")
    if total > MAX_TOTAL_BYTES:
        problems.append(f"the client is {total} bytes; the budget is {MAX_TOTAL_BYTES} (keep it small and readable)")
    return problems


def sha256_of(path: Path) -> str:
    data = path.read_bytes()
    if path.suffix != ".png":
        data = data.replace(b"\r\n", b"\n")  # a Windows checkout may convert line endings; the pinned hash is of the LF form
    return hashlib.sha256(data).hexdigest()


def vendor_checks(listed: list[str]) -> list[str]:
    """Every file under vendor/ is declared with a sha256 in its VENDOR.json and matches it, and vice versa."""
    problems: list[str] = []
    vendored = [p for p in listed if p.startswith(VENDOR)]
    for meta in sorted(p for p in vendored if p.endswith("/VENDOR.json")):
        folder = Path(meta).parent
        try:
            info = json.loads((CLIENT / meta).read_text(encoding="utf-8"))
            declared = info["files"]
        except (OSError, ValueError, KeyError) as exc:
            problems.append(f"{meta}: unreadable or without a 'files' map ({exc})")
            continue
        if not info.get("version"):
            problems.append(f"{meta}: must pin an exact 'version'")
        for rel, expected in declared.items():
            file = CLIENT / folder / rel
            if not file.is_file():
                problems.append(f"{meta}: declares '{rel}' but {folder.as_posix()}/{rel} does not exist")
            elif sha256_of(file) != expected:
                problems.append(f"{folder.as_posix()}/{rel}: sha256 does not match {meta} (vendored file changed; re-vendor deliberately and update the hash)")
        shipped = {p[len(folder.as_posix()) + 1:] for p in vendored if p.startswith(folder.as_posix() + "/")} - {"VENDOR.json"}
        for rel in sorted(shipped - set(declared)):
            problems.append(f"{folder.as_posix()}/{rel} is shipped but has no sha256 in {meta}")
    for rel in vendored:
        if not any(rel.startswith(Path(m).parent.as_posix() + "/") for m in vendored if m.endswith("/VENDOR.json")):
            problems.append(f"{rel}: vendored file without a VENDOR.json beside it")
    return problems


def node_test() -> int:
    node = shutil.which("node")
    if node is None:
        if os.environ.get("CI", "").lower() == "true":
            print("FAIL: node is required to test the experience client under CI=true")
            return 1
        print("SKIP: node is not installed; the experience client behaviour test was not run on this machine")
        return 0
    for path in (TEST, ENGINE, STDLIB):
        if not path.is_file():
            print(f"FAIL: {path.relative_to(ROOT)} is missing")
            return 1
    text = ENGINE.read_text(encoding="utf-8")
    if TABLE_MARKER not in text:
        print("FAIL: npdev-expr.js.mustache lost its " + TABLE_MARKER + " marker")
        return 1
    functions = json.dumps(json.loads(STDLIB.read_text(encoding="utf-8"))["functions"], separators=(",", ":"))
    with tempfile.TemporaryDirectory() as tmp:
        client = Path(tmp) / "client"
        shutil.copytree(CLIENT, client)
        (client / "package.json").write_text('{"type":"module"}', encoding="utf-8")
        engine = Path(tmp) / "npdev-expr.cjs"
        engine.write_text(text.replace(TABLE_MARKER, functions), encoding="utf-8")
        result = subprocess.run([node, str(TEST), str(client), str(engine)], text=True, encoding="utf-8", capture_output=True)
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        print("FAIL: the experience client tests failed (see lines above)")
        return 1
    return 0


def main() -> int:
    if not CLIENT.is_dir():
        print(f"FAIL: {CLIENT.relative_to(ROOT)} is missing")
        return 1
    problems = static_checks()
    for problem in problems:
        print("FAIL: " + problem)
    if problems:
        return 1
    print(f"OK: experience client static contract ({len(json.loads((CLIENT / MANIFEST).read_text(encoding='utf-8')))} files listed in {MANIFEST})")
    return node_test()


if __name__ == "__main__":
    sys.exit(main())
