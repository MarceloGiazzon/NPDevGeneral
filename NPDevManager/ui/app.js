// NPDev Manager -- plain JS, no framework, no bundler (SPEC.md §1.1). Every screen is a thin
// renderer over what a Tauri command returns; the standing rule is that no decision worth making
// lives here that the CLI itself does not already make.

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// ---------------------------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------------------------

function showScreen(name) {
  document.querySelectorAll(".screen").forEach((s) => (s.hidden = s.id !== `screen-${name}`));
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.dataset.screen === name));
  // The Scrap Manager's app picker is fed from the Monitor's last scan, so it needs the scan to have
  // happened. Refreshing on entry rather than on a timer keeps a tab nobody is looking at from
  // probing the machine every 30 seconds.
  if (name === "scrap" && window.__npdevRefreshScrap) window.__npdevRefreshScrap();
  if (name === "monitor" && window.__npdevRefreshMonitor) window.__npdevRefreshMonitor();
}

// The Monitor's "Explore this app" button crosses screens, which is the one affordance that turns
// two tools into one flow (D9).
window.__npdevShowScreen = showScreen;

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => showScreen(tab.dataset.screen));
});

// ---------------------------------------------------------------------------------------------
// Stub-mode banner
// ---------------------------------------------------------------------------------------------

async function initFakeBanner() {
  const fake = await invoke("is_fake_mode");
  if (!fake) return;
  const banner = document.getElementById("fake-banner");
  banner.hidden = false;
  const picker = document.getElementById("fake-scenario-picker");
  const scenarios = await invoke("fake_doctor_scenarios");
  picker.innerHTML = scenarios.map((s) => `<option value="${s}">${s}</option>`).join("");
  picker.addEventListener("change", async () => {
    await invoke("set_fake_doctor_scenario", { name: picker.value });
    await loadDoctor();
  });
}

// ---------------------------------------------------------------------------------------------
// 1: Ready screen (M2)
// ---------------------------------------------------------------------------------------------

const CHECK_NAMES = {
  "java-present": "Java",
  "java-version": "Java 17+",
  "java-home-agreement": "JAVA_HOME",
  "python-version": "Python 3.9+",
  "git-present": "git",
  "disk-space": "Disk space",
  "runtimehost-jars": "NPDev jars",
  "ai-knowledge-index": "AI knowledge index",
  "docker-present": "Docker",
  "pwsh-present": "PowerShell 7",
  // W5.3 requirement 3: the database checks sit on the Ready screen beside the Java/Python ones.
  // They only appear when doctor found an app to check -- a machine with no NPDev app on it is not
  // a broken machine, and rows that would always read "n/a" are noise.
  "database-engine-support": "Database engine",
  "database-reachable": "Database reachable",
  "database-credentials": "Database credentials",
  "database-exists": "Database exists",
  "database-privileges": "Database privileges",
  "database-charset": "Database charset",
};

function markFor(status) {
  if (status === "pass") return "✓"; // tick
  if (status === "fail") return "✗"; // cross
  return "!";
}

// A check whose `expected` says "n/a" was answered by an engine that has no server to answer about
// -- H2Local has nothing to reach, authenticate against, or mis-encode. The CLI deliberately returns
// these as PASSES rather than omitting them (a row that vanishes reads as "not checked"), and the
// stabilize plan's M15 asks that an H2 user not be shown five checks about a database they do not
// have. Both are right, so the row stays and is drawn muted, saying n/a in words: nothing vanishes,
// and nothing is dressed up as a verification that happened.
function isNotApplicable(check) {
  return typeof check.expected === "string" && check.expected.startsWith("n/a");
}

// ONE renderer for both surfaces (Ready's doctor rows and M13's Test-connection rows). The records
// are the same shape with the same ids on purpose -- two renderers would be free to describe the
// same database differently, which is the exact defect the shared CLI code path exists to prevent.
function renderCheckRows(container, checks) {
  container.innerHTML = "";
  for (const check of checks || []) {
    const row = document.createElement("div");
    const na = isNotApplicable(check);
    row.className = `check-row ${check.status}${na ? " na" : ""}`;
    const naText = `<span class="found">n/a -- ${escapeHtml(check.expected.replace(/^n\/a\s*/, "") || "nothing to check")}</span>`;
    const found = check.found ? `<span class="found">${escapeHtml(check.found)}</span>` : "";
    const detail = check.detail ? `<span class="detail" title="${escapeHtml(check.detail)}">${escapeHtml(check.detail)}</span>` : found;
    const fixBtn =
      check.status === "fail" && check.fixCommand
        ? `<button class="fix-btn" data-fix="${escapeHtml(check.fixCommand)}">Fix this</button>`
        : "";
    row.innerHTML = `
      <span class="mark">${na ? "–" : markFor(check.status)}</span>
      <span class="name">${escapeHtml(CHECK_NAMES[check.id] || check.name)}</span>
      ${na ? naText : detail}
      ${fixBtn}
    `;
    container.appendChild(row);
  }
  container.querySelectorAll("[data-fix]").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (btn.dataset.fix === "npdev setup") {
        showScreen("install");
      }
    });
  });
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text == null ? "" : String(text);
  return div.innerHTML;
}

// M15: which app's database Ready reports on. Populated from the Manager's own app list; hidden
// entirely when there are no apps yet, because then there is genuinely nothing to choose and an
// empty dropdown would imply something is missing.
async function refreshDoctorAppPicker() {
  const label = document.getElementById("doctor-app-label");
  const picker = document.getElementById("doctor-app-picker");
  let apps = [];
  try {
    apps = await invoke("list_apps");
  } catch {
    apps = [];
  }
  if (apps.length === 0) {
    label.hidden = true;
    picker.innerHTML = "";
    return;
  }
  const previous = picker.value;
  label.hidden = false;
  picker.innerHTML = apps
    .map((a) => `<option value="${escapeHtml(a.directory)}">${escapeHtml(a.name)}</option>`)
    .join("");
  // Keep the user's choice across a re-check; otherwise default to the most recently created app,
  // which is overwhelmingly the one they are working on.
  if (previous && apps.some((a) => a.directory === previous)) picker.value = previous;
  else picker.value = apps[apps.length - 1].directory;
}

async function loadDoctor() {
  const container = document.getElementById("doctor-rows");
  container.innerHTML = `<p class="status-line">running doctor&hellip;</p>`;
  const picker = document.getElementById("doctor-app-picker");
  const appDir = picker && picker.value ? picker.value : null;
  try {
    const result = await invoke("check_doctor", { appDir });
    renderCheckRows(container, result.checks);
  } catch (err) {
    container.innerHTML = `<p class="status-line">could not run doctor: ${escapeHtml(err)}</p>`;
  }
}

document.getElementById("doctor-refresh").addEventListener("click", loadDoctor);
document.getElementById("doctor-app-picker").addEventListener("change", loadDoctor);

// ---------------------------------------------------------------------------------------------
// 2: Install screen (M3/M4)
// ---------------------------------------------------------------------------------------------

async function refreshJdkStatus() {
  const status = await invoke("jdk_status");
  const el = document.getElementById("jdk-status");
  el.textContent = status.installed ? `installed at ${status.path}` : "not installed";
  document.getElementById("jdk-install-btn").disabled = status.installed;
}

document.getElementById("jdk-install-btn").addEventListener("click", async () => {
  const btn = document.getElementById("jdk-install-btn");
  const bar = document.getElementById("jdk-progress");
  btn.disabled = true;
  bar.hidden = false;
  bar.removeAttribute("value");
  try {
    await invoke("install_jdk");
    await refreshJdkStatus();
    await loadDoctor();
  } catch (err) {
    document.getElementById("jdk-status").textContent = `failed: ${err}`;
    btn.disabled = false;
  } finally {
    bar.hidden = true;
  }
});

listen("jdk-progress", (event) => {
  const bar = document.getElementById("jdk-progress");
  const { downloaded, total } = event.payload;
  if (total) {
    bar.max = total;
    bar.value = downloaded;
  } else {
    bar.removeAttribute("value");
  }
});

async function refreshPythonStatus() {
  const status = await invoke("python_status");
  const el = document.getElementById("python-status");
  if (status.systemPython) {
    el.textContent = `using system Python: ${status.systemPython}`;
    document.getElementById("python-install-btn").disabled = true;
  } else if (status.portableInstalled) {
    el.textContent = "private Python installed";
    document.getElementById("python-install-btn").disabled = true;
  } else {
    el.textContent = "no Python found";
    document.getElementById("python-install-btn").disabled = false;
  }
}

document.getElementById("python-install-btn").addEventListener("click", async () => {
  const btn = document.getElementById("python-install-btn");
  const bar = document.getElementById("python-progress");
  btn.disabled = true;
  bar.hidden = false;
  bar.removeAttribute("value");
  try {
    await invoke("install_python");
    await refreshPythonStatus();
  } catch (err) {
    document.getElementById("python-status").textContent = `failed: ${err}`;
    btn.disabled = false;
  } finally {
    bar.hidden = true;
  }
});

listen("python-progress", (event) => {
  const bar = document.getElementById("python-progress");
  const { downloaded, total } = event.payload;
  if (total) {
    bar.max = total;
    bar.value = downloaded;
  } else {
    bar.removeAttribute("value");
  }
});

async function refreshTagList(forceRefresh) {
  const picker = document.getElementById("tag-picker");
  picker.innerHTML = `<option>loading&hellip;</option>`;
  try {
    const tags = await invoke("list_tags", { forceRefresh: !!forceRefresh });
    picker.innerHTML = tags.map((t) => `<option value="${t.name}">${t.name} (${t.committed_at.slice(0, 10)})</option>`).join("");
  } catch (err) {
    picker.innerHTML = `<option>could not load: ${err}</option>`;
  }
}

document.getElementById("tag-refresh-btn").addEventListener("click", () => refreshTagList(true));

document.getElementById("version-install-btn").addEventListener("click", async () => {
  const picker = document.getElementById("tag-picker");
  const tag = picker.value;
  if (!tag) return;
  const btn = document.getElementById("version-install-btn");
  const bar = document.getElementById("version-progress");
  btn.disabled = true;
  bar.hidden = false;
  bar.removeAttribute("value");
  try {
    await invoke("install_npdev_version", { tag });
    await refreshVersionsScreen();
  } catch (err) {
    alert(`could not install ${tag}: ${err}`);
  } finally {
    btn.disabled = false;
    bar.hidden = true;
  }
});

listen("version-install-progress", (event) => {
  const bar = document.getElementById("version-progress");
  const { downloaded, total } = event.payload;
  if (total) {
    bar.max = total;
    bar.value = downloaded;
  } else {
    bar.removeAttribute("value");
  }
});

document.getElementById("setup-run-btn").addEventListener("click", async () => {
  const btn = document.getElementById("setup-run-btn");
  const statusEl = document.getElementById("setup-status");
  const logEl = document.getElementById("setup-log");
  btn.disabled = true;
  logEl.hidden = false;
  logEl.textContent = "";
  statusEl.textContent = "running…";
  try {
    const result = await invoke("run_setup");
    const source = result.jarsSource === "download" ? "downloaded (fast)" : "built locally (slow)";
    statusEl.textContent = `done -- jars ${source}`;
  } catch (err) {
    statusEl.textContent = `failed: ${err}`;
  } finally {
    btn.disabled = false;
  }
});

listen("setup-event", (event) => {
  const payload = event.payload;
  const logEl = document.getElementById("setup-log");
  logEl.hidden = false;
  const line = payload.phase ? `[${payload.phase}] ${payload.status}${payload.seconds ? ` (${payload.seconds}s)` : ""}` : JSON.stringify(payload);
  logEl.textContent += line + "\n";
  logEl.scrollTop = logEl.scrollHeight;
});

// ---------------------------------------------------------------------------------------------
// 3: Apps screen (M5)
// ---------------------------------------------------------------------------------------------

async function refreshAppList() {
  const apps = await invoke("list_apps");
  const container = document.getElementById("app-list");
  if (apps.length === 0) {
    container.innerHTML = `<p class="status-line">no apps yet -- create one below.</p>`;
    return;
  }
  container.innerHTML = apps
    .map(
      (a) => `
      <div class="app-item">
        <span>${escapeHtml(a.name)} <span class="found">${escapeHtml(a.directory)}</span></span>
        <button data-open="${escapeHtml(a.directory)}">Open folder</button>
      </div>`
    )
    .join("");
  container.querySelectorAll("[data-open]").forEach((btn) => {
    btn.addEventListener("click", () => invoke("open_folder", { path: btn.dataset.open }));
  });
}

// ---------------------------------------------------------------------------------------------
// M5 + W5.3: the engine picker.
//
// Every option, default port, summary and warning below comes from `npdev engines --json`. Nothing
// about engines is written in this file, deliberately: the Manager's job is to be a window onto the
// CLI, and a hardcoded list here would be a second source of truth that goes stale exactly when it
// matters -- the day an engine stops (or starts) being supported.
// ---------------------------------------------------------------------------------------------

let engineCatalog = [];

async function loadEngines() {
  const select = document.getElementById("new-app-engine");
  try {
    const listing = await invoke("list_engines");
    engineCatalog = listing.engines || [];
  } catch (err) {
    // No installed NPDev version yet is the ordinary case on a fresh machine. Say so instead of
    // silently offering an empty dropdown, which reads as "this app has no database options".
    select.innerHTML = `<option value="">(install an NPDev version first)</option>`;
    document.getElementById("new-app-engine-summary").textContent = String(err);
    return;
  }
  select.innerHTML = engineCatalog
    .map(
      (e) =>
        `<option value="${escapeHtml(e.key)}">${escapeHtml(e.externalName)}${
          e.status === "supported" ? "" : "  (experimental)"
        }</option>`
    )
    .join("");
  applyEngineSelection();
}

function applyEngineSelection() {
  const key = document.getElementById("new-app-engine").value;
  const engine = engineCatalog.find((e) => e.key === key);
  const summaryEl = document.getElementById("new-app-engine-summary");
  const warningEl = document.getElementById("new-app-engine-warning");
  const connectionEl = document.getElementById("new-app-connection");
  if (!engine) {
    summaryEl.textContent = "";
    warningEl.hidden = true;
    connectionEl.hidden = true;
    return;
  }
  summaryEl.textContent = engine.summary || "";
  // The honesty notice is composed by the CLI, not here, so the wording a user sees in the Manager
  // and the wording they see in a terminal are the same sentence.
  warningEl.textContent = engine.honestyNotice || "";
  warningEl.hidden = !engine.honestyNotice;
  connectionEl.hidden = !engine.needsServer;
  if (engine.needsServer) {
    const portEl = document.getElementById("new-app-db-port");
    portEl.placeholder = String(engine.defaultPort || "");
  }
}

document.getElementById("new-app-engine").addEventListener("change", () => {
  applyEngineSelection();
  // Any previous verdict is about the PREVIOUS engine. Leaving a green result on screen after the
  // engine changes would be the worst kind of stale: reassuring, and about something else.
  document.getElementById("test-connection-rows").innerHTML = "";
});

// ---------------------------------------------------------------------------------------------
// M13: Test connection.
//
// Renders through renderCheckRows -- the same function the Ready screen uses, over the same check
// ids -- because the CLI answers both with one code path. If this button and Ready ever disagreed
// about one database, the disagreement would be the bug.
// ---------------------------------------------------------------------------------------------

document.getElementById("test-connection-btn").addEventListener("click", async () => {
  const btn = document.getElementById("test-connection-btn");
  const rows = document.getElementById("test-connection-rows");
  const engine = document.getElementById("new-app-engine").value;
  if (!engine) {
    rows.innerHTML = `<p class="status-line">pick a database first.</p>`;
    return;
  }
  const portRaw = document.getElementById("new-app-db-port").value.trim();
  btn.disabled = true;
  rows.innerHTML = `<p class="status-line">testing&hellip;</p>`;
  try {
    const result = await invoke("test_connection", {
      engine,
      dbHost: document.getElementById("new-app-db-host").value.trim() || null,
      dbPort: portRaw ? parseInt(portRaw, 10) : null,
      dbUser: document.getElementById("new-app-db-user").value.trim() || null,
      dbPassword: document.getElementById("new-app-db-password").value || null,
    });
    // Same reasoning as the toolbox below: a refusal (an unknown engine, say) arrives as an
    // npdev-cli-result.v1 error object with no `checks`, and rendering an empty list under
    // "NOT usable" would hide the one sentence that says why.
    if (!result.checks && result.error && result.error.message) {
      rows.innerHTML = `<p class="status-line">${escapeHtml(result.error.message)}</p>`;
      return;
    }
    renderCheckRows(rows, result.checks);
    const verdict = document.createElement("p");
    verdict.className = "status-line";
    verdict.textContent = result.ok
      ? "Connection usable."
      : "This connection is NOT usable yet -- see the rows above.";
    rows.appendChild(verdict);
  } catch (err) {
    rows.innerHTML = `<p class="status-line">could not test: ${escapeHtml(err)}</p>`;
  } finally {
    btn.disabled = false;
  }
});

document.getElementById("new-app-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const name = document.getElementById("new-app-name").value.trim();
  const parentDir = document.getElementById("new-app-parent").value.trim();
  const engine = document.getElementById("new-app-engine").value || null;
  const statusEl = document.getElementById("new-app-status");
  const portRaw = document.getElementById("new-app-db-port").value.trim();
  statusEl.textContent = "creating…";
  try {
    await invoke("create_app", {
      name,
      parentDir,
      engine,
      // Blank fields are sent as null rather than "" so the CLI's own per-engine defaults stay in
      // charge -- an empty string would override a good default with nothing.
      dbHost: document.getElementById("new-app-db-host").value.trim() || null,
      dbPort: portRaw ? parseInt(portRaw, 10) : null,
      dbUser: document.getElementById("new-app-db-user").value.trim() || null,
      dbPassword: document.getElementById("new-app-db-password").value || null,
      // STOR-15. Sent unconditionally as a real boolean, never omitted-when-false: `false` IS the
      // answer for a server NPDev provisioned, and it is the answer that arms Reset. A field that
      // vanished when unticked would make "the user said no" and "the UI never asked"
      // indistinguishable at the CLI -- which is the state this item exists to end.
      externallyProvisioned:
        document.getElementById("new-app-externally-provisioned").checked === true,
    });
    statusEl.textContent = "created.";
    await refreshAppList();
    // M15: the new app becomes selectable on Ready immediately -- the moment its database checks
    // are most worth looking at is right after it is created.
    await refreshDoctorAppPicker();
  } catch (err) {
    statusEl.textContent = `failed: ${err}`;
  }
});

// ---------------------------------------------------------------------------------------------
// 4: Run screen (M6)
// ---------------------------------------------------------------------------------------------

let devRunning = false;

document.getElementById("run-start-btn").addEventListener("click", async () => {
  const appDir = document.getElementById("run-app-dir").value.trim();
  const port = parseInt(document.getElementById("run-port").value, 10) || 8080;
  if (!appDir) {
    alert("pick an app folder first");
    return;
  }
  const logEl = document.getElementById("run-log");
  logEl.textContent = "";
  document.getElementById("run-banner").hidden = true;
  document.getElementById("run-link").textContent = `http://localhost:${port}`;
  document.getElementById("run-link").href = `http://localhost:${port}`;
  try {
    await invoke("start_dev", { appDir, port });
    devRunning = true;
    document.getElementById("run-start-btn").disabled = true;
    document.getElementById("run-stop-btn").disabled = false;
  } catch (err) {
    alert(`could not start: ${err}`);
  }
});

document.getElementById("run-stop-btn").addEventListener("click", async () => {
  await invoke("stop_dev");
  devRunning = false;
  document.getElementById("run-start-btn").disabled = false;
  document.getElementById("run-stop-btn").disabled = true;
});

// ---------------------------------------------------------------------------------------------
// M14: the database toolbox.
//
// Every button runs `npdev db <operation>`, which runs the app's OWN generated `_ops` script. The
// parity work (E15) made those five byte-identical across Postgres, MySQL and SQL Server by having
// them branch on `profile.kind`; reimplementing any of them here would create a new twin free to
// drift from the scripts a terminal user runs.
// ---------------------------------------------------------------------------------------------

const DB_RESET_CONFIRMATION = "I_UNDERSTAND_DB_DATA_WILL_BE_DELETED";

async function runDbOperation(operation, confirm) {
  const appDir = document.getElementById("run-app-dir").value.trim();
  const out = document.getElementById("db-output");
  if (!appDir) {
    out.hidden = false;
    out.textContent = "pick an app folder first.";
    return;
  }
  out.hidden = false;
  out.textContent = `${operation}…`;
  try {
    const result = await invoke("db_operation", { appDir, operation, confirm: confirm || null });
    // The script's own words, verbatim. Re-describing what it did would be a second account of one
    // event, and the script is the one that was actually there.
    //
    // `error.message` is not a fallback afterthought: when the CLI refuses (no _ops toolbox yet, no
    // PowerShell, reset without the token) it emits an npdev-cli-result.v1 object that carries the
    // reason THERE and has no `output` at all. Reading only `output` would replace a precise,
    // actionable sentence with "(no output)" -- the silent-answer defect, reintroduced one layer up
    // from where the CLI took care to prevent it.
    out.textContent =
      result.output || (result.error && result.error.message) || (result.ok ? "(done)" : "(no output)");
  } catch (err) {
    out.textContent = String(err);
  }
}

for (const [id, operation] of [
  ["db-start-btn", "start"],
  ["db-stop-btn", "stop"],
  ["db-status-btn", "status"],
  ["db-connection-btn", "connection"],
]) {
  document.getElementById(id).addEventListener("click", () => runDbOperation(operation));
}

document.getElementById("db-reset-btn").addEventListener("click", async () => {
  // Reset DELETES the data. The CLI and the generated script both require an acknowledgement token;
  // the window must be at least as careful as the terminal, so the confirmation is explicit rather
  // than a one-click "are you sure" that reflexes straight through.
  const appDir = document.getElementById("run-app-dir").value.trim();
  if (!appDir) {
    const out = document.getElementById("db-output");
    out.hidden = false;
    out.textContent = "pick an app folder first.";
    return;
  }
  const answer = prompt(
    `This DELETES all data in this app's database and removes its container.\n\n` +
      `${appDir}\n\nThis cannot be undone. To confirm, type:\n${DB_RESET_CONFIRMATION}`
  );
  if (answer !== DB_RESET_CONFIRMATION) {
    const out = document.getElementById("db-output");
    out.hidden = false;
    out.textContent = "Reset cancelled -- nothing was deleted.";
    return;
  }
  await runDbOperation("reset", DB_RESET_CONFIRMATION);
});

document.getElementById("run-link").addEventListener("click", (event) => {
  event.preventDefault();
  const href = event.target.href;
  if (href && href !== "#") invoke("open_url", { url: href });
});

listen("dev-event", (event) => {
  const payload = event.payload;
  const logEl = document.getElementById("run-log");
  const banner = document.getElementById("run-banner");

  let line;
  switch (payload.kind) {
    case "changed":
      line = `changed: ${(payload.files || []).join(", ")}`;
      break;
    case "phase":
      line = `${payload.phase} …`;
      break;
    case "result":
      line = `  -> ${payload.result}`;
      if (payload.result === "FAILED") {
        banner.hidden = false;
        banner.className = "run-banner";
        banner.textContent = "The last save had a problem. The app you already had running is still live -- fix the model and save again.";
      } else if (payload.result === "ok") {
        banner.hidden = true;
      }
      break;
    case "classification":
      line = payload.metadataOnly ? "metadata-only change -- fast path" : "structural change -- full rebuild";
      break;
    case "diagnostics":
      line = `${payload.errorCount} error(s) -- see the banner above`;
      break;
    case "ready":
      line = `ready in ${payload.seconds}s${payload.metadataOnly ? " (fast path)" : ""}`;
      banner.hidden = true;
      break;
    case "stopped":
      line = "stopped.";
      devRunning = false;
      document.getElementById("run-start-btn").disabled = false;
      document.getElementById("run-stop-btn").disabled = true;
      break;
    default:
      line = JSON.stringify(payload);
  }
  logEl.textContent += line + "\n";
  logEl.scrollTop = logEl.scrollHeight;
});

// ---------------------------------------------------------------------------------------------
// 5: Versions screen (M7)
// ---------------------------------------------------------------------------------------------

async function refreshVersionsScreen() {
  const [installed, current] = await Promise.all([invoke("list_installed_versions"), invoke("current_version")]);
  const container = document.getElementById("version-list");
  if (installed.length === 0) {
    container.innerHTML = `<p class="status-line">no versions installed yet -- see the Install tab.</p>`;
    return;
  }
  container.innerHTML = installed
    .map((v) => {
      const isCurrent = v.tag === current;
      return `
      <div class="version-item ${isCurrent ? "current" : ""}">
        <span>${escapeHtml(v.tag)}${isCurrent ? '<span class="badge">current</span>' : ""}</span>
        <span>
          ${isCurrent ? "" : `<button data-use="${escapeHtml(v.tag)}">Use this version</button>`}
          <button data-remove="${escapeHtml(v.tag)}" ${isCurrent ? "disabled" : ""}>Remove</button>
        </span>
      </div>`;
    })
    .join("");
  container.querySelectorAll("[data-use]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      await invoke("set_current_version", { tag: btn.dataset.use });
      await refreshVersionsScreen();
    });
  });
  container.querySelectorAll("[data-remove]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      if (!confirm(`Remove ${btn.dataset.remove}? This deletes its folder.`)) return;
      try {
        await invoke("remove_installed_version", { tag: btn.dataset.remove });
        await refreshVersionsScreen();
      } catch (err) {
        alert(err);
      }
    });
  });
}

// ---------------------------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------------------------

(async function init() {
  await initFakeBanner();
  showScreen("ready");
  // Before loadDoctor: the picker's value is what tells doctor which app's database to check, so
  // populating it afterwards would make the first render the one WITHOUT database rows.
  await refreshDoctorAppPicker();
  await loadDoctor();
  await refreshJdkStatus();
  await refreshPythonStatus();
  await refreshTagList(false);
  await refreshAppList();
  await loadEngines();
  await refreshVersionsScreen();
})();
