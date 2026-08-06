//! Everything that shells out to the CLI (SPEC.md §3). **Standing rule: every Tauri command in
//! this file is a thin wrapper around `<python> <npdev_cli.py> <args>` -- if a screen needs logic
//! the CLI cannot already do standalone in a terminal, that is a CLI gap to report, not something
//! to reimplement here.**
//!
//! Stub mode (`NPDEV_MANAGER_FAKE=1`, Phase 2 M1) short-circuits every function in this module to
//! return a fixture captured from the real CLI instead of spawning anything -- the whole UI can be
//! built and every failure screen exercised with NPDev absent from the machine.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Mutex;

use serde_json::Value;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};

pub fn fake_mode() -> bool {
    std::env::var("NPDEV_MANAGER_FAKE")
        .map(|v| v == "1")
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------------------------
// Fixtures (embedded at compile time -- captured live from the real CLI, never hand-written; see
// each fixture file's own header comment for exactly how/when it was captured).
// ---------------------------------------------------------------------------------------------

const FIXTURE_DOCTOR_ALL_GREEN: &str = include_str!("../fixtures/doctor-all-green.json");
const FIXTURE_DOCTOR_MISSING_JAVA: &str = include_str!("../fixtures/doctor-missing-java.json");
const FIXTURE_DOCTOR_WRONG_JAVA: &str = include_str!("../fixtures/doctor-wrong-java.json");
const FIXTURE_DOCTOR_NO_JARS: &str = include_str!("../fixtures/doctor-no-jars.json");
const FIXTURE_INIT_RESULT: &str = include_str!("../fixtures/init-result.json");
const FIXTURE_SETUP_EVENTS: &str = include_str!("../fixtures/setup-events.jsonl");
const FIXTURE_DEV_EVENTS: &str = include_str!("../fixtures/dev-events.jsonl");

/// Which doctor fixture stub mode serves -- switchable at runtime (see `set_fake_doctor_scenario`
/// command) so every failure screen (missing Java, wrong version, unstaged jars) can be exercised
/// without restarting the Manager or ever having a real broken machine to test on.
pub static FAKE_DOCTOR_SCENARIO: Mutex<String> = Mutex::new(String::new());

pub fn fake_doctor_scenario_names() -> Vec<&'static str> {
    vec![
        "doctor-all-green",
        "doctor-missing-java",
        "doctor-wrong-java",
        "doctor-no-jars",
    ]
}

fn doctor_fixture_text(name: &str) -> &'static str {
    match name {
        "doctor-missing-java" => FIXTURE_DOCTOR_MISSING_JAVA,
        "doctor-wrong-java" => FIXTURE_DOCTOR_WRONG_JAVA,
        "doctor-no-jars" => FIXTURE_DOCTOR_NO_JARS,
        _ => FIXTURE_DOCTOR_ALL_GREEN,
    }
}

fn current_fake_doctor_scenario() -> String {
    let guard = FAKE_DOCTOR_SCENARIO.lock().expect("lock poisoned");
    if guard.is_empty() {
        "doctor-all-green".to_string()
    } else {
        guard.clone()
    }
}

// ---------------------------------------------------------------------------------------------
// Locating what to run
// ---------------------------------------------------------------------------------------------

/// `<version_dir>/NPDevCli/npdev_cli.py` -- the one entrypoint every command funnels through.
pub fn npdev_cli_path(version_dir: &Path) -> PathBuf {
    version_dir.join("NPDevCli").join("npdev_cli.py")
}

fn build_command(python_exe: &Path, npdev_cli: &Path, args: &[&str], java_home: Option<&str>, cwd: Option<&Path>) -> Command {
    let mut cmd = Command::new(python_exe);
    cmd.arg(npdev_cli);
    for a in args {
        cmd.arg(a);
    }
    if let Some(jh) = java_home {
        // M3's whole thesis: JAVA_HOME set ONLY in the spawned process's own environment, never
        // touching this machine's PATH, registry, or any system setting.
        cmd.env("JAVA_HOME", jh);
    }
    // Without this, npdev_cli.py's own default (repo_root().parent / "Build", where repo_root()
    // is the installed version's own directory) would scatter runtimehost-libs/npdev-ai under
    // each version's own parent instead of the one shared location SPEC.md's disk layout names
    // (`<manager_home>/runtimehost-libs`) -- setup would then rebuild the jars from scratch on
    // every version switch instead of sharing them where compatible.
    cmd.env("NPDEV_BUILD_ROOT", crate::state::manager_home());
    if let Some(dir) = cwd {
        cmd.current_dir(dir);
    }
    // `dev` boots a JVM as a grandchild of this process (`npdev dev` spawns it itself) -- confirmed
    // live that killing just the tracked child process left that JVM running, still bound to its
    // port. Isolating every spawned command into its own process group / job (harmless for the
    // one-shot commands too) is what lets `stop_dev` take the whole tree down together.
    #[cfg(windows)]
    {
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        cmd.creation_flags(CREATE_NEW_PROCESS_GROUP);
    }
    #[cfg(unix)]
    {
        cmd.process_group(0);
    }
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    cmd
}

fn parse_single_json(stdout: &[u8], stderr: &[u8], label: &str) -> Result<Value, String> {
    let text = String::from_utf8_lossy(stdout);
    let trimmed = text.trim();
    if trimmed.is_empty() {
        let err_text = String::from_utf8_lossy(stderr);
        let err_trimmed = err_text.trim();
        return Err(if err_trimmed.is_empty() {
            format!("{label}: no output on stdout (and nothing on stderr either)")
        } else {
            format!("{label}: no output on stdout -- stderr: {err_trimmed}")
        });
    }
    serde_json::from_str(trimmed).map_err(|e| format!("{label}: could not parse JSON ({e}): {trimmed}"))
}

// ---------------------------------------------------------------------------------------------
// doctor
// ---------------------------------------------------------------------------------------------

pub async fn run_doctor(python_exe: &Path, npdev_cli: &Path, java_home: Option<&str>) -> Result<Value, String> {
    if fake_mode() {
        let scenario = current_fake_doctor_scenario();
        return serde_json::from_str(doctor_fixture_text(&scenario))
            .map_err(|e| format!("fixture {scenario} did not parse: {e}"));
    }
    let output = build_command(python_exe, npdev_cli, &["doctor", "--json"], java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not run doctor: {e}"))?;
    parse_single_json(&output.stdout, &output.stderr, "doctor")
}

// ---------------------------------------------------------------------------------------------
// init
// ---------------------------------------------------------------------------------------------

pub async fn run_init(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    target_dir: &str,
) -> Result<Value, String> {
    if fake_mode() {
        return serde_json::from_str(FIXTURE_INIT_RESULT).map_err(|e| e.to_string());
    }
    let output = build_command(python_exe, npdev_cli, &["init", target_dir, "--json"], java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not run init: {e}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("npdev init failed: {stderr}"));
    }
    parse_single_json(&output.stdout, &output.stderr, "init")
}

// ---------------------------------------------------------------------------------------------
// setup / dev -- both stream JSON Lines; each parsed line is emitted as a Tauri event so the UI
// can update a progress bar / log view live instead of freezing until the whole thing finishes.
// ---------------------------------------------------------------------------------------------

/// `dev` boots a real app as a grandchild process (`npdev dev` spawns the JVM itself) -- on both
/// Windows and Unix, killing just the tracked `Child` (the `python npdev_cli.py dev` process)
/// leaves that JVM running as an orphan, still bound to the port. Confirmed live: killing the
/// tracked process directly left the booted app fully reachable. `job` (Windows) / process-group
/// membership (Unix) is what lets `stop_dev` actually take the whole tree down together.
pub struct RunningProcess {
    pub child: Child,
    #[cfg(windows)]
    pub job: Option<win32job::Job>,
}

#[cfg(windows)]
fn assign_to_new_job(child: &Child) -> Option<win32job::Job> {
    let job = win32job::Job::create().ok()?;
    let mut info = job.query_extended_limit_info().ok()?;
    info.limit_kill_on_job_close();
    job.set_extended_limit_info(&info).ok()?;
    job.assign_process(child.raw_handle()? as isize).ok()?;
    Some(job)
}

async fn stream_fixture_lines(app: &AppHandle, event_name: &str, fixture: &str) {
    for line in fixture.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Ok(value) = serde_json::from_str::<Value>(line) {
            let _ = app.emit(event_name, value);
            // Stub mode should still feel like real progress, not an instant dump.
            tokio::time::sleep(std::time::Duration::from_millis(150)).await;
        }
    }
}

pub async fn run_setup_streaming(app: AppHandle, python_exe: PathBuf, npdev_cli: PathBuf, java_home: Option<String>) -> Result<Value, String> {
    if fake_mode() {
        stream_fixture_lines(&app, "setup-event", FIXTURE_SETUP_EVENTS).await;
        let last_line = FIXTURE_SETUP_EVENTS.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("{}");
        return serde_json::from_str(last_line).map_err(|e| e.to_string());
    }
    run_setup_streaming_with(python_exe, npdev_cli, java_home, move |value| {
        let _ = app.emit("setup-event", value.clone());
    })
    .await
}

/// The real (never-fake-mode) body of `run_setup_streaming`, decoupled from `AppHandle` so
/// `--selftest` (I1, CLOSEOUT_PLAN.md) can drive the exact same `npdev setup` invocation with no
/// window -- staging the runtimehost jars + AI knowledge index is a precondition for `doctor` to
/// report all checks passing, the same as it is for a real user via the Ready screen's Setup step.
pub async fn run_setup_streaming_with(
    python_exe: PathBuf,
    npdev_cli: PathBuf,
    java_home: Option<String>,
    mut on_event: impl FnMut(&Value) + Send + 'static,
) -> Result<Value, String> {
    let mut cmd = build_command(&python_exe, &npdev_cli, &["setup", "--json"], java_home.as_deref(), None);
    let mut child = cmd.spawn().map_err(|e| format!("could not start setup: {e}"))?;
    let stdout = child.stdout.take().ok_or("setup: no stdout pipe")?;
    let stderr = child.stderr.take().ok_or("setup: no stderr pipe")?;
    let mut reader = BufReader::new(stdout).lines();

    // Narration (the human-readable "npdev setup: [1/3] ..." lines, and Gradle's own re-narrated
    // output) goes to the child's stderr in --json mode. Nothing here reads it -- and on Windows,
    // an anonymous pipe has a small OS buffer; if the child fills it while nobody drains the other
    // end, the WRITE blocks the whole (single-threaded) child process, which then never reaches
    // its next stdout write either. Drain it concurrently, even though this Rust side has nowhere
    // to show it yet, purely so the child can never stall on it.
    let mut stderr_reader = BufReader::new(stderr).lines();
    tokio::spawn(async move { while let Ok(Some(_)) = stderr_reader.next_line().await {} });

    let mut last: Option<Value> = None;
    while let Some(line) = reader.next_line().await.map_err(|e| e.to_string())? {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Ok(value) = serde_json::from_str::<Value>(line) {
            on_event(&value);
            last = Some(value);
        }
    }
    let status = child.wait().await.map_err(|e| e.to_string())?;
    match last {
        Some(value) if status.success() => Ok(value),
        Some(value) => Err(format!(
            "setup exited with {status}: {}",
            value.get("ok").map(|v| v.to_string()).unwrap_or_default()
        )),
        None => Err(format!("setup produced no JSON output (exit {status})")),
    }
}

/// `dev` is long-running (watches the model forever) -- the returned `RunningProcess` is stashed
/// in `AppState.running_dev` so a later `stop_dev` command can kill it. Never buffers to the end:
/// a frozen window would be indistinguishable from a crash during the first ~45s cycle.
pub async fn start_dev_streaming(
    app: AppHandle,
    python_exe: PathBuf,
    npdev_cli: PathBuf,
    java_home: Option<String>,
    model_dir: PathBuf,
    port: u16,
) -> Result<Option<RunningProcess>, String> {
    if fake_mode() {
        let app2 = app.clone();
        tauri::async_runtime::spawn(async move {
            stream_fixture_lines(&app2, "dev-event", FIXTURE_DEV_EVENTS).await;
        });
        return Ok(None);
    }

    let port_str = port.to_string();
    let mut cmd = build_command(
        &python_exe,
        &npdev_cli,
        &["dev", "--json", "--port", &port_str],
        java_home.as_deref(),
        Some(&model_dir),
    );
    let mut child = cmd.spawn().map_err(|e| format!("could not start dev: {e}"))?;
    #[cfg(windows)]
    let job = assign_to_new_job(&child);
    let stdout = child.stdout.take().ok_or("dev: no stdout pipe")?;
    let stderr = child.stderr.take().ok_or("dev: no stderr pipe")?;
    let mut reader = BufReader::new(stdout).lines();

    // Same reasoning as run_setup_streaming: drain stderr concurrently so the child can never
    // stall on a full pipe buffer even though nothing here surfaces it yet.
    let mut stderr_reader = BufReader::new(stderr).lines();
    tokio::spawn(async move { while let Ok(Some(_)) = stderr_reader.next_line().await {} });

    tauri::async_runtime::spawn(async move {
        while let Ok(Some(line)) = reader.next_line().await {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            if let Ok(value) = serde_json::from_str::<Value>(line) {
                let _ = app.emit("dev-event", value);
            }
        }
        let _ = app.emit("dev-event", serde_json::json!({"kind": "stopped"}));
    });

    Ok(Some(RunningProcess {
        child,
        #[cfg(windows)]
        job,
    }))
}

/// Kills the whole process tree `start_dev_streaming` started, not just the tracked `Child` --
/// confirmed live that killing only that one process left the booted JVM running as an orphan,
/// still bound to its port. On Windows, closing the job (with kill-on-close already set) takes
/// the JVM down with it; the explicit `child.kill()` is a redundant fallback for when the job
/// could not be created. On Unix, the whole process group gets SIGKILL.
pub async fn stop_running_process(mut proc: RunningProcess) -> Result<(), String> {
    #[cfg(unix)]
    {
        if let Some(pid) = proc.child.id() {
            unsafe {
                libc::kill(-(pid as i32), libc::SIGKILL);
            }
        }
    }
    #[cfg(windows)]
    {
        drop(proc.job.take());
    }
    let _ = proc.child.kill().await;
    Ok(())
}
