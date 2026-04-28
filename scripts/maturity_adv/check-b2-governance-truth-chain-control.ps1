[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$AggregateReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b2-governance-truth-chain-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b2-governance-truth-chain-report.json"
$AggregateReportPath = Resolve-Bucket1AggregateReportPath -WorkspaceRoot $WorkspaceRoot -AggregateReportPath $AggregateReportPath

$checks = @()
$aggregateMetadata = Get-MaturityReportMetadata $AggregateReportPath
$aggregateExists = $aggregateMetadata.exists -and [string]::IsNullOrWhiteSpace([string]$aggregateMetadata.parseError)
$aggregateReport = if ($aggregateExists) { Read-MaturityJsonFile $AggregateReportPath } else { $null }
$aggregateSchema = Test-PrioritizedControlObjectProperties -Value $aggregateReport -RequiredProperties @(
    "generatedAt",
    "runId",
    "releaseRunId",
    "workspaceRoot",
    "overallStatus",
    "authoritativeDecision",
    "steps"
)

$checks += New-MaturityCheck `
    -Name "aggregate-report" `
    -Status $(if ($aggregateExists) { "passed" } else { "failed" }) `
    -Expectation "Aggregate beta release gate report must exist and parse successfully." `
    -Summary $(if ($aggregateExists) { "Aggregate beta release gate report is readable." } else { "Aggregate beta release gate report is missing or unreadable." }) `
    -Data @{
        path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        parseError = $aggregateMetadata.parseError
    }

$checks += New-MaturityCheck `
    -Name "aggregate-schema" `
    -Status $(if ($aggregateExists -and $aggregateSchema.valid) { "passed" } else { "failed" }) `
    -Expectation "Aggregate report must expose the required governance truth-chain fields." `
    -Summary $(if ($aggregateExists -and $aggregateSchema.valid) { "Aggregate report schema includes the governance truth-chain fields." } else { "Aggregate report is missing one or more governance truth-chain fields." }) `
    -Data @{
        missingProperties = $aggregateSchema.missing
    }

$authoritativeDecision = if ($null -eq $aggregateReport) { $null } else { $aggregateReport.authoritativeDecision }
$authoritativeDecisionSchema = Test-PrioritizedControlObjectProperties -Value $authoritativeDecision -RequiredProperties @(
    "sourceOfTruth",
    "releaseRunId",
    "rule",
    "staleReportPolicy"
)
$authoritativeDecisionPassed = $aggregateExists -and $authoritativeDecisionSchema.valid -and `
    ([string]$authoritativeDecision.sourceOfTruth -eq "scripts\reports\out\beta-release-gate-report.json") -and `
    ([string]$authoritativeDecision.releaseRunId -eq [string]$aggregateReport.releaseRunId)
$checks += New-MaturityCheck `
    -Name "authoritative-decision" `
    -Status $(if ($authoritativeDecisionPassed) { "passed" } else { "failed" }) `
    -Expectation "Aggregate report must explicitly declare itself as the source of truth for the current release run." `
    -Summary $(if ($authoritativeDecisionPassed) { "Aggregate authoritativeDecision matches the current release run and report path." } else { "Aggregate authoritativeDecision is missing or disagrees with the current release run." }) `
    -Data @{
        missingProperties = $authoritativeDecisionSchema.missing
        authoritativeDecision = $authoritativeDecision
    }

$steps = if ($null -eq $aggregateReport) { @() } else { @($aggregateReport.steps) }
$stepAudits = [System.Collections.Generic.List[object]]::new()
foreach ($step in $steps) {
    $stepSchema = Test-PrioritizedControlObjectProperties -Value $step -RequiredProperties @(
        "name",
        "script",
        "status",
        "exitDisposition",
        "childReportDisposition",
        "finalDecisionReason",
        "startedAt",
        "endedAt"
    )

    $childReportPath = $null
    $childReportObject = $null
    $childMetadata = [pscustomobject]@{ exists = $false; parseError = $null }
    $childSchema = [pscustomobject]@{ valid = $false; missing = @() }
    $childRunIdMatches = $false
    $childWorkspaceMatches = $false
    $childScriptPathMatches = $false
    $crossFieldAgreement = $true

    if (-not [string]::IsNullOrWhiteSpace([string]$step.reportPath)) {
        $childReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$step.reportPath)
        $childMetadata = Get-MaturityReportMetadata $childReportPath
        if ($childMetadata.exists -and [string]::IsNullOrWhiteSpace([string]$childMetadata.parseError)) {
            $childReportObject = Read-MaturityJsonFile $childReportPath
            $childSchema = Test-PrioritizedControlObjectProperties -Value $childReportObject -RequiredProperties @(
                "generatedAt",
                "runId",
                "scriptPath",
                "workspaceRoot",
                "overallStatus"
            )
            $childRunIdMatches = $childSchema.valid -and ([string]$childReportObject.runId -eq [string]$aggregateReport.runId)
            $childWorkspaceMatches = $childSchema.valid -and ([string]$childReportObject.workspaceRoot -eq [string]$aggregateReport.workspaceRoot)
            $childScriptPathMatches = $childSchema.valid -and ([string]$childReportObject.scriptPath -eq [string]$step.reportScriptPath)
            if ([string]$step.childReportDisposition -eq "current" -and [string]$step.exitDisposition -eq "passed") {
                $crossFieldAgreement = ([string]$childReportObject.overallStatus -eq [string]$step.status)
            }
        }
        else {
            $crossFieldAgreement = $false
        }
    }

    [void]$stepAudits.Add([pscustomobject]@{
            name = [string]$step.name
            stepSchemaValid = $stepSchema.valid
            stepMissingProperties = $stepSchema.missing
            reportPath = if ($null -eq $childReportPath) { $null } else { Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $childReportPath }
            childReportExists = $childMetadata.exists
            childReportParseError = $childMetadata.parseError
            childSchemaValid = $childSchema.valid
            childMissingProperties = $childSchema.missing
            childRunIdMatches = $childRunIdMatches
            childWorkspaceMatches = $childWorkspaceMatches
            childScriptPathMatches = $childScriptPathMatches
            crossFieldAgreement = $crossFieldAgreement
            childStatus = if ($null -eq $childReportObject) { $null } else { [string]$childReportObject.overallStatus }
            aggregateStepStatus = [string]$step.status
        })
}

$requiredChildSteps = @($stepAudits | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.reportPath) })
$checks += New-MaturityCheck `
    -Name "step-schema" `
    -Status $(if ($stepAudits.Count -gt 0 -and @($stepAudits | Where-Object { -not $_.stepSchemaValid }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Every aggregate step must expose the required governance fields." `
    -Summary $(if ($stepAudits.Count -gt 0 -and @($stepAudits | Where-Object { -not $_.stepSchemaValid }).Count -eq 0) { "All aggregate steps expose the required governance fields." } else { "One or more aggregate steps are missing required governance fields." }) `
    -Data @{
        invalidSteps = @($stepAudits | Where-Object { -not $_.stepSchemaValid })
    }

$checks += New-MaturityCheck `
    -Name "child-report-availability" `
    -Status $(if (@($requiredChildSteps | Where-Object { -not $_.childReportExists -or -not [string]::IsNullOrWhiteSpace([string]$_.childReportParseError) }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Every aggregate step with reportPath must resolve to a readable current child report." `
    -Summary $(if (@($requiredChildSteps | Where-Object { -not $_.childReportExists -or -not [string]::IsNullOrWhiteSpace([string]$_.childReportParseError) }).Count -eq 0) { "Every aggregate step reportPath resolves to a readable child report." } else { "One or more aggregate step reportPath values do not resolve to a readable child report." }) `
    -Data @{
        invalidSteps = @($requiredChildSteps | Where-Object { -not $_.childReportExists -or -not [string]::IsNullOrWhiteSpace([string]$_.childReportParseError) })
    }

$checks += New-MaturityCheck `
    -Name "child-report-schema" `
    -Status $(if (@($requiredChildSteps | Where-Object { -not $_.childSchemaValid }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Each child report consumed by the aggregate gate must include runId, generatedAt, scriptPath, workspaceRoot, and overallStatus." `
    -Summary $(if (@($requiredChildSteps | Where-Object { -not $_.childSchemaValid }).Count -eq 0) { "Every consumed child report exposes the required fields." } else { "One or more consumed child reports are missing required fields." }) `
    -Data @{
        invalidSteps = @($requiredChildSteps | Where-Object { -not $_.childSchemaValid })
    }

$checks += New-MaturityCheck `
    -Name "child-run-id-agreement" `
    -Status $(if (@($requiredChildSteps | Where-Object { -not $_.childRunIdMatches }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Each child report consumed by the aggregate gate must share the aggregate runId." `
    -Summary $(if (@($requiredChildSteps | Where-Object { -not $_.childRunIdMatches }).Count -eq 0) { "Every consumed child report shares the aggregate runId." } else { "One or more consumed child reports do not share the aggregate runId." }) `
    -Data @{
        invalidSteps = @($requiredChildSteps | Where-Object { -not $_.childRunIdMatches })
        aggregateRunId = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.runId }
    }

$checks += New-MaturityCheck `
    -Name "child-workspace-root-agreement" `
    -Status $(if (@($requiredChildSteps | Where-Object { -not $_.childWorkspaceMatches }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Each child report consumed by the aggregate gate must agree on workspaceRoot." `
    -Summary $(if (@($requiredChildSteps | Where-Object { -not $_.childWorkspaceMatches }).Count -eq 0) { "Every consumed child report agrees on workspaceRoot." } else { "One or more consumed child reports do not agree on workspaceRoot." }) `
    -Data @{
        invalidSteps = @($requiredChildSteps | Where-Object { -not $_.childWorkspaceMatches })
        aggregateWorkspaceRoot = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.workspaceRoot }
    }

$checks += New-MaturityCheck `
    -Name "child-script-path-agreement" `
    -Status $(if (@($requiredChildSteps | Where-Object { -not $_.childScriptPathMatches }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Each child report consumed by the aggregate gate must agree with the step reportScriptPath." `
    -Summary $(if (@($requiredChildSteps | Where-Object { -not $_.childScriptPathMatches }).Count -eq 0) { "Every consumed child report agrees with the step reportScriptPath." } else { "One or more consumed child reports disagree with the step reportScriptPath." }) `
    -Data @{
        invalidSteps = @($requiredChildSteps | Where-Object { -not $_.childScriptPathMatches })
    }

$checks += New-MaturityCheck `
    -Name "cross-field-agreement" `
    -Status $(if (@($requiredChildSteps | Where-Object { -not $_.crossFieldAgreement }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Aggregate step status must agree with the child report when the child report is current and the child step exited successfully." `
    -Summary $(if (@($requiredChildSteps | Where-Object { -not $_.crossFieldAgreement }).Count -eq 0) { "Aggregate step status agrees with child report status where the child report controls the decision." } else { "One or more aggregate steps disagree with the child report that should control the decision." }) `
    -Data @{
        invalidSteps = @($requiredChildSteps | Where-Object { -not $_.crossFieldAgreement })
    }

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B1" `
    -ControlId "B2-GOVERNANCE-TRUTH-CHAIN" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        @($requiredChildSteps | ForEach-Object { [string]$_.reportPath })
    ) `
    -Checks $checks `
    -Extra @{
        aggregateReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        stepCount = $stepAudits.Count
        childReportCount = $requiredChildSteps.Count
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
