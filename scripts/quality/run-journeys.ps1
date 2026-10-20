<#
.SYNOPSIS
    JRN-1: the real-journey suite -- sign up, password reset, accept an invitation, change password,
    sign out and per-device session revocation, driven against ONE freshly generated, wiped
    jwt-mode app (NPDevSamples/journeys).

.DESCRIPTION
    An audit found password reset broken in every generated app and sign-out leaving the session
    cookie alive while every gate was green. This runner makes those paths a daily test result:

      1. generate + build NPDevSamples/journeys (identity pack, jwt auth, mail-smtp binding),
      2. start scripts/quality/journeys/smtp_sink.py -- a local SMTP sink that records each message
         the app sends, so no real mail server or credential is involved,
      3. boot the app on a wiped database with self-signup on and the mail capability pointed at the
         sink,
      4. run scripts/quality/journeys/run_journeys.py (REST journeys, the mailed one-time links read
         from the sink's file).

    Boots a real app, so this is T1/T2 (`jrn-journeys` in verification-cadence.json), never the
    static aiKnowledge gate. Refuses to start when its port is already taken and only ever stops
    processes it started.

.PARAMETER Port
    App port. Defaults to the sample's own runtime.serverPort (config.json).

.PARAMETER SmtpPort
    Port the SMTP sink listens on (127.0.0.1 only).

.PARAMETER BootTimeoutSeconds
    How long to wait for /actuator/health. 300s because `gradlew --no-daemon bootRun` forks a
    single-use daemon first; a genuine crash reports "Process exited before health check passed".

.PARAMETER ReuseBuild
    Skip generate + build and boot what is already under the sample's Output\App (iterating on the
    journeys themselves). The daily/CI run never uses it.

.PARAMETER SkipBrowser
    Run only the REST journeys. The default also drives the login page and the reset-password page
    in a real headless browser (ScrapForAI): sign in (wrong then right password), Create-account tab,
    the mailed link, choosing a password, and signing in with it; then a reset requested from the
    reset page, its mailed link, and signing in with the newest password.

.PARAMETER InstallBrowsers
    Also run `npx playwright install chromium` first. CI needs it; a developer machine usually has it.

.PARAMETER ReportPath
    Where to write the JSON report (schemaVersion npdev-journeys-result.v1).
#>
param(
    [string]$SampleId = "journeys",
    [int]$Port = 0,
    [int]$SmtpPort = 2526,
    [int]$BootTimeoutSeconds = 300,
    [switch]$ReuseBuild,
    [switch]$SkipBrowser,
    [switch]$InstallBrowsers,
    [string]$ReportPath = "scripts/reports/out/journeys-report.json"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repoRoot
try {

. (Join-Path $repoRoot "scripts\npdev-common.ps1")
Set-StrictMode -Off
. (Join-Path $repoRoot "NPDevSamples\scripts\sample-common.ps1")
. (Join-Path $repoRoot "NPDevSamples\scripts\browser\scrapforai-harness.ps1")

function Write-Section { param([string]$m) Write-Host "== $m ==" -ForegroundColor Cyan }

function Stop-ProcessTree {
    param([int]$RootProcessId)
    $ids = @((Get-NPDevDescendantProcessIds -RootProcessId $RootProcessId) | Select-Object -Unique)
    [array]::Reverse($ids)
    foreach ($id in $ids) {
        if ($id -ne $PID) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    }
    if ($RootProcessId -ne $PID) { Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue }
}

function Test-PortFree {
    param([int]$PortNumber)
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $PortNumber)
        $listener.Start()
        return $true
    } catch { return $false } finally { if ($null -ne $listener) { $listener.Stop() } }
}

$report = [ordered]@{
    schemaVersion = "npdev-journeys-result.v1"
    generatedAt   = (Get-Date).ToUniversalTime().ToString("o")
    sampleId      = $SampleId
    stages        = [ordered]@{}
    rest          = $null
    browser       = @()
    status        = "failed"
}
function Write-JsonReport {
    $dir = Split-Path -Parent $ReportPath
    if (-not [string]::IsNullOrWhiteSpace($dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}

$samplesRoot = Get-NPDevSamplesRoot -ScriptRoot (Join-Path $repoRoot "NPDevSamples\scripts")
# Resolve-NPDevSample insists the (gitignored) Output folder exists; a fresh checkout or a
# clean-workspace-state run has none, and generation creates the contents.
New-Item -ItemType Directory -Force -Path (Join-Path $samplesRoot "$SampleId\Output") | Out-Null
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId $SampleId
$config = Read-SampleConfig -Sample $sample
if ($Port -le 0) { $Port = Get-ConfigInt -Config $config -Path @("runtime", "serverPort") -Fallback 8190 }
$appRoot = $sample.AppRoot
$profiles = Get-ConfigString -Config $config -Path @("runtime", "springProfile") -Fallback "dev,trial"

foreach ($needed in @($Port, $SmtpPort)) {
    if (-not (Test-PortFree -PortNumber $needed)) {
        throw "Port $needed is already in use; refusing to start (this runner never kills a process it did not start)."
    }
}

if (-not $ReuseBuild) {
Write-Section "Generating $SampleId"
& pwsh -NoProfile -File (Join-Path $repoRoot "NPDevSamples\scripts\generate-sample-app.ps1") -SampleId $SampleId | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Generation failed for $SampleId." }
$report.stages.generate = "passed"

$gradlew = Get-NPDevGradleWrapperExecutable $appRoot
Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper"

Write-Section "Building $SampleId"
$buildOut = Join-Path $appRoot "journeys-build.stdout.log"
$buildErr = Join-Path $appRoot "journeys-build.stderr.log"
$build = Start-Process -FilePath $gradlew -ArgumentList @("--no-daemon", "build", "-x", "test", "--console=plain") -WorkingDirectory $appRoot -NoNewWindow -Wait -PassThru -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr
$report.stages.build = if ($build.ExitCode -eq 0) { "passed" } else { "failed (exit $($build.ExitCode); see $buildOut)" }
Write-JsonReport
if ($build.ExitCode -ne 0) { throw "Build failed for $SampleId -- see $buildOut." }
}
else {
    $gradlew = Get-NPDevGradleWrapperExecutable $appRoot
    Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper (run once without -ReuseBuild)"
}

$evidenceDir = Join-Path $sample.RunOutputRoot "journeys"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$mailFile = Join-Path $evidenceDir "mail.jsonl"
$py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }

$sink = $null
$app = $null
$savedEnv = @{}
$keysDir = Join-Path $evidenceDir "keys"
New-Item -ItemType Directory -Force -Path $keysDir | Out-Null
# application-dev.yml hardcodes mode apikey and pigmentampas gets its jwt block from a profile baked
# into the shared RuntimeHost template, so this fixture supplies the same settings through the
# environment (highest precedence). The keypair is auto-provisioned at these paths on first boot
# (JwtKeyPairEnvironmentPostProcessor), under the run's own Output tree.
$envToSet = @{
    "NPDEV_AUTH_MODE"                           = "jwt"
    "NPDEV_AUTH_JWT_ISSUER"                     = "journeys"
    "NPDEV_AUTH_JWT_AUDIENCE"                   = "journeys-api"
    "NPDEV_AUTH_JWT_PUBLIC_KEY_PATH"            = (Join-Path $keysDir "jwt-public.pem")
    "NPDEV_AUTH_JWT_PRIVATE_KEY_PATH"           = (Join-Path $keysDir "jwt-private-pkcs8.pem")
    "NPDEV_AUTH_JWT_EXPIRY_SECONDS"             = "3600"
    "NPDEV_AUTH_SIGNUP_ENABLED"                 = "true"
    "NPDEV_AUTH_PASSWORD_RESET_LINK_BASE_URL"   = "http://127.0.0.1:$Port/reset-password.html?token="
    "NPDEV_MAIL_SMTP_HOST"                      = "127.0.0.1"
    "NPDEV_MAIL_SMTP_PORT"                      = "$SmtpPort"
    "NPDEV_MAIL_SMTP_FROM"                      = "journeys@example.test"
    "NPDEV_MAIL_SMTP_STARTTLS"                  = "false"
}
try {
    Write-Section "Starting the SMTP sink on 127.0.0.1:$SmtpPort"
    $sink = Start-Process -FilePath $py -ArgumentList @((Join-Path $repoRoot "scripts\quality\journeys\smtp_sink.py"), "--port", "$SmtpPort", "--out", $mailFile) -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $evidenceDir "sink.stdout.log") -RedirectStandardError (Join-Path $evidenceDir "sink.stderr.log")
    Start-Sleep -Seconds 2
    if ($sink.HasExited) { throw "The SMTP sink exited immediately; see $evidenceDir\sink.stderr.log." }

    foreach ($key in $envToSet.Keys) {
        $savedEnv[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, $envToSet[$key])
    }

    # Regeneration spares <App>/data (CLAUDE.md), and bootstrap-admin refuses a tenant that already has a
    # user, so a daily suite must start from an empty database every run.
    $dataDir = Join-Path $appRoot "data"
    if ((Test-Path -LiteralPath $dataDir) -and $dataDir.StartsWith($sample.OutputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $dataDir -Recurse -Force
    }

    Write-Section "Booting $SampleId on port $Port"
    $bootOut = Join-Path $appRoot "journeys-boot.stdout.log"
    $bootErr = Join-Path $appRoot "journeys-boot.stderr.log"
    $app = Start-Process -FilePath $gradlew -ArgumentList @("--no-daemon", "bootRun", ('--args="--spring.profiles.active=' + $profiles + ' --server.port=' + $Port + '"')) -WorkingDirectory $appRoot -NoNewWindow -PassThru -RedirectStandardOutput $bootOut -RedirectStandardError $bootErr

    $baseUrl = "http://127.0.0.1:$Port"
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $healthy = $false
    $lastError = ""
    while ((Get-Date) -lt $deadline) {
        if ($app.HasExited) { $lastError = "Process exited before health check passed with code $($app.ExitCode)."; break }
        try {
            $health = Invoke-WebRequest -Uri "$baseUrl/actuator/health" -TimeoutSec 5 -SkipHttpErrorCheck
            # The actuator media type is not text/*, so Content arrives as byte[]; a bare [string] cast would not match.
            $healthBody = if ($health.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($health.Content) } else { [string]$health.Content }
            if ([int]$health.StatusCode -eq 200 -and $healthBody -match '"status"\s*:\s*"UP"') { $healthy = $true; break }
            $lastError = "Health returned $($health.StatusCode)"
        } catch { $lastError = $_.Exception.Message }
        Start-Sleep -Seconds 3
    }
    $report.stages.boot = if ($healthy) { "passed" } else { "failed ($lastError; see $bootOut)" }
    Write-JsonReport
    if (-not $healthy) { throw "Generated app did not become healthy: $lastError" }

    Write-Section "REST journeys"
    $restReport = Join-Path $evidenceDir "rest-report.json"
    & $py (Join-Path $repoRoot "scripts\quality\journeys\run_journeys.py") --base-url $baseUrl --mail-file $mailFile --report $restReport | Out-Host
    $restExit = $LASTEXITCODE
    if (Test-Path -LiteralPath $restReport) { $report.rest = Get-Content -Raw -LiteralPath $restReport | ConvertFrom-Json }

    $browserGreen = $true
    if (-not $SkipBrowser) {
        Write-Section "Browser journeys (login page, Create account, reset-password page)"
        $routineDir = Join-Path $repoRoot "NPDevSamples\scripts\browser\browser-routines"
        $browserDir = Join-Path $evidenceDir "browser"
        New-Item -ItemType Directory -Force -Path $browserDir | Out-Null
        $runStamp = (Get-Date).ToString("yyyyMMddHHmmss")
        $person = "browser-$runStamp@example.test"
        $signupPassword = "Browser-signup-pass-1"
        $resetPassword = "Browser-reset-pass-2"
        # A wrong password makes Chrome log a 401 for the fetch the page itself handles correctly; the
        # static login/reset pages carry no favicon, so Chrome logs one 404 per page load.
        $allowed = @("responded with a status of 401", "responded with a status of 404")

        function Get-MailedLink {
            param([string]$Address, [int]$After)
            $deadline = (Get-Date).AddSeconds(20)
            while ((Get-Date) -lt $deadline) {
                $lines = @(if (Test-Path -LiteralPath $mailFile) { Get-Content -LiteralPath $mailFile | Where-Object { $_.Trim() } } else { @() })
                for ($i = $After; $i -lt $lines.Count; $i++) {
                    $rec = $lines[$i] | ConvertFrom-Json
                    if (@($rec.to | ForEach-Object { "$_".ToLowerInvariant() }) -contains $Address.ToLowerInvariant()) {
                        $m = [regex]::Match([string]$rec.body, 'https?://[^/\s]+(/reset-password\.html\?[^\s"<>]+)')
                        if ($m.Success) { return $m.Groups[1].Value }
                    }
                }
                Start-Sleep -Milliseconds 400
            }
            return $null
        }
        function Get-MailCount { if (Test-Path -LiteralPath $mailFile) { return @(Get-Content -LiteralPath $mailFile | Where-Object { $_.Trim() }).Count } else { return 0 } }

        function Invoke-BrowserJourney {
            param([string]$RoutineName, [hashtable]$Variables = @{}, [string]$TargetPath = "")
            $name = [System.IO.Path]::GetFileNameWithoutExtension($RoutineName)
            Info ("=== Browser journey: " + $name + " ===")
            $status = "passed"; $message = "green"
            $result = Invoke-ScrapRoutine -Context $scrapCtx -RoutinePath (Join-Path $routineDir $RoutineName) -Variables $Variables -TargetPathOverride $TargetPath
            try { Assert-RoutineGreen -Result $result -Label $name -AllowConsoleErrorSubstrings $allowed | Out-Null }
            catch { $status = "failed"; $message = $_.Exception.Message; $script:browserGreen = $false }
            Save-RoutineEvidence -Result $result -OutDir $browserDir -Name $name | Out-Null
            $report.browser += [pscustomobject]@{ routine = $name; status = $status; message = $message }
            return ($status -eq "passed")
        }

        $scrapCtx = $null
        try {
            Initialize-ScrapForAI -InstallBrowsers:$InstallBrowsers | Out-Null
            $scrapCtx = Start-ScrapForAI -AppBaseUrl $baseUrl

            # 1. the admin the REST half created signs in through the real login page
            Invoke-BrowserJourney "journeys-signin-verify-routine.json" @{ username = "journey-admin"; password = "Admin-journey-pass-1" } | Out-Null

            # 2. Create account -> mailed link -> choose a password -> sign in with it
            $before = Get-MailCount
            if (Invoke-BrowserJourney "journeys-signup-verify-routine.json" @{ email = $person }) {
                $link = Get-MailedLink -Address $person -After $before
                if ($null -eq $link) { $browserGreen = $false; $report.browser += [pscustomobject]@{ routine = "signup-mailed-link"; status = "failed"; message = "no reset-password link was mailed to $person" } }
                elseif (Invoke-BrowserJourney "journeys-set-password-verify-routine.json" @{ password = $signupPassword } $link) {
                    Invoke-BrowserJourney "journeys-signin-verify-routine.json" @{ username = $person; password = $signupPassword } | Out-Null
                }
            }

            # 3. forgot password: request from the reset page -> mailed link -> new password -> sign in
            $before = Get-MailCount
            if (Invoke-BrowserJourney "journeys-reset-request-verify-routine.json" @{ username = $person }) {
                $link = Get-MailedLink -Address $person -After $before
                if ($null -eq $link) { $browserGreen = $false; $report.browser += [pscustomobject]@{ routine = "reset-mailed-link"; status = "failed"; message = "no reset-password link was mailed to $person" } }
                elseif (Invoke-BrowserJourney "journeys-set-password-verify-routine.json" @{ password = $resetPassword } $link) {
                    Invoke-BrowserJourney "journeys-signin-verify-routine.json" @{ username = $person; password = $resetPassword } | Out-Null
                }
            }
        }
        catch { $browserGreen = $false; $report.browser += [pscustomobject]@{ routine = "browser-harness"; status = "failed"; message = $_.Exception.Message } }
        finally { if ($null -ne $scrapCtx) { Stop-ScrapForAI $scrapCtx } }
    }

    $allGreen = ($restExit -eq 0) -and $browserGreen
    $report.status = if ($allGreen) { "passed" } else { "failed" }
    Write-JsonReport

    & $py (Join-Path $repoRoot "scripts\quality\cadence_state.py") record --id "jrn-journeys" --tier T1 --result ($(if ($allGreen) { "passed" } else { "failed" })) 2>&1 | Out-Null
    if (-not $allGreen) { throw "One or more journeys failed -- see $ReportPath and $restReport." }
    Write-Host "All identity journeys green against $SampleId." -ForegroundColor Green
}
finally {
    foreach ($key in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnv[$key]) }
    if ($null -ne $app -and -not $app.HasExited) { Stop-ProcessTree -RootProcessId $app.Id }
    if ($null -ne $sink -and -not $sink.HasExited) { Stop-ProcessTree -RootProcessId $sink.Id }
}

if ($report.status -ne "passed") { exit 1 }
exit 0

}
finally {
    Pop-Location
}
