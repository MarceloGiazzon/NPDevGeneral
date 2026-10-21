// FLOW-1: the Analytics tab of the Executions operator page. Reads GET /api/executions/analytics
// (tenant-scoped server-side; naming another tenant is SUPERUSER only) and draws, per flow, a step
// diagram with live counts plus the ranked bottleneck table. All text goes in via textContent.
(function () {
  "use strict";

  var tabAttention = document.getElementById("tabAttention");
  var tabAnalytics = document.getElementById("tabAnalytics");
  var panelAttention = document.getElementById("panelAttention");
  var panelAnalytics = document.getElementById("panelAnalytics");
  var flowInput = document.getElementById("anFlow");
  var flowList = document.getElementById("anFlowList");
  var fromInput = document.getElementById("anFrom");
  var toInput = document.getElementById("anTo");
  var tenantInput = document.getElementById("anTenant");
  var loadButton = document.getElementById("anLoad");
  var messageBox = document.getElementById("anMessage");
  var summaryBox = document.getElementById("anSummary");
  var bottleneckBody = document.getElementById("anBottlenecks");
  var flowsBox = document.getElementById("anFlows");
  var loadedOnce = false;

  function select(which) {
    var analytics = which === "analytics";
    tabAttention.setAttribute("aria-selected", analytics ? "false" : "true");
    tabAnalytics.setAttribute("aria-selected", analytics ? "true" : "false");
    panelAttention.hidden = analytics;
    panelAnalytics.hidden = !analytics;
    if (analytics && !loadedOnce) load();
  }

  function setMessage(text, isError) {
    messageBox.textContent = text || "";
    messageBox.style.color = isError ? "var(--np-accent)" : "";
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = String(text);
    return node;
  }

  function cell(text) {
    return el("td", null, text);
  }

  // 1234 -> "1.2 s", 61000 -> "1 min 1 s"; null (no samples) -> "-".
  function ms(value) {
    if (value == null) return "-";
    if (value < 1000) return (Math.round(value * 10) / 10) + " ms";
    var seconds = value / 1000;
    if (seconds < 60) return (Math.round(seconds * 10) / 10) + " s";
    var minutes = Math.floor(seconds / 60);
    return minutes + " min " + Math.round(seconds - minutes * 60) + " s";
  }

  function query() {
    var parts = [];
    var flow = flowInput.value.trim();
    if (flow) parts.push("flow=" + encodeURIComponent(flow));
    if (fromInput.value) parts.push("from=" + encodeURIComponent(new Date(fromInput.value).getTime()));
    if (toInput.value) parts.push("to=" + encodeURIComponent(new Date(toInput.value).getTime()));
    var tenant = tenantInput.value.trim();
    if (tenant) parts.push("tenant=" + encodeURIComponent(tenant));
    return parts.length ? "?" + parts.join("&") : "";
  }

  function renderSummary(body) {
    summaryBox.innerHTML = "";
    var outcomes = body.byOutcome || {};
    var wait = body.humanWait || {};
    var resumed = wait.resumedWaits || {};
    [
      ["Executions", body.executionCount],
      ["OK", outcomes.OK || 0],
      ["Failed", outcomes.FAILED || 0],
      ["Waiting now", wait.currentlyWaitingExecutions || 0],
      ["Median wait", ms(resumed.medianMs)],
      ["Total wait", ms(resumed.totalMs)]
    ].forEach(function (pair) {
      var box = el("div");
      box.appendChild(el("div", null, pair[0]));
      box.appendChild(el("strong", null, pair[1]));
      summaryBox.appendChild(box);
    });
    if (body.truncated) {
      summaryBox.appendChild(el("div", null, "Showing the newest " + body.scanLimit + " executions only."));
    }
  }

  function renderBottlenecks(rows) {
    bottleneckBody.innerHTML = "";
    if (!rows.length) {
      var tr = el("tr");
      var td = cell("No completed steps in this window.");
      td.colSpan = 8;
      tr.appendChild(td);
      bottleneckBody.appendChild(tr);
      return;
    }
    var top = rows[0].elapsedMs && rows[0].elapsedMs.medianMs || 1;
    rows.forEach(function (row) {
      var tr = el("tr");
      tr.appendChild(cell(row.rank));
      tr.appendChild(cell(row.flowName));
      tr.appendChild(cell(row.stepName));
      tr.appendChild(cell(ms(row.elapsedMs.medianMs)));
      tr.appendChild(cell(ms(row.elapsedMs.p95Ms)));
      tr.appendChild(cell(ms(row.waitMs.medianMs)));
      tr.appendChild(cell(row.elapsedMs.count));
      var barCell = el("td", "bar-cell");
      var bar = el("span", "bar");
      bar.style.width = Math.max(2, Math.round(100 * row.elapsedMs.medianMs / top)) + "%";
      barCell.appendChild(bar);
      tr.appendChild(barCell);
      bottleneckBody.appendChild(tr);
    });
  }

  function stepBox(step, slowest) {
    var box = el("div", "step-box" + (step.stepName === slowest ? " slowest" : ""));
    box.appendChild(el("div", "name", step.stepName));
    box.appendChild(el("div", "counts", step.stepType));
    box.appendChild(el("div", "counts",
      step.reached + " reached - " + step.ok + " ok - " + step.failed + " failed" +
      (step.waiting ? " - " + step.waiting + " waiting" : "") +
      (step.retried ? " - " + step.retried + " retried" : "")));
    box.appendChild(el("div", "counts", "median " + ms(step.elapsedMs.medianMs) + " / p95 " + ms(step.elapsedMs.p95Ms)));
    if (step.waitMs && step.waitMs.count) {
      box.appendChild(el("div", "counts", "waited " + ms(step.waitMs.medianMs) + " median"));
    }
    return box;
  }

  function renderFlows(flows) {
    flowsBox.innerHTML = "";
    flowList.innerHTML = "";
    if (!flows.length) {
      flowsBox.appendChild(el("p", "hint", "No executions with a trace in this window."));
      return;
    }
    flows.forEach(function (flow) {
      var option = document.createElement("option");
      option.value = flow.flowName;
      flowList.appendChild(option);

      var card = el("div", "flow-card");
      var outcomes = flow.byOutcome || {};
      card.appendChild(el("h2", null, flow.flowName));
      card.appendChild(el("div", "counts",
        flow.executions + " executions - " + (outcomes.OK || 0) + " ok - " + (outcomes.FAILED || 0) + " failed - " +
        (outcomes.WAITING || 0) + " waiting - duration median " + ms(flow.durationMs.medianMs) +
        " / p95 " + ms(flow.durationMs.p95Ms) + (flow.slowestStep ? " - slowest step: " + flow.slowestStep : "")));
      var diagram = el("div", "diagram");
      (flow.steps || []).forEach(function (step, index) {
        if (index > 0) diagram.appendChild(el("span", "arrow", "→"));
        diagram.appendChild(stepBox(step, flow.slowestStep));
      });
      card.appendChild(diagram);
      flowsBox.appendChild(card);
    });
  }

  function load() {
    loadedOnce = true;
    setMessage("Loading ...", false);
    window.NPDevShell.api.get("/api/executions/analytics" + query()).then(function (body) {
      setMessage("Tenant " + body.tenant + (body.flow ? ", flow " + body.flow : "") + ".", false);
      renderSummary(body);
      renderBottlenecks(body.bottlenecks || []);
      renderFlows(body.flows || []);
    }).catch(function (error) {
      summaryBox.innerHTML = "";
      bottleneckBody.innerHTML = "";
      flowsBox.innerHTML = "";
      setMessage(error && error.status === 403
        ? "Only the Super User can read another tenant's analytics."
        : "Could not load analytics: " + (error && error.message), true);
    });
  }

  tabAttention.addEventListener("click", function () { select("attention"); });
  tabAnalytics.addEventListener("click", function () { select("analytics"); });
  loadButton.addEventListener("click", load);
  flowInput.addEventListener("keydown", function (event) { if (event.key === "Enter") load(); });
  if (window.location.hash === "#analytics") select("analytics");
})();
