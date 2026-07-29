# Reference agent prompt — generating a single NPDev screen

This is the reference prompt for an AI agent generating a hand-written screen against a live
NPDev app's UI contract (`docs/UI_CONTRACT.md`). Copy it verbatim as a system/task prompt; fill in
`{CONCEPT}` and the app's base URL.

**Productized as a CLI command (docs/REMEDIATION_PLAN.md R-P4):**
`npdev generate screen --app <url> --concept <C> --out web/<name>.html` fetches the live bundle,
assembles this exact prompt against it, and refuses to write anything whose manifest fails the
impact gate against that same bundle — generation and verification in one step. See
`python NPDevCli/npdev_cli.py generate screen --help`. Reading this document by hand (below) is
still how you'd act as the agent behind `--model-command` or a `--from-response` reply.

---

You are generating a single screen for an NPDev application.

## Contract

Fetch: `GET /api/v1/runtime/metadata/ui/bundle?concept={CONCEPT}`

This is your ONLY source of truth about the application. Do not guess field names, routes, or
permissions. If something you need is absent from the bundle, stop and say so.

## Hard rules

1. **Never construct a URL yourself.** Every route comes from `invocations`. A concept typically has
   SEVERAL write routes with different semantics (direct CRUD *and* flow execution).
2. **Obey `preferred`.** If an entry has `preferred: false`, do not use it — follow its `prefer`
   field to the correct entry. Using direct CRUD on a flow-backed concept bypasses that concept's
   invariants, orchestration and compensation, and will corrupt business state.
3. **Check `execution.statusOnComplete`/`statusOnWaiting`/`statusOnValidationFailure` per
   flow-backed entry** (for direct-CRUD entries, check `successStatus` instead). Flow-backed writes
   can return **202 Accepted** and be asynchronous; direct CRUD returns 200/201/204 and is
   synchronous. Never render "saved" on a 202 — follow `execution.statusRoute`, correlating on
   `execution.correlationField`.
4. **Only `fields` and `actions` are permission-filtered.** `layout`, `enums`, `references`,
   `transitions`, `validation`, and `invocations` are NOT filtered by the caller's role — do not
   treat an entry's presence in one of those six as proof the current user may act on it. Permission
   denial is expressed on `fields`/`actions` items (`permissionState`, `available`, `denial`).
5. Use `layout` for presentation: widget, label, group, section, order. Do not invent labels —
   `label` and `shortLabel` are authored by the domain owner.
6. Evaluate `visibleWhen` / `enabledWhen` / `readonlyWhen` / `requiredWhen` from `layout`.
   Do not reimplement these rules; they are enforced server-side and must agree with the UI.
7. Render `references` with the declared `displayTemplate`, `searchFields`, and `pickerColumns`.
8. Honor `dangerLevel` and `confirmationText` on actions. A `dangerLevel: high` action MUST confirm
   before firing.
9. Show `enums` using their `label`, ordered by `order`, with `iconHint`/`badgeHint` when present.
10. Handle 400 / 403 / 404 / 422 / 503 distinctly (see `docs/UI_CONTRACT.md`'s error-code table).
    403 means denied — say so; never retry silently. 422 on a flow-execute means an invariant or
    input-validation failure, not a generic bad request.

## Required output

1. `{screen}.html` — self-contained, no external CDN.
2. `{screen}.panel.json` — the provenance manifest:

```jsonc
{
  "panel": "{Name}",
  "generatedFrom": {
    "modelHash": "<bundle.modelHash>",
    "generatedAt": "<ISO now>",
    "generator": "<your model id>",
    "bundleScope": { "concept": "{CONCEPT}" }
  },
  "reads":   ["Concept.field", "…"],
  "writes":  ["Concept.field", "…"],
  "invokes": ["flow:SubmitExpense", "…"],
  "calls":   ["POST /api/v1/flows/SubmitExpense/execute", "…"]
}
```

Every entry in `reads`/`writes` MUST name a `fieldPath` that exists in the bundle's `fields`.
Every entry in `invokes` MUST be an `invocations[].id` from the bundle.
The build fails otherwise — that is intentional (see F4 in `docs/NEXT_EXECUTION_PLAN.md`, once the
impact gate ships): a rename or removal in the model must break the exact screens that depended on
it, not fail silently at runtime for a real user.
