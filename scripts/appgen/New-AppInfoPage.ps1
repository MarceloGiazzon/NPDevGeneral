<#
.SYNOPSIS
  Emit an interactive app-info page (info.html) into an app's static folder.

.DESCRIPTION
  Writes a Property / Value / Copy / Open table with working clipboard buttons and open
  actions, served same-origin at http://localhost:<port>/info.html (localhost is a secure
  context so navigator.clipboard works; static is exempt from the API-key filter). GET API
  endpoints are opened via fetch with the X-Api-Key header; pages open directly.

  Must write into the App module's own src/main/resources/static (NOT npdev-generated/,
  which the runtime strict-execution validator hashes). See appgen-finalapp-recipe memory.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$StaticDir,
  [Parameter(Mandatory = $true)][string]$AppId,
  [Parameter(Mandatory = $true)][int]$Port,
  [Parameter(Mandatory = $true)][string]$AppFolder,
  [Parameter(Mandatory = $true)][string]$OutRoot,
  [Parameter(Mandatory = $true)][string]$GeneratedAppRoot,
  [Parameter(Mandatory = $true)][string]$OpsDir,
  [string]$Engine = 'InMemory',
  [string]$JdbcUrl = '',
  [string]$DbDataRoot = '',
  [string]$DbName = '',
  [string[]]$Flows = @(),
  [string[]]$Concepts = @(),
  [string[]]$CompanionFiles = @(),
  [string]$BuilderName = '',
  [string]$ConsoleLaunch = '',
  [int]$ConsolePort = 0,
  [string]$LoginPath = '',
  [string]$HomePath = ''
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null
$base = "http://localhost:$Port"
$rows = New-Object System.Collections.Generic.List[object]
function Add-InfoRow($section, $p, $v, $o = '') { $rows.Add([pscustomobject]@{ s = $section; p = $p; v = "$v"; o = "$o" }) }

Add-InfoRow 'Paths' 'App definition'        $AppFolder
Add-InfoRow 'Paths' 'Model'                 (Join-Path $AppFolder 'definition\model.json')
Add-InfoRow 'Paths' 'Generated output root' $OutRoot
Add-InfoRow 'Paths' 'Generated app'         $GeneratedAppRoot
Add-InfoRow 'Paths' 'Runnable jar'          (Join-Path $GeneratedAppRoot 'build\libs\FinalExec-0.1.0.jar')
Add-InfoRow 'Paths' 'Ops toolbox'           $OpsDir
Add-InfoRow 'Paths' 'Super User key file'   (Join-Path $OpsDir 'SUPER_USER_KEY.txt')
if ($Engine -eq 'H2Server' -and $DbName) { Add-InfoRow 'Paths' 'DB file' (Join-Path $DbDataRoot "$DbName.mv.db") }
Add-InfoRow 'URLs' 'Base URL'              $base $base
Add-InfoRow 'URLs' 'Operator UI (app)'     "$base/npdev-ui" "$base/npdev-ui"
Add-InfoRow 'URLs' 'Business UI (concepts)' "$base/npdev-business-ui/" "$base/npdev-business-ui/"
Add-InfoRow 'URLs' 'Info page'             "$base/info.html" "$base/info.html"
Add-InfoRow 'URLs' 'App tree'              "$base/app-tree.html" "$base/app-tree.html"
Add-InfoRow 'URLs' 'Control Panel'         "$base/control-panel.html" "$base/control-panel.html"
if ($LoginPath) { Add-InfoRow 'URLs' 'Login' "$base$LoginPath" "$base$LoginPath" }
if ($HomePath)  { Add-InfoRow 'URLs' 'Home'  "$base$HomePath"  "$base$HomePath" }
Add-InfoRow 'URLs' 'API key header'        'X-Api-Key: dev-key'
Add-InfoRow 'Flows' 'Flows list'            "$base/api/flows" "$base/api/flows"
foreach ($f in $Flows)    { Add-InfoRow 'Flows' "Execute $f" "$base/api/flows/$f/execute" '' }
Add-InfoRow 'Monitoring' 'Health'                "$base/actuator/health" "$base/actuator/health"
Add-InfoRow 'Monitoring' 'Audit'                 "$base/api/audit" "$base/api/audit"
Add-InfoRow 'Monitoring' 'Storage summary'       "$base/api/admin/storage/summary" "$base/api/admin/storage/summary"
foreach ($c in $Concepts) { $cp = $c.ToLower() + 's'; Add-InfoRow 'Concepts' "CRUD $c" "$base/api/$cp" "$base/api/$cp" }
foreach ($f in $CompanionFiles) { Add-InfoRow 'Companion Files' "Companion $f" "$base/$f" "$base/$f" }
if ($Engine -eq 'H2Server') {
  Add-InfoRow 'Database' 'DB engine' 'H2Server'
  Add-InfoRow 'Database' 'JDBC URL' $JdbcUrl
  Add-InfoRow 'Database' 'DB user / password' 'sa / (empty)'
  Add-InfoRow 'Database' 'DB driver' 'org.h2.Driver 2.2.224'
} else {
  Add-InfoRow 'Database' 'DB engine' $Engine
  Add-InfoRow 'Database' 'DB access' 'in-process, not externally reachable'
}
if ($ConsoleLaunch) { Add-InfoRow 'Console' 'Console (local)' $ConsoleLaunch }
Add-InfoRow 'Commands' 'Builder script' 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-AppGenApp.ps1'
if ($BuilderName) { Add-InfoRow 'Commands' 'Generate cmd' "& 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-AppGenApp.ps1' -App $BuilderName" }
Add-InfoRow 'Commands' 'Build cmd' ("& '" + (Join-Path $OpsDir 'Build-App.ps1') + "'")
Add-InfoRow 'Commands' 'Start cmd' ("& '" + (Join-Path $OpsDir 'Start-App.ps1') + "'")
Add-InfoRow 'Commands' 'Test cmd'  ("& '" + (Join-Path $OpsDir 'Test-App.ps1') + "'")
Add-InfoRow 'Commands' 'Stop cmd'  ("& '" + (Join-Path $OpsDir 'Stop-App.ps1') + "'")

$json = $rows | ConvertTo-Json -Depth 4
if ($rows.Count -eq 1) { $json = "[$json]" }

$tpl = @'
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__APP__ - info</title>
<style>
 body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;font-size:14px}
 header{padding:12px 16px;background:#1b1b1b;font-size:16px}
 header small{opacity:.6;font-size:12px}
 .controls{padding:8px 16px}
 .controls input{background:#000;color:#eee;border:1px solid #444;border-radius:4px;padding:5px 8px;width:260px}
 table{border-collapse:collapse;width:100%}
 th,td{border-bottom:1px solid #2a2a2a;padding:6px 10px;text-align:left;vertical-align:top}
 th{position:sticky;top:0;background:#222;color:#9bd}
 td.p{white-space:nowrap;color:#9bd}
 tr.section td{background:#181818;color:#7fa;font-weight:bold;padding:5px 10px;letter-spacing:.04em;text-transform:uppercase;font-size:12px}
 code{color:#cde;word-break:break-all}
 button{background:#2d6cdf;color:#fff;border:0;border-radius:4px;padding:3px 9px;cursor:pointer;font-size:14px}
 .open{background:none;border:0;cursor:pointer;font-size:16px;color:#9bd}
 .dash{opacity:.4}
 .consolepanel{border-top:2px solid #2d6cdf;margin-top:10px}
 .cbar{display:flex;gap:8px;align-items:center;padding:8px 16px;background:#161616;flex-wrap:wrap}
 .cbar input{flex:1;min-width:240px;background:#000;color:#cde;border:1px solid #444;border-radius:4px;padding:5px 8px;font-family:monospace}
 #cframe{width:100%;height:46vh;border:0;background:#0c0c0c}
 .muted{opacity:.55;font-size:12px}
</style></head><body>
<header>__APP__ <small>__BASE__ &middot; X-Api-Key: dev-key</small></header>
<div class="controls"><input id="flt" placeholder="filter..." oninput="doFilter()"></div>
<table><thead><tr><th>Property</th><th>Value</th><th>Copy</th><th>Open</th></tr></thead><tbody id="tb"></tbody></table>
<script>
const DATA = __DATA__;
const KEY = 'dev-key';
const tb = document.getElementById('tb');
function flash(btn){const o=btn.dataset.o||btn.innerHTML;btn.dataset.o=o;btn.innerHTML='&#10003;';setTimeout(()=>btn.innerHTML=o,900);}
function legacyCopy(v,btn){try{const t=document.createElement('textarea');t.value=v;t.style.position='fixed';t.style.opacity='0';document.body.appendChild(t);t.focus();t.select();document.execCommand('copy');document.body.removeChild(t);flash(btn);}catch(e){btn.innerHTML='&#10007;';}}
function copy(v, btn){ if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(v).then(()=>flash(btn)).catch(()=>legacyCopy(v,btn));}else{legacyCopy(v,btn);} }
function openUrl(u){
  if(!/\/api\//.test(u)){ window.open(u,'_blank'); return; }
  fetch(u,{headers:{'X-Api-Key':KEY}}).then(r=>r.text()).then(t=>{const w=window.open('','_blank');w.document.title=u;w.document.body.style.cssText='background:#111;color:#cde;font-family:monospace';w.document.body.innerHTML='<pre style="white-space:pre-wrap;padding:12px">'+t.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))+'</pre>';}).catch(e=>alert(e));
}
function mkSectionRow(s){
  const tr=document.createElement('tr'); tr.className='section'; tr.dataset.section=s;
  const td=document.createElement('td'); td.colSpan=4; td.textContent=s; tr.appendChild(td);
  return tr;
}
function mkRow(r){
  const tr=document.createElement('tr'); tr.dataset.section=r.s; tr.dataset.k=(r.p+' '+r.v).toLowerCase();
  const p=document.createElement('td'); p.className='p'; p.textContent=r.p; tr.appendChild(p);
  const v=document.createElement('td'); const c=document.createElement('code'); c.textContent=r.v; v.appendChild(c); tr.appendChild(v);
  const cc=document.createElement('td'); const b=document.createElement('button'); b.innerHTML='&#128203;'; b.title='Copy value'; b.onclick=()=>copy(r.v,b); cc.appendChild(b); tr.appendChild(cc);
  const oc=document.createElement('td'); if(r.o){const a=document.createElement('button');a.className='open';a.innerHTML='&#128279;';a.title='Open';a.onclick=()=>openUrl(r.o);oc.appendChild(a);}else{const s=document.createElement('span');s.className='dash';s.textContent='-';oc.appendChild(s);} tr.appendChild(oc);
  return tr;
}
let lastSection=null;
DATA.forEach(r=>{ if(r.s!==lastSection){ tb.appendChild(mkSectionRow(r.s)); lastSection=r.s; } tb.appendChild(mkRow(r)); });
function doFilter(){
  const q=document.getElementById('flt').value.toLowerCase();
  const visibleSections=new Set();
  [...tb.children].forEach(tr=>{
    if(tr.classList.contains('section')) return;
    const show=tr.dataset.k.includes(q);
    tr.style.display=show?'':'none';
    if(show) visibleSections.add(tr.dataset.section);
  });
  [...tb.children].forEach(tr=>{
    if(tr.classList.contains('section')) tr.style.display=visibleSections.has(tr.dataset.section)?'':'none';
  });
}
</script>
__CONSOLE__
</body></html>
'@
$consoleSection = ''
if ($ConsolePort -gt 0) {
  $curl = "http://127.0.0.1:$ConsolePort/"
  $consoleSection = @"
<div class="consolepanel">
  <div class="cbar"><b>Control terminal</b>
    <input id="curl" value="$curl">
    <button onclick="cf()">Reload</button>
    <span class="muted">Auto-loads $curl below. It is a SEPARATE local server: run the 'Console (local)' command first (if the frame is blank/refused, it isn't running yet). The app's own Operator UI is a different page.</span>
  </div>
  <iframe id="cframe" title="console" src="$curl"></iframe>
</div>
<script>function cf(){var u=document.getElementById('curl').value.trim();if(u)document.getElementById('cframe').src=u;}</script>
"@
}
$html = $tpl.Replace('__APP__', $AppId).Replace('__BASE__', $base).Replace('__DATA__', $json).Replace('__CONSOLE__', $consoleSection)
# info.html: served by the app at /info.html when it is running (same-origin).
Set-Content -LiteralPath (Join-Path $StaticDir 'info.html') -Value $html -Encoding UTF8
# starter.html: a copy at the output root, openable via file:// even when the app is OFF
# (info + paths + DB + generate/build/start commands; Copy works via the legacy fallback).
Set-Content -LiteralPath (Join-Path $OutRoot 'starter.html') -Value $html -Encoding UTF8
