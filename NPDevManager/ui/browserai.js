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
//   * it never reads the clipboard or the Downloads folder (it only writes the one text the user asked to
//     copy; the Rust side's watchers read, and tell the page only what they found);
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
    builtAppDir: null, // the generated app of the applied answer: what is built, started and opened
    launching: false, // building / starting it right now
    launchToken: 0, // a later launch (or starting over) makes an earlier one stop drawing
    openUrl: null,
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
  // The two outcomes whose button copies the text for a NEW conversation (`next.freshChatPrompt`): the AI cannot
  // go on (free limit) or will not (same mistake twice), so repeating the same-chat message to it would not help.
  // Every other button copies `next.prompt`, the short message for the conversation the person already has.
  const FRESH_CHAT = new Set(["LIMIT_REACHED", "GOING_IN_CIRCLES"]);
  // How one round of the conversation reads in the small history list, by the CLI's outcome for that round.
  const ROUND_STATES = {
    READY: ["✓", "ready to apply"],
    TRUNCATED: ["⚠", "cut off"],
    ELIDED: ["⚠", "parts left out"],
    BAD_JSON: ["⚠", "writing mistakes"],
    PARTIAL: ["⚠", "only partly usable"],
    NEEDS_FIX: ["⚠", "mistakes to fix"],
    NO_FILES: ["⚠", "no app in it"],
    FILE_LINK_ONLY: ["⚠", "a file to download"],
    QUESTIONS: ["•", "the AI asked questions"],
    LIMIT_REACHED: ["⚠", "free limit reached"],
  };
  const ROUND_CHECKED = ["•", "answer checked"]; // an outcome this list has no words for yet: still shown, never hidden
  const ROUND_WAITING = ["…", "waiting for the answer"];
  const MAX_ROUNDS_SHOWN = 8;
  const COPIED = "Copied. Now paste it into your AI and send it.";
  const WENT_WRONG = "Something went wrong. Nothing was changed. Try again.";

  // ------------------------------------------------------------------------------------ the steps

  function setPhase(phase) {
    S.phase = phase;
    S.expanded = {};
    render();
    syncWatch();
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
    for (const id of ["browserai-continue", "browserai-check", "browserai-pick-file", "browserai-undo", "browserai-rebuild", "browserai-else", "browserai-again"]) {
      const el = $(id);
      // Undo and Update would fight the build that is running: they wait for it. Asking for something else does not.
      if (el) el.disabled = on || (S.launching && (id === "browserai-undo" || id === "browserai-rebuild"));
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
  // The chosen app is carried AFTER the other screen has re-read its list of apps (app.js does that when a screen
  // opens): an app made a moment ago is not in a list read earlier, and copying the choice into a stale list drops
  // it -- then the way back would bring the other screen's old choice with it.
  async function switchTo(screen, fromAsk, fromApp, toAsk, toApp) {
    const ask = $(fromAsk).value;
    const app = $(fromApp).value;
    // The request crosses at once; re-reading the other screen's apps can take a moment (it reads the app's model).
    if (ask.trim()) {
      const box = $(toAsk);
      box.value = ask;
      box.dispatchEvent(new Event("input", { bubbles: true }));
    }
    if (window.__npdevShowScreen) window.__npdevShowScreen(screen);
    const reread = screen === "prompter" ? window.__npdevRefreshPrompter : window.__npdevRefreshBrowserAi;
    if (reread) {
      try {
        await reread();
      } catch (e) {
        /* a list that could not be re-read is the one already on screen */
      }
    }
    const picker = $(toApp);
    if (app && picker && [...picker.options].some((o) => o.value === app)) {
      picker.value = app;
      picker.dispatchEvent(new Event("change", { bubbles: true }));
    }
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
      drawRounds([]);
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

  // Every way an answer arrives ends here: one call to the CLI (it decides what the answer is), one result.
  // `source` is how it arrived and is passed on as it is -- the CLI logs it and the watchers (F3) add theirs.
  async function runCheck(ask) {
    if (S.busy) return;
    setBusy(true);
    showOutcome("Checking the answer…", null, "");
    try {
      let r;
      try {
        r = await ask();
      } catch (e) {
        return showOutcome(WENT_WRONG, null, String(e));
      }
      handleAnswer(r);
    } finally {
      setBusy(false);
    }
    refreshRounds(); // after the answer is drawn: the history is a companion, it never delays or blocks the sentence
  }

  // ------------------------------------------------------------- the rounds so far (small history list)

  // The CLI keeps the record (`relay_status`: one entry per message sent, with the outcome of its answer); this
  // only draws it, so the person sees the conversation moving: "Round 1 cut off", "Round 2 waiting for the answer".
  // Shown once a round has an answer; a conversation that has just begun has nothing to list.
  async function refreshRounds() {
    const code = S.code;
    if (!code) return;
    let st;
    try {
      st = await invoke("relay_status", { code });
    } catch (e) {
      return; // no history this time; the sentence above is what matters
    }
    if (code !== S.code) return; // the person started over meanwhile
    const rounds = st && st.session && Array.isArray(st.session.rounds) ? st.session.rounds : [];
    drawRounds(rounds);
  }

  function drawRounds(rounds) {
    const list = $("browserai-rounds");
    const wrap = $("browserai-rounds-wrap");
    list.replaceChildren();
    const answered = rounds.some((r) => r && r.outcome);
    wrap.hidden = !answered;
    if (!answered) return;
    for (const r of rounds.slice(-MAX_ROUNDS_SHOWN)) {
      const [mark, words] = !r.outcome ? ROUND_WAITING : ROUND_STATES[r.outcome] || ROUND_CHECKED;
      const item = document.createElement("li");
      const m = document.createElement("span");
      m.className = "ba-mark";
      m.setAttribute("aria-hidden", "true");
      m.textContent = mark;
      const n = document.createElement("span");
      n.className = "ba-round-n";
      n.textContent = `Round ${r.round}`;
      item.append(m, n);
      if (r.promptKind === "fresh-chat") {
        const kind = document.createElement("span");
        kind.className = "ba-round-kind";
        kind.textContent = "new conversation";
        item.append(kind);
      }
      const s = document.createElement("span");
      s.className = "ba-round-state";
      s.textContent = words;
      item.append(s);
      if (!r.outcome) item.setAttribute("aria-current", "step");
      list.append(item);
    }
  }

  function checkText(text, source) {
    return runCheck(() => invoke("relay_ingest_text", { text, code: S.code, round: null, source }));
  }

  function checkFiles(files, source) {
    return runCheck(() => invoke("relay_ingest_file", { files, code: S.code, round: null, source }));
  }

  async function onCheck() {
    const text = $("browserai-paste").value;
    showOutcome("", null, "");
    if (!text.trim()) return showOutcome("Paste the AI's answer first.", null, "");
    return checkText(text, "paste");
  }

  // Ctrl+V anywhere on this step. Only the plain text of the paste event is read (the page never reads the
  // clipboard by itself); the text lands in the box, so what was checked is what the person can see.
  function onPasteAnywhere(e) {
    if (!onStep3() || S.busy) return;
    const target = e.target;
    const editable = target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
    if (editable) return; // the answer box has its own handler; any other box keeps its paste
    const text = e.clipboardData ? e.clipboardData.getData("text/plain") : "";
    if (!text.trim()) return;
    e.preventDefault();
    $("browserai-paste").value = text;
    showOutcome("", null, "");
    checkText(text, "paste");
  }

  // The native dialog. Cancelling it is not an error.
  async function onPickFile() {
    if (S.busy) return;
    let files;
    try {
      files = await invoke("relay_pick_answer_files");
    } catch (e) {
      return showOutcome(WENT_WRONG, null, String(e));
    }
    if (Array.isArray(files) && files.length) {
      showOutcome("", null, "");
      checkFiles(files, "file-picker");
    }
  }

  // Files dropped on the window (Tauri hands over their paths; the CLI reads them, this page never does).
  // Only the open conversation's step 3 takes a drop -- anywhere else it would have nowhere to go.
  function onStep3() {
    return !$("screen-browserai").hidden && S.phase === "waiting" && !!S.code;
  }

  function dragging(on) {
    $("browserai-drop").dataset.over = on && onStep3() ? "true" : "false";
    $("browserai-drop-label").textContent = on && onStep3() ? "Drop the file now" : "Or drop the file the AI made here";
  }

  function onDropped(paths) {
    dragging(false);
    if ($("screen-browserai").hidden || S.busy || !Array.isArray(paths) || !paths.length) return;
    if (!onStep3()) return announce("Drop the file in step 3, after you have asked your AI.");
    showOutcome("", null, "");
    checkFiles(paths, "drop");
  }

  async function wireDrop() {
    const listen = window.__TAURI__ && window.__TAURI__.event && window.__TAURI__.event.listen;
    if (!listen) return;
    try {
      await listen("tauri://drag-enter", () => dragging(true));
      await listen("tauri://drag-leave", () => dragging(false));
      await listen("tauri://drag-drop", (e) => onDropped(e && e.payload && e.payload.paths));
    } catch (e) {
      /* no drop in a window that cannot listen; the button and Ctrl+V still work */
    }
  }

  // ------------------------------------- watching the clipboard and the Downloads folder (the Rust side's job)

  // While step 3 is open NPDev can notice the AI's answer the moment the person copies it, or saves the file the
  // AI offers (src/relay_watch.rs; Settings -> AI turns both off for good). This page never reads the clipboard
  // or the folder: it starts and stops the watcher, draws the notice that it is on -- always, naming the places
  // really being read, with the way to stop it -- and draws what the watcher found, which comes back as the same
  // ingest result a pasted or dropped answer gets. `WATCH.code` is the conversation a watcher was asked for;
  // `WATCH.stopped` is the one the person pressed Stop for, which stays stopped until they say so;
  // `WATCH.sources` are the places the Rust side says it is reading ("clipboard", "downloads").
  const WATCH = { code: null, state: "off", stopped: null, sources: [] };
  const WATCH_ON = "NPDev is watching your clipboard for the AI's answer. Anything else you copy is ignored and forgotten.";
  const WATCH_ON_FILES = "NPDev is watching your Downloads folder for the AI's answer. Any other file is ignored and forgotten.";
  const WATCH_ON_BOTH = "NPDev is watching your clipboard and your Downloads folder for the AI's answer. Anything else you copy or save is ignored and forgotten.";
  const WATCH_STOPPED = "NPDev is not watching for the AI's answer. Paste the answer below.";
  const WATCH_ERROR = "NPDev cannot watch for the AI's answer on this computer. Paste the answer below.";
  const WATCH_GOT = "Got the answer from your clipboard.";
  const WATCH_GOT_FILES = "Got the answer from your Downloads folder.";

  function watchOnText() {
    const files = WATCH.sources.includes("downloads");
    const clip = !WATCH.sources.length || WATCH.sources.includes("clipboard"); // no word on it: the clipboard, as before
    return files && clip ? WATCH_ON_BOTH : files ? WATCH_ON_FILES : WATCH_ON;
  }

  function drawWatch() {
    const box = $("browserai-watch-indicator");
    const stoppedHere = onStep3() && WATCH.stopped === S.code;
    const show = onStep3() && (stoppedHere || WATCH.state === "on" || WATCH.state === "error");
    box.hidden = !show;
    if (!show) return;
    const state = stoppedHere ? "stopped" : WATCH.state;
    box.dataset.state = state;
    $("browserai-watch-mark").textContent = state === "on" ? "●" : state === "stopped" ? "○" : "⚠";
    // English, like everything drawn on the page: the language engine translates it (and re-draws it on a change).
    $("browserai-watch-text").textContent = state === "on" ? watchOnText() : state === "stopped" ? WATCH_STOPPED : WATCH_ERROR;
    $("browserai-watch-stop").hidden = state !== "on";
    $("browserai-watch-start").hidden = state !== "stopped";
  }

  // Idempotent: asks for exactly the watcher this moment calls for -- one for the open conversation while its
  // step 3 is on screen -- and for no watcher at any other moment (another step, another screen, no conversation).
  async function syncWatch() {
    const want = onStep3() && WATCH.stopped !== S.code ? S.code : null;
    if (want === WATCH.code) return drawWatch();
    WATCH.code = want;
    if (!want) {
      WATCH.state = "off";
      WATCH.sources = [];
      drawWatch();
      try {
        await invoke("relay_watch_stop");
      } catch (e) {
        /* nothing to stop, or the window is closing */
      }
      return;
    }
    WATCH.state = "off";
    drawWatch();
    try {
      const r = await invoke("relay_watch_start", { code: want });
      if (WATCH.code !== want) return; // the person moved on while it started: the next sync decides
      WATCH.state = r && r.watching ? "on" : "off"; // off by Settings: no watcher, so no notice either
      WATCH.sources = r && Array.isArray(r.sources) ? r.sources : [];
    } catch (e) {
      if (WATCH.code === want) WATCH.state = "error";
    }
    drawWatch();
  }

  function onWatchState(p) {
    if (!p || p.code !== S.code || p.code !== WATCH.code) return;
    WATCH.state = p.state === "on" || p.state === "error" ? p.state : "off";
    if (Array.isArray(p.sources)) WATCH.sources = p.sources;
    drawWatch();
  }

  // The watcher found an answer and the CLI checked it: draw it exactly as if it had been pasted.
  function onWatchedAnswer(r) {
    if (!r || r.code !== S.code || !onStep3()) return;
    announce(r.source === "downloads-watch" ? WATCH_GOT_FILES : WATCH_GOT);
    handleAnswer(r);
    refreshRounds();
  }

  async function wireWatch() {
    const listen = window.__TAURI__ && window.__TAURI__.event && window.__TAURI__.event.listen;
    if (!listen) return;
    try {
      await listen("relay-watch-state", (e) => onWatchState(e && e.payload));
      await listen("relay-answer", (e) => onWatchedAnswer(e && e.payload));
    } catch (e) {
      /* no watching in a window that cannot listen; pasting still works */
    }
  }

  function onWatchStop() {
    WATCH.stopped = S.code;
    syncWatch();
  }

  function onWatchAgain() {
    WATCH.stopped = null;
    syncWatch();
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
    const own = (v) => (typeof v === "string" ? v : "");
    const text = FRESH_CHAT.has(key) ? own(next.freshChatPrompt) || own(next.prompt) : own(next.prompt);
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
      note("browserai-apply-note", ""); // "Applying…" must not stay behind the folded step
      showDone(r);
      S.builtAppDir = await generatedAppDir(r);
    } finally {
      setBusy(false);
      updateApply();
    }
    // Not awaited: a build takes minutes and the person may leave this screen or ask for something else meanwhile.
    if (S.builtAppDir) launch();
  }

  // The folder the built app lives in. A change already knows it (the app that was picked); a new app was made as a
  // model folder, and the one place that knows where its app goes is the Rust side.
  async function generatedAppDir(r) {
    if (r.created !== true) return S.appDir;
    if (window.__npdevRefreshMonitor) window.__npdevRefreshMonitor().catch(() => {}); // the app list now has it
    try {
      return await invoke("relay_generated_app_dir", { modelDir: r.appDir });
    } catch (e) {
      return null;
    }
  }

  // ------------------------------------------------------------------------------------ 5. done

  function showDone(r) {
    const created = r.created === true;
    note("browserai-progress", created ? "Done. Your new app was created." : "Done. The change is in your app.");
    showLaunchButtons({});
    note("browserai-launch", "");
    $("browserai-launch-details").hidden = true;
    $("browserai-go-apps").hidden = !created;
    $("browserai-undo").hidden = created || !r.backupPath; // a new app is not undone here
    setPhase("done");
    focusStep();
  }

  function onGoApps() {
    if (window.__npdevShowScreen) window.__npdevShowScreen("apps");
  }

  // ---------------------------------------------------- after Apply: build it, start it, wait until it answers

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function showLaunchButtons({ open, rebuild, seeRun }) {
    $("browserai-open").hidden = !open;
    $("browserai-rebuild").hidden = !rebuild;
    $("browserai-see-run").hidden = !seeRun;
  }

  async function probe(appDir) {
    try {
      return (await invoke("monitor_probe", { appDir, includeInfo: false })) || {};
    } catch (e) {
      return {};
    }
  }

  // build-finalapp streams `ops-event`; its `done` says how it ended. The listener is in place BEFORE the build
  // starts (a fast failure must not be missed) and only a build's own `done` counts (stopping an app is an ops
  // script too, and the Monitor shows every one of them).
  function runBuild(appDir) {
    return new Promise((resolve, reject) => {
      let unlisten = null;
      let finished = false;
      const finish = (value, failed) => {
        if (finished) return;
        finished = true;
        if (unlisten) unlisten();
        failed ? reject(value) : resolve(value);
      };
      window.__TAURI__.event
        .listen("ops-event", (ev) => {
          const p = (ev && ev.payload) || {};
          if (p.kind === "done" && (!p.script || p.script === "build-finalapp")) finish({ exitCode: p.exitCode, logFile: p.logFile || null }, false);
        })
        .then((fn) => {
          if (finished) return fn();
          unlisten = fn;
          return invoke("run_ops_script", { appDir, script: "build-finalapp", confirm: null });
        })
        .catch((e) => finish(e, true));
    });
  }

  // Generate, build, start and wait until the app answers; the progress is one live sentence. Every wait looks at
  // whether this launch is still the current one (starting over, or a newer launch, ends the older one's drawing).
  async function launch() {
    const appDir = S.builtAppDir;
    if (!appDir || S.launching) return;
    const token = ++S.launchToken;
    const current = () => token === S.launchToken;
    const pollMs = window.__npdevPollMs || 3000; // the render check shortens it
    const say = (text) => current() && note("browserai-launch", text, false);
    const stop = (text, details) => {
      if (!current()) return;
      note("browserai-launch", text, true);
      $("browserai-launch-prose").textContent = details || "";
      $("browserai-launch-details").hidden = !details;
      showLaunchButtons({ rebuild: true, seeRun: true });
    };
    S.launching = true;
    S.openUrl = null;
    showLaunchButtons({});
    $("browserai-launch-details").hidden = true;
    setBusy(S.busy);
    try {
      // A running app holds its own files, its _ops folder among them: it is stopped BEFORE the app is generated again
      // (generating replaces those files -- on Windows that fails while the app's script still has the folder open),
      // then generated, built and started. That holds for a retry of a new app as much as for a change.
      if ((await probe(appDir)).health === "running") {
        say("Stopping the running app so it can be rebuilt…");
        try {
          await invoke("stop_app", { appDir });
        } catch (e) {
          return stop("The app could not be built.", String(e));
        }
        for (let i = 0; i < 15 && current() && (await probe(appDir)).health === "running"; i++) await sleep(pollMs / 3);
      }
      if (!current()) return;
      say("Preparing the app…");
      try {
        await invoke("generate_app_from_model", { appDir });
      } catch (e) {
        return stop("The app could not be built.", String(e));
      }
      if (!current()) return;
      say("Building the app. This can take a few minutes.");
      let built;
      try {
        built = await runBuild(appDir);
      } catch (e) {
        return stop("The app could not be built.", String(e));
      }
      if (!current()) return;
      if (built.exitCode !== 0) return stop("The app could not be built.", `exit code ${built.exitCode}${built.logFile ? `\n${built.logFile}` : ""}`);
      say("Starting the app…");
      try {
        await invoke("start_app", { appDir });
      } catch (e) {
        // Started earlier from this window and still up: nothing to start, only to wait for.
        if (!/already running/i.test(String(e))) return stop("The app could not be started.", String(e));
      }
      let seen = {};
      let clashSince = null;
      const clashMs = window.__npdevConflictMs != null ? window.__npdevConflictMs : 30000; // the render check shortens it
      for (let i = 0; i < 60 && current(); i++) {
        seen = await probe(appDir);
        if (seen.health === "running") break;
        // Another app holds this app's port: this one will not come up while that one runs, so waiting out all the time
        // helps nobody. But only once the clash has lasted: a new app takes a while before it even tries the port, and
        // the other one may be on its way out (stopped a moment ago) -- then it simply starts.
        if (seen.health === "port-conflict") {
          if (clashSince === null) clashSince = Date.now();
          if (Date.now() - clashSince >= clashMs) break;
        } else {
          clashSince = null;
        }
        await sleep(pollMs);
      }
      if (!current()) return;
      if (seen.health === "port-conflict") {
        return stop("Another app is already using this app's address, so it could not start. Stop the other app in Running apps, then try again.", seen.healthDetail || "");
      }
      if (seen.health !== "running") return stop("The app was started but has not answered yet. Look at it in Running apps.", seen.healthDetail || "");
      S.openUrl = seen.baseUrl || null;
      say("Your app is running.");
      showLaunchButtons({ open: !!S.openUrl });
    } finally {
      if (current()) {
        S.launching = false;
        setBusy(S.busy);
      }
    }
  }

  async function onOpenApp() {
    if (!S.openUrl) return;
    try {
      await invoke("open_url", { url: S.openUrl });
    } catch (e) {
      note("browserai-launch", WENT_WRONG, true);
    }
  }

  function onSeeRun() {
    if (window.__npdevShowScreen) window.__npdevShowScreen("monitor");
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
        note("browserai-launch", "The app still shows the change until you update it.");
        showLaunchButtons({ rebuild: !!S.builtAppDir });
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
    drawRounds([]);
    S.promptText = "";
    S.result = null;
    S.applied = null;
    S.launchToken++; // a build still running goes on, but no longer draws here
    S.launching = false;
    S.builtAppDir = null;
    S.openUrl = null;
    note("browserai-launch", "");
    $("browserai-launch-details").hidden = true;
    showLaunchButtons({});
    $("browserai-paste").value = "";
    showOutcome("", null, "");
    note("browserai-apply-note", "");
    note("browserai-progress", "");
    announce("");
    setPhase("compose");
    focusStep();
    // The app just made (or the one just changed) may not be in the list this screen drew when it opened.
    refreshApps();
  }

  // ------------------------------------------------------------------------------------- wiring

  // The switch lives in Settings -> AI (index.html #relay-watch-setting); this file owns the relay, so it wires it.
  async function wireWatchSetting() {
    const box = $("relay-watch-setting-box");
    if (!box) return;
    try {
      box.checked = (await invoke("relay_watch_setting_get")) !== false;
    } catch (e) {
      /* the default is on, and the box starts ticked */
    }
    box.addEventListener("change", async () => {
      const wanted = box.checked;
      try {
        box.checked = (await invoke("relay_watch_setting_set", { enabled: wanted })) !== false;
      } catch (e) {
        box.checked = !wanted; // not saved: the box says what is really true
      }
    });
  }

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
    document.addEventListener("paste", onPasteAnywhere);
    $("browserai-pick-file").addEventListener("click", onPickFile);
    wireDrop();
    wireWatch();
    $("browserai-watch-stop").addEventListener("click", onWatchStop);
    $("browserai-watch-start").addEventListener("click", onWatchAgain);
    // Leaving this screen (or coming back to it) is when the watcher must stop (or start again).
    new MutationObserver(syncWatch).observe($("screen-browserai"), { attributes: true, attributeFilter: ["hidden"] });
    // A page that has just loaded has no notice on it, so a watcher left over from before the load (a dev reload)
    // must not keep running unseen.
    invoke("relay_watch_stop").catch(() => {});
    wireWatchSetting();
    $("browserai-outcome-button").addEventListener("click", onOutcomeButton);
    $("browserai-apply").addEventListener("click", onApply);
    $("browserai-else").addEventListener("click", () => startOver(true));
    $("browserai-again").addEventListener("click", () => startOver(false));
    $("browserai-rebuild").addEventListener("click", launch);
    $("browserai-open").addEventListener("click", onOpenApp);
    $("browserai-see-run").addEventListener("click", onSeeRun);
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
