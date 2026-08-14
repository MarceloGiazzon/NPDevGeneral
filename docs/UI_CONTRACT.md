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
