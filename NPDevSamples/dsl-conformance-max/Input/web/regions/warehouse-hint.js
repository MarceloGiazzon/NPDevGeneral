// A hand-authored region for the experience screen `warehouse-hint` (kind custom, component "warehouse-hint").
// Contract: window.npdev.regions.register(name, { mount(el, api) }); api = { screen, user, get(url), refresh() }.
(function () {
  window.npdev.regions.register("warehouse-hint", {
    mount: function (el, api) {
      var title = document.createElement("h3");
      title.textContent = "Warehouse tips";
      var note = document.createElement("p");
      note.className = "hint-user";
      note.textContent = "Signed in as " + (api.user.id || "unknown") + ". Place an order from the New order tab; suppliers are listed under Suppliers.";
      el.appendChild(title);
      el.appendChild(note);
    }
  });
})();
