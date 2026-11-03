// Unit test for theme-engine.js. Run: node theme-engine.test.cjs  (exit 1 on failure)
// Run by scripts/quality/check-themes.py (skips with a reason when Node is absent).
const fs = require("fs");
const path = require("path");
const T = require("./theme-engine.js");

let failures = 0;
function check(cond, msg) { if (!cond) { failures++; console.log("FAIL " + msg); } }

const dirs = [__dirname, path.join(__dirname, "..", "..", "NPDevSamples", "themes", "seasons")];
const catalog = {};
for (const d of dirs) {
  for (const f of fs.readdirSync(d).filter((f) => f.endsWith(".json") && f !== "theme.schema.json")) {
    const theme = JSON.parse(fs.readFileSync(path.join(d, f), "utf8"));
    catalog[theme.id] = theme;
    check(theme.id + ".json" === f, `${f}: file name must be <id>.json`);
    const r = T.validate(theme, { strict: theme.id === "high-contrast" });
    check(r.ok, `${f}: ${r.errors.join("; ")}`);
  }
}

// basedOn: a three-colour variation of summer resolves to a complete, valid theme.
const variation = { schema: "npdev-theme.v1", id: "summer-night-market", name: "Summer market", version: "1.0.0",
  colorScheme: "light", basedOn: "summer", colors: { accent: "#8a2be2", info: "#5b2a86" } };
check(T.validate(variation).ok, "variation must validate structurally");
const flat = T.resolve(variation, catalog);
check(flat.colors.bg === catalog.summer.colors.bg && flat.colors.accent === "#8a2be2", "resolve must inherit then override");
check(T.validate(flat).ok, "resolved variation must pass contrast: " + T.validate(flat).errors.join("; "));

// Hostile input is refused, not sanitised.
const hostile = JSON.parse(JSON.stringify(catalog["npdev-light"]));
hostile.colors.bg = "red; background:url(https://evil.example/x)";
hostile.typography.fontUi = "x; } body { display:none";
const h = T.validate(hostile);
check(!h.ok && h.errors.some((e) => e.includes("colors.bg")) && h.errors.some((e) => e.includes("fontUi")), "hostile values must be rejected");

// Low contrast is caught with a readable message.
const low = JSON.parse(JSON.stringify(catalog["npdev-light"]));
low.colors.textMuted = "#bbbbbb";
const l = T.validate(low);
check(!l.ok && l.errors.some((e) => e.startsWith("colors: textMuted on bg")), "low contrast must be reported per pair");

// Colour-blind-safe mode keeps every theme's status colours at 4.5:1 and actually changes them.
for (const theme of Object.values(catalog)) {
  for (const set of [theme.colors, theme.darkColors].filter(Boolean)) {
    const safe = T.cvdSafe(set);
    for (const token of ["positive", "negative"]) {
      check(T.contrastRatio(safe[token], set.surface) >= 4.5 && T.contrastRatio(safe[token], set.bg) >= 4.5,
        `${theme.id}: cvd-safe ${token} ${safe[token]} must keep 4.5:1`);
    }
  }
}
check(T.contrastRatio(T.cvdSafe(catalog["high-contrast"].colors, 7).positive, "#ffffff") >= 7, "high-contrast cvd pick must keep 7:1");
check(T.cvdSafe(catalog["npdev-light"].colors).positive !== catalog["npdev-light"].colors.positive, "cvd-safe must replace green");

// The AI prompt carries every token name and the base theme when asked for a variation.
const prompt = T.buildThemePrompt("like summer but at night", catalog.summer);
check(T.TOKENS.every((t) => prompt.includes(t)) && prompt.includes('"id": "summer"'), "prompt must list tokens and embed the base theme");

console.log(failures ? `${failures} failure(s)` : `OK: ${Object.keys(catalog).length} themes valid, basedOn, hostile input, contrast and prompt checks pass`);
process.exit(failures ? 1 : 0);
