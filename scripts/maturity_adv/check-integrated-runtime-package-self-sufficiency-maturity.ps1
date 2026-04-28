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
$RunId = Resolve-NPDevRunId $RunId "runtime-self-sufficiency-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\integrated-runtime-package-self-sufficiency-maturity-report.json"

$checks = @()

$classpathAudit = Get-ClassPathResourceAudit -WorkspaceRoot $WorkspaceRoot -SourceRelativeRoot "NPDevRuntimeHost\src\main\java" -ResourcesRelativeRoot "NPDevRuntimeHost\src\main\resources"
$templateCompletenessStatus = if (@($classpathAudit.missingReferences).Count -eq 0 -and @($classpathAudit.unresolvedReferences).Count -eq 0) { "passed" } else { "failed" }
$checks += New-MaturityCheck `
    -Name "template-completeness" `
    -Status $templateCompletenessStatus `
    -Expectation "Template-owned classpath resources referenced by the runtime host should actually exist in the template resource tree." `
    -Summary $(if ($templateCompletenessStatus -eq "passed") { "All detected ClassPathResource references resolved to real template resources." } else { "One or more ClassPathResource references do not resolve to template resources." }) `
    -Data @{
        totalReferences = $classpathAudit.totalReferences
        missingReferences = $classpathAudit.missingReferences
        unresolvedReferences = $classpathAudit.unresolvedReferences
    }

$assembledPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "scripts\quality\run-sample-matrix.ps1",
    "scripts\quality\run-runtimehost-gate.ps1",
    "NPDevGenerator\generator\src\test\java\com\npdev\generator\OfficialSamplesGenerationSmokeTest.java",
    "NPDevGenerator\generator\src\test\java\com\npdev\generator\CanonicalDemoGenerationSmokeTest.java"
) -PathType Leaf
$assembledReport = Get-MaturityReportMetadata (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-matrix-report.json")
$assembledStatus = if (-not $assembledPaths.allPresent) {
    "failed"
}
elseif (-not $assembledReport.exists) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "assembled-app-verifiability" `
    -Status $assembledStatus `
    -Expectation "Representative assembled-app behavior should be provable through smoke tests and matrix-style verification." `
    -Summary $(if ($assembledStatus -eq "passed") { "Assembly verification tooling and current matrix evidence are present." } elseif ($assembledStatus -eq "warning") { "Assembly verification tooling exists, but current matrix evidence is missing." } else { "Assembled-app verification assets are incomplete." }) `
    -Data @{
        existing = $assembledPaths.existing
        missing = $assembledPaths.missing
        sampleMatrixReportExists = $assembledReport.exists
        sampleMatrixReportStatus = $assembledReport.overallStatus
    }

$bootstrapReadmeCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "README.md") -Patterns @(
    "Start with PROJECT_DIGEST.md",
    "Current root automation entrypoints"
)
$bootstrapRuntimeHostCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\PROJECT_DIGEST.md") -Patterns @(
    "Do not build or run directly from",
    'Generated final apps are assembled by `NPDevGenerator`',
    "Run from the assembled final app root instead"
)
$bootstrapPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevRuntimeHost\docker-compose.yml",
    "NPDevRuntimeHost\reset-db.ps1",
    "NPDevRuntimeHost\reset-db-docker.ps1",
    "scripts\doctor\npdev-doctor.ps1"
) -PathType Leaf
$bootstrapStatus = if ($bootstrapReadmeCheck.allMatched -and $bootstrapRuntimeHostCheck.allMatched -and $bootstrapPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "bootstrap-clarity" `
    -Status $bootstrapStatus `
    -Expectation "The path from template source to assembled, runnable app should be explicit and repeatable." `
    -Summary $(if ($bootstrapStatus -eq "passed") { "Bootstrap and assembly guidance is explicit in docs and helper scripts." } else { "Bootstrap guidance exists in part but is not fully complete." }) `
    -Data @{
        readmeMissingPatterns = $bootstrapReadmeCheck.missing
        runtimeHostDigestMissingPatterns = $bootstrapRuntimeHostCheck.missing
        missingPaths = $bootstrapPaths.missing
    }

$runtimeDataFiles = @(
    "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\internal\EndUserLaunchChecklistService.java",
    "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\internal\OperationalReadinessDashboardService.java",
    "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\internal\ExplainabilityBundleService.java",
    "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\internal\TenantOperationalAdministrationService.java",
    "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\experimental\BetaReadinessConsolidationService.java"
)
$runtimeDataFindings = [System.Collections.Generic.List[object]]::new()
foreach ($relativePath in $runtimeDataFiles) {
    $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
    $content = if (Test-Path -LiteralPath $absolutePath -PathType Leaf) { Get-Content -LiteralPath $absolutePath -Raw } else { "" }
    [void]$runtimeDataFindings.Add([pscustomobject]@{
            path = $relativePath
            exists = (Test-Path -LiteralPath $absolutePath -PathType Leaf)
            guardsMissingData = ($content -match 'Files\.exists\(')
            persistsWithCreateDirectories = ($content -match 'Files\.createDirectories\(')
        })
}
$runtimeDataMissing = @($runtimeDataFindings | Where-Object { -not $_.exists -or -not $_.guardsMissingData })
$runtimeDataStatus = if (@($runtimeDataMissing).Count -eq 0) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "runtime-data-assumptions" `
    -Status $runtimeDataStatus `
    -Expectation "Runtime-data-oriented services should explicitly guard missing-data scenarios instead of assuming populated folders." `
    -Summary $(if ($runtimeDataStatus -eq "passed") { "The sampled runtime-data services all contain explicit missing-data guards." } else { "One or more sampled runtime-data services are missing explicit missing-data guards." }) `
    -Data @{
        findings = $runtimeDataFindings
        missingGuardCoverage = $runtimeDataMissing
    }

$packagingPolicyCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template") -Patterns @(
    "enforceSingleMigrationSource",
    "schema-realization analysis artifacts must never go into runtime classpath",
    "generated resources may provide npdev metadata/static assets/templates"
)
$packagingPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevGenerator\generator\src\main\java\com\npdev\generator\emitters\MetadataManifestAssetEmitter.java",
    "NPDevRuntimeHost\src\main\resources\npdev\runtime-supported-controllers.json"
) -PathType Leaf
$libsSyncReport = Get-MaturityReportMetadata (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-libs-sync-report.json")
$packagingStatus = if (-not $packagingPolicyCheck.allMatched -or -not $packagingPaths.allPresent) {
    "failed"
}
elseif (-not $libsSyncReport.exists) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "packaging-integrity" `
    -Status $packagingStatus `
    -Expectation "Packaging should be protected by resource-policy checks, manifest emitters, and visible library-sync evidence." `
    -Summary $(if ($packagingStatus -eq "passed") { "Packaging policy markers, manifest emitters, and libs-sync evidence are all present." } elseif ($packagingStatus -eq "warning") { "Packaging policy markers exist, but current libs-sync evidence is missing." } else { "Packaging policy or manifest-integrity markers are missing." }) `
    -Data @{
        missingPolicyPatterns = $packagingPolicyCheck.missing
        missingPaths = $packagingPaths.missing
        libsSyncReportExists = $libsSyncReport.exists
        libsSyncReportStatus = $libsSyncReport.overallStatus
    }

$applicationFiles = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources") -Filter "application*" -File -ErrorAction SilentlyContinue)
$portabilityPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevRuntimeHost\docker-compose.yml",
    "NPDevRuntimeHost\reset-db.ps1",
    "NPDevRuntimeHost\reset-db-docker.ps1",
    "scripts\quality\run-sample-matrix.ps1"
) -PathType Leaf
$portabilityStatus = if (@($applicationFiles).Count -ge 5 -and $portabilityPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "environment-portability" `
    -Status $portabilityStatus `
    -Expectation "Runtime packaging should account for multiple supported environments and repeatable local setup paths." `
    -Summary $(if ($portabilityStatus -eq "passed") { "Multiple runtime profiles and local environment helpers are present." } else { "Environment portability is only partially evidenced." }) `
    -Data @{
        applicationFileCount = @($applicationFiles).Count
        applicationFiles = @($applicationFiles | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName })
        missingPaths = $portabilityPaths.missing
    }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "integrated-runtime-package-self-sufficiency" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        checkedAreas = @(
            "template-completeness",
            "assembled-app-verifiability",
            "bootstrap-clarity",
            "runtime-data-assumptions",
            "packaging-integrity",
            "environment-portability"
        )
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
