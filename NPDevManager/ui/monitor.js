// The Monitor -- MONITOR_PLAN B3/B4/B5/B7.
//
// Every card is a thin render of what `npdev monitor scan|probe` returned. Nothing here decides what
// counts as an app, what counts as healthy, or which process owns a port: those answers come from
// the CLI, so this window and a terminal cannot reach different conclusions about the same machine.
//
// The one thing this file DOES own is how a state is drawn -- and one of those states exists because
// the naive version was wrong: `port-conflict` (a healthy app is on this app's port, but it is a
// DIFFERENT app) is drawn as its own red state rather than as green, because the first live test of
// the probe reported a stopped app as running for exactly that reason.

const { invoke: mInvoke } = window.__TAURI__.core;
const { listen: mListen } = window.__TAURI__.event;

const MONITOR_REFRESH_MS = 30000;

const monitorState = {
  apps: [],
  filter: "all",
  view: "grid",
  inspectPaths: [],
  selected: null,
  timer: null,
  paused: false,
  armed: new Map(), // B5: which destructive control is armed, and when it armed
};

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------

function esc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function toast(message, bad) {
  const el = document.getElementById("monitor-toast");
  el.textContent = message;
  el.className = "toast show" + (bad ? " bad" : "");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (el.className = "toast"), 3600);
}

/** The five states the CLI can report, mapped to the four the CRT knows how to tint. `starting` and
 *  `port-conflict` are NOT folded into running/error: a card that says "starting" during the ~24s
 *  boot stops users learning to ignore red, and a port conflict has a different fix than a crash. */
const STATUS_LABEL = {
  running: "● RUNNING",
  starting: "◐ STARTING",
  stopped: "○ STOPPED",
  error: "▲ ERROR",
  "port-conflict": "▲ PORT TAKEN",
  unknown: "— NO SIGNAL",
  "not-an-app": "— NOT AN APP",
};

const LED_FOR = {
  running: "ok", starting: "info", stopped: "warn",
  error: "err", "port-conflict": "err", unknown: "off", "not-an-app": "off",
};

function statusOf(app) {
  return app.status === "not-an-app" ? "not-an-app" : (app.health || "unknown");
}

function shortPath(value, keep = 46) {
  if (!value) return "—";
  return value.length <= keep ? value : "…" + value.slice(-(keep - 1));
}

// ---------------------------------------------------------------------------------------------
// Inspect paths (D7)
// ---------------------------------------------------------------------------------------------

async function loadInspectPaths() {
  monitorState.inspectPaths = await mInvoke("get_inspect_paths").catch(() => []);
  renderInspectPaths();
}

function renderInspectPaths() {
  const holder = document.getElementById("inspect-chips");
  holder.innerHTML = monitorState.inspectPaths
    .map((p, i) => `<span class="chip">${esc(p)} <span class="x" data-rm="${i}" title="Remove">✕</span></span>`)
    .join("");
  holder.querySelectorAll("[data-rm]").forEach((x) =>
    x.addEventListener("click", async () => {
      monitorState.inspectPaths.splice(Number(x.dataset.rm), 1);
      await mInvoke("set_inspect_paths", { paths: monitorState.inspectPaths });
      renderInspectPaths();
      await refreshMonitor();
    })
  );
}

// ---------------------------------------------------------------------------------------------
// Scan + render
// ---------------------------------------------------------------------------------------------

async function refreshMonitor() {
  const grid = document.getElementById("monitor-grid");
  if (!monitorState.apps.length) {
    grid.innerHTML = `<p class="subtitle">scanning…</p>`;
  }
  try {
    const result = await mInvoke("monitor_scan", { includeInfo: false });
    monitorState.apps = result.apps || [];
    monitorState.searched = result.searched || [];
    renderMonitor();
  } catch (err) {
    // A version mismatch is not a scan failure, and it has a different fix. The CLI wrapper turns
    // argparse's usage dump into one sentence; showing it plainly beats "could not scan" followed by
    // forty lines of choices.
    const message = String(err);
    grid.innerHTML = message.includes("has no `npdev monitor`")
      ? `<div class="pane"><h3>This NPDev version is too old for the Monitor</h3>
           <p class="subtitle">${esc(message)}</p></div>`
      : `<p class="subtitle">could not scan: ${esc(message)}</p>`;
  }
}

function renderMonitor() {
  const counts = {};
  for (const app of monitorState.apps) {
    const state = statusOf(app);
    counts[state] = (counts[state] || 0) + 1;
  }
  document.getElementById("monitor-counts").innerHTML = [
    ["running", "running"], ["starting", "starting"], ["stopped", "stopped"],
    ["error", "error"], ["port-conflict", "port taken"], ["unknown", "no signal"],
  ]
    .filter(([key]) => counts[key])
    .map(([key, label]) => `<span class="count"><span class="led ${LED_FOR[key]}"></span> ${counts[key]} ${label}</span>`)
    .join("");

  const grid = document.getElementById("monitor-grid");
  grid.className = "grid" + (monitorState.view === "list" ? " list" : "");

  const visible = monitorState.apps.filter((app) => {
    const state = statusOf(app);
    if (monitorState.filter === "all") return true;
    if (monitorState.filter === "running") return state === "running" || state === "starting";
    if (monitorState.filter === "attention") return state === "error" || state === "port-conflict";
    if (monitorState.filter === "idle") return state === "stopped" || state === "unknown";
    return true;
  });

  if (!visible.length) {
    grid.innerHTML = emptyStateHtml();
    return;
  }
  grid.innerHTML = visible.map(cardHtml).join("");
  wireCards();
}

function emptyStateHtml() {
  if (monitorState.apps.length) {
    return `<p class="subtitle">No app matches this filter.</p>`;
  }
  // Never a blank panel. On a fresh machine with nothing configured, the honest answer is "we did
  // not look anywhere yet", and it says how to change that.
  return `
    <div class="pane">
      <h3>No generated apps found</h3>
      <p class="subtitle">
        The Monitor shows the apps this Manager created, plus anything found under the inspect paths
        above. It recognises an app by its contents — an <code>_ops</code> directory plus either a
        <code>.npdev-root</code> marker or <code>_ops/resolved-db-plan.json</code> — never by folder
        name.
      </p>
      <p class="subtitle">Add the folder your apps are generated into, then press INSPECT.</p>
    </div>`;
}

function cardHtml(app) {
  const state = statusOf(app);
  const dir = esc(app.appDir);
  const kv = [
    ["URL", app.baseUrl ? `${esc(app.baseUrl)} <small>(probed on 127.0.0.1)</small>` : "—"],
    ["DB", esc(app.engine || "—") + (app.connection && app.connection.database ? ` · ${esc(app.connection.database)}` : "")],
    ["APP", `<span title="${dir}">${esc(shortPath(app.appDir))}</span>`],
    ["OPS", app.opsDir ? esc(shortPath(app.opsDir, 34)) : "—"],
    ["PID", app.pid ? `${app.pid} · port ${app.port || "—"}` : `port ${app.port || "—"}`],
    ["BUILT", app.builtAt ? esc(app.builtAt) : "<small>not built yet</small>"],
    ["ORIGIN", esc(app.origin) + ` <small>(${esc(app.discoveredBy || "?")})</small>`],
  ]
    .map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`)
    .join("");

  const message = app.healthDetail
    ? esc(app.healthDetail)
    : state === "running"
      ? `health: UP · ${app.explorations && app.explorations.definitionCount ? app.explorations.definitionCount + " exploration(s)" : "no explorations yet"}`
      : "";

  const explore = app.explorations || {};
  const exploreLight = explore.runCount
    ? (explore.lastRunGreen ? "🟢" : "🔴")
    : (explore.definitionCount ? "⚪" : "");

  const noSignal = state === "unknown" || state === "not-an-app";

  return `
  <article class="crt" data-status="${esc(state)}" data-dir="${dir}">
    <div class="screen" data-inspect="${dir}">
      ${noSignal ? `<div class="nosignal"><b>NO SIGNAL</b><span>${esc(app.detail || app.healthDetail || "nothing to probe")}</span></div>` : ""}
      <div class="phos">
        <div class="scr-head">
          <span class="app">${esc((app.name || "").toUpperCase())}</span>
          <span class="st">${STATUS_LABEL[state] || state}</span>
        </div>
        <hr class="scr-rule">
        <dl class="kv">${kv}</dl>
        <p class="scr-msg">${message}${state === "running" ? '<span class="blink">▮</span>' : ""}</p>
      </div>
    </div>
    <div class="side">
      <div class="side-st">${STATUS_LABEL[state] || state} ${exploreLight}</div>
      <dl class="kv">${kv}</dl>
      <p class="scr-msg">${message}</p>
    </div>
    <div class="bezel-bottom">
      <span class="brand">NPDEV · CRT-9000</span>
      <span class="power"><small>PWR</small><span class="led ${LED_FOR[state]}"></span></span>
    </div>
    <div class="controls">
      <button class="knob primary" data-open-url="${esc(app.baseUrl || "")}" ${app.baseUrl && state === "running" ? "" : "disabled"}>⛶ OPEN</button>
      ${state === "running" || state === "starting"
        ? `<button class="knob danger" data-stop="${dir}">■ STOP</button>`
        : `<button class="knob" data-start="${dir}" ${app.jarPath ? "" : "disabled title='build it first'"}>▶ START</button>`}
      <button class="knob" data-logs="${dir}">🗎 LOGS</button>
      <button class="knob" data-menu="1">☰ ACTIONS ▾</button>
      <div class="menu">
        <h5>_OPS RUNBOOK</h5>
        <button data-ops="status-environment" data-dir="${dir}">Status-Environment <i>status</i></button>
        <button data-ops="start-environment" data-dir="${dir}">Start-Environment <i>db up</i></button>
        <button data-ops="stop-environment" data-dir="${dir}">Stop-Environment <i>db down</i></button>
        <button data-ops="smoke-test" data-dir="${dir}">Smoke-Test <i>REST smoke</i></button>
        <button data-ops="build-finalapp" data-dir="${dir}">Build-FinalApp <i>rebuild</i></button>
        <button data-ops="print-db-connection-info" data-dir="${dir}">Print-DbConnectionInfo <i>creds</i></button>
        <button class="danger" data-ops="reset-environment" data-dir="${dir}" data-destructive="1">Reset-Environment <i>⚠ deletes data</i></button>
        <hr>
        <button data-explore="${dir}">Explore this app <i>scrap</i></button>
        <button data-folder="${dir}">Open app folder <i>explorer</i></button>
        <button data-folder="${esc(app.opsDir || app.appDir)}">Open _ops folder <i>explorer</i></button>
        <button data-export="${dir}">Export support bundle <i>zip</i></button>
      </div>
    </div>
  </article>`;
}

function wireCards() {
  const grid = document.getElementById("monitor-grid");

  grid.querySelectorAll("[data-inspect]").forEach((el) =>
    el.addEventListener("click", () => openInspector(el.dataset.inspect))
  );
  grid.querySelectorAll("[data-menu]").forEach((btn) =>
    btn.addEventListener("click", (event) => {
      event.stopPropagation();
      const menu = btn.parentElement.querySelector(".menu");
      const open = menu.classList.contains("open");
      document.querySelectorAll("#screen-monitor .menu.open").forEach((m) => m.classList.remove("open"));
      if (!open) menu.classList.add("open");
    })
  );
  grid.querySelectorAll("[data-open-url]").forEach((btn) =>
    btn.addEventListener("click", () => btn.dataset.openUrl && mInvoke("open_url", { url: btn.dataset.openUrl }))
  );
  grid.querySelectorAll("[data-folder]").forEach((btn) =>
    btn.addEventListener("click", () => mInvoke("open_folder", { path: btn.dataset.folder }))
  );
  grid.querySelectorAll("[data-start]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      openRunbox(`starting ${btn.dataset.start}`);
      try {
        await mInvoke("start_app", { appDir: btn.dataset.start });
        toast("starting — the card turns green when health answers");
      } catch (err) {
        toast(String(err), true);
      }
    })
  );
  grid.querySelectorAll("[data-stop]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      try {
        const result = await mInvoke("stop_app", { appDir: btn.dataset.stop });
        toast(result.how || "stopped");
        setTimeout(refreshMonitor, 1500);
      } catch (err) {
        toast(String(err), true);
      }
    })
  );
  grid.querySelectorAll("[data-logs]").forEach((btn) =>
    btn.addEventListener("click", () => openInspector(btn.dataset.logs, "logs"))
  );
  grid.querySelectorAll("[data-export]").forEach((btn) =>
    btn.addEventListener("click", () => exportBundle(btn.dataset.export))
  );
  grid.querySelectorAll("[data-explore]").forEach((btn) =>
    btn.addEventListener("click", () => {
      window.__npdevOpenScrap && window.__npdevOpenScrap(btn.dataset.explore);
    })
  );
  grid.querySelectorAll("[data-ops]").forEach((btn) =>
    btn.addEventListener("click", () => runOps(btn, btn.dataset.dir, btn.dataset.ops, btn.dataset.destructive === "1"))
  );
}

document.addEventListener("click", (event) => {
  if (!event.target.closest || !event.target.closest(".controls")) {
    document.querySelectorAll("#screen-monitor .menu.open").forEach((m) => m.classList.remove("open"));
  }
});

// ---------------------------------------------------------------------------------------------
// B5: running an ops script, with the two-click guard on the destructive ones
// ---------------------------------------------------------------------------------------------

// The token the generated script itself demands. Sent EXPLICITLY rather than silently -- the window
// must be at least as careful as the terminal, and the terminal makes you type this.
const RESET_TOKEN = "I_UNDERSTAND_DB_DATA_WILL_BE_DELETED";

async function runOps(button, appDir, script, destructive) {
  if (destructive) {
    const key = `${appDir}:${script}`;
    if (!monitorState.armed.has(key)) {
      // First click ARMS, visibly, and says exactly what the second click will do. A single
      // confirm() reflexes straight through; an armed state that must be clicked again does not.
      monitorState.armed.set(key, Date.now());
      button.classList.add("armed");
      const original = button.innerHTML;
      button.innerHTML = `CONFIRM: delete this app's data <i>click again</i>`;
      toast("Reset DELETES this app's database. Click again within 5s to confirm.", true);
      setTimeout(() => {
        monitorState.armed.delete(key);
        button.classList.remove("armed");
        button.innerHTML = original;
      }, 5000);
      return;
    }
    monitorState.armed.delete(key);
    button.classList.remove("armed");
  }
  document.querySelectorAll("#screen-monitor .menu.open").forEach((m) => m.classList.remove("open"));
  openRunbox(`${script} · ${appDir}`);
  try {
    await mInvoke("run_ops_script", {
      appDir,
      script,
      confirm: destructive ? RESET_TOKEN : null,
    });
  } catch (err) {
    appendRun(String(err));
    toast(String(err), true);
  }
}

function openRunbox(title) {
  const box = document.getElementById("runbox");
  box.hidden = false;
  document.getElementById("run-title").textContent = title;
  document.getElementById("runout").textContent = "";
  if (!document.getElementById("monitor-inspector").classList.contains("open")) {
    openInspector(monitorState.selected || (monitorState.apps[0] && monitorState.apps[0].appDir));
  }
}

function appendRun(line) {
  const out = document.getElementById("runout");
  out.textContent += line + "\n";
  out.scrollTop = out.scrollHeight;
}

mListen("ops-event", (event) => {
  const payload = event.payload || {};
  if (payload.kind === "line") appendRun(payload.text);
  else if (payload.kind === "done") {
    appendRun(`\n— finished (exit ${payload.exitCode}) —`);
    if (payload.logFile) appendRun(`captured to ${payload.logFile}`);
    setTimeout(refreshMonitor, 1200);
  } else if (payload.output) appendRun(payload.output);
});

document.getElementById("runbox-close").addEventListener("click", () => {
  document.getElementById("runbox").hidden = true;
});

// ---------------------------------------------------------------------------------------------
// B4 + B7: the inspector (info rows, probed rows, logs)
// ---------------------------------------------------------------------------------------------

async function openInspector(appDir, tab) {
  if (!appDir) return;
  monitorState.selected = appDir;
  const panel = document.getElementById("monitor-inspector");
  panel.classList.add("open");
  document.getElementById("monitor-backdrop").classList.add("show");
  document.getElementById("insp-title").textContent = appDir;
  const body = document.getElementById("insp-body");
  body.innerHTML = `<p class="insp-empty">reading…</p>`;
  setInspectorTab(tab || "info");

  try {
    const probe = await mInvoke("read_info_json", { appDir });
    monitorState.probe = probe;
    renderInspector(probe);
  } catch (err) {
    body.innerHTML = `<p class="insp-empty">could not read this app: ${esc(err)}</p>`;
  }
}

function setInspectorTab(tab) {
  monitorState.inspTab = tab;
  document.querySelectorAll("#insp-tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  if (monitorState.probe) renderInspector(monitorState.probe);
}

function renderInspector(probe) {
  const body = document.getElementById("insp-body");
  document.getElementById("insp-sub").textContent =
    `${probe.health || "?"} · ${probe.engine || "?"} · discovered by ${probe.discoveredBy || "?"}`;
  document.getElementById("insp-led").className = "led " + (LED_FOR[statusOf(probe)] || "off");

  if (monitorState.inspTab === "logs") {
    renderLogsTab(probe);
    return;
  }

  const sections = [];

  if (probe.superUserKeyFile) {
    sections.push(`
      <div class="insp-callout">
        🔑 <b>Super User key file</b> — needed to unlock the Control Panel the first time
        <code>${esc(probe.superUserKeyFile)}</code>
        <button data-copy="${esc(probe.superUserKeyFile)}">📋 Copy path</button>
      </div>`);
  }

  // GENERATED rows: the app's own info.json. B4's graceful degradation -- an app built before the
  // emitter has none, and says so rather than showing an empty panel.
  const info = probe.info;
  if (info && Array.isArray(info.records)) {
    const bySection = {};
    for (const record of info.records) (bySection[record.section] ||= []).push(record);
    for (const [name, rows] of Object.entries(bySection)) {
      sections.push(accordion(name, rows.map((r) => infoRow(r, probe)).join(""), rows.length));
    }
  } else {
    sections.push(`
      <div class="insp-note">
        This app has no <code>info.json</code>. It was generated before the emitter landed —
        regenerate it for the full inspector. The probed rows below still work.
      </div>`);
  }

  // PROBED rows: exactly what the emitter deliberately does NOT bake (D2-a).
  const probed = [
    ["Generated app root", probe.finalAppRoot || probe.appDir],
    ["Output root", probe.outputRoot],
    ["Ops toolbox", probe.opsDir],
    ["Runnable jar", probe.jarPath],
    ["Built at", probe.builtAt],
    ["App definition", probe.appDefinitionRoot],
    ["Model", probe.modelPath],
    ["Data root", probe.dataRoot],
    ["DB file", probe.dbFile],
    ["Run logs", probe.logsDir],
    ["Port", probe.port],
    ["PID", probe.pid],
    ["Listening", probe.listening],
    ["Identity", probe.identity + (probe.identityDetail ? " — " + probe.identityDetail : "")],
    ["Docker", probe.docker ? `${probe.docker.containerName}: ${probe.docker.state}` : null],
  ].filter(([, v]) => v !== null && v !== undefined && v !== "");
  sections.push(accordion("This machine (probed)",
    probed.map(([k, v]) => probedRow(k, v)).join(""), probed.length, true));

  // The runbook, runnable from here (B5).
  const scripts = [
    ["status-environment", "Status-Environment", false],
    ["start-environment", "Start-Environment", false],
    ["stop-environment", "Stop-Environment", false],
    ["build-finalapp", "Build-FinalApp", false],
    ["run-finalapp", "Run-FinalApp", false],
    ["smoke-test", "Smoke-Test", false],
    ["print-db-connection-info", "Print-DbConnectionInfo", false],
    ["create-environment", "Create-Environment", false],
    ["reset-environment", "Reset-Environment", true],
  ];
  sections.push(accordion("Runbook (_ops)", scripts.map(([key, label, danger]) => `
    <div class="irow">
      <span class="k">${esc(label)}</span>
      <span class="v">${danger ? "deletes this app's database" : ""}</span>
      <span class="ops">
        <button class="run${danger ? " danger" : ""}" data-ops="${key}" data-dir="${esc(probe.appDir)}"
                ${danger ? 'data-destructive="1"' : ""}>▶ run</button>
      </span>
    </div>`).join(""), scripts.length));

  body.innerHTML = sections.join("");
  wireInspector();
}

function infoRow(record, probe) {
  const live = probe.health === "running" && probe.baseUrl;
  let value = record.value != null ? record.value
    : (live ? probe.baseUrl + record.path : record.path);

  // D-b: info.json's "API key header" row is a LITERAL `X-Api-Key: dev-key`, and it has to be --
  // info.html is served unauthenticated, so it must never bake an app's real key. But the real value
  // comes from config.json's `trialDefaults.apiKey` via the resolved plan, so an app that configured
  // its own key has a published default that is quietly wrong. The probe now reads the actual value
  // (npdev_monitor.probe_app), and this is where the two meet: show the app's own key, and SAY that
  // the page still advertises the default, rather than silently replacing one string with another.
  if (probe.apiKey && probe.authHeader && String(record.value || "").startsWith(probe.authHeader + ":")) {
    const advertised = String(record.value).slice(probe.authHeader.length + 1).trim();
    if (advertised !== probe.apiKey) {
      value = `${probe.authHeader}: ${probe.apiKey}   (info.html still advertises "${advertised}")`;
    }
  }

  const openable = record.openable && live;
  return `
    <div class="irow${record.important ? " imp" : ""}">
      <span class="k">${esc(record.property)}</span>
      <span class="v">${esc(value)}</span>
      <span class="ops">
        <button data-copy="${esc(value)}">copy</button>
        ${openable ? `<button data-open="${esc(value)}">open</button>` : ""}
      </span>
    </div>`;
}

function probedRow(key, value) {
  return `
    <div class="irow probed">
      <span class="k">${esc(key)}</span>
      <span class="v">${esc(value)}</span>
      <span class="ops"><button data-copy="${esc(value)}">copy</button></span>
    </div>`;
}

function accordion(title, rows, count, open) {
  return `
    <div class="acc${open ? " open" : ""}">
      <button class="hd"><span class="tri">▶</span>${esc(title)}<span class="n">${count}</span></button>
      <div class="rows">${rows}</div>
    </div>`;
}

function wireInspector() {
  const body = document.getElementById("insp-body");
  body.querySelectorAll(".acc > button.hd").forEach((hd) =>
    hd.addEventListener("click", () => hd.parentElement.classList.toggle("open"))
  );
  body.querySelectorAll("[data-copy]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(btn.dataset.copy);
        toast("copied");
      } catch {
        toast("could not copy", true);
      }
    })
  );
  body.querySelectorAll("[data-open]").forEach((btn) =>
    btn.addEventListener("click", () => mInvoke("open_url", { url: btn.dataset.open }))
  );
  body.querySelectorAll("[data-ops]").forEach((btn) =>
    btn.addEventListener("click", () => runOps(btn, btn.dataset.dir, btn.dataset.ops, btn.dataset.destructive === "1"))
  );
  const filter = document.getElementById("insp-filter");
  filter.oninput = () => {
    const needle = filter.value.toLowerCase();
    body.querySelectorAll(".acc").forEach((acc) => {
      let any = false;
      acc.querySelectorAll(".irow").forEach((row) => {
        const show = row.textContent.toLowerCase().includes(needle);
        row.style.display = show ? "" : "none";
        if (show) any = true;
      });
      acc.style.display = any ? "" : "none";
      if (needle && any) acc.classList.add("open");
    });
  };
}

// ---------------------------------------------------------------------------------------------
// B7: the logs tab
// ---------------------------------------------------------------------------------------------

async function renderLogsTab(probe) {
  const body = document.getElementById("insp-body");
  body.innerHTML = `<p class="insp-empty">reading logs…</p>`;
  try {
    const result = await mInvoke("monitor_logs", { appDir: probe.appDir, source: "all", tail: 300 });
    const managerLog = await mInvoke("manager_log_path").catch(() => null);
    body.innerHTML = `
      <div class="insp-note">
        Three sources, one surface: the app's own stdout, every <code>_ops</code> script run from
        here, and the Manager's own log. Export produces one file you can send — with the database
        password redacted, because that is the whole point of an export leaving the machine.
      </div>
      <div class="irow" style="grid-template-columns:1fr auto">
        <span class="k">Manager log</span>
        <span class="ops"><button data-copy="${esc(managerLog || "")}">copy path</button></span>
      </div>
      ${(result.sources || []).map(logSection).join("")}
      <div style="padding:12px 8px; display:flex; gap:8px; flex-wrap:wrap">
        <button class="btn" id="logs-export">Export support bundle…</button>
        <button class="btn ghost" id="logs-folder">Open logs folder</button>
      </div>`;
    body.querySelectorAll(".acc > button.hd").forEach((hd) =>
      hd.addEventListener("click", () => hd.parentElement.classList.toggle("open"))
    );
    body.querySelectorAll("[data-copy]").forEach((btn) =>
      btn.addEventListener("click", () => navigator.clipboard.writeText(btn.dataset.copy).then(() => toast("copied")))
    );
    document.getElementById("logs-export").addEventListener("click", () => exportBundle(probe.appDir));
    document.getElementById("logs-folder").addEventListener("click", () =>
      mInvoke("open_folder", { path: probe.logsDir || probe.appDir })
    );
  } catch (err) {
    body.innerHTML = `<p class="insp-empty">could not read logs: ${esc(err)}</p>`;
  }
}

function logSection(source) {
  const files = source.files || [];
  const tail = (source.tail || []).join("\n");
  const inner = source.detail
    ? `<div class="irow"><span class="k">—</span><span class="v">${esc(source.detail)}</span><span></span></div>`
    : `<pre class="runout" style="max-height:280px">${esc(tail)}</pre>`;
  return accordion(`${source.source} (${files.length} file${files.length === 1 ? "" : "s"})`, inner, files.length,
    source.source === "app");
}

async function exportBundle(appDir) {
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const name = (appDir.split(/[\\/]/).pop() || "app") + "-support-" + stamp + ".zip";
  // Written beside the app so the path is predictable and the user can find it without a dialog --
  // the Manager deliberately does not depend on the dialog plugin for this.
  const target = `${appDir}\\${name}`;
  toast("writing bundle…");
  try {
    const result = await mInvoke("export_logs", { appDir, outZip: target });
    toast(`bundle written: ${result.zip}`);
    mInvoke("open_folder", { path: appDir });
  } catch (err) {
    toast(String(err), true);
  }
}

function closeInspector() {
  document.getElementById("monitor-inspector").classList.remove("open");
  document.getElementById("monitor-backdrop").classList.remove("show");
}

// ---------------------------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------------------------

function initMonitor() {
  document.getElementById("inspect-add").addEventListener("keydown", async (event) => {
    if (event.key !== "Enter") return;
    const value = event.target.value.trim();
    if (!value) return;
    monitorState.inspectPaths.push(value);
    await mInvoke("set_inspect_paths", { paths: monitorState.inspectPaths });
    event.target.value = "";
    renderInspectPaths();
    await refreshMonitor();
  });
  document.getElementById("inspect-scan").addEventListener("click", refreshMonitor);
  document.getElementById("inspect-auto").addEventListener("click", (event) => {
    monitorState.paused = !monitorState.paused;
    event.target.textContent = monitorState.paused ? "AUTO OFF" : "AUTO 30s";
    event.target.classList.toggle("ghost", monitorState.paused);
  });
  document.querySelectorAll("#monitor-filters button").forEach((btn) =>
    btn.addEventListener("click", () => {
      monitorState.filter = btn.dataset.f;
      document.querySelectorAll("#monitor-filters button").forEach((b) => b.classList.toggle("active", b === btn));
      renderMonitor();
    })
  );
  document.querySelectorAll("#monitor-view button").forEach((btn) =>
    btn.addEventListener("click", () => {
      monitorState.view = btn.dataset.v;
      document.querySelectorAll("#monitor-view button").forEach((b) => b.classList.toggle("active", b === btn));
      renderMonitor();
    })
  );
  document.getElementById("insp-close").addEventListener("click", closeInspector);
  document.getElementById("monitor-backdrop").addEventListener("click", closeInspector);
  document.querySelectorAll("#insp-tabs button").forEach((btn) =>
    btn.addEventListener("click", () => setInspectorTab(btn.dataset.tab))
  );

  loadInspectPaths().then(refreshMonitor);

  // The refresh loop re-derives every card from the machine rather than trusting anything this
  // window remembers -- kill the Manager and restart it and the wall is still correct.
  monitorState.timer = setInterval(() => {
    if (monitorState.paused) return;
    if (document.getElementById("screen-monitor").hidden) return;
    refreshMonitor();
  }, MONITOR_REFRESH_MS);
}

window.__npdevInitMonitor = initMonitor;
window.__npdevRefreshMonitor = refreshMonitor;
window.__npdevMonitorApps = () => monitorState.apps;
