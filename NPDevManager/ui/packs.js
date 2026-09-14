// The Packs tab -- W3.2 (NPDEV_ROADMAP_2026-09-12): the Manager had no pack screen at all even
// though packs are the platform's central reuse unit. Thin pipe, same standing rule as the
// Verification tab: every decision about what is locked, what a signature says, and what is
// deprecated lives in the CLI (`npdev pack list`/`pack why`, both shelled out to verbatim by
// npdev.rs's run_pack_list/run_pack_why) -- nothing here re-derives resolution, a digest, or a
// signature's meaning. `npdev_cli.py`'s own `_enrich_pack_list_report` is what merges `signature`
// and `deprecated` into `pack list`'s report before this file ever sees it.

const { invoke: kInvoke } = window.__TAURI__.core;

const packsState = {
  apps: [],
  report: null,
  selectedPackId: null,
  why: null,
};

function pEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function shortDigest(digest) {
  if (!digest) return "—";
  const bare = digest.startsWith("sha256:") ? digest.slice(7) : digest;
  return bare.length > 12 ? `${bare.slice(0, 12)}…` : bare;
}

// Same three-state badge shape npdev_cli.py's `_classify_pack_signature` already defines
// (VERIFIED / unsigned-allowed / everything else is a refusal that never reaches a locked entry) --
// this just draws what the lock already recorded, never re-verifies.
function signatureBadge(entry) {
  const sig = entry.signature;
  if (!sig) return `<span class="pbadge local">local</span>`;
  if (sig.status === "verified") return `<span class="pbadge verified">✓ verified (${pEsc(sig.keyId || "")})</span>`;
  if (sig.status === "unsigned") return `<span class="pbadge unsigned">unsigned${sig.allowedUnsigned ? " (allowed)" : ""}</span>`;
  return `<span class="pbadge unknown">${pEsc(sig.status || "unknown")}</span>`;
}

function deprecatedBadge(entry) {
  const dep = entry.deprecated;
  if (!dep) return "";
  const successor = dep.supersededBy
    ? ` — use <code>${pEsc(dep.supersededBy.pack)}</code> @ ${pEsc(dep.supersededBy.version)} instead`
    : "";
  return `
    <div class="pdeprecated">
      ⚠ <b>DEPRECATED</b> since ${pEsc(dep.since)}: ${pEsc(dep.reason)}${successor}
    </div>`;
}

function packRow(packId, entry) {
  const selected = packId === packsState.selectedPackId;
  return `
    <div class="prow${selected ? " selected" : ""}" data-pack-id="${pEsc(packId)}">
      <div class="prow-main">
        <span class="pname">${pEsc(packId)}</span>
        <span class="pversion">${pEsc(entry.resolvedVersion || "?")}</span>
        <span class="pdigest" title="${pEsc(entry.digest || "")}">${shortDigest(entry.digest)}</span>
        ${signatureBadge(entry)}
        <span class="pactions">
          <button class="btn ghost" data-why="${pEsc(packId)}">why?</button>
          <button class="btn ghost" data-export="${pEsc(packId)}">export…</button>
        </span>
      </div>
      ${deprecatedBadge(entry)}
    </div>`;
}

function renderPacks() {
  const list = document.getElementById("packs-list");
  const report = packsState.report;
  if (!report) {
    list.innerHTML = `<p class="status-line">Pick an app above, then Refresh.</p>`;
    return;
  }
  if (report.status === "failed") {
    list.innerHTML = `<p class="status-line err">${pEsc(report.error || "pack list failed")}</p>`;
    return;
  }
  const packs = report.packs || {};
  const ids = Object.keys(packs).sort();
  if (ids.length === 0) {
    list.innerHTML = report.locked === false
      ? `<p class="status-line">Not locked yet — run <code>npdev pack add</code> for this app.</p>`
      : `<p class="status-line">This app declares no packs.</p>`;
    return;
  }
  list.innerHTML = ids.map((id) => packRow(id, packs[id])).join("");
  wirePackRows();
}

function wirePackRows() {
  const list = document.getElementById("packs-list");
  list.querySelectorAll("[data-why]").forEach((btn) =>
    btn.addEventListener("click", () => showWhy(btn.dataset.why)));
  list.querySelectorAll("[data-export]").forEach((btn) =>
    btn.addEventListener("click", () => exportPack(btn.dataset.export)));
}

async function showWhy(packId) {
  packsState.selectedPackId = packId;
  renderPacks();
  const box = document.getElementById("packs-why");
  box.hidden = false;
  box.innerHTML = `<p class="status-line">asking why <code>${pEsc(packId)}</code> resolved…</p>`;
  const appDir = currentAppDir();
  if (!appDir) return;
  try {
    const why = await kInvoke("pack_why", { appDir, packId });
    packsState.why = why;
    if (why.status !== "ok") {
      box.innerHTML = `<p class="status-line err">${pEsc(why.error || "pack why failed")}</p>`;
      return;
    }
    const requiredBy = Array.isArray(why.requiredBy) ? why.requiredBy : [];
    box.innerHTML = `
      <h4>Why <code>${pEsc(packId)}</code> resolved</h4>
      ${requiredBy.length
        ? `<ul class="pwhy-list">${requiredBy.map((r) => `<li>${pEsc(r)}</li>`).join("")}</ul>`
        : `<p class="status-line">Directly declared by this app — nothing else requires it.</p>`}`;
  } catch (error) {
    box.innerHTML = `<p class="status-line err">could not ask why: ${pEsc(error)}</p>`;
  }
}

async function exportPack(packId) {
  const report = packsState.report;
  const entry = report && report.packs && report.packs[packId];
  if (!entry || !entry.sourcePath) return;
  try {
    const saved = await kInvoke("pack_export_copy", {
      sourcePath: entry.sourcePath,
      suggestedName: `${packId}-${entry.resolvedVersion || "unknown"}.pack.json`,
    });
    if (saved) packsStatus(`saved a copy of '${packId}' to ${saved}`, false);
  } catch (error) {
    packsStatus(`export failed: ${error}`, true);
  }
}

function packsStatus(message, bad) {
  const el = document.getElementById("packs-status");
  el.textContent = message || "";
  el.style.color = bad ? "var(--err)" : "var(--muted)";
}

function currentAppDir() {
  const picker = document.getElementById("packs-app-picker");
  return picker && picker.value ? picker.value : null;
}

// Same "populate from list_apps, keep the user's choice across a refresh, otherwise default to
// the most recently created app" shape as app.js's refreshDoctorAppPicker.
async function refreshAppPicker() {
  const label = document.getElementById("packs-app-label");
  const picker = document.getElementById("packs-app-picker");
  let apps = [];
  try {
    apps = await kInvoke("list_apps");
  } catch {
    apps = [];
  }
  packsState.apps = apps;
  if (apps.length === 0) {
    label.hidden = true;
    picker.innerHTML = "";
    return;
  }
  const previous = picker.value;
  label.hidden = false;
  picker.innerHTML = apps.map((a) => `<option value="${pEsc(a.directory)}">${pEsc(a.name)}</option>`).join("");
  if (previous && apps.some((a) => a.directory === previous)) picker.value = previous;
  else picker.value = apps[apps.length - 1].directory;
}

async function refreshPacks() {
  const appDir = currentAppDir();
  document.getElementById("packs-why").hidden = true;
  packsState.selectedPackId = null;
  if (!appDir) {
    packsState.report = null;
    renderPacks();
    return;
  }
  packsStatus("reading npdev.lock…", false);
  try {
    packsState.report = await kInvoke("pack_list", { appDir });
    packsStatus("", false);
  } catch (error) {
    packsState.report = { status: "failed", error: String(error) };
    packsStatus(`unable to list packs: ${error}`, true);
  }
  renderPacks();
}

function initPacks() {
  document.getElementById("packs-refresh").addEventListener("click", async () => {
    await refreshAppPicker();
    await refreshPacks();
  });
  document.getElementById("packs-app-picker").addEventListener("change", refreshPacks);
  refreshAppPicker().then(refreshPacks);
}

window.__npdevInitPacks = initPacks;
window.__npdevRefreshPacks = refreshPacks;
