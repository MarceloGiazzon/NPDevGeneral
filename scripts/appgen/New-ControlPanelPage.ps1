<#
.SYNOPSIS
  Emit the ControlPanel page (control-panel.html) into an app's static folder.

.DESCRIPTION
  Sibling to New-AppInfoPage.ps1, same dual-write shape (App module's own static dir, so it's
  served at http://localhost:<port>/control-panel.html when the app is running, plus a copy at
  OutRoot for reference). Unlike info.html, this page is authenticated and action-capable: it
  talks to the Super User-gated ControlPanel endpoints (TenantAdminController, DataSeedAdminController,
  ControlPanelAdminUserController) using the X-Super-User-Key header, completely independent of the
  app's own business auth.mode. Emitted for every app, unconditionally -- the ControlPanel is meant
  to always be present, unlike business Login which stays optional per app.

  Written for a non-specialist first-time user: a "Quick Start" guided flow (create a workspace +
  load starter data + create its first login, as one step-by-step action) is the primary path;
  the underlying tenant/seed/user primitives are still available individually under a collapsed
  "Advanced" section for return visits, each with a plain-language explanation of what it does.

  Must write into the App module's own src/main/resources/static (NOT npdev-generated/, which the
  runtime strict-execution validator hashes). See appgen-finalapp-recipe memory.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$StaticDir,
  [Parameter(Mandatory = $true)][string]$AppId,
  [Parameter(Mandatory = $true)][int]$Port,
  [Parameter(Mandatory = $true)][string]$OutRoot
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null
$base = "http://localhost:$Port"
$keyFilePath = Join-Path $OutRoot '_ops\SUPER_USER_KEY.txt'

$tpl = @'
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="icon" href="data:,">
<title>__APP__ - Control Panel</title>
<style>
 *{box-sizing:border-box}
 body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;font-size:14px}
 header{padding:14px 16px;background:#1b1b1b;border-bottom:1px solid #2a2a2a}
 header h1{margin:0;font-size:18px}
 header p{margin:4px 0 0;font-size:12px;opacity:.65;max-width:640px}
 main{padding:14px;max-width:720px;margin:0 auto}
 section{background:#1b1b1b;border:1px solid #2a2a2a;border-radius:8px;padding:16px;margin-bottom:16px}
 section.quickstart{border-color:#2d6cdf;box-shadow:0 0 0 1px rgba(45,108,223,.25)}
 h2{margin:0 0 6px 0;font-size:16px;color:#eee}
 .sub{font-size:12px;opacity:.65;margin-bottom:14px;line-height:1.5}
 .field{display:flex;flex-direction:column;gap:4px;margin-bottom:12px}
 .field label{font-size:12px;opacity:.85;font-weight:600}
 .field .hint{font-size:11px;opacity:.55;font-weight:400}
 .row{display:flex;gap:10px;flex-wrap:wrap}
 .row > .field{flex:1;min-width:180px}
 input[type=text],input[type=password],select{background:#000;color:#eee;border:1px solid #444;border-radius:5px;padding:8px;font-size:13px;width:100%}
 button.primary{background:#2d6cdf;color:#fff;border:0;border-radius:6px;padding:10px 18px;cursor:pointer;font-size:14px;font-weight:600}
 button.primary:hover{background:#255bbd}
 button.primary:disabled{opacity:.5;cursor:not-allowed}
 button.small{background:#333;color:#eee;border:1px solid #444;border-radius:5px;padding:5px 10px;cursor:pointer;font-size:12px}
 button.small:hover{background:#3d3d3d}
 table{width:100%;border-collapse:collapse;font-size:13px;margin:10px 0}
 th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #2a2a2a}
 th{color:#9bd;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.03em}
 #keyMsg,#qsMsg{font-size:12px;margin-top:10px;min-height:16px}
 .ok{color:#4caf50}
 .err{color:#e05252}
 .muted{opacity:.55;font-size:12px}
 #qsLog{background:#0b0b0b;border:1px solid #2a2a2a;border-radius:6px;padding:10px;font-size:12px;margin-top:12px;max-height:220px;overflow:auto;white-space:pre-wrap;display:none}
 #qsLog .step{color:#9bd}
 #qsLog .ok{color:#4caf50}
 #qsLog .err{color:#e05252}
 #qsResult{display:none;margin-top:12px;background:#14301f;border:1px solid #2d6c3f;border-radius:6px;padding:12px;font-size:13px}
 #qsResult a{color:#7fd88f}
 details{margin-top:4px}
 summary{cursor:pointer;font-size:13px;color:#9bd;padding:4px 0}
 .adv-section{border-top:1px solid #2a2a2a;padding-top:14px;margin-top:14px}
 .adv-section:first-child{border-top:0;padding-top:0;margin-top:0}
 #gate{display:none}
 #locked .sub{margin-bottom:18px}
 .keybox{background:#14301f;border:1px solid #2d6c3f;border-radius:8px;padding:12px 14px;margin:10px 0 16px}
 .keybox .row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:6px}
 .keybox code{flex:1;min-width:220px;color:#cfe;word-break:break-all}
</style></head><body>
<header>
  <h1>__APP__ &mdash; Control Panel</h1>
  <p>This page is for whoever sets up and maintains this application (the "Super User") &mdash;
     not for the people who use it day to day. If you're looking for the regular login, that's a
     different page.</p>
</header>
<main>

  <section id="locked">
    <h2>Unlock the Control Panel</h2>
    <div class="sub">
      The very first time this application started, it saved a one-time key to a file at the path
      below. Open that file, copy its contents, and paste it into the field below. That key is
      your master password for this page only. Once you've saved it somewhere safe (a password
      manager, a note), you can delete that file &mdash; it isn't needed again. If it's already
      gone and you don't have the key saved anywhere, run <code>Reissue-SuperUserKey.ps1</code>
      from this app's ops folder to get a new one (it will recreate that same file).
    </div>
    <div class="keybox">
      <b>&#128273; Super User key file</b>
      <div class="row">
        <code id="keyFilePath">__KEYFILEPATH__</code>
        <button class="small" id="copyKeyPath">&#128203; Copy path</button>
      </div>
    </div>
    <form id="unlockForm" onsubmit="return false;">
      <div class="field">
        <label for="key">Super User key</label>
        <input type="password" id="key" placeholder="npk_..." autocomplete="off" />
      </div>
      <button class="primary" id="saveKey">Unlock</button>
      <div id="keyMsg"></div>
    </form>
  </section>

  <div id="gate">
    <section class="quickstart">
      <h2>Quick Start: set up a new workspace</h2>
      <div class="sub">
        A <strong>workspace</strong> is a fully separate copy of this application's data &mdash;
        its own warehouses, products, and logins, kept apart from any other workspace on this
        server. Use this if you're setting things up for a new customer, a new demo, or just
        starting fresh. This does three things at once: creates the workspace, loads some starter
        data into it (optional), and creates the first login for it.
      </div>
      <div class="row">
        <div class="field">
          <label for="qsName">Workspace name</label>
          <input type="text" id="qsName" placeholder="Acme Warehousing" />
          <span class="hint">A human-friendly name, shown in lists.</span>
        </div>
        <div class="field">
          <label for="qsId">Workspace ID</label>
          <input type="text" id="qsId" placeholder="acme" />
          <span class="hint">Short, lowercase, no spaces &mdash; used behind the scenes.</span>
        </div>
      </div>
      <div class="field">
        <label for="qsSeed">Starter data</label>
        <select id="qsSeed"><option value="">(start empty, no starter data)</option></select>
        <span class="hint">Pre-built demo warehouses so you don't have to enter everything by hand. Optional.</span>
      </div>
      <form id="quickStartForm" onsubmit="return false;">
        <div class="row">
          <div class="field">
            <label for="qsUsername">First login &mdash; username</label>
            <input type="text" id="qsUsername" placeholder="admin" autocomplete="username" />
          </div>
          <div class="field">
            <label for="qsDisplayName">Their name</label>
            <input type="text" id="qsDisplayName" placeholder="Acme Administrator" autocomplete="name" />
          </div>
          <div class="field">
            <label for="qsPassword">Password</label>
            <input type="password" id="qsPassword" autocomplete="new-password" />
          </div>
        </div>
        <button class="primary" id="qsSubmit">Create workspace</button>
      </form>
      <div id="qsLog"></div>
      <div id="qsResult"></div>
    </section>

    <section>
      <h2>Advanced</h2>
      <div class="sub">
        These are the same tools Quick Start uses, exposed individually &mdash; useful once you
        already have a workspace and want to add data or a login to it later, without creating a
        new one.
      </div>

      <div class="adv-section">
        <h3 style="margin:0 0 4px;font-size:14px">Workspaces</h3>
        <div class="sub" style="margin-bottom:8px">
          Every workspace ever created on this server. "Disable" blocks all of that workspace's
          users from logging in or using the app, without deleting any of its data &mdash; useful
          for suspending a customer without losing their information.
        </div>
        <div id="tenantsTable"></div>
      </div>

      <div class="adv-section">
        <h3 style="margin:0 0 4px;font-size:14px">Load starter data into an existing workspace</h3>
        <div class="sub" style="margin-bottom:8px">Pick a workspace, then click Run next to a starter dataset to load it in.</div>
        <div class="field" style="max-width:260px">
          <label for="seedTenantId">Into which workspace?</label>
          <input type="text" id="seedTenantId" placeholder="acme" />
        </div>
        <div id="seedsTable"></div>
      </div>

      <div class="adv-section">
        <h3 style="margin:0 0 4px;font-size:14px">Add another login</h3>
        <div class="sub" style="margin-bottom:8px">Creates one more login for an existing workspace (not a Super User &mdash; a regular Admin, same as Quick Start's first login).</div>
        <form id="addLoginForm" onsubmit="return false;">
          <div class="row">
            <div class="field"><label>Workspace ID</label><input type="text" id="tuTenantId" placeholder="acme" /></div>
            <div class="field"><label>Username</label><input type="text" id="tuUsername" autocomplete="username" /></div>
            <div class="field"><label>Their name</label><input type="text" id="tuDisplayName" autocomplete="name" /></div>
            <div class="field"><label>Password</label><input type="password" id="tuPassword" autocomplete="new-password" /></div>
          </div>
          <button class="small" id="createTenantAdmin">Create login</button>
        </form>
        <div id="tenantAdminResult" class="muted" style="margin-top:8px"></div>
      </div>
    </section>
  </div>
</main>
<script>
const BASE = '__BASE__';
const $ = (id) => document.getElementById(id);
function key() { return localStorage.getItem('npdev.controlpanel.key') || ''; }
function slugify(v) { return (v || '').toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, ''); }
function flashCopyBtn(btn){ const o = btn.dataset.o || btn.innerHTML; btn.dataset.o = o; btn.innerHTML = '&#10003; Copied'; setTimeout(() => btn.innerHTML = o, 1200); }
function legacyCopy(v, btn){ try { const t = document.createElement('textarea'); t.value = v; t.style.position = 'fixed'; t.style.opacity = '0'; document.body.appendChild(t); t.focus(); t.select(); document.execCommand('copy'); document.body.removeChild(t); flashCopyBtn(btn); } catch (e) { btn.innerHTML = '&#10007; Failed'; } }
function copyText(v, btn){ if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(v).then(() => flashCopyBtn(btn)).catch(() => legacyCopy(v, btn)); } else { legacyCopy(v, btn); } }

window.addEventListener('DOMContentLoaded', function () {
  const saved = localStorage.getItem('npdev.controlpanel.key');
  if (saved) { $('key').value = saved; unlock(); }

  $('saveKey').addEventListener('click', unlock);
  $('copyKeyPath').addEventListener('click', function () { copyText($('keyFilePath').textContent, $('copyKeyPath')); });
  $('qsName').addEventListener('input', function () {
    if (!$('qsId').dataset.touched) $('qsId').value = slugify($('qsName').value);
  });
  $('qsId').addEventListener('input', function () { $('qsId').dataset.touched = '1'; });
  $('qsSubmit').addEventListener('click', runQuickStart);
  $('createTenantAdmin').addEventListener('click', createTenantAdmin);
});

async function unlock() {
  const k = $('key').value.trim();
  if (!k) { setKeyMsg('Paste the key from the startup log first.', 'err'); return; }
  localStorage.setItem('npdev.controlpanel.key', k);
  try {
    await api('/api/admin/tenants'); // validates the key actually works before revealing the page
    $('locked').style.display = 'none';
    $('gate').style.display = 'block';
    loadTenants();
    loadSeeds();
  } catch (e) {
    const friendly = e.message === 'invalid_super_user_key'
      ? "That key wasn't recognized. Double-check you copied it exactly, with no extra spaces."
      : 'That key was not accepted: ' + e.message;
    setKeyMsg(friendly, 'err');
  }
}

function setKeyMsg(text, cls) { const m = $('keyMsg'); m.className = cls || ''; m.textContent = text; }

async function api(path, options) {
  options = options || {};
  options.headers = Object.assign({ 'X-Super-User-Key': key(), 'Content-Type': 'application/json' }, options.headers || {});
  const res = await fetch(BASE + path, options);
  const text = await res.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch (e) { /* leave null */ }
  if (!res.ok) {
    const errMsg = (parsed && (parsed.error || parsed.message)) || text || ('HTTP ' + res.status);
    throw new Error(errMsg);
  }
  return parsed;
}

function qsLog(text, cls) {
  const el = $('qsLog');
  el.style.display = 'block';
  const line = document.createElement('div');
  line.className = cls || '';
  line.textContent = text;
  el.appendChild(line);
  el.scrollTop = el.scrollHeight;
}

async function runQuickStart() {
  const name = $('qsName').value.trim();
  const tenantId = $('qsId').value.trim();
  const seedId = $('qsSeed').value;
  const username = $('qsUsername').value.trim();
  const displayName = $('qsDisplayName').value.trim();
  const password = $('qsPassword').value;

  if (!tenantId || !username || !displayName || !password) {
    qsLog('Please fill in Workspace ID, username, name, and password.', 'err');
    return;
  }
  $('qsSubmit').disabled = true;
  $('qsLog').innerHTML = ''; $('qsLog').style.display = 'block';
  $('qsResult').style.display = 'none';

  try {
    qsLog('Creating workspace "' + tenantId + '"...', 'step');
    await api('/api/admin/tenants', { method: 'POST', body: JSON.stringify({ tenantId: tenantId, displayName: name || tenantId }) });
    qsLog('Workspace created.', 'ok');

    if (seedId) {
      qsLog('Loading starter data (' + seedId + ')...', 'step');
      const seedResult = await api('/api/admin/seeds/' + encodeURIComponent(seedId) + '/run?tenantId=' + encodeURIComponent(tenantId), { method: 'POST' });
      if (seedResult.ok) { qsLog('Starter data loaded.', 'ok'); }
      else { qsLog('Starter data failed to load: ' + (seedResult.failureMessage || 'unknown error') + ' (workspace was still created).', 'err'); }
    }

    qsLog('Creating first login "' + username + '"...', 'step');
    await api('/api/admin/tenant-admins', { method: 'POST', body: JSON.stringify({ tenantId: tenantId, username: username, displayName: displayName, password: password }) });
    qsLog('Login created.', 'ok');

    const result = $('qsResult');
    result.style.display = 'block';
    result.innerHTML = 'Done! Sign in at <a href="login.html?username=' + encodeURIComponent(username) + '&tenant=' + encodeURIComponent(tenantId) + '" target="_blank">login.html</a> '
      + 'with username <strong>' + escHtml(username) + '</strong> in workspace <strong>' + escHtml(tenantId) + '</strong>.';

    loadTenants();
  } catch (e) {
    qsLog('Failed: ' + e.message, 'err');
  } finally {
    $('qsSubmit').disabled = false;
  }
}

async function loadTenants() {
  try {
    const tenants = await api('/api/admin/tenants');
    renderTenants(Array.isArray(tenants) ? tenants : []);
  } catch (e) { /* Advanced section only; Quick Start already reports its own errors */ }
}

function renderTenants(rows) {
  const el = $('tenantsTable');
  if (!rows.length) { el.innerHTML = '<div class="muted">No workspaces yet &mdash; use Quick Start above to create the first one.</div>'; return; }
  const table = document.createElement('table');
  table.innerHTML = '<thead><tr><th>Workspace</th><th>Name</th><th>Status</th><th></th></tr></thead>';
  const tbody = document.createElement('tbody');
  rows.forEach(function (t) {
    const tr = document.createElement('tr');
    const action = t.status === 'DISABLED' ? 'enable' : 'disable';
    tr.innerHTML = '<td>' + escHtml(t.tenantId) + '</td><td>' + escHtml(t.displayName || '') + '</td><td>' + escHtml(t.status) + '</td><td></td>';
    const btn = document.createElement('button');
    btn.className = 'small';
    btn.textContent = action === 'enable' ? 'Enable' : 'Disable';
    btn.onclick = function () { toggleTenant(t.tenantId, action); };
    tr.lastElementChild.appendChild(btn);
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  el.innerHTML = '';
  el.appendChild(table);
}

async function toggleTenant(tenantId, action) {
  try {
    await api('/api/admin/tenants/' + encodeURIComponent(tenantId) + '/' + action, { method: 'POST' });
    loadTenants();
  } catch (e) { alert('Could not ' + action + ' workspace: ' + e.message); }
}

async function loadSeeds() {
  try {
    const seeds = await api('/api/admin/seeds');
    renderSeeds(Array.isArray(seeds) ? seeds : []);
    populateSeedDropdown(Array.isArray(seeds) ? seeds : []);
  } catch (e) { /* Advanced section only */ }
}

function populateSeedDropdown(rows) {
  const select = $('qsSeed');
  rows.forEach(function (s) {
    const opt = document.createElement('option');
    opt.value = s.id;
    opt.textContent = s.label || s.id;
    opt.title = s.description || '';
    select.appendChild(opt);
  });
}

function renderSeeds(rows) {
  const el = $('seedsTable');
  if (!rows.length) { el.innerHTML = '<div class="muted">No starter datasets declared for this app.</div>'; return; }
  const table = document.createElement('table');
  table.innerHTML = '<thead><tr><th>Dataset</th><th>Description</th><th></th></tr></thead>';
  const tbody = document.createElement('tbody');
  rows.forEach(function (s) {
    const tr = document.createElement('tr');
    tr.innerHTML = '<td>' + escHtml(s.label || s.id) + '</td><td class="muted">' + escHtml(s.description || '') + '</td><td></td>';
    const btn = document.createElement('button');
    btn.className = 'small';
    btn.textContent = 'Run';
    btn.onclick = function () { runSeed(s.id); };
    tr.lastElementChild.appendChild(btn);
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  el.innerHTML = '';
  el.appendChild(table);
}

async function runSeed(id) {
  const tenantId = $('seedTenantId').value.trim();
  if (!tenantId) { alert('Enter which workspace to load this data into first.'); return; }
  try {
    const result = await api('/api/admin/seeds/' + encodeURIComponent(id) + '/run?tenantId=' + encodeURIComponent(tenantId), { method: 'POST' });
    alert(result.ok ? 'Loaded successfully.' : ('Failed: ' + (result.failureMessage || 'unknown error')));
  } catch (e) { alert('Failed: ' + e.message); }
}

async function createTenantAdmin() {
  const body = {
    tenantId: $('tuTenantId').value.trim(),
    username: $('tuUsername').value.trim(),
    displayName: $('tuDisplayName').value.trim(),
    password: $('tuPassword').value
  };
  if (!body.tenantId || !body.username || !body.displayName || !body.password) {
    $('tenantAdminResult').textContent = 'All fields are required.';
    return;
  }
  try {
    const result = await api('/api/admin/tenant-admins', { method: 'POST', body: JSON.stringify(body) });
    $('tenantAdminResult').textContent = 'Created "' + result.username + '" in workspace "' + result.tenantId + '".';
    $('tuUsername').value = ''; $('tuDisplayName').value = ''; $('tuPassword').value = '';
  } catch (e) { $('tenantAdminResult').textContent = 'Failed: ' + e.message; }
}

function escHtml(v) { return (v == null ? '' : String(v)).replace(/[&<>]/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;'})[c]; }); }
</script>
</body></html>
'@

$html = $tpl.Replace('__APP__', $AppId).Replace('__BASE__', $base).Replace('__KEYFILEPATH__', $keyFilePath)
Set-Content -LiteralPath (Join-Path $StaticDir 'control-panel.html') -Value $html -Encoding UTF8
Set-Content -LiteralPath (Join-Path $OutRoot 'control-panel.html') -Value $html -Encoding UTF8
