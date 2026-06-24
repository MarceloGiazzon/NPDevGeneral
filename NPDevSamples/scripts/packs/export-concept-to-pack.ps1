param(
    [Parameter(Mandatory = $true)] [string]$ModelPath,
    [Parameter(Mandatory = $true)] [string]$ConceptName,
    [Parameter(Mandatory = $true)] [string]$PackName,
    [Parameter(Mandatory = $true)] [string]$Author,
    [string]$Category = "other",
    [string]$Description = "",
    [string]$Version = "1.0.0",
    [string]$Namespace = "",
    [string]$ForkedFromPack = "",
    [string]$ForkedFromVersion = "",
    [string]$ForkedFromAuthor = ""
)

# Author-time pack ecosystem (gap C.2, bounded MVP): a real, scripted "export from a project to
# the repo" path. Takes a concept's RAW model.json declaration (the same shape pack.json's own
# "concepts" array expects -- both follow model.schema.json's concept $def) and wraps it in a new
# pack.json under NPDevContract/packs/<PackName>/, stamped with author/category/optional fork
# attribution. This is the export half of the author ecosystem; the business UI's Store panel is
# read/browse-only (see business-ui-app.mustache's renderStorePanel drill-down) -- authoring a NEW
# pack from a project happens here, not in the running app.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    throw ("FAIL  " + $Message)
}
function Ok([string]$Message) {
    Write-Host ("OK    " + $Message) -ForegroundColor Green
}
function Info([string]$Message) {
    Write-Host ("INFO  " + $Message) -ForegroundColor Cyan
}

if (-not (Test-Path -LiteralPath $ModelPath)) {
    Fail "Model not found: $ModelPath"
}
if ($PackName -notmatch '^[a-z][a-z0-9_-]*$') {
    Fail "PackName must match ^[a-z][a-z0-9_-]*$ (pack.schema.json's identifier pattern), got: $PackName"
}

$scriptRoot = Split-Path -Parent $PSScriptRoot
$contractRoot = Resolve-Path (Join-Path $scriptRoot "..\..\NPDevContract")

# Read the category enum from pack.schema.json itself at runtime instead of a hand-duplicated
# copy here -- the two lists drifting apart silently was a real, if minor, gap (the export script
# could previously accept/reject categories pack.schema.json itself didn't agree with).
$packSchemaPath = Join-Path $contractRoot "schemas\pack.schema.json"
if (-not (Test-Path -LiteralPath $packSchemaPath)) {
    Fail "pack.schema.json not found at $packSchemaPath"
}
$packSchema = Get-Content -LiteralPath $packSchemaPath -Raw | ConvertFrom-Json
$validCategories = $packSchema.properties.category.enum
if (-not $validCategories -or $validCategories.Count -eq 0) {
    Fail "Could not read properties.category.enum from $packSchemaPath"
}
if ($validCategories -notcontains $Category) {
    Fail ("Category must be one of: " + ($validCategories -join ", ") + ", got: " + $Category)
}

$model = Get-Content -LiteralPath $ModelPath -Raw | ConvertFrom-Json
$concept = $model.concepts | Where-Object { $_.name -eq $ConceptName } | Select-Object -First 1
if (-not $concept) {
    $available = ($model.concepts | ForEach-Object { $_.name }) -join ", "
    Fail "Concept '$ConceptName' not found in $ModelPath. Available concepts: $available"
}
$packDir = Join-Path $contractRoot "packs\$PackName"
$packJsonPath = Join-Path $packDir "pack.json"
if (Test-Path -LiteralPath $packJsonPath) {
    Fail "Pack already exists, refusing to overwrite: $packJsonPath (choose a different -PackName or remove it first)"
}
New-Item -ItemType Directory -Force -Path $packDir | Out-Null

$pack = [ordered]@{
    '$schema'   = "../../schemas/pack.schema.json"
    dslVersion  = "1.0.0"
    pack        = $PackName
    version     = $Version
    description = if ($Description) { $Description } else { "Exported from concept '$ConceptName' in $ModelPath." }
    category    = $Category
    author      = $Author
    concepts    = @($concept)
}
if ($Namespace) {
    $pack.namespace = $Namespace
}
if ($ForkedFromPack -and $ForkedFromVersion) {
    $pack.forkedFrom = [ordered]@{
        pack         = $ForkedFromPack
        version      = $ForkedFromVersion
        originAuthor = $ForkedFromAuthor
    }
} elseif ($ForkedFromPack -or $ForkedFromVersion) {
    Fail "ForkedFromPack and ForkedFromVersion must both be set together (forkedFrom.version is required by pack.schema.json)"
}

($pack | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath $packJsonPath -Encoding UTF8
Ok "Exported concept '$ConceptName' to new pack: $packJsonPath"
Info "Pack identifier: $PackName  Category: $Category  Author: $Author"
