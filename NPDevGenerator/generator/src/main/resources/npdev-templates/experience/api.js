// Fetch wrapper for the experience client. Authenticates exactly like the generated shell (shell.js authHeaders /
// redirectToLogin): the token kept in localStorage by the login page, sent as Bearer + X-Api-Key, plus the
// X-NPDev-Request header the server requires for cookie-authenticated writes. A 401 sends the user to the app's
// login page with ?next=. Everything goes through the normal REST API, so access rules apply unchanged.
import { filterRows, listQuery, withQuery } from "./core.js";

const TOKEN_KEYS = ["npdev.shell.token", "npdev.businessUi.apiKey"];
const AUTH_CONFIG_KEY = "npdev.shell.authConfig";
const MANIFEST_URL = "/npdev-business-ui/generated-ui-manifest.json";
const SCAN_PAGES = 5;

export class ApiError extends Error {
  constructor(message, status, payload) { super(message); this.name = "ApiError"; this.status = status; this.payload = payload; }
}

function messageFor(status, payload) {
  if (status === 401) return NPDevI18n.t("experience.api.sessionExpired", "Your session expired. Sign in again.");
  if (status === 403) return NPDevI18n.t("experience.api.noPermission", "You don't have permission to do that.");
  if (status === 404) return NPDevI18n.t("experience.api.notFound", "That record no longer exists.");
  if (status >= 500) return NPDevI18n.t("experience.api.serverProblem", "The server had a problem. Try again.");
  const text = payload && typeof payload === "object" ? (payload.message || payload.error || "") : String(payload || "");
  const prose = typeof text === "string" && text.trim() && !/^[A-Z0-9_.:-]+$/.test(text.trim()) && !/^<(!doctype|html)/i.test(text.trim());
  return prose ? text.trim() : NPDevI18n.t("experience.api.requestFailed", "Request failed (HTTP {status}).", { status });
}

export function createApi(deps) {
  const d = deps || {};
  const fetchFn = d.fetch || ((...args) => globalThis.fetch(...args));
  const loc = d.location || globalThis.location;
  const store = d.storage || (() => { try { return globalThis.localStorage; } catch (e) { return null; } })();
  const read = (key) => { try { return (store && store.getItem(key)) || null; } catch (e) { return null; } };

  const token = () => TOKEN_KEYS.map(read).find(Boolean) || null;

  function headers(json) {
    const out = { "X-NPDev-Request": "1" };
    const locale = read("npdev.ui.locale");
    if (locale) out["X-Tag-locale"] = locale;
    const t = token();
    if (t) { out["Authorization"] = "Bearer " + t; out["X-Api-Key"] = t; }
    if (json) out["Content-Type"] = "application/json";
    return out;
  }

  // jwt apps declare auth.loginPath (default /login.html); apiKey apps declare "" and have no login page.
  async function loginPath() {
    let cached = null;
    try { cached = JSON.parse(read(AUTH_CONFIG_KEY)); } catch (e) { cached = null; }
    if (cached && typeof cached.loginPath === "string") return cached.loginPath;
    try {
      const res = await fetchFn(MANIFEST_URL, { headers: headers(false) });
      const manifest = res.ok ? await res.json() : null;
      if (manifest && manifest.auth && typeof manifest.auth.loginPath === "string") return manifest.auth.loginPath;
    } catch (e) { /* fall through to the jwt default */ }
    // The manifest itself 401s on a bad key, so the app's mode is unknown: only fall back to the jwt default page
    // when it really exists (an apiKey app has none; redirecting there lands on a Whitelabel 404).
    try {
      const probe = await fetchFn("/login.html", { method: "HEAD" });
      if (!probe.ok) return "";
    } catch (e) { return ""; }
    return "/login.html";
  }

  async function unauthorized() {
    const path = await loginPath();
    if (!path || !loc) return;
    const target = "/" + String(path).replace(/^\/+/, "");
    if (loc.pathname !== target) loc.href = target + "?next=" + encodeURIComponent(loc.pathname + (loc.search || ""));
  }

  async function request(method, url, body) {
    const init = { method, headers: headers(body !== undefined) };
    if (body !== undefined) init.body = JSON.stringify(body);
    let res;
    try { res = await fetchFn(url, init); } catch (e) { throw new ApiError(NPDevI18n.t("experience.api.unreachable", "Can't reach the server. Check your connection."), 0, null); }
    const raw = await res.text();
    let payload = raw;
    try { payload = raw ? JSON.parse(raw) : null; } catch (e) { payload = raw; }
    if (!res.ok) {
      if (res.status === 401) await unauthorized();
      throw new ApiError(messageFor(res.status, payload), res.status, payload);
    }
    return payload;
  }

  // After an OAuth round trip the session may exist only as an HttpOnly cookie: ask the server to hand the token back.
  async function adoptCookieSession() {
    if (token() || !store) return;
    try {
      const res = await fetchFn("/api/auth/session-token", { method: "POST", headers: { "X-NPDev-Request": "1" } });
      const body = res.ok ? await res.json() : null;
      if (body && body.token) TOKEN_KEYS.forEach((k) => store.setItem(k, body.token));
    } catch (e) { /* a logged-out visitor just gets a 401 later */ }
  }

  return { token, headers, request, adoptCookieSession,
    get: (url) => request("GET", url), post: (url, body) => request("POST", url, body === undefined ? {} : body) };
}

/** Rows of a screen's dataUrl, narrowed by the screen's `where` (evaluated here over at most SCAN_PAGES x 100 server
 *  rows). {rows, truncated}: truncated = the server had more rows than were scanned or wanted. */
export async function loadRows(api, screen, opts) {
  const o = opts || {};
  const want = o.want || 50;
  const rows = [];
  let truncated = false;
  for (let page = 0; page < SCAN_PAGES; page++) {
    const payload = await api.get(withQuery(screen.dataUrl, listQuery(screen, { page, size: 100, pick: o.pick, user: o.user })));
    const batch = Array.isArray(payload) ? payload : (payload && payload.content) || [];
    const total = payload && payload.totalElements != null ? Number(payload.totalElements) : batch.length;
    rows.push(...filterRows(batch, screen.where, o.user));
    const more = (page + 1) * 100 < total && batch.length > 0;
    if (rows.length >= want) { truncated = rows.length > want || more; break; }
    if (!more) break;
    if (page === SCAN_PAGES - 1) truncated = true;
  }
  return { rows: rows.slice(0, want), truncated };
}

/** One row by id; a row the screen's `where` excludes is "not found" (where only narrows, never widens). */
export async function loadRow(api, screen, id, user) {
  const row = await api.get(screen.dataUrl + "/" + encodeURIComponent(id));
  return filterRows([row], screen.where, user).length ? row : null;
}

/** The row a detail/status screen shows: the id in the URL, else the first/latest/only row of the filtered set. */
export async function resolveRow(api, screen, id, user, defaultPick) {
  if (id != null) return loadRow(api, screen, id, user);
  const pick = screen.pick || defaultPick;
  if (!pick) return undefined;
  const { rows } = await loadRows(api, screen, { want: 1, pick, user });
  return rows[0] || null;
}
