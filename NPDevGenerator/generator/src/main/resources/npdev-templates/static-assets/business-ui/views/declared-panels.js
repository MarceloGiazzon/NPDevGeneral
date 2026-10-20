/* NPDev business UI -- declared panels, widget grid, charts and panel slots (CARVE-1).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/declared-panels.js
 * App:  static/npdev-business-ui/views/declared-panels.js  (verbatim copy; NOT Mustache)
 *
 * Moved verbatim out of business-ui-app.mustache. It runs inside a factory registered with
 * NPDevViews.registerModule (registry.js); app.js calls NPDevViews.module("declaredPanels", host) once,
 * where host carries the app.js helpers/state this code uses, and destructures the returned exports.
 */
(function () {
  "use strict";
  NPDevViews.registerModule("declaredPanels", function (host) {
    const {
      button, el, evaluateWhen, fetchJson, fileHandleUrl, formatValue, headerRow, headers, isImageFileValue, notify, panelContextValues, panelSectionRef, render, sectionId, setStatus, state, text, trackFlowExecution
    } = host;

    function declaredPanelState(panelMeta) {
      if (!state.declaredPanels[panelMeta.name]) {
        state.declaredPanels[panelMeta.name] = {
          loading: false, loaded: false, data: {}, fieldBindings: [], dataSources: [], actions: [],
          lastActionName: "", lastActionResult: null, history: []
        };
      }
      return state.declaredPanels[panelMeta.name];
    }


    // Declared Panel Objects fetch their own data lazily from the existing, already-real generic
    // PanelRuntime endpoints -- same lazy-load-on-first-render pattern as Store/Box View/Preferences,
    // and no new backend surface, since /api/runtime/metadata/ui/panels/{name} already exists and
    // already enforces permission/tenant scoping through ConceptGateway for any concept-bound
    // dataSource or action.
    async function loadDeclaredPanel(panelMeta) {
      // Aggregate Workbench panels render as a link to their own served page (see renderDeclaredPanel);
      // they are not backed by the generic loadPanel data shape, so skip the fetch entirely.
      if (panelMeta.workbenchUrl) { return; }
      const entry = declaredPanelState(panelMeta);
      entry.loading = true;
      render();
      try {
        const payload = await fetchJson("/api/runtime/metadata/ui/panels/" + encodeURIComponent(panelMeta.name), {
          method: "GET",
          headers: headers()
        });
        entry.data = payload.data || {};
        entry.fieldBindings = payload.fieldBindings || [];
        entry.dataSources = payload.dataSources || [];
        entry.layout = payload.layout || {};
        // The boot-time manifest's panelMeta.actions only carries name/label/binding/visibleWhen/
        // enabledWhen (BusinessUiEmitter deliberately keeps it minimal) -- it has no "concept", so
        // resolveRowSaveAction below needs the richer actions array from this live GET response.
        entry.actions = payload.actions || [];
        entry.loaded = true;
      } catch (error) {
        setStatus(error.message, true);
      } finally {
        entry.loading = false;
        render();
      }
    }


    async function executeDeclaredPanelAction(panelMeta, actionName, body) {
      const entry = declaredPanelState(panelMeta);
      try {
        const payload = await fetchJson(
          "/api/runtime/metadata/ui/panels/" + encodeURIComponent(panelMeta.name) + "/actions/" + encodeURIComponent(actionName),
          { method: "POST", headers: headers(), body: JSON.stringify(body || {}) }
        );
        entry.lastActionName = actionName;
        entry.lastActionResult = payload.result;
        recordPanelHistory(entry, actionName, payload.status || "OK", payload.status === "FAILED");
        if (payload.status === "WAITING" && payload.executionId) {
          // R7.7: a flow-bound action that parked on awaitEvent is not "completed" -- see
          // trackFlowExecution's own comment for why this is the same lie docs/UI_CONTRACT.md warns
          // against, reached through a Panel action instead of a raw 202.
          trackFlowExecution(payload.executionId, (payload.result && payload.result.flowName) || actionName);
        } else {
          notify("Action \"" + actionName + "\" completed: " + (payload.status || "OK"), payload.status === "FAILED");
        }
        await loadDeclaredPanel(panelMeta);
      } catch (error) {
        recordPanelHistory(entry, actionName, error.message, true);
        setStatus(error.message, true);
        render();
      }
    }


    // LIFT-ROWOPS-P3/P2: declared-Panel dataSource row create/delete, gated purely by the dataSource's
    // declared rowOps (see PanelRuntime#createRow/#deleteRow and dataSourceSummary). parentId is only
    // sent for a child (nested) dataSource -- the server injects it into the child's FK column.
    async function createDeclaredPanelRow(panelMeta, dataSourceName, data, parentId) {
      try {
        const body = { data: data || {} };
        if (parentId) { body.parentId = parentId; }
        await fetchJson(
          "/api/runtime/metadata/ui/panels/" + encodeURIComponent(panelMeta.name)
            + "/dataSources/" + encodeURIComponent(dataSourceName) + "/rows",
          { method: "POST", headers: headers(), body: JSON.stringify(body) }
        );
        notify("Row added to \"" + dataSourceName + "\".", false);
        await loadDeclaredPanel(panelMeta);
      } catch (error) {
        setStatus(error.message, true);
        render();
      }
    }


    async function deleteDeclaredPanelRow(panelMeta, dataSourceName, id) {
      try {
        await fetchJson(
          "/api/runtime/metadata/ui/panels/" + encodeURIComponent(panelMeta.name)
            + "/dataSources/" + encodeURIComponent(dataSourceName) + "/rows/" + encodeURIComponent(id),
          { method: "DELETE", headers: headers() }
        );
        notify("Row removed from \"" + dataSourceName + "\".", false);
        await loadDeclaredPanel(panelMeta);
      } catch (error) {
        setStatus(error.message, true);
        render();
      }
    }


    function declaredPanelDataSourceMeta(entry, dataSourceName) {
      return (entry.dataSources || []).find(function (ds) { return ds.name === dataSourceName; }) || null;
    }


    // REG-92 (Move 11 W3): one free-text control for every declared Panel mini-form, mirroring the
    // Aggregate Workbench's own rule exactly -- see workbench-page.html.mustache, which has rendered a
    // <textarea rows="1"> here since REG-76 (Move 3 G4).
    //
    // A plain <input type="text"> silently strips/collapses newlines on assignment. Proven live in a
    // real browser against a declared Panel action input: a genuinely 3-line paste read back as
    // {"tagName":"INPUT","type":"text","value":"LINHA-1 LINHA-2 LINHA-3","newlines":0} -- three lines
    // in, one line out. The value is accepted, visibly "typed", and posted mangled: silent corruption,
    // never an error. For a pasted CSV the capability's own header-row skip then consumes the entire
    // payload and returns zero data rows, which is exactly how REG-76 was found on the Workbench side.
    //
    // Unconditional, NOT gated on a "multi-line widget" -- that is the Workbench's rule and mirroring
    // it is the whole point of this fix. A second, differently-conditioned rule on the Panel side is
    // how these two surfaces (which share one `inputFields` declaration, so one model text must mean
    // one behaviour) drifted apart in the first place. A rows="1" textarea reads and measures like a
    // single-line box for short values, and .value round-trips identically.
    function createDeclaredPanelTextInput(field) {
      const input = document.createElement("textarea");
      input.rows = 1;
      input.placeholder = field;
      input.className = "declared-panel-edit-input";
      return input;
    }


    // Header "+ add row" form for a dataSource with rowOps:[add]. Fields come from the declared
    // addFormFields (falling back to the declared table columns, if any) so authors get a usable
    // form even without hand-listing addFormFields for every add-able dataSource.
    function renderDeclaredPanelAddRowForm(panelMeta, entry, dataSourceName, columns) {
      const dataSourceMeta = declaredPanelDataSourceMeta(entry, dataSourceName);
      const rowOps = (dataSourceMeta && dataSourceMeta.rowOps) || [];
      if (rowOps.indexOf("add") === -1) { return null; }
      const fieldNames = (dataSourceMeta.addFormFields && dataSourceMeta.addFormFields.length)
        ? dataSourceMeta.addFormFields
        : (columns || []).map(function (column) { return column.sourceField; });
      const form = document.createElement("div");
      form.className = "declared-panel-add-row";
      const inputsByField = {};
      fieldNames.forEach(function (field) {
        // REG-92: same control, same reason, as the action mini-form below -- an add-row column whose
        // value is inherently multi-line was corrupted identically, and fixing only the twin REG-92
        // happens to name is precisely how REG-76's workbench fix failed to reach this side.
        const input = createDeclaredPanelTextInput(field);
        inputsByField[field] = input;
        form.appendChild(input);
      });
      form.appendChild(button("+ Add row", "secondary small", function () {
        const data = {};
        Object.keys(inputsByField).forEach(function (field) { data[field] = inputsByField[field].value; });
        const parentId = dataSourceMeta.parentDataSource ? currentDeclaredPanelParentId(entry, dataSourceMeta) : null;
        createDeclaredPanelRow(panelMeta, dataSourceName, data, parentId);
      }));
      return form;
    }


    // A child dataSource's add-row form needs the id of the (currently loaded) parent row it belongs
    // to. Tier 2 nesting is one level only, so the parent row is whichever single row is currently
    // rendered for dataSource.parentDataSource -- consistent with how __children is attached server-side.
    function currentDeclaredPanelParentId(entry, dataSourceMeta) {
      const parentRows = entry.data ? entry.data[dataSourceMeta.parentDataSource] : null;
      if (Array.isArray(parentRows) && parentRows.length === 1) { return parentRows[0].id; }
      return null;
    }

    const slotMounts = {};

    const slotScriptRequests = {};


    // Injects the app-owned <script src="/regions/<name>.js"> once per component and re-renders when it
    // settles (load OR failure), so the mount below replaces its "not registered" placeholder.
    function ensureSlotScript(name) {
      if (slotScriptRequests[name]) { return; }
      slotScriptRequests[name] = true;
      const script = document.createElement("script");
      script.src = "/regions/" + encodeURIComponent(name) + ".js";
      script.onload = function () { render(); };
      script.onerror = function () { render(); };
      document.head.appendChild(script);
    }


    function slotApi(panelMeta, entry) {
      return Object.freeze({
        panel: panelMeta.name,
        data: JSON.parse(JSON.stringify(entry.data || {})),
        refresh: function () { return loadDeclaredPanel(panelMeta); },
        invoke: function (actionName, data) { return executeDeclaredPanelAction(panelMeta, actionName, data || {}); }
      });
    }


    // One persistent container per (panel, component): render() rebuilds the whole page, but re-inserting
    // the SAME element keeps a mounted component's own DOM alive; update() runs on every later render.
    function mountPanelSlot(panelMeta, entry, slot) {
      const key = panelMeta.name + "/" + slot.component;
      let mount = slotMounts[key];
      if (!mount) {
        const el = document.createElement("div");
        el.className = "panel-slot panel-slot-" + slot.placement;
        mount = { el: el, name: null, body: document.createElement("div") };
        if (slot.label) {
          const heading = document.createElement("h3");
          heading.textContent = slot.label;
          el.appendChild(heading);
        }
        el.appendChild(mount.body);
        slotMounts[key] = mount;
      }
      ensureSlotScript(slot.component);
      const impl = window.npdev.regions.get(slot.component);
      if (!impl) {
        mount.body.innerHTML = "";
        const note = document.createElement("p");
        note.className = "sub";
        note.textContent = 'Component "' + slot.component + '" is not registered — check that web/regions/'
          + slot.component + '.js loads and calls window.npdev.regions.register.';
        mount.body.appendChild(note);
        mount.name = null;
        return mount.el;
      }
      const api = slotApi(panelMeta, entry);
      if (mount.name !== slot.component) {
        mount.body.innerHTML = "";
        if (impl.mount) { impl.mount(mount.body, api); }
        mount.name = slot.component;
      } else if (impl.update) {
        impl.update(api);
      }
      return mount.el;
    }


    // ---- F6-2 console / F6-3 dashboard: declarative widgets computed client-side over a panel's own
    // data sources (layout.type "console" | "dashboard", layout.widgets[]). No library: charts are inline SVG.
    function isWidgetLayout(layout) {
      return !!(layout && (layout.type === "console" || layout.type === "dashboard")
        && Array.isArray(layout.widgets) && layout.widgets.length);
    }


    // A concept-bound data source's rows are record envelopes ({tenantId, concept, id, data}); a query or
    // procedure source returns plain objects. Widgets read fields from whichever shape is present.
    function widgetRows(entry, widget) {
      const raw = (entry.data || {})[widget.dataSource];
      let rows = (Array.isArray(raw) ? raw : []).map(function (row) {
        return row && row.data && typeof row.data === "object" ? row.data : (row || {});
      });
      if (widget.filter && widget.filter.field) {
        rows = rows.filter(function (row) { return String(row[widget.filter.field]) === String(widget.filter.equals); });
      }
      return rows;
    }


    function aggregateRows(rows, aggregate, field) {
      const kind = aggregate || "count";
      if (kind === "count") { return rows.length; }
      const nums = rows.map(function (row) { return Number(row[field]); }).filter(function (n) { return !isNaN(n); });
      if (!nums.length) { return 0; }
      if (kind === "min") { return Math.min.apply(null, nums); }
      if (kind === "max") { return Math.max.apply(null, nums); }
      const total = nums.reduce(function (a, b) { return a + b; }, 0);
      return kind === "avg" ? total / nums.length : total;
    }


    function groupedPoints(rows, widget) {
      const groups = {};
      const order = [];
      rows.forEach(function (row) {
        const key = row[widget.groupBy] === undefined || row[widget.groupBy] === null ? "(none)" : String(row[widget.groupBy]);
        if (!groups[key]) { groups[key] = []; order.push(key); }
        groups[key].push(row);
      });
      order.sort();
      return order.map(function (key) {
        return { label: key, value: aggregateRows(groups[key], widget.aggregate, widget.field) };
      });
    }


    function formatWidgetNumber(n) {
      return Number.isInteger(n) ? String(n) : String(Math.round(n * 100) / 100);
    }


    const SVG_NS = "http://www.w3.org/2000/svg";

    const CHART_COLORS = ["#2f6fdb", "#e07b39", "#2e9e6b", "#9b59b6", "#d4a017", "#c0392b", "#16a0b5", "#7f8c8d"];


    function svgEl(name, attrs) {
      const el = document.createElementNS(SVG_NS, name);
      Object.keys(attrs || {}).forEach(function (key) { el.setAttribute(key, String(attrs[key])); });
      return el;
    }


    function svgText(x, y, text, anchor, size) {
      const el = svgEl("text", { x: x, y: y, "text-anchor": anchor || "middle", "font-size": size || 10, fill: "currentColor" });
      el.textContent = text;
      return el;
    }


    function renderBarChart(points) {
      const w = 360, h = 190, pad = 28;
      const svg = svgEl("svg", { viewBox: "0 0 " + w + " " + h, width: "100%", role: "img", "data-chart": "bar" });
      const max = Math.max.apply(null, points.map(function (p) { return p.value; }).concat([1]));
      const slot = (w - 2 * pad) / Math.max(points.length, 1);
      points.forEach(function (point, i) {
        const barHeight = (h - 2 * pad) * (point.value / max);
        const x = pad + i * slot + slot * 0.15;
        svg.appendChild(svgEl("rect", {
          x: x, y: h - pad - barHeight, width: slot * 0.7, height: Math.max(barHeight, 0),
          fill: CHART_COLORS[i % CHART_COLORS.length], "data-label": point.label, "data-value": point.value
        }));
        svg.appendChild(svgText(x + slot * 0.35, h - pad - barHeight - 4, formatWidgetNumber(point.value)));
        svg.appendChild(svgText(x + slot * 0.35, h - pad + 12, point.label));
      });
      return svg;
    }


    function renderLineChart(points) {
      const w = 360, h = 190, pad = 28;
      const svg = svgEl("svg", { viewBox: "0 0 " + w + " " + h, width: "100%", role: "img", "data-chart": "line" });
      const max = Math.max.apply(null, points.map(function (p) { return p.value; }).concat([1]));
      const step = points.length > 1 ? (w - 2 * pad) / (points.length - 1) : 0;
      const coords = points.map(function (point, i) {
        return [pad + i * step, h - pad - (h - 2 * pad) * (point.value / max)];
      });
      svg.appendChild(svgEl("polyline", {
        points: coords.map(function (c) { return c[0] + "," + c[1]; }).join(" "),
        fill: "none", stroke: CHART_COLORS[0], "stroke-width": 2
      }));
      points.forEach(function (point, i) {
        svg.appendChild(svgEl("circle", {
          cx: coords[i][0], cy: coords[i][1], r: 4, fill: CHART_COLORS[0],
          "data-label": point.label, "data-value": point.value
        }));
        svg.appendChild(svgText(coords[i][0], h - pad + 12, point.label));
      });
      return svg;
    }


    function renderDonutChart(points) {
      const wrap = document.createElement("div");
      wrap.style.cssText = "display:flex;align-items:center;gap:16px;flex-wrap:wrap";
      const radius = 54;
      const circumference = 2 * Math.PI * radius;
      const total = points.reduce(function (sum, p) { return sum + p.value; }, 0);
      const svg = svgEl("svg", { viewBox: "0 0 140 140", width: 140, height: 140, role: "img", "data-chart": "donut" });
      let offset = 0;
      points.forEach(function (point, i) {
        const fraction = total > 0 ? point.value / total : 0;
        svg.appendChild(svgEl("circle", {
          cx: 70, cy: 70, r: radius, fill: "none", stroke: CHART_COLORS[i % CHART_COLORS.length], "stroke-width": 26,
          "stroke-dasharray": (fraction * circumference) + " " + (circumference - fraction * circumference),
          "stroke-dashoffset": -offset, transform: "rotate(-90 70 70)",
          "data-label": point.label, "data-value": point.value
        }));
        offset += fraction * circumference;
      });
      wrap.appendChild(svg);
      const legend = document.createElement("ul");
      legend.className = "chart-legend";
      legend.style.cssText = "list-style:none;margin:0;padding:0";
      points.forEach(function (point, i) {
        const item = document.createElement("li");
        item.setAttribute("data-label", point.label);
        item.setAttribute("data-value", String(point.value));
        const swatch = document.createElement("span");
        swatch.style.cssText = "display:inline-block;width:10px;height:10px;margin-right:6px;background:" + CHART_COLORS[i % CHART_COLORS.length];
        item.appendChild(swatch);
        item.appendChild(document.createTextNode(point.label + ": " + formatWidgetNumber(point.value)));
        legend.appendChild(item);
      });
      wrap.appendChild(legend);
      return wrap;
    }


    function recordPanelHistory(entry, actionName, status, failed) {
      entry.history = entry.history || [];
      entry.history.unshift({ at: new Date(), action: actionName, status: status, failed: !!failed });
      if (entry.history.length > 25) { entry.history.length = 25; }
    }


    function renderWidgetGrid(panelMeta, entry, actionHost) {
      const grid = document.createElement("div");
      grid.className = "panel-widgets panel-widgets-" + entry.layout.type;
      grid.style.cssText = "display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:8px 0";
      entry.layout.widgets.forEach(function (widget) {
        const card = document.createElement("div");
        card.className = "panel-widget panel-widget-" + widget.kind;
        card.setAttribute("data-widget", widget.kind);
        const defaultSpan = widget.kind === "tile" ? 1 : (widget.kind === "chart" ? 2 : 4);
        card.style.cssText = "grid-column:span " + (widget.span || defaultSpan)
          + ";border:1px solid #c8d0d8;border-radius:8px;padding:10px 14px;min-width:0";
        if (widget.title) {
          const heading = document.createElement("h4");
          heading.textContent = widget.title;
          heading.style.cssText = "margin:0 0 6px 0;font-size:13px;opacity:.75";
          card.appendChild(heading);
        }
        if (widget.kind === "tile") {
          const value = aggregateRows(widgetRows(entry, widget), widget.aggregate, widget.field);
          card.setAttribute("data-title", widget.title || "");
          card.setAttribute("data-value", String(value));
          const number = document.createElement("div");
          number.className = "widget-value";
          number.style.cssText = "font-size:28px;font-weight:600";
          number.textContent = formatWidgetNumber(value);
          card.appendChild(number);
        } else if (widget.kind === "chart") {
          const points = groupedPoints(widgetRows(entry, widget), widget);
          card.setAttribute("data-chart-kind", widget.chart || "");
          if (!points.length) {
            const none = document.createElement("p");
            none.className = "sub";
            none.textContent = "No data.";
            card.appendChild(none);
          } else if (widget.chart === "line") {
            card.appendChild(renderLineChart(points));
          } else if (widget.chart === "donut") {
            card.appendChild(renderDonutChart(points));
          } else {
            card.appendChild(renderBarChart(points));
          }
        } else if (widget.kind === "actions") {
          card.appendChild(actionHost);
        } else if (widget.kind === "history") {
          const history = entry.history || [];
          if (!history.length) {
            const none = document.createElement("p");
            none.className = "sub";
            none.textContent = "No actions run yet.";
            card.appendChild(none);
          } else {
            const list = document.createElement("ol");
            list.className = "widget-history";
            list.style.cssText = "margin:0;padding-left:20px";
            history.forEach(function (run) {
              const item = document.createElement("li");
              item.setAttribute("data-action", run.action);
              item.setAttribute("data-status", run.status);
              item.textContent = run.at.toLocaleTimeString() + " \u2014 " + run.action + " \u2014 " + run.status;
              if (run.failed) { item.style.color = "#c0392b"; }
              list.appendChild(item);
            });
            card.appendChild(list);
          }
        } else if (widget.kind === "table") {
          card.appendChild(renderDeclaredPanelValue((entry.data || {})[widget.dataSource], entry, widget.dataSource, panelMeta));
        }
        grid.appendChild(card);
      });
      return grid;
    }


    function renderDeclaredPanel(panelMeta) {
      const section = document.createElement("section");
      section.className = "concept-panel";
      section.id = sectionId(panelSectionRef(panelMeta));

      // Aggregate Workbench: render a link to its dedicated served page rather than the generic
      // panel body (the multi-level editor lives at /npdev-workbench/<name>.html). ADR-0005.
      if (panelMeta.workbenchUrl) {
        const wbHeader = document.createElement("div");
        wbHeader.className = "panel-header";
        const wbTitle = document.createElement("h2");
        wbTitle.textContent = panelMeta.title || panelMeta.name;
        wbHeader.appendChild(wbTitle);
        section.appendChild(wbHeader);
        const link = document.createElement("a");
        link.className = "declared-panel-body";
        link.href = panelMeta.workbenchUrl;
        link.textContent = "Open " + (panelMeta.title || panelMeta.name) + " workbench →";
        section.appendChild(link);
        return section;
      }

      const header = document.createElement("div");
      header.className = "panel-header";
      const title = document.createElement("h2");
      title.textContent = panelMeta.title || panelMeta.name;
      header.appendChild(title);
      const headerActions = document.createElement("div");
      headerActions.className = "panel-actions";
      headerActions.appendChild(button("Refresh", "", function () { loadDeclaredPanel(panelMeta); }));
      header.appendChild(headerActions);
      section.appendChild(header);

      const panelContext = panelContextValues();
      const panelEnabled = evaluateWhen(panelMeta.enabledWhen, panelContext);
      // WMS-15: panelMeta.actions is the boot-time manifest (name/label/binding/visibleWhen/
      // enabledWhen/inputFields only -- see loadDeclaredPanel's own comment); confirmationText is
      // resolved server-side per request (PanelRuntime#panelActions, from the invoked flow's own
      // declared action.confirmationText) and only ever appears on the live entry.actions. Looked up
      // by name here so the header-button loop below can still iterate the boot-time list unchanged.
      const headerEntry = declaredPanelState(panelMeta);
      // F6-2 / F6-3: a console/dashboard layout places the action buttons itself (the `actions` widget);
      // every other layout appends them straight to the section, exactly as before.
      const widgetLayout = !!headerEntry.loaded && isWidgetLayout(headerEntry.layout);
      const actionHost = widgetLayout ? document.createElement("div") : section;
      if (widgetLayout) { actionHost.className = "panel-widget-actions"; }
      (panelMeta.actions || []).forEach(function (action) {
        // G2: scope="row" actions render per row inside renderDeclaredColumnsTable instead -- skip here.
        if (action.scope === "row") { return; }
        if (!evaluateWhen(action.visibleWhen, panelContext)) { return; }
        // REG-216: an aggregate root's Selection "New" button can never satisfy a blank
        // conceptMutation create once the root has a required field beyond id -- when the panel
        // carries a paired workbench (panelMeta.newWorkbenchUrl, ADR-0005), navigate straight to
        // that workbench's own empty-shell editor instead of invoking the mutation.
        if (action.name === "new" && panelMeta.newWorkbenchUrl) {
          const newButton = button(action.label, "primary", function () {
            window.location.href = panelMeta.newWorkbenchUrl;
          });
          newButton.disabled = !panelEnabled || !evaluateWhen(action.enabledWhen, panelContext);
          actionHost.appendChild(newButton);
          return;
        }
        const fieldNames = action.inputFields || [];
        // G3 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): a panel-scoped action with declared inputFields gets
        // an inline "collect input, then invoke" mini-form -- the same shape
        // renderDeclaredPanelAddRowForm already uses for a dataSource's add-row form, just wired to
        // executeDeclaredPanelAction instead of createDeclaredPanelRow. Free-text inputs only (no
        // widget/reference-picker resolution yet -- a real but smaller gap than having no form at all);
        // REG-92 made that free-text control newline-preserving, see createDeclaredPanelTextInput.
        const inputsByField = {};
        if (fieldNames.length) {
          const inlineForm = document.createElement("span");
          inlineForm.className = "declared-panel-action-inputs";
          fieldNames.forEach(function (field) {
            const input = createDeclaredPanelTextInput(field);
            inputsByField[field] = input;
            inlineForm.appendChild(input);
          });
          actionHost.appendChild(inlineForm);
        }
        const actionButton = button(action.label, "primary", function () {
          const liveAction = (headerEntry.actions || []).find(function (a) { return a.name === action.name; });
          if (liveAction && liveAction.confirmationText && !window.confirm(liveAction.confirmationText)) { return; }
          const data = {};
          Object.keys(inputsByField).forEach(function (field) { data[field] = inputsByField[field].value; });
          executeDeclaredPanelAction(panelMeta, action.name, data);
        });
        actionButton.disabled = !panelEnabled || !evaluateWhen(action.enabledWhen, panelContext);
        actionHost.appendChild(actionButton);
      });

      const entry = declaredPanelState(panelMeta);
      // F6-1 layoutSlot: slots only mount once the panel's data has loaded (the component is handed it).
      const slots = (entry.loaded && entry.layout && Array.isArray(entry.layout.slots)) ? entry.layout.slots : [];
      slots.forEach(function (slot) {
        if (slot.placement === "above") { section.appendChild(mountPanelSlot(panelMeta, entry, slot)); }
      });
      const body = document.createElement("div");
      body.className = "declared-panel-body";
      if (entry.loading) {
        body.textContent = "Loading…";
      } else if (entry.loaded) {
        Object.keys(entry.data || {}).forEach(function (dataSourceName) {
          const block = document.createElement("div");
          block.className = "declared-panel-datasource";
          const label = document.createElement("h3");
          label.textContent = dataSourceName;
          block.appendChild(label);
          block.appendChild(renderDeclaredPanelValue(entry.data[dataSourceName], entry, dataSourceName, panelMeta));
          body.appendChild(block);
        });
        if (entry.lastActionResult !== null && entry.lastActionResult !== undefined) {
          const resultBlock = document.createElement("div");
          resultBlock.className = "declared-panel-datasource";
          const label = document.createElement("h3");
          label.textContent = "Last action result (" + entry.lastActionName + ")";
          resultBlock.appendChild(label);
          resultBlock.appendChild(renderDeclaredPanelValueGeneric(entry.lastActionResult));
          body.appendChild(resultBlock);
        }
      }
      const replaceSlot = slots.find(function (slot) { return slot.placement === "replace"; });
      section.appendChild(replaceSlot
        ? mountPanelSlot(panelMeta, entry, replaceSlot)
        : (widgetLayout ? renderWidgetGrid(panelMeta, entry, actionHost) : body));
      slots.forEach(function (slot) {
        if (slot.placement === "below") { section.appendChild(mountPanelSlot(panelMeta, entry, slot)); }
      });
      return section;
    }


    // A declared Panel's dataSource/action-result values have no fixed shape (JSON returned by an
    // arbitrary bound procedure/concept query), so this renders generically: a list of uniform
    // objects becomes a table (mirrors every other records table in this file), anything else a
    // formatted JSON block. Kept as the guaranteed-unchanged fallback for any dataSource that has no
    // matching declared fieldBindings -- see renderDeclaredPanelValue below for the declared-column path.
    // panelMeta/entry/dataSourceName are optional (omitted for action-result rendering, which has no
    // row-delete concept) -- when supplied and the dataSource has rowOps:[delete], each row gets a
    // delete button, same as the declared-column table.
    function renderDeclaredPanelValueGeneric(value, panelMeta, entry, dataSourceName) {
      if (Array.isArray(value) && value.length && value.every(function (item) {
        return item && typeof item === "object" && !Array.isArray(item);
      })) {
        const dataSourceMeta = entry ? declaredPanelDataSourceMeta(entry, dataSourceName) : null;
        const deletable = Boolean(dataSourceMeta && (dataSourceMeta.rowOps || []).indexOf("delete") !== -1);
        const props = Array.from(value.reduce(function (set, item) {
          Object.keys(item).forEach(function (key) { set.add(key); });
          return set;
        }, new Set()));
        const table = document.createElement("table");
        table.className = "records";
        const headerLabels = deletable ? props.concat([""]) : props;
        const thead = document.createElement("thead");
        thead.appendChild(headerRow(headerLabels));
        table.appendChild(thead);
        const tbody = document.createElement("tbody");
        value.forEach(function (item) {
          const tr = document.createElement("tr");
          props.forEach(function (prop) {
            const td = document.createElement("td");
            td.textContent = formatValue(item[prop]);
            tr.appendChild(td);
          });
          if (deletable) {
            const actionTd = document.createElement("td");
            actionTd.appendChild(button("✕", "secondary small", function () {
              deleteDeclaredPanelRow(panelMeta, dataSourceName, item.id);
            }));
            tr.appendChild(actionTd);
          }
          tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        return table;
      }
      if (Array.isArray(value) && value.length === 0) {
        const empty = document.createElement("div");
        empty.className = "empty";
        empty.textContent = "(empty)";
        return empty;
      }
      const pre = document.createElement("pre");
      pre.className = "declared-panel-json";
      // a11y: a horizontally scrollable region must be keyboard-focusable (axe scrollable-region-focusable).
      pre.tabIndex = 0;
      pre.textContent = JSON.stringify(value, null, 2);
      return pre;
    }


    // Declared fieldBindings scoped to one dataSource (or unscoped, applying to every dataSource),
    // sorted by declared ui.order. Returns null when none apply, so the caller falls back to the
    // fully-generic union-of-keys rendering above -- existing Panels with no fieldBindings for a
    // given dataSource render byte-identically to before this function existed.
    function declaredPanelColumns(fieldBindings, dataSourceName) {
      const scoped = (fieldBindings || []).filter(function (binding) {
        return !binding.dataSource || binding.dataSource === dataSourceName;
      });
      if (!scoped.length) { return null; }
      return scoped.slice().sort(function (a, b) { return (a.order || 0) - (b.order || 0); });
    }


    // Resolves which declared Panel action (if any) should save an edited row of the given
    // dataSource: the first conceptMutation action whose concept matches that dataSource's own
    // concept. No matching action -> no Save button is rendered for that dataSource at all (a
    // graceful no-op, never an error) -- Panels with editable-looking bindings but no matching
    // action just render read-only, exactly like before this function existed.
    function resolveRowSaveAction(panelMeta, entry, dataSourceName) {
      const dataSourceMeta = (entry.dataSources || []).find(function (ds) { return ds.name === dataSourceName; });
      const concept = dataSourceMeta ? String(dataSourceMeta.concept || "").toLowerCase() : "";
      if (!concept) { return null; }
      return (entry.actions || []).find(function (action) {
        return String(action.binding || "").toLowerCase() === "conceptmutation"
          && String(action.concept || "").toLowerCase() === concept;
      }) || null;
    }


    // Declared-column table for one dataSource's rows. Record shape from the runtime is always
    // {tenantId, concept, id, data}, so each column's value lives at row.data[column.sourceField].
    // If a row carries __children (attached server-side by PanelRuntime for a declared parent/child
    // dataSource pair -- one level of nesting only, per the Tier 2 scope), each child dataSource's
    // rows render as their own nested table directly below the parent row. Columns marked
    // editable render an <input> instead of static text; an explicit per-row "Save" button (not
    // autosave) appears once per row that has at least one editable column and a resolvable save
    // action -- modeled directly on the proven WmsOffice centro-trabalho.html renderPosicaoRow pattern.
    // G2 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): declared actions with scope "row" render once per row of
    // their declared dataSource, instead of once at the panel header (see renderDeclaredPanel's header
    // loop, which skips them). visibleWhen/enabledWhen evaluate against the row's OWN field values
    // merged over the usual actor/role panelContext, so e.g. `situacao == 'Ativo'` can gate the button
    // per row -- panelContextValues() alone (isSuperUser/role/actorId) can't express that.
    function rowScopedActions(entry, dataSourceName) {
      return (entry.actions || []).filter(function (action) {
        return action.scope === "row" && action.dataSource === dataSourceName;
      });
    }


    function renderDeclaredColumnsTable(rows, columns, dataSourceName, panelMeta, entry, parentActionInputsByName) {
      const table = document.createElement("table");
      table.className = "records";
      const editableColumns = columns.filter(function (column) { return column.editable; });
      const saveAction = editableColumns.length ? resolveRowSaveAction(panelMeta, entry, dataSourceName) : null;
      const dataSourceMeta = declaredPanelDataSourceMeta(entry, dataSourceName);
      const deletable = Boolean(dataSourceMeta && (dataSourceMeta.rowOps || []).indexOf("delete") !== -1);
      const rowActions = rowScopedActions(entry, dataSourceName);
      // WMS-16: when THIS table is itself a nested child (parentActionInputsByName passed down from
      // the currently-rendering parent row), a "Clonar" button per row pre-fills whichever of the
      // parent's own input-collecting row actions declares at least one inputField matching one of
      // THIS table's columns -- e.g. the lotes table's loteId/quantidade/crossDocking columns
      // matching the recebimentosPendentes-scoped "Adicionar Item" action's own inputFields. Purely
      // name-matching (row-independent, computed once per table render), so it works for any app's
      // own field names, not just WmsOffice's.
      const cloneTargets = [];
      if (parentActionInputsByName) {
        Object.keys(parentActionInputsByName).forEach(function (actionName) {
          const targetInputs = parentActionInputsByName[actionName];
          const matchingFields = Object.keys(targetInputs).filter(function (field) {
            return columns.some(function (column) { return column.sourceField === field; });
          });
          if (matchingFields.length) { cloneTargets.push({ inputsByField: targetInputs, fields: matchingFields }); }
        });
      }
      const headerLabels = columns.map(function (column) { return column.label || column.field; });
      if (saveAction || deletable || rowActions.length || cloneTargets.length) { headerLabels.push(""); }
      const thead = document.createElement("thead");
      thead.appendChild(headerRow(headerLabels));
      table.appendChild(thead);
      const tbody = document.createElement("tbody");
      rows.forEach(function (row) {
        const tr = document.createElement("tr");
        const rowData = row && typeof row === "object" ? (row.data || {}) : {};
        const inputsByField = {};
        columns.forEach(function (column) {
          const td = document.createElement("td");
          const cellValue = rowData[column.sourceField];
          if (column.editable) {
            const input = document.createElement("input");
            const isNumeric = typeof cellValue === "number";
            input.type = isNumeric ? "number" : "text";
            input.value = cellValue === null || cellValue === undefined ? "" : cellValue;
            input.className = "declared-panel-edit-input";
            inputsByField[column.sourceField] = input;
            td.appendChild(input);
          } else {
            td.textContent = formatValue(cellValue);
          }
          tr.appendChild(td);
        });
        // WMS-16: per-parent-row registry of this row's own input-collecting row actions (keyed by
        // action name), filled below and handed down to a nested child dataSource's "Clonar" button.
        const actionInputsByName = {};
        if (saveAction || deletable || rowActions.length || cloneTargets.length) {
          const actionTd = document.createElement("td");
          if (cloneTargets.length) {
            actionTd.appendChild(button("Clonar", "secondary small", function () {
              cloneTargets.forEach(function (target) {
                target.fields.forEach(function (field) {
                  const value = rowData[field];
                  target.inputsByField[field].value = value === null || value === undefined ? "" : value;
                });
              });
              notify("Valores copiados para \"Adicionar Item\" -- ajuste e confirme.", false);
            }));
          }
          if (saveAction) {
            actionTd.appendChild(button("Save", "secondary small", function () {
              const data = Object.assign({}, rowData);
              Object.keys(inputsByField).forEach(function (field) {
                const input = inputsByField[field];
                data[field] = input.type === "number" ? Number(input.value) : input.value;
              });
              executeDeclaredPanelAction(panelMeta, saveAction.name, { id: row.id, data: data });
            }));
          }
          const rowContext = Object.assign({}, panelContextValues(), rowData);
          rowActions.forEach(function (action) {
            if (!evaluateWhen(action.visibleWhen, rowContext)) { return; }
            // WMS-16: a row-scoped action with declared inputFields gets the SAME inline
            // "collect input, then invoke" mini-form the header-action loop already renders (see
            // renderDeclaredPanel above) -- previously only {id: row.id} was ever sent for a
            // scope="row" action, so any inputFields it declared had no way to reach the server even
            // though PanelRuntime#resolveRowScopedInput already merges extra body fields on top of the
            // row's own data unconditionally. rowInputsByField is keyed per action so a row with
            // multiple input-collecting actions doesn't cross-wire their values.
            const rowInputsByField = {};
            const fieldNames = action.inputFields || [];
            if (fieldNames.length) {
              fieldNames.forEach(function (field) {
                const input = createDeclaredPanelTextInput(field);
                rowInputsByField[field] = input;
                actionTd.appendChild(input);
              });
              // WMS-16: exposes this action's own live input elements to a nested child dataSource's
              // "Clonar" row button (below), keyed by action name -- lets an existing child row
              // pre-fill THIS parent row's own "Adicionar Item"-style mini-form from its own field
              // values instead of the user retyping them, without hard-coding either name into this
              // shared template.
              if (actionInputsByName) { actionInputsByName[action.name] = rowInputsByField; }
            }
            const rowActionButton = button(action.label || action.name, "secondary small", function () {
              // WMS-15: entry.actions already carries confirmationText (resolved server-side from
              // the invoked flow's own declared action.confirmationText) -- no name lookup needed here
              // since this list IS the live one, unlike the header-button loop above.
              if (action.confirmationText && !window.confirm(action.confirmationText)) { return; }
              const data = { id: row.id };
              Object.keys(rowInputsByField).forEach(function (field) { data[field] = rowInputsByField[field].value; });
              executeDeclaredPanelAction(panelMeta, action.name, data);
            });
            rowActionButton.disabled = !evaluateWhen(action.enabledWhen, rowContext);
            actionTd.appendChild(rowActionButton);
          });
          if (deletable) {
            actionTd.appendChild(button("✕", "secondary small", function () {
              deleteDeclaredPanelRow(panelMeta, dataSourceName, row.id);
            }));
          }
          tr.appendChild(actionTd);
        }
        tbody.appendChild(tr);

        const children = row && typeof row === "object" ? row.__children : null;
        if (children && typeof children === "object") {
          Object.keys(children).forEach(function (childDataSourceName) {
            const childRows = children[childDataSourceName];
            const childTr = document.createElement("tr");
            childTr.className = "declared-panel-nested-row";
            const childTd = document.createElement("td");
            childTd.colSpan = headerLabels.length;
            childTd.appendChild(renderDeclaredPanelValue(childRows, entry, childDataSourceName, panelMeta, actionInputsByName));
            childTr.appendChild(childTd);
            tbody.appendChild(childTr);
          });
        }
      });
      table.appendChild(tbody);
      // WMS-16: dataSourceMeta.totalField (declared panelDataSource.totalField) gates a footer row
      // summing that field across every currently-rendered row -- purely a display aggregate over
      // rows already fetched, recomputed on each render() same as the rest of this table.
      if (dataSourceMeta && dataSourceMeta.totalField) {
        const totalColumn = columns.find(function (column) { return column.sourceField === dataSourceMeta.totalField; });
        const total = rows.reduce(function (sum, row) {
          const rowData = row && typeof row === "object" ? (row.data || {}) : {};
          const raw = rowData[dataSourceMeta.totalField];
          return sum + (typeof raw === "number" ? raw : Number(raw) || 0);
        }, 0);
        const tfoot = document.createElement("tfoot");
        const totalTr = document.createElement("tr");
        const totalTd = document.createElement("td");
        totalTd.colSpan = headerLabels.length;
        totalTd.className = "declared-panel-total";
        totalTd.textContent = "Total " + (totalColumn ? (totalColumn.label || totalColumn.sourceField) : dataSourceMeta.totalField) + ": " + formatValue(total);
        totalTr.appendChild(totalTd);
        tfoot.appendChild(totalTr);
        table.appendChild(tfoot);
      }
      return table;
    }


    function renderDeclaredPanelValue(value, entry, dataSourceName, panelMeta, parentActionInputsByName) {
      const columns = declaredPanelColumns(entry.fieldBindings, dataSourceName);
      const table = isDeclaredPanelGrid(value, columns, entry, dataSourceName)
        ? renderDeclaredPanelGrid(value, columns, entry, panelMeta, dataSourceName)
        : (columns && Array.isArray(value))
          ? renderDeclaredColumnsTable(value, columns, dataSourceName, panelMeta, entry, parentActionInputsByName)
          : renderDeclaredPanelValueGeneric(value, panelMeta, entry, dataSourceName);

      const addForm = renderDeclaredPanelAddRowForm(panelMeta, entry, dataSourceName, columns);
      if (!addForm) { return table; }
      const wrapper = document.createElement("div");
      wrapper.appendChild(addForm);
      wrapper.appendChild(table);
      return wrapper;
    }


    // panelLayout.type "grid" (G1d, Pigmentampas): a dataSource's rows render as a wrap of cards
    // instead of a table -- one card per row, one "label: value" line per field, an image file
    // field as a thumbnail. Cards are read-only: a dataSource with editable bindings or row-scoped
    // actions keeps the table (which is where those controls live), so "grid" never hides a control.
    // Every other layout type renders exactly as before.
    function isDeclaredPanelGrid(value, columns, entry, dataSourceName) {
      if (!entry.layout || entry.layout.type !== "grid" || !Array.isArray(value) || value.length === 0) { return false; }
      if ((columns || []).some(function (column) { return column.editable; })) { return false; }
      return rowScopedActions(entry, dataSourceName).length === 0;
    }


    function declaredPanelGridFields(rows, columns, entry) {
      if (columns && columns.length) {
        return columns.map(function (column) { return { key: column.sourceField, label: column.label || column.field }; });
      }
      const declared = (entry.layout && entry.layout.fields) || [];
      const keys = declared.length ? declared : Array.from(rows.reduce(function (set, row) {
        Object.keys(declaredPanelRowData(row)).forEach(function (key) { set.add(key); });
        return set;
      }, new Set()));
      return keys.map(function (key) { return { key: key, label: key }; });
    }


    // Records arrive as {tenantId, concept, id, data}; a procedure/query row is the plain object.
    function declaredPanelRowData(row) {
      if (!row || typeof row !== "object") { return {}; }
      return row.data && typeof row.data === "object" ? row.data : row;
    }


    function renderDeclaredPanelGrid(rows, columns, entry, panelMeta, dataSourceName) {
      const dataSourceMeta = declaredPanelDataSourceMeta(entry, dataSourceName);
      const deletable = Boolean(dataSourceMeta && (dataSourceMeta.rowOps || []).indexOf("delete") !== -1);
      const fields = declaredPanelGridFields(rows, columns, entry);
      const grid = document.createElement("div");
      grid.className = "panel-card-grid";
      rows.forEach(function (row) {
        const data = declaredPanelRowData(row);
        const card = document.createElement("div");
        card.className = "panel-card";
        fields.forEach(function (field) {
          const value = data[field.key];
          if (value && typeof value === "object" && value.key && isImageFileValue(null, value)) {
            const image = document.createElement("img");
            image.className = "panel-card-image";
            image.loading = "lazy";
            image.alt = value.originalName || field.label;
            image.src = fileHandleUrl(value);
            card.appendChild(image);
            return;
          }
          const line = document.createElement("div");
          line.className = "panel-card-field";
          const label = document.createElement("span");
          label.className = "panel-card-label";
          label.textContent = field.label;
          const text = document.createElement("span");
          text.textContent = formatValue(value);
          line.appendChild(label);
          line.appendChild(text);
          card.appendChild(line);
        });
        if (deletable && row && row.id) {
          card.appendChild(button("✕", "secondary small", function () {
            deleteDeclaredPanelRow(panelMeta, dataSourceName, row.id);
          }));
        }
        grid.appendChild(card);
      });
      return grid;
    }
    return { loadDeclaredPanel, renderDeclaredPanel };
  });
})();
