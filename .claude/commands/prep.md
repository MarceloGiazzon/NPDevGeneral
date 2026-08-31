---
description: Start a session - show the last handoff, repo state, open ledger items, and refresh the AI indexes.
allowed-tools: Bash(pwsh -NoProfile -File scripts/ai/Prepare-Session.ps1:*)
---

Session prep output:

!`pwsh -NoProfile -File scripts/ai/Prepare-Session.ps1`

Read the output above. It is the entire orientation for this session — do not
re-derive any of it with further tool calls.

If a `NEXT` line is present, that is the previous session's stated resume point
and it is the DEFAULT ACTION: say in one sentence what you are starting, then
start it. Do not ask whether to proceed. Stop and ask only if the user has
already said what they want to work on, if `NEXT` is ambiguous, or if carrying
it out would be destructive or hard to reverse.

Check first whether `NEXT` is already done — a session usually keeps working
after it writes its handoff, so the resume point can already be committed. If it
is done, say so in one line and go on to the obvious next thing instead of
redoing it.

If the handoff is more than ~48h old, or names a commit that is no longer near
`HEAD`, say so plainly rather than trusting it.
