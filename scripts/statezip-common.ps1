Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-StateZipInfo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [switch]$Quiet
    )

    if (-not $Quiet) {
        Write-Host $Message
    }
}

function Format-StateZipSize {
    param(
        [Parameter(Mandatory = $true)]
        [long]$ByteCount
    )

    if ($ByteCount -ge 1GB) {
        return ("{0:N2} GB" -f ($ByteCount / 1GB))
    }
    if ($ByteCount -ge 1MB) {
        return ("{0:N2} MB" -f ($ByteCount / 1MB))
    }
    if ($ByteCount -ge 1KB) {
        return ("{0:N2} KB" -f ($ByteCount / 1KB))
    }
    return ([string]$ByteCount + " bytes")
}

function Ensure-Directory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    if (-not (Test-Path -LiteralPath $PathValue)) {
        New-Item -ItemType Directory -Force -Path $PathValue | Out-Null
    }
}

function Assert-PathExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $PathValue)) {
        throw ($Label + " not found: " + $PathValue)
    }
}

function Remove-DirectorySafe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    if (Test-Path -LiteralPath $PathValue) {
        Remove-Item -LiteralPath $PathValue -Recurse -Force
    }
}

function Get-NormalizedFullPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    return [System.IO.Path]::GetFullPath($PathValue)
}

function Get-StateZipDeterministicTimestamp {
    return [datetime]::SpecifyKind([datetime]"2000-01-01T00:00:00Z", [System.DateTimeKind]::Utc)
}

function Set-StateZipTimestamp {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,
        [datetime]$Timestamp = (Get-StateZipDeterministicTimestamp)
    )

    if (Test-Path -LiteralPath $PathValue) {
        (Get-Item -LiteralPath $PathValue -Force).LastWriteTimeUtc = $Timestamp
    }
}

function Copy-StateZipFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,
        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    $destinationDir = Split-Path -Parent $DestinationPath
    Ensure-Directory -PathValue $destinationDir
    Copy-Item -LiteralPath $SourcePath -Destination $DestinationPath -Force

    if ((Test-Path -LiteralPath $SourcePath -PathType Leaf) -and (Test-Path -LiteralPath $DestinationPath -PathType Leaf)) {
        (Get-Item -LiteralPath $DestinationPath -Force).LastWriteTimeUtc = (Get-Item -LiteralPath $SourcePath -Force).LastWriteTimeUtc
    }
}

function Finalize-StateZipArchive {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ZipPath
    )

    Set-StateZipTimestamp -PathValue $ZipPath
}

function Get-DefaultStateZipOutDir {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRoot
    )

    $normalizedWorkspaceRoot = Get-NormalizedFullPath -PathValue $WorkspaceRoot
    $parent = Split-Path -Parent $normalizedWorkspaceRoot
    $leaf = Split-Path -Leaf $normalizedWorkspaceRoot
    return Join-Path $parent ($leaf + "__OutsideRepo\state-zips")
}

function Get-RelativePathCompat {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BasePath,
        [Parameter(Mandatory = $true)]
        [string]$TargetPath
    )

    $normalizedBasePath = [System.IO.Path]::GetFullPath($BasePath)
    $normalizedTargetPath = [System.IO.Path]::GetFullPath($TargetPath)

    if (-not $normalizedBasePath.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $normalizedBasePath = $normalizedBasePath + [System.IO.Path]::DirectorySeparatorChar
    }

    $baseUri = New-Object System.Uri($normalizedBasePath)
    $targetUri = New-Object System.Uri($normalizedTargetPath)
    $relativeUri = $baseUri.MakeRelativeUri($targetUri)
    $relativePath = [System.Uri]::UnescapeDataString($relativeUri.ToString())

    return $relativePath.Replace('/', '\')
}

function Should-ExcludeFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$FileItem,
        [Parameter(Mandatory = $true)]
        [string]$SourceRoot,
        [Parameter(Mandatory = $true)]
        [string[]]$ExcludeDirNames,
        [Parameter(Mandatory = $true)]
        [string[]]$ExcludeExtensions,
        [Parameter(Mandatory = $true)]
        [string[]]$ExcludeFilePatterns
    )

    $relativePath = Get-RelativePathCompat -BasePath $SourceRoot -TargetPath $FileItem.FullName

    foreach ($dirName in $ExcludeDirNames) {
        $segmentA = $dirName + "\"
        $segmentB = "\" + $dirName + "\"

        if ($relativePath.StartsWith($segmentA, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }

        if ($relativePath.IndexOf($segmentB, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        }
    }

    foreach ($pattern in $ExcludeFilePatterns) {
        if ($FileItem.Name -like $pattern) {
            return $true
        }
    }

    $extension = $FileItem.Extension.ToLowerInvariant()
    if ($ExcludeExtensions -contains $extension) {
        return $true
    }

    return $false
}

function Should-ExcludeDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.DirectoryInfo]$DirectoryItem,
        [Parameter(Mandatory = $true)]
        [string]$SourceRoot,
        [Parameter(Mandatory = $true)]
        [string[]]$ExcludeDirNames
    )

    $relativePath = Get-RelativePathCompat -BasePath $SourceRoot -TargetPath $DirectoryItem.FullName

    foreach ($dirName in $ExcludeDirNames) {
        if ($DirectoryItem.Name.Equals($dirName, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }

        $segmentA = $dirName + "\"
        $segmentB = "\" + $dirName + "\"

        if ($relativePath.StartsWith($segmentA, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }

        if ($relativePath.IndexOf($segmentB, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        }
    }

    return $false
}

function Get-IncludedFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceRoot,
        [Parameter(Mandatory = $true)]
        [string[]]$ExcludeDirNames,
        [Parameter(Mandatory = $true)]
        [string[]]$ExcludeExtensions,
        [Parameter(Mandatory = $true)]
        [string[]]$ExcludeFilePatterns
    )

    $files = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    $pendingDirectories = New-Object System.Collections.Generic.Stack[System.IO.DirectoryInfo]
    $pendingDirectories.Push((Get-Item -LiteralPath $SourceRoot -Force))

    while ($pendingDirectories.Count -gt 0) {
        $currentDirectory = $pendingDirectories.Pop()

        $children = Get-ChildItem -LiteralPath $currentDirectory.FullName -Force
        foreach ($child in $children) {
            if (($child.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                continue
            }

            if ($child -is [System.IO.DirectoryInfo]) {
                if (-not (Should-ExcludeDirectory -DirectoryItem $child -SourceRoot $SourceRoot -ExcludeDirNames $ExcludeDirNames)) {
                    $pendingDirectories.Push($child)
                }
                continue
            }

            if ($child -is [System.IO.FileInfo]) {
                if (-not (Should-ExcludeFile -FileItem $child -SourceRoot $SourceRoot -ExcludeDirNames $ExcludeDirNames -ExcludeExtensions $ExcludeExtensions -ExcludeFilePatterns $ExcludeFilePatterns)) {
                    $files.Add($child)
                }
            }
        }
    }

    return $files | Sort-Object FullName
}

function Copy-SignificantTree {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceRoot,
        [Parameter(Mandatory = $true)]
        [string]$DestRoot,
        [string]$StatusLabel = "",
        [switch]$Quiet,
        [string[]]$ExtraExcludeDirNames = @(),
        [string[]]$ExtraExcludeExtensions = @(),
        [string[]]$ExtraExcludeFilePatterns = @()
    )

    Assert-PathExists -PathValue $SourceRoot -Label "Source root"
    Ensure-Directory -PathValue $DestRoot

    $normalizedSourceRoot = Get-NormalizedFullPath -PathValue $SourceRoot
    $normalizedDestRoot = Get-NormalizedFullPath -PathValue $DestRoot
    $label = if ([string]::IsNullOrWhiteSpace($StatusLabel)) { Split-Path -Leaf $normalizedSourceRoot } else { $StatusLabel }

    $excludeDirNames = @(
        ".git",
        ".gradle",
        ".idea",
        ".vscode",
        "build",
        "out",
        "target",
        "node_modules",
        "dist",
        "coverage",
        "bin",
        "obj",
        "state-zips",
        "runtime-data",
        "npdev-generated"
    ) + $ExtraExcludeDirNames

    $excludeExtensions = @(
        ".class",
        ".jar",
        ".war",
        ".zip",
        ".7z",
        ".rar",
        ".log",
        ".tmp",
        ".bak",
        ".exe",
        ".dll",
        ".pdb",
        ".cache",
        ".lock",
        ".lck"
    ) + $ExtraExcludeExtensions

    $excludeFilePatterns = @(
        "hs_err_pid*.log",
        "*.iml",
        ".DS_Store",
        "Thumbs.db"
    ) + $ExtraExcludeFilePatterns

    Write-StateZipInfo -Quiet:$Quiet -Message ("Scanning significant files: " + $label)
    Write-StateZipInfo -Quiet:$Quiet -Message ("  Source:      " + $normalizedSourceRoot)
    Write-StateZipInfo -Quiet:$Quiet -Message ("  Destination: " + $normalizedDestRoot)

    $allFiles = @(Get-IncludedFiles `
        -SourceRoot $normalizedSourceRoot `
        -ExcludeDirNames $excludeDirNames `
        -ExcludeExtensions $excludeExtensions `
        -ExcludeFilePatterns $excludeFilePatterns)

    $copiedCount = 0
    $totalFiles = $allFiles.Count
    Write-StateZipInfo -Quiet:$Quiet -Message ("  Files selected: " + $totalFiles)

    foreach ($fileItem in $allFiles) {
        $relativePath = Get-RelativePathCompat -BasePath $normalizedSourceRoot -TargetPath $fileItem.FullName
        $destinationPath = Join-Path $normalizedDestRoot $relativePath
        $destinationDir = Split-Path -Parent $destinationPath

        Ensure-Directory -PathValue $destinationDir
        Copy-StateZipFile -SourcePath $fileItem.FullName -DestinationPath $destinationPath
        $copiedCount++

        if (-not $Quiet -and (($copiedCount % 250 -eq 0) -or ($copiedCount -eq $totalFiles))) {
            Write-Host ("  Copied {0}/{1} files for {2}" -f $copiedCount, $totalFiles, $label)
        }
    }

    Write-StateZipInfo -Quiet:$Quiet -Message ("Finished copy: " + $label + " (" + $copiedCount + " file(s))")
    return $copiedCount
}

function Copy-DirectoryTreeExact {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceRoot,
        [Parameter(Mandatory = $true)]
        [string]$DestRoot,
        [string]$StatusLabel = "",
        [switch]$Quiet
    )

    Assert-PathExists -PathValue $SourceRoot -Label "Source root"
    Ensure-Directory -PathValue $DestRoot

    $normalizedSourceRoot = Get-NormalizedFullPath -PathValue $SourceRoot
    $normalizedDestRoot = Get-NormalizedFullPath -PathValue $DestRoot
    $label = if ([string]::IsNullOrWhiteSpace($StatusLabel)) { Split-Path -Leaf $normalizedSourceRoot } else { $StatusLabel }

    Write-StateZipInfo -Quiet:$Quiet -Message ("Scanning exact files: " + $label)
    Write-StateZipInfo -Quiet:$Quiet -Message ("  Source:      " + $normalizedSourceRoot)
    Write-StateZipInfo -Quiet:$Quiet -Message ("  Destination: " + $normalizedDestRoot)

    $allDirectories = @(Get-ChildItem -LiteralPath $normalizedSourceRoot -Directory -Recurse -Force | Where-Object {
            ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0
        } | Sort-Object FullName)
    foreach ($directoryItem in $allDirectories) {
        $relativeDirectory = Get-RelativePathCompat -BasePath $normalizedSourceRoot -TargetPath $directoryItem.FullName
        Ensure-Directory -PathValue (Join-Path $normalizedDestRoot $relativeDirectory)
    }

    $allFiles = @(Get-ChildItem -LiteralPath $normalizedSourceRoot -File -Recurse -Force | Where-Object {
            ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0
        } | Sort-Object FullName)

    $copiedCount = 0
    $totalFiles = $allFiles.Count
    Write-StateZipInfo -Quiet:$Quiet -Message ("  Files selected: " + $totalFiles)

    foreach ($fileItem in $allFiles) {
        $relativePath = Get-RelativePathCompat -BasePath $normalizedSourceRoot -TargetPath $fileItem.FullName
        $destinationPath = Join-Path $normalizedDestRoot $relativePath
        $destinationDir = Split-Path -Parent $destinationPath

        Ensure-Directory -PathValue $destinationDir
        Copy-StateZipFile -SourcePath $fileItem.FullName -DestinationPath $destinationPath
        $copiedCount++

        if (-not $Quiet -and (($copiedCount % 250 -eq 0) -or ($copiedCount -eq $totalFiles))) {
            Write-Host ("  Copied {0}/{1} files for {2}" -f $copiedCount, $totalFiles, $label)
        }
    }

    Write-StateZipInfo -Quiet:$Quiet -Message ("Finished exact copy: " + $label + " (" + $copiedCount + " file(s))")
    return $copiedCount
}

function Copy-IfExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,
        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    if (-not (Test-Path -LiteralPath $SourcePath)) {
        return $false
    }

    Copy-StateZipFile -SourcePath $SourcePath -DestinationPath $DestinationPath
    return $true
}

function Get-ReleaseReadyDecision {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AggregateStatus,
        [string]$ProvenanceGrade = ""
    )

    if ([string]::IsNullOrWhiteSpace($AggregateStatus)) {
        throw "Beta release gate report is missing overallStatus."
    }
    if ($AggregateStatus -notin @("passed", "warning", "failed")) {
        throw ("Beta release gate report has invalid overallStatus: " + $AggregateStatus)
    }

    $traceabilitySatisfied = $ProvenanceGrade -in @("git-traceable", "ci-traceable")
    $releaseReady = ($AggregateStatus -eq "passed")
    $officialReleaseEligible = $releaseReady -and $traceabilitySatisfied
    $packagingMode = if ($officialReleaseEligible) { "RELEASE_READY" } else { "DIAGNOSTIC" }

    $decisionReason = if ($officialReleaseEligible) {
        "Aggregate beta release gate passed with traceable provenance."
    }
    elseif ($AggregateStatus -ne "passed") {
        "Aggregate beta release gate reported status '" + $AggregateStatus + "'."
    }
    elseif (-not $traceabilitySatisfied) {
        "Aggregate beta release gate passed, but provenance grade '" + $ProvenanceGrade + "' is diagnostic-only and does not satisfy release-ready traceability."
    }
    else {
        "Aggregate beta release gate passed."
    }

    return [pscustomobject]@{
        aggregateStatus = $AggregateStatus
        provenanceGrade = $ProvenanceGrade
        traceabilitySatisfied = $traceabilitySatisfied
        releaseReady = $releaseReady
        officialReleaseEligible = $officialReleaseEligible
        packagingMode = $packagingMode
        decisionReason = $decisionReason
    }
}

function Write-StateManifest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ManifestPath,
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRootValue,
        [Parameter(Mandatory = $true)]
        [string]$ZipPathValue,
        [Parameter(Mandatory = $true)]
        [string]$StateName,
        [Parameter(Mandatory = $true)]
        [string[]]$IncludedRoots,
        [Parameter(Mandatory = $true)]
        [hashtable]$CopiedCounts,
        [ValidateSet("SOURCE", "DIAGNOSTIC", "RELEASE_READY")]
        [string]$PackagingMode = "SOURCE",
        [string]$GeneratedAt = "2000-01-01 00:00:00 UTC",
        [string]$AggregateStatus = "",
        [string]$ReleaseRunId = "",
        [string]$ReleaseEvidenceStatus = "",
        [string]$ProvenanceGrade = "",
        [AllowNull()][object]$TraceabilitySatisfied = $null,
        [AllowNull()][object]$ReleaseReady = $null,
        [AllowNull()][object]$OfficialReleaseEligible = $null,
        [string]$CommitSha = "",
        [string]$Branch = "",
        [AllowNull()][object]$SourceDirty = $null,
        [string]$SourceProvider = ""
    )

    function Format-StateManifestMetadataValue {
        param(
            [AllowNull()][object]$Value
        )

        if ($null -eq $Value) {
            return ""
        }

        if ($Value -is [bool]) {
            return $Value.ToString().ToLowerInvariant()
        }

        return [string]$Value
    }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("NPDev General state zip manifest")
    $lines.Add("StateName=" + $StateName)
    $lines.Add("GeneratedAt=" + (Format-StateManifestMetadataValue -Value $GeneratedAt))
    $lines.Add("WorkspaceRoot=" + $WorkspaceRootValue)
    $lines.Add("ZipPath=" + [System.IO.Path]::GetFileName($ZipPathValue))
    $lines.Add("PackagingMode=" + $PackagingMode)
    $lines.Add("AggregateStatus=" + (Format-StateManifestMetadataValue -Value $AggregateStatus))
    $lines.Add("ReleaseRunId=" + (Format-StateManifestMetadataValue -Value $ReleaseRunId))
    $lines.Add("ReleaseEvidenceStatus=" + (Format-StateManifestMetadataValue -Value $ReleaseEvidenceStatus))
    $lines.Add("ProvenanceGrade=" + (Format-StateManifestMetadataValue -Value $ProvenanceGrade))
    $lines.Add("TraceabilitySatisfied=" + (Format-StateManifestMetadataValue -Value $TraceabilitySatisfied))
    $lines.Add("ReleaseReady=" + (Format-StateManifestMetadataValue -Value $ReleaseReady))
    $lines.Add("OfficialReleaseEligible=" + (Format-StateManifestMetadataValue -Value $OfficialReleaseEligible))
    $lines.Add("CommitSha=" + (Format-StateManifestMetadataValue -Value $CommitSha))
    $lines.Add("Branch=" + (Format-StateManifestMetadataValue -Value $Branch))
    $lines.Add("SourceDirty=" + (Format-StateManifestMetadataValue -Value $SourceDirty))
    $lines.Add("SourceProvider=" + (Format-StateManifestMetadataValue -Value $SourceProvider))
    $lines.Add("")

    $normalizedWorkspaceRoot = Get-NormalizedFullPath -PathValue $WorkspaceRootValue
    function Format-StateManifestRootValue {
        param([string]$RootValue)

        if ([string]::IsNullOrWhiteSpace($RootValue)) {
            return $RootValue
        }

        if (-not [System.IO.Path]::IsPathRooted($RootValue)) {
            return $RootValue
        }

        $normalizedRootValue = Get-NormalizedFullPath -PathValue $RootValue
        if ($normalizedRootValue.Equals($normalizedWorkspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            return "."
        }

        $workspacePrefix = $normalizedWorkspaceRoot
        if (-not $workspacePrefix.EndsWith("\")) {
            $workspacePrefix += "\"
        }
        if ($normalizedRootValue.StartsWith($workspacePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return Get-RelativePathCompat -BasePath $normalizedWorkspaceRoot -TargetPath $normalizedRootValue
        }

        if (Test-Path -LiteralPath $normalizedRootValue -PathType Leaf) {
            return [System.IO.Path]::GetFileName($normalizedRootValue)
        }

        return Split-Path -Leaf $normalizedRootValue
    }

    $lines.Add("IncludedRoots:")
    foreach ($rootValue in $IncludedRoots) {
        $lines.Add(" - " + (Format-StateManifestRootValue -RootValue $rootValue))
    }

    $lines.Add("")
    $lines.Add("CopiedCounts:")
    foreach ($key in ($CopiedCounts.Keys | Sort-Object)) {
        $lines.Add(" - " + $key + "=" + [string]$CopiedCounts[$key])
    }

    Set-Content -LiteralPath $ManifestPath -Value $lines -Encoding UTF8
    Set-StateZipTimestamp -PathValue $ManifestPath
}

function Copy-WorkspaceDocs {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRoot,
        [Parameter(Mandatory = $true)]
        [string]$DestRoot,
        [switch]$Quiet
    )

    $rootDocFiles = @(
        "README.md",
        "PROJECT_DIGEST.md",
        "NPDev_ExtrucureAndProposal.txt",
        "NPDev_ExtrucureAndProposal_Clean.txt"
    )
    $referencedDocFiles = @(
        "RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md",
        "RELEASE_BLOCKER_EXECUTION_ROADMAP.md",
        "FRONTEND_GATE_REPRODUCIBILITY.md",
        "SAMPLE_MATRIX_RELEASE_POLICY.md"
    )

    $copyCount = 0
    Write-StateZipInfo -Quiet:$Quiet -Message "Copying workspace root docs..."
    foreach ($fileName in $rootDocFiles) {
        $sourcePath = Join-Path $WorkspaceRoot $fileName
        $destinationPath = Join-Path $DestRoot $fileName
        if (Copy-IfExists -SourcePath $sourcePath -DestinationPath $destinationPath) {
            $copyCount++
            Write-StateZipInfo -Quiet:$Quiet -Message ("  Included root doc: " + $fileName)
        }
    }

    foreach ($fileName in $referencedDocFiles) {
        $sourcePath = Join-Path (Join-Path $WorkspaceRoot "docs") $fileName
        $destinationPath = Join-Path (Join-Path $DestRoot "docs") $fileName
        if (Copy-IfExists -SourcePath $sourcePath -DestinationPath $destinationPath) {
            $copyCount++
            Write-StateZipInfo -Quiet:$Quiet -Message ("  Included referenced doc: docs\" + $fileName)
        }
    }

    Write-StateZipInfo -Quiet:$Quiet -Message ("Workspace root docs copied: " + $copyCount)
    return $copyCount
}

function New-NPDevSubprojectStateZip {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRoot,
        [Parameter(Mandatory = $true)]
        [string]$SubprojectName,
        [string]$OutDir = "",
        [switch]$IncludeWorkspaceDocs = $true,
        [string]$Stamp = "",
        [switch]$Quiet,
        [string[]]$ExtraExcludeDirNames = @(),
        [string[]]$ExtraExcludeExtensions = @(),
        [string[]]$ExtraExcludeFilePatterns = @()
    )

    $normalizedWorkspaceRoot = Get-NormalizedFullPath -PathValue $WorkspaceRoot
    Assert-PathExists -PathValue $normalizedWorkspaceRoot -Label "NPDev_General workspace root"

    $subprojectRoot = Join-Path $normalizedWorkspaceRoot $SubprojectName
    Assert-PathExists -PathValue $subprojectRoot -Label $SubprojectName

    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $OutDir = Get-DefaultStateZipOutDir -WorkspaceRoot $normalizedWorkspaceRoot
    }

    $normalizedOutDir = Get-NormalizedFullPath -PathValue $OutDir
    Ensure-Directory -PathValue $normalizedOutDir

    if ([string]::IsNullOrWhiteSpace($Stamp)) {
        $Stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    }

    $stateName = "NPDev_General_State_" + $SubprojectName
    $stageRoot = Join-Path $env:TEMP ($stateName + "_" + $Stamp)
    $zipPath = Join-Path $normalizedOutDir ($stateName + "_" + $Stamp + ".zip")

    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }

    Remove-DirectorySafe -PathValue $stageRoot
    Ensure-Directory -PathValue $stageRoot

    $copiedCounts = @{}
    $includedRoots = New-Object System.Collections.Generic.List[string]

    try {
        Write-StateZipInfo -Quiet:$Quiet -Message ("== NPDev General: State zip (" + $SubprojectName + ") ==")
        Write-StateZipInfo -Quiet:$Quiet -Message "Step 1/5 - Preparing paths"
        Write-StateZipInfo -Quiet:$Quiet -Message ("  Workspace: " + $normalizedWorkspaceRoot)
        Write-StateZipInfo -Quiet:$Quiet -Message ("  Staging:   " + $stageRoot)
        Write-StateZipInfo -Quiet:$Quiet -Message ("  Zip:       " + $zipPath)

        Write-StateZipInfo -Quiet:$Quiet -Message ("Step 2/5 - Copying subproject: " + $SubprojectName)
        $copiedCounts[$SubprojectName] = Copy-SignificantTree `
            -SourceRoot $subprojectRoot `
            -DestRoot (Join-Path $stageRoot $SubprojectName) `
            -StatusLabel $SubprojectName `
            -Quiet:$Quiet `
            -ExtraExcludeDirNames $ExtraExcludeDirNames `
            -ExtraExcludeExtensions $ExtraExcludeExtensions `
            -ExtraExcludeFilePatterns $ExtraExcludeFilePatterns
        $includedRoots.Add($subprojectRoot)

        if ($IncludeWorkspaceDocs) {
            Write-StateZipInfo -Quiet:$Quiet -Message "Step 3/5 - Copying workspace docs"
            $workspaceDocsRoot = Join-Path $stageRoot "_workspace-root"
            $copiedCounts["_workspace-root"] = Copy-WorkspaceDocs -WorkspaceRoot $normalizedWorkspaceRoot -DestRoot $workspaceDocsRoot -Quiet:$Quiet
            $includedRoots.Add($normalizedWorkspaceRoot)
        } else {
            Write-StateZipInfo -Quiet:$Quiet -Message "Step 3/5 - Skipping workspace docs"
        }

        Write-StateZipInfo -Quiet:$Quiet -Message "Step 4/5 - Writing state manifest"
        $manifestPath = Join-Path $stageRoot "state-manifest.txt"
        Write-StateManifest -ManifestPath $manifestPath -WorkspaceRootValue $normalizedWorkspaceRoot -ZipPathValue $zipPath -StateName $stateName -IncludedRoots $includedRoots.ToArray() -CopiedCounts $copiedCounts

        Write-StateZipInfo -Quiet:$Quiet -Message "Step 5/5 - Compressing zip"
        Compress-Archive -Path (Join-Path $stageRoot "*") -DestinationPath $zipPath -Force
        Finalize-StateZipArchive -ZipPath $zipPath
        $zipSizeBytes = (Get-Item -LiteralPath $zipPath).Length

        if (-not $Quiet) {
            Write-Host ""
            Write-Host ("Resume: " + $SubprojectName)
            Write-Host (" - Zip: " + $zipPath)
            Write-Host (" - Size: " + (Format-StateZipSize -ByteCount $zipSizeBytes))
            Write-Host " - Copied counts:"
            foreach ($key in ($copiedCounts.Keys | Sort-Object)) {
                Write-Host ("   - " + $key + ": " + $copiedCounts[$key])
            }
            Write-Host ""
        }

        return [pscustomobject]@{
            StateName = $stateName
            SubprojectName = $SubprojectName
            ZipPath = $zipPath
            ZipSizeBytes = $zipSizeBytes
            CopiedCounts = $copiedCounts
        }
    }
    finally {
        Remove-DirectorySafe -PathValue $stageRoot
    }
}
