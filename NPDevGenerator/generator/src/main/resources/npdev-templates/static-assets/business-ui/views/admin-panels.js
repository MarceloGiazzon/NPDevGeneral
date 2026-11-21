/* NPDev business UI -- admin panels (pack store, box inspector, promotion) (CARVE-1).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/admin-panels.js
 * App:  static/npdev-business-ui/views/admin-panels.js  (verbatim copy; NOT Mustache)
 *
 * Moved verbatim out of business-ui-app.mustache. It runs inside a factory registered with
 * NPDevViews.registerModule (registry.js); app.js calls NPDevViews.module("adminPanels", host) once,
 * where host carries the app.js helpers/state this code uses, and destructures the returned exports.
 */
(function () {
  "use strict";
  NPDevViews.registerModule("adminPanels", function (host) {
    const {
      BOX_VIEW_SECTION, PROMOTION_SECTION, STORE_SECTION, badgeCell, button, fetchJson, headerRow, headers, lineageCell, openModal, render, sectionId, setStatus, state, text, textCell, visibleConcepts
    } = host;
    const PROMOTION_STAGES = ["S0_IDEA", "S1_DECLARED", "S2_GENERATED", "S3_CUSTOMIZED", "S4_RUNNABLE",
      "S5_TESTED", "S6_EVIDENCE_BACKED", "S7_RELEASE_APPROVED", "S8_RELEASED"];


    // Read-only "app store" view of every built-in platform pack the generator found on disk,
    // and whether internal.tables composed it into this app.
    async function loadStore() {
      state.store.loading = true;
      render();
      try {
        const payload = await fetchJson("/api/admin/packs", { method: "GET", headers: headers() });
        state.store.packs = Array.isArray(payload.packs) ? payload.packs : [];
        state.store.discoverable = Boolean(payload.discoverable);
        state.store.loaded = true;
        try {
          const intentsPayload = await fetchJson("/api/admin/packs/install-intents", { method: "GET", headers: headers() });
          const intents = Array.isArray(intentsPayload.intents) ? intentsPayload.intents : [];
          const byAlias = {};
          intents.forEach(function (intent) { byAlias[intent.alias] = Boolean(intent.configWritten); });
          state.store.installedIntents = byAlias;
        } catch (intentsError) {
          state.store.installedIntents = {};
        }
        setStatus(NPDevI18n.t("admin.store.catalogLoaded", "Pack catalog loaded."), false);
      } catch (error) {
        setStatus(error.message, true);
      }
      state.store.loading = false;
      render();
    }


    async function installPackIntent(alias) {
      try {
        const payload = await fetchJson("/api/admin/packs/" + encodeURIComponent(alias) + "/install-intent", {
          method: "POST",
          headers: headers()
        });
        state.store.installedIntents[alias] = Boolean(payload.configWritten);
        setStatus(payload.message || NPDevI18n.t("admin.store.installRecorded", "Install intent recorded."), false);
        render();
      } catch (error) {
        setStatus(error.message, true);
      }
    }


    const EDITABLE_PACK_FIELD_TYPES = ["string", "uuid", "int", "integer", "long", "boolean", "date", "datetime", "enum"];


    // Real field editing (add/change-type/remove) of an EXISTING pack's concept, via the
    // POST/PUT/DELETE .../packs/{alias}/concepts/{concept}/fields[/{field}] endpoints. Scoped to
    // scalar field types only (reference/object/array need richer sub-schemas this form doesn't
    // attempt to author). Editing pack.json has zero effect on any already-composed app until that
    // app regenerates against this pack again -- same "not hot-reload" story as everything else in
    // the Store panel.
    function renderPackConceptFieldEditor(pack, concept) {
      // Self-contained re-render: /api/admin/packs serves a generation-time-baked snapshot
      // (PackCatalogEmitter's pack-catalog.json classpath resource), so it never reflects an edit
      // made through THIS admin UI within the same running session -- calling loadStore()/a full
      // page render() after a mutation would silently show the stale pre-edit field list (and would
      // also collapse every other pack's drill-down, since renderStorePanel rebuilds the whole table
      // fresh). Instead, mutate concept.fields in place (the same object state.store.packs already
      // holds) and rebuild only this wrapper's own children.
      const wrapper = document.createElement("div");

      function refresh() {
        wrapper.innerHTML = "";
        const fields = Array.isArray(concept.fields) ? concept.fields : [];
        const table = document.createElement("table");
        table.className = "records pack-field-edit-table";
        const tbody = document.createElement("tbody");
        fields.forEach(function (field) {
          const row = document.createElement("tr");
          row.appendChild(textCell(field.name));
          row.appendChild(textCell(field.type || "string"));
          const actionsCell = document.createElement("td");
          if (field.referenceTarget) {
            actionsCell.appendChild(textNode(NPDevI18n.t("admin.store.notEditable", "(reference/bond field -- not editable here)")));
            row.appendChild(actionsCell);
            tbody.appendChild(row);
            return;
          }
          const typeSelect = document.createElement("select");
          EDITABLE_PACK_FIELD_TYPES.forEach(function (type) {
            const option = document.createElement("option");
            option.value = type;
            option.textContent = type;
            if (type === field.type) { option.selected = true; }
            typeSelect.appendChild(option);
          });
          actionsCell.appendChild(typeSelect);
          actionsCell.appendChild(button(NPDevI18n.t("admin.store.retype", "Retype"), "", async function () {
            const newType = typeSelect.value;
            let enumValues;
            if (newType === "enum") {
              const raw = window.prompt(NPDevI18n.t("admin.store.enumPrompt", "Comma-separated enum values for {field}:", { field: field.name }), "");
              if (raw === null) { return; }
              enumValues = raw.split(",").map(function (v) { return v.trim(); }).filter(Boolean);
              if (!enumValues.length) { setStatus(NPDevI18n.t("admin.store.enumRequired", "enumValues is required for type=enum"), true); return; }
            }
            try {
              const result = await fetchJson(
                "/api/admin/packs/" + encodeURIComponent(pack.alias) + "/concepts/" + encodeURIComponent(concept.name)
                  + "/fields/" + encodeURIComponent(field.name),
                { method: "PUT", headers: headers(), body: JSON.stringify({ type: newType, enumValues: enumValues }) }
              );
              setStatus(result.message || NPDevI18n.t("admin.store.fieldUpdated", "Field updated."), false);
              field.type = newType;
              refresh();
            } catch (error) {
              setStatus(error.message, true);
            }
          }));
          actionsCell.appendChild(button(NPDevI18n.t("admin.store.remove", "Remove"), "lookup-clear", async function () {
            if (!window.confirm(NPDevI18n.t("admin.store.removeConfirm", "Remove field \"{field}\" from {concept}? Takes effect on this pack's next composition.", { field: field.name, concept: concept.name }))) {
              return;
            }
            try {
              const result = await fetchJson(
                "/api/admin/packs/" + encodeURIComponent(pack.alias) + "/concepts/" + encodeURIComponent(concept.name)
                  + "/fields/" + encodeURIComponent(field.name),
                { method: "DELETE", headers: headers() }
              );
              setStatus(result.message || NPDevI18n.t("admin.store.fieldRemoved", "Field removed."), false);
              const index = concept.fields.indexOf(field);
              if (index !== -1) { concept.fields.splice(index, 1); }
              refresh();
            } catch (error) {
              setStatus(error.message, true);
            }
          }));
          row.appendChild(actionsCell);
          tbody.appendChild(row);
        });
        table.appendChild(tbody);
        wrapper.appendChild(fields.length ? table : textNode(NPDevI18n.t("admin.store.noFields", "(no fields declared)")));

        const addRow = document.createElement("div");
        addRow.className = "pack-export-row";
        const nameInput = document.createElement("input");
        nameInput.type = "text";
        nameInput.placeholder = NPDevI18n.t("admin.store.newFieldName", "New field name");
        const addTypeSelect = document.createElement("select");
        EDITABLE_PACK_FIELD_TYPES.forEach(function (type) {
          const option = document.createElement("option");
          option.value = type;
          option.textContent = type;
          addTypeSelect.appendChild(option);
        });
        addRow.appendChild(nameInput);
        addRow.appendChild(addTypeSelect);
        addRow.appendChild(button(NPDevI18n.t("admin.store.addField", "Add field"), "primary", async function () {
          let enumValues;
          if (addTypeSelect.value === "enum") {
            const raw = window.prompt(NPDevI18n.t("admin.store.enumPrompt", "Comma-separated enum values for {field}:", { field: nameInput.value }), "");
            if (raw === null) { return; }
            enumValues = raw.split(",").map(function (v) { return v.trim(); }).filter(Boolean);
            if (!enumValues.length) { setStatus(NPDevI18n.t("admin.store.enumRequired", "enumValues is required for type=enum"), true); return; }
          }
          const newFieldName = nameInput.value.trim();
          try {
            const result = await fetchJson(
              "/api/admin/packs/" + encodeURIComponent(pack.alias) + "/concepts/" + encodeURIComponent(concept.name) + "/fields",
              { method: "POST", headers: headers(), body: JSON.stringify({ name: newFieldName, type: addTypeSelect.value, enumValues: enumValues }) }
            );
            setStatus(result.message || NPDevI18n.t("admin.store.fieldAdded", "Field added."), false);
            if (!Array.isArray(concept.fields)) { concept.fields = []; }
            concept.fields.push({ name: newFieldName, type: addTypeSelect.value });
            refresh();
          } catch (error) {
            setStatus(error.message, true);
          }
        }));
        wrapper.appendChild(addRow);
      }

      refresh();
      return wrapper;
    }


    function textNode(text) {
      const span = document.createElement("span");
      span.textContent = text;
      return span;
    }


    // Visual graph view for a pack's drill-down: real boxes (one per concept, listing its fields),
    // draggable to reposition, with lines connecting a reference field to its target concept's box.
    // Positions persist in localStorage per pack alias so reopening the graph keeps the layout.
    // Field EDITING (add/change-type/remove) lives in renderPackConceptFieldEditor above, using the
    // real POST/PUT/DELETE endpoints -- this graph view stays purely about visual layout.
    function renderPackGraph(pack, concepts) {
      const container = document.createElement("div");
      container.className = "pack-graph-container";
      const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      svg.setAttribute("class", "pack-graph-edges");
      container.appendChild(svg);

      const storageKey = "npdev-store-graph-positions:" + pack.alias;
      let positions = {};
      try { positions = JSON.parse(localStorage.getItem(storageKey) || "{}"); } catch (e) { positions = {}; }

      function savePositions() {
        try { localStorage.setItem(storageKey, JSON.stringify(positions)); } catch (e) { /* best-effort */ }
      }

      const nodeEls = {};

      function redrawEdges() {
        while (svg.firstChild) { svg.removeChild(svg.firstChild); }
        let maxX = 0, maxY = 0;
        concepts.forEach(function (concept) {
          const node = nodeEls[concept.name];
          if (node) {
            maxX = Math.max(maxX, node.offsetLeft + node.offsetWidth);
            maxY = Math.max(maxY, node.offsetTop + node.offsetHeight);
          }
          (concept.fields || []).forEach(function (field) {
            if (!field.referenceTarget) { return; }
            const fromEl = nodeEls[concept.name];
            const toEl = nodeEls[field.referenceTarget];
            if (!fromEl || !toEl || fromEl === toEl) { return; }
            const x1 = fromEl.offsetLeft + fromEl.offsetWidth / 2;
            const y1 = fromEl.offsetTop + fromEl.offsetHeight / 2;
            const x2 = toEl.offsetLeft + toEl.offsetWidth / 2;
            const y2 = toEl.offsetTop + toEl.offsetHeight / 2;
            const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
            line.setAttribute("x1", String(x1));
            line.setAttribute("y1", String(y1));
            line.setAttribute("x2", String(x2));
            line.setAttribute("y2", String(y2));
            line.setAttribute("class", "pack-graph-edge-line");
            svg.appendChild(line);
            const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
            label.setAttribute("x", String((x1 + x2) / 2));
            label.setAttribute("y", String((y1 + y2) / 2));
            label.setAttribute("class", "pack-graph-edge-label");
            label.textContent = field.name;
            svg.appendChild(label);
          });
        });
        svg.setAttribute("width", String(Math.max(400, maxX + 40)));
        svg.setAttribute("height", String(Math.max(260, maxY + 40)));
      }

      concepts.forEach(function (concept, index) {
        const node = document.createElement("div");
        node.className = "graph-node";
        const defaultPos = { x: 20 + (index % 3) * 220, y: 20 + Math.floor(index / 3) * 170 };
        const pos = positions[concept.name] || defaultPos;
        positions[concept.name] = pos;
        node.style.left = pos.x + "px";
        node.style.top = pos.y + "px";

        const headerEl = document.createElement("div");
        headerEl.className = "graph-node-header";
        headerEl.textContent = concept.name;
        node.appendChild(headerEl);

        const fieldsEl = document.createElement("div");
        fieldsEl.className = "graph-node-fields";
        (concept.fields || []).forEach(function (field) {
          const fieldLine = document.createElement("div");
          fieldLine.textContent = field.name + ": " + (field.type || "string")
            + (field.referenceTarget ? (" → " + field.referenceTarget) : "");
          fieldsEl.appendChild(fieldLine);
        });
        node.appendChild(fieldsEl);

        let dragging = false;
        let startX = 0, startY = 0, originLeft = 0, originTop = 0;

        function onMouseMove(event) {
          if (!dragging) { return; }
          const newLeft = Math.max(0, originLeft + (event.clientX - startX));
          const newTop = Math.max(0, originTop + (event.clientY - startY));
          node.style.left = newLeft + "px";
          node.style.top = newTop + "px";
          positions[concept.name] = { x: newLeft, y: newTop };
          redrawEdges();
        }
        function onMouseUp() {
          if (!dragging) { return; }
          dragging = false;
          document.removeEventListener("mousemove", onMouseMove);
          document.removeEventListener("mouseup", onMouseUp);
          savePositions();
        }
        headerEl.addEventListener("mousedown", function (event) {
          dragging = true;
          startX = event.clientX;
          startY = event.clientY;
          originLeft = node.offsetLeft;
          originTop = node.offsetTop;
          document.addEventListener("mousemove", onMouseMove);
          document.addEventListener("mouseup", onMouseUp);
          event.preventDefault();
        });

        container.appendChild(node);
        nodeEls[concept.name] = node;
      });

      savePositions();
      setTimeout(redrawEdges, 0);
      return container;
    }


    function renderStorePanel() {
      const section = document.createElement("section");
      section.className = "concept-panel";
      section.id = sectionId(STORE_SECTION);

      const header = document.createElement("div");
      header.className = "panel-header";
      const title = document.createElement("h2");
      title.textContent = NPDevI18n.t("admin.store.title", "Store");
      header.appendChild(title);
      const actions = document.createElement("div");
      actions.className = "panel-actions";
      actions.appendChild(button(NPDevI18n.t("admin.store.refresh", "Refresh"), "", loadStore));
      header.appendChild(actions);
      section.appendChild(header);

      if (!state.store.loaded && !state.store.loading) {
        loadStore();
      }
      if (state.store.loaded && !state.store.discoverable) {
        const note = document.createElement("p");
        note.textContent = NPDevI18n.t("admin.store.noPacksDir", "The platform packs directory could not be located from this app's generation environment.");
        section.appendChild(note);
      }

      const filterRow = document.createElement("div");
      filterRow.className = "picker-search-row";
      const filterInput = document.createElement("input");
      filterInput.type = "search";
      filterInput.className = "picker-search";
      filterInput.placeholder = NPDevI18n.t("admin.store.filterPlaceholder", "Filter packs by name, category, or concept…");
      filterInput.value = state.store.filter || "";
      filterInput.addEventListener("input", function () {
        state.store.filter = filterInput.value;
        render();
        const refocused = document.querySelector("#" + sectionId(STORE_SECTION) + " .picker-search");
        if (refocused) { refocused.focus(); refocused.setSelectionRange(refocused.value.length, refocused.value.length); }
      });
      filterRow.appendChild(filterInput);
      section.appendChild(filterRow);

      const filterText = (state.store.filter || "").trim().toLowerCase();
      const visiblePacks = state.store.packs.filter(function (pack) {
        if (!filterText) { return true; }
        const haystack = [pack.name, pack.alias, pack.category, pack.description]
          .concat(Array.isArray(pack.conceptNames) ? pack.conceptNames : [])
          .filter(Boolean).join(" ").toLowerCase();
        return haystack.includes(filterText);
      });

      const table = document.createElement("table");
      table.className = "records";
      const thead = document.createElement("thead");
      thead.appendChild(headerRow(["", NPDevI18n.t("admin.store.colPack", "Pack"), NPDevI18n.t("admin.store.colCategory", "Category"), NPDevI18n.t("admin.store.colVersion", "Version"), NPDevI18n.t("admin.store.colConcepts", "Concepts"), NPDevI18n.t("admin.store.colDescription", "Description"), NPDevI18n.t("admin.store.colIncluded", "Included"), ""]));
      table.appendChild(thead);
      const tbody = document.createElement("tbody");
      visiblePacks.forEach(function (pack) {
        const row = document.createElement("tr");
        const toggleCell = document.createElement("td");
        const detailRow = document.createElement("tr");
        detailRow.className = "pack-detail-row";
        detailRow.style.display = "none";
        const toggleBtn = button("▶", "", function () {
          const expanded = detailRow.style.display !== "none";
          detailRow.style.display = expanded ? "none" : "";
          toggleBtn.textContent = expanded ? "▶" : "▼";
        });
        toggleCell.appendChild(toggleBtn);
        row.appendChild(toggleCell);
        row.appendChild(textCell(pack.name || pack.alias));
        row.appendChild(badgeCell(pack.category || "other", "badge-neutral"));
        row.appendChild(textCell(pack.version || "UNKNOWN"));
        row.appendChild(textCell(String(pack.conceptCount || 0)));
        row.appendChild(textCell(pack.description || ""));
        row.appendChild(badgeCell(pack.included ? NPDevI18n.t("admin.store.colIncluded", "Included") : NPDevI18n.t("admin.store.available", "Available"),pack.included ? "badge-success" : "badge-neutral"));
        const installCell = document.createElement("td");
        const alias = pack.alias;
        if (pack.included) {
          installCell.textContent = "—";
        } else if (Object.prototype.hasOwnProperty.call(state.store.installedIntents, alias)) {
          const badge = document.createElement("span");
          badge.className = "badge badge-neutral";
          badge.textContent = state.store.installedIntents[alias]
            ? NPDevI18n.t("admin.store.writtenToConfig", "Written to config.json -- regenerate to apply")
            : NPDevI18n.t("admin.store.installPending", "Install pending (regenerate to apply)");
          installCell.appendChild(badge);
        } else {
          installCell.appendChild(button(NPDevI18n.t("admin.store.install", "Install"), "", function () { installPackIntent(alias); }));
        }
        row.appendChild(installCell);
        tbody.appendChild(row);

        const detailCell = document.createElement("td");
        detailCell.colSpan = 8;
        detailCell.className = "pack-detail-cell";
        const concepts = Array.isArray(pack.concepts) && pack.concepts.length
          ? pack.concepts
          : (Array.isArray(pack.conceptNames) ? pack.conceptNames.map(function (name) { return { name: name, fields: [] }; }) : []);
        if (concepts.length) {
          const conceptsLabel = document.createElement("div");
          conceptsLabel.textContent = NPDevI18n.t("admin.store.conceptsHint", "Concepts (click to see fields):");
          detailCell.appendChild(conceptsLabel);
          const graphContainer = document.createElement("div");
          graphContainer.style.display = "none";
          const graphToggle = button(NPDevI18n.t("admin.store.showGraph", "🔗 Show graph view"), "", function () {
            const showing = graphContainer.style.display !== "none";
            if (showing) {
              graphContainer.style.display = "none";
              graphContainer.innerHTML = "";
              graphToggle.textContent = NPDevI18n.t("admin.store.showGraph", "🔗 Show graph view");
            } else {
              graphContainer.style.display = "";
              graphContainer.appendChild(renderPackGraph(pack, concepts));
              graphToggle.textContent = NPDevI18n.t("admin.store.hideGraph", "🔗 Hide graph view");
            }
          });
          detailCell.appendChild(graphToggle);
          detailCell.appendChild(graphContainer);
          concepts.forEach(function (concept) {
            const conceptWrapper = document.createElement("div");
            conceptWrapper.className = "pack-concept-row";
            const fieldsList = document.createElement("div");
            fieldsList.className = "pack-concept-fields";
            fieldsList.style.display = "none";
            const conceptToggle = button("▶ " + concept.name, "", function () {
              const expanded = fieldsList.style.display !== "none";
              fieldsList.style.display = expanded ? "none" : "";
              conceptToggle.textContent = (expanded ? "▶ " : "▼ ") + concept.name;
            });
            conceptWrapper.appendChild(conceptToggle);
            fieldsList.appendChild(renderPackConceptFieldEditor(pack, concept));
            conceptWrapper.appendChild(fieldsList);
            detailCell.appendChild(conceptWrapper);
          });
        } else {
          const conceptsLine = document.createElement("div");
          conceptsLine.textContent = NPDevI18n.t("admin.store.conceptsNone", "Concepts: (none declared)");
          detailCell.appendChild(conceptsLine);
        }
        if (pack.forkedFrom && pack.forkedFrom.pack) {
          const forkLine = document.createElement("div");
          forkLine.className = "pack-fork-badge";
          const originAuthor = pack.forkedFrom.originAuthor ? (" " + NPDevI18n.t("admin.store.forkBy", "by {author}", { author: pack.forkedFrom.originAuthor })) : "";
          const existsNote = pack.forkedFromExists === false ? (" — " + NPDevI18n.t("admin.store.forkOriginMissing", "origin not found locally")) : "";
          // Version-drift note: NOT a "your fork differs from its origin" warning (forks are SUPPOSED
          // to differ in content) -- specifically "the origin has moved to a newer version since this
          // fork declared what it was forked from", the same question a stale git fork answers.
          const driftNote = pack.forkedFromVersionDrift === true
            ? (" — " + NPDevI18n.t("admin.store.forkDrift", "origin now at v{current}, fork based on v{base}", { current: pack.forkedFromCurrentVersion, base: pack.forkedFrom.version }))
            : "";
          forkLine.textContent = NPDevI18n.t("admin.store.forkedFrom", "Forked from {pack} v{version}", { pack: pack.forkedFrom.pack, version: pack.forkedFrom.version }) + originAuthor + existsNote + driftNote;
          detailCell.appendChild(forkLine);
        }
        if (pack.author) {
          const authorLine = document.createElement("div");
          authorLine.textContent = NPDevI18n.t("admin.store.author", "Author: {author}", { author: pack.author });
          detailCell.appendChild(authorLine);
        }
        detailRow.appendChild(detailCell);
        tbody.appendChild(detailRow);
      });
      table.appendChild(tbody);
      section.appendChild(table);
      section.appendChild(renderExportConceptForm());
      return section;
    }


    // Author-time "export a concept to a new pack" (gap C.2), local-workflow-only (see the endpoint's
    // own doc comment): this app's Input/model.json and the platform NPDevContract/packs/ directory
    // are only both reachable from a sample's own local dev checkout.
    function renderExportConceptForm() {
      const wrapper = document.createElement("div");
      wrapper.className = "pack-export-form";
      const heading = document.createElement("h3");
      heading.textContent = NPDevI18n.t("admin.export.title", "Export a concept to a new pack");
      wrapper.appendChild(heading);

      const conceptSelect = document.createElement("select");
      visibleConcepts().forEach(function (concept) {
        const option = document.createElement("option");
        option.value = concept.conceptName;
        option.textContent = concept.displayName || concept.conceptName;
        conceptSelect.appendChild(option);
      });
      const packNameInput = document.createElement("input");
      packNameInput.type = "text";
      packNameInput.placeholder = NPDevI18n.t("admin.export.packName", "pack-name (lowercase, e.g. my-pack)");
      const authorInput = document.createElement("input");
      authorInput.type = "text";
      authorInput.placeholder = NPDevI18n.t("admin.export.author", "Author");
      const categoryInput = document.createElement("input");
      categoryInput.type = "text";
      categoryInput.placeholder = NPDevI18n.t("admin.export.category", "Category (e.g. other, security, web-ui, finance...)");
      categoryInput.value = "other";
      const descriptionInput = document.createElement("input");
      descriptionInput.type = "text";
      descriptionInput.placeholder = NPDevI18n.t("admin.export.description", "Description (optional)");

      const exportButton = button(NPDevI18n.t("admin.export.button", "Export"), "primary", async function () {
        const payload = {
          conceptName: conceptSelect.value,
          packName: packNameInput.value.trim(),
          author: authorInput.value.trim(),
          category: categoryInput.value.trim() || "other",
          description: descriptionInput.value.trim()
        };
        try {
          const result = await fetchJson("/api/admin/packs/export", {
            method: "POST",
            headers: headers(),
            body: JSON.stringify(payload)
          });
          setStatus(result.message || (result.exported ? NPDevI18n.t("admin.export.done", "Exported.") : NPDevI18n.t("admin.export.notDone", "Not exported.")), !result.exported);
          if (result.exported) {
            loadStore();
          }
        } catch (error) {
          setStatus(error.message, true);
        }
      });

      const row = document.createElement("div");
      row.className = "pack-export-row";
      [conceptSelect, packNameInput, authorInput, categoryInput, descriptionInput, exportButton].forEach(function (rowEl) {
        row.appendChild(rowEl);
      });
      wrapper.appendChild(row);
      return wrapper;
    }


    // P6.4 Box Inspector: Application -> Module -> Concept -> Field/Rule boxes, plus a top-level
    // Capability (+ Implementation) list, per the Box/Object/Truth model. The server upgrades every
    // box's T2 (Generated) to T3 (RunsLocally) since serving the response is itself the evidence.
    // The semantic graph (P2.2) is fetched separately and never cached into a box -- every "Why?"
    // answer is a live filter over graph.edges, not a string baked in ahead of time.
    async function loadBoxView() {
      state.boxView.loading = true;
      render();
      try {
        const [manifest, graph, truth] = await Promise.all([
          fetchJson("/api/admin/box", { method: "GET", headers: headers() }),
          fetchJson("/api/admin/box/graph", { method: "GET", headers: headers() }).catch(function () { return null; }),
          // TRUTH-1: display only -- a failure here leaves the rollup "unknown", it never fails the panel.
          fetchJson("/api/admin/box/truth", { method: "GET", headers: headers() }).catch(function () { return null; })
        ]);
        state.boxView.truth = truth;
        state.boxView.application = manifest.application || null;
        state.boxView.modules = Array.isArray(manifest.modules) ? manifest.modules : [];
        state.boxView.boxes = Array.isArray(manifest.boxes) ? manifest.boxes : [];
        state.boxView.capabilities = Array.isArray(manifest.capabilities) ? manifest.capabilities : [];
        state.boxView.graph = graph;
        state.boxView.note = manifest.truthLevelNote || "";
        state.boxView.loaded = true;
        setStatus(NPDevI18n.t("admin.box.loaded", "Box Inspector loaded."), false);
      } catch (error) {
        setStatus(error.message, true);
      }
      state.boxView.loading = false;
      render();
    }


    function truthBadge(level) {
      const badgeClass = level === "T3_RUNS_LOCALLY" ? "badge-success" : "badge-info";
      return badgeCell(level || "T2_GENERATED", badgeClass);
    }


    // Every graph edge names its endpoints by (kind, name) -- ReferenceIndex's own node vocabulary
    // (see BoxManifestEmitter's graphKind/graphName pair on every field/rule/concept/capability box).
    // "Used by" reads incoming edges (something else references THIS box); "Depends on" reads outgoing
    // edges (this box references something else). A box with no graphName (a free-form expression
    // invariant, which has no ref and so no graph node) can never be looked up -- that is reported
    // honestly rather than silently showing an empty result.
    function whyLines(kind, name) {
      if (!name || !kind) {
        return { loading: false, lines: [], unavailable: NPDevI18n.t("admin.box.noGraphNode", "No graph node recorded for this element.") };
      }
      if (!state.boxView.graph) {
        return { loading: true, lines: [], unavailable: "" };
      }
      const edges = (state.boxView.graph.edges || []);
      const lines = [];
      edges.forEach(function (edge) {
        if (edge.to && edge.to.kind === kind && edge.to.name === name) {
          lines.push(NPDevI18n.t("admin.box.usedBy", "{kind} {name} {verb} this", { kind: edge.from.kind || "", name: edge.from.name || "", verb: edge.verb || NPDevI18n.t("admin.box.verbReferences", "references") }));
        }
        if (edge.from && edge.from.kind === kind && edge.from.name === name) {
          lines.push(NPDevI18n.t("admin.box.dependsOn", "this {verb} {kind} {name}", { verb: edge.verb || NPDevI18n.t("admin.box.verbReferences", "references"), kind: edge.to.kind || "", name: edge.to.name || "" }));
        }
      });
      return { loading: false, lines: lines, unavailable: lines.length ? "" : NPDevI18n.t("admin.box.noRelationships", "No recorded relationships yet.") };
    }


    function whyParagraph(kind, name) {
      const why = whyLines(kind, name);
      const p = document.createElement("p");
      p.className = "box-why";
      if (why.loading) {
        p.textContent = NPDevI18n.t("admin.box.loadingGraph", "Loading semantic graph…");
      } else if (why.unavailable) {
        p.textContent = why.unavailable;
      } else {
        p.textContent = NPDevI18n.t("admin.box.why", "Why: {lines}", { lines: why.lines.join("; ") });
      }
      return p;
    }


    // Truncated inline summary (mirrors lineageCell's "counts + full detail in the native tooltip"
    // pattern) -- used in Field/Rule rows, where a full multi-line "Why" per row would overwhelm the
    // table, but the answer must still be reachable without another click-through.
    function whyCell(kind, name) {
      const why = whyLines(kind, name);
      const cell = document.createElement("td");
      if (why.loading) {
        cell.textContent = "…";
        return cell;
      }
      if (why.unavailable) {
        cell.textContent = "-";
        cell.title = why.unavailable;
        return cell;
      }
      cell.textContent = why.lines.length === 1 ? NPDevI18n.t("admin.box.relationshipOne", "{count} relationship", { count: why.lines.length }) : NPDevI18n.t("admin.box.relationshipMany", "{count} relationships", { count: why.lines.length });
      cell.title = why.lines.join("\n");
      return cell;
    }


    function buildConceptInspectorContent(box) {
      const wrapper = document.createElement("div");

      const summary = document.createElement("p");
      summary.textContent = NPDevI18n.t("admin.box.conceptSummary", "Module: {module} | Table: {table} | Declared truth level: {level}", { module: box.module || NPDevI18n.t("admin.box.ungrouped", "Ungrouped"), table: box.table || "-", level: box.declaredTruthLevel || "-" });
      wrapper.appendChild(summary);
      wrapper.appendChild(whyParagraph(box.graphKind, box.graphName));

      const fieldsHeading = document.createElement("h4");
      fieldsHeading.textContent = NPDevI18n.t("admin.box.fields", "Fields");
      wrapper.appendChild(fieldsHeading);
      const fieldsTable = document.createElement("table");
      fieldsTable.className = "records";
      const fieldsHead = document.createElement("thead");
      fieldsHead.appendChild(headerRow([NPDevI18n.t("admin.box.colField", "Field"), NPDevI18n.t("admin.box.colType", "Type"), NPDevI18n.t("admin.box.colRequired", "Required"), NPDevI18n.t("admin.box.colTruthLevel", "Truth Level"), NPDevI18n.t("admin.box.colWhy", "Why")]));
      fieldsTable.appendChild(fieldsHead);
      const fieldsBody = document.createElement("tbody");
      (box.fields || []).forEach(function (field) {
        const row = document.createElement("tr");
        row.appendChild(textCell(field.name));
        row.appendChild(textCell(field.dslType || ""));
        row.appendChild(textCell(field.required ? NPDevI18n.t("admin.box.yes", "Yes") : NPDevI18n.t("admin.box.no", "No")));
        row.appendChild(truthBadge(field.truthLevel));
        row.appendChild(whyCell(field.graphKind, field.graphName));
        fieldsBody.appendChild(row);
      });
      fieldsTable.appendChild(fieldsBody);
      wrapper.appendChild(fieldsTable);

      const rulesHeading = document.createElement("h4");
      rulesHeading.textContent = NPDevI18n.t("admin.box.rules", "Rules");
      wrapper.appendChild(rulesHeading);
      if (!(box.rules || []).length) {
        const noRules = document.createElement("p");
        noRules.textContent = NPDevI18n.t("admin.box.noRules", "No rules declared on this concept.");
        wrapper.appendChild(noRules);
      } else {
        const rulesTable = document.createElement("table");
        rulesTable.className = "records";
        const rulesHead = document.createElement("thead");
        rulesHead.appendChild(headerRow([NPDevI18n.t("admin.box.colRule", "Rule"), NPDevI18n.t("admin.box.colTruthLevel", "Truth Level"), NPDevI18n.t("admin.box.colWhy", "Why")]));
        rulesTable.appendChild(rulesHead);
        const rulesBody = document.createElement("tbody");
        box.rules.forEach(function (rule) {
          const row = document.createElement("tr");
          row.appendChild(textCell(rule.description));
          row.appendChild(truthBadge(rule.truthLevel));
          row.appendChild(whyCell(rule.graphKind, rule.graphName));
          rulesBody.appendChild(row);
        });
        rulesTable.appendChild(rulesBody);
        wrapper.appendChild(rulesTable);
      }
      return wrapper;
    }


    function inspectConcept(box) {
      openModal(NPDevI18n.t("admin.box.conceptTitle", "{name} (Concept Box)", { name: box.displayName || box.conceptName }), buildConceptInspectorContent(box));
    }


    function inspectCapability(capability) {
      const wrapper = document.createElement("div");
      const summary = document.createElement("p");
      summary.textContent = NPDevI18n.t("admin.box.capabilitySummary", "Type: {type} | Implementation: {implementation}", { type: capability.type || "-", implementation: capability.implementation ? capability.implementation.adapter : NPDevI18n.t("admin.box.notBound", "Not yet bound to an adapter") });
      wrapper.appendChild(summary);
      wrapper.appendChild(whyParagraph(capability.graphKind, capability.graphName));
      openModal(NPDevI18n.t("admin.box.capabilityTitle", "{name} (Capability Box)", { name: capability.name }), wrapper);
    }


    // TRUTH-1: the app-level rollup (GET /api/admin/box/truth). For every concept the level the model
    // DECLARES next to the level the evidence SUPPORTS, and each piece of evidence as a link that opens
    // its record. Display only -- nothing here blocks anything, and every missing piece reads "unknown".
    const TRUTH_LEVEL_LABELS = { T0: "Idea", T1: "Declared", T2: "Generated", T3: "RunsLocally",
      T4: "Tested", T5: "EvidenceBacked", T6: "ReleaseApproved" };

    function levelText(level) {
      return level ? level + " " + (TRUTH_LEVEL_LABELS[level] || "") : NPDevI18n.t("admin.box.unknown", "unknown");
    }


    function levelBadgeClass(level) {
      const rank = level ? Number(String(level).slice(1)) : -1;
      if (rank >= 4) return "badge-success";
      if (rank === 3) return "badge-info";
      return "badge-neutral";
    }


    function claimBadgeClass(claim) {
      if (claim === "ok") return "badge-success";
      if (claim === "overclaim") return "badge-danger";
      return "badge-neutral";
    }


    function appendDetailRow(table, label, value) {
      if (value === null || value === undefined || value === "") return;
      const row = document.createElement("tr");
      row.appendChild(textCell(label));
      row.appendChild(textCell(String(value)));
      table.appendChild(row);
    }


    // The evidence link: fetches the record behind the row's href (the endpoint is super-user gated and
    // the key travels in a header, so a plain anchor could not open it) and shows it in a modal.
    async function openEvidence(evidence) {
      const wrapper = document.createElement("div");
      wrapper.className = "truth-evidence";
      const loading = document.createElement("p");
      loading.textContent = NPDevI18n.t("admin.box.loadingEvidence", "Loading evidence…");
      wrapper.appendChild(loading);
      openModal(NPDevI18n.t("admin.box.evidenceTitle", "Evidence: {id}", { id: evidence.id }), wrapper);
      let record = null;
      let problem = "";
      try {
        record = evidence.href ? await fetchJson(evidence.href, { method: "GET", headers: headers() }) : evidence;
      } catch (error) {
        problem = error.message || String(error);
      }
      wrapper.innerHTML = "";
      if (!record) {
        const unavailable = document.createElement("p");
        unavailable.textContent = NPDevI18n.t("admin.box.evidenceUnavailable", "Evidence record unavailable ({problem}). Recorded summary: {summary}", { problem: problem, summary: evidence.summary || "-" });
        wrapper.appendChild(unavailable);
        return;
      }
      const summary = document.createElement("p");
      summary.textContent = record.summary || "";
      wrapper.appendChild(summary);
      const table = document.createElement("table");
      table.className = "records truth-evidence-detail";
      const body = document.createElement("tbody");
      appendDetailRow(body, NPDevI18n.t("admin.truth.rowKind", "Kind"), record.kind);
      appendDetailRow(body, NPDevI18n.t("admin.truth.rowSupportsLevel", "Supports level"), record.level);
      appendDetailRow(body, NPDevI18n.t("admin.truth.rowStatus", "Status"), record.status + (record.observedStatus ? " " + NPDevI18n.t("admin.truth.wasStatus", "(was {status})", { status: record.observedStatus }) : ""));
      appendDetailRow(body, NPDevI18n.t("admin.truth.rowObservedAt", "Observed at"), record.observedAt || NPDevI18n.t("admin.box.unknown", "unknown"));
      appendDetailRow(body, NPDevI18n.t("admin.truth.rowWhyNotCounted", "Why not counted"), record.staleReason);
      appendDetailRow(body, NPDevI18n.t("admin.truth.rowSource", "Source"), record.source && record.source.path);
      appendDetailRow(body, NPDevI18n.t("admin.truth.rowSample", "Sample"), record.source && record.source.sampleId);
      table.appendChild(body);
      wrapper.appendChild(table);
      if (record.detail && Object.keys(record.detail).length) {
        const detailHeading = document.createElement("h4");
        detailHeading.textContent = NPDevI18n.t("admin.truth.detail", "Detail");
        wrapper.appendChild(detailHeading);
        const pre = document.createElement("pre");
        pre.textContent = JSON.stringify(record.detail, null, 2);
        wrapper.appendChild(pre);
      }
    }


    function evidenceButton(evidence, label) {
      const link = button(label || (evidence.kind + (evidence.status === "observed" ? "" : " " + evidence.status)), "evidence-link", function () {
        openEvidence(evidence);
      });
      link.setAttribute("data-evidence-id", evidence.id);
      link.setAttribute("data-evidence-status", evidence.status);
      link.title = evidence.summary || evidence.id;
      return link;
    }


    // Many concepts carry dozens of test runs: generated/runtime stay one button each, the test runs
    // collapse into ONE grouped button ("7 passed, 1 known-failure") that lists every run, each of them
    // a link to its own record.
    function openEvidenceList(title, list) {
      const wrapper = document.createElement("div");
      wrapper.className = "truth-evidence-list";
      list.forEach(function (evidence) {
        const row = document.createElement("p");
        row.appendChild(evidenceButton(evidence, evidence.status));
        row.appendChild(document.createTextNode(" " + (evidence.summary || evidence.id)));
        wrapper.appendChild(row);
      });
      openModal(title, wrapper);
    }


    function evidenceGroupButton(title, list) {
      const counts = {};
      list.forEach(function (evidence) { counts[evidence.status] = (counts[evidence.status] || 0) + 1; });
      const label = Object.keys(counts).map(function (status) { return counts[status] + " " + status; }).join(", ");
      const link = button(NPDevI18n.t("admin.truth.testsLabel", "tests: {label}", { label: label }), "evidence-link evidence-group", function () { openEvidenceList(title, list); });
      link.setAttribute("data-evidence-group", String(list.length));
      link.title = NPDevI18n.t("admin.truth.openTests", "Open the {count} test run(s) behind this row", { count: list.length });
      return link;
    }


    function evidenceLinks(evidenceList, ownerName) {
      const cell = document.createElement("td");
      cell.className = "truth-evidence-cell";
      const tests = [];
      (evidenceList || []).forEach(function (evidence) {
        if (evidence.kind === "generated" || evidence.kind === "runtime") {
          cell.appendChild(evidenceButton(evidence));
          cell.appendChild(document.createTextNode(" "));
        } else {
          tests.push(evidence);
        }
      });
      if (tests.length) {
        cell.appendChild(evidenceGroupButton(NPDevI18n.t("admin.truth.testEvidenceTitle", "Test evidence: {owner}", { owner: ownerName }), tests));
      }
      return cell;
    }


    function renderTruthRollup(section) {
      const heading = document.createElement("h3");
      heading.textContent = NPDevI18n.t("admin.truth.title", "Truth rollup");
      section.appendChild(heading);
      const truth = state.boxView.truth;
      const lines = document.createElement("div");
      lines.className = "truth-rollup-summary";
      if (!truth || !truth.summary) {
        const unknown = document.createElement("p");
        unknown.textContent = state.boxView.loading
            ? NPDevI18n.t("admin.truth.loading", "Loading truth rollup…")
            : NPDevI18n.t("admin.truth.unavailable", "Truth rollup unavailable: every level is unknown.");
        lines.appendChild(unknown);
        section.appendChild(lines);
        return;
      }
      const summary = truth.summary;
      const headline = document.createElement("p");
      headline.className = "truth-headline";
      headline.textContent = NPDevI18n.t("admin.truth.headline", "Lowest level in this app (supported by evidence): {supported} | lowest declared by the model: {declared}", { supported: levelText(summary.lowestSupported), declared: levelText(summary.lowestDeclared) });
      lines.appendChild(headline);
      const counts = document.createElement("p");
      counts.className = "truth-counts";
      counts.textContent = NPDevI18n.t("admin.truth.counts", "{concepts} concepts | {tested} tested (T4 or above) | {overclaims} declared above their evidence | {stale} stale evidence | {failed} failed evidence | {known} known failures", { concepts: summary.conceptCount, tested: summary.testedConcepts, overclaims: summary.overclaims, stale: summary.staleEvidence, failed: summary.failedEvidence, known: summary.knownFailureEvidence || 0 });
      lines.appendChild(counts);
      const promotion = document.createElement("p");
      promotion.className = "truth-promotion";
      promotion.textContent = NPDevI18n.t("admin.truth.promotionStage", "App-wide promotion stage (a separate S0-S8 ladder, evidence only): {stage}", { stage: state.promotion && state.promotion.currentStage ? state.promotion.currentStage : NPDevI18n.t("admin.box.unknown", "unknown") });
      lines.appendChild(promotion);
      if (truth.note) {
        const note = document.createElement("p");
        note.textContent = truth.note;
        lines.appendChild(note);
      }
      section.appendChild(lines);

      const moduleByConcept = {};
      state.boxView.boxes.forEach(function (box) { moduleByConcept[box.conceptName] = box.module; });
      const table = document.createElement("table");
      table.className = "records truth-rollup";
      const thead = document.createElement("thead");
      thead.appendChild(headerRow([NPDevI18n.t("admin.truth.colConcept", "Concept"), NPDevI18n.t("admin.truth.colDeclared", "Declared"), NPDevI18n.t("admin.truth.colSupported", "Supported by evidence"), NPDevI18n.t("admin.truth.colClaim", "Claim"), NPDevI18n.t("admin.truth.colEvidence", "Evidence")]));
      table.appendChild(thead);
      const tbody = document.createElement("tbody");
      (truth.concepts || []).forEach(function (concept) {
        if (state.boxView.moduleFilter && moduleByConcept[concept.conceptName] !== state.boxView.moduleFilter) {
          return;
        }
        const row = document.createElement("tr");
        row.setAttribute("data-concept", concept.conceptName);
        row.appendChild(textCell(concept.displayName || concept.conceptName));
        row.appendChild(badgeCell(levelText(concept.declaredLevel), levelBadgeClass(concept.declaredLevel)));
        row.appendChild(badgeCell(levelText(concept.supportedLevel), levelBadgeClass(concept.supportedLevel)));
        row.appendChild(badgeCell(concept.claim || NPDevI18n.t("admin.box.unknown", "unknown"),claimBadgeClass(concept.claim)));
        row.appendChild(evidenceLinks(concept.evidence, concept.displayName || concept.conceptName));
        tbody.appendChild(row);
      });
      table.appendChild(tbody);
      section.appendChild(table);

      if ((truth.appEvidence || []).length) {
        const appHeading = document.createElement("h4");
        appHeading.textContent = NPDevI18n.t("admin.truth.appEvidence", "App-wide evidence");
        section.appendChild(appHeading);
        const appLine = document.createElement("p");
        appLine.className = "truth-app-evidence";
        appLine.appendChild(evidenceGroupButton(NPDevI18n.t("admin.truth.appEvidence", "App-wide evidence"), truth.appEvidence || []));
        section.appendChild(appLine);
      }
    }


    function renderBoxViewPanel() {
      const section = document.createElement("section");
      section.className = "concept-panel";
      section.id = sectionId(BOX_VIEW_SECTION);

      const header = document.createElement("div");
      header.className = "panel-header";
      const title = document.createElement("h2");
      title.textContent = NPDevI18n.t("admin.box.title", "Box Inspector");
      header.appendChild(title);
      const actions = document.createElement("div");
      actions.className = "panel-actions";
      actions.appendChild(button(NPDevI18n.t("admin.box.refresh", "Refresh"), "", loadBoxView));
      header.appendChild(actions);
      section.appendChild(header);

      if (!state.boxView.loaded && !state.boxView.loading) {
        loadBoxView();
      }

      // Application Box: the root of the hierarchy every Module/Concept box below belongs to.
      if (state.boxView.application) {
        const appLine = document.createElement("p");
        appLine.textContent = NPDevI18n.t("admin.box.application", "Application: {name}", { name: state.boxView.application.name || "" });
        const appBadge = document.createElement("span");
        const badgeClass = state.boxView.application.truthLevel === "T3_RUNS_LOCALLY" ? "badge-success" : "badge-info";
        appBadge.className = "badge " + badgeClass;
        appBadge.textContent = state.boxView.application.truthLevel || "T2_GENERATED";
        appLine.appendChild(document.createTextNode(" "));
        appLine.appendChild(appBadge);
        section.appendChild(appLine);
      }
      if (state.boxView.note) {
        const note = document.createElement("p");
        note.textContent = state.boxView.note;
        section.appendChild(note);
      }

      // Module Box filter: narrows the concept table below to one module's concepts. Left at "All
      // modules" the table shows every concept, same as the box view did before progressive
      // disclosure existed.
      if (state.boxView.modules.length) {
        const moduleLabel = document.createElement("label");
        moduleLabel.className = "module-filter";
        moduleLabel.textContent = NPDevI18n.t("admin.box.moduleLabel", "Module:") + " ";
        const select = document.createElement("select");
        const allOption = document.createElement("option");
        allOption.value = "";
        allOption.textContent = NPDevI18n.t("admin.box.allModules", "All modules ({count} concepts)", { count: state.boxView.boxes.length });
        select.appendChild(allOption);
        state.boxView.modules.forEach(function (module) {
          const option = document.createElement("option");
          option.value = module.name;
          option.textContent = module.name + " (" + module.conceptCount + ")";
          select.appendChild(option);
        });
        select.value = state.boxView.moduleFilter;
        select.addEventListener("change", function () {
          state.boxView.moduleFilter = select.value;
          render();
        });
        moduleLabel.appendChild(select);
        section.appendChild(moduleLabel);
      }

      renderTruthRollup(section);

      const table = document.createElement("table");
      table.className = "records";
      const thead = document.createElement("thead");
      thead.appendChild(headerRow([NPDevI18n.t("admin.box.colEntityBox", "Entity Box"), NPDevI18n.t("admin.box.colModule", "Module"), NPDevI18n.t("admin.box.colTable", "Table"), NPDevI18n.t("admin.box.fields", "Fields"), NPDevI18n.t("admin.box.colBonds", "Bonds"), NPDevI18n.t("admin.box.colSource", "Source"), NPDevI18n.t("admin.box.colSpecializes", "Specializes"), NPDevI18n.t("admin.box.colTruthLevel", "Truth Level"), ""]));
      table.appendChild(thead);
      const tbody = document.createElement("tbody");
      const visibleBoxes = state.boxView.moduleFilter
          ? state.boxView.boxes.filter(function (box) { return box.module === state.boxView.moduleFilter; })
          : state.boxView.boxes;
      visibleBoxes.forEach(function (box) {
        const row = document.createElement("tr");
        row.appendChild(textCell(box.displayName || box.conceptName));
        row.appendChild(textCell(box.module || NPDevI18n.t("admin.box.ungrouped", "Ungrouped")));
        row.appendChild(textCell(box.table || ""));
        row.appendChild(textCell(String(box.fieldCount || 0)));
        row.appendChild(textCell(String(box.bondCount || 0)));
        row.appendChild(textCell(box.admin ? NPDevI18n.t("admin.box.builtInPack", "Built-in pack") : NPDevI18n.t("admin.box.appAuthored", "App-authored")));
        row.appendChild(lineageCell(box.specializes));
        row.appendChild(truthBadge(box.truthLevel));
        const inspectCell = document.createElement("td");
        inspectCell.appendChild(button(NPDevI18n.t("admin.box.inspect", "Inspect"), "", function () { inspectConcept(box); }));
        row.appendChild(inspectCell);
        tbody.appendChild(row);
      });
      table.appendChild(tbody);
      section.appendChild(table);

      // Capability Box + Implementation: a flat top-level list (capabilities aren't owned by a single
      // module), each row answering both "what does this capability need" (type) and "how is it
      // actually carried out right now" (implementation/adapter, or honestly "not yet bound").
      if (state.boxView.capabilities.length) {
        const capHeading = document.createElement("h3");
        capHeading.textContent = NPDevI18n.t("admin.box.capabilities", "Capabilities");
        section.appendChild(capHeading);
        const capTable = document.createElement("table");
        capTable.className = "records";
        const capHead = document.createElement("thead");
        capHead.appendChild(headerRow([NPDevI18n.t("admin.box.colCapability", "Capability"), NPDevI18n.t("admin.box.colType", "Type"), NPDevI18n.t("admin.box.colImplementation", "Implementation"), NPDevI18n.t("admin.box.colTruthLevel", "Truth Level"), ""]));
        capTable.appendChild(capHead);
        const capBody = document.createElement("tbody");
        state.boxView.capabilities.forEach(function (capability) {
          const row = document.createElement("tr");
          row.appendChild(textCell(capability.name));
          row.appendChild(textCell(capability.type || ""));
          row.appendChild(textCell(capability.implementation ? capability.implementation.adapter : NPDevI18n.t("admin.box.notBoundShort", "Not yet bound")));
          row.appendChild(truthBadge(capability.truthLevel));
          const inspectCell = document.createElement("td");
          inspectCell.appendChild(button(NPDevI18n.t("admin.box.inspect", "Inspect"), "", function () { inspectCapability(capability); }));
          row.appendChild(inspectCell);
          capBody.appendChild(row);
        });
        capTable.appendChild(capBody);
        section.appendChild(capTable);
      }
      return section;
    }


    // S0-S8 promotion lifecycle (see PromotionController/PromotionStateService). The endpoint
    // returns the current accepted stage AND the full append-only history (including past REJECTED
    // attempts) in one call, so a single load covers both the status line and the audit table.
    async function loadPromotion() {
      state.promotion.loading = true;
      render();
      try {
        const payload = await fetchJson("/api/admin/promotion", { method: "GET", headers: headers() });
        state.promotion.currentStage = payload.currentStage || "";
        state.promotion.history = Array.isArray(payload.history) ? payload.history : [];
        state.promotion.loaded = true;
        setStatus(NPDevI18n.t("admin.promotion.loaded", "Promotion state loaded."), false);
      } catch (error) {
        setStatus(error.message, true);
      }
      // A completed attempt (success OR failure) must not auto-retry. On in-memory-storage apps
      // /api/admin/promotion returns 503 and never sets loaded=true, which previously made
      // renderPromotionPanel()'s auto-load guard re-trigger loadPromotion() on every render -- an
      // unbounded loop. The Refresh button (which calls loadPromotion directly) is unaffected.
      state.promotion.attempted = true;
      state.promotion.loading = false;
      render();
    }


    // A rejected advance attempt (stage-skip, missing evidence, missing ADMIN role) still gets
    // recorded as a REJECTED history event server-side even though the HTTP call itself fails with
    // 400 -- so the history is always reloaded afterward regardless of outcome, not only on success.
    async function advancePromotion(stage, evidence) {
      try {
        await fetchJson("/api/admin/promotion/advance", {
          method: "POST",
          headers: headers(),
          body: JSON.stringify({ stage: stage, evidence: evidence })
        });
        setStatus(NPDevI18n.t("admin.promotion.advanced", "Promotion advanced to {stage}.", { stage: stage }), false);
      } catch (error) {
        setStatus(error.message, true);
      } finally {
        await loadPromotion();
      }
    }


    function formatTimestampMs(value) {
      const n = Number(value);
      if (!n) return "";
      try { return new Date(n).toISOString(); } catch (e) { return String(value); }
    }


    function renderPromotionPanel() {
      const section = document.createElement("section");
      section.className = "concept-panel";
      section.id = sectionId(PROMOTION_SECTION);

      const header = document.createElement("div");
      header.className = "panel-header";
      const title = document.createElement("h2");
      title.textContent = NPDevI18n.t("admin.promotion.title", "Promotion");
      header.appendChild(title);
      const actions = document.createElement("div");
      actions.className = "panel-actions";
      actions.appendChild(button(NPDevI18n.t("admin.promotion.refresh", "Refresh"), "", loadPromotion));
      header.appendChild(actions);
      section.appendChild(header);

      if (!state.promotion.loaded && !state.promotion.loading && !state.promotion.attempted) {
        loadPromotion();
      }

      const status = document.createElement("p");
      status.textContent = NPDevI18n.t("admin.promotion.currentStage", "Current stage: {stage}", { stage: state.promotion.currentStage || NPDevI18n.t("admin.promotion.unknownStage", "(unknown)") });
      section.appendChild(status);

      const advanceRow = document.createElement("div");
      advanceRow.className = "filters";
      const stageSelect = document.createElement("select");
      PROMOTION_STAGES.forEach(function (stage) {
        const option = document.createElement("option");
        option.value = stage;
        option.textContent = stage;
        stageSelect.appendChild(option);
      });
      const evidenceInput = document.createElement("input");
      evidenceInput.type = "text";
      evidenceInput.placeholder = NPDevI18n.t("admin.promotion.evidencePlaceholder", "Evidence (required from S5 onward)");
      advanceRow.appendChild(stageSelect);
      advanceRow.appendChild(evidenceInput);
      advanceRow.appendChild(button(NPDevI18n.t("admin.promotion.advance", "Advance"), "primary", function () {
        advancePromotion(stageSelect.value, evidenceInput.value);
      }));
      section.appendChild(advanceRow);

      const table = document.createElement("table");
      table.className = "records";
      const thead = document.createElement("thead");
      thead.appendChild(headerRow([NPDevI18n.t("admin.promotion.colWhen", "When"), NPDevI18n.t("admin.promotion.colStage", "Stage"), NPDevI18n.t("admin.promotion.colActor", "Actor"), NPDevI18n.t("admin.promotion.colRoles", "Roles"), NPDevI18n.t("admin.promotion.colEvidence", "Evidence"), NPDevI18n.t("admin.promotion.colOutcome", "Outcome"), NPDevI18n.t("admin.promotion.colReason", "Reason")]));
      table.appendChild(thead);
      const tbody = document.createElement("tbody");
      state.promotion.history.forEach(function (event) {
        const row = document.createElement("tr");
        row.appendChild(textCell(formatTimestampMs(event.timestampMs)));
        row.appendChild(textCell(event.stage || ""));
        row.appendChild(textCell(event.actorId || ""));
        row.appendChild(textCell(event.roles || ""));
        row.appendChild(textCell(event.evidence || ""));
        row.appendChild(badgeCell(event.outcome || "", event.outcome === "ACCEPTED" ? "badge-success" : "badge-danger"));
        row.appendChild(textCell(event.reasonCode || ""));
        tbody.appendChild(row);
      });
      table.appendChild(tbody);
      section.appendChild(table);
      return section;
    }
    return { loadStore, renderStorePanel, loadBoxView, renderBoxViewPanel, loadPromotion, renderPromotionPanel };
  });
})();
