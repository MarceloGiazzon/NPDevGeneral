#!/usr/bin/env python3
"""2.E ledger migration (docs/REMEDIATION_PLAN.md R-P1, complete 2026-07-29): regenerate
docs/OPEN_ITEMS.md from ledger/items/*.yml -- the single source of truth for tracked items.

Never hand-edit docs/OPEN_ITEMS.md -- it is a projection, the same discipline
knowledge/platform-status.json already uses relative to the gaps ledger
(scripts/ai/extract_platform_status.py).

MIGRATION COMPLETE (see ledger/README.md): all 64 tracked ids are migrated.
docs/NPDEV_OPEN_ITEMS_REGISTER.md is archived-in-place (kept for its `#reg-N` anchors and prose
investigation narrative, linked from every item's `legacyDetailRef`) and is no longer hand-edited
for status.

    python scripts/quality/generate_open_items.py           # writes docs/OPEN_ITEMS.md
    python scripts/quality/generate_open_items.py --check    # exit 1 if the file is stale
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

REQUIRED_FIELDS = ("id", "title", "type", "severity", "status", "opened", "source", "surface", "detail")
VALID_TYPES = {"GAP", "BUG", "PROCESS", "BOUNDARY"}
VALID_SEVERITIES = {"LOW", "MEDIUM", "HIGH", "P0", "P1", None}
VALID_STATUSES = {"OPEN", "PARTIAL", "DONE"}
VALID_VERIFICATION = {"NOT_VERIFIED", "UNIT_TESTED", "VERIFIED_LIVE", None}


def load_items(ledger_dir: Path) -> list[dict]:
    items = []
    for path in sorted((ledger_dir / "items").glob("*.yml")):
        item = yaml.safe_load(path.read_text(encoding="utf-8"))
        errors = validate_item(item, path)
        if errors:
            raise ValueError(f"{path}: " + "; ".join(errors))
        items.append(item)
    return items


def validate_item(item: dict, path: Path) -> list[str]:
    errors = []
    for field in REQUIRED_FIELDS:
        # severity is required to be PRESENT but is allowed to be null for type: BOUNDARY
        # (ledger/README.md's own schema doc) -- checked properly below instead of here, where
        # every other field's blanket None-means-missing rule still applies. REG-7/REG-8 (2026-07-29,
        # docs/REMEDIATION_PLAN.md R-P1) were the first BOUNDARY items ever migrated and the first to
        # actually exercise this path -- until then this was a latent, never-triggered bug.
        if field == "severity":
            continue
        if field not in item or item[field] in (None, ""):
            errors.append(f"missing required field '{field}'")
    if "severity" not in item:
        errors.append("missing required field 'severity'")
    if item.get("id") and item["id"] != path.stem:
        errors.append(f"id '{item.get('id')}' does not match filename '{path.stem}'")
    if item.get("type") not in VALID_TYPES:
        errors.append(f"type '{item.get('type')}' not in {sorted(VALID_TYPES)}")
    if item.get("severity") not in VALID_SEVERITIES:
        errors.append(f"severity '{item.get('severity')}' not in {sorted(s for s in VALID_SEVERITIES if s)}")
    if item.get("severity") is None and item.get("type") != "BOUNDARY":
        errors.append("severity: null is only valid for type: BOUNDARY")
    if item.get("status") not in VALID_STATUSES:
        errors.append(f"status '{item.get('status')}' not in {sorted(VALID_STATUSES)}")
    if item.get("status") == "DONE" and not item.get("closed"):
        errors.append("status: DONE requires a 'closed' date")
    if item.get("verification") not in VALID_VERIFICATION:
        errors.append(f"verification '{item.get('verification')}' not in {sorted(v for v in VALID_VERIFICATION if v)}")
    return errors


def render(items: list[dict]) -> str:
    lines = [
        "# Open Items — generated",
        "",
        "> **GENERATED FILE — do not hand-edit.** Source: `ledger/items/*.yml`, the authoritative",
        "> record for every tracked id. Regenerate with `python scripts/quality/generate_open_items.py`.",
        "> See `ledger/README.md` for the schema. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` is archived-in-",
        "> place (its prose investigation narrative, linked from each item's `legacyDetailRef`) and is",
        "> no longer hand-edited for status.",
        "",
    ]

    open_items = [i for i in items if i["status"] != "DONE"]
    done_items = [i for i in items if i["status"] == "DONE"]

    lines.append(f"**{len(items)} item(s) migrated: {len(open_items)} open/partial, {len(done_items)} done.**")
    lines.append("")

    lines.append("| ID | Title | Type | Sev | Status | Opened |")
    lines.append("|---|---|---|---|---|---|")
    for item in sorted(items, key=lambda i: i["id"]):
        sev = item["severity"] or "—"
        lines.append(
            f"| {item['id']} | {item['title']} | {item['type']} | {sev} | "
            f"{item['status']} | {item['opened']} |"
        )
    lines.append("")

    lines.append("## Detail")
    lines.append("")
    for item in sorted(items, key=lambda i: i["id"]):
        lines.append(f"### {item['id']} — {item['title']}")
        lines.append("")
        sev = item["severity"] or "—"
        lines.append(f"**Type:** {item['type']} · **Severity:** {sev} · **Status:** {item['status']}"
                     + (f" ({item['closed']})" if item.get("closed") else ""))
        if item.get("verification"):
            lines.append(f"**Verification:** {item['verification']}")
        lines.append(f"**Source:** {item['source']}")
        lines.append(f"**Surface:** `{item['surface']}`")
        if item.get("files"):
            lines.append("**Files:**")
            for f in item["files"]:
                lines.append(f"- `{f}`")
        lines.append("")
        lines.append(item["detail"].strip())
        lines.append("")
        if item.get("legacyDetailRef"):
            lines.append(f"*Full historical narrative:* `{item['legacyDetailRef']}`")
            lines.append("")

    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true", help="exit 1 if docs/OPEN_ITEMS.md is stale")
    args = ap.parse_args(argv[1:])

    root = Path(args.root).resolve()
    ledger_dir = root / "ledger"
    out_path = root / "docs" / "OPEN_ITEMS.md"

    try:
        items = load_items(ledger_dir)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    rendered = render(items)

    if args.check:
        current = out_path.read_text(encoding="utf-8") if out_path.exists() else ""
        if current != rendered:
            print("docs/OPEN_ITEMS.md is STALE -- run without --check to regenerate.", file=sys.stderr)
            return 1
        print(f"OK: docs/OPEN_ITEMS.md is current ({len(items)} item(s)).")
        return 0

    out_path.write_text(rendered, encoding="utf-8")
    print(f"wrote {out_path} ({len(items)} item(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
