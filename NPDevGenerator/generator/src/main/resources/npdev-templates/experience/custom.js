// Archetype "custom" (screens[].component): an app-owned region mounted into the screen -- the SAME contract as the back
// office's regionMount: the app serves /regions/<component>.js (Input/web/regions/), which calls
//   window.npdev.regions.register("<component>", { mount(el, api), update(api) })
// The shell only provides the registry and the script load; what the region draws is the app's own code. The api it gets is
// deliberately narrow: { screen, user, get(url), refresh() } -- get() goes through the signed-in session, so a region reads
// exactly what the caller may read (REST, row-level access included) and nothing more.
import { card, h } from "./core.js";

const COMPONENT = /^[A-Za-z0-9_-]+$/;
const loading = new Map();

/** The registry, created once on globalThis.npdev (the back office page defines the identical one). */
export function registry() {
  const root = (globalThis.npdev = globalThis.npdev || {});
  if (!root.regions) {
    const impls = {};
    root.regions = { register: (name, impl) => { impls[name] = impl; }, get: (name) => impls[name] };
  }
  return root.regions;
}

/** Load /regions/<name>.js once; resolves with the registered implementation, rejects with a sentence when it is missing. */
export function loadRegion(name, doc) {
  if (!COMPONENT.test(String(name))) return Promise.reject(new Error("The component name is not valid."));
  const known = registry().get(name);
  if (known) return Promise.resolve(known);
  if (loading.has(name)) return loading.get(name);
  const head = (doc && (doc.head || doc.documentElement)) || globalThis.document.head;
  const promise = new Promise((resolve, reject) => {
    const script = h("script", { src: "/regions/" + encodeURIComponent(name) + ".js" });
    script.addEventListener("load", resolve);
    script.addEventListener("error", () => reject(new Error('The component "' + name + '" could not load (web/regions/' + name + ".js).")));
    head.appendChild(script);
  }).then(() => {
    const impl = registry().get(name);
    if (!impl || typeof impl.mount !== "function") throw new Error('The component "' + name + '" did not call window.npdev.regions.register("' + name + '", { mount }).');
    return impl;
  }).catch((error) => { loading.delete(name); throw error; });
  loading.set(name, promise);
  return promise;
}

export async function render(ctx) {
  const screen = ctx.screen;
  const impl = await loadRegion(screen.component, ctx.doc);
  const host = h("div", { class: "custom-region", "data-component": screen.component });
  ctx.root.appendChild(host);
  const api = Object.freeze({
    screen: screen.name,
    user: JSON.parse(JSON.stringify(ctx.user || {})),
    get: (url) => ctx.api.get(url),
    refresh: () => ctx.reload()
  });
  try { impl.mount(host, api); }
  catch (error) { host.replaceChildren(card("error", "This component could not start", error.message)); }
}
