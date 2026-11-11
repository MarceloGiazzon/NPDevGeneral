"""The readable face of a pack: one HTML rules sheet for a reviewer, and a plain-words preview for a person.

Two things are made from the same `pack.json`, so neither can drift from it:

  * `render(pack)` -- one self-contained HTML page of business rules (the DOM-0 sign-off sheet that
    `scripts/pack-repo/generate-pack-rules-sheet.py` writes). The core lived in that script until P4.4 moved it here.
  * `plain_preview(pack, base, gate)` -- the relay's preview of a pack a browser AI wrote (`sentences`, `warnings`,
    `lines[{code, params, text}]`, `dataLoss`: the same shape an app preview has, so `npdev_relay_preview_pt` can write
    the same lines in Portuguese from the codes and params). Nothing here is HTML or JSON for the person to decode.

Both read only the pack (and, for the preview, the publish gate's report against the previous version).
"""
from __future__ import annotations

import html
import re

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


# --------------------------------------------------------------------------- the plain-words preview (P4.4)

COUNTED = ("concepts", "fields", "queries", "flows", "procedures", "roles", "customCapabilities")
SHOWN_FIELDS = 6
SHOWN_CONCEPTS = 8
SHOWN_CHANGES = 8
ITEM_EN = {"concepts": ("kind of record", "kinds of record"), "fields": ("field", "fields"), "queries": ("search", "searches"),
           "flows": ("process", "processes"), "procedures": ("action", "actions"), "roles": ("role", "roles"),
           "customCapabilities": ("service it needs", "services it needs")}
CHANGE_EN = {"ADDITIVE": "something added", "BREAKING": "something removed or changed that apps may rely on", "PATCH": "a small fix"}


def _plural(count: int, key: str) -> str:
    one, many = ITEM_EN[key]
    return f"{count} {one if count == 1 else many}"


def _words(items) -> str:
    items = [str(i) for i in items]
    return items[0] if len(items) == 1 else (", ".join(items[:-1]) + " and " + items[-1] if items else "")


def _list(pack: dict, key: str) -> list:
    value = pack.get(key)
    return [v for v in value if isinstance(v, dict)] if isinstance(value, list) else []


def who_kind(expression) -> tuple[str, list[str]]:
    """Who an access expression admits, in words a person needs no syntax for: ("anyone"|"roles"|"rule"|"nobody", roles)."""
    if expression is None or (isinstance(expression, str) and not expression.strip()):
        return "anyone", []
    text = str(expression).strip()
    if text in ("true", "$user != null"):
        return "anyone", []
    if text == "false":
        return "nobody", []
    roles = roles_in(text)
    return ("roles", roles) if roles else ("rule", [])


def _who_en(kind: str, roles: list[str]) -> str:
    return {"anyone": "anyone signed in", "nobody": "nobody", "rule": "only the people a rule allows (for example the owner of the record)",
            "roles": _words(roles)}[kind]


def _line(code: str, text: str, **params) -> dict:
    return {"code": code, "params": params, "text": text}


def plain_preview(pack: dict, base: dict | None = None, gate: dict | None = None) -> dict:
    """The preview of a pack answer. `base` is the previous version (None for a new pack); `gate` is the publish gate's
    report of old vs new (`findings[{classification, message}]`, `overallClassification`) when it ran."""
    lines: list[dict] = []
    warnings: list[dict] = []
    concepts = _list(pack, "concepts")
    totals = {"concepts": len(concepts), "fields": sum(len([f for f in c.get("fields", []) if isinstance(f, dict)]) for c in concepts)}
    for key in COUNTED[2:]:
        totals[key] = len(pack.get(key) or []) if isinstance(pack.get(key), list) else 0
    counts = [[key, totals[key]] for key in COUNTED if totals[key]]
    pack_id, version = str(pack.get("pack", "?")), str(pack.get("version", "?"))
    lines.append(_line("PACK_SUMMARY", f'A reusable part "{pack_id}" (version {version}) with '
                       + (_words(_plural(n, k) for k, n in counts) or "nothing in it yet") + ".",
                       pack=pack_id, version=version, counts=counts))
    base_version = str((base or {}).get("version") or "") or None
    if base is None:
        lines.append(_line("PACK_NEW", "Nothing is replaced: this is a new reusable part."))
    else:
        change = (gate or {}).get("overallClassification")
        lines.append(_line("PACK_NEW_VERSION", f"Version {base_version} becomes {version}"
                           + (f" ({CHANGE_EN.get(change, 'changed')})." if change else "."),
                           old=base_version, new=version, change=change))
    for concept in concepts[:SHOWN_CONCEPTS]:
        name = concept.get("name") if isinstance(concept.get("name"), str) else "?"
        label = label_of(concept, name)
        fields = [label_of(f, f.get("name", "?")) for f in concept.get("fields", []) if isinstance(f, dict) and not f.get("id")]
        shown = fields[:SHOWN_FIELDS]
        more = max(0, len(fields) - SHOWN_FIELDS)
        fields_text = _words(shown) + (f" and {more} more" if more else "")
        lines.append(_line("CREATE_CONCEPT", f"{label}: {_plural(len(fields), 'fields')}" + (f" ({fields_text})" if fields else "") + ".",
                           label=label, count=len(fields), count_text=_plural(len(fields), "fields"),
                           fields_part=f" ({fields_text})" if fields else "", fields_text=fields_text, fields_shown=shown, fields_more=more))
        access = concept.get("access") or {}
        (read_kind, read_roles), (write_kind, write_roles) = who_kind(access.get("read")), who_kind(access.get("write"))
        lines.append(_line("PACK_ACCESS", f"{label}: seen by {_who_en(read_kind, read_roles)}; changed by {_who_en(write_kind, write_roles)}.",
                           label=label, read_kind=read_kind, read_roles=read_roles, write_kind=write_kind, write_roles=write_roles))
        life = concept.get("lifecycle")
        if isinstance(life, dict) and life.get("states"):
            states = [label_of(s, str(s.get("value", "?"))) for s in life["states"] if isinstance(s, dict)]
            lines.append(_line("PACK_LIFECYCLE", f"{label} moves through: {_words(states)}.", label=label, states=states))
    if len(concepts) > SHOWN_CONCEPTS:
        rest = len(concepts) - SHOWN_CONCEPTS
        lines.append(_line("PACK_MORE", f"...and {rest} more kinds of record.", count=rest, key="concepts"))
    for query in _list(pack, "queries"):
        lines.append(_line("PACK_QUERY", f'Search {query.get("name", "?")}: reads {query.get("concept", "?")} records.',
                           name=str(query.get("name", "?")), concept=str(query.get("concept", "?"))))
    needs = [str(c.get("name")) for c in _list(pack, "customCapabilities") if c.get("name")]
    if needs:
        lines.append(_line("PACK_NEEDS", f"It asks the app that uses it to provide: {_words(needs)}.", names=needs))
    findings = [f for f in (gate or {}).get("findings") or [] if isinstance(f, dict) and f.get("message")]
    for finding in findings[:SHOWN_CHANGES]:
        lines.append(_line("PACK_CHANGE", str(finding["message"]), classification=str(finding.get("classification") or "PATCH"),
                           message=str(finding["message"])))
    if len(findings) > SHOWN_CHANGES:
        rest = len(findings) - SHOWN_CHANGES
        lines.append(_line("PACK_MORE", f"...and {rest} more changes.", count=rest, key="changes"))
    if any(f.get("classification") == "BREAKING" for f in findings):
        warnings.append({"code": "PACK_BREAKING", "needsConfirmation": False, "params": {"family": "PACK_BREAKING"},
                         "text": "This version removes or changes something apps that already use the part may rely on: they will need an update."})
    return {"sentences": [l["text"] for l in lines], "warnings": warnings, "dataLoss": False, "lines": lines}
