<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>__APP__ - Verification</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  body { font: 14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif; margin: 0; padding: 24px; max-width: 1100px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #667; margin-bottom: 20px; font-size: 13px; }
  .cols { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 14px; margin-bottom: 24px; }
  .col { border: 1px solid #8884; border-radius: 8px; padding: 10px 12px; }
  .col h3 { margin: 0 0 8px; font-size: 12px; text-transform: uppercase; letter-spacing: 1px; color: #667; }
  .col b { float: right; }
  table { width: 100%; border-collapse: collapse; }
  td, th { text-align: left; padding: 7px 8px; border-bottom: 1px solid #8882; vertical-align: top; font-size: 13px; }
  th { font-size: 11px; text-transform: uppercase; color: #667; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
  .badge { display: inline-block; font-size: 11px; padding: 1px 8px; border-radius: 4px; border: 1px solid #8884; }
  .passed   { color: #1a7f37; background: #f0fff4; }
  .failed   { color: #c33;   background: #fff0f0; }
  .skip, .skipped, .cancelled, .not-applicable { color: #9a6b00; background: #fffaf0; }
  .running  { color: #0969da; background: #f0f6ff; }
  .never    { color: #888; }
  .note { font-size: 12px; color: #667; margin-top: 18px; }
  .actbtn { font-size: 11px; padding: 2px 8px; border-radius: 4px; border: 1px solid #8884; background: #fff; cursor: pointer; margin-right: 4px; }
  .actbtn:disabled { opacity: .4; cursor: default; }
  .actbtn.run { border-color: #1a7f37; color: #1a7f37; }
  .actbtn.stop { border-color: #c33; color: #c33; }
</style>
</head>
<body>
<h1>__APP__ &mdash; Verification</h1>
<div class="sub" id="subject">Inventory of this app's verification checks and their last-known results.</div>
<p class="sub" id="healthNote" style="display:none">Signed in as Super User: Status-App, Status-Environment and Check-Provenance can be run live below. Every other row stays a last-known snapshot -- no other script is ever reachable from this page.</p>

<div class="cols" id="cols"></div>
<table id="table">
  <thead><tr>
    <th>State</th><th>Item</th><th>Category</th><th>Last result</th><th>Last run</th><th>Last duration</th><th>Last-known description</th><th>Live</th>
  </tr></thead>
  <tbody id="rows"></tbody>
</table>

<p class="note">Rows without a Live action show what was true when this page was generated (works with the app stopped). To see fresh results for those, re-run the generated verification and regenerate this page.</p>

<script>
var VERIFICATION = __BLOB__;
var HEALTH_ITEMS = {}; // healthId -> live item, populated only if /api/admin/health/items succeeds (SUPERUSER)
var HEALTH_AVAILABLE = false;
var HEALTH_POLL = null;

function esc(v){ var d=document.createElement('div'); d.textContent= v==null?'':String(v); return d.innerHTML; }
function dur(v){ if(v==null) return '—'; return v>=60 ? (v/60).toFixed(1)+' min' : v+'s'; }
function rel(iso){ if(!iso) return '—'; var d=(new Date(iso)).getTime(); if(!isFinite(d)) return esc(iso); var m=Math.floor((Date.now()-d)/60000); if(m<1)return 'just now'; if(m<60)return m+'m ago'; var h=Math.floor(m/60); if(h<24)return h+'h ago'; return Math.floor(h/24)+'d ago'; }
function col(item){ if(!item.lastRun) return 'never-run'; if(item.lastRun.result==='failed'||item.lastRun.result==='timed-out') return 'failing'; return 'healthy'; }
var COLS={ 'never-run':'NEVER RUN','failing':'FAILING','stale':'STALE','healthy':'HEALTHY' };
function badge(r){ if(!r) return '<span class="never">—</span>'; var c=(r==='skipped'||r==='not-applicable'||r==='cancelled'||r==='stopped')?'skip':r; return '<span class="badge '+c+'">'+esc(r.toUpperCase())+'</span>'; }

function effectiveItem(i){
  // The live overlay wins whenever it has actually run this session -- otherwise fall back to the
  // baked-in last-known snapshot, so a never-refreshed page still shows something meaningful.
  if(i.healthId && HEALTH_ITEMS[i.healthId] && HEALTH_ITEMS[i.healthId].lastRun){
    return Object.assign({}, i, { lastRun: HEALTH_ITEMS[i.healthId].lastRun });
  }
  return i;
}

function actionsCell(i){
  if(!i.healthId || !HEALTH_AVAILABLE) return '';
  var live = HEALTH_ITEMS[i.healthId] || {};
  var running = live.lastRun && live.lastRun.result === 'running';
  var runBtn = '<button class="actbtn run" data-run="'+esc(i.healthId)+'"'+(running?' disabled':'')+'>▶ Run</button>';
  var stopBtn = '<button class="actbtn stop" data-stop="'+esc(i.healthId)+'"'+(running?'':' disabled')+'>■ Stop</button>';
  var logBtn = (live.lastRun) ? '<button class="actbtn" data-log="'+esc(i.healthId)+'">Log</button>' : '';
  return runBtn+stopBtn+logBtn;
}

function render(){
  var doc = VERIFICATION;
  if(!doc || !doc.items) { document.getElementById('rows').innerHTML='<tr><td colspan="8">No verification items declared.</td></tr>'; return; }
  var counts={ 'never-run':0,'failing':0,'stale':0,'healthy':0 };
  var effective = doc.items.map(effectiveItem);
  effective.forEach(function(i){ counts[col(i)]++; });
  var cols='';
  Object.keys(COLS).forEach(function(k){ cols+='<div class="col"><h3>'+COLS[k]+' <b>'+counts[k]+'</b></h3></div>'; });
  document.getElementById('cols').innerHTML=cols;
  var s=doc.subject||{}; document.getElementById('subject').textContent='Inventory of \''+(s.name||'')+'\' — generated '+(doc.generatedAt||'');
  document.getElementById('healthNote').style.display = HEALTH_AVAILABLE ? '' : 'none';
  var rows='';
  effective.forEach(function(i){ var l=i.lastRun||{}; rows+=
    '<tr><td>'+COLS[col(i)]+'</td><td><strong>'+esc(i.name)+'</strong><div class="mono" style="color:#667">'+esc(i.id)+'</div></td>'+
    '<td>'+esc(i.category||'')+'</td><td>'+badge(l.result)+'</td><td class="mono">'+rel(l.startedAt)+'</td>'+
    '<td>'+dur(l.durationSeconds)+'</td><td>'+esc(i.description||'')+'</td><td>'+actionsCell(i)+'</td></tr>'; });
  document.getElementById('rows').innerHTML=rows;
  document.querySelectorAll('[data-run]').forEach(function(b){ b.addEventListener('click', function(){ runHealthItem(b.dataset.run); }); });
  document.querySelectorAll('[data-stop]').forEach(function(b){ b.addEventListener('click', function(){ stopHealthItem(b.dataset.stop); }); });
  document.querySelectorAll('[data-log]').forEach(function(b){ b.addEventListener('click', function(){ window.open('/api/admin/health/log/'+encodeURIComponent(b.dataset.log), '_blank'); }); });
}

// S5.3 lift: the ONLY network calls this page ever makes, all against ControlPanelHealthController,
// all SUPERUSER-gated server-side regardless of what this client-side code does. A 403/network
// error here is not an error state to report -- it means "not signed in as Super User" or "app not
// reachable", both of which this page already handles by falling back to its baked-in snapshot.
function refreshHealth(){
  return fetch('/api/admin/health/items', { credentials: 'same-origin' })
    .then(function(r){ if(!r.ok) throw new Error('not available'); return r.json(); })
    .then(function(doc){
      HEALTH_AVAILABLE = true;
      HEALTH_ITEMS = {};
      (doc.items||[]).forEach(function(i){ HEALTH_ITEMS[i.id] = i; });
      render();
    })
    .catch(function(){ HEALTH_AVAILABLE = false; render(); });
}

function runHealthItem(id){
  fetch('/api/admin/health/run/'+encodeURIComponent(id), { method: 'POST', credentials: 'same-origin' })
    .then(function(){ startPolling(); })
    .catch(function(){ });
}
function stopHealthItem(id){
  fetch('/api/admin/health/stop/'+encodeURIComponent(id), { method: 'POST', credentials: 'same-origin' })
    .then(function(){ startPolling(); })
    .catch(function(){ });
}
function startPolling(){
  refreshHealth();
  if(HEALTH_POLL) return;
  var ticks = 0;
  HEALTH_POLL = setInterval(function(){
    ticks++;
    refreshHealth().then(function(){
      var stillRunning = Object.keys(HEALTH_ITEMS).some(function(k){ return HEALTH_ITEMS[k].lastRun && HEALTH_ITEMS[k].lastRun.result === 'running'; });
      if(!stillRunning || ticks > 300){ clearInterval(HEALTH_POLL); HEALTH_POLL = null; }
    });
  }, 2000);
}

render();
refreshHealth();
</script>
</body></html>
