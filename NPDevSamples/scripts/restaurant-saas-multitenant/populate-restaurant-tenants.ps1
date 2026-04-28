param(
    [string]$BaseUrl = "http://localhost:8093",
    [string]$ApiKey = "api-dev",
    [string]$OutputPath = ""
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
    return @($Value)
}

function Invoke-Get([string]$Route) {
    $uri = (Normalize-BaseUrl $BaseUrl) + "/api/" + $Route
    return Invoke-RestMethod -Method Get -Uri $uri -Headers @{ "X-Api-Key" = $ApiKey }
}

function Invoke-Post([string]$Route, [hashtable]$Body) {
    $uri = (Normalize-BaseUrl $BaseUrl) + "/api/" + $Route
    $json = $Body | ConvertTo-Json -Depth 20
    Write-Host ("POST " + $uri)
    try {
        return Invoke-RestMethod -Method Post -Uri $uri -Headers @{ "X-Api-Key" = $ApiKey } -ContentType "application/json" -Body $json
    } catch {
        Write-Host "FAILED request body:" -ForegroundColor Red
        Write-Host $json
        if ($_.Exception.Response -and $_.Exception.Response.GetResponseStream()) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $responseBody = $reader.ReadToEnd()
            if (-not [string]::IsNullOrWhiteSpace($responseBody)) {
                Write-Host "FAILED response body:" -ForegroundColor Red
                Write-Host $responseBody
            }
        }
        throw
    }
}

function Find-ByProperty($Rows, [string]$PropertyName, [string]$ExpectedValue) {
    foreach ($row in (As-Array $Rows)) {
        $value = $row.$PropertyName
        if ($null -ne $value -and [string]$value -eq $ExpectedValue) {
            return $row
        }
    }
    return $null
}

function Get-OrCreate-Tenant([hashtable]$TenantBody) {
    $existing = Find-ByProperty (Invoke-Get "tenants") "code" ([string]$TenantBody.code)
    if ($null -ne $existing) {
        Write-Host ("REUSE tenant " + $TenantBody.code + " -> " + $existing.id) -ForegroundColor Yellow
        return $existing
    }
    return Invoke-Post "tenants" $TenantBody
}

try {
    $health = Invoke-RestMethod -Method Get -Uri ((Normalize-BaseUrl $BaseUrl) + "/actuator/health")
    Write-Host ("OK health: " + $health.status) -ForegroundColor Green
} catch {
    throw "The generated app is not reachable at $BaseUrl. Start it with run-generated-app.ps1 first. Details: $($_.Exception.Message)"
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
    $sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "restaurant-saas-multitenant"
    $outputDir = $sample.RunOutputRoot
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputPath = Join-Path $outputDir ("seed-tenant-data-" + $stamp + ".json")
}

$tenantPizza = Get-OrCreate-Tenant @{
    code = "pizza-house"
    displayName = "Pizza House"
    legalName = "Pizza House Ltd."
    plan = "Growth"
    timezone = "America/Sao_Paulo"
    primaryContactEmail = "owner@pizza-house.example"
    active = $true
}

$tenantSushi = Get-OrCreate-Tenant @{
    code = "sushi-bar"
    displayName = "Sushi Bar"
    legalName = "Sushi Bar Ltd."
    plan = "Growth"
    timezone = "America/Sao_Paulo"
    primaryContactEmail = "owner@sushi-bar.example"
    active = $true
}

$tenantVegan = Get-OrCreate-Tenant @{
    code = "vegan-bistro"
    displayName = "Vegan Bistro"
    legalName = "Vegan Bistro Ltd."
    plan = "Starter"
    timezone = "America/Sao_Paulo"
    primaryContactEmail = "owner@vegan-bistro.example"
    active = $true
}

$staffPizzaManager = Invoke-Post "staffmembers" @{
    tenantId = $tenantPizza.id
    fullName = "Maria Pizza"
    email = "maria@pizza-house.example"
    role = "Manager"
    active = $true
}

$staffPizzaWaiter = Invoke-Post "staffmembers" @{
    tenantId = $tenantPizza.id
    fullName = "Lucas Pizza"
    email = "lucas@pizza-house.example"
    role = "Waiter"
    active = $true
}

$staffSushiManager = Invoke-Post "staffmembers" @{
    tenantId = $tenantSushi.id
    fullName = "Hana Sushi"
    email = "hana@sushi-bar.example"
    role = "Manager"
    active = $true
}

$staffVeganManager = Invoke-Post "staffmembers" @{
    tenantId = $tenantVegan.id
    fullName = "Bea Vegan"
    email = "bea@vegan-bistro.example"
    role = "Manager"
    active = $true
}

$tablePizza = Invoke-Post "diningtables" @{ tenantId = $tenantPizza.id; tableNumber = "P1"; seats = 4; status = "Available" }
$tableSushi = Invoke-Post "diningtables" @{ tenantId = $tenantSushi.id; tableNumber = "S1"; seats = 2; status = "Reserved" }
$tableVegan = Invoke-Post "diningtables" @{ tenantId = $tenantVegan.id; tableNumber = "V1"; seats = 6; status = "Available" }

$itemPizza = Invoke-Post "menuitems" @{ tenantId = $tenantPizza.id; sku = "PIZZA-MARG"; name = "Margherita Pizza"; category = "Pizza"; priceCents = 4200; available = $true }
$itemPizzaDrink = Invoke-Post "menuitems" @{ tenantId = $tenantPizza.id; sku = "PIZZA-SODA"; name = "House Soda"; category = "Drink"; priceCents = 900; available = $true }
$itemSushi = Invoke-Post "menuitems" @{ tenantId = $tenantSushi.id; sku = "SUSHI-COMBO"; name = "Salmon Combo"; category = "Sushi"; priceCents = 5800; available = $true }
$itemSushiTea = Invoke-Post "menuitems" @{ tenantId = $tenantSushi.id; sku = "SUSHI-TEA"; name = "Green Tea"; category = "Drink"; priceCents = 700; available = $true }
$itemVegan = Invoke-Post "menuitems" @{ tenantId = $tenantVegan.id; sku = "VEGAN-BOWL"; name = "Garden Bowl"; category = "Vegan"; priceCents = 3600; available = $true }
$itemVeganDessert = Invoke-Post "menuitems" @{ tenantId = $tenantVegan.id; sku = "VEGAN-CAKE"; name = "Cocoa Cake"; category = "Dessert"; priceCents = 1600; available = $true }

$reservationPizza = Invoke-Post "reservations" @{
    tenantId = $tenantPizza.id
    tableId = $tablePizza.id
    customerName = "Carla Oliveira"
    customerPhone = "+55-11-90000-1001"
    partySize = 4
    reservationAt = "2026-04-15T20:00:00-03:00"
    status = "Confirmed"
}

$reservationSushi = Invoke-Post "reservations" @{
    tenantId = $tenantSushi.id
    tableId = $tableSushi.id
    customerName = "Kenji Tanaka"
    customerPhone = "+55-11-90000-2002"
    partySize = 2
    reservationAt = "2026-04-15T19:30:00-03:00"
    status = "Confirmed"
}

$reservationVegan = Invoke-Post "reservations" @{
    tenantId = $tenantVegan.id
    tableId = $tableVegan.id
    customerName = "Laura Lima"
    customerPhone = "+55-11-90000-3003"
    partySize = 5
    reservationAt = "2026-04-16T12:30:00-03:00"
    status = "Pending"
}

$orderPizza = Invoke-Post "diningorders" @{
    tenantId = $tenantPizza.id
    tableId = $tablePizza.id
    openedByStaffId = $staffPizzaWaiter.id
    openedAt = "2026-04-15T20:05:00-03:00"
    paidAt = "2026-04-15T21:00:00-03:00"
    status = "Paid"
    totalCents = 5100
    notes = "Pizza House dinner order"
}

$orderSushi = Invoke-Post "diningorders" @{
    tenantId = $tenantSushi.id
    tableId = $tableSushi.id
    openedByStaffId = $staffSushiManager.id
    openedAt = "2026-04-15T19:35:00-03:00"
    status = "Submitted"
    totalCents = 6500
    notes = "Sushi Bar table order"
}

$orderVegan = Invoke-Post "diningorders" @{
    tenantId = $tenantVegan.id
    tableId = $tableVegan.id
    openedByStaffId = $staffVeganManager.id
    openedAt = "2026-04-16T12:40:00-03:00"
    status = "Open"
    totalCents = 5200
    notes = "Vegan Bistro lunch order"
}

$linePizza1 = Invoke-Post "orderlines" @{ tenantId = $tenantPizza.id; orderId = $orderPizza.id; menuItemId = $itemPizza.id; quantity = 1; unitPriceCents = 4200; status = "Served" }
$linePizza2 = Invoke-Post "orderlines" @{ tenantId = $tenantPizza.id; orderId = $orderPizza.id; menuItemId = $itemPizzaDrink.id; quantity = 1; unitPriceCents = 900; status = "Served" }
$lineSushi1 = Invoke-Post "orderlines" @{ tenantId = $tenantSushi.id; orderId = $orderSushi.id; menuItemId = $itemSushi.id; quantity = 1; unitPriceCents = 5800; status = "Fired" }
$lineSushi2 = Invoke-Post "orderlines" @{ tenantId = $tenantSushi.id; orderId = $orderSushi.id; menuItemId = $itemSushiTea.id; quantity = 1; unitPriceCents = 700; status = "New" }
$lineVegan1 = Invoke-Post "orderlines" @{ tenantId = $tenantVegan.id; orderId = $orderVegan.id; menuItemId = $itemVegan.id; quantity = 1; unitPriceCents = 3600; status = "New" }
$lineVegan2 = Invoke-Post "orderlines" @{ tenantId = $tenantVegan.id; orderId = $orderVegan.id; menuItemId = $itemVeganDessert.id; quantity = 1; unitPriceCents = 1600; status = "New" }

$receiptPizza = Invoke-Post "paymentreceipts" @{
    tenantId = $tenantPizza.id
    orderId = $orderPizza.id
    amountCents = 5100
    provider = "demo-pos"
    paidAt = "2026-04-15T21:00:00-03:00"
}

$summary = [ordered]@{
    baseUrl = $BaseUrl
    generatedAt = (Get-Date).ToString("o")
    tenants = @($tenantPizza, $tenantSushi, $tenantVegan)
    staffMembers = @($staffPizzaManager, $staffPizzaWaiter, $staffSushiManager, $staffVeganManager)
    diningTables = @($tablePizza, $tableSushi, $tableVegan)
    menuItems = @($itemPizza, $itemPizzaDrink, $itemSushi, $itemSushiTea, $itemVegan, $itemVeganDessert)
    reservations = @($reservationPizza, $reservationSushi, $reservationVegan)
    diningOrders = @($orderPizza, $orderSushi, $orderVegan)
    orderLines = @($linePizza1, $linePizza2, $lineSushi1, $lineSushi2, $lineVegan1, $lineVegan2)
    paymentReceipts = @($receiptPizza)
}

$summary | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host ("OK    Seed data written to " + $OutputPath) -ForegroundColor Green
Write-Host "OK    Created tenant-scoped records through generated CRUD APIs." -ForegroundColor Green
