param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "doc-entrypoint-validation-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$docsToCheck = @(
    "README.md",
    "PROJECT_DIGEST.md",
    "docs/beta/ai-only-beta-0-closure-checklist.md",
    "docs/beta/ai-only-beta-0-release-runbook.md",
    "docs/beta/ai-only-beta-0-no-false-green-scope.md",
    "docs/beta/ai-only-beta-0-runbook.md"
)

$failures = [System.Collections.Generic.List[string]]::new()
$entrypoints = @()
foreach ($docPath in $docsToCheck) {
    if (-not (Test-Path -LiteralPath $docPath -PathType Leaf)) {
        continue
    }
    $text = Get-Content -Raw -LiteralPath $docPath
    $matches = [regex]::Matches($text, "scripts[\\/][A-Za-z0-9_./\\-]+\.ps1")
    foreach ($match in $matches) {
        $path = ([string]$match.Value) -replace "\\", "/"
        $exists = Test-Path -LiteralPath $path -PathType Leaf
        if (-not $exists) {
            $failures.Add("$docPath references missing entrypoint $path") | Out-Null
        }
        $entrypoints += [pscustomobject]@{
            document = $docPath
            path = $path
            exists = $exists
        }
    }
}

$status = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-doc-entrypoint-validation-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-doc-entrypoint-validation.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $status
    entrypoints = $entrypoints
    failures = @($failures)
}

$reportPath = "scripts/reports/out/doc-entrypoint-validation-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8

if ($status -eq "passed") {
    Write-Host ("Doc entrypoint validation passed. Report: " + $reportPath)
    exit 0
}

Write-Error ("Doc entrypoint validation failed. Report: " + $reportPath)
