<#
.SYNOPSIS
  Emit agent-prompter.html -- a primitive test-of-concept page for composing a prompt to hand to
  an external AI tool, describing this app so it can be created or modified.

.DESCRIPTION
  A static, self-contained HTML+JS page, same boundary as every other hand-emitted page in this
  toolbox (New-ControlPanelPage.ps1, New-AppTreePage.ps1, New-PropertiesAdminPage.ps1): written
  into the App module's own src/main/resources/static, never templated with live data at
  generation time. Everything it shows is fetched at PAGE-LOAD time from same-origin static files
  the app already serves unauthenticated, so this one script works unchanged for every app -- no
  per-app parameters beyond where to write it and what name to show before detection runs.

  "Always set with an app" (the design ask this page exists to satisfy): on load it tries
  app-tree.json first (New-AppTreePage.ps1's fully-resolved model -- concepts WITH fields, flows,
  pages, menu), falling back to info.json (always emitted by the core generator, lighter: concept
  and flow NAMES only) if app-tree.json is absent. If neither loads -- opened over file://, or the
  app is not running -- the page falls back to a manual "app name" field instead of silently
  guessing, matching the "if not, have to select one" requirement.

  It composes a text prompt (current model context + a free-text request) and offers Copy to
  clipboard. It can ALSO send that prompt, but never directly: the browser posts to this app's own
  /api/agent-proxy/generate, which is SUPERUSER-gated and holds the provider key server-side (see
  secrets/agent-proxy.env.example). The page never sees a key, never hardcodes a provider endpoint,
  and never sends anything to an app whose operator has not configured one. With no provider
  configured -- the default for every generated app -- it degrades to exactly its previous behaviour:
  compose and copy. "Not configured" is an honest state here, never an error.

  Compose and Send stay separate on purpose: Send transmits the exact text shown in the Prompt box,
  so what the user reads is what leaves the machine.

.EXAMPLE
  & 'D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\New-AgentPrompterPage.ps1' `
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
<title>__APP__ - Agent Prompter</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { color-scheme: light dark; }
  body { font: 14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif; margin: 0; padding: 24px; max-width: 860px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #667; margin-bottom: 20px; }
  fieldset { border: 1px solid #8884; border-radius: 8px; margin-bottom: 16px; padding: 12px 16px; }
  legend { font-weight: 600; padding: 0 6px; }
  label { display: block; font-size: 12px; text-transform: uppercase; color: #667; font-weight: 600; margin: 10px 0 4px; }
  label:first-child { margin-top: 0; }
  input[type=text] { width: 100%; box-sizing: border-box; padding: 6px 8px; font: inherit; }
  textarea { width: 100%; box-sizing: border-box; padding: 8px; font: inherit; resize: vertical; }
  #ask { height: 90px; }
  #promptOut { height: 260px; font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 10px 0; }
  button { padding: 6px 14px; cursor: pointer; }
  button.primary { font-weight: 600; }
  .note { font-size: 12px; color: #667; }
  #detected { font-size: 12.5px; color: #667; }
  #detected b { color: inherit; }
  #offline { display: none; background: #8884; border-radius: 8px; padding: 10px 14px; margin-bottom: 16px; font-size: 13px; }
  #copyStatus { font-size: 12.5px; color: #2a7; }
  select { padding: 5px 6px; font: inherit; }
  input[type=password] { padding: 6px 8px; font: inherit; }
  #providerNote { font-size: 12.5px; color: #667; }
  #providerControls { display: none; }
  #responseBox { display: none; }
  #answer { height: 220px; }
  #sendStatus { font-size: 12.5px; color: #667; }
  #sendStatus.err { color: #c33; }
</style>
</head>
<body>
<h1>Agent Prompter <span class="note">(__APP__)</span></h1>
<p class="sub">
  Compose a prompt describing this app, then copy it into whichever AI tool you use &mdash; or, if
  this app's operator has configured a provider, send it from here. The prompt is sent by the app's
  own server, never by your browser: no API key is ever loaded into this page.
</p>
<div id="offline"></div>

<fieldset>
  <legend>Target app</legend>
  <div id="detected">detecting…</div>
  <label for="appName">App name (used in the prompt; edit if the detected name is wrong)</label>
  <input type="text" id="appName" placeholder="my-app" autocomplete="off">
  <div class="row">
    <label style="margin:0; text-transform:none; font-weight:normal; font-size:13px; display:flex; gap:6px; align-items:center">
      <input type="checkbox" id="includeContext" style="width:auto">
      Include the current model as context (concepts, fields, flows)
    </label>
  </div>
</fieldset>

<fieldset>
  <legend>What do you want to build or change?</legend>
  <textarea id="ask" placeholder="e.g. Add a 'Priority' field to the Ticket concept, with values Low/Medium/High, and show it as a column on the Tickets list."></textarea>
</fieldset>

<fieldset id="providerBox">
  <legend>Send from this app (optional)</legend>
  <div id="providerNote">checking…</div>
  <div id="providerControls">
    <div class="row">
      <label for="vendor" style="margin:0">Provider</label>
      <select id="vendor"></select>
      <label for="model" style="margin:0">Model</label>
      <input type="text" id="model" list="modelSuggestions" style="width:auto; min-width:220px" autocomplete="off">
      <datalist id="modelSuggestions"></datalist>
      <span id="effortWrap" hidden>
        <label for="effort" style="margin:0">Effort</label>
        <select id="effort">
          <option value="">default</option>
          <option value="low">low</option>
          <option value="medium">medium</option>
          <option value="high">high</option>
        </select>
      </span>
    </div>
  </div>
  <label for="superKey">Super user key (header X-Super-User-Key &mdash; needed to send; kept in memory only, never stored)</label>
  <div class="row">
    <input type="password" id="superKey" placeholder="paste this app's super user key" autocomplete="off" style="flex:1; min-width:260px">
    <button id="checkBtn">Check</button>
  </div>
</fieldset>

<div class="row">
  <button class="primary" id="generateBtn">Generate prompt</button>
  <button id="copyBtn">📋 Copy prompt</button>
  <button id="sendBtn" disabled title="configure a provider first">Send to provider</button>
  <span id="copyStatus"></span>
  <span id="sendStatus"></span>
</div>

<fieldset>
  <legend>Prompt</legend>
  <textarea id="promptOut" readonly placeholder="Press &quot;Generate prompt&quot; above."></textarea>
</fieldset>

<fieldset id="responseBox">
  <legend>Response</legend>
  <textarea id="answer" readonly></textarea>
  <div class="row">
    <button id="copyAnswerBtn">📋 Copy response</button>
    <span id="copyAnswerStatus" style="font-size:12.5px; color:#2a7"></span>
  </div>
</fieldset>

<script>
var APP_ID = "__APP__";

function origin_() {
  var origin = window.location.origin;
  return /^https?:$/.test(window.location.protocol) && origin && origin !== 'null' ? origin : null;
}

async function detectApp() {
  if (!origin_()) return null;
  // Prefer app-tree.json: the fully-resolved model, concepts WITH fields. Run-on-demand
  // (New-AppTreePage.ps1), so it may be absent even when the app is live.
  try {
    var r = await fetch('app-tree.json', { cache: 'no-store' });
    if (r.ok) {
      var doc = await r.json();
      var model = doc.sections && doc.sections.Model;
      return { source: 'app-tree.json', appName: doc.appId || APP_ID || '', context: model || null };
    }
  } catch (e) { /* fall through to info.json */ }
  // Always emitted by the core generator -- lighter (concept/flow NAMES only, no fields).
  try {
    var r2 = await fetch('info.json', { cache: 'no-store' });
    if (r2.ok) {
      var doc2 = await r2.json();
      return {
        source: 'info.json',
        appName: doc2.namespace || APP_ID || '',
        context: { dbEngine: doc2.dbEngine, concepts: doc2.concepts, flows: doc2.flows }
      };
    }
  } catch (e2) { /* neither source available */ }
  return null;
}

var detected = null;

function setDetectedNote(text) {
  document.getElementById('detected').innerHTML = text;
}

(async function init() {
  if (!origin_()) {
    var banner = document.getElementById('offline');
    banner.hidden = false;
    banner.textContent = 'Opened from a file, not from the running app. Start the app and reload ' +
      'this page to auto-detect its model -- until then, type an app name below and describe the ' +
      'change; the prompt just will not include current-model context.';
  }
  detected = await detectApp();
  var nameInput = document.getElementById('appName');
  var includeCb = document.getElementById('includeContext');
  if (detected && detected.appName) {
    nameInput.value = detected.appName;
    setDetectedNote('Detected from <b>' + detected.source + '</b>: <b>' + detected.appName + '</b>. ' +
      'Not right, or describing a different app? Edit the field below.');
    includeCb.checked = !!detected.context;
    includeCb.disabled = !detected.context;
  } else {
    nameInput.value = APP_ID || '';
    setDetectedNote('Could not detect a running app here. Type a name below, or start this app ' +
      'and reload -- then this page uses its actual model as context.');
    includeCb.checked = false;
    includeCb.disabled = true;
  }
})();

var CONTEXT_CHAR_CAP = 60000; // keep the clipboard payload sane for a large app

function buildPrompt() {
  var appName = document.getElementById('appName').value.trim() || 'this app';
  var ask = document.getElementById('ask').value.trim() || '(describe what to build or change above)';
  var includeContext = document.getElementById('includeContext').checked;

  var contextBlock = '';
  if (includeContext && detected && detected.context) {
    var json = JSON.stringify(detected.context, null, 2);
    var truncated = false;
    if (json.length > CONTEXT_CHAR_CAP) { json = json.slice(0, CONTEXT_CHAR_CAP); truncated = true; }
    contextBlock = '\n=== Current model ("' + appName + '", from ' + detected.source + ') ===\n' +
      json + (truncated ? '\n... (truncated)' : '') + '\n';
  }

  var prompt =
    'You are helping extend an app built on NPDev, a JSON-model-driven app platform. An NPDev ' +
    'app is described by a JSON "model": concepts (entities with typed fields), flows ' +
    '(multi-step server-side operations), pages and a menu. Below is the current model for an ' +
    'app called "' + appName + '", followed by what I want changed.\n\n' +
    'Please respond with the specific JSON changes needed (new or modified concepts, fields, or ' +
    'flows), explained clearly enough for me to apply by hand.\n' +
    contextBlock +
    '\n=== What I want ===\n' + ask + '\n';

  document.getElementById('promptOut').value = prompt;
  document.getElementById('copyStatus').textContent = '';
}

document.getElementById('generateBtn').addEventListener('click', buildPrompt);

document.getElementById('copyBtn').addEventListener('click', async function () {
  var out = document.getElementById('promptOut');
  if (!out.value.trim()) buildPrompt();
  var status = document.getElementById('copyStatus');
  try {
    await navigator.clipboard.writeText(out.value);
    status.textContent = 'copied';
    setTimeout(function () { status.textContent = ''; }, 2200);
  } catch (e) {
    status.textContent = 'could not copy -- select the text and copy manually';
  }
});

// ---------------------------------------------------------------------------
// Live send, through this app's own server-side proxy.
//
// The key never reaches this page: the browser posts a prompt and a super-user key to
// /api/agent-proxy/generate, and the app adds the provider credential on the server side from
// secrets/agent-proxy.env. That is also why there is no provider endpoint anywhere in this file --
// the caller picks WHICH configured vendor, never WHERE the request goes.
// ---------------------------------------------------------------------------

var providerConfig = null;

// One header covers all three auth modes. SuperUserCredentialAuthFilter runs ahead of both the
// api-key and the JWT filter and marks the request authenticated, and both skip a request that is
// already authenticated -- so an app in apikey mode does not additionally need an X-Api-Key here,
// and /generate needs SUPERUSER anyway, which only this header can grant.
function authHeaders() {
  var key = document.getElementById('superKey').value.trim();
  return key ? { 'X-Super-User-Key': key } : {};
}

function setProviderNote(text) {
  document.getElementById('providerNote').textContent = text;
}

function selectedVendor() {
  if (!providerConfig) return null;
  var id = document.getElementById('vendor').value;
  for (var i = 0; i < providerConfig.vendors.length; i++) {
    if (providerConfig.vendors[i].id === id) return providerConfig.vendors[i];
  }
  return null;
}

function onVendorChanged() {
  var vendor = selectedVendor();
  if (!vendor) return;
  document.getElementById('model').value = vendor.defaultModel || '';
  var list = document.getElementById('modelSuggestions');
  list.innerHTML = '';
  (vendor.models || []).forEach(function (m) {
    var option = document.createElement('option');
    option.value = m;
    list.appendChild(option);
  });
  // Shown only where the provider actually has an equivalent knob. Offering it everywhere would
  // either fail the send or silently do nothing, and the second is worse.
  document.getElementById('effortWrap').hidden = !vendor.effortSupported;
}

async function loadProviderConfig(userInitiated) {
  if (!origin_()) {
    document.getElementById('providerBox').hidden = true;
    return;
  }
  var controls = document.getElementById('providerControls');
  var sendBtn = document.getElementById('sendBtn');
  controls.style.display = 'none';
  sendBtn.disabled = true;
  try {
    var r = await fetch('api/agent-proxy/config', { headers: authHeaders(), cache: 'no-store' });
    if (r.status === 404) {
      // The controller is absent -- an app generated before this feature existed, or one running
      // with the proxy removed from its supported surface. Not an error the user can fix here.
      setProviderNote('Live send is not available in this app. Compose and copy instead.');
      return;
    }
    if (r.status === 401 || r.status === 403) {
      setProviderNote(userInitiated
        ? 'That key was not accepted (' + r.status + '). Check it and press Check again.'
        : 'Enter this app’s super user key above and press Check to see whether live send is available.');
      return;
    }
    if (!r.ok) {
      setProviderNote('Could not read the provider configuration (HTTP ' + r.status + '). Compose and copy still work.');
      return;
    }
    providerConfig = await r.json();
    var usable = (providerConfig.vendors || []).filter(function (v) { return v.keyPresent; });
    if (!providerConfig.configured || usable.length === 0) {
      var names = (providerConfig.vendors || []).map(function (v) { return v.keyEnvVarName; })
        .filter(function (n) { return !!n; });
      setProviderNote('Live send is not configured for this app — compose and copy instead. '
        + '(Operator: copy secrets/agent-proxy.env.example to secrets/agent-proxy.env and set '
        + (names.length ? 'one of ' + names.join(', ') : 'a provider key') + ', then restart via _ops.)');
      return;
    }
    providerConfig.vendors = usable;
    var vendorSelect = document.getElementById('vendor');
    vendorSelect.innerHTML = '';
    usable.forEach(function (v) {
      var option = document.createElement('option');
      option.value = v.id;
      option.textContent = v.id;
      vendorSelect.appendChild(option);
    });
    onVendorChanged();
    // 'block', not '': clearing the inline style would just re-expose the stylesheet's display:none.
    controls.style.display = 'block';
    setProviderNote('Ready to send through this app’s server. The provider key stays on the server.');
    sendBtn.disabled = false;
    sendBtn.title = '';
  } catch (e) {
    setProviderNote('Could not reach this app’s API (' + e.message + '). Compose and copy still work.');
  }
}

document.getElementById('vendor').addEventListener('change', onVendorChanged);
document.getElementById('checkBtn').addEventListener('click', function () { loadProviderConfig(true); });

document.getElementById('sendBtn').addEventListener('click', async function () {
  var vendor = selectedVendor();
  var status = document.getElementById('sendStatus');
  status.className = '';
  if (!vendor) { status.textContent = 'no provider selected'; return; }
  if (!document.getElementById('superKey').value.trim()) {
    status.className = 'err';
    status.textContent = 'a super user key is required to send';
    return;
  }

  // Compose/send separation, enforced in code rather than by convention: Send transmits exactly the
  // text sitting in the Prompt box, so the user has always seen the payload before it leaves.
  var out = document.getElementById('promptOut');
  if (!out.value.trim()) buildPrompt();

  var sendBtn = document.getElementById('sendBtn');
  sendBtn.disabled = true;
  status.textContent = 'sending…';
  try {
    var r = await fetch('api/agent-proxy/generate', {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body: JSON.stringify({
        vendor: vendor.id,
        model: document.getElementById('model').value.trim(),
        effort: vendor.effortSupported ? document.getElementById('effort').value : null,
        prompt: out.value
      })
    });
    var body = null;
    try { body = await r.json(); } catch (e) { /* non-JSON error body */ }
    if (!r.ok) {
      // The server's 409 deny reasons are written to be read by an operator ("no API key configured
      // (env var ...)"), so show the reason rather than a generic failure.
      status.className = 'err';
      status.textContent = 'failed (' + r.status + '): ' + ((body && (body.message || body.error)) || 'see the app log');
      return;
    }
    document.getElementById('answer').value = body.text || '(the provider returned no text)';
    document.getElementById('responseBox').style.display = 'block';
    status.textContent = 'answered by ' + body.vendor + ' / ' + body.model;
  } catch (e) {
    status.className = 'err';
    status.textContent = 'send failed: ' + e.message;
  } finally {
    sendBtn.disabled = false;
  }
});

document.getElementById('copyAnswerBtn').addEventListener('click', async function () {
  var status = document.getElementById('copyAnswerStatus');
  try {
    await navigator.clipboard.writeText(document.getElementById('answer').value);
    status.textContent = 'copied';
    setTimeout(function () { status.textContent = ''; }, 2200);
  } catch (e) {
    status.textContent = 'could not copy -- select the text and copy manually';
  }
});

// Try once with no credential: an auth.mode=none app answers immediately and the provider row just
// appears. Anything else lands on the "enter your key and press Check" note above.
loadProviderConfig(false);
</script>
</body></html>
'@
$html = $tpl.Replace('__APP__', $AppId)
Set-Content -LiteralPath (Join-Path $StaticDir 'agent-prompter.html') -Value $html -Encoding UTF8

Write-Host "Emitted Agent Prompter page: $(Join-Path $StaticDir 'agent-prompter.html')"
