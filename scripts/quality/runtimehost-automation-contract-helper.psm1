Set-StrictMode -Version Latest

function New-StructuredRunReport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$WorkspaceRoot,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string]$RunIdPrefix
    )

    return [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        runId = '{0}-{1}' -f $RunIdPrefix, (Get-Date -Format 'yyyyMMdd-HHmmssfff')
        scriptPath = $ScriptPath
        workspaceRoot = $WorkspaceRoot
        overallStatus = 'passed'
        commands = @()
        checks = @()
        summary = [ordered]@{
            failed = 0
            warnings = 0
            passed = 0
            total = 0
        }
    }
}

function Invoke-ReportedCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][hashtable]$Report,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$CommandText,
        [Parameter(Mandatory = $true)][scriptblock]$ScriptBlock
    )

    $startedAt = Get-Date
    $status = 'passed'
    $errorMessage = $null

    try {
        & $ScriptBlock
    } catch {
        $status = 'failed'
        $errorMessage = $_.Exception.Message
    }

    $finishedAt = Get-Date
    $Report.commands += [ordered]@{
        name = $Name
        command = $CommandText
        status = $status
        startedAt = $startedAt.ToString('o')
        finishedAt = $finishedAt.ToString('o')
        durationMs = [int][Math]::Round(($finishedAt - $startedAt).TotalMilliseconds)
        error = $errorMessage
    }

    $Report.checks += [ordered]@{
        name = $Name
        status = $status
        summary = if ($status -eq 'passed') { 'Command completed successfully.' } else { $errorMessage }
        data = [ordered]@{
            command = $CommandText
        }
        checkedAt = $finishedAt.ToString('o')
    }

    if ($status -eq 'failed') {
        $Report.overallStatus = 'failed'
        throw $errorMessage
    }
}

function Add-StructuredCheck {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][hashtable]$Report,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Summary,
        [object]$Data = $null
    )

    if ($Status -eq 'failed') {
        $Report.overallStatus = 'failed'
    }

    $Report.checks += [ordered]@{
        name = $Name
        status = $Status
        summary = $Summary
        data = $Data
        checkedAt = (Get-Date).ToString('o')
    }
}

function Write-StructuredRunReport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][hashtable]$Report,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )

    $failed = @($Report.checks | Where-Object { $_.status -eq 'failed' }).Count
    $warnings = @($Report.checks | Where-Object { $_.status -eq 'warning' }).Count
    $passed = @($Report.checks | Where-Object { $_.status -eq 'passed' }).Count
    $total = @($Report.checks).Count

    $Report.summary = [ordered]@{
        failed = $failed
        warnings = $warnings
        passed = $passed
        total = $total
    }

    if ($failed -gt 0) {
        $Report.overallStatus = 'failed'
    }

    $parent = Split-Path -Parent $OutputPath
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    $Report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
}

function Read-JsonFile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required JSON file not found: $Path"
    }

    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 100
}
