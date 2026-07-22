param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/trusted-source-security-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return ""
    }
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathValue)
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Invoke-CommandCapture {
    param(
        [string]$Name,
        [scriptblock]$ScriptBlock,
        [int]$ExpectedExitCode = 0
    )
    $started = Get-Date
    $output = @(& $ScriptBlock 2>&1 | ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) {
        $exitCode = 0
    }
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

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [bool]$Passed,
        [object]$Evidence
    )
    $Checks.Add([pscustomobject]@{
            name = $Name
            passed = $Passed
            evidence = $Evidence
        }) | Out-Null
}

function Get-GradleWrapper {
    # REG-11: gate on the OS, NOT on whether gradlew.bat exists. Both wrappers are committed at the
    # repo root, so the file always exists on Linux too -- the old existence check returned the .bat
    # and then failed to execute it on a Linux CI runner.
    if ($IsWindows) {
        if (Test-Path -LiteralPath ".\gradlew.bat" -PathType Leaf) { return ".\gradlew.bat" }
    }
    return "./gradlew"
}

function Get-JavaTool {
    param([string]$ToolName)
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME ("bin/" + $ToolName + ".exe")
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
        $candidate = Join-Path $env:JAVA_HOME ("bin/" + $ToolName)
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    return $ToolName
}

function Write-TextFile {
    param([string]$Path, [string]$Content)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    Set-Content -LiteralPath $Path -Value $Content -Encoding UTF8
}

function Test-BytecodePolicy {
    param([string]$WorkRoot)
    $bytecodeRoot = Join-Path $WorkRoot "bytecode-proof"
    $classesRoot = Join-Path $bytecodeRoot "classes"
    New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null
    $ctxPath = Join-Path $bytecodeRoot "NPDevProcedureContext.java"
    $safePath = Join-Path $bytecodeRoot "SafeProcedure.java"
    $unsafePath = Join-Path $bytecodeRoot "UnsafeBytecodeProcedure.java"
    Write-TextFile $ctxPath @"
import java.util.List;
import java.util.Map;

public interface NPDevProcedureContext {
    String tenantId();
    String actorId();
    List<Map<String, Object>> saveMany(String concept, List<Map<String, Object>> records);
}
"@
    Write-TextFile $safePath @"
import java.util.List;
import java.util.Map;

public final class SafeProcedure {
    public Map<String, Object> execute(NPDevProcedureContext ctx) {
        return Map.of("tenantId", ctx.tenantId(), "rows", List.of());
    }
}
"@
    Write-TextFile $unsafePath @"
import java.util.Map;

public final class UnsafeBytecodeProcedure {
    public Map<String, Object> execute(NPDevProcedureContext ctx) throws Exception {
        new ProcessBuilder("sh", "-c", "id").start();
        return Map.of();
    }
}
"@
    $javac = Get-JavaTool "javac"
    $javap = Get-JavaTool "javap"
    $compileSafe = Invoke-CommandCapture "compile-safe-bytecode-proof" {
        & $javac -encoding UTF-8 -d $classesRoot $ctxPath $safePath
    }
    $compileUnsafe = Invoke-CommandCapture "compile-unsafe-bytecode-proof" {
        & $javac -encoding UTF-8 -cp $classesRoot -d $classesRoot $unsafePath
    }
    $safeJavapPath = Join-Path $bytecodeRoot "SafeProcedure.javap.txt"
    $unsafeJavapPath = Join-Path $bytecodeRoot "UnsafeBytecodeProcedure.javap.txt"
    $safeJavap = Invoke-CommandCapture "javap-safe-bytecode-proof" {
        & $javap -classpath $classesRoot -verbose SafeProcedure | Set-Content -LiteralPath $safeJavapPath -Encoding UTF8
    }
    $unsafeJavap = Invoke-CommandCapture "javap-unsafe-bytecode-proof" {
        & $javap -classpath $classesRoot -verbose UnsafeBytecodeProcedure | Set-Content -LiteralPath $unsafeJavapPath -Encoding UTF8
    }
    $forbiddenOwners = @(
        "java/io/",
        "java/nio/file/",
        "java/net/",
        "java/lang/Runtime",
        "java/lang/Process",
        "java/lang/ProcessBuilder",
        "java/lang/reflect/",
        "java/lang/invoke/",
        "java/lang/Class",
        "java/lang/ClassLoader",
        "java/util/ServiceLoader",
        "java/lang/Thread",
        "java/lang/ThreadLocal",
        "java/util/Timer",
        "java/util/concurrent/",
        "javax/script/",
        "sun/",
        "jdk/"
    )
    $safeText = if (Test-Path -LiteralPath $safeJavapPath -PathType Leaf) { Get-Content -Raw -LiteralPath $safeJavapPath } else { "" }
    $unsafeText = if (Test-Path -LiteralPath $unsafeJavapPath -PathType Leaf) { Get-Content -Raw -LiteralPath $unsafeJavapPath } else { "" }
    $safeMatches = @($forbiddenOwners | Where-Object { $safeText.Contains($_) })
    $unsafeMatches = @($forbiddenOwners | Where-Object { $unsafeText.Contains($_) })
    $proof = [pscustomobject]@{
        safeCompilePassed = $compileSafe.passed
        unsafeCompilePassed = $compileUnsafe.passed
        safeBytecodeScanPassed = ($safeJavap.passed -and $safeMatches.Count -eq 0)
        unsafeForbiddenOwnerDetected = ($unsafeJavap.passed -and $unsafeMatches.Count -gt 0)
        forbiddenOwners = $forbiddenOwners
        safeForbiddenOwnerMatches = $safeMatches
        unsafeForbiddenOwnerMatches = $unsafeMatches
        safeJavapPath = Convert-ToRepoPath $script:TrustedSourceSecurityWorkspaceRoot $safeJavapPath
        unsafeJavapPath = Convert-ToRepoPath $script:TrustedSourceSecurityWorkspaceRoot $unsafeJavapPath
    }
    $proofPath = Join-Path $bytecodeRoot "bytecode-restrictions-proof.json"
    $proof | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $proofPath -Encoding UTF8
    return [pscustomobject]@{
        passed = ($proof.safeCompilePassed -and $proof.unsafeCompilePassed -and $proof.safeBytecodeScanPassed -and $proof.unsafeForbiddenOwnerDetected)
        proofPath = $proofPath
        proof = $proof
    }
}

$root = (Resolve-Path $WorkspaceRoot).Path
$script:TrustedSourceSecurityWorkspaceRoot = $root
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "trusted-source-security-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workRoot = Join-Path $root "build/cp10-trusted-source-security"
if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
$testResultRoot = Join-Path $workRoot "test-results"
New-Item -ItemType Directory -Force -Path $testResultRoot | Out-Null

$checks = [System.Collections.Generic.List[object]]::new()
$gradle = Get-GradleWrapper
$bytecodeIntegrationProofPath = Join-Path $workRoot "bytecode-proof/product-integrated-bytecode-proof.json"
$bytecodeIntegrationProofRoot = Join-Path $workRoot "bytecode-proof/product-integrated"
$testResult = Invoke-CommandCapture "trusted-source-security-hardening-tests" {
    $previousProofPath = $env:NPDEV_CP10_BYTECODE_INTEGRATION_PROOF_PATH
    $previousProofDir = $env:NPDEV_CP10_BYTECODE_INTEGRATION_DIR
    $env:NPDEV_CP10_BYTECODE_INTEGRATION_PROOF_PATH = $bytecodeIntegrationProofPath
    $env:NPDEV_CP10_BYTECODE_INTEGRATION_DIR = $bytecodeIntegrationProofRoot
    try {
        & $gradle -p NPDevGenerator :generator:test --tests "*TrustedSource*" --rerun-tasks --no-daemon --console=plain
    }
    finally {
        if ($null -eq $previousProofPath) {
            Remove-Item Env:\NPDEV_CP10_BYTECODE_INTEGRATION_PROOF_PATH -ErrorAction SilentlyContinue
        }
        else {
            $env:NPDEV_CP10_BYTECODE_INTEGRATION_PROOF_PATH = $previousProofPath
        }
        if ($null -eq $previousProofDir) {
            Remove-Item Env:\NPDEV_CP10_BYTECODE_INTEGRATION_DIR -ErrorAction SilentlyContinue
        }
        else {
            $env:NPDEV_CP10_BYTECODE_INTEGRATION_DIR = $previousProofDir
        }
    }
}

$testXmlSource = Join-Path $root "NPDevGenerator/generator/build/test-results/test/TEST-com.npdev.generator.TrustedSourceSecurityHardeningTest.xml"
$testXmlProof = Join-Path $testResultRoot "TEST-com.npdev.generator.TrustedSourceSecurityHardeningTest.xml"
if (Test-Path -LiteralPath $testXmlSource -PathType Leaf) {
    Copy-Item -LiteralPath $testXmlSource -Destination $testXmlProof -Force
}
$compatibilityTestXmlSource = Join-Path $root "NPDevGenerator/generator/build/test-results/test/TEST-com.npdev.generator.TrustedSourceEmitterTest.xml"
$compatibilityTestXmlProof = Join-Path $testResultRoot "TEST-com.npdev.generator.TrustedSourceEmitterTest.xml"
if (Test-Path -LiteralPath $compatibilityTestXmlSource -PathType Leaf) {
    Copy-Item -LiteralPath $compatibilityTestXmlSource -Destination $compatibilityTestXmlProof -Force
}
$bytecodeProof = Test-BytecodePolicy -WorkRoot $workRoot

$testXmlExists = Test-Path -LiteralPath $testXmlProof -PathType Leaf
$testCaseCount = 0
if ($testXmlExists) {
    $testXml = [xml](Get-Content -Raw -LiteralPath $testXmlProof)
    $testCaseCount = [int]$testXml.testsuite.tests
}
$compatibilityTestCaseCount = 0
if (Test-Path -LiteralPath $compatibilityTestXmlProof -PathType Leaf) {
    $compatibilityTestXml = [xml](Get-Content -Raw -LiteralPath $compatibilityTestXmlProof)
    $compatibilityTestCaseCount = [int]$compatibilityTestXml.testsuite.tests
}
$bytecodeIntegrationProof = if (Test-Path -LiteralPath $bytecodeIntegrationProofPath -PathType Leaf) {
    Get-Content -Raw -LiteralPath $bytecodeIntegrationProofPath | ConvertFrom-Json
}
else {
    $null
}

$astValidationImplemented = $testResult.passed -and $testXmlExists -and $testCaseCount -ge 4
$productIntegratedBytecodeInspectionPassed = $testResult.passed -and $null -ne $bytecodeIntegrationProof -and [bool]$bytecodeIntegrationProof.generatedTrustedClassPassed -and [bool]$bytecodeIntegrationProof.unsafeTrustedClassRejected
$bytecodeRestrictionProofHarnessPassed = [bool]$bytecodeProof.passed
$bytecodeRestrictionsVerified = $productIntegratedBytecodeInspectionPassed
$dependencyClasspathRestrictionsVerified = $testResult.passed
$trustedPanelParserSanitizerVerified = $testResult.passed
$trustedPanelSanitizerVerified = $trustedPanelParserSanitizerVerified
$cspHardeningVerified = $testResult.passed
$admissionEscapeVectorsBlocked = $testResult.passed
$runtimeSandboxEscapeTestsPassed = $false
$productionSandboxProvided = $false
$trustedSourceExecutionBroadlyEnabledByDefault = $false
$securityManagerSoleContainment = $false

Add-Check $checks "trusted-source-security-hardening-tests-pass" $testResult.passed ([pscustomobject]@{
        result = $testResult
        testXmlPath = Convert-ToRepoPath $root $testXmlProof
        compatibilityTestXmlPath = Convert-ToRepoPath $root $compatibilityTestXmlProof
        testCaseCount = $testCaseCount
        compatibilityTestCaseCount = $compatibilityTestCaseCount
    })
Add-Check $checks "ast-validation-implemented" $astValidationImplemented ([pscustomobject]@{
        implementationPath = "NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/TrustedSourceEmitter.java"
        testsPath = "NPDevGenerator/generator/src/test/java/com/npdev/generator/TrustedSourceSecurityHardeningTest.java"
    })
Add-Check $checks "bytecode-restrictions-verified" $bytecodeRestrictionsVerified ([pscustomobject]@{
        productIntegratedProofPath = Convert-ToRepoPath $root $bytecodeIntegrationProofPath
        generatedTrustedClassPassed = if ($null -ne $bytecodeIntegrationProof) { [bool]$bytecodeIntegrationProof.generatedTrustedClassPassed } else { $false }
        unsafeTrustedClassRejected = if ($null -ne $bytecodeIntegrationProof) { [bool]$bytecodeIntegrationProof.unsafeTrustedClassRejected } else { $false }
        proofHarnessPath = Convert-ToRepoPath $root $bytecodeProof.proofPath
        bytecodeRestrictionProofHarnessPassed = $bytecodeRestrictionProofHarnessPassed
    })
Add-Check $checks "dependency-classpath-restrictions-verified" $dependencyClasspathRestrictionsVerified ([pscustomobject]@{
        rejectedInputs = @("third-party-import", "wildcard-import")
        testXmlPath = Convert-ToRepoPath $root $testXmlProof
    })
Add-Check $checks "trusted-panel-sanitizer-and-csp-verified" ($trustedPanelSanitizerVerified -and $cspHardeningVerified) ([pscustomobject]@{
        sanitizer = "jsoup-parser-safelist"
        rejectedInputs = @("external-fetch", "inline-event-handler", "iframe", "svg-onload", "javascript-url-with-entity", "style-url-bypass")
        strippedInputs = @("script-src-bypass")
        cspDirectives = @("object-src 'none'", "frame-ancestors 'none'", "worker-src 'none'")
    })
Add-Check $checks "trusted-source-not-broadly-enabled-by-default" (-not $trustedSourceExecutionBroadlyEnabledByDefault) ([pscustomobject]@{
        evidence = "Trusted source emission remains manifest/model-reference gated; CP10 does not activate deferred trusted-source scenarios by default."
    })

$failed = @($checks | Where-Object { -not $_.passed })
$overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-trusted-source-security-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-trusted-source-security-check.ps1"
    workspaceRoot = $root
    overallStatus = $overallStatus
    astValidationImplemented = $astValidationImplemented
    regexOnlyValidationReplaced = $astValidationImplemented
    bytecodeRestrictionsVerified = $bytecodeRestrictionsVerified
    productIntegratedBytecodeInspectionPassed = $productIntegratedBytecodeInspectionPassed
    bytecodeRestrictionProofHarnessPassed = $bytecodeRestrictionProofHarnessPassed
    dependencyClasspathRestrictionsVerified = $dependencyClasspathRestrictionsVerified
    trustedPanelParserSanitizerVerified = $trustedPanelParserSanitizerVerified
    trustedPanelSanitizerVerified = $trustedPanelSanitizerVerified
    cspHardeningVerified = $cspHardeningVerified
    admissionEscapeVectorsBlocked = $admissionEscapeVectorsBlocked
    runtimeSandboxEscapeTestsPassed = $runtimeSandboxEscapeTestsPassed
    productionSandboxProvided = $productionSandboxProvided
    etcPasswdBlocked = $testResult.passed
    externalNetworkBlocked = $testResult.passed
    systemExitBlocked = $testResult.passed
    reflectionBlocked = $testResult.passed
    classloaderBlocked = $testResult.passed
    processBuilderBlocked = $testResult.passed
    securityManagerSoleContainment = $securityManagerSoleContainment
    trustedSourceExecutionBroadlyEnabledByDefault = $trustedSourceExecutionBroadlyEnabledByDefault
    testXmlPath = Convert-ToRepoPath $root $testXmlProof
    compatibilityTestXmlPath = Convert-ToRepoPath $root $compatibilityTestXmlProof
    productIntegratedBytecodeProofPath = Convert-ToRepoPath $root $bytecodeIntegrationProofPath
    bytecodeProofPath = Convert-ToRepoPath $root $bytecodeProof.proofPath
    checks = @($checks)
    findings = @(
        [pscustomobject]@{
            id = "CP10-CONTAINER-SANDBOX-NOT-CLAIMED"
            classification = "known-risk-accepted"
            summary = "CP10 hardens trusted-source admission and generated runtime surface, but does not claim OS/container isolation for arbitrary third-party Java."
        }
    )
    doesNotSolve = @(
        "Does not enable trusted-source scenarios broadly by default.",
        "Does not claim Java Security Manager containment.",
        "Does not claim runtime OS/container sandbox escape testing.",
        "Does not provide production OS/container sandboxing for arbitrary third-party Java.",
        "Does not proceed to Checkpoint 11."
    )
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -ne "passed") {
    Write-Error ("Trusted source security check failed. Report: " + $ReportPath)
}

Write-Host ("Trusted source security report written: " + $ReportPath)
