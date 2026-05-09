param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$RuntimeHostLibsDir = "",
    [string]$SyncReportPath = "",
    [switch]$SkipSync
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Add-Failure {
    param([string]$Code, [string]$Message, [string]$Path = "", [object]$Details = $null)
    $script:failures += [pscustomobject]@{
        code = $Code
        message = $Message
        path = $Path
        details = $Details
    }
}

function Get-ArtifactRecord {
    param([string]$PathValue, [string]$Type)
    $exists = Test-Path -LiteralPath $PathValue -PathType Leaf
    return [pscustomobject]@{
        type = $Type
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PathValue
        exists = $exists
        sizeBytes = if ($exists) { [int64](Get-Item -LiteralPath $PathValue).Length } else { $null }
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $PathValue).Hash.ToLowerInvariant() } else { $null }
    }
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "runtimehost-staged-jar-preflight"
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-staged-jar-preflight-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$script:failures = @()
$commands = @()
$artifacts = @()

$runtimeHostLibs = if ([string]::IsNullOrWhiteSpace($RuntimeHostLibsDir)) {
    Get-NPDevRuntimeHostLibsDir $WorkspaceRoot
}
else {
    Normalize-NPDevPath $RuntimeHostLibsDir
}
$runtimeHostRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost"
$templatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template"
$syncScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\runtimehost\sync-runtimehost-libs.ps1"
$syncReportPath = if ([string]::IsNullOrWhiteSpace($SyncReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-libs-sync-report.json"
}
else {
    Normalize-NPDevPath $SyncReportPath
}
$syncLogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-staged-jar-preflight-sync.log"
$runtimeHostLibsManifestPath = Join-Path $runtimeHostLibs "runtimehost-libs-manifest.json"

Ensure-NPDevDirectory $runtimeHostRoot "RuntimeHost root"
Ensure-NPDevFile $templatePath "RuntimeHost build template"
Ensure-NPDevFile $syncScript "RuntimeHost libs sync script"

if (-not $SkipSync) {
    $syncCommand = Invoke-NPDevCommandEvidence `
        -WorkspaceRoot $WorkspaceRoot `
        -WorkingDirectory $WorkspaceRoot `
        -Executable "pwsh" `
        -Arguments @(
            "-NoProfile",
            "-File",
            $syncScript,
            "-WorkspaceRoot",
            $WorkspaceRoot,
            "-BuildLocalJars",
            "-RuntimeHostLibsDir",
            $runtimeHostLibs,
            "-ReportPath",
            $syncReportPath
        ) `
        -LogPath $syncLogPath
    $commands += [pscustomobject]@{
        name = "sync-runtimehost-libs"
        status = [string]$syncCommand.status
        executable = [string]$syncCommand.executable
        arguments = @($syncCommand.arguments)
        display = [string]$syncCommand.display
        exitCode = $syncCommand.exitCode
        startedAt = [string]$syncCommand.startedAt
        endedAt = [string]$syncCommand.endedAt
        durationSeconds = $syncCommand.durationSeconds
        logPath = $syncCommand.logPath
        outputTail = @($syncCommand.outputTail)
        failureReasons = @($syncCommand.failureReasons)
    }
    if ([string]$syncCommand.status -ne "passed") {
        Add-Failure -Code "runtimehost-libs-sync-failed" -Message "RuntimeHost libs sync command failed." -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $syncScript) -Details @{ exitCode = $syncCommand.exitCode; logPath = $syncCommand.logPath }
    }
}
else {
    $commands += [pscustomobject]@{
        name = "sync-runtimehost-libs"
        status = "skipped"
        executable = "pwsh"
        arguments = @("-NoProfile", "-File", (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $syncScript))
        display = "sync skipped by test/preflight override"
        exitCode = $null
        startedAt = $null
        endedAt = $null
        durationSeconds = 0
        logPath = $null
        outputTail = @()
        failureReasons = @()
    }
}

$syncReport = $null
if (Test-Path -LiteralPath $syncReportPath -PathType Leaf) {
    try {
        $syncReport = Get-Content -Raw -LiteralPath $syncReportPath | ConvertFrom-Json
        $artifacts += Get-ArtifactRecord -PathValue $syncReportPath -Type "sync-report"
    }
    catch {
        Add-Failure -Code "sync-report-invalid-json" -Message ("RuntimeHost libs sync report is not valid JSON: " + $_.Exception.Message) -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $syncReportPath)
    }
}
elseif (-not $SkipSync) {
    Add-Failure -Code "sync-report-missing" -Message "RuntimeHost libs sync command did not produce its report." -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $syncReportPath)
}

$requiredJarNames = @()
$requiredJarSource = "missing-sync-report"
if ($null -ne $syncReport) {
    if ($null -ne $syncReport.PSObject.Properties["requiredStagedJars"] -and @($syncReport.requiredStagedJars).Count -gt 0) {
        $requiredJarNames = @($syncReport.requiredStagedJars | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $requiredJarSource = "sync-report.requiredStagedJars"
    }
    elseif ($null -ne $syncReport.PSObject.Properties["sourceDiscoveredJars"] -and @($syncReport.sourceDiscoveredJars).Count -gt 0) {
        $requiredJarNames = @($syncReport.sourceDiscoveredJars | ForEach-Object { [string]$_.name } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $requiredJarSource = "sync-report.sourceDiscoveredJars.name"
    }
    else {
        $requiredJarNames = @(@($syncReport.copied) + @($syncReport.upToDate) | ForEach-Object { [string]$_.name } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $requiredJarSource = "sync-report.copied+upToDate.name"
    }
}
if ($requiredJarNames.Count -eq 0) {
    Add-Failure -Code "required-staged-jar-set-empty" -Message "RuntimeHost staged-jar preflight could not derive a required jar set from the sync report." -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $syncReportPath)
}
$manifestRequiredJars = @()
if (Test-Path -LiteralPath $runtimeHostLibsManifestPath -PathType Leaf) {
    try {
        $manifest = Get-Content -Raw -LiteralPath $runtimeHostLibsManifestPath | ConvertFrom-Json
        $manifestRequiredJars = @($manifest.requiredStagedJars | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $artifacts += Get-ArtifactRecord -PathValue $runtimeHostLibsManifestPath -Type "runtimehost-libs-manifest"
    }
    catch {
        Add-Failure -Code "runtimehost-libs-manifest-invalid-json" -Message ("RuntimeHost libs manifest is not valid JSON: " + $_.Exception.Message) -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $runtimeHostLibsManifestPath)
    }
}
else {
    Add-Failure -Code "runtimehost-libs-manifest-missing" -Message "RuntimeHost libs manifest is missing from the staged libs directory." -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $runtimeHostLibsManifestPath)
}
if ($requiredJarNames.Count -gt 0 -and $manifestRequiredJars.Count -gt 0) {
    $missingFromManifest = @($requiredJarNames | Where-Object { $manifestRequiredJars -notcontains $_ })
    $manifestOnly = @($manifestRequiredJars | Where-Object { $requiredJarNames -notcontains $_ })
    if ($missingFromManifest.Count -gt 0 -or $manifestOnly.Count -gt 0) {
        Add-Failure -Code "runtimehost-libs-manifest-mismatch" -Message "RuntimeHost libs manifest requiredStagedJars does not match sync report requiredStagedJars." -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $runtimeHostLibsManifestPath) -Details @{ missingFromManifest = $missingFromManifest; manifestOnly = $manifestOnly }
    }
}

$workspaceRootWithSeparator = $WorkspaceRoot.TrimEnd("\", "/") + [System.IO.Path]::DirectorySeparatorChar
if ($runtimeHostLibs.StartsWith($workspaceRootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)) {
    Add-Failure -Code "runtimehost-libs-inside-workspace" -Message "RuntimeHost staged jars must live outside the source/evidence workspace." -Path $runtimeHostLibs
}

$jarFindings = @()
foreach ($jarName in $requiredJarNames) {
    $jarPath = Join-Path $runtimeHostLibs $jarName
    $exists = Test-Path -LiteralPath $jarPath -PathType Leaf
    $artifact = Get-ArtifactRecord -PathValue $jarPath -Type "required-runtimehost-jar"
    $jarFindings += [pscustomobject]@{
        name = $jarName
        path = $artifact.path
        exists = $exists
        sizeBytes = $artifact.sizeBytes
        sha256 = $artifact.sha256
    }
    if ($exists) {
        $artifacts += $artifact
        if ($artifact.sizeBytes -le 0) {
            Add-Failure -Code "required-jar-empty" -Message ("Required RuntimeHost jar is empty: " + $jarName) -Path $artifact.path
        }
    }
    else {
        Add-Failure -Code "required-jar-missing" -Message ("Required RuntimeHost jar is missing: " + $jarName) -Path (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $jarPath)
    }
}

$migrationJars = @()
if (Test-Path -LiteralPath $runtimeHostLibs -PathType Container) {
    $migrationJars = @(Get-ChildItem -LiteralPath $runtimeHostLibs -Filter "npdev-migrations-*.jar" -File -ErrorAction SilentlyContinue)
}
if ($migrationJars.Count -gt 0) {
    Add-Failure -Code "migration-jar-staged" -Message "RuntimeHost staging must not include migration helper jars." -Path $runtimeHostLibs -Details @{ jars = @($migrationJars | ForEach-Object { $_.Name }) }
}

$templateText = Get-Content -Raw -LiteralPath $templatePath
$templateChecks = @(
    [pscustomobject]@{
        name = "verify-task-declared"
        passed = $templateText -match "tasks\.register\('verifyNpdevRuntimeHostLibs'\)"
        source = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $templatePath
    },
    [pscustomobject]@{
        name = "compile-java-depends-on-preflight"
        passed = $templateText -match "compileJava'\)\s*\{\s*dependsOn tasks\.named\('verifyNpdevRuntimeHostLibs'\)"
        source = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $templatePath
    },
    [pscustomobject]@{
        name = "template-uses-runtimehost-libs-manifest"
        passed = $templateText.Contains("runtimehost-libs-manifest.json") -and $templateText.Contains("requiredStagedJars")
        source = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $templatePath
    },
    [pscustomobject]@{
        name = "filetree-uses-external-runtimehost-libs"
        passed = $templateText -match "implementation fileTree\(dir: npdevRuntimeHostLibsDir, include: \['\*\.jar'\]\)"
        source = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $templatePath
    }
)
foreach ($check in $templateChecks) {
    if (-not [bool]$check.passed) {
        Add-Failure -Code "runtimehost-template-preflight-missing" -Message ("RuntimeHost template preflight contract failed: " + [string]$check.name) -Path ([string]$check.source)
    }
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-runtimehost-staged-jar-preflight-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-runtimehost-staged-jar-preflight.ps1"
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    runtimeHostLibs = [pscustomobject]@{
        path = $runtimeHostLibs
        location = "external-local-cache"
        outsideWorkspace = -not $runtimeHostLibs.StartsWith($workspaceRootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)
    }
    requiredJarSet = [pscustomobject]@{
        source = $requiredJarSource
        count = $requiredJarNames.Count
        names = $requiredJarNames
        manifestPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $runtimeHostLibsManifestPath
        manifestCount = $manifestRequiredJars.Count
    }
    requiredJars = $jarFindings
    templateChecks = $templateChecks
    syncReport = if ($null -eq $syncReport) { $null } else { [pscustomobject]@{ path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $syncReportPath; overallStatus = [string]$syncReport.overallStatus } }
    commands = @($commands)
    artifacts = @($artifacts)
    failures = @($failures)
}

Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk ("RuntimeHost staged-jar preflight passed. Report: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ReportPath))
    exit 0
}

Write-NPDevWarn ("RuntimeHost staged-jar preflight failed. Report: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ReportPath))
exit 1
