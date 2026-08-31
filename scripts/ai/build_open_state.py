"""Render ONE human-readable digest of everything currently open.

Reads ledger/items/*.yml and ledger/boundaries/*.yml -- the two structured sources of
truth -- and writes ledger/session-state/open-state.md next to current.json, so a
session (or a person) can see every non-DONE item and every accepted boundary without
opening 398 YAML files.

The output is GENERATED and gitignored, exactly like docs/OPEN_ITEMS.md: a committed
status file goes stale, and stale status prose is what the process-document ban targets.
/prep regenerates it, so what is on disk is always current.

NOTHING may read this file with a script -- it is output for humans. The facts live in
the YAML. (scripts/quality/check-no-markdown-reads.py enforces that.)

    python scripts/ai/build_open_state.py
"""

from __future__ import annotations

import datetime as _dt
import re
import subprocess
import sys
from pathlib import Path

import yaml

# REG-144: exact arithmetic from this file's own location, never a walk looking for a
# directory NAME. npdev-build-root-resolution
REPO_ROOT = Path(__file__).resolve().parents[2]

SEVERITY_ORDER = {"P0": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, None: 4}
CLASS_BLURB = {
    "HITTABLE": "a user action reaches a real refusal, diagnostic or workflow gate",
    "POSTURAL": "a real, documented limit with no diagnostic to fire",
    "LIFTED": "closed boundaries, kept for history",
}


def _load(directory: Path) -> list[dict]:
    out = []
    for path in sorted(directory.glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            data["_path"] = path.relative_to(REPO_ROOT).as_posix()
            out.append(data)
    return out


def _flat(text: str) -> str:
    return " ".join(str(text or "").split())


def _done_when(detail: str) -> str:
    """Pull the item's own acceptance sentence out of its detail prose."""
    match = re.search(r"Done when:(.+?)(?:\n\n|\Z)", detail or "", re.S)
    return _flat(match.group(1)) if match else ""


def _latest_note(detail: str) -> str:
    """The last dated progress paragraph, which is the item's real current state."""
    paragraphs = [p for p in (detail or "").split("\n\n") if re.match(r"\s*\d{4}-\d{2}-\d{2}", p)]
    return _flat(paragraphs[-1]) if paragraphs else ""


def _clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _head() -> str:
    try:
        proc = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "log", "-1", "--format=%h %s"],
            capture_output=True, text=True, timeout=10, check=False,
        )
        return proc.stdout.strip() if proc.returncode == 0 else "(unknown)"
    except (OSError, subprocess.SubprocessError):
        return "(unknown)"


def render(items: list[dict], boundaries: list[dict]) -> str:
    open_items = [i for i in items if str(i.get("status", "")).upper() != "DONE"]
    open_items.sort(key=lambda i: (SEVERITY_ORDER.get(i.get("severity"), 4), str(i.get("id"))))

    lines: list[str] = []
    add = lines.append
    stamp = _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    add("# NPDev open state")
    add("")
    add(f"Generated {stamp} at `{_head()}` by `scripts/ai/build_open_state.py`.")
    add("**Do not hand-edit** -- the truth is `ledger/items/*.yml` and `ledger/boundaries/*.yml`.")
    add("")

    add(f"## Open items -- {len(open_items)} of {len(items)} not DONE")
    add("")
    if not open_items:
        add("Nothing open.")
        add("")
    for item in open_items:
        surface = _flat(item.get("surface", "")) or "-"
        add(f"### {item.get('id')} · {item.get('severity')} · {item.get('type')}")
        add("")
        add(f"**{_flat(item.get('title'))}**")
        add("")
        add(f"- opened `{item.get('opened')}` · status `{item.get('status')}` · surface `{surface}`")
        for key, label in (("_done_when", "Done when"), ("_latest", "Latest")):
            value = item.get(key)
            if value:
                add(f"- **{label}:** {value}")
        files = item.get("files") or []
        if files:
            add("- files: " + ", ".join(f"`{f}`" for f in files))
        add(f"- full text: `{item['_path']}`")
        add("")

    add(f"## Accepted boundaries -- {len(boundaries)}")
    add("")
    add("Deliberate limits, not defects. Prose companion: `docs/ACCEPTED_BOUNDARIES.md`.")
    add("")
    for name in ("HITTABLE", "POSTURAL", "LIFTED"):
        group = [b for b in boundaries if b.get("classification") == name]
        group.sort(key=lambda b: int(re.sub(r"\D", "", str(b.get("id"))) or 0))
        add(f"### {name} -- {len(group)}")
        add("")
        add(f"_{CLASS_BLURB[name]}_")
        add("")
        add("| id | boundary | code linked |")
        add("|---|---|---|")
        for boundary in group:
            linked = "yes" if boundary.get("codeLinked") else "no"
            add(f"| {boundary.get('id')} | {_clip(_flat(boundary.get('title')), 150)} | {linked} |")
        add("")
    return "\n".join(lines) + "\n"


def main() -> int:
    items = _load(REPO_ROOT / "ledger" / "items")
    boundaries = _load(REPO_ROOT / "ledger" / "boundaries")
    for item in items:
        detail = item.get("detail", "")
        item["_done_when"] = _clip(_done_when(detail), 400)
        item["_latest"] = _clip(_latest_note(detail), 400)

    out_path = REPO_ROOT / "ledger" / "session-state" / "open-state.md"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(render(items, boundaries).encode("utf-8"))

    open_count = sum(1 for i in items if str(i.get("status", "")).upper() != "DONE")
    print(f"open-state: {open_count} open item(s), {len(boundaries)} boundaries -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
