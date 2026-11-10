// Use a browser AI -- a chat AI the user already has, with no key (browser-AI relay, F2).
//
// The job is the Prompter's, done the way a person who has never seen an API key can do it: say what you
// want, COPY one text, give it to any chat AI, bring its answer back, see what it would change, apply.
// Five steps, only the current one open (see ui/browserai.css).
//
// What this file does NOT do is decide anything. `npdev ai relay` (the CLI, through the relay_* Tauri
// commands) composes the text, says what an answer is (ready, cut off, left parts out, asked questions, out
// of free messages ...), checks it, writes the preview sentences and applies it with a backup. This file
// holds the step the user is on and draws what the CLI said:
//
//   * it never parses an answer (the older Prompter's extractCandidateModel is deliberately not used);
//   * it never shows a path, a command or JSON on the main path -- the AI's own words, and any technical
//     text, sit under "See details" as plain text, never markup and never a link;
//   * it never reads the clipboard (it only writes the one text the user asked to copy);
//   * it applies only a READY answer, and only with every box the preview asked for ticked.
//
// Text is written to the page in English: the language engine (i18n.js) translates what a screen draws,
// from ui/i18n/pt.json, and re-draws it when the language changes. `NPDevI18n.tr` is used only where a
// string is not drawn on the page (a confirm box) or carries a value.

(function () {
  "use strict";
  const invoke = (name, args) => window.__TAURI__.core.invoke(name, args);
  const $ = (id) => document.getElementById(id);
  const tr = (text, vars) =>
    window.NPDevI18n && window.NPDevI18n.tr ? window.NPDevI18n.tr(text, vars) : text.replace(/\{(\w+)\}/g, (m, k) => (vars && k in vars ? vars[k] : m));

  // The steps, in order. A step is DONE before the current one, TODO after it (hidden).
  const PHASES = ["compose", "copy", "waiting", "preview", "done"];

  const S = {
    phase: "compose",
    intent: "create",
    appDir: null, // the app a change goes to (the picked one, for the Update button)
    code: null, // the conversation this window opened with relay_start
    promptText: "",
    result: null, // the last answer the CLI checked (the READY one drives the preview)
    applied: null, // what relay_apply said
    busy: false,
    expanded: {}, // a finished step the user opened again
    scanned: false,
  };

  // What the CLI says about an answer that is not ready, as one plain sentence and at most one button.
  // `next.messageId` is the CLI's own key and is preferred over `outcome` (it also names the cases a later
  // stage decided: going in circles, Java missing). An unknown one gets the general sentence, never silence.
  const SENTENCES = {
    PROMPT_ECHO: "This is the text to paste INTO the AI, not its answer. Paste it in the AI, then copy what it answers.",
    WRONG_SESSION: "This answer belongs to another conversation. Copy the answer to the text you copied last.",
    STALE_ROUND: "This looks like the AI's previous answer. Copy the most recent one.",
    LIMIT_REACHED: "Your AI reached its free limit. Take this conversation to another AI, or try again later.",
    FILE_LINK_ONLY: "The AI made a file to download. Click its link to download it; NPDev picks it up by itself. Or copy this message to the AI so it writes the files in the answer.",
    QUESTIONS: "The AI wants to know a few things. Answer it in the conversation, then copy its new answer.",
    NO_FILES: "The AI answered without the app. Copy this message and paste it in the same conversation.",
    TRUNCATED: "The answer was cut off. Copy this message so the AI sends it complete.",
    ELIDED: "The AI left parts out. Copy this message so it sends everything.",
    BAD_JSON: "The AI's answer has writing mistakes NPDev cannot read. Copy this message so it fixes them.",
    PARTIAL: "Part of the answer cannot be used. Copy this message so the AI sends only what is needed.",
    NEEDS_FIX: "The AI made a mistake. Copy this message and paste it in the same conversation.",
    GOING_IN_CIRCLES: "This AI is going in circles. Start a new conversation or try another AI — the text is ready.",
    JAVA_MISSING: "NPDev cannot check the answer yet because Java is missing on this computer. Install it in Settings, then paste the answer again.",
    GENERAL: "The answer could not be used. Copy the AI's answer again, or ask it to send the files again.",
  };
  const BUTTONS = {
    LIMIT_REACHED: "Take it to another AI",
    GOING_IN_CIRCLES: "Copy for a new conversation",
    FILE_LINK_ONLY: "Copy message",
    NO_FILES: "Copy message",
    TRUNCATED: "Copy message",
    ELIDED: "Copy message",
    BAD_JSON: "Copy message",
    PARTIAL: "Copy message",
    NEEDS_FIX: "Copy message",
  };
  const COPIED = "Copied. Now paste it into your AI and send it.";
  const WENT_WRONG = "Something went wrong. Nothing was changed. Try again.";

  // ------------------------------------------------------------------------------------ the steps

  function setPhase(phase) {
    S.phase = phase;
    S.expanded = {};
    render();
  }

  function render() {
    const at = PHASES.indexOf(S.phase) + 1; // the number of the current step
    for (let n = 1; n <= 5; n++) {
      const state = n < at ? "done" : n === at ? "current" : "todo";
      const step = $(`browserai-step-${n}`);
      step.dataset.state = state;
      step.hidden = state === "todo";
      const open = state === "current" || !!S.expanded[n];
      $(`browserai-body-${n}`).hidden = !open;
      $(`browserai-state-${n}`).textContent = state === "done" ? "Done" : state === "current" ? "Now" : "";
      const toggle = $(`browserai-toggle-${n}`);
      if (toggle) {
        toggle.hidden = state !== "done";
        toggle.setAttribute("aria-expanded", open ? "true" : "false");
        toggle.textContent = open ? "Hide" : "Show";
      }
    }
  }

  function focusStep() {
    const target = { compose: "browserai-ask", copy: "browserai-copy", waiting: "browserai-paste", preview: "browserai-apply", done: "browserai-progress" }[S.phase];
    const el = $(target);
    if (el) el.focus({ preventScroll: false });
  }

  function note(id, text, bad) {
    const el = $(id);
    el.textContent = text || "";
    el.classList.toggle("bad", !!bad);
  }

  function announce(text) {
    note("browserai-announce", text, false);
  }

  function setBusy(on) {
    S.busy = on;
    for (const id of ["browserai-continue", "browserai-check", "browserai-undo", "browserai-rebuild", "browserai-else", "browserai-again"]) {
      const el = $(id);
      if (el) el.disabled = on;
    }
    // Apply is never just "not busy": it also waits for every box the preview asked for.
    updateApply();
  }

  // ------------------------------------------------------------------ app, intent and the mode switch

  async function refreshApps() {
    const picker = $("browserai-app");
    let apps = (window.__npdevMonitorApps && window.__npdevMonitorApps()) || [];
    if (!apps.length && !S.scanned && window.__npdevRefreshMonitor) {
      // The app list is the Monitor's last scan; this screen may be opened before it ever ran.
      S.scanned = true;
      try {
        await window.__npdevRefreshMonitor();
      } catch (e) {
        /* an empty list just offers "a new app" */
      }
      apps = (window.__npdevMonitorApps && window.__npdevMonitorApps()) || [];
    }
    apps = apps.filter((a) => a.isAppRoot);
    const keep = picker.value;
    picker.replaceChildren(new Option("a new app", ""), ...apps.map((a) => new Option(a.name, a.appDir)));
    if (keep && apps.some((a) => a.appDir === keep)) picker.value = keep;
    onAppChanged(false);
  }

  function setIntent(intent) {
    S.intent = intent;
    $(`browserai-intent-${intent}`).checked = true;
    $("browserai-newname-label").hidden = intent !== "create";
  }

  // Picking an app means "change this app"; picking "a new app" means create. The person can still flip
  // the choice with the two buttons above the request.
  function onAppChanged(userPicked) {
    const has = !!$("browserai-app").value;
    $("browserai-intent-change").disabled = !has;
    if (userPicked || (!has && S.intent === "change")) setIntent(has ? "change" : "create");
    else if (has && S.phase === "compose" && !$("browserai-ask").value.trim()) setIntent("change");
  }

  // Both screens carry the typed request and the chosen app across, so switching loses nothing.
  function switchTo(screen, fromAsk, fromApp, toAsk, toApp) {
    const ask = $(fromAsk).value;
    if (ask.trim()) {
      const box = $(toAsk);
      box.value = ask;
      box.dispatchEvent(new Event("input", { bubbles: true }));
    }
    const app = $(fromApp).value;
    const picker = $(toApp);
    if (app && picker && [...picker.options].some((o) => o.value === app)) {
      picker.value = app;
      picker.dispatchEvent(new Event("change", { bubbles: true }));
    }
    if (window.__npdevShowScreen) window.__npdevShowScreen(screen);
  }

  // ------------------------------------------------------------------------------- 1. compose

  // The new app's folder name: spaces become dashes. Letters (any language), digits, dash and underscore only.
  function folderName(text) {
    return text.trim().replace(/\s+/g, "-");
  }
  const NAME_OK = /^[\p{L}\p{N}_-]{1,60}$/u;

  async function onContinue() {
    const ask = $("browserai-ask").value.trim();
    note("browserai-step1-note", "");
    if (!ask) return note("browserai-step1-note", "Describe what you want first.", true);
    let appDir = null;
    let newAppName = null;
    if (S.intent === "change") {
      appDir = $("browserai-app").value;
      if (!appDir) return note("browserai-step1-note", "Choose the app to change first.", true);
    } else {
      newAppName = folderName($("browserai-newname").value);
      if (!newAppName) return note("browserai-step1-note", "Give the new app a name first.", true);
      if (!NAME_OK.test(newAppName)) return note("browserai-step1-note", "Use letters, numbers, spaces and dashes in the name.", true);
    }
    setBusy(true);
    note("browserai-step1-note", "Getting your text ready…");
    try {
      let r;
      try {
        r = await invoke("relay_start", {
          intent: S.intent,
          ask,
          appDir,
          newAppName,
          target: null,
          language: (window.NPDevI18n && window.NPDevI18n.lang) || "en",
          interview: $("browserai-interview").checked,
          errors: null,
        });
      } catch (e) {
        return note("browserai-step1-note", WENT_WRONG, true);
      }
      if (!r || r.ok === false || !r.code || typeof r.prompt !== "string") return note("browserai-step1-note", WENT_WRONG, true);
      S.code = r.code;
      S.appDir = appDir;
      S.promptText = r.prompt;
      S.result = null;
      S.applied = null;
      $("browserai-prompt-text").textContent = r.prompt;
      note("browserai-step1-note", "");
      note("browserai-copy-note", "");
      setPhase("copy");
      focusStep();
    } finally {
      setBusy(false);
    }
  }

  // --------------------------------------------------------------------------------- 2. copy

  async function copyOut(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      return false;
    }
  }

  async function onCopy() {
    if (await copyOut(S.promptText)) {
      note("browserai-copy-note", COPIED);
      announce(COPIED);
      setPhase("waiting");
      focusStep();
    } else {
      note("browserai-copy-note", 'Could not copy. Open "See the text" and copy it by hand.', true);
      $("browserai-show-text").open = true;
    }
  }

  // ------------------------------------------------------------------ 3. wait for / check the answer

  function showOutcome(sentence, button, prose) {
    const line = $("browserai-outcome");
    line.textContent = sentence || "";
    line.hidden = !sentence;
    const b = $("browserai-outcome-button");
    b.hidden = !button;
    b.textContent = button ? button.label : "";
    b.dataset.copy = button ? button.text : "";
    $("browserai-outcome-prose").textContent = prose || "";
    $("browserai-outcome-details").hidden = !prose;
  }

  async function onCheck() {
    const text = $("browserai-paste").value;
    showOutcome("", null, "");
    if (!text.trim()) return showOutcome("Paste the AI's answer first.", null, "");
    setBusy(true);
    showOutcome("Checking the answer…", null, "");
    try {
      let r;
      try {
        r = await invoke("relay_ingest_text", { text, code: S.code, round: null });
      } catch (e) {
        return showOutcome(WENT_WRONG, null, String(e));
      }
      handleAnswer(r);
    } finally {
      setBusy(false);
    }
  }

  // One checked answer: READY goes to the preview; anything else is one sentence and at most one button.
  function handleAnswer(r) {
    if (!r || r.ok === false || !r.outcome) return showOutcome(WENT_WRONG, null, r && r.error ? String(r.error) : "");
    if (r.outcome === "READY") {
      S.result = r;
      renderPreview(r);
      showOutcome("", null, "");
      setPhase("preview");
      return focusStep();
    }
    // A newer answer that is not ready replaces an earlier READY one: its preview must not stay on offer.
    if (S.phase !== "waiting") {
      S.result = null;
      setPhase("waiting");
    }
    const next = r.next || {};
    const key = SENTENCES[next.messageId] ? next.messageId : SENTENCES[r.outcome] ? r.outcome : "GENERAL";
    const label = BUTTONS[key];
    const text = typeof next.prompt === "string" ? next.prompt : "";
    showOutcome(SENTENCES[key], label && text ? { label, text } : null, r.prose);
  }

  async function onOutcomeButton() {
    const text = $("browserai-outcome-button").dataset.copy || "";
    if (!text) return;
    if (await copyOut(text)) {
      announce(COPIED);
      showOutcome(COPIED, null, "");
      $("browserai-paste").value = "";
      $("browserai-paste").focus();
    } else {
      showOutcome('Could not copy. Open "See details" and copy it by hand.', null, text);
      $("browserai-outcome-details").open = true;
    }
  }

  // --------------------------------------------------------------------------- 4. preview, apply

  // From `result.preview` ONLY: its sentences, its warnings, and a checkbox for each warning the CLI says
  // needs the person's explicit yes. Nothing is worked out here.
  function renderPreview(r) {
    const p = r.preview || { sentences: [], warnings: [], dataLoss: true };
    const list = $("browserai-preview");
    list.replaceChildren();
    const li = (cls, mark, text) => {
      const item = document.createElement("li");
      item.className = cls;
      const m = document.createElement("span");
      m.className = "ba-mark";
      m.setAttribute("aria-hidden", "true");
      m.textContent = mark;
      const t = document.createElement("span");
      t.textContent = text;
      item.append(m, t);
      return item;
    };
    for (const s of p.sentences || []) list.append(li("", "•", s));
    const confirms = $("browserai-confirms");
    confirms.replaceChildren();
    let needsYes = 0;
    for (const w of p.warnings || []) {
      if (w.needsConfirmation) {
        needsYes += 1;
        const label = document.createElement("label");
        label.className = "ba-confirm";
        const box = document.createElement("input");
        box.type = "checkbox";
        box.dataset.code = w.code;
        box.addEventListener("change", updateApply);
        const mark = document.createElement("span");
        mark.setAttribute("aria-hidden", "true");
        mark.textContent = "⚠";
        const text = document.createElement("span");
        text.textContent = w.text;
        label.append(box, mark, text);
        confirms.append(label);
      } else {
        list.append(li("warn", "⚠", w.text));
      }
    }
    $("browserai-safe").hidden = !(S.intent === "change" && p.dataLoss === false && !needsYes);
    $("browserai-prose").textContent = r.prose || "";
    $("browserai-details").hidden = !r.prose;
    note("browserai-apply-note", needsYes ? "Tick each box to say you agree." : "");
    updateApply();
  }

  function updateApply() {
    const boxes = [...document.querySelectorAll("#browserai-confirms input")];
    $("browserai-apply").disabled = S.busy || boxes.some((b) => !b.checked);
  }

  // Where a NEW app goes: the folders NPDev already watches for apps ("Where your apps live"), else ask once.
  async function newAppParent() {
    try {
      const paths = await invoke("get_inspect_paths");
      if (Array.isArray(paths) && paths.length && paths[0]) return paths[0];
    } catch (e) {
      /* fall through to the folder picker */
    }
    try {
      return await invoke("pick_db_transfer_folder", { title: tr("Choose the folder to keep your apps in") });
    } catch (e) {
      return null;
    }
  }

  const APPLY_REFUSED = {
    "already-applied": "This answer was already applied.",
    "not-ready": "This answer cannot be applied yet.",
    "no-model": "This answer cannot be applied yet.",
    "confirmation-required": "Tick each box to say you agree.",
    "no-target": "Give the new app a name first.",
    "validator-unavailable": SENTENCES.JAVA_MISSING,
  };

  async function onApply() {
    if (S.busy || !S.code) return;
    const confirmed = [...document.querySelectorAll("#browserai-confirms input:checked")].map((b) => b.dataset.code);
    let parentDir = null;
    if (S.intent === "create") {
      parentDir = await newAppParent();
      if (!parentDir) return note("browserai-apply-note", "Choose where to keep your apps first.", true);
    }
    setBusy(true);
    note("browserai-apply-note", "Applying…");
    try {
      let r;
      try {
        r = await invoke("relay_apply", { code: S.code, round: null, confirmed, parentDir });
      } catch (e) {
        return note("browserai-apply-note", WENT_WRONG, true);
      }
      if (!r || r.ok !== true) {
        const why = r && APPLY_REFUSED[r.error];
        return note("browserai-apply-note", why || "The change could not be applied. Nothing was changed.", true);
      }
      S.applied = r;
      showDone(r);
    } finally {
      setBusy(false);
      updateApply();
    }
  }

  // ------------------------------------------------------------------------------------ 5. done

  function showDone(r) {
    const created = r.created === true;
    note("browserai-progress", created ? "Done. Your new app was created." : "Done. The change is in your app.");
    $("browserai-rebuild").hidden = created || !S.appDir;
    $("browserai-go-apps").hidden = !created;
    $("browserai-undo").hidden = created || !r.backupPath; // a new app is not undone here
    setPhase("done");
    focusStep();
  }

  function onGoApps() {
    if (window.__npdevShowScreen) window.__npdevShowScreen("apps");
  }

  // The same two calls the Prompter makes after it applies a model: regenerate, then build. The build streams
  // its progress to the Monitor, so that is where the person is sent to watch it.
  async function onRebuild() {
    if (!S.appDir) return;
    setBusy(true);
    note("browserai-progress", "Updating the app…");
    try {
      await invoke("generate_app_from_model", { appDir: S.appDir });
      await invoke("run_ops_script", { appDir: S.appDir, script: "build-finalapp" });
      if (window.__npdevShowScreen) window.__npdevShowScreen("monitor");
    } catch (e) {
      note("browserai-progress", WENT_WRONG, true);
    } finally {
      setBusy(false);
    }
  }

  const UNDO_REFUSED = {
    "nothing-to-undo": "There is nothing to undo.",
    "create-undo-unsupported": "A new app cannot be undone here. Delete it from My apps if you do not want it.",
    "app-missing": "The app or its saved copy could not be found, so nothing was undone.",
    "backup-missing": "The app or its saved copy could not be found, so nothing was undone.",
  };

  async function onUndo() {
    if (S.busy || !S.code) return;
    setBusy(true);
    try {
      let r;
      try {
        r = await invoke("relay_undo", { code: S.code, force: false });
        if (r && r.ok === false && r.error === "app-changed-since-apply") {
          // The app was edited after this was applied: only an explicit yes puts the old version back.
          if (!window.confirm(tr("The app was changed after this was applied. Undo anyway and lose those later changes?"))) return;
          r = await invoke("relay_undo", { code: S.code, force: true });
        }
      } catch (e) {
        return note("browserai-progress", WENT_WRONG, true);
      }
      if (r && r.ok === true) {
        $("browserai-undo").hidden = true;
        return note("browserai-progress", "Undone. Your app is as it was before.");
      }
      note("browserai-progress", (r && UNDO_REFUSED[r.error]) || "Nothing was undone.", true);
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------------------------------ start over

  // "Ask for something else": give up on the open conversation (if any) and go back to the first step with
  // the request still typed, so it can be edited.
  async function startOver(abandon) {
    if (S.busy) return;
    if (abandon && S.code) {
      try {
        await invoke("relay_abort", { code: S.code });
      } catch (e) {
        /* the conversation just stays listed as open; it is harmless */
      }
    }
    S.code = null;
    S.promptText = "";
    S.result = null;
    S.applied = null;
    $("browserai-paste").value = "";
    showOutcome("", null, "");
    note("browserai-apply-note", "");
    note("browserai-progress", "");
    announce("");
    setPhase("compose");
    focusStep();
  }

  // ------------------------------------------------------------------------------------- wiring

  function initBrowserAi() {
    $("browserai-mode-api").addEventListener("click", () => switchTo("prompter", "browserai-ask", "browserai-app", "prompter-ask", "prompter-app"));
    $("prompter-mode-browser").addEventListener("click", () => {
      switchTo("browserai", "prompter-ask", "prompter-app", "browserai-ask", "browserai-app");
    });
    $("browserai-app").addEventListener("change", () => onAppChanged(true));
    $("browserai-intent-create").addEventListener("change", () => setIntent("create"));
    $("browserai-intent-change").addEventListener("change", () => setIntent("change"));
    $("browserai-continue").addEventListener("click", onContinue);
    $("browserai-copy").addEventListener("click", onCopy);
    $("browserai-check").addEventListener("click", onCheck);
    // A paste into the box is checked at once: the person already did the work of copying the answer.
    $("browserai-paste").addEventListener("paste", () => setTimeout(onCheck, 0));
    $("browserai-outcome-button").addEventListener("click", onOutcomeButton);
    $("browserai-apply").addEventListener("click", onApply);
    $("browserai-else").addEventListener("click", () => startOver(true));
    $("browserai-again").addEventListener("click", () => startOver(false));
    $("browserai-rebuild").addEventListener("click", onRebuild);
    $("browserai-go-apps").addEventListener("click", onGoApps);
    $("browserai-undo").addEventListener("click", onUndo);
    for (let n = 1; n <= 4; n++) {
      $(`browserai-toggle-${n}`).addEventListener("click", () => {
        S.expanded[n] = !S.expanded[n];
        render();
      });
    }
    document.addEventListener("npdev:lang-changed", render);
    setIntent("create");
    render();
  }

  window.__npdevInitBrowserAi = initBrowserAi;
  window.__npdevRefreshBrowserAi = refreshApps;
})();
