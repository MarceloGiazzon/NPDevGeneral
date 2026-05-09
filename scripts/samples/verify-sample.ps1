[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$SampleIds = @(),
    [switch]$GenerateIfMissing,
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
if ($SampleIds.Count -eq 0) {
    $SampleIds = @(Get-NPDevDefaultSampleId $WorkspaceRoot)
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-verify-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$generateScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\generate-sample.ps1"
$cleanScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\clean-sample-output.ps1"
Ensure-NPDevFile $cleanScript "Sample cleanup script"

$results = @()
foreach ($sampleId in $SampleIds) {
    $appRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Output\App")
    $appBuildFile = Join-Path $appRoot "build.gradle"
    $verificationCommand = $null
    $cleanupEvidence = $null
    $sampleStatus = "passed"
    $sampleError = $null

    try {
        if (-not (Test-Path -LiteralPath $appBuildFile -PathType Leaf)) {
            if (-not $GenerateIfMissing) {
                throw ("Generated app is missing. Rerun with -GenerateIfMissing or generate first: " + $appRoot)
            }
            Ensure-NPDevFile $generateScript "Sample generation wrapper"
            & $generateScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($sampleId)
        }

        Ensure-NPDevDirectory $appRoot "Generated sample app"
        Ensure-NPDevFile $appBuildFile "Generated app build.gradle"
        $gradlew = Get-NPDevGradleWrapperExecutable (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost")
        $verificationLogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\out\sample-verify\" + $sampleId + "-verification.log")
        $verificationCommand = Invoke-NPDevCommandEvidence `
            -WorkspaceRoot $WorkspaceRoot `
            -WorkingDirectory $appRoot `
            -Executable $gradlew `
            -Arguments @("--no-daemon", "--console=plain", "-p", $appRoot, "enforceSingleMigrationSource", "test") `
            -LogPath $verificationLogPath

        if ([string]$verificationCommand.status -ne "passed") {
            throw "Sample verification command failed."
        }
    }
    catch {
        $sampleStatus = "failed"
        $sampleError = $_.Exception.Message
    }
    finally {
        $cleanupReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\out\sample-verify\" + $sampleId + "-clean-report.json")
        try {
            & $cleanScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($sampleId) -BuildCachesOnly -ReportPath $cleanupReportPath | Out-Null
            $cleanupEvidence = [pscustomobject]@{
                status = "passed"
                reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $cleanupReportPath
                error = $null
            }
        }
        catch {
            $cleanupError = $_.Exception.Message
            $cleanupEvidence = [pscustomobject]@{
                status = "failed"
                reportPath = $null
                error = $cleanupError
            }
            if ($sampleStatus -eq "passed") {
                $sampleStatus = "failed"
                $sampleError = "Sample output cleanup failed after verification: " + $cleanupError
            }
            else {
                $sampleError = $sampleError + " Sample output cleanup also failed: " + $cleanupError
            }
        }
    }

    $results += [pscustomobject]@{
        sampleId = $sampleId
        appRoot = $appRoot
        status = $sampleStatus
        verificationCommand = $verificationCommand
        cleanup = $cleanupEvidence
        error = $sampleError
    }
}

$failed = @($results | Where-Object { $_.status -eq "failed" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    sampleIds = $SampleIds
    overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    results = $results
}
Write-NPDevJsonFile $ReportPath $report

if ($failed.Count -eq 0) {
    Write-NPDevOk ("Sample verification passed for " + ($SampleIds -join ", ") + ".")
    return
}

Write-NPDevWarn ("Sample verification failed for " + ($failed.sampleId -join ", ") + ".")
throw "Sample verification failed."
