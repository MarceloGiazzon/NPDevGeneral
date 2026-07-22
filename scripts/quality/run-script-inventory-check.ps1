param(
    [string]$WorkspaceRoot = ".",
    [string]$PolicyPath = "scripts/policy/script-inventory-policy.json",
    [string]$ReportPath = "scripts/reports/out/script-inventory-report.json",
    [string]$SchemaPath = "schemas/ai/script-inventory-report.schema.json",
    [string]$RunId = ""
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

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "script-inventory-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $policyFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $PolicyPath
    $policy = Get-Content -Raw -LiteralPath $policyFullPath | ConvertFrom-Json
    $inventoryRoot = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue ([string]$policy.inventoryRoot)
    $extensions = @($policy.scriptExtensions | ForEach-Object { ([string]$_).ToLowerInvariant() })
    $allowedClassifications = @($policy.allowedClassifications | ForEach-Object { [string]$_ })
    $releaseCriticalAllowed = @($policy.releaseCriticalAllowedClassifications | ForEach-Object { [string]$_ })

    $scriptFiles = @(Get-ChildItem -LiteralPath $inventoryRoot -Recurse -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $extensions -contains $_.Extension.ToLowerInvariant() } |
        Sort-Object FullName)

    $scripts = @()
    foreach ($file in $scriptFiles) {
        $relative = Convert-ToRepoPath -Root $workspaceRootPath -PathValue $file.FullName
        $classificationResult = Get-ScriptClassification -PathValue $relative -Rules @($policy.classificationRules)
        $classification = [string]$classificationResult.classification
        $releaseCritical = Test-AnyPattern -PathValue $relative -Patterns @($policy.releaseCriticalPatterns)
        $validClassification = $allowedClassifications -contains $classification
        $releaseCriticalAllowedClassification = (-not $releaseCritical) -or ($releaseCriticalAllowed -contains $classification)

        $scripts += [pscustomobject]@{
            path = $relative
            classification = $classification
            classificationRule = [string]$classificationResult.rulePattern
            releaseCritical = $releaseCritical
            validClassification = $validClassification
            releaseCriticalAllowedClassification = $releaseCriticalAllowedClassification
            sizeBytes = [int64]$file.Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
        }
    }

    $unknownScripts = @($scripts | Where-Object { $_.classification -eq "unknown" -or -not $_.validClassification })
    $releaseCriticalViolations = @($scripts | Where-Object { $_.releaseCritical -and -not $_.releaseCriticalAllowedClassification })
    $classificationCounts = [ordered]@{}
    foreach ($classification in @($allowedClassifications + "unknown")) {
        $classificationCounts[$classification] = @($scripts | Where-Object { $_.classification -eq $classification }).Count
    }

    $blockers = @(
        @($unknownScripts | ForEach-Object { "Unclassified script: " + $_.path })
        @($releaseCriticalViolations | ForEach-Object { "Release-critical script has invalid classification: " + $_.path + " (" + $_.classification + ")" })
    )
    $overallStatus = if ($unknownScripts.Count -eq 0 -and $releaseCriticalViolations.Count -eq 0) { "passed" } else { "failed" }
    $report = [pscustomobject]@{
        schemaVersion = "npdev-script-inventory-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-script-inventory-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        policyPath = "scripts/policy/script-inventory-policy.json"
        scriptCount = $scripts.Count
        classificationCounts = $classificationCounts
        unknownScripts = @($unknownScripts | ForEach-Object { $_.path })
        releaseCriticalViolations = @($releaseCriticalViolations | ForEach-Object { $_.path })
        releaseCriticalAllowedClassifications = @($releaseCriticalAllowed)
        scripts = @($scripts)
        blockers = @($blockers)
        doesNotSolve = @(
            "Does not delete or move scripts.",
            "Does not archive deprecated scripts.",
            "Does not make script classification a product-scope expansion."
        )
    }

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

    if ($overallStatus -eq "passed") {
        Write-Host ("Script inventory check passed. Report: " + $ReportPath)
        exit 0
    }

    Write-Error ("Script inventory check failed. Report: " + $ReportPath)
    exit 1
}
finally {
    Pop-Location
}
