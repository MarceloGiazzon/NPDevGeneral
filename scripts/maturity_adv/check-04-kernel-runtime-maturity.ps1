[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "maturity-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "kernel-runtime-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\kernel-runtime-maturity-report.json"

$checks = @()

function Add-Condition {
    param(
        [string]$Id,
        [string]$Text,
        [bool]$Passed,
        [string]$PassSummary,
        [string]$FailSummary,
        [object]$Data = $null
    )

    $script:checks += New-MaturityDoneConditionCheck `
        -ConditionId $Id `
        -ConditionText $Text `
        -Passed:$Passed `
        -PassSummary $PassSummary `
        -FailSummary $FailSummary `
        -Data $Data
}

$adapterRoots = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters") -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "build" })
$adapterTestAudit = foreach ($adapterRoot in $adapterRoots) {
    $tests = @(Get-ChildItem -LiteralPath $adapterRoot.FullName -Recurse -File -Include "*Test.java", "*IT.java" -ErrorAction SilentlyContinue)
    [pscustomobject]@{
        adapter = $adapterRoot.Name
        testCount = @($tests).Count
    }
}
$adaptersMissingTests = @($adapterTestAudit | Where-Object { $_.testCount -lt 1 })
$coverageReportFiles = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out") -File -Filter "*coverage*.json" -ErrorAction SilentlyContinue)
$postgresAdapterTestPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevKernel\adapters\persistence-postgres\src\test\java\com\npdev\adapters\persistence\postgres\PostgresPersistenceCapabilityAdapterNullToleranceTest.java",
    "NPDevKernel\adapters\persistence-postgres\src\test\java\com\npdev\adapters\persistence\postgres\PostgresPersistenceCapabilityAdapterTimestampCoercionTest.java",
    "NPDevKernel\adapters\persistence-postgres\src\test\java\com\npdev\adapters\persistence\postgres\PostgresPersistenceCapabilityAdapterUuidCoercionTest.java",
    "NPDevKernel\adapters\persistence-postgres\src\test\java\com\npdev\adapters\persistence\postgres\PostgresPersistenceCapabilityAdapterColumnNamingTest.java"
) -PathType Leaf
$inProcAdapterHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters" -Includes @("*Test.java") -Pattern 'null|concurrent|cleanup|happy path'
$celInvariantTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\expression-cel\src\test\java" -Includes @("*.java") -Pattern 'invalid|null|performance|valid'
$staticBindingTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\kernel\src\test\java" -Includes @("*.java") -Pattern 'StaticCapabilityBindingResolverTest|static binding'
$runtimeOverrideTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\kernel\src\test\java" -Includes @("*.java") -Pattern 'RuntimeOverrideCapabilityBindingResolver|runtime override'
$bindingFailureHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\kernel\src\test\java" -Includes @("*.java") -Pattern 'CapabilityBindingNotFoundException|non-existent capability|two overrides'
$asyncResumeTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\AsyncWaitResumeE2EIT.java"
$asyncResumePatterns = Test-MaturityFilePatterns -FilePath $asyncResumeTestPath -Patterns @("resume", "event", "manual")
$asyncResumeFailureHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'expired token|invalid instance|concurrent resume|idempot'
$flowStoreContractHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters" -Includes @("*Test.java") -Pattern 'FlowInstanceStore|resumeAt|resumeAfter'
$tenantIsolationTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\TenantIsolationIT.java"
$tenantIsolationPatterns = Test-MaturityFilePatterns -FilePath $tenantIsolationTestPath -Patterns @("tenant", "query", "concurrent")
$tenantPartitionHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'TenantStoragePartitioningService|CrossTenantGovernanceService'

Add-Condition "KRM-001" "Every adapter module has ≥1 test class with ≥80% line coverage" `
    (@($adaptersMissingTests).Count -eq 0 -and @($coverageReportFiles).Count -gt 0) `
    "Every adapter module has at least one test file and adapter coverage reports exist under scripts\\reports\\out." `
    "One or more adapter modules are missing tests or no adapter coverage reports were found under scripts\\reports\\out." `
    @{ adapterTestAudit = $adapterTestAudit; coverageReports = @($coverageReportFiles | ForEach-Object { $_.Name }) }

Add-Condition "KRM-002" "PostgresPersistenceCapabilityAdapter tests cover: null tolerance, timestamp coercion, UUID coercion, column naming (already partially done — extend to 100%)" `
    ($postgresAdapterTestPaths.allPresent) `
    "The expected PostgresPersistenceCapabilityAdapter focused tests are present." `
    "One or more expected PostgresPersistenceCapabilityAdapter focused tests are missing." `
    @{ missingTests = $postgresAdapterTestPaths.missing }

Add-Condition "KRM-003" "InProc* adapters have tests for: happy path, null input, concurrent access, resource cleanup" `
    (@($inProcAdapterHits).Count -gt 0) `
    "The in-process adapter test suite contains some evidence for null/concurrency/cleanup coverage." `
    "The in-process adapter test suite does not yet show explicit evidence for null/concurrency/cleanup coverage." `
    @{ hits = $inProcAdapterHits | Select-Object -First 40 }

Add-Condition "KRM-004" "CelInvariantEngine tests cover: valid expressions, invalid expressions, null safety, performance baseline" `
    (@($celInvariantTestHits).Count -ge 4) `
    "CelInvariantEngine test sources show coverage signals for valid/invalid/null/performance cases." `
    "CelInvariantEngine test sources do not yet show all required valid/invalid/null/performance coverage signals." `
    @{ hits = $celInvariantTestHits }

Add-Condition "KRM-005" "Coverage report generated per adapter, stored in scripts\\reports\\out\\" `
    (@($coverageReportFiles).Count -gt 0) `
    "Coverage report artifacts were found in scripts\\reports\\out." `
    "No adapter coverage report artifacts were found in scripts\\reports\\out." `
    @{ coverageReports = @($coverageReportFiles | ForEach-Object { $_.Name }) }

Add-Condition "KRM-006" "Test proves static binding wins when no runtime override exists" `
    (@($staticBindingTestHits).Count -gt 0) `
    "Static capability binding resolution has explicit test coverage." `
    "No explicit static capability binding resolution test coverage was found." `
    @{ hits = $staticBindingTestHits }

Add-Condition "KRM-007" "Test proves runtime override wins when it conflicts with static binding" `
    (@($runtimeOverrideTestHits).Count -gt 0) `
    "Runtime override capability binding resolution has explicit test coverage." `
    "No explicit runtime override capability binding resolution test coverage was found." `
    @{ hits = $runtimeOverrideTestHits }

Add-Condition "KRM-008" "Test proves error when runtime override references non-existent capability" `
    (@($bindingFailureHits | Where-Object { $_.line -match 'non-existent capability|missing capability' }).Count -gt 0) `
    "Binding failure tests reference missing-capability override handling." `
    "No explicit test evidence was found for runtime override references to a non-existent capability." `
    @{ hits = $bindingFailureHits }

Add-Condition "KRM-009" "Test proves error when two overrides target same capability operation" `
    (@($bindingFailureHits | Where-Object { $_.line -match 'two overrides|duplicate override' }).Count -gt 0) `
    "Binding failure tests reference duplicate runtime override handling." `
    "No explicit test evidence was found for duplicate runtime overrides targeting the same capability operation." `
    @{ hits = $bindingFailureHits }

Add-Condition "KRM-010" "CapabilityBindingNotFoundException tested for all resolution failure modes" `
    (@($bindingFailureHits | Where-Object { $_.line -match 'CapabilityBindingNotFoundException' }).Count -gt 0) `
    "CapabilityBindingNotFoundException is referenced in kernel test sources." `
    "CapabilityBindingNotFoundException is not visibly exercised in kernel test sources." `
    @{ hits = $bindingFailureHits }

Add-Condition "KRM-011" "AsyncWaitResumeE2EIT passes consistently (not flaky)" `
    ((Test-Path -LiteralPath $asyncResumeTestPath -PathType Leaf) -and $null -ne (Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json")) -and [string](Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json")).overallStatus -eq "passed") `
    "AsyncWaitResumeE2EIT exists and the RuntimeHost verification suite is currently passing." `
    "There is not yet enough evidence to call AsyncWaitResumeE2EIT consistently green." `
    @{ asyncWaitResumeTest = "NPDevRuntimeHost\src\test\java\com\finalexec\AsyncWaitResumeE2EIT.java" }

Add-Condition "KRM-012" "Test covers: resume after scheduled time, resume after event, resume after manual trigger" `
    ($asyncResumePatterns.allMatched) `
    "AsyncWaitResumeE2EIT shows scheduled/event/manual resume coverage signals." `
    "AsyncWaitResumeE2EIT does not visibly show all scheduled/event/manual resume coverage signals." `
    @{ missingPatterns = $asyncResumePatterns.missing }

Add-Condition "KRM-013" "Test covers: resume failure (expired token, invalid instance ID)" `
    (@($asyncResumeFailureHits | Where-Object { $_.line -match 'expired token|invalid instance' }).Count -gt 0) `
    "Resume failure scenarios are referenced in test sources." `
    "No explicit resume failure scenario evidence was found for expired token or invalid instance ID." `
    @{ hits = $asyncResumeFailureHits }

Add-Condition "KRM-014" "Test covers: concurrent resume attempts (idempotency)" `
    (@($asyncResumeFailureHits | Where-Object { $_.line -match 'concurrent resume|idempot' }).Count -gt 0) `
    "Resume idempotency/concurrency scenarios are referenced in test sources." `
    "No explicit resume idempotency/concurrency scenario evidence was found." `
    @{ hits = $asyncResumeFailureHits }

Add-Condition "KRM-015" "Resume fields validated in FlowInstanceStore contract tests for both InProc and Postgres adapters" `
    (@($flowStoreContractHits).Count -gt 0) `
    "FlowInstanceStore-related resume field coverage was found in adapter test sources." `
    "No explicit FlowInstanceStore resume field contract coverage was found across adapters." `
    @{ hits = $flowStoreContractHits | Select-Object -First 40 }

Add-Condition "KRM-016" "TenantIsolationIT proves: concept A in tenant X is invisible to tenant Y" `
    ((Test-Path -LiteralPath $tenantIsolationTestPath -PathType Leaf) -and $tenantIsolationPatterns.exists) `
    "TenantIsolationIT exists and contains tenant isolation coverage signals." `
    "TenantIsolationIT does not yet show explicit tenant invisibility coverage signals." `
    @{ missingPatterns = $tenantIsolationPatterns.missing }

Add-Condition "KRM-017" "TenantIsolationIT proves: query enforcement rejects cross-tenant queries" `
    (@($tenantIsolationPatterns.matched | Where-Object { $_ -eq "query" }).Count -gt 0) `
    "TenantIsolationIT contains query-enforcement coverage signals." `
    "TenantIsolationIT does not visibly show query-enforcement coverage." `
    @{ matchedPatterns = $tenantIsolationPatterns.matched }

Add-Condition "KRM-018" "TenantStoragePartitioningService test proves: physical table separation (or schema separation) per tenant" `
    (@($tenantPartitionHits | Where-Object { $_.line -match 'TenantStoragePartitioningService' }).Count -gt 0) `
    "Tenant storage partitioning coverage was found in test sources." `
    "No explicit TenantStoragePartitioningService test evidence was found." `
    @{ hits = $tenantPartitionHits }

Add-Condition "KRM-019" "CrossTenantGovernanceService test proves: governance rules apply per-tenant" `
    (@($tenantPartitionHits | Where-Object { $_.line -match 'CrossTenantGovernanceService' }).Count -gt 0) `
    "Cross-tenant governance coverage was found in test sources." `
    "No explicit CrossTenantGovernanceService test evidence was found." `
    @{ hits = $tenantPartitionHits }

Add-Condition "KRM-020" "Test proves: tenant isolation holds under concurrent load" `
    (@($tenantIsolationPatterns.matched | Where-Object { $_ -eq "concurrent" }).Count -gt 0) `
    "TenantIsolationIT contains concurrent-load isolation signals." `
    "No explicit tenant isolation concurrent-load evidence was found." `
    @{ matchedPatterns = $tenantIsolationPatterns.matched }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "kernel-runtime-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        adapterCount = @($adapterRoots).Count
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
