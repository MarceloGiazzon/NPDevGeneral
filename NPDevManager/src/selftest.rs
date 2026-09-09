//! `--selftest` (CLOSEOUT_PLAN.md I1): the headless proof that the whole install path works with
//! no window and no system Java. Drives the exact same functions the real UI commands call
//! (`main.rs`'s `install_jdk`/`install_python`/`install_npdev_version`/`check_doctor` all wrap
//! these) from a CLI flag instead of a click, so I4's container harness can assert this
//! mechanically on every change instead of relying on a human clicking through five screens.
//!
//! Standing rule still applies here: this never reimplements CLI behaviour, only resolves/installs
//! the runtimes and then calls `npdev doctor --json` the same way `check_doctor` does.

use crate::{npdev, runtime, state, versions};

type StepError = (&'static str, String);

pub async fn run() -> i32 {
    println!("npdev-manager --selftest");
    match run_steps().await {
        Ok(()) => {
            println!("  SELFTEST PASS");
            0
        }
        Err((step, msg)) => {
            eprintln!("  SELFTEST FAIL at step [{step}]: {msg}");
            1
        }
    }
}

async fn run_steps() -> Result<(), StepError> {
    state::ensure_dirs().map_err(|e| ("0/10 create manager directories", e.to_string()))?;
    let app_state = state::AppState::new();

    resolve_jdk_step(&app_state).await?;
    let java_home = app_state.manager.lock().expect("lock poisoned").jdk_home.clone();

    let python_path = resolve_python_step(&app_state).await?;

    let cli_path = resolve_npdev_step(&app_state).await?;

    run_setup_step(&python_path, &cli_path, java_home.as_deref()).await?;

    run_engine_picker_step(&python_path, &cli_path, java_home.as_deref()).await?;

    run_doctor_step(&python_path, &cli_path, java_home.as_deref()).await?;

    run_monitor_steps(&python_path, &cli_path, java_home.as_deref()).await?;

    run_host_steps(&python_path, &cli_path, java_home.as_deref()).await?;

    run_database_steps(&python_path, &cli_path, java_home.as_deref()).await
}

// ------------------------------------------------------------------------------------------------
// MONITOR_PLAN B6: the Monitor and the Scrap Manager, headless.
//
// These are deliberately the paths that need NO app, NO engine and NO network, because those are the
// paths a container harness can actually run on every change. Everything richer -- a real scan of a
// real app, a real exploration -- is proven by the Phase B/D acceptance walks on a machine that has
// them; claiming otherwise here would be the kind of green that means nothing.
//
// What each assertion protects is named on it. All three are contract shapes the UI depends on and
// would render as a blank panel if they changed silently.
// ------------------------------------------------------------------------------------------------

async fn run_monitor_steps(python: &std::path::Path, cli: &std::path::Path,
                           java_home: Option<&str>) -> Result<(), StepError> {
    // The Manager runs whatever NPDev version is INSTALLED, which can be older than this window.
    // Found here on 2026-08-10: the installed beta1.14 has no `npdev monitor` at all, and the
    // harness reported it as a Monitor defect. It is not one -- but it is not a pass either, so it
    // is announced as a SKIP with the reason, never swallowed. (`database_steps_enabled` sets the
    // precedent: a skip that says why is honest; a silent one is the thing gates exist to prevent.)
    let missing = std::env::temp_dir().join("npdev-selftest-not-an-app");
    std::fs::create_dir_all(&missing).map_err(|e| ("6b/10 monitor", e.to_string()))?;
    match npdev::run_monitor_probe(python, cli, java_home, &missing.to_string_lossy(), false).await {
        Err(message) if message.contains("has no `npdev monitor`") => {
            println!("  [6b/10] monitor scan/probe/engine .............. SKIPPED -- {message}");
            return Ok(());
        }
        _ => {}
    }

    // 1. A scan with no paths must be a well-formed EMPTY answer, not an error and not a scan of the
    //    whole disk. This is the first thing a fresh Manager does, before anything is configured.
    let empty = npdev::run_monitor_scan(python, cli, java_home, &[], false)
        .await
        .map_err(|e| ("6b/10 monitor scan (no paths)", e))?;
    if empty.get("apps").and_then(|a| a.as_array()).map(|a| !a.is_empty()).unwrap_or(true) {
        return Err(("6b/10 monitor scan (no paths)",
                    "expected an empty app list; a fresh Manager must not invent apps".to_string()));
    }

    // 2. Probing a directory that is not an app must be a DIAGNOSED refusal carrying a reason -- the
    //    Monitor renders that reason on the card. An error here instead of a record would leave the
    //    card blank, which is the failure mode the marker-pair rule exists to make legible.
    let probe = npdev::run_monitor_probe(python, cli, java_home, &missing.to_string_lossy(), false)
        .await
        .map_err(|e| ("6c/10 monitor probe (not an app)", e))?;
    match probe.get("status").and_then(|s| s.as_str()) {
        Some("not-an-app") => {}
        other => {
            return Err(("6c/10 monitor probe (not an app)",
                        format!("expected status 'not-an-app', got {other:?}")));
        }
    }
    if probe.get("detail").and_then(|d| d.as_str()).unwrap_or("").is_empty() {
        return Err(("6c/10 monitor probe (not an app)",
                    "the refusal carries no `detail`, so the card would say nothing".to_string()));
    }

    // 3. Engine detection must always ANSWER -- found or not-found, never an exception. D9's whole
    //    point is that a machine without the engine gets an honest disabled state rather than a
    //    silent failure, and this is the only assertion that can fail on a machine that has one.
    let engine = npdev::run_monitor_engine(python, cli, java_home, 3010, None, "")
        .await
        .map_err(|e| ("6d/10 monitor engine", e))?;
    let state = engine.get("state").and_then(|s| s.as_str()).unwrap_or("");
    if !matches!(state, "running" | "installed-stopped" | "not-found") {
        return Err(("6d/10 monitor engine", format!("unexpected engine state {state:?}")));
    }
    if engine.get("detail").and_then(|d| d.as_str()).unwrap_or("").is_empty() {
        return Err(("6d/10 monitor engine",
                    "no `detail` -- the Scrap tab would show a disabled state with no reason".to_string()));
    }

    println!("  [6b/10] monitor scan/probe/engine driven ....... ok  (engine: {state})");
    Ok(())
}

// ------------------------------------------------------------------------------------------------
// [7/10]: the Share screen's read verbs (NPDEV_MANAGER_SHARE_IMPLEMENTATION_PLAN M12).
//
// Same discipline as run_monitor_steps above: an assertion against a directory that is NOT a
// generated app needs no app, no engine and no network, so it runs unconditionally in the bare
// container. Asserting `findings` against a REAL planned app needs one already generated and
// `npdev host plan` already run against it -- exactly the kind of environment the bare container
// does not have -- so that half is gated the same way `database_steps_enabled` gates the toolbox
// steps: SKIPPED WITH A REASON, never silently.
// ------------------------------------------------------------------------------------------------

/// What the real-app half of the host steps needs. `NPDEV_SELFTEST_HOST_APP` names a directory
/// already generated AND already planned (`npdev host plan --rung <n> --yes` already run against
/// it) -- this harness does not generate or plan one itself, the same way it does not stand up a
/// Postgres container for the database steps.
fn host_steps_enabled() -> Option<std::path::PathBuf> {
    std::env::var("NPDEV_SELFTEST_HOST_APP").ok().map(std::path::PathBuf::from)
}

async fn run_host_steps(python: &std::path::Path, cli: &std::path::Path,
                        java_home: Option<&str>) -> Result<(), StepError> {
    let missing = std::env::temp_dir().join("npdev-selftest-not-an-app");
    std::fs::create_dir_all(&missing).map_err(|e| ("7/10 host", e.to_string()))?;

    // Same precedent as run_monitor_steps above: the Manager runs whatever NPDev version is
    // INSTALLED (resolve_npdev_step installs the newest RELEASED tag), which can predate `npdev
    // host` entirely until a tag ships with it. That is a diagnosed absence, not a Manager defect.
    match npdev::run_host_status(python, cli, java_home, &missing.to_string_lossy()).await {
        Err(message) if message.contains("has no `npdev host`") => {
            println!("  [7/10] host status/check .............. SKIPPED -- {message}");
            return Ok(());
        }
        _ => {}
    }

    // 1. `host status` against a directory that has never shared anything must be a well-formed
    //    "down" answer, not an error -- the same "first visit, nothing configured yet" shape
    //    run_monitor_scan's empty-paths case asserts for the Monitor.
    let status = npdev::run_host_status(python, cli, java_home, &missing.to_string_lossy())
        .await
        .map_err(|e| ("7/10 host status (never shared)", e))?;
    if status.get("up").and_then(|v| v.as_bool()) != Some(false) {
        return Err(("7/10 host status (never shared)",
                    format!("expected up:false for a directory nothing was ever shared from, got {status}")));
    }

    let Some(app_dir) = host_steps_enabled() else {
        println!("  [7/10] SKIPPED -- set NPDEV_SELFTEST_HOST_APP=<a generated, host-planned app> to run the rest.");
        return Ok(());
    };
    let app_dir = app_dir.to_string_lossy().to_string();

    // 2. Against a real, planned app, `host check` must return the shape the Share screen's check
    //    list (M5) renders straight from: `findings[]`, each with `id`/`status`/`detail` -- a
    //    renderer that silently started reading a different field would show a blank list, not an
    //    error anyone would see.
    let check = npdev::run_host_check(python, cli, java_home, &app_dir, false, None)
        .await
        .map_err(|e| ("7/10 host check (real app)", e))?;
    let findings = check.get("findings").and_then(|f| f.as_array())
        .ok_or_else(|| ("7/10 host check (real app)",
                        "no `findings` array -- the check list would render nothing".to_string()))?;
    if findings.is_empty() {
        return Err(("7/10 host check (real app)",
                    "`findings` is empty -- a planned app always has at least one check".to_string()));
    }
    for finding in findings {
        for field in ["id", "status", "detail"] {
            if finding.get(field).and_then(|v| v.as_str()).unwrap_or("").is_empty() {
                return Err(("7/10 host check (real app)",
                            format!("a finding is missing `{field}`: {finding}")));
            }
        }
    }

    println!("  [7/10] host status/check driven ............... ok  ({} finding(s) on a real app)", findings.len());
    Ok(())
}

// ------------------------------------------------------------------------------------------------
// [8/10]-[10/10]: the database toolbox and Test connection.
//
// WHY THESE EXIST. `npdev db test-connection` and `npdev db start|stop|status|connection|reset` were
// proven live against real Postgres containers. The WINDOW that wraps them -- the thing a
// non-specialist actually touches -- had zero coverage: `test_connection` and `db_operation` were
// referenced 0 times by this harness, including `reset`, the one that deletes data. That is the
// STOR-4/5/6 shape one layer up, and it has produced a defect every time it existed here: the layer
// below is green while the artifact the user runs is unwatched.
//
// These drive the SAME `npdev::` functions the Tauri commands wrap -- no browser, no WebDriver. A UI
// driver would be a second harness to maintain, and it would not exercise more code than this does.
// ------------------------------------------------------------------------------------------------

/// What the database steps need, and why they are gated rather than always-on.
///
/// The bare-container job starts with NOTHING -- no docker, no PowerShell -- and that emptiness is
/// the entire claim it makes. Bolting an engine onto it would weaken the one thing it proves. So
/// these steps run only where those exist (the `manager-db-toolbox` job), and are SKIPPED WITH A
/// REASON everywhere else. Never silently: a step that vanishes reads as a step that passed.
fn database_steps_enabled() -> bool {
    std::env::var("NPDEV_SELFTEST_DB").map(|v| v == "1").unwrap_or(false)
}

async fn run_database_steps(python: &std::path::Path, cli: &std::path::Path,
                            java_home: Option<&str>) -> Result<(), StepError> {
    if !database_steps_enabled() {
        println!("  [8/10] SKIPPED -- database toolbox steps need docker + PowerShell.");
        println!("  [9/10] SKIPPED -- set NPDEV_SELFTEST_DB=1 in a job that has them.");
        println!("  [10/10] SKIPPED -- (the bare container deliberately has neither.)");
        return Ok(());
    }

    let workspace = std::env::temp_dir().join("npdev-selftest-db");
    let _ = std::fs::remove_dir_all(&workspace);
    std::fs::create_dir_all(&workspace)
        .map_err(|e| ("8/10 workspace", format!("could not create {}: {e}", workspace.display())))?;

    // TWO apps in ONE parent folder, deliberately. That is the shape QUAL-3 turned out to be --
    // two apps sharing one toolbox, one container and one data root, where resetting either
    // destroyed the other's data and reported success. [10/10] asserts it cannot happen here.
    let app_a = scaffold_app(python, cli, java_home, &workspace, "toolbox-a", 15511).await?;
    let app_b = scaffold_app(python, cli, java_home, &workspace, "toolbox-b", 15512).await?;

    run_toolbox_transitions_step(python, cli, java_home, &app_a).await?;
    run_test_connection_step(python, cli, java_home, &app_a).await?;
    run_reset_step(python, cli, java_home, &app_a, &app_b).await
}

/// `npdev init` then `npdev generate app` -- the toolbox reads a file only generation writes.
async fn scaffold_app(python: &std::path::Path, cli: &std::path::Path, java_home: Option<&str>,
                      workspace: &std::path::Path, name: &str, port: u16)
                      -> Result<std::path::PathBuf, StepError> {
    let dir = workspace.join(name);
    // STOR-15: `false` -- the selftest's Postgres IS NPDev's own container, started by the db
    // toolbox two steps earlier. Passing true here would make the very next step (Reset) refuse,
    // which is correct behaviour and the wrong fixture.
    npdev::run_init(python, cli, java_home, &dir.to_string_lossy(), Some("postgres"),
                    Some("localhost"), Some(port), Some("npdev"), Some("npdev"), false)
        .await
        .map_err(|e| ("8/10 scaffold", format!("npdev init {name}: {e}")))?;
    npdev::run_generate_app(
        python, cli, java_home,
        &dir.join("model.json").to_string_lossy(),
        &dir.join("config.json").to_string_lossy(),
        &workspace.join(format!("{name}-app")).to_string_lossy(),
    )
    .await
    .map_err(|e| ("8/10 scaffold", format!("generate {name}: {e}")))?;
    Ok(dir)
}

fn op_output(value: &serde_json::Value) -> String {
    value.get("output").and_then(|o| o.as_str()).unwrap_or("").to_string()
}

// The three assertions the new steps turn on, as pure functions -- so they can be RED-proven
// without docker, a database, or the 30-minute install path in front of them. ROUND2_START_PLAN.md
// section 2.5 asks for exactly that: "break it on purpose once and watch it fail. A step that has
// only ever passed is not a step." The end-to-end wiring is proven by the manager-db-toolbox job;
// what these pin is that the assertions REJECT the broken shapes, which is the part a green run can
// never demonstrate.

/// Does `docker ps` output (as the generated Status-Environment.ps1 prints it) show this container
/// actually up? Presence of the NAME alone is not enough -- `docker ps -a` lists exited containers
/// by name too, so a stopped engine would read as running.
fn reports_container_running(status_output: &str, container: &str) -> bool {
    status_output
        .lines()
        .any(|line| line.contains(container) && line.contains("Up "))
}

/// Which expected field is missing from the connection screen, if any. This is what a user reads to
/// reach their data with another tool, so a blank line here is a dead end rather than an error.
fn missing_connection_field(output: &str) -> Option<&'static str> {
    ["Host", "Port", "Database", "PostgreSQL"]
        .into_iter()
        .find(|expected| !output.contains(expected))
}

/// A reset refusal must NAME the token. "Refused" alone leaves the reader guessing what to type,
/// and the token exists precisely so the destructive path cannot be taken by reflex.
fn refusal_names_the_token(text: &str) -> bool {
    text.contains("I_UNDERSTAND_DB_DATA_WILL_BE_DELETED")
}

/// [8/10] start -> status -> connection -> stop, asserting STATE TRANSITIONS, not exit codes.
///
/// A `stop` that exits 0 having done nothing passes an exit-code check and fails a state check. So
/// the assertion is that `status` DISAGREES with itself either side of the operation.
async fn run_toolbox_transitions_step(python: &std::path::Path, cli: &std::path::Path,
                                      java_home: Option<&str>, app: &std::path::Path)
                                      -> Result<(), StepError> {
    let app_dir = app.to_string_lossy().to_string();
    let call = |op: &'static str, confirm: Option<&'static str>| {
        let app_dir = app_dir.clone();
        async move {
            npdev::run_db_operation(python, cli, java_home, &app_dir, op, confirm)
                .await
                .map_err(|e| ("8/10 db toolbox", format!("{op}: {e}")))
        }
    };

    call("start", None).await?;
    let running = op_output(&call("status", None).await?);
    if !reports_container_running(&running, "npdev-toolbox-a") {
        return Err(("8/10 status after start",
                    format!("status does not report the container as running after `start`. \
                             A `start` that exits 0 without starting anything looks identical to a \
                             real one until you ask. Output was:\n{running}")));
    }

    let details = op_output(&call("connection", None).await?);
    if let Some(expected) = missing_connection_field(&details) {
        return Err(("8/10 connection details",
                    format!("connection output is missing '{expected}' -- this is the screen a \
                             user opens to reach their data with another tool. Output was:\n{details}")));
    }

    call("stop", None).await?;
    let stopped = op_output(&call("status", None).await?);
    if reports_container_running(&stopped, "npdev-toolbox-a") {
        return Err(("8/10 status after stop",
                    format!("the container is still running after `stop` -- the exit code said \
                             otherwise. Output was:\n{stopped}")));
    }
    println!("  [8/10] db toolbox: start -> status(running) -> connection -> stop -> status(stopped)");
    Ok(())
}

/// [9/10] Test connection, BOTH answers -- and the wrong one is the point.
///
/// A check that only ever passes is not a check. This project has already shipped a doctor that
/// called a missing database a CREDENTIALS failure; a confident wrong diagnosis is worse than none,
/// and the window is where it reaches someone who cannot read past it. So the failing case asserts
/// WHICH check failed, not merely that something did.
async fn run_test_connection_step(python: &std::path::Path, cli: &std::path::Path,
                                  java_home: Option<&str>, app: &std::path::Path)
                                  -> Result<(), StepError> {
    let app_dir = app.to_string_lossy().to_string();
    npdev::run_db_operation(python, cli, java_home, &app_dir, "start", None)
        .await
        .map_err(|e| ("9/10 test connection", format!("could not start the engine to probe: {e}")))?;

    let good = npdev::run_db_test_connection(python, cli, java_home, "postgres",
                                             Some("localhost"), Some(15511), Some("npdev"), Some("npdev"))
        .await
        .map_err(|e| ("9/10 test connection (correct)", e))?;
    if good.get("ok").and_then(|o| o.as_bool()) != Some(true) {
        return Err(("9/10 test connection (correct)",
                    format!("correct credentials against a running engine were reported as UNUSABLE: {good}")));
    }

    let bad = npdev::run_db_test_connection(python, cli, java_home, "postgres",
                                            Some("localhost"), Some(15511), Some("npdev"), Some("definitely-wrong"))
        .await
        .map_err(|e| ("9/10 test connection (wrong password)", e))?;
    if bad.get("ok").and_then(|o| o.as_bool()) != Some(false) {
        return Err(("9/10 test connection (wrong password)",
                    format!("a WRONG PASSWORD against a reachable engine was reported as usable: {bad}")));
    }
    let failing: Vec<&str> = bad.get("checks").and_then(|c| c.as_array()).map(|checks| {
        checks.iter()
            .filter(|c| c.get("status").and_then(|s| s.as_str()) == Some("fail"))
            .filter_map(|c| c.get("id").and_then(|i| i.as_str()))
            .collect()
    }).unwrap_or_default();
    if !failing.contains(&"database-credentials") {
        return Err(("9/10 test connection (wrong password)",
                    format!("the server was REACHABLE and the password was wrong, but the failing \
                             check(s) were {failing:?} -- not `database-credentials`. Telling a user \
                             their database is unreachable when their password is wrong sends them to \
                             fix the wrong thing.")));
    }
    println!("  [9/10] test connection: correct -> usable; wrong password -> database-credentials");
    Ok(())
}

/// [10/10] Reset -- refuse, then destroy, then prove the neighbour survived.
///
/// Assertion 3 is QUAL-3's regression test AT THE WINDOW LAYER. QUAL-3 was filed MEDIUM, found HIGH,
/// and deleted a second app's data while reporting success. The CLI has nine tests for it inside T2.
/// The window had none -- and the window is where reset became a button.
async fn run_reset_step(python: &std::path::Path, cli: &std::path::Path, java_home: Option<&str>,
                        app_a: &std::path::Path, app_b: &std::path::Path) -> Result<(), StepError> {
    let a = app_a.to_string_lossy().to_string();
    let b = app_b.to_string_lossy().to_string();

    // The neighbour is RUNNING when A is reset. A destroyed container is only proof if the other one
    // was alive to begin with.
    npdev::run_db_operation(python, cli, java_home, &b, "start", None)
        .await
        .map_err(|e| ("10/10 reset", format!("could not start app B: {e}")))?;

    // 1. No token -> refused, and the refusal REACHES the caller as words.
    let refused = npdev::run_db_operation(python, cli, java_home, &a, "reset", None).await;
    let refusal_text = match &refused {
        Ok(v) => format!("{} {}", op_output(v), v.get("error").map(|e| e.to_string()).unwrap_or_default()),
        Err(e) => e.clone(),
    };
    let refused_ok = refused.as_ref().map(|v| v.get("ok").and_then(|o| o.as_bool()) != Some(true)).unwrap_or(true);
    if !refused_ok || !refusal_names_the_token(&refusal_text) {
        return Err(("10/10 reset without the token",
                    format!("reset without the acknowledgement token was not refused with a message \
                             naming the token. A button is far easier to press than that token is to \
                             type, so the window must be at least as careful as the terminal. Got: {refusal_text}")));
    }

    // 2. With the token -> A is gone.
    npdev::run_db_operation(python, cli, java_home, &a, "reset",
                            Some("I_UNDERSTAND_DB_DATA_WILL_BE_DELETED"))
        .await
        .map_err(|e| ("10/10 reset with the token", e))?;
    let a_status = op_output(&npdev::run_db_operation(python, cli, java_home, &a, "status", None)
        .await.map_err(|e| ("10/10 reset", e))?);
    if reports_container_running(&a_status, "npdev-toolbox-a") {
        return Err(("10/10 reset with the token",
                    format!("app A's container survived its own reset. Output was:\n{a_status}")));
    }

    // 3. The neighbour is untouched -- QUAL-3 at the window layer.
    let b_status = op_output(&npdev::run_db_operation(python, cli, java_home, &b, "status", None)
        .await.map_err(|e| ("10/10 neighbour", e))?);
    if !reports_container_running(&b_status, "npdev-toolbox-b") {
        return Err(("10/10 neighbour destroyed by a reset aimed elsewhere",
                    format!("resetting app A took app B's container with it. This is QUAL-3: two apps \
                             in one folder sharing an identity, where the acknowledgement token does \
                             not help because the user types it correctly for the app they intend and \
                             different data is destroyed. Output was:\n{b_status}")));
    }
    println!("  [10/10] reset: refused without the token; destroyed A; app B untouched (QUAL-3)");
    Ok(())
}

/// [5/10]: DRIVE THE ENGINE PICKER, rather than proving the Manager merely builds.
///
/// storage/OPEN_ITEMS_PLAN.md §3 is blunt about this: "A harness that proves the Manager *builds*
/// proves nothing about a picker. Either drive it, or record E11 as BLOCKED with the reason -- do
/// not soften it to green because the installer works."
///
/// So this exercises the exact command `list_engines` runs and asserts the two properties the picker
/// depends on:
///
///   1. it returns engines at all -- an empty dropdown is the failure mode a build check cannot see;
///   2. every EXPERIMENTAL engine carries an honesty notice.
///
/// (2) is the one that matters. BREAKING.md calling MySQL "selectable but NOT supported" is not the
/// user being told; the notice has to arrive at the point of choice. A dropdown that silently offers
/// MySQL beside PostgreSQL is the silent-answer defect wearing a UI.
async fn run_engine_picker_step(python_path: &std::path::Path, cli_path: &std::path::Path,
                                java_home: Option<&str>) -> Result<(), StepError> {
    let listing = npdev::run_engines(python_path, cli_path, java_home)
        .await
        .map_err(|e| ("5/10 npdev engines", e))?;

    let engines = listing
        .get("engines")
        .and_then(|e| e.as_array())
        .cloned()
        .unwrap_or_default();
    if engines.is_empty() {
        return Err(("5/10 engine picker", "the engine list is EMPTY -- the picker would render an                     empty dropdown, which is exactly what a build-only check cannot see".to_string()));
    }

    let mut experimental = 0;
    for engine in &engines {
        let status = engine.get("status").and_then(|s| s.as_str()).unwrap_or("");
        let name = engine.get("externalName").and_then(|s| s.as_str()).unwrap_or("?");
        if status == "supported" {
            continue;
        }
        experimental += 1;
        let notice = engine.get("honestyNotice").and_then(|n| n.as_str()).unwrap_or("");
        if notice.is_empty() {
            return Err((
                "5/10 engine picker",
                format!("engine '{name}' is '{status}' but carries no honestyNotice -- the picker                          would offer it with no warning at the point of choice"),
            ));
        }
    }
    println!(
        "  [5/10] engine picker driven ................... ok  ({} engine(s), {experimental}          experimental, each with a notice)",
        engines.len()
    );
    Ok(())
}

/// [1/10]-[3/10] in the plan's sample output: resolve the Adoptium asset for this platform,
/// download it with checksum verification, then extract -- collapsed here because
/// `download_verify_extract` does all three as one atomic operation (a checksum mismatch never
/// leaves a partial extract behind for a later step to trip over).
async fn resolve_jdk_step(app_state: &state::AppState) -> Result<(), StepError> {
    if runtime::jdk_already_installed() {
        println!(
            "  [1/10] JDK already installed ................. ok  ({})",
            state::jdk_dir().display()
        );
        return Ok(());
    }
    let target = runtime::resolve_jdk17().await.map_err(|e| ("1/10 resolve JDK", e))?;
    println!("  [1/10] resolve JDK for this platform .......... ok  ({})", target.file_name);
    let dest = state::jdk_dir();
    runtime::download_verify_extract(&target, &dest, |_, _| {})
        .await
        .map_err(|e| ("2-3/10 download + extract JDK", e))?;
    println!("  [2/10] download + checksum ..................... ok");
    println!("  [3/10] extract to {} ......... ok", dest.display());
    let mut manager = app_state.manager.lock().expect("lock poisoned");
    manager.jdk_home = Some(dest.to_string_lossy().to_string());
    manager.save().map_err(|e| ("3/10 save jdk_home", e.to_string()))?;
    Ok(())
}

/// [4/10]: prefers a system Python (matches `resolve_python_exe`'s own order in `main.rs`) and only
/// downloads the pinned portable build when none is found -- exactly what should happen on a
/// container with no system Python at all.
async fn resolve_python_step(app_state: &state::AppState) -> Result<std::path::PathBuf, StepError> {
    let python_path = if let Some(system) = runtime::detect_system_python().await {
        println!("  [4/10] resolve Python (system) ................. ok  ({})", system.display());
        system
    } else if runtime::portable_python_already_installed() {
        let p = runtime::python_binary_in(&state::python_dir());
        println!("  [4/10] Python already installed ................. ok  ({})", p.display());
        p
    } else {
        let target = runtime::resolve_portable_python();
        let dest = state::python_dir();
        runtime::download_verify_extract(&target, &dest, |_, _| {})
            .await
            .map_err(|e| ("4/10 download + extract Python", e))?;
        let p = runtime::python_binary_in(&dest);
        println!("  [4/10] resolve/download Python .................. ok  ({})", p.display());
        p
    };
    let mut manager = app_state.manager.lock().expect("lock poisoned");
    manager.python_exe = Some(python_path.to_string_lossy().to_string());
    manager.save().map_err(|e| ("4/10 save python_exe", e.to_string()))?;
    Ok(python_path)
}

/// Part of [6/10]: an installed NPDev CLI is a precondition for `doctor`, not a separate numbered
/// step in the plan's sample -- installs the newest tag if none is present yet, the same path the
/// Install screen's version picker drives (`versions::install_version`).
async fn resolve_npdev_step(app_state: &state::AppState) -> Result<std::path::PathBuf, StepError> {
    let existing = app_state.manager.lock().expect("lock poisoned").current_version.clone();
    if let Some(tag) = existing {
        let cli = npdev::npdev_cli_path(&state::versions_dir().join(&tag));
        if cli.exists() {
            return Ok(cli);
        }
    }
    let tags = versions::list_tags(false).await.map_err(|e| ("5/10 list NPDev tags", e))?;
    let newest = tags
        .first()
        .ok_or_else(|| ("5/10 list NPDev tags", "the repository has no tags to install".to_string()))?;
    versions::install_version(app_state, &newest.name, |_, _| {})
        .await
        .map_err(|e| ("5/10 install NPDev version", e))?;
    Ok(npdev::npdev_cli_path(&state::versions_dir().join(&newest.name)))
}

/// Part of [6/10]: `npdev setup` (stages the runtimehost jars + AI knowledge index) is a
/// precondition for `doctor` to report every check passing -- the same as it is for a real user
/// via the Ready screen's Setup step. Found live (2026-08-05, I4's first container run): without
/// this, `--selftest` reached doctor with jars unstaged and only 6/9 checks passing, which was
/// this selftest's own bug, not a Manager defect -- `resolve_npdev_step` alone was never enough.
async fn run_setup_step(python_path: &std::path::Path, cli_path: &std::path::Path, java_home: Option<&str>) -> Result<(), StepError> {
    let result = npdev::run_setup_streaming_with(
        python_path.to_path_buf(),
        cli_path.to_path_buf(),
        java_home.map(str::to_string),
        |_value| {},
    )
    .await
    .map_err(|e| ("5/10 npdev setup", e))?;
    let jars_source = result.get("jarsSource").and_then(|v| v.as_str()).unwrap_or("?");
    println!("        npdev setup (stage jars + knowledge index) ..... ok  (jarsSource={jars_source})");
    Ok(())
}

/// [6/10]: the exact `doctor --json` call `check_doctor` makes, via the runtimes resolved above.
async fn run_doctor_step(python_path: &std::path::Path, cli_path: &std::path::Path, java_home: Option<&str>) -> Result<(), StepError> {
    // None: the selftest proves the MACHINE is ready (its whole point is a clean-machine install
    // path), and it has no app. Doctor's database checks are correctly absent from that question.
    let result = npdev::run_doctor(python_path, cli_path, java_home, None)
        .await
        .map_err(|e| ("6/10 npdev doctor", e))?;
    let checks = result.get("checks").and_then(|c| c.as_array()).cloned().unwrap_or_default();
    let total = checks.len();
    let passed = checks
        .iter()
        .filter(|c| c.get("status").and_then(|s| s.as_str()) == Some("pass"))
        .count();
    let ok = result.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    println!(
        "  [6/10] npdev doctor via private runtimes ....... {}  ({passed}/{total} checks pass)",
        if ok { "ok" } else { "FAIL" }
    );
    if ok {
        Ok(())
    } else {
        Err(("6/10 npdev doctor", format!("doctor reported failure ({passed}/{total} checks passed): {result}")))
    }
}

// ------------------------------------------------------------------------------------------------
// RED proofs for the three assertions above (ROUND2_START_PLAN.md 2.5).
//
// "A step that has only ever passed is not a step." Each test below feeds the assertion the SHAPE
// of the defect it exists to catch and requires it to say no. They need no docker, no database and
// no install path, so they run in `cargo test` on every machine -- while the manager-db-toolbox job
// proves the wiring end to end against a real engine.
// ------------------------------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    // What Status-Environment.ps1 actually prints, both ways.
    const HEADER: &str = "CONTAINER ID   IMAGE     COMMAND   CREATED   STATUS    PORTS     NAMES";
    const RUNNING: &str = "CONTAINER ID   IMAGE         COMMAND       CREATED   STATUS         PORTS   NAMES\n\
                           a73865e3026b   postgres:16   \"docker-e…\"   1 min     Up 55 seconds  ...     npdev-toolbox-a";
    const EXITED: &str = "CONTAINER ID   IMAGE         COMMAND       CREATED   STATUS                     PORTS   NAMES\n\
                          a73865e3026b   postgres:16   \"docker-e…\"   1 min     Exited (0) 3 seconds ago           npdev-toolbox-a";

    #[test]
    fn running_container_is_reported_running() {
        assert!(reports_container_running(RUNNING, "npdev-toolbox-a"));
    }

    /// RED: a `start` that exits 0 without starting anything. Header only, no container.
    #[test]
    fn a_start_that_did_nothing_is_not_reported_running() {
        assert!(!reports_container_running(HEADER, "npdev-toolbox-a"),
                "an empty docker listing must not read as running -- that is exactly the no-op \
                 `start` this assertion exists to catch");
    }

    /// RED: the subtle one. `docker ps -a` lists EXITED containers by name, so matching the name
    /// alone would report a stopped engine as running -- and a `stop` that no-ops would pass.
    #[test]
    fn an_exited_container_is_not_reported_running() {
        assert!(!reports_container_running(EXITED, "npdev-toolbox-a"),
                "a container listed as Exited must not read as running; matching the NAME alone \
                 would let a `stop` that did nothing pass");
    }

    /// RED: the neighbour check must not be satisfied by a DIFFERENT app's container.
    #[test]
    fn another_apps_container_does_not_satisfy_the_neighbour_check() {
        assert!(!reports_container_running(RUNNING, "npdev-toolbox-b"),
                "app A's running container must not stand in for app B -- that would make QUAL-3's \
                 regression test pass while B had been destroyed");
    }

    #[test]
    fn complete_connection_details_pass() {
        let ok = "Database type: PostgreSQL\nHost: 127.0.0.1\nPort: 15511\nDatabase: toolbox_a";
        assert_eq!(missing_connection_field(ok), None);
    }

    /// RED: a connection screen missing the port is a dead end for the user.
    #[test]
    fn connection_details_missing_a_field_are_caught() {
        let no_port = "Database type: PostgreSQL\nHost: 127.0.0.1\nDatabase: toolbox_a";
        assert_eq!(missing_connection_field(no_port), Some("Port"));
    }

    #[test]
    fn a_refusal_naming_the_token_passes() {
        assert!(refusal_names_the_token(
            "Reset refused: it DELETES this app's data. Re-run with --confirm I_UNDERSTAND_DB_DATA_WILL_BE_DELETED"));
    }

    /// RED: "refused" without the token leaves the reader guessing what to type.
    #[test]
    fn a_bare_refusal_is_not_enough() {
        assert!(!refusal_names_the_token("Reset refused."),
                "a refusal that does not name the token tells the user they were stopped but not \
                 how to proceed deliberately");
    }
}
