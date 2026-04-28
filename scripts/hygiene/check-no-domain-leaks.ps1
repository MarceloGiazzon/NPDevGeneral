[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru,
    [string]$PolicyPath = "",
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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\domain-leak-report.json"
}

if ([string]::IsNullOrWhiteSpace($PolicyPath)) {
    $PolicyPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\cross-project-vocabulary-allowlist.json"
}
else {
    $PolicyPath = Normalize-NPDevPath $PolicyPath
}

Ensure-NPDevFile $PolicyPath "Cross-project vocabulary allowlist policy"
$policy = Get-Content -LiteralPath $PolicyPath -Raw | ConvertFrom-Json
$termSpecs = @($policy.terms)
$allowlistEntries = @($policy.allowlist)

$roots = @($policy.roots | ForEach-Object { [string]$_ })

function Test-AllowlistedFinding(
    [object]$Finding,
    [object[]]$Entries
) {
    foreach ($entry in $Entries) {
        $entryTerm = if ($null -eq $entry.term) { "" } else { [string]$entry.term }
        $pathPattern = if ($null -eq $entry.pathPattern) { "" } else { [string]$entry.pathPattern }
        $linePattern = if ($null -eq $entry.linePattern) { "" } else { [string]$entry.linePattern }
        $expiresAt = if ($null -eq $entry.expiresAt) { "" } else { [string]$entry.expiresAt }

        if (-not [string]::IsNullOrWhiteSpace($entryTerm) -and $entryTerm -ne [string]$Finding.term) {
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($pathPattern) -and [string]$Finding.path -notlike $pathPattern) {
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($linePattern) -and [string]$Finding.line -notmatch $linePattern) {
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($expiresAt)) {
            try {
                $expiry = [datetimeoffset]::Parse($expiresAt, [Globalization.CultureInfo]::InvariantCulture)
                if ($expiry -lt [datetimeoffset](Get-Date)) {
                    continue
                }
            }
            catch {
                continue
            }
        }

        return $true
    }

    return $false
}

$findings = @()
$allowlistedFindings = @()
foreach ($rootRelative in $roots) {
    $root = Resolve-NPDevWorkspacePath $WorkspaceRoot $rootRelative
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        continue
    }
    $files = Get-ChildItem -LiteralPath $root -Recurse -File -Force -Include *.java,*.json,*.md,*.ps1,*.gradle,*.properties,*.yml,*.yaml,*.sql | Where-Object {
        $_.FullName -notmatch "\\build\\" -and
        $_.FullName -notmatch "\\node_modules\\" -and
        $_.FullName -notmatch "\\.gradle\\" -and
        $_.FullName -notmatch "\\resources\\Models\\" -and
        $_.FullName -notmatch "\\npdev-templates\\static-react\\" -and
        $_.FullName -notmatch "\\src\\test\\" -and
        $_.FullName -notmatch "MIGRATION_DIGEST\.md$"
    }
    foreach ($term in $termSpecs) {
        foreach ($hit in ($files | Select-String -Pattern ([string]$term.pattern))) {
            $finding = [pscustomobject]@{
                path = (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $hit.Path)
                lineNumber = $hit.LineNumber
                term = [string]$term.name
                line = $hit.Line.Trim()
            }
            if (Test-AllowlistedFinding -Finding $finding -Entries $allowlistEntries) {
                $allowlistedFindings += $finding
                continue
            }
            $findings += $finding
        }
    }
}

$findings = @($findings | Sort-Object path, lineNumber, term -Unique)
$allowlistedFindings = @($allowlistedFindings | Sort-Object path, lineNumber, term -Unique)
$result = if ($findings.Count -eq 0) {
    New-NPDevCheckResult "domain-leaks" "passed" "No platform-core sample domain leaks were detected." @{
        matches = @()
        allowlistedMatches = $allowlistedFindings
        policyPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PolicyPath
    }
}
else {
    New-NPDevCheckResult "domain-leaks" "failed" "Platform-core sample domain leaks were detected." @{
        matches = $findings
        allowlistedMatches = $allowlistedFindings
        policyPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PolicyPath
    }
}

Write-NPDevJsonFile $ReportPath ([pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        workspaceRoot = $WorkspaceRoot
        overallStatus = $result.status
        result = $result
    })

if ($PassThru) {
    return $result
}
if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}
Write-NPDevWarn $result.summary
throw $result.summary
