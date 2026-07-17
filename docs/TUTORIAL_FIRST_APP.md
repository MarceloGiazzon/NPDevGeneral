# Your first NPDev app (LNCH-22 golden path)

This walks through the whole loop — author a model, validate it, generate a running app, verify
it — using the exact `simple-contact-intake` model this repo already ships and gate-tests
(`NPDevSamples/simple-contact-intake`). Per `docs/adr/ADR-0006-authoring-path.md`, the primary way
to author a model is conversationally, through an AI agent using the NPDev MCP tools — this
tutorial shows both what that AI-authored model looks like and the exact commands you (or the AI
on your behalf) run to turn it into a real app, so it doubles as a reference for reading what an
AI produced.

**Why this doubles as a CI gate, not just a doc that can rot:** the model shown below is not a
tutorial-only fixture — it's `NPDevSamples/simple-contact-intake/Input/model.json`, the exact file
`scripts/quality/run-runtimehost-gate.ps1` regenerates, builds, and tests by default every time
that gate runs. If a platform change ever breaks this model or the flow it declares, the gate goes
red before this tutorial could silently go stale.

## What you're building

A contact-intake form: one concept (`ContactMessage`), one flow (`SubmitContactMessage`) that
validates the input, saves it, sends a notification, and emits an event — the smallest complete
slice through NPDev's concept/capability/flow/event model.

## 1. The model

```json
{
  "namespace": "trial.contactintake",
  "dslVersion": "1.0.0",
  "version": "1.0",
  "concepts": [
    {
      "name": "ContactMessage",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "name", "type": "string", "required": true },
        { "name": "email", "type": "string", "required": true },
        { "name": "message", "type": "string", "required": true },
        { "name": "status", "type": "string", "required": true }
      ],
      "invariants": [
        { "name": "NameRequired", "expr": "name != null && name != ''" },
        { "name": "EmailRequired", "expr": "email != null && email != ''" },
        { "name": "MessageRequired", "expr": "message != null && message != ''" }
      ]
    }
  ],
  "capabilities": [
    { "name": "persistence", "type": "PersistenceCapability", "operations": ["save"] },
    { "name": "notification", "type": "NotificationCapability", "operations": ["send"] }
  ],
  "bindings": [
    { "capability": "persistence", "adapter": "repository" },
    { "capability": "notification", "adapter": "notification-inproc" },
    { "capability": "eventBus", "adapter": "inproc" }
  ],
  "events": [
    { "name": "ContactMessageReceived", "payload": [
      { "name": "id", "type": "uuid" }, { "name": "email", "type": "string" }, { "name": "status", "type": "string" }
    ]}
  ],
  "flows": [{
    "name": "SubmitContactMessage",
    "input": { "concept": "ContactMessage", "mode": "create" },
    "steps": [
      { "name": "validate-contact-message", "type": "enforceInvariants", "scope": "ContactMessage",
        "invariants": ["NameRequired", "EmailRequired", "MessageRequired"] },
      { "name": "save-message", "type": "capabilityCall", "cap": "persistence", "op": "save",
        "args": ["$input"], "out": "$saved" },
      { "name": "send-notification", "type": "capabilityCall", "cap": "notification", "op": "send",
        "args": ["$saved"], "out": "$delivery" },
      { "name": "emit-contact-event", "type": "emitEvent", "event": "ContactMessageReceived", "from": "$saved" },
      { "name": "return-message", "type": "return", "value": "$saved" }
    ]
  }]
}
```

Read it top to bottom: a **concept** is a data shape with fields and invariants (business rules
that must hold, checked as an `enforceInvariants` flow step or automatically around CRUD). A
**capability** is a named, adapter-pluggable side effect (`persistence`/`notification` here — see
`docs/DSL_REFERENCE.md`'s Capability section for the full shape); a **binding** picks which
concrete adapter backs it (`notification-inproc` here logs instead of sending real email — swap to
an SMTP adapter, see `docs/EMAIL_NOTIFICATIONS.md`, without touching the flow). A **flow** is an
explicit sequence of steps — this one is the platform's generated `POST` handler for creating a
`ContactMessage`, made explicit rather than implicit so you can see exactly what happens on
create: validate, save, notify, emit an event other flows/orchestrations can react to, return.

If you're prompting an AI to build something like this rather than hand-writing JSON: describe the
concept and what should happen on submit in plain language; the AI should reach for
`npdev_search_examples` (finds a precedent model shape close to what you're asking for) and
`npdev_check_support` (tells it honestly what this platform can and cannot yet build, before it
generates something that will fail) via the NPDev MCP server, then iterate with
`npdev validate model` (below) until it's clean.

## 2. Validate

```sh
./npdev validate model NPDevSamples/simple-contact-intake/Input/model.json
```

A clean model prints no errors. An invalid one — try deleting `"required": true` from the `id`
field's `"id": true` sibling, or misspelling a capability name in a flow step — prints a JSON
report where every diagnostic carries a stable `code`, a human `message`, and (where available) a
`suggestedFix`/`helpKey` (`ValidationDiagnostic`, wired through `ModelValidatorMain`) — not a raw
Java stack trace or an internal class name.

## 3. Generate + build + run

```sh
./npdev generate app --model NPDevSamples/simple-contact-intake/Input/model.json \
  --config NPDevSamples/simple-contact-intake/Input/config.json \
  --output build/npdev-tutorial-output
```

Or, for the full assembled-and-buildable FinalApp (what the gate actually does):

```powershell
.\NPDevSamples\scripts\generate-sample-app.ps1 -SampleId simple-contact-intake -NPDevRoot .
cd NPDevSamples\simple-contact-intake\Output\App
.\gradlew.bat bootJar
java -jar build\libs\FinalExec-0.1.0.jar --server.port=8080
```

## 4. Verify it's real

```sh
curl -s -X POST http://127.0.0.1:8080/api/contact_messages \
  -H "Content-Type: application/json" -H "X-Api-Key: dev-key" \
  -d '{"name":"Ada","email":"ada@example.test","message":"Hello","status":"New"}'
```

You should get back the saved record with a generated `id`. Submit one with a blank `name` and you
should get a `400` carrying the `NameRequired` invariant's code and message, not a stack trace —
the same invariant declared in the model above, enforced exactly where the flow said to enforce
it.

## Where to go from here

- `docs/DSL_REFERENCE.md` — every field/step/capability shape this model used, and the rest the
  schema supports (bonds, lifecycle, panels, schedules, compensation...), generated from the
  schema itself.
- `docs/NPDEV_CONCEPTS_DEEP_DIVE.md` — the conceptual model behind concepts/flows/capabilities.
- `docs/architecture/FLOW_TRANSACTION_CONTRACT.md` — what happens when a flow step fails partway
  through (this flow's `save-message`/`send-notification`/`emit-contact-event` sequence is exactly
  the shape that contract is about).
- `docs/GETTING_STARTED.md` — the `npdev` CLI's other commands (`normalize ai-model`, `report
  bootstrap`).
