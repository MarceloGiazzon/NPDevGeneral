param(
    [string]$WorkspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path,
    [string]$OutDir = "",
    [switch]$IncludeWorkspaceDocs = $true,
    [string]$Stamp = "",
    [switch]$Quiet
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "statezip-common.ps1")

New-NPDevSubprojectStateZip `
    -WorkspaceRoot $WorkspaceRoot `
    -SubprojectName "NPDevSamples" `
    -OutDir $OutDir `
    -IncludeWorkspaceDocs:$IncludeWorkspaceDocs `
    -Stamp $Stamp `
    -Quiet:$Quiet `
    -ExtraExcludeDirNames @(
        "App",
        "ArtifactNP"
    )
