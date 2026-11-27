"""The AI results page: every recorded AI-bench run as ONE self-contained HTML file (no server, no network).

`run_bench.py matrix` collects the rows (runs, one item per prompt or pack, the packs the workshop holds, the official
limits) and calls `render_html`; this module only turns that data into a page. It reads no file and writes none. The
rows carry outcomes, stages, token counts, times and the checker's first line per pack, never a model's answer or a log.

The page answers, for every arm (apps from a prompt, apps without NPDev, the chat relay, pack authoring), provider,
model and effort: how many prompts or packs were tried, how many passed, how many ended in the provider's quota or
timeout instead of in anything the model wrote (`infra`), what it cost in tokens and seconds, and which model got
which pack or prompt through. Stdlib only.
"""

from __future__ import annotations

import json

ARM_LABELS = {
    "npdev": "App from a prompt (NPDev model.json)",
    "pack-slice": "Pack authoring (one slice per message)",
    "relay": "Chat relay (a chat AI answers through the Manager)",
}


def arm_label(arm: str) -> str:
    if arm in ARM_LABELS:
        return ARM_LABELS[arm]
    if arm.startswith("direct-"):
        return "App without NPDev (direct code)"
    return arm


def embed(data: dict) -> str:
    """JSON for a <script type="application/json"> element: '</' must not end the element early, and '<!--' must not open the
    parser's "script data escaped" state (after '<!--' a '<script' in the data would keep '</script>' from ever closing it);
    both are replaced by JSON escapes the page's own JSON.parse reads back unchanged."""
    return (json.dumps(data, ensure_ascii=False).replace("</", "<\\/").replace("<!--", "\\u003c!--")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))


def render_html(data: dict) -> str:
    return PAGE.replace("__DATA__", embed(data))


PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NPDev: what AI models built with NPDev</title>
<style>
  :root { color-scheme: light dark; --bg:#fafaf8; --fg:#1f2328; --mute:#667; --line:#d8d8d2; --card:#fff; --accent:#1d5fbf;
          --ok:#1b7f3b; --okbg:#dff3e4; --warn:#8a5a00; --warnbg:#fbeccb; --bad:#a3262a; --badbg:#f9dcdc; --infra:#555; --infrabg:#e6e6e6; }
  @media (prefers-color-scheme: dark) { :root { --bg:#16171a; --fg:#e6e6e3; --mute:#9a9aa3; --line:#34363c; --card:#1f2024; --accent:#7fb0ff;
          --ok:#7fd49b; --okbg:#1f3a2a; --warn:#e8c26a; --warnbg:#3b3014; --bad:#f19a9d; --badbg:#3d1f21; --infra:#b5b5bd; --infrabg:#2c2d33; } }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif; }
  header { padding:18px 24px 8px; } h1 { margin:0 0 4px; font-size:20px; } .sub { color:var(--mute); }
  .bar { display:flex; flex-wrap:wrap; gap:8px 12px; padding:8px 24px 12px; align-items:center; border-bottom:1px solid var(--line); }
  .bar label { color:var(--mute); font-size:12px; } select, input[type=search] { background:var(--card); color:var(--fg); border:1px solid var(--line); border-radius:6px; padding:5px 8px; font:inherit; }
  nav { display:flex; gap:4px; padding:10px 24px 0; flex-wrap:wrap; } nav button { background:none; border:1px solid transparent; border-bottom:none; padding:7px 14px; border-radius:8px 8px 0 0; color:var(--mute); font:inherit; cursor:pointer; }
  nav button[aria-selected=true] { background:var(--card); border-color:var(--line); color:var(--fg); font-weight:600; }
  main { padding:0 24px 40px; } section { background:var(--card); border:1px solid var(--line); border-radius:0 8px 8px 8px; padding:14px; overflow-x:auto; }
  table { border-collapse:collapse; width:100%; } th, td { text-align:left; padding:6px 10px; border-bottom:1px solid var(--line); vertical-align:top; white-space:nowrap; }
  td.wrap { white-space:normal; min-width:220px; } th { font-size:12px; color:var(--mute); cursor:pointer; user-select:none; position:sticky; top:0; background:var(--card); }
  th[aria-sort=ascending]::after { content:" \25B2"; } th[aria-sort=descending]::after { content:" \25BC"; } td.num, th.num { text-align:right; font-variant-numeric:tabular-nums; }
  .pill { display:inline-block; padding:1px 8px; border-radius:10px; font-size:12px; } .ok { background:var(--okbg); color:var(--ok); } .warn { background:var(--warnbg); color:var(--warn); }
  .bad { background:var(--badbg); color:var(--bad); } .infra { background:var(--infrabg); color:var(--infra); } .none { color:var(--mute); }
  .cards { display:flex; flex-wrap:wrap; gap:10px; margin:0 0 12px; } .card { border:1px solid var(--line); border-radius:8px; padding:8px 14px; min-width:120px; } .card b { display:block; font-size:20px; }
  .note { color:var(--mute); font-size:12px; margin:8px 0; } h2 { font-size:15px; margin:18px 0 6px; } tr.click { cursor:pointer; } tr.click:hover td { background:color-mix(in srgb, var(--accent) 8%, transparent); }
  .bar2 { display:inline-block; height:8px; border-radius:4px; background:var(--ok); vertical-align:middle; min-width:1px; } .track { display:inline-block; width:70px; background:var(--infrabg); border-radius:4px; margin-right:6px; }
  td.cell { text-align:center; min-width:74px; } .empty { padding:24px; color:var(--mute); }
</style>
</head>
<body>
<header><h1>What AI models built with NPDev</h1><div class="sub" id="sub"></div></header>
<div class="bar">
  <label>Kind of work <select id="fArm"></select></label>
  <label>Provider <select id="fProv"></select></label>
  <label>Search <input type="search" id="fText" placeholder="model, pack, prompt, stage"></label>
  <span class="note" id="count"></span>
</div>
<nav id="tabs" role="tablist"></nav>
<main><section id="view" role="tabpanel" tabindex="0"></section></main>
<script type="application/json" id="data">__DATA__</script>
<script>
"use strict";
const DATA = JSON.parse(document.getElementById("data").textContent);
const $ = (id) => document.getElementById(id);
function el(tag, attrs, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) { if (v === false || v == null) continue; if (k === "class") n.className = v; else if (k === "text") n.textContent = v; else if (k.startsWith("on")) n.addEventListener(k.slice(2), v); else n.setAttribute(k, v === true ? "" : v); }
  for (const kid of kids.flat()) if (kid != null) n.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return n;
}
const state = { tab: "summary", arm: "", prov: "", text: "", sort: {} };
const median = (xs) => { if (!xs.length) return null; const s = [...xs].sort((a, b) => a - b), m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const fmtTok = (n) => n == null ? "" : n >= 1000 ? (n / 1000).toFixed(n >= 10000 ? 0 : 1) + "k" : String(Math.round(n));
const fmtSec = (n) => n == null ? "" : n >= 120 ? (n / 60).toFixed(1) + " min" : Math.round(n) + " s";
const modelKey = (r) => [r.provider, r.model, r.effort && r.effort !== "default" ? r.effort : ""].filter(Boolean).join(" / ");
const day = (s) => (s || "").slice(0, 10);

function visible(r) {
  if (state.arm && r.arm !== state.arm) return false;
  if (state.prov && r.provider !== state.prov) return false;
  if (state.text) { const hay = Object.values(r).filter((v) => typeof v === "string" || typeof v === "number").join(" ").toLowerCase(); if (!hay.includes(state.text.toLowerCase())) return false; }
  return true;
}
const items = () => DATA.items.filter(visible);
const runs = () => DATA.runs.filter(visible);

function pill(outcome, text) { const c = outcome === "pass" ? "ok" : outcome === "infra" ? "infra" : outcome === "fail" ? "bad" : "warn"; return el("span", { class: "pill " + c, text: text || outcome }); }

function table(cols, rows, key, opts) {
  opts = opts || {};
  const sort = state.sort[key] || opts.sort || null;
  let data = rows.slice();
  if (sort) { const c = cols.find((x) => x.id === sort.id); if (c) { const f = c.sortBy || c.get; data.sort((a, b) => { let x = f(a), y = f(b); if (x == null) x = sort.dir > 0 ? Infinity : -Infinity; if (y == null) y = sort.dir > 0 ? Infinity : -Infinity; return (x < y ? -1 : x > y ? 1 : 0) * sort.dir; }); } }
  if (!data.length) return el("div", { class: "empty", text: "Nothing matches the filters." });
  const head = el("tr", null, cols.map((c) => { const th = el("th", { class: c.num ? "num" : "", text: c.label, title: c.hint || "click to sort" }); if (sort && sort.id === c.id) th.setAttribute("aria-sort", sort.dir > 0 ? "ascending" : "descending");
    th.addEventListener("click", () => { state.sort[key] = { id: c.id, dir: sort && sort.id === c.id ? -sort.dir : (c.num ? -1 : 1) }; draw(); }); return th; }));
  const body = data.map((r) => { const tr = el("tr", { class: opts.onRow ? "click" : "" }, cols.map((c) => { const v = c.render ? c.render(r) : c.get(r); return el("td", { class: (c.num ? "num " : "") + (c.wrap ? "wrap" : "") }, v == null ? "" : v); })); if (opts.onRow) tr.addEventListener("click", () => opts.onRow(r)); return tr; });
  return el("table", null, el("thead", null, head), el("tbody", null, body));
}

function group(list, keyFn) { const m = new Map(); for (const r of list) { const k = keyFn(r); if (!m.has(k)) m.set(k, []); m.get(k).push(r); } return m; }

function summaryRows() {
  const out = [];
  for (const [, g] of group(items(), (r) => [r.arm, r.provider, r.model, r.effort].join("|"))) {
    const f = g[0], finished = g.filter((r) => r.outcome !== "infra"), passed = g.filter((r) => r.outcome === "pass");
    const infra = g.filter((r) => r.outcome === "infra"), kinds = {}; infra.forEach((r) => { const k = r.infra || "infra"; kinds[k] = (kinds[k] || 0) + 1; });
    out.push({ arm: f.arm, armLabel: f.armLabel, provider: f.provider, model: f.model, effort: f.effort, access: f.access, tried: g.length, finished: finished.length, passed: passed.length,
      rate: finished.length ? passed.length / finished.length : null, first: finished.length ? finished.filter((r) => r.firstAttemptPassed).length / finished.length : null,
      infra: infra.length, infraText: Object.entries(kinds).map(([k, v]) => k + " x" + v).join(", "), tokens: median(passed.map((r) => r.tokens).filter((x) => x != null)),
      secs: median(passed.map((r) => r.seconds).filter((x) => x != null)), runs: new Set(g.map((r) => r.run)).size, last: g.map((r) => r.startedAt || "").sort().pop() });
  }
  return out;
}

function viewSummary(box) {
  const its = items(), rs = summaryRows();
  box.append(el("div", { class: "cards" },
    [["Runs", runs().length], ["Items (prompts and packs)", its.length], ["Passed", its.filter((r) => r.outcome === "pass").length], ["Failed on the model's work", its.filter((r) => r.outcome === "fail").length],
     ["Stopped by quota, timeout or outage", its.filter((r) => r.outcome === "infra").length], ["Models", new Set(its.map((r) => r.provider + r.model)).size]].map(([l, v]) => el("div", { class: "card" }, el("b", { text: v }), l))));
  box.append(el("div", { class: "note", text: "Pass rate counts only the items the model really attempted: a provider quota, timeout or outage (grey) is not the model's failure. Median tokens and time are over the passed items." }));
  const cols = [
    { id: "arm", label: "Kind of work", get: (r) => r.armLabel }, { id: "provider", label: "Provider", get: (r) => r.provider }, { id: "model", label: "Model", get: (r) => r.model, wrap: true },
    { id: "effort", label: "Effort", get: (r) => (r.effort && r.effort !== "default" ? r.effort : "default") },
    { id: "pass", label: "Passed", num: true, hint: "passed / attempted by the model", sortBy: (r) => r.rate, render: (r) => r.rate == null ? el("span", { class: "none", text: "no finished item" }) :
      el("span", null, el("span", { class: "track" }, el("span", { class: "bar2", style: "width:" + Math.round(r.rate * 70) + "px" })), r.passed + "/" + r.finished + " (" + Math.round(r.rate * 100) + "%)") },
    { id: "first", label: "First try", num: true, hint: "passed without a repair round", sortBy: (r) => r.first, render: (r) => r.first == null ? "" : Math.round(r.first * 100) + "%" },
    { id: "infra", label: "Stopped (not the model)", num: true, sortBy: (r) => r.infra, render: (r) => r.infra ? el("span", { class: "pill infra", text: r.infraText }) : "" },
    { id: "tokens", label: "Median tokens", num: true, sortBy: (r) => r.tokens, render: (r) => fmtTok(r.tokens) }, { id: "secs", label: "Median time", num: true, sortBy: (r) => r.secs, render: (r) => fmtSec(r.secs) },
    { id: "runs", label: "Runs", num: true, get: (r) => r.runs }, { id: "last", label: "Last run", get: (r) => day(r.last) }];
  box.append(table(cols, rs, "summary", { sort: { id: "last", dir: -1 } }));
}

function viewSubjects(box) {
  const its = items();
  if (!its.length) { box.append(el("div", { class: "empty", text: "Nothing matches the filters." })); return; }
  const models = [...new Set(its.map((r) => r.provider + " / " + r.model + (r.effort && r.effort !== "default" ? " / " + r.effort : "")))].sort();
  box.append(el("div", { class: "note", text: "One row per prompt or pack, one column per model. Green: passed (n passes / n tries). Red: tried and failed (the stage it stopped at). Grey: only quota, timeout or outage. A blank cell: not tried." }));
  const bySubject = group(its, (r) => r.armLabel + "\u0000" + r.subject);
  const head = el("tr", null, el("th", { text: "Kind of work" }), el("th", { text: "Prompt or pack" }), el("th", { text: "Title" }), models.map((m) => el("th", { text: m, title: m, style: "white-space:normal;min-width:90px" })));
  const rows = [...bySubject.entries()].sort().map(([k, g]) => {
    const f = g[0], cells = models.map((m) => {
      const mine = g.filter((r) => r.provider + " / " + r.model + (r.effort && r.effort !== "default" ? " / " + r.effort : "") === m);
      if (!mine.length) return el("td", { class: "cell none", text: "" });
      const p = mine.filter((r) => r.outcome === "pass").length, fl = mine.filter((r) => r.outcome === "fail"), inf = mine.filter((r) => r.outcome === "infra").length;
      const stage = fl.length ? (fl[fl.length - 1].reached || fl[fl.length - 1].stage || "fail") : "";
      const kind = p ? "ok" : fl.length ? "bad" : "infra", label = p ? p + "/" + mine.length : fl.length ? stage : "infra";
      return el("td", { class: "cell" }, el("span", { class: "pill " + kind, text: label, title: mine.map((r) => day(r.startedAt) + " " + r.outcome + (r.stage ? " at " + r.stage : "") + (r.infra ? " (" + r.infra + ")" : "") + ", " + (r.loops ?? "?") + " loops, " + fmtTok(r.tokens) + " tokens").join("\n") }));
    });
    return el("tr", null, el("td", { text: f.armLabel }), el("td", { text: f.subject }), el("td", { class: "wrap", text: f.title || "" }), cells);
  });
  box.append(el("table", null, el("thead", null, head), el("tbody", null, rows)));
}

function itemCols() {
  return [{ id: "startedAt", label: "When", get: (r) => (r.startedAt || "").replace("T", " ").slice(0, 16) }, { id: "armLabel", label: "Kind of work", get: (r) => r.armLabel },
    { id: "subject", label: "Prompt or pack", get: (r) => r.subject }, { id: "title", label: "Title", wrap: true, get: (r) => r.title || "" },
    { id: "model", label: "Provider / model / effort", get: (r) => modelKey(r) }, { id: "access", label: "Access", get: (r) => r.access || "" },
    { id: "outcome", label: "Result", get: (r) => r.outcome, render: (r) => pill(r.outcome, r.outcome === "infra" ? "infra: " + (r.infra || "stopped") : r.outcome) },
    { id: "stage", label: "Stopped at", get: (r) => r.outcome === "pass" ? "" : (r.stage || ""), render: (r) => r.outcome === "pass" ? "" : (r.stage || "") },
    { id: "reached", label: "Reached", get: (r) => r.reached || "" }, { id: "loops", label: "Repair rounds", num: true, get: (r) => r.loops },
    { id: "tokens", label: "Tokens", num: true, get: (r) => r.tokens, render: (r) => fmtTok(r.tokens) }, { id: "seconds", label: "Time", num: true, get: (r) => r.seconds, render: (r) => fmtSec(r.seconds) }];
}
function viewItems(box) { box.append(el("div", { class: "note", text: "Every prompt or pack ever tried, one row each." }), table(itemCols(), items(), "items", { sort: { id: "startedAt", dir: -1 } })); }

function viewRuns(box) {
  const cols = [{ id: "startedAt", label: "Started", get: (r) => (r.startedAt || "").replace("T", " ").slice(0, 16) }, { id: "armLabel", label: "Kind of work", get: (r) => r.armLabel },
    { id: "model", label: "Provider / model / effort", get: (r) => modelKey(r) }, { id: "access", label: "Access", get: (r) => r.access || "" },
    { id: "pass", label: "Passed", num: true, get: (r) => r.passed, render: (r) => r.passed + "/" + r.prompts }, { id: "completed", label: "Attempted", num: true, get: (r) => r.completed },
    { id: "tok", label: "Median tokens / pass", num: true, get: (r) => r.medianTokensPerPassedApp, render: (r) => fmtTok(r.medianTokensPerPassedApp) }, { id: "wall", label: "Wall", num: true, get: (r) => r.wallSeconds, render: (r) => fmtSec(r.wallSeconds) },
    { id: "score", label: "Score", num: true, hint: DATA.scoreFormula, get: (r) => r.score }, { id: "obs", label: "Notes", wrap: true, get: (r) => (r.observations || []).join("; ") }, { id: "runId", label: "Run", get: (r) => r.runId }];
  box.append(el("div", { class: "note", text: "Click a run to see its items. Score: " + DATA.scoreFormula + ". Full execution logs: " + DATA.logsDir }), table(cols, runs(), "runs", { sort: { id: "startedAt", dir: -1 }, onRow: (r) => { state.tab = "items"; state.text = r.runId; $("fText").value = r.runId; draw(); } }));
}

function viewLimits(box) {
  const L = DATA.limits || {};
  box.append(el("h2", { text: "Chat AIs used in a browser: official limits" }), el("div", { class: "note", text: L.comment || "" }));
  box.append(table([{ id: "provider", label: "Provider", get: (r) => r.provider }, { id: "plan", label: "Plan", get: (r) => r.plan }, { id: "model", label: "Model", get: (r) => r.model }, { id: "effort", label: "Effort", get: (r) => r.effort || "" },
    { id: "ctx", label: "Context", get: (r) => r.contextTokens }, { id: "up", label: "Uploads", wrap: true, get: (r) => r.uploads }, { id: "ans", label: "Answer size", get: (r) => r.answerSize }, { id: "q", label: "Free quota", wrap: true, get: (r) => r.freeQuota },
    { id: "src", label: "Source", wrap: true, get: (r) => r.source }], L.rows || [], "limits"));
  const measured = (L.rows || []).flatMap((r) => (r.measured || []).map((m) => ({ who: [r.provider, r.plan, r.model, r.effort].filter(Boolean).join(" / "), what: m })));
  box.append(el("h2", { text: "Measured by hand in a browser chat" }));
  box.append(measured.length ? table([{ id: "who", label: "Chat", get: (r) => r.who }, { id: "what", label: "What happened", wrap: true, get: (r) => r.what }], measured, "measured") : el("div", { class: "empty", text: "None recorded." }));
  box.append(el("h2", { text: "API models (NPDevCli/ai-providers.json)" }));
  box.append(table([{ id: "provider", label: "Provider", get: (r) => r.provider }, { id: "model", label: "Model", get: (r) => r.model }, { id: "eff", label: "Effort levels", get: (r) => (r.effortLevels || []).join(", ") },
    { id: "ctx", label: "Context tokens", num: true, get: (r) => r.contextTokens }, { id: "out", label: "Max output tokens", num: true, get: (r) => r.maxOutputTokens }, { id: "free", label: "Free", get: (r) => (r.free ? "yes" : "no") }], L.apiModels || [], "api"));
}

const RUNG_CLASS = { probed: "ok", secure: "ok", composes: "warn", valid: "warn", delivered: "bad" };
function viewPacks(box) {
  const all = (DATA.packs || []).filter((r) => !state.text || Object.values(r).join(" ").toLowerCase().includes(state.text.toLowerCase()));
  const count = {}; all.forEach((r) => { const k = r.rejected ? "rejected" : r.rung; count[k] = (count[k] || 0) + 1; });
  box.append(el("div", { class: "cards" }, ["probed", "secure", "composes", "valid", "delivered", "rejected"].filter((k) => count[k]).map((k) => el("div", { class: "card" }, el("b", { text: count[k] }), k === "rejected" ? "rejected at some rung" : "at rung: " + k))));
  box.append(el("div", { class: "note", text: "Every pack version the workshop received, with the highest rung its newest test reached: valid (shape) < composes < secure (access rules lint) < probed (a booted app with behaviour probes). A version a later one replaced stays here, as a record of what the AI delivered. Who: the AI that delivered it; a 'Claude' note marks a fix made on this side." }));
  box.append(table([{ id: "pack", label: "Pack", get: (r) => r.pack + "@" + r.version }, { id: "area", label: "Area", get: (r) => r.area },
    { id: "rung", label: "Reached", get: (r) => r.rung, render: (r) => el("span", { class: "pill " + (RUNG_CLASS[r.rung] || "warn"), text: r.rung }) },
    { id: "stop", label: "Stopped at", get: (r) => r.rejected ? r.stoppedAt : "", render: (r) => r.rejected ? el("span", { class: "pill bad", text: r.stoppedAt || "rejected" }) : "" },
    { id: "author", label: "Delivered by", get: (r) => [r.authorKind, r.author].filter(Boolean).join(" / ") }, { id: "batch", label: "Batch", get: (r) => r.batch || "" },
    { id: "edits", label: "Edited by hand after delivery", wrap: true, get: (r) => (r.handEdits || []).join(" | "), hint: "what a person changed after the AI delivered: a probe rewritten, a line fixed" },
    { id: "note", label: "Note", wrap: true, get: (r) => r.note || "" }, { id: "fb", label: "First checker line", wrap: true, get: (r) => r.feedback || "" }], all, "packs", { sort: { id: "area", dir: 1 } }));
}

const TABS = [["summary", "By model", viewSummary], ["subjects", "By pack or prompt", viewSubjects], ["packs", "Packs built", viewPacks], ["runs", "All runs", viewRuns], ["items", "All items", viewItems], ["limits", "Providers and limits", viewLimits]];
function draw() {
  $("tabs").replaceChildren(...TABS.map(([id, label]) => el("button", { role: "tab", "aria-selected": String(state.tab === id), text: label, onclick: () => { state.tab = id; draw(); } })));
  const box = $("view"); box.replaceChildren(); (TABS.find((t) => t[0] === state.tab) || TABS[0])[2](box);
  $("count").textContent = runs().length + " runs, " + items().length + " items shown";
}
function fillSelect(sel, values, label) { sel.replaceChildren(el("option", { value: "", text: label }), ...values.map(([v, t]) => el("option", { value: v, text: t }))); }
function init() {
  $("sub").textContent = "Generated " + DATA.generatedAt + " from " + DATA.runs.length + " recorded runs (" + DATA.dateRange + "). Source: " + DATA.root;
  fillSelect($("fArm"), [...new Map(DATA.items.map((r) => [r.arm, r.armLabel]))].sort((a, b) => a[1].localeCompare(b[1])), "All kinds of work");
  fillSelect($("fProv"), [...new Set(DATA.items.map((r) => r.provider))].sort().map((p) => [p, p]), "All providers");
  $("fArm").addEventListener("change", (e) => { state.arm = e.target.value; draw(); }); $("fProv").addEventListener("change", (e) => { state.prov = e.target.value; draw(); });
  $("fText").addEventListener("input", (e) => { state.text = e.target.value; draw(); });
  draw();
}
init();
</script>
</body>
</html>
"""
