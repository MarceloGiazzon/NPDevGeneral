param(
    [string]$WorkspaceRoot = ".",
    [string]$PolicyPath = "scripts/policy/maturity-max-roadmap-policy.json",
    [string]$DocumentationPath = "docs/ROADMAP_BOUNDARY_POLICY.md",
    [string]$LedgerPath = "docs/MATURITY_CLOSURE_LEDGER.md",
    [string]$HumanActionRegisterPath = "docs/POST_BETA0_HUMAN_ACTION_REGISTER.md",
    [string]$SchemaPath = "schemas/ai/maturity-max-roadmap-boundary-report.schema.json",
    [string]$ReportPath = "scripts/reports/out/maturity-max-roadmap-boundary-report.json",
    [string]$Beta0TruthReportPath = "scripts/reports/out/beta0-state-truth-report.json",
    [string]$RoadmapSourcePath = "C:\Users\Marcelo\Downloads\npdev_full_maturity_closure_roadmap_updated.md",
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

function Get-ReportStatus {
    param([object]$Report)
    if ($null -eq $Report) { return "missing" }
    if ($Report.PSObject.Properties.Name -contains "overallStatus") { return [string]$Report.overallStatus }
    if ($Report.PSObject.Properties.Name -contains "status") { return [string]$Report.status }
    return "missing"
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
        [object]$Evidence,
        [bool]$Blocking = $true
    )
    $script:checks += [pscustomobject]@{
        name = $Name
        passed = $Passed
        blocking = $Blocking
        reason = $Reason
        evidence = $Evidence
    }
    if ($Blocking -and -not $Passed) {
        $script:blockers.Add(("Check failed: " + $Name + " - " + $Reason)) | Out-Null
    }
}

$expectedCheckpointNames = @(
    "Honest State and Closure Contract",
    "Phase-2 Postgres and Linux Residual Fixes",
    "RuntimeHost Integration Test Infrastructure",
    "Trusted-Source and Custom Scenario Reconciliation",
    "Report Bootstrap and Evidence Regeneration",
    "Portable Tooling and Path Neutrality",
    "Gradle-Native Validation Migration",
    "Schema Consolidation and Strict Legacy Rejection",
    "Stateful Additive Migration Support",
    "Incremental Migration Test Harness",
    "Trusted Source Security Hardening",
    "Shift-Left AI Safety and Schema Hardening",
    "Custom UX and Extensibility Support",
    "React Editor Decomplexification",
    "DSL Parser Robustness",
    "CI Parallelization, Caching, Onboarding, and Final Closure"
)

$expectedCheckpointSlugs = @(
    "honest-state-and-closure-contract",
    "phase-2-postgres-and-linux-residual-fixes",
    "runtimehost-integration-test-infrastructure",
    "trusted-source-and-custom-scenario-reconciliation",
    "report-bootstrap-and-evidence-regeneration",
    "portable-tooling-and-path-neutrality",
    "gradle-native-validation-migration",
    "schema-consolidation-and-strict-legacy-rejection",
    "stateful-additive-migration-support",
    "incremental-migration-test-harness",
    "trusted-source-security-hardening",
    "shift-left-ai-safety-and-schema-hardening",
    "custom-ux-and-extensibility-support",
    "react-editor-decomplexification",
    "dsl-parser-robustness",
    "ci-parallelization-caching-onboarding-and-final-closure"
)

$expectedClassifications = @(
    "current-checkpoint-blocker",
    "current-roadmap-blocker",
    "known-risk-accepted",
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

$notSolved = @(
    "Does not modify product code.",
    "Does not fix Postgres or Linux fidelity.",
    "Does not fix golden scenarios.",
    "Does not address schema, parser, migration, UI maintainability, CI performance, or onboarding gaps.",
    "Does not clean the worktree.",
    "Does not move, recreate, delete, retag, or reinterpret beta0.",
    "Does not proceed to Checkpoint 1."
)

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "maturity-max-roadmap-boundary-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $script:blockers = [System.Collections.Generic.List[string]]::new()
    $script:checks = @()

    foreach ($path in @($PolicyPath, $DocumentationPath, $LedgerPath, $HumanActionRegisterPath, $SchemaPath)) {
        $evidence = Get-FileEvidence -Root $workspaceRootPath -PathValue $path
        Add-Check -Name ("required-file-exists:" + $path) -Passed ([bool]$evidence.exists) -Reason ("Required Checkpoint 0 file exists: " + $path) -Evidence $evidence
    }

    $roadmapExists = Test-Path -LiteralPath $RoadmapSourcePath -PathType Leaf
    $roadmapHash = if ($roadmapExists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $RoadmapSourcePath).Hash.ToLowerInvariant() } else { $null }
    $roadmapText = if ($roadmapExists) { Get-Content -Raw -LiteralPath $RoadmapSourcePath } else { "" }
    $sourceContainsOlderTarget = $roadmapText -match "95\s*[-\u2013]\s*97" -or $roadmapText -match "91\s*[-\u2013]\s*92"
    Add-Check -Name "authoritative-roadmap-input-present" -Passed $roadmapExists -Reason "The human-provided roadmap input is available for exact bundle preservation." -Evidence ([pscustomobject]@{ path = ($RoadmapSourcePath -replace "\\", "/"); sha256 = $roadmapHash })

    $policyFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $PolicyPath
    $policy = Read-JsonFile $policyFullPath
    Add-Check -Name "policy-json-readable" -Passed ($null -ne $policy) -Reason "Maturity max roadmap policy JSON is readable." -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $PolicyPath)

    $docFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $DocumentationPath
    $ledgerFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $LedgerPath
    $humanRegisterFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $HumanActionRegisterPath
    $docText = if (Test-Path -LiteralPath $docFullPath -PathType Leaf) { Get-Content -Raw -LiteralPath $docFullPath } else { "" }
    $ledgerText = if (Test-Path -LiteralPath $ledgerFullPath -PathType Leaf) { Get-Content -Raw -LiteralPath $ledgerFullPath } else { "" }
    $humanRegisterText = if (Test-Path -LiteralPath $humanRegisterFullPath -PathType Leaf) { Get-Content -Raw -LiteralPath $humanRegisterFullPath } else { "" }

    $policyText = if ($null -ne $policy) { $policy | ConvertTo-Json -Depth 80 } else { "" }
    $generatedText = ($docText + "`n" + $ledgerText + "`n" + $humanRegisterText + "`n" + $policyText)
    Add-Check -Name "normalized-current-maturity-present" -Passed ($generatedText -match "7\.8/10 \(\~78%\)") -Reason "Generated CP0 evidence records current maturity as 7.8/10 (~78%)." -Evidence ([pscustomobject]@{ expected = "7.8/10 (~78%)" })
    Add-Check -Name "normalized-target-maturity-present" -Passed ($generatedText -match "9\.2-9\.5/10 \(\~92-95%\)") -Reason "Generated CP0 evidence records target maturity as 9.2-9.5/10 (~92-95%)." -Evidence ([pscustomobject]@{ expected = "9.2-9.5/10 (~92-95%)" })
    Add-Check -Name "old-target-not-propagated" -Passed (-not ($generatedText -match "95\s*[-\u2013]\s*97" -or $generatedText -match "91\s*[-\u2013]\s*92")) -Reason "Generated CP0 docs, policy, and report inputs do not propagate older target values." -Evidence ([pscustomobject]@{ normalizedTarget = "9.2-9.5/10 (~92-95%)" })

    $policyCheckpointCount = if ($null -ne $policy) { @($policy.checkpoints).Count } else { 0 }
    $policyCheckpointNames = if ($null -ne $policy) { @($policy.checkpoints | ForEach-Object { [string]$_.name }) } else { @() }
    $policyCheckpointSlugs = if ($null -ne $policy) { @($policy.checkpoints | ForEach-Object { [string]$_.slug }) } else { @() }
    $policyClassificationsPresent = $null -ne $policy -and (Test-SequenceEqual -Actual @($policy.allowedNewFindingClassifications) -Expected $expectedClassifications)
    $policyCheckpointsExact = $null -ne $policy -and $policyCheckpointCount -eq 16 -and (Test-SequenceEqual -Actual $policyCheckpointNames -Expected $expectedCheckpointNames) -and (Test-SequenceEqual -Actual $policyCheckpointSlugs -Expected $expectedCheckpointSlugs)
    $policyBeta0Immutable = $null -ne $policy -and [bool]$policy.beta0TagImmutable -and -not [bool]$policy.beta0RetagAllowed
    $requiresHumanApproval = $null -ne $policy -and [bool]$policy.newCheckpointRequiresHumanApproval -and [bool]$policy.requiresHumanApprovalForNewCheckpoint

    Add-Check -Name "policy-schema-version" -Passed ($null -ne $policy -and [string]$policy.schemaVersion -eq "npdev-maturity-max-roadmap-policy.v1") -Reason "Policy schema version is locked to maturity-max v1." -Evidence ([pscustomobject]@{ actual = if ($null -ne $policy) { [string]$policy.schemaVersion } else { "" } })
    Add-Check -Name "policy-roadmap-name" -Passed ($null -ne $policy -and [string]$policy.roadmapName -eq "NPDev Full Maturity Closure Roadmap") -Reason "Policy names the authoritative maturity closure roadmap." -Evidence ([pscustomobject]@{ actual = if ($null -ne $policy) { [string]$policy.roadmapName } else { "" } })
    Add-Check -Name "policy-exact-checkpoints" -Passed $policyCheckpointsExact -Reason "Policy contains exactly the 16 approved roadmap checkpoints in order." -Evidence ([pscustomobject]@{ checkpointCount = $policyCheckpointCount; expected = $expectedCheckpointNames })
    Add-Check -Name "policy-beta0-retag-forbidden" -Passed $policyBeta0Immutable -Reason "Policy marks beta0 immutable and retagging forbidden." -Evidence ([pscustomobject]@{ beta0TagImmutable = if ($null -ne $policy) { [bool]$policy.beta0TagImmutable } else { $false }; beta0RetagAllowed = if ($null -ne $policy) { [bool]$policy.beta0RetagAllowed } else { $true } })
    Add-Check -Name "policy-human-approval-for-new-checkpoint" -Passed $requiresHumanApproval -Reason "Policy requires human approval before adding or changing checkpoints." -Evidence ([pscustomobject]@{ newCheckpointRequiresHumanApproval = $requiresHumanApproval })
    Add-Check -Name "policy-finding-classifications" -Passed $policyClassificationsPresent -Reason "Policy contains exactly the allowed finding classifications." -Evidence ([pscustomobject]@{ expected = $expectedClassifications })

    foreach ($i in 0..15) {
        $checkpointLine = ([string]$i) + ". " + $expectedCheckpointNames[$i]
        Add-Check -Name ("doc-checkpoint-listed:" + $i) -Passed ($docText.Contains($checkpointLine)) -Reason ("Documentation lists checkpoint: " + $checkpointLine) -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    }
    foreach ($classification in $expectedClassifications) {
        Add-Check -Name ("doc-classification-listed:" + $classification) -Passed ($docText.Contains($classification)) -Reason ("Documentation lists finding classification: " + $classification) -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    }
    foreach ($evidenceFile in $expectedEvidenceFiles) {
        Add-Check -Name ("doc-evidence-requirement-listed:" + $evidenceFile) -Passed ($docText.Contains($evidenceFile)) -Reason ("Documentation lists checkpoint evidence requirement: " + $evidenceFile) -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    }
    Add-Check -Name "doc-roadmap-artifact-requirement" -Passed ($docText.Contains("artifacts/roadmap/npdev_full_maturity_closure_roadmap_updated.md") -and $docText.Contains("artifacts/roadmap/roadmap-sha256.txt")) -Reason "Documentation requires exact roadmap copy and hash in the CP0 bundle." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-no-retag-rule" -Passed ($docText -match "must not move" -and $docText -match "retag" -and $docText -match "git rev-parse beta0") -Reason "Documentation contains the no-retag rule and peeled-tag evidence command." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-closure-definition" -Passed ($docText -match "Closure Definition" -and $docText -match "Checkpoints 0 through 15" -and $docText -match "No new roadmap is automatically generated") -Reason "Documentation defines closure for the 16-checkpoint roadmap without automatic roadmap generation." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-dirty-worktree-evidence-only" -Passed ($docText -match "Dirty worktree state is recorded as evidence only") -Reason "Documentation records dirty worktree state without treating it as retagging or automatic CP0 blocker." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "doc-what-checkpoint-0-does-not-solve" -Passed ($docText -match "Checkpoint 0 Does Not Solve" -and $docText -match "does not modify product code" -and $docText -match "Does not proceed to Checkpoint 1") -Reason "Documentation states what Checkpoint 0 does not solve." -Evidence ([pscustomobject]@{ path = $DocumentationPath })
    Add-Check -Name "ledger-present-and-normalized" -Passed ($ledgerText -match "7\.8/10 \(\~78%\)" -and $ledgerText -match "9\.2-9\.5/10 \(\~92-95%\)" -and $ledgerText -match "beta0-verified") -Reason "Maturity closure ledger records normalized maturity values and Beta0 truth states." -Evidence ([pscustomobject]@{ path = $LedgerPath })
    Add-Check -Name "human-action-register-stop-rule" -Passed ($humanRegisterText -match "Checkpoint 1 approval gate" -and $humanRegisterText -match "must not proceed to Checkpoint 1") -Reason "Human action register records the required stop after CP0." -Evidence ([pscustomobject]@{ path = $HumanActionRegisterPath })

    $ErrorActionPreference = "Continue"
    $beta0PeeledOutput = git rev-parse "beta0^{}" 2>$null
    $beta0PeeledExit = $LASTEXITCODE
    $beta0RevListOutput = git rev-list -n 1 beta0 2>$null
    $beta0RevListExit = $LASTEXITCODE
    $dirtyLines = @(git status --porcelain=v1)
    $ErrorActionPreference = "Stop"

    $beta0PeeledCommit = (($beta0PeeledOutput | Select-Object -First 1) -as [string])
    $beta0RevListCommit = (($beta0RevListOutput | Select-Object -First 1) -as [string])
    $beta0TagPresent = $beta0PeeledExit -eq 0 -and -not [string]::IsNullOrWhiteSpace($beta0PeeledCommit)
    $peeledCommandsAgree = $beta0TagPresent -and $beta0RevListExit -eq 0 -and $beta0PeeledCommit -eq $beta0RevListCommit
    Add-Check -Name "beta0-peeled-tag-present" -Passed $beta0TagPresent -Reason "Existing beta0 tag is observed using the peeled commit without retagging." -Evidence ([pscustomobject]@{ command = "git rev-parse beta0^{}"; peeledCommit = $beta0PeeledCommit })
    Add-Check -Name "beta0-peeled-commands-agree" -Passed $peeledCommandsAgree -Reason "git rev-parse beta0^{} and git rev-list -n 1 beta0 agree." -Evidence ([pscustomobject]@{ revParseCommit = $beta0PeeledCommit; revListCommit = $beta0RevListCommit })

    $closureReportPath = "scripts/reports/out/beta0-final-closure-report.json"
    $releaseCheckPath = "scripts/reports/out/beta0-final-release-check-report.json"
    $betaReleaseGatePath = "scripts/reports/out/beta-release-gate-report.json"
    $betaReleaseManifestPath = "scripts/reports/out/beta-release-evidence-manifest.json"
    $closureReport = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $closureReportPath)
    $releaseCheckReport = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $releaseCheckPath)
    $betaReleaseGateReport = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $betaReleaseGatePath)
    $betaReleaseManifest = Read-JsonFile (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $betaReleaseManifestPath)

    $closureStatus = Get-ReportStatus $closureReport
    $releaseCheckStatus = Get-ReportStatus $releaseCheckReport
    $betaReleaseGateCommit = if ($null -ne $betaReleaseGateReport -and $null -ne $betaReleaseGateReport.git) { [string]$betaReleaseGateReport.git.commit } else { "" }
    $betaReleaseManifestCommit = if ($null -ne $betaReleaseManifest -and $null -ne $betaReleaseManifest.git) { [string]$betaReleaseManifest.git.commit } else { "" }
    $closureReportsPassed = $closureStatus -eq "passed" -and $releaseCheckStatus -eq "passed"
    $closureEvidenceCommits = @($betaReleaseGateCommit, $betaReleaseManifestCommit) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
    $closureCommitMatchesPeeledTag = $beta0TagPresent -and @($closureEvidenceCommits | Where-Object { $_ -eq $beta0PeeledCommit }).Count -gt 0
    $beta0RepoState = if (-not $beta0TagPresent) {
        "pre-release-hardening"
    }
    elseif ($closureReportsPassed -and $closureCommitMatchesPeeledTag) {
        "beta0-verified"
    }
    else {
        "beta0-stale-evidence"
    }
    Add-Check -Name "beta0-closure-reports-passed" -Passed $closureReportsPassed -Reason "Beta0 final closure and release check reports exist and passed." -Evidence ([pscustomobject]@{ closureReportPath = $closureReportPath; closureStatus = $closureStatus; releaseCheckPath = $releaseCheckPath; releaseCheckStatus = $releaseCheckStatus })
    Add-Check -Name "beta0-closure-commit-matches-peeled-tag" -Passed $closureCommitMatchesPeeledTag -Reason "The peeled beta0 commit matches the commit recorded in Beta0 closure evidence." -Evidence ([pscustomobject]@{ beta0PeeledCommit = $beta0PeeledCommit; closureEvidenceCommits = @($closureEvidenceCommits) })
    Add-Check -Name "beta0-repo-state-verified" -Passed ($beta0RepoState -eq "beta0-verified") -Reason "Repository state is beta0-verified." -Evidence ([pscustomobject]@{ repositoryState = $beta0RepoState })

    $dirtyWorktree = @($dirtyLines).Count -gt 0
    Add-Check -Name "dirty-worktree-recorded-as-evidence-only" -Passed $true -Blocking $false -Reason "Dirty worktree state is recorded as evidence and is not an automatic CP0 blocker." -Evidence ([pscustomobject]@{ dirty = $dirtyWorktree; dirtyLineCount = @($dirtyLines).Count; dirtyPaths = @($dirtyLines) })

    $sourceInconsistencies = @()
    $newFindings = @()
    if ($sourceContainsOlderTarget) {
        $sourceInconsistencies += [pscustomobject]@{
            id = "CP0-SOURCE-TARGET-INCONSISTENCY"
            description = "Authoritative roadmap input contains an older target range; CP0 evidence normalizes to the human-approved target."
            normalizedCurrent = "7.8/10 (~78%)"
            normalizedTarget = "9.2-9.5/10 (~92-95%)"
        }
        $newFindings += [pscustomobject]@{
            id = "CP0-SOURCE-TARGET-INCONSISTENCY"
            description = "Authoritative roadmap input contains an older target range; CP0 evidence normalizes to the human-approved target."
            classification = "known-risk-accepted"
            status = "normalized"
        }
    }

    $beta0TruthReport = [pscustomobject]@{
        schemaVersion = "npdev-beta0-state-truth-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-maturity-max-roadmap-boundary-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = if ($beta0RepoState -eq "beta0-verified") { "passed" } else { "failed" }
        repositoryState = $beta0RepoState
        beta0TagPresent = $beta0TagPresent
        beta0PeeledCommit = $beta0PeeledCommit
        beta0RevListCommit = $beta0RevListCommit
        beta0RetagAllowed = $false
        beta0RetagActionTaken = $false
        closureReportsPassed = $closureReportsPassed
        closureEvidenceCommits = @($closureEvidenceCommits)
        closureCommitMatchesPeeledTag = $closureCommitMatchesPeeledTag
        beta0ClosureEvidence = @(
            [pscustomobject]@{ name = "beta0-final-closure"; path = $closureReportPath; exists = $null -ne $closureReport; status = $closureStatus },
            [pscustomobject]@{ name = "beta0-final-release-check"; path = $releaseCheckPath; exists = $null -ne $releaseCheckReport; status = $releaseCheckStatus },
            [pscustomobject]@{ name = "beta-release-gate"; path = $betaReleaseGatePath; exists = $null -ne $betaReleaseGateReport; recordedCommit = $betaReleaseGateCommit },
            [pscustomobject]@{ name = "beta-release-evidence-manifest"; path = $betaReleaseManifestPath; exists = $null -ne $betaReleaseManifest; recordedCommit = $betaReleaseManifestCommit }
        )
        dirtyWorktree = [pscustomobject]@{
            dirty = $dirtyWorktree
            dirtyLineCount = @($dirtyLines).Count
            dirtyLines = @($dirtyLines)
            evidenceOnly = $true
            automaticCheckpointBlocker = $false
            beta0RetagAction = $false
        }
        notSolved = $notSolved
    }
    $beta0TruthReportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $Beta0TruthReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $beta0TruthReportFullPath) | Out-Null
    $beta0TruthReport | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $beta0TruthReportFullPath -Encoding UTF8

    $overallStatus = if ($blockers.Count -eq 0) { "passed" } else { "failed" }
    $report = [pscustomobject]@{
        schemaVersion = "npdev-maturity-max-roadmap-boundary-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-maturity-max-roadmap-boundary-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        checkpointCount = $policyCheckpointCount
        beta0RetagAllowed = $false
        beta0RetagActionTaken = $false
        newCheckpointRequiresHumanApproval = $requiresHumanApproval
        findingClassificationPolicyPresent = $policyClassificationsPresent
        closureDefinitionPresent = ($docText -match "Closure Definition")
        currentMaturity = [pscustomobject]@{
            scoreOutOf10 = 7.8
            percent = 78
            label = "7.8/10 (~78%)"
        }
        targetMaturity = [pscustomobject]@{
            scoreOutOf10Min = 9.2
            scoreOutOf10Max = 9.5
            percentMin = 92
            percentMax = 95
            label = "9.2-9.5/10 (~92-95%)"
        }
        selectedEvidencePathPolicy = [pscustomobject]@{
            cursorLocalDefault = "D:\WorkSpace\NPDev_General__OutsideRepo\temp\last-roadmap"
            cloudFallbackEnvironmentVariable = "NPDEV_CHECKPOINT_DIR"
            repoRelativeFallback = "docs/maturity-closure/checkpoints/last-roadmap"
        }
        authoritativeRoadmapInput = [pscustomobject]@{
            path = $RoadmapSourcePath
            exists = $roadmapExists
            sha256 = $roadmapHash
            exactCopyRequiredInBundle = $true
        }
        beta0TruthReportPath = $Beta0TruthReportPath
        beta0RepositoryState = $beta0RepoState
        beta0PeeledCommit = $beta0PeeledCommit
        sourceInconsistencies = @($sourceInconsistencies)
        checks = @($checks)
        blockers = @($blockers)
        newFindings = @($newFindings)
        checkpoint1Unblocked = ($overallStatus -eq "passed")
        notSolved = $notSolved
    }

    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    $schemaValidationPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/tmp/maturity-max-roadmap-boundary-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error ("Maturity max roadmap boundary report failed schema validation. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
        exit 1
    }

    if ($overallStatus -eq "passed") {
        Write-Host ("Maturity max roadmap boundary check passed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
        exit 0
    }

    Write-Error ("Maturity max roadmap boundary check failed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
    exit 1
}
finally {
    Pop-Location
}
