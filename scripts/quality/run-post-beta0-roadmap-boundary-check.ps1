param(
    [string]$WorkspaceRoot = ".",
    [string]$PolicyPath = "scripts/policy/post-beta0-maturity-roadmap-policy.json",
    [string]$DocumentationPath = "docs/maintainers/ROADMAP_BOUNDARY_POLICY.md",
    [string]$SchemaPath = "schemas/ai/post-beta0-roadmap-boundary-report.schema.json",
    [string]$ReportPath = "scripts/reports/out/post-beta0-roadmap-boundary-report.json",
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
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Test-SequenceEqual {
    param([object[]]$Actual, [string[]]$Expected)
    $actualValues = @($Actual | ForEach-Object { [string]$_ })
    if ($actualValues.Count -ne $Expected.Count) { return $false }
    for ($i = 0; $i -lt $Expected.Count; $i++) {
        if ($actualValues[$i] -ne $Expected[$i]) { return $false }
    }
    return $true
}

function Get-FileEvidence {
    param([string]$Root, [string]$PathValue)
    $fullPath = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    $exists = Test-Path -LiteralPath $fullPath -PathType Leaf
    return [pscustomobject]@{
        path = Convert-ToRepoPath -Root $Root -PathValue $fullPath
        exists = $exists
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant() } else { $null }
    }
}

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Reason,
        [object]$Evidence
    )
    $script:checks += [pscustomobject]@{
        name = $Name
        passed = $Passed
        reason = $Reason
        evidence = $Evidence
    }
    if (-not $Passed) {
        $script:blockers.Add(("Check failed: " + $Name + " - " + $Reason)) | Out-Null
    }
}

$expectedCheckpoints = @(
    "anti-horizon-controls",
    "kernel-postgres-adapter-fidelity",
    "runtimehost-postgres-profile-fidelity",
    "true-e2e-runtime-fidelity",
    "golden-scenario-truth",
    "boundary-lock-pack",
    "ai-to-dsl-mapping-contract",
    "ci-closure-and-human-action-register"
)

$expectedCheckpointNames = @(
    "0. Anti-Horizon Controls",
    "1. Kernel Postgres Adapter Fidelity",
    "2. RuntimeHost Postgres Profile Fidelity",
    "3. True E2E Runtime Fidelity",
    "4. Golden Scenario Truth",
    "5. Boundary Lock Pack",
    "6. AI-to-DSL Mapping Contract",
    "7. CI Closure and Human-Action Register"
)

$expectedClassifications = @(
    "current-roadmap-blocker",
    "known-risk",
    "post-roadmap-backlog",
    "human-decision-required",
    "invalid-or-duplicate"
)

$expectedEvidenceFiles = @(
    "checkpoint-summary.md",
    "checkpoint-result.json",
    "acceptance-matrix.md",
    "progress-delta.json",
    "changed-files.txt",
    "git-diff.patch",
    "incremental-diff-from-previous-checkpoint.patch",
    "validation-commands.txt",
    "validation-output.txt",
    "bundle-size-report.md",
    "omitted-large-artifacts.md",
    "omitted-large-artifacts.json",
    "artifacts/"
)

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "post-beta0-roadmap-boundary-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $script:blockers = [System.Collections.Generic.List[string]]::new()
    $script:checks = @()

    foreach ($path in @($PolicyPath, $DocumentationPath, $SchemaPath)) {
        $evidence = Get-FileEvidence -Root $workspaceRootPath -PathValue $path
        Add-Check -Name ("required-file-exists:" + $path) -Passed ([bool]$evidence.exists) -Reason ("Required Checkpoint 0 file exists: " + $path) -Evidence $evidence
    }

    $policyFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $PolicyPath
    $policy = Read-JsonFile $policyFullPath
    Add-Check -Name "policy-json-readable" -Passed ($null -ne $policy) -Reason "Post-Beta0 roadmap policy JSON is readable." -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $PolicyPath)

    $docFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $DocumentationPath
    $docText = if (Test-Path -LiteralPath $docFullPath -PathType Leaf) { Get-Content -Raw -LiteralPath $docFullPath } else { "" }
    Add-Check -Name "boundary-policy-doc-readable" -Passed (-not [string]::IsNullOrWhiteSpace($docText)) -Reason "Roadmap boundary policy document is readable." -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $DocumentationPath)

    $policyCheckpointCount = if ($null -ne $policy) { @($policy.checkpoints).Count } else { 0 }
    $policyClassificationsPresent = $null -ne $policy -and (Test-SequenceEqual -Actual @($policy.allowedNewFindingClassifications) -Expected $expectedClassifications)
    $policyCheckpointsExact = $null -ne $policy -and (Test-SequenceEqual -Actual @($policy.checkpoints) -Expected $expectedCheckpoints)
    $policyBeta0Immutable = $null -ne $policy -and [bool]$policy.beta0TagImmutable
    $requiresHumanApproval = $null -ne $policy -and [bool]$policy.requiresHumanApprovalForNewCheckpoint

    Add-Check -Name "policy-schema-version" -Passed ($null -ne $policy -and [string]$policy.schemaVersion -eq "npdev-post-beta0-maturity-roadmap-policy.v1") -Reason "Policy schema version is locked to v1." -Evidence ([pscustomobject]@{ actual = if ($null -ne $policy) { [string]$policy.schemaVersion } else { "" } })
    Add-Check -Name "policy-roadmap-name" -Passed ($null -ne $policy -and [string]$policy.roadmapName -eq "NPDev Post-Beta0 Maturity Hardening Roadmap") -Reason "Policy names the authoritative post-Beta0 maturity roadmap." -Evidence ([pscustomobject]@{ actual = if ($null -ne $policy) { [string]$policy.roadmapName } else { "" } })
    Add-Check -Name "policy-beta0-tag-immutable" -Passed $policyBeta0Immutable -Reason "Policy marks the beta0 tag immutable." -Evidence ([pscustomobject]@{ beta0TagImmutable = $policyBeta0Immutable })
    Add-Check -Name "policy-human-approval-for-new-checkpoint" -Passed $requiresHumanApproval -Reason "Policy requires human approval before adding checkpoints." -Evidence ([pscustomobject]@{ requiresHumanApprovalForNewCheckpoint = $requiresHumanApproval })
    Add-Check -Name "policy-exact-checkpoints" -Passed $policyCheckpointsExact -Reason "Policy contains exactly the eight approved roadmap checkpoint slugs in order." -Evidence ([pscustomobject]@{ checkpointCount = $policyCheckpointCount; expected = $expectedCheckpoints })
    Add-Check -Name "policy-finding-classifications" -Passed $policyClassificationsPresent -Reason "Policy contains exactly the allowed new finding classifications." -Evidence ([pscustomobject]@{ expected = $expectedClassifications })

    foreach ($checkpointName in $expectedCheckpointNames) {
        Add-Check -Name ("doc-checkpoint-listed:" + $checkpointName) -Passed ($docText.Contains($checkpointName)) -Reason ("Documentation lists checkpoint: " + $checkpointName) -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    }

    foreach ($classification in $expectedClassifications) {
        Add-Check -Name ("doc-classification-listed:" + $classification) -Passed ($docText.Contains($classification)) -Reason ("Documentation lists finding classification: " + $classification) -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    }

    foreach ($evidenceFile in $expectedEvidenceFiles) {
        Add-Check -Name ("doc-evidence-requirement-listed:" + $evidenceFile) -Passed ($docText.Contains($evidenceFile)) -Reason ("Documentation lists checkpoint evidence requirement: " + $evidenceFile) -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    }

    Add-Check -Name "doc-no-retag-rule" -Passed ($docText -match "beta0.*immutable" -and $docText -match "must not move") -Reason "Documentation contains the no-retag rule." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-no-new-phase-rule" -Passed ($docText -match "No-New-Phase" -and $docText -match "explicit human approval") -Reason "Documentation contains the no-new-phase rule." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-closure-definition" -Passed ($docText -match "Closure Definition" -and $docText -match "No new roadmap is automatically generated") -Reason "Documentation defines closure without automatic roadmap generation." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-new-analysis-rule" -Passed ($docText -match "New analysis does not automatically create a new roadmap") -Reason "Documentation states that new analysis does not automatically create a new roadmap." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-what-checkpoint-0-does-not-solve" -Passed ($docText -match "Checkpoint 0 Does Not Solve" -and $docText -match "does not fix Postgres fidelity" -and $docText -match "does not change.*beta0.*tag") -Reason "Documentation states what Checkpoint 0 does not solve." -Evidence ([pscustomobject]@{ path = $DocumentationPath })

    $ErrorActionPreference = "Continue"
    $beta0TagOutput = git rev-parse -q --verify refs/tags/beta0 2>$null
    $beta0TagExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    Add-Check -Name "beta0-tag-present-for-immutability-evidence" -Passed ($beta0TagExit -eq 0 -and -not [string]::IsNullOrWhiteSpace(($beta0TagOutput | Out-String))) -Reason "Existing beta0 tag is present; this check observes it without moving or recreating it." -Evidence ([pscustomobject]@{ tag = "beta0"; object = (($beta0TagOutput | Select-Object -First 1) -as [string]) })

    $overallStatus = if ($blockers.Count -eq 0) { "passed" } else { "failed" }
    $report = [pscustomobject]@{
        schemaVersion = "npdev-post-beta0-roadmap-boundary-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-post-beta0-roadmap-boundary-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        checkpointCount = $policyCheckpointCount
        beta0TagImmutable = $policyBeta0Immutable
        newPhaseExpansionAllowed = $false
        findingClassificationPolicyPresent = $policyClassificationsPresent
        beta0TagObject = (($beta0TagOutput | Select-Object -First 1) -as [string])
        checks = @($checks)
        blockers = @($blockers)
        newFindings = @()
        notSolved = @(
            "Does not fix Postgres fidelity",
            "Does not fix scenarios",
            "Does not add product features",
            "Does not change the beta0 tag"
        )
    }

    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    $schemaValidationPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/tmp/post-beta0-roadmap-boundary-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error ("Post-Beta0 roadmap boundary report failed schema validation. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
        exit 1
    }

    if ($overallStatus -eq "passed") {
        Write-Host ("Post-Beta0 roadmap boundary check passed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
        exit 0
    }

    Write-Error ("Post-Beta0 roadmap boundary check failed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
    exit 1
}
finally {
    Pop-Location
}
