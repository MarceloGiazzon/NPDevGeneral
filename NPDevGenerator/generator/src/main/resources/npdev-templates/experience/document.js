// Archetype "document" (P5-T9b, the old documents[]): printable output. This renderer prints nothing itself -- the server
// renders the PDF (and the CSV of a `report`) at /api/documents/<name>/render.pdf|csv with the caller's own access rules.
// The card only offers the downloads. A plain link cannot carry the bearer token, so each button fetches the file with the
// api's headers and saves the blob through an object URL. An aggregate-bound document prints one record: open it as
// #/<document>/<record id>, or pick a record from the list (the aggregate's root concept, so row-level access applies).
import { card, h, humanize } from "./core.js";
import { loadRows } from "./api.js";

const base = (name) => "/api/documents/" + encodeURIComponent(name);
export const pdfUrl = (name) => base(name) + "/render.pdf";
export const csvUrl = (name) => base(name) + "/render.csv";
export const aggregatePdfUrl = (name, id) => base(name) + "/aggregate/" + encodeURIComponent(id) + "/render.pdf";

const problem = (status) => status === 401 ? NPDevI18n.t("experience.api.sessionExpired", "Your session expired. Sign in again.")
  : status === 403 ? NPDevI18n.t("experience.document.noPermission", "You don't have permission to print this.")
  : status === 404 ? NPDevI18n.t("experience.document.notAvailable", "That document is not available.")
  : status === 413 ? NPDevI18n.t("experience.document.tooMany", "Too many rows for one document. Narrow what it covers.")
  : NPDevI18n.t("experience.document.createFailed", "The document could not be created (HTTP {status}).", { status });

function saveBlob(blob, filename) {
  const url = globalThis.URL.createObjectURL(blob);
  const link = h("a", { href: url, download: filename });
  link.click();
  globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 1000);
}

/** Fetch one rendered file with the api's headers and hand it to ctx.saveFile (or the browser). Errors go to ctx.reportError. */
export async function download(ctx, url, filename) {
  try {
    const res = await (ctx.fetch || globalThis.fetch)(url, { headers: ctx.api.headers(false) });
    if (!res.ok) { ctx.reportError(problem(res.status)); return; }
    (ctx.saveFile || saveBlob)(await res.blob(), filename);
  } catch (e) { ctx.reportError(NPDevI18n.t("experience.api.unreachable", "Can't reach the server. Check your connection.")); }
}

const button = (label, onclick, primary) => h("button", { type: "button", class: "btn " + (primary ? "primary" : "secondary"), text: label, onclick });

/** A row's label in the record list: its first non-empty text value, else its id. */
const labelOf = (row, idField) => Object.entries(row).filter(([k, v]) => k !== idField && typeof v === "string" && v).map(([, v]) => v)[0] || String(row[idField]);

export async function render(ctx) {
  const { screen, root } = ctx;
  const name = screen.name;
  const title = screen.title || humanize(name);
  const aggregate = Boolean(screen.aggregate) && Array.isArray(screen.bands) && screen.bands.length > 0;
  root.appendChild(card("notice", title, aggregate ? NPDevI18n.t("experience.document.printsOne", "Prints one record as a PDF.")
    : screen.report ? NPDevI18n.t("experience.document.reportHint", "A report: download it as a PDF or as a CSV file.")
    : NPDevI18n.t("experience.document.pdfHint", "Download it as a PDF.")));
  if (!aggregate) {
    root.appendChild(h("div", { class: "actions" }, button(NPDevI18n.t("experience.document.downloadPdf", "Download PDF"), () => download(ctx, pdfUrl(name), name + ".pdf"), true),
      screen.report ? button(NPDevI18n.t("experience.document.downloadCsv", "Download CSV"), () => download(ctx, csvUrl(name), name + ".csv")) : null));
    return;
  }
  const printOne = (id) => download(ctx, aggregatePdfUrl(name, id), name + "-" + id + ".pdf");
  if (ctx.id != null) { root.appendChild(h("div", { class: "actions" }, button(NPDevI18n.t("experience.document.downloadPdf", "Download PDF"), () => printOne(ctx.id), true))); return; }
  const { rows } = await loadRows(ctx.api, Object.assign({}, screen, { where: undefined, orderBy: undefined }), { want: 25, user: ctx.user });
  if (!rows.length) { root.appendChild(card("empty", null, NPDevI18n.t("experience.document.noRecords", "No records to print yet."))); return; }
  const idField = screen.idField || "id";
  root.appendChild(h("ul", { class: "rows" }, rows.map((row) => h("li", { class: "row-item" },
    h("div", { class: "row" }, h("div", { class: "cell title", text: labelOf(row, idField) }), button(NPDevI18n.t("experience.document.downloadPdf", "Download PDF"), () => printOne(row[idField])))))));
}
