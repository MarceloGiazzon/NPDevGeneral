// The Prompter (Phase F).
//
// App picker -> compose -> send, with the provider editor that this tab owns because the Manager has
// no Settings screen. Three rules from the Rust side are visible in the code below:
//
//   COMPOSE and SEND are two steps. Send transmits the exact text sitting in the prompt box, so the
//   user has always read the payload before it leaves the machine. It never re-composes.
//
//   The key is never in this window. `prompter_profiles` reports `hasCredential`, a boolean; the
//   value lives in the OS credential store and only Rust reads it. Blank in the key box means
//   "leave the stored key alone", never "erase it" -- the box cannot show what is stored, so it
//   cannot send it back either.
//
//   No `fetch()` anywhere. App data (including an app's own app-tree.json) is reached from Rust, so
//   a page in this window cannot be talked into making a request the Rust side would not.

const { invoke: pInvoke } = window.__TAURI__.core;
const { listen: pListen } = window.__TAURI__.event;

const prompterState = {
  apps: [],
  appDir: null,
  context: null,
  profiles: [],
  validatedCandidate: null,
  loopRunning: false,
  // The session the prompt in the box belongs to (from relay_prompt), and what the pasted-answer
  // check last said to copy back to the AI.
  relayCode: null,
  relayRound: null,
  relayNextPrompt: null,
};

function pEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function pStatus(message, bad) {
  const el = document.getElementById("prompter-status");
  el.textContent = message;
  el.style.color = bad ? "var(--fail)" : "";
}

// ---------------------------------------------------------------------------------------------
// App picker + context
// ---------------------------------------------------------------------------------------------

async function refreshPrompterApps(preferDir) {
  const picker = document.getElementById("prompter-app");
  // Fed by the Monitor's last scan, the same cross-module global the Scrap Manager reads.
  const apps = (window.__npdevMonitorApps && window.__npdevMonitorApps()) || [];
  prompterState.apps = apps.filter((a) => a.isAppRoot);
  if (!prompterState.apps.length) {
    picker.innerHTML = `<option value="">(no apps found — add inspect paths in The Monitor)</option>`;
    document.getElementById("prompter-context").textContent =
      "No generated app found yet. You can still type an app name below and compose a prompt.";
    return;
  }
  picker.innerHTML = prompterState.apps
    .map((a) => `<option value="${pEsc(a.appDir)}">${pEsc(a.name)} — ${pEsc(a.health)}</option>`)
    .join("");
  const target = preferDir || prompterState.appDir || prompterState.apps[0].appDir;
  if (prompterState.apps.some((a) => a.appDir === target)) picker.value = target;
  prompterState.appDir = picker.value;
  await loadPrompterContext();
}

async function loadPrompterContext() {
  const note = document.getElementById("prompter-context");
  const includeBox = document.getElementById("prompter-include-context");
  if (!prompterState.appDir) return;
  note.textContent = "reading the app's model…";
  try {
    prompterState.context = await pInvoke("prompter_app_context", { appDir: prompterState.appDir });
  } catch (e) {
    prompterState.context = null;
    note.textContent = `could not read the app: ${e}`;
    includeBox.checked = false;
    includeBox.disabled = true;
    return;
  }
  const nameBox = document.getElementById("prompter-appname");
  if (prompterState.context.appName) nameBox.value = prompterState.context.appName;
  const hasContext = !!prompterState.context.context;
  includeBox.checked = hasContext;
  includeBox.disabled = !hasContext;
  note.textContent = hasContext
    ? `model context from ${prompterState.context.source}`
    : "no model context available (start the app for a fuller prompt) — composing still works";
}

// ---------------------------------------------------------------------------------------------
// Compose
//
// The prompt is `npdev ai relay prompt`'s, not this file's. The CLI owns the wording, the authoring
// knowledge and the session code the answer has to carry back, so this window, a terminal and the
// browser-AI route all hand an AI the SAME measured text. Nothing below writes a word of it.
// ---------------------------------------------------------------------------------------------

/// Composes the prompt into the prompt box. Resolves to the text, or to "" when it could not be
/// composed (the reason is already on screen). It never rejects: its callers are click handlers, and
/// studio.js starts it without waiting.
async function buildPrompterPrompt() {
  const box = document.getElementById("prompter-prompt");
  const ask = document.getElementById("prompter-ask").value.trim();
  if (!ask) {
    pStatus("describe what to build or change first", true);
    return "";
  }
  // A change needs the app's model read from disk. Unticking "Include the current model" keeps the
  // model on this machine, so that prompt is for a new app instead.
  const changing =
    !!prompterState.appDir &&
    !!prompterState.context &&
    prompterState.context.source === "model.json" &&
    document.getElementById("prompter-include-context").checked;
  pStatus("composing…");
  try {
    const result = await pInvoke("relay_prompt", {
      intent: changing ? "change" : "create",
      ask,
      appDir: changing ? prompterState.appDir : null,
      language: NPDevI18n.lang,
    });
    if (!result || result.ok === false || typeof result.prompt !== "string" || !result.prompt) {
      throw (result && result.error) || "no prompt came back";
    }
    box.textContent = result.prompt;
    // The answer carries this code back; it is what tells an answer to THIS prompt from any other.
    prompterState.relayCode = result.code || null;
    prompterState.relayRound = result.round || 1;
    return result.prompt;
  } catch (e) {
    box.textContent = "";
    prompterState.relayCode = null;
    prompterState.relayRound = null;
    pStatus(`could not compose the prompt: ${e}`, true);
    return "";
  }
}

// ---------------------------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------------------------

function selectedProfile() {
  const id = document.getElementById("prompter-profile").value;
  return prompterState.profiles.find((p) => p.id === id) || null;
}

function onProfileChanged() {
  const profile = selectedProfile();
  const sendBtn = document.getElementById("prompter-send");
  const note = document.getElementById("prompter-provider-note");
  const modelBox = document.getElementById("prompter-model");
  const list = document.getElementById("prompter-model-suggestions");

  if (!profile) {
    sendBtn.disabled = true;
    modelBox.value = "";
    list.innerHTML = "";
    note.textContent =
      "No provider configured. Press ⚙ PROVIDERS to add one — until then, compose and copy.";
    return;
  }
  modelBox.value = profile.defaultModel || (profile.models || [])[0] || "";
  list.innerHTML = (profile.models || []).map((m) => `<option value="${pEsc(m)}"></option>`).join("");
  document.getElementById("prompter-effort").value = profile.defaultEffort || "";

  // A `command` profile runs a CLI the user already authenticated separately, and an `ai` one sends
  // through `npdev ai complete`, which holds the key itself (and says so if there is none) -- so
  // neither needs a credential here. An `http` one cannot send without a key, and saying so up front
  // beats a failed send.
  const usable = profile.kind === "command" || profile.kind === "ai" || profile.hasCredential;
  sendBtn.disabled = !usable;
  const target = profile.kind === "ai" ? `ai profile ${profile.aiProfile || "(prompter binding)"}` : profile.kind;
  note.textContent = usable
    ? `${profile.label || profile.id} — ${target}${profile.hasCredential ? ", key stored in this machine's credential store" : ""}`
    : `${profile.label || profile.id} — no key stored. Press ⚙ PROVIDERS and paste one.`;
}

async function refreshPrompterProfiles() {
  // Once per window: legacy `http` profiles move their key into `npdev providers` and become `ai`
  // profiles (main.rs migrate_http_profile). A profile that cannot move keeps working as it was.
  if (!prompterState.migrationTried) {
    prompterState.migrationTried = true;
    try {
      const report = await pInvoke("migrate_prompter_profiles");
      if ((report.migrated || []).length) {
        pStatus(`moved ${report.migrated.length} provider(s) to NPDev AI profiles; their keys are in the AI Providers tab now`);
      }
    } catch (e) {
      // Not fatal: the profiles still work the legacy way.
    }
  }
  await refreshAiProfileChoices();
  const result = await pInvoke("prompter_profiles");
  prompterState.profiles = result.profiles || [];
  const picker = document.getElementById("prompter-profile");
  const previous = picker.value;
  picker.innerHTML = prompterState.profiles.length
    ? prompterState.profiles
        .map((p) => `<option value="${pEsc(p.id)}">${pEsc(p.label || p.id)}</option>`)
        .join("")
    : `<option value="">(none configured)</option>`;
  if (prompterState.profiles.some((p) => p.id === previous)) picker.value = previous;
  onProfileChanged();
  renderProfileList();
}

function renderProfileList() {
  const host = document.getElementById("prompter-profile-list");
  if (!prompterState.profiles.length) {
    host.innerHTML = `<p class="subtitle">No providers yet. Fill the form below and press SAVE PROVIDER.</p>`;
    return;
  }
  host.innerHTML = prompterState.profiles
    .map(
      (p) => `<div class="runrow">
        <span class="chip">${pEsc(p.kind)}</span>
        <strong>${pEsc(p.label || p.id)}</strong>
        <span class="subtitle">${pEsc(p.id)}</span>
        <span class="chip">${p.hasCredential ? "key stored" : "no key"}</span>
        <button class="btn ghost" data-pf-edit="${pEsc(p.id)}">EDIT</button>
        <button class="btn ghost" data-pf-delete="${pEsc(p.id)}">DELETE</button>
      </div>`
    )
    .join("");
  host.querySelectorAll("[data-pf-edit]").forEach((b) =>
    b.addEventListener("click", () => editProfile(b.dataset.pfEdit))
  );
  host.querySelectorAll("[data-pf-delete]").forEach((b) =>
    b.addEventListener("click", () => deleteProfile(b.dataset.pfDelete))
  );
}

function setProfileKindFields() {
  const kind = document.getElementById("pf-kind").value;
  document.getElementById("pf-ai-fields").hidden = kind !== "ai";
  document.getElementById("pf-http-fields").hidden = kind !== "http";
  document.getElementById("pf-command-fields").hidden = kind !== "command";
}

/// The `npdev ai` profiles a Prompter profile can point at. The first choice is the prompter
/// binding itself (`npdev ai bind prompter ...`), so changing that binding retargets the Prompter.
async function refreshAiProfileChoices() {
  const picker = document.getElementById("pf-ai-profile");
  let names = [];
  try {
    const result = await pInvoke("ai_command", { args: ["profiles"] });
    names = (result.profiles || []).filter((p) => !p.problem).map((p) => p.name);
  } catch (e) {
    // No CLI yet (nothing installed): only the binding choice is offered.
  }
  const previous = picker.value;
  picker.innerHTML = [`<option value="">(the prompter binding)</option>`]
    .concat(names.map((n) => `<option value="${pEsc(n)}">${pEsc(n)}</option>`))
    .join("");
  if (names.includes(previous)) picker.value = previous;
}

function clearProfileForm() {
  ["pf-id", "pf-label", "pf-endpoint", "pf-key", "pf-models", "pf-command"].forEach(
    (id) => (document.getElementById(id).value = "")
  );
  document.getElementById("pf-kind").value = "ai";
  document.getElementById("pf-ai-profile").value = "";
  document.getElementById("pf-auth").value = "x-api-key";
  document.getElementById("pf-id").disabled = false;
  document.getElementById("prompter-editor-title").textContent = "Add a provider";
  setProfileKindFields();
  document.getElementById("pf-status").textContent = "";
}

function editProfile(id) {
  const profile = prompterState.profiles.find((p) => p.id === id);
  if (!profile) return;
  document.getElementById("pf-id").value = profile.id;
  // The id is the credential-store account key. Renaming it in place would orphan the stored key
  // under the old name, so editing keeps it fixed -- a rename is a delete plus an add.
  document.getElementById("pf-id").disabled = true;
  document.getElementById("pf-label").value = profile.label || "";
  document.getElementById("pf-kind").value = profile.kind || "ai";
  document.getElementById("pf-ai-profile").value = profile.aiProfile || "";
  document.getElementById("pf-endpoint").value = profile.endpoint || "";
  document.getElementById("pf-auth").value = profile.authStyle || "x-api-key";
  document.getElementById("pf-models").value = (profile.models || []).join(", ");
  document.getElementById("pf-command").value = (profile.command || []).join("\n");
  // Never pre-filled, because it cannot be read back. Blank on save means "keep what is stored".
  document.getElementById("pf-key").value = "";
  document.getElementById("prompter-editor-title").textContent = `Edit ${profile.label || profile.id}`;
  setProfileKindFields();
}

async function deleteProfile(id) {
  const status = document.getElementById("pf-status");
  try {
    await pInvoke("delete_prompter_profile", { id });
    status.textContent = `deleted ${id} (its stored key was removed too)`;
    await refreshPrompterProfiles();
  } catch (e) {
    status.textContent = `could not delete: ${e}`;
  }
}

async function saveProfile() {
  const status = document.getElementById("pf-status");
  const id = document.getElementById("pf-id").value.trim();
  if (!id) {
    status.textContent = "an id is required";
    return;
  }
  const models = document
    .getElementById("pf-models")
    .value.split(",")
    .map((m) => m.trim())
    .filter(Boolean);
  const command = document
    .getElementById("pf-command")
    .value.split("\n")
    .map((c) => c.trim())
    .filter(Boolean);
  const kind = document.getElementById("pf-kind").value;
  const profile = {
    id,
    label: document.getElementById("pf-label").value.trim() || id,
    kind,
    ai_profile: kind === "ai" ? document.getElementById("pf-ai-profile").value || null : null,
    command: kind === "command" ? command : [],
    endpoint: kind === "http" ? document.getElementById("pf-endpoint").value.trim() || null : null,
    auth_style: kind === "http" ? document.getElementById("pf-auth").value : null,
    models,
    default_model: models[0] || null,
    default_effort: null,
  };
  const key = document.getElementById("pf-key").value;
  try {
    // An empty key box is sent as null, not "": Rust reads null as "leave the stored key alone".
    await pInvoke("save_prompter_profile", { profile, apiKey: key ? key : null });
    status.textContent = `saved ${id}`;
    document.getElementById("pf-key").value = "";
    await refreshPrompterProfiles();
  } catch (e) {
    status.textContent = `could not save: ${e}`;
  }
}

// ---------------------------------------------------------------------------------------------
// Send
// ---------------------------------------------------------------------------------------------

async function sendPrompt() {
  const profile = selectedProfile();
  if (!profile) {
    pStatus("no provider selected", true);
    return;
  }
  const box = document.getElementById("prompter-prompt");
  // Compose-before-send, enforced here rather than by convention.
  if (!box.textContent.trim() && !(await buildPrompterPrompt())) return;

  const sendBtn = document.getElementById("prompter-send");
  sendBtn.disabled = true;
  pStatus("sending…");
  try {
    const result = await pInvoke("prompter_generate", {
      profileId: profile.id,
      model: document.getElementById("prompter-model").value.trim(),
      effort: document.getElementById("prompter-effort").value || null,
      prompt: box.textContent,
    });
    document.getElementById("prompter-answer").textContent =
      result.text || "(the provider returned no text)";
    document.getElementById("prompter-answer-pane").hidden = false;
    // A new answer invalidates any earlier validation/apply state -- both panes reset rather than
    // showing a stale report or letting Apply write a candidate that was never checked against this
    // answer.
    prompterState.validatedCandidate = null;
    document.getElementById("prompter-validate-pane").hidden = true;
    document.getElementById("prompter-apply-pane").hidden = true;
    pStatus(`answered by ${profile.label || profile.id}`);
  } catch (e) {
    pStatus(`send failed: ${e}`, true);
  } finally {
    sendBtn.disabled = false;
  }
}

async function copyText(text, message) {
  try {
    await navigator.clipboard.writeText(text);
    pStatus(message);
  } catch (e) {
    pStatus("could not copy — select the text and copy manually", true);
  }
}

// ---------------------------------------------------------------------------------------------
// Automatic (AI loop) -- Session 2, NPDEV_MEGA_ROADMAP.md. Everything past "Send" here is done FOR
// you: validate, apply, generate+build, start, wait for health, and re-prompt the AI with the
// specific classified failure on its own, bounded by the iteration budget. `ai-loop-event` is one
// shared event stream (see `ai_loop.rs`'s `Sink`) -- the same events land in
// `<app>/logs/ai-loop/run-<ts>.jsonl`, so nothing shown here is UI-only.
// ---------------------------------------------------------------------------------------------

function loopLog(text) {
  const box = document.getElementById("prompter-loop-log");
  box.textContent += (box.textContent ? "\n" : "") + text;
  box.scrollTop = box.scrollHeight;
}

function loopStatus(text) {
  document.getElementById("prompter-loop-status").textContent = text;
}

function setLoopRunning(running) {
  prompterState.loopRunning = running;
  document.getElementById("prompter-loop-run").disabled = running;
  document.getElementById("prompter-loop-stop").disabled = !running;
}

/// One line per `ai-loop-event` kind, kept short -- the full detail (prompts, reports, log tails)
/// is in the transcript file `loop-start` names, not repeated here.
function renderLoopEvent(payload) {
  const kind = payload.kind;
  if (kind === "loop-start") {
    loopLog(`— starting (max ${payload.maxIterations} iteration(s)) — transcript: ${payload.transcript}`);
  } else if (kind === "iteration-start") {
    loopLog(`\n[iteration ${payload.iteration}]`);
  } else if (kind === "prompt-composed") {
    loopLog(`  → sending prompt (${payload.prompt.length} chars)`);
  } else if (kind === "ai-call-done") {
    loopLog(`  ← response received (${payload.response.length} chars)`);
  } else if (kind === "validate-result") {
    const r = payload.report || {};
    loopLog(`  validate: ${r.status} — ${r.summary?.errors ?? 0} error(s), ${r.summary?.warnings ?? 0} warning(s)`);
  } else if (kind === "apply-done") {
    loopLog(`  applied to model.json`);
  } else if (kind === "generate-done") {
    loopLog(`  generated`);
  } else if (kind === "ops-line") {
    if (payload.raw && payload.raw.text) loopLog(`  build: ${payload.raw.text}`);
  } else if (kind === "build-done") {
    loopLog(`  build finished`);
  } else if (kind === "start-done") {
    loopLog(`  started, waiting for health…`);
  } else if (kind === "health-result") {
    loopLog(`  health: ${payload.health}`);
  } else if (kind === "iteration-failed") {
    loopLog(`  ✗ ${payload.class}${payload.health ? ` (health: ${payload.health})` : ""}`);
  } else if (kind === "reprompt") {
    loopLog(`  retrying with the failure appended to a fresh prompt…`);
  } else if (kind === "loop-done") {
    setLoopRunning(false);
    if (payload.outcome === "booted") {
      loopStatus(`booted and healthy after ${payload.iteration} iteration(s)`);
      loopLog(`\n— BOOTED AND HEALTHY (iteration ${payload.iteration}) —`);
    } else if (payload.outcome === "exhausted") {
      loopStatus(`exhausted budget — last failure: ${payload.lastFailure}`);
      loopLog(`\n— EXHAUSTED after ${payload.iteration} iteration(s), last failure: ${payload.lastFailure} —`);
    } else if (payload.outcome === "cancelled") {
      loopStatus("stopped");
      loopLog(`\n— stopped —`);
    } else {
      loopStatus(`aborted: ${payload.reason || "(no reason given)"}`);
      loopLog(`\n— ABORTED: ${payload.reason || "(no reason given)"} —`);
    }
    if (window.__npdevRefreshMonitor) window.__npdevRefreshMonitor();
  }
}

pListen("ai-loop-event", (event) => renderLoopEvent(event.payload || {}));

// ---- S17c: Reflection Loop Console ---------------------------------------------
// The live run streams above; this replays the PERSISTED transcripts of past runs
// (<app>/logs/ai-loop/run-*.jsonl) as per-iteration timelines, failures highlighted. Thinner
// pipe, same standing rule as the Impact tab: what each event means was decided by ai_loop.rs --
// this window only renders the transcript, never re-derives an outcome.

function reflectStatus(text, bad) {
  const el = document.getElementById("reflect-status");
  if (!el) return;
  el.textContent = text || "";
  el.style.color = bad ? "var(--err)" : "var(--muted)";
}

// Event kinds that mark a real phase boundary the timeline shows; ops-line/build noise is trimmed.
const REFLECT_PHASE_KINDS = new Set([
  "prompt-composed", "ai-call-done", "validate-result", "apply-done", "generate-done",
  "start-done", "health-result", "iteration-failed", "reprompt", "loop-done",
]);

function outcomeClass(outcome) {
  if (outcome === "booted") return "pbadge verified";
  if (outcome === "exhausted" || outcome === "aborted") return "pbadge unknown";
  return "pbadge local";
}

async function refreshLoopHistory() {
  const appDir = prompterState.appDir;
  const box = document.getElementById("reflect-runs");
  if (!appDir) {
    box.innerHTML = `<p class="subtitle">Select an app first, then press Loop History.</p>`;
    return;
  }
  reflectStatus("reading transcripts…");
  try {
    const result = await pInvoke("ai_loop_runs", { appDir });
    const runs = result.runs || [];
    document.getElementById("reflect-timeline").hidden = true;
    if (runs.length === 0) {
      box.innerHTML = `<p class="subtitle">No loop transcripts found for this app yet — run a loop above, or point the Prompter at an app that has.</p>`;
      reflectStatus("");
      return;
    }
    box.innerHTML = runs.map((r) => `
      <div class="prow" data-run="${pEsc(r.runId)}" title="${pEsc(r.summary || "")}">
        <div class="prow-main">
          <span class="pname">${pEsc(r.file)}</span>
          <span class="pversion">${r.iterations} iteration(s) · ${r.eventCount} event(s)</span>
          <span class="${outcomeClass(r.outcome)}">${pEsc(r.outcome)}</span>
        </div>
      </div>`).join("");
    box.querySelectorAll("[data-run]").forEach((row) =>
      row.addEventListener("click", () => replayRun(row.dataset.run)));
    reflectStatus(`${runs.length} run(s)`);
  } catch (error) {
    reflectStatus(`could not list runs: ${error}`, true);
    box.innerHTML = `<p class="status-line err">${pEsc(error)}</p>`;
  }
}

async function replayRun(runId) {
  const appDir = prompterState.appDir;
  const timeline = document.getElementById("reflect-timeline");
  reflectStatus(`replaying ${runId}…`);
  try {
    const result = await pInvoke("ai_loop_replay", { appDir, runId });
    const events = result.events || [];
    const iterations = new Map();
    for (const e of events) {
      if (e.kind === "iteration-start") iterations.set(e.iteration, []);
    }
    for (const e of events) {
      if (e.kind === "iteration-start") continue;
      if (!REFLECT_PHASE_KINDS.has(e.kind)) continue;
      if (iterations.has(e.iteration)) iterations.get(e.iteration).push(e);
    }
    let html = `<h4>Run ${pEsc(runId)}</h4>`;
    if (iterations.size === 0) {
      html += `<p class="subtitle">Transcript has no iteration events to render.</p>`;
    } else {
      for (const [iter, evts] of iterations) {
        const failed = evts.find((e) => e.kind === "iteration-failed");
        const booted = evts.find((e) => e.kind === "loop-done" && e.outcome === "booted");
        html += `
          <div class="reflect-iteration${failed ? " failed" : ""}${booted ? " booted" : ""}">
            <div class="prow-main"><span class="pname">iteration ${iter}</span></div>
            ${evts.map((e) => {
              let note = e.kind;
              if (e.kind === "validate-result") note = `validate: ${e.report?.status} (${e.report?.summary?.errors ?? 0} err, ${e.report?.summary?.warnings ?? 0} warn)`;
              else if (e.kind === "iteration-failed") note = `✗ ${e.class}${e.health ? ` (health: ${e.health})` : ""}`;
              else if (e.kind === "ai-call-done") note = `response (${e.response?.length ?? 0} chars)`;
              else if (e.kind === "prompt-composed") note = `prompt (${e.prompt?.length ?? 0} chars)`;
              else if (e.kind === "health-result") note = `health: ${e.health}`;
              return `<div class="reflect-event ${e.kind === "iteration-failed" ? "err" : ""}">${pEsc(note)}</div>`;
            }).join("")}
          </div>`;
      }
    }
    timeline.innerHTML = html;
    timeline.hidden = false;
    reflectStatus("");
  } catch (error) {
    reflectStatus(`could not replay ${runId}: ${error}`, true);
  }
}

async function runLoop() {
  if (!prompterState.appDir) {
    loopStatus("no app selected");
    return;
  }
  const profile = selectedProfile();
  if (!profile) {
    loopStatus("no provider selected");
    return;
  }
  const box = document.getElementById("prompter-prompt");
  const ask = document.getElementById("prompter-ask").value.trim();
  if (!ask) {
    loopStatus("describe what to build or change first");
    return;
  }
  if (!box.textContent.trim() && !(await buildPrompterPrompt())) {
    loopStatus("the prompt could not be composed");
    return;
  }

  document.getElementById("prompter-loop-log").textContent = "";
  setLoopRunning(true);
  loopStatus("running…");
  try {
    await pInvoke("run_ai_loop", {
      appDir: prompterState.appDir,
      appName: document.getElementById("prompter-appname").value.trim() || "this app",
      profileId: profile.id,
      model: document.getElementById("prompter-model").value.trim(),
      effort: document.getElementById("prompter-effort").value || null,
      initialPrompt: box.textContent,
      plainAsk: ask,
      maxIterations: parseInt(document.getElementById("prompter-loop-max").value, 10) || 5,
    });
  } catch (e) {
    setLoopRunning(false);
    loopStatus(`could not start: ${e}`);
  }
}

async function stopLoop() {
  try {
    await pInvoke("stop_ai_loop", { appDir: prompterState.appDir });
    loopStatus("stopping…");
  } catch (e) {
    loopStatus(`could not stop: ${e}`);
  }
}

// ---------------------------------------------------------------------------------------------
// Validate + Apply
//
// The prompt asks for a COMPLETE model.json (see buildPrompterPrompt above), so the answer
// text is a candidate file, not something to merge by hand. Validate checks it against the real
// DSL validator before anything touches disk; Apply only becomes available once that check has
// passed, and writes exactly the candidate that was validated -- never a re-parse of the answer
// box at apply time, so what gets checked is what gets written.
// ---------------------------------------------------------------------------------------------

const MODEL_APPLY_CONFIRMATION = "I_UNDERSTAND_THIS_OVERWRITES_MODEL_JSON";

/// Scans forward from `start` (which must index a `{`) and returns the substring covering the
/// first BALANCED object, or null if the braces never close. String literals and their escapes are
/// tracked so a `}` inside a value -- a label, a description, an expression -- does not close the
/// object early.
function balancedObjectAt(text, start) {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < text.length; i += 1) {
    const ch = text[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{") depth += 1;
    else if (ch === "}") {
      depth -= 1;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return null;
}

/// Mirrors the lenient extraction `npdev_cli.py`'s `_extract_json_object` already does
/// server-side for AI routine answers: a provider that wraps its JSON in prose or a fenced code
/// block is the common case, not the exception.
function extractCandidateModel(text) {
  const trimmed = (text || "").trim();
  try {
    return JSON.parse(trimmed);
  } catch (e) {
    // fall through
  }
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fenced) {
    try {
      return JSON.parse(fenced[1].trim());
    } catch (e) {
      // fall through
    }
  }
  const start = trimmed.indexOf("{");
  const end = trimmed.lastIndexOf("}");
  if (start !== -1 && end > start) {
    try {
      return JSON.parse(trimmed.slice(start, end + 1));
    } catch (e) {
      // fall through
    }
  }
  // MON-10: the outermost-brace span above assumes nothing brace-bearing follows the model. The
  // prompt now asks for a "deliberateRemovals" note after the JSON, and a provider that writes that
  // note with a brace in it would push `lastIndexOf("}")` past the model and lose an otherwise
  // perfectly good answer. Fall back to the FIRST balanced object instead of the widest span.
  if (start !== -1) {
    const firstObject = balancedObjectAt(trimmed, start);
    if (firstObject !== null) {
      try {
        return JSON.parse(firstObject);
      } catch (e) {
        // fall through
      }
    }
  }
  return null;
}

function renderValidationReport(report) {
  const statusEl = document.getElementById("prompter-validate-status");
  const color = { passed: "var(--pass)", warning: "var(--warn)", failed: "var(--fail)" }[report.status] || "";
  statusEl.style.color = color;
  statusEl.textContent =
    `${report.status} -- ${report.summary?.errors ?? 0} error(s), ${report.summary?.warnings ?? 0} warning(s)`;

  const host = document.getElementById("prompter-diagnostics");
  const diagnostics = report.diagnostics || [];
  host.innerHTML = diagnostics.length
    ? diagnostics
        .map((d) => {
          const sevColor = { error: "var(--fail)", warning: "var(--warn)" }[d.severity] || "var(--muted)";
          return `<div class="runrow">
            <span class="chip" style="color:${sevColor}">${pEsc(d.severity)}</span>
            <span>${pEsc(d.message)}</span>
            ${d.path ? `<span class="subtitle">${pEsc(d.path)}</span>` : ""}
            ${d.suggestedFix ? `<span class="subtitle">fix: ${pEsc(d.suggestedFix)}</span>` : ""}
          </div>`;
        })
        .join("")
    : "";
}

async function validatePrompterAnswer() {
  const answerText = document.getElementById("prompter-answer").textContent;
  const candidate = extractCandidateModel(answerText);
  document.getElementById("prompter-validate-pane").hidden = false;
  document.getElementById("prompter-apply-pane").hidden = true;
  prompterState.validatedCandidate = null;
  if (!candidate) {
    document.getElementById("prompter-validate-status").style.color = "var(--fail)";
    document.getElementById("prompter-validate-status").textContent =
      "could not find a complete JSON object in the response -- nothing to validate";
    document.getElementById("prompter-diagnostics").innerHTML = "";
    return;
  }
  await validatePrompterCandidate(candidate);
}

/// The validate half, shared by the VALIDATE button (above, which finds the candidate in the response
/// box) and the pasted-answer check (below, where the CLI has already found it): the candidate is
/// checked by the real validator, and APPLY only appears for the exact object that passed.
async function validatePrompterCandidate(candidate) {
  const validateBtn = document.getElementById("prompter-validate");
  validateBtn.disabled = true;
  document.getElementById("prompter-validate-status").style.color = "";
  document.getElementById("prompter-validate-status").textContent = "validating…";
  try {
    const report = await pInvoke("validate_prompter_model", { candidate });
    renderValidationReport(report);
    if (report.status !== "failed") {
      prompterState.validatedCandidate = candidate;
      document.getElementById("prompter-apply-pane").hidden = false;
      document.getElementById("prompter-apply-status").textContent = "—";
    }
  } catch (e) {
    document.getElementById("prompter-validate-status").style.color = "var(--fail)";
    document.getElementById("prompter-validate-status").textContent = `validation failed to run: ${e}`;
    document.getElementById("prompter-diagnostics").innerHTML = "";
  } finally {
    validateBtn.disabled = false;
  }
}

// ---------------------------------------------------------------------------------------------
// Paste the answer back (an AI used in a browser)
//
// The user takes the prompt to any chat AI and pastes its answer into the box here. `npdev ai relay
// ingest` says what that text is -- the answer to THIS prompt, a cut-off one, the prompt itself, a
// "you are out of messages" notice -- and this window shows that verdict as one plain sentence. It
// does not parse an answer itself (extractCandidateModel above belongs to the older Send path).
// What the AI wrote around its files is the AI's voice, not NPDev's: it is shown as plain text and
// nothing else -- never markup, never a link, never an instruction to follow.
// ---------------------------------------------------------------------------------------------

/// The one sentence for an ingest outcome. The wording is relay-messages.json's; the catalogue holds
/// its pt pair. An outcome this window does not know yet gets the general sentence, not silence.
function relayOutcomeSentence(outcome) {
  switch (outcome) {
    case "PROMPT_ECHO":
      return NPDevI18n.tr("This is the text to paste INTO the AI, not its answer. Paste it in the AI, then copy what it answers.");
    case "WRONG_SESSION":
      return NPDevI18n.tr("This answer belongs to another conversation. Copy the answer to the text you copied last.");
    case "STALE_ROUND":
      return NPDevI18n.tr("This looks like the AI's previous answer. Copy the most recent one.");
    case "LIMIT_REACHED":
      return NPDevI18n.tr("Your AI reached its free limit. Take this conversation to another AI, or try again later.");
    case "FILE_LINK_ONLY":
      return NPDevI18n.tr("The AI made a file to download. Click its link to download it; NPDev picks it up by itself. Or copy this message to the AI so it writes the files in the answer.");
    case "QUESTIONS":
      return NPDevI18n.tr("The AI wants to know a few things. Answer it in the conversation, then copy its new answer.");
    case "NO_FILES":
      return NPDevI18n.tr("The AI answered without the app. Copy this message and paste it in the same conversation.");
    case "TRUNCATED":
      return NPDevI18n.tr("The answer was cut off. Copy this message so the AI sends it complete.");
    case "ELIDED":
      return NPDevI18n.tr("The AI left parts out. Copy this message so it sends everything.");
    case "BAD_JSON":
      return NPDevI18n.tr("The AI's answer has writing mistakes NPDev cannot read. Copy this message so it fixes them.");
    case "PARTIAL":
      return NPDevI18n.tr("Part of the answer cannot be used. Copy this message so the AI sends only what is needed.");
    default:
      return NPDevI18n.tr("The answer could not be used. Copy the AI's answer again, or ask it to send the files again.");
  }
}

/// Puts the verdict on screen: the sentence, the AI's own words (plain text), and -- when the CLI
/// hands one back -- the message to copy into the same conversation.
function showRelayVerdict(sentence, prose, nextPrompt) {
  const status = document.getElementById("prompter-validate-status");
  status.style.color = sentence ? "var(--warn)" : "";
  status.textContent = sentence;
  document.getElementById("prompter-relay-prose").textContent = prose || "";
  document.getElementById("prompter-relay-prose-box").hidden = !prose;
  prompterState.relayNextPrompt = nextPrompt || null;
  document.getElementById("prompter-relay-copy").hidden = !nextPrompt;
}

async function checkPastedAnswer() {
  const button = document.getElementById("prompter-paste-check");
  const text = document.getElementById("prompter-paste").value;
  // Whatever an earlier check or validation left on screen belongs to an earlier answer.
  document.getElementById("prompter-validate-pane").hidden = false;
  document.getElementById("prompter-apply-pane").hidden = true;
  document.getElementById("prompter-diagnostics").innerHTML = "";
  prompterState.validatedCandidate = null;
  showRelayVerdict("", "", null);
  const status = document.getElementById("prompter-validate-status");
  if (!text.trim()) {
    showRelayVerdict(NPDevI18n.tr("paste the AI's answer first"), "", null);
    return;
  }
  if (!prompterState.relayCode) {
    showRelayVerdict(NPDevI18n.tr("compose the prompt first, take it to the AI, then paste its answer here"), "", null);
    return;
  }
  button.disabled = true;
  status.textContent = NPDevI18n.tr("checking the answer…");
  try {
    let result;
    try {
      result = await pInvoke("relay_ingest_text", {
        text,
        code: prompterState.relayCode,
        round: prompterState.relayRound,
      });
    } catch (e) {
      showRelayVerdict(NPDevI18n.tr("could not check the answer: {e}", { e }), "", null);
      return;
    }
    if (!result || result.ok === false || !result.outcome) {
      showRelayVerdict(NPDevI18n.tr("could not check the answer: {e}", { e: (result && result.error) || "-" }), "", null);
      return;
    }
    const nextPrompt = result.next && typeof result.next.prompt === "string" ? result.next.prompt : null;
    // PARSED is "the text is fine"; READY is the same text after the CLI also checked the model.
    if (result.outcome !== "PARSED" && result.outcome !== "READY") {
      showRelayVerdict(relayOutcomeSentence(result.outcome), result.prose, nextPrompt);
      return;
    }
    const file = (result.files || []).find((f) => f.path === "model.json" && typeof f.content === "string");
    if (!file) {
      showRelayVerdict(relayOutcomeSentence("NO_FILES"), result.prose, nextPrompt);
      return;
    }
    let candidate = null;
    try {
      candidate = JSON.parse(file.content);
    } catch (e) {
      candidate = null;
    }
    if (!candidate || typeof candidate !== "object" || Array.isArray(candidate)) {
      showRelayVerdict(relayOutcomeSentence("BAD_JSON"), result.prose, nextPrompt);
      return;
    }
    // The model the AI proposed goes where a Send answer goes, and is then checked like any other.
    document.getElementById("prompter-answer").textContent = file.content;
    document.getElementById("prompter-answer-pane").hidden = false;
    showRelayVerdict("", result.prose, null);
    await validatePrompterCandidate(candidate);
  } finally {
    button.disabled = false;
  }
}

async function applyPrompterModel() {
  const applyStatus = document.getElementById("prompter-apply-status");
  if (!prompterState.appDir) {
    applyStatus.textContent = "no app selected";
    return;
  }
  if (!prompterState.validatedCandidate) {
    applyStatus.textContent = "validate the response first";
    return;
  }
  // A click, not a typed token: the backup this command always takes first is what makes a
  // one-click Yes/No proportionate here, unlike the DB-reset button's typed confirmation (that one
  // deletes data with no backup at all). The Rust side still independently requires the exact
  // token below -- this dialog is the only thing that got simpler, not the safety check itself.
  const confirmed = confirm(
    NPDevI18n.tr(
      "This OVERWRITES this app's real model.json (a timestamped backup is kept alongside it).\n\n{appDir}\n\nApply the validated model now?",
      { appDir: prompterState.appDir }
    )
  );
  if (!confirmed) {
    applyStatus.textContent = "not confirmed -- model.json was not touched";
    return;
  }

  const applyBtn = document.getElementById("prompter-apply");
  applyBtn.disabled = true;
  applyStatus.textContent = "applying…";
  try {
    const result = await pInvoke("apply_prompter_model", {
      appDir: prompterState.appDir,
      candidate: prompterState.validatedCandidate,
      confirm: MODEL_APPLY_CONFIRMATION,
    });
    applyStatus.textContent = `applied -- previous model backed up to ${result.backupPath}`;

    if (document.getElementById("prompter-build-after-apply").checked) {
      applyStatus.textContent += " -- regenerating…";
      await pInvoke("generate_app_from_model", { appDir: prompterState.appDir });
      applyStatus.textContent += " -- building…";
      await pInvoke("run_ops_script", { appDir: prompterState.appDir, script: "build-finalapp" });
      // build-finalapp streams `ops-event`, which only the Monitor screen listens for -- jump there
      // so the user watches the real build instead of it running invisibly behind this tab.
      if (window.__npdevShowScreen) window.__npdevShowScreen("monitor");
    }
  } catch (e) {
    applyStatus.textContent = `apply failed: ${e}`;
  } finally {
    applyBtn.disabled = false;
  }
}

// ---------------------------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------------------------

function initPrompter() {
  document.getElementById("prompter-refresh").addEventListener("click", async () => {
    // Rescan first: the app list is the Monitor's, and this tab may be opened before it ever ran.
    if (window.__npdevRefreshMonitor) await window.__npdevRefreshMonitor();
    await refreshPrompterApps();
  });
  document.getElementById("prompter-app").addEventListener("change", async (e) => {
    prompterState.appDir = e.target.value;
    // A validated candidate is scoped to the app it was checked against.
    prompterState.validatedCandidate = null;
    document.getElementById("prompter-apply-pane").hidden = true;
    await loadPrompterContext();
  });
  document.getElementById("prompter-profile").addEventListener("change", onProfileChanged);
  document.getElementById("prompter-generate").addEventListener("click", async () => {
    if (await buildPrompterPrompt()) pStatus("composed — read it, then press Send");
  });
  document.getElementById("prompter-copy").addEventListener("click", async () => {
    const box = document.getElementById("prompter-prompt");
    if (!box.textContent.trim() && !(await buildPrompterPrompt())) return;
    copyText(box.textContent, "prompt copied");
  });
  document.getElementById("prompter-copy-answer").addEventListener("click", () =>
    copyText(document.getElementById("prompter-answer").textContent, "response copied")
  );
  document.getElementById("prompter-send").addEventListener("click", sendPrompt);
  document.getElementById("prompter-loop-run").addEventListener("click", runLoop);
  document.getElementById("prompter-loop-stop").addEventListener("click", stopLoop);
  document.getElementById("prompter-validate").addEventListener("click", validatePrompterAnswer);
  document.getElementById("prompter-paste-check").addEventListener("click", checkPastedAnswer);
  document.getElementById("prompter-relay-copy").addEventListener("click", () => {
    if (prompterState.relayNextPrompt) copyText(prompterState.relayNextPrompt, "message copied");
  });
  document.getElementById("prompter-apply").addEventListener("click", applyPrompterModel);
  // S17c: the Reflection Loop Console -- replay of persisted AI-loop transcripts.
  document.getElementById("reflect-refresh").addEventListener("click", refreshLoopHistory);

  const modal = document.getElementById("prompter-modal");
  document.getElementById("prompter-providers").addEventListener("click", () => {
    clearProfileForm();
    renderProfileList();
    modal.classList.add("show");
  });
  document.getElementById("prompter-modal-close").addEventListener("click", () =>
    modal.classList.remove("show")
  );
  document.getElementById("pf-kind").addEventListener("change", setProfileKindFields);
  document.getElementById("pf-save").addEventListener("click", saveProfile);
  document.getElementById("pf-clear").addEventListener("click", clearProfileForm);

  refreshPrompterProfiles();
}

window.__npdevInitPrompter = initPrompter;
window.__npdevRefreshPrompter = async () => {
  await refreshPrompterProfiles();
  await refreshPrompterApps();
};
