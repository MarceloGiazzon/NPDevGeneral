<#
.SYNOPSIS
  Share the local NPDev validation service with an online chat AI through a tunnel (ngrok fixed address, or a free
  cloudflared quick tunnel).

.DESCRIPTION
  Starts NPDevMcp/validation_service.py --mcp (unless one with MCP is already listening on the port), starts a tunnel and
  prints the connector URL: <tunnel>/mcp/<token>. Paste that into the AI's "custom connector" screen.

  Two providers:
    * ngrok (recommended): one FREE address that never changes (ngrok gives every free account one static domain), so
      the connector is added to the AI ONCE and keeps working across restarts. One-time setup:
        1. Sign up (free) at https://dashboard.ngrok.com/signup and install ngrok from https://ngrok.com/download
        2. Run once, yourself:  ngrok config add-authtoken <your token from the dashboard>
        3. Copy your free static domain from the dashboard (looks like  name-1234.ngrok-free.app)
        4. Run this script once with -NgrokDomain <that domain>. It is remembered (tunnel.json in the state folder), so
           the next runs need no arguments.
      The authtoken stays in ngrok's own config; this script never reads, stores or prints it.
    * cloudflared quick tunnel (the fallback, no account): the address is RANDOM and changes on every run, so the
      connector has to be added again each time.
  -Provider auto (default) uses ngrok when a domain is known (-NgrokDomain or saved), otherwise cloudflared.

  Safety rules this script keeps:
    * It never downloads cloudflared or ngrok. If the program is missing it prints the official download page and stops,
      so no surprise binary lands on the machine.
    * It only ever stops the processes IT started (it tracks their PIDs); a validator that was already running is left
      alone, and so is anything else on the port.
    * The service binds 127.0.0.1 only; the tunnel connects locally. Nothing is opened on the router.
    * While this runs, anyone who has the connector link can validate models on this computer. Ctrl+C ends it.

.PARAMETER Port
  The service port (default 8780, same as `npdev validator serve`).

.PARAMETER Provider
  auto (default), cloudflare or ngrok.

.PARAMETER NgrokDomain
  Your free ngrok static domain (a bare host name like name-1234.ngrok-free.app; https:// is tolerated). Saved to
  tunnel.json so later runs do not need it.

.PARAMETER NgrokPath
  Full path to ngrok when it is not on PATH.

.PARAMETER CloudflaredPath
  Full path to cloudflared when it is not on PATH.

.PARAMETER StateDir
  Where the MCP token, tunnel.json and the logs live. Default: <BuildRoot>\validation-service (the same place
  `npdev validator serve` uses). Mainly for tests.

.PARAMETER PublicBaseUrl
  Testing only: probe this address instead of https://<NgrokDomain> when confirming the tunnel works end to end.

.PARAMETER TunnelTimeoutSeconds
  How long to wait for the tunnel to come up.

.EXAMPLE
  pwsh -NoProfile -File scripts/ai/Start-ValidatorTunnel.ps1 -NgrokDomain name-1234.ngrok-free.app   # first time
  pwsh -NoProfile -File scripts/ai/Start-ValidatorTunnel.ps1                                         # every time after
#>
param(
    [int]$Port = 8780,
    [ValidateSet("auto", "cloudflare", "ngrok")][string]$Provider = "auto",
    [string]$NgrokDomain = "",
    [string]$NgrokPath = "",
    [string]$CloudflaredPath = "",
    [string]$StateDir = "",
    [string]$PublicBaseUrl = "",
    [int]$TunnelTimeoutSeconds = 60,
    [int]$ServiceTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
# REG-144: the repo root comes from this script's own location, never from a directory NAME.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $repoRoot "scripts\npdev-common.ps1")

$cloudflaredDownloadPage = "https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"
$ngrokSignupPage = "https://dashboard.ngrok.com/signup"
$ngrokDownloadPage = "https://ngrok.com/download"

$stateDir = if ($StateDir) { $StateDir } else { Join-Path (Get-NPDevBuildRoot $repoRoot) "validation-service" }
$logDir = Join-Path $stateDir "logs"
$tokenFile = Join-Path $stateDir "token.txt"
$settingsFile = Join-Path $stateDir "tunnel.json"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$health = "http://127.0.0.1:$Port/api/health"

# --- remembered settings (tunnel.json: provider choice inputs only; never a secret) ------------------------------------
function Read-TunnelSettings {
    if (Test-Path -LiteralPath $settingsFile) {
        try { return (Get-Content -LiteralPath $settingsFile -Raw | ConvertFrom-Json) } catch { }
    }
    return [pscustomobject]@{}
}

# A bare host name only: this value ends up in a command line and in a URL, so nothing else is accepted.
function ConvertTo-NgrokDomain([string]$Text) {
    $domain = $Text.Trim().ToLowerInvariant() -replace "^https?://", ""
    $domain = $domain.TrimEnd("/")
    if ($domain -notmatch "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$") {
        throw "'$Text' is not a host name. Give just your ngrok domain, like name-1234.ngrok-free.app (no path, no spaces)."
    }
    return $domain
}

function Find-Program([string]$ExplicitPath, [string]$Name) {
    if ($ExplicitPath) {
        if (-not (Test-Path -LiteralPath $ExplicitPath)) { throw "$Name not found at: $ExplicitPath" }
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }
    $found = Get-Command $Name -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    return $null
}

$saved = Read-TunnelSettings
$savedDomain = if ($saved.PSObject.Properties["ngrokDomain"]) { [string]$saved.ngrokDomain } else { "" }
$provider = $Provider
if ($provider -eq "auto") {
    $provider = if ($NgrokDomain -or $savedDomain) { "ngrok" } else { "cloudflare" }
}

$domain = ""
if ($provider -eq "ngrok") {
    $domain = if ($NgrokDomain) { ConvertTo-NgrokDomain $NgrokDomain } elseif ($savedDomain) { ConvertTo-NgrokDomain $savedDomain } else { "" }
    if (-not $domain) {
        Write-Host "ngrok needs your free static domain (one-time setup):" -ForegroundColor Yellow
        Write-Host "  1. Sign up (free): $ngrokSignupPage"
        Write-Host "  2. Install ngrok:  $ngrokDownloadPage"
        Write-Host "  3. Run once:       ngrok config add-authtoken <your token from the dashboard>"
        Write-Host "  4. Copy your free static domain from the dashboard (like name-1234.ngrok-free.app)"
        Write-Host "  5. Run:            pwsh -NoProfile -File scripts/ai/Start-ValidatorTunnel.ps1 -NgrokDomain <that domain>"
        Write-Host "The domain is remembered, so next time you run the script with no arguments."
        exit 1
    }
    $tunnelBinary = Find-Program $NgrokPath "ngrok"
    if (-not $tunnelBinary) {
        Write-Host "ngrok was not found on this computer." -ForegroundColor Yellow
        Write-Host "Install it from the official page, then run this script again (or pass -NgrokPath):"
        Write-Host "  $ngrokDownloadPage"
        Write-Host "Then run once:  ngrok config add-authtoken <your token from the dashboard>"
        exit 1
    }
} else {
    $tunnelBinary = Find-Program $CloudflaredPath "cloudflared"
    if (-not $tunnelBinary) {
        Write-Host "cloudflared was not found on this computer." -ForegroundColor Yellow
        Write-Host "Install it from the official page, then run this script again (or pass -CloudflaredPath):"
        Write-Host "  $cloudflaredDownloadPage"
        exit 1
    }
}

$python = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { throw "Python was not found on PATH." }

function Get-ValidatorHealth {
    try { return Invoke-RestMethod -Uri $health -TimeoutSec 2 } catch { return $null }
}

function Test-PortTaken([int]$PortNumber) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $attempt = $client.BeginConnect("127.0.0.1", $PortNumber, $null, $null)
        if ($attempt.AsyncWaitHandle.WaitOne(500)) { $client.EndConnect($attempt); return $true }
        return $false
    } catch { return $false } finally { $client.Close() }
}

# End-to-end proof that the PUBLIC address reaches the validator with MCP on. The header makes ngrok skip its
# browser-warning page, which would otherwise answer a PowerShell request (its User-Agent says "Mozilla").
function Test-PublicHealth([string]$BaseUrl) {
    try {
        $answer = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 5 -Headers @{ "ngrok-skip-browser-warning" = "1" }
        return [bool]($answer -and $answer.mcp)
    } catch { return $false }
}

function Get-TunnelFailureText([string[]]$Logs) {
    $lines = @()
    foreach ($log in $Logs) {
        if (Test-Path -LiteralPath $log) { $lines += @(Get-Content -LiteralPath $log -ErrorAction SilentlyContinue) }
    }
    # ngrok repeats the same error several times at full length; one short line plus a hint is what a person needs.
    $short = { param($line) if ($line.Length -gt 300) { $line.Substring(0, 300) + " ..." } else { $line } }
    $code = $null
    foreach ($line in $lines) { if ($line -match "ERR_NGROK_\d+") { $code = $Matches[0]; break } }
    if ($code) {
        $text = "ngrok reported $code."
    } else {
        $errors = @($lines | Where-Object { $_ -match "lvl=eror|lvl=crit" } | Select-Object -Last 3)
        if (-not $errors.Count) { $errors = @($lines | Select-Object -Last 5) }
        $text = (@($errors | ForEach-Object { & $short $_ }) -join "`n")
    }
    $hint = ""
    if ($code -eq "ERR_NGROK_4018") { $hint = "`nHint: ngrok has no authtoken yet. Run once: ngrok config add-authtoken <your token from https://dashboard.ngrok.com/get-started/your-authtoken>" }
    elseif ($code -eq "ERR_NGROK_108") { $hint = "`nHint: the free plan allows ONE ngrok agent at a time. Stop any other ngrok (including one started by 'npdev host share') and retry." }
    elseif ($code -eq "ERR_NGROK_334") { $hint = "`nHint: that domain is already online from another ngrok agent. Stop it and retry." }
    return ($text + $hint)
}

$started = New-Object System.Collections.Generic.List[int]   # ONLY processes this script launched
try {
    # --- the service ----------------------------------------------------------------------------------------------------
    $existing = Get-ValidatorHealth
    if ($existing) {
        if (-not $existing.mcp) {
            throw "A validator is already running on port $Port WITHOUT --mcp. Stop it first, or pick another -Port."
        }
        Write-Host "Validator with MCP already running on port $Port - reusing it (it will be left running)."
    } else {
        if (Test-PortTaken $Port) { throw "Port $Port is taken by something that is not the NPDev validator. Pick another -Port." }
        $serviceArgs = @((Join-Path $repoRoot "NPDevMcp\validation_service.py"), "--mcp", "--port", "$Port", "--quiet",
            "--token-file", $tokenFile)
        $outLog = Join-Path $logDir "service.out.log"
        $errLog = Join-Path $logDir "service.err.log"
        $service = Start-Process -FilePath $python.Source -ArgumentList $serviceArgs -WorkingDirectory $repoRoot `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
        $started.Add($service.Id)
        Write-Host "Starting the validator (first start prepares the authoring context; log: $outLog) ..."
        $deadline = (Get-Date).AddSeconds($ServiceTimeoutSeconds)
        while ((Get-Date) -lt $deadline -and -not $service.HasExited -and -not (Get-ValidatorHealth)) { Start-Sleep -Milliseconds 500 }
        if ($service.HasExited) {
            $why = if (Test-Path -LiteralPath $errLog) { (Get-Content -LiteralPath $errLog -Raw) } else { "" }
            throw "The validator exited (code $($service.ExitCode)). $why"
        }
        if (-not (Get-ValidatorHealth)) { throw "The validator did not answer on $health within $ServiceTimeoutSeconds s." }
    }
    if (-not (Test-Path -LiteralPath $tokenFile)) { throw "The MCP token file is missing: $tokenFile" }
    $token = (Get-Content -LiteralPath $tokenFile -Raw).Trim()

    # --- the tunnel -----------------------------------------------------------------------------------------------------
    $logName = if ($provider -eq "ngrok") { "ngrok" } else { "tunnel" }   # cloudflared keeps its historical file names
    $tunnelOut = Join-Path $logDir "$logName.out.log"
    $tunnelErr = Join-Path $logDir "$logName.err.log"
    foreach ($log in @($tunnelOut, $tunnelErr)) { if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force } }

    $publicUrl = $null
    $confirmed = $false
    if ($provider -eq "ngrok") {
        $tunnel = Start-Process -FilePath $tunnelBinary `
            -ArgumentList @("http", "http://127.0.0.1:$Port", "--url", "https://$domain", "--log", "stdout") `
            -RedirectStandardOutput $tunnelOut -RedirectStandardError $tunnelErr -PassThru -WindowStyle Hidden
        $started.Add($tunnel.Id)
        Write-Host "Starting ngrok on the fixed address https://$domain ..."
        $publicUrl = "https://$domain"
        $probeBase = if ($PublicBaseUrl) { $PublicBaseUrl.TrimEnd("/") } else { $publicUrl }
        $deadline = (Get-Date).AddSeconds($TunnelTimeoutSeconds)
        while ((Get-Date) -lt $deadline -and -not $tunnel.HasExited -and -not $confirmed) {
            Start-Sleep -Milliseconds 1000
            $confirmed = Test-PublicHealth $probeBase
        }
        if ($tunnel.HasExited) {
            throw "ngrok exited (code $($tunnel.ExitCode)).`n$(Get-TunnelFailureText @($tunnelOut, $tunnelErr))`n(logs: $tunnelOut)"
        }
        if ($confirmed) {
            Write-Host "Confirmed: $publicUrl/api/health reaches the validator." -ForegroundColor Green
        } else {
            Write-Host "WARNING: ngrok is running but $publicUrl/api/health did not answer from this computer within $TunnelTimeoutSeconds s (logs: $tunnelOut)." -ForegroundColor Yellow
            Write-Host "The link below may still work from the internet; if the AI cannot reach it, check the log above."
        }
        # Remember the domain only now that ngrok accepted it (a typo never gets saved).
        if ($domain -ne $savedDomain) {
            @{ ngrokDomain = $domain } | ConvertTo-Json | Set-Content -LiteralPath $settingsFile -Encoding UTF8
            Write-Host "Saved the domain in $settingsFile - next time run this script with no arguments."
        }
    } else {
        $tunnel = Start-Process -FilePath $tunnelBinary -ArgumentList @("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$Port") `
            -RedirectStandardOutput $tunnelOut -RedirectStandardError $tunnelErr -PassThru -WindowStyle Hidden
        $started.Add($tunnel.Id)
        Write-Host "Waiting for cloudflared to announce its address ..."
        $deadline = (Get-Date).AddSeconds($TunnelTimeoutSeconds)
        while ((Get-Date) -lt $deadline -and -not $tunnel.HasExited -and -not $publicUrl) {
            Start-Sleep -Milliseconds 500
            foreach ($log in @($tunnelErr, $tunnelOut)) {
                if (-not $publicUrl -and (Test-Path -LiteralPath $log)) {
                    $text = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
                    if ($text -match "https://[a-z0-9-]+\.trycloudflare\.com") { $publicUrl = $Matches[0] }
                }
            }
        }
        if (-not $publicUrl) {
            throw "cloudflared did not announce an address within $TunnelTimeoutSeconds s (see $tunnelErr)."
        }
    }

    Write-Host ""
    Write-Host "Add this as a custom connector in the online AI:" -ForegroundColor Green
    Write-Host "  $publicUrl/mcp/$token"
    Write-Host ""
    Write-Host "While this window is open, anyone with that link can validate models on this computer."
    if ($provider -eq "ngrok") {
        Write-Host "This address is FIXED: add the connector once; it keeps working every time you run this script. Press Ctrl+C to stop."
    } else {
        Write-Host "The address changes every run (quick tunnel). For a fixed address use ngrok: -NgrokDomain <your free ngrok domain>. Press Ctrl+C to stop."
    }
    while (-not $tunnel.HasExited) { Start-Sleep -Seconds 2 }
    Write-Host "$logName ended (exit $($tunnel.ExitCode))."
} finally {
    foreach ($processId in $started) {
        $alive = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($alive) { Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue }
    }
}
