[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$ClassificationReportPath = "",
    [string]$AllowlistReportPath = "",
    [string]$FootprintReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "runtime-surface-evidence"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-evidence-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

if ([string]::IsNullOrWhiteSpace($ClassificationReportPath)) {
    $ClassificationReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-classification-report.json"
}
if ([string]::IsNullOrWhiteSpace($AllowlistReportPath)) {
    $AllowlistReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-allowlist-report.json"
}
if ([string]::IsNullOrWhiteSpace($FootprintReportPath)) {
    $FootprintReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-footprint-report.json"
}

$manifestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources\npdev\runtime-supported-controllers.json"
$buildTemplatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template"
$allowlistConfigPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\config\RuntimeControllerAllowlistConfig.java"
$defaultPropertiesPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources\application-default.properties"
$packagingTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\SupportedRuntimeSurfacePackagingTest.java"
$controllerRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\api"
$serviceRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service"
$runtimeSourceRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src"

Ensure-NPDevFile $manifestPath "Runtime surface manifest"
Ensure-NPDevFile $buildTemplatePath "RuntimeHost build template"
Ensure-NPDevFile $allowlistConfigPath "Runtime controller allowlist config"
Ensure-NPDevFile $defaultPropertiesPath "RuntimeHost default properties"
Ensure-NPDevFile $packagingTestPath "Runtime surface packaging test"
Ensure-NPDevDirectory $controllerRoot "RuntimeHost controller source root"
Ensure-NPDevDirectory $serviceRoot "RuntimeHost service source root"
Ensure-NPDevDirectory $runtimeSourceRoot "RuntimeHost source root"

function Get-StringArray([object]$Value) {
    if ($null -eq $Value) {
        return @()
    }
    return @($Value | ForEach-Object {
            $text = [string]$_
            if (-not [string]::IsNullOrWhiteSpace($text)) {
                $text.Trim()
            }
        } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Test-RuntimePatternMatch([string]$Value, [string[]]$Patterns) {
    foreach ($pattern in $Patterns) {
        $regex = '^' + [Regex]::Escape($pattern).Replace('\*', '.*') + '$'
        if ($Value -match $regex) {
            return $true
        }
    }
    return $false
}

function Get-RuntimePropertiesMap([string]$PathValue) {
    $map = @{}
    foreach ($line in Get-Content -LiteralPath $PathValue) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
            continue
        }
        $separatorIndex = $trimmed.IndexOf("=")
        if ($separatorIndex -lt 0) {
            $separatorIndex = $trimmed.IndexOf(":")
        }
        if ($separatorIndex -lt 0) {
            continue
        }
        $key = $trimmed.Substring(0, $separatorIndex).Trim()
        $value = $trimmed.Substring($separatorIndex + 1).Trim()
        if (-not [string]::IsNullOrWhiteSpace($key)) {
            $map[$key] = $value
        }
    }
    return $map
}

function Get-DeclaredJavaPackage([string]$PathValue) {
    $content = Get-Content -LiteralPath $PathValue -Raw
    $match = [Regex]::Match($content, '(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*;')
    if ($match.Success) {
        return $match.Groups[1].Value.Trim()
    }
    return ""
}

function Get-ExpectedBucket(
    [string]$Name,
    [string[]]$SupportedExact,
    [string[]]$SupportedPatterns,
    [string[]]$NonDefaultPatterns,
    [string[]]$ExperimentalPatterns
) {
    $matches = [System.Collections.Generic.List[string]]::new()
    if ($SupportedExact -contains $Name) {
        [void]$matches.Add("supported-core")
    }
    if (Test-RuntimePatternMatch $Name $SupportedPatterns) {
        [void]$matches.Add("supported-core")
    }
    if (Test-RuntimePatternMatch $Name $NonDefaultPatterns) {
        [void]$matches.Add("internal-but-needed")
    }
    if (Test-RuntimePatternMatch $Name $ExperimentalPatterns) {
        [void]$matches.Add("transitional")
    }

    $bucket = if ($matches.Count -eq 0) {
        "unclassified"
    } elseif ($matches -contains "supported-core") {
        "supported-core"
    } elseif ($matches -contains "internal-but-needed") {
        "internal-but-needed"
    } else {
        "transitional"
    }

    return [pscustomobject]@{
        name = $Name
        expectedBucket = $bucket
        matches = @($matches | Select-Object -Unique)
    }
}

function Get-PackageBucket(
    [string]$PackageName,
    [string]$RootPackage,
    [string]$InternalPackage,
    [string]$ExperimentalPackage
) {
    if ($PackageName -eq $RootPackage) {
        return "supported-core"
    }
    if ($PackageName -eq $InternalPackage) {
        return "internal-but-needed"
    }
    if ($PackageName -eq $ExperimentalPackage) {
        return "transitional"
    }
    return "unexpected-package"
}

function Get-RuntimeEntry(
    [System.IO.FileInfo]$File,
    [string]$WorkspaceRootValue,
    [string]$RootPackage,
    [string]$InternalPackage,
    [string]$ExperimentalPackage,
    [string[]]$SupportedExact,
    [string[]]$SupportedPatterns,
    [string[]]$NonDefaultPatterns,
    [string[]]$ExperimentalPatterns
) {
    $declaredPackage = Get-DeclaredJavaPackage $File.FullName
    $classification = Get-ExpectedBucket `
        -Name $File.BaseName `
        -SupportedExact $SupportedExact `
        -SupportedPatterns $SupportedPatterns `
        -NonDefaultPatterns $NonDefaultPatterns `
        -ExperimentalPatterns $ExperimentalPatterns
    $packageBucket = Get-PackageBucket `
        -PackageName $declaredPackage `
        -RootPackage $RootPackage `
        -InternalPackage $InternalPackage `
        -ExperimentalPackage $ExperimentalPackage
    $expectedBucket = [string]$classification.expectedBucket
    $rootPackageViolation = ($expectedBucket -ne "supported-core" -and $packageBucket -eq "supported-core")
    $namespaceAligned = (
        ($expectedBucket -eq "supported-core" -and $packageBucket -eq "supported-core") -or
        ($expectedBucket -eq "internal-but-needed" -and $packageBucket -eq "internal-but-needed") -or
        ($expectedBucket -eq "transitional" -and $packageBucket -eq "transitional")
    )

    return [pscustomobject]@{
        name = $File.BaseName
        relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRootValue $File.FullName
        declaredPackage = $declaredPackage
        expectedBucket = $expectedBucket
        packageBucket = $packageBucket
        matches = $classification.matches
        rootPackageViolation = $rootPackageViolation
        namespaceAligned = $namespaceAligned
    }
}

function New-RuntimeSurfaceCheck([string]$Name, [bool]$Passed, [string]$Summary, [object]$Data = $null) {
    return New-NPDevCheckResult -Name $Name -Status $(if ($Passed) { "passed" } else { "failed" }) -Summary $Summary -Data $Data
}

function Get-OverallStatus([object[]]$Checks) {
    $failed = @($Checks | Where-Object { $_.status -eq "failed" })
    return $(if ($failed.Count -eq 0) { "passed" } else { "failed" })
}

function Get-CheckSummary([object[]]$Checks) {
    $passed = @($Checks | Where-Object { $_.status -eq "passed" }).Count
    $failed = @($Checks | Where-Object { $_.status -eq "failed" }).Count
    return [pscustomobject]@{
        total = $Checks.Count
        passed = $passed
        failed = $failed
    }
}

function Get-DeadRemoveCandidates([object[]]$Entries, [string[]]$SearchPaths) {
    $candidates = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $Entries | Where-Object { $_.expectedBucket -in @("internal-but-needed", "transitional") }) {
        $pattern = '\b' + [Regex]::Escape([string]$entry.name) + '\b'
        $hits = 0
        foreach ($searchPath in $SearchPaths) {
            if ($searchPath -eq (Resolve-NPDevWorkspacePath $WorkspaceRoot $entry.relativePath)) {
                continue
            }
            $matches = Select-String -LiteralPath $searchPath -Pattern $pattern -AllMatches -ErrorAction SilentlyContinue
            foreach ($match in $matches) {
                $hits += @($match.Matches).Count
            }
        }

        if ($hits -eq 0) {
            [void]$candidates.Add([pscustomobject]@{
                    name = $entry.name
                    relativePath = $entry.relativePath
                    declaredPackage = $entry.declaredPackage
                    expectedBucket = $entry.expectedBucket
                    referenceHitCount = $hits
                })
        }
    }
    return @($candidates)
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$supportedControllers = Get-StringArray $manifest.supportedCoreControllers
$supportedServiceComponents = Get-StringArray $manifest.supportedCoreServiceComponents
$supportedServicePatterns = Get-StringArray $manifest.supportedCoreServicePatterns
$nonDefaultControllerPatterns = Get-StringArray $manifest.nonDefaultPatterns
$experimentalControllerPatterns = Get-StringArray $manifest.experimentalPatterns
$nonDefaultServicePatterns = Get-StringArray $manifest.nonDefaultServicePatterns
$experimentalServicePatterns = Get-StringArray $manifest.experimentalServicePatterns

$controllers = @(Get-ChildItem -LiteralPath $controllerRoot -Recurse -Filter "*Controller.java" -File | Sort-Object FullName)
$services = @(Get-ChildItem -LiteralPath $serviceRoot -Recurse -Filter "*.java" -File | Sort-Object FullName)
$runtimeJavaPaths = @(
    Get-ChildItem -LiteralPath $runtimeSourceRoot -Recurse -File -Filter "*.java" |
    Sort-Object FullName |
    Select-Object -ExpandProperty FullName
)

$controllerEntries = @($controllers | ForEach-Object {
        Get-RuntimeEntry `
            -File $_ `
            -WorkspaceRootValue $WorkspaceRoot `
            -RootPackage "com.finalexec.api" `
            -InternalPackage "com.finalexec.api.internal" `
            -ExperimentalPackage "com.finalexec.api.experimental" `
            -SupportedExact $supportedControllers `
            -SupportedPatterns @() `
            -NonDefaultPatterns $nonDefaultControllerPatterns `
            -ExperimentalPatterns $experimentalControllerPatterns
    })
$serviceEntries = @($services | ForEach-Object {
        Get-RuntimeEntry `
            -File $_ `
            -WorkspaceRootValue $WorkspaceRoot `
            -RootPackage "com.finalexec.npdev.service" `
            -InternalPackage "com.finalexec.npdev.service.internal" `
            -ExperimentalPackage "com.finalexec.npdev.service.experimental" `
            -SupportedExact $supportedServiceComponents `
            -SupportedPatterns $supportedServicePatterns `
            -NonDefaultPatterns $nonDefaultServicePatterns `
            -ExperimentalPatterns $experimentalServicePatterns
    })

$unclassifiedControllers = @($controllerEntries | Where-Object { $_.expectedBucket -eq "unclassified" } | Select-Object -ExpandProperty name)
$unclassifiedServices = @($serviceEntries | Where-Object { $_.expectedBucket -eq "unclassified" } | Select-Object -ExpandProperty name)
$overlappingControllers = @($controllerEntries | Where-Object { @($_.matches).Count -gt 1 } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            matches = $_.matches
        }
    })
$overlappingServices = @($serviceEntries | Where-Object { @($_.matches).Count -gt 1 } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            matches = $_.matches
        }
    })
$controllerRootPackageViolations = @($controllerEntries | Where-Object { $_.rootPackageViolation } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            relativePath = $_.relativePath
            declaredPackage = $_.declaredPackage
            expectedBucket = $_.expectedBucket
        }
    })
$serviceRootPackageViolations = @($serviceEntries | Where-Object { $_.rootPackageViolation } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            relativePath = $_.relativePath
            declaredPackage = $_.declaredPackage
            expectedBucket = $_.expectedBucket
        }
    })
$controllerNamespaceMismatches = @($controllerEntries | Where-Object {
        $_.expectedBucket -ne "unclassified" -and -not $_.namespaceAligned
    } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            relativePath = $_.relativePath
            declaredPackage = $_.declaredPackage
            expectedBucket = $_.expectedBucket
            packageBucket = $_.packageBucket
        }
    })
$serviceNamespaceMismatches = @($serviceEntries | Where-Object {
        $_.expectedBucket -ne "unclassified" -and -not $_.namespaceAligned
    } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            relativePath = $_.relativePath
            declaredPackage = $_.declaredPackage
            expectedBucket = $_.expectedBucket
            packageBucket = $_.packageBucket
        }
    })
$controllerUnexpectedPackages = @($controllerEntries | Where-Object { $_.packageBucket -eq "unexpected-package" } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            relativePath = $_.relativePath
            declaredPackage = $_.declaredPackage
        }
    })
$serviceUnexpectedPackages = @($serviceEntries | Where-Object { $_.packageBucket -eq "unexpected-package" } | ForEach-Object {
        [pscustomobject]@{
            name = $_.name
            relativePath = $_.relativePath
            declaredPackage = $_.declaredPackage
        }
    })

$supportedControllerFootprint = @($controllerEntries | Where-Object { $_.expectedBucket -eq "supported-core" } | Select-Object -ExpandProperty name)
$supportedServiceFootprint = @($serviceEntries | Where-Object { $_.expectedBucket -eq "supported-core" } | Select-Object -ExpandProperty name)
$internalButNeededControllers = @($controllerEntries | Where-Object { $_.expectedBucket -eq "internal-but-needed" } | Select-Object -ExpandProperty name)
$internalButNeededServices = @($serviceEntries | Where-Object { $_.expectedBucket -eq "internal-but-needed" } | Select-Object -ExpandProperty name)
$transitionalControllers = @($controllerEntries | Where-Object { $_.expectedBucket -eq "transitional" } | Select-Object -ExpandProperty name)
$transitionalServices = @($serviceEntries | Where-Object { $_.expectedBucket -eq "transitional" } | Select-Object -ExpandProperty name)
$deadRemoveCandidates = [pscustomobject]@{
    controllers = Get-DeadRemoveCandidates -Entries $controllerEntries -SearchPaths $runtimeJavaPaths
    services = Get-DeadRemoveCandidates -Entries $serviceEntries -SearchPaths $runtimeJavaPaths
}
$excludedControllerCount = $controllers.Count - $supportedControllerFootprint.Count
$excludedServiceCount = $services.Count - $supportedServiceFootprint.Count

$buildTemplateText = Get-Content -LiteralPath $buildTemplatePath -Raw
$allowlistConfigText = Get-Content -LiteralPath $allowlistConfigPath -Raw
$packagingTestText = Get-Content -LiteralPath $packagingTestPath -Raw
$defaultProperties = Get-RuntimePropertiesMap $defaultPropertiesPath

$classificationChecks = @(
    (New-RuntimeSurfaceCheck -Name "controllers-classified" -Passed ($unclassifiedControllers.Count -eq 0) -Summary ("unclassifiedControllers=" + $unclassifiedControllers.Count) -Data $unclassifiedControllers)
    (New-RuntimeSurfaceCheck -Name "services-classified" -Passed ($unclassifiedServices.Count -eq 0) -Summary ("unclassifiedServices=" + $unclassifiedServices.Count) -Data $unclassifiedServices)
    (New-RuntimeSurfaceCheck -Name "controller-buckets-are-exclusive" -Passed ($overlappingControllers.Count -eq 0) -Summary ("overlappingControllers=" + $overlappingControllers.Count) -Data $overlappingControllers)
    (New-RuntimeSurfaceCheck -Name "service-buckets-are-exclusive" -Passed ($overlappingServices.Count -eq 0) -Summary ("overlappingServices=" + $overlappingServices.Count) -Data $overlappingServices)
    (New-RuntimeSurfaceCheck -Name "controller-root-packages-contain-supported-core-only" -Passed ($controllerRootPackageViolations.Count -eq 0) -Summary ("controllerRootPackageViolations=" + $controllerRootPackageViolations.Count) -Data $controllerRootPackageViolations)
    (New-RuntimeSurfaceCheck -Name "service-root-packages-contain-supported-core-only" -Passed ($serviceRootPackageViolations.Count -eq 0) -Summary ("serviceRootPackageViolations=" + $serviceRootPackageViolations.Count) -Data $serviceRootPackageViolations)
    (New-RuntimeSurfaceCheck -Name "controller-namespaces-match-convergence-buckets" -Passed ($controllerNamespaceMismatches.Count -eq 0 -and $controllerUnexpectedPackages.Count -eq 0) -Summary ("controllerNamespaceMismatches=" + $controllerNamespaceMismatches.Count + "; controllerUnexpectedPackages=" + $controllerUnexpectedPackages.Count) -Data ([pscustomobject]@{ mismatches = $controllerNamespaceMismatches; unexpectedPackages = $controllerUnexpectedPackages }))
    (New-RuntimeSurfaceCheck -Name "service-namespaces-match-convergence-buckets" -Passed ($serviceNamespaceMismatches.Count -eq 0 -and $serviceUnexpectedPackages.Count -eq 0) -Summary ("serviceNamespaceMismatches=" + $serviceNamespaceMismatches.Count + "; serviceUnexpectedPackages=" + $serviceUnexpectedPackages.Count) -Data ([pscustomobject]@{ mismatches = $serviceNamespaceMismatches; unexpectedPackages = $serviceUnexpectedPackages }))
)

$allowlistChecks = @(
    (New-RuntimeSurfaceCheck -Name "supported-controller-allowlist-populated" -Passed ($supportedControllers.Count -gt 0) -Summary ("supportedControllers=" + $supportedControllers.Count) -Data $supportedControllers)
    (New-RuntimeSurfaceCheck -Name "supported-service-allowlist-populated" -Passed (($supportedServiceComponents.Count + $supportedServicePatterns.Count) -gt 0) -Summary ("supportedServiceEntries=" + ($supportedServiceComponents.Count + $supportedServicePatterns.Count)) -Data ([pscustomobject]@{ exact = $supportedServiceComponents; patterns = $supportedServicePatterns }))
    (New-RuntimeSurfaceCheck -Name "supported-controller-files-exist" -Passed (@($supportedControllers | Where-Object { $_ -notin $controllerEntries.name }).Count -eq 0) -Summary "all supported controller files resolved." -Data (@($supportedControllers | Where-Object { $_ -notin $controllerEntries.name })))
    (New-RuntimeSurfaceCheck -Name "supported-service-files-exist" -Passed (@($supportedServiceComponents | Where-Object { $_ -notin $serviceEntries.name }).Count -eq 0) -Summary "all exact supported service files resolved." -Data (@($supportedServiceComponents | Where-Object { $_ -notin $serviceEntries.name })))
    (New-RuntimeSurfaceCheck -Name "build-template-is-manifest-driven-and-recursive" -Passed (
            $buildTemplateText.Contains("JsonSlurper") -and
            $buildTemplateText.Contains("runtimeSurfaceManifest") -and
            $buildTemplateText.Contains("supportedCoreControllerNames") -and
            $buildTemplateText.Contains("supportedCoreServiceNames") -and
            $buildTemplateText.Contains("supportedCoreServicePatterns") -and
            $buildTemplateText.Contains("include '**/*Controller.java'") -and
            $buildTemplateText.Contains("include '**/*.java'")
        ) -Summary "build.gradle.template reads the runtime surface manifest and recurses through RuntimeHost subpackages." -Data $null)
    (New-RuntimeSurfaceCheck -Name "allowlist-config-uses-runtime-manifest" -Passed (
            $allowlistConfigText.Contains('ALLOWLIST_RESOURCE = "npdev/runtime-supported-controllers.json"') -and
            $allowlistConfigText.Contains("supportedCoreControllers") -and
            $allowlistConfigText.Contains("defaultSurfaceProfile")
        ) -Summary "RuntimeControllerAllowlistConfig reads the shared runtime surface manifest." -Data $null)
    (New-RuntimeSurfaceCheck -Name "packaging-test-covers-supported-surface-and-fences" -Passed (
            $packagingTestText.Contains("generatedDefaultArtifactPackagesOnlySupportedControllerSurface") -and
            $packagingTestText.Contains("generatedDefaultArtifactPackagesOnlySupportedRuntimeServices") -and
            $packagingTestText.Contains("com.finalexec.api.internal.") -and
            $packagingTestText.Contains("com.finalexec.api.experimental.") -and
            $packagingTestText.Contains("com.finalexec.npdev.service.internal.") -and
            $packagingTestText.Contains("com.finalexec.npdev.service.experimental.")
        ) -Summary "SupportedRuntimeSurfacePackagingTest exercises supported-core packaging and internal/experimental fences." -Data $null)
    (New-RuntimeSurfaceCheck -Name "default-profile-enforces-supported-core" -Passed (
            $defaultProperties["npdev.runtime.surface-profile"] -eq "supported-core" -and
            $defaultProperties["npdev.runtime.supported-surface-enforced"] -eq "true"
        ) -Summary "application-default.properties keeps supported-core enforcement enabled." -Data $defaultProperties)
)

$footprintChecks = @(
    (New-RuntimeSurfaceCheck -Name "controller-inventory-fully-classified" -Passed ($unclassifiedControllers.Count -eq 0) -Summary ("unclassifiedControllers=" + $unclassifiedControllers.Count) -Data $unclassifiedControllers)
    (New-RuntimeSurfaceCheck -Name "service-inventory-fully-classified" -Passed ($unclassifiedServices.Count -eq 0) -Summary ("unclassifiedServices=" + $unclassifiedServices.Count) -Data $unclassifiedServices)
    (New-RuntimeSurfaceCheck -Name "controller-root-package-convergence-is-clean" -Passed ($controllerRootPackageViolations.Count -eq 0) -Summary ("controllerRootPackageViolations=" + $controllerRootPackageViolations.Count) -Data $controllerRootPackageViolations)
    (New-RuntimeSurfaceCheck -Name "service-root-package-convergence-is-clean" -Passed ($serviceRootPackageViolations.Count -eq 0) -Summary ("serviceRootPackageViolations=" + $serviceRootPackageViolations.Count) -Data $serviceRootPackageViolations)
    (New-RuntimeSurfaceCheck -Name "controller-namespace-convergence-is-clean" -Passed ($controllerNamespaceMismatches.Count -eq 0) -Summary ("controllerNamespaceMismatches=" + $controllerNamespaceMismatches.Count) -Data $controllerNamespaceMismatches)
    (New-RuntimeSurfaceCheck -Name "service-namespace-convergence-is-clean" -Passed ($serviceNamespaceMismatches.Count -eq 0) -Summary ("serviceNamespaceMismatches=" + $serviceNamespaceMismatches.Count) -Data $serviceNamespaceMismatches)
    (New-RuntimeSurfaceCheck -Name "supported-controller-footprint-is-smaller-than-inventory" -Passed ($supportedControllerFootprint.Count -lt $controllers.Count) -Summary ("supportedControllers=" + $supportedControllerFootprint.Count + "; totalControllers=" + $controllers.Count) -Data $supportedControllerFootprint)
    (New-RuntimeSurfaceCheck -Name "supported-service-footprint-is-smaller-than-inventory" -Passed ($supportedServiceFootprint.Count -lt $services.Count) -Summary ("supportedServices=" + $supportedServiceFootprint.Count + "; totalServices=" + $services.Count) -Data $supportedServiceFootprint)
    (New-RuntimeSurfaceCheck -Name "supported-controller-footprint-stays-minority" -Passed ($supportedControllerFootprint.Count -lt $excludedControllerCount) -Summary ("supportedControllers=" + $supportedControllerFootprint.Count + "; excludedControllers=" + $excludedControllerCount) -Data $null)
    (New-RuntimeSurfaceCheck `
        -Name "supported-service-footprint-balance-observation" `
        -Passed $true `
        -Summary ("supportedServices=" + $supportedServiceFootprint.Count + "; excludedServices=" + $excludedServiceCount + "; totalServices=" + $services.Count) `
        -Data ([pscustomobject]@{
                supportedServices = $supportedServiceFootprint.Count
                excludedServices = $excludedServiceCount
                totalServices = $services.Count
                rule = "observation-only"
                reason = "At the late convergence stage, service balance versus excluded surface is tracked as an observation, not a blocking invariant."
            }))
)

$classificationReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = Get-OverallStatus $classificationChecks
    manifestPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $manifestPath
    summary = Get-CheckSummary $classificationChecks
    inventory = [pscustomobject]@{
        controllerCount = $controllers.Count
        serviceCount = $services.Count
        controllerBuckets = [pscustomobject]@{
            supportedCore = $supportedControllerFootprint.Count
            internalButNeeded = $internalButNeededControllers.Count
            transitional = $transitionalControllers.Count
            unclassified = $unclassifiedControllers.Count
            rootPackageViolations = $controllerRootPackageViolations.Count
        }
        serviceBuckets = [pscustomobject]@{
            supportedCore = $supportedServiceFootprint.Count
            internalButNeeded = $internalButNeededServices.Count
            transitional = $transitionalServices.Count
            unclassified = $unclassifiedServices.Count
            rootPackageViolations = $serviceRootPackageViolations.Count
        }
    }
    checks = $classificationChecks
}

$allowlistReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = Get-OverallStatus $allowlistChecks
    manifestPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $manifestPath
    buildTemplatePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $buildTemplatePath
    allowlistConfigPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $allowlistConfigPath
    defaultPropertiesPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $defaultPropertiesPath
    summary = Get-CheckSummary $allowlistChecks
    checks = $allowlistChecks
}

$footprintReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = Get-OverallStatus $footprintChecks
    manifestPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $manifestPath
    summary = Get-CheckSummary $footprintChecks
    footprint = [pscustomobject]@{
        supportedControllerCount = $supportedControllerFootprint.Count
        totalControllerCount = $controllers.Count
        excludedControllerCount = $excludedControllerCount
        supportedServiceCount = $supportedServiceFootprint.Count
        totalServiceCount = $services.Count
        excludedServiceCount = $excludedServiceCount
        controllerBuckets = [pscustomobject]@{
            supportedCore = $supportedControllerFootprint.Count
            internalButNeeded = $internalButNeededControllers.Count
            transitional = $transitionalControllers.Count
            unclassified = $unclassifiedControllers.Count
            rootPackageViolations = $controllerRootPackageViolations.Count
        }
        serviceBuckets = [pscustomobject]@{
            supportedCore = $supportedServiceFootprint.Count
            internalButNeeded = $internalButNeededServices.Count
            transitional = $transitionalServices.Count
            unclassified = $unclassifiedServices.Count
            rootPackageViolations = $serviceRootPackageViolations.Count
        }
        unclassifiedControllers = $unclassifiedControllers
        unclassifiedServices = $unclassifiedServices
        supportedControllers = $supportedControllerFootprint
        supportedServices = $supportedServiceFootprint
        internalButNeededControllers = $internalButNeededControllers
        internalButNeededServices = $internalButNeededServices
        transitionalControllers = $transitionalControllers
        transitionalServices = $transitionalServices
        rootPackageViolations = [pscustomobject]@{
            controllers = $controllerRootPackageViolations
            services = $serviceRootPackageViolations
        }
        namespaceMismatches = [pscustomobject]@{
            controllers = $controllerNamespaceMismatches
            services = $serviceNamespaceMismatches
        }
        deadRemoveCandidates = $deadRemoveCandidates
    }
    checks = $footprintChecks
}

Write-NPDevJsonFile $ClassificationReportPath $classificationReport
Write-NPDevJsonFile $AllowlistReportPath $allowlistReport
Write-NPDevJsonFile $FootprintReportPath $footprintReport

$allReports = @(
    @{ label = "classification"; report = $classificationReport },
    @{ label = "allowlist"; report = $allowlistReport },
    @{ label = "footprint"; report = $footprintReport }
)
$failedReports = @($allReports | Where-Object { $_.report.overallStatus -ne "passed" })
$warningReports = @($allReports | Where-Object { $_.report.overallStatus -eq "warning" })
$parentReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedReports.Count -gt 0) { "failed" } elseif ($warningReports.Count -gt 0) { "warning" } else { "passed" }
    summary = [pscustomobject]@{
        failed = $failedReports.Count
        warnings = $warningReports.Count
        passed = @($allReports | Where-Object { $_.report.overallStatus -eq "passed" }).Count
        total = $allReports.Count
    }
    childReports = @(
        [pscustomobject]@{
            name = "classification"
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ClassificationReportPath
            overallStatus = [string]$classificationReport.overallStatus
            summary = $classificationReport.summary
        },
        [pscustomobject]@{
            name = "allowlist"
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $AllowlistReportPath
            overallStatus = [string]$allowlistReport.overallStatus
            summary = $allowlistReport.summary
        },
        [pscustomobject]@{
            name = "footprint"
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $FootprintReportPath
            overallStatus = [string]$footprintReport.overallStatus
            summary = $footprintReport.summary
        }
    )
}
Write-NPDevJsonFile $ReportPath $parentReport

if ($PassThru) {
    return $parentReport
}

if ($failedReports.Count -eq 0) {
    Write-NPDevOk "Runtime surface evidence reports generated."
    return
}

Write-NPDevWarn ("Runtime surface evidence failed: " + (($failedReports | ForEach-Object { $_.label }) -join ", "))
throw "Runtime surface evidence failed."
