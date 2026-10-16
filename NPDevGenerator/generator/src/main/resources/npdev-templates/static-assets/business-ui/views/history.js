/* NPDev business UI -- record History detail section (UI-4).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/history.js
 * App:  static/npdev-business-ui/views/history.js  (verbatim copy; NOT Mustache)
 *
 * Data: GET /api/audit?resourceType=<CONCEPT UPPER>&resourceId=<id>&limit=50
 *   (npdev-runtime-audit-controller.mustache; rows newest-first -- JdbcAuditLogStore ORDER BY ts_ms DESC,
 *    InProcAuditLogStore DESC_ORDER). Each row: { auditId, timestampMs, actorId, action
 *    ("CRUD_CREATE"|"CRUD_UPDATE"|"CRUD_DELETE"|...), outcome, meta: { operation, fieldDiff, fieldChanges } }.
 *   resourceType is the concept name UPPERCASED by GeneratedCrudRuntimeSupport.appendCrudAudit
 *   (Locale.ROOT); the JDBC store compares it exactly, so the client must upper-case too.
 *   Requires Permission.READ_AUDIT (built-in ADMIN only, RolePermissions.java:34-35) -> 403 otherwise.
 *
 * ctx (built by app.js openDetails, UI-4 step 5):
 *   { concept, record, helpers: { fetchJson(url, opts), headers(), field(name), renderValue(el, field, value) } }
 */
(function () {
  "use strict";

  // Pure: prefer the structured meta.fieldChanges (JSON string, UI-4 server step) and fall back to
  // parsing the legacy "a: x -> y; b: p -> q" meta.fieldDiff (ambiguous if a value contains "; ").
  function parseChanges(meta) {
    if (!meta) { return []; }
    if (meta.fieldChanges) {
      try {
        var parsed = JSON.parse(meta.fieldChanges);
        if (Array.isArray(parsed)) { return parsed; }
      } catch (ignored) { /* fall through to the legacy string */ }
    }
    if (!meta.fieldDiff) { return []; }
    return String(meta.fieldDiff).split("; ").map(function (part) {
      var colon = part.indexOf(": ");
      var arrow = part.indexOf(" -> ", colon + 2);
      if (colon < 0 || arrow < 0) { return { field: part, before: "", after: "" }; }
      return { field: part.slice(0, colon), before: part.slice(colon + 2, arrow), after: part.slice(arrow + 4) };
    });
  }

  var ACTION_LABELS = { CRUD_CREATE: "Created", CRUD_UPDATE: "Updated", CRUD_DELETE: "Deleted", CRUD_RESTORE: "Restored" };

  function shown(value) {
    return value === null || value === undefined || value === "null" || value === "" ? "—" : String(value);
  }

  function renderEntries(ctx, rows, target) {
    target.innerHTML = "";
    if (!rows.length) {
      target.textContent = "No recorded changes yet.";
      return;
    }
    var list = document.createElement("ol");
    list.className = "history-list";
    rows.forEach(function (row) {
      var item = document.createElement("li");
      item.className = "history-entry";
      var head = document.createElement("div");
      head.className = "history-head";
      head.textContent = new Date(row.timestampMs).toLocaleString() + " · " + (row.actorId || "system")
        + " · " + (ACTION_LABELS[row.action] || row.action) + (row.outcome && row.outcome !== "ALLOW" ? " (" + row.outcome + ")" : "");
      item.appendChild(head);
      var changes = parseChanges(row.meta).filter(function (c) { return c.field !== "version"; });
      if (changes.length) {
        var table = document.createElement("table");
        table.className = "history-changes";
        changes.forEach(function (change) {
          var field = ctx.helpers.field(change.field);
          var tr = document.createElement("tr");
          var name = document.createElement("th");
          name.textContent = field ? (field.label || field.name) : change.field;
          var before = document.createElement("td");
          var after = document.createElement("td");
          var masked = field && field.widget === "password";
          before.textContent = masked ? "••••" : shown(change.before);
          after.textContent = masked ? "••••" : shown(change.after);
          tr.appendChild(name); tr.appendChild(before); tr.appendChild(after);
          table.appendChild(tr);
        });
        item.appendChild(table);
      }
      list.appendChild(item);
    });
    target.appendChild(list);
  }

  function render(ctx) {
    var section = document.createElement("section");
    section.className = "detail-history";
    var title = document.createElement("h3");
    title.textContent = "History";
    section.appendChild(title);
    var body = document.createElement("div");
    var load = document.createElement("button");
    load.type = "button";
    load.textContent = "Show history";
    body.appendChild(load);
    section.appendChild(body);

    load.addEventListener("click", async function () {
      body.textContent = "Loading…";
      var params = new URLSearchParams();
      params.set("resourceType", String(ctx.concept.conceptName).toUpperCase());
      params.set("resourceId", String(ctx.record[ctx.concept.idField]));
      params.set("limit", "50");
      try {
        var rows = await ctx.helpers.fetchJson("/api/audit?" + params.toString(), { method: "GET", headers: ctx.helpers.headers() });
        renderEntries(ctx, Array.isArray(rows) ? rows : [], body);
      } catch (error) {
        body.textContent = error && error.status === 403
          ? "Change history is visible to users with the audit permission."
          : "Could not load history: " + (error && error.message ? error.message : "error");
      }
    });
    return section;
  }

  window.NPDevViews.registerDetailSection("history", { render: render, _parseChanges: parseChanges });
})();
