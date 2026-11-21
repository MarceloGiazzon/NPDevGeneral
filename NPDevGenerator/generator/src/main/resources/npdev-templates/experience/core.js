// Pure helpers shared by every experience renderer (FORM P5-T3). No framework, no network.
// The DOM is only touched through h(): data goes in as textContent / text nodes, never as markup.

export const MAX_COLUMNS = 7;
export const MAX_TABS = 5;
export const SUPPORTED_KINDS = ["list", "detail", "form", "status", "inbox", "board", "dashboard", "map", "picker", "document", "custom"];

const ICONS = { car: "\u{1F697}", clock: "\u23F1", list: "\u2630", bell: "\u{1F514}", user: "\u{1F464}", chart: "\u{1F4CA}",
  map: "\u{1F5FA}", home: "\u2302", plus: "\u2795", star: "\u2605", search: "\u{1F50D}", settings: "\u2699" };

// ---- DOM ---------------------------------------------------------------------------------------------------
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === "class") el.className = String(value);
    else if (key === "text") el.textContent = String(value);
    else if (key.startsWith("on")) { if (typeof value === "function") el.addEventListener(key.slice(2).toLowerCase(), value); }
    else if ((key === "href" || key === "src") && /^\s*(javascript|data|vbscript):/i.test(String(value))) continue;
    else el.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    el.appendChild(typeof child === "object" ? child : document.createTextNode(String(child)));
  }
  return el;
}

export function card(kind, title, text) {
  return h("div", { class: "card " + kind, role: kind === "error" ? "alert" : "status" },
    title ? h("strong", { text: title }) : null, text ? h("p", { text }) : null);
}

export const emptyCard = (screen) => card("empty", null, (screen.empty && screen.empty.text) || NPDevI18n.t("experience.core.nothingYet", "Nothing here yet."));
export const notSupportedCard = (screen) => card("notice", NPDevI18n.t("experience.core.notSupportedTitle", "Not supported yet"),
  NPDevI18n.t("experience.core.notSupportedBody", "The \"{kind}\" screen type is not available in this version of the app.", { kind: screen.kind }));

// ---- text, paths, routes -----------------------------------------------------------------------------------
export function humanize(name) {
  const last = String(name == null ? "" : name).split(".").pop();
  const words = last.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").trim().toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : "";
}

export const iconFor = (name) => ICONS[String(name || "").toLowerCase()] || "";
const PATH = /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/;
export const isPath = (text) => PATH.test(String(text));

export function pathValue(row, path) {
  let current = row;
  for (const part of String(path).split(".")) {
    if (current == null || typeof current !== "object") return null;
    current = current[part];
  }
  return current === undefined ? null : current;
}

export function parseHash(hash) {
  const parts = String(hash || "").replace(/^#\/?/, "").split("/").filter(Boolean);
  const decode = (s) => { try { return decodeURIComponent(s); } catch (e) { return s; } };
  return { screen: parts[0] ? decode(parts[0]) : null, id: parts[1] ? decode(parts.slice(1).join("/")) : null };
}

export const buildHash = (screen, id) => "#/" + encodeURIComponent(screen) + (id == null ? "" : "/" + encodeURIComponent(id));

/** The screen a hash points at: {screen, id} or {error}. An empty hash opens the experience's home screen. */
export function resolveRoute(hash, experience, screensByName) {
  const route = parseHash(hash);
  const name = route.screen || experience.home;
  const screen = screensByName[name];
  return screen ? { screen, id: route.id } : { error: NPDevI18n.t("experience.core.noScreen", "There is no screen called \"{name}\".", { name }) };
}

// ---- expressions (the platform's browser engine, loaded by index.html as /npdev-expr.js) -------------------
function engine() {
  const expr = globalThis.npdev && globalThis.npdev.expr;
  if (!expr) throw new Error(NPDevI18n.t("experience.core.engineMissing", "The expression engine is not loaded."));
  return expr;
}

export const scopeVars = (row, form, user) =>
  Object.assign({}, row || {}, { $row: row || {}, $form: form || {}, $user: user || {} });

export function evalExpr(source, vars) {
  const expr = engine();
  return expr.evaluate(source, vars, { functions: expr.stdlib.functions });
}

const isTrue = (v) => v === true || (typeof v === "number" && v !== 0);

/** Visibility is a convenience, never security: the server enforces access. On an expression that cannot be
 *  evaluated here (e.g. a server-side count()), `onError` decides: "hide" (actions) or "show" (screens). */
export function isVisible(source, vars, onError) {
  if (!source) return true;
  try { return isTrue(evalExpr(source, vars)); } catch (e) { return onError !== "hide"; }
}

/** `where` only ever narrows what the server already allows. Throws when the expression cannot be evaluated. */
export const filterRows = (rows, where, user) => (where ? rows.filter((row) => isTrue(evalExpr(where, scopeVars(row, null, user)))) : rows);

// ---- list queries (mirror the generated REST API: page, size<=100, sort, direction) ------------------------
export function parseOrderBy(orderBy) {
  const match = /^\s*([A-Za-z_][A-Za-z0-9_.]*)(?:\s+(asc|desc))?\s*$/i.exec(orderBy || "");
  return match ? { sort: match[1], direction: (match[2] || "asc").toLowerCase() } : {};
}

export function listQuery(screen, opts) {
  const o = opts || {};
  const params = new URLSearchParams();
  params.set("page", String(Math.max(0, o.page || 0)));
  params.set("size", String(Math.max(1, Math.min(100, o.size || 100))));
  const order = parseOrderBy(screen.orderBy);
  if (order.sort) { params.set("sort", order.sort); params.set("direction", order.direction); }
  else if (o.pick === "latest") params.set("direction", "desc");
  // `params` (a query screen): each value is an expression over $user, sent as ?name=value to /api/queries/<name>.
  // One that cannot be evaluated is left out, so the query answers its own "required parameter" error.
  if (screen.params && typeof screen.params === "object") {
    for (const [name, source] of Object.entries(screen.params)) {
      try { const value = evalExpr(String(source), scopeVars(null, null, o.user)); if (value != null) params.set(name, String(value)); } catch (e) { /* left out */ }
    }
  }
  return params.toString();
}

export const withQuery = (url, query) => url + (url.includes("?") ? "&" : "?") + query;

// ---- columns and fields ------------------------------------------------------------------------------------
function guessFormat(type) {
  if (type === "date") return "date";
  if (type === "datetime" || type === "instant" || type === "timestamp") return "datetime";
  if (type === "integer" || type === "decimal" || type === "long" || type === "number") return "number";
  return type === "enum" ? "badge" : "text";
}

export function normColumn(column, meta) {
  const spec = typeof column === "string" ? { value: column } : Object.assign({}, column);
  const info = (meta && meta[spec.value]) || {};
  spec.label = spec.label || info.label || humanize(spec.value);
  spec.format = spec.format || guessFormat(info.type);
  return spec;
}

function columnRank(name, type) {
  if (name === "id") return 9;
  if (/^(name|title|label|code)$/i.test(name)) return 0;
  const byType = { enum: 1, date: 2, datetime: 2, instant: 2, timestamp: 2, decimal: 3, integer: 3, long: 3, string: 4, boolean: 5 };
  return byType[type] == null ? 6 : byType[type];
}

/** At most 7 columns. Without `columns` the screen is not a spreadsheet: pick by field type (label field, status,
 *  dates, numbers, text), never the id. */
export function selectColumns(screen) {
  const meta = screen.fieldMeta || {};
  let columns = screen.columns && screen.columns.length ? screen.columns : null;
  if (!columns) {
    columns = Object.keys(meta).map((name, i) => ({ name, i, rank: columnRank(name, (meta[name] || {}).type) }))
      .sort((a, b) => a.rank - b.rank || a.i - b.i).map((c) => c.name);
  }
  return columns.slice(0, MAX_COLUMNS).map((c) => normColumn(c, meta));
}

export function selectFields(screen) {
  const meta = screen.fieldMeta || {};
  const fields = screen.fields && screen.fields.length ? screen.fields : Object.keys(meta).filter((n) => n !== "id");
  return fields.map((f) => normColumn(f, meta));
}

export function cellValue(row, column, user) {
  if (isPath(column.value)) return pathValue(row, column.value);
  try { return evalExpr(column.value, scopeVars(row, null, user)); } catch (e) { return null; }
}

// ---- formatting --------------------------------------------------------------------------------------------
const num = (v) => (typeof v === "number" ? v : Number(v));

export function relativeTime(value, now) {
  const ms = new Date(value).getTime() - now;
  if (Number.isNaN(ms)) return String(value);
  const mins = Math.round(Math.abs(ms) / 60000);
  const past = ms < 0;
  const hours = Math.round(mins / 60);
  const days = Math.round(mins / 1440);
  if (mins < 1) return NPDevI18n.t("experience.time.now", "just now");
  if (mins < 60) return past ? NPDevI18n.t("experience.time.minAgo", "{n} min ago", { n: mins }) : NPDevI18n.t("experience.time.minIn", "in {n} min", { n: mins });
  if (mins < 1440) return past ? NPDevI18n.t("experience.time.hourAgo", "{n} h ago", { n: hours }) : NPDevI18n.t("experience.time.hourIn", "in {n} h", { n: hours });
  return past ? NPDevI18n.t("experience.time.dayAgo", "{n} d ago", { n: days }) : NPDevI18n.t("experience.time.dayIn", "in {n} d", { n: days });
}

function duration(minutes) {
  const m = Math.round(minutes);
  return m < 60 ? m + " min" : Math.floor(m / 60) + " h " + (m % 60) + " min";
}

/** {text, kind} where kind is "text" | "badge" | "muted". `opts` = {now, locale} for deterministic tests. */
export function formatValue(value, column, opts) {
  const o = opts || {};
  if (value == null || value === "") return { text: "\u2014", kind: "muted" };
  const format = column.format || "text";
  const scaled = column.unitScale ? num(value) * column.unitScale : num(value);
  const asText = (text) => ({ text, kind: "text" });
  if (["money", "number", "distance", "duration", "stars"].includes(format) && Number.isNaN(scaled)) return asText(String(value));
  switch (format) {
    case "money":
      try { return asText(new Intl.NumberFormat(o.locale, { style: "currency", currency: column.currency || "USD" }).format(scaled)); }
      catch (e) { return asText(scaled.toFixed(2)); }
    case "number": return asText(new Intl.NumberFormat(o.locale, { maximumFractionDigits: 2 }).format(scaled));
    case "distance": return asText(Math.round(scaled * 10) / 10 + " km");
    case "duration": return asText(duration(scaled));
    case "stars": { const n = Math.max(0, Math.min(5, Math.round(scaled))); return asText("\u2605".repeat(n) + "\u2606".repeat(5 - n)); }
    case "badge": return { text: String(value), kind: "badge" };
    case "relative-time": return asText(relativeTime(value, o.now == null ? Date.now() : o.now));
    case "date": case "datetime": {
      const d = new Date(value);
      if (Number.isNaN(d.getTime())) return asText(String(value));
      return asText(format === "date" ? d.toLocaleDateString(o.locale) : d.toLocaleString(o.locale));
    }
    default:
      if (typeof value === "boolean") return asText(value ? NPDevI18n.t("experience.core.yes", "Yes") : NPDevI18n.t("experience.core.no", "No"));
      return asText(typeof value === "object" ? JSON.stringify(value) : String(value));
  }
}

export const valueNode = (f) => h("span", { class: f.kind === "badge" ? "badge" : f.kind === "muted" ? "muted" : "", text: f.text });

// ---- forms -------------------------------------------------------------------------------------------------
/** Which control a field type needs (from fieldMeta.type). */
export function inputKind(meta) {
  const m = meta || {};
  const options = m.values || m.enumValues || m.options || null;
  if (options && options.length) return { control: "select", options };
  switch (m.type) {
    case "boolean": return { control: "input", type: "checkbox" };
    case "integer": case "long": return { control: "input", type: "number", step: "1", inputmode: "numeric" };
    case "decimal": case "number": return { control: "input", type: "number", step: "any", inputmode: "decimal" };
    case "date": return { control: "input", type: "date" };
    case "datetime": case "instant": case "timestamp": return { control: "input", type: "datetime-local" };
    case "text": case "longText": return { control: "textarea" };
    default: return { control: "input", type: "text" };
  }
}

/** Raw control value -> {value} or {error}. An empty optional value is null. */
export function coerceFormValue(raw, meta) {
  const type = (meta || {}).type;
  if (type === "boolean") return { value: raw === true || raw === "true" || raw === "on" };
  if (raw == null || String(raw).trim() === "") return { value: null };
  const text = String(raw).trim();
  if (type === "integer" || type === "long") return /^-?\d+$/.test(text) ? { value: parseInt(text, 10) } : { error: NPDevI18n.t("experience.form.wholeNumber", "Enter a whole number.") };
  if (type === "decimal" || type === "number") return Number.isFinite(Number(text)) ? { value: Number(text) } : { error: NPDevI18n.t("experience.form.enterNumber", "Enter a number.") };
  if (type === "datetime" || type === "instant" || type === "timestamp") {
    const d = new Date(text);
    return Number.isNaN(d.getTime()) ? { error: NPDevI18n.t("experience.form.enterDateTime", "Enter a valid date and time.") } : { value: d.toISOString() };
  }
  return { value: text };
}

/** Raw values by field name -> {values, errors} (client-side check; the server's 400 body stays authoritative). */
export function validateForm(fields, raw, fieldMeta) {
  const values = {};
  const errors = {};
  for (const f of fields) {
    const meta = (fieldMeta || {})[f.value] || {};
    const r = coerceFormValue(raw[f.value], meta);
    if (r.error) errors[f.value] = r.error;
    else if (r.value == null && meta.required && meta.type !== "boolean") errors[f.value] = NPDevI18n.t("experience.form.required", "This field is required.");
    else values[f.value] = r.value;
  }
  return { values, errors };
}

const withoutNulls = (obj) => Object.fromEntries(Object.entries(obj).filter(([, v]) => v != null));

/** The JSON body an action posts: its `input` expressions over $row/$form/$user, else the form values as typed. */
export function buildInput(action, scope) {
  const s = scope || {};
  if (!action.input || !Object.keys(action.input).length) return withoutNulls(s.form || {});
  const vars = scopeVars(s.row, s.form, s.user);
  const body = {};
  for (const [key, source] of Object.entries(action.input)) body[key] = evalExpr(source, vars);
  return withoutNulls(body);
}

export const visibleActions = (actions, scope) => (actions || []).filter((a) => a.startUrl &&
  isVisible(a.visibleWhen, scopeVars((scope || {}).row, (scope || {}).form, (scope || {}).user), "hide"));

/** Field-addressable messages from a 4xx body: flow inputValidationErrors, CRUD violations[].path, or a message
 *  naming a known field. Returns [{field, message}]. */
export function fieldErrors(payload, knownFields) {
  const out = [];
  const known = knownFields || [];
  // the server names a flow input "$.customerEmail": drop the leading "$." before taking the first path segment
  const push = (field, message) => { const name = String(field || "").replace(/^\$\.?/, "").split(/[.[]/)[0].trim(); if (name) out.push({ field: name, message: NPDevI18n.server(String(message || NPDevI18n.t("experience.form.invalidValue", "Invalid value."))) }); };
  if (payload && typeof payload === "object") {
    (payload.inputValidationErrors || []).forEach((e) => e && push(e.field, e.message));
    (payload.violations || []).forEach((v) => v && String(v.path || "").split(",").forEach((p) => push(p, v.message)));
    if (!out.length && payload.path) push(payload.path, payload.message);
    if (!out.length && typeof payload.message === "string") {
      const quoted = /'([^']+)'/.exec(payload.message) || /:\s*([A-Za-z_][A-Za-z0-9_]*)\s*$/.exec(payload.message);
      if (quoted && known.includes(quoted[1])) push(quoted[1], payload.message);
    }
  }
  return out;
}

// ---- navigation --------------------------------------------------------------------------------------------
/** experience.navigation (names or {screen,label,icon,badge}) -> entries for screens that exist and are not hidden. */
export function navEntries(experience, screensByName, user) {
  const raw = experience.navigation && experience.navigation.length ? experience.navigation : [experience.home];
  return raw.map((n) => (typeof n === "string" ? { screen: n } : n))
    .filter((n) => screensByName[n.screen] && isVisible(screensByName[n.screen].visibleWhen, scopeVars(null, null, user), "show"))
    .map((n) => ({ screen: n.screen, icon: n.icon || "", badge: n.badge,
      label: n.label || screensByName[n.screen].title || humanize(n.screen) }));
}

/** Bottom tab bar: at most 5 tabs; beyond that the first 4 plus a "More" tab that lists the rest. */
export function tabLayout(entries) {
  return entries.length <= MAX_TABS ? { tabs: entries, more: [] } : { tabs: entries.slice(0, MAX_TABS - 1), more: entries.slice(MAX_TABS - 1) };
}

/** Evaluate with the browser engine: {ok:true, value} or {ok:false} when it cannot (server-only function, bad syntax). */
export function evalLocal(source, vars) {
  try { return { ok: true, value: evalExpr(source, vars) }; } catch (e) { return { ok: false }; }
}

/** A badge count: a positive number (or numeric text), else null = show no badge. */
export function badgeNumber(v) {
  const n = typeof v === "number" ? v : (typeof v === "string" && v.trim() !== "" ? Number(v) : NaN);
  return Number.isFinite(n) && n > 0 ? n : null;
}

/** A navigation badge this client can resolve alone: a number as-is, or an expression the browser engine can
 *  evaluate over the row-less scope. {local:false} = ask the server (count()/sum() are server-only functions). */
export function localBadge(badge, user) {
  if (typeof badge === "number") return { local: true, value: badgeNumber(badge) };
  if (!badge) return { local: true, value: null };
  const r = evalLocal(badge, scopeVars(null, null, user));
  return r.ok ? { local: true, value: badgeNumber(r.value) } : { local: false };
}

export function badgeValue(badge, user) {
  const r = localBadge(badge, user);
  return r.local ? r.value : null;
}
