[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$SampleIds = @(),
    [string]$ReportPath = "",
    [switch]$CheckOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\mirrored-sample-sync-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$policyPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\sample-mirrors.json"
Ensure-NPDevFile $policyPath "Sample mirror policy"
$policy = Get-Content -LiteralPath $policyPath -Raw | ConvertFrom-Json

function Get-MirrorRelativeRoot([object]$Sample) {
    $canonicalSampleId = Get-NPDevCanonicalSampleId $WorkspaceRoot
    if ([string]$Sample.id -eq $canonicalSampleId) {
        return $canonicalSampleId
    }
    if ($Sample.kind -eq "official-sample") {
        return "official-samples\" + $Sample.id
    }
    if ($Sample.kind -eq "tenant-sample") {
        return "tenant-samples\" + $Sample.id
    }
    return $null
}

function Get-MirrorRelativePath([string]$CanonicalInput, [System.IO.FileInfo]$File) {
    $relative = Get-NPDevWorkspaceRelativePath $CanonicalInput $File.FullName
    if ($relative.StartsWith("Requests\", [System.StringComparison]::OrdinalIgnoreCase)) {
        $relative = "input\" + $relative.Substring("Requests\".Length)
    }
    return $relative
}

function Get-Sha256([string]$PathValue) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $PathValue).Hash.ToLowerInvariant()
}

function Get-CanonicalInputFiles([string]$CanonicalInput) {
    return @(Get-ChildItem -LiteralPath $CanonicalInput -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
            [pscustomobject]@{
                relativePath = Get-MirrorRelativePath $CanonicalInput $_
                sourcePath = $_.FullName
                sha256 = Get-Sha256 $_.FullName
                sizeBytes = $_.Length
            }
        })
}

function Assert-WorkspaceChildPath([string]$WorkspaceRootValue, [string]$TargetPath, [string]$Label) {
    $normalizedRoot = Normalize-NPDevPath $WorkspaceRootValue
    if (-not $normalizedRoot.EndsWith("\")) {
        $normalizedRoot += "\"
    }
    $normalizedTarget = Normalize-NPDevPath $TargetPath
    if (-not $normalizedTarget.StartsWith($normalizedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ($Label + " is outside the workspace: " + $normalizedTarget)
    }
}

function Copy-CanonicalInputToMirror([string]$WorkspaceRootValue, [string]$CanonicalInput, [string]$MirrorRoot) {
    Assert-WorkspaceChildPath -WorkspaceRootValue $WorkspaceRootValue -TargetPath $MirrorRoot -Label "Mirror root"
    if (Test-Path -LiteralPath $MirrorRoot) {
        Remove-Item -LiteralPath $MirrorRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $MirrorRoot | Out-Null
    $copied = @()
    foreach ($file in Get-ChildItem -LiteralPath $CanonicalInput -Recurse -File -Force) {
        $relative = Get-MirrorRelativePath $CanonicalInput $file
        $destination = Join-Path $MirrorRoot $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
        $copied += $relative
    }
    return $copied
}

function Compare-CanonicalInputToMirror([string]$CanonicalInput, [string]$MirrorRoot) {
    $canonicalFiles = Get-CanonicalInputFiles $CanonicalInput
    $expectedPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($file in $canonicalFiles) {
        [void]$expectedPaths.Add([string]$file.relativePath)
    }

    $missingFiles = [System.Collections.Generic.List[object]]::new()
    $mismatchedFiles = [System.Collections.Generic.List[object]]::new()
    $extraFiles = [System.Collections.Generic.List[object]]::new()

    foreach ($file in $canonicalFiles) {
        $mirrorPath = Join-Path $MirrorRoot ([string]$file.relativePath)
        if (-not (Test-Path -LiteralPath $mirrorPath -PathType Leaf)) {
            [void]$missingFiles.Add([pscustomobject]@{
                    relativePath = [string]$file.relativePath
                    expectedSha256 = [string]$file.sha256
                })
            continue
        }

        $mirrorSha = Get-Sha256 $mirrorPath
        if ($mirrorSha -ne [string]$file.sha256) {
            [void]$mismatchedFiles.Add([pscustomobject]@{
                    relativePath = [string]$file.relativePath
                    expectedSha256 = [string]$file.sha256
                    actualSha256 = $mirrorSha
                })
        }
    }

    if (Test-Path -LiteralPath $MirrorRoot -PathType Container) {
        foreach ($file in Get-ChildItem -LiteralPath $MirrorRoot -Recurse -File -Force | Sort-Object FullName) {
            $relative = Get-NPDevWorkspaceRelativePath $MirrorRoot $file.FullName
            if (-not $expectedPaths.Contains($relative)) {
                [void]$extraFiles.Add([pscustomobject]@{
                        relativePath = $relative
                        sha256 = Get-Sha256 $file.FullName
                    })
            }
        }
    }

    $issueCount = $missingFiles.Count + $mismatchedFiles.Count + $extraFiles.Count
    return [pscustomobject]@{
        status = if ($issueCount -eq 0) { "passed" } else { "failed" }
        canonicalFileCount = $canonicalFiles.Count
        missingFiles = @($missingFiles)
        mismatchedFiles = @($mismatchedFiles)
        extraFiles = @($extraFiles)
        issueCount = $issueCount
    }
}

$samples = Get-NPDevSampleEntries $WorkspaceRoot
if ($SampleIds.Count -gt 0) {
    $samples = @($samples | Where-Object { $_.id -in $SampleIds })
}

$mirrorTargets = @($policy.mirrorTargets)

$results = @()
foreach ($sample in $samples) {
    $relativeMirror = Get-MirrorRelativeRoot $sample
    if ([string]::IsNullOrWhiteSpace($relativeMirror)) {
        continue
    }

    $canonicalInput = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sample.id + "\Input")
    foreach ($target in $mirrorTargets) {
        $mirrorBase = [string]$target.root
        $mirrorRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot (Join-Path $mirrorBase $relativeMirror)
        if ($CheckOnly) {
            $comparison = Compare-CanonicalInputToMirror $canonicalInput $mirrorRoot
            $results += [pscustomobject]@{
                sampleId = $sample.id
                mirrorName = [string]$target.name
                mirrorRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $mirrorRoot
                status = [string]$comparison.status
                comparison = $comparison
            }
        }
        else {
            $copied = Copy-CanonicalInputToMirror $WorkspaceRoot $canonicalInput $mirrorRoot
            $results += [pscustomobject]@{
                sampleId = $sample.id
                mirrorName = [string]$target.name
                mirrorRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $mirrorRoot
                status = "synced"
                copiedFiles = $copied
            }
        }
    }
}

$failed = @($results | Where-Object { $_.status -eq "failed" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    mode = if ($CheckOnly) { "check-only" } else { "sync" }
    policyPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $policyPath
    canonicalRoot = [string]$policy.canonicalRoot
    overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    summary = [pscustomobject]@{
        total = $results.Count
        passed = @($results | Where-Object { $_.status -in @("passed", "synced") }).Count
        failed = $failed.Count
    }
    results = $results
}
Write-NPDevJsonFile $ReportPath $report

if ($failed.Count -gt 0) {
    Write-NPDevWarn ("Mirrored sample drift detected in " + $failed.Count + " target(s).")
    throw "Mirrored sample drift detected."
}

if ($CheckOnly) {
    Write-NPDevOk "Mirrored sample resources match NPDevSamples."
}
else {
    Write-NPDevOk "Mirrored sample resources were synced from NPDevSamples."
}
