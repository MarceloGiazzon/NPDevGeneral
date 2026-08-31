---
description: Show the last few session handoffs - what was done, what came next, and whether it actually happened.
allowed-tools: Bash(pwsh -NoProfile -Command:*)
---

Recent session handoffs:

!`pwsh -NoProfile -Command "$p='D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\session-state\history.jsonl'; if (Test-Path $p) { Get-Content $p | Select-Object -Last 8 | ForEach-Object { $r=$_|ConvertFrom-Json; '{0}  {1}' -f $r.closedAt, $r.head; '   did   {0}' -f $r.summary; '   NEXT  {0}' -f $r.nextStep; if ($r.blocked) { '   BLK   {0}' -f $r.blocked }; '' } } else { 'No handoff history yet - run /close at the end of a session to start it.' }"`

Use this when the thread has been lost, or to see whether a stated NEXT step was
actually the thing that happened in the following session. Report what you see;
do not go looking through git history to cross-check unless asked.
