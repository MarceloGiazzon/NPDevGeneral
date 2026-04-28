Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "maturity-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

function Resolve-PrioritizedControlReportPath {
    param(
        [string]$WorkspaceRoot,
        [string]$ReportPath,
        [string]$DefaultRelativePath
    )

    if ([string]::IsNullOrWhiteSpace($ReportPath)) {
        return Resolve-NPDevWorkspacePath $WorkspaceRoot $DefaultRelativePath
    }

    return Normalize-NPDevPath $ReportPath
}

function Get-PrioritizedControlObjectPropertyNames {
    param(
        [AllowNull()][object]$Value
    )

    if ($null -eq $Value) {
        return @()
    }

    return @($Value.PSObject.Properties | Select-Object -ExpandProperty Name)
}

function Test-PrioritizedControlObjectProperties {
    param(
        [AllowNull()][object]$Value,
        [string[]]$RequiredProperties
    )

    $propertyNames = Get-PrioritizedControlObjectPropertyNames $Value
    $missing = @($RequiredProperties | Where-Object { $_ -notin $propertyNames })

    return [pscustomobject]@{
        propertyNames = $propertyNames
        missing = $missing
        valid = ($missing.Count -eq 0)
    }
}

function Get-PrioritizedControlStringProperty {
    param(
        [AllowNull()][object]$Value,
        [string]$PropertyName
    )

    if ($null -eq $Value) {
        return $null
    }

    $propertyNames = Get-PrioritizedControlObjectPropertyNames $Value
    if ($PropertyName -notin $propertyNames) {
        return $null
    }

    $propertyValue = $Value.$PropertyName
    if ($null -eq $propertyValue) {
        return $null
    }

    return [string]$propertyValue
}

function Resolve-Bucket1AggregateReportPath {
    param(
        [string]$WorkspaceRoot,
        [string]$AggregateReportPath
    )

    if ([string]::IsNullOrWhiteSpace($AggregateReportPath)) {
        return Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
    }

    return Normalize-NPDevPath $AggregateReportPath
}

function Resolve-Bucket1EvidenceManifestPath {
    param(
        [string]$WorkspaceRoot,
        [string]$AggregateReportPath,
        [string]$EvidenceManifestPath
    )

    if (-not [string]::IsNullOrWhiteSpace($EvidenceManifestPath)) {
        return Normalize-NPDevPath $EvidenceManifestPath
    }

    $aggregateReport = Read-MaturityJsonFile $AggregateReportPath
    if ($null -eq $aggregateReport) {
        return $null
    }

    $evidenceRootValue = Get-PrioritizedControlStringProperty -Value $aggregateReport -PropertyName "evidenceRoot"
    if ([string]::IsNullOrWhiteSpace($evidenceRootValue)) {
        return $null
    }

    $evidenceRoot = if ([System.IO.Path]::IsPathRooted($evidenceRootValue)) {
        Normalize-NPDevPath $evidenceRootValue
    }
    else {
        Resolve-NPDevWorkspacePath $WorkspaceRoot $evidenceRootValue
    }

    return Join-Path $evidenceRoot "evidence-manifest.json"
}

function Get-PrioritizedControlEvidencePath {
    param(
        [string]$WorkspaceRoot,
        [string]$PathValue
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $null
    }

    $normalizedPath = Normalize-NPDevPath $PathValue
    if (-not (Test-Path -LiteralPath $normalizedPath)) {
        return $normalizedPath
    }

    return Get-NPDevWorkspaceRelativePath $WorkspaceRoot $normalizedPath
}

function Get-PrioritizedExpectedTraceabilitySatisfied {
    param(
        [string]$ProvenanceGrade
    )

    return $ProvenanceGrade -in @("git-traceable", "ci-traceable")
}

function Resolve-LatestAllStateZipPath {
    param(
        [string]$WorkspaceRoot,
        [string]$StateZipPath
    )

    if (-not [string]::IsNullOrWhiteSpace($StateZipPath)) {
        return Normalize-NPDevPath $StateZipPath
    }

    $outDir = Get-DefaultStateZipOutDir -WorkspaceRoot $WorkspaceRoot
    if (-not (Test-Path -LiteralPath $outDir -PathType Container)) {
        return $null
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $outDir -File -Filter "NPDev_General_State_ALL_*.zip" -ErrorAction SilentlyContinue |
        Sort-Object CreationTime, Name -Descending
    )
    if ($candidates.Count -eq 0) {
        return $null
    }

    return $candidates[0].FullName
}

function Get-ZipEntryText {
    param(
        [string]$ZipPath,
        [string]$EntryPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $normalizedEntryPath = $EntryPath.Replace("\", "/")
        $entry = $archive.GetEntry($normalizedEntryPath)
        if ($null -eq $entry) {
            return $null
        }

        $reader = New-Object System.IO.StreamReader($entry.Open())
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

function Get-ZipEntryNames {
    param(
        [string]$ZipPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        return @($archive.Entries | ForEach-Object { [string]$_.FullName.Replace("/", "\") })
    }
    finally {
        $archive.Dispose()
    }
}

function Convert-StateManifestTextToMap {
    param(
        [string]$ManifestText
    )

    $map = @{}
    if ([string]::IsNullOrWhiteSpace($ManifestText)) {
        return $map
    }

    foreach ($line in @($ManifestText -split "(`r`n|`n|`r)")) {
        $trimmedLine = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmedLine)) {
            continue
        }
        if ($trimmedLine.StartsWith("- ")) {
            continue
        }
        $separatorIndex = $trimmedLine.IndexOf("=")
        if ($separatorIndex -lt 1) {
            continue
        }

        $key = $trimmedLine.Substring(0, $separatorIndex).Trim()
        $value = $trimmedLine.Substring($separatorIndex + 1).Trim()
        if (-not [string]::IsNullOrWhiteSpace($key)) {
            $map[$key] = $value
        }
    }

    return $map
}

function Write-PrioritizedControlReport {
    param(
        [string]$WorkspaceRoot,
        [string]$RunId,
        [string]$ScriptPath,
        [string]$Bucket,
        [string]$ControlId,
        [string]$ReportPath,
        [string[]]$EvidencePaths,
        [object[]]$Checks,
        [object]$Extra = $null
    )

    $overallStatus = Get-MaturityOverallStatus $Checks
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        runId = $RunId
        scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ScriptPath
        workspaceRoot = $WorkspaceRoot
        bucket = $Bucket
        controlId = $ControlId
        overallStatus = $overallStatus
        evidencePaths = @($EvidencePaths | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Unique)
        checks = $Checks
        summary = [pscustomobject]@{
            failed = @($Checks | Where-Object { $_.status -eq "failed" }).Count
            warnings = @($Checks | Where-Object { $_.status -eq "warning" }).Count
            passed = @($Checks | Where-Object { $_.status -eq "passed" }).Count
            total = $Checks.Count
        }
        extra = $Extra
    }

    Write-NPDevJsonFile $ReportPath $report
    return $report
}

function Complete-PrioritizedControlScript {
    param(
        [object]$Report,
        [switch]$PassThru
    )

    if ($PassThru) {
        return $Report
    }

    $label = $Report.controlId
    if ($Report.overallStatus -eq "passed") {
        Write-NPDevOk ($label + " passed.")
        return
    }

    if ($Report.overallStatus -eq "warning") {
        Write-NPDevWarn ($label + " completed with warnings.")
        return
    }

    Write-NPDevWarn ($label + " failed.")
    throw ($label + " failed.")
}
