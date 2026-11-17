// Screenshot capture for `npdev pack workshop test --screens`: one pass per identity, driven by the app's own
// side navigation (so it names no concept: it shows what each role can reach, which is the point).
// Uses the Playwright that ships inside the ScrapForAI engine.
//   node pack_screens_shoot.cjs <scrapforai-root> <base-url> <out-dir> <users.json>
// users.json: [{"name": "...", "role": "...", "key": "..."}]   (the key goes to the page's storage, never to a file)
// Writes <out-dir>/*.png and <out-dir>/manifest.json; prints one summary line.
const path = require("path");
const fs = require("fs");
const [scrapRoot, base, outDir, usersFile] = process.argv.slice(2);
const { chromium } = require(path.join(scrapRoot, "node_modules", "playwright"));

const users = JSON.parse(fs.readFileSync(usersFile, "utf8"));
const KEYS = ["npdev.shell.token", "npdev.businessUi.apiKey"];
const MAX_SECTIONS = 12;                 // per identity: a pack screen set, not a crawl
const BENIGN = [/theme\.css/i, /\b401\b/, /\b403\b/, /Failed to load resource/i];

const slug = (s) => String(s).replace(/[^a-zA-Z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60);

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const manifest = [];
  const browser = await chromium.launch({ headless: true });
  let n = 0;
  const shot = async (page, user, screen, extra) => {
    const file = String(n++).padStart(2, "0") + "-" + slug(user.name) + "-" + slug(screen) + ".png";
    await page.screenshot({ path: path.join(outDir, file), fullPage: true });
    manifest.push({ user: user.name, role: user.role, screen, file, ...extra });
  };

  for (const user of users) {
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
    await ctx.addInitScript((pairs) => { for (const [k, v] of pairs) localStorage.setItem(k, v); },
      KEYS.map((k) => [k, user.key]));
    const page = await ctx.newPage();
    const errors = [];
    page.on("console", (m) => {
      if (m.type() !== "error") return;
      const text = m.text().slice(0, 200);
      if (!BENIGN.some((re) => re.test(text))) errors.push(text);
    });
    await page.goto(base + "/npdev-business-ui/", { waitUntil: "networkidle", timeout: 30000 }).catch(() => {});
    await page.waitForSelector("#sideNav", { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1200);
    await shot(page, user, "home", {});

    const links = await page.$$eval('#sideNav a[href*="#concept-"]', (as) => as.map((a) => ({
      href: a.getAttribute("href"), label: (a.textContent || "").trim().slice(0, 60) })))
      .catch(() => []);
    const seen = new Set();
    for (const link of links) {
      const hash = link.href.slice(link.href.indexOf("#"));
      if (seen.has(hash) || seen.size >= MAX_SECTIONS) continue;
      seen.add(hash);
      await page.goto(base + "/npdev-business-ui/" + hash, { waitUntil: "networkidle", timeout: 20000 }).catch(() => {});
      const id = hash.slice(1);
      const visible = await page.waitForSelector("#" + id, { state: "visible", timeout: 8000 }).then(() => true).catch(() => false);
      await page.waitForTimeout(1200);
      let rows = null;
      // data rows have several cells; the "No X yet." empty state is one row with a single cell and is not a record
      if (visible) rows = await page.$$eval("#" + id + " table tbody tr", (t) => t.filter((tr) => tr.children.length > 1).length).catch(() => null);
      await shot(page, user, link.label || id, { section: id, panelVisible: visible, rows });
    }
    manifest.push({ user: user.name, role: user.role, navLinks: links.length, consoleErrors: errors.slice(0, 10) });
    await ctx.close();
  }
  await browser.close();
  fs.writeFileSync(path.join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2));
  console.log("shots:", manifest.filter((m) => m.file).length, "identities:", users.length);
})().catch((e) => { console.error(String(e && e.stack || e).slice(0, 600)); process.exit(1); });
