<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="icon" href="data:,">
<title>__APP__ - Hosting</title>
<style>
 *{box-sizing:border-box}
 body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;font-size:14px}
 header{padding:14px 16px;background:#1b1b1b;border-bottom:1px solid #2a2a2a}
 header h1{margin:0;font-size:18px}
 header p{margin:4px 0 0;font-size:12px;opacity:.65;max-width:640px}
 main{padding:14px;max-width:860px;margin:0 auto}
 section{background:#1b1b1b;border:1px solid #2a2a2a;border-radius:8px;padding:16px;margin-bottom:16px}
 h2{margin:0 0 6px 0;font-size:16px;color:#eee}
 .sub{font-size:12px;opacity:.65;margin-bottom:14px;line-height:1.5}
 table{width:100%;border-collapse:collapse;font-size:13px;margin:10px 0}
 th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #2a2a2a;vertical-align:top}
 th{color:#9bd;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.03em}
 .ok{color:#4caf50}
 .warn{color:#e0a952}
 .fix{color:#e05252}
 .muted{opacity:.55;font-size:12px}
 .badge{display:inline-block;border-radius:4px;padding:1px 7px;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.03em}
 .badge.ok{background:#14301f;color:#4caf50}
 .badge.warn{background:#332a14;color:#e0a952}
 .badge.fix{background:#331616;color:#e05252}
 .hop{display:flex;align-items:baseline;gap:10px;padding:8px 0;border-bottom:1px solid #2a2a2a}
 .hop:last-child{border-bottom:0}
 .hop .name{min-width:160px;font-weight:600}
 .hop .meaning{opacity:.75;font-size:12.5px}
 code{color:#cfe;word-break:break-all}
 .targets{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px;margin-top:10px}
 .target-card{border:1px solid #2a2a2a;border-radius:6px;padding:10px;font-size:12.5px}
 .target-card.current{border-color:#2d6cdf;box-shadow:0 0 0 1px rgba(45,108,223,.25)}
 .target-card h3{margin:0 0 4px;font-size:13px}
 .target-card ul{margin:6px 0 0;padding-left:16px}
 .target-card li{margin-bottom:4px;opacity:.85}
 #err{display:none;color:#e05252;font-size:13px}
</style></head><body>
<header>
 <h1>__APP__ &mdash; Hosting</h1>
 <p>Where this app runs, who can reach it, what would break for a real user right now, and what
    changes on a different target. Read-only -- use <code>npdev host plan</code>/<code>npdev host
    check --fix</code> to change anything.</p>
</header>
<main>
 <p id="err"></p>
 <section id="topology-section" style="display:none">
  <h2>Topology</h2>
  <p class="sub">One row per hop between a real user and this app's data.</p>
  <div id="topology"></div>
 </section>

 <section id="checks-section" style="display:none">
  <h2>Checks</h2>
  <p class="sub">Every non-OK row states what happens to a real user before it names a property.</p>
  <table><thead><tr><th>Status</th><th>Check</th><th>Detail</th><th>Fix</th></tr></thead>
   <tbody id="checks"></tbody>
  </table>
 </section>

 <section id="env-section" style="display:none">
  <h2>Environment</h2>
  <p class="sub">Every setting this hosting shape needs. <code>npdev</code> means the platform
     already resolved it; <code>you</code> means an operator must supply it.</p>
  <table><thead><tr><th>Variable</th><th>Value now</th><th>From</th><th>What breaks if wrong</th></tr></thead>
   <tbody id="env"></tbody>
  </table>
 </section>

 <section id="targets-section" style="display:none">
  <h2>What changes if I&hellip;</h2>
  <p class="sub">Every hosting target this app could move to, and the trade-off stated up front.</p>
  <div class="targets" id="targets"></div>
 </section>
</main>
<script>
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>]/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));

function statusBadge(status) {
  return '<span class="badge ' + esc(status) + '">' + esc(status) + '</span>';
}

function renderTopology(plan) {
  const rows = [];
  const runsOn = plan.runsOn || {};
  const dataLivesOn = plan.dataLivesOn || {};
  const reachableBy = plan.reachableBy || {};
  rows.push({ name: 'User', meaning: reachableBy.kind === 'nobody'
    ? 'Nobody but you can reach this app right now (rung ' + esc(plan.rung) + ').'
    : 'Reaches this app via ' + esc(reachableBy.kind) + (reachableBy.provider ? ' (' + esc(reachableBy.provider) + ')' : '') + '.' });
  if (reachableBy.kind !== 'nobody') {
    rows.push({ name: 'TLS termination', meaning: reachableBy.terminatesTlsElsewhere
      ? 'Terminated in front of this app -- forwarded headers must be trusted (see Checks).'
      : 'Not yet configured.' });
  }
  rows.push({ name: 'App', meaning: 'Runs on ' + esc(runsOn.kind || 'this-machine')
    + (runsOn.port ? ' at port ' + esc(runsOn.port) : '')
    + (runsOn.memoryMb ? ', capped at ' + esc(runsOn.memoryMb) + ' MB' : '') + '.' });
  rows.push({ name: 'Data', meaning: esc(dataLivesOn.engine || 'unknown') + ' on ' + esc(dataLivesOn.kind || 'this-machine')
    + (dataLivesOn.survivesRestart ? ', survives a restart' : ', DOES NOT survive a restart')
    + (dataLivesOn.survivesThisMachineDying ? ', survives this machine dying.' : '.') });
  $('topology').innerHTML = rows.map((r) =>
    '<div class="hop"><span class="name">' + esc(r.name) + '</span><span class="meaning">' + r.meaning + '</span></div>'
  ).join('');
  $('topology-section').style.display = '';
}

function renderChecks(findings) {
  if (!findings || !findings.length) return;
  $('checks').innerHTML = findings.map((f) =>
    '<tr><td>' + statusBadge(f.status) + '</td><td>' + esc(f.title) + '</td>'
    + '<td>' + esc(f.detail) + '</td><td>' + (f.fix ? esc(f.fix) : '<span class="muted">&mdash;</span>') + '</td></tr>'
  ).join('');
  $('checks-section').style.display = '';
}

function renderEnv(plan) {
  const env = plan.requiredEnv || {};
  const names = Object.keys(env);
  if (!names.length) return;
  $('env').innerHTML = names.map((name) => {
    const info = env[name] || {};
    const value = info.value == null ? '<span class="muted">(you must supply this)</span>' : esc(info.value);
    const source = info.source === 'npdev' ? 'npdev' : 'you';
    return '<tr><td><code>' + esc(name) + '</code></td><td>' + value + '</td>'
      + '<td>' + esc(source) + '</td><td>' + esc(info.why) + '</td></tr>';
  }).join('');
  $('env-section').style.display = '';
}

function renderTargets(plan, targets) {
  if (!targets || !targets.length) return;
  $('targets').innerHTML = targets.map((t) => {
    const current = t.id === plan.target;
    const mismatch = t.requiresEngine && plan.dataLivesOn && t.requiresEngine !== plan.dataLivesOn.engine;
    return '<div class="target-card' + (current ? ' current' : '') + '">'
      + '<h3>' + esc(t.label) + (current ? ' (current)' : '') + '</h3>'
      + (mismatch ? '<div class="fix">Needs regeneration: this app\'s engine is '
          + esc(plan.dataLivesOn.engine) + ', not ' + esc(t.requiresEngine) + '.</div>' : '')
      + '<ul>' + (t.caveats || []).map((c) => '<li>' + esc(c) + '</li>').join('') + '</ul>'
      + '</div>';
  }).join('');
  $('targets-section').style.display = '';
}

fetch('host-plan.json', { cache: 'no-store' })
  .then((r) => r.json())
  .then((data) => {
    const plan = data.plan || {};
    renderTopology(plan);
    renderChecks(data.findings);
    renderEnv(plan);
    renderTargets(plan, data.targets);
  })
  .catch((e) => {
    const el = $('err');
    el.textContent = 'Could not load host-plan.json: ' + e.message + ' -- run `npdev host plan` first.';
    el.style.display = '';
  });
</script>
</body></html>
