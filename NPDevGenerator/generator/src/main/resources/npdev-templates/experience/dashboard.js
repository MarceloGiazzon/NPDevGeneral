// Archetype "dashboard": a grid of widgets (tile, table, list, chart, map). A widget's
// `span` (1..4) is its width in grid columns. A widget with `screen` links to that screen; table/list/chart widgets
// read the rows of that screen (so its `where` and access rules apply). Tile/chart `value` expressions that need the
// server (count(), sum()) go out together as one /api/x/eval request -- see serverval.js. Nothing here can fail the
// whole dashboard: a widget that cannot load shows a short notice instead.
import { buildHash, card, cellValue, formatValue, h, pathValue, selectColumns, valueNode } from "./core.js";
import { loadRows } from "./api.js";
import { mountMap, rowPoints } from "./map.js";

const LINKED = ["tile", "chart", "map"];
export const spanOf = (w) => Math.max(1, Math.min(4, Math.floor(Number(w && w.span)) || 1));
const muted = (text) => h("p", { class: "muted note", text });

export function showValue(v) {
  if (v === undefined || v === null || v === "") return h("span", { class: "muted", text: "\u2014" });
  if (typeof v === "boolean") return h("span", { text: v ? NPDevI18n.t("experience.core.yes", "Yes") : NPDevI18n.t("experience.core.no", "No") });
  if (typeof v === "number") return h("span", { text: new Intl.NumberFormat((typeof window !== "undefined" && window.NPDevI18n ? window.NPDevI18n.language() : undefined), { maximumFractionDigits: 2 }).format(v) });
  return h("span", { text: typeof v === "object" ? JSON.stringify(v) : String(v) });
}

async function tile(ctx, w) {
  const v = w.value ? await ctx.server.value(w.value, ctx.user) : undefined;
  return h("div", { class: "tile-value" }, showValue(v));
}

/** Chart data as [{label, expr?, value?}]: from `value` (an expression, a {label: expression} map or a
 *  [{label, value}] list), else the rows of the linked screen counted per group. */
async function chartEntries(ctx, w, target) {
  const v = w.value;
  const specs = typeof v === "string" ? [{ label: w.title || "", expr: v }]
    : Array.isArray(v) ? v.filter((e) => e && e.value).map((e) => ({ label: String(e.label == null ? e.value : e.label), expr: e.value }))
    : v && typeof v === "object" ? Object.entries(v).map(([label, expr]) => ({ label, expr })) : null;
  if (specs) {
    const answers = await Promise.all(specs.map((s) => ctx.server.value(s.expr, ctx.user)));
    return specs.map((s, i) => ({ label: s.label, value: answers[i] }));
  }
  if (!target || !target.dataUrl) return [];
  const meta = target.fieldMeta || {};
  const field = w.groupBy || target.groupBy || Object.keys(meta).find((n) => meta[n] && meta[n].type === "enum");
  if (!field) return [];
  const { rows } = await loadRows(ctx.api, target, { want: 200, user: ctx.user });
  const counts = new Map();
  for (const row of rows) { const k = pathValue(row, field); const key = k == null || k === "" ? NPDevI18n.t("experience.board.noValue", "No value") : String(k); counts.set(key, (counts.get(key) || 0) + 1); }
  return Array.from(counts, ([label, value]) => ({ label, value }));
}

async function chart(ctx, w, target) {
  const entries = await chartEntries(ctx, w, target);
  if (!entries.length) return muted(NPDevI18n.t("experience.dashboard.noChart", "Nothing to chart yet."));
  const nums = entries.map((e) => (typeof e.value === "number" && Number.isFinite(e.value) ? Math.max(0, e.value) : 0));
  const max = Math.max(...nums, 0);
  const summary = (w.title ? w.title + ": " : "") + entries.map((e, i) => e.label + " " + nums[i]).join(", ");
  return h("div", { class: "chart", role: "img", "aria-label": summary }, entries.map((e, i) => h("div", { class: "bar-row" },
    h("span", { class: "bar-label", text: e.label }),
    h("span", { class: "bar-track" }, h("span", { class: "bar", style: "width:" + (max > 0 ? Math.round((nums[i] / max) * 1000) / 10 : 0) + "%" })),
    h("span", { class: "bar-value" }, showValue(e.value)))));
}

async function rowsWidget(ctx, w, target, compact) {
  if (!target || !target.dataUrl) return muted(NPDevI18n.t("experience.dashboard.nothingToShow", "Nothing to show."));
  const { rows } = await loadRows(ctx.api, target, { want: 5, user: ctx.user });
  if (!rows.length) return muted((target.empty && target.empty.text) || NPDevI18n.t("experience.core.nothingYet", "Nothing here yet."));
  const detail = ctx.detailFor(target);
  const idOf = (row) => row[target.idField || "id"];
  const columns = selectColumns(target).slice(0, compact ? 2 : 4);
  const cell = (row, col) => valueNode(formatValue(cellValue(row, col, ctx.user), col));
  if (compact) {
    return h("ul", { class: "compact" }, rows.map((row) => {
      const id = idOf(row);
      const body = [h("span", { class: "title" }, cell(row, columns[0])), columns[1] ? cell(row, columns[1]) : null];
      return h("li", null, id == null ? h("div", { class: "compact-row" }, body) : h("a", { class: "compact-row", href: buildHash(detail, id) }, body));
    }));
  }
  return h("div", { class: "table-wrap" }, h("table", { class: "table" },
    h("thead", null, h("tr", null, columns.map((c) => h("th", { text: c.label })))),
    h("tbody", null, rows.map((row) => {
      const id = idOf(row);
      return h("tr", null, columns.map((col, i) => h("td", null,
        i === 0 && id != null ? h("a", { href: buildHash(detail, id) }, cell(row, col)) : cell(row, col))));
    }))));
}

/** A `map` widget shows the markers of the linked screen's rows (that screen's `map` says where lat/lng are). */
async function mapWidget(ctx, w, target) {
  if (!target || !target.dataUrl || !target.map) return muted(NPDevI18n.t("experience.dashboard.nothingToShow", "Nothing to show."));
  const { rows } = await loadRows(ctx.api, target, { want: 100, user: ctx.user });
  const points = rowPoints(rows, target.map, ctx.user);
  if (!points.length) return muted((target.empty && target.empty.text) || NPDevI18n.t("experience.core.nothingYet", "Nothing here yet."));
  return mountMap(ctx, target.map, { points, label: w.title || target.title || NPDevI18n.t("experience.map.label", "Map"), compact: true }).el;
}

async function body(ctx, w, target) {
  switch (w.kind) {
    case "tile": return tile(ctx, w);
    case "chart": return chart(ctx, w, target);
    case "table": return rowsWidget(ctx, w, target, false);
    case "list": return rowsWidget(ctx, w, target, true);
    case "map": return mapWidget(ctx, w, target);
    default: return muted(NPDevI18n.t("experience.dashboard.notSupported", "Not supported yet."));
  }
}

async function widget(ctx, w) {
  const target = w.screen && ctx.screensByName ? ctx.screensByName[w.screen] : null;
  let content;
  try { content = await body(ctx, w, target); }
  catch (e) { content = muted(NPDevI18n.t("experience.dashboard.widgetFailed", "This widget could not load.")); }
  const title = w.title ? h("h3", { class: "widget-title", text: w.title }) : null;
  const link = target ? buildHash(target.name) : null;
  const linkAll = link && LINKED.includes(w.kind) && w.kind !== "map"; // a map must stay tappable: only its title links
  const inner = [title && link && !linkAll ? h("a", { class: "widget-link", href: link }, title) : title, content];
  return h("section", { class: "widget " + (LINKED.includes(w.kind) ? w.kind : "rowset") + " span-" + spanOf(w), "data-kind": String(w.kind) },
    linkAll ? h("a", { class: "widget-link", href: link }, inner) : inner);
}

export async function render(ctx) {
  const widgets = Array.isArray(ctx.screen.widgets) ? ctx.screen.widgets : [];
  if (!widgets.length) { ctx.root.appendChild(card("empty", null, (ctx.screen.empty && ctx.screen.empty.text) || NPDevI18n.t("experience.core.nothingYet", "Nothing here yet."))); return; }
  // every widget starts synchronously, so all their server values share one /api/x/eval request
  const built = await Promise.all(widgets.map((w) => widget(ctx, w || {})));
  ctx.root.appendChild(h("div", { class: "widgets" }, built));
}
