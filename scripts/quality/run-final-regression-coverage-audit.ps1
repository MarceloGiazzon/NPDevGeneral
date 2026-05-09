param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/final-regression-coverage-audit-report.json",
    [string]$PolicyPath = "scripts/policy/beta-release-gate-policy.json",
    [string]$ScopePath = "scripts/policy/beta0-scope.json",
    [string]$CoverageManifestPath = "scripts/policy/final-regression-coverage-manifest.json",
    [string]$FinalReleaseScriptPath = "scripts/quality/run-beta0-final-release-check.ps1",
    [string]$ReportSchemaValidationScriptPath = "scripts/quality/run-report-schema-validation.ps1",
    [string]$SampleMatrixReportPath = "scripts/reports/out/sample-matrix-report.json",
    [string]$BetaReleaseGateReportPath = "scripts/reports/out/beta-release-gate-report.json"
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-TextFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return "" }
    return Get-Content -Raw -LiteralPath $Path
}

function Get-Basename {
    param([string]$PathValue)
    return [System.IO.Path]::GetFileName(([string]$PathValue).Replace("\", "/"))
}

function Resolve-UnderWorkspace {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot $PathValue))
}

function Get-ReportPropertyValue {
    param([object]$Report, [string]$PropertyPath)
    $value = $Report
    foreach ($segment in @(([string]$PropertyPath) -split "\.")) {
        if ($null -eq $value) { return $null }
        $property = $value.PSObject.Properties[$segment]
        if ($null -eq $property) { return $null }
        $value = $property.Value
    }
    return $value
}

function Get-ReportNames {
    param([object]$Report, [string]$CollectionName)
    $property = $Report.PSObject.Properties[$CollectionName]
    if ($null -eq $property) { return @() }
    return @($property.Value | ForEach-Object {
            if ($null -eq $_) { return }
            if ($_.PSObject.Properties.Name -contains "name") { [string]$_.name }
            else { [string]$_ }
        } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Test-ReportEvidence {
    param([object]$Definition)
    $reportPath = [string]$Definition.path
    $fullPath = Resolve-UnderWorkspace $reportPath
    $report = Read-JsonFile $fullPath
    $errors = [System.Collections.Generic.List[string]]::new()
    if ($null -eq $report) {
        $errors.Add("Report is missing: $reportPath") | Out-Null
        return [pscustomobject]@{ passed = $false; errors = @($errors); evidence = [pscustomobject]@{ path = $reportPath; exists = $false } }
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$Definition.schemaVersion) -and [string]$report.schemaVersion -ne [string]$Definition.schemaVersion) {
        $errors.Add("Report schemaVersion mismatch: $reportPath") | Out-Null
    }
    $statusProperty = [string]$Definition.statusProperty
    $actualStatus = if (-not [string]::IsNullOrWhiteSpace($statusProperty)) { [string](Get-ReportPropertyValue $report $statusProperty) } else { "" }
    $allowFailure = $Definition.PSObject.Properties.Name -contains "allowCurrentFailureAsReleaseBlocker" -and [bool]$Definition.allowCurrentFailureAsReleaseBlocker
    if (-not $allowFailure -and -not [string]::IsNullOrWhiteSpace($statusProperty) -and $actualStatus -ne [string]$Definition.passValue) {
        $errors.Add("Report status mismatch: $reportPath expected $($Definition.passValue) but got $actualStatus") | Out-Null
    }
    foreach ($expected in @($Definition.requiredAssertionNames | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })) {
        $names = @(Get-ReportNames $report "assertions")
        $objectNames = @()
        $assertionsProperty = $report.PSObject.Properties["assertions"]
        if ($null -ne $assertionsProperty -and $assertionsProperty.Value.PSObject.Properties.Name -contains "names") {
            $objectNames = @($assertionsProperty.Value.names | ForEach-Object { [string]$_ })
        }
        $allNames = @($names + $objectNames | Sort-Object -Unique)
        if ($allNames -notcontains [string]$expected) {
            $errors.Add("Required assertion missing from ${reportPath}: $expected") | Out-Null
        }
    }
    foreach ($expected in @($Definition.requiredCaseNames | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })) {
        $names = @(Get-ReportNames $report "cases")
        if ($names -notcontains [string]$expected) {
            $errors.Add("Required case missing from ${reportPath}: $expected") | Out-Null
        }
    }
    foreach ($expected in @($Definition.requiredTestedReportBasenames | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })) {
        $basenames = @($report.testedReports | ForEach-Object { Get-Basename $_ })
        if ($basenames -notcontains [string]$expected) {
            $errors.Add("Required tested report missing from ${reportPath}: $expected") | Out-Null
        }
    }
    foreach ($expected in @($Definition.requiredTopLevelProperties | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })) {
        if ($report.PSObject.Properties.Name -notcontains [string]$expected) {
            $errors.Add("Required top-level property missing from ${reportPath}: $expected") | Out-Null
        }
    }
    return [pscustomobject]@{
        passed = $errors.Count -eq 0
        errors = @($errors)
        allowCurrentFailureAsReleaseBlocker = $allowFailure
        evidence = [pscustomobject]@{
            path = $reportPath
            exists = $true
            schemaVersion = [string]$report.schemaVersion
            statusProperty = $statusProperty
            actualStatus = $actualStatus
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant()
        }
    }
}

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [bool]$ReleaseBlocking,
        [string]$Reason,
        [object]$Evidence
    )
    $script:checks += [pscustomobject]@{
        name = $Name
        passed = $Passed
        releaseBlocking = $ReleaseBlocking
        reason = $Reason
        evidence = $Evidence
    }
    if ($ReleaseBlocking -and -not $Passed) {
        $script:failures.Add(("Check failed: " + $Name + " - " + $Reason)) | Out-Null
    }
}

function Test-TextContainsAll {
    param([string]$Text, [string[]]$Needles)
    foreach ($needle in @($Needles)) {
        if ($Text -notmatch [regex]::Escape($needle)) { return $false }
    }
    return $true
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "final-regression-coverage-audit-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$script:checks = @()
$script:failures = [System.Collections.Generic.List[string]]::new()
$releaseBlockers = [System.Collections.Generic.List[object]]::new()
$coverageItems = @()

$schemaPaths = @(
    "schemas/ai/direct-evidence-hardening-tests-report.schema.json",
    "schemas/ai/traceable-local-release-report.schema.json",
    "schemas/ai/roadmap-closure-check-report.schema.json",
    "schemas/ai/runbook-workflow-alignment-tests-report.schema.json",
    "schemas/ai/final-regression-coverage-audit-report.schema.json"
)
foreach ($schemaPath in $schemaPaths) {
    Add-Check -Name ("schema-exists:" + $schemaPath) -Passed (Test-Path -LiteralPath $schemaPath -PathType Leaf) -ReleaseBlocking $true -Reason ("Regression/governance report schema exists: " + $schemaPath) -Evidence ([pscustomobject]@{ path = $schemaPath })
}

$schemaScriptText = Get-TextFile $ReportSchemaValidationScriptPath
foreach ($schemaPath in $schemaPaths) {
    Add-Check -Name ("schema-validation-case-wired:" + $schemaPath) -Passed ($schemaScriptText -match [regex]::Escape($schemaPath)) -ReleaseBlocking $true -Reason ("Report schema validation covers " + $schemaPath) -Evidence ([pscustomobject]@{ scriptPath = $ReportSchemaValidationScriptPath; schemaPath = $schemaPath })
}

$finalScriptText = Get-TextFile $FinalReleaseScriptPath
$requiredFinalScripts = @(
    "run-direct-evidence-hardening-tests.ps1",
    "run-runbook-workflow-alignment-tests.ps1",
    "run-final-regression-coverage-audit.ps1",
    "run-report-schema-validation.ps1",
    "run-doc-entrypoint-validation.ps1",
    "run-report-provenance-tests.ps1"
)
foreach ($scriptName in $requiredFinalScripts) {
    Add-Check -Name ("canonical-final-release-runs:" + $scriptName) -Passed ($finalScriptText -match [regex]::Escape($scriptName)) -ReleaseBlocking $true -Reason ("Canonical final release sequence includes " + $scriptName) -Evidence ([pscustomobject]@{ scriptPath = $FinalReleaseScriptPath; expectedEntrypoint = $scriptName })
}

$policy = Read-JsonFile $PolicyPath
$scope = Read-JsonFile $ScopePath
$requiredReportNames = @($policy.requiredReports | ForEach-Object { [string]$_.name })
$requiredReportPaths = @($policy.requiredReports | ForEach-Object { [string]$_.path })
$scopeBlockingBasenames = @($scope.blockingReports | ForEach-Object { Get-Basename $_ })

Add-Check -Name "policy-requires-final-regression-audit" -Passed ($requiredReportPaths -contains "scripts/reports/out/final-regression-coverage-audit-report.json") -ReleaseBlocking $true -Reason "Beta release policy requires the final regression coverage audit report." -Evidence ([pscustomobject]@{ policyPath = $PolicyPath; requiredReports = $requiredReportNames })
Add-Check -Name "scope-requires-final-regression-audit" -Passed ($scopeBlockingBasenames -contains "final-regression-coverage-audit-report.json") -ReleaseBlocking $true -Reason "Authoritative beta0 scope includes the final regression coverage audit report." -Evidence ([pscustomobject]@{ scopePath = $ScopePath; blockingReports = $scopeBlockingBasenames })

$manifest = Read-JsonFile (Resolve-UnderWorkspace $CoverageManifestPath)
$manifestLoaded = $null -ne $manifest -and [string]$manifest.schemaVersion -eq "npdev-final-regression-coverage-manifest.v1"
Add-Check -Name "coverage-manifest-loads" -Passed $manifestLoaded -ReleaseBlocking $true -Reason "Final regression coverage audit manifest exists and has the expected schema version." -Evidence ([pscustomobject]@{ manifestPath = $CoverageManifestPath; schemaVersion = if ($null -ne $manifest) { [string]$manifest.schemaVersion } else { "" } })

if ($manifestLoaded) {
    foreach ($item in @($manifest.coverageItems)) {
        $itemErrors = [System.Collections.Generic.List[string]]::new()
        $itemEvidence = [System.Collections.Generic.List[object]]::new()
        foreach ($entrypoint in @($item.finalEntrypoints | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })) {
            $present = $finalScriptText -match [regex]::Escape([string]$entrypoint)
            if (-not $present) {
                $itemErrors.Add("Missing final release path entrypoint: $entrypoint") | Out-Null
            }
            $itemEvidence.Add([pscustomobject]@{ type = "final-entrypoint"; path = [string]$entrypoint; present = $present }) | Out-Null
        }
        foreach ($reportDefinition in @($item.reports | Where-Object { $null -ne $_ -and -not [string]::IsNullOrWhiteSpace([string]$_.path) })) {
            $result = Test-ReportEvidence $reportDefinition
            if (-not [bool]$result.passed) {
                foreach ($reportError in @($result.errors)) {
                    $itemErrors.Add([string]$reportError) | Out-Null
                }
            }
            if ([bool]$result.allowCurrentFailureAsReleaseBlocker -and [string]$result.evidence.actualStatus -ne [string]$reportDefinition.passValue) {
                $releaseBlockers.Add([pscustomobject]@{
                        name = "coverage-report-currently-failed:" + [string]$item.id
                        expected = $true
                        reason = "Coverage evidence exists but the underlying release report is currently failed and remains a release blocker."
                        evidence = $result.evidence
                    }) | Out-Null
            }
            $itemEvidence.Add([pscustomobject]@{ type = "report"; result = $result.evidence; errors = @($result.errors) }) | Out-Null
        }
        foreach ($sourcePattern in @($item.sourcePatterns | Where-Object { $null -ne $_ -and -not [string]::IsNullOrWhiteSpace([string]$_.path) })) {
            $text = Get-TextFile (Resolve-UnderWorkspace ([string]$sourcePattern.path))
            $missing = @($sourcePattern.patterns | Where-Object { $text -notmatch [regex]::Escape([string]$_) })
            if ($missing.Count -gt 0) {
                $itemErrors.Add("Source evidence missing patterns from $($sourcePattern.path): " + ($missing -join ", ")) | Out-Null
            }
            $itemEvidence.Add([pscustomobject]@{ type = "source-pattern"; path = [string]$sourcePattern.path; missingPatterns = @($missing) }) | Out-Null
        }
        foreach ($policyRequirement in @($item.policyRequirements | Where-Object { $null -ne $_ -and -not [string]::IsNullOrWhiteSpace([string]$_.policyPath) })) {
            $policyDoc = Read-JsonFile (Resolve-UnderWorkspace ([string]$policyRequirement.policyPath))
            $requiredReport = @($policyDoc.requiredReports | Where-Object { [string]$_.name -eq [string]$policyRequirement.requiredReportName } | Select-Object -First 1)
            $evidenceRequirement = if ($requiredReport.Count -eq 1) {
                @($requiredReport[0].evidenceRequirements | Where-Object { [string]$_.path -eq [string]$policyRequirement.evidenceRequirementPath } | Select-Object -First 1)
            }
            else { @() }
            $passed = $requiredReport.Count -eq 1 -and $evidenceRequirement.Count -eq 1 -and [bool]$evidenceRequirement[0].releaseBlocking -and [string]$evidenceRequirement[0].expected -eq [string]$policyRequirement.expected
            if (-not $passed) {
                $itemErrors.Add("Policy evidence requirement missing or not release-blocking: $($policyRequirement.requiredReportName).$($policyRequirement.evidenceRequirementPath)") | Out-Null
            }
            $itemEvidence.Add([pscustomobject]@{ type = "policy-requirement"; policyPath = [string]$policyRequirement.policyPath; passed = $passed }) | Out-Null
        }
        $itemPassed = $itemErrors.Count -eq 0
        $coverageItems += [pscustomobject]@{
            id = [string]$item.id
            description = [string]$item.description
            passed = $itemPassed
            evidence = @($itemEvidence)
            failures = @($itemErrors)
        }
        Add-Check -Name ("coverage-item:" + [string]$item.id) -Passed $itemPassed -ReleaseBlocking $true -Reason ([string]$item.description) -Evidence ([pscustomobject]@{ manifestPath = $CoverageManifestPath; failures = @($itemErrors) })
    }
}

$sampleRequired = @($policy.requiredReports | Where-Object { [string]$_.name -eq "sample-matrix" } | Select-Object -First 1)
$sampleRequirement = @($sampleRequired.evidenceRequirements | Where-Object { [string]$_.path -eq "releaseEvidence.eligible" } | Select-Object -First 1)
$sampleReleaseRequirementPresent = $sampleRequirement.Count -eq 1 -and [bool]$sampleRequirement[0].releaseBlocking -and [bool]$sampleRequirement[0].expected
Add-Check -Name "sample-release-evidence-remains-blocking" -Passed $sampleReleaseRequirementPresent -ReleaseBlocking $true -Reason "Sample input-contract success must not satisfy release eligibility without releaseEvidence.eligible=true." -Evidence ([pscustomobject]@{ policyPath = $PolicyPath; sampleEvidenceRequirement = if ($sampleRequirement.Count -eq 1) { $sampleRequirement[0] } else { $null } })

$sampleReport = Read-JsonFile $SampleMatrixReportPath
$sampleReleaseEligible = $null -ne $sampleReport -and $null -ne $sampleReport.releaseEvidence -and [bool]$sampleReport.releaseEvidence.eligible
if (-not $sampleReleaseEligible) {
    $releaseBlockers.Add([pscustomobject]@{
            name = "sample-runtime-generation-release-evidence"
            expected = $true
            reason = "sample-matrix releaseEvidence.eligible is not true; final release readiness must remain blocked until full sample generation/runtime proof exists."
            evidence = [pscustomobject]@{
                reportPath = $SampleMatrixReportPath
                reportExists = $null -ne $sampleReport
                releaseEvidenceEligible = $sampleReleaseEligible
            }
        }) | Out-Null
}

$betaReport = Read-JsonFile $BetaReleaseGateReportPath
if ($null -ne $betaReport -and [string]$betaReport.overallStatus -ne "passed") {
    $releaseBlockers.Add([pscustomobject]@{
            name = "aggregate-beta-release-gate"
            expected = $true
            reason = "Aggregate beta release gate is currently red; the audit may pass coverage while release readiness stays blocked."
            evidence = [pscustomobject]@{
                reportPath = $BetaReleaseGateReportPath
                overallStatus = [string]$betaReport.overallStatus
                blockerCount = @($betaReport.blockers).Count
            }
        }) | Out-Null
}

$coverageStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$releaseReadinessStatus = if ($releaseBlockers.Count -eq 0) { "eligible" } else { "blocked" }
$overallStatus = $coverageStatus

$report = [pscustomobject]@{
    schemaVersion = "npdev-final-regression-coverage-audit-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-final-regression-coverage-audit.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    coverageStatus = $coverageStatus
    releaseReadinessStatus = $releaseReadinessStatus
    coverageManifestPath = $CoverageManifestPath
    coverageItems = @($coverageItems)
    checks = @($checks)
    releaseBlockers = @($releaseBlockers)
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Final regression coverage audit passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Final regression coverage audit failed. Report: " + $ReportPath)
