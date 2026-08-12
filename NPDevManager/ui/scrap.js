// The Scrap Manager -- MONITOR_PLAN D2/D3/D4 + E.
//
// App picker -> run history -> filmstrip detail -> create/validate/play. Every verdict, every
// validation message and every retention decision comes from `npdev explore`; this file renders.
//
// Two rules are load-bearing here and are visible in the code below:
//
//   D4  A failed PRECONDITION is never drawn like a failed exploration. A tool problem dressed as a
//       test result teaches people to distrust the tests -- the QUAL-4 lesson.
//   D5  An EXCUSED error is rendered VISIBLY, struck through, with the rule that excused it beside
//       it. An allowlist nobody can see is how an excuse outlives its reason.

const { invoke: sInvoke } = window.__TAURI__.core;

const scrapState = {
  apps: [],
  appDir: null,
  list: null,
  run: null,
  engine: null,
  createTab: "assistant",
};

/** Playwright's errors carry ANSI colour codes, and the engine passes them through verbatim into
 *  `error.message`. Rendered in HTML they show up as literal `[2m` / `[22m` noise in the middle of
 *  the one sentence a user is trying to read. Stripped at DISPLAY time only -- the run record keeps
 *  the message exactly as the engine returned it, because a record that has been "cleaned up" is a
 *  record you cannot compare against the tool's own output. */
function stripAnsi(text) {
  return String(text).replace(/\u001b\[[0-9;]*m/g, "");
}

function sEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : stripAnsi(value);
  return div.innerHTML;
}

function sToast(message, bad) {
  const el = document.getElementById("monitor-toast");
  el.textContent = message;
  el.className = "toast show" + (bad ? " bad" : "");
  clearTimeout(sToast._t);
  sToast._t = setTimeout(() => (el.className = "toast"), 4200);
}

// ---------------------------------------------------------------------------------------------
// D9: the engine chip. Discovered, never asked for.
// ---------------------------------------------------------------------------------------------

async function refreshEngine() {
  const chip = document.getElementById("engine-chip");
  const startBtn = document.getElementById("engine-start");
  const stopBtn = document.getElementById("engine-stop");
  try {
    const engine = await sInvoke("engine_status", {});
    scrapState.engine = engine;
    if (engine.state === "running") {
      chip.className = "enginechip on";
      chip.textContent = `engine: running (${engine.endpoint})`;
      startBtn.hidden = true;
      // Only offer to stop what THIS window started. Stopping an engine somebody else started, from
      // a window they did not use to start it, is a surprise nobody asked for.
      stopBtn.hidden = !engine.startedByThisWindow;
    } else if (engine.state === "installed-stopped") {
      chip.className = "enginechip off";
      chip.textContent = `engine: installed, stopped (found via ${engine.via})`;
      startBtn.hidden = false;
      stopBtn.hidden = true;
    } else {
      chip.className = "enginechip missing";
      chip.textContent = "engine: not installed";
      startBtn.hidden = true;
      stopBtn.hidden = true;
    }
    document.getElementById("engine-detail").textContent = engine.detail || "";
    // D9's honest disabled state: history and definitions still render, only Play is off.
    document.querySelectorAll("[data-needs-engine]").forEach((el) => {
      el.disabled = !engine.found;
      el.title = engine.found ? "" : "install the exploration engine to run this";
    });
  } catch (err) {
    chip.className = "enginechip missing";
    chip.textContent = "engine: could not check";
    document.getElementById("engine-detail").textContent = String(err);
  }
}

// ---------------------------------------------------------------------------------------------
// App picker + history
// ---------------------------------------------------------------------------------------------

async function refreshScrapApps(preferDir) {
  const picker = document.getElementById("scrap-app");
  const apps = (window.__npdevMonitorApps && window.__npdevMonitorApps()) || [];
  scrapState.apps = apps.filter((a) => a.isAppRoot);
  if (!scrapState.apps.length) {
    picker.innerHTML = `<option value="">(no apps found — see The Monitor)</option>`;
    document.getElementById("scrap-runs").innerHTML =
      `<p class="subtitle">No generated app to explore yet.</p>`;
    return;
  }
  picker.innerHTML = scrapState.apps
    .map((a) => `<option value="${sEsc(a.appDir)}">${sEsc(a.name)} — ${sEsc(a.health)}</option>`)
    .join("");
  const target = preferDir || scrapState.appDir || scrapState.apps[0].appDir;
  if (scrapState.apps.some((a) => a.appDir === target)) picker.value = target;
  scrapState.appDir = picker.value;
  await refreshScrapList();
}

async function refreshScrapList() {
  const holder = document.getElementById("scrap-runs");
  const defs = document.getElementById("scrap-definitions");
  if (!scrapState.appDir) return;
  holder.innerHTML = `<p class="subtitle">reading history…</p>`;
  try {
    const list = await sInvoke("explore_list", { appDir: scrapState.appDir });
    scrapState.list = list;

    defs.innerHTML = (list.definitions || []).length
      ? list.definitions
          .map(
            (d) => `
        <div class="runrow" data-def="${sEsc(d.file)}">
          <span class="dot ${d.hasBaseline ? "green" : ""}"></span>
          <span>${sEsc(d.name)}<br><span class="meta">${d.stepCount} steps${d.hasBaseline ? " · baseline accepted" : ""}</span></span>
          <span><button class="btn" data-play="${sEsc(d.file)}" data-needs-engine="1">▶</button></span>
        </div>`
          )
          .join("")
      : `<p class="subtitle">No routines yet. Create one below — the JSON tab needs no assistant.</p>`;

    const runs = list.runs || [];
    holder.innerHTML = runs.length
      ? runs
          .map(
            (r) => `
        <div class="runrow" data-run="${sEsc(r.runId)}">
          <span class="dot ${r.verdict && r.verdict.green ? "green" : "red"}"></span>
          <span>${sEsc((r.definition && r.definition.scenarioName) || r.runId)}
            <br><span class="meta">${sEsc(r.startedAt || r.runId)} · ${r.stepCount || 0} steps${
              r.baselineDiff && r.baselineDiff.screenshotsChanged
                ? ` · ${r.baselineDiff.screenshotsChanged} screenshot(s) changed`
                : ""
            }</span></span>
          <span>
            <span class="badge driver">${sEsc(r.driver || "")}</span>
            ${r.pinned ? '<span class="badge pinned">pinned</span>' : ""}
          </span>
        </div>`
          )
          .join("")
      : `<p class="subtitle">No runs yet for this app.</p>`;

    holder.querySelectorAll("[data-run]").forEach((row) =>
      row.addEventListener("click", () => openRun(row.dataset.run))
    );
    defs.querySelectorAll("[data-play]").forEach((btn) =>
      btn.addEventListener("click", (event) => {
        event.stopPropagation();
        playRoutine(btn.dataset.play);
      })
    );
    await refreshEngine();
  } catch (err) {
    holder.innerHTML = `<p class="subtitle">could not list: ${sEsc(err)}</p>`;
  }
}

// ---------------------------------------------------------------------------------------------
// D2: the filmstrip
// ---------------------------------------------------------------------------------------------

async function openRun(runId) {
  const detail = document.getElementById("scrap-detail");
  detail.innerHTML = `<p class="subtitle">reading run…</p>`;
  document.querySelectorAll("#scrap-runs .runrow").forEach((r) =>
    r.classList.toggle("active", r.dataset.run === runId)
  );
  try {
    const result = await sInvoke("explore_show", { appDir: scrapState.appDir, runId });
    scrapState.run = result.run;
    renderRun(result.run);
  } catch (err) {
    detail.innerHTML = `<p class="subtitle">could not read the run: ${sEsc(err)}</p>`;
  }
}

function renderRun(run) {
  const verdict = run.verdict || {};
  const evidence = run.evidence || {};
  const definition = run.definition || {};
  const target = run.target || {};

  const steps = (run.steps || [])
    .map(
      (s) => `
      <div class="tstep ${sEsc(s.status)}">
        <span class="idx">${s.index}</span>
        <span class="action">${sEsc(s.action || "")}</span>
        <span>${sEsc(s.label || "")}</span>
        <span class="dur">${s.durationMs ? s.durationMs + " ms" : ""}</span>
        ${s.error ? `<span class="err">${sEsc(s.error)}</span>` : ""}
      </div>`
    )
    .join("");

  // D5 made visible: excused errors are SHOWN, struck through, each with its rule.
  const excused = (verdict.excused || [])
    .map(
      (e) => `<div class="evrow excused"><span class="txt">${sEsc(e.text)}</span><span class="rule">excused by ${sEsc(e.rule)}</span></div>`
    )
    .join("");
  const evidenceRows = [
    ...(evidence.consoleErrors || []).map((e) => `<div class="evrow">console: ${sEsc(e.text || JSON.stringify(e))}</div>`),
    ...(evidence.pageErrors || []).map((e) => `<div class="evrow">page: ${sEsc(e)}</div>`),
    ...(evidence.networkFailures || []).map(
      (e) => `<div class="evrow">network: ${sEsc((e.origin || "") + (e.pathname || ""))} ${sEsc(e.status || e.failureText || "")}</div>`
    ),
    ...(evidence.unexpectedExternalRequests || []).map(
      (e) => `<div class="evrow">external: ${sEsc((e.origin || "") + (e.pathname || ""))}</div>`
    ),
  ].join("");

  const shots = (evidence.screenshots || [])
    .map((s) =>
      s.resolvedPath
        ? `<div class="thumb" data-shot="${sEsc(s.resolvedPath)}">
             <img src="${convertSrc(s.resolvedPath)}" alt="${sEsc(s.name)}">
             <div class="cap">${sEsc(s.name)}</div>
           </div>`
        : `<div class="thumb"><div class="cap">${sEsc(s.name)} — ${sEsc(s.detail || "not stored")}</div></div>`
    )
    .join("");

  const diff = run.baselineDiff;

  document.getElementById("scrap-detail").innerHTML = `
    <div class="pane">
      <div style="display:flex; align-items:center; gap:14px; flex-wrap:wrap">
        <span class="verdict ${verdict.green ? "green" : "red"}">${verdict.green ? "GREEN" : "RED"}</span>
        <span class="subtitle" style="margin:0">${sEsc(run.runId)}</span>
        <span class="badge driver">${sEsc(run.driver || "")}</span>
        ${run.pinned ? '<span class="badge pinned">pinned</span>' : ""}
        <span style="margin-left:auto; display:flex; gap:8px">
          <button class="btn ghost" id="run-pin">${run.pinned ? "Unpin" : "Pin evidence"}</button>
          <button class="btn ghost" id="run-accept">Accept as baseline</button>
          ${verdict.green ? "" : `<button class="btn" id="run-repair" data-needs-engine="1">Ask assistant to fix…</button>`}
        </span>
      </div>
      ${(verdict.reasons || []).map((r) => `<p class="subtitle" style="color:#e2b7b3">why not green: ${sEsc(r)}</p>`).join("")}

      <h3 style="margin-top:14px">Identity</h3>
      <div class="irow"><span class="k">routine sha256</span><span class="v">${sEsc(definition.contentSha256 || "—")}</span><span></span></div>
      <div class="irow"><span class="k">model sha256</span><span class="v">${sEsc(target.modelSha256 || "—")}</span><span></span></div>
      <div class="irow"><span class="k">platform</span><span class="v">${sEsc(target.platform || "—")}</span><span></span></div>
      <div class="irow"><span class="k">target</span><span class="v">${sEsc(target.baseUrl || "—")} · ${sEsc(target.engine || "—")}</span><span></span></div>
      ${diff ? `<div class="irow"><span class="k">vs baseline</span><span class="v">${diff.screenshotsChanged} screenshot(s) changed, text ${diff.textChanged ? "changed" : "unchanged"}</span><span></span></div>` : ""}

      <h3 style="margin-top:14px">Steps</h3>
      <div class="timeline">${steps || '<p class="subtitle">no steps recorded</p>'}</div>

      <h3 style="margin-top:14px">Evidence</h3>
      ${evidenceRows || '<p class="subtitle">no console, page, network or external-request problems</p>'}
      ${excused ? `<p class="subtitle" style="margin-top:8px">Excused by the allowlist that was in force for this run — shown, never hidden:</p>${excused}` : ""}

      <h3 style="margin-top:14px">Screenshots</h3>
      <div class="thumbs">${shots || '<p class="subtitle">none captured</p>'}</div>
    </div>`;

  document.querySelectorAll("#scrap-detail [data-shot]").forEach((thumb) =>
    thumb.addEventListener("click", () => {
      const box = document.getElementById("lightbox");
      box.querySelector("img").src = convertSrc(thumb.dataset.shot);
      box.classList.add("show");
    })
  );
  document.getElementById("run-pin").addEventListener("click", async () => {
    await sInvoke("explore_pin", { appDir: scrapState.appDir, runId: run.runId, ledger: null, unpin: !!run.pinned });
    sToast(run.pinned ? "unpinned" : "pinned — its evidence is now exempt from pruning");
    await refreshScrapList();
    await openRun(run.runId);
  });
  document.getElementById("run-accept").addEventListener("click", async () => {
    await sInvoke("explore_accept_baseline", { appDir: scrapState.appDir, runId: run.runId });
    sToast("baseline accepted");
    await refreshScrapList();
  });
  const repair = document.getElementById("run-repair");
  if (repair) repair.addEventListener("click", () => openAssistant(run.runId));
  refreshEngine();
}

/** Tauri's asset protocol. R1: the capability scope is limited to the runs/blobs directories in
 *  `capabilities/default.json`; without that entry the browser refuses these URLs outright, which is
 *  exactly how M0-M8 lost an afternoon. */
function convertSrc(path) {
  try {
    return window.__TAURI__.core.convertFileSrc(path);
  } catch {
    return "";
  }
}

// ---------------------------------------------------------------------------------------------
// D4: play, with preconditions reported as preconditions
// ---------------------------------------------------------------------------------------------

async function playRoutine(file) {
  const detail = document.getElementById("scrap-detail");
  detail.innerHTML = `<div class="pane"><h3>Preflight</h3><p class="subtitle">checking…</p></div>`;
  let pre;
  try {
    pre = await sInvoke("explore_preflight", { appDir: scrapState.appDir });
  } catch (err) {
    detail.innerHTML = `<div class="pane"><h3>Preflight</h3><p class="subtitle">${sEsc(err)}</p></div>`;
    return;
  }
  const rows = (pre.checks || [])
    .map(
      (c) => `
      <div class="pre ${c.status}">
        <span class="mark">${c.status === "pass" ? "✓" : "!"}</span>
        <span class="name">${sEsc(c.name)}</span>
        <span class="detail">${sEsc(c.detail || "")}</span>
      </div>`
    )
    .join("");
  const failed = (pre.checks || []).filter((c) => c.status !== "pass");
  detail.innerHTML = `
    <div class="pane">
      <h3>Preflight</h3>
      ${rows}
      ${
        failed.length
          ? `<p class="subtitle" style="margin-top:10px">
               These are TOOL conditions, not test results — nothing has been explored yet.
               Fix them and press ▶ again.
             </p>`
          : `<p class="subtitle" style="margin-top:10px">Running the routine…</p>`
      }
    </div>`;
  if (failed.length) return;

  try {
    const record = await sInvoke("explore_run", { appDir: scrapState.appDir, file });
    sToast(record.verdict && record.verdict.green ? "GREEN" : "RED — open the run for the failing step",
           !(record.verdict && record.verdict.green));
    // Re-list rather than hand-patching the list: the CLI just wrote the record, and a UI that
    // reconstructs it from the response is a second copy free to drift.
    await refreshScrapList();
    await openRun(record.runId);
  } catch (err) {
    detail.innerHTML = `<div class="pane"><h3>The run could not start</h3><p class="subtitle">${sEsc(err)}</p></div>`;
  }
}

// ---------------------------------------------------------------------------------------------
// D3: the create modal
// ---------------------------------------------------------------------------------------------

const SKELETON = {
  scenarioName: "my-first-exploration",
  targetPath: "/npdev-business-ui/",
  options: { headless: true, screenshots: "always", collectDomOnFailure: true },
  steps: [
    { action: "goto", url: "http://127.0.0.1:8080/npdev-business-ui/", label: "Open the business UI" },
    { action: "waitForSelector", selector: "body", state: "visible", label: "Wait for the page" },
  ],
};

function openCreate() {
  const modal = document.getElementById("create-modal");
  modal.classList.add("show");
  const app = scrapState.apps.find((a) => a.appDir === scrapState.appDir);
  const skeleton = JSON.parse(JSON.stringify(SKELETON));
  if (app && app.probeBaseUrl) {
    skeleton.steps[0].url = app.probeBaseUrl + "/npdev-business-ui/";
  }
  document.getElementById("create-json").value = JSON.stringify(skeleton, null, 2);
  document.getElementById("create-name").value = "";
  document.getElementById("create-validation").innerHTML = "";
  document.getElementById("create-play").disabled = true;
  setCreateTab("json");
  refreshEngine();
}

function setCreateTab(tab) {
  scrapState.createTab = tab;
  document.querySelectorAll("#create-tabs button").forEach((b) => b.classList.toggle("active", b.dataset.t === tab));
  document.getElementById("create-json-pane").hidden = tab !== "json";
  document.getElementById("create-assistant-pane").hidden = tab !== "assistant";
}

async function validateDraft() {
  const out = document.getElementById("create-validation");
  const text = document.getElementById("create-json").value;
  out.innerHTML = `<span class="i">validating…</span>`;
  let temp;
  try {
    JSON.parse(text);
  } catch (err) {
    out.innerHTML = `<div class="e">not valid JSON: ${sEsc(err)}</div>`;
    document.getElementById("create-play").disabled = true;
    return null;
  }
  try {
    // Save first, then validate the FILE -- the CLI validates files, and validating a different
    // representation than the one that will run is how "valid in the UI" starts to mean something
    // else. The name is required before Validate for exactly that reason.
    const name = document.getElementById("create-name").value.trim() || "draft";
    temp = await sInvoke("save_routine", { appDir: scrapState.appDir, name, content: text });
    const app = scrapState.apps.find((a) => a.appDir === scrapState.appDir);
    const result = await sInvoke("explore_validate", { file: temp, baseUrl: (app && app.probeBaseUrl) || null });
    const errors = (result.errors || []).map((e) => `<div class="e">error &nbsp;${sEsc(e.path || "")} ${sEsc(e.message)}</div>`).join("");
    const warnings = (result.warnings || [])
      .map((w) => `<div class="${w.level === "warning" ? "w" : "i"}">${sEsc(w.level)} ${sEsc(w.message)}</div>`)
      .join("");
    out.innerHTML =
      (result.valid ? `<div class="ok">VALID — ${sEsc(result.stepCount)} steps against ${sEsc(result.composedTargetUrl)}</div>` : "") +
      errors + warnings +
      (result.unassertedFormats && result.unassertedFormats.length
        ? `<div class="i">note &nbsp;these formats were not asserted: ${sEsc(result.unassertedFormats.join(", "))}</div>`
        : "");
    document.getElementById("create-play").disabled = !result.valid;
    return result.valid ? temp : null;
  } catch (err) {
    out.innerHTML = `<div class="e">${sEsc(err)}</div>`;
    document.getElementById("create-play").disabled = true;
    return null;
  }
}

// ---------------------------------------------------------------------------------------------
// E3-a: compose, SHOW, then send. Two steps, structurally.
// ---------------------------------------------------------------------------------------------

async function openAssistant(runId) {
  const modal = document.getElementById("assistant-modal");
  modal.classList.add("show");
  document.getElementById("assistant-payload").textContent = "";
  document.getElementById("assistant-send").disabled = true;
  document.getElementById("assistant-result").innerHTML = "";
  modal.dataset.runId = runId || "";
  const config = await sInvoke("assistant_config").catch(() => ({ configured: false }));
  // Phase F: providers are configured in the Prompter tab now -- this modal is read-only, and the
  // Manager has no Settings screen to send anyone to. Saying "not configured" full stop to someone
  // who HAS set providers up one tab over is both wrong and unactionable, so the count decides which
  // sentence they get.
  document.getElementById("assistant-state").textContent = config.configured
    ? `provider: ${config.kind}${config.model ? " · " + config.model : ""}`
    : config.prompterProfiles
      ? `no routine provider configured here — you have ${config.prompterProfiles} provider(s) in the Prompter tab; ` +
        "this flow is separate because it validates the returned routine against the pinned engine schema. " +
        "You can also write the routine by hand in the JSON tab."
      : "no provider configured — configure one in the Prompter tab, or write the routine by hand in the JSON tab";
  document.getElementById("assistant-send").hidden = !config.configured;
}

async function composePayload() {
  const modal = document.getElementById("assistant-modal");
  const prompt = document.getElementById("assistant-prompt").value.trim();
  if (!prompt) {
    sToast("say what you want it to do", true);
    return;
  }
  const includeText = document.getElementById("assistant-include-text").checked;
  const view = document.getElementById("assistant-payload");
  view.textContent = "composing…";
  try {
    const payload = await sInvoke("assistant_compose", {
      appDir: scrapState.appDir,
      prompt,
      runId: modal.dataset.runId || null,
      includePageText: includeText,
    });
    scrapState.payload = payload;
    view.textContent = JSON.stringify(payload, null, 2);
    document.getElementById("assistant-send").disabled = false;
    sToast("composed — nothing has been sent. Read it, then press Send.");
  } catch (err) {
    view.textContent = String(err);
  }
}

async function sendPayload() {
  const view = document.getElementById("assistant-result");
  view.innerHTML = `<span class="i">sending…</span>`;
  try {
    // Sends EXACTLY the object that was displayed. Not a re-composition: if the bytes that leave
    // were not the bytes that were shown, the guarantee is worthless.
    const result = await sInvoke("assistant_generate", { payload: scrapState.payload });
    if (result.routine) {
      document.getElementById("create-json").value = JSON.stringify(result.routine, null, 2);
      view.innerHTML = result.ok
        ? `<span class="ok">a VALID routine came back — it is in the JSON tab; review it before playing</span>`
        : `<span class="w">a routine came back but it does not validate; see the JSON tab</span>`;
      document.getElementById("assistant-modal").classList.remove("show");
      document.getElementById("create-modal").classList.add("show");
      setCreateTab("json");
      validateDraft();
    } else {
      view.innerHTML = `<span class="e">${sEsc((result.error && result.error.message) || "no routine in the answer")}</span>`;
    }
  } catch (err) {
    view.innerHTML = `<span class="e">${sEsc(err)}</span>`;
  }
}

// ---------------------------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------------------------

function initScrap() {
  document.getElementById("scrap-app").addEventListener("change", async (event) => {
    scrapState.appDir = event.target.value;
    await refreshScrapList();
  });
  document.getElementById("scrap-refresh").addEventListener("click", () => refreshScrapApps());
  document.getElementById("scrap-new").addEventListener("click", openCreate);
  document.getElementById("engine-start").addEventListener("click", async () => {
    const app = scrapState.apps.find((a) => a.appDir === scrapState.appDir);
    try {
      await sInvoke("start_engine", {
        root: (scrapState.engine && scrapState.engine.root) || "",
        origins: app && app.probeBaseUrl ? [app.probeBaseUrl] : [],
      });
      if (scrapState.engine && scrapState.engine.root && scrapState.engine.via === "derived-candidate") {
        await sInvoke("remember_engine_root", { root: scrapState.engine.root });
      }
      sToast("engine starting…");
      setTimeout(refreshEngine, 3000);
    } catch (err) {
      sToast(String(err), true);
    }
  });
  document.getElementById("engine-stop").addEventListener("click", async () => {
    await sInvoke("stop_engine");
    sToast("engine stopped");
    setTimeout(refreshEngine, 800);
  });

  document.querySelectorAll("#create-tabs button").forEach((b) =>
    b.addEventListener("click", () => setCreateTab(b.dataset.t))
  );
  document.getElementById("create-validate").addEventListener("click", validateDraft);
  document.getElementById("create-play").addEventListener("click", async () => {
    const file = await validateDraft();
    if (!file) return;
    document.getElementById("create-modal").classList.remove("show");
    await refreshScrapList();
    await playRoutine(file);
  });
  document.getElementById("create-save").addEventListener("click", async () => {
    const name = document.getElementById("create-name").value.trim();
    if (!name) {
      sToast("give it a name first", true);
      return;
    }
    try {
      const path = await sInvoke("save_routine", {
        appDir: scrapState.appDir,
        name,
        content: document.getElementById("create-json").value,
      });
      sToast("saved: " + path);
      await refreshScrapList();
    } catch (err) {
      sToast(String(err), true);
    }
  });
  document.querySelectorAll("[data-close-modal]").forEach((b) =>
    b.addEventListener("click", () => document.getElementById(b.dataset.closeModal).classList.remove("show"))
  );
  document.getElementById("assistant-compose").addEventListener("click", composePayload);
  document.getElementById("assistant-send").addEventListener("click", sendPayload);
  document.getElementById("lightbox").addEventListener("click", (event) =>
    event.currentTarget.classList.remove("show")
  );
  document.getElementById("assistant-open").addEventListener("click", () => openAssistant(null));
}

window.__npdevInitScrap = initScrap;
window.__npdevRefreshScrap = refreshScrapApps;
// The Monitor's "Explore this app" -- the one button that turns two tools into one flow.
window.__npdevOpenScrap = async (appDir) => {
  window.__npdevShowScreen("scrap");
  await refreshScrapApps(appDir);
};
