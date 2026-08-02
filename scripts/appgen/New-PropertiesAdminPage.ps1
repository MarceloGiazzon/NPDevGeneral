<#
.SYNOPSIS
  Emit properties.html -- RC-A5's generated admin surface for the scoped-property cascade.

.DESCRIPTION
  Move 14 Phase B item B3 (RC-A5): "generate the screen from the declarations -- one section per
  scope, one control per property, widget from type, visible only where settableAt allows, effective
  value and source scope shown inline (A3's explain)."

  A static, self-contained HTML+JS page calling the live REST surface PropertyResolverController
  exposes (GET /api/properties, GET /api/properties/scopes, GET/PUT /api/properties/{key}) -- open to
  every authenticated user regardless of role (REG-114's fix), so this page works for a plain user
  viewing/editing their own scope, not just an admin. A small embedded login form obtains a bearer
  token (this app's auth.mode is per-app; JWT apps need one, apikey apps can leave it blank and rely
  on a configured X-Api-Key instead -- see the note rendered in the page itself).

  Must write into the App module's own src/main/resources/static (NOT npdev-generated/, which the
  runtime strict-execution validator hashes) -- same boundary every other hand-emitted page in this
  toolbox already respects.

.EXAMPLE
  & 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\New-PropertiesAdminPage.ps1' `
    -StaticDir 'D:\WorkSpace\NPDev\Build\generated-finalapps\wmsoffice\App\src\main\resources\static' `
    -AppId 'wmsoffice'
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$StaticDir,
  [string]$AppId = ''
)
$ErrorActionPreference = 'Stop'

New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null

$tpl = @'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>__APP__ - Properties</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { color-scheme: light dark; }
  body { font: 14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif; margin: 0; padding: 24px; max-width: 960px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #667; margin-bottom: 20px; }
  fieldset { border: 1px solid #8884; border-radius: 8px; margin-bottom: 16px; padding: 12px 16px; }
  legend { font-weight: 600; padding: 0 6px; }
  table { width: 100%; border-collapse: collapse; }
  td, th { text-align: left; padding: 6px 8px; border-bottom: 1px solid #8882; vertical-align: top; }
  th { font-size: 12px; text-transform: uppercase; color: #667; font-weight: 600; }
  input[type=text], input[type=number] { width: 100%; box-sizing: border-box; padding: 4px 6px; }
  .src { font-size: 12px; color: #667; }
  .sec-relevant { color: #b45; font-weight: 600; }
  button { padding: 4px 10px; cursor: pointer; }
  #loginBox { border: 1px solid #8884; border-radius: 8px; padding: 16px; margin-bottom: 20px; }
  #loginBox input { margin-right: 6px; padding: 4px 6px; }
  #status { margin: 12px 0; font-size: 13px; }
  #err { color: #c33; }
  .hidden { display: none; }
</style>
</head>
<body>
<h1>__APP__ &mdash; Properties</h1>
<div class="sub">Generated admin surface (RC-A5) &mdash; one section per scope, effective value + source shown inline.</div>

<div id="loginBox">
  <strong>Authenticate</strong> (JWT apps only):
  <div style="margin-top:8px">
    <input id="tenantId" type="text" placeholder="tenant" value="trial">
    <input id="username" type="text" placeholder="username">
    <input id="password" type="password" placeholder="password">
    <button onclick="login()">Login</button>
    <button onclick="load()" title="apikey-mode apps need no login -- call the API directly">Skip login (apikey mode)</button>
    <span id="who"></span>
  </div>
</div>

<div id="status"></div>
<div id="sections"></div>

<script>
let token = '';

function authHeaders() {
  return token ? { 'Authorization': 'Bearer ' + token } : {};
}

async function login() {
  const tenantId = document.getElementById('tenantId').value.trim();
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
  setStatus('Logging in...');
  try {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, tenantId })
    });
    const body = await r.json();
    if (!r.ok) { setStatus('Login failed: ' + (body.error || r.status), true); return; }
    token = body.token;
    document.getElementById('who').textContent = '  logged in as ' + username + ' (' + (body.roles || []).join(',') + ')';
    setStatus('');
    load();
  } catch (e) {
    setStatus('Login error: ' + e.message, true);
  }
}

function setStatus(msg, isError) {
  const el = document.getElementById('status');
  el.textContent = msg || '';
  el.className = isError ? 'err' : '';
}

function widgetFor(prop, value) {
  const id = 'in_' + prop.name;
  if (prop.type === 'boolean') {
    return '<input type="checkbox" id="' + id + '"' + (value === true || value === 'true' ? ' checked' : '') + '>';
  }
  if (prop.type === 'int') {
    return '<input type="number" id="' + id + '" value="' + (value == null ? '' : value) + '">';
  }
  return '<input type="text" id="' + id + '" value="' + (value == null ? '' : String(value).replace(/"/g,'&quot;')) + '">';
}

function readWidget(prop) {
  const el = document.getElementById('in_' + prop.name);
  if (prop.type === 'boolean') return el.checked;
  if (prop.type === 'int') return el.value === '' ? null : parseInt(el.value, 10);
  return el.value === '' ? null : el.value;
}

async function save(prop, scopeType, scopeId) {
  const value = readWidget(prop);
  setStatus('Saving ' + prop.name + '@' + scopeType + '...');
  try {
    const r = await fetch('/api/properties/' + encodeURIComponent(prop.name), {
      method: 'PUT', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body: JSON.stringify({ scopeType, scopeId, value })
    });
    if (!r.ok) {
      const body = await r.json().catch(() => ({}));
      setStatus('Save failed (' + r.status + '): ' + (body.message || body.error || r.statusText), true);
      return;
    }
    setStatus('Saved ' + prop.name + '@' + scopeType + '.');
    load();
  } catch (e) {
    setStatus('Save error: ' + e.message, true);
  }
}

async function load() {
  setStatus('Loading...');
  try {
    const [propsR, scopesR] = await Promise.all([
      fetch('/api/properties', { headers: authHeaders() }),
      fetch('/api/properties/scopes', { headers: authHeaders() })
    ]);
    if (!propsR.ok || !scopesR.ok) {
      setStatus('Could not load declarations (HTTP ' + propsR.status + '/' + scopesR.status + '). Log in above if this app requires it.', true);
      return;
    }
    const properties = await propsR.json();
    const scopes = await scopesR.json();
    if (properties.length === 0) {
      document.getElementById('sections').innerHTML = '<p>This app declares no properties[] yet.</p>';
      setStatus('');
      return;
    }

    const explanations = {};
    for (const p of properties) {
      const r = await fetch('/api/properties/' + encodeURIComponent(p.name), { headers: authHeaders() });
      explanations[p.name] = r.ok ? await r.json() : null;
    }

    const container = document.getElementById('sections');
    container.innerHTML = '';
    for (const scope of scopes) {
      const applicable = properties.filter(p => p.settableAt.includes(scope.name));
      if (applicable.length === 0) continue;
      const fs = document.createElement('fieldset');
      const legend = document.createElement('legend');
      legend.textContent = scope.name + (scope.from ? '  (' + scope.from + ')' : '  (tenant-wide root scope)');
      fs.appendChild(legend);
      const table = document.createElement('table');
      table.innerHTML = '<tr><th>Property</th><th>Set here</th><th>Effective value / source</th><th></th></tr>';
      for (const prop of applicable) {
        const exp = explanations[prop.name];
        const tr = document.createElement('tr');
        const nameCell = '<td>' + (prop.securityRelevant ? '<span class="sec-relevant">&#9888; </span>' : '')
          + (prop.label || prop.name) + '</td>';
        const widgetCell = '<td>' + widgetFor(prop, exp ? exp.value : prop.defaultValue) + '</td>';
        const srcText = exp
          ? (exp.source.scopeType === 'default' ? 'default' : exp.source.scopeType + '=' + exp.source.scopeId) + ': ' + JSON.stringify(exp.value)
          : '(not resolvable)';
        const srcCell = '<td class="src">' + srcText + '</td>';
        tr.innerHTML = nameCell + widgetCell + srcCell;
        const btnTd = document.createElement('td');
        const btn = document.createElement('button');
        btn.textContent = 'Save';
        // scopeId left blank -- the server resolves "my own identity at this scope" the same way
        // it resolves a read (see PropertyResolverController#resolveOwnScopeId). An admin acting on
        // a broader-than-self scope (e.g. tenant-wide) still gets THEIR OWN tenant, which is the
        // only sensible target anyway (tenant isolation means there is no other tenant to reach).
        btn.onclick = () => save(prop, scope.name, '');
        btnTd.appendChild(btn);
        tr.appendChild(btnTd);
        table.appendChild(tr);
      }
      fs.appendChild(table);
      container.appendChild(fs);
    }
    setStatus('');
  } catch (e) {
    setStatus('Load error: ' + e.message, true);
  }
}

// Deliberately NOT called unconditionally here: this app may require JWT auth, and firing a
// request before the caller has a token produces a real (if internally handled) 401 network
// error. The caller triggers the first load() themselves, via Login or "Skip login" above.
</script>
</body></html>
'@
$html = $tpl.Replace('__APP__', $AppId)
Set-Content -LiteralPath (Join-Path $StaticDir 'properties.html') -Value $html -Encoding UTF8
