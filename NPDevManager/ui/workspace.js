// The app workspace (NAV-3): choose the app ONCE, and every app-scoped screen follows.
//
// Before this, each of nine screens (Start & stop, Edit, Ask AI, Data, Share, the two Checks, Impact,
// Packs) had its own app picker, so a user picked "Bakery" again on every screen. Now `select(app)` stores
// one selected app and tells every screen:
//
//   * it dispatches `npdev:app-selected` (detail: the app) for any code that wants to react;
//   * it sets each screen's own picker to that app and fires the picker's own `change` event, so the
//     screen refreshes exactly the way it does when a person picks -- no screen's code had to change;
//   * a MutationObserver re-applies the choice whenever a picker is repopulated (the screens rebuild their
//     option lists when they are entered), so a refresh cannot silently put another app back;
//   * while a workspace screen is showing, <main data-in-workspace="true"> hides those pickers (CSS in
//     workspace.css), and the My apps strip shows the app's name with a "Change app" button.
//
// An app has two identities in this Manager: the MODEL folder (what `Apps` registers and what Edit, Data,
// Impact and Packs pick) and the generated FinalApp folder (`<model>-app`, what the Monitor scans and what
// Share, Browser checks and Ask AI pick). `select` accepts either and matches option values against both.

(function () {
  "use strict";
  const invoke = (name, args) => window.__TAURI__.core.invoke(name, args);
  const t = (obj) => (window.NPDevI18n ? window.NPDevI18n.t(obj) : obj.en);
  const STORE_KEY = "npdev.manager.selectedApp";

  // screen -> the pickers it owns. `input` is the Start & stop folder field, which is the truth that
  // screen reads. The CSS in workspace.css hides the same ids.
  const SCREENS = {
    run: { selects: ["#run-app-picker"], input: "#run-app-dir" },
    studio: { selects: ["#studio-app-picker"] },
    browserai: { selects: ["#browserai-app"] },
    prompter: { selects: ["#prompter-app"] },
    dbio: { selects: ["#dbio-app-picker"] },
    hosting: { selects: ["#hosting-app-picker"] },
    scrap: { selects: ["#scrap-app"] },
    gpuchecks: { selects: ["#gpuchecks-app-picker"] },
    provenance: { selects: ["#provenance-app-picker"] },
    packs: { selects: ["#packs-app-picker"] },
  };
  // The workspace tabs of navigation.json's apps place, by the tab ids a caller may ask for.
  const TAB_SCREEN = { overview: "run", edit: "studio", "ask-ai": "browserai", "ask-ai-key": "prompter", data: "dbio", share: "hosting", checks: "scrap", inside: "provenance" };

  const TEXT = {
    app: { en: "App", pt: "App" },
    change: { en: "Change app", pt: "Trocar de app" },
    open: { en: "Open", pt: "Abrir" },
  };

  let selected = null; // { name, directory, appDir }
  let currentScreen = null;

  const norm = (p) => String(p || "").replace(/\\/g, "/").replace(/\/+$/, "").toLowerCase();
  const leaf = (p) => norm(p).split("/").pop();

  function identities(app) {
    const out = new Set();
    for (const p of [app.directory, app.appDir]) if (p) out.add(norm(p));
    if (app.directory) out.add(norm(app.directory) + "-app");
    if (app.appDir && /-app$/.test(norm(app.appDir))) out.add(norm(app.appDir).replace(/-app$/, ""));
    return out;
  }

  const matches = (app, value) => !!value && identities(app).has(norm(value));

  function store() {
    try {
      if (selected) window.localStorage.setItem(STORE_KEY, JSON.stringify(selected));
      else window.localStorage.removeItem(STORE_KEY);
    } catch (e) {
      /* the choice just is not remembered across a restart */
    }
  }

  function restore() {
    try {
      const raw = window.localStorage.getItem(STORE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  // ----------------------------------------------------------------------------- applying

  function applyToPicker(sel) {
    const picker = document.querySelector(sel);
    if (!picker || !selected) return;
    const option = [...picker.options].find((o) => matches(selected, o.value));
    if (option && picker.value !== option.value) {
      picker.value = option.value;
      // The screen's own code refreshes on this `change`, as it does when a person picks. It is NOT a pick: the flag lets a
      // screen that treats a pick as a decision (Ask AI: "this app" means "change it") tell the two apart (REG-325).
      const ev = new Event("change", { bubbles: true });
      ev.npdevFromWorkspace = true;
      picker.dispatchEvent(ev);
    }
  }

  function applyToScreen(screen) {
    const spec = SCREENS[screen];
    if (!spec || !selected) return;
    spec.selects.forEach(applyToPicker);
    if (spec.input) {
      const input = document.querySelector(spec.input);
      const value = selected.directory || selected.appDir;
      if (input && value && input.value !== value) {
        input.value = value;
        input.dispatchEvent(new Event("input", { bubbles: true }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
      }
    }
  }

  function applyAll() {
    Object.keys(SCREENS).forEach(applyToScreen);
  }

  // Screens rebuild their option lists on entry; put the chosen app back whenever one does.
  const observers = [];
  function watchPickers() {
    Object.entries(SCREENS).forEach(([screen, spec]) => {
      spec.selects.forEach((sel) => {
        const picker = document.querySelector(sel);
        if (!picker) return;
        const obs = new MutationObserver(() => applyToScreen(screen));
        obs.observe(picker, { childList: true });
        observers.push(obs);
      });
    });
  }

  function paint() {
    const inWorkspace = !!selected && !!SCREENS[currentScreen];
    const main = document.querySelector("main");
    if (main) {
      if (inWorkspace) main.setAttribute("data-in-workspace", "true");
      else main.removeAttribute("data-in-workspace");
    }
    if (selected) document.body.setAttribute("data-app-selected", "true");
    else document.body.removeAttribute("data-app-selected");
    const bar = document.getElementById("workspace-bar");
    if (bar) {
      bar.hidden = !selected;
      const name = bar.querySelector(".ws-name");
      if (name && selected) name.textContent = selected.name || leaf(selected.directory || selected.appDir);
      const label = bar.querySelector(".ws-label");
      if (label) label.textContent = t(TEXT.app) + ":";
      const change = bar.querySelector(".ws-change");
      if (change) change.textContent = t(TEXT.change);
    }
  }

  // ---------------------------------------------------------------------------- selecting

  // Fill in whichever identity is missing from the two lists the Manager already has.
  async function complete(app) {
    const out = { name: app.name || "", directory: app.directory || null, appDir: app.appDir || null };
    try {
      const registry = (await invoke("list_apps")) || [];
      const hit = registry.find((r) => matches(out, r.directory) || (out.name && r.name === out.name));
      if (hit) {
        out.directory = out.directory || hit.directory;
        out.name = out.name || hit.name;
      }
    } catch (e) {
      /* the registry is optional: a scanned app that was never registered still works */
    }
    try {
      const scan = await invoke("monitor_scan", { includeInfo: false });
      const hit = ((scan && scan.apps) || []).find((a) => a.status !== "not-an-app" && matches(out, a.appDir));
      if (hit) {
        out.appDir = out.appDir || hit.appDir;
        out.name = out.name || hit.name;
      }
    } catch (e) {
      /* same */
    }
    if (!out.name) out.name = leaf(out.directory || out.appDir);
    return out;
  }

  async function select(app) {
    selected = await complete(app);
    store();
    paint();
    applyAll();
    document.dispatchEvent(new CustomEvent("npdev:app-selected", { detail: selected }));
    return selected;
  }

  function clear() {
    selected = null;
    store();
    paint();
    document.dispatchEvent(new CustomEvent("npdev:app-selected", { detail: null }));
  }

  // Choose an app and land on one of its tabs (Overview by default).
  async function open(app, tab) {
    await select(app);
    const screen = TAB_SCREEN[tab || "overview"] || tab || "run";
    if (window.__npdevShowScreen) window.__npdevShowScreen(screen);
  }

  // ------------------------------------------------------------------------------- the bar

  function ensureBar() {
    const strip = document.querySelector('.place-strip[data-place="apps"]');
    if (!strip) return;
    let bar = document.getElementById("workspace-bar");
    if (bar && strip.contains(bar)) return paint();
    bar = document.createElement("div");
    bar.id = "workspace-bar";
    bar.setAttribute("translate", "no"); // workspace.js speaks the chosen language itself (t({en, pt}))
    bar.className = "workspace-bar";
    bar.hidden = true;
    const label = document.createElement("span");
    label.className = "ws-label";
    const name = document.createElement("strong");
    name.className = "ws-name";
    const change = document.createElement("button");
    change.type = "button";
    change.className = "ws-change";
    change.addEventListener("click", () => window.__npdevShowScreen && window.__npdevShowScreen("apps"));
    bar.append(label, " ", name, " ", change);
    strip.prepend(bar);
    paint();
  }

  document.addEventListener("npdev:nav-rendered", ensureBar);
  document.addEventListener("npdev:lang-changed", paint);
  document.addEventListener("npdev:screen-shown", (e) => {
    currentScreen = e.detail && e.detail.screen;
    paint();
    applyToScreen(currentScreen);
  });

  // The All apps list: one Open button per app, which is how a person chooses the app.
  document.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-ws-open]");
    if (!btn) return;
    open({ name: btn.dataset.name, directory: btn.dataset.wsOpen }, "overview");
  });

  window.NPDevWorkspace = {
    select,
    open,
    clear,
    current: () => selected,
    openLabel: () => t(TEXT.open),
    matches,
  };

  watchPickers();
  const remembered = restore();
  if (remembered) {
    selected = remembered;
    paint();
  }
  ensureBar();
})();
