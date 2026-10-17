// PROC-7: operator recovery page. Lists the FAILED and STUCK executions of one tenant and offers
// Retry (FAILED), Cancel (WAITING_EVENT / STUCK) and Unstick (STUCK). Everything goes through
// NPDevShell.api so the shell's token handling applies; the endpoints themselves are SUPERUSER-gated
// server-side (ExecutionMonitorController), this page only decides what to offer.
(function () {
  "use strict";

  var TENANT_KEY = "npdev.ops.executions.tenant";
  var tenantInput = document.getElementById("opsTenant");
  var loadButton = document.getElementById("opsLoad");
  var messageBox = document.getElementById("opsMessage");
  var rowsBody = document.getElementById("opsRows");

  function readTenant() {
    try { return window.localStorage.getItem(TENANT_KEY) || ""; } catch (ignored) { return ""; }
  }

  function rememberTenant(value) {
    try { window.localStorage.setItem(TENANT_KEY, value); } catch (ignored) { /* private window */ }
  }

  function setMessage(text, isError) {
    messageBox.textContent = text || "";
    messageBox.style.color = isError ? "var(--np-accent)" : "";
  }

  function cell(text) {
    var td = document.createElement("td");
    td.textContent = text == null ? "" : String(text);
    return td;
  }

  function when(epochMs) {
    return epochMs ? new Date(epochMs).toLocaleString() : "";
  }

  // What each status can be offered. Mirrors the server: retry only FAILED; cancel only
  // WAITING_EVENT / STUCK (never RUNNING); unstick only STUCK.
  function actionsFor(item) {
    var actions = [];
    if (item.status === "FAILED") actions.push({ label: "Retry", path: item.retryPath, confirm: null });
    if (item.status === "STUCK" || item.status === "WAITING_EVENT") {
      actions.push({
        label: "Cancel", path: item.cancelPath,
        confirm: "Cancel this execution for good? An awaited event will no longer resume it."
      });
    }
    if (item.status === "STUCK") actions.push({ label: "Unstick", path: item.unstickPath, confirm: null });
    return actions;
  }

  function outcomeText(body) {
    if (!body) return "done";
    return (body.outcome || "") + (body.message ? " - " + body.message : "");
  }

  function runAction(action, item) {
    if (action.confirm && !window.confirm(action.confirm)) return;
    setMessage(action.label + " " + item.flowName + " ...", false);
    window.NPDevShell.api.post(action.path, {}).then(function (body) {
      setMessage(action.label + ": " + outcomeText(body), false);
      load();
    }).catch(function (error) {
      // A 409/404 carries the outcome body ({ outcome: NOT_RETRYABLE, message: ... }).
      setMessage(action.label + " failed: " + outcomeText(error && error.body || { message: error && error.message }), true);
      load();
    });
  }

  function render(items) {
    rowsBody.innerHTML = "";
    if (!items.length) {
      var empty = document.createElement("tr");
      var td = document.createElement("td");
      td.colSpan = 6;
      td.textContent = "Nothing needs attention for this tenant.";
      empty.appendChild(td);
      rowsBody.appendChild(empty);
      return;
    }
    items.forEach(function (item) {
      var tr = document.createElement("tr");
      tr.appendChild(cell(item.flowName));
      var statusCell = cell(item.status);
      statusCell.className = "status status-" + item.status;
      tr.appendChild(statusCell);
      tr.appendChild(cell([item.lastErrorCode, item.lastResumeErrorCode].filter(Boolean).join(" / ")));
      tr.appendChild(cell(when(item.updatedAtEpochMs)));
      tr.appendChild(cell(item.resumeAttemptCount));
      var actionsCell = document.createElement("td");
      var box = document.createElement("div");
      box.className = "actions";
      actionsFor(item).forEach(function (action) {
        var button = document.createElement("button");
        button.type = "button";
        button.textContent = action.label;
        button.addEventListener("click", function () { runAction(action, item); });
        box.appendChild(button);
      });
      actionsCell.appendChild(box);
      tr.appendChild(actionsCell);
      rowsBody.appendChild(tr);
    });
  }

  function load() {
    var tenant = tenantInput.value.trim();
    if (!tenant) {
      setMessage("Enter a tenant id.", true);
      return;
    }
    rememberTenant(tenant);
    window.NPDevShell.api.get("/api/executions/attention?tenant=" + encodeURIComponent(tenant)).then(function (body) {
      render((body && body.items) || []);
    }).catch(function (error) {
      rowsBody.innerHTML = "";
      setMessage(error && error.status === 403
        ? "Only the Super User can use this page."
        : "Could not load executions: " + (error && error.message), true);
    });
  }

  tenantInput.value = readTenant() || "default";
  loadButton.addEventListener("click", load);
  tenantInput.addEventListener("keydown", function (event) { if (event.key === "Enter") load(); });
  if (readTenant()) load();
})();
