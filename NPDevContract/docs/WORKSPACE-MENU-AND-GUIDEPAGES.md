# Workspace Menu, GuidePages, and Shell Gadgets

This is the authoring reference for the platform-default corporate shell: the unified frame
(top bar + collapsible left nav tree + collapsible right gadget rail) that both the generated
business UI and any hand-authored companion page share via `shell.js`/`shell.css`. It covers three
related but separate mechanisms: **GuidePage** (declared frame/theme definitions), **menu.json**
(the authoring source for the System nav tree), and **shell gadgets** (right-rail widgets).

## GuidePage

A GuidePage is a first-class, contract-declared object controlling which chrome regions render
and how they're themed. Declare zero or more under the app model's top-level `guidePages` array,
alongside `panels`:

```json
"guidePages": [
  {
    "name": "Default",
    "default": true,
    "regions": {
      "top": true,
      "left":  { "enabled": true,  "collapsible": true },
      "right": { "enabled": true,  "collapsible": true, "defaultCollapsed": true, "width": 280 }
    },
    "theme": { "mode": "light", "accent": "#0b5fff", "density": "comfortable", "logoText": "", "logoUrl": "" },
    "gadgets": [ { "name": "recent", "type": "recent-items", "title": "Recentes" } ]
  },
  { "name": "Visitor", "regions": { "top": false, "left": { "enabled": false }, "right": { "enabled": false } } }
]
```

**Built-ins.** Three GuidePages always exist, even if the app declares none: `Default` (top bar +
left nav), `Minimal` (top bar only), `None` (no chrome at all — the page/section owns the whole
viewport). A declared GuidePage with one of these names **overrides** the built-in of the same name
in place; every other declared name is additive. Exactly one GuidePage may be marked `"default": true`;
if none is, the (possibly overridden) `Default` is used.

**Assigning a GuidePage to a surface:**
- **Concept section** (generic business UI): the `ui.guidePage` setting, resolved via the usual
  concept → app cascade (`SettingTarget.forConcept`). If unset, falls back to the existing
  `ui.frame.mode` setting mapped `full → app default`, `minimal → Minimal`, `none → None` — so an
  app that only ever configured frame mode keeps rendering exactly as before.
- **Declared Panel**: an optional `guidePage` string directly on the panel object.
- **Hand-authored page**: `<meta name="npdev-guide-page" content="Visitor">` in `<head>`. Omit it
  to use the app's default GuidePage.

An explicit `ui.guidePage`/panel `guidePage` naming an unknown GuidePage **fails generation** with
a clear diagnostic — this is authoring error, not a case to silently fall back from.

**Regions**: `top` (bool), `left`/`right` (`enabled`, `collapsible`, `defaultCollapsed`, and — for
`right` only — `width` in px). **Theme**: `mode` (`light`/`dark`), `accent` (any CSS color),
`density`, `logoText`/`logoUrl` (overrides the shell's default title-derived logo).

## `definition/menu.json` — the System nav tree

`workspace::Menu` rows are what actually render as the "System" section of the shell's left nav
(header = the app's `appName`). Authoring every row by hand as a flat table loses hierarchy, so
apps that want a multi-level tree (groups, nested groups) declare it once as
`definition/menu.json`, a sibling of the existing `definition/pages.json`:

```json
[
  { "label": "Demanda", "ordinal": 10, "children": [
      { "label": "Centro de Trabalho", "kind": "PAGE", "target": "centro-trabalho.html", "ordinal": 10 },
      { "label": "Recebimentos", "kind": "BUSINESS", "target": "Recebimento", "ordinal": 40 },
      { "label": "Topologia", "ordinal": 50, "children": [
          { "label": "Areas", "kind": "BUSINESS", "target": "Area", "ordinal": 10 }
      ]}
  ]}
]
```

- A node with `children` (or no explicit `kind`) is a **GROUP** — a non-navigable collapsible
  label; nesting is unlimited.
- A leaf node's `kind` is `PAGE` (target = relative URL of a hand-authored page under `web/`) or
  `BUSINESS` (target = a persisted concept name or declared Panel name).
- `Build-NpdevApp.ps1` flattens this tree at build time into `npdev-seed/workspace-menu-pages-seed.json`
  alongside any `pages.json` entries not already referenced in the tree (so an app with only
  `pages.json` produces the exact same seed as before `menu.json` existed — fully backward
  compatible). `WorkspaceMenuSeeder` resolves the tree's authoring-only `key`/`parentKey` pairs
  into real `parent_menu_id` UUIDs at boot.
- Business concepts and declared Panels **not** referenced anywhere in `menu.json`/`pages.json`
  still get a nav entry automatically — the generic business UI derives a "Dados"/"Paineis" group
  for them (`NPDevShell.mount({ deriveNativeGroups })`), and admin concepts (identity/workspace
  packs) get their own auto-derived, super-user-gated "NPDev" section. `menu.json` is for curating
  *hierarchy and ordering*, not for making something reachable that otherwise wouldn't be.

### Seeder modes

`WorkspaceMenuSeeder` runs once per boot and is controlled by `npdev.workspace.menu-seed.mode`:

- **`insert-if-empty`** (default): if `workspace_menus` already has any row for the tenant, do
  nothing at all — an app author's edits/additions via the generic CRUD UI are permanent. This is
  the safe default; existing installs need no changes and keep working exactly as before.
- **`upsert-if-fingerprint-changed`** (opt-in): computes a SHA-256 fingerprint over the merged seed
  rows (the generator's own derived seed + `menu.json`/`pages.json`) and records it in a hidden
  marker row (`kind: INTERNAL`, `target: npdev:seed-fingerprint:<hash>`, `visible: false`). On
  every boot, if the freshly computed fingerprint matches the stored one, nothing happens
  (idempotent no-op). If it differs — because the app author changed `menu.json`/`pages.json` and
  redeployed — **all rows for the tenant are deleted and reseeded from scratch**, including the new
  fingerprint. This is deliberately destructive: any manual edit made through generic CRUD since
  the last seed is lost along with the stale rows. Use it only for environments where the
  declared menu is the source of truth and redeploys should always pick up authoring changes
  (e.g. a staging/demo environment reset on every deploy) — not for a production tenant where
  operators curate the nav by hand.

Manual fallback for either mode: truncate `workspace_menus` for the tenant and restart: the seeder
reseeds from scratch on the next boot regardless of mode.

## Shell gadgets

The right rail renders one card per gadget declared on the active GuidePage. Three built-in types:

- **`recent-items`** — the last 10 sections/pages visited, tracked client-side in `localStorage`.
- **`context-info`** — freeform rows supplied by `NPDevShell.setContext({ title, rows })`; the
  generic business UI calls this on every section switch (`title` also drives the topbar
  breadcrumb).
- **`page-fragment`** — fetches a same-origin URL (`{"type":"page-fragment","url":"..."}`) and
  injects the HTML via `innerHTML` (display-only; embedded `<script>` tags never execute).

Register a custom type at runtime from any page's own script, after `shell.js` has loaded:

```html
<script>
  window.NPDevShell.registerGadget("my-widget", function (container, gadgetConfig) {
    container.textContent = "Hello from a custom gadget: " + gadgetConfig.title;
  });
</script>
```

Unknown gadget types render a small "unknown gadget type" placeholder instead of failing.
