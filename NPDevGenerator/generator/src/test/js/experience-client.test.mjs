// FORM P5-T3: tests for the experience client modules (npdev-templates/experience/*). No npm dependencies: plain Node,
// a tiny stub DOM and a stub fetch. Run by scripts/quality/check-experience-client.py, which copies the client files
// into a temp dir (so they load as ES modules) and passes:  node <this file> <clientDir> <expr-engine.cjs>
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const [clientDir, enginePath] = process.argv.slice(2);
createRequire(import.meta.url)(enginePath); // sets globalThis.npdev.expr, like /npdev-expr.js does in the page
const load = (name) => import(pathToFileURL(join(clientDir, name)).href);

// ---- stub DOM ------------------------------------------------------------------------------------------------
const trap = (name) => { throw new Error(name + " is forbidden: data must only reach the DOM as text"); };
class Text { constructor(text) { this.text = String(text); } get textContent() { return this.text; } }
class El {
  constructor(tag) { this.tag = tag; this.children = []; this.attrs = {}; this.on = {}; this.className = ""; this.disabled = false; this.value = ""; this.checked = false; }
  get innerHTML() { return trap("innerHTML"); } set innerHTML(v) { trap("innerHTML"); }
  get outerHTML() { return trap("outerHTML"); } set outerHTML(v) { trap("outerHTML"); }
  insertAdjacentHTML() { trap("insertAdjacentHTML"); }
  setAttribute(k, v) { this.attrs[k] = String(v); }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  removeAttribute(k) { delete this.attrs[k]; }
  // like a real DOM: only nodes can be children (an array or a plain value throws the browser's TypeError -- a defect the
  // inbox shipped with: its cells were appended as ONE array, so the page kept the empty card forever)
  appendChild(c) { if (!(c instanceof El || c instanceof Text)) throw new TypeError("Failed to execute 'appendChild' on 'Node': parameter 1 is not of type 'Node'."); this.children.push(c); return c; }
  replaceChildren(...cs) { cs.forEach((c) => { if (!(c instanceof El || c instanceof Text)) throw new TypeError("replaceChildren: parameter is not of type 'Node'."); }); this.children = cs; }
  get textContent() { return this.children.map((c) => c.textContent).join(""); }
  set textContent(v) { this.children = v === "" || v == null ? [] : [new Text(v)]; }
  addEventListener(t, f) { (this.on[t] = this.on[t] || []).push(f); }
  focus() {}
  fire(type) { return Promise.all((this.on[type] || []).map((f) => f({ preventDefault() {} }))); }
  click() { return this.fire("click"); }
}
globalThis.NPDevI18n = { t: (k, f, v) => String(f).replace(/\{(\w+)\}/g, (m, n) => (v && v[n] != null ? v[n] : m)), apply() {}, language: () => "en", use() {} };
globalThis.document = { createElement: (t) => new El(t), createTextNode: (t) => new Text(t), documentElement: new El("html"), title: "", hidden: false };

const walk = (n, out = []) => { if (n instanceof El) { out.push(n); n.children.forEach((c) => walk(c, out)); } return out; };
const byClass = (root, cls) => walk(root).filter((e) => e.className.split(" ").includes(cls));
const byTag = (root, tag) => walk(root).filter((e) => e.tag === tag);
const withText = (root, text) => walk(root).filter((e) => e.textContent === text && e.children.every((c) => c instanceof Text));
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const html = (n) => (n instanceof Text ? esc(n.text) : "<" + n.tag + ">" + n.children.map(html).join("") + "</" + n.tag + ">");

// ---- stub fetch / window -------------------------------------------------------------------------------------
function makeFetch(routes) {
  const calls = [];
  const fn = async (url, init) => {
    calls.push({ url, init: init || {}, body: init && init.body ? JSON.parse(init.body) : undefined });
    const key = (init && init.method ? init.method : "GET") + " " + String(url).split("?")[0];
    const hit = routes[key];
    const r = typeof hit === "function" ? hit(url, init) : hit || { status: 404, body: { message: "no route" } };
    const status = r.status || 200;
    const text = r.body === undefined ? "" : typeof r.body === "string" ? r.body : JSON.stringify(r.body);
    return { ok: status < 400, status, text: async () => text, json: async () => JSON.parse(text) };
  };
  fn.calls = calls;
  return fn;
}
const memory = (init) => { const m = new Map(Object.entries(init || {})); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)) }; };
function makeWin(hash) {
  const handlers = {}; const timers = new Map(); const fns = new Map(); let nextTimer = 1;
  const timeouts = new Map(); let nextTimeout = 1;
  return { timeouts, setTimeout: (f, ms) => { timeouts.set(nextTimeout, { f, ms }); return nextTimeout++; }, clearTimeout: (id) => { timeouts.delete(id); },
    runTimeouts: (ms) => { const due = [...timeouts].filter(([, t]) => t.ms === ms); due.forEach(([id]) => timeouts.delete(id)); return Promise.all(due.map(([, t]) => t.f())); },
    location: { hash: hash || "", pathname: "/x/rider/", search: "", href: "" }, timers, fns,
    addEventListener: (t, f) => { handlers[t] = f; }, dispatch: (t) => handlers[t](),
    setInterval: (f, ms) => { timers.set(nextTimer, ms); fns.set(nextTimer, f); return nextTimer++; },
    clearInterval: (id) => { timers.delete(id); fns.delete(id); },
    fire: (ms) => Promise.all([...fns].filter(([id]) => timers.get(id) === ms).map(([, f]) => f())) };
}

// ---- tiny runner ---------------------------------------------------------------------------------------------
let passed = 0; const failures = [];
async function test(name, fn) { try { await fn(); passed++; } catch (e) { failures.push(name + ": " + (e && e.stack ? e.stack.split("\n").slice(0, 3).join(" | ") : e)); } }

const core = await load("core.js");
const apiMod = await load("api.js");
const appMod = await load("app.js");
const USER = { id: "u1", roles: ["Rider"] };
const META = { id: { type: "uuid" }, name: { type: "string", label: "Name" }, status: { type: "enum" }, createdAt: { type: "datetime" },
  fareCents: { type: "integer" }, f1: { type: "string" }, f2: { type: "string" }, f3: { type: "string" }, f4: { type: "string" },
  f5: { type: "string" }, f6: { type: "string" }, f7: { type: "string" }, f8: { type: "string" }, ok: { type: "boolean" } };

// ---- routing -------------------------------------------------------------------------------------------------
await test("routing: parse/build round trip, home default, unknown screen", () => {
  assert.deepEqual(core.parseHash("#/my-ride/a%2Fb"), { screen: "my-ride", id: "a/b" });
  assert.deepEqual(core.parseHash(""), { screen: null, id: null });
  assert.equal(core.buildHash("trips", "x y"), "#/trips/x%20y");
  const by = { home: { name: "home" } };
  assert.equal(core.resolveRoute("", { home: "home" }, by).screen.name, "home");
  assert.ok(core.resolveRoute("#/nope", { home: "home" }, by).error);
});

await test("queries mirror the REST API: page, size<=100, sort, direction", () => {
  assert.deepEqual(core.parseOrderBy("createdAt desc"), { sort: "createdAt", direction: "desc" });
  assert.deepEqual(core.parseOrderBy("x; drop"), {});
  assert.equal(core.listQuery({ orderBy: "createdAt desc" }, { size: 500 }), "page=0&size=100&sort=createdAt&direction=desc");
  assert.equal(core.listQuery({}, { pick: "latest", page: 2, size: 1 }), "page=2&size=1&direction=desc");
  assert.equal(core.withQuery("/api/concepts/a?x=1", "page=0"), "/api/concepts/a?x=1&page=0");
});

const customMod = await load("custom.js");

await test("custom screen: a registered component is mounted with a narrow frozen api; a component that never registers says so", async () => {
  const seen = [];
  customMod.registry().register("hint", { mount: (el, api) => { seen.push(api); el.appendChild(document.createTextNode("hello " + api.user.id)); } });
  const root = new El("div");
  let reloaded = 0;
  await customMod.render({ screen: { name: "tips", component: "hint" }, root, user: { id: "u1" }, api: { get: (url) => "got " + url }, reload: () => { reloaded++; } });
  assert.equal(seen.length, 1);
  assert.equal(seen[0].screen, "tips");
  assert.equal(seen[0].get("/x"), "got /x");
  seen[0].refresh();
  assert.equal(reloaded, 1);
  assert.equal(Object.isFrozen(seen[0]), true);
  assert.ok(walk(root).some((e) => e.textContent === "hello u1"));
  const pending = customMod.loadRegion("ghost", document);
  const script = document.documentElement.children.at(-1);
  assert.equal(script.attrs.src, "/regions/ghost.js");
  await script.fire("load");
  await assert.rejects(pending, /did not call window\.npdev\.regions\.register/);
  await assert.rejects(customMod.loadRegion("../evil", document), /not valid/);
});

await test("a query screen's params are expressions over $user, sent by name", () => {
  const screen = { params: { account: "$user.id", missing: "$user.nope", fixed: "'z'" } };
  const query = new URLSearchParams(core.listQuery(screen, { user: { id: "driver-1" } }));
  assert.equal(query.get("account"), "driver-1");
  assert.equal(query.get("fixed"), "z");
  assert.equal(query.has("missing"), false, "an expression that yields nothing is left out");
  assert.equal(new URLSearchParams(core.listQuery({}, {})).has("account"), false);
});

// ---- columns -------------------------------------------------------------------------------------------------
await test("columns: capped at 7, auto-pick leads with the label field and never the id", () => {
  const auto = core.selectColumns({ fieldMeta: META }).map((c) => c.value);
  assert.equal(auto.length, 7);
  assert.equal(auto[0], "name");
  assert.ok(!auto.includes("id"));
  assert.equal(core.selectColumns({ columns: Object.keys(META), fieldMeta: META }).length, 7);
  const col = core.selectColumns({ columns: ["status", { value: "fareCents", label: "Fare" }], fieldMeta: META });
  assert.equal(col[0].format, "badge");
  assert.equal(col[0].label, "Status");
  assert.equal(col[1].label, "Fare");
});

await test("formatValue: money/unitScale, stars, duration, distance, badge, empty, boolean, relative", () => {
  const f = (v, c) => core.formatValue(v, c, { locale: "en-US", now: Date.parse("2026-01-01T12:00:00Z") });
  assert.equal(f(1234, { format: "money", currency: "USD", unitScale: 0.01 }).text, "$12.34");
  assert.equal(f(3.6, { format: "stars" }).text, "★★★★☆");
  assert.equal(f(75, { format: "duration" }).text, "1 h 15 min");
  assert.equal(f(1.234, { format: "distance" }).text, "1.2 km");
  assert.deepEqual(f("OFFERED", { format: "badge" }), { text: "OFFERED", kind: "badge" });
  assert.equal(f(null, {}).kind, "muted");
  assert.equal(f(true, {}).text, "Yes");
  assert.equal(f("2026-01-01T11:55:00Z", { format: "relative-time" }).text, "5 min ago");
  assert.equal(f("not a number", { format: "money" }).text, "not a number");
  assert.equal(f({ a: 1 }, {}).text, '{"a":1}');
});

// ---- forms ---------------------------------------------------------------------------------------------------
await test("form: field types -> controls, coercion, required and number errors, form -> input", () => {
  assert.deepEqual(core.inputKind({ type: "integer" }), { control: "input", type: "number", step: "1", inputmode: "numeric" });
  assert.equal(core.inputKind({ type: "boolean" }).type, "checkbox");
  assert.equal(core.inputKind({ type: "date" }).type, "date");
  assert.equal(core.inputKind({ type: "enum", values: ["A", "B"] }).control, "select");
  assert.deepEqual(core.coerceFormValue("42", { type: "integer" }), { value: 42 });
  assert.ok(core.coerceFormValue("4.2", { type: "integer" }).error);
  assert.deepEqual(core.coerceFormValue("", { type: "string" }), { value: null });
  assert.deepEqual(core.coerceFormValue(false, { type: "boolean" }), { value: false });
  const fields = [{ value: "name" }, { value: "qty" }];
  const meta = { name: { type: "string", required: true }, qty: { type: "integer" } };
  const bad = core.validateForm(fields, { name: " ", qty: "x" }, meta);
  assert.equal(bad.errors.name, "This field is required.");
  assert.equal(bad.errors.qty, "Enter a whole number.");
  const good = core.validateForm(fields, { name: "A", qty: "3" }, meta);
  assert.deepEqual(good.values, { name: "A", qty: 3 });
  assert.deepEqual(core.buildInput({ input: { id: "$row.id", status: "'AVAILABLE'", n: "$form.qty" } }, { row: { id: "r1" }, form: { qty: 3 } }),
    { id: "r1", status: "AVAILABLE", n: 3 });
  assert.deepEqual(core.buildInput({}, { form: { a: 1, b: null } }), { a: 1 });
  assert.deepEqual(core.fieldErrors({ inputValidationErrors: [{ field: "qty", message: "bad" }] }), [{ field: "qty", message: "bad" }]);
  assert.deepEqual(core.fieldErrors({ violations: [{ path: "a, b", message: "dup" }] }).map((e) => e.field), ["a", "b"]);
  // the live server names a flow input "$.customerEmail" (found by the P5 real-browser smoke): it must land on the field, not on "$"
  assert.deepEqual(core.fieldErrors({ inputValidationErrors: [{ field: "$.customerEmail", message: "Minimum length is 1" }, { field: "$.lines[0].qty", message: "x" }] }),
    [{ field: "customerEmail", message: "Minimum length is 1" }, { field: "lines", message: "x" }]);
  assert.deepEqual(core.fieldErrors({ message: "Required field is missing for X: name" }, ["name"]).map((e) => e.field), ["name"]);
  assert.deepEqual(core.fieldErrors({ message: "Required field is missing for X: zzz" }, ["name"]), []);
});

// ---- visibility, where, navigation ---------------------------------------------------------------------------
await test("actions: visibleWhen ($row/$form/$user, in), no startUrl, broken expression hides", () => {
  const acts = [
    { label: "Cancel", startUrl: "/a", visibleWhen: "$row.status in ['REQUESTED','ASSIGNED']" },
    { label: "Rate", startUrl: "/b", visibleWhen: "$row.status == 'COMPLETED' && $row.driverStars == null" },
    { label: "NoUrl" }, { label: "Broken", startUrl: "/c", visibleWhen: "count('Ride', true) > 0" }, { label: "Always", startUrl: "/d" }];
  const labels = (row) => core.visibleActions(acts, { row, user: USER }).map((a) => a.label);
  assert.deepEqual(labels({ status: "REQUESTED" }), ["Cancel", "Always"]);
  assert.deepEqual(labels({ status: "COMPLETED", driverStars: null }), ["Rate", "Always"]);
  assert.deepEqual(labels({ status: "COMPLETED", driverStars: 5 }), ["Always"]);
});

await test("where narrows client-side; an expression that cannot be evaluated throws", () => {
  const rows = [{ riderAccount: "u1", n: 1 }, { riderAccount: "u2", n: 2 }];
  assert.deepEqual(core.filterRows(rows, "riderAccount == $user.id", USER), [rows[0]]);
  assert.equal(core.filterRows(rows, "", USER), rows);
  assert.throws(() => core.filterRows(rows, "nosuchfunction(1)", USER));
});

await test("navigation: hidden screens dropped, labels default, 7 entries -> 4 tabs + More", () => {
  const by = Object.fromEntries(["a", "b", "c", "d", "e", "f", "g"].map((n) => [n, { name: n, title: n.toUpperCase() }]));
  by.b.visibleWhen = "false";
  const entries = core.navEntries({ home: "a", navigation: ["a", "b", { screen: "c", label: "See" }, "d", "e", "f", "g", "zz"] }, by, USER);
  assert.deepEqual(entries.map((e) => e.screen), ["a", "c", "d", "e", "f", "g"]);
  assert.equal(entries[0].label, "A");
  assert.equal(entries[1].label, "See");
  const layout = core.tabLayout(entries);
  assert.equal(layout.tabs.length, 4);
  assert.equal(layout.more.length, 2);
  assert.equal(core.tabLayout(entries.slice(0, 5)).more.length, 0);
  assert.equal(core.badgeValue("count('Ride', true)", USER), null);
  assert.equal(core.badgeValue(3, USER), 3);
});

// ---- escaping ------------------------------------------------------------------------------------------------
await test("h(): data stays text; javascript: urls and string handlers are dropped", () => {
  const evil = '<img src=x onerror="alert(1)">';
  const el = core.h("p", { text: evil, onclick: "alert(1)", href: "javascript:alert(1)", title: evil }, evil);
  assert.equal(el.getAttribute("onclick"), null);
  assert.equal(el.getAttribute("href"), null);
  assert.equal(byTag(el, "img").length, 0);
  assert.ok(html(el).includes("&lt;img"));
  assert.throws(() => { el.innerHTML = "x"; });
});

// ---- api -----------------------------------------------------------------------------------------------------
await test("api: auth headers (Bearer + X-Api-Key + X-NPDev-Request), JSON post, error messages", async () => {
  const fetch = makeFetch({ "GET /a": { body: { ok: 1 } }, "POST /b": { body: { done: true } }, "GET /f": { status: 403, body: { message: "FORBIDDEN" } },
    "GET /v": { status: 400, body: { message: "Name is too short" } }, "GET /s": { status: 500, body: "boom" } });
  const api = apiMod.createApi({ fetch, storage: memory({ "npdev.shell.token": "tok" }), location: { pathname: "/x/a/", search: "" } });
  assert.deepEqual(await api.get("/a"), { ok: 1 });
  assert.equal(fetch.calls[0].init.headers["Authorization"], "Bearer tok");
  assert.equal(fetch.calls[0].init.headers["X-Api-Key"], "tok");
  assert.equal(fetch.calls[0].init.headers["X-NPDev-Request"], "1");
  await api.post("/b", { x: 1 });
  assert.equal(fetch.calls[1].init.headers["Content-Type"], "application/json");
  assert.deepEqual(fetch.calls[1].body, { x: 1 });
  await assert.rejects(api.get("/f"), (e) => e.status === 403 && e.message === "You don't have permission to do that.");
  await assert.rejects(api.get("/v"), (e) => e.message === "Name is too short" && e.payload.message === "Name is too short");
  await assert.rejects(api.get("/s"), (e) => e.message === "The server had a problem. Try again.");
  const anon = apiMod.createApi({ fetch, storage: memory(), location: {} });
  await anon.get("/a");
  assert.equal(fetch.calls.at(-1).init.headers["Authorization"], undefined);
});

await test("api: 401 goes to the login page with ?next=; apiKey apps (loginPath '') stay put", async () => {
  const fetch = makeFetch({ "GET /a": { status: 401, body: {} } });
  const loc = { pathname: "/x/rider/", search: "?q=1", href: "" };
  const jwt = apiMod.createApi({ fetch, storage: memory({ "npdev.shell.authConfig": JSON.stringify({ mode: "jwt", loginPath: "login.html" }) }), location: loc });
  await assert.rejects(jwt.get("/a"), (e) => e.status === 401);
  assert.equal(loc.href, "/login.html?next=" + encodeURIComponent("/x/rider/?q=1"));
  const loc2 = { pathname: "/x/rider/", search: "", href: "" };
  const key = apiMod.createApi({ fetch, storage: memory({ "npdev.shell.authConfig": JSON.stringify({ mode: "apiKey", loginPath: "" }) }), location: loc2 });
  await assert.rejects(key.get("/a"));
  assert.equal(loc2.href, "");
  const loc3 = { pathname: "/x/rider/", search: "", href: "" };
  const withPage = makeFetch({ "GET /a": { status: 401, body: {} }, "HEAD /login.html": { status: 200 } });
  await assert.rejects(apiMod.createApi({ fetch: withPage, storage: memory(), location: loc3 }).get("/a")); // no config, page exists: jwt default
  assert.equal(loc3.href, "/login.html?next=" + encodeURIComponent("/x/rider/"));
  // no config (bad key: manifest 401 too) and NO login page (apiKey app): stay put instead of a Whitelabel 404
  const loc4 = { pathname: "/x/rider/", search: "", href: "" };
  await assert.rejects(apiMod.createApi({ fetch, storage: memory(), location: loc4 }).get("/a"), (e) => e.message === "Your session expired. Sign in again.");
  assert.equal(loc4.href, "");
});

await test("api: loadRows pages through the server and applies where; loadRow honours where", async () => {
  const mk = (from, n, owner) => Array.from({ length: n }, (_, i) => ({ id: "r" + (from + i), owner: owner(from + i) }));
  const fetch = makeFetch({ "GET /rows": (url) => {
    const page = Number(new URL(url, "http://x").searchParams.get("page"));
    return { body: { content: mk(page * 100, Math.min(100, 250 - page * 100), (i) => (i % 50 === 0 ? "u1" : "u2")), page, size: 100, totalElements: 250 } };
  }, "GET /rows/r1": { body: { id: "r1", owner: "u2" } }, "GET /rows/r0": { body: { id: "r0", owner: "u1" } } });
  const api = apiMod.createApi({ fetch, storage: memory(), location: {} });
  const screen = { dataUrl: "/rows", where: "owner == $user.id", orderBy: "id desc" };
  const { rows } = await apiMod.loadRows(api, screen, { want: 50, user: USER });
  assert.equal(rows.length, 5); // rows 0,50,100,150,200 over three pages
  assert.ok(fetch.calls[0].url.includes("size=100") && fetch.calls[0].url.includes("sort=id") && fetch.calls[0].url.includes("direction=desc"));
  assert.equal(await apiMod.loadRow(api, screen, "r1", USER), null);
  assert.equal((await apiMod.loadRow(api, screen, "r0", USER)).id, "r0");
  assert.equal(await apiMod.resolveRow(api, { dataUrl: "/rows" }, null, USER, null), undefined);
});

// ---- the whole client against a stub server ------------------------------------------------------------------
const EVIL = '<img src=x onerror="alert(1)">';
const SCREENS = [
  { name: "trips", title: "Trips", kind: "list", concept: "Ride", dataUrl: "/api/concepts/rides", orderBy: "createdAt desc", fieldMeta: META,
    columns: [{ value: "name", label: "Name" }, { value: "status", format: "badge" }, { value: "fareCents", format: "money", currency: "USD", unitScale: 0.01 }], empty: { text: "No trips." } },
  { name: "trip", title: "Trip", kind: "detail", concept: "Ride", dataUrl: "/api/concepts/rides", fieldMeta: META, fields: ["name", "status"] },
  { name: "now", title: "Your ride", kind: "status", concept: "Ride", dataUrl: "/api/concepts/rides", where: "owner == $user.id", pick: "latest", live: true, fieldMeta: META,
    fields: ["name", { value: "status", format: "badge" }],
    actions: [{ label: "Cancel ride", flow: "CancelRide", startUrl: "/api/x/flows/CancelRide/execute", style: "danger", confirm: "Cancel this ride?", input: { id: "$row.id" }, visibleWhen: "$row.status in ['REQUESTED']" },
      { label: "Rate", flow: "Rate", startUrl: "/api/x/flows/Rate/execute", visibleWhen: "$row.status == 'COMPLETED'" }], empty: { text: "No ride in progress." } },
  { name: "request", title: "Where to?", kind: "form", concept: "Ride", fieldMeta: { name: { type: "string", label: "Name", required: true }, fareCents: { type: "integer", label: "Fare" } },
    fields: ["name", "fareCents"], actions: [{ label: "Request ride", flow: "RequestRide", startUrl: "/api/x/flows/RequestRide/execute", style: "primary", then: "now",
      input: { name: "$form.name", fareCents: "$form.fareCents" } }] },
  { name: "offers", title: "Offers", kind: "inbox", concept: "Ride", dataUrl: "/api/concepts/rides" },
  { name: "places", title: "Places", kind: "map", concept: "Ride" }
];
const ME = { experience: { name: "rider", title: "Ride", home: "trips", device: "both", backOffice: true,
  navigation: ["trips", { screen: "now", label: "Current", icon: "clock" }, "request", "offers"] }, screens: SCREENS, user: USER };
const ROWS = [{ id: "r2", name: EVIL, status: EVIL, owner: "u1", fareCents: 1250, createdAt: "2026-01-01T10:00:00Z" },
  { id: "r1", name: "Airport", status: "REQUESTED", owner: "u1", fareCents: 900, createdAt: "2025-12-31T10:00:00Z" }];

async function boot(routes, hash) {
  const root = new El("div");
  const win = makeWin(hash);
  const fetch = makeFetch(Object.assign({ "GET /api/x/me": { body: ME } }, routes));
  const api = apiMod.createApi({ fetch, storage: memory({ "npdev.shell.token": "t" }), location: win.location });
  await appMod.start(root, api, { win, doc: document });
  return { root, win, fetch, main: () => byClass(root, "screen")[0] };
}
const page = { content: ROWS, page: 0, size: 100, totalElements: 2 };

await test("client: shell (top bar, tab bar, back office link) and list with hostile data stays text", async () => {
  const app = await boot({ "GET /api/concepts/rides": { body: page } });
  assert.equal(byClass(app.root, "tab").length, 4);
  assert.equal(byClass(app.root, "navlink").length, 4);
  assert.equal(byClass(app.root, "backoffice").length, 1);
  assert.equal(byClass(app.root, "screen-title")[0].textContent, "Trips");
  assert.equal(byClass(app.main(), "row-item").length, 2);
  assert.equal(byTag(app.root, "img").length, 0, "hostile row data must not become an element");
  assert.ok(html(app.root).includes("&lt;img src=x onerror="));
  const call = app.fetch.calls.find((c) => String(c.url).startsWith("/api/concepts/rides"));
  assert.ok(call.url.includes("page=0") && call.url.includes("size=100") && call.url.includes("sort=createdAt&direction=desc"));
  assert.equal(byClass(app.root, "row")[1].attrs.href, "#/trip/r1"); // row tap -> the detail screen of the same concept
  assert.equal(document.title, "Ride");
});

await test("client: empty list shows the screen's empty text; load errors show an error card", async () => {
  const empty = await boot({ "GET /api/concepts/rides": { body: { content: [], totalElements: 0 } } });
  assert.ok(withText(empty.main(), "No trips.").length === 1);
  const broken = await boot({ "GET /api/concepts/rides": { status: 500, body: "x" } });
  assert.equal(byClass(broken.main(), "error").length, 1);
});

await test("client: detail screen shows the row from the URL id", async () => {
  const app = await boot({ "GET /api/concepts/rides/r1": { body: ROWS[1] } }, "#/trip/r1");
  assert.ok(withText(app.main(), "Airport").length === 1);
});

await test("client: unsupported kind and unknown screen render a card, never crash", async () => {
  const unsupported = await boot({ "GET /api/x/me": { body: Object.assign({}, ME, { screens: SCREENS.concat([{ name: "bench", kind: "workbench" }]) }) } }, "#/bench");
  assert.ok(withText(unsupported.main(), "Not supported yet").length === 1);
  const unknown = await boot({}, "#/nope");
  assert.equal(byClass(unknown.main(), "error").length, 1);
});

await test("client: no experience -> message with a link to the back office; API failure -> same", async () => {
  const none = await boot({ "GET /api/x/me": { body: { experience: null, screens: [], user: USER } } });
  assert.equal(byTag(none.root, "a")[0].attrs.href, "/");
  const down = await boot({ "GET /api/x/me": { status: 500, body: "x" } });
  assert.equal(byClass(down.root, "error").length, 1);
});

await test("client: status screen = latest row filtered by where, live polling, confirm then POST then refresh", async () => {
  const app = await boot({ "GET /api/concepts/rides": { body: { content: [{ id: "r9", name: "Mine", status: "REQUESTED", owner: "u1" }, { id: "r8", name: "Other", status: "REQUESTED", owner: "u2" }], totalElements: 2 } },
    "POST /api/x/flows/CancelRide/execute": { status: 200, body: { status: "OK" } } }, "#/now");
  assert.ok(withText(app.main(), "Mine").length === 1);
  assert.equal(app.win.timers.size, 1, "a live screen polls");
  const buttons = byTag(app.main(), "button");
  assert.deepEqual(buttons.map((b) => b.textContent), ["Cancel ride"], "Rate is hidden: visibleWhen is false for this row");
  assert.ok(app.fetch.calls.some((c) => c.url.includes("direction=desc")), "pick latest asks for newest first");
  await buttons[0].click(); // confirm step first, nothing posted yet
  assert.ok(!app.fetch.calls.some((c) => c.init.method === "POST"));
  assert.ok(withText(app.main(), "Cancel this ride?").length === 1);
  const before = app.fetch.calls.length;
  await withText(app.main(), "Confirm")[0].click();
  const post = app.fetch.calls.find((c) => c.init.method === "POST");
  assert.equal(post.url, "/api/x/flows/CancelRide/execute");
  assert.deepEqual(post.body, { id: "r9" });
  assert.ok(app.fetch.calls.length > before + 1, "refreshes after success");
  await app.win.dispatch("hashchange");
  assert.equal(app.win.timers.size, 1, "polling restarts instead of stacking");
  app.win.location.hash = "#/trips";
  await app.win.dispatch("hashchange");
  assert.equal(app.win.timers.size, 0, "leaving a live screen stops polling");
});

await test("client: form validates, posts typed values, follows `then`, shows the server's field errors", async () => {
  let reply = { status: 422, body: { status: "INPUT_VALIDATION_FAILED", inputValidationErrors: [{ field: "fareCents", code: "x", message: "Too high." }] } };
  const app = await boot({ "POST /api/x/flows/RequestRide/execute": () => reply, "GET /api/concepts/rides": { body: page } }, "#/request");
  const [name, fare] = byTag(app.main(), "input");
  assert.equal(fare.attrs.type, "number");
  const submit = byTag(app.main(), "button")[0];
  assert.equal(submit.attrs.type, "submit");
  await submit.click();
  assert.ok(!app.fetch.calls.some((c) => c.init.method === "POST"), "required field blocks the request");
  assert.ok(byClass(app.main(), "field-error")[0].textContent.length > 0);
  name.value = EVIL; fare.value = "12";
  await submit.click();
  const post = app.fetch.calls.find((c) => c.init.method === "POST");
  assert.deepEqual(post.body, { name: EVIL, fareCents: 12 });
  assert.equal(byClass(app.main(), "field-error")[1].textContent, "Too high.");
  assert.equal(byTag(app.root, "img").length, 0);
  reply = { status: 200, body: { status: "OK" } };
  await submit.click();
  assert.equal(app.win.location.hash, "#/now", "then: opens the named screen");
});

await test("client: an action's success toast survives the refresh that follows it; a stale toast timer never wipes a newer error", async () => {
  let reply = { status: 200, body: { status: "OK" } };
  const app = await boot({ "POST /api/x/flows/RequestRide/execute": () => reply, "GET /api/concepts/rides": { body: page } }, "#/request");
  const banner = () => byClass(app.root, "banner")[0];
  const [name, fare] = byTag(app.main(), "input");
  name.value = "Airport"; fare.value = "12";
  await byTag(app.main(), "button")[0].click(); // ok -> toast "Done." and `then` navigates (hashchange is dispatched by hand here)
  await app.win.dispatch("hashchange");
  assert.equal(banner().textContent, "Done.", "the toast must still be visible after the screen refresh");
  assert.equal(banner().className, "banner ok");
  // the same screen again, this time failing: the first toast's timer (3 s) fires later and must not clear the error
  app.win.location.hash = "#/request"; await app.win.dispatch("hashchange");
  const [name2, fare2] = byTag(app.main(), "input");
  reply = { status: 422, body: { message: "Fare is not allowed." } };
  name2.value = "Airport"; fare2.value = "12";
  await byTag(app.main(), "button")[0].click();
  assert.equal(banner().textContent, "Fare is not allowed.");
  await app.win.runTimeouts(3000);
  assert.equal(banner().textContent, "Fare is not allowed.", "a stale toast timer left the newer error banner alone");
});

// ---- P5-T4: inbox, board, dashboard, badges -------------------------------------------------------------------
const inboxMod = await load("inbox.js");
const boardMod = await load("board.js");
const dashMod = await load("dashboard.js");
const liveMod = await load("live.js");
const svMod = await load("serverval.js");
const OFFER = { label: "Accept", flow: "AcceptOffer", startUrl: "/api/x/flows/AcceptOffer/execute", style: "primary", input: { offerId: "$row.id" } };
const DECLINE = { label: "Decline", flow: "DeclineOffer", startUrl: "/api/x/flows/DeclineOffer/execute", input: { offerId: "$row.id" } };
const T0 = Date.parse("2026-01-01T12:00:00Z");
const fakeTicker = () => { const subs = new Set(); return { subs, subscribe: (f) => { subs.add(f); return () => subs.delete(f); }, tick: (now) => subs.forEach((f) => f(now)) }; };

function makeCtx(screen, routes, extra) {
  const fetch = makeFetch(routes || {});
  const api = apiMod.createApi({ fetch, storage: memory({ "npdev.shell.token": "t" }), location: {} });
  const cleanups = []; const posted = [];
  const byName = Object.fromEntries(((extra && extra.screens) || []).map((s) => [s.name, s]));
  const clock = { now: T0 };
  const ctx = { screen, api, user: USER, id: null, root: new El("div"), clock: () => clock.now, ticker: fakeTicker(), screensByName: byName,
    server: svMod.createServerValues(api, { now: () => clock.now }), detailFor: (s) => s.name + "-detail",
    onCleanup: (f) => cleanups.push(f), toast() {}, reportError() {}, afterAction: async (a) => { posted.push(a.flow); } };
  return Object.assign(ctx, { fetch, cleanups, posted, setNow: (ms) => { clock.now = ms; ctx.ticker.tick(ms); } });
}

await test("countdown: mm:ss, hours, days, expired; deadline from instant, epoch or seconds remaining", () => {
  assert.equal(inboxMod.formatCountdown(20), "00:20");
  assert.equal(inboxMod.formatCountdown(19.2), "00:20");
  assert.equal(inboxMod.formatCountdown(754), "12:34");
  assert.equal(inboxMod.formatCountdown(3900), "1 h 05 min");
  assert.equal(inboxMod.formatCountdown(90000), "1 d 1 h");
  assert.equal(inboxMod.formatCountdown(0), "Expired");
  assert.equal(inboxMod.formatCountdown(-5), "Expired");
  assert.equal(inboxMod.formatCountdown(NaN), "Expired");
  const d = (spec, row) => inboxMod.deadlineOf(spec, row || {}, USER, T0);
  assert.equal(d({ until: "addSeconds(createdAt, 20)" }, { createdAt: "2026-01-01T11:59:50Z" }), T0 + 10000);
  assert.equal(d("deadline", { deadline: "2026-01-01T12:01:00Z" }), T0 + 60000);
  assert.equal(d("n", { n: 30 }), T0 + 30000, "a small number is seconds remaining");
  assert.equal(d("n", { n: T0 + 5000 }), T0 + 5000, "a big number is an epoch in ms");
  assert.equal(d("n", { n: Math.floor(T0 / 1000) + 5 }), T0 + 5000, "an epoch in seconds");
  assert.equal(d("nosuchfunction(1)"), null);
  assert.equal(d("t", { t: "garbage" }), null);
});

await test("inbox: rows, countdown ticks per second, urgent under 10 s, expiry locks the actions and starts no flow", async () => {
  const rows = [{ id: "o1", fare: 1250, createdAt: new Date(T0 - 5000).toISOString(), name: "Airport" }, { id: "o2", fare: 900, createdAt: new Date(T0 - 25000).toISOString(), name: "Mall" }];
  const screen = { name: "offers", kind: "inbox", dataUrl: "/api/concepts/offers", where: "fare > 0", fieldMeta: META,
    columns: [{ value: "name" }, { value: "fare", format: "money", currency: "USD", unitScale: 0.01 }],
    countdown: { until: "addSeconds(createdAt, 20)", label: "Answer within" }, actions: [OFFER, DECLINE], empty: { text: "No offers." } };
  const ctx = makeCtx(screen, { "GET /api/concepts/offers": { body: { content: rows, totalElements: 2 } } });
  await inboxMod.render(ctx);
  const timers = byClass(ctx.root, "countdown");
  assert.equal(timers.length, 2);
  assert.equal(timers[0].attrs.role, "timer");
  assert.ok(timers[0].textContent.includes("Answer within") && timers[0].textContent.includes("00:15"));
  assert.ok(timers[1].className.includes("expired") && timers[1].textContent.includes("Expired"), "already past the deadline at render");
  const second = byClass(ctx.root, "inbox-row")[1];
  assert.ok(byTag(second, "button").every((b) => b.disabled), "expired row: actions disabled");
  const first = byClass(ctx.root, "inbox-row")[0];
  assert.ok(byTag(first, "button").every((b) => !b.disabled));
  assert.equal(ctx.ticker.subs.size, 2, "one subscription per countdown, all on the one shared ticker");
  ctx.setNow(T0 + 6000);
  assert.ok(timers[0].textContent.includes("00:09") && timers[0].className.includes("urgent"));
  ctx.setNow(T0 + 14000);
  assert.ok(timers[0].textContent.includes("00:01"));
  assert.ok(!byTag(first, "button")[0].disabled);
  ctx.setNow(T0 + 15000);
  assert.ok(timers[0].textContent.includes("Expired") && timers[0].className.includes("expired") && !timers[0].className.includes("urgent"));
  assert.ok(byTag(first, "button").every((b) => b.disabled), "expiry disables the row's actions");
  await byTag(first, "button")[0].click();
  assert.equal(ctx.fetch.calls.filter((c) => c.init.method === "POST").length, 0, "no flow is ever started on expiry or on a locked click");
  ctx.cleanups.forEach((f) => f());
  assert.equal(ctx.ticker.subs.size, 0, "unmount unsubscribes");
});

await test("inbox: accept posts the flow with $row input; empty inbox shows the screen's text; no countdown = no timer", async () => {
  const ctx = makeCtx({ name: "offers", kind: "inbox", dataUrl: "/o", columns: ["name"], actions: [OFFER], fieldMeta: META,
    empty: { text: "No offers." } }, { "GET /o": { body: [{ id: "o1", name: "A" }] }, "POST /api/x/flows/AcceptOffer/execute": { body: { status: "OK" } } });
  await inboxMod.render(ctx);
  assert.equal(byClass(ctx.root, "countdown").length, 0);
  assert.equal(ctx.ticker.subs.size, 0);
  await byTag(ctx.root, "button")[0].click();
  assert.deepEqual(ctx.fetch.calls.find((c) => c.init.method === "POST").body, { offerId: "o1" });
  const none = makeCtx({ name: "offers", kind: "inbox", dataUrl: "/o", empty: { text: "No offers." } }, { "GET /o": { body: [] } });
  await inboxMod.render(none);
  assert.equal(withText(none.root, "No offers.").length, 1);
});

await test("inbox: a countdown expression that cannot be evaluated shows no timer and leaves actions enabled", async () => {
  const ctx = makeCtx({ name: "o", kind: "inbox", dataUrl: "/o", columns: ["name"], countdown: "count('X', true)", actions: [OFFER], fieldMeta: META },
    { "GET /o": { body: [{ id: "o1", name: "A" }] } });
  await inboxMod.render(ctx);
  assert.equal(byClass(ctx.root, "countdown").length, 0);
  assert.ok(!byTag(ctx.root, "button")[0].disabled);
});

await test("ticker: ONE shared interval while anyone listens; cleared when the last unsubscribes", () => {
  const win = makeWin();
  const ticker = liveMod.createTicker(win, () => 5);
  const seen = [];
  const a = ticker.subscribe((n) => seen.push("a" + n));
  const b = ticker.subscribe((n) => seen.push("b" + n));
  assert.equal(win.timers.size, 1);
  assert.equal([...win.timers.values()][0], 1000);
  ticker.tick();
  assert.deepEqual(seen, ["a5", "b5"]);
  a();
  assert.equal(win.timers.size, 1);
  b();
  assert.equal(win.timers.size, 0);
  ticker.subscribe(() => {}); ticker.clear();
  assert.equal(win.timers.size, 0);
});

const BMETA = Object.assign({}, META, { status: { type: "enum", values: ["WAITING", "ACTIVE", "DONE"] } });
const MOVE = { label: "Start", flow: "Start", startUrl: "/api/x/flows/Start/execute", style: "move", moveTo: "ACTIVE", input: { id: "$row.id" }, visibleWhen: "$row.status == 'WAITING'" };

await test("board: columns from fieldMeta enum order, unknown and missing values get their own columns, cards show label fields", async () => {
  const rows = [{ id: "1", name: "A", status: "ACTIVE", fareCents: 100 }, { id: "2", name: "B", status: "WAITING" }, { id: "3", name: EVIL, status: "WEIRD" },
    { id: "4", name: "D", status: null }, { id: "5", name: "E", status: "ACTIVE" }];
  const ctx = makeCtx({ name: "b", kind: "board", dataUrl: "/b", groupBy: "status", fieldMeta: BMETA, columns: ["name", "status", "fareCents"], actions: [MOVE] }, { "GET /b": { body: { content: rows, totalElements: 5 } } });
  await boardMod.render(ctx);
  const cols = byClass(ctx.root, "board-col");
  assert.deepEqual(cols.map((c) => c.attrs["aria-label"]), ["WAITING", "ACTIVE", "DONE", "WEIRD", "No value"]);
  assert.deepEqual(cols.map((c) => byClass(c, "badge")[0].textContent), ["1", "2", "0", "1", "1"]);
  assert.equal(byClass(cols[1], "board-card").length, 2);
  assert.equal(byClass(cols[2], "board-card").length, 0);
  assert.equal(withText(cols[2], "Nothing here.").length, 1);
  assert.equal(withText(ctx.root, "Fare cents").length, 5, "other label fields are shown on every card");
  assert.equal(withText(ctx.root, "Status").length, 0, "the group field is not repeated on the card");
  assert.equal(byTag(ctx.root, "img").length, 0, "hostile card text stays text");
  assert.ok(html(ctx.root).includes("&lt;img src=x onerror="));
  assert.deepEqual(boardMod.boardColumns({}, [{ s: "b" }, { s: "a" }, { s: "b" }], "s"), ["b", "a"], "without enum meta: distinct values in first-seen order");
});

await test("board: action buttons are per card and respect visibleWhen; drag and drop only for style move + moveTo", async () => {
  const rows = [{ id: "1", name: "A", status: "WAITING" }, { id: "2", name: "B", status: "ACTIVE" }];
  const plain = { label: "Cancel", flow: "Cancel", startUrl: "/api/x/flows/Cancel/execute", style: "danger", input: { id: "$row.id" } };
  const ctx = makeCtx({ name: "b", kind: "board", dataUrl: "/b", groupBy: "status", fieldMeta: BMETA, columns: ["name"], actions: [MOVE, plain] },
    { "GET /b": { body: rows }, "POST /api/x/flows/Start/execute": { body: { status: "OK" } } });
  await boardMod.render(ctx);
  const cards = byClass(ctx.root, "board-card");
  assert.deepEqual(byTag(cards[0], "button").map((b) => b.textContent), ["Start", "Cancel"]);
  assert.deepEqual(byTag(cards[1], "button").map((b) => b.textContent), ["Cancel"], "Start is not offered for an ACTIVE row");
  assert.equal(cards[0].attrs.draggable, "true");
  assert.equal(cards[1].attrs.draggable, undefined, "no movable action for this row");
  const cols = byClass(ctx.root, "board-col");
  await cards[0].fire("dragstart");
  const evOver = { prevented: false, preventDefault() { this.prevented = true; } };
  cols[1].on.dragover[0](evOver);
  assert.ok(evOver.prevented, "ACTIVE accepts the drop");
  const evNo = { prevented: false, preventDefault() { this.prevented = true; } };
  cols[2].on.dragover[0](evNo);
  assert.ok(!evNo.prevented, "DONE is not a moveTo of any action");
  await cols[1].on.drop[0]({ preventDefault() {} });
  const post = ctx.fetch.calls.find((c) => c.init.method === "POST");
  assert.equal(post.url, "/api/x/flows/Start/execute");
  assert.deepEqual(post.body, { id: "1" });
  // without style move + moveTo nothing is draggable
  const ctx2 = makeCtx({ name: "b", kind: "board", dataUrl: "/b", groupBy: "status", fieldMeta: BMETA, columns: ["name"], actions: [plain, Object.assign({}, MOVE, { style: "primary" })] }, { "GET /b": { body: rows } });
  await boardMod.render(ctx2);
  assert.ok(byClass(ctx2.root, "board-card").every((c) => c.attrs.draggable === undefined));
  // a confirm-guarded move asks first, also when dropped
  const guarded = Object.assign({}, MOVE, { confirm: "Start it?" });
  const ctx3 = makeCtx({ name: "b", kind: "board", dataUrl: "/b", groupBy: "status", fieldMeta: BMETA, columns: ["name"], actions: [guarded] }, { "GET /b": { body: rows } });
  await boardMod.render(ctx3);
  await byClass(ctx3.root, "board-card")[0].fire("dragstart");
  await byClass(ctx3.root, "board-col")[1].on.drop[0]({ preventDefault() {} });
  assert.equal(ctx3.fetch.calls.filter((c) => c.init.method === "POST").length, 0);
  assert.equal(withText(ctx3.root, "Start it?").length, 1);
});

await test("board: without groupBy a notice; empty shows the screen text", async () => {
  const a = makeCtx({ name: "b", kind: "board", dataUrl: "/b" }, { "GET /b": { body: [] } });
  await boardMod.render(a);
  assert.equal(byClass(a.root, "notice").length, 1);
  const b = makeCtx({ name: "b", kind: "board", dataUrl: "/b", groupBy: "status", empty: { text: "Quiet." } }, { "GET /b": { body: [] } });
  await boardMod.render(b);
  assert.equal(withText(b.root, "Quiet.").length, 1);
});

const RSCREENS = [{ name: "rides-all", kind: "list", concept: "Ride", dataUrl: "/rides", fieldMeta: BMETA, columns: ["name", "status", "fareCents"] },
  { name: "places", kind: "map", concept: "Ride" }];

await test("dashboard: tile/table/list/chart/map widgets, span classes, links, ONE batched /api/x/eval", async () => {
  const widgets = [
    { kind: "tile", title: "Waiting", value: "count('Ride', status == 'REQUESTED')" },
    { kind: "tile", title: "Road", value: "count('Ride', status == 'IN_PROGRESS')", screen: "rides-all" },
    { kind: "tile", title: "Local", value: "1 + 2" },
    { kind: "map", title: "Drivers", screen: "places", span: 2 },
    { kind: "table", title: "Latest", screen: "rides-all", span: 3 },
    { kind: "list", title: "Compact", screen: "rides-all" },
    { kind: "chart", title: "By status", screen: "rides-all", span: 9 },
    { kind: "chart", title: "Counts", value: { Waiting: "count('Ride', status == 'REQUESTED')", Road: "count('Ride', status == 'IN_PROGRESS')" } }];
  const ctx = makeCtx({ name: "d", kind: "dashboard", widgets }, {
    "POST /api/x/eval": (u, init) => { const ex = JSON.parse(init.body).expressions; return { body: { values: Object.fromEntries(Object.keys(ex).map((k) => [k, ex[k].includes("REQUESTED") ? 7 : 3])) } }; },
    "GET /rides": { body: [{ id: "r1", name: "A", status: "ACTIVE", fareCents: 5 }, { id: "r2", name: EVIL, status: "ACTIVE" }, { id: "r3", name: "C", status: "DONE" }] } }, { screens: RSCREENS });
  await dashMod.render(ctx);
  const evals = ctx.fetch.calls.filter((c) => c.url === "/api/x/eval");
  assert.equal(evals.length, 1, "every server value of one render shares one request");
  assert.deepEqual(Object.values(evals[0].body.expressions).sort(), ["count('Ride', status == 'IN_PROGRESS')", "count('Ride', status == 'REQUESTED')"], "duplicates are asked once; local ones never");
  const ws = byClass(ctx.root, "widget");
  assert.equal(ws.length, 8);
  assert.deepEqual(ws.map((w) => w.className.split(" ").find((c) => c.startsWith("span-"))), ["span-1", "span-1", "span-1", "span-2", "span-3", "span-1", "span-4", "span-1"]);
  assert.equal(byClass(ws[0], "tile-value")[0].textContent, "7");
  assert.equal(byClass(ws[1], "tile-value")[0].textContent, "3");
  assert.equal(byTag(ws[1], "a")[0].attrs.href, "#/rides-all", "a widget with `screen` links to it");
  assert.equal(byTag(ws[0], "a").length, 0);
  assert.equal(byClass(ws[2], "tile-value")[0].textContent, "3", "a browser-evaluable value needs no server");
  assert.equal(withText(ws[3], "Nothing to show.").length, 1, "a map widget whose screen has no map/dataUrl says so (real maps: see the map tests)");
  assert.equal(byTag(ws[4], "tr").length, 4, "header + 3 rows");
  assert.equal(byTag(ws[4], "a")[0].attrs.href, "#/rides-all", "the title links to the screen");
  assert.equal(byTag(ws[4], "a")[1].attrs.href, "#/rides-all-detail/r1", "a row opens the detail screen");
  assert.equal(byClass(ws[5], "compact-row").length, 3);
  const bars = byClass(ws[6], "bar");
  assert.equal(bars.length, 2, "rows counted per enum value: ACTIVE and DONE");
  assert.equal(bars[0].attrs.style, "width:100%");
  assert.equal(bars[1].attrs.style, "width:50%");
  assert.ok(byTag(ws[6], "div").find((d) => d.attrs.role === "img").attrs["aria-label"].includes("ACTIVE 2"));
  assert.equal(byClass(ws[7], "bar").length, 2);
  assert.equal(byTag(ctx.root, "img").length, 0, "hostile row text stays text");
  assert.ok(html(ctx.root).includes("&lt;img src=x onerror="));
});

await test("dashboard: a widget that fails shows a notice, the others still render; empty dashboard shows empty text", async () => {
  const ctx = makeCtx({ name: "d", kind: "dashboard", widgets: [{ kind: "table", title: "T", screen: "rides-all" }, { kind: "tile", title: "Ok", value: "40 + 2" }, { kind: "gauge", title: "?" }] },
    { "GET /rides": { status: 500, body: "x" } }, { screens: RSCREENS });
  await dashMod.render(ctx);
  assert.equal(byClass(ctx.root, "widget").length, 3);
  assert.equal(withText(ctx.root, "This widget could not load.").length, 1);
  assert.equal(withText(ctx.root, "42").length, 1);
  assert.equal(withText(ctx.root, "Not supported yet.").length, 1);
  const empty = makeCtx({ name: "d", kind: "dashboard", widgets: [], empty: { text: "Nothing to show." } });
  await dashMod.render(empty);
  assert.equal(withText(empty.root, "Nothing to show.").length, 1);
});

await test("server values: one batched request, 5 s cache, dedupe, unknown keys stay undefined, never throws", async () => {
  let clock = 0; let n = 0;
  const fetch = makeFetch({ "POST /api/x/eval": (u, init) => { n++; const ex = JSON.parse(init.body).expressions; return { body: { values: Object.fromEntries(Object.keys(ex).filter((k) => ex[k] !== "missing()").map((k) => [k, ex[k] === "s()" ? "text" : n])) } }; } });
  const api = apiMod.createApi({ fetch, storage: memory(), location: {} });
  const sv = svMod.createServerValues(api, { now: () => clock });
  const [a, b, c, d] = await Promise.all([sv.ask("count('A')"), sv.ask("count('B')"), sv.ask("count('A')"), sv.ask("missing()")]);
  assert.equal(fetch.calls.length, 1);
  assert.equal(Object.keys(fetch.calls[0].body.expressions).length, 3, "A is sent once");
  assert.deepEqual([a, b, c, d], [1, 1, 1, undefined]);
  clock = 4000; assert.equal(await sv.ask("count('A')"), 1); assert.equal(fetch.calls.length, 1, "cached for 5 s");
  clock = 5001; assert.equal(await sv.ask("count('A')"), 2); assert.equal(fetch.calls.length, 2, "expired cache asks again");
  assert.equal(await sv.value("s()", USER), "text");
  assert.deepEqual(await sv.askMany({ x: "count('A')", y: "count('B')" }), { x: 2, y: 4 });
  sv.invalidate(); await sv.ask("count('A')"); assert.equal(fetch.calls.length, 5, "invalidate drops the cache");
});

await test("server values: 404/501 silences the endpoint for good, other failures degrade without throwing", async () => {
  for (const status of [404, 501]) {
    const fetch = makeFetch({ "POST /api/x/eval": { status, body: { message: "nope" } } });
    const sv = svMod.createServerValues(apiMod.createApi({ fetch, storage: memory(), location: {} }), { now: () => 0 });
    assert.equal(await sv.ask("count('A')"), undefined);
    assert.ok(sv.isOff());
    assert.equal(await sv.ask("count('B')"), undefined);
    assert.equal(fetch.calls.length, 1, "no second request once the endpoint said 404/501");
  }
  const flaky = makeFetch({ "POST /api/x/eval": { status: 500, body: "boom" } });
  const sv = svMod.createServerValues(apiMod.createApi({ fetch: flaky, storage: memory(), location: {} }), { now: () => 0 });
  assert.equal(await sv.ask("count('A')"), undefined);
  assert.ok(!sv.isOff(), "a 500 is transient");
  const down = svMod.createServerValues(apiMod.createApi({ fetch: async () => { throw new Error("offline"); }, storage: memory(), location: {} }), { now: () => 0 });
  assert.equal(await down.ask("count('A')"), undefined);
});

const settle = () => new Promise((r) => setTimeout(r, 5));
const NAV_ME = (nav, extra) => ({ body: Object.assign({}, ME, { experience: Object.assign({}, ME.experience, { navigation: nav }) }, extra || {}) });

await test("badges: browser-evaluable ones stay local, count() is asked of the server in ONE request, shown when > 0", async () => {
  const evalBody = (u, init) => { const ex = JSON.parse(init.body).expressions; return { body: { values: Object.fromEntries(Object.entries(ex).map(([k, v]) => [k, v.includes("Offer") ? 4 : 0])) } }; };
  const app = await boot({ "GET /api/x/me": NAV_ME(["trips", { screen: "request", badge: "count('Ride', true)" }, { screen: "now", badge: "1 + 1" },
    { screen: "offers", badge: "count('RideOffer', status == 'OFFERED')" }, { screen: "places", badge: 5 }]), "POST /api/x/eval": evalBody,
    "GET /api/concepts/rides": { body: page } });
  await settle();
  const evals = app.fetch.calls.filter((c) => c.url === "/api/x/eval");
  assert.equal(evals.length, 1, "tab bar + top bar share one request");
  assert.equal(Object.keys(evals[0].body.expressions).length, 2);
  const labels = (cls) => byClass(app.root, cls).map((l) => byClass(l, "badge-count")[0]).map((b) => (b.hidden ? "" : b.textContent));
  assert.deepEqual(labels("navlink"), ["", "", "2", "4", "5"], "0 from the server hides the badge");
  assert.deepEqual([...app.win.timers.values()], [30000], "badges poll while the page is open");
  document.hidden = true; await app.win.fire(30000); document.hidden = false;
  assert.equal(app.fetch.calls.filter((c) => c.url === "/api/x/eval").length, 1, "no polling while the tab is hidden");
});

await test("badges: the endpoint missing (404) degrades silently: no badge, no error, polling stops", async () => {
  const app = await boot({ "GET /api/x/me": NAV_ME(["trips", { screen: "request", badge: "count('Ride', true)" }]), "GET /api/concepts/rides": { body: page } });
  await settle();
  assert.equal(byClass(app.root, "error").length, 0);
  assert.equal(byClass(app.root, "banner")[0].textContent, "");
  assert.ok(byClass(app.root, "badge-count").every((b) => b.hidden === true || "hidden" in b.attrs));
  assert.equal(app.fetch.calls.filter((c) => c.url === "/api/x/eval").length, 1);
  await app.win.fire(30000);
  assert.equal(app.win.timers.size, 0, "polling stops once the endpoint is known to be absent");
  assert.equal(app.fetch.calls.filter((c) => c.url === "/api/x/eval").length, 1);
});

await test("client: inbox/board/dashboard are supported and wired into the router; live inbox polls and its ticker is released on navigation", async () => {
  for (const k of ["inbox", "board", "dashboard"]) assert.ok(core.SUPPORTED_KINDS.includes(k));
  assert.ok(core.SUPPORTED_KINDS.includes("map"));
  const soon = new Date(Date.now() + 60000).toISOString();
  const screens = SCREENS.map((s) => (s.name === "offers" ? Object.assign({}, s, { live: true, columns: ["name"], countdown: "deadline", actions: [OFFER], fieldMeta: META }) : s));
  const app = await boot({ "GET /api/x/me": { body: Object.assign({}, ME, { screens }) }, "GET /api/concepts/rides": { body: [{ id: "o1", name: "Hi", deadline: soon }] } }, "#/offers");
  assert.equal(byClass(app.main(), "countdown").length, 1);
  assert.deepEqual([...app.win.timers.values()].sort((a, b) => a - b), [1000, 15000], "one 1 s ticker + the live poll");
  await app.win.fire(15000); await settle();
  assert.deepEqual([...app.win.timers.values()].sort((a, b) => a - b), [1000, 15000], "a live refresh swaps the countdown without stacking timers");
  app.win.location.hash = "#/trips";
  await app.win.dispatch("hashchange");
  assert.equal(app.win.timers.size, 0, "leaving the inbox clears the shared ticker and the poll");
});

// ---- P5-T5: live updates (server-sent events through fetch) ---------------------------------------------------------
const streamMod = await load("livestream.js");
const encoder = new TextEncoder();
/** A fetch whose /api/live answers are controllable: refuse(status|"network") before a connect; push/end per connection. */
function streamServer() {
  const conns = []; let next = null;
  const fetch = async (url, init) => {
    const c = { url, init, queue: [], waiter: null, ended: false, cancelled: false };
    conns.push(c);
    const refusal = next; next = null;
    if (refusal === "network") throw new Error("offline");
    if (refusal) return { ok: false, status: refusal, text: async () => "", body: null };
    const wake = () => { const w = c.waiter; c.waiter = null; if (w) w(); };
    c.push = (text) => { c.queue.push(encoder.encode(text)); wake(); };
    c.end = () => { c.ended = true; wake(); };
    return { ok: true, status: 200, body: { getReader: () => ({
      read: async () => { for (;;) { if (c.queue.length) return { done: false, value: c.queue.shift() }; if (c.ended || c.cancelled) return { done: true }; await new Promise((r) => { c.waiter = r; }); } },
      cancel: async () => { c.cancelled = true; wake(); } }) } };
  };
  return { conns, fetch, refuse: (status) => { next = status; } };
}
const sseEvent = (obj, name) => "event: " + (name || "changed") + "\ndata: " + (typeof obj === "string" ? obj : JSON.stringify(obj)) + "\n\n";
const liveDoc = () => { const handlers = []; return { hidden: false, handlers, addEventListener: (t, f) => handlers.push(f), removeEventListener: (t, f) => handlers.splice(handlers.indexOf(f), 1),
  setHidden(h) { this.hidden = h; handlers.slice().forEach((f) => f()); } }; };
const tick = async (n = 6) => { for (let i = 0; i < n; i++) await settle(); };
const LIVE_SCREEN = { name: "now", kind: "status", concept: "Ride", live: true };
function liveHarness(screen, extra, refusal) {
  const win = makeWin(); const doc = liveDoc(); const server = streamServer(); let refreshes = 0;
  if (refusal) server.refuse(refusal);
  const api = apiMod.createApi({ fetch: server.fetch, storage: memory({ "npdev.shell.token": "tok" }), location: win.location });
  const env = Object.assign({ win, doc, api, fetch: server.fetch, screensByName: {}, now: () => 0 }, extra);
  const stop = liveMod.watch(env, screen || LIVE_SCREEN, () => { refreshes++; });
  return { win, doc, server, stop, refreshes: () => refreshes };
}

await test("livestream: the SSE parser handles split chunks, CRLF, comments, multiple events and oversized lines", async () => {
  const got = [];
  const feed = (chunks) => { const p = streamMod.createSseParser((e) => got.push(e)); chunks.forEach((c) => p.push(c)); };
  const wire = ": hello\r\n\r\nevent: changed\r\ndata: {\"concept\":\"Ride\",\"id\":\"1\",\"version\":2}\r\n\r\n: heartbeat\n\nevent: changed\ndata: a\ndata: b\n\ndata: bare\n\nretry: 5\nid: 9\n\n";
  feed([wire]);
  assert.deepEqual(got.map((e) => [e.event, e.data]), [["changed", "{\"concept\":\"Ride\",\"id\":\"1\",\"version\":2}"], ["changed", "a\nb"], ["message", "bare"]]);
  const whole = got.slice(); got.length = 0;
  feed(wire.split("")); // one character at a time: every split point, including between CR and LF
  assert.deepEqual(got, whole);
  got.length = 0;
  feed(["event: changed\r", "\ndata: x\r", "\n\r", "\n"]);
  assert.deepEqual(got.map((e) => e.data), ["x"]);
  got.length = 0;
  const p = streamMod.createSseParser((e) => got.push(e));
  p.push("data: " + "A".repeat(streamMod.MAX_LINE + 10)); p.push("\n\n"); p.push("data: ok\n\n");
  assert.deepEqual(got.map((e) => e.data), ["ok"], "an oversized line is dropped, the next event still parses");
  const bomb = streamMod.createSseParser(() => { throw new Error("handler bug"); });
  bomb.push("data: 1\n\ndata: 2\n\n"); // a throwing handler must not break the parser
});

await test("live: a connected stream replaces polling; a burst of notices is one debounced refetch; hostile notices are ignored", async () => {
  const h = liveHarness();
  assert.equal(h.win.timers.size, 1, "polls until the stream is up");
  await tick();
  assert.equal(h.server.conns.length, 1);
  const conn = h.server.conns[0];
  assert.equal(conn.url, "/api/live?concepts=Ride");
  assert.equal(conn.init.headers.Authorization, "Bearer tok", "header auth: why fetch() and not EventSource");
  assert.equal(conn.init.headers.Accept, "text/event-stream");
  assert.equal(h.win.timers.size, 0, "polling stops once connected");
  assert.deepEqual([...h.win.timeouts.values()].map((t) => t.ms), [300], "opening the stream refetches once: a change between the first fetch and the connect was never announced");
  await h.win.runTimeouts(300);
  assert.equal(h.refreshes(), 1, "the catch-up refetch");
  conn.push(": heartbeat\n\n");
  conn.push(sseEvent({ concept: "Other", id: "1", version: 1 }));
  conn.push(sseEvent("{not json"));
  conn.push(sseEvent("null"));
  conn.push(sseEvent({ concept: { x: 1 }, id: "1" }));
  conn.push(sseEvent({ concept: "Ride" }));
  conn.push(sseEvent({ concept: "Ride", id: "1", version: 1 }, "other-event"));
  conn.push(sseEvent("<img src=x onerror=alert(1)>"));
  await tick();
  assert.equal(h.win.timeouts.size, 0, "heartbeats and hostile or foreign notices schedule nothing");
  for (let v = 1; v <= 5; v++) conn.push(sseEvent({ concept: "ride", id: "r1", version: v })); // concept names match case-insensitively
  conn.push(sseEvent({ concept: "Ride", id: "r2", version: 1 }));
  await tick();
  assert.equal(h.refreshes(), 1, "nothing refetches before the debounce window ends");
  assert.deepEqual([...h.win.timeouts.values()].map((t) => t.ms), [300], "one 300 ms timer for the whole burst");
  await h.win.runTimeouts(300);
  assert.equal(h.refreshes(), 2);
  conn.push(sseEvent({ concept: "Ride", id: "r3", version: 1 }));
  await tick();
  await h.win.runTimeouts(300);
  assert.equal(h.refreshes(), 3, "a later notice starts a new window");
  h.stop();
});

await test("live: a refused stream keeps polling (400/401/403/404 never retried; 5xx/429/network retried with backoff 1s..30s)", async () => {
  for (const status of [404, 401, 403, 400]) {
    const h = liveHarness(null, null, status);
    await tick();
    assert.equal(h.win.timers.size, 1, status + ": polling stays");
    assert.equal(h.win.timeouts.size, 0, status + ": no retry");
    assert.equal(h.server.conns.length, 1);
    h.stop();
  }
  const h = liveHarness(null, null, 503);
  await tick();
  assert.equal(h.win.timers.size, 1);
  const delays = [];
  for (let i = 0; i < 7; i++) {
    const [t] = [...h.win.timeouts.values()]; delays.push(t.ms);
    h.server.refuse(i % 2 ? "network" : 429);
    await h.win.runTimeouts(t.ms); await tick();
  }
  assert.deepEqual(delays, [1000, 2000, 4000, 8000, 16000, 30000, 30000], "exponential backoff capped at 30 s");
  assert.equal(h.win.timers.size, 1, "still polling while the stream is down");
  h.stop();
  assert.equal(h.win.timeouts.size, 0);
  assert.equal(h.win.timers.size, 0);
});

await test("live: a dropped stream resumes polling, reconnects, and refetches once to catch up", async () => {
  const h = liveHarness();
  await tick();
  await h.win.runTimeouts(300); // the first-open catch-up
  assert.equal(h.refreshes(), 1);
  h.server.conns[0].end();
  await tick();
  assert.equal(h.win.timers.size, 1, "polling resumes while disconnected");
  assert.deepEqual([...h.win.timeouts.values()].map((t) => t.ms), [1000]);
  await h.win.runTimeouts(1000); await tick();
  assert.equal(h.server.conns.length, 2);
  assert.equal(h.win.timers.size, 0, "polling-free again");
  assert.deepEqual([...h.win.timeouts.values()].map((t) => t.ms), [300], "notices may have been missed: refetch once");
  await h.win.runTimeouts(300);
  assert.equal(h.refreshes(), 2);
  h.stop();
});

await test("live: stopping (navigation) closes the stream and every timer; nothing fires afterwards", async () => {
  const h = liveHarness();
  await tick();
  const conn = h.server.conns[0];
  conn.push(sseEvent({ concept: "Ride", id: "1", version: 1 }));
  await tick();
  h.stop();
  assert.ok(conn.cancelled, "the connection is cancelled");
  assert.equal(h.win.timeouts.size, 0);
  assert.equal(h.win.timers.size, 0);
  assert.equal(h.doc.handlers.length, 0, "the visibility listener is removed");
  conn.push(sseEvent({ concept: "Ride", id: "2", version: 2 }));
  await tick();
  assert.equal(h.win.timeouts.size, 0);
  assert.equal(h.refreshes(), 0);
  assert.equal(h.server.conns.length, 1, "no reconnect after stop");
});

await test("live: a hidden tab pauses the stream; showing it again refetches once and reconnects", async () => {
  const h = liveHarness();
  await tick();
  await h.win.runTimeouts(300); // the first-open catch-up
  assert.equal(h.refreshes(), 1);
  h.doc.setHidden(true);
  assert.ok(h.server.conns[0].cancelled);
  await tick();
  assert.equal(h.win.timeouts.size, 0, "no reconnect while hidden");
  assert.equal(h.server.conns.length, 1);
  h.doc.setHidden(false);
  assert.equal(h.refreshes(), 2, "one refetch on becoming visible");
  await tick();
  assert.equal(h.server.conns.length, 2);
  assert.equal(h.win.timers.size, 0);
  h.stop();
});

await test("live: only live screens watch; a dashboard subscribes to the concepts of its linked widget screens (max 10, names validated)", async () => {
  assert.equal(liveMod.watch({ win: makeWin(), doc: liveDoc() }, { name: "x", live: false }, () => {}), null);
  const byName = { a: { concept: "Ride" }, b: { concept: "Driver" }, c: { concept: "ride" }, d: { concept: "bad name;" } };
  const screen = { concept: "RideOffer", widgets: [{ screen: "a" }, { screen: "b" }, { screen: "c" }, { screen: "d" }, { screen: "nope" }, null, { kind: "tile" }] };
  assert.deepEqual(liveMod.liveConcepts(screen, byName), ["RideOffer", "Ride", "Driver"]);
  const many = Object.fromEntries(Array.from({ length: 15 }, (_, i) => ["s" + i, { concept: "C" + i }]));
  assert.equal(liveMod.liveConcepts({ widgets: Object.keys(many).map((k) => ({ screen: k })) }, many).length, 10);
  const h = liveHarness({ name: "dash", kind: "dashboard", concept: "RideOffer", live: true, widgets: [{ screen: "a" }] }, { screensByName: byName });
  await tick();
  assert.equal(h.server.conns[0].url, "/api/live?concepts=RideOffer%2CRide");
  h.stop();
  const none = liveHarness({ name: "tiles", kind: "dashboard", live: true, widgets: [{ kind: "tile" }] });
  await tick();
  assert.equal(none.server.conns.length, 0, "nothing to subscribe to: polling only");
  assert.equal(none.win.timers.size, 1);
  none.stop();
});

await test("client: a live screen refetches once per debounced notice and keeps ONE stream across its own refreshes", async () => {
  const server = streamServer();
  const rows = { body: { content: [{ id: "r9", name: "Mine", status: "REQUESTED", owner: "u1" }], totalElements: 1 } };
  const base = makeFetch({ "GET /api/x/me": { body: ME }, "GET /api/concepts/rides": rows });
  const fetch = (url, init) => (String(url).startsWith("/api/live") ? server.fetch(url, init) : base(url, init));
  const root = new El("div"); const win = makeWin("#/now");
  const api = apiMod.createApi({ fetch, storage: memory({ "npdev.shell.token": "t" }), location: win.location });
  await appMod.start(root, api, { win, doc: Object.assign(Object.create(document), liveDoc()), fetch });
  await tick();
  assert.equal(server.conns.length, 1);
  assert.equal(win.timers.size, 0, "no polling timer while the stream is up");
  const rideCalls = () => base.calls.filter((c) => String(c.url).startsWith("/api/concepts/rides")).length;
  const before = rideCalls();
  server.conns[0].push(sseEvent({ concept: "Ride", id: "r9", version: 2 }));
  await tick();
  await win.runTimeouts(300); await tick();
  assert.equal(rideCalls(), before + 1, "one refetch through the normal REST API");
  assert.equal(server.conns.length, 1, "the refresh did not reconnect the stream");
  win.location.hash = "#/trips";
  await win.dispatch("hashchange");
  assert.ok(server.conns[0].cancelled, "navigating away closes the stream");
  assert.equal(win.timers.size, 0);
});

await test("files.json lists every client file (the server ships only what it lists) and includes the P5-T4 modules", async () => {
  const fs = await import("node:fs");
  const manifest = JSON.parse(fs.readFileSync(join(clientDir, "files.json"), "utf8"));
  const walkDir = (dir, prefix = "") => fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walkDir(join(dir, e.name), prefix + e.name + "/") : [prefix + e.name]));
  const present = walkDir(clientDir).filter((f) => f !== "files.json" && f !== "package.json").sort();
  assert.deepEqual([...manifest].sort(), present);
  for (const f of ["inbox.js", "board.js", "dashboard.js", "live.js", "serverval.js"]) assert.ok(manifest.includes(f), f);
});

// ---- P5-T6 maps + P5-T7 installable experiences ---------------------------------------------------------------
// A minimal stub of the Leaflet global: only the functions map.js calls. The real library is vendored and hash-pinned.
const mapMod = await load("map.js");
const formMod = await load("form.js");
const detailMod = await load("detail.js");
const made = { maps: [], markers: [], tiles: [], circles: [] };
function makeL() {
  return {
    Icon: { Default: {} },
    map(el, opts) {
      const m = { el, opts, handlers: {}, views: [], fits: [], removed: false,
        on(t, f) { (m.handlers[t] = m.handlers[t] || []).push(f); return m; },
        fire(t, e) { (m.handlers[t] || []).forEach((f) => f(e)); },
        setView(c, z) { m.views.push([c, z]); return m; }, fitBounds(b, o) { m.fits.push(b); return m; },
        invalidateSize() {}, getCenter: () => [0, 0], getZoom: () => 3, remove() { m.removed = true; } };
      made.maps.push(m); return m;
    },
    tileLayer(url, o) { const t = { url, o, addTo(m) { t.map = m; return t; } }; made.tiles.push(t); return t; },
    layerGroup() {
      const g = { layers: [], addTo() { return g; }, clearLayers() { g.layers = []; }, addLayer(l) { g.layers.push(l); return g; },
        removeLayer(l) { g.layers = g.layers.filter((x) => x !== l); } };
      return g;
    },
    marker(ll, o) {
      const mk = { ll, o, handlers: {}, popup: null, bindPopup(c) { mk.popup = c; return mk; }, on(t, f) { mk.handlers[t] = f; return mk; },
        setLatLng(x) { mk.ll = x; return mk; }, getLatLng: () => ({ lat: mk.ll[0], lng: mk.ll[1] }) };
      made.markers.push(mk); return mk;
    },
    circleMarker(ll) { const c = { ll, addTo(g) { g.layers.push(c); return c; } }; made.circles.push(c); return c; }
  };
}
const freshHead = () => { document.head = new El("head"); return document.head; };
const leafletTags = (head) => head.children.filter((e) => e.attrs.src || e.attrs.href);
const loadAll = async (head, type) => { for (const el of head.children) await el.fire(type || "load"); };
const MAP_SPEC = { lat: "lat", lng: "lng", label: "name + ' (' + status + ')'" };
const PLACES = { name: "places", title: "Places", kind: "map", concept: "Driver", dataUrl: "/drivers", fieldMeta: {}, map: MAP_SPEC, empty: { text: "No drivers." } };
const DRIVERS = [{ id: "d1", name: "Ana", status: "FREE", lat: -23.55, lng: -46.63 }, { id: "d2", name: EVIL, status: "BUSY", lat: "-23.56", lng: -46.64 },
  { id: "d3", name: "NoPos", status: "FREE", lat: null, lng: -46.6 }, { id: "d4", name: "Far", status: "FREE", lat: 120, lng: 0 }];
const TILES = { mapTilesUrl: "https://tiles.test/{z}/{x}/{y}.png", mapAttribution: "(c) <b>Tiles</b> test" };
const withExperience = (ctx, exp) => Object.assign(ctx, { experience: exp });

await test("map: a failed library load shows a notice and the next map tries again; no script before a map is shown", async () => {
  const head = freshHead();
  const list = makeCtx({ name: "l", kind: "list", dataUrl: "/rows", columns: ["name"], fieldMeta: META }, { "GET /rows": { body: [{ id: "1", name: "x" }] } });
  await (await load("list.js")).render(list);
  assert.equal(leafletTags(head).length, 0, "screens without a map never load Leaflet");
  const ctx = withExperience(makeCtx(PLACES, { "GET /drivers": { body: DRIVERS } }), TILES);
  await mapMod.render(ctx);
  assert.equal(leafletTags(head).filter((e) => e.attrs.src).length, 1);
  await loadAll(head, "error");
  await settle();
  assert.equal(withText(ctx.root, "The map could not load.").length, 1);
  assert.ok(byClass(ctx.root, "map-list").length === 1, "the place list is still there as a text alternative");
  const again = withExperience(makeCtx(PLACES, { "GET /drivers": { body: DRIVERS } }), TILES);
  await mapMod.render(again);
  assert.equal(leafletTags(head).filter((e) => e.attrs.src).length, 2, "a failed load is retried by the next map");
  await loadAll(head, "error"); await settle(); // leave no load pending for the next test
});

await test("map: Leaflet loads once from the vendored path, markers per row with a per-row label expression, hostile text stays text", async () => {
  const head = freshHead();
  const a = withExperience(makeCtx(PLACES, { "GET /drivers": { body: DRIVERS } }), TILES);
  const b = withExperience(makeCtx(PLACES, { "GET /drivers": { body: DRIVERS } }), TILES);
  await Promise.all([mapMod.render(a), mapMod.render(b)]);
  const tags = leafletTags(head);
  assert.deepEqual(tags.map((e) => e.attrs.src || e.attrs.href).sort(), ["/x/vendor/leaflet/leaflet.css", "/x/vendor/leaflet/leaflet.js"], "one css + one script for two maps");
  assert.equal(globalThis.L, undefined, "nothing runs before the script has loaded");
  globalThis.L = makeL();
  await loadAll(head);
  await settle();
  assert.equal(globalThis.L.Icon.Default.imagePath, "/x/vendor/leaflet/images/", "marker images come from the vendored folder");
  const [first, second] = made.maps.slice(-2);
  assert.ok(first && second);
  const markers = made.markers.slice(-4);
  assert.equal(markers.length, 4, "2 usable rows x 2 maps: a null, an out-of-range and a missing position are skipped");
  assert.equal(markers[0].o.title, "Ana (FREE)");
  assert.equal(markers[1].o.title, EVIL + " (BUSY)", "the label is an expression evaluated per row; hostile text only as a title");
  assert.deepEqual(markers[1].ll, [-23.56, -46.64], "a numeric string is accepted");
  assert.ok(html(markers[0].popup).includes("<strong>Ana (FREE)</strong>"));
  assert.ok(html(markers[1].popup).includes("&lt;img src=x onerror="), "popup content is a DOM node with text");
  assert.equal(byTag(markers[1].popup, "img").length, 0);
  assert.equal(first.fits.length, 1, "two points: fitBounds");
  assert.equal(made.tiles.at(-1).url, TILES.mapTilesUrl);
  assert.equal(made.tiles.at(-1).o.attribution, "(c) &lt;b&gt;Tiles&lt;/b&gt; test", "attribution reaches Leaflet escaped (it renders strings as markup)");
  assert.equal(made.tiles.at(-1).o.maxZoom, 19);
  assert.ok(byClass(a.root, "np-map").length === 1 && byClass(a.root, "map-list")[0].children.length === 2);
  assert.equal(byTag(a.root, "img").length, 0);
  a.cleanups.forEach((f) => f());
  assert.equal(first.removed, true, "leaving the screen removes the Leaflet map");
});

await test("map: unsafe tile URLs are ignored, attribution is always passed, a single row centres on it, no rows = the empty text", async () => {
  freshHead(); globalThis.L = makeL();
  const bad = withExperience(makeCtx(Object.assign({}, PLACES, { map: Object.assign({}, MAP_SPEC, { tilesUrl: "javascript:alert(1)" }) }), { "GET /drivers": { body: [DRIVERS[0]] } }), { mapTilesUrl: "data:text/plain,x" });
  const tilesBefore = made.tiles.length;
  await mapMod.render(bad); await settle();
  assert.equal(made.tiles.length, tilesBefore, "no tile layer for a non-http(s) template");
  assert.deepEqual(made.maps.at(-1).views.at(-1), [[-23.55, -46.63], 15], "one marker: setView, not fitBounds");
  const own = withExperience(makeCtx(Object.assign({}, PLACES, { map: Object.assign({}, MAP_SPEC, { tilesUrl: "/t/{z}/{x}/{y}.png" }) }), { "GET /drivers": { body: [DRIVERS[0]] } }), TILES);
  await mapMod.render(own); await settle();
  assert.equal(made.tiles.at(-1).url, "/t/{z}/{x}/{y}.png", "a screen's own tile template wins");
  assert.equal(made.tiles.at(-1).o.attribution, "Map tiles", "never an empty attribution");
  const empty = makeCtx(PLACES, { "GET /drivers": { body: [DRIVERS[2]] } });
  await mapMod.render(empty);
  assert.equal(withText(empty.root, "No drivers.").length, 1);
  assert.equal(byClass(empty.root, "np-map").length, 0);
});

await test("map: the browser is asked for the user's position only after a tap on 'Use my location'", async () => {
  freshHead(); globalThis.L = makeL();
  let asked = 0; let onOk; let onFail;
  const navigator = { geolocation: { getCurrentPosition: (ok, fail) => { asked++; onOk = ok; onFail = fail; } } };
  const errors = [];
  const ctx = withExperience(makeCtx(Object.assign({}, PLACES, { map: Object.assign({}, MAP_SPEC, { center: "me" }) }), { "GET /drivers": { body: DRIVERS } }), TILES);
  ctx.navigator = navigator; ctx.reportError = (m) => errors.push(m);
  await mapMod.render(ctx); await settle();
  assert.equal(asked, 0, "nothing is asked on load");
  const button = withText(ctx.root, "Use my location")[0];
  assert.ok(button, "a visible button offers it");
  await button.click();
  assert.equal(asked, 1);
  onOk({ coords: { latitude: 10.5, longitude: 20.5 } });
  await settle();
  assert.deepEqual(made.maps.at(-1).views.at(-1), [[10.5, 20.5], 15]);
  assert.equal(made.circles.length >= 1, true);
  await button.click(); onFail(new Error("denied"));
  assert.deepEqual(errors, ["Your location is not available."]);
  const plain = makeCtx(PLACES, { "GET /drivers": { body: DRIVERS } }); plain.navigator = navigator;
  await mapMod.render(plain);
  assert.equal(withText(plain.root, "Use my location").length, 0, "only a map that asks for center: me has the button");
});

await test("map: a form with pickPoints fills the lat/lng inputs on tap and drag, the values reach the flow, typing moves the marker", async () => {
  freshHead(); globalThis.L = makeL();
  const meta = { pickupLat: { type: "decimal" }, pickupLng: { type: "decimal" }, dropoffLat: { type: "decimal" }, dropoffLng: { type: "decimal" } };
  const screen = { name: "ride-now", kind: "form", fieldMeta: meta, fields: ["pickupLat", "pickupLng", "dropoffLat", "dropoffLng"],
    map: { pickPoints: [{ name: "pickup", label: "Pick-up", lat: "pickupLat", lng: "pickupLng" }, { name: "dropoff", label: "Drop-off", lat: "dropoffLat", lng: "dropoffLng" }] },
    actions: [{ label: "Go", flow: "RequestRide", startUrl: "/api/x/flows/RequestRide/execute", style: "primary",
      input: { pickupLat: "$form.pickupLat", pickupLng: "$form.pickupLng", dropoffLat: "$form.dropoffLat", dropoffLng: "$form.dropoffLng" } }] };
  const ctx = withExperience(makeCtx(screen, { "POST /api/x/flows/RequestRide/execute": { body: { status: "OK" } } }), TILES);
  formMod.render(ctx); await settle();
  const inputs = byTag(ctx.root, "input");
  assert.equal(inputs.length, 4, "the numeric inputs stay (keyboard accessible)");
  const map = made.maps.at(-1);
  const pressed = () => byClass(ctx.root, "pick").map((b) => b.attrs["aria-pressed"]);
  assert.deepEqual(pressed(), ["true", "false"]);
  map.fire("click", { latlng: { lat: -23.5, lng: -46.6 } });
  assert.equal(inputs[0].value, "-23.500000"); assert.equal(inputs[1].value, "-46.600000");
  assert.deepEqual(pressed(), ["false", "true"], "after the pick-up the drop-off is the next one to set");
  map.fire("click", { latlng: { lat: -23.6, lng: -46.7 } });
  await settle();
  assert.equal(inputs[2].value, "-23.600000");
  const markers = made.markers.slice(-2);
  assert.ok(markers.every((m) => m.o.draggable === true));
  markers[0].ll = [1.5, 2.5]; markers[0].handlers.dragend();
  assert.equal(inputs[0].value, "1.500000"); assert.equal(inputs[1].value, "2.500000", "dragging a marker updates the fields");
  inputs[2].value = "5"; await inputs[2].fire("change"); await settle();
  assert.deepEqual(markers[1].ll, [5, -46.7], "typing in an input moves its marker");
  await byTag(ctx.root, "button").find((b) => b.attrs.type === "submit").click();
  const post = ctx.fetch.calls.find((c) => c.init.method === "POST");
  assert.deepEqual(post.body, { pickupLat: 1.5, pickupLng: 2.5, dropoffLat: 5, dropoffLng: -46.7 }, "the picked values are what the flow gets");
});

await test("map: a status/detail screen with `map` shows one marker for its row; a dashboard map widget draws the linked screen's rows and stays tappable", async () => {
  freshHead(); globalThis.L = makeL();
  const status = { name: "now", kind: "status", dataUrl: "/rides", fieldMeta: META, fields: ["name"], pick: "latest", map: { lat: "plat", lng: "plng", label: "'Pick-up'" } };
  const ctx = withExperience(makeCtx(status, { "GET /rides": { body: [{ id: "r1", name: "Trip", plat: 1, plng: 2 }] } }), TILES);
  await detailMod.render(ctx, "latest"); await settle();
  assert.equal(made.markers.at(-1).o.title, "Pick-up");
  assert.deepEqual(made.markers.at(-1).ll, [1, 2]);
  const noPos = withExperience(makeCtx(status, { "GET /rides": { body: [{ id: "r1", name: "Trip" }] } }), TILES);
  await detailMod.render(noPos, "latest");
  assert.equal(byClass(noPos.root, "np-map").length, 0, "a row without a position shows no map");

  const dash = withExperience(makeCtx({ name: "d", kind: "dashboard", widgets: [{ kind: "map", title: "Drivers", screen: "places" }, { kind: "map", title: "Bare" }] },
    { "GET /drivers": { body: DRIVERS } }, { screens: [PLACES] }), TILES);
  await dashMod.render(dash); await settle();
  const ws = byClass(dash.root, "widget");
  assert.equal(byClass(ws[0], "np-map").length, 1);
  assert.deepEqual(byTag(ws[0], "a").map((a) => a.attrs.href), ["#/places"], "only the title links: the map itself must stay tappable");
  assert.equal(made.markers.slice(-2).length, 2);
  assert.equal(withText(ws[1], "Nothing to show.").length, 1);
});

await test("pwa: mobile and both experiences get the manifest link, theme colour and home-screen tags; a web one gets none", async () => {
  const run = async (exp) => {
    const head = freshHead();
    const me = Object.assign({}, ME, { experience: Object.assign({}, ME.experience, exp) });
    await boot({ "GET /api/x/me": { body: me }, "GET /api/concepts/rides": { body: page } });
    return head.children;
  };
  const tags = await run({ device: "mobile", themeColor: "#123456" });
  const by = (name, key) => tags.find((e) => e.attrs[key] === name);
  assert.equal(by("manifest", "rel").attrs.href, "/x/rider/manifest.webmanifest");
  assert.equal(by("theme-color", "name").attrs.content, "#123456");
  assert.equal(by("apple-mobile-web-app-capable", "name").attrs.content, "yes");
  assert.equal(by("apple-touch-icon", "rel").attrs.href, "/x/rider/icon-192.png");
  assert.equal((await run({ device: "both" })).filter((e) => e.attrs.rel === "manifest").length, 1);
  const fallback = await run({ device: "both", themeColor: "red" });
  assert.equal(fallback.find((e) => e.attrs.name === "theme-color").attrs.content, "#0b5fff", "an unusable colour falls back to the shell default");
  assert.equal((await run({ device: undefined })).filter((e) => e.attrs.rel === "manifest").length, 1, "device defaults to both");
  assert.equal((await run({ device: "web" })).length, 0, "a web experience is not installable");
});

// ---- picker (P5-T9a: the old selectors[]) ------------------------------------------------------------------------
const pickerMod = await load("picker.js");
const memStore = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), map: m }; };
const CATALOG = [{ id: "c3", sku: "C-3", displayName: "Cog", discontinued: false }, { id: "c1", sku: "A-1", displayName: "Axle", discontinued: false },
  { id: "c2", sku: "B-2", displayName: "Bolt", discontinued: true }, { id: "c4", sku: "D-4", displayName: "Dowel", discontinued: false }];
const PICKER = { name: "WidgetCatalogPicker", kind: "picker", concept: "WidgetCatalogEntry", dataUrl: "/api/concepts/catalog", multiSelect: true,
  filters: ["sku", "displayName"], columns: ["sku", "displayName"], orderBy: ["sku"], where: "discontinued == false", returnMapping: { sku: "sku" },
  fieldMeta: { id: { type: "uuid" }, sku: { type: "string" }, displayName: { type: "string" }, discontinued: { type: "boolean" } } };
function pickerCtx(screen, id) {
  const ctx = makeCtx(screen, { "GET /api/concepts/catalog": { body: CATALOG } }, { screens: [{ name: "place-order" }] });
  const went = []; const toasts = [];
  return Object.assign(ctx, { id, storage: memStore(), went, toasts, go: (hash) => went.push(hash), toast: (t) => toasts.push(t), clock: () => 1234 });
}
const rowButtons = (ctx) => byClass(ctx.root, "row");
const button = (ctx, label) => byTag(ctx.root, "button").find((b) => b.textContent === label);

await test("picker: it is a supported kind, so app.js renders it instead of the not-supported card", () => {
  assert.ok(core.SUPPORTED_KINDS.includes("picker"));
});

await test("picker: predicate (&& of ==/!=, quoted or bare), sort (list, direction), returnMapping, takePicked reads once", () => {
  const p = pickerMod.predicate("discontinued == false && sku != 'B-2'");
  assert.deepEqual(CATALOG.filter(p).map((r) => r.id), ["c3", "c1", "c4"]);
  assert.equal(pickerMod.predicate("")({}), true);
  assert.equal(pickerMod.predicate("x == $root.id")({ x: 1 }), true, "a $root reference is ignored, never guessed");
  assert.deepEqual(pickerMod.sortRows(CATALOG, ["sku"]).map((r) => r.sku), ["A-1", "B-2", "C-3", "D-4"]);
  assert.deepEqual(pickerMod.sortRows(CATALOG, "-sku").map((r) => r.sku), ["D-4", "C-3", "B-2", "A-1"]);
  assert.deepEqual(pickerMod.sortRows(CATALOG, "sku desc").map((r) => r.sku), ["D-4", "C-3", "B-2", "A-1"]);
  assert.equal(pickerMod.sortRows(CATALOG, undefined), CATALOG);
  assert.deepEqual(pickerMod.returned(CATALOG[0], { code: "sku", name: "displayName" }, "id"), { code: "C-3", name: "Cog" });
  assert.deepEqual(pickerMod.returned(CATALOG[0], {}, "id"), { id: "c3" });
  const store = memStore();
  store.setItem("npdev.picker.P", JSON.stringify({ picked: [{ a: 1 }], at: 1 }));
  assert.deepEqual(pickerMod.takePicked(store, "P"), [{ a: 1 }]);
  assert.equal(pickerMod.takePicked(store, "P"), null, "taken once");
  store.setItem("npdev.picker.P", "not json");
  assert.equal(pickerMod.takePicked(store, "P"), null);
  assert.equal(pickerMod.takePicked(null, "P"), null);
});

await test("picker: multiSelect lists the narrowed, ordered rows; Done hands back the mapped picks and returns to the opener", async () => {
  const ctx = pickerCtx(PICKER, "place-order");
  await pickerMod.render(ctx);
  assert.equal(rowButtons(ctx).length, 3, "the discontinued row is excluded by where");
  assert.deepEqual(rowButtons(ctx).map((b) => b.textContent.slice(0, 3)), ["A-1", "C-3", "D-4"], "ordered by sku");
  assert.equal(button(ctx, "Done").disabled, true, "nothing picked yet");
  assert.equal(rowButtons(ctx)[0].attrs.role, "checkbox");
  await rowButtons(ctx)[0].click();
  await rowButtons(ctx)[1].click();
  assert.deepEqual(rowButtons(ctx).map((b) => b.attrs["aria-checked"]), ["true", "true", "false"], "several rows stay picked");
  await rowButtons(ctx)[0].click();
  assert.equal(rowButtons(ctx)[0].attrs["aria-checked"], "false", "a second click unpicks");
  await button(ctx, "Done").click();
  assert.deepEqual(ctx.went, ["#/place-order"]);
  assert.deepEqual(JSON.parse(ctx.storage.getItem("npdev.picker.WidgetCatalogPicker")), { picked: [{ sku: "C-3" }], at: 1234 });
});

await test("picker: single select replaces the pick; search narrows by the filters fields; Cancel returns without a hand-off", async () => {
  const ctx = pickerCtx(Object.assign({}, PICKER, { multiSelect: false }), "place-order");
  await pickerMod.render(ctx);
  assert.equal(rowButtons(ctx)[0].attrs.role, "radio");
  await rowButtons(ctx)[0].click();
  await rowButtons(ctx)[1].click();
  assert.deepEqual(rowButtons(ctx).map((b) => b.attrs["aria-checked"]), ["false", "true", "false"], "exactly one stays picked");
  const search = byTag(ctx.root, "input")[0];
  await search.on.input[0]({ target: { value: "dow" } });
  assert.equal(rowButtons(ctx).length, 1, "search by displayName");
  assert.equal(rowButtons(ctx)[0].textContent.startsWith("D-4"), true);
  await search.on.input[0]({ target: { value: "zzz" } });
  assert.equal(rowButtons(ctx).length, 0);
  assert.equal(withText(ctx.root, "No match.").length, 1);
  await button(ctx, "Cancel").click();
  assert.deepEqual(ctx.went, ["#/place-order"]);
  assert.equal(ctx.storage.getItem("npdev.picker.WidgetCatalogPicker"), null, "Cancel hands nothing back");
});

await test("picker: opened directly it does not crash, keeps the pick and says so; no rows shows the empty card; no storage is tolerated", async () => {
  const direct = pickerCtx(PICKER, null);
  await pickerMod.render(direct);
  assert.equal(button(direct, "Cancel"), undefined, "nothing to cancel back to");
  await rowButtons(direct)[1].click();
  await button(direct, "Done").click();
  assert.deepEqual(direct.went, []);
  assert.deepEqual(direct.toasts, ["Selection kept."]);
  assert.deepEqual(pickerMod.takePicked(direct.storage, "WidgetCatalogPicker"), [{ sku: "C-3" }]);
  const unknown = pickerCtx(PICKER, "no-such-screen");
  await pickerMod.render(unknown);
  await rowButtons(unknown)[0].click();
  await button(unknown, "Done").click();
  assert.deepEqual(unknown.went, [], "an opener the experience does not know is treated as no opener");
  const none = makeCtx(Object.assign({}, PICKER, { empty: { text: "No catalog." } }), { "GET /api/concepts/catalog": { body: [] } });
  await pickerMod.render(none);
  assert.equal(withText(none.root, "No catalog.").length, 1);
  const bare = pickerCtx(Object.assign({}, PICKER, { returnMapping: undefined, filters: undefined, orderBy: undefined, where: undefined }), null);
  bare.storage = null;
  await pickerMod.render(bare);
  assert.equal(byTag(bare.root, "input").length, 0, "no filters, no search box");
  assert.equal(rowButtons(bare).length, 4);
  await rowButtons(bare)[0].click();
  await button(bare, "Done").click();
  assert.deepEqual(bare.toasts, ["Selection kept."], "storage blocked: still no crash");
});

// ---- document (P5-T9b, the old documents[]) ------------------------------------------------------------------
const documentMod = await load("document.js");
function documentCtx(screen, id, status) {
  const ctx = makeCtx(screen, { "GET /api/concepts/orders": { body: [{ id: "o1", number: "SO-1", total: 5 }, { id: "o2", total: 7 }] } }, {});
  const downloads = []; const errors = []; const saved = [];
  ctx.fetch = async (url, init) => { downloads.push({ url, init }); const s = typeof status === "function" ? status(url) : status || 200; return { ok: s < 400, status: s, blob: async () => "blob:" + url }; };
  return Object.assign(ctx, { id, downloads, errors, saved, reportError: (m) => errors.push(m), saveFile: (blob, name) => saved.push([blob, name]) });
}
const ONE = { name: "WidgetOrderSummary", kind: "document", concept: "WidgetOrder", title: "Order summary", dataUrl: "/api/concepts/orders" };

await test("document: kind is supported; a one-record document offers only the PDF, fetched with the app's auth headers", async () => {
  assert.ok(core.SUPPORTED_KINDS.includes("document"));
  const ctx = documentCtx(ONE, null);
  await documentMod.render(ctx);
  assert.equal(withText(ctx.root, "Order summary").length, 1);
  assert.deepEqual(byTag(ctx.root, "button").map((b) => b.textContent), ["Download PDF"]);
  await button(ctx, "Download PDF").click();
  assert.equal(ctx.downloads.length, 1);
  assert.equal(ctx.downloads[0].url, "/api/documents/WidgetOrderSummary/render.pdf");
  assert.equal(ctx.downloads[0].init.headers.Authorization, "Bearer t", "a link cannot send the token; the fetch does");
  assert.equal(ctx.downloads[0].init.headers["X-NPDev-Request"], "1");
  assert.deepEqual(ctx.saved, [["blob:/api/documents/WidgetOrderSummary/render.pdf", "WidgetOrderSummary.pdf"]]);
  assert.equal(ctx.errors.length, 0);
});

await test("document: a report also offers the CSV; the title falls back to the humanized name", async () => {
  const ctx = documentCtx({ name: "WidgetCatalogPriceReport", kind: "document", concept: "WidgetCatalogEntry", report: true }, null);
  await documentMod.render(ctx);
  assert.equal(withText(ctx.root, "Widget catalog price report").length, 1);
  assert.deepEqual(byTag(ctx.root, "button").map((b) => b.textContent), ["Download PDF", "Download CSV"]);
  await button(ctx, "Download CSV").click();
  assert.equal(ctx.downloads[0].url, "/api/documents/WidgetCatalogPriceReport/render.csv");
  assert.equal(ctx.saved[0][1], "WidgetCatalogPriceReport.csv");
  assert.equal(documentMod.pdfUrl("a b"), "/api/documents/a%20b/render.pdf");
});

await test("document: a refused or failed download is reported in plain words, never saved", async () => {
  for (const [status, text] of [[401, "Your session expired. Sign in again."], [403, "You don't have permission to print this."],
    [404, "That document is not available."], [413, "Too many rows for one document. Narrow what it covers."], [500, "The document could not be created (HTTP 500)."]]) {
    const ctx = documentCtx(ONE, null, status);
    await documentMod.render(ctx);
    await button(ctx, "Download PDF").click();
    assert.deepEqual(ctx.errors, [text]);
    assert.equal(ctx.saved.length, 0);
  }
  const down = documentCtx(ONE, null);
  down.fetch = async () => { throw new Error("offline"); };
  await documentMod.render(down);
  await button(down, "Download PDF").click();
  assert.deepEqual(down.errors, ["Can't reach the server. Check your connection."]);
});

await test("document: an aggregate-bound document prints one record: the id in the route, or one button per record of its concept", async () => {
  const AGG = Object.assign({}, ONE, { name: "WidgetOrderInvoice", aggregate: "WidgetOrderAggregate", bands: [{ name: "header", kind: "header", fields: [{ field: "number" }] }] });
  const one = documentCtx(AGG, "o 1");
  await documentMod.render(one);
  await button(one, "Download PDF").click();
  assert.equal(one.downloads[0].url, "/api/documents/WidgetOrderInvoice/aggregate/o%201/render.pdf");
  assert.equal(one.saved[0][1], "WidgetOrderInvoice-o 1.pdf");
  const many = documentCtx(AGG, null);
  await documentMod.render(many);
  assert.deepEqual(byTag(many.root, "button").map((b) => b.textContent), ["Download PDF", "Download PDF"]);
  assert.deepEqual(withText(many.root, "SO-1").length + withText(many.root, "o2").length, 2, "first text value, else the id");
  await byTag(many.root, "button")[1].click();
  assert.equal(many.downloads[0].url, "/api/documents/WidgetOrderInvoice/aggregate/o2/render.pdf");
  const none = makeCtx(AGG, { "GET /api/concepts/orders": { body: [] } }, {});
  await documentMod.render(none);
  assert.equal(withText(none.root, "No records to print yet.").length, 1);
});

console.log(failures.length ? failures.join("\n") : "");
console.log(failures.length ? "FAILED: " + failures.length + " of " + (passed + failures.length) : "experience client: " + passed + " tests passed");
process.exit(failures.length ? 1 : 0);
