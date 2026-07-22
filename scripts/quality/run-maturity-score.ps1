param(
    [string]$WorkspaceRoot = ".",
    [string]$PolicyPath = "scripts/policy/maturity-score-policy.json",
    [string]$ReportPath = "scripts/reports/out/maturity-score-report.json",
    [string]$SchemaPath = "schemas/ai/maturity-score-report.schema.json",
    [string]$RunId = "",
    [decimal]$ClaimedBeta0MaturityScore = -1,
    [decimal]$ClaimedProductMaturityScore = -1
)

$ErrorActionPreference = "Stop"

function Resolve-WorkspacePath {
    param([string]$Root, [string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
}

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathValue)
    if (-not $resolvedRoot.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $resolvedRoot += [System.IO.Path]::DirectorySeparatorChar
    }
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length) -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Read-JsonFile {
    param([string]$PathValue)
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }
    return Get-Content -Raw -LiteralPath $PathValue | ConvertFrom-Json
}

function Get-ReportStatus {
    param([object]$Report)
    if ($null -eq $Report) {
        return "missing"
    }
    if ($Report.PSObject.Properties.Name -contains "overallStatus") {
        return [string]$Report.overallStatus
    }
    if ($Report.PSObject.Properties.Name -contains "status") {
        return [string]$Report.status
    }
    return "missing-status"
}

function New-ReportEvidence {
    param([string]$Root, [string]$PathValue)
    $fullPath = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    $report = Read-JsonFile $fullPath
    $status = Get-ReportStatus $report
    return [pscustomobject]@{
        path = Convert-ToRepoPath -Root $Root -PathValue $fullPath
        exists = $null -ne $report
        schemaVersion = if ($null -ne $report -and $report.PSObject.Properties.Name -contains "schemaVersion") { [string]$report.schemaVersion } else { "" }
        status = $status
        passed = ($null -ne $report -and $status -eq "passed")
        generatedAt = if ($null -ne $report -and $report.PSObject.Properties.Name -contains "generatedAt") { [string]$report.generatedAt } else { "" }
        sha256 = if ($null -ne $report) { (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant() } else { $null }
    }
}

function Add-CapIf {
    param(
        [System.Collections.Generic.List[object]]$Caps,
        [object]$PolicyCap,
        [string]$Evidence,
        [bool]$Applies
    )
    if (-not $Applies) {
        return
    }
    [void]$Caps.Add([pscustomobject]@{
        id = [string]$PolicyCap.id
        reason = [string]$PolicyCap.reason
        evidence = $Evidence
        profiles = $PolicyCap.profiles
    })
}

function Get-CapForProfile {
    param([object[]]$Caps, [string]$Profile, [decimal]$RawScore)
    $capValue = [decimal]100
    foreach ($cap in @($Caps)) {
        if ($cap.profiles.PSObject.Properties.Name -contains $Profile) {
            $candidate = [decimal]$cap.profiles.$Profile
            if ($candidate -lt $capValue) {
                $capValue = $candidate
            }
        }
    }
    if ($RawScore -lt $capValue) {
        return $RawScore
    }
    return $capValue
}

function Get-Confidence {
    param([object[]]$AreaResults, [object[]]$Caps)
    $missingCount = 0
    foreach ($area in @($AreaResults)) {
        $missingCount += [int]$area.missingReportCount
    }
    if ($missingCount -gt 8 -or @($Caps).Count -gt 4) {
        return "low"
    }
    if ($missingCount -gt 0 -or @($Caps).Count -gt 0) {
        return "medium"
    }
    return "high"
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "maturity-score-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $policyFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $PolicyPath
    $policy = Get-Content -Raw -LiteralPath $policyFullPath | ConvertFrom-Json

    $areaResults = @()
    foreach ($area in @($policy.areas)) {
        $reportEvidence = @($area.reports | ForEach-Object { New-ReportEvidence -Root $workspaceRootPath -PathValue ([string]$_) })
        $passedCount = @($reportEvidence | Where-Object { $_.passed }).Count
        $totalCount = $reportEvidence.Count
        $earnedWeight = if ($totalCount -eq 0) { [decimal]0 } else { [math]::Round(([decimal]$area.weight * ([decimal]$passedCount / [decimal]$totalCount)), 2) }
        $areaResults += [pscustomobject]@{
            id = [string]$area.id
            name = [string]$area.name
            weight = [decimal]$area.weight
            earnedWeight = $earnedWeight
            passedReportCount = $passedCount
            reportCount = $totalCount
            missingReportCount = @($reportEvidence | Where-Object { -not $_.exists }).Count
            reports = @($reportEvidence)
        }
    }

    $rawScore = [math]::Round(([decimal](($areaResults | Measure-Object earnedWeight -Sum).Sum)), 2)
    if ($rawScore -gt 100) {
        $rawScore = 100
    }

    $activeCaps = [System.Collections.Generic.List[object]]::new()
    $beta0Scope = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/policy/beta0-scope.json")
    $sampleMatrix = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/out/sample-matrix-report.json")
    $phase2 = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/out/phase2-residual-fidelity-report.json")
    $postBeta0 = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/out/post-beta0-maturity-closure-report.json")

    foreach ($cap in @($policy.caps)) {
        switch ([string]$cap.id) {
            "trusted-source-deferred" {
                $trustedSourceEnabled = $false
                if ($null -ne $beta0Scope -and $beta0Scope.PSObject.Properties.Name -contains "trustedSourcePolicy") {
                    $trustedSourceEnabled = [bool]$beta0Scope.trustedSourcePolicy.enabled
                }
                Add-CapIf -Caps $activeCaps -PolicyCap $cap -Evidence "scripts/policy/beta0-scope.json trustedSourcePolicy.enabled=false" -Applies (-not $trustedSourceEnabled)
            }
            "custom-procedure-or-panel-incomplete" {
                $customUxReport = New-ReportEvidence -Root $workspaceRootPath -PathValue "scripts/reports/out/custom-ux-extensibility-report.json"
                Add-CapIf -Caps $activeCaps -PolicyCap $cap -Evidence "custom-ux-extensibility-report not passing" -Applies (-not $customUxReport.passed)
            }
            "fixture-only-sample-gaps" {
                $nonBlockingIssueCount = if ($null -ne $sampleMatrix -and $sampleMatrix.PSObject.Properties.Name -contains "nonBlockingIssueCount") { [int]$sampleMatrix.nonBlockingIssueCount } else { 0 }
                Add-CapIf -Caps $activeCaps -PolicyCap $cap -Evidence ("sample-matrix nonBlockingIssueCount=" + $nonBlockingIssueCount) -Applies ($nonBlockingIssueCount -gt 0)
            }
            "missing-real-participant-validation" {
                $actions = if ($null -ne $postBeta0 -and $postBeta0.PSObject.Properties.Name -contains "humanActions") { @($postBeta0.humanActions) } else { @() }
                $participantComplete = @($actions | Where-Object { [string]$_.action -eq "Real participant sessions" -and [string]$_.status -match "complete|signed|done|passed" }).Count -gt 0
                Add-CapIf -Caps $activeCaps -PolicyCap $cap -Evidence "Real participant sessions are not complete in post-beta0 maturity evidence." -Applies (-not $participantComplete)
            }
            "missing-independent-audit" {
                $actions = if ($null -ne $postBeta0 -and $postBeta0.PSObject.Properties.Name -contains "humanActions") { @($postBeta0.humanActions) } else { @() }
                $auditComplete = @($actions | Where-Object { [string]$_.action -eq "Independent audit sign-off" -and [string]$_.status -match "complete|signed|done|passed" }).Count -gt 0
                Add-CapIf -Caps $activeCaps -PolicyCap $cap -Evidence "Independent audit sign-off is not complete in post-beta0 maturity evidence." -Applies (-not $auditComplete)
            }
            "unproven-branch-protection" {
                $actions = if ($null -ne $postBeta0 -and $postBeta0.PSObject.Properties.Name -contains "humanActions") { @($postBeta0.humanActions) } else { @() }
                $branchProtectionComplete = @($actions | Where-Object { [string]$_.action -eq "Branch protection required Linux job" -and [string]$_.status -match "complete|enabled|done|passed" }).Count -gt 0
                Add-CapIf -Caps $activeCaps -PolicyCap $cap -Evidence "Required branch protection is not proven in human-action evidence." -Applies (-not $branchProtectionComplete)
            }
            "phase2-residual-fidelity-not-passing" {
                $phase2Status = Get-ReportStatus $phase2
                Add-CapIf -Caps $activeCaps -PolicyCap $cap -Evidence ("phase2-residual-fidelity overallStatus=" + $phase2Status) -Applies ($phase2Status -ne "passed")
            }
        }
    }

    $beta0Capped = [math]::Round((Get-CapForProfile -Caps @($activeCaps) -Profile "beta0" -RawScore $rawScore), 2)
    $productCapped = [math]::Round((Get-CapForProfile -Caps @($activeCaps) -Profile "product" -RawScore $rawScore), 2)
    $confidence = Get-Confidence -AreaResults @($areaResults) -Caps @($activeCaps)

    $claimFailures = [System.Collections.Generic.List[string]]::new()
    if ($ClaimedBeta0MaturityScore -ge 0 -and $ClaimedBeta0MaturityScore -gt $beta0Capped) {
        [void]$claimFailures.Add("Claimed beta0 maturity " + $ClaimedBeta0MaturityScore + " exceeds capped score " + $beta0Capped + ".")
    }
    if ($ClaimedProductMaturityScore -ge 0 -and $ClaimedProductMaturityScore -gt $productCapped) {
        [void]$claimFailures.Add("Claimed product maturity " + $ClaimedProductMaturityScore + " exceeds capped score " + $productCapped + ".")
    }
    if ($ClaimedBeta0MaturityScore -gt [decimal]$policy.maxClaimWithoutExplicitCapExplanation -and @($activeCaps).Count -eq 0) {
        [void]$claimFailures.Add("Claimed beta0 maturity above " + $policy.maxClaimWithoutExplicitCapExplanation + " requires explicit cap explanation.")
    }
    if ($ClaimedProductMaturityScore -gt [decimal]$policy.maxClaimWithoutExplicitCapExplanation -and @($activeCaps).Count -eq 0) {
        [void]$claimFailures.Add("Claimed product maturity above " + $policy.maxClaimWithoutExplicitCapExplanation + " requires explicit cap explanation.")
    }

    $overallStatus = if ($claimFailures.Count -eq 0) { "passed" } else { "failed" }
    $remainingReasons = @($activeCaps | ForEach-Object { [string]$_.id + ": " + [string]$_.reason })
    $report = [pscustomobject]@{
        schemaVersion = "npdev-maturity-score-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-maturity-score.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        policyPath = "scripts/policy/maturity-score-policy.json"
        scores = [pscustomobject]@{
            beta0MaturityScore = [pscustomobject]@{
                raw = $rawScore
                capped = $beta0Capped
                confidence = $confidence
            }
            productMaturityScore = [pscustomobject]@{
                raw = $rawScore
                capped = $productCapped
                confidence = $confidence
            }
        }
        areas = @($areaResults)
        caps = @($activeCaps)
        claimValidation = [pscustomobject]@{
            passed = ($claimFailures.Count -eq 0)
            claimedBeta0MaturityScore = if ($ClaimedBeta0MaturityScore -ge 0) { $ClaimedBeta0MaturityScore } else { $null }
            claimedProductMaturityScore = if ($ClaimedProductMaturityScore -ge 0) { $ClaimedProductMaturityScore } else { $null }
            failures = @($claimFailures)
        }
        remainingCappedMaturityReasons = @($remainingReasons)
        doesNotSolve = @(
            "Does not implement trusted-source or custom procedure product scope.",
            "Does not complete human validation or independent audit.",
            "Does not add new checkpoints."
        )
    }

    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    $schemaValidationPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/tmp/maturity-score-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error "Maturity score report failed schema validation."
        exit 1
    }

    if ($overallStatus -eq "passed") {
        Write-Host ("Maturity score check passed. Report: " + $ReportPath)
        exit 0
    }

    Write-Error ("Maturity score check failed. Report: " + $ReportPath)
    exit 1
}
finally {
    Pop-Location
}
