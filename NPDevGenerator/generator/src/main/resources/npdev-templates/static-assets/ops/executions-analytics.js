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
      [NPDevI18n.t("shell.nav.executions", "Executions"), body.executionCount],
      [NPDevI18n.t("shell.ops.summaryOk", "OK"), outcomes.OK || 0],
      [NPDevI18n.t("shell.ops.summaryFailed", "Failed"), outcomes.FAILED || 0],
      [NPDevI18n.t("shell.ops.summaryWaiting", "Waiting now"), wait.currentlyWaitingExecutions || 0],
      [NPDevI18n.t("shell.ops.medianWait", "Median wait"), ms(resumed.medianMs)],
      [NPDevI18n.t("shell.ops.totalWait", "Total wait"), ms(resumed.totalMs)]
    ].forEach(function (pair) {
      var box = el("div");
      box.appendChild(el("div", null, pair[0]));
      box.appendChild(el("strong", null, pair[1]));
      summaryBox.appendChild(box);
    });
    if (body.truncated) {
      summaryBox.appendChild(el("div", null, NPDevI18n.t("shell.ops.truncated", "Showing the newest {count} executions only.", { count: body.scanLimit })));
    }
  }

  function renderBottlenecks(rows) {
    bottleneckBody.innerHTML = "";
    if (!rows.length) {
      var tr = el("tr");
      var td = cell(NPDevI18n.t("shell.ops.noSteps", "No completed steps in this window."));
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
    // A list of counts joined by " - "; each count is its own phrase in the catalogue.
    var counts = [
      NPDevI18n.t("shell.ops.stepReached", "{count} reached", { count: step.reached }),
      NPDevI18n.t("shell.ops.stepOk", "{count} ok", { count: step.ok }),
      NPDevI18n.t("shell.ops.stepFailed", "{count} failed", { count: step.failed })
    ];
    if (step.waiting) counts.push(NPDevI18n.t("shell.ops.stepWaiting", "{count} waiting", { count: step.waiting }));
    if (step.retried) counts.push(NPDevI18n.t("shell.ops.stepRetried", "{count} retried", { count: step.retried }));
    box.appendChild(el("div", "counts", counts.join(" - ")));
    box.appendChild(el("div", "counts", NPDevI18n.t("shell.ops.stepTiming", "median {median} / p95 {p95}",
      { median: ms(step.elapsedMs.medianMs), p95: ms(step.elapsedMs.p95Ms) })));
    if (step.waitMs && step.waitMs.count) {
      box.appendChild(el("div", "counts", NPDevI18n.t("shell.ops.stepWaited", "waited {median} median", { median: ms(step.waitMs.medianMs) })));
    }
    return box;
  }

  function renderFlows(flows) {
    flowsBox.innerHTML = "";
    flowList.innerHTML = "";
    if (!flows.length) {
      flowsBox.appendChild(el("p", "hint", NPDevI18n.t("shell.ops.noFlows", "No executions with a trace in this window.")));
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
        NPDevI18n.t("shell.ops.flowSummary", "{executions} executions - {ok} ok - {failed} failed - {waiting} waiting - duration median {median} / p95 {p95}", {
          executions: flow.executions, ok: outcomes.OK || 0, failed: outcomes.FAILED || 0, waiting: outcomes.WAITING || 0,
          median: ms(flow.durationMs.medianMs), p95: ms(flow.durationMs.p95Ms)
        }) + (flow.slowestStep ? " - " + NPDevI18n.t("shell.ops.flowSlowest", "slowest step: {step}", { step: flow.slowestStep }) : "")));
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
    setMessage(NPDevI18n.t("shell.ops.loading", "Loading ..."), false);
    window.NPDevShell.api.get("/api/executions/analytics" + query()).then(function (body) {
      setMessage(body.flow
        ? NPDevI18n.t("shell.ops.scopeFlow", "Tenant {tenant}, flow {flow}.", { tenant: body.tenant, flow: body.flow })
        : NPDevI18n.t("shell.ops.scope", "Tenant {tenant}.", { tenant: body.tenant }), false);
      renderSummary(body);
      renderBottlenecks(body.bottlenecks || []);
      renderFlows(body.flows || []);
    }).catch(function (error) {
      summaryBox.innerHTML = "";
      bottleneckBody.innerHTML = "";
      flowsBox.innerHTML = "";
      setMessage(error && error.status === 403
        ? NPDevI18n.t("shell.ops.analyticsSuperUserOnly", "Only the Super User can read another tenant's analytics.")
        : NPDevI18n.t("shell.ops.analyticsLoadFailed", "Could not load analytics: {message}", { message: error && error.message }), true);
    });
  }

  tabAttention.addEventListener("click", function () { select("attention"); });
  tabAnalytics.addEventListener("click", function () { select("analytics"); });
  loadButton.addEventListener("click", load);
  flowInput.addEventListener("keydown", function (event) { if (event.key === "Enter") load(); });
  // Wait for the shell (it hands the language catalogue to NPDevI18n before ready() resolves) before the first load.
  if (window.location.hash === "#analytics") window.NPDevShell.api.ready().then(function () { select("analytics"); });
})();
