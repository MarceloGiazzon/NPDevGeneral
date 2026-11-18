// Values only the server can compute (count()/sum() over concepts, permission-scoped). One endpoint:
//   POST /api/x/eval   {"expressions": {"<key>": "<expression text>"}}  ->  {"values": {"<key>": number|string|boolean|null}}
// Every ask made in the same tick (nav badges + all the tiles of a dashboard) goes out as ONE request; answers are
// cached for a few seconds; an endpoint that answers 404/501 (not built, or not allowed) is never asked again; any
// other failure just yields "unknown". Callers treat `undefined` as "show nothing" -- this layer never raises.
import { evalLocal, scopeVars } from "./core.js";

export const EVAL_URL = "/api/x/eval";
export const CACHE_MS = 5000;

export function createServerValues(api, opts) {
  const o = opts || {};
  const now = o.now || (() => Date.now());
  const ttl = o.ttl == null ? CACHE_MS : o.ttl;
  const cache = new Map();   // expression text -> {at, value}
  const inflight = new Map(); // expression text -> Promise
  let queue = null;          // expression text -> resolver, filled during one tick
  let off = false;

  async function flush(batch) {
    const texts = Array.from(batch.keys());
    const expressions = {};
    texts.forEach((text, i) => { expressions["e" + i] = text; });
    let values = null;
    try {
      const res = await api.post(EVAL_URL, { expressions });
      values = res && typeof res === "object" && res.values && typeof res.values === "object" ? res.values : null;
    } catch (e) {
      if (e && (e.status === 404 || e.status === 501)) off = true;
    }
    texts.forEach((text, i) => {
      const known = values != null && Object.prototype.hasOwnProperty.call(values, "e" + i);
      const value = known ? values["e" + i] : undefined;
      if (known) cache.set(text, { at: now(), value });
      inflight.delete(text);
      batch.get(text)(value);
    });
  }

  function ask(text) {
    if (off || !text) return Promise.resolve(undefined);
    const hit = cache.get(text);
    if (hit && now() - hit.at < ttl) return Promise.resolve(hit.value);
    if (inflight.has(text)) return inflight.get(text);
    if (!queue) { queue = new Map(); const batch = queue; Promise.resolve().then(() => { queue = null; return flush(batch); }); }
    const promise = new Promise((resolve) => { queue.set(text, resolve); });
    inflight.set(text, promise);
    return promise;
  }

  return {
    ask,
    isOff: () => off,
    invalidate: () => cache.clear(),
    /** {key: expression text} -> {key: value | undefined}; one request however many keys. */
    async askMany(map) {
      const keys = Object.keys(map);
      const answers = await Promise.all(keys.map((k) => ask(map[k])));
      return Object.fromEntries(keys.map((k, i) => [k, answers[i]]));
    },
    /** Evaluate in the browser when the engine can, else ask the server: the value or undefined. */
    async value(text, user) {
      const local = evalLocal(text, scopeVars(null, null, user));
      return local.ok ? local.value : ask(text);
    }
  };
}
