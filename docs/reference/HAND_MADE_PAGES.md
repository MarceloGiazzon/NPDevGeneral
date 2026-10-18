# Hand-made pages

When no declaration fits a screen — a drawing board, a warehouse map, a public gallery — write the
page yourself, beside the model. It is plain HTML + JavaScript in a `web/` folder. Every generate
copies it into the app, the platform shell wraps it (top bar, menu, sign-in, roles), and it talks to
the app through the same REST API and token as the generated UI. Generated code stays disposable:
delete the output, regenerate, and the page is still there.

Worked examples: `NPDevSamples/pigmentampas/Input/web/studio.html` (a hex mosaic editor, signed in)
and `gallery.html` (a public, logged-out gallery).

## Where the files go

```
Input/
  model.json
  pages.json            menu entries for the pages (optional)
  web/
    studio.html         the page
    studio.panel.json   what the page reads/writes (optional, recommended)
```

Everything under `web/` is copied to the app's `src/main/resources/static/`, so `web/studio.html` is
served at `/studio.html`, and images, scripts or styles beside it keep their relative paths. A file
named like one of the platform's own (`shell.js`, `shell.css`, `info.html`, `info.json`,
`model-surface.html`, `model-authoring.html`, or anything under `npdev-business-ui/` or
`npdev-workbench/`) fails the generate instead of overwriting it.

## The page skeleton

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>My app - Studio</title>
  <link rel="stylesheet" href="shell.css" />
  <script src="shell.js" defer></script>
</head>
<body>
  <div id="root"></div>
  <script>
    document.addEventListener("DOMContentLoaded", function () {
      var api = window.NPDevShell.api;
      api.ready().then(function (user) {
        return api.get("/api/queries/BeersToDrink");
      }).then(function (result) {
        document.getElementById("root").textContent = result.rows.length + " caps to go";
      }).catch(function (err) {
        document.getElementById("root").textContent = err.message;
      });
    });
  </script>
</body>
</html>
```

`shell.js` paints the top bar and side menu, sends a visitor without a token to the login page, and
exposes `NPDevShell.api`.

## `NPDevShell.api`

| Call | Returns |
|---|---|
| `ready()` | A promise resolved once with the signed-in user, `{ id, roles, token }`. |
| `user()` | The same object, right now (empty `id` before sign-in completes). |
| `token()` | The bearer token, or nothing for a logged-out visitor. |
| `get(path)` · `del(path)` | Parsed JSON of the response. |
| `post(path, body)` · `put(path, body)` | Parsed JSON; `body` is sent as JSON. |
| `request(method, path, body)` | The general form of the four above. |
| `fileUrl(handle)` | An `<img src>` for a stored file field (a handle, or the `{ url }` a public read returns). |

Every call sends the user's token and the headers the generated UI sends. A non-2xx response rejects
with an `Error` whose `message` is the server's message, plus `.status` and `.body`. Use the
app's normal endpoints: `/api/concepts/<table>` (the concept's snake_case plural route, e.g. `/api/concepts/mosaic_cells`), `/api/queries/<Query>`,
`/api/runtime/aggregate/<Aggregate>/...`, and `/api/public/**` for public reads. Row-level access,
permissions and validation apply exactly as for the generated screens; a hand-made page cannot see
or write more than its user could anyway.

## Menu entries: `pages.json`

```json
[
  { "path": "studio.html",  "label": "Studio",  "requiredRole": null,     "ordinal": 1000 },
  { "path": "gallery.html", "label": "Gallery", "requiredRole": "MEMBER", "ordinal": 1010 }
]
```

Each entry becomes a top-level menu item. `requiredRole` hides it from users without that role
(it hides the menu entry only; the API still enforces access on every call). Entries without an
`ordinal` get 1000, 1010, … For nested menus, a `menu.json` tree beside `model.json`
(`{ label, ordinal?, requiredRole?, kind?, target?, children? }`) is read too; a page already placed
there as a `PAGE` node is not added twice.

## Public pages

```html
<meta name="npdev-public-page" content="true" />
```

A page with this tag is never redirected to login. A logged-out visitor sees the shell without a
menu, `api.token()` is empty, and the page should read only `/api/public/**`, which serves the
concepts and fields the model declares under `access.public`.

## The sidecar: `<page>.panel.json`

A sidecar records what the page touches, so the extension inventory lists it and the provenance
check after a rebuild can flag a page that reads a field the model no longer has.

```json
{
  "schemaVersion": "npdev-panel-provenance.v1",
  "panel": "Gallery",
  "screen": "web/gallery.html",
  "screenClass": "list",
  "producer": "human",
  "generatedFrom": { "modelHash": null, "generatedAt": "2026-10-07T00:00:00Z",
                     "generator": "hand-written companion page", "bundleScope": {} },
  "reads": ["Mosaic.title", "Mosaic.status", "Cap.image"],
  "writes": [],
  "invokes": [],
  "calls": ["/api/public/concepts/Mosaic"],
  "slotOf": null,
  "confirmed": true,
  "unresolved": [],
  "handWritten": true
}
```

The format is `schemas/panel-provenance.schema.json`; every key above except `screen`,
`screenClass` and `handWritten` is required. `NPDevSamples/pigmentampas/Input/web/gallery.panel.json`
is a complete one.

## Smaller hooks

If only part of a generated screen needs custom code, two narrower hooks exist:

- **A custom region inside an aggregate workbench** — `regionMount` loads an app-owned
  `/regions/<name>.js` into one region of the generated page.
- **A custom widget for one field** — `ui.widget: "custom"` with `ui.customWidgetRef`
  (examples are in AppGen, e.g. under `AppGen/apps/_official/WmsOffice/definition/concepts/`; `NPDevSamples` has none).
