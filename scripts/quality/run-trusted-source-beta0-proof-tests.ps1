param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "trusted-source-beta0-proof-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workspaceRoot = (Resolve-Path ".").Path
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/trusted-source-beta0-proof-tests"
if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function New-ScenarioRoot {
    param([string]$Name)
    $root = Join-Path $testRoot $Name
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    return $root
}

function Write-TrustedProcedureScenario {
    param(
        [string]$Root,
        [string]$ScenarioId,
        [string]$Source,
        [string]$ExpectedOutcome = "fail",
        [switch]$HashMismatch,
        [switch]$NoManifest,
        [string]$RelativePath = "procedure/TestProcedure.java"
    )
    $scenario = Join-Path $Root $ScenarioId
    New-Item -ItemType Directory -Force -Path $scenario | Out-Null
    $sourcePath = Join-Path $scenario $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sourcePath) | Out-Null
    Set-Content -LiteralPath $sourcePath -Value $Source -Encoding UTF8
    if ($NoManifest) { return }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash.ToLowerInvariant()
    if ($HashMismatch) { $hash = ("0" * 64) }
    $manifest = [ordered]@{
        schemaVersion = "npdev-trusted-source-manifest.v1"
        scenarioId = $ScenarioId
        policyVersion = "test"
        expectedOutcome = $ExpectedOutcome
        entries = @(
            [ordered]@{
                entryId = "procedure-test"
                kind = "procedure"
                relativePath = $RelativePath
                language = "java"
                sha256 = $hash
                runtimeBinding = "procedure:test"
                className = "TestProcedure"
                method = "execute"
                requiredRole = "admin"
                tenantScoped = $true
            }
        )
    }
    $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $scenario "trusted-source-manifest.json") -Encoding UTF8
}

function Write-TrustedPanelScenario {
    param(
        [string]$Root,
        [string]$ScenarioId,
        [string]$Source
    )
    $scenario = Join-Path $Root $ScenarioId
    $sourcePath = Join-Path $scenario "panel/test-panel.html"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sourcePath) | Out-Null
    Set-Content -LiteralPath $sourcePath -Value $Source -Encoding UTF8
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        schemaVersion = "npdev-trusted-source-manifest.v1"
        scenarioId = $ScenarioId
        policyVersion = "test"
        expectedOutcome = "fail"
        entries = @(
            [ordered]@{
                entryId = "panel-test"
                kind = "panel"
                relativePath = "panel/test-panel.html"
                language = "html+javascript"
                sha256 = $hash
                runtimeBinding = "panel:/test"
                requiredRole = "admin"
                tenantScoped = $true
            }
        )
    }
    $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $scenario "trusted-source-manifest.json") -Encoding UTF8
}

function Invoke-Proof {
    param([string]$ScenarioRoot, [string]$Name, [switch]$ExpectNonZero, [switch]$StaticOnlyPass)
    $reportPath = Join-Path $testRoot ($Name + ".json")
    $relativeRoot = [System.IO.Path]::GetRelativePath($workspaceRoot, $ScenarioRoot) -replace "\\", "/"
    $args = @("-NoProfile", "-File", "scripts/quality/run-trusted-source-beta0-proof.ps1", "-RunId", $RunId, "-ScenarioRoot", $relativeRoot, "-ReportPath", $reportPath)
    if ($StaticOnlyPass) { $args += "-StaticOnlyPass" }
    $ErrorActionPreference = "Continue"
    & pwsh @args 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw "Proof report missing for $Name." }
    if ($ExpectNonZero -and $exitCode -eq 0) { throw "Expected nonzero proof exit for $Name." }
    if (-not $ExpectNonZero -and $exitCode -ne 0) { throw "Expected zero proof exit for $Name, got $exitCode." }
    return [pscustomobject]@{ exitCode = $exitCode; report = (Read-JsonFile $reportPath); reportPath = $reportPath }
}

function ConvertTo-BetaLocalSlug {
    param([string]$Value)
    $slug = ([string]$Value).ToLowerInvariant() -replace '[^a-z0-9]+', '-'
    $slug = $slug -replace '^-+|-+$', ''
    if ([string]::IsNullOrWhiteSpace($slug)) { return "user" }
    return $slug
}

function Get-GeneratedBetaLocalApiKey {
    param([string]$ScenarioId, [string]$UserId)
    return "ai-" + (ConvertTo-BetaLocalSlug $ScenarioId) + "-" + (ConvertTo-BetaLocalSlug $UserId)
}

$safeSource = @"
import java.util.List;
import java.util.Map;

public final class TestProcedure {
    public Map<String, Object> execute(NPDevProcedureContext ctx) {
        return Map.of("ok", true);
    }
}
"@

$negativeJavaCases = [ordered]@{
    "java-io" = "import java.io.File; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return File.separator; } }"
    "java-nio-file" = "import java.nio.file.Files; import java.nio.file.Path; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) throws Exception { return Files.exists(Path.of(`"x`")); } }"
    "java-net" = "import java.net.URI; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return URI.create(`"https://example.com`"); } }"
    "runtime" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return Runtime.getRuntime(); } }"
    "process-builder" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return new ProcessBuilder(`"cmd`"); } }"
    "system-getenv" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return System.getenv(); } }"
    "system-get-property" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return System.getProperty(`"user.home`"); } }"
    "system-properties" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return System.getProperties(); } }"
    "system-set-property" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { System.setProperty(`"x`", `"y`"); return null; } }"
    "system-exit" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { System.exit(1); return null; } }"
    "reflection" = "import java.lang.reflect.Method; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return Method.class; } }"
    "class-type" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { Class<?> c = String.class; return c; } }"
    "fully-qualified-file" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return java.io.File.separator; } }"
    "fully-qualified-runtime" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return java.lang.Runtime.getRuntime(); } }"
    "class-for-name" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) throws Exception { return Class.forName(`"java.lang.String`"); } }"
    "class-loader" = "public final class TestProcedure extends ClassLoader { public Object execute(NPDevProcedureContext ctx) { return this; } }"
    "service-loader" = "import java.util.ServiceLoader; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return ServiceLoader.class; } }"
    "thread" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return new Thread(); } }"
    "thread-local" = "public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return new ThreadLocal<String>(); } }"
    "timer" = "import java.util.Timer; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return new Timer(); } }"
    "concurrent" = "import java.util.concurrent.Executors; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return Executors.newSingleThreadExecutor(); } }"
    "javax-script" = "import javax.script.ScriptEngineManager; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return new ScriptEngineManager(); } }"
    "sun" = "import sun.misc.Unsafe; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return Unsafe.class; } }"
    "jdk" = "import jdk.jshell.JShell; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return JShell.class; } }"
    "static-initializer" = "public final class TestProcedure { static { int x = 1; } public Object execute(NPDevProcedureContext ctx) { return null; } }"
    "native-method" = "public final class TestProcedure { public native Object execute(NPDevProcedureContext ctx); }"
    "arbitrary-dependency-import" = "import com.fasterxml.jackson.databind.ObjectMapper; public final class TestProcedure { public Object execute(NPDevProcedureContext ctx) { return ObjectMapper.class; } }"
}

$javaRoot = New-ScenarioRoot "java-negative"
foreach ($caseName in $negativeJavaCases.Keys) {
    Write-TrustedProcedureScenario -Root $javaRoot -ScenarioId $caseName -Source ([string]$negativeJavaCases[$caseName])
}
$javaRun = Invoke-Proof -ScenarioRoot $javaRoot -Name "java-negative" -StaticOnlyPass
if ($javaRun.report.overallStatus -ne "passed") { throw "Java negative cases did not pass fail-closed proof." }
foreach ($caseName in $negativeJavaCases.Keys) {
    $scenario = @($javaRun.report.scenarios | Where-Object { [string]$_.scenarioId -eq $caseName } | Select-Object -First 1)
    if ($null -eq $scenario -or [string]$scenario.status -ne "passed" -or @($scenario.failures).Count -lt 1) {
        throw "Java negative case did not fail closed: $caseName"
    }
}

$manifestRoot = New-ScenarioRoot "manifest-negative"
Write-TrustedProcedureScenario -Root $manifestRoot -ScenarioId "missing-manifest" -Source $safeSource -NoManifest
Write-TrustedProcedureScenario -Root $manifestRoot -ScenarioId "hash-mismatch" -Source $safeSource -HashMismatch
Write-TrustedProcedureScenario -Root $manifestRoot -ScenarioId "path-traversal" -Source $safeSource -RelativePath "../escape/TestProcedure.java"
$missingRef = Join-Path $manifestRoot "reference-missing-from-manifest"
New-Item -ItemType Directory -Force -Path (Join-Path $missingRef "procedure") | Out-Null
Set-Content -LiteralPath (Join-Path $missingRef "procedure/TestProcedure.java") -Value $safeSource -Encoding UTF8
@{
    schemaVersion = "ai-custom-procedure.v1"
    procedureId = "test"
    executionMode = "governed"
    trust = "trusted"
    sideEffectType = "none"
    requiredRole = "admin"
    tenantScoped = $true
    maxAffectedRows = 0
    inputs = @()
    outputs = @()
    implementation = @{ mode = "trustedSource"; language = "java"; entrypoint = "procedure/TestProcedure.java"; className = "TestProcedure"; method = "execute" }
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $missingRef "custom-procedure.json") -Encoding UTF8
@{
    schemaVersion = "npdev-trusted-source-manifest.v1"
    scenarioId = "reference-missing-from-manifest"
    policyVersion = "test"
    expectedOutcome = "fail"
    entries = @(@{ entryId = "placeholder"; kind = "procedure"; relativePath = "procedure/OtherProcedure.java"; language = "java"; sha256 = ("0" * 64); runtimeBinding = "procedure:other"; className = "OtherProcedure"; method = "execute"; requiredRole = "admin"; tenantScoped = $true })
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $missingRef "trusted-source-manifest.json") -Encoding UTF8
$unexpected = Join-Path $manifestRoot "unexpected-manifest-entry"
New-Item -ItemType Directory -Force -Path (Join-Path $unexpected "procedure") | Out-Null
Set-Content -LiteralPath (Join-Path $unexpected "procedure/TestProcedure.java") -Value $safeSource -Encoding UTF8
$unexpectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $unexpected "procedure/TestProcedure.java")).Hash.ToLowerInvariant()
@{
    schemaVersion = "npdev-trusted-source-manifest.v1"
    scenarioId = "unexpected-manifest-entry"
    policyVersion = "test"
    expectedOutcome = "fail"
    entries = @(@{ entryId = "unexpected"; kind = "procedure"; relativePath = "procedure/TestProcedure.java"; language = "java"; sha256 = $unexpectedHash; runtimeBinding = "procedure:test"; className = "TestProcedure"; method = "execute"; requiredRole = "admin"; tenantScoped = $true })
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $unexpected "trusted-source-manifest.json") -Encoding UTF8
$manifestRun = Invoke-Proof -ScenarioRoot $manifestRoot -Name "manifest-negative" -StaticOnlyPass
if ($manifestRun.report.overallStatus -ne "passed") { throw "Manifest negative cases did not pass fail-closed proof." }

$panelCases = [ordered]@{
    "external-script" = "<html><script src=`"https://example.com/app.js`"></script></html>"
    "external-style" = "<html><link href=`"https://example.com/app.css`" rel=`"stylesheet`"></html>"
    "external-image" = "<html><img src=`"https://example.com/logo.png`"></html>"
    "iframe" = "<html><iframe src=`"/generated/x`"></iframe></html>"
    "object" = "<html><object data=`"/generated/x`"></object></html>"
    "base" = "<html><base href=`"https://example.com/`"></html>"
    "css-import" = "<html><style>@import url(`"https://example.com/x.css`");</style></html>"
    "css-url" = "<html><style>body{background-image:url(`"https://example.com/x.png`")}</style></html>"
    "external-fetch" = "<html><script>fetch(`"https://example.com/api`")</script></html>"
    "non-generated-fetch" = "<html><script>fetch(`"/api/users`")</script></html>"
    "websocket" = "<html><script>new WebSocket(`"wss://example.com/ws`")</script></html>"
    "eval" = "<html><script>eval(`"1+1`")</script></html>"
    "function-constructor" = "<html><script>new Function(`"return 1`")</script></html>"
    "dynamic-import" = "<html><script>import(`"./x.js`")</script></html>"
    "inline-handler" = "<html><button onclick=`"alert(1)`">x</button></html>"
    "javascript-url" = "<html><a href=`"javascript:alert(1)`">x</a></html>"
}
$panelRoot = New-ScenarioRoot "panel-negative"
foreach ($caseName in $panelCases.Keys) {
    Write-TrustedPanelScenario -Root $panelRoot -ScenarioId $caseName -Source ([string]$panelCases[$caseName])
}
$panelRun = Invoke-Proof -ScenarioRoot $panelRoot -Name "panel-negative" -StaticOnlyPass
if ($panelRun.report.overallStatus -ne "passed") { throw "Panel negative cases did not pass fail-closed proof." }

$goldenRun = Invoke-Proof -ScenarioRoot (Join-Path $workspaceRoot "golden-ai-scenarios") -Name "golden-static" -StaticOnlyPass
if ($goldenRun.report.overallStatus -ne "passed") { throw "Golden trusted-source static proof did not pass." }
$runtimeRun = Invoke-Proof -ScenarioRoot (Join-Path $workspaceRoot "golden-ai-scenarios") -Name "golden-generated-runtime-proof"
if ($runtimeRun.report.trustedSourceSupportStatus -ne "passed" -or $runtimeRun.report.overallStatus -ne "passed") {
    throw "Default trusted-source generated-runtime proof should pass once real generated-runtime integration is proven."
}
if ([bool]$runtimeRun.report.overlayHarnessUsed -or [string]$runtimeRun.report.generatedRuntimeOverlayHarnessStatus -ne "not-run" -or [string]$runtimeRun.report.productGeneratedTrustedSourceIntegrationStatus -ne "passed") {
    throw "Generated-runtime proof must use product-generated trusted-source integration with overlayHarnessUsed=false."
}
if ($runtimeRun.report.trustedSourceProductionPath.overlayHarnessUsed -or [string]$runtimeRun.report.trustedSourceProductionPath.dispatchModel -ne "explicit-compile-time-generated-wiring") {
    throw "Trusted-source production path must document product-generated compile-time wiring and no overlay harness."
}
if (-not [bool]$runtimeRun.report.roleChecks.passed -or -not [bool]$runtimeRun.report.tenantChecks.passed -or -not [bool]$runtimeRun.report.procedureSmoke.passed -or -not [bool]$runtimeRun.report.panelSmoke.passed -or -not [bool]$runtimeRun.report.runtimeInvocation.passed) {
    throw "Generated-runtime booleans must pass only when backed by real generated app endpoint evidence."
}
if (-not [bool]$runtimeRun.report.manifestLock.passed -or -not [bool]$runtimeRun.report.javaContainment.passed -or -not [bool]$runtimeRun.report.panelContainment.passed -or -not [bool]$runtimeRun.report.compile.passed) {
    throw "Static trusted-source containment and manifest proof should remain green with generated-runtime integration."
}
if ($null -eq $runtimeRun.report.localHarnessEvidence -or [bool]$runtimeRun.report.localHarnessEvidence.releaseEvidence) {
    throw "Local trusted-source harness evidence must be explicitly marked as non-release evidence."
}
$generatedPanelCheck = @($runtimeRun.report.scenarios[0].checks | Where-Object { [string]$_.name -eq "real-generated-panel-route-action-smoke" } | Select-Object -First 1)
if ($null -eq $generatedPanelCheck -or -not [bool]$generatedPanelCheck.passed -or [string]::IsNullOrWhiteSpace([string]$generatedPanelCheck.evidence.details.browserActionProofPath)) {
    throw "Generated panel action proof must include direct JS action evidence against the actual generated endpoint."
}
$productGeneratedCheck = @($runtimeRun.report.scenarios[0].checks | Where-Object { [string]$_.name -eq "product-generated-trusted-source-artifacts" } | Select-Object -First 1)
if ($null -eq $productGeneratedCheck -or -not [bool]$productGeneratedCheck.passed -or [bool]$productGeneratedCheck.evidence.details.overlayHarnessUsed) {
    throw "Generated trusted-source artifacts must be emitted by the product generator path, not overlay."
}
$runtimeReportText = Get-Content -Raw -LiteralPath $runtimeRun.reportPath
foreach ($secret in @(
    (Get-GeneratedBetaLocalApiKey -ScenarioId "create-users-panel-procedure" -UserId "admin-user"),
    (Get-GeneratedBetaLocalApiKey -ScenarioId "create-users-panel-procedure" -UserId "viewer-user"),
    (Get-GeneratedBetaLocalApiKey -ScenarioId "create-users-panel-procedure" -UserId "other-admin")
)) {
    if ($runtimeReportText.Contains($secret)) {
        throw "Trusted-source proof report leaked an API key secret value."
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-trusted-source-beta0-proof-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-trusted-source-beta0-proof-tests.ps1"
    overallStatus = "passed"
    cases = @(
        [pscustomobject]@{ name = "java-forbidden-api-negative-cases"; count = $negativeJavaCases.Count; reportPath = $javaRun.reportPath },
        [pscustomobject]@{ name = "manifest-lock-negative-cases"; count = 5; reportPath = $manifestRun.reportPath },
        [pscustomobject]@{ name = "panel-containment-negative-cases"; count = $panelCases.Count; reportPath = $panelRun.reportPath },
        [pscustomobject]@{ name = "golden-static-proof"; count = @($goldenRun.report.scenarios).Count; reportPath = $goldenRun.reportPath },
        [pscustomobject]@{ name = "default-generated-runtime-proof"; count = @($runtimeRun.report.scenarios).Count; reportPath = $runtimeRun.reportPath }
    )
}
$reportPath = "scripts/reports/out/trusted-source-beta0-proof-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("Trusted-source Beta0 proof tests passed. Report: " + $reportPath)
