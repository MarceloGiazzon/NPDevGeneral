// NPDev "Display": which theme this person sees and how it is presented (THM-7, THM-8, ACC-5).
//
// One small script shared by the shell (shell.js) and by the pages that do not load the shell (sign-in, reset,
// invite, change-password, security, workbench). It owns three things and nothing else:
//   1. the CHOICE -- a theme (from the ones the app's author allowed) and accessibility preferences, remembered in
//      this browser's localStorage["npdev.display"] (every access is wrapped: a private window refuses storage and
//      the page must still render, with the app's default);
//   2. APPLYING it -- through the theme engine (/npdev-theme.js, the very file the NPDev Manager runs) which turns a
//      validated theme into the shell's --np-* variables; a theme is data and can contain no CSS;
//   3. the Display MENU -- text size, motion, keyboard focus, text spacing, links, colour-blind safe status colours,
//      and (only when the author allowed several) a theme picker.
//
// The app's own default theme is applied by the shell's CSS before any script runs, so an app that never opts in
// pays nothing: when there is no stored choice and the theme is the default, no script is loaded at all.
//
// Plain ES5, no dependencies. Exposes window.NPDevDisplay.
(function (root) {
  "use strict";

  var KEY = "npdev.display";
  var DEFAULT_ID = "npdev-default";
  var A11Y_DEFAULTS = { textScale: 1, motion: "system", focus: "auto", spacing: "normal", links: "auto", cvd: "off" };

  var cfg = null;          // { themeName, mode, choices:[ids]|["*"], index }
  var index = null;        // /themes/index.json once loaded
  var applying = 0;

  // ---------------------------------------------------------------------------------- storage

  function read() {
    try {
      var v = JSON.parse(root.localStorage.getItem(KEY) || "{}");
      return v && typeof v === "object" ? v : {};
    } catch (e) {
      return {};
    }
  }

  function write(v) {
    try {
      root.localStorage.setItem(KEY, JSON.stringify(v));
    } catch (e) {
      /* the choice is just not remembered */
    }
  }

  function a11yIsDefault(a) {
    if (!a) return true;
    return Object.keys(A11Y_DEFAULTS).every(function (k) { return a[k] === undefined || a[k] === A11Y_DEFAULTS[k]; });
  }

  // ----------------------------------------------------------------------------------- loading

  function loadScript(src, done) {
    var s = root.document.createElement("script");
    s.src = src;
    s.async = true;
    s.onload = function () { done(null); };
    s.onerror = function () { done(new Error("could not load " + src)); };
    root.document.head.appendChild(s);
  }

  function ensureEngine(done) {
    if (root.NPDevTheme) return done(null);
    loadScript("/npdev-theme.js", function (err) { done(err || (root.NPDevTheme ? null : new Error("no engine"))); });
  }

  function ensureA11yCss() {
    if (root.document.querySelector("link[data-npdev-a11y]")) return;
    var l = root.document.createElement("link");
    l.rel = "stylesheet";
    l.href = "/npdev-a11y.css";
    l.setAttribute("data-npdev-a11y", "true");
    root.document.head.appendChild(l);
  }

  function getJson(url, done) {
    var x = new root.XMLHttpRequest();
    x.open("GET", url);
    x.onload = function () {
      if (x.status < 200 || x.status >= 300) return done(new Error(url + " " + x.status));
      try { done(null, JSON.parse(x.responseText)); } catch (e) { done(e); }
    };
    x.onerror = function () { done(new Error("could not reach " + url)); };
    x.send();
  }

  function loadIndex(done) {
    if (index) return done(index);
    getJson("/themes/index.json", function (err, data) {
      index = err ? { themes: [], choices: [], default: DEFAULT_ID, mode: "system" } : data;
      done(index);
    });
  }

  // A theme and every ancestor of its basedOn chain, fetched one by one (depth is capped at 3 by the engine).
  function loadTheme(id, done) {
    var catalog = {};
    function next(wanted, depth) {
      if (depth > 4 || catalog[wanted]) return finish(wanted);
      getJson("/themes/" + encodeURIComponent(wanted) + ".json", function (err, theme) {
        if (err) return done(err);
        catalog[wanted] = theme;
        if (theme.basedOn) return next(theme.basedOn, depth + 1);
        finish(id);
      });
    }
    function finish(top) {
      try {
        var engine = root.NPDevTheme;
        var flat = engine.resolve(catalog[id], catalog);
        var check = engine.validate(flat, { strict: id === "high-contrast" });
        // the engine refuses anything unreadable or unsafe, so a corrupted file leaves the app on its default
        if (!check.ok) return done(new Error(check.errors.join("; ")));
        done(null, flat);
      } catch (e) {
        done(e);
      }
    }
    next(id, 0);
  }

  // --------------------------------------------------------------------------------- applying

  function allowed(id) {
    if (!cfg) return false;
    if (id === cfg.themeName) return true;
    var c = cfg.choices || [];
    if (c.indexOf("*") >= 0) return true;
    return c.indexOf(id) >= 0;
  }

  function effective() {
    var s = read();
    var theme = s.theme && allowed(s.theme) ? s.theme : cfg.themeName;
    return { theme: theme, a11y: s.a11y || {} };
  }

  function applyNow(done) {
    var eff = effective();
    var needEngine = eff.theme !== DEFAULT_ID || !a11yIsDefault(eff.a11y);
    var token = ++applying;
    if (!needEngine) {
      // nothing to do on top of the default look; undo a previous choice if there was one
      if (root.NPDevTheme) {
        root.NPDevTheme.applyA11y(A11Y_DEFAULTS);
        clearInline();
        reapplyMode();
      }
      return done && done();
    }
    if (!a11yIsDefault(eff.a11y)) ensureA11yCss();
    ensureEngine(function (err) {
      if (err || token !== applying) return done && done();
      var finishA11y = function () {
        root.NPDevTheme.applyA11y({
          textScale: eff.a11y.textScale, motion: eff.a11y.motion, focus: eff.a11y.focus,
          spacing: eff.a11y.spacing, links: eff.a11y.links, cvd: eff.a11y.cvd
        });
        done && done();
      };
      if (eff.theme === DEFAULT_ID && eff.a11y.cvd !== "safe") return finishA11y();
      loadTheme(eff.theme === DEFAULT_ID ? DEFAULT_ID : eff.theme, function (e2, flat) {
        if (e2 || token !== applying) return finishA11y();
        root.NPDevTheme.apply(flat, { host: "app", mode: cfg.mode || "system", cvd: eff.a11y.cvd });
        finishA11y();
      });
    });
  }

  function clearInline() {
    // only the variables the engine itself sets, never one the shell or a GuidePage set (e.g. --np-accent from a GuidePage
    // is re-applied by the shell once this is done)
    var style = root.document.documentElement.style;
    var host = root.NPDevTheme && root.NPDevTheme.HOSTS && root.NPDevTheme.HOSTS.app;
    Object.keys(host || {}).forEach(function (token) {
      host[token].forEach(function (name) { style.removeProperty(name); });
    });
    root.document.documentElement.removeAttribute("data-np-theme-name");
    root.document.documentElement.removeAttribute("data-np-elevation");
  }

  // The shell's own light/dark cascade (applyThemeMode) is the one place the mode lives; after a theme is cleared
  // it is simply asked again.
  function reapplyMode() {
    if (root.NPDevShell && root.NPDevShell.reapplyThemeMode) root.NPDevShell.reapplyThemeMode();
    else if (cfg && cfg.mode && cfg.mode !== "system") {
      root.document.documentElement.dataset.npTheme = cfg.mode;
    } else {
      root.document.documentElement.removeAttribute("data-np-theme");
    }
  }

  // ----------------------------------------------------------------------------------- the API

  /**
   * @param opts { themeName, mode, choices:[ids]|["*"], done }  from generated-ui-manifest.json's theme block
   */
  function init(opts) {
    cfg = { themeName: opts.themeName || DEFAULT_ID, mode: opts.mode || "system", choices: opts.choices || [] };
    var s = read();
    var hasChoice = (s.theme && s.theme !== cfg.themeName) || !a11yIsDefault(s.a11y);
    if (cfg.themeName === DEFAULT_ID && !hasChoice) return opts.done && opts.done();
    applyNow(opts.done);
  }

  /** Pages that do not load the shell: read the app's default from /themes/index.json, then apply. */
  function boot(done) {
    loadIndex(function (idx) {
      init({ themeName: idx["default"] || DEFAULT_ID, mode: idx.mode || "system", choices: ["*"], done: done });
    });
  }

  function set(patch, done) {
    var s = read();
    if (patch.theme !== undefined) s.theme = patch.theme;
    if (patch.a11y) {
      s.a11y = s.a11y || {};
      Object.keys(patch.a11y).forEach(function (k) { s.a11y[k] = patch.a11y[k]; });
    }
    write(s);
    applyNow(done);
  }

  function reset(done) {
    write({});
    applyNow(done);
  }

  // ----------------------------------------------------------------------------------- the menu

  var DEFAULT_LABELS = {
    button: "Display", title: "Display", theme: "Theme", textSize: "Text size", motion: "Motion",
    motionSystem: "Follow my device", motionReduce: "Reduce", focus: "Keyboard focus", focusAuto: "Normal",
    focusAlways: "Always visible", spacing: "Text spacing", spacingNormal: "Normal", spacingWide: "Wide",
    links: "Links", linksAuto: "Normal", linksUnderline: "Always underlined", cvd: "Status colours",
    cvdOff: "Normal", cvdSafe: "Colour-blind safe", reset: "Reset", close: "Close"
  };

  var MENU_CSS =
    ".np-display{position:relative;display:inline-block;margin-left:8px}" +
    ".np-display>button{padding:4px 10px;border:1px solid var(--np-border-strong,#7d8da1);border-radius:var(--np-radius,6px);" +
    "background:var(--np-surface,#fff);color:var(--np-text,#1c2333);cursor:pointer;font:inherit}" +
    ".np-display-panel{position:absolute;right:0;top:calc(100% + 6px);z-index:1000;min-width:260px;padding:12px;" +
    "background:var(--np-surface-raised,#fff);color:var(--np-text,#1c2333);border:1px solid var(--np-border-strong,#7d8da1);" +
    "border-radius:var(--np-radius,6px);box-shadow:var(--np-shadow,0 2px 8px rgba(0,0,0,.2));text-align:left}" +
    ".np-display-panel[hidden]{display:none}" +
    ".np-display-panel label{display:block;margin:0 0 10px;font-size:13px}" +
    ".np-display-panel select{display:block;width:100%;margin-top:3px;padding:4px;font:inherit;color:inherit;" +
    "background:var(--np-surface,#fff);border:1px solid var(--np-border-strong,#7d8da1);border-radius:4px}" +
    ".np-display-actions{display:flex;gap:8px;justify-content:flex-end}" +
    ".np-display-actions button{padding:4px 10px;font:inherit;cursor:pointer}";

  function injectMenuCss() {
    if (root.document.querySelector("style[data-npdev-display]")) return;
    var st = root.document.createElement("style");
    st.setAttribute("data-npdev-display", "true");
    st.textContent = MENU_CSS;
    root.document.head.appendChild(st);
  }

  function option(value, text, selected) {
    var o = root.document.createElement("option");
    o.value = String(value);
    o.textContent = text;
    if (selected) o.selected = true;
    return o;
  }

  function field(id, label, options, current, onChange) {
    var wrap = root.document.createElement("label");
    wrap.setAttribute("for", id);
    wrap.appendChild(root.document.createTextNode(label));
    var sel = root.document.createElement("select");
    sel.id = id;
    options.forEach(function (o) { sel.appendChild(option(o[0], o[1], String(o[0]) === String(current))); });
    sel.addEventListener("change", function () { onChange(sel.value); });
    wrap.appendChild(sel);
    return wrap;
  }

  /**
   * Builds the Display button + panel into `container`. opts: { t(key, fallback), showThemes }.
   * The a11y preferences are always offered; the theme picker only when the author allowed more than one theme.
   */
  function mountMenu(container, opts) {
    opts = opts || {};
    var t = function (k) { return opts.t ? opts.t("display." + k, DEFAULT_LABELS[k]) : DEFAULT_LABELS[k]; };
    injectMenuCss();
    var wrap = root.document.createElement("span");
    wrap.className = "np-display";
    var btn = root.document.createElement("button");
    btn.type = "button";
    btn.setAttribute("aria-haspopup", "true");
    btn.setAttribute("aria-expanded", "false");
    btn.setAttribute("aria-controls", "np-display-panel");
    btn.textContent = t("button");
    var panel = root.document.createElement("div");
    panel.id = "np-display-panel";
    panel.className = "np-display-panel";
    panel.setAttribute("role", "group");
    panel.setAttribute("aria-label", t("title"));
    panel.hidden = true;
    wrap.appendChild(btn);
    wrap.appendChild(panel);
    container.appendChild(wrap);

    function render() {
      panel.textContent = "";
      var eff = effective();
      var a = eff.a11y;
      var current = function (k) { return a[k] === undefined ? A11Y_DEFAULTS[k] : a[k]; };
      var change = function (k, numeric) {
        return function (v) { var patch = {}; patch[k] = numeric ? Number(v) : v; set({ a11y: patch }); };
      };
      if (opts.showThemes && index && index.themes) {
        var offered = (cfg.choices.indexOf("*") >= 0 ? index.themes.map(function (x) { return x.id; }) : cfg.choices.slice());
        if (offered.indexOf(cfg.themeName) < 0) offered.unshift(cfg.themeName);
        var lang = (root.document.documentElement.lang || "en").slice(0, 2);
        var rows = offered.map(function (id) {
          var th = index.themes.filter(function (x) { return x.id === id; })[0];
          var label = th ? ((th.names && th.names[lang]) || th.name) : id;
          return [id, label];
        });
        panel.appendChild(field("np-display-theme", t("theme"), rows, eff.theme, function (v) { set({ theme: v }); }));
      }
      if (opts.showA11y === false) { appendActions(); return; }
      panel.appendChild(field("np-display-size", t("textSize"),
        [[0.9, "90 %"], [1, "100 %"], [1.15, "115 %"], [1.3, "130 %"], [1.5, "150 %"]], current("textScale"), change("textScale", true)));
      panel.appendChild(field("np-display-motion", t("motion"),
        [["system", t("motionSystem")], ["reduce", t("motionReduce")]], current("motion"), change("motion")));
      panel.appendChild(field("np-display-focus", t("focus"),
        [["auto", t("focusAuto")], ["always", t("focusAlways")]], current("focus"), change("focus")));
      panel.appendChild(field("np-display-spacing", t("spacing"),
        [["normal", t("spacingNormal")], ["wide", t("spacingWide")]], current("spacing"), change("spacing")));
      panel.appendChild(field("np-display-links", t("links"),
        [["auto", t("linksAuto")], ["underline", t("linksUnderline")]], current("links"), change("links")));
      panel.appendChild(field("np-display-cvd", t("cvd"),
        [["off", t("cvdOff")], ["safe", t("cvdSafe")]], current("cvd"), change("cvd")));
      appendActions();
    }

    function appendActions() {
      var actions = root.document.createElement("div");
      actions.className = "np-display-actions";
      var resetBtn = root.document.createElement("button");
      resetBtn.type = "button";
      resetBtn.textContent = t("reset");
      resetBtn.addEventListener("click", function () { reset(render); });
      var closeBtn = root.document.createElement("button");
      closeBtn.type = "button";
      closeBtn.textContent = t("close");
      closeBtn.addEventListener("click", function () { closePanel(true); });
      actions.appendChild(resetBtn);
      actions.appendChild(closeBtn);
      panel.appendChild(actions);
    }

    function closePanel(focusButton) {
      panel.hidden = true;
      btn.setAttribute("aria-expanded", "false");
      if (focusButton) btn.focus();
    }

    btn.addEventListener("click", function () {
      if (panel.hidden) {
        loadIndex(function () {
          render();
          panel.hidden = false;
          btn.setAttribute("aria-expanded", "true");
          var first = panel.querySelector("select");
          if (first) first.focus();
        });
      } else {
        closePanel(true);
      }
    });
    root.document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && !panel.hidden) closePanel(true);
    });
    return wrap;
  }

  root.NPDevDisplay = { init: init, boot: boot, set: set, reset: reset, mountMenu: mountMenu, effective: function () { return cfg ? effective() : null; } };
})(window);
