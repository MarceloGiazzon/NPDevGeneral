# NPDev — the pitch

> Written for `docs/archive/programme-history/POST_PUBLIC_PLAN.md` P2.4 ("tell 3 specific people"). Use this as a starting
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

**The honest limitation, upfront:** NPDev does not design your brand. It generates a working,
themable UI (lists, forms, workbenches, dashboards, reports). A polished, customer-facing screen is
one you write as a hand-made HTML page, which runs inside the app's shell or publicly with no
sign-in, calls the same API, and survives every regeneration. If your evaluator expects a designed
custom UI out of the box, say so before they find it themselves, and show them the hand-made page
path (`docs/reference/HAND_MADE_PAGES.md`) in the same breath.

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
`docs/archive/programme-history/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (restored to `docs/` on 2026-07-29 as a reusable
template and since re-archived with its programme history).
