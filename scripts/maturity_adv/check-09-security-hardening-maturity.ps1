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
$RunId = Resolve-NPDevRunId $RunId "security-hardening-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\security-hardening-maturity-report.json"

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

$jwtFilterPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\config\JwtBearerAuthFilter.java"
$jwtUnitTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'JwtBearerAuthFilter|valid token|expired token|malformed token|wrong signature|missing token|missing claims|extra claims'
$jwtIntegrationHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'JwtAuth|authenticated request|unauthenticated request'
$jwtRateLimitHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'rate limit|burst|invalid token'
$jwtSensitiveLogHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\main\java" -Includes @("JwtBearerAuthFilter.java") -Pattern 'Authorization|token'
$requestFilterPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\runtime-validation\src\main\java\com\npdev\adapters\runtime\validation\RuntimeRequestSizeFilter.java"
$requestFilterTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\runtime-validation\src\test\java\com\npdev\adapters\runtime\validation\RuntimeRequestSizeFilterTest.java"
$requestFilterLimitHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\runtime-validation\src\test\java" -Includes @("RuntimeRequestSizeFilterTest.java") -Pattern 'limit|over limit|at limit'
$requestFilter413Hits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\runtime-validation\src\test\java" -Includes @("RuntimeRequestSizeFilterTest.java") -Pattern '413|structured error'
$requestFilterConfigHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\runtime-validation\src\main\java" -Includes @("RuntimeRequestSizeFilter.java") -Pattern 'application.properties|@Value|Environment'
$requestFilterProtocolHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\runtime-validation\src\test\java" -Includes @("RuntimeRequestSizeFilterTest.java") -Pattern 'chunked|multipart|WebSocket'
$requestFilterPerfHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel\adapters\runtime-validation\src\test\java" -Includes @("RuntimeRequestSizeFilterTest.java") -Pattern '<1ms|1ms|performance'
$sandboxEnginePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\SandboxedPluginExecutionEngine.java"
$sandboxTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\SandboxedPluginExecutionEngineTest.java"
$sandboxHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("SandboxedPluginExecutionEngineTest.java") -Pattern 'filesystem|network|memory|cpu|timeout|infinite|System.exit|reflection'

Add-Condition "SHM-001" "Unit test covers: valid token, expired token, malformed token, missing token, wrong signature" `
    (@($jwtUnitTestHits | Where-Object { $_.line -match 'valid token|expired token|malformed token|missing token|wrong signature' }).Count -ge 5) `
    "JWT auth test sources contain signals for valid/expired/malformed/missing/wrong-signature cases." `
    "JWT auth test sources do not yet visibly cover all required valid/expired/malformed/missing/wrong-signature cases." `
    @{ hits = $jwtUnitTestHits }

Add-Condition "SHM-002" "Unit test covers: token with missing claims, token with extra claims" `
    (@($jwtUnitTestHits | Where-Object { $_.line -match 'missing claims|extra claims' }).Count -ge 2) `
    "JWT auth test sources contain missing-claims and extra-claims coverage signals." `
    "JWT auth test sources do not yet visibly cover missing-claims and extra-claims cases." `
    @{ hits = $jwtUnitTestHits }

Add-Condition "SHM-003" "Integration test covers: authenticated request succeeds, unauthenticated request rejected" `
    (@($jwtIntegrationHits).Count -gt 0) `
    "JWT auth integration-test signals were found." `
    "No explicit JWT auth integration-test signals were found." `
    @{ hits = $jwtIntegrationHits }

Add-Condition "SHM-004" "Rate limiting tested: burst of invalid tokens handled gracefully" `
    (@($jwtRateLimitHits).Count -gt 0) `
    "JWT security test sources reference burst/rate-limit handling." `
    "No explicit burst/rate-limit handling evidence was found for JWT auth." `
    @{ hits = $jwtRateLimitHits }

Add-Condition "SHM-005" "Filter does not log sensitive token data" `
    (@($jwtSensitiveLogHits | Where-Object { $_.line -match 'log|logger' }).Count -eq 0) `
    "JwtBearerAuthFilter does not visibly log Authorization/token data." `
    "JwtBearerAuthFilter appears to log Authorization/token data or needs closer review." `
    @{ hits = $jwtSensitiveLogHits }

Add-Condition "SHM-006" "RuntimeRequestSizeFilterTest covers: request at limit passes, request over limit rejected" `
    (@($requestFilterLimitHits).Count -gt 0) `
    "RuntimeRequestSizeFilterTest contains limit-boundary coverage signals." `
    "RuntimeRequestSizeFilterTest does not yet visibly cover limit-boundary behavior." `
    @{ hits = $requestFilterLimitHits }

Add-Condition "SHM-007" "Rejected request returns HTTP 413 with structured error body" `
    (@($requestFilter413Hits).Count -gt 0) `
    "RuntimeRequestSizeFilterTest contains HTTP 413 / structured error signals." `
    "RuntimeRequestSizeFilterTest does not yet visibly prove HTTP 413 with structured error body." `
    @{ hits = $requestFilter413Hits }

Add-Condition "SHM-008" "Limit is configurable via application.properties" `
    (@($requestFilterConfigHits).Count -gt 0) `
    "RuntimeRequestSizeFilter source contains configuration-injection signals." `
    "RuntimeRequestSizeFilter source does not visibly show configuration from application properties/environment." `
    @{ hits = $requestFilterConfigHits }

Add-Condition "SHM-009" "Test covers: chunked encoding, multipart upload, WebSocket upgrade" `
    (@($requestFilterProtocolHits).Count -ge 3) `
    "RuntimeRequestSizeFilterTest contains chunked/multipart/WebSocket signals." `
    "RuntimeRequestSizeFilterTest does not yet visibly cover chunked/multipart/WebSocket behavior." `
    @{ hits = $requestFilterProtocolHits }

Add-Condition "SHM-010" "Performance test: filter overhead <1ms per request" `
    (@($requestFilterPerfHits).Count -gt 0) `
    "RuntimeRequestSizeFilter test sources reference performance overhead targets." `
    "No explicit RuntimeRequestSizeFilter performance-overhead evidence was found." `
    @{ hits = $requestFilterPerfHits }

Add-Condition "SHM-011" "Plugin cannot access filesystem outside its sandbox" `
    (@($sandboxHits | Where-Object { $_.line -match 'filesystem' }).Count -gt 0) `
    "Sandboxed plugin tests reference filesystem containment." `
    "No explicit filesystem-containment evidence was found for sandboxed plugins." `
    @{ hits = $sandboxHits }

Add-Condition "SHM-012" "Plugin cannot make network connections (unless explicitly allowed)" `
    (@($sandboxHits | Where-Object { $_.line -match 'network' }).Count -gt 0) `
    "Sandboxed plugin tests reference network containment." `
    "No explicit network-containment evidence was found for sandboxed plugins." `
    @{ hits = $sandboxHits }

Add-Condition "SHM-013" "Plugin cannot consume unbounded memory or CPU" `
    (@($sandboxHits | Where-Object { $_.line -match 'memory|cpu' }).Count -gt 0) `
    "Sandboxed plugin tests reference resource-limit handling." `
    "No explicit resource-limit evidence was found for sandboxed plugins." `
    @{ hits = $sandboxHits }

Add-Condition "SHM-014" "Plugin timeout enforced (max execution time)" `
    (@($sandboxHits | Where-Object { $_.line -match 'timeout' }).Count -gt 0) `
    "Sandboxed plugin tests reference timeout enforcement." `
    "No explicit timeout-enforcement evidence was found for sandboxed plugins." `
    @{ hits = $sandboxHits }

Add-Condition "SHM-015" "Test proves: malicious plugin (infinite loop, System.exit, reflection attack) is contained" `
    (@($sandboxHits | Where-Object { $_.line -match 'infinite|System.exit|reflection' }).Count -ge 2) `
    "Sandboxed plugin tests reference malicious-plugin containment cases." `
    "Sandboxed plugin tests do not yet visibly cover enough malicious-plugin containment cases." `
    @{ hits = $sandboxHits }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "security-hardening-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
