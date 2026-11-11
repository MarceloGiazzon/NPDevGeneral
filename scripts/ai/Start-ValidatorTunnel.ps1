<#
.SYNOPSIS
  Share the local NPDev validation service with an online chat AI through a free cloudflared quick tunnel.

.DESCRIPTION
  Starts NPDevMcp/validation_service.py --mcp (unless one with MCP is already listening on the port), starts
  `cloudflared tunnel --url http://127.0.0.1:<port>`, reads the https://<random>.trycloudflare.com address from its
  output and prints the connector URL: <tunnel>/mcp/<token>. Paste that into the AI's "custom connector" screen.

  Safety rules this script keeps:
    * It never downloads cloudflared. If the program is missing it prints the official download page and stops, so no
      surprise binary lands on the machine.
    * It only ever stops the processes IT started (it tracks their PIDs); a validator that was already running is left
      alone, and so is anything else on the port.
    * The service binds 127.0.0.1 only; the tunnel connects locally. Nothing is opened on the router.
    * The quick-tunnel address changes on every run, so the connector must be re-added each time (the price of free).
    * While this runs, anyone who has the connector link can validate models on this computer. Ctrl+C ends it.

.PARAMETER Port
  The service port (default 8780, same as `npdev validator serve`).

.PARAMETER CloudflaredPath
  Full path to cloudflared when it is not on PATH.

.PARAMETER TunnelTimeoutSeconds
  How long to wait for cloudflared to announce its address.

.EXAMPLE
  pwsh -NoProfile -File scripts/ai/Start-ValidatorTunnel.ps1
#>
param(
    [int]$Port = 8780,
    [string]$CloudflaredPath = "",
    [int]$TunnelTimeoutSeconds = 60,
    [int]$ServiceTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
# REG-144: the repo root comes from this script's own location, never from a directory NAME.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $repoRoot "scripts\npdev-common.ps1")

$downloadPage = "https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"

# --- cloudflared: found, or stop with the official link (never downloaded here) -----------------------------------------
$cloudflared = $null
if ($CloudflaredPath) {
    if (-not (Test-Path -LiteralPath $CloudflaredPath)) { throw "cloudflared not found at: $CloudflaredPath" }
    $cloudflared = (Resolve-Path -LiteralPath $CloudflaredPath).Path
} else {
    $found = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($found) { $cloudflared = $found.Source }
}
if (-not $cloudflared) {
    Write-Host "cloudflared was not found on this computer." -ForegroundColor Yellow
    Write-Host "Install it from the official page, then run this script again (or pass -CloudflaredPath):"
    Write-Host "  $downloadPage"
    exit 1
}

$python = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { throw "Python was not found on PATH." }

$stateDir = Join-Path (Get-NPDevBuildRoot $repoRoot) "validation-service"
$logDir = Join-Path $stateDir "logs"
$tokenFile = Join-Path $stateDir "token.txt"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$health = "http://127.0.0.1:$Port/api/health"

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
        $serviceArgs = @((Join-Path $repoRoot "NPDevMcp\validation_service.py"), "--mcp", "--port", "$Port", "--quiet")
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
    $tunnelOut = Join-Path $logDir "tunnel.out.log"
    $tunnelErr = Join-Path $logDir "tunnel.err.log"
    foreach ($log in @($tunnelOut, $tunnelErr)) { if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force } }
    $tunnel = Start-Process -FilePath $cloudflared -ArgumentList @("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$Port") `
        -RedirectStandardOutput $tunnelOut -RedirectStandardError $tunnelErr -PassThru -WindowStyle Hidden
    $started.Add($tunnel.Id)
    Write-Host "Waiting for cloudflared to announce its address ..."
    $publicUrl = $null
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

    Write-Host ""
    Write-Host "Add this as a custom connector in the online AI:" -ForegroundColor Green
    Write-Host "  $publicUrl/mcp/$token"
    Write-Host ""
    Write-Host "While this window is open, anyone with that link can validate models on this computer."
    Write-Host "The address changes every run. Press Ctrl+C to stop."
    while (-not $tunnel.HasExited) { Start-Sleep -Seconds 2 }
    Write-Host "cloudflared ended (exit $($tunnel.ExitCode))."
} finally {
    foreach ($processId in $started) {
        $alive = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($alive) { Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue }
    }
}
