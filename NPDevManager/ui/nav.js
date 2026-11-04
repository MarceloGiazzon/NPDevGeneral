// The Manager's navigation (NAV-1) and its language (owner decision D4, 2026-11-03).
//
// WHY THIS EXISTS
// ---------------
// The Manager grew one tab per subsystem, in the order they were built: 18 of them, none of which is
// a step of what the user is trying to do. A first-time user had no obvious path. This file renders
// four PLACES instead -- Set up, My apps, Activity, Settings -- each with a strip of the screens that
// belong to it, from `navigation.json`. The screens themselves are untouched: every one keeps its
// <section id="screen-X">, and `showScreen(name)` in app.js stays the only router.
//
// There is no developer mode with the old flat strip (owner decision D5), so a screen that
// navigation.json does not list cannot be opened. main.rs's
// `every_screen_is_reachable_from_the_navigation` test fails the build if that ever happens.
//
// LANGUAGE
// --------
// Every string in navigation.json is an object keyed by language id. `NPDevI18n.t(obj)` picks the
// current language and falls back to English. The choice is a per-viewer presentation preference, so
// it lives in localStorage (wrapped: a private profile can refuse it, and the Manager must still
// work). Changing it fires `npdev:lang-changed`. The engine itself is i18n.js (I18N-1): it translates
// every screen's text from `i18n/<lang>.json`, so a screen needs no code to follow the picker.

(function () {
  // The language engine (state, storage, catalogue, DOM translation) is i18n.js, loaded before this file.
  const I18n = window.NPDevI18n;

  let nav = null; // the parsed navigation.json
  let activePlace = null;

  // NAV-4: "Set up" is only shown until this computer is ready; afterwards its screens live under
  // Settings. `showWhen` in navigation.json names the condition. Unknown readiness counts as NOT ready
  // (a fresh profile must see Set up); the last answer is remembered so the first paint is stable.
  const READY_KEY = "npdev.manager.machineReady";
  let machineReady = (() => {
    try {
      return window.localStorage.getItem(READY_KEY) === "1";
    } catch (e) {
      return false;
    }
  })();
  const shows = (x) => (x.showWhen === "machineReady" ? machineReady : x.showWhen === "machineNotReady" ? !machineReady : true);
  const screensOf = (place) => place.screens.filter(shows);
  const visiblePlaces = () => nav.places.filter((p) => shows(p) && screensOf(p).length > 0);
  const lastScreenOf = {}; // place id -> the screen last shown there, so a place reopens where you left it

  function el(tag, attrs, text) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === undefined || v === null || v === false) continue;
      e.setAttribute(k, v === true ? "" : v);
    }
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function placesFor(screen) {
    return visiblePlaces().filter((p) => screensOf(p).some((s) => s.screen === screen));
  }

  // Called by app.js's showScreen after every screen change, whoever asked for it (a tab, the
  // Monitor's "Explore this app" button, init). Keeps the highlighted place and its strip in step.
  function sync(screen) {
    if (!nav || !screen) return;
    const owners = placesFor(screen);
    if (!owners.length) return;
    // A screen listed in two places ("ready" is both Set up > Check my computer and Settings > This
    // computer) stays in the place you reached it from.
    const place = owners.find((p) => p.id === activePlace) || owners[0];
    activePlace = place.id;
    lastScreenOf[place.id] = screen;
    document.querySelectorAll(".tabs .place").forEach((b) => {
      const on = b.dataset.place === place.id;
      b.classList.toggle("active", on);
      if (on) b.setAttribute("aria-current", "page");
      else b.removeAttribute("aria-current");
    });
    document.querySelectorAll(".tabs .place-strip").forEach((s) => (s.hidden = s.dataset.place !== place.id));
    syncTabs(screen);
  }

  // NAV-5: the strip is a tablist -- exactly one tab is in the tab order (the selected one, or the first
  // when the strip's place is not the current one), and the others are reached with the arrow keys.
  function syncTabs(screen) {
    document.querySelectorAll('.tabs [role="tablist"]').forEach((list) => {
      const tabs = [...list.querySelectorAll('[role="tab"]')];
      const current = tabs.find((t) => t.dataset.screen === screen);
      tabs.forEach((t) => {
        t.setAttribute("aria-selected", t === current ? "true" : "false");
        t.tabIndex = t === (current || tabs[0]) ? 0 : -1;
      });
    });
  }

  function onStripKey(e) {
    const keys = ["ArrowLeft", "ArrowRight", "Home", "End"];
    if (!keys.includes(e.key)) return;
    const list = e.currentTarget;
    const tabs = [...list.querySelectorAll('[role="tab"]')].filter((t) => t.offsetParent !== null);
    if (!tabs.length) return;
    const at = tabs.indexOf(document.activeElement);
    let next = at;
    if (e.key === "ArrowRight") next = (at + 1) % tabs.length;
    else if (e.key === "ArrowLeft") next = (at - 1 + tabs.length) % tabs.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = tabs.length - 1;
    e.preventDefault();
    tabs[next].focus();
    tabs[next].click(); // automatic activation: the arrow keys move AND open
  }

  function openPlace(placeId) {
    const place = visiblePlaces().find((p) => p.id === placeId);
    if (!place) return;
    activePlace = place.id;
    const screens = screensOf(place);
    const screen = screens.some((s) => s.screen === lastScreenOf[place.id]) ? lastScreenOf[place.id] : screens[0].screen;
    window.__npdevShowScreen(screen);
  }

  function render() {
    const bar = document.querySelector("nav.tabs");
    const version = document.getElementById("manager-version");
    bar.replaceChildren();
    bar.setAttribute("aria-label", I18n.t(nav.chrome.mainNav));

    const skip = document.getElementById("skip-link");
    if (skip) skip.textContent = I18n.t(nav.chrome.skipToContent);

    const places = el("div", { class: "places" });
    for (const place of visiblePlaces()) {
      const single = screensOf(place).length === 1;
      // A place with one screen IS that screen's tab, so it carries `.tab` + data-screen and app.js's
      // router highlights it like any other tab (check-manager-ui-render.mjs clicks
      // `.tab[data-screen="monitor"]`, which is the Activity place).
      const b = el("button", {
        type: "button",
        class: single ? "place tab" : "place",
        "data-place": place.id,
        "data-screen": single ? screensOf(place)[0].screen : undefined,
        title: I18n.t(place.summary),
      });
      b.append(el("span", { class: "place-icon", "aria-hidden": "true" }, place.icon || ""));
      b.append(document.createTextNode(I18n.t(place.label)));
      places.append(b);
    }

    const tools = el("div", { class: "nav-tools" });
    const langLabel = el("label", { class: "lang-picker" });
    langLabel.append(el("span", { class: "visually-hidden" }, I18n.t(nav.chrome.language)));
    const select = el("select", { id: "lang-picker", "aria-label": I18n.t(nav.chrome.language) });
    for (const l of I18n.languages) {
      const o = el("option", { value: l.id }, l.label);
      if (l.id === I18n.lang) o.selected = true;
      select.append(o);
    }
    select.addEventListener("change", () => I18n.setLang(select.value));
    langLabel.append(select);
    tools.append(langLabel);
    if (version) tools.append(version);
    places.append(tools);
    bar.append(places);

    for (const place of visiblePlaces()) {
      const strip = el("div", { class: "place-strip", "data-place": place.id, hidden: true });
      // Labels stay short (minimalism at first sight); a screen's `hint` is its tab's tooltip.
      // The place's one-line summary is NOT drawn here -- it is the tooltip (title) of the place's button above.
      if (screensOf(place).length > 1) {
        const row = el("div", { class: "strip-tabs", role: "tablist", "aria-label": I18n.t(place.label) });
        row.addEventListener("keydown", onStripKey);
        let group = null;
        for (const s of screensOf(place)) {
          if ((s.group || null) !== group) {
            group = s.group || null;
            if (group) row.append(el("span", { class: "strip-group" }, I18n.t(nav.groups[group])));
          }
          row.append(el("button", { type: "button", class: "tab", role: "tab", "aria-controls": `screen-${s.screen}`,
            "data-screen": s.screen, "data-ws": s.workspace ? "1" : undefined,
            title: s.hint ? I18n.t(s.hint) : undefined }, I18n.t(s.label)));
        }
        strip.append(row);
      }
      bar.append(strip);
    }

    const current = window.__npdevCurrentScreen;
    if (current) {
      document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.dataset.screen === current));
      sync(current);
    }
    syncTabs(current || null);
    // workspace.js puts the app bar into the My apps strip once the strips exist.
    document.dispatchEvent(new CustomEvent("npdev:nav-rendered"));
  }

  // If navigation.json cannot be read, every screen must still be reachable: fall back to one plain
  // tab per <section>, named by its own <h1>. Ugly on purpose -- it is a broken install, not a mode.
  function renderFallback(err) {
    console.error("navigation.json could not be loaded; showing every screen as a tab", err);
    const bar = document.querySelector("nav.tabs");
    const version = document.getElementById("manager-version");
    bar.replaceChildren();
    const row = el("div", { class: "places" });
    document.querySelectorAll("main > section.screen").forEach((s) => {
      const name = s.id.replace(/^screen-/, "");
      const title = (s.querySelector("h1")?.textContent || name).replace(/\?$/, "").trim();
      row.append(el("button", { type: "button", class: "tab", "data-screen": name }, title));
    });
    if (version) row.append(version);
    bar.append(row);
  }

  document.addEventListener("click", (e) => {
    const b = e.target.closest(".tabs .place[data-place]");
    if (b && !b.dataset.screen) openPlace(b.dataset.place);
  });

  document.addEventListener("npdev:lang-changed", () => nav && render());

  window.__npdevNavSync = sync;

  // app.js reports every computer check; Set up appears and disappears with the answer.
  document.addEventListener("npdev:doctor-result", (e) => {
    const ready = !!(e.detail && e.detail.ready);
    if (ready === machineReady) return;
    machineReady = ready;
    try {
      window.localStorage.setItem(READY_KEY, ready ? "1" : "0");
    } catch (err) {
      /* the next start just assumes not ready until the check answers */
    }
    if (nav) render();
  });

  fetch("navigation.json")
    .then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return r.json();
    })
    .then((data) => {
      nav = data;
      window.__npdevNav = data;
      I18n.init(data.languages);
      render();
    })
    .catch(renderFallback);
})();
