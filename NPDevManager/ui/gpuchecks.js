// GPU Checks tab (Track B, G5) -- data-rule sweep with GPU acceleration and an exact CPU twin.
// Thin pipe, same standing rule as DB Import/Export: engine choice, packing, GPU/CPU dispatch,
// host checks and the run record's own shape all live in `npdev gpu-check` (NPDevCli/
// npdev_gpu_check.py, shelled out verbatim by npdev.rs's run_gpu_check_*) -- nothing here
// re-derives any of it, this file only renders the `npdev-cli-result.v1` envelope those commands
// return. The headline use: "before I deploy this model change, which existing rows would break
// the new rules?" -- answered by `npdev gpu-check plan`, CLI-only for now (not yet in this tab).

const { invoke: gpuChecksInvoke } = window.__TAURI__.core;

let gpuChecksLatestShow = null; // the last `show` result, for the details pane's buttons

function gpuChecksEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function gpuChecksCurrentAppDir() {
  const picker = document.getElementById("gpuchecks-app-picker");
  return picker && picker.value ? picker.value : null;
}

function gpuChecksStatus(message, bad) {
  const el = document.getElementById("gpuchecks-status");
  el.textContent = message || "";
  el.classList.toggle("err", !!bad);
}

// Same "populate from list_apps, keep the user's choice across a refresh, otherwise default to the
// most recently created app" shape as dbio.js's dbioRefreshAppPicker.
async function gpuChecksRefreshAppPicker() {
  const label = document.getElementById("gpuchecks-app-label");
  const picker = document.getElementById("gpuchecks-app-picker");
  let apps = [];
  try {
    apps = await gpuChecksInvoke("list_apps");
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
  picker.innerHTML = apps.map((a) => `<option value="${gpuChecksEsc(a.directory)}">${gpuChecksEsc(a.name)}</option>`).join("");
  if (previous && apps.some((a) => a.directory === previous)) picker.value = previous;
  else picker.value = apps[apps.length - 1].directory;
}

/// Cross-screen jump idiom (D9, same as the Monitor's "Explore this app" -> `__npdevOpenScrap`):
/// the Monitor's "GPU Check Routines" card action calls this to switch tabs with the app preselected.
async function gpuChecksOpenFor(appDir) {
  window.__npdevShowScreen("gpuchecks");
  await gpuChecksRefreshAppPicker();
  const picker = document.getElementById("gpuchecks-app-picker");
  if (picker && Array.from(picker.options).some((o) => o.value === appDir)) {
    picker.value = appDir;
  }
  await gpuChecksRefreshAll();
}
window.__npdevOpenGpuChecks = gpuChecksOpenFor;

function gpuChecksEmptyState(show) {
  document.getElementById("gpuchecks-empty").hidden = !show;
  document.getElementById("gpuchecks-columns").hidden = show;
}

async function gpuChecksRefreshList() {
  const appDir = gpuChecksCurrentAppDir();
  const tbody = document.getElementById("gpuchecks-checks-body");
  if (!appDir) { tbody.innerHTML = ""; return; }
  try {
    const result = await gpuChecksInvoke("gpu_check_list", { appDir });
    if (!result.ok) {
      gpuChecksEmptyState(true);
      document.getElementById("gpuchecks-empty-detail").textContent = result.detail || "";
      return;
    }
    gpuChecksEmptyState(false);
    const checks = result.checks || [];
    tbody.innerHTML = checks.map((c) => `
      <tr>
        <td>${gpuChecksEsc(c.concept)}</td>
        <td>${gpuChecksEsc(c.id)}</td>
        <td>${gpuChecksEsc(c.kind)}</td>
        <td><span class="gpuchecks-engine-tag ${c.engine === "gpu" ? "gpu" : ""}">${gpuChecksEsc(c.engine)}</span></td>
      </tr>`).join("") || `<tr><td colspan="4" class="gpuchecks-samples">No checks in this app's manifest.</td></tr>`;
  } catch (error) {
    gpuChecksEmptyState(true);
    document.getElementById("gpuchecks-empty-detail").textContent = String(error);
  }
}

function gpuChecksFormatRow(run) {
  const engine = run.engine || {};
  const verify = run.verify || {};
  const total = run.timingsMs && run.timingsMs.total ? `${Math.round(run.timingsMs.total)} ms` : "";
  const verifyMark = verify.mode === "none" ? "—" : (verify.matched ? "✓" : "✗ MISMATCH");
  return `
    <tr class="gpuchecks-row-clickable" data-run="${gpuChecksEsc(run.runId)}">
      <td>${gpuChecksEsc(run.startedAt)}</td>
      <td><span class="gpuchecks-engine-tag ${engine.used === "gpu" ? "gpu" : ""}">${gpuChecksEsc(engine.used)}</span> ${gpuChecksEsc(engine.device || "")}</td>
      <td>${gpuChecksEsc(run.rowsChecked)}</td>
      <td class="${run.violations > 0 ? "gpuchecks-fail" : "gpuchecks-pass"}">${gpuChecksEsc(run.violations)}</td>
      <td class="${verify.matched === false ? "gpuchecks-fail" : ""}">${verifyMark}</td>
      <td>${total}</td>
    </tr>`;
}

async function gpuChecksRefreshHistory() {
  const appDir = gpuChecksCurrentAppDir();
  const tbody = document.getElementById("gpuchecks-runs-body");
  if (!appDir) { tbody.innerHTML = ""; return; }
  try {
    const result = await gpuChecksInvoke("gpu_check_history", { appDir });
    if (!result.ok) { tbody.innerHTML = ""; return; }
    const runs = result.runs || [];
    tbody.innerHTML = runs.map(gpuChecksFormatRow).join("")
      || `<tr><td colspan="6" class="gpuchecks-samples">No runs yet -- press ▶ RUN above.</td></tr>`;
    tbody.querySelectorAll("[data-run]").forEach((row) =>
      row.addEventListener("click", () => gpuChecksShowRun(row.dataset.run)));
  } catch {
    tbody.innerHTML = "";
  }
}

async function gpuChecksRun() {
  const appDir = gpuChecksCurrentAppDir();
  if (!appDir) { gpuChecksStatus("pick an app first", true); return; }
  const engine = document.getElementById("gpuchecks-engine").value;
  const verify = document.getElementById("gpuchecks-verify").checked;
  const btn = document.getElementById("gpuchecks-run-btn");
  btn.disabled = true;
  gpuChecksStatus(engine === "gpu" || engine === "auto" ? "exporting, packing, dispatching to GPU…" : "exporting, packing, checking…", false);
  try {
    const result = await gpuChecksInvoke("gpu_check_run", { appDir, engine, verify });
    if (!result.ok && result.exitCode === 2) {
      gpuChecksStatus("GPU result disagreed with the CPU twin -- see the run's details.", true);
    } else if (result.result) {
      const r = result.result;
      gpuChecksStatus(`run ${r.runId}: ${r.violations} violation(s) across ${r.rowsChecked} row(s), engine=${r.engine.used}`, r.violations > 0);
    } else {
      gpuChecksStatus(result.detail || "run failed", true);
    }
    await gpuChecksRefreshHistory();
    await gpuChecksRefreshList();
    if (result.result) await gpuChecksShowRun(result.result.runId);
  } catch (error) {
    gpuChecksStatus(`run failed: ${error}`, true);
  } finally {
    btn.disabled = false;
  }
}

function gpuChecksRenderDetails(result) {
  gpuChecksLatestShow = result;
  const pane = document.getElementById("gpuchecks-details");
  pane.hidden = false;
  document.getElementById("gpuchecks-details-title").textContent =
    `Run ${result.runId} -- ${result.violations} violation(s), engine=${result.engine.used}` +
    (result.engine.device ? ` (${result.engine.device})` : "");

  const rows = [];
  for (const concept of result.concepts || []) {
    for (const check of concept.checks || []) {
      const cls = check.status === "failed" ? "gpuchecks-fail" : check.status === "skipped" ? "gpuchecks-skip" : "gpuchecks-pass";
      const samples = (check.sampleIds || []).slice(0, 10).join(", ");
      rows.push(`
        <tr>
          <td>${gpuChecksEsc(concept.concept)}</td>
          <td>${gpuChecksEsc(check.id)}</td>
          <td class="${cls}">${gpuChecksEsc(check.status)}</td>
          <td>${gpuChecksEsc(check.violations)}</td>
          <td class="gpuchecks-samples">${gpuChecksEsc(samples || check.skipReason || "")}</td>
        </tr>`);
    }
  }
  document.getElementById("gpuchecks-details-body").innerHTML = rows.join("")
    || `<tr><td colspan="5" class="gpuchecks-samples">No per-check data for this run.</td></tr>`;

  const packLines = [];
  for (const concept of result.concepts || []) {
    for (const pack of concept.packs || []) {
      packLines.push(`${concept.concept} / ${pack.packId}: ${pack.engine}${pack.fallbackReason ? " (" + pack.fallbackReason + ")" : ""}`);
    }
  }
  document.getElementById("gpuchecks-details-packs").textContent = packLines.join("  ·  ");

  document.getElementById("gpuchecks-shader-pre").hidden = true;
  document.getElementById("gpuchecks-prompt-pre").hidden = true;
}

async function gpuChecksShowRun(runId) {
  const appDir = gpuChecksCurrentAppDir();
  if (!appDir) return;
  try {
    const result = await gpuChecksInvoke("gpu_check_show", { appDir, runId });
    if (result.ok && result.result) gpuChecksRenderDetails(result.result);
  } catch (error) {
    gpuChecksStatus(`could not load run ${runId}: ${error}`, true);
  }
}

async function gpuChecksDownloadBundle() {
  if (!gpuChecksLatestShow) return;
  const appDir = gpuChecksCurrentAppDir();
  const folder = await gpuChecksInvoke("pick_db_transfer_folder", { title: "Choose where to save the bundle" });
  if (!folder) return;
  const outZip = `${folder.replace(/[\\/]+$/, "")}/gpu-check-${gpuChecksLatestShow.runId}.zip`;
  try {
    const result = await gpuChecksInvoke("gpu_check_bundle", { appDir, runId: gpuChecksLatestShow.runId, outZip });
    gpuChecksStatus(result.ok ? `bundle saved: ${result.path || outZip}` : (result.detail || "bundle failed"), !result.ok);
  } catch (error) {
    gpuChecksStatus(`bundle failed: ${error}`, true);
  }
}

async function gpuChecksCopyPrompt() {
  if (!gpuChecksLatestShow) return;
  const pre = document.getElementById("gpuchecks-prompt-pre");
  const text = gpuChecksLatestShow.aiPrompt || "(no AI prompt recorded for this run)";
  pre.textContent = text;
  pre.hidden = false;
  document.getElementById("gpuchecks-shader-pre").hidden = true;
  try {
    await navigator.clipboard.writeText(text);
    gpuChecksStatus("AI prompt copied to clipboard.", false);
  } catch {
    gpuChecksStatus("Could not reach the clipboard -- the prompt is shown below.", false);
  }
}

function gpuChecksViewShader() {
  if (!gpuChecksLatestShow) return;
  const shaders = gpuChecksLatestShow.shaders || {};
  const names = Object.keys(shaders);
  const pre = document.getElementById("gpuchecks-shader-pre");
  if (names.length === 0) {
    pre.textContent = "(no shader text recorded for this run)";
  } else {
    pre.textContent = names.map((name) => `// ${name}\n${shaders[name]}`).join("\n\n");
  }
  pre.hidden = false;
  document.getElementById("gpuchecks-prompt-pre").hidden = true;
}

async function gpuChecksRefreshAll() {
  await Promise.all([gpuChecksRefreshList(), gpuChecksRefreshHistory()]);
}
window.__npdevRefreshGpuChecks = gpuChecksRefreshAll;

function initGpuChecks() {
  document.getElementById("gpuchecks-refresh-apps").addEventListener("click", async () => {
    await gpuChecksRefreshAppPicker();
    await gpuChecksRefreshAll();
  });
  document.getElementById("gpuchecks-app-picker").addEventListener("change", gpuChecksRefreshAll);
  document.getElementById("gpuchecks-run-btn").addEventListener("click", gpuChecksRun);
  document.getElementById("gpuchecks-download-bundle").addEventListener("click", gpuChecksDownloadBundle);
  document.getElementById("gpuchecks-copy-prompt").addEventListener("click", gpuChecksCopyPrompt);
  document.getElementById("gpuchecks-view-shader").addEventListener("click", gpuChecksViewShader);
  gpuChecksRefreshAppPicker().then(gpuChecksRefreshAll);
}

window.__npdevInitGpuChecks = initGpuChecks;
