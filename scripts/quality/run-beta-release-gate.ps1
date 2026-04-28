[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$SampleId = "",
    [string]$ReportPath = "",
    [string]$EvidenceRoot = "",
    [switch]$PreserveExistingReports,
    [string]$SourceCommitSha = "",
    [string]$SourceBranch = "",
    [AllowNull()][object]$SourceDirty = $null,
    [string]$SourceProvider = "",
    [string]$SourceRunId = "",
    [string]$SourceRunAttempt = "",
    [string]$SourceWorkflow = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}
$gateStartedAt = Get-Date
$releaseRunId = "runtimehost-beta-" + $gateStartedAt.ToString("yyyyMMdd-HHmmss")

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    $EvidenceRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\releases\" + $releaseRunId)
}
else {
    $EvidenceRoot = Normalize-NPDevPath $EvidenceRoot
}
New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null

function Test-PathUnderDirectory(
    [string]$ParentPath,
    [string]$ChildPath
) {
    $normalizedParent = Normalize-NPDevPath $ParentPath
    if (-not $normalizedParent.EndsWith("\")) {
        $normalizedParent += "\"
    }
    $normalizedChild = Normalize-NPDevPath $ChildPath
    return $normalizedChild.StartsWith($normalizedParent, [System.StringComparison]::OrdinalIgnoreCase)
}

function Clear-GeneratedReportFile(
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $false
    }

    $reportsOutRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out"
    if (-not (Test-PathUnderDirectory -ParentPath $reportsOutRoot -ChildPath $PathValue)) {
        return $false
    }

    Remove-Item -LiteralPath $PathValue -Force
    if ($null -ne (Get-Variable -Name clearedGeneratedReports -ErrorAction SilentlyContinue)) {
        [void]$clearedGeneratedReports.Add((Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PathValue))
    }
    return $true
}

function Get-ReportMetadata(
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return [pscustomobject]@{
            exists = $false
            overallStatus = $null
            runId = $null
            scriptPath = $null
            workspaceRoot = $null
            generatedAt = $null
            generatedAtDate = $null
            parseError = $null
        }
    }

    try {
        $report = Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
        $propertyNames = @($report.PSObject.Properties | Select-Object -ExpandProperty Name)
        $overallStatus = if ($propertyNames -contains "overallStatus") { [string]$report.overallStatus } else { $null }
        $runId = if ($propertyNames -contains "runId") { [string]$report.runId } else { $null }
        $scriptPath = if ($propertyNames -contains "scriptPath") { [string]$report.scriptPath } else { $null }
        $workspaceRootValue = if ($propertyNames -contains "workspaceRoot") { [string]$report.workspaceRoot } else { $null }
        $generatedAtValue = if ($propertyNames -contains "generatedAt") { $report.generatedAt } else { $null }
        $generatedAt = if ($null -eq $generatedAtValue) { $null } else { [string]$generatedAtValue }
        $generatedAtDate = $null
        if ($generatedAtValue -is [datetime]) {
            $generatedAtDate = [datetimeoffset]$generatedAtValue
            $generatedAt = $generatedAtDate.ToString("o")
        }
        elseif ($generatedAtValue -is [datetimeoffset]) {
            $generatedAtDate = $generatedAtValue
            $generatedAt = $generatedAtDate.ToString("o")
        }
        elseif (-not [string]::IsNullOrWhiteSpace($generatedAt)) {
            try {
                $generatedAtDate = [datetimeoffset]::Parse($generatedAt, [Globalization.CultureInfo]::InvariantCulture)
                $generatedAt = $generatedAtDate.ToString("o")
            }
            catch {
                $generatedAtDate = $null
            }
        }

        return [pscustomobject]@{
            exists = $true
            overallStatus = $overallStatus
            runId = $runId
            scriptPath = $scriptPath
            workspaceRoot = $workspaceRootValue
            generatedAt = $generatedAt
            generatedAtDate = $generatedAtDate
            parseError = $null
        }
    }
    catch {
        return [pscustomobject]@{
            exists = $true
            overallStatus = $null
            runId = $null
            scriptPath = $null
            workspaceRoot = $null
            generatedAt = $null
            generatedAtDate = $null
            parseError = $_.Exception.Message
        }
    }
}

function Invoke-BetaGateStep(
    [string]$Name,
    [string]$ScriptPath,
    [hashtable]$Parameters = @{},
    [string]$ExpectedReportPath = ""
) {
    Ensure-NPDevFile $ScriptPath ($Name + " script")
    Write-NPDevInfo ("Running beta release step: " + $Name)
    # Evidence expectation: child gate reports should be backed by Invoke-NPDevCommandEvidence
    # and expose command.outputTail, command.failingTaskName, and command.logPath-style fields.

    $startedAt = Get-Date
    $clearedPreviousReport = $false
    if (-not [string]::IsNullOrWhiteSpace($ExpectedReportPath) -and -not $PreserveExistingReports) {
        $clearedPreviousReport = Clear-GeneratedReportFile $ExpectedReportPath
    }

    $exitDisposition = "passed"
    $errorMessage = $null
    try {
        $Parameters["RunId"] = $releaseRunId
        & $ScriptPath @Parameters | Out-Null
    }
    catch {
        $exitDisposition = "failed"
        $errorMessage = $_.Exception.Message
    }

    $reportMetadata = $null
    $childReportDisposition = "not-required"
    $runIdMatch = $null
    $finalStatus = $exitDisposition
    $finalDecisionReason = if ($exitDisposition -eq "passed") { "Child script exited successfully and no child report was required." } else { "Child script failed and no child report was required." }

    if (-not [string]::IsNullOrWhiteSpace($ExpectedReportPath)) {
        $reportMetadata = Get-ReportMetadata $ExpectedReportPath
        $runIdMatch = $false

        if (-not $reportMetadata.exists) {
            $childReportDisposition = "missing"
            $finalStatus = "failed"
            $finalDecisionReason = if ($exitDisposition -eq "failed") {
                "child gate failed before emitting current-run evidence"
            }
            else {
                "child gate exited without emitting required evidence"
            }
            if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                $errorMessage = "Expected report was not produced: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
            }
        }
        elseif (-not [string]::IsNullOrWhiteSpace([string]$reportMetadata.parseError)) {
            $childReportDisposition = "parse-error"
            $finalStatus = "failed"
            $finalDecisionReason = "child report could not be parsed"
            if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                $errorMessage = "Unable to parse expected report: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
            }
        }
        elseif ($null -eq $reportMetadata.generatedAtDate) {
            $childReportDisposition = "invalid"
            $finalStatus = "failed"
            $finalDecisionReason = "child report is missing a parseable generatedAt timestamp"
            if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                $errorMessage = "Expected report is missing a parseable generatedAt timestamp: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
            }
        }
        elseif ([string]::IsNullOrWhiteSpace([string]$reportMetadata.runId)) {
            $childReportDisposition = "missing-run-id"
            $finalStatus = "failed"
            $finalDecisionReason = "child report is missing runId"
            if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                $errorMessage = "Expected report is missing runId: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
            }
        }
        elseif ([string]$reportMetadata.runId -ne $releaseRunId) {
            $childReportDisposition = "stale-run-id"
            $finalStatus = "failed"
            $runIdMatch = $false
            $finalDecisionReason = "child report runId does not match aggregate runId"
            if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                $errorMessage = "Expected report belongs to a different runId: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
            }
        }
        elseif ([string]::IsNullOrWhiteSpace([string]$reportMetadata.scriptPath)) {
            $childReportDisposition = "invalid"
            $finalStatus = "failed"
            $runIdMatch = $true
            $finalDecisionReason = "child report is missing scriptPath"
            if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                $errorMessage = "Expected report is missing scriptPath: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
            }
        }
        elseif ([string]::IsNullOrWhiteSpace([string]$reportMetadata.workspaceRoot)) {
            $childReportDisposition = "invalid"
            $finalStatus = "failed"
            $runIdMatch = $true
            $finalDecisionReason = "child report is missing workspaceRoot"
            if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                $errorMessage = "Expected report is missing workspaceRoot: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
            }
        }
        else {
            $childReportDisposition = "current"
            $runIdMatch = $true
            $reportStatus = [string]$reportMetadata.overallStatus
            if ($reportStatus -notin @("passed", "warning", "failed")) {
                $childReportDisposition = "invalid"
                $finalStatus = "failed"
                $finalDecisionReason = "child report has invalid overallStatus"
                if ([string]::IsNullOrWhiteSpace($errorMessage)) {
                    $errorMessage = "Expected report has invalid overallStatus: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath)
                }
            }
            elseif ($exitDisposition -eq "failed") {
                $finalStatus = "failed"
                $finalDecisionReason = "child script threw despite emitting current-run evidence"
            }
            else {
                $finalStatus = $reportStatus
                $finalDecisionReason = "child report is current for aggregate runId and controls the step decision"
            }
        }
    }

    $endedAt = Get-Date
    return [pscustomobject]@{
        name = $Name
        script = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ScriptPath
        status = $finalStatus
        exitDisposition = $exitDisposition
        childReportDisposition = $childReportDisposition
        runIdMatch = $runIdMatch
        finalDecisionReason = $finalDecisionReason
        startedAt = $startedAt.ToString("o")
        endedAt = $endedAt.ToString("o")
        durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 1)
        clearedPreviousReport = $clearedPreviousReport
        reportPath = if (-not [string]::IsNullOrWhiteSpace($ExpectedReportPath) -and (Test-Path -LiteralPath $ExpectedReportPath -PathType Leaf)) {
            Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ExpectedReportPath
        }
        else {
            $null
        }
        reportGeneratedAt = if ($null -eq $reportMetadata) { $null } else { $reportMetadata.generatedAt }
        reportRunId = if ($null -eq $reportMetadata) { $null } else { $reportMetadata.runId }
        reportScriptPath = if ($null -eq $reportMetadata) { $null } else { $reportMetadata.scriptPath }
        error = $errorMessage
    }
}

function Add-EvidenceFile(
    [System.Collections.Generic.List[object]]$ManifestEntries,
    [string]$PathValue
) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return
    }

    $relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PathValue
    if (@($ManifestEntries | Where-Object { [string]$_.source -eq $relativePath }).Count -gt 0) {
        return
    }

    $destinationPath = Join-Path $EvidenceRoot $relativePath
    $destinationDirectory = Split-Path -Parent $destinationPath
    if (-not [string]::IsNullOrWhiteSpace($destinationDirectory)) {
        New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
    }

    Copy-Item -LiteralPath $PathValue -Destination $destinationPath -Force
    $sourceHash = (Get-FileHash -LiteralPath $PathValue -Algorithm SHA256).Hash.ToLowerInvariant()
    $copiedHash = (Get-FileHash -LiteralPath $destinationPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $fileInfo = Get-Item -LiteralPath $destinationPath
    [void]$ManifestEntries.Add([pscustomobject]@{
            source = $relativePath
            copiedTo = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $destinationPath
            sha256 = $copiedHash
            sourceSha256 = $sourceHash
            sizeBytes = $fileInfo.Length
        })
}

function Add-EvidenceCandidatePath(
    [System.Collections.Generic.List[string]]$CandidatePaths,
    [string]$PathValue
) {
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return
    }

    $normalizedPath = Normalize-NPDevPath $PathValue
    if (@($CandidatePaths | Where-Object { $_ -eq $normalizedPath }).Count -eq 0) {
        [void]$CandidatePaths.Add($normalizedPath)
    }
}

function Resolve-OptionalWorkspaceFilePath(
    [string]$PathValue
) {
    $trimmedPath = Get-TrimmedString $PathValue
    if ([string]::IsNullOrWhiteSpace($trimmedPath)) {
        return $null
    }

    $candidatePath = if ([System.IO.Path]::IsPathRooted($trimmedPath)) {
        Normalize-NPDevPath $trimmedPath
    }
    else {
        Resolve-NPDevWorkspacePath $WorkspaceRoot $trimmedPath
    }

    if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
        return $null
    }

    return $candidatePath
}

function Get-ReportEvidenceArtifactPaths(
    [string]$ReportPathValue
) {
    if (-not (Test-Path -LiteralPath $ReportPathValue -PathType Leaf)) {
        return @()
    }

    try {
        $reportJson = Get-Content -LiteralPath $ReportPathValue -Raw
    }
    catch {
        return @()
    }

    $artifactPaths = [System.Collections.Generic.List[string]]::new()
    foreach ($match in [regex]::Matches($reportJson, '"(?:logPath|reportPath)"\s*:\s*"(?<path>(?:[^"\\]|\\.)+)"')) {
        $rawPath = [string]$match.Groups["path"].Value
        if ([string]::IsNullOrWhiteSpace($rawPath)) {
            continue
        }

        $pathValue = $rawPath.Replace('\\', '\').Replace('\/', '/')
        $resolvedPath = Resolve-OptionalWorkspaceFilePath $pathValue
        if ($null -ne $resolvedPath) {
            Add-EvidenceCandidatePath -CandidatePaths $artifactPaths -PathValue $resolvedPath
        }
    }
    return @($artifactPaths)
}

function Get-FirstOutputLine([object]$Capture) {
    $lines = @($Capture.Output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($lines.Count -eq 0) {
        return $null
    }
    return [string]$lines[0]
}

function Get-OptionalCommandVersion(
    [string]$WorkingDirectory,
    [string]$Executable,
    [string[]]$Arguments
) {
    if (-not (Test-NPDevCommandAvailable $Executable)) {
        return $null
    }

    try {
        $capture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkingDirectory -Executable $Executable -Arguments $Arguments
        return Get-FirstOutputLine $capture
    }
    catch {
        return $null
    }
}

function Get-TrimmedString([object]$Value) {
    if ($null -eq $Value) {
        return ""
    }

    return ([string]$Value).Trim()
}

function Parse-OptionalBoolean([object]$Value) {
    if ($null -eq $Value) {
        return $null
    }

    if ($Value -is [bool]) {
        return [bool]$Value
    }

    $text = Get-TrimmedString $Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }

    switch ($text.ToLowerInvariant()) {
        "true" { return $true }
        "false" { return $false }
        "1" { return $true }
        "0" { return $false }
        default { throw ("Unable to parse boolean value: " + $text) }
    }
}

function Get-ExplicitCommitIdentity(
    [string]$CommitSha,
    [string]$Branch,
    [object]$Dirty,
    [string]$Provider,
    [string]$RunId,
    [string]$RunAttempt,
    [string]$Workflow
) {
    $trimmedCommitSha = Get-TrimmedString $CommitSha
    if ([string]::IsNullOrWhiteSpace($trimmedCommitSha)) {
        return $null
    }

    $dirtyValue = Parse-OptionalBoolean $Dirty
    $resolvedProvider = Get-TrimmedString $Provider
    if ([string]::IsNullOrWhiteSpace($resolvedProvider)) {
        $resolvedProvider = "explicit"
    }

    return [pscustomobject]@{
        available = $true
        source = $resolvedProvider
        commitSha = $trimmedCommitSha
        shortSha = if ($trimmedCommitSha.Length -ge 12) { $trimmedCommitSha.Substring(0, 12) } else { $trimmedCommitSha }
        branch = Get-TrimmedString $Branch
        dirty = $dirtyValue
        runId = Get-TrimmedString $RunId
        runAttempt = Get-TrimmedString $RunAttempt
        workflow = Get-TrimmedString $Workflow
        diagnostics = [pscustomobject]@{
            status = "passed"
            reason = "explicit-source-metadata"
            message = "Explicit source metadata was supplied by the caller."
            steps = @()
        }
    }
}

function New-CommitIdentityDiagnostics(
    [string]$Status,
    [string]$Reason,
    [string]$Message,
    [object[]]$Steps = @()
) {
    return [pscustomobject]@{
        status = $Status
        reason = $Reason
        message = $Message
        steps = @($Steps)
    }
}

function New-UnavailableCommitIdentity(
    [string]$Reason,
    [string]$Message,
    [object[]]$Steps = @()
) {
    return [pscustomobject]@{
        available = $false
        source = "unavailable"
        commitSha = $null
        shortSha = $null
        branch = $null
        dirty = $null
        runId = $null
        runAttempt = $null
        workflow = $null
        diagnostics = New-CommitIdentityDiagnostics -Status "failed" -Reason $Reason -Message $Message -Steps @($Steps)
    }
}

function Get-CommitIdentity(
    [string]$WorkspaceRootValue,
    [string]$CommitSha = "",
    [string]$Branch = "",
    [object]$Dirty = $null,
    [string]$Provider = "",
    [string]$RunId = "",
    [string]$RunAttempt = "",
    [string]$Workflow = ""
) {
    $explicitIdentity = Get-ExplicitCommitIdentity `
        -CommitSha $CommitSha `
        -Branch $Branch `
        -Dirty $Dirty `
        -Provider $Provider `
        -RunId $RunId `
        -RunAttempt $RunAttempt `
        -Workflow $Workflow
    if ($null -ne $explicitIdentity) {
        return $explicitIdentity
    }

    if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_SHA)) {
        $sha = [string]$env:GITHUB_SHA
        return [pscustomobject]@{
            available = $true
            source = "github-actions"
            commitSha = $sha
            shortSha = if ($sha.Length -ge 12) { $sha.Substring(0, 12) } else { $sha }
            branch = [string]$env:GITHUB_REF_NAME
            dirty = $false
            runId = [string]$env:GITHUB_RUN_ID
            runAttempt = [string]$env:GITHUB_RUN_ATTEMPT
            workflow = [string]$env:GITHUB_WORKFLOW
            diagnostics = [pscustomobject]@{
                status = "passed"
                reason = "github-actions"
                message = "GitHub Actions source metadata was discovered from environment variables."
                steps = @()
            }
        }
    }

    if (-not (Test-NPDevCommandAvailable "git")) {
        return New-UnavailableCommitIdentity `
            -Reason "git-not-found" `
            -Message "Git was not found on PATH; commit identity could not be discovered."
    }

    $steps = [System.Collections.Generic.List[object]]::new()
    try {
        $insideWorkTreeCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("rev-parse", "--is-inside-work-tree")
        $insideWorkTree = Get-FirstOutputLine $insideWorkTreeCapture
        [void]$steps.Add([pscustomobject]@{
                command = "git rev-parse --is-inside-work-tree"
                exitCode = [int]$insideWorkTreeCapture.ExitCode
                firstOutputLine = $insideWorkTree
            })
        if ($insideWorkTreeCapture.ExitCode -ne 0 -or [string]$insideWorkTree -ne "true") {
            return New-UnavailableCommitIdentity `
                -Reason "not-a-git-worktree" `
                -Message ("Workspace is not inside a Git worktree: " + $WorkspaceRootValue) `
                -Steps @($steps)
        }

        $shaCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("rev-parse", "HEAD")
        $sha = Get-FirstOutputLine $shaCapture
        [void]$steps.Add([pscustomobject]@{
                command = "git rev-parse HEAD"
                exitCode = [int]$shaCapture.ExitCode
                firstOutputLine = $sha
            })
        if ($shaCapture.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($sha)) {
            return New-UnavailableCommitIdentity `
                -Reason "commit-unavailable" `
                -Message "Git worktree was detected, but HEAD commit could not be resolved." `
                -Steps @($steps)
        }

        $branchCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("rev-parse", "--abbrev-ref", "HEAD")
        $branch = Get-FirstOutputLine $branchCapture
        [void]$steps.Add([pscustomobject]@{
                command = "git rev-parse --abbrev-ref HEAD"
                exitCode = [int]$branchCapture.ExitCode
                firstOutputLine = $branch
            })
        if ($branchCapture.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($branch)) {
            return New-UnavailableCommitIdentity `
                -Reason "branch-unavailable" `
                -Message "Git worktree was detected, but branch could not be resolved." `
                -Steps @($steps)
        }

        $statusCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("status", "--porcelain")
        [void]$steps.Add([pscustomobject]@{
                command = "git status --porcelain"
                exitCode = [int]$statusCapture.ExitCode
                firstOutputLine = Get-FirstOutputLine $statusCapture
            })
        if ($statusCapture.ExitCode -ne 0) {
            return New-UnavailableCommitIdentity `
                -Reason "dirty-status-unavailable" `
                -Message "Git worktree was detected, but dirty status could not be resolved." `
                -Steps @($steps)
        }

        $dirty = @($statusCapture.Output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -gt 0
        return [pscustomobject]@{
            available = $true
            source = "local-git"
            commitSha = $sha
            shortSha = if ([string]::IsNullOrWhiteSpace($sha)) { $null } elseif ($sha.Length -ge 12) { $sha.Substring(0, 12) } else { $sha }
            branch = $branch
            dirty = $dirty
            runId = $null
            runAttempt = $null
            workflow = $null
            diagnostics = New-CommitIdentityDiagnostics `
                -Status "passed" `
                -Reason "local-git-discovered" `
                -Message "Local Git commit identity was discovered." `
                -Steps @($steps)
        }
    }
    catch {
        return New-UnavailableCommitIdentity `
            -Reason "git-discovery-error" `
            -Message ("Git discovery failed: " + $_.Exception.Message) `
            -Steps @($steps)
    }

}

function Get-ProvenanceGrade([object]$CommitIdentity) {
    if ($null -ne $CommitIdentity -and [bool]$CommitIdentity.available -and [string]$CommitIdentity.source -eq "github-actions") {
        return "ci-traceable"
    }

    if ($null -ne $CommitIdentity -and [bool]$CommitIdentity.available -and -not [string]::IsNullOrWhiteSpace([string]$CommitIdentity.commitSha)) {
        return "git-traceable"
    }

    return "local-unanchored"
}

function Test-TraceabilitySatisfied([string]$ProvenanceGrade) {
    return $ProvenanceGrade -in @("ci-traceable", "git-traceable")
}

function Get-EnvironmentFingerprint([string]$WorkspaceRootValue) {
    $runtimeInfo = [System.Runtime.InteropServices.RuntimeInformation]
    return [pscustomobject]@{
        osDescription = $runtimeInfo::OSDescription
        osArchitecture = $runtimeInfo::OSArchitecture.ToString()
        processArchitecture = $runtimeInfo::ProcessArchitecture.ToString()
        frameworkDescription = $runtimeInfo::FrameworkDescription
        powershellVersion = $PSVersionTable.PSVersion.ToString()
        ci = [pscustomobject]@{
            isCi = -not [string]::IsNullOrWhiteSpace([string]$env:CI)
            githubActions = ([string]$env:GITHUB_ACTIONS -eq "true")
            workflow = [string]$env:GITHUB_WORKFLOW
            actor = [string]$env:GITHUB_ACTOR
            ref = [string]$env:GITHUB_REF
            refName = [string]$env:GITHUB_REF_NAME
            runId = [string]$env:GITHUB_RUN_ID
            runAttempt = [string]$env:GITHUB_RUN_ATTEMPT
        }
        toolchain = [pscustomobject]@{
            java = Get-OptionalCommandVersion -WorkingDirectory $WorkspaceRootValue -Executable "java" -Arguments @("-version")
            node = Get-OptionalCommandVersion -WorkingDirectory $WorkspaceRootValue -Executable "node" -Arguments @("--version")
            npm = Get-OptionalCommandVersion -WorkingDirectory $WorkspaceRootValue -Executable "npm" -Arguments @("--version")
            git = Get-OptionalCommandVersion -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("--version")
        }
    }
}

$doctorReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\doctor-report.json"
$hygieneReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\hygiene-gate-report.json"
$contractReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-gate-report.json"
$editorReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\editor-gate-report.json"
$samplePresentationLabelReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-presentation-label-report.json"
$frontendReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-gate-report.json"
$frontendAuditReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-audit-gate-report.json"
$generatorReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-gate-report.json"
$kernelReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-gate-report.json"
$pluginReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\plugin-gate-report.json"
$invariantReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\invariant-gate-report.json"
$betaScopeReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-scope-report.json"
$runtimeHostReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json"
$runtimeSurfaceClassificationReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-classification-report.json"
$runtimeSurfaceAllowlistReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-allowlist-report.json"
$runtimeFootprintReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-footprint-report.json"
$sampleMatrixReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-matrix-report.json"
$sampleDiagnosticsReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json"
$aiBetaMatrixReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta-matrix-report.json"
$runtimeHostLibsSyncReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-libs-sync-report.json"
$contractSurfaceConsistencyReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-surface-consistency-report.json"
$entityCanonicalSurfaceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\entity-canonical-surface-report.json"
$legacyEntitySurfaceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\legacy-entity-surface-report.json"
$domainLeakReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\domain-leak-report.json"
$rootBuildCouplingReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\root-build-coupling-report.json"
$deterministicGenerationReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json"
$aiBetaMatrixDefinitionPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\matrix\matrix-definition.json"
$aiBetaNativeMatrixReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\matrix\matrix-report.json"

$generatedReportPaths = @(
    $ReportPath,
    $doctorReportPath,
    $hygieneReportPath,
    $contractReportPath,
    $editorReportPath,
    $samplePresentationLabelReportPath,
    $contractSurfaceConsistencyReportPath,
    $entityCanonicalSurfaceReportPath,
    $legacyEntitySurfaceReportPath,
    $domainLeakReportPath,
    $rootBuildCouplingReportPath,
    $deterministicGenerationReportPath,
    $frontendReportPath,
    $frontendAuditReportPath,
    $generatorReportPath,
    $kernelReportPath,
    $pluginReportPath,
    $invariantReportPath,
    $betaScopeReportPath,
    $runtimeHostReportPath,
    $runtimeHostLibsSyncReportPath,
    $runtimeSurfaceClassificationReportPath,
    $runtimeSurfaceAllowlistReportPath,
    $runtimeFootprintReportPath,
    $sampleMatrixReportPath,
    $sampleDiagnosticsReportPath,
    $aiBetaMatrixReportPath,
    $aiBetaMatrixDefinitionPath,
    $aiBetaNativeMatrixReportPath
)
$clearedGeneratedReports = [System.Collections.Generic.List[string]]::new()
if (-not $PreserveExistingReports) {
    foreach ($pathValue in @($generatedReportPaths | Select-Object -Unique)) {
        [void](Clear-GeneratedReportFile $pathValue)
    }
}

$steps = @(
    (Invoke-BetaGateStep `
            -Name "doctor" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\npdev-doctor.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $doctorReportPath
            } `
            -ExpectedReportPath $doctorReportPath),
    (Invoke-BetaGateStep `
            -Name "hygiene" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-hygiene-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                SampleId = $SampleId
                ReportPath = $hygieneReportPath
            } `
            -ExpectedReportPath $hygieneReportPath),
    (Invoke-BetaGateStep `
            -Name "contract" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-contract-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $contractReportPath
            } `
            -ExpectedReportPath $contractReportPath),
    (Invoke-BetaGateStep `
            -Name "editor" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-editor-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $editorReportPath
            } `
            -ExpectedReportPath $editorReportPath),
    (Invoke-BetaGateStep `
            -Name "frontend" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-frontend-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $frontendReportPath
            } `
            -ExpectedReportPath $frontendReportPath),
    (Invoke-BetaGateStep `
            -Name "frontend-audit" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-frontend-audit-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $frontendAuditReportPath
            } `
            -ExpectedReportPath $frontendAuditReportPath),
    (Invoke-BetaGateStep `
            -Name "generator" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-generator-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $generatorReportPath
            } `
            -ExpectedReportPath $generatorReportPath),
    (Invoke-BetaGateStep `
            -Name "kernel" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-kernel-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $kernelReportPath
            } `
            -ExpectedReportPath $kernelReportPath),
    (Invoke-BetaGateStep `
            -Name "plugin" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-plugin-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                SampleId = $SampleId
                GenerateIfMissing = $true
                ReportPath = $pluginReportPath
            } `
            -ExpectedReportPath $pluginReportPath),
    (Invoke-BetaGateStep `
            -Name "invariant" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-invariant-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $invariantReportPath
            } `
            -ExpectedReportPath $invariantReportPath),
    (Invoke-BetaGateStep `
            -Name "beta-scope" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-beta-scope-check.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $betaScopeReportPath
            } `
            -ExpectedReportPath $betaScopeReportPath),
    (Invoke-BetaGateStep `
            -Name "runtimehost" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-runtimehost-gate.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                SampleId = $SampleId
                ReportPath = $runtimeHostReportPath
            } `
            -ExpectedReportPath $runtimeHostReportPath),
    (Invoke-BetaGateStep `
            -Name "sample-matrix" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-sample-matrix.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $sampleMatrixReportPath
            } `
            -ExpectedReportPath $sampleMatrixReportPath),
    (Invoke-BetaGateStep `
            -Name "ai-beta-matrix" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-ai-beta-matrix.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $aiBetaMatrixReportPath
            } `
            -ExpectedReportPath $aiBetaMatrixReportPath)
)

$evidenceConsistencyChecks = @(
    (Invoke-BetaGateStep `
            -Name "sample-diagnostics-enrichment" `
            -ScriptPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-sample-diagnostics-audit.ps1") `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                MatrixReportPath = $sampleMatrixReportPath
                ReportPath = $sampleDiagnosticsReportPath
            } `
            -ExpectedReportPath $sampleDiagnosticsReportPath)
)

$failedSteps = @($steps | Where-Object { $_.status -eq "failed" })
$warningSteps = @($steps | Where-Object { $_.status -eq "warning" })
$failedEvidenceConsistencyChecks = @($evidenceConsistencyChecks | Where-Object { $_.status -eq "failed" })
$warningEvidenceConsistencyChecks = @($evidenceConsistencyChecks | Where-Object { $_.status -eq "warning" })
$overallStatus = if ($failedSteps.Count -gt 0 -or $failedEvidenceConsistencyChecks.Count -gt 0) {
    "failed"
}
elseif ($warningSteps.Count -gt 0 -or $warningEvidenceConsistencyChecks.Count -gt 0) {
    "warning"
}
else {
    "passed"
}
$gateEndedAt = Get-Date
$commitIdentity = Get-CommitIdentity `
    -WorkspaceRootValue $WorkspaceRoot `
    -CommitSha $SourceCommitSha `
    -Branch $SourceBranch `
    -Dirty $SourceDirty `
    -Provider $SourceProvider `
    -RunId $SourceRunId `
    -RunAttempt $SourceRunAttempt `
    -Workflow $SourceWorkflow
if (-not [bool]$commitIdentity.available) {
    $diagnosticMessage = if ($commitIdentity.PSObject.Properties.Name -contains "diagnostics" -and $null -ne $commitIdentity.diagnostics) {
        [string]$commitIdentity.diagnostics.message
    }
    else {
        "Commit identity is unavailable."
    }
    Write-NPDevWarn ("Release traceability is diagnostic-only: " + $diagnosticMessage)
}
$provenanceGrade = Get-ProvenanceGrade $commitIdentity
$traceabilitySatisfied = Test-TraceabilitySatisfied $provenanceGrade
$releaseReady = ($overallStatus -eq "passed")
$officialReleaseEligible = ($releaseReady -and $traceabilitySatisfied)

$evidencePathsToCopy = [System.Collections.Generic.List[string]]::new()
foreach ($pathValue in @(
        $doctorReportPath,
        $hygieneReportPath,
        $contractReportPath,
        $editorReportPath,
        $samplePresentationLabelReportPath,
        $contractSurfaceConsistencyReportPath,
        $entityCanonicalSurfaceReportPath,
        $legacyEntitySurfaceReportPath,
        $domainLeakReportPath,
        $rootBuildCouplingReportPath,
        $deterministicGenerationReportPath,
        $frontendReportPath,
        $frontendAuditReportPath,
        $generatorReportPath,
        $kernelReportPath,
        $pluginReportPath,
        $invariantReportPath,
        $betaScopeReportPath,
        $runtimeHostReportPath,
        $runtimeHostLibsSyncReportPath,
        $runtimeSurfaceClassificationReportPath,
        $runtimeSurfaceAllowlistReportPath,
        $runtimeFootprintReportPath,
        $sampleMatrixReportPath,
        $sampleDiagnosticsReportPath,
        $aiBetaMatrixReportPath,
        $aiBetaMatrixDefinitionPath,
        $aiBetaNativeMatrixReportPath
    )) {
    Add-EvidenceCandidatePath -CandidatePaths $evidencePathsToCopy -PathValue $pathValue
}
foreach ($pathValue in @($steps | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.reportPath) } | ForEach-Object {
            Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$_.reportPath)
        })) {
    foreach ($artifactPath in @(Get-ReportEvidenceArtifactPaths -ReportPathValue $pathValue)) {
        Add-EvidenceCandidatePath -CandidatePaths $evidencePathsToCopy -PathValue $artifactPath
    }
}
foreach ($pathValue in @($evidenceConsistencyChecks | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.reportPath) } | ForEach-Object {
            Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$_.reportPath)
        })) {
    foreach ($artifactPath in @(Get-ReportEvidenceArtifactPaths -ReportPathValue $pathValue)) {
        Add-EvidenceCandidatePath -CandidatePaths $evidencePathsToCopy -PathValue $artifactPath
    }
}

$evidenceManifestEntries = [System.Collections.Generic.List[object]]::new()
Write-NPDevInfo ("Copying " + $evidencePathsToCopy.Count + " evidence file(s) into " + $EvidenceRoot)
foreach ($pathValue in @($evidencePathsToCopy)) {
    Add-EvidenceFile -ManifestEntries $evidenceManifestEntries -PathValue $pathValue
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $releaseRunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    releaseRunId = $releaseRunId
    workspaceRoot = $WorkspaceRoot
    sampleId = $SampleId
    overallStatus = $overallStatus
    gateStartedAt = $gateStartedAt.ToString("o")
    gateEndedAt = $gateEndedAt.ToString("o")
    durationSeconds = [math]::Round(($gateEndedAt - $gateStartedAt).TotalSeconds, 1)
    evidenceRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $EvidenceRoot
    provenanceGrade = $provenanceGrade
    traceabilitySatisfied = $traceabilitySatisfied
    releaseReady = $releaseReady
    officialReleaseEligible = $officialReleaseEligible
    commitIdentity = $commitIdentity
    authoritativeDecision = [pscustomobject]@{
        sourceOfTruth = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ReportPath
        releaseRunId = $releaseRunId
        rule = "Only this aggregate beta release gate report for this releaseRunId is authoritative. Focused gate reports are evidence only and must be interpreted through this aggregate report."
        staleReportPolicy = if ($PreserveExistingReports) { "preserved-but-rejected-unless-runId-matches" } else { "purged-before-execution" }
        staleGeneratedReportsCleared = @($clearedGeneratedReports)
    }
    summary = [pscustomobject]@{
        failed = $failedSteps.Count + $failedEvidenceConsistencyChecks.Count
        warnings = $warningSteps.Count + $warningEvidenceConsistencyChecks.Count
        passed = @($steps | Where-Object { $_.status -eq "passed" }).Count + @($evidenceConsistencyChecks | Where-Object { $_.status -eq "passed" }).Count
        total = $steps.Count + $evidenceConsistencyChecks.Count
    }
    steps = $steps
    evidenceConsistency = $evidenceConsistencyChecks
    copiedEvidence = $evidenceManifestEntries
}
Write-NPDevJsonFile $ReportPath $report
Add-EvidenceFile -ManifestEntries $evidenceManifestEntries -PathValue $ReportPath

$evidenceManifestPath = Join-Path $EvidenceRoot "evidence-manifest.json"
Write-NPDevJsonFile $evidenceManifestPath ([pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        runId = $releaseRunId
        releaseRunId = $releaseRunId
        workspaceRoot = $WorkspaceRoot
        evidenceRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $EvidenceRoot
        gateStartedAt = $gateStartedAt.ToString("o")
        gateEndedAt = $gateEndedAt.ToString("o")
        authoritativeReport = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ReportPath
        decisionRule = "Use the aggregate beta release gate report, not individual focused reports, as the release decision source of truth."
        provenanceGrade = $provenanceGrade
        traceabilitySatisfied = $traceabilitySatisfied
        releaseReady = $releaseReady
        officialReleaseEligible = $officialReleaseEligible
        commitIdentity = $commitIdentity
        environmentFingerprint = Get-EnvironmentFingerprint $WorkspaceRoot
        gateReport = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ReportPath
        files = $evidenceManifestEntries
    })

if ($overallStatus -eq "passed") {
    Write-NPDevOk ("Beta release gate passed. Evidence bundle: " + $EvidenceRoot)
    return
}

if ($overallStatus -eq "warning") {
    Write-NPDevWarn ("Beta release gate completed with warnings. Evidence bundle: " + $EvidenceRoot)
    return
}

Write-NPDevWarn ("Beta release gate failed. Evidence bundle: " + $EvidenceRoot)
throw "Beta release gate failed."
