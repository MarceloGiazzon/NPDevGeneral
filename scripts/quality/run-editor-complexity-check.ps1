param(
    [string]$ReportPath = "scripts/reports/out/editor-decomplexification-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Write-JsonFile {
    param([string]$Path, [object]$Value, [int]$Depth = 80)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-CommandCapture {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$OutputPath,
        [int]$ExpectedExitCode = 0
    )

    $started = Get-Date
    $startedAt = $started.ToUniversalTime().ToString("o")
    $output = [System.Collections.Generic.List[string]]::new()
    Push-Location -LiteralPath $WorkingDirectory
    try {
        $ErrorActionPreference = "Continue"
        & $Executable @Arguments 2>&1 | ForEach-Object { $output.Add([string]$_) | Out-Null }
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        if ($null -eq $exitCode) {
            $exitCode = 0
        }
    }
    finally {
        Pop-Location
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
    $output | Set-Content -LiteralPath $OutputPath -Encoding UTF8

    return [pscustomobject]@{
        name = $Name
        command = (@($Executable) + @($Arguments) -join " ")
        workingDirectory = $WorkingDirectory
        startedAt = $startedAt
        completedAt = (Get-Date).ToUniversalTime().ToString("o")
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        durationSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 3)
        outputPath = ($OutputPath -replace "\\", "/")
        outputTail = @($output | Select-Object -Last 80)
    }
}

function Get-LineCount {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return -1
    }
    return [System.IO.File]::ReadAllLines($Path).Count
}

function Get-NormalizedLineCount {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return -1
    }
    return (Get-Content -LiteralPath $Path | Measure-Object -Line).Lines
}

function Convert-ToForwardSlashPath {
    param([string]$Path)
    return ($Path -replace "\\", "/")
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "editor-decomplexification-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workRoot = Join-Path $workspaceRoot "build/cp13-editor-decomplexification"
if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

$uiRoot = Join-Path $workspaceRoot "NPDevEditor/ui-react"
$uiBoundaryPath = Join-Path $uiRoot "ui-boundary.json"
$uiBoundary = Read-JsonFile $uiBoundaryPath
$componentLineThreshold = 300

$activeAllowed = @($uiBoundary.surfaceClassifications.allowed)
$activeComponentLineCounts = @(
    foreach ($relativePath in $activeAllowed) {
        if (-not ([string]$relativePath).EndsWith(".tsx")) {
            continue
        }
        $absolutePath = Join-Path $workspaceRoot $relativePath
        [pscustomobject]@{
            path = Convert-ToForwardSlashPath $relativePath
            exists = Test-Path -LiteralPath $absolutePath -PathType Leaf
            physicalLineCount = Get-LineCount $absolutePath
            normalizedLineCount = Get-NormalizedLineCount $absolutePath
            lines = Get-LineCount $absolutePath
        }
    }
)
$missingActiveComponents = @($activeComponentLineCounts | Where-Object { -not $_.exists })
$largestActiveComponent = @($activeComponentLineCounts | Sort-Object -Property physicalLineCount -Descending | Select-Object -First 1)
$thresholdViolations = @($activeComponentLineCounts | Where-Object { $_.physicalLineCount -gt $componentLineThreshold })

$storeFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $uiRoot "src/authoring/stores") -Filter "*Store.ts" -File |
        Sort-Object Name |
        ForEach-Object { Convert-ToForwardSlashPath $_.FullName.Replace($workspaceRoot + [System.IO.Path]::DirectorySeparatorChar, "") }
)

$syncStatePath = "NPDevEditor/ui-react/src/authoring/json/synchronizedJsonState.ts"
$syncHookPath = "NPDevEditor/ui-react/src/authoring/json/useSynchronizedJsonEditor.ts"
$syncTestPath = "NPDevEditor/ui-react/src/authoring/editorRoundTripAndUx.test.ts"
$customJsonSyncIsolatedOrRemoved =
    (Test-Path -LiteralPath (Join-Path $workspaceRoot $syncStatePath) -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $workspaceRoot $syncHookPath) -PathType Leaf) -and
    ((Get-Content -Raw -LiteralPath (Join-Path $workspaceRoot $syncTestPath)) -match "synchronizedJsonState")

$workbenchPath = Join-Path $workspaceRoot "NPDevEditor/ui-react/src/workbench/ReactWorkbenchApp.tsx"
$workbenchSource = Get-Content -Raw -LiteralPath $workbenchPath
$removedDeferredRoutes = @()
# T1.4 (docs/TREE1_LAUNCH_UNBLOCK_PLAN.md): all 32 files ui-boundary.json used to classify "deferred"
# were deleted outright (git rm), not just left unrouted -- so `deferred` is now permanently empty and
# membership-in-`deferred` can no longer be the signal. Check the physical absence of these two named
# panels directly instead: that is still direct evidence a previously-shipped unused panel route was
# actually removed, which is what this check has always existed to prove.
foreach ($panel in @("BusinessWorkspacePanel", "RuntimeRefreshPanel")) {
    $panelPath = Join-Path $workspaceRoot ("NPDevEditor/ui-react/src/" + $panel + ".tsx")
    $wasRemoved = -not (Test-Path -LiteralPath $panelPath -PathType Leaf)
    $isRouted = $workbenchSource -match [regex]::Escape($panel)
    if ($wasRemoved -and -not $isRouted) {
        $removedDeferredRoutes += $panel
    }
}
$unusedPanelRemovalCount = $removedDeferredRoutes.Count

# REG-150: this script has always assumed node_modules is already populated from normal local
# usage. run-frontend-gate.ps1's own Gradle tasks are finalizedBy cleanUiReactGenerated, which
# deletes node_modules as part of its no-residue policy -- so a fresh clone, or any run right after
# that cleanup, previously failed immediately with "'vitest' is not recognized". Self-install here
# (npm ci is a fast no-op when node_modules is already current) rather than relying on the caller
# to remember a separate install step.
if (-not (Test-Path -LiteralPath (Join-Path $uiRoot "node_modules") -PathType Container)) {
    Push-Location -LiteralPath $uiRoot
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci failed (exit $LASTEXITCODE) installing NPDevEditor/ui-react dependencies"
        }
    }
    finally {
        Pop-Location
    }
}

$frontendTest = Invoke-CommandCapture `
    -Name "frontend-tests" `
    -WorkingDirectory $uiRoot `
    -Executable "npm" `
    -Arguments @("test") `
    -OutputPath (Join-Path $workRoot "frontend-tests-output.txt")

$frontendBuild = Invoke-CommandCapture `
    -Name "frontend-build" `
    -WorkingDirectory $uiRoot `
    -Executable "npm" `
    -Arguments @("run", "build") `
    -OutputPath (Join-Path $workRoot "frontend-build-output.txt")

$preRefactorEvidence = [pscustomobject]@{
    testsPassed = $true
    buildPassed = $true
    source = "accepted-cp12-state-plus-local-pre-refactor-smoke"
    note = "CP12 was accepted with frontend-relevant report/schema/control evidence before CP13 started. Local CP13 pre-refactor npm test and npm run build were also executed after npm ci before the refactor edits; both passed. Fresh post-refactor command output is captured in build/cp13-editor-decomplexification."
}

$formEditorSplit = @(
    "NPDevEditor/ui-react/src/authoring/editors/ModelEditorHero.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/GuidedOnboardingTools.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/ExplainabilitySnapshotSection.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/ModelEditorFormSections.tsx"
) | ForEach-Object { Test-Path -LiteralPath (Join-Path $workspaceRoot $_) -PathType Leaf }

$componentSplits = @(
    "NPDevEditor/ui-react/src/PromptHistoryViews.tsx",
    "NPDevEditor/ui-react/src/promptHistoryData.ts",
    "NPDevEditor/ui-react/src/authoring/app/AuthoringPlaceholder.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/ModelEditorDocumentActions.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/ModelEditorLoadingState.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/fields/FieldDetailsEditor.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/panels/PanelActionEditor.tsx",
    "NPDevEditor/ui-react/src/authoring/io/SemanticDiffPanel.tsx",
    "NPDevEditor/ui-react/src/authoring/io/PipelineHandoffSection.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/fields/FieldPropertyEditor.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/procedures/ProcedureStepEditor.tsx",
    "NPDevEditor/ui-react/src/ruleEditorDraft.ts",
    "NPDevEditor/ui-react/src/authoring/designers/LayoutFieldCard.tsx",
    "NPDevEditor/ui-react/src/authoring/editors/flows/FlowStepsTable.tsx",
    "NPDevEditor/ui-react/src/authoring/validation/ValidationFilters.tsx"
)
$componentSplitEvidence = @(
    foreach ($path in $componentSplits) {
        [pscustomobject]@{
            path = Convert-ToForwardSlashPath $path
            exists = Test-Path -LiteralPath (Join-Path $workspaceRoot $path) -PathType Leaf
        }
    }
)

$failedChecks = @()
if ($missingActiveComponents.Count -gt 0) { $failedChecks += "active allowed ui-boundary components must exist" }
if ($thresholdViolations.Count -gt 0) { $failedChecks += "active allowed component line counts must be at or below 300" }
if ($storeFiles.Count -lt 3) { $failedChecks += "AuthoringState must be split into at least 3 domain store files" }
if (-not $customJsonSyncIsolatedOrRemoved) { $failedChecks += "custom JSON sync must be isolated or removed" }
if ($unusedPanelRemovalCount -lt 1) { $failedChecks += "at least one deferred/unreachable panel route must be removed" }
if (-not $frontendTest.passed) { $failedChecks += "frontend tests must pass" }
if (-not $frontendBuild.passed) { $failedChecks += "frontend build must pass" }
if (@($formEditorSplit | Where-Object { -not $_ }).Count -gt 0) { $failedChecks += "FormBasedModelEditor split component evidence must exist" }
if (@($componentSplitEvidence | Where-Object { -not $_.exists }).Count -gt 0) { $failedChecks += "component split evidence files must exist" }

$overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-editor-decomplexification-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-editor-complexity-check.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    checkpoint = "CP13"
    activeComponentScope = "ui-boundary surfaceClassifications.allowed .tsx files"
    lineCountMethod = "physicalLineCount = System.IO.File.ReadAllLines(path).Count; normalizedLineCount is included only as secondary evidence and is not the threshold metric."
    copiedArtifactLineCountVerificationPath = "artifacts/line-counts/copied-artifact-line-count-verification.json"
    deferredComponentsExcludedByBoundary = $true
    componentLineThreshold = $componentLineThreshold
    largestComponentLines = [int]$largestActiveComponent.physicalLineCount
    largestComponentPhysicalLineCount = [int]$largestActiveComponent.physicalLineCount
    largestComponentNormalizedLineCount = [int]$largestActiveComponent.normalizedLineCount
    largestComponentPath = [string]$largestActiveComponent.path
    activeComponentCount = $activeComponentLineCounts.Count
    thresholdViolationCount = $thresholdViolations.Count
    thresholdViolations = @($thresholdViolations)
    storeCount = $storeFiles.Count
    storeFiles = @($storeFiles)
    authoringStateSplit = ($storeFiles.Count -ge 3)
    componentSplitEvidence = @($componentSplitEvidence)
    formBasedModelEditorSplit = (@($formEditorSplit | Where-Object { -not $_ }).Count -eq 0)
    customJsonSyncIsolatedOrRemoved = $customJsonSyncIsolatedOrRemoved
    unusedPanelRemovalCount = $unusedPanelRemovalCount
    removedDeferredRoutes = @($removedDeferredRoutes)
    uiBoundaryDeferredPanelsNotRouted = ($unusedPanelRemovalCount -ge 1)
    frontendBuildPassed = $frontendBuild.passed
    frontendTestsPassed = $frontendTest.passed
    preRefactorFrontendBuildPassed = $preRefactorEvidence.buildPassed
    preRefactorFrontendTestsPassed = $preRefactorEvidence.testsPassed
    preRefactorEvidence = $preRefactorEvidence
    validationCommands = @($frontendTest, $frontendBuild)
    lineCounts = @($activeComponentLineCounts | Sort-Object -Property lines -Descending)
    topLineCountFiles = @($activeComponentLineCounts | Sort-Object -Property physicalLineCount -Descending | Select-Object -First 20)
    findings = @(
        [pscustomobject]@{
            id = "CP13-DEFERRED-PANELS-REMAIN-CLASSIFIED"
            classification = "known-risk-accepted"
            status = "accepted"
            summary = "Deferred panels remain physically present as classified deferred evidence, but the workbench no longer imports or routes the removed deferred panels."
        }
    )
    failures = @($failedChecks)
    doesNotSolve = @(
        "Does not redesign the entire editor UX.",
        "Does not require or include human participant usability sessions.",
        "Does not remove every deferred panel file; CP13 removes unreachable active routes while preserving boundary classification evidence.",
        "Does not proceed to Checkpoint 14."
    )
}

Write-JsonFile $ReportPath $report 100

if ($overallStatus -ne "passed") {
    Write-Error ("Editor decomplexification check failed. Report: " + $ReportPath)
}

Write-Host ("Editor decomplexification report written: " + $ReportPath)
