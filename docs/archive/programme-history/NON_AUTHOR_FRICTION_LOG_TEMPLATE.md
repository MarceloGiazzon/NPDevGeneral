# Non-author friction log (LNCH-18 DoD)

Use this to run and record the test `docs/adr/ADR-0006-authoring-path.md`'s DoD actually asks
for: **someone who is not Marcelo and not an AI assistant session** builds a real app through the
chosen authoring path (the MCP/CLI validate→fix→generate loop) and reports exactly where they got
stuck. The output of running this once is a filled-in copy of this file — that filled-in copy
*is* the next roadmap increment's input, per the DoD's own wording.

## Before you start

- **Who should do this**: someone who has never used NPDev, ideally someone who isn't a
  professional software engineer either (or at least hasn't seen this codebase). The whole point
  is testing whether a stranger succeeds, not whether an expert can find the light switch.
- **What they get**: nothing but `docs/TUTORIAL_FIRST_APP.md`, `docs/DSL_REFERENCE.md`, and access
  to an AI assistant with the NPDev MCP tools configured. No live coaching from Marcelo or from
  whoever set this test up — if they ask a question, write down that they asked it and what the
  honest answer was, but let the friction show.
- **What "done" looks like**: a running FinalApp they can hit with a browser or curl and see their
  own described behavior actually happen. Not "the model validated" — the DoD says "running,
  verified."

## Session log

**Tester** (first name / role is enough, no need for full identity): _______________
**Date**: _______________
**What they were asked to build** (one or two sentences, their own words if possible):

```
_______________________________________________________
```

### Timeline

Fill in a row every time something notable happens — a wrong turn, a confusing error, a moment of
"oh, that actually worked," a question asked. Timestamps are optional but help show where time
actually went.

| Time / step # | What they tried | What happened | Confusing / smooth? |
|---|---|---|---|
| | | | |
| | | | |
| | | | |

### Did they reach a running, verified app?

- [ ] Yes — describe how they confirmed it actually worked (curl output, browser screenshot, etc.):
- [ ] No — describe where it broke down and what would have unblocked them:

### Specific friction points (fill in as many as apply)

1. **First real confusion.** What was the first moment they didn't know what to do next?
2. **Worst error message encountered.** Paste it verbatim. Did it have a code/suggested fix? Was
   the fix actually right?
3. **Anything in `docs/TUTORIAL_FIRST_APP.md` that was wrong, missing, or assumed knowledge they
   didn't have?**
4. **Did the AI (via MCP tools) ever generate something that looked plausible but was actually
   broken?** If so, what, and how did they (or didn't they) catch it?
5. **What would have saved them the most time?**

### Tester's own words

Ask them directly: "If you were describing this to a friend, what was the experience like?" Write
their answer close to verbatim, don't polish it.

```
_______________________________________________________
```

## After the session

- File every distinct friction point above as its own entry — either a `knowledge/cards/*.json`
  card (if it's a durable platform finding) or a tracked issue (if it's a concrete bug/gap).
- If the tutorial doc itself was wrong or confusing, fix `docs/TUTORIAL_FIRST_APP.md` directly —
  that's the cheapest, highest-leverage fix available from a single run of this test.
- Update `docs/adr/ADR-0006-authoring-path.md`'s DoD section to link to this filled-in log and
  mark the DoD satisfied (or not, with what's still missing) — don't leave it silently ambiguous.
