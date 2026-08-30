# Field widgets and datatypes

How a model field's `type` becomes an input control in the generated app, which widgets exist,
how to pick one, and how to write your own when none fit. Everything in this doc is either the
live contract in `FieldWidgetDefaults.java` or was run through `npdev validate model` / the
generator gate for real before being written down — no guessed syntax.

## 1. The 14 datatypes — all fully supported, including the generated frontend

| `type` | What it holds | Default widget |
|---|---|---|
| `string` | Text | `text` |
| `uuid` | A UUID (usually an `id` field) | `text` |
| `int`, `integer` | Whole numbers | `number` |
| `long` | Large whole numbers | `number` |
| `decimal` | Fixed-point numbers (money, quantities) | `number` |
| `boolean` | True/false | `checkbox` |
| `date` | Calendar date, no time | `date` |
| `datetime` | Date + time | `datetime-local` |
| `enum` | A closed set of named values | `select` (once `enumValues` is declared) |
| `reference` | A foreign key to another concept | `lookup` (single) / `multiselect` (many-to-many) |
| `object` | A nested sub-record | `group` (structural — always the nested editor) |
| `array` | A list of items | `list` (structural — always the nested editor) |
| `file` | An uploaded file (image, PDF, anything) | dedicated upload control (not part of the widget system below) |

Every one of these renders, validates, saves, and round-trips today. There is no "coming soon"
datatype on this list.

## 2. The widget catalogue

A widget is chosen with `ui.widget` on a field. If you don't set one, the table above's default
applies. Every combination is checked at `npdev validate model` time and falls into one of four
buckets:

- **Compatible** — used as declared.
- **Discouraged** — renders, but the platform thinks you probably meant something else. A warning,
  not a blocker — generation still succeeds.
- **Incompatible** — rejected outright. `npdev validate model` fails with a clear message and the
  app will not generate until you fix it.
- **Unknown widget** — you mistyped the name. The error message lists every valid name.

| Widget | Compatible on | Notes |
|---|---|---|
| `text` | any type | The universal fallback — always compatible, even on a boolean or a date, if you really want a plain text box. |
| `textarea` | `string` (compatible); numeric/`uuid` (discouraged) | Multi-line text. |
| `number` | `int` / `integer` / `long` / `decimal` | |
| `range` | `int` / `integer` / `long` / `decimal`, **and** the field must declare bounds via a `domainType` (§4) | A slider. Without bounds it still renders but is discouraged — a slider with no min/max isn't meaningfully a slider. |
| `email` | `string` only | Browser-native email format check. **Do not** use on numeric/uuid fields — see the warning box below. |
| `url` | `string` only | Browser-native URL format check. Same warning applies. |
| `tel` | `string` (compatible); numeric/`uuid` (discouraged) | Phone-shaped keyboard hint; no format enforcement, so it's harmless even where it doesn't quite fit. |
| `password` | `string` only | Masked input. |
| `color` | `string` only | Native color picker; value is a hex string. |
| `date` | `date` only | |
| `datetime-local` | `datetime` only | |
| `checkbox` | `boolean` only | |
| `toggle` | `boolean` only | Same value as `checkbox`, drawn as a switch. |
| `select` | `enum` with `enumValues` declared, or a single `reference` | Dropdown. |
| `radio` | same as `select` | A radio-button group instead of a dropdown — reads better for 2–4 options. |
| `autocomplete` | same as `select` | Type-to-filter version of `select`. |
| `lookup` (alias `search-dialog`) | single `reference` only | A full browse/search dialog instead of a plain dropdown — the right choice once there are more candidates than a dropdown can show comfortably. |
| `image-select` | `enum` with an `iconHint` per option, or a `reference` with `ui.imageField` set | Picks by picture instead of by label. Compatible only once an actual image source is declared — otherwise discouraged. |
| `multiselect` | many-to-many `reference`, or an `array` whose `items.type` is `enum` (a "closed-enum array") | |
| `chips` | same as `multiselect` | Same data, drawn as removable tag chips instead of a checkbox list. |
| `image-preview` | `file`, and only compatible once the field's `file.contentTypes` are all `image/*` | Shows a thumbnail instead of a bare "Download" link. Still renders (discouraged) on an unrestricted file field. |
| `group` | `object` only | Structural — labels the nested-object editor. Rarely set explicitly; it's the default. |
| `list` | `array` only | Structural — labels the nested-array editor. Rarely set explicitly; it's the default. |
| `custom` | any type, but only once `ui.customWidgetRef` is also set | Your own widget — see §5. |

> **Why `email`/`url` are strict about type.** Both render as a real HTML `<input type="email">` /
> `<input type="url">` inside a genuine, validated `<form>`. Put either on a numeric or `uuid`
> field and the browser will silently refuse to submit any value that isn't email/URL-shaped —
> which for a plain number is *every* value. That's why the platform treats this as **incompatible**
> rather than merely discouraged (unlike `textarea`/`tel`, which have no such native format check
> and are genuinely harmless mismatches).

## 3. How to use it — a worked example

```json
{
  "namespace": "shop.demo",
  "dslVersion": "1.0.0",
  "version": "1.0",
  "concepts": [
    {
      "name": "Product",
      "ui": { "label": "Product" },
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "name", "type": "string", "required": true, "ui": { "label": "Name" } },
        { "name": "sku", "type": "string", "ui": { "label": "SKU", "widget": "text" } },
        { "name": "description", "type": "string", "ui": { "label": "Description", "widget": "textarea" } },
        { "name": "priceCents", "type": "long", "ui": { "label": "Price (cents)", "widget": "number" } },
        { "name": "accentColor", "type": "string", "ui": { "label": "Accent color", "widget": "color" } },
        { "name": "inStock", "type": "boolean", "ui": { "label": "In stock", "widget": "toggle" } },
        {
          "name": "status",
          "type": "enum",
          "enumValues": [
            { "value": "Active", "iconHint": "🟢" },
            { "value": "Retired", "iconHint": "⚪" }
          ],
          "ui": { "label": "Status", "widget": "image-select" }
        },
        {
          "name": "tags",
          "type": "array",
          "items": { "type": "enum", "enumValues": ["fragile", "perishable", "oversized"] },
          "ui": { "label": "Tags", "widget": "chips" }
        },
        {
          "name": "brochure",
          "type": "file",
          "file": { "contentTypes": ["application/pdf"] },
          "ui": { "label": "Brochure" }
        }
      ]
    }
  ]
}
```

Every field above uses a widget that's **compatible** with its type. Run it through
`npdev validate model --json` (or just `npdev validate model <path>`) before generating — it's
free, fast, and it's exactly what catches a typo'd widget name or a wrong pairing before you spend
time generating and booting the app.

## 4. Numeric bounds, and why `range` needs a `domainType`

A field's own JSON object has **no** `min`/`max` property — only `minLength`/`maxLength` (strings),
`precision`/`scale` (decimals), and `minItems`/`maxItems` (arrays). Bounds on a plain number are
declared once, by name, as a **domain type**, and then referenced from any field that needs them:

```json
{
  "domainTypes": [
    { "name": "Rating", "baseType": "int", "validation": { "type": "int", "min": 1, "max": 5 } }
  ],
  "concepts": [
    {
      "name": "Review",
      "ui": { "label": "Review" },
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        {
          "name": "stars",
          "type": "int",
          "domainType": "Rating",
          "required": true,
          "ui": { "label": "Stars", "widget": "range" }
        }
      ]
    }
  ]
}
```

This is a real, verified example (`npdev validate model` reports zero errors and zero warnings for
it) — and it's the reusable pattern: define `Rating` once, reference it from as many fields as you
like, and every one of them gets a real, working slider with the declared min/max as its bounds.
Without the `domainType` reference, `range` still renders (as a plain unbounded slider) but is
flagged `discouraged_widget` — a nudge to either add real bounds or pick `number` instead.

## 5. Building your own widget

The 21 built-in widgets don't cover everything — a map picker, a star-rating control, a
signature pad. `ui.widget: "custom"` is the escape hatch, and it's a real sandboxed extension
point, not an unsupported side door.

### 5.1 The three pieces

1. **A JS file**, next to your `model.json` (e.g. `widgets/star-rating.js`):

   ```js
   window.NpdevCustomWidgets.register("widgets/star-rating.js", {
     render: function (field, value) {
       var input = document.createElement("input");
       input.name = field.name;   // required -- this is how the form reads the value back
       input.type = "number";
       input.value = value || "";
       return input;
     }
   });
   ```

   `render(field, value)` receives the field's metadata (name, type, label, …) and its current
   value, and must return an `HTMLElement`. That element must contain a control **named
   `field.name`** — that's the entire write-back contract, the same one every built-in widget
   (lookup, autocomplete, multiselect…) already uses. Fire normal `input`/`change` events so
   conditional-visibility rules (`visibleWhen`/`enabledWhen`) keep working.

2. **A manifest entry**, in `trusted-source-manifest.json` — a sibling file next to `model.json`:

   ```json
   {
     "schemaVersion": "npdev-trusted-source-manifest.v1",
     "entries": [
       {
         "entryId": "star-rating-widget",
         "kind": "widget",
         "relativePath": "widgets/star-rating.js",
         "language": "javascript",
         "sha256": "<sha-256 of the exact file content>",
         "tenantScoped": false
       }
     ]
   }
   ```

   Compute the hash yourself (`sha256sum widgets/star-rating.js` or equivalent) and paste it in —
   this isn't generated for you. If the file's content ever drifts from the hash you declared,
   generation fails closed with `Trusted source SHA-256 mismatch for widgets/star-rating.js`,
   never a silent stale copy.

3. **The field declaration**:

   ```json
   { "name": "stars", "type": "int", "ui": { "widget": "custom", "customWidgetRef": "widgets/star-rating.js" } }
   ```

   `customWidgetRef` is the *same* relative path in all three places — the file on disk, the
   manifest's `relativePath`, and the field's own `ui.customWidgetRef`. Nothing maps between
   different names; it's one path, used consistently.

### 5.2 What your widget's JS is not allowed to do

Every custom widget is scanned before it ships, and the scan is not a suggestion — any of these
fail generation outright, with a message naming the exact violation:

- `eval(...)`, `new Function(...)`, or a dynamic `import(...)`
- `fetch(...)` to anywhere outside this app's own `/generated/...` paths
- `new WebSocket(...)` to any external address
- `document.cookie` or `localStorage.setItem(...)`

This is what makes "custom" safe to allow at all: a widget can render whatever UI it wants, but it
cannot phone home, read/write cookies or local storage, or dynamically evaluate code handed to it
at runtime.

### 5.3 A more realistic extension

The example above is intentionally minimal (it's the real, generator-tested fixture the platform's
own test suite uses). To build something like a location picker, star-rating-with-visuals, or a
signature pad: keep the same `render(field, value)` signature and the same named-input write-back
contract, and put your real UI (canvas, SVG, third-party-free JS library bundled inline) inside the
returned element. A `<img>`-tag-based tile loader is one way to fetch external image assets while
staying inside the `/generated/...` fetch restriction, if you need a map-like widget without a
live WebSocket/fetch connection to a mapping service.

## 6. What isn't supported today

Real gaps, not hedging — if you need one of these, `custom` is the way to get it today:

- **No rich text / Markdown editor.** `textarea` is the ceiling for long-form string content.
- **No currency-formatted number** (thousands separator, currency symbol prefix) — `number`/`range`
  render the raw numeric value.
- **No masked/pattern input** beyond the browser-native shapes `email`/`url`/`tel` give you (no
  generic "phone number formatted as (555) 123-4567" or SSN-style mask).
- **No date-range or duration widget** — two separate `date`/`datetime` fields is the current
  pattern for a range.
- **No native map/geo picker** — build one with `custom` (see §5.3).
- **No barcode/QR display or scanner widget.**
- **No drag-and-drop dropzone styling variant for `file`** — the built-in upload control is
  functional but plain.

## 7. Reference

- Source of truth: `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/FieldWidgetDefaults.java`
  — every rule in §2's table is a direct read of `classify()` in that file, not a paraphrase.
- Rendering: `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-app.mustache`
  (`createInput`/`renderFieldValue`) and `business-ui-style.mustache` for the CSS.
- Trusted-source validation: `TrustedSourceManifest.java` and `TrustedPanelSourcePolicy.java`
  under `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/`.
- Validate any model before generating: `npdev validate model <path> [--json]`.
