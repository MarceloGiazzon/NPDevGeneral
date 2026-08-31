---
description: End a session safely - write the handoff so the next session resumes without re-deriving anything, then it is safe to /clear.
argument-hint: [optional note about where you are stopping]
---

Close this session out.

Compose the handoff yourself from what actually happened in this conversation —
do not ask the user to supply it, and do not re-read files to reconstruct it.
If the user added a note, it is: $ARGUMENTS

Then run:

```
pwsh -NoProfile -File scripts/ai/Close-Session.ps1 `
    -Summary  "<what this session actually did, 1-2 sentences>" `
    -NextStep "<the exact resume point - specific enough to act on with no other context>" `
    -Plan     "<path or URL to the plan being followed, if any>" `
    -Blocked  "<anything genuinely blocked, and on what - omit if nothing>" `
    -Verified "<what verification actually ran: gate names and results, or 'none'>"
```

Rules for the fields, because a vague handoff is worse than none:

- **NextStep must be actionable cold.** "Continue the roadmap" is useless.
  "Wire the PreToolUse hook into settings.json, then verify it fires by running
  a bare gradlew" is useful.
- **Verified must be honest.** If no gate ran, write `none`. Never imply
  verification that did not happen.
- **Blocked means genuinely blocked**, not "not done yet".

After it runs, show the user the `NEXT STEP` line it printed and tell them it is
safe to `/clear`. If anything is uncommitted or unpushed, say so first — the
handoff records the state but does not save the work.
