// Maps (FORM P5-T6). Leaflet is VENDORED and pinned (vendor/leaflet, see vendor/leaflet/VENDOR.json); it is inserted
// into the page lazily, the first time a map is shown, from /x/vendor/leaflet/ -- never from a CDN. Tiles come from the
// resolved experience (`mapTilesUrl` + `mapAttribution`, written by the generator from the model property `mapTilesUrl`,
// default OpenStreetMap), a screen may name another tile property (`map.tiles` -> `map.tilesUrl`). The attribution is
// always shown. No API key ever reaches the browser from here.
//
// Every string handed to Leaflet is either a DOM node or an HTML-escaped attribution: Leaflet turns STRING popup and
// tooltip content into markup, so popups are built with h() (textContent) and marker captions use the `title` option.
// The user's position is read ONLY after a tap on "Use my location"; nothing asks on load.
//
// Archetype "map": the rows of the screen's dataUrl with a numeric lat/lng become markers (label = an expression
// evaluated per row). Other archetypes reuse mountMap: detail/status (one marker), dashboard widgets, and forms
// (pickMap: tap the map or drag the marker to fill the form's own lat/lng fields).
import { buildHash, card, cellValue, emptyCard, h, humanize } from "./core.js";
import { loadRows } from "./api.js";

const BASE = "/x/vendor/leaflet/";
const MAX_ZOOM = 19;
const FIT = { padding: [24, 24], maxZoom: 16 };
const SAFE_TILES = /^(https?:\/\/|\/[^/])/i; // a template that is not an absolute http(s) URL or a same-origin path is ignored
const views = new WeakMap(); // screen -> {center, zoom}: a live refresh keeps where the user panned

const escapeText = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

// ---- loading Leaflet once ----------------------------------------------------------------------------------
let loading = null;

export function loadLeaflet(doc) {
  if (globalThis.L && typeof globalThis.L.map === "function") return Promise.resolve(globalThis.L);
  if (loading) return loading;
  const head = (doc && (doc.head || doc.documentElement)) || globalThis.document.head;
  const insert = (el, optional) => new Promise((resolve, reject) => {
    el.addEventListener("load", resolve);
    el.addEventListener("error", () => (optional ? resolve() : reject(new Error("The map library could not load."))));
    head.appendChild(el);
  });
  loading = Promise.all([insert(h("link", { rel: "stylesheet", href: BASE + "leaflet.css" }), true), insert(h("script", { src: BASE + "leaflet.js" }), false)])
    .then(() => {
      const L = globalThis.L;
      if (!L || typeof L.map !== "function") throw new Error("The map library did not start.");
      if (L.Icon && L.Icon.Default) L.Icon.Default.imagePath = BASE + "images/";
      return L;
    })
    .catch((error) => { loading = null; throw error; });
  return loading;
}

// ---- points ------------------------------------------------------------------------------------------------
const coordinate = (v, limit) => {
  if (v == null || v === "" || typeof v === "boolean") return NaN;
  const n = Number(v);
  return Number.isFinite(n) && Math.abs(n) <= limit ? n : NaN;
};

/** Rows -> [{lat, lng, label, row}]. lat/lng/label are field names or expressions (browser engine); a row without a
 *  usable position is skipped, never an error. */
export function rowPoints(rows, spec, user) {
  const out = [];
  if (!spec || !spec.lat || !spec.lng) return out;
  for (const row of rows || []) {
    const lat = coordinate(cellValue(row, { value: spec.lat }, user), 90);
    const lng = coordinate(cellValue(row, { value: spec.lng }, user), 180);
    if (Number.isNaN(lat) || Number.isNaN(lng)) continue;
    const label = spec.label ? cellValue(row, { value: spec.label }, user) : null;
    out.push({ lat, lng, label: label == null ? "" : String(label), row });
  }
  return out;
}

const coords = (p) => p.lat.toFixed(5) + ", " + p.lng.toFixed(5);

function tileConfig(ctx, spec) {
  const exp = ctx.experience || {};
  const own = spec && spec.tilesUrl; // a screen's own template brings its own attribution, never the experience's
  const url = own || exp.mapTilesUrl || "";
  if (!SAFE_TILES.test(url)) return null;
  return { url, attribution: (own ? spec.attribution : exp.mapAttribution) || "Map tiles" };
}

function popupFor(p, href) {
  return h("div", { class: "map-popup" }, h("strong", { text: p.label || coords(p) }), href ? h("a", { href, text: "Open" }) : null);
}

// ---- the map -------------------------------------------------------------------------------------------------
/**
 * Builds the map container at once and creates the Leaflet map when the library has loaded.
 * opts = {points, label, keepView, linkFor(row) -> href, compact, locate}.
 * Returns {el, ready: Promise<{L, map}|null>, setPoints(points), showMe(lat, lng)}; el is attached by the caller.
 */
export function mountMap(ctx, spec, opts) {
  const o = opts || {};
  const surface = h("div", { class: "np-map" + (o.compact ? " compact" : ""), role: "region", "aria-label": o.label || "Map" });
  let points = o.points || [];
  let live = null;
  let dead = false;

  const fit = () => {
    if (!live) return;
    const { L, map } = live;
    const saved = o.keepView ? views.get(ctx.screen) : null;
    if (saved) { map.setView(saved.center, saved.zoom); return; }
    const mine = String((spec && spec.center) || "");
    const fixed = /^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*$/.test(mine) ? mine.split(",").map(Number) : null;
    if (points.length === 1) map.setView([points[0].lat, points[0].lng], 15);
    else if (points.length) map.fitBounds(points.map((p) => [p.lat, p.lng]), FIT);
    else map.setView(fixed || [0, 0], fixed ? 13 : 2);
  };

  const draw = () => {
    if (!live) return;
    live.layer.clearLayers();
    for (const p of points) {
      const marker = live.L.marker([p.lat, p.lng], { title: p.label || coords(p), alt: p.label || "Location", keyboard: true });
      marker.bindPopup(popupFor(p, o.linkFor ? o.linkFor(p.row) : null));
      live.layer.addLayer(marker);
    }
  };

  const ready = loadLeaflet(globalThis.document).then((L) => {
    if (dead) return null;
    const map = L.map(surface, { maxZoom: MAX_ZOOM });
    const tiles = tileConfig(ctx, spec);
    if (tiles) L.tileLayer(tiles.url, { maxZoom: MAX_ZOOM, attribution: escapeText(tiles.attribution) }).addTo(map);
    live = { L, map, layer: L.layerGroup().addTo(map) };
    draw();
    fit();
    if (o.keepView) map.on("moveend", () => views.set(ctx.screen, { center: map.getCenter(), zoom: map.getZoom() }));
    return live;
  }).catch(() => { surface.replaceChildren(card("notice", null, "The map could not load.")); return null; });

  // A map built before its container is on the page has no size: once it has one, refit.
  const RO = globalThis.ResizeObserver;
  let first = true;
  const observer = RO ? new RO(() => {
    if (!live || !(surface.clientWidth > 0)) return;
    live.map.invalidateSize();
    if (first) { first = false; fit(); }
  }) : null;
  if (observer) observer.observe(surface);
  if (ctx.onCleanup) ctx.onCleanup(() => { dead = true; if (observer) observer.disconnect(); if (live) live.map.remove(); });

  const handle = {
    el: surface, ready,
    setPoints(next) { points = next; draw(); fit(); },
    showMe(lat, lng) {
      return ready.then((l) => {
        if (!l) return;
        l.L.circleMarker([lat, lng], { radius: 8 }).addTo(l.layer);
        l.map.setView([lat, lng], 15);
      });
    }
  };
  const nav = ctx.navigator || globalThis.navigator;
  if (o.locate && nav && nav.geolocation) {
    handle.el = h("div", { class: "map-wrap" }, h("button", {
      type: "button", class: "btn secondary locate", text: "Use my location",
      onclick: () => nav.geolocation.getCurrentPosition(
        (pos) => handle.showMe(pos.coords.latitude, pos.coords.longitude),
        () => { if (ctx.reportError) ctx.reportError("Your location is not available."); },
        { timeout: 10000 })
    }), surface);
  }
  return handle;
}

/** A text alternative to the map: the same places as a list (also what a screen reader and a failed map fall back on). */
export function placeList(points, linkFor, limit) {
  return h("ul", { class: "rows map-list" }, points.slice(0, limit || 50).map((p) => {
    const href = linkFor ? linkFor(p.row) : null;
    const body = [h("div", { class: "cell title", text: p.label || coords(p) }), h("span", { class: "muted", text: coords(p) })];
    return h("li", { class: "row-item" }, href ? h("a", { class: "row", href }, body) : h("div", { class: "row" }, body));
  }));
}

const linkTo = (ctx, screen) => {
  const target = ctx.detailFor ? ctx.detailFor(screen) : screen.name;
  return !target || target === screen.name ? null : (row) => { const id = row && row[screen.idField || "id"]; return id == null ? null : buildHash(target, id); };
};

// ---- archetype "map" -------------------------------------------------------------------------------------------
export async function render(ctx) {
  const { screen, root } = ctx;
  const spec = screen.map || {};
  if (!screen.dataUrl || !spec.lat || !spec.lng) { root.appendChild(emptyCard(screen)); return; }
  const { rows, truncated } = await loadRows(ctx.api, screen, { want: 200, user: ctx.user });
  const points = rowPoints(rows, spec, ctx.user);
  if (!points.length) { root.appendChild(emptyCard(screen)); return; }
  const linkFor = linkTo(ctx, screen);
  const view = mountMap(ctx, spec, { points, label: screen.title || "Map", keepView: true, linkFor, locate: spec.center === "me" });
  root.appendChild(view.el);
  root.appendChild(placeList(points, linkFor));
  if (truncated) root.appendChild(h("p", { class: "muted note", text: "Showing the first " + rows.length + " places." }));
}

/** One marker per row, for a detail/status screen (`map` on the screen) or a dashboard widget. Returns an element or null. */
export function rowMap(ctx, spec, rows, opts) {
  const points = rowPoints(rows, spec, ctx.user);
  if (!points.length) return null;
  return mountMap(ctx, spec, Object.assign({ points, label: (ctx.screen && ctx.screen.title) || "Map", locate: (spec || {}).center === "me" }, opts)).el;
}

// ---- forms: pick points ------------------------------------------------------------------------------------------
/**
 * spec.pickPoints = [{name, label, lat, lng}] where lat/lng name FORM FIELDS. A tap on the map sets the chosen point,
 * a drag of its marker moves it; the values land in the form's own inputs (which stay editable and keyboard
 * accessible), so the action's input mapping and validation use them unchanged. Typing in an input moves the marker.
 * `controls` = {fieldName: input element}. Returns {el, ready, activate(i)}.
 */
export function pickMap(ctx, spec, controls) {
  const picks = (spec.pickPoints || []).filter((p) => p && controls[p.lat] && controls[p.lng]);
  const handle = mountMap(ctx, spec, { label: "Choose places on the map", locate: spec.center === "me" });
  const markers = [];
  let active = 0;
  const read = (p) => [coordinate(controls[p.lat].value, 90), coordinate(controls[p.lng].value, 180)];
  const nameOf = (p) => p.label || humanize(p.name);
  const buttons = picks.map((p, i) => h("button", { type: "button", class: "btn secondary pick", text: nameOf(p), "aria-pressed": "false", onclick: () => activate(i) }));
  function activate(i) {
    active = i;
    buttons.forEach((b, n) => b.setAttribute("aria-pressed", n === i ? "true" : "false"));
  }
  const firstUnset = () => { const i = picks.findIndex((p) => read(p).some(Number.isNaN)); return i < 0 ? 0 : i; };
  activate(firstUnset());

  async function place(i) {
    const l = await handle.ready;
    if (!l || !picks[i]) return;
    const [lat, lng] = read(picks[i]);
    if (Number.isNaN(lat) || Number.isNaN(lng)) { if (markers[i]) { l.layer.removeLayer(markers[i]); markers[i] = null; } return; }
    if (markers[i]) { markers[i].setLatLng([lat, lng]); return; }
    const marker = l.L.marker([lat, lng], { draggable: true, keyboard: true, title: nameOf(picks[i]), alt: nameOf(picks[i]) });
    marker.on("dragend", () => { const at = marker.getLatLng(); set(i, at.lat, at.lng, false); });
    l.layer.addLayer(marker);
    markers[i] = marker;
  }
  function set(i, lat, lng, move) {
    const p = picks[i];
    controls[p.lat].value = lat.toFixed(6);
    controls[p.lng].value = lng.toFixed(6);
    if (move !== false) place(i);
  }
  picks.forEach((p, i) => [p.lat, p.lng].forEach((name) => controls[name].addEventListener("change", () => place(i))));

  handle.ready.then((l) => {
    if (!l) return;
    picks.forEach((p, i) => place(i));
    const known = picks.map(read).filter((c) => !c.some(Number.isNaN));
    if (known.length === 1) l.map.setView(known[0], 15); else if (known.length) l.map.fitBounds(known, FIT);
    l.map.on("click", (e) => {
      set(active, e.latlng.lat, e.latlng.lng);
      const next = picks.findIndex((q, i) => i !== active && read(q).some(Number.isNaN));
      if (next >= 0) activate(next);
    });
  });
  handle.el = h("div", { class: "pick-map" }, picks.length > 1 ? h("div", { class: "pick-choose", role: "group", "aria-label": "A tap on the map sets" }, buttons) : null, handle.el);
  handle.activate = activate;
  return handle;
}
