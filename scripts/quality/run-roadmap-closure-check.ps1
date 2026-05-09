param(
    [string]$WorkspaceRoot = ".",
    [string]$StateZipRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/roadmap-closure-check-report.json",
    [string]$TraceableLocalReleaseReportPath = "scripts/reports/out/traceable-local-release-report.json",
    [string]$FinalReleaseReportPath = "scripts/reports/out/beta0-final-release-check-report.json",
    [string]$FinalClosureReportPath = "scripts/reports/out/beta0-final-closure-report.json",
    [string]$BetaReleaseGateReportPath = "scripts/reports/out/beta-release-gate-report.json",
    [string]$OfficialRunbookPath = "docs/OFFICIAL_BETA_RELEASE_RUNBOOK.md",
    [string]$ReleaseWorkflowPath = ".github/workflows/npdev-release-gate.yml",
    [string]$AiBetaWorkflowPath = ".github/workflows/ai-beta-gate.yml",
    [string]$DocEntrypointPolicyPath = "scripts/policy/doc-entrypoint-classification-policy.json"
)

$ErrorActionPreference = "Stop"

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Resolve-WorkspacePath {
    param([string]$Root, [string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
}

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-FileEvidence {
    param([string]$Root, [string]$PathValue)
    $fullPath = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    $exists = Test-Path -LiteralPath $fullPath -PathType Leaf
    return [pscustomobject]@{
        path = Convert-ToRepoPath -Root $Root -PathValue $fullPath
        exists = $exists
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant() } else { $null }
    }
}

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Reason,
        [object]$Evidence
    )
    $script:checks += [pscustomobject]@{
        name = $Name
        passed = $Passed
        reason = $Reason
        evidence = $Evidence
    }
    if (-not $Passed) {
        $script:blockers.Add(("Check failed: " + $Name + " - " + $Reason)) | Out-Null
    }
}

function Get-ReportStatusEvidence {
    param([string]$Root, [string]$PathValue, [string]$ExpectedStatus = "passed")
    $fullPath = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    $report = Read-JsonFile $fullPath
    $exists = $null -ne $report
    $status = if ($exists -and $report.PSObject.Properties.Name -contains "overallStatus") { [string]$report.overallStatus } else { "missing" }
    return [pscustomobject]@{
        path = Convert-ToRepoPath -Root $Root -PathValue $fullPath
        exists = $exists
        schemaVersion = if ($exists) { [string]$report.schemaVersion } else { "" }
        runId = if ($exists) { [string]$report.runId } else { "" }
        overallStatus = $status
        expectedStatus = $ExpectedStatus
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant() } else { $null }
        officialReleaseEligible = $exists -and ($report.PSObject.Properties.Name -contains "officialReleaseEligible") -and [bool]$report.officialReleaseEligible
        beta0TagAllowed = $exists -and ($report.PSObject.Properties.Name -contains "beta0TagAllowed") -and [bool]$report.beta0TagAllowed
    }
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "roadmap-closure-check-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $script:blockers = [System.Collections.Generic.List[string]]::new()
    $script:checks = @()

    foreach ($scriptPath in @("scripts/quality/run-traceable-local-release.ps1", "scripts/quality/run-roadmap-closure-check.ps1")) {
        $evidence = Get-FileEvidence -Root $workspaceRootPath -PathValue $scriptPath
        Add-Check -Name ("helper-script-exists:" + $scriptPath) -Passed ([bool]$evidence.exists) -Reason ("Required runbook helper exists: " + $scriptPath) -Evidence $evidence
    }

    $policy = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $DocEntrypointPolicyPath)
    foreach ($scriptPath in @("scripts/quality/run-traceable-local-release.ps1", "scripts/quality/run-roadmap-closure-check.ps1")) {
        $entry = @($policy.scriptClassifications | Where-Object { [string]$_.path -eq $scriptPath } | Select-Object -First 1)
        $passed = $entry.Count -eq 1 -and [string]$entry[0].classification -eq "release-relevant" -and [bool]$entry[0].releaseRelevant
        Add-Check -Name ("helper-policy-release-relevant:" + $scriptPath) -Passed $passed -Reason ("Doc-entrypoint policy classifies " + $scriptPath + " as implemented release-relevant.") -Evidence ([pscustomobject]@{
                policyPath = $DocEntrypointPolicyPath
                classification = if ($entry.Count -eq 1) { [string]$entry[0].classification } else { "missing" }
                releaseRelevant = $entry.Count -eq 1 -and [bool]$entry[0].releaseRelevant
            })
    }

    $runbookFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $OfficialRunbookPath
    $runbookText = if (Test-Path -LiteralPath $runbookFullPath -PathType Leaf) { Get-Content -Raw -LiteralPath $runbookFullPath } else { "" }
    Add-Check -Name "official-runbook-uses-traceable-wrapper" -Passed ($runbookText -match "run-traceable-local-release\.ps1") -Reason "Official runbook calls the canonical traceable local release wrapper." -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $OfficialRunbookPath)
    Add-Check -Name "official-runbook-uses-roadmap-closure-helper" -Passed ($runbookText -match "run-roadmap-closure-check\.ps1") -Reason "Official runbook calls the roadmap closure helper after canonical release evidence." -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $OfficialRunbookPath)

    foreach ($workflowPath in @($ReleaseWorkflowPath, $AiBetaWorkflowPath)) {
        $workflowFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $workflowPath
        $workflowText = if (Test-Path -LiteralPath $workflowFullPath -PathType Leaf) { Get-Content -Raw -LiteralPath $workflowFullPath } else { "" }
        Add-Check -Name ("workflow-uses-traceable-wrapper:" + $workflowPath) -Passed ($workflowText -match "run-traceable-local-release\.ps1") -Reason ("Workflow calls the same traceable release entrypoint as the official runbook: " + $workflowPath) -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $workflowPath)
        Add-Check -Name ("workflow-runs-roadmap-closure:" + $workflowPath) -Passed ($workflowText -match "run-roadmap-closure-check\.ps1") -Reason ("Workflow runs the roadmap closure helper so closure evidence is uploaded: " + $workflowPath) -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $workflowPath)
    }

    $traceableEvidence = Get-ReportStatusEvidence -Root $workspaceRootPath -PathValue $TraceableLocalReleaseReportPath
    Add-Check -Name "traceable-local-release-report-passed" -Passed ($traceableEvidence.exists -and $traceableEvidence.overallStatus -eq "passed") -Reason "Traceable local release report must exist and pass." -Evidence $traceableEvidence

    $finalEvidence = Get-ReportStatusEvidence -Root $workspaceRootPath -PathValue $FinalReleaseReportPath
    Add-Check -Name "final-release-check-report-passed" -Passed ($finalEvidence.exists -and $finalEvidence.overallStatus -eq "passed" -and $finalEvidence.officialReleaseEligible -and $finalEvidence.beta0TagAllowed) -Reason "Canonical final release report must pass and allow the Beta 0 tag." -Evidence $finalEvidence

    $closureEvidence = Get-ReportStatusEvidence -Root $workspaceRootPath -PathValue $FinalClosureReportPath
    Add-Check -Name "final-closure-report-passed" -Passed ($closureEvidence.exists -and $closureEvidence.overallStatus -eq "passed" -and $closureEvidence.officialReleaseEligible -and $closureEvidence.beta0TagAllowed) -Reason "Final closure report must pass and allow the Beta 0 tag." -Evidence $closureEvidence

    $betaEvidence = Get-ReportStatusEvidence -Root $workspaceRootPath -PathValue $BetaReleaseGateReportPath
    Add-Check -Name "beta-release-gate-report-passed" -Passed ($betaEvidence.exists -and $betaEvidence.overallStatus -eq "passed" -and $betaEvidence.officialReleaseEligible) -Reason "Beta release gate report must pass and grant official release eligibility." -Evidence $betaEvidence

    if (-not [string]::IsNullOrWhiteSpace($StateZipRoot)) {
        $zipRootFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $StateZipRoot
        $latestZip = if (Test-Path -LiteralPath $zipRootFullPath -PathType Container) {
            Get-ChildItem -LiteralPath $zipRootFullPath -Filter "NPDev_General_State_ALL_*.zip" -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        }
        else {
            $null
        }
        Add-Check -Name "release-state-zip-exists" -Passed ($null -ne $latestZip) -Reason "Release-ready state zip must exist when StateZipRoot is provided." -Evidence ([pscustomobject]@{
                stateZipRoot = $StateZipRoot
                latestStateZip = if ($null -ne $latestZip) { $latestZip.FullName -replace "\\", "/" } else { "" }
                sha256 = if ($null -ne $latestZip) { (Get-FileHash -Algorithm SHA256 -LiteralPath $latestZip.FullName).Hash.ToLowerInvariant() } else { $null }
            })
    }

    $overallStatus = if ($blockers.Count -eq 0) { "passed" } else { "failed" }
    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    $report = [pscustomobject]@{
        schemaVersion = "npdev-roadmap-closure-check-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-roadmap-closure-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        checks = @($checks)
        blockers = @($blockers)
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    if ($overallStatus -eq "passed") {
        Write-Host ("Roadmap closure check passed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
        exit 0
    }

    Write-Error ("Roadmap closure check failed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
}
finally {
    Pop-Location
}
