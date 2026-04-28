[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$BoundaryConfigPath = "",
    [string]$SourceRoot = "",
    [string]$ReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "frontend-boundary"

if ([string]::IsNullOrWhiteSpace($BoundaryConfigPath)) {
    $BoundaryConfigPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\ui-boundary.json"
}
else {
    $BoundaryConfigPath = Normalize-NPDevPath $BoundaryConfigPath
}

if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
    $SourceRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\src"
}
else {
    $SourceRoot = Normalize-NPDevPath $SourceRoot
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-boundary-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

Ensure-NPDevFile $BoundaryConfigPath "Frontend boundary config"
Ensure-NPDevDirectory $SourceRoot "Frontend source root"

function Get-StringArray {
    param(
        [AllowNull()][object]$Value
    )

    if ($null -eq $Value) {
        return @()
    }

    return @(
        $Value |
        ForEach-Object {
            $text = [string]$_
            if (-not [string]::IsNullOrWhiteSpace($text)) {
                $text.Trim()
            }
        } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
}

function Convert-ToBoundaryPattern {
    param(
        [string]$Pattern
    )

    $normalized = [string]$Pattern
    $normalized = $normalized.Replace("/", "\")
    $normalized = $normalized.Replace("**\*", "<recursive-file>")
    $normalized = $normalized.Replace("**", "<recursive>")
    $normalized = $normalized.Replace("*", "<wildcard>")
    $normalized = [regex]::Escape($normalized)
    $normalized = $normalized.Replace("<recursive-file>", ".*")
    $normalized = $normalized.Replace("<recursive>", ".*")
    $normalized = $normalized.Replace("<wildcard>", "[^\\]*")
    return "^" + $normalized + "$"
}

function Test-BoundaryPatternMatch {
    param(
        [string]$RelativePath,
        [string[]]$Patterns
    )

    foreach ($pattern in $Patterns) {
        if ($RelativePath -match (Convert-ToBoundaryPattern $pattern)) {
            return $true
        }
    }
    return $false
}

function Resolve-LocalImportTarget {
    param(
        [string]$ImportingFilePath,
        [string]$ImportSpecifier
    )

    if ([string]::IsNullOrWhiteSpace($ImportSpecifier)) {
        return $null
    }
    if (-not ($ImportSpecifier.StartsWith(".\") -or $ImportSpecifier.StartsWith("..\") -or $ImportSpecifier.StartsWith("./") -or $ImportSpecifier.StartsWith("../"))) {
        return $null
    }

    $sourceDir = Split-Path -Parent $ImportingFilePath
    $baseTarget = Normalize-NPDevPath (Join-Path $sourceDir $ImportSpecifier)
    $candidates = @(
        $baseTarget,
        ($baseTarget + ".ts"),
        ($baseTarget + ".tsx"),
        ($baseTarget + ".js"),
        ($baseTarget + ".jsx"),
        (Join-Path $baseTarget "index.ts"),
        (Join-Path $baseTarget "index.tsx"),
        (Join-Path $baseTarget "index.js"),
        (Join-Path $baseTarget "index.jsx")
    )

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return Normalize-NPDevPath $candidate
        }
    }

    return $null
}

function Get-StaticImportSpecifiers {
    param(
        [string]$PathValue
    )

    $content = Get-Content -LiteralPath $PathValue -Raw
    $matches = [System.Collections.Generic.List[string]]::new()
    $patterns = @(
        '(?m)^\s*import\s+[^;]*?\s+from\s+["'']([^"'']+)["'']',
        '(?m)^\s*import\s+["'']([^"'']+)["'']',
        '(?m)^\s*export\s+[^;]*?\s+from\s+["'']([^"'']+)["'']'
    )

    foreach ($pattern in $patterns) {
        foreach ($match in [regex]::Matches($content, $pattern)) {
            if ($match.Groups.Count -gt 1) {
                [void]$matches.Add($match.Groups[1].Value)
            }
        }
    }

    return @($matches | Select-Object -Unique)
}

$boundary = Get-Content -LiteralPath $BoundaryConfigPath -Raw | ConvertFrom-Json
$classificationGroups = $boundary.classificationGroups
$requiredGroups = @("authoring", "runtime", "shared")
$groupDefinitions = @{}
foreach ($groupName in $requiredGroups) {
    $groupDefinition = if ($null -eq $classificationGroups) { $null } else { $classificationGroups.$groupName }
    if ($null -eq $groupDefinition) {
        throw ("Frontend boundary config is missing classification group '" + $groupName + "'.")
    }

    $groupDefinitions[$groupName] = [pscustomobject]@{
        include = Get-StringArray $groupDefinition.include
        exclude = Get-StringArray $groupDefinition.exclude
    }
}

$sourceFiles = @(
    Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Include @("*.ts", "*.tsx") |
    Sort-Object FullName
)
$tsxFiles = @($sourceFiles | Where-Object { $_.Extension -eq ".tsx" })

$classificationEntries = [System.Collections.Generic.List[object]]::new()
$unclassifiedSourceFiles = [System.Collections.Generic.List[string]]::new()
$unclassifiedTsxFiles = [System.Collections.Generic.List[string]]::new()
$multiClassifiedSourceFiles = [System.Collections.Generic.List[object]]::new()
$groupCounts = @{
    authoring = 0
    runtime = 0
    shared = 0
}
$classificationByPath = @{}

foreach ($file in $sourceFiles) {
    $relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
    $matches = @()
    foreach ($groupName in $requiredGroups) {
        $definition = $groupDefinitions[$groupName]
        $included = Test-BoundaryPatternMatch -RelativePath $relativePath -Patterns $definition.include
        $excluded = Test-BoundaryPatternMatch -RelativePath $relativePath -Patterns $definition.exclude
        if ($included -and -not $excluded) {
            $matches += $groupName
        }
    }

    $classification = if ($matches.Count -eq 1) { [string]$matches[0] } else { $null }
    if ($matches.Count -eq 0) {
        [void]$unclassifiedSourceFiles.Add($relativePath)
        if ($file.Extension -eq ".tsx") {
            [void]$unclassifiedTsxFiles.Add($relativePath)
        }
    }
    elseif ($matches.Count -gt 1) {
        [void]$multiClassifiedSourceFiles.Add([pscustomobject]@{
                path = $relativePath
                groups = $matches
            })
    }
    else {
        $groupCounts[$classification]++
        $classificationByPath[$relativePath] = $classification
    }

    [void]$classificationEntries.Add([pscustomobject]@{
            path = $relativePath
            extension = $file.Extension
            groups = $matches
            classification = $classification
        })
}

$allowedTargets = @{
    authoring = @("authoring", "shared")
    runtime = @("runtime", "shared")
    shared = @("shared")
}

$importViolations = [System.Collections.Generic.List[object]]::new()
$unresolvedLocalImports = [System.Collections.Generic.List[object]]::new()
foreach ($entry in @($classificationEntries | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.classification) })) {
    $importingFile = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$entry.path)
    foreach ($specifier in Get-StaticImportSpecifiers -PathValue $importingFile) {
        $resolvedTarget = Resolve-LocalImportTarget -ImportingFilePath $importingFile -ImportSpecifier $specifier
        if ($null -eq $resolvedTarget) {
            continue
        }

        if (-not $resolvedTarget.StartsWith($SourceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        if ($resolvedTarget -notmatch '\.(ts|tsx|js|jsx)$') {
            continue
        }

        $targetRelativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $resolvedTarget
        if (-not $classificationByPath.ContainsKey($targetRelativePath)) {
            [void]$unresolvedLocalImports.Add([pscustomobject]@{
                    sourcePath = [string]$entry.path
                    sourceGroup = [string]$entry.classification
                    specifier = $specifier
                    targetPath = $targetRelativePath
                })
            continue
        }

        $targetGroup = [string]$classificationByPath[$targetRelativePath]
        if ($targetGroup -notin $allowedTargets[[string]$entry.classification]) {
            [void]$importViolations.Add([pscustomobject]@{
                    sourcePath = [string]$entry.path
                    sourceGroup = [string]$entry.classification
                    specifier = $specifier
                    targetPath = $targetRelativePath
                    targetGroup = $targetGroup
                })
        }
    }
}

$checks = @(
    (New-NPDevCheckResult -Name "classification-groups-present" -Status "passed" -Summary "Frontend boundary config declares authoring, runtime, and shared groups." -Data ([pscustomobject]$groupDefinitions))
    (New-NPDevCheckResult -Name "tsx-files-classified-exactly-once" -Status $(if ($unclassifiedTsxFiles.Count -eq 0 -and @($multiClassifiedSourceFiles | Where-Object { $_.path -like "*.tsx" }).Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($unclassifiedTsxFiles.Count -eq 0 -and @($multiClassifiedSourceFiles | Where-Object { $_.path -like "*.tsx" }).Count -eq 0) { "Every local .tsx entry is classified exactly once." } else { "One or more .tsx files are unclassified or overlap across groups." }) -Data ([pscustomobject]@{ unclassifiedTsxFiles = @($unclassifiedTsxFiles); multiClassifiedTsxFiles = @($multiClassifiedSourceFiles | Where-Object { $_.path -like "*.tsx" }) }))
    (New-NPDevCheckResult -Name "source-files-resolve-to-single-group" -Status $(if ($unclassifiedSourceFiles.Count -eq 0 -and $multiClassifiedSourceFiles.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($unclassifiedSourceFiles.Count -eq 0 -and $multiClassifiedSourceFiles.Count -eq 0) { "Local source files participating in the boundary audit resolve to one group each." } else { "One or more local source files are unclassified or overlap across groups." }) -Data ([pscustomobject]@{ unclassifiedSourceFiles = @($unclassifiedSourceFiles); multiClassifiedSourceFiles = @($multiClassifiedSourceFiles) }))
    (New-NPDevCheckResult -Name "local-import-boundaries-hold" -Status $(if ($importViolations.Count -eq 0 -and $unresolvedLocalImports.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($importViolations.Count -eq 0 -and $unresolvedLocalImports.Count -eq 0) { "Local source imports obey authoring/runtime/shared boundary rules." } else { "Local source imports cross a forbidden boundary or resolve to an unclassified target." }) -Data ([pscustomobject]@{ violations = @($importViolations); unresolvedLocalImports = @($unresolvedLocalImports) }))
)

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }
    boundaryConfigPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $BoundaryConfigPath
    sourceRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $SourceRoot
    classificationGroups = [pscustomobject]$groupDefinitions
    summary = [pscustomobject]@{
        totalSourceFiles = $sourceFiles.Count
        totalTsxFiles = $tsxFiles.Count
        authoringFiles = $groupCounts.authoring
        runtimeFiles = $groupCounts.runtime
        sharedFiles = $groupCounts.shared
        unclassifiedSourceFiles = $unclassifiedSourceFiles.Count
        multiClassifiedSourceFiles = $multiClassifiedSourceFiles.Count
        importViolations = $importViolations.Count
        unresolvedLocalImports = $unresolvedLocalImports.Count
        failed = $failedChecks.Count
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
    coverage = [pscustomobject]@{
        unclassifiedTsxFiles = @($unclassifiedTsxFiles)
        multiClassifiedSourceFiles = @($multiClassifiedSourceFiles)
    }
    imports = [pscustomobject]@{
        violations = @($importViolations)
        unresolvedLocalImports = @($unresolvedLocalImports)
    }
    checks = $checks
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Frontend boundary audit passed."
    return
}

Write-NPDevWarn "Frontend boundary audit failed."
throw "Frontend boundary audit failed."
