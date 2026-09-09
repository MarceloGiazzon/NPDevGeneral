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
  pickedRung: null,
  busy: false,
};

function renderHosting() {
  // Single render entry point, called after every state change. Each panel decides its own
  // visibility from hostingState -- no panel toggles another panel.
}

async function initHosting() {
  // M1: renders nothing yet.
}

document.addEventListener("DOMContentLoaded", initHosting);
