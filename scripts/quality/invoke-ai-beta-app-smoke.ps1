param(
    [Parameter(Mandatory = $true)][string]$AppRoot,
    [Parameter(Mandatory = $true)][string]$VerificationPath,
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [int]$Port = 18080,
    [string]$Profiles = "dev,step0,ai-beta-local",
    [int]$BootTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

function New-StageResult {
    param([string]$Status, [string]$Message, [object]$Evidence = $null)
    return [pscustomobject]@{
        status = $Status
        message = $Message
        evidence = $Evidence
    }
}

function Write-JsonReport {
    param([object]$Report)
    $reportDirectory = Split-Path -Parent $ReportPath
    if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
        New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    }
    $Report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}

function Convert-ResponseContentToString {
    param([object]$Content)
    if ($null -eq $Content) {
        return ""
    }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    return [string]$Content
}

function Get-DescendantProcessIds {
    param([int]$RootProcessId)
    $allProcesses = @(Get-CimInstance Win32_Process)
    $pending = [System.Collections.Generic.Queue[int]]::new()
    $descendants = [System.Collections.Generic.List[int]]::new()
    $pending.Enqueue($RootProcessId)
    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        foreach ($child in @($allProcesses | Where-Object { $_.ParentProcessId -eq $parentId })) {
            $childId = [int]$child.ProcessId
            $descendants.Add($childId) | Out-Null
            $pending.Enqueue($childId)
        }
    }
    return @($descendants)
}

function Stop-ProcessTree {
    param([int]$RootProcessId)
    if (-not $IsWindows) {
        Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
        return
    }
    $ids = @((Get-DescendantProcessIds -RootProcessId $RootProcessId) | Select-Object -Unique)
    [array]::Reverse($ids)
    foreach ($id in $ids) {
        if ($id -ne $PID) {
            Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        }
    }
    if ($RootProcessId -ne $PID) {
        Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Resolve-GradleWrapper {
    param(
        [string]$PrimaryRoot,
        [string]$FallbackRoot
    )
    $roots = @($PrimaryRoot, $FallbackRoot | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($root in $roots) {
        $windowsWrapper = Join-Path $root "gradlew.bat"
        $posixWrapper = Join-Path $root "gradlew"
        if ($IsWindows) {
            foreach ($candidate in @($windowsWrapper, $posixWrapper)) {
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
            }
        }
        else {
            foreach ($candidate in @($posixWrapper, $windowsWrapper)) {
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
            }
        }
    }
    throw ("No Gradle wrapper is available for generated app verification. Checked: " + ($roots -join ", "))
}

$appRootFull = (Resolve-Path -LiteralPath $AppRoot).Path
$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($env:NPDEV_RUNTIMEHOST_LIBS_DIR)) {
    $workspace = Get-Item -LiteralPath $workspaceRoot
    $outsideRepoRoot = Join-Path $workspace.Parent.FullName ($workspace.Name + "__OutsideRepo")
    $env:NPDEV_RUNTIMEHOST_LIBS_DIR = Join-Path $outsideRepoRoot "runtimehost-libs"
}
$gradlew = Resolve-GradleWrapper -PrimaryRoot $appRootFull -FallbackRoot (Join-Path $workspaceRoot "NPDevRuntimeHost")

$report = [ordered]@{
    schemaVersion = "npdev-ai-beta-app-smoke-result.v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/invoke-ai-beta-app-smoke.ps1"
    appRoot = $appRootFull
    baseUrl = "http://127.0.0.1:$Port"
    profiles = $Profiles
    build = $null
    boot = $null
    health = $null
    smoke = $null
    status = "failed"
}

$buildStdout = Join-Path $appRootFull "ai-beta-build.stdout.log"
$buildStderr = Join-Path $appRootFull "ai-beta-build.stderr.log"
$buildWatch = [System.Diagnostics.Stopwatch]::StartNew()
$build = Start-Process -FilePath $gradlew -ArgumentList @("--no-daemon", "clean", "build", "-x", "test", "--console=plain") -WorkingDirectory $appRootFull -NoNewWindow -Wait -PassThru -RedirectStandardOutput $buildStdout -RedirectStandardError $buildStderr
$buildWatch.Stop()
$report.build = New-StageResult -Status ($(if ($build.ExitCode -eq 0) { "passed" } else { "failed" })) -Message ("Gradle build exited " + $build.ExitCode + ".") -Evidence ([pscustomobject]@{
    exitCode = $build.ExitCode
    durationMs = [int]$buildWatch.ElapsedMilliseconds
    stdoutTail = if (Test-Path $buildStdout) { (Get-Content -Tail 40 -LiteralPath $buildStdout) -join "`n" } else { "" }
    stderrTail = if (Test-Path $buildStderr) { (Get-Content -Tail 40 -LiteralPath $buildStderr) -join "`n" } else { "" }
})
Write-JsonReport $report
if ($build.ExitCode -ne 0) {
    throw "Generated app build failed."
}

$bootStdout = Join-Path $appRootFull "ai-beta-boot.stdout.log"
$bootStderr = Join-Path $appRootFull "ai-beta-boot.stderr.log"
$bootArgs = @("--no-daemon", "bootRun", ('--args="--spring.profiles.active=' + $Profiles + ' --server.port=' + $Port + '"'))
$process = $null
try {
    $process = Start-Process -FilePath $gradlew -ArgumentList $bootArgs -WorkingDirectory $appRootFull -NoNewWindow -PassThru -RedirectStandardOutput $bootStdout -RedirectStandardError $bootStderr
    $report.boot = New-StageResult -Status "running" -Message "Generated app process started." -Evidence ([pscustomobject]@{
        processId = $process.Id
        command = ([System.IO.Path]::GetFileName($gradlew) + " " + ($bootArgs -join " "))
    })
    Write-JsonReport $report

    $healthUri = "http://127.0.0.1:$Port/actuator/health"
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $healthPassed = $false
    $lastHealthError = ""
    $healthResponse = $null
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            $lastHealthError = "Process exited before health check passed with code " + $process.ExitCode + "."
            break
        }
        try {
            $healthResponse = Invoke-WebRequest -Uri $healthUri -TimeoutSec 5 -SkipHttpErrorCheck
            $healthBody = Convert-ResponseContentToString $healthResponse.Content
            if ([int]$healthResponse.StatusCode -eq 200 -and $healthBody -match '"status"\s*:\s*"UP"') {
                $healthPassed = $true
                break
            }
            $lastHealthError = "Health returned " + [string]$healthResponse.StatusCode + ": " + $healthBody
        }
        catch {
            $lastHealthError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }

    $report.health = New-StageResult -Status ($(if ($healthPassed) { "passed" } else { "failed" })) -Message ($(if ($healthPassed) { "Health endpoint returned UP." } else { $lastHealthError })) -Evidence ([pscustomobject]@{
        uri = $healthUri
        statusCode = if ($null -ne $healthResponse) { [int]$healthResponse.StatusCode } else { $null }
        body = if ($null -ne $healthResponse) { Convert-ResponseContentToString $healthResponse.Content } else { "" }
    })
    if (-not $healthPassed) {
        Write-JsonReport $report
        throw "Generated app did not become healthy."
    }

    $smokeReportPath = Join-Path (Split-Path -Parent $ReportPath) "rest-smoke-result.json"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 -VerificationPath $VerificationPath -BaseUrl $report.baseUrl -ReportPath $smokeReportPath -ExpectedPort $Port | Out-Null
    $smokeExit = $LASTEXITCODE
    $smokeResult = if (Test-Path -LiteralPath $smokeReportPath -PathType Leaf) { Get-Content -Raw -LiteralPath $smokeReportPath | ConvertFrom-Json } else { $null }
    $report.smoke = New-StageResult -Status ($(if ($smokeExit -eq 0) { "passed" } else { "failed" })) -Message ($(if ($smokeExit -eq 0) { "REST smoke checks passed." } else { "REST smoke checks failed." })) -Evidence $smokeResult
    $report.status = if ($smokeExit -eq 0) { "passed" } else { "failed" }
    Write-JsonReport $report
    if ($smokeExit -ne 0) {
        throw "REST smoke checks failed."
    }
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-ProcessTree -RootProcessId $process.Id
    }
    if ($null -ne $report.boot) {
        $report.boot.status = "stopped"
        $report.boot.message = "Generated app process stopped after verification."
        Write-JsonReport $report
    }
}
