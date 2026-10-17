/* PROC-4: the shell notification bell. Emitted verbatim (not Mustache) to
 * static/npdev-business-ui/collab/bell.js. shell.js loads it only when the generated manifest says
 * collaboration.notifications is true (the model binds the notify capability).
 * All HTTP goes through NPDevShell.api (token + the SEC-1 CSRF header). It must never throw or show an
 * error to the visitor when /api/notifications fails (auth.mode=none app, 401, network): chrome only.
 */
(function () {
  "use strict";
  if (window.__npdevBell) return;                      // shell applyData runs more than once per load
  window.__npdevBell = true;
  var shell = window.NPDevShell;
  if (!shell || !shell.api) return;
  var POLL_MS = 60000;
  var CSS = "/npdev-business-ui/collab/collab.css";
  if (!document.querySelector('link[href="' + CSS + '"]')) {
    var link = document.createElement("link");
    link.rel = "stylesheet"; link.href = CSS;
    document.head.appendChild(link);
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = String(text);
    return e;
  }

  var button = el("button", "npdev-shell-bell", "🔔");
  button.type = "button";
  button.title = "Notifications";
  button.setAttribute("aria-label", "Notifications");
  var badge = el("span", "npdev-shell-bell-count");
  badge.hidden = true;
  button.appendChild(badge);
  var panel = el("div", "npdev-shell-bell-panel");
  panel.setAttribute("role", "region");
  panel.setAttribute("aria-label", "Notifications");
  panel.hidden = true;

  function setCount(n) {
    badge.hidden = !n;
    badge.textContent = n > 99 ? "99+" : String(n || "");
  }

  function refreshCount() {
    shell.api.get("/api/notifications/unread-count").then(function (b) { setCount(b && b.count); })
      .catch(function () { /* silent: chrome must never error */ });
  }

  // A link is followed only after its read-mark returned, so the navigation cannot cancel the request.
  function openItem(n, row, ev) {
    var target = n.link;
    if (target && (target.charAt(0) !== "/" || target.charAt(1) === "/")) target = null;   // site-relative only
    if (!target) ev.preventDefault();
    if (n.read) return;
    n.read = true;
    row.className = "npdev-shell-bell-item";
    if (target) ev.preventDefault();
    var done = function () { refreshCount(); if (target) window.location.href = target; };
    shell.api.post("/api/notifications/" + encodeURIComponent(n.id) + "/read", {}).then(done, done);
  }

  function renderPanel() {
    shell.renderLoading(panel, { message: "Loading..." });
    shell.api.get("/api/notifications?limit=20").then(function (b) {
      panel.innerHTML = "";
      var items = (b && b.items) || [];
      setCount(b && b.unreadCount);
      var head = el("div", "npdev-shell-bell-head");
      head.appendChild(el("strong", "", "Notifications"));
      var all = el("button", "", "Mark all read");
      all.type = "button";
      all.addEventListener("click", function () {
        shell.api.post("/api/notifications/read-all", {}).then(renderPanel, function () { /* silent */ });
      });
      head.appendChild(all);
      panel.appendChild(head);
      if (!items.length) {
        panel.appendChild(el("div", "npdev-shell-bell-empty", "No notifications."));
        return;
      }
      items.forEach(function (n) {
        var row = el("a", "npdev-shell-bell-item" + (n.read ? "" : " npdev-shell-bell-unread"));
        row.href = n.link || "#";
        row.appendChild(el("div", "npdev-shell-bell-title", n.title));
        if (n.body) row.appendChild(el("div", "npdev-shell-bell-body", n.body));
        row.appendChild(el("div", "npdev-shell-bell-time", new Date(n.createdAtEpochMs).toLocaleString()));
        row.addEventListener("click", function (ev) { openItem(n, row, ev); });
        panel.appendChild(row);
      });
    }).catch(function () {
      panel.innerHTML = "";
      panel.appendChild(el("div", "npdev-shell-bell-empty", "Notifications are unavailable."));
    });
  }

  button.addEventListener("click", function (ev) {
    ev.stopPropagation();
    panel.hidden = !panel.hidden;
    if (!panel.hidden) renderPanel();
  });
  document.addEventListener("click", function (ev) {
    if (!panel.hidden && !panel.contains(ev.target)) panel.hidden = true;
  });

  shell.api.ready().then(function () {
    var slot = document.querySelector(".npdev-shell-topbar .npdev-shell-identity");
    if (!slot || !slot.parentNode) return;
    slot.parentNode.insertBefore(button, slot);
    slot.parentNode.insertBefore(panel, slot);
    refreshCount();
    setInterval(function () { if (!document.hidden) refreshCount(); }, POLL_MS);
  }).catch(function () { /* no shell session: no bell */ });
})();
