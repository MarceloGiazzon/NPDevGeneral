"""FORM P5-T9c / P5-T9g codemod: the top-level ``appShell``, ``guidePages`` and ``settings`` keys became one optional
root ``backOffice``.

Two rewrites, no guessing.

A model (a JSON object with ``dslVersion`` and ``namespace``)::

    appShell: {defaultRoute, navigation}     backOffice: {defaultRoute, navigation, pages}
    guidePages: [...]                        (the same entries, now ``pages``)
    settings: {locale, strings, ui}          backOffice.locale / backOffice.strings (settings.ui was read by nothing: dropped)

A pack (a JSON object with ``pack``)::

    guidePages: [...]                        backOffice: {pages: [...]}

The new key is independent of experiences and roles, so nothing else is added to the model. Safe by construction:
  * dry-run by default (``--apply`` writes);
  * textual surgery, never a re-dump of the whole file, so a file's line endings and layout survive (the new member
    takes the place of the old ``appShell`` member, or of ``guidePages`` when there is no ``appShell``);
  * idempotent (a file without a top-level ``appShell``/``guidePages``/``settings`` is untouched);
  * ``--backup-dir`` copies each file there before rewriting it (use it for trees that are not under git).

Usage::

    python NPDevCli/appshell_guidepages_to_backoffice_codemod.py --root <dir-or-file> [--root ...] [--apply] [--backup-dir <dir>]
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path

SKIP_PARTS = {"Output", "node_modules", "build", ".gradle", "archive", ".git", "tmp", "ai-scenarios", "__pycache__"}
SKIP_NAMES = {"ai-model.json"}


@dataclass
class Report:
    path: str
    migrated: int = 0
    notes: list[str] = field(default_factory=list)
    manual: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return self.migrated > 0 and not self.manual


# ------------------------------------------------------------------ textual JSON scanning

def _string_end(text: str, i: int) -> int:
    j = i + 1
    while text[j] != '"':
        j += 2 if text[j] == "\\" else 1
    return j + 1


def _value_end(text: str, i: int) -> int:
    """Offset just past the JSON value starting at ``i``."""
    if text[i] == '"':
        return _string_end(text, i)
    if text[i] in "{[":
        depth = 0
        while True:
            ch = text[i]
            if ch == '"':
                i = _string_end(text, i)
                continue
            if ch in "{[":
                depth += 1
            elif ch in "}]":
                depth -= 1
                if depth == 0:
                    return i + 1
            i += 1
    while i < len(text) and text[i] not in ",}] \t\r\n":
        i += 1
    return i


def _scan_object(text: str, start: int) -> dict[str, tuple[int, int, int]]:
    """Members of the object whose ``{`` is at ``start``: key -> (key's opening quote, value start, value end)."""
    spans: dict[str, tuple[int, int, int]] = {}
    i = start + 1
    n = len(text)
    while True:
        while i < n and text[i] in " \t\r\n,":
            i += 1
        if i >= n or text[i] == "}":
            return spans
        key_start = i
        i = _string_end(text, i)
        key = json.loads(text[key_start:i])
        while text[i] in " \t\r\n:":
            i += 1
        value_start = i
        i = _value_end(text, i)
        spans[key] = (key_start, value_start, i)


def _line_start(text: str, offset: int) -> int:
    return text.rfind("\n", 0, offset) + 1


def _member_indent(text: str, key_start: int) -> str:
    indent = text[_line_start(text, key_start):key_start]
    return "  " if indent.strip() else indent


def _dump(value, indent: str) -> str:
    """json.dumps(value) with every line after the first prefixed by ``indent`` (the first follows a key)."""
    return json.dumps(value, indent=2, ensure_ascii=False).replace("\n", "\n" + indent)


def _remove_member(work: str, key_start: int, value_end: int) -> str:
    """Delete one member (the whole line(s) it occupies) and fix the surrounding comma."""
    start = _line_start(work, key_start)
    end = value_end
    while end < len(work) and work[end] in " \t":
        end += 1
    if end < len(work) and work[end] == ",":
        end += 1
        while end < len(work) and work[end] in " \t":
            end += 1
        if end < len(work) and work[end] == "\n":
            end += 1
        return work[:start] + work[end:]
    if end < len(work) and work[end] == "\n":
        end += 1
    head = work[:start].rstrip()
    if head.endswith(","):
        head = head[:-1]
    return head + "\n" + work[end:]


# ------------------------------------------------------------------ the rewrite

def _back_office_object(data: dict) -> dict:
    obj: dict = {}
    shell = data.get("appShell")
    if isinstance(shell, dict):
        for key in ("defaultRoute", "navigation"):
            if key in shell:
                obj[key] = shell[key]
    pages = data.get("guidePages")
    if isinstance(pages, list) and pages:
        obj["pages"] = pages
    settings = data.get("settings")
    if isinstance(settings, dict):
        if settings.get("locale"):
            obj["locale"] = settings["locale"]
        if settings.get("strings"):
            obj["strings"] = settings["strings"]
    return obj


def _is_pack(data: dict) -> bool:
    return isinstance(data.get("pack"), str)


def _is_model(data: dict) -> bool:
    return "dslVersion" in data and "namespace" in data and not _is_pack(data)


def migrate_text(text: str, path: str = "<memory>") -> tuple[str, Report]:
    """Return the migrated text (unchanged when there is nothing to do or a MANUAL case exists) and a report."""
    report = Report(path)
    try:
        data = json.loads(text)
    except ValueError:
        return text, report
    if not isinstance(data, dict):
        return text, report
    has_shell = isinstance(data.get("appShell"), dict)
    has_pages = isinstance(data.get("guidePages"), list)
    has_settings = isinstance(data.get("settings"), dict) and not _is_pack(data)
    if not has_shell and not has_pages and not has_settings:
        return text, report

    newline = "\r\n" if "\r\n" in text else "\n"
    work = text.replace("\r\n", "\n")
    spans = _scan_object(work, work.index("{"))

    if _is_pack(data):
        if has_shell:
            report.manual.append("a pack declares appShell; a pack carries only backOffice.pages -- migrate it by hand")
            return text, report
        if "backOffice" in data:
            report.manual.append("the pack declares both guidePages and backOffice; merge them by hand")
            return text, report
        key_start, _value_start, value_end = spans["guidePages"]
        indent = _member_indent(work, key_start)
        block = '"backOffice": {\n' + indent + '  "pages": ' + _dump(data["guidePages"], indent + "  ") + "\n" + indent + "}"
        work = work[:key_start] + block + work[value_end:]
        report.migrated = 1
        report.notes.append("pack guidePages -> backOffice.pages")
        return (work.replace("\n", newline) if newline != "\n" else work), report

    if not _is_model(data):
        report.manual.append("neither a model (dslVersion + namespace) nor a pack (pack); migrate it by hand")
        return text, report
    obj = _back_office_object(data)
    if "backOffice" in data:
        existing = data["backOffice"]
        if has_shell or has_pages or not isinstance(existing, dict) or set(existing) & set(obj):
            report.manual.append("the model declares both the old keys and backOffice; merge them by hand")
            return text, report
        # Only settings is left to move and the backOffice object has no clashing member: fold locale/strings into it
        # (the backOffice member keeps its place, settings is removed).
        key_start, _value_start, value_end = spans["backOffice"]
        indent = _member_indent(work, key_start)
        work = work[:key_start] + '"backOffice": ' + _dump({**existing, **obj}, indent) + work[value_end:]
        spans = _scan_object(work, work.index("{"))
        ks, _vs, ve = spans["settings"]
        work = _remove_member(work, ks, ve)
        report.migrated = 1
        report.notes.append("moved settings.locale/strings into the existing backOffice")
        return (work.replace("\n", newline) if newline != "\n" else work), report

    # The new member takes the place of the first old member present (appShell, then guidePages, then settings); the
    # other old members are removed.
    old_keys = [k for k in ("appShell", "guidePages", "settings") if k in spans]
    key_start, _value_start, value_end = spans[old_keys[0]]
    if obj:
        indent = _member_indent(work, key_start)
        work = work[:key_start] + '"backOffice": ' + _dump(obj, indent) + work[value_end:]
    else:
        work = _remove_member(work, key_start, value_end)
        report.notes.append("empty appShell/guidePages/settings removed (nothing to carry)")
    for other_key in old_keys[1:]:
        spans = _scan_object(work, work.index("{"))
        ks, _vs, ve = spans[other_key]
        work = _remove_member(work, ks, ve)
    report.migrated = int(has_shell) + int(has_pages) + int(has_settings)
    moved = [k for k, v in (("appShell", has_shell), ("guidePages", has_pages), ("settings", has_settings)) if v]
    report.notes.append("moved " + ", ".join(moved) + " into the root backOffice")
    return (work.replace("\n", newline) if newline != "\n" else work), report


# ------------------------------------------------------------------ driver

def iter_model_files(roots: list[Path]):
    for root in roots:
        if root.is_file():
            yield root
            continue
        for path in sorted(root.rglob("*.json")):
            if SKIP_PARTS.intersection(path.relative_to(root).parts) or path.name in SKIP_NAMES:
                continue
            yield path


def run(roots: list[Path], apply: bool, backup_dir: Path | None) -> list[Report]:
    reports: list[Report] = []
    for path in iter_model_files(roots):
        try:
            text = path.read_bytes().decode("utf-8-sig")
        except (OSError, UnicodeDecodeError):
            continue
        if '"appShell"' not in text and '"guidePages"' not in text and '"settings"' not in text:
            continue
        new_text, report = migrate_text(text, str(path))
        if not report.migrated and not report.manual:
            continue
        reports.append(report)
        if apply and report.changed:
            if backup_dir is not None:
                target = backup_dir / Path(*[p for p in path.resolve().parts if p not in ("\\", "/") and ":" not in p])
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
            raw = path.read_bytes()
            bom = b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b""
            path.write_bytes(bom + new_text.encode("utf-8"))
    return reports


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--root", action="append", required=True, type=Path, help="a directory or model file (repeatable)")
    parser.add_argument("--apply", action="store_true", help="write the changes (default is a dry run)")
    parser.add_argument("--backup-dir", type=Path, default=None, help="copy each rewritten file here first")
    args = parser.parse_args(argv)
    reports = run(args.root, args.apply, args.backup_dir)
    verb = "migrated" if args.apply else "would migrate"
    for report in reports:
        status = "MANUAL" if report.manual else verb
        print(f"{status}: {report.path}")
        for note in report.notes:
            print(f"  NOTE: {note}")
        for item in report.manual:
            print(f"  MANUAL: {item}")
    if not reports:
        print("nothing to migrate")
    return 1 if any(r.manual for r in reports) else 0


if __name__ == "__main__":
    sys.exit(main())
