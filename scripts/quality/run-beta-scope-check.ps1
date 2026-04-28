[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "beta-scope"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-scope-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$policyPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\beta-scope.json"
$cliTestRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator\tools\npdev-cli\src\test\java"
Ensure-NPDevFile $policyPath "Beta scope policy"
Ensure-NPDevDirectory $cliTestRoot "NPDev CLI test root"

function Get-DisabledCliTests([string]$RootPath, [string]$WorkspaceRootValue) {
    $disabledTests = [System.Collections.Generic.List[object]]::new()

    foreach ($file in Get-ChildItem -LiteralPath $RootPath -Recurse -Filter "*.java" -File | Sort-Object FullName) {
        $lines = Get-Content -LiteralPath $file.FullName
        $packageName = $null
        $className = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
        for ($index = 0; $index -lt $lines.Count; $index++) {
            $line = [string]$lines[$index]
            if ($line -match '^\s*package\s+([a-zA-Z0-9_.]+)\s*;') {
                $packageName = $matches[1]
            }

            if ($line -notmatch '@Disabled\("([^"]+)"\)') {
                continue
            }

            $reason = $matches[1]
            $methodName = $null
            for ($lookAhead = $index + 1; $lookAhead -lt $lines.Count; $lookAhead++) {
                $candidate = [string]$lines[$lookAhead]
                if ($candidate -match 'void\s+([A-Za-z0-9_]+)\s*\(') {
                    $methodName = $matches[1]
                    break
                }
            }

            if ([string]::IsNullOrWhiteSpace($methodName)) {
                continue
            }

            $qualifiedName = if ([string]::IsNullOrWhiteSpace($packageName)) {
                $className + "#" + $methodName
            }
            else {
                $packageName + "." + $className + "#" + $methodName
            }

            [void]$disabledTests.Add([pscustomobject]@{
                    test = $qualifiedName
                    file = Get-NPDevWorkspaceRelativePath $WorkspaceRootValue $file.FullName
                    reason = $reason
                })
        }
    }

    return @($disabledTests)
}

$policy = Get-Content -LiteralPath $policyPath -Raw | ConvertFrom-Json
$includedSurfaces = [object[]]@($policy.betaRelease.includedSurfaces | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$excludedSurfaces = [object[]]@(if ($policy.betaRelease.PSObject.Properties.Name -contains "excludedSurfaces") {
        @($policy.betaRelease.excludedSurfaces)
    }
    else {
        @()
    })
$betaScopeTransitions = [object[]]@(if ($policy.betaRelease.PSObject.Properties.Name -contains "betaScopeTransitions") {
        @($policy.betaRelease.betaScopeTransitions)
    }
    else {
        @()
    })
$excludedCliScope = @($excludedSurfaces | Where-Object { $_.id -eq "npdev-cli-runtime" }) | Select-Object -First 1
$transitionCliScope = @($betaScopeTransitions | Where-Object { $_.id -eq "npdev-cli-runtime" }) | Select-Object -First 1
$cliClosureScope = if ($null -ne $transitionCliScope) { $transitionCliScope } else { $excludedCliScope }
$disabledCliTests = [object[]]@(Get-DisabledCliTests -RootPath $cliTestRoot -WorkspaceRootValue $WorkspaceRoot)
$cliScopeIncludedInBeta = "npdev-cli-runtime" -in $includedSurfaces
$cliScopeExcluded = $null -ne $excludedCliScope
$cliScopeStatus = if ($cliScopeExcluded) {
    [string]$excludedCliScope.status
}
elseif ($cliScopeIncludedInBeta) {
    "beta-covered"
}
else {
    $null
}
$cliTransitionStatus = if ($null -eq $transitionCliScope) { $null } else { [string]$transitionCliScope.status }
$declaredDisabledTests = [object[]]@(if ($null -eq $cliClosureScope) {
    @()
}
else {
    @($cliClosureScope.disabledTests | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
})
$closureItems = [object[]]@(if ($null -eq $cliClosureScope -or $null -eq $cliClosureScope.closureItems) {
    @()
}
else {
    @($cliClosureScope.closureItems | ForEach-Object {
            [pscustomobject]@{
                priority = if ($null -eq $_.priority) { $null } else { [int]$_.priority }
                test = [string]$_.test
                status = [string]$_.status
                owner = [string]$_.owner
                implementationNote = [string]$_.implementationNote
            }
        })
})

$missingDeclaration = [object[]]@($disabledCliTests | Where-Object { $_.test -notin $declaredDisabledTests } | Select-Object -ExpandProperty test)
$staleDeclarations = [object[]]@($declaredDisabledTests | Where-Object { $_ -notin @($disabledCliTests | Select-Object -ExpandProperty test) })
$trackedClosureTests = [object[]]@($closureItems | Select-Object -ExpandProperty test)
$duplicateClosureTests = [object[]]@($trackedClosureTests | Group-Object | Where-Object { $_.Count -gt 1 } | Select-Object -ExpandProperty Name)
$invalidClosureStatuses = [object[]]@($closureItems | Where-Object { [string]$_.status -notin @("re-enabled-this-cycle", "still-excluded") } | Select-Object -ExpandProperty test)
$undocumentedClosureItems = [object[]]@($closureItems | Where-Object {
        [string]::IsNullOrWhiteSpace([string]$_.test) -or
        [string]::IsNullOrWhiteSpace([string]$_.status) -or
        [string]::IsNullOrWhiteSpace([string]$_.owner) -or
        [string]::IsNullOrWhiteSpace([string]$_.implementationNote)
    } | Select-Object -ExpandProperty test)
$declaredWithoutClosureItem = [object[]]@($declaredDisabledTests | Where-Object { $_ -notin $trackedClosureTests })
$disabledWithoutClosureItem = [object[]]@($disabledCliTests | Where-Object { $_.test -notin $trackedClosureTests } | Select-Object -ExpandProperty test)
$reEnabledThisCycle = [object[]]@($closureItems | Where-Object { [string]$_.status -eq "re-enabled-this-cycle" })
$stillExcluded = [object[]]@($closureItems | Where-Object { [string]$_.status -eq "still-excluded" })
$reEnabledStillDisabled = [object[]]@($reEnabledThisCycle | Where-Object {
        $_.test -in $declaredDisabledTests -or $_.test -in @($disabledCliTests | Select-Object -ExpandProperty test)
    } | Select-Object -ExpandProperty test)
$rationaleOwners = [object[]]@($stillExcluded | Select-Object -ExpandProperty owner -Unique | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$currentStateListsAreClean = -not ($cliScopeIncludedInBeta -and $cliScopeExcluded)
$cliScopeDeclarationPassed = $currentStateListsAreClean -and (
    (
        $cliScopeIncludedInBeta -and
        -not $cliScopeExcluded -and
        $null -ne $transitionCliScope -and
        $cliTransitionStatus -eq "beta-covered"
    ) -or
    (
        -not $cliScopeIncludedInBeta -and
        $cliScopeExcluded -and
        $cliScopeStatus -eq "out-of-beta-scope"
    )
)
$cliScopeStatusConsistent = switch ($cliScopeStatus) {
    "out-of-beta-scope" {
        ($declaredDisabledTests.Count -gt 0) -and
        ($stillExcluded.Count -gt 0) -and
        (-not $cliScopeIncludedInBeta) -and
        $cliScopeExcluded
    }
    "beta-covered" {
        ($declaredDisabledTests.Count -eq 0) -and
        ($disabledCliTests.Count -eq 0) -and
        ($stillExcluded.Count -eq 0) -and
        $cliScopeIncludedInBeta -and
        (-not $cliScopeExcluded) -and
        $cliTransitionStatus -eq "beta-covered"
    }
    default {
        $false
    }
}

$checks = @(
    (New-NPDevCheckResult "cli-scope-current-state-clean" $(if ($cliScopeDeclarationPassed) { "passed" } else { "failed" }) "NPDev CLI runtime beta scope is represented in exactly one current-state list." ([pscustomobject]@{
                scopeStatus = $cliScopeStatus
                includedInBeta = $cliScopeIncludedInBeta
                excludedInCurrentState = $cliScopeExcluded
                transitionStatus = $cliTransitionStatus
            })),
    (New-NPDevCheckResult "cli-scope-status-consistent" $(if ($cliScopeStatusConsistent) { "passed" } else { "failed" }) "NPDev CLI runtime beta scope status matches current-state lists and transition evidence." ([pscustomobject]@{
                scopeStatus = $cliScopeStatus
                includedInBeta = $cliScopeIncludedInBeta
                excludedInCurrentState = $cliScopeExcluded
                transitionStatus = $cliTransitionStatus
                declaredDisabledTests = $declaredDisabledTests.Count
                actualDisabledTests = $disabledCliTests.Count
                stillExcluded = $stillExcluded.Count
            })),
    (New-NPDevCheckResult "cli-disabled-tests-accounted-for" $(if ($missingDeclaration.Count -eq 0 -and $staleDeclarations.Count -eq 0) { "passed" } else { "failed" }) "CLI disabled test list matches the declared beta-scope exclusion." ([pscustomobject]@{
                missingDeclaration = $missingDeclaration
                staleDeclarations = $staleDeclarations
            })),
    (New-NPDevCheckResult "cli-closure-items-documented" $(if (
                $closureItems.Count -gt 0 -and
                $duplicateClosureTests.Count -eq 0 -and
                $invalidClosureStatuses.Count -eq 0 -and
                $undocumentedClosureItems.Count -eq 0 -and
                $declaredWithoutClosureItem.Count -eq 0 -and
                $disabledWithoutClosureItem.Count -eq 0
            ) { "passed" } else { "failed" }) "CLI beta-scope closure items are tracked with owners and implementation notes." ([pscustomobject]@{
                trackedClosureTests = $trackedClosureTests
                duplicateClosureTests = $duplicateClosureTests
                invalidClosureStatuses = $invalidClosureStatuses
                undocumentedClosureItems = $undocumentedClosureItems
                declaredWithoutClosureItem = $declaredWithoutClosureItem
                disabledWithoutClosureItem = $disabledWithoutClosureItem
            })),
    (New-NPDevCheckResult "cli-reenabled-tests-active" $(if ($reEnabledStillDisabled.Count -eq 0) { "passed" } else { "failed" }) "Re-enabled CLI tests are no longer declared as disabled." ([pscustomobject]@{
                reEnabledThisCycle = @($reEnabledThisCycle | Select-Object -ExpandProperty test)
                stillExcluded = @($stillExcluded | Select-Object -ExpandProperty test)
                reEnabledStillDisabled = $reEnabledStillDisabled
            }))
)

$failedChecks = [object[]]@($checks | Where-Object { $_.status -eq "failed" })
$overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    policyPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $policyPath
    includedSurfaces = $includedSurfaces
    excludedSurfaces = $excludedSurfaces
    excludedSurface = $excludedCliScope
    closureTransition = $transitionCliScope
    disabledCliTests = $disabledCliTests
    closureItems = $closureItems
    reEnabledThisCycle = $reEnabledThisCycle
    stillExcluded = $stillExcluded
    rationaleOwners = $rationaleOwners
    summary = [pscustomobject]@{
        scopeStatus = $cliScopeStatus
        includedInBeta = $cliScopeIncludedInBeta
        excludedInCurrentState = $cliScopeExcluded
        transitionStatus = $cliTransitionStatus
        disabledCliTests = $disabledCliTests.Count
        declaredDisabledTests = $declaredDisabledTests.Count
        closureItems = $closureItems.Count
        reEnabledThisCycle = $reEnabledThisCycle.Count
        stillExcluded = $stillExcluded.Count
        failedChecks = $failedChecks.Count
    }
    checks = $checks
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk "Beta scope declaration, closure tracking, and CLI disabled coverage match."
    return
}

Write-NPDevWarn "Beta scope declaration, closure tracking, or CLI disabled coverage is inconsistent."
throw "Beta scope check failed."
