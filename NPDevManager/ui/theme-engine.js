// NPDev theme + accessibility engine (npdev-theme.v1). ONE file, two hosts: the NPDev Manager
// (NPDevManager/ui/theme-engine.js) and every generated app (emitted from
// npdev-templates/npdev-theme.js.mustache). The two copies must stay byte-identical -- pinned by a
// twin-pair rule -- so a theme behaves the same in the Manager and in the user's own app.
//
// Plain script, no modules, no dependencies: both hosts load plain <script> files. Exposes
// window.NPDevTheme (and module.exports under Node, for the unit test).
//
// A theme is DATA. This file is the only place that turns it into CSS custom properties, and it
// refuses anything that is not a hex colour, a safe font list, a small integer or a known enum.
(function (root) {
  "use strict";

  var SCHEMA = "npdev-theme.v1";
  var TOKENS = ["bg", "surface", "surfaceRaised", "border", "borderStrong", "text", "textMuted",
    "accent", "onAccent", "positive", "negative", "warning", "info"];
  var HEX = /^#[0-9a-fA-F]{6}$/;
  var FONT = /^[A-Za-z0-9 ,"'-]{1,200}$/;
  var ID = /^[a-z0-9][a-z0-9-]{1,40}$/;
  var ELEVATIONS = { flat: 1, soft: 1, bevel: 1, glow: 1 };

  // [foreground, background, minimum ratio]. WCAG 2.2: 1.4.3 text 4.5:1, 1.4.11 UI components 3:1.
  var CONTRAST_RULES = [
    ["text", "bg", 4.5], ["text", "surface", 4.5], ["text", "surfaceRaised", 4.5],
    ["textMuted", "bg", 4.5], ["textMuted", "surface", 4.5],
    ["accent", "bg", 4.5], ["accent", "surface", 4.5], ["onAccent", "accent", 4.5],
    ["positive", "surface", 4.5], ["negative", "surface", 4.5],
    ["warning", "surface", 4.5], ["info", "surface", 4.5],
    ["borderStrong", "surface", 3.0], ["borderStrong", "bg", 3.0]
  ];

  // Where each token lands, per host. Manager: --t-* only (tokens.css aliases the legacy names).
  // App: the shell's existing --np-* names, so shell.css and every generated screen need no change.
  var HOSTS = {
    manager: {
      // Only --t-*. tokens.css aliases every legacy name (--bg, --fg, --panel, --bezel, ...) to these,
      // so a scoped legacy redefinition can never shadow the theme.
      bg: ["--t-bg"], surface: ["--t-surface"], surfaceRaised: ["--t-surface-raised"], border: ["--t-border"],
      borderStrong: ["--t-border-strong"], text: ["--t-text"], textMuted: ["--t-text-muted"], accent: ["--t-accent"],
      onAccent: ["--t-on-accent"], positive: ["--t-positive"], negative: ["--t-negative"], warning: ["--t-warning"],
      info: ["--t-info"], fontUi: ["--t-font-ui"], fontMono: ["--t-font-mono"], radius: ["--t-radius"], shadow: ["--t-shadow"]
    },
    app: {
      bg: ["--np-bg"],
      surface: ["--np-surface"],
      surfaceRaised: ["--np-surface-raised"],
      border: ["--np-border"],
      borderStrong: ["--np-border-strong"],
      text: ["--np-text"],
      textMuted: ["--np-text-muted"],
      accent: ["--np-accent"],
      onAccent: ["--np-on-accent"],
      positive: ["--np-positive"],
      negative: ["--np-negative", "--np-danger"],
      warning: ["--np-warning"],
      info: ["--np-info"],
      fontUi: ["--np-font"],
      fontMono: ["--np-font-mono"],
      radius: ["--np-radius"],
      shadow: ["--np-shadow"]
    }
  };

  // ------------------------------------------------------------------------------------- colour

  function channel(c) {
    c = c / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }
  function luminance(hex) {
    var n = parseInt(hex.slice(1), 16);
    return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
  }
  function contrastRatio(a, b) {
    var la = luminance(a), lb = luminance(b);
    var hi = Math.max(la, lb), lo = Math.min(la, lb);
    return (hi + 0.05) / (lo + 0.05);
  }
  function mix(hexA, hexB, weightA) {
    var a = parseInt(hexA.slice(1), 16), b = parseInt(hexB.slice(1), 16), w = weightA;
    function ch(shift) { return Math.round(((a >> shift) & 255) * w + ((b >> shift) & 255) * (1 - w)); }
    var n = (ch(16) << 16) | (ch(8) << 8) | ch(0);
    return "#" + ("000000" + n.toString(16)).slice(-6);
  }

  // ----------------------------------------------------------------------------------- validate

  /** Structural checks + contrast. Never throws. strict=true asks for 7:1 on every text pair (AAA). */
  function validate(theme, opts) {
    opts = opts || {};
    var errors = [], warnings = [], contrast = [];
    if (!theme || typeof theme !== "object") return { ok: false, errors: ["not a JSON object"], warnings: warnings, contrast: contrast };
    if (theme.schema !== SCHEMA) errors.push("schema must be \"" + SCHEMA + "\"");
    if (!ID.test(String(theme.id || ""))) errors.push("id must match " + ID);
    if (!theme.name || String(theme.name).length > 40) errors.push("name is required (max 40 characters)");
    if (theme.colorScheme !== "light" && theme.colorScheme !== "dark") errors.push("colorScheme must be light or dark");
    var allowed = { "$schema": 1, schema: 1, id: 1, name: 1, names: 1, version: 1, author: 1, description: 1, descriptions: 1, license: 1,
      colorScheme: 1, basedOn: 1, colors: 1, darkColors: 1, typography: 1, shape: 1 };
    Object.keys(theme).forEach(function (k) { if (!allowed[k]) errors.push("unknown key: " + k); });
    if (theme.descriptions !== undefined) {
      var ds = theme.descriptions;
      if (!ds || typeof ds !== "object" || Array.isArray(ds)) errors.push("descriptions must be an object of language -> text");
      else Object.keys(ds).forEach(function (l) {
        if (!/^[a-z]{2}(-[A-Z]{2})?$/.test(l)) errors.push("descriptions." + l + " is not a language code");
        else if (typeof ds[l] !== "string" || !ds[l] || ds[l].length > 200) errors.push("descriptions." + l + " must be 1-200 characters");
      });
    }
    ["colors", "darkColors"].forEach(function (setName) {
      var set = theme[setName];
      if (set === undefined) return;
      Object.keys(set).forEach(function (k) {
        if (TOKENS.indexOf(k) < 0) errors.push(setName + "." + k + " is not a token");
        else if (!HEX.test(String(set[k]))) errors.push(setName + "." + k + " must be #rrggbb, got " + JSON.stringify(set[k]));
      });
    });
    if (!theme.basedOn) {
      TOKENS.forEach(function (t) { if (!theme.colors || !theme.colors[t]) errors.push("colors." + t + " is required (or use basedOn)"); });
    }
    if (theme.darkColors && theme.colorScheme !== "light") errors.push("a theme with darkColors must have colorScheme light");
    var typo = theme.typography || {};
    ["fontUi", "fontMono"].forEach(function (k) {
      if (typo[k] !== undefined && !FONT.test(String(typo[k]))) errors.push("typography." + k + " has characters outside [A-Za-z0-9 ,\"'-]");
    });
    var shape = theme.shape || {};
    if (shape.radius !== undefined && !(Number.isInteger(shape.radius) && shape.radius >= 0 && shape.radius <= 24)) errors.push("shape.radius must be an integer 0..24");
    if (shape.elevation !== undefined && !ELEVATIONS[shape.elevation]) errors.push("shape.elevation must be flat, soft, bevel or glow");

    // Contrast only on a complete set (a basedOn theme is checked after resolve()).
    ["colors", "darkColors"].forEach(function (setName) {
      var set = theme[setName];
      if (!set || TOKENS.some(function (t) { return !HEX.test(String(set[t] || "")); })) return;
      CONTRAST_RULES.forEach(function (r) {
        var min = (opts.strict && r[2] === 4.5) ? 7 : r[2];
        var ratio = contrastRatio(set[r[0]], set[r[1]]);
        var row = { set: setName, pair: r[0] + " on " + r[1], ratio: Math.round(ratio * 100) / 100, min: min, ok: ratio >= min };
        contrast.push(row);
        if (!row.ok) errors.push(setName + ": " + row.pair + " is " + row.ratio + ":1, needs " + min + ":1");
      });
    });
    return { ok: errors.length === 0, errors: errors, warnings: warnings, contrast: contrast };
  }

  /** Flattens basedOn (depth <= 3, cycle-safe). catalog: { id: theme }. Returns a new object. */
  function resolve(theme, catalog) {
    var chain = [], seen = {}, cur = theme;
    while (cur) {
      if (seen[cur.id]) throw new Error("basedOn cycle at " + cur.id);
      seen[cur.id] = true;
      chain.unshift(cur);
      if (!cur.basedOn) break;
      if (chain.length > 3) throw new Error("basedOn chain deeper than 3");
      cur = catalog[cur.basedOn];
      if (!cur) throw new Error("basedOn: unknown theme " + chain[0].basedOn);
    }
    var out = { colors: {}, typography: {}, shape: {} };
    chain.forEach(function (t) {
      Object.keys(t).forEach(function (k) {
        if (k === "colors" || k === "typography" || k === "shape") Object.assign(out[k], t[k]);
        else if (k === "darkColors") out.darkColors = Object.assign(out.darkColors || {}, t[k]);
        else out[k] = t[k];
      });
    });
    delete out.basedOn;
    return out;
  }

  // -------------------------------------------------------------------------------------- apply

  function shadowFor(elevation, colors) {
    switch (elevation) {
      case "flat": return "none";
      case "bevel": return "inset -1px -1px 0 " + mix(colors.text, colors.surface, 0.75) + ", inset 1px 1px 0 #ffffff, " +
        "inset -2px -2px 0 " + mix(colors.text, colors.surface, 0.5) + ", inset 2px 2px 0 " + mix("#ffffff", colors.surface, 0.5);
      case "glow": return "0 0 0 1px " + mix(colors.accent, colors.bg, 0.5) + ", 0 0 14px " + mix(colors.accent, colors.bg, 0.35);
      default: return "0 1px 2px rgba(0,0,0,.10), 0 1px 3px rgba(0,0,0,.14)"; // soft
    }
  }

  // Colour-vision-deficiency-safe status colours (Okabe-Ito blue / vermillion-orange families, light
  // to dark). Red/green is the pair most people with CVD cannot tell apart; blue/orange survives all
  // three common types. Of the candidates that still meet 4.5:1 on bg AND surface of THIS theme, it
  // picks the one with the smallest margin -- the most vivid colour that passes -- so the switch can
  // never make a theme fail contrast and does not turn every status colour near-black or near-white.
  var CVD_CANDIDATES = {
    positive: ["#003d63", "#005a8e", "#0072b2", "#2b8fd6", "#56b4e9", "#8ccdf3"],
    negative: ["#6e2c00", "#8f3900", "#b34700", "#d55e00", "#e69f00", "#f5c25c"]
  };
  function cvdSafe(colors, min) {
    min = min || 4.5;
    var out = Object.assign({}, colors);
    Object.keys(CVD_CANDIDATES).forEach(function (token) {
      var best = null, bestMargin = Infinity;
      CVD_CANDIDATES[token].forEach(function (c) {
        var worst = Math.min(contrastRatio(c, colors.surface), contrastRatio(c, colors.bg));
        if (worst >= min && worst < bestMargin) { best = c; bestMargin = worst; }
      });
      if (best) out[token] = best; // no candidate fits: keep the theme's own (already-valid) colour
    });
    return out;
  }

  /**
   * Applies a RESOLVED, VALIDATED theme to `target` (default <html>).
   * opts.cvd === "safe" swaps positive/negative for colour-blind-safe ones (see cvdSafe).
   * mode: "light" | "dark" | "system" -- only meaningful when the theme has darkColors.
   * Returns the colour set actually applied.
   */
  function apply(theme, opts) {
    opts = opts || {};
    var host = HOSTS[opts.host || "manager"];
    var target = opts.target || root.document.documentElement;
    var mode = opts.mode || "system";
    var dark = theme.darkColors && (mode === "dark" ||
      (mode === "system" && root.matchMedia && root.matchMedia("(prefers-color-scheme: dark)").matches));
    var colors = dark ? theme.darkColors : theme.colors;
    var scheme = dark ? "dark" : theme.colorScheme;
    if (opts.cvd === "safe") colors = cvdSafe(colors, theme.id === "high-contrast" ? 7 : 4.5);
    function set(token, value) { (host[token] || []).forEach(function (v) { target.style.setProperty(v, value); }); }
    TOKENS.forEach(function (t) { set(t, colors[t]); });
    var typo = theme.typography || {}, shape = theme.shape || {};
    if (typo.fontUi) set("fontUi", typo.fontUi);
    if (typo.fontMono) set("fontMono", typo.fontMono);
    set("radius", (shape.radius === undefined ? 6 : shape.radius) + "px");
    set("shadow", shadowFor(shape.elevation || "soft", colors));
    target.style.setProperty("color-scheme", scheme);
    target.setAttribute("data-np-theme-name", theme.id);
    target.setAttribute("data-np-elevation", shape.elevation || "soft");
    // The shell's existing light/dark switch keys off data-np-theme; keep it truthful.
    target.setAttribute("data-np-theme", scheme);
    return colors;
  }

  // -------------------------------------------------------------------------------------- a11y

  var A11Y_DEFAULTS = { textScale: 1, motion: "system", focus: "auto", spacing: "normal", links: "auto", cvd: "off" };
  var A11Y_ALLOWED = {
    textScale: [0.9, 1, 1.15, 1.3, 1.5],
    motion: ["system", "reduce"],
    focus: ["auto", "always"],
    spacing: ["normal", "wide"],
    links: ["auto", "underline"],
    cvd: ["off", "safe"]
  };

  /** Applies accessibility preferences as data attributes + one variable; a11y.css does the rest. */
  function applyA11y(prefs, opts) {
    opts = opts || {};
    var target = opts.target || root.document.documentElement;
    var p = Object.assign({}, A11Y_DEFAULTS, prefs || {});
    Object.keys(A11Y_ALLOWED).forEach(function (k) { if (A11Y_ALLOWED[k].indexOf(p[k]) < 0) p[k] = A11Y_DEFAULTS[k]; });
    target.style.setProperty("--np-text-scale", String(p.textScale));
    target.setAttribute("data-np-motion", p.motion);
    target.setAttribute("data-np-focus", p.focus);
    target.setAttribute("data-np-spacing", p.spacing);
    target.setAttribute("data-np-links", p.links);
    target.setAttribute("data-np-cvd", p.cvd);
    return p;
  }

  // ------------------------------------------------------------------------- AI theme prompt

  /** The text behind "Ask an AI for a theme" / "Ask an AI for a variation". Paste-anywhere. */
  function buildThemePrompt(request, baseTheme) {
    var lines = [
      "You are designing a colour theme for NPDev, a free, open-source app generator",
      "(https://github.com/MarceloGiazzon/NPDevGeneral). The theme styles the NPDev Manager and the apps it generates.",
      "",
      "Reply with ONE JSON object and nothing else. Rules:",
      "- \"schema\": \"npdev-theme.v1\"; \"id\": lowercase letters, digits, hyphens; \"name\": max 40 characters; \"version\": \"1.0.0\".",
      "- \"colorScheme\": \"light\" or \"dark\". Optional \"darkColors\" (same tokens) only if colorScheme is light.",
      "- \"colors\" must contain exactly these tokens, each a #rrggbb hex value: " + TOKENS.join(", ") + ".",
      "- Contrast (WCAG 2.2 AA), all mandatory: text and textMuted at least 4.5:1 on bg and surface; text 4.5:1 on surfaceRaised;",
      "  accent 4.5:1 on bg and surface; onAccent 4.5:1 on accent; positive, negative, warning, info 4.5:1 on surface;",
      "  borderStrong 3:1 on bg and surface. Compute the ratios; do not guess.",
      "- Optional \"typography\": {\"fontUi\", \"fontMono\"} using only fonts installed on Windows/macOS/Linux, characters [A-Za-z0-9 ,\"'-] only.",
      "- Optional \"shape\": {\"radius\": 0-24, \"elevation\": \"flat\"|\"soft\"|\"bevel\"|\"glow\"}.",
      "- No CSS, no URLs, no comments, no other keys.",
      "",
      "What I want: " + String(request || "").trim()
    ];
    if (baseTheme) {
      lines.push("", "Start from this theme and change only what my request needs. Keep the same token names:",
        JSON.stringify(baseTheme, null, 2));
    }
    return lines.join("\n");
  }

  var api = {
    SCHEMA: SCHEMA, TOKENS: TOKENS, HOSTS: HOSTS, CONTRAST_RULES: CONTRAST_RULES, A11Y_DEFAULTS: A11Y_DEFAULTS,
    A11Y_ALLOWED: A11Y_ALLOWED, contrastRatio: contrastRatio, validate: validate, resolve: resolve, apply: apply,
    cvdSafe: cvdSafe,
    applyA11y: applyA11y, buildThemePrompt: buildThemePrompt
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.NPDevTheme = api;
})(typeof window !== "undefined" ? window : globalThis);
