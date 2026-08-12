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
  // Same reasoning as the Scrap Manager: the Prompter's app picker is fed from the Monitor's last
  // scan, and its provider list can be edited from its own modal, so both are re-read on entry.
  if (name === "prompter" && window.__npdevRefreshPrompter) window.__npdevRefreshPrompter();
  // The five original screens had NO on-entry refresh at all -- everything they showed was whatever
  // init() found at launch. That is how the Install tab could report "not run yet" after a setup run
  // and "no Python found" after installing one: the facts changed, the window did not re-ask. These
  // two screens are the ones whose truth changes WHILE the Manager is open (a setup run, a version
  // switch, a new monitored path), so they re-ask on entry like the three newer screens do.
  if (name === "install" && window.__npdevRefreshInstall) window.__npdevRefreshInstall();
  if (name === "run" && window.__npdevRefreshRun) window.__npdevRefreshRun();
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

// ---------------------------------------------------------------------------------------------
// The supported-engines panel.
//
// Doctor's six database rows are app-scoped -- they report the ONE engine the selected app declares,
// and for an H2 app the other five read "n/a for an engine with no server". Correct, and the reason
// a user could ask "why do I only ever see H2 information?": nothing in this window had ever listed
// what the PLATFORM supports. This panel does, with no app created yet.
//
// Every row is `npdev engines --json`, including the caveats -- which the CLI has always returned
// and this UI ignored until now. A caveat is not a footnote: "MySQL commits implicitly on DDL, so a
// failed migration cannot be rolled back" is the difference between two engines this screen would
// otherwise present as interchangeable.
// ---------------------------------------------------------------------------------------------

function engineShape(engine) {
  if (engine.needsServer) {
    return engine.defaultPort ? `server, default port ${engine.defaultPort}` : "server";
  }
  return "file/embedded, no server";
}

async function refreshEngineMatrix() {
  const container = document.getElementById("engine-matrix");
  container.innerHTML = `<p class="status-line">reading the engine list&hellip;</p>`;
  // One call feeds both surfaces: this panel and the New-app dropdown. Asking twice would let them
  // disagree about the same machine for as long as one answer is newer than the other.
  await loadEngines();
  if (engineLoadError) {
    container.innerHTML = `<p class="status-line">could not list the engines: ${escapeHtml(engineLoadError)}</p>`;
    return;
  }
  if (engineCatalog.length === 0) {
    container.innerHTML = `<p class="status-line">the installed NPDev version reported no engines.</p>`;
    return;
  }
  container.innerHTML = engineCatalog
    .map((engine) => {
      const supported = engine.status === "supported";
      const since = engine.supportedSince ? ` since ${escapeHtml(engine.supportedSince)}` : "";
      const caveat = engine.caveat
        ? `<span class="engine-caveat">${escapeHtml(engine.caveat)}</span>`
        : "";
      return `
      <div class="check-row ${supported ? "pass" : "warn"} engine-row">
        <span class="mark">${supported ? "✓" : "!"}</span>
        <span class="name">${escapeHtml(engine.externalName)}</span>
        <span class="engine-summary">
          <span class="engine-shape">${escapeHtml(engine.status)}${since} &middot; ${escapeHtml(engineShape(engine))}</span>
          <span class="engine-line">${escapeHtml(engine.summary || "")}</span>
          ${caveat}
        </span>
      </div>`;
    })
    .join("");
}

document.getElementById("doctor-refresh").addEventListener("click", async () => {
  await loadDoctor();
  // Re-check means re-check: the engine list comes from the installed CLI, which the user may have
  // just changed on the Versions tab.
  await refreshEngineMatrix();
});
document.getElementById("doctor-app-picker").addEventListener("change", loadDoctor);

// ---------------------------------------------------------------------------------------------
// 2: Install screen (M3/M4)
// ---------------------------------------------------------------------------------------------

// A system Java that already works is reported as what it is, and the private download becomes an
// OPTION rather than the path. The Manager never records the system Java anywhere: when
// `manager.jdk_home` is unset the CLI resolves JAVA_HOME/PATH itself, which is the proven fallback
// -- so this is reporting and button gating, and deliberately nothing else.
async function refreshJdkStatus() {
  const status = await invoke("jdk_status");
  const el = document.getElementById("jdk-status");
  const btn = document.getElementById("jdk-install-btn");
  if (status.resolved === "portable") {
    el.textContent = `private JDK installed at ${status.path}`;
    btn.textContent = "Reinstall private JDK";
  } else if (status.resolved === "system") {
    el.textContent = `using system Java ${status.systemJavaVersion} at ${status.systemJava}`;
    // Enabled on purpose: wanting a private copy anyway is a real choice (a pinned JDK the machine's
    // own Java cannot drift out from under), and the Manager's whole thesis is that it never has to
    // touch the system one either way.
    btn.textContent = "Install private JDK anyway (optional)";
  } else {
    el.textContent = "not installed -- no Java 17+ found on this machine";
    btn.textContent = "Install private JDK";
  }
  btn.disabled = false;
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

// Same rule as Java above: a found system Python is used, and the private download stays available
// rather than being disabled. Disabling it made the one thing this step can do unreachable for
// anybody who wanted a pinned copy, and said nothing about why.
async function refreshPythonStatus() {
  const status = await invoke("python_status");
  const el = document.getElementById("python-status");
  const btn = document.getElementById("python-install-btn");
  if (status.systemPython) {
    el.textContent = `using system Python: ${status.systemPython}`;
    btn.textContent = "Install private Python anyway (optional)";
  } else if (status.portableInstalled) {
    el.textContent = "private Python installed";
    btn.textContent = "Reinstall private Python";
  } else {
    el.textContent = "no Python found";
    btn.textContent = "Install private Python";
  }
  btn.disabled = false;
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

// The last tag this window downloaded, so the step-3 line can say whether it became the current
// one. Deliberately not persisted: it is about what just happened in front of the user.
let lastDownloadedTag = null;

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
    lastDownloadedTag = tag;
    await refreshVersionsScreen();
    // The engine list is answered by the installed CLI, and `loadEngines()` used to run only from
    // init() -- so on a fresh machine the New-app dropdown said "(install an NPDev version first)"
    // forever after a version WAS installed, until the Manager was restarted.
    await refreshEngineMatrix();
    // Setup's status names the CURRENT version, which this download may or may not have changed.
    await refreshSetupStatus();
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

// The honest answer to "has setup run?", asked of the machine rather than remembered by this window.
//
// What was here before: an HTML literal reading "not run yet" that ONLY the click handler below ever
// rewrote. So a Manager whose jars were staged months ago said "not run yet" on every launch, and a
// Manager whose staged jars had since been deleted still said "done". There were no criteria at all
// -- these are the criteria, and they are doctor's own two checks (`runtimehost-jars`,
// `ai-knowledge-index`) rather than a second opinion about the same machine.
async function refreshSetupStatus() {
  const statusEl = document.getElementById("setup-status");
  const btn = document.getElementById("setup-run-btn");
  let status;
  try {
    status = await invoke("setup_status");
  } catch (err) {
    statusEl.textContent = `could not read the setup status: ${err}`;
    return;
  }
  renderVersionHint(status.currentVersion);
  if (!status.currentVersion) {
    // Pressing Run setup here fails with the CLI resolver's raw string ("no NPDev version installed
    // -- install one first"). Saying which step to do instead, and disabling the button, is the same
    // information delivered before the failure rather than after it.
    statusEl.textContent = "no NPDev version installed -- do step 3 first.";
    btn.disabled = true;
    return;
  }
  btn.disabled = false;
  if (status.jarsStaged && status.aiIndexPresent) {
    statusEl.textContent =
      `ready -- ${status.jarCount} jar(s) staged for ${status.currentVersion}, in ${status.libsDir} ` +
      `(shared across versions).`;
  } else if (status.jarsStaged) {
    statusEl.textContent =
      `jars staged (${status.jarCount}) for ${status.currentVersion}; the AI knowledge index is ` +
      `missing -- Run setup to rebuild it. (It is needed by the MCP tools, not by generate/build/run.)`;
  } else {
    statusEl.textContent = `not run yet -- Run setup stages the runtime jars into ${status.libsDir}.`;
  }
}

// Step 3's own line. `install_npdev_version` deliberately does not switch `current_version` when one
// is already set, so downloading a newer tag and then pressing Run setup sets up the OLD version.
// The Versions tab owns switching; this only stops the difference being invisible.
function renderVersionHint(currentVersion) {
  const el = document.getElementById("version-status");
  if (!el) return;
  if (!currentVersion) {
    el.textContent = lastDownloadedTag
      ? `downloaded ${lastDownloadedTag}.`
      : "no version installed yet.";
    return;
  }
  if (lastDownloadedTag && lastDownloadedTag !== currentVersion) {
    el.textContent =
      `downloaded ${lastDownloadedTag}; the current version is still ${currentVersion} -- ` +
      `switch on the Versions tab, or setup and every app will use ${currentVersion}.`;
  } else {
    el.textContent = `current version: ${currentVersion}.`;
  }
}

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
    logEl.textContent += `done -- jars ${source}\n`;
  } catch (err) {
    statusEl.textContent = `failed: ${err}`;
    // The run's own account goes to the log as well as the status line, because the line below is
    // about to be replaced by what is actually on disk NOW. The two answer different questions --
    // "what did this run do" and "what is staged" -- and only the second survives a restart, which
    // is the entire point of this section. Neither is allowed to overwrite the other silently.
    logEl.textContent += `failed: ${err}\n`;
  } finally {
    btn.disabled = false;
    await refreshSetupStatus();
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
// Install step 5: Monitored App Paths.
//
// One writable editor for a list two other screens consume (the Monitor scans these folders, the
// Run tab offers the apps they contain). It used to live inside the Monitor only, which made "where
// do my apps live" -- machine configuration, answered once -- something you had to open a wall of
// running apps to change.
//
// The same three commands the Monitor used: `get_inspect_paths`, `set_inspect_paths`,
// `pick_inspect_folders`. The stored field keeps its name; renaming it would orphan every existing
// manager.json for the sake of a label.
// ---------------------------------------------------------------------------------------------

let monitoredPaths = [];

async function refreshAppPaths() {
  try {
    monitoredPaths = await invoke("get_inspect_paths");
  } catch (err) {
    document.getElementById("apppaths-status").textContent = `could not read the saved paths: ${err}`;
    return;
  }
  renderAppPaths();
}

function renderAppPaths() {
  const holder = document.getElementById("apppaths-chips");
  holder.innerHTML = monitoredPaths
    .map(
      (p, i) =>
        `<span class="path-chip">${escapeHtml(p)}<button class="path-chip-x" data-rm="${i}" title="Remove">✕</button></span>`
    )
    .join("");
  holder.querySelectorAll("[data-rm]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      monitoredPaths.splice(Number(btn.dataset.rm), 1);
      await persistAppPaths();
    });
  });
  const status = document.getElementById("apppaths-status");
  status.textContent = monitoredPaths.length
    ? `${monitoredPaths.length} folder(s) monitored.`
    : "none yet -- the Monitor and the Run tab will show only apps this Manager created.";
}

// Every mutation persists immediately: a list that is only saved on some later action is a list
// that is one crash away from being wrong, and this one decides what two other screens can see.
async function persistAppPaths() {
  try {
    await invoke("set_inspect_paths", { paths: monitoredPaths });
  } catch (err) {
    document.getElementById("apppaths-status").textContent = `could not save: ${err}`;
    return;
  }
  renderAppPaths();
}

async function addAppPaths(paths) {
  const fresh = (paths || []).map((p) => (p || "").trim()).filter((p) => p && !monitoredPaths.includes(p));
  if (fresh.length === 0) return;
  monitoredPaths.push(...fresh);
  await persistAppPaths();
}

document.getElementById("apppaths-add").addEventListener("keydown", async (event) => {
  if (event.key !== "Enter") return;
  const value = event.target.value.trim();
  if (!value) return;
  event.target.value = "";
  await addAppPaths([value]);
});

document.getElementById("apppaths-browse").addEventListener("click", async () => {
  let picked = [];
  try {
    picked = await invoke("pick_inspect_folders");
  } catch (err) {
    document.getElementById("apppaths-status").textContent = `could not open the folder picker: ${err}`;
    return;
  }
  // An empty result is the user cancelling the dialog, not an empty selection -- doing nothing is
  // the only correct response, and clearing the list here would be a data-loss bug wearing a
  // "helpful" hat.
  if (picked.length === 0) return;
  await addAppPaths(picked);
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
// Why the last load failed, if it did -- shared by the New-app dropdown and the Ready screen's
// engine panel so the two cannot describe the same failure differently.
let engineLoadError = null;

async function loadEngines() {
  const select = document.getElementById("new-app-engine");
  try {
    const listing = await invoke("list_engines");
    engineCatalog = listing.engines || [];
    engineLoadError = null;
  } catch (err) {
    // No installed NPDev version yet is the ordinary case on a fresh machine. Say so instead of
    // silently offering an empty dropdown, which reads as "this app has no database options".
    engineCatalog = [];
    engineLoadError = String(err);
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
    // The app someone just created is overwhelmingly the one they are about to run.
    document.getElementById("run-app-dir").value = "";
    await prefillRunAppDir();
  } catch (err) {
    statusEl.textContent = `failed: ${err}`;
  }
});

// ---------------------------------------------------------------------------------------------
// 4: Run screen (M6)
// ---------------------------------------------------------------------------------------------

// Defaults the app-folder field to the most recently created app, same as the doctor picker
// (M15) already does -- retyping a path the Manager itself just wrote is pure friction. Only
// touches the field while it is still empty, so it never clobbers a folder the user typed or
// picked for an app the Manager did not create.
async function prefillRunAppDir() {
  const input = document.getElementById("run-app-dir");
  if (input.value.trim()) return;
  let apps = [];
  try {
    apps = await invoke("list_apps");
  } catch {
    apps = [];
  }
  if (apps.length === 0) return;
  input.value = apps[apps.length - 1].directory;
}

// ---------------------------------------------------------------------------------------------
// The app selector.
//
// Until now this screen offered one bare text field and a one-shot prefill of the last app the
// Manager itself created -- so running anything else meant typing an absolute path from memory,
// including every app generated outside the Manager. The list comes from `monitor_scan`, which
// already searches the union of the Monitored App Paths and every registered app directory (and
// recognises an app by its contents, not its folder name).
//
// `#run-app-dir` remains the ONE value Start/Stop and all five database buttons read. The picker
// writes into it and never becomes a second source of truth -- which is also why typing resets the
// picker: two controls that silently disagree about which app you are about to reset is a bad way
// to find out about a bug.
// ---------------------------------------------------------------------------------------------

let runScanApps = [];

function folderLeaf(path) {
  const parts = String(path || "").split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : String(path || "");
}

async function refreshRunAppPicker() {
  const picker = document.getElementById("run-app-picker");
  const manual = `<option value="">(type a folder below)</option>`;
  let apps = [];
  try {
    const result = await invoke("monitor_scan", { includeInfo: false });
    // `not-an-app` entries are directories the scan looked at and rejected. They belong on the
    // Monitor's wall (it says what it looked at) and not in a list of things you can run.
    apps = (result.apps || []).filter((a) => a.status !== "not-an-app");
  } catch (err) {
    // A scanner that cannot run must never block this screen: manual entry is the older path and it
    // still works. Saying why beats an empty dropdown.
    picker.innerHTML = `${manual}<option value="" disabled>(scan unavailable -- ${escapeHtml(err)})</option>`;
    picker.value = "";
    return;
  }
  runScanApps = apps;
  picker.innerHTML =
    manual +
    apps
      .map(
        (a) =>
          `<option value="${escapeHtml(a.appDir)}">${escapeHtml(a.name || folderLeaf(a.appDir))} -- ${escapeHtml(a.appDir)}</option>`
      )
      .join("");
  syncRunPickerToField();
}

// Keeps the two controls honest in the other direction: the picker shows the manual option unless
// the folder in the field is genuinely one of the discovered apps.
function syncRunPickerToField() {
  const picker = document.getElementById("run-app-picker");
  const current = document.getElementById("run-app-dir").value.trim();
  picker.value = runScanApps.some((a) => a.appDir === current) ? current : "";
}

document.getElementById("run-app-picker").addEventListener("change", () => {
  const picker = document.getElementById("run-app-picker");
  if (!picker.value) return; // the manual option: leave whatever the user typed alone
  document.getElementById("run-app-dir").value = picker.value;
  const app = runScanApps.find((a) => a.appDir === picker.value);
  // The port the app's own resolved plan declares -- the same number the Monitor's card shows and
  // the app will actually bind. Guessing 8080 for an app whose plan says 8411 is how "it started
  // but the link is dead" happens.
  if (app && app.port) document.getElementById("run-port").value = app.port;
});

document.getElementById("run-app-dir").addEventListener("input", syncRunPickerToField);

document.getElementById("run-app-refresh").addEventListener("click", async () => {
  const btn = document.getElementById("run-app-refresh");
  btn.disabled = true;
  try {
    await refreshRunAppPicker();
  } finally {
    btn.disabled = false;
  }
});

// Item 0's on-entry refresh for the Run screen. Scanning shells out to the CLI, so it happens on
// entry and on explicit Refresh only -- never on a timer, on a screen whose job is to run one app.
window.__npdevRefreshRun = async function refreshRun() {
  await prefillRunAppDir();
  await refreshRunAppPicker();
};

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

// Item 0's on-entry refresh for the Install screen. Every status this screen shows can change while
// the Manager is open -- a JDK installed, a setup run, a version switched on the next tab -- and
// before this hook existed none of them was ever re-read after launch.
window.__npdevRefreshInstall = async function refreshInstall() {
  await refreshJdkStatus();
  await refreshPythonStatus();
  await refreshSetupStatus();
  // Also picks up paths added before this step existed: they are the same `inspect_paths` the
  // Monitor has been writing since D7, read out of the same manager.json.
  await refreshAppPaths();
};

(async function init() {
  await initFakeBanner();
  showScreen("ready");
  // Before loadDoctor: the picker's value is what tells doctor which app's database to check, so
  // populating it afterwards would make the first render the one WITHOUT database rows.
  await refreshDoctorAppPicker();
  await loadDoctor();
  // Everything the FIRST screen shows, before anything the other screens need -- these run in
  // series, and `refreshTagList` talks to GitHub. Measured on a real machine: with the engine panel
  // populated after the tag fetch, the answer to "what databases does this support" was still blank
  // several seconds after the window opened, on the screen that opens first.
  // (It also fills the New-app dropdown, from the same one call.)
  await refreshEngineMatrix();
  await refreshJdkStatus();
  await refreshPythonStatus();
  await refreshSetupStatus();
  await refreshAppPaths();
  await refreshAppList();
  await prefillRunAppDir();
  await refreshTagList(false);
  await refreshVersionsScreen();
})();
