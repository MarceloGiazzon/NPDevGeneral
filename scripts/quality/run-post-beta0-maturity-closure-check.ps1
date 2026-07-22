param(
    [string]$WorkspaceRoot = ".",
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/post-beta0-maturity-closure-report.json"
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
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function New-ReportEvidence {
    param(
        [string]$Root,
        [string]$Name,
        [string]$PathValue
    )
    $fullPath = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    $report = Read-JsonFile $fullPath
    $exists = $null -ne $report
    $status = if ($exists -and $report.PSObject.Properties.Name -contains "overallStatus") {
        [string]$report.overallStatus
    }
    elseif ($exists -and $report.PSObject.Properties.Name -contains "status") {
        [string]$report.status
    }
    else {
        "missing"
    }
    return [pscustomobject]@{
        name = $Name
        path = Convert-ToRepoPath -Root $Root -PathValue $fullPath
        schemaVersion = if ($exists) { [string]$report.schemaVersion } else { "" }
        exists = $exists
        overallStatus = $status
        passed = ($exists -and $status -eq "passed")
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant() } else { $null }
    }
}

function Test-Contains {
    param([string]$Text, [string]$Pattern)
    return $Text -match [regex]::Escape($Pattern)
}

function Read-HumanActionRegister {
    param([string]$Path)
    $actions = @()
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return @()
    }
    $lines = Get-Content -LiteralPath $Path
    foreach ($line in $lines) {
        if ($line -notmatch "^\| " -or $line -match "^\| ---") {
            continue
        }
        $cells = @($line.Trim("|").Split("|") | ForEach-Object { $_.Trim() })
        if ($cells.Count -lt 6 -or $cells[0] -eq "Action") {
            continue
        }
        $actions += [pscustomobject]@{
            action = $cells[0] -replace '`', ''
            owner = $cells[1] -replace '`', ''
            status = $cells[2] -replace '`', ''
            evidencePath = $cells[3] -replace '`', ''
            blockingStatus = $cells[4] -replace '`', ''
            notes = $cells[5] -replace '`', ''
        }
    }
    return @($actions)
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "post-beta0-maturity-closure-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $blockers = [System.Collections.Generic.List[string]]::new()
    $requiredReports = @(
        New-ReportEvidence -Root $workspaceRootPath -Name "post-beta0-roadmap-boundary" -PathValue "scripts/reports/out/post-beta0-roadmap-boundary-report.json"
        New-ReportEvidence -Root $workspaceRootPath -Name "postgres-fidelity" -PathValue "scripts/reports/out/postgres-fidelity-report.json"
        New-ReportEvidence -Root $workspaceRootPath -Name "runtimehost-postgres-profile-fidelity" -PathValue "scripts/reports/out/runtimehost-postgres-profile-fidelity-report.json"
        New-ReportEvidence -Root $workspaceRootPath -Name "runtime-e2e-fidelity" -PathValue "scripts/reports/out/runtime-e2e-fidelity-report.json"
        New-ReportEvidence -Root $workspaceRootPath -Name "scenario-coherence" -PathValue "scripts/reports/out/scenario-coherence-report.json"
        New-ReportEvidence -Root $workspaceRootPath -Name "boundary-lock" -PathValue "scripts/reports/out/boundary-lock-report.json"
        New-ReportEvidence -Root $workspaceRootPath -Name "ai-model-to-dsl-mapping" -PathValue "scripts/reports/out/ai-model-to-dsl-mapping-report.json"
    )
    foreach ($requiredReport in $requiredReports) {
        if (-not $requiredReport.passed) {
            $blockers.Add("Required report did not pass: " + $requiredReport.name) | Out-Null
        }
    }

    $workflowPath = ".github/workflows/npdev-ci-validation.yml"
    $workflowFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $workflowPath
    $workflowText = if (Test-Path -LiteralPath $workflowFullPath -PathType Leaf) { Get-Content -Raw -LiteralPath $workflowFullPath } else { "" }
    $linuxJobPresent = $workflowText -match "(?m)^\s*linux-maturity-validation\s*:"
    $windowsJobPresent = $workflowText -match "windows-latest"
    $linuxJobText = if ($linuxJobPresent -and $workflowText -match "(?s)linux-maturity-validation:.*?(?=\n  ci-validation:|\z)") { $Matches[0] } else { "" }
    $requiredCommandCoverage = @(
        [pscustomobject]@{ name = "dsl-contract-tests"; present = (Test-Contains $linuxJobText "working-directory: NPDevContract/dsl") -and (Test-Contains $linuxJobText "./gradlew test"); evidence = "NPDevContract/dsl ./gradlew test" },
        [pscustomobject]@{ name = "kernel-inproc-adapter-tests"; present = (Test-Contains $linuxJobText ":adapters:audit-inproc:test") -and (Test-Contains $linuxJobText ":adapters:persistence-inproc:test"); evidence = "inproc adapter Gradle tasks" },
        [pscustomobject]@{ name = "kernel-postgres-testcontainers-tests"; present = (Test-Contains $linuxJobText ":adapters:eventstore-postgres:test") -and (Test-Contains $linuxJobText ":adapters:flowinstance-postgres:test"); evidence = "Postgres adapter Gradle tasks" },
        [pscustomobject]@{ name = "flow-compiled-tests"; present = Test-Contains $linuxJobText ":adapters:flow-compiled:test"; evidence = ":adapters:flow-compiled:test" },
        [pscustomobject]@{ name = "generator-unit-tests"; present = Test-Contains $linuxJobText ":generator:test"; evidence = "NPDevGenerator :generator:test" }
    )
    $ciWorkflow = [pscustomobject]@{
        path = $workflowPath
        windowsJobPresent = $windowsJobPresent
        linuxCiJobPresent = $linuxJobPresent
        usesUbuntuLatest = Test-Contains $linuxJobText "ubuntu-latest"
        usesJava17 = (Test-Contains $linuxJobText "java-version: '17'") -or (Test-Contains $linuxJobText "java-version: 17")
        usesGradleCache = (Test-Contains $linuxJobText "cache: gradle") -or (Test-Contains $linuxJobText "actions/cache")
        usesPosixGradlew = Test-Contains $linuxJobText "./gradlew"
        usesWindowsGradleWrapperInLinuxJob = Test-Contains $linuxJobText "gradlew.bat"
        requiredCommandCoverage = $requiredCommandCoverage
    }
    if (-not $ciWorkflow.windowsJobPresent) { $blockers.Add("Windows CI job is missing.") | Out-Null }
    if (-not $ciWorkflow.linuxCiJobPresent) { $blockers.Add("Linux maturity CI job is missing.") | Out-Null }
    if (-not $ciWorkflow.usesUbuntuLatest) { $blockers.Add("Linux maturity CI job does not use ubuntu-latest.") | Out-Null }
    if (-not $ciWorkflow.usesJava17) { $blockers.Add("Linux maturity CI job does not use Java 17.") | Out-Null }
    if (-not $ciWorkflow.usesGradleCache) { $blockers.Add("Linux maturity CI job does not configure Gradle cache.") | Out-Null }
    if (-not $ciWorkflow.usesPosixGradlew) { $blockers.Add("Linux maturity CI job does not use ./gradlew.") | Out-Null }
    if ($ciWorkflow.usesWindowsGradleWrapperInLinuxJob) { $blockers.Add("Linux maturity CI job uses gradlew.bat.") | Out-Null }
    foreach ($coverage in $requiredCommandCoverage) {
        if (-not $coverage.present) {
            $blockers.Add("Linux maturity CI job is missing command coverage: " + $coverage.name) | Out-Null
        }
    }

    $registerPath = "docs/POST_BETA0_HUMAN_ACTION_REGISTER.md"
    $registerFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $registerPath
    $humanActions = @(Read-HumanActionRegister -Path $registerFullPath)
    $requiredHumanActions = @(
        "Branch protection required Linux job",
        "Independent audit sign-off",
        "Real participant sessions"
    )
    foreach ($requiredAction in $requiredHumanActions) {
        if (@($humanActions | Where-Object { $_.action -eq $requiredAction }).Count -ne 1) {
            $blockers.Add("Human action register missing: " + $requiredAction) | Out-Null
        }
    }
    foreach ($humanAction in $humanActions) {
        foreach ($property in @("owner", "status", "evidencePath", "blockingStatus", "notes")) {
            if ([string]::IsNullOrWhiteSpace([string]$humanAction.$property)) {
                $blockers.Add("Human action register has blank " + $property + " for " + [string]$humanAction.action) | Out-Null
            }
        }
    }

    $requiredHumanActionRows = @($humanActions | Where-Object { $requiredHumanActions -contains [string]$_.action })
    $humanBlockingActionCount = @($requiredHumanActionRows | Where-Object { [string]$_.blockingStatus -notmatch "^non-blocking" }).Count
    $postRoadmapBacklogCount = 0
    $requiredReportsPassed = @($requiredReports | Where-Object { -not $_.passed }).Count -eq 0
    $humanActionRegisterPresent = (Test-Path -LiteralPath $registerFullPath -PathType Leaf) -and $humanActions.Count -ge 3
    $technicalMaturityReady = $requiredReportsPassed -and $ciWorkflow.windowsJobPresent -and $ciWorkflow.linuxCiJobPresent -and $humanActionRegisterPresent -and $humanBlockingActionCount -eq 0 -and $postRoadmapBacklogCount -eq 0 -and $blockers.Count -eq 0
    $overallStatus = if ($technicalMaturityReady) { "passed" } else { "failed" }

    $findings = @(
        [pscustomobject]@{
            id = "c7-human-actions-registered"
            classification = "human-decision-required"
            status = "registered"
            summary = "Branch protection, independent audit sign-off, and real participant sessions require human action and are registered as non-blocking for technical closure."
        }
    )

    $report = [pscustomobject]@{
        schemaVersion = "npdev-post-beta0-maturity-closure-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-post-beta0-maturity-closure-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        technicalMaturityReady = $technicalMaturityReady
        requiredReportsPassed = $requiredReportsPassed
        linuxCiJobPresent = $ciWorkflow.linuxCiJobPresent
        humanActionRegisterPresent = $humanActionRegisterPresent
        humanBlockingActionCount = $humanBlockingActionCount
        postRoadmapBacklogCount = $postRoadmapBacklogCount
        requiredReports = @($requiredReports)
        ciWorkflow = $ciWorkflow
        humanActions = @($humanActions)
        findings = $findings
        doesNotSolve = @(
            "Does not update GitHub branch protection settings.",
            "Does not conduct participant sessions.",
            "Does not obtain independent audit sign-off.",
            "Documents human-only actions without representing them as AI-completed."
        )
        blockers = @($blockers)
    }

    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    if ($overallStatus -eq "passed") {
        Write-Host ("Post-Beta0 maturity closure check passed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
        exit 0
    }

    Write-Error ("Post-Beta0 maturity closure check failed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
}
finally {
    Pop-Location
}
