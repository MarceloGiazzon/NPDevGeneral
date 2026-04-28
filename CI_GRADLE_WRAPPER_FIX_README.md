# NPDev CI Gradle Wrapper Fix Pack

This pack fixes the first GitHub Actions hang where the beta gate stayed in the doctor Gradle-wrapper repair step.

It does three things:

1. Adds `.gitignore` exceptions so Gradle wrapper jars/scripts can be tracked.
2. Ensures and force-adds Gradle wrapper artifacts for:
   - NPDevContract/dsl
   - NPDevEditor
   - NPDevGenerator
   - NPDevKernel
   - NPDevRuntimeHost
3. Patches `.github/workflows/npdev-release-gate.yml`:
   - adds a Gradle wrapper preflight step
   - adds timeout-minutes: 90 to the full beta gate step

Run:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\release\fix-ci-gradle-wrapper-and-push.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```
