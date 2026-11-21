/* NPDev "My tasks" page (PROC-3).
 *
 * Source (repo):  NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/collab/tasks.js
 * Emitted (app):  src/main/resources/static/npdev-business-ui/collab/tasks.js
 * Copied VERBATIM via TemplateEngine.raw (UI-0) -- NOT Mustache, so `{{` would be harmless here.
 * Loaded by collab/tasks.html AFTER /shell.js (both `defer`), so window.NPDevShell exists.
 *
 * All HTTP goes through NPDevShell.api (token + X-NPDev-Request CSRF header from SEC-1). Never raw fetch().
 */
(function () {
  "use strict";
  var shell = window.NPDevShell;
  var root = document.getElementById("npdev-tasks");

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }

  function fmtDate(ms) { return ms ? new Date(ms).toLocaleString((window.NPDevI18n ? window.NPDevI18n.language() : undefined)) : ""; }

  function load() {
    shell.renderLoading(root, { message: NPDevI18n.t("shell.tasks.loading", "Loading your tasks...") });
    shell.api.get("/api/tasks").then(function (body) {
      var items = (body && body.items) || [];
      root.innerHTML = "";
      if (!items.length) {
        shell.renderEmpty(root, { message: NPDevI18n.t("shell.tasks.empty", "Nothing is waiting for you.") });
        return;
      }
      var list = el("ul", "npdev-task-list");
      items.forEach(function (task) { list.appendChild(row(task)); });
      root.appendChild(list);
    }).catch(function (err) {
      shell.renderError(root, {
        message: err.message || NPDevI18n.t("shell.tasks.loadFailed", "Could not load tasks."),
        action: { label: NPDevI18n.t("shell.common.retry", "Retry"), onClick: load }
      });
    });
  }

  function row(task) {
    var li = el("li", "npdev-task" + (task.overdue ? " npdev-task-overdue" : ""));
    var head = el("button", "npdev-task-head");
    head.type = "button";
    head.appendChild(el("span", "npdev-task-title", task.title));
    // A list of facts joined by " - "; each fact is its own sentence in the catalogue.
    var facts = [];
    if (task.startedBy) facts.push(NPDevI18n.t("shell.tasks.from", "from {name}", { name: task.startedBy }));
    facts.push(NPDevI18n.t("shell.tasks.waitingSince", "waiting since {date}", { date: fmtDate(task.waitingSinceEpochMs) }));
    if (task.dueAtEpochMs) facts.push(NPDevI18n.t("shell.tasks.due", "due {date}", { date: fmtDate(task.dueAtEpochMs) }));
    head.appendChild(el("span", "npdev-task-meta", facts.join(" - ")));
    if (task.overdue) head.appendChild(el("span", "npdev-task-badge", NPDevI18n.t("shell.tasks.overdue", "Overdue")));
    var body = el("div", "npdev-task-body");
    body.hidden = true;
    head.addEventListener("click", function () {
      body.hidden = !body.hidden;
      if (!body.hidden && !body.dataset.loaded) openTask(task, body);
    });
    li.appendChild(head);
    li.appendChild(body);
    return li;
  }

  function input(field) {
    var wrap = el("label", "npdev-task-field");
    wrap.appendChild(el("span", "", (field.label || field.name) + (field.required ? " *" : "")));
    var control;
    if (field.type === "text") {
      control = el("textarea");
    } else if (field.type === "boolean") {
      control = el("input"); control.type = "checkbox";
    } else {
      control = el("input");
      control.type = field.type === "integer" || field.type === "decimal" ? "number"
        : field.type === "date" ? "date" : "text";
      if (field.type === "decimal") control.step = "any";
    }
    if (field.maxLength) control.maxLength = field.maxLength;
    control.name = field.name;
    wrap.appendChild(control);
    return wrap;
  }

  function collect(form, fields) {
    var values = {};
    fields.forEach(function (f) {
      var c = form.elements[f.name];
      if (!c) return;
      if (f.type === "boolean") { values[f.name] = c.checked; return; }
      if (c.value === "") return;
      values[f.name] = (f.type === "integer" || f.type === "decimal") ? Number(c.value) : c.value;
    });
    return values;
  }

  function show(value) {
    if (value === null || value === undefined) return NPDevI18n.t("shell.tasks.emptyValue", "(empty)");
    return typeof value === "string" ? value : JSON.stringify(value);
  }

  // AI7-1: a review task shows the proposed values against the current ones, field by field. Text only.
  function proposalTable(proposal) {
    var wrap = el("div", "npdev-task-proposal");
    wrap.appendChild(el("p", "npdev-task-proposal-note",
      NPDevI18n.t("shell.tasks.proposalNote", "Proposed change - nothing is applied until you approve.")));
    var table = el("table", "npdev-task-diff");
    var head = el("tr");
    [NPDevI18n.t("shell.tasks.colField", "Field"), NPDevI18n.t("shell.tasks.colCurrent", "Current"),
      NPDevI18n.t("shell.tasks.colProposed", "Proposed")].forEach(function (h) { head.appendChild(el("th", "", h)); });
    table.appendChild(head);
    (proposal.diff || []).forEach(function (d) {
      var tr = el("tr", d.changed ? "npdev-task-diff-changed" : "npdev-task-diff-same");
      tr.appendChild(el("td", "", d.field));
      tr.appendChild(el("td", "npdev-task-diff-before", show(d.before)));
      tr.appendChild(el("td", "npdev-task-diff-after", show(d.after)));
      table.appendChild(tr);
    });
    wrap.appendChild(table);
    return wrap;
  }

  function openTask(task, body) {
    body.dataset.loaded = "1";
    shell.renderLoading(body, { message: NPDevI18n.t("shell.common.loading", "Loading...") });
    shell.api.get("/api/tasks/" + encodeURIComponent(task.executionId)).then(function (detail) {
      body.innerHTML = "";
      if (detail.description) body.appendChild(el("p", "npdev-task-description", detail.description));
      if (detail.proposal) body.appendChild(proposalTable(detail.proposal));
      var form = el("form", "npdev-task-form");
      (detail.fields || []).forEach(function (f) { form.appendChild(input(f)); });
      var status = el("div", "npdev-task-status");
      var buttons = el("div", "npdev-task-actions");
      (detail.outcomes || []).forEach(function (o) {
        var b = el("button", "", o.label || o.value);
        b.type = "button";
        b.addEventListener("click", function () {
          Array.prototype.forEach.call(buttons.children, function (x) { x.disabled = true; });
          status.textContent = NPDevI18n.t("shell.tasks.sending", "Sending...");
          shell.api.post("/api/tasks/" + encodeURIComponent(task.executionId) + "/complete", {
            stepName: detail.stepName, outcome: o.value, values: collect(form, detail.fields || [])
          }).then(function () {
            load();
          }).catch(function (err) {
            status.textContent = (err.body && err.body.message) || err.message;
            if (err.status === 409) { load(); return; }          // answered elsewhere -> refresh list
            Array.prototype.forEach.call(buttons.children, function (x) { x.disabled = false; });
          });
        });
        buttons.appendChild(b);
      });
      form.appendChild(buttons);
      body.appendChild(form);
      body.appendChild(status);
    }).catch(function (err) {
      shell.renderError(body, { message: err.message });
    });
  }

  shell.api.ready().then(load);
})();
