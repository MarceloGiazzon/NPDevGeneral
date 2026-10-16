/* NPDev business UI -- per-user language switch (UI-8). Copied verbatim (NOT Mustache) to
 * static/npdev-business-ui/views/locale.js.
 *
 * Shown only when the model declares labels in more than one language (manifest.locales, emitted by
 * BusinessUiEmitter.declaredLocales). The choice is kept in localStorage "npdev.ui.locale" and sent as
 * X-Tag-locale on every call (app.js headers(), shell.js authHeaders()); the SERVER resolves labels
 * (kernel LabelResolver: exact tag, language, default), so this file never interprets a label map.
 * Changing the language reloads the page, the simplest correct way to refetch every ui bundle and
 * re-run shell.js's shellString(). "Auto" clears the key, so Accept-Language decides.
 */
(function () {
  "use strict";
  var KEY = "npdev.ui.locale";

  function stored() {
    try { return localStorage.getItem(KEY) || ""; } catch (ignored) { return ""; }
  }

  function init(app) {
    var locales = (app && app.manifest && app.manifest.locales) || [];
    if (locales.length < 2) { return; }
    var select = document.createElement("select");
    select.className = "npdev-shell-locale";
    select.setAttribute("aria-label", "Language");
    select.title = "Language";
    var auto = document.createElement("option");
    auto.value = "";
    auto.textContent = "Auto";
    select.appendChild(auto);
    locales.forEach(function (tag) {
      var option = document.createElement("option");
      option.value = tag;
      option.textContent = tag;
      select.appendChild(option);
    });
    select.value = stored();
    if (select.value !== stored()) { select.value = ""; }
    select.addEventListener("change", function () {
      try {
        if (select.value) { localStorage.setItem(KEY, select.value); } else { localStorage.removeItem(KEY); }
      } catch (ignored) { /* private mode: the choice just will not persist */ }
      location.reload();
    });
    var topbar = document.querySelector(".npdev-shell-topbar");
    if (topbar) { topbar.appendChild(select); }
  }

  window.NPDevViews.registerGlobal("locale", { init: init });
})();
