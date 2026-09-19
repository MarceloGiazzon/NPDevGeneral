#!/usr/bin/env python3
r"""S13 (NPDEV_MEGA_ROADMAP.md): capability documentation with tooling-captured screenshots.

One command regenerates the three capability docs (depth-2 workbench, Google sign-in/link, packs)
against a RUNNING app: walk the declared route list, capture each screen, and render a self-contained
HTML doc per capability. Reusable by construction -- point it at any generated app whose routes are
in the capability definitions below.

    python capture-capability-docs.py --base-url http://localhost:8084
    python capture-capability-docs.py --base-url http://localhost:8084 --out <dir> --no-browser

Captures through Playwright when it is importable; when it is not (or --no-browser), the doc is
still emitted with an honest "not captured" placeholder and a note naming the exact command that
would capture it -- the tooling is the deliverable, the screenshot is its evidence, and a machine
with no browser must never fake either.

Screenshots land in <out>/shots/<capability>-<n>.png; the HTML embeds them as data: URIs so each
doc opens standalone.
"""
from __future__ import annotations

import argparse
import base64
import sys
from pathlib import Path

CAPABILITIES = [
    {
        "id": "depth2-workbench",
        "title": "The depth-2 origin/destination workbench",
        "definition": (
            "Session 1's declarative depth-2 aggregate panel: nested collections render, the "
            "cross-collection balancing invariant validates on 'recalculate balances', per-row "
            "maximums come from a queried value, and after commit the screen goes read-only. "
            "Shown here against the generated sample's order workbench."
        ),
        "routes": ["/widget-orders"],
    },
    {
        "id": "google-signin",
        "title": "Google sign-in and account linking",
        "definition": (
            "Session 3's identity-provider flow: the jwt-mode login/signup screen offers "
            "'Continue with Google', the code exchange maps the verified Google identity onto an "
            "identity::User, and tokenVersion revocation still kills Google-linked sessions."
        ),
        "routes": ["/login.html"],
    },
    {
        "id": "packs",
        "title": "Packs: model, consume, what you get",
        "definition": (
            "Sessions 4-5: a pack is a versioned, named domain module. The tracing pack gives an "
            "app a TraceEntry concept and a viewer; the identity pack gives User/Role. Consumed by "
            "declaring packs[].from with a versioned coordinate (git or OCI)."
        ),
        "routes": ["/trace-entries"],
    },
]


def try_import_playwright():
    try:
        import playwright  # noqa: F401
        return True
    except ImportError:
        return False


def capture_screens(base_url: str, routes: list[str], out_dir: Path, use_browser: bool) -> list[tuple[str, bool, str]]:
    """Captures one screenshot per route. Returns [(route, captured, html_note)]. Never raises:
    a route that 404s or times out is recorded as not captured with the HTTP status in the note."""
    if not use_browser:
        return [(route, False, "no browser requested (--no-browser); re-run without it on a "
                               "machine with Playwright to capture") for route in routes]
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return [(route, False, "playwright is not installed on this machine; install it "
                               "('pip install playwright && playwright install chromium') and "
                               "re-run") for route in routes]

    out_dir.mkdir(parents=True, exist_ok=True)
    results = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 800})
        for index, route in enumerate(routes):
            url = base_url.rstrip("/") + ("/" + route.lstrip("/") if route != "/" else "/")
            try:
                response = page.goto(url, wait_until="networkidle", timeout=20_000)
                status = response.status if response is not None else 0
                if status and status >= 400:
                    results.append((route, False, f"HTTP {status} for {url}"))
                    continue
                shot = out_dir / f"shot-{index}.png"
                page.screenshot(path=str(shot), full_page=True)
                results.append((route, True, f"HTTP {status}, {shot.name}"))
            except Exception as error:
                results.append((route, False, f"{type(error).__name__}: {error}"))
        browser.close()
    return results


def render_doc(capability: dict, base_url: str, results: list[tuple[str, bool, str]], shots_dir: Path) -> str:
    blocks = []
    for (route, captured, note), index in zip(results, range(len(results))):
        if captured:
            data_uri = "data:image/png;base64," + base64.b64encode(
                (shots_dir / f"shot-{index}.png").read_bytes()).decode("ascii")
            image = f'<img src="{data_uri}" alt="screenshot of {route}" style="max-width:100%;border:1px solid #ccc;border-radius:6px" />'
            badge = '<span style="color:#1f7a33;font-weight:700">captured</span>'
        else:
            image = ('<div style="background:#f4f4f4;border:1px dashed #bbb;border-radius:6px;'
                     'padding:24px;text-align:center;color:#777;font-family:monospace">'
                     f'screenshot not captured<br><small>{note}</small></div>')
            badge = '<span style="color:#c0392b;font-weight:700">not captured</span>'
        blocks.append(
            f'<h3>{route} <small>{badge}</small></h3>{image}')
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8" />
<title>{capability['title']}</title></head>
<body style="font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:2rem;max-width:64rem;line-height:1.6">
<h1>{capability['title']}</h1>
<p style="color:#444">{capability['definition']}</p>
<p style="color:#777;font-size:13px">Captured against <code>{base_url}</code> by
<code>capture-capability-docs.py</code> — re-run the same command after a UI change to regenerate.</p>
{''.join(blocks)}
</body></html>
"""


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--out", default=None, help="output directory (default: scripts/reports/out/capability-docs)")
    ap.add_argument("--no-browser", action="store_true", help="never launch a browser; emit placeholder docs")
    args = ap.parse_args(argv[1:])

    import json as _json
    repo_root = Path(__file__).resolve().parents[2]
    out = Path(args.out).expanduser().resolve() if args.out else \
        repo_root / "scripts" / "reports" / "out" / "capability-docs"
    shots = out / "shots"
    shots.mkdir(parents=True, exist_ok=True)

    use_browser = not args.no_browser
    if use_browser and not try_import_playwright():
        print("Note: Playwright is not installed -- docs will carry 'not captured' placeholders. "
              "Install it to capture real screenshots.", file=sys.stderr)

    manifest = []
    for capability in CAPABILITIES:
        results = capture_screens(args.base_url, capability["routes"], shots, use_browser)
        doc = render_doc(capability, args.base_url, results, shots)
        target = out / f"{capability['id']}.html"
        target.write_text(doc, encoding="utf-8")
        captured = sum(1 for _, ok, _ in results if ok)
        manifest.append({
            "id": capability["id"], "title": capability["title"], "file": target.name,
            "routes": len(capability["routes"]), "captured": captured,
        })
        print(f"  {capability['id']}: {captured}/{len(capability['routes'])} screenshot(s) -> {target.name}")
    (out / "manifest.json").write_text(_json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Capability docs written to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))