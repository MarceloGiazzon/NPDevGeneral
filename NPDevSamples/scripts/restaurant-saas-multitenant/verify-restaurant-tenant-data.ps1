param(
    [string]$BaseUrl = "http://localhost:8093",
    [string]$ApiKey = "api-dev"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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
    return Invoke-RestMethod -Method Get -Uri $uri -Headers @{ "X-Api-Key" = $ApiKey }
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
    @{ route = "staffmembers"; label = "Staff members" },
    @{ route = "diningtables"; label = "Dining tables" },
    @{ route = "menuitems"; label = "Menu items" },
    @{ route = "reservations"; label = "Reservations" },
    @{ route = "diningorders"; label = "Dining orders" },
    @{ route = "orderlines"; label = "Order lines" },
    @{ route = "paymentreceipts"; label = "Payment receipts" }
)

foreach ($routeInfo in $routes) {
    $rows = @(As-Array (Invoke-Get ([string]$routeInfo.route)))
    Write-Host ""
    Write-Host ([string]$routeInfo.label) -ForegroundColor Cyan
    if ($rows.Count -eq 0) {
        Write-Host " - no rows"
        continue
    }

    $groups = $rows | Group-Object -Property tenantId
    foreach ($group in $groups) {
        $name = Tenant-Name -TenantNames $tenantNames -TenantId ([string]$group.Name)
        Write-Host (" - " + $name + ": " + $group.Count)
    }
}

Write-Host ""
Write-Host "OK    Tenant-shaped data is present in generated CRUD APIs." -ForegroundColor Green
Write-Host "NOTE  This verifies modeled tenant references. It is not row-level authenticated tenant filtering." -ForegroundColor Yellow
