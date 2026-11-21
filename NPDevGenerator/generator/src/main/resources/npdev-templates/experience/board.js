// Archetype "board": rows grouped into columns by the screen's `groupBy` field (a lifecycle / enum field). A card
// shows the label fields and the screen's actions as ordinary buttons -- a "move" to another column is just an action
// that starts a flow, so it works with a tap and a keyboard. Drag and drop is an ADDITIONAL path, wired only for an
// action that declares `style: "move"` and a `moveTo` column value; it triggers that same action (confirm included).
import { cellValue, enumText, formatValue, h, emptyCard, card, pathValue, selectColumns, valueNode } from "./core.js";
import { renderActions } from "./actions.js";
import { loadRows } from "./api.js";

export const noValue = () => NPDevI18n.t("experience.board.noValue", "No value");
const keyOf = (v) => (v == null ? "" : String(v));

/** Column keys in order: the declared enum values first, then any other value found (and "" for rows without one). */
export function boardColumns(meta, rows, field) {
  const info = (meta && meta[field]) || {};
  const declared = (info.values || info.enumValues || info.options || []).map(String);
  const keys = declared.slice();
  for (const row of rows) { const k = keyOf(pathValue(row, field)); if (!keys.includes(k)) keys.push(k); }
  return keys;
}

const isMove = (a) => a && a.style === "move" && a.moveTo != null && a.moveTo !== "";

export async function render(ctx) {
  const { screen, root } = ctx;
  const field = screen.groupBy;
  if (!field) { root.appendChild(card("notice", NPDevI18n.t("experience.board.noGroupByTitle", "This board has no groupBy field"), NPDevI18n.t("experience.board.noGroupByHint", "Set groupBy to the field that makes the columns."))); return; }
  const { rows, truncated } = await loadRows(ctx.api, screen, { want: 200, user: ctx.user });
  if (!rows.length) { root.appendChild(emptyCard(screen)); return; }
  const labelColumns = selectColumns(screen).filter((c) => c.value !== field).slice(0, 4);
  const keys = boardColumns(screen.fieldMeta, rows, field);
  const lists = new Map();
  const board = h("div", { class: "board" });
  let dragging = null; // {row, box}: the card being dragged

  const moveFor = (drag, key) => drag && drag.box.shown.find((a) => isMove(a) && String(a.moveTo) === key);
  const columnEl = (key) => {
    const ul = h("ul", { class: "rows" });
    lists.set(key, ul);
    const count = h("span", { class: "badge", text: "0" });
    const el = h("section", { class: "board-col", "aria-label": key === "" ? noValue() : enumText(key),
      ondragover: (e) => { if (moveFor(dragging, key) && e && e.preventDefault) e.preventDefault(); },
      ondrop: (e) => {
        if (e && e.preventDefault) e.preventDefault();
        const action = moveFor(dragging, key);
        if (action) dragging.box.activate(action);
        dragging = null;
      }
    }, h("h3", { class: "board-col-title" }, h("span", { text: key === "" ? noValue() : enumText(key) }), count), ul);
    el.count = count;
    return el;
  };
  const columns = new Map(keys.map((k) => [k, columnEl(k)]));
  columns.forEach((el) => board.appendChild(el));

  for (const row of rows) {
    const key = keyOf(pathValue(row, field));
    const box = renderActions(ctx, screen.actions, { scope: () => ({ row }) });
    const cells = labelColumns.map((col, i) => h("div", { class: i === 0 ? "cell title" : "cell" },
      i === 0 ? null : h("span", { class: "k", text: col.label }), valueNode(formatValue(cellValue(row, col, ctx.user), col))));
    const draggable = box.shown.some(isMove);
    const item = h("li", { class: "row-item" }, h("div", {
      class: "board-card", draggable: draggable ? "true" : null,
      ondragstart: draggable ? (e) => { dragging = { row, box }; if (e && e.dataTransfer) e.dataTransfer.setData("text/plain", keyOf(row[screen.idField || "id"])); } : null,
      ondragend: draggable ? () => { dragging = null; } : null
    }, cells, box));
    lists.get(key).appendChild(item);
  }
  columns.forEach((el, key) => {
    const n = lists.get(key).children.length;
    el.count.textContent = String(n);
    if (!n) lists.get(key).appendChild(h("li", { class: "muted note", text: NPDevI18n.t("experience.board.nothingHere", "Nothing here.") }));
  });
  root.appendChild(board);
  if (truncated) root.appendChild(h("p", { class: "muted note", text: NPDevI18n.t("experience.list.firstRows", "Showing the first {count} rows.", { count: rows.length }) }));
}
