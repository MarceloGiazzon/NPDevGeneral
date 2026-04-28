# NPDev CI Java 17 Toolchain Fix Pack

The latest GitHub Actions run no longer hangs at Gradle wrapper repair.
It fails because Gradle requests Java 17:

`No matching toolchains found for requested specification: {languageVersion=17}`

This pack patches `.github/workflows/npdev-release-gate.yml` so CI installs both Java 17 and Java 21 and configures Gradle toolchain discovery from:

- JAVA_HOME_17_X64
- JAVA_HOME_21_X64

Run:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\release\fix-ci-java17-toolchain-and-push.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```
