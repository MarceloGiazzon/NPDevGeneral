// The Verification tab -- VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27 Phase 3.
//
// Two views over one npdev-verification-panel.v1 document read fresh from `npdev verify --panel
// --json` on every entry. Nothing here decides what a state MEANS: the Kanban column is derived by
// the CLI (npdev_panel.kanban_column) exactly per PLAN S1.2, and this file mirrors those rules only
// so it can draw them -- a window and a terminal must not reach different conclusions about the same
// ledger. The staleness thresholds below are a single documented mirror of
// STALENESS_TO_TIMEDELTA in scripts/quality/cadence_state.py (same twin-pair discipline the repo
// already applies to the model.schema.json copies) and must be kept in sync when that table changes.

const { invoke: vInvoke } = window.__TAURI__.core;

// Mirrors STALENESS_TO_TIMEDELTA in scripts/quality/cadence_state.py. 1-move/7-days carry the same
// 7-day value by design; keep this list in lockstep with that table.
const STALENESS_MS = {
  "every-run": 5 * 60 * 1000,
  "1-wave": 2 * 24 * 60 * 60 * 1000,
  "1-move": 7 * 24 * 60 * 60 * 1000,
  "7-days": 7 * 24 * 60 * 60 * 1000,
  "30-days": 30 * 24 * 60 * 60 * 1000,
};

const COLUMN_LABEL = {
  "never-run": "NEVER RUN",
  failing: "FAILING",
  stale: "STALE",
  healthy: "HEALTHY",
};

const verifyState = {
  document: null,
  view: "kanban",
};

function esc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

// §1.2 derived state, reproduced from npdev_panel.kanban_column so the drawing matches the CLI's
// decision. `startedAt` null is treated as healthy-by-default (never a red you cannot explain).
function kanbanColumn(item, now) {
  const last = item.lastRun;
  if (!last) return "never-run";
  if (last.result === "failed") return "failing";
  const ms = STALENESS_MS[item.maxStaleness];
  if (ms && last.startedAt) {
    const started = new Date(last.startedAt).getTime();
    if (Number.isFinite(started) && now - started > ms) return "stale";
  }
  return "healthy";
}

function dur(value) {
  if (value == null) return "—";
  return value >= 60 ? `${(value / 60).toFixed(1)} min` : `${value}s`;
}

function relativeTime(iso) {
  if (!iso) return "—";
  const diff = Date.now() - new Date(iso).getTime();
  if (!Number.isFinite(diff)) return esc(iso);
  const min = Math.floor(diff / 60000);
  if (min < 1) return "just now";
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  return `${day}d ago`;
}

function resultBadge(result) {
  if (!result) return "";
  const label = esc((result || "").toUpperCase());
  // `skipped` / `not-applicable` render distinctly from passed -- never green (S1.1).
  const cls = result === "skipped" || result === "not-applicable" ? "skip" : esc(result);
  return `<span class="vbadge ${cls}">${label}</span>`;
}

function actionCell(item) {
  if (!item) return "";
  const report = item.lastRun && item.lastRun.reportPath;
  const reportBtn = report
    // Local report file -- opens with its default app (same open_folder the Monitor uses for logs).
    ? `<button class="btn ghost" data-report="${esc(report)}">View report</button>`
    : `<button class="btn ghost" disabled>View report</button>`;
  return `<div style="display:flex;gap:6px">${runButtonHtml(item)}${reportBtn}</div>`;
}

function subjectText(doc) {
  if (!doc || !doc.subject) return "";
  const s = doc.subject;
  return `Showing <strong>${esc(s.name)}</strong> · commit <code>${esc(s.commit || "—")}</code> · generated ${esc(doc.generatedAt || "—")}`;
}

// ---------------------------------------------------------------------------------------------
// Phase 5 executor: Run buttons. The ID is the only thing sent to the backend; npdev_executor.py
// resolves the command from the trusted panel document and runs it through the controlled runner.
// Manager-local only -- nothing here is a served web page control.
// ---------------------------------------------------------------------------------------------

function runStatus(message, bad) {
  const el = document.getElementById("verify-run-status");
  el.textContent = message || "";
  el.style.color = bad ? "var(--err)" : "var(--muted)";
}
runStatus("", false);

async function runItem(itemId) {
  runStatus(`re-running ${itemId} through the controlled runner… (this can take minutes)`, false);
  document.querySelectorAll(`[data-run-id="${itemId}"]`).forEach((b) => {
    b.disabled = true;
    b.textContent = "RUNNING…";
  });
  try {
    const result = await vInvoke("verification_run_item", { itemId, timeout_seconds: 0 });
    const ledger = result.ledger || {};
    const bad = result.result !== "passed";
    runStatus(
      `${result.itemId}: ${String(result.result || "?").toUpperCase()} in ` +
      `${dur(result.durationSeconds)}` +
      (ledger.recorded ? " · recorded in the ledger" : " · NOT recorded") +
      (result.controlled && result.controlled.stderr ? ` · ${esc(String(result.controlled.stderr).slice(0, 300))}` : ""),
      bad);
    refreshVerification();
  } catch (error) {
    runStatus(`run failed: ${error || "unknown error"}`, true);
    document.querySelectorAll(`[data-run-id="${itemId}"]`).forEach((b) => {
      b.disabled = false;
      b.textContent = "▶ RUN";
    });
  }
}

function runButtonHtml(item) {
  if (!item.runnable) return "";
  return `<button class="btn ghost run" data-run-id="${esc(item.id)}" title="${esc(item.command || "")}">▶ RUN</button>`;
}

function wireRunButtons(wrap) {
  wrap.querySelectorAll("[data-run-id]").forEach((btn) =>
    btn.addEventListener("click", () => runItem(btn.dataset.runId)));
}

function kanbanCard(item, now) {
  const col = kanbanColumn(item, now);
  const last = item.lastRun;
  return `
    <div class="vcard" data-column="${col}">
      <div class="vcard-head">
        <span class="vname">${esc(item.name)}</span>
        <span class="vcat">${esc(item.category || "")}</span>
      </div>
      <div class="vdesc" title="${esc(item.description || "")}">${esc(item.description || "")}</div>
      <div class="vmeta">
        ${resultBadge(last && last.result)}
        <span class="vwhen">${last ? relativeTime(last.startedAt) : "never"}</span>
        ${item.tier ? `<span class="vtier">T${esc(item.tier.slice(-1))}</span>` : ""}
        ${runButtonHtml(item)}
      </div>
      ${last && last.durationSeconds != null ? `<div class="vdur">last ${dur(last.durationSeconds)}</div>` : ""}
    </div>`;
}

function renderKanban() {
  const now = Date.now();
  const doc = verifyState.document;
  const wrap = document.getElementById("verify-kanban");
  if (!doc || !doc.items || doc.items.length === 0) {
    wrap.innerHTML = `<p class="status-line">Nothing declared yet in the verification panel.</p>`;
    return;
  }
  const columns = { "never-run": [], failing: [], stale: [], healthy: [] };
  for (const item of doc.items) {
    columns[kanbanColumn(item, now)].push(item);
  }
  wrap.innerHTML = `<div class="viewtoggle" hidden></div>`; // clean slate
  let html = "";
  for (const col of ["never-run", "failing", "stale", "healthy"]) {
    const items = columns[col];
    html += `
      <div class="vcol" data-column="${col}">
        <h3>${COLUMN_LABEL[col]} <span class="vcount">${items.length}</span></h3>
        <div class="vcol-body">${items.length ? items.map((i) => kanbanCard(i, now)).join("") : `<p class="vempty">—</p>`}</div>
      </div>`;
  }
  wrap.innerHTML = `<div class="vcols">${html}</div>`;
  wireRunButtons(wrap);
}

function renderTable() {
  const doc = verifyState.document;
  const wrap = document.getElementById("verify-table-wrap");
  if (!doc || !doc.items || doc.items.length === 0) {
    wrap.innerHTML = `<p class="status-line">Nothing declared yet in the verification panel.</p>`;
    wrap.hidden = false;
    return;
  }
  const now = Date.now();
  const rows = doc.items
    .map((item) => {
      const last = item.lastRun;
      const stat = item.typicalDurationSeconds;
      return `<tr>
        <td><span class="tdot" data-column="${kanbanColumn(item, now)}"></span>${esc(item.name)}</td>
        <td class="vdesc" title="${esc(item.description || "")}">${esc(item.description || "")}</td>
        <td>${esc(item.category || "—")}</td>
        <td class="mono">${esc(item.command || "—")}</td>
        <td>${stat ? `${dur(stat.p50)} <span class="dim">(p50)</span>` : "—"}</td>
        <td>${resultBadge(last && last.result)}</td>
        <td class="vwhen">${last ? relativeTime(last.startedAt) : "never"}</td>
        <td>${last && last.durationSeconds != null ? dur(last.durationSeconds) : "—"}</td>
        <td>${actionCell(item)}</td>
      </tr>`;
    })
    .join("");
  wrap.innerHTML = `<table class="vtable"><thead><tr>
    <th>Name</th><th>Description</th><th>Category</th><th>Command</th>
    <th>Typical</th><th>Last result</th><th>Last run</th><th>Last duration</th><th>Actions</th>
  </tr></thead><tbody>${rows}</tbody></table>`;
  wrap.querySelectorAll("[data-report]").forEach((btn) =>
    btn.addEventListener("click", () => vInvoke("open_folder", { path: btn.dataset.report })));
  wireRunButtons(wrap);
  wrap.hidden = false;
}

function toggleView(view) {
  verifyState.view = view;
  const kanban = document.getElementById("verify-kanban");
  const table = document.getElementById("verify-table-wrap");
  kanban.hidden = table.hidden = false;
  document.querySelectorAll("#verify-view button").forEach((b) =>
    b.classList.toggle("active", b.dataset.v === view));
  if (view === "table") {
    kanban.hidden = true;
    renderTable();
  } else {
    table.hidden = true;
    renderKanban();
  }
}

async function refreshVerification() {
  const subj = document.getElementById("verify-subject");
  subj.textContent = "reading the verification panel…";
  try {
    verifyState.document = await vInvoke("verification_panel");
    subj.textContent = "";
    document.getElementById("verify-subject").innerHTML = subjectText(verifyState.document);
    toggleView(verifyState.view);
  } catch (error) {
    subj.textContent = `unable to read the verification panel: ${error || "unknown error"}`;
  }
}

function initVerification() {
  document.getElementById("verify-refresh").addEventListener("click", refreshVerification);
  document.querySelectorAll("#verify-view button").forEach((btn) =>
    btn.addEventListener("click", () => toggleView(btn.dataset.v)));
  refreshVerification();
}

window.__npdevInitVerification = initVerification;
window.__npdevRefreshVerification = refreshVerification;