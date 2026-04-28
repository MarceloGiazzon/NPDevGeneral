# NPDev CI Streaming Gate Fix Pack

The CI is no longer failing because of Gradle wrapper repair or Java 17.
It is timing out because `Invoke-NPDevCommandCapture` captured process output with `ReadToEnd()`,
so GitHub Actions could not see live Gradle output during long phases.

This pack:

1. patches `scripts/npdev-common.ps1` so command output is streamed live while still captured for reports;
2. removes the duplicate `Preflight NPDevGenerator gate` step from CI;
3. lengthens the heartbeat timeout now that live output is visible;
4. commits and pushes the fix.

Run:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\release\fix-ci-streaming-gate-and-push.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```
