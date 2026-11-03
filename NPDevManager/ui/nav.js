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
// work). Changing it fires `npdev:lang-changed` so other screens can re-render their own text as they
// gain translations; today the navigation, the place summaries and the language picker follow it.

(function () {
  const STORAGE_KEY = "npdev.manager.lang";

  function storedLang() {
    try {
      return window.localStorage.getItem(STORAGE_KEY);
    } catch (e) {
      return null;
    }
  }

  function storeLang(id) {
    try {
      window.localStorage.setItem(STORAGE_KEY, id);
    } catch (e) {
      /* a refused write only means the choice is not remembered next time */
    }
  }

  const I18n = {
    lang: "en",
    languages: [{ id: "en", label: "English" }],
    t(value) {
      if (value == null) return "";
      if (typeof value === "string") return value;
      return value[I18n.lang] ?? value.en ?? Object.values(value)[0] ?? "";
    },
    setLang(id) {
      if (!I18n.languages.some((l) => l.id === id)) id = "en";
      I18n.lang = id;
      document.documentElement.lang = id;
      storeLang(id);
      document.dispatchEvent(new CustomEvent("npdev:lang-changed", { detail: { lang: id } }));
    },
  };
  window.NPDevI18n = I18n;

  function initialLang(languages) {
    const ids = languages.map((l) => l.id);
    const stored = storedLang();
    if (stored && ids.includes(stored)) return stored;
    const browser = (navigator.language || "en").slice(0, 2).toLowerCase();
    return ids.includes(browser) ? browser : "en";
  }

  let nav = null; // the parsed navigation.json
  let activePlace = null;
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
    return nav.places.filter((p) => p.screens.some((s) => s.screen === screen));
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
  }

  function openPlace(placeId) {
    const place = nav.places.find((p) => p.id === placeId);
    if (!place) return;
    activePlace = place.id;
    const screen = lastScreenOf[place.id] || place.screens[0].screen;
    window.__npdevShowScreen(screen);
  }

  function render() {
    const bar = document.querySelector("nav.tabs");
    const version = document.getElementById("manager-version");
    bar.replaceChildren();
    bar.setAttribute("aria-label", I18n.t(nav.chrome.mainNav));

    const places = el("div", { class: "places" });
    for (const place of nav.places) {
      const single = place.screens.length === 1;
      // A place with one screen IS that screen's tab, so it carries `.tab` + data-screen and app.js's
      // router highlights it like any other tab (check-manager-ui-render.mjs clicks
      // `.tab[data-screen="monitor"]`, which is the Activity place).
      const b = el("button", {
        type: "button",
        class: single ? "place tab" : "place",
        "data-place": place.id,
        "data-screen": single ? place.screens[0].screen : undefined,
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

    for (const place of nav.places) {
      const strip = el("div", { class: "place-strip", "data-place": place.id, hidden: true });
      strip.append(el("p", { class: "place-summary" }, I18n.t(place.summary)));
      if (place.screens.length > 1) {
        const row = el("div", { class: "strip-tabs" });
        let group = null;
        for (const s of place.screens) {
          if ((s.group || null) !== group) {
            group = s.group || null;
            if (group) row.append(el("span", { class: "strip-group" }, I18n.t(nav.groups[group])));
          }
          row.append(el("button", { type: "button", class: "tab", "data-screen": s.screen }, I18n.t(s.label)));
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

  fetch("navigation.json")
    .then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return r.json();
    })
    .then((data) => {
      nav = data;
      I18n.languages = data.languages;
      I18n.lang = initialLang(data.languages);
      document.documentElement.lang = I18n.lang;
      render();
    })
    .catch(renderFallback);
})();
