// The DB Import/Export tab -- data mobility (ListaSementes.txt: "I want to have some options... to
// export and import. This options have to be present on the NPDev Manager. On a new tab."). Thin
// pipe, same standing rule as Packs/Verification: every decision (format handling, table scoping,
// the pre-import structure check, connection resolution) lives in `npdev db export`/`npdev db import`
// (shelled out verbatim by npdev.rs's run_db_export/run_db_import) -- nothing here re-derives any of
// it, this file only renders the `npdev-cli-result.v1` envelope those commands return.

const { invoke: dbioInvoke } = window.__TAURI__.core;

function dbioEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function dbioCurrentAppDir() {
  const picker = document.getElementById("dbio-app-picker");
  return picker && picker.value ? picker.value : null;
}

function dbioStatus(message, bad) {
  const el = document.getElementById("dbio-status");
  el.textContent = message || "";
  el.style.color = bad ? "var(--err)" : "var(--muted)";
}

// Same "populate from list_apps, keep the user's choice across a refresh, otherwise default to the
// most recently created app" shape as packs.js's refreshAppPicker.
async function dbioRefreshAppPicker() {
  const label = document.getElementById("dbio-app-label");
  const picker = document.getElementById("dbio-app-picker");
  let apps = [];
  try {
    apps = await dbioInvoke("list_apps");
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
  picker.innerHTML = apps.map((a) => `<option value="${dbioEsc(a.directory)}">${dbioEsc(a.name)}</option>`).join("");
  if (previous && apps.some((a) => a.directory === previous)) picker.value = previous;
  else picker.value = apps[apps.length - 1].directory;
}

async function dbioPickFolder(inputId, title) {
  const picked = await dbioInvoke("pick_db_transfer_folder", { title });
  if (picked) document.getElementById(inputId).value = picked;
}

function dbioRenderResult(outputElId, result) {
  const el = document.getElementById(outputElId);
  el.textContent = (result && result.output) || "";
  el.classList.toggle("dbio-output-err", !(result && result.ok));
  return !!(result && result.ok);
}

async function dbioRunExport() {
  const appDir = dbioCurrentAppDir();
  const outDir = document.getElementById("dbio-export-out").value.trim();
  const output = document.getElementById("dbio-export-output");
  if (!appDir) { dbioStatus("pick an app first", true); return; }
  if (!outDir) { dbioStatus("pick an output folder first", true); return; }
  output.textContent = "exporting…";
  dbioStatus("", false);
  try {
    const result = await dbioInvoke("db_export", {
      appDir,
      outDir,
      format: document.getElementById("dbio-export-format").value,
      scope: document.getElementById("dbio-export-scope").value,
      tables: null,
    });
    const ok = dbioRenderResult("dbio-export-output", result);
    if (!ok) dbioStatus("export finished with problems -- see output above", true);
  } catch (error) {
    output.textContent = String(error);
    dbioStatus(`export failed: ${error}`, true);
  }
}

async function dbioRunImport(apply) {
  const appDir = dbioCurrentAppDir();
  const inDir = document.getElementById("dbio-import-in").value.trim();
  const output = document.getElementById("dbio-import-output");
  if (!appDir) { dbioStatus("pick an app first", true); return; }
  if (!inDir) { dbioStatus("pick an input folder first", true); return; }
  output.textContent = apply ? "importing…" : "checking structure…";
  dbioStatus("", false);
  try {
    const result = await dbioInvoke("db_import", {
      appDir,
      inDir,
      format: document.getElementById("dbio-import-format").value,
      apply,
      force: document.getElementById("dbio-import-force").checked,
    });
    const ok = dbioRenderResult("dbio-import-output", result);
    if (!ok) {
      dbioStatus(apply ? "import finished with problems -- see output above"
                        : "at least one table is INCOMPATIBLE -- see output above", true);
    }
  } catch (error) {
    output.textContent = String(error);
    dbioStatus(`${apply ? "import" : "structure check"} failed: ${error}`, true);
  }
}

function initDbio() {
  document.getElementById("dbio-refresh-apps").addEventListener("click", dbioRefreshAppPicker);
  document.getElementById("dbio-export-pick").addEventListener("click", () =>
    dbioPickFolder("dbio-export-out", "Select export output folder"));
  document.getElementById("dbio-import-pick").addEventListener("click", () =>
    dbioPickFolder("dbio-import-in", "Select import input folder"));
  document.getElementById("dbio-export-run").addEventListener("click", dbioRunExport);
  document.getElementById("dbio-import-check").addEventListener("click", () => dbioRunImport(false));
  document.getElementById("dbio-import-run").addEventListener("click", () => dbioRunImport(true));
  dbioRefreshAppPicker();
}

window.__npdevInitDbio = initDbio;
