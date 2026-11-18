// Timers of the experience client, all in one place so nothing else owns a clock.
//  - createTicker: ONE shared once-a-second timer for every countdown on screen; it runs only while someone is subscribed.
//  - watch: the "live" refresh hook. FORM P5-T5: a server-sent-events stream (livestream.js), polling as the fallback.
//  - pollWhileVisible: a slow poll that skips ticks while the tab is hidden (navigation badges).
import { openStream } from "./livestream.js";

export const POLL_MS = 15000;
export const BADGE_POLL_MS = 30000;
export const DEBOUNCE_MS = 300;

/** clock() -> epoch ms. subscribe(fn) calls fn(nowMs) every second and returns the unsubscribe function. */
export function createTicker(win, clock) {
  const subs = new Set();
  let id = null;
  const tick = () => { const now = clock(); for (const fn of Array.from(subs)) fn(now); };
  const stop = () => { if (id != null) { win.clearInterval(id); id = null; } };
  return {
    tick,
    size: () => subs.size,
    clear: () => { subs.clear(); stop(); },
    subscribe(fn) {
      subs.add(fn);
      if (id == null) id = win.setInterval(tick, 1000);
      return () => { subs.delete(fn); if (!subs.size) stop(); };
    }
  };
}

/** Concept names a screen shows: its own plus those of the screens its dashboard widgets link to (at most 10). */
export function liveConcepts(screen, byName) {
  const names = [];
  const add = (c) => {
    if (typeof c === "string" && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(c) && !names.some((n) => n.toLowerCase() === c.toLowerCase())) names.push(c);
  };
  add(screen.concept);
  for (const w of Array.isArray(screen.widgets) ? screen.widgets : []) {
    const target = w && w.screen && byName ? byName[w.screen] : null;
    if (target) add(target.concept);
  }
  return names.slice(0, 10);
}

/** Keep a screen fresh. Returns a stop function (or null when the screen is not live). `refresh()` re-renders it.
 *  Polling runs until the server-sent-events stream (GET /api/live, see livestream.js) is connected, then stops; when the
 *  stream cannot be opened (no endpoint, 401/403, ...) polling simply stays. A `changed` notice for a concept of the
 *  screen calls refresh once after DEBOUNCE_MS (a burst coalesces into one refetch). A hidden tab drops the stream and
 *  refetches once when it is visible again. env = {win, doc, api, screensByName, fetch?, now?}. */
export function watch(env, screen, refresh) {
  if (!screen.live) return null;
  const { win, doc } = env;
  const now = env.now || (() => Date.now());
  const fetchFn = env.fetch || (typeof globalThis.fetch === "function" ? (...a) => globalThis.fetch(...a) : null);
  const concepts = liveConcepts(screen, env.screensByName);
  const wanted = new Set(concepts.map((c) => c.toLowerCase()));
  let poll = null, feed = null, debounce = null, retry = null, stopped = false, delay = 1000, openedAt = 0, dropped = false;
  const startPoll = () => { if (!poll) poll = pollWhileVisible(win, doc, refresh, POLL_MS); };
  const stopPoll = () => { if (poll) { poll(); poll = null; } };
  const clear = (t) => { if (t != null && win.clearTimeout) win.clearTimeout(t); };
  const run = () => { try { Promise.resolve(refresh()).catch(() => {}); } catch (e) { /* the next notice or poll retries */ } };
  const poke = () => {
    if (stopped || debounce != null) return;
    debounce = win.setTimeout(() => { debounce = null; if (!stopped && !doc.hidden) run(); }, DEBOUNCE_MS);
  };
  const relevant = (text) => {
    let msg = null;
    try { msg = JSON.parse(text); } catch (e) { return false; }
    return !!msg && typeof msg === "object" && typeof msg.concept === "string" && wanted.has(msg.concept.toLowerCase())
      && (typeof msg.id === "string" || typeof msg.id === "number");
  };
  const closeFeed = () => { if (feed) { feed.close(); feed = null; } clear(retry); retry = null; };
  const connect = () => {
    if (stopped || feed || doc.hidden || !fetchFn || !concepts.length || !env.api || typeof env.api.headers !== "function") return;
    feed = openStream({
      fetch: fetchFn, url: "/api/live?concepts=" + encodeURIComponent(concepts.join(",")),
      headers: Object.assign({}, env.api.headers(false), { Accept: "text/event-stream" }),
      // catch-up refresh on EVERY open, not only after a drop: a row that changed between the screen's first fetch and the
      // moment the stream connected (an accepted offer resumes its parked flow within ~100 ms) was never announced to us
      onOpen: () => { openedAt = now(); stopPoll(); dropped = false; poke(); },
      onEvent: (ev) => { if (ev.event === "changed" && relevant(ev.data)) poke(); },
      onClose: (info) => {
        feed = null;
        if (stopped) return;
        startPoll();
        if (info.opened) { dropped = true; if (now() - openedAt > 10000) delay = 1000; }
        if (info.retry) { retry = win.setTimeout(() => { retry = null; connect(); }, delay); delay = Math.min(delay * 2, 30000); }
      }
    });
  };
  const onVisibility = () => {
    if (stopped) return;
    if (doc.hidden) { closeFeed(); startPoll(); delay = 1000; } else { run(); connect(); }
  };
  startPoll();
  if (typeof doc.addEventListener === "function") doc.addEventListener("visibilitychange", onVisibility);
  connect();
  return () => {
    stopped = true;
    closeFeed();
    clear(debounce); debounce = null;
    stopPoll();
    if (typeof doc.removeEventListener === "function") doc.removeEventListener("visibilitychange", onVisibility);
  };
}

export function pollWhileVisible(win, doc, fn, ms) {
  const id = win.setInterval(() => { if (!doc.hidden) fn(); }, ms);
  return () => win.clearInterval(id);
}
