// The Share screen.
//
// Every panel here is a thin render of what `npdev host status|check|share|deploy --json` returned.
// Nothing in this file decides whether it is safe to share, which rung is available, whether a
// finding blocks, or what a target requires -- those answers come from the CLI, so this window and
// a terminal cannot reach different conclusions about the same app.
//
// The one thing this file DOES own is that the primary button is never disabled: when preflight
// would block, it says what it will do first ("Fix 2 things, then share") and does it. A
// non-expert left holding an error they cannot act on is the failure this screen exists to avoid.

const { invoke: hInvoke } = window.__TAURI__.core;
const { listen: hListen } = window.__TAURI__.event;

const hostingState = {
  appDir: null,
  status: null,     // host status
  check: null,      // host check
  targets: null,    // hosting-targets.json, via host_targets
  keys: null,       // host_keys (list)
  mintedKey: null,  // the raw value, only right after host_keys --new -- never persisted
  pickedRung: null,
  pickedTarget: null,   // only meaningful at rung >= 3
  pickedProvider: null, // only meaningful at rung 1 (cloudflared/ngrok)
  busy: false,
  progress: null,   // set only while runPrimaryAction's host-event stream is in flight
};

let hostingApps = [];

// ---------------------------------------------------------------------------------------------
// The app picker -- same monitor_scan source and "manual entry never disappears" shape as the Run
// screen's picker (app.js refreshRunAppPicker), so the two never learn to discover apps differently.
// ---------------------------------------------------------------------------------------------

async function refreshHostingAppPicker() {
  const picker = document.getElementById("hosting-app-picker");
  let apps = [];
  try {
    const result = await hInvoke("monitor_scan", { includeInfo: false });
    apps = (result.apps || []).filter((a) => a.status !== "not-an-app");
  } catch (err) {
    picker.innerHTML = `<option value="">(scan unavailable -- ${escapeHtml(String(err))})</option>`;
    hostingApps = [];
    return;
  }
  hostingApps = apps;
  if (!apps.length) {
    picker.innerHTML = `<option value="">(no generated apps found -- scan from the Monitor tab)</option>`;
    hostingState.appDir = null;
    return;
  }
  picker.innerHTML = apps
    .map((a) => `<option value="${escapeHtml(a.appDir)}">${escapeHtml(a.name || folderLeaf(a.appDir))}</option>`)
    .join("");
  const stillThere = hostingState.appDir && apps.some((a) => a.appDir === hostingState.appDir);
  hostingState.appDir = stillThere ? hostingState.appDir : apps[0].appDir;
  picker.value = hostingState.appDir;
}

document.getElementById("hosting-app-picker").addEventListener("change", () => {
  const picker = document.getElementById("hosting-app-picker");
  hostingState.appDir = picker.value || null;
  hostingState.pickedRung = null;
  hostingState.pickedTarget = null;
  hostingState.pickedProvider = null;
  hostingState.mintedKey = null;
  loadHosting();
});

// ---------------------------------------------------------------------------------------------
// Loading -- one call per panel's data, run together. `host_check`/`host_targets` resolve
// normally even when the CLI itself reports failure (an engine-mismatch refusal, "no
// host.definition.json yet") -- run_json/parse_single_json treat a non-zero exit carrying valid
// JSON as a RESULT, not a transport error, and the wrappers pass that straight through. Only a
// genuine transport problem (CLI missing, JSON truly absent) rejects the promise.
// ---------------------------------------------------------------------------------------------

async function loadHosting() {
  const stateEl = document.getElementById("hosting-app-state");
  if (!hostingState.appDir) {
    stateEl.textContent = hostingApps.length ? "" : "Pick an app to see its sharing state.";
    hostingState.status = null;
    hostingState.check = null;
    hostingState.targets = null;
    renderHosting();
    return;
  }
  stateEl.textContent = "Loading…";
  try {
    const [status, check, targets, keys] = await Promise.all([
      hInvoke("host_status", { appDir: hostingState.appDir }),
      hInvoke("host_check", { appDir: hostingState.appDir, fix: false, remote: null }),
      hInvoke("host_targets", { appDir: hostingState.appDir }),
      hInvoke("host_keys", { appDir: hostingState.appDir, new: false }),
    ]);
    hostingState.status = status;
    hostingState.check = check;
    hostingState.targets = targets;
    hostingState.keys = keys;
    stateEl.textContent = "";
    // Pre-select whatever this app is already planned for, so a returning visit shows its real
    // configuration rather than resetting to a default every time. Falls back to "shared by link"
    // (rung 1) only for an app that has never been planned -- the most common first choice.
    if (typeof check.rung === "number" && check.rung !== null) {
      hostingState.pickedRung = check.rung;
      hostingState.pickedTarget = check.target || null;
    } else if (hostingState.pickedRung === null) {
      hostingState.pickedRung = 1;
    }
  } catch (err) {
    stateEl.textContent = `Could not read this app's sharing state -- ${escapeHtml(String(err))}`;
    hostingState.status = null;
    hostingState.check = null;
    hostingState.targets = null;
  }
  renderHosting();
}

// ---------------------------------------------------------------------------------------------
// Artboard 1 -- the headline. Three sentences, chosen from `host_status` alone: whether the
// tunnel is actually running right now is the one fact that matters, independent of what rung an
// app happens to be planned for.
// ---------------------------------------------------------------------------------------------

function renderReach() {
  const el = document.getElementById("hosting-reach");
  if (!hostingState.appDir) {
    el.innerHTML = "";
    return;
  }
  const up = hostingState.status && hostingState.status.up;
  el.innerHTML = up
    ? `<p class="reach-line reach-live">Anyone with this link can reach your app.</p>`
    : `<p class="reach-line">Only you can reach this app.</p>`;
}

// ---------------------------------------------------------------------------------------------
// Artboard 4 -- the live state. The address is the largest element on the screen: it is what the
// user came for. "Stop sharing" sits beside it, never in a menu -- easy exposure without easy
// un-exposure is a trap. `state.routedApps` is the array `run_host_share` fixed to carry (name,
// slug, port), not a bare count -- see npdev_cli.py's `run_host_share` for why that mattered.
// ---------------------------------------------------------------------------------------------

const HOP_LABELS = ["Browser", "Tunnel", "Shared ingress", "This machine", "Your app"];

async function copyShareUrl(url) {
  const btn = document.getElementById("hosting-copy-btn");
  try {
    await navigator.clipboard.writeText(url);
    if (btn) {
      const original = btn.textContent;
      btn.textContent = "Copied";
      setTimeout(() => { btn.textContent = original; }, 1500);
    }
  } catch (err) {
    if (btn) btn.textContent = `Could not copy -- ${escapeHtml(String(err))}`;
  }
}

async function stopSharing() {
  if (!hostingState.appDir) return;
  const btn = document.getElementById("hosting-stop-btn");
  if (btn) { btn.disabled = true; btn.textContent = "Stopping…"; }
  try {
    await hInvoke("host_down", { appDir: hostingState.appDir });
    hostingState.status = await hInvoke("host_status", { appDir: hostingState.appDir });
  } catch (err) {
    document.getElementById("hosting-app-state").textContent = `Could not stop sharing -- ${escapeHtml(String(err))}`;
  }
  renderHosting();
}

function renderLive() {
  const el = document.getElementById("hosting-live");
  const up = hostingState.appDir && hostingState.status && hostingState.status.up;
  if (!up) {
    el.hidden = true;
    el.innerHTML = "";
    return;
  }
  el.hidden = false;
  const state = hostingState.status.state || {};
  const url = state.url || "";
  const keyCount = hostingState.keys && Array.isArray(hostingState.keys.keys) ? hostingState.keys.keys.length : null;
  const keyLine = keyCount === null
    ? ""
    : keyCount === 0
      ? `<p class="status-line key-warning">No API keys exist yet -- visitors cannot get in. <a href="#" class="keys-link">Mint one</a>.</p>`
      : `<p class="status-line">Visitors still need one of your ${keyCount} key${keyCount === 1 ? "" : "s"}. <a href="#" class="keys-link">Manage keys</a>.</p>`;
  const hops = HOP_LABELS.map((label) => `<span class="hop">${escapeHtml(label)}</span>`).join(`<span class="hop-arrow">&rarr;</span>`);
  const routed = Array.isArray(state.routedApps) ? state.routedApps : [];
  const routedRows = routed.length
    ? routed.map((a) => `
        <div class="routed-row">
          <span class="name">${escapeHtml(a.name)}</span>
          <span class="found">:${escapeHtml(String(a.port))} -- /${escapeHtml(a.slug)}/</span>
        </div>
      `).join("")
    : `<p class="status-line">No apps are currently routed.</p>`;

  el.innerHTML = `
    <p class="reach-url">${escapeHtml(url)}</p>
    <div class="live-actions">
      <button type="button" id="hosting-copy-btn">Copy link</button>
      <button type="button" id="hosting-open-btn">Open it</button>
      <button type="button" id="hosting-stop-btn" class="destructive">Stop sharing</button>
    </div>
    ${keyLine}
    <div class="hop-strip">${hops}</div>
    <h2>Routed apps</h2>
    <div class="routed-list">${routedRows}</div>
  `;
  document.getElementById("hosting-copy-btn").addEventListener("click", () => copyShareUrl(url));
  document.getElementById("hosting-open-btn").addEventListener("click", () => hInvoke("open_url", { url }));
  document.getElementById("hosting-stop-btn").addEventListener("click", stopSharing);
  const keysLink = el.querySelector(".keys-link");
  if (keysLink) keysLink.addEventListener("click", (ev) => { ev.preventDefault(); scrollToKeys(); });
}

// ---------------------------------------------------------------------------------------------
// M9 -- keys. Listed masked; `--new` mints one and its raw value comes back exactly once, in that
// one response. It is never re-displayed after this render cycle -- not stored, not logged, gone
// the moment the user picks another rung or reloads.
// ---------------------------------------------------------------------------------------------

function scrollToKeys() {
  const el = document.getElementById("hosting-detail");
  if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
}

async function mintHostKey() {
  if (!hostingState.appDir) return;
  const btn = document.getElementById("hosting-new-key-btn");
  if (btn) { btn.disabled = true; btn.textContent = "Minting…"; }
  try {
    const minted = await hInvoke("host_keys", { appDir: hostingState.appDir, new: true });
    hostingState.mintedKey = minted.key || null;
    hostingState.keys = await hInvoke("host_keys", { appDir: hostingState.appDir, new: false });
  } catch (err) {
    document.getElementById("hosting-app-state").textContent = `Could not mint a key -- ${escapeHtml(String(err))}`;
  }
  renderHosting();
}

function renderKeys() {
  const el = document.getElementById("hosting-detail");
  if (!hostingState.appDir) {
    el.innerHTML = "";
    return;
  }
  const keys = hostingState.keys && Array.isArray(hostingState.keys.keys) ? hostingState.keys.keys : [];
  const mintedBanner = hostingState.mintedKey
    ? `
      <div class="minted-key">
        <p><strong>New key:</strong> <code>${escapeHtml(hostingState.mintedKey)}</code></p>
        <p class="status-line">This is the only time it is shown. Copy it now -- NPDev cannot show it again.</p>
      </div>
    `
    : "";
  const rows = keys.length
    ? keys.map((k) => `
        <div class="check-row ok">
          <span class="mark">•</span>
          <span class="name">${escapeHtml(k.tenant)} / ${escapeHtml(k.actor)}</span>
          <span class="detail">${escapeHtml(k.role)}</span>
          <span class="found">${escapeHtml(k.masked)}</span>
        </div>
      `).join("")
    : `<p class="status-line">No keys yet.</p>`;
  el.innerHTML = `
    <h2>Keys</h2>
    ${mintedBanner}
    <div class="check-list">${rows}</div>
    <button type="button" id="hosting-new-key-btn">New key</button>
  `;
  document.getElementById("hosting-new-key-btn").addEventListener("click", mintHostKey);
}

// ---------------------------------------------------------------------------------------------
// Artboard 2, part 1 -- the ladder. Rung 0 is synthetic (private has no CLI target); rungs 1-4
// group `host_targets`'s own annotated list. `incompatibleReason` is rendered verbatim -- it is
// already a plain-language sentence from the CLI (_run_host_plan_list_targets), never recomputed
// here.
// ---------------------------------------------------------------------------------------------

const RUNG_LABELS = {
  0: "Private",
  1: "Shared by link",
  2: "Permanent address, your own box",
  3: "External free tier",
  4: "External paid host",
};

function pickRung(rung, targetId, provider) {
  hostingState.pickedRung = rung;
  hostingState.pickedTarget = rung >= 3 ? targetId : null;
  hostingState.pickedProvider = rung === 1 ? provider : null;
  renderHosting();
}
window.__hostingPickRung = pickRung;

function renderTargetCard(target) {
  const disabled = target.compatible === false;
  const picked = hostingState.pickedRung === target.rung &&
    (target.rung < 3 || hostingState.pickedTarget === target.id) &&
    (target.rung !== 1 || hostingState.pickedProvider === target.id);
  const reason = disabled
    ? `<p class="rung-reason">${escapeHtml(target.incompatibleReason)}</p>`
    : `<p class="rung-reason muted">${escapeHtml((target.caveats || [])[0] || target.fit || "")}</p>`;
  const provider = target.rung === 1 ? target.id : null;
  return `
    <button type="button" class="rung${picked ? " picked" : ""}${disabled ? " disabled" : ""}"
            ${disabled ? "disabled" : ""}
            data-rung="${target.rung}" data-target="${escapeHtml(target.id)}" data-provider="${escapeHtml(provider || "")}">
      <span class="rung-label">${escapeHtml(target.label)}</span>
      ${reason}
    </button>
  `;
}

function renderLadder() {
  const el = document.getElementById("hosting-ladder");
  if (!hostingState.appDir) {
    el.innerHTML = "";
    return;
  }
  if (!hostingState.targets || !hostingState.targets.ok) {
    el.innerHTML = `<p class="status-line">Could not load hosting targets.</p>`;
    return;
  }
  const byRung = {};
  for (const target of hostingState.targets.targets || []) {
    (byRung[target.rung] = byRung[target.rung] || []).push(target);
  }
  const privatePicked = hostingState.pickedRung === 0;
  const groups = [`
    <div class="rung-group">
      <h3>0 &middot; ${escapeHtml(RUNG_LABELS[0])}</h3>
      <button type="button" class="rung${privatePicked ? " picked" : ""}" data-rung="0" data-target="" data-provider="">
        <span class="rung-label">Nobody but you can reach it</span>
        <p class="rung-reason muted">The safest state -- nothing is exposed.</p>
      </button>
    </div>
  `];
  for (const rung of [1, 2, 3, 4]) {
    if (!byRung[rung]) continue;
    groups.push(`
      <div class="rung-group">
        <h3>${rung} &middot; ${escapeHtml(RUNG_LABELS[rung])}</h3>
        ${byRung[rung].map(renderTargetCard).join("")}
      </div>
    `);
  }
  el.innerHTML = `<div class="ladder">${groups.join("")}</div>`;
  el.querySelectorAll(".rung:not(.disabled)").forEach((btn) => {
    btn.addEventListener("click", () => {
      const rung = Number(btn.dataset.rung);
      pickRung(rung, btn.dataset.target || null, btn.dataset.provider || null);
    });
  });
}

// ---------------------------------------------------------------------------------------------
// Artboard 2, part 2 -- the check list. Findings map onto .check-row/.check-list from app.css
// unchanged: "ok"/"warn"/"fix" (the CLI's vocabulary) render as "pass"/"warn"/"fail" (the CSS's),
// and `finding.detail` is rendered verbatim -- it is already the plain-language consequence
// sentence, never paraphrased here.
// ---------------------------------------------------------------------------------------------

function hostMarkFor(status) {
  if (status === "ok") return "✓";
  if (status === "fix") return "✗";
  return "!";
}

function hostRowClass(status) {
  if (status === "ok") return "pass";
  if (status === "fix") return "fail";
  return "warn";
}

function renderChecks() {
  const el = document.getElementById("hosting-checks");
  if (!hostingState.appDir) {
    el.innerHTML = "";
    return;
  }
  const check = hostingState.check;
  if (!check || !Array.isArray(check.findings)) {
    // No plan yet (`host check` refuses with "run `npdev host plan` first" until a rung has been
    // picked and applied) -- the ladder above is exactly the next step, so point at it rather than
    // showing an error the user cannot act on.
    el.innerHTML = `<p class="status-line">Pick a way to share this app above to see its checks.</p>`;
    return;
  }
  const rows = check.findings.map((finding) => {
    const cls = hostRowClass(finding.status);
    // The one check row this screen points somewhere else: a key is minted from the Keys panel
    // (M9), not from a fix this row could apply itself.
    const keysBtn = finding.id === "auth-fail-closed"
      ? `<button type="button" class="keys-jump-btn">Manage keys</button>`
      : "";
    const fixNote = !keysBtn && finding.status === "fix" && finding.fix && !finding.fixable
      ? `<span class="found">${escapeHtml(finding.fix)}</span>`
      : "";
    return `
      <div class="check-row ${cls}">
        <span class="mark">${hostMarkFor(finding.status)}</span>
        <span class="name">${escapeHtml(finding.title)}</span>
        <span class="detail" title="${escapeHtml(finding.detail)}">${escapeHtml(finding.detail)}</span>
        ${keysBtn || fixNote}
      </div>
    `;
  });
  el.innerHTML = `<div class="check-list">${rows.join("")}</div>`;
  const jump = el.querySelector(".keys-jump-btn");
  if (jump) jump.addEventListener("click", scrollToKeys);
}

// ---------------------------------------------------------------------------------------------
// The primary action -- "Fix N things, then share". NEVER disabled: a blocked preflight is a step
// this button takes, not a wall it stops at. Label comes from `counts` alone (warnings never
// block, only `fix` findings do); pressing it fixes what can be fixed, re-renders from the
// response `check` already carries (no second `host check` call), then shares.
// ---------------------------------------------------------------------------------------------

function primaryLabel() {
  if (hostingState.busy) return "Working…";
  const counts = hostingState.check && hostingState.check.counts;
  const fixCount = counts ? counts.fix : 0;
  if (fixCount > 0) return `Fix ${fixCount} thing${fixCount === 1 ? "" : "s"}, then share`;
  return "Share by link";
}

// ---------------------------------------------------------------------------------------------
// Artboard 3 -- visible progress. `host_share_streaming` is fire-and-forget on the Rust side; this
// listener is the only place progress state lives. Steps "ingress"/"tunnel"/"confirm" tick
// together around the one blocking `host share` call (see run_host_share_streaming's own comment
// for why: the CLI has no incremental output for that call to report honestly).
// ---------------------------------------------------------------------------------------------

const PROGRESS_STEPS = [
  { id: "check", label: "Checking this app" },
  { id: "fix", label: "Fixing what can be fixed automatically" },
  { id: "ingress", label: "Refreshing the shared ingress" },
  { id: "tunnel", label: "Opening the tunnel" },
  { id: "confirm", label: "Confirming the address" },
];

function renderProgress() {
  const el = document.getElementById("hosting-progress");
  if (!hostingState.progress) {
    el.hidden = true;
    el.innerHTML = "";
    return;
  }
  el.hidden = false;
  el.innerHTML = PROGRESS_STEPS.map((step) => {
    const s = hostingState.progress[step.id] || { status: "pending" };
    const mark = { pending: "…", running: "…", done: "✓", skipped: "–", error: "✗" }[s.status] || "…";
    return `
      <div class="progress-step ${s.status}">
        <span class="mark">${mark}</span>
        <span class="name">${escapeHtml(step.label)}</span>
        ${s.detail ? `<span class="detail">${escapeHtml(s.detail)}</span>` : ""}
      </div>
    `;
  }).join("");
}

async function runPrimaryAction() {
  if (hostingState.busy || !hostingState.appDir) return;
  hostingState.busy = true;
  hostingState.progress = {};
  for (const step of PROGRESS_STEPS) hostingState.progress[step.id] = { status: "pending" };
  renderActions();
  renderProgress();

  const appDir = hostingState.appDir;
  const check = hostingState.check;
  const planned = check && typeof check.rung === "number";
  const needsPlan = !planned || check.rung !== hostingState.pickedRung ||
    (hostingState.pickedRung >= 3 && check.target !== hostingState.pickedTarget);

  const unlisten = await hListen("host-event", async (event) => {
    const payload = event.payload;
    if (payload.kind === "step") {
      hostingState.progress[payload.id] = { status: payload.status, detail: payload.detail };
      renderProgress();
      return;
    }
    if (payload.kind === "done") {
      unlisten();
      hostingState.busy = false;
      hostingState.progress = null;
      // The stream reports what it saw at the time; re-read status fresh rather than trusting a
      // `share` payload that may be null (blocked, or an error mid-sequence).
      try {
        hostingState.status = await hInvoke("host_status", { appDir });
      } catch (_err) {
        // leave whatever status was last known -- a status read failing here is not this action's
        // failure to report.
      }
      if (payload.check) hostingState.check = payload.check;
      renderHosting();
    }
  });

  try {
    await hInvoke("host_share_streaming", {
      appDir,
      rung: hostingState.pickedRung,
      target: hostingState.pickedRung >= 3 ? hostingState.pickedTarget : null,
      needsPlan,
      provider: hostingState.pickedProvider,
    });
  } catch (err) {
    unlisten();
    hostingState.busy = false;
    hostingState.progress = null;
    document.getElementById("hosting-app-state").textContent = `Could not share this app -- ${escapeHtml(String(err))}`;
    renderHosting();
  }
}

function renderActions() {
  const el = document.getElementById("hosting-actions");
  if (!hostingState.appDir || hostingState.pickedRung === null || hostingState.pickedRung >= 3) {
    // Rung >= 3 shares nothing from this button -- M10/M11 render their own deploy action instead.
    el.innerHTML = "";
    return;
  }
  el.innerHTML = `<button type="button" id="hosting-primary-btn" class="primary">${escapeHtml(primaryLabel())}</button>`;
  document.getElementById("hosting-primary-btn").addEventListener("click", runPrimaryAction);
}

// ---------------------------------------------------------------------------------------------
// Single render entry point. Each panel decides its own visibility from hostingState -- no panel
// toggles another panel.
// ---------------------------------------------------------------------------------------------

function renderHosting() {
  renderReach();
  renderLive();
  renderLadder();
  renderKeys();
  renderChecks();
  renderActions();
  renderProgress();
}

async function initHosting() {
  hostingState.mintedKey = null;
  await refreshHostingAppPicker();
  await loadHosting();
}

window.__npdevRefreshHosting = initHosting;

document.addEventListener("DOMContentLoaded", initHosting);
