// The Evals tab -- Wave 7.2 (NPDEV_FEATURE_PLAN_2026-09-24.md). Thin renderer over `npdev eval *`
// (7.1): list-scenarios for the table's rows, list-runs for the run selector, run/compare/stop for
// the three buttons. No decision here that the CLI does not already make -- this only draws
// `results.json`'s own verdict field and the underlying schema-validation/beta-gate report's own
// stage list, never reclassifies either.
//
// Honesty note for whoever extends this: there is no live multi-turn LLM transcript to show yet --
// `npdev eval run` is the headless (7.1) engine, a deterministic pipeline (schema check, or
// normalize->generate->build->boot->smoke), not an interactive AI loop. The row-detail view below is
// labelled "Stage detail" rather than "transcript" for that reason. `NPDevManager/src/ai_loop.rs`
// (the Manager's separate, existing real AI loop) is where a genuine prompt/response transcript
// would come from, if this tab is ever pointed at that engine instead.

const { invoke: eInvoke } = window.__TAURI__.core;

const evalsState = {
  scenarios: [],       // from eval_list_scenarios
  runs: [],            // from eval_list_runs, newest first
  selectedRunId: null,  // which run's verdicts populate the table
  compareA: null,
  compareB: null,
  running: false,
  selectedScenario: null,
};

function eEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function eDur(ms) {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  return s >= 60 ? `${(s / 60).toFixed(1)}min` : `${s.toFixed(1)}s`;
}

function eRelativeTime(iso) {
  if (!iso) return "—";
  const diff = Date.now() - new Date(iso).getTime();
  if (!Number.isFinite(diff)) return eEsc(iso);
  const min = Math.floor(diff / 60000);
  if (min < 1) return "just now";
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  return `${Math.floor(hr / 24)}d ago`;
}

function evalsStatus(message, cls) {
  const el = document.getElementById("evals-status");
  el.textContent = message || "";
  el.className = "status-line" + (cls ? ` ${cls}` : "");
}

function verdictBadge(verdict) {
  if (!verdict) return `<span class="evals-badge unknown">NEVER RUN</span>`;
  const cls = verdict.toLowerCase();
  return `<span class="evals-badge ${eEsc(cls)}">${eEsc(verdict.replace(/_/g, " "))}</span>`;
}

function currentRunScenarios() {
  const run = evalsState.runs.find((r) => r.runId === evalsState.selectedRunId);
  return run ? run.scenarios || {} : {};
}

// `eval_list_runs` only returns aggregate counts (schema npdev-eval-run-list.v1) -- the per-scenario
// verdicts for whichever run is selected come from that run's own results.json, loaded lazily and
// cached on the run object itself so switching the selector twice does not re-read the file.
async function ensureRunDetailLoaded(runId) {
  const run = evalsState.runs.find((r) => r.runId === runId);
  if (!run || run.scenarios || !run.resultsPath) return;
  try {
    const report = await eInvoke("read_json_file", { path: run.resultsPath });
    const byId = {};
    for (const s of report.scenarios || []) byId[s.scenario] = s;
    run.scenarios = byId;
  } catch (error) {
    run.scenarios = {};
  }
}

function renderTable() {
  const wrap = document.getElementById("evals-table-wrap");
  if (!evalsState.scenarios.length) {
    wrap.innerHTML = `<p class="status-line">No scenarios discovered under NPDevSamples/ai-scenarios.</p>`;
    return;
  }
  const byId = currentRunScenarios();
  const rows = evalsState.scenarios
    .map((sc) => {
      const r = byId[sc.scenario];
      return `<tr data-scenario="${eEsc(sc.scenario)}" class="${evalsState.selectedScenario === sc.scenario ? "selected" : ""}">
        <td class="mono">${eEsc(sc.scenario)}${sc.heavy ? '<span class="evals-heavy" title="Needs a real generate+build+boot to verify">BOOT</span>' : ""}</td>
        <td>${eEsc(sc.kind || "—")}</td>
        <td class="mono">${eEsc(sc.expectedOutcome || "—")}${sc.expectedFailureStage ? ` / ${eEsc(sc.expectedFailureStage)}` : ""}</td>
        <td>${verdictBadge(r && r.verdict)}</td>
        <td>${r ? eDur(r.durationMs) : "—"}</td>
        <td><button class="btn ghost run-one" data-scenario="${eEsc(sc.scenario)}" ${evalsState.running ? "disabled" : ""}>&#9654; RUN</button></td>
      </tr>`;
    })
    .join("");
  wrap.innerHTML = `<table class="evals-table"><thead><tr>
    <th>Scenario</th><th>Kind</th><th>Expected</th><th>Last verdict</th><th>Duration</th><th>Actions</th>
  </tr></thead><tbody>${rows}</tbody></table>`;
  wrap.querySelectorAll("tr[data-scenario]").forEach((tr) =>
    tr.addEventListener("click", (ev) => {
      if (ev.target.closest("button")) return;
      showScenarioDetail(tr.dataset.scenario);
    }));
  wrap.querySelectorAll(".run-one").forEach((btn) =>
    btn.addEventListener("click", (ev) => {
      ev.stopPropagation();
      runEval(btn.dataset.scenario);
    }));
}

function renderRunSelectors() {
  const options = evalsState.runs
    .map((r) => `<option value="${eEsc(r.runId)}">${eEsc(r.runId)}${r.stillRunning ? " (running…)" : ""} — ${r.passed}/${r.scenarioCount} passed</option>`)
    .join("");
  for (const id of ["evals-run-picker", "evals-compare-a", "evals-compare-b"]) {
    const sel = document.getElementById(id);
    const previous = sel.value;
    sel.innerHTML = `<option value="">—</option>${options}`;
    if (previous && evalsState.runs.some((r) => r.runId === previous)) sel.value = previous;
  }
  if (!evalsState.selectedRunId && evalsState.runs.length) {
    evalsState.selectedRunId = evalsState.runs[0].runId;
  }
  document.getElementById("evals-run-picker").value = evalsState.selectedRunId || "";
}

async function showScenarioDetail(scenarioId) {
  evalsState.selectedScenario = scenarioId;
  renderTable();
  const panel = document.getElementById("evals-detail");
  const byId = currentRunScenarios();
  const entry = byId[scenarioId];
  if (!entry) {
    panel.innerHTML = `<h3>${eEsc(scenarioId)}</h3><p class="status-line">Not run yet in the selected run.</p>`;
    panel.hidden = false;
    return;
  }
  panel.innerHTML = `<h3>${eEsc(scenarioId)} — ${verdictBadge(entry.verdict)}</h3>
    <p class="status-line">${entry.firstError ? eEsc(entry.firstError) : "Matched its expected outcome."}</p>
    <div class="evals-stage-list" id="evals-stage-list"></div>
    <pre id="evals-raw-detail">loading detail report…</pre>`;
  panel.hidden = false;
  if (!entry.detailReportPath) {
    document.getElementById("evals-raw-detail").textContent = "(no detail report path recorded for this scenario)";
    return;
  }
  try {
    const detailReport = await eInvoke("read_json_file", { path: entry.detailReportPath });
    const stages = (detailReport.scenarios || [])
      .find((s) => s.scenarioId === scenarioId)?.stages;
    const stageList = document.getElementById("evals-stage-list");
    if (stages && stages.length) {
      stageList.innerHTML = stages
        .map((st) => `<div class="evals-stage" data-status="${eEsc(st.status)}">
          <span class="evals-stage-name">${eEsc(st.name)}</span>
          <span class="mono">${eEsc(st.status)}</span>
          <span class="evals-stage-message">${eEsc(st.message || "")}</span>
        </div>`)
        .join("");
    } else {
      // The light (schema-only) path has no `stages` array -- show its two checks instead.
      const schemaEntry = (detailReport.scenarios || []).find((s) => s.scenarioId === scenarioId);
      if (schemaEntry) {
        stageList.innerHTML = ["schemaValidation", "semanticValidation"]
          .filter((k) => schemaEntry[k])
          .map((k) => `<div class="evals-stage" data-status="${eEsc(schemaEntry[k].status)}">
            <span class="evals-stage-name">${eEsc(k)}</span>
            <span class="mono">${eEsc(schemaEntry[k].status)}</span>
          </div>`)
          .join("");
      }
    }
    document.getElementById("evals-raw-detail").textContent = JSON.stringify(detailReport, null, 2);
  } catch (error) {
    document.getElementById("evals-raw-detail").textContent = `could not read detail report: ${error || "unknown error"}`;
  }
}

async function refreshScenariosAndRuns() {
  const [scenarioReport, runReport] = await Promise.all([
    eInvoke("eval_list_scenarios"),
    eInvoke("eval_list_runs"),
  ]);
  evalsState.scenarios = scenarioReport.scenarios || [];
  evalsState.runs = (runReport.runs || []).map((r) => ({ ...r }));
  renderRunSelectors();
  if (evalsState.selectedRunId) await ensureRunDetailLoaded(evalsState.selectedRunId);
  renderTable();
}

async function runEval(scenario) {
  const runId = "eval-" + new Date().toISOString().replace(/[-:.]/g, "").replace("T", "-").slice(0, 15);
  evalsState.running = true;
  evalsState.selectedRunId = runId;
  document.getElementById("evals-stop").disabled = false;
  document.getElementById("evals-run-all").disabled = true;
  evalsStatus(scenario ? `running ${scenario}…` : "running every scenario… this can take several minutes; Stop cancels before the next one starts.", "");
  renderTable();
  try {
    const report = await eInvoke("eval_run", { scenario: scenario || null, runId });
    evalsStatus(
      `${report.passed}/${report.scenarioCount} passed` + (report.cancelled ? " (cancelled)" : ""),
      report.ok ? "ok" : "err");
  } catch (error) {
    evalsStatus(`eval run failed: ${error || "unknown error"}`, "err");
  } finally {
    evalsState.running = false;
    document.getElementById("evals-stop").disabled = true;
    document.getElementById("evals-run-all").disabled = false;
    await refreshScenariosAndRuns();
  }
}

async function stopEval() {
  if (!evalsState.selectedRunId) return;
  try {
    await eInvoke("eval_stop", { runId: evalsState.selectedRunId });
    evalsStatus("cancel requested — it takes effect before the next scenario starts.", "");
  } catch (error) {
    evalsStatus(`stop failed: ${error || "unknown error"}`, "err");
  }
}

async function runCompare() {
  const a = document.getElementById("evals-compare-a").value;
  const b = document.getElementById("evals-compare-b").value;
  const summary = document.getElementById("evals-compare-summary");
  if (!a || !b) {
    summary.innerHTML = `<span>Pick two runs to compare.</span>`;
    return;
  }
  try {
    const report = await eInvoke("eval_compare", { runA: a, runB: b });
    summary.innerHTML = `
      <span class="regressed">Regressed: <b>${report.regressed.length}</b>${report.regressed.length ? ` (${report.regressed.map(eEsc).join(", ")})` : ""}</span>
      <span class="improved">Improved: <b>${report.improved.length}</b>${report.improved.length ? ` (${report.improved.map(eEsc).join(", ")})` : ""}</span>
      <span>Unchanged: <b>${report.unchanged}</b></span>
      ${report.onlyInA.length ? `<span>Only in A: <b>${report.onlyInA.length}</b></span>` : ""}
      ${report.onlyInB.length ? `<span>Only in B: <b>${report.onlyInB.length}</b></span>` : ""}`;
  } catch (error) {
    summary.innerHTML = `<span class="regressed">compare failed: ${eEsc(error || "unknown error")}</span>`;
  }
}

function initEvals() {
  document.getElementById("evals-refresh").addEventListener("click", refreshScenariosAndRuns);
  document.getElementById("evals-run-all").addEventListener("click", () => runEval(null));
  document.getElementById("evals-stop").addEventListener("click", stopEval);
  document.getElementById("evals-run-picker").addEventListener("change", async (ev) => {
    evalsState.selectedRunId = ev.target.value || null;
    if (evalsState.selectedRunId) await ensureRunDetailLoaded(evalsState.selectedRunId);
    renderTable();
  });
  document.getElementById("evals-compare-btn").addEventListener("click", runCompare);
  refreshScenariosAndRuns();
}

window.__npdevInitEvals = initEvals;
window.__npdevRefreshEvals = refreshScenariosAndRuns;
