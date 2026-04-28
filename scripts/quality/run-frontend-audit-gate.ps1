[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "frontend-audit-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-audit-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$uiRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react"
$packageLockPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\package-lock.json"
$policyPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\frontend-npm-audit-policy.json"
Ensure-NPDevDirectory $uiRoot "NPDevEditor ui-react root"
Ensure-NPDevFile $packageLockPath "NPDevEditor package-lock.json"
Ensure-NPDevFile $policyPath "Frontend npm audit policy"

function Get-SeverityRank([string]$Severity) {
    switch (($Severity ?? "").ToLowerInvariant()) {
        "info" { return 0 }
        "low" { return 1 }
        "moderate" { return 2 }
        "high" { return 3 }
        "critical" { return 4 }
        default { return -1 }
    }
}

function Get-LockPackageEntry([object]$PackageMap, [string]$NodePath) {
    if ($null -eq $PackageMap) {
        return $null
    }
    if ($PackageMap -is [System.Collections.IDictionary]) {
        if ($PackageMap.Contains($NodePath)) {
            return $PackageMap[$NodePath]
        }
        return $null
    }
    $property = $PackageMap.PSObject.Properties | Where-Object { $_.Name -eq $NodePath } | Select-Object -First 1
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-VulnerabilityScope([object]$Vulnerability, [object]$PackageMap) {
    $nodes = @($Vulnerability.nodes)
    if ($nodes.Count -eq 0) {
        return "unknown"
    }

    $sawProd = $false
    $sawDev = $false
    foreach ($nodePath in $nodes) {
        $entry = Get-LockPackageEntry -PackageMap $PackageMap -NodePath ([string]$nodePath)
        if ($null -eq $entry) {
            $sawProd = $true
            continue
        }

        $isDev = $false
        if ($entry -is [System.Collections.IDictionary]) {
            if ($entry.Contains("dev")) {
                $isDev = [bool]$entry["dev"]
            }
        }
        elseif ($entry.PSObject.Properties.Name -contains "dev") {
            $isDev = [bool]$entry.dev
        }

        if ($isDev) {
            $sawDev = $true
        }
        else {
            $sawProd = $true
        }
    }

    if ($sawProd) {
        return "prod"
    }
    if ($sawDev) {
        return "dev"
    }
    return "unknown"
}

function Get-AdvisoryId([object]$ViaEntry) {
    if ($ViaEntry -is [string]) {
        return $null
    }
    $url = [string]$ViaEntry.url
    if ($url -match '(GHSA-[A-Za-z0-9-]+)$') {
        return $matches[1]
    }
    return $null
}

function Get-AllowlistExpiry([object]$Entry) {
    if ($null -eq $Entry -or [string]::IsNullOrWhiteSpace([string]$Entry.expiresOn)) {
        return $null
    }

    return [datetime]::Parse([string]$Entry.expiresOn)
}

function Get-ActiveAllowlistEntry([object[]]$Allowlist, [string]$PackageName, [string]$Severity) {
    $today = (Get-Date).Date
    foreach ($entry in $Allowlist) {
        if ([string]$entry.package -ne $PackageName) {
            continue
        }
        if ([string]$entry.severity -ne $Severity) {
            continue
        }

        $expiresOn = Get-AllowlistExpiry $entry
        if ($null -ne $expiresOn -and $expiresOn.Date -lt $today) {
            continue
        }

        return $entry
    }

    return $null
}

$policy = Get-Content -LiteralPath $policyPath -Raw | ConvertFrom-Json
$packageLock = Get-Content -LiteralPath $packageLockPath -Raw | ConvertFrom-Json -AsHashTable
$auditCapture = Invoke-NPDevCommandCapture -WorkingDirectory $uiRoot -Executable "cmd.exe" -Arguments @("/d", "/c", "npm", "audit", "--json", "--package-lock-only")
$auditJsonText = ($auditCapture.Output -join [Environment]::NewLine).Trim()

if ([string]::IsNullOrWhiteSpace($auditJsonText)) {
    throw "npm audit did not return JSON output."
}

$audit = $auditJsonText | ConvertFrom-Json
$allowedDevPackages = @($policy.allowedDevPackages)
$severityThreshold = Get-SeverityRank ([string]$policy.rules.failOnSeverityAtOrAbove)
$requireAllowlistForDevSeverities = @($policy.rules.requireAllowlistForDevSeverities | ForEach-Object { [string]$_ })
$findings = [System.Collections.Generic.List[object]]::new()
$today = (Get-Date).Date

foreach ($property in $audit.vulnerabilities.PSObject.Properties | Sort-Object Name) {
    $packageName = [string]$property.Name
    $vulnerability = $property.Value
    $severity = [string]$vulnerability.severity
    $scope = Get-VulnerabilityScope -Vulnerability $vulnerability -PackageMap $packageLock.packages
    $severityRank = Get-SeverityRank $severity
    $allowlistEntry = $null
    $decision = "blocked"
    $decisionReason = $null

    if ($scope -ne "dev" -and [bool]$policy.rules.failOnAnyProdVulnerability) {
        $decisionReason = "Production or unknown-scope vulnerabilities are not allowed in the beta gate."
    }
    elseif ($severityRank -ge $severityThreshold) {
        $decisionReason = "Severity " + $severity + " meets the blocking threshold " + [string]$policy.rules.failOnSeverityAtOrAbove + "."
    }
    elseif ($requireAllowlistForDevSeverities -contains $severity) {
        $allowlistEntry = Get-ActiveAllowlistEntry -Allowlist $allowedDevPackages -PackageName $packageName -Severity $severity
        if ($null -ne $allowlistEntry) {
            $decision = "allowed"
            $decisionReason = [string]$allowlistEntry.reason
        }
        else {
            $decisionReason = "Dev dependency vulnerability requires an explicit allowlist entry."
        }
    }
    else {
        $decision = "allowed"
        $decisionReason = "Allowed by default policy for dev-only severity " + $severity + "."
    }

    $viaEntries = @($vulnerability.via | ForEach-Object {
            if ($_ -is [string]) {
                [pscustomobject]@{
                    type = "package"
                    package = [string]$_
                    advisory = $null
                    title = $null
                    url = $null
                }
            }
            else {
                [pscustomobject]@{
                    type = "advisory"
                    package = [string]$_.name
                    advisory = Get-AdvisoryId $_
                    title = [string]$_.title
                    url = [string]$_.url
                }
            }
        })

    [void]$findings.Add([pscustomobject]@{
            package = $packageName
            severity = $severity
            scope = $scope
            direct = [bool]$vulnerability.isDirect
            fixAvailable = $vulnerability.fixAvailable
            decision = $decision
            decisionReason = $decisionReason
            allowlist = if ($null -eq $allowlistEntry) { $null } else { $allowlistEntry }
            via = $viaEntries
            nodes = @($vulnerability.nodes)
        })
}

$blockedFindings = @($findings | Where-Object { $_.decision -eq "blocked" })
$allowedFindings = @($findings | Where-Object { $_.decision -eq "allowed" })
$usedAllowlistKeys = @($allowedFindings | Where-Object { $null -ne $_.allowlist } | ForEach-Object {
        ([string]$_.allowlist.package) + "|" + ([string]$_.allowlist.severity)
    } | Sort-Object -Unique)
$expiredAllowlistEntries = @($allowedDevPackages | ForEach-Object {
        $expiresOn = Get-AllowlistExpiry $_
        if ($null -ne $expiresOn -and $expiresOn.Date -lt $today) {
            [pscustomobject]@{
                package = [string]$_.package
                severity = [string]$_.severity
                expiresOn = [string]$_.expiresOn
                reason = [string]$_.reason
            }
        }
    } | Where-Object { $null -ne $_ })
$unusedAllowlistEntries = @($allowedDevPackages | ForEach-Object {
        $entryKey = ([string]$_.package) + "|" + ([string]$_.severity)
        if ($entryKey -notin $usedAllowlistKeys) {
            [pscustomobject]@{
                package = [string]$_.package
                severity = [string]$_.severity
                expiresOn = [string]$_.expiresOn
                reason = [string]$_.reason
            }
        }
    } | Where-Object { $null -ne $_ })
$overallStatus = if ($blockedFindings.Count -eq 0 -and $expiredAllowlistEntries.Count -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    uiRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $uiRoot
    overallStatus = $overallStatus
    policyPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $policyPath
    command = "npm audit --json --package-lock-only"
    commandExitCode = $auditCapture.ExitCode
    summary = [pscustomobject]@{
        totalFindings = $findings.Count
        blockedFindings = $blockedFindings.Count
        allowedFindings = $allowedFindings.Count
        expiredAllowlistEntries = $expiredAllowlistEntries.Count
        unusedAllowlistEntries = $unusedAllowlistEntries.Count
        metadata = $audit.metadata
    }
    findings = $findings
    expiredAllowlistEntries = $expiredAllowlistEntries
    unusedAllowlistEntries = $unusedAllowlistEntries
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk "Frontend npm audit policy passed."
    return
}

Write-NPDevWarn "Frontend npm audit policy failed."
throw "Frontend npm audit policy failed."
