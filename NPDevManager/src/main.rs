// Prevents an extra console window on Windows in release builds -- the Manager's whole point is
// a window instead of a terminal.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod log;
mod npdev;
mod runtime;
mod selftest;
mod state;
mod versions;

use std::path::PathBuf;

use serde::Serialize;
use serde_json::Value;
use tauri::{Emitter, State};

use state::{AppEntry, AppState, InstalledVersion};

// -------------------------------------------------------------------------------------------
// Resolution helpers -- every command below funnels through these rather than duplicating "which
// python, which npdev_cli.py, which JAVA_HOME" logic (the standing rule: no CLI behaviour lives
// in Rust, only enough plumbing to invoke the CLI the same way a terminal user would).
// -------------------------------------------------------------------------------------------

fn resolve_java_home(app_state: &AppState) -> Option<String> {
    if npdev::fake_mode() {
        return None;
    }
    app_state.manager.lock().expect("lock poisoned").jdk_home.clone()
}

async fn resolve_python_exe(app_state: &AppState) -> Result<PathBuf, String> {
    if npdev::fake_mode() {
        return Ok(PathBuf::from("python"));
    }
    let existing = app_state.manager.lock().expect("lock poisoned").python_exe.clone();
    if let Some(p) = existing {
        return Ok(PathBuf::from(p));
    }
    if let Some(system) = runtime::detect_system_python().await {
        let mut m = app_state.manager.lock().expect("lock poisoned");
        m.python_exe = Some(system.to_string_lossy().to_string());
        let _ = m.save();
        return Ok(system);
    }
    let portable = runtime::python_binary_in(&state::python_dir());
    if portable.exists() {
        return Ok(portable);
    }
    Err("no Python found -- run Install Python first (M4)".to_string())
}

fn resolve_npdev_cli(app_state: &AppState) -> Result<PathBuf, String> {
    if npdev::fake_mode() {
        return Ok(PathBuf::from("npdev_cli.py"));
    }
    let manager = app_state.manager.lock().expect("lock poisoned");
    let dir = state::current_version_dir(&manager)
        .ok_or_else(|| "no NPDev version installed -- install one first".to_string())?;
    let cli = npdev::npdev_cli_path(&dir);
    if !cli.exists() {
        return Err(format!("npdev_cli.py not found at {}", cli.display()));
    }
    Ok(cli)
}

// -------------------------------------------------------------------------------------------
// M1: stub mode plumbing
// -------------------------------------------------------------------------------------------

#[tauri::command]
fn is_fake_mode() -> bool {
    npdev::fake_mode()
}

#[tauri::command]
fn fake_doctor_scenarios() -> Vec<&'static str> {
    npdev::fake_doctor_scenario_names()
}

#[tauri::command]
fn set_fake_doctor_scenario(name: String) {
    *npdev::FAKE_DOCTOR_SCENARIO.lock().expect("lock poisoned") = name;
}

// -------------------------------------------------------------------------------------------
// M2: Ready screen
// -------------------------------------------------------------------------------------------

/// `app_dir` (M15) selects WHICH app's database the six database checks run against. Optional
/// because doctor's other ten checks are about the machine and must still answer on a machine with
/// no app on it at all -- "no app yet" is not a broken machine.
#[tauri::command]
async fn check_doctor(state: State<'_, AppState>, app_dir: Option<String>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_doctor(
            &PathBuf::from("python"),
            &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(),
            app_dir.as_deref(),
        )
        .await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_doctor(&python, &cli, java_home.as_deref(), app_dir.as_deref()).await
}

/// M13: "Test connection", beside the connection fields on the create-app form.
///
/// The highest-value half-day in the stabilize plan, and it is pure wiring: every check it renders
/// already existed and was already RED-proven by failing fixtures. What did not exist was any way to
/// ask them BEFORE an app was scaffolded -- which is precisely when a user has just typed a port and
/// wants to know if it is right.
#[allow(clippy::too_many_arguments)]
#[tauri::command]
async fn test_connection(
    state: State<'_, AppState>,
    engine: String,
    db_host: Option<String>,
    db_port: Option<u16>,
    db_user: Option<String>,
    db_password: Option<String>,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_db_test_connection(
            &PathBuf::from("python"),
            &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(),
            &engine,
            db_host.as_deref(),
            db_port,
            db_user.as_deref(),
            db_password.as_deref(),
        )
        .await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_db_test_connection(
        &python,
        &cli,
        java_home.as_deref(),
        &engine,
        db_host.as_deref(),
        db_port,
        db_user.as_deref(),
        db_password.as_deref(),
    )
    .await
}

// -------------------------------------------------------------------------------------------
// M3: private JDK -- the thesis. Emits `jdk-progress` events as it downloads.
// -------------------------------------------------------------------------------------------

#[derive(Serialize, Clone)]
struct DownloadProgress {
    downloaded: u64,
    total: Option<u64>,
}

#[tauri::command]
fn jdk_status() -> Value {
    let installed = runtime::jdk_already_installed();
    serde_json::json!({
        "installed": installed,
        "path": if installed { Some(state::jdk_dir().to_string_lossy().to_string()) } else { None },
    })
}

#[tauri::command]
async fn install_jdk(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let target = runtime::resolve_jdk17().await?;
    let dest = state::jdk_dir();
    let app2 = app.clone();
    runtime::download_verify_extract(&target, &dest, move |downloaded, total| {
        let _ = app2.emit("jdk-progress", DownloadProgress { downloaded, total });
    })
    .await?;
    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.jdk_home = Some(dest.to_string_lossy().to_string());
    manager.save().map_err(|e| e.to_string())?;
    Ok(())
}

// -------------------------------------------------------------------------------------------
// M4: private Python, NPDev tag zip, setup
// -------------------------------------------------------------------------------------------

#[tauri::command]
async fn python_status(state: State<'_, AppState>) -> Result<Value, String> {
    let system = runtime::detect_system_python().await;
    let portable_installed = runtime::portable_python_already_installed();
    let resolved = state.manager.lock().expect("lock poisoned").python_exe.clone();
    Ok(serde_json::json!({
        "systemPython": system.map(|p| p.to_string_lossy().to_string()),
        "portableInstalled": portable_installed,
        "resolved": resolved,
    }))
}

#[tauri::command]
async fn install_python(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let target = runtime::resolve_portable_python();
    let dest = state::python_dir();
    let app2 = app.clone();
    runtime::download_verify_extract(&target, &dest, move |downloaded, total| {
        let _ = app2.emit("python-progress", DownloadProgress { downloaded, total });
    })
    .await?;
    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.python_exe = Some(runtime::python_binary_in(&dest).to_string_lossy().to_string());
    manager.save().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
async fn list_tags(force_refresh: bool) -> Result<Vec<versions::TagInfo>, String> {
    versions::list_tags(force_refresh).await
}

#[tauri::command]
async fn install_npdev_version(app: tauri::AppHandle, state: State<'_, AppState>, tag: String) -> Result<(), String> {
    let app2 = app.clone();
    versions::install_version(&state, &tag, move |downloaded, total| {
        let _ = app2.emit("version-install-progress", DownloadProgress { downloaded, total });
    })
    .await
}

#[tauri::command]
async fn run_setup(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_setup_streaming(app, python, cli, java_home).await
}

// -------------------------------------------------------------------------------------------
// M5: Apps
// -------------------------------------------------------------------------------------------

#[tauri::command]
fn list_apps(state: State<'_, AppState>) -> Vec<AppEntry> {
    state.manager.lock().expect("lock poisoned").apps.clone()
}

/// W5.3 requirement 1: the engine picker's options come from `npdev engines --json`, never from a
/// list written here. The status attached to each engine ("experimental", and why) is what the form
/// must show at the point of choice; a copy in this file would drift the day that changes, and the
/// user would be told an engine is fine when the platform no longer claims so.
#[tauri::command]
async fn list_engines(state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_engines(&python, &cli, java_home.as_deref()).await
}

#[allow(clippy::too_many_arguments)]
#[tauri::command]
async fn create_app(
    state: State<'_, AppState>,
    name: String,
    parent_dir: String,
    engine: Option<String>,
    db_host: Option<String>,
    db_port: Option<u16>,
    db_user: Option<String>,
    db_password: Option<String>,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    let target_dir = PathBuf::from(&parent_dir).join(&name);
    let result = npdev::run_init(
        &python,
        &cli,
        java_home.as_deref(),
        &target_dir.to_string_lossy(),
        engine.as_deref(),
        db_host.as_deref(),
        db_port,
        db_user.as_deref(),
        db_password.as_deref(),
    )
    .await?;

    let directory = result
        .get("created")
        .and_then(|c| c.get("directory"))
        .and_then(|d| d.as_str())
        .unwrap_or(&parent_dir)
        .to_string();

    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.apps.push(AppEntry {
        name: name.clone(),
        directory,
        created_at: versions::chrono_now_iso(),
    });
    manager.save().map_err(|e| e.to_string())?;
    Ok(result)
}

/// M14: Start / Stop / Status / Connection details / Reset, without a terminal.
///
/// The Manager exists to remove the terminal, and until now the newest feature was terminal-only: a
/// user could pick MySQL in this window and then had to open PowerShell to start it. `reset` carries
/// the same acknowledgement token the CLI and the generated script both demand -- a button is far
/// easier to press than that token is to type, so the window must be at least as careful as the
/// terminal, never less.
#[tauri::command]
async fn db_operation(
    state: State<'_, AppState>,
    app_dir: String,
    operation: String,
    confirm: Option<String>,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_db_operation(
            &PathBuf::from("python"),
            &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(),
            &app_dir,
            &operation,
            confirm.as_deref(),
        )
        .await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_db_operation(&python, &cli, java_home.as_deref(), &app_dir, &operation, confirm.as_deref()).await
}

#[tauri::command]
fn open_folder(app: tauri::AppHandle, path: String) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    app.opener().open_path(path, None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
fn open_url(app: tauri::AppHandle, url: String) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string())
}

// -------------------------------------------------------------------------------------------
// M6: Run
// -------------------------------------------------------------------------------------------

/// B2: the Run screen keeps its one-at-a-time UX BY POLICY, not by type. The registry can hold many
/// apps (the Monitor starts several); this screen refuses a second one because that is what its own
/// single log view can honestly display.
const RUN_SCREEN_KEY: &str = "__run_screen__";

#[tauri::command]
async fn start_dev(app: tauri::AppHandle, state: State<'_, AppState>, app_dir: String, port: u16) -> Result<(), String> {
    {
        let running = state.running.lock().expect("lock poisoned");
        if running.contains_key(RUN_SCREEN_KEY) {
            return Err("dev is already running -- stop it first".to_string());
        }
    }
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    log::info(format!("start_dev app_dir={app_dir} port={port}"));
    let running = npdev::start_dev_streaming(app, python, cli, java_home, PathBuf::from(app_dir), port).await?;
    if let Some(proc) = running {
        state.running.lock().expect("lock poisoned").insert(RUN_SCREEN_KEY.to_string(), proc);
    }
    Ok(())
}

#[tauri::command]
async fn stop_dev(state: State<'_, AppState>) -> Result<(), String> {
    let running = state.running.lock().expect("lock poisoned").remove(RUN_SCREEN_KEY);
    if let Some(proc) = running {
        log::info("stop_dev");
        npdev::stop_running_process(proc).await?;
    }
    Ok(())
}

// -------------------------------------------------------------------------------------------
// MONITOR_PLAN Phase B: The Monitor.
//
// Every command here is a pipe to `npdev monitor ...`. Nothing decides anything: not what counts as
// an app, not what counts as healthy, not where the engine lives. Those answers belong to the CLI so
// that the window and a terminal cannot disagree, and so that stub mode can show a REAL answer that
// was captured rather than a shape somebody invented.
// -------------------------------------------------------------------------------------------

#[tauri::command]
async fn monitor_scan(state: State<'_, AppState>, include_info: Option<bool>) -> Result<Value, String> {
    // The UNION of D7: paths the user asked us to inspect, plus the directory of every app the
    // Manager itself created. The second half is what makes the Monitor useful on first launch with
    // nothing configured.
    let (mut paths, app_dirs) = {
        let manager = state.manager.lock().expect("lock poisoned");
        (
            manager.inspect_paths.clone(),
            manager.apps.iter().map(|a| a.directory.clone()).collect::<Vec<_>>(),
        )
    };
    for dir in app_dirs {
        // The app's own directory AND its parent: `npdev init <d>` generates into `<d>-app`, so the
        // registered directory is often the model folder rather than the FinalApp.
        if let Some(parent) = PathBuf::from(&dir).parent().map(|p| p.to_string_lossy().to_string()) {
            if !paths.contains(&parent) {
                paths.push(parent);
            }
        }
        if !paths.contains(&dir) {
            paths.push(dir);
        }
    }
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_monitor_scan(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                       java_home.as_deref(), &paths, include_info.unwrap_or(false)).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_monitor_scan(&python, &cli, java_home.as_deref(), &paths, include_info.unwrap_or(false)).await
}

#[tauri::command]
async fn monitor_probe(state: State<'_, AppState>, app_dir: String, include_info: Option<bool>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_monitor_probe(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                        java_home.as_deref(), &app_dir, include_info.unwrap_or(false)).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_monitor_probe(&python, &cli, java_home.as_deref(), &app_dir, include_info.unwrap_or(false)).await
}

/// B4: the inspector's data. `--include-info` inlines the app's own generated `info.json`; the probe
/// supplies the machine-specific rows the emitter deliberately does not bake (D2-a). An app built
/// before the emitter simply has no `info` field, and the UI says "regenerate for the full
/// inspector" rather than rendering an empty panel.
#[tauri::command]
async fn read_info_json(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    monitor_probe(state, app_dir, Some(true)).await
}

#[tauri::command]
fn get_inspect_paths(state: State<'_, AppState>) -> Vec<String> {
    state.manager.lock().expect("lock poisoned").inspect_paths.clone()
}

#[tauri::command]
fn set_inspect_paths(state: State<'_, AppState>, paths: Vec<String>) -> Result<(), String> {
    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.inspect_paths = paths.into_iter().filter(|p| !p.trim().is_empty()).collect();
    log::info(format!("inspect paths set to {:?}", manager.inspect_paths));
    manager.save().map_err(|e| e.to_string())
}

/// B5. Streams as `ops-event`. A destructive script carries the SAME acknowledgement token the
/// generated script demands -- a button is far easier to press than that token is to type, so the
/// window has to be at least as careful as the terminal (M14's rule).
#[tauri::command]
async fn run_ops_script(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    app_dir: String,
    script: String,
    confirm: Option<String>,
) -> Result<(), String> {
    let java_home = resolve_java_home(&state);
    let key = format!("ops:{app_dir}:{script}");
    {
        let running = state.running.lock().expect("lock poisoned");
        if running.contains_key(&key) {
            return Err(format!("{script} is already running for this app -- wait for it to finish"));
        }
    }
    let (python, cli) = if npdev::fake_mode() {
        (PathBuf::from("python"), PathBuf::from("npdev_cli.py"))
    } else {
        (resolve_python_exe(&state).await?, resolve_npdev_cli(&state)?)
    };
    log::info(format!("ops {script} for {app_dir}{}", if confirm.is_some() { " (confirmed)" } else { "" }));
    let running = npdev::run_ops_script_streaming(app, python, cli, java_home, app_dir, script, confirm).await?;
    if let Some(proc) = running {
        state.running.lock().expect("lock poisoned").insert(key, proc);
    }
    Ok(())
}

/// Stop an app the Monitor can see. Two cases, and conflating them is how an orphan is born:
/// a process THIS Manager started is killed as a tree; anything else -- started by a terminal, by a
/// previous Manager session, by the app's own script -- is stopped through the app's own
/// `Stop-Environment` runbook, because that is the thing that knows how.
#[tauri::command]
async fn stop_app(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    app_dir: String,
) -> Result<Value, String> {
    let owned = state.running.lock().expect("lock poisoned").remove(&app_dir);
    if let Some(proc) = owned {
        log::info(format!("stop_app (owned process tree) {app_dir}"));
        npdev::stop_running_process(proc).await?;
        return Ok(serde_json::json!({"ok": true, "how": "killed the process tree this window started"}));
    }
    log::info(format!("stop_app (via the app's own runbook) {app_dir}"));
    run_ops_script(app, state, app_dir, "stop-environment".to_string(), None).await?;
    Ok(serde_json::json!({
        "ok": true,
        "how": "ran the app's own Stop-Environment script -- this window did not start it",
    }))
}

#[tauri::command]
async fn start_app(app: tauri::AppHandle, state: State<'_, AppState>, app_dir: String) -> Result<(), String> {
    let key = app_dir.clone();
    {
        let running = state.running.lock().expect("lock poisoned");
        if running.contains_key(&key) {
            return Err("this app is already running from this window".to_string());
        }
    }
    let java_home = resolve_java_home(&state);
    let (python, cli) = if npdev::fake_mode() {
        (PathBuf::from("python"), PathBuf::from("npdev_cli.py"))
    } else {
        (resolve_python_exe(&state).await?, resolve_npdev_cli(&state)?)
    };
    log::info(format!("start_app {app_dir}"));
    let running = npdev::run_ops_script_streaming(app, python, cli, java_home, app_dir,
                                                  "run-finalapp".to_string(), None).await?;
    if let Some(proc) = running {
        state.running.lock().expect("lock poisoned").insert(key, proc);
    }
    Ok(())
}

/// Which apps THIS window is holding a process for. Deliberately not "which apps are running" --
/// that question is answered by `monitor_probe`, from the machine, because a Manager restarted after
/// a crash holds nothing while the apps keep serving. Trusting memory for that is the AppState
/// desync M0-M8 spent a day on.
#[tauri::command]
fn owned_processes(state: State<'_, AppState>) -> Vec<String> {
    state.running.lock().expect("lock poisoned").keys().cloned().collect()
}

// -------------------------------------------------------------------------------------------
// D10: logs
// -------------------------------------------------------------------------------------------

#[tauri::command]
async fn monitor_logs(state: State<'_, AppState>, app_dir: String, source: Option<String>, tail: Option<u32>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let source = source.unwrap_or_else(|| "all".to_string());
    if npdev::fake_mode() {
        return npdev::run_monitor_logs(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                       java_home.as_deref(), &app_dir, &source, tail.unwrap_or(200)).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_monitor_logs(&python, &cli, java_home.as_deref(), &app_dir, &source, tail.unwrap_or(200)).await
}

#[tauri::command]
async fn export_logs(state: State<'_, AppState>, app_dir: String, out_zip: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    log::info(format!("export_logs {app_dir} -> {out_zip}"));
    if npdev::fake_mode() {
        return npdev::run_monitor_logs_export(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                              java_home.as_deref(), &app_dir, &out_zip).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_monitor_logs_export(&python, &cli, java_home.as_deref(), &app_dir, &out_zip).await
}

#[tauri::command]
fn manager_log_path() -> String {
    log::log_file().to_string_lossy().to_string()
}

// -------------------------------------------------------------------------------------------
// D9 + Phase D: the exploration engine and the Scrap Manager
// -------------------------------------------------------------------------------------------

#[tauri::command]
async fn engine_status(state: State<'_, AppState>, fake_scenario: Option<String>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let configured = state.manager.lock().expect("lock poisoned").scrapforai_root.clone();
    let owned = state.engine.lock().expect("lock poisoned").is_some();
    let mut result = if npdev::fake_mode() {
        npdev::run_monitor_engine(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                  java_home.as_deref(), 3010, configured.as_deref(),
                                  fake_scenario.as_deref().unwrap_or("engine-running")).await?
    } else {
        let python = resolve_python_exe(&state).await?;
        let cli = resolve_npdev_cli(&state)?;
        npdev::run_monitor_engine(&python, &cli, java_home.as_deref(), 3010, configured.as_deref(), "").await?
    };
    // Whether WE started it decides whether we may stop it. Stopping an engine a user started by
    // hand, from a window they did not use to start it, is a surprise this feature does not need.
    if let Some(object) = result.as_object_mut() {
        object.insert("startedByThisWindow".to_string(), Value::Bool(owned));
    }
    Ok(result)
}

#[tauri::command]
fn remember_engine_root(state: State<'_, AppState>, root: String) -> Result<(), String> {
    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.scrapforai_root = if root.trim().is_empty() { None } else { Some(root) };
    log::info(format!("scrapforai_root remembered: {:?}", manager.scrapforai_root));
    manager.save().map_err(|e| e.to_string())
}

#[tauri::command]
async fn start_engine(state: State<'_, AppState>, root: String, origins: Vec<String>) -> Result<(), String> {
    {
        if state.engine.lock().expect("lock poisoned").is_some() {
            return Err("the exploration engine is already running from this window".to_string());
        }
    }
    if npdev::fake_mode() {
        return Ok(());
    }
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    log::info(format!("start_engine root={root} origins={origins:?}"));
    let proc = npdev::start_engine_streaming(python, cli, java_home, root, 3010, origins).await?;
    *state.engine.lock().expect("lock poisoned") = Some(proc);
    Ok(())
}

#[tauri::command]
async fn stop_engine(state: State<'_, AppState>) -> Result<(), String> {
    let proc = state.engine.lock().expect("lock poisoned").take();
    if let Some(proc) = proc {
        log::info("stop_engine");
        npdev::stop_running_process(proc).await?;
    }
    Ok(())
}

/// Every `npdev explore ...` invocation funnels through here, and every FAILURE is logged.
///
/// That logging is the point of D10 from the other end: when a tester says "it did not work", the
/// support bundle has to contain what the Manager actually tried and what came back. A failure that
/// only ever existed as a red toast in a window that has since been closed is not evidence.
async fn explore_command(state: &State<'_, AppState>, args: Vec<String>, label: &str) -> Result<Value, String> {
    let java_home = resolve_java_home(state);
    let result = if npdev::fake_mode() {
        npdev::run_explore(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                           java_home.as_deref(), args, label).await
    } else {
        let python = resolve_python_exe(state).await?;
        let cli = resolve_npdev_cli(state)?;
        npdev::run_explore(&python, &cli, java_home.as_deref(), args, label).await
    };
    match &result {
        Err(message) => log::error(format!("{label} failed: {message}")),
        Ok(value) if value.get("ok") == Some(&Value::Bool(false)) => {
            log::warn(format!("{label} reported a problem: {}",
                              value.get("error").map(|e| e.to_string()).unwrap_or_default()));
        }
        _ => {}
    }
    result
}

#[tauri::command]
async fn explore_list(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    explore_command(&state, vec!["explore".into(), "list".into(), "--json".into(),
                                 "--app-dir".into(), app_dir], "explore list").await
}

#[tauri::command]
async fn explore_show(state: State<'_, AppState>, app_dir: String, run_id: String) -> Result<Value, String> {
    explore_command(&state, vec!["explore".into(), "show".into(), "--json".into(),
                                 "--app-dir".into(), app_dir, "--run".into(), run_id], "explore show").await
}

/// D3: the UI never validates on its own. It shows the CLI's messages VERBATIM, so "valid in the
/// window" and "the engine accepts it" cannot drift apart.
#[tauri::command]
async fn explore_validate(state: State<'_, AppState>, file: String, base_url: Option<String>) -> Result<Value, String> {
    let mut args = vec!["explore".to_string(), "validate".to_string(), "--json".to_string(),
                        "--file".to_string(), file];
    if let Some(url) = base_url.filter(|u| !u.is_empty()) {
        args.push("--base-url".to_string());
        args.push(url);
    }
    explore_command(&state, args, "explore validate").await
}

/// D4: each precondition reported as its own row, BEFORE anything runs. A failed precondition is
/// never rendered like a failed exploration -- the QUAL-4 lesson: a tool problem dressed as a test
/// result teaches people to distrust the tests.
#[tauri::command]
async fn explore_preflight(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    explore_command(&state, vec!["explore".into(), "preflight".into(), "--json".into(),
                                 "--app-dir".into(), app_dir], "explore preflight").await
}

#[tauri::command]
async fn explore_run(state: State<'_, AppState>, app_dir: String, file: String) -> Result<Value, String> {
    let engine_root = state.manager.lock().expect("lock poisoned").scrapforai_root.clone();
    let mut args = vec!["explore".to_string(), "run".to_string(), "--json".to_string(),
                        "--app-dir".to_string(), app_dir, "--file".to_string(), file,
                        "--driver".to_string(), "monitor-ui".to_string()];
    if let Some(root) = engine_root.filter(|r| !r.is_empty()) {
        args.push("--engine-root".to_string());
        args.push(root);
    }
    explore_command(&state, args, "explore run").await
}

#[tauri::command]
async fn explore_accept_baseline(state: State<'_, AppState>, app_dir: String, run_id: String) -> Result<Value, String> {
    explore_command(&state, vec!["explore".into(), "accept".into(), "--json".into(),
                                 "--app-dir".into(), app_dir, "--run".into(), run_id], "explore accept").await
}

#[tauri::command]
async fn explore_pin(state: State<'_, AppState>, app_dir: String, run_id: String, ledger: Option<String>, unpin: Option<bool>) -> Result<Value, String> {
    let mut args = vec!["explore".to_string(), "pin".to_string(), "--json".to_string(),
                        "--app-dir".to_string(), app_dir, "--run".to_string(), run_id];
    if let Some(id) = ledger.filter(|l| !l.is_empty()) {
        args.push("--ledger".to_string());
        args.push(id);
    }
    if unpin.unwrap_or(false) {
        args.push("--unpin".to_string());
    }
    explore_command(&state, args, "explore pin").await
}

#[tauri::command]
async fn explore_context(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    explore_command(&state, vec!["explore".into(), "context".into(), "--json".into(),
                                 "--app-dir".into(), app_dir], "explore context").await
}

/// D3: writing a routine into the app's `explorations/` folder. Deliberately a Rust command rather
/// than a CLI verb, because it is a FILE WRITE with no decision in it -- and it refuses to write
/// anywhere but that folder, since the filename arrives from a text box.
#[tauri::command]
fn save_routine(app_dir: String, name: String, content: String) -> Result<String, String> {
    let stem: String = name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .collect();
    if stem.is_empty() {
        return Err("give the routine a name (letters, digits, - and _)".to_string());
    }
    serde_json::from_str::<Value>(&content).map_err(|e| format!("that is not valid JSON: {e}"))?;
    let dir = PathBuf::from(&app_dir).join("_ops").join("explorations");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join(format!("{stem}.json"));
    std::fs::write(&path, content).map_err(|e| e.to_string())?;
    log::info(format!("saved routine {}", path.display()));
    Ok(path.to_string_lossy().to_string())
}

// -------------------------------------------------------------------------------------------
// M7: Versions
// -------------------------------------------------------------------------------------------

#[tauri::command]
fn list_installed_versions(state: State<'_, AppState>) -> Vec<InstalledVersion> {
    versions::list_installed_versions(&state)
}

#[tauri::command]
fn current_version(state: State<'_, AppState>) -> Option<String> {
    state.manager.lock().expect("lock poisoned").current_version.clone()
}

#[tauri::command]
fn set_current_version(state: State<'_, AppState>, tag: String) -> Result<(), String> {
    versions::set_current_version(&state, &tag)
}

#[tauri::command]
fn remove_installed_version(state: State<'_, AppState>, tag: String) -> Result<(), String> {
    versions::remove_version(&state, &tag)
}

#[tauri::command]
fn manager_home_path() -> String {
    state::manager_home().to_string_lossy().to_string()
}

// -------------------------------------------------------------------------------------------
// Phase E: the Assistant.
//
// E3-a is the shape of this whole section, and it is structural rather than a UI convention:
// COMPOSE and SEND are two commands. `assistant_compose` produces the exact bytes and sends
// nothing; `assistant_generate` sends exactly what it is handed. A user cannot be surprised by what
// left their machine, because the only thing that can leave is the thing they were shown and then
// pressed Send on.
//
// The Manager never bundles an API key (E2). A provider is something the user configures with their
// own credential, or the tab renders an honest "assistant not configured" state -- and the JSON tab
// is never blocked by its absence.
// -------------------------------------------------------------------------------------------

#[tauri::command]
fn assistant_config(state: State<'_, AppState>) -> Value {
    let manager = state.manager.lock().expect("lock poisoned");
    match &manager.assistant {
        None => serde_json::json!({"configured": false}),
        Some(config) => serde_json::json!({
            "configured": true,
            "kind": config.kind,
            "command": config.command,
            "endpoint": config.endpoint,
            "model": config.model,
            // The key is never returned to the UI, only whether one is set. A window that can read
            // it back is a window that can leak it into a screenshot.
            "hasApiKey": config.api_key.as_ref().map(|k| !k.is_empty()).unwrap_or(false),
        }),
    }
}

#[tauri::command]
fn set_assistant_config(
    state: State<'_, AppState>,
    kind: String,
    command: Option<Vec<String>>,
    endpoint: Option<String>,
    api_key: Option<String>,
    model: Option<String>,
) -> Result<(), String> {
    let mut manager = state.manager.lock().expect("lock poisoned");
    let existing_key = manager.assistant.as_ref().and_then(|a| a.api_key.clone());
    manager.assistant = Some(state::AssistantConfig {
        kind,
        command: command.unwrap_or_default(),
        endpoint,
        // An empty box means "leave it alone", not "erase it" -- the UI cannot show the key, so it
        // cannot send it back either, and treating blank as a delete would wipe the key every save.
        api_key: match api_key {
            Some(key) if !key.is_empty() => Some(key),
            _ => existing_key,
        },
        model,
    });
    log::info("assistant provider configured");
    manager.save().map_err(|e| e.to_string())
}

/// Produces the payload and sends NOTHING. `includePageText` defaults to false: the default excerpt
/// is selectors, tags, attributes and the error -- no text nodes -- because most failures are
/// selector problems and that is the cheapest way to not send somebody's data anywhere.
#[tauri::command]
async fn assistant_compose(
    state: State<'_, AppState>,
    app_dir: String,
    prompt: String,
    run_id: Option<String>,
    include_page_text: Option<bool>,
) -> Result<Value, String> {
    let mut args = vec!["explore".to_string(), "repair-payload".to_string(), "--json".to_string(),
                        "--app-dir".to_string(), app_dir, "--prompt".to_string(), prompt];
    if let Some(id) = run_id.filter(|r| !r.is_empty()) {
        args.push("--run".to_string());
        args.push(id);
    }
    if include_page_text.unwrap_or(false) {
        args.push("--include-page-text".to_string());
    }
    explore_command(&state, args, "explore repair-payload").await
}

/// Sends EXACTLY the payload it is given -- which is the payload the user saw. It deliberately does
/// not re-compose: re-composing would mean the bytes that leave are not provably the bytes that
/// were displayed, and that difference is the entire guarantee.
#[tauri::command]
async fn assistant_generate(state: State<'_, AppState>, payload: Value) -> Result<Value, String> {
    let config = state.manager.lock().expect("lock poisoned").assistant.clone();
    let config = config.ok_or_else(|| {
        "no assistant provider is configured -- set one in Settings, or write the routine in the \
         JSON tab (which never needs one)".to_string()
    })?;
    let java_home = resolve_java_home(&state);
    let (python, cli) = if npdev::fake_mode() {
        (PathBuf::from("python"), PathBuf::from("npdev_cli.py"))
    } else {
        (resolve_python_exe(&state).await?, resolve_npdev_cli(&state)?)
    };

    // The payload goes through a FILE, never a command line: a DOM excerpt on an argv lands in shell
    // history and in every process listing on the machine.
    let dir = std::env::temp_dir().join("npdev-manager-assistant");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let payload_path = dir.join(format!("payload-{}.json", versions::chrono_now_iso().replace(':', "-")));
    std::fs::write(&payload_path, serde_json::to_vec_pretty(&payload).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;

    let mut args = vec!["ai".to_string(), "generate-routine".to_string(), "--json".to_string(),
                        "--payload-file".to_string(), payload_path.to_string_lossy().to_string(),
                        "--provider".to_string(), config.kind.clone()];
    for part in &config.command {
        args.push("--command".to_string());
        args.push(part.clone());
    }
    if let Some(endpoint) = &config.endpoint {
        args.push("--endpoint".to_string());
        args.push(endpoint.clone());
    }
    if let Some(key) = &config.api_key {
        args.push("--api-key".to_string());
        args.push(key.clone());
    }
    if let Some(model) = &config.model {
        args.push("--model".to_string());
        args.push(model.clone());
    }
    log::info(format!("assistant_generate via provider kind={}", config.kind));
    let result = npdev::run_explore(&python, &cli, java_home.as_deref(), args, "ai generate-routine").await;
    let _ = std::fs::remove_file(&payload_path);
    result
}

fn main() {
    // I1 (CLOSEOUT_PLAN.md): a headless proof the whole install path works with no window, so
    // "does the Manager work on a clean machine" can be checked by a container on every change
    // instead of only by a human clicking through five screens. Must run before Tauri's own
    // Builder ever touches a display -- `--selftest` never opens one.
    if std::env::args().any(|a| a == "--selftest") {
        let rt = tokio::runtime::Runtime::new().expect("failed to start a tokio runtime for --selftest");
        std::process::exit(rt.block_on(selftest::run()));
    }

    state::ensure_dirs().expect("could not create the Manager's home directories");
    // D10 source 3. `logs_dir()` has been created on every startup since M0-M8 and NOTHING had ever
    // written into it -- while HANDOVER.md §5 told testers to send the .log files from there (and
    // named the wrong directory besides). This is the line that makes that instruction true.
    log::startup_banner();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(AppState::new())
        .invoke_handler(tauri::generate_handler![
            is_fake_mode,
            fake_doctor_scenarios,
            set_fake_doctor_scenario,
            check_doctor,
            test_connection,
            jdk_status,
            install_jdk,
            python_status,
            install_python,
            list_tags,
            install_npdev_version,
            run_setup,
            list_apps,
            list_engines,
            create_app,
            db_operation,
            open_folder,
            open_url,
            start_dev,
            stop_dev,
            list_installed_versions,
            current_version,
            set_current_version,
            remove_installed_version,
            manager_home_path,
            // The Monitor (Phase B)
            monitor_scan,
            monitor_probe,
            read_info_json,
            get_inspect_paths,
            set_inspect_paths,
            run_ops_script,
            start_app,
            stop_app,
            owned_processes,
            monitor_logs,
            export_logs,
            manager_log_path,
            // The Scrap Manager (Phase D) + the engine (D9)
            engine_status,
            remember_engine_root,
            start_engine,
            stop_engine,
            explore_list,
            explore_show,
            explore_validate,
            explore_preflight,
            explore_run,
            explore_accept_baseline,
            explore_pin,
            explore_context,
            save_routine,
            // The Assistant (Phase E)
            assistant_config,
            set_assistant_config,
            assistant_compose,
            assistant_generate,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the NPDev Manager");
}
