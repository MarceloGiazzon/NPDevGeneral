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
$RunId = Resolve-NPDevRunId $RunId "architecture-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\architecture-maturity-report.json"

$checks = @()

$boundaryPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "README.md",
    ".npdev-root",
    "NPDevContract\.npdev-root",
    "NPDevEditor\.npdev-root",
    "NPDevGenerator\.npdev-root",
    "NPDevRuntimeHost\.npdev-root",
    "scripts\doctor\check-root-boundaries.ps1",
    "scripts\hygiene\check-root-boundaries.ps1",
    "NPDevContract\dsl\src\test\java\com\npdev\dsl\v1\RootBoundaryArchUnitTest.java",
    "NPDevRuntimeHost\src\test\java\com\finalexec\RootBoundaryArchUnitTest.java"
) -PathType Any
$unexpectedRootBuildFiles = @(
    @("build.gradle", "settings.gradle", "package.json") | Where-Object {
        Test-Path -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot $_) -PathType Leaf
    }
)
$boundaryStatus = if ($boundaryPaths.allPresent -and $unexpectedRootBuildFiles.Count -eq 0) { "passed" } else { "failed" }
$boundarySummary = if ($boundaryStatus -eq "passed") {
    "Project boundaries are documented and reinforced by boundary-checking assets."
}
else {
    "Boundary ownership is incomplete or the workspace root has build-coupling files that should not be here."
}
$checks += New-MaturityCheck `
    -Name "responsibility-boundaries" `
    -Status $boundaryStatus `
    -Expectation "Subprojects should have explicit responsibilities and machine-checkable boundary enforcement." `
    -Summary $boundarySummary `
    -Data @{
        existing = $boundaryPaths.existing
        missing = $boundaryPaths.missing
        unexpectedRootBuildFiles = $unexpectedRootBuildFiles
    }

$contractPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevContract\docs\MODEL-CONTRACT.md",
    "NPDevContract\docs\CONFIG-CONTRACT.md",
    "NPDevContract\docs\GENERATOR-HANDOFF-CONTRACT.md",
    "NPDevContract\docs\RUNTIME-MANIFEST-CONTRACT.md",
    "NPDevContract\docs\VERSIONING.md",
    "NPDevContract\dsl\src\test\java\com\npdev\dsl\v1\DslSchemaConformanceTest.java",
    "NPDevGenerator\generator\src\test\java\com\npdev\generator\GeneratorApiVersioningContractTest.java"
) -PathType Leaf
$versioningCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\docs\VERSIONING.md") -Patterns @(
    "Breaking changes",
    "Compatible additive changes",
    "Do not silently change"
)
$contractStatus = if ($contractPaths.allPresent -and $versioningCheck.allMatched) { "passed" } else { "failed" }
$checks += New-MaturityCheck `
    -Name "contract-first-evolution" `
    -Status $contractStatus `
    -Expectation "Contracts should be documented, versioned, and backed by contract tests." `
    -Summary $(if ($contractStatus -eq "passed") { "Contract evolution has both documentation and dedicated contract/versioning tests." } else { "Contract evolution is missing documentation markers or dedicated versioning/schema tests." }) `
    -Data @{
        existing = $contractPaths.existing
        missing = $contractPaths.missing
        versioningPatternsMissing = $versioningCheck.missing
    }

$determinismPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "scripts\hygiene\check-deterministic-generation.ps1",
    "NPDevGenerator\generator\src\main\java\com\npdev\generator\emitters\GeneratedFolderSignatureAssetEmitter.java",
    "NPDevGenerator\generator\src\main\java\com\npdev\generator\emitters\MetadataManifestAssetEmitter.java",
    "NPDevGenerator\generator\src\test\java\com\npdev\generator\RegenerationEvolutionSafetyTest.java",
    "NPDevGenerator\generator\src\test\java\com\npdev\generator\assembly\FinalAppAssemblerTest.java"
) -PathType Leaf
$determinismReport = Get-MaturityReportMetadata (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json")
$determinismStatus = if (-not $determinismPaths.allPresent) {
    "failed"
}
elseif (-not $determinismReport.exists -or -not [string]::IsNullOrWhiteSpace([string]$determinismReport.parseError)) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "assembly-determinism" `
    -Status $determinismStatus `
    -Expectation "Generation should be deterministic and emit assembly metadata that can be re-verified." `
    -Summary $(if ($determinismStatus -eq "passed") { "Deterministic generation tooling and evidence are present." } elseif ($determinismStatus -eq "warning") { "Deterministic generation tooling exists, but current evidence is missing or unreadable." } else { "Deterministic generation tooling or tests are missing." }) `
    -Data @{
        existing = $determinismPaths.existing
        missing = $determinismPaths.missing
        evidenceReportExists = $determinismReport.exists
        evidenceReportStatus = $determinismReport.overallStatus
        evidenceParseError = $determinismReport.parseError
    }

$runtimePolicyCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template") -Patterns @(
    "generated resources may provide npdev metadata/static assets/templates",
    "runtime schema-realization SQL comes from src/main/resources/db/migration",
    "schema-realization analysis artifacts must never go into runtime classpath",
    "enforceSingleMigrationSource"
)
$resourcePolicyStatus = if ($runtimePolicyCheck.allMatched) { "passed" } else { "failed" }
$checks += New-MaturityCheck `
    -Name "resource-ownership-clarity" `
    -Status $resourcePolicyStatus `
    -Expectation "Runtime resource provenance should be explicit in the build template and protected by policy checks." `
    -Summary $(if ($resourcePolicyStatus -eq "passed") { "Runtime resource policy markers are explicit in the build template." } else { "Runtime resource ownership rules are not fully expressed in the build template." }) `
    -Data @{
        buildTemplate = "NPDevRuntimeHost\build.gradle.template"
        missingPatterns = $runtimePolicyCheck.missing
    }

$proofPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "scripts\quality\run-runtime-surface-evidence.ps1",
    "scripts\quality\run-hygiene-gate.ps1",
    "NPDevRuntimeHost\src\test\java\com\finalexec\SupportedRuntimeSurfacePackagingTest.java",
    "NPDevRuntimeHost\src\test\java\com\finalexec\SupportedRuntimeSurfaceAllowlistIntegrationTest.java"
) -PathType Leaf
$proofReports = @(
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-classification-report.json")
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-allowlist-report.json")
)
$missingProofReports = @($proofReports | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) } | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_ })
$proofStatus = if (-not $proofPaths.allPresent) {
    "failed"
}
elseif ($missingProofReports.Count -gt 0) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "architectural-proof" `
    -Status $proofStatus `
    -Expectation "Architecture claims should be backed by executable tests and runtime surface evidence." `
    -Summary $(if ($proofStatus -eq "passed") { "Runtime surface proof assets and evidence reports are present." } elseif ($proofStatus -eq "warning") { "Proof scripts and tests exist, but current runtime-surface evidence is missing." } else { "Architectural proof assets are incomplete." }) `
    -Data @{
        existing = $proofPaths.existing
        missing = $proofPaths.missing
        missingEvidenceReports = $missingProofReports
    }

$extensionPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevRuntimeHost\src\main\resources\npdev\schema\npdev-plugin-manifest-v1.schema.json",
    "NPDevRuntimeHost\src\main\resources\npdev\schema\npdev-plugin-package-v1.schema.json",
    "NPDevRuntimeHost\src\main\resources\npdev\schema\npdev-plugin-repository-v1.schema.json",
    "scripts\quality\run-plugin-gate.ps1",
    "NPDevRuntimeHost\src\test\java\com\finalexec\PluginManifestLoaderTest.java",
    "NPDevRuntimeHost\src\test\java\com\finalexec\PluginPackageDescriptorLoaderTest.java",
    "NPDevRuntimeHost\src\test\java\com\finalexec\RuntimePluginPackageAdmissionEvaluatorTest.java",
    "NPDevRuntimeHost\src\test\java\com\finalexec\RuntimePluginPackageRealizationServiceTest.java",
    "NPDevGenerator\generator\src\test\java\com\npdev\generator\PluginRequirementAssetEmitterTest.java"
) -PathType Leaf
$extensionStatus = if ($extensionPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "extension-model-stability" `
    -Status $extensionStatus `
    -Expectation "Plugin and capability extension surfaces should have schemas, tests, and gate coverage." `
    -Summary $(if ($extensionStatus -eq "passed") { "Extension surfaces have schema, gate, and test coverage." } else { "Extension-surface coverage exists in part but is missing one or more schema/test/gate assets." }) `
    -Data @{
        existing = $extensionPaths.existing
        missing = $extensionPaths.missing
    }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "architecture" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        checkedAreas = @(
            "responsibility-boundaries",
            "contract-first-evolution",
            "assembly-determinism",
            "resource-ownership-clarity",
            "architectural-proof",
            "extension-model-stability"
        )
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru

