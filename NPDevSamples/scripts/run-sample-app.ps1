param(
    [Parameter(Mandatory = $true)]
    [string]$SampleId,
    [string]$NPDevRoot = "",
    [string]$AppRoot = "",
    [int]$Port = 0,
    [string]$Profiles = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "sample-common.ps1")

$samplesRoot = Get-NPDevSamplesRoot -ScriptRoot $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($NPDevRoot)) {
    $NPDevRoot = Get-NPDevWorkspaceRoot -SamplesRoot $samplesRoot
}
$NPDevRoot = Normalize-AbsolutePath $NPDevRoot

$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId $SampleId
$config = Read-SampleConfig -Sample $sample

if ([string]::IsNullOrWhiteSpace($AppRoot)) {
    $AppRoot = $sample.AppRoot
}
else {
    $AppRoot = Normalize-AbsolutePath $AppRoot
}

if ($Port -le 0) {
    $Port = Get-ConfigInt -Config $config -Path @("runtime", "serverPort") -Fallback 8080
}

if ([string]::IsNullOrWhiteSpace($Profiles)) {
    $Profiles = Get-ConfigString -Config $config -Path @("runtime", "springProfile") -Fallback "dev,step0,trial"
}

$gradlew = Get-NPDevGradleWrapperExecutable $AppRoot
Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper"

Info ("Starting sample app from: " + $AppRoot)
Info ("URL after boot: http://localhost:" + $Port)

Push-Location $AppRoot
try {
    & $gradlew --no-daemon bootRun "--args=--spring.profiles.active=$Profiles --server.port=$Port"
    if ($LASTEXITCODE -ne 0) {
        Fail ("Generated app exited with code " + $LASTEXITCODE)
    }
}
finally {
    Pop-Location
}
