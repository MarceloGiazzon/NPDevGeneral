[CmdletBinding()]
param(
    [string]$ZipRoot = "D:\WorkSpace\NPDev_General__OutsideRepo\state-zips"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ZipRoot -PathType Container)) {
    throw "Zip root not found: $ZipRoot"
}

$updated = 0
$skipped = 0
$processed = [System.Collections.Generic.List[object]]::new()

Get-ChildItem -LiteralPath $ZipRoot -Filter "NPDev_General_State_*.zip" -File | ForEach-Object {
    if ($_.BaseName -match '_(\d{8})_(\d{6})$') {
        $stampText = $matches[1] + "_" + $matches[2]
        $timestamp = [datetime]::ParseExact(
            $stampText,
            "yyyyMMdd_HHmmss",
            [Globalization.CultureInfo]::InvariantCulture
        )

        $_.CreationTime = $timestamp
        $_.LastWriteTime = $timestamp
        $_.LastAccessTime = $timestamp
        $updated++
        [void]$processed.Add([pscustomobject]@{
                name = $_.Name
                timestamp = $timestamp.ToString("o")
                status = "updated"
            })
    }
    else {
        $skipped++
        [void]$processed.Add([pscustomobject]@{
                name = $_.Name
                timestamp = $null
                status = "skipped"
            })
    }
}

$reportPath = Join-Path $ZipRoot "statezip-visible-timestamp-fix-report.json"
[pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    zipRoot = $ZipRoot
    updated = $updated
    skipped = $skipped
    processed = @($processed)
    overallStatus = "passed"
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host "OK    Updated visible timestamps for $updated state zip file(s). Skipped $skipped."
