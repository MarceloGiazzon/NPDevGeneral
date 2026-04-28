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
$RunId = Resolve-NPDevRunId $RunId "runtimehost-sample-matrix-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\runtimehost-sample-matrix-maturity-report.json"

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

$buildTemplatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template"
$runtimehostReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json"
$sampleMatrixReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-matrix-report.json"
$doctorReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\doctor-report.json"
$betaReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
$runtimehostReport = Read-MaturityJsonFile $runtimehostReportPath
$sampleMatrixReport = Read-MaturityJsonFile $sampleMatrixReportPath
$doctorReport = Read-MaturityJsonFile $doctorReportPath
$betaReport = Read-MaturityJsonFile $betaReportPath

$sampleAppRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\simple-contact-intake\Output\App"
$sampleBuildGradlePath = Join-Path $sampleAppRoot "build.gradle"
$sampleMigrationRoot = Join-Path $sampleAppRoot "src\main\resources\db\migration"
$migrationFiles = if (Test-Path -LiteralPath $sampleMigrationRoot -PathType Container) {
    @(Get-ChildItem -LiteralPath $sampleMigrationRoot -File -Filter "*.sql" -ErrorAction SilentlyContinue)
}
else {
    @()
}
$migrationVersions = @($migrationFiles | Where-Object { $_.BaseName -match '^V([0-9]+)__' } | ForEach-Object { $matches[1] })
$duplicateMigrationVersions = @($migrationVersions | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
$releaseSampleIds = @("canonical-demo", "simple-contact-intake", "simple-user-registry", "medium-expense-approval", "restaurant-saas-multitenant")
$expectedMigrationFiles = @(
    "V5001__create_npdev_flow_instance.sql",
    "V5002__create_npdev_event_store.sql",
    "V5003__create_npdev_trace.sql",
    "V5004__add_execution_context_columns.sql",
    "V5005__create_npdev_correlation_owner.sql",
    "V5006__create_npdev_audit_log.sql",
    "V5007__add_perf_indexes.sql",
    "V5008__add_flow_instance_resume_fields.sql",
    "V5009__create_npdev_circuit_breaker.sql",
    "V5010__create_npdev_idempotency.sql",
    "V5011__create_npdev_publication_execution.sql",
    "V5012__add_flow_instance_failure_fields.sql",
    "V5013__create_npdev_publication_audit.sql",
    "V5014__create_npdev_scheduled_event.sql"
)
$existingMigrationFileNames = @($migrationFiles | ForEach-Object { $_.Name })
$missingExpectedMigrations = @($expectedMigrationFiles | Where-Object { $_ -notin $existingMigrationFileNames })
$migrationDirectories = if (Test-Path -LiteralPath $sampleAppRoot -PathType Container) {
    @(Get-ChildItem -LiteralPath $sampleAppRoot -Recurse -Directory -ErrorAction SilentlyContinue | Where-Object {
            $_.FullName -like "*\db\migration" -and $_.FullName -notmatch "\\build\\"
        })
}
else {
    @()
}

$dependencyPatterns = Test-MaturityFilePatterns -FilePath $buildTemplatePath -Patterns @("com.networknt:json-schema-validator:1.5.6")
$generatedDependencyPatterns = Test-MaturityFilePatterns -FilePath $sampleBuildGradlePath -Patterns @("com.networknt:json-schema-validator")

Add-Condition "RTM-001" "build.gradle.template includes com.networknt:json-schema-validator:1.5.6 (or compatible version)" `
    ($dependencyPatterns.allMatched) `
    "The RuntimeHost template declares the JSON schema validator dependency." `
    "The RuntimeHost template is missing the JSON schema validator dependency." `
    @{ missingPatterns = $dependencyPatterns.missing }

Add-Condition "RTM-002" "Generator's dependency propagation logic verified to include this in assembled apps" `
    ($generatedDependencyPatterns.allMatched) `
    "The assembled simple-contact-intake app build file includes the propagated JSON schema validator dependency." `
    "The assembled simple-contact-intake app build file does not show the propagated JSON schema validator dependency." `
    @{ sampleBuildGradle = "NPDevSamples\simple-contact-intake\Output\App\build.gradle"; missingPatterns = $generatedDependencyPatterns.missing }

Add-Condition "RTM-003" "simple-contact-intake sample completes ./gradlew.bat enforceSingleMigrationSource test without compile errors" `
    ($null -ne $runtimehostReport -and [string]$runtimehostReport.overallStatus -eq "passed" -and [int]$runtimehostReport.verificationCommand.exitCode -eq 0) `
    "The runtimehost canary verification for simple-contact-intake is green." `
    "The runtimehost canary verification for simple-contact-intake is not green." `
    @{
        reportPath = "scripts\reports\out\runtimehost-gate-report.json"
        overallStatus = if ($null -eq $runtimehostReport) { $null } else { [string]$runtimehostReport.overallStatus }
        exitCode = if ($null -eq $runtimehostReport) { $null } else { $runtimehostReport.verificationCommand.exitCode }
    }

$dependencyRegressionHits = @(
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'json-schema-validator|networknt'
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\tests" -Includes @("*.ps1") -Pattern 'json-schema-validator|networknt'
)
Add-Condition "RTM-004" "A focused regression test exists that fails if the dependency is removed from the template" `
    (@($dependencyRegressionHits).Count -gt 0) `
    "Focused test coverage references the schema validator dependency contract." `
    "No focused regression test evidence was found for removal of the schema validator dependency from the template." `
    @{ hits = $dependencyRegressionHits }

Add-Condition "RTM-005" "scripts\\reports\\out\\runtimehost-gate-report.json shows overallStatus = passed" `
    ($null -ne $runtimehostReport -and [string]$runtimehostReport.overallStatus -eq "passed") `
    "The runtimehost gate report is passed." `
    "The runtimehost gate report is missing or not passed." `
    @{ overallStatus = if ($null -eq $runtimehostReport) { $null } else { [string]$runtimehostReport.overallStatus } }

Add-Condition "RTM-006" "Generated app has exactly one Flyway migration source path" `
    (@($migrationDirectories).Count -eq 1) `
    "The generated simple-contact-intake app exposes exactly one db\\migration source path." `
    "The generated simple-contact-intake app does not expose exactly one db\\migration source path." `
    @{
        migrationDirectories = @($migrationDirectories | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName })
    }

Add-Condition "RTM-007" "V5001__create_npdev_flow_instance.sql through V5014__create_npdev_scheduled_event.sql are present in generated app" `
    (@($missingExpectedMigrations).Count -eq 0) `
    "The generated simple-contact-intake app contains the full expected migration sequence through V5014." `
    "The generated simple-contact-intake app is missing one or more expected migration files in the V5001-V5014 sequence." `
    @{ missingMigrationFiles = $missingExpectedMigrations; existingMigrationFiles = $existingMigrationFileNames }

Add-Condition "RTM-008" "No duplicate migration versions exist in the assembled app" `
    (@($duplicateMigrationVersions).Count -eq 0) `
    "No duplicate Flyway migration versions were found in the assembled app." `
    "Duplicate Flyway migration versions were found in the assembled app." `
    @{ duplicateVersions = $duplicateMigrationVersions }

Add-Condition "RTM-009" "enforceSingleMigrationSource Gradle task passes for simple-contact-intake" `
    ($null -ne $runtimehostReport -and [string]$runtimehostReport.overallStatus -eq "passed" -and [string]$runtimehostReport.verificationCommand.display -match 'enforceSingleMigrationSource') `
    "The runtimehost canary verification ran and passed the enforceSingleMigrationSource task." `
    "There is no passing runtimehost canary evidence for enforceSingleMigrationSource on simple-contact-intake." `
    @{
        command = if ($null -eq $runtimehostReport) { $null } else { [string]$runtimehostReport.verificationCommand.display }
    }

$migrationTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'Flyway|migrate|migration'
Add-Condition "RTM-010" "Test proves migration from empty schema to latest version succeeds" `
    (@($migrationTestHits).Count -gt 0) `
    "Migration-oriented test coverage exists in the RuntimeHost test suite." `
    "No explicit RuntimeHost test evidence was found for migration from empty schema to latest version." `
    @{ hits = $migrationTestHits | Select-Object -First 20 }

Add-Condition "RTM-011" "scripts\\reports\\out\\sample-matrix-report.json shows overallStatus = passed" `
    ($null -ne $sampleMatrixReport -and [string]$sampleMatrixReport.overallStatus -eq "passed") `
    "The sample matrix report is passed." `
    "The sample matrix report is missing or not passed." `
    @{ overallStatus = if ($null -eq $sampleMatrixReport) { $null } else { [string]$sampleMatrixReport.overallStatus } }

Add-Condition "RTM-012" "matrixCoveragePercent = 100.0" `
    ($null -ne $sampleMatrixReport -and [double]$sampleMatrixReport.matrixCoveragePercent -eq 100.0) `
    "The sample matrix currently reports 100% required coverage." `
    "The sample matrix does not currently report 100% required coverage." `
    @{ matrixCoveragePercent = if ($null -eq $sampleMatrixReport) { $null } else { $sampleMatrixReport.matrixCoveragePercent } }

Add-Condition "RTM-013" "releaseEvidence.eligible = true" `
    ($null -ne $sampleMatrixReport -and [bool]$sampleMatrixReport.releaseEvidence.eligible) `
    "The current sample matrix report is marked release-evidence eligible." `
    "The current sample matrix report is not marked release-evidence eligible." `
    @{ releaseEvidence = if ($null -eq $sampleMatrixReport) { $null } else { $sampleMatrixReport.releaseEvidence } }

$sampleStatuses = if ($null -eq $sampleMatrixReport) { @() } else { @($sampleMatrixReport.results | ForEach-Object { [pscustomobject]@{ sampleId = [string]$_.sampleId; status = [string]$_.status } }) }
$missingPassedSamples = @($releaseSampleIds | Where-Object {
        $sampleId = $_
        -not (@($sampleStatuses | Where-Object { $_.sampleId -eq $sampleId -and $_.status -eq "passed" }).Count -gt 0)
    })
Add-Condition "RTM-014" "All 5 samples pass: canonical-demo, simple-contact-intake, simple-user-registry, medium-expense-approval, restaurant-saas-multitenant" `
    (@($missingPassedSamples).Count -eq 0) `
    "All policy-defined release samples are currently passing in the sample matrix." `
    "One or more policy-defined release samples are not currently passing in the sample matrix." `
    @{ missingPassedSamples = $missingPassedSamples; sampleStatuses = $sampleStatuses }

$inputFingerprintIssues = if ($null -eq $sampleMatrixReport) {
    @("sample-matrix-report.json missing")
}
else {
    @($sampleMatrixReport.inputFingerprints | Where-Object {
            $fileNames = @($_.files | ForEach-Object { [string]$_.name })
            @("model.json", "config.json", "manifest.json") | Where-Object { $_ -notin $fileNames }
        } | ForEach-Object { [string]$_.sampleId })
}
Add-Condition "RTM-015" "Per-sample input fingerprints (SHA256 of model.json, config.json, manifest.json) recorded in report" `
    (@($inputFingerprintIssues).Count -eq 0) `
    "Every sample matrix input fingerprint entry includes model.json, config.json, and manifest.json hashes." `
    "One or more sample matrix input fingerprint entries are missing model/config/manifest hashes." `
    @{ missingFingerprintSamples = $inputFingerprintIssues }

$betaSampleMatrixStep = if ($null -eq $betaReport) { $null } else { @($betaReport.steps | Where-Object { [string]$_.name -eq "sample-matrix" } | Select-Object -First 1)[0] }
Add-Condition "RTM-016" "Aggregate beta gate no longer lists sample-matrix as failed" `
    ($null -ne $betaSampleMatrixStep -and [string]$betaSampleMatrixStep.status -ne "failed") `
    "The aggregate beta release gate does not currently list sample-matrix as failed." `
    "The aggregate beta release gate still lists sample-matrix as failed or the step is missing." `
    @{ sampleMatrixStep = $betaSampleMatrixStep }

$cleanupScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\clean-sample-output.ps1"
$runtimehostGatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-runtimehost-gate.ps1"
$sampleMatrixGatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-sample-matrix.ps1"
$cleanupFinallyPatterns = @(
    (Test-MaturityFilePatterns -FilePath $runtimehostGatePath -Patterns @("finally", "clean-sample-output.ps1")).allMatched,
    (Test-MaturityFilePatterns -FilePath $sampleMatrixGatePath -Patterns @("finally", "clean-sample-output.ps1")).allMatched
)
Add-Condition "RTM-017" "clean-sample-output.ps1 runs in finally block of verification scripts" `
    (($cleanupFinallyPatterns -notcontains $false) -and (Test-Path -LiteralPath $cleanupScriptPath -PathType Leaf)) `
    "Both runtimehost and sample-matrix verification scripts invoke clean-sample-output.ps1 from a finally block." `
    "The verification scripts do not yet both show finally-block cleanup through clean-sample-output.ps1." `
    @{
        runtimehostGate = (Test-MaturityFilePatterns -FilePath $runtimehostGatePath -Patterns @("finally", "clean-sample-output.ps1"))
        sampleMatrixGate = (Test-MaturityFilePatterns -FilePath $sampleMatrixGatePath -Patterns @("finally", "clean-sample-output.ps1"))
    }

$sampleReportsPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\simple-contact-intake\Output\Reports"
$cachePaths = @(
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\simple-contact-intake\Output\App\.gradle",
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\simple-contact-intake\Output\App\build",
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\simple-contact-intake\Output\App\node_modules"
)
$remainingCachePaths = @($cachePaths | Where-Object { Test-Path -LiteralPath $_ })
Add-Condition "RTM-018" "Output\\Reports evidence preserved while Output\\App\\.gradle, build, node_modules removed" `
    ((Test-Path -LiteralPath $sampleReportsPath -PathType Container) -and @($remainingCachePaths).Count -eq 0) `
    "Sample Output\\Reports evidence is present and disposable Output\\App caches are absent." `
    "Sample evidence preservation/cache cleanup does not yet meet the expected Output\\Reports vs Output\\App separation." `
    @{
        reportsExists = (Test-Path -LiteralPath $sampleReportsPath -PathType Container)
        remainingCachePaths = @($remainingCachePaths | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_ })
    }

$doctorOutputCleanliness = if ($null -eq $doctorReport) { $null } else { @($doctorReport.checks | Where-Object { [string]$_.name -eq "output-cleanliness" } | Select-Object -First 1)[0] }
Add-Condition "RTM-019" "Doctor (check-output-cleanliness.ps1) shows overallStatus = passed after both failed and successful runs" `
    ($null -ne $doctorOutputCleanliness -and [string]$doctorOutputCleanliness.status -eq "passed" -and @(
            Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts" -Includes @("*.ps1", "*.md") -Pattern 'failed run.*doctor|doctor passes.*failed'
        ).Count -gt 0) `
    "Doctor output-cleanliness is green and there is explicit evidence covering failed-run cleanup validation." `
    "There is not yet explicit evidence that doctor output-cleanliness stays green after both failed and successful runs." `
    @{
        doctorOutputCleanliness = $doctorOutputCleanliness
        failedRunEvidenceHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts" -Includes @("*.ps1", "*.md") -Pattern 'failed run.*doctor|doctor passes.*failed'
    }

Add-Condition "RTM-020" "scripts\\reports\\out\\doctor-report.json shows no output-cleanliness warning" `
    ($null -ne $doctorOutputCleanliness -and [string]$doctorOutputCleanliness.status -eq "passed") `
    "The doctor report shows no output-cleanliness warning." `
    "The doctor report is missing or still contains an output-cleanliness warning/failure." `
    @{ doctorOutputCleanliness = $doctorOutputCleanliness }

$failedCleanupTestHits = @(
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'cleanup.*failed|failed run.*cleanup|output-cleanliness|doctor passes'
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\tests" -Includes @("*.ps1") -Pattern 'cleanup.*failed|failed run.*cleanup|output-cleanliness|doctor passes'
)
Add-Condition "RTM-021" "Test simulates failed run, verifies cleanup occurred, verifies doctor passes" `
    (@($failedCleanupTestHits).Count -gt 0) `
    "Test evidence was found for failed-run cleanup and doctor verification behavior." `
    "No explicit test evidence was found for failed-run cleanup and follow-up doctor verification." `
    @{ hits = $failedCleanupTestHits }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "runtimehost-sample-matrix-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        releaseSampleIds = $releaseSampleIds
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
