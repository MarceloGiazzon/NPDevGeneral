"""FORM P5-T9e codemod: the top-level ``aggregates[]`` key became ``concepts[].composition``.

An aggregate has no identity apart from its root concept, so each ``aggregates[]`` entry moves onto its root concept::

    aggregates: [ {name: "OrderAggregate", root: "Order", collections, invariants, balances, onValidate, onCommit, metadata} ]
        ->  concepts: [ {name: "Order", ..., composition: {collections, invariants, balances, onValidate, onCommit, metadata}} ]

The aggregate's NAME becomes the concept's name, so every ``"aggregate": "OrderAggregate"`` reference (autoPanels, screens,
agentAccess exposures, gadgets) is rewritten to ``"aggregate": "Order"`` and the runtime address becomes
``/api/v1/runtime/aggregate/Order``. Works on a model, a pack, a context or a fragment (any JSON object with a top-level
``aggregates`` array whose entries carry ``root``).

Safe by construction:
  * dry-run by default (``--apply`` writes);
  * textual surgery, never a re-dump of the whole file (line endings and layout survive);
  * a root concept that is not declared inline in the SAME file (a ``$ref`` entry, another file), two aggregates on one root,
    or an existing ``composition`` is reported MANUAL and the file is left untouched;
  * idempotent (a file without a top-level ``aggregates`` array is untouched);
  * ``--backup-dir`` copies each file there before rewriting it (use it for trees that are not under git).

Usage::

    python NPDevCli/aggregates_to_composition_codemod.py --root <dir-or-file> [--root ...] [--apply] [--backup-dir <dir>]
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from appshell_guidepages_to_backoffice_codemod import (  # noqa: E402  (shared textual-JSON helpers)
    SKIP_NAMES,
    SKIP_PARTS,
    _dump,
    _line_start,
    _member_indent,
    _remove_member,
    _scan_object,
    _string_end,
    _value_end,
)


@dataclass
class Report:
    path: str
    migrated: int = 0
    notes: list[str] = field(default_factory=list)
    manual: list[str] = field(default_factory=list)
    side_edits: dict = field(default_factory=dict)  # Path -> new text of a referenced concept file

    @property
    def changed(self) -> bool:
        return self.migrated > 0 and not self.manual


def _scan_array(text: str, start: int) -> list[tuple[int, int]]:
    """Element spans (start, end) of the array whose ``[`` is at ``start``."""
    spans: list[tuple[int, int]] = []
    i = start + 1
    n = len(text)
    while True:
        while i < n and text[i] in " \t\r\n,":
            i += 1
        if i >= n or text[i] == "]":
            return spans
        end = _value_end(text, i)
        spans.append((i, end))
        i = end


def _insert_member(work: str, obj_start: int, obj_end: int, key: str, value) -> str:
    """Append ``"key": value`` as the last member of the object spanning [obj_start, obj_end)."""
    members = _scan_object(work, obj_start)
    first_key_start = min(s for s, _v, _e in members.values())
    indent = _member_indent(work, first_key_start)
    last_end = max(e for _s, _v, e in members.values())
    insertion = ",\n" + indent + json.dumps(key) + ": " + _dump(value, indent)
    return work[:last_end] + insertion + work[last_end:]


def migrate_text(text: str, path: str = "<memory>", base_dir: Path | None = None) -> tuple[str, Report]:
    report = Report(path)
    try:
        data = json.loads(text)
    except ValueError:
        return text, report
    if not isinstance(data, dict):
        return text, report
    aggregates = data.get("aggregates")
    if not isinstance(aggregates, list) or not aggregates or not all(isinstance(a, dict) and "root" in a for a in aggregates):
        return text, report

    concepts = data.get("concepts")
    declared = {c.get("name"): i for i, c in enumerate(concepts) if isinstance(c, dict) and "name" in c} if isinstance(concepts, list) else {}
    # A concept declared as {"$ref": "concepts/X.json"} lives in that file (resolved next to this one).
    ref_files: dict[str, Path] = {}
    if base_dir is not None and isinstance(concepts, list):
        for c in concepts:
            if isinstance(c, dict) and set(c) == {"$ref"} and isinstance(c["$ref"], str):
                target = (base_dir / c["$ref"]).resolve()
                try:
                    inner = json.loads(target.read_bytes().decode("utf-8-sig"))
                except (OSError, ValueError):
                    continue
                if isinstance(inner, dict) and isinstance(inner.get("name"), str):
                    ref_files[inner["name"]] = target
    roots = [a.get("root") for a in aggregates]
    if len(set(roots)) != len(roots):
        report.manual.append("two aggregates share one root concept; a concept has ONE composition -- merge them by hand")
        return text, report
    renames: dict[str, str] = {}
    for aggregate in aggregates:
        root = aggregate.get("root")
        name = aggregate.get("name")
        if root not in declared and root not in ref_files:
            report.manual.append(f"aggregate {name!r}: root concept {root!r} is not declared inline in this file -- move it by hand")
            return text, report
        if root in declared and "composition" in concepts[declared[root]]:
            report.manual.append(f"concept {root!r} already has a composition")
            return text, report
        if name != root:
            renames[name] = root

    newline = "\r\n" if "\r\n" in text else "\n"
    work = text.replace("\r\n", "\n")
    top = _scan_object(work, work.index("{"))
    concepts_start = top["concepts"][1]
    element_spans = _scan_array(work, concepts_start)

    # Insert the compositions from the LAST concept to the first so earlier offsets stay valid.
    inserts = []
    for aggregate in aggregates:
        composition = {k: v for k, v in aggregate.items() if k not in ("name", "root")}
        if aggregate["root"] in declared:
            inserts.append((declared[aggregate["root"]], composition))
            continue
        target = ref_files[aggregate["root"]]
        raw = target.read_bytes()
        side_crlf = b"\r\n" in raw
        side = raw.decode("utf-8-sig").replace("\r\n", "\n")
        if "composition" in json.loads(side):
            report.manual.append(f"concept file {target} already has a composition")
            return text, report
        side = _insert_member(side, side.index("{"), len(side), "composition", composition)
        report.side_edits[target] = side.replace("\n", "\r\n") if side_crlf else side
    for index, composition in sorted(inserts, key=lambda pair: -pair[0]):
        start, end = element_spans[index]
        work = _insert_member(work, start, end, "composition", composition)

    top = _scan_object(work, work.index("{"))
    key_start, _value_start, value_end = top["aggregates"]
    work = _remove_member(work, key_start, value_end)

    # Rewrite "aggregate": "<OldName>" references anywhere (autoPanels, screens, exposures, gadgets).
    count = 0
    for old, new in renames.items():
        pattern = re.compile(r'("aggregate"\s*:\s*)"' + re.escape(old) + '"')
        work, n = pattern.subn(lambda m: m.group(1) + json.dumps(new), work)
        count += n
    report.migrated = len(aggregates)
    report.notes.append(f"moved {len(aggregates)} aggregate(s) into concepts[].composition"
                        + (f"; renamed {count} reference(s): " + ", ".join(f"{o}->{n}" for o, n in renames.items()) if renames else ""))
    return (work.replace("\n", newline) if newline != "\n" else work), report


def iter_model_files(roots: list[Path]):
    for root in roots:
        if root.is_file():
            yield root
            continue
        for path in sorted(root.rglob("*.json")):
            if SKIP_PARTS.intersection(path.parts) or path.name in SKIP_NAMES:
                continue
            yield path


def run(roots: list[Path], apply: bool, backup_dir: Path | None) -> list[Report]:
    reports: list[Report] = []
    for path in iter_model_files(roots):
        try:
            text = path.read_bytes().decode("utf-8-sig")
        except (OSError, UnicodeDecodeError):
            continue
        if '"aggregates"' not in text:
            continue
        new_text, report = migrate_text(text, str(path), path.parent)
        if not report.migrated and not report.manual:
            continue
        reports.append(report)
        if apply and report.changed:
            for side_path, side_text in report.side_edits.items():
                if backup_dir is not None:
                    target = backup_dir / Path(*[p for p in side_path.resolve().parts if p not in ("\\", "/") and ":" not in p])
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(side_path, target)
                side_raw = side_path.read_bytes()
                side_bom = b"\xef\xbb\xbf" if side_raw.startswith(b"\xef\xbb\xbf") else b""
                side_path.write_bytes(side_bom + side_text.encode("utf-8"))
            if backup_dir is not None:
                target = backup_dir / Path(*[p for p in path.resolve().parts if p not in ("\\", "/") and ":" not in p])
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
            raw = path.read_bytes()
            bom = b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b""
            path.write_bytes(bom + new_text.encode("utf-8"))
    return reports


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Move top-level aggregates[] into concepts[].composition (FORM P5-T9e).")
    parser.add_argument("--root", action="append", required=True, help="a directory (searched for *.json) or one file; repeatable")
    parser.add_argument("--apply", action="store_true", help="write the changes (default: dry run)")
    parser.add_argument("--backup-dir", help="copy each file here before rewriting it")
    opts = parser.parse_args(argv)
    reports = run([Path(r) for r in opts.root], opts.apply, Path(opts.backup_dir) if opts.backup_dir else None)
    if not reports:
        print("nothing to migrate")
        return 0
    manual = 0
    for report in reports:
        if report.manual:
            manual += 1
            print(f"MANUAL: {report.path}")
            for line in report.manual:
                print(f"  MANUAL: {line}")
        else:
            print(("migrated: " if opts.apply else "would migrate: ") + report.path)
            for line in report.notes:
                print(f"  NOTE: {line}")
    return 1 if manual else 0


if __name__ == "__main__":
    sys.exit(main())
