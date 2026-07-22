param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "runtime-null-context-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$sourcePath = Join-Path $workspaceRoot "NPDevRuntimeHost/src/main/java/com/finalexec/execution/DirectExecutionGateway.java"
if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "DirectExecutionGateway.java is missing."
}

$source = Get-Content -Raw -LiteralPath $sourcePath
$failures = [System.Collections.Generic.List[string]]::new()

if ($source -notmatch "ExecutionContext effectiveRequesterContext = requesterContext == null \? ExecutionContext\.anonymous\(\) : requesterContext;") {
    $failures.Add("execute must normalize null requesterContext to ExecutionContext.anonymous().") | Out-Null
}

if ($source -match "ExecutionContext effectiveContext = requesterContext\s*[\r\n]+\s*\.withTag") {
    $failures.Add("execute must not dereference requesterContext directly when building tags.") | Out-Null
}

if ($source -notmatch "buildRejectedRecord\([\s\S]*?effectiveRequesterContext") {
    $failures.Add("rejected direct-execution records must receive the normalized requester context.") | Out-Null
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-runtime-null-context-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-runtime-null-context-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
    target = "NPDevRuntimeHost/src/main/java/com/finalexec/execution/DirectExecutionGateway.java"
    assertions = @(
        "null requesterContext normalizes to ExecutionContext.anonymous",
        "execute does not dereference requesterContext directly before tagging",
        "rejection records use the normalized requester context"
    )
    failures = @($failures)
}

$reportPath = "scripts/reports/out/runtime-null-context-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8

if ($failures.Count -eq 0) {
    Write-Host ("Runtime null-context tests passed. Report: " + $reportPath)
    exit 0
}

Write-Error ("Runtime null-context tests failed. Report: " + $reportPath)
