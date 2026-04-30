Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Initialize-Bucket2Workspace {
    param(
        [string]$WorkspaceRoot,
        [string]$ScriptRoot
    )

    if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
        $WorkspaceRoot = Get-NPDevWorkspaceRoot $ScriptRoot
    }
    return Normalize-NPDevPath $WorkspaceRoot
}

function Read-Bucket2JsonFile {
    param(
        [string]$PathValue
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }

    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}

function Get-Bucket2RelativePath {
    param(
        [string]$WorkspaceRoot,
        [string]$PathValue
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $null
    }

    $normalized = Normalize-NPDevPath $PathValue
    if (-not (Test-Path -LiteralPath $normalized)) {
        return $normalized
    }

    return Get-NPDevWorkspaceRelativePath $WorkspaceRoot $normalized
}

function Get-Bucket2PropertiesMap {
    param(
        [string]$PathValue
    )

    $map = @{}
    foreach ($line in Get-Content -LiteralPath $PathValue) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
            continue
        }

        $separatorIndex = $trimmed.IndexOf("=")
        if ($separatorIndex -lt 0) {
            $separatorIndex = $trimmed.IndexOf(":")
        }
        if ($separatorIndex -lt 0) {
            continue
        }

        $key = $trimmed.Substring(0, $separatorIndex).Trim()
        $value = $trimmed.Substring($separatorIndex + 1).Trim()
        if (-not [string]::IsNullOrWhiteSpace($key)) {
            $map[$key] = $value
        }
    }

    return $map
}

function Get-Bucket2JUnitSummary {
    param(
        [string]$PathValue
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return [pscustomobject]@{
            exists = $false
            passed = $false
            tests = 0
            failures = 0
            errors = 0
            skipped = 0
            path = $PathValue
            parseError = $null
        }
    }

    try {
        [xml]$xml = Get-Content -LiteralPath $PathValue -Raw
        $suite = $xml.testsuite
        $tests = [int]$suite.tests
        $failures = [int]$suite.failures
        $errors = [int]$suite.errors
        $skipped = if ($null -eq $suite.skipped -or [string]::IsNullOrWhiteSpace([string]$suite.skipped)) { 0 } else { [int]$suite.skipped }
        return [pscustomobject]@{
            exists = $true
            passed = ($failures -eq 0 -and $errors -eq 0 -and $tests -gt 0)
            tests = $tests
            failures = $failures
            errors = $errors
            skipped = $skipped
            path = $PathValue
            parseError = $null
        }
    }
    catch {
        return [pscustomobject]@{
            exists = $true
            passed = $false
            tests = 0
            failures = 0
            errors = 0
            skipped = 0
            path = $PathValue
            parseError = $_.Exception.Message
        }
    }
}

function Get-Bucket2FilePatternHits {
    param(
        [string]$PathValue,
        [string[]]$Patterns
    )

    $hits = [System.Collections.Generic.List[object]]::new()
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return @()
    }
    $content = Get-Content -LiteralPath $PathValue -Raw
    foreach ($pattern in $Patterns) {
        foreach ($match in [regex]::Matches($content, $pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
            [void]$hits.Add([pscustomobject]@{
                    pattern = $pattern
                    value = $match.Value
                })
        }
    }

    return @($hits)
}

function Get-Bucket2MissingPatterns {
    param(
        [string]$PathValue,
        [string[]]$Patterns
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return @($Patterns)
    }

    $content = Get-Content -LiteralPath $PathValue -Raw
    return @($Patterns | Where-Object { $content -notmatch $_ })
}

function Get-Bucket2OverallStatus {
    param(
        [object[]]$Checks
    )

    if (@($Checks | Where-Object { $_.status -eq "failed" }).Count -gt 0) {
        return "failed"
    }
    if (@($Checks | Where-Object { $_.status -eq "warning" }).Count -gt 0) {
        return "warning"
    }
    return "passed"
}

function Get-Bucket2Summary {
    param(
        [object[]]$Checks
    )

    return [pscustomobject]@{
        failed = @($Checks | Where-Object { $_.status -eq "failed" }).Count
        warnings = @($Checks | Where-Object { $_.status -eq "warning" }).Count
        passed = @($Checks | Where-Object { $_.status -eq "passed" }).Count
        total = $Checks.Count
    }
}
