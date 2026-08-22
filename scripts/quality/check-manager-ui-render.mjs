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
 * order: $PLAYWRIGHT_MODULE, then the ScrapForAI engine root that
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
  console.log("  Tried $PLAYWRIGHT_MODULE and the engine root reported by");
  console.log("  `npdev monitor engine --json`. Install one of them to run this check.");
  process.exit(0);
}
const { chromium } = await import(pathToFileURL(playwrightModule).href);


const UI = path.join(REPO, "NPDevManager", "ui");
const FIX = path.join(REPO, "NPDevManager", "fixtures");
const OUT = process.argv[2] || ".";

const fixture = (name) => JSON.parse(readFileSync(path.join(FIX, name), "utf8"));

const SHIM = `
window.__TAURI__ = {
  core: {
    convertFileSrc: (p) => "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    invoke: async (name, args) => {
      const F = window.__FIXTURES__;
      switch (name) {
        case "is_fake_mode": return true;
        case "fake_doctor_scenarios": return ["doctor-all-green"];
        case "check_doctor": return F.doctor;
        case "list_apps": return [];
        case "list_engines": return { engines: [] };
        case "jdk_status": return { installed: true, path: "x" };
        case "python_status": return { systemPython: "python" };
        case "list_tags": return [];
        case "list_installed_versions": return [];
        case "current_version": return null;
        case "get_inspect_paths": return ["D:\\\\WorkSpace\\\\NPDev\\\\Build"];
        case "set_inspect_paths": return null;
        case "monitor_scan": return F.scan;
        case "monitor_probe": case "read_info_json": return F.probe;
        case "monitor_logs": return F.logs;
        case "engine_status": return Object.assign({}, F.engineRunning, { startedByThisWindow: false });
        case "explore_list": return F.list;
        case "explore_show": return F.runRed;
        case "explore_preflight": return F.preflight;
        case "explore_run": await new Promise((r) => setTimeout(r, 2500)); return { runId: "x", verdict: { green: true } };
        case "explore_validate": return F.validateBad;
        case "assistant_config": return { configured: false };
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
const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });

const consoleErrors = [];
page.on("console", (m) => m.type() === "error" && consoleErrors.push(m.text()));
page.on("pageerror", (e) => consoleErrors.push("pageerror: " + e.message));

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
  })};`,
});

await page.goto("http://127.0.0.1:4599/");
await page.waitForTimeout(500);

const results = [];
const check = (name, ok, detail) => { results.push({ name, ok, detail }); };

// ---- The Monitor ---------------------------------------------------------------------------
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

// Logs tab
await page.click('#insp-tabs button[data-tab="logs"]');
await page.waitForSelector("#logs-export", { timeout: 5000 });
check("logs tab offers Export support bundle", true, "");
await page.click("#insp-close");
await page.waitForTimeout(500);

await page.screenshot({ path: path.join(OUT, "monitor-wall.png"), fullPage: false });

// ---- The Scrap Manager ---------------------------------------------------------------------
await page.click('.tab[data-screen="scrap"]');
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

check("no console or page errors while rendering either screen", consoleErrors.length === 0,
  consoleErrors.slice(0, 3).join(" | "));

await browser.close();
server.close();

const failedChecks = results.filter((r) => !r.ok);
for (const r of results) console.log(`  ${r.ok ? "PASS" : "FAIL"}  ${r.name}${r.detail ? "  -- " + r.detail : ""}`);
console.log(`\n${results.length - failedChecks.length}/${results.length} checks passed`);
writeFileSync(path.join(OUT, "ui-render-check.json"), JSON.stringify({ results, consoleErrors }, null, 2));
process.exit(failedChecks.length ? 1 : 0);
