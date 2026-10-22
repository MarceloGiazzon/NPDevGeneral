// The AI Keys tab -- a window over `npdev providers` (npdev_providers.py), which owns the provider
// catalogue and the key store. Same discipline as the Secrets tab: a key only ever travels INTO the
// store; nothing here receives one back (`providers list --json` carries where a key comes from,
// never the key), so a screenshot of this tab cannot leak one.

const { invoke: providersInvoke } = window.__TAURI__.core;

let providersRows = [];

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

function providersRenderStore(store) {
  const el = document.getElementById("providers-store");
  el.innerHTML = store && store.available
    ? `Key store: <b>${providersEsc(store.store)}</b> <span class="dim">(index ${providersEsc(store.vault)})</span>`
    : `<span class="err">No key store on this machine -- ${providersEsc(store ? store.reason : "unknown")}</span>`;
  el.classList.toggle("bad", !(store && store.available));
}

function providersRenderTable(rows) {
  const el = document.getElementById("providers-list");
  const body = rows.map((p) => {
    const [cls, text] = PROVIDERS_SOURCE_BADGE[p.keySource] || ["unset", p.keySource];
    const hasKey = p.keySource === "vault" || p.keySource === "env" || p.keySource === "none-needed";
    const id = providersEsc(p.id);
    return `<tr>
      <td><b>${providersEsc(p.name)}</b><div class="dim">${id}${p.appVendor ? " · app" : ""}</div></td>
      <td><span class="providers-kind ${providersEsc(p.kind)}">${providersEsc(p.kind)}</span></td>
      <td><span class="secrets-badge ${cls}">${text}</span>${p.addedAt ? `<div class="dim">${providersEsc(p.addedAt)}</div>` : ""}</td>
      <td id="providers-test-${id}" class="dim"></td>
      <td>
        ${p.keyEnvVar ? `<button class="btn ghost" data-providers-pick="${id}">${p.keySource === "vault" ? "Replace…" : "Add…"}</button>` : ""}
        ${hasKey ? `<button class="btn ghost" data-providers-test="${id}">Test</button>` : ""}
        ${p.keySource === "vault" ? `<button class="btn ghost" data-providers-remove="${id}">Remove</button>` : ""}
        ${p.signupUrl ? `<a class="btn ghost" href="${providersEsc(p.signupUrl)}" target="_blank" rel="noreferrer">Get a key</a>` : ""}
      </td>
    </tr>`;
  }).join("");
  el.innerHTML = `<table class="secrets-list">
    <thead><tr><th>Provider</th><th>Kind</th><th>Key</th><th>Last test</th><th></th></tr></thead>
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
}

function providersRenderForm(rows) {
  const select = document.getElementById("providers-form-id");
  const previous = select.value;
  const keyed = rows.filter((p) => p.keyEnvVar);
  select.innerHTML = keyed.map((p) => `<option value="${providersEsc(p.id)}">${providersEsc(p.name)}</option>`).join("");
  if (keyed.some((p) => p.id === previous)) select.value = previous;
  providersFormHint();

  document.getElementById("providers-export-choices").innerHTML = rows.filter((p) => p.appVendor).map((p) =>
    `<label class="providers-check"><input type="checkbox" value="${providersEsc(p.id)}" ${p.keySource === "missing" ? "disabled" : ""} /> ${providersEsc(p.name)}</label>`
  ).join("");
}

function providersFormHint() {
  const id = document.getElementById("providers-form-id").value;
  const row = providersRows.find((p) => p.id === id);
  const el = document.getElementById("providers-form-hint");
  if (!row) { el.textContent = ""; return; }
  const paid = row.kind === "paid" ? " PAID provider: the benchmark refuses it unless --allow-paid is given." : "";
  el.textContent = `Env var that overrides the stored key: ${row.keyEnvVar}.${paid}`;
}

async function providersRefresh() {
  try {
    const result = await providersInvoke("providers_list");
    providersRows = result.providers || [];
    providersRenderStore(result.store);
    providersRenderTable(providersRows);
    providersRenderForm(providersRows);
    providersStatus("", false);
  } catch (error) {
    providersStatus(`could not load providers: ${error}`, true);
  }
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

function initProviders() {
  document.getElementById("providers-refresh").addEventListener("click", providersRefresh);
  document.getElementById("providers-form-save").addEventListener("click", providersSave);
  document.getElementById("providers-form-id").addEventListener("change", providersFormHint);
  document.getElementById("providers-export-go").addEventListener("click", providersExport);
  providersRefresh();
}

window.__npdevInitProviders = initProviders;
