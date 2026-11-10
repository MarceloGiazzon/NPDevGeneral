# NPDev features

> **Re-audited row by row on 2026-11-08** against `NPDevContract/schemas/model.schema.json`, the
> CLI's own `--help`, `NPDevMcp/server.py` and the RuntimeHost/kernel source. That audit corrected
> four rows that had drifted (aggregate functions, conversion operators, `sensitive`, restart on a
> model change) and added the features the page had never listed: lifecycles, soft delete,
> effective dating, sequences, rollups, record comments, human tasks, compensation, webhooks, HTTP
> connectors, operation policies, dashboards, public pages, sign-up/2FA/OIDC, `agentAccess` and
> hosting. The date is a floor on staleness, not a guarantee. **A feature list is a promise**, so
> when a row and the code disagree, the code is right and the row is a bug.

---

## The shape of it

**One JSON file describes your whole application.** Not a schema — an application: data, rules,
processes, screens, permissions, and how it all changes over time.

| Area | What you declare |
|---|---|
| **Data** | concepts, fields, types, constraints, relationships, inheritance |
| **Rules** | invariants, expressions, validation, lifecycle |
| **Behaviour** | flows, procedures, events, scheduled work |
| **Screens** | panels, workbenches, documents, guides |
| **Access** | roles, grants, row-level rules, sensitive fields |
| **Change** | renames, conversions, destructive-change policy |
| **Scale** | packs, fragments, bounded contexts, scoped properties |

---

## 1. Data model

| Feature | What it does for you |
|---|---|
| **14 field types** — `string`, `uuid`, `int`, `long`, `boolean`, `date`, `datetime`, `enum`, `reference`, `object`, `array`, `file`, `integer`, `decimal` | covers real business data without escape hatches; `file` and `array` included |
| **`domainTypes`** | define `MRN` or `Money` **once** — format, validation, widget, label — then reuse it everywhere. Change the rule in one place |
| **`invariants`** | `unique` (single or compound) becomes a **real DB constraint**, and `expression` rules are enforced as **runtime validation with a clear error** (no SQL `CHECK` constraint is emitted) — not a comment nobody enforces |
| **`reference` fields / bonds** | foreign keys, joins, cascade behaviour, and a **searchable picker in the UI** — from one declaration |
| **`extends` / `specializes`** | concept inheritance. Shared fields declared once; specialized concepts add to them |
| **`indexes`** | declare the indexes your queries need; they are created and tracked |
| **`derivedExpression` / `defaultExpression`** | computed and defaulted values evaluated **server-side**, so every client agrees |
| **`sensitive`** | mark a field and its value is masked in trace, event and execution records, in audit-trail diffs (`*** -> ***`), and in external-AI review packs. The CSV/XLSX export does not mask it yet |
| **`enumValues`, `minLength`, `maxItems`, `uniqueItems`, …** | 35 field properties in total — the ordinary constraints you would otherwise hand-write in three places |
| **`lifecycle`** | a state machine on a status field: declared states and transitions (with optional `guard`, `requires` and an action button), an initial state filled in on create, and any other move refused with `CONCEPT_LIFECYCLE_TRANSITION_INVALID`, on single and batch updates |
| **`softDelete`** | a delete only stamps `deletedAt`; every generated read hides the row, a generated restore brings it back, and a `unique` value is freed for reuse |
| **`temporal`** | effective-dated rows (price lists, tax rates, assignments): several rows for one entity over non-overlapping validity windows |
| **`sequences`** | document numbers such as `INV-2026-0001`: a `defaultExpression` calls `nextNumber('name')` and the next value is allocated atomically |
| **`rollups`** | a stored field on a parent kept equal to an aggregate over its children (`Mosaic.likeCount`), recomputed on every child save, delete and restore |
| **`collaboration`** | `comments: true` gives every record a comment thread with `@username` mentions; `attachments: true` lets files be attached to it |
| **`satelliteOf`** | extend a concept another pack owns (`identity::User`) with your own fields, without touching that pack |
| **`truthLevel` (T0–T6)** | classify how *trustworthy* each concept's data is; a reference to less-trusted data raises a warning at authoring time |

## 2. The database, and changing it

**This is the part most generators do not do.**

| Feature | What it does for you |
|---|---|
| **Schema evolution, not drop-and-recreate** | regenerate against a live database and **your data survives** |
| **`renamedFrom`** | renames move the column *and its data*, instead of being seen as a drop plus an add |
| **Destructive-change refusal** | dropping a column or concept is refused unless you explicitly acknowledge it. **Your data is not collateral damage of an edit** |
| **`conversions`** — `copy`, `split`, `lookup`, `merge`, `convert`, `coalesce`, `case` | reshape existing data as part of a migration, declaratively: split a name into two columns, look a value up against another table |
| **Raw-SQL hooks** | when the declarative vocabulary is not enough, run SQL, guarded by a claim that must match the real schema diff |
| **H2 → PostgreSQL / MySQL / SQL Server** | develop on a file database, promote to any of the three production engines with a real operator command (`npdev engines` lists what each needs and its caveats) |
| **Snapshots** | table-level snapshot and restore around risky migrations |
| **Multi-instance safety** | a real lock, so two instances cannot migrate the same database at once |

## 3. API and queries

| Feature | What it does for you |
|---|---|
| **Generated REST API** | full CRUD for every concept, consistent, with a generated OpenAPI description |
| **CSV/XLSX export, CSV import** | every generated list exports its current filtered, sorted view, and imports a CSV through a propose → review → commit dialog that runs the same validation as a create |
| **`queries`** with `where`, `orderBy`, `limit`, `parameters` | named, parameterised, permission-checked queries — no string-built SQL in a controller |
| **`groupBy` + `aggregates` + `having`** | roll-ups (`count`, `sum`, `avg`, `min`, `max`) as declarations, including **joins across concepts** — dashboards without a reporting layer |
| **`lookups` + `computed` on a grouped query** | bring a second concept's per-key total into each grouped row and do arithmetic over the columns — "cells needed minus caps I own" in one query, with `having`/`orderBy` on the result |
| **`permissionRequirements` per query** | a query carries its own access rule, so it cannot be exposed by accident |
| **Audit trail** | every concept create/update/delete/restore is logged automatically with actor, timestamp and a field-level before/after diff — not opt-in, not a model setting |
| **`selectors`** | reusable pickers/lookups shared across screens |

## 4. Business logic

| Feature | What it does for you |
|---|---|
| **`flows` — 13 step types** | `invariantCheck`, `capabilityCall`, `generatedAction`, `emitEvent`, `scheduleEvent`, `return`, `branch`, `awaitEvent`, `createConcept`, `updateConcept`, `map`, `forEach`, `callProcedure` |
| **Durable execution** | a flow **survives a process restart** and resumes where it stopped. Long-running approvals do not evaporate on a deploy |
| **`awaitEvent`** | pause a process until something happens — an approval, a delivery, a webhook — for as long as it takes |
| **Human tasks (`awaitEvent.task`)** | the wait becomes a task in the assignee's inbox (`GET /api/tasks`), with a title, a form and named `outcomes`; completing it resumes the flow with the values and who completed it |
| **`onFailure` compensation** | saga-style cleanup: if a later step fails for good, the completed steps' `onFailure` steps run in reverse order (best effort, not a distributed transaction) |
| **`forEach`, including parallel** | iterate a collection; parallel iterations stay **isolated from each other** and each resumes correctly after a crash |
| **`scheduleEvent` + `schedule`** | delayed and recurring work without a separate scheduler |
| **`procedures`** | reusable server-side logic with parameters, locals, and a return value |
| **`orchestrationRules`** | react to events: when *this* happens, run *that* |
| **`capabilities` / `bindings`** | name an operation, bind it to an implementation — swap adapters without touching the model |
| **Operation `policy`** | per capability operation: `retryCount`/`retryDelayMs`, `timeoutMs`, a circuit breaker (`circuitOpenAfterFailures`), a bulkhead (`bulkheadMaxConcurrent`) and idempotency keys |
| **HTTP connectors** | a capability bound to `http-connector` calls an outside REST API with no client code; `baseUrl` can come from a tenant-overridable property, and credentials are named environment variables, never values in the model |
| **`webhooks`** | inbound doors at `POST /api/hooks/{source}`: the HMAC-SHA256 signature is checked against a named environment variable, then the event wakes a flow waiting on it, or `startFlow` starts one |
| **`customCapabilities`** | your own Java, called from a flow, when the declarative vocabulary runs out — sandboxed (no files, network or DB), so a flow hands it what it needs: query results, and uploaded images through the built-in **`fileStore.readImage`** capability (a `file` field → tenant-checked `data:` URI). Pigmentampas' *Paint from photo* is the worked example |

## 5. Screens

| Feature | What it does for you |
|---|---|
| **Generated admin UI** | list, create, edit, search for every concept, from day one, with no work |
| **`autoPanels`** | richer screens derived automatically from the model |
| **`panels`** | declared screens with a real `route`, multiple `dataSources`, layout, field bindings, and actions |
| **Dashboards and consoles** | a panel with `layout.type: "dashboard"` or `"console"` renders declared `widgets[]`: `tile` (one aggregate number), `chart` (bar, line or donut per `groupBy` value), `table`, `actions` and `history`. Example: `NPDevSamples/f6-showcase` |
| **Panel `slots`** | mount your own component `above`, `below` or instead of (`replace`) a generated panel body |
| **`appShell`** | your own navigation tree and default route for the app's shell |
| **`settings.strings` / `settings.locale`, themes** | replace any of the platform's UI text in your language, and pick a built-in theme or your own `themes/<id>.json` (`ui.theme.name`, plus `ui.theme.choices` for an end-user picker) — see `NPDEV_FEATURE_GUIDE.md` §11 |
| **`visibility` / `enabledWhen`** | show and enable things conditionally, by rule rather than by JavaScript |
| **`actions`** | buttons that call flows or procedures, including ones that **stream a generated file back** |
| **`aggregates` (workbenches)** | master-detail-detail screens editing a root and its collections **in one transaction**, with `onValidate` and `onCommit` hooks |
| **`documents`** | generated documents and printable output: one-record and aggregate documents, and `kind: list` multi-record reports (named query, columns, grouping, subtotals, grand total) as PDF and CSV, computed over what the viewer may read |
| **`guidePages` / `explainability`** | attach guidance to a screen so users are told what it is for — in the model, beside the thing it explains |
| **Widget catalogue** | field types map to appropriate inputs, validated for compatibility at authoring time |
| **Paintable boards (`render: "cellGrid"`)** | a workbench collection drawn as a square or hex board instead of a row grid: each child row is one painted cell (row, col, value), the palette is a query's rows (label, image, colour), with paint/fill/erase/undo/zoom, and Save still commits the whole aggregate in one transaction. Examples: Pigmentampas `Mosaics` (20×33 hex mosaic of caps), `dsl-conformance-max` `ShelfLayoutWorkbench` (warehouse shelf map) |
| **Hand-made pages (`web/*.html`)** | when no declaration fits — a drawing board, a map — write the page yourself beside the model. It is copied into the app on every generate, wrapped by the shell (nav, sign-in, roles), listed in the menu from `pages.json`, and talks to the app through `NPDevShell.api` (`get`/`post`/`fileUrl`, same token and errors as the generated UI). Example: `NPDevSamples/pigmentampas/Input/web/studio.html`; how-to: [reference/HAND_MADE_PAGES.md](reference/HAND_MADE_PAGES.md) |
| **Public pages** | a hand-made page with `<meta name="npdev-public-page" content="true">` is served to anonymous visitors, outside the menu, and reads `/api/public/**` — the concepts and fields the model opens under `access.public`. Read-only: anonymous requests there are GET/HEAD only. Example: Pigmentampas `gallery.html` |
| **Custom regions and widgets** | smaller escape hatches: an app-owned region inside a generated workbench (`transaction.regions.<address>: { "render": "component", ... }`), or one field rendered by your own widget (`ui.widget: "custom"`) |

## 6. Security

| Feature | What it does for you |
|---|---|
| **`roles` + `grants`** | declare roles and what they may do |
| **`roles[].concepts`** | per-concept CRUD by role — `{ "Mosaic": ["read", "create"] }`; once any role names a concept, only the roles naming it reach it |
| **Multi-role users** | `POST /api/auth/create-user` takes `roleNames[]`; a tenant ADMIN adds roles via `POST /api/auth/users/{username}/roles` and removes one at a time via `DELETE /api/auth/users/{username}/roles/{role}` |
| **`permissionRequirements` / `visibility`** | `permissionRequirements` on queries, procedures and panel actions, `visibility` on panels — the permissions a caller needs, declared next to the thing they protect |
| **Row-level authorization** | restrict *which rows* a user sees, under a real transaction |
| **`access` per concept** | per-concept `read` / `write` rules, and `public`: the fields anonymous visitors may read at `/api/public/**` |
| **`sensitive` fields** | masked in trace, event and execution records, audit-trail diffs and external-AI review packs |
| **JWT or API-key auth** | pick per deployment; API keys map to roles |
| **Identity pack** | self-service sign-up, password reset, invitations, change password, authenticator-app two-factor (TOTP, with recovery codes) and session listing and revocation, with login throttling and a password policy |
| **Google, GitHub and OpenID Connect sign-in** | on top of username and password; configured by environment variables (`NPDEV_AUTH_OIDC_<ID>_…`) or the keyring, with no model change |
| **Super-user bootstrap** | a key issued on first boot, written to disk — no default password to forget to change |
| **ControlPanel** | built-in admin surface for users, roles, credentials, schedules |

## 7. Composition and scale

| Feature | What it does for you |
|---|---|
| **`fragments`** | split a large model across files; compose by `$ref` |
| **`packs`** | reusable model modules shared across applications, resolved locally or from a git coordinate and locked in `npdev.lock`. Built in: `identity`, `workspace`, `approvals`, `contacts`, `helpdesk`, `inventory`, `leave`, `notification`, `purchasing`, `sales`, `tracing`. A pack's `requires` must be met by the app's `provides` |
| **`contexts`** (bounded contexts) | partition a big model into named contexts with **explicit `imports`** — so one team's `Order` is not another's |
| **`physicallyIsolate`** | opt in to context-qualified table names when two contexts genuinely need separate tables |
| **`propertyScopes` / `properties`** | configuration that **cascades** — each property has a model `default`, overridden per tenant and per user (there is no runtime "global" scope); resolution order is declared |
| **Multi-tenancy** | tenant isolation is built in, not bolted on |
| **`externalAi`** | declare external AI usage explicitly, with egress validated (`denied` by default, `packOnly` or `apiEnabled`) |
| **`agentAccess`** | an AI assistant on the app itself, over MCP, Telegram or WhatsApp (each off by default). Every tool call goes through the app's REST API as the calling or linked user, so roles and `access` rules still decide; `photoIntake` turns a photo sent to the bot into a draft record |

## 8. Operating it

| Feature | What it does for you |
|---|---|
| **Docker Compose generated** | a Dockerfile, `docker-compose.yml` and a Caddy TLS reverse-proxy recipe in every app, with a database service and backup where the engine supports one |
| **`npdev host`** | take an app from private to reachable: `plan`, `check` (what a real user would hit, with `--fix`), `share` through a tunnel, `deploy` to your own server. See `HOSTING.md` |
| **`npdev package` / `upgrade` / `service`** | bundle a built app's runnable jar, install or upgrade it in place on another machine, and register it with the OS supervisor |
| **`npdev import db`** | draft a model from an existing JDBC database (`import genexus` layers a GeneXus export on top) |
| **`npdev test`** | model-derived, REST and browser checks against one booted app |
| **NPDev Manager** | a desktop app that installs the prerequisites and runs, builds and edits apps with no terminal. See `MANAGER.md` |
| **Seed data** | named JSON seed files for demos, tests, and fresh environments, plus `seeds[]` in the model applied once at first boot |
| **Audit + trace policies** | the audit trail is unconditional (every governed create, update, delete and restore). On an untrusted-extension procedure, `actionDescriptor` declares `tracePolicy`, `idempotencyPolicy`, `auditResourceType` and `eventNameOnSuccess`. The per-step `trace`/`audit` flags on ordinary procedure steps are accepted but not read |
| **`npdev dev`** | watch the model and, on every save, apply the change to the running app (a new flow, query, role grant, enum value, concept or optional field) or regenerate, rebuild and restart when it has to, evolving the schema either way |
| **`npdev doctor`** | check the machine before you spend an hour finding out |
| **`npdev capabilities`** | what each storage engine can do — read from the dialects, so it always matches what the generator refuses |
| **`npdev migrate`** | when NPDev's own DSL changes, a codemod rewrites your models |
| **`npdev validate`** | typed, machine-readable diagnostics with the exact path and a suggested fix |
| **MCP server + AI authoring** | 24 tools (`NPDevMcp/server.py`, re-counted 2026-10-12; was 16 at this doc's last full verification) so an agent can author, validate, correct itself, generate, and — added since 2026-08-05 — query the semantic reuse graph (`npdev_graph_explain`, `npdev_graph_reuse`), gate a diff before authoring (`npdev_author_diff_gate`, `npdev_author_submit`), and run one intent-to-runtime-proof loop (`npdev_close_loop`) |

---

## The honest frame

**These are declarations, not code you write.** The whole list above is JSON in one file. That is
the ease.

**And it is a real Spring Boot application at the other end** — source you own. (It does link the NPDev kernel and adapter jars at build and run time; they are staged next to the
app.) That is the quality.

**You are not boxed in.** When no declaration fits, you write the screen (a hand-made page, signed-in
or public), the component (a panel slot, region or widget), the logic (a custom capability or an
untrusted-extension procedure), or another client against the REST API. None of these is generated
code you edit, so a regeneration keeps all of them.

**What NPDev does not do *for* you:**
- **Brand design.** The generated screens are functional and themable. A designed, customer-facing
  screen is one you write as a hand-made page (§5).
- **Microservices.** One model is one deployable app. Several services are several apps, connected
  by REST, HTTP connectors, webhooks and shared packs.
- **Anonymous writes.** Public pages read only what `access.public` opens. Writing needs a signed-in
  user, and self-service sign-up (§6) provides one.
- **Every change live.** Many model changes go live with no restart (§8 `npdev dev`). Removing,
  renaming or retyping a field, or adding a required, unique or reference field, still means a
  rebuild and restart.

Those and every other designed limit are in `docs/ACCEPTED_BOUNDARIES.md` — **kept accurate on
purpose, because a stale limitations page costs more trust than a short feature list.**

---
