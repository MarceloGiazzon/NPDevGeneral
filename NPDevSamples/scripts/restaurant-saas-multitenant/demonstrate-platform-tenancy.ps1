param(
    [string]$BaseUrl = "http://localhost:8093",
    [string]$AdminApiKey = "api-dev",
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\sample-common.ps1")

# This script demonstrates the PLATFORM's own tenant lifecycle (npdev_tenant / npdev_api_credential),
# which is independent of -- and sits underneath -- this sample's own app-modeled Tenant concept and
# tenantRef reference field used by populate-restaurant-tenants.ps1. Both exist on purpose: tenantRef
# is "which restaurant a row is for" (a business fact an app author models); the platform's tenant_id
# is "which authenticated caller this row belongs to" (enforced automatically on every generated CRUD
# row, regardless of what the model declares).
#
# IMPORTANT, CONFIRMED LIMITATION: a tenant created here via /api/admin/tenants can authenticate
# (/api/me resolves correctly) but CANNOT use this sample's generated CRUD endpoints out of the box.
# The generated, signed dev.permissions.json only ever authors grants with tenantId="dev" -- the
# permission evaluator requires an EXACT tenantId match unless a grant's tenantId is blank (wildcard),
# and the generator never emits a blank-tenantId grant. So every brand-new platform tenant gets 403 on
# every generated CRUD call until someone hand-authors an additional grant for it (or a wildcard grant)
# in the permissions file -- which the admin tenant-lifecycle API does NOT do for you. This script
# demonstrates that 403 explicitly (Step 4) rather than working around it, because masking it would
# hide a real, currently-unresolved gap in the tenant lifecycle feature.

function Normalize-BaseUrl([string]$Value) {
    return $Value.TrimEnd("/")
}

function Invoke-Json([string]$Method, [string]$Route, [string]$ApiKey, [hashtable]$Body = $null) {
    $uri = (Normalize-BaseUrl $BaseUrl) + $Route
    $headers = @{ "X-Api-Key" = $ApiKey }
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 20
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body $json
        }
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    } catch {
        $statusCode = 0
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        return [pscustomobject]@{ NPDevError = $true; StatusCode = $statusCode; Message = $_.Exception.Message }
    }
}

function Assert-StatusCode([object]$Result, [int]$ExpectedStatusCode, [string]$Label) {
    $actual = if ($Result -is [pscustomobject] -and $Result.PSObject.Properties.Name -contains "NPDevError") { $Result.StatusCode } else { 200 }
    if ($actual -ne $ExpectedStatusCode) {
        Fail ("$Label expected status $ExpectedStatusCode but got $actual")
    }
    Ok ("$Label -> $actual as expected")
}

function Assert-Success([object]$Result, [string]$Label) {
    if ($Result -is [pscustomobject] -and $Result.PSObject.Properties.Name -contains "NPDevError") {
        Fail ("$Label failed with status $($Result.StatusCode): $($Result.Message)")
    }
    Ok $Label
}

function Get-OrCreate-PlatformTenant([string]$TenantId, [string]$DisplayName) {
    $created = Invoke-Json -Method Post -Route "/api/admin/tenants" -ApiKey $AdminApiKey -Body @{ tenantId = $TenantId; displayName = $DisplayName }
    if ($created -is [pscustomobject] -and $created.PSObject.Properties.Name -contains "NPDevError") {
        # Re-running this script against an already-populated database: the tenant likely exists
        # already (duplicate create currently returns 503, not 400/409 -- itself a finding worth
        # flagging, see the gaps report). Re-enable it in case a prior run left it disabled, and proceed.
        Invoke-Json -Method Post -Route ("/api/admin/tenants/" + $TenantId + "/enable") -ApiKey $AdminApiKey | Out-Null
        Ok ("Platform tenant already existed, reused: " + $TenantId)
        return [pscustomobject]@{ tenantId = $TenantId; displayName = $DisplayName; status = "ACTIVE" }
    }
    Ok ("Created platform tenant: " + ($created | ConvertTo-Json -Compress))
    return $created
}

try {
    $health = Invoke-RestMethod -Method Get -Uri ((Normalize-BaseUrl $BaseUrl) + "/actuator/health")
    Info ("App reachable, health: " + $health.status)
} catch {
    throw "The generated app is not reachable at $BaseUrl. Start it with run-generated-app.ps1 first. Details: $($_.Exception.Message)"
}

Info "=== Step 1: create two platform tenants (npdev_tenant) ==="
$pizzaTenant = Get-OrCreate-PlatformTenant -TenantId "pizza-house" -DisplayName "Pizza House"
$sushiTenant = Get-OrCreate-PlatformTenant -TenantId "sushi-bar" -DisplayName "Sushi Bar"

Info "=== Step 2: issue a runtime credential for each tenant (npdev_api_credential) -- no restart ==="
$pizzaCredential = Invoke-Json -Method Post -Route "/api/admin/credentials" -ApiKey $AdminApiKey -Body @{ tenantId = "pizza-house"; actorId = "alice"; roles = @("ADMIN") }
Assert-Success -Result $pizzaCredential -Label "Issued credential for pizza-house actor alice"
$pizzaKey = [string]$pizzaCredential.apiKey
$sushiCredential = Invoke-Json -Method Post -Route "/api/admin/credentials" -ApiKey $AdminApiKey -Body @{ tenantId = "sushi-bar"; actorId = "bob"; roles = @("ADMIN") }
Assert-Success -Result $sushiCredential -Label "Issued credential for sushi-bar actor bob"
$sushiKey = [string]$sushiCredential.apiKey

Info "=== Step 3: each issued key authenticates as its own platform tenant ==="
$pizzaMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $pizzaKey
if ($pizzaMe.tenantId -ne "pizza-house") { Fail "pizza credential did not resolve to tenant pizza-house" }
Ok ("pizza-house credential resolves to: " + ($pizzaMe | ConvertTo-Json -Compress))
$sushiMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $sushiKey
if ($sushiMe.tenantId -ne "sushi-bar") { Fail "sushi credential did not resolve to tenant sushi-bar" }
Ok ("sushi-bar credential resolves to: " + ($sushiMe | ConvertTo-Json -Compress))

Info "=== Step 4: CONFIRMED GAP -- a brand-new platform tenant authenticates but has no CRUD permission ==="
$pizzaCrudAttempt = Invoke-Json -Method Post -Route "/api/tenants" -ApiKey $pizzaKey -Body @{ code = "PIZZA"; displayName = "Pizza House"; plan = "Growth"; active = $true }
Assert-StatusCode -Result $pizzaCrudAttempt -ExpectedStatusCode 403 -Label "pizza-house's brand-new credential calling generated CRUD (create Tenant)"
Info "    This is BY DESIGN today, not a bug in this script: dev.permissions.json only authors grants"
Info "    with tenantId='dev'. The permission evaluator requires an exact tenantId match unless a"
Info "    grant's tenantId is blank (wildcard) -- and the generator never emits a blank-tenantId grant."
Info "    A new platform tenant is fully authenticated and isolated, but functionally inert for CRUD"
Info "    until someone hand-authors a grant for it. See the gaps report for the full writeup."

Info "=== Step 5: disabling a platform tenant denies its (still-active) credential everywhere ==="
Invoke-Json -Method Post -Route "/api/admin/tenants/sushi-bar/disable" -ApiKey $AdminApiKey | Out-Null
$disabledMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $sushiKey
Assert-StatusCode -Result $disabledMe -ExpectedStatusCode 403 -Label "sushi-bar's credential, after its TENANT was disabled"

Info "=== Step 6: re-enabling restores access ==="
Invoke-Json -Method Post -Route "/api/admin/tenants/sushi-bar/enable" -ApiKey $AdminApiKey | Out-Null
$restoredMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $sushiKey
if ($restoredMe.tenantId -ne "sushi-bar") { Fail "sushi-bar credential did not resolve correctly after re-enable" }
Ok "sushi-bar's credential works again after re-enable"

Info "=== Step 7: revoke a credential directly (independent of tenant status) ==="
$pizzaCredentialId = [string]$pizzaCredential.credentialId
Invoke-Json -Method Post -Route ("/api/admin/credentials/" + $pizzaCredentialId + "/revoke") -ApiKey $AdminApiKey | Out-Null
$revokedMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $pizzaKey
Assert-StatusCode -Result $revokedMe -ExpectedStatusCode 401 -Label "pizza-house's credential, after being revoked"

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
    $sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "restaurant-saas-multitenant"
    $outputDir = $sample.RunOutputRoot
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputPath = Join-Path $outputDir ("platform-tenancy-demo-" + $stamp + ".json")
}

$summary = [ordered]@{
    baseUrl     = $BaseUrl
    generatedAt = (Get-Date).ToString("o")
    platformTenants = @(
        [ordered]@{ tenantId = "pizza-house"; credentialId = $pizzaCredentialId; revoked = $true }
        [ordered]@{ tenantId = "sushi-bar"; credentialId = [string]$sushiCredential.credentialId; disabledThenReEnabled = $true }
    )
    checks = [ordered]@{
        newTenantAuthenticatesViaMe          = $true
        newTenantCrudDenied403NoGrant        = $true
        tenantDisableDenies403               = $true
        tenantReEnableRestores               = $true
        credentialRevokeDenies401            = $true
    }
    knownGap = "A platform tenant created via /api/admin/tenants authenticates successfully but has no generated-CRUD permission grant by default (dev.permissions.json only authors grants for tenantId=dev); see project gaps report."
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host ""
Ok ("Evidence written to " + $OutputPath)
Ok "Platform tenancy lifecycle (create/issue/authenticate/disable/enable/revoke) demonstrated end to end."
