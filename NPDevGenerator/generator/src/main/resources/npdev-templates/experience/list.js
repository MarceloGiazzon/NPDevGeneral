// Archetype "list": rows of one concept/query as tappable cards (<= 7 columns). A row tap opens the detail screen.
import { buildHash, cellValue, emptyCard, formatValue, h, selectColumns, valueNode } from "./core.js";
import { loadRows } from "./api.js";

export function rowCard(ctx, row, columns, href) {
  const cells = columns.map((col, i) => {
    const value = valueNode(formatValue(cellValue(row, col, ctx.user), col));
    return h("div", { class: i === 0 ? "cell title" : "cell" }, i === 0 ? null : h("span", { class: "k", text: col.label }), value);
  });
  return h("li", { class: "row-item" }, href ? h("a", { class: "row", href }, cells) : h("div", { class: "row" }, cells));
}

export async function render(ctx) {
  const { screen, root } = ctx;
  const columns = selectColumns(screen);
  const { rows, truncated } = await loadRows(ctx.api, screen, { want: 50, user: ctx.user });
  if (!rows.length) { root.appendChild(emptyCard(screen)); return; }
  const target = ctx.detailFor(screen);
  const list = h("ul", { class: "rows", style: "--cols:" + columns.length });
  for (const row of rows) {
    const id = row[screen.idField || "id"];
    list.appendChild(rowCard(ctx, row, columns, id == null ? null : buildHash(target, id)));
  }
  root.appendChild(list);
  if (truncated) root.appendChild(h("p", { class: "muted note", text: "Showing the first " + rows.length + " rows." }));
}
