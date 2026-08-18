# NPDev feature guide

What NPDev can do, in the order you are likely to need it. Every section answers four questions:
**what it is** in one paragraph with no class names, **when to reach for it**, **JSON you can
copy**, and **the real app that uses it** — because a feature nothing in the corpus uses is a
feature nobody has proven.

You write a JSON *model*. NPDev generates a complete Spring Boot application from it — database
schema, REST API, web UI, background jobs — and you run that application. Nothing here is a
framework you import; it is all generated code you own.

Start with `docs/GETTING_STARTED.md` if you have not built an app yet. This guide assumes you
have one and want to know what else is available.

## 1. Concepts and fields — the UI you get for free

**What it is.** A concept is a thing your app stores: a customer, an order, a task. You declare
its name and its fields, and NPDev gives you a database table, a REST API over it, and a
working table-and-form screen — without writing a screen.

**When to reach for it.** Always. Everything else in this guide sits on top of concepts.

```json
{
  "name": "Task",
  "truthLevel": "T2",
  "ui": { "label": "Task" },
  "fields": [
    { "name": "id", "type": "uuid", "id": true, "required": true },
    { "name": "title", "type": "string", "required": true, "ui": { "label": "Title" } },
    { "name": "priority", "type": "integer", "ui": { "label": "Priority" } },
    { "name": "status", "type": "string", "ui": { "label": "Status" } }
  ]
}
```

The `ui.label` is not decoration — it is what the generated screen shows a human. A field
without one gets a warning, because a column headed `ownerEmail` is a column somebody has to
translate in their head.

**Real app:** `NPDevSamples/npdev-canary` — two concepts, a full working app.

## 2. The four ways to get a screen

**What it is.** NPDev will not make you hand-write a screen, but it will let you. There are
four paths, and the difference is how much control you want in exchange for how much you write.

| # | Path | You write | You get |
|---|---|---|---|
| 1 | Automatic Business UI | nothing — just concepts | a table + form per concept |
| 2 | `autoPanels[]` | one entry per concept or aggregate | Selection / Detail / Transaction / Prompt screens |
| 3 | `panels[]` | the full screen DSL | exactly the screen you designed |
| 4 | trusted-source panel | your own HTML/JS asset | your asset, hash-pinned into the app |

**When to reach for each.** Start at 1 and move down only when you hit something it will not
do. Most apps never leave 2. Reach for 3 when a screen shows data from more than one concept at
once, or when the layout matters. Reach for 4 only when you are bringing an existing page.

```json
{
  "name": "TaskAdmin",
  "concept": "Task",
  "route": "/tasks",
  "surfaces": ["selection", "detail"],
  "selection": { "columns": ["title", "priority", "status"], "filters": ["status"] },
  "detail":    { "fields":  ["title", "priority", "status"] }
}
```

That is an `autoPanels[]` entry: a filterable list and an editable detail form, from six lines.

**Real apps:** path 1 is every sample; path 2 is `dsl-conformance-max`'s
`WidgetOrderTypedActionsWorkbench`; path 3 is `invoice-bonds-demo`'s `InvoiceConsolePanel`;
path 4 is any app declaring `metadata.trustedSourceEntrypoint`.

## 3. The three ways to get a procedure

**What it is.** A procedure is server-side logic that is not its own public endpoint — a panel
action calls it, a flow step calls it, another procedure calls it.

- **DSL steps** — a small script of declared steps (`readConcept`, `mapValue`, `patchConcept`,
  `return`). No Java, no build step of your own.
- **Trusted-source Java** — your own class, hash-pinned so the app refuses to run a modified one.
- **Pack-contributed** — a procedure a library brings with it (see §6).

**When to reach for each.** DSL steps first; they are inspectable and they survive a
regeneration. Trusted-source Java when you need something the step vocabulary does not have.

```json
{
  "name": "RenameTaskStatus",
  "steps": [
    { "name": "map-status", "type": "mapValue", "value": "$newStatus", "target": "status" },
    { "name": "done", "type": "return", "value": "$status" }
  ]
}
```

**A word about "Coda".** You may see the word in older notes. It means the author-code hook in
the generated CRUD path (`coda.allowed`), NOT a procedure. The two are unrelated and the
nickname has been dropped from the procedure docs.

**Real app:** `dsl-conformance-max` — 16 procedures covering every step type.

## 4. Flows, events and orchestrations — behaviour that reacts on its own

**What it is.** Three escalating levels of "something happens without a user clicking it".

- A **flow** is an operation with a public endpoint — multi-step, transactional, callable.
- An **event** is an announcement your app broadcasts when something happened.
- An **orchestration rule** is automation with no explicit caller: when this event arrives and
  this condition holds, do that.

**When to reach for it.** A flow when the caller waits for the answer. An event when something
else might care but the caller should not wait. An orchestration rule when the reaction belongs
to the system rather than to any one caller.

Flows also `awaitEvent` — a flow can pause, durably, until something arrives, and resume days
later across a restart.

**Real app:** `await-resume-rehearsal` — a flow that pauses on an approval and resumes.

## 5. Bonds and references — how concepts point at each other

**What it is.** A reference field is a pointer from one concept to another, and NPDev treats it
as a first-class *bond*: it drives the foreign key, the picker widget in the UI, the delete
rule, and the join in a grouped query.

```json
{ "name": "owner", "type": "reference",
  "reference": { "target": "Person", "via": "id", "displayField": "fullName",
                 "onDelete": "restrict" } }
```

`displayField` is what a human sees in the picker instead of a UUID. `onDelete` decides what
happens to this row when the target is deleted, and it is enforced in the database.

See the whole shape at once:

```sh
./npdev inspect bonds --model <model.json> --diagram bonds.html
```

**Real app:** `invoice-bonds-demo`.

## 6. Pack, context, fragment — three ways to split a model

**What it is.** Three mechanisms that take content out of one `model.json`. They are not
interchangeable.

| | `packs[]` | `contexts[]` | `fragments[]` |
|---|---|---|---|
| What it is | a distributable **Library** | an in-project **Module** | a file split |
| Namespaced | yes (`pack::X`, or an `as` alias) | yes (`context::X`) | no — flat merge |
| Versioned | semver + `npdev.lock` | no | no |
| Can live elsewhere | yes (`git+https`, `oci`) | no | no |
| Boundary enforcement | `requires` / `provides` | `imports[]`, acyclic, **compile error** | none |
| Physical DB isolation | no | `physicallyIsolate` | no |

**When to reach for each.** Organising ONE project: `contexts[]` — it is the module mechanism,
and the reason is the boundary column. A context may only reference another it has declared,
the graph must be acyclic, and a violation is a compile error rather than a convention.
Shipping something for OTHER projects: `packs[]`. Splitting a file that got long:
`fragments[]`, which is not a boundary at all.

A pack or context contributes its members under a qualifier, and references *inside* the
contribution are rewritten to match — a pack-contributed panel naming `Label` gets
`labeling::Label`, because that is what the concept is called once composed.

**Real app:** `dsl-conformance-max` — four contexts (two physically isolated) and a `labeling`
pack contributing a concept, a query, an autoPanel and a guide page.

## 7. Data lifecycle — what happens to your rows when the model changes

**What it is.** Your model changes; your database already has rows in it. `schemaLifecycle`
declares what NPDev may do about that. This is the section worth reading before you deploy
anything twice.

| `strategy` | What it does |
|---|---|
| `KeepExistingIfCompatible` | keep what is there when it still fits the model. **Start here.** |
| `DropAndRecreateOnStructureChange` | authorizes itemized column drops and type narrowings — and *only* those |
| `Ephemeral` | this app's data is disposable: every start drops the tables NPDev owns and rebuilds them |

**When to reach for `Ephemeral`.** A dev or CI app whose rows are throwaway. Never anything
whose data you would miss. It is scoped to tables the manifest says NPDev owns, so an app
sharing a database cannot take a neighbour's tables with it, and it must say so out loud:

```json
{
  "strategy": "Ephemeral",
  "allowDestructiveRecreate": true,
  "destructiveRecreateConfirmation": "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START",
  "scope": "NpdevOwnedTablesOnly"
}
```

**Renaming is not dropping-and-adding.** From a diff alone, "renamed `birthDay` to `birthDate`"
and "dropped `birthDay`, added `birthDate`" look identical, and guessing wrong destroys a
column. So a rename is DECLARED:

```json
{ "name": "birthDate", "renamedFrom": "birthDay", "type": "date" }
```

Declared that way it applies in place, with no data loss and no acknowledgment. Undeclared, it
is either refused or it costs you the column.

**A genuinely destructive change asks first.** Dropping a concept, or any change that cannot be
applied item by item, refuses the boot and prints the token that would authorize it. Run
`Build-NpdevApp.ps1 -Upgrade -PlanOnly` to see the itemized plan before you commit to it.

`npdev monitor` shows each app's posture as a DATA badge, so you can see at a glance which of
your running apps keeps its rows.

## 8. Show usage — what breaks if I change this?

**What it is.** A field is referenced from more places than it looks: panel columns and field
bindings, a query's `orderBy` and `where`, procedure steps, visibility rules. `inspect usage`
lists every one of them.

```sh
./npdev inspect usage --model <model.json> --of Patient.birthDate
./npdev inspect usage --model <model.json> --orphans
./npdev inspect usage --model <model.json> --of Patient --diagram usage.html
```

**When to reach for it.** Before renaming or removing anything, and in a pre-commit hook —
`--orphans` exits non-zero when a reference points at something that does not exist.

Each result carries the exact structural path of the reference, which is what the rename
cascade edits:

```sh
./npdev migrate rename --model <model.json> Patient.birthDay birthDate --cascade --write
```

That stamps `renamedFrom` AND rewrites every reference, at a known location rather than by
string replacement. It refuses outright — changing nothing — if any reference is one it cannot
follow, because a rename that fixes what it can see and silently leaves the rest is worse than
one that stops: it looks finished.

Three outcomes appear in the output, and the third matters. **RESOLVED**: the target exists.
**UNRESOLVED**: nothing by that name exists, and `npdev validate model` now fails on it.
**UNDECIDABLE**: it could not be worked out without running the app. That is reported, never
counted as clean — "we could not check this" and "we checked it and it was fine" are different
answers, and a tool that prints them identically is how a broken model comes to validate.

## 9. Custom capabilities — code NPDev does not generate

**What it is.** A capability is a verb your app needs but does not implement itself: send an
email, call a payment provider, score a risk. The model declares the verb; an adapter provides
it. Swap the adapter and nothing in the model changes.

**When to reach for it.** Anything that leaves the process. Declaring it as a capability keeps
it out of your flows' logic and makes it substitutable in tests.

`plugin:java-source` lets you ship the implementation as your own Java, compiled into the
generated app with its dependencies declared alongside it.

## 10. Properties and scoped settings — configuration that is not a rebuild

**What it is.** A typed setting with a resolution order. Declare the scopes most-specific-first,
and a lookup walks them until it finds a value.

```json
"propertyScopes": [
  { "name": "user", "from": "tenant" },
  { "name": "tenant" }
],
"properties": [
  { "name": "pageSize", "type": "integer", "defaultValue": 25, "settableAt": ["tenant", "user"] }
]
```

The scope with no `from` is the root and must be declared last — enforced at compile time.

**When to reach for it.** Anything an operator should be able to change without regenerating
the app. Values live in the database and are served by `GET/PUT /api/properties`, with a
generated admin page at `/properties.html`.

## Where to go next

- `docs/GETTING_STARTED.md` — validate, generate, run, change, regenerate.
- `docs/NPDEV_CONCEPTS_DEEP_DIVE.md` — the same pieces, in much more detail.
- `docs/SCHEMA_EVOLUTION.md` — the full data-lifecycle worked example.
- `docs/FLOWS.md` — the durable flow engine.
- `docs/MONITOR.md` — the Monitor and Scrap Manager.
- `npdev capabilities` and `npdev engines` — what this build actually supports, printed from
  the code so it cannot drift from it.
