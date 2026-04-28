Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$script:NPDevBackgroundGateNames = @(
    "beta-release",
    "runtimehost",
    "sample-matrix",
    "frontend",
    "frontend-audit",
    "editor",
    "hygiene",
    "ai-beta-matrix"
)

function Get-NPDevBackgroundGateNames {
    return @($script:NPDevBackgroundGateNames)
}

function Get-NPDevBackgroundGateMetadataRoot([string]$WorkspaceRoot) {
    $metadataRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\background"
    New-Item -ItemType Directory -Force -Path $metadataRoot | Out-Null
    return $metadataRoot
}

function Get-NPDevBackgroundGateMetadataPath(
    [string]$WorkspaceRoot,
    [string]$JobId
) {
    $metadataRoot = Get-NPDevBackgroundGateMetadataRoot $WorkspaceRoot
    return Join-Path $metadataRoot ($JobId + ".json")
}

function Get-NPDevBackgroundGateLogPath(
    [string]$WorkspaceRoot,
    [string]$JobId,
    [ValidateSet("stdout", "stderr")]
    [string]$StreamName
) {
    $metadataRoot = Get-NPDevBackgroundGateMetadataRoot $WorkspaceRoot
    return Join-Path $metadataRoot ($JobId + "." + $StreamName + ".log")
}

function Merge-NPDevMetadataObject(
    [object]$CurrentValue,
    [hashtable]$Updates
) {
    $merged = [ordered]@{}

    if ($null -ne $CurrentValue) {
        foreach ($property in $CurrentValue.PSObject.Properties) {
            $merged[$property.Name] = $property.Value
        }
    }

    foreach ($key in $Updates.Keys) {
        $merged[$key] = $Updates[$key]
    }

    return [pscustomobject]$merged
}

function Read-NPDevBackgroundGateMetadata([string]$MetadataPath) {
    Ensure-NPDevFile $MetadataPath "Background gate metadata"
    return Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json
}

function Write-NPDevBackgroundGateMetadata(
    [string]$MetadataPath,
    [hashtable]$Updates
) {
    $currentValue = $null
    if (Test-Path -LiteralPath $MetadataPath -PathType Leaf) {
        $currentValue = Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json
    }

    $nextValue = Merge-NPDevMetadataObject -CurrentValue $currentValue -Updates $Updates
    Write-NPDevJsonFile $MetadataPath $nextValue
    return $nextValue
}

function Get-NPDevBackgroundGateReportSnapshot([string]$ExpectedReportPath) {
    if ([string]::IsNullOrWhiteSpace($ExpectedReportPath) -or -not (Test-Path -LiteralPath $ExpectedReportPath -PathType Leaf)) {
        return [pscustomobject]@{
            exists = $false
            overallStatus = $null
            generatedAt = $null
            runId = $null
            path = $null
            parseError = $null
        }
    }

    try {
        $report = Get-Content -LiteralPath $ExpectedReportPath -Raw | ConvertFrom-Json
        return [pscustomobject]@{
            exists = $true
            overallStatus = if ($report.PSObject.Properties.Name -contains "overallStatus") { [string]$report.overallStatus } else { $null }
            generatedAt = if ($report.PSObject.Properties.Name -contains "generatedAt") { [string]$report.generatedAt } else { $null }
            runId = if ($report.PSObject.Properties.Name -contains "runId") { [string]$report.runId } else { $null }
            path = $ExpectedReportPath
            parseError = $null
        }
    }
    catch {
        return [pscustomobject]@{
            exists = $true
            overallStatus = $null
            generatedAt = $null
            runId = $null
            path = $ExpectedReportPath
            parseError = $_.Exception.Message
        }
    }
}

function Get-NPDevBackgroundGateLogTail(
    [string]$PathValue,
    [int]$LineCount = 40
) {
    if ([string]::IsNullOrWhiteSpace($PathValue) -or -not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return @()
    }

    return @(
        Get-Content -LiteralPath $PathValue -Tail $LineCount |
        ForEach-Object { [string]$_ }
    )
}

function Resolve-NPDevBackgroundGateDefinition {
    param(
        [string]$WorkspaceRoot,
        [ValidateSet("beta-release", "runtimehost", "sample-matrix", "frontend", "frontend-audit", "editor", "hygiene", "ai-beta-matrix")]
        [string]$Gate,
        [string]$SampleId = "",
        [string]$SourceCommitSha = "",
        [string]$SourceBranch = "",
        [AllowNull()][object]$SourceDirty = $null,
        [string]$SourceProvider = "",
        [string]$SourceRunId = "",
        [string]$SourceRunAttempt = "",
        [string]$SourceWorkflow = ""
    )

    $workspaceRootValue = Normalize-NPDevPath $WorkspaceRoot
    if ([string]::IsNullOrWhiteSpace($SampleId)) {
        $SampleId = Get-NPDevDefaultSampleId $workspaceRootValue
    }
    $parameters = [ordered]@{
        WorkspaceRoot = $workspaceRootValue
    }

    $expectedReportPath = $null
    $scriptPath = $null

    switch ($Gate) {
        "beta-release" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-beta-release-gate.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\beta-release-gate-report.json"
            $parameters["SampleId"] = $SampleId
            if (-not [string]::IsNullOrWhiteSpace($SourceCommitSha)) {
                $parameters["SourceCommitSha"] = $SourceCommitSha
            }
            if (-not [string]::IsNullOrWhiteSpace($SourceBranch)) {
                $parameters["SourceBranch"] = $SourceBranch
            }
            if ($null -ne $SourceDirty) {
                $parameters["SourceDirty"] = $SourceDirty
            }
            if (-not [string]::IsNullOrWhiteSpace($SourceProvider)) {
                $parameters["SourceProvider"] = $SourceProvider
            }
            if (-not [string]::IsNullOrWhiteSpace($SourceRunId)) {
                $parameters["SourceRunId"] = $SourceRunId
            }
            if (-not [string]::IsNullOrWhiteSpace($SourceRunAttempt)) {
                $parameters["SourceRunAttempt"] = $SourceRunAttempt
            }
            if (-not [string]::IsNullOrWhiteSpace($SourceWorkflow)) {
                $parameters["SourceWorkflow"] = $SourceWorkflow
            }
        }
        "runtimehost" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-runtimehost-gate.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\runtimehost-gate-report.json"
            $parameters["SampleId"] = $SampleId
        }
        "sample-matrix" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-sample-matrix.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\sample-matrix-report.json"
        }
        "frontend" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-frontend-gate.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\frontend-gate-report.json"
        }
        "frontend-audit" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-frontend-audit-gate.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\frontend-audit-gate-report.json"
        }
        "editor" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-editor-gate.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\editor-gate-report.json"
        }
        "hygiene" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-hygiene-gate.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\hygiene-gate-report.json"
        }
        "ai-beta-matrix" {
            $scriptPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\quality\run-ai-beta-matrix.ps1"
            $expectedReportPath = Resolve-NPDevWorkspacePath $workspaceRootValue "scripts\reports\out\ai-beta-matrix-report.json"
        }
        default {
            throw ("Unsupported background gate: " + $Gate)
        }
    }

    Ensure-NPDevFile $scriptPath ($Gate + " gate script")

    return [pscustomobject]@{
        gate = $Gate
        workspaceRoot = $workspaceRootValue
        scriptPath = $scriptPath
        scriptPathRelative = Get-NPDevWorkspaceRelativePath $workspaceRootValue $scriptPath
        expectedReportPath = $expectedReportPath
        expectedReportPathRelative = if ([string]::IsNullOrWhiteSpace($expectedReportPath)) { $null } else { Get-NPDevWorkspaceRelativePath $workspaceRootValue $expectedReportPath }
        parameters = [pscustomobject]$parameters
        sampleId = $SampleId
    }
}
