/**
 * Render the Manager's two newest screens in a real browser, against the CAPTURED fixtures, and
 * assert what they actually draw. MONITOR_PLAN B6/G1.
 *
 * WHY THIS EXISTS
 * ---------------
 * `--selftest` proves the Tauri commands; the CLI's own tests prove the answers. Until this file,
 * NOTHING had ever executed `monitor.js` or `scrap.js` -- the layer a user actually looks at was the
 * only layer with no coverage at all. That is the shape this project keeps finding one level up
 * (Tier B green while no app could boot; the dialect answering correctly while nothing asked).
 *
 * It renders the REAL fixtures, so it asserts against what the CLI actually returns rather than a
 * shape somebody invented -- and it catches what a headless assertion cannot: the first run showed
 * five cards squeezed into a 470px column (app.css's `max-width: 760px`, right for a form and wrong
 * for a wall), and the filmstrip printing Playwright's raw ANSI codes as `[2m` noise through the
 * middle of the failure message.
 *
 * USAGE
 * -----
 *     node scripts/quality/check-manager-ui-render.mjs [output-dir]
 *
 * Needs Playwright, which is NOT a dependency of this repo. It is resolved from the machine, in
 * order: $PLAYWRIGHT_MODULE, the editor's node_modules, then the ScrapForAI engine root that
 * `npdev monitor engine --json` reports. If none is present it SKIPS with a reason and exits 0 --
 * a check that cannot run must say so rather than fail a gate for a missing optional tool, and must
 * never pass silently.
 */
import { createServer } from "node:http";
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\//, "")), "..", "..");

/** Playwright, found on THIS machine -- never a path literal (REG-144). */
function resolvePlaywright() {
  const candidates = [];
  if (process.env.PLAYWRIGHT_MODULE) candidates.push(process.env.PLAYWRIGHT_MODULE);
  try {
    const answer = execFileSync(process.env.PYTHON ?? "python",
      [path.join(REPO, "NPDevCli", "npdev_cli.py"), "monitor", "engine", "--json"],
      { encoding: "utf8" });
    const engine = JSON.parse(answer);
    if (engine.root) candidates.push(path.join(engine.root, "node_modules", "playwright", "index.mjs"));
  } catch { /* no engine is a normal state, not an error */ }
  return candidates.find((c) => existsSync(c)) ?? null;
}

const playwrightModule = resolvePlaywright();
if (!playwrightModule) {
  console.log("SKIPPED -- Playwright is not available on this machine.");
  console.log("  Tried $PLAYWRIGHT_MODULE and the engine root");
  console.log("  reported by `npdev monitor engine --json`. Install one of them to run this check.");
  process.exit(0);
}
const { chromium } = await import(pathToFileURL(playwrightModule).href);


const UI = path.join(REPO, "NPDevManager", "ui");
const FIX = path.join(REPO, "NPDevManager", "fixtures");
// Screenshots and the JSON report are build output: never the current directory (that is the repo root when run from it).
const OUT = process.argv[2] || path.join(tmpdir(), "npdev-manager-ui-render");
mkdirSync(OUT, { recursive: true });

// Gallery order, same as src/themes.rs BUILT_IN (a Rust test pins that list).
const BUILTIN_ORDER = ["npdev-dark", "npdev-light", "retro", "synth", "farm", "warm", "windows", "high-contrast"];
const season = (id) => readFileSync(path.join(REPO, "NPDevSamples", "themes", "seasons", id + ".json"), "utf8");

const fixture = (name) => JSON.parse(readFileSync(path.join(FIX, name), "utf8"));

const SHIM = `
window.__TAURI__ = {
  core: {
    convertFileSrc: (p) => "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    invoke: async (name, args) => {
      const F = window.__FIXTURES__;
      switch (name) {
        case "is_fake_mode": return true;
        case "fake_doctor_scenarios": return Object.keys(F.doctors);
        case "set_fake_doctor_scenario": window.__scenario = args.name; return null;
        case "check_doctor":
          if (window.__doctorBroken) throw "python was not found";
          return F.doctors[window.__scenario || "doctor-all-green"];
        case "last_failed_run": return window.__lastFailed || null;
        // Only the Home block turns the providers fixture on: the AI providers screen drawing it exposes provider
        // names and a key tooltip that are not in the pt catalogue yet (a known gap, not Home's).
        case "providers_list": return window.__providersBroken ? Promise.reject("python was not found") : F.providers;
        // The browser-AI launch (build, start, wait): every step is logged in order so a check can read the sequence.
        case "start_app": window.__started = args.appDir; (window.__launchLog = window.__launchLog || []).push("start:" + args.appDir); if (window.__startError) throw window.__startError; return null;
        case "stop_app": window.__stopped = args.appDir; (window.__launchLog = window.__launchLog || []).push("stop:" + args.appDir); return { ok: true, how: "stub" };
        case "generate_app_from_model": (window.__launchLog = window.__launchLog || []).push("generate:" + args.appDir); return null;
        case "run_ops_script":
          (window.__launchLog = window.__launchLog || []).push("run:" + args.script + ":" + args.appDir);
          // Like the real one: the call returns at once and the build's end arrives later as an ops-event. Only the
          // browser-AI block turns this on, so the Monitor's own checks are not handed events they never asked for.
          if (window.__fireOpsDone) setTimeout(() => (window.__listeners["ops-event"] || []).forEach((f) => f({ payload: { kind: "done", script: args.script, exitCode: window.__buildExit || 0, logFile: "C:/apps/logs/ops-build.log" } })), 30);
          return null;
        case "relay_generated_app_dir": return args.modelDir + "-app";
        case "studio_history_list": return [];
        case "jobs_list": window.__jobsListCalls = (window.__jobsListCalls || 0) + 1; return window.__jobsEmpty ? { ok: true, jobs: [], summary: {} } : F.jobs;
        case "jobs_action": window.__jobsActions = (window.__jobsActions || []).concat([args.action + ":" + args.jobId]); return { ok: true };
        case "recent_operations": return [{ operation: "install-java", intent: "install Java (JDK 17)", label: { en: "Install Java", pt: "Instalar o Java" }, startedAt: "2026-11-03T10:00:00Z", endedAt: "2026-11-03T10:01:00Z", running: false, ok: false }];
        case "list_apps": return [{ name: "auxscreen", directory: "D:\\\\WorkSpace\\\\NPDev\\\\Build\\\\generated-finalapps\\\\auxscreen", created_at: "2026-11-03" }];
        case "list_engines": return { engines: window.__engines || [] };
        // The New-app database card (dbsetup.js). Each answer is overridable per case by the checks below.
        case "detect_databases": return window.__detect ? JSON.parse(JSON.stringify(window.__detect)) : {};
        case "provision_database": window.__provisionCalls = (window.__provisionCalls || []).concat([args]); return window.__provisionAnswer || F.dbProvision;
        case "start_db_container": window.__startedContainer = args.name; return args.name;
        case "install_docker_desktop": window.__installCalls = (window.__installCalls || 0) + 1; if (window.__installError) throw window.__installError; return "opened";
        case "start_docker_desktop": window.__dockerStarted = (window.__dockerStarted || 0) + 1; return "Docker Desktop.exe";
        case "wsl_status": return window.__wsl;
        case "install_wsl": window.__wslInstallCalls = (window.__wslInstallCalls || 0) + 1; if (window.__wslError) throw window.__wslError; return "opened";
        case "create_app": window.__createdApp = args; return { created: { directory: "D:/Apps/" + args.name + "/" + args.name } };
        case "manager_build_stamp": return "v0.0.0";
        // The shape the real jdk_status command returns (main.rs). Default is "no Java anywhere" (the first-run
        // screen several checks below expect). The old installed/path mock carried no resolved field, so it only
        // looked like "Java present" and nothing ever exercised the ready path; window.__jdk overrides it.
        case "jdk_status": return window.__jdk || { portableInstalled: false, systemJava: null, systemJavaVersion: null, resolved: "none", path: null };
        case "python_status": return { systemPython: "python" };
        case "list_tags": return window.__tags || [];
        case "install_npdev_version": window.__installedTag = args.tag; window.__npdevVersion = args.tag; return null;
        case "run_setup": window.__setupRuns = (window.__setupRuns || 0) + 1; window.__setupDone = true; return { jarsSource: "download" };
        case "setup_status": return { currentVersion: window.__npdevVersion || null, jarsStaged: !!window.__setupDone, aiIndexPresent: !!window.__setupDone, jarCount: 3, libsDir: "x" };
        case "list_installed_versions": return [];
        case "current_version": return null;
        case "get_inspect_paths": return ["D:\\\\WorkSpace\\\\NPDev\\\\Build"];
        case "set_inspect_paths": return null;
        case "monitor_scan": return window.__scanEmpty ? F.scanEmpty : F.scan;
        // __probeQueue: the health the next probes report (the last one repeats) -- how a check scripts "still stopped, then up".
        case "monitor_probe": case "read_info_json": {
          if (name === "monitor_probe" && window.__probeQueue) {
            const h = window.__probeQueue.length > 1 ? window.__probeQueue.shift() : window.__probeQueue[0];
            return Object.assign({}, F.probe, { health: h, healthDetail: h === "running" ? null : "nothing is listening on 127.0.0.1:8411" });
          }
          return F.probe;
        }
        case "monitor_logs": return F.logs;
        case "engine_status": return Object.assign({}, F.engineRunning, { startedByThisWindow: false });
        case "explore_list": return F.list;
        case "explore_show": return F.runRed;
        case "explore_preflight": return F.preflight;
        case "explore_run": await new Promise((r) => setTimeout(r, 2500)); return { runId: "x", verdict: { green: true } };
        case "explore_validate": return F.validateBad;
        case "assistant_config": return { configured: false };
        case "install_jdk": throw "download failed: GROQ_API_KEY=gsk_abcdefghijklmnopqrstuvwx was rejected by the proxy";
        case "support_report": return F.support;
        case "theme_list": return F.themes.concat(window.__userThemes || []);
        case "appearance_get":
          if (sessionStorage.getItem("hang-appearance")) return new Promise(() => {});
          return window.__appearance || (window.__appearance = { theme_id: "npdev-dark", mode: "system",
            a11y: { text_scale: 1, motion: "system", focus: "auto", spacing: "normal", links: "auto", cvd: "off" } });
        case "appearance_set": window.__appearance = args.appearance; return args.appearance;
        case "theme_import": {
          const th = JSON.parse(args.jsonText);
          window.__userThemes = (window.__userThemes || []).filter((x) => x.id !== th.id).concat([Object.assign({ builtin: false }, th)]);
          window.__imported = th.id;
          return { id: th.id, name: th.name };
        }
        case "theme_catalogue_index": {
          const have = F.themes.concat(window.__userThemes || []).map((x) => x.id);
          const demo = Object.assign({}, F.catalogue.index.themes.find((x) => x.id === "winter"), { id: "tampered-demo", name: "Tampered (stub demo)", names: {} });
          return { repo: "MarceloGiazzon/npdev-themes", stub: true,
            themes: F.catalogue.index.themes.concat([demo]).map((x) => Object.assign({}, x, { installed: have.includes(x.id) })) };
        }
        case "theme_catalogue_fetch":
          if (args.id === "tampered-demo") throw 'the file for tampered-demo does not match the catalogue sha256 (expected 9b0f23349e3b, got 0123456789ab); it was NOT imported';
          return F.catalogue.files[args.id];
        case "theme_share_url": return "https://github.com/MarceloGiazzon/npdev-themes/new/main/themes?filename=" + JSON.parse(args.jsonText).id + ".json&value=x";
        case "open_url": window.__opened = args.url; return null;
        case "theme_delete": window.__userThemes = (window.__userThemes || []).filter((x) => x.id !== args.id); return { removed: true };
        case "manager_log_path": return "C:\\\\x\\\\manager.log";
        // Use a browser AI (the relay_* commands): the CAPTURED answers of the real CLI. A marker in the pasted
        // text picks which one, exactly as NPDev's own stub mode does (npdev.rs relay_ingest_stub).
        case "relay_start": window.__relayStarts = (window.__relayStarts || []).concat([args]); return F.relay.prompt;
        case "relay_ingest_text": {
          window.__relayIngests = (window.__relayIngests || []).concat([args]);
          const t = args.text || "";
          return t.includes("STUB-TRUNCATED") ? F.relay.truncated : t.includes("STUB-NEEDS-FIX") ? F.relay.needsFix
            : t.includes("STUB-REMOVAL") ? F.relay.removal : t.includes("STUB-CREATE") ? F.relay.create
            : t.includes("STUB-READY") ? F.relay.change : F.relay.parsed;
        }
        // A file the AI made, dropped on the window or picked: the stub answers by the file's NAME (a marker in it), the
        // way relay_ingest_text answers by a marker in the text -- so the same captured answers serve every route in.
        case "relay_ingest_file": {
          window.__relayFileIngests = (window.__relayFileIngests || []).concat([args]);
          const n = (args.files || []).join("|");
          return n.includes("STUB-TRUNCATED") ? F.relay.truncated : n.includes("STUB-NEEDS-FIX") ? F.relay.needsFix
            : n.includes("STUB-REMOVAL") ? F.relay.removal : n.includes("STUB-CREATE") ? F.relay.create : F.relay.parsed;
        }
        case "relay_pick_answer_files": window.__pickCalls = (window.__pickCalls || 0) + 1; return window.__pickAnswer || [];
        case "relay_apply": window.__relayApplies = (window.__relayApplies || []).concat([args]); return window.__applyAnswer || F.relay.apply;
        case "relay_undo": window.__relayUndos = (window.__relayUndos || []).concat([args]); return window.__undoAnswer || F.relay.undo;
        case "relay_abort": window.__relayAborts = (window.__relayAborts || []).concat([args]); return F.relay.abort;
        default: return {};
      }
    },
  },
  // The window's own events (tauri://drag-drop ...): kept so a check can fire them the way Tauri would.
  event: { listen: async (name, cb) => { (window.__listeners = window.__listeners || {})[name] = (window.__listeners[name] || []).concat([cb]); return () => {}; } },
};
`;

const server = createServer((req, res) => {
  const name = req.url === "/" ? "/index.html" : req.url.split("?")[0];
  const file = path.join(UI, name);
  if (!existsSync(file)) { res.writeHead(404); res.end("no"); return; }
  let body = readFileSync(file, "utf8");
  if (name === "/index.html") {
    body = body.replace("<script src=\"app.js\">", `<script>${SHIM}</script>\n  <script src="app.js">`);
  }
  res.writeHead(200, { "content-type": name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "text/javascript" : "text/html" });
  res.end(body);
});

await new Promise((r) => server.listen(4599, "127.0.0.1", r));

const browser = await chromium.launch();
// A fixed locale: the Manager picks its language from the browser, and the check must not depend on the machine it runs on.
const page = await browser.newPage({ viewport: { width: 1400, height: 1000 }, locale: "en-US" });

const consoleErrors = [];
// A browser-level "connection refused" is the Manager probing a stub app's localhost port that nothing listens on;
// it is not a script error, so it is not counted.
page.on("console", (m) => m.type() === "error" && !/net::ERR_CONNECTION_REFUSED/.test(m.text()) && consoleErrors.push(m.text()));
page.on("pageerror", (e) => consoleErrors.push("pageerror: " + e.message));
if (process.env.NPDEV_RENDER_DEBUG) { page.on("console", (m) => console.log("[page]", m.type(), m.text())); page.on("pageerror", (e) => console.log("[pageerror]", e.message)); }

await page.addInitScript({
  content: `window.__FIXTURES__ = ${JSON.stringify({
    scan: fixture("monitor-scan-mixed.json"),
    probe: fixture("monitor-probe.json"),
    logs: fixture("monitor-logs.json"),
    engineRunning: fixture("monitor-engine-running.json"),
    list: fixture("explore-list.json"),
    runRed: fixture("explore-run-red.json"),
    preflight: fixture("explore-preflight.json"),
    validateBad: fixture("explore-validate-bad.json"),
    doctor: fixture("doctor-all-green.json"),
    doctors: Object.fromEntries(["doctor-all-green", "doctor-missing-java", "doctor-wrong-java", "doctor-no-jars", "doctor-acceptable-newer-java"]
      .map((n) => [n, fixture(n + ".json")])),
    scanEmpty: fixture("monitor-scan-empty.json"),
    jobs: fixture("jobs-list.json"),
    providers: fixture("ai-providers.json"),
    support: fixture("support-install-failed.json"),
    relay: {
      prompt: fixture("relay-prompt.json"), parsed: fixture("relay-ingest-parsed.json"), truncated: fixture("relay-ingest-truncated.json"),
      needsFix: fixture("relay-ingest-needs-fix.json"), create: fixture("relay-ingest-ready-create.json"),
      change: fixture("relay-ingest-ready-change.json"), removal: fixture("relay-ingest-ready-removal.json"),
      apply: fixture("relay-apply.json"), undo: fixture("relay-undo.json"), abort: fixture("relay-abort.json"),
    },
    dbProvision: fixture("db-provision.json"),
    catalogue: (() => {
      const dir = path.join(REPO, "NPDevManager", "fixtures", "theme-catalogue");
      const index = JSON.parse(readFileSync(path.join(dir, "index.json"), "utf8"));
      return { index, files: Object.fromEntries(index.themes.map((t) => [t.id, readFileSync(path.join(dir, t.id + ".json"), "utf8")])) };
    })(),
    themes: BUILTIN_ORDER.map((id) => Object.assign({ builtin: true }, JSON.parse(readFileSync(path.join(REPO, "NPDevContract", "themes", id + ".json"), "utf8")))),
  })};`,
});

await page.goto("http://127.0.0.1:4599/");
await page.waitForTimeout(500);
// The stub machine is "all green", so Set up has already disappeared (NAV-4). Most checks below drive the
// Set up place, so start them as a fresh profile would: not ready. The NAV-4 block restores the real flow.
await page.evaluate(() => document.dispatchEvent(new CustomEvent("npdev:doctor-result", { detail: { ready: false } })));
await page.waitForTimeout(200);

const results = [];
const check = (name, ok, detail) => { results.push({ name, ok, detail }); };

// ---- Accessibility measurement (ACC-4) ---------------------------------------------------------
// The vendored axe-core build the golden browser routines use (NPDevSamples/scripts/browser/vendor/axe-core)
// is injected on the Manager's key screens and the per-impact violation NODE counts are compared with the
// floor in scripts/policy/manager-a11y-baseline.json. Same ratchet shape as scripts/policy/a11y-baseline.json:
// the floor may only FALL; a higher count fails; NPDEV_A11Y_WRITE=1 records a lower (or first) measurement.
const AXE_SRC = readFileSync(path.join(REPO, "NPDevSamples", "scripts", "browser", "vendor", "axe-core", "axe.min.js"), "utf8");
const A11Y_BASELINE_PATH = path.join(REPO, "scripts", "policy", "manager-a11y-baseline.json");
const a11yBaseline = existsSync(A11Y_BASELINE_PATH)
  ? JSON.parse(readFileSync(A11Y_BASELINE_PATH, "utf8"))
  : { schema: "npdev-manager-a11y-baseline.v1", screens: {} };
const a11yMeasured = {};
async function axeScan(screenId) {
  await page.waitForTimeout(350); // let transitions (toasts, panels) settle: axe would measure a half-faded element
  if (!(await page.evaluate(() => typeof window.axe !== "undefined"))) await page.addScriptTag({ content: AXE_SRC });
  const out = await page.evaluate(async () => {
    const r = await window.axe.run(document, { runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "best-practice"] } });
    const counts = { critical: 0, serious: 0, moderate: 0, minor: 0 };
    const rules = {};
    const targets = {};
    for (const v of r.violations) {
      counts[v.impact] = (counts[v.impact] || 0) + v.nodes.length;
      rules[v.id] = (rules[v.id] || 0) + v.nodes.length;
      targets[v.id] = v.nodes.slice(0, 4).map((n) => n.target.join(" ") + " :: " + (n.any[0] && n.any[0].message || n.failureSummary || "").slice(0, 160));
    }
    return { counts, rules, targets };
  });
  a11yMeasured[screenId] = out;
  return out;
}

// ---- Navigation (NAV-1): four places from navigation.json, the language follows the picker ------
const places = await page.$$eval(".tabs .place", (e) => e.map((b) => b.dataset.place));
check("the main bar renders Home and the four places", places.join(",") === "home,setup,apps,activity,settings", places.join(","));
const startPlace = await page.$eval(".tabs .place.active", (b) => b.dataset.place).catch(() => null);
check("the Manager opens on Home", startPlace === "home", `${startPlace}`);
// ---- Home (NAV-2): each doctor scenario, and the other decisions, show the right card ----------------------
const homeCard = async (setup) => {
  await page.evaluate(setup);
  await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
  await page.waitForFunction(() => document.querySelector("#home-root .home-card"), null, { timeout: 3000 });
  return page.evaluate(() => ({ when: document.querySelector(".home-card").dataset.when, title: document.querySelector(".home-title").textContent,
    button: document.querySelector(".home-action")?.textContent, strip: document.querySelector(".home-strip").textContent }));
};
let card = await homeCard(() => { window.__scenario = "doctor-all-green"; window.__scanEmpty = false; window.__lastFailed = null; });
check("healthy computer with a running app: Home offers to open it", card.when === "appRunning" && /npdev-canary is running at http:\/\/localhost:8411/.test(card.title) && card.button === "Open it", JSON.stringify(card));
check("the status strip summarises the whole picture", /Computer ready/.test(card.strip) && /5 apps/.test(card.strip) && /1 running/.test(card.strip) && /last problem: none/.test(card.strip), card.strip);
for (const scenario of ["doctor-missing-java", "doctor-wrong-java", "doctor-no-jars"]) {
  await page.evaluate((sc) => { window.__scenario = sc; window.__scanEmpty = false; window.__lastFailed = null; }, scenario);
  await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
  await page.waitForTimeout(150);
  const c = await page.evaluate(() => ({ when: document.querySelector(".home-card").dataset.when, title: document.querySelector(".home-title").textContent, button: document.querySelector(".home-action").textContent }));
  check(`scenario ${scenario}: Home says what is missing and offers to install it`, c.when === "doctorHasFailures" && /things are missing/.test(c.title) && c.button === "Install them", JSON.stringify(c));
}
await page.evaluate(() => { window.__scenario = "doctor-acceptable-newer-java"; });
await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
check("scenario doctor-acceptable-newer-java is not a failure", (await page.$eval(".home-card", (c) => c.dataset.when)) === "appRunning", "");
await page.evaluate(() => { window.__scenario = "doctor-all-green"; window.__scanEmpty = true; });
await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
const noApps = await page.evaluate(() => ({ when: document.querySelector(".home-card").dataset.when, button: document.querySelector(".home-action").textContent }));
check("no apps yet: Home offers to create the first one", noApps.when === "noApps" && noApps.button === "Create an app", JSON.stringify(noApps));
await page.click(".home-action");
await page.waitForTimeout(200);
check("that button opens the Apps screen with the name field focused",
  (await page.evaluate(() => window.__npdevCurrentScreen)) === "apps" && (await page.evaluate(() => document.activeElement && document.activeElement.id)) === "new-app-name", "");
await page.evaluate(() => { window.__scanEmpty = false; window.__lastFailed = { operation: "install-java", intent: "install Java (JDK 17), the runtime generated apps run on", endedAt: "2026-11-03T10:00:00Z" }; });
await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
const failedCard = await page.evaluate(() => ({ when: document.querySelector(".home-card").dataset.when, title: document.querySelector(".home-title").textContent, button: document.querySelector(".home-action").textContent }));
check("a failed operation: Home names it and offers the help dialog", failedCard.when === "lastOperationFailed" && /install Java/.test(failedCard.title) && failedCard.button === "Get help with this error", JSON.stringify(failedCard));
await page.click(".home-action");
await page.waitForFunction(() => document.getElementById("support-dialog")?.open, null, { timeout: 3000 });
check("...and that button opens the support dialog", true, "");
await page.keyboard.press("Escape");
await page.evaluate(() => { window.__lastFailed = null; window.__doctorBroken = true; });
await page.evaluate(async () => { NPDevHome.reset(true); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
check("a computer that was never checked: Home asks to check it", (await page.$eval(".home-card", (c) => c.dataset.when)) === "doctorNeverRun", "");
await page.evaluate(() => { window.__doctorBroken = false; window.__npdevDoctorRan([]); });
await page.selectOption("#lang-picker", "pt");
await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
check("Home speaks Portuguese when asked", /está rodando/.test(await page.textContent(".home-title")) && (await page.textContent(".home-action")) === "Abrir", await page.textContent(".home-title"));
await page.screenshot({ path: path.join(OUT, "home-pt.png"), fullPage: true });
await page.selectOption("#lang-picker", "en");
await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
await page.screenshot({ path: path.join(OUT, "home.png"), fullPage: true });
// ---- Home (HOME-1): tiles, your apps, recent activity, shortcuts ---------------------------------------------
const homeView = await page.evaluate(() => ({
  tiles: [...document.querySelectorAll(".home-tile")].map((x) => [x.dataset.tile, x.dataset.tone, x.querySelector(".home-tile-value").textContent, x.textContent]),
  apps: [...document.querySelectorAll(".home-app")].map((x) => [x.dataset.state, x.querySelector(".home-app-name").textContent]),
  ops: [...document.querySelectorAll(".home-op")].map((x) => x.dataset.state),
  shortcuts: document.querySelectorAll(".home-shortcut").length,
}));
check("Home draws four status tiles: computer ready, 5 apps, 1 running, AI keys known",
  JSON.stringify(homeView.tiles.map((x) => x[0])) === '["computer","apps","running","ai"]' && homeView.tiles[0][1] === "positive" && homeView.tiles[1][2] === "5" && homeView.tiles[2][2] === "1"
  && /2 ready/.test(homeView.tiles[3][2]), JSON.stringify(homeView.tiles));
check("Home: one app with a problem reads in the singular", /1 needs attention/.test(homeView.tiles[2][3]), homeView.tiles[2][3]);
check("Your apps lists every app, the running one first, and tells duplicate names apart by folder",
  homeView.apps.length === 5 && homeView.apps[0][0] === "running" && homeView.apps.some(([st, name]) => st === "problem" && /npdev-canary · /.test(name)), JSON.stringify(homeView.apps));
check("Recent activity shows what this window did, with the failure marked", JSON.stringify(homeView.ops) === '["failed"]', JSON.stringify(homeView.ops));
check("Home offers six shortcuts", homeView.shortcuts === 6, String(homeView.shortcuts));
await page.evaluate(() => [...document.querySelectorAll(".home-app")].find((r) => r.dataset.state === "stopped" && !r.querySelector("button").disabled).querySelector("button").click());
await page.waitForTimeout(150);
check("Start on a built, stopped app starts THAT app and shows it as starting",
  /engine-proof/.test(await page.evaluate(() => window.__started || "")) && (await page.$$eval('.home-pill[data-state="starting"]', (x) => x.length)) === 1, await page.evaluate(() => window.__started || "none"));
check("Start is disabled for an app that was never built", await page.evaluate(() =>
  [...document.querySelectorAll(".home-app")].filter((r) => /wordlab|auxscreen/.test(r.textContent)).every((r) => r.querySelector(".home-app-actions button").disabled)), "");
await page.click('.home-tile[data-tile="ai"]');
await page.waitForTimeout(150);
check("the AI tile opens AI providers", (await page.evaluate(() => window.__npdevCurrentScreen)) === "providers", "");
await page.evaluate(async () => { window.__providersBroken = true; NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
check("an AI tile that could not ask says so instead of claiming zero", (await page.$eval('.home-tile[data-tile="ai"]', (x) => x.dataset.tone)) === "muted", "");
await page.evaluate(async () => { window.__providersBroken = false; window.__scanEmpty = true; NPDevHome.reset(); await NPDevHome.refresh(); });
check("with no apps, Your apps offers to create one", (await page.$$eval(".home-empty button.primary", (x) => x.length)) === 1, "");
await page.screenshot({ path: path.join(OUT, "home-empty.png"), fullPage: true });
await page.setViewportSize({ width: 760, height: 1000 });
await page.evaluate(async () => { window.__scanEmpty = false; NPDevHome.reset(); await NPDevHome.refresh(); });
check("Home has no horizontal scroll in a narrow window", await page.evaluate(() => document.querySelector("main").scrollWidth <= document.querySelector("main").clientWidth + 1), "");
await page.screenshot({ path: path.join(OUT, "home-narrow.png"), fullPage: true });
await page.setViewportSize({ width: 1400, height: 1000 });

await page.click('.tabs .place[data-place="settings"]');
await page.waitForTimeout(200);
const settingsScreen = await page.$eval("main > section.screen:not([hidden])", (s) => s.id);
check("opening a place shows its first screen", settingsScreen === "screen-providers", settingsScreen);
await page.selectOption("#lang-picker", "pt");
await page.waitForTimeout(200);
const ptLabel = await page.textContent('.tabs .place[data-place="apps"]');
const ptLang = await page.$eval("html", (h) => h.lang);
check("choosing Portugues relabels the navigation", /Meus apps/.test(ptLabel) && ptLang === "pt", `${ptLabel.trim()} / lang=${ptLang}`);
await page.selectOption("#lang-picker", "en");
await page.waitForTimeout(200);
await page.screenshot({ path: path.join(OUT, "navigation-settings.png"), fullPage: false });

await page.click('.tabs .place[data-place="setup"]');
await page.waitForTimeout(150);
await axeScan("ready");
// ACC-2 / ACC-3: a status row is a glyph AND a word, and the progress and result regions are live.
const rowStats = await page.$$eval("#doctor-checks .check-row, .check-row", (rows) => ({
  rows: rows.length,
  withWord: rows.filter((r) => (r.querySelector(".mark .np-sr-only")?.textContent || "").trim().length > 0).length,
}));
check("every Ready row carries a status word as well as a glyph", rowStats.rows > 0 && rowStats.rows === rowStats.withWord, JSON.stringify(rowStats));
const liveIds = await page.$$eval("#jdk-status, #python-status, #setup-status, #setup-announce, #new-app-status", (e) => e.map((x) => x.getAttribute("aria-live")));
check("install, setup and create-app results are polite live regions", liveIds.length === 5 && liveIds.every((v) => v === "polite"), liveIds.join(","));

// ---- The Monitor ---------------------------------------------------------------------------
// Activity has two screens (Running apps, Tasks): open the place, then the Monitor's tab in its strip.
await page.click('.tabs .place[data-place="activity"]');
await page.click('.place-strip:not([hidden]) .tab[data-screen="monitor"]');
await page.waitForSelector("#monitor-grid .crt", { timeout: 5000 });

const cards = await page.$$eval("#monitor-grid .crt", (els) =>
  els.map((e) => ({ status: e.dataset.status, name: e.querySelector(".app")?.textContent }))
);
check("Monitor renders one card per scanned app", cards.length === 5, `${cards.length} cards`);
check("running state is drawn", cards.some((c) => c.status === "running"), JSON.stringify(cards.map(c => c.status)));
check("port-conflict is its OWN state, not running", cards.some((c) => c.status === "port-conflict"), "");
check("stopped state is drawn", cards.filter((c) => c.status === "stopped").length === 3, "");

const counts = await page.textContent("#monitor-counts");
check("counts line summarises the wall", /running/.test(counts) && /stopped/.test(counts), counts.trim());

await page.click('#monitor-view button[data-v="list"]');
check("list view toggles", await page.$eval("#monitor-grid", (e) => e.className.includes("list")), "");
await page.click('#monitor-view button[data-v="grid"]');

await page.click('#monitor-filters button[data-f="attention"]');
await page.waitForTimeout(150);
const attention = await page.$$eval("#monitor-grid .crt", (e) => e.length);
check("Attention filter shows only the problem cards", attention === 1, `${attention} card(s)`);
await page.click('#monitor-filters button[data-f="all"]');

// Inspector
await page.click("#monitor-grid .crt .screen");
await page.waitForSelector("#monitor-inspector.open .acc", { timeout: 5000 });
const sections = await page.$$eval("#insp-body .acc > button.hd", (els) => els.map((e) => e.textContent.trim()));
check("inspector renders the app's generated info.json sections",
  sections.some((s) => /URLS/i.test(s)) && sections.some((s) => /CONCEPTS/i.test(s)), sections.join(" | "));
check("inspector renders the PROBED section separately",
  sections.some((s) => /THIS MACHINE/i.test(s)), sections.join(" | "));
check("inspector offers the runbook", sections.some((s) => /RUNBOOK/i.test(s)), "");
const probedMarked = await page.$$eval(".irow.probed", (e) => e.length);
check("probed rows are visually distinguished from generated ones", probedMarked > 5, `${probedMarked} rows`);

// A grid track's default minimum is `auto` -- as wide as the longest UNBREAKABLE content -- and
// `overflow-wrap` does not change track sizing. So a long jar path can widen the row past the panel
// and push the Copy button off-screen, where it cannot be clicked. A DOM-only assertion cannot see
// that; measured geometry can.
//
// Wait for the slide-in to SETTLE first. The panel animates `translateX(103%) -> 0` over 0.28s, and
// measuring during it reports the panel's CLOSED position -- which is off-screen by construction and
// looks exactly like the bug. (It did, the first time: 2039 vs a 1400 viewport, which is precisely
// 1400 + 103% of 620.)
await page.waitForFunction(() => {
  const panel = document.getElementById("monitor-inspector");
  return Math.abs(panel.getBoundingClientRect().right - window.innerWidth) < 2;
}, { timeout: 3000 });
const overflow = await page.evaluate(() => {
  const panel = document.getElementById("monitor-inspector").getBoundingClientRect();
  const worst = [...document.querySelectorAll("#insp-body .irow .ops")]
    .map((el) => el.getBoundingClientRect().right)
    .reduce((a, b) => Math.max(a, b), 0);
  return { panelRight: Math.round(panel.right), worstRight: Math.round(worst),
           viewport: window.innerWidth };
});
check("inspector rows stay inside the panel (the Copy button is reachable)",
  overflow.worstRight <= overflow.panelRight + 1 && overflow.panelRight <= overflow.viewport + 1,
  `row right ${overflow.worstRight} vs panel right ${overflow.panelRight}, viewport ${overflow.viewport}`);

await page.screenshot({ path: path.join(OUT, "monitor-inspector.png"), fullPage: false });

// Logs: once a tab of the inspector, now one of its accordion sections, so the button is in the
// panel whether or not the section is expanded.
await page.waitForSelector("#logs-export", { state: "attached", timeout: 5000 });
check("logs section offers Export support bundle", true, "");
await page.click("#insp-close");
await page.waitForTimeout(500);

await page.screenshot({ path: path.join(OUT, "monitor-wall.png"), fullPage: false });
// A not-generated card: its GENERATE & RUN button must sit below the "NOT GENERATED YET" message, never on it.
const ngBoxes = await page.evaluate(() => {
  // The fixture has no not-generated app, so draw one with the real template and measure it in the live Monitor.
  const saved = monitorState.notGenerated;
  monitorState.notGenerated = [{ name: "demo", directory: "D:\models\demo" }];
  const host = document.createElement("div");
  host.className = "grid";
  host.id = "ng-probe";
  host.innerHTML = notGeneratedHtml();
  document.getElementById("screen-monitor").appendChild(host);
  const b = host.querySelector("[data-run-new]"), m = host.querySelector(".nosignal.flow");
  const out = b && m ? { buttonTop: b.getBoundingClientRect().top, messageBottom: m.getBoundingClientRect().bottom } : null;
  host.remove();
  monitorState.notGenerated = saved;
  return out;
});
check("monitor: a not-generated card has its message above GENERATE & RUN, not under it", ngBoxes !== null && ngBoxes.buttonTop >= ngBoxes.messageBottom, JSON.stringify(ngBoxes));
await axeScan("monitor");

// ---- The app workspace (NAV-3): choose the app ONCE ----------------------------------------------------
await page.click('.tabs .place[data-place="apps"]');
await page.waitForSelector("#app-list [data-ws-open]", { timeout: 3000 });
const hiddenBefore = await page.$$eval('.place-strip[data-place="apps"] .tab[data-ws]', (t) => t.every((x) => getComputedStyle(x).display === "none"));
check("before an app is chosen the app tabs are not offered", hiddenBefore, "");
await page.click("#app-list [data-ws-open]");
await page.waitForTimeout(400);
check("Open lands on the app's first tab", (await page.evaluate(() => window.__npdevCurrentScreen)) === "run", "");
check("the workspace bar names the chosen app", /auxscreen/.test(await page.textContent("#workspace-bar")) && !(await page.$eval("#workspace-bar", (b) => b.hidden)), "");
const WS_PICKERS = { run: "#run-app-picker", studio: "#studio-app-picker", browserai: "#browserai-app", prompter: "#prompter-app", dbio: "#dbio-app-picker", hosting: "#hosting-app-picker",
  scrap: "#scrap-app", gpuchecks: "#gpuchecks-app-picker", provenance: "#provenance-app-picker", packs: "#packs-app-picker" };
const wsProblems = [];
for (const [screen, sel] of Object.entries(WS_PICKERS)) {
  await page.click(`.place-strip:not([hidden]) .tab[data-screen="${screen}"]`);
  await page.waitForTimeout(350);
  const r = await page.evaluate((q) => {
    const el = document.querySelector(q);
    const shown = !!el && el.offsetParent !== null;
    return { value: el ? el.value : null, shown, main: document.querySelector("main").getAttribute("data-in-workspace") };
  }, sel);
  const ok = (screen === "run" || /auxscreen/i.test(String(r.value))) && !r.shown && r.main === "true";
  if (!ok) wsProblems.push(`${screen}: ${JSON.stringify(r)}`);
}
check("every app screen already has the chosen app and never shows its own picker", wsProblems.length === 0, wsProblems.join(" | "));
const runDir = await page.evaluate(async () => { await window.__npdevShowScreen("run"); return document.getElementById("run-app-dir").value; });
check("Start & stop uses the chosen app's folder", /auxscreen/i.test(runDir), runDir);
// Screen review wave 2: Edit leads with a plain request that Use a browser AI then carries out.
const editHandoff = await page.evaluate(async () => {
  await window.__npdevShowScreen("studio");
  const jsonFolded = !document.getElementById("studio-advanced").open;
  document.getElementById("studio-describe-text").value = "add a phone number to customers";
  document.getElementById("studio-describe-go").click();
  await new Promise((r) => setTimeout(r, 400));
  const change = document.getElementById("browserai-intent-change");
  return { jsonFolded, screen: window.__npdevCurrentScreen, ask: document.getElementById("browserai-ask").value, intentChange: change.checked };
});
// Item 9: every advanced screen (its group, or the Activity place) says so; the beginner screens do not.
const advancedNotes = await page.evaluate(async () => {
  const out = {};
  for (const id of ["provenance", "packs", "verification", "evals", "monitor", "jobs", "studio", "dbio", "hosting"]) {
    await window.__npdevShowScreen(id);
    out[id] = !!document.querySelector(`#screen-${id} > .advanced-note`);
  }
  await window.__npdevShowScreen("run");
  return out;
});
check("advanced screens carry the Advanced note and Edit, Data and Share do not",
  ["provenance", "packs", "verification", "evals", "monitor", "jobs"].every((k) => advancedNotes[k]) &&
  !advancedNotes.studio && !advancedNotes.dbio && !advancedNotes.hosting, JSON.stringify(advancedNotes));
check("Edit folds the JSON editor and hands a described change to Use a browser AI, words filled in and 'change this app' chosen",
  editHandoff.jsonFolded && editHandoff.screen === "browserai" && editHandoff.ask === "add a phone number to customers" && editHandoff.intentChange, JSON.stringify(editHandoff));
await page.click("#workspace-bar .ws-change");
await page.waitForTimeout(150);
check("Change app returns to the list", (await page.evaluate(() => window.__npdevCurrentScreen)) === "apps", "");
await page.click('.tabs .place[data-place="settings"]');
await page.waitForTimeout(150);
check("outside the workspace the pickers come back", (await page.evaluate(() => document.querySelector("main").getAttribute("data-in-workspace"))) === null, "");
await page.screenshot({ path: path.join(OUT, "workspace.png"), fullPage: false });

// ---- Use a browser AI (browser-AI relay F2/T2.2): the five steps against the CAPTURED relay answers ----------
// Nothing here is invented: every answer the shim returns is a file in NPDevManager/fixtures captured from the real
// `npdev ai relay` (a marker in the pasted text picks which). What this asserts is that the screen DRAWS what the CLI
// said -- one plain sentence per outcome, the preview from `preview` only, a box per warning that needs a yes, the
// copied text byte for byte -- and decides nothing else.
{
  // The sections after this one expect the workspace app the section before it chose: remember it, give it back.
  const keptApp = await page.evaluate(() => window.NPDevWorkspace.current());
  await page.evaluate(() => {
    window.NPDevWorkspace.clear();
    window.__clip = null;
    window.__relayStarts = []; window.__relayIngests = []; window.__relayApplies = []; window.__relayUndos = []; window.__relayAborts = [];
    window.__relayFileIngests = []; window.__pickCalls = 0; window.__pickAnswer = null;
    window.__launchLog = []; window.__fireOpsDone = true; window.__npdevPollMs = 5; window.__probeQueue = null; window.__buildExit = 0; window.__startError = null; window.__opened = null;
    window.__applyAnswer = null;
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async (t) => { window.__clip = t; } } });
    return window.__npdevShowScreen("browserai");
  });
  await page.waitForTimeout(400);
  // A clean start: the Edit hand-off check above left its request in the box and the workspace's app in the picker.
  await page.evaluate(() => {
    const picker = document.getElementById("browserai-app");
    picker.value = "";
    picker.dispatchEvent(new Event("change", { bubbles: true }));
    document.getElementById("browserai-ask").value = "";
  });
  const shown = (sel) => page.$eval(sel, (e) => !e.hidden && e.offsetParent !== null).catch(() => false);
  const text = (sel) => page.$eval(sel, (e) => e.textContent.trim()).catch(() => null);
  const fixtureNext = (name) => fixture(name).next.prompt;
  check("browser AI: its tab is offered before any app is chosen (a new app needs none)",
    await page.$eval('.place-strip[data-place="apps"] .tab[data-screen="browserai"]', (t) => getComputedStyle(t).display !== "none"), "");
  check("browser AI: opens on step 1 only, as 'create a new app'",
    (await shown("#browserai-step-1")) && !(await shown("#browserai-step-2")) && !(await shown("#browserai-step-4")) &&
    (await page.$eval("#browserai-intent-create", (e) => e.checked)) && (await shown("#browserai-newname")), "");
  await page.click("#browserai-continue");
  check("browser AI: Continue with nothing typed says what is missing, in a sentence",
    (await text("#browserai-step1-note")) === "Describe what you want first." && (await page.evaluate(() => window.__relayStarts.length)) === 0, String(await text("#browserai-step1-note")));
  await page.fill("#browserai-ask", "a small bike repair shop");
  await page.click("#browserai-continue");
  check("browser AI: a new app needs a name before anything is asked of the CLI",
    (await text("#browserai-step1-note")) === "Give the new app a name first." && (await page.evaluate(() => window.__relayStarts.length)) === 0, "");
  await page.fill("#browserai-newname", "../Bike shop");
  await page.click("#browserai-continue");
  check("browser AI: a name that is more than one plain name is refused here, and never reaches the CLI",
    /letters, numbers, spaces and dashes/.test(await text("#browserai-step1-note")) && (await page.evaluate(() => window.__relayStarts.length)) === 0, String(await text("#browserai-step1-note")));
  await page.fill("#browserai-newname", "Bike shop");
  await page.click("#browserai-continue");
  await page.waitForTimeout(250);
  const starts = await page.evaluate(() => window.__relayStarts);
  check("browser AI: Continue opens ONE conversation for a NEW app, with a folder-safe name",
    starts.length === 1 && starts[0].intent === "create" && starts[0].newAppName === "Bike-shop" && !starts[0].appDir && starts[0].ask === "a small bike repair shop" && starts[0].interview === false, JSON.stringify(starts));
  check("browser AI: step 2 is current with COPY, and step 1 is folded away",
    (await shown("#browserai-copy")) && !(await shown("#browserai-ask")) && (await text("#browserai-state-1")) === "Done" && (await text("#browserai-state-2")) === "Now", "");
  check("browser AI: 'See the text' holds the CLI's text as plain text",
    /^NPDEV-PROMPT /.test(await text("#browserai-prompt-text")), "");
  await page.click("#browserai-copy");
  await page.waitForTimeout(150);
  const clip = await page.evaluate(() => window.__clip);
  check("browser AI: COPY puts the CLI's text on the clipboard byte for byte and moves to step 3",
    clip === fixture("relay-prompt.json").prompt && (await shown("#browserai-paste")) && !(await shown("#browserai-copy")), `${(clip || "").length} chars`);
  check("browser AI: the copy is announced in a live region", (await text("#browserai-announce")) === "Copied. Now paste it into your AI and send it.", String(await text("#browserai-announce")));

  // the other ways in (T2.3): Ctrl+V with nothing focused, the file dialog, a file dropped on the window. Each one
  // reaches the CLI as the SAME check as a typed paste, told apart only by how it arrived.
  const NEEDS_FIX_SENTENCE = "The AI made a mistake. Copy this message and paste it in the same conversation.";
  const TRUNCATED_SENTENCE = "The answer was cut off. Copy this message so the AI sends it complete.";
  const fireDrag = (name, paths) => page.evaluate(([n, p]) => { (window.__listeners[n] || []).forEach((f) => f({ payload: { paths: p, position: { x: 4, y: 4 } } })); }, [name, paths]);
  check("browser AI: step 3 offers a drop place, a Choose-a-file button and says Ctrl+V works",
    (await shown("#browserai-drop")) && (await text("#browserai-pick-file")) === "Choose a file" && /Ctrl\+V/.test(await text("#browserai-paste-hint")) &&
    (await page.$eval("#browserai-drop", (e) => e.getAttribute("role") === "group" && !!e.getAttribute("aria-labelledby"))), "");
  await page.evaluate(() => {
    document.activeElement && document.activeElement.blur();
    const dt = new DataTransfer();
    dt.setData("text/plain", "STUB-TRUNCATED pasted without focusing the box");
    document.body.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
  });
  await page.waitForTimeout(250);
  check("browser AI: Ctrl+V anywhere on step 3 fills the box and checks it as a paste",
    await page.evaluate(() => { const i = window.__relayIngests.at(-1); return window.__relayIngests.length === 1 && i.source === "paste" && i.code === "ZWW4" && i.text.startsWith("STUB-TRUNCATED pasted"); }) &&
    (await page.$eval("#browserai-paste", (e) => e.value)).startsWith("STUB-TRUNCATED pasted") && (await text("#browserai-outcome")) === TRUNCATED_SENTENCE, String(await text("#browserai-outcome")));
  await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.setData("text/plain", "STUB-TRUNCATED into another box");
    document.getElementById("browserai-ask").dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
  });
  await page.waitForTimeout(150);
  check("browser AI: a paste into some OTHER box is left alone (only unfocused Ctrl+V is taken)",
    (await page.evaluate(() => window.__relayIngests.length)) === 1, "");
  await page.evaluate(() => { window.__pickAnswer = ["D:\\Downloads\\STUB-NEEDS-FIX-answer.json"]; });
  await page.click("#browserai-pick-file");
  await page.waitForTimeout(250);
  check("browser AI: Choose a file hands the picked path to the CLI as a file-picker answer for this conversation",
    await page.evaluate(() => { const i = window.__relayFileIngests.at(-1); return window.__relayFileIngests.length === 1 && i.code === "ZWW4" && i.source === "file-picker" && i.files.length === 1 && /STUB-NEEDS-FIX-answer\.json$/.test(i.files[0]); }) &&
    (await text("#browserai-outcome")) === NEEDS_FIX_SENTENCE, String(await text("#browserai-outcome")));
  await page.evaluate(() => { window.__pickAnswer = []; });
  await page.click("#browserai-pick-file");
  await page.waitForTimeout(150);
  check("browser AI: cancelling the file dialog checks nothing and says nothing",
    (await page.evaluate(() => window.__pickCalls)) === 2 && (await page.evaluate(() => window.__relayFileIngests.length)) === 1 && (await text("#browserai-outcome")) === NEEDS_FIX_SENTENCE, "");
  await fireDrag("tauri://drag-enter", ["D:\\Downloads\\STUB-TRUNCATED-answer.txt"]);
  check("browser AI: a file held over the window changes the drop place in words and in its look, not by colour alone",
    (await page.getAttribute("#browserai-drop", "data-over")) === "true" && (await text("#browserai-drop-label")) === "Drop the file now", String(await text("#browserai-drop-label")));
  await fireDrag("tauri://drag-leave", []);
  check("browser AI: moving the file away puts the drop place back",
    (await page.getAttribute("#browserai-drop", "data-over")) === "false" && (await text("#browserai-drop-label")) === "Or drop the file the AI made here", "");
  await fireDrag("tauri://drag-enter", ["x"]);
  await fireDrag("tauri://drag-drop", ["D:\\Downloads\\STUB-TRUNCATED-answer.txt", "D:\\Downloads\\second.json"]);
  await page.waitForTimeout(250);
  check("browser AI: dropping files on the window hands ALL their paths to the CLI as a drop answer",
    await page.evaluate(() => { const i = window.__relayFileIngests.at(-1); return window.__relayFileIngests.length === 2 && i.code === "ZWW4" && i.source === "drop" && i.files.length === 2 && /second\.json$/.test(i.files[1]); }) &&
    (await text("#browserai-outcome")) === TRUNCATED_SENTENCE && (await page.getAttribute("#browserai-drop", "data-over")) === "false", String(await text("#browserai-outcome")));

  // step 3 open with its drop place and an outcome showing: measured on its own (the later scan sees step 3 folded)
  const step3Axe = await axeScan("browserai-step3");
  delete a11yMeasured["browserai-step3"];
  const step3Bad = Object.values(step3Axe.counts).reduce((a, b) => a + b, 0);
  check("browser AI: step 3 with its drop place has no accessibility violations", step3Bad === 0, JSON.stringify(step3Axe.targets).slice(0, 400));

  // an answer that is not ready: one sentence, one button, and the button copies the CLI's message
  await page.fill("#browserai-paste", "STUB-TRUNCATED half an answer");
  await page.click("#browserai-check");
  await page.waitForTimeout(200);
  check("browser AI: a cut-off answer is one plain sentence with a Copy message button",
    (await text("#browserai-outcome")) === "The answer was cut off. Copy this message so the AI sends it complete." && (await text("#browserai-outcome-button")) === "Copy message", String(await text("#browserai-outcome")));
  check("browser AI: the check asked about THIS conversation, with the pasted text",
    await page.evaluate(() => { const i = window.__relayIngests.at(-1); return i.code === "ZWW4" && i.text.startsWith("STUB-TRUNCATED"); }), "");
  await page.click("#browserai-outcome-button");
  await page.waitForTimeout(150);
  check("browser AI: Copy message copies the CLI's own message and clears the box for the next answer",
    (await page.evaluate(() => window.__clip)) === fixtureNext("relay-ingest-truncated.json") && (await page.$eval("#browserai-paste", (e) => e.value)) === "", "");
  await page.fill("#browserai-paste", "STUB-NEEDS-FIX");
  await page.click("#browserai-check");
  await page.waitForTimeout(200);
  check("browser AI: mistakes the AI made are one sentence and the message to send back",
    (await text("#browserai-outcome")) === "The AI made a mistake. Copy this message and paste it in the same conversation." && (await text("#browserai-outcome-button")) === "Copy message", String(await text("#browserai-outcome")));
  await page.fill("#browserai-paste", "");
  await page.click("#browserai-check");
  check("browser AI: checking an empty box says to paste first", (await text("#browserai-outcome")) === "Paste the AI's answer first.", "");

  // a ready answer for a NEW app: the preview is the CLI's sentences, no box to tick, Apply with a place to keep it
  await page.fill("#browserai-paste", "STUB-CREATE");
  await page.click("#browserai-check");
  await page.waitForTimeout(250);
  const createPreview = await page.$$eval("#browserai-preview li", (l) => l.map((x) => x.textContent.replace(/^\S\s*/, "").trim()));
  check("browser AI: a ready answer opens step 4 with the CLI's own sentences, and nothing else",
    (await shown("#browserai-step-4")) && createPreview[0] === "A new app with 2 kinds of records and 7 fields." && createPreview.length === fixture("relay-ingest-ready-create.json").preview.sentences.length, JSON.stringify(createPreview));
  check("browser AI: step 4 asks for no tick when the CLI asked for none",
    (await page.$$eval("#browserai-confirms input", (b) => b.length)) === 0 && !(await page.$eval("#browserai-apply", (b) => b.disabled)), "");
  await page.evaluate(() => {
    window.__applyAnswer = Object.assign({}, window.__FIXTURES__.relay.apply, { created: true, backupPath: null, intent: "create", appDir: "/home/you/NPDev/Apps/Bike-shop" });
    window.__probeQueue = ["stopped", "running"]; // not up yet on the first look, up on the second
  });
  await page.click("#browserai-apply");
  await page.waitForTimeout(300);
  const createApply = await page.evaluate(() => window.__relayApplies);
  check("browser AI: Apply sends the conversation, no ticks, and the folder NPDev already keeps apps in",
    createApply.length === 1 && createApply[0].code === "ZWW4" && createApply[0].confirmed.length === 0 && createApply[0].parentDir === "D:\\WorkSpace\\NPDev\\Build", JSON.stringify(createApply));
  check("browser AI: a new app is generated, built and started in that order, in ITS generated folder (never the model folder)",
    JSON.stringify(await page.evaluate(() => window.__launchLog)) === JSON.stringify([
      "generate:/home/you/NPDev/Apps/Bike-shop-app", "run:build-finalapp:/home/you/NPDev/Apps/Bike-shop-app", "start:/home/you/NPDev/Apps/Bike-shop-app"]),
    JSON.stringify(await page.evaluate(() => window.__launchLog)));
  check("browser AI: once the app answers, it says so and offers to open it",
    (await text("#browserai-launch")) === "Your app is running." && (await shown("#browserai-open")) && !(await shown("#browserai-rebuild")) && !(await shown("#browserai-see-run")), String(await text("#browserai-launch")));
  await page.click("#browserai-open");
  await page.waitForTimeout(100);
  check("browser AI: Open the app opens the address the Monitor reports for it", (await page.evaluate(() => window.__opened)) === "http://localhost:8411", String(await page.evaluate(() => window.__opened)));
  check("browser AI: a created app ends on 'Show my apps', with no Undo offered for it",
    (await text("#browserai-progress")) === "Done. Your new app was created." && (await shown("#browserai-go-apps")) && !(await shown("#browserai-undo")) && !(await shown("#browserai-rebuild")), String(await text("#browserai-progress")));

  // start over keeps the request; then CHANGE an app: a removal needs the person's explicit yes
  await page.click("#browserai-again");
  check("browser AI: asking for something else returns to step 1 with the request still there",
    (await shown("#browserai-ask")) && (await page.$eval("#browserai-ask", (e) => e.value)) === "a small bike repair shop", "");
  await fireDrag("tauri://drag-drop", ["D:\\x\\STUB-CREATE.json"]);
  await page.waitForTimeout(150);
  check("browser AI: a file dropped when no conversation is waiting for it goes nowhere and says where to drop it",
    (await page.evaluate(() => window.__relayFileIngests.length)) === 2 && (await text("#browserai-announce")) === "Drop the file in step 3, after you have asked your AI.", String(await text("#browserai-announce")));
  await page.evaluate(() => {
    window.__relayStarts = []; window.__relayApplies = []; window.__relayUndos = []; window.__relayAborts = [];
    window.__applyAnswer = null; window.__launchLog = [];
    window.__probeQueue = ["running", "stopped", "running"]; // the app is up when the change lands; down once stopped; up again at the end
    const p = document.getElementById("browserai-app");
    const opt = [...p.options].find((o) => /auxscreen/i.test(o.textContent));
    if (opt) { p.value = opt.value; p.dispatchEvent(new Event("change", { bubbles: true })); }
  });
  check("browser AI: picking an app means 'change this app'",
    (await page.$eval("#browserai-intent-change", (e) => e.checked)) && !(await shown("#browserai-newname")), "");
  await page.fill("#browserai-ask", "drop the first name");
  await page.check("#browserai-interview");
  await page.click("#browserai-continue");
  await page.waitForTimeout(250);
  const changeStart = await page.evaluate(() => window.__relayStarts);
  check("browser AI: a change is started for the picked app, and 'let the AI ask me questions' is passed on",
    changeStart.length === 1 && changeStart[0].intent === "change" && /auxscreen/i.test(changeStart[0].appDir) && !changeStart[0].newAppName && changeStart[0].interview === true, JSON.stringify(changeStart));
  await page.click("#browserai-copy");
  await page.fill("#browserai-paste", "STUB-REMOVAL");
  await page.click("#browserai-check");
  await page.waitForTimeout(250);
  const boxes = await page.$$eval("#browserai-confirms label", (l) => l.map((x) => x.textContent.replace(/^\s*⚠\s*/, "").trim()));
  check("browser AI: a removal shows the CLI's warning as a box to tick, and Apply waits for it",
    boxes.length === 1 && boxes[0] === "This removes the field First name from Customer and the data stored in it." && (await page.$eval("#browserai-apply", (b) => b.disabled)), JSON.stringify(boxes));
  // the richest state (a folded step 1-3, a warning box, the AI's words under See details): measured for accessibility here
  await axeScan("browserai");
  await page.check("#browserai-confirms input");
  check("browser AI: ticking the box enables Apply", !(await page.$eval("#browserai-apply", (b) => b.disabled)), "");
  await page.click("#browserai-apply");
  await page.waitForTimeout(300);
  const changeApply = await page.evaluate(() => window.__relayApplies);
  check("browser AI: Apply names exactly the warning that was ticked, and no folder (the app exists)",
    changeApply.length === 1 && changeApply[0].confirmed.join() === "REMOVAL:field:Customer.firstName" && !changeApply[0].parentDir, JSON.stringify(changeApply));
  const changeLog = await page.evaluate(() => window.__launchLog);
  check("browser AI: a RUNNING app that is changed is generated, stopped, built, then started -- stopped BEFORE the build, since it holds its files",
    changeLog.length === 4 && changeLog.map((l) => l.split(":")[0]).join() === "generate,stop,run,start" && changeLog.every((l) => /auxscreen$/i.test(l)), JSON.stringify(changeLog));
  check("browser AI: a changed app ends with the change in, the app running, Open the app and Undo",
    (await text("#browserai-progress")) === "Done. The change is in your app." && (await text("#browserai-launch")) === "Your app is running." &&
    (await shown("#browserai-open")) && (await shown("#browserai-undo")) && !(await shown("#browserai-rebuild")) && !(await shown("#browserai-go-apps")), String(await text("#browserai-launch")));
  await page.click("#browserai-undo");
  await page.waitForTimeout(250);
  check("browser AI: Undo asks the CLI for this conversation and says so in a sentence",
    (await page.evaluate(() => window.__relayUndos.length)) === 1 && (await text("#browserai-progress")) === "Undone. Your app is as it was before." && !(await shown("#browserai-undo")), String(await text("#browserai-progress")));
  check("browser AI: after Undo the running app still has the change, so Update the app now is offered and says why",
    (await shown("#browserai-rebuild")) && (await text("#browserai-launch")) === "The app still shows the change until you update it.", String(await text("#browserai-launch")));

  // what goes wrong while the app is made ready: one sentence each, the reason under 'See what happened', a way to try again
  await page.evaluate(() => { window.__launchLog = []; window.__buildExit = 2; window.__probeQueue = ["stopped"]; });
  await page.click("#browserai-rebuild");
  await page.waitForTimeout(300);
  check("browser AI: a build that fails says so, keeps the reason (exit code and log) under 'See what happened', and offers another try",
    (await text("#browserai-launch")) === "The app could not be built." && (await page.$eval("#browserai-launch", (e) => e.classList.contains("bad"))) &&
    /exit code 2/.test(await text("#browserai-launch-prose")) && /ops-build\.log/.test(await text("#browserai-launch-prose")) &&
    (await shown("#browserai-launch-details")) && (await shown("#browserai-rebuild")) && (await shown("#browserai-see-run")) && !(await shown("#browserai-open")) &&
    (await page.evaluate(() => window.__launchLog.every((l) => !l.startsWith("start:")))), String(await text("#browserai-launch")));
  // the failure state is the richest on step 5 (a bad line, the reason under See what happened, three buttons): measured on its own
  await page.evaluate(() => { document.getElementById("browserai-launch-details").open = true; });
  const failAxe = await axeScan("browserai-failed");
  delete a11yMeasured["browserai-failed"];
  check("browser AI: step 5 after a failed build has no accessibility violations", Object.values(failAxe.counts).reduce((a, b) => a + b, 0) === 0, JSON.stringify(failAxe.targets).slice(0, 400));
  await page.evaluate(() => { window.__buildExit = 0; window.__startError = "port 8080 is already in use"; });
  await page.click("#browserai-rebuild");
  await page.waitForTimeout(300);
  check("browser AI: an app that will not start says so with the reason, and does not wait for it",
    (await text("#browserai-launch")) === "The app could not be started." && /port 8080/.test(await text("#browserai-launch-prose")) && (await shown("#browserai-rebuild")), String(await text("#browserai-launch")));
  await page.evaluate(() => { window.__startError = null; window.__probeQueue = ["stopped"]; });
  await page.click("#browserai-rebuild");
  await page.waitForTimeout(900);
  check("browser AI: an app that starts but never answers says where to look, with the app's own words about why",
    (await text("#browserai-launch")) === "The app was started but has not answered yet. Look at it in Running apps." && /127\.0\.0\.1:8411/.test(await text("#browserai-launch-prose")) && (await shown("#browserai-see-run")), String(await text("#browserai-launch")));
  await page.click("#browserai-see-run");
  await page.waitForTimeout(250);
  check("browser AI: 'See it in Running apps' goes to the Monitor", (await page.evaluate(() => window.__npdevCurrentScreen)) === "monitor", "");
  await page.evaluate(() => { window.__probeQueue = ["stopped", "running"]; return window.__npdevShowScreen("browserai"); });
  await page.waitForTimeout(250);
  await page.click("#browserai-rebuild");
  await page.waitForTimeout(300);
  check("browser AI: trying again once it works ends in 'Your app is running.', and the failure's reason is gone",
    (await text("#browserai-launch")) === "Your app is running." && !(await shown("#browserai-launch-details")) && (await shown("#browserai-open")) && !(await shown("#browserai-rebuild")), String(await text("#browserai-launch")));

  // a conversation given up on is closed in the CLI too
  await page.click("#browserai-again");
  await page.fill("#browserai-ask", "one more change");
  await page.click("#browserai-continue");
  await page.waitForTimeout(200);
  await page.click("#browserai-copy");
  await page.fill("#browserai-paste", "STUB-READY");
  await page.click("#browserai-check");
  await page.waitForTimeout(200);
  await page.click("#browserai-else");
  await page.waitForTimeout(200);
  check("browser AI: 'Ask for something else' on a preview closes that conversation",
    (await page.evaluate(() => window.__relayAborts.length)) === 1 && (await shown("#browserai-ask")), "");

  // the two screens are one switch apart and the request travels with it
  await page.fill("#browserai-ask", "carry me across");
  await page.click("#browserai-mode-api");
  await page.waitForTimeout(250);
  check("browser AI: the switch opens the API-key screen with the request carried across",
    (await page.evaluate(() => window.__npdevCurrentScreen)) === "prompter" && (await page.$eval("#prompter-ask", (e) => e.value)) === "carry me across" &&
    (await page.getAttribute("#prompter-mode-api", "aria-pressed")) === "true", "");
  await page.fill("#prompter-ask", "and back again");
  await page.click("#prompter-mode-browser");
  await page.waitForTimeout(250);
  check("browser AI: and back, with the request carried again",
    (await page.evaluate(() => window.__npdevCurrentScreen)) === "browserai" && (await page.$eval("#browserai-ask", (e) => e.value)) === "and back again" &&
    (await page.getAttribute("#browserai-mode-browser", "aria-pressed")) === "true", "");
  check("browser AI: Ask AI (Home, Edit) lands here, not on the API-key screen",
    await page.evaluate(async () => { await window.NPDevWorkspace.open({ name: "auxscreen", directory: "D:\\WorkSpace\\NPDev\\Build\\generated-finalapps\\auxscreen" }, "ask-ai"); return window.__npdevCurrentScreen === "browserai"; }), "");

  // the same walk in Portuguese: every sentence the screen draws is in the catalogue and reads in Portuguese
  await page.evaluate(() => { window.NPDevWorkspace.clear(); return NPDevI18n.setLang("pt"); });
  await page.waitForTimeout(400);
  await page.evaluate(() => window.__npdevShowScreen("browserai"));
  await page.waitForTimeout(300);
  await page.check("#browserai-intent-create");
  await page.fill("#browserai-ask", "uma oficina de bicicletas");
  await page.fill("#browserai-newname", "Oficina");
  await page.click("#browserai-continue");
  await page.waitForTimeout(250);
  await page.click("#browserai-copy");
  await page.fill("#browserai-paste", "STUB-NEEDS-FIX");
  await page.click("#browserai-check");
  await page.waitForTimeout(250);
  const ptOutcome = await text("#browserai-outcome");
  check("browser AI: in Portuguese the outcome is one Portuguese sentence with a Portuguese button",
    ptOutcome === "A IA cometeu um erro. Copie esta mensagem e cole na mesma conversa." && (await text("#browserai-outcome-button")) === "Copiar mensagem", String(ptOutcome));
  check("browser AI: in Portuguese the drop place, its button and the Ctrl+V hint read in Portuguese",
    (await text("#browserai-drop-label")) === "Ou solte aqui o arquivo que a IA criou" && (await text("#browserai-pick-file")) === "Escolher arquivo" &&
    (await text("#browserai-paste-hint")) === "Você também pode apertar Ctrl+V em qualquer lugar desta etapa.", String(await text("#browserai-drop-label")));
  await fireDrag("tauri://drag-enter", ["x"]);
  check("browser AI: in Portuguese a file held over the window says so in Portuguese", (await text("#browserai-drop-label")) === "Solte o arquivo agora", String(await text("#browserai-drop-label")));
  await fireDrag("tauri://drag-leave", []);
  check("browser AI: in Portuguese the drop place goes back to its resting sentence", (await text("#browserai-drop-label")) === "Ou solte aqui o arquivo que a IA criou", "");
  await page.fill("#browserai-paste", "STUB-REMOVAL");
  await page.click("#browserai-check");
  await page.waitForTimeout(250);
  const ptBox = await page.$eval("#browserai-confirms label", (l) => l.textContent.replace(/^\s*⚠\s*/, "").trim());
  check("browser AI: in Portuguese the box a person must tick, the sentence they agree to, reads in Portuguese",
    ptBox === "Isto remove o campo First name de Customer e os dados guardados nele.", ptBox);
  check("browser AI: the CLI's summary list is marked as English text, not silently half-translated",
    (await page.getAttribute("#browserai-preview", "lang")) === "en" && (await page.getAttribute("#browserai-preview", "translate")) === "no", "");
  await page.check("#browserai-confirms input");
  await page.evaluate(() => {
    window.__applyAnswer = Object.assign({}, window.__FIXTURES__.relay.apply, { created: true, backupPath: null, intent: "create", appDir: "/home/you/NPDev/Apps/Oficina" });
    window.__launchLog = []; window.__buildExit = 1; window.__probeQueue = ["running"];
  });
  await page.click("#browserai-apply");
  await page.waitForTimeout(300);
  check("browser AI: in Portuguese a build that fails is told in Portuguese, with its buttons",
    (await text("#browserai-launch")) === "Não foi possível montar o app." && (await text("#browserai-launch-details summary")) === "Ver o que aconteceu" &&
    (await text("#browserai-rebuild")) === "Atualizar o app agora" && (await text("#browserai-see-run")) === "Ver em Apps em execução", String(await text("#browserai-launch")));
  await page.evaluate(() => { window.__buildExit = 0; });
  await page.click("#browserai-rebuild");
  await page.waitForTimeout(300);
  check("browser AI: in Portuguese the app that runs is announced and offered in Portuguese",
    (await text("#browserai-launch")) === "Seu app está funcionando." && (await text("#browserai-open")) === "Abrir o app", String(await text("#browserai-launch")));
  const ptMissing = await page.evaluate(() => {
    const visible = (e) => !!(e.offsetParent || e.getClientRects().length);
    const shownText = Array.from(document.querySelectorAll("#screen-browserai *")).filter((e) => visible(e)).map((e) => e.textContent);
    return NPDevI18n.missingNow().filter((x) => shownText.some((t) => t.includes(x)));
  });
  check("browser AI: every sentence on the screen, in every step it walked, is in the Portuguese catalogue", ptMissing.length === 0, JSON.stringify(ptMissing.slice(0, 8)));
  await page.evaluate(() => NPDevI18n.setLang("en"));
  await page.waitForTimeout(300);
  if (keptApp) await page.evaluate((a) => window.NPDevWorkspace.select(a), keptApp);
  await page.click('.tabs .place[data-place="settings"]');
  await page.waitForTimeout(200);
}

// ---- The Scrap Manager ---------------------------------------------------------------------
// NAV-1: it lives in the My apps strip, which shows only once that place is open.
await page.click('.tabs .place[data-place="apps"]');
await page.click('.place-strip:not([hidden]) .tab[data-screen="scrap"]');
await page.waitForTimeout(600);
const appOptions = await page.$$eval("#scrap-app option", (e) => e.length);
check("Scrap Manager's app picker is fed from the Monitor's scan", appOptions === 5, `${appOptions}`);

await page.waitForSelector("#scrap-runs .runrow", { timeout: 5000 });
const runRows = await page.$$eval("#scrap-runs .runrow .dot", (e) => e.map((d) => d.className));
check("history renders green and red runs", runRows.some((c) => c.includes("green")) && runRows.some((c) => c.includes("red")),
  runRows.join(","));

await page.click("#scrap-runs .runrow");
await page.waitForSelector(".verdict", { timeout: 5000 });
const verdict = await page.textContent(".verdict");
check("run detail shows the verdict", /RED|GREEN/.test(verdict), verdict.trim());
const failed = await page.$$eval(".tstep.failed", (e) => e.length);
check("the failing step is highlighted", failed === 1, `${failed}`);
const identity = await page.textContent("#scrap-detail");
check("identity panel carries the three hashes",
  /routine sha256/.test(identity) && /model sha256/.test(identity) && /platform/.test(identity), "");
await page.screenshot({ path: path.join(OUT, "scrap-filmstrip.png"), fullPage: false });

// D4: preconditions as tool state
await page.click("#scrap-definitions [data-play]");
await page.waitForSelector(".pre", { timeout: 5000 });
const pre = await page.$$eval(".pre", (e) => e.length);
check("preflight renders each precondition as its own row", pre === 4, `${pre} rows`);

// D3: the create modal shows the CLI's validation verbatim
await page.click("#scrap-new");
await page.waitForSelector("#create-modal.show", { timeout: 3000 });
await page.fill("#create-name", "invalid-draft");
await page.click("#create-validate").catch(() => {});
await page.waitForTimeout(400);
check("Play stays disabled until the CLI says VALID",
  await page.$eval("#create-play", (e) => e.disabled), "");
await page.screenshot({ path: path.join(OUT, "scrap-create.png"), fullPage: false });

await page.click('[data-close-modal="create-modal"]');
await page.waitForTimeout(150);

// ---- Get help with this error (SUP-3) ----------------------------------------------------------
// The shim answers support_report with fixtures/support-install-failed.json, which a Rust test pins to
// what src/support.rs really produces for a planted API key and a planted known secret.
await page.click('.tabs .place[data-place="setup"]');
await page.click('.place-strip:not([hidden]) .tab[data-screen="install"]');
await page.waitForSelector("#jdk-install-btn", { state: "visible", timeout: 3000 });
await page.click("#jdk-install-btn");
await page.waitForSelector("#jdk-status + .support-help-btn", { timeout: 3000 });
check("an install failure offers 'Get help with this error'", true, "");
await page.click("#jdk-status + .support-help-btn");
await page.waitForFunction(() => document.getElementById("support-text")?.value.length > 50, null, { timeout: 3000 });
const report = await page.$eval("#support-text", (t) => t.value);
check("the help dialog names what the user was trying to do", /install Java/i.test(report), report.slice(0, 120));
check("the help dialog shows the key as <redacted:api-key>, never the key",
  report.includes("<redacted:api-key>") && !report.includes("gsk_abcdefghijklmnopqrstuvwx") && !report.includes("hunter-two-secret-value"), "");
const summaryText = await page.textContent("#support-summary");
check("the dialog says how much was hidden", /secret/.test(summaryText), summaryText.trim());
check("the dialog is a native modal that holds focus",
  await page.$eval("#support-dialog", (d) => d.open && d.matches(":modal")), "");
await page.screenshot({ path: path.join(OUT, "support-dialog.png"), fullPage: false });
await page.keyboard.press("Escape");
await page.waitForTimeout(150);
await axeScan("install");

// ---- Appearance & accessibility (THM-3, THM-4, ACC-1) ---------------------------------------------
const cssVar = (name) => page.evaluate((n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim(), name);
await page.click('.tabs .place[data-place="settings"]');
await page.click('.place-strip:not([hidden]) .tab[data-screen="appearance"]');
await page.waitForSelector("#appearance-root .theme-card", { timeout: 3000 });
const cardIds = await page.$$eval(".theme-card", (c) => c.map((x) => x.dataset.theme));
check("the gallery lists System then the eight built-ins in the planned order",
  cardIds.join(",") === "system," + BUILTIN_ORDER.join(","), cardIds.join(","));
check("Dark is the default theme (D1)",
  (await page.$eval('.theme-card[data-theme="npdev-dark"]', (b) => b.getAttribute("aria-pressed"))) === "true"
  && (await cssVar("--t-bg")) === "#0b0d10", await cssVar("--t-bg"));
await page.click('.theme-card[data-theme="npdev-light"]');
await page.waitForTimeout(150);
const lightBg = JSON.parse(readFileSync(path.join(REPO, "NPDevContract", "themes", "npdev-light.json"), "utf8")).colors.bg;
check("choosing Light repaints the whole window", (await cssVar("--t-bg")) === lightBg, await cssVar("--t-bg"));
check("the choice was sent to be saved", (await page.evaluate(() => window.__appearance.theme_id)) === "npdev-light", "");
await page.screenshot({ path: path.join(OUT, "appearance-light.png"), fullPage: false });
await page.click('.theme-card[data-theme="npdev-dark"]');
await page.waitForTimeout(150);
await axeScan("appearance");
await page.click('.theme-card[data-theme="npdev-light"]');
await page.waitForTimeout(150);

// Load a theme file: live preview, Keep stores it, a broken file changes nothing.
const summerBg = JSON.parse(season("summer")).colors.bg;
await page.setInputFiles("#theme-file", { name: "summer.json", mimeType: "application/json", buffer: Buffer.from(season("summer")) });
await page.waitForSelector("#load-msg button.primary", { timeout: 3000 });
check("a seasonal file previews live", (await cssVar("--t-bg")) === summerBg, await cssVar("--t-bg"));
await page.click("#load-msg button.primary");
await page.waitForTimeout(250);
check("Keep stores the theme and selects it", (await page.evaluate(() => window.__imported)) === "summer"
  && (await page.evaluate(() => window.__appearance.theme_id)) === "summer", "");
check("a kept theme joins the gallery after the built-ins",
  (await page.$$eval(".theme-card", (c) => c.map((x) => x.dataset.theme).slice(-1)[0])) === "summer", "");

const broken = JSON.parse(season("autumn"));
broken.colors.textMuted = broken.colors.bg;
broken.colors.accent = "red; background:url(https://evil.example/x)";
await page.setInputFiles("#theme-file", { name: "broken.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(broken)) });
await page.waitForFunction(() => /Not loaded/.test(document.getElementById("load-msg").textContent), null, { timeout: 3000 });
const brokenMsg = await page.textContent("#load-msg");
check("a hand-broken file shows readable errors", /colors\.accent must be #rrggbb/.test(brokenMsg), brokenMsg.slice(0, 140));
check("...and changes nothing", (await cssVar("--t-bg")) === summerBg, await cssVar("--t-bg"));

// Ask an AI: an answer wrapped in a code fence still parses; Undo restores.
await page.fill("#ai-answer", "Here you go:\n```json\n" + season("winter") + "\n```");
await page.click("#ai-preview");
await page.waitForSelector("#ai-msg button.primary", { timeout: 3000 });
const winterBg = JSON.parse(season("winter")).colors.bg;
check("an AI answer wrapped in prose and a code fence still previews", (await cssVar("--t-bg")) === winterBg, await cssVar("--t-bg"));
await page.click("#ai-msg button:not(.primary)");
await page.waitForTimeout(150);
check("Undo puts the previous theme back", (await cssVar("--t-bg")) === summerBg, await cssVar("--t-bg"));
const prompt = await page.evaluate(() => NPDevTheme.buildThemePrompt("cozy", null));
check("the AI prompt names every colour token", ["bg", "surfaceRaised", "borderStrong", "onAccent"].every((k) => prompt.includes(k)), "");

// Browse themes and Share this theme (THM-10): nothing is fetched until asked; a verified file previews and Keep
// stores it; a file that fails its sha256 is refused and changes nothing; Share opens the catalogue's new-file page.
check("opening Appearance fetches nothing: the catalogue list is not there yet", (await page.$("#catalogue-list")) === null, "");
await page.click("#catalogue-load");
await page.waitForSelector("#catalogue-list .catalogue-row", { timeout: 3000 });
const rowIds = await page.$$eval(".catalogue-row", (r) => r.map((x) => x.dataset.themeId));
check("Browse themes lists the catalogue index", rowIds.join(",") === "autumn,spring,summer,winter,tampered-demo", rowIds.join(","));
check("a theme the user already has is marked installed, not offered again",
  (await page.$('.catalogue-row[data-theme-id="summer"] button')) === null
  && /Installed/.test(await page.textContent('.catalogue-row[data-theme-id="summer"]')), "");
const springBg = JSON.parse(season("spring")).colors.bg;
await page.click('#catalogue-list button[data-theme-id="spring"]');
await page.waitForSelector("#catalogue-msg button.primary", { timeout: 3000 });
check("a catalogue theme previews live", (await cssVar("--t-bg")) === springBg, await cssVar("--t-bg"));
await page.click("#catalogue-msg button.primary");
await page.waitForTimeout(250);
check("Keep imports the catalogue theme and selects it", (await page.evaluate(() => window.__imported)) === "spring"
  && (await page.evaluate(() => window.__appearance.theme_id)) === "spring", "");
await page.click("#theme-share");
await page.waitForFunction(() => !!window.__opened, null, { timeout: 3000 });
const opened = await page.evaluate(() => window.__opened);
check("Share this theme opens the catalogue repo's new-file page for that theme",
  opened.startsWith("https://github.com/MarceloGiazzon/npdev-themes/new/main/themes?filename=spring.json"), opened);
await page.click('#catalogue-load');
await page.waitForSelector("#catalogue-list .catalogue-row", { timeout: 3000 });
await page.click('#catalogue-list button[data-theme-id="tampered-demo"]');
await page.waitForFunction(() => /Refused/.test(document.getElementById("catalogue-msg").textContent), null, { timeout: 3000 });
const refusal = await page.textContent("#catalogue-msg");
check("a file that fails its sha256 is refused and says so", /sha256/.test(refusal) && /NOT imported/.test(refusal), refusal.slice(0, 160));
check("...and nothing was imported or repainted",
  (await page.evaluate(() => window.__imported)) === "spring" && (await cssVar("--t-bg")) === springBg
  && (await page.evaluate(() => (window.__userThemes || []).some((x) => x.id === "tampered-demo"))) === false, "");
await page.click('.theme-card[data-theme="summer"]');
await page.waitForTimeout(150);

// Accessibility preferences change the render (ACC-1).
await page.selectOption("#a11y-text_scale", "1.3");
await page.selectOption("#a11y-motion", "reduce");
await page.selectOption("#a11y-focus", "always");
await page.selectOption("#a11y-spacing", "wide");
await page.selectOption("#a11y-links", "underline");
const positiveBefore = await cssVar("--t-positive");
await page.selectOption("#a11y-cvd", "safe");
await page.waitForTimeout(150);
const attrs = await page.evaluate(() => ({ scale: document.documentElement.style.getPropertyValue("--np-text-scale"),
  motion: document.documentElement.dataset.npMotion, focus: document.documentElement.dataset.npFocus,
  spacing: document.documentElement.dataset.npSpacing, links: document.documentElement.dataset.npLinks, cvd: document.documentElement.dataset.npCvd }));
check("every accessibility preference reaches the document",
  attrs.scale === "1.3" && attrs.motion === "reduce" && attrs.focus === "always" && attrs.spacing === "wide" && attrs.links === "underline" && attrs.cvd === "safe",
  JSON.stringify(attrs));
check("colour-blind safe mode really changes the status colours", (await cssVar("--t-positive")) !== positiveBefore, positiveBefore);
check("the saved appearance carries the preferences", (await page.evaluate(() => window.__appearance.a11y.text_scale)) === 1.3, "");
await page.screenshot({ path: path.join(OUT, "appearance-a11y.png"), fullPage: false });

// First paint: the snapshot in localStorage is applied before any command answers (no flash).
await page.evaluate(() => sessionStorage.setItem("hang-appearance", "1"));
await page.reload();
await page.waitForTimeout(300);
check("a restart paints the remembered theme before the Manager answers (no flash)",
  (await cssVar("--t-bg")) === summerBg, await cssVar("--t-bg"));
await page.evaluate(() => sessionStorage.removeItem("hang-appearance"));

// Every screen must follow the theme (THM-5): look at the Monitor wall and a form screen in Light and High contrast.
for (const themeId of ["npdev-light", "high-contrast"]) {
  await page.click('.tabs .place[data-place="settings"]');
  await page.click('.place-strip:not([hidden]) .tab[data-screen="appearance"]');
  await page.waitForSelector(`.theme-card[data-theme="${themeId}"]`, { timeout: 3000 });
  await page.click(`.theme-card[data-theme="${themeId}"]`);
  await page.waitForTimeout(150);
  await page.click('.tabs .place[data-place="activity"]');
  await page.waitForSelector("#monitor-grid .crt", { timeout: 3000 });
  await page.screenshot({ path: path.join(OUT, `monitor-${themeId}.png`), fullPage: false });
  const bgNow = await cssVar("--t-bg");
  const wallBg = await page.$eval("#screen-monitor", (e) => getComputedStyle(e).backgroundColor);
  check(`the Monitor wall follows the ${themeId} theme`, wallBg !== "rgb(11, 13, 16)" || bgNow === "#0b0d10", `${wallBg} vs --t-bg ${bgNow}`);
}
await page.click('.tabs .place[data-place="settings"]');
await page.click('.place-strip:not([hidden]) .tab[data-screen="appearance"]');
await page.click('.theme-card[data-theme="npdev-dark"]');
await page.waitForTimeout(150);

// Every other screen the navigation can open gets the same scan, so no screen is left unmeasured.
await page.click('.tabs .place[data-place="settings"]');
await page.click('.place-strip:not([hidden]) .tab[data-screen="appearance"]');
await page.click('.theme-card[data-theme="npdev-dark"]');
const navScreens = [...new Set(JSON.parse(readFileSync(path.join(UI, "navigation.json"), "utf8")).places.flatMap((p) => p.screens.map((x) => x.screen)))];
for (const screenId of navScreens.filter((id) => !(id in a11yMeasured))) {
  await page.evaluate((id) => window.__npdevShowScreen(id), screenId);
  await page.waitForTimeout(250);
  await axeScan(screenId);
}

// ---- NAV-4: Set up disappears once the computer is ready; Ready rows get a Fix button ----------------------
const placeIds = () => page.$$eval(".tabs .places .place", (b) => b.map((x) => x.dataset.place));
await page.evaluate(async () => { window.__scenario = "doctor-missing-java"; await loadDoctor(); });
await page.waitForTimeout(250);
check("a computer with something missing keeps Set up in the bar", (await placeIds()).includes("setup"), (await placeIds()).join(","));
await page.evaluate(() => window.__npdevShowScreen("ready"));
await page.waitForSelector("#doctor-rows .fix-btn", { timeout: 3000 });
check("the failing Java row offers Fix", (await page.$$eval("#doctor-rows .check-row.fail .fix-btn", (b) => b.length)) === 1, "");
await page.click("#doctor-rows .check-row.fail .fix-btn");
await page.waitForTimeout(250);
check("Fix jumps to the Java step of Install and focuses its button",
  (await page.evaluate(() => window.__npdevCurrentScreen)) === "install" && (await page.evaluate(() => document.activeElement && document.activeElement.id)) === "jdk-install-btn", "");
await page.evaluate(async () => { window.__scenario = "doctor-no-jars"; await loadDoctor(); window.__npdevShowScreen("ready"); });
await page.waitForSelector("#doctor-rows .fix-btn", { timeout: 3000 });
await page.click("#doctor-rows .check-row.fail .fix-btn");
await page.waitForTimeout(250);
check("a missing-jars failure jumps to the Setup step and shows its button",
  (await page.evaluate(() => window.__npdevCurrentScreen)) === "install" && (await page.$eval("#setup-run-btn", (b) => { const r = b.getBoundingClientRect(); return r.top >= 0 && r.bottom <= window.innerHeight; })), "");
await page.evaluate(async () => { window.__scenario = "doctor-all-green"; await loadDoctor(); });
await page.waitForTimeout(250);
check("once the computer is ready Set up is gone from the bar", !(await placeIds()).includes("setup"), (await placeIds()).join(","));
await page.click('.tabs .place[data-place="settings"]');
await page.waitForTimeout(150);
const settingsTabs = await page.$$eval('.place-strip:not([hidden]) .tab', (t) => t.map((x) => x.dataset.screen));
check("...and Settings now shows This computer and Install & folders", settingsTabs.includes("ready") && settingsTabs.includes("install"), settingsTabs.join(","));
await page.evaluate(() => window.__npdevShowScreen("ready"));
await page.waitForTimeout(150);
check("Ready stays reachable inside Settings", (await page.$eval(".tabs .place.active", (b) => b.dataset.place)) === "settings", "");

// ---- NAV-5: keyboard and ARIA ---------------------------------------------------------------------------------
await page.click('.tabs .place[data-place="settings"]');
await page.waitForTimeout(150);
const aria = await page.evaluate(() => {
  const strip = document.querySelector(".place-strip:not([hidden]) [role=tablist]");
  const tabs = [...strip.querySelectorAll("[role=tab]")];
  return { label: strip.getAttribute("aria-label"), tabs: tabs.length, selected: tabs.filter((t) => t.getAttribute("aria-selected") === "true").length,
    inOrder: tabs.filter((t) => t.tabIndex === 0).length, nav: document.querySelector("nav.tabs").getAttribute("aria-label"),
    current: document.querySelector(".tabs .place.active")?.getAttribute("aria-current") };
});
check("the secondary strip is a labelled tablist with one tab selected and one in the tab order",
  !!aria.label && aria.tabs > 1 && aria.selected === 1 && aria.inOrder === 1, JSON.stringify(aria));
check("the main bar is a labelled nav and marks the current place", !!aria.nav && aria.current === "page", JSON.stringify(aria));
await page.focus('.place-strip:not([hidden]) [role=tab][tabindex="0"]');
const before = await page.evaluate(() => window.__npdevCurrentScreen);
await page.keyboard.press("ArrowRight");
await page.waitForTimeout(200);
const after = await page.evaluate(() => ({ screen: window.__npdevCurrentScreen, focus: document.activeElement.dataset.screen }));
check("ArrowRight moves to the next tab and opens it", after.screen !== before && after.focus === after.screen, `${before} -> ${JSON.stringify(after)}`);
await page.keyboard.press("End");
await page.waitForTimeout(200);
const last = await page.evaluate(() => ({ screen: window.__npdevCurrentScreen, isLast: document.activeElement === [...document.querySelectorAll(".place-strip:not([hidden]) [role=tab]")].filter((t) => t.offsetParent).pop() }));
check("End jumps to the last tab", last.isLast, JSON.stringify(last));
await page.keyboard.press("Home");
await page.waitForTimeout(200);
check("Home jumps back to the first tab", (await page.evaluate(() => document.activeElement === [...document.querySelectorAll(".place-strip:not([hidden]) [role=tab]")].filter((t) => t.offsetParent)[0])), "");
check("Skip to content comes before everything else in the page",
  await page.evaluate(() => { const first = document.querySelector("a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])"); return first && first.id === "skip-link"; }), "");
await page.focus("#skip-link");
await page.keyboard.press("Enter");
await page.waitForTimeout(100);
check("activating it moves focus into the page content", (await page.evaluate(() => document.activeElement && document.activeElement.id)) === "main-content", "");

// ---- JOBS-3: Activity > Tasks ----------------------------------------------------------------------------------
await page.click('.tabs .place[data-place="activity"]');
await page.click('.place-strip:not([hidden]) .tab[data-screen="jobs"]');
await page.waitForSelector("#jobs-root .jobs-table", { timeout: 3000 });
const jobRows = await page.$$eval("#jobs-root tbody tr", (r) => r.map((x) => ({ id: x.dataset.job, text: x.textContent, lost: x.classList.contains("lost"),
  buttons: [...x.querySelectorAll("button")].map((b) => b.textContent) })));
check("the board lists every recorded task", jobRows.length === 4, `${jobRows.length}`);
const lostRow = jobRows.find((r) => /rebuild/i.test(r.id));
check("a lost task is marked in words and by row, and says what it means", lostRow && lostRow.lost && /LOST/.test(lostRow.text) && /Nothing is watching/.test(lostRow.text), lostRow && lostRow.text.slice(0, 120));
const gate = jobRows.find((r) => /gates/.test(r.id));
check("a task started by an AI agent says so and shows its last news", /AI agent/.test(gate.text) && /run-runtimehost-gate/.test(gate.text) && /6m 30s/.test(gate.text), gate.text.slice(0, 160));
check("a running task offers Stop and Open log, not Restart", gate.buttons.includes("Stop") && gate.buttons.includes("Open log") && !gate.buttons.includes("Restart"), gate.buttons.join(","));
const ci = jobRows.find((r) => /bench/.test(r.id));
check("a task started by CI offers only Open log", ci.buttons.join(",") === "Open log", ci.buttons.join(","));
await page.click('tr[data-job$="gates"] button.destructive');
await page.waitForTimeout(200);
check("Stop asks the CLI to stop exactly that task", (await page.evaluate(() => window.__jobsActions)).join() === "stop:20261103T100000Z-gates", "");
check("this window's own operations are listed too", /install Java/.test(await page.textContent("#jobs-root .jobs-here")), "");
// polling happens only while the screen is the one being looked at
const calls1 = await page.evaluate(() => window.__jobsListCalls);
await page.evaluate(() => window.__npdevShowScreen("monitor"));
await page.waitForTimeout(100);
await page.evaluate(() => NPDevJobs.refresh(false));
check("a refresh while another screen is showing does nothing", (await page.evaluate(() => window.__jobsListCalls)) === calls1, "");
check("it polls every 10 seconds", (await page.evaluate(() => NPDevJobs.pollMs)) === 10000 && (await page.evaluate(() => NPDevJobs.isPolling())), "");
await page.evaluate(() => { window.__jobsEmpty = true; window.__npdevShowScreen("jobs"); });
await page.waitForTimeout(250);
check("no tasks yet says how to get some", /run_digest/.test(await page.textContent("#jobs-root")), "");
await page.evaluate(() => { window.__jobsEmpty = false; });

// ---- The New-app database card (dbsetup.js) -------------------------------------------------------------
// The card answers "where will the database run?" for the engine picked, by itself: what already runs here,
// whether Docker is usable, and the one next action -- use it, start it, create one, or fix Docker.
// Every state is driven through the real window against the CLI's own `db detect` shape (db-detect.json);
// only the Docker block of that object is varied.
{
  const detectFixture = fixture("db-detect.json");
  const withDocker = (docker, databases = detectFixture.databases) => Object.assign({}, detectFixture, { docker, databases });
  const DOCKER_OK = { status: "ok", detail: null, installed: true };
  const DOCKER_OFF = { status: "unavailable", detail: "error during connect: the system cannot find the file specified", installed: true };
  const DOCKER_MISSING = { status: "unavailable", detail: "docker is not on PATH", installed: false };
  const cardText = async () => (await page.textContent("#db-setup-card")).replace(/\s+/g, " ").trim();
  const settle = () => page.waitForTimeout(200);
  const pick = async (key) => { await page.selectOption("#new-app-engine", key); await settle(); };
  const setDetect = (d) => page.evaluate((x) => { window.__detect = x; }, d);

  await page.evaluate((engines) => { window.__engines = engines; }, fixture("engines.json").engines);
  await page.evaluate(async () => { await loadEngines(); window.__npdevShowScreen("apps"); NPDevDbSetup.setPollMs(120); });
  await settle();

  check("db card: an engine that needs no server shows nothing", (await cardText()) === "", await cardText());

  // -- Docker running, a Postgres is up, the only MySQL is a stopped container with no published port
  await setDetect(withDocker(DOCKER_OK));
  const engineLocal = await page.evaluate(async () => {
    await refreshEngineMatrix();
    const el = document.querySelector('.engine-local[data-engine="postgres"]');
    return el ? { text: el.textContent, title: el.title } : null;
  });
  check("engine panel: each engine says in one plain sentence whether it is usable here, the CLI detail is its tooltip",
    engineLocal && engineLocal.text === "Already running on this computer." && /localhost:15432/.test(engineLocal.title), JSON.stringify(engineLocal));
  await pick("mysql");
  let t = await cardText();
  check("db card: MySQL says plainly that no MySQL server is running", /No MySQL server is running on this machine\./.test(t), t);
  check("db card: the stopped MySQL container is listed, with why it cannot be used", /old-mysql/.test(t) && /cannot be used from here: it runs inside Docker without an open port/.test(t), t);
  check("db card: with Docker running and no server, creating one is offered and ticked", (await page.isChecked("#db-setup-create"))
    && /Create a new MySQL server in Docker when I press Create/.test(t), t);
  check("db card: it says what creating does (official image, local only, generated password, first download)",
    /official image/.test(t) && /only from this computer/.test(t) && /generated password/.test(t) && /downloads the image/.test(t), t);
  await page.locator("#new-app-form").screenshot({ path: path.join(OUT, "db-card-create.png") });

  await pick("postgres");
  t = await cardText();
  check("db card: a running Postgres is announced and offered", /Found a Postgres server running on this machine\./.test(t) && (await page.locator("#db-setup-card button", { hasText: "Use this" }).count()) === 1, t);
  check("db card: with a server found, creating a new one is NOT ticked by default", !(await page.isChecked("#db-setup-create")), "");
  await page.click("#db-setup-card button:has-text('Use this')");
  await settle();
  t = await cardText();
  check("db card: Use this fills host and port and ticks 'this server is mine'",
    (await page.inputValue("#new-app-db-host")) === "localhost" && (await page.inputValue("#new-app-db-port")) === "15432"
    && (await page.isChecked("#new-app-externally-provisioned")) && /Filled in postgres at localhost:15432/.test(t), t);
  check("db card: an adopted server turns the create option off", (await page.isDisabled("#db-setup-create")) && !(await page.isChecked("#db-setup-create")), "");
  await pick("mysql");
  check("db card: switching engine does not carry another engine's server into the form",
    (await page.inputValue("#new-app-db-host")) === "" && (await page.inputValue("#new-app-db-port")) === "" && !(await page.isChecked("#new-app-externally-provisioned")),
    `${await page.inputValue("#new-app-db-host")}:${await page.inputValue("#new-app-db-port")}`);

  // -- Docker missing: the guided install
  await setDetect(withDocker(DOCKER_MISSING, []));
  await pick("sqlserver");
  t = await cardText();
  check("db card: no Docker says so, and that Docker is how NPDev makes the server",
    /Docker is not installed/.test(t) && /create a SqlServer server for you in one click/.test(t), t);
  check("db card: there is no create option while Docker is missing", (await page.locator("#db-setup-create").count()) === 0, "");
  await page.click("#db-setup-card button:has-text('Install Docker, step by step')");
  await settle();
  t = await cardText();
  check("db card: the guided install shows its four steps", ["1. Install Docker Desktop.", "2. Restart if it asks.", "3. Start Docker Desktop and accept its terms.", "4. NPDev notices by itself."].every((x) => t.includes(x)), t);
  await page.locator("#new-app-form").screenshot({ path: path.join(OUT, "db-card-install.png") });
  check("db card: step 3 is not available before Docker is installed", await page.locator("#db-setup-card li", { hasText: "3. Start Docker Desktop" }).locator("button").isDisabled(), "");
  await page.click("#db-setup-card button:has-text('Open the installer')");
  await settle();
  check("db card: Open the installer asks the Manager to run it, and says to follow the other window",
    (await page.evaluate(() => window.__installCalls)) === 1 && /follow it there/.test(await cardText()), await cardText());
  // Docker gets installed (but is off): the card notices by itself
  await setDetect(withDocker(DOCKER_OFF, []));
  await page.waitForTimeout(500);
  t = await cardText();
  check("db card: it notices the install by itself and moves on to starting Docker", /Docker is installed but not running/.test(t), t);
  // ...and then Docker comes up
  await setDetect(withDocker(DOCKER_OK, []));
  await page.waitForTimeout(500);
  check("db card: when Docker comes up it offers to create the server, ticked", (await page.locator("#db-setup-create").count()) === 1 && (await page.isChecked("#db-setup-create")), await cardText());

  // -- declined admin prompt and no winget are different, honest messages
  await setDetect(withDocker(DOCKER_MISSING, []));
  await page.evaluate(() => { window.__installError = "the administrator prompt was declined"; });
  await pick("postgres"); await pick("sqlserver");
  await page.click("#db-setup-card button:has-text('Install Docker, step by step')");
  await page.click("#db-setup-card button:has-text('Open the installer')");
  await settle();
  check("db card: a declined administrator prompt is explained, and can be retried", /administrator prompt was declined/.test(await cardText()) && (await page.locator("#db-setup-card button", { hasText: "Open the installer" }).isEnabled()), await cardText());
  await page.evaluate(() => { window.__installError = "winget was not found on this computer"; });
  await page.click("#db-setup-card button:has-text('Open the installer')");
  await settle();
  check("db card: no winget offers Docker's download page", (await page.locator("#db-setup-card button", { hasText: "Open Docker's download page" }).count()) === 1, await cardText());
  await page.click("#db-setup-card button:has-text(\"Open Docker's download page\")");
  check("db card: ...which opens docker.com", /^https:\/\/www\.docker\.com\//.test(String(await page.evaluate(() => window.__opened))), "");
  await page.evaluate(() => { window.__installError = null; });

  // -- Docker installed but off
  await setDetect(withDocker(DOCKER_OFF, []));
  await pick("postgres"); await pick("mysql");
  t = await cardText();
  check("db card: Docker installed but off says so and offers to start it", /Docker is installed but not running/.test(t) && (await page.locator("#db-setup-card button", { hasText: "Start Docker Desktop" }).count()) === 1, t);
  await page.locator("#new-app-form").screenshot({ path: path.join(OUT, "db-card-docker-off.png") });
  await page.click("#db-setup-card button:has-text('Start Docker Desktop')");
  await settle();
  check("db card: Start Docker Desktop starts it and says it is waiting", (await page.evaluate(() => window.__dockerStarted)) === 1 && /Waiting for Docker to start/.test(await cardText()), await cardText());
  await setDetect(withDocker(DOCKER_OK, []));
  await page.waitForTimeout(500);
  check("db card: once Docker answers, the wait ends and the create option appears", (await page.locator("#db-setup-create").count()) === 1 && !/Waiting for Docker/.test(await cardText()), await cardText());

  // -- a stopped container that CAN be started
  const stopped = { source: "docker", engine: "mysql", name: "dev-mysql", image: "mysql:8.4", matchedBy: "image", state: "exited", running: false, host: "localhost", port: 3307, initArgs: null, note: "container is exited, not running -- `docker start dev-mysql` brings it up" };
  await setDetect(withDocker(DOCKER_OK, [stopped]));
  await pick("postgres"); await pick("mysql");
  t = await cardText();
  check("db card: a stopped MySQL container offers Start (and not the terminal instruction)", (await page.locator("#db-setup-card button:text-is('Start')").count()) === 1 && !/docker start dev-mysql/.test(t), t);
  await page.locator("#new-app-form").screenshot({ path: path.join(OUT, "db-card-stopped.png") });
  await page.click("#db-setup-card button:text-is('Start')");
  await settle();
  check("db card: Start starts exactly that container", (await page.evaluate(() => window.__startedContainer)) === "dev-mysql", "");

  // -- a database the person installed themselves, found on its default port (no container, no name)
  const listener = { source: "listener", engine: "postgres", name: null, image: null, matchedBy: "port", state: "listening", running: true, host: "localhost", port: 5432, initArgs: ["--engine", "postgres", "--db-host", "localhost", "--db-port", "5432"], note: "something answers on 5432; NPDev has not confirmed it is postgres -- `npdev db test-connection` does" };
  await setDetect(withDocker(DOCKER_OK, [listener]));
  await pick("mysql"); await pick("postgres");
  t = await cardText();
  check("db card: a database found on its port says what is known in one sentence, with no terminal command",
    /Something is answering on port 5432, but NPDev has not confirmed it is a postgres server\./.test(t) && !/npdev db test-connection/.test(t), t);

  // -- the create flow
  await setDetect(withDocker(DOCKER_OK, []));
  await pick("postgres"); await pick("mysql");
  await page.fill("#new-app-name", "shop");
  await page.fill("#new-app-parent", "D:\\Apps");
  await page.evaluate(() => { window.__provisionCalls = []; window.__createdApp = null; });
  await page.click("#new-app-create-btn");
  await page.waitForTimeout(500);
  const made = await page.evaluate(() => ({ prov: window.__provisionCalls, app: window.__createdApp }));
  check("db card: Create first makes the container, for this engine and this app", made.prov.length === 1 && made.prov[0].engine === "mysql" && made.prov[0].appName === "shop", JSON.stringify(made.prov));
  const dbFix = fixture("db-provision.json").provisioned;
  check("db card: the app is created against the container that was made, as a server it adopts",
    made.app && made.app.dbHost === dbFix.host && made.app.dbPort === dbFix.port && made.app.dbUser === dbFix.user
    && made.app.dbPassword === dbFix.password && made.app.externallyProvisioned === true && made.app.engine === "mysql", JSON.stringify(made.app));
  t = (await page.textContent("#new-app-status")).replace(/\s+/g, " ");
  check("db card: the result names the container and says it restarts with Docker", /created shop/.test(t) && /Docker container npdev-mysql/.test(t) && /starts again with Docker/.test(t), t);
  check("db card: after creating, a second create is not ticked for the next app", !(await page.isChecked("#db-setup-create")), "");

  // -- the create flow when Docker refuses
  await setDetect(withDocker(DOCKER_OK, []));
  await pick("postgres"); await pick("mysql");
  await page.evaluate(() => {
    window.__provisionAnswer = { ok: false, error: { code: "image-pull-failed", message: "Could not download mysql:8.4: offline", fix: "Check the internet connection and that Docker Desktop is signed in if it asks, then try again." } };
    window.__provisionCalls = []; window.__createdApp = null;
  });
  await page.fill("#new-app-name", "shop2");
  await page.click("#new-app-create-btn");
  await page.waitForTimeout(500);
  t = (await page.textContent("#new-app-status")).replace(/\s+/g, " ");
  check("db card: a failed container creation creates NO app and says why and what to do",
    (await page.evaluate(() => window.__createdApp)) === null && /not created: the MySQL server could not be created/.test(t) && /Could not download mysql:8.4: offline/.test(t) && /Check the internet connection/.test(t), t);
  await page.evaluate(() => {
    window.__provisionAnswer = null;
    // the support button the failure offered is drawn once, in the language of the moment: clear it before the language checks
    const el = document.getElementById("new-app-status");
    NPDevSupport.clearHelp(el);
    el.textContent = "";
  });

  // -- without Docker: two ways for Postgres, with a "?" that says which to pick
  await setDetect(withDocker(DOCKER_MISSING, []));
  await pick("mysql"); await pick("postgres");
  t = await cardText();
  check("db card: with no Docker, Postgres offers NPDev's own copy and a Windows service", /Install Postgres without Docker:/.test(t) && t.indexOf("without Docker:") < t.indexOf("Docker is not installed")
    && /NPDev's own Postgres \(recommended\)/.test(t) && /Postgres as a Windows service/.test(t), t);
  check("db card: the choice explanation stays closed until \"?\" is pressed", !/Not sure\?/.test(t), t);
  await page.click("#db-setup-card .db-help-btn"); await settle();
  t = await cardText();
  check("db card: \"?\" explains which to choose, recommending NPDev's own copy", /Not sure\? Choose NPDev's own Postgres/.test(t)
    && (await page.getAttribute("#db-setup-card .db-help-btn", "aria-expanded")) === "true", t);
  await page.locator("#new-app-form").screenshot({ path: path.join(OUT, "db-card-no-docker.png") });
  await page.click('#db-setup-card .db-option:has-text("recommended") button'); await settle();
  check("db card: choosing NPDev's own copy marks it and says Create installs it first",
    /✓ NPDev's own Postgres/.test(await cardText()) && (await page.evaluate(() => NPDevDbSetup.createWhere())) === "private", await cardText());
  await page.fill("#new-app-name", "shop3");
  await page.evaluate(() => { window.__provisionCalls = []; window.__createdApp = null; });
  await page.click("#new-app-create-btn");
  await page.waitForTimeout(500);
  const madeNd = await page.evaluate(() => ({ prov: window.__provisionCalls, app: window.__createdApp }));
  check("db card: Create asks the CLI for NPDev's own Postgres, then creates the app on it",
    madeNd.prov.length === 1 && madeNd.prov[0].placement === "private" && madeNd.prov[0].engine === "postgres" && !!madeNd.app, JSON.stringify(madeNd.prov));
  await setDetect(withDocker(DOCKER_MISSING, []));
  await pick("postgres"); await pick("mysql");
  check("db card: MySQL without Docker says plainly it needs Docker for now, and that Postgres does not",
    /Without Docker, NPDev cannot install MySQL for you yet/.test(await cardText()) && (await page.locator("#db-setup-card .db-option").count()) === 0, await cardText());
  await page.evaluate(() => { const el = document.getElementById("new-app-status"); NPDevSupport.clearHelp(el); el.textContent = ""; });

  // -- typing a connection by hand means "use THIS server"
  await setDetect(withDocker(DOCKER_OK, []));
  await pick("postgres"); await pick("mysql");
  check("db card: starts ticked", await page.isChecked("#db-setup-create"), "");
  await page.evaluate(() => { document.getElementById("new-app-connection-details").open = true; });
  await page.fill("#new-app-db-host", "db.internal");
  check("db card: typing your own connection unticks the create option", !(await page.isChecked("#db-setup-create")), "");

  // -- every state above, in Portuguese, leaves nothing untranslated
  await page.evaluate(() => NPDevI18n.setLang("pt"));
  await page.waitForTimeout(300);
  const ptStates = [
    ["mysql", withDocker(DOCKER_OK, [])], ["mysql", withDocker(DOCKER_OK, [stopped])], ["postgres", withDocker(DOCKER_OK)],
    ["mysql", withDocker(DOCKER_OFF, [])], ["mysql", withDocker(DOCKER_MISSING, [])], ["postgres", withDocker(DOCKER_MISSING, [])],
  ];
  const ptMissing = [];
  // Only what the Apps screen is showing: the other screens are not visited yet at this point (the I18N-1 block below
  // visits them all).
  const missingOnApps = () => page.evaluate(() => {
    const shown = document.getElementById("screen-apps").textContent.replace(/\s+/g, " ");
    return NPDevI18n.missingNow().filter((x) => shown.includes(x));
  });
  for (const [key, detect] of ptStates) {
    await setDetect(detect);
    await pick("sqlserver"); await pick(key);
    if (detect.docker.installed === false) { await page.click("#db-setup-card button:has-text('Instalar o Docker')"); await settle(); }
    if (key === "postgres" && detect.docker.installed === false) { await page.click("#db-setup-card .db-help-btn"); await settle(); }
    ptMissing.push(...(await missingOnApps()));
  }
  await pick("sqlserver");
  await setDetect(withDocker(DOCKER_MISSING, []));
  await page.click("#find-databases-btn"); await settle();
  ptMissing.push(...(await missingOnApps()));
  check("db card (pt): no state leaves English on screen", ptMissing.length === 0, JSON.stringify([...new Set(ptMissing)].slice(0, 8)));
  await setDetect(withDocker(DOCKER_OK, []));
  await pick("postgres"); await pick("mysql");
  check("db card (pt): the headline and the create option read in Portuguese",
    /Nenhum servidor MySQL está rodando neste computador\./.test(await cardText()) && /Criar um novo servidor MySQL no Docker quando eu clicar em Criar/.test(await cardText()), await cardText());
  await setDetect(withDocker(DOCKER_OK));
  const ptEngine = await page.evaluate(async () => {
    await refreshEngineMatrix();
    await new Promise((r) => setTimeout(r, 300));
    const pick = (k) => document.querySelector(`.engine-local[data-engine="${k}"]`);
    return { text: pick("postgres").textContent, title: pick("postgres").title, h2title: pick("h2local").title };
  });
  check("engine panel (pt): the sentence and its CLI tooltip read in Portuguese",
    ptEngine.text === "Já está rodando neste computador." && /^já está rodando aqui \(localhost:15432\)/.test(ptEngine.title)
      && /^roda dentro do próprio processo/.test(ptEngine.h2title), JSON.stringify(ptEngine));
  await page.evaluate(() => NPDevI18n.setLang("en"));
  await page.waitForTimeout(300);
  // -- The standalone Docker assistant (Settings > Install): the same steps, reachable with no engine picked.
  await pick("h2local");
  const helpText = async () => (await page.textContent("#docker-assistant")).replace(/\s+/g, " ").trim();
  await page.evaluate(() => window.__npdevShowScreen("install"));
  await page.evaluate(async () => { await refreshJdkStatus(); await refreshPythonStatus(); });
  const stepText = async (id) => (await page.textContent(`.install-step:has(#${id})`)).replace(/\s+/g, " ");
  t = await stepText("jdk-status");
  check("java assistant: no Java says so in plain words, with one visible button", /Java is not on this computer yet\./.test(t)
    && /No permission is needed/.test(t) && (await page.isVisible("#jdk-install-btn")) && /Install Java for NPDev/.test(await page.textContent("#jdk-install-btn")), t);
  // A Java the machine already has (system) or NPDev's own copy (portable) is simply ready: no button, the
  // path only under Details. This is the state a developer machine is in; the screen must not say "not here yet".
  for (const [label, jdk] of [
    ["system", { portableInstalled: false, systemJava: "C:\\Java\\jdk-17", systemJavaVersion: "17.0.11", resolved: "system", path: null }],
    ["portable", { portableInstalled: true, systemJava: null, systemJavaVersion: null, resolved: "portable", path: "C:\\NPDev\\jdk" }],
  ]) {
    await page.evaluate(async (j) => { window.__jdk = j; await refreshJdkStatus(); }, jdk);
    t = await stepText("jdk-status");
    check(`java assistant: a ${label} Java is simply ready, with no install button`, /✓ Java is ready\./.test(t)
      && !/not on this computer yet/.test(t) && !(await page.isVisible("#jdk-install-btn")) && !(await page.isVisible("#jdk-details-text")), t);
  }
  await page.evaluate(async () => { window.__jdk = null; await refreshJdkStatus(); });
  t = await stepText("python-status");
  check("python assistant: a Python already here is simply ready, its path only under Details", /✓ Python is ready\./.test(t)
    && !(await page.isVisible("#python-install-btn")) && !(await page.isVisible("#python-details-text")), t);
  // -- Install step 3: one button downloads the NEWEST NPDev and prepares it
  await page.evaluate(async () => {
    window.__tags = [{ name: "v9.2.0", committed_at: "2026-11-01T00:00:00Z" }, { name: "v9.1.0", committed_at: "2026-10-01T00:00:00Z" }];
    window.__npdevVersion = null; window.__setupDone = false; window.__setupRuns = 0;
    await refreshTagList(false); await refreshSetupStatus();
  });
  check("npdev assistant: with no NPDev, one Install NPDev button is offered in plain words",
    (await page.isVisible("#npdev-easy-btn")) && /NPDev is not on this computer yet/.test(await page.textContent("#npdev-easy-status")), await page.textContent("#npdev-easy-status"));
  await page.click("#npdev-easy-btn");
  await page.waitForTimeout(600);
  check("npdev assistant: it downloads the newest version and then prepares it, and says so",
    (await page.evaluate(() => window.__installedTag)) === "v9.2.0" && (await page.evaluate(() => window.__setupRuns)) === 1
    && /NPDev v9\.2\.0 is installed/.test(await page.textContent("#npdev-easy-status")) && !(await page.isVisible("#npdev-easy-btn"))
    && /NPDev is prepared/.test(await page.textContent("#setup-status")), await page.textContent("#npdev-easy-status") + " | " + await page.textContent("#setup-status"));
  check("npdev assistant: a successful preparation hides its technical log", !(await page.isVisible("#setup-log")), await page.textContent("#setup-log"));
  // 0.13.20: with "Use local repo" on, setup_status reports currentVersion as "local checkout (<path>)", which step 3
  // interpolates into "NPDev {version} is installed." -- in Portuguese the label must not stay an English fragment.
  await page.evaluate(async () => { window.__npdevVersion = "local checkout (C:\\Dev\\NPDev)"; await refreshSetupStatus(); });
  await page.evaluate(() => NPDevI18n.setLang("pt")); await page.waitForTimeout(300);
  t = (await page.textContent("#npdev-easy-status")).trim();
  check("npdev assistant (pt): a local checkout is named in Portuguese, not an English fragment",
    t === "✓ O NPDev da sua cópia local (C:\\Dev\\NPDev) está instalado." && !/checkout/.test(t), t);
  await page.evaluate(() => NPDevI18n.setLang("en")); await page.waitForTimeout(300);
  t = (await page.textContent("#npdev-easy-status")).trim();
  check("npdev assistant (en): a local checkout still reads in English", t === "✓ NPDev local checkout (C:\\Dev\\NPDev) is installed.", t);
  await page.evaluate(async () => { window.__tags = []; window.__npdevVersion = null; window.__setupDone = false; await refreshTagList(false); await refreshSetupStatus(); });
  await setDetect(withDocker(DOCKER_OK, []));
  await page.evaluate(() => NPDevDbSetup.refreshAssistant()); await settle();
  t = await helpText();
  check("docker assistant: Docker running says it is ready and what that gives you", /Docker is ready\./.test(t) && /NPDev can now make that database for you/.test(t), t);
  await setDetect(withDocker(DOCKER_MISSING, []));
  await page.evaluate(() => { window.__wsl = { applicable: true, installed: false }; window.__wslInstallCalls = 0; });
  await page.evaluate(() => NPDevDbSetup.refreshAssistant()); await settle();
  t = await helpText();
  check("docker assistant: no Docker shows the guided steps at once, no extra click", /Docker is not installed on this computer\./.test(t) && t.includes("1. Install Docker Desktop.") && t.includes("4. NPDev notices by itself."), t);
  check("docker assistant: missing WSL is the first step, explained in plain words", /First: turn on WSL, the part of Windows that Docker runs on\./.test(t), t);
  await page.locator("#screen-install .install-step:has(#docker-assistant)").screenshot({ path: path.join(OUT, "docker-assistant-missing.png") });
  await page.click("#docker-assistant button:has-text('Turn on WSL')"); await settle();
  check("docker assistant: Turn on WSL opens the installer and says to restart after", (await page.evaluate(() => window.__wslInstallCalls)) === 1 && /restart the computer/.test(await helpText()), await helpText());
  await page.evaluate(() => { window.__wsl = { applicable: true, installed: true }; });
  await page.evaluate(() => NPDevDbSetup.refreshAssistant()); await settle();
  check("docker assistant: with WSL on, the WSL step is gone", !/turn on WSL/.test(await helpText()), await helpText());
  await page.evaluate(() => { window.__wsl = { applicable: true, installed: false }; });
  await page.evaluate(() => NPDevI18n.setLang("pt")); await page.waitForTimeout(300);
  const ptHelpMissing = [];
  for (const d of [withDocker(DOCKER_OK, []), withDocker(DOCKER_OFF, []), withDocker(DOCKER_MISSING, [])]) {
    await setDetect(d);
    await page.evaluate(() => NPDevDbSetup.refreshAssistant()); await settle();
    ptHelpMissing.push(...(await page.evaluate(() => {
      const shown = document.getElementById("screen-install").textContent.replace(/\s+/g, " ");
      return NPDevI18n.missingNow().filter((x) => shown.includes(x));
    })));
  }
  check("docker assistant (pt): no state leaves English on screen", ptHelpMissing.length === 0, JSON.stringify([...new Set(ptHelpMissing)].slice(0, 8)));
  await page.evaluate(() => NPDevI18n.setLang("en")); await page.waitForTimeout(300);
  await page.evaluate(() => { window.__engines = []; window.__detect = null; window.__wsl = undefined; NPDevDbSetup.stop(); window.__npdevShowScreen("apps"); });
  await page.evaluate(() => refreshEngineMatrix());
}

// ---- I18N-1: the whole window follows the language picker ---------------------------------------------------
// i18n.js translates what every screen DRAWS from ui/i18n/pt.json, so this drives the real window: switch to
// Portuguese, look at a static screen, text a script writes afterwards, and every screen's leftovers, then
// switch back. Last in the file on purpose: everything above asserts English text.
await page.evaluate(() => window.__npdevShowScreen("ready"));
await page.evaluate(() => NPDevI18n.setLang("pt"));
await page.waitForTimeout(400);
check("i18n: <html lang> follows the picker", (await page.evaluate(() => document.documentElement.lang)) === "pt", "");
check("i18n: a static button is translated", (await page.textContent("#doctor-refresh")).trim() === "Verificar de novo", await page.textContent("#doctor-refresh"));
check("i18n: a static heading is translated", /Bancos de dados/.test(await page.textContent("#screen-ready h2")), "");
check("i18n: an attribute (aria-label) is translated", (await page.getAttribute("#fake-banner", "aria-label")) === "Modo de simulação", String(await page.getAttribute("#fake-banner", "aria-label")));
await page.evaluate(() => {
  const p = document.createElement("p");
  p.id = "i18n-probe";
  p.textContent = "Run setup";
  document.getElementById("screen-ready").append(p);
});
await page.waitForTimeout(100);
check("i18n: text a script writes later is translated", (await page.textContent("#i18n-probe")) === "Executar a configuração", await page.textContent("#i18n-probe"));
await page.evaluate(() => { document.getElementById("i18n-probe").textContent = "Stop"; });
await page.waitForTimeout(100);
check("i18n: text a script REWRITES is translated again", (await page.textContent("#i18n-probe")) === "Parar", await page.textContent("#i18n-probe"));
await page.evaluate(() => { document.getElementById("i18n-probe").innerHTML = "<code>npdev doctor</code> Run setup"; });
await page.waitForTimeout(100);
check("i18n: <code> is never translated", (await page.textContent("#i18n-probe code")) === "npdev doctor", "");
// every screen, in Portuguese, then ask the engine what it could not translate
const screens = await page.evaluate(() => Array.from(document.querySelectorAll("main > section.screen")).map((s) => s.id.replace("screen-", "")));
// NPDEV_UI_TOUR=1 also saves a full-page picture of every screen, in Portuguese, under <out>/tour/ -- the
// material of a "could a non-technical person do this?" review (owner rule: the Manager is for beginners).
const tour = process.env.NPDEV_UI_TOUR === "1";
if (tour) mkdirSync(path.join(OUT, "tour"), { recursive: true });
for (const s of screens) {
  await page.evaluate((n) => window.__npdevShowScreen(n), s);
  await page.waitForTimeout(tour ? 400 : 150);
  if (tour) await page.screenshot({ path: path.join(OUT, "tour", `pt-${s}.png`), fullPage: true });
}
const untranslated = await page.evaluate(() => NPDevI18n.missingNow());
writeFileSync(path.join(OUT, "i18n-untranslated.json"), JSON.stringify(untranslated, null, 1));
check("i18n: every screen's text is in the Portuguese catalogue", untranslated.length === 0,
  `${untranslated.length} untranslated: ${JSON.stringify(untranslated.slice(0, 12))}`);
// THM-descriptions: a theme card shows its description in the UI language when the theme has one (npdev-dark has pt + es),
// and the English text otherwise (npdev-light has none).
await page.evaluate(() => window.__npdevShowScreen("appearance"));
await page.waitForTimeout(300);
const cardDesc = (id) => page.textContent(`.theme-card[data-theme="${id}"] small`);
check("i18n: a theme card shows its Portuguese description", /^O visual atual do Manager/.test(await cardDesc("npdev-dark")), String(await cardDesc("npdev-dark")));
check("i18n: a theme with no Portuguese description stays English", !/[ãçé]/.test(await cardDesc("npdev-light")), String(await cardDesc("npdev-light")));

await page.evaluate(() => NPDevI18n.setLang("en"));
await page.waitForTimeout(400);
check("i18n: back to English restores the original text", (await page.textContent("#doctor-refresh")).trim() === "Re-check"
  && (await page.getAttribute("#fake-banner", "aria-label")) === "Stub mode", "");

// ---- ACC-4: compare the measured axe counts with the floor ------------------------------------------
const IMPACTS = ["critical", "serious", "moderate", "minor"];
for (const [screenId, out] of Object.entries(a11yMeasured)) {
  const floor = a11yBaseline.screens[screenId];
  const total = IMPACTS.reduce((n, k) => n + out.counts[k], 0);
  if (!floor) {
    check(`axe: ${screenId} has a recorded floor`, process.env.NPDEV_A11Y_WRITE === "1", `${total} violation node(s) measured, no floor yet (NPDEV_A11Y_WRITE=1 records it)`);
    continue;
  }
  const worse = IMPACTS.filter((k) => out.counts[k] > floor.counts[k]);
  check(`axe: ${screenId} has no more violations than its floor`, worse.length === 0,
    `${JSON.stringify(out.counts)} vs floor ${JSON.stringify(floor.counts)}; rules ${JSON.stringify(out.rules)}`);
}
if (process.env.NPDEV_A11Y_WRITE === "1") {
  for (const [screenId, out] of Object.entries(a11yMeasured)) {
    const floor = a11yBaseline.screens[screenId];
    const lower = !floor || IMPACTS.every((k) => out.counts[k] <= floor.counts[k]);
    if (lower) a11yBaseline.screens[screenId] = { counts: out.counts, rules: out.rules };
  }
  a11yBaseline.comment = "ACC-4. Ratchet floor per Manager screen (dark theme, stub fixtures), written by scripts/quality/check-manager-ui-render.mjs with NPDEV_A11Y_WRITE=1 from a real axe-core scan. Counts are violation NODE counts per impact; the floor may only fall. Never hand-edit a count downward.";
  writeFileSync(A11Y_BASELINE_PATH, JSON.stringify(a11yBaseline, null, 2) + "\n");
}

check("no console or page errors while rendering either screen", consoleErrors.length === 0,
  consoleErrors.slice(0, 3).join(" | "));

await browser.close();
server.close();

const failedChecks = results.filter((r) => !r.ok);
for (const r of results) console.log(`  ${r.ok ? "PASS" : "FAIL"}  ${r.name}${r.detail ? "  -- " + r.detail : ""}`);
console.log(`\n${results.length - failedChecks.length}/${results.length} checks passed`);
writeFileSync(path.join(OUT, "ui-render-check.json"), JSON.stringify({ results, consoleErrors, a11y: a11yMeasured }, null, 2));
process.exit(failedChecks.length ? 1 : 0);
