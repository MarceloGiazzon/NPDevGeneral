param(
    [string]$ReportPath = "scripts/reports/out/ai-beta-reproducibility-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Invoke-TextCommand {
    param([string]$Executable, [string[]]$Arguments = @())
    try {
        $output = & $Executable @Arguments 2>&1
        return (($output | Out-String).Trim())
    }
    catch {
        return ""
    }
}

function Invoke-GitText {
    param([string[]]$Arguments)
    try {
        $output = & git @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) {
            return ""
        }
        return (($output | Out-String).Trim())
    }
    catch {
        return ""
    }
}

function New-Sha256Text {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    return ([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::HashData($bytes)) -replace "-", "").ToLowerInvariant()
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "ai-beta-reproducibility-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$gitStatus = Invoke-GitText @("status", "--porcelain=v1")
$gradleWrapperProperties = Join-Path $workspaceRoot "NPDevGenerator/gradle/wrapper/gradle-wrapper.properties"
$gradleDistribution = if (Test-Path -LiteralPath $gradleWrapperProperties -PathType Leaf) {
    @((Get-Content -LiteralPath $gradleWrapperProperties) | Where-Object { $_ -match "^distributionUrl=" } | Select-Object -First 1) -replace "^distributionUrl=", ""
}
else {
    ""
}
$runtimeHostLibs = if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_RUNTIMEHOST_LIBS_DIR)) {
    $env:NPDEV_RUNTIMEHOST_LIBS_DIR
}
else {
    $workspace = Get-Item -LiteralPath $workspaceRoot
    $outsideRepoRoot = Join-Path $workspace.Parent.FullName ($workspace.Name + "__OutsideRepo")
    Join-Path $outsideRepoRoot "runtimehost-libs"
}

$report = [pscustomObject]@{
    schemaVersion = "npdev-ai-beta-reproducibility-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/write-ai-beta-reproducibility-report.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    environment = [pscustomobject]@{
        os = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        processArchitecture = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
        machineName = $env:COMPUTERNAME
        user = $env:USERNAME
        ci = [bool]($env:CI -eq "true")
        container = [bool]($env:NPDEV_AI_BETA_CONTAINER -eq "true")
        timezone = [System.TimeZoneInfo]::Local.Id
        culture = [System.Globalization.CultureInfo]::CurrentCulture.Name
    }
    tools = [pscustomobject]@{
        powershell = $PSVersionTable.PSVersion.ToString()
        java = Invoke-TextCommand "java" @("-version")
        node = Invoke-TextCommand "node" @("--version")
        npm = Invoke-TextCommand "npm" @("--version")
        gradleDistribution = $gradleDistribution
    }
    git = [pscustomobject]@{
        branch = Invoke-GitText @("branch", "--show-current")
        commit = Invoke-GitText @("rev-parse", "HEAD")
        dirty = -not [string]::IsNullOrWhiteSpace($gitStatus)
        dirtyFileCount = @($gitStatus -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
        dirtyHash = New-Sha256Text $gitStatus
    }
    network = [pscustomobject]@{
        policy = "disabled-except-localhost"
        localSmokeOnly = $true
        externalDependencyDownloadsAllowedOutsideProof = $true
    }
    cache = [pscustomobject]@{
        mode = "declared-local-cache"
        gradleUserHome = if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) { Join-Path $HOME ".gradle" } else { $env:GRADLE_USER_HOME }
        runtimeHostLibs = $runtimeHostLibs
        npmCache = Invoke-TextCommand "npm" @("config", "get", "cache")
        cleanScenarioWorkDirectory = $true
        generatedAppBuildUsesCleanTask = $true
        note = "Dependency caches may be reused; generated scenario output directories are recreated and generated apps run Gradle clean build."
    }
}

$reportDirectory = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
}
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
Write-Host ("AI beta reproducibility report written: " + $ReportPath)
