// Data Transfer -- export/import/transfer/structure-check over com.finalexec.db.DataTransferMain
// (via `npdev db export|import|transfer|structure-check`). Every field on this screen maps to one
// flag the CLI already accepts; nothing here decides what's compatible or how a table is scoped --
// that comes back from the CLI, same standing rule as every other screen (see app.js's header).

const { invoke: dtInvoke } = window.__TAURI__.core;

const dtState = {
  sourceApps: [],
  targetApps: [],
  lastConfirmArgs: null, // set when a run comes back NEEDS_CONFIRMATION, so "Confirm & run" can re-invoke with confirm:true
};

function dtEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

// ---------------------------------------------------------------------------------------------
// App pickers -- fed by the Monitor's own discovery (same registry `npdev monitor scan` reads),
// not a Manager-only "apps I created" list: a data-mobility source/target can be ANY app this
// machine can already see, not only ones made here.
// ---------------------------------------------------------------------------------------------

async function dtRefreshAppPicker(role) {
  const picker = document.getElementById(`dt-${role}-app-picker`);
  const manual = `<option value="">(type a folder below)</option>`;
  let apps = [];
  try {
    const result = await dtInvoke("monitor_scan", { includeInfo: false });
    apps = (result.apps || []).filter((a) => a.status !== "not-an-app");
  } catch (err) {
    picker.innerHTML = `${manual}<option value="" disabled>(scan unavailable -- ${dtEsc(err)})</option>`;
    picker.value = "";
    return;
  }
  dtState[`${role}Apps`] = apps;
  picker.innerHTML =
    manual +
    apps.map((a) => `<option value="${dtEsc(a.appDir)}">${dtEsc(a.name || a.appDir)}</option>`).join("");
}

function dtWireAppPicker(role) {
  const picker = document.getElementById(`dt-${role}-app-picker`);
  const field = document.getElementById(`dt-${role}-app-dir`);
  picker.addEventListener("change", () => {
    if (!picker.value) return;
    field.value = picker.value;
  });
  field.addEventListener("input", () => {
    const apps = dtState[`${role}Apps`];
    picker.value = apps.some((a) => a.appDir === field.value.trim()) ? field.value.trim() : "";
  });
  document.getElementById(`dt-${role}-app-refresh`).addEventListener("click", async (event) => {
    event.target.disabled = true;
    try {
      await dtRefreshAppPicker(role);
    } finally {
      event.target.disabled = false;
    }
  });
}

// ---------------------------------------------------------------------------------------------
// Direction / field visibility -- one flag each, so the form only ever asks for what the chosen
// direction's CLI verb actually accepts.
// ---------------------------------------------------------------------------------------------

function dtApplyDirectionVisibility() {
  const direction = document.getElementById("dt-direction").value;
  const show = (id, visible) => {
    document.getElementById(id).hidden = !visible;
  };
  show("dt-target-group", direction !== "export");
  show("dt-bundle-group", direction === "import");
  show("dt-format-label", direction === "export" || direction === "import");
  show("dt-out-label", direction === "export");
  const tablesRelevant = direction !== "import"; // import's scope came from what was exported
  document.getElementById("dt-tables-mode").closest("label").hidden = !tablesRelevant;
  document.getElementById("dt-include-ddl").closest("label").hidden = direction === "export";
  dtApplyTablesVisibility();
  document.getElementById("dt-structure-check").hidden = true;
  document.getElementById("dt-result").hidden = true;
  document.getElementById("dt-error").hidden = true;
  dtState.lastConfirmArgs = null;
}

function dtApplyTablesVisibility() {
  const explicit = document.getElementById("dt-tables-mode").value === "explicit";
  document.getElementById("dt-tables-explicit-label").hidden = !explicit;
}

function dtTablesArg() {
  const mode = document.getElementById("dt-tables-mode").value;
  if (mode === "explicit") {
    return document.getElementById("dt-tables-explicit").value.trim() || "all";
  }
  return mode;
}

// ---------------------------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------------------------

function dtRenderError(message) {
  const el = document.getElementById("dt-error");
  el.hidden = false;
  el.textContent = message;
  document.getElementById("dt-structure-check").hidden = true;
  document.getElementById("dt-result").hidden = true;
}

function dtRenderStructureCheck(verdict, incompatibleReasons, compatibleReasons, onConfirm) {
  const panel = document.getElementById("dt-structure-check");
  const badge = document.getElementById("dt-verdict-badge");
  const list = document.getElementById("dt-reasons");
  const confirmBtn = document.getElementById("dt-confirm-btn");
  panel.hidden = false;
  badge.textContent = verdict;
  badge.className = `dt-badge dt-badge-${verdict.toLowerCase()}`;
  list.innerHTML = [...incompatibleReasons, ...compatibleReasons]
    .map((r) => `<li>${dtEsc(r)}</li>`)
    .join("");
  confirmBtn.hidden = !(verdict === "COMPATIBLE" && onConfirm);
  confirmBtn.onclick = onConfirm || null;
}

function dtRenderRowCounts(rowCountsByTable) {
  const panel = document.getElementById("dt-result");
  const summary = document.getElementById("dt-result-summary");
  const list = document.getElementById("dt-row-counts");
  panel.hidden = false;
  const entries = Object.entries(rowCountsByTable || {});
  summary.textContent = entries.length
    ? `${entries.length} table(s) affected.`
    : "No rows moved.";
  list.innerHTML = entries.map(([table, count]) => `<li>${dtEsc(table)}: ${dtEsc(count)} row(s)</li>`).join("");
}

async function dtRunExport() {
  return dtInvoke("data_mobility_export", {
    appDir: document.getElementById("dt-source-app-dir").value.trim() || null,
    url: document.getElementById("dt-source-url").value.trim() || null,
    dbUser: null,
    dbPassword: null,
    format: document.getElementById("dt-format").value,
    tables: dtTablesArg(),
    out: document.getElementById("dt-out-dir").value.trim(),
  });
}

async function dtRunImport(confirm) {
  return dtInvoke("data_mobility_import", {
    appDir: document.getElementById("dt-target-app-dir").value.trim() || null,
    url: document.getElementById("dt-target-url").value.trim() || null,
    dbUser: null,
    dbPassword: null,
    bundle: document.getElementById("dt-bundle-dir").value.trim(),
    format: document.getElementById("dt-format").value,
    includeDdl: document.getElementById("dt-include-ddl").checked,
    confirm: !!confirm,
  });
}

async function dtRunTransfer(confirm) {
  return dtInvoke("data_mobility_transfer", {
    sourceApp: document.getElementById("dt-source-app-dir").value.trim() || null,
    sourceUrl: document.getElementById("dt-source-url").value.trim() || null,
    sourceDbUser: null,
    sourceDbPassword: null,
    targetApp: document.getElementById("dt-target-app-dir").value.trim() || null,
    targetUrl: document.getElementById("dt-target-url").value.trim() || null,
    targetDbUser: null,
    targetDbPassword: null,
    tables: dtTablesArg(),
    includeDdl: document.getElementById("dt-include-ddl").checked,
    confirm: !!confirm,
  });
}

async function dtRunStructureCheck() {
  return dtInvoke("data_mobility_structure_check", {
    sourceApp: document.getElementById("dt-source-app-dir").value.trim() || null,
    sourceUrl: document.getElementById("dt-source-url").value.trim() || null,
    sourceDbUser: null,
    sourceDbPassword: null,
    targetApp: document.getElementById("dt-target-app-dir").value.trim() || null,
    targetUrl: document.getElementById("dt-target-url").value.trim() || null,
    targetDbUser: null,
    targetDbPassword: null,
    tables: dtTablesArg(),
    includeDdl: document.getElementById("dt-include-ddl").checked,
  });
}

async function dtHandleImportOrTransferResult(result, rerun) {
  dtRenderStructureCheck(
    result.verdict,
    result.incompatibleReasons || [],
    result.compatibleReasons || [],
    result.outcome === "NEEDS_CONFIRMATION"
      ? async () => {
          const btn = document.getElementById("dt-confirm-btn");
          btn.disabled = true;
          try {
            const confirmed = await rerun(true);
            dtHandleImportOrTransferResult(confirmed, rerun);
          } catch (err) {
            dtRenderError(String(err));
          } finally {
            btn.disabled = false;
          }
        }
      : null
  );
  if (result.outcome === "IMPORTED" || result.outcome === "TRANSFERRED") {
    dtRenderRowCounts(result.rowCountsByTable);
  }
}

async function dtRun() {
  const btn = document.getElementById("dt-run-btn");
  const direction = document.getElementById("dt-direction").value;
  document.getElementById("dt-error").hidden = true;
  document.getElementById("dt-structure-check").hidden = true;
  document.getElementById("dt-result").hidden = true;
  btn.disabled = true;
  try {
    if (direction === "export") {
      const result = await dtRunExport();
      dtRenderRowCounts(result.rowCountsByTable);
    } else if (direction === "import") {
      const result = await dtRunImport(false);
      await dtHandleImportOrTransferResult(result, (confirm) => dtRunImport(confirm));
    } else if (direction === "transfer") {
      const result = await dtRunTransfer(false);
      await dtHandleImportOrTransferResult(result, (confirm) => dtRunTransfer(confirm));
    } else if (direction === "structure-check") {
      const result = await dtRunStructureCheck();
      dtRenderStructureCheck(result.verdict, result.incompatibleReasons || [], result.compatibleReasons || [], null);
    }
  } catch (err) {
    dtRenderError(String(err));
  } finally {
    btn.disabled = false;
  }
}

// ---------------------------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------------------------

document.getElementById("dt-direction").addEventListener("change", dtApplyDirectionVisibility);
document.getElementById("dt-tables-mode").addEventListener("change", dtApplyTablesVisibility);
document.getElementById("dt-run-btn").addEventListener("click", dtRun);
dtWireAppPicker("source");
dtWireAppPicker("target");

window.__npdevRefreshDataTransfer = function refreshDataTransfer() {
  dtRefreshAppPicker("source");
  dtRefreshAppPicker("target");
};

dtApplyDirectionVisibility();
