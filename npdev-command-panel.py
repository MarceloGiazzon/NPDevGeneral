#!/usr/bin/env python3
"""
NPDev Command Panel - a local, browser-based form for running common NPDev
build / gate / CI commands and watching their output in a text block.

Std-lib only (http.server + subprocess). No third-party dependencies.

Usage:
    python npdev-command-panel.py            # serve on 127.0.0.1:8123
    python npdev-command-panel.py --port 9000
    python npdev-command-panel.py --root <repo-root>   # override repo root

Open http://127.0.0.1:8123 and click a button. Output streams into the panel.

Design notes:
- Commands are pre-registered by key (COMMANDS below) so the form can only run
  the commands in this list, never arbitrary text from the page (no injection).
- Long-running commands stream line-by-line into the page; you can Kill (terminate)
  the current process. Some tools (Gradle, pwsh) buffer output when connected to a
  pipe, so output can arrive in chunks rather than continuously.
- ANSI colour codes are stripped for clean display.
"""

import json
import os
import re
import shlex
import subprocess
import sys
import threading
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------------------
# Repo root = script directory, overridable via --root. Easiest reliable default.
# ---------------------------------------------------------------------------
ROOT = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------
# Command registry: kind + cwd + args. `defArgs` is pre-filled, editable text
# appended to the fixed args (e.g. a tag or a path) the user may change.
# ---------------------------------------------------------------------------
COMMANDS = [
    # ---- Build NPDev -------------------------------------------------------
    dict(key="setup", kind="npdev", cwd="", defArgs="", section="Build NPDev",
         label="npdev setup (build jars once)",
         args=["setup"]),
    dict(key="setup-local", kind="npdev", cwd="", defArgs="", section="Build NPDev",
         label="npdev setup --build-local (never download)",
         args=["setup", "--build-local"]),
    dict(key="maturity-check", kind="gradle", cwd="", defArgs="", section="Build NPDev",
         label="gradlew postBeta0MaturityCheck (Gradle-native gates)",
         args=["postBeta0MaturityCheck", "--no-daemon", "--console=plain"]),

    # ---- Modules: build & test ---------------------------------------------
    dict(key="dsl-check", kind="gradle", cwd="NPDevContract/dsl", defArgs="",
         section="Modules", label="DSL contract check",
         args=["check", "--no-daemon", "--console=plain"]),
    dict(key="gen-test", kind="gradle", cwd="NPDevGenerator", defArgs="",
         section="Modules", label="Generator unit + behavior tests",
         args=[":generator:test", ":generator:behaviorTest", "--no-daemon", "--console=plain"]),
    dict(key="gen-proof", kind="gradle", cwd="NPDevGenerator", defArgs="",
         section="Modules", label="Generator packaged-app proofs (serial)",
         args=[":generator:packagedProofTest", "--no-daemon", "--console=plain"]),
    dict(key="gen-gate", kind="gradle", cwd="NPDevGenerator", defArgs="",
         section="Modules", label="Generator quality gate",
         args=[":dsl:clean", "generatorQualityGate", "--no-daemon", "--console=plain"]),
    dict(key="kernel-inproc", kind="gradle", cwd="NPDevKernel", defArgs="",
         section="Modules", label="Kernel inproc adapter tests",
         args=[":kernel:test", ":adapters:audit-inproc:test", ":adapters:bulkhead-inproc:test",
               ":adapters:circuit-inproc:test", ":adapters:events-inproc:test",
               ":adapters:flowinstance-inproc:test", ":adapters:idempotency-inproc:test",
               ":adapters:messaging-http:test", ":adapters:messaging-inproc:test",
               ":adapters:notification-inproc:test", ":adapters:persistence-inproc:test",
               ":adapters:tracing-inproc:test", ":adapters:webhook-inproc:test",
               "--no-daemon", "--console=plain"]),
    dict(key="kernel-gate", kind="gradle", cwd="NPDevKernel", defArgs="",
         section="Modules", label="Kernel quality gate (all adapters)",
         args=["kernelQualityGate", "--no-daemon", "--console=plain"]),
    dict(key="runtimehost-core", kind="gradle", cwd="NPDevRuntimeHost/runtimehost-core", defArgs="",
         section="Modules", label="RuntimeHost core build",
         args=["build", "--no-daemon", "--console=plain"]),

    # ---- Export / stage RuntimeHost libs -----------------------------------
    dict(key="sync-libs", kind="pwsh", cwd="", defArgs="",
         section="Export RuntimeHost", label="Sync RuntimeHost libs (build + stage jars)",
         args=["scripts/runtimehost/sync-runtimehost-libs.ps1", "-BuildLocalJars"]),

    # ---- Generate apps -----------------------------------------------------
    dict(key="validate-model", kind="npdev", cwd="", defArgs="", section="Generate apps",
         label="Validate model (canonical demo)",
         args=["validate", "model", "NPDevContract/dsl/resources/Models/canonical-demo/model.json"]),
    dict(key="normalize-ai", kind="npdev", cwd="", defArgs="", section="Generate apps",
         label="Normalize AI model",
         args=["normalize", "ai-model", "golden-ai-scenarios/base-ai-loop/ai-model.json"]),
    dict(key="generate-app", kind="npdev", cwd="", defArgs="",
         section="Generate apps", label="Generate app (canonical demo)",
         args=["generate", "app", "--model", "NPDevContract/dsl/resources/Models/canonical-demo/model.json",
               "--config", "NPDevContract/dsl/resources/Models/canonical-demo/config.json",
               "--output", "build/npdev-generated"]),
    dict(key="dev", kind="npdev", cwd="", defArgs="",
         section="Generate apps", label="npdev dev (watch model, auto restart)",
         args=["dev"]),
    dict(key="run-app", kind="npdev", cwd="", defArgs="", section="Generate apps",
         label="npdev run app (one-shot)",
         args=["run", "app"]),
    dict(key="init-app", kind="npdev", cwd="", defArgs="../my-app",
         section="Generate apps", label="npdev init <name> (new app outside clone)",
         args=["init"]),
    dict(key="make-generate-app", kind="make", cwd="", defArgs="", section="Generate apps",
         label="make generate-app",
         args=["generate-app"]),
    dict(key="sample-generate", kind="pwsh", cwd="", defArgs="",
         section="Generate apps", label="Generate sample app (canonical-demo)",
         args=["NPDevSamples/scripts/generate-sample-app.ps1", "-SampleId", "canonical-demo"]),

    # ---- Gates & checks ----------------------------------------------------
    dict(key="all-gates", kind="pwsh", cwd="", defArgs="", section="Gates & checks",
         label="All gates (T2) - run-all-gates.ps1",
         args=["scripts/quality/run-all-gates.ps1"]),
    dict(key="all-gates-release", kind="pwsh", cwd="", defArgs="", section="Gates & checks",
         label="All gates + release (T3)",
         args=["scripts/quality/run-all-gates.ps1", "-IncludeReleaseGate"]),
    dict(key="gate-generator", kind="pwsh", cwd="", defArgs="", section="Gates & checks",
         label="Generator gate only",
         args=["scripts/quality/run-generator-gate.ps1"]),
    dict(key="gate-runtimehost", kind="pwsh", cwd="", defArgs="", section="Gates & checks",
         label="RuntimeHost gate only",
         args=["scripts/quality/run-runtimehost-gate.ps1"]),
    dict(key="gate-aiknowledge", kind="pwsh", cwd="", defArgs="", section="Gates & checks",
         label="AI knowledge gate only",
         args=["scripts/quality/run-ai-knowledge-gate.ps1"]),
    dict(key="gate-betarelease", kind="pwsh", cwd="", defArgs="", section="Gates & checks",
         label="Beta release gate only",
         args=["scripts/quality/run-beta-release-gate.ps1"]),
    dict(key="report-bootstrap", kind="npdev", cwd="", defArgs="", section="Gates & checks",
         label="npdev report bootstrap (maturity reports)",
         args=["report", "bootstrap"]),
    dict(key="verify-t0", kind="npdev", cwd="", defArgs="", section="Gates & checks",
         label="npdev verify --tier T0 (inner loop)",
         args=["verify", "--tier", "T0"]),
    dict(key="verify-t1", kind="npdev", cwd="", defArgs="", section="Gates & checks",
         label="npdev verify --tier T1 (fast gate)",
         args=["verify", "--tier", "T1"]),
    dict(key="verify-t2", kind="npdev", cwd="", defArgs="", section="Gates & checks",
         label="npdev verify --tier T2 (full)",
         args=["verify", "--tier", "T2"]),
    dict(key="verify-t3", kind="npdev", cwd="", defArgs="", section="Gates & checks",
         label="npdev verify --tier T3 (release)",
         args=["verify", "--tier", "T3"]),

    # ---- Workflows / CI (gh CLI) -------------------------------------------
    dict(key="ci-pr-gate", kind="gh", cwd="", defArgs="", section="Workflows / CI",
         label="Trigger PR gate",
         args=["workflow", "run", "npdev-pr-gate.yml"]),
    dict(key="ci-validation", kind="gh", cwd="", defArgs="", section="Workflows / CI",
         label="Trigger CI validation (nightly)",
         args=["workflow", "run", "npdev-ci-validation.yml"]),
    dict(key="ci-publish-libs", kind="gh", cwd="", defArgs="beta1.7",
         section="Workflows / CI", label="Publish runtimehost-libs for a tag",
         args=["workflow", "run", "publish-runtimehost-libs.yml", "-f", "tag=beta1.7"]),
    dict(key="ci-watch", kind="gh", cwd="", defArgs="", section="Workflows / CI",
         label="Watch latest workflow run (blocking)",
         args=["run", "watch", "--exit-status"]),
]

SECTIONS = list(dict.fromkeys(c["section"] for c in COMMANDS))
BY_KEY = {c["key"]: c for c in COMMANDS}
ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
MAX_OUT = 2_000_000  # cap buffered output chars

RUNS = {}
RUNS_LOCK = threading.Lock()


def _resolve_program(kind, cwd_abs, arg0):
    """Return the executable prefix (a list) for a command kind."""
    if kind == "npdev":
        return [sys.executable, os.path.join(ROOT, "NPDevCli", "npdev_cli.py")]
    if kind == "gradle":
        if os.name == "nt":
            bat = os.path.join(cwd_abs, "gradlew.bat")
            gp = bat if os.path.isfile(bat) else os.path.join(cwd_abs, "gradlew")
            return ["cmd", "/c", gp]
        return [os.path.join(cwd_abs, "gradlew")]
    if kind == "pwsh":
        script_abs = os.path.join(ROOT, arg0)
        return ["pwsh", "-NoProfile", "-File", script_abs]
    if kind == "gh":
        return ["gh"]
    if kind == "make":
        return ["make"] if os.name != "nt" else ["gmake"]
    raise ValueError("unknown kind %r" % kind)


def build_command(defn, extra):
    """Return (argv_list, working_dir, display_string)."""
    cwd_abs = os.path.join(ROOT, defn["cwd"]) if defn["cwd"] else ROOT
    argv = list(_resolve_program(defn["kind"], cwd_abs, defn["args"][0] if defn["kind"] == "pwsh" else ""))
    tail = list(defn["args"])
    if defn["kind"] == "pwsh":
        tail = tail[1:]  # arg0 is the script, already in the prefix
    if extra.strip():
        tail += shlex.split(extra)
    argv += tail
    display = "cd %s && %s" % (defn["cwd"] or ".", " ".join(argv))
    return argv, cwd_abs, display


def start_run(key, extra):
    defn = BY_KEY.get(key)
    if not defn:
        raise KeyError("unknown command key %r" % key)
    argv, cwd_abs, display = build_command(defn, extra)
    env = os.environ.copy()
    env.setdefault("PYTHONUNBUFFERED", "1")
    env.setdefault("PYTHONIOENCODING", "utf-8")
    proc = subprocess.Popen(
        argv, cwd=cwd_abs, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", env=env, bufsize=1)
    rid = uuid.uuid4().hex[:12]
    with RUNS_LOCK:
        RUNS[rid] = {"key": key, "label": defn["label"], "display": display,
                     "proc": proc, "out": [], "running": True, "exit": None}
    threading.Thread(target=_reader, args=(rid, proc), daemon=True).start()
    return rid


def _reader(rid, proc):
    try:
        for line in proc.stdout:
            with RUNS_LOCK:
                run = RUNS.get(rid)
                if run is None:
                    return
                run["out"].append(line)
                if len("".join(run["out"])) > MAX_OUT:
                    run["out"] = run["out"][1:]
        proc.wait()
    finally:
        with RUNS_LOCK:
            if RUNS.get(rid) is not None:
                RUNS[rid]["running"] = False
                RUNS[rid]["exit"] = proc.returncode


def stop_run(rid):
    with RUNS_LOCK:
        run = RUNS.get(rid)
        if run is None or not run["running"]:
            return False
        proc = run["proc"]
    try:
        proc.terminate()
    except Exception:
        pass
    return True


def run_state(rid):
    with RUNS_LOCK:
        run = RUNS.get(rid)
        if run is None:
            return None
        text = ANSI_RE.sub("", "".join(run["out"]))
        return {"id": rid, "label": run["label"], "display": run["display"],
                "running": run["running"], "exit": run["exit"], "output": text}


HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>NPDev Command Panel</title>
<style>
  :root { --accent:#3b82f6; --border:#e2e8f0; --bg:#f8fafc; --fg:#0f172a; }
  * { box-sizing:border-box; }
  html, body { height:100%; }
  body { font-family:ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto; margin:0; background:var(--bg); color:var(--fg); overflow:hidden; }
  header { background:#0f172a; color:#fff; padding:14px 20px; display:flex; align-items:center; gap:12px; }
  header h1 { font-size:16px; margin:0; }
  header .root { font-size:12px; color:#94a3b8; font-family:ui-monospace,Consolas,monospace; }
  main { display:grid; grid-template-columns: minmax(320px,420px) 1fr; gap:0; height:calc(100vh - 56px); overflow:hidden; }
  .panel { overflow:auto; padding:16px; border-right:1px solid var(--border); background:#fff; min-height:0; }
  .panel h2 { font-size:12px; text-transform:uppercase; letter-spacing:.08em; color:#64748b; margin:20px 0 8px; }
  .panel h2:first-child { margin-top:0; }
  .cmd { border:1px solid var(--border); border-radius:8px; padding:8px; margin-bottom:8px; }
  .cmd .row { display:flex; gap:8px; align-items:center; }
  .cmd .label { font-size:13px; flex:1; }
  .cmd input[type=text] { width:100%; margin-top:6px; padding:6px 8px; font-size:12px; border:1px solid var(--border); border-radius:6px; font-family:ui-monospace,Consolas,monospace; }
  .run { background:var(--accent); color:#fff; border:0; border-radius:6px; padding:7px 14px; font-size:13px; cursor:pointer; white-space:nowrap; }
  .run:hover { filter:brightness(1.08); }
  .run.running { background:#64748b; }
  .outwrap { display:flex; flex-direction:column; min-width:0; overflow:scroll; }
  .outtop { padding:10px 16px; border-bottom:1px solid var(--border); display:flex; align-items:center; gap:12px; background:#fff; }
  .outtop .title { font-size:13px; font-weight:600; flex:1; }
  #status { font-size:12px; padding:3px 10px; border-radius:20px; }
  #status.idle { background:#e2e8f0; color:#475569; }
  #status.run { background:#fef3c7; color:#92400e; }
  #status.ok { background:#dcfce7; color:#166534; }
  #status.fail { background:#fee2e2; color:#991b1b; }
  #kill { background:#ef4444; color:#fff; border:0; border-radius:6px; padding:7px 14px; cursor:pointer; display:none; }
  #copy, #clear { background:#e2e8f0; color:#0f172a; border:0; border-radius:6px; padding:7px 14px; cursor:pointer; }
  #copy:hover, #clear:hover { filter:brightness(0.97); }
  #out { flex:1; margin:0; overflow:auto; padding:14px 16px; white-space:pre-wrap; word-break:break-word; min-height:0;
         font-family:ui-monospace,Consolas,Menlo,monospace; font-size:12.5px; line-height:1.5; background:#0b1220; color:#cbd5e1; }
  #out .cmdline { color:#67e8f9; }
  .empty { color:#64748b; }
</style>
</head>
<body>
<header>
  <h1>NPDev Command Panel</h1>
  <span class="root" id="rootline"></span>
</header>
<main>
  <div class="panel" id="panel"></div>
  <div class="outwrap">
    <div class="outtop">
      <span class="title" id="title">No command running</span>
      <span id="status" class="idle">idle</span>
      <button id="kill">Kill</button>
      <button id="copy">Copy</button>
      <button id="clear">Clear output</button>
    </div>
    <pre id="out"><span class="empty">Pick a command on the left. Output appears here.</span></pre>
  </div>
</main>
<script>
const sections = SECTIONS_JSON;
const commands = COMMANDS_JSON;
const rootline = document.getElementById('rootline');
rootline.textContent = 'root: ' + ROOT;

const panel = document.getElementById('panel');
for (const sec of sections) {
  const h = document.createElement('h2'); h.textContent = sec; panel.appendChild(h);
  for (const c of commands.filter(x => x.section === sec)) {
    const box = document.createElement('div'); box.className = 'cmd';
    const row = document.createElement('div'); row.className = 'row';
    const lab = document.createElement('span'); lab.className = 'label'; lab.textContent = c.label;
    const btn = document.createElement('button'); btn.className = 'run'; btn.textContent = 'Run';
    btn.dataset.key = c.key; btn.dataset.label = c.label;
    row.appendChild(lab); row.appendChild(btn);
    box.appendChild(row);
    if (c.hasArgs) {
      const inp = document.createElement('input'); inp.type = 'text';
      inp.placeholder = c.label; inp.value = c.defaultArgs || '';
      inp.dataset.for = c.key; box.appendChild(inp);
    }
    panel.appendChild(box);
  }
}

const outEl = document.getElementById('out');
const titleEl = document.getElementById('title');
const statusEl = document.getElementById('status');
const killBtn = document.getElementById('kill');
let pollTimer = null;
let currentId = null;

function setStatus(kind, text) {
  statusEl.className = kind; statusEl.textContent = text;
}

function appendOutput(text, isCmdline) {
  if (outEl.querySelector('.empty')) outEl.innerHTML = '';
  const span = document.createElement('div');
  span.style.whiteSpace = 'pre-wrap';
  span.textContent = text;
  if (isCmdline) { span.className = 'cmdline'; span.style.color = '#67e8f9'; }
  outEl.appendChild(span);
  outEl.scrollTop = outEl.scrollHeight;
}

async function run(key, label) {
  if (currentId) killCurrent(true);
  const inp = document.querySelector('input[data-for="' + key + '"]');
  const extra = inp ? inp.value : '';
  outEl.innerHTML = '';
  titleEl.textContent = label;
  setStatus('run', 'running…');
  killBtn.style.display = 'inline-block';
  let id;
  try {
    const r = await fetch('/run', { method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ key, extra }) });
    const data = await r.json();
    id = data.id;
    if (data.error) throw new Error(data.error);
  } catch (e) {
    setStatus('fail', 'error'); titleEl.textContent = label + ' — start failed';
    appendOutput('ERROR: ' + e.message); currentId = null; killBtn.style.display='none'; return;
  }
  currentId = id;
  clearInterval(pollTimer);
  pollTimer = setInterval(() => poll(id, label), 700);
}

async function poll(id, label) {
  try {
    const r = await fetch('/poll/' + id);
    const s = await r.json();
    appendOutput(s.output);
    if (!s.running) {
      clearInterval(pollTimer); pollTimer = null; currentId = null;
      killBtn.style.display = 'none';
      titleEl.textContent = label + ' — exit ' + (s.exit === 0 ? '0 (passed)' : s.exit);
      setStatus(s.exit === 0 ? 'ok' : 'fail', s.exit === 0 ? 'ok' : 'failed');
      outEl.scrollTop = outEl.scrollHeight;
    }
  } catch (e) { /* transient */ }
}

function killCurrent(silent) {
  if (!currentId) return;
  clearInterval(pollTimer); pollTimer = null;
  fetch('/stop/' + currentId).catch(()=>{});
  if (!silent) { appendOutput('[killed]'); setStatus('fail', 'killed'); }
  currentId = null;
}

document.getElementById('panel').addEventListener('click', async (ev) => {
  const btn = ev.target.closest('button.run'); if (!btn) return;
  btn.disabled = true;
  await run(btn.dataset.key, btn.dataset.label);
  btn.disabled = false;
});
killBtn.addEventListener('click', () => killCurrent(false));
const copyBtn = document.getElementById('copy');
copyBtn.addEventListener('click', async () => {
  const text = outEl.innerText;
  if (!text || outEl.querySelector('.empty')) return;
  let ok = false;
  try {
    await navigator.clipboard.writeText(text);
    ok = true;
  } catch (_) {
    const ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); ok = true; } catch (_) {}
    document.body.removeChild(ta);
  }
  copyBtn.textContent = ok ? 'Copied!' : 'Copy failed';
  setTimeout(() => { copyBtn.textContent = 'Copy'; }, 1200);
});
document.getElementById('clear').addEventListener('click', () => { outEl.innerHTML=''; });
</script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj).encode("utf-8"), "application/json")

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/index.html"):
            self._send(200, HTML.encode("utf-8"), "text/html; charset=utf-8")
        elif self.path.startswith("/poll/"):
            rid = self.path.split("/")[-1]
            s = run_state(rid)
            if s is None:
                self._json(404, {"error": "unknown run", "running": False, "output": ""})
            else:
                self._json(200, s)
        else:
            self._send(404, b"not found", "text/plain")

    def do_POST(self):
        if self.path == "/run":
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
            try:
                rid = start_run(body.get("key", ""), body.get("extra", ""))
                self._json(200, {"id": rid})
            except KeyError as e:
                self._json(400, {"error": str(e)})
        elif self.path.startswith("/stop/"):
            rid = self.path.split("/")[-1]
            ok = stop_run(rid)
            self._json(200, {"stopped": ok})
        else:
            self._json(404, {"error": "not found"})

    def log_message(self, *args):
        pass


def main():
    global ROOT
    port = 8123
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--port" and i + 1 < len(args):
            port = int(args[i + 1]); i += 2
        elif args[i] == "--root" and i + 1 < len(args):
            ROOT = os.path.abspath(args[i + 1]); i += 2
        else:
            i += 1

    # Inject registry into the HTML before serving.
    global HTML
    HTML = (HTML.replace("SECTIONS_JSON", json.dumps(SECTIONS))
                .replace("COMMANDS_JSON", json.dumps(
                    [{"key": c["key"], "label": c["label"], "section": c["section"],
                      "hasArgs": bool(c.get("defArgs")), "defaultArgs": c.get("defArgs", "")}
                     for c in COMMANDS]))
                .replace("const rootline = document.getElementById('rootline');\nrootline.textContent = 'root: ' + ROOT;",
                         "const ROOT = %s; const rootline = document.getElementById('rootline');\nrootline.textContent = 'root: ' + ROOT;" % json.dumps(ROOT)))

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    url = "http://127.0.0.1:%d" % port
    print("NPDev Command Panel")
    print("  URL   : %s" % url)
    print("  root  : %s" % ROOT)
    print("  press Ctrl+C to stop")
    print("Opening browser…")
    try:
        webbrowser.open(url)
    except Exception:
        pass
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()