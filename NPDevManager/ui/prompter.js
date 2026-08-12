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

const prompterState = {
  apps: [],
  appDir: null,
  context: null,
  profiles: [],
};

function pEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function pStatus(message, bad) {
  const el = document.getElementById("prompter-status");
  el.textContent = message;
  el.style.color = bad ? "var(--bad)" : "";
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
// The prompt text is deliberately the same wording the generated agent-prompter.html builds,
// including the 60k context cap. Two surfaces that describe the same platform in two different ways
// would give two different answers for the same app, and neither would be wrong on its own.
// ---------------------------------------------------------------------------------------------

const PROMPTER_CONTEXT_CHAR_CAP = 60000;

function buildPrompterPrompt() {
  const appName = document.getElementById("prompter-appname").value.trim() || "this app";
  const ask =
    document.getElementById("prompter-ask").value.trim() ||
    "(describe what to build or change above)";
  const includeContext = document.getElementById("prompter-include-context").checked;

  let contextBlock = "";
  if (includeContext && prompterState.context && prompterState.context.context) {
    let json = JSON.stringify(prompterState.context.context, null, 2);
    let truncated = false;
    if (json.length > PROMPTER_CONTEXT_CHAR_CAP) {
      json = json.slice(0, PROMPTER_CONTEXT_CHAR_CAP);
      truncated = true;
    }
    contextBlock =
      `\n=== Current model ("${appName}", from ${prompterState.context.source}) ===\n` +
      json + (truncated ? "\n... (truncated)" : "") + "\n";
  }

  const prompt =
    'You are helping extend an app built on NPDev, a JSON-model-driven app platform. An NPDev ' +
    'app is described by a JSON "model": concepts (entities with typed fields), flows ' +
    '(multi-step server-side operations), pages and a menu. Below is the current model for an ' +
    `app called "${appName}", followed by what I want changed.\n\n` +
    'Please respond with the specific JSON changes needed (new or modified concepts, fields, or ' +
    'flows), explained clearly enough for me to apply by hand.\n' +
    contextBlock +
    `\n=== What I want ===\n${ask}\n`;

  document.getElementById("prompter-prompt").textContent = prompt;
  return prompt;
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

  // A `command` profile runs a CLI the user already authenticated separately, so it needs no stored
  // credential; an `http` one cannot send without a key, and saying so up front beats a failed send.
  const usable = profile.kind === "command" || profile.hasCredential;
  sendBtn.disabled = !usable;
  note.textContent = usable
    ? `${profile.label || profile.id} — ${profile.kind}${profile.hasCredential ? ", key stored in this machine's credential store" : ""}`
    : `${profile.label || profile.id} — no key stored. Press ⚙ PROVIDERS and paste one.`;
}

async function refreshPrompterProfiles() {
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
  const http = document.getElementById("pf-kind").value === "http";
  document.getElementById("pf-http-fields").hidden = !http;
  document.getElementById("pf-command-fields").hidden = http;
}

function clearProfileForm() {
  ["pf-id", "pf-label", "pf-endpoint", "pf-key", "pf-models", "pf-command"].forEach(
    (id) => (document.getElementById(id).value = "")
  );
  document.getElementById("pf-kind").value = "http";
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
  document.getElementById("pf-kind").value = profile.kind || "http";
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
  if (!box.textContent.trim()) buildPrompterPrompt();

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
    await loadPrompterContext();
  });
  document.getElementById("prompter-profile").addEventListener("change", onProfileChanged);
  document.getElementById("prompter-generate").addEventListener("click", () => {
    buildPrompterPrompt();
    pStatus("composed — read it, then press Send");
  });
  document.getElementById("prompter-copy").addEventListener("click", () => {
    const box = document.getElementById("prompter-prompt");
    if (!box.textContent.trim()) buildPrompterPrompt();
    copyText(box.textContent, "prompt copied");
  });
  document.getElementById("prompter-copy-answer").addEventListener("click", () =>
    copyText(document.getElementById("prompter-answer").textContent, "response copied")
  );
  document.getElementById("prompter-send").addEventListener("click", sendPrompt);

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
