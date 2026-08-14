param(
    [string]$BaseUrl = "http://localhost:8093",
    # R7 Stage D: "" means "resolve the live key" (Get-NpdevLiveApiKey below) -- a hardcoded
    # "api-dev" default stopped being reliably correct once Stage C's per-app random key can
    # replace it, depending on how the target app was launched. Pass -ApiKey explicitly to force
    # a specific value (e.g. a RED-proof against the old literal).
    [string]$ApiKey = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\sample-common.ps1")

function Normalize-BaseUrl([string]$Value) {
    return $Value.TrimEnd("/")
}

function As-Array($Value) {
    if ($null -eq $Value) { return @() }
    if ($Value -is [System.Array]) { return @($Value) }
    return ,@($Value)
}

function Invoke-Get([string]$Route) {
    $uri = (Normalize-BaseUrl $BaseUrl) + "/api/" + $Route
    $response = Invoke-RestMethod -Method Get -Uri $uri -Headers @{ "X-Api-Key" = $ApiKey }
    # Generated list endpoints return a paged wrapper {content:[...], page, size, ...}, not a bare array.
    if ($null -ne $response -and ($response.PSObject.Properties.Name -contains "content")) {
        return $response.content
    }
    return $response
}

function Tenant-Name([hashtable]$TenantNames, [string]$TenantId) {
    if ($TenantNames.ContainsKey($TenantId)) {
        return $TenantNames[$TenantId]
    }
    return $TenantId
}

try {
    $health = Invoke-RestMethod -Method Get -Uri ((Normalize-BaseUrl $BaseUrl) + "/actuator/health")
    Write-Host ("OK health: " + $health.status) -ForegroundColor Green
} catch {
    throw "The generated app is not reachable at $BaseUrl. Start it with run-generated-app.ps1 first. Details: $($_.Exception.Message)"
}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
    $sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "restaurant-saas-multitenant"
    $ApiKey = Get-NpdevLiveApiKey -AppRoot $sample.AppRoot
}

$tenants = @(As-Array (Invoke-Get "tenants"))
if ($tenants.Count -eq 0) {
    throw "No tenants found. Run populate-restaurant-tenants.ps1 first."
}

$tenantNames = @{}
foreach ($tenant in $tenants) {
    $tenantNames[[string]$tenant.id] = [string]$tenant.displayName
}

Write-Host ""
Write-Host "Tenants" -ForegroundColor Cyan
foreach ($tenant in $tenants) {
    Write-Host (" - " + $tenant.displayName + " :: " + $tenant.code + " :: " + $tenant.id)
}

$routes = @(
    @{ route = "staff_members"; label = "Staff members" },
    @{ route = "dining_tables"; label = "Dining tables" },
    @{ route = "menu_items"; label = "Menu items" },
    @{ route = "reservations"; label = "Reservations" },
    @{ route = "dining_orders"; label = "Dining orders" },
    @{ route = "order_lines"; label = "Order lines" },
    @{ route = "payment_receipts"; label = "Payment receipts" }
)

foreach ($routeInfo in $routes) {
    $rows = @(As-Array (Invoke-Get ([string]$routeInfo.route)))
    Write-Host ""
    Write-Host ([string]$routeInfo.label) -ForegroundColor Cyan
    if ($rows.Count -eq 0) {
        Write-Host " - no rows"
        continue
    }

    $groups = $rows | Group-Object -Property tenantRef
    foreach ($group in $groups) {
        $name = Tenant-Name -TenantNames $tenantNames -TenantId ([string]$group.Name)
        Write-Host (" - " + $name + ": " + $group.Count)
    }
}

Write-Host ""
Write-Host "OK    Tenant-shaped data is present in generated CRUD APIs." -ForegroundColor Green
Write-Host "NOTE  This verifies the app-modeled Tenant/tenantRef shape using a single ADMIN key." -ForegroundColor Yellow
Write-Host "NOTE  It does not exercise authenticated-tenant row filtering -- see demonstrate-platform-tenancy.ps1 for that." -ForegroundColor Yellow
