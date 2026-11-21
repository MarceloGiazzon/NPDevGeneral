// Archetype "inbox": rows that wait for the user, each with its actions (accept / decline) and, when the screen
// declares `countdown`, a live countdown to the row's deadline. The countdown is display only: at zero the row's
// buttons are locked and it says "Expired"; the client never starts a flow on expiry (the server owns the deadline).
import { cellValue, emptyCard, evalLocal, formatValue, h, scopeVars, selectColumns, valueNode } from "./core.js";
import { renderActions } from "./actions.js";
import { loadRows } from "./api.js";

export const URGENT_SECONDS = 10;
const pad = (n) => (n < 10 ? "0" : "") + n;

/** Seconds left -> "mm:ss" (under an hour), "1 h 05 min", "2 d 3 h"; zero or less -> "Expired". */
export function formatCountdown(seconds) {
  const s = Math.ceil(seconds);
  if (!(s > 0)) return NPDevI18n.t("experience.inbox.expired", "Expired");
  if (s < 3600) return pad(Math.floor(s / 60)) + ":" + pad(s % 60);
  if (s < 86400) return Math.floor(s / 3600) + " h " + pad(Math.floor((s % 3600) / 60)) + " min";
  return Math.floor(s / 86400) + " d " + Math.floor((s % 86400) / 3600) + " h";
}

/** The deadline (epoch ms) a countdown expression gives for one row, or null when it cannot be evaluated.
 *  The expression may yield an instant (ISO text or epoch), or a plain number of seconds still remaining. */
export function deadlineOf(spec, row, user, nowMs) {
  const source = typeof spec === "string" ? spec : spec && spec.until;
  if (!source) return null;
  const r = evalLocal(source, scopeVars(row, null, user));
  if (!r.ok) return null;
  const v = r.value;
  if (typeof v === "number" && Number.isFinite(v)) return v >= 1e11 ? v : v >= 1e9 ? v * 1000 : nowMs + v * 1000;
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? null : v.getTime();
  if (typeof v === "string" && v.trim() !== "") { const t = Date.parse(v); return Number.isNaN(t) ? null : t; }
  return null;
}

/** One countdown line bound to one row. Returns {node, update(nowMs)}; `onExpire` fires once. */
export function countdownView(label, deadline, onExpire) {
  const text = h("span", { class: "countdown-time" });
  const node = h("div", { class: "countdown", role: "timer" }, label ? h("span", { class: "k", text: label }) : null, text);
  let expired = false;
  const update = (nowMs) => {
    const left = (deadline - nowMs) / 1000;
    text.textContent = formatCountdown(left);
    const done = !(Math.ceil(left) > 0);
    node.className = "countdown" + (done ? " expired" : left < URGENT_SECONDS ? " urgent" : "");
    if (done && !expired) { expired = true; onExpire(); }
  };
  return { node, update };
}

export async function render(ctx) {
  const { screen, root } = ctx;
  const columns = selectColumns(screen);
  const { rows, truncated } = await loadRows(ctx.api, screen, { want: 50, user: ctx.user });
  if (!rows.length) { root.appendChild(emptyCard(screen)); return; }
  const nowMs = ctx.clock();
  const list = h("ul", { class: "rows inbox" });
  for (const row of rows) {
    const cells = columns.map((col, i) => h("div", { class: i === 0 ? "cell title" : "cell" },
      i === 0 ? null : h("span", { class: "k", text: col.label }), valueNode(formatValue(cellValue(row, col, ctx.user), col))));
    const actions = renderActions(ctx, screen.actions, { scope: () => ({ row }) });
    const children = [...cells]; // a spread: h() flattens one level, an array inside would reach appendChild as an array
    const deadline = screen.countdown ? deadlineOf(screen.countdown, row, ctx.user, nowMs) : null;
    if (deadline != null) {
      const label = typeof screen.countdown === "object" ? screen.countdown.label : "";
      const view = countdownView(label, deadline, () => actions.lock());
      view.update(nowMs);
      ctx.onCleanup(ctx.ticker.subscribe(view.update));
      children.push(view.node);
    }
    children.push(actions);
    list.appendChild(h("li", { class: "row-item" }, h("div", { class: "row inbox-row" }, children)));
  }
  root.appendChild(list);
  if (truncated) root.appendChild(h("p", { class: "muted note", text: NPDevI18n.t("experience.inbox.firstItems", "Showing the first {count} items.", { count: rows.length }) }));
}
