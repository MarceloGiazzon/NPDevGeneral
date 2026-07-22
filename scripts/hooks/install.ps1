Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (& git rev-parse --show-toplevel).Trim()
$hooksDir = Join-Path $repoRoot ".git\hooks"
$target = Join-Path $hooksDir "pre-commit"

$shim = @"
#!/bin/sh
pwsh -File "scripts/hooks/pre-commit.ps1"
exit `$?
"@

Set-Content -LiteralPath $target -Value $shim -NoNewline:$false -Encoding ascii
Write-Host "Installed pre-commit hook at $target"
