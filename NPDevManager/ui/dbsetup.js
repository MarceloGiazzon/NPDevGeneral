// The New-app "Database" card.
//
// WHAT IT ANSWERS. A person picks Postgres, MySQL or SQL Server and the question is "where will it run?".
// This card answers it for the engine they picked, without being asked, in four situations:
//
//   * a server of that engine is already running here          -> list it, "Use this"
//   * a container of that engine exists but is stopped          -> list it, "Start"
//   * Docker is installed and running, nothing of that engine   -> offer to CREATE one (ticked by default)
//   * Docker is missing, or installed but off                   -> say so plainly, and walk through fixing it
//                                                                  (install -> restart -> start -> detected)
//
// Everything it knows comes from the CLI: `npdev db detect` (what exists, and Docker's state) and
// `npdev db provision` (create). Nothing about engines, images or ports is written in this file.
//
// It owns no form field. It reads the chosen engine from app.js, and hands back a connection to fill in
// (`createContainer`) or a server to adopt (`onUse`); app.js does the filling, exactly as it already did
// for "Find databases". A server NPDev does not own is never started, stopped or removed from here
// except the one container the person pressed "Start" on.
//
// THE DOCKER ASSISTANT. The same guided Docker steps also live on their own, on Settings > Install
// (`#docker-assistant`), so a person can get Docker ready before they ever pick an engine -- help that is
// only reachable from one state of one card does not exist for someone who never lands in that state.
// Both hosts draw from ONE state (one Docker, one install in progress), so starting the install on one
// screen and finishing it on the other just works. On Windows the steps first check WSL 2, which Docker
// Desktop runs on and a fresh Windows often lacks.
//
// Wrapped in a function: the Manager's scripts share ONE global scope (see ui_script_global_tests).

(function () {
  // Looked up per call: this file loads before app.js, and the Tauri bridge is only guaranteed by the time a button is pressed.
  const invoke = (...args) => window.__TAURI__.core.invoke(...args);

  let pollMs = 5000; // how often to ask while waiting for Docker; shortened only by the render check
  const POLL_MAX_MS = 15 * 60 * 1000;
  const DOCKER_DOWNLOAD_URL = "https://www.docker.com/products/docker-desktop/";

  let engine = null; // the engine-catalogue entry the card is about (null = none picked)
  let seq = 0; // a newer refresh makes an older answer stale
  let pollTimer = null;
  let polling = false; // true from the moment we wait for Docker until Docker answers, the wait runs out, or the engine stops needing it
  let pollStartedAt = 0;
  let wantNew = null; // the person's explicit tick; null = follow the default
  let adopted = null; // the server they pressed "Use this" on
  let last = null; // the last answer from npdev db detect
  let onUse = () => {};
  let onRelease = () => {};
  let filledEngine = null; // the engine whose connection THIS card put in the form (adopted or created), until the person types their own
  let installState = null; // null | { opened, error, noWinget }
  let showAll = false;
  let noDocker = null; // null | "private" | "service": a server WITHOUT Docker, made when Create is pressed
  let noDockerHelp = false; // the "?" explanation is open
  let wsl = null; // null = unknown (not asked, or not answered) | { applicable, installed }
  let wslState = null; // null | { opened, error }

  const card = () => document.getElementById("db-setup-card");
  const assistantHost = () => document.getElementById("docker-assistant");

  function h(tag, props, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (k === "class") el.className = v;
      else if (k === "on") for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
      else if (v === true) el.setAttribute(k, "");
      else if (v !== false && v != null) el.setAttribute(k, String(v));
    }
    for (const c of children.flat()) if (c != null && c !== false) el.append(c.nodeType ? c : document.createTextNode(String(c)));
    return el;
  }

  const label = () => (engine ? engine.externalName : "");
  const containerized = () => !!(engine && engine.needsServer && engine.containerized);

  function stopPolling() {
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = null;
    polling = false;
  }

  // One bounded wait for Docker to come up: every few seconds ask the CLI's db detect, stop after fifteen minutes
  // or as soon as the card has nothing left to wait for. Never a hidden loop -- the card says it is waiting.
  function startPolling() {
    stopPolling();
    polling = true;
    pollStartedAt = Date.now();
    const tick = async () => {
      pollTimer = null;
      if (Date.now() - pollStartedAt > POLL_MAX_MS) {
        polling = false;
        render();
        return;
      }
      // With no engine picked the card does not look at all, so the Install screen's assistant asks itself.
      if (card() && containerized()) await refresh({ keepInstall: true, quiet: true });
      else await refreshAssistant();
      const docker = last && last.docker;
      if (polling && docker && docker.status !== "ok" && ((card() && containerized()) || assistantHost())) pollTimer = setTimeout(tick, pollMs);
      else if (polling) {
        polling = false; // Docker answered (or the card no longer needs it): the next render shows the outcome
        render();
      }
    };
    pollTimer = setTimeout(tick, pollMs);
  }

  async function refresh(opts) {
    opts = opts || {};
    const mine = ++seq;
    if (opts.showAll !== undefined) showAll = !!opts.showAll;
    if (!opts.keepInstall) {
      installState = null;
      stopPolling();
    }
    if (opts.engine !== undefined) {
      engine = opts.engine;
      adopted = null;
      wantNew = null;
      noDocker = null;
    }
    if (opts.forget) {
      adopted = null;
      wantNew = null;
      noDocker = null;
    }
    const root = card();
    if (!root) return;
    if (!containerized() && !showAll) {
      stopPolling();
      root.innerHTML = "";
      return;
    }
    if (!opts.quiet) {
      root.innerHTML = "";
      root.append(
        h("p", { class: "status-line" }, containerized() ? `Looking for a ${label()} server on this machine…` : "looking…")
      );
    }
    let result;
    try {
      result = await invoke("detect_databases");
    } catch (err) {
      if (mine !== seq) return;
      root.innerHTML = "";
      root.append(h("p", { class: "status-line" }, `could not look: ${err}`));
      return;
    }
    if (mine !== seq) return; // the engine changed while Docker was answering
    last = result;
    await refreshWsl();
    render();
  }

  // Asked only while Docker is not installed: once Docker is there, WSL evidently is too.
  async function refreshWsl() {
    const docker = last && last.docker;
    if (!docker || docker.installed !== false) return;
    try {
      const answer = await invoke("wsl_status");
      wsl = answer && typeof answer === "object" ? answer : null;
    } catch (e) {
      wsl = null; // unknown: the Docker installer itself says so if WSL is missing
    }
  }

  // The Install screen's own copy of the assistant: look at Docker, whatever engine (if any) is picked.
  async function refreshAssistant() {
    const host = assistantHost();
    if (!host) return;
    if (!last) {
      host.innerHTML = "";
      host.append(h("p", { class: "status-line" }, "Checking Docker on this computer…"));
    }
    let result;
    try {
      result = await invoke("detect_databases");
    } catch (err) {
      host.innerHTML = "";
      host.append(h("p", { class: "status-line" }, `could not check Docker: ${err}`));
      return;
    }
    last = result;
    await refreshWsl();
    render();
  }

  // ---- rendering ---------------------------------------------------------------------------------

  function statePhrase(db) {
    if (db.source === "listener") return "answering on the port";
    return { running: "running", exited: "stopped", paused: "paused", created: "not started", restarting: "restarting" }[db.state] || db.state;
  }

  function foundRow(db) {
    const name = db.name ? h("code", { translate: "no" }, db.name) : `${db.engine} listener`;
    const where = db.port ? `${db.host}:${db.port}` : "";
    const row = h(
      "div",
      { class: `check-row db-found ${db.running ? "pass" : "warn"}` },
      h("span", { class: "mark", "aria-hidden": "true" }, db.running ? "✓" : "!"),
      h("span", { class: "name" }, name),
      h("span", { class: "detail" }, [where && h("code", { translate: "no" }, where), " ", statePhrase(db)]),
      actionFor(db)
    );
    // The CLI's note for a stopped container says how to start it in a terminal; the Start button below
    // says it in the window, so showing both would be the same instruction twice.
    const hasStart = db.source === "docker" && !db.running && db.port;
    if (db.note && !hasStart) row.append(h("div", { class: "field-hint db-note" }, db.note));
    return row;
  }

  function actionFor(db) {
    if (db.initArgs) {
      return h("button", { type: "button", on: { click: () => adopt(db) } }, "Use this");
    }
    if (db.source === "docker" && !db.running && db.port) {
      return h("button", { type: "button", on: { click: (e) => startContainer(db, e.currentTarget) } }, "Start");
    }
    return h("span");
  }

  function adopt(db) {
    // onUse switches the engine picker when the server is of another engine, which re-points the card
    // (and clears `adopted`); so adoption is recorded AFTER it, not before.
    onUse(db);
    adopted = db;
    filledEngine = db.engine;
    render();
  }

  async function startContainer(db, button) {
    button.disabled = true;
    button.textContent = "starting…";
    try {
      await invoke("start_db_container", { name: db.name });
    } catch (err) {
      button.disabled = false;
      button.textContent = "Start";
      button.after(h("div", { class: "field-hint" }, `could not start it: ${err}`));
      return;
    }
    // A database takes a few seconds to accept connections after its container starts; the row will
    // say "running" (it is) and Test connection tells the truth about whether it already answers.
    await refresh({ quiet: true, keepInstall: true });
  }

  function dockerBlock(docker) {
    const box = h("div", { class: "db-docker" });
    if (docker.status === "ok") return box;
    if (docker.installed === false) {
      box.append(
        h("p", { class: "status-line" }, `Docker is not installed. With Docker, NPDev can create a ${label()} server for you in one click -- you install nothing else.`),
        h("div", { class: "panel-actions" },
          h("button", { type: "button", class: "primary", on: { click: () => { installState = installState || {}; render(); } } }, "Install Docker, step by step")),
        h("p", { class: "field-hint" }, `Prefer to run ${label()} yourself? Install it, then press Find databases -- NPDev will find it.`)
      );
      if (installState) box.append(installSteps(docker));
      return box;
    }
    const waiting = polling;
    box.append(
      h("p", { class: "status-line" }, `Docker is installed but not running. Start it and NPDev can create a ${label()} server for you.`)
    );
    const actions = h("div", { class: "panel-actions" });
    const start = h("button", { type: "button", class: "primary", on: { click: () => startDocker(start, actions) } }, "Start Docker Desktop");
    start.disabled = waiting;
    actions.append(start);
    if (waiting) actions.append(waitingNote());
    box.append(actions);
    if (docker.detail) box.append(h("div", { class: "field-hint" }, `docker said: ${docker.detail}`));
    return box;
  }

  function waitingNote() {
    const secs = Math.round((Date.now() - pollStartedAt) / 1000);
    return h("span", { class: "status-line" }, `Waiting for Docker to start… ${secs}s`);
  }

  async function startDocker(button, actions) {
    button.disabled = true;
    try {
      await invoke("start_docker_desktop");
    } catch (err) {
      button.disabled = false;
      actions.append(h("span", { class: "status-line" }, `could not start Docker Desktop: ${err}`));
      return;
    }
    startPolling();
    render();
  }

  // The guided install. Four plain steps; the marks follow what db detect reports, not what was
  // clicked, so a step is done when the machine says so (including steps the person did elsewhere).
  function installSteps(docker) {
    const installed = docker.installed !== false;
    const running = docker.status === "ok";
    const step = (done, title, ...body) =>
      h("li", { class: `db-step ${done ? "done" : ""}` },
        h("span", { class: "mark", "aria-hidden": "true" }, done ? "✓" : "○"),
        h("div", {}, h("div", { class: "db-step-title" }, title), ...body));

    const open = h("button", { type: "button", class: "primary", on: { click: () => openInstaller(open) } }, installState.opened ? "Open the installer again" : "Open the installer");
    open.disabled = installed;
    const notes = [];
    if (installState.opened && !installed) notes.push(h("p", { class: "field-hint" }, "The installer is open in another window -- follow it there. This can take a few minutes."));
    if (installState.error) notes.push(h("p", { class: "field-hint db-error" }, installState.error));
    if (installState.noWinget) {
      notes.push(
        h("p", { class: "field-hint" }, "Windows cannot install it for you from here (winget is missing). Download Docker Desktop from Docker's site, install it, and come back."),
        h("button", { type: "button", on: { click: () => invoke("open_url", { url: DOCKER_DOWNLOAD_URL }).catch(() => {}) } }, "Open Docker's download page")
      );
    }

    const startBtn = h("button", { type: "button", on: { click: () => startDocker(startBtn, startBtn.parentElement) } }, "Start Docker Desktop");
    startBtn.disabled = !installed || running;

    const timedOut = !polling && pollStartedAt > 0 && Date.now() - pollStartedAt > POLL_MAX_MS;
    const watching = running
      ? h("p", { class: "field-hint" }, "Docker is ready.")
      : timedOut
        ? [
            h("p", { class: "field-hint" }, "Docker has not shown up yet. Check again once its whale icon says it is running."),
            h("button", { type: "button", on: { click: () => { startPolling(); render(); } } }, "Check again"),
          ]
        : polling
          ? waitingNote()
          : h("button", { type: "button", on: { click: () => { startPolling(); render(); } } }, "Check now");

    const first = wsl && wsl.applicable && !wsl.installed && !installed ? [wslStep(step)] : [];
    return h(
      "ol",
      { class: "db-steps" },
      ...first,
      step(installed, "1. Install Docker Desktop.", h("p", { class: "field-hint" }, "Press the button. Windows asks for your permission, then a window shows the installation."), h("div", { class: "panel-actions" }, open), ...notes),
      step(running, "2. Restart if it asks.", h("p", { class: "field-hint" }, "Docker may ask to restart the computer. If it does, restart, open NPDev Manager again and come back to this screen -- your apps are kept.")),
      step(running, "3. Start Docker Desktop and accept its terms.", h("div", { class: "panel-actions" }, startBtn)),
      step(running, "4. NPDev notices by itself.", watching)
    );
  }

  // Before step 1, only when Windows lacks it: WSL 2, the Linux layer Docker Desktop runs on.
  function wslStep(step) {
    wslState = wslState || {};
    const btn = h("button", { type: "button", class: "primary", on: { click: () => openWslInstaller(btn) } }, wslState.opened ? "Turn on WSL again" : "Turn on WSL");
    const notes = [];
    if (wslState.opened) notes.push(h("p", { class: "field-hint" }, "A window is turning WSL on. When it finishes, restart the computer, open NPDev Manager again and come back here."));
    if (wslState.error) notes.push(h("p", { class: "field-hint db-error" }, wslState.error));
    return step(false, "First: turn on WSL, the part of Windows that Docker runs on.",
      h("p", { class: "field-hint" }, "It is off on this computer. Press the button: Windows asks for your permission and turns it on. Nothing else is installed."),
      h("div", { class: "panel-actions" }, btn), ...notes);
  }

  async function openWslInstaller(button) {
    button.disabled = true;
    wslState = Object.assign(wslState || {}, { error: null });
    try {
      await invoke("install_wsl");
      wslState.opened = true;
    } catch (err) {
      wslState.error = /declined/.test(String(err))
        ? "The administrator prompt was declined, so nothing was changed. Press the button to try again."
        : `could not turn on WSL: ${err}`;
    }
    render();
  }

  async function openInstaller(button) {
    button.disabled = true;
    installState = Object.assign(installState || {}, { error: null, noWinget: false });
    try {
      await invoke("install_docker_desktop");
      installState.opened = true;
      startPolling();
    } catch (err) {
      const text = String(err);
      if (/winget was not found/.test(text)) installState.noWinget = true;
      else if (/declined/.test(text)) installState.error = "The administrator prompt was declined, so nothing was installed. Press the button to try again.";
      else installState.error = `could not open the installer: ${text}`;
    }
    render();
  }

  function createOption(running) {
    const defaultOn = running.length === 0;
    const checked = adopted ? false : wantNew === null ? defaultOn : wantNew;
    const box = h("input", { type: "checkbox", id: "db-setup-create", checked, disabled: !!adopted });
    box.addEventListener("change", () => {
      wantNew = box.checked;
      if (box.checked && noDocker) {
        noDocker = null; // one way at a time
        render();
      }
    });
    return h(
      "div",
      { class: "db-create" },
      h("label", { class: "checkbox-line" }, box, `Create a new ${label()} server in Docker when I press Create`),
      h("div", { class: "field-hint" }, "A new container from the official image, reachable only from this computer, with a generated password that NPDev fills in for you. The first time, Docker downloads the image (a few hundred MB), so it can take a few minutes.")
    );
  }

  // ---- a server WITHOUT Docker --------------------------------------------------------------------
  // Two ways, side by side, with a "?" that says which to pick (the CLI's npdev_db_server does the work).
  // Postgres only for now; for the other engines the card says plainly that Docker is the way.
  const NO_DOCKER_ENGINES = ["postgres"];

  function noDockerBlock(docker) {
    const dockerOk = docker.status === "ok";
    if (!NO_DOCKER_ENGINES.includes(engine.key)) {
      if (dockerOk) return null;
      return h("p", { class: "field-hint db-nodocker-note" },
        `Without Docker, NPDev cannot install ${label()} for you yet. Postgres can be installed without Docker: if your app does not need ${label()}, pick Postgres above.`);
    }
    const helpBtn = h("button", {
      type: "button", class: "db-help-btn", "aria-label": "Which one should I choose?", title: "Which one should I choose?",
      "aria-expanded": noDockerHelp ? "true" : "false",
      on: { click: () => { noDockerHelp = !noDockerHelp; render(); } },
    }, "?");
    const option = (where, title, text) => {
      const chosen = noDocker === where;
      const btn = h("button", { type: "button", class: chosen ? "" : "primary", on: { click: () => chooseNoDocker(chosen ? null : where) } },
        chosen ? "Undo" : "Use this when I press Create");
      return h("div", { class: `db-option ${chosen ? "chosen" : ""}` },
        h("div", { class: "db-step-title" }, chosen ? `✓ ${title}` : title),
        h("p", { class: "field-hint" }, text),
        chosen ? h("p", { class: "field-hint" }, "When you press Create, NPDev installs it first and fills in the connection for you.") : null,
        h("div", { class: "panel-actions" }, btn));
    };
    const body = [
      h("div", { class: "db-nodocker-head" },
        h("span", { class: "status-line" }, dockerOk ? "Or, without Docker:" : "Install Postgres without Docker:"), helpBtn),
      noDockerHelp ? h("div", { class: "field-hint db-help-text", role: "note" },
        h("p", {}, "Not sure? Choose NPDev's own Postgres. It is the simplest: no permission prompt, and it stays inside NPDev's own folder."),
        h("p", {}, "Choose the Windows service only if other programs on this computer will use the same Postgres, or if someone who looks after this computer asked for it. It is installed for the whole computer, like any other program."),
        h("p", {}, "Docker is the third way: it can also run MySQL and SQL Server, but it is a bigger program to install.")) : null,
      option("private", "NPDev's own Postgres (recommended)",
        "NPDev downloads its own copy (about 25 MB), just for NPDev. No permission prompt, nothing else on your computer changes. It starts by itself when you sign in to Windows."),
      option("service", "Postgres as a Windows service",
        "The official installer. Windows asks for your permission, then Postgres is installed for the whole computer and starts with Windows. It can take a few minutes."),
    ];
    return h("div", { class: "db-nodocker" }, ...body);
  }

  function chooseNoDocker(where) {
    noDocker = where;
    if (where) {
      wantNew = false; // one way at a time: the Docker tick goes off
      const box = document.getElementById("db-setup-create");
      if (box) box.checked = false;
    }
    render();
  }

  function render() {
    renderCard();
    renderAssistant();
  }

  // The standalone assistant: Docker's state in plain words, and the one thing to do next.
  function renderAssistant() {
    const host = assistantHost();
    if (!host || !last) return;
    host.innerHTML = "";
    const docker = last.docker || { status: "ok" };
    if (docker.status === "ok") {
      host.append(
        h("div", { class: "check-row pass" }, h("span", { class: "mark", "aria-hidden": "true" }, "✓"), h("span", { class: "name" }, "Docker is ready.")),
        h("p", { class: "field-hint" }, "When you create an app with Postgres, MySQL or SQL Server, NPDev can now make that database for you. Nothing else to do here.")
      );
      return;
    }
    if (docker.installed === false) {
      installState = installState || {};
      host.append(
        h("p", { class: "status-line" }, "Docker is not installed on this computer."),
        h("p", { class: "field-hint" }, "You only need Docker for apps that use Postgres, MySQL or SQL Server: with it, NPDev creates that database for you, so you never install a database server by hand. Apps that use H2 work without it."),
        installSteps(docker)
      );
      return;
    }
    const actions = h("div", { class: "panel-actions" });
    const start = h("button", { type: "button", class: "primary", on: { click: () => startDocker(start, actions) } }, "Start Docker Desktop");
    start.disabled = polling;
    actions.append(start);
    if (polling) actions.append(waitingNote());
    host.append(
      h("p", { class: "status-line" }, "Docker is installed but not running."),
      h("p", { class: "field-hint" }, "Press the button: Docker Desktop opens (the first time it asks you to accept its terms). NPDev notices by itself when it is ready."),
      actions
    );
  }

  function renderCard() {
    const root = card();
    if (!root || !last) return;
    if (!containerized() && !showAll) return; // refresh() cleared it; a redraw for the assistant must not bring it back
    root.innerHTML = "";
    const docker = last.docker || { status: "ok" };
    const all = last.databases || [];

    if (containerized()) {
      const mine = all.filter((d) => d.engine === engine.key);
      const running = mine.filter((d) => d.initArgs);
      if (adopted) {
        root.append(h("p", { class: "status-line" }, `Filled in ${adopted.engine} at ${adopted.host}:${adopted.port}. Type the user and password, then Test connection.`));
      } else if (running.length) {
        root.append(h("p", { class: "status-line" }, running.length === 1 ? `Found a ${label()} server running on this machine.` : `Found ${running.length} ${label()} servers running on this machine.`));
      } else {
        root.append(h("p", { class: "status-line" }, `No ${label()} server is running on this machine.`));
      }
      if (mine.length) root.append(h("div", { class: "check-list" }, mine.map(foundRow)));
      // Without Docker, the Docker-free way (when this engine has one) is the easier path: it comes first.
      const alt = !adopted && !running.length ? noDockerBlock(docker) : null;
      const altFirst = alt && docker.status !== "ok" && NO_DOCKER_ENGINES.includes(engine.key);
      if (altFirst) root.append(alt);
      root.append(dockerBlock(docker));
      if (docker.status === "ok") root.append(createOption(running));
      if (alt && !altFirst) root.append(alt);
    }

    if (showAll) {
      const others = all.filter((d) => !containerized() || d.engine !== engine.key);
      if (others.length) {
        root.append(h("p", { class: "status-line" }, containerized() ? "Other databases on this machine:" : "Databases found on this machine:"), h("div", { class: "check-list" }, others.map(foundRow)));
      } else if (!containerized()) {
        const ready = (last.engines || []).filter((e) => e.status === "ready").map((e) => e.engine);
        root.append(
          h("p", { class: "status-line" }, "No running database server found on this machine." + (ready.length ? ` Usable without one: ${ready.join(", ")} (see Ready > Database engines).` : ""))
        );
        if (docker.status !== "ok") root.append(h("p", { class: "field-hint" }, "Pick Postgres, MySQL or SQL Server above and NPDev will help you set one up."));
      }
    }
  }

  // ---- what app.js calls ---------------------------------------------------------------------------

  window.NPDevDbSetup = {
    /** Wire the callback that fills the form when the person adopts a found server. */
    init(opts) {
      onUse = (opts && opts.onUse) || onUse;
      onRelease = (opts && opts.onRelease) || onRelease;
    },
    /** The engine picker changed (or finished loading): look, for that engine. */
    setEngine(entry) {
      const same = entry ? !!engine && engine.key === entry.key : !engine;
      engine = entry || null; // keep the reference fresh even when nothing needs redrawing
      if (same) return Promise.resolve(); // a reload of the same pick is not a new question
      // Host, port and user that were filled in for another engine's server are wrong for this one.
      if (filledEngine && (!entry || entry.key !== filledEngine)) {
        filledEngine = null;
        onRelease();
      }
      return refresh({ engine, showAll: false, keepInstall: true });
    },
    /** The "Find databases" button: look again, and also list every other database found. */
    findAll() {
      return refresh({ showAll: true, forget: true });
    },
    /** The person typed their own connection: stop offering to create one over it. */
    connectionEdited() {
      wantNew = false;
      if (noDocker) {
        noDocker = null;
        render();
      }
      filledEngine = null; // theirs now, not ours to clear
      const box = document.getElementById("db-setup-create");
      if (box) box.checked = false;
    },
    /** True when Create app should first create a database container. */
    wantsNewContainer() {
      return this.createWhere() !== null;
    },
    /** Where Create should make the server: "docker", "private", "service", or null for nowhere. */
    createWhere() {
      if (!containerized() || adopted) return null;
      const box = document.getElementById("db-setup-create");
      if (box && box.checked && !box.disabled && last && last.docker && last.docker.status === "ok") return "docker";
      return noDocker && NO_DOCKER_ENGINES.includes(engine.key) ? noDocker : null;
    },
    engineLabel: label,
    setPollMs(ms) {
      pollMs = ms;
    },
    /**
     * Create the container. Resolves to the CLI's own object: `{ok:true, provisioned}` or
     * `{ok:false, error:{code,message,fix}}` -- a refusal is an answer, not an exception.
     */
    async createContainer(appName) {
      return invoke("provision_database", { engine: engine.key, appName, port: null, placement: this.createWhere() || "docker" });
    },
    /** After a container was created (or the app was): look again so the card shows what now exists. */
    afterCreate() {
      return refresh({ forget: true, quiet: true });
    },
    /** A created container's connection is now in the form (app.js filled it). */
    noteFilled() {
      filledEngine = engine ? engine.key : null;
    },
    stop: stopPolling,
    /** Settings > Install: draw (or redraw) the standalone Docker assistant. */
    refreshAssistant,
  };
})();
