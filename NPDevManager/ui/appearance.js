// Appearance & accessibility (THM-3, THM-4, ACC-1).
//
// Two jobs in one file:
//   1. the CORE: keep the Manager's chosen theme and accessibility preferences applied -- at start
//      (index.html applies a cached snapshot before first paint, so there is no flash), after the
//      authoritative `appearance_get`, and live when the operating system flips between light and dark;
//   2. the SCREEN: the theme gallery, "Load a theme file", "Ask an AI for a theme / variation",
//      export/remove, and the accessibility preferences.
//
// A theme is DATA (theme-engine.js turns it into CSS custom properties; it can contain no CSS). The engine
// validates structure AND contrast in the webview; the Rust side (themes.rs) re-validates structure before
// it writes a file, because the file name comes from the theme's id.
//
// The persisted choice lives in manager.json (Rust). localStorage only holds a SNAPSHOT of the resolved
// theme so the next start can paint correctly before any command returns.

(function () {
  "use strict";
  const T = window.NPDevTheme;
  if (!T) return;
  const invoke = (name, args) => window.__TAURI__.core.invoke(name, args);
  const CACHE_KEY = "npdev.manager.appearance";
  const t = (obj) => (window.NPDevI18n ? window.NPDevI18n.t(obj) : obj.en);

  const DEFAULT_A11Y = { text_scale: 1, motion: "system", focus: "auto", spacing: "normal", links: "auto", cvd: "off" };

  const TEXT = {
    title: { en: "Appearance & accessibility", pt: "Aparência e acessibilidade" },
    lead: {
      en: "Make NPDev yours. Every theme here passes the WCAG contrast checks, and so must any theme you load.",
      pt: "Deixe o NPDev com a sua cara. Todo tema aqui passa nas verificações de contraste WCAG, e qualquer tema que você carregar também precisa passar.",
    },
    theme: { en: "Theme", pt: "Tema" },
    system: { en: "System", pt: "Sistema" },
    systemDesc: { en: "Light or Dark, following your computer", pt: "Claro ou Escuro, seguindo o seu computador" },
    mode: { en: "Light or dark (for themes that have both)", pt: "Claro ou escuro (para temas que têm os dois)" },
    modeSystem: { en: "Follow my computer", pt: "Seguir o meu computador" },
    modeLight: { en: "Light", pt: "Claro" },
    modeDark: { en: "Dark", pt: "Escuro" },
    loadTitle: { en: "Load a theme file", pt: "Carregar um arquivo de tema" },
    loadHelp: {
      en: "A theme is a small .json file. You see it live first and can undo; it is only kept when you say so.",
      pt: "Um tema é um arquivo .json pequeno. Você o vê ao vivo primeiro e pode desfazer; ele só é mantido se você disser.",
    },
    loadFile: { en: "Theme file", pt: "Arquivo de tema" },
    askTitle: { en: "Ask an AI for a theme", pt: "Peça um tema a uma IA" },
    askDescribe: { en: "Describe it", pt: "Descreva" },
    askPlaceholder: { en: "cozy, like a coffee shop on a rainy evening", pt: "aconchegante, como uma cafeteria numa noite de chuva" },
    askVariation: { en: "as a variation of the current theme", pt: "como variação do tema atual" },
    askCopy: { en: "Copy the prompt", pt: "Copiar o pedido" },
    askCopied: { en: "Prompt copied. Paste it into any AI chat.", pt: "Pedido copiado. Cole em qualquer chat de IA." },
    askPaste: { en: "Paste the AI's answer", pt: "Cole a resposta da IA" },
    askPreview: { en: "Preview", pt: "Visualizar" },
    keep: { en: "Keep", pt: "Manter" },
    undo: { en: "Undo", pt: "Desfazer" },
    backIn: { en: "back in", pt: "volta em" },
    previewing: { en: "Previewing", pt: "Visualizando" },
    weakest: { en: "Weakest pair", pt: "Par mais fraco" },
    needs: { en: "needs", pt: "precisa de" },
    notLoaded: { en: "Not loaded. Nothing changed.", pt: "Não carregado. Nada mudou." },
    ownId: {
      en: "That id belongs to one of NPDev's own themes; give yours a different id.",
      pt: "Esse id pertence a um tema do próprio NPDev; dê outro id ao seu.",
    },
    notJson: { en: "That is not valid JSON", pt: "Isso não é um JSON válido" },
    askJsonOnly: { en: "Ask the AI to reply with the JSON only.", pt: "Peça à IA que responda só com o JSON." },
    kept: { en: "kept", pt: "mantido" },
    undone: { en: "Theme undone.", pt: "Tema desfeito." },
    exportBtn: { en: "Export this theme", pt: "Exportar este tema" },
    removeBtn: { en: "Remove this theme", pt: "Remover este tema" },
    removed: { en: "Theme removed.", pt: "Tema removido." },
    a11y: { en: "Accessibility", pt: "Acessibilidade" },
    textSize: { en: "Text size", pt: "Tamanho do texto" },
    motion: { en: "Motion", pt: "Movimento" },
    motionSystem: { en: "Follow my computer", pt: "Seguir o meu computador" },
    motionReduce: { en: "Reduce", pt: "Reduzir" },
    focus: { en: "Keyboard focus", pt: "Foco do teclado" },
    focusAuto: { en: "Normal", pt: "Normal" },
    focusAlways: { en: "Always visible", pt: "Sempre visível" },
    spacing: { en: "Text spacing", pt: "Espaçamento do texto" },
    spacingNormal: { en: "Normal", pt: "Normal" },
    spacingWide: { en: "Wide", pt: "Largo" },
    links: { en: "Links", pt: "Links" },
    linksAuto: { en: "Normal", pt: "Normal" },
    linksUnderline: { en: "Always underlined", pt: "Sempre sublinhados" },
    cvd: { en: "Status colours", pt: "Cores de status" },
    cvdOff: { en: "Normal", pt: "Normal" },
    cvdSafe: { en: "Colour-blind safe", pt: "Seguras para daltonismo" },
    sample: { en: "Sample", pt: "Exemplo" },
    pass: { en: "Pass", pt: "Passou" },
    fail: { en: "Fail", pt: "Falhou" },
    warn: { en: "Warning", pt: "Atenção" },
    info: { en: "Info", pt: "Info" },
    link: { en: "a link", pt: "um link" },
    saveFailed: { en: "Could not save the choice", pt: "Não consegui salvar a escolha" },
    browseTitle: { en: "Browse themes", pt: "Procurar temas" },
    browseHelp: {
      en: "Themes shared by the community. Nothing is downloaded until you ask, and every file is checked against the catalogue's fingerprint (sha256) before it is imported.",
      pt: "Temas compartilhados pela comunidade. Nada é baixado até você pedir, e cada arquivo é conferido com a impressão digital (sha256) do catálogo antes de ser importado.",
    },
    browseBtn: { en: "Load the catalogue", pt: "Carregar o catálogo" },
    browseLoading: { en: "Loading the catalogue…", pt: "Carregando o catálogo…" },
    browseEmpty: { en: "The catalogue has no themes yet.", pt: "O catálogo ainda não tem temas." },
    browseFailed: { en: "Could not load the catalogue", pt: "Não consegui carregar o catálogo" },
    browseStub: { en: "Stub mode: showing the captured catalogue, not the network.", pt: "Modo de demonstração: mostrando o catálogo capturado, não a rede." },
    browseInstalled: { en: "Installed", pt: "Instalado" },
    browsePreview: { en: "Preview", pt: "Pré-visualizar" },
    browseRefused: { en: "Refused. Nothing was imported.", pt: "Recusado. Nada foi importado." },
    shareBtn: { en: "Share this theme", pt: "Compartilhar este tema" },
    shareOpened: {
      en: "GitHub's new-file page is open in your browser. Review it and commit to submit your theme.",
      pt: "A página de novo arquivo do GitHub foi aberta no seu navegador. Revise e confirme para enviar seu tema.",
    },
    highContrastHint: {
      en: "For maximum legibility choose the High contrast theme.",
      pt: "Para máxima legibilidade escolha o tema Alto contraste.",
    },
  };

  const state = {
    appearance: { theme_id: "npdev-dark", mode: "system", a11y: Object.assign({}, DEFAULT_A11Y) },
    list: [], // theme_list: built-ins then user themes (original, un-resolved)
    catalog: {},
    previewTimer: null,
    previewing: false,
    // THM-10: the public catalogue, loaded only when the user asks (no network on opening the screen).
    catalogue: { status: "idle", themes: [], error: "", stub: false },
  };

  // ----------------------------------------------------------------------------------- applying

  const prefersDark = () => !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);

  function engineA11y(a) {
    return { textScale: a.text_scale, motion: a.motion, focus: a.focus, spacing: a.spacing, links: a.links, cvd: a.cvd };
  }

  function resolved(id) {
    const src = state.catalog[id] || state.catalog["npdev-dark"];
    if (!src) return null;
    try {
      return T.resolve(src, state.catalog);
    } catch (e) {
      return state.catalog["npdev-dark"] ? T.resolve(state.catalog["npdev-dark"], state.catalog) : null;
    }
  }

  function chosenTheme() {
    const id = state.appearance.theme_id;
    if (id === "system") return resolved(prefersDark() ? "npdev-dark" : "npdev-light");
    return resolved(id);
  }

  function applyNow(previewTheme) {
    const theme = previewTheme || chosenTheme();
    if (theme) T.apply(theme, { host: "manager", mode: state.appearance.mode, cvd: state.appearance.a11y.cvd });
    T.applyA11y(engineA11y(state.appearance.a11y));
  }

  // The snapshot index.html applies before first paint. Only complete, resolved themes are stored.
  function writeCache() {
    try {
      const snap = {
        id: state.appearance.theme_id,
        mode: state.appearance.mode,
        a11y: engineA11y(state.appearance.a11y),
        themes: { chosen: resolved(state.appearance.theme_id === "system" ? "npdev-dark" : state.appearance.theme_id),
                  light: resolved("npdev-light"), dark: resolved("npdev-dark") },
      };
      window.localStorage.setItem(CACHE_KEY, JSON.stringify(snap));
    } catch (e) {
      /* a private profile can refuse storage; the next start just paints the default first */
    }
  }

  async function persist() {
    try {
      const saved = await invoke("appearance_set", { appearance: state.appearance });
      if (saved && saved.theme_id) state.appearance = normalise(saved);
    } catch (err) {
      flash(`${t(TEXT.saveFailed)}: ${err}`, false);
    }
    writeCache();
  }

  function normalise(a) {
    a = a || {};
    return {
      theme_id: a.theme_id || "npdev-dark",
      mode: a.mode || "system",
      a11y: Object.assign({}, DEFAULT_A11Y, a.a11y || {}),
    };
  }

  async function loadList() {
    let list = [];
    try {
      const r = await invoke("theme_list");
      if (Array.isArray(r)) list = r;
    } catch (e) {
      list = [];
    }
    state.list = list;
    state.catalog = {};
    list.forEach((th) => (state.catalog[th.id] = th));
  }

  async function init() {
    await loadList();
    let a = null;
    try {
      a = await invoke("appearance_get");
    } catch (e) {
      a = null;
    }
    state.appearance = normalise(a);
    applyNow();
    writeCache();
  }

  // Follow the operating system live, for "System" and for themes with both schemes.
  if (window.matchMedia) {
    window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
      if (!state.previewing) applyNow();
    });
  }

  // ------------------------------------------------------------------------------------ screen

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === false || v == null) continue;
      if (k === "class") node.className = v;
      else if (k === "text") node.textContent = v;
      else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v === true ? "" : String(v));
    }
    for (const c of children.flat()) {
      if (c == null || c === false) continue;
      node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    }
    return node;
  }

  function displayName(th) {
    const lang = (window.NPDevI18n && window.NPDevI18n.lang) || "en";
    return (th.names && th.names[lang]) || th.name;
  }

  function swatches(th) {
    let c;
    try {
      c = T.resolve(th, state.catalog).colors;
    } catch (e) {
      c = null;
    }
    const wrap = el("div", { class: "theme-swatches", "aria-hidden": "true" });
    // "System" has no colours of its own: it shows the Light and Dark built-ins side by side.
    const light = resolved("npdev-light");
    const dark = resolved("npdev-dark");
    const colours = c
      ? [c.bg, c.surface, c.accent, c.positive, c.negative]
      : light && dark ? [light.colors.bg, light.colors.surface, dark.colors.surface, dark.colors.bg] : [];
    colours.forEach((x) => {
      const s = el("span");
      s.style.background = x; // a validated #rrggbb: themes cannot carry anything else
      wrap.appendChild(s);
    });
    return wrap;
  }

  function card(th, isSystem) {
    const id = isSystem ? "system" : th.id;
    const pressed = state.appearance.theme_id === id;
    const btn = el(
      "button",
      { type: "button", class: "theme-card", "data-theme": id, "aria-pressed": pressed ? "true" : "false",
        onclick: () => choose(id) },
      isSystem ? swatches({ colors: null }) : swatches(th),
      el("div", { class: "theme-meta" },
        el("b", { text: isSystem ? t(TEXT.system) : displayName(th) }),
        el("small", { text: isSystem ? t(TEXT.systemDesc) : th.description || "" }))
    );
    return btn;
  }

  async function choose(id) {
    cancelPreview(false);
    state.appearance.theme_id = id;
    await persist();
    applyNow();
    renderScreen();
  }

  function flash(message, ok) {
    const box = document.getElementById("appearance-flash");
    if (!box) return;
    box.className = "appearance-msg " + (ok ? "ok" : "bad");
    box.textContent = message;
  }

  function select(key, label, options) {
    const current = String(state.appearance.a11y[key]);
    const sel = el("select", { id: `a11y-${key}`, "data-a11y": key },
      options.map(([v, l]) => el("option", { value: String(v), selected: String(v) === current, text: t(l) })));
    sel.addEventListener("change", async () => {
      state.appearance.a11y[key] = key === "text_scale" ? Number(sel.value) : sel.value;
      applyNow();
      await persist();
    });
    return el("label", { class: "appearance-field", for: `a11y-${key}` }, el("span", { text: t(label) }), sel);
  }

  function chip(kind, icon, word) {
    // ACC-2: a status is an icon AND a word, never a colour alone.
    return el("span", { class: `status-chip ${kind}` }, el("span", { "aria-hidden": "true", text: icon }), " ", word);
  }

  // ----- loading / previewing a theme (a file, a paste, or a built-in sample) -------------------

  // An AI usually wraps the JSON in a code fence and often adds a sentence before or after it. Take the
  // fenced block if there is one, else everything from the first { to the last }.
  function stripFence(text) {
    const s = String(text).trim();
    const fenced = s.match(/```[a-zA-Z]*\s*([\s\S]*?)```/);
    if (fenced) return fenced[1].trim();
    const open = s.indexOf("{");
    const close = s.lastIndexOf("}");
    if (open >= 0 && close > open) return s.slice(open, close + 1);
    return s;
  }

  function cancelPreview(reapply) {
    if (state.previewTimer) clearInterval(state.previewTimer);
    state.previewTimer = null;
    state.previewing = false;
    if (reapply) applyNow();
  }

  function tryTheme(text, msgEl) {
    let theme;
    try {
      theme = JSON.parse(stripFence(text));
    } catch (e) {
      return showMsg(msgEl, false, `${t(TEXT.notJson)}: ${e.message}. ${t(TEXT.askJsonOnly)}`);
    }
    if (!theme || typeof theme !== "object") return showMsg(msgEl, false, `${t(TEXT.notLoaded)} ${t(TEXT.notJson)}`);
    const own = state.catalog[theme.id];
    if (own && own.builtin) {
      return showMsg(msgEl, false, `${t(TEXT.notLoaded)} ${t(TEXT.ownId)}`);
    }
    // Resolve against the catalogue plus this theme, so `basedOn` a built-in works.
    const catalog = Object.assign({}, state.catalog, theme.id ? { [theme.id]: theme } : {});
    let flat = theme;
    try {
      if (theme.basedOn) flat = Object.assign(T.resolve(theme, catalog), { id: theme.id, name: theme.name });
    } catch (e) {
      return showMsg(msgEl, false, `${t(TEXT.notLoaded)} ${e.message}`);
    }
    // The resolved theme carries the contrast rules; the original carries the structure rules (a
    // `basedOn` theme has no complete colour set of its own).
    const complete = T.validate(flat);
    const original = theme.basedOn ? T.validate(theme) : complete;
    const errors = [...new Set(complete.errors.concat(original.errors))];
    if (errors.length) {
      return showMsg(msgEl, false, `${t(TEXT.notLoaded)} ${errors.join(" · ")}`);
    }
    startPreview(theme, flat, complete, msgEl);
  }

  function startPreview(theme, flat, report, msgEl) {
    cancelPreview(false);
    state.previewing = true;
    applyNow(flat);
    let left = 15;
    const worst = report.contrast.slice().sort((a, b) => a.ratio - b.ratio)[0];
    msgEl.className = "appearance-msg ok";
    msgEl.textContent = "";
    const countdown = el("span", { class: "appearance-countdown", text: `${t(TEXT.backIn)} ${left}s` });
    const keepBtn = el("button", { type: "button", class: "primary", text: t(TEXT.keep) });
    const undoBtn = el("button", { type: "button", text: t(TEXT.undo) });
    msgEl.append(
      el("span", { text: `${t(TEXT.previewing)} ${displayName(theme)}. ` }),
      worst ? el("span", { text: `${t(TEXT.weakest)}: ${worst.pair} ${worst.ratio}:1 (${t(TEXT.needs)} ${worst.min}). ` }) : "",
      keepBtn, " ", undoBtn, " ", countdown
    );
    const undo = () => {
      cancelPreview(true);
      msgEl.className = "appearance-msg";
      msgEl.textContent = t(TEXT.undone);
    };
    state.previewTimer = setInterval(() => {
      left -= 1;
      countdown.textContent = `${t(TEXT.backIn)} ${left}s`;
      if (left <= 0) undo();
    }, 1000);
    undoBtn.addEventListener("click", undo);
    keepBtn.addEventListener("click", async () => {
      cancelPreview(false);
      try {
        await invoke("theme_import", { jsonText: JSON.stringify(theme, null, 2) });
      } catch (err) {
        applyNow();
        return showMsg(msgEl, false, `${t(TEXT.notLoaded)} ${err}`);
      }
      await loadList();
      state.appearance.theme_id = theme.id;
      await persist();
      applyNow();
      renderScreen();
      flash(`${displayName(theme)} ${t(TEXT.kept)}.`, true);
    });
  }

  function showMsg(node, ok, text) {
    node.className = "appearance-msg " + (ok ? "ok" : "bad");
    node.textContent = text;
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      return false;
    }
  }

  function exportCurrent() {
    const th = state.catalog[state.appearance.theme_id];
    if (!th) return;
    const copy = Object.assign({}, th);
    delete copy.builtin;
    const blob = new Blob([JSON.stringify(copy, null, 2) + "\n"], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = el("a", { href: url, download: `${th.id}.json` });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }

  async function removeCurrent() {
    const id = state.appearance.theme_id;
    try {
      await invoke("theme_delete", { id });
    } catch (err) {
      return flash(String(err), false);
    }
    await loadList();
    state.appearance.theme_id = "npdev-dark";
    await persist();
    applyNow();
    renderScreen();
    flash(t(TEXT.removed), true);
  }

  // ----- THM-10: the public catalogue ----------------------------------------------------------

  async function loadCatalogue() {
    state.catalogue = { status: "loading", themes: [], error: "", stub: false };
    renderScreen();
    try {
      const r = await invoke("theme_catalogue_index");
      state.catalogue = { status: "ready", themes: r.themes || [], error: "", stub: !!r.stub };
    } catch (err) {
      state.catalogue = { status: "error", themes: [], error: String(err), stub: false };
    }
    renderScreen();
    const first = document.querySelector("#catalogue-list button, #catalogue-msg");
    if (first && first.focus) first.focus();
  }

  // The Rust side downloads the file and returns it ONLY if its sha256 matches the index; a mismatch comes back
  // as an error and nothing is stored. A verified file then takes the same preview-then-keep path as any other.
  async function previewFromCatalogue(entry, msgEl) {
    showMsg(msgEl, true, t(TEXT.browseLoading));
    let text;
    try {
      text = await invoke("theme_catalogue_fetch", { id: entry.id });
    } catch (err) {
      return showMsg(msgEl, false, `${t(TEXT.browseRefused)} ${err}`);
    }
    tryTheme(text, msgEl);
  }

  function catalogueCard() {
    const c = state.catalogue;
    const msg = el("div", { id: "catalogue-msg", class: "appearance-msg", "aria-live": "polite", tabindex: "-1" });
    if (c.status === "error") showMsg(msg, false, `${t(TEXT.browseFailed)}: ${c.error}`);
    if (c.status === "loading") showMsg(msg, true, t(TEXT.browseLoading));
    const rows = c.themes.map((th) => {
      const sw = el("span", { class: "catalogue-swatches", "aria-hidden": "true" },
        (th.swatches || []).map((hex) => el("span", { style: `background:${/^#[0-9a-fA-F]{6}$/.test(hex) ? hex : "transparent"}` })));
      const meta = [th.author, th.license, th.version].filter(Boolean).join(" · ");
      const action = th.installed
        ? el("span", { class: "status-chip pass" }, el("span", { "aria-hidden": "true", text: "✓" }), " ", t(TEXT.browseInstalled))
        : el("button", { type: "button", "data-theme-id": th.id, "aria-label": `${t(TEXT.browsePreview)}: ${displayName(th)}`, text: t(TEXT.browsePreview),
            onclick: () => previewFromCatalogue(th, msg) });
      return el("li", { class: "catalogue-row", "data-theme-id": th.id }, sw,
        el("span", { class: "catalogue-name" }, el("b", { text: displayName(th) }), el("small", { text: meta })), action);
    });
    return el("div", { class: "card", id: "catalogue-card" },
      el("h2", { text: t(TEXT.browseTitle) }),
      el("p", { class: "field-hint", text: t(TEXT.browseHelp) }),
      el("button", { type: "button", id: "catalogue-load", text: t(TEXT.browseBtn), disabled: c.status === "loading", onclick: loadCatalogue }),
      c.stub ? el("p", { class: "field-hint", text: t(TEXT.browseStub) }) : null,
      c.status === "ready" && !rows.length ? el("p", { class: "field-hint", text: t(TEXT.browseEmpty) }) : null,
      rows.length ? el("ul", { id: "catalogue-list", class: "catalogue-list" }, rows) : null,
      msg);
  }

  // A shared theme must be self-contained, so a `basedOn` theme is flattened first (the engine's own resolve).
  async function shareCurrent() {
    const th = state.catalog[state.appearance.theme_id];
    if (!th) return;
    try {
      const flat = Object.assign(T.resolve(th, state.catalog), { id: th.id, name: th.name });
      ["names", "version", "author", "description", "license"].forEach((k) => {
        if (th[k] != null) flat[k] = th[k];
      });
      delete flat.builtin;
      delete flat.basedOn;
      const url = await invoke("theme_share_url", { jsonText: JSON.stringify(flat, null, 2) });
      await invoke("open_url", { url });
      flash(t(TEXT.shareOpened), true);
    } catch (err) {
      flash(String(err), false);
    }
  }

  function renderScreen() {
    const root = document.getElementById("appearance-root");
    if (!root) return;
    root.textContent = "";
    const current = state.catalog[state.appearance.theme_id];
    const isUser = current && !current.builtin;
    const cards = [card(null, true), ...state.list.map((th) => card(th, false))];

    const loadMsg = el("div", { id: "load-msg", class: "appearance-msg", "aria-live": "polite" });
    const fileInput = el("input", { type: "file", id: "theme-file", accept: ".json,application/json", "aria-label": t(TEXT.loadFile) });
    fileInput.addEventListener("change", () => {
      const f = fileInput.files && fileInput.files[0];
      if (!f) return;
      const r = new FileReader();
      r.onload = () => tryTheme(String(r.result), loadMsg);
      r.readAsText(f);
    });

    const askMsg = el("div", { id: "ai-msg", class: "appearance-msg", "aria-live": "polite" });
    const request = el("input", { type: "text", id: "ai-request", placeholder: t(TEXT.askPlaceholder), value: t(TEXT.askPlaceholder) });
    const variation = el("input", { type: "checkbox", id: "ai-variation" });
    const answer = el("textarea", { id: "ai-answer", class: "mono", rows: "5", placeholder: '{"schema": "npdev-theme.v1", ...}' });
    const copyBtn = el("button", { type: "button", id: "ai-copy", text: t(TEXT.askCopy) });
    copyBtn.addEventListener("click", async () => {
      const base = variation.checked && current ? Object.assign({}, current, { builtin: undefined }) : null;
      const ok = await copyText(T.buildThemePrompt(request.value, base));
      showMsg(askMsg, ok, ok ? t(TEXT.askCopied) : "…");
    });
    const previewBtn = el("button", { type: "button", id: "ai-preview", class: "primary", text: t(TEXT.askPreview) });
    previewBtn.addEventListener("click", () => tryTheme(answer.value, askMsg));

    const a11yGrid = el("div", { class: "card appearance-a11y" },
      select("text_scale", TEXT.textSize, [[0.9, { en: "90 %" }], [1, { en: "100 %" }], [1.15, { en: "115 %" }], [1.3, { en: "130 %" }], [1.5, { en: "150 %" }]]),
      select("motion", TEXT.motion, [["system", TEXT.motionSystem], ["reduce", TEXT.motionReduce]]),
      select("focus", TEXT.focus, [["auto", TEXT.focusAuto], ["always", TEXT.focusAlways]]),
      select("spacing", TEXT.spacing, [["normal", TEXT.spacingNormal], ["wide", TEXT.spacingWide]]),
      select("links", TEXT.links, [["auto", TEXT.linksAuto], ["underline", TEXT.linksUnderline]]),
      select("cvd", TEXT.cvd, [["off", TEXT.cvdOff], ["safe", TEXT.cvdSafe]])
    );

    const modeSel = el("select", { id: "appearance-mode" },
      [["system", TEXT.modeSystem], ["light", TEXT.modeLight], ["dark", TEXT.modeDark]].map(([v, l]) =>
        el("option", { value: v, selected: state.appearance.mode === v, text: t(l) })));
    modeSel.addEventListener("change", async () => {
      state.appearance.mode = modeSel.value;
      applyNow();
      await persist();
    });

    root.append(
      el("h1", { text: t(TEXT.title) }),
      el("p", { class: "field-hint", text: t(TEXT.lead) }),
      el("div", { id: "appearance-flash", class: "appearance-msg", "aria-live": "polite" }),
      el("h2", { text: t(TEXT.theme) }),
      el("div", { class: "theme-grid" }, cards),
      el("label", { class: "appearance-field", for: "appearance-mode" }, el("span", { text: t(TEXT.mode) }), modeSel),
      el("div", { class: "appearance-actions" },
        el("button", { type: "button", id: "theme-export", text: t(TEXT.exportBtn), onclick: exportCurrent }),
        isUser ? el("button", { type: "button", id: "theme-share", text: t(TEXT.shareBtn), onclick: shareCurrent }) : null,
        isUser ? el("button", { type: "button", id: "theme-remove", class: "destructive", text: t(TEXT.removeBtn), onclick: removeCurrent }) : null),
      catalogueCard(),
      el("div", { class: "appearance-two" },
        el("div", { class: "card" }, el("h2", { text: t(TEXT.loadTitle) }), el("p", { class: "field-hint", text: t(TEXT.loadHelp) }), fileInput, loadMsg),
        el("div", { class: "card" }, el("h2", { text: t(TEXT.askTitle) }),
          el("label", { class: "appearance-field", for: "ai-request" }, el("span", { text: t(TEXT.askDescribe) }), request),
          el("label", { class: "appearance-check", for: "ai-variation" }, variation, " ", t(TEXT.askVariation)),
          el("p", null, copyBtn),
          el("label", { class: "appearance-field", for: "ai-answer" }, el("span", { text: t(TEXT.askPaste) }), answer),
          previewBtn, askMsg)),
      el("h2", { text: t(TEXT.a11y) }),
      a11yGrid,
      el("p", { class: "field-hint", text: t(TEXT.highContrastHint) }),
      el("p", { class: "appearance-sample" }, `${t(TEXT.sample)}: `,
        chip("pass", "✓", t(TEXT.pass)), " ", chip("fail", "✕", t(TEXT.fail)), " ", chip("warn", "!", t(TEXT.warn)), " ",
        chip("info", "i", t(TEXT.info)), " · ", el("a", { href: "#", onclick: (e) => e.preventDefault(), text: t(TEXT.link) }))
    );
  }

  window.NPDevAppearance = { state, init, renderScreen, choose, tryTheme, applyNow, stripFence };
  window.__npdevRefreshAppearance = async () => {
    await loadList();
    renderScreen();
  };
  document.addEventListener("npdev:lang-changed", () => renderScreen());

  init().then(renderScreen);
})();
