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
</style>
</head>
<body>
<h1>__APP__ &mdash; Verification</h1>
<div class="sub" id="subject">Read-only inventory of this app's verification checks and their last-known results. No action here runs anything.</div>

<div class="cols" id="cols"></div>
<table id="table">
  <thead><tr>
    <th>State</th><th>Item</th><th>Category</th><th>Last result</th><th>Last run</th><th>Last duration</th><th>Last-known description</th>
  </tr></thead>
  <tbody id="rows"></tbody>
</table>

<p class="note">Shows what was true when this page was generated (works with the app stopped). To see fresh results, re-run the generated verification and regenerate this page.</p>

<script>
var VERIFICATION = __BLOB__;

function esc(v){ var d=document.createElement('div'); d.textContent= v==null?'':String(v); return d.innerHTML; }
function dur(v){ if(v==null) return '—'; return v>=60 ? (v/60).toFixed(1)+' min' : v+'s'; }
function rel(iso){ if(!iso) return '—'; var d=(new Date(iso)).getTime(); if(!isFinite(d)) return esc(iso); var m=Math.floor((Date.now()-d)/60000); if(m<1)return 'just now'; if(m<60)return m+'m ago'; var h=Math.floor(m/60); if(h<24)return h+'h ago'; return Math.floor(h/24)+'d ago'; }
function col(item){ if(!item.lastRun) return 'never-run'; if(item.lastRun.result==='failed') return 'failing'; return 'healthy'; }
var COLS={ 'never-run':'NEVER RUN','failing':'FAILING','stale':'STALE','healthy':'HEALTHY' };
function badge(r){ if(!r) return '<span class="never">—</span>'; var c=(r==='skipped'||r==='not-applicable'||r==='cancelled')?'skip':r; return '<span class="badge '+c+'">'+esc(r.toUpperCase())+'</span>'; }

(function(){
  var doc = VERIFICATION;
  if(!doc || !doc.items) { document.getElementById('rows').innerHTML='<tr><td colspan="7">No verification items declared.</td></tr>'; return; }
  var counts={ 'never-run':0,'failing':0,'stale':0,'healthy':0 };
  doc.items.forEach(function(i){ counts[col(i)]++; });
  var cols='';
  Object.keys(COLS).forEach(function(k){ cols+='<div class="col"><h3>'+COLS[k]+' <b>'+counts[k]+'</b></h3></div>'; });
  document.getElementById('cols').innerHTML=cols;
  var s=doc.subject||{}; document.getElementById('subject').textContent='Read-only inventory of \''+(s.name||'')+'\' — generated '+(doc.generatedAt||'');
  var rows='';
  doc.items.forEach(function(i){ var l=i.lastRun||{}; rows+=
    '<tr><td>'+COLS[col(i)]+'</td><td><strong>'+esc(i.name)+'</strong><div class="mono" style="color:#667">'+esc(i.id)+'</div></td>'+
    '<td>'+esc(i.category||'')+'</td><td>'+badge(l.result)+'</td><td class="mono">'+rel(l.startedAt)+'</td>'+
    '<td>'+dur(l.durationSeconds)+'</td><td>'+esc(i.description||'')+'</td></tr>'; });
  document.getElementById('rows').innerHTML=rows;
})();
</script>
</body></html>
