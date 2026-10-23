// The AI Providers tab (AIP-4) -- a window over two CLIs and nothing else. `npdev ai` owns the
// configuration (providers, models, profiles, which feature uses which profile, the spend guard, the
// usage log); `npdev providers` owns the keys. Same discipline as the Secrets tab: a key only ever
// travels INTO the store; nothing here receives one back (both listings carry where a key comes from,
// never the key), so a screenshot of this tab cannot leak one.

const { invoke: providersInvoke } = window.__TAURI__.core;

let providersRows = [];   // `npdev providers list`: the key store's view
let aiProviders = [];
let aiModels = [];
let aiProfiles = [];
let aiBindings = [];
let aiUsage = null;
let aiSpend = null;

function providersEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function providersStatus(message, bad) {
  const el = document.getElementById("providers-status");
  el.textContent = message || "";
  el.classList.toggle("err", !!bad);
}

const PROVIDERS_SOURCE_BADGE = {
  vault: ["set", "STORED"],
  env: ["set", "FROM ENV VAR"],
  "none-needed": ["unset", "NO KEY NEEDED"],
  missing: ["unset", "NO KEY"],
};

const AI_SOURCE_LABEL = { shipped: "", mine: "yours", overridden: "changed" };

function aiSourceTag(source) {
  const label = AI_SOURCE_LABEL[source];
  return label ? ` <span class="ai-source">${label}</span>` : "";
}

function usd(value) {
  return value == null ? "?" : `$${parseFloat(Number(value).toFixed(4))}`;
}

/** `npdev ai <args>`; an `ok: false` answer becomes an error carrying the CLI's own message. */
async function aiRun(args) {
  const result = await providersInvoke("ai_command", { args });
  if (result && result.ok === false) throw new Error(result.error || `ai ${args[0]} failed`);
  return result;
}

/** One change, then a full refresh -- every view reads the same configuration. */
async function aiChange(args, done) {
  try {
    await aiRun(args);
    providersStatus(done, false);
    await providersRefresh();
  } catch (error) {
    providersStatus(`${args.slice(0, 2).join(" ")} failed: ${error.message || error}`, true);
  }
}

// ---------------------------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------------------------

function providersRenderStore(store) {
  const el = document.getElementById("providers-store");
  el.innerHTML = store && store.available
    ? `Key store: <b>${providersEsc(store.store)}</b> <span class="dim">(index ${providersEsc(store.vault)})</span>`
    : `<span class="err">No key store on this machine -- ${providersEsc(store ? store.reason : "unknown")}</span>`;
  el.classList.toggle("bad", !(store && store.available));
}

function providersRenderTable() {
  const el = document.getElementById("providers-list");
  const body = aiProviders.map((p) => {
    const [cls, text] = PROVIDERS_SOURCE_BADGE[p.keySource] || ["unset", p.keySource];
    const hasKey = p.keySource === "vault" || p.keySource === "env" || p.keySource === "none-needed";
    const id = providersEsc(p.id);
    return `<tr class="${p.disabled ? "ai-off" : ""}">
      <td><b>${providersEsc(p.name)}</b>${aiSourceTag(p.source)}
        <div class="dim">${id}${p.appVendor ? " · app" : ""} · ${providersEsc(p.protocol)} · ${providersEsc(p.baseUrl)}</div></td>
      <td><span class="providers-kind ${providersEsc(p.kind)}">${providersEsc(p.kind)}</span></td>
      <td><span class="secrets-badge ${cls}">${text}</span></td>
      <td>${p.models == null ? "" : providersEsc(p.models)}</td>
      <td id="providers-test-${id}" class="dim">${p.disabled ? "disabled" : ""}</td>
      <td>
        ${p.keyEnvVar ? `<button class="btn ghost" data-providers-pick="${id}">${p.keySource === "vault" ? "Replace key…" : "Add key…"}</button>` : ""}
        ${hasKey && !p.disabled ? `<button class="btn ghost" data-providers-test="${id}">Test</button>` : ""}
        ${p.keySource === "vault" ? `<button class="btn ghost" data-providers-remove="${id}">Remove key</button>` : ""}
        <button class="btn ghost" data-ai-provider-toggle="${id}">${p.disabled ? "Enable" : "Disable"}</button>
        ${p.source === "overridden" ? `<button class="btn ghost" data-ai-reset="provider:${id}">Reset</button>` : ""}
        ${p.signupUrl ? `<a class="btn ghost" href="${providersEsc(p.signupUrl)}" target="_blank" rel="noreferrer">Get a key</a>` : ""}
      </td>
    </tr>`;
  }).join("");
  el.innerHTML = `<table class="secrets-list">
    <thead><tr><th>Provider</th><th>Kind</th><th>Key</th><th>Models</th><th>Last test</th><th></th></tr></thead>
    <tbody>${body}</tbody>
  </table>`;

  el.querySelectorAll("[data-providers-pick]").forEach((btn) => btn.addEventListener("click", () => {
    document.getElementById("providers-form-id").value = btn.dataset.providersPick;
    providersFormHint();
    document.getElementById("providers-form-key").focus();
  }));
  el.querySelectorAll("[data-providers-test]").forEach((btn) =>
    btn.addEventListener("click", () => providersTest(btn.dataset.providersTest)));
  el.querySelectorAll("[data-providers-remove]").forEach((btn) =>
    btn.addEventListener("click", () => providersRemove(btn.dataset.providersRemove)));
  el.querySelectorAll("[data-ai-provider-toggle]").forEach((btn) => btn.addEventListener("click", () => {
    const id = btn.dataset.aiProviderToggle;
    const verb = btn.textContent === "Enable" ? "enable" : "disable";
    aiChange(["providers", verb, id], `${verb}d ${id}`);
  }));
  aiBindResets(el);
}

function aiBindResets(el) {
  el.querySelectorAll("[data-ai-reset]").forEach((btn) => btn.addEventListener("click", () => {
    const [section, key] = btn.dataset.aiReset.split(/:(.*)/s);
    aiChange(["reset", section, key], `${section} ${key} is back to the shipped value`);
  }));
}

function providersRenderForm() {
  const select = document.getElementById("providers-form-id");
  const previous = select.value;
  const keyed = providersRows.filter((p) => p.keyEnvVar);
  select.innerHTML = keyed.map((p) => `<option value="${providersEsc(p.id)}">${providersEsc(p.name)}</option>`).join("");
  if (keyed.some((p) => p.id === previous)) select.value = previous;
  providersFormHint();

  document.getElementById("providers-export-choices").innerHTML = providersRows.filter((p) => p.appVendor).map((p) =>
    `<label class="providers-check"><input type="checkbox" value="${providersEsc(p.id)}" ${p.keySource === "missing" ? "disabled" : ""} /> ${providersEsc(p.name)}</label>`
  ).join("");
}

function providersFormHint() {
  const id = document.getElementById("providers-form-id").value;
  const row = providersRows.find((p) => p.id === id);
  const el = document.getElementById("providers-form-hint");
  if (!row) { el.textContent = ""; return; }
  const paid = row.kind === "paid" ? " PAID provider: a feature uses it only after a paid opt-in (USES)." : "";
  el.textContent = `Env var that overrides the stored key: ${row.keyEnvVar}.${paid}`;
}

function providersAnswer(result, verb) {
  if (result && result.ok === false) throw new Error(result.error || `${verb} failed`);
  return result;
}

async function providersSave() {
  const provider = document.getElementById("providers-form-id").value;
  const input = document.getElementById("providers-form-key");
  const key = input.value.trim();
  if (!key) { providersStatus("paste the key first -- a stored key is never shown back", true); return; }
  try {
    const result = providersAnswer(await providersInvoke("providers_add", { provider, key }), "save");
    input.value = "";
    providersStatus(`stored ${provider} (${result.store})`, false);
    await providersRefresh();
  } catch (error) {
    input.value = "";
    providersStatus(`save failed: ${error.message || error}`, true);
  }
}

// One click, no confirmation -- same convention as the Secrets tab: re-adding the key fully recovers.
async function providersRemove(provider) {
  try {
    providersAnswer(await providersInvoke("providers_remove", { provider }), "remove");
    providersStatus(`removed ${provider}`, false);
    await providersRefresh();
  } catch (error) {
    providersStatus(`remove failed: ${error.message || error}`, true);
  }
}

async function providersTest(provider) {
  const cell = document.getElementById(`providers-test-${provider}`);
  if (cell) cell.textContent = "testing…";
  try {
    const result = await providersInvoke("providers_test", { provider, chat: false });
    const text = result.ok ? `OK · ${result.models} models · ${result.seconds}s` : `FAILED · ${result.error}`;
    if (cell) {
      cell.textContent = text;
      cell.classList.toggle("err", !result.ok);
    }
  } catch (error) {
    if (cell) cell.textContent = `FAILED · ${error}`;
  }
}

async function providersExport() {
  const appDir = document.getElementById("providers-export-app").value.trim();
  const providers = [...document.querySelectorAll("#providers-export-choices input:checked")].map((i) => i.value);
  if (!appDir) { providersStatus("type the generated app's folder first", true); return; }
  if (providers.length === 0) { providersStatus("check at least one provider to export", true); return; }
  try {
    const result = providersAnswer(await providersInvoke("providers_export", { appDir, providers }), "export");
    const shadowed = (result.shadowedByLinesOutsideBlock || []).length
      ? ` -- WARNING: ${result.shadowedByLinesOutsideBlock.join(", ")} also set outside the managed block`
      : "";
    providersStatus(`wrote ${result.file}${shadowed}`, shadowed !== "");
  } catch (error) {
    providersStatus(`export failed: ${error.message || error}`, true);
  }
}

async function aiDetect(add) {
  const el = document.getElementById("ai-detect-result");
  el.textContent = add ? "adding…" : "searching…";
  try {
    const result = await aiRun(add ? ["detect", "--add"] : ["detect"]);
    const servers = result.servers || [];
    if (servers.length === 0) {
      el.innerHTML = `<span class="dim">No local LLM server answered. Start Ollama or LM Studio and search again.</span>`;
      return;
    }
    el.innerHTML = servers.map((s) => `<div><b>${providersEsc(s.name)}</b> <span class="dim">${providersEsc(s.baseUrl)}</span>
      <div class="dim">${(s.models || []).map(providersEsc).join(", ") || "(no models loaded)"}</div></div>`).join("")
      + (add ? `<div class="dim">added ${(result.added || []).length} model(s)</div>`
             : `<button class="btn ghost" id="ai-detect-add">Add all as local providers</button>`);
    const addButton = document.getElementById("ai-detect-add");
    if (addButton) addButton.addEventListener("click", async () => { await aiDetect(true); await providersRefresh(); });
  } catch (error) {
    el.innerHTML = `<span class="err">${providersEsc(error.message || error)}</span>`;
  }
}

function aiAddProvider() {
  const value = (id) => document.getElementById(id).value.trim();
  const id = value("ai-prov-id");
  const url = value("ai-prov-url");
  if (!id || !url) { providersStatus("a provider needs an id and a base URL", true); return; }
  const kind = value("ai-prov-kind");
  const args = ["providers", "add", id, "--base-url", url, "--protocol", value("ai-prov-protocol"), "--kind", kind];
  if (value("ai-prov-name")) args.push("--name", value("ai-prov-name"));
  if (kind === "local") args.push("--auth-style", "none");
  aiChange(args, `added ${id} -- now add its models in MODELS`);
}

// ---------------------------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------------------------

function aiProviderOptions(select, { all } = {}) {
  const previous = select.value;
  const options = aiProviders.filter((p) => !p.disabled)
    .map((p) => `<option value="${providersEsc(p.id)}">${providersEsc(p.name)}</option>`).join("");
  select.innerHTML = (all ? `<option value="">all providers</option>` : "") + options;
  if ([...select.options].some((o) => o.value === previous)) select.value = previous;
}

function aiPrice(m) {
  if (!m.paid) return "free";
  if (!m.price || m.price.in == null) return `<span class="err">no price</span>`;
  return `${usd(m.price.in)} / ${usd(m.price.out)}`;
}

function aiRenderModels() {
  const filter = document.getElementById("ai-models-provider").value;
  const rows = aiModels.filter((m) => !filter || m.provider === filter);
  const body = rows.map((m) => {
    const key = `${providersEsc(m.provider)}|${providersEsc(m.id)}`;
    return `<tr class="${m.enabled === false ? "ai-off" : ""}">
      <td><b>${providersEsc(m.name || m.id)}</b>${aiSourceTag(m.source)}<div class="dim">${providersEsc(m.provider)} · ${providersEsc(m.id)}</div></td>
      <td>${m.contextTokens ? `${Math.round(m.contextTokens / 1000)}K` : ""}</td>
      <td>${aiPrice(m)}</td>
      <td class="dim">${(m.effortLevels || []).map(providersEsc).join(", ")}</td>
      <td class="dim">${m.enabled === false ? "disabled" : providersEsc(m.status || "")}</td>
      <td>
        <button class="btn ghost" data-ai-model-edit="${key}">Edit</button>
        <button class="btn ghost" data-ai-model-toggle="${key}">${m.enabled === false ? "Enable" : "Disable"}</button>
        ${m.source === "overridden" ? `<button class="btn ghost" data-ai-reset="model:${providersEsc(m.provider)}/${providersEsc(m.id)}">Reset</button>` : ""}
      </td>
    </tr>`;
  }).join("");
  const el = document.getElementById("ai-models-list");
  el.innerHTML = `<table class="secrets-list">
    <thead><tr><th>Model</th><th>Context</th><th>USD / M tokens in / out</th><th>Effort</th><th>Status</th><th></th></tr></thead>
    <tbody>${body || `<tr><td colspan="6" class="dim">no models -- Refresh from server, or add one</td></tr>`}</tbody>
  </table>`;
  el.querySelectorAll("[data-ai-model-toggle]").forEach((btn) => btn.addEventListener("click", () => {
    const [provider, model] = btn.dataset.aiModelToggle.split("|");
    const verb = btn.textContent === "Enable" ? "enable" : "disable";
    aiChange(["models", verb, provider, model], `${verb}d ${provider}/${model}`);
  }));
  el.querySelectorAll("[data-ai-model-edit]").forEach((btn) => btn.addEventListener("click", () => {
    const [provider, id] = btn.dataset.aiModelEdit.split("|");
    const m = aiModels.find((x) => x.provider === provider && x.id === id);
    document.getElementById("ai-model-provider").value = provider;
    document.getElementById("ai-model-id").value = id;
    document.getElementById("ai-model-name").value = m.name || "";
    document.getElementById("ai-model-in").value = m.price && m.price.in != null ? m.price.in : "";
    document.getElementById("ai-model-out").value = m.price && m.price.out != null ? m.price.out : "";
    document.getElementById("ai-model-effort").value = (m.effortLevels || []).join(",");
  }));
  aiBindResets(el);
}

function aiSaveModel() {
  const value = (id) => document.getElementById(id).value.trim();
  const provider = value("ai-model-provider");
  const model = value("ai-model-id");
  if (!provider || !model) { providersStatus("pick a provider and type the model id", true); return; }
  const exists = aiModels.some((m) => m.provider === provider && m.id === model);
  const args = ["models", exists ? "set" : "add", provider, model];
  if (value("ai-model-name")) args.push("--name", value("ai-model-name"));
  if (value("ai-model-in")) args.push("--price-in", value("ai-model-in"));
  if (value("ai-model-out")) args.push("--price-out", value("ai-model-out"));
  if (value("ai-model-effort")) args.push("--effort-levels", value("ai-model-effort"));
  aiChange(args, `${exists ? "saved" : "added"} ${provider}/${model}`);
}

function aiRefreshModels() {
  const provider = document.getElementById("ai-models-provider").value;
  if (!provider) { providersStatus("pick one provider to ask for its model list", true); return; }
  providersStatus(`asking ${provider} for its models…`, false);
  aiChange(["models", "refresh", provider], `refreshed the models of ${provider}`);
}

// ---------------------------------------------------------------------------------------------
// Profiles
// ---------------------------------------------------------------------------------------------

function aiFeaturesUsing(profileName) {
  return aiBindings.filter((b) => aiChain(b.profiles).some((p) => p.name === profileName)).map((b) => b.feature);
}

/** A binding's profiles plus every fallback they reach, in the order a call tries them. */
function aiChain(names) {
  const seen = [];
  const visit = (name) => {
    const profile = aiProfiles.find((p) => p.name === name);
    if (!profile || seen.includes(profile)) return;
    seen.push(profile);
    (profile.fallback || []).forEach(visit);
  };
  (names || []).forEach(visit);
  return seen;
}

function aiRenderProfiles() {
  const body = aiProfiles.map((p) => {
    const name = providersEsc(p.name);
    const uses = aiFeaturesUsing(p.name);
    return `<tr class="${p.disabled ? "ai-off" : ""}">
      <td><b>${name}</b>${aiSourceTag(p.source)}<div class="dim">${providersEsc(p.description || "")}</div>
        ${p.problem ? `<div class="err">${providersEsc(p.problem)}</div>` : ""}</td>
      <td>${providersEsc(p.provider)}<div class="dim">${providersEsc(p.model)}</div></td>
      <td class="dim">${[p.effort, p.maxTokens && `${p.maxTokens} tok`].filter(Boolean).map(providersEsc).join(" · ")}</td>
      <td class="dim">${(p.fallback || []).map(providersEsc).join(" → ")}</td>
      <td class="dim">${uses.map(providersEsc).join(", ")}</td>
      <td id="ai-profile-test-${name}" class="dim"></td>
      <td>
        ${p.disabled ? "" : `<button class="btn ghost" data-ai-profile-test="${name}">Test</button>`}
        <button class="btn ghost" data-ai-profile-edit="${name}">Edit</button>
        <button class="btn ghost" data-ai-profile-toggle="${name}">${p.disabled ? "Enable" : "Disable"}</button>
        ${p.source === "mine" ? `<button class="btn ghost" data-ai-profile-remove="${name}">Remove</button>` : ""}
        ${p.source === "overridden" ? `<button class="btn ghost" data-ai-reset="profile:${name}">Reset</button>` : ""}
      </td>
    </tr>`;
  }).join("");
  const el = document.getElementById("ai-profiles-list");
  el.innerHTML = `<table class="secrets-list">
    <thead><tr><th>Profile</th><th>Provider / model</th><th>Effort</th><th>Then</th><th>Used by</th><th>Last test</th><th></th></tr></thead>
    <tbody>${body}</tbody>
  </table>`;
  el.querySelectorAll("[data-ai-profile-test]").forEach((btn) =>
    btn.addEventListener("click", () => aiTestProfile(btn.dataset.aiProfileTest)));
  el.querySelectorAll("[data-ai-profile-edit]").forEach((btn) =>
    btn.addEventListener("click", () => aiEditProfile(btn.dataset.aiProfileEdit)));
  el.querySelectorAll("[data-ai-profile-toggle]").forEach((btn) => btn.addEventListener("click", () => {
    const name = btn.dataset.aiProfileToggle;
    const verb = btn.textContent === "Enable" ? "enable" : "disable";
    aiChange(["profiles", verb, name], `${verb}d profile ${name}`);
  }));
  el.querySelectorAll("[data-ai-profile-remove]").forEach((btn) => btn.addEventListener("click", () =>
    aiChange(["profiles", "remove", btn.dataset.aiProfileRemove], `removed profile ${btn.dataset.aiProfileRemove}`)));
  aiBindResets(el);
}

// A test spends a real (one-word) call; a paid profile is refused by the CLI without --allow-paid.
async function aiTestProfile(name) {
  const cell = document.getElementById(`ai-profile-test-${name}`);
  if (cell) { cell.textContent = "testing…"; cell.classList.remove("err"); }
  try {
    const result = await providersInvoke("ai_command", { args: ["test", name] });
    const text = result.ok
      ? `OK · ${result.provider}/${result.model} · ${result.seconds}s · ${result.inputTokens ?? "?"}+${result.outputTokens ?? "?"} tok`
      : `FAILED · ${result.error}`;
    if (cell) {
      cell.textContent = text.length > 140 ? `${text.slice(0, 140)}…` : text;
      cell.title = text;
      cell.classList.toggle("err", !result.ok);
    }
  } catch (error) {
    if (cell) { cell.textContent = `FAILED · ${error}`; cell.classList.add("err"); }
  }
}

function aiProfileModelOptions(selected) {
  const provider = document.getElementById("ai-profile-provider").value;
  const select = document.getElementById("ai-profile-model");
  const models = aiModels.filter((m) => m.provider === provider && m.enabled !== false);
  select.innerHTML = models.map((m) => `<option value="${providersEsc(m.id)}">${providersEsc(m.name || m.id)}</option>`).join("");
  if (selected && models.some((m) => m.id === selected)) select.value = selected;
}

function aiEditProfile(name) {
  const p = aiProfiles.find((x) => x.name === name);
  if (!p) return;
  document.getElementById("ai-profile-name").value = p.name;
  document.getElementById("ai-profile-desc").value = p.description || "";
  document.getElementById("ai-profile-provider").value = p.provider;
  aiProfileModelOptions(p.model);
  document.getElementById("ai-profile-effort").value = p.effort || "";
  document.getElementById("ai-profile-max").value = p.maxTokens || "";
  document.getElementById("ai-profile-fallback").value = (p.fallback || []).join(",");
}

function aiSaveProfile() {
  const value = (id) => document.getElementById(id).value.trim();
  const name = value("ai-profile-name");
  if (!name || !value("ai-profile-provider") || !value("ai-profile-model")) {
    providersStatus("a profile needs a name, a provider and one of its models", true);
    return;
  }
  const exists = aiProfiles.some((p) => p.name === name);
  const args = ["profiles", exists ? "set" : "add", name,
                "--provider", value("ai-profile-provider"), "--model", value("ai-profile-model")];
  if (value("ai-profile-desc")) args.push("--description", value("ai-profile-desc"));
  if (value("ai-profile-effort")) args.push("--effort", value("ai-profile-effort"));
  if (value("ai-profile-max")) args.push("--max-tokens", value("ai-profile-max"));
  if (value("ai-profile-fallback")) args.push("--fallback", value("ai-profile-fallback"));
  aiChange(args, `${exists ? "saved" : "added"} profile ${name}`);
}

// ---------------------------------------------------------------------------------------------
// Uses: which feature calls what, and what it cost this month
// ---------------------------------------------------------------------------------------------

function aiUsageRows(predicate) {
  const rows = (aiUsage && aiUsage.rows || []).filter(predicate);
  return {
    calls: rows.reduce((n, r) => n + r.calls, 0),
    failed: rows.reduce((n, r) => n + r.failed, 0),
    estUsd: rows.reduce((n, r) => n + (r.estUsd || 0), 0),
    unpriced: rows.reduce((n, r) => n + (r.unpriced || 0), 0),
  };
}

function aiUsageCell(u) {
  if (!u.calls) return `<span class="dim">no calls</span>`;
  const failed = u.failed ? ` <span class="err">(${u.failed} failed)</span>` : "";
  const unpriced = u.unpriced ? ` <span class="err">+${u.unpriced} unpriced</span>` : "";
  return `${u.calls} call(s)${failed} · ${usd(u.estUsd)}${unpriced}`;
}

function aiRenderUses() {
  const allowPaid = (aiSpend && aiSpend.allowPaid) || {};
  const options = (selected) => aiProfiles.filter((p) => !p.disabled).map((p) =>
    `<option value="${providersEsc(p.name)}" ${p.name === selected ? "selected" : ""}>${providersEsc(p.name)}</option>`).join("");
  const body = aiBindings.map((b) => {
    const feature = providersEsc(b.feature);
    const [first, ...rest] = b.profiles || [];
    const chain = aiChain(b.profiles).map((p) => `${providersEsc(p.provider)}/${providersEsc(p.model)}`).join(" → ");
    return `<tr>
      <td><b>${feature}</b>${aiSourceTag(b.source)}</td>
      <td><select data-ai-bind-first="${feature}">${options(first)}</select>
        <input type="text" class="ai-then" data-ai-bind-rest="${feature}" value="${providersEsc(rest.join(","))}" placeholder="then (optional)" /></td>
      <td class="dim">${chain}</td>
      <td><label class="providers-check"><input type="checkbox" data-ai-paid="${feature}" ${allowPaid[b.feature] ? "checked" : ""} /> paid calls</label></td>
      <td>${aiUsageCell(aiUsageRows((r) => r.feature === b.feature))}</td>
      <td>
        <button class="btn ghost" data-ai-bind-save="${feature}">Save</button>
        ${b.source === "overridden" ? `<button class="btn ghost" data-ai-reset="binding:${feature}">Reset</button>` : ""}
      </td>
    </tr>`;
  }).join("");
  const el = document.getElementById("ai-uses-list");
  el.innerHTML = `<table class="secrets-list">
    <thead><tr><th>Feature</th><th>Profile, then fallbacks</th><th>Reaches</th><th>Paid</th><th>This month</th><th></th></tr></thead>
    <tbody>${body}</tbody>
  </table>`;
  el.querySelectorAll("[data-ai-bind-save]").forEach((btn) => btn.addEventListener("click", () => {
    const feature = btn.dataset.aiBindSave;
    const first = el.querySelector(`[data-ai-bind-first="${CSS.escape(feature)}"]`).value;
    const rest = el.querySelector(`[data-ai-bind-rest="${CSS.escape(feature)}"]`).value.split(",")
      .map((s) => s.trim()).filter((s) => s && s !== first);
    aiChange(["bind", feature, [first, ...rest].join(",")], `${feature} now uses ${[first, ...rest].join(" → ")}`);
  }));
  el.querySelectorAll("[data-ai-paid]").forEach((box) => box.addEventListener("change", () => {
    const feature = box.dataset.aiPaid;
    aiChange(box.checked ? ["spend", "allow-paid", feature] : ["spend", "allow-paid", feature, "--off"],
      `${feature} ${box.checked ? "may" : "may no longer"} make paid calls`);
  }));
  aiBindResets(el);
  aiRenderByProvider();
}

/** Answers "what uses my Gemini key, and what did it cost": features that can reach each provider. */
function aiRenderByProvider() {
  const reach = {};
  aiBindings.forEach((b) => aiChain(b.profiles).forEach((p) => {
    (reach[p.provider] = reach[p.provider] || new Set()).add(b.feature);
  }));
  (aiUsage && aiUsage.rows || []).forEach((r) => { reach[r.provider] = reach[r.provider] || new Set(); });
  const body = Object.keys(reach).sort().map((id) => {
    const p = aiProviders.find((x) => x.id === id) || { name: id };
    const [cls, text] = PROVIDERS_SOURCE_BADGE[p.keySource] || ["unset", p.keySource || "?"];
    return `<tr>
      <td><b>${providersEsc(p.name)}</b><div class="dim">${providersEsc(id)}</div></td>
      <td><span class="secrets-badge ${cls}">${text}</span></td>
      <td>${[...reach[id]].map(providersEsc).join(", ") || `<span class="dim">no feature</span>`}</td>
      <td>${aiUsageCell(aiUsageRows((r) => r.provider === id))}</td>
    </tr>`;
  }).join("");
  document.getElementById("ai-by-provider").innerHTML = `<table class="secrets-list">
    <thead><tr><th>Provider</th><th>Key</th><th>Features that may call it</th><th>Calls and cost</th></tr></thead>
    <tbody>${body}</tbody>
  </table>`;
  document.getElementById("ai-usage-since").textContent = aiUsage ? (aiUsage.since || "").slice(0, 10) : "?";
  const unknown = aiUsage && aiUsage.paidCallsWithoutPrice ? ` + ${aiUsage.paidCallsWithoutPrice} paid call(s) of unknown price` : "";
  document.getElementById("ai-usage-total").textContent = aiUsage
    ? `estimated total ${usd(aiUsage.estUsd)}${unknown} · log ${aiUsage.log}` : "";
  const cap = document.getElementById("ai-spend-cap");
  if (document.activeElement !== cap) cap.value = aiSpend && aiSpend.monthlyCapUsd != null ? aiSpend.monthlyCapUsd : "";
}

function aiSaveCap() {
  const value = document.getElementById("ai-spend-cap").value.trim();
  aiChange(["spend", "cap", value || "off"], value ? `monthly cap $${value}` : "monthly cap removed");
}

// ---------------------------------------------------------------------------------------------

async function providersRefresh() {
  const errors = [];
  const load = async (promise, label) => {
    try { return await promise; } catch (error) { errors.push(`${label}: ${error.message || error}`); return null; }
  };
  const [keys, providers, models, profiles, bindings, usage, spend] = await Promise.all([
    load(providersInvoke("providers_list"), "keys"),
    load(aiRun(["providers"]), "providers"),
    load(aiRun(["models", "--all"]), "models"),
    load(aiRun(["profiles"]), "profiles"),
    load(aiRun(["bindings"]), "bindings"),
    load(aiRun(["usage"]), "usage"),
    load(aiRun(["spend"]), "spend"),
  ]);
  if (keys) {
    providersRows = keys.providers || [];
    providersRenderStore(keys.store);
  }
  if (providers) aiProviders = providers.providers || [];
  if (models) aiModels = models.models || [];
  if (profiles) aiProfiles = profiles.profiles || [];
  if (bindings) aiBindings = bindings.bindings || [];
  if (usage) aiUsage = usage;
  if (spend) aiSpend = spend;

  providersRenderTable();
  providersRenderForm();
  [document.getElementById("ai-models-provider"), document.getElementById("ai-model-provider"),
   document.getElementById("ai-profile-provider")].forEach((select, i) => aiProviderOptions(select, { all: i === 0 }));
  aiRenderModels();
  aiProfileModelOptions(document.getElementById("ai-profile-model").value);
  aiRenderProfiles();
  aiRenderUses();
  if (errors.length) providersStatus(`could not load ${errors.join("; ")}`, true);
}

function aiShowView(view) {
  document.querySelectorAll("#ai-views button").forEach((b) => b.classList.toggle("active", b.dataset.aiView === view));
  document.querySelectorAll("#screen-providers [data-ai-pane]").forEach((pane) => { pane.hidden = pane.dataset.aiPane !== view; });
}

function initProviders() {
  document.querySelectorAll("#ai-views button").forEach((b) => b.addEventListener("click", () => aiShowView(b.dataset.aiView)));
  document.getElementById("providers-refresh").addEventListener("click", () => {
    providersStatus("", false);
    providersRefresh();
  });
  document.getElementById("providers-form-save").addEventListener("click", providersSave);
  document.getElementById("providers-form-id").addEventListener("change", providersFormHint);
  document.getElementById("providers-export-go").addEventListener("click", providersExport);
  document.getElementById("ai-detect-go").addEventListener("click", () => aiDetect(false));
  document.getElementById("ai-prov-add").addEventListener("click", aiAddProvider);
  document.getElementById("ai-models-provider").addEventListener("change", aiRenderModels);
  document.getElementById("ai-models-refresh").addEventListener("click", aiRefreshModels);
  document.getElementById("ai-model-save").addEventListener("click", aiSaveModel);
  document.getElementById("ai-profile-provider").addEventListener("change", () => aiProfileModelOptions(null));
  document.getElementById("ai-profile-save").addEventListener("click", aiSaveProfile);
  document.getElementById("ai-spend-cap-save").addEventListener("click", aiSaveCap);
  providersRefresh();
}

window.__npdevInitProviders = initProviders;
