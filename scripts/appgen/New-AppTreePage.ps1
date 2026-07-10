<#
.SYNOPSIS
  Emit a read-only tree-view page (app-tree.html + app-tree.json) into an app's static folder.

.DESCRIPTION
  Standalone, run-on-demand companion to New-AppInfoPage.ps1. Reads an app's own
  definition\model.json + definition\config.json (NOT the generator's staged/compiled
  output) and writes a JSON tree (App > Config / Modules / Custom) plus an HTML page that
  renders it with expand/collapse only -- no editing. Re-run this script any time the
  model changes and reload app-tree.html to see the update; nothing else needs rebuilding.

  Must write into the App module's own src/main/resources/static (NOT npdev-generated/,
  which the runtime strict-execution validator hashes). See appgen-finalapp-recipe memory.

  Tree shape:
    <AppId>
      Config            -- flat property/value list from config.json
      Modules
        <ModuleName>
          Concepts       -- concept names declared with this module (default module "Default")
          Flows          -- flow names whose owning concept belongs to this module
      Custom
        Codas            -- concept names where the effective coda.allowed setting is true
                             (resolved from config.json defaults/overrides the same way
                             NpdevSettings.CODA_ALLOWED is resolved at generation time)
        Panels           -- panel names

.EXAMPLE
  & 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\New-AppTreePage.ps1' `
    -AppFolder 'D:\WorkSpace\NPDev\AppGen\apps\WmsOffice' `
    -StaticDir 'D:\WorkSpace\NPDev\Build\generated-finalapps\WmsOffice\App\src\main\resources\static'
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$AppFolder,
  [Parameter(Mandatory = $true)][string]$StaticDir,
  [string]$AppId = ''
)
$ErrorActionPreference = 'Stop'

function Read-JsonFile {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) { throw "JSON file not found: $Path" }
  Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 100
}

$Definition = Join-Path $AppFolder 'definition'
$ModelPath  = Join-Path $Definition 'model.json'
$ConfigPath = Join-Path $Definition 'config.json'
foreach ($p in @($AppFolder, $Definition, $ModelPath, $ConfigPath)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "Required path not found: $p" }
}
$ModelDir = Split-Path -Parent $ModelPath
$Model  = Read-JsonFile $ModelPath
$Config = Read-JsonFile $ConfigPath
if (-not $AppId) { $AppId = "$($Config.scenario.name)" }
if (-not $AppId) { $AppId = Split-Path -Leaf $AppFolder }

# ---- resolve one-file-per-concept ($ref) authoring, same convention as Build-NpdevApp.ps1 ----
function Resolve-RefNode {
  param($Node)
  if ($null -eq $Node) { return $null }
  if ($Node.PSObject.Properties.Name -contains '$ref' -and $Node.'$ref') {
    return Read-JsonFile (Join-Path $ModelDir $Node.'$ref')
  }
  return $Node
}

$concepts           = @($Model.concepts           | ForEach-Object { Resolve-RefNode $_ } | Where-Object { $_ -and $_.name })
$flows              = @($Model.flows               | ForEach-Object { Resolve-RefNode $_ } | Where-Object { $_ -and $_.name })
$customCapabilities = @($Model.customCapabilities  | ForEach-Object { Resolve-RefNode $_ } | Where-Object { $_ -and $_.name })
$panels             = @($Model.panels               | ForEach-Object { Resolve-RefNode $_ } | Where-Object { $_ -and $_.name })

# ---- tree node helpers ------------------------------------------------------
function New-FolderNode { param([string]$Label, [array]$Children = @())
  [ordered]@{ label = $Label; kind = 'folder'; children = @($Children) }
}
function New-LeafNode { param([string]$Label)
  [ordered]@{ label = $Label; kind = 'leaf' }
}
function New-LeafList { param([array]$Labels)
  if ($Labels.Count -eq 0) { return @(New-LeafNode '(none)') }
  return @($Labels | ForEach-Object { New-LeafNode $_ })
}

# ---- Config section ----------------------------------------------------------
$configLeaves = New-Object System.Collections.Generic.List[object]
function Add-ConfigLeaf($label, $value) { if ($null -ne $value -and "$value" -ne '') { $configLeaves.Add((New-LeafNode "${label}: $value")) } }
Add-ConfigLeaf 'App name'        $Config.scenario.name
Add-ConfigLeaf 'Description'     $Config.scenario.description
Add-ConfigLeaf 'Server port'     $Config.runtime.serverPort
Add-ConfigLeaf 'Spring profile'  $Config.runtime.springProfile
Add-ConfigLeaf 'API key'         $Config.trialDefaults.apiKey
Add-ConfigLeaf 'DB provider'     $Config.database.provider
Add-ConfigLeaf 'DB name'         $Config.database.database
Add-ConfigLeaf 'Console mode'    $Config.console.mode
Add-ConfigLeaf 'DSL version'     $Model.dslVersion
Add-ConfigLeaf 'Model version'   $Model.version
Add-ConfigLeaf 'Namespace'       $Model.namespace
Add-ConfigLeaf 'Model'           $Model.model
$configNode = New-FolderNode 'Config' $configLeaves

# ---- Modules section (Concepts + Flows grouped by concept.module) ------------
$DefaultModule = 'Default'
$UnassignedModule = 'Unassigned'
$conceptModuleByName = @{}
foreach ($c in $concepts) {
  $mod = if ($c.PSObject.Properties.Name -contains 'module' -and $c.module) { "$($c.module)" } else { $DefaultModule }
  $conceptModuleByName[$c.name] = $mod
}
$moduleNames = New-Object System.Collections.Generic.List[string]
foreach ($mod in ($conceptModuleByName.Values | Select-Object -Unique | Sort-Object)) { $moduleNames.Add($mod) }

$flowsByModule = @{}
foreach ($f in $flows) {
  $mod = $UnassignedModule
  if ($f.PSObject.Properties.Name -contains 'concept' -and $f.concept -and $conceptModuleByName.ContainsKey($f.concept)) {
    $mod = $conceptModuleByName[$f.concept]
  }
  if (-not $flowsByModule.ContainsKey($mod)) { $flowsByModule[$mod] = New-Object System.Collections.Generic.List[string] }
  $flowsByModule[$mod].Add($f.name)
}
if ($flowsByModule.ContainsKey($UnassignedModule) -and -not $moduleNames.Contains($UnassignedModule)) { $moduleNames.Add($UnassignedModule) }

$moduleNodes = @()
foreach ($mod in $moduleNames) {
  $conceptNames = @($concepts | Where-Object { $conceptModuleByName[$_.name] -eq $mod } | ForEach-Object { $_.name } | Sort-Object)
  $flowNames    = if ($flowsByModule.ContainsKey($mod)) { @($flowsByModule[$mod] | Sort-Object) } else { @() }
  $moduleNodes += New-FolderNode $mod @(
    (New-FolderNode 'Concepts' (New-LeafList $conceptNames))
    (New-FolderNode 'Flows'    (New-LeafList $flowNames))
  )
}
$modulesNode = New-FolderNode 'Modules' $moduleNodes

# ---- Custom section: Codas (coda.allowed resolution) + Panels ----------------
# Mirrors ConfigSettingsReader/SettingResolver precedence: concept:<Name> overrides
# module:<Module> overrides app defaults overrides the platform default (false).
$appCodaAllowed = $false
if ($Config.defaults -and ($Config.defaults.PSObject.Properties.Name -contains 'coda.allowed')) {
  $appCodaAllowed = [bool]$Config.defaults.'coda.allowed'
}
function Resolve-CodaAllowed {
  param([string]$ConceptName, [string]$ModuleName)
  $overrides = $Config.overrides
  if ($overrides) {
    $conceptSel = "concept:$ConceptName"
    if (($overrides.PSObject.Properties.Name -contains $conceptSel) -and
        ($overrides.$conceptSel.PSObject.Properties.Name -contains 'coda.allowed')) {
      return [bool]$overrides.$conceptSel.'coda.allowed'
    }
    if ($ModuleName) {
      $moduleSel = "module:$ModuleName"
      if (($overrides.PSObject.Properties.Name -contains $moduleSel) -and
          ($overrides.$moduleSel.PSObject.Properties.Name -contains 'coda.allowed')) {
        return [bool]$overrides.$moduleSel.'coda.allowed'
      }
    }
  }
  return $appCodaAllowed
}
$codaConceptNames = @($concepts | Where-Object { Resolve-CodaAllowed -ConceptName $_.name -ModuleName $conceptModuleByName[$_.name] } | ForEach-Object { $_.name } | Sort-Object)
$panelNames = @($panels | ForEach-Object { $_.name } | Sort-Object)

$customNode = New-FolderNode 'Custom' @(
  (New-FolderNode 'Codas'  (New-LeafList $codaConceptNames))
  (New-FolderNode 'Panels' (New-LeafList $panelNames))
)

$root = New-FolderNode $AppId @($configNode, $modulesNode, $customNode)
$tree = [ordered]@{
  schemaVersion = 'npdev-app-tree.v1'
  appId         = $AppId
  generatedAt   = (Get-Date).ToString('o')
  root          = $root
}

New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null
$treeJson = $tree | ConvertTo-Json -Depth 100
Set-Content -LiteralPath (Join-Path $StaticDir 'app-tree.json') -Value $treeJson -Encoding UTF8

$tpl = @'
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__APP__ - tree</title>
<style>
 body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;font-size:14px}
 header{padding:12px 16px;background:#1b1b1b;font-size:16px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
 header small{opacity:.6;font-size:12px}
 header button{background:#2d6cdf;color:#fff;border:0;border-radius:4px;padding:5px 10px;cursor:pointer;font-size:13px}
 main{padding:12px 16px}
 .err{color:#f88;padding:12px 16px}
 ul.tree,ul.children{list-style:none;margin:0;padding-left:18px}
 ul.tree{padding-left:0}
 li{margin:2px 0}
 .node{display:flex;align-items:center;gap:6px;padding:2px 4px;border-radius:3px}
 .node.folder{cursor:pointer}
 .node.folder:hover{background:#1c1c1c}
 .caret{display:inline-block;width:1em;color:#9bd;user-select:none}
 .bullet{display:inline-block;width:1em;color:#567;text-align:center}
 .label{white-space:pre-wrap}
 .node.folder>.label{color:#dce}
 .node.leaf>.label{color:#cde}
 .count{opacity:.5;font-size:12px}
 ul.children.collapsed{display:none}
</style></head><body>
<header>
  <b id="appLabel">__APP__</b> <small>tree view &middot; read-only &middot; loaded from app-tree.json</small>
  <button id="expandAll">Expand all</button>
  <button id="collapseAll">Collapse all</button>
  <button id="reload">Reload</button>
</header>
<main><ul class="tree" id="tree"></ul></main>
<script>
const treeEl = document.getElementById('tree');
function buildNode(node, depth){
  const li = document.createElement('li');
  if (node.kind === 'folder') {
    const row = document.createElement('div'); row.className = 'node folder';
    const caret = document.createElement('span'); caret.className = 'caret';
    const label = document.createElement('span'); label.className = 'label'; label.textContent = node.label;
    const count = document.createElement('span'); count.className = 'count'; count.textContent = '(' + (node.children ? node.children.length : 0) + ')';
    row.appendChild(caret); row.appendChild(label); row.appendChild(count);
    const childUl = document.createElement('ul'); childUl.className = 'children';
    const open = depth < 1;
    caret.textContent = open ? '▾' : '▸';
    if (!open) childUl.classList.add('collapsed');
    (node.children || []).forEach(c => childUl.appendChild(buildNode(c, depth + 1)));
    row.onclick = () => {
      const collapsed = childUl.classList.toggle('collapsed');
      caret.textContent = collapsed ? '▸' : '▾';
    };
    li.appendChild(row); li.appendChild(childUl);
  } else {
    const row = document.createElement('div'); row.className = 'node leaf';
    const bullet = document.createElement('span'); bullet.className = 'bullet'; bullet.textContent = '•';
    const label = document.createElement('span'); label.className = 'label'; label.textContent = node.label;
    row.appendChild(bullet); row.appendChild(label);
    li.appendChild(row);
  }
  return li;
}
function setAll(collapsed){
  document.querySelectorAll('ul.children').forEach(ul => ul.classList.toggle('collapsed', collapsed));
  document.querySelectorAll('.node.folder .caret').forEach(c => c.textContent = collapsed ? '▸' : '▾');
}
document.getElementById('expandAll').onclick = () => setAll(false);
document.getElementById('collapseAll').onclick = () => setAll(true);
document.getElementById('reload').onclick = () => window.location.reload();
fetch('app-tree.json', { cache: 'no-store' })
  .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
  .then(data => {
    document.getElementById('appLabel').textContent = data.appId || data.root.label;
    (data.root.children || []).forEach(c => treeEl.appendChild(buildNode(c, 0)));
  })
  .catch(e => {
    const p = document.createElement('p'); p.className = 'err';
    p.textContent = 'Could not load app-tree.json: ' + e.message + ' -- run New-AppTreePage.ps1 first, and open this page over http:// (not file://).';
    treeEl.replaceWith(p);
  });
</script>
</body></html>
'@
$html = $tpl.Replace('__APP__', $AppId)
Set-Content -LiteralPath (Join-Path $StaticDir 'app-tree.html') -Value $html -Encoding UTF8

Write-Host "Emitted tree page: $(Join-Path $StaticDir 'app-tree.html') (+ app-tree.json)"
