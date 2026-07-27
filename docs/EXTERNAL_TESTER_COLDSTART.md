# NPDev — External Tester Cold-Start Kit (REG-13 / REG-14 / REG-17)

> **Purpose.** A self-contained brief to hand an **independent, project-blind agent** (a fresh AI
> tool, or a person who has never seen NPDev) so it can run the three usability/reproduction tests
> that have never been done by anyone outside the project. Copy the "Brief to paste" section below
> verbatim into a *fresh* agent session — one with **no** prior NPDev context.
>
> **Why independence is the whole point.** Every app NPDev has produced was built by the author or an
> AI they supervised. The claim "a non-engineer / a stranger can author an app from the docs" is
> entirely unvalidated. The *friction* the outsider hits **is the result** — do not coach them, do not
> pre-load them with project knowledge, do not let them ask the author for help. A warmed-up agent
> that already knows the project proves nothing.

---

## What this closes

| Item | The claim being tested | Definition of Done |
|---|---|---|
| **REG-13** (LNCH-18) | A non-author can take an app from description → running FinalApp using the MCP toolbox + docs. | A friction log from an independent agent that authored an app it was never shown how to build. |
| **REG-14** (LNCH-22) | A newcomer can build the tutorial app from `docs/` **alone** (no AI assistance). | A friction log from following `docs/TUTORIAL_FIRST_APP.md` + `docs/DSL_REFERENCE.md` with no help. |
| **REG-17** | A third party can reproduce the verification on hardware the author doesn't control. | The agent clones, builds, and runs the quality gates on its own machine (Linux ideally), logging every question it had to ask. **Now unblocked** — CI is green (REG-10) and the build is cross-platform (REG-11), so a clean clone can build. |

**Run all three in one session with the same independent agent** — they share setup and the friction
log format. Do not merge them with a project-aware session.

---

## Materials to give the agent (and nothing else)

Point the agent at the repo and these files **only** — resist adding context:

- `docs/TUTORIAL_FIRST_APP.md` — the guided first-app tutorial (REG-14's subject).
- `docs/DSL_REFERENCE.md` — the model DSL reference (generated from schema, drift-checked).
- `docs/GETTING_STARTED.md` — orientation.
- The MCP toolbox (`NPDevMcp` server: `npdev_search_examples`, `npdev_search_fix`,
  `npdev_check_support`, the validate→fix→generate loop) — for REG-13's AI-authoring path.
- `docs/archive/programme-history/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` — the structured friction log to fill in.

**Do NOT give them:** this file, the open-items register, the LNCH-1 plans, the retrospective, or any
"here's how it really works" summary. If they can't find it in the four docs above + the tool output,
that gap **is** a finding.

---

## Brief to paste into the fresh agent

> You are evaluating a low-code platform called NPDev that you have never seen. Your job is to be an
> honest first-time user and record exactly where you get stuck — the friction is the deliverable, not
> a working app. Do three things, in order, and keep a running friction log using the template at
> `docs/archive/programme-history/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (one entry per point of confusion, dead end, or
> assumption you had to make):
>
> **Task A — Author an app with the tools (REG-13).** Using only the NPDev MCP tools and the docs in
> `docs/`, take this app from description to a running FinalApp: *"a simple issue tracker — issues have
> a title, description, status (open/closed), and an assignee; users can create, list, edit, and close
> issues."* Do not read the platform's source, plans, or internal notes. When something is unclear, log
> it and make your best guess rather than asking anyone.
>
> **Task B — Build the tutorial from docs alone (REG-14).** Follow `docs/TUTORIAL_FIRST_APP.md` and
> `docs/DSL_REFERENCE.md` to build the tutorial app **without** using the MCP tools to fill gaps — docs
> only. Log every place the docs were ambiguous, wrong, out of date, or assumed knowledge you didn't
> have.
>
> **Task C — Reproduce the verification (REG-17).** Clone the repo fresh onto your own machine
> (Linux/macOS if you have it — that's the real test), and get the quality gates to run. Start from
> the docs; log every question you had to ask, every command that didn't work first try, every
> platform assumption you hit. You do NOT need every gate green — you need an honest record of what a
> stranger on unknown hardware runs into.
>
> Deliver: the three friction logs. Do not summarize them as "it went fine" — specific friction points
> only. If a task was genuinely frictionless end to end, say exactly which steps and why.

---

## After the run

Feed the friction logs back as **new, dated findings** in `docs/LAUNCH_READINESS_GAPS.md` (and the
register if they rise to real bugs) — do not silently fix them mid-session; the record of what a
stranger hit is the value. Then REG-13/REG-14/REG-17 can move from "never done" to DONE with evidence.

*Companion: `docs/archive/programme-history/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (the log format) ·
`docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.5/§2.6/§3.2 (the items this closes).*
