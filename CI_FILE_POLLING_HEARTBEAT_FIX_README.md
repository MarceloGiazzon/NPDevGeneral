# NPDev Robust CI File-Polling Heartbeat Fix

This replaces the failing async event-handler wrapper with a file-polling wrapper.

Why:
- GitHub Actions failed with "There is no Runspace available" when async PowerShell event handlers tried to write output.
- The new wrapper uses only Start-Process with stdout/stderr redirected to files.
- It polls those files and emits heartbeat lines. No async handlers. No runspace dependency.

Run:
```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\release\fix-ci-file-polling-heartbeat-and-push.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```
