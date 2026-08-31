---
description: Start a session - show the last handoff, repo state, open ledger items, and refresh the AI indexes.
allowed-tools: Bash(pwsh -NoProfile -File scripts/ai/Prepare-Session.ps1:*)
---

Session prep output:

!`pwsh -NoProfile -File scripts/ai/Prepare-Session.ps1`

Read the output above. It is the entire orientation for this session — do not
re-derive any of it with further tool calls.

If a `NEXT` line is present, that is the previous session's stated resume point.
Confirm it back to the user in one sentence and ask whether to proceed with it,
unless they have already said what they want to work on.

If the handoff is more than ~48h old, or names a commit that is no longer near
`HEAD`, say so plainly rather than trusting it.
