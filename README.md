# NPDev

**NPDev is spec-driven development for business applications.** You write a specification in
domain language; NPDev derives a complete, deterministic system — database schema and its
migrations, REST API, authorization, and long-running business processes — as Spring Boot source
you own outright.

Concretely: you author a JSON model (concepts, fields, flows, panels, authorization rules), and
`npdev generate app` produces a real Gradle/Spring Boot project — not a scaffold you fill in, a
working application with a database schema, a REST API, row-level authorization, and a generic
admin UI already wired up. You then build and run it like any other Spring Boot app.

## What it generates

| Layer | Generated |
|---|---|
| Entities, persistence, REST API, OpenAPI | ✅ |
| Schema migrations + evolution planning | ✅ |
| Row-level authorization, tenant isolation | ✅ |
| Durable flows, events, orchestration | ✅ |
| Auth, JWT, password reset, ControlPanel | ✅ |
| Docker + compose + Caddy | ✅ |
| Generic CRUD admin UI | ✅ |
| **Custom business screens** | ❌ **hand-written against the generated API** |

## Three things this does that most CRUD generators don't

**Schema evolution that preserves data.** Change your model — rename a field, split a concept,
narrow a type — and NPDev diffs the new shape against the live database and emits a migration
plan, not a drop-and-recreate. Renames need to be declared (a rename and a drop+add look
identical in a pure shape diff), but a declared rename keeps the data. See
`docs/DATABASES_AND_MIGRATIONS.md`.

**A durable workflow engine.** A flow can pause on `awaitEvent` — waiting for an approval, a
webhook, a reply — and survive a JVM restart while it waits; a durable suspend/resume mechanism
finds it and continues exactly where it left off, days later if needed. A failure mid-flow
compensates already-completed steps (a saga, not a rollback), and that compensation itself
survives a crash mid-unwind. See `docs/FLOWS.md`.

**AI-authored specs against a schema-constrained validator.** The model is a JSON document with a
JSON Schema and a semantic validator that rejects an invalid spec before anything is generated —
which makes it a tractable target for an LLM to author directly, with real, structured feedback
instead of a runtime crash three steps later. See `docs/ai/AI_KNOWLEDGE_LOOP_AND_TOOLING_PLAN.md`.

## Quickstart

Requires Java 17 and (for the Docker path) Docker. Clone the repo, then from its root:

```sh
# Validate a real, checked-in sample model (full structural + semantic check by default;
# pass --structural-only for a fast JSON-Schema-only check with no Gradle invocation)
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json

# Generate a complete Spring Boot app from it
./npdev generate app \
  --model NPDevContract/dsl/resources/Models/canonical-demo/model.json \
  --config NPDevContract/dsl/resources/Models/canonical-demo/config.json \
  --output /path/outside/this/repo/canonical-demo-app
```

(On Windows, use `npdev.bat` with the same arguments.) The output directory is a complete,
buildable Spring Boot project — a `docker-compose.yml` (with an optional Caddy TLS-terminating
`proxy` profile) is generated alongside it. To run it:

```sh
cd /path/outside/this/repo/canonical-demo-app
cp .env.example .env    # set NPDEV_AUTH_APIKEYS at minimum
docker compose up
```

Full deployment options (Postgres-first production path, env-var reference, the mail-catcher
profile) are in `docs/DEPLOYMENT.md`. `docs/GETTING_STARTED.md` covers the portable CLI in more
depth (`./npdev normalize ai-model`, `./npdev report bootstrap`); `docs/NPDEV_CONCEPTS_DEEP_DIVE.md`
is the author-facing tour of concepts, flows, capabilities, panels, and events.

## Honest limitations

- **Custom business screens are hand-written.** NPDev generates a generic CRUD admin UI against
  the REST API it also generates; a polished, bespoke frontend is not generated and has to be
  built against that API like any other client.
- **One bounded context per model.** A single JSON model compiles to a single deployable app; it
  is not a multi-service/microservice generator.
- **Pre-1.0 and deliberately unstable.** See "Stability policy" below and `BREAKING.md`.
- **Windows-first tooling.** Development scripts (`scripts/**/*.ps1`) assume PowerShell; the
  portable `npdev`/`npdev.bat` CLI and CI both also run on Linux, but the day-to-day maintainer
  workflow is Windows-first today.

## Stability policy (pre-1.0)

NPDev is pre-1.0 and **deliberately unstable**. The model DSL, generated code layout, and internal
APIs will change without deprecation cycles.

We do this on purpose. NPDev models are machine-authored — an agent writes them from your
specification. A breaking DSL change costs one regeneration, not a migration project. We would
rather fix a design mistake than carry it for a decade.

Every breaking change ships with:
  • a `npdev migrate` codemod that rewrites existing models automatically
  • a one-line entry in `BREAKING.md`
  • the reason it was worth breaking

If you need frozen APIs today, NPDev is not ready for you yet. We will freeze at 1.0, and not one
release before.

## License and status

Apache-2.0 (see `LICENSE`). Pre-1.0; the git tag (e.g. `beta1.4`) is the authoritative release
version. Two other numbers exist and mean something narrower: `npdev --version` reports the
portable CLI wrapper's own version, and the Gradle module version pinned in each module's
`build.gradle` (e.g. `NPDevContract/dsl/build.gradle`) is an internal JAR-artifact version, not the
platform's. Separately, every model declares `"dslVersion": "1.0.0"` — that is the *model JSON
format* version, unrelated to any of the above; it has never changed, including across the
"DSL 2.0" flowStep-vocabulary change (`BREAKING.md`).

## Future direction: Box/Object/Truth

The platform's longer-term architectural direction — Boxes as the unit of structure/ownership/
release, code-bearing Panel/Procedure Objects, and a T0–T6 truth-classification ladder — is
described in `docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md` (see also
`docs/adr/ADR-0002-box-object-truth-model.md`, `docs/adr/ADR-0003-code-bearing-panel-procedure-objects.md`).

**Status, precisely:** the Box/Object hierarchy itself (`Application Box` → `Module Box` →
`Entity Box`/`Rule Box`/`Evidence Box`, code-bearing Panel/Procedure Objects) is **not
implemented** — `"box"` appears zero times in the model schema. The **truth** half is partially
real today, independent of Boxes: a `T0`–`T6` truth-level ladder exists on every concept, a bond
(concept-to-concept reference) pointing at a less-true concept raises a warning at authoring time,
and a release-gate validator hard-blocks promoting a concept whose reachable dependencies haven't
earned the required truth level yet (`NPDevContract/docs/BONDS.md`, Phase 6). What's not built yet
is the Box hierarchy that would organize this platform-wide, and code-bearing Panel/Procedure
Objects as the primary authored surface — both are the vision doc's subject, not this README's.
