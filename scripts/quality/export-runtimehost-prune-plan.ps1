[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

function Get-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required JSON file not found: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 100
}

function Get-ReferenceKey {
    param([object]$Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [string]) { return $null }
    $type = $Value.GetType()
    if ($type.IsValueType) { return $null }
    return $type.FullName + ':' + [Runtime.CompilerServices.RuntimeHelpers]::GetHashCode($Value)
}

function Get-ChildNodes {
    param([object]$Node)
    $children = New-Object 'System.Collections.Generic.List[object]'
    if ($null -eq $Node) { return $children }

    if ($Node -is [System.Collections.IDictionary]) {
        foreach ($entry in $Node.GetEnumerator()) {
            $children.Add($entry.Value) | Out-Null
        }
        return $children
    }

    if ($Node -is [System.Collections.IEnumerable] -and -not ($Node -is [string])) {
        foreach ($item in $Node) {
            $children.Add($item) | Out-Null
        }
        return $children
    }

    if ($Node.PSObject -and $Node.PSObject.Properties.Count -gt 0) {
        foreach ($prop in $Node.PSObject.Properties) {
            $children.Add($prop.Value) | Out-Null
        }
    }

    return $children
}

function Get-PropertyMap {
    param([object]$Node)
    $map = @{}
    if ($null -eq $Node) { return $map }
    if (-not ($Node.PSObject -and $Node.PSObject.Properties.Count -gt 0)) { return $map }
    foreach ($prop in $Node.PSObject.Properties) {
        if (-not $map.ContainsKey($prop.Name.ToLowerInvariant())) {
            $map[$prop.Name.ToLowerInvariant()] = $prop.Value
        }
    }
    return $map
}

$reportsRoot = Join-Path $WorkspaceRoot 'scripts\reports\out'
$footprintReportPath = Join-Path $reportsRoot 'runtime-footprint-report.json'
$outJsonPath = Join-Path $reportsRoot 'runtimehost-prune-plan.json'
$outMdPath = Join-Path $reportsRoot 'runtimehost-prune-plan.md'

$footprintReport = Get-JsonFile -Path $footprintReportPath

$queue = New-Object 'System.Collections.Generic.Queue[object]'
$visited = New-Object 'System.Collections.Generic.HashSet[string]'
$queue.Enqueue($footprintReport)

$results = New-Object 'System.Collections.Generic.List[object]'
$seen = New-Object 'System.Collections.Generic.HashSet[string]'

while ($queue.Count -gt 0) {
    $node = $queue.Dequeue()
    if ($null -eq $node) { continue }

    $refKey = Get-ReferenceKey -Value $node
    if ($null -ne $refKey) {
        if (-not $visited.Add($refKey)) {
            continue
        }
    }

    $props = Get-PropertyMap -Node $node
    if ($props.Count -gt 0) {
        $name = $null
        foreach ($candidate in @('name','simpleName','id','identifier','fqcn')) {
            if ($props.ContainsKey($candidate) -and $null -ne $props[$candidate]) {
                $name = [string]$props[$candidate]
                break
            }
        }

        if (-not [string]::IsNullOrWhiteSpace($name)) {
            $type = $null
            foreach ($candidate in @('type','surfaceType','kind','entryType')) {
                if ($props.ContainsKey($candidate) -and $null -ne $props[$candidate]) {
                    $type = [string]$props[$candidate]
                    break
                }
            }

            $classification = $null
            foreach ($candidate in @('classification','status','bucket','category','disposition')) {
                if ($props.ContainsKey($candidate) -and $null -ne $props[$candidate]) {
                    $classification = [string]$props[$candidate]
                    break
                }
            }

            $referenceHitCount = $null
            foreach ($candidate in @('referencehitcount','referencehits','hitcount','references')) {
                if ($props.ContainsKey($candidate)) {
                    try {
                        $referenceHitCount = [int]$props[$candidate]
                        break
                    } catch {
                        $referenceHitCount = $null
                    }
                }
            }

            $keep = ($classification -match 'remove|dead|transitional|internal') -or ($referenceHitCount -eq 0)
            if ($keep) {
                $key = "{0}|{1}|{2}|{3}" -f $type, $name, $classification, $referenceHitCount
                if ($seen.Add($key)) {
                    $results.Add([pscustomobject]@{
                        type = $type
                        name = $name
                        classification = $classification
                        referenceHitCount = $referenceHitCount
                    }) | Out-Null
                }
            }
        }
    }

    foreach ($child in Get-ChildNodes -Node $node) {
        if ($null -ne $child) {
            $queue.Enqueue($child)
        }
    }
}

$filtered = @($results | Sort-Object type, classification, name)

$plan = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    scriptPath = 'scripts\quality\export-runtimehost-prune-plan.ps1'
    workspaceRoot = $WorkspaceRoot
    overallStatus = 'advisory'
    candidateCount = $filtered.Count
    candidates = $filtered
}

$plan | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $outJsonPath -Encoding UTF8

$lines = @()
$lines += '# RuntimeHost prune plan'
$lines += ''
$lines += 'This file is advisory. Use it to decide the next safe removal/reclassification batch.'
$lines += ''
$lines += '| Type | Name | Classification | ReferenceHitCount |'
$lines += '|---|---|---:|---:|'
foreach ($item in $filtered) {
    $lines += "| $($item.type) | $($item.name) | $($item.classification) | $($item.referenceHitCount) |"
}
if ($filtered.Count -eq 0) {
    $lines += '| n/a | No prune candidates were extracted from the current runtime-footprint-report.json | n/a | n/a |'
}
$lines -join [Environment]::NewLine | Set-Content -LiteralPath $outMdPath -Encoding UTF8

Write-Host "OK    RuntimeHost prune plan exported:"
Write-Host "      JSON: $outJsonPath"
Write-Host "      MD:   $outMdPath"
