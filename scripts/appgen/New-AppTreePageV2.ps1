<#
.SYNOPSIS
  Emit a read-only, COMPLETE, CATEGORIZED tree-view page (app-tree-v2.html + app-tree-v2.json)
  for an app -- the advanced sibling of New-AppTreePage.ps1.

.DESCRIPTION
  Fork of New-AppTreePage.ps1 (2026-08-16), kept as a SEPARATE script/output so the original
  app-tree.html/.json are untouched -- both are available side by side. Same source data and
  same read-only/no-editing premise; two differences over the original:

  1. Everything -- not just the Model section -- is grouped GeneXus-Knowledge-Base style (2026-08-16
     revision, replacing this script's own first flat-Model-only categorization): a top-level
     Objects / Features / Configs / Tests split, mirroring GeneXus's own KB folder structure
     (Transactions/Web Panels/Procedures as Objects; a Properties/general-config area; a
     tests/checks area). Objects itself splits into Concepts (data shape: concepts, domainTypes,
     aggregates -- GeneXus's Transaction), Panels (every UI surface: autoPanels/panels/selectors/
     documents/guidePages/pages.json/menu.json, PLUS their implementation source -- widget JS,
     hand-authored web/ pages, trusted-source panel HTML -- GeneXus's Web Panel), and Procedures
     (flows/procedures/orchestrationRules/conversions, PLUS their Java source -- custom-capability
     plugins, trusted-source procedures -- GeneXus's Procedure object). Features holds the
     cross-cutting, non-object DSL wiring (packs, roles, properties, capabilities, bindings,
     events, queries, ...). Configs splits into Project General (config.json + the model's own
     identity/meta fields) and DB Engines (db.definition.json). Tests splits into Checks
     (trusted-source-manifest.json's policy/expectedOutcome), Scripts (smoke-plan.json, when an
     app has one), and Seed Data (definition/seeds/*.json).
     This is a PURE regroup, not a data change: every key/file this script reads is placed into
     exactly one bucket via an explicit lookup table below; anything the table doesn't recognize
     (a newer DSL addition) surfaces under a top-level "Other" bucket rather than being silently
     dropped -- see CLAUDE.md's REG-108 note on why silent-drop is the one failure mode to design
     out here.
  2. Array items get a real, meaningful label instead of a bare "[0]"/"[1]" index. The original
     script already tried a short, English-only key list (name/label/title/...); this version
     broadens it, adds a generic "first short scalar property, in declared order" fallback (so
     Portuguese-named domain fields like "nome"/"codigo" -- common in this app's own seed data --
     get a real label too, with no per-language key list needed), and only falls back to a
     numbered placeholder ("<parent> #<n>") when an item truly has no scalar to show at all.

  Must write into the App module's own src/main/resources/static (NOT npdev-generated/,
  which the runtime strict-execution validator hashes). See appgen-finalapp-recipe memory.

.EXAMPLE
  & 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\New-AppTreePageV2.ps1' `
    -AppFolder 'D:\WorkSpace\NPDev\AppGen\apps\_official\WmsOffice' `
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

# Two known app layouts: AppGen apps keep model.json/config.json/etc. under a definition/
# subfolder (concepts split one-per-file, packs/, capabilities/, ...); installer/`npdev dev`-created
# apps (e.g. C:\NPDev_Install\Apps\<id>) keep them flat at the app root, no definition/ subfolder at
# all. Auto-detect rather than requiring a flag: prefer definition/model.json when it exists, fall
# back to a flat model.json at $AppFolder itself.
$Definition = Join-Path $AppFolder 'definition'
if (-not (Test-Path -LiteralPath (Join-Path $Definition 'model.json'))) {
  $Definition = $AppFolder
}
$ModelPath  = Join-Path $Definition 'model.json'
$ConfigPath = Join-Path $Definition 'config.json'
foreach ($p in @($AppFolder, $Definition, $ModelPath)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "Required path not found: $p" }
}
$Model  = Read-JsonFile $ModelPath
$Config = if (Test-Path -LiteralPath $ConfigPath) { Read-JsonFile $ConfigPath } else { $null }
if (-not $AppId) { $AppId = "$($Config.scenario.name)" }
if (-not $AppId) { $AppId = "$($Model.model)" }
if (-not $AppId) { $AppId = Split-Path -Leaf $AppFolder }

# ---- recursively resolve every "$ref" against its own containing folder -------
# A $ref node is replaced by the loaded file (itself recursively resolved). Any
# sibling keys on the $ref node (e.g. packs' "as") are preserved.
function Resolve-Refs {
  param($Node, [string]$BaseDir)
  if ($null -eq $Node) { return $null }
  if ($Node -is [string] -or $Node -is [bool] -or $Node -is [ValueType]) { return $Node }
  if ($Node -is [System.Management.Automation.PSCustomObject]) {
    $refProp = $Node.PSObject.Properties | Where-Object { $_.Name -eq '$ref' } | Select-Object -First 1
    if ($refProp -and $refProp.Value) {
      $refPath = Join-Path $BaseDir ("$($refProp.Value)" -replace '/', '\')
      if (Test-Path -LiteralPath $refPath) {
        $loaded   = Read-JsonFile $refPath
        $resolved = Resolve-Refs $loaded (Split-Path -Parent $refPath)
        foreach ($p in $Node.PSObject.Properties) {
          if ($p.Name -ne '$ref' -and ($resolved.PSObject.Properties.Name -notcontains $p.Name)) {
            $resolved | Add-Member -NotePropertyName $p.Name -NotePropertyValue $p.Value -Force
          }
        }
        return $resolved
      }
    }
    $out = [ordered]@{}
    foreach ($p in $Node.PSObject.Properties) {
      if ($p.Name -eq '$schema') { continue }
      $out[$p.Name] = Resolve-Refs $p.Value $BaseDir
    }
    return [pscustomobject]$out
  }
  if ($Node -is [System.Collections.IEnumerable]) {
    return @($Node | ForEach-Object { Resolve-Refs $_ $BaseDir })
  }
  return $Node
}

$resolvedModel = Resolve-Refs $Model $Definition

# ---- gather the source code of code-bearing objects --------------------------
# Custom-capability Java plugins, app widget scripts, trusted-source procedure/panel source, and
# the bespoke web/ UI pages. Each file renders in the in-page code viewer; the key keeps the file
# extension so the viewer can tint syntax and show a language pill.
$capSources     = [ordered]@{}
$widgetSources  = [ordered]@{}
$webSources     = [ordered]@{}
$trustedProcSrc = [ordered]@{}
$trustedPanelSrc = [ordered]@{}

$capDir = Join-Path $Definition 'capabilities'
if (Test-Path -LiteralPath $capDir) {
  Get-ChildItem -LiteralPath $capDir -Directory | ForEach-Object {
    $pluginPath = Join-Path $_.FullName 'capability.plugin.json'
    if (Test-Path -LiteralPath $pluginPath) {
      $pj = Read-JsonFile $pluginPath
      $srcRoot = $null
      if ($pj.implementation -and $pj.implementation.sourceRoot) {
        $srcRoot = Join-Path $Definition ("$($pj.implementation.sourceRoot)" -replace '/', '\')
      }
      if ($srcRoot -and (Test-Path -LiteralPath $srcRoot)) {
        Get-ChildItem -LiteralPath $srcRoot -Recurse -File -Include *.java, *.kt, *.groovy | ForEach-Object {
          $capSources["$($pj.capability) / $($_.Name)"] = (Get-Content -LiteralPath $_.FullName -Raw)
        }
      }
    }
  }
}

$widgetsDir = Join-Path $Definition 'widgets'
if (Test-Path -LiteralPath $widgetsDir) {
  Get-ChildItem -LiteralPath $widgetsDir -File -Include *.js, *.ts, *.css | ForEach-Object {
    $widgetSources["$($_.Name)"] = (Get-Content -LiteralPath $_.FullName -Raw)
  }
}

# web/ holds the app's bespoke UI screens (custom HTML pages + theme.css). It lives beside
# definition/, under the app folder.
$webDir = Join-Path $AppFolder 'web'
if (Test-Path -LiteralPath $webDir) {
  $webRootLen = $webDir.TrimEnd('\').Length + 1
  Get-ChildItem -LiteralPath $webDir -Recurse -File -Include *.html, *.htm, *.css, *.js | Sort-Object FullName | ForEach-Object {
    $rel = $_.FullName.Substring($webRootLen).Replace('\', '/')
    $webSources[$rel] = (Get-Content -LiteralPath $_.FullName -Raw)
  }
}

# trusted-source/procedure and trusted-source/panel: real GeneXus-shaped "Procedure"/"Panel"
# objects (server-authored, capability-gated) that neither the original app-tree.html nor this
# script's first revision ever surfaced -- a real fidelity gap, closed here.
$trustedDir = Join-Path $Definition 'trusted-source'
$trustedProcDir  = Join-Path $trustedDir 'procedure'
$trustedPanelDir = Join-Path $trustedDir 'panel'
if (Test-Path -LiteralPath $trustedProcDir) {
  Get-ChildItem -LiteralPath $trustedProcDir -File -Include *.java | ForEach-Object {
    $trustedProcSrc[$_.Name] = (Get-Content -LiteralPath $_.FullName -Raw)
  }
}
if (Test-Path -LiteralPath $trustedPanelDir) {
  Get-ChildItem -LiteralPath $trustedPanelDir -File -Include *.html | ForEach-Object {
    $trustedPanelSrc[$_.Name] = (Get-Content -LiteralPath $_.FullName -Raw)
  }
}

# ---- load sibling definition files ---------------------------------------
function Load-OptionalJson { param([string]$Name)
  $p = Join-Path $Definition $Name
  if (Test-Path -LiteralPath $p) { return (Resolve-Refs (Read-JsonFile $p) $Definition) }
  return $null
}
$pages     = Load-OptionalJson 'pages.json'
$menu      = Load-OptionalJson 'menu.json'
$db        = Load-OptionalJson 'db.definition.json'
$ts        = Load-OptionalJson 'trusted-source-manifest.json'
$smokePlan = Load-OptionalJson 'smoke-plan.json'

$seedObj = [ordered]@{}
$seedsDir = Join-Path $Definition 'seeds'
if (Test-Path -LiteralPath $seedsDir) {
  Get-ChildItem -LiteralPath $seedsDir -File -Include *.json | ForEach-Object {
    $seedObj["$($_.Name)"] = (Resolve-Refs (Read-JsonFile $_.FullName) $seedsDir)
  }
}

# ---- GeneXus-Knowledge-Base-style grouping ------------------------------------
# Objects (Concepts / Panels / Procedures) -- Features -- Configs (Project General / DB Engines)
# -- Tests (Checks / Scripts / Seed Data). Explicit key -> bucket lookup for the Model's own
# top-level DSL keys (from NPdevContract/schemas/model.schema.json's root property list); every
# other section (config.json, db.definition.json, seeds, source code, trusted-source, ...) is
# placed by hand below it. Any Model key this table doesn't recognize (a DSL addition made after
# this script was written) surfaces under a top-level "Other" bucket -- never silently dropped,
# see CLAUDE.md's REG-108 note.
$ModelKeyToBucket = @{
  # Objects > Concepts -- GeneXus Transaction: the data shape itself.
  concepts            = 'Concepts'
  domainTypes         = 'Concepts'
  aggregates          = 'Concepts'
  # Objects > Panels -- GeneXus Web Panel: UI surfaces.
  autoPanels          = 'Panels'
  panels              = 'Panels'
  selectors           = 'Panels'
  documents           = 'Panels'
  guidePages          = 'Panels'
  # Objects > Procedures -- GeneXus Procedure: server-side behavior.
  flows               = 'Procedures'
  procedures          = 'Procedures'
  orchestrationRules  = 'Procedures'
  orchestrations      = 'Procedures'
  conversions         = 'Procedures'
  # Features -- cross-cutting DSL wiring, not a concrete KB object.
  packs               = 'Features'
  fragments           = 'Features'
  contexts            = 'Features'
  provides            = 'Features'
  roles               = 'Features'
  propertyScopes      = 'Features'
  properties          = 'Features'
  capabilities        = 'Features'
  customCapabilities  = 'Features'
  bindings            = 'Features'
  events              = 'Features'
  queries             = 'Features'
  ruleProfiles        = 'Features'
  # Configs > Project General -- the model's own identity/meta fields.
  schemaVersion       = 'ProjectGeneral'
  dslVersion          = 'ProjectGeneral'
  namespace           = 'ProjectGeneral'
  model               = 'ProjectGeneral'
  version             = 'ProjectGeneral'
  metadata            = 'ProjectGeneral'
  settings            = 'ProjectGeneral'
  externalAi          = 'ProjectGeneral'
}

function New-Bucket { [ordered]@{} }
$objConcepts  = New-Bucket
$objPanels    = New-Bucket
$objProcs     = New-Bucket
$features     = New-Bucket
$projGeneral  = New-Bucket
$otherModel   = New-Bucket

foreach ($p in $resolvedModel.PSObject.Properties) {
  switch ($ModelKeyToBucket[$p.Name]) {
    'Concepts'      { $objConcepts[$p.Name] = $p.Value }
    'Panels'        { $objPanels[$p.Name]   = $p.Value }
    'Procedures'    { $objProcs[$p.Name]    = $p.Value }
    'Features'      { $features[$p.Name]    = $p.Value }
    'ProjectGeneral'{ $projGeneral[$p.Name] = $p.Value }
    default         { $otherModel[$p.Name]  = $p.Value }
  }
}

# Panels also gets its UI-adjacent siblings: the app's navigation (menu.json), its declared
# companion screens (pages.json), and every piece of PANEL implementation source.
if ($null -ne $menu)  { $objPanels['Menu (navigation)'] = $menu }
if ($null -ne $pages) { $objPanels['Pages (companion screens)'] = $pages }
if ($widgetSources.Count      -gt 0) { $objPanels['Widget source']         = [pscustomobject]$widgetSources }
if ($webSources.Count         -gt 0) { $objPanels['Web page source']       = [pscustomobject]$webSources }
if ($trustedPanelSrc.Count    -gt 0) { $objPanels['Trusted panel source']  = [pscustomobject]$trustedPanelSrc }

# Procedures also gets every piece of PROCEDURE implementation source.
if ($capSources.Count      -gt 0) { $objProcs['Capability source']        = [pscustomobject]$capSources }
if ($trustedProcSrc.Count  -gt 0) { $objProcs['Trusted procedure source'] = [pscustomobject]$trustedProcSrc }

$objects = [ordered]@{}
if ($objConcepts.Count -gt 0) { $objects['Concepts']   = [pscustomobject]$objConcepts }
if ($objPanels.Count   -gt 0) { $objects['Panels']     = [pscustomobject]$objPanels }
if ($objProcs.Count    -gt 0) { $objects['Procedures'] = [pscustomobject]$objProcs }

$configs = [ordered]@{}
$projGeneralOut = [ordered]@{}
if ($Config)              { $projGeneralOut['App Config']    = $Config }
if ($projGeneral.Count -gt 0) { $projGeneralOut['Model Overview'] = [pscustomobject]$projGeneral }
if ($projGeneralOut.Count -gt 0) { $configs['Project General'] = [pscustomobject]$projGeneralOut }
if ($null -ne $db)        { $configs['DB Engines'] = $db }

$tests = [ordered]@{}
if ($null -ne $ts)        { $tests['Checks']    = $ts }
if ($null -ne $smokePlan) { $tests['Scripts']   = $smokePlan }
if ($seedObj.Count -gt 0) { $tests['Seed Data'] = [pscustomobject]$seedObj }

$sections = [ordered]@{}
if ($objects.Count  -gt 0) { $sections['Objects']  = [pscustomobject]$objects }
if ($features.Count -gt 0) { $sections['Features'] = [pscustomobject]$features }
if ($configs.Count  -gt 0) { $sections['Configs']  = [pscustomobject]$configs }
if ($tests.Count    -gt 0) { $sections['Tests']    = [pscustomobject]$tests }
if ($otherModel.Count -gt 0) { $sections['Other']  = [pscustomobject]$otherModel }

$doc = [ordered]@{
  schemaVersion = 'npdev-app-tree.v3'
  appId         = $AppId
  generatedAt   = (Get-Date).ToString('o')
  sections      = [pscustomobject]$sections
}

New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null
$docJson = $doc | ConvertTo-Json -Depth 100
Set-Content -LiteralPath (Join-Path $StaticDir 'app-tree-v2.json') -Value $docJson -Encoding UTF8

# ---- app-files.json: the raw project tree, verbatim, for the "Export all" ZIP -------------
# Unlike app-tree.json (resolved model), this preserves the ACTUAL on-disk file structure --
# one file per concept, capability sources, web pages, packs, etc. -- so the exported ZIP is a
# faithful copy of the app's definition. Text files only; keyed by path relative to the app root.
$textExt = @('*.json','*.java','*.kt','*.groovy','*.js','*.ts','*.mjs','*.css','*.html','*.htm',
             '*.md','*.txt','*.yml','*.yaml','*.xml','*.properties','*.sql','*.csv')
$rootLen = $AppFolder.TrimEnd('\').Length + 1
$projectFiles = New-Object System.Collections.Generic.List[object]
Get-ChildItem -LiteralPath $AppFolder -Recurse -File -Include $textExt |
  Where-Object { $_.FullName -notmatch '\\(build|target|node_modules|\.git|bin|obj)\\' } |
  Sort-Object FullName | ForEach-Object {
    $rel = $_.FullName.Substring($rootLen).Replace('\', '/')
    $projectFiles.Add([ordered]@{ path = $rel; content = (Get-Content -LiteralPath $_.FullName -Raw) })
  }
$filesArr = $projectFiles.ToArray()
$filesDoc = [ordered]@{
  schemaVersion = 'npdev-app-files.v1'
  appId         = $AppId
  root          = $AppId
  generatedAt   = (Get-Date).ToString('o')
  files         = $filesArr
}
$filesJson = $filesDoc | ConvertTo-Json -Depth 100
Set-Content -LiteralPath (Join-Path $StaticDir 'app-files.json') -Value $filesJson -Encoding UTF8

$tpl = @'
<!DOCTYPE html>
<html lang="en" data-theme="dark"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__APP__ - definition tree (advanced)</title>
<style>
:root{
  --bg:#0b0e14;--bg2:#0f1420;--panel:#111725;--panel2:#0c1119;--border:#1e2637;--border2:#28324a;
  --text:#e6edf7;--muted:#7d8aa3;--key:#8ea9d6;--fold:#e7eefc;
  --accent:#4c8dff;--accent-soft:rgba(76,141,255,.14);--accent-dim:rgba(76,141,255,.25);
  --str:#9ece6a;--num:#e0af68;--bool:#bb9af7;--nullc:#6b7686;
  --code-bg:#0a0d14;--code-fg:#c4d0e4;--gutter:#39435c;
  --tk-c:#5a6784;--tk-s:#9ece6a;--tk-k:#7aa2f7;--tk-n:#e0af68;--tk-t:#f7768e;
  --cat-objects:#4c8dff;--cat-features:#bb9af7;--cat-configs:#9ece6a;--cat-tests:#e0af68;--cat-other:#7d8aa3;
  --shadow:0 8px 30px rgba(0,0,0,.35);
}
html[data-theme="light"]{
  --bg:#f4f6fb;--bg2:#eef1f8;--panel:#ffffff;--panel2:#f7f9fd;--border:#e2e7f0;--border2:#d3dbe8;
  --text:#1a2233;--muted:#67748c;--key:#3f5f9e;--fold:#12203a;
  --accent:#2f6bff;--accent-soft:rgba(47,107,255,.10);--accent-dim:rgba(47,107,255,.18);
  --str:#3f8f3f;--num:#b06400;--bool:#8250df;--nullc:#94a0b4;
  --code-bg:#f7f9fd;--code-fg:#22304a;--gutter:#b6c1d4;
  --tk-c:#8a94a8;--tk-s:#3f8f3f;--tk-k:#2f6bff;--tk-n:#b06400;--tk-t:#c0325a;
  --cat-objects:#2f6bff;--cat-features:#8250df;--cat-configs:#3f8f3f;--cat-tests:#b06400;--cat-other:#67748c;
  --shadow:0 8px 26px rgba(30,45,90,.12);
}
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%;overflow:hidden}
body{background:var(--bg);color:var(--text);font-size:13px;line-height:1.5;
  font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
  -webkit-font-smoothing:antialiased;display:flex;flex-direction:column}
.mono{font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace}

/* ---- header ---- */
header{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:8px 16px;
  background:var(--panel);border-bottom:1px solid var(--border);flex:none;z-index:10}
.brand{display:flex;align-items:center;gap:10px;min-width:0}
.logo{width:28px;height:28px;border-radius:6px;flex:none;display:grid;place-items:center;
  font-weight:800;font-size:13px;color:#fff;
  background:linear-gradient(135deg,var(--accent),#8a5bff);box-shadow:0 2px 8px rgba(76,141,255,.35)}
.brand .name{font-size:14px;font-weight:700;letter-spacing:.2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.brand .sub{font-size:11px;color:var(--muted)}
.spacer{flex:1}
.search{position:relative;display:flex;align-items:center}
.search svg{position:absolute;left:9px;width:14px;height:14px;color:var(--muted);pointer-events:none}
.search input{background:var(--panel2);border:1px solid var(--border2);color:var(--text);border-radius:6px;
  padding:6px 10px 6px 28px;font-size:12.5px;min-width:200px;outline:none;transition:border-color .15s,box-shadow .15s}
.search input:focus{border-color:var(--accent);box-shadow:0 0 0 2px var(--accent-soft)}
.btn{background:var(--panel2);color:var(--text);border:1px solid var(--border2);border-radius:6px;
  padding:5px 10px;cursor:pointer;font-size:12px;font-weight:500;transition:background .12s,border-color .12s;
  display:inline-flex;align-items:center;gap:5px}
.btn:hover{background:var(--accent-soft);border-color:var(--accent)}
.btn svg{width:14px;height:14px;stroke:currentColor;fill:none;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}
.btn.primary{color:#fff;border-color:transparent;background:linear-gradient(135deg,var(--accent),#8a5bff);
  box-shadow:0 2px 8px rgba(76,141,255,.3);font-weight:600}
.btn.primary:hover{filter:brightness(1.08)}
.btn[disabled]{opacity:.5;cursor:progress}

/* ---- workbench layout ---- */
.workbench{display:flex;flex:1;overflow:hidden}
.pane-left{width:300px;min-width:220px;max-width:420px;border-right:1px solid var(--border);
  display:flex;flex-direction:column;background:var(--bg2);flex:none}
.pane-center{flex:1;overflow:auto;background:var(--bg)}
.pane-right{width:320px;min-width:240px;max-width:440px;border-left:1px solid var(--border);
  overflow:auto;background:var(--bg2);flex:none}

/* ---- tree pane ---- */
.tree-toolbar{display:flex;align-items:center;gap:4px;padding:6px 10px;border-bottom:1px solid var(--border);flex:none}
.tree-toolbar .tbtn{background:none;border:1px solid transparent;color:var(--muted);cursor:pointer;
  padding:3px 6px;border-radius:4px;display:flex;align-items:center;gap:3px;font-size:11px;transition:color .12s,background .12s}
.tree-toolbar .tbtn:hover{color:var(--text);background:var(--accent-soft)}
.tree-toolbar .tbtn svg{width:13px;height:13px;stroke:currentColor;fill:none;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}
.tree-scroll{flex:1;overflow:auto;padding:6px 0}

/* ---- tree nodes ---- */
ul.tree,ul.children{list-style:none;margin:0;padding:0}
ul.children{margin-left:8px;padding-left:10px;border-left:1px solid var(--border)}
li{margin:0}
.row{display:flex;align-items:baseline;gap:5px;padding:2px 10px 2px 6px;cursor:pointer;
  transition:background .1s;position:relative;font-size:12.5px}
.row:hover{background:var(--accent-soft)}
.row.selected{background:var(--accent-soft)}
.row.selected::before{content:'';position:absolute;left:0;top:0;bottom:0;width:2px;background:var(--accent)}
.caret{display:inline-block;width:14px;flex:none;color:var(--accent);user-select:none;text-align:center;
  font-size:10px;line-height:1;transition:transform .1s;transform:rotate(90deg)}
.caret.closed{transform:rotate(0deg)}
.bullet{display:inline-block;width:14px;flex:none;color:var(--muted);text-align:center;font-size:7px;transform:translateY(-1px)}
.key{color:var(--key);font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace;font-size:12px}
.row.folder>.key{color:var(--fold);font-weight:600}
.val{word-break:break-word;white-space:pre-wrap;font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace;font-size:12px}
.val.str{color:var(--str)}.val.num{color:var(--num)}.val.bool{color:var(--bool)}.val.null{color:var(--nullc);font-style:italic}
.count{color:var(--muted);font-size:11px;margin-left:2px}
ul.children.collapsed{display:none}
li.hide{display:none}

/* ---- section type borders ---- */
.sec-objects>.row{border-left:2px solid var(--cat-objects);padding-left:8px}
.sec-features>.row{border-left:2px solid var(--cat-features);padding-left:8px}
.sec-configs>.row{border-left:2px solid var(--cat-configs);padding-left:8px}
.sec-tests>.row{border-left:2px solid var(--cat-tests);padding-left:8px}
.sec-other>.row{border-left:2px solid var(--cat-other);padding-left:8px}

/* ---- section icons ---- */
.sec-icon{width:14px;height:14px;flex:none;stroke:currentColor;fill:none;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}
.sec-objects>.row>.sec-icon{color:var(--cat-objects)}
.sec-features>.row>.sec-icon{color:var(--cat-features)}
.sec-configs>.row>.sec-icon{color:var(--cat-configs)}
.sec-tests>.row>.sec-icon{color:var(--cat-tests)}
.sec-other>.row>.sec-icon{color:var(--cat-other)}

/* ---- badges ---- */
.badge{display:inline-block;font-size:9px;font-weight:700;letter-spacing:.4px;text-transform:uppercase;
  padding:1px 5px;border-radius:3px;margin-left:3px;vertical-align:middle;line-height:1.4}
.badge-object{color:var(--cat-objects);background:rgba(76,141,255,.12)}
.badge-list{color:var(--accent);background:var(--accent-soft)}
.badge-leaf{color:var(--muted);background:rgba(125,138,163,.1)}
.badge-code{color:var(--str);background:rgba(158,206,106,.12)}
html[data-theme="light"] .badge-object{background:rgba(47,107,255,.1)}
html[data-theme="light"] .badge-leaf{background:rgba(103,116,140,.1)}
html[data-theme="light"] .badge-code{background:rgba(63,143,63,.1)}

/* ---- center pane ---- */
.detail-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;
  height:100%;color:var(--muted);gap:12px;padding:40px}
.detail-empty svg{width:48px;height:48px;stroke:var(--border2);fill:none;stroke-width:1}
.detail-empty .de-title{font-size:14px;font-weight:600;color:var(--text)}
.detail-empty .de-sub{font-size:12px;text-align:center;max-width:320px}
.detail-wrap{padding:20px 24px}
.detail-bc{display:flex;align-items:center;gap:4px;flex-wrap:wrap;margin-bottom:16px;font-size:12px}
.detail-bc .bc-sep{color:var(--muted);font-size:10px}
.detail-bc .bc-item{color:var(--muted)}
.detail-bc .bc-item:last-child{color:var(--text);font-weight:600}
.detail-title{font-size:16px;font-weight:700;margin-bottom:4px;
  font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace}
.detail-meta{font-size:11px;color:var(--muted);margin-bottom:16px;display:flex;gap:12px;flex-wrap:wrap}
.detail-table{width:100%;border-collapse:collapse;font-size:12.5px}
.detail-table th{text-align:left;font-weight:600;color:var(--muted);font-size:11px;text-transform:uppercase;
  letter-spacing:.4px;padding:6px 10px;border-bottom:1px solid var(--border);background:var(--panel2)}
.detail-table td{padding:5px 10px;border-bottom:1px solid var(--border);vertical-align:top}
.detail-table tr:last-child td{border-bottom:none}
.detail-table tr:hover td{background:var(--accent-soft)}
.detail-table .dt-key{color:var(--key);font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace;
  font-size:12px;white-space:nowrap;width:1%;font-weight:500}
.detail-table .dt-val{word-break:break-word;white-space:pre-wrap;max-width:500px;
  font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace;font-size:12px}
.detail-table .dt-val.str{color:var(--str)}.detail-table .dt-val.num{color:var(--num)}
.detail-table .dt-val.bool{color:var(--bool)}.detail-table .dt-val.null{color:var(--nullc);font-style:italic}
.detail-table .dt-type{color:var(--muted);font-size:11px;white-space:nowrap}

/* ---- right inspector ---- */
.insp-empty{padding:20px;color:var(--muted);font-size:12px;text-align:center;margin-top:40px}
.insp-section{padding:12px 16px;border-bottom:1px solid var(--border)}
.insp-section:last-child{border-bottom:none}
.insp-label{font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.5px;color:var(--muted);margin-bottom:6px}
.insp-path{display:flex;align-items:center;gap:3px;flex-wrap:wrap;font-size:11.5px}
.insp-path .ip-sep{color:var(--muted);font-size:9px}
.insp-path .ip-item{color:var(--text)}
.insp-path .ip-item:last-child{color:var(--accent);font-weight:600}
.insp-key{font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace;font-size:13px;
  font-weight:600;color:var(--fold);word-break:break-all}
.insp-type{display:flex;gap:6px;flex-wrap:wrap}
.insp-code-info{font-size:11.5px;color:var(--muted);display:flex;gap:10px;flex-wrap:wrap}
.insp-copy{margin-top:8px}
.insp-copy .btn{font-size:11px;padding:4px 10px}
.insp-preview{margin-top:8px;border:1px solid var(--border);border-radius:4px;overflow:hidden;background:var(--code-bg)}
.insp-preview pre{margin:0;padding:8px 10px;overflow:auto;max-height:200px;color:var(--code-fg);
  font-family:ui-monospace,"Cascadia Code",Consolas,Menlo,monospace;font-size:11.5px;line-height:1.5;
  white-space:pre;tab-size:2}

/* ---- code viewer (in-tree) ---- */
.code-card{margin:4px 0 4px 4px;border:1px solid var(--border);border-radius:6px;overflow:hidden;
  background:var(--code-bg)}
.code-card.collapsed{display:none}
.code-head{display:flex;align-items:center;gap:8px;padding:5px 10px;background:var(--panel);
  border-bottom:1px solid var(--border)}
.code-head .fname{font-size:12px;font-weight:600;color:var(--fold);
  font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace}
.code-head .lang{font-size:9px;font-weight:700;text-transform:uppercase;letter-spacing:.4px;color:#fff;
  background:linear-gradient(135deg,var(--accent),#8a5bff);border-radius:4px;padding:1px 6px}
.code-head .lines{font-size:10.5px;color:var(--muted)}
.code-head .copy{margin-left:auto;font-size:11px;padding:3px 8px;border-radius:4px;border:1px solid var(--border2);
  background:var(--panel2);color:var(--text);cursor:pointer;transition:background .12s}
.code-head .copy:hover{background:var(--accent-soft);border-color:var(--accent)}
.code-head .copy.done{color:var(--str);border-color:var(--str)}
pre.code{margin:0;padding:8px 0;overflow:auto;max-height:400px;color:var(--code-fg);
  font-family:ui-monospace,"Cascadia Code",Consolas,Menlo,monospace;font-size:12px;line-height:1.55;
  counter-reset:ln;white-space:pre;tab-size:2}
pre.code .cl{display:block;padding:0 12px 0 0}
pre.code .cl::before{counter-increment:ln;content:counter(ln);display:inline-block;width:2.8em;margin-right:12px;
  padding-right:5px;color:var(--gutter);text-align:right;user-select:none;border-right:1px solid var(--border)}
pre.code .cl:hover{background:color-mix(in srgb,var(--accent) 6%,transparent)}
.tk-c{color:var(--tk-c);font-style:italic}.tk-s{color:var(--tk-s)}.tk-k{color:var(--tk-k)}
.tk-n{color:var(--tk-n)}.tk-t{color:var(--tk-t)}

/* ---- code viewer (center pane) ---- */
.center-code{margin-top:16px;border:1px solid var(--border);border-radius:6px;overflow:hidden;background:var(--code-bg)}
.center-code .code-head{border-radius:0}
.center-code pre.code{max-height:600px}

/* ---- search highlight ---- */
mark{background:var(--accent);color:#fff;border-radius:2px;padding:0 1px}

/* ---- error ---- */
.err{color:#f77;padding:20px 24px;font-size:13px}

/* ---- scrollbar ---- */
::-webkit-scrollbar{width:10px;height:10px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--border2);border-radius:6px;border:2px solid transparent;background-clip:padding-box}
::-webkit-scrollbar-thumb:hover{background:var(--muted);background-clip:padding-box}

/* ---- adv pill in header ---- */
.pill-adv{font-size:9px;font-weight:700;text-transform:uppercase;letter-spacing:.4px;color:#fff;
  background:linear-gradient(135deg,var(--accent),#8a5bff);border-radius:3px;padding:1px 5px;
  vertical-align:middle;margin-left:5px}
</style></head><body>
<header>
  <div class="brand">
    <div class="logo" id="logo">A</div>
    <div class="txt">
      <div class="name" id="appLabel">__APP__<span class="pill-adv">adv</span></div>
      <div class="sub" id="meta">definition tree · read-only · categorized</div>
    </div>
  </div>
  <span class="spacer"></span>
  <label class="search">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>
    <input type="search" id="search" placeholder="Filter name, value, key…" autocomplete="off">
  </label>
  <button class="btn" id="expandAll"><svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg>Expand</button>
  <button class="btn" id="collapseAll"><svg viewBox="0 0 24 24"><path d="M7 14l5-5 5 5"/></svg>Collapse</button>
  <button class="btn primary" id="exportAll" title="Download the whole definition as a ZIP"><svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>Export</button>
  <button class="btn" id="theme" title="Toggle theme"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg></button>
  <button class="btn" id="reload" title="Reload"><svg viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/></svg></button>
</header>
<div class="workbench">
  <div class="pane-left">
    <div class="tree-toolbar">
      <button class="tbtn" id="tExpandAll" title="Expand all"><svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></button>
      <button class="tbtn" id="tCollapseAll" title="Collapse all"><svg viewBox="0 0 24 24"><path d="M7 14l5-5 5 5"/></svg></button>
    </div>
    <div class="tree-scroll">
      <ul class="tree" id="tree"></ul>
    </div>
  </div>
  <div class="pane-center" id="centerPane">
    <div class="detail-empty" id="detailEmpty">
      <svg viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>
      <div class="de-title">Select a node</div>
      <div class="de-sub">Click a folder in the tree to view its contents here, or click any node to inspect it.</div>
    </div>
    <div class="detail-wrap" id="detailWrap" style="display:none"></div>
  </div>
  <div class="pane-right" id="rightPane">
    <div class="insp-empty" id="inspEmpty">Select a node to inspect</div>
    <div id="inspContent" style="display:none"></div>
  </div>
</div>
<script>
const treeEl = document.getElementById('tree');
const centerPane = document.getElementById('centerPane');
const detailEmpty = document.getElementById('detailEmpty');
const detailWrap = document.getElementById('detailWrap');
const rightPane = document.getElementById('rightPane');
const inspEmpty = document.getElementById('inspEmpty');
const inspContent = document.getElementById('inspContent');

const CODE_KEYS = new Set(['expr','condition','code','source','sql','query','template','script','body','content','value','purpose','description']);
const KEYWORDS = {
  java:'abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var record true false null'.split(' '),
  js:'async await break case catch class const continue debugger default delete do else export extends finally for from function if import in instanceof let new of return static super switch this throw try typeof var void while yield true false null undefined'.split(' '),
  css:[], html:[], txt:[]
};
KEYWORDS.ts=KEYWORDS.js; KEYWORDS.kt=KEYWORDS.java; KEYWORDS.htm=KEYWORDS.html;

function isPlainObject(v){ return v && typeof v === 'object' && !Array.isArray(v); }
function looksLikeCode(key, v){
  if (typeof v !== 'string') return false;
  if (v.indexOf('\n') >= 0) return true;
  if (v.length > 140) return true;
  return CODE_KEYS.has(key) && v.length > 40;
}
function langOf(label){
  const m = /\.([a-z0-9]+)\s*$/i.exec(label || '');
  const ext = m ? m[1].toLowerCase() : 'txt';
  return KEYWORDS[ext] ? ext : (ext === 'json' ? 'js' : 'txt');
}
const PREFERRED_LABEL_KEYS = [
  'name','label','title','alias','id','operation','event','route','path','capability','type','as','field',
  'concept','procedure','role','permission','action','column','table','key','code','op','pack'
];
const MAX_LABEL_VALUE_LEN = 60;
function shortScalar(v){
  if (typeof v === 'string' && v && v.indexOf('\n') < 0 && v.length <= MAX_LABEL_VALUE_LEN) return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
}
function itemLabel(v){
  if (!isPlainObject(v)) return null;
  for (const k of PREFERRED_LABEL_KEYS){
    const s = shortScalar(v[k]);
    if (s) return s;
  }
  for (const k of Object.keys(v)){
    const s = shortScalar(v[k]);
    if (s) return k + ': ' + s;
  }
  return null;
}
function scalarClass(v){
  if (v === null) return 'null';
  if (typeof v === 'number') return 'num';
  if (typeof v === 'boolean') return 'bool';
  return 'str';
}
function scalarText(v){ return v === null ? 'null' : (typeof v === 'string' ? v : String(v)); }
function el(tag, cls, txt){ const e=document.createElement(tag); if(cls)e.className=cls; if(txt!=null)e.textContent=txt; return e; }
function esc(s){ return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function highlight(line, lang){
  const e = esc(line);
  const parts = [];
  const comm = [];
  if (lang==='java'||lang==='js'||lang==='ts'||lang==='kt') comm.push('\\/\\/[^\n]*');
  if (lang==='java'||lang==='js'||lang==='ts'||lang==='kt'||lang==='css') comm.push('\\/\\*[\\s\\S]*?\\*\\/');
  if (lang==='html'||lang==='htm') comm.push('&lt;!--[\\s\\S]*?--&gt;');
  if (comm.length) parts.push('(?<c>' + comm.join('|') + ')');
  parts.push('(?<s>"(?:[^"\\\\]|\\\\.)*"|\'(?:[^\'\\\\]|\\\\.)*\'|`(?:[^`\\\\]|\\\\.)*`)');
  const kw = (KEYWORDS[lang] || []).join('|');
  if (kw) parts.push('(?<k>\\b(?:' + kw + ')\\b)');
  parts.push('(?<n>\\b\\d[\\d._]*)');
  if (lang==='html'||lang==='htm') parts.push('(?<t>&lt;\\/?[a-zA-Z][\\w-]*)');
  try {
    const re = new RegExp(parts.join('|'), 'g');
    return e.replace(re, (...a) => { const g = a[a.length - 1];
      if (g.c) return '<span class="tk-c">' + g.c + '</span>';
      if (g.s) return '<span class="tk-s">' + g.s + '</span>';
      if (g.k) return '<span class="tk-k">' + g.k + '</span>';
      if (g.n) return '<span class="tk-n">' + g.n + '</span>';
      if (g.t) return '<span class="tk-t">' + g.t + '</span>';
      return a[0]; });
  } catch(_){ return e; }
}

/* ---- section icon SVGs (inline, stroke-based) ---- */
const SEC_IC = {
  Objects:'<svg class="sec-icon" viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>',
  Features:'<svg class="sec-icon" viewBox="0 0 24 24"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>',
  Configs:'<svg class="sec-icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 01-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.6a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z"/></svg>',
  Tests:'<svg class="sec-icon" viewBox="0 0 24 24"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11"/></svg>',
  Other:'<svg class="sec-icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>'
};
function secClass(name){
  const n = (name||'').toLowerCase();
  if (n==='objects') return 'sec-objects';
  if (n==='features') return 'sec-features';
  if (n==='configs') return 'sec-configs';
  if (n==='tests') return 'sec-tests';
  return 'sec-other';
}
function secIcon(name){
  const n = (name||'').toLowerCase();
  if (n==='objects') return SEC_IC.Objects;
  if (n==='features') return SEC_IC.Features;
  if (n==='configs') return SEC_IC.Configs;
  if (n==='tests') return SEC_IC.Tests;
  return SEC_IC.Other;
}

/* ---- selection / pane management ---- */
let selectedRow = null;
function clearSelection(){ if (selectedRow){ selectedRow.classList.remove('selected'); selectedRow=null; } }
function setSelection(row){ clearSelection(); row.classList.add('selected'); selectedRow=row; }

function getPath(li){
  const parts=[];
  let cur=li;
  while(cur && cur!==treeEl){
    if(cur._pathKey) parts.unshift(cur._pathKey);
    cur=cur.parentElement?cur.parentElement.parentElement:null;
  }
  return parts;
}

function showDetail(li){
  const path=getPath(li);
  const key=li._pathKey||'';
  const value=li._nodeValue;
  const nType=li._nodeType;

  detailEmpty.style.display='none';
  detailWrap.style.display='';
  detailWrap.innerHTML='';

  const bc=el('div','detail-bc');
  path.forEach((p,i)=>{
    if(i>0) bc.appendChild(el('span','bc-sep','›'));
    bc.appendChild(el('span','bc-item',p));
  });
  detailWrap.appendChild(bc);

  const title=el('div','detail-title mono',key);
  detailWrap.appendChild(title);

  const meta=el('div','detail-meta');
  const badge=el('span','badge badge-'+nType,nType);
  meta.appendChild(badge);
  if(nType==='object'||nType==='list'){
    const cnt=value?(Array.isArray(value)?value.length:Object.keys(value).length):0;
    meta.appendChild(el('span','',cnt+' '+(cnt===1?'item':'items')));
  }
  detailWrap.appendChild(meta);

  if(nType==='code'){
    const content=li._codeContent||'';
    const lines=content.split('\n');
    const lang=langOf(key);
    const cc=el('div','center-code');
    const head=el('div','code-head');
    head.appendChild(el('span','fname mono',key));
    head.appendChild(el('span','lang',lang));
    head.appendChild(el('span','lines',lines.length+' lines'));
    const copy=el('button','copy','Copy');
    copy.onclick=()=>{navigator.clipboard.writeText(content).then(()=>{copy.textContent='Copied ✓';copy.classList.add('done');setTimeout(()=>{copy.textContent='Copy';copy.classList.remove('done');},1400);});};
    head.appendChild(copy);
    cc.appendChild(head);
    const pre=el('pre','code mono');
    const frag=document.createDocumentFragment();
    for(const ln of lines){const d=el('div','cl');d.innerHTML=highlight(ln===' '?' ':ln,lang);frag.appendChild(d);}
    pre.appendChild(frag);
    cc.appendChild(pre);
    detailWrap.appendChild(cc);
  } else if((nType==='object'||nType==='list') && value){
    const entries=Array.isArray(value)?value.map((v,i)=>[i,v]):Object.entries(value);
    if(entries.length>0){
      const tbl=el('table','detail-table');
      const thead=el('thead');
      const thr=el('tr');
      thr.appendChild(el('th','','Key'));
      thr.appendChild(el('th','','Type'));
      thr.appendChild(el('th','','Value'));
      thead.appendChild(thr);
      tbl.appendChild(thead);
      const tbody=el('tbody');
      for(const [k,v] of entries){
        const tr=el('tr');
        const tdKey=el('td','dt-key',String(k));
        const tdType=el('td','dt-type');
        const tdVal=el('td');
        if(v===null){
          tdType.textContent='null';
          tdVal.className='dt-val null';tdVal.textContent='null';
        } else if(Array.isArray(v)){
          tdType.textContent='list['+v.length+']';
          tdVal.className='dt-val';tdVal.textContent='{…}';tdVal.style.color='var(--muted)';
        } else if(isPlainObject(v)){
          tdType.textContent='object';
          tdVal.className='dt-val';tdVal.textContent='{…}';tdVal.style.color='var(--muted)';
        } else if(typeof v==='string' && looksLikeCode(String(k),v)){
          tdType.textContent='code';
          const lang2=langOf(String(k));
          tdVal.className='dt-val str';
          const preview=v.split('\n').slice(0,3).join('\n');
          tdVal.textContent=preview+(v.split('\n').length>3?'\n…':'');
        } else {
          tdType.textContent=typeof v;
          tdVal.className='dt-val '+scalarClass(v);
          tdVal.textContent=scalarText(v);
        }
        tr.append(tdKey,tdType,tdVal);
        tbody.appendChild(tr);
      }
      tbl.appendChild(tbody);
      detailWrap.appendChild(tbl);
    }
  } else if(nType==='leaf'){
    const valDiv=el('div','');
    valDiv.style.cssText='margin-top:12px;padding:12px;background:var(--panel2);border:1px solid var(--border);border-radius:6px;';
    const valPre=el('div','mono');
    valPre.style.cssText='white-space:pre-wrap;word-break:break-word;font-size:12.5px;color:var(--'+scalarClass(value)+')';
    valPre.textContent=scalarText(value);
    valDiv.appendChild(valPre);
    detailWrap.appendChild(valDiv);
  }
}

function showInspector(li){
  const path=getPath(li);
  const key=li._pathKey||'';
  const nType=li._nodeType;

  inspEmpty.style.display='none';
  inspContent.style.display='';
  inspContent.innerHTML='';

  const sPath=el('div','insp-section');
  sPath.appendChild(el('div','insp-label','Path'));
  const pathDiv=el('div','insp-path');
  path.forEach((p,i)=>{
    if(i>0) pathDiv.appendChild(el('span','ip-sep','›'));
    const sp=el('span','ip-item',p);
    if(i===path.length-1) sp.style.color='var(--accent)';
    pathDiv.appendChild(sp);
  });
  if(path.length===0) pathDiv.textContent='(root)';
  sPath.appendChild(pathDiv);
  inspContent.appendChild(sPath);

  const sKey=el('div','insp-section');
  sKey.appendChild(el('div','insp-label','Key'));
  sKey.appendChild(el('div','insp-key',key));
  inspContent.appendChild(sKey);

  const sType=el('div','insp-section');
  sType.appendChild(el('div','insp-label','Type'));
  const typeDiv=el('div','insp-type');
  typeDiv.appendChild(el('span','badge badge-'+nType,nType));
  sType.appendChild(typeDiv);
  inspContent.appendChild(sType);

  if(nType==='code'){
    const content=li._codeContent||'';
    const lines=content.split('\n');
    const lang=langOf(key);
    const sCode=el('div','insp-section');
    sCode.appendChild(el('div','insp-label','Code'));
    const info=el('div','insp-code-info');
    info.appendChild(el('span','',''+lang));
    info.appendChild(el('span','',lines.length+' lines'));
    sCode.appendChild(info);
    const copyDiv=el('div','insp-copy');
    const copyBtn=el('button','btn','Copy code');
    copyBtn.onclick=()=>{navigator.clipboard.writeText(content).then(()=>{copyBtn.textContent='Copied ✓';setTimeout(()=>{copyBtn.textContent='Copy code';},1400);});};
    copyDiv.appendChild(copyBtn);
    sCode.appendChild(copyDiv);
    const preview=el('div','insp-preview');
    const pre=el('pre','mono');
    const previewLines=lines.slice(0,12);
    pre.textContent=previewLines.join('\n')+(lines.length>12?'\n…':'');
    preview.appendChild(pre);
    sCode.appendChild(preview);
    inspContent.appendChild(sCode);
  }
}

function selectNode(li, row){
  setSelection(row);
  showDetail(li);
  showInspector(li);
}

function makeCodeNode(labelText, content){
  const li = el('li');
  const lines = content.split('\n');
  const lang = langOf(labelText);
  const fname = labelText.split(' / ').pop();

  const row = el('div','row folder');
  const caret = el('span','caret closed','▸');
  const key = el('span','key', labelText);
  const badge = el('span','badge badge-code', lang);
  row.append(caret, key, badge);

  const card = el('div','code-card collapsed');
  const head = el('div','code-head');
  head.append(el('span','fname mono', fname), el('span','lang', lang), el('span','lines', lines.length + ' lines'));
  const copy = el('button','copy','Copy');
  copy.onclick = (ev) => { ev.stopPropagation();
    navigator.clipboard.writeText(content).then(()=>{ copy.textContent='Copied ✓'; copy.classList.add('done');
      setTimeout(()=>{ copy.textContent='Copy'; copy.classList.remove('done'); },1400); }); };
  head.appendChild(copy);
  const pre = el('pre','code mono');
  card.append(head, pre);

  let built = false;
  const build = () => { if (built) return; built = true;
    const frag = document.createDocumentFragment();
    for (const ln of lines){ const d=el('div','cl'); d.innerHTML = highlight(ln === '' ? ' ' : ln, lang); frag.appendChild(d); }
    pre.appendChild(frag);
  };
  row.onclick = (ev) => { ev.stopPropagation();
    const collapsed = card.classList.toggle('collapsed');
    caret.classList.toggle('closed', collapsed); caret.textContent = collapsed ? '▸' : '▾';
    if (!collapsed) build();
    li._codeContent = content;
    selectNode(li, row);
  };
  li._expand = () => { if (card.classList.contains('collapsed')){ card.classList.remove('collapsed');
    caret.classList.remove('closed'); caret.textContent='▾'; build(); } };
  li.append(row, card);
  li._searchText = (labelText + ' ' + content).toLowerCase();
  li._pathKey = labelText;
  li._nodeType = 'code';
  li._codeContent = content;
  return li;
}

function nodeFor(keyText, value, depth, parentPath){
  const curPath = (parentPath||[]).concat([keyText]);
  if (typeof value === 'string' && looksLikeCode(keyText, value)) return makeCodeNode(keyText, value);

  if (value === null || typeof value !== 'object'){
    const li = el('li');
    const row = el('div','row leaf');
    row.append(el('span','bullet','●'), el('span','key', keyText + ':'));
    row.appendChild(el('span','val ' + scalarClass(value), scalarText(value)));
    li.appendChild(row);
    li._searchText = (keyText + ' ' + scalarText(value)).toLowerCase();
    li._pathKey = keyText;
    li._nodeType = 'leaf';
    li._nodeValue = value;
    row.onclick = (ev) => { ev.stopPropagation(); selectNode(li, row); };
    return li;
  }

  const isArr = Array.isArray(value);
  const singular = keyText.replace(/s$/, '') || keyText;

  const li = el('li');
  const row = el('div','row folder');
  const caret = el('span','caret');
  const key = el('span','key', keyText);
  const count = el('span','count', isArr ? value.length : Object.keys(value).length);
  const badge = el('span','badge '+(isArr?'badge-list':'badge-object'), isArr?'list':'object');
  row.append(caret, key, count, badge);

  const childUl = el('ul','children');
  const open = depth < 1;
  caret.textContent = open ? '▾' : '▸';
  caret.classList.toggle('closed', !open);
  if (!open) childUl.classList.add('collapsed');

  if (isArr) {
    value.forEach((v, i) => {
      if (isPlainObject(v) || Array.isArray(v)) {
        const label = itemLabel(v) || (singular + ' #' + (i + 1));
        const child = nodeFor(label, v, depth + 1, curPath);
        childUl.appendChild(child);
      } else {
        childUl.appendChild(scalarListItem(v, curPath));
      }
    });
  } else {
    Object.keys(value).forEach(k => childUl.appendChild(nodeFor(k, value[k], depth + 1, curPath)));
  }

  row.onclick = (ev) => { ev.stopPropagation();
    const c = childUl.classList.toggle('collapsed');
    caret.classList.toggle('closed', c); caret.textContent = c ? '▸' : '▾';
    selectNode(li, row);
  };
  li._expand = () => { childUl.classList.remove('collapsed'); caret.classList.remove('closed'); caret.textContent='▾'; };
  li.append(row, childUl);
  li._searchText = keyText.toLowerCase();
  li._pathKey = keyText;
  li._nodeValue = value;
  li._nodeType = isArr ? 'list' : 'object';
  return li;
}

function scalarListItem(v, parentPath){
  const li = el('li');
  const row = el('div','row leaf');
  row.append(el('span','bullet','●'), el('span','val ' + scalarClass(v), scalarText(v)));
  li.appendChild(row);
  li._searchText = scalarText(v).toLowerCase();
  li._pathKey = scalarText(v);
  li._nodeType = 'leaf';
  li._nodeValue = v;
  row.onclick = (ev) => { ev.stopPropagation(); selectNode(li, row); };
  return li;
}

function setAll(collapsed){
  document.querySelectorAll('ul.children').forEach(ul => ul.classList.toggle('collapsed', collapsed));
  if (collapsed) document.querySelectorAll('.code-card').forEach(c => c.classList.add('collapsed'));
  document.querySelectorAll('.row.folder .caret').forEach(c => { c.classList.toggle('closed', collapsed); c.textContent = collapsed ? '▸' : '▾'; });
}
document.getElementById('expandAll').onclick  = () => setAll(false);
document.getElementById('collapseAll').onclick = () => setAll(true);
document.getElementById('tExpandAll').onclick  = () => setAll(false);
document.getElementById('tCollapseAll').onclick = () => setAll(true);
document.getElementById('reload').onclick = () => window.location.reload();

/* ---- Export all: fetch the raw project files and build a ZIP in-browser (no libraries) ---- */
const CRC_T = (() => { const t = new Uint32Array(256);
  for (let n=0;n<256;n++){ let c=n; for (let k=0;k<8;k++) c = c&1 ? 0xEDB88320 ^ (c>>>1) : c>>>1; t[n]=c>>>0; } return t; })();
function crc32(u8){ let c=0xFFFFFFFF; for (let i=0;i<u8.length;i++) c = CRC_T[(c ^ u8[i]) & 0xFF] ^ (c>>>8); return (c ^ 0xFFFFFFFF) >>> 0; }
function zipStore(entries){
  const enc = new TextEncoder(); const chunks = []; let off = 0; const central = [];
  const u16 = v => { const b=new Uint8Array(2); b[0]=v&255; b[1]=(v>>8)&255; return b; };
  const u32 = v => { const b=new Uint8Array(4); b[0]=v&255; b[1]=(v>>8)&255; b[2]=(v>>16)&255; b[3]=(v>>>24)&255; return b; };
  const push = u8 => { chunks.push(u8); off += u8.length; };
  for (const e of entries){
    const name = enc.encode(e.path), data = e.data, crc = crc32(data), lho = off;
    push(u32(0x04034b50)); push(u16(20)); push(u16(0x0800)); push(u16(0)); push(u16(0)); push(u16(0x21));
    push(u32(crc)); push(u32(data.length)); push(u32(data.length)); push(u16(name.length)); push(u16(0));
    push(name); push(data);
    central.push({ name, crc, size: data.length, lho });
  }
  const cdStart = off;
  for (const c of central){
    push(u32(0x02014b50)); push(u16(20)); push(u16(20)); push(u16(0x0800)); push(u16(0)); push(u16(0)); push(u16(0x21));
    push(u32(c.crc)); push(u32(c.size)); push(u32(c.size)); push(u16(c.name.length)); push(u16(0)); push(u16(0));
    push(u16(0)); push(u16(0)); push(u32(0)); push(u32(c.lho)); push(c.name);
  }
  const cdSize = off - cdStart;
  push(u32(0x06054b50)); push(u16(0)); push(u16(0)); push(u16(central.length)); push(u16(central.length));
  push(u32(cdSize)); push(u32(cdStart)); push(u16(0));
  return new Blob(chunks, { type: 'application/zip' });
}
const exportBtn = document.getElementById('exportAll');
exportBtn.onclick = async () => {
  const label = exportBtn.textContent; exportBtn.disabled = true; exportBtn.textContent = 'Preparing…';
  try {
    const r = await fetch('app-files.json', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const doc = await r.json();
    const enc = new TextEncoder();
    const root = (doc.root || doc.appId || 'app').replace(/[\/\\]+$/,'');
    const entries = (doc.files || []).map(f => ({ path: root + '/' + f.path, data: enc.encode(f.content ?? '') }));
    if (!entries.length) throw new Error('no files to export');
    const blob = zipStore(entries);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = root + '-definition.zip';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
    exportBtn.textContent = '✓ ' + entries.length + ' files';
    setTimeout(() => { exportBtn.textContent = label; }, 1800);
  } catch (e) {
    exportBtn.textContent = 'Export failed';
    console.error('Export all failed:', e);
    setTimeout(() => { exportBtn.textContent = label; }, 2200);
  } finally { exportBtn.disabled = false; }
};

/* ---- theme toggle (persisted) ---- */
const root = document.documentElement;
try { const saved = localStorage.getItem('npdev-tree-theme'); if (saved) root.dataset.theme = saved; } catch(_){}
document.getElementById('theme').onclick = () => {
  const next = root.dataset.theme === 'light' ? 'dark' : 'light';
  root.dataset.theme = next; try { localStorage.setItem('npdev-tree-theme', next); } catch(_){}
};

/* ---- filter ---- */
const searchEl = document.getElementById('search');
function matchLi(li, q){
  const selfMatch = (li._searchText || '').includes(q);
  let childMatch = false;
  li.querySelectorAll(':scope > ul.children > li').forEach(k => { if (matchLi(k, q)) childMatch = true; });
  const visible = !q || selfMatch || childMatch;
  li.classList.toggle('hide', !visible);
  if (q && childMatch && li._expand) li._expand();
  return visible;
}
let t;
searchEl.oninput = () => {
  clearTimeout(t);
  t = setTimeout(() => {
    const q = searchEl.value.trim().toLowerCase();
    treeEl.querySelectorAll(':scope > li').forEach(li => matchLi(li, q));
  }, 160);
};

/* ---- load data ---- */
fetch('app-tree-v2.json', { cache: 'no-store' })
  .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
  .then(data => {
    const app = data.appId || 'App';
    document.getElementById('appLabel').innerHTML = esc(app) + '<span class="pill-adv">adv</span>';
    document.getElementById('logo').textContent = (app[0] || 'A').toUpperCase();
    document.getElementById('meta').textContent =
      'definition tree · read-only · categorized · generated ' + (data.generatedAt || '').replace('T',' ').slice(0,19);
    const sections = data.sections || {};
    Object.keys(sections).forEach(k => {
      const secLi = nodeFor(k, sections[k], 0, []);
      secLi.classList.add(secClass(k));
      const row = secLi.querySelector(':scope > .row');
      if (row) {
        const iconSpan = document.createElement('span');
        iconSpan.innerHTML = secIcon(k);
        const iconEl = iconSpan.firstElementChild;
        if (iconEl) row.insertBefore(iconEl, row.firstChild);
      }
      treeEl.appendChild(secLi);
    });
  })
  .catch(e => {
    const p = el('p','err','Could not load app-tree-v2.json: ' + e.message +
      ' — run New-AppTreePageV2.ps1 first, and open this page over http:// (not file://).');
    treeEl.replaceWith(p);
  });
</script>
</body></html>
'@
$html = $tpl.Replace('__APP__', $AppId)
Set-Content -LiteralPath (Join-Path $StaticDir 'app-tree-v2.html') -Value $html -Encoding UTF8

Write-Host "Emitted advanced tree page: $(Join-Path $StaticDir 'app-tree-v2.html') (+ app-tree-v2.json)"
