# Human vs. AI Verification — a synthesis for NPDev

> **STATUS: ACTIVE.** Decision-support document, written 2026-07-29 against `beta1-vision-spine`
> (repo public, tag `beta1.2`, ledger **69 items / 0 open**, knowledge gate **18 steps green**,
> corpus **29/29 models parse**).
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\HUMAN_VS_AI_VERIFICATION.md" "D:\WorkSpace\NPDev\NPDev_General\docs\HUMAN_VS_AI_VERIFICATION.md"
> ```
>
> **Purpose.** The owner's position: *human testers are scarcer than AI agents; a buggy first
> impression burns a scarce contact; and the human testing loop is ~99% simulatable.* This document
> takes that position seriously, measures it against what this project's AI loop has actually
> produced, states precisely where the substitution holds and where it breaks, and turns the result
> into a decision rule rather than an opinion.
>
> **Short answer.** The position is right about *testing* and wrong about *what the human is for*.
> Keep the AI loop as the primary defect-finding mechanism — it is measurably better. Stop treating
> the human as a late-stage tester who must be protected from bugs, because that framing is what has
> made the item slip through every plan in the sequence.

---

# Part 0 — Executive summary

| Question | Answer |
|---|---|
| Is the AI loop a good substitute for human **testing**? | **Yes, and demonstrably better.** 69 ledger items, 0 regressions, 14 HIGH all closed |
| Is it a good substitute for human **judgment**? | **No, and not partially.** Three specific signals are structurally unobtainable |
| Is "wait until bugs are low" correct? | **Correct for a tester. Backwards for a design partner** |
| What is actually blocked today? | At least one architectural decision (**B20**) explicitly defers its own trigger to conversations that are not happening |
| What should happen next? | **One conversation, zero installs.** Then a middle-path simulation nobody has run yet |
| What is the cost of continuing as-is? | Not bugs. **Direction.** Nine plans of hardening along an axis no outsider has confirmed matters |

---

# Part 1 — What the AI loop has actually delivered

This is not a hypothetical comparison. This project has run AI-driven verification in four distinct
modes and every one produced measurable results.

## 1.1 Blind cold-start agent (REG-13 / REG-14, 2026-07-22)

**Setup.** A subagent given only a cold-start brief, a fresh context window, and its own isolated git
worktree. No access to the project's plans, register, or history. No mid-run coaching.

**Result.** Passed the Definition of Done on the **first cold run**, twice — once authoring an
issue-tracker app end-to-end and verifying all four CRUD operations over REST unaided; once building
the tutorial app from `docs/TUTORIAL_FIRST_APP.md` alone, with no MCP tools or CLI validator used to
fill gaps.

**Findings produced — three, all real, all in exactly the class a first-time user hits:**

1. The user manual's `createConcept`/`updateConcept` examples **omit the persistence
   capability/binding block**, producing a model that validates cleanly and then 500s at runtime with
   no diagnostic naming the real cause.
2. The tutorial's literal `gradlew.bat bootJar` command **fails on an undocumented RuntimeHost-libs
   staging prerequisite**, whose own suggested fix also fails standalone in a fresh worktree.
3. The docs claim HTTP **400** for an invariant violation; reality is **422**.

**Assessment.** This is exactly what a competent first-time human tester would have reported, for the
cost of one agent run, with no scheduling and no social cost. **The substitution worked.**

## 1.2 Automated external reproduction (REG-17)

**Setup.** `npdev-ci-validation.yml` — both the Linux maturity job and the Windows segmented job —
running green end-to-end on GitHub-hosted runners from a clean checkout.

**Result.** Achieved after ~11 root-caused fixes across ~9 rounds: profile/config fixes, a
JDBC-capable sample for the Postgres integration tests, surface-evidence advisory wiring, an npm
ENOENT on Windows, a deterministic runtimehost-libs sync, `@DisabledOnOs` for a Linux-container test
Windows cannot run, and an editor-E2E static-host path fix.

**The owner's own recorded call (2026-07-27):** *"the automated-repro + blind-AI-operator combination
already achieved satisfies REG-17's DoD intent; no further literal-human run is required to consider
it closed."*

**Assessment.** Correct call. "Runs on hardware this project has never touched" is a real
independence claim, and it was obtained without a human.

## 1.3 External AI adversarial review (ADR-0009, 2026-07-27)

**Setup.** Redacted review packs sent to third-party vendors (NVIDIA, Gemini), verdicts returned as
structured findings, every claim re-verified against live source before any fix.

**Result — the most interesting data point in this document:**

- **3 real, code-verified security gaps** filed (REG-48 delete-authz ordering, REG-50 Postgres
  metadata fail-open, REG-52 tenant normalization) — none hallucinated.
- **1 withdrawn as a false positive** (REG-49) — and the *reason* it was a false positive became its
  own finding (REG-51: the pack recorded no provenance, so stale generated code was reviewed against
  a fix that had landed 62 minutes later).
- The **calibration control found nothing** — 0/4 synthetic-bug missions hit their target. The real
  missions found what calibration never modelled.

**Assessment.** An external, non-human reviewer found real security defects that internal review had
not. This is the strongest single argument for the owner's position.

## 1.4 Continuous gate suite

**Current state:** 18 gate steps, 69 ledger items, **0 open**, 29/29 corpus models parsing.

**What the gates have caught on their own:** REG-17 (CI), REG-33 (Windows npm ENOENT), REG-39,
REG-62, REG-67 (a rotted calibration). Five items found by machinery with no human or agent in the
loop at all.

## 1.5 The scoreboard

| Mode | Cost | Yield | Class of finding |
|---|---|---|---|
| Blind cold-start agent | 1 run | 3 | Onboarding / documentation conformance |
| Automated external repro | ~9 CI rounds | ~11 | Environment / portability |
| External AI adversarial review | 3 missions | 3 real + 1 instructive false positive | **Security** |
| Continuous gates | ongoing | 5 | Regression / drift |
| Internal audit + building | ongoing | ~47 | Everything else |
| **Human testers** | **0** | **0** | **—** |

**Conclusion of Part 1: the owner's premise is empirically correct.** The AI loop has found 69 items
including 14 HIGH, with **zero regressions caused by prior fixes**. As a defect-finding machine it is
excellent, and a human tester added to this loop would be slower, more expensive, and less thorough.

---

# Part 2 — Where the substitution breaks, precisely

The loop the owner describes — *AI generates input artifacts → run scripts → report errors → wait for
fix → retry → report success → try a new feature → error → report → wait* — is accurate, and it **is**
simulatable. Points 1.1–1.4 prove it.

What is not simulatable is what happens **outside** that loop. Three signals, each structurally
unobtainable from an agent at any budget.

## 2.1 Abandonment — "I stopped, and here is where"

**An agent completes. A human quits.**

The blind tester *"met the pass bar on the first cold run."* It hit the persistence-binding omission —
a defect severe enough that a model validates and then 500s with no useful diagnostic — and it
**worked around it and finished.** It read the source, inferred the missing block, and completed the
task.

That competence is the problem. **The agent's ability to route around a defect masks the defect's
true cost.** A human hitting the same wall at 9pm on a Tuesday closes the tab, and never tells you.

Where someone stops, and what they were thinking when they stopped, is the single highest-value datum
in adoption. It is unobtainable internally because:

- An agent is instructed to complete; abandonment is failure, so it will not abandon.
- Even an agent instructed to "give up when frustrated" is simulating a frustration threshold you
  invented — you would be measuring your own guess.

**This is not a gap you can close with a better prompt.**

## 2.2 Domain fit — "my business does not look like your examples"

**An agent tests the domain you gave it. A human brings their own.**

REG-13's brief specified an issue-tracker. That is a shape already known to fit — concepts, fields,
CRUD, a flow. The agent succeeded because the task was drawn from inside the platform's competence.

A real user arrives with *their* domain. The interesting outcome is not a bug report; it is
**"I could not express my business in this DSL."** That finding changes the product rather than the
docs.

You cannot prompt for this, because writing the prompt requires already knowing the shape that does
not fit. **The unknown-unknown is the entire value.**

There is direct evidence this matters here: `SCREEN_TAXONOMY.md` measured that **6 of WmsOffice's 13
hand-written screens are operator consoles** — a whole screen class with no primitive, invisible
until the screens were measured as a set. That was found by internal measurement of *one* app you
already had. A second domain, from someone else, would surface the equivalent for the model layer.

## 2.3 Worth — "I would not use this, and here is what I would use instead"

**An agent executes. It never forms a preference.**

The blind tester built the app. It never asked whether NPDev was preferable to writing the app by
hand, to Retool, to Lovable, or to nothing. That judgment requires:

- a real problem with a real deadline,
- knowledge of the alternatives,
- and something at stake in the choice.

An agent has none of the three. It will complete any task framed as achievable, which makes its
success **uninformative about desirability**.

This is the signal that decides roadmap, positioning, and whether the three crown jewels (schema
evolution, durable flow engine, SDD/AI-authoring substrate) are the right three.

## 2.4 The boundary, stated as a rule

| Question | Simulatable? | Why |
|---|---|---|
| Does the documented path work? | ✅ **Yes** — proven, REG-13/14 | Conformance to a specification you wrote |
| Does it work on hardware I do not own? | ✅ **Yes** — proven, REG-17 | Environment is enumerable |
| Are there security defects? | ✅ **Yes** — proven, ADR-0009 | Code is fully observable |
| Does it stay working? | ✅ **Yes** — 18 gates | Regression is mechanical |
| **Where would someone give up?** | ❌ **No** | Requires a threshold you would have to invent |
| **Does my domain fit the DSL?** | ⚠️ **Partially** — see §5.2 | Requires a domain you did not choose |
| **Would anyone prefer this?** | ❌ **No** | Requires stakes, alternatives, and a real problem |

**The line is not "human vs AI." It is "conformance vs judgment."** Everything on the conformance
side is simulatable and this project has proven it. Nothing on the judgment side is, and no amount of
additional hardening moves an item across that line.

---

# Part 3 — Why "wait until the bugs are gone" is right and wrong at once

The owner's second point — *showing someone a broken product burns a scarce contact* — is sound risk
management. It is also **framing-dependent**, and the framing is doing all the work.

## 3.1 Two different jobs, two different economics

| | **Human as tester** | **Human as design partner** |
|---|---|---|
| Question asked | "Does it work?" | "Is this worth building?" |
| Bugs are | a cost — they waste the session | **expected** — and irrelevant to the answer |
| Best time to engage | after hardening | **now**, or earlier |
| AI substitutes? | ✅ yes, better | ❌ no |
| Cost of delay | low | **compounds weekly** |
| Failure mode | wasted contact | **building the wrong thing well** |

**Waiting is correct in the left column and backwards in the right one.** A design partner is not
evaluating polish; they are evaluating premise. A stack trace does not damage that conversation. A
wrong premise, discovered in six months, does.

## 3.2 The reputational risk is real but mis-scoped

"Bad first impression" is a **product-launch** risk. It exists when you present something as finished
and it is not.

It does not exist when you say: *"I am building this. Here is what it does and what it explicitly
does not. Would it have helped on your last project?"* That framing makes bugs irrelevant, because
you are not asking them to use anything.

**The scarce resource is not spent by asking. It is spent by asking them to install something.**

## 3.3 The cost being paid right now

`docs/ACCEPTED_BOUNDARIES.md` **B20** — the bounded-contexts / multi-namespace decision — states in
its own text that its trigger *"likely surfaces via P6.3's outreach conversations before it surfaces
via more internal measurement."*

That is a real architectural decision, scoped at **1–2 weeks of compiler and schema work**,
explicitly parked pending evidence the project has decided not to collect. It is not the only one; it
is the one written down.

**This is what the delay costs, and it is already being paid.** Not bugs — direction.

## 3.4 The pattern in the plan sequence

Across nine plans, the outreach item has appeared as: TREE-3 item 3.2 → POST_PUBLIC P2.4 → NEXT_EXECUTION P6.3 → REMEDIATION R-O2 → FINAL_OPEN_ITEMS F11 → CLOSEOUT G5 → INVOCATION_TOPOLOGY T7.

**Seven consecutive plans. Never actioned.** Each time it was correctly identified as the highest-
value open item; each time something more tractable was done instead. That is not negligence — it is
what happens when an item is filed in the same queue as engineering tasks but is not one. It has no
Definition of Done an agent can execute, so it loses every prioritisation contest to items that do.

**The fix is not more urgency. It is taking it out of the engineering queue.**

---

# Part 4 — Current project state, and what it implies

## 4.1 Where the project actually is

| Dimension | State |
|---|---|
| Ledger | 69 items, **0 open** |
| Gates | **18 steps**, all green; empty allowlists throughout |
| Corpus | **29/29** models parse; every DSL feature fixtured; conformance fixture generated in CI |
| Security | 5 findings closed (REG-48/50/51/52/53); `SECURITY.md` published; disclosure path defined |
| Repo | **Public**, Apache-2.0, tagged `beta1.2` |
| Docs | README rewritten around SDD framing; `FLOWS.md`, `UI_CONTRACT.md`, `SCREEN_TAXONOMY.md`, `CONTRIBUTING.md` all shipped |
| Regressions caused by prior fixes | **0 of 69** |
| External human users | **0** |

## 4.2 The severity trend

```
07-21   26 findings  (4 HIGH)   ← initial audit
07-22    4           (0 HIGH)
07-24    9           (2 HIGH)
07-25    9           (0 HIGH)
07-27    8           (4 HIGH)   ← first external AI review
07-28    7           (4 HIGH)   ← durable demo + first live re-verify in 3 weeks
07-29    5           (0 HIGH)   ← conformance fixture
```

Two things this shows:

1. **Volume is declining and weight is dropping.** Early findings: *"the README describes an
   unimplemented architecture," "32 unreachable panels," "no security disclosure path."* Late
   findings: *"a calibration is pinned to a moving HEAD," "step numbering is off by one."*
2. **Every HIGH spike maps to doing something new for the first time** — not to re-reading. 07-27 was
   the first real external review; 07-28 was building the durable demo and the first live
   re-verification in three weeks. **The last day, which was pure closing with no new activity,
   produced zero HIGHs.**

**The list refills when you do something you have never done before.** That is discovery, and it
terminates. The technical axis is converging, and converging fast.

## 4.3 What that implies for this decision

The hygiene argument for delay is **spent**. There is no longer a version of "wait until it is
presentable" that is honest — the repo is public, gated, documented, and has zero open items.

The remaining findings are of a class (*"a self-test for a gate has rotted; the gate still works"*)
that no outsider would ever see, let alone be put off by.

**The condition the owner set has been met.** The question is now whether the condition was the right
one.

---

# Part 5 — Near-future: what to actually do

Three actions, ordered by cost. None requires anyone to install anything except #3.

## 5.1 One conversation, zero installs — **do this first**

**Cost:** one conversation. **Risk of bad impression: zero** — nothing is demonstrated.

Pick the sharpest-fit scenario. On this project's own evidence that is a **GeneXus / legacy-4GL
shop**: WmsOffice is a real GeneXus WMS recreation, the original export is on disk, and the migration
story is the one place NPDev has a proven, differentiated answer.

Ask exactly one question:

> *"I built a platform that takes a specification and generates a complete Spring Boot application —
> schema and its migrations, REST API, authorization, and long-running business processes — as source
> you own. It does not generate custom business screens; those you write against the generated API.
> Would that have helped on your last migration, and if not, what would it have needed?"*

**What you learn regardless of the answer:**

- *No* → the most valuable information of the quarter, for the price of one conversation. Positioning
  is wrong, and you learn it before another quarter of hardening.
- *Yes, but…* → the "but" is your roadmap, sourced from outside for the first time.
- *Yes* → B20 and the bounded-contexts question get a real trigger, and the outreach item finally has
  a Definition of Done.

**Do not ask them to clone anything.** That is a different conversation, later, and it is what your
point 2 correctly protects against.

## 5.2 The middle-path simulation nobody has run

There is one part of the human signal that **is** partially simulatable, and this project has not
tried it.

Every AI verification run so far has given the agent a brief *you wrote* (REG-13's issue-tracker) or a
task *you specified*. Instead:

> Give an agent an **unfamiliar business domain you have never modelled** — and *no* NPDev-specific
> instructions beyond the public docs. Do not tell it what to build. Tell it the business, and ask it
> to produce a working app.

Candidate domains deliberately unlike anything in the corpus: a clinical trial site-visit scheduler; a
freight-forwarding customs workflow; a school timetabling system.

**What this tests that prior runs did not:** not "does the documented path work" (proven) but **"does
the DSL's shape fit a domain the author did not choose."** That is §2.2's signal, and it is the one
piece of judgment that admits partial simulation.

**Expected yield, based on this project's own history:** the last three times something new was
attempted for the first time (external review, durable demo, conformance fixture), each produced
HIGH-severity findings within a day. This is the next "first time" available, and it costs one agent
run.

## 5.3 Only then, a hands-on tester

After 5.1 and 5.2, if the premise holds, ask one person to actually build something — with the
friction log ready (`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` already exists and was moved back into
`docs/` for exactly this).

**Ask them to regenerate an app**, not just clone it. That is the path REG-63 broke — 17 models,
three weeks, found by accident — and the fastest test of whether the corpus work held for someone who
is not you.

---

# Part 6 — The decision rule, for reuse

Rather than re-litigating this per item, a rule:

> **Use an AI agent when the question has a knowable right answer. Use a human when the question is
> whether the answer is worth having.**

Applied:

| Question | Mechanism |
|---|---|
| Does the tutorial work from a cold start? | 🤖 blind agent — **proven** |
| Does it build on hardware I do not own? | 🤖 CI — **proven** |
| Are there authz/tenant/injection defects? | 🤖 external AI review — **proven** |
| Did today's change break yesterday's? | 🤖 gates — **proven** |
| Does a domain I did not choose fit the DSL? | ⚗️ **§5.2 — untried, cheap, do it** |
| Where do people give up? | 👤 human, unavoidably |
| Is the premise right? | 👤 human, unavoidably |
| Should bounded contexts exist (B20)? | 👤 human — **and it is written down as such** |

**Corollary — the queue rule:** the outreach item must not live in the same backlog as engineering
tasks. It has no DoD an agent can execute, so it loses every prioritisation contest to items that do.
Seven plans have demonstrated this. Track it separately, or it will slip an eighth time.

---

# Part 7 — Honest summary

**The owner is right about the mechanism and wrong about the role.**

Right: AI agents are a better *tester* than a human would be here — cheaper, parallel, repeatable,
and empirically productive (69 items, 14 HIGH, 0 regressions, 3 real security findings from external
review, 3 onboarding defects from a single blind run). Adding a human to that loop would slow it down
and find less.

Wrong: the human is not a tester. The human is the only available source of three signals —
**abandonment, domain fit, and worth** — and none of them requires a bug-free product. They require a
conversation.

Which means the protective instinct ("wait until it is clean") is guarding against a risk that only
exists in the framing being abandoned. The scarce contact is not spent by asking whether the premise
is right; it is spent by asking them to install something. **Those are separable, and only the second
one needs the product to be ready.**

**The technical work is converging.** Volume down, severity down, zero regressions, zero open items,
18 gates. That axis is nearly finished and the loop that got it there should keep running.

**The direction is not converging, because nothing outside this machine has ever been asked.** That
is not a bug-finding gap. It is the reason B20 sits parked and why the highest-value item has slipped
seven consecutive plans.

**One conversation. Zero installs. Then the domain simulation in §5.2.** After that, the hands-on
test, with everything already in place for it.

---

*Companions: `docs/INVOCATION_TOPOLOGY_PLAN.md` (T7) · `docs/ACCEPTED_BOUNDARIES.md` (B20, the parked
decision) · `docs/SCREEN_TAXONOMY.md` (the operator-console finding) ·
`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (ready for §5.3) ·
`docs/adr/ADR-0009-external-ai-delegation.md` (§1.3's evidence) ·
`ledger/items/REG-13.yml`, `REG-14.yml`, `REG-17.yml` (§1.1–1.2's evidence).*
