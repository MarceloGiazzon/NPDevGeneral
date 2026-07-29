# NPDev — the pitch

> Written for `docs/POST_PUBLIC_PLAN.md` P2.4 ("tell 3 specific people"). Use this as a starting
> point for a DM, forum post, or conversation opener — not as a doc to link to verbatim.

**The one-line version:** you write a specification in domain language; NPDev derives a complete,
deterministic Spring Boot system from it — schema, migrations, REST API, authorization, and
long-running business processes — as source you own outright, not a scaffold you fill in.

**Why it's not just another CRUD generator:**

1. **Schema evolution that preserves data.** Rename a field, split a concept, narrow a type — NPDev
   diffs the new model against the live database and emits a migration plan, not a
   drop-and-recreate. Most generators only handle the first `CREATE TABLE`; this one handles the
   fifth model change against a database with real rows in it.

2. **A durable workflow engine.** A flow can pause on an event — an approval, a webhook, a reply —
   and survive a process restart while it waits, resuming exactly where it left off. A failure
   mid-flow compensates already-completed steps (a saga, not a rollback), and that compensation
   itself survives a crash mid-unwind. This is the category Temporal and Camunda charge for,
   embedded in the generator.

3. **A spec built for AI authoring, not retrofitted for it.** The model is a JSON document with a
   JSON Schema and a semantic validator that rejects an invalid spec before anything generates —
   structured feedback for an LLM authoring a model directly, instead of a runtime crash three
   steps later.

**The honest limitation, upfront:** custom business screens are hand-written against the generated
REST API. NPDev generates a working generic admin UI, not a bespoke frontend — if your evaluator
cares primarily about a polished custom UI out of the box, say so before they find it themselves.

**Who this is likely to resonate with:**
- Teams maintaining a legacy 4GL/low-code platform (GeneXus and similar) who recognize the
  schema-evolution and generated-CRUD shape, and want to know what a modern equivalent looks like.
- Internal-tools teams who write the same CRUD-plus-approval-workflow app repeatedly and are tired
  of rebuilding the migration and authorization plumbing each time.
- Someone skeptical of AI app-builders who has noticed none of them handle durable state or schema
  evolution — this is the argument for why that gap is hard, not incidental.

**Where to point them:** `README.md` (front door + quickstart), `docs/FLOWS.md` (the workflow
engine in depth), `docs/DATABASES_AND_MIGRATIONS.md` (schema evolution in depth).

**After the conversation:** record what they hit in the first hour —
`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (restored to `docs/` 2026-07-29, docs/REMEDIATION_PLAN.md
R-O2 — it had been archived alongside its own DoD's programme history, but this is a reusable
template, not a historical record).
