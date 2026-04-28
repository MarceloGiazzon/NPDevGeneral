[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ContractGateReportPath = "",
    [string]$InventoryPath = "",
    [string]$MirrorReportPath = "",
    [string]$ReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "contract-schema-governance"

if ([string]::IsNullOrWhiteSpace($ContractGateReportPath)) {
    $ContractGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-gate-report.json"
}
else {
    $ContractGateReportPath = Normalize-NPDevPath $ContractGateReportPath
}

if ([string]::IsNullOrWhiteSpace($InventoryPath)) {
    $InventoryPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\contract-schema-version-inventory.json"
}
else {
    $InventoryPath = Normalize-NPDevPath $InventoryPath
}

if ([string]::IsNullOrWhiteSpace($MirrorReportPath)) {
    $MirrorReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\mirrored-sample-sync-report.json"
}
else {
    $MirrorReportPath = Normalize-NPDevPath $MirrorReportPath
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-schema-governance-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Get-ObjectPropertyNames {
    param(
        [AllowNull()][object]$Value
    )

    if ($null -eq $Value) {
        return @()
    }

    return @($Value.PSObject.Properties | Select-Object -ExpandProperty Name)
}

function Get-ObjectPropertyValue {
    param(
        [AllowNull()][object]$Value,
        [string]$PropertyName
    )

    if ($null -eq $Value) {
        return $null
    }

    $property = $Value.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Test-RequiredProperties {
    param(
        [AllowNull()][object]$Value,
        [string[]]$RequiredProperties
    )

    $propertyNames = Get-ObjectPropertyNames $Value
    $missing = @($RequiredProperties | Where-Object { $_ -notin $propertyNames })
    return [pscustomobject]@{
        valid = ($missing.Count -eq 0)
        missing = $missing
    }
}

function Read-JsonOrNull {
    param(
        [string]$PathValue
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }

    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}

function Test-ExpectedValues {
    param(
        [AllowNull()][object]$Document,
        [AllowNull()][object]$ExpectedValues
    )

    $mismatches = [System.Collections.Generic.List[object]]::new()
    if ($null -eq $Document -or $null -eq $ExpectedValues) {
        return [pscustomobject]@{
            valid = $false
            mismatches = @([pscustomobject]@{
                    property = "<document>"
                    expected = "present"
                    actual = $null
                })
        }
    }

    foreach ($expectedProperty in @($ExpectedValues.PSObject.Properties)) {
        $propertyName = [string]$expectedProperty.Name
        $expectedValue = $expectedProperty.Value
        $actualValue = Get-ObjectPropertyValue -Value $Document -PropertyName $propertyName

        $matches = if ($expectedValue -is [bool]) {
            [bool]$actualValue -eq [bool]$expectedValue
        }
        else {
            [string]$actualValue -eq [string]$expectedValue
        }

        if (-not $matches) {
            [void]$mismatches.Add([pscustomobject]@{
                    property = $propertyName
                    expected = $expectedValue
                    actual = $actualValue
                })
        }
    }

    return [pscustomobject]@{
        valid = ($mismatches.Count -eq 0)
        mismatches = @($mismatches)
    }
}

Ensure-NPDevFile $InventoryPath "Contract schema version inventory"
$inventory = Get-Content -LiteralPath $InventoryPath -Raw | ConvertFrom-Json
$contractGateReport = Read-JsonOrNull $ContractGateReportPath
$contractGateSchema = Test-RequiredProperties -Value $contractGateReport -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "workingDirectory",
    "command"
)

$schemaInventoryResults = [System.Collections.Generic.List[object]]::new()
foreach ($schemaEntry in @($inventory.schemas)) {
    $schemaPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$schemaEntry.path)
    $exists = Test-Path -LiteralPath $schemaPath -PathType Leaf
    $doc = if ($exists) { Read-JsonOrNull $schemaPath } else { $null }
    $expectedValueCheck = Test-ExpectedValues -Document $doc -ExpectedValues $schemaEntry.expectedValues
    [void]$schemaInventoryResults.Add([pscustomobject]@{
            name = [string]$schemaEntry.name
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $schemaPath
            exists = $exists
            matchesInventory = ($exists -and $expectedValueCheck.valid)
            mismatches = $expectedValueCheck.mismatches
        })
}

$aliasPolicy = $inventory.aliasBehavior
$compiledSchemaPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$aliasPolicy.compiledModelSchemaPath)
$compiledSchema = Read-JsonOrNull $compiledSchemaPath
$compiledProperties = if ($null -eq $compiledSchema) { $null } else { $compiledSchema.properties }
$conceptsProperty = if ($null -eq $compiledProperties) { $null } else { Get-ObjectPropertyValue -Value $compiledProperties -PropertyName ([string]$aliasPolicy.canonicalProperty) }
$entitiesProperty = if ($null -eq $compiledProperties) { $null } else { Get-ObjectPropertyValue -Value $compiledProperties -PropertyName ([string]$aliasPolicy.legacyProperty) }
$entitiesDescription = if ($null -eq $entitiesProperty) { "" } else { [string](Get-ObjectPropertyValue -Value $entitiesProperty -PropertyName "description") }
$entitiesDeprecated = if ($null -eq $entitiesProperty) { $false } else { [bool](Get-ObjectPropertyValue -Value $entitiesProperty -PropertyName "deprecated") }
$writerPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$aliasPolicy.writerPath)
$writerText = if (Test-Path -LiteralPath $writerPath -PathType Leaf) { Get-Content -LiteralPath $writerPath -Raw } else { "" }
$readerTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$aliasPolicy.readerTestPath)
$readerTestText = if (Test-Path -LiteralPath $readerTestPath -PathType Leaf) { Get-Content -LiteralPath $readerTestPath -Raw } else { "" }
$aliasBehaviorPassed = ($null -ne $conceptsProperty) -and `
    ($null -ne $entitiesProperty) -and `
    $entitiesDeprecated -and `
    $entitiesDescription.Contains("Readers may accept it") -and `
    $entitiesDescription.Contains("writers must emit concepts") -and `
    $writerText.Contains('root.set("concepts"') -and `
    (-not $writerText.Contains('root.set("entities"')) -and `
    $readerTestText.Contains("readsLegacyEntitiesAlias")

$requiredTestResults = [System.Collections.Generic.List[object]]::new()
foreach ($testEntry in @($inventory.requiredTests)) {
    $testPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$testEntry.path)
    $exists = Test-Path -LiteralPath $testPath -PathType Leaf
    $text = if ($exists) { Get-Content -LiteralPath $testPath -Raw } else { "" }
    $missingTokens = @($testEntry.requiredTokens | Where-Object { -not $text.Contains([string]$_) })
    [void]$requiredTestResults.Add([pscustomobject]@{
            name = [string]$testEntry.name
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $testPath
            exists = $exists
            missingTokens = $missingTokens
            passed = ($exists -and $missingTokens.Count -eq 0)
        })
}

$mirrorScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\sync-mirrored-samples.ps1"
Ensure-NPDevFile $mirrorScriptPath "Mirrored sample sync script"
$mirrorReport = $null
$mirrorError = $null
try {
    & $mirrorScriptPath -WorkspaceRoot $WorkspaceRoot -CheckOnly -ReportPath $MirrorReportPath | Out-Null
    $mirrorReport = Read-JsonOrNull $MirrorReportPath
}
catch {
    $mirrorError = $_.Exception.Message
    $mirrorReport = Read-JsonOrNull $MirrorReportPath
}
$mirrorSchema = Test-RequiredProperties -Value $mirrorReport -RequiredProperties @(
    "generatedAt",
    "overallStatus",
    "summary",
    "results"
)
$mirrorPassed = $mirrorSchema.valid -and [string]$mirrorReport.overallStatus -eq "passed" -and [int]$mirrorReport.summary.failed -eq 0

$checks = @(
    (New-NPDevCheckResult "contract-gate-current" $(if ($contractGateSchema.valid -and [string]$contractGateReport.overallStatus -eq "passed") { "passed" } else { "failed" }) $(if ($contractGateSchema.valid -and [string]$contractGateReport.overallStatus -eq "passed") { "The contract gate is green." } else { "The contract gate report is missing, incomplete, or failing." }) @{
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ContractGateReportPath
            missingProperties = $contractGateSchema.missing
            overallStatus = if ($null -eq $contractGateReport) { $null } else { [string]$contractGateReport.overallStatus }
        }),
    (New-NPDevCheckResult "schema-inventory-current" $(if (@($schemaInventoryResults | Where-Object { -not $_.matchesInventory }).Count -eq 0) { "passed" } else { "failed" }) $(if (@($schemaInventoryResults | Where-Object { -not $_.matchesInventory }).Count -eq 0) { "Schema inventory entries match the current contract assets." } else { "One or more schema inventory entries drift from the current contract assets." }) @{
            inventoryPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $InventoryPath
            results = @($schemaInventoryResults)
        }),
    (New-NPDevCheckResult "deprecated-alias-governance" $(if ($aliasBehaviorPassed) { "passed" } else { "failed" }) $(if ($aliasBehaviorPassed) { "Concepts remain canonical and entities remain legacy-reader-only." } else { "Canonical/legacy alias behavior drifted." }) @{
            compiledSchemaPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $compiledSchemaPath
            writerPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $writerPath
            readerTestPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $readerTestPath
            entitiesDeprecated = $entitiesDeprecated
            entitiesDescription = $entitiesDescription
        }),
    (New-NPDevCheckResult "deprecation-regression-coverage" $(if (@($requiredTestResults | Where-Object { -not $_.passed }).Count -eq 0) { "passed" } else { "failed" }) $(if (@($requiredTestResults | Where-Object { -not $_.passed }).Count -eq 0) { "Deprecation and schema regression tests are present." } else { "Required deprecation/schema regression coverage is missing." }) @{
            results = @($requiredTestResults)
        }),
    (New-NPDevCheckResult "mirror-sync-current" $(if ($mirrorPassed) { "passed" } else { "failed" }) $(if ($mirrorPassed) { "Mirrored sample schema assets remain exactly aligned." } else { "Mirrored sample schema assets drifted or could not be verified." }) @{
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MirrorReportPath
            mirrorError = $mirrorError
            overallStatus = if ($null -eq $mirrorReport) { $null } else { [string]$mirrorReport.overallStatus }
            failed = if ($null -eq $mirrorReport) { $null } else { [int]$mirrorReport.summary.failed }
        })
)

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$warningChecks = @($checks | Where-Object { $_.status -eq "warning" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedChecks.Count -gt 0) { "failed" } elseif ($warningChecks.Count -gt 0) { "warning" } else { "passed" }
    contractGateReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ContractGateReportPath
    inventoryPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $InventoryPath
    mirrorReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MirrorReportPath
    schemaInventory = @($schemaInventoryResults)
    aliasBehavior = [pscustomobject]@{
        compiledModelSchemaPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $compiledSchemaPath
        canonicalProperty = [string]$aliasPolicy.canonicalProperty
        legacyProperty = [string]$aliasPolicy.legacyProperty
        passed = $aliasBehaviorPassed
    }
    regressionCoverage = @($requiredTestResults)
    mirrorSync = if ($null -eq $mirrorReport) { $null } else { [pscustomobject]@{
            overallStatus = [string]$mirrorReport.overallStatus
            summary = $mirrorReport.summary
        } }
    checks = $checks
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = $warningChecks.Count
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Contract schema governance report generated."
    return
}

if ($report.overallStatus -eq "warning") {
    Write-NPDevWarn "Contract schema governance report generated with warnings."
    return
}

Write-NPDevWarn "Contract schema governance report failed."
throw "Contract schema governance report failed."
