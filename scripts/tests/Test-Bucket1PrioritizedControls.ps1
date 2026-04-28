Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

function Write-JsonFileForTest {
    param(
        [string]$PathValue,
        [object]$Value
    )

    $parent = Split-Path -Parent $PathValue
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $PathValue -Encoding UTF8
}

function Add-ManifestFileEntry {
    param(
        [System.Collections.Generic.List[object]]$Files,
        [string]$WorkspaceRoot,
        [string]$EvidenceRoot,
        [string]$SourcePath
    )

    $relativeSource = [Uri]::UnescapeDataString(([Uri]((Normalize-NPDevPath $WorkspaceRoot).TrimEnd('\') + '\')).MakeRelativeUri([Uri](Normalize-NPDevPath $SourcePath)).ToString()).Replace("/", "\")
    $copiedPath = Join-Path $EvidenceRoot $relativeSource
    $copiedParent = Split-Path -Parent $copiedPath
    if (-not [string]::IsNullOrWhiteSpace($copiedParent)) {
        New-Item -ItemType Directory -Force -Path $copiedParent | Out-Null
    }
    Copy-Item -LiteralPath $SourcePath -Destination $copiedPath -Force
    $sourceHash = (Get-FileHash -LiteralPath $SourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $copiedHash = (Get-FileHash -LiteralPath $copiedPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [void]$Files.Add([pscustomobject]@{
            source = $relativeSource
            copiedTo = [Uri]::UnescapeDataString(([Uri]((Normalize-NPDevPath $WorkspaceRoot).TrimEnd('\') + '\')).MakeRelativeUri([Uri](Normalize-NPDevPath $copiedPath)).ToString()).Replace("/", "\")
            sha256 = $copiedHash
            sourceSha256 = $sourceHash
            sizeBytes = (Get-Item -LiteralPath $copiedPath).Length
        })
}

function New-Bucket1Fixture {
    param(
        [string]$RootPath,
        [string]$ProvenanceGrade = "local-unanchored",
        [bool]$TraceabilitySatisfied = $false,
        [string]$ChildRunId = "fixture-run-1",
        [bool]$IncludeOutputTail = $true
    )

    if (Test-Path -LiteralPath $RootPath) {
        Remove-Item -LiteralPath $RootPath -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $RootPath | Out-Null

    $outRoot = Join-Path $RootPath "scripts\reports\out"
    $releaseRunId = "fixture-run-1"
    $evidenceRootRelative = "scripts\reports\releases\" + $releaseRunId
    $evidenceRoot = Join-Path $RootPath $evidenceRootRelative
    $aggregateReportPath = Join-Path $outRoot "beta-release-gate-report.json"
    $childReportPath = Join-Path $outRoot "runtimehost-gate-report.json"
    $logPath = Join-Path $outRoot "runtimehost-simple-contact-intake-verification.log"
    $manifestPath = Join-Path $evidenceRoot "evidence-manifest.json"

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logPath) | Out-Null
    "fixture log" | Set-Content -LiteralPath $logPath -Encoding UTF8

    $commitIdentity = switch ($ProvenanceGrade) {
        "git-traceable" {
            [pscustomobject]@{
                available = $true
                source = "traceable-local"
                commitSha = "abc123def456"
                branch = "release/main"
                dirty = $false
                runId = ""
                runAttempt = ""
                workflow = "manual"
            }
        }
        "ci-traceable" {
            [pscustomobject]@{
                available = $true
                source = "github-actions"
                commitSha = "abc123def456"
                branch = "main"
                dirty = $false
                runId = "ci-run-77"
                runAttempt = "1"
                workflow = "ci"
            }
        }
        default {
            [pscustomobject]@{
                available = $false
                source = "unavailable"
                commitSha = $null
                branch = $null
                dirty = $null
                runId = $null
                runAttempt = $null
                workflow = $null
            }
        }
    }

    $verificationCommand = [pscustomobject]@{
        status = "passed"
        workingDirectory = "NPDevSamples\simple-contact-intake\Output\App"
        executable = ".\gradlew.bat"
        arguments = @("--no-daemon", "--console=plain", "enforceSingleMigrationSource", "test")
        display = ".\gradlew.bat --no-daemon --console=plain enforceSingleMigrationSource test"
        exitCode = 0
        startedAt = "2026-04-23T00:00:00Z"
        endedAt = "2026-04-23T00:00:30Z"
        durationSeconds = 30.0
        outputLineCount = if ($IncludeOutputTail) { 2 } else { 0 }
        outputTail = if ($IncludeOutputTail) { @("> Task :enforceSingleMigrationSource", "BUILD SUCCESSFUL") } else { $null }
        failingTaskName = $null
        logPath = "scripts\reports\out\runtimehost-simple-contact-intake-verification.log"
        error = $null
        failureReasons = @()
    }

    $childReport = [pscustomobject]@{
        generatedAt = "2026-04-23T00:00:30Z"
        runId = $ChildRunId
        scriptPath = "scripts\quality\run-runtimehost-gate.ps1"
        workspaceRoot = (Normalize-NPDevPath $RootPath)
        overallStatus = "passed"
        sampleId = "simple-contact-intake"
        verificationCommand = $verificationCommand
        cleanup = [pscustomobject]@{
            status = "passed"
            reportPath = $null
            error = $null
        }
    }
    Write-JsonFileForTest -PathValue $childReportPath -Value $childReport

    $aggregateReport = [pscustomobject]@{
        generatedAt = "2026-04-23T00:01:00Z"
        runId = $releaseRunId
        releaseRunId = $releaseRunId
        scriptPath = "scripts\quality\run-beta-release-gate.ps1"
        workspaceRoot = (Normalize-NPDevPath $RootPath)
        overallStatus = "passed"
        evidenceRoot = $evidenceRootRelative
        provenanceGrade = $ProvenanceGrade
        traceabilitySatisfied = $TraceabilitySatisfied
        commitIdentity = $commitIdentity
        authoritativeDecision = [pscustomobject]@{
            sourceOfTruth = "scripts\reports\out\beta-release-gate-report.json"
            releaseRunId = $releaseRunId
            rule = "fixture"
            staleReportPolicy = "purged-before-execution"
        }
        steps = @(
            [pscustomobject]@{
                name = "runtimehost"
                script = "scripts\quality\run-runtimehost-gate.ps1"
                status = "passed"
                exitDisposition = "passed"
                childReportDisposition = "current"
                runIdMatch = $true
                finalDecisionReason = "child report is current for aggregate runId and controls the step decision"
                startedAt = "2026-04-23T00:00:00Z"
                endedAt = "2026-04-23T00:00:30Z"
                durationSeconds = 30.0
                clearedPreviousReport = $false
                reportPath = "scripts\reports\out\runtimehost-gate-report.json"
                reportGeneratedAt = "2026-04-23T00:00:30Z"
                reportRunId = $ChildRunId
                reportScriptPath = "scripts\quality\run-runtimehost-gate.ps1"
                error = $null
            }
        )
        copiedEvidence = @()
    }
    Write-JsonFileForTest -PathValue $aggregateReportPath -Value $aggregateReport

    $manifestFiles = [System.Collections.Generic.List[object]]::new()
    Add-ManifestFileEntry -Files $manifestFiles -WorkspaceRoot $RootPath -EvidenceRoot $evidenceRoot -SourcePath $childReportPath
    Add-ManifestFileEntry -Files $manifestFiles -WorkspaceRoot $RootPath -EvidenceRoot $evidenceRoot -SourcePath $aggregateReportPath

    $manifest = [pscustomobject]@{
        generatedAt = "2026-04-23T00:01:01Z"
        runId = $releaseRunId
        releaseRunId = $releaseRunId
        workspaceRoot = (Normalize-NPDevPath $RootPath)
        evidenceRoot = $evidenceRootRelative
        authoritativeReport = "scripts\reports\out\beta-release-gate-report.json"
        provenanceGrade = $ProvenanceGrade
        commitIdentity = $commitIdentity
        environmentFingerprint = [pscustomobject]@{
            osDescription = "Windows"
        }
        files = $manifestFiles
    }
    Write-JsonFileForTest -PathValue $manifestPath -Value $manifest

    return [pscustomobject]@{
        root = (Normalize-NPDevPath $RootPath)
        aggregateReportPath = $aggregateReportPath
        evidenceManifestPath = $manifestPath
    }
}

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
$b1Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b1-provenance-control.ps1"
$b2Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b2-governance-truth-chain-control.ps1"
$b3Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b3-evidence-bundle-diagnostics-control.ps1"
$boardScript = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\run-prioritized-control-board.ps1"
$stateZipScript = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\statezip-npdev-general.ps1"

$fixtureRoot = Join-Path $env:TEMP "npdev-bucket1-controls-fixture"
$staleFixtureRoot = Join-Path $env:TEMP "npdev-bucket1-controls-fixture-stale"
$missingEvidenceFixtureRoot = Join-Path $env:TEMP "npdev-bucket1-controls-fixture-missing-evidence"
$stamp = "TEST_BUCKET1_CONTROL_BOARD"

$failures = [System.Collections.Generic.List[string]]::new()
function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        [void]$failures.Add($Message)
    }
}

try {
    $fixture = New-Bucket1Fixture -RootPath $fixtureRoot -ProvenanceGrade "local-unanchored" -TraceabilitySatisfied:$false -ChildRunId "fixture-run-1" -IncludeOutputTail:$true
    $b1Report = & $b1Script -WorkspaceRoot $fixture.root -AggregateReportPath $fixture.aggregateReportPath -EvidenceManifestPath $fixture.evidenceManifestPath -PassThru
    $b1FailedChecks = @($b1Report.checks | Where-Object { $_.status -eq "failed" } | ForEach-Object { [string]$_.name }) -join ", "
    Assert-True ([string]$b1Report.overallStatus -eq "warning") ("Expected valid local-unanchored provenance evidence to evaluate as warning. Actual: " + [string]$b1Report.overallStatus + ". Failed checks: " + $b1FailedChecks)

    $b2Report = & $b2Script -WorkspaceRoot $fixture.root -AggregateReportPath $fixture.aggregateReportPath -PassThru
    $b2FailedChecks = @($b2Report.checks | Where-Object { $_.status -eq "failed" } | ForEach-Object { [string]$_.name }) -join ", "
    Assert-True ([string]$b2Report.overallStatus -eq "passed") ("Expected exact governance truth-chain control to pass for the valid fixture. Actual: " + [string]$b2Report.overallStatus + ". Failed checks: " + $b2FailedChecks)

    $b3Report = & $b3Script -WorkspaceRoot $fixture.root -AggregateReportPath $fixture.aggregateReportPath -EvidenceManifestPath $fixture.evidenceManifestPath -PassThru
    $b3FailedChecks = @($b3Report.checks | Where-Object { $_.status -eq "failed" } | ForEach-Object { [string]$_.name }) -join ", "
    Assert-True ([string]$b3Report.overallStatus -eq "passed") ("Expected exact evidence-bundle diagnostics control to pass for the valid fixture. Actual: " + [string]$b3Report.overallStatus + ". Failed checks: " + $b3FailedChecks)

    $staleFixture = New-Bucket1Fixture -RootPath $staleFixtureRoot -ProvenanceGrade "git-traceable" -TraceabilitySatisfied:$true -ChildRunId "stale-run-9" -IncludeOutputTail:$true
    $staleGovernanceReport = & $b2Script -WorkspaceRoot $staleFixture.root -AggregateReportPath $staleFixture.aggregateReportPath -PassThru
    Assert-True ([string]$staleGovernanceReport.overallStatus -eq "failed") ("Expected governance truth-chain control to fail when the child report runId is stale. Actual: " + [string]$staleGovernanceReport.overallStatus)

    $missingEvidenceFixture = New-Bucket1Fixture -RootPath $missingEvidenceFixtureRoot -ProvenanceGrade "git-traceable" -TraceabilitySatisfied:$true -ChildRunId "fixture-run-1" -IncludeOutputTail:$false
    $missingEvidenceReport = & $b3Script -WorkspaceRoot $missingEvidenceFixture.root -AggregateReportPath $missingEvidenceFixture.aggregateReportPath -EvidenceManifestPath $missingEvidenceFixture.evidenceManifestPath -PassThru
    Assert-True ([string]$missingEvidenceReport.overallStatus -eq "failed") ("Expected evidence-bundle diagnostics control to fail when machine-readable outputTail evidence is missing. Actual: " + [string]$missingEvidenceReport.overallStatus)

    & $stateZipScript -WorkspaceRoot $workspaceRoot -ReleaseReady -ExistingEvidenceRoot last -Stamp $stamp -Quiet | Out-Null
    $boardReport = & $boardScript -WorkspaceRoot $workspaceRoot -RunId "test-prioritized-control-board" -PassThru
    Assert-True ($null -ne $boardReport.generatedAt) "Expected prioritized control board summary to include generatedAt."
    Assert-True ($null -ne $boardReport.runId) "Expected prioritized control board summary to include runId."
    Assert-True ($boardReport.summary.total -eq 5) "Expected prioritized control board to aggregate the five Bucket 1 controls."
    Assert-True (@($boardReport.controls).Count -eq 5) "Expected prioritized control board summary to expose five control entries."
    foreach ($control in @($boardReport.controls)) {
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$control.bucket)) "Expected each control entry to include bucket."
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$control.controlId)) "Expected each control entry to include controlId."
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$control.status)) "Expected each control entry to include status."
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$control.reportPath)) "Expected each control entry to include reportPath."
        Assert-True ($null -ne $control.evidencePaths) "Expected each control entry to include evidencePaths."
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$control.generatedAt)) "Expected each control entry to include generatedAt."
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$control.controlRunId)) "Expected each control entry to include controlRunId."
    }
}
catch {
    [void]$failures.Add($_.Exception.Message)
}
finally {
    foreach ($pathValue in @($fixtureRoot, $staleFixtureRoot, $missingEvidenceFixtureRoot)) {
        if (Test-Path -LiteralPath $pathValue) {
            Remove-Item -LiteralPath $pathValue -Recurse -Force
        }
    }
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "Bucket 1 prioritized control tests passed."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "Bucket 1 prioritized control tests failed."
