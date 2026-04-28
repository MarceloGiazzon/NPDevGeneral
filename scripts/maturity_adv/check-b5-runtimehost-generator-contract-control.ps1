[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$PolicyPath = "",
    [string]$BuildTemplatePath = "",
    [string]$SampleBuildPath = "",
    [string]$RuntimehostReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

function Find-PatternHitsInTree {
    param(
        [string]$RootPath,
        [string[]]$Patterns
    )

    if (-not (Test-Path -LiteralPath $RootPath -PathType Container)) {
        return @()
    }

    $files = @(Get-ChildItem -LiteralPath $RootPath -Recurse -File -Include @("*.java", "*.kt", "*.groovy") -ErrorAction SilentlyContinue)
    $hits = [System.Collections.Generic.List[object]]::new()
    foreach ($pattern in $Patterns) {
        $escapedPattern = [regex]::Escape($pattern)
        foreach ($match in (Select-String -LiteralPath $files.FullName -Pattern $escapedPattern -ErrorAction SilentlyContinue)) {
            [void]$hits.Add([pscustomobject]@{
                    pattern = $pattern
                    path = $match.Path
                    lineNumber = $match.LineNumber
                    line = $match.Line.Trim()
                })
        }
    }

    return @($hits)
}

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b5-runtimehost-generator-contract-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b5-runtimehost-generator-contract-report.json"
$PolicyPath = if ([string]::IsNullOrWhiteSpace($PolicyPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\runtimehost-template-dependency-map.json"
}
else {
    Normalize-NPDevPath $PolicyPath
}
$BuildTemplatePath = if ([string]::IsNullOrWhiteSpace($BuildTemplatePath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template"
}
else {
    Normalize-NPDevPath $BuildTemplatePath
}
$SampleBuildPath = if ([string]::IsNullOrWhiteSpace($SampleBuildPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\simple-contact-intake\Output\App\build.gradle"
}
else {
    Normalize-NPDevPath $SampleBuildPath
}
$RuntimehostReportPath = if ([string]::IsNullOrWhiteSpace($RuntimehostReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json"
}
else {
    Normalize-NPDevPath $RuntimehostReportPath
}

$checks = @()
$policy = Read-MaturityJsonFile $PolicyPath
$policySchema = Test-PrioritizedControlObjectProperties -Value $policy -RequiredProperties @("version", "contracts", "migrationLayout")
$checks += New-MaturityCheck `
    -Name "dependency-policy" `
    -Status $(if ($null -ne $policy -and $policySchema.valid) { "passed" } else { "failed" }) `
    -Expectation "Bucket 1 RuntimeHost/Generator contract control must be backed by an explicit policy map." `
    -Summary $(if ($null -ne $policy -and $policySchema.valid) { "RuntimeHost template dependency map is readable and exposes the required sections." } else { "RuntimeHost template dependency map is missing or invalid." }) `
    -Data @{
        policyPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $PolicyPath
        missingProperties = $policySchema.missing
    }

$buildTemplateContent = if (Test-Path -LiteralPath $BuildTemplatePath -PathType Leaf) { Get-Content -LiteralPath $BuildTemplatePath -Raw } else { "" }
$sampleBuildContent = if (Test-Path -LiteralPath $SampleBuildPath -PathType Leaf) { Get-Content -LiteralPath $SampleBuildPath -Raw } else { "" }
$runtimehostReport = Read-MaturityJsonFile $RuntimehostReportPath

$contractAudits = [System.Collections.Generic.List[object]]::new()
foreach ($contract in @(if ($null -eq $policy) { @() } else { @($policy.contracts) })) {
    $contractSchema = Test-PrioritizedControlObjectProperties -Value $contract -RequiredProperties @(
        "id",
        "sampleSearchRoot",
        "requiredImportPatterns",
        "requiredGradleCoordinates"
    )
    $sampleSearchRoot = if ($contractSchema.valid) {
        Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$contract.sampleSearchRoot)
    }
    else {
        $null
    }
    $requiredImportPatterns = if ($contractSchema.valid) { @($contract.requiredImportPatterns | ForEach-Object { [string]$_ }) } else { @() }
    $requiredGradleCoordinates = if ($contractSchema.valid) { @($contract.requiredGradleCoordinates | ForEach-Object { [string]$_ }) } else { @() }
    $patternHits = @(Find-PatternHitsInTree -RootPath $sampleSearchRoot -Patterns $requiredImportPatterns)
    $missingImportPatterns = @($requiredImportPatterns | Where-Object { $_ -notin @($patternHits | ForEach-Object { [string]$_.pattern }) })
    $missingTemplateCoordinates = @($requiredGradleCoordinates | Where-Object { $buildTemplateContent -notmatch [regex]::Escape($_) })
    $missingSampleCoordinates = @($requiredGradleCoordinates | Where-Object { $sampleBuildContent -notmatch [regex]::Escape($_) })

    [void]$contractAudits.Add([pscustomobject]@{
            id = [string]$contract.id
            schemaValid = $contractSchema.valid
            missingProperties = $contractSchema.missing
            sampleSearchRoot = if ($null -eq $sampleSearchRoot) { $null } else { Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $sampleSearchRoot }
            missingImportPatterns = $missingImportPatterns
            missingTemplateCoordinates = $missingTemplateCoordinates
            missingSampleCoordinates = $missingSampleCoordinates
            patternHits = @($patternHits | Select-Object -First 20)
        })
}

$checks += New-MaturityCheck `
    -Name "template-dependency-coverage" `
    -Status $(if ($contractAudits.Count -gt 0 -and @($contractAudits | Where-Object { -not $_.schemaValid -or @($_.missingImportPatterns).Count -gt 0 -or @($_.missingTemplateCoordinates).Count -gt 0 }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Policy-mapped generated import expectations must be present in the assembled canary app and covered by the RuntimeHost build template dependencies." `
    -Summary $(if ($contractAudits.Count -gt 0 -and @($contractAudits | Where-Object { -not $_.schemaValid -or @($_.missingImportPatterns).Count -gt 0 -or @($_.missingTemplateCoordinates).Count -gt 0 }).Count -eq 0) { "Policy-mapped generated import expectations are covered by the RuntimeHost build template." } else { "One or more policy-mapped generated import expectations are missing from the RuntimeHost build template contract." }) `
    -Data @{
        invalidContracts = @($contractAudits | Where-Object { -not $_.schemaValid -or @($_.missingImportPatterns).Count -gt 0 -or @($_.missingTemplateCoordinates).Count -gt 0 })
    }

$checks += New-MaturityCheck `
    -Name "generated-app-dependency-propagation" `
    -Status $(if ($contractAudits.Count -gt 0 -and @($contractAudits | Where-Object { @($_.missingSampleCoordinates).Count -gt 0 }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Policy-mapped RuntimeHost template dependencies must propagate into the assembled canary app build.gradle." `
    -Summary $(if ($contractAudits.Count -gt 0 -and @($contractAudits | Where-Object { @($_.missingSampleCoordinates).Count -gt 0 }).Count -eq 0) { "RuntimeHost template dependencies propagate into the assembled canary app build.gradle." } else { "One or more RuntimeHost template dependencies do not propagate into the assembled canary app build.gradle." }) `
    -Data @{
        invalidContracts = @($contractAudits | Where-Object { @($_.missingSampleCoordinates).Count -gt 0 })
        sampleBuildPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleBuildPath
    }

$migrationLayout = if ($null -eq $policy) { $null } else { $policy.migrationLayout }
$migrationLayoutSchema = Test-PrioritizedControlObjectProperties -Value $migrationLayout -RequiredProperties @(
    "sampleAppRoot",
    "canonicalMigrationRoot",
    "forbiddenGeneratedMigrationRoot",
    "verificationTasks"
)
$sampleAppRoot = if ($migrationLayoutSchema.valid) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$migrationLayout.sampleAppRoot)
}
else {
    $null
}
$canonicalMigrationRoot = if ($migrationLayoutSchema.valid) {
    Join-Path $sampleAppRoot ([string]$migrationLayout.canonicalMigrationRoot)
}
else {
    $null
}
$forbiddenGeneratedMigrationRoot = if ($migrationLayoutSchema.valid) {
    Join-Path $sampleAppRoot ([string]$migrationLayout.forbiddenGeneratedMigrationRoot)
}
else {
    $null
}
$canonicalMigrationFiles = @(
    if ($null -ne $canonicalMigrationRoot -and (Test-Path -LiteralPath $canonicalMigrationRoot -PathType Container)) {
        Get-ChildItem -LiteralPath $canonicalMigrationRoot -Filter "*.sql" -File -ErrorAction SilentlyContinue
    }
)
$forbiddenGeneratedMigrationFiles = @(
    if ($null -ne $forbiddenGeneratedMigrationRoot -and (Test-Path -LiteralPath $forbiddenGeneratedMigrationRoot -PathType Container)) {
        Get-ChildItem -LiteralPath $forbiddenGeneratedMigrationRoot -Filter "*.sql" -File -Recurse -ErrorAction SilentlyContinue
    }
)

$checks += New-MaturityCheck `
    -Name "migration-layout-contract" `
    -Status $(if ($migrationLayoutSchema.valid -and $canonicalMigrationFiles.Count -gt 0 -and $forbiddenGeneratedMigrationFiles.Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "The assembled canary app must keep runtime migrations in the canonical source root and must not package generated db/migration content." `
    -Summary $(if ($migrationLayoutSchema.valid -and $canonicalMigrationFiles.Count -gt 0 -and $forbiddenGeneratedMigrationFiles.Count -eq 0) { "The assembled canary app preserves the single migration-layout contract." } else { "The assembled canary app does not satisfy the single migration-layout contract." }) `
    -Data @{
        sampleAppRoot = if ($null -eq $sampleAppRoot) { $null } else { Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $sampleAppRoot }
        canonicalMigrationRoot = if ($null -eq $canonicalMigrationRoot) { $null } else { Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $canonicalMigrationRoot }
        forbiddenGeneratedMigrationRoot = if ($null -eq $forbiddenGeneratedMigrationRoot) { $null } else { Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $forbiddenGeneratedMigrationRoot }
        canonicalMigrationCount = $canonicalMigrationFiles.Count
        forbiddenGeneratedMigrationCount = $forbiddenGeneratedMigrationFiles.Count
        missingProperties = $migrationLayoutSchema.missing
    }

$runtimehostVerificationDisplay = if ($null -eq $runtimehostReport -or $null -eq $runtimehostReport.verificationCommand) { $null } else { [string]$runtimehostReport.verificationCommand.display }
$expectedVerificationTasks = if ($null -eq $migrationLayout -or $null -eq $migrationLayout.verificationTasks) { @() } else { @($migrationLayout.verificationTasks | ForEach-Object { [string]$_ }) }
$missingVerificationTasks = @($expectedVerificationTasks | Where-Object { [string]::IsNullOrWhiteSpace($runtimehostVerificationDisplay) -or $runtimehostVerificationDisplay -notmatch [regex]::Escape($_) })
$runtimehostContractPassed = $null -ne $runtimehostReport -and `
    ([string]$runtimehostReport.overallStatus -eq "passed") -and `
    (@($missingVerificationTasks).Count -eq 0) -and `
    ($null -ne $runtimehostReport.generationMarker) -and `
    [bool]$runtimehostReport.generationMarker.runIdMatches
$checks += New-MaturityCheck `
    -Name "runtimehost-canary-guardrail" `
    -Status $(if ($runtimehostContractPassed) { "passed" } else { "failed" }) `
    -Expectation "The current RuntimeHost canary must stay green and must prove the expected verification task contract." `
    -Summary $(if ($runtimehostContractPassed) { "The RuntimeHost canary remains green and proves the expected verification tasks." } else { "The RuntimeHost canary is missing, not green, or does not prove the expected verification tasks." }) `
    -Data @{
        runtimehostReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $RuntimehostReportPath
        overallStatus = if ($null -eq $runtimehostReport) { $null } else { [string]$runtimehostReport.overallStatus }
        verificationDisplay = $runtimehostVerificationDisplay
        missingVerificationTasks = $missingVerificationTasks
        generationMarker = if ($null -eq $runtimehostReport) { $null } else { $runtimehostReport.generationMarker }
    }

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B1" `
    -ControlId "B5-RUNTIMEHOST-GENERATOR-CONTRACT" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $PolicyPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $BuildTemplatePath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleBuildPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $RuntimehostReportPath
    ) `
    -Checks $checks `
    -Extra @{
        contractCount = $contractAudits.Count
        sampleBuildPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleBuildPath
        runtimehostReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $RuntimehostReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
