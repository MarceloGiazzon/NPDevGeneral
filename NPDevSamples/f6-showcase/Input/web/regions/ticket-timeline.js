// F6-1 layoutSlot demo: a hand-authored list mounted BELOW the generated ticket table. It shows how a
// slot goes back through the generated wiring: the Refresh button calls api.refresh().
(function () {
  var host = null;

  function draw(api) {
    // Panel rows are record envelopes -- { tenantId, concept, id, data: { ...fields } } -- so read fields from .data.
    var rows = (api.data && Array.isArray(api.data.tickets))
      ? api.data.tickets.map(function (row) { return row.data || row; }) : [];
    var ordered = rows.slice().sort(function (a, b) { return (b.priority || 0) - (a.priority || 0); });
    host.innerHTML = "";
    var list = document.createElement("ol");
    list.className = "f6-timeline";
    ordered.forEach(function (row) {
      var item = document.createElement("li");
      item.textContent = "[P" + row.priority + "] " + row.title + " — " + row.status;
      list.appendChild(item);
    });
    host.appendChild(list);
    var refresh = document.createElement("button");
    refresh.className = "f6-timeline-refresh";
    refresh.textContent = "Refresh timeline";
    refresh.onclick = function () { api.refresh(); };
    host.appendChild(refresh);
  }

  window.npdev.regions.register("ticket-timeline", {
    mount: function (el, api) { host = el; draw(api); },
    update: function (api) { if (host) { draw(api); } },
    unmount: function (el) { el.innerHTML = ""; host = null; }
  });
})();
