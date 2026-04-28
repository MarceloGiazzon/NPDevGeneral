[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$PolicyPath = "",
    [string]$RuntimeSurfaceAllowlistReportPath = "",
    [string]$SecurityMaturityReportPath = "",
    [string]$ObservabilityConfigPath = "",
    [string]$JwtFilterPath = "",
    [string]$AuthConfigPath = "",
    [string]$RuntimeModeConfigPath = "",
    [string]$ControllerRoot = "",
    [string]$ExternalBetaPropertiesPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "bucket2-report-common.ps1")

$WorkspaceRoot = Initialize-Bucket2Workspace -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "runtime-security-consistency"
$ReportPath = if ([string]::IsNullOrWhiteSpace($ReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-security-consistency-report.json" } else { Normalize-NPDevPath $ReportPath }
$PolicyPath = if ([string]::IsNullOrWhiteSpace($PolicyPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\security-sensitive-field-inventory.json" } else { Normalize-NPDevPath $PolicyPath }
$RuntimeSurfaceAllowlistReportPath = if ([string]::IsNullOrWhiteSpace($RuntimeSurfaceAllowlistReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-allowlist-report.json" } else { Normalize-NPDevPath $RuntimeSurfaceAllowlistReportPath }
$SecurityMaturityReportPath = if ([string]::IsNullOrWhiteSpace($SecurityMaturityReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\security-hardening-maturity-report.json" } else { Normalize-NPDevPath $SecurityMaturityReportPath }
$ObservabilityConfigPath = if ([string]::IsNullOrWhiteSpace($ObservabilityConfigPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevObservabilityConfig.java" } else { Normalize-NPDevPath $ObservabilityConfigPath }
$JwtFilterPath = if ([string]::IsNullOrWhiteSpace($JwtFilterPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\config\JwtBearerAuthFilter.java" } else { Normalize-NPDevPath $JwtFilterPath }
$AuthConfigPath = if ([string]::IsNullOrWhiteSpace($AuthConfigPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevAuthConfig.java" } else { Normalize-NPDevPath $AuthConfigPath }
$RuntimeModeConfigPath = if ([string]::IsNullOrWhiteSpace($RuntimeModeConfigPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevRuntimeModeConfig.java" } else { Normalize-NPDevPath $RuntimeModeConfigPath }
$ControllerRoot = if ([string]::IsNullOrWhiteSpace($ControllerRoot)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\api" } else { Normalize-NPDevPath $ControllerRoot }
$ExternalBetaPropertiesPath = if ([string]::IsNullOrWhiteSpace($ExternalBetaPropertiesPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources\application-external-beta.properties" } else { Normalize-NPDevPath $ExternalBetaPropertiesPath }

$policy = Read-Bucket2JsonFile $PolicyPath
$allowlistReport = Read-Bucket2JsonFile $RuntimeSurfaceAllowlistReportPath
$securityMaturityReport = Read-Bucket2JsonFile $SecurityMaturityReportPath
$externalBetaProperties = Get-Bucket2PropertiesMap $ExternalBetaPropertiesPath

$controllers = @(
    Get-ChildItem -LiteralPath $ControllerRoot -Recurse -File -Filter "*Controller.java" |
    Sort-Object FullName
)
$annotationPatterns = @(
    if ($null -eq $policy) { @() } else { @($policy.allowlistedControllerAnnotations | ForEach-Object { "@" + [regex]::Escape([string]$_) }) }
)
$annotatedControllers = [System.Collections.Generic.List[string]]::new()
$allowlistedExceptions = [System.Collections.Generic.List[string]]::new()
foreach ($controller in $controllers) {
    $content = Get-Content -LiteralPath $controller.FullName -Raw
    $relativePath = Get-Bucket2RelativePath $WorkspaceRoot $controller.FullName
    $hasAnnotation = $false
    foreach ($pattern in $annotationPatterns) {
        if ($content -match $pattern) {
            $hasAnnotation = $true
            break
        }
    }

    if ($hasAnnotation) {
        [void]$annotatedControllers.Add($relativePath)
    }
    else {
        [void]$allowlistedExceptions.Add($relativePath)
    }
}

$redactionTestPaths = @(
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\tracing-redaction-default\src\test\java\com\npdev\adapters\tracing\redaction\DefaultReadRedactionPoliciesTest.java"
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\tracing-redaction-default\src\test\java\com\npdev\adapters\tracing\redaction\DefaultTraceRedactionPolicyTest.java"
)
$redactionFieldCoverage = [System.Collections.Generic.List[object]]::new()
foreach ($field in @(if ($null -eq $policy) { @() } else { @($policy.fields) })) {
    $name = [string]$field.name
    $coveredBy = @()
    foreach ($testPath in $redactionTestPaths) {
        if ((Get-Content -LiteralPath $testPath -Raw) -match [regex]::Escape($name)) {
            $coveredBy += Get-Bucket2RelativePath $WorkspaceRoot $testPath
        }
    }
    [void]$redactionFieldCoverage.Add([pscustomobject]@{
            name = $name
            covered = ($coveredBy.Count -gt 0)
            coveredBy = $coveredBy
        })
}
$missingRedactionFields = @($redactionFieldCoverage | Where-Object { -not $_.covered } | Select-Object -ExpandProperty name)

$observabilityConfigMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $ObservabilityConfigPath -Patterns @(
    "DefaultEventRedactionPolicy",
    "DefaultExecutionRedactionPolicy",
    "DefaultTraceRedactionPolicy"
))

$jwtFilterMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $JwtFilterPath -Patterns @(
    "Authorization",
    "missing_bearer_token",
    "validateAndExtractClaims"
))
$authConfigMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $AuthConfigPath -Patterns @(
    'ConditionalOnProperty\(name = "npdev\.auth\.mode", havingValue = "jwt"\)',
    'bean\.addUrlPatterns\("/api/\*", "/api/v1/\*"\)',
    'bean\.setEnabled\(runtimeSettings\.authEnabled\(\)\)'
))
$runtimeSettingsMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $RuntimeModeConfigPath -Patterns @(
    '@Value\("\$\{npdev\.auth\.enabled:true\}"\) boolean authEnabled'
))
$authMode = [string]$externalBetaProperties["npdev.auth.mode"]
$authEnabled = [string]$externalBetaProperties["npdev.auth.enabled"]
$globalAuthCoveragePassed = (
    $controllers.Count -gt 0 -and
    $authMode -eq "jwt" -and
    $authEnabled -eq "true" -and
    $authConfigMissingPatterns.Count -eq 0 -and
    $runtimeSettingsMissingPatterns.Count -eq 0
)

$checks = @(
    (New-NPDevCheckResult -Name "sensitive-field-inventory" -Status $(if ($null -ne $policy -and @($policy.fields).Count -gt 0) { "passed" } else { "failed" }) -Summary $(if ($null -ne $policy -and @($policy.fields).Count -gt 0) { "Sensitive field inventory is defined." } else { "Sensitive field inventory is missing or empty." }) -Data ([pscustomobject]@{ policyPath = Get-Bucket2RelativePath $WorkspaceRoot $PolicyPath; fieldCount = if ($null -eq $policy) { 0 } else { @($policy.fields).Count } }))
    (New-NPDevCheckResult -Name "security-maturity-evidence-current" -Status $(if ($null -ne $securityMaturityReport -and [string]$securityMaturityReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Summary $(if ($null -ne $securityMaturityReport -and [string]$securityMaturityReport.overallStatus -eq "passed") { "Security maturity evidence is current and green." } else { "Security maturity evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $SecurityMaturityReportPath; overallStatus = if ($null -eq $securityMaturityReport) { $null } else { [string]$securityMaturityReport.overallStatus } }))
    (New-NPDevCheckResult -Name "experimental-surfaces-disabled" -Status $(if ($null -ne $allowlistReport -and [string]$allowlistReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Summary $(if ($null -ne $allowlistReport -and [string]$allowlistReport.overallStatus -eq "passed") { "Supported-surface packaging evidence keeps experimental controllers disabled in the supported profile." } else { "Supported-surface packaging evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $RuntimeSurfaceAllowlistReportPath; overallStatus = if ($null -eq $allowlistReport) { $null } else { [string]$allowlistReport.overallStatus } }))
    (New-NPDevCheckResult -Name "controller-security-model" -Status $(if ($globalAuthCoveragePassed) { "passed" } else { "failed" }) -Summary $(if ($globalAuthCoveragePassed) { "Controllers are protected by the external-beta JWT filter path or explicit annotations." } else { "Controller security coverage could not be established from the governed JWT filter path." }) -Data ([pscustomobject]@{ controllerCount = $controllers.Count; annotatedControllers = @($annotatedControllers); globalFilterControllers = @($controllers | ForEach-Object { Get-Bucket2RelativePath $WorkspaceRoot $_.FullName }); explicitAllowlistExceptions = @($allowlistedExceptions); authMode = $authMode; authEnabled = $authEnabled; authConfigMissingPatterns = @($authConfigMissingPatterns); runtimeSettingsMissingPatterns = @($runtimeSettingsMissingPatterns) }))
    (New-NPDevCheckResult -Name "redaction-policies-wired" -Status $(if ($observabilityConfigMissingPatterns.Count -eq 0 -and $jwtFilterMissingPatterns.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($observabilityConfigMissingPatterns.Count -eq 0 -and $jwtFilterMissingPatterns.Count -eq 0) { "Runtime security wiring uses the default redaction policies and JWT filter." } else { "Runtime security wiring is missing required redaction or JWT filter signals." }) -Data ([pscustomobject]@{ observabilityConfigPath = Get-Bucket2RelativePath $WorkspaceRoot $ObservabilityConfigPath; jwtFilterPath = Get-Bucket2RelativePath $WorkspaceRoot $JwtFilterPath; missingObservabilityPatterns = @($observabilityConfigMissingPatterns); missingJwtFilterPatterns = @($jwtFilterMissingPatterns) }))
    (New-NPDevCheckResult -Name "inventory-redaction-coverage" -Status $(if ($missingRedactionFields.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($missingRedactionFields.Count -eq 0) { "The sensitive field inventory is covered by trace/event/execution redaction tests." } else { "One or more inventory fields are not covered by redaction tests." }) -Data ([pscustomobject]@{ fields = @($redactionFieldCoverage); missingFields = @($missingRedactionFields) }))
)

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = Get-Bucket2OverallStatus $checks
    policyPath = Get-Bucket2RelativePath $WorkspaceRoot $PolicyPath
    controllerSecurity = [pscustomobject]@{
        controllerCount = $controllers.Count
        annotatedControllers = @($annotatedControllers)
        explicitAllowlistExceptions = @($allowlistedExceptions)
        authMode = $authMode
        authEnabled = $authEnabled
        authConfigPath = Get-Bucket2RelativePath $WorkspaceRoot $AuthConfigPath
        externalBetaPropertiesPath = Get-Bucket2RelativePath $WorkspaceRoot $ExternalBetaPropertiesPath
        runtimeModeConfigPath = Get-Bucket2RelativePath $WorkspaceRoot $RuntimeModeConfigPath
    }
    experimentalSurfaceProof = [pscustomobject]@{
        reportPath = Get-Bucket2RelativePath $WorkspaceRoot $RuntimeSurfaceAllowlistReportPath
        status = if ($null -eq $allowlistReport) { "failed" } else { [string]$allowlistReport.overallStatus }
    }
    redactionCoverage = [pscustomobject]@{
        fields = @($redactionFieldCoverage)
        missingFields = @($missingRedactionFields)
    }
    checks = $checks
    summary = Get-Bucket2Summary $checks
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Runtime security consistency report generated."
    return
}

Write-NPDevWarn "Runtime security consistency report failed."
throw "Runtime security consistency report failed."
