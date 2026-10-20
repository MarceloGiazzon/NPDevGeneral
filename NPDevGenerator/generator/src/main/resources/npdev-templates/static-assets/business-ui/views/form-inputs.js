/* NPDev business UI -- form input controls and picker dialog (CARVE-1).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/form-inputs.js
 * App:  static/npdev-business-ui/views/form-inputs.js  (verbatim copy; NOT Mustache)
 *
 * Moved verbatim out of business-ui-app.mustache. It runs inside a factory registered with
 * NPDevViews.registerModule (registry.js); app.js calls NPDevViews.module("formInputs", host) once,
 * where host carries the app.js helpers/state this code uses, and destructures the returned exports.
 */
(function () {
  "use strict";
  NPDevViews.registerModule("formInputs", function (host) {
    const {
      button, closeModalIn, createCustomWidgetInput, el, fetchJson, fileHandleUrl, formatValue, headers, humanize, isImageField, isImageFileValue, loadReferenceLabel, lookupLabel, openModalInto, referenceCacheKey, setStatus, state, text, whereExpression
    } = host;

    function createInput(field, value, recordId) {
      if (field.widget === "custom" && field.customWidgetRef) {
        return createCustomWidgetInput(field, value);
      }
      if (field.type === "file") {
        return createFileInput(field, value);
      }
      if (field.type === "array" && field.widget === "multiselect" && field.itemsSchema && field.itemsSchema.type === "enum") {
        return createArrayEnumMultiselectInput(field, Array.isArray(value) ? value : []);
      }
      if (field.type === "array" && field.itemsSchema) {
        return createArrayTableInput(field, Array.isArray(value) ? value : []);
      }
      if (field.type === "object" && field.objectSchema) {
        return createObjectInput(field, value && typeof value === "object" ? value : {});
      }
      if (field.widget === "multiselect" && field.reference) {
        return createMultiSelectInput(field, recordId);
      }
      if (field.widget === "select" && field.reference) {
        return createReferenceSelectInput(field, value);
      }
      if (field.widget === "autocomplete" && field.reference) {
        return createAutocompleteInput(field, value);
      }
      if (field.widget === "lookup" && field.reference) {
        return createLookupInput(field, value);
      }
      if (field.widget === "image-select" && (field.reference || (field.enumValues && field.enumValues.length))) {
        return createImageSelectInput(field, value);
      }
      if (field.enumValues && field.enumValues.length) {
        if (field.widget === "autocomplete") {
          return createEnumAutocompleteInput(field, value);
        }
        return createEnumSelect(field, value);
      }
      if (field.widget === "rating") {
        return createRatingInput(field, value);
      }
      if (field.widget === "richtext") {
        return createRichTextInput(field, value);
      }
      if (field.widget === "uuid") {
        return createUuidInput(field, value);
      }
      if (field.type === "boolean") {
        const input = document.createElement("input");
        input.name = field.name;
        input.type = "checkbox";
        input.checked = Boolean(value);
        if (field.widget === "toggle") {
          input.className = "toggle-switch-input";
        }
        return input;
      }
      if (field.widget === "textarea") {
        const textarea = document.createElement("textarea");
        textarea.name = field.name;
        textarea.rows = 3;
        textarea.value = value === null || value === undefined ? "" : String(value);
        return textarea;
      }
      if (field.widget === "slider") {
        return createSliderInput(field, value);
      }
      const input = document.createElement("input");
      input.name = field.name;
      // field.widget === "currency" MUST be checked before the generic number/decimal branch below --
      // a currency field's field.type is "decimal" (or "number"/"integer"), which would otherwise match
      // that earlier, more general branch first in this else-if chain and leave step unset, defaulting
      // the browser's native number-input validation to step=1 and rejecting any 2-decimal amount.
      if (field.widget === "currency") {
        input.type = "number";
        input.step = "0.01";
      } else if (field.widget === "datetime-local" || field.type === "datetime") {
        input.type = "datetime-local";
      } else if (field.widget === "date" || field.type === "date") {
        input.type = "date";
      } else if (field.widget === "number" || field.type === "number" || field.type === "integer" || field.type === "decimal") {
        input.type = "number";
      } else if (field.widget === "color") {
        input.type = "color";
      } else if (field.widget === "email") {
        input.type = "email";
      } else if (field.widget === "tel") {
        input.type = "tel";
      } else if (field.widget === "url") {
        input.type = "url";
      } else if (field.widget === "password") {
        input.type = "password";
      } else {
        input.type = "text";
      }
      input.value = value === null || value === undefined ? "" : String(value);
      if (field.widget === "password") {
        return wrapPasswordInput(input);
      }
      if (field.widget === "currency") {
        return wrapCurrencyInput(input, field);
      }
      return input;
    }



    // Top-level entry points (called from createInput()'s field dispatch): build the editor and
    // return just the element -- the value round-trips through the hidden input's real form
    // `name` via the existing currentFormValues()/submitForm() JSON.parse path, same as before.
    function createArrayTableInput(field, rows) {
      return createNestedArrayEditor(field.itemsSchema, field.name, rows, null).element;
    }


    function createObjectInput(field, value) {
      return createNestedObjectEditor(field.objectSchema, field.name, value, null).element;
    }


    // Recursive editors for object/array fields, depth-unlimited (mirrors the generator's
    // recursive objectSchema/itemsSchema emission). `schema` is a {type, properties, items?} node
    // -- either a top-level field's own objectSchema/itemsSchema, or a nested property's. `name` is
    // only set at the top level (it becomes the real <input name> submitForm reads); nested calls
    // pass null and instead expose `getValue()`, which the PARENT editor calls during its own
    // syncHidden() so a value of any nesting depth round-trips correctly.
    //
    // `onChange` is how a change at ANY depth reaches the form's top-level hidden input: each level
    // only listens for input events on its OWN direct leaf inputs, so without propagation, editing a
    // deeply-nested leaf (e.g. level 3) would refresh level 3's own hidden snapshot but never the
    // level-1/level-2 ancestors' -- and submitForm only ever reads the level-1 (top-level) hidden
    // input.
    //
    // `getValue` (exposed to the parent, used while the parent ASSEMBLES its own value) and
    // `notifyChange` (used as the child's `onChange`, fired on a leaf edit) MUST be two different
    // functions even though both refresh this level's own hidden snapshot: if the parent's assembly
    // pass called the SAME function that also re-invokes the parent (via onChange), every leaf edit
    // would recurse parent->child->parent->child... infinitely (child.notifyChange calls
    // onChange===parent.notifyChange, which calls child.getValue() to assemble itself -- if getValue
    // were notifyChange, that nested call would re-trigger onChange again, forever). `getValue` is
    // the pure, side-effect-only-on-self computation; `notifyChange` adds "and tell whoever owns me".
    function createNestedObjectEditor(schema, name, value, onChange) {
      const propsSchema = (schema && schema.properties) || {};
      const propNames = Object.keys(propsSchema);
      const wrapper = document.createElement("div");
      wrapper.className = "object-group-editor";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      if (name) { hidden.name = name; }

      const nestedGetters = {};

      function getValue() {
        const out = {};
        propNames.forEach(function (prop) {
          if (nestedGetters[prop]) {
            out[prop] = nestedGetters[prop]();
            return;
          }
          const inp = wrapper.querySelector("[data-prop=\"" + prop + "\"]");
          out[prop] = inp ? text(inp.value).trim() : "";
        });
        hidden.value = JSON.stringify(out);
        return out;
      }

      function notifyChange() {
        getValue();
        if (onChange) { onChange(); }
      }

      const grid = document.createElement("div");
      grid.className = "object-group-grid";
      propNames.forEach(function (prop) {
        const propSchema = propsSchema[prop] || {};
        const existing = value ? value[prop] : null;
        const propLabel = document.createElement("label");
        propLabel.className = "object-group-field";
        const labelText = document.createElement("span");
        labelText.className = "field-label-text";
        labelText.textContent = humanize(prop);
        propLabel.appendChild(labelText);

        if (propSchema.objectSchema) {
          const nested = createNestedObjectEditor(propSchema.objectSchema, null, existing && typeof existing === "object" ? existing : {}, notifyChange);
          nestedGetters[prop] = nested.getValue;
          propLabel.appendChild(nested.element);
        } else if (propSchema.itemsSchema) {
          const nested = createNestedArrayEditor(propSchema.itemsSchema, null, Array.isArray(existing) ? existing : [], notifyChange);
          nestedGetters[prop] = nested.getValue;
          propLabel.appendChild(nested.element);
        } else {
          const inp = document.createElement("input");
          inp.type = "text";
          inp.dataset.prop = prop;
          inp.value = existing === null || existing === undefined ? "" : String(existing);
          inp.addEventListener("input", notifyChange);
          propLabel.appendChild(inp);
        }
        grid.appendChild(propLabel);
      });

      wrapper.appendChild(grid);
      wrapper.appendChild(hidden);
      getValue();
      return { element: wrapper, getValue: getValue };
    }


    function createNestedArrayEditor(schema, name, rows, onChange) {
      const propsSchema = (schema && schema.properties) || {};
      const propNames = Object.keys(propsSchema);
      const wrapper = document.createElement("div");
      wrapper.className = "array-table-editor";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      if (name) { hidden.name = name; }

      const rowGetters = [];

      function getValue() {
        const out = rowGetters.map(function (getRow) { return getRow(); });
        hidden.value = JSON.stringify(out);
        return out;
      }

      function notifyChange() {
        getValue();
        if (onChange) { onChange(); }
      }

      const table = document.createElement("table");
      table.className = "array-editor-table";
      const thead = document.createElement("thead");
      const headRow = document.createElement("tr");
      propNames.forEach(function (prop) {
        const th = document.createElement("th");
        th.textContent = humanize(prop);
        headRow.appendChild(th);
      });
      headRow.appendChild(document.createElement("th"));
      thead.appendChild(headRow);
      table.appendChild(thead);

      const tbody = document.createElement("tbody");

      function addRow(rowData) {
        const tr = document.createElement("tr");
        tr.className = "array-row";
        const cellGetters = {};
        propNames.forEach(function (prop) {
          const td = document.createElement("td");
          const propSchema = propsSchema[prop] || {};
          const existing = rowData ? rowData[prop] : null;
          if (propSchema.objectSchema) {
            const nested = createNestedObjectEditor(propSchema.objectSchema, null, existing && typeof existing === "object" ? existing : {}, notifyChange);
            cellGetters[prop] = nested.getValue;
            td.appendChild(nested.element);
          } else if (propSchema.itemsSchema) {
            const nested = createNestedArrayEditor(propSchema.itemsSchema, null, Array.isArray(existing) ? existing : [], notifyChange);
            cellGetters[prop] = nested.getValue;
            td.appendChild(nested.element);
          } else {
            const inp = document.createElement("input");
            inp.type = "text";
            inp.dataset.prop = prop;
            inp.value = existing !== null && existing !== undefined ? String(existing) : "";
            inp.addEventListener("input", notifyChange);
            td.appendChild(inp);
            cellGetters[prop] = function () { return text(inp.value).trim(); };
          }
          tr.appendChild(td);
        });
        const tdDel = document.createElement("td");
        tdDel.appendChild(button("×", "danger", function () {
          tr.remove();
          const idx = rowGetters.indexOf(rowGetter);
          if (idx >= 0) { rowGetters.splice(idx, 1); }
          notifyChange();
        }));
        tr.appendChild(tdDel);
        tbody.appendChild(tr);

        const rowGetter = function () {
          const item = {};
          propNames.forEach(function (prop) { item[prop] = cellGetters[prop] ? cellGetters[prop]() : ""; });
          return item;
        };
        rowGetters.push(rowGetter);
        notifyChange();
      }

      (Array.isArray(rows) ? rows : []).forEach(addRow);
      table.appendChild(tbody);

      const addBtn = button("+ Add row", "", function () { addRow(null); });
      wrapper.appendChild(table);
      wrapper.appendChild(addBtn);
      wrapper.appendChild(hidden);
      getValue();
      return { element: wrapper, getValue: getValue };
    }


    function createEnumSelect(field, value) {
      const select = document.createElement("select");
      select.name = field.name;
      select.appendChild(new Option("", ""));

      const options = Array.isArray(field.enumOptions) && field.enumOptions.length
        ? field.enumOptions
        : field.enumValues.map(function (option) {
            return {
              value: option,
              label: option,
              deprecated: false,
              default: false,
              group: "",
              description: ""
            };
          });

      options.forEach(function (option) {
        if (!option || option.value === null || option.value === undefined || option.value === "") {
          return;
        }
        const label = option.label || option.value;
        const element = new Option(label, option.value);
        if (option.deprecated) {
          element.disabled = true;
          element.textContent = label + " (deprecated)";
        }
        if (option.group) {
          element.dataset.group = option.group;
        }
        if (option.description) {
          element.title = option.description;
        }
        select.appendChild(element);
      });

      const current = text(value);
      const defaultValue = text(field.defaultEnumValue);
      select.value = current || defaultValue || "";
      return select;
    }


    function createLookupInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "lookup-control";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = text(value);
      wrapper.appendChild(hidden);

      const display = document.createElement("input");
      display.type = "text";
      display.readOnly = true;
      display.className = "lookup-display";
      display.placeholder = "— select " + humanize(field.reference.targetConcept || "record") + " —";
      wrapper.appendChild(display);

      if (!field.required) {
        const clearBtn = button("×", "lookup-clear", function () {
          hidden.value = "";
          display.value = "";
        });
        clearBtn.title = "Clear selection";
        wrapper.appendChild(clearBtn);
      }

      const browseBtn = button("Browse…", "lookup-browse", function () {
        openPickerDialog(field, function (id, label) {
          hidden.value = id;
          display.value = label;
        }, browseBtn);
      });
      wrapper.appendChild(browseBtn);

      const rawId = text(value).trim();
      if (rawId) {
        const cached = state.referenceLabels[referenceCacheKey(field.reference, rawId)];
        if (cached && cached.label) {
          display.value = cached.label;
        } else {
          display.value = rawId;
          loadReferenceLabel(field, rawId).then(function (label) {
            if (label) { display.value = label; }
          });
        }
      }

      return wrapper;
    }


    // LIFT-UPLOAD-P4: a `file`-typed field. Mirrors createLookupInput's hidden-input-plus-display
    // skeleton -- the "true" submitted value is a FileHandle JSON blob (not user-typed), held in a
    // hidden input, with a status label + optional download link rendered around it. Picking a new
    // file immediately uploads it (multipart, outside the record's own JSON save request) and only
    // updates the hidden input on success; the record's own create/update POST still carries just
    // the returned handle, never raw bytes.
    // Wave 2 (NPDEV_FEATURE_PLAN_2026-09-24.md): field.widget="slider" for an int/integer/long/decimal
    // field -- a native <input type=range> IS a real named form control with its own .value, so no
    // hidden-input wrapper is needed (unlike rating/richtext below, which have no native HTML element).
    // Range bounds come from field.min/field.max (Wave 2 threaded these onto the manifest for the
    // first time -- see BusinessUiEmitter); absent either, the plan's documented default is 0-100.
    function createSliderInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "slider-control";
      const input = document.createElement("input");
      input.type = "range";
      input.name = field.name;
      const min = (field.min !== undefined && field.min !== null) ? field.min : 0;
      const max = (field.max !== undefined && field.max !== null) ? field.max : 100;
      input.min = String(min);
      input.max = String(max);
      input.step = (field.type === "decimal") ? "any" : "1";
      input.value = (value === null || value === undefined || value === "") ? String(min) : String(value);
      const label = document.createElement("span");
      label.className = "slider-control-value";
      label.textContent = input.value;
      input.addEventListener("input", function () {
        label.textContent = input.value;
      });
      wrapper.appendChild(input);
      wrapper.appendChild(label);
      return wrapper;
    }


    // field.widget="rating" for an int/integer field: no native star-rating element exists, so (like
    // createFileInput below) a hidden input carries the real named value and a row of clickable star
    // buttons drives it. Star count is field.max if declared and in [1,10], else 5 (the plan's default).
    function createRatingInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "rating-control";
      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = (value === null || value === undefined) ? "" : String(value);
      wrapper.appendChild(hidden);
      const maxStars = (field.max !== undefined && field.max !== null && field.max >= 1 && field.max <= 10)
        ? Math.round(field.max) : 5;
      const stars = [];
      function refresh() {
        const current = parseInt(hidden.value, 10) || 0;
        stars.forEach(function (star, i) {
          const filled = i < current;
          star.textContent = filled ? "★" : "☆";
          star.classList.toggle("rating-star-filled", filled);
        });
      }
      for (let i = 0; i < maxStars; i++) {
        const star = document.createElement("button");
        star.type = "button";
        star.className = "rating-star";
        star.setAttribute("aria-label", "Rate " + (i + 1) + " of " + maxStars);
        star.addEventListener("click", function () {
          // Clicking the currently-set star clears the rating back to unset, same as most rating
          // widgets -- otherwise a required field could never be un-rated once clicked.
          hidden.value = (parseInt(hidden.value, 10) === i + 1) ? "" : String(i + 1);
          refresh();
        });
        stars.push(star);
        wrapper.appendChild(star);
      }
      refresh();
      return wrapper;
    }


    // field.widget="richtext" for a string field: a minimal contenteditable with bold/italic/list,
    // sanitized on every change AND once more on blur (a paste can carry markup the input listener
    // hasn't run for yet) so nothing but the whitelist below ever reaches the hidden input that
    // actually gets submitted -- see sanitizeRichText's own docstring for the whitelist and why it
    // parses real DOM instead of a regex.
    function createRichTextInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "richtext-control";
      const toolbar = document.createElement("div");
      toolbar.className = "richtext-control-toolbar";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;

      const editor = document.createElement("div");
      editor.className = "richtext-control-editor";
      editor.contentEditable = "true";
      const initialHtml = sanitizeRichText(value === null || value === undefined ? "" : String(value));
      editor.innerHTML = initialHtml;
      hidden.value = initialHtml;

      function makeToolButton(label, command, title) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "richtext-control-tool";
        btn.textContent = label;
        btn.title = title;
        // Without this, clicking the button steals focus from the editor FIRST, so execCommand runs
        // with no selection inside it.
        btn.addEventListener("mousedown", function (e) { e.preventDefault(); });
        btn.addEventListener("click", function () {
          document.execCommand(command, false, null);
          syncHidden();
        });
        return btn;
      }
      toolbar.appendChild(makeToolButton("B", "bold", "Bold"));
      toolbar.appendChild(makeToolButton("I", "italic", "Italic"));
      toolbar.appendChild(makeToolButton("• List", "insertUnorderedList", "Bulleted list"));

      function syncHidden() {
        hidden.value = sanitizeRichText(editor.innerHTML);
      }
      editor.addEventListener("input", syncHidden);
      editor.addEventListener("blur", function () {
        const cleaned = sanitizeRichText(editor.innerHTML);
        if (cleaned !== editor.innerHTML) {
          editor.innerHTML = cleaned;
        }
        hidden.value = cleaned;
      });

      wrapper.appendChild(toolbar);
      wrapper.appendChild(editor);
      wrapper.appendChild(hidden);
      return wrapper;
    }


    // field.widget="uuid": read-only monospace display + copy-to-clipboard, and (Wave 2) the DEFAULT
    // widget for a uuid-typed field. Still a real named input (readOnly, not disabled, so its .value
    // keeps round-tripping through submitForm's generic form.elements[field.name] read) -- only the
    // copy button is extra chrome around it.
    function createUuidInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "uuid-control";
      const input = document.createElement("input");
      input.type = "text";
      input.name = field.name;
      input.value = value === null || value === undefined ? "" : String(value);
      input.readOnly = true;
      input.className = "uuid-control-input";
      wrapper.appendChild(input);
      const copyButton = document.createElement("button");
      copyButton.type = "button";
      copyButton.className = "uuid-control-copy";
      copyButton.textContent = "Copy";
      copyButton.addEventListener("click", function () {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(input.value);
          const original = copyButton.textContent;
          copyButton.textContent = "Copied";
          setTimeout(function () { copyButton.textContent = original; }, 1200);
        }
      });
      wrapper.appendChild(copyButton);
      return wrapper;
    }


    // field.widget="password": the <input type=password> itself is built by createInput's generic
    // dispatch (same value path as any other text-like field) -- this just adds a show/hide toggle
    // around it. Wrapping is safe: form.elements[field.name] finds a named control anywhere in the
    // form regardless of nesting, the same reason createFileInput/createRatingInput's wrappers work.
    function wrapPasswordInput(input) {
      const wrapper = document.createElement("div");
      wrapper.className = "password-control";
      input.className = "password-control-input";
      wrapper.appendChild(input);
      const toggle = document.createElement("button");
      toggle.type = "button";
      toggle.className = "password-control-toggle";
      toggle.textContent = "Show";
      toggle.addEventListener("click", function () {
        const showing = input.type === "text";
        input.type = showing ? "password" : "text";
        toggle.textContent = showing ? "Show" : "Hide";
      });
      wrapper.appendChild(toggle);
      return wrapper;
    }


    // field.widget="currency": a decimal field rendered as a plain number input (decimal stays a raw
    // string through submitForm -- see its own numericTypes comment -- so no special submit handling
    // is needed here) with a currency-symbol affix read from field.currency (BusinessUiEmitter emits
    // it from the field's declared ui.currency, Wave 2).
    function wrapCurrencyInput(input, field) {
      const wrapper = document.createElement("div");
      wrapper.className = "currency-control";
      const symbol = document.createElement("span");
      symbol.className = "currency-control-symbol";
      symbol.textContent = field.currency || "";
      input.className = "currency-control-input";
      wrapper.appendChild(symbol);
      wrapper.appendChild(input);
      return wrapper;
    }


    // Whitelist-based HTML sanitizer for field.widget="richtext" -- parses real DOM (a fresh, never-
    // inserted <div>) and walks it rather than using a regex, because a regex cannot correctly reason
    // about nested/malformed markup and is the classic way an HTML sanitizer turns out to be bypassable.
    // Keeps only RICHTEXT_ALLOWED_TAGS, unwrapping (not deleting) any other element so plain pasted
    // text/structure survives; strips EVERY attribute from a kept element (no href/src/style/onXXX --
    // this widget has no legitimate use for any of them), and drops any non-element/non-text node
    // (comments, processing instructions). Used both on every editor change and when first rendering
    // a stored value, so a value written before this sanitizer existed (or written some other way)
    // gets cleaned on next read, not just on next edit.
    const RICHTEXT_ALLOWED_TAGS = new Set(["B", "STRONG", "I", "EM", "UL", "OL", "LI", "BR", "P"]);

    function sanitizeRichText(html) {
      const container = document.createElement("div");
      container.innerHTML = html || "";
      (function clean(node) {
        Array.from(node.childNodes).forEach(function (child) {
          if (child.nodeType === Node.ELEMENT_NODE) {
            if (!RICHTEXT_ALLOWED_TAGS.has(child.tagName)) {
              while (child.firstChild) {
                node.insertBefore(child.firstChild, child);
              }
              node.removeChild(child);
              return;
            }
            Array.from(child.attributes).forEach(function (attr) {
              child.removeAttribute(attr.name);
            });
            clean(child);
          } else if (child.nodeType !== Node.TEXT_NODE) {
            node.removeChild(child);
          }
        });
      })(container);
      return container.innerHTML;
    }


    function createFileInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "file-control";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = value && typeof value === "object" ? JSON.stringify(value) : "";
      wrapper.appendChild(hidden);

      const status = document.createElement("span");
      status.className = "file-control-status";
      status.textContent = value && value.originalName ? value.originalName : "(no file)";
      wrapper.appendChild(status);

      const downloadLink = document.createElement("a");
      downloadLink.className = "file-control-download";
      downloadLink.target = "_blank";
      downloadLink.rel = "noopener";
      downloadLink.style.display = "none";
      downloadLink.textContent = "Download";
      wrapper.appendChild(downloadLink);

      const preview = document.createElement("img");
      preview.className = "file-image file-control-preview";
      preview.alt = field.label || field.name;
      preview.style.display = "none";
      if (isImageField(field) || isImageFileValue(field, value)) {
        wrapper.insertBefore(preview, wrapper.firstChild);
      }

      function refreshDownloadLink(handle) {
        if (handle && handle.key) {
          downloadLink.href = fileHandleUrl(handle);
          downloadLink.style.display = "";
          if (isImageFileValue(field, handle)) {
            if (!preview.parentNode) { wrapper.insertBefore(preview, wrapper.firstChild); }
            preview.src = downloadLink.href;
            preview.style.display = "";
          }
        } else {
          downloadLink.style.display = "none";
          preview.style.display = "none";
        }
      }
      refreshDownloadLink(value);

      const picker = document.createElement("input");
      picker.type = "file";
      if (field.fileContentTypes && field.fileContentTypes.length) {
        picker.accept = field.fileContentTypes.join(",");
      }
      picker.onchange = async function () {
        const chosen = picker.files && picker.files[0];
        if (!chosen) { return; }
        status.textContent = "Uploading…";
        try {
          const formData = new FormData();
          formData.append("file", chosen);
          const uploadHeaders = headers();
          delete uploadHeaders["Content-Type"]; // let the browser set the multipart boundary
          const response = await fetch(
            "/api/files/" + encodeURIComponent(field.concept || "") + "/" + encodeURIComponent(field.name),
            { method: "POST", headers: uploadHeaders, body: formData }
          );
          if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || ("Upload failed: " + response.status));
          }
          const savedHandle = await response.json();
          hidden.value = JSON.stringify(savedHandle);
          status.textContent = savedHandle.originalName || chosen.name;
          refreshDownloadLink(savedHandle);
        } catch (error) {
          status.textContent = "Upload failed";
          setStatus(error.message, true);
        }
      };
      wrapper.appendChild(picker);

      return wrapper;
    }


    // field.widget="select" alternative to the lookup picker dialog for a single (N:1/1:1)
    // reference field: a plain <select> populated from the same reference.endpointBase rows,
    // labelled via the same lookupLabel() the picker uses. Falls back to an empty placeholder
    // option while rows are loading, and re-applies the current value once they arrive (the
    // initial value may resolve before the fetch completes).
    function createReferenceSelectInput(field, value) {
      const reference = field.reference || {};
      const select = document.createElement("select");
      select.name = field.name;
      const placeholder = document.createElement("option");
      placeholder.value = "";
      placeholder.textContent = "— select " + humanize(reference.targetConcept || "record") + " —";
      select.appendChild(placeholder);
      const currentValue = text(value);
      select.value = "";

      async function loadOptions() {
        if (!reference.endpointBase || !reference.targetIdField) {
          return;
        }
        const params = new URLSearchParams();
        params.set("page", "0");
        params.set("size", "200");
        params.set("sort", reference.displayField || reference.targetIdField);
        params.set("direction", "asc");
        try {
          const payload = await fetchJson(reference.endpointBase + "?" + params.toString(), {
            method: "GET",
            headers: headers()
          });
          const rows = Array.isArray(payload.content) ? payload.content : [];
          rows.forEach(function (row) {
            const isViaRef = reference.via && reference.via !== "" && reference.anchorField && reference.anchorField !== reference.targetIdField;
            const id = isViaRef ? text(row[reference.anchorField]) : text(row[reference.targetIdField]);
            const option = document.createElement("option");
            option.value = id;
            option.textContent = lookupLabel(reference, row);
            select.appendChild(option);
            state.referenceLabels[referenceCacheKey(reference, id)] = { status: "resolved", label: option.textContent, row: row };
          });
          if (currentValue) {
            select.value = currentValue;
          }
        } catch (error) {
          setStatus(error.message, true);
        }
      }

      loadOptions();
      return select;
    }


    // field.widget="autocomplete": a lighter-weight alternative to the picker dialog for a single
    // (N:1/1:1) reference field with a LARGE candidate set, where a plain <select> (field.widget=
    // "select", which fetches up to 200 rows upfront) becomes unwieldy. Same hidden-input-plus-
    // display skeleton as the lookup picker (createLookupInput) -- the real submitted value is the
    // anchor id in a hidden field, never the visible label -- but suggestions filter live via the
    // same reference.endpointBase + "filter" query param the picker dialog's search box uses,
    // without ever opening a modal.
    function createAutocompleteInput(field, value) {
      const reference = field.reference || {};
      const wrapper = document.createElement("div");
      wrapper.className = "autocomplete-control";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = text(value);
      wrapper.appendChild(hidden);

      const search = document.createElement("input");
      search.type = "text";
      search.className = "autocomplete-search";
      search.placeholder = "Search " + humanize(reference.targetConcept || "records") + "…";
      search.autocomplete = "off";
      wrapper.appendChild(search);

      const suggestions = document.createElement("div");
      suggestions.className = "autocomplete-suggestions";
      suggestions.style.display = "none";
      wrapper.appendChild(suggestions);

      if (!field.required) {
        const clearBtn = button("×", "lookup-clear", function () {
          hidden.value = "";
          search.value = "";
        });
        clearBtn.title = "Clear selection";
        wrapper.appendChild(clearBtn);
      }

      function selectRow(row) {
        const isViaRef = reference.via && reference.via !== "" && reference.anchorField && reference.anchorField !== reference.targetIdField;
        const id = isViaRef ? text(row[reference.anchorField]) : text(row[reference.targetIdField]);
        const label = lookupLabel(reference, row);
        state.referenceLabels[referenceCacheKey(reference, id)] = { status: "resolved", label: label, row: row };
        hidden.value = id;
        search.value = label;
        suggestions.style.display = "none";
        suggestions.innerHTML = "";
      }

      async function loadSuggestions(query) {
        if (!reference.endpointBase || !reference.targetIdField) {
          return;
        }
        const params = new URLSearchParams();
        params.set("page", "0");
        params.set("size", "10");
        params.set("sort", reference.displayField || reference.targetIdField);
        params.set("direction", "asc");
        if (query) { params.set("filter", query); }
        try {
          const payload = await fetchJson(reference.endpointBase + "?" + params.toString(), {
            method: "GET",
            headers: headers()
          });
          const rows = Array.isArray(payload.content) ? payload.content : [];
          suggestions.innerHTML = "";
          if (rows.length === 0) {
            suggestions.style.display = "none";
            return;
          }
          rows.forEach(function (row) {
            const item = document.createElement("div");
            item.className = "autocomplete-suggestion";
            item.textContent = lookupLabel(reference, row);
            // mousedown (not click) fires before the search input's blur handler hides the dropdown.
            item.addEventListener("mousedown", function (e) {
              e.preventDefault();
              selectRow(row);
            });
            suggestions.appendChild(item);
          });
          suggestions.style.display = "";
        } catch (error) {
          setStatus(error.message, true);
        }
      }

      let searchTimer;
      search.addEventListener("input", function () {
        hidden.value = "";
        clearTimeout(searchTimer);
        searchTimer = setTimeout(function () { loadSuggestions(search.value.trim()); }, 300);
      });
      search.addEventListener("focus", function () {
        if (!hidden.value) { loadSuggestions(search.value.trim()); }
      });
      search.addEventListener("blur", function () {
        suggestions.style.display = "none";
      });

      const rawId = text(value).trim();
      if (rawId) {
        const cached = state.referenceLabels[referenceCacheKey(reference, rawId)];
        if (cached && cached.label) {
          search.value = cached.label;
        } else {
          search.value = rawId;
          loadReferenceLabel(field, rawId).then(function (label) {
            if (label) { search.value = label; }
          });
        }
      }

      return wrapper;
    }


    // field.widget="multiselect" for a many-to-many bond field: a real checkbox list, not a native
    // <select multiple> (easier to style/test, and avoids ctrl-click discoverability issues). The
    // field has no scalar value on the record itself (it's not an entity property -- it's rows in a
    // junction table reached via reference.bondEndpointBase/bondFieldName), so on edit this loads the
    // CURRENT members from that bond endpoint rather than reading record[field.name]. Selection state
    // round-trips through a hidden input's JSON-array value, same pattern as the nested array/object
    // editors, so submitForm's existing per-field control read still finds something at
    // form.elements[field.name] -- but submitForm itself must skip this field's value from the regular
    // create/update payload (the backend entity doesn't have this property either) and instead PUT the
    // selected anchors to the bond endpoint once the parent record's id is known.
    function createMultiSelectInput(field, recordId) {
      const reference = field.reference || {};
      const wrapper = document.createElement("div");
      wrapper.className = "multiselect-control";
      wrapper.dataset.bondField = "true";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = "[]";
      wrapper.appendChild(hidden);

      const list = document.createElement("div");
      list.className = "multiselect-list";
      wrapper.appendChild(list);

      let selected = [];

      function syncHidden() {
        hidden.value = JSON.stringify(selected);
      }

      async function loadOptionsAndCurrent() {
        window.NPDevShell.renderLoading(list);
        if (!reference.endpointBase || !reference.anchorField) {
          window.NPDevShell.renderError(list, { message: "Reference metadata is incomplete." });
          return;
        }
        const params = new URLSearchParams();
        params.set("page", "0");
        params.set("size", "200");
        params.set("sort", reference.displayField || reference.targetIdField);
        params.set("direction", "asc");
        let rows;
        try {
          const payload = await fetchJson(reference.endpointBase + "?" + params.toString(), {
            method: "GET",
            headers: headers()
          });
          rows = Array.isArray(payload.content) ? payload.content : [];
        } catch (error) {
          window.NPDevShell.renderError(list, { message: text(error.message) });
          return;
        }
        if (recordId && reference.bondEndpointBase && reference.bondFieldName) {
          try {
            const current = await fetchJson(
              reference.bondEndpointBase + "/" + encodeURIComponent(recordId) + "/" + reference.bondFieldName,
              { method: "GET", headers: headers() }
            );
            selected = (Array.isArray(current) ? current : []).map(function (v) { return text(v); });
          } catch (error) {
            selected = [];
          }
        }
        syncHidden();
        list.innerHTML = "";
        if (rows.length === 0) {
          window.NPDevShell.renderEmpty(list, { message: "No candidates available to link." });
          return;
        }
        rows.forEach(function (row) {
          const anchorValue = text(row[reference.anchorField]);
          const item = document.createElement("label");
          item.className = "multiselect-item";
          const checkbox = document.createElement("input");
          checkbox.type = "checkbox";
          checkbox.value = anchorValue;
          checkbox.checked = selected.indexOf(anchorValue) !== -1;
          checkbox.addEventListener("change", function () {
            if (checkbox.checked) {
              if (selected.indexOf(anchorValue) === -1) { selected.push(anchorValue); }
            } else {
              selected = selected.filter(function (v) { return v !== anchorValue; });
            }
            syncHidden();
          });
          item.appendChild(checkbox);
          const labelSpan = document.createElement("span");
          labelSpan.textContent = lookupLabel(reference, row);
          item.appendChild(labelSpan);
          list.appendChild(item);
        });
      }

      loadOptionsAndCurrent();
      return wrapper;
    }


    // field.widget="multiselect" on a plain array field whose itemsSchema is a closed enum (a
    // fixed, declared value set) -- a lighter-weight checkbox list than the generic nested array
    // table editor (createArrayTableInput), for the common "pick some values from a fixed set" case.
    // Unlike the many-to-many bond multiselect (createMultiSelectInput) this IS a real property on
    // the entity: its hidden JSON-array value round-trips through the normal create/update payload,
    // not a separate bond endpoint.
    function createArrayEnumMultiselectInput(field, values) {
      const wrapper = document.createElement("div");
      wrapper.className = "multiselect-control";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;

      let selected = (Array.isArray(values) ? values : []).map(function (v) { return text(v); });
      hidden.value = JSON.stringify(selected);
      wrapper.appendChild(hidden);

      const list = document.createElement("div");
      list.className = "multiselect-list";
      wrapper.appendChild(list);

      const options = (field.itemsSchema && Array.isArray(field.itemsSchema.enumValues)) ? field.itemsSchema.enumValues : [];
      options.forEach(function (option) {
        const optionValue = text(option);
        const item = document.createElement("label");
        item.className = "multiselect-item";
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.value = optionValue;
        checkbox.checked = selected.indexOf(optionValue) !== -1;
        checkbox.addEventListener("change", function () {
          if (checkbox.checked) {
            if (selected.indexOf(optionValue) === -1) { selected.push(optionValue); }
          } else {
            selected = selected.filter(function (v) { return v !== optionValue; });
          }
          hidden.value = JSON.stringify(selected);
        });
        item.appendChild(checkbox);
        const labelSpan = document.createElement("span");
        labelSpan.textContent = humanize(optionValue);
        item.appendChild(labelSpan);
        list.appendChild(item);
      });

      return wrapper;
    }


    // field.widget="autocomplete" on an enum field: a client-side type-to-filter dropdown over the
    // field's own (already fully known) enum option list. Unlike the reference-field autocomplete
    // (createAutocompleteInput) there is no server round-trip -- the whole option set is filtered
    // in the browser as the user types. Reuses the same autocomplete-* CSS classes and suggestion
    // markup as the reference version.
    function createEnumAutocompleteInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "autocomplete-control";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = text(value);
      wrapper.appendChild(hidden);

      const search = document.createElement("input");
      search.type = "text";
      search.className = "autocomplete-search";
      search.placeholder = "Search options…";
      search.autocomplete = "off";
      wrapper.appendChild(search);

      const suggestions = document.createElement("div");
      suggestions.className = "autocomplete-suggestions";
      suggestions.style.display = "none";
      wrapper.appendChild(suggestions);

      const options = Array.isArray(field.enumOptions) && field.enumOptions.length
        ? field.enumOptions
        : (field.enumValues || []).map(function (option) { return { value: option, label: option, deprecated: false }; });

      if (!field.required) {
        const clearBtn = button("×", "lookup-clear", function () {
          hidden.value = "";
          search.value = "";
        });
        clearBtn.title = "Clear selection";
        wrapper.appendChild(clearBtn);
      }

      function selectOption(option) {
        hidden.value = text(option.value);
        search.value = option.label || option.value;
        suggestions.style.display = "none";
        suggestions.innerHTML = "";
      }

      function renderSuggestions(query) {
        const normalized = query.trim().toLowerCase();
        const matches = options.filter(function (option) {
          if (option.deprecated) { return false; }
          const label = String(option.label || option.value).toLowerCase();
          return !normalized || label.indexOf(normalized) !== -1;
        });
        suggestions.innerHTML = "";
        if (matches.length === 0) {
          suggestions.style.display = "none";
          return;
        }
        matches.forEach(function (option) {
          const item = document.createElement("div");
          item.className = "autocomplete-suggestion";
          item.textContent = option.label || option.value;
          // mousedown (not click) fires before the search input's blur handler hides the dropdown.
          item.addEventListener("mousedown", function (e) {
            e.preventDefault();
            selectOption(option);
          });
          suggestions.appendChild(item);
        });
        suggestions.style.display = "";
      }

      search.addEventListener("input", function () {
        hidden.value = "";
        renderSuggestions(search.value);
      });
      search.addEventListener("focus", function () {
        renderSuggestions(search.value);
      });
      search.addEventListener("blur", function () {
        suggestions.style.display = "none";
      });

      const current = text(value);
      if (current) {
        const match = options.find(function (option) { return text(option.value) === current; });
        search.value = match ? (match.label || match.value) : current;
      }

      return wrapper;
    }


    // field.widget="image-select": a grid of image+label option cards, for either an enum field
    // (image per option.iconHint) or a single reference field (image per candidate row's
    // field.reference.imageField). SemanticValidator only warns (never blocks) when there's no
    // actual image source declared, so both branches degrade to a plain labeled card grid.
    function looksLikeImagePath(value) {
      if (!value) { return false; }
      const trimmed = String(value).trim();
      return /^(https?:)?\//.test(trimmed) || /\.(svg|png|jpe?g|gif|webp)$/i.test(trimmed);
    }


    function renderOptionIcon(iconValue) {
      if (looksLikeImagePath(iconValue)) {
        const img = document.createElement("img");
        img.className = "image-select-icon";
        img.src = iconValue;
        img.alt = "";
        return img;
      }
      const span = document.createElement("span");
      span.className = "image-select-icon image-select-icon-glyph";
      span.textContent = iconValue || "";
      span.setAttribute("aria-hidden", "true");
      return span;
    }


    function createImageSelectInput(field, value) {
      return field.reference ? createReferenceImageSelectInput(field, value) : createEnumImageSelectInput(field, value);
    }


    function createEnumImageSelectInput(field, value) {
      const wrapper = document.createElement("div");
      wrapper.className = "image-select-control";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = text(value);
      wrapper.appendChild(hidden);

      const grid = document.createElement("div");
      grid.className = "image-select-grid";
      wrapper.appendChild(grid);

      const options = Array.isArray(field.enumOptions) && field.enumOptions.length
        ? field.enumOptions
        : (field.enumValues || []).map(function (option) { return { value: option, label: option, iconHint: "" }; });

      const current = text(value);

      function selectCard(card, optionValue) {
        Array.prototype.forEach.call(grid.querySelectorAll(".image-select-card"), function (c) {
          c.classList.remove("selected");
        });
        card.classList.add("selected");
        hidden.value = optionValue;
      }

      options.forEach(function (option) {
        if (option.deprecated) { return; }
        const card = document.createElement("button");
        card.type = "button";
        card.className = "image-select-card";
        if (text(option.value) === current) { card.classList.add("selected"); }
        if (option.iconHint) { card.appendChild(renderOptionIcon(option.iconHint)); }
        const label = document.createElement("span");
        label.textContent = option.label || option.value;
        card.appendChild(label);
        card.addEventListener("click", function () { selectCard(card, text(option.value)); });
        grid.appendChild(card);
      });

      return wrapper;
    }


    function createReferenceImageSelectInput(field, value) {
      const reference = field.reference || {};
      const wrapper = document.createElement("div");
      wrapper.className = "image-select-control";

      const hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = field.name;
      hidden.value = text(value);
      wrapper.appendChild(hidden);

      const grid = document.createElement("div");
      grid.className = "image-select-grid";
      window.NPDevShell.renderLoading(grid);
      wrapper.appendChild(grid);

      const current = text(value);

      function selectCard(card, id) {
        Array.prototype.forEach.call(grid.querySelectorAll(".image-select-card"), function (c) {
          c.classList.remove("selected");
        });
        card.classList.add("selected");
        hidden.value = id;
      }

      async function loadOptions() {
        if (!reference.endpointBase || !reference.targetIdField) {
          window.NPDevShell.renderError(grid, { message: "Reference metadata is incomplete." });
          return;
        }
        const params = new URLSearchParams();
        params.set("page", "0");
        params.set("size", "200");
        params.set("sort", reference.displayField || reference.targetIdField);
        params.set("direction", "asc");
        try {
          const payload = await fetchJson(reference.endpointBase + "?" + params.toString(), { method: "GET", headers: headers() });
          const rows = Array.isArray(payload.content) ? payload.content : [];
          grid.innerHTML = "";
          if (rows.length === 0) {
            window.NPDevShell.renderEmpty(grid, { message: "No options available." });
            return;
          }
          rows.forEach(function (row) {
            const isViaRef = reference.via && reference.via !== "" && reference.anchorField && reference.anchorField !== reference.targetIdField;
            const id = isViaRef ? text(row[reference.anchorField]) : text(row[reference.targetIdField]);
            const card = document.createElement("button");
            card.type = "button";
            card.className = "image-select-card";
            if (id === current) { card.classList.add("selected"); }
            const imageValue = reference.imageField ? row[reference.imageField] : null;
            const cardLabel = lookupLabel(reference, row);
            if (imageValue) { card.appendChild(renderOptionIcon(imageValue)); }
            const label = document.createElement("span");
            label.textContent = cardLabel;
            card.appendChild(label);
            card.addEventListener("click", function () { selectCard(card, id); });
            grid.appendChild(card);
            state.referenceLabels[referenceCacheKey(reference, id)] = { status: "resolved", label: cardLabel, row: row };
          });
        } catch (error) {
          window.NPDevShell.renderError(grid, { message: text(error.message) });
        }
      }

      loadOptions();
      return wrapper;
    }


    function openPickerDialog(field, onSelect, contextElement) {
      const reference = field.reference || {};
      const content = document.createElement("div");
      content.className = "picker-dialog";

      const searchRow = document.createElement("div");
      searchRow.className = "picker-search-row";
      const searchInput = document.createElement("input");
      searchInput.type = "search";
      searchInput.className = "picker-search";
      searchInput.placeholder = "Search " + humanize(reference.targetConcept || "records") + "…";
      searchRow.appendChild(searchInput);
      content.appendChild(searchRow);

      const tableWrapper = document.createElement("div");
      tableWrapper.className = "picker-table-wrapper";
      content.appendChild(tableWrapper);

      const columns = Array.isArray(reference.pickerColumns) && reference.pickerColumns.length
        ? reference.pickerColumns
        : (Array.isArray(reference.displayFields) && reference.displayFields.length
            ? reference.displayFields
            : [reference.displayField || reference.targetIdField]);

      async function loadPickerRows(filter) {
        if (!reference.endpointBase || !reference.targetIdField) {
          window.NPDevShell.renderError(tableWrapper, { message: "Reference metadata is incomplete." });
          return;
        }
        window.NPDevShell.renderLoading(tableWrapper);
        const params = new URLSearchParams();
        params.set("page", "0");
        params.set("size", "30");
        // EDIT-18 (REAL_LIFT_PLAN_2026-09-03 package C2): a resolved selectorRef's orderBy, when
        // present -- the endpoint's sort param is single-field, so the first declared field wins.
        const sortField = Array.isArray(reference.orderBy) && reference.orderBy.length
          ? reference.orderBy[0]
          : (reference.displayField || reference.targetIdField);
        params.set("sort", sortField);
        params.set("direction", "asc");
        const normalizedFilter = filter && filter.trim() ? filter.trim() : "";
        if (normalizedFilter) { params.set("filter", normalizedFilter); }
        const where = whereExpression(reference.defaultFilterExpression, contextElement);
        if (where) { params.set("where", where); }
        try {
          const payload = await fetchJson(reference.endpointBase + "?" + params.toString(), {
            method: "GET",
            headers: headers()
          });
          const rows = Array.isArray(payload.content) ? payload.content : [];
          tableWrapper.innerHTML = "";
          if (rows.length === 0) {
            window.NPDevShell.renderEmpty(tableWrapper, {
              filtered: Boolean(normalizedFilter),
              message: normalizedFilter ? "No matches for “" + normalizedFilter + "”." : null
            });
            return;
          }
          const table = document.createElement("table");
          table.className = "records picker-records";
          const thead = document.createElement("thead");
          const headRow = document.createElement("tr");
          columns.forEach(function (col) {
            const th = document.createElement("th");
            th.textContent = humanize(col);
            headRow.appendChild(th);
          });
          thead.appendChild(headRow);
          table.appendChild(thead);
          const tbody = document.createElement("tbody");
          rows.forEach(function (row) {
            const tr = document.createElement("tr");
            tr.className = "picker-row";
            // R7.9: a picker row was only ever a click target -- the focus trap this dialog now sits
            // in (see openModalInto) would otherwise land a Tab-only user on the search box and the
            // Close button with every actual choice unreachable. role="button" follows the same
            // pattern R7.5 used for the SVG dashboard shapes (makeClickableSvg in shell.js.mustache).
            tr.tabIndex = 0;
            tr.setAttribute("role", "button");
            columns.forEach(function (col) {
              const td = document.createElement("td");
              td.textContent = formatValue(row[col]);
              tr.appendChild(td);
            });
            function pickRow() {
              const isViaRef = reference.via && reference.via !== "" && reference.anchorField && reference.anchorField !== reference.targetIdField;
              const id = isViaRef ? text(row[reference.anchorField]) : text(row[reference.targetIdField]);
              const label = lookupLabel(reference, row);
              state.referenceLabels[referenceCacheKey(reference, id)] = { status: "resolved", label: label, row: row };
              onSelect(id, label);
              closeModalIn(el.pickerModalRoot);
            }
            tr.addEventListener("click", pickRow);
            tr.addEventListener("keydown", function (event) {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                pickRow();
              }
            });
            tbody.appendChild(tr);
          });
          table.appendChild(tbody);
          tableWrapper.appendChild(table);
        } catch (error) {
          window.NPDevShell.renderError(tableWrapper, { message: text(error.message) });
          setStatus(error.message, true);
        }
      }

      let searchTimer;
      searchInput.addEventListener("input", function () {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(function () { loadPickerRows(searchInput.value); }, 250);
      });

      loadPickerRows("");
      openModalInto(el.pickerModalRoot, "Select " + humanize(reference.targetConcept || "record"), content);
      setTimeout(function () { searchInput.focus(); }, 50);
    }
    return { createInput, sanitizeRichText };
  });
})();
