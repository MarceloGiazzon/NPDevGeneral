// Prevents an extra console window on Windows in release builds -- the Manager's whole point is
// a window instead of a terminal.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod ai_loop;
mod dockersetup;
mod log;
mod npdev;
mod redact;
mod relay_downloads;
mod relay_watch;
mod runs;
mod runtime;
mod secrets;
mod selftest;
mod state;
mod support;
mod theme_catalogue;
mod themes;
mod versions;

use std::path::{Path, PathBuf};

use serde::Serialize;
use serde_json::Value;
use tauri::{Emitter, Manager, State};

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
    let use_local_repo = manager.use_local_repo;
    let dir = state::resolve_npdev_dir(&manager).ok_or_else(|| {
        if use_local_repo {
            "no local NPDev repo folder set -- pick one in the Install tab first".to_string()
        } else {
            "no NPDev version installed -- on the Install tab, download one (step 3) or switch on \"Use local repo\" and pick your checkout".to_string()
        }
    })?;
    let cli = npdev::npdev_cli_path(&dir);
    if !cli.exists() {
        return Err(format!("npdev_cli.py not found at {}", cli.display()));
    }
    Ok(cli)
}

/// The same three-directory signature `WorkspaceRootLocator` uses to find the repo root by
/// CONTENTS rather than by directory name (CLAUDE.md, REG-144) -- a quick, cheap sanity check
/// before committing to a path, so a wrong folder fails immediately with a clear reason instead of
/// surfacing as "npdev_cli.py not found" three clicks later.
fn looks_like_npdev_repo(dir: &Path) -> bool {
    ["NPDevContract", "NPDevGenerator", "NPDevKernel", "NPDevCli"]
        .iter()
        .all(|name| dir.join(name).is_dir())
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

#[tauri::command]
fn fake_host_scenarios() -> Vec<&'static str> {
    npdev::fake_host_scenario_names()
}

#[tauri::command]
fn set_fake_host_scenario(name: String) {
    *npdev::FAKE_HOST_SCENARIO.lock().expect("lock poisoned") = name;
}

#[tauri::command]
fn fake_pack_scenarios() -> Vec<&'static str> {
    npdev::fake_pack_scenario_names()
}

#[tauri::command]
fn set_fake_pack_scenario(name: String) {
    *npdev::FAKE_PACK_SCENARIO.lock().expect("lock poisoned") = name;
}

// -------------------------------------------------------------------------------------------
// M2: Ready screen
// -------------------------------------------------------------------------------------------

/// `app_dir` (M15) selects WHICH app's database the six database checks run against. Optional
/// because doctor's other ten checks are about the machine and must still answer on a machine with
/// no app on it at all -- "no app yet" is not a broken machine.
#[tauri::command]
async fn check_doctor(state: State<'_, AppState>, app_dir: Option<String>) -> Result<Value, String> {
    runs::recorded("doctor", async move {
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
    })
    .await
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
    runs::recorded("db-test-connection", async move {
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
    })
    .await
}

/// RUN-37: the "Find databases" button -- what already runs here that an app could point at.
#[tauri::command]
async fn detect_databases(state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_db_detect(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"), java_home.as_deref())
            .await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_db_detect(&python, &cli, java_home.as_deref()).await
}

/// The action behind `npdev db detect`'s "Docker is installed but not running": launch Docker
/// Desktop, nothing more. Its daemon takes a while to answer after launch, so the UI re-runs
/// detection rather than this command claiming Docker is up. Installing Docker is NOT done from
/// here -- it needs admin rights and often a reboot, which a button should not spring on anyone.
#[tauri::command]
fn start_docker_desktop() -> Result<String, String> {
    let exe = docker_desktop_exe().ok_or(
        "Docker Desktop was not found (looked next to docker.exe on PATH and under Program Files)",
    )?;
    std::process::Command::new(&exe)
        .spawn()
        .map_err(|e| format!("could not start {}: {e}", exe.display()))?;
    Ok(exe.display().to_string())
}

/// New-app, the card's "Install Docker" step. Opens a visible, elevated window running winget (see
/// `dockersetup`), and returns at once: the install takes minutes and may end in a restart, so the card
/// polls detection instead of this command pretending to know when Docker is up. An `Err` is a reason
/// the card can act on -- no winget (it then offers Docker's download page) or a declined admin prompt.
#[tauri::command]
fn install_docker_desktop() -> Result<String, String> {
    if npdev::fake_mode() {
        return Ok("STUB MODE -- the Docker Desktop installer was not opened.".to_string());
    }
    dockersetup::install_docker_desktop()
}

/// The Docker assistant's first check on Windows: is WSL 2 there for Docker Desktop to run on?
#[tauri::command]
async fn wsl_status() -> Result<Value, String> {
    if npdev::fake_mode() {
        return Ok(serde_json::json!({ "applicable": true, "installed": true }));
    }
    tokio::task::spawn_blocking(dockersetup::wsl_status)
        .await
        .map_err(|e| format!("could not check WSL: {e}"))
}

/// The Docker assistant's "Turn on WSL" step: a visible, elevated `wsl --install --no-distribution`.
#[tauri::command]
fn install_wsl() -> Result<String, String> {
    if npdev::fake_mode() {
        return Ok("STUB MODE -- the WSL installer was not opened.".to_string());
    }
    dockersetup::install_wsl()
}

/// Start a stopped database container the New-app card listed (`docker start <name>`).
#[tauri::command]
async fn start_db_container(name: String) -> Result<String, String> {
    if npdev::fake_mode() {
        return Ok(name);
    }
    tokio::task::spawn_blocking(move || dockersetup::start_container(&name))
        .await
        .map_err(|e| format!("could not start the container: {e}"))?
}

/// New-app, "create a database for me": see `npdev::run_db_provision`.
#[tauri::command]
async fn provision_database(
    state: State<'_, AppState>,
    engine: String,
    app_name: String,
    port: Option<u16>,
    placement: Option<String>,
) -> Result<Value, String> {
    // docker (a container), private (NPDev's own copy) or service (a Windows service): see npdev_db_server.
    let placement = placement.unwrap_or_else(|| "docker".to_string());
    if !matches!(placement.as_str(), "docker" | "private" | "service") {
        return Err(format!("'{placement}' is not a place NPDev can create a database"));
    }
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_db_provision(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                       java_home.as_deref(), &engine, &app_name, port, &placement).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_db_provision(&python, &cli, java_home.as_deref(), &engine, &app_name, port, &placement).await
}

/// On launch: start NPDev's own database servers (made "without Docker") that are not running --
/// the per-user sign-in entry normally did it already; this covers a server made earlier in a session
/// that was stopped. Best effort, never an error the person has to read.
#[tauri::command]
async fn start_own_db_servers(state: State<'_, AppState>) -> Result<(), String> {
    if npdev::fake_mode() {
        return Ok(());
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_db_server_start(&python, &cli, resolve_java_home(&state).as_deref()).await
}

#[cfg(windows)]
fn docker_desktop_exe() -> Option<PathBuf> {
    // docker.exe lives at <install>/resources/bin/docker.exe; the app is <install>/Docker Desktop.exe.
    let from_path = std::env::var_os("PATH").and_then(|path| {
        std::env::split_paths(&path)
            .map(|dir| dir.join("docker.exe"))
            .find(|candidate| candidate.is_file())
            .and_then(|docker| docker.parent()?.parent()?.parent().map(|d| d.join("Docker Desktop.exe")))
    });
    let program_files = std::env::var_os("ProgramFiles")
        .map(|pf| PathBuf::from(pf).join("Docker").join("Docker").join("Docker Desktop.exe"));
    [from_path, program_files].into_iter().flatten().find(|p| p.is_file())
}

#[cfg(not(windows))]
fn docker_desktop_exe() -> Option<PathBuf> {
    None
}

// -------------------------------------------------------------------------------------------
// M3: private JDK -- the thesis. Emits `jdk-progress` events as it downloads.
// -------------------------------------------------------------------------------------------

#[derive(Serialize, Clone)]
struct DownloadProgress {
    downloaded: u64,
    total: Option<u64>,
}

/// The whole Java picture, not one bool.
///
/// This used to answer only "does the private JDK exist under the Manager's home", so a machine
/// with a perfectly good system JDK 17 was told "not installed" and offered a download as the only
/// way forward -- while `resolve_java_home` returning None already let the CLI find that same Java
/// itself, and everything worked. The download was never required; the screen just never said so.
///
/// `resolved` is what a child process will ACTUALLY get, in that order: the private JDK if
/// installed (it is what `resolve_java_home` hands over), else the system one, else nothing.
#[tauri::command]
async fn jdk_status() -> Value {
    if npdev::fake_mode() {
        // A fixed shape so the stub walk is deterministic, matching `python_status` below. Reading
        // the developer's real machine here would make the same walk render differently per machine.
        return serde_json::json!({
            "portableInstalled": false,
            "systemJava": "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.11",
            "systemJavaVersion": "17.0.11",
            "resolved": "system",
            "path": null,
        });
    }
    let portable_installed = runtime::jdk_already_installed();
    let system = runtime::detect_system_java().await;
    let resolved = if portable_installed {
        "portable"
    } else if system.is_some() {
        "system"
    } else {
        "none"
    };
    serde_json::json!({
        "portableInstalled": portable_installed,
        "systemJava": system.as_ref().map(|j| j.java_home.to_string_lossy().to_string()),
        "systemJavaVersion": system.as_ref().map(|j| j.version.clone()),
        "resolved": resolved,
        "path": if portable_installed { Some(state::jdk_dir().to_string_lossy().to_string()) } else { None },
    })
}

#[tauri::command]
async fn install_jdk(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    runs::recorded("install-java", async move {
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
    })
    .await
}

// -------------------------------------------------------------------------------------------
// M4: private Python, NPDev tag zip, setup
// -------------------------------------------------------------------------------------------

#[tauri::command]
async fn python_status(state: State<'_, AppState>) -> Result<Value, String> {
    if npdev::fake_mode() {
        // Fixed, for the same reason as `jdk_status`: stub mode must render the same screen on
        // every machine. Before this it probed the real one, so the stub walk showed whatever
        // Python the developer happened to have.
        return Ok(serde_json::json!({
            "systemPython": "C:\\Python312\\python.exe",
            "portableInstalled": false,
            "resolved": "C:\\Python312\\python.exe",
        }));
    }
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
    runs::recorded("install-python", async move {
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
    })
    .await
}

#[tauri::command]
async fn list_tags(force_refresh: bool) -> Result<Vec<versions::TagInfo>, String> {
    versions::list_tags(force_refresh).await
}

#[tauri::command]
async fn install_npdev_version(app: tauri::AppHandle, state: State<'_, AppState>, tag: String) -> Result<(), String> {
    runs::recorded("install-npdev", async move {
        let app2 = app.clone();
        versions::install_version(&state, &tag, move |downloaded, total| {
            let _ = app2.emit("version-install-progress", DownloadProgress { downloaded, total });
        })
        .await
    })
    .await
}

#[tauri::command]
async fn run_setup(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<Value, String> {
    runs::recorded("npdev-setup", async move {
        let java_home = resolve_java_home(&state);
        let python = resolve_python_exe(&state).await?;
        let cli = resolve_npdev_cli(&state)?;
        npdev::run_setup_streaming(app, python, cli, java_home).await
    })
    .await
}

/// Has setup ever actually run, and is its result still there?
///
/// The Install screen's "not run yet" was an HTML literal that only the Run-setup click handler
/// ever rewrote -- so it said "not run yet" on every launch of a fully set-up Manager, and said
/// "done" after a run whose jars somebody later deleted. There were no criteria; this command is
/// the criteria, and they are the SAME facts doctor reports rather than a second opinion:
/// `runtimehost-jars` (the staged libs directory) and `ai-knowledge-index` (`rag-index.json`).
///
/// Deliberately does not create either path -- `state::ensure_dirs`'s rule. A status check that
/// conjures the directory it is checking always reports healthy.
#[tauri::command]
fn setup_status(state: State<'_, AppState>) -> Value {
    if npdev::fake_mode() {
        // A fixed healthy shape, like every other stub answer: the stub walk exists to exercise
        // the UI, and a status read off the developer's real machine would make it non-deterministic.
        return serde_json::json!({
            "jarsStaged": true,
            "jarCount": 12,
            "libsDir": state::runtimehost_libs_dir().to_string_lossy().to_string(),
            "aiIndexPresent": true,
            "currentVersion": "beta1.17",
            "useLocalRepo": false,
            "localRepoPath": Value::Null,
        });
    }
    let libs_dir = state::runtimehost_libs_dir();
    let jar_count = state::count_jars_in(&libs_dir);
    let manager = state.manager.lock().expect("lock poisoned");
    let use_local_repo = manager.use_local_repo;
    let local_repo_path = manager.local_repo_path.clone();
    // Step 4's gate ("do step 3 first") and its "for {currentVersion}" messages just interpolate
    // this string -- so a local checkout reports a label here rather than needing its own branch
    // in every place that reads `currentVersion`.
    let current_version = if use_local_repo {
        local_repo_path.clone().map(|p| format!("local checkout ({p})"))
    } else {
        manager.current_version.clone()
    };
    drop(manager);
    serde_json::json!({
        // Directory existence is doctor's own bar for `runtimehost-jars`, so this agrees with the
        // Ready screen by construction. The count is reported beside it rather than substituted for
        // it: an empty staged directory is a real state, and it is not the same as "never ran".
        "jarsStaged": libs_dir.is_dir(),
        "jarCount": jar_count,
        "libsDir": libs_dir.to_string_lossy().to_string(),
        "aiIndexPresent": state::ai_knowledge_index_path().exists(),
        "currentVersion": current_version,
        "useLocalRepo": use_local_repo,
        "localRepoPath": local_repo_path,
    })
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

/// BUILD-5: each new app gets a folder of its own, `<parent>/<name>/<name>`. Generation writes the
/// app to `<model dir>-app` and its ArtifactNP to `<model dir>/../ArtifactNP`, so two apps whose model
/// folders sat side by side in one parent shared -- and overwrote -- a single ArtifactNP.
fn app_target_dir(parent_dir: &str, name: &str) -> PathBuf {
    PathBuf::from(parent_dir).join(name).join(name)
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
    // STOR-15. Not Option<bool>: absent and false mean the same thing here, and an Option would
    // invite a caller to omit it -- which is exactly how this flag came to have no writer at all.
    externally_provisioned: bool,
) -> Result<Value, String> {
    runs::recorded("create-app", async move {
        let java_home = resolve_java_home(&state);
        let python = resolve_python_exe(&state).await?;
        let cli = resolve_npdev_cli(&state)?;
        let target_dir = app_target_dir(&parent_dir, &name);
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
            externally_provisioned,
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
    })
    .await
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

/// Data mobility tab: `npdev db export`. Thin pipe, same shape as `pack_list` -- format/scope
/// choices and connection resolution live entirely in the CLI.
#[tauri::command]
async fn db_export(
    state: State<'_, AppState>,
    app_dir: String,
    out_dir: String,
    format: String,
    scope: String,
    tables: Option<String>,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_db_export(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(), &app_dir, &out_dir, &format, &scope, tables.as_deref()).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_db_export(&python, &cli, java_home.as_deref(), &app_dir, &out_dir, &format, &scope, tables.as_deref()).await
}

/// Data mobility tab: `npdev db import`. `apply=false` is the pre-import "DB Structure Check" (the
/// Check Structure button); `apply=true` is the Import button, `force` only meaningful together with
/// it. See ImportMain's own javadoc for the EQUAL/COMPATIBLE/INCOMPATIBLE gating this passes through.
#[tauri::command]
async fn db_import(
    state: State<'_, AppState>,
    app_dir: String,
    in_dir: String,
    format: String,
    apply: bool,
    force: bool,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_db_import(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(), &app_dir, &in_dir, &format, apply, force).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_db_import(&python, &cli, java_home.as_deref(), &app_dir, &in_dir, &format, apply, force).await
}

/// A native folder picker for the data mobility tab's export-output / import-input path fields --
/// same `rfd` pattern as `pick_local_repo_folder`, parameterized on the dialog title since this one
/// serves two different fields.
#[tauri::command]
async fn pick_db_transfer_folder(title: String) -> Option<String> {
    let handle = rfd::AsyncFileDialog::new().set_title(&title).pick_folder().await?;
    Some(handle.path().to_string_lossy().to_string())
}

/// GPU Checks tab (Track B, G5): `npdev gpu-check list`. Thin pipe, same shape as `pack_list` --
/// engine eligibility per check is entirely the CLI's own decision.
#[tauri::command]
async fn gpu_check_list(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_gpu_check_list(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_gpu_check_list(&python, &cli, java_home.as_deref(), &app_dir).await
}

/// GPU Checks tab: `npdev gpu-check run --engine <auto|gpu|cpu> [--verify]`. Exit 0 = no
/// violations, 1 = violations found, 2 = GPU disagreed with the CPU twin -- the CLI's own
/// npdev-cli-result.v1 envelope carries that in `exitCode`/`ok`, nothing re-derived here.
#[tauri::command]
async fn gpu_check_run(
    state: State<'_, AppState>,
    app_dir: String,
    engine: String,
    verify: bool,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_gpu_check_run(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(), &app_dir, &engine, verify).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_gpu_check_run(&python, &cli, java_home.as_deref(), &app_dir, &engine, verify).await
}

/// GPU Checks tab: `npdev gpu-check history`.
#[tauri::command]
async fn gpu_check_history(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_gpu_check_history(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_gpu_check_history(&python, &cli, java_home.as_deref(), &app_dir).await
}

/// GPU Checks tab: `npdev gpu-check show --run <id>` -- the details pane behind clicking a run row.
#[tauri::command]
async fn gpu_check_show(state: State<'_, AppState>, app_dir: String, run_id: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_gpu_check_show(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(), &app_dir, &run_id).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_gpu_check_show(&python, &cli, java_home.as_deref(), &app_dir, &run_id).await
}

/// GPU Checks tab: the "⬇ Download bundle" button -- `npdev gpu-check bundle`, writing to a
/// user-picked folder (reuses `pick_db_transfer_folder`'s dialog from the UI side; this command
/// only runs the CLI once a destination path is known).
#[tauri::command]
async fn gpu_check_bundle(
    state: State<'_, AppState>,
    app_dir: String,
    run_id: String,
    out_zip: String,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_gpu_check_bundle(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
            java_home.as_deref(), &app_dir, &run_id, &out_zip).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_gpu_check_bundle(&python, &cli, java_home.as_deref(), &app_dir, &run_id, &out_zip).await
}

#[tauri::command]
fn open_folder(app: tauri::AppHandle, path: String) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    app.opener().open_path(path, None::<&str>).map_err(|e| e.to_string())
}

/// Wave 7.2: reads a scenario's `detailReportPath` (an `ai-schema-validation-report.json` or
/// `ai-beta-gate-report.json`, per `results.json`'s own field) for the Evals tab's row-detail view.
/// `path` is never operator-typed -- the Evals tab only ever gets it from a `results.json` the CLI
/// itself just wrote, same trust boundary `verify-run-status`'s "View report" button already applies
/// to `open_folder` above.
#[tauri::command]
fn read_json_file(path: String) -> Result<Value, String> {
    let text = std::fs::read_to_string(&path).map_err(|e| format!("could not read {path}: {e}"))?;
    serde_json::from_str(&text).map_err(|e| format!("{path} did not parse as JSON: {e}"))
}

// -------------------------------------------------------------------------------------------
// SUP-1: "Get help with this error" -- the redacted report (support.rs)
// -------------------------------------------------------------------------------------------

/// Every secret VALUE this machine holds for the Manager: the OS credential store entries behind the
/// Prompter profiles and the db/deploy secret profiles, plus the values of secret-named environment
/// variables. Used only to scrub text; never logged, never returned to the UI.
fn known_secret_values(state: &AppState) -> Vec<String> {
    let mut out = redact::known_env_secrets();
    let (prompter, keyed): (Vec<String>, Vec<(String, String, String)>) = {
        let m = state.manager.lock().expect("lock poisoned");
        (
            m.prompter_profiles.iter().map(|p| p.id.clone()).collect(),
            m.secret_profile_keys.iter().map(|k| (k.scope.clone(), k.env.clone(), k.profile_id.clone())).collect(),
        )
    };
    for id in prompter {
        if let Ok(Some(v)) = secrets::get_secret_scoped("prompter", None, &id) {
            out.push(v);
        }
    }
    for (scope, env, id) in keyed {
        if let Ok(Some(v)) = secrets::get_secret_scoped(&scope, Some(&env), &id) {
            out.push(v);
        }
    }
    out
}

/// Best-effort OS version and administrator state, for the report. Short-lived child processes with no
/// console window; any failure just yields "unknown" -- a report must never fail because of its header.
fn os_and_admin() -> (String, String) {
    let base = format!("{} ({})", std::env::consts::OS, std::env::consts::ARCH);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let run = |prog: &str, args: &[&str]| -> Option<String> {
            std::process::Command::new(prog)
                .args(args)
                .creation_flags(CREATE_NO_WINDOW)
                .output()
                .ok()
                .map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        };
        let ver = run("cmd", &["/C", "ver"]).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
        let admin = match run("whoami", &["/groups"]) {
            Some(t) if t.contains("S-1-16-12288") => "yes",
            Some(_) => "no",
            None => "unknown",
        };
        (format!("{} {}", ver.unwrap_or_default(), base).trim().to_string(), admin.to_string())
    }
    #[cfg(not(windows))]
    {
        (base, "unknown".to_string())
    }
}

fn summarize_tool(path: &Option<String>, label: &str) -> String {
    match path {
        Some(_) => format!("a private {label} installed by the Manager"),
        None => format!("system {label} (or none installed)"),
    }
}

/// `operation` is an id from ui/support-intents.json; None means "the latest failed operation".
/// `error` is what the UI caught -- used when the recorder holds nothing for that operation, so every
/// error site can offer help even before its command is wrapped by the recorder.
#[tauri::command]
async fn support_report(
    state: State<'_, AppState>,
    operation: Option<String>,
    screen: String,
    app_dir: Option<String>,
    ui_language: String,
    error: Option<String>,
) -> Result<Value, String> {
    let record = runs::latest(operation.as_deref());
    let op = operation
        .clone()
        .or_else(|| record.as_ref().map(|r| r.operation.clone()))
        .unwrap_or_else(|| "unknown".to_string());
    let known = known_secret_values(&state);
    let (os, admin) = os_and_admin();
    let (java, python, source) = {
        let m = state.manager.lock().expect("lock poisoned");
        let source = if m.use_local_repo {
            "a local checkout (path hidden)".to_string()
        } else {
            m.current_version.clone().unwrap_or_else(|| "none installed yet".to_string())
        };
        (summarize_tool(&m.jdk_home, "Java"), summarize_tool(&m.python_exe, "Python"), source)
    };
    let out = support::build_report(support::ReportInputs {
        record,
        operation: op,
        fallback_error: error,
        screen,
        app_dir,
        ui_language,
        manager_version: manager_version_description(),
        source,
        os,
        admin,
        java,
        python,
        known_secrets: known,
    });
    serde_json::to_value(out).map_err(|e| e.to_string())
}

// -------------------------------------------------------------------------------------------
// THM-3: themes and appearance (themes.rs)
// -------------------------------------------------------------------------------------------

/// Built-ins in gallery order, then the user's own themes. Each carries `builtin: true|false`.
#[tauri::command]
fn theme_list() -> Vec<Value> {
    themes::list()
}

/// Validate (structure) and store a user theme file's text. Returns `{id, name}`.
#[tauri::command]
fn theme_import(json_text: String) -> Result<Value, String> {
    themes::import(&json_text)
}

/// Remove a user theme. If it was the chosen theme the choice falls back to Dark, so the Manager
/// never points at a file that is gone.
#[tauri::command]
fn theme_delete(state: State<'_, AppState>, id: String) -> Result<Value, String> {
    let removed = themes::delete(&id)?;
    let mut manager = state.manager.lock().expect("lock poisoned");
    let mut reset = false;
    if manager.appearance.theme_id == id {
        manager.appearance.theme_id = themes::DEFAULT_THEME.to_string();
        manager.save().map_err(|e| e.to_string())?;
        reset = true;
    }
    Ok(serde_json::json!({ "removed": removed, "resetToDefault": reset }))
}

/// THM-10: the public catalogue's entries (sanitised), each marked `installed` when the user already has that id.
#[tauri::command]
async fn theme_catalogue_index() -> Result<Value, String> {
    let fake = npdev::fake_mode();
    let mut entries = theme_catalogue::index(fake).await?;
    let have: Vec<String> = themes::list().iter().filter_map(|t| t["id"].as_str().map(String::from)).collect();
    for e in entries.iter_mut() {
        let installed = e["id"].as_str().map(|id| have.iter().any(|h| h == id)).unwrap_or(false);
        e["installed"] = Value::Bool(installed);
    }
    Ok(serde_json::json!({ "repo": theme_catalogue::REPO, "stub": fake, "themes": entries }))
}

/// THM-10: one catalogue theme's JSON text, returned ONLY if its SHA-256 matches the index. It is not stored here;
/// the webview previews it and `theme_import` stores it when the user keeps it.
#[tauri::command]
async fn theme_catalogue_fetch(id: String) -> Result<String, String> {
    theme_catalogue::fetch(npdev::fake_mode(), &id).await
}

/// THM-10: GitHub's "new file" URL for `themes/<id>.json` in the catalogue repo, prefilled with this theme.
#[tauri::command]
fn theme_share_url(json_text: String) -> Result<String, String> {
    theme_catalogue::share_url(&json_text)
}

#[tauri::command]
fn appearance_get(state: State<'_, AppState>) -> themes::Appearance {
    let mut a = state.manager.lock().expect("lock poisoned").appearance.clone();
    // A theme that has since been deleted by hand must not leave the window unstyled.
    if !themes::exists(&a.theme_id) {
        a.theme_id = themes::DEFAULT_THEME.to_string();
    }
    a
}

#[tauri::command]
fn appearance_set(state: State<'_, AppState>, appearance: themes::Appearance) -> Result<themes::Appearance, String> {
    appearance.validate()?;
    if !themes::exists(&appearance.theme_id) {
        return Err(format!("there is no theme {:?}", appearance.theme_id));
    }
    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.appearance = appearance.clone();
    manager.save().map_err(|e| e.to_string())?;
    Ok(appearance)
}

/// JOBS-3: the Activity > Tasks board. The CLI owns the records and the decision that a job is `lost`.
#[tauri::command]
async fn jobs_list(state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let args = vec!["list".to_string(), "--limit".to_string(), "60".to_string()];
    if npdev::fake_mode() {
        return npdev::run_jobs(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"), java_home.as_deref(), args).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_jobs(&python, &cli, java_home.as_deref(), args).await
}

/// Stop or restart one job. `action` is checked here against the two the board offers, because the id and the
/// verb end up as command-line arguments.
#[tauri::command]
async fn jobs_action(state: State<'_, AppState>, action: String, job_id: String) -> Result<Value, String> {
    if action != "stop" && action != "restart" {
        return Err(format!("unknown task action {action:?}"));
    }
    let ok_id = job_id.len() <= 90
        && job_id.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '.' | '_'));
    if !ok_id {
        return Err("not a task id".to_string());
    }
    let java_home = resolve_java_home(&state);
    let args = vec![action, job_id];
    if npdev::fake_mode() {
        return npdev::run_jobs(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"), java_home.as_deref(), args).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_jobs(&python, &cli, java_home.as_deref(), args).await
}

/// What THIS window has done recently (the run recorder), for the Tasks board's "started in this window" rows.
/// Summaries only: no command line, no output.
#[tauri::command]
fn recent_operations() -> Vec<Value> {
    runs::snapshot()
        .into_iter()
        .rev()
        .map(|r| {
            serde_json::json!({
                "operation": r.operation,
                "intent": support::intent_text(&r.operation),
                "label": support::intent_label(&r.operation),
                "startedAt": r.started_at,
                "endedAt": r.ended_at,
                "running": r.running,
                "ok": r.ok,
            })
        })
        .collect()
}

/// Home's "Something went wrong" card: the newest operation that failed and was not fixed by a later
/// success of the same operation, in plain words. None when there is nothing to report.
#[tauri::command]
fn last_failed_run() -> Option<Value> {
    runs::latest_unresolved_failure().map(|r| {
        serde_json::json!({
            "operation": r.operation,
            "intent": support::intent_text(&r.operation),
            "endedAt": r.ended_at,
        })
    })
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

/// `npdev init <d>` generates a FinalApp into the sibling `<d>-app` -- so a model directory and its
/// built app share one H2 data file. Compares path strings only (trailing separator + case
/// normalised); good enough to catch the same-window collision this exists to prevent, not a
/// general path-equality oracle.
fn norm_path(p: &str) -> String {
    p.trim_end_matches(['\\', '/']).to_ascii_lowercase()
}

/// The other direction: where the app generated from a model folder lives -- `npdev init`'s own convention,
/// the model folder's sibling `<name>-app` (also what `npdev dev` and `model_dir_of_app` rely on). One place, so
/// no screen spells the convention out for itself.
pub(crate) fn app_dir_of_model(model_dir: &str) -> Option<String> {
    let normalized = model_dir.trim_end_matches(['\\', '/']);
    if normalized.is_empty() || normalized.contains('\0') {
        return None;
    }
    Some(format!("{normalized}-app"))
}

/// "Use a browser AI": a new app is made as a model folder; building, starting and opening it all work on the
/// generated app next to it. This is the one step between the two.
#[tauri::command]
fn relay_generated_app_dir(model_dir: String) -> Result<String, String> {
    app_dir_of_model(&model_dir).ok_or_else(|| "the app's folder is not valid".to_string())
}

pub(crate) fn model_dir_of_app(app_dir: &str) -> Option<String> {
    let normalized = app_dir.trim_end_matches(['\\', '/']);
    if let Some(stripped) = normalized.strip_suffix("-app") {
        return Some(stripped.to_string());
    }
    // Wave 6 (Studio tab): AppGen/NPDevSamples apps (Build-NpdevApp.ps1 et al) don't use npdev
    // init's `<name>-app` naming at all -- the generated app lives at `<outputRoot>/App`, with the
    // model/config it was generated FROM staged alongside it at `<outputRoot>/Input` (confirmed live
    // against a real build: `App` and `Input` are siblings). Existence-checked here, unlike the
    // suffix-strip above, since "Input" is not itself a naming convention that implies a model lives
    // there the way "-app" does -- an app_dir that happens to have an unrelated sibling "Input"
    // folder with no model.json in it must fall through to the caller's own poorer fallback, not
    // silently point at the wrong directory.
    let candidate = PathBuf::from(normalized).parent()?.join("Input");
    if candidate.join("model.json").is_file() {
        return candidate.to_str().map(|s| s.to_string());
    }
    None
}

#[tauri::command]
async fn start_dev(app: tauri::AppHandle, state: State<'_, AppState>, app_dir: String, port: u16) -> Result<(), String> {
    runs::recorded("start-app", async move {
        {
            let running = state.running.lock().expect("lock poisoned");
            if running.contains_key(RUN_SCREEN_KEY) {
                return Err("dev is already running -- stop it first".to_string());
            }
            let built_app_dir = app_dir_of_model(&app_dir).unwrap_or_else(|| app_dir.clone());
            if running.keys().any(|k| norm_path(k) == norm_path(&built_app_dir)) {
                return Err(format!(
                    "the app built from this model ({built_app_dir}) is already running from the Apps/Run \
                     tab -- stop it first. Both hold the same database file and a second opener crashes on boot."
                ));
            }
        }
        let java_home = resolve_java_home(&state);
        let python = resolve_python_exe(&state).await?;
        let cli = resolve_npdev_cli(&state)?;
        log::info(format!("start_dev app_dir={app_dir} port={port}"));
        let running = npdev::start_dev_streaming(app, python, cli, java_home, PathBuf::from(&app_dir), port).await?;
        if let Some(proc) = running {
            state.running.lock().expect("lock poisoned").insert(RUN_SCREEN_KEY.to_string(), proc);
            *state.dev_app_dir.lock().expect("lock poisoned") = Some(app_dir);
        }
        Ok(())
    })
    .await
}

#[tauri::command]
async fn stop_dev(state: State<'_, AppState>) -> Result<(), String> {
    let running = state.running.lock().expect("lock poisoned").remove(RUN_SCREEN_KEY);
    if let Some(proc) = running {
        log::info("stop_dev");
        npdev::stop_running_process(proc).await?;
    }
    *state.dev_app_dir.lock().expect("lock poisoned") = None;
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

// ---------------------------------------------------------------------------------------------
// The Share screen (NPDEV_MANAGER_SHARE_IMPLEMENTATION_PLAN). Every command here is the same thin
// shape as monitor_probe above: fake_mode returns BEFORE resolve_python_exe/resolve_npdev_cli (the
// branch that lets stub mode run with no NPDev installed at all), otherwise it resolves the real
// interpreter/CLI and delegates straight to npdev::run_host_*. No hosting decision is made here.
// ---------------------------------------------------------------------------------------------

#[tauri::command]
async fn host_status(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_host_status(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                      java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_status(&python, &cli, java_home.as_deref(), &app_dir).await
}

#[tauri::command]
async fn host_check(
    state: State<'_, AppState>,
    app_dir: String,
    fix: Option<bool>,
    remote: Option<String>,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let fix = fix.unwrap_or(false);
    if npdev::fake_mode() {
        return npdev::run_host_check(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                     java_home.as_deref(), &app_dir, fix, remote.as_deref()).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_check(&python, &cli, java_home.as_deref(), &app_dir, fix, remote.as_deref()).await
}

#[tauri::command]
async fn host_explain(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_host_explain(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                       java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_explain(&python, &cli, java_home.as_deref(), &app_dir).await
}

#[tauri::command]
async fn host_targets(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_host_targets(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                       java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_targets(&python, &cli, java_home.as_deref(), &app_dir).await
}

#[tauri::command]
async fn host_plan(
    state: State<'_, AppState>,
    app_dir: String,
    rung: u8,
    target: Option<String>,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_host_plan(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                    java_home.as_deref(), &app_dir, rung, target.as_deref()).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_plan(&python, &cli, java_home.as_deref(), &app_dir, rung, target.as_deref()).await
}

#[tauri::command]
async fn host_share(
    state: State<'_, AppState>,
    app_dir: String,
    provider: Option<String>,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_host_share(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                     java_home.as_deref(), &app_dir, provider.as_deref()).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_share(&python, &cli, java_home.as_deref(), &app_dir, provider.as_deref()).await
}

/// M7: the "Fix N things, then share" sequence with visible progress. Fire-and-forget, like
/// `run_ops_script` above -- there is no single child process to hand back, only a sequence of
/// already-thin CLI calls `run_host_share_streaming` makes and reports on via `host-event`. The
/// event NAME landing in the same commit as this command (capabilities/default.json) is R1's own
/// rule: forgetting it is a silent empty progress panel, not an error anyone can read.
#[tauri::command]
async fn host_share_streaming(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    app_dir: String,
    rung: u8,
    target: Option<String>,
    needs_plan: bool,
    provider: Option<String>,
) -> Result<(), String> {
    let java_home = resolve_java_home(&state);
    let (python, cli) = if npdev::fake_mode() {
        (PathBuf::from("python"), PathBuf::from("npdev_cli.py"))
    } else {
        (resolve_python_exe(&state).await?, resolve_npdev_cli(&state)?)
    };
    tauri::async_runtime::spawn(npdev::run_host_share_streaming(
        app, python, cli, java_home, app_dir, rung, target, needs_plan, provider,
    ));
    Ok(())
}

#[tauri::command]
async fn host_down(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_host_down(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                    java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_down(&python, &cli, java_home.as_deref(), &app_dir).await
}

#[tauri::command]
async fn host_deploy(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_host_deploy(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                      java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_deploy(&python, &cli, java_home.as_deref(), &app_dir).await
}

#[tauri::command]
async fn host_keys(state: State<'_, AppState>, app_dir: String, new: Option<bool>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let new = new.unwrap_or(false);
    if npdev::fake_mode() {
        return npdev::run_host_keys(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                    java_home.as_deref(), &app_dir, new).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_host_keys(&python, &cli, java_home.as_deref(), &app_dir, new).await
}

/// The AI Providers tab: every call is `npdev providers ... --json` (see npdev::run_providers). A provider
/// id is checked here so a value like `--json` can never reach argparse as a flag.
async fn providers_call(state: &State<'_, AppState>, args: Vec<String>, stdin: Option<String>) -> Result<Value, String> {
    let java_home = resolve_java_home(state);
    let (python, cli) = if npdev::fake_mode() {
        (PathBuf::from("python"), PathBuf::from("npdev_cli.py"))
    } else {
        (resolve_python_exe(state).await?, resolve_npdev_cli(state)?)
    };
    npdev::run_providers(&python, &cli, java_home.as_deref(), &args, stdin).await
}

fn provider_id_ok(id: &str) -> Result<String, String> {
    let id = id.trim();
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-') || id.starts_with('-') {
        return Err(format!("not a provider id: {id:?}"));
    }
    Ok(id.to_string())
}

#[tauri::command]
async fn providers_list(state: State<'_, AppState>) -> Result<Value, String> {
    providers_call(&state, vec!["list".into(), "--json".into()], None).await
}

#[tauri::command]
async fn providers_add(state: State<'_, AppState>, provider: String, key: String) -> Result<Value, String> {
    let provider = provider_id_ok(&provider)?;
    let key = key.trim().to_string();
    if key.is_empty() || key.contains('\n') {
        return Err("the key is empty or has a line break".into());
    }
    providers_call(&state, vec!["add".into(), provider, "--key-stdin".into(), "--json".into()], Some(key)).await
}

#[tauri::command]
async fn providers_remove(state: State<'_, AppState>, provider: String) -> Result<Value, String> {
    let provider = provider_id_ok(&provider)?;
    providers_call(&state, vec!["remove".into(), provider, "--json".into()], None).await
}

#[tauri::command]
async fn providers_test(state: State<'_, AppState>, provider: String, chat: Option<bool>) -> Result<Value, String> {
    let mut args = vec!["test".into(), provider_id_ok(&provider)?];
    if chat.unwrap_or(false) {
        args.push("--chat".into());
    }
    providers_call(&state, args, None).await
}

#[tauri::command]
async fn providers_export(state: State<'_, AppState>, app_dir: String, providers: Vec<String>) -> Result<Value, String> {
    if providers.is_empty() {
        return Err("choose at least one provider to export".into());
    }
    let mut args = vec!["export-env".into(), "--app".into(), app_dir];
    for p in providers {
        args.push("--provider".into());
        args.push(provider_id_ok(&p)?);
    }
    providers_call(&state, args, None).await
}

/// VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27 Phase 3: the Verification tab reads one
/// npdev-verification-panel.v1 document from `npdev verify --panel --json`. Thin command, same
/// shape as monitor_scan -- no decision lives in Rust.
#[tauri::command]
async fn verification_panel(state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_verification_panel(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                             java_home.as_deref()).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_verification_panel(&python, &cli, java_home.as_deref()).await
}

/// VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27 Phase 5: Run one panel item. The ID is the only
/// input; npdev_executor.py resolves the command from the trusted panel document and executes it
/// through the controlled runner. Manager-local only -- the emitted app page never gets this.
#[tauri::command]
async fn verification_run_item(state: State<'_, AppState>, item_id: String,
                               timeout_seconds: Option<u32>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_verification_run_item(&PathBuf::from("python"),
                                                &PathBuf::from("npdev_cli.py"),
                                                java_home.as_deref(), &item_id,
                                                timeout_seconds.unwrap_or(0)).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_verification_run_item(&python, &cli, java_home.as_deref(), &item_id,
                                     timeout_seconds.unwrap_or(0)).await
}

/// W3.2: the Packs screen's list -- `npdev pack list --model <appDir>/model.json`. Thin pipe, same
/// shape as verification_panel: no resolution/signature/deprecation decision lives in Rust, the
/// CLI's own report (already enriched by npdev_cli.py's `_enrich_pack_list_report`) is returned
/// verbatim.
#[tauri::command]
async fn pack_list(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_pack_list(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                    java_home.as_deref(), &app_dir).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_pack_list(&python, &cli, java_home.as_deref(), &app_dir).await
}

/// W3.2: the Packs screen's detail panel -- `npdev pack why --model <appDir>/model.json <packId>`.
#[tauri::command]
async fn pack_why(state: State<'_, AppState>, app_dir: String, pack_id: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    if npdev::fake_mode() {
        return npdev::run_pack_why(&PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                                   java_home.as_deref(), &app_dir, &pack_id).await;
    }
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_pack_why(&python, &cli, java_home.as_deref(), &app_dir, &pack_id).await
}

/// W3.2's "offer export": save a copy of the currently-locked pack's own pack.json file to
/// wherever the user picks, through the same native "Save As" dialog `pick_local_repo_folder`
/// uses for folders. Deliberately NOT a new `npdev pack` verb -- `source_path` is a lock entry's
/// own field the Packs screen already has in hand, so this is a plain file copy, not a second
/// resolution mechanism. Returns `None` if the user cancels the dialog.
#[tauri::command]
async fn pack_export_copy(source_path: String, suggested_name: String) -> Result<Option<String>, String> {
    let Some(handle) = rfd::AsyncFileDialog::new()
        .set_title("Save a copy of this pack.json")
        .set_file_name(&suggested_name)
        .add_filter("pack.json", &["json"])
        .save_file()
        .await
    else {
        return Ok(None);
    };
    let dest = handle.path().to_path_buf();
    std::fs::copy(&source_path, &dest)
        .map_err(|e| format!("could not copy {source_path} to {}: {e}", dest.display()))?;
    Ok(Some(dest.to_string_lossy().to_string()))
}

/// D7's inspect paths were type-in-a-folder-path-by-hand only -- fine for a terminal user, hostile
/// for anyone who does not already know the exact string to type. A native multi-select folder
/// dialog is the same affordance every other Windows app uses for "pick some folders."
#[tauri::command]
async fn pick_inspect_folders() -> Vec<String> {
    let Some(handles) = rfd::AsyncFileDialog::new()
        .set_title("Add folders to inspect")
        .pick_folders()
        .await
    else {
        return Vec::new();
    };
    handles
        .into_iter()
        .map(|h| h.path().to_string_lossy().to_string())
        .collect()
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

/// Windows-only: finds `java.exe` processes whose command line names this app directory (the shape
/// every generated `Run-FinalApp.ps1` launches: `java -jar <appRoot>\build\libs\...jar`) and force-
/// stops them, returning the PIDs killed. `Stop-Environment.ps1` manages the DATABASE only -- for a
/// docker-backed engine or H2's own TCP server it has a real process to stop, but for the common
/// default (`profile.kind == "embedded"`, a plain H2 FILE) it has nothing to do and exits 0 with
/// "No background environment service to stop", which reads as success while the app's own JVM
/// keeps serving untouched. This is what actually stops that JVM when this window did not start it
/// (a previous Manager session, a terminal, or a Manager instance that was closed and relaunched).
#[cfg(windows)]
fn kill_external_app_processes(app_dir: &str) -> Vec<u32> {
    let needle = app_dir.replace('\'', "''");
    let script = format!(
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | \
         Where-Object {{ $_.CommandLine -and $_.CommandLine.Contains('{needle}') }} | \
         ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; $_.ProcessId }}"
    );
    let output = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .output();
    match output {
        Ok(o) => String::from_utf8_lossy(&o.stdout)
            .lines()
            .filter_map(|l| l.trim().parse::<u32>().ok())
            .collect(),
        Err(_) => Vec::new(),
    }
}

#[cfg(not(windows))]
fn kill_external_app_processes(_app_dir: &str) -> Vec<u32> {
    Vec::new()
}

/// Stop an app the Monitor can see. Two cases, and conflating them is how an orphan is born:
/// a process THIS Manager started is killed as a tree; anything else -- started by a terminal, by a
/// previous Manager session, by the app's own script -- is stopped through the app's own
/// `Stop-Environment` runbook for its DATABASE, plus a direct kill of its own JVM (see
/// `kill_external_app_processes`) since Stop-Environment does not know how for an embedded H2 app.
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
    run_ops_script(app, state, app_dir.clone(), "stop-environment".to_string(), None).await?;
    let killed = kill_external_app_processes(&app_dir);
    if !killed.is_empty() {
        log::info(format!("stop_app force-stopped external JVM(s) {killed:?} for {app_dir}"));
        return Ok(serde_json::json!({
            "ok": true,
            "how": format!(
                "ran the app's own Stop-Environment script (database only) and force-stopped its JVM directly (pid {killed:?}) -- this window did not start it"
            ),
        }));
    }
    Ok(serde_json::json!({
        "ok": true,
        "how": "ran the app's own Stop-Environment script -- this window did not start it",
    }))
}

#[tauri::command]
async fn start_app(app: tauri::AppHandle, state: State<'_, AppState>, app_dir: String) -> Result<(), String> {
    runs::recorded("start-app", async move {
        let key = app_dir.clone();
        {
            let mut running = state.running.lock().expect("lock poisoned");
            if npdev::holds_live_process(&mut running, &key) {
                return Err("this app is already running from this window".to_string());
            }
        }
        if let Some(model_dir) = model_dir_of_app(&app_dir) {
            let dev_dir = state.dev_app_dir.lock().expect("lock poisoned").clone();
            if let Some(dev_dir) = dev_dir {
                if norm_path(&dev_dir) == norm_path(&model_dir) {
                    return Err(format!(
                        "dev mode is running against this app's model ({dev_dir}, Run tab) -- stop it \
                         first. Both hold the same database file and a second opener crashes on boot."
                    ));
                }
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
    })
    .await
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

/// Lets someone developing NPDev itself point the Manager straight at a repo checkout on disk --
/// their own working tree, no download/tag round-trip -- instead of requiring a version already
/// published as a GitHub tag. Same native-picker pattern as `pick_inspect_folders`.
#[tauri::command]
async fn pick_local_repo_folder() -> Option<String> {
    let handle = rfd::AsyncFileDialog::new()
        .set_title("Select an NPDev repo checkout")
        .pick_folder()
        .await?;
    Some(handle.path().to_string_lossy().to_string())
}

#[tauri::command]
fn set_local_repo_path(state: State<'_, AppState>, path: String) -> Result<(), String> {
    let dir = PathBuf::from(&path);
    if !looks_like_npdev_repo(&dir) {
        return Err(format!(
            "{path} does not look like an NPDev repo checkout -- expected NPDevContract, \
             NPDevGenerator, NPDevKernel and NPDevCli folders alongside each other"
        ));
    }
    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.local_repo_path = Some(path);
    manager.use_local_repo = true;
    manager.save().map_err(|e| e.to_string())
}

/// Switches back to whatever downloaded tag was selected before. `local_repo_path` is left in
/// place so flipping the toggle back on later does not require re-picking the folder.
#[tauri::command]
fn use_downloaded_npdev_version(state: State<'_, AppState>) -> Result<(), String> {
    let mut manager = state.manager.lock().expect("lock poisoned");
    manager.use_local_repo = false;
    manager.save().map_err(|e| e.to_string())
}

#[tauri::command]
fn local_repo_config(state: State<'_, AppState>) -> Value {
    let manager = state.manager.lock().expect("lock poisoned");
    serde_json::json!({
        "path": manager.local_repo_path,
        "active": manager.use_local_repo,
    })
}

#[tauri::command]
fn manager_home_path() -> String {
    state::manager_home().to_string_lossy().to_string()
}

#[tauri::command]
fn manager_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// What changed in the CURRENT version -- shown as the version chip's tooltip, so someone looking
/// at the tab bar knows what shipped without leaving the Manager. Update this string alongside
/// `Cargo.toml`'s and `tauri.conf.json`'s `version`, AND `CURRENT_VERSION_TITLE` below, on every
/// bump; it deliberately describes only the current version, not a full changelog (that's `git log`).
#[tauri::command]
fn manager_version_description() -> String {
    format!(
        "{CURRENT_VERSION_TITLE}\nBuilt {}\n{}",
        env!("NPDEV_MANAGER_BUILD_TIMESTAMP"),
        VERSION_DESCRIPTION
    )
}

const VERSION_DESCRIPTION: &str =
    "0.13.31: On Use a browser AI, the waiting step now also watches your Downloads folder, so when your AI offers the answer as a file (a model.json, or a zip of files) saving it is the whole job -- no picking the file. NPDev only reads files that appear after you start waiting, only .json, .zip, .txt and .md, only once they have finished downloading, keeps a file only if it is that answer, forgets every other one, and never moves, renames or deletes anything. The same notice, Stop button and Settings > AI switch cover both the clipboard and the Downloads folder (a person who had turned the clipboard watching off keeps it off), and the notice names the places it is really reading. Before that, 0.13.30: On Use a browser AI, once you have copied the message for your AI, NPDev now watches what you copy and picks up the AI's answer by itself when you press the AI's own Copy button -- no switching back, no pasting. A notice on the waiting step says it is watching, with a button to stop; NPDev keeps only a text that is that answer and forgets everything else you copy; and Settings > AI has a switch to turn it off for good (it is on by default). Before that, 0.13.29: On Use a browser AI, when you make a second app while the first is still running, the second one now says so in one plain sentence (another app is already using this app's address -- stop it in Running apps and try again) instead of waiting three minutes and saying only that it has not answered (it says so once the clash has lasted, not while the other app is just being stopped), and Try again on a new app that is already running stops it before rebuilding, as a change does. Before that, 0.13.28: On Use a browser AI, after you make an app, Make another change now lists that app (it used to show a list read before the app existed), switching to the API-key screen and back keeps the app you picked instead of bringing back the other screen's older choice, and a change to an app that is running now stops it BEFORE rebuilding (it used to try to rebuild while the app still held its files, and failed), and an app that stopped by itself no longer blocks starting it again (it used to answer \"already running\" forever). Before that, 0.13.27: Use a browser AI is clearer when an answer cannot be used. When your AI reaches its free limit, or keeps making the same mistake, the button copies a text made for a brand-new conversation (so you can take it to another AI); every other problem still copies the short message for the conversation you already have. A small list under the answer box shows the rounds so far -- which were cut off or had mistakes, and which one is waiting for an answer. Before that, 0.13.26: Use a browser AI now finishes the job. After you apply an answer, NPDev builds the app, starts it and tells you when it answers, then offers Open the app -- and if the build or the start fails it says so in a sentence, keeps the reason under See what happened, and lets you try again. A change to an app that is running stops it first, since a running app holds its own files. A new app gets a folder of its own, like New app does. Before that, 0.13.25: On Use a browser AI, the AI's answer can come back three more ways: press Ctrl+V anywhere on step 3 (no need to click the box first), choose the file the AI made with a button, or drop that file on the window. Each is checked exactly like a pasted answer. Before that, 0.13.24: Use a browser AI is a new screen under My apps, and it is where Ask AI on Home now goes. Say what you want, copy one text, give it to any chat AI you already use (no key, no cost), paste its answer back, read what it would change in plain words, tick the boxes it asks about, and apply -- with an Undo for a change. Use an AI with an API key is the second choice, one button away on both screens, and carries your request across. Before that, 0.13.23: Behind the scenes, the window can open a browser-AI session, read an answer from a dropped file, apply it, undo it and give it up. Before that, 0.13.22: The Prompter shows the measured prompt for a chat AI in your browser and has a box to paste the answer back -- NPDev reads it, tells you if it was cut off or left parts out, and applies a change only after you say yes (with a backup you can undo). Before that, 0.13.21: A database you installed yourself, found on its port, gets one plain sentence instead of a terminal command. Before that, 0.13.20: Install step 3 and the version lines name your own local copy of NPDev in Portuguese (cópia local) instead of an English fragment. Before that, 0.13.19: Home says 1 app needs attention (not 1 need), and preparing NPDev ends with one sentence (Done: NPDev is ready to make apps.) instead of a technical log. Before that, 0.13.18: The technical detail behind each database line (its tooltip) now reads in Portuguese too. Before that, 0.13.17: The database list on New app speaks plainly -- each database says Ready to use on this computer, Already running on this computer or Needs Docker running on this computer first (the technical detail is its tooltip), and a Docker database that cannot be reached says so in one sentence. Before that, 0.13.16: Screens for advanced users say so at the top -- Impact, Packs, Checks, Evaluations, Running apps and Tasks open with a note that you do not need them to make, run, change or share an app -- and Impact, Packs, Checks and Evaluations get plain titles and a sentence on what each is for instead of the commands behind them. Before that, 0.13.15: Edit starts with What do you want to change? -- say it in your own words and Ask the AI to make this change opens Ask AI with your words filled in; editing the model JSON by hand is under Advanced. Data reads plainly (Save a copy, Bring data in, Check first (changes nothing), CSV (opens in Excel)) with no commands or capital-letter headings. On Share, Keys is now Access keys, with one line saying what a key is for (whoever opens your shared app needs one to get in). Before that, 0.13.14: New app names each database for what it is and when to choose it (Simple: built in, nothing to install (recommended); PostgreSQL ... NPDev can install it for you), with the technical text under Technical details; the Connection fields are folded away (NPDev fills them in) and open by themselves when you pick a found server; the old This server is mine checkbox now reads This database was here before NPDev (do not let NPDev erase it). On Home, an app with a problem says Something is wrong: see Manage (the monitor's own sentence is its tooltip), and Recent activity names each task plainly (Install Java, Start an app...). Before that, 0.13.13: Three screens read for beginners -- Start and stop is one sentence (is the app running?) and one big Start the app button, with Open in the browser once it runs; the port, the app folder, the database buttons and the log are under Advanced. Settings > Computer opens with a verdict (This computer is ready to make apps, or which things are missing) and keeps every check under Details, opened by itself when something fails. Install's NPDev step is one Install NPDev button that downloads the newest version and prepares it; choosing a version is under Details, and Monitored App Paths is now Where your apps live. Before that, 0.13.12: Postgres without Docker -- on New app, Postgres offers two more ways side by side, with a ? that says which to pick: NPDev's own Postgres (recommended: a small download just for NPDev, no permission prompt, starts by itself when you sign in) or Postgres as a Windows service (the official installer; Windows asks for permission). When Docker is missing this comes first. Press Create and NPDev installs it, makes the app's database and fills in the connection. MySQL and SQL Server say plainly that Docker is the way for them, for now. Before that, 0.13.11: Java and Python are guided the same way -- Settings > Install says in plain words what each is for and whether this computer already has it (\"Java is ready.\"), and when one is missing a single button downloads NPDev's own copy, with the percentage while it downloads and no permission prompt; folders and versions moved under Details. Before that, 0.13.10: Docker has its own assistant -- Settings > Install > 6. Docker walks you through getting Docker ready before you create any app: it checks this computer, turns on WSL (the part of Windows Docker runs on) when it is off, installs Docker Desktop, tells you when to restart, starts it, and notices by itself when it is ready. The New app Database card uses the same steps. Before that, 0.13.9: Setting up a database is guided. Pick Postgres, MySQL or SQL Server on My apps > New app and the Database card looks for that engine on this computer by itself: it lists the servers and stopped containers it finds (Use this, Start) and, when Docker is running, offers to create a new server in Docker for you -- the official image, reachable only from this computer, with a generated password it fills in. If Docker is installed but off it starts it and waits; if Docker is missing it walks you through installing it (install, restart, start) and notices by itself when it is ready. The database descriptions (MySQL, Postgres, SQL Server, H2) are now translated to Portuguese. Before that, 0.13.8: My apps > All apps is two panels -- Your apps (each app's name, with its folder beneath) and New app, whose fields are grouped into Name and folder, Database and Connection -- and Settings has short tab names (AI, Credentials, Appearance, Versions, Computer, Install, Checks, Evaluations) with the longer explanation in each tab's tooltip; the two check tabs under My apps are now Browser and Data rules. Before that, 0.13.7: the AI providers screen reads fully in Portuguese -- provider names (Anthropic (pago), Groq (plano gratuito)...) and the hint about the environment variable that overrides a stored key are translated. Before that, 0.13.6: Home is a real starting point -- under the next-step card it now shows four status tiles (computer, apps, running, AI keys) that each open the screen where that is fixed, Your apps with every app's state and its own Open, Start, Stop and Manage buttons (running first; two apps with the same name are told apart by folder), Recent activity with a Get help link on a failure, and six shortcuts; a greeting with Refresh and the time it last looked. Before that, 0.13.5: the version chip shows only the version number (what this version is about and when it was built are the first two lines of its tooltip), and the one-line explanation under each place in the top bar is gone -- it is the tooltip of the place's button. Before that: the action buttons on the AI providers table no longer overlap in Portuguese, and on Running apps the GENERATE & RUN button of a not-yet-generated app no longer sits on top of its message (which is now translated). Before that: a theme can describe itself in more than one language (a `descriptions` map next to `names`) and its card shows the one matching the language picker -- Dark ships English, Portuguese and Spanish, every other theme stays English for now. Before that: eight screens (AI providers, Secrets, Packs, Studio, Verification, Evals, DB import/export, GPU checks) drop their neon-green glowing title and match the rest of the window, and their buttons stay readable when hovered. Before that: the Portuguese window is more complete -- Disable, Test, Reset, Remove key and Get a key on AI providers are translated, and previewing or keeping a theme names it in your language. Before that: Appearance gets Browse themes -- a community catalogue of themes you can preview and keep (every file is checked against the catalogue's sha256 first, and a file that does not match is refused) -- and Share this theme, which opens GitHub's new-file page with your theme filled in. Before that: the whole window follows the language picker (English and Portuguese) -- every screen, button, status line and message is translated as it is drawn, switching language redraws the open screen, and a new English string with no translation fails the build. Before that: Activity > Tasks -- gates, rebuilds and benchmarks that take a while, with whether each is still alive (a task whose watcher died shows as LOST), Stop, Restart and Open log; checks itself every 10 seconds while open. Before that: Set up disappears once your computer is ready (its screens move to Settings), failing checks on Ready get a Fix button that jumps to the right Install step, the strips of screens work from the keyboard (arrow keys, Home, End) and there is a Skip to content link. Before that: Home -- one card that says what to do next, with one button -- and the app workspace: choose an app once and Start & stop, Edit, Ask AI, Data, Share and the checks all follow it (their own app pickers hide). Also fixes two screens that silently never worked: the Packs app picker was never filled and the Verification table drew nothing, because two scripts shared function names. Before that: every colour in every screen now follows your theme (no hard-coded colours left), statuses show a word as well as a colour, progress is announced to screen readers, and all 19 screens pass the axe accessibility scan. Before that: Appearance & accessibility (Settings) -- pick Dark, Light, Retro, Synth, Farm, Warm, Windows or \
     High contrast, load a theme file, or ask any AI for a new theme (you preview it and can undo); text \
     size, motion, focus, spacing, links and colour-blind safe status colours; remembered between starts \
     with no flash. Before that: one look for the whole window, Get help with this error (copy a redacted \
     report for any AI chat), four places instead of 18 tabs and a language picker.";

/// Short headline for the version chip's own visible text (not just its tooltip) -- update
/// alongside `manager_version_description` on every bump. The timestamp half is NOT hand-maintained
/// here: `build.rs` stamps `NPDEV_MANAGER_BUILD_TIMESTAMP` at compile time, so a rebuild without a
/// version bump is still visible at a glance instead of looking identical to the last one.
const CURRENT_VERSION_TITLE: &str = "Your answer is picked up by itself";

#[tauri::command]
fn manager_build_stamp() -> String {
    // Only the version number is visible. The title and the build time are the first two lines of
    // the tooltip (`manager_version_description`).
    format!("v{}", env!("CARGO_PKG_VERSION"))
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
    // Phase F: the Prompter tab is where a provider gets configured now -- there is no Settings
    // screen, and this modal is read-only. Reporting the profile COUNT lets the modal say "you have
    // providers, they are over there" instead of "not configured", which is wrong and unactionable
    // once the user has set one up in the other tab.
    let prompter_profiles = manager.prompter_profiles.len();
    // The legacy key may have moved to the OS credential store (migrate_legacy_assistant_key), so
    // "is a key set" is no longer answerable from this struct alone.
    let has_api_key = manager
        .assistant
        .as_ref()
        .and_then(|a| a.api_key.as_ref())
        .map(|k| !k.is_empty())
        .unwrap_or(false)
        || secrets::has_secret(LEGACY_ASSISTANT_PROFILE_ID);
    match &manager.assistant {
        None => serde_json::json!({"configured": false, "kind": "ai", "prompterProfiles": prompter_profiles,
                                   "note": "uses the scrap-assistant binding of `npdev ai`; keys in the AI Providers tab"}),
        Some(config) => serde_json::json!({
            "configured": true,
            "kind": config.kind,
            "profile": config.profile,
            "command": config.command,
            "endpoint": config.endpoint,
            "model": config.model,
            "prompterProfiles": prompter_profiles,
            // The key is never returned to the UI, only whether one is set. A window that can read
            // it back is a window that can leak it into a screenshot.
            "hasApiKey": has_api_key,
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
    profile: Option<String>,
) -> Result<(), String> {
    // A key typed now goes straight to the OS credential store, never into manager.json (AIP-2, 2.6).
    // An empty box means "leave it alone", not "erase it" -- the UI cannot show the key, so it cannot
    // send it back either, and treating blank as a delete would wipe the key every save. Stored BEFORE
    // the config changes, so a refusing store leaves an unchanged configuration.
    if let Some(key) = api_key.filter(|k| !k.is_empty()) {
        secrets::set_secret(LEGACY_ASSISTANT_PROFILE_ID, &key)?;
    }
    let mut manager = state.manager.lock().expect("lock poisoned");
    let legacy_key = manager.assistant.as_ref().and_then(|a| a.api_key.clone());
    manager.assistant = Some(state::AssistantConfig {
        kind,
        profile: profile.filter(|p| !p.is_empty()),
        command: command.unwrap_or_default(),
        endpoint,
        // Only a not-yet-migrated plaintext key survives here (see AssistantConfig::api_key).
        api_key: legacy_key,
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
/// were displayed, and that difference is the entire guarantee. `profile`/`effort` (AIP-4, 4.3) are
/// this run's choice from the modal, for the `ai` kind only; absent, the configured profile (or the
/// scrap-assistant binding) and the profile's own effort apply.
#[tauri::command]
async fn assistant_generate(
    state: State<'_, AppState>,
    payload: Value,
    profile: Option<String>,
    effort: Option<String>,
) -> Result<Value, String> {
    let config = state.manager.lock().expect("lock poisoned").assistant.clone();
    // Nothing configured means the `ai` kind: the scrap-assistant binding of `npdev ai`, with the key
    // from the AI Providers tab -- a working default that still sends nothing without a stored key.
    let config = config.unwrap_or_else(|| state::AssistantConfig { kind: "ai".to_string(), ..Default::default() });
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
    let run_profile = profile.filter(|p| !p.is_empty()).or_else(|| config.profile.clone().filter(|p| !p.is_empty()));
    if let Some(profile) = run_profile.filter(|_| config.kind == "ai") {
        args.push("--profile".to_string());
        args.push(profile);
    }
    if let Some(effort) = effort.filter(|e| config.kind == "ai" && !e.is_empty()) {
        args.push("--effort".to_string());
        args.push(effort);
    }
    for part in &config.command {
        args.push("--command".to_string());
        args.push(part.clone());
    }
    if let Some(endpoint) = &config.endpoint {
        args.push("--endpoint".to_string());
        args.push(endpoint.clone());
    }
    if let Some(model) = &config.model {
        args.push("--model".to_string());
        args.push(model.clone());
    }

    // The key travels in the child's ENVIRONMENT, never on its argv.
    //
    // `--api-key <key>` put a live credential in the command line of a process, where any other
    // process on the machine can read it out of the process listing for as long as the child runs --
    // the same class of leak the payload-file rule above exists to prevent, in the same function.
    // `npdev ai generate-routine` reads NPDEV_AI_API_KEY when `--api-key` is absent, the idiom the
    // CLI already uses for SCRAPFORAI_API_KEY. `--api-key` still works for direct CLI use; the
    // Manager just stops being the thing that uses it.
    //
    // Read from the OS credential store first (where migrate_legacy_assistant_key put it), falling
    // back to whatever is still in manager.json on a machine where that migration could not run.
    //
    // The "ai" kind needs no key here at all: the CLI resolves it from `npdev providers` itself.
    let api_key = if config.kind == "ai" {
        None
    } else {
        secrets::get_secret(LEGACY_ASSISTANT_PROFILE_ID)
            .ok()
            .flatten()
            .filter(|k| !k.is_empty())
            .or_else(|| config.api_key.clone())
    };

    log::info(format!("assistant_generate via provider kind={}", config.kind));
    let result = npdev::run_explore_with_env(
        &python,
        &cli,
        java_home.as_deref(),
        args,
        "ai generate-routine",
        api_key.map(|key| ("NPDEV_AI_API_KEY".to_string(), key)),
    )
    .await;
    let _ = std::fs::remove_file(&payload_path);
    result
}

// -------------------------------------------------------------------------------------------
// Phase F: the Prompter.
//
// The Manager's half of the agent-prompter feature: compose a prompt about a selected app and send
// it, with the credential in the OS credential store rather than in manager.json.
//
// It keeps Phase E's structural rule -- COMPOSE and SEND are two commands, and Send transmits
// exactly the text the user was shown. It does NOT reuse `AssistantConfig`, because that type's key
// field is the thing being moved out; profiles live in `state::PrompterProfile` and their keys in
// `secrets`.
//
// Everything the UI needs comes through these commands. The Manager's UI calls `fetch()` nowhere --
// an app's HTTP surface is reached from Rust, so a page in a window cannot be talked into making a
// request the Rust side would not.
// -------------------------------------------------------------------------------------------

#[tauri::command]
fn prompter_profiles(state: State<'_, AppState>) -> Value {
    let profiles = state.manager.lock().expect("lock poisoned").prompter_profiles.clone();
    let described: Vec<Value> = profiles
        .iter()
        .map(|p| {
            serde_json::json!({
                "id": p.id,
                "label": p.label,
                "kind": p.kind,
                "command": p.command,
                "endpoint": p.endpoint,
                "authStyle": p.auth_style,
                "models": p.models,
                "defaultModel": p.default_model,
                "defaultEffort": p.default_effort,
                "aiProfile": p.ai_profile,
                // Whether a credential EXISTS. Never the credential. Same rule as
                // assistant_config's hasApiKey: a window that can read a key back is a window that
                // can leak it into a screenshot.
                "hasCredential": secrets::has_secret(&p.id),
            })
        })
        .collect();
    serde_json::json!({ "profiles": described })
}

#[tauri::command]
fn save_prompter_profile(
    state: State<'_, AppState>,
    profile: state::PrompterProfile,
    api_key: Option<String>,
) -> Result<(), String> {
    if profile.id.trim().is_empty() {
        return Err("a profile needs an id".to_string());
    }
    // Store the credential BEFORE mutating the profile list. If the credential store refuses, the
    // user gets an error and an unchanged configuration, rather than a saved profile that silently
    // cannot send.
    if let Some(key) = api_key.filter(|k| !k.is_empty()) {
        secrets::set_secret(&profile.id, &key)?;
    }

    let mut manager = state.manager.lock().expect("lock poisoned");
    match manager.prompter_profiles.iter_mut().find(|p| p.id == profile.id) {
        Some(existing) => *existing = profile.clone(),
        None => manager.prompter_profiles.push(profile.clone()),
    }
    // Id and kind only -- a label is user text and an endpoint is a URL they typed.
    log::info(format!("prompter profile saved: id={} kind={}", profile.id, profile.kind));
    manager.save().map_err(|e| e.to_string())
}

#[tauri::command]
fn delete_prompter_profile(state: State<'_, AppState>, id: String) -> Result<(), String> {
    {
        let mut manager = state.manager.lock().expect("lock poisoned");
        manager.prompter_profiles.retain(|p| p.id != id);
        manager.save().map_err(|e| e.to_string())?;
    }
    // After the list is saved: a credential left behind by a failed delete is recoverable (the user
    // can re-create the profile and overwrite it), whereas a profile left behind by a failed save
    // would point at a credential that no longer exists.
    secrets::delete_secret(&id)?;
    log::info(format!("prompter profile deleted: id={id}"));
    Ok(())
}

// -------------------------------------------------------------------------------------------
// S17b (NPDEV_MEGA_ROADMAP.md): DB/deploy secret profiles with per-environment isolation.
// Same discipline as prompter_profiles above, restated for the scoped registry: the UI is told
// NAMES and an existence flag, never a value. The value lives in the OS credential store under
// account "<scope>/<env>/<id>" (secrets.rs); the manager.json registry (secret_profile_keys)
// holds only the names so a window can render them.
// -------------------------------------------------------------------------------------------

/// Lists DB/deploy credential keys. `scope`/`env` filter the registry (empty = all scopes/envs).
/// Every returned entry carries `hasCredential` -- whether the OS store HAS a value -- and never
/// the value itself. The same rule as prompter_profiles: a window that can read a key back is a
/// window that can leak it into a screenshot.
#[tauri::command]
fn secret_profiles(state: State<'_, AppState>, scope: Option<String>, env: Option<String>) -> Value {
    let manager = state.manager.lock().expect("lock poisoned");
    let scope_filter = scope.as_deref().unwrap_or("").trim();
    let env_filter = env.as_deref().unwrap_or("").trim();
    let keys = manager.secret_profile_keys.iter().filter(|k| {
        (scope_filter.is_empty() || k.scope == scope_filter)
            && (env_filter.is_empty() || k.env == env_filter)
    });
    let entries: Vec<Value> = keys.map(|k| {
        serde_json::json!({
            "scope": k.scope,
            "env": k.env,
            "profileId": k.profile_id,
            "label": k.label,
            "hasCredential": secrets::has_secret_scoped(&k.scope, Some(&k.env), &k.profile_id),
        })
    }).collect();
    serde_json::json!({ "profiles": entries, "scopes": secrets::SCOPES, "environments": secrets::ENVIRONMENTS })
}

/// Records a DB/deploy credential: value to the OS store, name to the registry. The credential is
/// stored BEFORE the registry save -- the same order prompter_profiles uses, so a refusing store
/// leaves the configuration unchanged instead of a registry entry pointing at nothing.
#[tauri::command]
fn save_secret_profile(
    state: State<'_, AppState>,
    scope: String,
    env: String,
    profile_id: String,
    label: Option<String>,
    value: Option<String>,
) -> Result<(), String> {
    let scope = scope.trim();
    let env = env.trim();
    let profile_id = profile_id.trim();
    if scope.is_empty() || env.is_empty() || profile_id.is_empty() {
        return Err("a secret profile needs scope, env and profile id".to_string());
    }
    if let Some(v) = value.filter(|v| !v.is_empty()) {
        secrets::set_secret_scoped(scope, Some(env), profile_id, &v)?;
    }
    let mut manager = state.manager.lock().expect("lock poisoned");
    let key = state::SecretProfileKey {
        scope: scope.to_string(),
        env: env.to_string(),
        profile_id: profile_id.to_string(),
        label: label.unwrap_or_default().trim().to_string(),
    };
    match manager.secret_profile_keys.iter_mut().find(|k| *k == &key) {
        Some(existing) => *existing = key.clone(),
        None => manager.secret_profile_keys.push(key),
    }
    log::info(format!("secret profile saved: scope={scope} env={env} id={profile_id}"));
    manager.save().map_err(|e| e.to_string())
}

/// Removes a DB/deploy credential: registry entry first (saved), then the OS-store value -- the
/// same order delete_prompter_profile uses, so a failed registry save cannot strand a profile that
/// points at a credential which no longer exists.
#[tauri::command]
fn delete_secret_profile(state: State<'_, AppState>, scope: String, env: String, profile_id: String) -> Result<(), String> {
    {
        let mut manager = state.manager.lock().expect("lock poisoned");
        manager.secret_profile_keys.retain(|k| {
            !(k.scope == scope && k.env == env && k.profile_id == profile_id)
        });
        manager.save().map_err(|e| e.to_string())?;
    }
    secrets::delete_secret_scoped(&scope, Some(&env), &profile_id)?;
    log::info(format!("secret profile deleted: scope={scope} env={env} id={profile_id}"));
    Ok(())
}

/// The app model that goes into the prompt as context.
///
/// Prefers `app-tree.json` (concepts WITH fields) over the probe's inlined `info.json` (names only),
/// the same order the generated page uses -- so the Manager and the page describe an app the same
/// way. `app-tree.json` is emitted on demand and is often absent, which is why `info.json` is not a
/// fallback for failure but the ordinary case.
///
/// The fetch happens HERE, in Rust, rather than in the window: `NPDevManager/ui` calls `fetch()`
/// nowhere, and this is app data like every other.
#[tauri::command]
async fn prompter_app_context(state: State<'_, AppState>, app_dir: String) -> Result<Value, String> {
    // Prefer the actual model source on disk -- full fields (a running app's app-tree.json may be
    // absent, and info.json carries names+routes only, never fields). `npdev init <d>` generates
    // into the sibling `<d>-app`, so this is the SAME mapping `model_dir_of_app` already uses to
    // prevent the Run/Monitor same-database collision -- reused, not reinvented.
    if let Some(model_dir) = model_dir_of_app(&app_dir) {
        let model_path = PathBuf::from(&model_dir).join("model.json");
        if let Ok(text) = std::fs::read_to_string(&model_path) {
            if let Ok(model) = serde_json::from_str::<Value>(&text) {
                let app_name = model.get("namespace").and_then(|n| n.as_str()).unwrap_or("").to_string();
                return Ok(serde_json::json!({
                    "appName": app_name,
                    "source": "model.json",
                    "modelPath": model_path.to_string_lossy(),
                    "context": model,
                }));
            }
        }
    }

    let probe = monitor_probe(state, app_dir, Some(true)).await?;
    let app_name = probe
        .get("info")
        .and_then(|i| i.get("namespace"))
        .and_then(|n| n.as_str())
        .or_else(|| probe.get("name").and_then(|n| n.as_str()))
        .unwrap_or("")
        .to_string();

    let base_url = probe
        .get("probeBaseUrl")
        .or_else(|| probe.get("baseUrl"))
        .and_then(|u| u.as_str())
        .unwrap_or("")
        .trim_end_matches('/')
        .to_string();

    if !base_url.is_empty() {
        let url = format!("{base_url}/app-tree.json");
        if let Ok(response) = reqwest::get(&url).await {
            if response.status().is_success() {
                if let Ok(doc) = response.json::<Value>().await {
                    if let Some(model) = doc.get("sections").and_then(|s| s.get("Model")) {
                        return Ok(serde_json::json!({
                            "appName": doc.get("appId").and_then(|a| a.as_str()).unwrap_or(&app_name),
                            "source": "app-tree.json",
                            "context": model,
                        }));
                    }
                }
            }
        }
    }

    match probe.get("info") {
        Some(info) if !info.is_null() => Ok(serde_json::json!({
            "appName": app_name,
            "source": "info.json",
            "context": {
                "dbEngine": info.get("dbEngine"),
                "concepts": info.get("concepts"),
                "flows": info.get("flows"),
            },
        })),
        // An app that is not running, or one generated before info.json existed. Not an error: the
        // Prompter still composes, it just has no current-model context to include.
        _ => Ok(serde_json::json!({ "appName": app_name, "source": "none", "context": Value::Null })),
    }
}

/// Sends EXACTLY the prompt it is handed -- the text the user is looking at in the compose box.
#[tauri::command]
async fn prompter_generate(
    state: State<'_, AppState>,
    profile_id: String,
    model: String,
    effort: Option<String>,
    prompt: String,
) -> Result<Value, String> {
    if npdev::fake_mode() {
        return Ok(serde_json::from_str(npdev::FIXTURE_PROMPTER_GENERATE)
            .expect("the prompter-generate fixture is valid JSON"));
    }

    // Scoped so the lock is released before any .await below -- a MutexGuard held across an await
    // point is the deadlock this codebase already learned about once.
    let profile = {
        let manager = state.manager.lock().expect("lock poisoned");
        manager.prompter_profiles.iter().find(|p| p.id == profile_id).cloned()
    };
    let profile = profile.ok_or_else(|| {
        format!("no provider profile '{profile_id}' -- open the Prompter tab's provider editor and add one")
    })?;

    let model = if model.trim().is_empty() {
        profile.default_model.clone().unwrap_or_default()
    } else {
        model
    };
    // The id and model, never the prompt and never the key.
    log::info(format!("prompter_generate profile={} model={}", profile.id, model));

    generate_via_profile(&state, &profile, &model, effort.as_deref(), &prompt).await
}

/// The dispatch body of `prompter_generate` above, pulled out so `ai_loop::run` (Session 2,
/// NPDEV_MEGA_ROADMAP.md) calls the exact same "command" vs "http" send rather than a second copy --
/// the manual Prompter tab and the automatic AI loop must speak to a provider identically.
pub(crate) async fn generate_via_profile(
    app_state: &AppState,
    profile: &state::PrompterProfile,
    model: &str,
    effort: Option<&str>,
    prompt: &str,
) -> Result<Value, String> {
    match profile.kind.as_str() {
        "ai" => prompter_generate_via_ai(app_state, profile, effort, prompt).await,
        "command" => prompter_generate_via_command(profile, prompt).await,
        // Legacy: move the key into `npdev providers` and the endpoint into an `npdev ai` profile the
        // first time the profile is used, then send through the one client. A migration that cannot
        // finish leaves the profile and its key untouched and sends the old way, so nobody loses a
        // working Prompter to an upgrade step.
        "http" => match migrate_http_profile(app_state, profile).await {
            Ok((migrated, _)) => prompter_generate_via_ai(app_state, &migrated, effort, prompt).await,
            Err(e) => {
                log::info(format!("prompter profile {} not migrated ({e}); sending the legacy way", profile.id));
                prompter_generate_via_http(profile, model, effort, prompt).await
            }
        },
        other => Err(format!("unknown provider kind '{other}' -- expected 'ai', 'command' or 'http'")),
    }
}

/// `npdev ai <args>` with python/cli/JAVA_HOME resolved the way every command here does.
pub(crate) async fn ai_call(app_state: &AppState, args: Vec<String>, stdin: Option<String>) -> Result<Value, String> {
    let java_home = resolve_java_home(app_state);
    let (python, cli) = if npdev::fake_mode() {
        (PathBuf::from("python"), PathBuf::from("npdev_cli.py"))
    } else {
        (resolve_python_exe(app_state).await?, resolve_npdev_cli(app_state)?)
    };
    npdev::run_ai(&python, &cli, java_home.as_deref(), &args, stdin).await
}

/// A CLI answer of `{"ok": true, ...}`, or its `error` as the Err.
fn cli_ok(answer: Value, what: &str) -> Result<Value, String> {
    if answer.get("ok").and_then(Value::as_bool) == Some(true) {
        Ok(answer)
    } else {
        Err(format!("{what}: {}", answer.get("error").and_then(Value::as_str).unwrap_or("failed")))
    }
}

/// The Prompter through `npdev ai complete` (AIP-2, 2.4). The prompt travels on stdin; the key is
/// resolved by the CLI from `npdev providers` and never reaches this process. `--feature prompter`
/// labels the call for the spend guard and the usage log.
async fn prompter_generate_via_ai(
    app_state: &AppState,
    profile: &state::PrompterProfile,
    effort: Option<&str>,
    prompt: &str,
) -> Result<Value, String> {
    let mut args: Vec<String> = ["complete", "--json", "--prompt-file", "-", "--feature", "prompter", "--timeout", "600"]
        .iter()
        .map(|s| s.to_string())
        .collect();
    if let Some(name) = profile.ai_profile.as_deref().filter(|n| !n.is_empty()) {
        args.push("--profile".to_string());
        args.push(name.to_string());
    }
    if let Some(level) = effort.filter(|e| !e.is_empty()) {
        args.push("--effort".to_string());
        args.push(level.to_string());
    }
    let answer = cli_ok(ai_call(app_state, args, Some(prompt.to_string())).await?, "the AI call failed")?;
    let text = answer.get("text").cloned().unwrap_or(Value::String(String::new()));
    Ok(serde_json::json!({
        "ok": true, "text": text, "raw": text,
        "provider": answer.get("provider"), "model": answer.get("model"), "profile": answer.get("profile"),
        "warnings": answer.get("warnings"),
    }))
}

/// Lower-case letters, digits and single dashes -- the id shape `npdev ai` accepts.
fn slug(text: &str) -> String {
    let mut out = String::new();
    for c in text.to_ascii_lowercase().chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c);
        } else if !out.ends_with('-') && !out.is_empty() {
            out.push('-');
        }
    }
    out.trim_end_matches('-').chars().take(60).collect()
}

fn url_host(url: &str) -> &str {
    let rest = url.split_once("://").map(|(_, r)| r).unwrap_or(url);
    rest.split('/').next().unwrap_or(rest)
}

/// AIP-2, 2.5: one legacy `http` Prompter profile becomes an `npdev ai` profile.
///
/// The key moves from the Manager's `prompter/<id>` keyring entry into `npdev providers` (piped on
/// stdin), under the catalogue provider whose endpoint matches -- as a second account of it when it
/// already holds a key (D5) -- or under a custom provider in the user's ai.json for an endpoint NPDev
/// does not know. The old entry is deleted only after the profile was rewritten, so a step that fails
/// leaves everything exactly as it was. A paid provider gets `spend allow-paid prompter`: this
/// profile was already sending to it, and the migration keeps what worked working.
async fn migrate_http_profile(
    app_state: &AppState,
    profile: &state::PrompterProfile,
) -> Result<(state::PrompterProfile, Value), String> {
    let endpoint = profile.endpoint.clone().filter(|e| !e.is_empty())
        .ok_or_else(|| "the profile has no endpoint".to_string())?;
    let key = secrets::get_secret(&profile.id)?.filter(|k| !k.is_empty())
        .ok_or_else(|| "no credential stored for it".to_string())?;
    let listing = cli_ok(ai_call(app_state, vec!["providers".into(), "--json".into()], None).await?, "ai providers")?;
    let rows = listing.get("providers").and_then(Value::as_array).cloned().unwrap_or_default();
    let matches_prefix = |row: &&Value| {
        let mut urls = vec![row.get("baseUrl").and_then(Value::as_str).unwrap_or("").to_string()];
        if let Some(aliases) = row.get("aliasBaseUrls").and_then(Value::as_array) {
            urls.extend(aliases.iter().filter_map(Value::as_str).map(str::to_string));
        }
        urls.iter().any(|u| !u.is_empty() && endpoint.starts_with(u.trim_end_matches('/')))
    };
    let found = rows.iter().find(matches_prefix)
        .or_else(|| rows.iter().find(|row| url_host(row.get("baseUrl").and_then(Value::as_str).unwrap_or("")) == url_host(&endpoint)))
        .cloned();
    let auth_style = profile.auth_style.clone().unwrap_or_else(|| "bearer".to_string());
    let (provider_id, paid, key_present) = match &found {
        Some(row) => (
            row.get("id").and_then(Value::as_str).unwrap_or_default().to_string(),
            row.get("kind").and_then(Value::as_str) == Some("paid"),
            !matches!(row.get("keySource").and_then(Value::as_str), Some("missing") | None),
        ),
        None => {
            let id = slug(&format!("prompter-{}", profile.id));
            let protocol = match auth_style.as_str() {
                "x-api-key" => "anthropic-messages",
                "x-goog-api-key" => "gemini-native",
                _ => "openai-chat",
            };
            let base = endpoint.split("/chat/completions").next().unwrap_or(&endpoint);
            let base = base.split("/messages").next().unwrap_or(base);
            let base = base.split("/models/").next().unwrap_or(base).to_string();
            let args = ["providers", "add", &id, "--name", &profile.label, "--kind", "paid", "--protocol", protocol,
                        "--auth-style", &auth_style, "--base-url", &base, "--json"];
            cli_ok(ai_call(app_state, args.iter().map(|s| s.to_string()).collect(), None).await?, "ai providers add")?;
            (id, true, false)
        }
    };
    let account = if key_present { Some(slug(&profile.id)) } else { None };
    let mut add_key = vec!["add".to_string(), provider_id.clone(), "--key-stdin".to_string(), "--json".to_string()];
    if let Some(a) = &account {
        add_key.extend(["--account".to_string(), a.clone()]);
    }
    let java_home = resolve_java_home(app_state);
    let (python, cli) = (resolve_python_exe(app_state).await?, resolve_npdev_cli(app_state)?);
    cli_ok(npdev::run_providers(&python, &cli, java_home.as_deref(), &add_key, Some(key)).await?, "providers add")?;

    let model = profile.default_model.clone().filter(|m| !m.is_empty())
        .or_else(|| profile.models.first().cloned())
        .or_else(|| found.as_ref().and_then(|r| r.get("defaultModel")).and_then(Value::as_str).map(str::to_string))
        .ok_or_else(|| "the profile names no model".to_string())?;
    let known = cli_ok(ai_call(app_state, vec!["models".into(), "--provider".into(), provider_id.clone(), "--all".into(),
                                             "--json".into()], None).await?, "ai models")?;
    let has_model = known.get("models").and_then(Value::as_array)
        .is_some_and(|ms| ms.iter().any(|m| m.get("id").and_then(Value::as_str) == Some(model.as_str())));
    if !has_model {
        cli_ok(ai_call(app_state, vec!["models".into(), "add".into(), provider_id.clone(), model.clone(), "--json".into()],
                       None).await?, "ai models add")?;
    }
    let name = slug(&format!("prompter-{}", profile.id));
    let mut fields = vec!["--provider".to_string(), provider_id.clone(), "--model".to_string(), model.clone(),
                          "--max-tokens".to_string(), "16000".to_string(), "--json".to_string()];
    if let Some(level) = profile.default_effort.clone().filter(|e| !e.is_empty()) {
        fields.extend(["--effort".to_string(), level]);
    }
    if let Some(a) = &account {
        fields.extend(["--account".to_string(), a.clone()]);
    }
    let mut add = vec!["profiles".to_string(), "add".to_string(), name.clone()];
    add.extend(fields.clone());
    if cli_ok(ai_call(app_state, add, None).await?, "ai profiles add").is_err() {
        let mut set = vec!["profiles".to_string(), "set".to_string(), name.clone()];
        set.extend(fields);
        cli_ok(ai_call(app_state, set, None).await?, "ai profiles set")?;
    }
    if paid {
        cli_ok(ai_call(app_state, vec!["spend".into(), "allow-paid".into(), "prompter".into(), "--json".into()], None)
                   .await?, "ai spend allow-paid")?;
    }

    let mut migrated = profile.clone();
    migrated.kind = "ai".to_string();
    migrated.ai_profile = Some(name.clone());
    migrated.endpoint = None;
    migrated.auth_style = None;
    {
        let mut manager = app_state.manager.lock().expect("lock poisoned");
        if let Some(existing) = manager.prompter_profiles.iter_mut().find(|p| p.id == profile.id) {
            *existing = migrated.clone();
        }
        manager.save().map_err(|e| e.to_string())?;
    }
    if let Err(e) = secrets::delete_secret(&profile.id) {
        log::info(format!("prompter profile {} migrated, but its old keyring entry stays: {e}", profile.id));
    }
    // Ids only -- never the key, the endpoint the user typed, or the model list.
    log::info(format!("prompter profile {} migrated to npdev ai profile {name} (provider {provider_id})", profile.id));
    Ok((migrated, serde_json::json!({
        "id": profile.id, "aiProfile": name, "provider": provider_id, "account": account,
        "customProvider": found.is_none(), "paidAllowed": paid,
    })))
}

/// AIP-2, 2.5: migrate every legacy `http` Prompter profile that still holds a key. The Prompter tab
/// calls this when it opens; `generate_via_profile` also migrates one on first use.
#[tauri::command]
async fn migrate_prompter_profiles(state: State<'_, AppState>) -> Result<Value, String> {
    if npdev::fake_mode() {
        return Ok(serde_json::json!({ "migrated": [], "failed": [] }));
    }
    let legacy: Vec<state::PrompterProfile> = {
        let manager = state.manager.lock().expect("lock poisoned");
        manager.prompter_profiles.iter().filter(|p| p.kind == "http").cloned().collect()
    };
    let mut migrated = Vec::new();
    let mut failed = Vec::new();
    for profile in legacy {
        if !secrets::has_secret(&profile.id) {
            continue;
        }
        match migrate_http_profile(&state, &profile).await {
            Ok((_, report)) => migrated.push(report),
            Err(e) => failed.push(serde_json::json!({ "id": profile.id, "error": e })),
        }
    }
    Ok(serde_json::json!({ "migrated": migrated, "failed": failed }))
}

/// The AI Providers tab (AIP-4): `npdev ai <args> --json`, a thin pipe. `complete` is not reachable
/// from here -- sending a prompt is the Prompter's job, with its own compose-then-send rule.
#[tauri::command]
async fn ai_command(state: State<'_, AppState>, args: Vec<String>) -> Result<Value, String> {
    const ALLOWED: [&str; 12] = ["providers", "models", "profiles", "bindings", "bind", "reset", "validate", "test",
                                 "usage", "spend", "detect", "app"];
    match args.first().map(String::as_str) {
        Some(first) if ALLOWED.contains(&first) => {}
        other => return Err(format!("not an `npdev ai` command this window may run: {other:?}")),
    }
    let mut args = args;
    if !args.iter().any(|a| a == "--json") {
        args.push("--json".to_string());
    }
    ai_call(&state, args, None).await
}

/// Validates a candidate model.json the Prompter got back from an AI answer, BEFORE anything
/// touches the app's real model. Writes the candidate to a scratch file (never the app's own
/// model.json) and runs it through the real DSL validator -- the exact typed
/// npdev-validation-report.v2 an AI-authoring loop would self-correct against.
#[tauri::command]
async fn validate_prompter_model(state: State<'_, AppState>, candidate: Value) -> Result<Value, String> {
    let candidate_path =
        std::env::temp_dir().join(format!("npdev-prompter-candidate-{}.json", std::process::id()));
    std::fs::write(
        &candidate_path,
        serde_json::to_string_pretty(&candidate).map_err(|e| e.to_string())?,
    )
    .map_err(|e| format!("could not write candidate model: {e}"))?;

    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    let java_home = resolve_java_home(&state);
    let result =
        npdev::run_validate_model(&python, &cli, java_home.as_deref(), &candidate_path.to_string_lossy()).await;
    let _ = std::fs::remove_file(&candidate_path);
    result
}

/// "Use a browser AI": the ONE prompt the Prompter shows, copies and sends. `npdev ai relay prompt`
/// composes it (the measured wording, the authoring knowledge, a session code the answer will carry
/// back); this command only gathers the inputs. `intent` is `create` or `change`; a change reads the
/// app's own model.json from disk, the same file `prompter_app_context` reads, so the AI is shown the
/// real model and not a re-serialisation of it. `language` is the Manager's language id (`pt`, `en`).
#[tauri::command]
async fn relay_prompt(
    state: State<'_, AppState>,
    intent: String,
    ask: String,
    app_dir: Option<String>,
    language: Option<String>,
) -> Result<Value, String> {
    if !matches!(intent.as_str(), "create" | "change") {
        return Err(format!("not an intent this window can compose: '{intent}'"));
    }
    if ask.trim().is_empty() {
        return Err("describe what to build or change first".to_string());
    }
    let model_text = if intent == "change" && !npdev::fake_mode() {
        let dir = app_dir
            .as_deref()
            .filter(|d| !d.trim().is_empty())
            .ok_or_else(|| "a change needs an app to change".to_string())?;
        let model_dir = model_dir_of_app(dir).ok_or_else(|| "could not find this app's model".to_string())?;
        let model_path = PathBuf::from(model_dir).join("model.json");
        Some(std::fs::read_to_string(&model_path).map_err(|e| format!("could not read this app's model: {e}"))?)
    } else {
        None
    };
    let language_name = npdev::relay_language_name(language.as_deref().unwrap_or("en"));
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    let java_home = resolve_java_home(&state);
    // The ask is the user's own words and the model is their app: only the code and the round are logged.
    let result = npdev::run_relay_prompt(
        &python,
        &cli,
        java_home.as_deref(),
        &intent,
        &ask,
        model_text.as_deref(),
        language_name,
    )
    .await?;
    log::info(format!(
        "relay_prompt intent={intent} code={} round={}",
        result.get("code").and_then(|c| c.as_str()).unwrap_or("-"),
        result.get("round").and_then(|r| r.as_u64()).unwrap_or(0)
    ));
    Ok(result)
}

/// "Use a browser AI": what the user copied back from the chat AI, classified by `npdev ai relay
/// ingest` (the CLI decides what the text is; this window never parses an answer). `code` and `round`
/// are the ones `relay_prompt` returned for the prompt the user took to the AI.
#[tauri::command]
async fn relay_ingest_text(
    state: State<'_, AppState>,
    text: String,
    code: String,
    round: Option<u32>,
    source: Option<String>,
) -> Result<Value, String> {
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    let java_home = resolve_java_home(&state);
    let result =
        npdev::run_relay_ingest_text(&python, &cli, java_home.as_deref(), &text, &code, round, source.as_deref()).await?;
    // Never the answer itself, which is the user's app.
    log::info(format!(
        "relay_ingest_text code={code} outcome={}",
        result.get("outcome").and_then(|o| o.as_str()).unwrap_or("-")
    ));
    Ok(result)
}

/// What every `relay_*` command below needs to run the CLI: Python, the CLI script and JAVA_HOME.
async fn relay_cli(state: &AppState) -> Result<(PathBuf, PathBuf, Option<String>), String> {
    Ok((resolve_python_exe(state).await?, resolve_npdev_cli(state)?, resolve_java_home(state)))
}

/// One word for the log: the answer's `outcome` (ingest), else `ok`, else `refused:<the CLI's error code>`.
/// Never any of the user's words, files or app.
fn relay_verdict(result: &Value) -> String {
    if let Some(outcome) = result.get("outcome").and_then(|o| o.as_str()) {
        return outcome.to_string();
    }
    match result.get("ok").and_then(|ok| ok.as_bool()) {
        Some(true) => "ok".to_string(),
        Some(false) => format!("refused:{}", result.get("error").and_then(|e| e.as_str()).unwrap_or("-")),
        None => "-".to_string(),
    }
}

/// "Use a browser AI": open a session -- like `relay_prompt`, but the session is kept, so the answer can be
/// checked, applied and undone later (even after the window is closed). `intent` is `create`, `change` or
/// `fix`; a change or fix names the app (`app_dir`, its model or generated folder -- the CLI reads the model
/// itself), a create may name the folder it will get (`new_app_name`). `target` is the chat AI the user is
/// going to (it shapes the prompt), `errors` is what went wrong for a fix. Answers like `relay_prompt`.
#[tauri::command]
async fn relay_start(
    state: State<'_, AppState>,
    intent: String,
    ask: String,
    app_dir: Option<String>,
    new_app_name: Option<String>,
    target: Option<String>,
    language: Option<String>,
    interview: Option<bool>,
    errors: Option<String>,
) -> Result<Value, String> {
    if !matches!(intent.as_str(), "create" | "change" | "fix") {
        return Err(format!("not an intent this window can compose: '{intent}'"));
    }
    if intent != "fix" && ask.trim().is_empty() {
        return Err("describe what to build or change first".to_string());
    }
    let app_dir = app_dir.filter(|d| !d.trim().is_empty());
    if intent != "create" && app_dir.is_none() && !npdev::fake_mode() {
        return Err("a change needs an app to change".to_string());
    }
    let new_app_name = new_app_name.filter(|n| !n.trim().is_empty());
    let language_name = npdev::relay_language_name(language.as_deref().unwrap_or("en"));
    let (python, cli, java_home) = relay_cli(&state).await?;
    let result = npdev::run_relay_start(
        &python,
        &cli,
        java_home.as_deref(),
        &intent,
        &ask,
        app_dir.as_deref(),
        new_app_name.as_deref(),
        target.as_deref(),
        language_name,
        interview.unwrap_or(false),
        errors.as_deref(),
    )
    .await?;
    log::info(format!(
        "relay_start intent={intent} code={} round={}",
        result.get("code").and_then(|c| c.as_str()).unwrap_or("-"),
        result.get("round").and_then(|r| r.as_u64()).unwrap_or(0)
    ));
    Ok(result)
}

/// "Use a browser AI": the native "choose the file the AI made" dialog -- the keyboard-and-mouse way to do what
/// dropping a file on the window does. Only the kinds of file the CLI reads are offered. Empty when the person
/// cancels. The CLI, not this dialog, decides what a chosen file is (it refuses anything it does not read).
#[tauri::command]
async fn relay_pick_answer_files() -> Vec<String> {
    let Some(handles) = rfd::AsyncFileDialog::new()
        .set_title("Choose the file the AI made")
        .add_filter("What an AI makes", &["json", "txt", "md", "zip"])
        .pick_files()
        .await
    else {
        return Vec::new();
    };
    handles.into_iter().map(|h| h.path().to_string_lossy().to_string()).collect()
}

/// "Use a browser AI": the file(s) the chat AI made (`.json`, `.txt`, `.md`, `.zip`) -- dropped on the
/// window, picked, or found in Downloads -- classified like a pasted answer. `source` says how they arrived
/// (`drop`, `file-picker`, `downloads-watch`).
#[tauri::command]
async fn relay_ingest_file(
    state: State<'_, AppState>,
    files: Vec<String>,
    code: String,
    round: Option<u32>,
    source: Option<String>,
) -> Result<Value, String> {
    let (python, cli, java_home) = relay_cli(&state).await?;
    let result =
        npdev::run_relay_ingest_file(&python, &cli, java_home.as_deref(), &files, &code, round, source.as_deref()).await?;
    log::info(format!("relay_ingest_file code={code} files={} outcome={}", files.len(), relay_verdict(&result)));
    Ok(result)
}

/// "Use a browser AI": one session's state, rounds and last result.
#[tauri::command]
async fn relay_status(state: State<'_, AppState>, code: String) -> Result<Value, String> {
    let (python, cli, java_home) = relay_cli(&state).await?;
    npdev::run_relay_status(&python, &cli, java_home.as_deref(), &code).await
}

/// "Use a browser AI": the sessions still open, newest first -- how a closed window finds its way back.
#[tauri::command]
async fn relay_list(state: State<'_, AppState>) -> Result<Value, String> {
    let (python, cli, java_home) = relay_cli(&state).await?;
    npdev::run_relay_list(&python, &cli, java_home.as_deref()).await
}

/// "Use a browser AI": write a READY answer -- a create makes the app inside `parent_dir`, a change
/// replaces the model after a backup. `confirmed` carries the user's yes to each preview warning that asked
/// for one; without it the CLI refuses (`confirmation-required`, naming what is missing) and writes nothing.
#[tauri::command]
async fn relay_apply(
    state: State<'_, AppState>,
    code: String,
    round: Option<u32>,
    confirmed: Option<Vec<String>>,
    parent_dir: Option<String>,
) -> Result<Value, String> {
    let (python, cli, java_home) = relay_cli(&state).await?;
    let parent_dir = parent_dir.filter(|d| !d.trim().is_empty());
    let result = npdev::run_relay_apply(
        &python,
        &cli,
        java_home.as_deref(),
        &code,
        round,
        confirmed.as_deref().unwrap_or(&[]),
        parent_dir.as_deref(),
    )
    .await?;
    log::info(format!("relay_apply code={code} {}", relay_verdict(&result)));
    // A NEW app now exists on disk: put it in the list the Apps screen shows, the way `create_app` does.
    if result.get("ok").and_then(|v| v.as_bool()) == Some(true) && result.get("created").and_then(|v| v.as_bool()) == Some(true) {
        if let Some(directory) = result.get("appDir").and_then(|d| d.as_str()) {
            register_created_app(&state, directory);
        }
    }
    Ok(result)
}

/// Adds an app the AI relay just created to the Manager's app list (once: the same folder is never listed
/// twice, whatever the slash style or letter case). A failure to save only means the app is not listed; the
/// app itself is on disk, so it is logged and not raised.
fn register_created_app(state: &AppState, directory: &str) {
    let same = |a: &str, b: &str| a.replace('\\', "/").trim_end_matches('/').eq_ignore_ascii_case(b.replace('\\', "/").trim_end_matches('/'));
    let name = directory.replace('\\', "/").trim_end_matches('/').rsplit('/').next().unwrap_or(directory).to_string();
    let mut manager = state.manager.lock().expect("lock poisoned");
    if manager.apps.iter().any(|a| same(&a.directory, directory)) {
        return;
    }
    manager.apps.push(AppEntry { name, directory: directory.to_string(), created_at: versions::chrono_now_iso() });
    if let Err(e) = manager.save() {
        log::info(format!("relay_apply: the new app could not be added to the list: {e}"));
    }
}

/// "Use a browser AI": put the app's model back from the backup `relay_apply` made. The CLI refuses when the
/// app changed since the answer was applied, unless `force` -- so a later edit is never lost silently.
#[tauri::command]
async fn relay_undo(state: State<'_, AppState>, code: String, force: Option<bool>) -> Result<Value, String> {
    let (python, cli, java_home) = relay_cli(&state).await?;
    let result = npdev::run_relay_undo(&python, &cli, java_home.as_deref(), &code, force.unwrap_or(false)).await?;
    log::info(format!("relay_undo code={code} {}", relay_verdict(&result)));
    Ok(result)
}

/// "Use a browser AI": give up on a session (it stops being listed as open). Nothing is left watching the
/// clipboard for a conversation that no longer exists.
#[tauri::command]
async fn relay_abort(app: tauri::AppHandle, state: State<'_, AppState>, code: String) -> Result<Value, String> {
    relay_watch::stop_for(&app, &code);
    let (python, cli, java_home) = relay_cli(&state).await?;
    let result = npdev::run_relay_abort(&python, &cli, java_home.as_deref(), &code).await?;
    log::info(format!("relay_abort code={code} {}", relay_verdict(&result)));
    Ok(result)
}

/// "Use a browser AI", step 3: start looking at the clipboard and the Downloads folder for this conversation's
/// answer (relay_watch.rs). Does nothing -- and says so -- when the Settings switch is off, so the page draws no
/// notice for a watcher that is not there. Otherwise says which places are read (`sources`).
#[tauri::command]
fn relay_watch_start(app: tauri::AppHandle, state: State<'_, AppState>, code: String) -> Result<Value, String> {
    npdev::check_relay_code(&code)?;
    let allowed = state.manager.lock().expect("lock poisoned").relay_watch_on();
    if !allowed {
        relay_watch::stop(&app);
        return Ok(serde_json::json!({ "ok": true, "watching": false, "reason": "disabled" }));
    }
    Ok(relay_watch::start(&app, &code))
}

/// "Use a browser AI": stop looking at the clipboard and the Downloads folder (Stop pressed, the step left, the
/// conversation over).
#[tauri::command]
fn relay_watch_stop(app: tauri::AppHandle) -> Value {
    relay_watch::stop(&app)
}

/// The Settings switch "Watch the clipboard and the Downloads folder while waiting for an AI": on unless the
/// person turned it off.
#[tauri::command]
fn relay_watch_setting_get(state: State<'_, AppState>) -> bool {
    state.manager.lock().expect("lock poisoned").relay_watch_on()
}

/// Turn the switch on or off. Off also stops a watcher that is running right now.
#[tauri::command]
fn relay_watch_setting_set(app: tauri::AppHandle, state: State<'_, AppState>, enabled: bool) -> Result<bool, String> {
    {
        let mut manager = state.manager.lock().expect("lock poisoned");
        manager.relay_watch_clipboard = Some(enabled);
        manager.save().map_err(|e| format!("could not save the setting: {e}"))?;
    }
    if !enabled {
        relay_watch::stop(&app);
    }
    log::info(format!("relay_watch_setting enabled={enabled}"));
    Ok(enabled)
}

/// Overwrites the app's real model.json with a VALIDATED candidate, after an explicit typed
/// confirmation -- the same discipline `db reset` already applies (`main.rs` B5 note above):
/// a button is far easier to press than a token is to type, so the window has to be at least as
/// careful as the terminal. The previous model.json is preserved as a timestamped backup rather
/// than deleted; this command never runs the validator itself, callers are expected to have
/// called `validate_prompter_model` first and refused to reach here on a failed result.
#[tauri::command]
fn apply_prompter_model(app_dir: String, candidate: Value, confirm: String) -> Result<Value, String> {
    apply_validated_model(app_dir, candidate, confirm)
}

/// The body of `apply_prompter_model` above, pulled out (not `#[tauri::command]` itself -- adding
/// `pub(crate)` directly to a `#[tauri::command]` fn collides with the macro's own generated item
/// names) so `ai_loop::run` (Session 2, NPDEV_MEGA_ROADMAP.md) calls the exact same write-plus-backup
/// rather than a second copy.
pub(crate) fn apply_validated_model(app_dir: String, candidate: Value, confirm: String) -> Result<Value, String> {
    const TOKEN: &str = "I_UNDERSTAND_THIS_OVERWRITES_MODEL_JSON";
    if confirm != TOKEN {
        return Err("confirmation token did not match -- model.json was not touched".to_string());
    }
    let model_dir = model_dir_of_app(&app_dir)
        .ok_or_else(|| "not an npdev-init app (no '<name>-app' suffix on its directory)".to_string())?;
    let model_path = PathBuf::from(&model_dir).join("model.json");
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_secs();
    let backup_path = PathBuf::from(&model_dir).join(format!("model.json.bak-{stamp}"));
    if model_path.exists() {
        std::fs::copy(&model_path, &backup_path)
            .map_err(|e| format!("could not back up the existing model.json: {e}"))?;
    }
    std::fs::write(
        &model_path,
        serde_json::to_string_pretty(&candidate).map_err(|e| e.to_string())?,
    )
    .map_err(|e| format!("could not write model.json: {e}"))?;
    log::info(format!("apply_prompter_model wrote {} (backup {})", model_path.display(), backup_path.display()));
    Ok(serde_json::json!({
        "modelPath": model_path.to_string_lossy(),
        "backupPath": backup_path.to_string_lossy(),
    }))
}

/// Regenerates a FinalApp's generated source from its (just-applied) model.json. Wraps
/// `npdev::run_generate_app`, which until now only `--selftest` called (`selftest.rs`) -- the
/// generation step itself already preserves `data`/`logs`/`secrets` (FinalAppAssembler), so this
/// is a thin pipe, not new business logic. Callers pair this with `run_ops_script(..,
/// "build-finalapp", ..)` to actually compile it -- that ops script only builds the ALREADY
/// generated source, it does not regenerate.
#[tauri::command]
async fn generate_app_from_model(state: State<'_, AppState>, app_dir: String) -> Result<(), String> {
    runs::recorded("generate-app", async move {
        let model_dir = model_dir_of_app(&app_dir)
            .ok_or_else(|| "not an npdev-init app (no '<name>-app' suffix on its directory)".to_string())?;
        let model = format!("{model_dir}/model.json");
        let config = format!("{model_dir}/config.json");
        let python = resolve_python_exe(&state).await?;
        let cli = resolve_npdev_cli(&state)?;
        let java_home = resolve_java_home(&state);
        npdev::run_generate_app(&python, &cli, java_home.as_deref(), &model, &config, &app_dir).await
    })
    .await
}

/// Wave 6.1 (the Manager's Studio tab, NPDEV_FEATURE_PLAN_2026-09-24.md): the "Apply" button's live
/// half. The caller (studio.js) has ALREADY validated the candidate (`validate_prompter_model`) and
/// written it to disk (`apply_prompter_model`, which returns `modelPath`/`backupPath`) before this is
/// called -- this command classifies the two on-disk files and pushes the change into the RUNNING app
/// if it can go live, never writing the model itself. Thin pipe to `npdev monitor studio-apply`;
/// the classify/route/push decision lives entirely in that CLI command, not here.
#[tauri::command]
async fn studio_apply_live(
    state: State<'_, AppState>,
    app_dir: String,
    model_path: String,
    baseline_path: String,
) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_monitor_studio_apply(&python, &cli, java_home.as_deref(), &app_dir, &model_path, &baseline_path).await
}

/// Wave 7.2: the Evals tab's scenario table, before any run has happened yet.
#[tauri::command]
async fn eval_list_scenarios(state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_eval_list_scenarios(&python, &cli, java_home.as_deref()).await
}

/// The run selector's own list, newest first (the CLI already sorts it that way).
#[tauri::command]
async fn eval_list_runs(state: State<'_, AppState>) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_eval_list_runs(&python, &cli, java_home.as_deref()).await
}

/// `scenario: None` is "Run all"; `scenario: Some(id)` is "Run one". The caller generates `run_id`
/// itself (a timestamp) before calling this, so it can start polling `eval_list_runs` for that same
/// id immediately rather than waiting for this (potentially minutes-long) call to resolve.
#[tauri::command]
async fn eval_run(state: State<'_, AppState>, scenario: Option<String>, run_id: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_eval_run(&python, &cli, java_home.as_deref(), scenario.as_deref(), &run_id).await
}

#[tauri::command]
async fn eval_stop(state: State<'_, AppState>, run_id: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_eval_stop(&python, &cli, java_home.as_deref(), &run_id).await
}

#[tauri::command]
async fn eval_compare(state: State<'_, AppState>, run_a: String, run_b: String) -> Result<Value, String> {
    let java_home = resolve_java_home(&state);
    let python = resolve_python_exe(&state).await?;
    let cli = resolve_npdev_cli(&state)?;
    npdev::run_eval_compare(&python, &cli, java_home.as_deref(), &run_a, &run_b).await
}

const STUDIO_HISTORY_LIMIT: usize = 20;

fn studio_history_dir(app_dir: &str) -> PathBuf {
    PathBuf::from(app_dir).join("data").join("studio-history")
}

/// Wave 6.2 (Undo): copies the just-written model.json into the app's own
/// `data/studio-history/<millis>.json` -- `data/` is one of the exactly three directories
/// (`data`/`logs`/`secrets`) regeneration spares (FinalAppAssembler.PRESERVED_APP_DIRECTORIES), so
/// this history survives a regenerate+rebuild the same way the app's own database does. Prunes down
/// to the newest `STUDIO_HISTORY_LIMIT` entries -- a rolling window, not an unbounded audit log.
/// Best-effort by design (the caller swallows any error): history is a convenience for Undo, never a
/// gate on an apply that already succeeded.
#[tauri::command]
fn studio_history_record(app_dir: String, model_path: String) -> Result<(), String> {
    let dir = studio_history_dir(&app_dir);
    std::fs::create_dir_all(&dir).map_err(|e| format!("could not create {}: {e}", dir.display()))?;
    let millis = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_millis();
    let dest = dir.join(format!("{millis}.json"));
    std::fs::copy(&model_path, &dest)
        .map_err(|e| format!("could not copy {model_path} to {}: {e}", dest.display()))?;

    let mut entries: Vec<PathBuf> = std::fs::read_dir(&dir)
        .map_err(|e| e.to_string())?
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("json"))
        .collect();
    entries.sort();
    if entries.len() > STUDIO_HISTORY_LIMIT {
        for stale in &entries[..entries.len() - STUDIO_HISTORY_LIMIT] {
            let _ = std::fs::remove_file(stale);
        }
    }
    Ok(())
}

/// Wave 6.2: every recorded version, NEWEST FIRST (index 0 is always the just-applied current
/// state, since `studio_history_record` runs right after every successful write) -- the Studio's
/// Undo offers index 1, the one before it.
#[tauri::command]
fn studio_history_list(app_dir: String) -> Result<Value, String> {
    let dir = studio_history_dir(&app_dir);
    let mut entries: Vec<PathBuf> = match std::fs::read_dir(&dir) {
        Ok(read) => read.filter_map(|entry| entry.ok().map(|e| e.path())).collect(),
        Err(_) => Vec::new(),
    };
    entries.retain(|p| p.extension().and_then(|e| e.to_str()) == Some("json"));
    entries.sort();
    entries.reverse();
    let items: Vec<Value> = entries.iter().map(|path| {
        let file_name = path.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
        let millis: i64 = file_name.trim_end_matches(".json").parse().unwrap_or(0);
        serde_json::json!({"fileName": file_name, "appliedAtMillis": millis})
    }).collect();
    Ok(Value::Array(items))
}

/// Wave 6.2: reads one recorded version back as a candidate model for Undo to re-apply through the
/// SAME `validate_prompter_model` -> `apply_prompter_model` -> `studio_apply_live` path a fresh edit
/// uses (the plan's own "Undo re-applies the previous one through the same path"). Rejects a
/// `fileName` that is not a bare `<digits>.json` (no path traversal via `..`/separators -- this
/// reads whatever file the Studio itself named, never an operator-typed path).
#[tauri::command]
fn studio_history_read(app_dir: String, file_name: String) -> Result<Value, String> {
    if !file_name.ends_with(".json")
        || !file_name.trim_end_matches(".json").chars().all(|c| c.is_ascii_digit())
    {
        return Err(format!("not a valid history file name: {file_name}"));
    }
    let path = studio_history_dir(&app_dir).join(&file_name);
    let text = std::fs::read_to_string(&path).map_err(|e| format!("could not read {}: {e}", path.display()))?;
    serde_json::from_str(&text).map_err(|e| format!("{} is not valid JSON: {e}", path.display()))
}

/// Session 2 (NPDEV_MEGA_ROADMAP.md): the AI loop's Tauri entry point. Resolves everything the
/// engine needs ONCE (profile, python/cli/java_home) and registers a cancel flag BEFORE spawning --
/// same "already running" guard shape as `run_ops_script` -- then hands off to `ai_loop::run` in a
/// background task, exactly like `run_ops_script_streaming` already does, because a multi-iteration
/// run (build + boot per iteration) can genuinely take minutes and the command must return so the
/// window stays responsive. Progress streams as `ai-loop-event`; nothing here re-implements the
/// engine's own stage sequence.
#[tauri::command]
async fn run_ai_loop(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    app_dir: String,
    app_name: String,
    profile_id: String,
    model: String,
    effort: Option<String>,
    initial_prompt: String,
    plain_ask: String,
    max_iterations: u32,
) -> Result<(), String> {
    {
        let cancels = state.ai_loop_cancel.lock().expect("lock poisoned");
        if cancels.contains_key(&app_dir) {
            return Err("an AI loop is already running for this app -- stop it first".to_string());
        }
    }
    let profile = {
        let manager = state.manager.lock().expect("lock poisoned");
        manager.prompter_profiles.iter().find(|p| p.id == profile_id).cloned()
    };
    let profile = profile.ok_or_else(|| {
        format!("no provider profile '{profile_id}' -- open the Prompter tab's provider editor and add one")
    })?;
    let model = if model.trim().is_empty() { profile.default_model.clone().unwrap_or_default() } else { model };
    let python_exe = resolve_python_exe(&state).await?;
    let npdev_cli = resolve_npdev_cli(&state)?;
    let java_home = resolve_java_home(&state);
    let max_iterations = max_iterations.clamp(1, 10);

    let cancel = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    state.ai_loop_cancel.lock().expect("lock poisoned").insert(app_dir.clone(), cancel.clone());
    log::info(format!("run_ai_loop app_dir={app_dir} profile={} model={model} max_iterations={max_iterations}", profile.id));

    let config = ai_loop::LoopConfig {
        app_dir: app_dir.clone(),
        app_name,
        profile,
        model,
        effort,
        initial_prompt,
        plain_ask,
        max_iterations,
        python_exe,
        npdev_cli,
        java_home,
    };
    let app2 = app.clone();
    tauri::async_runtime::spawn(async move {
        let app_state = app2.state::<AppState>();
        ai_loop::run(&app_state, config, ai_loop::Sink::Tauri(app2.clone()), cancel).await;
        app_state.ai_loop_cancel.lock().expect("lock poisoned").remove(&app_dir);
    });
    Ok(())
}

/// The AI loop's Stop button: a cooperative cancel, not a process kill -- see `AppState.ai_loop_cancel`.
#[tauri::command]
fn stop_ai_loop(state: State<'_, AppState>, app_dir: String) -> Result<(), String> {
    let cancels = state.ai_loop_cancel.lock().expect("lock poisoned");
    match cancels.get(&app_dir) {
        Some(flag) => {
            flag.store(true, std::sync::atomic::Ordering::Relaxed);
            Ok(())
        }
        None => Err("no AI loop is running for this app".to_string()),
    }
}

/// M10: the engine-mismatch offer's "Rebuild on <engine>" button. Only works for an app this
/// Manager can regenerate (`model_dir_of_app` -- an `npdev init`-created `<name>-app`, same
/// requirement `generate_app_from_model` already has); an app generated some other way gets a
/// named refusal rather than a silent no-op. Sets `database.databaseName` explicitly alongside the
/// engine -- the plan's own warning: without it, every regeneration expects a fresh database and
/// `DatabaseIdentityStartupValidator` refuses to start.
#[tauri::command]
async fn rebuild_app_engine(
    state: State<'_, AppState>,
    app_dir: String,
    engine: String,
) -> Result<(), String> {
    let model_dir = model_dir_of_app(&app_dir)
        .ok_or_else(|| "this app was not created from a model this Manager can regenerate -- \
            edit db.definition.json by hand and regenerate it yourself, then run `npdev host deploy` \
            again".to_string())?;
    let db_def_path = PathBuf::from(&model_dir).join("db.definition.json");
    let text = std::fs::read_to_string(&db_def_path)
        .map_err(|e| format!("could not read {}: {e}", db_def_path.display()))?;
    let mut db_def: Value = serde_json::from_str(&text)
        .map_err(|e| format!("{} is not valid JSON: {e}", db_def_path.display()))?;
    let database = db_def.get_mut("database").ok_or_else(|| {
        format!("{} has no \"database\" object -- refusing to guess its shape", db_def_path.display())
    })?;
    database["engine"] = Value::String(engine);
    if database.get("databaseName").and_then(|v| v.as_str()).unwrap_or("").is_empty() {
        let name = PathBuf::from(&model_dir).file_name().map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "npdev_app".to_string());
        database["databaseName"] = Value::String(name.replace('-', "_"));
    }
    let written = serde_json::to_string_pretty(&db_def).map_err(|e| e.to_string())?;
    std::fs::write(&db_def_path, written + "\n")
        .map_err(|e| format!("could not write {}: {e}", db_def_path.display()))?;

    generate_app_from_model(state, app_dir).await
}

async fn prompter_generate_via_command(
    profile: &state::PrompterProfile,
    prompt: &str,
) -> Result<Value, String> {
    if profile.command.is_empty() {
        return Err("this profile has no command configured".to_string());
    }
    // The prompt goes through a FILE, never a command line -- same rule as assistant_generate's
    // payload file. An argv lands in shell history and in every process listing on the machine.
    let dir = std::env::temp_dir().join("npdev-manager-prompter");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let prompt_path = dir.join(format!("prompt-{}.txt", versions::chrono_now_iso().replace(':', "-")));
    std::fs::write(&prompt_path, prompt).map_err(|e| e.to_string())?;
    let prompt_arg = prompt_path.to_string_lossy().to_string();

    let argv: Vec<String> = profile
        .command
        .iter()
        .map(|part| part.replace("{prompt_file}", &prompt_arg))
        .collect();

    let output = tokio::process::Command::new(&argv[0])
        .args(&argv[1..])
        .output()
        .await
        .map_err(|e| format!("could not run {}: {e}", argv[0]));
    let _ = std::fs::remove_file(&prompt_path);
    let output = output?;

    if !output.status.success() {
        return Err(format!(
            "{} exited with {}: {}",
            argv[0],
            output.status,
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    let text = String::from_utf8_lossy(&output.stdout).to_string();
    Ok(serde_json::json!({ "ok": true, "text": text, "raw": text }))
}

async fn prompter_generate_via_http(
    profile: &state::PrompterProfile,
    model: &str,
    effort: Option<&str>,
    prompt: &str,
) -> Result<Value, String> {
    let endpoint = profile
        .endpoint
        .as_deref()
        .filter(|e| !e.is_empty())
        .ok_or_else(|| "this profile has no endpoint configured".to_string())?;
    let key = secrets::get_secret(&profile.id)?.filter(|k| !k.is_empty()).ok_or_else(|| {
        format!(
            "no credential stored for profile '{}' -- open the Prompter tab's provider editor and \
             paste the key",
            profile.id
        )
    })?;

    let auth_style = profile.auth_style.as_deref().unwrap_or("bearer");
    let client = reqwest::Client::new();
    let mut request = client.post(endpoint).header("content-type", "application/json");

    let body = match auth_style {
        "x-api-key" => {
            // Anthropic's Messages API. `max_tokens` is required and caps thinking PLUS response
            // text together, not just the answer. Depth is `output_config.effort`; the old
            // `thinking.budget_tokens` is removed on current models and returns 400.
            request = request.header("x-api-key", &key).header("anthropic-version", "2023-06-01");
            let mut payload = serde_json::json!({
                "model": model,
                "max_tokens": 16000,
                "messages": [{"role": "user", "content": prompt}],
            });
            if let Some(level) = effort.filter(|e| !e.is_empty()) {
                payload["output_config"] = serde_json::json!({ "effort": level });
            }
            payload
        }
        "x-goog-api-key" => {
            request = request.header("x-goog-api-key", &key);
            serde_json::json!({ "contents": [{"parts": [{"text": prompt}]}] })
        }
        // "bearer" and anything unrecognised: the OpenAI-compatible shape, which is what most
        // endpoints a user pastes will speak. `effort` is deliberately NOT sent -- reasoning_effort
        // exists only on reasoning models and is a 400 on the chat models, so sending it would turn
        // a UI affordance into a failed request.
        _ => {
            request = request.bearer_auth(&key);
            serde_json::json!({ "model": model, "messages": [{"role": "user", "content": prompt}] })
        }
    };

    let response = request
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("provider request failed: {e}"))?;
    let status = response.status();
    let text = response.text().await.map_err(|e| format!("could not read the provider reply: {e}"))?;
    if !status.is_success() {
        // The provider's own error body, not a paraphrase -- "model not found" and "insufficient
        // credit" need to reach the person who can act on them.
        return Err(format!("provider returned HTTP {}: {}", status.as_u16(), truncate_for_ui(&text)));
    }

    let parsed: Value = serde_json::from_str(&text)
        .map_err(|e| format!("provider reply was not JSON ({e}): {}", truncate_for_ui(&text)))?;
    Ok(serde_json::json!({
        "ok": true,
        "text": extract_provider_text(auth_style, &parsed).unwrap_or_default(),
        "raw": truncate_for_ui(&text),
    }))
}

/// Pull the assistant text out of whichever response shape the provider used.
///
/// The Anthropic branch walks to the first `type: "text"` block rather than reading `content[0]`:
/// with thinking on -- the default on current models -- block 0 is a thinking block with no `text`
/// field at all, so indexing it returns nothing on a perfectly good response.
fn extract_provider_text(auth_style: &str, parsed: &Value) -> Option<String> {
    match auth_style {
        "x-api-key" => parsed
            .get("content")?
            .as_array()?
            .iter()
            .find(|block| block.get("type").and_then(|t| t.as_str()) == Some("text"))
            .and_then(|block| block.get("text"))
            .and_then(|t| t.as_str())
            .map(|s| s.to_string()),
        "x-goog-api-key" => parsed
            .get("candidates")?
            .get(0)?
            .get("content")?
            .get("parts")?
            .get(0)?
            .get("text")?
            .as_str()
            .map(|s| s.to_string()),
        _ => parsed
            .get("choices")?
            .get(0)?
            .get("message")?
            .get("content")?
            .as_str()
            .map(|s| s.to_string()),
    }
}

/// Matches the CLI's cap on echoing a provider body back to a caller.
fn truncate_for_ui(raw: &str) -> String {
    const MAX: usize = 20_000;
    if raw.chars().count() <= MAX {
        return raw.to_string();
    }
    let head: String = raw.chars().take(MAX).collect();
    format!("{head}... (truncated)")
}

/// The account the legacy `AssistantConfig.api_key` is migrated to.
const LEGACY_ASSISTANT_PROFILE_ID: &str = "legacy-assistant";

/// Move a plaintext key out of `manager.json` and into the OS credential store, once, at startup.
///
/// `AssistantConfig.api_key` has been stored in plaintext since E2. That file is rewritten on every
/// settings change and lives in the user's profile, so the key is one "paste your manager.json"
/// support request away from being shared. This is a one-way move: read it, store it, blank the
/// field, save.
///
/// **A failed keyring write leaves the key exactly where it was.** On a box with no usable
/// credential store the alternative -- blanking the field anyway -- would destroy a working
/// configuration to satisfy a hardening step, which is a worse outcome than the thing being fixed.
/// The migration simply retries on the next start.
fn migrate_legacy_assistant_key() {
    let mut manager = state::ManagerState::load();
    let legacy_key = manager
        .assistant
        .as_ref()
        .and_then(|a| a.api_key.clone())
        .filter(|k| !k.is_empty());
    let Some(key) = legacy_key else { return };

    match secrets::set_secret(LEGACY_ASSISTANT_PROFILE_ID, &key) {
        Ok(()) => {
            if let Some(assistant) = manager.assistant.as_mut() {
                assistant.api_key = None;
            }
            match manager.save() {
                // Names the store and the account, never the value.
                Ok(()) => log::info(
                    "migrated the assistant API key out of manager.json into the OS credential \
                     store (account prompter/legacy-assistant)",
                ),
                // The credential is now in BOTH places. Not a leak beyond the status quo, and the
                // next successful save completes the move.
                Err(e) => log::info(format!(
                    "stored the assistant API key in the OS credential store but could not rewrite \
                     manager.json ({e}); it still holds the old copy and will be retried on the next start"
                )),
            }
        }
        Err(e) => log::info(format!(
            "leaving the assistant API key in manager.json: the OS credential store is unavailable ({e})"
        )),
    }
}

/// The headless body of `--ai-loop`: `--app-dir <d> --profile <id> --ask <text> [--model <m>]
/// [--effort low|medium|high] [--max-iterations N]`. Resolves python/cli/JAVA_HOME the same way
/// every Tauri command does (`resolve_python_exe`/`resolve_npdev_cli`/`resolve_java_home`), composes
/// the first prompt itself (`ai_loop::compose_initial_prompt`, reading the app's model.json the same
/// way `prompter_app_context` does) since there is no frontend here to have already built one, then
/// calls `ai_loop::run` directly -- the identical function the UI's `run_ai_loop` command spawns.
async fn run_ai_loop_cli() -> i32 {
    let mut app_dir: Option<String> = None;
    let mut profile_id: Option<String> = None;
    let mut ask: Option<String> = None;
    let mut model = String::new();
    let mut effort: Option<String> = None;
    let mut max_iterations: u32 = 5;

    let args: Vec<String> = std::env::args().collect();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--app-dir" => { app_dir = args.get(i + 1).cloned(); i += 1; }
            "--profile" => { profile_id = args.get(i + 1).cloned(); i += 1; }
            "--ask" => { ask = args.get(i + 1).cloned(); i += 1; }
            "--model" => { model = args.get(i + 1).cloned().unwrap_or_default(); i += 1; }
            "--effort" => { effort = args.get(i + 1).cloned(); i += 1; }
            "--max-iterations" => {
                max_iterations = args.get(i + 1).and_then(|v| v.parse().ok()).unwrap_or(5);
                i += 1;
            }
            _ => {}
        }
        i += 1;
    }
    let (Some(app_dir), Some(profile_id), Some(ask)) = (app_dir, profile_id, ask) else {
        eprintln!("--ai-loop requires --app-dir <dir> --profile <id> --ask <text>");
        return 2;
    };
    let max_iterations = max_iterations.clamp(1, 10);

    state::ensure_dirs().expect("could not create the Manager's home directories");
    let app_state = AppState::new();
    let profile = {
        let manager = app_state.manager.lock().expect("lock poisoned");
        manager.prompter_profiles.iter().find(|p| p.id == profile_id).cloned()
    };
    let Some(profile) = profile else {
        eprintln!("no provider profile '{profile_id}' -- configure one in the Manager's Prompter tab first");
        return 1;
    };
    let model = if model.trim().is_empty() { profile.default_model.clone().unwrap_or_default() } else { model };

    let python_exe = match resolve_python_exe(&app_state).await {
        Ok(p) => p,
        Err(e) => { eprintln!("{e}"); return 1; }
    };
    let npdev_cli = match resolve_npdev_cli(&app_state) {
        Ok(p) => p,
        Err(e) => { eprintln!("{e}"); return 1; }
    };
    let java_home = resolve_java_home(&app_state);

    let model_json: Option<Value> = model_dir_of_app(&app_dir)
        .and_then(|d| std::fs::read_to_string(PathBuf::from(&d).join("model.json")).ok())
        .and_then(|text| serde_json::from_str::<Value>(&text).ok());
    let app_name = model_json
        .as_ref()
        .and_then(|m| m.get("namespace"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let initial_prompt = ai_loop::compose_initial_prompt(&app_name, &ask, model_json.as_ref());

    let config = ai_loop::LoopConfig {
        app_dir,
        app_name,
        profile,
        model,
        effort,
        initial_prompt,
        plain_ask: ask,
        max_iterations,
        python_exe,
        npdev_cli,
        java_home,
    };
    let cancel = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    ai_loop::run(&app_state, config, ai_loop::Sink::Stdout, cancel).await;
    0
}

/// S17b: parses the three positional args after a `--*-secret-env` flag (`<scope> <env> <profile
/// id>`) and runs `action` with them, mapping arg-count failures to the SAME exit-2 "bad usage"
/// contract the unscoped flags use. The action returns the process exit code.
fn env_secret_triplet(flag_pos: usize, action: impl FnOnce(&str, &str, &str) -> i32) -> i32 {
    let args: Vec<String> = std::env::args().collect();
    let flag = &args[flag_pos];
    let get = |i: usize| args.get(flag_pos + i + 1).map(|s| s.as_str());
    match (get(0), get(1), get(2)) {
        (Some(scope), Some(env), Some(id)) => {
            if scope.is_empty() || env.is_empty() || id.is_empty() {
                eprintln!("{flag} requires a scope, an environment and a profile id: {flag} <scope> <env> <profile_id>");
                2
            } else {
                action(scope, env, id)
            }
        }
        _ => {
            eprintln!("{flag} requires a scope, an environment and a profile id: {flag} <scope> <env> <profile_id>");
            2
        }
    }
}

/// S17c: reads one AI-loop transcript (a `.jsonl` of structured events) into a Vec<Value>, skipping
/// malformed lines rather than failing the whole read -- a truncated run (killed mid-write) must
/// still replay as far as it got. Caps at 10_000 events so a runaway ops-line stream cannot balloon
/// a replay call.
fn read_ai_loop_transcript(path: &std::path::Path) -> Result<Vec<Value>, String> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("could not read loop transcript {}: {e}", path.display()))?;
    let mut events = Vec::new();
    for line in text.lines() {
        if let Ok(value) = serde_json::from_str::<Value>(line) {
            events.push(value);
            if events.len() >= 10_000 {
                break;
            }
        }
    }
    Ok(events)
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

    // Headless counterpart to `save_prompter_profile`'s `api_key` param: lets a prompter
    // credential be provisioned without opening the GUI (CI, this CLI, a fresh machine). Reads the
    // key from stdin rather than argv, the same "never put a secret in a command line" rule
    // `--api-key` vs. `NPDEV_AI_API_KEY` already documents below -- argv is visible to any other
    // process on the box via the process list; stdin is not.
    if let Some(pos) = std::env::args().position(|a| a == "--set-secret") {
        let profile_id = std::env::args().nth(pos + 1);
        std::process::exit(match profile_id {
            Some(profile_id) => {
                let mut key = String::new();
                match std::io::stdin().read_line(&mut key) {
                    Ok(_) => {
                        let key = key.trim_end_matches(['\r', '\n']);
                        if key.is_empty() {
                            eprintln!("--set-secret {profile_id}: no key read from stdin");
                            2
                        } else {
                            match secrets::set_secret(&profile_id, key) {
                                Ok(()) => {
                                    println!("credential stored for profile '{profile_id}'");
                                    0
                                }
                                Err(e) => {
                                    eprintln!("--set-secret {profile_id}: {e}");
                                    1
                                }
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("--set-secret {profile_id}: could not read stdin: {e}");
                        1
                    }
                }
            }
            None => {
                eprintln!("--set-secret requires a profile id: --set-secret <profile_id> (key on stdin)");
                2
            }
        });
    }

    // Headless read/remove twins of --set-secret (REG-212): the emitted app launchers materialize
    // secrets/*.env from the OS credential store at boot by calling --get-secret <profile>, and CI
    // or an operator can delete an entry without the GUI. Security posture is stated here, next to
    // the code: --get-secret puts a stored value on STDOUT, so it opens a read window the
    // "never returns one to the UI" rule deliberately kept closed -- justified because the same
    // user who stores the key is the one the launcher runs as, and the app must receive the value
    // to start. The value still never crosses a command line (stdout, not argv), missing entries
    // and store failures exit nonzero with the message on stderr, and an empty value is never
    // printed as success.
    if let Some(pos) = std::env::args().position(|a| a == "--get-secret") {
        let profile_id = std::env::args().nth(pos + 1);
        std::process::exit(match profile_id {
            Some(profile_id) => match secrets::get_secret(&profile_id) {
                Ok(Some(value)) => {
                    if value.is_empty() {
                        eprintln!("--get-secret {profile_id}: credential is empty");
                        1
                    } else {
                        println!("{value}");
                        0
                    }
                }
                Ok(None) => {
                    eprintln!("--get-secret {profile_id}: no credential stored for this profile");
                    1
                }
                Err(e) => {
                    eprintln!("--get-secret {profile_id}: {e}");
                    1
                }
            },
            None => {
                eprintln!("--get-secret requires a profile id: --get-secret <profile_id>");
                2
            }
        });
    }
    if let Some(pos) = std::env::args().position(|a| a == "--delete-secret") {
        let profile_id = std::env::args().nth(pos + 1);
        std::process::exit(match profile_id {
            Some(profile_id) => match secrets::delete_secret(&profile_id) {
                Ok(()) => {
                    println!("credential deleted for profile '{profile_id}'");
                    0
                }
                Err(e) => {
                    eprintln!("--delete-secret {profile_id}: {e}");
                    1
                }
            },
            None => {
                eprintln!("--delete-secret requires a profile id: --delete-secret <profile_id>");
                2
            }
        });
    }

    // S17b (NPDEV_MEGA_ROADMAP.md): headless twins of the three flags above, scoped to a
    // DB/deploy credential kind and an environment: --set-secret-env/--get-secret-env/
    // --delete-secret-env <scope> <env> <profile_id>. The same contracts hold verbatim -- key on
    // stdin for set (never argv), value on stdout only for get (the app launcher's read window),
    // empty value never printed as success, missing store entries exit nonzero -- with the account
    // key widened to "<scope>/<env>/<id>" so provisioning a dev/staging/prod credential from CI is
    // one command and cannot collide across environments.
    if let Some(pos) = std::env::args().position(|a| a == "--set-secret-env") {
        std::process::exit(env_secret_triplet(pos, |scope, env, id| {
            let mut key = String::new();
            match std::io::stdin().read_line(&mut key) {
                Ok(_) => {
                    let key = key.trim_end_matches(['\r', '\n']);
                    if key.is_empty() {
                        eprintln!("--set-secret-env {scope} {env} {id}: no key read from stdin");
                        2
                    } else {
                        match secrets::set_secret_scoped(scope, Some(env), id, key) {
                            Ok(()) => {
                                println!("credential stored for {scope}/{env}/{id}");
                                0
                            }
                            Err(e) => {
                                eprintln!("--set-secret-env {scope} {env} {id}: {e}");
                                1
                            }
                        }
                    }
                }
                Err(e) => {
                    eprintln!("--set-secret-env {scope} {env} {id}: could not read stdin: {e}");
                    1
                }
            }
        }));
    }
    if let Some(pos) = std::env::args().position(|a| a == "--get-secret-env") {
        std::process::exit(env_secret_triplet(pos, |scope, env, id| {
            match secrets::get_secret_scoped(scope, Some(env), id) {
                Ok(Some(value)) => {
                    if value.is_empty() {
                        eprintln!("--get-secret-env {scope} {env} {id}: credential is empty");
                        1
                    } else {
                        println!("{value}");
                        0
                    }
                }
                Ok(None) => {
                    eprintln!("--get-secret-env {scope} {env} {id}: no credential stored for this profile");
                    1
                }
                Err(e) => {
                    eprintln!("--get-secret-env {scope} {env} {id}: {e}");
                    1
                }
            }
        }));
    }
    if let Some(pos) = std::env::args().position(|a| a == "--delete-secret-env") {
        std::process::exit(env_secret_triplet(pos, |scope, env, id| {
            match secrets::delete_secret_scoped(scope, Some(env), id) {
                Ok(()) => {
                    println!("credential deleted for {scope}/{env}/{id}");
                    0
                }
                Err(e) => {
                    eprintln!("--delete-secret-env {scope} {env} {id}: {e}");
                    1
                }
            }
        }));
    }

    // S15 (NPDEV_MEGA_ROADMAP.md, Track B): the Impact explorer's data source -- the generated
    // app's provenance-index.json (S14), resolved through the SAME model_dir_of_app convention
    // the prompter uses (a generated app lives in the sibling `<model-dir>-app`). Returns the
    // index verbatim; the UI owns what to render. No decision lives here.
    #[tauri::command]
    async fn app_provenance_index(app_dir: String) -> Result<Value, String> {
        let mut candidates: Vec<PathBuf> = Vec::new();
        if let Some(model_dir) = model_dir_of_app(&app_dir) {
            let trimmed = model_dir.trim_end_matches(['/', '\\']);
            candidates.push(PathBuf::from(format!("{trimmed}-app/src/main/resources/npdev/provenance-index.json")));
            candidates.push(PathBuf::from(format!("{trimmed}/src/main/resources/npdev/provenance-index.json")));
        }
        // Direct app-folder probe as a last resort (an app dir may BE the generated root).
        candidates.push(PathBuf::from(&app_dir).join("src/main/resources/npdev/provenance-index.json"));
        for candidate in candidates {
            if let Ok(text) = std::fs::read_to_string(&candidate) {
                if let Ok(index) = serde_json::from_str::<Value>(&text) {
                    return Ok(index);
                }
            }
        }
        Err(format!(
            "No provenance index found for app at {app_dir} -- generate the app with a current NPDev generator first"
        ))
    }

    /// S16 (NPDEV_MEGA_ROADMAP.md, Track B): blast radius -- given the app's CURRENT model as the
    /// baseline and an edited/candidate model, run `npdev impact --app` so the caller sees which
    /// tables, generated classes, routes and components change or break BEFORE any build, with
    /// destructive changes flagged. Thin pipe: the decision of what is destructive lives in the
    /// CLI's join (and the generator's migration plan behind it), never here.
    #[tauri::command]
    async fn impact_blast_radius(
        state: State<'_, AppState>,
        app_dir: String,
        proposed_model: String,
    ) -> Result<Value, String> {
        let baseline_model = model_dir_of_app(&app_dir).map(|dir| {
            PathBuf::from(dir).join("model.json")
        }).filter(|p| p.is_file());
        let baseline = match baseline_model {
            Some(path) => path,
            None => return Err(format!(
                "Could not find the app's current model.json for {app_dir} -- \
the blast radius needs a baseline to diff against"
            )),
        };
        let proposed = PathBuf::from(&proposed_model);
        if !proposed.is_file() {
            return Err(format!("Proposed model not found: {proposed_model}"));
        }
        let java_home = resolve_java_home(&state);
        if npdev::fake_mode() {
            return npdev::run_impact_cli(
                &PathBuf::from("python"), &PathBuf::from("npdev_cli.py"),
                java_home.as_deref(), &baseline.to_string_lossy(), &proposed_model, Some(&app_dir),
            ).await;
        }
        let python = resolve_python_exe(&state).await?;
        let cli = resolve_npdev_cli(&state)?;
        npdev::run_impact_cli(&python, &cli, java_home.as_deref(),
                              &baseline.to_string_lossy(), &proposed_model, Some(&app_dir)).await
    }

    /// S17c (NPDEV_MEGA_ROADMAP.md): the Reflection Loop Console's data source -- the persisted
    /// transcripts of past AI-loop runs (`<app>/logs/ai-loop/run-*.jsonl`, written by ai_loop.rs).
    /// Lists the runs for an app: file name, event count, and the loop outcome decoded from the
    /// trailing `loop-done` event. No decision lives here; the UI renders the timeline.
    #[tauri::command]
    fn ai_loop_runs(app_dir: String) -> Result<Value, String> {
        let logs_dir = PathBuf::from(&app_dir).join("logs").join("ai-loop");
        if !logs_dir.is_dir() {
            return Ok(serde_json::json!({ "runs": [] }));
        }
        let mut runs: Vec<Value> = Vec::new();
        for entry in std::fs::read_dir(&logs_dir).map_err(|e| format!("could not list loop runs: {e}"))? {
            let entry = entry.map_err(|e| format!("could not list loop runs: {e}"))?;
            let path = entry.path();
            if path.extension().map(|e| e != "jsonl").unwrap_or(true) {
                continue;
            }
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
            let events = match read_ai_loop_transcript(&path) {
                Ok(events) => events,
                Err(_) => continue,
            };
            if events.is_empty() {
                continue;
            }
            runs.push(serde_json::json!({
                "runId": name.trim_end_matches(".jsonl"),
                "file": name,
                "eventCount": events.len(),
                "iterations": events.iter().filter(|e| e.get("kind").and_then(|k| k.as_str()) == Some("iteration-start")).count(),
                "outcome": events.iter().rev().find_map(|e| {
                    if e.get("kind").and_then(|k| k.as_str()) == Some("loop-done") {
                        Some(e.get("outcome").and_then(|o| o.as_str()).unwrap_or("?").to_string())
                    } else { None }
                }).unwrap_or_else(|| "?".to_string()),
                "summary": events.iter().rev().find_map(|e| {
                    if e.get("kind").and_then(|k| k.as_str()) == Some("loop-done") {
                        Some(e.get("summary").and_then(|s| s.as_str()).unwrap_or("").to_string())
                    } else { None }
                }).unwrap_or_default(),
            }));
        }
        runs.sort_by(|a, b| b.get("file").and_then(|f| f.as_str()).unwrap_or("").cmp(
            a.get("file").and_then(|f| f.as_str()).unwrap_or("")));
        Ok(serde_json::json!({ "runs": runs }))
    }

    /// S17c: reads one persisted transcript and returns its structured events in order. A hard cap
    /// guards a pathological run -- the window renders the timeline, it does not pull megabytes of
    /// ops-line noise into the UI.
    #[tauri::command]
    fn ai_loop_replay(app_dir: String, run_id: String) -> Result<Value, String> {
        let path = PathBuf::from(&app_dir).join("logs").join("ai-loop").join(format!("{run_id}.jsonl"));
        let events = read_ai_loop_transcript(&path)?;
        Ok(serde_json::json!({ "runId": run_id, "events": events }))
    }

    // Session 2 (NPDEV_MEGA_ROADMAP.md): "headless and scriptable first, UI second" -- this drives
    // the EXACT SAME `ai_loop::run` the `run_ai_loop` Tauri command below calls, mirroring
    // `--selftest`'s own precedent for a no-window entry point on this binary. Assumes the Manager's
    // own Ready screen has already run once on this machine (a python/npdev/JDK install is a
    // one-time setup step, not something a CI-facing flag should silently trigger).
    if std::env::args().any(|a| a == "--ai-loop") {
        let rt = tokio::runtime::Runtime::new().expect("failed to start a tokio runtime for --ai-loop");
        std::process::exit(rt.block_on(run_ai_loop_cli()));
    }

    state::ensure_dirs().expect("could not create the Manager's home directories");
    // D10 source 3. `logs_dir()` has been created on every startup since M0-M8 and NOTHING had ever
    // written into it -- while HANDOVER.md §5 told testers to send the .log files from there (and
    // named the wrong directory besides). This is the line that makes that instruction true.
    log::startup_banner();
    migrate_legacy_assistant_key();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(AppState::new())
        .invoke_handler(tauri::generate_handler![
            is_fake_mode,
            fake_doctor_scenarios,
            set_fake_doctor_scenario,
            check_doctor,
            support_report,
            last_failed_run,
            jobs_list,
            jobs_action,
            recent_operations,
            theme_list,
            theme_import,
            theme_delete,
            theme_catalogue_index,
            theme_catalogue_fetch,
            theme_share_url,
            appearance_get,
            appearance_set,
            test_connection,
            detect_databases,
            start_docker_desktop,
            install_docker_desktop,
            wsl_status,
            install_wsl,
            start_db_container,
            provision_database,
            start_own_db_servers,
            jdk_status,
            install_jdk,
            python_status,
            install_python,
            list_tags,
            install_npdev_version,
            run_setup,
            setup_status,
            list_apps,
            list_engines,
            create_app,
            db_operation,
            db_export,
            db_import,
            pick_db_transfer_folder,
            gpu_check_list,
            gpu_check_run,
            gpu_check_history,
            gpu_check_show,
            gpu_check_bundle,
            open_folder,
            read_json_file,
            open_url,
            start_dev,
            stop_dev,
            list_installed_versions,
            current_version,
            set_current_version,
            remove_installed_version,
            pick_local_repo_folder,
            set_local_repo_path,
            use_downloaded_npdev_version,
            local_repo_config,
            manager_home_path,
            manager_version,
            manager_version_description,
            manager_build_stamp,
            // The Monitor (Phase B)
            monitor_scan,
            monitor_probe,
            read_info_json,
            verification_panel,
            verification_run_item,
            pack_list,
            pack_why,
            pack_export_copy,
            fake_pack_scenarios,
            set_fake_pack_scenario,
            pick_inspect_folders,
            get_inspect_paths,
            set_inspect_paths,
            run_ops_script,
            start_app,
            stop_app,
            owned_processes,
            monitor_logs,
            export_logs,
            manager_log_path,
            // The Share screen (NPDEV_MANAGER_SHARE_IMPLEMENTATION_PLAN)
            host_status,
            host_check,
            host_explain,
            host_targets,
            host_plan,
            host_share,
            host_share_streaming,
            host_down,
            host_deploy,
            host_keys,
            providers_list,
            providers_add,
            providers_remove,
            providers_test,
            providers_export,
            fake_host_scenarios,
            set_fake_host_scenario,
            rebuild_app_engine,
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
            prompter_profiles,
            save_prompter_profile,
            delete_prompter_profile,
            migrate_prompter_profiles,
            ai_command,
            // S17b (Track B): DB/deploy secret profiles with per-environment isolation
            secret_profiles,
            save_secret_profile,
            delete_secret_profile,
            validate_prompter_model,
            relay_prompt,
            relay_ingest_text,
            relay_start,
            relay_ingest_file,
            relay_pick_answer_files,
            relay_generated_app_dir,
            relay_status,
            relay_list,
            relay_apply,
            relay_undo,
            relay_abort,
            relay_watch_start,
            relay_watch_stop,
            relay_watch_setting_get,
            relay_watch_setting_set,
            apply_prompter_model,
            generate_app_from_model,
            studio_apply_live,
            studio_history_record,
            studio_history_list,
            studio_history_read,
            eval_list_scenarios,
            eval_list_runs,
            eval_run,
            eval_stop,
            eval_compare,
            prompter_app_context,
            prompter_generate,
            run_ai_loop,
            stop_ai_loop,
            assistant_compose,
            assistant_generate,
            // S15/S16 (Track B): Impact/provenance explorer + blast radius
            app_provenance_index,
            impact_blast_radius,
            // S17c (Track B): Reflection Loop Console -- persisted AI-loop transcripts
            ai_loop_runs,
            ai_loop_replay,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the NPDev Manager");
}


#[cfg(test)]
mod prompter_migration_tests {
    use super::{slug, url_host};

    /// The ids `npdev ai` accepts: lower-case letters, digits, single dashes.
    #[test]
    fn slug_makes_an_npdev_ai_id() {
        assert_eq!(slug("prompter-Anthropic Work"), "prompter-anthropic-work");
        assert_eq!(slug("--a__b--"), "a-b");
        assert_eq!(slug(&"x".repeat(80)).len(), 60);
    }

    #[test]
    fn url_host_is_scheme_free_and_path_free() {
        assert_eq!(url_host("https://llm.example.com/v1/messages"), "llm.example.com");
        assert_eq!(url_host("http://127.0.0.1:1234/v1"), "127.0.0.1:1234");
        assert_eq!(url_host("api.example.com/x"), "api.example.com");
    }
}

#[cfg(test)]
mod app_target_dir_tests {
    use super::{app_dir_of_model, app_target_dir, model_dir_of_app};
    use std::path::Path;

    /// The two directions of the `<name>` / `<name>-app` convention agree, and a trailing slash does not matter.
    #[test]
    fn a_model_folder_and_its_generated_app_map_both_ways() {
        let app = app_dir_of_model("C:/apps/shop/shop/").expect("a normal folder maps");
        assert_eq!(app, "C:/apps/shop/shop-app");
        assert_eq!(model_dir_of_app(&app).as_deref(), Some("C:/apps/shop/shop"));
        assert_eq!(app_dir_of_model(""), None);
        assert_eq!(app_dir_of_model("/"), None);
        assert_eq!(app_dir_of_model("a\0b"), None);
    }

    /// BUILD-5: two apps created in one parent folder must not share `<model dir>/../ArtifactNP`.
    #[test]
    fn each_new_app_gets_its_own_artifact_parent() {
        let a = app_target_dir("C:/apps", "alpha");
        let b = app_target_dir("C:/apps", "beta");
        assert_eq!(a, Path::new("C:/apps").join("alpha").join("alpha"));
        assert_ne!(a.parent(), b.parent(), "ArtifactNP lands in the model dir's parent");
        // The `<model>-app` sibling convention every lookup relies on is unchanged.
        let built = format!("{}-app", a.to_string_lossy());
        assert_eq!(model_dir_of_app(&built).as_deref(), Some(a.to_string_lossy().as_ref()));
    }
}

#[cfg(test)]
mod monitor_css_scope_tests {
    /// Every Manager tab is a `<section class="screen">`, and the Monitor's CRT cards also call their
    /// inner div `.screen`. A bare `.screen` rule in monitor.css therefore paints EVERY tab as a
    /// CRT: near-black background, dark labels on it, and a scanline overlay drawn over the inputs
    /// (found 2026-10-28 driving the real window for RUN-37). The CRT rules must stay under `.crt`.
    #[test]
    fn monitor_css_never_styles_a_bare_screen() {
        let css = include_str!("../ui/monitor.css");
        let bare: Vec<&str> = css
            .lines()
            .map(str::trim_start)
            .filter(|l| l.starts_with(".screen") && (l.contains('{') || l.ends_with(',')))
            .collect();
        assert!(bare.is_empty(), "unscoped .screen rules in monitor.css: {bare:?}");
    }

    /// The dark tabs style themselves with tokens (`--ok`, `--text`, ...). Since THM-2 those come from
    /// tokens.css for EVERY screen; monitor.css's scoped block now only gives these tabs their CRT
    /// background and text colour. A tab missing from that block would render on the bare page
    /// background. Every `#screen-<id>` a ui/*.css styles with `var(--ok)` must be in the block.
    #[test]
    fn every_dark_tab_gets_the_palette() {
        let ui = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("ui");
        let monitor = std::fs::read_to_string(ui.join("monitor.css")).unwrap();
        let block_start = monitor.find("#screen-monitor, #screen-scrap").unwrap();
        let selector = &monitor[block_start..block_start + monitor[block_start..].find('{').unwrap()];
        let mut missing = Vec::new();
        for entry in std::fs::read_dir(&ui).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().and_then(|e| e.to_str()) != Some("css") {
                continue;
            }
            let css = std::fs::read_to_string(&path).unwrap();
            if !css.contains("var(--ok)") {
                continue;
            }
            for id in css.split("#screen-").skip(1) {
                let id: String = id.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '-').collect();
                // app.css styles light tabs too (#screen-run); only its dark-token rules matter, and
                // those target the Monitor, so the check is per stylesheet that owns one dark tab.
                if path.ends_with("app.css") || path.ends_with("hosting.css") {
                    continue;
                }
                if !selector.contains(&format!("#screen-{id}")) {
                    missing.push(format!("{}: #screen-{id}", path.file_name().unwrap().to_string_lossy()));
                }
            }
        }
        missing.sort();
        missing.dedup();
        assert!(missing.is_empty(), "dark tabs without the palette: {missing:?}");
    }

    /// NAV-1: the navigation is rendered from ui/navigation.json and there is no developer mode with
    /// the old flat strip (owner decision D5), so a `<section id="screen-X">` that no place lists is
    /// a screen nobody can open. Also: every string has English (the fallback), and every place has
    /// at least one screen.
    #[test]
    fn every_screen_is_reachable_from_the_navigation() {
        let ui = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("ui");
        let html = std::fs::read_to_string(ui.join("index.html")).unwrap();
        let nav: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(ui.join("navigation.json")).unwrap()).unwrap();
        let mut listed = std::collections::BTreeSet::new();
        let mut problems = Vec::new();
        for place in nav["places"].as_array().unwrap() {
            let id = place["id"].as_str().unwrap_or("?");
            for key in ["label", "summary"] {
                if place[key]["en"].as_str().is_none() {
                    problems.push(format!("place {id}: {key} has no English"));
                }
            }
            let screens = place["screens"].as_array().unwrap();
            if screens.is_empty() {
                problems.push(format!("place {id}: no screens"));
            }
            for s in screens {
                let name = s["screen"].as_str().unwrap();
                if s["label"]["en"].as_str().is_none() {
                    problems.push(format!("place {id} > {name}: label has no English"));
                }
                if let Some(g) = s["group"].as_str() {
                    if nav["groups"][g]["en"].as_str().is_none() {
                        problems.push(format!("place {id} > {name}: unknown group {g}"));
                    }
                }
                if !html.contains(&format!("id=\"screen-{name}\"")) {
                    problems.push(format!("place {id} lists screen {name}, which index.html does not have"));
                }
                listed.insert(name.to_string());
            }
        }
        for chunk in html.split("<section id=\"screen-").skip(1) {
            let name: String = chunk.chars().take_while(|c| *c != '"').collect();
            if !listed.contains(&name) {
                problems.push(format!("screen {name} is in index.html but no place in navigation.json lists it"));
            }
        }
        assert!(problems.is_empty(), "navigation problems: {problems:#?}");
    }
}

/// The Manager's scripts are plain `<script>` files sharing ONE global scope, so a function with the same
/// name in two files silently replaces the first -- no error anywhere. It happened twice: packs.js and
/// provenance.js both declared `refreshAppPicker` and `currentAppDir` (the Packs app picker was never
/// filled), and evals.js shadowed verification.js's `renderTable` (the Verification table drew nothing).
/// Found 2026-11-03 while building the app workspace, which has to drive every screen's app picker.
#[cfg(test)]
mod ui_script_global_tests {
    #[test]
    fn no_two_manager_scripts_declare_the_same_top_level_name() {
        let ui = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("ui");
        let decl = regex::Regex::new(r"(?m)^(?:async\s+)?function\s+([A-Za-z0-9_$]+)\s*\(|^(?:const|let|var)\s+([A-Za-z0-9_$]+)\s*=").unwrap();
        let mut owners: std::collections::BTreeMap<String, Vec<String>> = Default::default();
        for entry in std::fs::read_dir(&ui).unwrap() {
            let path = entry.unwrap().path();
            let name = path.file_name().unwrap().to_string_lossy().to_string();
            // theme-engine.js is an IIFE (it declares nothing global) and is a shared twin.
            if path.extension().and_then(|e| e.to_str()) != Some("js") || name == "theme-engine.js" {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            for cap in decl.captures_iter(&text) {
                let ident = cap.get(1).or_else(|| cap.get(2)).unwrap().as_str().to_string();
                owners.entry(ident).or_default().push(name.clone());
            }
        }
        let clashes: Vec<String> = owners
            .into_iter()
            .filter(|(_, files)| {
                let mut f = files.clone();
                f.sort();
                f.dedup();
                f.len() > 1
            })
            .map(|(ident, files)| format!("{ident}: {files:?}"))
            .collect();
        assert!(clashes.is_empty(), "top-level names declared in more than one ui/*.js file: {clashes:?}");
    }
}

#[cfg(test)]
mod jobs_fixture_tests {
    /// The Tasks board's stub fixture must be what `npdev jobs list --json` really answers: the same schema id and
    /// the fields jobs.js reads, with every status the board draws.
    #[test]
    fn the_jobs_fixture_has_every_status_and_the_fields_the_board_reads() {
        let v: serde_json::Value = serde_json::from_str(include_str!("../fixtures/jobs-list.json")).unwrap();
        assert_eq!(v["schema"], "npdev-jobs-list.v1");
        let jobs = v["jobs"].as_array().unwrap();
        let statuses: std::collections::BTreeSet<&str> = jobs.iter().map(|j| j["status"].as_str().unwrap()).collect();
        for want in ["running", "succeeded", "failed", "lost"] {
            assert!(statuses.contains(want), "fixture lacks a {want} job");
        }
        for j in jobs {
            for field in ["jobId", "title", "argv", "status", "startedAt", "launcher", "actions", "logPath"] {
                assert!(j.get(field).is_some(), "job {} lacks {field}", j["jobId"]);
            }
        }
    }
}
