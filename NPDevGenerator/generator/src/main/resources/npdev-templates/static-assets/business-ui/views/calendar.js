/* NPDev business UI -- Calendar (month) view (UI-6).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/calendar.js
 * App:  static/npdev-business-ui/views/calendar.js (verbatim copy; NOT Mustache)
 *
 * Offered when the manifest's concept.views contains "calendar" (emitter: any shown field whose
 * manifest type is "date" or "datetime"). The user picks WHICH date field in the view header
 * (remembered per concept in localStorage) -- no DSL key needed.
 * Data: its OWN request per visible month, reusing the grid's current filter/where/sort params
 * (ctx.helpers.listParams(), UI-6 step 3) plus two range clauses that UI-6 step 1 teaches the server:
 *   where=<existing>,<field>:gte:<from>,<field>:lte:<to>   size=100  sort=<field> asc
 */
(function () {
  "use strict";
  var FIELD_KEY = "npdev.businessUi.calendarField.";
  // app.js re-renders the whole workspace on every state change (render() clears el.app), so this
  // view is rebuilt often: keep the visible month per concept and reuse a fetch for 5 s instead of
  // refetching on every unrelated re-render.
  var monthByConcept = {};
  var cache = {};

  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function isoDate(d) { return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()); }

  // Pure: the 6x7 grid of days covering `month` (a Date on any day of that month), Monday-first.
  function monthGrid(month) {
    var first = new Date(month.getFullYear(), month.getMonth(), 1);
    var offset = (first.getDay() + 6) % 7; // Monday = 0
    var start = new Date(first.getFullYear(), first.getMonth(), 1 - offset);
    var days = [];
    for (var i = 0; i < 42; i++) { days.push(new Date(start.getFullYear(), start.getMonth(), start.getDate() + i)); }
    return days;
  }

  // Pure: where clauses for the visible range (inclusive) of a date or datetime field.
  function rangeClauses(field, days) {
    var from = isoDate(days[0]), to = isoDate(days[days.length - 1]);
    if (field.type === "datetime") { from += "T00:00:00"; to += "T23:59:59"; }
    return [field.name + ":gte:" + from, field.name + ":lte:" + to];
  }

  // Pure: the local yyyy-mm-dd key a record value falls on ("2026-10-15" or an ISO datetime).
  function dayKey(value) {
    if (!value) { return null; }
    var text = String(value);
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) { return text; }
    var d = new Date(text);
    return isNaN(d.getTime()) ? null : isoDate(d);
  }

  function render(ctx) {
    var dateFields = (ctx.concept.fields || []).filter(function (f) { return (f.type === "date" || f.type === "datetime") && f.showInDefaultWebUi !== false; });
    var stored = "";
    try { stored = localStorage.getItem(FIELD_KEY + ctx.concept.conceptName) || ""; } catch (ignored) { stored = ""; }
    var field = dateFields.find(function (f) { return f.name === stored; }) || dateFields[0];
    var month = monthByConcept[ctx.concept.conceptName] || new Date();

    var root = document.createElement("div");
    root.className = "calendar-view";
    var bar = document.createElement("div");
    bar.className = "calendar-bar";
    var title = document.createElement("strong");
    var grid = document.createElement("div");
    grid.className = "calendar-grid";
    var note = document.createElement("div");
    note.className = "calendar-truncated";

    function navButton(label, delta) {
      var b = document.createElement("button");
      b.type = "button";
      b.textContent = label;
      b.addEventListener("click", function () {
        month = delta === 0 ? new Date() : new Date(month.getFullYear(), month.getMonth() + delta, 1);
        monthByConcept[ctx.concept.conceptName] = month;
        load();
      });
      return b;
    }
    bar.appendChild(navButton("‹", -1));
    bar.appendChild(navButton("Today", 0));
    bar.appendChild(navButton("›", 1));
    bar.appendChild(title);
    if (dateFields.length > 1) {
      var pick = document.createElement("select");
      pick.setAttribute("aria-label", "Date field");
      dateFields.forEach(function (f) {
        var o = document.createElement("option");
        o.value = f.name; o.textContent = f.label || f.name; o.selected = f === field;
        pick.appendChild(o);
      });
      pick.addEventListener("change", function () {
        field = dateFields.find(function (f) { return f.name === pick.value; });
        try { localStorage.setItem(FIELD_KEY + ctx.concept.conceptName, field.name); } catch (ignored) { /* private mode */ }
        load();
      });
      bar.appendChild(pick);
    }
    root.appendChild(bar);
    root.appendChild(grid);
    root.appendChild(note);

    async function load() {
      var days = monthGrid(month);
      title.textContent = month.toLocaleDateString(undefined, { month: "long", year: "numeric" });
      var params = ctx.helpers.listParams();
      var where = (params.get("where") || "").split(",").filter(Boolean).concat(rangeClauses(field, days));
      params.set("where", where.join(","));
      params.set("page", "0");
      params.set("size", "100");
      params.set("sort", field.name);
      params.set("direction", "asc");
      var url = ctx.concept.endpointBase + "?" + params.toString();
      var hit = cache[url];
      var payload;
      if (hit && Date.now() - hit.at < 5000) {
        payload = hit.payload;
      } else {
        grid.textContent = "Loading…";
        try {
          payload = await ctx.helpers.fetchJson(url, { method: "GET", headers: ctx.helpers.headers() });
          cache[url] = { at: Date.now(), payload: payload };
        } catch (error) {
          grid.textContent = "Could not load: " + error.message;
          return;
        }
      }
      var byDay = {};
      (payload.content || []).forEach(function (r) {
        var k = dayKey(r[field.name]);
        if (k) { (byDay[k] = byDay[k] || []).push(r); }
      });
      grid.innerHTML = "";
      ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].forEach(function (d) {
        var h = document.createElement("div"); h.className = "calendar-dow"; h.textContent = d; grid.appendChild(h);
      });
      var today = isoDate(new Date());
      days.forEach(function (day) {
        var key = isoDate(day);
        var cell = document.createElement("div");
        cell.className = "calendar-day" + (day.getMonth() !== month.getMonth() ? " calendar-other" : "") + (key === today ? " calendar-today" : "");
        var num = document.createElement("div"); num.className = "calendar-num"; num.textContent = String(day.getDate());
        cell.appendChild(num);
        var items = byDay[key] || [];
        items.slice(0, 3).forEach(function (r) {
          var chip = document.createElement("button");
          chip.type = "button"; chip.className = "calendar-chip"; chip.textContent = ctx.helpers.recordTitle(r);
          chip.addEventListener("click", function () { ctx.helpers.openDetails(r); });
          cell.appendChild(chip);
        });
        if (items.length > 3) {
          var more = document.createElement("div"); more.className = "calendar-more"; more.textContent = "+" + (items.length - 3) + " more";
          cell.appendChild(more);
        }
        grid.appendChild(cell);
      });
      var total = Number(payload.totalElements || 0);
      note.textContent = total > (payload.content || []).length ? "Showing " + payload.content.length + " of " + total + " in this range — filter to narrow." : "";
    }

    load();
    return root;
  }

  window.NPDevViews.register("calendar", {
    label: "Calendar",
    // The view fetches its own month window, so the grid's own page fetch can stay minimal.
    pageSize: 1,
    render: render,
    _monthGrid: monthGrid, _rangeClauses: rangeClauses, _dayKey: dayKey
  });
})();
