[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru,
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\root-boundaries-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$findings = @()

function Add-BoundaryFinding(
    [string]$Area,
    [string]$Path,
    [string]$Issue,
    [Nullable[int]]$LineNumber = $null,
    [string]$Line = ""
) {
    $finding = [ordered]@{
        area = $Area
        path = $Path
        issue = $Issue
    }
    if ($null -ne $LineNumber) {
        $finding.lineNumber = $LineNumber
    }
    if (-not [string]::IsNullOrWhiteSpace($Line)) {
        $finding.line = $Line.Trim()
    }
    $script:findings += [pscustomobject]$finding
}

function Find-ImportViolations(
    [string]$RelativeRoot,
    [string[]]$Includes,
    [string]$Pattern,
    [string]$Area,
    [string]$Issue
) {
    $root = Resolve-NPDevWorkspacePath $WorkspaceRoot $RelativeRoot
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        return
    }
    $files = Get-ChildItem -LiteralPath $root -Recurse -File -Include $Includes -ErrorAction SilentlyContinue | Where-Object {
        $_.FullName -notmatch "\\build\\" -and
        $_.FullName -notmatch "\\dist\\" -and
        $_.FullName -notmatch "\\node_modules\\" -and
        $_.FullName -notmatch "\\Output\\"
    }
    if (@($files).Count -eq 0) {
        return
    }
    foreach ($hit in (Select-String -LiteralPath $files.FullName -Pattern $Pattern -ErrorAction SilentlyContinue)) {
        Add-BoundaryFinding $Area (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $hit.Path) $Issue $hit.LineNumber $hit.Line
    }
}

Find-ImportViolations `
    "NPDevRuntimeHost\src" `
    @("*.java") `
    '^\s*import\s+com\.npdev\.(editor|generator)\.' `
    "runtime-import" `
    "RuntimeHost must not import editor or generator Java packages."

Find-ImportViolations `
    "NPDevContract\dsl\src" `
    @("*.java") `
    '^\s*import\s+com\.(finalexec\.|npdev\.(editor|generator|generated)\.)' `
    "contract-import" `
    "NPDevContract must not import runtime, editor, generator, or generated-app packages."

Find-ImportViolations `
    "NPDevEditor\ui-react\src" `
    @("*.ts", "*.tsx") `
    '^\s*import\s+.+from\s+[''"][^''"]*(NPDevRuntimeHost|NPDevGenerator|com/finalexec|com/npdev/generated)[^''"]*[''"]' `
    "editor-import" `
    "NPDevEditor must not import runtime-host, generator, or generated-app source directly."

$dslBuildPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\build.gradle"
Ensure-NPDevFile $dslBuildPath "NPDevContract DSL build.gradle"
$dslBuild = Get-Content -LiteralPath $dslBuildPath -Raw
if ($dslBuild.Contains("project(':core')")) {
    Add-BoundaryFinding "contract-build" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $dslBuildPath) "NPDevContract DSL must not depend on NPDevKernel core directly."
}
if (-not $dslBuild.Contains("com.tngtech.archunit:archunit-junit5")) {
    Add-BoundaryFinding "contract-build" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $dslBuildPath) "NPDevContract DSL build must include ArchUnit boundary tests."
}

$runtimeBuildPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template"
Ensure-NPDevFile $runtimeBuildPath "NPDevRuntimeHost build.gradle.template"
$runtimeBuild = Get-Content -LiteralPath $runtimeBuildPath -Raw
if (-not $runtimeBuild.Contains("com.tngtech.archunit:archunit-junit5")) {
    Add-BoundaryFinding "runtime-build" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $runtimeBuildPath) "NPDevRuntimeHost build template must include ArchUnit boundary tests."
}

$expectedBoundaryTests = @(
    "NPDevContract\dsl\src\test\java\com\npdev\dsl\v1\RootBoundaryArchUnitTest.java",
    "NPDevRuntimeHost\src\test\java\com\finalexec\RootBoundaryArchUnitTest.java"
)

foreach ($relative in $expectedBoundaryTests) {
    $path = Resolve-NPDevWorkspacePath $WorkspaceRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Add-BoundaryFinding "archunit-test" $relative "Expected ArchUnit boundary test is missing."
    }
}

$status = if ($findings.Count -eq 0) { "passed" } else { "failed" }
$result = New-NPDevCheckResult "root-boundaries" $status `
    $(if ($status -eq "passed") { "No root-boundary violations were detected." } else { "Root-boundary violations were detected." }) `
    @{ findings = $findings }

Write-NPDevJsonFile $ReportPath ([pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        workspaceRoot = $WorkspaceRoot
        overallStatus = $result.status
        result = $result
    })

if ($PassThru) {
    return $result
}
if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}

Write-NPDevWarn $result.summary
throw $result.summary
