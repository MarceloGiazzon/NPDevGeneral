/* NPDev business UI -- CSV/XLSX export and CSV import dialog (CARVE-1).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/csv-io.js
 * App:  static/npdev-business-ui/views/csv-io.js  (verbatim copy; NOT Mustache)
 *
 * Moved verbatim out of business-ui-app.mustache. It runs inside a factory registered with
 * NPDevViews.registerModule (registry.js); app.js calls NPDevViews.module("csvIo", host) once,
 * where host carries the app.js helpers/state this code uses, and destructures the returned exports.
 */
(function () {
  "use strict";
  NPDevViews.registerModule("csvIo", function (host) {
    const {
      baseQueryParams, button, fetchJson, fieldViolations, formFields, headerRow, headers, notify, openModal, panelState, reloadAfterMutation, setStatus, text, visibleFields
    } = host;

    // R7.3 (REG-12 LNCH-10 follow-up): Export CSV used to hit a SEPARATE server endpoint
    // (/api/concepts/{concept}/export.csv, ConceptQueryController) whose query parser ignores both
    // `filter` and `where` outright -- so a filtered grid silently exported the whole table. That
    // endpoint isn't ours to change (RuntimeHost is out of scope for this template), so instead this
    // walks the SAME list endpoint (concept.endpointBase) the grid itself already uses, one page at a
    // time, with the SAME filter/sort params loadPanel sends -- "export matches the on-screen rows" by
    // construction, not by keeping two query builders in sync. EXPORT_PAGE_SIZE mirrors the CRUD
    // controller's own MAX_PAGE_SIZE (business-concept-crud-controller.mustache); EXPORT_ROW_CAP is a
    // client-side safety bound so a runaway loop can't hang the tab on an unexpectedly huge result.
    const EXPORT_PAGE_SIZE = 100;

    const EXPORT_ROW_CAP = 50000;


    // INT-9: the page walk shared by Export CSV and Export Excel -> {columns, rows}.
    async function collectExportRows(concept) {
      const panel = panelState(concept);
      const fields = visibleFields(concept, panel);
      const columns = fields.map(function (field) { return { name: field.name, label: field.label || field.name }; });
      const rows = [];
      let page = 0;
      setStatus("Preparing " + concept.displayName + " export…", false);
      while (rows.length < EXPORT_ROW_CAP) {
        const params = baseQueryParams(concept, panel);
        params.set("page", String(page));
        params.set("size", String(EXPORT_PAGE_SIZE));
        const payload = await fetchJson(concept.endpointBase + "?" + params.toString(), {
          method: "GET",
          headers: headers()
        });
        const content = Array.isArray(payload.content) ? payload.content : [];
        rows.push.apply(rows, content);
        if (content.length < EXPORT_PAGE_SIZE) {
          break;
        }
        page += 1;
      }
      return { columns: columns, rows: rows };
    }


    async function exportPanelCsv(concept) {
      try {
        const collected = await collectExportRows(concept);
        downloadCsv(buildCsv(collected.columns, collected.rows), csvFilenameFor(concept));
        setStatus(concept.displayName + " export ready: " + collected.rows.length + " row(s).", false);
      } catch (error) {
        notify(error.message, true);
      }
    }


    // INT-9: same rows as Export CSV, written as a real .xlsx by npdev-xlsx.js (no library; formula-lead
    // cells are neutralised there exactly like csvCell above).
    async function exportPanelXlsx(concept) {
      try {
        if (!window.NpdevXlsx) {
          throw new Error("Excel export is unavailable (npdev-xlsx.js did not load).");
        }
        const collected = await collectExportRows(concept);
        const blob = window.NpdevXlsx.writeXlsx(collected.columns, collected.rows, concept.displayName);
        downloadBlob(blob, csvFilenameFor(concept).replace(/\.csv$/, ".xlsx"));
        setStatus(concept.displayName + " Excel export ready: " + collected.rows.length + " row(s).", false);
      } catch (error) {
        notify(error.message, true);
      }
    }


    function buildCsv(columns, rows) {
      const lines = [columns.map(function (column) { return csvCell(column.label); }).join(",")];
      rows.forEach(function (row) {
        lines.push(columns.map(function (column) { return csvCell(row[column.name]); }).join(","));
      });
      return lines.join("\r\n") + "\r\n";
    }


    // Client-side mirror of CsvCells (NPDevRuntimeHost com.finalexec.api) so a value that started a
    // formula (=, +, -, @, tab, CR) can't execute in a spreadsheet, while a plain negative number like
    // -42 still exports as a number rather than a neutralized string -- same rule, same reasoning, kept
    // in lockstep on purpose because this now IS the export path (the server's own CSV writer is no
    // longer in this UI's request path).
    function csvCell(value) {
      const raw = value === null || value === undefined ? "" : String(value);
      const cell = neutralizeCsvFormula(raw);
      if (cell.indexOf(",") < 0 && cell.indexOf("\"") < 0 && cell.indexOf("\n") < 0 && cell.indexOf("\r") < 0) {
        return cell;
      }
      return "\"" + cell.replace(/"/g, "\"\"") + "\"";
    }


    function neutralizeCsvFormula(value) {
      if (!value) {
        return value;
      }
      const first = value.charAt(0);
      const formulaLead = first === "=" || first === "+" || first === "-" || first === "@"
          || first === "\t" || first === "\r";
      if (!formulaLead || isPlainCsvNumber(value)) {
        return value;
      }
      return "'" + value;
    }


    function isPlainCsvNumber(value) {
      if (value.length < 2) {
        return false;
      }
      let digitSeen = false;
      let dotSeen = false;
      for (let index = 1; index < value.length; index += 1) {
        const ch = value.charAt(index);
        if (ch >= "0" && ch <= "9") {
          digitSeen = true;
        } else if (ch === "." && !dotSeen) {
          dotSeen = true;
        } else {
          return false;
        }
      }
      return digitSeen;
    }


    function csvFilenameFor(concept) {
      const safe = String(concept.conceptName || "export").replace(/[^A-Za-z0-9_-]/g, "_");
      return (safe || "export") + ".csv";
    }


    function downloadCsv(csvText, filename) {
      downloadBlob(new Blob([csvText], { type: "text/csv;charset=utf-8" }), filename);
    }


    function downloadBlob(blob, filename) {
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }


    // R7.6 -- Generic CSV import, generalizing the propose -> review -> commit triad three
    // hand-authored WmsOffice wizards already proved at the AGGREGATE level (DocumentoFiscalAggregate/
    // RomaneioAggregate/InventarioArquivoAggregate: a procedure parses raw input into a draft, the
    // Workbench renders an editable review grid, and commit is one atomic N+1-row write). A plain
    // concept grid has no aggregate/draft/procedure machinery to reuse, so this generalizes the SHAPE
    // of that pattern with different mechanics: "propose" is a client-side CSV parse (below), "review"
    // is a per-row-validated preview table (reusing fieldViolations, the same per-field error parser
    // R7.1/EDIT-5 built for the create/edit form), and "commit" is a sequence of independently atomic
    // single-row POSTs against the SAME concept.endpointBase the New/Edit form already uses -- each
    // accepted row's write is all-or-nothing (it is exactly the one request submitForm already makes),
    // never a giant multi-row transaction, so a mid-file failure can only ever leave the rows already
    // POSTed committed and the remaining rows simply not attempted -- never a half-written ROW. Rows
    // that fail CLIENT-side validation (required/enum) are never sent at all, so "commit only accepted
    // rows" is enforced before a single request goes out, not after a partial server-side failure.
    //
    // Deliberately NOT an aggregate-style multi-row transaction: importing 500 rows is 500 independent
    // creates, so a row 400 rejection does not roll back rows 1-399. That is the DESIGN (matching the
    // roadmap's explicit "commit only ACCEPTED rows" wording, not "all or nothing"), not a limitation
    // discovered late -- H2/MySQL's implicit commit-on-DDL quirk (CLAUDE.md) does not apply here since
    // Import performs no DDL, only the same DML the create endpoint always performed.
    //
    // The `id` column (present in Export CSV, since a concept's id is shown-in-UI by default) is never
    // part of the create payload -- importableFields is formFields(concept, "create"), which already
    // excludes every readOnly field including id, so a re-import always asks the server to mint a fresh
    // id rather than attempting to reuse the exported one (which would collide on a populated grid).

    function importableFields(concept) {
      return formFields(concept, "create").filter(function (field) {
        if (field.type === "array" || field.type === "object" || field.type === "file") {
          return false;
        }
        // A many-to-many bond field has no flat scalar to put in one CSV cell.
        return field.widget !== "multiselect";
      });
    }


    const IMPORT_NUMERIC_TYPES = ["int", "integer", "long", "double", "float", "short", "byte", "number"];


    // Inverse of csvCell/neutralizeCsvFormula: a value this app's OWN Export CSV wrote with a leading
    // "'" guard (because it started with =, +, -, @, tab or CR and was not a plain negative number) has
    // that guard stripped so the round-tripped value matches exactly. A genuine leading apostrophe that
    // was never neutralized (not followed by one of those trigger characters) is left alone.
    function unneutralizeCsvFormula(value) {
      if (value.length >= 2 && value.charAt(0) === "'") {
        const second = value.charAt(1);
        if (second === "=" || second === "+" || second === "-" || second === "@" || second === "\t" || second === "\r") {
          return value.slice(1);
        }
      }
      return value;
    }


    // A small RFC4180-shaped parser: quoted fields may contain commas/newlines/doubled-quote escapes,
    // matching exactly what buildCsv/csvCell (this same file's Export CSV) can produce, so Export's own
    // output always parses back losslessly. \r\n, bare \n and bare \r line endings are all accepted
    // (a pasted file may arrive with any of the three depending on the user's OS/editor).
    function parseCsvText(rawText) {
      const rows = [];
      let row = [];
      let field = "";
      let inQuotes = false;
      let i = 0;
      const len = rawText.length;
      while (i < len) {
        const ch = rawText.charAt(i);
        if (inQuotes) {
          if (ch === "\"") {
            if (rawText.charAt(i + 1) === "\"") { field += "\""; i += 2; continue; }
            inQuotes = false; i += 1; continue;
          }
          field += ch; i += 1; continue;
        }
        if (ch === "\"") { inQuotes = true; i += 1; continue; }
        if (ch === ",") { row.push(field); field = ""; i += 1; continue; }
        if (ch === "\r" || ch === "\n") {
          row.push(field);
          field = "";
          rows.push(row);
          row = [];
          i += (ch === "\r" && rawText.charAt(i + 1) === "\n") ? 2 : 1;
          continue;
        }
        field += ch;
        i += 1;
      }
      if (field.length > 0 || row.length > 0) {
        row.push(field);
        rows.push(row);
      }
      // Drop a wholly-blank trailing line (the common case: the file/paste ends with a newline).
      return rows.filter(function (r) { return !(r.length === 1 && r[0] === ""); });
    }


    function importColumnLabel(field) {
      return text(field.label || field.name);
    }


    function matchImportField(fields, headerCell) {
      const needle = text(headerCell).trim().toLowerCase();
      if (!needle) {
        return null;
      }
      const byLabel = fields.find(function (field) {
        return importColumnLabel(field).trim().toLowerCase() === needle;
      });
      if (byLabel) {
        return byLabel;
      }
      return fields.find(function (field) { return field.name.toLowerCase() === needle; }) || null;
    }


    // Mirrors submitForm's own type coercion (same numeric-type list, same "decimal" exclusion to avoid
    // a lossy float round-trip -- see submitForm's comment) so an imported row is converted exactly the
    // way a hand-typed form value would be.
    function importCellToPayloadValue(field, rawCell) {
      const value = unneutralizeCsvFormula(text(rawCell));
      if (field.type === "boolean") {
        return value.trim().toLowerCase() === "true";
      }
      if (value === "") {
        return "";
      }
      if (IMPORT_NUMERIC_TYPES.indexOf(field.type) !== -1) {
        const n = (field.type === "int" || field.type === "integer" || field.type === "long"
            || field.type === "short" || field.type === "byte")
          ? parseInt(value, 10)
          : parseFloat(value);
        return isNaN(n) ? value : n;
      }
      return value;
    }


    // The client-side half of "per-row-validated preview": required + enum membership, the two checks
    // that need no round trip to the server. Everything else (uniqueness, reference existence, other
    // invariants) is left to the server's own create endpoint and surfaced via fieldViolations at
    // commit time -- exactly the same split submitForm already uses (isFieldRequiredNow pre-submit,
    // fieldViolations on a rejected save).
    function validateImportRow(fields, values) {
      const errors = [];
      fields.forEach(function (field) {
        const raw = text(values[field.name]).trim();
        if (field.required && raw === "") {
          errors.push({ field: field.name, message: (field.label || field.name) + " is required." });
          return;
        }
        if (raw !== "" && field.enumValues && field.enumValues.length && field.enumValues.indexOf(raw) === -1) {
          errors.push({
            field: field.name,
            message: (field.label || field.name) + ": \"" + raw + "\" is not one of " + field.enumValues.join(", ") + "."
          });
        }
      });
      return errors;
    }


    function buildImportPreview(concept, csvText) {
      const fields = importableFields(concept);
      const table = parseCsvText(csvText);
      if (!table.length) {
        return { error: "No rows found -- upload a CSV file or paste CSV text first.", fields: fields, rows: [] };
      }
      const headerRow = table[0];
      const columnFields = headerRow.map(function (cell) { return matchImportField(fields, cell); });
      const missingRequired = fields.filter(function (field) {
        return field.required && columnFields.indexOf(field) === -1;
      });
      if (missingRequired.length) {
        return {
          error: "Missing required column(s): "
              + missingRequired.map(function (field) { return field.label || field.name; }).join(", ") + ".",
          fields: fields,
          rows: []
        };
      }
      const rows = table.slice(1).map(function (cells, index) {
        const values = {};
        columnFields.forEach(function (field, colIndex) {
          if (field) {
            values[field.name] = unneutralizeCsvFormula(text(cells[colIndex]));
          }
        });
        const errors = validateImportRow(fields, values);
        return { index: index, values: values, errors: errors, status: errors.length ? "invalid" : "pending" };
      });
      return { error: null, fields: fields, rows: rows };
    }


    // Commits only rows with zero client-side errors, one atomic single-row POST at a time (never
    // batched), in file order. onRowUpdate is called after every row so the preview table can render
    // progress live rather than freezing until the whole file finishes.
    async function commitImportRows(concept, preview, onRowUpdate) {
      let created = 0;
      let skipped = 0;
      let rejected = 0;
      for (const row of preview.rows) {
        if (row.errors.length) {
          row.status = "skipped";
          skipped += 1;
          onRowUpdate(row);
          continue;
        }
        const payload = {};
        preview.fields.forEach(function (field) {
          if (row.values[field.name] === undefined) {
            return;
          }
          const converted = importCellToPayloadValue(field, row.values[field.name]);
          if (converted !== "" || field.required) {
            payload[field.name] = converted;
          }
        });
        try {
          await fetchJson(concept.endpointBase, { method: "POST", headers: headers(), body: JSON.stringify(payload) });
          row.status = "created";
          created += 1;
        } catch (error) {
          row.status = "rejected";
          row.serverErrors = fieldViolations(error.payload, error.message);
          row.serverMessage = error.message;
          rejected += 1;
        }
        onRowUpdate(row);
      }
      return { created: created, skipped: skipped, rejected: rejected };
    }


    function importRowStatusLabel(status) {
      switch (status) {
        case "invalid": return "Error";
        case "pending": return "Ready";
        case "skipped": return "Skipped";
        case "created": return "Created";
        case "rejected": return "Rejected";
        default: return status;
      }
    }


    // The dialog itself: file-picker OR paste both feed the SAME textarea (one code path parses
    // whichever text ends up there), matching "upload via the existing files endpoint or paste" --
    // the picker reads the local file with FileReader entirely client-side (consistent with the rest
    // of this template's principle of not adding a new server endpoint for a feature that does not
    // need one; the file's bytes never have to leave the browser before the user has even previewed
    // them). Uses the SAME openModal/closeModal (-> openModalInto/closeModalIn) every other dialog in
    // this template uses, so Escape-to-close, the Tab focus trap and restore-focus-on-close (R7.9/
    // EDIT-10) apply here for free -- nothing about dialog behavior is reimplemented.
    function openImportDialog(concept) {
      const fields = importableFields(concept);
      const body = document.createElement("div");
      body.className = "import-modal-body";

      const intro = document.createElement("p");
      intro.className = "import-intro";
      const requiredLabels = fields.filter(function (field) { return field.required; })
          .map(function (field) { return field.label || field.name; });
      intro.textContent = "Upload CSV or Excel (.xlsx, first sheet), or paste CSV, whose header row matches "
          + concept.displayName + "'s Export CSV / Export Excel." + (requiredLabels.length ? " Required columns: " + requiredLabels.join(", ") + "." : "");
      body.appendChild(intro);

      const fileLabel = document.createElement("label");
      fileLabel.className = "import-file-label";
      const fileLabelText = document.createElement("span");
      fileLabelText.textContent = "CSV or Excel file";
      fileLabel.appendChild(fileLabelText);
      const fileInput = document.createElement("input");
      fileInput.type = "file";
      fileInput.accept = ".csv,text/csv,.xlsx";
      fileLabel.appendChild(fileInput);
      body.appendChild(fileLabel);

      const pasteLabel = document.createElement("label");
      pasteLabel.className = "import-paste-label";
      const pasteLabelText = document.createElement("span");
      pasteLabelText.textContent = "…or paste CSV text";
      pasteLabel.appendChild(pasteLabelText);
      const textarea = document.createElement("textarea");
      textarea.className = "import-paste-area";
      textarea.rows = 8;
      textarea.placeholder = fields.map(importColumnLabel).join(",");
      pasteLabel.appendChild(textarea);
      body.appendChild(pasteLabel);

      fileInput.addEventListener("change", function () {
        const chosen = fileInput.files && fileInput.files[0];
        if (!chosen) {
          return;
        }
        if (/\.xlsx$/i.test(chosen.name)) {
          // INT-9: the first worksheet becomes CSV text in the same textarea, so validation, mapping and
          // upload stay the single existing pipeline. Dates arrive as Excel serial numbers: format date
          // columns as Text in Excel (or use CSV).
          if (!window.NpdevXlsx) {
            summary.textContent = "Excel import is unavailable (npdev-xlsx.js did not load).";
            return;
          }
          window.NpdevXlsx.readFirstSheet(chosen).then(function (sheetRows) {
            textarea.value = sheetRows.map(function (row) {
              return row.map(function (cell) { return "\"" + String(cell).replace(/"/g, "\"\"") + "\""; }).join(",");
            }).join("\n");
            summary.textContent = "";
          }).catch(function (error) {
            textarea.value = "";
            summary.textContent = "Could not read " + chosen.name + ": " + (error && error.message ? error.message : error);
          });
          return;
        }
        const reader = new FileReader();
        reader.onload = function () { textarea.value = String(reader.result || ""); };
        reader.readAsText(chosen);
      });

      const previewWrap = document.createElement("div");
      previewWrap.className = "import-preview-wrap";
      body.appendChild(previewWrap);

      const summary = document.createElement("div");
      summary.className = "import-summary";
      body.appendChild(summary);

      let preview = null;

      const actions = document.createElement("div");
      actions.className = "modal-actions form-actions";
      const commitButton = button("Import accepted rows", "primary", async function () {
        if (!preview || preview.error || !preview.rows.length) {
          return;
        }
        commitButton.disabled = true;
        const result = await commitImportRows(concept, preview, renderImportPreview);
        commitButton.disabled = false;
        summary.textContent = "Import complete: " + result.created + " created, " + result.skipped
            + " skipped (failed validation, never sent), " + result.rejected + " rejected by the server.";
        notify(summary.textContent, result.rejected > 0);
        if (result.created > 0) {
          await reloadAfterMutation(concept);
        }
      });
      commitButton.disabled = true;
      const previewButton = button("Preview", "", function () {
        preview = buildImportPreview(concept, textarea.value);
        renderImportPreview();
      });
      actions.appendChild(previewButton);
      actions.appendChild(commitButton);
      // No separate Close button here -- openModalInto's own header Close button (plus Escape) already
      // closes every dialog in this app; a second one would just duplicate it.
      body.appendChild(actions);

      function renderImportPreview() {
        previewWrap.innerHTML = "";
        if (!preview) {
          return;
        }
        if (preview.error) {
          const err = document.createElement("div");
          err.className = "import-error";
          err.textContent = preview.error;
          previewWrap.appendChild(err);
          commitButton.disabled = true;
          return;
        }
        const acceptedCount = preview.rows.filter(function (row) { return row.status !== "invalid"; }).length;
        summary.textContent = preview.rows.length + " row(s) parsed -- " + acceptedCount + " will be committed, "
            + (preview.rows.length - acceptedCount) + " have validation errors and will be skipped.";
        commitButton.disabled = acceptedCount === 0;
        const table = document.createElement("table");
        table.className = "import-preview-table";
        const thead = document.createElement("thead");
        const headRow = document.createElement("tr");
        const rowTh = document.createElement("th");
        rowTh.textContent = "Row";
        headRow.appendChild(rowTh);
        preview.fields.forEach(function (field) {
          const th = document.createElement("th");
          th.textContent = field.label || field.name;
          headRow.appendChild(th);
        });
        thead.appendChild(headRow);
        table.appendChild(thead);
        const tbody = document.createElement("tbody");
        preview.rows.forEach(function (row) {
          const tr = document.createElement("tr");
          tr.className = "import-row-" + row.status;
          tr.dataset.importRow = String(row.index);
          tr.dataset.importStatus = row.status;
          const rowTd = document.createElement("td");
          rowTd.textContent = (row.index + 1) + " (" + importRowStatusLabel(row.status) + ")";
          tr.appendChild(rowTd);
          preview.fields.forEach(function (field) {
            const td = document.createElement("td");
            td.textContent = text(row.values[field.name]);
            tr.appendChild(td);
          });
          tbody.appendChild(tr);
          const noteMessages = row.errors.length
              ? row.errors.map(function (e) { return e.message; })
              : (row.serverErrors && row.serverErrors.length
                  ? row.serverErrors.map(function (e) { return e.message; })
                  : (row.serverMessage ? [row.serverMessage] : []));
          if (noteMessages.length) {
            const noteTr = document.createElement("tr");
            noteTr.className = "import-row-note";
            const noteTd = document.createElement("td");
            noteTd.colSpan = preview.fields.length + 1;
            noteTd.textContent = noteMessages.filter(Boolean).join(" ");
            noteTr.appendChild(noteTd);
            tbody.appendChild(noteTr);
          }
        });
        table.appendChild(tbody);
        previewWrap.appendChild(table);
      }

      openModal("Import " + concept.displayName, body);
    }
    return { exportPanelCsv, exportPanelXlsx, importCellToPayloadValue, openImportDialog, buildCsv, csvFilenameFor, downloadCsv, neutralizeCsvFormula };
  });
})();
