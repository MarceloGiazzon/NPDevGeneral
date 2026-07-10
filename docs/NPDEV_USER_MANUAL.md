# The NPDev Manual — From Zero to a Running App

> **Who this is for:** you don't need to know Java, React, or how a code generator works
> internally. You need to be able to read and write JSON. Everything in this manual has been
> built and run for real — the examples are trimmed versions of apps that already exist and
> already work in this workspace.

---

## 1. What NPDev actually is

NPDev is **not** an app. It is a factory that turns a JSON description of your app (called a
**definition**) into a real, running web application with:

- a database (in-memory, or a real H2/Postgres database),
- a REST API,
- a web page where you can create/edit/delete your data,
- validation rules, events, and automation, if you ask for them.

You describe *what* your app is — its data, its rules, its screens — in JSON files. NPDev
(the "generator") reads that description and writes a complete Java/Spring application for
you. You never hand-write the backend. You only write the *definition*, and optionally some
small pieces of custom logic (a Java method, an HTML page) when the built-in behavior isn't
enough.

Think of it like this:

```
Your JSON definition  --->  [ NPDev generator ]  --->  A real, runnable web app
     (you write this)          (you never touch)         (generated, disposable)
```

If you change the JSON and re-run the generator, you get a new version of the app. If you
delete the generated app entirely, nothing is lost — it can always be regenerated from the
JSON definition. **The JSON definition is the only thing that matters long-term.**

### 1.1 The four building blocks you'll use

| Term | Plain-language meaning |
| --- | --- |
| **Concept** | A "thing" your app stores — like a database table. Example: `User`, `Invoice`, `Product`. It has **fields** (columns). |
| **Flow** | An action your app can perform, callable over the web (`POST /api/flows/<Name>/execute`). Example: "create a user", "issue an invoice". You describe it as a sequence of **steps**. |
| **Capability** | A verb your app needs but doesn't implement itself — "save to storage", "send a notification", "score a support ticket". Built-in capabilities (persistence, events, notifications) come for free. You can also write your own in a few lines of Java (a "custom capability"). |
| **Panel** | A screen in the web UI (a table, a form, a dashboard). Most simple apps don't need to write panels by hand — NPDev **generates a working data-entry web UI automatically** from your concepts. You only write panels yourself when you want a custom-looking screen. |

Two more words you'll see as you go deeper:

| Term | Plain-language meaning |
| --- | --- |
| **Event** | An announcement your app broadcasts when something happens (`UserCreated`, `InvoiceIssued`). Other parts of the app can react to it. |
| **Orchestration rule** | "When event X happens, automatically do Y" — no manual trigger needed. |
| **Reference / bond** | A link from one concept to another, like a foreign key — e.g. an `Invoice` field that points at the `User` who owns it. |
| **Procedure (coda)** | A small server-side script (steps like "read this", "compute that", "save this") for logic that doesn't need to be a public web action. |

---

## 2. The three folders you need to know

NPDev's files live in three places on disk. Only the middle one is where you, as an app
author, actually work day to day.

```
D:\WorkSpace\NPDev\
│
├── NPDev_General\          <- THE ENGINE. Java/TypeScript source code of the generator,
│                              the runtime, the editor. You do NOT edit this to build an app.
│                              (This is also where the build/run *scripts* live —
│                              scripts/appgen/ — because scripts are "engine", not "app".)
│
├── AppGen\apps\<YourApp>\  <- YOUR WORK. Every app you build lives here as a folder with
│                              a `definition\` subfolder full of JSON (+ optional Java/HTML).
│                              This is the ONLY thing you need to back up / version-control
│                              to be able to rebuild your app from scratch.
│
└── Build\                  <- DISPOSABLE OUTPUT. Everything the generator produces:
    └── generated-finalapps\<yourapp>\   compiled code, the runnable .jar, log files,
                                          database files. Safe to delete at any time —
                                          it gets rebuilt from AppGen\apps\<YourApp> on
                                          the next build.
```

**Rule of thumb:** if a file lives under `Build\`, never edit it by hand — your edit will be
silently thrown away next time you regenerate. If you want to change how your app behaves,
edit the JSON under `AppGen\apps\<YourApp>\definition\` and regenerate.

---

## 3. One-time setup

Before building your first app, two things must be true on your machine:

1. **PowerShell 7**, Java (JDK), and the Gradle wrapper must work (already the case on this
   workstation — the scripts use `pwsh.exe`/`gradlew.bat` under the hood).
2. **The RuntimeHost libraries must be staged once** (and again any time the NPDev engine
   itself changes). This is a one-time compile of the engine into a folder every generated
   app links against:

```powershell
& 'D:\WorkSpace\NPDev\NPDev_General\scripts\runtimehost\sync-runtimehost-libs.ps1' `
  -BuildLocalJars `
  -RuntimeHostLibsDir 'D:\WorkSpace\NPDev\Build\runtimehost-libs'
```

This takes a few minutes. You'll know you need to re-run it if a build fails complaining
about a mismatched constructor / stale library.

---

## 4. The five commands that take you from JSON to a running app

Every app, at every complexity level, is built and run the exact same way. This is the whole
workflow:

```powershell
# 1. Generate the app from your JSON definition + emit a control-script toolbox ("_ops")
& 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-AppGenApp.ps1' -App <YourAppName>

# The command above prints where it put things, typically:
$ops = 'D:\WorkSpace\NPDev\Build\generated-finalapps\<yourapp>\_ops'

# 2. Compile it (Gradle build -> runnable .jar)
& "$ops\Build-App.ps1"

# 3. Start it (brings up the database if needed, then the app, waits until it's healthy)
& "$ops\Start-App.ps1"

# 4. Exercise it — either open the browser UI, call the REST API, or run the smoke test:
& "$ops\Test-App.ps1"

# 5. Stop it when you're done
& "$ops\Stop-App.ps1"
```

`-App` accepts either a short name (resolved under `AppGen\apps\`) or a full path. Nothing
about steps 2–5 changes between a one-concept toy app and a full showcase app — the
*complexity lives entirely in your JSON*, not in the commands.

### 4.1 Full command reference

| Script | Where | What it does |
| --- | --- | --- |
| `scripts\appgen\Build-AppGenApp.ps1 -App <name\|path>` | NPDev_General | Reads your `definition\`, calls the generator, produces the app + the `_ops\` toolbox below. Run this again any time you edit your JSON. |
| `_ops\Build-App.ps1` | generated app | Compiles the generated Java project into `FinalExec-0.1.0.jar`. |
| `_ops\Start-App.ps1` | generated app | Starts the database environment (if your app uses a physical H2/Postgres server) and then the app; waits until `/api/flows` answers. |
| `_ops\Status-App.ps1` | generated app | Quick up/down probe. |
| `_ops\Test-App.ps1` | generated app | Runs the data-driven smoke test described by `definition\smoke-plan.json` (if present) against the running app. |
| `_ops\Stop-App.ps1` | generated app | Stops the app and, if applicable, its database server. |
| `_ops\Start-Environment.ps1` / `Stop-Environment.ps1` | generated app | Starts/stops only the database server process (used automatically by Start/Stop-App). |
| `scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir ...` | NPDev_General | One-time (or after an engine change) compile of the shared runtime libraries every generated app links against. |

Output is always written to `D:\WorkSpace\NPDev\Build\generated-finalapps\<scenario.name>`
— never inside `NPDev_General` and never inside `AppGen`.

---

## 5. Anatomy of an app definition

Every app you author is one folder under `AppGen\apps\<YourAppName>\`:

```
AppGen\apps\<YourAppName>\
├── definition\
│   ├── config.json           REQUIRED. App identity, port, database mode, runtime knobs.
│   ├── model.json             REQUIRED. The actual app: concepts, flows, rules, panels...
│   ├── db.definition.json    REQUIRED. Which database engine, and how the schema is managed.
│   ├── manifest.json          optional. Free-form catalog metadata, not used by the generator.
│   ├── smoke-plan.json        optional. A scripted "does it work" test (used by Test-App.ps1).
│   ├── input\                 optional. Sample JSON payloads referenced by smoke-plan.json.
│   │   └── create-user.json
│   └── capabilities\<name>\   optional. Your own Java code for a custom capability.
│       ├── capability.plugin.json
│       └── src\main\java\...\SomeCapability.java
├── web\                        optional. Your own static HTML/CSS/JS pages, served as-is.
│   └── my-page.html
└── README.md                   optional.
```

**Hard rules** (violate these and generation fails loudly, before anything runs):

- `definition\config.json`, `definition\model.json`, `definition\db.definition.json` must
  all exist.
- Every JSON file is validated strictly — **an unknown key anywhere fails generation.** There
  is no "the generator will just ignore that field" — if you invented a key, fix the typo or
  remove it.
- Everything under `definition\` must be self-contained (no `..\` references outside the
  folder) — the builder copies it verbatim before generating.
- Don't hand-edit paths like output roots — you write relative/placeholder paths, the builder
  computes and injects the real absolute paths for you.

### 5.1 `config.json` — identity and runtime knobs

This is the minimal shape every proven app uses (there is no `database` block here — the
database lives in `db.definition.json`):

```json
{
  "$schema": "..\\..\\..\\..\\NPDev_General\\NPDevContract\\schemas\\config.schema.json",
  "configVersion": "1.0",
  "scenario": {
    "name": "myapp",
    "description": "One-line description of the app.",
    "outputRoot": "..\\Output"
  },
  "generator": {
    "failIfModelMissing": true,
    "failIfConfigMissing": true,
    "cleanOutputBeforeGenerate": true,
    "emitPluginAssets": true,
    "emitRuntimeAssets": true,
    "emitUiAssets": true
  },
  "bootstrap": { "root": "..\\..\\NPDevRuntimeHost", "mergeStrategy": "clean-copy" },
  "artifact": {
    "root": "..\\Output\\ArtifactNP",
    "generatedFolderName": "npdev-generated",
    "libsFolderName": "libs",
    "metaFolderName": "npdev-meta"
  },
  "finalExec": { "root": "..\\Output\\App", "deleteBeforeMount": true },
  "runtime": { "springProfile": "dev,step0,trial", "serverPort": 8100, "javaArgs": [], "gradleTask": "bootRun" },
  "trialDefaults": {
    "apiKey": "dev-key",
    "recommendedProfiles": "dev,step0,trial",
    "runtimeUrl": "http://localhost:8100/",
    "databaseMode": "step0-h2",
    "pluginDiscoveryMode": "filesystem-folder",
    "pluginPackageDirectory": "./npdev-generated/src/main/resources/npdev/plugin-packages",
    "notes": []
  }
}
```

Every REST call you make against the running app needs the header
`X-Api-Key: dev-key` (or whatever `trialDefaults.apiKey` says).

### 5.2 `db.definition.json` — the database

The **critical rule**: the database `engine` and `runtime.springProfile` in `config.json`
must be paired correctly, or the app refuses to start.

| `engine` | pair with `runtime.springProfile` | Why |
| --- | --- | --- |
| `InMemory` | `dev,step0,trial` | Zero setup — an ephemeral in-memory database, wiped on every restart. Best for learning and Level 1–2 apps. |
| `H2Server` / `H2Local` / `Postgres` | `dev,trial` (**do not include `step0`**) | A real, persistent database. If `step0` is left in, it silently overrides the datasource and the app refuses to boot with a "connected database is ... finalexec_step0" error. |

InMemory example:

```json
{
  "database": { "engine": "InMemory", "createInternalTables": true, "createBusinessTables": true },
  "schemaLifecycle": {
    "strategy": "RecreateOnAppStart",
    "allowDestructiveRecreate": true,
    "destructiveRecreateConfirmation": "I_UNDERSTAND_INMEMORY_DATA_IS_EPHEMERAL",
    "scope": "NpdevOwnedLogicalStoresOnly"
  }
}
```

H2Server (persistent) example — pick a unique TCP port and a unique data folder per app:

```json
{
  "database": {
    "engine": "H2Server",
    "databaseName": "npdev_myapp",
    "jdbcUrl": "jdbc:h2:tcp://localhost:9200/D:/WorkSpace/NPDev/Build/databases/myapp/npdev_myapp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
    "username": "sa",
    "password": "",
    "createInternalTables": true,
    "createBusinessTables": true
  },
  "schemaLifecycle": {
    "strategy": "DropAndRecreateOnStructureChange",
    "allowDestructiveRecreate": true,
    "destructiveRecreateConfirmation": "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
    "scope": "NpdevOwnedTablesOnly"
  }
}
```

### 5.3 `model.json` — the actual app

This is where your app "happens": `dslVersion` (always `"1.0.0"`), `version` (your own app
version string), `namespace` (a Java-style package name), and `concepts` (at least one) are
required. Everything else — `flows`, `events`, `queries`, `procedures`, `panels`,
`orchestrationRules`, `capabilities`, `customCapabilities` — is optional and is exactly what
the four levels below add, one at a time.

---

## 6. Four levels of app complexity

Each level below is a real, buildable pattern — modeled directly on apps that have already
been generated, compiled, started, and smoke-tested successfully in this workspace. Level 2,
3, and 4 each point to an existing app you can build **today**, with the exact command, so
you can see the pattern run before you write your own.

### Level 1 — Data + an Action, no web page ("headless")

**What you learn:** a concept, a field, a validation rule, a flow, calling it over REST.
**New vocabulary:** Concept, field, invariant, flow, `capabilityCall`.

This is the smallest useful app: one concept (`Note`), one flow that creates it
(`CreateNote`), no web screen — you talk to it purely via REST calls. This is the shape to
reach for when you're testing an idea, wiring an integration, or building something meant to
be called by another program rather than looked at by a person.

`definition\model.json`:

```json
{
  "$schema": "..\\..\\..\\..\\NPDev_General\\NPDevContract\\schemas\\model.schema.json",
  "dslVersion": "1.0.0",
  "version": "0.1.0",
  "namespace": "com.example.notes",
  "concepts": [
    {
      "name": "Note",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "title", "type": "string", "required": true, "maxLength": 200 },
        { "name": "body", "type": "string" }
      ],
      "invariants": [
        { "name": "TitleRequired", "expr": "title != null && title != ''" }
      ]
    }
  ],
  "flows": [
    {
      "name": "CreateNote",
      "concept": "Note",
      "input": { "concept": "Note", "mode": "create" },
      "steps": [
        { "name": "validate-note", "type": "validate", "invariants": ["TitleRequired"] },
        { "name": "save-note", "type": "createConcept", "scope": "Note", "input": "$input", "out": "$saved" },
        { "name": "return-note", "type": "return", "value": "$saved" }
      ]
    }
  ]
}
```

`definition\config.json` sets `"defaults": { "ui.generateBusinessUi": false }` (a top-level
sibling of `scenario`/`generator`/etc.) to deliberately skip the automatic web UI, since this
level is about the API only — omit that block and you'd get Level 2 for free.

`definition\db.definition.json`: the `InMemory` block from §5.2. `runtime.springProfile`:
`"dev,step0,trial"`.

**Run it** (once you've created the folder as `AppGen\apps\notes-app\`):

```powershell
& 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-AppGenApp.ps1' -App notes-app
$ops = 'D:\WorkSpace\NPDev\Build\generated-finalapps\notes-app\_ops'
& "$ops\Build-App.ps1"; & "$ops\Start-App.ps1"
```

**Prove it works** — call the flow over REST:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8100/api/flows/CreateNote/execute' `
  -Headers @{ 'X-Api-Key' = 'dev-key' } -ContentType 'application/json' `
  -Body '{ "title": "Buy milk", "body": "2% please" }'
```

You get back the saved `Note` (with a generated `id`) wrapped in `.output`. Stop with
`& "$ops\Stop-App.ps1"`.

---

### Level 2 — Data with an automatic web page (business CRUD UI)

**What you learn:** how a plain concept becomes a working data-entry screen for free, with no
panel-authoring at all. **New vocabulary:** unique fields, generated business UI, `/api/me`.

Add a couple more fields and a uniqueness rule, and — because you did **not** turn off
`ui.generateBusinessUi` — NPDev automatically generates a full CRUD web page: a table listing
your records, and a form to create/edit them, wired to the same generated REST endpoints,
with client-side validation matching your invariants.

This exact pattern already exists and is proven green as the reference app
`simple-user-registry-inmemory`:

`model.json` (trimmed):

```json
{
  "namespace": "trial.userregistry",
  "dslVersion": "1.0.0",
  "version": "1.0",
  "concepts": [
    {
      "name": "User",
      "ui": { "label": "User" },
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "name", "type": "string", "required": true, "ui": { "label": "Name" } },
        { "name": "email", "type": "string", "required": true, "ui": { "label": "Email" } },
        { "name": "active", "type": "boolean", "required": true, "ui": { "label": "Active" } }
      ],
      "invariants": [
        { "name": "EmailRequired", "expr": "email != null && email != ''" },
        { "name": "NameRequired", "expr": "name != null && name != ''" },
        { "name": "EmailUnique", "type": "unique", "fields": ["email"] }
      ]
    }
  ],
  "events": [ { "name": "UserCreated", "payload": [ { "name": "id", "type": "uuid" }, { "name": "email", "type": "string" } ] } ],
  "flows": [
    {
      "name": "CreateUser",
      "input": { "concept": "User", "mode": "create" },
      "steps": [
        { "name": "validate-user", "type": "enforceInvariants", "scope": "User", "invariants": ["EmailRequired", "NameRequired", "EmailUnique"] },
        { "name": "save-user", "type": "capabilityCall", "cap": "persistence", "op": "save", "args": ["$input"], "out": "$saved" },
        { "name": "emit-user-created", "type": "emitEvent", "event": "UserCreated", "from": "$saved" },
        { "name": "return-user", "type": "return", "value": "$saved" }
      ]
    }
  ]
}
```

**Run the real thing:**

```powershell
& 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-AppGenApp.ps1' -App simple-user-registry-inmemory
$ops = 'D:\WorkSpace\NPDev\Build\generated-finalapps\simple-user-registry\_ops'
& "$ops\Build-App.ps1"; & "$ops\Start-App.ps1"
```

**Prove it works** — open a browser at the app's `runtimeUrl` (its `config.json` sets the
port). You'll see a working page listing `User` records with an "Add" form; creating a user
with a duplicate email is rejected client-side because of `EmailUnique`. This is the entire
value of Level 2: **zero UI code, a working screen.**

---

### Level 3 — Connected data with automation (relationships, events, orchestration)

**What you learn:** linking concepts together (references/"bonds"), reacting to events
automatically without a manual trigger, and read-only server-side procedures.
**New vocabulary:** `reference` field, `onDelete`, `orchestrations`, `procedure` (coda),
`query`.

Real apps are rarely one flat table. Level 3 introduces **references** — a field on one
concept that points at another (like a foreign key) — plus **orchestration rules**, which say
"whenever event X fires, automatically do Y" with no human or explicit flow call involved.

This is exactly what the reference app `invoice-bonds-demo` demonstrates: `User`, `Product`,
`Invoice` (references `User`), `InvoiceItem` (references both `Invoice` and `Product`), and an
automatic notification created the moment an invoice is issued.

`model.json` (trimmed to the interesting parts):

```json
{
  "namespace": "com.npdev.invoicebonds",
  "dslVersion": "1.0.0",
  "version": "0.2.0",
  "concepts": [
    {
      "name": "User",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "email", "type": "string", "required": true, "unique": true, "maxLength": 200 },
        { "name": "displayName", "type": "string", "required": true, "maxLength": 200 }
      ]
    },
    {
      "name": "Invoice",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "invoiceNumber", "type": "string", "required": true, "unique": true, "maxLength": 64 },
        { "name": "userId", "type": "reference", "required": true, "reference": { "target": "User", "onDelete": "restrict" } },
        { "name": "status", "type": "enum", "required": true, "enumValues": ["DRAFT", "ISSUED", "PAID", "VOID"], "default": "DRAFT" }
      ]
    },
    {
      "name": "InvoiceNotification",
      "fields": [
        { "name": "id", "type": "uuid", "id": true, "required": true },
        { "name": "invoiceId", "type": "reference", "required": true, "reference": { "target": "Invoice", "onDelete": "cascade" } },
        { "name": "message", "type": "string", "required": true, "maxLength": 280 }
      ]
    }
  ],
  "events": [ { "name": "InvoiceIssued", "payload": ["id", "invoiceNumber"] } ],
  "queries": [
    { "name": "PendingInvoices", "concept": "Invoice", "where": "status == 'DRAFT'", "orderBy": ["invoiceNumber"], "auditPolicy": "read" }
  ],
  "flows": [
    {
      "name": "IssueInvoice",
      "concept": "Invoice",
      "input": { "concept": "Invoice", "mode": "update" },
      "steps": [
        { "name": "mark-issued", "type": "updateConcept", "scope": "Invoice", "input": "$input", "out": "$issued" },
        { "name": "announce", "type": "emitEvent", "event": "InvoiceIssued", "from": "$issued" },
        { "name": "return-issued", "type": "return", "value": "$issued" }
      ]
    }
  ],
  "orchestrations": [
    {
      "name": "NotifyOnInvoiceIssued",
      "trigger": { "type": "event", "event": "InvoiceIssued" },
      "action": { "type": "create", "concept": "InvoiceNotification", "map": { "invoiceId": "id", "message": "invoiceNumber" } }
    }
  ]
}
```

Read it as: *"When `IssueInvoice` runs, it emits `InvoiceIssued`. Nobody called
`NotifyOnInvoiceIssued` directly — it fires by itself because it's subscribed to that
event, and creates an `InvoiceNotification` row automatically."* That's the whole point of
Level 3: **behavior that reacts on its own**, driven by relationships between your data.

`db.definition.json` uses a real, persistent `H2Server` engine here (paired with
`runtime.springProfile: "dev,trial"`, no `step0`) because this app is meant to keep its data
across restarts.

**Run the real thing:**

```powershell
& 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-AppGenApp.ps1' -App invoice-bonds-demo
$ops = 'D:\WorkSpace\NPDev\Build\generated-finalapps\invoicebonds\_ops'
& "$ops\Build-App.ps1"; & "$ops\Start-App.ps1"; & "$ops\Test-App.ps1"
```

`Test-App.ps1` reads `definition\smoke-plan.json`, which POSTs
`definition\input\create-user.json` and `definition\input\create-product.json` to their flows
and checks `/api/flows` answers — a fully scripted "does it still work" check you get for
free once you've written a couple of sample payloads.

---

### Level 4 — A full application (custom logic, tailored panels, resumable workflow)

**What you learn:** writing your own capability in Java when built-in behavior isn't enough,
authoring a hand-designed panel instead of the automatic one, and a flow that *pauses* and
waits for a real-world event before continuing. **New vocabulary:** `customCapabilities`
(`plugin:java-source`), `panel` authoring, `waitForEvent` (resumable flow), multi-tenant data.

This is where NPDev stops being "just JSON" and starts being a real development platform: you
can drop in a few lines of Java for logic that has no generic built-in equivalent (like
scoring/triage), design a screen that looks exactly the way you want instead of the generic
table/form, and build workflows that don't complete in one HTTP call — they create a ticket,
notify someone, and **wait** until that someone acts, potentially hours later.

This exact pattern is the `Claude` (Claude Support Desk) reference app, already proven
green end-to-end. Three pieces, trimmed from its real `model.json`:

**1. A custom capability** — declared in `model.json`:

```json
"customCapabilities": [
  { "name": "triageAssistant", "type": "TriageAssistantCapability", "operations": ["score"] }
],
"bindings": [
  { "capability": "triageAssistant", "adapter": "plugin:java-source" }
]
```

...implemented as plain Java under
`definition\capabilities\triageAssistant\src\main\java\...\TriageAssistantCapability.java`
(a `Map<String,Object> score(Map<String,Object> ticket)` method — no framework code, no
boilerplate) plus a `capability.plugin.json` descriptor that tells the generator which class
and method to wire up:

```json
{
  "capability": "triageAssistant",
  "capabilityType": "TriageAssistantCapability",
  "adapterId": "plugin:java-source",
  "implementation": {
    "kind": "javaSource",
    "sourceRoot": "capabilities/triageAssistant/src/main/java",
    "mainClass": "com.claude.capabilities.triage.TriageAssistantCapability",
    "factory": { "kind": "defaultConstructor" }
  },
  "operationBindings": [
    { "operation": "score", "method": "score",
      "input": { "mode": "firstArgument", "expectedType": "java.util.Map" },
      "output": { "mode": "returnValue", "expectedType": "java.util.Map" } }
  ]
}
```

The generator compiles that Java file straight into the generated application. Your flow can
now call `triageAssistant.score` like any built-in capability.

**2. A hand-authored panel** (instead of relying on the automatic table/form):

```json
{
  "name": "TriageQueuePanel",
  "route": "/tickets/triage",
  "title": "Triage queue",
  "dataSources": [ { "name": "openTickets", "query": "OpenTickets" } ],
  "layout": { "type": "table", "fields": ["subject", "channel", "priority", "triageScore", "status"] },
  "actions": [ { "name": "SubmitTicket", "binding": "flow", "flow": "SubmitTicket", "label": "Submit ticket" } ]
}
```

**3. A resumable flow** — a step that stops and waits for a real event instead of finishing
immediately:

```json
{ "type": "waitForEvent", "awaitEvent": "TicketResolved", "match": { "correlationId": "$saved.id" } }
```

Once this step runs, the flow instance is parked. It comes back to life — from exactly where
it left off — only when a matching `TicketResolved` event is emitted (e.g., by another flow,
minutes or hours later). This is how "submit a ticket, wait for a human to resolve it, then
notify" is modeled without polling or manual bookkeeping.

**Run the real thing:**

```powershell
& 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-AppGenApp.ps1' -App Claude
$ops = 'D:\WorkSpace\NPDev\Build\generated-finalapps\claude-support-desk\_ops'
& "$ops\Build-App.ps1"; & "$ops\Start-App.ps1"; & "$ops\Test-App.ps1"
```

Open `http://localhost:8090/` for the generated business UI, or `/tickets/triage` for the
hand-authored panel. This app also demonstrates multi-tenant data (`Tenant`/`SupportAgent`/
`SupportTicket`), trace/audit, and a coda procedure — all built the same way as everything
above, just more of it at once.

---

## 7. Quick-reference: level comparison

| | Level 1 | Level 2 | Level 3 | Level 4 |
| --- | --- | --- | --- | --- |
| Reference app | (`notes-app`, write it yourself) | `simple-user-registry-inmemory` | `invoice-bonds-demo` | `Claude` |
| Concepts | 1, standalone | 1, standalone | Several, linked by `reference` | Several, linked, multi-tenant |
| Web UI | None (`ui.generateBusinessUi: false`) | Automatic (table + form) | Automatic | Automatic + hand-authored panels |
| Behavior | One flow, one invariant | Flow + uniqueness rule | Flow + event + orchestration rule + query | + custom Java capability + resumable (`waitForEvent`) flow |
| Database | InMemory | InMemory | Persistent (H2Server) | Persistent (H2Server) |
| You write Java? | No | No | No | Yes — one small plain class |
| Talk to it via | REST only | Browser + REST | Browser + REST + smoke test | Browser (custom panel) + REST + smoke test |

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Build throws about a mismatched/stale constructor | `Build\runtimehost-libs` is out of date with the engine source | Re-run `sync-runtimehost-libs.ps1 -BuildLocalJars ...` (§3) |
| App refuses to boot: "connected database is jdbc:h2:mem:finalexec_step0" | `step0` left in `runtime.springProfile` while using a physical DB engine | Remove `step0`, keep `dev,trial`, for any engine other than `InMemory` (§5.2) |
| Generation fails on `additionalProperties` | An unknown/misspelled key exists somewhere in your JSON | Every schema is strict — remove the key or check its exact spelling against §5 |
| Builder throws "App definition not found" | `definition\` folder missing or one of `config.json`/`model.json`/`db.definition.json` missing | Recheck the required layout in §5 |
| "Strict execution signature mismatch ... unexpected file" at boot | Something was added directly under the generated `npdev-generated\...\static` folder | Never edit generated output. Put your own static pages under your app's `web\` folder instead (§5) — they're served same-origin and are outside the signed/protected tree |
| You edited a file under `Build\...` and it "didn't stick" | `Build\` is fully regenerated on every `Build-AppGenApp.ps1` run | Make the change in `AppGen\apps\<YourApp>\definition\` instead, then rebuild |

---

## 9. Where to go deeper

- `D:\WorkSpace\NPDev\AppGen\apps\APP_DEFINITION_FORMAT.md` — the exhaustive field-by-field
  reference for `config.json` / `db.definition.json` / `model.json`, written for whoever is
  authoring a definition (human or AI).
- `D:\WorkSpace\NPDev\AppGen\apps\README.md` — the list of proven reference apps and the exact
  build commands for each.
- `docs\architecture\NPDEV_BOX_OBJECT_TRUTH_VISION.md` — the underlying philosophy (why
  generated output is disposable, why your JSON is the only real source of truth, how NPDev
  tracks "how proven" an app is before you claim it's release-ready).
