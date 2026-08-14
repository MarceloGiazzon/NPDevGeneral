param(
    [string]$BaseUrl = "http://localhost:8093",
    # R7 Stage D: "" means "resolve the live key" (Get-NpdevLiveApiKey below) -- a hardcoded
    # "api-dev" default stopped being reliably correct once Stage C's per-app random key can
    # replace it, depending on how the target app was launched. Pass -AdminApiKey explicitly to
    # force a specific value (e.g. a RED-proof against the old literal).
    [string]$AdminApiKey = "",
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
# A platform tenant created via /api/admin/tenants gets a wildcard-tenantId permission grant from
# generation onward (RuntimeApiEmitter emits blank tenantId, not the generation-time "dev" tenant) --
# so it can authenticate AND use generated CRUD immediately, with no restart and no hand-authored
# grant. Role still gates capability; tenant_id row scoping (demonstrated in Steps 5-7 below) is the
# separate, already-enforced mechanism that keeps one tenant's data from another's.

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

function Get-OrCreate-AppTenant([string]$ApiKey, [hashtable]$Body) {
    $existingList = @(Get-Rows (Invoke-Json -Method Get -Route "/api/tenants" -ApiKey $ApiKey))
    $existing = $existingList | Where-Object { $_.code -eq $Body.code } | Select-Object -First 1
    if ($null -ne $existing) {
        Ok ("App-level Tenant row already existed, reused: " + $Body.code)
        return $existing
    }
    $created = Invoke-Json -Method Post -Route "/api/tenants" -ApiKey $ApiKey -Body $Body
    Assert-Success -Result $created -Label ("Created app-level Tenant row: " + $Body.code)
    return $created
}

function Get-OrCreate-StaffMember([string]$ApiKey, [hashtable]$Body) {
    $existingList = @(Get-Rows (Invoke-Json -Method Get -Route "/api/staff_members" -ApiKey $ApiKey))
    $existing = $existingList | Where-Object { $_.email -eq $Body.email } | Select-Object -First 1
    if ($null -ne $existing) {
        Ok ("StaffMember row already existed, reused: " + $Body.email)
        return $existing
    }
    $created = Invoke-Json -Method Post -Route "/api/staff_members" -ApiKey $ApiKey -Body $Body
    Assert-Success -Result $created -Label ("Created StaffMember row: " + $Body.email)
    return $created
}

function Get-Rows($ListResponse) {
    # The generated Tenant list returns a bare array; other generated list endpoints return a paged
    # {content:[...], page, size, ...} wrapper. Handle both without assuming which shape a given
    # concept's route uses.
    if ($null -eq $ListResponse) { return @() }
    if ($ListResponse -is [System.Array]) { return @($ListResponse) }
    if ($ListResponse.PSObject.Properties.Name -contains "content") { return @($ListResponse.content) }
    return @($ListResponse)
}

try {
    $health = Invoke-RestMethod -Method Get -Uri ((Normalize-BaseUrl $BaseUrl) + "/actuator/health")
    Info ("App reachable, health: " + $health.status)
} catch {
    throw "The generated app is not reachable at $BaseUrl. Start it with run-generated-app.ps1 first. Details: $($_.Exception.Message)"
}

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "restaurant-saas-multitenant"

if ([string]::IsNullOrWhiteSpace($AdminApiKey)) {
    $AdminApiKey = Get-NpdevLiveApiKey -AppRoot $sample.AppRoot
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

Info "=== Step 4: each brand-new platform tenant creates its OWN app-level Tenant + StaffMember row ==="
$pizzaAppTenant = Get-OrCreate-AppTenant -ApiKey $pizzaKey -Body @{ code = "PIZZA"; displayName = "Pizza House"; plan = "Growth"; active = $true }
$pizzaAppTenantId = [string]$pizzaAppTenant.id
$pizzaStaff = Get-OrCreate-StaffMember -ApiKey $pizzaKey -Body @{ tenantRef = $pizzaAppTenantId; fullName = "Alice Staff"; email = "alice@pizza.test"; role = "Manager"; active = $true }

$sushiAppTenant = Get-OrCreate-AppTenant -ApiKey $sushiKey -Body @{ code = "SUSHI"; displayName = "Sushi Bar"; plan = "Starter"; active = $true }
$sushiAppTenantId = [string]$sushiAppTenant.id
$sushiStaff = Get-OrCreate-StaffMember -ApiKey $sushiKey -Body @{ tenantRef = $sushiAppTenantId; fullName = "Bob Staff"; email = "bob@sushi.test"; role = "Manager"; active = $true }

Info "=== Step 5: row isolation -- each platform tenant only ever sees its own rows ==="
$pizzaStaffList = @(Get-Rows (Invoke-Json -Method Get -Route "/api/staff_members" -ApiKey $pizzaKey))
if ($pizzaStaffList.Count -ne 1) { Fail "pizza-house should see exactly its own 1 staff member, saw $($pizzaStaffList.Count)" }
Ok "pizza-house's staff_members list shows exactly its own row (isolation confirmed)"
$sushiStaffList = @(Get-Rows (Invoke-Json -Method Get -Route "/api/staff_members" -ApiKey $sushiKey))
if ($sushiStaffList.Count -ne 1) { Fail "sushi-bar should see exactly its own 1 staff member, saw $($sushiStaffList.Count)" }
Ok "sushi-bar's staff_members list shows exactly its own row (isolation confirmed)"

Info "=== Step 6: cross-tenant READ is denied without confirming existence (404, not 403) ==="
$crossRead = Invoke-Json -Method Get -Route ("/api/tenants/" + $sushiAppTenantId) -ApiKey $pizzaKey
Assert-StatusCode -Result $crossRead -ExpectedStatusCode 404 -Label "pizza-house reading sushi-bar's Tenant row by id"

Info "=== Step 7: cross-tenant BOND WRITE is rejected (the fix verified live earlier this session) ==="
$crossBondWrite = Invoke-Json -Method Post -Route "/api/staff_members" -ApiKey $pizzaKey -Body @{
    tenantRef = $sushiAppTenantId
    fullName  = "Cross Tenant Staff"
    email     = "cross@pizza.test"
    role      = "Manager"
    active    = $true
}
Assert-StatusCode -Result $crossBondWrite -ExpectedStatusCode 422 -Label "pizza-house creating a StaffMember whose tenantRef points at sushi-bar's Tenant row"

Info "=== Step 8: disabling a platform tenant denies its (still-active) credential everywhere ==="
Invoke-Json -Method Post -Route "/api/admin/tenants/sushi-bar/disable" -ApiKey $AdminApiKey | Out-Null
$disabledMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $sushiKey
Assert-StatusCode -Result $disabledMe -ExpectedStatusCode 403 -Label "sushi-bar's credential, after its TENANT was disabled"
$disabledCrud = Invoke-Json -Method Get -Route "/api/staff_members" -ApiKey $sushiKey
Assert-StatusCode -Result $disabledCrud -ExpectedStatusCode 403 -Label "sushi-bar's credential on generated CRUD, after disable"

Info "=== Step 9: re-enabling restores access ==="
Invoke-Json -Method Post -Route "/api/admin/tenants/sushi-bar/enable" -ApiKey $AdminApiKey | Out-Null
$restoredMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $sushiKey
if ($restoredMe.tenantId -ne "sushi-bar") { Fail "sushi-bar credential did not resolve correctly after re-enable" }
Ok "sushi-bar's credential works again after re-enable"

Info "=== Step 10: revoke a credential directly (independent of tenant status) ==="
$pizzaCredentialId = [string]$pizzaCredential.credentialId
Invoke-Json -Method Post -Route ("/api/admin/credentials/" + $pizzaCredentialId + "/revoke") -ApiKey $AdminApiKey | Out-Null
$revokedMe = Invoke-Json -Method Get -Route "/api/me" -ApiKey $pizzaKey
Assert-StatusCode -Result $revokedMe -ExpectedStatusCode 401 -Label "pizza-house's credential, after being revoked"

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $outputDir = $sample.RunOutputRoot
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputPath = Join-Path $outputDir ("platform-tenancy-demo-" + $stamp + ".json")
}

$summary = [ordered]@{
    baseUrl     = $BaseUrl
    generatedAt = (Get-Date).ToString("o")
    platformTenants = @(
        [ordered]@{ tenantId = "pizza-house"; credentialId = $pizzaCredentialId; appTenantId = $pizzaAppTenantId; revoked = $true }
        [ordered]@{ tenantId = "sushi-bar"; credentialId = [string]$sushiCredential.credentialId; appTenantId = $sushiAppTenantId; disabledThenReEnabled = $true }
    )
    checks = [ordered]@{
        newTenantUsesCrudWithNoHandAuthoredGrant = $true
        rowIsolationConfirmed                    = $true
        crossTenantReadDenied404                  = $true
        crossTenantBondWriteDenied422              = $true
        tenantDisableDenies403                    = $true
        tenantReEnableRestores                    = $true
        credentialRevokeDenies401                 = $true
    }
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host ""
Ok ("Evidence written to " + $OutputPath)
Ok "Platform tenancy lifecycle (create/issue/use-CRUD/isolate/disable/enable/revoke) demonstrated end to end."
