# NPDev

[![NPDev CI Validation](https://github.com/MarceloGiazzon/NPDevGeneral/actions/workflows/npdev-ci-validation.yml/badge.svg?branch=main)](https://github.com/MarceloGiazzon/NPDevGeneral/actions/workflows/npdev-ci-validation.yml?query=branch%3Amain)

**You declare what your application is. NPDev builds all of it — database, REST API, admin screens,
role-based access, durable background processes — as Spring Boot source you own outright.**

One JSON file describes the things your app tracks, how they relate, who may see them, and what
happens automatically. NPDev turns that into a real Gradle/Spring Boot project — not a scaffold you
fill in, a working application.

---

## See it run

**Requires Java 17 and Python 3** — Docker is optional, only needed for the Docker run path.
`npdev doctor` checks all of it and tells you exactly what is missing.

```sh
npdev doctor                       # is this machine ready? (Java 17, Python, disk)
npdev setup                        # one-time: build NPDev's own jars locally
npdev init my-app && cd my-app     # a small, runnable app to start from
npdev dev                          # build it, run it, and watch it
```

**→ open http://localhost:8080**

Log in with the key in `SUPER_USER_KEY.txt`, written to that folder on first start.

**Four commands.** You now have list and edit screens, a REST API behind them, a database, and
authentication.

Leave `npdev dev` running. Add a field to `model.json`, save, and watch:

```
14:09:02  changed: model.json
14:09:02  validate ......................................... ok
14:09:47  ready in 45.2s   http://localhost:8080
```

The screens, the API and the database column are all there. **Get it wrong and it says so without
taking your app down** — validation runs before anything is touched.

---

## What you can build

This is the part worth reading. **Every row below is a declaration in your model file — not code
you write.**

### Data

| You write | You get |
|---|---|
| a concept with fields | table, REST API, list/create/edit screens, validation |
| **13 field types** — text, numbers, dates, enums, references, files, arrays, objects | the right column type, the right input widget, the right validation |
| a `domainType` | format, validation, widget and label defined **once**, reused everywhere |
| `{"type":"unique","fields":["isbn"]}` | a real DB constraint, server validation, and a clear error |
| an `expression` invariant | business rules enforced where they cannot be bypassed |
| a `reference` field | foreign key, joins, cascade rules, and a searchable picker in the UI |
| `extends` on a concept | inheritance — shared fields declared once |
| `derivedExpression` | computed values evaluated server-side, so every client agrees |
| `indexes` | the indexes your queries need, created and tracked |

### Behaviour

| You write | You get |
|---|---|
| a `flow` with steps | **13 step types** — call, branch, loop, create, update, emit, schedule, await, return |
| any flow | **durable execution: it survives a process restart and resumes where it stopped** |
| `awaitEvent` | a process that pauses for an approval or a webhook — for days, across deploys |
| `forEach`, including parallel | isolated iterations that each resume correctly after a crash |
| `scheduleEvent` / `schedule` | delayed and recurring work, with no separate scheduler |
| a `procedure` | reusable server-side logic with parameters and a return value |
| an `orchestrationRule` | when *this* event happens, run *that* |
| a `capability` + `binding` | name an operation, swap its implementation without touching the model |

### Screens

| You write | You get |
|---|---|
| nothing | a generated admin UI for every concept, from day one |
| a `panel` | a real screen with a route, several data sources, layout, and actions |
| `visibility` / `enabledWhen` | conditional display driven by rules, not JavaScript |
| an `action` | a button that runs a flow — including ones that stream a generated file back |
| an `aggregate` | a **master-detail-detail workbench**, saved in one transaction, with commit hooks |
| a `guidePage` | in-app guidance attached to the screen it explains |

### Access

| You write | You get |
|---|---|
| `roles` + `grants` | roles and what they may do |
| `requiredRole` | access **enforced at the API**, not just hidden in the UI |
| row-level rules | control over *which rows* each user sees |
| `sensitive` on a field | special handling in logs, traces, and exports |
| — | JWT or API-key auth, a super-user key issued on first boot, and a built-in ControlPanel for users, roles and schedules |

### Queries and reporting

| You write | You get |
|---|---|
| a named `query` | parameterised, permission-checked queries — no SQL built by hand in a controller |
| `groupBy` + `aggregates` + `having` | roll-ups (`sum`, `count`, `avg`) **joined across concepts** — dashboards without a reporting layer |
| `tracePolicy` / `auditPolicy` | tracing and auditing declared per operation instead of remembered |

### Changing it later

| You write | You get |
|---|---|
| **save the file** | `npdev dev` validates, regenerates, rebuilds and restarts — automatically |
| a new field | the schema evolves against the live database — it is not dropped and recreated |
| `renamedFrom` | the column and its contents move together |
| `conversions` | reshape existing data as part of the change: split, copy, look up, merge |
| a typo | an error naming the exact path, **and your app still running on the last good model** |
| — | destructive changes refused unless you acknowledge them explicitly |
| — | snapshots around risky migrations, and a real lock so two instances cannot migrate at once |

### Growing it

| You write | You get |
|---|---|
| `fragments` | split a large model across files |
| `packs` | reusable model modules shared between applications |
| `contexts` + `imports` | bounded contexts, so one team's `Order` is not another's |
| `propertyScopes` | configuration that cascades: global → tenant → user, in a declared order |
| — | multi-tenancy, H2 for development and PostgreSQL for production |

**Full reference: `docs/FEATURES.md`.**

---

## What you actually get

```
Java 17 · Spring Boot · Gradle · H2 (dev) / PostgreSQL (production)
REST API · generated admin UI · JWT or API-key auth · Docker Compose included
```

**A normal Gradle project.** Open it in your IDE, read it, commit it, deploy it anywhere you deploy
Spring Boot. **If you stopped using NPDev tomorrow, your application would keep working.**

---

## Changing your mind is the normal case

Add a field to your model, regenerate, and the app follows — the screens, the API, and the database
schema. Rename something and say so with `renamedFrom`, and it is understood as a rename.

Most business applications are wrong three times before they are right. **NPDev is built for the
third version, not the first.**

---

## Who this is for

**A good fit for** internal tools, admin systems, operations back-offices, line-of-business
apps — anything with real entities, real rules, real users, and a schema that will change a dozen
times before it settles.

**Also a good fit if you want an AI to write it.** The model is JSON with a strict schema and a
validator that returns typed, machine-readable errors, so an agent can author, check its own work,
and correct itself. See `docs/AUTHORING_WITH_AI.md`.

**Not a good fit if you want** a bespoke consumer-facing UI (NPDev generates a functional admin
interface, not a designed product surface), a microservice generator (one model is one deployable
app), or frozen APIs today.

---

## Honest limitations

- **Custom screens are hand-written.** You get a working admin UI plus declarable panels and
  workbenches; a polished, bespoke frontend is built against the generated REST API like any other
  client.
- **One model is one deployable app.** Bounded contexts exist inside a model; this is not a
  microservice generator.
- **The app restarts to pick up a model change.** `npdev dev` makes that automatic and
  reports it, but it is a restart, not hot reload.
- **Pre-1.0 and deliberately unstable.** See below.

The current, complete list of designed limits is `docs/ACCEPTED_BOUNDARIES.md` — kept accurate on
purpose, because a stale limitations page costs more trust than a short feature list.

---

## The CLI

```sh
./npdev doctor                    # check this machine
./npdev setup                     # build NPDev's jars locally (once per clone)
./npdev init <name>               # scaffold a new model, with git history
./npdev dev                       # watch the model; rebuild + restart on save
./npdev run app                   # generate, build, boot, health-check (one shot)
./npdev validate model <path>     # full structural + semantic check, no generation
./npdev generate app --model <m> --config <c> --output <dir>
./npdev mcp install               # connect an AI tool to NPDev
```

`npdev --help` describes every command. **Windows:** `npdev.bat`, same arguments.

For AI-authored models, `./npdev normalize ai-model <path>` rewrites a draft into canonical form
before validation.

---

## Stability policy (pre-1.0)

NPDev is pre-1.0 and **deliberately unstable**. The model DSL, generated code layout, and internal
APIs will change without deprecation cycles.

**This is a design position, not an apology.** NPDev models are meant to be machine-authored — an
agent writes them from your specification. **A breaking DSL change costs one regeneration, not a
migration project.** We would rather fix a design mistake than carry it for a decade.

Every breaking change ships with an `npdev migrate` codemod that rewrites existing models
automatically, a one-line entry in `BREAKING.md`, and the reason it was worth breaking.

**If you need frozen APIs today, NPDev is not ready for you.** We freeze at 1.0, and not one release
before.

---

## Where to go next

| If you want to… | Go to |
|---|---|
| **build your own app** | `docs/YOUR_FIRST_APP.md` — about 15 minutes |
| **have an AI write the model** | `docs/AUTHORING_WITH_AI.md` |
| **see everything it can do** | `docs/FEATURES.md` |
| understand the concepts | `docs/NPDEV_CONCEPTS_DEEP_DIVE.md` |
| deploy properly (Postgres, Docker, env vars) | `docs/DEPLOYMENT.md` |
| know how schema changes work | `docs/DATABASES_AND_MIGRATIONS.md` |
| know what NPDev **won't** do | `docs/ACCEPTED_BOUNDARIES.md` |
| fix a broken setup | `npdev doctor`, then `docs/GETTING_STARTED.md` |
| see where the platform is heading | `docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md` |

---

## License and status

Apache-2.0 (see `LICENSE`). Pre-1.0; the git tag (e.g. `beta1.4`) is the authoritative release
version. Two other numbers exist and mean something narrower: `npdev --version` reports the
portable CLI wrapper's own version, and the Gradle module version pinned in each module's
`build.gradle` (e.g. `NPDevContract/dsl/build.gradle`) is an internal JAR-artifact version, not the
platform's. Separately, every model declares `"dslVersion": "1.0.0"` — that is the *model JSON
format* version, unrelated to any of the above; it has never changed, including across the
"DSL 2.0" flowStep-vocabulary change (`BREAKING.md`).

`beta1.7` is cut exactly at `main`'s current head — no drift to record. (Superseded `beta1.5`'s and
`beta1.6`'s own drift notes, each resolved the same way: cut the next tag fresh at `main` rather
than move a published one.)

**`main` is the sole working branch** (as of 2026-08-07, REG-139/I2) — all work lands directly on
`main`; there is no separate release/working branch to keep in sync. The `beta1-vision-spine`
branch this repo used earlier in beta1 has been deleted (both locally and on `origin`) after
sitting unused, 6 commits behind `main`, for several sessions' worth of work that had already moved
to `main` directly. See `docs/RELEASE_PROCESS.md`'s "Merge cadence" section for why a drifting
second branch was a real, previously-recurring problem (150 and then 71 commits of drift), and why
collapsing to one branch removes that failure mode by construction.
