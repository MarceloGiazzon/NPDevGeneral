// F6-1 layoutSlot demo: a hand-authored KPI strip mounted ABOVE the generated ticket table.
// The api is the narrowed slot api: { panel, data, refresh(), invoke(actionName, data) }.
(function () {
  var host = null;

  // Panel rows are record envelopes -- { tenantId, concept, id, data: { ...fields } } -- so read fields from .data.
  function rowsOf(api) {
    var rows = api.data && api.data.tickets;
    return Array.isArray(rows) ? rows.map(function (row) { return row.data || row; }) : [];
  }

  function draw(el, api) {
    var rows = rowsOf(api);
    var byStatus = {};
    rows.forEach(function (row) { byStatus[row.status] = (byStatus[row.status] || 0) + 1; });
    var tiles = [["Total", rows.length]].concat(Object.keys(byStatus).sort().map(function (s) { return [s, byStatus[s]]; }));
    el.innerHTML = "";
    var strip = document.createElement("div");
    strip.className = "f6-kpis";
    strip.style.cssText = "display:flex;gap:12px;flex-wrap:wrap;margin:8px 0";
    tiles.forEach(function (tile) {
      var box = document.createElement("div");
      box.className = "f6-kpi";
      box.setAttribute("data-kpi", tile[0]);
      box.style.cssText = "border:1px solid #c8d0d8;border-radius:8px;padding:8px 16px;min-width:96px";
      box.innerHTML = '<div style="font-size:12px;opacity:.7"></div><div style="font-size:24px;font-weight:600"></div>';
      box.children[0].textContent = tile[0];
      box.children[1].textContent = String(tile[1]);
      strip.appendChild(box);
    });
    el.appendChild(strip);
  }

  window.npdev.regions.register("ticket-kpis", {
    mount: function (el, api) { host = el; draw(el, api); },
    update: function (api) { if (host) { draw(host, api); } },
    unmount: function (el) { el.innerHTML = ""; host = null; }
  });
})();
