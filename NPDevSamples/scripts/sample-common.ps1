Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Info([string]$Message) {
    Write-Host ("INFO  " + $Message) -ForegroundColor Cyan
}

function Ok([string]$Message) {
    Write-Host ("OK    " + $Message) -ForegroundColor Green
}

function Fail([string]$Message) {
    throw ("FAIL  " + $Message)
}

function Ensure-File([string]$PathValue, [string]$Label) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        Fail ($Label + " not found: " + $PathValue)
    }
}

function Ensure-Directory([string]$PathValue, [string]$Label) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Container)) {
        Fail ($Label + " not found: " + $PathValue)
    }
}

function Normalize-AbsolutePath([string]$PathValue) {
    return [System.IO.Path]::GetFullPath($PathValue)
}

function Get-NPDevLocalCacheRoot([string]$WorkspaceRoot) {
    if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_LOCAL_CACHE_ROOT)) {
        return Normalize-AbsolutePath $env:NPDEV_LOCAL_CACHE_ROOT
    }

    $localApplicationData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if (-not [string]::IsNullOrWhiteSpace($localApplicationData)) {
        return Normalize-AbsolutePath (Join-Path $localApplicationData "NPDev")
    }

    if (-not [string]::IsNullOrWhiteSpace($env:XDG_CACHE_HOME)) {
        return Normalize-AbsolutePath (Join-Path $env:XDG_CACHE_HOME "npdev")
    }

    if (-not [string]::IsNullOrWhiteSpace($env:HOME)) {
        return Normalize-AbsolutePath (Join-Path (Join-Path $env:HOME ".cache") "npdev")
    }

    return Normalize-AbsolutePath (Join-Path $WorkspaceRoot ".npdev-cache")
}

function Get-NPDevGradleUserHome([string]$WorkspaceRoot) {
    $gradleUserHome = if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_GRADLE_USER_HOME)) {
        Normalize-AbsolutePath $env:NPDEV_GRADLE_USER_HOME
    }
    else {
        Join-Path (Get-NPDevLocalCacheRoot $WorkspaceRoot) "gradle"
    }

    New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
    return $gradleUserHome
}

function Quote-Arg([string]$Value) {
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Get-NPDevSamplesRoot([string]$ScriptRoot) {
    return Normalize-AbsolutePath (Join-Path $ScriptRoot "..")
}

function Get-NPDevWorkspaceRoot([string]$SamplesRoot) {
    return Normalize-AbsolutePath (Join-Path $SamplesRoot "..")
}

function Resolve-NPDevSample([string]$SamplesRoot, [string]$SampleId) {
    if ([string]::IsNullOrWhiteSpace($SampleId)) {
        Fail "SampleId is required."
    }

    $sampleRoot = Normalize-AbsolutePath (Join-Path $SamplesRoot $SampleId)
    $inputRoot = Join-Path $sampleRoot "Input"
    $outputRoot = Join-Path $sampleRoot "Output"

    Ensure-Directory -PathValue $sampleRoot -Label "Sample root"
    Ensure-Directory -PathValue $inputRoot -Label "Sample Input folder"
    Ensure-Directory -PathValue $outputRoot -Label "Sample Output folder"

    return [PSCustomObject]@{
        SampleId = $SampleId
        SampleRoot = $sampleRoot
        InputRoot = $inputRoot
        OutputRoot = $outputRoot
        ModelPath = Join-Path $inputRoot "model.json"
        ConfigPath = Join-Path $inputRoot "config.json"
        ArtifactRoot = Join-Path $outputRoot "ArtifactNP"
        AppRoot = Join-Path $outputRoot "App"
        RunOutputRoot = Join-Path $outputRoot "RunOutput"
    }
}

function Read-SampleConfig([psobject]$Sample) {
    Ensure-File -PathValue $Sample.ConfigPath -Label "Sample config.json"
    return Get-Content -LiteralPath $Sample.ConfigPath -Raw | ConvertFrom-Json
}

function Get-ConfigValue([object]$Config, [object[]]$Path, $Fallback = $null) {
    $current = $Config
    foreach ($segment in $Path) {
        if ($null -eq $current) {
            return $Fallback
        }

        $current = $current.$segment
    }

    if ($null -eq $current) {
        return $Fallback
    }

    return $current
}

function Get-ConfigString([object]$Config, [object[]]$Path, [string]$Fallback = "") {
    $value = Get-ConfigValue -Config $Config -Path $Path -Fallback $null
    if ($null -eq $value) {
        return $Fallback
    }

    $text = [string]$value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $Fallback
    }

    return $text
}

function Get-ConfigInt([object]$Config, [object[]]$Path, [int]$Fallback) {
    $value = Get-ConfigValue -Config $Config -Path $Path -Fallback $null
    if ($null -eq $value) {
        return $Fallback
    }

    try {
        return [int]$value
    }
    catch {
        return $Fallback
    }
}
