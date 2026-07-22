param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/shift-left-ai-safety-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

. (Join-Path (Resolve-Path ".").Path "scripts/ai/Test-AiShiftLeftSafety.ps1")

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathValue)
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-CommandCapture {
    param([string]$Name, [scriptblock]$ScriptBlock, [int]$ExpectedExitCode = 0)
    $started = Get-Date
    $output = @(& $ScriptBlock 2>&1 | ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) { $exitCode = 0 }
    $finished = Get-Date
    return [pscustomobject]@{
        name = $Name
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        durationSeconds = [math]::Round(($finished - $started).TotalSeconds, 3)
        outputTail = @($output | Select-Object -Last 120)
    }
}

function Invoke-SchemaCase {
    param(
        [string]$Name,
        [string]$SchemaPath,
        [string]$InstancePath,
        [bool]$ShouldPass,
        [string]$ValidationRoot
    )
    $resultPath = Join-Path $ValidationRoot ($Name + ".json")
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $InstancePath -ReportPath $resultPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $result = if (Test-Path -LiteralPath $resultPath -PathType Leaf) { Read-JsonFile $resultPath } else { $null }
    $actualPass = ($exitCode -eq 0 -and $null -ne $result -and [string]$result.status -eq "passed")
    return [pscustomobject]@{
        name = $Name
        schemaPath = $SchemaPath
        instancePath = Convert-ToRepoPath $script:ShiftLeftWorkspaceRoot $InstancePath
        resultPath = Convert-ToRepoPath $script:ShiftLeftWorkspaceRoot $resultPath
        shouldPass = $ShouldPass
        passed = ($actualPass -eq $ShouldPass)
        actualStatus = if ($actualPass) { "passed" } else { "failed" }
        failureCount = if ($null -ne $result) { [int]$result.failureCount } else { 0 }
        failures = if ($null -ne $result) { @($result.failures) } else { @("schema validation did not write a result") }
    }
}

function New-MinimalExpandedAiModel {
    param([string]$ScenarioId = "shift-left-valid")
    return [ordered]@{
        schemaVersion = "ai-model.v1"
        app = [ordered]@{ name = "Shift Left Valid"; kind = "expanded-beta-application" }
        entities = @([ordered]@{ name = "Ticket"; tenantScoped = $true; fields = @([ordered]@{ name = "title"; type = "string"; required = $true }) })
        flows = @([ordered]@{ name = "CreateTicket"; entity = "Ticket"; operation = "create"; requiredRole = "agent"; tenantScoped = $true })
        panels = @([ordered]@{ panelId = "ticket-panel"; type = "entity-list"; route = "/tickets"; dataSource = [ordered]@{ kind = "entity"; name = "Ticket" }; requiredRole = "agent"; tenantScoped = $true })
        procedures = @([ordered]@{ procedureId = "ticket-summary"; type = "read-only-query"; inputs = @(); outputs = @(); sideEffectType = "none"; requiredRole = "agent"; tenantScoped = $true; allowedEntities = @("Ticket"); maxAffectedRows = 0 })
        workflows = @([ordered]@{ workflowId = "ticket-flow"; entity = "Ticket"; states = @("open", "closed"); startState = "open"; terminalStates = @("closed"); transitions = @([ordered]@{ name = "close"; from = "open"; to = "closed"; requiredRole = "agent" }); tenantScoped = $true })
        tenancy = [ordered]@{ mode = "shared-app-tenant-scoped-data"; tenantIdField = "tenantId"; testTenants = @("tenant-a", "tenant-b") }
        auth = [ordered]@{ mode = "generated-test-token"; principalFields = @("userId", "tenantId", "roles"); testUsers = @([ordered]@{ userId = "agent-a"; tenantId = "tenant-a"; roles = @("agent") }, [ordered]@{ userId = "agent-b"; tenantId = "tenant-b"; roles = @("agent") }) }
        roles = @([ordered]@{ roleId = "agent"; permissions = @("ticket:update") })
    }
}

$root = (Resolve-Path $WorkspaceRoot).Path
$script:ShiftLeftWorkspaceRoot = $root
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "shift-left-ai-safety-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workRoot = Join-Path $root "build/cp11-shift-left-ai-safety"
if (Test-Path -LiteralPath $workRoot) { Remove-Item -LiteralPath $workRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
$fixtureRoot = Join-Path $workRoot "fixtures"
$validationRoot = Join-Path $workRoot "schema-validation"
New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null
New-Item -ItemType Directory -Force -Path $validationRoot | Out-Null

$validModelPath = Join-Path $fixtureRoot "valid-ai-model.json"
Write-JsonFile $validModelPath (New-MinimalExpandedAiModel)
$bulkModel = New-MinimalExpandedAiModel
$bulkModel.procedures = @([ordered]@{ procedureId = "bulk-close"; type = "bulk-command"; inputs = @(); outputs = @(); sideEffectType = "bulk"; requiredRole = "agent"; tenantScoped = $true; allowedEntities = @("Ticket"); maxAffectedRows = 0 })
$bulkModelPath = Join-Path $fixtureRoot "unbounded-bulk-ai-model.json"
Write-JsonFile $bulkModelPath $bulkModel
$pathModel = New-MinimalExpandedAiModel
$pathModel.procedures[0]["implementation"] = [ordered]@{ mode = "trustedSource"; language = "java"; entrypoint = "../unsafe/Escape.java"; className = "Escape" }
$pathModelPath = Join-Path $fixtureRoot "path-traversal-ai-model.json"
Write-JsonFile $pathModelPath $pathModel
$roleModel = New-MinimalExpandedAiModel
$roleModel.roles = @([ordered]@{ roleId = "agent"; permissions = @("tenant:bypass") })
$roleModelPath = Join-Path $fixtureRoot "tenant-bypass-role-ai-model.json"
Write-JsonFile $roleModelPath $roleModel
$verificationPath = Join-Path $fixtureRoot "unsafe-url-verification.json"
Write-JsonFile $verificationPath ([ordered]@{ schemaVersion = "ai-verification-report.v1"; scenarioId = "unsafe-url-verification"; baseUrlVariable = "NPDEV_GENERATED_APP_BASE_URL"; checks = @([ordered]@{ id = "health"; type = "http"; method = "GET"; path = "/actuator/health"; expectedStatus = 200 }, [ordered]@{ id = "external"; type = "http"; method = "GET"; path = "https://example.com/steal"; expectedStatus = 200 }) })
$validCommandPath = Join-Path $fixtureRoot "valid-command-request.json"
Write-JsonFile $validCommandPath ([ordered]@{ schemaVersion = "npdev-ai-command-request.v1"; type = "schema-validation"; schemaPath = "schemas/ai/ai-model.schema.json"; instancePath = "golden-ai-scenarios/base-ai-loop/ai-model.json"; timeoutSeconds = 30 })
$externalCommandPath = Join-Path $fixtureRoot "external-url-command-request.json"
Write-JsonFile $externalCommandPath ([ordered]@{ schemaVersion = "npdev-ai-command-request.v1"; type = "raw-command"; executable = "node"; arguments = @("https://example.com"); workingDirectory = "."; timeoutSeconds = 5 })
$chainedCommandPath = Join-Path $fixtureRoot "chained-command-request.json"
Write-JsonFile $chainedCommandPath ([ordered]@{ schemaVersion = "npdev-ai-command-request.v1"; type = "raw-command"; executable = "pwsh"; arguments = @("-NoProfile", "-File", "scripts/quality/run-ai-schema-validation.ps1", "&&", "rm", "-rf", "."); workingDirectory = "."; timeoutSeconds = 5 })
$pathCommandPath = Join-Path $fixtureRoot "path-traversal-command-request.json"
Write-JsonFile $pathCommandPath ([ordered]@{ schemaVersion = "npdev-ai-command-request.v1"; type = "raw-command"; executable = "pwsh"; arguments = @("-NoProfile", "-File", "scripts/quality/run-ai-schema-validation.ps1"); workingDirectory = "../outside"; timeoutSeconds = 5 })

$schemaCases = @(
    Invoke-SchemaCase "valid-ai-model" "schemas/ai/ai-model.schema.json" $validModelPath $true $validationRoot
    Invoke-SchemaCase "unbounded-bulk-ai-model-rejected" "schemas/ai/ai-model.schema.json" $bulkModelPath $false $validationRoot
    Invoke-SchemaCase "path-traversal-ai-model-rejected" "schemas/ai/ai-model.schema.json" $pathModelPath $false $validationRoot
    Invoke-SchemaCase "tenant-bypass-role-rejected" "schemas/ai/ai-model.schema.json" $roleModelPath $false $validationRoot
    Invoke-SchemaCase "unsafe-url-verification-rejected" "schemas/ai/ai-verification-report.schema.json" $verificationPath $false $validationRoot
    Invoke-SchemaCase "valid-command-request" "schemas/ai/ai-command-request.schema.json" $validCommandPath $true $validationRoot
    Invoke-SchemaCase "external-url-command-request-rejected" "schemas/ai/ai-command-request.schema.json" $externalCommandPath $false $validationRoot
    Invoke-SchemaCase "chained-command-request-rejected" "schemas/ai/ai-command-request.schema.json" $chainedCommandPath $false $validationRoot
    Invoke-SchemaCase "path-traversal-command-request-rejected" "schemas/ai/ai-command-request.schema.json" $pathCommandPath $false $validationRoot
)

$lintCases = @()
foreach ($case in @(
        [pscustomobject]@{ name = "valid-ai-model"; kind = "model"; path = $validModelPath; expectedCodes = @() },
        [pscustomobject]@{ name = "unbounded-bulk"; kind = "model"; path = $bulkModelPath; expectedCodes = @("PROCEDURE_BULK_LIMIT_MISSING") },
        [pscustomobject]@{ name = "path-traversal-model"; kind = "model"; path = $pathModelPath; expectedCodes = @("AI_SAFETY_PATH_TRAVERSAL") },
        [pscustomobject]@{ name = "external-url-command"; kind = "command"; path = $externalCommandPath; expectedCodes = @("AI_SAFETY_UNSAFE_EXTERNAL_URL") },
        [pscustomobject]@{ name = "chained-command"; kind = "command"; path = $chainedCommandPath; expectedCodes = @("AI_SAFETY_SUSPICIOUS_COMMAND") },
        [pscustomobject]@{ name = "path-traversal-command"; kind = "command"; path = $pathCommandPath; expectedCodes = @("AI_SAFETY_PATH_TRAVERSAL") }
    )) {
    $json = Read-JsonFile $case.path
    $findings = if ($case.kind -eq "model") { @(Invoke-AiShiftLeftSafetyLint -AiModel $json) } else { @(Invoke-AiShiftLeftSafetyLint -CommandRequest $json) }
    $codes = @($findings | ForEach-Object { [string]$_.code } | Sort-Object -Unique)
    $missing = @($case.expectedCodes | Where-Object { $codes -notcontains $_ })
    $unexpectedForValid = ($case.expectedCodes.Count -eq 0 -and $codes.Count -gt 0)
    $lintCases += [pscustomobject]@{
        name = [string]$case.name
        kind = [string]$case.kind
        path = Convert-ToRepoPath $root $case.path
        expectedCodes = @($case.expectedCodes)
        actualCodes = $codes
        findings = @($findings)
        passed = ($missing.Count -eq 0 -and -not $unexpectedForValid)
    }
}

$aiSchemaValidation = Invoke-CommandCapture "ai-schema-validation" { pwsh -NoProfile -File scripts/quality/run-ai-schema-validation.ps1 }
$normalizerValidation = Invoke-CommandCapture "ai-contract-normalizer-tests" { pwsh -NoProfile -File scripts/quality/run-ai-contract-normalizer-tests.ps1 }
$commandPolicyValidation = Invoke-CommandCapture "structured-command-surface-alignment" { pwsh -NoProfile -File scripts/quality/run-structured-command-surface-alignment.ps1 }

$schemaLevelRestrictionsPassed = @($schemaCases | Where-Object { -not $_.passed }).Count -eq 0
$preAstSafetyLintPassed = @($lintCases | Where-Object { -not $_.passed }).Count -eq 0
$negativeScenariosFailEarlyPassed = $aiSchemaValidation.passed -and $normalizerValidation.passed
$runtimeFirewallDefenseInDepthPreserved = $commandPolicyValidation.passed
$failed = @(
    -not $schemaLevelRestrictionsPassed,
    -not $preAstSafetyLintPassed,
    -not $negativeScenariosFailEarlyPassed,
    -not $runtimeFirewallDefenseInDepthPreserved
) | Where-Object { $_ }
$overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    schemaVersion = "npdev-shift-left-ai-safety-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-shift-left-ai-safety-check.ps1"
    workspaceRoot = $root
    overallStatus = $overallStatus
    schemaLevelRestrictionsPassed = $schemaLevelRestrictionsPassed
    preAstSafetyLintPassed = $preAstSafetyLintPassed
    destructiveShellCommandsRejectedEarly = (@($lintCases | Where-Object { $_.actualCodes -contains "AI_SAFETY_SUSPICIOUS_COMMAND" -and $_.passed }).Count -ge 1)
    suspiciousCommandChainingRejectedEarly = (@($schemaCases | Where-Object { $_.name -eq "chained-command-request-rejected" -and $_.passed }).Count -eq 1)
    unsafeExternalUrlsRejectedEarly = (@($schemaCases | Where-Object { $_.name -eq "external-url-command-request-rejected" -and $_.passed }).Count -eq 1) -and (@($schemaCases | Where-Object { $_.name -eq "unsafe-url-verification-rejected" -and $_.passed }).Count -eq 1)
    pathTraversalRejectedEarly = (@($schemaCases | Where-Object { $_.name -eq "path-traversal-ai-model-rejected" -and $_.passed }).Count -eq 1) -and (@($schemaCases | Where-Object { $_.name -eq "path-traversal-command-request-rejected" -and $_.passed }).Count -eq 1)
    unboundedBulkOperationsRejectedEarly = (@($schemaCases | Where-Object { $_.name -eq "unbounded-bulk-ai-model-rejected" -and $_.passed }).Count -eq 1) -and (@($lintCases | Where-Object { $_.actualCodes -contains "PROCEDURE_BULK_LIMIT_MISSING" -and $_.passed }).Count -ge 1)
    negativeScenariosFailAtEarliestStage = $negativeScenariosFailEarlyPassed
    runtimeFirewallDefenseInDepthPreserved = $runtimeFirewallDefenseInDepthPreserved
    schemaCases = @($schemaCases)
    lintCases = @($lintCases)
    validationCommands = @($aiSchemaValidation, $normalizerValidation, $commandPolicyValidation)
    findings = @(
        [pscustomobject]@{
            id = "CP11-RUNTIME-FIREWALL-REMAINS-DEFENSE-IN-DEPTH"
            classification = "known-risk-accepted"
            summary = "CP11 rejects known bad AI-generated patterns earlier, but keeps runtime command firewall checks as defense-in-depth rather than removing them."
        }
    )
    doesNotSolve = @(
        "Does not prove arbitrary LLM prompt safety.",
        "Does not remove runtime firewall checks.",
        "Does not proceed to Checkpoint 12."
    )
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -ne "passed") {
    Write-Error ("Shift-left AI safety check failed. Report: " + $ReportPath)
}

Write-Host ("Shift-left AI safety report written: " + $ReportPath)
