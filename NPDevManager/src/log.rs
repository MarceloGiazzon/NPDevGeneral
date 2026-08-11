//! The Manager's own log file. MONITOR_PLAN D10, source 3.
//!
//! WHAT WAS BROKEN, MEASURED 2026-08-10
//! ------------------------------------
//! `state.rs::logs_dir()` has existed since M0-M8 and is created on every startup. **Nothing ever
//! wrote into it** -- `Cargo.toml` had no logging dependency of any kind. Meanwhile `HANDOVER.md`
//! §5, the escape hatch for "it will not start at all", told a tester to send the `.log` files from
//! `%LOCALAPPDATA%\npdev-manager\` -- a directory that is not even the Manager's home (that is
//! `%LOCALAPPDATA%\NPDev`). So the single most important support path pointed at the wrong empty
//! directory, for files nobody wrote.
//!
//! WHY THIS IS A MODULE AND NOT A CRATE
//! ------------------------------------
//! MONITOR_PLAN B7 says "the Manager gains a logging crate". This is ~90 lines instead, and the
//! trade is deliberate: what the Manager needs is one append-only file per day, a size cap, and a
//! line that says what happened. `tracing` + `tracing-subscriber` + `tracing-appender` is three more
//! crates on a cold build that already takes ~17 minutes, to get filtering and spans this window
//! has no use for. If structured levels or per-module filtering are ever wanted, swapping this for
//! a crate is a contained change: everything outside this file calls `log::info!`-shaped helpers.
//!
//! Never logs a credential. `db_operation` and `create_app` carry database passwords, so callers
//! log the OPERATION and the app, never the arguments.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// Rotate at 5 MB. Small enough that a tester can attach it, large enough to hold a session.
const MAX_BYTES: u64 = 5 * 1024 * 1024;
/// Keep this many rotated files. Bounded, and the bound is stated rather than silent -- an
/// unbounded log directory on a user's machine is a bug that shows up months later.
const KEEP_ROTATIONS: usize = 3;

static WRITE_LOCK: Mutex<()> = Mutex::new(());

pub fn log_file() -> PathBuf {
    crate::state::logs_dir().join("manager.log")
}

fn timestamp() -> String {
    // Seconds since epoch rather than a formatted date: `chrono` is not a dependency of this crate,
    // and a wrong-looking date is worse than an unambiguous number. `versions::chrono_now_iso`
    // exists for the places that need a human date.
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    crate::versions::iso_from_unix_seconds(seconds)
}

fn rotate_if_needed(path: &PathBuf) {
    let too_big = std::fs::metadata(path).map(|m| m.len() >= MAX_BYTES).unwrap_or(false);
    if !too_big {
        return;
    }
    for index in (1..KEEP_ROTATIONS).rev() {
        let from = path.with_extension(format!("log.{index}"));
        let to = path.with_extension(format!("log.{}", index + 1));
        let _ = std::fs::rename(&from, &to);
    }
    let _ = std::fs::rename(path, path.with_extension("log.1"));
}

/// Append one line. Failure to log is never allowed to fail the operation being logged -- a Manager
/// that refuses to start an app because its log file is read-only would be a worse product than one
/// that quietly has no log.
pub fn write(level: &str, message: &str) {
    let _guard = WRITE_LOCK.lock();
    let path = log_file();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    rotate_if_needed(&path);
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(file, "{} {:<5} {}", timestamp(), level, message);
    }
}

pub fn info(message: impl AsRef<str>) {
    write("INFO", message.as_ref());
}

pub fn warn(message: impl AsRef<str>) {
    write("WARN", message.as_ref());
}

pub fn error(message: impl AsRef<str>) {
    write("ERROR", message.as_ref());
}

/// Everything the first line of a support conversation needs, written once at startup so a log a
/// tester sends is self-describing rather than a stream of events about an unknown machine.
pub fn startup_banner() {
    info(format!(
        "NPDev Manager {} starting -- os={} arch={} home={}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
        std::env::consts::ARCH,
        crate::state::manager_home().display()
    ));
}
