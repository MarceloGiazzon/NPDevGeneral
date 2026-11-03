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
import { readFileSync, existsSync, writeFileSync } from "node:fs";
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
const OUT = process.argv[2] || ".";

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
    support: fixture("support-install-failed.json"),
    themes: BUILTIN_ORDER.map((id) => Object.assign({ builtin: true }, JSON.parse(readFileSync(path.join(REPO, "NPDevContract", "themes", id + ".json"), "utf8")))),
  })};`,
});

await page.goto("http://127.0.0.1:4599/");
await page.waitForTimeout(500);

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
// The Activity place has one screen, so its place button IS the Monitor's tab.
await page.click('.tab[data-screen="monitor"]');
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
