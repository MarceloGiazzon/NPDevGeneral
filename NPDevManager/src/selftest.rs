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
    state::ensure_dirs().map_err(|e| ("0/5 create manager directories", e.to_string()))?;
    let app_state = state::AppState::new();

    resolve_jdk_step(&app_state).await?;
    let java_home = app_state.manager.lock().expect("lock poisoned").jdk_home.clone();

    let python_path = resolve_python_step(&app_state).await?;

    let cli_path = resolve_npdev_step(&app_state).await?;

    run_doctor_step(&python_path, &cli_path, java_home.as_deref()).await
}

/// [1/5]-[3/5] in the plan's sample output: resolve the Adoptium asset for this platform,
/// download it with checksum verification, then extract -- collapsed here because
/// `download_verify_extract` does all three as one atomic operation (a checksum mismatch never
/// leaves a partial extract behind for a later step to trip over).
async fn resolve_jdk_step(app_state: &state::AppState) -> Result<(), StepError> {
    if runtime::jdk_already_installed() {
        println!(
            "  [1/5] JDK already installed ................. ok  ({})",
            state::jdk_dir().display()
        );
        return Ok(());
    }
    let target = runtime::resolve_jdk17().await.map_err(|e| ("1/5 resolve JDK", e))?;
    println!("  [1/5] resolve JDK for this platform .......... ok  ({})", target.file_name);
    let dest = state::jdk_dir();
    runtime::download_verify_extract(&target, &dest, |_, _| {})
        .await
        .map_err(|e| ("2-3/5 download + extract JDK", e))?;
    println!("  [2/5] download + checksum ..................... ok");
    println!("  [3/5] extract to {} ......... ok", dest.display());
    let mut manager = app_state.manager.lock().expect("lock poisoned");
    manager.jdk_home = Some(dest.to_string_lossy().to_string());
    manager.save().map_err(|e| ("3/5 save jdk_home", e.to_string()))?;
    Ok(())
}

/// [4/5]: prefers a system Python (matches `resolve_python_exe`'s own order in `main.rs`) and only
/// downloads the pinned portable build when none is found -- exactly what should happen on a
/// container with no system Python at all.
async fn resolve_python_step(app_state: &state::AppState) -> Result<std::path::PathBuf, StepError> {
    let python_path = if let Some(system) = runtime::detect_system_python().await {
        println!("  [4/5] resolve Python (system) ................. ok  ({})", system.display());
        system
    } else if runtime::portable_python_already_installed() {
        let p = runtime::python_binary_in(&state::python_dir());
        println!("  [4/5] Python already installed ................. ok  ({})", p.display());
        p
    } else {
        let target = runtime::resolve_portable_python();
        let dest = state::python_dir();
        runtime::download_verify_extract(&target, &dest, |_, _| {})
            .await
            .map_err(|e| ("4/5 download + extract Python", e))?;
        let p = runtime::python_binary_in(&dest);
        println!("  [4/5] resolve/download Python .................. ok  ({})", p.display());
        p
    };
    let mut manager = app_state.manager.lock().expect("lock poisoned");
    manager.python_exe = Some(python_path.to_string_lossy().to_string());
    manager.save().map_err(|e| ("4/5 save python_exe", e.to_string()))?;
    Ok(python_path)
}

/// Part of [5/5]: an installed NPDev CLI is a precondition for `doctor`, not a separate numbered
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
    let tags = versions::list_tags(false).await.map_err(|e| ("5/5 list NPDev tags", e))?;
    let newest = tags
        .first()
        .ok_or_else(|| ("5/5 list NPDev tags", "the repository has no tags to install".to_string()))?;
    versions::install_version(app_state, &newest.name, |_, _| {})
        .await
        .map_err(|e| ("5/5 install NPDev version", e))?;
    Ok(npdev::npdev_cli_path(&state::versions_dir().join(&newest.name)))
}

/// [5/5]: the exact `doctor --json` call `check_doctor` makes, via the runtimes resolved above.
async fn run_doctor_step(python_path: &std::path::Path, cli_path: &std::path::Path, java_home: Option<&str>) -> Result<(), StepError> {
    let result = npdev::run_doctor(python_path, cli_path, java_home)
        .await
        .map_err(|e| ("5/5 npdev doctor", e))?;
    let checks = result.get("checks").and_then(|c| c.as_array()).cloned().unwrap_or_default();
    let total = checks.len();
    let passed = checks
        .iter()
        .filter(|c| c.get("status").and_then(|s| s.as_str()) == Some("pass"))
        .count();
    let ok = result.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    println!(
        "  [5/5] npdev doctor via private runtimes ....... {}  ({passed}/{total} checks pass)",
        if ok { "ok" } else { "FAIL" }
    );
    if ok {
        Ok(())
    } else {
        Err(("5/5 npdev doctor", format!("doctor reported failure ({passed}/{total} checks passed): {result}")))
    }
}
