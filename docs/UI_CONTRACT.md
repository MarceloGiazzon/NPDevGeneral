# The NPDev UI Contract

Every generated NPDev app publishes a machine-readable description of its own user interface: what
data exists, how it should be presented, what a user may do, and **how to invoke each action**. This
is the contract a frontend — hand-written, generated, or AI-authored — builds against.

## One call

```
GET /api/v1/runtime/metadata/ui/bundle?concept=ExpenseRequest
Authorization: Bearer <token>
X-Tenant-Id: acme
```

(`/api/runtime/metadata/ui/bundle` — no `/v1` — is an equivalent alias, same as every other route
under this controller.) Omit `concept` for an unscoped bundle covering every concept; pass `panel=`
instead to scope `invocations` to one panel's actions/row-ops (see "Scoping" below).

## Response shape

```jsonc
{
  "schemaVersion": "npdev-ui-contract.v1",
  "modelHash": "sha256:…",
  "generatedAt": "2026-07-28T14:27:35.56Z",
  "namespace": "wmsoffice.core",
  "permissionAware": true,
  "scope": { "concept": "ExpenseRequest" },
  "concept": { "name": "ExpenseRequest", … },
  "fields": [ … ], "layout": [ … ], "enums": [ … ], "references": [ … ],
  "actions": [ … ], "transitions": [ … ], "validation": [ … ], "invocations": [ … ],
  "apiBase": "/api/v1",
  "auth": { "scheme": "bearer", "tenantHeader": "X-Tenant-Id" }
}
```

## Only two arrays are permission-filtered — read this before trusting anything

**`fields` and `actions` are filtered for the calling user's role. The other six —
`layout`, `enums`, `references`, `transitions`, `validation`, `invocations` — are NOT filtered.**
They describe the concept's full structure regardless of caller, the same way the model itself is
not a secret. Do not treat their presence as an authorization signal, and do not build a screen that
shows a `transitions` entry or an `invocations` route as if reaching it were guaranteed to succeed —
`fields`/`actions` are where permission denial is actually expressed (`permissionState: "hidden"` /
`"readonly"`, `available: false` + a `denial` object). This is a deliberate scope boundary, not an
oversight: no per-actor filter exists anywhere in the platform for those six catalogs today, so this
endpoint composes what actually exists rather than inventing six new filters.

## The platform's catalogs — and which ones this endpoint exposes

The generator emits twelve named catalogs into every app's `compiled-metadata.json`. The bundle
endpoint exposes eight of them as scoped arrays, plus the single matching `concept` object:

| Catalog | In the bundle? | Answers |
|---|---|---|
| `fields` | yes, filtered | What data, of what type, required, unique, referencing what? |
| `layout` | yes, raw | How should it be shown? Widget, label, group, section, order, `visibleWhen`/`enabledWhen`/`readonlyWhen`/`requiredWhen`. |
| `enums` | yes, raw | Allowed values, with label, order, icon and badge hints. |
| `references` | yes, raw | How to render a picker: display field, search fields, preview card, inline-create. |
| `actions` | yes, filtered | What a user may do: label, confirmation text, success message, danger level. |
| `transitions` | yes, raw | Legal state changes, with guards and required payload. |
| `validation` | yes, raw | What must hold, and where it is enforced. |
| `invocations` | yes, raw | **How to actually perform each action** — method, path, body, async semantics. |
| `concepts` | as a single object, keyed by `scope.concept` | Entity identity, labels, grouping, lifecycle status field. |
| `panels` | **no** | Declared screens and their data sources — fetch `GET .../ui/panels/{panelName}` directly. |
| `procedures` | **no** | Callable server-side procedures — not yet exposed through this endpoint. |
| `domainTypes` | **no** | Reusable value types with format hints — read `compiled-metadata.json` directly if you need these. |

## Scoping

- `?concept=X` — every array (except `invocations` entries that key on `panel`/`aggregate` instead of
  `concept`) is filtered to that concept; `concept` is the resolved entity object.
- `?panel=Y` — `invocations` is filtered to that panel's `panelAction`/`panelRowAdd`/`panelRowDelete`
  entries by hand (there is no generic "panel" filter key the other catalogs share); `concept` stays
  `null` since a panel is not a concept.
- Neither — every array returns every item, unscoped.
- Both given — `concept` wins; `?concept=X|?panel=Y` reads as one-or-the-other, not a conjunction.

## How to perform an action — read this before writing any code

**There is more than one way to write a record, and they are not equivalent.** A generated app
exposes direct CRUD (`POST /api/concepts/{tableName}`, keyed by TABLE name, not concept name) *and*
flow execution (`POST /api/v1/flows/{flowName}/execute`). Using direct CRUD on a concept whose
mutations are flow-backed **silently bypasses that flow's invariants, orchestration and
compensation.**

**Never choose the route yourself. Ask the `invocations` catalog.** Every entry carries:

- `preferred: true` — use this one, or
- `preferred: false` + `prefer: "<other id>"` + `preferReason` — do not use this one, and why.

Correct:

```
POST /api/v1/flows/SubmitExpense/execute
{ "amount": 42.00, "description": "Taxi" }
→ 200 (completed synchronously) or 202 (parked on an awaitEvent) — check execution.statusOnComplete
  / execution.statusOnWaiting rather than assuming either.
```

There is also an untyped admin/direct-execution gateway
(`POST /api/v1/execute/flow`, body `{"tenantId", "flowName", "input"}`) that bypasses the
`{flowName}` path-templated route. **It is real (`DirectExecutionGatewayController`) but is NOT
represented anywhere in the `invocations` catalog** — it requires you to already know the flow name
out-of-band, which defeats the point of a discoverable contract. Use the cataloged
`/api/v1/flows/{flowName}/execute` route instead unless you have a specific reason not to.

### Flow-backed writes can be asynchronous

`202 Accepted` means the flow **started**. It may still be running, and it may park on an event for
days (see `docs/FLOWS.md`). A UI that shows "Saved!" on a 202 is lying. Use
`execution.statusRoute` (`/api/v1/executions/{executionId}/links`) from the invocation entry to
follow the outcome, correlating via `execution.correlationField` (`executionId`). Direct CRUD entries
carry a single `successStatus` (200/201/204) and *are* synchronous — check it per entry rather than
assuming.

### List reads are capped, and say so

`GET /api/{route}` (direct CRUD's plain list endpoint) and `GET /api/concepts/{conceptName}`'s
free-text-search fallback both cap at `ConceptQuery.MAX_LIMIT` (1000 rows) instead of returning an
entire tenant table (RUN-1/R8a). Every response from either carries:

| Header | Meaning |
|---|---|
| `X-List-Truncated` | `"true"` if more rows existed than this response carries; `"false"` if this response is the complete list. |
| `X-List-Limit` | The cap that was applied (currently always `1000`). |

A caller that sees `X-List-Truncated: true` should switch to `GET /api/concepts/{conceptName}?page=&size=`
(pushed-down SQL `LIMIT`/`OFFSET`, no cap on total rows reachable via paging) rather than assume the
list endpoint ever grows past the cap.

### Error codes

| Status | Meaning |
|---|---|
| 400 | Invalid input |
| 403 | Permission denied, or row-scope denied |
| 404 | The requested id/concept/panel does not resolve |
| 422 | Flow-execute invariant/input-validation failure (not 400 — check `execution.statusOnValidationFailure`) |
| 503 | Runtime unavailable (e.g. no schema-realization manifest generated yet) |

## Reactivity is declared, not inferred

`layout` entries carry four predicates — `visibleWhen`, `enabledWhen`, `readonlyWhen`,
`requiredWhen` — as expressions over the current record. Evaluate them; do not reimplement the
rules in JavaScript. They are the same expressions the server enforces, so a UI that ignores them
will disagree with the server.

## Provenance — required if you generate a screen

Any screen built from this contract should ship a provenance manifest recording the `modelHash` it
was built against and which fields/invocations it uses (see F3 in `docs/NEXT_EXECUTION_PLAN.md`
once that manifest format ships). `modelHash` here is `SchemaLifecycleExecutor`'s own schema
fingerprint, reused verbatim — it changes when table/column/type/required/unique shape changes
(e.g. a field rename), but **not** for a panel-action/permission-hint/flow/lifecycle-only edit. A
provenance check against this hash is therefore a precise drift signal for schema-shaped changes and
a silent no-op for the others — a known, accepted boundary.

## Stability

This contract is versioned by `schemaVersion` (currently `npdev-ui-contract.v1`). NPDev is pre-1.0 —
see `BREAKING.md`.

## Shell versioning

The browser shell (`business-ui-index.mustache` + `business-ui-app.mustache` + `business-ui-style.mustache`
+ `shell.js.mustache` + `shell.css.mustache`, ~9,300 lines total) carries no model data and is emitted
byte-identical into every generated app — see Path A finding 1.3(a). `NpdevUiShellVersion`
(`NPDevGenerator/generator/.../emitters/`) is the single source of truth for its version
(`npdev-ui-shell.v1`) and its declared compatibility range against this contract's `schemaVersion`
(currently `npdev-ui-contract.v1`..`npdev-ui-contract.v1`). `BusinessUiEmitter` threads that identity
into the shell templates (a JS constant, an HTML `<meta>` tag, a CSS comment) and into a dedicated
`shell-manifest.json` stamp file alongside `generated-ui-manifest.json`, so a generated app carries a
discoverable version rather than an anonymous copy.

`layout.mustache` is not part of the shipped shell: it is never rendered by `BusinessUiEmitter` or
included by any other template, so it does not carry a version stamp.

## Design tokens — density and theme mode (W1.4)

`shell.css.mustache`'s `:root` block is the single declaration site for every `--np-*` custom
property; `business-ui-style.mustache` consumes them (never re-declares its own base colors) via
`var(--np-token, <fallback>)`, so an app-owned `/theme.css` (injected by `shell.js` when present)
restyles both the shell chrome and every generated business-UI screen by overriding one token in one
place. This is a public surface: an app author's own theme override should target these tokens, not
guess at the underlying literal colors/spacing they resolve to today.

**Density.** `--np-density-scale` plus a matching set of row/control tokens
(`--np-row-pad-v`, `--np-row-pad-h`, `--np-row-font-size`, `--np-control-h`, `--np-pad`) drive row
height, padding and font-size everywhere a data grid or form control reads them. The baseline values
declared in `:root` are `comfortable`; `body[data-np-density="compact"]` is the only variant rule and
overrides them — so an explicit `data-np-density="comfortable"` attribute is a no-op by design, and
adding a new density tier means adding one more `body[data-np-density="..."]` block, never touching
individual selectors.

**Theme mode.** `light` | `dark` | `system`, applied as `data-np-theme` on both `<html>` and `<body>`
(shell.css checks both — the `prefers-color-scheme` escape hatch specifically targets `<html>`). Two
sources feed it, in cascade order:

1. **App-wide default** — the `ui.theme.mode`/`ui.density` settings (`NpdevSettings`, resolved
   through the normal config `defaults`/`overrides` envelope, default `system`/`comfortable`),
   emitted into `generated-ui-manifest.json`'s top-level `theme` block and applied by
   `shell.js`'s `applyAppTheme()` on every boot, before any GuidePage is resolved.
2. **Per-GuidePage override** — a GuidePage's own `theme.mode`/`theme.density` (model-declared,
   `guidePages[].theme`), applied by `applyGuidePage()` immediately after. Left `null` in the
   manifest when the GuidePage doesn't declare them, so the app-wide default is what actually shows
   for every page that hasn't opted into a page-specific override. The three built-in GuidePages
   (`Default`, `Minimal`, `None`) declare no theme at all (`GuidePageDefaults`), so they always follow
   the app-wide default; only an app-declared GuidePage with its own `theme.mode` pins a mode.

`mode: "system"` is applied as the ABSENCE of the `data-np-theme` attribute (removed, not set to the
literal string `"system"`) — that is what lets the `prefers-color-scheme` media query decide. Both
`applyThemeMode()`/`applyDensity()` in `shell.js.mustache` are the one place this logic lives; extend
them rather than re-deriving the cascade elsewhere.

## Shared global states (W1.5 + S10)

Six states every surface needs -- loading, empty, error, permission-denied, no-results, offline --
are owned in ONE place, `shell.js.mustache`, and exposed on `window.NPDevShell` so any surface
(including an Untrusted Extension page) renders the same visual language instead of hand-rolling
its own placeholder:

- `renderLoading(container, { message })`
- `renderEmpty(container, { filtered, message, action: { label, onClick } })` -- `filtered` says
  whether this is "no data matching the current filter" (offer a clear-filter action) or "no data
  yet" (offer a create action); callers that can tell the difference should pass it.
- `renderError(container, { message, action })` -- `message` is whatever the caller/server composed
  and is rendered through `textContent`, never `innerHTML` string concatenation (a caught error or a
  server-echoed message is not trusted markup).
- `renderDenied(container, { reason })` -- `reason` is the SERVER's own denial reason; this never
  invents one. Wired to real data today: a concept form/detail field hidden by
  `field.permissionOverlay.visible === false` (the live `ui/bundle` response, not a declared
  `visibleWhen`) renders this instead of vanishing indistinguishably from a plain conditional hide.
  Extending it to a PanelAction's own `available:false` + `denial` object is a known next step, not
  done yet -- the bundle's `actions` catalog is still not consumed anywhere in the shell (see "The
  shell now also consumes this contract's bundle endpoint" below).
- `renderOffline(container, { message, retry: { label, onClick } })` -- S10: the disconnected
  surface. `message` and `retry` are caller-supplied; this only gives the notice a consistent,
  visually-distinct box (dashed border, muted), never guesses the network condition.

All four render through `stateBox`/`appendStateLine` helpers that only ever set `textContent`, and
are styled purely from the density/theme tokens documented above (`.npdev-shell-state*` in
`shell.css.mustache`) -- a caller passing a server- or user-composed string can't reintroduce an XSS
sink by construction.

### The shell now also consumes this contract's bundle endpoint (Path A P6.2)

`./generated-ui-manifest.json` remains the shell's structural source of truth (routes, panels, nav) —
this contract's bundle endpoint has no equivalent (`panels` is explicitly not exposed here; see "The
platform's catalogs" above), so it cannot replace the manifest, only refine it. At boot,
`business-ui-app.mustache` additionally fetches `GET .../ui/bundle?concept=X` once for every visible
concept (`fetchUiContractBundle`) and overlays the response's permission-filtered `fields` (matched to
the manifest's own fields by `name`/`fieldPath`) onto that concept's manifest fields
(`applyUiContractFieldOverlay`) — `isFieldVisible`/`isFieldReadonlyByCondition` consult the overlay
first, so a field the caller cannot actually read/write is hidden/read-only using the SERVER's real
per-actor answer, not just the declared-presence guess `field.accessWriteScoped` provided before.

Two conditions degrade to the manifest's prior (unfiltered) behavior, never a broken page: a fetch
failure (network error, non-2xx), or a `schemaVersion` outside `NpdevUiShellVersion`'s declared compat
range (compared numerically off the trailing `.vN`). Either logs `console.warn` plus a one-time
"Live permission data unavailable..." toast (`warnUiContractDegraded`) so the degradation is visible,
not silent. The bundle's `actions` catalog (declared PanelActions — label/confirmation/danger-level) is
NOT consumed: the manifest's own concept `actions` are a different, simpler concept (fixed
create/display/update/delete flags), not the same catalog, so overlaying one onto the other would
conflate two unrelated concerns — left as a known, accepted boundary rather than force a shoehorned
merge. Declared Panels/Store/Box View sections are likewise not covered (no concept to scope by).

## View renderers (`window.NPDevViews`)

New business-UI list views and detail-dialog sections do NOT grow `business-ui-app.mustache`. They
are plain static files under `NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/`,
listed in `BusinessUiEmitter.BUSINESS_UI_VIEW_ASSETS`, copied verbatim (`TemplateEngine.raw`, so they
are NOT Mustache and `{{` is safe in them) to `static/npdev-business-ui/views/`, and loaded by one
`<script src="./views/<name>.js">` tag in `business-ui-index.mustache` after `./views/registry.js` and
before `./app.js`. The index carries no model data; whatever a view needs per concept goes in the
concept node of `generated-ui-manifest.json`.

- **List views.** `NPDevViews.register(name, { render(ctx), pageSize, label })`. A concept's manifest
  node carries `views`, always starting with `"table"`; a name is offered in the Table | ... switcher
  only if it is in that list AND its script registered it. With `views: ["table"]` there is no
  switcher and the grid is unchanged. The chosen view is remembered per concept in `localStorage`.
  A view that throws falls back to the table with a toast. `render(ctx)` returns an HTMLElement;
  `ctx` is documented at the top of `views/registry.js`: `concept`, `records`, `totalElements`, and
  `helpers.{openDetails, openForm, setField, reload, notify, listParams, fetchJson, headers, field,
  renderValue, recordTitle}`. Writes go through `helpers.setField`, i.e. `POST {endpointBase}/batch`
  `setField`, so permissions, invariants and audit apply (read `results[0].ok`: the endpoint answers
  200 even when a row fails).
- **Detail sections.** `NPDevViews.registerDetailSection(name, { render(ctx) })`; every registered
  section is rendered by `openDetails` below the field rows, in registration order, and a section that
  throws is logged and skipped. `ctx = { concept, record, helpers: { fetchJson, headers, field,
  renderValue, setField(fieldName, value), notify, close } }`. The built-in section is **History**
  (`views/history.js`): a "Show history" button that loads
  `GET /api/audit?resourceType=<CONCEPT UPPERCASED>&resourceId=<id>&limit=50` on click and lists each
  change (time, actor, Created/Updated/Deleted, and a field / before / after table using the concept's
  field labels). The data is the CRUD audit trail: `GeneratedCrudRuntimeSupport.appendCrudAudit`
  writes `meta.fieldChanges` (a JSON string of `{field, before, after}`, sorted by field name) next to
  the legacy `meta.fieldDiff` string, and `***` replaces both sides of any `sensitive: true` or
  password-widget field. Reading `/api/audit` needs the audit permission (`READ_AUDIT`, ADMIN by
  default): a user without it sees "Change history is visible to users with the audit permission."
  rather than an error. Widening it to every record reader needs its own endpoint that first runs the
  record's read authorization, and is deliberately not done.
- **Board (`views/board.js`).** Appears (concept `views` gains `"board"`, manifest gains `concept.board`
  from `BoardManifestBuilder`) for any concept with a `lifecycle` whose status field is an enum, or any
  concept whose existing `ui.defaultGroup` names an enum field. No new DSL key. Columns are the
  lifecycle states (declaration order) or the enum values; rows with an empty/unknown value go to a
  trailing "—" column. A card moves by drag-and-drop or its "Move to" select (the touch/keyboard
  path). Illegal drop targets are greyed out from the declared lifecycle transitions, but that is UX
  only: every move is `POST {endpointBase}/batch` `setField`, so the server still enforces
  `status_transition_not_allowed`, `status_transition_requires_field` and
  `status_transition_guard_failed`, and the refusal surfaces as a red toast with the card unmoved. A
  transition that declares `requiredPayload` opens the edit form pre-set to the target instead. The
  board loads at most 100 rows (the CRUD controller's `MAX_PAGE_SIZE`) and shows "Showing N of M" when
  the filter matches more; it does not page inside columns.

- **Calendar (`views/calendar.js`).** Appears (concept `views` gains `"calendar"`) for any concept with at
  least one visible `date` or `datetime` field. A Monday-first 6x7 month grid with prev / Today / next;
  each record is a chip on its day (click opens details), three chips per day plus "+N more", and a
  field picker when the concept has several date fields (remembered per concept in `localStorage`; the
  default is the first declared one). No DSL key. The view fetches its own window per visible month:
  the grid's current `filter` / `where` / sort are kept and `<field>:gte:<first grid day>` and
  `<field>:lte:<last grid day>` clauses are appended (`datetime` bounds get `T00:00:00` / `T23:59:59`),
  `size=100` sorted by that field, so a column filter set in Table view still applies. A day is the
  viewer's local day. The list API's `where` DSL (`field:operator:value`, clauses joined by `,` or `;`,
  so never a comma inside a value) accepts `eq`, `ne`, `contains` and, since this view, the range
  operators `gt`, `gte`, `lt`, `lte`: ISO dates and datetimes compare as text, numbers numerically on
  the in-memory (free-text `filter`) path, and a row with no value never matches a range operator.
  More than 100 records in the window shows "Showing N of M in this range".
- **Global features (`views/search.js`).** `NPDevViews.registerGlobal(name, { init(appCtx) })`;
  `bootstrap()` calls every registered global's `init` once, right after `NPDevShell.mount`, with
  `appCtx = { manifest, visibleConcepts(), fetchJson, headers, openDetails(conceptName, record) }`. The
  built-in global is the **search palette** (Ctrl+K / Cmd+K, `/` outside an input, or the top-bar
  button). It needs no server endpoint: for each visible concept that has at least one `filterable`
  field it calls the concept's own list endpoint with `filter=<q>&page=0&size=5` (debounced 300 ms, at
  least 2 characters, at most 6 requests in flight, a superseded query is dropped, a 403 or error on one
  concept just yields no hits there), so permissions and row-level access are exactly the grid's.
  Each concept's free-text filter is bounded by `ConceptQuery.MAX_LIMIT` (1000 rows scanned per
  concept), so a hit may be missing in a very large table: refine the query. A concept with no
  `filterable` field is not searchable, and the server's `filterRows` returns no rows for a non-blank
  `filter` on such a concept (it used to return the whole table).

## Breadcrumbs and default route (W1.6)

The topbar breadcrumb is a projection of the same nav tree `renderSidebar()` already draws
(`deriveNativeGroups()` in `business-ui-app.mustache`), not a second, hand-maintained hierarchy.
`setContext({ title, path, rows })` accepts an ordered `path` array; `shell.js.mustache` joins it with
`" / "`. On activating a concept or declared Panel, `business-ui-app.mustache` looks up which group
(`appShell`-curated, or the native `"Dados"`/`"Paineis"` fallback) contains that target's `hash` and
passes `[groupLabel, title]` — a nested route (e.g. `appShell.navigation` placing `WidgetOrder` under
group `"Operations"`) renders `/ Operations / Widget order`. A target with no group (the superuser-only
Store/Box View/Promotion sections, which have no home in that tree) falls back to a flat `/ Title`,
unchanged from before this task. Callers that only pass `title` (none remain in-repo, but an
Untrusted Extension page could) still get the same single-segment breadcrumb they always did.

Cold entry (`bootstrap()`, no URL hash present) already honours `appShell.defaultRoute` ahead of the
positional `concepts[0]` fallback — built and validated in W1.1, unchanged here.

Recents stay a per-viewer `localStorage` convenience (`RECENT_ITEMS_KEY`, capped at 10) — deliberately
NOT promoted to a model-owned concept for this task. A model-owned "recent" belongs to the workspace
pack, not the shell, and is out of scope here (see W1.6's own ledger item for the tradeoff).
