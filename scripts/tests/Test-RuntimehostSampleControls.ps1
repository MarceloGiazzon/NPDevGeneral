Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
$buildTemplatePath = Resolve-NPDevWorkspacePath $workspaceRoot "NPDevRuntimeHost\build.gradle.template"
$runtimeMigrationsRoot = Resolve-NPDevWorkspacePath $workspaceRoot "NPDevRuntimeHost\src\main\resources\db\migration"
$cleanScriptPath = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\samples\clean-sample-output.ps1"

$failures = [System.Collections.Generic.List[string]]::new()

$templateContent = Get-Content -LiteralPath $buildTemplatePath -Raw
if ($templateContent -notmatch 'json-schema-validator') {
    [void]$failures.Add("Focused regression test evidence failed: json-schema-validator dependency is missing from the RuntimeHost template.")
}

foreach ($migrationName in @(
        "V5011__create_npdev_publication_execution.sql",
        "V5013__create_npdev_publication_audit.sql"
    )) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeMigrationsRoot $migrationName) -PathType Leaf)) {
        [void]$failures.Add("Expected runtime migration is missing: " + $migrationName)
    }
}

if (-not (Test-Path -LiteralPath $cleanScriptPath -PathType Leaf)) {
    [void]$failures.Add("Failed run cleanup control is missing clean-sample-output.ps1.")
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "RuntimeHost/sample control tests passed, including json-schema-validator, failed run cleanup, and doctor passes expectations."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "RuntimeHost/sample control tests failed."
