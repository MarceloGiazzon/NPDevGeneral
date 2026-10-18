/* NPDev business UI -- global search palette (UI-5).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/search.js
 * App:  static/npdev-business-ui/views/search.js  (verbatim copy; NOT Mustache)
 *
 * VIEW-1: ONE request, `GET /api/search?q=<q>&limit=5` (GlobalSearchController -> GlobalSearchService),
 * which asks the concept gateway once per readable concept with an OR of case-insensitive CONTAINS
 * over the concept's string/enum fields. Same read permission, row rules and field visibility as a
 * normal list, so search can never show a row the grid would not.
 *
 * Fallback: an app generated before VIEW-1 answers 404 there; the palette then does the original
 * client-side fan-out over the per-concept free-text search,
 *   GET {concept.endpointBase}?filter=<q>&page=0&size=5
 * (business-concept-crud-controller.mustache:210-273 -- OR across every `filterable` field,
 *  case-insensitive substring, bounded to ConceptQuery.MAX_LIMIT=1000 rows per concept, answers
 *  X-List-Truncated).
 *
 * Needs registry.js `registerGlobal` (UI-5 step 1) and app.js calling every global's init(appCtx)
 * once after NPDevShell.mount (UI-5 step 2). appCtx =
 *   { manifest, visibleConcepts(), fetchJson(url, opts), headers(), openDetails(conceptName, record) }
 */
(function () {
  "use strict";
  var MIN_CHARS = 2, PER_CONCEPT = 5, MAX_PARALLEL = 6, DEBOUNCE_MS = 300;

  // Pure: which concepts are searched. A concept with NO filterable field must be skipped -- the
  // server's filterRows returns EVERY row when the filterable list is empty (crud-controller :589-591).
  function searchableConcepts(concepts) {
    return (concepts || []).filter(function (c) {
      return (c.fields || []).some(function (f) { return f.filterable; });
    });
  }

  // VIEW-1: the server's flat, ranked list -> the palette's per-concept groups (group order = first hit
  // order, so an exact match's concept leads). Hits for a concept the manifest does not show are dropped.
  function groupServerResults(results, concepts) {
    var byName = {}, groups = [], index = {};
    concepts.forEach(function (c) { byName[c.conceptName] = c; });
    (results || []).forEach(function (hit) {
      var concept = byName[hit.concept];
      if (!concept) { return; }
      if (index[hit.concept] === undefined) {
        index[hit.concept] = groups.length;
        groups.push({ concept: concept, rows: [], total: 0, labels: [] });
      }
      var group = groups[index[hit.concept]];
      group.rows.push(hit.record || { id: hit.id });
      group.labels.push(hit.label);
      group.total = group.rows.length;
    });
    return groups;
  }

  async function runLimited(tasks, limit) {
    var results = new Array(tasks.length), next = 0;
    async function worker() {
      while (next < tasks.length) { var i = next++; results[i] = await tasks[i](); }
    }
    var workers = [];
    for (var w = 0; w < Math.min(limit, tasks.length); w++) { workers.push(worker()); }
    await Promise.all(workers);
    return results;
  }

  function init(app) {
    var overlay = document.createElement("div");
    overlay.className = "global-search-overlay";
    overlay.hidden = true;
    overlay.innerHTML = '<div class="global-search" role="dialog" aria-label="Search">'
      + '<input type="search" class="global-search-input" placeholder="Search all records…" aria-label="Search all records">'
      + '<div class="global-search-results" role="listbox"></div></div>';
    document.body.appendChild(overlay);
    var input = overlay.querySelector(".global-search-input");
    var results = overlay.querySelector(".global-search-results");
    var timer = null, generation = 0;

    function open() { overlay.hidden = false; input.focus(); input.select(); }
    function close() { overlay.hidden = true; }

    async function search(q) {
      var mine = ++generation;
      if (q.length < MIN_CHARS) { results.textContent = ""; return; }
      results.textContent = "Searching…";
      var groups = null;
      try {
        var payload = await app.fetchJson("/api/search?" + new URLSearchParams({ q: q, limit: String(PER_CONCEPT) }).toString(),
          { method: "GET", headers: app.headers() });
        groups = groupServerResults(payload && payload.results, app.visibleConcepts());
      } catch (error) {
        if (error && error.status !== 404 && error.status !== 405) { groups = []; } // a real failure: no fallback storm
      }
      if (groups === null) { groups = await fanOut(q); } // an app generated before VIEW-1
      if (mine !== generation) { return; } // a newer query superseded this one
      render(groups);
    }

    async function fanOut(q) {
      var concepts = searchableConcepts(app.visibleConcepts());
      var tasks = concepts.map(function (concept) {
        return async function () {
          var params = new URLSearchParams({ filter: q, page: "0", size: String(PER_CONCEPT) });
          try {
            var payload = await app.fetchJson(concept.endpointBase + "?" + params.toString(), { method: "GET", headers: app.headers() });
            return { concept: concept, rows: payload.content || [], total: Number(payload.totalElements || 0) };
          } catch (error) {
            return { concept: concept, rows: [], total: 0, error: error }; // 403 etc: silently skip
          }
        };
      });
      return runLimited(tasks, MAX_PARALLEL);
    }

    function render(groups) {
      results.innerHTML = "";
      var any = false;
      groups.forEach(function (group) {
        if (!group.rows.length) { return; }
        any = true;
        var head = document.createElement("div");
        head.className = "global-search-group";
        head.textContent = group.concept.displayName + (group.total > group.rows.length ? " (" + group.total + ")" : "");
        results.appendChild(head);
        group.rows.forEach(function (row) {
          var item = document.createElement("button");
          item.type = "button";
          item.className = "global-search-hit";
          var columns = (group.concept.list && group.concept.list.columns) || [];
          var label = group.labels && group.labels[group.rows.indexOf(row)];
          if (!label) {
            label = columns.filter(function (c) { return c !== group.concept.idField; })
              .slice(0, 2).map(function (c) { return row[c]; })
              .filter(function (v) { return v !== null && v !== undefined && v !== ""; }).join(" · ");
          }
          item.textContent = label || String(row[group.concept.idField]);
          item.addEventListener("click", function () { close(); app.openDetails(group.concept.conceptName, row); });
          results.appendChild(item);
        });
      });
      if (!any) { results.textContent = "No matches."; }
    }

    input.addEventListener("input", function () {
      clearTimeout(timer);
      timer = setTimeout(function () { search(input.value.trim()); }, DEBOUNCE_MS);
    });
    overlay.addEventListener("click", function (e) { if (e.target === overlay) { close(); } });
    document.addEventListener("keydown", function (e) {
      var typing = /^(INPUT|TEXTAREA|SELECT)$/.test((e.target && e.target.tagName) || "") || (e.target && e.target.isContentEditable);
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); open(); }
      else if (e.key === "/" && !typing && overlay.hidden) { e.preventDefault(); open(); }
      else if (e.key === "Escape" && !overlay.hidden) { close(); }
    });

    // Entry point in the shell top bar (shell.js buildTopbar :302 gives it class npdev-shell-topbar);
    // fall back to a floating button when the page has no shell chrome (GuidePage "None").
    var trigger = document.createElement("button");
    trigger.type = "button";
    trigger.className = "global-search-trigger";
    trigger.title = "Search (Ctrl+K)";
    trigger.setAttribute("aria-label", "Search all records");
    trigger.textContent = "🔍";
    trigger.addEventListener("click", open);
    var topbar = document.querySelector(".npdev-shell-topbar");
    if (topbar) { topbar.appendChild(trigger); } else {
      trigger.classList.add("global-search-trigger-floating"); document.body.appendChild(trigger);
      // The shell may paint its top bar after globals are initialised: move the trigger into it then.
      if (window.MutationObserver) {
        var observer = new MutationObserver(function () {
          var late = document.querySelector(".npdev-shell-topbar");
          if (late) {
            trigger.classList.remove("global-search-trigger-floating");
            late.appendChild(trigger);
            observer.disconnect();
          }
        });
        observer.observe(document.body, { childList: true });
        setTimeout(function () { observer.disconnect(); }, 15000);
      }
    }
  }

  window.NPDevViews.registerGlobal("search", {
    init: init, _searchableConcepts: searchableConcepts, _groupServerResults: groupServerResults
  });
})();
