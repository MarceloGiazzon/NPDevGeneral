// Archetype "picker" (P5-T9a, the old selectors[]): rows of one concept to pick from, one or several (multiSelect).
// Hand-off, deliberately tiny: open it as  #/<picker>/<screen to return to>  (the id slot names the opener). Done stores the
// pick as JSON under sessionStorage key  npdev.picker.<picker> = {"picked": [{returnedName: field value}], "at": ms}  and
// goes back; the opener reads it once with takePicked(). Opened with no return screen it keeps the pick and says so: no crash.
// Rows come from the concept's REST list, so row-level access applies; `where` only narrows (picker predicate, see below).
import { buildHash, card, cellValue, emptyCard, formatValue, h, selectColumns, valueNode } from "./core.js";
import { loadRows } from "./api.js";

export const pickerKey = (name) => "npdev.picker." + name;
const storeOf = (ctx) => { try { return ctx.storage || globalThis.sessionStorage || null; } catch (e) { return null; } };
const text = (v) => (v == null ? "" : String(v));

/** Read and clear what a picker handed back: the array of returned objects, or null when there is none. */
export function takePicked(storage, name) {
  try {
    const raw = storage && storage.getItem(pickerKey(name));
    if (!raw) return null;
    if (storage.removeItem) storage.removeItem(pickerKey(name));
    const value = JSON.parse(raw);
    return Array.isArray(value.picked) ? value.picked : null;
  } catch (e) { return null; }
}

/** The picker predicate: clauses joined by &&, each `field == literal` or `field != literal` (literal 'quoted' or bare).
 *  A clause it cannot read (a $root.x reference needs a record the picker does not have) is ignored, never guessed. */
export function predicate(where) {
  const clauses = text(where).split("&&").map((c) => /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(==|!=)\s*(.+?)\s*$/.exec(c)).filter(Boolean)
    .map((m) => ({ field: m[1], same: m[2] === "==", literal: /^'.*'$/.test(m[3]) ? m[3].slice(1, -1) : m[3] }))
    .filter((c) => !c.literal.startsWith("$"));
  return (row) => clauses.every((c) => (text(row[c.field]) === c.literal) === c.same);
}

/** orderBy is a field name or a list of them; "-f" or "f desc" sorts descending. */
export function sortRows(rows, orderBy) {
  const keys = [].concat(orderBy || []).map((o) => /^\s*(-?)([A-Za-z_][A-Za-z0-9_]*)(?:\s+(asc|desc))?\s*$/i.exec(o)).filter(Boolean)
    .map((m) => ({ field: m[2], sign: m[1] === "-" || /^desc$/i.test(m[3] || "") ? -1 : 1 }));
  const cmp = (a, b) => { for (const k of keys) { const x = a[k.field], y = b[k.field]; if (x !== y) return (x == null ? -1 : y == null ? 1 : x < y ? -1 : 1) * k.sign; } return 0; };
  return keys.length ? rows.slice().sort(cmp) : rows;
}

/** What one picked row hands back: {returnedName: row[field]} per returnMapping, else the whole row's id. */
export function returned(row, mapping, idField) {
  const entries = Object.entries(mapping || {});
  return entries.length ? Object.fromEntries(entries.map(([name, field]) => [name, row[field]])) : { id: row[idField] };
}

export async function render(ctx) {
  const { screen, root } = ctx;
  const idField = screen.idField || "id";
  const many = screen.multiSelect === true;
  const { rows } = await loadRows(ctx.api, Object.assign({}, screen, { where: undefined, orderBy: undefined }), { want: 500, user: ctx.user });
  const all = sortRows(rows.filter(predicate(screen.where)), screen.orderBy);
  if (!all.length) { root.appendChild(emptyCard(screen)); return; }
  const columns = selectColumns(screen);
  const chosen = new Map();
  const searchBy = (screen.filters || []).filter((f) => typeof f === "string");
  let query = "";
  const list = h("ul", { class: "rows", role: many ? "group" : "radiogroup" });
  const done = h("button", { type: "button", class: "btn primary", text: NPDevI18n.t("experience.picker.done", "Done"), onclick: () => finish() });
  done.disabled = true;
  const opener = ctx.id && ctx.screensByName && ctx.screensByName[ctx.id] ? ctx.id : null;

  function paint() {
    const shown = query ? all.filter((r) => searchBy.some((f) => text(r[f]).toLowerCase().includes(query))) : all;
    list.replaceChildren(...shown.map((row) => {
      const id = row[idField];
      const on = chosen.has(id);
      return h("li", { class: "row-item" }, h("button", { type: "button", class: "row" + (on ? " selected" : ""), role: many ? "checkbox" : "radio", "aria-checked": on ? "true" : "false",
        onclick: () => { if (on) chosen.delete(id); else { if (!many) chosen.clear(); chosen.set(id, row); } paint(); } },
      columns.map((col, i) => h("div", { class: i === 0 ? "cell title" : "cell" }, valueNode(formatValue(cellValue(row, col, ctx.user), col))))));
    }));
    done.disabled = chosen.size === 0;
    if (!shown.length) list.appendChild(h("li", null, card("empty", null, NPDevI18n.t("experience.picker.noMatch", "No match."))));
  }
  function finish() {
    const picked = [...chosen.values()].map((row) => returned(row, screen.returnMapping, idField));
    const store = storeOf(ctx);
    try { if (store) store.setItem(pickerKey(screen.name), JSON.stringify({ picked, at: ctx.clock ? ctx.clock() : Date.now() })); } catch (e) { /* storage blocked: nothing to hand back */ }
    if (opener && ctx.go) ctx.go(buildHash(opener)); else ctx.toast(NPDevI18n.t("experience.picker.kept", "Selection kept."));
  }

  if (searchBy.length) root.appendChild(h("input", { type: "search", class: "search", "aria-label": NPDevI18n.t("experience.picker.search", "Search"), placeholder: NPDevI18n.t("experience.picker.search", "Search"),
    oninput: (e) => { query = text(e.target && e.target.value).trim().toLowerCase(); paint(); } }));
  paint();
  root.appendChild(list);
  root.appendChild(h("div", { class: "actions" }, done,
    opener ? h("button", { type: "button", class: "btn secondary", text: NPDevI18n.t("experience.picker.cancel", "Cancel"), onclick: () => ctx.go && ctx.go(buildHash(opener)) }) : null));
}
