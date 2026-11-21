// Archetype "detail": one row as label/value pairs plus its actions. The row comes from the id in the URL
// (#/<screen>/<id>) or, without one, from the screen's `pick` (first | latest | only).
import { card, cellValue, emptyCard, formatValue, h, selectFields, valueNode } from "./core.js";
import { renderActions } from "./actions.js";
import { resolveRow } from "./api.js";
import { rowMap } from "./map.js";

export function rowView(ctx, row) {
  const list = h("dl", { class: "fields" });
  for (const field of selectFields(ctx.screen)) {
    list.appendChild(h("div", { class: "field" }, h("dt", { text: field.label }),
      h("dd", null, valueNode(formatValue(cellValue(row, field, ctx.user), field)))));
  }
  const actions = renderActions(ctx, ctx.screen.actions, { scope: () => ({ row }) });
  // a `map` on the screen (status/detail): the row's own position, one marker (Leaflet loads only now)
  const map = ctx.screen.map && ctx.screen.map.lat ? rowMap(ctx, ctx.screen.map, [row]) : null;
  return h("section", { class: "panel" }, map, list, actions);
}

export async function render(ctx, defaultPick) {
  const row = await resolveRow(ctx.api, ctx.screen, ctx.id, ctx.user, defaultPick);
  if (row === undefined) { ctx.root.appendChild(card("empty", null, NPDevI18n.t("experience.detail.selectRow", "Select a row to see its details."))); return; }
  if (row === null) { ctx.root.appendChild(emptyCard(ctx.screen)); return; }
  ctx.root.appendChild(rowView(ctx, row));
}
