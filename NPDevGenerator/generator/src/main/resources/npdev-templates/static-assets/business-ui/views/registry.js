/* NPDev business UI -- view renderer registry (UI-0).
 *
 * Source location (repo):  NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/registry.js
 * Emitted to (app):        src/main/resources/static/npdev-business-ui/views/registry.js
 * Copied VERBATIM by BusinessUiEmitter via TemplateEngine.raw(...) -- NOT a Mustache template, so
 * `{{` is safe here (unlike business-ui-app.mustache, landmine L29).
 *
 * Loaded by business-ui-index.mustache BEFORE app.js:
 *   <script src="./views/registry.js"></script>
 *   <script src="./views/board.js"></script>      (UI-3)
 *   <script src="./views/calendar.js"></script>   (UI-6)
 *   <script src="./app.js"></script>
 *
 * Same shape as window.NpdevCustomWidgets (business-ui-app.mustache:19-23) on purpose: one
 * idempotent registry object, register(name, impl) / get(name).
 *
 * VIEW CONTRACT (keep it this small; app.js builds ctx in viewContext(), see renderPanel-hook.js):
 *   impl.render(ctx) -> HTMLElement            required; pure DOM build, no global state
 *   impl.pageSize                              optional number; app.js loads this many rows (cap 100,
 *                                              the CRUD controller's MAX_PAGE_SIZE) while the view is active
 *   impl.label                                 optional button label for the view switcher
 *
 *   ctx = {
 *     concept,          // manifest concept node (generated-ui-manifest.json concepts[i]) incl. concept.board / concept.calendar
 *     records,          // panel.records -- the rows app.js already loaded with the current filter/sort
 *     totalElements,    // server total for the current filter
 *     helpers: {
 *       openDetails(record), openForm(mode, record),
 *       setField(record, fieldName, value) -> Promise<{ok, message}>   // POST {endpointBase}/batch setField, then reload
 *       reload(), notify(message, isError),
 *       field(name) -> manifest field node | null,
 *       renderValue(targetEl, field, value)   // = app.js renderFieldValue (enum badges, references, images)
 *       recordTitle(record) -> string         // first list column's display text, else id
 *     }
 *   }
 */
(function () {
  "use strict";
  if (window.NPDevViews && window.NPDevViews._registry) {
    return;
  }
  window.NPDevViews = {
    _registry: {},
    register: function (name, impl) {
      if (!name || !impl || typeof impl.render !== "function") {
        throw new Error("NPDevViews.register(name, { render(ctx) }) requires a render function");
      }
      this._registry[name] = impl;
    },
    get: function (name) {
      return this._registry[name] || null;
    },
    names: function () {
      return Object.keys(this._registry);
    },
    // Detail sections (UI-4 history; later e.g. lifecycle transition buttons): rendered by app.js openDetails, in
    // registration order, below the field rows. impl.render(ctx) -> HTMLElement | null, with
    //   ctx = { concept, record, helpers: { fetchJson, headers, field, renderValue, setField, notify, close } }
    _detailSections: [],
    registerDetailSection: function (name, impl) {
      if (!name || !impl || typeof impl.render !== "function") {
        throw new Error("NPDevViews.registerDetailSection(name, { render(ctx) }) requires a render function");
      }
      this._detailSections = this._detailSections.filter(function (s) { return s.name !== name; });
      this._detailSections.push({ name: name, impl: impl });
    },
    detailSections: function () {
      return this._detailSections.slice();
    },
    // Page-level features (UI-5 global search): app.js calls impl.init(appCtx) ONCE after
    // NPDevShell.mount, with appCtx = { manifest, visibleConcepts(), fetchJson, headers,
    // openDetails(conceptName, record) }.
    _globals: [],
    registerGlobal: function (name, impl) {
      if (!name || !impl || typeof impl.init !== "function") {
        throw new Error("NPDevViews.registerGlobal(name, { init(appCtx) }) requires an init function");
      }
      this._globals = this._globals.filter(function (g) { return g.name !== name; });
      this._globals.push({ name: name, impl: impl });
    },
    globals: function () {
      return this._globals.slice();
    },
    // CARVE-1 modules: code moved verbatim out of app.js. A module file calls
    // registerModule(name, function (host) { ...; return { exports }; }); app.js then calls
    // module(name, host) ONCE with the helpers/state the code uses and destructures the exports.
    _modules: {},
    registerModule: function (name, factory) {
      if (!name || typeof factory !== "function") {
        throw new Error("NPDevViews.registerModule(name, function (host) { return exports; }) requires a factory");
      }
      this._modules[name] = factory;
    },
    module: function (name, host) {
      var factory = this._modules[name];
      if (!factory) {
        throw new Error("NPDevViews module '" + name + "' is not loaded (missing <script> in index.html?)");
      }
      return factory(host);
    }
  };
})();
