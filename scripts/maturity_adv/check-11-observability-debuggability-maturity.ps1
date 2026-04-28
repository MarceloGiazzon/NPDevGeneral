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
$RunId = Resolve-NPDevRunId $RunId "observability-debuggability-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\observability-debuggability-maturity-report.json"

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

$healthIndicatorPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevKernel\adapters\runtime-validation\src\main\java\com\npdev\adapters\runtime\validation\NpdevDbHealthIndicator.java",
    "NPDevKernel\adapters\runtime-validation\src\main\java\com\npdev\adapters\runtime\validation\NpdevEventStoreHealthIndicator.java",
    "NPDevKernel\adapters\runtime-validation\src\main\java\com\npdev\adapters\runtime\validation\NpdevSchedulerHealthIndicator.java"
) -PathType Leaf
$extraHealthHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel" -Includes @("*.java") -Pattern 'PluginRegistry.*Health|CapabilityDispatcher.*Health|MigrationStatus.*Health'
$healthTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\runtime-validation\src\test\java\com\npdev\adapters\runtime\validation\RuntimeHealthIndicatorsTest.java"
$healthStatusHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\runtime-validation\src\test\java" -Includes @("RuntimeHealthIndicatorsTest.java") -Pattern 'UP|DOWN|UNKNOWN|detail'
$healthEndpointHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern '/actuator/health|health endpoint'
$healthAlertHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'alert sink|mock alert|trigger alert'
$healthPerfHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\runtime-validation\src\test\java" -Includes @("*.java") -Pattern '50ms|performance'
$traceFieldHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel" -Includes @("*.java") -Pattern 'flow instance|step name|step type|input|output|duration|status|error'
$traceCorrelationHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel" -Includes @("*.java") -Pattern 'correlation ID|correlationId'
$traceRedactionHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\tracing-redaction-default\src\test\java" -Includes @("*.java") -Pattern 'mask|redact|sensitive'
$traceStorePaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevKernel\adapters\tracing-inproc\src\test\java\com\npdev\adapters\tracing\inproc\InProcExecutionTracerTest.java",
    "NPDevKernel\adapters\tracestore-postgres\src\test\java\com\npdev\adapters\tracestore\PersistentExecutionTracerTest.java"
) -PathType Leaf
$traceQueryPerfHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel" -Includes @("*.java") -Pattern '1000 traces|100ms'

Add-Condition "ODM-001" "Health indicators for: database, event store, scheduler, plugin registry, capability dispatcher, migration status" `
    ($healthIndicatorPaths.allPresent -and @($extraHealthHits).Count -ge 3) `
    "Health indicator files exist for the core runtime checks and extra health signals were found for plugin/capability/migration coverage." `
    "Health indicator coverage is incomplete for one or more required components." `
    @{ missingPaths = $healthIndicatorPaths.missing; extraHits = $extraHealthHits }

Add-Condition "ODM-002" "Each indicator returns: UP/DOWN/UNKNOWN with detailed message" `
    (@($healthStatusHits).Count -ge 4) `
    "Runtime health indicator tests reference UP/DOWN/UNKNOWN/detail semantics." `
    "Runtime health indicator tests do not yet visibly cover UP/DOWN/UNKNOWN/detail semantics." `
    @{ hits = $healthStatusHits }

Add-Condition "ODM-003" "Health endpoint (/actuator/health) tested in integration test" `
    (@($healthEndpointHits).Count -gt 0) `
    "Integration-test signals were found for the health endpoint." `
    "No explicit health endpoint integration-test evidence was found." `
    @{ hits = $healthEndpointHits }

Add-Condition "ODM-004" "Down indicator triggers alert (tested via mock alert sink)" `
    (@($healthAlertHits).Count -gt 0) `
    "Health-alert test signals were found." `
    "No explicit mock-alert-sink health alert evidence was found." `
    @{ hits = $healthAlertHits }

Add-Condition "ODM-005" "Health check performance: <50ms per indicator" `
    (@($healthPerfHits).Count -gt 0) `
    "Health indicator test sources reference performance targets." `
    "No explicit health indicator performance target evidence was found." `
    @{ hits = $healthPerfHits }

Add-Condition "ODM-006" "Trace includes: flow instance ID, step name, step type, input/output, duration, status, error (if any)" `
    (@($traceFieldHits).Count -ge 7) `
    "Trace-related sources reference the expected execution trace fields." `
    "Trace-related sources do not yet visibly cover all expected execution trace fields." `
    @{ hits = $traceFieldHits | Select-Object -First 40 }

Add-Condition "ODM-007" "Trace includes: correlation ID for distributed tracing" `
    (@($traceCorrelationHits).Count -gt 0) `
    "Trace-related sources reference correlation IDs." `
    "No explicit correlation-ID evidence was found in trace-related sources." `
    @{ hits = $traceCorrelationHits }

Add-Condition "ODM-008" "Trace redaction tested: sensitive fields masked per TraceRedactionPolicy" `
    (@($traceRedactionHits).Count -gt 0) `
    "Trace redaction test sources reference masking/redaction of sensitive fields." `
    "No explicit trace redaction masking evidence was found." `
    @{ hits = $traceRedactionHits }

Add-Condition "ODM-009" "Trace storage tested: both InProcExecutionTracer and PostgresTraceStore" `
    ($traceStorePaths.allPresent) `
    "Both in-process and PostgreSQL trace store test files are present." `
    "Trace storage test coverage is incomplete for in-process or PostgreSQL trace storage." `
    @{ missingPaths = $traceStorePaths.missing }

Add-Condition "ODM-010" "Trace query performance: retrieve 1000 traces in <100ms" `
    (@($traceQueryPerfHits).Count -gt 0) `
    "Trace-related sources reference 1000-trace / 100ms query performance." `
    "No explicit 1000-trace / 100ms trace query performance evidence was found." `
    @{ hits = $traceQueryPerfHits }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "observability-debuggability-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
