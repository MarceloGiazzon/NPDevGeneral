[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$StateZipRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "roadmap-closure-check"

if ([string]::IsNullOrWhiteSpace($StateZipRoot)) {
    $StateZipRoot = Get-DefaultStateZipOutDir -WorkspaceRoot $WorkspaceRoot
}
else {
    $StateZipRoot = Normalize-NPDevPath $StateZipRoot
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\roadmap-closure-check-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$checks = [System.Collections.Generic.List[object]]::new()

function Add-ClosureCheck {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Summary,
        [object]$Data = $null
    )

    [void]$checks.Add((New-NPDevCheckResult $Name $(if ($Passed) { "passed" } else { "failed" }) $Summary $Data))
}

function Invoke-ClosureScriptCheck {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [hashtable]$Parameters
    )

    Ensure-NPDevFile $ScriptPath ($Name + " script")
    Write-NPDevInfo ("Running closure check: " + $Name)

    try {
        & $ScriptPath @Parameters
        Add-ClosureCheck $Name $true ($Name + " passed.") ([pscustomobject]@{
                script = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ScriptPath
            })
    }
    catch {
        Add-ClosureCheck $Name $false ($Name + " failed: " + $_.Exception.Message) ([pscustomobject]@{
                script = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ScriptPath
                error = $_.Exception.Message
            })
    }
}

function Get-OptionalJsonProperty {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    if ($null -ne $ObjectValue -and $ObjectValue.PSObject.Properties.Name -contains $PropertyName) {
        return $ObjectValue.PSObject.Properties[$PropertyName].Value
    }

    return $null
}

function Get-StateZipStamp {
    param([System.IO.FileInfo]$File)

    if ($File.BaseName -match '_(\d{8})_(\d{6})$') {
        try {
            return [datetime]::ParseExact(
                ($matches[1] + "_" + $matches[2]),
                "yyyyMMdd_HHmmss",
                [Globalization.CultureInfo]::InvariantCulture
            )
        }
        catch {
            return [datetime]::MinValue
        }
    }

    return [datetime]::MinValue
}

function Resolve-LatestAllStateZip {
    param([string]$ZipRoot)

    if (-not (Test-Path -LiteralPath $ZipRoot -PathType Container)) {
        return $null
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $ZipRoot -File -Filter "NPDev_General_State_ALL_*.zip" |
        Where-Object { $_.Name -notmatch "TEST_" } |
        Sort-Object @{ Expression = { Get-StateZipStamp $_ }; Descending = $true }, LastWriteTime -Descending
    )

    if ($candidates.Count -eq 0) {
        return $null
    }

    return $candidates[0].FullName
}

function Get-ZipEntryText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ZipPath,
        [Parameter(Mandatory = $true)]
        [string]$EntryPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entry = $archive.GetEntry($EntryPath.Replace("\", "/"))
        if ($null -eq $entry) {
            return $null
        }

        $reader = [System.IO.StreamReader]::new($entry.Open())
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Invoke-GitCapture {
    param([string[]]$Arguments)

    $capture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRoot -Executable "git" -Arguments $Arguments
    return [pscustomobject]@{
        exitCode = $capture.ExitCode
        output = @($capture.Output)
    }
}

try {
    $head = Invoke-GitCapture @("rev-parse", "HEAD")
    $headText = if ($head.output.Count -gt 0) { [string]$head.output[0] } else { "" }
    Add-ClosureCheck "git-head-exists" ($head.exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($headText)) "Git HEAD must resolve." ([pscustomobject]@{ head = $headText })
}
catch {
    Add-ClosureCheck "git-head-exists" $false ("Git HEAD did not resolve: " + $_.Exception.Message)
}

try {
    $tag = Invoke-GitCapture @("rev-parse", "--verify", "refs/tags/npdev-official-beta-20260428-062512")
    $tagText = if ($tag.output.Count -gt 0) { [string]$tag.output[0] } else { "" }
    Add-ClosureCheck "official-beta-tag-exists" ($tag.exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($tagText)) "Baseline official beta tag must exist." ([pscustomobject]@{ tag = "npdev-official-beta-20260428-062512"; target = $tagText })
}
catch {
    Add-ClosureCheck "official-beta-tag-exists" $false ("Baseline official beta tag did not resolve: " + $_.Exception.Message)
}

$latestStateZip = Resolve-LatestAllStateZip -ZipRoot $StateZipRoot
Add-ClosureCheck "latest-state-zip-exists" (-not [string]::IsNullOrWhiteSpace($latestStateZip) -and (Test-Path -LiteralPath $latestStateZip -PathType Leaf)) "Latest ALL state zip must exist." ([pscustomobject]@{
        stateZipRoot = $StateZipRoot
        latestStateZip = $latestStateZip
    })

$releaseSummary = $null
if (-not [string]::IsNullOrWhiteSpace($latestStateZip) -and (Test-Path -LiteralPath $latestStateZip -PathType Leaf)) {
    try {
        $summaryText = Get-ZipEntryText -ZipPath $latestStateZip -EntryPath "release-ready-summary.json"
        if (-not [string]::IsNullOrWhiteSpace($summaryText)) {
            $releaseSummary = $summaryText | ConvertFrom-Json
        }
    }
    catch {
        $releaseSummary = $null
    }
}

$summaryChecksPassed = $false
if ($null -ne $releaseSummary) {
    $summaryChecksPassed = (
        [bool](Get-OptionalJsonProperty $releaseSummary "releaseReady") -and
        [bool](Get-OptionalJsonProperty $releaseSummary "officialReleaseEligible") -and
        [string](Get-OptionalJsonProperty $releaseSummary "packagingMode") -eq "RELEASE_READY" -and
        [string](Get-OptionalJsonProperty $releaseSummary "provenanceGrade") -in @("git-traceable", "ci-traceable") -and
        [bool](Get-OptionalJsonProperty $releaseSummary "traceabilitySatisfied")
    )
}
Add-ClosureCheck "latest-state-zip-release-summary" $summaryChecksPassed "Latest ALL state zip release summary must be release-ready, eligible, and traceable." $releaseSummary

$scriptAutomationReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\roadmap-closure-script-automation-quality-report.json"
Invoke-ClosureScriptCheck "script-automation-quality" (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-script-automation-quality.ps1") @{
    WorkspaceRoot = $WorkspaceRoot
    RunId = $RunId
    ReportPath = $scriptAutomationReportPath
}

$hygieneReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\roadmap-closure-hygiene-gate-report.json"
Invoke-ClosureScriptCheck "hygiene-gate" (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-hygiene-gate.ps1") @{
    WorkspaceRoot = $WorkspaceRoot
    RunId = $RunId
    ReportPath = $hygieneReportPath
}

$runtimeSurfaceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\roadmap-closure-runtime-surface-evidence-report.json"
$runtimeClassificationReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\roadmap-closure-runtime-surface-classification-report.json"
$runtimeAllowlistReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\roadmap-closure-runtime-surface-allowlist-report.json"
$runtimeFootprintReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\roadmap-closure-runtime-footprint-report.json"
Invoke-ClosureScriptCheck "runtime-surface-evidence" (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-runtime-surface-evidence.ps1") @{
    WorkspaceRoot = $WorkspaceRoot
    RunId = $RunId
    ReportPath = $runtimeSurfaceReportPath
    ClassificationReportPath = $runtimeClassificationReportPath
    AllowlistReportPath = $runtimeAllowlistReportPath
    FootprintReportPath = $runtimeFootprintReportPath
}

$deadRemoveCandidatesPassed = $false
$deadRemoveCandidatesData = $null
if (Test-Path -LiteralPath $runtimeFootprintReportPath -PathType Leaf) {
    try {
        $runtimeFootprint = Get-Content -LiteralPath $runtimeFootprintReportPath -Raw | ConvertFrom-Json
        $deadRemoveCandidatesData = $runtimeFootprint.footprint.deadRemoveCandidates
        $controllerCandidates = Get-OptionalJsonProperty $deadRemoveCandidatesData "controllers"
        $serviceCandidates = Get-OptionalJsonProperty $deadRemoveCandidatesData "services"
        $controllerCount = if ($null -eq $controllerCandidates) { 0 } else { @($controllerCandidates).Count }
        $serviceCount = if ($null -eq $serviceCandidates) { 0 } else { @($serviceCandidates).Count }
        $deadRemoveCandidatesPassed = ($controllerCount + $serviceCount -eq 0)
    }
    catch {
        $deadRemoveCandidatesData = [pscustomobject]@{ error = $_.Exception.Message }
    }
}
Add-ClosureCheck "no-runtimehost-dead-remove-candidates" $deadRemoveCandidatesPassed "RuntimeHost deadRemoveCandidates must be empty." $deadRemoveCandidatesData

$runbookPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "docs\OFFICIAL_BETA_RELEASE_RUNBOOK.md"
Add-ClosureCheck "official-release-runbook-exists" (Test-Path -LiteralPath $runbookPath -PathType Leaf) "Official beta release runbook must exist." ([pscustomobject]@{
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $runbookPath
    })

$workflowPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ".github\workflows\npdev-release-gate.yml"
Add-ClosureCheck "ci-release-gate-workflow-exists" (Test-Path -LiteralPath $workflowPath -PathType Leaf) "NPDev release gate workflow must exist." ([pscustomobject]@{
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $workflowPath
    })

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    stateZipRoot = $StateZipRoot
    latestStateZip = $latestStateZip
    overallStatus = $overallStatus
    checks = @($checks)
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk "Roadmap closure check passed."
    return
}

foreach ($failure in $failedChecks) {
    Write-NPDevWarn ($failure.name + ": " + $failure.summary)
}
throw "Roadmap closure check failed."
