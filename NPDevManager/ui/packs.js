// Ready-made parts -- W3.2 (the pack list) reshaped by P3 (browse the catalog, add, update, trust).
//
// Thin pipe, same standing rule as the Verification tab: every decision lives in the CLI -- what is locked, what a
// signature or the temporary trust rule means (`pack list`, enriched by npdev_cli.py with `trustUntil` and
// `latestVersion`), what a catalog holds (`pack search`), and whether adding or updating worked (`pack add|update --json`,
// which also puts model.json and npdev.lock back when a part is refused or the app would stop validating). This file only
// words the answers for someone who has never heard of a lock, a digest or a signature -- those words never appear here.

const { invoke: kInvoke } = window.__TAURI__.core;

const packsState = {
  apps: [],
  report: null,
  selectedPackId: null,
  why: null,
  catalog: null,
  busy: false,
};

const T = (text, params) => window.NPDevI18n.tr(text, params);

function packsEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function packsLocale() {
  return window.NPDevI18n && window.NPDevI18n.lang === "pt" ? "pt-BR" : undefined;
}

// "2027-02-28" -> the person's own date format. Dates are shown, never parsed for a decision.
function packsDate(iso) {
  const when = new Date(/^\d{4}-\d{2}-\d{2}$/.test(String(iso)) ? `${iso}T12:00:00` : iso);
  return Number.isNaN(when.getTime()) ? String(iso || "") : when.toLocaleDateString(packsLocale());
}

// The status chip is drawn from what the lock already recorded (and the CLI's `trustUntil`); nothing is re-verified here.
function statusChip(entry) {
  const sig = entry.signature;
  if (!sig) return `<span class="pchip plain">${packsEsc(T("Part of your app"))}</span>`;
  if (sig.status === "verified") return `<span class="pchip ok">${packsEsc(T("Checked"))}</span>`;
  if (sig.status === "unsigned" && entry.trustUntil) {
    return `<span class="pchip ok">${packsEsc(T("From your catalog — accepted until {date}", { date: packsDate(entry.trustUntil) }))}</span>`;
  }
  return `<span class="pchip note">${packsEsc(T("Not checked"))}</span>`;
}

function deprecatedBadge(entry) {
  const dep = entry.deprecated;
  if (!dep) return "";
  const successor = dep.supersededBy
    ? ` — ${T("use {name} instead", { name: `${dep.supersededBy.pack} ${dep.supersededBy.version}` })}`
    : "";
  return `
    <div class="pdeprecated">
      ⚠ <b>${packsEsc(T("Replaced by a newer part"))}</b> (${packsEsc(packsDate(dep.since))}): ${packsEsc(dep.reason)}${packsEsc(successor)}
    </div>`;
}

function packRow(packId, entry) {
  const selected = packId === packsState.selectedPackId;
  const update = entry.latestVersion
    ? `<button class="btn" data-update="${packsEsc(packId)}">${packsEsc(T("Update to {v}", { v: entry.latestVersion }))}</button>`
    : "";
  return `
    <div class="prow${selected ? " selected" : ""}" data-pack-id="${packsEsc(packId)}">
      <div class="prow-main">
        <span class="pname">${packsEsc(packId)}</span>
        <span class="pversion">${packsEsc(entry.resolvedVersion || "?")}</span>
        ${statusChip(entry)}
        <span class="pactions">
          ${update}
          <button class="btn ghost" data-why="${packsEsc(packId)}">${packsEsc(T("Why is it here?"))}</button>
        </span>
      </div>
      ${deprecatedBadge(entry)}
    </div>`;
}

function renderPacks() {
  const list = document.getElementById("packs-list");
  const report = packsState.report;
  if (!report) {
    list.innerHTML = `<p class="status-line">${packsEsc(T("Pick an app above, then Refresh."))}</p>`;
    return;
  }
  if (report.status === "failed") {
    list.innerHTML = `<p class="status-line err">${packsEsc(report.error || T("The list of parts could not be read."))}</p>`;
    return;
  }
  const packs = report.packs || {};
  const ids = Object.keys(packs).sort();
  if (ids.length === 0) {
    list.innerHTML = `<p class="status-line">${packsEsc(T("This app has no ready-made parts yet. Add one below."))}</p>`;
    return;
  }
  list.innerHTML = ids.map((id) => packRow(id, packs[id])).join("");
  wirePackRows();
}

function wirePackRows() {
  const list = document.getElementById("packs-list");
  list.querySelectorAll("[data-why]").forEach((btn) =>
    btn.addEventListener("click", () => showWhy(btn.dataset.why)));
  list.querySelectorAll("[data-update]").forEach((btn) =>
    btn.addEventListener("click", () => changeParts(null, btn.dataset.update)));
}

async function showWhy(packId) {
  packsState.selectedPackId = packId;
  renderPacks();
  const box = document.getElementById("packs-why");
  box.hidden = false;
  box.innerHTML = `<p class="status-line">${packsEsc(T("Looking up why {name} is here…", { name: packId }))}</p>`;
  const appDir = packsCurrentAppDir();
  if (!appDir) return;
  try {
    const why = await kInvoke("pack_why", { appDir, packId });
    packsState.why = why;
    if (why.status !== "ok") {
      box.innerHTML = `<p class="status-line err">${packsEsc(why.error || T("Could not find out."))}</p>`;
      return;
    }
    const requiredBy = Array.isArray(why.requiredBy) ? why.requiredBy : [];
    box.innerHTML = `
      <h4>${packsEsc(T("Why {name} is here", { name: packId }))}</h4>
      ${requiredBy.length
        ? `<ul class="pwhy-list">${requiredBy.map((r) => `<li>${packsEsc(r)}</li>`).join("")}</ul>`
        : `<p class="status-line">${packsEsc(T("You added it yourself — no other part needs it."))}</p>`}`;
  } catch (error) {
    box.innerHTML = `<p class="status-line err">${packsEsc(T("Could not find out."))} ${packsEsc(error)}</p>`;
  }
}

function packsStatus(message, bad) {
  const el = document.getElementById("packs-status");
  el.textContent = message || "";
  el.style.color = bad ? "var(--err)" : "var(--muted)";
}

function packsCurrentAppDir() {
  const picker = document.getElementById("packs-app-picker");
  return picker && picker.value ? picker.value : null;
}

// ---- Find more: the catalog -----------------------------------------------------------------------------------------

function inThisApp(packId) {
  const packs = packsState.report && packsState.report.packs;
  return !!(packs && Object.prototype.hasOwnProperty.call(packs, packId));
}

// The catalog's description is the part author's own text (English, often several sentences about roles and bindings):
// the card shows its first sentence, the whole text is its tooltip, and it is data -- never translated.
function packsOneLine(description) {
  const text = String(description || "").replace(/^(Built-in )?NPDev [\w -]*?\b(pack|model|tables)\b:?\s*/, "");
  const first = text.split(". ")[0].replace(/\.$/, "");
  return first.length > 140 ? `${first.slice(0, 137).trimEnd()}…` : first;
}

function catalogCard(entry) {
  const added = inThisApp(entry.pack);
  const action = added
    ? `<span class="pchip plain">${packsEsc(T("Already in this app"))}</span>`
    : `<button class="btn" data-add="${packsEsc(entry.pack)}"${packsCurrentAppDir() ? "" : " disabled"}>${packsEsc(T("Add to this app"))}</button>`;
  return `
    <div class="pcard" data-card="${packsEsc(entry.pack)}">
      <div class="pcard-main">
        <span class="pname">${packsEsc(entry.pack)}</span>
        <span class="pversion">${packsEsc(entry.version || "")}</span>
        ${entry.category ? `<span class="pcat">${packsEsc(entry.category)}</span>` : ""}
        <span class="pactions">${action}</span>
      </div>
      <div class="pcard-desc" translate="no" title="${packsEsc(entry.description || "")}">${packsEsc(packsOneLine(entry.description))}</div>
    </div>`;
}

function renderCatalog() {
  const box = document.getElementById("packs-catalog");
  const note = document.getElementById("packs-catalog-note");
  const data = packsState.catalog;
  note.textContent = "";
  if (!data) {
    box.innerHTML = "";
    return;
  }
  if (data.error) {
    box.innerHTML = `<p class="status-line err">${packsEsc(T("The list of parts could not be loaded. Connect to the internet and try again."))}</p>`;
    return;
  }
  if (data.catalogStatus === "stale") {
    note.textContent = T("Showing the list saved on {date}. Connect to the internet to see new parts.",
      { date: packsDate(data.catalogFetchedAt) });
  }
  const results = Array.isArray(data.results) ? data.results : [];
  box.innerHTML = results.length
    ? results.map(catalogCard).join("")
    : `<p class="status-line">${packsEsc(T("No part matches that search."))}</p>`;
  box.querySelectorAll("[data-add]").forEach((btn) =>
    btn.addEventListener("click", () => changeParts(btn.dataset.add, null)));
}

async function searchCatalog() {
  const box = document.getElementById("packs-catalog");
  box.innerHTML = `<p class="status-line">${packsEsc(T("Loading the list of parts…"))}</p>`;
  const query = document.getElementById("packs-search").value.trim();
  try {
    packsState.catalog = await kInvoke("pack_search", { query });
  } catch (error) {
    packsState.catalog = { error: String(error) };
  }
  renderCatalog();
}

// ---- Add / update: one CLI call, one plain answer -------------------------------------------------------------------

function resultBox(kind, html) {
  const box = document.getElementById("packs-result");
  box.hidden = !html;
  box.className = kind || "";
  box.innerHTML = html || "";
}

function resultLines(doc, name, updating) {
  switch (doc.code) {
    case "DONE":
      return updating
        ? { ok: true, text: T("Your parts are up to date.") }
        : { ok: true, text: T("{name} was added to your app. Rebuild the app to start using it.", { name }) };
    case "ALREADY":
      return { ok: true, text: T("{name} is already in this app.", { name }) };
    case "UNTRUSTED":
      return { ok: false, text: T("This part could not be checked and does not come from your catalog. It was not added.") };
    case "OFFLINE":
      return { ok: false, text: T("NPDev could not reach the internet to get this part. Connect and try again. Nothing was changed.") };
    case "NOT_IN_CATALOG":
      return { ok: false, text: T("That part is not in the catalog.") };
    case "INVALID":
      return { ok: false, text: T("With {name} the app would stop working, so it was not added. Nothing was changed.", { name }) };
    case "NO_MODEL":
      return { ok: false, text: T("This app has no model.json to change.") };
    default:
      return { ok: false, text: T("NPDev could not finish. Nothing was changed.") };
  }
}

async function changeParts(addId, updateId) {
  const appDir = packsCurrentAppDir();
  if (!appDir || packsState.busy) return;
  const name = addId || updateId || "";
  const updating = !addId;
  packsState.busy = true;
  document.querySelectorAll("#screen-packs [data-add], #screen-packs [data-update]").forEach((b) => (b.disabled = true));
  resultBox("", `<p class="status-line">${packsEsc(updating
    ? T("Updating your parts… this can take a minute.")
    : T("Adding {name}… this can take a minute.", { name }))}</p>`);
  let doc;
  try {
    doc = await kInvoke("pack_change", { appDir, packId: addId || null });
  } catch (error) {
    doc = { ok: false, code: "FAILED", details: [String(error)] };
  }
  packsState.busy = false;
  const line = resultLines(doc, name, updating);
  const details = Array.isArray(doc.details) && doc.details.length && !line.ok
    ? `<details><summary>${packsEsc(T("Details"))}</summary>${doc.details.map((d) => `<div translate="no">${packsEsc(d)}</div>`).join("")}</details>`
    : "";
  const rebuild = line.ok && !updating && doc.code === "DONE"
    ? `<br><button class="btn" id="packs-rebuild">${packsEsc(T("Rebuild the app"))}</button>`
    : "";
  resultBox(line.ok ? "ok" : "bad", `${packsEsc(line.text)}${details}${rebuild}`);
  const rebuildBtn = document.getElementById("packs-rebuild");
  if (rebuildBtn) rebuildBtn.addEventListener("click", () => window.__npdevShowScreen("run"));
  await refreshPacks({ keepResult: true });
  renderCatalog();
}

// ---- the app picker and the list ------------------------------------------------------------------------------------

// Same "populate from list_apps, keep the user's choice across a refresh, otherwise default to
// the most recently created app" shape as app.js's refreshDoctorAppPicker.
async function packsRefreshAppPicker() {
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
  picker.innerHTML = apps.map((a) => `<option value="${packsEsc(a.directory)}">${packsEsc(a.name)}</option>`).join("");
  if (previous && apps.some((a) => a.directory === previous)) picker.value = previous;
  else picker.value = apps[apps.length - 1].directory;
}

async function refreshPacks(options) {
  const appDir = packsCurrentAppDir();
  document.getElementById("packs-why").hidden = true;
  if (!(options && options.keepResult)) resultBox("", "");
  packsState.selectedPackId = null;
  if (!appDir) {
    packsState.report = null;
    renderPacks();
    return;
  }
  packsStatus(T("Reading this app's parts…"), false);
  try {
    packsState.report = await kInvoke("pack_list", { appDir });
    packsStatus("", false);
  } catch (error) {
    packsState.report = { status: "failed", error: String(error) };
    packsStatus(T("The list of parts could not be read."), true);
  }
  renderPacks();
  if (packsState.catalog) renderCatalog();
}

function initPacks() {
  document.getElementById("packs-refresh").addEventListener("click", async () => {
    await packsRefreshAppPicker();
    await refreshPacks();
  });
  document.getElementById("packs-app-picker").addEventListener("change", () => refreshPacks());
  document.getElementById("packs-search-go").addEventListener("click", searchCatalog);
  document.getElementById("packs-search").addEventListener("keydown", (event) => {
    if (event.key === "Enter") searchCatalog();
  });
  packsRefreshAppPicker().then(() => refreshPacks()).then(searchCatalog);
}

window.__npdevInitPacks = initPacks;
// Entering the screen re-reads the app list too: an app made after the Manager started is not in the picker yet, and
// without it the workspace's chosen app cannot be put back (the screen would quietly stay on an older app).
window.__npdevRefreshPacks = async () => {
  await packsRefreshAppPicker();
  await refreshPacks();
};
