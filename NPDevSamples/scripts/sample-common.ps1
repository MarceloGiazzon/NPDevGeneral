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

# LNCH-20 (2026-07-19, scoping + Phase 1 fix): every caller of this file used to hardcode
# "gradlew.bat" directly, which does not exist as an executable form on Linux/macOS (no shebang, no
# execute bit) -- the exact mismatch that made LNCH-19's Linux CI (npdev-pr-gate.yml, which calls
# generate-sample-app.ps1) likely to fail on ubuntu-latest despite PowerShell 7 itself being
# cross-platform. Same resolution order already proven correct in scripts/npdev-common.ps1's
# Get-NPDevGradleWrapperExecutable (used there by several quality-gate scripts) -- duplicated here
# rather than dot-sourcing that file, since NPDevSamples/scripts/ is a deliberately self-contained
# script family (it already has its own Get-NPDevLocalCacheRoot etc. rather than sharing npdev-common.ps1's).
function Get-NPDevGradleWrapperExecutable([string]$ProjectRoot) {
    $windowsWrapper = Join-Path $ProjectRoot "gradlew.bat"
    $posixWrapper = Join-Path $ProjectRoot "gradlew"
    if ($IsWindows) {
        if (Test-Path -LiteralPath $windowsWrapper -PathType Leaf) {
            return $windowsWrapper
        }
        if (Test-Path -LiteralPath $posixWrapper -PathType Leaf) {
            return $posixWrapper
        }
    }
    else {
        if (Test-Path -LiteralPath $posixWrapper -PathType Leaf) {
            return $posixWrapper
        }
        if (Test-Path -LiteralPath $windowsWrapper -PathType Leaf) {
            return $windowsWrapper
        }
    }

    throw ("Gradle wrapper not found in " + $ProjectRoot)
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

# R7 Stage D. Resolves the API key that ACTUALLY authenticates against a freshly generated app right
# now, regardless of which of this platform's launch pathways started it: Stage C's
# Ensure-NpdevApiKey (`<app>/secrets/api-key.env`, written once any of the three shipped launch
# pipelines -- `_ops/Run-FinalApp.ps1`, `Build-NpdevApp.ps1`, `Build-ClaudeApp.ps1` -- runs) or,
# absent that, the Spring `dev` profile's own accepted-risk static default (application-dev.yml),
# which is what a raw `gradlew bootRun` -- e.g. run-sample-app.ps1, or this sample family's own
# self-contained schema-evolution/promotion-lifecycle scripts, none of which call
# Ensure-NpdevApiKey -- still leaves active. Neither `dev-key` nor `api-dev` is safe to hardcode
# here: which one (if either) still authenticates depends entirely on how the target app was booted.
#
# ONE implementation of "what's the real key" (same instinct as R10, and MONITOR_PLAN's own
# single-verdict rule): shells out to `npdev monitor probe`, which already carries this exact
# fallback order (`npdev_monitor._read_live_api_key`, then the generation-time `plan.apiKey`) --
# this function must never re-parse `secrets/api-key.env` or `config.json` itself, or it becomes a
# second, potentially-drifting opinion.
function Get-NpdevLiveApiKey([string]$AppRoot) {
    $repoRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
    $cli = Join-Path $repoRoot "NPDevCli\npdev_cli.py"
    Ensure-File -PathValue $cli -Label "npdev CLI (npdev_cli.py)"
    Ensure-Directory -PathValue $AppRoot -Label "Generated app root"
    $json = & python $cli monitor probe --app-dir $AppRoot --json 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail ("'npdev monitor probe --app-dir " + $AppRoot + "' failed: " + ($json | Out-String).Trim())
    }
    $record = $json | ConvertFrom-Json
    $apiKey = [string]$record.apiKey
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        Fail ("npdev monitor probe returned no apiKey for " + $AppRoot + ". Has this app actually been generated (config.json / resolved-db-plan.json present)?")
    }
    return $apiKey
}

# T1/C2: application-dev.yml no longer seeds a known api-dev/dev-key pair, so `dev` now fails
# closed exactly like `default` and `prod` -- StartupValidator.validateAuth() refuses to boot
# without a key. A sample script that boots the app itself (raw `gradlew bootRun` / `java -jar`,
# never `_ops/Run-FinalApp.ps1`) must provision one BEFORE starting that process, the same way
# Ensure-NpdevApiKey (OperationalRunbookEmitter.java) does for the supported launchers -- Start-Process
# inherits the calling PowerShell session's environment, so setting $env:NPDEV_AUTH_API_KEYS /
# $env:NPDEV_AUTH_APIKEYS here reaches the child gradlew/java process without either variable ever
# being written to a generated file this repo owns.
#
# Same file, same contract as the generator's provisioner: idempotent, "present but unusable"
# (REG-157: empty file, or no non-comment line contains '=') treated as absent, printed once.
function Ensure-NpdevSampleApiKey([string]$AppRoot) {
    $secretsDir = Join-Path $AppRoot "secrets"
    $keyFile = Join-Path $secretsDir "api-key.env"
    $needsGeneration = -not (Test-Path -LiteralPath $keyFile)
    if (-not $needsGeneration) {
        $hasUsableMapping = $false
        foreach ($rawLine in (Get-Content -LiteralPath $keyFile)) {
            $line = $rawLine.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) { $hasUsableMapping = $true; break }
        }
        $needsGeneration = -not $hasUsableMapping
    }
    if ($needsGeneration) {
        if (-not (Test-Path -LiteralPath $secretsDir)) { New-Item -ItemType Directory -Force -Path $secretsDir | Out-Null }
        $bytes = New-Object byte[] 24
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        $key = ([Convert]::ToBase64String($bytes) -replace "[^a-zA-Z0-9]", "")
        Set-Content -LiteralPath $keyFile -Value ("NPDEV_AUTH_API_KEYS=" + $key + "=dev:developer:admin") -Encoding UTF8 -NoNewline
        Info ("Generated a new admin API key for this sample app, saved to: " + $keyFile)
    }
    foreach ($rawLine in (Get-Content -LiteralPath $keyFile)) {
        $line = $rawLine.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $name = $parts[0].Trim()
            if ($name) {
                Set-Item -Path ("env:" + $name) -Value $parts[1].Trim()
                if ($name -eq "NPDEV_AUTH_API_KEYS") { Set-Item -Path "env:NPDEV_AUTH_APIKEYS" -Value $parts[1].Trim() }
            }
        }
    }
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
