// The Studio tab -- Wave 6.1 (NPDEV_FEATURE_PLAN_2026-09-24.md): edit a concept/panel/field's JSON
// node and see the running app change without a rebuild. Reuses the Prompter's existing model
// read/validate/write commands (prompter_app_context, validate_prompter_model, apply_prompter_model)
// -- this file adds no new way to read or write model.json, only the live-push decision
// (studio_apply_live, a thin pipe to `npdev monitor studio-apply`) and a small undo history
// (studio_history_*, reading/writing the app's own data/studio-history/, spared by regeneration).

const { invoke: studioInvoke } = window.__TAURI__.core;
const { listen: studioListen } = window.__TAURI__.event;

const STUDIO_CONFIRM_TOKEN = "I_UNDERSTAND_THIS_OVERWRITES_MODEL_JSON";

let studioAppDir = null;
let studioModel = null;
let studioSelectedPath = null; // dotted path into studioModel, e.g. "concepts.2.fields.0"
let studioBaseUrl = null;

function studioEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function studioStatus(message, kind) {
  const el = document.getElementById("studio-status");
  el.textContent = message || "";
  el.classList.remove("err", "ok");
  if (kind) el.classList.add(kind);
}

function studioCurrentAppDir() {
  const picker = document.getElementById("studio-app-picker");
  return picker && picker.value ? picker.value : null;
}

// Same "populate from list_apps, keep the user's choice across a refresh" shape as dbio.js/packs.js.
async function studioRefreshAppPicker() {
  const picker = document.getElementById("studio-app-picker");
  let apps = [];
  try {
    apps = await studioInvoke("list_apps");
  } catch {
    apps = [];
  }
  if (apps.length === 0) {
    picker.innerHTML = "";
    return;
  }
  const previous = picker.value;
  picker.innerHTML = apps.map((a) => `<option value="${studioEsc(a.directory)}">${studioEsc(a.name)}</option>`).join("");
  if (previous && apps.some((a) => a.directory === previous)) picker.value = previous;
  else picker.value = apps[apps.length - 1].directory;
}

async function studioLoadApp() {
  const appDir = studioCurrentAppDir();
  if (!appDir) return;
  studioAppDir = appDir;
  studioSelectedPath = null;
  studioHideRebuildBanner();
  studioStatus("loading model...", null);

  try {
    const context = await studioInvoke("prompter_app_context", { appDir });
    studioModel = context.context;
  } catch (error) {
    studioModel = null;
    studioStatus(`could not load model: ${error}`, "err");
    studioRenderTree();
    studioRenderEditor();
    return;
  }

  try {
    const probe = await studioInvoke("monitor_probe", { appDir, includeInfo: false });
    studioBaseUrl = (probe && (probe.probeBaseUrl || probe.baseUrl)) || null;
  } catch {
    studioBaseUrl = null;
  }

  studioRenderTree();
  studioRenderEditor();
  studioRefreshPreview();
  studioLoadWidgetCatalog();
  studioRefreshHistory();
  studioStatus("", null);
}

function studioRenderTree() {
  const el = document.getElementById("studio-tree");
  if (!studioModel) {
    el.innerHTML = `<p class="secrets-empty">Pick an app above.</p>`;
    return;
  }
  const groups = [
    ["concepts", "Concepts"],
    ["panels", "Panels"],
    ["flows", "Flows"],
    ["procedures", "Procedures"],
  ];
  let html = "";
  for (const [key, label] of groups) {
    const items = Array.isArray(studioModel[key]) ? studioModel[key] : [];
    if (items.length === 0) continue;
    html += `<div class="studio-tree-group"><div class="studio-tree-group-label">${studioEsc(label)} (${items.length})</div>`;
    items.forEach((item, index) => {
      const name = (item && (item.name || item.id)) || `#${index}`;
      const path = `${key}.${index}`;
      html += studioTreeButton(path, name, false);
      if (key === "concepts" && item && Array.isArray(item.fields)) {
        item.fields.forEach((field, fieldIndex) => {
          const fieldPath = `${path}.fields.${fieldIndex}`;
          html += studioTreeButton(fieldPath, (field && field.name) || `#${fieldIndex}`, true);
        });
      }
    });
    html += `</div>`;
  }
  el.innerHTML = html || `<p class="secrets-empty">No concepts/panels/flows in this model.</p>`;
  el.querySelectorAll("[data-studio-path]").forEach((btn) => {
    btn.addEventListener("click", () => {
      studioSelectedPath = btn.dataset.studioPath;
      studioRenderTree();
      studioRenderEditor();
    });
  });
}

function studioTreeButton(path, name, isChild) {
  const classes = `studio-tree-node${isChild ? " studio-tree-child" : ""}${studioSelectedPath === path ? " selected" : ""}`;
  return `<button type="button" class="${classes}" data-studio-path="${studioEsc(path)}">${studioEsc(name)}</button>`;
}

// Walks a dotted path ("concepts.2.fields.0") against `root`, returning the parent object/array,
// the key/index to assign on it, and the current value there.
function studioResolvePathOn(root, path) {
  const parts = path.split(".");
  let node = root;
  let parent = null;
  let key = null;
  for (const part of parts) {
    parent = node;
    key = /^\d+$/.test(part) ? Number(part) : part;
    node = node == null ? undefined : node[key];
  }
  return { parent, key, value: node };
}

function studioRenderEditor() {
  const textarea = document.getElementById("studio-editor");
  const errorEl = document.getElementById("studio-editor-error");
  const applyBtn = document.getElementById("studio-apply");
  errorEl.textContent = "";
  textarea.classList.remove("studio-invalid");
  if (!studioSelectedPath || !studioModel) {
    textarea.value = "";
    textarea.disabled = true;
    applyBtn.disabled = true;
    return;
  }
  textarea.disabled = false;
  applyBtn.disabled = false;
  const { value } = studioResolvePathOn(studioModel, studioSelectedPath);
  textarea.value = JSON.stringify(value, null, 2);
}

// Validation-on-type (plan's own wording: "a textarea with validation-on-type is enough; no
// Monaco") -- just confirms the text is parseable JSON, never anything model-semantic; that check
// is `validate_prompter_model`'s job, run only on Apply.
function studioValidateTextarea() {
  const textarea = document.getElementById("studio-editor");
  const errorEl = document.getElementById("studio-editor-error");
  try {
    const parsed = JSON.parse(textarea.value);
    textarea.classList.remove("studio-invalid");
    errorEl.textContent = "";
    return parsed;
  } catch (error) {
    textarea.classList.add("studio-invalid");
    errorEl.textContent = `invalid JSON: ${error.message}`;
    return undefined;
  }
}

async function studioLoadWidgetCatalog() {
  const el = document.getElementById("studio-widget-chips");
  el.innerHTML = "";
  if (!studioBaseUrl) return;
  let names = [];
  try {
    const response = await fetch(`${studioBaseUrl.replace(/\/$/, "")}/widget-catalog.json`);
    if (response.ok) {
      const catalog = await response.json();
      const widgets = Array.isArray(catalog.widgets) ? catalog.widgets : (Array.isArray(catalog) ? catalog : []);
      names = widgets.map((w) => (typeof w === "string" ? w : w && w.name)).filter(Boolean);
    }
  } catch {
    names = [];
  }
  el.innerHTML = names.map((name) =>
    `<button type="button" class="studio-widget-chip" data-studio-widget="${studioEsc(name)}">${studioEsc(name)}</button>`
  ).join("");
  el.querySelectorAll("[data-studio-widget]").forEach((btn) => {
    btn.addEventListener("click", () => studioInsertWidget(btn.dataset.studioWidget));
  });
}

// A convenience, not a structural editor: replaces an existing "widget" value in the textarea, or
// inserts one into a `ui` object if present, else adds a minimal `ui` object at the node's top --
// the user reviews the resulting JSON (and Apply re-validates it) before anything is written.
function studioInsertWidget(name) {
  const textarea = document.getElementById("studio-editor");
  const quoted = JSON.stringify(name);
  if (/"widget"\s*:\s*"[^"]*"/.test(textarea.value)) {
    textarea.value = textarea.value.replace(/"widget"\s*:\s*"[^"]*"/, `"widget": ${quoted}`);
  } else {
    const uiMatch = textarea.value.match(/"ui"\s*:\s*\{/);
    if (uiMatch) {
      const insertAt = uiMatch.index + uiMatch[0].length;
      textarea.value = `${textarea.value.slice(0, insertAt)}\n    "widget": ${quoted},${textarea.value.slice(insertAt)}`;
    } else {
      const insertAt = textarea.value.indexOf("{") + 1;
      textarea.value = `${textarea.value.slice(0, insertAt)}\n  "ui": { "widget": ${quoted} },${textarea.value.slice(insertAt)}`;
    }
  }
  studioValidateTextarea();
}

function studioRefreshPreview() {
  const iframe = document.getElementById("studio-iframe");
  iframe.src = studioBaseUrl
    ? `${studioBaseUrl.replace(/\/$/, "")}/npdev-business-ui/index.html?_studio=${Date.now()}`
    : "about:blank";
}

function studioShowRebuildBanner(message) {
  document.getElementById("studio-rebuild-message").textContent =
    message || "This change needs a rebuild to take effect.";
  document.getElementById("studio-rebuild-banner").classList.add("show");
}

function studioHideRebuildBanner() {
  document.getElementById("studio-rebuild-banner").classList.remove("show");
}

async function studioApply() {
  if (!studioAppDir || !studioSelectedPath || !studioModel) return;
  const parsed = studioValidateTextarea();
  if (parsed === undefined) return;

  const candidate = JSON.parse(JSON.stringify(studioModel));
  const target = studioResolvePathOn(candidate, studioSelectedPath);
  target.parent[target.key] = parsed;

  const applyBtn = document.getElementById("studio-apply");
  applyBtn.disabled = true;
  studioHideRebuildBanner();
  try {
    await studioApplyCandidate(candidate);
  } finally {
    applyBtn.disabled = false;
  }
}

// Shared by studioApply (a freshly edited node) and studioUndo (a whole previous model read back
// from history) -- both are "here is a full candidate model; validate it, write it, try to make it
// live" through the exact same path, per the plan's own "Undo re-applies the previous one through
// the same path."
async function studioApplyCandidate(candidate) {
  studioStatus("validating...", null);
  let validation;
  try {
    validation = await studioInvoke("validate_prompter_model", { candidate });
  } catch (error) {
    studioStatus(`validation failed: ${error}`, "err");
    return false;
  }
  if (validation && validation.status === "failed") {
    const messages = (validation.diagnostics || [])
      .filter((d) => d.severity !== "warning")
      .map((d) => d.message);
    studioStatus(`validation failed: ${messages.join("; ") || "see diagnostics"}`, "err");
    return false;
  }

  studioStatus("writing model.json...", null);
  let written;
  try {
    written = await studioInvoke("apply_prompter_model", {
      appDir: studioAppDir, candidate, confirm: STUDIO_CONFIRM_TOKEN,
    });
  } catch (error) {
    studioStatus(`write failed: ${error}`, "err");
    return false;
  }

  await studioRecordHistory(written.modelPath);
  studioModel = candidate;

  studioStatus("applying live...", null);
  try {
    const result = await studioInvoke("studio_apply_live", {
      appDir: studioAppDir, modelPath: written.modelPath, baselinePath: written.backupPath,
    });
    if (result && result.code === "NEEDS_REBUILD") {
      studioShowRebuildBanner(result.message);
      studioStatus("written -- needs a rebuild to take effect", "ok");
    } else if (result && result.ok) {
      studioStatus(result.message || "applied live", "ok");
      studioRefreshPreview();
    } else {
      studioStatus(`apply failed: ${(result && result.message) || "unknown error"}`, "err");
      return false;
    }
  } catch (error) {
    studioStatus(`apply failed: ${error}`, "err");
    return false;
  } finally {
    studioRenderTree();
    studioRefreshHistory();
  }
  return true;
}

async function studioRebuild() {
  if (!studioAppDir) return;
  const btn = document.getElementById("studio-rebuild-btn");
  btn.disabled = true;
  studioStatus("regenerating...", null);
  try {
    await studioInvoke("generate_app_from_model", { appDir: studioAppDir });
  } catch (error) {
    studioStatus(`regenerate failed: ${error}`, "err");
    btn.disabled = false;
    return;
  }

  studioStatus("building (this can take a minute -- watch the Monitor tab for the full log)...", null);
  let unlisten = null;
  const buildDone = new Promise((resolve) => {
    studioListen("ops-event", (event) => {
      const payload = event.payload || {};
      if (payload.kind === "done") {
        resolve(payload.exitCode);
      }
    }).then((fn) => { unlisten = fn; });
  });
  try {
    await studioInvoke("run_ops_script", { appDir: studioAppDir, script: "build-finalapp", confirm: null });
    const exitCode = await buildDone;
    if (exitCode !== 0) {
      studioStatus(`build failed (exit ${exitCode}) -- see the Monitor tab's run log`, "err");
      return;
    }
    studioStatus("restarting...", null);
    await studioInvoke("stop_app", { appDir: studioAppDir });
    await studioInvoke("start_app", { appDir: studioAppDir });
    studioStatus("rebuilt and restarted", "ok");
    studioHideRebuildBanner();
    setTimeout(studioRefreshPreview, 3000);
  } catch (error) {
    studioStatus(`rebuild failed: ${error}`, "err");
  } finally {
    if (unlisten) unlisten();
    btn.disabled = false;
  }
}

// ---- 6.2: Undo, via the app's own data/studio-history/ (spared by regeneration) ----

async function studioRecordHistory(modelPath) {
  try {
    await studioInvoke("studio_history_record", { appDir: studioAppDir, modelPath });
  } catch {
    // Best-effort: history is a convenience, never a gate on applying.
  }
}

async function studioRefreshHistory() {
  const el = document.getElementById("studio-history-list");
  if (!studioAppDir) {
    el.innerHTML = "";
    return;
  }
  let entries = [];
  try {
    entries = await studioInvoke("studio_history_list", { appDir: studioAppDir });
  } catch {
    entries = [];
  }
  if (!entries || entries.length < 2) {
    el.innerHTML = `<li class="secrets-empty">No previous version to undo to yet.</li>`;
    return;
  }
  // entries[0] is the just-applied current state; Undo targets entries[1] (the one before it).
  const previous = entries[1];
  const label = previous.appliedAtMillis ? new Date(previous.appliedAtMillis).toLocaleString() : previous.fileName;
  el.innerHTML = `<li>
    <span>${studioEsc(label)}</span>
    <button type="button" class="btn ghost" id="studio-undo-btn">Undo to this</button>
  </li>`;
  const undoBtn = document.getElementById("studio-undo-btn");
  if (undoBtn) undoBtn.addEventListener("click", () => studioUndo(previous.fileName));
}

async function studioUndo(fileName) {
  if (!studioAppDir) return;
  studioStatus("reading previous version...", null);
  let candidate;
  try {
    candidate = await studioInvoke("studio_history_read", { appDir: studioAppDir, fileName });
  } catch (error) {
    studioStatus(`undo failed: ${error}`, "err");
    return;
  }
  const applied = await studioApplyCandidate(candidate);
  if (applied) {
    studioSelectedPath = null;
    studioRenderEditor();
  }
}

function initStudio() {
  document.getElementById("studio-app-picker").addEventListener("change", studioLoadApp);
  document.getElementById("studio-refresh").addEventListener("click", async () => {
    await studioRefreshAppPicker();
    if (studioCurrentAppDir()) await studioLoadApp();
  });
  document.getElementById("studio-editor").addEventListener("input", studioValidateTextarea);
  document.getElementById("studio-apply").addEventListener("click", studioApply);
  document.getElementById("studio-preview-refresh").addEventListener("click", studioRefreshPreview);
  document.getElementById("studio-rebuild-btn").addEventListener("click", studioRebuild);

  studioRefreshAppPicker().then(() => {
    if (studioCurrentAppDir()) studioLoadApp();
  });
}

window.__npdevInitStudio = initStudio;
