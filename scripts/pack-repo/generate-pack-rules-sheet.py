#!/usr/bin/env python3
"""DOM-0: one reviewable page of business rules, generated from a pack.

A domain pack (inventory, sales, purchasing, ...) is written by an engineer but has to be
signed off by someone who knows the business. This script renders `NPDevContract/packs/<id>/pack.json`
as a single self-contained HTML page -- no JSON, no expression syntax a reviewer has to decode:

  * what each concept holds (fields, which are required / unique, allowed values, bounds, defaults);
  * lifecycles as "from -> to via action" tables;
  * invariants (the rules that refuse a bad record), with the expression beside a plain restatement
    of the names it uses;
  * who may read / write each concept (logical role tokens shown as role names);
  * the derived queries (what is filtered, grouped, summed);
  * flows and their human tasks;
  * "Decisions and gaps": the pack's own `metadata.reviewNotes` -- what the pack deliberately does NOT
    enforce. A rules sheet that hid its gaps would defeat the sign-off.

Everything is read from the pack, so the sheet cannot drift from it. Output defaults to
`<Build root>/reports/pack-rules-sheets/<pack>.html` (never inside the repo).

    python scripts/pack-repo/generate-pack-rules-sheet.py inventory
    python scripts/pack-repo/generate-pack-rules-sheet.py path/to/pack.json --out some/dir/sheet.html
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKS_DIR = REPO_ROOT / "NPDevContract" / "packs"
DEFAULT_OUT_DIR = REPO_ROOT.parent / "Build" / "reports" / "pack-rules-sheets"

ROLE_TOKEN = re.compile(r"role\('([^']+)'\)")


def esc(value) -> str:
    return html.escape("" if value is None else str(value))


def label_of(node: dict, fallback: str) -> str:
    ui = node.get("ui") or {}
    label = ui.get("label") or node.get("label") or fallback
    return label if isinstance(label, str) else fallback


def roles_in(expression: str) -> list[str]:
    """Role names an access predicate admits: logical tokens and literal role names, ADMIN last."""
    found = ROLE_TOKEN.findall(expression or "")
    for literal in re.findall(r"roles\.contains\('([^']+)'\)", expression or ""):
        if literal not in found:
            found.append(literal)
    return sorted(found, key=lambda r: (r == "ADMIN", r))


def who(expression: str | None) -> str:
    if not expression:
        return "anyone signed in"
    if expression.strip() in ("true", "$user != null"):
        return "anyone signed in"
    roles = roles_in(expression)
    if not roles:
        return f"<code>{esc(expression)}</code>"
    return ", ".join(f"<b>{esc(r)}</b>" for r in roles)


def constraints(field: dict) -> str:
    parts = []
    if field.get("required"):
        parts.append("required")
    if field.get("unique"):
        parts.append("unique")
    if "maxLength" in field:
        parts.append(f"up to {field['maxLength']} characters")
    if "min" in field or "max" in field:
        low, high = field.get("min"), field.get("max")
        parts.append("between " + (str(low) if low is not None else "-∞") + " and " + (str(high) if high is not None else "∞"))
    if "precision" in field:
        parts.append(f"{field['precision']} digits, {field.get('scale', 0)} after the point")
    if "default" in field:
        parts.append(f"default {field['default']}")
    if "defaultExpression" in field:
        parts.append(f"default {field['defaultExpression']}")
    ref = field.get("reference") or field.get("ref")
    if isinstance(ref, dict):
        parts.append(f"points at a {ref.get('target')}")
    elif isinstance(ref, str):
        parts.append(f"points at a {ref}")
    return ", ".join(parts)


def field_type(field: dict) -> str:
    values = field.get("enumValues") or field.get("values")
    if field.get("type") == "enum" and values:
        names = [v if isinstance(v, str) else v.get("value", "") for v in values]
        return "one of: " + ", ".join(names)
    return str(field.get("type", ""))


def concept_section(concept: dict) -> str:
    name = concept["name"]
    out = [f"<section><h3>{esc(label_of(concept, name))} <small>({esc(name)})</small></h3>"]
    out.append("<table><tr><th>Field</th><th>Kind</th><th>Rules</th></tr>")
    for f in concept.get("fields", []):
        if f.get("id"):
            continue
        out.append(f"<tr><td>{esc(label_of(f, f['name']))}<br><small>{esc(f['name'])}</small></td>"
                   f"<td>{esc(field_type(f))}</td><td>{esc(constraints(f))}</td></tr>")
    out.append("</table>")
    for index in concept.get("indexes", []):
        if index.get("unique"):
            out.append(f"<p class='rule'>Unique together: <b>{esc(', '.join(index['fields']))}</b> "
                       f"(no two records share this combination).</p>")
    life = concept.get("lifecycle")
    if life:
        states = {s["value"]: s for s in life.get("states", [])}
        out.append("<h4>Lifecycle</h4><table><tr><th>From</th><th>To</th><th>Action</th><th>Condition</th></tr>")
        for t in life.get("transitions", []):
            action = t.get("actionLabel") or t.get("event") or ""
            if isinstance(action, dict):
                action = next(iter(action.values()), "")
            out.append(f"<tr><td>{esc(label_of(states.get(t['from'], {}), t['from']))}</td>"
                       f"<td>{esc(label_of(states.get(t['to'], {}), t['to']))}</td><td>{esc(action)}</td>"
                       f"<td>{esc(t.get('guard', ''))}</td></tr>")
        out.append("</table>")
        terminal = [s["value"] for s in life.get("states", []) if s.get("terminal")]
        if terminal:
            out.append(f"<p class='rule'>Final states (no way out): <b>{esc(', '.join(terminal))}</b>.</p>")
    invariants = concept.get("invariants", [])
    if invariants:
        out.append("<h4>Rules that refuse a bad record</h4><ul>")
        for inv in invariants:
            expr = inv.get("expr") or inv.get("expression") or inv.get("rule")
            if inv.get("type") == "unique":
                out.append(f"<li><b>{esc(inv.get('name', 'unique'))}</b>: no two records may have the same "
                           f"{esc(' + '.join(inv.get('fields', [])))}.</li>")
            else:
                out.append(f"<li><b>{esc(inv.get('name', 'rule'))}</b>: the record must satisfy "
                           f"<code>{esc(expr)}</code></li>")
        out.append("</ul>")
    access = concept.get("access") or {}
    out.append(f"<p class='access'>May read: {who(access.get('read'))}. May change: {who(access.get('write'))}.</p>")
    out.append("</section>")
    return "\n".join(out)


def query_section(queries: list[dict]) -> str:
    if not queries:
        return ""
    rows = []
    for q in queries:
        what = [f"from <b>{esc(q.get('concept'))}</b>"]
        if q.get("where"):
            what.append(f"only where <code>{esc(q['where'])}</code>")
        if q.get("groupBy"):
            keys = [g if isinstance(g, str) else g.get("field", "") for g in q["groupBy"]]
            what.append("one row per " + esc(" + ".join(keys)))
        for a in q.get("aggregates", []):
            what.append(f"<i>{esc(a['name'])}</i> = {esc(a['fn'])}{'(' + esc(a['field']) + ')' if a.get('field') else ''}")
        for l in q.get("lookups", []):
            extra = f" where <code>{esc(l['where'])}</code>" if l.get("where") else ""
            what.append(f"<i>{esc(l['name'])}</i> = {esc(l['fn'])}({esc(l.get('field', ''))}) of {esc(l['concept'])}{extra}")
        for c in q.get("computed", []):
            what.append(f"<i>{esc(c['name'])}</i> = <code>{esc(c['expression'])}</code>")
        rows.append(f"<tr><td><b>{esc(q['name'])}</b></td><td>{'; '.join(what)}</td></tr>")
    return "<h2>Derived views</h2><table><tr><th>Query</th><th>What it computes</th></tr>" + "".join(rows) + "</table>"


def flow_section(flows: list[dict]) -> str:
    if not flows:
        return ""
    out = ["<h2>Flows</h2>"]
    for flow in flows:
        out.append(f"<h3>{esc(flow['name'])}</h3><ol>")
        for step in flow.get("steps", []):
            line = f"{esc(step.get('name'))} <small>({esc(step.get('type'))})</small>"
            assignee = (step.get("assignee") or {}).get("roles")
            if assignee:
                line += " &mdash; assigned to " + ", ".join(f"<b>{esc(r)}</b>" for r in sum((roles_in(a) or [a] for a in assignee), []))
            out.append(f"<li>{line}</li>")
        out.append("</ol>")
    return "".join(out)


def notes_section(pack: dict) -> str:
    notes = (pack.get("metadata") or {}).get("reviewNotes") or []
    body = "".join(f"<li>{esc(n)}</li>" for n in notes) or "<li>The pack declares no review notes.</li>"
    return f"<h2>Decisions and gaps for the reviewer</h2><ul class='gaps'>{body}</ul>"


STYLE = """
:root{--bg:#fff;--fg:#1d2733;--muted:#5b6b7c;--line:#d5dde6;--accent:#0b5cad;--warn:#fff4d6}
@media (prefers-color-scheme:dark){:root{--bg:#14191f;--fg:#e6ebf0;--muted:#9aa8b6;--line:#2d3945;--accent:#6cb0ff;--warn:#3a3217}}
body{background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif;max-width:960px;margin:0 auto;padding:24px 16px}
h1{margin:0 0 4px}h2{margin-top:36px;border-bottom:2px solid var(--line);padding-bottom:4px}h3{margin-bottom:4px}h4{margin:14px 0 4px}
small{color:var(--muted)}table{border-collapse:collapse;width:100%;margin:6px 0}th,td{border:1px solid var(--line);padding:5px 8px;text-align:left;vertical-align:top}
th{background:rgba(127,127,127,.12)}code{background:rgba(127,127,127,.15);padding:0 4px;border-radius:3px;word-break:break-word}
.rule{margin:6px 0}.access{margin:10px 0;padding:6px 10px;border-left:4px solid var(--accent)}
.gaps{background:var(--warn);padding:10px 10px 10px 28px;border-radius:6px}
section{margin-bottom:22px}@media print{body{max-width:none}}
"""


def render(pack: dict) -> str:
    title = f"{pack['pack']} pack — business rules"
    parts = [f"<h1>{esc(title)}</h1>",
             f"<p><small>version {esc(pack.get('version'))} · namespace {esc(pack.get('namespace'))} · generated from pack.json; "
             f"edit the pack, not this page.</small></p>",
             f"<p>{esc((pack.get('metadata') or {}).get('summary') or pack.get('description'))}</p>", "<h2>What it holds</h2>"]
    parts += [concept_section(c) for c in pack.get("concepts", [])]
    parts += [query_section(pack.get("queries", [])), flow_section(pack.get("flows", [])), notes_section(pack)]
    return ("<!doctype html><html lang='en'><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            f"<title>{esc(pack['pack'])} rules sheet</title><style>{STYLE}</style></head><body>" + "".join(parts) + "</body></html>")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("pack", help="A built-in pack id (NPDevContract/packs/<id>) or a path to a pack.json.")
    ap.add_argument("--out", help="Output .html file (default: <Build root>/reports/pack-rules-sheets/<pack>.html).")
    args = ap.parse_args()
    path = Path(args.pack)
    if not path.is_file():
        path = PACKS_DIR / args.pack / "pack.json"
    if not path.is_file():
        print(f"generate-pack-rules-sheet: no pack at {args.pack} or {path}", file=sys.stderr)
        return 2
    pack = json.loads(path.read_text(encoding="utf-8-sig"))
    out = Path(args.out) if args.out else DEFAULT_OUT_DIR / f"{pack['pack']}.html"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(render(pack).encode("utf-8"))
    print(f"generate-pack-rules-sheet: wrote {out.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
