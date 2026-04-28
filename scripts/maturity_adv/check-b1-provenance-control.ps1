[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$AggregateReportPath = "",
    [string]$EvidenceManifestPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b1-provenance-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b1-provenance-report.json"
$AggregateReportPath = Resolve-Bucket1AggregateReportPath -WorkspaceRoot $WorkspaceRoot -AggregateReportPath $AggregateReportPath
$EvidenceManifestPath = Resolve-Bucket1EvidenceManifestPath -WorkspaceRoot $WorkspaceRoot -AggregateReportPath $AggregateReportPath -EvidenceManifestPath $EvidenceManifestPath

$checks = @()
$allowedProvenanceGrades = @("git-traceable", "ci-traceable", "local-unanchored")

$aggregateMetadata = Get-MaturityReportMetadata $AggregateReportPath
$aggregateExists = $aggregateMetadata.exists -and [string]::IsNullOrWhiteSpace([string]$aggregateMetadata.parseError)
$aggregateReport = if ($aggregateExists) { Read-MaturityJsonFile $AggregateReportPath } else { $null }
$aggregateSchema = Test-PrioritizedControlObjectProperties -Value $aggregateReport -RequiredProperties @(
    "generatedAt",
    "runId",
    "releaseRunId",
    "workspaceRoot",
    "overallStatus",
    "evidenceRoot",
    "provenanceGrade",
    "traceabilitySatisfied",
    "commitIdentity",
    "authoritativeDecision",
    "steps",
    "copiedEvidence"
)

$manifestMetadata = if ([string]::IsNullOrWhiteSpace($EvidenceManifestPath)) {
    [pscustomobject]@{ exists = $false; parseError = $null }
}
else {
    Get-MaturityReportMetadata $EvidenceManifestPath
}
$manifestExists = $manifestMetadata.exists -and [string]::IsNullOrWhiteSpace([string]$manifestMetadata.parseError)
$evidenceManifest = if ($manifestExists) { Read-MaturityJsonFile $EvidenceManifestPath } else { $null }
$manifestSchema = Test-PrioritizedControlObjectProperties -Value $evidenceManifest -RequiredProperties @(
    "generatedAt",
    "runId",
    "releaseRunId",
    "workspaceRoot",
    "evidenceRoot",
    "authoritativeReport",
    "provenanceGrade",
    "commitIdentity",
    "environmentFingerprint",
    "files"
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
    -Expectation "Aggregate report must expose the required provenance fields." `
    -Summary $(if ($aggregateExists -and $aggregateSchema.valid) { "Aggregate report schema includes the required provenance fields." } else { "Aggregate report is missing one or more required provenance fields." }) `
    -Data @{
        path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        missingProperties = $aggregateSchema.missing
    }

$checks += New-MaturityCheck `
    -Name "evidence-manifest" `
    -Status $(if ($manifestExists) { "passed" } else { "failed" }) `
    -Expectation "Evidence manifest must exist and parse successfully." `
    -Summary $(if ($manifestExists) { "Evidence manifest is readable." } else { "Evidence manifest is missing or unreadable." }) `
    -Data @{
        path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $EvidenceManifestPath
        parseError = $manifestMetadata.parseError
    }

$checks += New-MaturityCheck `
    -Name "manifest-schema" `
    -Status $(if ($manifestExists -and $manifestSchema.valid) { "passed" } else { "failed" }) `
    -Expectation "Evidence manifest must expose the required provenance fields." `
    -Summary $(if ($manifestExists -and $manifestSchema.valid) { "Evidence manifest schema includes the required provenance fields." } else { "Evidence manifest is missing one or more required provenance fields." }) `
    -Data @{
        path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $EvidenceManifestPath
        missingProperties = $manifestSchema.missing
    }

$aggregateEvidenceRoot = Get-PrioritizedControlStringProperty -Value $aggregateReport -PropertyName "evidenceRoot"
$manifestEvidenceRoot = Get-PrioritizedControlStringProperty -Value $evidenceManifest -PropertyName "evidenceRoot"
$aggregateRunId = Get-PrioritizedControlStringProperty -Value $aggregateReport -PropertyName "runId"
$aggregateReleaseRunId = Get-PrioritizedControlStringProperty -Value $aggregateReport -PropertyName "releaseRunId"
$manifestRunId = Get-PrioritizedControlStringProperty -Value $evidenceManifest -PropertyName "runId"
$manifestReleaseRunId = Get-PrioritizedControlStringProperty -Value $evidenceManifest -PropertyName "releaseRunId"
$aggregateProvenanceGrade = Get-PrioritizedControlStringProperty -Value $aggregateReport -PropertyName "provenanceGrade"
$manifestProvenanceGrade = Get-PrioritizedControlStringProperty -Value $evidenceManifest -PropertyName "provenanceGrade"
$aggregateTraceabilitySatisfied = if ($null -eq $aggregateReport) { $null } else { $aggregateReport.traceabilitySatisfied }
$expectedTraceabilitySatisfied = Get-PrioritizedExpectedTraceabilitySatisfied -ProvenanceGrade $aggregateProvenanceGrade

$runAgreementPassed = $aggregateExists -and $manifestExists -and `
    -not [string]::IsNullOrWhiteSpace($aggregateRunId) -and `
    -not [string]::IsNullOrWhiteSpace($aggregateReleaseRunId) -and `
    $aggregateRunId -eq $aggregateReleaseRunId -and `
    $aggregateRunId -eq $manifestRunId -and `
    $aggregateReleaseRunId -eq $manifestReleaseRunId
$checks += New-MaturityCheck `
    -Name "run-id-agreement" `
    -Status $(if ($runAgreementPassed) { "passed" } else { "failed" }) `
    -Expectation "Aggregate report and evidence manifest must agree on runId and releaseRunId." `
    -Summary $(if ($runAgreementPassed) { "Aggregate report and manifest agree on the release run identity." } else { "Aggregate report and manifest disagree on runId or releaseRunId." }) `
    -Data @{
        aggregateRunId = $aggregateRunId
        aggregateReleaseRunId = $aggregateReleaseRunId
        manifestRunId = $manifestRunId
        manifestReleaseRunId = $manifestReleaseRunId
    }

$evidenceRootAgreementPassed = $aggregateExists -and $manifestExists -and `
    -not [string]::IsNullOrWhiteSpace($aggregateEvidenceRoot) -and `
    $aggregateEvidenceRoot -eq $manifestEvidenceRoot
$checks += New-MaturityCheck `
    -Name "evidence-root-agreement" `
    -Status $(if ($evidenceRootAgreementPassed) { "passed" } else { "failed" }) `
    -Expectation "Aggregate report and evidence manifest must agree on the evidence root." `
    -Summary $(if ($evidenceRootAgreementPassed) { "Aggregate report and manifest agree on the evidence root." } else { "Aggregate report and manifest disagree on the evidence root." }) `
    -Data @{
        aggregateEvidenceRoot = $aggregateEvidenceRoot
        manifestEvidenceRoot = $manifestEvidenceRoot
    }

$authoritativeReportPath = Get-PrioritizedControlStringProperty -Value $evidenceManifest -PropertyName "authoritativeReport"
$authoritativeReportAgreementPassed = $manifestExists -and -not [string]::IsNullOrWhiteSpace($authoritativeReportPath) -and `
    $authoritativeReportPath -eq "scripts\reports\out\beta-release-gate-report.json"
$checks += New-MaturityCheck `
    -Name "authoritative-report" `
    -Status $(if ($authoritativeReportAgreementPassed) { "passed" } else { "failed" }) `
    -Expectation "Evidence manifest must point back to the aggregate beta release gate report." `
    -Summary $(if ($authoritativeReportAgreementPassed) { "Evidence manifest points to the aggregate beta release gate report." } else { "Evidence manifest does not point to the aggregate beta release gate report." }) `
    -Data @{
        authoritativeReport = $authoritativeReportPath
    }

$provenanceGradeAgreementPassed = $aggregateExists -and $manifestExists -and `
    $aggregateProvenanceGrade -in $allowedProvenanceGrades -and `
    $aggregateProvenanceGrade -eq $manifestProvenanceGrade
$checks += New-MaturityCheck `
    -Name "provenance-grade" `
    -Status $(if ($provenanceGradeAgreementPassed) { "passed" } else { "failed" }) `
    -Expectation "Aggregate report and evidence manifest must agree on a supported provenance grade." `
    -Summary $(if ($provenanceGradeAgreementPassed) { "Aggregate report and manifest agree on a supported provenance grade." } else { "Aggregate report and manifest do not agree on a supported provenance grade." }) `
    -Data @{
        aggregateProvenanceGrade = $aggregateProvenanceGrade
        manifestProvenanceGrade = $manifestProvenanceGrade
        allowed = $allowedProvenanceGrades
    }

$traceabilityAgreementPassed = $aggregateExists -and ($aggregateTraceabilitySatisfied -eq $expectedTraceabilitySatisfied)
$checks += New-MaturityCheck `
    -Name "traceability-agreement" `
    -Status $(if ($traceabilityAgreementPassed) { "passed" } else { "failed" }) `
    -Expectation "traceabilitySatisfied must exactly match the provenance-grade rule." `
    -Summary $(if ($traceabilityAgreementPassed) { "traceabilitySatisfied matches the provenance-grade rule." } else { "traceabilitySatisfied does not match the provenance-grade rule." }) `
    -Data @{
        provenanceGrade = $aggregateProvenanceGrade
        traceabilitySatisfied = $aggregateTraceabilitySatisfied
        expectedTraceabilitySatisfied = $expectedTraceabilitySatisfied
    }

$aggregateCommitIdentity = if ($null -eq $aggregateReport) { $null } else { $aggregateReport.commitIdentity }
$manifestCommitIdentity = if ($null -eq $evidenceManifest) { $null } else { $evidenceManifest.commitIdentity }
$aggregateCommitIdentitySchema = Test-PrioritizedControlObjectProperties -Value $aggregateCommitIdentity -RequiredProperties @(
    "available",
    "source",
    "commitSha",
    "branch",
    "dirty",
    "runId",
    "runAttempt",
    "workflow"
)
$manifestCommitIdentitySchema = Test-PrioritizedControlObjectProperties -Value $manifestCommitIdentity -RequiredProperties @(
    "available",
    "source",
    "commitSha",
    "branch",
    "dirty",
    "runId",
    "runAttempt",
    "workflow"
)
$commitIdentityMatchPassed = $aggregateCommitIdentitySchema.valid -and $manifestCommitIdentitySchema.valid -and `
    (ConvertTo-Json $aggregateCommitIdentity -Depth 10) -eq (ConvertTo-Json $manifestCommitIdentity -Depth 10)
$aggregateCommitSha = Get-PrioritizedControlStringProperty -Value $aggregateCommitIdentity -PropertyName "commitSha"
$aggregateCommitSource = Get-PrioritizedControlStringProperty -Value $aggregateCommitIdentity -PropertyName "source"
$commitIdentityConsistencyPassed = $false
switch ($aggregateProvenanceGrade) {
    "ci-traceable" {
        $commitIdentityConsistencyPassed = $commitIdentityMatchPassed -and `
            [bool]$aggregateCommitIdentity.available -and `
            -not [string]::IsNullOrWhiteSpace($aggregateCommitSha) -and `
            $aggregateCommitSource -eq "github-actions"
    }
    "git-traceable" {
        $commitIdentityConsistencyPassed = $commitIdentityMatchPassed -and `
            [bool]$aggregateCommitIdentity.available -and `
            -not [string]::IsNullOrWhiteSpace($aggregateCommitSha)
    }
    "local-unanchored" {
        $commitIdentityConsistencyPassed = $commitIdentityMatchPassed -and `
            (-not [bool]$aggregateCommitIdentity.available) -and `
            [string]::IsNullOrWhiteSpace($aggregateCommitSha)
    }
    default {
        $commitIdentityConsistencyPassed = $false
    }
}
$checks += New-MaturityCheck `
    -Name "commit-identity" `
    -Status $(if ($commitIdentityConsistencyPassed) { "passed" } else { "failed" }) `
    -Expectation "Commit identity must match between the aggregate report and the evidence manifest and must be consistent with the provenance grade." `
    -Summary $(if ($commitIdentityConsistencyPassed) { "Commit identity is internally consistent with the provenance grade." } else { "Commit identity is missing, contradictory, or disagrees across the aggregate report and evidence manifest." }) `
    -Data @{
        aggregateCommitIdentity = $aggregateCommitIdentity
        manifestCommitIdentity = $manifestCommitIdentity
        aggregateCommitIdentityMissing = $aggregateCommitIdentitySchema.missing
        manifestCommitIdentityMissing = $manifestCommitIdentitySchema.missing
    }

$provenanceDecisionStatus = switch ($aggregateProvenanceGrade) {
    "git-traceable" { "passed" }
    "ci-traceable" { "passed" }
    "local-unanchored" { "warning" }
    default { "failed" }
}
$checks += New-MaturityCheck `
    -Name "provenance-decision" `
    -Status $provenanceDecisionStatus `
    -Expectation "Traceable provenance passes, local-unanchored provenance remains diagnostic-only, and contradictory provenance fails." `
    -Summary $(switch ($provenanceDecisionStatus) {
            "passed" { "The current release evidence is traceable and release-eligible." }
            "warning" { "The current release evidence is internally valid but diagnostic-only because it is local-unanchored." }
            default { "The current release evidence has an unsupported or contradictory provenance grade." }
        }) `
    -Data @{
        provenanceGrade = $aggregateProvenanceGrade
        traceabilitySatisfied = $aggregateTraceabilitySatisfied
    }

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B1" `
    -ControlId "B1-PROVENANCE" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $EvidenceManifestPath
    ) `
    -Checks $checks `
    -Extra @{
        aggregateReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        evidenceManifestPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $EvidenceManifestPath
        provenanceGrade = $aggregateProvenanceGrade
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
