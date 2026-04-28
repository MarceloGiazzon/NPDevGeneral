[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$SampleIds = @(),
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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-presentation-label-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Get-OptionalPropertyValue([object]$Target, [string]$PropertyName) {
    if ($null -eq $Target) {
        return $null
    }

    $property = $Target.PSObject.Properties | Where-Object { $_.Name -eq $PropertyName } | Select-Object -First 1
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

$samples = @(Get-NPDevSampleEntries $WorkspaceRoot)
if ($SampleIds.Count -gt 0) {
    $samples = @($samples | Where-Object { $_.id -in $SampleIds })
}

$findings = [System.Collections.Generic.List[object]]::new()
foreach ($sample in $samples) {
    $sampleId = [string]$sample.id
    if ([string]::IsNullOrWhiteSpace($sampleId)) {
        continue
    }

    $modelPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\model.json")
    Ensure-NPDevFile $modelPath ("Sample model for " + $sampleId)
    $model = Get-Content -LiteralPath $modelPath -Raw | ConvertFrom-Json
    $concepts = @($model.concepts)

    foreach ($concept in $concepts) {
        $conceptName = [string]$concept.name
        $conceptUi = Get-OptionalPropertyValue -Target $concept -PropertyName "ui"
        $conceptLabel = if ($null -eq $conceptUi) { $null } else { [string](Get-OptionalPropertyValue -Target $conceptUi -PropertyName "label") }
        if ([string]::IsNullOrWhiteSpace($conceptLabel)) {
            [void]$findings.Add([pscustomobject]@{
                    sampleId = $sampleId
                    severity = "warning"
                    kind = "concept"
                    concept = $conceptName
                    field = $null
                    path = "concepts[" + $conceptName + "]"
                    message = "Concept " + $conceptName + " is missing ui.label."
                    modelPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $modelPath
                })
        }

        foreach ($field in @($concept.fields)) {
            $isId = [bool](Get-OptionalPropertyValue -Target $field -PropertyName "id")
            if ($isId) {
                continue
            }
            $fieldUi = Get-OptionalPropertyValue -Target $field -PropertyName "ui"
            $fieldLabel = if ($null -eq $fieldUi) { $null } else { [string](Get-OptionalPropertyValue -Target $fieldUi -PropertyName "label") }
            if ([string]::IsNullOrWhiteSpace($fieldLabel)) {
                [void]$findings.Add([pscustomobject]@{
                        sampleId = $sampleId
                        severity = "warning"
                        kind = "field"
                        concept = $conceptName
                        field = [string]$field.name
                        path = "concepts[" + $conceptName + "].fields[" + [string]$field.name + "]"
                        message = "Field " + $conceptName + "." + [string]$field.name + " is missing ui.label."
                        modelPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $modelPath
                    })
            }
        }
    }
}

$overallStatus = if ($findings.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    sampleCount = $samples.Count
    checkedSampleIds = @($samples | Select-Object -ExpandProperty id)
    summary = [pscustomobject]@{
        missingLabels = $findings.Count
        conceptLabelsMissing = @($findings | Where-Object { $_.kind -eq "concept" }).Count
        fieldLabelsMissing = @($findings | Where-Object { $_.kind -eq "field" }).Count
    }
    findings = $findings
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk "Sample presentation labels are complete."
    return
}

Write-NPDevWarn "Sample presentation labels are incomplete."
throw "Sample presentation labels are incomplete."
