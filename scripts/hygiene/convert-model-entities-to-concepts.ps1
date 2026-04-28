[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$RelativePaths = @("NPDevContract\examples")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$rewritten = @()
foreach ($relativePath in $RelativePaths) {
    $path = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $files = @((Get-Item -LiteralPath $path))
    }
    elseif (Test-Path -LiteralPath $path -PathType Container) {
        $files = @(Get-ChildItem -LiteralPath $path -Recurse -File -Include *.json,*.model.json)
    }
    else {
        throw "Path not found: $relativePath"
    }

    foreach ($file in $files) {
        $raw = Get-Content -LiteralPath $file.FullName -Raw
        if (-not ($raw -match '"entities"\s*:')) {
            continue
        }

        $json = $raw | ConvertFrom-Json
        $properties = @($json.PSObject.Properties.Name)
        if (($properties -contains "entities") -and -not ($properties -contains "concepts")) {
            $ordered = [ordered]@{}
            foreach ($property in $json.PSObject.Properties) {
                if ($property.Name -eq "entities") {
                    $ordered["concepts"] = $property.Value
                }
                else {
                    $ordered[$property.Name] = $property.Value
                }
            }
            if ($PSCmdlet.ShouldProcess($file.FullName, "rewrite entities to concepts")) {
                Write-NPDevJsonFile $file.FullName ([pscustomobject]$ordered)
                $rewritten += Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
            }
        }
    }
}

Write-NPDevInfo ("Canonicalized model files: " + $rewritten.Count)
foreach ($path in $rewritten) {
    Write-NPDevInfo (" - " + $path)
}
