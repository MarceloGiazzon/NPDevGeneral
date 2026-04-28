# Finish NPDev Roadmap Pack - Fixed

This pack fixes the PowerShell parser issue caused by `$LASTEXITCODE:` inside a double-quoted string.

## Run

Extract this zip into:

`D:\WorkSpace\NPDev_General`

Then run:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File 'D:\WorkSpace\NPDev_General\scripts\quality\finish-npdev-roadmap.ps1' -WorkspaceRoot 'D:\WorkSpace\NPDev_General' -OutDir 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips'
```
