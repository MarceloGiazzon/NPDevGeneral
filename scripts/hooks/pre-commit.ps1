Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (& git rev-parse --show-toplevel).Trim()
Push-Location $repoRoot
try {
    pwsh -File (Join-Path $repoRoot "scripts\hygiene\Test-WorkspaceSlimness.ps1")
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "Commit blocked: workspace exceeds the slimness policy (docs\WORKSPACE_CLEANUP_POLICY.md)." -ForegroundColor Red
        Write-Host "Run: pwsh -File scripts\hygiene\clean-workspace-state.ps1" -ForegroundColor Yellow
        exit 1
    }
}
finally {
    Pop-Location
}
