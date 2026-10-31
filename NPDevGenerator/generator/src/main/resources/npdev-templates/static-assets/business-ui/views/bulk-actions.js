/* NPDev business UI -- bulk selection and bulk actions (CARVE-1).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/bulk-actions.js
 * App:  static/npdev-business-ui/views/bulk-actions.js  (verbatim copy; NOT Mustache)
 *
 * Moved verbatim out of business-ui-app.mustache. It runs inside a factory registered with
 * NPDevViews.registerModule (registry.js); app.js calls NPDevViews.module("bulkActions", host) once,
 * where host carries the app.js helpers/state this code uses, and destructures the returned exports.
 */
(function () {
  "use strict";
  NPDevViews.registerModule("bulkActions", function (host) {
    const {
      buildCsv, button, closeModal, createInput, csvFilenameFor, downloadCsv, fetchJson, formFields, headerRow, headers, importCellToPayloadValue, neutralizeCsvFormula, notify, openDetailsMany, openModal, reloadAfterMutation, render, setStatus, textCell, visibleFields
    } = host;

    // ---------------------------------------------------------------------------------------------
    // R7.8: bulk selection + bulk actions (delete, set-field-on-selected) on the generic concept grid
    // (renderTable below -- NOT the declared-Panel table renderDeclaredColumnsTable, which binds to
    // an author-defined dataSource that need not even be a plain concept and already has its own
    // action model via entry.actions/rowOps; extending bulk actions there is a separate, larger
    // change this item does not cover).
    //
    // Design decision recorded in the roadmap item: a SERVER-SIDE batched endpoint, not a client-side
    // loop, so one request gets one coherent audit trail instead of N independent ones. The batch
    // endpoint (business-concept-crud-controller.mustache, POST {conceptName}/batch) does not do its
    // own persistence -- it loops the SAME ConceptBinding.update()/delete() references the existing
    // single-record PUT/DELETE endpoints already call (business-concept-crud-controller.mustache),
    // which is the proven governed path: checkCrudPermission, ConceptGateway.save/delete (row-level
    // access + invariants), and the audit trail, all per row, exactly as a single PUT/DELETE already
    // gets. A row a caller lacks permission for comes back as one failed outcome (code "forbidden"),
    // never a whole-batch rejection -- so a mixed-permission selection still applies to every row it
    // can and reports the rest, mirroring how a single unauthorized PUT/DELETE already fails today
    // (a 403 from checkCrudPermission), just captured per id instead of aborting the HTTP response.
    //
    // No client-side "may I do this" catalog exists to consult before offering these actions (the
    // generic grid's existing View/Edit/Delete buttons are unconditional today too -- permission is
    // enforced server-side, never hidden client-side); Delete/Set field are offered to every caller
    // the same way, and a forbidden row is reported as a per-row failure rather than pre-filtered out.

    // Candidate fields for "set field on selected": the same flat-scalar shape importableFields (CSV
    // import) already established -- non-readOnly, no array/object/file -- minus reference fields
    // (typing a raw foreign-key id across many rows blind is error-prone and out of this item's
    // scope) and minus custom widgets (no generic way to drive one without a record to bind into).
    function bulkEditableFields(concept) {
      return formFields(concept, "edit").filter(function (field) {
        if (field.type === "array" || field.type === "object" || field.type === "file") { return false; }
        if (field.widget === "multiselect" || field.widget === "custom") { return false; }
        return !field.reference;
      });
    }


    function selectedRecordIds(panel) {
      return panel.selectedIds || (panel.selectedIds = []);
    }


    function isRowSelected(panel, id) {
      return selectedRecordIds(panel).indexOf(String(id)) !== -1;
    }


    function toggleRowSelected(panel, id, selected) {
      const ids = selectedRecordIds(panel);
      const key = String(id);
      const at = ids.indexOf(key);
      if (selected && at === -1) {
        ids.push(key);
      } else if (!selected && at !== -1) {
        ids.splice(at, 1);
      }
    }


    function clearSelection(panel) {
      panel.selectedIds = [];
    }


    // Reuses importCellToPayloadValue's boolean/numeric coercion (the same rules submitForm applies
    // to a typed form field) so a bulk-set value round-trips to the same JSON shape a normal edit
    // would send, rather than a third, slightly different parsing path.
    function bulkValueFromControl(field, control) {
      if (control.type === "checkbox") {
        return control.checked;
      }
      return importCellToPayloadValue(field, control.value);
    }


    async function postConceptBatch(concept, operation, ids, extra) {
      const body = Object.assign({ operation: operation, ids: ids }, extra || {});
      return fetchJson(concept.endpointBase + "/batch", {
        method: "POST",
        headers: headers(),
        body: JSON.stringify(body)
      });
    }


    // Reuses .import-preview-table/.badge-* wholesale (business-ui-style.mustache) rather than
    // introducing a second results-table style -- one row per requested id, "OK" or the server's own
    // error code, and its message when it failed.
    function showBatchResultsModal(concept, operationLabel, response) {
      const results = Array.isArray(response.results) ? response.results : [];
      const body = document.createElement("div");
      body.className = "import-modal-body";
      const summary = document.createElement("p");
      summary.className = "import-summary";
      summary.textContent = operationLabel + " complete: " + (response.succeeded || 0) + " succeeded, "
          + (response.failed || 0) + " failed (of " + (response.requested || results.length) + " selected).";
      body.appendChild(summary);
      const wrap = document.createElement("div");
      wrap.className = "import-preview-wrap";
      const table = document.createElement("table");
      table.className = "import-preview-table";
      const thead = document.createElement("thead");
      thead.appendChild(headerRow(["Record", "Result", "Message"]));
      table.appendChild(thead);
      const tbody = document.createElement("tbody");
      results.forEach(function (row) {
        const tr = document.createElement("tr");
        tr.appendChild(textCell(row.id));
        const statusTd = document.createElement("td");
        const badge = document.createElement("span");
        badge.className = "badge " + (row.ok ? "badge-success" : "badge-danger");
        badge.textContent = row.ok ? "OK" : (row.code || "Error");
        statusTd.appendChild(badge);
        tr.appendChild(statusTd);
        tr.appendChild(textCell(row.ok ? "" : (row.message || "")));
        tbody.appendChild(tr);
      });
      table.appendChild(tbody);
      wrap.appendChild(table);
      body.appendChild(wrap);
      openModal(operationLabel + " results -- " + concept.displayName, body);
    }


    async function runBulkDelete(concept, panel) {
      const ids = selectedRecordIds(panel).slice();
      if (!ids.length) { return; }
      if (!window.confirm("Delete " + ids.length + " selected " + concept.displayName + " record(s)? This cannot be undone.")) {
        return;
      }
      try {
        const response = await postConceptBatch(concept, "delete", ids);
        clearSelection(panel);
        showBatchResultsModal(concept, "Delete", response);
        notify("Deleted " + (response.succeeded || 0) + " of " + ids.length + " selected record(s).", (response.failed || 0) > 0);
        if ((response.succeeded || 0) > 0) {
          await reloadAfterMutation(concept);
        } else {
          render();
        }
      } catch (error) {
        notify(error.message, true);
      }
    }


    // The field-picker dialog IS the confirm step (an explicit "Set field" click after choosing both
    // field and value), plus a window.confirm restating exactly what is about to happen -- the same
    // belt-and-suspenders pattern deleteRecord/runBulkDelete use for the single/bulk delete actions.
    function openBulkSetFieldDialog(concept, panel) {
      const ids = selectedRecordIds(panel).slice();
      if (!ids.length) { return; }
      const fields = bulkEditableFields(concept);
      if (!fields.length) {
        notify("No editable fields are available for bulk update on " + concept.displayName + ".", true);
        return;
      }
      const body = document.createElement("div");
      body.className = "import-modal-body";
      const intro = document.createElement("p");
      intro.className = "import-intro";
      intro.textContent = "Set a field to a new value on " + ids.length + " selected " + concept.displayName + " record(s).";
      body.appendChild(intro);

      const fieldLabel = document.createElement("label");
      const fieldLabelText = document.createElement("span");
      fieldLabelText.className = "field-label-text";
      fieldLabelText.textContent = "Field";
      fieldLabel.appendChild(fieldLabelText);
      const fieldSelect = document.createElement("select");
      fields.forEach(function (field) {
        const option = document.createElement("option");
        option.value = field.name;
        option.textContent = field.label || field.name;
        fieldSelect.appendChild(option);
      });
      fieldLabel.appendChild(fieldSelect);
      body.appendChild(fieldLabel);

      const valueWrap = document.createElement("div");
      body.appendChild(valueWrap);

      let valueControl = null;
      function renderValueControl() {
        const field = fields.find(function (f) { return f.name === fieldSelect.value; }) || fields[0];
        valueWrap.innerHTML = "";
        const valueLabel = document.createElement("label");
        const valueLabelText = document.createElement("span");
        valueLabelText.className = "field-label-text";
        valueLabelText.textContent = "New value";
        valueLabel.appendChild(valueLabelText);
        valueControl = createInput(field, "", null);
        valueLabel.appendChild(valueControl);
        valueWrap.appendChild(valueLabel);
      }
      fieldSelect.addEventListener("change", renderValueControl);
      renderValueControl();

      const actions = document.createElement("div");
      actions.className = "modal-actions form-actions";
      const applyButton = button("Set field", "primary", async function () {
        const field = fields.find(function (f) { return f.name === fieldSelect.value; }) || fields[0];
        if (!field || !valueControl) { return; }
        const value = bulkValueFromControl(field, valueControl);
        if (!window.confirm("Set " + (field.label || field.name) + " to \"" + value + "\" on " + ids.length + " record(s)?")) {
          return;
        }
        applyButton.disabled = true;
        try {
          const response = await postConceptBatch(concept, "setField", ids, { field: field.name, value: value });
          clearSelection(panel);
          closeModal();
          showBatchResultsModal(concept, "Set field", response);
          notify("Updated " + (response.succeeded || 0) + " of " + ids.length + " selected record(s).", (response.failed || 0) > 0);
          if ((response.succeeded || 0) > 0) {
            await reloadAfterMutation(concept);
          } else {
            render();
          }
        } catch (error) {
          notify(error.message, true);
        } finally {
          applyButton.disabled = false;
        }
      });
      actions.appendChild(button("Cancel", "", closeModal));
      actions.appendChild(applyButton);
      body.appendChild(actions);

      openModal("Set field -- " + concept.displayName, body);
    }


    // Selection is page-scoped (loadPanel clears it on every fetch), so the selected rows are always
    // present in panel.records -- copy/download/open read them from there, in on-screen order, with no
    // extra request.
    function selectedRecords(concept, panel) {
      return panel.records.filter(function (record) { return isRowSelected(panel, record[concept.idField]); });
    }


    function selectAllOnPage(concept, panel) {
      panel.records.forEach(function (record) { toggleRowSelected(panel, record[concept.idField], true); });
      render();
    }


    function openSelected(concept, panel) {
      const records = selectedRecords(concept, panel);
      if (records.length) { openDetailsMany(concept, records); }
    }


    function cellText(value) {
      if (value === null || value === undefined) { return ""; }
      return typeof value === "object" ? JSON.stringify(value) : String(value);
    }


    // Tab-separated, header row first -- what a spreadsheet puts on the clipboard for a selected range, so
    // a paste into Excel / Sheets / a mail lands in cells. Formula-lead cells are neutralised exactly like
    // the CSV export (a pasted "=..." would otherwise execute).
    function buildTsv(columns, rows) {
      function tsvCell(value) {
        const cell = neutralizeCsvFormula(cellText(value));
        return /[\t\r\n"]/.test(cell) ? "\"" + cell.replace(/"/g, "\"\"") + "\"" : cell;
      }
      const lines = [columns.map(function (column) { return tsvCell(column.label); }).join("\t")];
      rows.forEach(function (row) {
        lines.push(columns.map(function (column) { return tsvCell(row[column.name]); }).join("\t"));
      });
      return lines.join("\r\n");
    }


    function selectedExport(concept, panel) {
      const columns = visibleFields(concept, panel).map(function (field) {
        return { name: field.name, label: field.label || field.name };
      });
      return { columns: columns, rows: selectedRecords(concept, panel) };
    }


    // navigator.clipboard only exists in a secure context (https / localhost); a FinalApp reached over
    // plain http on a LAN address needs the textarea + execCommand path.
    async function writeClipboard(value) {
      if (navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
        return;
      }
      const area = document.createElement("textarea");
      area.value = value;
      area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      const ok = document.execCommand("copy");
      document.body.removeChild(area);
      if (!ok) { throw new Error("The browser blocked copying to the clipboard."); }
    }


    async function copySelected(concept, panel) {
      const picked = selectedExport(concept, panel);
      if (!picked.rows.length) { return; }
      try {
        await writeClipboard(buildTsv(picked.columns, picked.rows));
        notify("Copied " + picked.rows.length + " selected row(s) to the clipboard.", false);
      } catch (error) {
        notify(error.message, true);
      }
    }


    // The merge: every selected row, one file.
    function downloadSelected(concept, panel) {
      const picked = selectedExport(concept, panel);
      if (!picked.rows.length) { return; }
      downloadCsv(buildCsv(picked.columns, picked.rows), csvFilenameFor(concept).replace(/\.csv$/, "-selected.csv"));
      setStatus(picked.rows.length + " selected row(s) downloaded.", false);
    }


    // Renders null (nothing appended by the caller) while nothing is selected, so an app generated
    // before this feature and one generated after render byte-identical grids until a user actually
    // checks a row.
    function buildBulkActionsBar(concept, panel) {
      const ids = selectedRecordIds(panel);
      if (!ids.length) { return null; }
      const bar = document.createElement("div");
      bar.className = "bulk-actions-bar";
      const count = document.createElement("span");
      count.className = "bulk-actions-count";
      count.textContent = ids.length + " selected";
      bar.appendChild(count);
      if (ids.length < panel.records.length) {
        bar.appendChild(button("Select all", "", function () { selectAllOnPage(concept, panel); }));
      }
      bar.appendChild(button("Deselect all", "", function () { clearSelection(panel); render(); }));
      // ui.selectionTools: opt-in per concept; an app that never declares it renders exactly the bar it did before.
      if (concept.selectionTools) {
        bar.appendChild(button("Open selected", "", function () { openSelected(concept, panel); }));
        bar.appendChild(button("Copy selected", "", function () { copySelected(concept, panel); }));
        bar.appendChild(button("Download selected", "", function () { downloadSelected(concept, panel); }));
      }
      bar.appendChild(button("Set field…", "", function () { openBulkSetFieldDialog(concept, panel); }));
      bar.appendChild(button("Delete selected", "danger", function () { runBulkDelete(concept, panel); }));
      return bar;
    }
    return { isRowSelected, toggleRowSelected, postConceptBatch, buildBulkActionsBar };
  });
})();
