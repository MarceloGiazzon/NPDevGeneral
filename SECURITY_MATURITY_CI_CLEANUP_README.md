# NPDev Security Maturity CI Cleanup Pack

Purpose:
- Create `scripts/quality/run-security-hardening-maturity.ps1`.
- Generate `scripts/reports/out/security-hardening-maturity-report.json`.
- Add CI steps before RuntimeHost gate:
  - Security hardening maturity evidence
  - Runtime security consistency evidence

This cleans up the secondary failed `runtime-security-consistency-report.json`
inside the CI evidence artifact.

Run:
```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\release\apply-security-maturity-ci-cleanup.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```
