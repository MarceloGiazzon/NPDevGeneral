<#
.SYNOPSIS
    Script topology gate (docs/INVOCATION_TOPOLOGY_PLAN.md T2): every script under scripts/ must
    declare BOTH a classification (what it is) and an invocation (what invokes it), and both
    declarations must match reality.

.DESCRIPTION
    Classification (scripts/policy/script-inventory-policy.json, pattern-based, unchanged from the
    original script-inventory design): canonical / helper / deprecated / one-time-repair /
    outside-repo-only.

    Invocation (scripts/policy/script-invocation-declarations.json, per-file, NEW): declares what is
    supposed to invoke this script, then this gate CHECKS that declaration against reality --
    - ci-gate: basename or stem must appear in some .github/workflows/*.yml, OR in the text of a
      scripts/quality/run-*.ps1 gate runner that is ITSELF named in a workflow (one-hop: gate runner
      -> workflow). A run-*.ps1 that is not itself workflow-reachable does not transitively make
      anything else ci-gate either -- that would just be two orphans citing each other.
    - manual-runbook: basename or stem must appear in at least one *.md file repo-wide (excluding
      .git/.gradle/build/node_modules/npdev-generated/dist/target/out).
    - orchestrated: basename or stem must appear in some OTHER script under scripts/.
    - retired: must declare a non-empty `reason` and `date`.
    A script with no invocation declaration at all fails, same as an unclassified script always has.

    This is the same "declare it, then check the declaration against reality" shape as
    scripts/quality/check-test-task-coverage.py, generalized from Gradle Test tasks to every
    executable script -- deliberately a cheap, static, text-level check (same accepted limitation
    that script's own docstring names), not a build-graph evaluation.

    Was itself an orphan until this task (docs/INVOCATION_TOPOLOGY_PLAN.md's own finding): nothing
    anywhere invoked run-script-inventory-check.ps1. Now wired into run-ai-knowledge-gate.ps1 step
    [17/17], and documented in CONTRIBUTING.md.

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-script-inventory-check.ps1
    pwsh -NoProfile -File scripts/quality/run-script-inventory-check.ps1 -Calibrate
#>
param(
    [string]$WorkspaceRoot = ".",
    [string]$PolicyPath = "scripts/policy/script-inventory-policy.json",
    [string]$InvocationDeclarationsPath = "scripts/policy/script-invocation-declarations.json",
    [string]$ReportPath = "scripts/reports/out/script-inventory-report.json",
    [string]$SchemaPath = "schemas/ai/script-inventory-report.schema.json",
    [string]$RunId = "",
    [switch]$Calibrate
)

$ErrorActionPreference = "Stop"

function Resolve-WorkspacePath {
    param([string]$Root, [string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
}

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathValue)
    if (-not $resolvedRoot.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $resolvedRoot += [System.IO.Path]::DirectorySeparatorChar
    }
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length) -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Test-AnyPattern {
    param([string]$PathValue, [object[]]$Patterns)
    foreach ($pattern in @($Patterns)) {
        if ($PathValue -match [string]$pattern) {
            return $true
        }
    }
    return $false
}

function Get-ScriptClassification {
    param([string]$PathValue, [object[]]$Rules)
    foreach ($rule in @($Rules)) {
        if ($PathValue -match [string]$rule.pattern) {
            return [pscustomobject]@{
                classification = [string]$rule.classification
                rulePattern = [string]$rule.pattern
            }
        }
    }
    return [pscustomobject]@{
        classification = "unknown"
        rulePattern = ""
    }
}

function Test-NameIn {
    param([string]$Basename, [string]$Stem, [string]$Text)
    if ($Text.Contains($Basename)) { return $true }
    if ($Stem.Length -gt 6 -and $Text.Contains($Stem)) { return $true }
    return $false
}

$EXCLUDED_MD_DIR_PARTS = @(".git", ".gradle", "build", "node_modules", "npdev-generated", "dist", "target", "out")

function Get-InvocationEvidence {
    param(
        [string]$WorkspaceRootPath,
        [System.IO.FileInfo[]]$AllScriptFiles
    )
    $workflowDir = Join-Path $WorkspaceRootPath ".github\workflows"
    $workflowText = ""
    if (Test-Path -LiteralPath $workflowDir) {
        foreach ($f in Get-ChildItem -LiteralPath $workflowDir -Filter "*.yml" -File -ErrorAction SilentlyContinue) {
            $workflowText += (Get-Content -Raw -LiteralPath $f.FullName) + "`n"
        }
    }

    $qualityDir = Join-Path $WorkspaceRootPath "scripts\quality"
    $gateRunnerFiles = @(Get-ChildItem -LiteralPath $qualityDir -Filter "run-*.ps1" -File -ErrorAction SilentlyContinue)
    $reachableRunners = @($gateRunnerFiles | Where-Object { $workflowText.Contains($_.Name) })
    $reachableRunnerTextByName = @{}
    foreach ($f in $reachableRunners) {
        $reachableRunnerTextByName[$f.Name] = Get-Content -Raw -LiteralPath $f.FullName
    }

    $mdFiles = @(Get-ChildItem -LiteralPath $WorkspaceRootPath -Recurse -Force -File -Filter "*.md" -ErrorAction SilentlyContinue |
        Where-Object {
            $relative = Convert-ToRepoPath -Root $WorkspaceRootPath -PathValue $_.FullName
            $segments = @($relative -split "/")
            @($segments | Where-Object { $EXCLUDED_MD_DIR_PARTS -contains $_ }).Count -eq 0
        })
    $docTextByFile = @{}
    foreach ($f in $mdFiles) {
        $docTextByFile[$f.FullName] = Get-Content -Raw -LiteralPath $f.FullName
    }

    $scriptTextByFile = @{}
    foreach ($f in $AllScriptFiles) {
        $scriptTextByFile[$f.FullName] = Get-Content -Raw -LiteralPath $f.FullName
    }

    return [pscustomobject]@{
        WorkflowText = $workflowText
        ReachableRunnerTextByName = $reachableRunnerTextByName
        DocTextByFile = $docTextByFile
        ScriptTextByFile = $scriptTextByFile
    }
}

function Test-InvocationDeclaration {
    param(
        [string]$RelativePath,
        [string]$Basename,
        [string]$FullName,
        [object]$Declaration,
        [object]$Evidence
    )
    if ($null -eq $Declaration) {
        return [pscustomobject]@{ valid = $false; reason = "no invocation declaration in $($InvocationDeclarationsPath)" }
    }
    $invocation = [string]$Declaration.invocation
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($Basename)

    switch ($invocation) {
        "ci-gate" {
            if (Test-NameIn -Basename $Basename -Stem $stem -Text $Evidence.WorkflowText) {
                return [pscustomobject]@{ valid = $true; reason = "named in a workflow" }
            }
            foreach ($runnerName in $Evidence.ReachableRunnerTextByName.Keys) {
                if ($runnerName -eq $Basename) { continue }
                if (Test-NameIn -Basename $Basename -Stem $stem -Text $Evidence.ReachableRunnerTextByName[$runnerName]) {
                    return [pscustomobject]@{ valid = $true; reason = "named in workflow-reachable gate runner $runnerName" }
                }
            }
            return [pscustomobject]@{ valid = $false; reason = "declared ci-gate but not named in any workflow or workflow-reachable gate runner" }
        }
        "manual-runbook" {
            foreach ($docPath in $Evidence.DocTextByFile.Keys) {
                if (Test-NameIn -Basename $Basename -Stem $stem -Text $Evidence.DocTextByFile[$docPath]) {
                    return [pscustomobject]@{ valid = $true; reason = "referenced by at least one doc" }
                }
            }
            return [pscustomobject]@{ valid = $false; reason = "declared manual-runbook but referenced by no *.md file" }
        }
        "orchestrated" {
            foreach ($scriptPath in $Evidence.ScriptTextByFile.Keys) {
                if ($scriptPath -eq $FullName) { continue }
                if (Test-NameIn -Basename $Basename -Stem $stem -Text $Evidence.ScriptTextByFile[$scriptPath]) {
                    return [pscustomobject]@{ valid = $true; reason = "referenced by another script" }
                }
            }
            return [pscustomobject]@{ valid = $false; reason = "declared orchestrated but referenced by no other script" }
        }
        "retired" {
            $reason = [string]$Declaration.reason
            $date = [string]$Declaration.date
            if (-not [string]::IsNullOrWhiteSpace($reason) -and -not [string]::IsNullOrWhiteSpace($date)) {
                return [pscustomobject]@{ valid = $true; reason = "retired with reason + date" }
            }
            return [pscustomobject]@{ valid = $false; reason = "declared retired but missing a non-empty reason and/or date" }
        }
        default {
            return [pscustomobject]@{ valid = $false; reason = "unrecognized invocation value '$invocation'" }
        }
    }
}

function Invoke-InventoryScan {
    param(
        [string]$WorkspaceRootPath,
        [object]$Policy,
        [object]$InvocationDeclarations,
        [switch]$IncludeSyntheticUnknown
    )
    $inventoryRoot = Resolve-WorkspacePath -Root $WorkspaceRootPath -PathValue ([string]$Policy.inventoryRoot)
    $extensions = @($Policy.scriptExtensions | ForEach-Object { ([string]$_).ToLowerInvariant() })
    $allowedClassifications = @($Policy.allowedClassifications | ForEach-Object { [string]$_ })
    $allowedInvocations = @($Policy.allowedInvocations | ForEach-Object { [string]$_ })
    $releaseCriticalAllowed = @($Policy.releaseCriticalAllowedClassifications | ForEach-Object { [string]$_ })

    $scriptFiles = @(Get-ChildItem -LiteralPath $inventoryRoot -Recurse -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $extensions -contains $_.Extension.ToLowerInvariant() } |
        Sort-Object FullName)

    $evidence = Get-InvocationEvidence -WorkspaceRootPath $WorkspaceRootPath -AllScriptFiles $scriptFiles

    $declByPath = @{}
    foreach ($prop in $InvocationDeclarations.declarations.PSObject.Properties) {
        $declByPath[$prop.Name] = $prop.Value
    }

    $scripts = @()
    foreach ($file in $scriptFiles) {
        $relative = Convert-ToRepoPath -Root $WorkspaceRootPath -PathValue $file.FullName
        $classificationResult = Get-ScriptClassification -PathValue $relative -Rules @($Policy.classificationRules)
        $classification = [string]$classificationResult.classification
        $releaseCritical = Test-AnyPattern -PathValue $relative -Patterns @($Policy.releaseCriticalPatterns)
        $validClassification = $allowedClassifications -contains $classification
        $releaseCriticalAllowedClassification = (-not $releaseCritical) -or ($releaseCriticalAllowed -contains $classification)

        $declaration = $declByPath[$relative]
        $invocation = if ($declaration) { [string]$declaration.invocation } else { "unknown" }
        $validInvocationValue = $allowedInvocations -contains $invocation
        $invocationCheck = Test-InvocationDeclaration -RelativePath $relative -Basename $file.Name -FullName $file.FullName -Declaration $declaration -Evidence $evidence

        $scripts += [pscustomobject]@{
            path = $relative
            classification = $classification
            classificationRule = [string]$classificationResult.rulePattern
            releaseCritical = $releaseCritical
            validClassification = $validClassification
            releaseCriticalAllowedClassification = $releaseCriticalAllowedClassification
            invocation = $invocation
            validInvocationValue = $validInvocationValue
            invocationMatchesReality = $invocationCheck.valid
            invocationEvidence = $invocationCheck.reason
            sizeBytes = [int64]$file.Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
        }
    }

    if ($IncludeSyntheticUnknown) {
        $scripts += [pscustomobject]@{
            path = "scripts/<synthetic-uninventoried-script>.ps1"
            classification = "unknown"
            classificationRule = ""
            releaseCritical = $false
            validClassification = $false
            invocation = "unknown"
            validInvocationValue = $false
            invocationMatchesReality = $false
            invocationEvidence = "no invocation declaration in $($InvocationDeclarationsPath)"
            sizeBytes = 0
            sha256 = ""
        }
    }

    return $scripts
}

function Build-Report {
    param([object[]]$Scripts, [object]$Policy, [string]$RunId, [string]$WorkspaceRootPath)
    $allowedClassifications = @($Policy.allowedClassifications | ForEach-Object { [string]$_ })
    $allowedInvocations = @($Policy.allowedInvocations | ForEach-Object { [string]$_ })
    $releaseCriticalAllowed = @($Policy.releaseCriticalAllowedClassifications | ForEach-Object { [string]$_ })

    $unknownScripts = @($Scripts | Where-Object { $_.classification -eq "unknown" -or -not $_.validClassification })
    $releaseCriticalViolations = @($Scripts | Where-Object { $_.releaseCritical -and -not $_.releaseCriticalAllowedClassification })
    $unknownInvocationScripts = @($Scripts | Where-Object { -not $_.validInvocationValue })
    $invocationMismatchScripts = @($Scripts | Where-Object { $_.validInvocationValue -and -not $_.invocationMatchesReality })

    $classificationCounts = [ordered]@{}
    foreach ($classification in @($allowedClassifications + "unknown")) {
        $classificationCounts[$classification] = @($Scripts | Where-Object { $_.classification -eq $classification }).Count
    }
    $invocationCounts = [ordered]@{}
    foreach ($invocation in @($allowedInvocations + "unknown")) {
        $invocationCounts[$invocation] = @($Scripts | Where-Object { $_.invocation -eq $invocation }).Count
    }

    $blockers = @(
        @($unknownScripts | ForEach-Object { "Unclassified script: " + $_.path })
        @($releaseCriticalViolations | ForEach-Object { "Release-critical script has invalid classification: " + $_.path + " (" + $_.classification + ")" })
        @($unknownInvocationScripts | ForEach-Object { "Script with no valid invocation declaration: " + $_.path + " (" + $_.invocationEvidence + ")" })
        @($invocationMismatchScripts | ForEach-Object { "Invocation declaration does not match reality: " + $_.path + " declared '" + $_.invocation + "' but " + $_.invocationEvidence })
    )
    $overallStatus = if ($blockers.Count -eq 0) { "passed" } else { "failed" }

    return [pscustomobject]@{
        schemaVersion = "npdev-script-inventory-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-script-inventory-check.ps1"
        workspaceRoot = $WorkspaceRootPath
        overallStatus = $overallStatus
        policyPath = "scripts/policy/script-inventory-policy.json"
        invocationDeclarationsPath = $InvocationDeclarationsPath
        scriptCount = $Scripts.Count
        classificationCounts = $classificationCounts
        invocationCounts = $invocationCounts
        unknownScripts = @($unknownScripts | ForEach-Object { $_.path })
        releaseCriticalViolations = @($releaseCriticalViolations | ForEach-Object { $_.path })
        unknownInvocationScripts = @($unknownInvocationScripts | ForEach-Object { $_.path })
        invocationMismatchScripts = @($invocationMismatchScripts | ForEach-Object { $_.path })
        releaseCriticalAllowedClassifications = @($releaseCriticalAllowed)
        scripts = @($Scripts)
        blockers = @($blockers)
        doesNotSolve = @(
            "Does not delete or move scripts.",
            "Does not archive deprecated scripts.",
            "Does not make script classification a product-scope expansion.",
            "Invocation-reality checks are text-level (basename/stem presence), not a build-graph or call-graph evaluation."
        )
    }
}

function Invoke-Calibration {
    param([string]$WorkspaceRootPath, [object]$Policy, [object]$InvocationDeclarations)
    $ok = $true

    Write-Host "Calibration -- a script with no invocation declaration must be flagged; the real corpus must not:"

    $syntheticScripts = Invoke-InventoryScan -WorkspaceRootPath $WorkspaceRootPath -Policy $Policy -InvocationDeclarations $InvocationDeclarations -IncludeSyntheticUnknown
    $syntheticReport = Build-Report -Scripts $syntheticScripts -Policy $Policy -RunId "calibrate-synthetic" -WorkspaceRootPath $WorkspaceRootPath
    $syntheticFired = $syntheticReport.blockers.Count -gt 0 -and ($syntheticReport.unknownInvocationScripts -contains "scripts/<synthetic-uninventoried-script>.ps1")
    $pass1 = $syntheticFired -eq $true
    $ok = $ok -and $pass1
    Write-Host "  [$(if ($pass1) { 'PASS' } else { 'FAIL' })] synthetic undeclared script vs. the checker (fired: $syntheticFired, expected: fired)"

    $realScripts = Invoke-InventoryScan -WorkspaceRootPath $WorkspaceRootPath -Policy $Policy -InvocationDeclarations $InvocationDeclarations
    $realReport = Build-Report -Scripts $realScripts -Policy $Policy -RunId "calibrate-real" -WorkspaceRootPath $WorkspaceRootPath
    $realFired = $realReport.overallStatus -ne "passed"
    $pass2 = $realFired -eq $false
    $ok = $ok -and $pass2
    Write-Host "  [$(if ($pass2) { 'PASS' } else { 'FAIL' })] the real, currently-declared corpus vs. the checker (fired: $realFired, expected: silent)"
    if (-not $pass2) {
        $realReport.blockers | ForEach-Object { Write-Host "           $_" }
    }

    if (-not $ok) {
        Write-Host "`nFAIL: calibration did not reproduce the expected RED/GREEN pair." -ForegroundColor Red
        return 1
    }
    Write-Host "`nOK: both controls behave correctly."
    return 0
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "script-inventory-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $policyFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $PolicyPath
    $policy = Get-Content -Raw -LiteralPath $policyFullPath | ConvertFrom-Json

    $declPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $InvocationDeclarationsPath
    $invocationDeclarations = Get-Content -Raw -LiteralPath $declPath | ConvertFrom-Json

    if ($Calibrate) {
        exit (Invoke-Calibration -WorkspaceRootPath $workspaceRootPath -Policy $policy -InvocationDeclarations $invocationDeclarations)
    }

    $scripts = Invoke-InventoryScan -WorkspaceRootPath $workspaceRootPath -Policy $policy -InvocationDeclarations $invocationDeclarations
    $report = Build-Report -Scripts $scripts -Policy $policy -RunId $RunId -WorkspaceRootPath $workspaceRootPath

    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    $schemaValidationPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/tmp/script-inventory-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error "Script inventory report failed schema validation."
        exit 1
    }

    if ($report.overallStatus -eq "passed") {
        Write-Host ("Script inventory check passed ({0} scripts, {1} classified+invocation-declared). Report: {2}" -f $report.scriptCount, $report.scriptCount, $ReportPath)
        exit 0
    }

    Write-Host "Script inventory check FAILED:" -ForegroundColor Red
    $report.blockers | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Error ("Script inventory check failed. Report: " + $ReportPath)
    exit 1
}
finally {
    Pop-Location
}
