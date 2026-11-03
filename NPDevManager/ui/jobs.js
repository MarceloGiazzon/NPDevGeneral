// Activity > Tasks (JOBS-3): long-running work -- quality gates, rebuilds, benchmarks -- without asking anyone.
//
// The records are written by scripts/ai/run_digest.py and read by `npdev jobs` (NPDevCli/npdev_jobs.py), which
// is also what decides that a job is LOST (its writer died and nobody wrote an end). This screen only shows
// what the CLI answered and offers Stop, Restart and Open log. It polls every 10 seconds ONLY while it is the
// screen being looked at, so it costs nothing in the background.

(function () {
  "use strict";
  const invoke = (name, args) => window.__TAURI__.core.invoke(name, args);
  const t = (obj) => (window.NPDevI18n ? window.NPDevI18n.t(obj) : obj.en);
  const POLL_MS = 10000;

  const TEXT = {
    title: { en: "Tasks", pt: "Tarefas" },
    lead: {
      en: "Gates, rebuilds and benchmarks that take a while, and whether they are still alive. This page checks every 10 seconds while it is open.",
      pt: "Verificações, reconstruções e benchmarks demorados, e se ainda estão vivos. Esta página se atualiza a cada 10 segundos enquanto está aberta.",
    },
    empty: { en: "No tasks recorded yet. Long commands started through run_digest.py appear here.", pt: "Nenhuma tarefa registrada ainda. Comandos longos iniciados pelo run_digest.py aparecem aqui." },
    task: { en: "Task", pt: "Tarefa" },
    by: { en: "Started by", pt: "Iniciada por" },
    status: { en: "Status", pt: "Situação" },
    started: { en: "Started", pt: "Início" },
    time: { en: "Time", pt: "Tempo" },
    last: { en: "Last news", pt: "Última notícia" },
    actions: { en: "Actions", pt: "Ações" },
    stop: { en: "Stop", pt: "Parar" },
    restart: { en: "Restart", pt: "Reiniciar" },
    log: { en: "Open log", pt: "Abrir o log" },
    running: { en: "Running", pt: "Rodando" },
    succeeded: { en: "Done", pt: "Concluída" },
    failed: { en: "Failed", pt: "Falhou" },
    cancelled: { en: "Stopped", pt: "Parada" },
    lost: { en: "LOST", pt: "PERDIDA" },
    lostHint: {
      en: "Nothing is watching this task any more. It may still be running; check the log.",
      pt: "Nada mais está acompanhando esta tarefa. Ela ainda pode estar rodando; veja o log.",
    },
    paused: { en: "Paused", pt: "Pausada" },
    agent: { en: "AI agent", pt: "Agente de IA" },
    manager: { en: "Manager", pt: "Manager" },
    terminal: { en: "Terminal", pt: "Terminal" },
    ci: { en: "CI", pt: "CI" },
    here: { en: "Started in this window", pt: "Iniciadas nesta janela" },
    working: { en: "working", pt: "em andamento" },
    couldNot: { en: "Could not read the tasks", pt: "Não consegui ler as tarefas" },
  };
  const GLYPH = { running: "▶", succeeded: "✓", failed: "✕", cancelled: "■", lost: "!", paused: "‖" };
  const WHO = { "claude-code": TEXT.agent, manager: TEXT.manager, terminal: TEXT.terminal, ci: TEXT.ci };

  let timer = null;
  let busy = false;

  function el(tag, attrs, ...kids) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === "class") n.className = v;
      else if (k === "text") n.textContent = v;
      else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
      else n.setAttribute(k, v === true ? "" : String(v));
    }
    for (const c of kids.flat()) if (c != null && c !== false) n.append(c);
    return n;
  }

  function took(secs) {
    if (secs == null) return "";
    const s = Math.round(secs);
    if (s >= 3600) return `${Math.floor(s / 3600)}h ${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}m`;
    return s >= 60 ? `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, "0")}s` : `${s}s`;
  }

  async function act(action, job, note) {
    note.textContent = "";
    try {
      const r = await invoke("jobs_action", { action, jobId: job.jobId });
      if (r && r.ok === false) note.textContent = r.error || "";
    } catch (err) {
      note.textContent = String(err);
    }
    await refresh(true);
  }

  function row(job, note) {
    const status = job.status;
    const word = TEXT[status] ? t(TEXT[status]) : status;
    const launcher = job.launcher || {};
    const buttons = el("td", { class: "jobs-actions" });
    const can = job.actions || [];
    if (can.includes("stop") && (status === "running" || status === "paused" || status === "lost")) {
      buttons.append(el("button", { type: "button", class: "destructive", text: t(TEXT.stop), onclick: () => act("stop", job, note) }), " ");
    }
    if (can.includes("restart") && status !== "running" && status !== "paused") {
      buttons.append(el("button", { type: "button", text: t(TEXT.restart), onclick: () => act("restart", job, note) }), " ");
    }
    if (job.logPath) {
      buttons.append(el("button", { type: "button", text: t(TEXT.log), onclick: () => invoke("open_folder", { path: job.logPath }).catch((e) => (note.textContent = String(e))) }));
    }
    const statusCell = el("td", { class: `jobs-status ${status}` },
      el("span", { "aria-hidden": "true", text: GLYPH[status] || "" }), " ", word,
      status === "lost" ? el("small", { text: t(TEXT.lostHint) }) : null);
    return el("tr", { class: status === "lost" ? "lost" : "", "data-job": job.jobId },
      el("td", null, el("b", { text: job.title }), el("small", { text: (job.argv || []).join(" ").slice(0, 140) })),
      el("td", { text: t(WHO[launcher.kind] || TEXT.terminal) }),
      statusCell,
      el("td", { text: job.startedAt || "" }),
      el("td", { text: took(job.elapsedSeconds) }),
      el("td", { text: (job.progress && job.progress.lastLine) || "" }),
      buttons);
  }

  function render(list, recent) {
    const root = document.getElementById("jobs-root");
    root.textContent = "";
    const note = el("p", { class: "jobs-note", role: "status", "aria-live": "polite" });
    const jobs = (list && list.jobs) || [];
    if (!jobs.length) {
      root.append(el("p", { class: "field-hint", text: t(TEXT.empty) }));
    } else {
      const head = ["task", "by", "status", "started", "time", "last", "actions"].map((k) => el("th", { scope: "col", text: t(TEXT[k]) }));
      root.append(el("table", { class: "jobs-table" },
        el("caption", { class: "np-sr-only", text: t(TEXT.title) }),
        el("thead", null, el("tr", null, head)),
        el("tbody", null, jobs.map((j) => row(j, note)))));
    }
    root.append(note);
    if (recent && recent.length) {
      root.append(el("h2", { text: t(TEXT.here) }),
        el("ul", { class: "jobs-here" }, recent.slice(0, 8).map((r) =>
          el("li", null, el("span", { "aria-hidden": "true", text: r.running ? "▶" : r.ok ? "✓" : "✕" }), " ",
            `${r.intent} · `, r.running ? t(TEXT.working) : r.ok ? t(TEXT.succeeded) : t(TEXT.failed), ` · ${r.endedAt || r.startedAt}`))));
    }
  }

  async function refresh(force) {
    const screen = document.getElementById("screen-jobs");
    if (!screen || (screen.hidden && !force) || busy) return;
    busy = true;
    try {
      const [list, recent] = await Promise.all([invoke("jobs_list"), invoke("recent_operations").catch(() => [])]);
      render(list, recent);
    } catch (err) {
      const root = document.getElementById("jobs-root");
      if (window.NPDevSupport) window.NPDevSupport.reportableError(root, { operation: "jobs", error: err, screen: "jobs", message: `${t(TEXT.couldNot)}: ${err}` });
      else root.textContent = `${t(TEXT.couldNot)}: ${err}`;
    } finally {
      busy = false;
    }
  }

  function startPolling() {
    if (timer) return;
    timer = setInterval(() => {
      if (!document.hidden) refresh(false);
    }, POLL_MS);
  }

  window.__npdevRefreshJobs = () => {
    document.getElementById("jobs-title").textContent = t(TEXT.title);
    document.getElementById("jobs-lead").textContent = t(TEXT.lead);
    refresh(true);
    startPolling();
  };
  document.addEventListener("npdev:lang-changed", () => {
    const screen = document.getElementById("screen-jobs");
    if (screen && !screen.hidden) window.__npdevRefreshJobs();
  });
  window.NPDevJobs = { refresh, pollMs: POLL_MS, isPolling: () => !!timer };
})();
