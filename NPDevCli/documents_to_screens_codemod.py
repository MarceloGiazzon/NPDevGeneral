"""FORM P5-T9b codemod: the top-level ``documents[]`` key became ``screens[]`` entries of ``kind: "document"``.

One rewrite, no guessing. Every document entry becomes a screen::

    document                          screen (kind "document")
    --------------------------------  ---------------------------------------------------------
    name, concept, title              same keys
    pageSize, marginMm, metadata      same keys
    aggregate, bands, logo            same keys
    query, groupBy                    same keys (groupBy stays an array)
    subtotals, grandTotal             same keys
    kind: "list"                      report: true   (the multi-record REP-1 report)
    columns                           printColumns   (a screen's own ``columns`` is capped at 7; a printed report is not)

The screen keeps the document's name, so the render URLs ``/api/documents/{name}/render.pdf|csv`` and the
generated back office's "Download PDF/CSV" toolbar keep working unchanged.

Safe by construction:
  * dry-run by default (``--apply`` writes);
  * textual surgery, never a re-dump of the whole file, so the diff is exactly the moved block and a file's
    line endings and layout survive;
  * idempotent (a file without a top-level ``documents`` key is untouched);
  * a document whose name collides with an existing screen, whose name is not a legal screen name, whose ``kind``
    is not ``"list"`` or that is a ``$ref`` entry is reported as MANUAL and its file is left alone;
  * ``--backup-dir`` copies each file there before rewriting it (use it for trees that are not under git).

Usage::

    python NPDevCli/documents_to_screens_codemod.py --root <dir> [--root <dir> ...] [--apply] [--backup-dir <dir>]
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path

SKIP_PARTS = {"Output", "node_modules", "build", ".gradle", "archive", ".git", "tmp", "ai-scenarios", "__pycache__"}
SKIP_NAMES = {"ai-model.json"}
SCREEN_NAME = re.compile(r"^[A-Za-z][a-zA-Z0-9-]*$")


@dataclass
class Report:
    path: str
    migrated: int = 0
    notes: list[str] = field(default_factory=list)
    manual: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return self.migrated > 0 and not self.manual


def document_to_screen(document: dict, report: Report) -> dict:
    """The document screen for one documents[] entry (key order: name, kind, then the document's own order)."""
    name = document["name"]
    screen: dict = {"name": name, "kind": "document"}
    for key, value in document.items():
        if key == "name":
            continue
        if key == "kind":
            if value == "list":
                screen["report"] = True
            else:
                report.manual.append(f"document {name}: kind {value!r} is not 'list'; migrate it by hand")
        elif key == "columns":
            screen["printColumns"] = value
        else:
            screen[key] = value
    return screen


def _string_end(text: str, i: int) -> int:
    """Offset just past the string literal whose opening quote is at ``i``."""
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


def _scan_top_level(text: str) -> dict[str, tuple[int, int, int]]:
    """Top-level object members: key -> (offset of the key's opening quote, value start, value end exclusive)."""
    spans: dict[str, tuple[int, int, int]] = {}
    i = text.index("{") + 1
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


def _indented(block: str, indent: str, newline: str) -> str:
    return newline.join(indent + line for line in block.split("\n"))


def migrate_text(text: str, path: str = "<memory>") -> tuple[str, Report]:
    """Return the migrated text (unchanged when there is nothing to do or a MANUAL case exists) and a report."""
    report = Report(path)
    try:
        data = json.loads(text)
    except ValueError:
        return text, report
    if not isinstance(data, dict) or not isinstance(data.get("documents"), list):
        return text, report
    documents = data["documents"]
    if not documents or not any(isinstance(d, dict) for d in documents):
        return text, report  # an empty list or a list of plain strings is not a model's documents[]
    for entry in documents:
        if not isinstance(entry, dict) or not entry.get("name") or not entry.get("concept"):
            report.manual.append("documents has an entry that is not {name, concept, ...} (a $ref?); migrate it by hand")
            return text, report

    existing = {str(s.get("name", "")).lower() for s in data.get("screens", []) if isinstance(s, dict)}
    screens: list[dict] = []
    for document in documents:
        name = document["name"]
        if not SCREEN_NAME.match(name):
            report.manual.append(f"document {name}: not a legal screen name (letters, digits, '-', starting with a letter); rename it")
        if name.lower() in existing:
            report.manual.append(f"document {name}: a screen with that name already exists; rename one of them")
        existing.add(name.lower())
        screens.append(document_to_screen(document, report))
    report.migrated = len(screens)
    if report.manual:
        return text, report

    newline = "\r\n" if "\r\n" in text else "\n"
    work = text.replace("\r\n", "\n")
    spans = _scan_top_level(work)
    if "documents" not in spans:
        report.manual.append("could not locate the top-level documents member textually; migrate it by hand")
        report.migrated = 0
        return text, report
    key_start, value_start, value_end = spans["documents"]
    line_start = _line_start(work, key_start)
    member_indent = work[line_start:key_start]
    if member_indent.strip():
        member_indent = "  "  # the key shares a line with other content: fall back to the common 2-space layout
    item_indent = member_indent + "  "
    rendered = ",\n".join(_indented(json.dumps(s, indent=2, ensure_ascii=False), item_indent, "\n") for s in screens)

    if "screens" in spans:
        # Remove the documents member first (it may sit before or after screens), then re-scan.
        work = _remove_member(work, key_start, value_end)
        spans = _scan_top_level(work)
        _, s_value_start, s_value_end = spans["screens"]
        close = s_value_end - 1  # the ']'
        before = work[s_value_start:close].rstrip()
        if before.endswith("["):
            insertion = "\n" + rendered + "\n" + member_indent
            work = work[:close].rstrip() + insertion + work[close:]
        else:
            work = work[:close].rstrip() + ",\n" + rendered + "\n" + member_indent + work[close:]
    else:
        screens_block = '"screens": [\n' + rendered + "\n" + member_indent + "]"
        work = work[:key_start] + screens_block + work[value_end:]
    return work.replace("\n", newline) if newline != "\n" else work, report


def _remove_member(work: str, key_start: int, value_end: int) -> str:
    """Delete one top-level member (the whole line(s) it occupies) and fix the surrounding comma."""
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
    # last member: drop the comma that ended the previous one
    if end < len(work) and work[end] == "\n":
        end += 1
    head = work[:start].rstrip()
    if head.endswith(","):
        head = head[:-1]
    return head + "\n" + work[end:]


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
        if '"documents"' not in text:
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
        print(f"{status}: {report.path} ({report.migrated} document(s))")
        for note in report.notes:
            print(f"  NOTE: {note}")
        for item in report.manual:
            print(f"  MANUAL: {item}")
    if not reports:
        print("nothing to migrate")
    return 1 if any(r.manual for r in reports) else 0


if __name__ == "__main__":
    sys.exit(main())
