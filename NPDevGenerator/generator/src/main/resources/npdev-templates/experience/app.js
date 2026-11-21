// Experience client: bootstrap, hash router (#/<screen>[/<id>]) and shell (top bar on web, bottom tab bar on mobile).
// It renders only what GET /api/x/me returns and starts only the flows the server resolved as startable; the server
// enforces access on every call. One renderer per archetype; kinds that are not built yet show a clear card.
import { SUPPORTED_KINDS, badgeNumber, buildHash, card, h, iconFor, isVisible, localBadge, navEntries, notSupportedCard,
  parseHash, resolveRoute, scopeVars, tabLayout } from "./core.js";
import { createApi } from "./api.js";
import { BADGE_POLL_MS, createTicker, pollWhileVisible, watch } from "./live.js";
import { createServerValues } from "./serverval.js";
import * as list from "./list.js";
import * as detail from "./detail.js";
import * as form from "./form.js";
import * as status from "./status.js";
import * as inbox from "./inbox.js";
import * as board from "./board.js";
import * as dashboard from "./dashboard.js";
import * as map from "./map.js";
import * as picker from "./picker.js";
import * as documentScreen from "./document.js";
import * as custom from "./custom.js";
import { addInstallable } from "./pwa.js";

const RENDERERS = { list, detail, form, status, inbox, board, dashboard, map, picker, document: documentScreen, custom };
const POLLED_KINDS = ["list", "detail", "status", "inbox", "board", "dashboard", "map"];
const MORE = "__more";

function fatal(root, message) {
  root.replaceChildren(h("div", { class: "shell dev-both" }, h("main", { class: "screen" },
    card("error", NPDevI18n.t("experience.app.loadFailedTitle", "This page could not load"), message),
    h("a", { class: "btn secondary", href: "/", text: NPDevI18n.t("experience.app.openBackOffice", "Open the back office") }))));
}

function navLink(entry, className) {
  const badge = h("span", { class: "badge-count", hidden: true });
  const el = h("a", { class: className, href: entry.screen === MORE ? buildHash(MORE) : buildHash(entry.screen) },
    h("span", { class: "icon", "aria-hidden": "true", text: iconFor(entry.icon) || entry.label.charAt(0).toUpperCase() }),
    h("span", { class: "label", text: entry.label }), badge);
  return { el, badge, entry };
}

export async function start(root, api, env) {
  const win = (env && env.win) || window;
  const doc = (env && env.doc) || document;
  const clock = (env && env.now) || (() => Date.now());
  const server = createServerValues(api, { now: clock });
  const ticker = createTicker(win, clock);
  let me;
  try { me = await api.get("/api/x/me"); } catch (e) { fatal(root, e.message); return; }
  const exp = me && me.experience;
  if (!exp) {
    fatal(root, NPDevI18n.t("experience.app.noExperience", "There is no experience set up for your role."));
    return;
  }
  const user = me.user || {};
  const screens = me.screens || [];
  const byName = Object.fromEntries(screens.map((s) => [s.name, s]));
  const entries = navEntries(exp, byName, user);
  const device = ["web", "mobile", "both"].includes(exp.device) ? exp.device : "both";
  if (["dark", "light"].includes(exp.theme)) doc.documentElement.setAttribute("data-np-theme", exp.theme);
  doc.title = exp.title || exp.name;
  addInstallable(doc, exp); // mobile/both: manifest link + theme colour + home-screen hints (P5-T7)

  // ---- shell
  const { tabs, more } = tabLayout(entries);
  const topLinks = entries.map((e) => navLink(e, "navlink"));
  const tabLinks = tabs.map((e) => navLink(e, "tab"));
  if (more.length) tabLinks.push(navLink({ screen: MORE, label: NPDevI18n.t("experience.nav.more", "More"), icon: "list" }, "tab"));
  const heading = h("h1", { class: "screen-title" });
  const banner = h("div", { class: "banner", role: "status", "aria-live": "polite" });
  const main = h("main", { class: "screen", id: "screen", tabindex: "-1" });
  const header = h("header", { class: "topbar" }, h("div", { class: "brand", text: exp.title || exp.name }),
    entries.length > 1 ? h("nav", { class: "topnav", "aria-label": NPDevI18n.t("experience.nav.main", "Main") }, topLinks.map((l) => l.el)) : null,
    exp.backOffice ? h("a", { class: "backoffice", href: "/", text: NPDevI18n.t("experience.nav.backOffice", "Back office") }) : null);
  const tabbar = entries.length > 1 ? h("nav", { class: "tabbar", "aria-label": NPDevI18n.t("experience.nav.main", "Main") }, tabLinks.map((l) => l.el)) : null;
  root.replaceChildren(h("div", { class: "shell dev-" + device }, header, h("div", { class: "content" }, heading, banner, main), tabbar));

  // bannerSeq: a toast's 3 s timer may only clear ITS OWN banner, never a newer error; keepBanner: the toast of an action
  // survives the refresh/navigation that follows it (afterAction), instead of being wiped by it.
  let bannerSeq = 0;
  let keepBanner = false;
  const say = (text, kind) => { bannerSeq += 1; banner.className = "banner " + (kind || ""); banner.textContent = text || ""; };
  const mark = (links, screen) => links.forEach((l) => {
    if (l.entry.screen === screen) l.el.setAttribute("aria-current", "page"); else l.el.removeAttribute("aria-current");
  });
  const setBadge = (l, n) => { l.badge.textContent = n == null ? "" : String(n); l.badge.hidden = n == null; };
  // A badge the browser engine can evaluate stays local; count()/sum() ask the server, all keys in one request.
  async function refreshBadges() {
    const pending = [];
    for (const l of topLinks.concat(tabLinks)) {
      if (l.entry.badge == null) continue;
      const local = localBadge(l.entry.badge, user);
      if (local.local) setBadge(l, local.value); else pending.push(l);
    }
    if (!pending.length) return;
    const values = await server.askMany(Object.fromEntries(pending.map((l, i) => ["b" + i, l.entry.badge])));
    pending.forEach((l, i) => setBadge(l, badgeNumber(values["b" + i])));
  }
  const updateBadges = () => { refreshBadges().catch(() => {}); };
  let stopBadges = null;
  if (entries.some((e) => typeof e.badge === "string")) {
    stopBadges = pollWhileVisible(win, doc, () => { if (server.isOff()) stopBadges(); else updateBadges(); }, BADGE_POLL_MS);
  }

  // ---- router
  let seq = 0;
  let stopLive = null;
  let cleanups = []; // unsubscribe functions of what the current screen registered (countdown ticks)
  const runCleanups = (list) => { for (const fn of list.splice(0)) { try { fn(); } catch (e) { /* nothing to undo */ } } };
  const go = (hash) => { if (win.location.hash === hash) show(true); else win.location.hash = hash; };
  const detailFor = (screen) => (screens.find((s) => s.kind === "detail" && s.concept === screen.concept) || screen).name;

  async function show(initial) {
    const mine = ++seq;
    if (stopLive && initial) { stopLive(); stopLive = null; } // a live refresh keeps its stream; only navigation restarts it
    if (initial) runCleanups(cleanups);
    const hash = win.location.hash || "";
    if (initial && !keepBanner) say("");
    keepBanner = false;
    if (parseHash(hash).screen === MORE) {
      heading.textContent = NPDevI18n.t("experience.nav.more", "More");
      mark(tabLinks, MORE);
      main.replaceChildren(h("ul", { class: "rows" }, more.map((e) => h("li", { class: "row-item" },
        h("a", { class: "row", href: buildHash(e.screen) }, h("div", { class: "cell title", text: e.label }))))));
      return;
    }
    const route = resolveRoute(hash, exp, byName);
    if (route.error) { heading.textContent = ""; main.replaceChildren(card("error", NPDevI18n.t("experience.app.notFoundTitle", "Not found"), route.error)); return; }
    const screen = route.screen;
    heading.textContent = screen.title || screen.name;
    mark(topLinks, screen.name);
    mark(tabLinks, tabs.some((e) => e.screen === screen.name) ? screen.name : (more.some((e) => e.screen === screen.name) ? MORE : screen.name));
    updateBadges();
    if (!isVisible(screen.visibleWhen, scopeVars(null, null, user), "show")) {
      main.replaceChildren(card("notice", null, NPDevI18n.t("experience.app.screenUnavailable", "This screen is not available right now.")));
      return;
    }
    const renderer = SUPPORTED_KINDS.includes(screen.kind) ? RENDERERS[screen.kind] : null;
    if (!renderer) { main.replaceChildren(notSupportedCard(screen)); return; }
    if (initial) main.replaceChildren(card("loading", null, NPDevI18n.t("experience.app.loading", "Loading...")));
    const fresh = h("div", { class: "screen-body" });
    const mineCleanups = [];
    const ctx = {
      clock, ticker, server, screensByName: byName, onCleanup: (fn) => mineCleanups.push(fn),
      screen, api, user, id: route.id, root: fresh, experience: exp, detailFor, go, reload: () => show(true),
      toast: (text) => { say(text, "ok"); const mine = bannerSeq; win.setTimeout(() => { if (mine === bannerSeq) say(""); }, 3000); },
      reportError: (message) => say(message, "error"),
      afterAction: async (action) => { server.invalidate(); keepBanner = true; if (action.then && byName[action.then]) go(buildHash(action.then)); else await show(true); }
    };
    try { await renderer.render(ctx); }
    catch (error) {
      runCleanups(mineCleanups);
      if (mine !== seq) return;
      if (initial) main.replaceChildren(card("error", NPDevI18n.t("experience.app.screenLoadFailed", "This screen could not load"), error.message));
      else say(error.message, "error");
      return;
    }
    if (mine !== seq) { runCleanups(mineCleanups); return; }
    main.replaceChildren(fresh);
    const previous = cleanups;
    cleanups = mineCleanups;
    runCleanups(previous);
    if (!stopLive && POLLED_KINDS.includes(screen.kind)) stopLive = watch({ win, doc, api, screensByName: byName, fetch: env && env.fetch, now: clock }, screen, () => show(false));
  }

  win.addEventListener("hashchange", () => show(true));
  await show(true);
}

if (typeof document !== "undefined" && typeof document.getElementById === "function" && document.getElementById("app")) {
  NPDevI18n.apply(document); // the static boot text and <html lang> of index.html
  const api = createApi();
  api.adoptCookieSession().then(() => start(document.getElementById("app"), api));
}
