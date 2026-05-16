param(
    [switch]$ContinueOnFailure,
    [string]$ReportPath = "scripts/reports/out/beta0-final-release-check-report.json",
    [string]$RunId = "",
    [switch]$SkipFastPreflight,
    [string]$PreflightReportPath = "scripts/reports/out/beta0-final-release-preflight-report.json",
    [string]$WorkspaceRoot = "",
    [string]$ReleaseEvidenceArchiveRoot = "",
    [switch]$SkipReleaseEvidencePublish
)

$ErrorActionPreference = "Stop"

function Write-ReleaseCheckMessage {
    param([string]$Message)
    Write-Host ("[" + (Get-Date).ToString("HH:mm:ss") + "] " + $Message)
}

function Invoke-Gate {
    param(
        [string]$Name,
        [string]$Command,
        [int]$Index = 0,
        [int]$Total = 0,
        [bool]$AlwaysContinue = $false,
        [bool]$ExpectedNonzero = $false
    )
    $startedAt = (Get-Date).ToUniversalTime()
    $prefix = if ($Index -gt 0 -and $Total -gt 0) { "Gate " + $Index + "/" + $Total + " " } else { "" }
    Write-ReleaseCheckMessage ($prefix + "START " + $Name + " -> " + $Command)
    $ErrorActionPreference = "Continue"
    & pwsh -NoProfile -File $Command -RunId $RunId
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $finishedAt = (Get-Date).ToUniversalTime()
    $durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
    $status = if ($exitCode -eq 0) { "passed" } elseif ($ExpectedNonzero) { "failed-as-expected" } else { "failed" }
    Write-ReleaseCheckMessage ($prefix + "END   " + $Name + " => " + $status + " (exit " + $exitCode + ", " + $durationSeconds + "s)")
    $result = [pscustomobject]@{
        name = $Name
        command = ($Command + " -RunId " + $RunId)
        status = $status
        exitCode = $exitCode
        blocking = (-not $ExpectedNonzero)
        expectedNonzero = $ExpectedNonzero
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = $durationSeconds
    }
    $script:gateResults += $result
    if ($exitCode -ne 0 -and -not $ContinueOnFailure -and -not $AlwaysContinue) {
        throw "Gate failed: $Name"
    }
    return $result
}

function Invoke-PostVerificationWorkspaceCleanup {
    $startedAt = (Get-Date).ToUniversalTime()
    $cleanupCommand = "scripts/hygiene/clean-rebuildable-artifacts.ps1"
    $slimnessCommand = "scripts/hygiene/Test-WorkspaceSlimness.ps1"

    Write-ReleaseCheckMessage "Post-verification cleanup START -> scripts/hygiene/clean-rebuildable-artifacts.ps1"
    $ErrorActionPreference = "Continue"
    & pwsh -NoProfile -File $cleanupCommand
    $cleanupExitCode = $LASTEXITCODE
    if ($null -eq $cleanupExitCode) { $cleanupExitCode = 0 }
    Write-ReleaseCheckMessage ("Post-verification cleanup END   => exit " + $cleanupExitCode)

    $slimnessExitCode = $null
    if ($cleanupExitCode -eq 0) {
        Write-ReleaseCheckMessage "Workspace slimness START -> scripts/hygiene/Test-WorkspaceSlimness.ps1"
        & pwsh -NoProfile -File $slimnessCommand -RunId $RunId
        $slimnessExitCode = $LASTEXITCODE
        if ($null -eq $slimnessExitCode) { $slimnessExitCode = 0 }
        Write-ReleaseCheckMessage ("Workspace slimness END   => exit " + $slimnessExitCode)
    }
    $ErrorActionPreference = "Stop"

    $finishedAt = (Get-Date).ToUniversalTime()
    $status = if ($cleanupExitCode -eq 0 -and $slimnessExitCode -eq 0) { "passed" } else { "failed" }
    $script:gateResults += [pscustomobject]@{
        name = "post-verification-workspace-cleanup"
        command = ($cleanupCommand + "; " + $slimnessCommand + " -RunId " + $RunId)
        status = $status
        exitCode = if ($cleanupExitCode -ne 0) { $cleanupExitCode } else { $slimnessExitCode }
        blocking = $true
        expectedNonzero = $false
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
    }

    if ($status -ne "passed") {
        throw "Post-verification workspace cleanup failed."
    }
}

function Convert-GitStatusLineToPath {
    param([string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) { return "" }
    $value = $Line
    if ($value.Length -ge 4) {
        $value = $value.Substring(3)
    }
    $value = $value.Trim()
    if ($value -match " -> ") {
        $parts = $value -split " -> "
        $value = $parts[$parts.Count - 1]
    }
    $value = $value.Trim('"') -replace "\\", "/"
    return $value
}

function Test-AllowedGeneratedEvidenceDirtyPath {
    param([string]$PathValue)
    $normalized = ([string]$PathValue) -replace "\\", "/"
    if ($normalized -match "^scripts/reports/out/[^/]+\.(json|log)$") { return $true }
    if ($normalized -match "^scripts/reports/releases/") { return $true }
    return $false
}

function Add-PreflightCheck {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [System.Collections.Generic.List[string]]$Blockers,
        [string]$Name,
        [string]$Status,
        [bool]$Blocking,
        [string]$Message = "",
        [object[]]$Details = @(),
        [string]$Remediation = ""
    )

    $Checks.Add([pscustomobject]@{
        name = $Name
        status = $Status
        blocking = $Blocking
        message = $Message
        details = @($Details)
        remediation = $Remediation
    }) | Out-Null

    if ($Blocking -and $Status -ne "passed") {
        $blockerMessage = if ([string]::IsNullOrWhiteSpace($Message)) { $Name } else { $Name + ": " + $Message }
        $Blockers.Add($blockerMessage) | Out-Null
    }
}

function Test-PreflightCommandAvailable {
    param([string]$Name)
    return ($null -ne (Get-Command $Name -ErrorAction SilentlyContinue))
}

function Invoke-PreflightNativeCommand {
    param(
        [string]$Command,
        [string[]]$Arguments = @()
    )

    try {
        $ErrorActionPreference = "Continue"
        $output = & $Command @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) { $exitCode = 0 }
        $ErrorActionPreference = "Stop"
        return [pscustomobject]@{
            exitCode = [int]$exitCode
            output = (($output | Out-String).TrimEnd())
        }
    }
    catch {
        $ErrorActionPreference = "Stop"
        return [pscustomobject]@{
            exitCode = 999
            output = $_.Exception.Message
        }
    }
}

function Write-PreflightReport {
    param(
        [string]$Path,
        [string]$RunIdValue,
        [string]$WorkspaceRootValue,
        [string]$Status,
        [object[]]$Checks,
        [string[]]$Blockers,
        [datetime]$StartedAt,
        [datetime]$FinishedAt
    )

    $report = [pscustomobject]@{
        schemaVersion = "npdev-beta0-final-release-preflight-report.v1"
        runId = $RunIdValue
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-beta0-final-release-check_v2.ps1"
        workspaceRoot = $WorkspaceRootValue
        overallStatus = $Status
        durationSeconds = [int]([DateTimeOffset]$FinishedAt - [DateTimeOffset]$StartedAt).TotalSeconds
        checks = @($Checks)
        blockers = @($Blockers)
        generatedEvidenceDirtinessPolicy = "Dirty paths under scripts/reports/out/*.json, scripts/reports/out/*.log, and scripts/reports/releases/** are generated release evidence and do not block the fast preflight; source/temp files inside the repository do block it."
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-Beta0FinalReleaseFastPreflight {
    param(
        [string]$WorkspaceRootValue,
        [string]$RunIdValue,
        [string]$PreflightReportPathValue
    )

    $startedAt = (Get-Date).ToUniversalTime()
    Write-ReleaseCheckMessage "Fast preflight START -> common release blockers before long gates"
    $checks = [System.Collections.Generic.List[object]]::new()
    $blockers = [System.Collections.Generic.List[string]]::new()

    $requiredCommands = @("pwsh", "git", "docker", "node", "npm", "java", "javac")
    foreach ($commandName in $requiredCommands) {
        if (Test-PreflightCommandAvailable $commandName) {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name ("command-available:" + $commandName) -Status "passed" -Blocking $true -Message ($commandName + " is available on PATH.")
        }
        else {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name ("command-available:" + $commandName) -Status "failed" -Blocking $true -Message ($commandName + " is not available on PATH.") -Remediation ("Install/configure " + $commandName + " before running Beta0 final release evidence.")
        }
    }

    if (Test-PreflightCommandAvailable "git") {
        $gitRootResult = Invoke-PreflightNativeCommand -Command "git" -Arguments @("rev-parse", "--show-toplevel")
        if ($gitRootResult.exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($gitRootResult.output)) {
            $actualRoot = [System.IO.Path]::GetFullPath($gitRootResult.output.Trim())
            $expectedRoot = [System.IO.Path]::GetFullPath($WorkspaceRootValue)
            $rootTrimChars = @([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
            $actualRootNormalized = $actualRoot.TrimEnd($rootTrimChars)
            $expectedRootNormalized = $expectedRoot.TrimEnd($rootTrimChars)
            if ([string]::Equals($actualRootNormalized, $expectedRootNormalized, [System.StringComparison]::OrdinalIgnoreCase)) {
                Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-repo-root" -Status "passed" -Blocking $true -Message "Current directory is the repository root."
            }
            else {
                Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-repo-root" -Status "failed" -Blocking $true -Message ("Workspace root mismatch. Git root is " + $actualRoot + "; script workspace root is " + $expectedRoot + ".") -Remediation "Run from the actual repository root or pass -WorkspaceRoot with the correct checkout path."
            }
        }
        else {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-repo-root" -Status "failed" -Blocking $true -Message "Unable to resolve git repository root." -Details @($gitRootResult.output) -Remediation "Run inside a valid git checkout."
        }

        $headResult = Invoke-PreflightNativeCommand -Command "git" -Arguments @("rev-parse", "HEAD")
        if ($headResult.exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($headResult.output)) {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-commit-identity" -Status "passed" -Blocking $true -Message ("HEAD commit available: " + $headResult.output.Trim())
        }
        else {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-commit-identity" -Status "failed" -Blocking $true -Message "HEAD commit identity is unavailable." -Details @($headResult.output) -Remediation "Commit or repair the repository before release closure."
        }

        $statusResult = Invoke-PreflightNativeCommand -Command "git" -Arguments @("status", "--porcelain=v1")
        if ($statusResult.exitCode -ne 0) {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-status" -Status "failed" -Blocking $true -Message "git status failed." -Details @($statusResult.output) -Remediation "Fix git repository state before release closure."
        }
        else {
            $lines = @()
            if (-not [string]::IsNullOrWhiteSpace($statusResult.output)) {
                $lines = @($statusResult.output -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            }

            $sourceDirtyPaths = @()
            $allowedGeneratedEvidenceDirtyPaths = @()
            foreach ($line in $lines) {
                $pathValue = Convert-GitStatusLineToPath $line
                if (Test-AllowedGeneratedEvidenceDirtyPath $pathValue) {
                    $allowedGeneratedEvidenceDirtyPaths += $pathValue
                }
                else {
                    $sourceDirtyPaths += $pathValue
                }
            }

            if (@($sourceDirtyPaths).Count -eq 0) {
                Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-source-dirtiness" -Status "passed" -Blocking $true -Message ("No blocking source dirtiness. Allowed generated evidence dirty files: " + [string](@($allowedGeneratedEvidenceDirtyPaths).Count) + ".") -Details @($allowedGeneratedEvidenceDirtyPaths)
            }
            else {
                Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "git-source-dirtiness" -Status "failed" -Blocking $true -Message ("Blocking source/workspace dirtiness found: " + [string](@($sourceDirtyPaths).Count) + " path(s).") -Details @($sourceDirtyPaths) -Remediation "Remove, move outside the repo, or commit these paths before running long evidence."
            }
        }
    }

    $requiredPaths = @(
        "scripts/hygiene/clean-rebuildable-artifacts.ps1",
        "scripts/hygiene/Test-WorkspaceSlimness.ps1",
        "scripts/policy/beta-release-gate-policy.json",
        "scripts/policy/beta0-scope.json",
        "scripts/policy/beta0-release-truth-table.json",
        "scripts/reports/blocking-report-manifest.json",
        "scripts/quality/json-schema-validator/package.json",
        "scripts/quality/json-schema-validator/validate-json-schema.mjs",
        "scripts/quality/run-json-schema-validator-tests.ps1",
        "scripts/quality/run-ai-schema-validation.ps1",
        "scripts/quality/run-ai-contract-normalizer-tests.ps1",
        "scripts/quality/run-controlled-command-runner-tests.ps1",
        "scripts/quality/run-ai-rest-smoke-verifier-tests.ps1",
        "scripts/quality/run-runtime-null-context-tests.ps1",
        "scripts/quality/run-runtimehost-staged-jar-preflight-tests.ps1",
        "scripts/quality/run-runtimehost-staged-jar-preflight.ps1",
        "scripts/quality/run-frontend-gate.ps1",
        "scripts/quality/run-frontend-gate-tests.ps1",
        "scripts/quality/run-docker-linux-proof-tests.ps1",
        "scripts/quality/run-docker-linux-proof.ps1",
        "scripts/quality/run-sample-matrix-tests.ps1",
        "scripts/quality/run-scope-policy-enforcement-tests.ps1",
        "scripts/quality/run-direct-evidence-hardening-tests.ps1",
        "scripts/quality/run-runbook-workflow-alignment-tests.ps1",
        "scripts/quality/run-sample-matrix.ps1",
        "scripts/quality/run-ai-beta-gate.ps1",
        "scripts/quality/run-expanded-beta0-evidence.ps1",
        "scripts/quality/run-structured-command-surface-alignment.ps1",
        "scripts/quality/run-trusted-source-beta0-proof-tests.ps1",
        "scripts/quality/run-trusted-source-beta0-proof.ps1",
        "scripts/quality/run-doc-entrypoint-validation-tests.ps1",
        "scripts/quality/run-report-schema-validation.ps1",
        "scripts/quality/run-doc-entrypoint-validation.ps1",
        "scripts/quality/run-beta-release-gate.ps1",
        "scripts/quality/run-final-regression-coverage-audit-tests.ps1",
        "scripts/quality/run-final-regression-coverage-audit.ps1",
        "scripts/quality/run-report-provenance-tests.ps1",
        "scripts/quality/run-beta0-final-closure-gate.ps1"
    ) | Sort-Object -Unique

    $missingPaths = @($requiredPaths | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) })
    if (@($missingPaths).Count -eq 0) {
        Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "required-files-present" -Status "passed" -Blocking $true -Message ("All required scripts/policies exist: " + [string](@($requiredPaths).Count) + " file(s).")
    }
    else {
        Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "required-files-present" -Status "failed" -Blocking $true -Message ("Missing required scripts/policies: " + [string](@($missingPaths).Count) + " file(s).") -Details @($missingPaths) -Remediation "Restore the missing file(s) before running long evidence."
    }

    $policyPaths = @(
        "scripts/policy/beta-release-gate-policy.json",
        "scripts/policy/beta0-scope.json",
        "scripts/policy/beta0-release-truth-table.json"
    )
    $invalidPolicyDetails = @()
    foreach ($policyPath in $policyPaths) {
        if (Test-Path -LiteralPath $policyPath -PathType Leaf) {
            try {
                $null = Get-Content -Raw -LiteralPath $policyPath | ConvertFrom-Json
            }
            catch {
                $invalidPolicyDetails += ($policyPath + ": " + $_.Exception.Message)
            }
        }
    }
    if (@($invalidPolicyDetails).Count -eq 0) {
        Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "policy-json-parse" -Status "passed" -Blocking $true -Message "Core release policy JSON files parse successfully."
    }
    else {
        Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "policy-json-parse" -Status "failed" -Blocking $true -Message "One or more core release policy JSON files do not parse." -Details @($invalidPolicyDetails) -Remediation "Fix JSON syntax before running long evidence."
    }

    try {
        $betaPolicy = Get-Content -Raw -LiteralPath "scripts/policy/beta-release-gate-policy.json" | ConvertFrom-Json
        $scopePolicy = Get-Content -Raw -LiteralPath "scripts/policy/beta0-scope.json" | ConvertFrom-Json
        $truthTable = Get-Content -Raw -LiteralPath "scripts/policy/beta0-release-truth-table.json" | ConvertFrom-Json
        $policyProblems = @()
        if ([string]$betaPolicy.schemaVersion -ne "npdev-beta-release-gate-policy.v1") { $policyProblems += "beta-release-gate-policy schemaVersion mismatch" }
        if ([string]$betaPolicy.release -ne "ai-only-beta-0") { $policyProblems += "beta-release-gate-policy release mismatch" }
        if ([string]$scopePolicy.schemaVersion -ne "npdev-beta0-scope.v2") { $policyProblems += "beta0-scope schemaVersion mismatch" }
        if ([string]$scopePolicy.release -ne "ai-only-beta-0") { $policyProblems += "beta0-scope release mismatch" }
        if (-not [bool]$scopePolicy.dockerRequiredForBeta0) { $policyProblems += "beta0-scope does not require Docker for Beta0" }
        if ([string]$truthTable.schemaVersion -ne "npdev-beta0-release-truth-table.v1") { $policyProblems += "beta0-release-truth-table schemaVersion mismatch" }
        if (@($policyProblems).Count -eq 0) {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "release-policy-basics" -Status "passed" -Blocking $true -Message "Core release policy identity matches ai-only-beta-0."
        }
        else {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "release-policy-basics" -Status "failed" -Blocking $true -Message "Core release policy identity has unexpected values." -Details @($policyProblems) -Remediation "Restore the Beta0 policy files before running long evidence."
        }
    }
    catch {
        Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "release-policy-basics" -Status "failed" -Blocking $true -Message ("Unable to inspect release policy basics: " + $_.Exception.Message) -Remediation "Restore readable Beta0 policy files before running long evidence."
    }

    $outRoot = Join-Path $WorkspaceRootValue "scripts/reports/out"
    try {
        New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
        $tempWritePath = Join-Path $outRoot (".preflight-write-test-" + $RunIdValue + ".tmp")
        Set-Content -LiteralPath $tempWritePath -Value "ok" -Encoding UTF8
        Remove-Item -LiteralPath $tempWritePath -Force
        Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "reports-out-writable" -Status "passed" -Blocking $true -Message "scripts/reports/out is writable."
    }
    catch {
        Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "reports-out-writable" -Status "failed" -Blocking $true -Message ("Unable to write to scripts/reports/out: " + $_.Exception.Message) -Remediation "Fix directory permissions or close tools locking files."
    }

    if (Test-PreflightCommandAvailable "docker") {
        $dockerResult = Invoke-PreflightNativeCommand -Command "docker" -Arguments @("info", "--format", "{{.ServerVersion}}")
        if ($dockerResult.exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($dockerResult.output)) {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "docker-daemon-ready" -Status "passed" -Blocking $true -Message ("Docker daemon is reachable. ServerVersion: " + $dockerResult.output.Trim())
        }
        else {
            Add-PreflightCheck -Checks $checks -Blockers $blockers -Name "docker-daemon-ready" -Status "failed" -Blocking $true -Message "Docker CLI exists but the daemon is not reachable." -Details @($dockerResult.output) -Remediation "Start Docker Desktop / Docker daemon before running the long Docker/Linux proof."
        }
    }

    $finishedAt = (Get-Date).ToUniversalTime()
    $status = if ($blockers.Count -eq 0) { "passed" } else { "failed" }
    Write-PreflightReport -Path $PreflightReportPathValue -RunIdValue $RunIdValue -WorkspaceRootValue $WorkspaceRootValue -Status $status -Checks @($checks) -Blockers @($blockers) -StartedAt $startedAt -FinishedAt $finishedAt

    $script:gateResults += [pscustomobject]@{
        name = "fast-preflight"
        command = "embedded preflight checks before Gate 1/30"
        status = $status
        exitCode = if ($status -eq "passed") { 0 } else { 1 }
        blocking = $true
        expectedNonzero = $false
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
        reportPath = $PreflightReportPathValue
        blockers = @($blockers)
    }

    Write-ReleaseCheckMessage ("Fast preflight END   => " + $status + " (blockers " + $blockers.Count + ", report " + $PreflightReportPathValue + ")")
    if ($status -ne "passed") {
        throw "Fast preflight failed. Report: $PreflightReportPathValue"
    }
}

function Write-EarlyFailureReleaseCheckReport {
    param(
        [string]$Path,
        [string]$RunIdValue,
        [string]$WorkspaceRootValue,
        [object[]]$GateResults,
        [string]$FailureMessage
    )

    $report = [pscustomobject]@{
        schemaVersion = "npdev-beta0-final-release-check-report.v1"
        runId = $RunIdValue
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-beta0-final-release-check_v2.ps1"
        workspaceRoot = $WorkspaceRootValue
        overallStatus = "failed"
        candidateReady = $false
        releaseReady = $false
        provenanceReady = $false
        officialReleaseEligible = $false
        beta0TagAllowed = $false
        failedBeforeLongGates = $true
        preflightReportPath = $PreflightReportPath
        failureMessage = $FailureMessage
        gates = @($GateResults)
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $Path -Encoding UTF8
}



function Get-Beta0RelativePathIfUnderWorkspace {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRootValue,
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    $workspaceFull = [System.IO.Path]::GetFullPath($WorkspaceRootValue)
    $pathFull = [System.IO.Path]::GetFullPath($PathValue)
    if (-not $workspaceFull.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $workspaceFull = $workspaceFull + [System.IO.Path]::DirectorySeparatorChar
    }

    if ($pathFull.StartsWith($workspaceFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return (($pathFull.Substring($workspaceFull.Length)) -replace "\\", "/")
    }

    return $pathFull
}

function Resolve-Beta0PathAgainstWorkspace {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRootValue,
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }

    return [System.IO.Path]::GetFullPath((Join-Path $WorkspaceRootValue $PathValue))
}

function Copy-Beta0DirectoryContents {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceDirectory,
        [Parameter(Mandatory = $true)]
        [string]$DestinationDirectory
    )

    if (-not (Test-Path -LiteralPath $SourceDirectory -PathType Container)) {
        throw ("Source directory not found: " + $SourceDirectory)
    }

    New-Item -ItemType Directory -Force -Path $DestinationDirectory | Out-Null
    Get-ChildItem -LiteralPath $SourceDirectory -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $DestinationDirectory -Recurse -Force
    }
}

function Copy-Beta0RequiredEvidenceFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,
        [Parameter(Mandatory = $true)]
        [string]$DestinationPath,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
        throw ($Label + " not found: " + $SourcePath)
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $DestinationPath) | Out-Null
    Copy-Item -LiteralPath $SourcePath -Destination $DestinationPath -Force
}

function Publish-Beta0FinalReleaseEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRootValue,
        [Parameter(Mandatory = $true)]
        [string]$RunIdValue,
        [Parameter(Mandatory = $true)]
        [string]$ReportsOutRoot,
        [Parameter(Mandatory = $true)]
        [string]$ReportPathValue,
        [Parameter(Mandatory = $true)]
        [string]$ReleaseEvidenceArchiveRootValue
    )

    $archiveRoot = Resolve-Beta0PathAgainstWorkspace -WorkspaceRootValue $WorkspaceRootValue -PathValue $ReleaseEvidenceArchiveRootValue
    $safeRunId = ([string]$RunIdValue) -replace '[\\/:*?"<>|]', '-'
    if ([string]::IsNullOrWhiteSpace($safeRunId)) {
        $safeRunId = "beta0-final-release-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $releaseEvidenceRoot = Join-Path $archiveRoot $safeRunId
    $structuredReportsOutRoot = Join-Path $releaseEvidenceRoot "scripts\reports\out"

    if (Test-Path -LiteralPath $releaseEvidenceRoot) {
        throw ("Release evidence root already exists; refusing to delete or overwrite existing evidence: " + $releaseEvidenceRoot)
    }

    New-Item -ItemType Directory -Force -Path $releaseEvidenceRoot | Out-Null
    Copy-Beta0DirectoryContents -SourceDirectory $ReportsOutRoot -DestinationDirectory $structuredReportsOutRoot

    $manifestSource = Join-Path $ReportsOutRoot "beta-release-evidence-manifest.json"
    $betaReportSource = Join-Path $ReportsOutRoot "beta-release-gate-report.json"
    $finalReportSource = Resolve-Beta0PathAgainstWorkspace -WorkspaceRootValue $WorkspaceRootValue -PathValue $ReportPathValue
    $closureReportSource = Join-Path $ReportsOutRoot "beta0-final-closure-report.json"

    Copy-Beta0RequiredEvidenceFile -SourcePath $manifestSource -DestinationPath (Join-Path $releaseEvidenceRoot "evidence-manifest.json") -Label "Release evidence manifest"
    Copy-Beta0RequiredEvidenceFile -SourcePath $manifestSource -DestinationPath (Join-Path $releaseEvidenceRoot "beta-release-evidence-manifest.json") -Label "Release evidence manifest"
    Copy-Beta0RequiredEvidenceFile -SourcePath $betaReportSource -DestinationPath (Join-Path $releaseEvidenceRoot "beta-release-gate-report.json") -Label "Beta release gate report"
    Copy-Beta0RequiredEvidenceFile -SourcePath $finalReportSource -DestinationPath (Join-Path $releaseEvidenceRoot "beta0-final-release-check-report.json") -Label "Beta0 final release check report"

    if (Test-Path -LiteralPath $closureReportSource -PathType Leaf) {
        Copy-Item -LiteralPath $closureReportSource -Destination (Join-Path $releaseEvidenceRoot "beta0-final-closure-report.json") -Force
    }

    $publishReport = [pscustomobject]@{
        schemaVersion = "npdev-beta0-release-evidence-publish-report.v1"
        runId = $RunIdValue
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        workspaceRoot = $WorkspaceRootValue
        reportsOutSource = (Get-Beta0RelativePathIfUnderWorkspace -WorkspaceRootValue $WorkspaceRootValue -PathValue $ReportsOutRoot)
        releaseEvidenceRoot = (Get-Beta0RelativePathIfUnderWorkspace -WorkspaceRootValue $WorkspaceRootValue -PathValue $releaseEvidenceRoot)
        statezipExistingEvidenceRoot = "last"
        statezipExpectedStructuredReportsOut = "scripts/reports/out"
        status = "published"
        requiredFlatFiles = @(
            "evidence-manifest.json",
            "beta-release-evidence-manifest.json",
            "beta-release-gate-report.json",
            "beta0-final-release-check-report.json"
        )
    }

    $publishReportPath = Join-Path $releaseEvidenceRoot "release-evidence-publish-report.json"
    $publishReport | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $publishReportPath -Encoding UTF8
    Copy-Item -LiteralPath $publishReportPath -Destination (Join-Path $structuredReportsOutRoot "release-evidence-publish-report.json") -Force

    return $releaseEvidenceRoot
}

function Invoke-PostEvidenceWorkspaceCleanlinessValidation {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunIdValue
    )

    $startedAt = (Get-Date).ToUniversalTime()
    $command = "scripts/hygiene/Test-WorkspaceSlimness.ps1"
    $reportPath = "scripts/reports/out/workspace-cleanliness-report.json"
    Write-ReleaseCheckMessage "Post-evidence workspace cleanliness START -> scripts/hygiene/Test-WorkspaceSlimness.ps1"
    $ErrorActionPreference = "Continue"
    & pwsh -NoProfile -File $command -RunId $RunIdValue -ReportPath $reportPath -CleanTransientReportTemp:$false
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) { $exitCode = 0 }
    $ErrorActionPreference = "Stop"
    $finishedAt = (Get-Date).ToUniversalTime()
    $status = if ($exitCode -eq 0) { "passed" } else { "failed" }
    Write-ReleaseCheckMessage ("Post-evidence workspace cleanliness END   => " + $status + " (exit " + $exitCode + ")")

    return [pscustomobject]@{
        status = $status
        exitCode = $exitCode
        reportPath = $reportPath
        command = $command + " -RunId " + $RunIdValue + " -ReportPath " + $reportPath + " -CleanTransientReportTemp:`$false"
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
    }
}

function Resolve-Beta0WorkspaceRoot {
    param([string]$ExplicitWorkspaceRoot)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitWorkspaceRoot)) {
        return (Resolve-Path -LiteralPath $ExplicitWorkspaceRoot).Path
    }

    # Preferred default: derive the repo root from this script location.
    # Expected location: <repo>/scripts/quality/run-beta0-final-release-check_v2.ps1
    if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        $candidate = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and
            (Test-Path -LiteralPath (Join-Path $candidate ".git")) -and
            (Test-Path -LiteralPath (Join-Path $candidate "scripts/quality/run-beta-release-gate.ps1"))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    # Fallback: current directory, preserving compatibility with the original script.
    return (Resolve-Path -LiteralPath ".").Path
}

$workspaceRoot = Resolve-Beta0WorkspaceRoot -ExplicitWorkspaceRoot $WorkspaceRoot
Set-Location -LiteralPath $workspaceRoot
if ([string]::IsNullOrWhiteSpace($ReleaseEvidenceArchiveRoot)) {
    $workspaceItem = Get-Item -LiteralPath $workspaceRoot
    $outsideRepoRoot = Join-Path $workspaceItem.Parent.FullName ($workspaceItem.Name + "__OutsideRepo")
    $ReleaseEvidenceArchiveRoot = Join-Path $outsideRepoRoot "release-evidence\releases"
}
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "beta0-final-release-check-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
Write-ReleaseCheckMessage ("Beta 0 final release check v2 starting. RunId: " + $RunId)
Write-ReleaseCheckMessage ("Workspace: " + $workspaceRoot)
$gateResults = @()
$failedEarly = $false

if (-not $SkipFastPreflight) {
    try {
        Invoke-Beta0FinalReleaseFastPreflight -WorkspaceRootValue $workspaceRoot -RunIdValue $RunId -PreflightReportPathValue $PreflightReportPath
    }
    catch {
        $failedEarly = $true
        Write-EarlyFailureReleaseCheckReport -Path $ReportPath -RunIdValue $RunId -WorkspaceRootValue $workspaceRoot -GateResults @($gateResults) -FailureMessage $_.Exception.Message
        Write-Error ("Beta 0 final release check v2 stopped before long gates. " + $_.Exception.Message + " Final report: " + $ReportPath)
        exit 1
    }
}
else {
    Write-ReleaseCheckMessage "Fast preflight SKIPPED by -SkipFastPreflight."
}

$outRoot = Join-Path $workspaceRoot "scripts/reports/out"
if (Test-Path -LiteralPath $outRoot -PathType Container) {
    Write-ReleaseCheckMessage ("Clearing previous JSON reports from " + $outRoot)
    Get-ChildItem -LiteralPath $outRoot -Filter "*.json" -File | Remove-Item -Force
}
else {
    Write-ReleaseCheckMessage ("Creating reports directory " + $outRoot)
    New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
}

$orderedGates = @(
    [pscustomobject]@{ name = "json-schema-validator-tests"; command = "scripts/quality/run-json-schema-validator-tests.ps1" },
    [pscustomobject]@{ name = "ai-schema-validation"; command = "scripts/quality/run-ai-schema-validation.ps1" },
    [pscustomobject]@{ name = "ai-contract-normalizer-tests"; command = "scripts/quality/run-ai-contract-normalizer-tests.ps1" },
    [pscustomobject]@{ name = "controlled-command-runner-tests"; command = "scripts/quality/run-controlled-command-runner-tests.ps1" },
    [pscustomobject]@{ name = "ai-rest-smoke-verifier-tests"; command = "scripts/quality/run-ai-rest-smoke-verifier-tests.ps1" },
    [pscustomobject]@{ name = "runtime-null-context-tests"; command = "scripts/quality/run-runtime-null-context-tests.ps1" },
    [pscustomobject]@{ name = "runtimehost-staged-jar-preflight-tests"; command = "scripts/quality/run-runtimehost-staged-jar-preflight-tests.ps1" },
    [pscustomobject]@{ name = "runtimehost-staged-jar-preflight"; command = "scripts/quality/run-runtimehost-staged-jar-preflight.ps1" },
    [pscustomobject]@{ name = "frontend-gate"; command = "scripts/quality/run-frontend-gate.ps1" },
    [pscustomobject]@{ name = "frontend-gate-tests"; command = "scripts/quality/run-frontend-gate-tests.ps1" },
    [pscustomobject]@{ name = "docker-linux-proof-tests"; command = "scripts/quality/run-docker-linux-proof-tests.ps1" },
    [pscustomobject]@{ name = "docker-linux-proof"; command = "scripts/quality/run-docker-linux-proof.ps1" },
    [pscustomobject]@{ name = "sample-matrix-tests"; command = "scripts/quality/run-sample-matrix-tests.ps1" },
    [pscustomobject]@{ name = "scope-policy-enforcement-tests"; command = "scripts/quality/run-scope-policy-enforcement-tests.ps1" },
    [pscustomobject]@{ name = "direct-evidence-hardening-tests"; command = "scripts/quality/run-direct-evidence-hardening-tests.ps1" },
    [pscustomobject]@{ name = "runbook-workflow-alignment-tests"; command = "scripts/quality/run-runbook-workflow-alignment-tests.ps1" },
    [pscustomobject]@{ name = "sample-matrix"; command = "scripts/quality/run-sample-matrix.ps1" },
    [pscustomobject]@{ name = "ai-beta-gate"; command = "scripts/quality/run-ai-beta-gate.ps1" },
    [pscustomobject]@{ name = "expanded-beta0-evidence"; command = "scripts/quality/run-expanded-beta0-evidence.ps1" },
    [pscustomobject]@{ name = "structured-command-surface-alignment"; command = "scripts/quality/run-structured-command-surface-alignment.ps1" },
    [pscustomobject]@{ name = "trusted-source-beta0-proof-tests"; command = "scripts/quality/run-trusted-source-beta0-proof-tests.ps1" },
    [pscustomobject]@{ name = "trusted-source-beta0-proof"; command = "scripts/quality/run-trusted-source-beta0-proof.ps1" },
    [pscustomobject]@{ name = "doc-entrypoint-validation-tests"; command = "scripts/quality/run-doc-entrypoint-validation-tests.ps1" },
    [pscustomobject]@{ name = "report-schema-validation"; command = "scripts/quality/run-report-schema-validation.ps1" },
    [pscustomobject]@{ name = "doc-entrypoint-validation"; command = "scripts/quality/run-doc-entrypoint-validation.ps1" },
    [pscustomobject]@{ name = "beta-release-gate-pre-audit"; command = "scripts/quality/run-beta-release-gate.ps1"; alwaysContinue = $true; expectedNonzero = $true },
    [pscustomobject]@{ name = "final-regression-coverage-audit-tests"; command = "scripts/quality/run-final-regression-coverage-audit-tests.ps1" },
    [pscustomobject]@{ name = "final-regression-coverage-audit"; command = "scripts/quality/run-final-regression-coverage-audit.ps1" },
    [pscustomobject]@{ name = "report-schema-validation-final"; command = "scripts/quality/run-report-schema-validation.ps1" },
    [pscustomobject]@{ name = "report-provenance-tests"; command = "scripts/quality/run-report-provenance-tests.ps1" }
)

try {
    for ($i = 0; $i -lt $orderedGates.Count; $i++) {
        $gate = $orderedGates[$i]
        $alwaysContinue = $gate.PSObject.Properties.Name -contains "alwaysContinue" -and [bool]$gate.alwaysContinue
        $expectedNonzero = $gate.PSObject.Properties.Name -contains "expectedNonzero" -and [bool]$gate.expectedNonzero
        Invoke-Gate -Name $gate.name -Command $gate.command -Index ($i + 1) -Total $orderedGates.Count -AlwaysContinue $alwaysContinue -ExpectedNonzero $expectedNonzero | Out-Null
    }
}
catch {
    $failedEarly = $true
    if ($ContinueOnFailure) {
        Write-Warning $_.Exception.Message
    }
    else {
        Write-Warning $_.Exception.Message
    }
}

Invoke-PostVerificationWorkspaceCleanup

$finalGates = @(
    [pscustomobject]@{ name = "beta-release-gate-initial-final"; command = "scripts/quality/run-beta-release-gate.ps1" },
    [pscustomobject]@{ name = "final-regression-coverage-audit-refresh"; command = "scripts/quality/run-final-regression-coverage-audit.ps1" },
    [pscustomobject]@{ name = "report-schema-validation-post-audit-refresh"; command = "scripts/quality/run-report-schema-validation.ps1" },
    [pscustomobject]@{ name = "report-provenance-tests-post-audit-refresh"; command = "scripts/quality/run-report-provenance-tests.ps1" },
    [pscustomobject]@{ name = "beta-release-gate"; command = "scripts/quality/run-beta-release-gate.ps1" },
    [pscustomobject]@{ name = "beta0-final-closure-gate"; command = "scripts/quality/run-beta0-final-closure-gate.ps1" }
)
for ($i = 0; $i -lt $finalGates.Count; $i++) {
    $gate = $finalGates[$i]
    Invoke-Gate -Name $gate.name -Command $gate.command -Index ($i + 1) -Total $finalGates.Count -AlwaysContinue $true | Out-Null
}

$closureReportPath = "scripts/reports/out/beta0-final-closure-report.json"
$closureReport = if (Test-Path -LiteralPath $closureReportPath -PathType Leaf) {
    Get-Content -Raw -LiteralPath $closureReportPath | ConvertFrom-Json
}
else {
    $null
}

$candidateReady = $null -ne $closureReport -and [bool]$closureReport.candidateReady
$releaseReady = $null -ne $closureReport -and [bool]$closureReport.releaseReady
$provenanceReady = $null -ne $closureReport -and [bool]$closureReport.provenanceReady
$officialReleaseEligible = $null -ne $closureReport -and [bool]$closureReport.officialReleaseEligible
$beta0TagAllowed = $null -ne $closureReport -and [bool]$closureReport.beta0TagAllowed
$blockingFailedGateCount = @($gateResults | Where-Object { $_.status -eq "failed" -and $_.blocking }).Count
$overallStatus = if ($beta0TagAllowed -and -not $failedEarly -and $blockingFailedGateCount -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    schemaVersion = "npdev-beta0-final-release-check-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-beta0-final-release-check_v2.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    candidateReady = $candidateReady
    releaseReady = $releaseReady
    provenanceReady = $provenanceReady
    officialReleaseEligible = $officialReleaseEligible
    beta0TagAllowed = $beta0TagAllowed
    releaseEvidencePublishRequired = (-not $SkipReleaseEvidencePublish)
    releaseEvidencePublishStatus = if ($SkipReleaseEvidencePublish) { "skipped" } else { "not-run" }
    releaseEvidencePublished = $false
    releaseEvidenceRoot = $null
    releaseEvidencePublishError = $null
    postEvidenceCleanlinessValidationRequired = (-not $SkipReleaseEvidencePublish)
    postEvidenceCleanlinessStatus = if ($SkipReleaseEvidencePublish) { "skipped" } else { "not-run" }
    postEvidenceCleanlinessReportPath = "scripts/reports/out/workspace-cleanliness-report.json"
    postEvidenceCleanlinessError = $null
    gates = $gateResults
}

$resolvedReportPath = Resolve-Beta0PathAgainstWorkspace -WorkspaceRootValue $workspaceRoot -PathValue $ReportPath
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedReportPath) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $resolvedReportPath -Encoding UTF8
Write-ReleaseCheckMessage ("Final release check report written: " + $ReportPath)

if ($overallStatus -eq "passed" -and -not $SkipReleaseEvidencePublish) {
    try {
        Write-ReleaseCheckMessage ("Publishing aggregate release evidence START -> " + $ReleaseEvidenceArchiveRoot)
        $publishedReleaseEvidenceRoot = Publish-Beta0FinalReleaseEvidence `
            -WorkspaceRootValue $workspaceRoot `
            -RunIdValue $RunId `
            -ReportsOutRoot $outRoot `
            -ReportPathValue $resolvedReportPath `
            -ReleaseEvidenceArchiveRootValue $ReleaseEvidenceArchiveRoot

        $report.releaseEvidencePublishStatus = "passed"
        $report.releaseEvidencePublished = $true
        $report.releaseEvidenceRoot = (Get-Beta0RelativePathIfUnderWorkspace -WorkspaceRootValue $workspaceRoot -PathValue $publishedReleaseEvidenceRoot)
        $cleanlinessResult = Invoke-PostEvidenceWorkspaceCleanlinessValidation -RunIdValue $RunId
        $report.postEvidenceCleanlinessStatus = $cleanlinessResult.status
        $report.postEvidenceCleanlinessReportPath = $cleanlinessResult.reportPath
        if ($cleanlinessResult.status -ne "passed") {
            throw ("Post-evidence workspace cleanliness validation failed. Report: " + $cleanlinessResult.reportPath)
        }
        $report.generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $resolvedReportPath -Encoding UTF8

        $publishedStructuredReportPath = Join-Path $publishedReleaseEvidenceRoot "scripts\reports\out\beta0-final-release-check-report.json"
        $publishedFlatReportPath = Join-Path $publishedReleaseEvidenceRoot "beta0-final-release-check-report.json"
        Copy-Item -LiteralPath $resolvedReportPath -Destination $publishedStructuredReportPath -Force
        Copy-Item -LiteralPath $resolvedReportPath -Destination $publishedFlatReportPath -Force
        $currentCleanlinessReportPath = Resolve-Beta0PathAgainstWorkspace -WorkspaceRootValue $workspaceRoot -PathValue "scripts/reports/out/workspace-cleanliness-report.json"
        if (Test-Path -LiteralPath $currentCleanlinessReportPath -PathType Leaf) {
            Copy-Item -LiteralPath $currentCleanlinessReportPath -Destination (Join-Path $publishedReleaseEvidenceRoot "scripts\reports\out\workspace-cleanliness-report.json") -Force
        }

        Write-ReleaseCheckMessage ("Publishing aggregate release evidence END   => passed (" + $report.releaseEvidenceRoot + ")")
    }
    catch {
        $overallStatus = "failed"
        $report.overallStatus = "failed"
        $report.releaseEvidencePublishStatus = "failed"
        $report.releaseEvidencePublished = $false
        $report.releaseEvidencePublishError = $_.Exception.Message
        if ($report.postEvidenceCleanlinessStatus -ne "passed") {
            $report.postEvidenceCleanlinessError = $_.Exception.Message
        }
        $report.generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $resolvedReportPath -Encoding UTF8
        Write-Error ("Beta 0 final release check passed gates, but failed to publish aggregate release evidence for statezip. Report: " + $ReportPath + " Error: " + $_.Exception.Message)
        exit 1
    }
}

if ($overallStatus -eq "passed") {
    Write-Host ("Beta 0 final release check passed. Report: " + $ReportPath)
    if ($report.releaseEvidencePublished) {
        Write-Host ("Aggregate release evidence published. Statezip can use: -ExistingEvidenceRoot 'last'")
    }
    exit 0
}

Write-Error ("Beta 0 final release check failed. Report: " + $ReportPath)
