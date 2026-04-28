Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Resolve-MaturityWorkspaceRoot(
    [string]$WorkspaceRoot,
    [string]$ScriptRoot
) {
    if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
        $WorkspaceRoot = Get-NPDevWorkspaceRoot $ScriptRoot
    }

    return Normalize-NPDevPath $WorkspaceRoot
}

function Resolve-MaturityReportPath(
    [string]$WorkspaceRoot,
    [string]$ReportPath,
    [string]$DefaultRelativePath
) {
    if ([string]::IsNullOrWhiteSpace($ReportPath)) {
        return Resolve-NPDevWorkspacePath $WorkspaceRoot $DefaultRelativePath
    }

    return Normalize-NPDevPath $ReportPath
}

function Get-MaturityWorkspaceFiles(
    [string]$WorkspaceRoot,
    [string]$RelativeRoot,
    [string[]]$Includes
) {
    $root = Resolve-NPDevWorkspacePath $WorkspaceRoot $RelativeRoot
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        return @()
    }

    return @(
        Get-ChildItem -LiteralPath $root -Recurse -File -Include $Includes -ErrorAction SilentlyContinue | Where-Object {
            $_.FullName -notmatch "\\build\\" -and
            $_.FullName -notmatch "\\dist\\" -and
            $_.FullName -notmatch "\\node_modules\\" -and
            $_.FullName -notmatch "\\Output\\" -and
            $_.FullName -notmatch "\\\.gradle\\"
        }
    )
}

function Test-MaturityPaths(
    [string]$WorkspaceRoot,
    [string[]]$RelativePaths,
    [ValidateSet("Leaf", "Container", "Any")]
    [string]$PathType = "Any"
) {
    $existing = [System.Collections.Generic.List[string]]::new()
    $missing = [System.Collections.Generic.List[string]]::new()

    foreach ($relativePath in $RelativePaths) {
        $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
        $exists = switch ($PathType) {
            "Leaf" { Test-Path -LiteralPath $absolutePath -PathType Leaf }
            "Container" { Test-Path -LiteralPath $absolutePath -PathType Container }
            default { Test-Path -LiteralPath $absolutePath }
        }

        if ($exists) {
            [void]$existing.Add($relativePath)
        }
        else {
            [void]$missing.Add($relativePath)
        }
    }

    return [pscustomobject]@{
        existing = @($existing)
        missing = @($missing)
        allPresent = ($missing.Count -eq 0)
    }
}

function Test-MaturityFilePatterns(
    [string]$FilePath,
    [string[]]$Patterns
) {
    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        return [pscustomobject]@{
            exists = $false
            matched = @()
            missing = @($Patterns)
            allMatched = $false
        }
    }

    $content = Get-Content -LiteralPath $FilePath -Raw
    $matched = [System.Collections.Generic.List[string]]::new()
    $missing = [System.Collections.Generic.List[string]]::new()

    foreach ($pattern in $Patterns) {
        if ($content -match [regex]::Escape($pattern)) {
            [void]$matched.Add($pattern)
        }
        else {
            [void]$missing.Add($pattern)
        }
    }

    return [pscustomobject]@{
        exists = $true
        matched = @($matched)
        missing = @($missing)
        allMatched = ($missing.Count -eq 0)
    }
}

function Find-MaturityTextMatches(
    [string]$WorkspaceRoot,
    [string]$RelativeRoot,
    [string[]]$Includes,
    [string]$Pattern
) {
    $files = @(Get-MaturityWorkspaceFiles -WorkspaceRoot $WorkspaceRoot -RelativeRoot $RelativeRoot -Includes $Includes)
    if ($files.Count -eq 0) {
        return @()
    }

    $matches = [System.Collections.Generic.List[object]]::new()
    foreach ($hit in (Select-String -LiteralPath $files.FullName -Pattern $Pattern -ErrorAction SilentlyContinue)) {
        [void]$matches.Add([pscustomobject]@{
                path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $hit.Path
                lineNumber = $hit.LineNumber
                line = $hit.Line.Trim()
            })
    }

    return @($matches)
}

function Read-MaturityJsonFile(
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }

    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}

function Get-MaturityWaiverDocument {
    param(
        [string]$WaiverPath
    )

    $state = "missing"
    $waivers = @()
    $version = $null
    $parseError = $null

    if (-not (Test-Path -LiteralPath $WaiverPath -PathType Leaf)) {
        return [pscustomobject]@{
            state = $state
            version = $version
            waivers = $waivers
            parseError = $parseError
        }
    }

    try {
        $doc = Read-MaturityJsonFile $WaiverPath
        $state = "loaded"
        if ($null -ne $doc) {
            if ($doc.PSObject.Properties.Name -contains "version") {
                $version = [string]$doc.version
            }
            if ($doc.PSObject.Properties.Name -contains "waivers" -and $doc.waivers -is [System.Collections.IEnumerable]) {
                $waivers = @($doc.waivers)
            }
        }
    }
    catch {
        $state = "parse-error"
        $parseError = $_.Exception.Message
    }

    return [pscustomobject]@{
        state = $state
        version = $version
        waivers = $waivers
        parseError = $parseError
    }
}

function Get-MaturityReportMetadata(
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return [pscustomobject]@{
            exists = $false
            parseError = $null
            overallStatus = $null
            generatedAt = $null
            generatedAtDate = $null
            runId = $null
            properties = @()
        }
    }

    try {
        $report = Read-MaturityJsonFile $PathValue
        $properties = @($report.PSObject.Properties | Select-Object -ExpandProperty Name)
        $generatedAt = $null
        $generatedAtDate = $null
        if ($properties -contains "generatedAt") {
            $generatedAt = [string]$report.generatedAt
            try {
                $generatedAtDate = [datetimeoffset]::Parse($generatedAt, [Globalization.CultureInfo]::InvariantCulture)
                $generatedAt = $generatedAtDate.ToString("o")
            }
            catch {
                $generatedAtDate = $null
            }
        }

        return [pscustomobject]@{
            exists = $true
            parseError = $null
            overallStatus = if ($properties -contains "overallStatus") { [string]$report.overallStatus } else { $null }
            generatedAt = $generatedAt
            generatedAtDate = $generatedAtDate
            runId = if ($properties -contains "runId") { [string]$report.runId } else { $null }
            properties = $properties
        }
    }
    catch {
        return [pscustomobject]@{
            exists = $true
            parseError = $_.Exception.Message
            overallStatus = $null
            generatedAt = $null
            generatedAtDate = $null
            runId = $null
            properties = @()
        }
    }
}

function Test-MaturityReportSchema(
    [string]$PathValue,
    [string[]]$RequiredProperties
) {
    $metadata = Get-MaturityReportMetadata $PathValue
    if (-not $metadata.exists) {
        return [pscustomobject]@{
            exists = $false
            parseError = $null
            missingProperties = @($RequiredProperties)
            valid = $false
            metadata = $metadata
        }
    }

    if (-not [string]::IsNullOrWhiteSpace([string]$metadata.parseError)) {
        return [pscustomobject]@{
            exists = $true
            parseError = $metadata.parseError
            missingProperties = @($RequiredProperties)
            valid = $false
            metadata = $metadata
        }
    }

    $missing = @($RequiredProperties | Where-Object { $_ -notin $metadata.properties })
    return [pscustomobject]@{
        exists = $true
        parseError = $null
        missingProperties = $missing
        valid = ($missing.Count -eq 0)
        metadata = $metadata
    }
}

function Get-MaturityFreshness(
    [string]$PathValue,
    [int]$MaxAgeDays = 14
) {
    $metadata = Get-MaturityReportMetadata $PathValue
    if (-not $metadata.exists -or $null -eq $metadata.generatedAtDate) {
        return [pscustomobject]@{
            isFresh = $false
            ageDays = $null
            metadata = $metadata
        }
    }

    $ageDays = [math]::Round(((Get-DateOffset) - $metadata.generatedAtDate).TotalDays, 2)
    return [pscustomobject]@{
        isFresh = ($ageDays -le $MaxAgeDays)
        ageDays = $ageDays
        metadata = $metadata
    }
}

function Get-DateOffset {
    return [datetimeoffset](Get-Date)
}

function New-MaturityCheck {
    param(
        [string]$Name,
        [ValidateSet("passed", "warning", "failed")]
        [string]$Status,
        [string]$Expectation,
        [string]$Summary,
        [object]$Data = $null
    )

    return [pscustomobject]@{
        name = $Name
        status = $Status
        expectation = $Expectation
        summary = $Summary
        data = $Data
        checkedAt = (Get-Date).ToString("o")
    }
}

function Get-MaturityOverallStatus(
    [object[]]$Checks
) {
    if (@($Checks | Where-Object { $_.status -eq "failed" }).Count -gt 0) {
        return "failed"
    }

    if (@($Checks | Where-Object { $_.status -eq "warning" }).Count -gt 0) {
        return "warning"
    }

    return "passed"
}

function Write-MaturityReport {
    param(
        [string]$WorkspaceRoot,
        [string]$RunId,
        [string]$ScriptPath,
        [string]$MaturityItem,
        [string]$ReportPath,
        [object[]]$Checks,
        [object]$Extra = $null
    )

    $overallStatus = Get-MaturityOverallStatus $Checks
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        runId = $RunId
        scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ScriptPath
        workspaceRoot = $WorkspaceRoot
        maturityItem = $MaturityItem
        overallStatus = $overallStatus
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

function Complete-MaturityScript {
    param(
        [object]$Report,
        [switch]$PassThru
    )

    if ($PassThru) {
        return $Report
    }

    if ($Report.overallStatus -eq "passed") {
        Write-NPDevOk ($Report.maturityItem + " maturity checks passed.")
        return
    }

    if ($Report.overallStatus -eq "warning") {
        Write-NPDevWarn ($Report.maturityItem + " maturity checks completed with warnings.")
        return
    }

    Write-NPDevWarn ($Report.maturityItem + " maturity checks failed.")
    throw ($Report.maturityItem + " maturity checks failed.")
}

function Get-ClassPathResourceAudit(
    [string]$WorkspaceRoot,
    [string]$SourceRelativeRoot,
    [string]$ResourcesRelativeRoot
) {
    $sourceFiles = Get-MaturityWorkspaceFiles -WorkspaceRoot $WorkspaceRoot -RelativeRoot $SourceRelativeRoot -Includes @("*.java")
    $resourcesRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ResourcesRelativeRoot
    $resolved = [System.Collections.Generic.List[object]]::new()
    $missing = [System.Collections.Generic.List[object]]::new()
    $unresolved = [System.Collections.Generic.List[object]]::new()
    $dynamic = [System.Collections.Generic.List[object]]::new()

    foreach ($file in $sourceFiles) {
        $content = Get-Content -LiteralPath $file.FullName -Raw
        $constants = @{}
        $stringVariables = @{}
        foreach ($match in [regex]::Matches($content, '(?m)\b(?:private|public|protected)?\s*static\s+final\s+String\s+([A-Z0-9_]+)\s*=\s*"([^"]+)"\s*;')) {
            $constants[$match.Groups[1].Value] = $match.Groups[2].Value
        }
        foreach ($match in [regex]::Matches($content, '(?m)\b(?:final\s+)?(?:String|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*"([^"]+)"\s*;')) {
            $stringVariables[$match.Groups[1].Value] = $match.Groups[2].Value
        }

        foreach ($match in [regex]::Matches($content, 'new\s+ClassPathResource\s*\(\s*([^)]+?)\s*\)')) {
            $token = $match.Groups[1].Value.Trim()
            $resourcePath = $null
            if ($token.StartsWith('"') -and $token.EndsWith('"')) {
                $resourcePath = $token.Trim('"')
            }
            elseif ($constants.ContainsKey($token)) {
                $resourcePath = [string]$constants[$token]
            }
            elseif ($stringVariables.ContainsKey($token)) {
                $resourcePath = [string]$stringVariables[$token]
            }

            if ([string]::IsNullOrWhiteSpace($resourcePath) -and ($token -match '^[a-z_][A-Za-z0-9_]*$' -or $token -match '\(')) {
                [void]$dynamic.Add([pscustomobject]@{
                        source = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
                        token = $token
                    })
                continue
            }

            if ([string]::IsNullOrWhiteSpace($resourcePath)) {
                [void]$unresolved.Add([pscustomobject]@{
                        source = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
                        token = $token
                    })
                continue
            }

            $absoluteResourcePath = Join-Path $resourcesRoot $resourcePath.Replace("/", "\")
            $record = [pscustomobject]@{
                source = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
                resourcePath = $resourcePath
                resourceExists = (Test-Path -LiteralPath $absoluteResourcePath -PathType Leaf)
            }

            if ($record.resourceExists) {
                [void]$resolved.Add($record)
            }
            else {
                [void]$missing.Add($record)
            }
        }
    }

    return [pscustomobject]@{
        resolvedReferences = @($resolved)
        missingReferences = @($missing)
        unresolvedReferences = @($unresolved)
        dynamicReferences = @($dynamic)
        totalReferences = ($resolved.Count + $missing.Count + $unresolved.Count + $dynamic.Count)
    }
}

function New-MaturityDoneConditionCheck {
    param(
        [string]$ConditionId,
        [string]$ConditionText,
        [bool]$Passed,
        [string]$PassSummary = "Done condition satisfied.",
        [string]$FailSummary = "Done condition not yet satisfied.",
        [object]$Data = $null
    )

    return [pscustomobject]@{
        conditionId = $ConditionId
        name = $ConditionId
        status = if ($Passed) { "passed" } else { "failed" }
        conditionText = $ConditionText
        expectation = $ConditionText
        summary = if ($Passed) { $PassSummary } else { $FailSummary }
        data = $Data
        checkedAt = (Get-Date).ToString("o")
    }
}

function Get-MaturityFileSha256(
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }

    return (Get-FileHash -LiteralPath $PathValue -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-MaturityFileLineCount(
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return 0
    }

    return @(Get-Content -LiteralPath $PathValue).Count
}

function Get-MaturityMarkdownBrokenLinks(
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return @()
    }

    $content = Get-Content -LiteralPath $PathValue -Raw
    $fileDirectory = Split-Path -Parent $PathValue
    $issues = [System.Collections.Generic.List[object]]::new()

    foreach ($match in [regex]::Matches($content, '\[[^\]]+\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value.Trim()
        if ([string]::IsNullOrWhiteSpace($target)) {
            continue
        }
        if ($target.StartsWith("http://") -or $target.StartsWith("https://") -or $target.StartsWith("mailto:")) {
            continue
        }
        if ($target.StartsWith("#")) {
            continue
        }

        $cleanTarget = ($target -split '#')[0]
        if ([string]::IsNullOrWhiteSpace($cleanTarget)) {
            continue
        }

        $resolvedPath = if ([System.IO.Path]::IsPathRooted($cleanTarget)) {
            Normalize-NPDevPath $cleanTarget
        }
        else {
            Normalize-NPDevPath (Join-Path $fileDirectory $cleanTarget)
        }

        if (-not (Test-Path -LiteralPath $resolvedPath)) {
            [void]$issues.Add([pscustomobject]@{
                    target = $target
                    resolvedPath = $resolvedPath
                })
        }
    }

    return @($issues)
}
