/* NPDev business UI -- Board (kanban) view (UI-3).
 * Repo:  NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/board.js
 * App:   static/npdev-business-ui/views/board.js   (copied verbatim; NOT Mustache)
 * Needs: views/registry.js loaded first (UI-0); manifest concept.board (BoardManifestBuilder).
 *
 * Moves go through ctx.helpers.setField -> POST {endpointBase}/batch {operation:"setField"} ->
 * the SAME ConceptBinding.update the edit form uses, so permissions, invariants, lifecycle
 * transitions (GeneratedCrudRuntimeSupport.validateLifecycleTransitions), guards and audit all apply.
 * The client-side `allowedTargets` check is only UX (grey out illegal columns); the server decides.
 */
(function () {
  "use strict";
  var NONE = "__none__";

  function valueKey(value) {
    return value === null || value === undefined || value === "" ? NONE : String(value);
  }

  // Pure: { columnValue -> [records] } preserving column order; unknown values go to NONE.
  function groupRecords(records, board) {
    var groups = {};
    var known = {};
    (board.columns || []).forEach(function (column) { groups[column.value] = []; known[column.value] = true; });
    groups[NONE] = [];
    (records || []).forEach(function (record) {
      var key = valueKey(record[board.groupBy]);
      (known[key] ? groups[key] : groups[NONE]).push(record);
    });
    return groups;
  }

  // Pure: list of { to, label, requires, guarded } a card in column `from` may move to.
  function allowedTargets(board, from) {
    if (!board.restricted) {
      return (board.columns || [])
        .filter(function (c) { return c.value !== from; })
        .map(function (c) { return { to: c.value, label: c.label, requires: [], guarded: false }; });
    }
    return (board.transitions || [])
      .filter(function (t) { return String(t.from) === String(from); })
      .map(function (t) { return { to: t.to, label: t.label, requires: t.requires || [], guarded: Boolean(t.guarded) }; });
  }

  function columnLabel(ctx, column) {
    var field = ctx.helpers.field(ctx.concept.board.groupBy);
    var options = field && Array.isArray(field.enumOptions) ? field.enumOptions : [];
    var option = options.find(function (o) { return String(o.value) === String(column.value); });
    return (option && option.label) || column.label || column.value;
  }

  async function move(ctx, record, target) {
    var board = ctx.concept.board;
    if (target.requires && target.requires.length) {
      // Required payload: let the existing edit form collect it, pre-set to the target column.
      var draft = Object.assign({}, record);
      draft[board.groupBy] = target.to;
      ctx.helpers.notify("Moving to " + target.label + " needs: " + target.requires.join(", ") + ". Fill them in and save.", false);
      ctx.helpers.openForm("edit", draft);
      return;
    }
    await ctx.helpers.setField(record, board.groupBy, target.to);
  }

  function buildCard(ctx, record, from) {
    var board = ctx.concept.board;
    var card = document.createElement("article");
    card.className = "board-card";
    card.tabIndex = 0;
    card.draggable = true;
    card.dataset.id = String(record[ctx.concept.idField]);
    var title = document.createElement("div");
    title.className = "board-card-title";
    title.textContent = ctx.helpers.recordTitle(record);
    card.appendChild(title);

    // Up to two more list columns as secondary lines (skips id, title column and the group field).
    var columns = (ctx.concept.list && ctx.concept.list.columns) || [];
    columns.filter(function (name) { return name !== ctx.concept.idField && name !== board.groupBy; })
      .slice(1, 3)
      .forEach(function (name) {
        var f = ctx.helpers.field(name);
        if (!f) { return; }
        var line = document.createElement("div");
        line.className = "board-card-line";
        ctx.helpers.renderValue(line, f, record[name]);
        card.appendChild(line);
      });

    // Touch/keyboard path (HTML5 drag-and-drop does not fire on touch screens): a "Move to" select.
    var targets = allowedTargets(board, from === NONE ? null : from);
    if (targets.length) {
      var select = document.createElement("select");
      select.className = "board-card-move";
      select.setAttribute("aria-label", "Move to");
      var placeholder = document.createElement("option");
      placeholder.value = "";
      placeholder.textContent = "Move to…";
      select.appendChild(placeholder);
      targets.forEach(function (t, i) {
        var option = document.createElement("option");
        option.value = String(i);
        option.textContent = t.label;
        select.appendChild(option);
      });
      select.addEventListener("click", function (e) { e.stopPropagation(); });
      select.addEventListener("change", function () {
        if (select.value !== "") { move(ctx, record, targets[Number(select.value)]); }
      });
      card.appendChild(select);
    }

    card.addEventListener("click", function () { ctx.helpers.openDetails(record); });
    card.addEventListener("keydown", function (e) { if (e.key === "Enter") { ctx.helpers.openDetails(record); } });
    card.addEventListener("dragstart", function (e) {
      e.dataTransfer.setData("text/plain", card.dataset.id);
      e.dataTransfer.effectAllowed = "move";
      card.classList.add("dragging");
      document.querySelectorAll(".board-column").forEach(function (col) {
        var ok = targets.some(function (t) { return String(t.to) === col.dataset.value; });
        col.classList.toggle("board-drop-ok", ok);
        col.classList.toggle("board-drop-blocked", !ok && col.dataset.value !== String(from));
      });
    });
    card.addEventListener("dragend", function () {
      card.classList.remove("dragging");
      document.querySelectorAll(".board-column").forEach(function (col) {
        col.classList.remove("board-drop-ok", "board-drop-blocked");
      });
    });
    return card;
  }

  function render(ctx) {
    var board = ctx.concept.board;
    var groups = groupRecords(ctx.records, board);
    var byId = {};
    (ctx.records || []).forEach(function (r) { byId[String(r[ctx.concept.idField])] = r; });

    var root = document.createElement("div");
    root.className = "board-view";
    var lanes = (board.columns || []).slice();
    if (groups[NONE].length) { lanes.push({ value: NONE, label: "—" }); }

    lanes.forEach(function (column) {
      var lane = document.createElement("section");
      lane.className = "board-column";
      lane.dataset.value = String(column.value);
      var head = document.createElement("header");
      head.className = "board-column-head";
      head.textContent = (column.value === NONE ? "—" : columnLabel(ctx, column)) + " (" + groups[column.value].length + ")";
      lane.appendChild(head);
      groups[column.value].forEach(function (record) { lane.appendChild(buildCard(ctx, record, column.value)); });

      lane.addEventListener("dragover", function (e) {
        if (lane.classList.contains("board-drop-ok")) { e.preventDefault(); }
      });
      lane.addEventListener("drop", function (e) {
        e.preventDefault();
        var record = byId[e.dataTransfer.getData("text/plain")];
        if (!record) { return; }
        var from = valueKey(record[board.groupBy]);
        var target = allowedTargets(board, from === NONE ? null : from)
          .find(function (t) { return String(t.to) === lane.dataset.value; });
        if (target) { move(ctx, record, target); }
      });
      root.appendChild(lane);
    });

    if (ctx.totalElements > (ctx.records || []).length) {
      var more = document.createElement("div");
      more.className = "board-truncated";
      more.textContent = "Showing " + ctx.records.length + " of " + ctx.totalElements + " — filter the list to see the rest.";
      root.appendChild(more);
    }
    return root;
  }

  window.NPDevViews.register("board", {
    label: "Board",
    pageSize: 100,
    render: render,
    // exposed for the node unit test only
    _groupRecords: groupRecords,
    _allowedTargets: allowedTargets
  });
})();
