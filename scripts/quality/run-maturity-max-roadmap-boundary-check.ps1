param(
    [string]$WorkspaceRoot = ".",
    [string]$PolicyPath = "scripts/policy/maturity-max-roadmap-policy.json",
    [string]$DocumentationPath = "docs/maintainers/ROADMAP_BOUNDARY_POLICY.md",
    [string]$LedgerPath = "docs/maintainers/MATURITY_CLOSURE_LEDGER.md",
    [string]$HumanActionRegisterPath = "docs/maintainers/POST_BETA0_HUMAN_ACTION_REGISTER.md",
    [string]$SchemaPath = "schemas/ai/maturity-max-roadmap-boundary-report.schema.json",
    [string]$ReportPath = "scripts/reports/out/maturity-max-roadmap-boundary-report.json",
    [string]$Beta0TruthReportPath = "scripts/reports/out/beta0-state-truth-report.json",
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

$py = (Get-Command python -ErrorAction Stop).Source
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

    # PLAN-11-to-4.md Item 4: this block used to default to a machine-local path in one maintainer's
    # own Downloads folder ($RoadmapSourcePath), read it (Get-Content), and assert two things from
    # its content: "the authoritative roadmap input is present" (could only ever PASS on that one
    # machine -- Test-Path was false everywhere else, so the assertion reported FALSE on every other
    # machine) and "the policy hasn't silently regressed to an older target the source input might
    # still carry" (a real question, but one this same read answered "no finding" on every machine
    # BUT the author's -- not a false pass, just zero actual verification anywhere else, the same
    # "green while checking nothing" shape this repo has been bitten by repeatedly). Both assertions
    # and the read behind them are removed outright, not narrowed: an external, non-repo, personal
    # reference file is not a coupling this repo's own docs/policy can fix by conversion, and keeping
    # a check that only ever verifies on one laptop is worse than not having it.

    $policyFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $PolicyPath
    $policy = Read-JsonFile $policyFullPath
    Add-Check -Name "policy-json-readable" -Passed ($null -ne $policy) -Reason "Maturity max roadmap policy JSON is readable." -Evidence (Get-FileEvidence -Root $workspaceRootPath -PathValue $PolicyPath)
    $notSolved = if ($null -ne $policy) { @($policy.checkpoint0DoesNotSolve | ForEach-Object { [string]$_ }) } else { @() }

    # md-zero-2026-08-11 PLAN.md Phase 3: the 3 docs below (ROADMAP_BOUNDARY_POLICY.md,
    # MATURITY_CLOSURE_LEDGER.md, POST_BETA0_HUMAN_ACTION_REGISTER.md) are GENERATED from this same
    # $policy by scripts/docs/generate_maturity_max_roadmap_docs.py -- nothing here reads their text
    # anymore. What used to be ~20 separate doc-text .Contains()/-match assertions (each a redundant
    # confirmation that the doc still said what the policy already said) is now one freshness check:
    # is each rendered doc byte-identical to what the policy would render today?
    #
    # PLAN-11-to-4.md Item 2: this used to run with --check (render in memory, read the CURRENTLY
    # COMMITTED .md back off disk to compare) -- itself a script reading markdown content. Now it
    # always regenerates in place, and git (which diffs bytes without this process ever opening the
    # .md) answers "did that change anything already committed".
    $ErrorActionPreference = "Continue"
    & $py "scripts/docs/generate_maturity_max_roadmap_docs.py" 2>$null | Out-Null
    $generatorRan = ($LASTEXITCODE -eq 0)
    $docsUpToDate = $false
    if ($generatorRan) {
        git diff --exit-code -- docs/maintainers/ROADMAP_BOUNDARY_POLICY.md docs/maintainers/MATURITY_CLOSURE_LEDGER.md docs/maintainers/POST_BETA0_HUMAN_ACTION_REGISTER.md 2>$null | Out-Null
        $docsUpToDate = ($LASTEXITCODE -eq 0)
    }
    $ErrorActionPreference = "Stop"
    Add-Check -Name "generated-docs-current" -Passed $docsUpToDate -Reason "ROADMAP_BOUNDARY_POLICY.md, MATURITY_CLOSURE_LEDGER.md and POST_BETA0_HUMAN_ACTION_REGISTER.md are byte-identical to what the policy JSON renders (run 'python scripts/docs/generate_maturity_max_roadmap_docs.py' and commit the result if this fails)." -Evidence ([pscustomobject]@{ generator = "scripts/docs/generate_maturity_max_roadmap_docs.py" })

    $policyCheckpointCount = if ($null -ne $policy) { @($policy.checkpoints).Count } else { 0 }
    $policyCheckpointsSequential = $null -ne $policy -and $policyCheckpointCount -eq [int]$policy.checkpointCount -and
        (Test-SequenceEqual -Actual @($policy.checkpoints | ForEach-Object { [string]$_.number }) -Expected @(0..($policyCheckpointCount - 1) | ForEach-Object { [string]$_ })) -and
        (@($policy.checkpoints | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.slug) -or [string]::IsNullOrWhiteSpace([string]$_.name) }).Count -eq 0)
    $policyClassificationsPresent = $null -ne $policy -and (@($policy.allowedNewFindingClassifications).Count -gt 0) -and
        (@($policy.allowedNewFindingClassifications | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.value) -or [string]::IsNullOrWhiteSpace([string]$_.meaning) -or [string]::IsNullOrWhiteSpace([string]$_.action) }).Count -eq 0)
    $policyBeta0Immutable = $null -ne $policy -and [bool]$policy.beta0TagImmutable -and -not [bool]$policy.beta0RetagAllowed
    $requiresHumanApproval = $null -ne $policy -and [bool]$policy.newCheckpointRequiresHumanApproval -and [bool]$policy.requiresHumanApprovalForNewCheckpoint
    $normalizedMaturityConsistent = $null -ne $policy -and [string]$policy.currentMaturity.label -eq "7.8/10 (~78%)" -and [string]$policy.targetMaturity.label -eq "9.2-9.5/10 (~92-95%)"

    Add-Check -Name "policy-schema-version" -Passed ($null -ne $policy -and [string]$policy.schemaVersion -eq "npdev-maturity-max-roadmap-policy.v1") -Reason "Policy schema version is locked to maturity-max v1." -Evidence ([pscustomobject]@{ actual = if ($null -ne $policy) { [string]$policy.schemaVersion } else { "" } })
    Add-Check -Name "policy-roadmap-name" -Passed ($null -ne $policy -and [string]$policy.roadmapName -eq "NPDev Full Maturity Closure Roadmap") -Reason "Policy names the authoritative maturity closure roadmap." -Evidence ([pscustomobject]@{ actual = if ($null -ne $policy) { [string]$policy.roadmapName } else { "" } })
    Add-Check -Name "policy-checkpoints-well-formed" -Passed $policyCheckpointsSequential -Reason "Policy's checkpoints are numbered 0..N-1 with no gaps, and every one has a non-empty slug and name." -Evidence ([pscustomobject]@{ checkpointCount = $policyCheckpointCount })
    Add-Check -Name "policy-beta0-retag-forbidden" -Passed $policyBeta0Immutable -Reason "Policy marks beta0 immutable and retagging forbidden." -Evidence ([pscustomobject]@{ beta0TagImmutable = if ($null -ne $policy) { [bool]$policy.beta0TagImmutable } else { $false }; beta0RetagAllowed = if ($null -ne $policy) { [bool]$policy.beta0RetagAllowed } else { $true } })
    Add-Check -Name "policy-human-approval-for-new-checkpoint" -Passed $requiresHumanApproval -Reason "Policy requires human approval before adding or changing checkpoints." -Evidence ([pscustomobject]@{ newCheckpointRequiresHumanApproval = $requiresHumanApproval })
    Add-Check -Name "policy-finding-classifications" -Passed $policyClassificationsPresent -Reason "Policy declares at least one finding classification, and every one has a value, meaning and action." -Evidence ([pscustomobject]@{ classificationCount = if ($null -ne $policy) { @($policy.allowedNewFindingClassifications).Count } else { 0 } })
    Add-Check -Name "normalized-maturity-values" -Passed $normalizedMaturityConsistent -Reason "Policy records current maturity as 7.8/10 (~78%) and target as 9.2-9.5/10 (~92-95%)." -Evidence ([pscustomobject]@{ expected = @{ current = "7.8/10 (~78%)"; target = "9.2-9.5/10 (~92-95%)" } })

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

    # PLAN-11-to-4.md Item 4: used to populate a CP0-SOURCE-TARGET-INCONSISTENCY finding from the
    # now-removed external roadmap read above. Always empty now -- kept as fields (not deleted from
    # the report) since neither is schema-required and other evidence consumers may already key off
    # their presence.
    $sourceInconsistencies = @()
    $newFindings = @()

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
        closureDefinitionPresent = ($null -ne $policy -and @($policy.closureDefinition).Count -gt 0)
        currentMaturity = $policy.currentMaturity
        targetMaturity = $policy.targetMaturity
        selectedEvidencePathPolicy = [pscustomobject]@{
            cursorLocalDefault = $policy.evidencePathPolicy.cursorLocalDefault
            cloudFallbackEnvironmentVariable = $policy.evidencePathPolicy.cloudFallbackEnvironmentVariable
            repoRelativeFallback = $policy.evidencePathPolicy.repoRelativeFallback
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
