// The Manager's language engine (I18N-1; the picker and the navigation came with NAV-1).
//
// WHY THIS EXISTS
// ---------------
// 18 screens, their buttons, status lines and help popups were English whatever the language picker
// said. Rewriting every string in ~600 KB of screen code into key lookups would be a rewrite of the
// whole UI; this engine instead translates what the screens DRAW:
//
//   * `ui/i18n/<lang>.json` is a flat catalogue { "English text": "translation" }. English is the source
//     text and needs no file of its own -- a string with no entry simply stays English.
//   * A key may hold `{name}` placeholders ("Found {n} apps"); one placeholder matches any text, and the
//     translation says where to put it back.
//   * A MutationObserver walks the document: every text node and every title / aria-label / placeholder /
//     alt attribute whose text is in the catalogue is shown translated. The English original is kept per
//     node, so switching language re-draws the open screen in place and switching back restores English.
//     Text a screen writes later (a status line, a table row) is translated the moment it appears.
//   * `NPDevI18n.t({en, pt})` -- the per-string objects navigation.json and the newer screens already
//     use -- keeps working unchanged. `NPDevI18n.tr("text")` translates one string from script.
//
// Parts of the page that must never be translated (a command, a path, a user's own data) carry
// `translate="no"` -- the standard HTML attribute -- and are skipped, subtree and all. <code>, <pre>,
// <kbd>, <samp>, <script>, <style> are skipped by default.
//
// `scripts/quality/check-manager-i18n.py` fails when a string the static pages draw has no entry in a
// shipped catalogue, and `check-manager-ui-render.mjs` asserts the same of what the screens draw at runtime.

(function () {
  const STORAGE_KEY = "npdev.manager.lang";
  const SKIP_TAGS = new Set(["SCRIPT", "STYLE", "CODE", "PRE", "KBD", "SAMP", "TEXTAREA", "NOSCRIPT"]);
  const ATTRS = ["title", "aria-label", "placeholder", "alt"];

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

  const norm = (s) => s.replace(/\s+/g, " ").trim();
  const NUMERIC_NAME = /^(n|m|s|f|o|count|port|pid|code)\d*$/;

  // lang id -> { exact: Map(en -> tr), patterns: [{re, tr, names}] }  (null while loading)
  const catalogues = new Map();
  // node -> { en: original text/value, shown: what we last wrote }   (text nodes and attributes alike)
  const textState = new WeakMap();
  const attrState = new WeakMap(); // element -> { attrName: {en, shown} }
  // text a `{en, pt}` object already produced in the active language: it is translated, so never "missing"
  const produced = new Set();

  const I18n = {
    lang: "en",
    languages: [{ id: "en", label: "English" }],

    /** A `{en, pt}` object, or a plain string -> the current language's text, English as the fallback. */
    t(value) {
      if (value == null) return "";
      if (typeof value === "string") return value;
      const out = value[I18n.lang] ?? value.en ?? Object.values(value)[0] ?? "";
      if (I18n.lang !== "en" && typeof out === "string") produced.add(norm(out));
      return out;
    },

    /** One English string (or a `{placeholder}` template with `params`) -> the current language's text. */
    tr(text, params) {
      let out = I18n.lang === "en" ? text : translate(text, I18n.lang) ?? text;
      if (params) out = out.replace(/\{(\w+)\}/g, (m, k) => (k in params ? String(params[k]) : m));
      // what a script puts on screen after translating it itself is already in the language: not "missing"
      if (I18n.lang !== "en") produced.add(norm(out));
      return out;
    },

    /**
     * A catalogue entry that is not a sentence of the page but a whole block (a help popup's HTML), looked up by
     * id in the current language; null in English or when the language has no such entry.
     */
    block(id) {
      if (I18n.lang === "en") return null;
      const cat = catalogues.get(I18n.lang);
      const hit = cat && cat.exact.get(id);
      return hit == null ? null : hit;
    },

    /** Called by nav.js once navigation.json says which languages exist. */
    init(languages) {
      I18n.languages = languages;
      const ids = languages.map((l) => l.id);
      const stored = storedLang();
      let id = "en";
      if (stored && ids.includes(stored)) id = stored;
      else {
        const browser = (navigator.language || "en").slice(0, 2).toLowerCase();
        if (ids.includes(browser)) id = browser;
      }
      I18n.lang = id;
      document.documentElement.lang = id;
      load(id).then(() => {
        retranslateAll();
        document.dispatchEvent(new CustomEvent("npdev:lang-changed", { detail: { lang: id } }));
      });
    },

    setLang(id) {
      if (!I18n.languages.some((l) => l.id === id)) id = "en";
      I18n.lang = id;
      document.documentElement.lang = id;
      storeLang(id);
      load(id).then(() => {
        retranslateAll();
        document.dispatchEvent(new CustomEvent("npdev:lang-changed", { detail: { lang: id } }));
      });
    },

    /**
     * What the window shows RIGHT NOW in a non-English language that the catalogue has no entry for (for the
     * render checker). A live scan, not a log: text a screen has since replaced is not a leftover.
     */
    missingNow() {
      const out = new Set();
      if (I18n.lang === "en") return [];
      const consider = (en) => {
        const k = norm(en);
        if (looksLikeProse(k) && !isData(k) && !produced.has(k) && translate(en, I18n.lang) == null) out.add(k);
      };
      const visit = (n) => {
        if (n.nodeType === 3) {
          const p = n.parentNode;
          if (p && p.nodeType === 1 && !skipped(p)) {
            const st = textState.get(n);
            consider(st ? st.en : n.data);
          }
          return;
        }
        if (n.nodeType !== 1 || skipped(n)) return;
        for (const a of ATTRS) {
          if (!n.hasAttribute(a)) continue;
          const st = (attrState.get(n) || {})[a];
          consider(st ? st.en : n.getAttribute(a));
        }
        for (let c = n.firstChild; c; c = c.nextSibling) visit(c);
      };
      visit(document.body);
      return Array.from(out);
    },

    /** Translate a subtree now (the observer does this on its own; exposed for the render checker). */
    refresh(root) {
      walk(root || document.body, true);
    },
  };
  window.NPDevI18n = I18n;

  // ---- catalogue ----------------------------------------------------------------------------

  function build(raw) {
    const exact = new Map();
    const patterns = [];
    for (const [en, tr] of Object.entries(raw)) {
      if (en === "_comment") continue;
      if (/\{\w+\}/.test(en)) {
        const names = [];
        const src = norm(en)
          .split(/(\{\w+\})/)
          .map((part) => {
            const m = /^\{(\w+)\}$/.exec(part);
            if (m) {
              names.push(m[1]);
              // a placeholder named n, m, count, port, pid or code (optionally numbered) stands for a number
              return NUMERIC_NAME.test(m[1]) ? "(\\d+(?:[.,]\\d+)?)" : "(.*?)";
            }
            return part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
          })
          .join("");
        patterns.push({ re: new RegExp("^" + src + "$"), tr, names });
      } else {
        exact.set(norm(en), tr);
      }
    }
    return { exact, patterns };
  }

  function load(lang) {
    if (lang === "en" || catalogues.has(lang)) return Promise.resolve();
    return fetch(`i18n/${lang}.json`)
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then((raw) => catalogues.set(lang, build(raw)))
      .catch(() => catalogues.set(lang, build({}))); // a missing catalogue means English, never a broken page
  }

  function translate(text, lang) {
    const cat = catalogues.get(lang);
    if (!cat) return null;
    const key = norm(text);
    if (!key) return null;
    const hit = cat.exact.get(key);
    if (hit != null) return keepSpace(text, hit);
    for (const p of cat.patterns) {
      const m = p.re.exec(key);
      if (m) {
        let out = p.tr;
        p.names.forEach((n, i) => {
          // a captured value is itself translated when it is a known string ("{status}" = "running")
          out = out.split(`{${n}}`).join(translate(m[i + 1], lang) ?? m[i + 1]);
        });
        return keepSpace(text, out);
      }
    }
    // a line built from parts joined with " · " ("3 stale · 1 failed"): translate the parts that are known
    if (key.includes(" · ")) {
      let any = false;
      const parts = key.split(" · ").map((part) => {
        const hit = translate(part, lang);
        if (hit != null) any = true;
        return hit ?? part;
      });
      if (any) return keepSpace(text, parts.join(" · "));
    }
    return null;
  }

  const keepSpace = (orig, out) => (/^\s/.test(orig) ? " " : "") + out + (/\s$/.test(orig) ? " " : "");

  // ---- the DOM ------------------------------------------------------------------------------

  const looksLikeProse = (s) => /[A-Za-z]{3,}/.test(s) && /\s|^[A-Z][a-z]+$/.test(s.trim());

  // a path, a URL or a command line is data the screen shows, not text to translate
  const isData = (s) => /\\|:\/\/|^[~.]?\//.test(s) || /^[\w-]+ · [\w-]+$/.test(s);

  function skipped(el) {
    for (let n = el; n && n.nodeType === 1; n = n.parentNode) {
      if (SKIP_TAGS.has(n.tagName) || n.getAttribute("translate") === "no") return true;
    }
    return false;
  }

  function showText(node) {
    const parent = node.parentNode;
    if (!parent || (parent.nodeType === 1 && skipped(parent))) return;
    let st = textState.get(node);
    // text a screen wrote after we translated is a NEW original
    if (!st || (node.data !== st.shown && node.data !== st.en)) {
      st = { en: node.data, shown: node.data };
      textState.set(node, st);
    }
    if (I18n.lang === "en") {
      if (node.data !== st.en) node.data = st.shown = st.en;
      return;
    }
    const out = translate(st.en, I18n.lang);
    if (out != null) {
      if (node.data !== out) node.data = out;
      st.shown = out;
    } else {
      if (node.data !== st.en) node.data = st.en;
      st.shown = st.en;
    }
  }

  function showAttr(el, name) {
    if (!el.hasAttribute(name) || skipped(el)) return;
    const all = attrState.get(el) || {};
    let st = all[name];
    const cur = el.getAttribute(name);
    if (!st || (cur !== st.shown && cur !== st.en)) {
      st = { en: cur, shown: cur };
      all[name] = st;
      attrState.set(el, all);
    }
    if (I18n.lang === "en") {
      if (cur !== st.en) el.setAttribute(name, (st.shown = st.en));
      return;
    }
    const out = translate(st.en, I18n.lang);
    if (out != null) {
      if (cur !== out) el.setAttribute(name, out);
      st.shown = out;
    } else {
      if (cur !== st.en) el.setAttribute(name, st.en);
      st.shown = st.en;
    }
  }

  function walk(root) {
    if (!root) return;
    if (root.nodeType === 3) return showText(root);
    if (root.nodeType !== 1) return;
    if (skipped(root)) return;
    for (const a of ATTRS) showAttr(root, a);
    for (let c = root.firstChild; c; c = c.nextSibling) walk(c);
  }

  function retranslateAll() {
    walk(document.body);
    const t = document.querySelector("title");
    if (t) walk(t);
  }

  const observer = new MutationObserver((records) => {
    // our own writes echo back as mutations; showText/showAttr see text === shown and do nothing
    for (const r of records) {
      if (r.type === "characterData") showText(r.target);
      else if (r.type === "attributes") showAttr(r.target, r.attributeName);
      else r.addedNodes.forEach((n) => walk(n));
    }
  });

  function start() {
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      attributeFilter: ATTRS,
    });
  }
  if (document.body) start();
  else document.addEventListener("DOMContentLoaded", start);
})();
