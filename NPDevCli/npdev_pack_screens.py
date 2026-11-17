"""Rung 5 of the pack workshop ladder: screenshots of the consumer app, one pass per identity (P6-T4).

`npdev pack workshop test <pack> --screens` boots the same throwaway consumer the probes use and, while it is up,
asks the ScrapForAI engine's Playwright for one screenshot of the home page and of every section each identity's
side navigation offers -- so the gallery shows what each ROLE can reach, which is what a pack author and the owner
need to judge a pack. It writes `<run>/screens/*.png`, `manifest.json` and a self-contained `gallery.html`.

Like the probe rung it can only be SKIPPED, never the cause of a rejection: no engine, no node, a crashed browser
or a timeout is the machine's problem, not the pack's (the validator and the probes already judged the pack).
The identities' keys are handed to the browser through a temp file that is deleted right after; they are never
written into the run folder.
"""
from __future__ import annotations

import html
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

SHOOT_SCRIPT = Path(__file__).resolve().parent / "pack_screens_shoot.cjs"
SCREENS_BUDGET_SECONDS = 90   # on top of the boot budget; screens are opt-in
DEFAULT_USER = {"name": "tester", "role": "USER"}


class ScreensUnavailable(Exception):
    """The capture cannot run here (no engine, no node, crash, timeout): the step is skipped, never failed."""


def default_users(role_tokens: list[str]) -> list[dict]:
    """Identities for a pack with no probes: one per role token the pack names (bound to the upper-cased token, as the
    scratch model binds them), or one plain user when the pack names no role."""
    users = [{"name": token.lower().replace("_", "-") + "-user", "role": token.upper()} for token in role_tokens]
    return users or [dict(DEFAULT_USER)]


def find_engine_root() -> Path:
    """Where the engine's files (and so its Playwright) are. A RUNNING engine answers without saying where it lives, so
    when that is all we learn, look again by files only (port 0 never answers)."""
    import npdev_monitor

    workspace = Path(__file__).resolve().parents[1]   # the repo root, found by position, never by its folder name
    found = npdev_monitor.detect_engine(workspace_root=workspace)
    if found.get("found") and not found.get("root"):
        found = npdev_monitor.detect_engine(port=0, workspace_root=workspace)
    root = found.get("root")
    if not found.get("found") or not root:
        raise ScreensUnavailable("the ScrapForAI engine was not found where its files can be read "
                                 "(set SCRAPFORAI_ROOT to the folder that holds its node_modules)" if found.get("found")
                                 else "the ScrapForAI engine is not installed on this machine (see `npdev monitor engine`)")
    return Path(root)


def run_shoot(engine_root: Path, base_url: str, out_dir: Path, users: list[dict], keys: dict[str, str],
              budget: float = SCREENS_BUDGET_SECONDS) -> None:
    """Run the node script. Keys travel in a temp file that is removed in every case."""
    node = shutil.which("node")
    if node is None:
        raise ScreensUnavailable("node is not on PATH")
    if not SHOOT_SCRIPT.is_file():
        raise ScreensUnavailable(f"{SHOOT_SCRIPT.name} is missing from the CLI folder")
    payload = [{"name": u["name"], "role": u.get("role", ""), "key": keys[u["name"]]} for u in users]
    handle, tmp = tempfile.mkstemp(prefix="npdev-screens-", suffix=".json")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            json.dump(payload, out)
        try:
            proc = subprocess.run([node, str(SHOOT_SCRIPT), str(engine_root), base_url, str(out_dir), tmp],
                                  capture_output=True, text=True, timeout=budget, cwd=str(out_dir.parent))
        except subprocess.TimeoutExpired:
            raise ScreensUnavailable(f"the capture did not finish inside {budget:.0f} s") from None
        except OSError as exc:
            raise ScreensUnavailable(f"node could not start ({type(exc).__name__})") from None
        if proc.returncode != 0:
            tail = (proc.stderr or proc.stdout or "").strip().splitlines()[-1:] or [""]
            raise ScreensUnavailable(f"the capture failed: {tail[0][:200]}")
    finally:
        Path(tmp).unlink(missing_ok=True)


def gallery_html(title: str, manifest: list[dict]) -> str:
    """One self-contained page: a section per identity, each screenshot linked relatively, console errors listed."""
    by_user: dict[str, list[dict]] = {}
    notes: dict[str, dict] = {}
    for row in manifest:
        if "file" in row:
            by_user.setdefault(row["user"], []).append(row)
        else:
            notes[row["user"]] = row
    parts = [f"<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>",
             "<style>body{font:15px system-ui;margin:2rem;max-width:1200px}h2{margin-top:2.5rem}"
             ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:1rem}"
             "figure{margin:0;border:1px solid #8884;border-radius:8px;overflow:hidden}"
             "img{width:100%;display:block}figcaption{padding:.5rem .75rem;font-size:13px}"
             ".err{color:#b00020}</style>", f"<h1>{html.escape(title)}</h1>"]
    for user, rows in by_user.items():
        role = rows[0].get("role") or ""
        note = notes.get(user, {})
        parts.append(f"<h2>{html.escape(user)} <small>role {html.escape(role)}; {note.get('navLinks', 0)} menu entries</small></h2>")
        for line in note.get("consoleErrors") or []:
            parts.append(f"<p class=err>console: {html.escape(line)}</p>")
        parts.append("<div class=grid>")
        for row in rows:
            extra = ""
            if "rows" in row and row["rows"] is not None:
                extra = f" - {row['rows']} row(s)"
            if row.get("panelVisible") is False:
                extra = " - panel not shown"
            parts.append(f"<figure><a href='{html.escape(row['file'])}'><img loading=lazy src='{html.escape(row['file'])}'></a>"
                         f"<figcaption>{html.escape(str(row['screen']))}{html.escape(extra)}</figcaption></figure>")
        parts.append("</div>")
    return "\n".join(parts) + "\n"


def capture(base_url: str, users: list[dict], keys: dict[str, str], out_dir: Path, title: str,
            shoot=None, engine_root: Path | None = None) -> dict:
    """Capture and write the gallery. `shoot(base_url, out_dir, users, keys)` is injectable for tests.
    Returns {shots, identities, gallery, manifest, consoleErrors}; raises ScreensUnavailable."""
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    started = time.monotonic()
    if shoot is None:
        root = engine_root or find_engine_root()
        shoot = lambda url, out, us, ks: run_shoot(root, url, out, us, ks)  # noqa: E731
    shoot(base_url, out_dir, users, keys)
    manifest_path = out_dir / "manifest.json"
    if not manifest_path.is_file():
        raise ScreensUnavailable("the capture finished but wrote no manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    shots = [row for row in manifest if "file" in row]
    if not shots:
        raise ScreensUnavailable("the capture finished but took no screenshot")
    (out_dir / "gallery.html").write_text(gallery_html(title, manifest), encoding="utf-8")
    errors = [f"{row['user']}: {line}" for row in manifest if "file" not in row for line in row.get("consoleErrors") or []]
    return {"shots": len(shots), "identities": len({r["user"] for r in shots}), "gallery": "screens/gallery.html",
            "manifest": "screens/manifest.json", "consoleErrors": errors, "seconds": round(time.monotonic() - started, 1)}
