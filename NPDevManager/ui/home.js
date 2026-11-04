// Home (NAV-2, HOME-1): the screen the Manager opens on.
//
// From top to bottom:
//   1. a greeting and a Refresh button;
//   2. the NEXT STEP card -- ONE card that says what to do next, with ONE button. The decisions are DATA
//      (`homeDecisions` in navigation.json), evaluated top to bottom, the first that applies wins;
//   3. four status tiles (computer, apps, running, AI), each a button that goes where that status is fixed;
//   4. "Your apps" -- every app with its state and the buttons that matter (Open, Start, Stop, Manage), so the
//      common case never needs another screen -- and "Recent activity" (what this window did lately);
//   5. shortcuts to the screens people come back to.
//
// Inputs, all existing commands: `check_doctor` (only after the user has run the computer check once --
// Home never probes a machine nobody asked about), `monitor_scan` (the apps and whether they run),
// `last_failed_run` and `recent_operations` (the Manager's run recorder, SUP-1), and `providers_list`
// (which AI providers have a key). Start and stop are the Monitor's own `start_app` / `stop_app`.
//
// Everything is built with DOM calls, never innerHTML: app names and paths come from disk.

(function () {
  "use strict";
  const invoke = (name, args) => window.__TAURI__.core.invoke(name, args);
  const I18n = () => window.NPDevI18n;
  const lang = () => (I18n() && I18n().lang) || "en";
  const t = (obj) => (I18n() ? I18n().t(obj) : (obj && obj.en) || "");
  const DOCTOR_FLAG = "npdev.manager.doctorRan";
  const DOCTOR_TTL_MS = 60 * 1000;
  const PROVIDERS_TTL_MS = 60 * 1000;
  const MAX_APPS = 6;
  const MAX_ACTIVITY = 5;

  const TEXT = {
    checking: { en: "Looking at your computer…", pt: "Olhando o seu computador…" },
    morning: { en: "Good morning", pt: "Bom dia" },
    afternoon: { en: "Good afternoon", pt: "Boa tarde" },
    evening: { en: "Good evening", pt: "Boa noite" },
    lede: { en: "Here is NPDev on this computer right now.", pt: "Este é o NPDev neste computador agora." },
    refresh: { en: "Refresh", pt: "Atualizar" },
    updated: { en: "Updated {time}", pt: "Atualizado às {time}" },
    nextStep: { en: "Next step", pt: "Próximo passo" },
    allGood: { en: "All good", pt: "Tudo certo" },
    fallbackCard: { en: "Everything looks fine", pt: "Tudo certo por aqui" },

    tileComputer: { en: "Computer", pt: "Computador" },
    computerReady: { en: "Ready", pt: "Pronto" },
    computerMissing: { en: "{n} missing", pt: "Faltam {n}" },
    computerUnchecked: { en: "Not checked", pt: "Não verificado" },
    computerHint: { en: "Java, Python and NPDev", pt: "Java, Python e NPDev" },
    tileApps: { en: "Apps", pt: "Apps" },
    appsHintNone: { en: "Create your first one", pt: "Crie o primeiro" },
    appsHintSome: { en: "{n} not built yet", pt: "{n} ainda não gerados" },
    appsHintAll: { en: "All built", pt: "Todos gerados" },
    tileRunning: { en: "Running", pt: "Em execução" },
    runningHintNone: { en: "Nothing is running", pt: "Nada está rodando" },
    runningHintProblems: { en: "{n} need attention", pt: "{n} precisam de atenção" },
    runningHintOk: { en: "All healthy", pt: "Todos saudáveis" },
    tileAi: { en: "AI", pt: "IA" },
    aiReady: { en: "{n} ready", pt: "{n} prontos" },
    aiNone: { en: "No key yet", pt: "Nenhuma chave" },
    aiUnknown: { en: "—", pt: "—" },
    aiHintReady: { en: "Providers you can use", pt: "Provedores que você pode usar" },
    aiHintNone: { en: "Add one to ask AI", pt: "Adicione um para usar a IA" },
    aiHintUnknown: { en: "Could not ask", pt: "Não foi possível consultar" },

    yourApps: { en: "Your apps", pt: "Seus apps" },
    seeAll: { en: "See all", pt: "Ver todos" },
    andMore: { en: "and {n} more", pt: "e mais {n}" },
    noAppsYet: { en: "No apps yet. An app starts as a name; NPDev builds the rest.", pt: "Nenhum app ainda. Um app começa com um nome; o NPDev faz o resto." },
    createApp: { en: "Create an app", pt: "Criar um app" },
    open: { en: "Open", pt: "Abrir" },
    start: { en: "Start", pt: "Iniciar" },
    stop: { en: "Stop", pt: "Parar" },
    manage: { en: "Manage", pt: "Gerenciar" },
    starting: { en: "Starting…", pt: "Iniciando…" },
    stopping: { en: "Stopping…", pt: "Parando…" },
    notBuilt: { en: "Not built yet", pt: "Ainda não gerado" },
    buildFirst: { en: "Build it first (Manage)", pt: "Gere primeiro (Gerenciar)" },
    stRunning: { en: "Running", pt: "Rodando" },
    stStarting: { en: "Starting", pt: "Iniciando" },
    stStopped: { en: "Stopped", pt: "Parado" },
    stProblem: { en: "Problem", pt: "Problema" },
    stUnknown: { en: "Unknown", pt: "Desconhecido" },

    activity: { en: "Recent activity", pt: "Atividade recente" },
    allTasks: { en: "All tasks", pt: "Todas as tarefas" },
    noActivity: { en: "Nothing has run in this window yet. Builds, installs and starts show up here.", pt: "Nada rodou nesta janela ainda. Gerações, instalações e inícios aparecem aqui." },
    opRunning: { en: "Running", pt: "Rodando" },
    opOk: { en: "Done", pt: "Concluído" },
    opFailed: { en: "Failed", pt: "Falhou" },
    getHelp: { en: "Get help", pt: "Pedir ajuda" },

    shortcuts: { en: "Shortcuts", pt: "Atalhos" },
    scCreate: { en: "New app", pt: "Novo app" },
    scCreateHint: { en: "Start from a name", pt: "Comece com um nome" },
    scEdit: { en: "Edit an app", pt: "Editar um app" },
    scEditHint: { en: "Change its model", pt: "Altere o modelo" },
    scAsk: { en: "Ask AI", pt: "Perguntar à IA" },
    scAskHint: { en: "Describe a change", pt: "Descreva uma mudança" },
    scMonitor: { en: "Running apps", pt: "Apps em execução" },
    scMonitorHint: { en: "Logs and health", pt: "Logs e saúde" },
    scProviders: { en: "AI providers", pt: "Provedores de IA" },
    scProvidersHint: { en: "Keys and models", pt: "Chaves e modelos" },
    scAppearance: { en: "Appearance", pt: "Aparência" },
    scAppearanceHint: { en: "Themes and text size", pt: "Temas e tamanho do texto" },
  };

  // The icon for each decision's card. Decorative: the title says it in words.
  const ICON = {
    doctorNeverRun: "🔍",
    doctorHasFailures: "🔧",
    noApps: "✨",
    lastOperationFailed: "⚠",
    appsAllStopped: "⏸",
    appRunning: "▶",
    none: "✓",
  };

  let decisions = [];
  let busy = false;
  let doctorCache = null; // { at, checks }
  let providersCache = null; // { at, value }
  let lastContext = null;
  const pending = new Map(); // appDir -> "starting" | "stopping" | error text
  let pollTimer = null;

  // ------------------------------------------------------------------------------------------- inputs

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

  // Providers that can answer right now: a key in the vault or the environment, or none needed (local
  // models), and not switched off. null means "could not ask" -- the tile says so instead of guessing zero.
  async function readProviders() {
    if (providersCache && Date.now() - providersCache.at < PROVIDERS_TTL_MS) return providersCache.value;
    let value = null;
    try {
      const r = await invoke("providers_list");
      if (r && Array.isArray(r.providers)) {
        const usable = r.providers.filter((p) => !p.disabled && ["vault", "env", "none-needed"].includes(p.keySource));
        const keyed = usable.filter((p) => p.keySource !== "none-needed");
        value = { usable: usable.length, keyed: keyed.length, names: usable.map((p) => p.name || p.id) };
      }
    } catch (e) {
      value = null;
    }
    providersCache = { at: Date.now(), value };
    return value;
  }

  const safe = (p, fallback) => p.catch(() => fallback);

  async function gather() {
    const ran = flagSet() || !!doctorCache || !!window.__npdevLastDoctor;
    const [checks, apps, failed, ops, ai] = await Promise.all([
      ran ? readDoctor() : Promise.resolve(null),
      readApps(),
      safe(invoke("last_failed_run"), null),
      safe(invoke("recent_operations"), []),
      readProviders(),
    ]);
    const failures = (checks || []).filter((c) => c.status === "fail" && !isNotApplicable(c));
    const running = apps.filter((a) => a.health === "running");
    return { ran, checks, failures, apps, running, failed: failed || null, ops: Array.isArray(ops) ? ops : [], ai, at: new Date() };
  }

  // ------------------------------------------------------------------------------------ the decision

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

  // The app the card talks about: the running one, else the one most worth starting (built first).
  const focusApp = (c) => c.running[0] || c.apps.find((a) => a.jarPath) || c.apps[0] || {};

  function fill(text, c) {
    const first = focusApp(c);
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

  const go = (screen) => window.__npdevShowScreen && window.__npdevShowScreen(screen);

  function openWorkspace(app, tab) {
    if (window.NPDevWorkspace && app && app.appDir) {
      window.NPDevWorkspace.open({ name: app.name, directory: app.appDir, appDir: app.appDir }, tab);
    } else {
      go("apps");
    }
  }

  function goCreate() {
    go("apps");
    const field = document.getElementById("new-app-name");
    if (field) field.focus();
  }

  function openSupport(c) {
    if (window.NPDevSupport) {
      window.NPDevSupport.openSupportDialog({ operation: c.failed && c.failed.operation, screen: "home" }, null);
    }
  }

  function perform(action, c) {
    if (!action) return;
    if (action.kind === "supportReport") return openSupport(c);
    if (action.kind === "openUrl") {
      const url = (c.running[0] || {}).baseUrl;
      if (url) invoke("open_url", { url }).catch(() => {});
      return;
    }
    if (action.workspace) {
      const app = focusApp(c);
      if (app.appDir) openWorkspace(app, action.workspace);
      else go("run");
      return;
    }
    if (action.screen) {
      if (action.intent === "create") return goCreate();
      go(action.screen);
    }
  }

  // ------------------------------------------------------------------------------------------ drawing

  function h(tag, attrs, ...kids) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === "class") n.className = v;
      else if (k === "on") for (const [ev, fn] of Object.entries(v)) n.addEventListener(ev, fn);
      else if (k === "data") for (const [dk, dv] of Object.entries(v)) n.dataset[dk] = dv;
      else n.setAttribute(k, v === true ? "" : String(v));
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) n.append(kid);
    return n;
  }

  const sub = (obj, vars) => {
    let s = t(obj);
    for (const [k, v] of Object.entries(vars || {})) s = s.replace(`{${k}}`, String(v));
    return s;
  };

  function greeting() {
    const hour = new Date().getHours();
    return t(hour < 12 ? TEXT.morning : hour < 18 ? TEXT.afternoon : TEXT.evening);
  }

  function clock(date) {
    try {
      return date.toLocaleTimeString(lang() === "pt" ? "pt-BR" : "en-US", { hour: "2-digit", minute: "2-digit" });
    } catch (e) {
      return date.toTimeString().slice(0, 5);
    }
  }

  function ago(iso) {
    const then = Date.parse(iso || "");
    if (!then) return "";
    const secs = Math.round((then - Date.now()) / 1000);
    const units = [["day", 86400], ["hour", 3600], ["minute", 60]];
    try {
      const fmt = new Intl.RelativeTimeFormat(lang() === "pt" ? "pt-BR" : "en", { numeric: "auto" });
      for (const [unit, size] of units) if (Math.abs(secs) >= size) return fmt.format(Math.round(secs / size), unit);
      return fmt.format(0, "second");
    } catch (e) {
      return new Date(then).toLocaleString();
    }
  }

  function head(c) {
    const refreshBtn = h("button", { type: "button", class: "home-refresh", on: { click: () => refresh() } }, "↻ ", t(TEXT.refresh));
    return h("header", { class: "home-head" },
      h("div", {},
        h("p", { class: "home-greeting" }, greeting()),
        h("p", { class: "home-lede" }, t(TEXT.lede))),
      h("div", { class: "home-head-tools" },
        h("span", { class: "home-updated" }, sub(TEXT.updated, { time: clock(c.at) })),
        refreshBtn));
  }

  function nextCard(c) {
    const d = pick(c);
    const when = d ? d.when : "none";
    const body = h("div", { class: "home-card-body" },
      h("p", { class: "home-eyebrow" }, t(d ? TEXT.nextStep : TEXT.allGood)),
      h("h2", { class: "home-title" }, d ? fill(t(d.card), c) : t(TEXT.fallbackCard)),
      d && d.detail ? h("p", { class: "home-detail" }, fill(t(d.detail), c)) : null);
    const card = h("section", { class: "home-card", data: { when }, "aria-labelledby": "home-next-title" },
      h("span", { class: "home-card-icon", "aria-hidden": "true" }, ICON[when] || ICON.none), body);
    body.querySelector(".home-title").id = "home-next-title";
    if (d && d.action) {
      card.append(h("div", { class: "home-card-actions" },
        h("button", { type: "button", class: "primary home-action", on: { click: () => perform(d.action, c) } }, t(d.action.label))));
    }
    return card;
  }

  function tile(id, label, value, hint, tone, onClick) {
    return h("button", { type: "button", class: "home-tile", data: { tile: id, tone }, on: { click: onClick } },
      h("span", { class: "home-tile-label" }, label),
      h("span", { class: "home-tile-value" }, value),
      h("span", { class: "home-tile-hint" }, hint));
  }

  const isProblem = (a) => !["running", "stopped", "starting"].includes(a.health);

  function tiles(c) {
    const unbuilt = c.apps.filter((a) => !a.jarPath).length;
    const problems = c.apps.filter(isProblem).length;
    const computer = !c.ran
      ? tile("computer", t(TEXT.tileComputer), t(TEXT.computerUnchecked), t(TEXT.computerHint), "muted", () => go("ready"))
      : c.failures.length
        ? tile("computer", t(TEXT.tileComputer), "✕ " + sub(TEXT.computerMissing, { n: c.failures.length }), t(TEXT.computerHint), "negative", () => go("install"))
        : tile("computer", t(TEXT.tileComputer), "✓ " + t(TEXT.computerReady), t(TEXT.computerHint), "positive", () => go("ready"));
    const apps = tile("apps", t(TEXT.tileApps), String(c.apps.length),
      c.apps.length === 0 ? t(TEXT.appsHintNone) : unbuilt ? sub(TEXT.appsHintSome, { n: unbuilt }) : t(TEXT.appsHintAll),
      c.apps.length === 0 ? "warning" : "neutral", () => (c.apps.length ? go("apps") : goCreate()));
    const running = tile("running", t(TEXT.tileRunning), String(c.running.length),
      problems ? sub(TEXT.runningHintProblems, { n: problems }) : c.running.length ? t(TEXT.runningHintOk) : t(TEXT.runningHintNone),
      problems ? "warning" : c.running.length ? "positive" : "neutral", () => go("monitor"));
    const ai = c.ai == null
      ? tile("ai", t(TEXT.tileAi), t(TEXT.aiUnknown), t(TEXT.aiHintUnknown), "muted", () => go("providers"))
      : c.ai.usable
        ? tile("ai", t(TEXT.tileAi), sub(TEXT.aiReady, { n: c.ai.usable }), t(TEXT.aiHintReady), "positive", () => go("providers"))
        : tile("ai", t(TEXT.tileAi), t(TEXT.aiNone), t(TEXT.aiHintNone), "warning", () => go("providers"));
    if (c.ai && c.ai.names.length) ai.title = c.ai.names.join(", ");
    // `home-strip` is the one-line summary screen readers (and the render check) read; the tiles are its picture.
    return h("section", { class: "home-tiles", "aria-label": stripText(c) }, computer, apps, running, ai);
  }

  function stripText(c) {
    const n = c.apps.length;
    const parts = [];
    if (!c.ran) parts.push(t({ en: "Computer not checked yet", pt: "Computador ainda não verificado" }));
    else parts.push(c.failures.length ? t({ en: "Computer not ready", pt: "Computador não está pronto" }) : t({ en: "Computer ready", pt: "Computador pronto" }));
    parts.push(`${n} ${n === 1 ? "app" : "apps"}`);
    parts.push(`${c.running.length} ${t({ en: "running", pt: "em execução" })}`);
    parts.push(`${t({ en: "last problem", pt: "último problema" })}: ${c.failed ? c.failed.intent : t({ en: "none", pt: "nenhum" })}`);
    return parts.join(" · ");
  }

  function statusOf(a) {
    const p = pending.get(a.appDir);
    if (p === "starting" || a.health === "starting") return ["starting", t(TEXT.stStarting)];
    if (a.health === "running") return ["running", t(TEXT.stRunning)];
    if (a.health === "stopped") return ["stopped", t(TEXT.stStopped)];
    if (a.health === "unknown" || !a.health) return ["unknown", t(TEXT.stUnknown)];
    return ["problem", t(TEXT.stProblem)];
  }

  const RANK = { running: 0, starting: 1, problem: 2, stopped: 3, unknown: 4 };

  function shortDir(p) {
    const parts = String(p || "").split(/[\\/]/).filter(Boolean);
    return parts.slice(-2).join("\\");
  }

  async function startApp(a) {
    pending.set(a.appDir, "starting");
    redraw();
    try {
      await invoke("start_app", { appDir: a.appDir });
      pollUntilSettled();
    } catch (e) {
      pending.set(a.appDir, String(e));
      redraw();
    }
  }

  async function stopApp(a) {
    pending.set(a.appDir, "stopping");
    redraw();
    try {
      await invoke("stop_app", { appDir: a.appDir });
      pending.delete(a.appDir);
      setTimeout(refresh, 1200);
    } catch (e) {
      pending.set(a.appDir, String(e));
      redraw();
    }
  }

  // After a start, look again every few seconds until the app answers (or a minute passes).
  function pollUntilSettled() {
    clearTimeout(pollTimer);
    let tries = 0;
    const tick = async () => {
      tries += 1;
      await refresh();
      const waiting = lastContext && lastContext.apps.some((a) => pending.get(a.appDir) === "starting" && a.health !== "running");
      if (!waiting || tries >= 12) {
        for (const [dir, v] of pending) if (v === "starting") pending.delete(dir);
        if (waiting) redraw();
        return;
      }
      pollTimer = setTimeout(tick, 5000);
    };
    pollTimer = setTimeout(tick, 3000);
  }

  function appRow(a, dupNames) {
    const [state, word] = statusOf(a);
    const p = pending.get(a.appDir);
    const busyNow = p === "starting" || p === "stopping";
    const error = p && !busyNow ? p : null;
    const subline = a.health === "running" && a.baseUrl
      ? a.baseUrl
      : !a.jarPath ? t(TEXT.notBuilt)
        : state === "problem" && a.healthDetail ? a.healthDetail
          : [a.engine, a.port ? `:${a.port}` : null].filter(Boolean).join(" ");
    const actions = h("div", { class: "home-app-actions" });
    if (a.health === "running") {
      actions.append(
        h("button", { type: "button", class: "primary", disabled: !a.baseUrl, on: { click: () => invoke("open_url", { url: a.baseUrl }).catch(() => {}) } }, t(TEXT.open)),
        h("button", { type: "button", disabled: busyNow, on: { click: () => stopApp(a) } }, p === "stopping" ? t(TEXT.stopping) : t(TEXT.stop)));
    } else {
      actions.append(h("button", {
        type: "button", disabled: busyNow || !a.jarPath, title: a.jarPath ? null : t(TEXT.buildFirst),
        on: { click: () => startApp(a) },
      }, p === "starting" ? t(TEXT.starting) : t(TEXT.start)));
    }
    actions.append(h("button", { type: "button", class: "ghost", on: { click: () => openWorkspace(a, "overview") } }, t(TEXT.manage)));
    return h("li", { class: "home-app", data: { state } },
      h("span", { class: "home-pill", data: { state } }, h("span", { class: "home-dot", "aria-hidden": "true" }), word),
      h("div", { class: "home-app-text" },
        h("span", { class: "home-app-name", title: a.appDir }, a.name || shortDir(a.appDir),
          dupNames.has(a.name) ? h("span", { class: "home-app-dir" }, " · " + shortDir(a.appDir)) : null),
        h("span", { class: "home-app-sub", title: subline }, subline),
        error ? h("span", { class: "home-app-error", role: "alert" }, error) : null),
      actions);
  }

  function panel(id, title, linkLabel, onLink, ...body) {
    return h("section", { class: "home-panel", "aria-labelledby": `home-${id}-title` },
      h("div", { class: "home-panel-head" },
        h("h2", { id: `home-${id}-title` }, title),
        linkLabel ? h("button", { type: "button", class: "home-link", on: { click: onLink } }, linkLabel, " →") : null),
      ...body);
  }

  function appsPanel(c) {
    if (!c.apps.length) {
      return panel("apps", t(TEXT.yourApps), null, null,
        h("div", { class: "home-empty" },
          h("p", {}, t(TEXT.noAppsYet)),
          h("button", { type: "button", class: "primary", on: { click: goCreate } }, "+ ", t(TEXT.createApp))));
    }
    const seen = new Map();
    for (const a of c.apps) seen.set(a.name, (seen.get(a.name) || 0) + 1);
    const dupNames = new Set([...seen].filter(([, n]) => n > 1).map(([name]) => name));
    const sorted = c.apps.slice().sort((x, y) =>
      RANK[statusOf(x)[0]] - RANK[statusOf(y)[0]] || (y.jarPath ? 1 : 0) - (x.jarPath ? 1 : 0) || String(x.name).localeCompare(String(y.name)));
    const list = h("ul", { class: "home-apps" }, sorted.slice(0, MAX_APPS).map((a) => appRow(a, dupNames)));
    const foot = h("div", { class: "home-panel-foot" },
      h("button", { type: "button", class: "ghost", on: { click: goCreate } }, "+ ", t(TEXT.createApp)),
      c.apps.length > MAX_APPS ? h("span", { class: "home-more" }, sub(TEXT.andMore, { n: c.apps.length - MAX_APPS })) : null);
    return panel("apps", t(TEXT.yourApps), t(TEXT.seeAll), () => go("monitor"), list, foot);
  }

  function activityPanel(c) {
    const ops = c.ops.slice(0, MAX_ACTIVITY);
    if (!ops.length) {
      return panel("activity", t(TEXT.activity), t(TEXT.allTasks), () => go("jobs"),
        h("p", { class: "home-empty-line" }, t(TEXT.noActivity)));
    }
    const list = h("ul", { class: "home-ops" }, ops.map((op) => {
      const state = op.running ? "running" : op.ok ? "ok" : "failed";
      const word = t(op.running ? TEXT.opRunning : op.ok ? TEXT.opOk : TEXT.opFailed);
      const mark = op.running ? "…" : op.ok ? "✓" : "✕";
      const help = state === "failed"
        ? h("button", { type: "button", class: "home-link", on: { click: () => window.NPDevSupport && window.NPDevSupport.openSupportDialog({ operation: op.operation, screen: "home" }, null) } }, t(TEXT.getHelp))
        : null;
      return h("li", { class: "home-op", data: { state } },
        h("span", { class: "home-op-mark", "aria-hidden": "true" }, mark),
        h("div", { class: "home-op-text" },
          h("span", { class: "home-op-intent" }, op.intent || op.operation),
          h("span", { class: "home-op-meta" }, word, " · ", ago(op.endedAt || op.startedAt))),
        help);
    }));
    return panel("activity", t(TEXT.activity), t(TEXT.allTasks), () => go("jobs"), list);
  }

  function shortcuts(c) {
    const app = focusApp(c);
    const withApp = (tab) => () => (app.appDir ? openWorkspace(app, tab) : go("apps"));
    const items = [
      ["✚", TEXT.scCreate, TEXT.scCreateHint, goCreate],
      ["✎", TEXT.scEdit, TEXT.scEditHint, withApp("edit")],
      ["✦", TEXT.scAsk, TEXT.scAskHint, withApp("ask-ai")],
      ["◉", TEXT.scMonitor, TEXT.scMonitorHint, () => go("monitor")],
      ["⚿", TEXT.scProviders, TEXT.scProvidersHint, () => go("providers")],
      ["◐", TEXT.scAppearance, TEXT.scAppearanceHint, () => go("appearance")],
    ];
    return h("section", { class: "home-shortcuts-wrap", "aria-labelledby": "home-shortcuts-title" },
      h("h2", { id: "home-shortcuts-title", class: "home-section-title" }, t(TEXT.shortcuts)),
      h("div", { class: "home-shortcuts" }, items.map(([icon, label, hint, fn]) =>
        h("button", { type: "button", class: "home-shortcut", on: { click: fn } },
          h("span", { class: "home-shortcut-icon", "aria-hidden": "true" }, icon),
          h("span", { class: "home-shortcut-text" },
            h("span", { class: "home-shortcut-label" }, t(label)),
            h("span", { class: "home-shortcut-hint" }, t(hint)))))));
  }

  function render(c) {
    lastContext = c;
    const root = document.getElementById("home-root");
    if (!root) return;
    // Keep focus on the same control across a redraw (Refresh, Start, Stop all redraw).
    const active = document.activeElement;
    const focusKey = active && root.contains(active) ? active.textContent : null;
    root.textContent = "";
    root.append(
      head(c),
      nextCard(c),
      tiles(c),
      h("p", { class: "home-strip visually-hidden" }, stripText(c)),
      h("div", { class: "home-columns" }, appsPanel(c), activityPanel(c)),
      shortcuts(c));
    if (focusKey) {
      const again = [...root.querySelectorAll("button")].find((b) => b.textContent === focusKey);
      if (again) again.focus();
    }
  }

  const redraw = () => lastContext && render(lastContext);

  async function refresh() {
    if (busy) return;
    busy = true;
    const root = document.getElementById("home-root");
    if (root && !root.firstChild) root.append(h("p", { class: "home-loading" }, t(TEXT.checking)));
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
    providersCache = null;
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
