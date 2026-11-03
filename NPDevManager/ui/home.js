// Home (NAV-2): ONE card that says what to do next, with ONE button.
//
// The decisions are DATA -- `homeDecisions` in navigation.json, evaluated top to bottom, the first that
// applies wins. This file only knows how to ask each question ("has the computer check ever run?",
// "which checks failed?", "are there apps?", "is one running?") using commands that already exist, and
// how to perform the three kinds of button (go to a screen, open the support dialog, open a URL).
// Under the card sits a one-line status strip so a user can see the whole picture without opening a tab.
//
// Inputs, all existing commands: `check_doctor` (only after the user has run the computer check once --
// Home never probes a machine nobody asked about), `monitor_scan` (the apps and whether one is running),
// and `last_failed_run` (the Manager's run recorder, SUP-1).

(function () {
  "use strict";
  const invoke = (name, args) => window.__TAURI__.core.invoke(name, args);
  const I18n = () => window.NPDevI18n;
  const t = (obj) => (I18n() ? I18n().t(obj) : (obj && obj.en) || "");
  const DOCTOR_FLAG = "npdev.manager.doctorRan";
  const DOCTOR_TTL_MS = 60 * 1000;

  const TEXT = {
    checking: { en: "Looking at your computer…", pt: "Olhando o seu computador…" },
    stripReady: { en: "Computer ready", pt: "Computador pronto" },
    stripNotReady: { en: "Computer not ready", pt: "Computador não está pronto" },
    stripUnchecked: { en: "Computer not checked yet", pt: "Computador ainda não verificado" },
    apps: { en: ["app", "apps"], pt: ["app", "apps"] },
    running: { en: "running", pt: "em execução" },
    lastProblem: { en: "last problem", pt: "último problema" },
    none: { en: "none", pt: "nenhum" },
    fallbackCard: { en: "Everything looks fine", pt: "Tudo certo por aqui" },
  };

  let decisions = [];
  let busy = false;
  let doctorCache = null; // { at, checks }

  async function loadDecisions() {
    try {
      const r = await fetch("navigation.json");
      const nav = await r.json();
      decisions = nav.homeDecisions || [];
    } catch (e) {
      decisions = [];
    }
  }

  const flagSet = () => {
    try {
      return window.localStorage.getItem(DOCTOR_FLAG) === "1";
    } catch (e) {
      return false;
    }
  };

  // app.js calls this after every computer check so Home knows it has run at least once.
  function markRan() {
    try {
      window.localStorage.setItem(DOCTOR_FLAG, "1");
    } catch (e) {
      /* a refused write only means Home asks again next start */
    }
  }

  window.__npdevDoctorRan = (checks) => {
    doctorCache = { at: Date.now(), checks: checks || [] };
    markRan();
    // The Manager checks the computer at launch, after Home is already showing: redraw when it answers.
    const screen = document.getElementById("screen-home");
    if (screen && !screen.hidden) refresh();
  };

  const isNotApplicable = (c) => /^n\/a/i.test(String(c.expected || ""));

  async function readDoctor() {
    if (!doctorCache && window.__npdevLastDoctor) doctorCache = window.__npdevLastDoctor;
    if (doctorCache && Date.now() - doctorCache.at < DOCTOR_TTL_MS) return doctorCache.checks;
    try {
      const r = await invoke("check_doctor", { appDir: null });
      doctorCache = { at: Date.now(), checks: (r && r.checks) || [] };
      return doctorCache.checks;
    } catch (e) {
      return null;
    }
  }

  async function readApps() {
    try {
      const scan = await invoke("monitor_scan", { includeInfo: false });
      return ((scan && scan.apps) || []).filter((a) => a.status !== "not-an-app");
    } catch (e) {
      return [];
    }
  }

  async function gather() {
    const ran = flagSet() || !!doctorCache || !!window.__npdevLastDoctor;
    const checks = ran ? await readDoctor() : null;
    const failures = (checks || []).filter((c) => c.status === "fail" && !isNotApplicable(c));
    const apps = await readApps();
    const running = apps.filter((a) => a.health === "running");
    let failed = null;
    try {
      failed = await invoke("last_failed_run");
    } catch (e) {
      failed = null;
    }
    return { ran, checks, failures, apps, running, failed };
  }

  // The questions behind each `when` in navigation.json. A name this file does not know never applies,
  // so a decision added to the data before the code is skipped rather than shown wrongly.
  const WHEN = {
    doctorNeverRun: (c) => !c.ran,
    doctorHasFailures: (c) => c.ran && c.failures.length > 0,
    noApps: (c) => c.apps.length === 0,
    lastOperationFailed: (c) => !!c.failed,
    appsAllStopped: (c) => c.apps.length > 0 && c.running.length === 0,
    appRunning: (c) => c.running.length > 0,
  };

  function fill(text, c) {
    const first = (c.running[0] || c.apps[0] || {});
    return String(text)
      .replace("{n}", String(c.failures.length))
      .replace("{operation}", c.failed ? c.failed.intent : "")
      .replace("{app}", first.name || "")
      .replace("{url}", first.baseUrl || "");
  }

  function pick(c) {
    for (const d of decisions) {
      const q = WHEN[d.when];
      if (q && q(c)) return d;
    }
    return null;
  }

  function perform(action, c) {
    if (!action) return;
    if (action.kind === "supportReport") {
      if (window.NPDevSupport) {
        window.NPDevSupport.openSupportDialog({ operation: c.failed && c.failed.operation, screen: "home" }, null);
      }
      return;
    }
    if (action.kind === "openUrl") {
      const url = (c.running[0] || {}).baseUrl;
      if (url) invoke("open_url", { url }).catch(() => {});
      return;
    }
    if (action.workspace) {
      const app = c.running[0] || c.apps[0];
      if (window.NPDevWorkspace && app) window.NPDevWorkspace.open(app, action.workspace);
      else if (window.__npdevShowScreen) window.__npdevShowScreen("run");
      return;
    }
    if (action.screen && window.__npdevShowScreen) {
      window.__npdevShowScreen(action.screen);
      if (action.intent === "create") {
        const field = document.getElementById("new-app-name");
        if (field) field.focus();
      }
    }
  }

  function strip(c) {
    const apps = t(TEXT.apps);
    const n = c.apps.length;
    const parts = [];
    if (!c.ran) parts.push(t(TEXT.stripUnchecked));
    else parts.push(c.failures.length ? `✕ ${t(TEXT.stripNotReady)}` : `✓ ${t(TEXT.stripReady)}`);
    parts.push(`${n} ${n === 1 ? apps[0] : apps[1]}`);
    parts.push(`${c.running.length} ${t(TEXT.running)}`);
    parts.push(`${t(TEXT.lastProblem)}: ${c.failed ? c.failed.intent : t(TEXT.none)}`);
    return parts.join(" · ");
  }

  function render(c) {
    const root = document.getElementById("home-root");
    if (!root) return;
    root.textContent = "";
    const d = pick(c);
    const card = document.createElement("div");
    card.className = "home-card";
    card.dataset.when = d ? d.when : "none";
    const h = document.createElement("h2");
    h.className = "home-title";
    h.textContent = d ? fill(t(d.card), c) : t(TEXT.fallbackCard);
    card.appendChild(h);
    if (d && d.detail) {
      const p = document.createElement("p");
      p.className = "home-detail";
      p.textContent = fill(t(d.detail), c);
      card.appendChild(p);
    }
    if (d && d.action) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "primary home-action";
      b.textContent = t(d.action.label);
      b.addEventListener("click", () => perform(d.action, c));
      card.appendChild(b);
    }
    const s = document.createElement("p");
    s.className = "home-strip";
    s.textContent = strip(c);
    root.append(card, s);
  }

  async function refresh() {
    if (busy) return;
    busy = true;
    const root = document.getElementById("home-root");
    if (root && !root.firstChild) root.textContent = t(TEXT.checking);
    try {
      if (!decisions.length) await loadDecisions();
      render(await gather());
    } finally {
      busy = false;
    }
  }

  window.__npdevRefreshHome = refresh;
  document.addEventListener("npdev:lang-changed", () => {
    if (!document.getElementById("screen-home").hidden) refresh();
  });
  // Test hook: forget what Home has cached so a changed fixture or scenario is re-read.
  function reset(forgetThatItEverRan) {
    doctorCache = null;
    window.__npdevLastDoctor = null;
    if (!forgetThatItEverRan) return markRan();
    try {
      window.localStorage.removeItem(DOCTOR_FLAG);
    } catch (e) {
      /* nothing to clear */
    }
  }
  window.NPDevHome = { refresh, pick, gather, reset, decisionsLoaded: () => decisions.length };
})();
