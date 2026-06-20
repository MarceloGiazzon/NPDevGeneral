param(
    [Parameter(Mandatory = $true)]
    [string]$SampleId,
    [string]$NPDevRoot = "",
    [string]$OutputRoot = "",
    [string]$RunId = "",
    [switch]$NoAssembleFinalApp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "sample-common.ps1")

$samplesRoot = Get-NPDevSamplesRoot -ScriptRoot $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($NPDevRoot)) {
    $NPDevRoot = Get-NPDevWorkspaceRoot -SamplesRoot $samplesRoot
}
$NPDevRoot = Normalize-AbsolutePath $NPDevRoot
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "sample-generation-" + (Get-Date).ToString("yyyyMMdd-HHmmssfff")
}

$sampleOutputRoot = Join-Path (Join-Path $samplesRoot $SampleId) "Output"
New-Item -ItemType Directory -Force -Path $sampleOutputRoot | Out-Null
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId $SampleId
Ensure-File -PathValue $sample.ModelPath -Label "Sample model.json"
Ensure-File -PathValue $sample.ConfigPath -Label "Sample config.json"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = $sample.OutputRoot
}
else {
    $OutputRoot = Normalize-AbsolutePath $OutputRoot
}

$generatorRoot = Join-Path $NPDevRoot "NPDevGenerator"
$runtimeHostRoot = Join-Path $NPDevRoot "NPDevRuntimeHost"
$gradlew = Join-Path $generatorRoot "gradlew.bat"
$artifactRoot = Join-Path $OutputRoot "ArtifactNP"
$finalAppRoot = Join-Path $OutputRoot "App"
$reportsRoot = Join-Path $OutputRoot "Reports"
$generationMarkerPath = Join-Path $reportsRoot "generation-run.json"
$dbDefinitionPath = Join-Path $sample.InputRoot "db.definition.json"

Ensure-File -PathValue $gradlew -Label "Generator Gradle wrapper"
Ensure-Directory -PathValue $runtimeHostRoot -Label "RuntimeHost base template"
Ensure-File -PathValue $dbDefinitionPath -Label "Sample db.definition.json"

Info ("NPDevRoot:    " + $NPDevRoot)
Info ("SampleId:     " + $sample.SampleId)
Info ("SampleRoot:   " + $sample.SampleRoot)
Info ("InputRoot:    " + $sample.InputRoot)
Info ("OutputRoot:   " + $OutputRoot)
Info ("ArtifactRoot: " + $artifactRoot)
Info ("AppRoot:      " + $finalAppRoot)
Info ("DbDefinition: " + $dbDefinitionPath)

$generatorArgs = @(
    "--config", $sample.ConfigPath,
    "--model", $sample.ModelPath,
    "--out", $artifactRoot,
    "--dbDefinitionPath", $dbDefinitionPath,
    "--runtimeHostTemplate", $runtimeHostRoot,
    "--finalAppOut", $finalAppRoot,
    "--clean"
)

if ($NoAssembleFinalApp) {
    $generatorArgs += "--no-assembleFinalApp"
}
else {
    $generatorArgs += @("--assembleFinalApp", "--cleanFinalApp")
}

$generatorArgLine = ($generatorArgs | ForEach-Object { Quote-Arg $_ }) -join " "
$workspaceGradleUserHome = Get-NPDevGradleUserHome $NPDevRoot

Info "Generating ArtifactNP and assembling the sample app through NPDevGenerator"
Push-Location $generatorRoot
$previousGradleUserHome = $env:GRADLE_USER_HOME
$env:GRADLE_USER_HOME = $workspaceGradleUserHome
try {
    & $gradlew ":generator:run" "--args=$generatorArgLine"
    if ($LASTEXITCODE -ne 0) {
        Fail ("Generator failed with exit code " + $LASTEXITCODE)
    }
}
finally {
    if ($null -eq $previousGradleUserHome) {
        Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue
    }
    else {
        $env:GRADLE_USER_HOME = $previousGradleUserHome
    }
    Pop-Location
}

Ok ("Sample generation complete for " + $sample.SampleId)
New-Item -ItemType Directory -Force -Path $reportsRoot | Out-Null
$generationMarker = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = "NPDevSamples\scripts\generate-sample-app.ps1"
    workspaceRoot = $NPDevRoot
    sampleId = $sample.SampleId
    outputRoot = $OutputRoot
    artifactRoot = $artifactRoot
    finalAppRoot = $finalAppRoot
    assembledFinalApp = -not [bool]$NoAssembleFinalApp
}
$generationMarker | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $generationMarkerPath -Encoding UTF8
if (-not $NoAssembleFinalApp) {
    Write-Host ("Next: powershell -NoProfile -ExecutionPolicy Bypass -File `"" + (Join-Path $PSScriptRoot "run-sample-app.ps1") + "`" -SampleId " + $sample.SampleId)
}
