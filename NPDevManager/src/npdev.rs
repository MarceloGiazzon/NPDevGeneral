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
// deps-and-java/PLAN.md W1.7: hand-authored (no Java 11/22 install on the authoring machine to
// capture live from) to match npdev_cli.py's new >=17-passes/warn-on->17 doctor shape -- every
// other sibling fixture in this list IS a live capture; these two are the documented exception.
const FIXTURE_DOCTOR_ACCEPTABLE_NEWER_JAVA: &str = include_str!("../fixtures/doctor-acceptable-newer-java.json");
const FIXTURE_DOCTOR_NO_JARS: &str = include_str!("../fixtures/doctor-no-jars.json");
// M13. Both captured live on 2026-08-09 from `npdev db test-connection --json` against a real
// Postgres in a container (`npdev-local-pg`, host port 15432): the OK one with its real credentials,
// the refused one by pointing the same command at port 59999 with nothing listening. Neither is
// hand-written -- a hand-written fixture is a guess about the shape the CLI emits, and the whole
// reason stub mode exists is to build the UI against what the CLI ACTUALLY returns.
const FIXTURE_DB_TEST_CONNECTION_OK: &str = include_str!("../fixtures/db-test-connection-ok.json");
const FIXTURE_DB_TEST_CONNECTION_REFUSED: &str = include_str!("../fixtures/db-test-connection-refused.json");
const FIXTURE_INIT_RESULT: &str = include_str!("../fixtures/init-result.json");
const FIXTURE_SETUP_EVENTS: &str = include_str!("../fixtures/setup-events.jsonl");
const FIXTURE_DEV_EVENTS: &str = include_str!("../fixtures/dev-events.jsonl");
// MONITOR_PLAN B1/D1/G1. Every one of these was CAPTURED from the real CLI on 2026-08-10 (see each
// file's own `_captured` header) -- never hand-written. A hand-written fixture is a guess about the
// shape the CLI emits, and the entire reason stub mode exists is to build the UI against what the
// CLI ACTUALLY returns.
const FIXTURE_MONITOR_SCAN_MIXED: &str = include_str!("../fixtures/monitor-scan-mixed.json");
const FIXTURE_MONITOR_SCAN_EMPTY: &str = include_str!("../fixtures/monitor-scan-empty.json");
const FIXTURE_MONITOR_PROBE: &str = include_str!("../fixtures/monitor-probe.json");
const FIXTURE_MONITOR_ENGINE_RUNNING: &str = include_str!("../fixtures/monitor-engine-running.json");
const FIXTURE_MONITOR_ENGINE_STOPPED: &str = include_str!("../fixtures/monitor-engine-stopped.json");
const FIXTURE_MONITOR_ENGINE_MISSING: &str = include_str!("../fixtures/monitor-engine-missing.json");
const FIXTURE_MONITOR_LOGS: &str = include_str!("../fixtures/monitor-logs.json");
/// Phase F. The one fixture here that is hand-authored rather than captured, and the file says why
/// in its own `_captured` header: capturing it means paying a third-party provider with a real key.
/// Same documented-exception shape as the two doctor fixtures above.
pub const FIXTURE_PROMPTER_GENERATE: &str = include_str!("../fixtures/prompter-generate.json");
const FIXTURE_EXPLORE_LIST: &str = include_str!("../fixtures/explore-list.json");
const FIXTURE_EXPLORE_RUN_GREEN: &str = include_str!("../fixtures/explore-run-green.json");
const FIXTURE_EXPLORE_RUN_RED: &str = include_str!("../fixtures/explore-run-red.json");
const FIXTURE_EXPLORE_VALIDATE_OK: &str = include_str!("../fixtures/explore-validate-ok.json");
const FIXTURE_EXPLORE_VALIDATE_BAD: &str = include_str!("../fixtures/explore-validate-bad.json");
const FIXTURE_EXPLORE_PREFLIGHT: &str = include_str!("../fixtures/explore-preflight.json");

/// Which doctor fixture stub mode serves -- switchable at runtime (see `set_fake_doctor_scenario`
/// command) so every failure screen (missing Java, wrong version, unstaged jars) can be exercised
/// without restarting the Manager or ever having a real broken machine to test on.
pub static FAKE_DOCTOR_SCENARIO: Mutex<String> = Mutex::new(String::new());

pub fn fake_doctor_scenario_names() -> Vec<&'static str> {
    vec![
        "doctor-all-green",
        "doctor-missing-java",
        "doctor-wrong-java",
        "doctor-acceptable-newer-java",
        "doctor-no-jars",
    ]
}

fn doctor_fixture_text(name: &str) -> &'static str {
    match name {
        "doctor-missing-java" => FIXTURE_DOCTOR_MISSING_JAVA,
        "doctor-wrong-java" => FIXTURE_DOCTOR_WRONG_JAVA,
        "doctor-acceptable-newer-java" => FIXTURE_DOCTOR_ACCEPTABLE_NEWER_JAVA,
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
        // ...which means JAVA_HOME deliberately DISAGREES with whatever `java` is on PATH, on any
        // machine that already has one. `npdev doctor`'s java-home-agreement check reads that
        // disagreement as a fault -- correctly, for a terminal user who set JAVA_HOME by hand and
        // now has Gradle silently using a different JDK than they think.
        //
        // Under the Manager it is the DESIGN, not a fault. So the Manager says so, rather than
        // leaving doctor to infer it from the shape of the path: inferring intent from where files
        // happen to live is REG-144's family, and this is the same question ("is this ours?") that
        // eleven build-root resolvers got wrong by guessing.
        //
        // Found by CI: manager-db-toolbox runs on a runner that ships a system JDK -- the same shape
        // as an ordinary developer's machine -- so doctor failed there while the bare container
        // (no system java, nothing to disagree with) stayed green. Every user who already has Java
        // would have hit this on their first Ready screen.
        cmd.env("NPDEV_MANAGED_JDK", "1");
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
        // Without this, every button press flashes a console window: python.exe is a
        // console-subsystem binary, and Windows allocates it one by default when a GUI
        // (no-console) parent like this Tauri app spawns it without CREATE_NO_WINDOW.
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW);
    }
    #[cfg(unix)]
    {
        cmd.process_group(0);
    }
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    cmd
}

/// The Manager runs whatever NPDev version the user INSTALLED, which may be older than this window.
/// argparse's answer to an unknown verb is a 40-line usage dump on stderr, and showing that to
/// somebody who pressed a button is telling them nothing they can act on.
///
/// Found by `--selftest` on 2026-08-10: the Monitor's very first call failed against the installed
/// `beta1.14`, which predates `npdev monitor`, and the message was the usage dump. The capability is
/// genuinely absent -- the honest report is which version is installed and what to do, not a stack
/// of choices.
fn version_too_old_message(stderr: &str, label: &str) -> Option<String> {
    if !stderr.contains("invalid choice") {
        return None;
    }
    let verb = label.split_whitespace().next().unwrap_or(label);
    if !stderr.contains(&format!("'{verb}'")) {
        return None;
    }
    Some(format!(
        "the installed NPDev version has no `npdev {verb}` command. This screen needs a newer one -- \
         install it on the Versions tab, then come back. (Nothing is broken: the feature simply does \
         not exist in the version currently selected.)"
    ))
}

fn parse_single_json(stdout: &[u8], stderr: &[u8], label: &str) -> Result<Value, String> {
    let text = String::from_utf8_lossy(stdout);
    let trimmed = text.trim();
    if trimmed.is_empty() {
        let err_text = String::from_utf8_lossy(stderr);
        let err_trimmed = err_text.trim();
        if let Some(message) = version_too_old_message(err_trimmed, label) {
            return Err(message);
        }
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

/// `app_dir` is M15. Doctor's six database checks are app-scoped -- with no `--app`, doctor looks
/// for a `db.definition.json` in its own CWD, which for a Manager launched from a Start-menu
/// shortcut is never an app directory. So the Ready screen has been ABLE to render those six rows
/// since W5.3 and has never once had a database row to show: the renderer was wired, the argument
/// that produces the data was not.
pub async fn run_doctor(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    app_dir: Option<&str>,
) -> Result<Value, String> {
    if fake_mode() {
        let scenario = current_fake_doctor_scenario();
        return serde_json::from_str(doctor_fixture_text(&scenario))
            .map_err(|e| format!("fixture {scenario} did not parse: {e}"));
    }
    let mut args: Vec<&str> = vec!["doctor", "--json"];
    if let Some(dir) = app_dir.filter(|d| !d.is_empty()) {
        args.push("--app");
        args.push(dir);
    }
    let output = build_command(python_exe, npdev_cli, &args, java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not run doctor: {e}"))?;
    parse_single_json(&output.stdout, &output.stderr, "doctor")
}

/// M13: the same five database checks, against a connection the user is still TYPING.
///
/// Deliberately `npdev db test-connection` rather than any logic here: the CLI shares one code path
/// with `doctor`'s database checks, so this button and the Ready screen cannot reach different
/// conclusions about the same database. A Rust reimplementation would be a second opinion, and the
/// point of the button is to be the SAME opinion, earlier.
#[allow(clippy::too_many_arguments)]
pub async fn run_db_test_connection(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    engine: &str,
    db_host: Option<&str>,
    db_port: Option<u16>,
    db_user: Option<&str>,
    db_password: Option<&str>,
) -> Result<Value, String> {
    if fake_mode() {
        // Port 59999 is the refused fixture purely so the failure screen is reachable in stub mode
        // with no broken machine to hand -- the same reason the doctor scenarios exist.
        let text = if db_port == Some(59999) {
            FIXTURE_DB_TEST_CONNECTION_REFUSED
        } else {
            FIXTURE_DB_TEST_CONNECTION_OK
        };
        return serde_json::from_str(text).map_err(|e| format!("fixture did not parse: {e}"));
    }
    let mut args: Vec<String> = vec![
        "db".into(),
        "test-connection".into(),
        "--json".into(),
        "--engine".into(),
        engine.into(),
    ];
    // Same rule as run_init: forward only what the user actually typed, so the CLI's per-engine
    // defaults stay in charge. Probing a blank host would test something the app will never use.
    for (flag, value) in [("--db-host", db_host), ("--db-user", db_user), ("--db-password", db_password)] {
        if let Some(value) = value.filter(|v| !v.is_empty()) {
            args.push(flag.into());
            args.push(value.into());
        }
    }
    if let Some(port) = db_port.filter(|p| *p > 0) {
        args.push("--db-port".into());
        args.push(port.to_string());
    }
    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let output = build_command(python_exe, npdev_cli, &borrowed, java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not test the connection: {e}"))?;
    // Exit 1 here means "the checks ran and some FAILED", which is a result to render, not an error
    // to raise. Only a total absence of output is a real failure -- parse_single_json says so.
    parse_single_json(&output.stdout, &output.stderr, "db test-connection")
}

// ---------------------------------------------------------------------------------------------
// init
// ---------------------------------------------------------------------------------------------

/// The database choices, and the honest status of each, **read from the CLI**.
///
/// storage/FULL_SUPPORT_PLAN.md W5.3, requirement 1: the engine picker is "driven by the CLI's
/// engine list, not a hardcoded copy". A copy here would be free to drift the day an engine's status
/// changes -- and the status is the whole point, because BREAKING.md's "selectable but NOT
/// supported" must reach the user AT THE POINT OF CHOICE. A dropdown that silently offers MySQL is
/// the silent-answer defect in UI form.
pub async fn run_engines(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
) -> Result<Value, String> {
    let output = build_command(python_exe, npdev_cli, &["engines", "--json"], java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not list engines: {e}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("npdev engines failed: {stderr}"));
    }
    parse_single_json(&output.stdout, &output.stderr, "engines")
}

/// Scaffold an app. `engine` and the connection fields are optional so the no-engine call is
/// byte-identical to what it always was -- the CLI's own default is `h2local`, which is what this
/// used to produce implicitly.
#[allow(clippy::too_many_arguments)]
pub async fn run_init(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    target_dir: &str,
    engine: Option<&str>,
    db_host: Option<&str>,
    db_port: Option<u16>,
    db_user: Option<&str>,
    db_password: Option<&str>,
    externally_provisioned: bool,
) -> Result<Value, String> {
    if fake_mode() {
        return serde_json::from_str(FIXTURE_INIT_RESULT).map_err(|e| e.to_string());
    }
    let mut args: Vec<String> = vec!["init".into(), target_dir.into(), "--json".into()];
    if let Some(value) = engine {
        args.push("--engine".into());
        args.push(value.into());
    }
    // Each connection field is forwarded only when the user actually typed one, so the CLI's own
    // per-engine defaults stay in charge. Passing an empty --db-host would override a good default
    // with nothing, which is the shape of bug where a UI "helpfully" sends blanks.
    for (flag, value) in [("--db-host", db_host), ("--db-user", db_user), ("--db-password", db_password)] {
        if let Some(value) = value.filter(|v| !v.is_empty()) {
            args.push(flag.into());
            args.push(value.into());
        }
    }
    if let Some(port) = db_port.filter(|p| *p > 0) {
        args.push("--db-port".into());
        args.push(port.to_string());
    }
    // STOR-15. Unlike the connection fields above, this is NOT "forward only when the user typed
    // something": it is a store_true switch, so sending it means yes and omitting it means no --
    // both real answers. The CLI refuses it for embedded engines, which is why the checkbox lives
    // inside the connection group that appears only for engines that connect somewhere.
    if externally_provisioned {
        args.push("--externally-provisioned".into());
    }
    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let output = build_command(python_exe, npdev_cli, &borrowed, java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not run init: {e}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("npdev init failed: {stderr}"));
    }
    parse_single_json(&output.stdout, &output.stderr, "init")
}

/// Generate an app's code from its model -- the step that WRITES the `_ops` toolbox.
///
/// Used by `--selftest` (not by a screen): the five database operations read
/// `_ops/resolved-db-plan.json`, which does not exist until an app has been generated, so a harness
/// that wants to drive the toolbox has to produce one first. Kept here rather than in selftest.rs so
/// it goes through `build_command` like every other invocation -- JAVA_HOME and NPDEV_BUILD_ROOT set
/// the same way, which is exactly the plumbing a hand-rolled `Command` in the test would get subtly
/// wrong.
pub async fn run_generate_app(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    model: &str,
    config: &str,
    output: &str,
) -> Result<(), String> {
    let output_result = build_command(
        python_exe,
        npdev_cli,
        &["generate", "app", "--model", model, "--config", config, "--output", output],
        java_home,
        None,
    )
    .output()
    .await
    .map_err(|e| format!("could not run generate app: {e}"))?;
    if !output_result.status.success() {
        let stderr = String::from_utf8_lossy(&output_result.stderr);
        let stdout = String::from_utf8_lossy(&output_result.stdout);
        return Err(format!("npdev generate app failed: {}",
                           if stderr.trim().is_empty() { stdout.trim() } else { stderr.trim() }));
    }
    Ok(())
}

/// M14: one of the five database operations, run through the CLI.
///
/// The Manager deliberately does NOT locate `_ops` or spawn PowerShell itself. Both would be a
/// second copy of a question the CLI already answers -- and "where does the build output live" is
/// the question eleven copies of got three different answers in one checkout (REG-144). The CLI
/// finds the generated script and runs it; this is a pipe.
pub async fn run_db_operation(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    app_dir: &str,
    operation: &str,
    confirm: Option<&str>,
) -> Result<Value, String> {
    if fake_mode() {
        return Ok(serde_json::json!({
            "schemaVersion": "npdev-cli-result.v1",
            "command": format!("db {operation}"),
            "ok": true,
            "exitCode": 0,
            "output": format!("STUB MODE -- `npdev db {operation}` was not run."),
        }));
    }
    let mut args: Vec<String> = vec!["db".into(), operation.into(), "--app".into(), app_dir.into(), "--json".into()];
    if let Some(token) = confirm.filter(|t| !t.is_empty()) {
        args.push("--confirm".into());
        args.push(token.into());
    }
    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let output = build_command(python_exe, npdev_cli, &borrowed, java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not run db {operation}: {e}"))?;
    // A non-zero exit is a RESULT here (the database is not running, the reset was refused), not a
    // transport failure -- the JSON carries `ok` and the script's own words. Only genuinely empty
    // output is an error, which parse_single_json reports with whatever stderr said.
    parse_single_json(&output.stdout, &output.stderr, &format!("db {operation}"))
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

// ---------------------------------------------------------------------------------------------
// MONITOR_PLAN B1 / D1: `npdev monitor` and `npdev explore`, wrapped.
//
// Every function below is the same three lines -- build argv, run the CLI, parse one JSON object.
// That is the whole point. The Monitor's discovery rule, its health verdict, the exploration
// verdict, the retention policy and the engine-detection order all live in the CLI, so a terminal
// user has them and this window cannot form a second opinion. Two opinions about "is this app
// running" is the class of defect REG-144 cost twelve days of red CI.
// ---------------------------------------------------------------------------------------------

/// Runs the CLI and parses one JSON object, treating a non-zero exit as a RESULT rather than a
/// transport error whenever the command still produced JSON -- `_emit_json_error` guarantees it
/// does. Only genuinely empty stdout is a failure.
async fn run_json(
    python_exe: &Path,
    npdev_cli: &Path,
    args: &[String],
    java_home: Option<&str>,
    label: &str,
) -> Result<Value, String> {
    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let output = build_command(python_exe, npdev_cli, &borrowed, java_home, None)
        .output()
        .await
        .map_err(|e| format!("could not run {label}: {e}"))?;
    parse_single_json(&output.stdout, &output.stderr, label)
}

pub async fn run_monitor_scan(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    paths: &[String],
    include_info: bool,
) -> Result<Value, String> {
    if fake_mode() {
        let text = if paths.is_empty() { FIXTURE_MONITOR_SCAN_EMPTY } else { FIXTURE_MONITOR_SCAN_MIXED };
        return serde_json::from_str(text).map_err(|e| format!("fixture did not parse: {e}"));
    }
    if paths.is_empty() {
        // Not an error and not an empty scan of the whole disk: with no inspect paths configured the
        // Monitor shows only the apps the Manager itself created, which is the honest answer. Guessing
        // where a user keeps their apps is how a path literal gets born.
        return Ok(serde_json::json!({
            "schemaVersion": "npdev-monitor-scan.v1", "command": "monitor scan", "ok": true,
            "searched": [], "apps": []
        }));
    }
    let mut args = vec!["monitor".to_string(), "scan".to_string(), "--json".to_string(),
                        "--paths".to_string(), paths.join(";")];
    if include_info {
        args.push("--include-info".to_string());
    }
    run_json(python_exe, npdev_cli, &args, java_home, "monitor scan").await
}

pub async fn run_monitor_probe(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    app_dir: &str,
    include_info: bool,
) -> Result<Value, String> {
    if fake_mode() {
        return serde_json::from_str(FIXTURE_MONITOR_PROBE).map_err(|e| format!("fixture did not parse: {e}"));
    }
    let mut args = vec!["monitor".to_string(), "probe".to_string(), "--json".to_string(),
                        "--app-dir".to_string(), app_dir.to_string()];
    if include_info {
        args.push("--include-info".to_string());
    }
    run_json(python_exe, npdev_cli, &args, java_home, "monitor probe").await
}

/// D9. Three fixtures rather than one, because the three states drive three DIFFERENT screens --
/// enabled, "Start engine", and an honest "not installed" -- and the only way to be sure all three
/// render is to be able to reach all three without uninstalling anything.
pub async fn run_monitor_engine(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    port: u16,
    configured_root: Option<&str>,
    fake_scenario: &str,
) -> Result<Value, String> {
    if fake_mode() {
        let text = match fake_scenario {
            "engine-stopped" => FIXTURE_MONITOR_ENGINE_STOPPED,
            "engine-missing" => FIXTURE_MONITOR_ENGINE_MISSING,
            _ => FIXTURE_MONITOR_ENGINE_RUNNING,
        };
        return serde_json::from_str(text).map_err(|e| format!("fixture did not parse: {e}"));
    }
    let mut args = vec!["monitor".to_string(), "engine".to_string(), "--json".to_string(),
                        "--port".to_string(), port.to_string()];
    if let Some(root) = configured_root.filter(|r| !r.is_empty()) {
        args.push("--root".to_string());
        args.push(root.to_string());
    }
    run_json(python_exe, npdev_cli, &args, java_home, "monitor engine").await
}

pub async fn run_monitor_logs(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    app_dir: &str,
    source: &str,
    tail: u32,
) -> Result<Value, String> {
    if fake_mode() {
        return serde_json::from_str(FIXTURE_MONITOR_LOGS).map_err(|e| format!("fixture did not parse: {e}"));
    }
    let args = vec!["monitor".to_string(), "logs".to_string(), "--json".to_string(),
                    "--app-dir".to_string(), app_dir.to_string(),
                    "--source".to_string(), source.to_string(),
                    "--tail".to_string(), tail.to_string()];
    run_json(python_exe, npdev_cli, &args, java_home, "monitor logs").await
}

pub async fn run_monitor_logs_export(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    app_dir: &str,
    out_zip: &str,
) -> Result<Value, String> {
    if fake_mode() {
        return Ok(serde_json::json!({
            "schemaVersion": "npdev-monitor-logs-export.v1", "command": "monitor logs export",
            "ok": true, "zip": out_zip, "bytes": 0,
            "included": ["STUB MODE -- no bundle was written."],
        }));
    }
    let args = vec!["monitor".to_string(), "logs".to_string(), "export".to_string(),
                    "--json".to_string(), "--app-dir".to_string(), app_dir.to_string(),
                    "--out".to_string(), out_zip.to_string()];
    run_json(python_exe, npdev_cli, &args, java_home, "monitor logs export").await
}

pub async fn run_explore(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    args: Vec<String>,
    label: &str,
) -> Result<Value, String> {
    run_explore_with_env(python_exe, npdev_cli, java_home, args, label, None).await
}

/// `run_explore` plus one environment variable for the child process.
///
/// It exists so a SECRET can reach the CLI without going on its argv, where every process listing on
/// the machine can read it. The one caller passes `NPDEV_AI_API_KEY`; the variable name is a
/// parameter rather than baked in so this stays a general "give the child one env var" and not a
/// key-shaped hole.
pub async fn run_explore_with_env(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    args: Vec<String>,
    label: &str,
    env: Option<(String, String)>,
) -> Result<Value, String> {
    if fake_mode() {
        let text = match label {
            "explore list" => FIXTURE_EXPLORE_LIST,
            "explore preflight" => FIXTURE_EXPLORE_PREFLIGHT,
            "explore validate" => {
                if args.iter().any(|a| a.contains("invalid")) { FIXTURE_EXPLORE_VALIDATE_BAD }
                else { FIXTURE_EXPLORE_VALIDATE_OK }
            }
            "explore run" => {
                // Reaching the RED screen must not require a broken app -- the same reason the doctor
                // scenarios exist.
                if args.iter().any(|a| a.contains("red")) { FIXTURE_EXPLORE_RUN_RED }
                else { FIXTURE_EXPLORE_RUN_GREEN }
            }
            _ => FIXTURE_EXPLORE_LIST,
        };
        return serde_json::from_str(text).map_err(|e| format!("fixture did not parse: {e}"));
    }
    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let mut command = build_command(python_exe, npdev_cli, &borrowed, java_home, None);
    if let Some((name, value)) = env {
        command.env(name, value);
    }
    let output = command
        .output()
        .await
        .map_err(|e| format!("could not run {label}: {e}"))?;
    parse_single_json(&output.stdout, &output.stderr, label)
}

/// B5 + D10 source 2: run one `_ops` script, streaming its output as `ops-event` so the window shows
/// a build happening instead of freezing for two minutes. The CLI also tees every line to
/// `<app>/logs/ops-<script>-<timestamp>.log`, so a closed window is not a lost run.
pub async fn run_ops_script_streaming(
    app: AppHandle,
    python_exe: PathBuf,
    npdev_cli: PathBuf,
    java_home: Option<String>,
    app_dir: String,
    script: String,
    confirm: Option<String>,
) -> Result<Option<RunningProcess>, String> {
    if fake_mode() {
        let app2 = app.clone();
        let script2 = script.clone();
        tauri::async_runtime::spawn(async move {
            for line in [
                format!("STUB MODE -- `npdev monitor ops --script {script2}` was not run."),
                "This is what the streamed output looks like.".to_string(),
            ] {
                let _ = app2.emit("ops-event", serde_json::json!({"kind": "line", "text": line}));
                tokio::time::sleep(std::time::Duration::from_millis(200)).await;
            }
            let _ = app2.emit("ops-event", serde_json::json!({"kind": "done", "exitCode": 0}));
        });
        return Ok(None);
    }

    let mut args: Vec<String> = vec!["monitor".into(), "ops".into(), "--json".into(),
                                     "--app-dir".into(), app_dir.clone(), "--script".into(), script.clone()];
    if let Some(token) = confirm.filter(|t| !t.is_empty()) {
        args.push("--confirm".into());
        args.push(token);
    }
    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let mut cmd = build_command(&python_exe, &npdev_cli, &borrowed, java_home.as_deref(), None);
    let mut child = cmd.spawn().map_err(|e| format!("could not start {script}: {e}"))?;
    #[cfg(windows)]
    let job = assign_to_new_job(&child);
    let stdout = child.stdout.take().ok_or("ops: no stdout pipe")?;
    let stderr = child.stderr.take().ok_or("ops: no stderr pipe")?;
    let mut reader = BufReader::new(stdout).lines();
    // Same reason as run_setup_streaming: a full stderr pipe blocks the child's next stdout write.
    let mut stderr_reader = BufReader::new(stderr).lines();
    tokio::spawn(async move { while let Ok(Some(_)) = stderr_reader.next_line().await {} });

    let app2 = app.clone();
    let script2 = script.clone();
    tauri::async_runtime::spawn(async move {
        let mut last: Option<Value> = None;
        while let Ok(Some(line)) = reader.next_line().await {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            if let Ok(value) = serde_json::from_str::<Value>(line) {
                let _ = app2.emit("ops-event", value.clone());
                last = Some(value);
            }
        }
        let exit_code = last
            .as_ref()
            .and_then(|v| v.get("exitCode"))
            .and_then(|v| v.as_i64())
            .unwrap_or(0);
        let _ = app2.emit("ops-event", serde_json::json!({
            "kind": "done", "script": script2, "exitCode": exit_code,
            "logFile": last.and_then(|v| v.get("logFile").cloned()),
        }));
    });

    Ok(Some(RunningProcess {
        child,
        #[cfg(windows)]
        job,
    }))
}

/// R2: the engine outlives requests by design, so the Manager starts it through the process registry
/// and it dies with the window. Started via the CLI, so the SSRF allowlist is composed in exactly one
/// place (R4) -- the UI never assembles origins.
pub async fn start_engine_streaming(
    python_exe: PathBuf,
    npdev_cli: PathBuf,
    java_home: Option<String>,
    root: String,
    port: u16,
    origins: Vec<String>,
) -> Result<RunningProcess, String> {
    let mut args: Vec<String> = vec!["monitor".into(), "engine-start".into(), "--json".into(),
                                     "--root".into(), root, "--port".into(), port.to_string()];
    for origin in origins.iter().filter(|o| !o.is_empty()) {
        args.push("--allow-origin".into());
        args.push(origin.clone());
    }
    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let mut cmd = build_command(&python_exe, &npdev_cli, &borrowed, java_home.as_deref(), None);
    let child = cmd.spawn().map_err(|e| format!("could not start the exploration engine: {e}"))?;
    #[cfg(windows)]
    let job = assign_to_new_job(&child);
    Ok(RunningProcess {
        child,
        #[cfg(windows)]
        job,
    })
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
