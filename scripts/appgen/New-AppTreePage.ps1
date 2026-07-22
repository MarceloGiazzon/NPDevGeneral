<#
.SYNOPSIS
  Emit a read-only, COMPLETE tree-view page (app-tree.html + app-tree.json) for an app.

.DESCRIPTION
  Standalone, run-on-demand companion to New-AppInfoPage.ps1. Reads an app's own
  definition/ folder (NOT the generator's staged/compiled output) and writes a JSON
  document plus an HTML page that renders the ENTIRE app definition as an expand/collapse
  tree -- read-only, no editing.

  Design premise: ALL of an app's definition should be visible in the tree. Rather than
  hand-pick a handful of sections, this script:
    * fully resolves every "$ref" in model.json (packs, concepts, flows, panels, ...) so
      the complete, inlined model is emitted;
    * loads every sibling definition file (config, pages, menu, db, trusted sources, seeds);
    * attaches the source code of code-bearing objects -- custom-capability Java plugins and
      app widget scripts -- so they can be read in an in-page code viewer.

  The HTML renders this generically: objects become branches, arrays become branches whose
  items are labelled by their name/label/title/id, scalars become "key: value" leaves, and
  any multi-line / code-like string (Java source, CEL expressions, SQL, templates, ...)
  renders in a collapsible code viewer. A search box filters the tree. Re-run any time the
  definition changes and reload app-tree.html -- nothing else needs rebuilding.

  Must write into the App module's own src/main/resources/static (NOT npdev-generated/,
  which the runtime strict-execution validator hashes). See appgen-finalapp-recipe memory.

.EXAMPLE
  & 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\New-AppTreePage.ps1' `
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

$Definition = Join-Path $AppFolder 'definition'
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
# Custom-capability Java plugins, app widget scripts, and the bespoke web/ UI pages.
# Each file renders in the in-page code viewer; the key keeps the file extension so the
# viewer can tint syntax and show a language pill.
$capSources    = [ordered]@{}
$widgetSources = [ordered]@{}
$webSources    = [ordered]@{}

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

# ---- load sibling definition files as their own sections ---------------------
function Load-OptionalJson { param([string]$Name)
  $p = Join-Path $Definition $Name
  if (Test-Path -LiteralPath $p) { return (Resolve-Refs (Read-JsonFile $p) $Definition) }
  return $null
}

$sections = [ordered]@{}
if ($Config) { $sections['Config'] = $Config }
$sections['Model'] = $resolvedModel
$pages = Load-OptionalJson 'pages.json'
if ($null -ne $pages) { $sections['Pages'] = $pages }
$menu = Load-OptionalJson 'menu.json'
if ($null -ne $menu) { $sections['Menu'] = $menu }
$db = Load-OptionalJson 'db.definition.json'
if ($null -ne $db) { $sections['Database'] = $db }
$ts = Load-OptionalJson 'trusted-source-manifest.json'
if ($null -ne $ts) { $sections['TrustedSources'] = $ts }

$seedsDir = Join-Path $Definition 'seeds'
if (Test-Path -LiteralPath $seedsDir) {
  $seedObj = [ordered]@{}
  Get-ChildItem -LiteralPath $seedsDir -File -Include *.json | ForEach-Object {
    $seedObj["$($_.Name)"] = (Resolve-Refs (Read-JsonFile $_.FullName) $seedsDir)
  }
  if ($seedObj.Count -gt 0) { $sections['Seeds'] = [pscustomobject]$seedObj }
}

$srcGroups = [ordered]@{}
if ($capSources.Count    -gt 0) { $srcGroups['Capabilities'] = [pscustomobject]$capSources }
if ($widgetSources.Count -gt 0) { $srcGroups['Widgets']      = [pscustomobject]$widgetSources }
if ($webSources.Count    -gt 0) { $srcGroups['Web Pages']    = [pscustomobject]$webSources }
if ($srcGroups.Count     -gt 0) { $sections['SourceCode']    = [pscustomobject]$srcGroups }

$doc = [ordered]@{
  schemaVersion = 'npdev-app-tree.v2'
  appId         = $AppId
  generatedAt   = (Get-Date).ToString('o')
  sections      = [pscustomobject]$sections
}

New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null
$docJson = $doc | ConvertTo-Json -Depth 100
Set-Content -LiteralPath (Join-Path $StaticDir 'app-tree.json') -Value $docJson -Encoding UTF8

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
<title>__APP__ - definition tree</title>
<style>
 :root{
   --bg:#0b0e14;--bg2:#0f1420;--panel:#111725;--panel-2:#0c1119;--border:#1e2637;--border-2:#28324a;
   --text:#e6edf7;--muted:#7d8aa3;--fold:#e7eefc;--key:#8ea9d6;--accent:#4c8dff;--accent-soft:rgba(76,141,255,.14);
   --str:#9ece6a;--num:#e0af68;--bool:#bb9af7;--null:#6b7686;
   --code-bg:#0a0d14;--code-fg:#c4d0e4;--gutter:#39435c;
   --tk-c:#5a6784;--tk-s:#9ece6a;--tk-k:#7aa2f7;--tk-n:#e0af68;--tk-t:#f7768e;
   --shadow:0 8px 30px rgba(0,0,0,.35);
 }
 html[data-theme="light"]{
   --bg:#f4f6fb;--bg2:#eef1f8;--panel:#ffffff;--panel-2:#f7f9fd;--border:#e2e7f0;--border-2:#d3dbe8;
   --text:#1a2233;--muted:#67748c;--fold:#12203a;--key:#3f5f9e;--accent:#2f6bff;--accent-soft:rgba(47,107,255,.10);
   --str:#3f8f3f;--num:#b06400;--bool:#8250df;--null:#94a0b4;
   --code-bg:#f7f9fd;--code-fg:#22304a;--gutter:#b6c1d4;
   --tk-c:#8a94a8;--tk-s:#3f8f3f;--tk-k:#2f6bff;--tk-n:#b06400;--tk-t:#c0325a;
   --shadow:0 8px 26px rgba(30,45,90,.12);
 }
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:var(--text);font-size:13.5px;line-height:1.55;
   font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
   -webkit-font-smoothing:antialiased}
 .mono{font-family:ui-monospace,"Cascadia Code",Consolas,"SF Mono",Menlo,monospace}
 header{position:sticky;top:0;z-index:10;display:flex;gap:12px;align-items:center;flex-wrap:wrap;
   padding:12px 20px;background:color-mix(in srgb,var(--panel) 82%,transparent);
   backdrop-filter:blur(10px);border-bottom:1px solid var(--border)}
 .brand{display:flex;align-items:center;gap:11px;min-width:0}
 .logo{width:30px;height:30px;border-radius:9px;flex:none;display:grid;place-items:center;font-weight:800;
   color:#fff;background:linear-gradient(135deg,var(--accent),#8a5bff);box-shadow:0 4px 14px rgba(76,141,255,.4)}
 .brand .txt{min-width:0}
 .brand .name{font-size:15px;font-weight:700;letter-spacing:.2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
 .brand .sub{font-size:11.5px;color:var(--muted)}
 .spacer{flex:1}
 .search{position:relative;display:flex;align-items:center}
 .search svg{position:absolute;left:10px;width:15px;height:15px;color:var(--muted);pointer-events:none}
 .search input{background:var(--panel-2);border:1px solid var(--border-2);color:var(--text);border-radius:9px;
   padding:7px 11px 7px 31px;font-size:13px;min-width:230px;outline:none;transition:border-color .15s,box-shadow .15s}
 .search input:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--accent-soft)}
 .btn{background:var(--panel-2);color:var(--text);border:1px solid var(--border-2);border-radius:9px;
   padding:7px 12px;cursor:pointer;font-size:12.5px;font-weight:500;transition:background .15s,border-color .15s}
 .btn:hover{background:var(--accent-soft);border-color:var(--accent)}
 .btn.icon{padding:7px 9px;line-height:1}
 .btn.primary{color:#fff;border-color:transparent;background:linear-gradient(135deg,var(--accent),#8a5bff);
   box-shadow:0 4px 14px rgba(76,141,255,.35);font-weight:600}
 .btn.primary:hover{filter:brightness(1.08)}
 .btn[disabled]{opacity:.6;cursor:progress}
 main{padding:16px 20px 80px;max-width:1200px}
 .err{color:#f77;padding:16px 20px}
 ul.tree,ul.children{list-style:none;margin:0;padding:0}
 ul.children{margin-left:9px;padding-left:11px;border-left:1px solid var(--border)}
 ul.tree>li>.row{margin-top:6px}
 li{margin:1px 0}
 .row{display:flex;align-items:baseline;gap:7px;padding:3px 8px;border-radius:8px;transition:background .12s}
 .row.folder{cursor:pointer}
 .row.folder:hover{background:var(--accent-soft)}
 .row.leaf:hover{background:color-mix(in srgb,var(--muted) 9%,transparent)}
 .caret{display:inline-block;width:.85em;flex:none;color:var(--accent);user-select:none;
   transition:transform .12s;transform:rotate(90deg)}
 .caret.closed{transform:rotate(0deg)}
 .bullet{display:inline-block;width:.85em;flex:none;color:var(--muted);text-align:center;font-size:9px;transform:translateY(-1px)}
 .key{color:var(--key)}
 .row.folder>.key{color:var(--fold);font-weight:650}
 .val{word-break:break-word;white-space:pre-wrap}
 .val.str{color:var(--str)}.val.num{color:var(--num)}.val.bool{color:var(--bool)}.val.null{color:var(--null);font-style:italic}
 .count{color:var(--muted);font-size:11.5px}
 .pill{font-size:10px;font-weight:600;letter-spacing:.3px;text-transform:uppercase;color:var(--muted);
   border:1px solid var(--border-2);border-radius:999px;padding:1px 8px;margin-left:2px}
 .pill.list{color:var(--accent);border-color:color-mix(in srgb,var(--accent) 45%,transparent)}
 ul.children.collapsed{display:none}
 /* code viewer */
 .code-card{margin:6px 0 8px 6px;border:1px solid var(--border);border-radius:11px;overflow:hidden;
   background:var(--code-bg);box-shadow:var(--shadow)}
 .code-card.collapsed{display:none}
 .code-head{display:flex;align-items:center;gap:9px;padding:7px 11px;background:var(--panel);
   border-bottom:1px solid var(--border)}
 .code-head .fname{font-size:12.5px;font-weight:600;color:var(--fold)}
 .code-head .lang{font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.4px;color:#fff;
   background:linear-gradient(135deg,var(--accent),#8a5bff);border-radius:6px;padding:1px 7px}
 .code-head .lines{font-size:11px;color:var(--muted)}
 .code-head .copy{margin-left:auto;font-size:11.5px;padding:4px 10px;border-radius:7px;border:1px solid var(--border-2);
   background:var(--panel-2);color:var(--text);cursor:pointer;transition:background .12s}
 .code-head .copy:hover{background:var(--accent-soft);border-color:var(--accent)}
 .code-head .copy.done{color:var(--str);border-color:var(--str)}
 pre.code{margin:0;padding:12px 0;overflow:auto;max-height:520px;color:var(--code-fg);
   font-family:ui-monospace,"Cascadia Code",Consolas,Menlo,monospace;font-size:12.5px;line-height:1.6;
   counter-reset:ln;white-space:pre;tab-size:2}
 pre.code .cl{display:block;padding:0 14px 0 0}
 pre.code .cl::before{counter-increment:ln;content:counter(ln);display:inline-block;width:3em;margin-right:14px;
   padding-right:6px;color:var(--gutter);text-align:right;user-select:none;
   border-right:1px solid var(--border)}
 pre.code .cl:hover{background:color-mix(in srgb,var(--accent) 8%,transparent)}
 .tk-c{color:var(--tk-c);font-style:italic}.tk-s{color:var(--tk-s)}.tk-k{color:var(--tk-k)}
 .tk-n{color:var(--tk-n)}.tk-t{color:var(--tk-t)}
 mark{background:var(--accent);color:#fff;border-radius:3px;padding:0 1px}
 li.hide{display:none}
 ::-webkit-scrollbar{width:11px;height:11px}
 ::-webkit-scrollbar-thumb{background:var(--border-2);border-radius:8px;border:3px solid transparent;background-clip:padding-box}
 ::-webkit-scrollbar-thumb:hover{background:var(--muted);background-clip:padding-box}
</style></head><body>
<header>
  <div class="brand">
    <div class="logo" id="logo">A</div>
    <div class="txt">
      <div class="name" id="appLabel">__APP__</div>
      <div class="sub" id="meta">definition tree · read-only</div>
    </div>
  </div>
  <span class="spacer"></span>
  <label class="search">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>
    <input type="search" id="search" placeholder="Filter name, value, key…" autocomplete="off">
  </label>
  <button class="btn" id="expandAll">Expand all</button>
  <button class="btn" id="collapseAll">Collapse all</button>
  <button class="btn primary" id="exportAll" title="Download the whole definition as a ZIP">⬇ Export all</button>
  <button class="btn icon" id="theme" title="Toggle theme">◐</button>
  <button class="btn icon" id="reload" title="Reload">⟳</button>
</header>
<main><ul class="tree" id="tree"></ul></main>
<script>
const treeEl = document.getElementById('tree');
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
// Prefer a meaningful label for an array item.
function itemLabel(v){
  if (!isPlainObject(v)) return null;
  for (const k of ['name','label','title','id','operation','event','route','path','capability','type','as','field']){
    if (typeof v[k] === 'string' && v[k]) return v[k];
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

// Line-scoped syntax tinting over already-escaped text (safe: no cross-line spans).
// Named groups avoid empty-alternative pitfalls; comments are language-aware.
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

function makeCodeNode(labelText, content){
  const li = el('li');
  const lines = content.split('\n');
  const lang = langOf(labelText);
  const fname = labelText.split(' / ').pop();

  const row = el('div','row folder');
  const caret = el('span','caret closed','▸');
  const key = el('span','key', labelText);
  const pill = el('span','pill', lang);
  row.append(caret, key, pill);

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
  row.onclick = () => { const collapsed = card.classList.toggle('collapsed');
    caret.classList.toggle('closed', collapsed); caret.textContent = collapsed ? '▸' : '▾';
    if (!collapsed) build(); };
  li._expand = () => { if (card.classList.contains('collapsed')){ card.classList.remove('collapsed');
    caret.classList.remove('closed'); caret.textContent='▾'; build(); } };
  li.append(row, card);
  li._searchText = (labelText + ' ' + content).toLowerCase();
  return li;
}

// Build an <li> for any (key,value). Objects/arrays -> collapsible folders.
function nodeFor(keyText, value, depth){
  if (typeof value === 'string' && looksLikeCode(keyText, value)) return makeCodeNode(keyText, value);

  if (value === null || typeof value !== 'object'){
    const li = el('li');
    const row = el('div','row leaf');
    row.append(el('span','bullet','●'), el('span','key', keyText + ':'));
    row.appendChild(el('span','val ' + scalarClass(value), scalarText(value)));
    li.appendChild(row);
    li._searchText = (keyText + ' ' + scalarText(value)).toLowerCase();
    return li;
  }

  const isArr = Array.isArray(value);
  const entries = isArr
    ? value.map((v,i) => [ itemLabel(v) || ('[' + i + ']'), v ])
    : Object.keys(value).map(k => [k, value[k]]);

  const li = el('li');
  const row = el('div','row folder');
  const caret = el('span','caret');
  const key = el('span','key', keyText);
  const count = el('span','count', entries.length);
  row.append(caret, key, count);
  if (isArr) row.appendChild(el('span','pill list','list'));

  const childUl = el('ul','children');
  const open = depth < 1;
  caret.textContent = open ? '▾' : '▸';
  caret.classList.toggle('closed', !open);
  if (!open) childUl.classList.add('collapsed');
  entries.forEach(([k,v]) => childUl.appendChild(nodeFor(k, v, depth + 1)));
  row.onclick = () => { const c = childUl.classList.toggle('collapsed');
    caret.classList.toggle('closed', c); caret.textContent = c ? '▸' : '▾'; };
  li._expand = () => { childUl.classList.remove('collapsed'); caret.classList.remove('closed'); caret.textContent='▾'; };
  li.append(row, childUl);
  li._searchText = keyText.toLowerCase();
  return li;
}

function setAll(collapsed){
  document.querySelectorAll('ul.children').forEach(ul => ul.classList.toggle('collapsed', collapsed));
  if (collapsed) document.querySelectorAll('.code-card').forEach(c => c.classList.add('collapsed'));
  document.querySelectorAll('.row.folder .caret').forEach(c => { c.classList.toggle('closed', collapsed); c.textContent = collapsed ? '▸' : '▾'; });
}
document.getElementById('expandAll').onclick  = () => setAll(false);
document.getElementById('collapseAll').onclick = () => setAll(true);
document.getElementById('reload').onclick = () => window.location.reload();

// ---- Export all: fetch the raw project files and build a ZIP in-browser (no libraries) ----
const CRC_T = (() => { const t = new Uint32Array(256);
  for (let n=0;n<256;n++){ let c=n; for (let k=0;k<8;k++) c = c&1 ? 0xEDB88320 ^ (c>>>1) : c>>>1; t[n]=c>>>0; } return t; })();
function crc32(u8){ let c=0xFFFFFFFF; for (let i=0;i<u8.length;i++) c = CRC_T[(c ^ u8[i]) & 0xFF] ^ (c>>>8); return (c ^ 0xFFFFFFFF) >>> 0; }
function zipStore(entries){ // entries: [{path, data:Uint8Array}]  -> Blob (stored, no compression)
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

// theme toggle (persisted)
const root = document.documentElement;
try { const saved = localStorage.getItem('npdev-tree-theme'); if (saved) root.dataset.theme = saved; } catch(_){}
document.getElementById('theme').onclick = () => {
  const next = root.dataset.theme === 'light' ? 'dark' : 'light';
  root.dataset.theme = next; try { localStorage.setItem('npdev-tree-theme', next); } catch(_){}
};

// Filter: a node is visible if it or any descendant matches; matches auto-expand.
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

fetch('app-tree.json', { cache: 'no-store' })
  .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
  .then(data => {
    const app = data.appId || 'App';
    document.getElementById('appLabel').textContent = app;
    document.getElementById('logo').textContent = (app[0] || 'A').toUpperCase();
    document.getElementById('meta').textContent =
      'definition tree · read-only · generated ' + (data.generatedAt || '').replace('T',' ').slice(0,19);
    const sections = data.sections || {};
    Object.keys(sections).forEach(k => treeEl.appendChild(nodeFor(k, sections[k], 0)));
  })
  .catch(e => {
    const p = el('p','err','Could not load app-tree.json: ' + e.message +
      ' — run New-AppTreePage.ps1 first, and open this page over http:// (not file://).');
    treeEl.replaceWith(p);
  });
</script>
</body></html>
'@
$html = $tpl.Replace('__APP__', $AppId)
Set-Content -LiteralPath (Join-Path $StaticDir 'app-tree.html') -Value $html -Encoding UTF8

Write-Host "Emitted tree page: $(Join-Path $StaticDir 'app-tree.html') (+ app-tree.json)"
