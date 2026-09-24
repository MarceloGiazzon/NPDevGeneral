// The Secrets tab -- surfaces the scoped db/deploy credential store (secrets.rs, S17b) that already
// existed backend-only: OS keyring storage under account "<scope>/<env>/<profile id>", isolated per
// environment (dev/staging/prod) so the same profile id can hold a different credential in each.
// Same discipline as the Prompter's provider editor: this file never receives a stored value back
// from Rust, only names and a "hasCredential" flag -- `secret_profiles` deliberately does not return
// values, and neither does this UI render one. A window that can read a key back can leak it into a
// screenshot.

const { invoke: secretsInvoke } = window.__TAURI__.core;

function secretsEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function secretsStatus(message, bad) {
  const el = document.getElementById("secrets-status");
  el.textContent = message || "";
  el.classList.toggle("err", !!bad);
}

function secretsRenderTable(profiles) {
  const el = document.getElementById("secrets-list");
  if (!profiles || profiles.length === 0) {
    el.innerHTML = `<p class="secrets-empty">No secret profiles saved yet -- add one on the right.</p>`;
    return;
  }
  const rows = profiles.map((p) => {
    const badge = p.hasCredential
      ? `<span class="secrets-badge set">SET</span>`
      : `<span class="secrets-badge unset">NOT SET</span>`;
    return `<tr>
      <td>${secretsEsc(p.scope)}</td>
      <td>${secretsEsc(p.env)}</td>
      <td>${secretsEsc(p.profileId)}</td>
      <td>${secretsEsc(p.label)}</td>
      <td>${badge}</td>
      <td>
        <button class="btn ghost" data-secrets-replace="${secretsEsc(p.scope)}|${secretsEsc(p.env)}|${secretsEsc(p.profileId)}|${secretsEsc(p.label)}">Replace…</button>
        <button class="btn ghost" data-secrets-delete="${secretsEsc(p.scope)}|${secretsEsc(p.env)}|${secretsEsc(p.profileId)}">Delete</button>
      </td>
    </tr>`;
  }).join("");
  el.innerHTML = `<table class="secrets-list">
    <thead><tr><th>Scope</th><th>Env</th><th>Profile id</th><th>Label</th><th>Credential</th><th></th></tr></thead>
    <tbody>${rows}</tbody>
  </table>`;

  el.querySelectorAll("[data-secrets-replace]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const [scope, env, profileId, label] = btn.dataset.secretsReplace.split("|");
      document.getElementById("secrets-form-scope").value = scope;
      document.getElementById("secrets-form-env").value = env;
      document.getElementById("secrets-form-id").value = profileId;
      document.getElementById("secrets-form-label").value = label;
      document.getElementById("secrets-form-value").value = "";
      document.getElementById("secrets-form-value").focus();
    });
  });
  el.querySelectorAll("[data-secrets-delete]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const [scope, env, profileId] = btn.dataset.secretsDelete.split("|");
      secretsDeleteProfile(scope, env, profileId);
    });
  });
}

function secretsPopulateSelect(id, values, preserveValue) {
  const el = document.getElementById(id);
  const previous = preserveValue ? el.value : null;
  el.innerHTML = values.map((v) => `<option value="${secretsEsc(v)}">${secretsEsc(v)}</option>`).join("");
  if (previous && values.includes(previous)) el.value = previous;
}

async function secretsRefresh() {
  try {
    const result = await secretsInvoke("secret_profiles", { scope: null, env: null });
    secretsPopulateSelect("secrets-form-scope", result.scopes || [], true);
    secretsPopulateSelect("secrets-form-env", result.environments || [], true);
    secretsRenderTable(result.profiles || []);
    secretsStatus("", false);
  } catch (error) {
    secretsStatus(`could not load secret profiles: ${error}`, true);
  }
}

async function secretsSave() {
  const scope = document.getElementById("secrets-form-scope").value;
  const env = document.getElementById("secrets-form-env").value;
  const profileId = document.getElementById("secrets-form-id").value.trim();
  const label = document.getElementById("secrets-form-label").value.trim();
  const value = document.getElementById("secrets-form-value").value;
  if (!profileId) { secretsStatus("profile id is required", true); return; }
  if (!value) { secretsStatus("a value is required -- this form never shows a previously stored one back", true); return; }
  try {
    await secretsInvoke("save_secret_profile", { scope, env, profileId, label, value });
    document.getElementById("secrets-form-id").value = "";
    document.getElementById("secrets-form-label").value = "";
    document.getElementById("secrets-form-value").value = "";
    secretsStatus(`saved ${scope}/${env}/${profileId}`, false);
    await secretsRefresh();
  } catch (error) {
    secretsStatus(`save failed: ${error}`, true);
  }
}

// One-click, no confirmation dialog -- same convention as the Prompter's provider delete: this
// removes a registry entry and its keyring value, not data, and re-entering the value later fully
// recovers it.
async function secretsDeleteProfile(scope, env, profileId) {
  try {
    await secretsInvoke("delete_secret_profile", { scope, env, profileId });
    secretsStatus(`deleted ${scope}/${env}/${profileId}`, false);
    await secretsRefresh();
  } catch (error) {
    secretsStatus(`delete failed: ${error}`, true);
  }
}

function initSecrets() {
  document.getElementById("secrets-refresh").addEventListener("click", secretsRefresh);
  document.getElementById("secrets-form-save").addEventListener("click", secretsSave);
  secretsRefresh();
}

window.__npdevInitSecrets = initSecrets;
