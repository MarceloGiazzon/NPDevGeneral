// Prevents an extra console window on Windows in release builds -- the Manager's whole point is
// a window instead of a terminal.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

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

#[tauri::command]
async fn check_doctor(state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_doctor(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"), java_home.as_deref()).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_doctor(&python, &cli, java_home.as_deref()).await
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

#[tauri::command]
async fn start_dev(app: tauri::AppHandle, state: State<'_, AppState>, app_dir: String, port: u16) -> Result<(), String> {
    {
        let running = state.running_dev.lock().expect("lock poisoned");
        if running.is_some() {
            return Err("dev is already running -- stop it first".to_string());
        }
    }
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    let running = npdev::start_dev_streaming(app, python, cli, java_home, PathBuf::from(app_dir), port).await?;
    *state.running_dev.lock().expect("lock poisoned") = running;
    Ok(())
}

#[tauri::command]
async fn stop_dev(state: State<'_, AppState>) -> Result<(), String> {
    let running = state.running_dev.lock().expect("lock poisoned").take();
    if let Some(proc) = running {
        npdev::stop_running_process(proc).await?;
    }
    Ok(())
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

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(AppState::new())
        .invoke_handler(tauri::generate_handler![
            is_fake_mode,
            fake_doctor_scenarios,
            set_fake_doctor_scenario,
            check_doctor,
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
            open_folder,
            open_url,
            start_dev,
            stop_dev,
            list_installed_versions,
            current_version,
            set_current_version,
            remove_installed_version,
            manager_home_path,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the NPDev Manager");
}
