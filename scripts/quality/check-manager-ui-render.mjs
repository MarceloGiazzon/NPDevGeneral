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
        case "studio_history_list": return [];
        case "jobs_list": window.__jobsListCalls = (window.__jobsListCalls || 0) + 1; return window.__jobsEmpty ? { ok: true, jobs: [], summary: {} } : F.jobs;
        case "jobs_action": window.__jobsActions = (window.__jobsActions || []).concat([args.action + ":" + args.jobId]); return { ok: true };
        case "recent_operations": return [{ operation: "install-java", intent: "install Java (JDK 17)", startedAt: "2026-11-03T10:00:00Z", endedAt: "2026-11-03T10:01:00Z", running: false, ok: false }];
        case "list_apps": return [{ name: "auxscreen", directory: "D:\\\\WorkSpace\\\\NPDev\\\\Build\\\\generated-finalapps\\\\auxscreen", created_at: "2026-11-03" }];
        case "list_engines": return { engines: [] };
        case "jdk_status": return { installed: true, path: "x" };
        case "python_status": return { systemPython: "python" };
        case "list_tags": return [];
        case "list_installed_versions": return [];
        case "current_version": return null;
        case "get_inspect_paths": return ["D:\\\\WorkSpace\\\\NPDev\\\\Build"];
        case "set_inspect_paths": return null;
        case "monitor_scan": return window.__scanEmpty ? F.scanEmpty : F.scan;
        case "monitor_probe": case "read_info_json": return F.probe;
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
        default: return {};
      }
    },
  },
  event: { listen: async () => () => {} },
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
    support: fixture("support-install-failed.json"),
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
await page.selectOption("#lang-picker", "en");
await page.evaluate(async () => { NPDevHome.reset(); await window.__npdevShowScreen("home"); await NPDevHome.refresh(); });
await page.screenshot({ path: path.join(OUT, "home.png"), fullPage: false });

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
const WS_PICKERS = { run: "#run-app-picker", studio: "#studio-app-picker", prompter: "#prompter-app", dbio: "#dbio-app-picker", hosting: "#hosting-app-picker",
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
await page.click("#workspace-bar .ws-change");
await page.waitForTimeout(150);
check("Change app returns to the list", (await page.evaluate(() => window.__npdevCurrentScreen)) === "apps", "");
await page.click('.tabs .place[data-place="settings"]');
await page.waitForTimeout(150);
check("outside the workspace the pickers come back", (await page.evaluate(() => document.querySelector("main").getAttribute("data-in-workspace"))) === null, "");
await page.screenshot({ path: path.join(OUT, "workspace.png"), fullPage: false });

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
for (const s of screens) { await page.evaluate((n) => window.__npdevShowScreen(n), s); await page.waitForTimeout(150); }
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
