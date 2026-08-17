"""ER-diagram rendering for `npdev inspect bonds --diagram`.

Renders the SAME `concepts`/`bonds` data `inspect_bonds` already computes (npdev_cli.py) as a
self-contained SVG/HTML page -- no separate model read, no duplicated bond-extraction logic, so
this can never drift from what `inspect bonds`'s own JSON output says. The layout algorithm
mirrors NPDevEditor/ui-react/src/authoring/graph/erDiagramLayout.ts (a layered auto-layout: a
table's layer is the longest chain of outgoing reference fields it owns) so the CLI's diagram and
the Editor's live "ER diagram" view read the same way; kept as a second implementation rather than
a shared one because the Editor is TypeScript/React and this CLI is deliberately stdlib-only
Python with no build step (see npdev_cli.py's own zero-third-party-deps discipline).

Stdlib only, by the same rule as npdev_engines.py/npdev_monitor.py.
"""

from __future__ import annotations

import json
from html import escape as _esc

_HEADER_HEIGHT = 32
_ROW_HEIGHT = 22
_TABLE_WIDTH = 230
_LAYER_GAP_X = 110
_TABLE_GAP_Y = 32
_PADDING = 24
# Colors: only defined here for the initial-layout math above; the actual rendering (including
# these same values) now lives client-side in _DIAGRAM_SCRIPT below, since drag-and-drop needs a
# single render implementation that runs identically on load and after every table move.


def _tables_from_concepts(concepts: dict) -> list[dict]:
    """concepts: the same {name: conceptDict} map inspect_bonds() already builds."""
    tables = []
    for name, concept in concepts.items():
        fields = concept.get("fields") or []
        columns = []
        for field in fields:
            if not isinstance(field, dict):
                continue
            reference = field.get("reference") if isinstance(field.get("reference"), dict) else {}
            is_fk = field.get("type") == "reference" and bool(reference.get("target"))
            columns.append({
                "name": field.get("name", ""),
                "type": field.get("type") or "string",
                "isPrimaryKey": field.get("id") is True,
                "isForeignKey": is_fk,
                "required": field.get("required") is True or field.get("id") is True,
            })
        tables.append({"id": name, "name": name, "columns": columns})
    return tables


def _relationships_from_bonds(bonds: list[dict]) -> list[dict]:
    """bonds: the same list inspect_bonds() already computed -- reused verbatim, not re-derived."""
    relationships = []
    for bond in bonds:
        target = bond.get("targetConcept")
        if not target:
            continue
        relationships.append({
            "id": f"{bond['sourceConcept']}.{bond['sourceField']}->{target}",
            "fromTable": bond["sourceConcept"],
            "fromColumn": bond["sourceField"],
            "toTable": target,
            "toColumn": bond.get("via") or "id",
            "manyToMany": bond.get("cardinality") == "many-to-many",
        })
    return relationships


def _compute_layer(table_id: str, outgoing: dict, layer_of: dict, visiting: set) -> int:
    if table_id in layer_of:
        return layer_of[table_id]
    if table_id in visiting:
        return 0  # cycle guard
    visiting.add(table_id)
    targets = outgoing.get(table_id, [])
    layer = 0 if not targets else 1 + max(_compute_layer(t, outgoing, layer_of, visiting) for t in targets)
    visiting.discard(table_id)
    layer_of[table_id] = layer
    return layer


def compute_er_layout(tables: list[dict], relationships: list[dict]) -> dict:
    """Same math as erDiagramLayout.ts's computeErLayout -- kept in lockstep by hand (see module
    docstring for why this isn't literally shared code)."""
    by_id = {t["id"]: t for t in tables}
    outgoing: dict[str, list[str]] = {t["id"]: [] for t in tables}
    for rel in relationships:
        if rel["toTable"] in by_id and rel["toTable"] != rel["fromTable"]:
            outgoing[rel["fromTable"]].append(rel["toTable"])

    layer_of: dict[str, int] = {}
    for t in tables:
        _compute_layer(t["id"], outgoing, layer_of, set())

    layers: dict[int, list[dict]] = {}
    for t in tables:
        layers.setdefault(layer_of.get(t["id"], 0), []).append(t)
    max_layer = max(layers.keys(), default=0)

    layout_tables = []
    by_layout_id: dict[str, dict] = {}
    for layer in range(max_layer + 1):
        cursor_y = _PADDING
        x = _PADDING + layer * (_TABLE_WIDTH + _LAYER_GAP_X)
        for table in layers.get(layer, []):
            height = _HEADER_HEIGHT + max(1, len(table["columns"])) * _ROW_HEIGHT
            columns = [
                {**col, "y": _HEADER_HEIGHT + i * _ROW_HEIGHT + _ROW_HEIGHT / 2}
                for i, col in enumerate(table["columns"])
            ]
            layout_table = {
                "id": table["id"], "name": table["name"], "x": x, "y": cursor_y,
                "width": _TABLE_WIDTH, "height": height, "columns": columns,
            }
            layout_tables.append(layout_table)
            by_layout_id[table["id"]] = layout_table
            cursor_y += height + _TABLE_GAP_Y

    connectors = []
    for rel in relationships:
        source = by_layout_id.get(rel["fromTable"])
        target = by_layout_id.get(rel["toTable"])
        if not source or not target:
            continue
        from_col = next((c for c in source["columns"] if c["name"] == rel["fromColumn"]), None)
        to_col = next((c for c in target["columns"] if c["name"] == rel["toColumn"]), None)
        if to_col is None and target["columns"]:
            to_col = target["columns"][0]
        from_y = source["y"] + (from_col["y"] if from_col else _HEADER_HEIGHT / 2)
        to_y = target["y"] + (to_col["y"] if to_col else _HEADER_HEIGHT / 2)
        going_right = target["x"] >= source["x"]
        start_x = source["x"] + source["width"] if going_right else source["x"]
        end_x = target["x"] if going_right else target["x"] + target["width"]
        mid_x = (start_x + end_x) / 2
        connectors.append({
            "id": rel["id"],
            "path": f"M {start_x} {from_y} C {mid_x} {from_y}, {mid_x} {to_y}, {end_x} {to_y}",
            "manyToMany": rel["manyToMany"],
            "startX": start_x, "startY": from_y, "endX": end_x, "endY": to_y,
            "startDirection": 1 if going_right else -1,
            "endDirection": 1 if going_right else -1,
        })

    width = _PADDING * 2 + (max_layer + 1) * _TABLE_WIDTH + max_layer * _LAYER_GAP_X
    tallest_layer_height = max(
        1,
        max(
            (sum(_HEADER_HEIGHT + max(1, len(t["columns"])) * _ROW_HEIGHT + _TABLE_GAP_Y for t in entries)
             for entries in layers.values()),
            default=1,
        ),
    )
    height = _PADDING * 2 + tallest_layer_height
    return {"tables": layout_tables, "connectors": connectors, "width": width, "height": height}


_DRAG_MARGIN = 400  # extra canvas room beyond the auto-layout's own bounds, so a dragged table has somewhere to go


# The renderer (table boxes, connectors, crow's-foot/tick markers) and the drag interaction both
# live here, in JS, driven entirely by the JSON data block this module embeds below -- so the
# diagram you see on load and the diagram you get after dragging a table can never disagree with
# each other (one render function, called on load and again after every pointermove). Mirrors the
# same connector geometry as erDiagramLayout.ts/ErDiagramView.tsx's live-redraw (see this module's
# own docstring for why this is a second implementation, not shared code).
_DIAGRAM_SCRIPT = """
(function () {
  var data = JSON.parse(document.getElementById('er-diagram-data').textContent);
  var tablesLayer = document.getElementById('er-diagram-tables');
  var connectorsLayer = document.getElementById('er-diagram-connectors');
  var NS = 'http://www.w3.org/2000/svg';
  var tableById = {};
  data.tables.forEach(function (t) { tableById[t.id] = t; });
  var positions = {};

  function currentTable(id) {
    var base = tableById[id];
    if (!base) return null;
    var pos = positions[id];
    return pos ? Object.assign({}, base, pos) : base;
  }

  function el(tag, attrs) {
    var e = document.createElementNS(NS, tag);
    for (var k in attrs) { e.setAttribute(k, attrs[k]); }
    return e;
  }

  function textEl(attrs, content) {
    var t = el('text', attrs);
    t.textContent = content;
    t.style.userSelect = 'none';
    return t;
  }

  function renderTableEl(table) {
    var g = el('g', { transform: 'translate(' + table.x + ',' + table.y + ')' });
    g.style.cursor = 'grab';
    g.style.touchAction = 'none';
    g.appendChild(el('rect', { width: table.width, height: table.height, rx: 10, fill: '#ffffff', stroke: '#d5e1ef', 'stroke-width': 1.5 }));
    var headerPath = 'M 0 10 A 10 10 0 0 1 10 0 L ' + (table.width - 10) + ' 0 A 10 10 0 0 1 ' + table.width + ' 10 L ' + table.width + ' 32 L 0 32 Z';
    g.appendChild(el('path', { d: headerPath, fill: '#163f86' }));
    g.appendChild(textEl({ x: 12, y: 20, fill: '#ffffff', 'font-weight': 700, 'font-size': 13 }, table.name));
    table.columns.forEach(function (c) {
      var row = el('g', { transform: 'translate(0,' + (c.y - 11) + ')' });
      if (c.isPrimaryKey) {
        row.appendChild(el('circle', { cx: 14, cy: 11, r: 3.5, fill: '#163f86' }));
      } else if (c.isForeignKey) {
        row.appendChild(el('circle', { cx: 14, cy: 11, r: 3.5, fill: 'none', stroke: '#5f7388', 'stroke-width': 1.3 }));
      }
      row.appendChild(textEl({ x: 26, y: 15, 'font-size': 12, 'font-weight': c.isPrimaryKey ? 700 : 400, fill: '#0e1a2b' }, c.name));
      row.appendChild(textEl({ x: table.width - 10, y: 15, 'font-size': 11, fill: '#5f7388', 'text-anchor': 'end' }, c.type + (c.required ? '' : '?')));
      g.appendChild(row);
    });
    attachDrag(g, table.id);
    return g;
  }

  function computeConnector(from, to, rel) {
    var fromCol = from.columns.filter(function (c) { return c.name === rel.fromColumn; })[0];
    var toCol = to.columns.filter(function (c) { return c.name === rel.toColumn; })[0] || to.columns[0];
    var fromY = from.y + (fromCol ? fromCol.y : 16);
    var toY = to.y + (toCol ? toCol.y : 16);
    var goingRight = to.x >= from.x;
    var startX = goingRight ? from.x + from.width : from.x;
    var endX = goingRight ? to.x : to.x + to.width;
    var midX = (startX + endX) / 2;
    return {
      path: 'M ' + startX + ' ' + fromY + ' C ' + midX + ' ' + fromY + ', ' + midX + ' ' + toY + ', ' + endX + ' ' + toY,
      manyToMany: rel.manyToMany,
      startX: startX, startY: fromY, endX: endX, endY: toY,
      startDirection: goingRight ? 1 : -1, endDirection: goingRight ? 1 : -1
    };
  }

  function markerEl(x, y, direction, kind) {
    var g = el('g', { stroke: '#7c93b1', 'stroke-width': 1.5 });
    if (kind === 'one') {
      var t1 = x - direction * 6, t2 = x - direction * 11;
      g.appendChild(el('line', { x1: t1, y1: y - 6, x2: t1, y2: y + 6 }));
      g.appendChild(el('line', { x1: t2, y1: y - 6, x2: t2, y2: y + 6 }));
    } else {
      g.setAttribute('fill', 'none');
      var bx = x - direction * 14;
      g.appendChild(el('line', { x1: bx, y1: y - 7, x2: x, y2: y }));
      g.appendChild(el('line', { x1: bx, y1: y, x2: x, y2: y }));
      g.appendChild(el('line', { x1: bx, y1: y + 7, x2: x, y2: y }));
    }
    return g;
  }

  function renderConnectors() {
    connectorsLayer.innerHTML = '';
    data.relationships.forEach(function (rel) {
      var from = currentTable(rel.fromTable);
      var to = currentTable(rel.toTable);
      if (!from || !to) { return; }
      var c = computeConnector(from, to, rel);
      var g = el('g', {});
      g.appendChild(el('path', { d: c.path, fill: 'none', stroke: '#7c93b1', 'stroke-width': 1.5 }));
      g.appendChild(markerEl(c.startX, c.startY, c.startDirection, 'many'));
      g.appendChild(markerEl(c.endX, c.endY, c.endDirection, c.manyToMany ? 'many' : 'one'));
      connectorsLayer.appendChild(g);
    });
  }

  function renderTables() {
    tablesLayer.innerHTML = '';
    data.tables.forEach(function (t) { tablesLayer.appendChild(renderTableEl(currentTable(t.id))); });
  }

  var drag = null;
  function attachDrag(g, tableId) {
    g.addEventListener('pointerdown', function (e) {
      g.setPointerCapture(e.pointerId);
      var t = currentTable(tableId);
      drag = { tableId: tableId, pointerId: e.pointerId, startClientX: e.clientX, startClientY: e.clientY, startX: t.x, startY: t.y };
    });
    g.addEventListener('pointermove', function (e) {
      if (!drag || drag.pointerId !== e.pointerId || drag.tableId !== tableId) { return; }
      var nx = drag.startX + (e.clientX - drag.startClientX);
      var ny = drag.startY + (e.clientY - drag.startClientY);
      positions[tableId] = { x: nx, y: ny };
      g.setAttribute('transform', 'translate(' + nx + ',' + ny + ')');
      renderConnectors();
    });
    function end(e) { if (drag && drag.pointerId === e.pointerId) { drag = null; } }
    g.addEventListener('pointerup', end);
    g.addEventListener('pointercancel', end);
  }

  renderTables();
  renderConnectors();
})();
"""


def render_bonds_diagram_html(concepts: dict, bonds: list[dict], *, model_label: str) -> str:
    """concepts/bonds: the exact structures inspect_bonds() already built. Returns a complete,
    self-contained, interactive HTML page (inline SVG, drag-to-reposition tables, light/dark theme)
    -- write it to disk with your own Path.write_text call, same as write_or_print_json does for
    the JSON output."""
    tables = _tables_from_concepts(concepts)
    relationships = _relationships_from_bonds(bonds)
    layout = compute_er_layout(tables, relationships)

    # `.replace("</", "<\\/")`: standard guard against a concept/field name that happens to contain
    # "</script>" prematurely closing the data block it's embedded in below.
    diagram_data = json.dumps({"tables": layout["tables"], "relationships": relationships}).replace("</", "<\\/")
    svg_width = layout["width"] + _DRAG_MARGIN
    svg_height = layout["height"] + _DRAG_MARGIN

    return f"""<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<title>{_esc(model_label)} — Concept Relationship Diagram</title>
<style>
  :root {{
    --bg: #f5f7f9; --surface: #ffffff; --surface-alt: #eef2f6; --border: #d9e0e7;
    --text: #18232c; --text-muted: #576773; --accent: #163f86; --accent-soft: #e2ebf1;
    --mono-bg: #eef2f6; --mono-border: #d3dce3; --shadow: 0 1px 2px rgba(24,35,44,0.06), 0 8px 24px rgba(24,35,44,0.05);
  }}
  @media (prefers-color-scheme: dark) {{
    :root:not([data-theme="light"]) {{
      --bg: #10161b; --surface: #171f26; --surface-alt: #1c262e; --border: #2a3742;
      --text: #e7edf2; --text-muted: #96a6b1; --accent: #7fb0cf; --accent-soft: #1c3140;
      --mono-bg: #1b2530; --mono-border: #2c3945; --shadow: 0 1px 2px rgba(0,0,0,0.4), 0 8px 24px rgba(0,0,0,0.35);
    }}
  }}
  :root[data-theme="dark"] {{
    --bg: #10161b; --surface: #171f26; --surface-alt: #1c262e; --border: #2a3742;
    --text: #e7edf2; --text-muted: #96a6b1; --accent: #7fb0cf; --accent-soft: #1c3140;
    --mono-bg: #1b2530; --mono-border: #2c3945; --shadow: 0 1px 2px rgba(0,0,0,0.4), 0 8px 24px rgba(0,0,0,0.35);
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; background: var(--bg); color: var(--text); font-family: -apple-system, "Segoe UI", ui-sans-serif, system-ui, sans-serif; line-height: 1.5; }}
  .page {{ max-width: 1100px; margin: 0 auto; padding: 2.5rem 1.5rem 4rem; }}
  .eyebrow {{ font-size: 0.72rem; letter-spacing: 0.09em; text-transform: uppercase; color: var(--accent); font-weight: 700; margin: 0 0 0.5rem; }}
  h1 {{ font-size: clamp(1.5rem, 3vw, 2rem); margin: 0 0 0.5rem; text-wrap: balance; letter-spacing: -0.01em; }}
  .dek {{ color: var(--text-muted); max-width: 68ch; margin: 0 0 1rem; }}
  .meta-row {{ display: flex; flex-wrap: wrap; gap: 0.4rem 1.2rem; font-size: 0.85rem; color: var(--text-muted); margin-bottom: 1.5rem; }}
  .meta-row strong {{ color: var(--text); font-weight: 600; }}
  code {{ background: var(--mono-bg); border: 1px solid var(--mono-border); border-radius: 4px; padding: 0.08em 0.4em; font-size: 0.86em; font-family: ui-monospace, "Cascadia Code", Consolas, monospace; }}
  .diagram-card {{ border: 1px solid var(--border); border-radius: 12px; background: var(--surface); box-shadow: var(--shadow); overflow: hidden; }}
  .diagram-scroll {{ overflow: auto; max-height: 80vh; padding: 1rem; }}
  .diagram-scroll svg {{ display: block; }}
  .diagram-scroll rect[fill="#ffffff"] {{ fill: var(--surface); }}
  .diagram-scroll text[fill="#0e1a2b"] {{ fill: var(--text); }}
  .diagram-scroll text[fill="#5f7388"] {{ fill: var(--text-muted); }}
  .diagram-scroll rect[stroke="#d5e1ef"] {{ stroke: var(--border); }}
  .legend {{ display: flex; flex-wrap: wrap; gap: 1.2rem; padding: 0.8rem 1rem; border-top: 1px solid var(--border); background: var(--surface-alt); font-size: 0.82rem; color: var(--text-muted); }}
  .legend span {{ display: inline-flex; align-items: center; gap: 0.4em; }}
  .legend .dot {{ width: 9px; height: 9px; border-radius: 50%; background: var(--accent); display: inline-block; }}
  .legend .ring {{ width: 9px; height: 9px; border-radius: 50%; border: 1.3px solid var(--text-muted); display: inline-block; }}
</style>
</head>
<body>
<div class="page">
  <header>
    <p class="eyebrow">npdev inspect bonds --diagram</p>
    <h1>{_esc(model_label)} — Concept Relationship Diagram</h1>
    <p class="dek">Rendered from the same bond/concept data <code>npdev inspect bonds</code>'s JSON output reports.</p>
    <div class="meta-row">
      <span><strong>{len(tables)} concepts</strong></span>
      <span><strong>{len(relationships)} references</strong></span>
    </div>
  </header>
  <div class="diagram-card">
    <div class="diagram-scroll">
      <svg id="er-diagram-svg" width="{svg_width}" height="{svg_height}" viewBox="0 0 {svg_width} {svg_height}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Concept relationship diagram, tables are draggable">
        <g id="er-diagram-connectors"></g>
        <g id="er-diagram-tables"></g>
      </svg>
    </div>
    <div class="legend">
      <span><span class="dot"></span> Primary key</span>
      <span><span class="ring"></span> Foreign key</span>
      <span>Crow's-foot end = many</span>
      <span>Double-tick end = one</span>
      <span>Drag any table to reposition it</span>
    </div>
  </div>
  <script id="er-diagram-data" type="application/json">{diagram_data}</script>
  <script>{_DIAGRAM_SCRIPT}</script>
</div>
</body>
</html>
"""
